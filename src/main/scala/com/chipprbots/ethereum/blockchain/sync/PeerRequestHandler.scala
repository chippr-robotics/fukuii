package com.chipprbots.ethereum.blockchain.sync

import org.apache.pekko.actor._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.FiniteDuration
import scala.reflect.ClassTag

import com.chipprbots.ethereum.network.EtcPeerManagerActor
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent.MessageFromPeer
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent.PeerDisconnected
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerSelector
import com.chipprbots.ethereum.network.PeerEventBusActor.Subscribe
import com.chipprbots.ethereum.network.PeerEventBusActor.SubscriptionClassifier.MessageClassifier
import com.chipprbots.ethereum.network.PeerEventBusActor.SubscriptionClassifier.PeerDisconnectedClassifier
import com.chipprbots.ethereum.network.PeerEventBusActor.Unsubscribe
import com.chipprbots.ethereum.network.p2p.Message
import com.chipprbots.ethereum.network.p2p.MessageSerializable

class PeerRequestHandler[RequestMsg <: Message, ResponseMsg <: Message: ClassTag](
    peer: Peer,
    responseTimeout: FiniteDuration,
    etcPeerManager: ActorRef,
    peerEventBus: ActorRef,
    requestMsg: RequestMsg,
    responseMsgCode: Int
)(implicit scheduler: Scheduler, toSerializable: RequestMsg => MessageSerializable)
    extends Actor
    with ActorLogging {

  import PeerRequestHandler._

  private val initiator: ActorRef = context.parent

  private val timeout: Cancellable = scheduler.scheduleOnce(responseTimeout, self, Timeout)

  private val startTime: Long = System.currentTimeMillis()

  private def subscribeMessageClassifier = MessageClassifier(Set(responseMsgCode), PeerSelector.WithId(peer.id))

  private def timeTakenSoFar(): Long = System.currentTimeMillis() - startTime

  override def preStart(): Unit = {
    log.info(
      "PEER_REQUEST: Starting request to peer={}, reqType={}, respCode=0x{}, timeout={}ms",
      peer.id,
      requestMsg.getClass.getSimpleName,
      responseMsgCode.toHexString,
      responseTimeout.toMillis
    )
    log.debug(
      "PEER_REQUEST: Request details: {}",
      requestMsg.toShortString
    )
    etcPeerManager ! EtcPeerManagerActor.SendMessage(toSerializable(requestMsg), peer.id)
    peerEventBus ! Subscribe(PeerDisconnectedClassifier(PeerSelector.WithId(peer.id)))
    peerEventBus ! Subscribe(subscribeMessageClassifier)
  }

  override def receive: Receive = {
    case MessageFromPeer(responseMsg: ResponseMsg, _)  => handleResponseMsg(responseMsg)
    case Timeout                                       => handleTimeout()
    case PeerDisconnected(peerId) if peerId == peer.id => handleTerminated()
  }

  def handleResponseMsg(responseMsg: ResponseMsg): Unit = {
    val elapsed = timeTakenSoFar()
    log.info(
      "PEER_REQUEST_SUCCESS: peer={}, reqType={}, respType={}, elapsed={}ms",
      peer.id,
      requestMsg.getClass.getSimpleName,
      responseMsg.getClass.getSimpleName,
      elapsed
    )
    log.debug("PEER_REQUEST_SUCCESS: Response details: {}", responseMsg.toShortString)
    cleanupAndStop()
    initiator ! ResponseReceived(peer, responseMsg, timeTaken = elapsed)
  }

  def handleTimeout(): Unit = {
    val elapsed = timeTakenSoFar()
    log.error(
      "PEER_REQUEST_TIMEOUT: peer={}, reqType={}, elapsed={}ms (timeout={}ms)",
      peer.id,
      requestMsg.getClass.getSimpleName,
      elapsed,
      responseTimeout.toMillis
    )
    cleanupAndStop()
    initiator ! RequestFailed(peer, "request timeout")
  }

  def handleTerminated(): Unit = {
    val elapsed = timeTakenSoFar()
    log.error(
      "PEER_REQUEST_DISCONNECTED: peer={}, reqType={}, elapsed={}ms - connection closed before response",
      peer.id,
      requestMsg.getClass.getSimpleName,
      elapsed
    )
    cleanupAndStop()
    initiator ! RequestFailed(peer, "connection closed")
  }

  def cleanupAndStop(): Unit = {
    timeout.cancel()
    peerEventBus ! Unsubscribe()
    context.stop(self)
  }
}

object PeerRequestHandler {
  def props[RequestMsg <: Message, ResponseMsg <: Message: ClassTag](
      peer: Peer,
      responseTimeout: FiniteDuration,
      etcPeerManager: ActorRef,
      peerEventBus: ActorRef,
      requestMsg: RequestMsg,
      responseMsgCode: Int
  )(implicit scheduler: Scheduler, toSerializable: RequestMsg => MessageSerializable): Props =
    Props(new PeerRequestHandler(peer, responseTimeout, etcPeerManager, peerEventBus, requestMsg, responseMsgCode))

  final case class RequestFailed(peer: Peer, reason: String)
  final case class ResponseReceived[T](peer: Peer, response: T, timeTaken: Long)

  private case object Timeout
}
