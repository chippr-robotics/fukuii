package com.chipprbots.ethereum.blockchain.sync.snap.controller

import org.apache.pekko.actor.typed.ActorRef as TypedActorRef
import org.apache.pekko.util.ByteString

import scala.collection.mutable
import scala.concurrent.duration.*
import scala.util.Try

import com.chipprbots.ethereum.blockchain.sync.PeerListHelper
import com.chipprbots.ethereum.blockchain.sync.PeerListSupportNg
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.*
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.utils.Hex

private[snap] trait PeerPoolApi:
  def peersToDownloadFrom: Map[com.chipprbots.ethereum.network.PeerId, PeerListSupportNg.PeerWithInfo]
  def calibratePivotTD(pivotBlockNumber: BigInt): Option[BigInt]

private[snap] trait SnapPeerPoolState:
  def peerListHelper: PeerListHelper
  def handshakedPeersAdapter: TypedActorRef[NetworkPeerManagerActor.HandshakedPeers]
  var bestEth68PeerForCalibration: Option[(BigInt, BigInt)]
  var snapPeerEvictionStarted: Boolean
  var snapServerPeersSchedulerStarted: Boolean
  def BootstrapCheckKey: String

/** The SNAP peer pool (spec 016 M3, research.md R6 "Peer tracking / eviction"): the handshaked-peer view
  * (`handshakedPeers`, `peersToDownloadFrom`, `snapServingPeers` with its `[SNAP-PEERS]` exclusion log), the peer-list
  * refresh with rate tracking and bootstrap reactivity, the debounced disconnect flush, the handshaked-peer poll,
  * non-SNAP peer eviction, snap-server-peer dialling, the pivot-probe target, and the pivot TD estimate
  * (`calibratePivotTD`). The core keeps the dispatch: `peerEventArms`, the reactivity arm of `bootstrapping` and the
  * peer-tick arms of `commonSyncingArms` call these members.
  */
