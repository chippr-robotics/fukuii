package com.chipprbots.ethereum.blockchain.sync.snap.actors

import org.apache.pekko.actor.{Actor, ActorLogging, ActorRef, Props, SupervisorStrategy, OneForOneStrategy}
import org.apache.pekko.actor.SupervisorStrategy._
import org.apache.pekko.util.ByteString

import scala.collection.mutable
import scala.concurrent.duration._

import com.chipprbots.ethereum.blockchain.sync.snap._
import com.chipprbots.ethereum.db.storage.{DeferredWriteMptStorage, MptStorage}
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.utils.ByteStringUtils.ByteStringOps

/** AccountRangeCoordinator manages account range download workers.
  *
  * Now contains ALL business logic previously in AccountRangeDownloader.
  * This is the sole implementation - no synchronized fallback.
  *
  * Responsibilities:
  * - Maintain queue of pending account range tasks
  * - Distribute tasks to worker actors
  * - Verify Merkle proofs for downloaded accounts
  * - Store accounts to MPT storage
  * - Identify contract accounts for bytecode download
  * - Finalize state trie after completion
  * - Report progress to SNAPSyncController
  * - Handle worker failures with supervision
  *
  * @param stateRoot
  *   State root hash for account sync
  * @param networkPeerManager
  *   Actor for sending network messages
  * @param requestTracker
  *   Tracker for requests/responses
  * @param mptStorage
  *   Storage for persisting accounts
  * @param concurrency
  *   Number of worker actors to spawn
  * @param snapSyncController
  *   Parent controller to notify of completion
  */
