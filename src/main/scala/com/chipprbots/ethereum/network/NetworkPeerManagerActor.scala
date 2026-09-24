package com.chipprbots.ethereum.network

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.typed
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.ActorContext as TypedActorContext
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.TimerScheduler
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.util.ByteString

import scala.concurrent.duration.*

import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.domain.Account.*
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.network.PeerActor.DisconnectPeer
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent.*
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerSelector
import com.chipprbots.ethereum.network.PeerEventBusActor.SubscribeCmd
import com.chipprbots.ethereum.network.PeerEventBusActor.SubscriptionClassifier.*
import com.chipprbots.ethereum.network.PeerEventBusActor.UnsubscribeCmd
import com.chipprbots.ethereum.network.handshaker.Handshaker.HandshakeResult
import com.chipprbots.ethereum.network.p2p.Message
import com.chipprbots.ethereum.network.p2p.MessageSerializable
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.network.p2p.messages.Codes
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.NewBlockHashes.NewBlockHashes
import com.chipprbots.ethereum.network.p2p.messages.SNAP
import com.chipprbots.ethereum.network.p2p.messages.SNAP.*
import com.chipprbots.ethereum.network.p2p.messages.WireProtocol.Disconnect
import com.chipprbots.ethereum.utils.ByteStringUtils
import com.chipprbots.ethereum.utils.ByteStringUtils.ByteStringOps

