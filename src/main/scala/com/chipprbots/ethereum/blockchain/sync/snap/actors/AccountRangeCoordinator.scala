package com.chipprbots.ethereum.blockchain.sync.snap.actors

import org.apache.pekko.actor.{Actor, ActorLogging, ActorRef, Props, SupervisorStrategy, OneForOneStrategy}
import org.apache.pekko.actor.SupervisorStrategy._
import org.apache.pekko.util.ByteString

import scala.collection.mutable
import scala.concurrent.duration._

import com.chipprbots.ethereum.blockchain.sync.snap._
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.network.Peer

/** AccountRangeCoordinator manages account range download workers.
  *
  * Responsibilities:
  * - Maintain queue of pending account range tasks
  * - Distribute tasks to worker actors
  * - Collect results from workers
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

  // Statistics
  private var accountsDownloaded: Long = 0
  private var bytesDownloaded: Long = 0
  private val startTime = System.currentTimeMillis()

  // Contract accounts collected for bytecode download
  private val contractAccounts = mutable.ArrayBuffer[(ByteString, ByteString)]()

  // State trie builder (shared across workers via coordinator)
  private val accountRangeDownloader = new AccountRangeDownloader(
    stateRoot = stateRoot,
    networkPeerManager = networkPeerManager,
    requestTracker = requestTracker,
    mptStorage = mptStorage,
    concurrency = concurrency
  )

  override def preStart(): Unit = {
    log.info(s"AccountRangeCoordinator starting with $concurrency workers")
    // Workers are created on-demand when peers become available
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
      if (pendingTasks.nonEmpty && workers.size < concurrency) {
        // Create worker and assign task
        val worker = createWorker()
        val task = pendingTasks.dequeue()
        worker ! FetchAccountRange(task, peer)
      } else if (pendingTasks.nonEmpty && workers.nonEmpty) {
        // Reuse existing idle worker
        // For simplicity, just send to first worker (it will handle if busy)
        val worker = workers.head
        val task = pendingTasks.dequeue()
        worker ! FetchAccountRange(task, peer)
      } else {
        log.debug("No pending tasks or max workers reached")
      }

    case TaskComplete(requestId, result) =>
      handleTaskComplete(requestId, result)

    case TaskFailed(requestId, reason) =>
      handleTaskFailed(requestId, reason)

    case GetProgress =>
      val progress = calculateProgress()
      sender() ! progress

    case CheckCompletion =>
      if (isComplete) {
        log.info("Account range sync complete!")
        // Finalize trie before reporting completion
        accountRangeDownloader.finalizeTrie() match {
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
        requestTracker = requestTracker,
        accountRangeDownloader = accountRangeDownloader
      )
    )
    workers += worker
    log.debug(s"Created worker ${worker.path.name}, total workers: ${workers.size}")
    worker
  }

  private def handleTaskComplete(requestId: BigInt, result: Either[String, Int]): Unit = {
    activeTasks.remove(requestId).foreach { case (task, worker) =>
      result match {
        case Right(accountCount) =>
          log.info(s"Task completed successfully: $accountCount accounts")
          accountsDownloaded += accountCount
          task.done = true
          task.pending = false
          completedTasks += task

          // Check for continuation tasks (handled by worker/downloader)

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
      log.warning(s"Task failed: $reason")
      task.pending = false
      pendingTasks.enqueue(task)
    }
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
      contractAccountsFound = accountRangeDownloader.getContractAccountCount
    )
  }

  private def isComplete: Boolean = {
    pendingTasks.isEmpty && activeTasks.isEmpty
  }

  def getContractAccounts: Seq[(ByteString, ByteString)] = {
    accountRangeDownloader.getContractAccounts
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
