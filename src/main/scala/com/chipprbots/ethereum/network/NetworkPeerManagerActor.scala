package com.chipprbots.ethereum.network

import org.apache.pekko.actor.Actor
import org.apache.pekko.actor.ActorLogging
import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.Props
import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.network.NetworkPeerManagerActor._
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
import com.chipprbots.ethereum.network.p2p.messages.ETH62.{BlockHeaders => ETH62BlockHeaders}
import com.chipprbots.ethereum.network.p2p.messages.ETH62.{GetBlockHeaders => ETH62GetBlockHeaders}
import com.chipprbots.ethereum.network.p2p.messages.ETH62.NewBlockHashes
import com.chipprbots.ethereum.network.p2p.messages.ETH66
import com.chipprbots.ethereum.network.p2p.messages.ETH66.{BlockHeaders => ETH66BlockHeaders}
import com.chipprbots.ethereum.network.p2p.messages.ETH66.{GetBlockHeaders => ETH66GetBlockHeaders}
import com.chipprbots.ethereum.network.p2p.messages.ETH64
import com.chipprbots.ethereum.network.p2p.messages.SNAP
import com.chipprbots.ethereum.network.p2p.messages.SNAP._
import com.chipprbots.ethereum.network.p2p.messages.WireProtocol.Disconnect
import com.chipprbots.ethereum.utils.ByteStringUtils
import com.chipprbots.ethereum.utils.ByteStringUtils.ByteStringOps

import org.bouncycastle.util.encoders.Hex

/** NetworkPeerManager actor keeps peer state up to date and exposes that information to other components. It subscribes
  * to peer lifecycle events (handshake, disconnection, messages) and routes protocol traffic to the appropriate
  * handlers.
  */