object NetworkPeerManagerActor:

  // =========================================================================
  // Command ADT — internal Typed dispatch protocol.
  //
  // Sealed: all subtypes are defined in this companion object. PeerEvent
  // subtypes arrive wrapped in PeerEventCmd, so no external extension is
  // needed. Sealing enables exhaustiveness checking on the receiveMessage
  // dispatch.
  // =========================================================================

  sealed trait Command

  // Ask-path commands:
  final case class GetHandshakedPeersCmd(replyTo: typed.ActorRef[HandshakedPeers]) extends Command
  final case class PeerInfoRequestCmd(peerId: PeerId, replyTo: typed.ActorRef[PeerInfoResponse]) extends Command

  // Timer tick self-messages (keys and payloads for Behaviors.withTimers):
  private[network] case object LogNetworkSummaryTick extends Command
  private[network] case object RefreshPeerBestBlocksTick extends Command
  private[network] case object CheckLaggingPeersTick extends Command

  // Fire-and-forget registrations forwarded by the Classic shell:
  final case class RegisterSnapSyncControllerCmd(
      ref: typed.ActorRef[com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.Command]
  ) extends Command
  final case class RegisterChainWeightCalibrationTargetCmd(
      target: typed.ActorRef[com.chipprbots.ethereum.blockchain.sync.SyncProtocol.CalibrateChainWeightFromPeer]
  ) extends Command
  case object CalibrateChainWeightNowCmd extends Command

  // Wire protocol messages forwarded by the Classic shell:
  final case class SendMessageCmd(message: MessageSerializable, peerId: PeerId) extends Command
  final case class UpdateClHeadCmd(blockNumber: BigInt) extends Command
  final case class ConnectToPeerForwardCmd(uri: java.net.URI) extends Command

  /** Ask every handshaked peer whose advertised height is below `number` for the header of block `hash`.
    *
    * Sent only by `SyncController`'s regular-sync `BeaconHead` arm (PoS chains only), for the first block missing from
    * the ancestry of the head the consensus layer named. See `blockchain.sync.MissingAncestorProbe`. The reply needs no
    * handler of its own: `updateMaxBlock` raises the peer's advertised height from any `BlockHeaders` it sends, which
    * is what lets `PeersClient.bestPeer` select a peer that handshook at genesis and never re-advertised.
    */
  final case class ProbeMissingAncestorCmd(hash: ByteString, number: BigInt) extends Command

  // PeerEvent wrapper delivered via messageAdapter from the event bus:
  final case class PeerEventCmd(event: PeerEvent) extends Command

  // Deferred blacklist request: re-enters the actor mailbox via a single-shot
  // Typed timer (P7) instead of a Classic scheduler.scheduleOnce callback, which
  // would run on the HashedWheelTimer thread off the actor mailbox.
  final private[network] case class DeferredBlacklistCmd(
      request: PeerManagerActor.AddToBlacklistRequest
  ) extends Command

  // Timer keys for the deferred-blacklist single-shot timers. One key family per
  // schedule site; each carries the peer address so concurrent evictions get
  // independent timers (a static key would coalesce and drop all but the last).
  sealed private trait BlacklistTimerKey
  final private case class LaggingPeerBlacklistTimerKey(address: String) extends BlacklistTimerKey
  final private case class TdProxyGapBlacklistTimerKey(address: String) extends BlacklistTimerKey

  // =========================================================================
  // Typed behavior factory
  // =========================================================================

  def behavior(
      peerManagerActor: typed.ActorRef[PeerManagerActor.Command],
      peerEventBusActor: typed.ActorRef[PeerEventBusActor.Command],
      appStateStorage: AppStateStorage,
      forkResolverOpt: Option[ForkResolver],
      initialSnapSyncControllerOpt: Option[ActorRef] = None,
      evmCodeStorageOpt: Option[com.chipprbots.ethereum.db.storage.EvmCodeStorage] = None,
      mptStorageOpt: Option[com.chipprbots.ethereum.db.storage.MptStorage] = None,
      blockchainReader: Option[com.chipprbots.ethereum.domain.BlockchainReader] = None,
      isPoWChain: Boolean = false
  ): Behavior[Command] =
    Behaviors.setup { ctx =>
      Behaviors.withTimers { timers =>
        // messageAdapter bridges PeerEvent → PeerEventCmd so the event bus (Classic) can
        // deliver to the Typed core. The same adapter ref is used as the sender for all
        // Subscribe / Unsubscribe calls to PeerEventBusActor (which runs via its own Classic
        // shell that captures sender() as the subscriber).
        val eventAdapter: typed.ActorRef[PeerEvent] = ctx.messageAdapter[PeerEvent](PeerEventCmd(_))

        // Subscribe to the event of any peer getting handshaked
        peerEventBusActor ! SubscribeCmd(PeerHandshaked, eventAdapter)

        // Subscribe globally to SNAP request codes. The hive devp2p snap test client sends
        // GetAccountRange/etc immediately after the RLPx hello, BEFORE the ETH-status exchange
        // (and therefore before PeerHandshakeSuccessful fires and the per-peer subscription is
        // installed). Without a global subscription those early requests get dropped by the
        // event bus and the test peer times out waiting for a reply.
        peerEventBusActor ! SubscribeCmd(
          MessageClassifier(
            Set(
              SNAP.Codes.GetAccountRangeCode,
              SNAP.Codes.GetStorageRangesCode,
              SNAP.Codes.GetTrieNodesCode,
              SNAP.Codes.GetByteCodesCode
            ),
            PeerSelector.AllPeers
          ),
          eventAdapter
        )

        // Replace the 3 Classic scheduleWithFixedDelay calls with Behaviors.withTimers.
        timers.startTimerWithFixedDelay(LogNetworkSummaryTick, LogNetworkSummaryTick, 60.seconds)
        timers.startTimerWithFixedDelay(
          RefreshPeerBestBlocksTick,
          RefreshPeerBestBlocksTick,
          BestBlockRefreshInterval
        )
        timers.startTimerWithFixedDelay(
          CheckLaggingPeersTick,
          CheckLaggingPeersTick,
          LaggingPeerCheckInterval
        )

        new Impl(
          ctx,
          timers,
          eventAdapter,
          peerManagerActor,
          peerEventBusActor,
          appStateStorage,
          forkResolverOpt,
          initialSnapSyncControllerOpt,
          evmCodeStorageOpt,
          mptStorageOpt,
          blockchainReader,
          isPoWChain
        ).handleMessages(Map.empty)
      }
    }

  // scalastyle:off number.of.methods
  final private class Impl(
      ctx: TypedActorContext[Command],
      timers: TimerScheduler[Command],
      eventAdapter: typed.ActorRef[PeerEvent],
      peerManagerActor: typed.ActorRef[PeerManagerActor.Command],
      peerEventBusActor: typed.ActorRef[PeerEventBusActor.Command],
      appStateStorage: AppStateStorage,
      forkResolverOpt: Option[ForkResolver],
      initialSnapSyncControllerOpt: Option[ActorRef],
      evmCodeStorageOpt: Option[com.chipprbots.ethereum.db.storage.EvmCodeStorage],
      mptStorageOpt: Option[com.chipprbots.ethereum.db.storage.MptStorage],
      blockchainReader: Option[com.chipprbots.ethereum.domain.BlockchainReader],
      isPoWChain: Boolean
  ):

    private val log = ctx.log

    private[network] type PeersWithInfo = Map[PeerId, PeerWithInfo]

    // Mutable reference to SNAPSyncController that can be set after initialization
    private var snapSyncControllerOpt
        : Option[typed.ActorRef[com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.Command]] =
      initialSnapSyncControllerOpt.map(
        _.toTyped[com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.Command]
      )

    private var emptyHeaderResponses: Int = 0

    // Tracks whether our chain tip has advanced past block 0 for the first time. Used to trigger
    // a one-shot chain weight refresh for ETH69 peers whose weight was stuck at COLD_START (TD=0)
    // because `resolveETH69ChainWeight` had no anchor when they connected.
    private var coldStartCompleted = false

    // Last time we observed each peer pushing or replying with a block-height signal. Used by
    // the periodic best-block re-probe to skip peers whose ETH/69 `BlockRangeUpdate` arrived
    // recently — they're already keeping `maxBlockNumber` fresh on their own.
    private val lastBlockSignalMs = scala.collection.mutable.Map.empty[PeerId, Long]

    // Last CL forkchoice head reported by SNAPSyncController. None until first
    // `UpdateClHead` arrives. Pre-merge chains never set this and the lagging-peer-eviction
    // loop is a no-op there (correct: ETC mainnet has no consensus layer).
    private var lastKnownClHead: Option[BigInt] = None

    // SyncController registers itself here so TD-PROXY-GAP events can push calibration data
    // (peer TD + block number from the STATUS message) before the offending peer is evicted.
    private var chainWeightCalibrationTarget
        : Option[typed.ActorRef[com.chipprbots.ethereum.blockchain.sync.SyncProtocol.CalibrateChainWeightFromPeer]] =
      None

    // Best (TD, blockNumber) seen from any ETH68 peer this run: updated on ETH68 STATUS
    // handshake and on NewBlock receipt. Stays None for pure ETH69 networks.
    private var bestNetworkTip: Option[(BigInt, BigInt)] = None

    // First timestamp at which a peer was observed lagging more than `LaggingPeerLagThreshold`
    // blocks behind `lastKnownClHead`. Used for hysteresis.
    private val laggingPeerSince = scala.collection.mutable.Map.empty[PeerId, Long]

    // Per-ETH69-peer count of consecutive RefreshPeerBestBlocksTick probes where
    // maxBlockNumber did not advance. Incremented at each tick where the current
    // maxBlockNumber matches lastProbeMaxBlock; reset when maxBlockNumber advances.
    // Peers whose count reaches StaticPeerProbeThreshold are exempt from the
    // monotonic chain-weight guard in updateMaxBlock.
    private val consecutiveUnchangedProbes = scala.collection.mutable.Map.empty[PeerId, Int]

    // MaxBlockNumber recorded at the last RefreshPeerBestBlocksTick probe, per ETH69 peer.
    // Used by the next tick to detect whether the peer advanced.
    private val lastProbeMaxBlock = scala.collection.mutable.Map.empty[PeerId, BigInt]

    // PeerIds for which PMA just performed inbound-wins: the outbound PeerActor was closed and will
    // shortly publish PeerDisconnected. That event must NOT evict the peer from peersWithInfo.
    private val pendingInboundWinsDisconnects: scala.collection.mutable.Set[PeerId] =
      scala.collection.mutable.Set.empty

    // Cache of recent canonical state roots, refreshed lazily when the chain advances.
    private var freshRootCache: scala.collection.mutable.Set[ByteString] = scala.collection.mutable.Set.empty
    private var freshRootCacheTip: BigInt = BigInt(-1)

    /** Processes both messages for updating the information about each peer and for requesting this information
      *
      * @param peersWithInfo
      *   which has the peer and peer information for each handshaked peer (identified by its id)
      */
    def handleMessages(peersWithInfo: PeersWithInfo): Behavior[Command] =
      Behaviors.receiveMessage {

        // ── Ask-paths ────────────────────────────────────────────────────────

        case GetHandshakedPeersCmd(replyTo) =>
          replyTo ! HandshakedPeers(peersWithInfo.collect {
            case (_, PeerWithInfo(peer, peerInfo)) if peerHasUpdatedBestBlock(peerInfo) => peer -> peerInfo
          })
          Behaviors.same

        case PeerInfoRequestCmd(peerId, replyTo) =>
          val peerInfoOpt = peersWithInfo.get(peerId).map { case PeerWithInfo(_, peerInfo) => peerInfo }
          replyTo ! PeerInfoResponse(peerInfoOpt)
          Behaviors.same

        // ── Fire-and-forget registrations ────────────────────────────────────

        case RegisterSnapSyncControllerCmd(ref) =>
          log.info("Registering SNAPSyncController for message routing")
          snapSyncControllerOpt = Some(ref)
          Behaviors.same

        case RegisterChainWeightCalibrationTargetCmd(target) =>
          log.info("Registering chain-weight calibration target: {}", target)
          chainWeightCalibrationTarget = Some(target)
          Behaviors.same

        case CalibrateChainWeightNowCmd =>
          chainWeightCalibrationTarget match
            case None =>
              log.warn("TIMED_CALIBRATION: no calibration target registered — CalibrateChainWeightNow dropped")
            case Some(target) =>
              val (td, blockNum) = bestNetworkTip.getOrElse((BigInt(0), BigInt(0)))
              if td > BigInt(0) then
                log.info(
                  "TIMED_CALIBRATION: forwarding td={} blockNum={} source={}",
                  td,
                  blockNum,
                  if blockNum > BigInt(0) then "NEW_BLOCK_EXACT" else "ETH68_STATUS_ONLY"
                )
              else
                log.info(
                  "TIMED_CALIBRATION: no peer TD seen (pure ETH69 or no connected peers) — forwarding sentinel (0,0)"
                )
              target ! com.chipprbots.ethereum.blockchain.sync.SyncProtocol
                .CalibrateChainWeightFromPeer(td, blockNum)
          Behaviors.same

        // ── Wire protocol ─────────────────────────────────────────────────────

        case SendMessageCmd(message, peerId) =>
          NetworkMetrics.SentMessagesCounter.increment()
          log.debug(
            "SEND_VIA_MANAGER: peer={}, type={}, code=0x{}",
            peerId,
            message.underlyingMsg.getClass.getSimpleName,
            message.code.toHexString
          )
          val newPeersWithInfo = updatePeersWithInfo(peersWithInfo, peerId, message.underlyingMsg, handleSentMessage)
          peerManagerActor ! PeerManagerActor.SendMessageCmd(message, peerId)
          handleMessages(newPeersWithInfo)

        case UpdateClHeadCmd(blockNumber) =>
          if !lastKnownClHead.contains(blockNumber) then lastKnownClHead = Some(blockNumber)
          Behaviors.same

        case ProbeMissingAncestorCmd(hash, number) =>
          // Only peers that have NOT told us they are at or past this height. A peer that has is already selectable,
          // and the by-number fetch will ask it. The post-handshake probe's genesis exemption (no eager probe of a
          // peer's own genesis hash, 7c6d6e993) concerns a different request: this one names a non-genesis block the
          // consensus layer has shown exists, so an honest peer answers with the header or with nothing.
          val behind = peersWithInfo.values.filter(_.peerInfo.maxBlockNumber < number)
          if behind.nonEmpty then
            log.info(
              "MISSING_ANCESTOR_PROBE: block {} ({}) — asking {} of {} peers whose advertised head is below it",
              number,
              ByteStringUtils.hash2string(hash),
              behind.size,
              peersWithInfo.size
            )
            behind.foreach { case PeerWithInfo(peer, _) =>
              val probe: MessageSerializable =
                ETHPackets.GetBlockHeaders(ETHPackets.nextRequestId, Right(hash), 1, 0, reverse = false)
              peerManagerActor ! PeerManagerActor.SendMessageCmd(probe, peer.id)
            }
          Behaviors.same

        case ConnectToPeerForwardCmd(uri) =>
          log.info("Forwarding ConnectToPeer({}) to PeerManagerActor", uri)
          peerManagerActor ! PeerManagerActor.ConnectToPeerCmd(uri)
          Behaviors.same

        case DeferredBlacklistCmd(request) =>
          // Delivered on the actor mailbox by a single-shot timer; forward to PeerManagerActor.
          peerManagerActor ! PeerManagerActor.AddToBlacklistCmd(request, ctx.system.deadLetters)
          Behaviors.same

        // ── Timer ticks ───────────────────────────────────────────────────────

        case LogNetworkSummaryTick =>
          import com.chipprbots.ethereum.network.rlpx.RLPxConnectionHandler
          val tcpFailed = RLPxConnectionHandler.tcpFailedCount.getAndSet(0)
          val authFailed = RLPxConnectionHandler.authFailedCount.getAndSet(0)
          val authTimeout = RLPxConnectionHandler.authTimeoutCount.getAndSet(0)
          val emptyHeaders = emptyHeaderResponses; emptyHeaderResponses = 0
          val active = peersWithInfo.size
          val snapPeers = peersWithInfo.values.count(_.peerInfo.remoteStatus.supportsSnap)
          val inbound = peersWithInfo.values.count(_.peer.incomingConnection)
          val outbound = active - inbound
          log.info(
            "Network [60s]: active={} ({} snap) in={} out={} | +{} tcp-fail +{} auth-fail +{} auth-timeout +{} empty-hdrs",
            active,
            snapPeers,
            inbound,
            outbound,
            tcpFailed,
            authFailed,
            authTimeout,
            emptyHeaders
          )
          Behaviors.same

        case RefreshPeerBestBlocksTick =>
          // Periodically poll each handshaked peer for its current head. Mirrors the eager
          // post-handshake probe but runs every `BestBlockRefreshInterval` so
          // `PeerInfo.maxBlockNumber` stays current without depending on NewBlock gossip
          // (dead post-merge) or peer-side BlockRangeUpdate push.
          val now = System.currentTimeMillis()
          val refreshStaleAfterMs = BlockSignalStaleAfter.toMillis
          var probed = 0
          peersWithInfo.foreach { case (peerId, PeerWithInfo(peer, peerInfo)) =>
            if peerInfo.isAtGenesis then {
              // Genesis peers are block 0 by definition — nothing to refresh.
            } else
              val recentlySignaled = peerInfo.remoteStatus.capability == Capability.ETH69 &&
                lastBlockSignalMs.get(peerId).exists(t => now - t < refreshStaleAfterMs)
              if !recentlySignaled then
                // Archive-node detection: track consecutive probes with no maxBlockNumber advancement.
                if peerInfo.remoteStatus.capability == Capability.ETH69 then
                  lastProbeMaxBlock.get(peerId) match
                    case Some(prev) if peerInfo.maxBlockNumber <= prev =>
                      consecutiveUnchangedProbes(peerId) = consecutiveUnchangedProbes.getOrElse(peerId, 0) + 1
                    case Some(_) =>
                      consecutiveUnchangedProbes.remove(peerId)
                    case None => ()
                  lastProbeMaxBlock(peerId) = peerInfo.maxBlockNumber
                val bestHash = peerInfo.remoteStatus.bestHash
                val probe: MessageSerializable =
                  ETHPackets.GetBlockHeaders(ETHPackets.nextRequestId, Right(bestHash), 1, 0, reverse = false)
                log.debug(
                  "BEST_BLOCK_REPROBE: peer={} cap={} maxBlock={} — periodic refresh",
                  peer.id,
                  peerInfo.remoteStatus.capability,
                  peerInfo.maxBlockNumber
                )
                peerManagerActor ! PeerManagerActor.SendMessageCmd(probe, peer.id)
                probed += 1
          }
          if probed > 0 then
            log.debug(s"BEST_BLOCK_REPROBE: probed $probed/${peersWithInfo.size} peers for current head")

          // One-shot: once our chain tip advances past genesis, re-evaluate ETH69 peers that are
          // still at COLD_START (TD=0 because ourBestNum was 0 when they connected).
          var updatedPeers = peersWithInfo
          if !coldStartCompleted then
            blockchainReader.foreach { reader =>
              if reader.getBestBlockNumber > 0 then
                coldStartCompleted = true
                var refreshCount = 0
                peersWithInfo.foreach { case (peerId, PeerWithInfo(_, peerInfo)) =>
                  if peerInfo.remoteStatus.capability == Capability.ETH69 && peerInfo.maxBlockNumber > 0 then
                    val (cw, source) = reader.resolveETH69ChainWeight(
                      peerInfo.bestBlockHash,
                      peerInfo.maxBlockNumber,
                      isPoWChain
                    )
                    if source != "COLD_START" then
                      log.info(
                        "ETH69_COLD_START_RESOLVED: peer={} newTD={} source={}",
                        peerId,
                        cw.totalDifficulty,
                        source
                      )
                      updatedPeers = updatedPeers.updated(
                        peerId,
                        updatedPeers(peerId).copy(peerInfo = peerInfo.withChainWeight(cw))
                      )
                      refreshCount += 1
                }
                if refreshCount > 0 then
                  log.info("ETH69_COLD_START_RESOLVED: chain weights refreshed for {} ETH69 peers", refreshCount)
            }
          if updatedPeers ne peersWithInfo then handleMessages(updatedPeers)
          else Behaviors.same

        case CheckLaggingPeersTick =>
          lastKnownClHead match
            case None =>
              // Pre-merge chain or CL hasn't connected yet — no authoritative tip to anchor on.
              ()
            case Some(clHead) =>
              val now = System.currentTimeMillis()
              val poolSize = peersWithInfo.size
              if poolSize <= LaggingPeerMinPoolFloor then
                log.debug(
                  s"LAGGING_PEER_CHECK: pool=$poolSize <= floor=$LaggingPeerMinPoolFloor — skipping eviction sweep"
                )
              else
                val laggingFloor = clHead - LaggingPeerLagThreshold
                val candidates = peersWithInfo.iterator
                  .flatMap { case (peerId, PeerWithInfo(peer, peerInfo)) =>
                    if peerInfo.maxBlockNumber > 0 && peerInfo.maxBlockNumber < laggingFloor then
                      val firstSeen = laggingPeerSince.getOrElseUpdate(peerId, now)
                      val laggedFor = now - firstSeen
                      if laggedFor >= LaggingPeerEvictAfter.toMillis then Some((peerId, peer, peerInfo, laggedFor))
                      else None
                    else
                      laggingPeerSince.remove(peerId)
                      None
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
                  // PeerClosedConnection will apply a short-tier (2-min) blacklist for UselessPeer.
                  // Schedule a longer override that lands AFTER PeerClosedConnection's short-tier add.
                  // Single-shot Typed timer (P7) re-enters the mailbox via DeferredBlacklistCmd.
                  val blacklistAddress = peer.remoteAddress.getHostString
                  timers.startSingleTimer(
                    LaggingPeerBlacklistTimerKey(blacklistAddress),
                    DeferredBlacklistCmd(
                      PeerManagerActor.AddToBlacklistRequest(
                        address = blacklistAddress,
                        duration = Some(LaggingPeerBlacklistDuration),
                        reason = Disconnect.reasonToString(Disconnect.Reasons.UselessPeer)
                      )
                    ),
                    LaggingPeerBlacklistOverrideDelay
                  )
                  laggingPeerSince.remove(peerId)
                }
          Behaviors.same

        // ── SNAP server requests — matched BEFORE the general MessageFromPeer guard ──
        // The hive devp2p snap test client fires these right after the RLPx hello, before
        // ETH-status exchange completes, so the per-peer subscription isn't yet installed.
        // First-match-wins in receiveMessage preserves this ordering.

        case PeerEventCmd(MessageFromPeer(msg: GetAccountRange, peerId)) =>
          handleGetAccountRange(msg, peerId, peersWithInfo.get(peerId))
          Behaviors.same

        case PeerEventCmd(MessageFromPeer(msg: GetStorageRanges, peerId)) =>
          handleGetStorageRanges(msg, peerId, peersWithInfo.get(peerId))
          Behaviors.same

        case PeerEventCmd(MessageFromPeer(msg: GetTrieNodes, peerId)) =>
          handleGetTrieNodes(msg, peerId, peersWithInfo.get(peerId))
          Behaviors.same

        case PeerEventCmd(MessageFromPeer(msg: GetByteCodes, peerId)) =>
          handleGetByteCodes(msg, peerId, peersWithInfo.get(peerId))
          Behaviors.same

        // ── General MessageFromPeer (guarded by peersWithInfo membership) ────

        case PeerEventCmd(MessageFromPeer(message, peerId)) if peersWithInfo.contains(peerId) =>
          // Route SNAP protocol responses (from peers we're syncing from) to SNAPSyncController.
          // Messages are wrapped in Command ADT so the Typed SSC mailbox accepts them.
          message match
            case msg: AccountRange =>
              log.debug("Routing AccountRange to SNAPSyncController from peer {}", peerId)
              snapSyncControllerOpt.foreach(
                _ ! com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.AccountRangeResponse(msg)
              )
            case msg: ByteCodes =>
              log.debug("Routing ByteCodes to SNAPSyncController from peer {}", peerId)
              snapSyncControllerOpt.foreach(
                _ ! com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.ByteCodesResponse(msg)
              )
            case msg: StorageRanges =>
              log.debug("Routing StorageRanges to SNAPSyncController from peer {}", peerId)
              snapSyncControllerOpt.foreach(
                _ ! com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.StorageRangesResponse(msg)
              )
            case msg: TrieNodes =>
              log.debug("Routing TrieNodes to SNAPSyncController from peer {}", peerId)
              snapSyncControllerOpt.foreach(
                _ ! com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.TrieNodesResponse(msg)
              )
            case _ => // ETH protocol messages — no special routing needed
          // Track per-peer block-height signals so the periodic re-probe can skip ETH/69 peers
          // that are actively pushing BlockRangeUpdate.
          message match
            case bru: ETHPackets.BlockRangeUpdate =>
              log.info(
                "ETH69_BRU_RECEIVED: peer={} earliest={} latest={} latestHash={}",
                peerId,
                bru.earliestBlock,
                bru.latestBlock,
                bru.latestBlockHash
              )
              lastBlockSignalMs(peerId) = System.currentTimeMillis()
            case _: ETHPackets.BlockHeaders | _: ETHPackets.NewBlock | _: NewBlockHashes =>
              lastBlockSignalMs(peerId) = System.currentTimeMillis()
            case _ => // not a block-height signal
          val newPeersWithInfo = updatePeersWithInfo(peersWithInfo, peerId, message, handleReceivedMessage)
          NetworkMetrics.ReceivedMessagesCounter.increment()
          handleMessages(newPeersWithInfo)

        // ── Peer lifecycle events ─────────────────────────────────────────────

        case PeerEventCmd(PeerHandshakeSuccessful(peer, peerInfo: PeerInfo)) =>
          handlePeerHandshakeSuccessful(peer, peerInfo, peersWithInfo)

        case PeerEventCmd(PeerDisconnected(peerId)) =>
          if !peersWithInfo.contains(peerId) then
            log.debug(
              "PEER_DISCONNECTED_IGNORED: {} not in peersWithInfo — likely suppressed duplicate or already removed",
              peerId
            )
            Behaviors.same
          else if pendingInboundWinsDisconnects.contains(peerId) then
            // The outbound PeerActor that PMA closed after inbound-wins is dying. peersWithInfo already
            // holds the live inbound entry — do not evict.
            pendingInboundWinsDisconnects -= peerId
            log.info(
              "INBOUND_WINS_DISCONNECT_SUPPRESSED: {} — outbound closed by PMA, inbound retained in peersWithInfo",
              peerId
            )
            Behaviors.same
          else
            val pw = peersWithInfo(peerId)
            log.info(
              s"PEER_DISCONNECTED: ${peerId.value} " +
                s"addr=${pw.peer.remoteAddress} cap=${pw.peerInfo.remoteStatus.capability} " +
                s"inbound=${pw.peer.incomingConnection}"
            )
            peerEventBusActor ! UnsubscribeCmd(PeerDisconnectedClassifier(PeerSelector.WithId(peerId)), eventAdapter)
            peerEventBusActor ! UnsubscribeCmd(
              MessageClassifier(msgCodesWithInfo, PeerSelector.WithId(peerId)),
              eventAdapter
            )
            NetworkMetrics.registerRemoveHandshakedPeer(peersWithInfo(peerId).peer)
            PeerTelemetry.deregisterPeer(peerId)
            lastBlockSignalMs.remove(peerId)
            laggingPeerSince.remove(peerId)
            consecutiveUnchangedProbes.remove(peerId)
            lastProbeMaxBlock.remove(peerId)
            handleMessages(peersWithInfo - peerId)

        case PeerEventCmd(_) =>
          Behaviors.same
      }

    private def handlePeerHandshakeSuccessful(
        peer: Peer,
        peerInfo: PeerInfo,
        peersWithInfo: PeersWithInfo
    ): Behavior[Command] =
      val chainInfoDisplay =
        if peerInfo.remoteStatus.capability == com.chipprbots.ethereum.network.p2p.messages.Capability.ETH69 then
          s"latestBlock=${peerInfo.remoteStatus.latestBlock.getOrElse("?")} TD=${peerInfo.remoteStatus.chainWeight.totalDifficulty} (ETH/69, TD from local DB or block-number proxy)"
        else s"TD=${peerInfo.remoteStatus.chainWeight.totalDifficulty}"
      val clientType = NodeClientType.recognize(peerInfo.remoteStatus.remoteClientId)
      log.info(
        s"PEER_HANDSHAKE_SUCCESS: Peer ${peer.id} cap=${peerInfo.remoteStatus.capability} " +
          s"client=${clientType.name} " +
          s"networkId=${peerInfo.remoteStatus.networkId} " +
          s"bestHash=${ByteStringUtils.hash2string(peerInfo.remoteStatus.bestHash)} " +
          s"$chainInfoDisplay forkAccepted=${peerInfo.forkAccepted} " +
          s"supportsSnap=${peerInfo.remoteStatus.supportsSnap}"
      )

      // Track best ETH68 peer TD for timed calibration (CalibrateChainWeightNow).
      if peerInfo.remoteStatus.capability != com.chipprbots.ethereum.network.p2p.messages.Capability.ETH69 then
        val peerTD = peerInfo.remoteStatus.chainWeight.totalDifficulty.value
        if bestNetworkTip.forall { case (best, _) => peerTD > best } then
          val prevTD = bestNetworkTip.map(_._1).getOrElse(BigInt(0))
          bestNetworkTip = Some((peerTD, peerInfo.maxBlockNumber))
          log.debug(
            "BEST_NETWORK_TIP_UPDATED: source=ETH68_STATUS td={} blockNum={} prevTD={}",
            peerTD,
            peerInfo.maxBlockNumber,
            prevTD
          )

      // PEER-CHAIN-DIVERGE / TD-PROXY-GAP checks
      blockchainReader.foreach { reader =>
        peerInfo.remoteStatus.latestBlock.foreach { peerBlockNum =>
          reader.getBlockHeaderByNumber(peerBlockNum).foreach { ourHeader =>
            if ourHeader.hash.value != peerInfo.remoteStatus.bestHash then
              log.warn(
                "PEER-CHAIN-DIVERGE: Peer {} reports hash={} at block {}; our hash={}. " +
                  "One of us may be on a fork.",
                peer.id,
                ByteStringUtils.hash2string(peerInfo.remoteStatus.bestHash),
                peerBlockNum,
                ByteStringUtils.hash2string(ourHeader.hash.value)
              )
          }
        }
        if peerInfo.remoteStatus.capability != com.chipprbots.ethereum.network.p2p.messages.Capability.ETH69 then
          val peerTD = peerInfo.remoteStatus.chainWeight.totalDifficulty.value
          reader.getBestBlock.foreach { ourBest =>
            reader.getChainWeightByHash(ourBest.header.hash).foreach { ourWeight =>
              if peerTD > ourWeight.totalDifficulty.value then
                val ratio = peerTD / ourWeight.totalDifficulty.value
                // FRESH-NODE EXEMPTION (#1367, restored 2026-06-30 — dropped during the Scala-3
                // refactors #1373/#1378/#1384). TD-PROXY-GAP must NOT fire when our best block is at
                // genesis (number == 0). On a freshly-wiped node the genesis TD is the real chain
                // difficulty, not a stale proxy, so the ratio vs a real peer's cumulative TD always
                // exceeds 10K. Firing the eviction disconnects + 5-min-blacklists every legitimate
                // SNAP peer before the eager best-block probe runs → maxBlockNumber=0 everywhere →
                // pivot=0 forever. TD-PROXY-GAP is only meaningful past genesis (post-SNAP genesis-
                // proxy TD on a high best block). `number.value > 0` is the minimal, unambiguous guard.
                if ratio > BigInt(10_000) && ourBest.header.number.value > 0 then
                  // TD-PROXY-GAP: stored TD is a genesis proxy from SNAP finalization.
                  chainWeightCalibrationTarget.foreach { target =>
                    target ! com.chipprbots.ethereum.blockchain.sync.SyncProtocol
                      .CalibrateChainWeightFromPeer(peerTD, peerInfo.maxBlockNumber)
                  }
                  log.warn(
                    "TD-PROXY-GAP: peer {} TD={} vs ours={} (ratio={}x). " +
                      "Calibrating from peer STATUS data and evicting peer for 5 minutes.",
                    peer.id,
                    peerTD,
                    ourWeight.totalDifficulty.value,
                    ratio
                  )
                  peer.ref ! DisconnectPeer(Disconnect.Reasons.UselessPeer)
                  // Single-shot Typed timer (P7) re-enters the mailbox via DeferredBlacklistCmd.
                  val blacklistAddress = peer.remoteAddress.getHostString
                  timers.startSingleTimer(
                    TdProxyGapBlacklistTimerKey(blacklistAddress),
                    DeferredBlacklistCmd(
                      PeerManagerActor.AddToBlacklistRequest(
                        address = blacklistAddress,
                        duration = Some(5.minutes),
                        reason = "TD-PROXY-GAP: stale chain weight, reconnect after calibration"
                      )
                    ),
                    LaggingPeerBlacklistOverrideDelay
                  )
                else
                  log.warn(
                    "TD-DIVERGE: Peer {} TD={} > our TD={} at block {}. We may be on a lighter fork or behind.",
                    peer.id,
                    peerTD,
                    ourWeight.totalDifficulty.value,
                    ourBest.header.number
                  )
            }
          }
      }

      if peersWithInfo.contains(peer.id) then
        val old = peersWithInfo(peer.id)
        val newIsInbound = peer.incomingConnection
        val existingIsOutbound = !old.peer.incomingConnection

        if newIsInbound && existingIsOutbound then
          // Inbound-wins: swap peersWithInfo to the live inbound ref.
          pendingInboundWinsDisconnects += peer.id
          log.info(
            "DUPLICATE_HANDSHAKE_INBOUND_WINS: {} swapping {} → {} (outbound eviction suppressed)",
            peer.id,
            old.peer.remoteAddress,
            peer.remoteAddress
          )
          handleMessages(peersWithInfo + (peer.id -> PeerWithInfo(peer, peerInfo)))
        else
          // Keep the EXISTING entry — don't overwrite with the duplicate.
          log.warn(
            s"DUPLICATE_HANDSHAKE_DROPPED: ${peer.id} already in peersWithInfo " +
              s"(existing=${old.peer.remoteAddress} inbound=${old.peer.incomingConnection}, " +
              s"duplicate=${peer.remoteAddress} inbound=${peer.incomingConnection}) — " +
              s"keeping existing entry, duplicate will be dropped by PeerManagerActor"
          )
          Behaviors.same
      else
        peerEventBusActor ! SubscribeCmd(PeerDisconnectedClassifier(PeerSelector.WithId(peer.id)), eventAdapter)
        peerEventBusActor ! SubscribeCmd(
          MessageClassifier(msgCodesWithInfo, PeerSelector.WithId(peer.id)),
          eventAdapter
        )
        // Besu-style eager best-block probe. ETH/69 peers are deliberately excluded — NOT
        // because they need a substitute post-handshake message, but because they need NOTHING:
        // their initial block range is already inside the 7-field STATUS payload itself
        // (earliestBlock/latestBlock/latestBlockHash — see EthNodeStatus69ExchangeState.createStatusMsg,
        // which we just exchanged a few lines above this call). A previous version of this branch
        // sent a standalone ETH69.BlockRangeUpdate here ("announce our block range immediately after
        // STATUS"), which was wrong on two counts: (1) pure duplicate of what STATUS already said, and
        // (2) a protocol violation — EIP-7642 defines BlockRangeUpdate (0x11) as a change notification.
        // go-ethereum only emits it from blockRangeLoop on ChainHeadEvent / snap-sync-progress
        // (go-ethereum eth/handler.go: blockRangeLoop/broadcastBlockRange), gated by shouldSend()
        // (every 32 blocks, or immediately on a backward range move), and broadcasts it to ALL
        // currently-connected peers at that moment — never as a one-shot greeting to the peer that
        // just finished a handshake. fukuii's own change-driven path already exists and is unaffected
        // by this fix: BlockBroadcast.broadcastBlock (new block imported) and
        // BlockBroadcast.announceCanonicalHead (CL forkchoice head advance) both send BlockRangeUpdate
        // to ETH69 peers exactly when our range changes.
        //
        // The eager send here broke hive's devp2p Transaction/InvalidTxs/LargeTxRequest tests: the
        // harness's eth-relative code switch (cmd/devp2p/internal/ethtest/conn.go) has no case for
        // message code 17 (BlockRangeUpdate at wire offset 16+1) and panics with
        // "unhandled eth msg code 17" the moment it arrives unsolicited.
        if peerInfo.remoteStatus.capability != Capability.ETH69 && !peerInfo.isAtGenesis then
          val bestHash = peerInfo.remoteStatus.bestHash
          val probe: MessageSerializable =
            ETHPackets.GetBlockHeaders(ETHPackets.nextRequestId, Right(bestHash), 1, 0, reverse = false)
          log.debug(
            "BEST_BLOCK_PROBE: peer={} cap={} bestHash={} — asking for header at bestHash to discover block number",
            peer.id,
            peerInfo.remoteStatus.capability,
            ByteStringUtils.hash2string(bestHash)
          )
          peerManagerActor ! PeerManagerActor.SendMessageCmd(probe, peer.id)
        NetworkMetrics.registerAddHandshakedPeer(peer)
        PeerTelemetry.registerPeer(peer, peerInfo)
        handleMessages(peersWithInfo + (peer.id -> PeerWithInfo(peer, peerInfo)))

    private def peerHasUpdatedBestBlock(@annotation.unused peerInfo: PeerInfo): Boolean =
      // All handshaked peers are usable. go-ethereum has no equivalent gate (eth/peerset.go all()
      // returns all peers unconditionally). ETH/69 peers carry latestBlock in Status; ETH/68 peers
      // carry bestHash. Both are sufficient to identify the peer as having chain state.
      true

    /** Processes the message, updating the information for each peer */
    private def updatePeersWithInfo(
        peers: PeersWithInfo,
        peerId: PeerId,
        message: Message,
        messageHandler: (Message, PeerWithInfo) => PeerInfo
    ): PeersWithInfo =
      if peers.contains(peerId) then
        val peerWithInfo = peers(peerId)
        val newPeerInfo = messageHandler(message, peerWithInfo)
        peers + (peerId -> peerWithInfo.copy(peerInfo = newPeerInfo))
      else peers

    private def handleSentMessage(@annotation.unused _message: Message, initialPeerWithInfo: PeerWithInfo): PeerInfo =
      initialPeerWithInfo.peerInfo

    private def handleReceivedMessage(message: Message, initialPeerWithInfo: PeerWithInfo): PeerInfo =
      // Log non-empty BlockHeaders at debug; count empty responses for 60s summary
      message match
        case m: ETHPackets.BlockHeaders =>
          if m.headers.nonEmpty then
            log.debug(
              "RECV_BLOCKHEADERS: peer={}, requestId={}, count={}, blockNumbers={}",
              initialPeerWithInfo.peer.id,
              m.requestId,
              m.headers.size,
              m.headers.take(5).map(_.number).mkString(", ") + (if m.headers.size > 5 then "..." else "")
            )
          else emptyHeaderResponses += 1
        case m: ETHPackets.NewBlock =>
          // Track best network tip for timed TD calibration. NewBlock carries exact cumulative TD
          // and block number — more precise than ETH68 STATUS.
          val newBlockTD = m.totalDifficulty
          val newBlockNum = m.block.header.number.value
          if bestNetworkTip.forall { case (best, _) => newBlockTD > best } then
            val prevTD = bestNetworkTip.map(_._1).getOrElse(BigInt(0))
            bestNetworkTip = Some((newBlockTD, newBlockNum))
            log.debug(
              "BEST_NETWORK_TIP_UPDATED: source=NEW_BLOCK td={} blockNum={} prevTD={}",
              newBlockTD,
              newBlockNum,
              prevTD
            )
        case _ => // Don't log other message types
      updateChainWeight(message)
        .andThen(updateForkAccepted(message, initialPeerWithInfo.peer))
        .andThen(updateMaxBlock(message, initialPeerWithInfo.peer.id))(initialPeerWithInfo.peerInfo)

    private def updateChainWeight(message: Message)(initialPeerInfo: PeerInfo): PeerInfo =
      message match
        case newBlock: ETHPackets.NewBlock =>
          val prevTD = initialPeerInfo.chainWeight.totalDifficulty.value
          val actualTD = newBlock.totalDifficulty
          val delta = actualTD - prevTD
          val deltaPercent = if prevTD > 0 then (delta * 100) / prevTD else BigInt(0)
          log.debug(
            "ETH69_TIER3_ACCURACY: peer={} prevTD={} actualTD={} delta={} deltaPercent={}%",
            initialPeerInfo.remoteStatus.bestHash,
            prevTD,
            actualTD,
            delta,
            deltaPercent
          )
          initialPeerInfo.copy(chainWeight = ChainWeight.totalDifficultyOnly(newBlock.totalDifficulty))
        case _ => initialPeerInfo

    private def updateForkAccepted(message: Message, peer: Peer)(initialPeerInfo: PeerInfo): PeerInfo =
      message match
        case ETHPackets.BlockHeaders(_, blockHeaders) =>
          val newPeerInfoOpt: Option[PeerInfo] =
            for
              forkResolver <- forkResolverOpt
              forkBlockHeader <- blockHeaders.find(_.number.value == forkResolver.forkBlockNumber)
            yield
              val newFork = forkResolver.recognizeFork(forkBlockHeader)
              log.debug("Received fork block header with fork: {}", newFork)

              if !forkResolver.isAccepted(newFork) then
                log.debug("Peer is not running the accepted fork, disconnecting")
                peer.ref ! DisconnectPeer(Disconnect.Reasons.UselessPeer)
                initialPeerInfo
              else initialPeerInfo.withForkAccepted(true)
          newPeerInfoOpt.getOrElse(initialPeerInfo)

        case _ => initialPeerInfo

    private def updateMaxBlock(message: Message, peerId: PeerId)(initialPeerInfo: PeerInfo): PeerInfo =
      def update(ns: Seq[(BigInt, ByteString)]): PeerInfo =
        if ns.isEmpty then initialPeerInfo
        else
          val (maxBlockNumber, maxBlockHash) = ns.maxBy(_._1)
          if maxBlockNumber > appStateStorage.getEstimatedHighestBlock() then
            appStateStorage.putEstimatedHighestBlock(maxBlockNumber).commit()

          val updated =
            if maxBlockNumber > initialPeerInfo.maxBlockNumber then
              initialPeerInfo.withBestBlockData(maxBlockNumber, maxBlockHash)
            else initialPeerInfo

          // For ETH/69 peers: re-resolve chainWeight via 3-tier.
          // Archive/static peers (maxBlockNumber unchanged across N probes) are exempt from
          // the monotonic guard so a Tier3 overestimate at handshake can be corrected down.
          blockchainReader match
            case Some(reader) if updated.remoteStatus.capability == Capability.ETH69 =>
              val (cw, source) = reader.resolveETH69ChainWeight(maxBlockHash, maxBlockNumber, isPoWChain)
              val isImprovement = cw.totalDifficulty > updated.chainWeight.totalDifficulty
              val isPeerStatic =
                consecutiveUnchangedProbes.getOrElse(peerId, 0) >= StaticPeerProbeThreshold
              val shouldUpdate = (isImprovement || isPeerStatic) && source != "COLD_START"
              if shouldUpdate then
                log.info(
                  "ETH69_CHAINWEIGHT_REFRESH: blockNum={} newTD={} prevTD={} source={} static={}",
                  maxBlockNumber,
                  cw.totalDifficulty,
                  updated.chainWeight.totalDifficulty,
                  source,
                  isPeerStatic
                )
                updated.withChainWeight(cw)
              else updated
            case _ => updated

      message match
        case m: ETHPackets.BlockHeaders =>
          update(m.headers.map(header => (header.number.value, header.hash.value)))
        case m: ETHPackets.NewBlock =>
          update(Seq((m.block.header.number.value, m.block.header.hash.value)))
        case m: NewBlockHashes =>
          update(m.hashes.map(h => (h.number, h.hash)))
        case m: ETHPackets.BlockRangeUpdate =>
          update(Seq((m.latestBlock, m.latestBlockHash)))
        case _ => initialPeerInfo

    /** Handle incoming GetAccountRange request from a peer (server-side) */
    private def handleGetAccountRange(
        msg: GetAccountRange,
        peerId: PeerId,
        // None is expected for pre-handshake SNAP requests (hive devp2p snap test client
        // sends GetAccountRange immediately after RLPx hello, before ETH-status exchange).
        // Reserved for future per-peer rate limiting.
        @annotation.unused peerWithInfo: Option[PeerWithInfo]
    ): Unit =
      log.debug(
        s"Received GetAccountRange request from peer $peerId: requestId=${msg.requestId}, root=${msg.rootHash.take(4).toHex}, start=${msg.startingHash.take(4).toHex}, limit=${msg.limitHash.take(4).toHex}, bytes=${msg.responseBytes}"
      )
      val response: AccountRange =
        try
          mptStorageOpt match
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
        catch
          case t: Throwable =>
            log.error(
              s"serveAccountRange threw for peer $peerId (requestId=${msg.requestId}): ${t.getClass.getName}: ${t.getMessage}"
            )
            AccountRange(msg.requestId, Seq.empty, Seq.empty)
      peerManagerActor ! PeerManagerActor.SendMessageCmd(response, peerId)
      log.debug(
        "SNAP-SERVE: GetAccountRange peer={} accounts={} proofs={}",
        peerId,
        response.accounts.size,
        response.proof.size
      )

    private def refreshFreshRootCache(reader: com.chipprbots.ethereum.domain.BlockchainReader): Unit =
      val tip = reader.getBestBlockNumber
      if tip != freshRootCacheTip then
        val window = BigInt(128)
        val from = (tip - window).max(BigInt(0))
        val newCache = scala.collection.mutable.Set.empty[ByteString]
        var n = tip
        while n >= from do
          reader.getBlockHeaderByNumber(n).foreach(h => newCache += h.stateRoot.value)
          n = n - 1
        freshRootCache = newCache
        freshRootCacheTip = tip

    /** Per SNAP/1: nodes only need to serve state for "recent" roots — geth uses a 128-block window. */
    private def isStateRootFresh(rootHash: ByteString): Boolean = blockchainReader match
      case None => true
      case Some(reader) =>
        refreshFreshRootCache(reader)
        freshRootCache.contains(rootHash)

    /** Handle incoming GetStorageRanges request from a peer (server-side) */
    private def handleGetStorageRanges(
        msg: GetStorageRanges,
        peerId: PeerId,
        // None is expected for pre-handshake SNAP requests (hive devp2p snap test client
        // sends GetStorageRanges immediately after RLPx hello, before ETH-status exchange).
        // Reserved for future per-peer rate limiting.
        @annotation.unused peerWithInfo: Option[PeerWithInfo]
    ): Unit =
      log.debug(
        s"Received GetStorageRanges request from peer $peerId: requestId=${msg.requestId}, root=${msg.rootHash.take(4).toHex}, accounts=${msg.accountHashes.size}, start=${msg.startingHash.take(4).toHex}, limit=${msg.limitHash.take(4).toHex}, bytes=${msg.responseBytes}"
      )
      val response = mptStorageOpt match
        case Some(storage) =>
          import com.chipprbots.ethereum.network.snapserver.SnapServer
          import com.chipprbots.ethereum.mpt.{MerklePatriciaTrie, NullNode}
          val stateRootNodeOpt: Option[com.chipprbots.ethereum.mpt.MptNode] =
            try
              if msg.rootHash.toArray.sameElements(MerklePatriciaTrie.EmptyRootHash) then None
              else
                val node = storage.get(msg.rootHash.toArray)
                if node == NullNode then None else Some(node)
            catch
              case e: Throwable =>
                log.warn("MPT traversal error in handleGetStorageRanges (state root load): {}", e.getMessage)
                None
          val accountRoot: ByteString => Option[ByteString] = accountHash =>
            val nibbles = SnapServer.hashToNibbles(accountHash)
            try
              stateRootNodeOpt.flatMap { rootNode =>
                def find(node: com.chipprbots.ethereum.mpt.MptNode, rem: Array[Byte]): Option[ByteString] =
                  val resolved = node match
                    case h: com.chipprbots.ethereum.mpt.HashNode => storage.get(h.hashNode)
                    case other                                   => other
                  resolved match
                    case com.chipprbots.ethereum.mpt.LeafNode(key, value, _, _, _) =>
                      if rem.sameElements(key.toArray) then
                        val acct = value.toArray.toAccount
                        Some(acct.storageRoot.value)
                      else None
                    case com.chipprbots.ethereum.mpt.ExtensionNode(sk, next, _, _, _) =>
                      if rem.length >= sk.length && rem.take(sk.length).sameElements(sk.toArray) then
                        find(next, rem.drop(sk.length))
                      else None
                    case com.chipprbots.ethereum.mpt.BranchNode(children, _, _, _, _) =>
                      if rem.isEmpty then None
                      else
                        val ch = children(rem(0) & 0x0f)
                        if ch == NullNode then None else find(ch, rem.drop(1))
                    case _ => None
                find(rootNode, nibbles)
              }
            catch
              case e: Throwable =>
                log.warn("MPT traversal error in handleGetStorageRanges: {}", e.getMessage)
                None
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
          catch
            case t: Throwable =>
              log.error(
                s"serveStorageRanges threw for peer $peerId (requestId=${msg.requestId}): ${t.getClass.getName}: ${t.getMessage}"
              )
              StorageRanges(msg.requestId, Seq.empty, Seq.empty)
        case None =>
          StorageRanges(msg.requestId, Seq.empty, Seq.empty)
      peerManagerActor ! PeerManagerActor.SendMessageCmd(response, peerId)
      log.debug(
        "SNAP-SERVE: GetStorageRanges peer={} slots={} proofs={}",
        peerId,
        response.slots.size,
        response.proof.size
      )

    /** Handle incoming GetTrieNodes request from a peer (server-side) */
    private def handleGetTrieNodes(
        msg: GetTrieNodes,
        peerId: PeerId,
        // None is expected for pre-handshake SNAP requests (hive devp2p snap test client
        // sends GetTrieNodes immediately after RLPx hello, before ETH-status exchange).
        // Reserved for future per-peer rate limiting.
        @annotation.unused peerWithInfo: Option[PeerWithInfo]
    ): Unit =
      log.debug(
        s"Received GetTrieNodes request from peer $peerId: requestId=${msg.requestId}, root=${msg.rootHash.take(4).toHex}, paths=${msg.paths.size}, bytes=${msg.responseBytes}"
      )
      val response: TrieNodes =
        try
          mptStorageOpt match
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
        catch
          case t: Throwable =>
            log.error(
              s"serveTrieNodes threw for peer $peerId (requestId=${msg.requestId}): ${t.getClass.getName}: ${t.getMessage}"
            )
            TrieNodes(msg.requestId, Seq.empty)
      peerManagerActor ! PeerManagerActor.SendMessageCmd(response, peerId)
      log.debug(
        "SNAP-SERVE: GetTrieNodes peer={} nodes={}",
        peerId,
        response.nodes.size
      )

    /** Handle incoming GetByteCodes request from a peer (server-side) */
    private def handleGetByteCodes(
        msg: GetByteCodes,
        peerId: PeerId,
        // None is expected for pre-handshake SNAP requests (hive devp2p snap test client
        // sends GetByteCodes immediately after RLPx hello, before ETH-status exchange).
        // Reserved for future per-peer rate limiting.
        @annotation.unused peerWithInfo: Option[PeerWithInfo]
    ): Unit =
      log.debug(
        s"Received GetByteCodes request from peer $peerId: requestId=${msg.requestId}, hashes=${msg.hashes.size}, bytes=${msg.responseBytes}"
      )
      val response: ByteCodes =
        try
          evmCodeStorageOpt match
            case Some(storage) =>
              com.chipprbots.ethereum.network.snapserver.SnapServer.serveByteCodes(
                requestId = msg.requestId,
                hashes = msg.hashes,
                responseBytes = msg.responseBytes,
                storage = storage
              )
            case None =>
              ByteCodes(msg.requestId, Seq.empty)
        catch
          case t: Throwable =>
            log.error(
              s"serveByteCodes threw for peer $peerId (requestId=${msg.requestId}): ${t.getClass.getName}: ${t.getMessage}"
            )
            ByteCodes(msg.requestId, Seq.empty)
      peerManagerActor ! PeerManagerActor.SendMessageCmd(response, peerId)
      log.debug(
        "SNAP-SERVE: GetByteCodes peer={} codes={}",
        peerId,
        response.codes.size
      )
  // scalastyle:on number.of.methods

  // =========================================================================
  // Public wire messages, domain types, and companion constants
  // (unchanged from Classic — all callers import from this companion)
  // =========================================================================

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
      latestBlock: Option[BigInt] = None,
      remoteClientId: String = ""
  ):
    override def toString: String =
      s"RemoteStatus { " +
        s"capability: $capability, " +
        s"networkId: $networkId, " +
        s"chainWeight: $chainWeight, " +
        s"bestHash: ${ByteStringUtils.hash2string(bestHash)}, " +
        s"genesisHash: ${ByteStringUtils.hash2string(genesisHash)}, " +
        s"supportsSnap: $supportsSnap, " +
        s"capabilities: ${capabilities.mkString("[", ", ", "]")}" +
        s"latestBlock: $latestBlock, " +
        s"remoteClientId: $remoteClientId" +
        s"}"

  object RemoteStatus:
    def apply(
        status: ETHPackets.Status68.Status68,
        negotiatedCapability: Capability,
        supportsSnap: Boolean,
        capabilities: List[Capability],
        remoteClientId: String
    ): RemoteStatus =
      RemoteStatus(
        negotiatedCapability,
        status.networkId,
        ChainWeight.totalDifficultyOnly(status.totalDifficulty),
        status.bestHash,
        status.genesisHash,
        supportsSnap,
        capabilities,
        remoteClientId = remoteClientId
      )

    def apply(status: ETHPackets.Status68.Status68): RemoteStatus =
      RemoteStatus(
        Capability.ETH63,
        status.networkId,
        ChainWeight.totalDifficultyOnly(status.totalDifficulty),
        status.bestHash,
        status.genesisHash,
        false,
        List.empty
      )

    /** ETH/69: no totalDifficulty on the wire. Caller provides a resolved ChainWeight. */
    def fromETH69Status(
        status: com.chipprbots.ethereum.network.p2p.messages.ETH69.Status,
        negotiatedCapability: Capability,
        supportsSnap: Boolean,
        capabilities: List[Capability],
        resolvedChainWeight: ChainWeight,
        remoteClientId: String = ""
    ): RemoteStatus =
      RemoteStatus(
        negotiatedCapability,
        status.networkId,
        resolvedChainWeight,
        status.latestBlockHash,
        status.genesisHash,
        supportsSnap,
        capabilities,
        latestBlock = Some(status.latestBlock),
        remoteClientId = remoteClientId
      )

  case class PeerInfo(
      remoteStatus: RemoteStatus,
      chainWeight: ChainWeight,
      forkAccepted: Boolean,
      maxBlockNumber: BigInt,
      bestBlockHash: ByteString
  ) extends HandshakeResult:

    def withForkAccepted(forkAccepted: Boolean): PeerInfo = copy(forkAccepted = forkAccepted)

    def withBestBlockData(maxBlockNumber: BigInt, bestBlockHash: ByteString): PeerInfo =
      copy(maxBlockNumber = maxBlockNumber, bestBlockHash = bestBlockHash)

    def withChainWeight(weight: ChainWeight): PeerInfo =
      copy(chainWeight = weight)

    /** Checks if this peer is at genesis block (bestHash == genesisHash). */
    def isAtGenesis: Boolean = remoteStatus.bestHash == remoteStatus.genesisHash

    override def toString: String =
      s"PeerInfo {" +
        s" chainWeight: $chainWeight," +
        s" forkAccepted: $forkAccepted," +
        s" maxBlockNumber: $maxBlockNumber," +
        s" bestBlockHash: ${ByteStringUtils.hash2string(bestBlockHash)}," +
        s" handshakeStatus: $remoteStatus" +
        s" }"

  object PeerInfo:
    def apply(remoteStatus: RemoteStatus, forkAccepted: Boolean): PeerInfo =
      val initialMaxBlock: BigInt =
        if remoteStatus.capability == com.chipprbots.ethereum.network.p2p.messages.Capability.ETH69 then
          remoteStatus.latestBlock.getOrElse(BigInt(0))
        else BigInt(0)
      PeerInfo(
        remoteStatus,
        remoteStatus.chainWeight,
        forkAccepted,
        initialMaxBlock,
        remoteStatus.bestHash
      )

    def withForkAccepted(remoteStatus: RemoteStatus): PeerInfo =
      PeerInfo(remoteStatus, forkAccepted = true)

    def withNotForkAccepted(remoteStatus: RemoteStatus): PeerInfo =
      PeerInfo(remoteStatus, forkAccepted = false)

  private[network] case class PeerWithInfo(peer: Peer, peerInfo: PeerInfo)

  case object GetHandshakedPeers

  /** Sent by `SNAPSyncController` whenever it ingests a new CL forkchoice head. */
  case class UpdateClHead(blockNumber: BigInt)

  /** How often to send each handshaked peer a one-shot `GetBlockHeaders(bestHash, 1)` to refresh
    * `PeerInfo.maxBlockNumber`.
    */
  private[network] val BestBlockRefreshInterval: FiniteDuration = 5.minutes

  /** Window after which we re-probe an ETH/69 peer even though it has already had a `BlockRangeUpdate` opportunity. */
  private[network] val BlockSignalStaleAfter: FiniteDuration = 150.seconds

  /** Lagging-peer eviction parameters. */
  private[network] val LaggingPeerCheckInterval: FiniteDuration = 2.minutes
  private[network] val LaggingPeerEvictAfter: FiniteDuration = 10.minutes
  private[network] val LaggingPeerLagThreshold: BigInt = BigInt(4096)
  private[network] val LaggingPeerMaxEvictionsPerCycle: Int = 5

  /** Below this floor of handshaked peers, skip eviction. */
  private[network] val LaggingPeerMinPoolFloor: Int = 2

  /** IP-blacklist applied to a peer that just failed the 10-minute lagging-peer hysteresis. */
  private[network] val LaggingPeerBlacklistDuration: FiniteDuration = 30.minutes

  /** Delay before applying the override blacklist. */
  private[network] val LaggingPeerBlacklistOverrideDelay: FiniteDuration = 5.seconds

  /** Consecutive `RefreshPeerBestBlocksTick` probes with no `maxBlockNumber` advancement needed to classify an ETH69
    * peer as static (archive/non-mining). Static peers are exempt from the monotonic chain-weight guard so a Tier3
    * overestimate at handshake can be corrected downward.
    */
  private[network] val StaticPeerProbeThreshold: Int = 3

  case class HandshakedPeers(peers: Map[Peer, PeerInfo])

  case class PeerInfoRequest(peerId: PeerId)

  case class PeerInfoResponse(peerInfo: Option[PeerInfo])

  case class SendMessage(message: MessageSerializable, peerId: PeerId)

  /** Register the SNAPSyncController actor for message routing */
  case class RegisterSnapSyncController(
      snapSyncController: typed.ActorRef[com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.Command]
  )

  /** Register SyncController as the recipient for TD-PROXY-GAP calibration data. */
  case class RegisterChainWeightCalibrationTarget(
      target: typed.ActorRef[com.chipprbots.ethereum.blockchain.sync.SyncProtocol.CalibrateChainWeightFromPeer]
  )

  /** Unconditional timed calibration: sent by SyncController 30s after startRegularSync. */
  case object CalibrateChainWeightNow
