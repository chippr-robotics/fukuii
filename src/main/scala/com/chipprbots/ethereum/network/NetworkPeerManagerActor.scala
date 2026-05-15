package com.chipprbots.ethereum.network

import scala.concurrent.duration._

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
import com.chipprbots.ethereum.network.p2p.messages.ETH62
import com.chipprbots.ethereum.network.p2p.messages.ETH62.{BlockHeaders => ETH62BlockHeaders}
import com.chipprbots.ethereum.network.p2p.messages.ETH62.NewBlockHashes
import com.chipprbots.ethereum.network.p2p.messages.ETH66
import com.chipprbots.ethereum.network.p2p.messages.ETH66.{BlockHeaders => ETH66BlockHeaders}
import com.chipprbots.ethereum.network.p2p.messages.ETH64
import com.chipprbots.ethereum.network.p2p.messages.ETH69
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
    evmCodeStorageOpt: Option[com.chipprbots.ethereum.db.storage.EvmCodeStorage] = None,
    mptStorageOpt: Option[com.chipprbots.ethereum.db.storage.MptStorage] = None,
    blockchainReader: Option[com.chipprbots.ethereum.domain.BlockchainReader] = None,
    isPoWChain: Boolean = true
) extends Actor
    with ActorLogging {

  private[network] type PeersWithInfo = Map[PeerId, PeerWithInfo]

  // Maximum length for hex string in debug logs (to avoid very long log lines)

  // Mutable reference to SNAPSyncController that can be set after initialization
  private var snapSyncControllerOpt: Option[ActorRef] = initialSnapSyncControllerOpt

  private var emptyHeaderResponses: Int = 0

  // Last time we observed each peer pushing or replying with a block-height signal. Used by
  // the periodic best-block re-probe to skip peers whose ETH/69 `BlockRangeUpdate` arrived
  // recently — they're already keeping `maxBlockNumber` fresh on their own.
  private val lastBlockSignalMs = scala.collection.mutable.Map.empty[PeerId, Long]

  // Last CL forkchoice head reported by SNAPSyncController. None until first
  // `UpdateClHead` arrives. Pre-merge chains never set this and the lagging-peer-eviction
  // loop is a no-op there (correct: ETC mainnet has no consensus layer).
  private var lastKnownClHead: Option[BigInt] = None

  // First timestamp at which a peer was observed lagging more than `LaggingPeerLagThreshold`
  // blocks behind `lastKnownClHead`. Used for hysteresis: a peer needs to stay lagging
  // continuously for `LaggingPeerEvictAfter` before being disconnected. Cleared when the
  // peer catches up (so a peer that's mid-catch-up doesn't get evicted) or on disconnect.
  private val laggingPeerSince = scala.collection.mutable.Map.empty[PeerId, Long]

  // 60s network summary — read+reset RLPx counters and log one aggregate line
  context.system.scheduler.scheduleWithFixedDelay(
    60.seconds,
    60.seconds,
    self,
    NetworkPeerManagerActor.LogNetworkSummary
  )(context.dispatcher)

  // Periodic peer best-block re-probe. The post-handshake eager probe (below) updates
  // `PeerInfo.maxBlockNumber` once; thereafter only `NewBlock` / `NewBlockHashes` gossip
  // (dead post-merge) or ETH/69 `BlockRangeUpdate` (only if the remote actively pushes)
  // keeps it current. Without a periodic refresh, peers freeze at their handshake-time
  // block forever — breaking the freshness floor (PR #1234), the pivot selection in
  // `SNAPSyncController.currentNetworkBestFromSnapPeers`, and the chronically-lagging-peer
  // eviction landing in the next PR.
  //
  // Cadence: every 5 minutes. One `GetBlockHeaders(bestHash, 1)` per peer is negligible
  // bandwidth (peers already serve thousands of SNAP requests in that window). Skip
  // ETH/69 peers whose `BlockRangeUpdate` arrived within the last `BlockSignalStaleAfter`
  // window — they're already self-reporting.
  context.system.scheduler.scheduleWithFixedDelay(
    BestBlockRefreshInterval,
    BestBlockRefreshInterval,
    self,
    NetworkPeerManagerActor.RefreshPeerBestBlocks
  )(context.dispatcher)

  // Periodic lagging-peer eviction. Peers chronically more than `LaggingPeerLagThreshold`
  // blocks below the CL head occupy connection slots that fresh peers can't take. Without
  // eviction the pool decays to whatever ratio of stuck-vs-fresh peers happened to
  // connect first. Hysteresis (10 min) prevents catching peers that are mid-catch-up.
  // Pre-merge chains have no `lastKnownClHead` → handler is a no-op.
  context.system.scheduler.scheduleWithFixedDelay(
    LaggingPeerCheckInterval,
    LaggingPeerCheckInterval,
    self,
    NetworkPeerManagerActor.CheckLaggingPeers
  )(context.dispatcher)

  // Subscribe to the event of any peer getting handshaked
  peerEventBusActor ! Subscribe(PeerHandshaked)

  // Subscribe globally to SNAP request codes. The hive devp2p snap test client sends
  // GetAccountRange/etc immediately after the RLPx hello, BEFORE the ETH-status exchange
  // (and therefore before PeerHandshakeSuccessful fires and the per-peer subscription is
  // installed). Without a global subscription those early requests get dropped by the
  // event bus and the test peer times out waiting for a reply.
  peerEventBusActor ! Subscribe(
    MessageClassifier(
      Set(
        SNAP.Codes.GetAccountRangeCode,
        SNAP.Codes.GetStorageRangesCode,
        SNAP.Codes.GetTrieNodesCode,
        SNAP.Codes.GetByteCodesCode
      ),
      PeerSelector.AllPeers
    )
  )

  override def receive: Receive = handleMessages(Map.empty)

  /** Processes both messages for updating the information about each peer and for requesting this information
    *
    * @param peersWithInfo,
    *   which has the peer and peer information for each handshaked peer (identified by it's id)
    */
  def handleMessages(peersWithInfo: PeersWithInfo): Receive =
    handleCommonMessages(peersWithInfo).orElse(handlePeersInfoEvents(peersWithInfo))

  private def peerHasUpdatedBestBlock(@annotation.unused peerInfo: PeerInfo): Boolean =
    // All handshaked peers are usable. go-ethereum has no equivalent gate (eth/peerset.go all()
    // returns all peers unconditionally). ETH/69 peers carry latestBlock in Status; ETH/68 peers
    // carry bestHash. Both are sufficient to identify the peer as having chain state.
    true

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

    case NetworkPeerManagerActor.LogNetworkSummary =>
      import com.chipprbots.ethereum.network.rlpx.RLPxConnectionHandler
      val tcpFailed = RLPxConnectionHandler.tcpFailedCount.getAndSet(0)
      val authFailed = RLPxConnectionHandler.authFailedCount.getAndSet(0)
      val authTimeout = RLPxConnectionHandler.authTimeoutCount.getAndSet(0)
      val emptyHeaders = emptyHeaderResponses; emptyHeaderResponses = 0
      val active = peersWithInfo.size
      val snapPeers = peersWithInfo.values.count(_.peerInfo.remoteStatus.supportsSnap)
      log.info(
        s"Network [60s]: active=$active peers ($snapPeers snap-capable), +$tcpFailed tcp-failed, +$authFailed auth-failed, +$authTimeout auth-timeout, +$emptyHeaders empty-headers"
      )

    case NetworkPeerManagerActor.RefreshPeerBestBlocks =>
      // Periodically poll each handshaked peer for its current head. Mirrors the eager
      // post-handshake probe (in `handlePeersInfoEvents`) but runs every
      // `BestBlockRefreshInterval` so `PeerInfo.maxBlockNumber` stays current without
      // depending on NewBlock gossip (dead post-merge) or peer-side BlockRangeUpdate
      // push. Responses route through the existing BlockHeadersCode subscription back
      // into `updateMaxBlock` and `withBestBlockData`.
      val now = System.currentTimeMillis()
      val refreshStaleAfterMs = BlockSignalStaleAfter.toMillis
      var probed = 0
      peersWithInfo.foreach { case (peerId, PeerWithInfo(peer, peerInfo)) =>
        if (peerInfo.isAtGenesis) {
          // Genesis peers are block 0 by definition — nothing to refresh.
        } else {
          // For ETH/69 peers, skip if the remote pushed a `BlockRangeUpdate` recently.
          val recentlySignaled = peerInfo.remoteStatus.capability == Capability.ETH69 &&
            lastBlockSignalMs.get(peerId).exists(t => now - t < refreshStaleAfterMs)
          if (!recentlySignaled) {
            val bestHash = peerInfo.remoteStatus.bestHash
            val probe: MessageSerializable =
              if (Capability.usesRequestId(peerInfo.remoteStatus.capability))
                ETH66.GetBlockHeaders(ETH66.nextRequestId, Right(bestHash), 1, 0, reverse = false)
              else
                ETH62.GetBlockHeaders(Right(bestHash), 1, 0, reverse = false)
            log.debug(
              "BEST_BLOCK_REPROBE: peer={} cap={} maxBlock={} — periodic refresh",
              peer.id,
              peerInfo.remoteStatus.capability,
              peerInfo.maxBlockNumber
            )
            peerManagerActor ! PeerManagerActor.SendMessage(probe, peer.id)
            probed += 1
          }
        }
      }
      if (probed > 0) {
        log.debug(s"BEST_BLOCK_REPROBE: probed $probed/${peersWithInfo.size} peers for current head")
      }

    case NetworkPeerManagerActor.UpdateClHead(blockNumber) =>
      // Cheap update from SNAPSyncController. We only act on this in the CheckLaggingPeers
      // tick, so no need to do anything else here.
      if (!lastKnownClHead.contains(blockNumber)) {
        lastKnownClHead = Some(blockNumber)
      }

    case NetworkPeerManagerActor.CheckLaggingPeers =>
      lastKnownClHead match {
        case None =>
          // Pre-merge chain or CL hasn't connected yet — no authoritative tip to anchor on.
          ()
        case Some(clHead) =>
          val now = System.currentTimeMillis()
          val poolSize = peersWithInfo.size
          if (poolSize <= LaggingPeerMinPoolFloor) {
            // Don't crater the pool on a small network. Better one stale peer than zero peers.
            log.debug(
              s"LAGGING_PEER_CHECK: pool=$poolSize <= floor=$LaggingPeerMinPoolFloor — skipping eviction sweep"
            )
          } else {
            val laggingFloor = clHead - LaggingPeerLagThreshold
            val candidates = peersWithInfo.iterator
              .flatMap { case (peerId, PeerWithInfo(peer, peerInfo)) =>
                if (peerInfo.maxBlockNumber > 0 && peerInfo.maxBlockNumber < laggingFloor) {
                  val firstSeen = laggingPeerSince.getOrElseUpdate(peerId, now)
                  val laggedFor = now - firstSeen
                  if (laggedFor >= LaggingPeerEvictAfter.toMillis) Some((peerId, peer, peerInfo, laggedFor))
                  else None
                } else {
                  // Peer caught up (or never lagged) — reset hysteresis.
                  laggingPeerSince.remove(peerId)
                  None
                }
              }
              .take(LaggingPeerMaxEvictionsPerCycle)
              .toList

            candidates.foreach { case (peerId, peer, peerInfo, laggedFor) =>
              log.info(
                s"LAGGING_PEER_EVICT: peer=${peerId.value} maxBlock=${peerInfo.maxBlockNumber} " +
                  s"(${clHead - peerInfo.maxBlockNumber} behind CL head $clHead) for ${laggedFor / 1000}s " +
                  s"— disconnecting and blacklisting for $LaggingPeerBlacklistDuration"
              )
              com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncMetrics.incrementLaggingPeerEvicted()
              peer.ref ! DisconnectPeer(Disconnect.Reasons.UselessPeer)
              // PeerClosedConnection will apply a short-tier (2-min) blacklist for UselessPeer
              // via getBlacklistDuration. That's right for transient rejections, but a peer that
              // failed our 10-min hysteresis is a *chronic* laggard — re-dialing in 2 min just
              // restarts the eviction cycle. Schedule a longer override that lands AFTER the
              // PeerClosedConnection's short-tier add; cache.put replaces by key, so the longer
              // duration wins.
              context.system.scheduler.scheduleOnce(
                LaggingPeerBlacklistOverrideDelay,
                peerManagerActor,
                PeerManagerActor.AddToBlacklistRequest(
                  address = peer.remoteAddress.getHostString,
                  duration = Some(LaggingPeerBlacklistDuration),
                  reason = Disconnect.reasonToString(Disconnect.Reasons.UselessPeer)
                )
              )(context.dispatcher)
              laggingPeerSince.remove(peerId)
            }
          }
      }

    // SNAPSyncController sends ConnectToPeer to networkPeerManager — forward to PeerManagerActor
    // which is the only actor that handles it. Without this, the message is silently dropped.
    case PeerManagerActor.ConnectToPeer(uri) =>
      log.info("Forwarding ConnectToPeer({}) to PeerManagerActor", uri)
      peerManagerActor ! PeerManagerActor.ConnectToPeer(uri)
  }

  /** Processes events and updating the information about each peer
    *
    * @param peersWithInfo,
    *   which has the peer and peer information for each handshaked peer (identified by it's id)
    */
  private def handlePeersInfoEvents(peersWithInfo: PeersWithInfo): Receive = {

    // SNAP request messages are served via peerManagerActor.SendMessage by peerId, so
    // they don't require the peer to be in peersWithInfo. The hive devp2p snap test
    // client fires GetAccountRange right after the RLPx hello, before our ETH-status
    // exchange completes, so the per-peer subscription isn't yet in place — handle
    // them ahead of the peersWithInfo guard so they reach the server-side handlers.
    case MessageFromPeer(msg: GetAccountRange, peerId) =>
      handleGetAccountRange(msg, peerId, peersWithInfo.get(peerId))
    case MessageFromPeer(msg: GetStorageRanges, peerId) =>
      handleGetStorageRanges(msg, peerId, peersWithInfo.get(peerId))
    case MessageFromPeer(msg: GetTrieNodes, peerId) =>
      handleGetTrieNodes(msg, peerId, peersWithInfo.get(peerId))
    case MessageFromPeer(msg: GetByteCodes, peerId) =>
      handleGetByteCodes(msg, peerId, peersWithInfo.get(peerId))

    case MessageFromPeer(message, peerId) if peersWithInfo.contains(peerId) =>
      // Route SNAP protocol responses (from peers we're syncing from) to SNAPSyncController
      message match {
        case msg @ (_: AccountRange | _: StorageRanges | _: TrieNodes | _: ByteCodes) =>
          log.debug("Routing {} message to SNAPSyncController from peer {}", msg.getClass.getSimpleName, peerId)
          snapSyncControllerOpt.foreach(_ ! msg)

        case _ => // ETH protocol messages - no special routing needed
      }

      // Track per-peer block-height signals so the periodic re-probe (RefreshPeerBestBlocks)
      // can skip ETH/69 peers that are actively pushing BlockRangeUpdate.
      message match {
        case _: ETH69.BlockRangeUpdate | _: ETH62BlockHeaders | _: ETH66BlockHeaders | _: BaseETH6XMessages.NewBlock |
            _: NewBlockHashes =>
          lastBlockSignalMs(peerId) = System.currentTimeMillis()
        case _ => // not a block-height signal
      }

      val newPeersWithInfo = updatePeersWithInfo(peersWithInfo, peerId, message, handleReceivedMessage)
      NetworkMetrics.ReceivedMessagesCounter.increment()
      context.become(handleMessages(newPeersWithInfo))

    case PeerHandshakeSuccessful(peer, peerInfo: PeerInfo) =>
      val chainInfoDisplay =
        if (peerInfo.remoteStatus.capability == com.chipprbots.ethereum.network.p2p.messages.Capability.ETH69)
          s"latestBlock=${peerInfo.remoteStatus.latestBlock.getOrElse("?")} TD=${peerInfo.remoteStatus.chainWeight.totalDifficulty} (ETH/69, TD from local DB or block-number proxy)"
        else
          s"TD=${peerInfo.remoteStatus.chainWeight.totalDifficulty}"
      // INFO-level so operators can see real chain affiliation (networkId, forkId, height,
      // forkAccepted) of every peer that completes handshake. Critical for diagnosing
      // bootstrap failures where most inbound peers are wrong-chain DHT noise — the
      // INBOUND_CAP_OFFSETS log only proves capability negotiation, not chain membership.
      log.info(
        s"PEER_HANDSHAKE_SUCCESS: Peer ${peer.id} cap=${peerInfo.remoteStatus.capability} " +
          s"networkId=${peerInfo.remoteStatus.networkId} " +
          s"bestHash=${ByteStringUtils.hash2string(peerInfo.remoteStatus.bestHash)} " +
          s"$chainInfoDisplay forkAccepted=${peerInfo.forkAccepted} " +
          s"supportsSnap=${peerInfo.remoteStatus.supportsSnap}"
      )
      if (peersWithInfo.contains(peer.id)) {
        val old = peersWithInfo(peer.id)
        // Keep the EXISTING entry — don't overwrite with the duplicate.
        // PeerManagerActor will drop the loser via AlreadyConnected; with the PeerDisconnected
        // gate fix, the loser's termination no longer evicts the winner. Overwriting here with
        // the duplicate (which may be the about-to-die loser) would leave a stale ref in
        // peersWithInfo — mirroring Besu's registerDisconnect guard which only updates
        // activeConnections when peer.getConnection().equals(connection) (the primary connection).
        log.warning(
          s"DUPLICATE_HANDSHAKE_DROPPED: ${peer.id} already in peersWithInfo " +
            s"(existing=${old.peer.remoteAddress} inbound=${old.peer.incomingConnection}, " +
            s"duplicate=${peer.remoteAddress} inbound=${peer.incomingConnection}) — " +
            s"keeping existing entry, duplicate will be dropped by PeerManagerActor"
        )
        // No update to peersWithInfo, no double-count in metrics
        context.become(handleMessages(peersWithInfo))
      } else {
        peerEventBusActor ! Subscribe(PeerDisconnectedClassifier(PeerSelector.WithId(peer.id)))
        peerEventBusActor ! Subscribe(MessageClassifier(msgCodesWithInfo, PeerSelector.WithId(peer.id)))
        // Besu-style eager best-block probe.
        // ETH/64-/68 STATUS carries `bestHash` but not the peer's best block *number* — only
        // ETH/61 (long gone) and ETH/69 do. Without the number, fast-sync `PivotBlockSelector`
        // sees `peer.maxBlockNumber == 0`, picks pivot = 0, and never converges (the loop
        // we hit on Barad-dûr 2026-04-29). Geth resolves this lazily inside the downloader by
        // probing the chosen peer's bestHash; besu and nethermind do it eagerly the moment
        // STATUS completes. We follow the eager pattern: one `GetBlockHeaders(bestHash, 1)`
        // immediately after handshake, the response routes through `updateMaxBlock` via the
        // existing `BlockHeadersCode` subscription and populates `PeerInfo.maxBlockNumber`.
        //
        // Skip the probe for:
        //  * ETH/69 — STATUS already carries latestBlock (stuffed into chainWeight.totalDifficulty
        //    by the handshaker), so maxBlockNumber is set on construction.
        //  * Peers at genesis (`bestHash == genesisHash`) — they're block 0 by definition;
        //    `peerHasUpdatedBestBlock` already accepts them as `isAtGenesis`.
        //
        // Peers that don't reply to the probe simply stay at maxBlockNumber=0 and get filtered
        // by `peerHasUpdatedBestBlock` — no disconnect, since Mordor peers can be flaky and
        // we don't want to throw away connections for one missed reply (Bug 26 lesson).
        if (peerInfo.remoteStatus.capability != Capability.ETH69 && !peerInfo.isAtGenesis) {
          val bestHash = peerInfo.remoteStatus.bestHash
          val probe: MessageSerializable =
            if (Capability.usesRequestId(peerInfo.remoteStatus.capability))
              ETH66.GetBlockHeaders(ETH66.nextRequestId, Right(bestHash), 1, 0, reverse = false)
            else
              ETH62.GetBlockHeaders(Right(bestHash), 1, 0, reverse = false)
          log.debug(
            "BEST_BLOCK_PROBE: peer={} cap={} bestHash={} — asking for header at bestHash to discover block number",
            peer.id,
            peerInfo.remoteStatus.capability,
            ByteStringUtils.hash2string(bestHash)
          )
          peerManagerActor ! PeerManagerActor.SendMessage(probe, peer.id)
        }
        NetworkMetrics.registerAddHandshakedPeer(peer)
        context.become(handleMessages(peersWithInfo + (peer.id -> PeerWithInfo(peer, peerInfo))))
      }

    case PeerDisconnected(peerId) if !peersWithInfo.contains(peerId) =>
      log.debug(
        "PEER_DISCONNECTED_IGNORED: {} not in peersWithInfo — likely suppressed duplicate or already removed",
        peerId
      )

    case PeerDisconnected(peerId) if peersWithInfo.contains(peerId) =>
      val pw = peersWithInfo(peerId)
      log.info(
        s"PEER_DISCONNECTED: ${peerId.value} " +
          s"addr=${pw.peer.remoteAddress} cap=${pw.peerInfo.remoteStatus.capability} " +
          s"inbound=${pw.peer.incomingConnection}"
      )
      peerEventBusActor ! Unsubscribe(PeerDisconnectedClassifier(PeerSelector.WithId(peerId)))
      peerEventBusActor ! Unsubscribe(MessageClassifier(msgCodesWithInfo, PeerSelector.WithId(peerId)))
      NetworkMetrics.registerRemoveHandshakedPeer(peersWithInfo(peerId).peer)
      lastBlockSignalMs.remove(peerId)
      laggingPeerSince.remove(peerId)
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
    // Log non-empty BlockHeaders at debug; count empty responses for 60s summary
    message match {
      case m: ETH62BlockHeaders =>
        if (m.headers.nonEmpty)
          log.debug(
            "RECV_BLOCKHEADERS: peer={}, count={}, blockNumbers={}",
            initialPeerWithInfo.peer.id,
            m.headers.size,
            m.headers.take(5).map(_.number).mkString(", ") + (if (m.headers.size > 5) "..." else "")
          )
        else
          emptyHeaderResponses += 1
      case m: ETH66BlockHeaders =>
        if (m.headers.nonEmpty)
          log.debug(
            "RECV_BLOCKHEADERS: peer={}, requestId={}, count={}, blockNumbers={}",
            initialPeerWithInfo.peer.id,
            m.requestId,
            m.headers.size,
            m.headers.take(5).map(_.number).mkString(", ") + (if (m.headers.size > 5) "..." else "")
          )
        else
          emptyHeaderResponses += 1
      case _ => // Don't log other message types
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

        val updated =
          if (maxBlockNumber > initialPeerInfo.maxBlockNumber)
            initialPeerInfo.withBestBlockData(maxBlockNumber, maxBlockHash)
          else
            initialPeerInfo

        // For ETH/69 peers: re-resolve chainWeight via 3-tier (hash → canonical-number → POW_SCALING).
        // Hash-only lookup misses when the peer's current head differs from our stored pivot hash;
        // full 3-tier falls back to proportional scaling from our real anchor so the refresh fires
        // even when the peer is ahead of our downloaded chain.
        blockchainReader match {
          case Some(reader) if updated.remoteStatus.capability == Capability.ETH69 =>
            val (cw, source) = reader.resolveETH69ChainWeight(maxBlockHash, maxBlockNumber, isPoWChain)
            val isImprovement = cw.totalDifficulty > updated.chainWeight.totalDifficulty
            if (isImprovement && source != "COLD_START") {
              log.info(
                "ETH69_CHAINWEIGHT_REFRESH: blockNum={} newTD={} prevTD={} source={}",
                maxBlockNumber,
                cw.totalDifficulty,
                updated.chainWeight.totalDifficulty,
                source
              )
              updated.withChainWeight(cw)
            } else updated
          case _ => updated
        }
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
      case m: ETH69.BlockRangeUpdate =>
        update(Seq((m.latestBlock, m.latestBlockHash)))
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

    // Route reply via peerManagerActor.SendMessage so it works whether or not the peer is
    // already in PeersWithInfo (early SNAP requests can arrive before ETH-status exchange).
    val _ = peerWithInfo
    val response: AccountRange =
      try
        mptStorageOpt match {
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
            AccountRange(msg.requestId, Seq.empty, Seq.empty)
        }
      catch {
        case t: Throwable =>
          log.error(
            s"serveAccountRange threw for peer $peerId (requestId=${msg.requestId}): ${t.getClass.getName}: ${t.getMessage}"
          )
          AccountRange(msg.requestId, Seq.empty, Seq.empty)
      }
    peerManagerActor ! PeerManagerActor.SendMessage(response, peerId)
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

  /** Per SNAP/1: nodes only need to serve state for "recent" roots — geth uses a 128-block window. Looks up the
    * requested rootHash in a cached set of recent canonical state roots. Returns true (serve) if blockchainReader isn't
    * injected.
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

    val _ = peerWithInfo
    val response = mptStorageOpt match {
      case Some(storage) =>
        // Per-account storage roots come from looking up each account in the state trie.
        // Here we resolve via blockchainReader's ability to fetch the account at the
        // canonical state root — but the SNAP request specifies an arbitrary state root.
        // Approximation: walk the account trie at msg.rootHash to find each account's
        // storageRoot. Since this is only on the server-serve path, simple fallback:
        // look up each account via SnapServer using msg.rootHash.
        import com.chipprbots.ethereum.network.snapserver.SnapServer
        import com.chipprbots.ethereum.mpt.{MerklePatriciaTrie, NullNode}
        // Resolve the state-trie root ONCE per request. The per-account closure
        // reuses this resolved node instead of re-fetching on every call.
        val stateRootNodeOpt: Option[com.chipprbots.ethereum.mpt.MptNode] =
          try
            if (msg.rootHash.toArray.sameElements(MerklePatriciaTrie.EmptyRootHash)) None
            else {
              val node = storage.get(msg.rootHash.toArray)
              if (node == NullNode) None else Some(node)
            }
          catch { case _: Throwable => None }
        val accountRoot: ByteString => Option[ByteString] = { accountHash =>
          val nibbles = SnapServer.hashToNibbles(accountHash)
          try
            stateRootNodeOpt.flatMap { rootNode =>
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
          catch { case _: Throwable => None }
        }
        try
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
        catch {
          case t: Throwable =>
            log.error(
              s"serveStorageRanges threw for peer $peerId (requestId=${msg.requestId}): ${t.getClass.getName}: ${t.getMessage}"
            )
            StorageRanges(msg.requestId, Seq.empty, Seq.empty)
        }
      case None =>
        StorageRanges(msg.requestId, Seq.empty, Seq.empty)
    }
    peerManagerActor ! PeerManagerActor.SendMessage(response, peerId)
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

    val _ = peerWithInfo
    val response: TrieNodes =
      try
        mptStorageOpt match {
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
      catch {
        case t: Throwable =>
          log.error(
            s"serveTrieNodes threw for peer $peerId (requestId=${msg.requestId}): ${t.getClass.getName}: ${t.getMessage}"
          )
          TrieNodes(msg.requestId, Seq.empty)
      }
    peerManagerActor ! PeerManagerActor.SendMessage(response, peerId)
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

    val _ = peerWithInfo
    val response: ByteCodes =
      try
        evmCodeStorageOpt match {
          case Some(storage) =>
            com.chipprbots.ethereum.network.snapserver.SnapServer.serveByteCodes(
              requestId = msg.requestId,
              hashes = msg.hashes,
              responseBytes = msg.responseBytes,
              storage = storage
            )
          case None =>
            ByteCodes(msg.requestId, Seq.empty)
        }
      catch {
        case t: Throwable =>
          log.error(
            s"serveByteCodes threw for peer $peerId (requestId=${msg.requestId}): ${t.getClass.getName}: ${t.getMessage}"
          )
          ByteCodes(msg.requestId, Seq.empty)
      }
    peerManagerActor ! PeerManagerActor.SendMessage(response, peerId)
  }

}

object NetworkPeerManagerActor {

  val msgCodesWithInfo: Set[Int] = Set(
    Codes.BlockHeadersCode,
    Codes.NewBlockCode,
    Codes.NewBlockHashesCode,
    Codes.BlockRangeUpdateCode,
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
      capabilities: List[Capability] = List.empty,
      latestBlock: Option[BigInt] = None
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
        s"latestBlock: $latestBlock" +
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

    /** ETH/69: no totalDifficulty on the wire. Caller provides a resolved ChainWeight — either looked up from local
      * ChainWeightStorage via latestBlockHash (accurate PoW TD when the peer's block is already in our chain) or a
      * block-number proxy as fallback. latestBlock is stored separately so PeerInfo.apply can initialize maxBlockNumber
      * correctly without conflating it with chainWeight.totalDifficulty.
      */
    def fromETH69Status(
        status: com.chipprbots.ethereum.network.p2p.messages.ETH69.Status,
        negotiatedCapability: Capability,
        supportsSnap: Boolean,
        capabilities: List[Capability],
        resolvedChainWeight: ChainWeight
    ): RemoteStatus =
      RemoteStatus(
        negotiatedCapability,
        status.networkId,
        resolvedChainWeight,
        status.latestBlockHash,
        status.genesisHash,
        supportsSnap,
        capabilities,
        latestBlock = Some(status.latestBlock)
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
          remoteStatus.latestBlock.getOrElse(BigInt(0)) // ETH/69: block number from Status, stored separately
        else BigInt(0) // ETH/64-68: maxBlockNumber is updated via peerHasUpdatedBestBlock messages
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
  private[network] case object LogNetworkSummary

  /** Self-message that drives the periodic best-block re-probe loop. */
  private[network] case object RefreshPeerBestBlocks

  /** Self-message that drives the lagging-peer-eviction loop. */
  private[network] case object CheckLaggingPeers

  /** Sent by `SNAPSyncController` whenever it ingests a new CL forkchoice head. Lets the peer manager evict
    * chronically-lagging peers (more than `LaggingPeerLagThreshold` blocks below the CL head) so discovery can refill
    * the connection slot with a fresh peer. Without this signal the actor has no notion of "actual chain tip" —
    * pre-merge chains never send it and lagging-peer eviction is correctly disabled.
    */
  case class UpdateClHead(blockNumber: BigInt)

  /** How often to send each handshaked peer a one-shot `GetBlockHeaders(bestHash, 1)` to refresh
    * `PeerInfo.maxBlockNumber`. Defaults to 5 minutes — small fraction of typical peer connection lifetimes (~30+ min)
    * and small fraction of pivot-refresh cadence.
    */
  private[network] val BestBlockRefreshInterval: FiniteDuration = 5.minutes

  /** Window after which we re-probe an ETH/69 peer even though it has already had a `BlockRangeUpdate` opportunity.
    * ETH/69 peers that don't actively push BRUs would otherwise never get re-probed. Half of the re-probe interval so a
    * quiet peer gets polled within 2.5 minutes regardless of its push cadence.
    */
  private[network] val BlockSignalStaleAfter: FiniteDuration = 150.seconds

  /** Lagging-peer eviction parameters. Together: a peer that has been ≥ 4096 blocks behind the CL head continuously for
    * 10 minutes is disconnected (and IP-blacklisted for 2 minutes to prevent immediate re-dial). 4096 matches the pivot
    * freshness floor default (`snap-sync.max-pivot-staleness-blocks`, see #1234) so the same peer that fails pivot
    * selection is the one we evict.
    *
    * Per-cycle cap of 5 evictions prevents the pool from collapsing if a sweep catches many peers at once; the
    * 10-minute hysteresis prevents catching peers that are merely catching up.
    */
  private[network] val LaggingPeerCheckInterval: FiniteDuration = 2.minutes
  private[network] val LaggingPeerEvictAfter: FiniteDuration = 10.minutes
  private[network] val LaggingPeerLagThreshold: BigInt = BigInt(4096)
  private[network] val LaggingPeerMaxEvictionsPerCycle: Int = 5

  /** Below this floor of handshaked peers, skip eviction — better to keep a stale peer than to crater the connection
    * pool on a small network. The floor is intentionally low (2): on thin testnets like sepolia we often see only 3-5
    * SNAP-capable peers total, and the lagging-peer pathology is most acute exactly there. A floor of 5 would skip
    * eviction entirely and the stuck peers would never recycle out. Two is still enough to prevent a single sweep from
    * leaving us peerless mid-sync; the per-cycle cap of 5 plus the 10-minute hysteresis provide the heavier-weight
    * safeties.
    */
  private[network] val LaggingPeerMinPoolFloor: Int = 2

  /** IP-blacklist applied to a peer that just failed the 10-minute lagging-peer hysteresis. Distinct from PR #1235's
    * short-tier UselessPeer mapping (also 2 min): that's right for transient "rejected by remote" signals, but a peer
    * we *chose* to evict for chronic lag has already proven it can't keep up. 30 minutes is long enough to break the
    * immediate re-dial cycle (discovery has time to surface a fresh peer), short enough that a peer whose operator
    * restarts and resyncs can reconnect within the hour.
    */
  private[network] val LaggingPeerBlacklistDuration: FiniteDuration = 30.minutes

  /** Delay before applying the override blacklist. PeerClosedConnection takes a network round-trip to propagate; we
    * need the override to land AFTER PeerManagerActor processes that close path (which applies the short-tier 2-min
    * blacklist), otherwise the short entry overrides ours. 5 seconds is comfortably above typical disconnect
    * propagation without delaying the eviction so long that the peer could reconnect first.
    */
  private[network] val LaggingPeerBlacklistOverrideDelay: FiniteDuration = 5.seconds

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
      evmCodeStorageOpt: Option[com.chipprbots.ethereum.db.storage.EvmCodeStorage] = None,
      mptStorageOpt: Option[com.chipprbots.ethereum.db.storage.MptStorage] = None,
      blockchainReader: Option[com.chipprbots.ethereum.domain.BlockchainReader] = None,
      isPoWChain: Boolean = true
  ): Props =
    Props(
      new NetworkPeerManagerActor(
        peerManagerActor,
        peerEventBusActor,
        appStateStorage,
        forkResolverOpt,
        snapSyncControllerOpt,
        evmCodeStorageOpt,
        mptStorageOpt,
        blockchainReader,
        isPoWChain
      )
    )
}
