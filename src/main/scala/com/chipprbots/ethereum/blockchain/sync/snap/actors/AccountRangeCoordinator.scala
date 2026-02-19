package com.chipprbots.ethereum.blockchain.sync.snap.actors

import org.apache.pekko.actor.{Actor, ActorLogging, ActorRef, Props, SupervisorStrategy, OneForOneStrategy, Status}
import org.apache.pekko.actor.SupervisorStrategy._
import org.apache.pekko.util.ByteString

import scala.collection.mutable
import scala.concurrent.{Future, blocking}
import scala.concurrent.duration._
import scala.util.{Success, Failure}

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
    initialStateRoot: ByteString,
    networkPeerManager: ActorRef,
    requestTracker: SNAPRequestTracker,
    mptStorage: MptStorage,
    concurrency: Int,
    snapSyncController: ActorRef
) extends Actor
    with ActorLogging {

  import Messages._
  import SNAPSyncController.PivotStateUnservable

  // Mutable state root — updated in-place when the controller refreshes the pivot.
  private var stateRoot: ByteString = initialStateRoot

  // Stateless peer tracking: peers that return "Missing proof for empty account range"
  // are unable to serve the current state root. Unlike the previous exponential cooldown,
  // this is a binary classification: either a peer can serve the root or it can't.
  // When ALL known peers become stateless, we request a pivot refresh from the controller.
  private val statelessPeers = mutable.Set[com.chipprbots.ethereum.network.PeerId]()
  private var pivotRefreshRequested = false
  private var pivotWasRefreshed = false

  private def isPeerStateless(peer: Peer): Boolean =
    statelessPeers.contains(peer.id)

  private def markPeerStateless(peer: Peer, reason: String): Unit = {
    val shouldMark = if (reason.contains("Missing proof for empty account range")) {
      // Peer explicitly returned empty response — immediately stateless
      true
    } else if (reason.contains("Request timeout")) {
      // Peer timed out — track consecutive timeouts.
      // On ETC mainnet, peers silently stop responding when the snap serve window expires
      // rather than returning explicit empty responses. After N consecutive timeouts,
      // we treat the peer as stateless so pivot refresh can trigger.
      val count = peerConsecutiveTimeouts.getOrElse(peer.id.value, 0) + 1
      peerConsecutiveTimeouts.update(peer.id.value, count)
      if (count >= consecutiveTimeoutThreshold) {
        log.info(s"Peer ${peer.id.value} hit $count consecutive timeouts — treating as stateless")
        true
      } else {
        false
      }
    } else {
      false
    }

    if (shouldMark) {
      statelessPeers.add(peer.id)
      log.info(
        s"Peer ${peer.id.value} marked stateless for root ${stateRoot.take(4).toHex} " +
          s"(${statelessPeers.size}/${knownAvailablePeers.size} peers stateless)"
      )
      maybeRequestPivotRefresh()
    }
  }

  private def maybeRequestPivotRefresh(): Unit = {
    if (pivotRefreshRequested) return
    // If all known peers are stateless, the root has aged out of the serve window
    val allStateless = knownAvailablePeers.nonEmpty &&
      knownAvailablePeers.forall(p => statelessPeers.contains(p.id))
    if (!allStateless) return

    // Exponential backoff: don't hammer the controller with rapid refresh requests
    val now = System.currentTimeMillis()
    val backoffMs = math.min(
      minRefreshIntervalMs * (1L << math.min(consecutiveUnproductiveRefreshes, 3)),
      maxRefreshIntervalMs
    )
    val elapsed = now - lastPivotRefreshTimeMs
    if (lastPivotRefreshTimeMs > 0 && elapsed < backoffMs) {
      log.info(
        s"All ${statelessPeers.size} peers stateless but backing off pivot refresh " +
          s"(${elapsed / 1000}s / ${backoffMs / 1000}s elapsed, attempt=${consecutiveUnproductiveRefreshes + 1}). " +
          "Will retry after backoff."
      )
      // Schedule a re-check after the remaining backoff period
      import context.dispatcher
      context.system.scheduler.scheduleOnce((backoffMs - elapsed).millis, self, CheckCompletion)
      return
    }

    pivotRefreshRequested = true
    lastPivotRefreshTimeMs = now
    consecutiveUnproductiveRefreshes += 1
    log.warning(
      s"All ${statelessPeers.size} known peers are stateless for root ${stateRoot.take(4).toHex}. " +
        s"Requesting pivot refresh from controller (attempt=$consecutiveUnproductiveRefreshes, backoff=${backoffMs / 1000}s)."
    )
    snapSyncController ! PivotStateUnservable(
      rootHash = stateRoot,
      reason = "all peers stateless for AccountRange root",
      consecutiveEmptyResponses = statelessPeers.size
    )
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

  // Per-peer adaptive byte budgeting (ported from StorageRangeCoordinator).
  // Geth's snap handler supports up to 2MB responses. Starting at 512KB and probing upward
  // on responsive peers, scaling down on failures.
  private val minResponseBytes: BigInt = 50 * 1024 // 50KB floor
  private val maxResponseBytes: BigInt = 2 * 1024 * 1024 // 2MB ceiling (Geth handler limit)
  private val initialResponseBytes: BigInt = 512 * 1024 // 512KB starting point
  private val increaseFactor: Double = 1.25 // Scale up when 90%+ fill
  private val decreaseFactor: Double = 0.5 // Scale down on failure/empty

  private val peerResponseBytesTarget = mutable.Map.empty[String, BigInt]

  private def responseBytesTargetFor(peer: Peer): BigInt =
    peerResponseBytesTarget
      .getOrElseUpdate(peer.id.value, initialResponseBytes)
      .max(minResponseBytes)
      .min(maxResponseBytes)

  private def adjustResponseBytesOnSuccess(peer: Peer, requested: BigInt, received: BigInt): Unit =
    if (requested > 0 && received * 10 >= requested * 9 && requested < maxResponseBytes) {
      val next = (requested.toDouble * increaseFactor).toLong
      peerResponseBytesTarget.update(peer.id.value, BigInt(next).min(maxResponseBytes))
    }

  private def adjustResponseBytesOnFailure(peer: Peer, reason: String): Unit = {
    val cur = responseBytesTargetFor(peer)
    val next = (cur.toDouble * decreaseFactor).toLong
    peerResponseBytesTarget.update(peer.id.value, BigInt(next).max(minResponseBytes))
    log.debug(
      s"Reducing account responseBytes target for peer ${peer.id.value}: $cur -> ${peerResponseBytesTarget(peer.id.value)} ($reason)"
    )
  }

  // Track consecutive timeouts per peer. When a peer hits the threshold, it's marked stateless.
  // This handles the case where ETC mainnet peers silently stop responding (timeout) when
  // their snap serve window expires, rather than returning empty responses with proofs.
  private val peerConsecutiveTimeouts = mutable.Map[String, Int]()
  private val consecutiveTimeoutThreshold = 3 // Mark stateless after 3 consecutive timeouts

  // Peer cooldown (best-effort): used for transient errors (timeouts, verification failures).
  private val peerCooldownUntilMs = mutable.Map[String, Long]()
  private val peerCooldownDefault = 30.seconds

  private def isPeerCoolingDown(peer: Peer): Boolean =
    peerCooldownUntilMs.get(peer.id.value).exists(_ > System.currentTimeMillis())

  private def recordPeerCooldown(peer: Peer, reason: String): Unit = {
    val until = System.currentTimeMillis() + peerCooldownDefault.toMillis
    peerCooldownUntilMs.put(peer.id.value, until)
    log.debug(s"Cooling down peer ${peer.id.value} for ${peerCooldownDefault.toSeconds}s: $reason")
  }

  // Pivot refresh backoff: prevents rapid-fire pivot refresh requests when all peers are stateless.
  // Exponential backoff from 60s to 5min.
  private var consecutiveUnproductiveRefreshes: Int = 0
  private var lastPivotRefreshTimeMs: Long = 0L
  private val minRefreshIntervalMs: Long = 60000L // 60s minimum between refreshes
  private val maxRefreshIntervalMs: Long = 300000L // 5min ceiling

  // Internal message for async trie finalization result
  private case class TrieFlushComplete(result: Either[String, Unit])

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

    case PivotRefreshed(newStateRoot) =>
      log.info(s"Pivot refreshed: ${stateRoot.take(4).toHex} -> ${newStateRoot.take(4).toHex}")
      stateRoot = newStateRoot
      pivotWasRefreshed = true

      // Update all pending tasks to use the new root
      pendingTasks.foreach(_.rootHash = newStateRoot)
      // Active tasks will fail with root mismatch and get re-queued;
      // handleTaskFailed will re-enqueue them with the old root, but they'll be
      // dispatched with the new root on their next attempt.

      // Clear stateless tracking — peers can serve the new root
      statelessPeers.clear()
      pivotRefreshRequested = false

      // Clear per-peer adaptive state (new root = new response characteristics)
      peerResponseBytesTarget.clear()
      peerCooldownUntilMs.clear()
      peerConsecutiveTimeouts.clear()
      // Note: do NOT reset consecutiveUnproductiveRefreshes here.
      // Only reset when we receive real account data (proof the new root is servable).

      // Resume dispatching with the fresh root
      tryRedispatchPendingTasks()

    case PeerAvailable(peer) =>
      knownAvailablePeers += peer
      if (isPeerStateless(peer)) {
        log.debug(s"Ignoring PeerAvailable(${peer.id.value}) - peer is stateless for current root")
      } else if (isPeerCoolingDown(peer)) {
        log.debug(s"Ignoring PeerAvailable(${peer.id.value}) - peer is cooling down")
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
      computeKeyspaceEstimate().foreach { est =>
        snapSyncController ! SNAPSyncController.ProgressAccountEstimate(est)
      }
      if (isComplete) {
        log.info("Account range sync complete!")
        log.info(s"Starting async trie finalization for $accountsDownloaded accounts...")
        // Notify controller so progress monitor shows finalization status
        snapSyncController ! SNAPSyncController.ProgressAccountsFinalizingTrie
        // Switch to finalizing state so no message can touch the trie during flush
        context.become(finalizing)

        // Run the expensive flush (O(n*log(n)) trie collapse + RocksDB write) on a
        // separate thread to avoid blocking the Pekko dispatcher for 10+ minutes.
        val selfRef = self
        Future {
          blocking { finalizeTrie() }
        }(scala.concurrent.ExecutionContext.global)
          .onComplete {
            case Success(result) => selfRef ! TrieFlushComplete(result)
            case Failure(ex)     => selfRef ! Status.Failure(ex)
          }(context.dispatcher)
      }
  }

  /** Receive state during async trie finalization.
    * The in-memory trie is being collapsed and written to RocksDB on a background thread.
    * No message should touch stateTrie or deferredStorage during this phase.
    */
  private def finalizing: Receive = {
    case TrieFlushComplete(Right(_)) =>
      log.info("State trie finalized successfully")
      snapSyncController ! SNAPSyncController.AccountRangeSyncComplete

    case TrieFlushComplete(Left(error)) =>
      log.error(s"Failed to finalize trie: $error")
      snapSyncController ! SNAPSyncController.AccountRangeSyncComplete // Still proceed

    case Status.Failure(ex) =>
      log.error(ex, s"Trie finalization failed with exception: ${ex.getMessage}")
      snapSyncController ! SNAPSyncController.AccountRangeSyncComplete // Still proceed

    case _: PeerAvailable =>
      // Ignore — no more tasks to dispatch during finalization

    case _: PivotRefreshed =>
      log.info("Ignoring PivotRefreshed during trie finalization")

    case GetProgress =>
      sender() ! calculateProgress()

    case GetContractAccounts =>
      sender() ! ContractAccountsResponse(contractAccounts.toSeq)

    case GetContractStorageAccounts =>
      sender() ! ContractStorageAccountsResponse(contractStorageAccounts.toSeq)

    case CheckCompletion =>
      // Already finalizing, ignore
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
    val responseBytes = responseBytesTargetFor(peer)

    worker ! FetchAccountRange(task, peer, requestId, responseBytes)
  }

  // How many accounts to insert per chunk before yielding to the actor mailbox.
  // With DeferredWriteMptStorage, puts are purely in-memory (~1-10μs each), so we can
  // process much larger chunks. 2000 accounts takes ~2-20ms in-memory.
  private val storeChunkSize = 2000

  // No intermediate flushing during account download.
  // DeferredWriteMptStorage.flush() collapses the ENTIRE in-memory trie (O(n*log(n))) which
  // blocks the actor for minutes (72s+ at 200K accounts, worse as trie grows). With 4GB heap,
  // the full ETC state (~2.3M accounts, ~1-2GB) fits in memory. All accounts are inserted
  // purely in-memory, and the single flush happens at finalization in finalizeTrie().

  private def handleTaskComplete(
      requestId: BigInt,
      result: Either[String, (Int, Seq[(ByteString, Account)], Seq[ByteString])]
  ): Unit = {
    activeTasks.remove(requestId).foreach { case (task, worker, peer) =>
      result match {
        case Right((accountCount, accounts, _)) =>
          log.info(s"Task completed successfully: $accountCount accounts (responseBytes=${responseBytesTargetFor(peer)})")

          // Adjust adaptive byte budget — estimate received bytes from account count
          val estimatedBytes = BigInt(accountCount * 100) // ~100 bytes per account (hash + RLP)
          adjustResponseBytesOnSuccess(peer, responseBytesTargetFor(peer), estimatedBytes)

          // Reset consecutive timeout counter — peer is responsive
          peerConsecutiveTimeouts.remove(peer.id.value)

          // Reset pivot refresh backoff on receiving real account data
          if (accountCount > 0) {
            consecutiveUnproductiveRefreshes = 0
          }

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
      markPeerStateless(peer, reason)
      log.warning(s"Task failed: $reason")
      task.pending = false
      pendingTasks.enqueue(task)

      // Apply cooldown and reduce byte budget for failing peers (unless stateless-marked,
      // which already blocks them from dispatch)
      if (!reason.contains("Missing proof for empty account range")) {
        recordPeerCooldown(peer, reason)
        adjustResponseBytesOnFailure(peer, reason)
      }
    }
    // Re-dispatch re-queued tasks to any known available peer that isn't stateless.
    // Without this, tasks sit in the queue until the next PeerAvailable message arrives.
    tryRedispatchPendingTasks()
  }

  private def tryRedispatchPendingTasks(): Unit = {
    if (pendingTasks.isEmpty) return
    val eligiblePeers = knownAvailablePeers
      .filterNot(isPeerStateless)
      .filterNot(isPeerCoolingDown)
      .toList
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
        // All chunks for this response stored in-memory. No flush here — the entire trie
        // stays in memory and is flushed once at the end in finalizeTrie().
        log.info(s"Stored all $totalCount accounts in-memory ($accountsDownloaded total)")

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

      // After pivot refresh(es), root mismatch is expected — the healing phase
      // will reconcile. Only fail on root mismatch if NO pivot refresh occurred.
      if (!pivotWasRefreshed &&
          stateRoot.nonEmpty &&
          stateRoot != ByteString(MerklePatriciaTrie.EmptyRootHash) &&
          currentRootHash != stateRoot) {
        val expected = stateRoot.take(8).toArray.map("%02x".format(_)).mkString
        val actual = currentRootHash.take(8).toArray.map("%02x".format(_)).mkString
        Left(s"Root mismatch: expected=$expected..., actual=$actual...")
      } else {
        if (pivotWasRefreshed && currentRootHash != stateRoot) {
          log.info("Root mismatch expected after pivot refresh - healing phase will reconcile")
        }
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

  /** Estimate total accounts from keyspace coverage.
    * Uses completed tasks' ranges to compute keyspace density (accounts per unit of keyspace),
    * then extrapolates to the full 2^256 space. Only considers tasks that have actually been
    * explored, avoiding inflation from un-dispatched chunks.
    */
  private def computeKeyspaceEstimate(): Option[Long] = {
    if (accountsDownloaded < 10000) return None // too early for reliable estimate

    val keyspaceSize = BigInt(2).pow(256)
    val nonCompleteTasks = pendingTasks.toSeq ++ activeTasks.values.map(_._1)
    val remaining = if (nonCompleteTasks.isEmpty) {
      BigInt(0)
    } else {
      nonCompleteTasks.foldLeft(BigInt(0)) { case (sum, task) =>
        val taskEnd = BigInt(1, task.last.toArray)
        val taskPos = BigInt(1, task.next.toArray)
        sum + (taskEnd - taskPos).max(0)
      }
    }

    val covered = keyspaceSize - remaining
    if (covered <= 0) return None

    val fraction = covered.toDouble / keyspaceSize.toDouble
    // Require at least 1% keyspace coverage for a reliable estimate.
    // With 16 chunks and 3 peers, this takes ~2 minutes to reach.
    if (fraction < 0.01) return None

    val estimated = (accountsDownloaded / fraction).toLong
    Some(estimated)
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
        initialStateRoot = stateRoot,
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
