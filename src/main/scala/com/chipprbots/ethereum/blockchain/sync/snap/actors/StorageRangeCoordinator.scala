package com.chipprbots.ethereum.blockchain.sync.snap.actors

import org.apache.pekko.actor.{Actor, ActorLogging, ActorRef, Props, SupervisorStrategy, OneForOneStrategy}
import org.apache.pekko.actor.SupervisorStrategy._
import org.apache.pekko.util.ByteString

import scala.collection.mutable
import scala.concurrent.duration._

import com.chipprbots.ethereum.blockchain.sync.snap._
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.network.Peer

/** StorageRangeCoordinator manages storage range download workers.
  *
  * @param stateRoot
  *   State root hash
  * @param networkPeerManager
  *   Network manager
  * @param requestTracker
  *   Request tracker
  * @param mptStorage
  *   MPT storage
  * @param maxAccountsPerBatch
  *   Max accounts per batch
  * @param snapSyncController
  *   Parent controller
  */
class StorageRangeCoordinator(
    stateRoot: ByteString,
    networkPeerManager: ActorRef,
    requestTracker: SNAPRequestTracker,
    mptStorage: MptStorage,
    maxAccountsPerBatch: Int,
    snapSyncController: ActorRef
) extends Actor
    with ActorLogging {

  import Messages._

  private val storageDownloader = new StorageRangeDownloader(
    stateRoot = stateRoot,
    networkPeerManager = networkPeerManager,
    requestTracker = requestTracker,
    mptStorage = mptStorage,
    maxAccountsPerBatch = maxAccountsPerBatch
  )

  private val workers = mutable.ArrayBuffer[ActorRef]()
  private val maxWorkers = 8

  private var slotsDownloaded: Long = 0

  override def preStart(): Unit = {
    log.info("StorageRangeCoordinator starting")
  }

  override val supervisorStrategy: SupervisorStrategy =
    OneForOneStrategy(maxNrOfRetries = 3, withinTimeRange = 1.minute) {
      case _: Exception =>
        log.warning("Storage worker failed, restarting")
        Restart
    }

  override def receive: Receive = {
    case StartStorageRangeSync(root) =>
      log.info(s"Starting storage range sync for state root ${root.take(8).toHex}")

    case StoragePeerAvailable(peer) =>
      if (!storageDownloader.isComplete && workers.size < maxWorkers) {
        val worker = createWorker()
        worker ! FetchStorageRanges(null, peer)
      }

    case StorageTaskComplete(requestId, result) =>
      result match {
        case Right(count) =>
          slotsDownloaded += count
          log.info(s"Storage task completed: $count slots")
          self ! StorageCheckCompletion
        case Left(error) =>
          log.warning(s"Storage task failed: $error")
      }

    case StorageCheckCompletion =>
      if (storageDownloader.isComplete) {
        log.info("Storage range sync complete!")
        snapSyncController ! SNAPSyncController.StorageRangeSyncComplete
      }

    case StorageGetProgress =>
      val stats = storageDownloader.statistics
      sender() ! stats
  }

  private def createWorker(): ActorRef = {
    val worker = context.actorOf(
      StorageRangeWorker.props(
        coordinator = self,
        networkPeerManager = networkPeerManager,
        requestTracker = requestTracker,
        storageDownloader = storageDownloader
      )
    )
    workers += worker
    log.debug(s"Created storage worker, total: ${workers.size}")
    worker
  }
}

object StorageRangeCoordinator {
  def props(
      stateRoot: ByteString,
      networkPeerManager: ActorRef,
      requestTracker: SNAPRequestTracker,
      mptStorage: MptStorage,
      maxAccountsPerBatch: Int,
      snapSyncController: ActorRef
  ): Props =
    Props(
      new StorageRangeCoordinator(
        stateRoot,
        networkPeerManager,
        requestTracker,
        mptStorage,
        maxAccountsPerBatch,
        snapSyncController
      )
    )
}