private[snap] trait SnapPeerPool extends PeerPoolApi:
  self: SnapPeerPoolState & SnapSharedState & SnapControllerEnv & CoordinatorHandles =>

  private[snap] def handshakedPeers: Map[com.chipprbots.ethereum.network.PeerId, PeerListSupportNg.PeerWithInfo] =
    peerListHelper.handshakedPeers
  def peersToDownloadFrom: Map[com.chipprbots.ethereum.network.PeerId, PeerListSupportNg.PeerWithInfo] =
    peerListHelper.peersToDownloadFrom

  // Exclusion visibility for the SNAP peer set (see snapServingPeers). Logged when the excluded set changes (at most
  // every SnapExclusionMinLogIntervalMs) and otherwise once per SnapExclusionLogIntervalMs while non-empty.
  private var lastSnapExclusions: Map[String, String] = Map.empty
  private var lastSnapExclusionLogMs: Long = 0L

  /** The peers handed to the SNAP coordinators: handshaked, not blacklisted, and `servesSnapState`. Also logs which
    * snap-capable peers were left out and why, because "only 3 of 9 snap peers reach the coordinators" was otherwise
    * invisible (Sepolia 2026-10-07).
    */
  private[snap] def snapServingPeers(): List[com.chipprbots.ethereum.network.Peer] =
    // Keyed by stable reasons only, so `changed` does not fire on every head update of an excluded peer.
    val exclusions: Map[String, String] = peerListHelper.handshakedPeers.flatMap { case (peerId, p) =>
      SNAPSyncController
        .snapExclusionReason(p.peerInfo, peerListHelper.blacklistReason(peerId))
        .map(reason => peerId.value -> reason)
    }
    val now = System.currentTimeMillis()
    val changed = exclusions != lastSnapExclusions
    val elapsed = now - lastSnapExclusionLogMs
    if (changed && elapsed >= SNAPSyncController.SnapExclusionMinLogIntervalMs) ||
      (exclusions.nonEmpty && elapsed >= SNAPSyncController.SnapExclusionLogIntervalMs)
    then
      lastSnapExclusions = exclusions
      lastSnapExclusionLogMs = now
      val snapCapable = peerListHelper.handshakedPeers.values.count(_.peerInfo.remoteStatus.supportsSnap)
      val detail =
        if exclusions.isEmpty then "none"
        else
          val maxBlockById = peerListHelper.handshakedPeers.map { case (id, p) =>
            id.value -> p.peerInfo.maxBlockNumber
          }
          exclusions.toList.sorted
            .map { case (id, r) =>
              val atBlock =
                if r == SNAPSyncController.SnapExclusionAtGenesis then
                  maxBlockById.get(id).fold("")(n => s"(maxBlock=$n)")
                else ""
              s"${id.take(8)}:$r$atBlock"
            }
            .mkString(", ")
      ctx.log.info(
        "[SNAP-PEERS] snapCapable={} excluded={} served={} exclusions=[{}]",
        snapCapable,
        exclusions.size,
        snapCapable - exclusions.size,
        detail
      )
    peersToDownloadFrom.collect {
      case (_, peerWithInfo) if SNAPSyncController.servesSnapState(peerWithInfo.peerInfo) => peerWithInfo.peer
    }.toList
  private def getSnapPeerWithHighestBlock: Option[PeerListSupportNg.PeerWithInfo] =
    peerListHelper.getSnapPeerWithHighestBlock

  // Debounce rapid PeerDisconnected bursts — collect peer IDs and flush after a 3 s quiet window.
  // Prevents a burst of TCP failures (e.g. 15 in 33 s) from draining all coordinator task queues
  // simultaneously, which would deplete the peer pool and trigger a cascade pivot refresh.
  private var pendingDisconnectedPeers: Set[String] = Set.empty
  private lazy val DisconnectFlushKey = "disconnect-flush"

  // Eviction-churn guard: if eviction keeps firing without the snap-peer count ever rising, the network simply has no
  // more snap peers to find — continuing to evict non-snap peers every cycle only thrashes discovery slots (the "Too
  // many peers" log spam on ETC). Track consecutive fruitless eviction cycles and back off once they pile up; reset
  // when the snap count actually improves.
  private var fruitlessEvictionCycles: Int = 0
  private var lastEvictionSnapCount: Int = -1
  private lazy val MaxFruitlessEvictionCycles: Int = 5

  // C1: the two former `.orElse` peer-list partials become explicit private helper methods,
  // invoked from each behavior's match arms for WrappedHandshakedPeers / WrappedPeerDisconnected /
  // FlushPeerDisconnects. The handshaked-peers update routes through peerListHelper.handleHandshakedPeers
  // (which maintains its own ethRateTracker); SSC additionally maintains requestTracker.rateTracker, so
  // we diff the peer set around the helper call to add/remove there too.

  /** Like handleHandshakedPeersRateTracking, but also reactively triggers a bootstrap retry when new SNAP-capable peers
    * arrive during the bootstrapping state. Without this, the node waits for the full exponential backoff timer (up to
    * 60s) even though peers are already available. Core-geth starts syncing within 200ms of first peer — we should too.
    */
  private[snap] def handleHandshakedPeersBootstrapReactivity(
      peers: Map[com.chipprbots.ethereum.network.Peer, com.chipprbots.ethereum.network.NetworkPeerManagerActor.PeerInfo]
  ): Unit =
    val hadSnapPeers = handshakedPeers.values.exists(_.peerInfo.remoteStatus.supportsSnap)
    val oldPeerIds = handshakedPeers.keySet
    peerListHelper.handleHandshakedPeers(peers)
    val newPeerIds = handshakedPeers.keySet
    (newPeerIds -- oldPeerIds).foreach { peerId =>
      requestTracker.rateTracker.addPeer(peerId.value)
    }
    (oldPeerIds -- newPeerIds).foreach { peerId =>
      requestTracker.rateTracker.removePeer(peerId.value)
    }
    // If we just gained our first SNAP-capable peer(s), trigger a retry.
    // If any peer already has a known height, retry immediately (heights are ready).
    // Otherwise schedule a short 2s delay for ETH status exchange to complete before
    // the retry fires — avoids committing to genesis when heights are merely uninitialized.
    val hasSnapPeers = handshakedPeers.values.exists(_.peerInfo.remoteStatus.supportsSnap)
    if !hadSnapPeers && hasSnapPeers then
      val anySnapPeerHasHeight = handshakedPeers.values
        .filter(_.peerInfo.remoteStatus.supportsSnap)
        .exists(_.peerInfo.maxBlockNumber > 0)

      if anySnapPeerHasHeight then
        ctx.log.info(
          s"First SNAP-capable peer(s) with known height detected (${handshakedPeers.size} total, " +
            s"${handshakedPeers.values.count(_.peerInfo.remoteStatus.supportsSnap)} snap). " +
            s"Cancelling backoff timer and retrying immediately."
        )
        timers.cancel(BootstrapCheckKey)
        ctx.self ! RetrySnapSyncStart
      else
        ctx.log.info(
          s"First SNAP-capable peer(s) detected (${handshakedPeers.size} total, " +
            s"${handshakedPeers.values.count(_.peerInfo.remoteStatus.supportsSnap)} snap) " +
            s"but all heights unknown. Scheduling retry in 2s for ETH status exchange to complete."
        )
        timers.startSingleTimer(BootstrapCheckKey, RetrySnapSyncStart, 2.seconds)

  /** Update peerListHelper + the requestTracker rate tracker when peers connect/disconnect. Tracks previous peer set to
    * detect additions and removals.
    */
  private[snap] def handleHandshakedPeersRateTracking(
      peers: Map[com.chipprbots.ethereum.network.Peer, com.chipprbots.ethereum.network.NetworkPeerManagerActor.PeerInfo]
  ): Unit =
    val oldPeerIds = handshakedPeers.keySet
    peerListHelper.handleHandshakedPeers(peers) // updates handshakedPeers
    val newPeerIds = handshakedPeers.keySet
    // Add new peers to rate tracker
    (newPeerIds -- oldPeerIds).foreach { peerId =>
      requestTracker.rateTracker.addPeer(peerId.value)
    }
    // Remove departed peers from rate tracker
    (oldPeerIds -- newPeerIds).foreach { peerId =>
      requestTracker.rateTracker.removePeer(peerId.value)
    }

  /** Common PeerDisconnected handling for both peer-list variants: drop the peer, update rate tracking, and debounce a
    * flush of batched PeerUnavailable notifications to the coordinators.
    */
  private[snap] def handlePeerDisconnectedDebounced(peerId: com.chipprbots.ethereum.network.PeerId): Unit =
    peerListHelper.handlePeerDisconnected(peerId) // immediate: keeps handshakedPeers current
    requestTracker.rateTracker.removePeer(peerId.value) // immediate: rate tracking
    pendingDisconnectedPeers += peerId.value
    timers.startSingleTimer(DisconnectFlushKey, FlushPeerDisconnects, 3.seconds)

  private[snap] def flushPeerDisconnects(): Unit =
    val ids = pendingDisconnectedPeers
    pendingDisconnectedPeers = Set.empty
    ids.foreach(broadcastPeerUnavailable)

  private[snap] def pollHandshakedPeers(): Unit =
    networkPeerManager ! com.chipprbots.ethereum.network.NetworkPeerManagerActor.GetHandshakedPeersCmd(
      handshakedPeersAdapter
    )

  /** Evict non-SNAP outgoing peers when SNAP peer count is below threshold.
    *
    * Core-Geth completes full SNAP sync in ~5 minutes because it connects to SNAP-capable peers rapidly. Fukuii's peer
    * slots can fill with non-SNAP peers (ETH-only), leaving no room for SNAP-capable peers to connect. This method
    * actively disconnects the oldest non-SNAP outgoing peers to free connection slots, allowing discovery to fill them
    * with SNAP-capable peers.
    */
  private[snap] def evictNonSnapPeers(): Unit =
    val allPeers = handshakedPeers.values.toSeq
    val snapPeerCount = allPeers.count(_.peerInfo.remoteStatus.supportsSnap)
    val nonSnapOutgoing = allPeers
      .filter(p => !p.peerInfo.remoteStatus.supportsSnap && !p.peer.incomingConnection)
      .sortBy(_.peer.createTimeMillis) // oldest first

    if snapPeerCount >= snapSyncConfig.minSnapPeers || nonSnapOutgoing.isEmpty then
      // Pool reached the target (or there's nothing to evict): reset the churn guard.
      if snapPeerCount >= snapSyncConfig.minSnapPeers then fruitlessEvictionCycles = 0
      lastEvictionSnapCount = snapPeerCount
      ctx.log.debug(
        s"SNAP peer eviction: $snapPeerCount snap peers (need ${snapSyncConfig.minSnapPeers}), " +
          s"${nonSnapOutgoing.size} non-snap outgoing — no eviction needed"
      )
    else
      // Churn guard: if prior evictions never lifted the snap count, the network has no more snap peers to find.
      // Back off instead of thrashing discovery slots every cycle.
      if snapPeerCount > lastEvictionSnapCount then fruitlessEvictionCycles = 0 // progress since last cycle — reset
      else fruitlessEvictionCycles += 1
      lastEvictionSnapCount = snapPeerCount
      if fruitlessEvictionCycles > MaxFruitlessEvictionCycles then
        ctx.log.info(
          s"SNAP peer eviction backing off: $fruitlessEvictionCycles cycles with snap count stuck at $snapPeerCount " +
            s"(need ${snapSyncConfig.minSnapPeers}) — not evicting; the network appears to have no more snap peers"
        )
      else
        val numToEvict = math.min(
          snapSyncConfig.maxEvictionsPerCycle,
          math.min(nonSnapOutgoing.size, snapSyncConfig.minSnapPeers - snapPeerCount)
        )

        if numToEvict > 0 then
          ctx.log.info(
            s"SNAP peer eviction: only $snapPeerCount snap peers (need ${snapSyncConfig.minSnapPeers}), " +
              s"evicting $numToEvict of ${nonSnapOutgoing.size} non-snap outgoing peers to free slots for discovery"
          )
          nonSnapOutgoing.take(numToEvict).foreach { peerWithInfo =>
            ctx.log.info(
              s"Evicting non-SNAP peer ${peerWithInfo.peer.id} (${peerWithInfo.peer.remoteAddress}, " +
                s"cap=${peerWithInfo.peerInfo.remoteStatus.capability})"
            )
            peerWithInfo.peer.ref ! com.chipprbots.ethereum.network.PeerActor.DisconnectPeer(
              com.chipprbots.ethereum.network.p2p.messages.WireProtocol.Disconnect.Reasons.TooManyPeers
            )
          }

  /** Start the periodic SNAP peer eviction task if not already running. */
  private[snap] def startSnapPeerEviction(): Unit =
    if !snapPeerEvictionStarted then
      snapPeerEvictionStarted = true
      timers.startTimerWithFixedDelay(EvictNonSnapPeers, EvictNonSnapPeers, snapSyncConfig.snapPeerEvictionInterval)
      ctx.log.info(
        s"SNAP peer eviction started: checking every ${snapSyncConfig.snapPeerEvictionInterval.toSeconds}s, " +
          s"min ${snapSyncConfig.minSnapPeers} snap peers, max ${snapSyncConfig.maxEvictionsPerCycle} evictions/cycle"
      )

  // Start (or re-use) the snap-server-peers reconnect scheduler.
  // Idempotent: does nothing if already running. 15s initial delay lets inbound connections
  // complete STATUS exchange before we fire an outbound ConnectToPeer (avoids AlreadyConnected races).
  private[snap] def startSnapServerPeersScheduler(): Unit =
    if snapSyncConfig.snapServerPeers.nonEmpty && !snapServerPeersSchedulerStarted then
      snapServerPeersSchedulerStarted = true
      timers.startTimerWithFixedDelay(EnsureSnapServerPeersConnected, EnsureSnapServerPeersConnected, 30.seconds)

  /** Select the best probe target peer for pivot readiness probing.
    *
    * Prefers snap-server-peers (configured local clients — Besu, core-geth) because they have archive state and minimal
    * latency, making them the most reliable probe targets. Falls back to the highest-block SNAP-capable external peer
    * when no snap-server-peer is connected.
    */
  private[snap] def bestSnapProbeTarget() =
    val snapServerNodeIds = snapSyncConfig.snapServerPeers.flatMap { uri =>
      Try(ByteString(Hex.decode(uri.getUserInfo))).toOption
    }.toSet
    val localPeer =
      if snapServerNodeIds.nonEmpty then
        handshakedPeers.values
          .find(p => p.peerInfo.remoteStatus.supportsSnap && p.peer.nodeId.exists(snapServerNodeIds.contains))
          .map(p => (p, "snap-server-peer"))
      else None
    localPeer.orElse(getSnapPeerWithHighestBlock.map(p => (p, "external")))

  // Suppress duplicate ConnectToPeer for snap-server-peers for 60s after a send attempt.
  // Prevents the race where the reconnect timer fires within the 5s peersScanInterval
  // window after STATUS_EXCHANGE completes (peer in ETH handshake but not yet in handshakedPeers).
  private lazy val snapServerPeerLastConnectAttemptMs: mutable.Map[String, Long] = mutable.Map.empty

  /** Reconnect to any configured snap-server-peers that are not currently connected.
    *
    * snap-server-peers are static SNAP-serving nodes (e.g. local Besu with --snapsync-server-enabled) that are the only
    * source of ETC GetTrieNodes responses. They may disconnect after the storage phase. This method ensures
    * reconnection so they are in the peer pool when healing dispatches requests.
    */
  private[snap] def ensureSnapServerPeersConnected(): Unit =
    if snapSyncConfig.snapServerPeers.nonEmpty then
      val connectedNodeIds = handshakedPeers.values.flatMap(_.peer.nodeId).toSet
      val nowMs = System.currentTimeMillis()
      snapSyncConfig.snapServerPeers.foreach { uri =>
        val configuredNodeId = Try(ByteString(Hex.decode(uri.getUserInfo))).toOption
        val host = uri.getHost
        val port = uri.getPort
        val key = s"$host:$port"
        val isConnected = configuredNodeId.exists(connectedNodeIds.contains)
        if isConnected then
          // Peer confirmed in handshakedPeers — clear suppression so we reconnect promptly if it disconnects later
          snapServerPeerLastConnectAttemptMs.remove(key)
        else
          val lastAttemptMs = snapServerPeerLastConnectAttemptMs.getOrElse(key, 0L)
          val suppressUntilMs = lastAttemptMs + 60_000L
          if nowMs >= suppressUntilMs then
            ctx.log.info(s"snap-server-peer $host:$port not connected — reconnecting")
            networkPeerManager ! com.chipprbots.ethereum.network.NetworkPeerManagerActor.ConnectToPeerForwardCmd(uri)
            snapServerPeerLastConnectAttemptMs(key) = nowMs
          else
            ctx.log.debug(
              s"snap-server-peer $host:$port not yet in handshakedPeers — suppressing reconnect for ${(suppressUntilMs - nowMs) / 1000}s (waiting for peersScanInterval)"
            )
      }
  // end if snapSyncConfig.snapServerPeers.nonEmpty

  /** Estimate the cumulative TD at `pivotBlockNumber` using linear interpolation over connected ETH68 peers.
    *
    * ETH68 peers carry their real cumulative TD in the STATUS wire message. ETH69 peers do not (TD was removed from
    * their STATUS). For each ETH68 peer whose reported head is above the pivot, we interpolate: pivotTD ≈ peerTipTD ×
    * (pivotNumber / peerTipNumber) The gap between our pivot and the peer's tip is typically ≤ 128 blocks, giving an
    * error ≤ 0.0005% — negligible compared to the ~1.4-billion× error of the genesis-proxy fallback.
    *
    * Returns None when no qualified ETH68 peers are connected (e.g. very early in startup before any handshakes). The
    * caller falls back to the block-number proxy in that case.
    */
  def calibratePivotTD(pivotBlockNumber: BigInt): Option[BigInt] =
    val genesisBlockTD: BigInt = blockchainReader
      .getChainWeightByHash(blockchainReader.genesisHeader.hash)
      .map(_.totalDifficulty.value)
      .getOrElse(blockchainReader.genesisHeader.difficulty.value)

    // Current peers + best peer seen historically this session (fallback when all ETH68
    // peers have disconnected by the time finalization runs, which is common on long syncs).
    // Both peerBlock > 0 and peerBlock > pivotBlockNumber are ETH68_BOOTSTRAP safeguards:
    //   peerBlock > 0: prevents division-by-zero / direct-peerTD use before probe fires
    //   peerBlock > pivot: ensures interpolation (peerTD * pivot / peerBlock) scales correctly
    val currentPeerCandidates: Seq[(BigInt, BigInt)] = handshakedPeers.values
      .filter { p =>
        p.peerInfo.forkAccepted &&
        !Capability.isEth69Plus(p.peerInfo.remoteStatus.capability) &&
        p.peerInfo.maxBlockNumber > pivotBlockNumber
      }
      .map(p => (p.peerInfo.remoteStatus.chainWeight.totalDifficulty.value, p.peerInfo.maxBlockNumber))
      .toSeq

    val historicalCandidate: Seq[(BigInt, BigInt)] =
      bestEth68PeerForCalibration.filter(_._2 > pivotBlockNumber).toSeq

    val source = if currentPeerCandidates.nonEmpty then "CURRENT_PEER" else "HISTORICAL_PEER"
    val allCandidates = currentPeerCandidates ++ historicalCandidate

    if allCandidates.nonEmpty then
      val histTD = bestEth68PeerForCalibration.map(_._1).getOrElse(BigInt(0))
      val histBlock = bestEth68PeerForCalibration.map(_._2).getOrElse(BigInt(0))
      ctx.log.info(
        s"SNAP_CALIBRATION_STATS: pivot=$pivotBlockNumber currentPeers=${currentPeerCandidates.size} historicalPeer=(td=$histTD block=$histBlock) source=$source"
      )

    allCandidates
      .map { case (peerTD, peerBlock) => peerTD * pivotBlockNumber / peerBlock }
      .filter(_ > genesisBlockTD * BigInt(1000))
      .maxOption
