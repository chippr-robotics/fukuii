package com.chipprbots.ethereum.blockchain.sync

import org.apache.pekko.actor.Actor
import org.apache.pekko.actor.ActorLogging
import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.Cancellable
import org.apache.pekko.actor.Props
import org.apache.pekko.actor.Scheduler

import scala.concurrent.ExecutionContext
import scala.reflect.ClassTag

import com.chipprbots.ethereum.blockchain.sync.Blacklist.BlacklistReason
import com.chipprbots.ethereum.blockchain.sync.PeerListSupportNg.PeerWithInfo
import com.chipprbots.ethereum.network.EtcPeerManagerActor.PeerInfo
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.p2p.Message
import com.chipprbots.ethereum.network.p2p.MessageSerializable
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.network.p2p.messages.Codes
import com.chipprbots.ethereum.network.p2p.messages.ETH62._
import com.chipprbots.ethereum.network.p2p.messages.ETH63.GetNodeData
import com.chipprbots.ethereum.network.p2p.messages.ETH63.NodeData
import com.chipprbots.ethereum.network.p2p.messages.ETH66.{BlockHeaders => ETH66BlockHeaders}
import com.chipprbots.ethereum.network.p2p.messages.ETH66.{GetBlockHeaders => ETH66GetBlockHeaders}
import com.chipprbots.ethereum.utils.Config.SyncConfig

