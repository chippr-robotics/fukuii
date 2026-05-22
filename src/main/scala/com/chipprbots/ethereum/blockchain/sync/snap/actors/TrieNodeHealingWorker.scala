package com.chipprbots.ethereum.blockchain.sync.snap.actors

import org.apache.pekko.actor.{Actor, ActorLogging, ActorRef, Cancellable, Props}

import scala.concurrent.duration._

import com.chipprbots.ethereum.blockchain.sync.snap._

/** TrieNodeHealingWorker fetches trie nodes from a peer.
  *
  * Workers request tasks from the coordinator, handle responses, and report results back.
  *
  * @param coordinator
  *   Parent coordinator that manages all healing logic
  * @param networkPeerManager
  *   Network manager
  * @param requestTracker
  *   Request tracker
  */
class TrieNodeHealingWorker(
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

  def idle: Receive = { case FetchTrieNodes(_, peer) =>
    // Request work from coordinator by notifying it of peer availability
    coordinator ! HealingPeerAvailable(peer)
    context.become(working)

    idleCheckTask = Some(
      context.system.scheduler.scheduleOnce(30.seconds, self, HealingCheckIdle)(context.dispatcher)
    )
  }

  def working: Receive = {
    case TrieNodesResponseMsg(response) =>
      // Forward response to coordinator for processing
      coordinator ! TrieNodesResponseMsg(response)
      idleCheckTask.foreach(_.cancel())
      idleCheckTask = None
      currentRequestId = None
      context.become(idle)

    case HealingCheckIdle =>
      // If still working after timeout, go back to idle
      if (currentRequestId.isEmpty) {
        log.info("[healing-worker] idle check: no active request — worker idle, awaiting assignment")
        context.become(idle)
      }

    case HealingRequestTimeout(requestId) =>
      currentRequestId match {
        case Some(reqId) if reqId == requestId =>
          log.warning(s"Healing request $requestId timed out")
          coordinator ! HealingTaskFailed(requestId, "Timeout")
          currentRequestId = None
          context.become(idle)
        case _ =>
      }
  }
}

object TrieNodeHealingWorker {
  def props(
      coordinator: ActorRef,
      networkPeerManager: ActorRef,
      requestTracker: SNAPRequestTracker
  ): Props =
    Props(new TrieNodeHealingWorker(coordinator, networkPeerManager, requestTracker))
}
