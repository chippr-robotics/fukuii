package com.chipprbots.ethereum.blockchain.sync.snap.actors

import org.apache.pekko.actor.{Actor, ActorLogging, ActorRef, Props}

import scala.concurrent.duration._

import com.chipprbots.ethereum.blockchain.sync.snap._
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.p2p.messages.SNAP._

/** StorageRangeWorker fetches storage ranges from a peer.
  *
  * @param coordinator
  *   Parent coordinator
  * @param networkPeerManager
  *   Network manager
  * @param requestTracker
  *   Request tracker
  * @param storageDownloader
  *   Shared downloader
  */
class StorageRangeWorker(
    coordinator: ActorRef,
    networkPeerManager: ActorRef,
    requestTracker: SNAPRequestTracker,
    storageDownloader: StorageRangeDownloader
) extends Actor
    with ActorLogging {

  import Messages._

  private var currentRequestId: Option[BigInt] = None

  override def receive: Receive = idle

  def idle: Receive = {
    case FetchStorageRanges(_, peer) =>
      storageDownloader.requestNextRanges(peer) match {
        case Some(requestId) =>
          currentRequestId = Some(requestId)
          context.become(working)
          
          import context.dispatcher
          context.system.scheduler.scheduleOnce(30.seconds, self, StorageRequestTimeout(requestId))

        case None =>
          log.debug("No more storage ranges to fetch")
          context.stop(self)
      }
  }

  def working: Receive = {
    case StorageRangesResponseMsg(response) =>
      currentRequestId match {
        case Some(requestId) if response.requestId == requestId =>
          storageDownloader.handleResponse(response) match {
            case Right(count) =>
              coordinator ! StorageTaskComplete(requestId, Right(count))
              currentRequestId = None
              context.become(idle)

            case Left(error) =>
              coordinator ! StorageTaskFailed(requestId, error)
              currentRequestId = None
              context.become(idle)
          }
        case _ =>
          log.debug("Received response for wrong request")
      }

    case StorageRequestTimeout(requestId) =>
      currentRequestId match {
        case Some(reqId) if reqId == requestId =>
          log.warning(s"Storage request $requestId timed out")
          coordinator ! StorageTaskFailed(requestId, "Timeout")
          currentRequestId = None
          context.become(idle)
        case _ =>
      }
  }
}

object StorageRangeWorker {
  def props(
      coordinator: ActorRef,
      networkPeerManager: ActorRef,
      requestTracker: SNAPRequestTracker,
      storageDownloader: StorageRangeDownloader
  ): Props =
    Props(new StorageRangeWorker(coordinator, networkPeerManager, requestTracker, storageDownloader))
}