class NetworkPeerManagerActor(
    peerManagerActor: ActorRef,
    peerEventBusActor: ActorRef,
    appStateStorage: AppStateStorage,
    forkResolverOpt: Option[ForkResolver],
    initialSnapSyncControllerOpt: Option[ActorRef] = None
) extends Actor
    with ActorLogging {

  private[network] type PeersWithInfo = Map[PeerId, PeerWithInfo]

  // Maximum length for hex string in debug logs (to avoid very long log lines)
  private val MaxHexLogLength = 200

  // Mutable reference to SNAPSyncController that can be set after initialization
  private var snapSyncControllerOpt: Option[ActorRef] = initialSnapSyncControllerOpt

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

    case RegisterSnapSyncController(snapSyncController) =>
      log.info("Registering SNAPSyncController for message routing")
      snapSyncControllerOpt = Some(snapSyncController)

    case NetworkPeerManagerActor.SendMessage(message, peerId) =>
      NetworkMetrics.SentMessagesCounter.increment()
      log.debug(
        "SEND_VIA_MANAGER: peer={}, type={}, code=0x{}",
        peerId,
        message.underlyingMsg.getClass.getSimpleName,
        message.code.toHexString
      )
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
      // Route SNAP protocol messages to SNAPSyncController
      message match {
        case msg @ (_: AccountRange | _: StorageRanges | _: TrieNodes | _: ByteCodes) =>
          log.debug("Routing {} message to SNAPSyncController from peer {}", msg.getClass.getSimpleName, peerId)
          snapSyncControllerOpt.foreach(_ ! msg)

        // Handle incoming SNAP request messages (server-side)
        case msg: GetAccountRange =>
          handleGetAccountRange(msg, peerId, peersWithInfo.get(peerId))

        case msg: GetStorageRanges =>
          handleGetStorageRanges(msg, peerId, peersWithInfo.get(peerId))

        case msg: GetTrieNodes =>
          handleGetTrieNodes(msg, peerId, peersWithInfo.get(peerId))

        case msg: GetByteCodes =>
          handleGetByteCodes(msg, peerId, peersWithInfo.get(peerId))

        case _ => // ETH protocol messages - no special routing needed
      }

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

      // Many peers disconnect with reason 0x10 (Other) when asked for headers at genesis
      // as they implement peer selection policies that reject genesis-only nodes.
      // When peer is at genesis, we skip this initial GetBlockHeaders to avoid disconnect.
      // Block synchronization will be initiated by the sync controller (SyncController/SNAPSyncController)
      // once it determines which peers to use for sync, avoiding unnecessary blacklisting.
      if (peerInfo.isAtGenesis) {
        log.info(
          "PEER_HANDSHAKE_SUCCESS: Peer {} is at genesis block - skipping GetBlockHeaders to avoid disconnect. " +
            "Peer will be available for sync controller.",
          peer.id
        )
      } else {
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
      }
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
  private def handleReceivedMessage(message: Message, initialPeerWithInfo: PeerWithInfo): PeerInfo = {
    // Log received BlockHeaders for debugging GetBlockHeaders response tracking
    message match {
      case m: ETH62BlockHeaders =>
        log.info(
          "RECV_BLOCKHEADERS: peer={}, count={}, blockNumbers={}",
          initialPeerWithInfo.peer.id,
          m.headers.size,
          m.headers.take(5).map(_.number).mkString(", ") + (if (m.headers.size > 5) "..." else "")
        )
      case m: ETH66BlockHeaders =>
        log.info(
          "RECV_BLOCKHEADERS: peer={}, requestId={}, count={}, blockNumbers={}",
          initialPeerWithInfo.peer.id,
          m.requestId,
          m.headers.size,
          m.headers.take(5).map(_.number).mkString(", ") + (if (m.headers.size > 5) "..." else "")
        )
      case _ => // Don't log other message types at INFO level
    }

    (updateChainWeight(message) _)
      .andThen(updateForkAccepted(message, initialPeerWithInfo.peer))
      .andThen(updateMaxBlock(message))(initialPeerWithInfo.peerInfo)
  }

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
      case _ => initialPeerInfo
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
      case m: NewBlockHashes =>
        update(m.hashes.map(h => (h.number, h.hash)))
      case _ => initialPeerInfo
    }
  }

  /** Handle incoming GetAccountRange request from a peer (server-side)
    *
    * @param msg
    *   The GetAccountRange request
    * @param peerId
    *   The peer that sent the request
    * @param peerWithInfo
    *   Optional peer information
    */
  private def handleGetAccountRange(
      msg: GetAccountRange,
      peerId: PeerId,
      peerWithInfo: Option[PeerWithInfo]
  ): Unit = {
    // Note: This is an optional server-side implementation
    // Fukuii primarily acts as a client, so we log and ignore for now
    log.debug(
      s"Received GetAccountRange request from peer $peerId: requestId=${msg.requestId}, root=${msg.rootHash.take(4).toHex}, start=${msg.startingHash.take(4).toHex}, limit=${msg.limitHash.take(4).toHex}, bytes=${msg.responseBytes}"
    )

    // TODO: Implement server-side account range retrieval
    // 1. Verify we have the requested state root
    // 2. Retrieve accounts from startingHash to limitHash (up to responseBytes)
    // 3. Generate Merkle proofs for the range
    // 4. Send AccountRange response

    // For now, send an empty response to indicate we don't serve SNAP data
    peerWithInfo.foreach { pwi =>
      val emptyResponse = AccountRange(
        requestId = msg.requestId,
        accounts = Seq.empty,
        proof = Seq.empty
      )
      pwi.peer.ref ! PeerActor.SendMessage(emptyResponse)
    }
  }

  /** Handle incoming GetStorageRanges request from a peer (server-side)
    *
    * @param msg
    *   The GetStorageRanges request
    * @param peerId
    *   The peer that sent the request
    * @param peerWithInfo
    *   Optional peer information
    */
  private def handleGetStorageRanges(
      msg: GetStorageRanges,
      peerId: PeerId,
      peerWithInfo: Option[PeerWithInfo]
  ): Unit = {
    log.debug(
      s"Received GetStorageRanges request from peer $peerId: requestId=${msg.requestId}, root=${msg.rootHash.take(4).toHex}, accounts=${msg.accountHashes.size}, start=${msg.startingHash.take(4).toHex}, limit=${msg.limitHash.take(4).toHex}, bytes=${msg.responseBytes}"
    )

    // TODO: Implement server-side storage range retrieval
    // 1. Verify we have the requested state root
    // 2. For each account, retrieve storage slots from startingHash to limitHash
    // 3. Generate Merkle proofs for each account's storage
    // 4. Send StorageRanges response

    // For now, send an empty response
    peerWithInfo.foreach { pwi =>
      val emptyResponse = StorageRanges(
        requestId = msg.requestId,
        slots = Seq.empty,
        proof = Seq.empty
      )
      pwi.peer.ref ! PeerActor.SendMessage(emptyResponse)
    }
  }

  /** Handle incoming GetTrieNodes request from a peer (server-side)
    *
    * @param msg
    *   The GetTrieNodes request
    * @param peerId
    *   The peer that sent the request
    * @param peerWithInfo
    *   Optional peer information
    */
  private def handleGetTrieNodes(
      msg: GetTrieNodes,
      peerId: PeerId,
      peerWithInfo: Option[PeerWithInfo]
  ): Unit = {
    log.debug(
      s"Received GetTrieNodes request from peer $peerId: requestId=${msg.requestId}, root=${msg.rootHash.take(4).toHex}, paths=${msg.paths.size}, bytes=${msg.responseBytes}"
    )

    // TODO: Implement server-side trie node retrieval
    // 1. Verify we have the requested state root
    // 2. For each path, retrieve the trie node
    // 3. Send TrieNodes response

    // For now, send an empty response
    peerWithInfo.foreach { pwi =>
      val emptyResponse = TrieNodes(
        requestId = msg.requestId,
        nodes = Seq.empty
      )
      pwi.peer.ref ! PeerActor.SendMessage(emptyResponse)
    }
  }

  /** Handle incoming GetByteCodes request from a peer (server-side)
    *
    * @param msg
    *   The GetByteCodes request
    * @param peerId
    *   The peer that sent the request
    * @param peerWithInfo
    *   Optional peer information
    */
  private def handleGetByteCodes(
      msg: GetByteCodes,
      peerId: PeerId,
      peerWithInfo: Option[PeerWithInfo]
  ): Unit = {
    log.debug(
      s"Received GetByteCodes request from peer $peerId: requestId=${msg.requestId}, hashes=${msg.hashes.size}, bytes=${msg.responseBytes}"
    )

    // TODO: Implement server-side bytecode retrieval
    // 1. For each code hash, retrieve the bytecode from EvmCodeStorage
    // 2. Send ByteCodes response (up to responseBytes limit)

    // For now, send an empty response
    peerWithInfo.foreach { pwi =>
      val emptyResponse = ByteCodes(
        requestId = msg.requestId,
        codes = Seq.empty
      )
      pwi.peer.ref ! PeerActor.SendMessage(emptyResponse)
    }
  }

}

