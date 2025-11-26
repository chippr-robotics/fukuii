package com.chipprbots.ethereum.network

import org.apache.pekko.actor.Actor
import org.apache.pekko.actor.ActorLogging
import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.Props
import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.network.EtcPeerManagerActor._
import com.chipprbots.ethereum.network.PeerActor.DisconnectPeer
import com.chipprbots.ethereum.network.PeerActor.SendMessage
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent._
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerSelector
import com.chipprbots.ethereum.network.PeerEventBusActor.Subscribe
import com.chipprbots.ethereum.network.PeerEventBusActor.SubscriptionClassifier._
import com.chipprbots.ethereum.network.PeerEventBusActor.Unsubscribe
import com.chipprbots.ethereum.network.handshaker.Handshaker.HandshakeResult
import com.chipprbots.ethereum.network.p2p.Message
import com.chipprbots.ethereum.network.p2p.MessageSerializable
import com.chipprbots.ethereum.network.p2p.messages.BaseETH6XMessages
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.network.p2p.messages.Codes
import com.chipprbots.ethereum.network.p2p.messages.ETC64
import com.chipprbots.ethereum.network.p2p.messages.ETC64.NewBlock
import com.chipprbots.ethereum.network.p2p.messages.ETH62.{BlockHeaders => ETH62BlockHeaders}
import com.chipprbots.ethereum.network.p2p.messages.ETH62.{GetBlockHeaders => ETH62GetBlockHeaders}
import com.chipprbots.ethereum.network.p2p.messages.ETH62.NewBlockHashes
import com.chipprbots.ethereum.network.p2p.messages.ETH66
import com.chipprbots.ethereum.network.p2p.messages.ETH66.{BlockHeaders => ETH66BlockHeaders}
import com.chipprbots.ethereum.network.p2p.messages.ETH66.{GetBlockHeaders => ETH66GetBlockHeaders}
import com.chipprbots.ethereum.network.p2p.messages.ETH64
import com.chipprbots.ethereum.network.p2p.messages.WireProtocol.Disconnect
import com.chipprbots.ethereum.utils.ByteStringUtils

import org.bouncycastle.util.encoders.Hex

/** EtcPeerManager actor is in charge of keeping updated information about each peer, while also being able to query it
  * for this information. In order to do so it receives events for peer creation, disconnection and new messages being
  * sent and received by each peer.
  */
