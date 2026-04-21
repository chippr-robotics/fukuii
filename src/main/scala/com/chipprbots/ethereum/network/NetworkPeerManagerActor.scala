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
import com.chipprbots.ethereum.network.p2p.messages.ETH62.NewBlockHashes
import com.chipprbots.ethereum.network.p2p.messages.ETH66
import com.chipprbots.ethereum.network.p2p.messages.ETH66.{BlockHeaders => ETH66BlockHeaders}
import com.chipprbots.ethereum.network.p2p.messages.ETH64
import com.chipprbots.ethereum.network.p2p.messages.SNAP
import com.chipprbots.ethereum.network.p2p.messages.SNAP._
import com.chipprbots.ethereum.network.p2p.messages.WireProtocol.Disconnect
import com.chipprbots.ethereum.utils.ByteStringUtils
import com.chipprbots.ethereum.utils.ByteStringUtils.ByteStringOps

/** NetworkPeerManager actor keeps peer state up to date and exposes that information to other components. It subscribes
  * to peer lifecycle events (handshake, disconnection, messages) and routes protocol traffic to the appropriate
  * handlers.
  */
class NetworkPeerManagerActor(
    peerManagerActor: ActorRef,
    peerEventBusActor: ActorRef,
    appStateStorage: AppStateStorage,
    forkResolverOpt: Option[ForkResolver],
    initialSnapSyncControllerOpt: Option[ActorRef] = None,
    evmCodeStorage: Option[com.chipprbots.ethereum.db.storage.EvmCodeStorage] = None,
    mptStorageOpt: Option[com.chipprbots.ethereum.db.storage.MptStorage] = None,
    blockchainReader: Option[com.chipprbots.ethereum.domain.BlockchainReader] = None
) extends Actor
    with ActorLogging {

  private[network] type PeersWithInfo = Map[PeerId, PeerWithInfo]

  // Maximum length for hex string in debug logs (to avoid very long log lines)

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

      // Do NOT send unsolicited GetBlockHeaders after handshake.
      // The sync engine (BlockFetcher/HeadersFetcher) will request headers when needed through
      // the normal PeersClient polling mechanism. Sending GetBlockHeaders immediately violates
      // the expected message flow in devp2p protocol tests and is not standard behavior
      // (geth does not send unsolicited GetBlockHeaders after Status exchange).
      // For ETH69+, latestBlock/latestBlockHash are already in the Status message.
      // For ETH64+, ForkId in Status provides fork validation without needing block headers.
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
  private def handleSentMessage(@annotation.unused _message: Message, initialPeerWithInfo: PeerWithInfo): PeerInfo =
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
    log.debug(
      s"Received GetAccountRange request from peer $peerId: requestId=${msg.requestId}, root=${msg.rootHash.take(4).toHex}, start=${msg.startingHash.take(4).toHex}, limit=${msg.limitHash.take(4).toHex}, bytes=${msg.responseBytes}"
    )

    peerWithInfo.foreach { pwi =>
      val response = mptStorageOpt match {
        case Some(storage) if isStateRootFresh(msg.rootHash) =>
          com.chipprbots.ethereum.network.snapserver.SnapServer.serveAccountRange(
            requestId = msg.requestId,
            rootHash = msg.rootHash,
            startingHash = msg.startingHash,
            limitHash = msg.limitHash,
            responseBytes = msg.responseBytes,
            storage = storage
          )
        case _ =>
          // Per SNAP/1: respond with empty when state root isn't within the recent
          // window we can serve. Hive's "Test 11" sends genesis stateRoot (older than
          // 127 blocks) and expects an empty response.
          AccountRange(msg.requestId, Seq.empty, Seq.empty)
      }
      pwi.peer.ref ! PeerActor.SendMessage(response)
    }
  }

  // Cache of recent canonical state roots, refreshed lazily when the chain advances. The
  // 128-block window matches go-ethereum's snap-serve policy and avoids 128 RocksDB reads
  // per inbound SNAP request — critical when the hive snap simulator fires dozens of
  // back-to-back GetAccountRange calls at us.
  private var freshRootCache: scala.collection.mutable.Set[ByteString] = scala.collection.mutable.Set.empty
  private var freshRootCacheTip: BigInt = BigInt(-1)

  private def refreshFreshRootCache(reader: com.chipprbots.ethereum.domain.BlockchainReader): Unit = {
    val tip = reader.getBestBlockNumber()
    if (tip != freshRootCacheTip) {
      val window = BigInt(128)
      val from = (tip - window).max(BigInt(0))
      val newCache = scala.collection.mutable.Set.empty[ByteString]
      var n = tip
      while (n >= from) {
        reader.getBlockHeaderByNumber(n).foreach(h => newCache += h.stateRoot)
        n = n - 1
      }
      freshRootCache = newCache
      freshRootCacheTip = tip
    }
  }

  /** Per SNAP/1: nodes only need to serve state for "recent" roots — geth uses a 128-block
    * window. Looks up the requested rootHash in a cached set of recent canonical state
    * roots. Returns true (serve) if blockchainReader isn't injected.
    */
  private def isStateRootFresh(rootHash: ByteString): Boolean = blockchainReader match {
    case None => true
    case Some(reader) =>
      refreshFreshRootCache(reader)
      freshRootCache.contains(rootHash)
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

    peerWithInfo.foreach { pwi =>
      val response = mptStorageOpt match {
        case Some(storage) =>
          // Per-account storage roots come from looking up each account in the state trie.
          // Here we resolve via blockchainReader's ability to fetch the account at the
          // canonical state root — but the SNAP request specifies an arbitrary state root.
          // Approximation: walk the account trie at msg.rootHash to find each account's
          // storageRoot. Since this is only on the server-serve path, simple fallback:
          // look up each account via SnapServer using msg.rootHash.
          import com.chipprbots.ethereum.network.snapserver.SnapServer
          import com.chipprbots.ethereum.mpt.{MerklePatriciaTrie, MptTraversals, NullNode}
          val accountRoot: ByteString => Option[ByteString] = { accountHash =>
            // walk the state trie at msg.rootHash to find this account, return storageRoot
            val nibbles = SnapServer.hashToNibbles(accountHash)
            // crude lookup: traverse from root following nibbles, return the leaf's account.storageRoot
            // We can use the existing MerklePatriciaTrie API for lookup.
            try {
              val stateRoot =
                if (msg.rootHash.toArray.sameElements(MerklePatriciaTrie.EmptyRootHash)) None
                else Some(msg.rootHash.toArray)
              stateRoot.flatMap { sr =>
                val rootNode = storage.get(sr)
                if (rootNode == NullNode) None
                else {
                  // find leaf matching nibbles
                  def find(node: com.chipprbots.ethereum.mpt.MptNode, rem: Array[Byte]): Option[ByteString] = {
                    val resolved = node match {
                      case h: com.chipprbots.ethereum.mpt.HashNode => storage.get(h.hashNode)
                      case other                                   => other
                    }
                    resolved match {
                      case com.chipprbots.ethereum.mpt.LeafNode(key, value, _, _, _) =>
                        if (rem.sameElements(key.toArray)) {
                          import com.chipprbots.ethereum.network.p2p.messages.ETH63.AccountImplicits._
                          val acct = value.toArray.toAccount
                          Some(acct.storageRoot)
                        } else None
                      case com.chipprbots.ethereum.mpt.ExtensionNode(sk, next, _, _, _) =>
                        if (rem.length >= sk.length && rem.take(sk.length).sameElements(sk.toArray))
                          find(next, rem.drop(sk.length))
                        else None
                      case com.chipprbots.ethereum.mpt.BranchNode(children, _, _, _, _) =>
                        if (rem.isEmpty) None
                        else {
                          val ch = children(rem(0) & 0x0f)
                          if (ch == NullNode) None else find(ch, rem.drop(1))
                        }
                      case _ => None
                    }
                  }
                  find(rootNode, nibbles)
                }
              }
            } catch { case _: Throwable => None }
          }
          SnapServer.serveStorageRanges(
            requestId = msg.requestId,
            rootHash = msg.rootHash,
            accountHashes = msg.accountHashes,
            startingHash = msg.startingHash,
            limitHash = msg.limitHash,
            responseBytes = msg.responseBytes,
            storage = storage,
            accountRoot = accountRoot
          )
        case None =>
          StorageRanges(msg.requestId, Seq.empty, Seq.empty)
      }
      pwi.peer.ref ! PeerActor.SendMessage(response)
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

    peerWithInfo.foreach { pwi =>
      val response = mptStorageOpt match {
        case Some(storage) =>
          com.chipprbots.ethereum.network.snapserver.SnapServer.serveTrieNodes(
            requestId = msg.requestId,
            rootHash = msg.rootHash,
            paths = msg.paths,
            responseBytes = msg.responseBytes,
            storage = storage
          )
        case None =>
          TrieNodes(msg.requestId, Seq.empty)
      }
      pwi.peer.ref ! PeerActor.SendMessage(response)
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

    peerWithInfo.foreach { pwi =>
      // Look up each requested code hash in EvmCodeStorage. Stop accumulating when we
      // would exceed the peer's responseBytes soft limit (per SNAP/1 spec, the server
      // is allowed to truncate the response prefix early — proofs aren't needed for
      // bytecodes since each code is keyed by its keccak256 hash).
      val maxBytes = msg.responseBytes.toInt.max(0)
      val codes: Seq[ByteString] = evmCodeStorage match {
        case Some(storage) =>
          val collected = scala.collection.mutable.ListBuffer.empty[ByteString]
          var totalBytes = 0
          val it = msg.hashes.iterator
          while (it.hasNext && (totalBytes < maxBytes || collected.isEmpty)) {
            val codeHash = it.next()
            storage.get(codeHash).foreach { code =>
              collected += code
              totalBytes += code.size
            }
          }
          collected.toList
        case None => Seq.empty
      }
      val response = ByteCodes(requestId = msg.requestId, codes = codes)
      pwi.peer.ref ! PeerActor.SendMessage(response)
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
    SNAP.Codes.ByteCodesCode,
    // SNAP protocol request codes — incoming requests we serve as a SNAP server.
    // Hive's devp2p snap suite (and any peer that decides to fetch state from us)
    // sends these. Subscribing here is required for handleGet*Range/Codes/TrieNodes
    // to fire.
    SNAP.Codes.GetAccountRangeCode,
    SNAP.Codes.GetStorageRangesCode,
    SNAP.Codes.GetTrieNodesCode,
    SNAP.Codes.GetByteCodesCode
  )

  /** RemoteStatus was created to decouple status information from protocol status messages (they are different versions
    * of Status msg)
    */
  case class RemoteStatus(
      capability: Capability,
      networkId: Long,
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

    /** ETH/69: no totalDifficulty — use latestBlock number as a proxy for chain weight */
    def fromETH69Status(
        status: com.chipprbots.ethereum.network.p2p.messages.ETH69.Status,
        negotiatedCapability: Capability,
        supportsSnap: Boolean,
        capabilities: List[Capability]
    ): RemoteStatus =
      RemoteStatus(
        negotiatedCapability,
        status.networkId,
        ChainWeight.totalDifficultyOnly(status.latestBlock), // Use block number as weight proxy
        status.latestBlockHash,
        status.genesisHash,
        supportsSnap,
        capabilities
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
    def apply(remoteStatus: RemoteStatus, forkAccepted: Boolean): PeerInfo = {
      // For ETH/69, chainWeight.totalDifficulty holds the block number (latestBlock from Status).
      // For ETH/64-68, it holds the actual TD which shouldn't be used as block number.
      // Initialize maxBlockNumber from the Status message to avoid the circular dependency where
      // peerHasUpdatedBestBlock filters out new peers before they can exchange any block data.
      val initialMaxBlock: BigInt =
        if (remoteStatus.capability == com.chipprbots.ethereum.network.p2p.messages.Capability.ETH69)
          remoteStatus.chainWeight.totalDifficulty // ETH/69: latestBlock number stored as TD
        else BigInt(0) // ETH/64-68: don't confuse TD with block number
      PeerInfo(
        remoteStatus,
        remoteStatus.chainWeight,
        forkAccepted,
        initialMaxBlock,
        remoteStatus.bestHash
      )
    }

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
      snapSyncControllerOpt: Option[ActorRef] = None,
      evmCodeStorage: Option[com.chipprbots.ethereum.db.storage.EvmCodeStorage] = None,
      mptStorageOpt: Option[com.chipprbots.ethereum.db.storage.MptStorage] = None,
      blockchainReader: Option[com.chipprbots.ethereum.domain.BlockchainReader] = None
  ): Props =
    Props(
      new NetworkPeerManagerActor(
        peerManagerActor,
        peerEventBusActor,
        appStateStorage,
        forkResolverOpt,
        snapSyncControllerOpt,
        evmCodeStorage,
        mptStorageOpt,
        blockchainReader
      )
    )
}
