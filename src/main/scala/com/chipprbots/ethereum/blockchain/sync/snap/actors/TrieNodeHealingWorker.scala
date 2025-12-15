package com.chipprbots.ethereum.blockchain.sync.snap.actors

import org.apache.pekko.actor.{Actor, ActorLogging, ActorRef, Props}

import scala.concurrent.duration._

import com.chipprbots.ethereum.blockchain.sync.snap._
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.p2p.messages.SNAP._

/** TrieNodeHealingWorker fetches trie nodes from a peer.
  *
  * @param coordinator
  *   Parent coordinator
  * @param networkPeerManager
  *   Network manager
  * @param requestTracker
  *   Request tracker
  * @param trieNodeHealer
  *   Shared healer
  */
class TrieNodeHealingWorker(
    coordinator: ActorRef,
    networkPeerManager: ActorRef,
    requestTracker: SNAPRequestTracker,
    trieNodeHealer: TrieNodeHealer
) extends Actor
    with ActorLogging {

  import Messages._

  private var currentRequestId: Option[BigInt] = None

  override def receive: Receive = idle

  def idle: Receive = {
    case FetchTrieNodes(_, peer) =>
      trieNodeHealer.requestNextBatch(peer) match {
        case Some(requestId) =>
          currentRequestId = Some(requestId)
          context.become(working)
          
          import context.dispatcher
          context.system.scheduler.scheduleOnce(30.seconds, self, HealingRequestTimeout(requestId))

        case None =>
          log.debug("No more trie nodes to heal")
          context.stop(self)
      }
  }

  def working: Receive = {
    case TrieNodesResponseMsg(response) =>
      currentRequestId match {
        case Some(requestId) if response.requestId == requestId =>
          trieNodeHealer.handleResponse(response) match {
            case Right(count) =>
              coordinator ! HealingTaskComplete(requestId, Right(count))
              currentRequestId = None
              context.become(idle)

            case Left(error) =>
              coordinator ! HealingTaskFailed(requestId, error)
              currentRequestId = None
              context.become(idle)
          }
        case _ =>
          log.debug("Received response for wrong request")
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
      requestTracker: SNAPRequestTracker,
      trieNodeHealer: TrieNodeHealer
  ): Props =
    Props(new TrieNodeHealingWorker(coordinator, networkPeerManager, requestTracker, trieNodeHealer))
}
