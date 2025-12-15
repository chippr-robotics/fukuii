package com.chipprbots.ethereum.blockchain.sync.snap.actors

import org.apache.pekko.actor.{Actor, ActorLogging, ActorRef, Props}

import scala.concurrent.duration._

import com.chipprbots.ethereum.blockchain.sync.snap._
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.p2p.messages.SNAP._

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
    networkPeerManager: ActorRef,
    requestTracker: SNAPRequestTracker
) extends Actor
    with ActorLogging {

  import Messages._

  private var currentRequestId: Option[BigInt] = None
  private var currentPeer: Option[Peer] = None

  override def receive: Receive = idle

  def idle: Receive = {
    case FetchTrieNodes(_, peer) =>
      currentPeer = Some(peer)
      // Request work from coordinator by notifying it of peer availability
      coordinator ! HealingPeerAvailable(peer)
      context.become(working)
          
      import context.dispatcher
      context.system.scheduler.scheduleOnce(30.seconds, self, HealingCheckIdle)
  }

  def working: Receive = {
    case TrieNodesResponseMsg(response) =>
      // Forward response to coordinator for processing
      coordinator ! TrieNodesResponseMsg(response)
      currentRequestId = None
      currentPeer = None
      context.become(idle)

    case HealingCheckIdle =>
      // If still working after timeout, go back to idle
      if (currentRequestId.isEmpty) {
        context.become(idle)
      }

    case HealingRequestTimeout(requestId) =>
      currentRequestId match {
        case Some(reqId) if reqId == requestId =>
          log.warning(s"Healing request $requestId timed out")
          coordinator ! HealingTaskFailed(requestId, "Timeout")
          currentRequestId = None
          currentPeer = None
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