object NetworkPeerManagerActor {

  val msgCodesWithInfo: Set[Int] = Set(
    Codes.BlockHeadersCode,
    Codes.NewBlockCode,
    Codes.NewBlockHashesCode,
    // SNAP protocol response codes (responses we receive from peers)
    SNAP.Codes.AccountRangeCode,
    SNAP.Codes.StorageRangesCode,
    SNAP.Codes.TrieNodesCode,
    SNAP.Codes.ByteCodesCode
  )

  /** RemoteStatus was created to decouple status information from protocol status messages (they are different versions
    * of Status msg)
    */
  case class RemoteStatus(
      capability: Capability,
      networkId: Int,
      chainWeight: ChainWeight,
      bestHash: ByteString,
      genesisHash: ByteString,
      supportsSnap: Boolean = false,
      capabilities: List[Capability] = List.empty
  ) {
    override def toString: String =
      s"RemoteStatus { " +
        s"capability: $capability, " +
        s"networkId: $networkId, " +
        s"chainWeight: $chainWeight, " +
        s"bestHash: ${ByteStringUtils.hash2string(bestHash)}, " +
        s"genesisHash: ${ByteStringUtils.hash2string(genesisHash)}, " +
        s"supportsSnap: $supportsSnap, " +
        s"capabilities: ${capabilities.mkString("[", ", ", "]")}" +
        s"}"
  }

  object RemoteStatus {
    def apply(
        status: ETH64.Status,
        negotiatedCapability: Capability,
        supportsSnap: Boolean,
        capabilities: List[Capability]
    ): RemoteStatus =
      RemoteStatus(
        negotiatedCapability,
        status.networkId,
        ChainWeight.totalDifficultyOnly(status.totalDifficulty),
        status.bestHash,
        status.genesisHash,
        supportsSnap,
        capabilities
      )

    def apply(status: ETH64.Status): RemoteStatus =
      RemoteStatus(
        Capability.ETH64,
        status.networkId,
        ChainWeight.totalDifficultyOnly(status.totalDifficulty),
        status.bestHash,
        status.genesisHash,
        false, // supportsSnap defaults to false
        List.empty
      )

    def apply(
        status: BaseETH6XMessages.Status,
        negotiatedCapability: Capability,
        supportsSnap: Boolean,
        capabilities: List[Capability]
    ): RemoteStatus =
      RemoteStatus(
        negotiatedCapability,
        status.networkId,
        ChainWeight.totalDifficultyOnly(status.totalDifficulty),
        status.bestHash,
        status.genesisHash,
        supportsSnap,
        capabilities
      )

    def apply(status: BaseETH6XMessages.Status): RemoteStatus =
      RemoteStatus(
        Capability.ETH63,
        status.networkId,
        ChainWeight.totalDifficultyOnly(status.totalDifficulty),
        status.bestHash,
        status.genesisHash,
        false, // supportsSnap defaults to false
        List.empty
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

    /** Checks if this peer is at genesis block (bestHash == genesisHash). Peers at genesis often disconnect when asked
      * for headers as they implement peer selection policies that reject genesis-only nodes.
      */
    def isAtGenesis: Boolean = remoteStatus.bestHash == remoteStatus.genesisHash

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

  /** Register the SNAPSyncController actor for message routing */
  case class RegisterSnapSyncController(snapSyncController: ActorRef)

  def props(
      peerManagerActor: ActorRef,
      peerEventBusActor: ActorRef,
      appStateStorage: AppStateStorage,
      forkResolverOpt: Option[ForkResolver],
      snapSyncControllerOpt: Option[ActorRef] = None
  ): Props =
    Props(
      new NetworkPeerManagerActor(
        peerManagerActor,
        peerEventBusActor,
        appStateStorage,
        forkResolverOpt,
        snapSyncControllerOpt
      )
    )
}
