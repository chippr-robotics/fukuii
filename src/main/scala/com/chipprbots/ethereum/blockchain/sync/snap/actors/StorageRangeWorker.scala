package com.chipprbots.ethereum.blockchain.sync.snap.actors

import org.apache.pekko.actor.{Actor, ActorLogging, ActorRef, Cancellable, Props}

import scala.concurrent.duration._

import com.chipprbots.ethereum.blockchain.sync.snap._

/** StorageRangeWorker fetches storage ranges from a peer.
  *
  * Workers request tasks from the coordinator, handle responses, and report results back.
  *
  * @param coordinator
  *   Parent coordinator that manages all storage sync logic
  * @param networkPeerManager
  *   Network manager
  * @param requestTracker
  *   Request tracker
  */
class StorageRangeWorker(
    coordinator: ActorRef,
    @annotation.unused _networkPeerManager: ActorRef,
    @annotation.unused _requestTracker: SNAPRequestTracker
) extends Actor
    with ActorLogging {

  import Messages._

  private var currentRequestId: Option[BigInt] = None
  private var idleCheckTask: Option[Cancellable] = None

  override def receive: Receive = idle

  override def postStop(): Unit = {
    idleCheckTask.foreach(_.cancel())
    super.postStop()
  }

  def idle: Receive = { case FetchStorageRanges(_, peer) =>
    // Request work from coordinator by notifying it of peer availability
    coordinator ! StoragePeerAvailable(peer)
    context.become(working)

    idleCheckTask = Some(
      context.system.scheduler.scheduleOnce(30.seconds, self, StorageCheckIdle)(context.dispatcher)
    )
  }

  def working: Receive = {
    case StorageRangesResponseMsg(response) =>
      // Forward response to coordinator for processing
      coordinator ! StorageRangesResponseMsg(response)
      idleCheckTask.foreach(_.cancel())
      idleCheckTask = None
      currentRequestId = None
      context.become(idle)

    case StorageCheckIdle =>
      // If still working after timeout, go back to idle
      if (currentRequestId.isEmpty) {
        log.info("[STORAGE-WORKER] idle check: no active request — worker idle, awaiting assignment")
        context.become(idle)
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
      requestTracker: SNAPRequestTracker
  ): Props =
    Props(new StorageRangeWorker(coordinator, networkPeerManager, requestTracker))
}