class EtcPeerManagerActor(
    peerManagerActor: ActorRef,
    peerEventBusActor: ActorRef,
    appStateStorage: AppStateStorage,
    forkResolverOpt: Option[ForkResolver]
) extends Actor
    with ActorLogging {

  private[network] type PeersWithInfo = Map[PeerId, PeerWithInfo]
  
  // Maximum length for hex string in debug logs (to avoid very long log lines)
  private val MaxHexLogLength = 200

  // Subscribe to the event of any peer getting handshaked
  peerEventBusActor ! Subscribe(PeerHandshaked)

  override def receive: Receive = handleMessages(Map.empty)

  /** Processes both messages for updating the information about each peer and for requesting this information
    *
    * @param peersWithInfo,
    *   which has the peer and peer information for each handshaked peer (identified by it's id)
    */
  def handleMessages(peersWithInfo: PeersWithInfo): Receive =
    handleCommonMessages(peersWithInfo).orElse(handlePeersInfoEvents(peersWithInfo))

  private def peerHasUpdatedBestBlock(peerInfo: PeerInfo): Boolean = {
    val peerBestBlockIsItsGenesisBlock = peerInfo.bestBlockHash == peerInfo.remoteStatus.genesisHash
    peerBestBlockIsItsGenesisBlock || (!peerBestBlockIsItsGenesisBlock && peerInfo.maxBlockNumber > 0)
  }

  /** Processes both messages for sending messages and for requesting peer information
    *
    * @param peersWithInfo,
    *   which has the peer and peer information for each handshaked peer (identified by it's id)
    */
  private def handleCommonMessages(peersWithInfo: PeersWithInfo): Receive = {
    case GetHandshakedPeers =>
      // Provide only peers which already responded to request for best block hash, and theirs best block hash is different
      // form their genesis block
      sender() ! HandshakedPeers(peersWithInfo.collect {
        case (_, PeerWithInfo(peer, peerInfo)) if peerHasUpdatedBestBlock(peerInfo) => peer -> peerInfo
      })

    case PeerInfoRequest(peerId) =>
      val peerInfoOpt = peersWithInfo.get(peerId).map { case PeerWithInfo(_, peerInfo) => peerInfo }
      sender() ! PeerInfoResponse(peerInfoOpt)

    case EtcPeerManagerActor.SendMessage(message, peerId) =>
      NetworkMetrics.SentMessagesCounter.increment()
      val newPeersWithInfo = updatePeersWithInfo(peersWithInfo, peerId, message.underlyingMsg, handleSentMessage)
      peerManagerActor ! PeerManagerActor.SendMessage(message, peerId)
      context.become(handleMessages(newPeersWithInfo))
  }

  /** Processes events and updating the information about each peer
    *
    * @param peersWithInfo,
    *   which has the peer and peer information for each handshaked peer (identified by it's id)
    */
  private def handlePeersInfoEvents(peersWithInfo: PeersWithInfo): Receive = {

    case MessageFromPeer(message, peerId) if peersWithInfo.contains(peerId) =>
      val newPeersWithInfo = updatePeersWithInfo(peersWithInfo, peerId, message, handleReceivedMessage)
      NetworkMetrics.ReceivedMessagesCounter.increment()
      context.become(handleMessages(newPeersWithInfo))

    case PeerHandshakeSuccessful(peer, peerInfo: PeerInfo) =>
      log.info(
        "PEER_HANDSHAKE_SUCCESS: Peer {} handshake successful. Capability: {}, BestHash: {}, TotalDifficulty: {}",
        peer.id,
        peerInfo.remoteStatus.capability,
        ByteStringUtils.hash2string(peerInfo.remoteStatus.bestHash),
        peerInfo.remoteStatus.chainWeight.totalDifficulty
      )
      peerEventBusActor ! Subscribe(PeerDisconnectedClassifier(PeerSelector.WithId(peer.id)))
      peerEventBusActor ! Subscribe(MessageClassifier(msgCodesWithInfo, PeerSelector.WithId(peer.id)))

      // Ask for the highest block from the peer
      // Send GetBlockHeaders in format based on negotiated capability
      val usesRequestId = Capability.usesRequestId(peerInfo.remoteStatus.capability)
      val getBlockHeadersMsg: MessageSerializable =
        if (usesRequestId)
          ETH66GetBlockHeaders(ETH66.nextRequestId, Right(peerInfo.remoteStatus.bestHash), 1, 0, reverse = false)
        else
          ETH62GetBlockHeaders(Right(peerInfo.remoteStatus.bestHash), 1, 0, reverse = false)
      
      // Debug: Log the raw RLP-encoded message bytes for protocol analysis
      if (log.isDebugEnabled) {
        val encodedBytes = getBlockHeadersMsg.toBytes
        val hexBytes = Hex.toHexString(encodedBytes)
        log.debug(
          "PEER_HANDSHAKE_SUCCESS: GetBlockHeaders RLP bytes (len={}): {}",
          encodedBytes.length,
          if (hexBytes.length > MaxHexLogLength) hexBytes.take(MaxHexLogLength) + "..." else hexBytes
        )
      }
      
      log.info(
        "PEER_HANDSHAKE_SUCCESS: Sending GetBlockHeaders to peer {} (usesRequestId: {}, bestHash: {})",
        peer.id,
        usesRequestId,
        ByteStringUtils.hash2string(peerInfo.remoteStatus.bestHash)
      )
      peer.ref ! SendMessage(getBlockHeadersMsg)
      NetworkMetrics.registerAddHandshakedPeer(peer)
      context.become(handleMessages(peersWithInfo + (peer.id -> PeerWithInfo(peer, peerInfo))))

    case PeerDisconnected(peerId) if peersWithInfo.contains(peerId) =>
      peerEventBusActor ! Unsubscribe(PeerDisconnectedClassifier(PeerSelector.WithId(peerId)))
      peerEventBusActor ! Unsubscribe(MessageClassifier(msgCodesWithInfo, PeerSelector.WithId(peerId)))
      NetworkMetrics.registerRemoveHandshakedPeer(peersWithInfo(peerId).peer)
      context.become(handleMessages(peersWithInfo - peerId))

  }

  /** Processes the message, updating the information for each peer
    *
    * @param peers
    *   with the information for each peer
    * @param peerId
    *   from whom the message was received (or who sent the message)
    * @param message
    *   to be processed
    * @param messageHandler
    *   for processing the message and obtaining the new peerInfo
    * @return
    *   new information for each peer
    */
  private def updatePeersWithInfo(
      peers: PeersWithInfo,
      peerId: PeerId,
      message: Message,
      messageHandler: (Message, PeerWithInfo) => PeerInfo
  ): PeersWithInfo =
    if (peers.contains(peerId)) {
      val peerWithInfo = peers(peerId)
      val newPeerInfo = messageHandler(message, peerWithInfo)
      peers + (peerId -> peerWithInfo.copy(peerInfo = newPeerInfo))
    } else
      peers

  /** Processes the message and the old peer info and returns the peer info
    *
    * @param message
    *   to be processed
    * @param initialPeerWithInfo
    *   from before the message was processed
    * @return
    *   new updated peer info
    */
  private def handleSentMessage(_message: Message, initialPeerWithInfo: PeerWithInfo): PeerInfo =
    initialPeerWithInfo.peerInfo

  /** Processes the message and the old peer info and returns the peer info
    *
    * @param message
    *   to be processed
    * @param initialPeerWithInfo
    *   from before the message was processed
    * @return
    *   new updated peer info
    */
  private def handleReceivedMessage(message: Message, initialPeerWithInfo: PeerWithInfo): PeerInfo =
    (updateChainWeight(message) _)
      .andThen(updateForkAccepted(message, initialPeerWithInfo.peer))
      .andThen(updateMaxBlock(message))(initialPeerWithInfo.peerInfo)

  /** Processes the message and updates the chain weight of the peer
    *
    * @param message
    *   to be processed
    * @param initialPeerInfo
    *   from before the message was processed
    * @return
    *   new peer info with the total difficulty updated
    */
  private def updateChainWeight(message: Message)(initialPeerInfo: PeerInfo): PeerInfo =
    message match {
      case newBlock: BaseETH6XMessages.NewBlock =>
        initialPeerInfo.copy(chainWeight = ChainWeight.totalDifficultyOnly(newBlock.totalDifficulty))
      case newBlock: ETC64.NewBlock => initialPeerInfo.copy(chainWeight = newBlock.chainWeight)
      case _                        => initialPeerInfo
    }

  /** Processes the message and updates if the fork block was accepted from the peer
    *
    * @param message
    *   to be processed
    * @param initialPeerInfo
    *   from before the message was processed
    * @return
    *   new peer info with the fork block accepted value updated
    */
  private def updateForkAccepted(message: Message, peer: Peer)(initialPeerInfo: PeerInfo): PeerInfo = message match {
    // Handle both ETH62 and ETH66+ BlockHeaders formats
    case ETH62BlockHeaders(blockHeaders) =>
      val newPeerInfoOpt: Option[PeerInfo] =
        for {
          forkResolver <- forkResolverOpt
          forkBlockHeader <- blockHeaders.find(_.number == forkResolver.forkBlockNumber)
        } yield {
          val newFork = forkResolver.recognizeFork(forkBlockHeader)
          log.debug("Received fork block header with fork: {}", newFork)

          if (!forkResolver.isAccepted(newFork)) {
            log.debug("Peer is not running the accepted fork, disconnecting")
            peer.ref ! DisconnectPeer(Disconnect.Reasons.UselessPeer)
            initialPeerInfo
          } else
            initialPeerInfo.withForkAccepted(true)
        }
      newPeerInfoOpt.getOrElse(initialPeerInfo)

    case ETH66BlockHeaders(_, blockHeaders) =>
      val newPeerInfoOpt: Option[PeerInfo] =
        for {
          forkResolver <- forkResolverOpt
          forkBlockHeader <- blockHeaders.find(_.number == forkResolver.forkBlockNumber)
        } yield {
          val newFork = forkResolver.recognizeFork(forkBlockHeader)
          log.debug("Received fork block header with fork: {}", newFork)

          if (!forkResolver.isAccepted(newFork)) {
            log.debug("Peer is not running the accepted fork, disconnecting")
            peer.ref ! DisconnectPeer(Disconnect.Reasons.UselessPeer)
            initialPeerInfo
          } else
            initialPeerInfo.withForkAccepted(true)
        }
      newPeerInfoOpt.getOrElse(initialPeerInfo)

    case _ => initialPeerInfo
  }

  /** Processes the message and updates the max block number from the peer
    *
    * @param message
    *   to be processed
    * @param initialPeerInfo
    *   from before the message was processed
    * @return
    *   new peer info with the max block number updated
    */
  private def updateMaxBlock(message: Message)(initialPeerInfo: PeerInfo): PeerInfo = {
    def update(ns: Seq[(BigInt, ByteString)]): PeerInfo =
      if (ns.isEmpty) {
        initialPeerInfo
      } else {
        val (maxBlockNumber, maxBlockHash) = ns.maxBy(_._1)
        if (maxBlockNumber > appStateStorage.getEstimatedHighestBlock())
          appStateStorage.putEstimatedHighestBlock(maxBlockNumber).commit()

        if (maxBlockNumber > initialPeerInfo.maxBlockNumber) {
          initialPeerInfo.withBestBlockData(maxBlockNumber, maxBlockHash)
        } else
          initialPeerInfo
      }

    message match {
      case m: ETH62BlockHeaders =>
        update(m.headers.map(header => (header.number, header.hash)))
      case m: ETH66BlockHeaders =>
        update(m.headers.map(header => (header.number, header.hash)))
      case m: BaseETH6XMessages.NewBlock =>
        update(Seq((m.block.header.number, m.block.header.hash)))
      case m: NewBlock =>
        update(Seq((m.block.header.number, m.block.header.hash)))
      case m: NewBlockHashes =>
        update(m.hashes.map(h => (h.number, h.hash)))
      case _ => initialPeerInfo
    }
  }

}