class PeersClient(
    val etcPeerManager: ActorRef,
    val peerEventBus: ActorRef,
    val blacklist: Blacklist,
    val syncConfig: SyncConfig,
    implicit val scheduler: Scheduler
) extends Actor
    with ActorLogging
    with PeerListSupportNg {
  import PeersClient._

  implicit val ec: ExecutionContext = context.dispatcher

  val statusSchedule: Cancellable =
    scheduler.scheduleWithFixedDelay(syncConfig.printStatusInterval, syncConfig.printStatusInterval, self, PrintStatus)

  def receive: Receive = running(Map())

  override def postStop(): Unit = {
    super.postStop()
    statusSchedule.cancel()
  }

  def running(requesters: Requesters): Receive =
    handlePeerListMessages.orElse {
      case PrintStatus                   => printStatus(requesters: Requesters)
      case BlacklistPeer(peerId, reason) => blacklistIfHandshaked(peerId, syncConfig.blacklistDuration, reason)
      case Request(message, peerSelector, toSerializable) =>
        val requester = sender()
        log.debug(
          "Received request for message type {} using selector {}",
          message.getClass.getSimpleName,
          peerSelector
        )
        log.debug(
          "Total handshaked peers: {}, Available peers (not blacklisted): {}",
          handshakedPeers.size,
          peersToDownloadFrom.size
        )

        if (peersToDownloadFrom.isEmpty && handshakedPeers.nonEmpty) {
          log.debug("All {} handshaked peers are blacklisted", handshakedPeers.size)
          handshakedPeers.foreach { case (peerId, peerInfo) =>
            log.debug(
              "Peer {} ({}): blacklisted={}",
              peerId,
              peerInfo.peer.remoteAddress,
              blacklist.isBlacklisted(peerId)
            )
          }
        }

        selectPeer(peerSelector) match {
          case Some(peer) =>
            log.debug("Selected peer {} with address {} for request", peer.id, peer.remoteAddress.getHostString)
            // Adapt message format based on peer's negotiated capability
            val adaptedMessage = adaptMessageForPeer(peer, message)
            val handler =
              makeRequest(peer, adaptedMessage, responseMsgCode(adaptedMessage), toSerializable)(scheduler, responseClassTag(adaptedMessage))
            val newRequesters = requesters + (handler -> requester)
            context.become(running(newRequesters))
          case None =>
            log.debug(
              "No suitable peer found to issue a request (handshaked: {}, available: {})",
              handshakedPeers.size,
              peersToDownloadFrom.size
            )
            requester ! NoSuitablePeer
        }
      case PeerRequestHandler.ResponseReceived(peer, message, _) =>
        handleResponse(requesters, Response(peer, message.asInstanceOf[Message]))
      case PeerRequestHandler.RequestFailed(peer, reason) =>
        log.warning(s"Request to peer ${peer.remoteAddress} failed - reason: $reason")
        handleResponse(requesters, RequestFailed(peer, BlacklistReason.RegularSyncRequestFailed(reason)))
    }

  private def makeRequest[RequestMsg <: Message, ResponseMsg <: Message](
      peer: Peer,
      requestMsg: RequestMsg,
      responseMsgCode: Int,
      toSerializable: RequestMsg => MessageSerializable
  )(implicit scheduler: Scheduler, classTag: ClassTag[ResponseMsg]): ActorRef =
    context.actorOf(
      PeerRequestHandler.props[RequestMsg, ResponseMsg](
        peer = peer,
        responseTimeout = syncConfig.peerResponseTimeout,
        etcPeerManager = etcPeerManager,
        peerEventBus = peerEventBus,
        requestMsg = requestMsg,
        responseMsgCode = responseMsgCode
      )(classTag, scheduler, toSerializable)
    )

  private def handleResponse[ResponseMsg <: ResponseMessage](requesters: Requesters, responseMsg: ResponseMsg): Unit = {
    val requestHandler = sender()
    requesters.get(requestHandler).foreach(_ ! responseMsg)
    context.become(running(requesters - requestHandler))
  }

  private def selectPeer(peerSelector: PeerSelector): Option[Peer] =
    peerSelector match {
      case BestPeer =>
        log.debug("Selecting best peer from {} available peers", peersToDownloadFrom.size)
        bestPeer(peersToDownloadFrom, log)
    }

  /** Adapts message format based on peer's negotiated capability.
    * ETH66+ peers use RequestId wrapper, earlier versions use ETH62 format.
    */
  private def adaptMessageForPeer[RequestMsg <: Message](peer: Peer, message: RequestMsg): Message = {
    handshakedPeers.get(peer.id) match {
      case Some(peerWithInfo) =>
        val usesRequestId = Capability.usesRequestId(peerWithInfo.peerInfo.remoteStatus.capability)
        message match {
          case eth66: ETH66GetBlockHeaders if !usesRequestId =>
            // Convert ETH66 format to ETH62 format for older peers
            ETH62.GetBlockHeaders(eth66.block, eth66.maxHeaders, eth66.skip, eth66.reverse)
          case eth62: ETH62.GetBlockHeaders if usesRequestId =>
            // Convert ETH62 format to ETH66 format for newer peers  
            ETH66GetBlockHeaders(0, eth62.block, eth62.maxHeaders, eth62.skip, eth62.reverse)
          case _ => message // Already in correct format or not a GetBlockHeaders message
        }
      case None =>
        log.warning("Peer {} not found in handshaked peers, using message as-is", peer.id)
        message
    }
  }

  private def responseClassTag[RequestMsg <: Message](requestMsg: RequestMsg): ClassTag[_ <: Message] =
    requestMsg match {
      case _: ETH66GetBlockHeaders | _: ETH62.GetBlockHeaders => implicitly[ClassTag[ETH66BlockHeaders]]
      case _: GetBlockBodies  => implicitly[ClassTag[BlockBodies]]
      case _: GetNodeData     => implicitly[ClassTag[NodeData]]
    }

  private def responseMsgCode[RequestMsg <: Message](requestMsg: RequestMsg): Int =
    requestMsg match {
      case _: ETH66GetBlockHeaders | _: ETH62.GetBlockHeaders => Codes.BlockHeadersCode
      case _: GetBlockBodies  => Codes.BlockBodiesCode
      case _: GetNodeData     => Codes.NodeDataCode
    }

  private def printStatus(requesters: Requesters): Unit = {
    log.debug(
      "Request status: requests in progress: {}, available peers: {}",
      requesters.size,
      peersToDownloadFrom.size
    )

    lazy val handshakedPeersStatus = handshakedPeers.map { case (peerId, peerWithInfo) =>
      val peerNetworkStatus = PeerNetworkStatus(
        peerWithInfo.peer,
        isBlacklisted = blacklist.isBlacklisted(peerId)
      )
      (peerNetworkStatus, peerWithInfo.peerInfo)
    }

    log.debug(s"Handshaked peers status (number of peers: ${handshakedPeersStatus.size}): $handshakedPeersStatus")
  }
}

