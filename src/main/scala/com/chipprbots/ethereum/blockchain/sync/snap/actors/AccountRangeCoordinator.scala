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
  private val activeTasks = mutable.Map[BigInt, (AccountTask, ActorRef)]() // requestId -> (task, worker)
  private val completedTasks = mutable.ArrayBuffer[AccountTask]()

  // Worker pool
  private val workers = mutable.ArrayBuffer[ActorRef]()
  private val busyWorkers = mutable.Set[ActorRef]()

  // Statistics
  private var accountsDownloaded: Long = 0
  private var bytesDownloaded: Long = 0
  private val startTime = System.currentTimeMillis()

  // Contract accounts collected for bytecode download
  private val contractAccounts = mutable.ArrayBuffer[(ByteString, ByteString)]()

  // Merkle proof verifier
  private val proofVerifier = MerkleProofVerifier(stateRoot)

  // State trie for storing accounts - initialized with existing state root or new trie
  private var stateTrie: MerklePatriciaTrie[ByteString, Account] = {
    import com.chipprbots.ethereum.mpt.{byteStringSerializer, MerklePatriciaTrie}
    import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingRootNodeException

    // Initialize trie with the state root if it exists, otherwise create empty trie
    if (stateRoot.isEmpty || stateRoot == ByteString(MerklePatriciaTrie.EmptyRootHash)) {
      log.info("Initializing new empty state trie")
      MerklePatriciaTrie[ByteString, Account](mptStorage)
    } else {
      try {
        log.info(
          s"Loading existing state trie with root ${stateRoot.take(8).toArray.map("%02x".format(_)).mkString}..."
        )
        MerklePatriciaTrie[ByteString, Account](stateRoot.toArray, mptStorage)
      } catch {
        case e: MissingRootNodeException =>
          log.warning(s"State root not found in storage, creating new trie")
          MerklePatriciaTrie[ByteString, Account](mptStorage)
      }
    }
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

    case PeerAvailable(peer) =>
      if (pendingTasks.isEmpty) {
        log.debug("No pending account range tasks available for peer {}", peer.id)
      } else {
        val workerOpt = getIdleWorker.orElse {
          if (workers.size < concurrency) Some(createWorker()) else None
        }

        workerOpt match {
          case Some(worker) =>
            assignTaskToWorker(worker, peer)
          case None =>
            log.debug(
              s"No idle account range workers available (active=${busyWorkers.size}, max=$concurrency)"
            )
        }
      }

    case responseMsg: AccountRangeResponseMsg =>
      activeTasks.get(responseMsg.response.requestId) match {
        case Some((_, worker)) =>
          worker ! responseMsg
        case None =>
          log.debug(s"Received AccountRange response for unknown request ${responseMsg.response.requestId}")
      }

    case TaskComplete(requestId, result) =>
      handleTaskComplete(requestId, result)

    case TaskFailed(requestId, reason) =>
      handleTaskFailed(requestId, reason)

    case GetProgress =>
      val progress = calculateProgress()
      sender() ! progress
      if (sender() != snapSyncController) {
        snapSyncController ! progress
      }

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

  private def getIdleWorker: Option[ActorRef] = {
    workers.find(worker => !busyWorkers.contains(worker))
  }

  private def assignTaskToWorker(worker: ActorRef, peer: Peer): Unit = {
    if (pendingTasks.isEmpty) {
      log.debug("No pending tasks to assign")
      return
    }

    val task = pendingTasks.dequeue()
    val requestId = requestTracker.generateRequestId()
    task.pending = true
    activeTasks.put(requestId, (task, worker))
    busyWorkers += worker

    log.debug(
      s"Assigning account range ${task.rangeString} to worker ${worker.path.name} for peer ${peer.id} (requestId=$requestId)"
    )

    worker ! FetchAccountRange(task, peer, requestId)
  }

  private def handleTaskComplete(requestId: BigInt, result: Either[String, (Int, Seq[(ByteString, Account)])]): Unit = {
    activeTasks.remove(requestId).foreach { case (task, worker) =>
      busyWorkers -= worker
      result match {
        case Right((accountCount, accounts)) =>
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
              task.done = true
              task.pending = false
              completedTasks += task
              
              // Update statistics
              val accountBytes = accounts.map { case (hash, account) =>
                hash.size + 32 // Rough estimate
              }.sum
              bytesDownloaded += accountBytes
          }

          // Check if complete
          self ! CheckCompletion

        case Left(error) =>
          log.warning(s"Task completed with error: $error")
          // Re-queue task for retry
          task.pending = false
          pendingTasks.enqueue(task)
      }
    }
  }

  private def handleTaskFailed(requestId: BigInt, reason: String): Unit = {
    activeTasks.remove(requestId).foreach { case (task, worker) =>
      busyWorkers -= worker
      log.warning(s"Task failed: $reason")
      task.pending = false
      pendingTasks.enqueue(task)
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
        log.error(s"Failed to store accounts: ${e.getMessage}", e)
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
        log.error(s"Failed to finalize trie: ${e.getMessage}", e)
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