object EtcPeerManagerActor {

  val msgCodesWithInfo: Set[Int] = Set(Codes.BlockHeadersCode, Codes.NewBlockCode, Codes.NewBlockHashesCode)

  /** RemoteStatus was created to decouple status information from protocol status messages (they are different versions
    * of Status msg)
    */
  case class RemoteStatus(
      capability: Capability,
      networkId: Int,
      chainWeight: ChainWeight,
      bestHash: ByteString,
      genesisHash: ByteString
  ) {
    override def toString: String =
      s"RemoteStatus { " +
        s"capability: $capability, " +
        s"networkId: $networkId, " +
        s"chainWeight: $chainWeight, " +
        s"bestHash: ${ByteStringUtils.hash2string(bestHash)}, " +
        s"genesisHash: ${ByteStringUtils.hash2string(genesisHash)}," +
        s"}"
  }

  object RemoteStatus {
    def apply(status: ETH64.Status, negotiatedCapability: Capability): RemoteStatus =
      RemoteStatus(
        negotiatedCapability,
        status.networkId,
        ChainWeight.totalDifficultyOnly(status.totalDifficulty),
        status.bestHash,
        status.genesisHash
      )

    def apply(status: ETH64.Status): RemoteStatus =
      RemoteStatus(
        Capability.ETH64,
        status.networkId,
        ChainWeight.totalDifficultyOnly(status.totalDifficulty),
        status.bestHash,
        status.genesisHash
      )

