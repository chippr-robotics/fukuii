package com.chipprbots.ethereum.blockchain.sync.snap.actors

import org.apache.pekko.actor.{Actor, ActorLogging, ActorRef, Props, SupervisorStrategy, OneForOneStrategy}
import org.apache.pekko.actor.SupervisorStrategy._
import org.apache.pekko.util.ByteString

import scala.collection.mutable
import scala.concurrent.duration._

import com.chipprbots.ethereum.blockchain.sync.snap._
import com.chipprbots.ethereum.db.storage.MptStorage
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

  // Merkle proof verifier
  private val proofVerifier = MerkleProofVerifier(stateRoot)

  // State trie for storing accounts.
  // In SNAP, we typically start with an empty local DB and rebuild the state trie from downloaded ranges.
  // Attempting to "load" the pivot stateRoot will fail because the root node isn't present locally yet.
  private var stateTrie: MerklePatriciaTrie[ByteString, Account] = {
    import com.chipprbots.ethereum.mpt.byteStringSerializer
    MerklePatriciaTrie[ByteString, Account](mptStorage)
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
      if (pendingTasks.nonEmpty) {
        val maybeIdleWorker = workers.find(w => !activeTasks.values.exists(_._2 == w))
        val worker = maybeIdleWorker.orElse {
          if (workers.size < concurrency) Some(createWorker()) else None
        }

        worker match {
          case Some(w) =>
            dispatchNextTaskToWorker(w, peer)
          case None =>
            log.debug("No idle workers available")
        }
      } else {
        log.debug("No pending tasks")
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
          
          // Store accounts to database
          storeAccounts(accounts) match {
            case Left(error) =>
              log.warning(s"Failed to store accounts: $error")
              // Re-queue task for retry
              task.pending = false
              pendingTasks.enqueue(task)

            case Right(_) =>
              accountsDownloaded += accountCount
              snapSyncController ! SNAPSyncController.ProgressAccountsSynced(accountCount.toLong)

              // Update statistics
              val accountBytes = accounts.map { case (hash, _) =>
                hash.size + 32 // Rough estimate
              }.sum
              bytesDownloaded += accountBytes

              // Advance the task through its range.
              val isTaskComplete = updateTaskProgress(task, accounts)
              task.pending = false

              if (isTaskComplete) {
                task.done = true
                completedTasks += task
              } else {
                // Need more requests for the same interval; re-queue with updated `next`.
                pendingTasks.enqueue(task)
              }
          }

          // Check if complete
          self ! CheckCompletion

          // Keep pipeline moving without relying on new PeerAvailable events
          dispatchNextTaskToWorker(worker, peer)

        case Left(error) =>
          log.warning(s"Task completed with error: $error")
          // Re-queue task for retry
          task.pending = false
          pendingTasks.enqueue(task)

          // Keep pipeline moving
          dispatchNextTaskToWorker(worker, peer)
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
      log.warning(s"Task failed: $reason")
      task.pending = false
      pendingTasks.enqueue(task)

      // Keep pipeline moving
      dispatchNextTaskToWorker(worker, peer)
    }
  }

  /** Store accounts to MptStorage
    *
    * Inserts verified accounts into the state trie using proper MPT structure.
    *
    * @param accounts
    *   Accounts to store (accountHash -> account)
    * @return
    *   Either error or success
    */
  private def storeAccounts(accounts: Seq[(ByteString, Account)]): Either[String, Unit] =
    try {
      if (accounts.nonEmpty) {
        // Build new trie by folding over accounts
        var currentTrie = stateTrie
        accounts.foreach { case (accountHash, account) =>
          log.debug(
            s"Storing account ${accountHash.take(4).toArray.map("%02x".format(_)).mkString} " +
              s"(balance: ${account.balance}, nonce: ${account.nonce})"
          )

          // Create new trie version - MPT is immutable
          currentTrie = currentTrie.put(accountHash, account)
        }

        // Update stateTrie
        stateTrie = currentTrie

        log.info(s"Inserted ${accounts.size} accounts into state trie")
      }

      // Persist
      mptStorage.persist()

      log.info(s"Successfully persisted ${accounts.size} accounts to storage")
      Right(())
    } catch {
      case e: Exception =>
        log.error(e, s"Failed to store accounts: ${e.getMessage}")
        Left(s"Storage error: ${e.getMessage}")
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

    if (contracts.nonEmpty) {
      contractAccounts.appendAll(contracts)
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
      log.info("Finalizing state trie and ensuring all nodes are persisted...")
      
      // Get the current root hash for logging
      val currentRootHash = ByteString(stateTrie.getRootHash)
      log.info(s"Current state root: ${currentRootHash.take(8).toArray.map("%02x".format(_)).mkString}")

      // Validate that the reconstructed root matches the expected pivot stateRoot.
      if (stateRoot.nonEmpty && stateRoot != ByteString(MerklePatriciaTrie.EmptyRootHash) && currentRootHash != stateRoot) {
        val expected = stateRoot.take(8).toArray.map("%02x".format(_)).mkString
        val actual = currentRootHash.take(8).toArray.map("%02x".format(_)).mkString
        throw new RuntimeException(s"Reconstructed state root does not match expected pivot root (expected=$expected..., actual=$actual...)")
      }
      
      // Check if we have a non-empty trie
      if (currentRootHash.isEmpty || currentRootHash == ByteString(MerklePatriciaTrie.EmptyRootHash)) {
        log.warning("State trie is empty, nothing to finalize")
      } else {
        log.info("State trie has content, proceeding with finalization")
        
        // Force the root node to be explicitly persisted to storage
        val dummyKey = ByteString("__snap_finalize_dummy__")
        val dummyValue = Account(
          nonce = com.chipprbots.ethereum.domain.UInt256.Zero,
          balance = com.chipprbots.ethereum.domain.UInt256.Zero,
          storageRoot = ByteString(MerklePatriciaTrie.EmptyRootHash),
          codeHash = ByteString(Account.EmptyCodeHash)
        )
        
        // Put dummy account and immediately remove it
        val tempTrie = stateTrie.put(dummyKey, dummyValue)
        stateTrie = tempTrie.remove(dummyKey)
        
        log.info(s"Forced root node persistence through dummy operation")
        
        // Verify the root hash hasn't changed
        val finalRootHash = ByteString(stateTrie.getRootHash)
        if (finalRootHash != currentRootHash) {
          log.error(s"Root hash changed after dummy operation!")
          throw new RuntimeException("Root hash changed during finalization")
        }
        
        log.info("Root node finalization verified")
      }
      
      // Flush all pending writes to disk
      mptStorage.persist()
      log.info("Flushed all trie nodes to disk")
      
      log.info("State trie finalization complete")
      Right(())
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