object PeersClient {

  def props(
      etcPeerManager: ActorRef,
      peerEventBus: ActorRef,
      blacklist: Blacklist,
      syncConfig: SyncConfig,
      scheduler: Scheduler
  ): Props =
    Props(new PeersClient(etcPeerManager, peerEventBus, blacklist, syncConfig, scheduler))

  type Requesters = Map[ActorRef, ActorRef]

  sealed trait PeersClientMessage
  case class BlacklistPeer(peerId: PeerId, reason: BlacklistReason) extends PeersClientMessage
  case class Request[RequestMsg <: Message](
      message: RequestMsg,
      peerSelector: PeerSelector,
      toSerializable: RequestMsg => MessageSerializable
  ) extends PeersClientMessage

  object Request {

    def create[RequestMsg <: Message](message: RequestMsg, peerSelector: PeerSelector)(implicit
        toSerializable: RequestMsg => MessageSerializable
    ): Request[RequestMsg] =
      Request(message, peerSelector, toSerializable)
  }

  case class PeerNetworkStatus(peer: Peer, isBlacklisted: Boolean) {
    override def toString: String =
      s"PeerNetworkStatus {" +
        s" RemotePeerAddress: ${peer.remoteAddress}," +
        s" ConnectionDirection: ${if (peer.incomingConnection) "Incoming" else "Outgoing"}," +
        s" Is blacklisted?: $isBlacklisted" +
        s" }"
  }
  case object PrintStatus extends PeersClientMessage

  sealed trait ResponseMessage
  case object NoSuitablePeer extends ResponseMessage
  case class RequestFailed(peer: Peer, reason: BlacklistReason) extends ResponseMessage
  case class Response[T <: Message](peer: Peer, message: T) extends ResponseMessage

  sealed trait PeerSelector
  case object BestPeer extends PeerSelector

  def bestPeer(
      peersToDownloadFrom: Map[PeerId, PeerWithInfo],
      log: org.apache.pekko.event.LoggingAdapter
  ): Option[Peer] = {
    log.debug("Evaluating {} peers to find best peer", peersToDownloadFrom.size)

    val peersToUse = peersToDownloadFrom.values
      .map { case PeerWithInfo(peer, peerInfo) =>
        val isReady = peerInfo.forkAccepted
        log.debug(
          "Peer {} ({}) - ready: {}, maxBlock: {}",
          peer.id,
          peer.remoteAddress,
          isReady,
          peerInfo.maxBlockNumber
        )
        log.debug("Peer {} chainWeight: {}", peer.id, peerInfo.chainWeight)
        (peer, peerInfo, isReady)
      }
      .collect { case (peer, PeerInfo(_, chainWeight, true, _, _), _) =>
        log.debug("Peer {} is ready and eligible for selection", peer.id)
        log.debug("Peer {} chainWeight: {}", peer.id, chainWeight)
        (peer, chainWeight)
      }

    if (peersToUse.nonEmpty) {
      val (peer, chainWeight) = peersToUse.maxBy(_._2)
      log.debug("Selected best peer {} with chainWeight {}", peer.id, chainWeight)
      Some(peer)
    } else {
      log.debug("No ready peers available for selection from {} total peers", peersToDownloadFrom.size)
      None
    }
  }

  // Legacy method for backward compatibility
  def bestPeer(peersToDownloadFrom: Map[PeerId, PeerWithInfo]): Option[Peer] = {
    val peersToUse = peersToDownloadFrom.values
      .collect { case PeerWithInfo(peer, PeerInfo(_, chainWeight, true, _, _)) =>
        (peer, chainWeight)
      }

    if (peersToUse.nonEmpty) {
      val (peer, _) = peersToUse.maxBy(_._2)
      Some(peer)
    } else {
      None
    }
  }
}