class AccountRangeCoordinator(
    stateRoot: ByteString,
    networkPeerManager: ActorRef,
    requestTracker: SNAPRequestTracker,
    mptStorage: MptStorage,
    concurrency: Int,
    snapSyncController: ActorRef
) extends Actor
    with ActorLogging {

  import Messages._

  private val peerFailureCounts = mutable.Map.empty[com.chipprbots.ethereum.network.PeerId, Int]
  private val peerCooldownUntilMillis = mutable.Map.empty[com.chipprbots.ethereum.network.PeerId, Long]
  private val maxPeerCooldown: FiniteDuration = 10.minutes
  private val basePeerCooldown: FiniteDuration = 2.seconds

  private def nowMillis: Long = System.currentTimeMillis()

  private def isPeerCoolingDown(peer: Peer): Boolean =
    peerCooldownUntilMillis.get(peer.id).exists(_ > nowMillis)

  private def recordPeerCooldown(peer: Peer, reason: String): Option[FiniteDuration] = {
    // Only penalize the specific protocol violation that causes tight retry loops.
    // This avoids overreacting to transient issues like timeouts.
    if (!reason.contains("Missing proof for empty account range")) {
      return None
    }

    val failures = peerFailureCounts.getOrElse(peer.id, 0) + 1
    peerFailureCounts.update(peer.id, failures)

    // Exponential backoff: 2s, 4s, 8s, ... (capped)
    val exponent = math.min(failures - 1, 12)
    val cooldown = (basePeerCooldown * (1L << exponent)).min(maxPeerCooldown)
    peerCooldownUntilMillis.update(peer.id, nowMillis + cooldown.toMillis)
    Some(cooldown)
  }

  // Task management
  private val pendingTasks = mutable.Queue[AccountTask](AccountTask.createInitialTasks(stateRoot, concurrency): _*)
  private val activeTasks = mutable.Map[BigInt, (AccountTask, ActorRef, Peer)]() // requestId -> (task, worker, peer)
  private val completedTasks = mutable.ArrayBuffer[AccountTask]()

  // Worker pool
  private val workers = mutable.ArrayBuffer[ActorRef]()

  // Statistics
  private var accountsDownloaded: Long = 0
  private var bytesDownloaded: Long = 0
  private val startTime = System.currentTimeMillis()

  // Contract accounts collected for bytecode download
  private val contractAccounts = mutable.ArrayBuffer[(ByteString, ByteString)]()

  // Contract accounts collected for storage download (accountHash -> storageRoot)
  private val contractStorageAccounts = mutable.ArrayBuffer[(ByteString, ByteString)]()

  // Track last known available peers so we can re-dispatch after task failures
  // without waiting for the next PeerAvailable message.
  private val knownAvailablePeers = mutable.Set[Peer]()

  // Merkle proof verifier
  private val proofVerifier = MerkleProofVerifier(stateRoot)

  // Deferred-write storage wrapper: makes updateNodesInStorage() a no-op during bulk insertion,
  // keeping all trie nodes in memory. This avoids the per-put collapse (RLP encode + Keccak-256 hash)
  // and database write that was causing insertion to degrade from ~300/s to ~20/s.
  // Nodes are flushed to RocksDB in a single batch at response boundaries via flushTrieToStorage().
  private val deferredStorage = new DeferredWriteMptStorage(mptStorage)

  // State trie for storing accounts.
  // In SNAP, we start with an empty local DB and rebuild the state trie from downloaded ranges.
  private var stateTrie: MerklePatriciaTrie[ByteString, Account] = {
    import com.chipprbots.ethereum.mpt.byteStringSerializer
    MerklePatriciaTrie[ByteString, Account](deferredStorage)
  }

  override def preStart(): Unit = {
    log.info(s"AccountRangeCoordinator starting with $concurrency workers")
  }

  override def postStop(): Unit = {
    log.info(s"AccountRangeCoordinator stopped. Downloaded $accountsDownloaded accounts")
  }

  // Supervision strategy: Restart worker on failure
  override val supervisorStrategy: SupervisorStrategy =
    OneForOneStrategy(maxNrOfRetries = 3, withinTimeRange = 1.minute) {
      case _: Exception =>
        log.warning("Worker failed, restarting")
        Restart
    }

  override def receive: Receive = {
    case StartAccountRangeSync(root) =>
      log.info(s"Starting account range sync for state root ${root.take(8).toHex}")
      // Tasks already initialized in constructor

    case AccountRangeResponseMsg(response) =>
      activeTasks.get(response.requestId) match {
        case None =>
          log.debug(s"Received AccountRange response for unknown or completed request ${response.requestId}")

        case Some((_, worker, _)) =>
          // Forward to the specific worker that owns this requestId so it can validate/complete the request.
          worker ! AccountRangeResponseMsg(response)
      }

    case PeerAvailable(peer) =>
      knownAvailablePeers += peer
      if (isPeerCoolingDown(peer)) {
        log.debug(s"Ignoring PeerAvailable(${peer.id.value}) due to cooldown")
      } else if (pendingTasks.isEmpty) {
        log.debug("No pending tasks")
      } else {
        // Fill as many idle workers as possible for this peer.
        var keepDispatching = true
        while (keepDispatching && pendingTasks.nonEmpty) {
          val maybeIdleWorker = workers.find(w => !activeTasks.values.exists(_._2 == w))
          val worker = maybeIdleWorker.orElse {
            if (workers.size < concurrency) Some(createWorker()) else None
          }

          worker match {
            case Some(w) =>
              dispatchNextTaskToWorker(w, peer)
            case None =>
              keepDispatching = false
              log.debug("No idle workers available")
          }
        }
      }

    case TaskComplete(requestId, result) =>
      handleTaskComplete(requestId, result)

    case TaskFailed(requestId, reason) =>
      handleTaskFailed(requestId, reason)

    case GetProgress =>
      val progress = calculateProgress()
      sender() ! progress

    case GetContractAccounts =>
      sender() ! ContractAccountsResponse(contractAccounts.toSeq)

    case GetContractStorageAccounts =>
      sender() ! ContractStorageAccountsResponse(contractStorageAccounts.toSeq)

    case StoreAccountChunk(task, remaining, totalCount, storedSoFar, isTaskRangeComplete) =>
      handleStoreAccountChunk(task, remaining, totalCount, storedSoFar, isTaskRangeComplete)

    case CheckCompletion =>
      if (isComplete) {
        log.info("Account range sync complete!")
        // Finalize trie before reporting completion
        finalizeTrie() match {
          case Right(_) =>
            log.info("State trie finalized successfully")
            snapSyncController ! SNAPSyncController.AccountRangeSyncComplete
          case Left(error) =>
            log.error(s"Failed to finalize trie: $error")
            snapSyncController ! SNAPSyncController.AccountRangeSyncComplete // Still proceed
        }
      }
  }

  private def createWorker(): ActorRef = {
    val worker = context.actorOf(
      AccountRangeWorker.props(
        coordinator = self,
        networkPeerManager = networkPeerManager,
        requestTracker = requestTracker
      )
    )
    workers += worker
    log.debug(s"Created worker ${worker.path.name}, total workers: ${workers.size}")
    worker
  }

  private def dispatchNextTaskToWorker(worker: ActorRef, peer: Peer): Unit = {
    if (pendingTasks.isEmpty) {
      return
    }

    val task = pendingTasks.dequeue()
    task.pending = true

    val requestId = requestTracker.generateRequestId()
    activeTasks.put(requestId, (task, worker, peer))

    worker ! FetchAccountRange(task, peer, requestId)
  }

  // How many accounts to insert per chunk before yielding to the actor mailbox.
  // With DeferredWriteMptStorage, puts are purely in-memory (~1-10μs each), so we can
  // process much larger chunks. 2000 accounts takes ~2-20ms in-memory.
  private val storeChunkSize = 2000

  private def handleTaskComplete(
      requestId: BigInt,
      result: Either[String, (Int, Seq[(ByteString, Account)], Seq[ByteString])]
  ): Unit = {
    activeTasks.remove(requestId).foreach { case (task, worker, peer) =>
      result match {
        case Right((accountCount, accounts, _)) =>
          log.info(s"Task completed successfully: $accountCount accounts")

          // Identify contract accounts
          identifyContractAccounts(accounts)

          // Update task progress before starting async storage.
          // This sets task.next so re-queuing (if needed) uses the correct start.
          val isTaskDone = updateTaskProgress(task, accounts)
          task.pending = false

          // Update statistics
          val accountBytes = accounts.map { case (hash, _) =>
            hash.size + 32 // Rough estimate
          }.sum
          bytesDownloaded += accountBytes

          // Start chunked async storage - this yields back to the actor mailbox between chunks
          // so the coordinator can still process PeerAvailable, AccountRangeResponseMsg, etc.
          self ! StoreAccountChunk(task, accounts, accountCount, storedSoFar = 0, isTaskRangeComplete = isTaskDone)

        case Left(error) =>
          log.warning(s"Task completed with error: $error")
          // Re-queue task for retry
          task.pending = false
          pendingTasks.enqueue(task)
      }
    }
  }

  private def updateTaskProgress(task: AccountTask, accounts: Seq[(ByteString, Account)]): Boolean = {
    // If the peer returns no accounts for this segment, we assume the remainder of the interval is empty.
    if (accounts.isEmpty) {
      return true
    }

    val lastHash = accounts.last._1
    if (isMaxHash(lastHash)) {
      // Cannot advance beyond 0xFF..; this must be the end.
      return true
    }

    val nextStart = incrementHash32(lastHash)
    task.next = nextStart

    // If this task has no upper bound, keep going until peer returns empty.
    if (task.last.isEmpty) {
      false
    } else {
      // Treat `last` as an exclusive upper bound.
      compareUnsigned32(nextStart, task.last) >= 0
    }
  }

  private def compareUnsigned32(a: ByteString, b: ByteString): Int = {
    // Empty is treated as unbounded; callers should handle this before comparing.
    val aa = a.toArray
    val bb = b.toArray
    val maxLen = math.max(aa.length, bb.length)
    val ap = if (aa.length == maxLen) aa else Array.fill(maxLen - aa.length)(0.toByte) ++ aa
    val bp = if (bb.length == maxLen) bb else Array.fill(maxLen - bb.length)(0.toByte) ++ bb
    var i = 0
    while (i < maxLen) {
      val ai = ap(i) & 0xff
      val bi = bp(i) & 0xff
      if (ai != bi) return ai - bi
      i += 1
    }
    0
  }

  private def incrementHash32(hash: ByteString): ByteString = {
    require(hash.length == 32, s"Expected 32-byte hash, got ${hash.length}")
    val bytes = hash.toArray
    var i = bytes.length - 1
    var carry = 1
    while (i >= 0 && carry != 0) {
      val sum = (bytes(i) & 0xff) + carry
      bytes(i) = (sum & 0xff).toByte
      carry = if (sum > 0xff) 1 else 0
      i -= 1
    }
    ByteString(bytes)
  }

  private def isMaxHash(hash: ByteString): Boolean =
    hash.length == 32 && hash.forall(b => (b & 0xff) == 0xff)

  private def handleTaskFailed(requestId: BigInt, reason: String): Unit = {
    activeTasks.remove(requestId).foreach { case (task, worker, peer) =>
      val cooldownOpt = recordPeerCooldown(peer, reason)
      cooldownOpt match {
        case Some(cooldown) =>
          log.warning(s"Task failed: $reason (cooling down peer ${peer.id.value} for $cooldown)")
        case None =>
          log.warning(s"Task failed: $reason")
      }
      task.pending = false
      pendingTasks.enqueue(task)
    }
    // Re-dispatch re-queued tasks to any known available peer that isn't on cooldown.
    // Without this, tasks sit in the queue until the next PeerAvailable message arrives.
    tryRedispatchPendingTasks()
  }

  private def tryRedispatchPendingTasks(): Unit = {
    if (pendingTasks.isEmpty) return
    val eligiblePeers = knownAvailablePeers.filterNot(isPeerCoolingDown).toList
    if (eligiblePeers.isEmpty) return

    for (peer <- eligiblePeers if pendingTasks.nonEmpty) {
      val maybeIdleWorker = workers.find(w => !activeTasks.values.exists(_._2 == w))
      val worker = maybeIdleWorker.orElse {
        if (workers.size < concurrency) Some(createWorker()) else None
      }
      worker.foreach(w => dispatchNextTaskToWorker(w, peer))
    }
  }

  /** Handle a chunk of account storage, inserting a batch into the in-memory trie and yielding
    * back to the actor mailbox between chunks. With DeferredWriteMptStorage, puts are purely
    * in-memory (no collapse, no encoding, no hashing, no DB writes). The trie is flushed to
    * RocksDB in a single batch when all chunks for a response are done.
    */
  private def handleStoreAccountChunk(
      task: AccountTask,
      remaining: Seq[(ByteString, Account)],
      totalCount: Int,
      storedSoFar: Int,
      isTaskRangeComplete: Boolean
  ): Unit = {
    val (chunk, rest) = remaining.splitAt(storeChunkSize)

    try {
      var currentTrie = stateTrie
      chunk.foreach { case (accountHash, account) =>
        currentTrie = currentTrie.put(accountHash, account)
      }
      stateTrie = currentTrie
      // No persist per chunk — DeferredWriteMptStorage buffers everything in memory

      val newStored = storedSoFar + chunk.size
      // Report incremental progress so the stagnation watchdog sees activity
      accountsDownloaded += chunk.size
      snapSyncController ! SNAPSyncController.ProgressAccountsSynced(chunk.size.toLong)

      if (rest.nonEmpty) {
        log.info(s"Stored chunk: $newStored/$totalCount accounts (${rest.size} remaining)")
        // Yield to actor mailbox - other messages (PeerAvailable, responses) process before next chunk
        self ! StoreAccountChunk(task, rest, totalCount, newStored, isTaskRangeComplete)
      } else {
        // All chunks for this response done — flush the in-memory trie to RocksDB in one batch
        log.info(s"Stored all $totalCount accounts in-memory, flushing trie to storage...")
        flushTrieToStorage()

        if (isTaskRangeComplete) {
          task.done = true
          completedTasks += task
        } else {
          // Need more requests for the same interval; re-queue with updated `next`.
          pendingTasks.enqueue(task)
        }

        // Check if all tasks are complete
        self ! CheckCompletion
      }
    } catch {
      case e: Exception =>
        log.error(e, s"Failed to store account chunk: ${e.getMessage}")
        // Re-queue task for retry
        task.pending = false
        task.done = false
        pendingTasks.enqueue(task)
    }
  }

  /** Flush all in-memory trie nodes to RocksDB in a single batch.
    * This collapses the entire in-memory trie (RLP-encode + Keccak-256 hash all nodes),
    * then writes everything to RocksDB via one WriteBatch. After flush, the trie is rebuilt
    * from the persisted root hash so old in-memory nodes can be garbage collected.
    */
  private def flushTrieToStorage(): Unit = {
    deferredStorage.flush().foreach { rootHash =>
      import com.chipprbots.ethereum.mpt.byteStringSerializer
      stateTrie = MerklePatriciaTrie[ByteString, Account](rootHash, deferredStorage)
      log.info(s"Flushed trie to storage, root=${rootHash.take(8).map("%02x".format(_)).mkString}...")
    }
  }

  /** Identify contract accounts (those with non-empty code hash)
    *
    * @param accounts
    *   Accounts to scan for contracts
    */
  private def identifyContractAccounts(accounts: Seq[(ByteString, Account)]): Unit = {
    val contracts = accounts.collect {
      case (accountHash, account) if account.codeHash != Account.EmptyCodeHash =>
        (accountHash, account.codeHash)
    }

    val storageContracts = accounts.collect {
      case (accountHash, account) if account.codeHash != Account.EmptyCodeHash =>
        (accountHash, account.storageRoot)
    }

    if (contracts.nonEmpty) {
      contractAccounts.appendAll(contracts)
      contractStorageAccounts.appendAll(storageContracts)
      log.info(s"Identified ${contracts.size} contract accounts (total: ${contractAccounts.size})")
    }
  }

  /** Finalize the trie and ensure all nodes including the root are persisted to storage.
    *
    * @return
    *   Either error message or success
    */
  private def finalizeTrie(): Either[String, Unit] =
    try {
      log.info("Finalizing state trie...")

      // Flush any remaining deferred writes to RocksDB
      flushTrieToStorage()

      val currentRootHash = ByteString(stateTrie.getRootHash)
      log.info(s"Final state root: ${currentRootHash.take(8).toArray.map("%02x".format(_)).mkString}")

      // Validate that the reconstructed root matches the expected pivot stateRoot.
      if (stateRoot.nonEmpty && stateRoot != ByteString(MerklePatriciaTrie.EmptyRootHash) && currentRootHash != stateRoot) {
        val expected = stateRoot.take(8).toArray.map("%02x".format(_)).mkString
        val actual = currentRootHash.take(8).toArray.map("%02x".format(_)).mkString
        Left(s"Root mismatch: expected=$expected..., actual=$actual...")
      } else {
        log.info("State trie finalization complete")
        Right(())
      }
    } catch {
      case e: Exception =>
        log.error(e, s"Failed to finalize trie: ${e.getMessage}")
        Left(s"Trie finalization error: ${e.getMessage}")
    }

  /** Get the current state root hash from the trie
    *
    * @return
    *   Current state root hash
    */
  def getStateRoot: ByteString = {
    ByteString(stateTrie.getRootHash)
  }

  /** Get all collected contract accounts for bytecode download
    *
    * @return
    *   Sequence of (accountHash, codeHash) for contract accounts
    */
  def getContractAccounts: Seq[(ByteString, ByteString)] = {
    contractAccounts.toSeq
  }


  private def calculateProgress(): AccountRangeProgress = {
    val total = completedTasks.size + activeTasks.size + pendingTasks.size
    val progress = if (total == 0) 1.0 else completedTasks.size.toDouble / total
    val elapsedMs = System.currentTimeMillis() - startTime

    AccountRangeProgress(
      accountsDownloaded = accountsDownloaded,
      bytesDownloaded = bytesDownloaded,
      tasksCompleted = completedTasks.size,
      tasksActive = activeTasks.size,
      tasksPending = pendingTasks.size,
      progress = progress,
      elapsedTimeMs = elapsedMs,
      contractAccountsFound = contractAccounts.size
    )
  }

  private def isComplete: Boolean = {
    pendingTasks.isEmpty && activeTasks.isEmpty
  }
}

object AccountRangeCoordinator {
  def props(
      stateRoot: ByteString,
      networkPeerManager: ActorRef,
      requestTracker: SNAPRequestTracker,
      mptStorage: MptStorage,
      concurrency: Int,
      snapSyncController: ActorRef
  ): Props =
    Props(
      new AccountRangeCoordinator(
        stateRoot,
        networkPeerManager,
        requestTracker,
        mptStorage,
        concurrency,
        snapSyncController
      )
    )
}

case class AccountRangeProgress(
    accountsDownloaded: Long,
    bytesDownloaded: Long,
    tasksCompleted: Int,
    tasksActive: Int,
    tasksPending: Int,
    progress: Double,
    elapsedTimeMs: Long,
    contractAccountsFound: Int
)
