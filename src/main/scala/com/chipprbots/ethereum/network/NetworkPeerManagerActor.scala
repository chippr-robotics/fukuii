package com.chipprbots.ethereum.network

import org.apache.pekko.actor.Actor
import org.apache.pekko.actor.ActorLogging
import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.Props
import org.apache.pekko.util.ByteString

import scala.concurrent.duration._

import com.chipprbots.ethereum.blockchain.sync.snap.SnapServerChecker
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.FlatAccountStorage
import com.chipprbots.ethereum.db.storage.ArchiveNodeStorage
import com.chipprbots.ethereum.db.storage.FlatSlotStorage
import com.chipprbots.ethereum.db.storage.NodeStorage
import com.chipprbots.ethereum.db.storage.SerializingMptStorage
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.mpt._
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
    evmCodeStorage: Option[EvmCodeStorage] = None,
    nodeStorage: Option[NodeStorage] = None,
    flatAccountStorage: Option[FlatAccountStorage] = None,
    flatSlotStorage: Option[FlatSlotStorage] = None,
    initialSnapSyncControllerOpt: Option[ActorRef] = None
) extends Actor
    with ActorLogging {

  private[network] type PeersWithInfo = Map[PeerId, PeerWithInfo]

  // Maximum length for hex string in debug logs (to avoid very long log lines)
  private val MaxHexLogLength = 200

  // Mutable reference to SNAPSyncController that can be set after initialization
  private var snapSyncControllerOpt: Option[ActorRef] = initialSnapSyncControllerOpt

  // LRU cache for upper trie nodes in GetTrieNodes serving.
  // Top 4-5 levels of the account trie (~340 nodes) are identical across requests
  // until the state root changes. Cache is invalidated when a new root is seen.
  private val TrieNodeCacheCapacity = 512
  private var trieNodeCacheRoot: ByteString = ByteString.empty
  private val trieNodeCache: java.util.LinkedHashMap[ByteString, Array[Byte]] =
    new java.util.LinkedHashMap[ByteString, Array[Byte]](TrieNodeCacheCapacity, 0.75f, true) {
      override def removeEldestEntry(eldest: java.util.Map.Entry[ByteString, Array[Byte]]): Boolean =
        size() > TrieNodeCacheCapacity
    }

  /** Get a trie node by hash, checking the LRU cache first.
    * If the root hash has changed since last request, the cache is cleared.
    */
  private def cachedNodeGet(rootHash: ByteString, nodeHash: ByteString, storage: NodeStorage): Option[Array[Byte]] = {
    if (rootHash != trieNodeCacheRoot) {
      trieNodeCache.clear()
      trieNodeCacheRoot = rootHash
    }
    val cached = trieNodeCache.get(nodeHash)
    if (cached != null) {
      Some(cached)
    } else {
      storage.get(nodeHash) match {
        case some @ Some(data) =>
          trieNodeCache.put(nodeHash, data)
          some
        case None => None
      }
    }
  }

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
      // Check if this is a SNAP probe response FIRST — handle and return without falling through
      val handledAsProbe = message match {
        case msg: AccountRange if SnapServerChecker.isProbeRequestId(msg.requestId) =>
          SnapServerChecker.completeProbe(msg.requestId).foreach { probedPeerId =>
            val serving = SnapServerChecker.isServingSnap(msg)
            log.info(
              "SNAP_PROBE_RESULT: peer={} isServingSnap={} (accounts={}, proofNodes={})",
              probedPeerId,
              serving,
              msg.accounts.size,
              msg.proof.size
            )
            peersWithInfo.get(probedPeerId).foreach { pwi =>
              val updatedInfo = pwi.peerInfo.withServingSnap(serving)
              context.become(handleMessages(peersWithInfo + (probedPeerId -> pwi.copy(peerInfo = updatedInfo))))
            }
          }
          true
        case _ => false
      }

      if (!handledAsProbe) {
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
      }

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
      if (peerInfo.isAtGenesis && !peerInfo.remoteStatus.supportsSnap) {
        log.info(
          "PEER_HANDSHAKE_SUCCESS: Peer {} is at genesis block (non-snap) - skipping GetBlockHeaders to avoid disconnect. " +
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
      // SNAP probe is deferred until we receive BlockHeaders response — we need the
      // header's stateRoot (not bestHash which is a block hash) for GetAccountRange.
      // See handleReceivedMessage where the probe is sent after extracting stateRoot.

      val peerWithCapability = peer.copy(negotiatedCapability = Some(peerInfo.remoteStatus.capability))
      NetworkMetrics.registerAddHandshakedPeer(peerWithCapability)
      context.become(handleMessages(peersWithInfo + (peer.id -> PeerWithInfo(peerWithCapability, peerInfo))))

    case PeerDisconnected(peerId) if peersWithInfo.contains(peerId) =>
      peerEventBusActor ! Unsubscribe(PeerDisconnectedClassifier(PeerSelector.WithId(peerId)))
      peerEventBusActor ! Unsubscribe(MessageClassifier(msgCodesWithInfo, PeerSelector.WithId(peerId)))
      NetworkMetrics.registerRemoveHandshakedPeer(peersWithInfo(peerId).peer)
      SnapServerChecker.clearProbedState(peerId) // allow re-probe on reconnect with same PeerId
      context.become(handleMessages(peersWithInfo - peerId))

    case SnapProbeTimeout(requestId) =>
      SnapServerChecker.cancelProbe(requestId).foreach { peerId =>
        log.info("SNAP_PROBE_TIMEOUT: peer={} did not respond to snap probe within {}ms", peerId, SnapServerChecker.ProbeTimeoutMillis)
        // Clear probed state so the peer can be re-probed on the next BlockHeaders message.
        // Without this, a single timeout permanently excludes the peer (hasBeenProbed stays true
        // but isServingSnap stays false — the probe condition can never pass again).
        SnapServerChecker.clearProbedState(peerId)
      }

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
    // Log received BlockHeaders and send deferred SNAP probe using actual stateRoot
    message match {
      case m: ETH62BlockHeaders =>
        log.debug(
          "RECV_BLOCKHEADERS: peer={}, count={}, blockNumbers={}",
          initialPeerWithInfo.peer.id,
          m.headers.size,
          m.headers.take(5).map(_.number).mkString(", ") + (if (m.headers.size > 5) "..." else "")
        )
        maybeSendSnapProbe(initialPeerWithInfo, m.headers.headOption.map(_.stateRoot))
      case m: ETH66BlockHeaders =>
        log.debug(
          "RECV_BLOCKHEADERS: peer={}, requestId={}, count={}, blockNumbers={}",
          initialPeerWithInfo.peer.id,
          m.requestId,
          m.headers.size,
          m.headers.take(5).map(_.number).mkString(", ") + (if (m.headers.size > 5) "..." else "")
        )
        maybeSendSnapProbe(initialPeerWithInfo, m.headers.headOption.map(_.stateRoot))
      case _ => // Don't log other message types at INFO level
    }

    (updateChainWeight(message) _)
      .andThen(updateForkAccepted(message, initialPeerWithInfo.peer))
      .andThen(updateMaxBlock(message))(initialPeerWithInfo.peerInfo)
  }

  /** Sends a SNAP probe to a peer using the stateRoot from their best block header.
    * Only probes peers that advertise snap/1 and haven't been probed yet.
    */
  private def maybeSendSnapProbe(peerWithInfo: PeerWithInfo, stateRootOpt: Option[ByteString]): Unit = {
    val peer = peerWithInfo.peer
    val peerInfo = peerWithInfo.peerInfo
    if (peerInfo.remoteStatus.supportsSnap && !peerInfo.isServingSnap && !SnapServerChecker.hasBeenProbed(peer.id)) {
      stateRootOpt match {
        case Some(stateRoot) =>
          val probeRequestId = SnapServerChecker.sendProbe(peer.ref, stateRoot, peer.id)
          log.info(
            "SNAP_PROBE_SENT: Probing peer {} for snap serving capability (requestId={}, stateRoot={})",
            peer.id,
            probeRequestId,
            ByteStringUtils.hash2string(stateRoot)
          )
          import context.dispatcher
          context.system.scheduler.scheduleOnce(
            SnapServerChecker.ProbeTimeoutMillis.millis,
            self,
            SnapProbeTimeout(probeRequestId)
          )
        case None =>
          log.debug("SNAP_PROBE_SKIPPED: peer={} - no headers in response", peer.id)
      }
    }
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

  /** Generate Merkle boundary proofs for a partial SNAP range response.
    *
    * Per the devp2p SNAP/1 spec, when a server returns a partial range (not all accounts/slots
    * up to the limit hash), it MUST include Merkle proofs for the first and last returned keys.
    * This allows the client to verify that no accounts/slots were omitted within the range.
    *
    * @param rootHash
    *   The state root hash for the trie
    * @param firstKey
    *   The first key in the returned range
    * @param lastKey
    *   The last key in the returned range
    * @return
    *   RLP-encoded trie nodes forming the boundary proofs, or empty if nodeStorage is unavailable
    */
  private def generateBoundaryProofs(
      rootHash: ByteString,
      firstKey: ByteString,
      lastKey: ByteString
  ): Seq[ByteString] =
    nodeStorage
      .map { ns =>
        try {
          val mptStorage = new SerializingMptStorage(new ArchiveNodeStorage(ns))
          val trie = MerklePatriciaTrie[ByteString, ByteString](rootHash.toArray, mptStorage)
          val firstProof = trie.getProof(firstKey).getOrElse(Vector.empty)
          val lastProof = trie.getProof(lastKey).getOrElse(Vector.empty)
          // Deduplicate nodes that appear in both proofs (common ancestors)
          val allNodes = (firstProof ++ lastProof).distinctBy(n => MptTraversals.encodeNode(n).toSeq)
          allNodes.map(node => ByteString(MptTraversals.encodeNode(node)))
        } catch {
          case e: Exception =>
            log.warning("Failed to generate boundary proofs for root {}: {}", rootHash.take(4).toHex, e.getMessage)
            Seq.empty
        }
      }
      .getOrElse(Seq.empty)

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

    // Guard: only serve SNAP state when sync is complete — state is incomplete during sync
    if (!appStateStorage.isSnapSyncDone()) {
      log.debug(s"[SNAP-SERVER] GetAccountRange from $peerId: SNAP sync not complete, sending empty response")
      peerWithInfo.foreach(_.peer.ref ! PeerActor.SendMessage(AccountRange(msg.requestId, Seq.empty, Seq.empty)))
      return
    }

    peerWithInfo.foreach { pwi =>
      val responseLimit = msg.responseBytes.toLong.min(MaxSnapResponseBytes)
      flatAccountStorage match {
        case Some(storage) =>
          import cats.effect.unsafe.IORuntime
          implicit val runtime: IORuntime = IORuntime.global
          val accounts = storage
            .seekFrom(msg.startingHash)
            .collect { case Right((key, value)) => (key, value) }
            .takeWhile { case (key, _) =>
              // Stop when key exceeds limitHash (unsigned byte comparison)
              compareByteStrings(key, msg.limitHash) <= 0
            }
            .through(accumulateWithByteLimit(responseLimit))
            .compile
            .toVector
            .unsafeRunSync()

          val accountPairs: Seq[(ByteString, Account)] = accounts.flatMap { case (hash, rlpBytes) =>
            Account(rlpBytes).toOption.map(acc => (hash, acc))
          }

          // Generate boundary proofs for partial range responses (SNAP/1 spec compliance).
          // Proofs are needed when the response doesn't cover the full requested range.
          val proof: Seq[ByteString] =
            if (accountPairs.nonEmpty) {
              val lastReturnedHash = accountPairs.last._1
              val isPartialRange = compareByteStrings(lastReturnedHash, msg.limitHash) < 0
              if (isPartialRange)
                generateBoundaryProofs(msg.rootHash, accountPairs.head._1, lastReturnedHash)
              else
                Seq.empty // Full range returned — no proof needed
            } else Seq.empty

          log.debug(
            s"GetAccountRange response for peer $peerId: ${accountPairs.size} accounts, ${proof.size} proof nodes"
          )
          pwi.peer.ref ! PeerActor.SendMessage(AccountRange(msg.requestId, accountPairs, proof))

        case None =>
          log.debug(s"GetAccountRange: no FlatAccountStorage available, returning empty response to peer $peerId")
          pwi.peer.ref ! PeerActor.SendMessage(AccountRange(msg.requestId, Seq.empty, Seq.empty))
      }
    }
  }

  /** fs2 Pipe that accumulates (key, value) pairs until the byte limit is reached.
    * Allows one entry to overshoot (Besu's ExceedingPredicate pattern).
    */
  private def accumulateWithByteLimit(
      maxBytes: Long
  ): fs2.Pipe[cats.effect.IO, (ByteString, ByteString), (ByteString, ByteString)] = { stream =>
    stream.scanChunks(0L) { (totalBytes, chunk) =>
      if (totalBytes >= maxBytes) {
        (totalBytes, fs2.Chunk.empty)
      } else {
        val builder = Vector.newBuilder[(ByteString, ByteString)]
        var bytes = totalBytes
        val iter = chunk.iterator
        while (iter.hasNext && bytes < maxBytes) {
          val pair = iter.next()
          bytes += pair._1.length + pair._2.length
          builder += pair
        }
        (bytes, fs2.Chunk.from(builder.result()))
      }
    }
  }

  /** Unsigned lexicographic comparison of two ByteStrings (used for hash range checks). */
  private def compareByteStrings(a: ByteString, b: ByteString): Int = {
    val minLen = math.min(a.length, b.length)
    var i = 0
    while (i < minLen) {
      val diff = (a(i) & 0xff) - (b(i) & 0xff)
      if (diff != 0) return diff
      i += 1
    }
    a.length - b.length
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

    if (!appStateStorage.isSnapSyncDone()) {
      log.debug(s"[SNAP-SERVER] GetStorageRanges from $peerId: SNAP sync not complete, sending empty response")
      peerWithInfo.foreach(_.peer.ref ! PeerActor.SendMessage(StorageRanges(msg.requestId, Seq.empty, Seq.empty)))
      return
    }

    peerWithInfo.foreach { pwi =>
      val responseLimit = msg.responseBytes.toLong.min(MaxSnapResponseBytes)
      flatSlotStorage match {
        case Some(storage) =>
          import cats.effect.unsafe.IORuntime
          implicit val runtime: IORuntime = IORuntime.global
          var totalBytes = 0L
          val allSlots = Seq.newBuilder[Seq[(ByteString, ByteString)]]

          val accountIter = msg.accountHashes.iterator
          while (accountIter.hasNext && totalBytes < responseLimit) {
            val accountHash = accountIter.next()
            val accountSlots = storage
              .seekStorageRange(accountHash, msg.startingHash)
              .collect { case Right((slotHash, value)) => (slotHash, value) }
              .takeWhile { case (slotHash, _) =>
                compareByteStrings(slotHash, msg.limitHash) <= 0
              }
              .compile
              .toVector
              .unsafeRunSync()

            if (accountSlots.nonEmpty) {
              val slotBytes = accountSlots.foldLeft(0L) { case (acc, (k, v)) => acc + k.length + v.length }
              totalBytes += slotBytes
              allSlots += accountSlots
            } else {
              allSlots += Seq.empty
            }
          }

          val result = allSlots.result()

          // Generate storage trie boundary proofs for the last account if its range is partial.
          // Per SNAP/1 spec, proofs are against the last account's storage trie root.
          val proof: Seq[ByteString] = {
            val lastAccountSlots = result.lastOption.getOrElse(Seq.empty)
            if (lastAccountSlots.nonEmpty) {
              val lastSlotHash = lastAccountSlots.last._1
              val isPartialRange = compareByteStrings(lastSlotHash, msg.limitHash) < 0
              if (isPartialRange) {
                // Look up the last account to get its storageRoot
                val lastAccountHash = msg.accountHashes(result.size - 1)
                val storageRootOpt = flatAccountStorage.flatMap(_.getAccount(lastAccountHash)).flatMap { rlpBytes =>
                  Account(rlpBytes).toOption.map(_.storageRoot)
                }
                storageRootOpt
                  .map(root => generateBoundaryProofs(root, lastAccountSlots.head._1, lastSlotHash))
                  .getOrElse(Seq.empty)
              } else Seq.empty
            } else Seq.empty
          }

          log.debug(
            s"GetStorageRanges response for peer $peerId: ${result.size} account slot sets, $totalBytes bytes, ${proof.size} proof nodes"
          )
          pwi.peer.ref ! PeerActor.SendMessage(StorageRanges(msg.requestId, result, proof))

        case None =>
          log.debug(s"GetStorageRanges: no FlatSlotStorage available, returning empty response to peer $peerId")
          pwi.peer.ref ! PeerActor.SendMessage(StorageRanges(msg.requestId, Seq.empty, Seq.empty))
      }
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

    if (!appStateStorage.isSnapSyncDone()) {
      log.debug(s"[SNAP-SERVER] GetTrieNodes from $peerId: SNAP sync not complete, sending empty response")
      peerWithInfo.foreach(_.peer.ref ! PeerActor.SendMessage(TrieNodes(msg.requestId, Seq.empty)))
      return
    }

    peerWithInfo.foreach { pwi =>
      val responseLimit = msg.responseBytes.toLong.min(MaxSnapResponseBytes)
      val nodes = nodeStorage match {
        case Some(storage) =>
          var totalBytes = 0L
          val builder = Seq.newBuilder[ByteString]
          val pathIter = msg.paths.iterator
          while (pathIter.hasNext && totalBytes < responseLimit) {
            val pathGroup = pathIter.next()
            if (pathGroup.nonEmpty) {
              // First element is the account trie path (or empty for root-level lookups)
              // Subsequent elements are storage trie paths under that account
              if (pathGroup.size == 1) {
                // Single path: account trie node lookup
                resolveTrieNode(msg.rootHash, pathGroup.head, storage) match {
                  case Some(nodeData) =>
                    builder += nodeData
                    totalBytes += nodeData.length
                  case None => // skip missing nodes
                }
              } else {
                // Multi-path: first is account path, rest are storage paths under that account
                // For Phase 3 we handle the account trie path; storage trie deferred to Phase 7
                val accountPath = pathGroup.head
                resolveTrieNode(msg.rootHash, accountPath, storage) match {
                  case Some(nodeData) =>
                    builder += nodeData
                    totalBytes += nodeData.length
                  case None => // skip missing
                }
                // Storage paths: walk from account's storageRoot
                // Find the account node to get storageRoot, then walk storage trie
                resolveAccountStorageRoot(msg.rootHash, accountPath, storage).foreach { storageRoot =>
                  val storageIter = pathGroup.iterator
                  storageIter.next() // skip the account path (already processed)
                  while (storageIter.hasNext && totalBytes < responseLimit) {
                    resolveTrieNode(storageRoot, storageIter.next(), storage) match {
                      case Some(nodeData) =>
                        builder += nodeData
                        totalBytes += nodeData.length
                      case None => // skip missing
                    }
                  }
                }
              }
            }
          }
          val result = builder.result()
          log.debug(
            s"GetTrieNodes response for peer $peerId: ${result.size} nodes found, $totalBytes bytes"
          )
          result
        case None =>
          log.debug(s"GetTrieNodes: no NodeStorage available, returning empty response to peer $peerId")
          Seq.empty
      }
      pwi.peer.ref ! PeerActor.SendMessage(TrieNodes(msg.requestId, nodes))
    }
  }

  /** Walk the MPT from rootHash following an HP-encoded compact path to find the node at that path.
    * Returns the RLP-encoded node bytes if found. Uses the LRU cache for upper trie nodes.
    */
  private def resolveTrieNode(
      rootHash: ByteString,
      compactPath: ByteString,
      storage: NodeStorage
  ): Option[ByteString] = {
    if (compactPath.isEmpty) {
      // Empty path = root node itself
      cachedNodeGet(rootHash, rootHash, storage).map(ByteString(_))
    } else {
      val (nibbles, _) = HexPrefix.decode(compactPath.toArray)
      // Load root node
      cachedNodeGet(rootHash, rootHash, storage).flatMap { rootEncoded =>
        val rootNode = MptTraversals.decodeNode(rootEncoded)
        walkTriePath(rootHash, rootNode, nibbles, 0, storage)
      }
    }
  }

  /** Recursively walk the trie following nibble path, returning the RLP-encoded node at the target position.
    * Uses the LRU cache for hash node resolution.
    */
  private def walkTriePath(
      rootHash: ByteString,
      node: MptNode,
      nibbles: Array[Byte],
      pos: Int,
      storage: NodeStorage
  ): Option[ByteString] = {
    if (pos >= nibbles.length) {
      // We've consumed the entire path — this node is the target
      Some(ByteString(node.encode))
    } else {
      node match {
        case BranchNode(children, _, _, _, _) =>
          val childIdx = nibbles(pos) & 0x0f
          children(childIdx) match {
            case NullNode => None
            case hashNode: HashNode =>
              cachedNodeGet(rootHash, ByteString(hashNode.hashNode), storage).flatMap { childEncoded =>
                val childNode = MptTraversals.decodeNode(childEncoded)
                walkTriePath(rootHash, childNode, nibbles, pos + 1, storage)
              }
            case inlineNode =>
              walkTriePath(rootHash, inlineNode, nibbles, pos + 1, storage)
          }

        case ExtensionNode(sharedKey, next, _, _, _) =>
          val keyNibbles = HexPrefix.decode(sharedKey.toArray)._1
          // Check if remaining path starts with the extension's shared key
          if (pos + keyNibbles.length > nibbles.length) {
            None // path too short
          } else {
            var matches = true
            var i = 0
            while (i < keyNibbles.length && matches) {
              if (nibbles(pos + i) != keyNibbles(i)) matches = false
              i += 1
            }
            if (!matches) {
              None
            } else {
              val newPos = pos + keyNibbles.length
              next match {
                case hashNode: HashNode =>
                  cachedNodeGet(rootHash, ByteString(hashNode.hashNode), storage).flatMap { childEncoded =>
                    val childNode = MptTraversals.decodeNode(childEncoded)
                    walkTriePath(rootHash, childNode, nibbles, newPos, storage)
                  }
                case _ =>
                  walkTriePath(rootHash, next, nibbles, newPos, storage)
              }
            }
          }

        case _: LeafNode =>
          // Leaf node reached before path consumed — no deeper nodes exist
          None

        case hashNode: HashNode =>
          // Resolve hash reference and continue walking
          cachedNodeGet(rootHash, ByteString(hashNode.hashNode), storage).flatMap { childEncoded =>
            val childNode = MptTraversals.decodeNode(childEncoded)
            walkTriePath(rootHash, childNode, nibbles, pos, storage)
          }

        case NullNode => None
      }
    }
  }

  /** Given an account trie path, resolve the account's storageRoot hash.
    * Returns None if the account node cannot be found or decoded.
    */
  private def resolveAccountStorageRoot(
      stateRootHash: ByteString,
      accountPath: ByteString,
      storage: NodeStorage
  ): Option[ByteString] = {
    import com.chipprbots.ethereum.rlp.{rawDecode, RLPList, RLPValue}
    // Walk account trie to find the leaf, then decode Account RLP to get storageRoot
    if (accountPath.isEmpty) return None
    val (nibbles, _) = HexPrefix.decode(accountPath.toArray)
    cachedNodeGet(stateRootHash, stateRootHash, storage).flatMap { rootEncoded =>
      val rootNode = MptTraversals.decodeNode(rootEncoded)
      findLeafValue(stateRootHash, rootNode, nibbles, 0, storage)
    }.flatMap { accountRlp =>
      // Account RLP: [nonce, balance, storageRoot, codeHash]
      try {
        rawDecode(accountRlp.toArray) match {
          case RLPList(_, _, storageRootRlp: RLPValue, _) =>
            Some(ByteString(storageRootRlp.bytes))
          case _ => None
        }
      } catch {
        case _: Exception => None
      }
    }
  }

  /** Walk the trie to find the value stored at the leaf matching the given nibble path.
    * Uses the LRU cache for hash node resolution.
    */
  private def findLeafValue(
      rootHash: ByteString,
      node: MptNode,
      nibbles: Array[Byte],
      pos: Int,
      storage: NodeStorage
  ): Option[ByteString] = {
    node match {
      case LeafNode(key, value, _, _, _) =>
        val keyNibbles = HexPrefix.decode(key.toArray)._1
        // Check remaining nibbles match the leaf key
        if (nibbles.length - pos == keyNibbles.length &&
            (0 until keyNibbles.length).forall(i => nibbles(pos + i) == keyNibbles(i))) {
          Some(value)
        } else {
          None
        }

      case BranchNode(children, terminator, _, _, _) =>
        if (pos >= nibbles.length) {
          terminator
        } else {
          val childIdx = nibbles(pos) & 0x0f
          children(childIdx) match {
            case NullNode => None
            case hashNode: HashNode =>
              cachedNodeGet(rootHash, ByteString(hashNode.hashNode), storage).flatMap { enc =>
                findLeafValue(rootHash, MptTraversals.decodeNode(enc), nibbles, pos + 1, storage)
              }
            case inline => findLeafValue(rootHash, inline, nibbles, pos + 1, storage)
          }
        }

      case ExtensionNode(sharedKey, next, _, _, _) =>
        val keyNibbles = HexPrefix.decode(sharedKey.toArray)._1
        if (pos + keyNibbles.length > nibbles.length) return None
        var i = 0
        while (i < keyNibbles.length) {
          if (nibbles(pos + i) != keyNibbles(i)) return None
          i += 1
        }
        val newPos = pos + keyNibbles.length
        next match {
          case hashNode: HashNode =>
            cachedNodeGet(rootHash, ByteString(hashNode.hashNode), storage).flatMap { enc =>
              findLeafValue(rootHash, MptTraversals.decodeNode(enc), nibbles, newPos, storage)
            }
          case _ => findLeafValue(rootHash, next, nibbles, newPos, storage)
        }

      case hashNode: HashNode =>
        cachedNodeGet(rootHash, ByteString(hashNode.hashNode), storage).flatMap { enc =>
          findLeafValue(rootHash, MptTraversals.decodeNode(enc), nibbles, pos, storage)
        }

      case NullNode => None
    }
  }

  /** Soft response size limit for SNAP serving (2MB, matches Geth's softResponseLimit) */
  private val MaxSnapResponseBytes: Long = 2L * 1024 * 1024

  /** Handle incoming GetByteCodes request from a peer (server-side).
    * Looks up each requested code hash in EvmCodeStorage, accumulating results
    * until the response byte limit is reached.
    */
  private def handleGetByteCodes(
      msg: GetByteCodes,
      peerId: PeerId,
      peerWithInfo: Option[PeerWithInfo]
  ): Unit = {
    log.debug(
      s"Received GetByteCodes request from peer $peerId: requestId=${msg.requestId}, hashes=${msg.hashes.size}, bytes=${msg.responseBytes}"
    )

    if (!appStateStorage.isSnapSyncDone()) {
      log.debug(s"[SNAP-SERVER] GetByteCodes from $peerId: SNAP sync not complete, sending empty response")
      peerWithInfo.foreach(_.peer.ref ! PeerActor.SendMessage(ByteCodes(msg.requestId, Seq.empty)))
      return
    }

    peerWithInfo.foreach { pwi =>
      val responseLimit = msg.responseBytes.toLong.min(MaxSnapResponseBytes)
      val codes = evmCodeStorage match {
        case Some(storage) =>
          var totalBytes = 0L
          val builder = Seq.newBuilder[ByteString]
          val iter = msg.hashes.iterator
          while (iter.hasNext && totalBytes < responseLimit) {
            storage.get(iter.next()) match {
              case Some(code) =>
                builder += code
                totalBytes += code.length
              case None => // skip missing codes
            }
          }
          val result = builder.result()
          log.debug(
            s"GetByteCodes response for peer $peerId: ${result.size}/${msg.hashes.size} codes found, $totalBytes bytes"
          )
          result
        case None =>
          log.debug(s"GetByteCodes: no EvmCodeStorage available, returning empty response to peer $peerId")
          Seq.empty
      }
      pwi.peer.ref ! PeerActor.SendMessage(ByteCodes(msg.requestId, codes))
    }
  }

}

object NetworkPeerManagerActor {

  val msgCodesWithInfo: Set[Int] = Set(
    Codes.BlockHeadersCode,
    Codes.NewBlockCode,
    Codes.NewBlockHashesCode,
    // SNAP protocol response codes (responses we receive from peers as a client)
    SNAP.Codes.AccountRangeCode,
    SNAP.Codes.StorageRangesCode,
    SNAP.Codes.TrieNodesCode,
    SNAP.Codes.ByteCodesCode,
    // SNAP protocol request codes (requests we receive from peers as a server)
    SNAP.Codes.GetAccountRangeCode,
    SNAP.Codes.GetStorageRangesCode,
    SNAP.Codes.GetByteCodesCode,
    SNAP.Codes.GetTrieNodesCode
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
      bestBlockHash: ByteString,
      isServingSnap: Boolean = false // Verified by SnapServerChecker probe
  ) extends HandshakeResult {

    def withForkAccepted(forkAccepted: Boolean): PeerInfo = copy(forkAccepted = forkAccepted)

    def withBestBlockData(maxBlockNumber: BigInt, bestBlockHash: ByteString): PeerInfo =
      copy(maxBlockNumber = maxBlockNumber, bestBlockHash = bestBlockHash)

    def withChainWeight(weight: ChainWeight): PeerInfo =
      copy(chainWeight = weight)

    def withServingSnap(serving: Boolean): PeerInfo = copy(isServingSnap = serving)

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

  /** Sent by scheduler when a snap server probe times out */
  private case class SnapProbeTimeout(requestId: BigInt)

  def props(
      peerManagerActor: ActorRef,
      peerEventBusActor: ActorRef,
      appStateStorage: AppStateStorage,
      forkResolverOpt: Option[ForkResolver],
      evmCodeStorage: Option[EvmCodeStorage] = None,
      nodeStorage: Option[NodeStorage] = None,
      flatAccountStorage: Option[FlatAccountStorage] = None,
      flatSlotStorage: Option[FlatSlotStorage] = None,
      snapSyncControllerOpt: Option[ActorRef] = None
  ): Props =
    Props(
      new NetworkPeerManagerActor(
        peerManagerActor,
        peerEventBusActor,
        appStateStorage,
        forkResolverOpt,
        evmCodeStorage,
        nodeStorage,
        flatAccountStorage,
        flatSlotStorage,
        snapSyncControllerOpt
      )
    )
}
