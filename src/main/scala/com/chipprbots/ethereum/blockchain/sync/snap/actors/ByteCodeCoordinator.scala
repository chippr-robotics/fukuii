package com.chipprbots.ethereum.blockchain.sync.snap.actors

import org.apache.pekko.actor.{Actor, ActorLogging, ActorRef, Props, SupervisorStrategy, OneForOneStrategy}
import org.apache.pekko.actor.SupervisorStrategy._
import org.apache.pekko.util.ByteString

import scala.collection.mutable
import scala.concurrent.duration._

import com.chipprbots.ethereum.blockchain.sync.snap._
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.network.Peer

/** ByteCodeCoordinator manages bytecode download workers.
  *
  * Responsibilities:
  * - Maintain queue of pending bytecode download tasks
  * - Distribute tasks to worker actors
  * - Collect results from workers
  * - Report progress to SNAPSyncController
  *
  * @param evmCodeStorage
  *   Storage for contract bytecodes
  * @param networkPeerManager
  *   Actor for network communication
  * @param requestTracker
  *   Tracker for requests/responses
  * @param batchSize
  *   Number of bytecodes per batch
  * @param snapSyncController
  *   Parent controller to notify of completion
  * @param scheduler
  *   Scheduler for timeouts
  */
class ByteCodeCoordinator(
    evmCodeStorage: EvmCodeStorage,
    networkPeerManager: ActorRef,
    requestTracker: SNAPRequestTracker,
    batchSize: Int,
    snapSyncController: ActorRef
)(implicit scheduler: org.apache.pekko.actor.Scheduler)
    extends Actor
    with ActorLogging {

  import Messages._

  private val bytecodeDownloader = new ByteCodeDownloader(
    evmCodeStorage = evmCodeStorage,
    networkPeerManager = networkPeerManager,
    requestTracker = requestTracker,
    batchSize = batchSize
  )(scheduler)

  private val workers = mutable.ArrayBuffer[ActorRef]()
  private val maxWorkers = 8 // Reasonable default for bytecode downloads

  private var bytecodesDownloaded: Long = 0
  private val startTime = System.currentTimeMillis()

  override def preStart(): Unit = {
    log.info("ByteCodeCoordinator starting")
  }

  override val supervisorStrategy: SupervisorStrategy =
    OneForOneStrategy(maxNrOfRetries = 3, withinTimeRange = 1.minute) {
      case _: Exception =>
        log.warning("ByteCode worker failed, restarting")
        Restart
    }

  override def receive: Receive = {
    case StartByteCodeSync(contractAccounts) =>
      log.info(s"Starting bytecode sync for ${contractAccounts.size} contracts")
      bytecodeDownloader.queueContracts(contractAccounts)

    case ByteCodePeerAvailable(peer) =>
      if (!bytecodeDownloader.isComplete && workers.size < maxWorkers) {
        val worker = createWorker()
        worker ! FetchByteCodes(null, peer) // Worker will fetch from downloader
      }

    case ByteCodeTaskComplete(requestId, result) =>
      result match {
        case Right(count) =>
          bytecodesDownloaded += count
          log.info(s"Bytecode task completed: $count codes")
          self ! ByteCodeCheckCompletion
        case Left(error) =>
          log.warning(s"Bytecode task failed: $error")
      }

    case ByteCodeCheckCompletion =>
      if (bytecodeDownloader.isComplete) {
        log.info("Bytecode sync complete!")
        snapSyncController ! SNAPSyncController.ByteCodeSyncComplete
      }

    case ByteCodeGetProgress =>
      val stats = bytecodeDownloader.statistics
      sender() ! stats
  }

  private def createWorker(): ActorRef = {
    val worker = context.actorOf(
      ByteCodeWorker.props(
        coordinator = self,
        networkPeerManager = networkPeerManager,
        requestTracker = requestTracker,
        bytecodeDownloader = bytecodeDownloader
      )
    )
    workers += worker
    log.debug(s"Created bytecode worker, total: ${workers.size}")
    worker
  }
}

object ByteCodeCoordinator {
  def props(
      evmCodeStorage: EvmCodeStorage,
      networkPeerManager: ActorRef,
      requestTracker: SNAPRequestTracker,
      batchSize: Int,
      snapSyncController: ActorRef
  )(implicit scheduler: org.apache.pekko.actor.Scheduler): Props =
    Props(
      new ByteCodeCoordinator(
        evmCodeStorage,
        networkPeerManager,
        requestTracker,
        batchSize,
        snapSyncController
      )(scheduler)
    )
}