    def apply(status: ETC64.Status): RemoteStatus =
      RemoteStatus(
        Capability.ETC64,
        status.networkId,
        status.chainWeight,
        status.bestHash,
        status.genesisHash
      )

    def apply(status: BaseETH6XMessages.Status): RemoteStatus =
      RemoteStatus(
        Capability.ETH63,
        status.networkId,
        ChainWeight.totalDifficultyOnly(status.totalDifficulty),
        status.bestHash,
        status.genesisHash
      )
  }

  case class PeerInfo(
      remoteStatus: RemoteStatus, // Updated only after handshaking
      chainWeight: ChainWeight,
      forkAccepted: Boolean,
      maxBlockNumber: BigInt,
      bestBlockHash: ByteString
  ) extends HandshakeResult {

    def withForkAccepted(forkAccepted: Boolean): PeerInfo = copy(forkAccepted = forkAccepted)

    def withBestBlockData(maxBlockNumber: BigInt, bestBlockHash: ByteString): PeerInfo =
      copy(maxBlockNumber = maxBlockNumber, bestBlockHash = bestBlockHash)

    def withChainWeight(weight: ChainWeight): PeerInfo =
      copy(chainWeight = weight)

    override def toString: String =
      s"PeerInfo {" +
        s" chainWeight: $chainWeight," +
        s" forkAccepted: $forkAccepted," +
        s" maxBlockNumber: $maxBlockNumber," +
        s" bestBlockHash: ${ByteStringUtils.hash2string(bestBlockHash)}," +
        s" handshakeStatus: $remoteStatus" +
        s" }"
  }

  object PeerInfo {
    def apply(remoteStatus: RemoteStatus, forkAccepted: Boolean): PeerInfo =
      PeerInfo(
        remoteStatus,
        remoteStatus.chainWeight,
        forkAccepted,
        0,
        remoteStatus.bestHash
      )

    def withForkAccepted(remoteStatus: RemoteStatus): PeerInfo =
      PeerInfo(remoteStatus, forkAccepted = true)

    def withNotForkAccepted(remoteStatus: RemoteStatus): PeerInfo =
      PeerInfo(remoteStatus, forkAccepted = false)
  }

  private[network] case class PeerWithInfo(peer: Peer, peerInfo: PeerInfo)

  case object GetHandshakedPeers

  case class HandshakedPeers(peers: Map[Peer, PeerInfo])

  case class PeerInfoRequest(peerId: PeerId)

  case class PeerInfoResponse(peerInfo: Option[PeerInfo])

  case class SendMessage(message: MessageSerializable, peerId: PeerId)

  def props(
      peerManagerActor: ActorRef,
      peerEventBusActor: ActorRef,
      appStateStorage: AppStateStorage,
      forkResolverOpt: Option[ForkResolver]
  ): Props =
    Props(new EtcPeerManagerActor(peerManagerActor, peerEventBusActor, appStateStorage, forkResolverOpt))

}
