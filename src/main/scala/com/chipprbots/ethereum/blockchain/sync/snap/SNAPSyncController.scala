package com.chipprbots.ethereum.blockchain.sync.snap

import org.apache.pekko.actor.Scheduler
import org.apache.pekko.actor.typed.ActorRef as TypedActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.PostStop
import org.apache.pekko.actor.typed.SupervisorStrategy
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.TimerScheduler
import org.apache.pekko.util.ByteString

import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*
import scala.util.Try

import com.chipprbots.ethereum.blockchain.sync.Blacklist
import com.chipprbots.ethereum.blockchain.sync.PeerListHelper
import com.chipprbots.ethereum.blockchain.sync.PeerListSupportNg
import com.chipprbots.ethereum.blockchain.sync.SyncController
import com.chipprbots.ethereum.blockchain.sync.SyncProtocol
import com.chipprbots.ethereum.consensus.engine.PoSBlockHeaderValidator
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.BfsQueueStorage
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.FlatSlotStorage
import com.chipprbots.ethereum.db.storage.HealingFrontierStorage
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.db.storage.Namespaces
import com.chipprbots.ethereum.db.storage.PathNodeStorage
import com.chipprbots.ethereum.db.storage.RocksDbBfsQueueStorage
import com.chipprbots.ethereum.db.storage.SnapSyncProgressStorage
import com.chipprbots.ethereum.db.storage.StateStorage
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockBody
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.BlockchainWriter
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.domain.TrieRoot
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.network.p2p.messages.SNAP
import com.chipprbots.ethereum.network.p2p.messages.SNAP.*
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.ByteStringUtils.ByteStringOps
import com.chipprbots.ethereum.utils.Config.SyncConfig
import com.chipprbots.ethereum.utils.Hex

private class SNAPSyncControllerImpl(
    ctx: ActorContext[SNAPSyncController.Command],
    timers: TimerScheduler[SNAPSyncController.Command],
    blockchainReader: BlockchainReader,
    blockchainWriter: BlockchainWriter,
    appStateStorage: AppStateStorage,
    stateStorage: StateStorage,
    evmCodeStorage: EvmCodeStorage,
    flatSlotStorage: FlatSlotStorage,
    networkPeerManager: TypedActorRef[com.chipprbots.ethereum.network.NetworkPeerManagerActor.Command],
    peerEventBus: TypedActorRef[com.chipprbots.ethereum.network.PeerEventBusActor.Command],
    syncConfig: SyncConfig,
    snapSyncConfig: SNAPSyncConfig,
    scheduler: Scheduler,
    blacklist: Blacklist,
    syncController: TypedActorRef[SyncProtocol.SyncControllerReply],
    // Factory for `StateValidator` so unit tests can inject a fake. Production
    // default is a thin `new StateValidator(_)` wrapper; tests can supply a
    // `FakeStateValidator` that returns canned results, delays, or throws.
    validatorFactory: MptStorage => StateValidator
)(implicit ec: ExecutionContext):

  import SNAPSyncController.*
  import SyncPhase.*

  // P11: plain SLF4J logger, safe to call from Future callbacks (off the actor thread).
  // Used ONLY at the two Recovery-streaming Future `.foreach` sites; all on-thread logging uses ctx.log.
  private val asyncLog = org.slf4j.LoggerFactory.getLogger(getClass)

  // ── Timer keys (Behaviors.withTimers; replaces the Classic Cancellable fields) ───────────────
  // Recurring keys reuse the Command case object itself; one-shot keys are string literals.
  private val BootstrapCheckKey = "bootstrap-check"
  private val DisconnectFlushKey = "disconnect-flush"
  private val PivotBootstrapRetryKey = "pivot-bootstrap-retry"
  private val SnapCapabilityCheckKey = "snap-capability-check"
  private val DormantWakeUpKey = "dormant-wakeup"
  private val ScheduledTrieWalkKey = "scheduled-trie-walk"
  private val PollHandshakedPeersKey = "poll-handshaked-peers"
  // Probe timeouts are keyed by the probe's BigInt requestId (C4) — see startPivotProbe.

  // Dedicated dispatcher for the long-running synchronous trie walks inside
  // `validateState()`. See `pekko.conf` for the rationale.
  private val snapValidationEc: ExecutionContext =
    ctx.system.classicSystem.dispatchers.lookup("snap-validation-dispatcher")

  // Typed-compatible peer-list management (Group PLN), replacing the Classic PeerListSupportNg mixin.
  // The handshaked-peers poll (PollHandshakedPeers timer) and the PeerDisconnected bridge live in the
  // behaviors; bestEth68PeerForCalibration tracking is preserved via the onPeerListUpdated override.
  private val peerListHelper: PeerListHelper =
    new PeerListHelper(
      peerEventBus,
      blacklist,
      ctx.messageAdapter[com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent] {
        case com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent.PeerDisconnected(peerId) =>
          WrappedPeerDisconnected(peerId)
        case e => throw new MatchError(s"unexpected PeerEvent from bus: $e")
      },
      org.slf4j.LoggerFactory.getLogger(getClass)
    ):
      override protected def onPeerListUpdated(
          currentPeers: Iterable[PeerListSupportNg.PeerWithInfo]
      ): Unit =
        currentPeers
          .filter { p =>
            p.peerInfo.forkAccepted &&
            !Capability.isEth69Plus(p.peerInfo.remoteStatus.capability) &&
            p.peerInfo.maxBlockNumber > BigInt(0) // Wait for eager probe; peerBlock=0 → ETH68_BOOTSTRAP risk
          }
          .foreach { p =>
            val td = p.peerInfo.remoteStatus.chainWeight.totalDifficulty.value
            val block = p.peerInfo.maxBlockNumber
            if bestEth68PeerForCalibration.forall { case (best, _) => td > best } then
              val prevBestTD = bestEth68PeerForCalibration.map(_._1).getOrElse(BigInt(0))
              bestEth68PeerForCalibration = Some((td, block))
              ctx.log.info(
                "SNAP_CALIBRATION_PEER: new best ETH68 peer td={} block={} prevBestTD={}",
                td,
                block,
                prevBestTD
              )
          }

  private def handshakedPeers: Map[com.chipprbots.ethereum.network.PeerId, PeerListSupportNg.PeerWithInfo] =
    peerListHelper.handshakedPeers
  private def peersToDownloadFrom: Map[com.chipprbots.ethereum.network.PeerId, PeerListSupportNg.PeerWithInfo] =
    peerListHelper.peersToDownloadFrom
  private def getSnapPeerWithHighestBlock: Option[PeerListSupportNg.PeerWithInfo] =
    peerListHelper.getSnapPeerWithHighestBlock
  private val bigIntReverseOrdering: Ordering[BigInt] = Ordering[BigInt].reverse

  // SNAP download progress storage (namespace 'p'). Shares the same RocksDB DataSource as
  // AppStateStorage but uses a dedicated namespace so progress survives crash-restart without
  // interfering with other state. Replaces AppStateStorage's plain-text SnapSyncProgress entry
  // (namespace 's', account-only). Storage cursor persistence is new.
  private val snapProgressStorage = new SnapSyncProgressStorage(appStateStorage.dataSource)

  ctx.log.info("SNAPSyncController started with shared blacklist (cross-mode penalty propagation enabled)")

  // Writable MptStorage, lazily created when pivot block number is known.
  // Uses getBackingStorage(pivotBlockNumber) to ensure nodes are tagged with the
  // correct block number for proper reference counting in pruning modes.
  private var mptStorage: Option[MptStorage] = None

  // PathScheme: create PathNodeStorage backed by the same RocksDB data source as flat storage.
  // None for HashScheme (default/ETC). Shared across coordinator restarts (data source is long-lived).
  private val pathNodeStorageOpt: Option[PathNodeStorage] =
    if snapSyncConfig.storageScheme == StorageScheme.Path then Some(new PathNodeStorage(flatSlotStorage.dataSource))
    else None

  private def getOrCreateMptStorage(pivotBlockNumber: BigInt): MptStorage =
    mptStorage.getOrElse {
      val storage = stateStorage.getBackingStorage(pivotBlockNumber)
      mptStorage = Some(storage)
      ctx.log.info(s"Created writable MptStorage for pivot block $pivotBlockNumber")
      storage
    }

  // Actor-based coordinators (OQ-6 Option A: Typed refs, spawned via ctx.spawn).
  private var accountRangeCoordinator
      : Option[org.apache.pekko.actor.typed.ActorRef[actors.AccountRangeCoordinator.Command]] =
    None
  private var bytecodeCoordinator: Option[org.apache.pekko.actor.typed.ActorRef[actors.ByteCodeCoordinator.Command]] =
    None
  private var storageRangeCoordinator
      : Option[org.apache.pekko.actor.typed.ActorRef[actors.StorageRangeCoordinator.Command]] =
    None
  private var trieNodeHealingCoordinator
      : Option[org.apache.pekko.actor.typed.ActorRef[actors.TrieNodeHealingCoordinator.Command]] =
    None
  // ChainDownloader is Behavior[Command] (S6 narrowed); typed ref enables type-safe sends.
  private var chainDownloader: Option[org.apache.pekko.actor.typed.ActorRef[ChainDownloader.Command]] = None
  private var chainDownloadComplete: Boolean = false

  // Monotonic counter appended to coordinator actor names so restarts don't collide
  // with still-stopping actors from the previous cycle.
  private var coordinatorGeneration: Long = 0

  // Buffered CL-driven pivot hint. Populated whenever a `CLPivotHint` message arrives
  // from `SyncController`. Consumed by `startSnapSync()` to skip TD-based pivot selection
  // on post-merge chains. Only meaningful when `isPoSChain == true`. Closes #1207.
  private var clPivotHint: Option[CLPivotHint] = None

  // Minimum pivot block enforced when re-entering SNAP from a RegularSyncStuck escape.
  // Prevents re-selecting the same pivot that caused the regular-sync stall.
  private var minPivotHint: BigInt = BigInt(0)
  private var clHintArrivedAtMs: Option[Long] = None

  // Captured once at construction. ETC mainnet has TTD=None and never goes down the
  // CL-driven path; Sepolia/mainnet have TTD set and switch off TD-based pivot entirely.
  private val isPoSChain: Boolean =
    com.chipprbots.ethereum.utils.Config.blockchains.blockchainConfig.terminalTotalDifficulty.isDefined

  private val requestTracker = new SNAPRequestTracker()(scheduler)

  private var currentPhase: SyncPhase = Idle
  private var pivotBlock: Option[BigInt] = None
  private var stateRoot: Option[TrieRoot] = None

  // Preserved account range progress across SNAP sync restarts (core-geth parity).
  // Maps range `last` hash → current `next` position for ALL ranges (not just completed ones).
  // Content-addressed MPT storage survives pivot changes (accounts keyed by keccak256 hash),
  // so already-traversed keyspace doesn't need re-downloading if the pivot
  // hasn't drifted too far (within MaxPreservedPivotDistance blocks).
  private var preservedRangeProgress: Map[ByteString, ByteString] = Map.empty
  private var preservedAtPivotBlock: Option[BigInt] = None
  // Resume saved account-range cursors across this much pivot drift before falling back to a
  // full re-walk. Raised from 256 (~55 min ETC) to 50_000 (~1 week ETC) on 2026-06-01: the 256
  // cap forced a full re-walk of ~16.8M accounts after a 404-block drift, even though FULLY-
  // COMPLETE ranges are content-addressed and valid across ANY drift and the healing walk from
  // the new pivot root reconciles the changed-account delta. The cap is now only a perf heuristic
  // (very large drift => large healing delta where a cold re-walk may be comparable), NOT a
  // correctness boundary. Correctness comes from: (a) only FULLY-COMPLETE ranges are resumed —
  // partial ranges re-download from start because the StackTrie cannot resume mid-range (see
  // AccountRangeCoordinator); and (b) `resumedStaleCursors` forces the healing walk even under
  // deferred-merkleization.
  private val MaxPreservedPivotDistance: BigInt = 50_000

  // Set true whenever account-range cursors were resumed from a prior session (resumeProgress
  // non-empty). Forces StateHealing to run at completion (overriding the deferred-merkleization
  // skip-healing fast path) so a delta downloaded against a drifted root is never handed off
  // unwalked. This is the single load-bearing anti-corruption guard for cursor resume.
  private var resumedStaleCursors: Boolean = false

  // Geth-aligned: all 3 coordinators run concurrently from first account response.
  // accountsComplete is set when AccountRangeSyncComplete arrives and NoMore sentinels are sent.
  private var accountsComplete: Boolean = false

  // Concurrent phase completion tracking (all 3 coordinators run in parallel)
  private var bytecodePhaseComplete: Boolean = false
  private var storagePhaseComplete: Boolean = false
  private var storagePhaseForceCompleted: Boolean = false

  private val progressMonitor = new SyncProgressMonitor(scheduler)

  // Failure tracking — critical failures trigger dormant retry with exponential backoff.
  private var criticalFailureCount: Int = 0
  private var accountsAtLastCriticalFailure: Long = 0L
  private val AccountProgressResetThreshold: Long = 100_000L

  // Dormant retry: when critical failures exhaust, preserve all RocksDB data and wait
  // for peers to re-index their snapshots with exponential backoff (3min–20min).
  private var dormantRetryCount: Int = 0
  private val DormantBaseDelay: FiniteDuration = 3.minutes
  private val DormantMaxDelay: FiniteDuration = 20.minutes

  // Retry counter for validation failures to prevent infinite loops
  private var validationRetryCount: Int = 0
  private val MaxValidationRetries = 3
  private val ValidationRetryDelay = 500.millis

  // Async validation state. `validationInProgress` is the re-entrance guard
  // for the trie-walk Future. `validationGeneration` is bumped at every
  // (a) fresh validation spawn, (b) `restartSnapSync`, and (c) post-pivot
  // mutation in `completePivotRefreshWithStateRoot`, so a long-running Future's
  // result that arrives after such a transition is dropped on the floor rather
  // than being applied against the wrong root. Both result-message handlers
  // and the scheduled `ValidationRetry` carry the generation they were
  // spawned/scheduled at and only honour matching values.
  private var validationInProgress: Boolean = false
  private var validationGeneration: Long = 0L

  // #1188: when the round-2 healing trie walk returns 0 missing, the entire
  // account+storage trie has just been DFS-walked end-to-end. Capture the root
  // it was clean against so `validateState()` can short-circuit the redundant
  // `validateAccountTrie + validateAllStorageTries` passes (which together can
  // exceed the time of the SNAP download itself on populated states).
  // Belt-and-suspenders: only honoured when the captured root *equals* the
  // current `stateRoot`, so any pivot refresh / restart naturally invalidates
  // the signal and full validation runs.
  private var healingValidatedRoot: Option[TrieRoot] = None

  // Running total of unique codeHashes streamed in via `IncrementalContractData`. Used to set
  // `progressMonitor.estimatedTotalBytecodes` so the SNAP-sync dashboard's
  // `100 * downloaded / clamp_min(estimated_total, 1)` formula has a real denominator. Without
  // this, `estimated_total` stays at 0, the formula divides by 1 (the clamp floor), and the
  // dashboard reads `5,239,500%` for a normal in-flight bytecodes count.
  private var bytecodesEstimatedTotal: Long = 0L

  // Retry counter for bootstrap-to-SNAP transition (exponential backoff: 2s→60s cap, max 10 retries)
  private var bootstrapRetryCount: Int = 0
  private val BootstrapRetryBaseDelay = 2.seconds
  private val BootstrapRetryMaxDelay = 60.seconds
  private val MaxBootstrapRetries = 10

  // Pivot restart guard (prevents noisy rapid restarts if peer head fluctuates)
  private var lastPivotRestartMs: Long = 0L
  private val MinPivotRestartInterval: FiniteDuration = 30.seconds

  // Proactive pivot rolling: keep pivot within core-geth's 128-block snapshot window.
  // ETC network is predominantly core-geth peers; once the pivot ages beyond 128 blocks,
  // all external peers respond with accounts=[], proof=[] and only local Besu can serve.
  // Rolling proactively at 100 blocks preserves all downloaded state (unlike go-ethereum).
  private var lastProactivePivotBlock: Option[BigInt] = None
  private val SnapServeWindowBlocks: BigInt = BigInt(100)

  // Roll target margin: a proactive/reactive roll picks networkBest - max(pivotBlockOffset,
  // SnapServeWindowMargin) so the new root lands INSIDE peers' indexed snapshot window.
  // Rolling to the live tip (pivot-block-offset=0) probes a root core-geth peers haven't
  // indexed yet (their snapshot lags head by ~128 blocks) → readiness probe returns
  // "not indexed" forever → the pivot freezes (observed 2026-06-01: ETC stalled 70+ min
  // at 16.8M accounts). Margin ≈ half the serve window keeps the new pivot servable with
  // ~50 blocks of runway before it ages past the 100-block roll trigger.
  private val SnapServeWindowMargin: BigInt = BigInt(50)

  // Pivot readiness probe: before committing a proactive pivot roll, we probe one peer with
  // the candidate root. Only if the peer's snapshot is indexed (returns ≥1 account) do we
  // dispatch PivotRefreshed to coordinators. Otherwise we defer and retry every 30s, letting
  // coordinators keep downloading on the old root. This eliminates the ~8-minute dead window.
  private var pivotProbeRequestId: Option[BigInt] = None
  private var proactiveRollNeedsProbe: Boolean = false // flag: stagnation check → completePivotRefresh
  private var pendingProbeCommit: Option[(BigInt, BlockHeader, String)] = None // deferred commit args
  private var lastProbeAttemptMs: Long = 0L
  private val ProbeCooldownMs: Long = 30_000L // match DownloadStagnationCheckInterval
  private var probeAttemptCount: Int = 0
  private val MaxProbeAttempts: Int = 5 // 5 × 30s = 150s max deferral before forced roll
  private val ZeroPeerStagnationMs: Long = 3 * 60 * 1000L // 3 min zero-peer short-circuit

  // Consecutive pivot refresh counter: when all peers are repeatedly stateless after
  // pivot refreshes, it strongly indicates no peer has a snapshot database. Each
  // PivotStateUnservable increments this; any successful account download resets it.
  // After MaxConsecutivePivotRefreshes, we record a critical failure and enter dormant
  // retry mode. Set to 10 (was 3) to tolerate ETC mainnet's 1-5 SNAP peers needing
  // 5+ minutes to re-index after serve-window expiry.
  private var consecutivePivotRefreshes: Int = 0
  private val MaxConsecutivePivotRefreshes = 10

  // Pivot blocks confirmed unservable in this session. When a refreshPivotInPlace resolves
  // to the same block as one in this set, fast-track consecutivePivotRefreshes to the
  // threshold so dormant mode activates after the next coordinator escalation (2–3 cycles
  // instead of 10). Entries are never cleared — a failed pivot block should not be retried
  // within the same session.
  private val failedPivotBlocks: mutable.Set[BigInt] = mutable.Set.empty

  // Pending pivot refresh: when refreshPivotInPlace() needs a header from a peer,
  // it requests a bootstrap and stores the pending pivot here. When BootstrapComplete
  // arrives in the syncing state, the refresh is completed.
  private var pendingPivotRefresh: Option[(BigInt, String)] = None

  // Debounce rapid PeerDisconnected bursts — collect peer IDs and flush after a 3 s quiet window.
  // Prevents a burst of TCP failures (e.g. 15 in 33 s) from draining all coordinator task queues
  // simultaneously, which would deplete the peer pool and trigger a cascade pivot refresh.
  private var pendingDisconnectedPeers: Set[String] = Set.empty

  private var storageStagnationRefreshAttempted: Boolean = false
  // Prevent sending ForceCompleteStorage more than once per coordinator lifecycle.
  // SNAPSyncController can queue 10+ StorageCoordinatorProgress responses before the first
  // StorageRangeSyncForceCompleted reply arrives, causing storagePhaseComplete to still be false
  // for all of them. Without this guard, each one sends a duplicate ForceCompleteStorage.
  private var forceCompleteStorageSent: Boolean = false
  private var trieWalkInProgress: Boolean = false
  private var healingRoundCount: Int = 0
  // spec 004 (Decoupled Heal Serve-Root) T011: serve-root refresh bookkeeping. The healing coordinator fetches
  // missing nodes against an advancing SERVE root while its completeness walk stays pinned to the walk root. We
  // ask the parent for a newest-servable root (networkBest − RecentRootMarginBlocks) on the healing tick when the
  // current serve root has aged > HealingServeRootMarginBlocks behind the network head. A single in-flight latch
  // (the bootstrap is a ~1s peer round-trip — never per-block) and a block number of the last pushed serve root.
  private var healingServeRootRequestInFlight: Boolean = false
  private var lastHealingServeRootBlock: Option[BigInt] = None
  // The serve root is considered stale when it is more than this many blocks behind the network head. Matches
  // SyncController.RecentRootMarginBlocks (64) so the refreshed root lands comfortably inside peers' serve window.
  private val HealingServeRootMarginBlocks: BigInt = BigInt(64)
  // spec 009 T009/T014 (Moving-Root Delta Heal — BOUNDED re-peg last-resort). Under `movingRootDeltaHeal` the heal
  // re-pegs the single heal root via refreshPivotInPlace (from maybeRequestHealingServeRoot). If a re-peg attempt
  // during StateHealing finds NO suitable served root (refreshPivotInPlace's newPivotOpt.isEmpty branch), this counter
  // increments; once it reaches the budget the controller takes the SAME fail-SAFE lazy-heal handoff (completeSnapSync
  // → on-demand GetTrieNodes during block execution, anchor guard STILL enforced) rather than looping the 30s
  // RetryPivotRefresh forever (the heal-churn #1371 fought). Fail-SAFE (never force-marks-done / weakens any gate),
  // never fail-OPEN. Reset on any successful re-peg (completePivotRefreshWithStateRoot) and on entering healing.
  private var healRepegNoRootAttempts: Int = 0
  private val MaxHealRepegNoRootAttempts: Int = 10 // 10 × 30s ≈ 5 min of no servable root before the lazy handoff
  // Suppress duplicate ConnectToPeer for snap-server-peers for 60s after a send attempt.
  // Prevents the race where the reconnect timer fires within the 5s peersScanInterval
  // window after STATUS_EXCHANGE completes (peer in ETH handshake but not yet in handshakedPeers).
  private val snapServerPeerLastConnectAttemptMs: mutable.Map[String, Long] = mutable.Map.empty
  // Tracks whether the SNAP peer eviction recurring timer is active (replaces the snapPeerEvictionTask
  // Cancellable). startSnapPeerEviction() is idempotent — it only starts the timer once per session.
  private var snapPeerEvictionStarted: Boolean = false
  // Tracks whether the snap-server-peers reconnect timer is active (replaces the snapServerPeersScheduler
  // Cancellable presence check). Reset on stopSnapOnlySchedules so a later phase can restart it.
  private var snapServerPeersSchedulerStarted: Boolean = false
  // Eviction-churn guard: if eviction keeps firing without the snap-peer count ever rising, the network simply has no
  // more snap peers to find — continuing to evict non-snap peers every cycle only thrashes discovery slots (the "Too
  // many peers" log spam on ETC). Track consecutive fruitless eviction cycles and back off once they pile up; reset
  // when the snap count actually improves.
  private var fruitlessEvictionCycles: Int = 0
  private var lastEvictionSnapCount: Int = -1
  private val MaxFruitlessEvictionCycles: Int = 5

  // Best ETH68 peer (TD, maxBlockNumber) seen at any point during this SNAP session.
  // Preserved across peer disconnects so calibratePivotTD can use it at finalization even
  // if those peers have long since disconnected. Only updated when maxBlockNumber > 0
  // (eager probe has fired) — guards against the ETH68_BOOTSTRAP inflation pattern where
  // peerTD used directly without knowing peerBlock.
  private var bestEth68PeerForCalibration: Option[(BigInt, BigInt)] = None

  // C1: the two former `.orElse` peer-list partials become explicit private helper methods,
  // invoked from each behavior's match arms for WrappedHandshakedPeers / WrappedPeerDisconnected /
  // FlushPeerDisconnects. The handshaked-peers update routes through peerListHelper.handleHandshakedPeers
  // (which maintains its own ethRateTracker); SSC additionally maintains requestTracker.rateTracker, so
  // we diff the peer set around the helper call to add/remove there too.

  /** Like handleHandshakedPeersRateTracking, but also reactively triggers a bootstrap retry when new SNAP-capable peers
    * arrive during the bootstrapping state. Without this, the node waits for the full exponential backoff timer (up to
    * 60s) even though peers are already available. Core-geth starts syncing within 200ms of first peer — we should too.
    */
  private def handleHandshakedPeersBootstrapReactivity(
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
  private def handleHandshakedPeersRateTracking(
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
  private def handlePeerDisconnectedDebounced(peerId: com.chipprbots.ethereum.network.PeerId): Unit =
    peerListHelper.handlePeerDisconnected(peerId) // immediate: keeps handshakedPeers current
    requestTracker.rateTracker.removePeer(peerId.value) // immediate: rate tracking
    pendingDisconnectedPeers += peerId.value
    timers.startSingleTimer(DisconnectFlushKey, FlushPeerDisconnects, 3.seconds)

  private def flushPeerDisconnects(): Unit =
    val ids = pendingDisconnectedPeers
    pendingDisconnectedPeers = Set.empty
    ids.foreach { id =>
      accountRangeCoordinator.foreach(_ ! actors.AccountRangeCoordinator.PeerUnavailable(id))
      storageRangeCoordinator.foreach(_ ! actors.StorageRangeCoordinator.StoragePeerUnavailable(id))
      bytecodeCoordinator.foreach(_ ! actors.ByteCodeCoordinator.ByteCodePeerUnavailable(id))
      trieNodeHealingCoordinator.foreach(_ ! actors.TrieNodeHealingCoordinator.HealingPeerUnavailable(id))
    }

  // Storage stagnation watchdog: if storage stops advancing while tasks remain, repivot/restart.
  // This addresses the common case where peers no longer serve the chosen pivot/state window.
  // Threshold must be generous enough to allow large chains to complete within the SNAP serve window.
  // Unified stagnation watchdog thresholds — one check interval, phase-specific thresholds
  private val DownloadStagnationCheckInterval: FiniteDuration = 30.seconds
  private val StorageStagnationThreshold: FiniteDuration =
    10.minutes // CFG-2: 20→10min; second stall force-completes after 30s anyway
  private val AccountStagnationThreshold: FiniteDuration = snapSyncConfig.accountStagnationTimeout
  private var lastStorageProgressMs: Long = System.currentTimeMillis()
  private var lastBytecodeProgressMs: Long = System.currentTimeMillis()
  private var lastBytecodeProgressCount: Long = 0L
  private var lastAccountProgressMs: Long = System.currentTimeMillis()
  private var lastAccountTasksCompleted: Int = 0
  private var lastAccountsDownloaded: Long = 0
  // Tracks storage contract completion (0.0–1.0) from push-based ProgressStorageContracts messages.
  // Used to suppress proactive pivot rolls when storage is nearly done (Bug 2 guard).
  private var storageContractProgressPct: Double = 0.0

  /** Replaces preStart(): runs the initialization side effects, starts the handshaked-peers poll, and returns the
    * initial `idle` behavior. Called from `apply()`.
    */
  def start(): Behavior[Command] =
    checkStorageSchemeMismatch()
    ctx.log.info("SNAP Sync Controller initialized")
    ctx.log.info(
      s"SNAPSyncConfig: accountConcurrency=${snapSyncConfig.accountConcurrency}, " +
        s"storageBatchSize=${snapSyncConfig.storageBatchSize}, " +
        s"pivotBlockOffset=${snapSyncConfig.pivotBlockOffset}, " +
        s"storageScheme=${snapSyncConfig.storageScheme}"
    )
    progressMonitor.startPeriodicLogging()
    // OQ-3: poll NPMA for the handshaked-peer set (replaces PeerListSupportNg's HandshakedPeers broadcast).
    timers.startTimerWithFixedDelay(PollHandshakedPeersKey, PollHandshakedPeers, 5.seconds)
    ctx.self ! PollHandshakedPeers // immediate first poll
    idle()

  /** OQ-3: ask NetworkPeerManagerActor for the current handshaked peers; the reply (`HandshakedPeers`) is bridged back
    * into the Command ADT via a Typed message adapter.
    */
  private val handshakedPeersAdapter: org.apache.pekko.actor.typed.ActorRef[
    com.chipprbots.ethereum.network.NetworkPeerManagerActor.HandshakedPeers
  ] =
    ctx
      .messageAdapter[com.chipprbots.ethereum.network.NetworkPeerManagerActor.HandshakedPeers](m =>
        WrappedHandshakedPeers(m.peers)
      )

  private def pollHandshakedPeers(): Unit =
    networkPeerManager ! com.chipprbots.ethereum.network.NetworkPeerManagerActor.GetHandshakedPeersCmd(
      handshakedPeersAdapter
    )

  /** OQ-2: reply target for ChainDownloader's `Done`. ChainDownloader (Behavior[Command], S6 narrowed) sends Done to
    * this typed adapter, which bridges into SSC's sealed mailbox as ChainDownloaderDone. Progress is polled separately
    * via GetProgress — this adapter handles Done only.
    */
  private val chainDownloaderReplyAdapter: TypedActorRef[ChainDownloader.Done.type] =
    ctx.messageAdapter[ChainDownloader.Done.type](_ => ChainDownloaderDone)

  private def onStop(): Unit =
    stopSnapOnlySchedules()
    // dormantWakeUp is now a timer — auto-cancelled on stop.
    ctx.log.info("SNAP Sync Controller stopped")

  /** Guard: fail fast if the DB was written with PathScheme but config says HashScheme (or vice versa).
    *
    * Path-scheme data lives in [[Namespaces.StateTriePathNamespace]] (namespace 't'). We check for the root node at
    * empty nibble path — HP([]) = 0x20. If it exists, a PathScheme SNAP sync was performed on this datadir. A
    * HashScheme config at that point would silently ignore all path-keyed nodes, so we throw immediately.
    *
    * The reverse (HashScheme data + PathScheme config) is safe: PathScheme simply ignores the existing hash-keyed nodes
    * and starts fresh. No silent data loss — just wasted disk space.
    */
  private def checkStorageSchemeMismatch(): Unit =
    val emptyHp = com.chipprbots.ethereum.mpt.HexPrefix.encode(Array.empty[Byte], isLeaf = false)
    val hasPathRoot = flatSlotStorage.dataSource
      .getOptimized(Namespaces.StateTriePathNamespace, emptyHp)
      .isDefined
    if hasPathRoot && snapSyncConfig.storageScheme == StorageScheme.Hash then
      throw new IllegalStateException(
        "Storage scheme mismatch: DB contains path-scheme account trie data (root at empty path) " +
          "but config has storage-scheme = hash. " +
          "Either set storage-scheme = path in snap-sync config, or wipe the datadir and resync."
      )

  /** Cancel every SNAP-only scheduled timer. Idempotent — `timers.cancel` tolerates unknown keys. Called both at the
    * lifecycle transition into `completedWithBackfill` (so eviction/tickers don't keep running while regular sync owns
    * the peer pool) and from the PostStop signal handler.
    */
  private def stopSnapOnlySchedules(): Unit =
    timers.cancel(RequestAccountRanges)
    timers.cancel(RequestByteCodes)
    timers.cancel(RequestStorageRanges)
    timers.cancel(CheckDownloadStagnation)
    timers.cancel(RequestTrieNodeHealing)
    timers.cancel(BootstrapCheckKey)
    timers.cancel(PivotBootstrapRetryKey)
    timers.cancel(SnapCapabilityCheckKey)
    timers.cancel(EvictNonSnapPeers)
    timers.cancel(TuneRateTracker)
    timers.cancel(EnsureSnapServerPeersConnected)
    snapServerPeersSchedulerStarted = false
    snapPeerEvictionStarted = false
    progressMonitor.stopPeriodicLogging()

  /** Stop the SNAP state-sync child coordinators (account/bytecode/storage/healing) and clear their references. They do
    * not self-stop on completion, so when `SNAPSyncController` is kept alive past `finalizeSnapSync()` for background
    * chain backfill (#1162), failing to stop them retains completed task buffers, worker actors, and rate trackers for
    * the entire backfill window. The previous `PoisonPill` to the parent used to clean these up implicitly.
    *
    * `chainDownloader` is NOT stopped here — it keeps running in `completedWithBackfill`.
    */
  private def stopStateSyncChildren(): Unit =
    accountRangeCoordinator.foreach(ctx.stop)
    accountRangeCoordinator = None
    bytecodeCoordinator.foreach(ctx.stop)
    bytecodeCoordinator = None
    storageRangeCoordinator.foreach(ctx.stop)
    storageRangeCoordinator = None
    forceCompleteStorageSent = false
    trieNodeHealingCoordinator.foreach(ctx.stop)
    trieNodeHealingCoordinator = None

  // ── Behaviors ────────────────────────────────────────────────────────────────────────────────
  // Each behavior is `Behaviors.receiveMessage` over the sealed Command, with a PostStop signal for
  // cleanup. `context.become(X)` becomes `break(X())`; sender() becomes replyTo; the two peer-list
  // partials are inlined as explicit arms (C1); aroundReceive's stagnation dispatch is inlined into
  // `syncing` (C2).

  def idle(): Behavior[Command] =
    Behaviors
      .receiveMessage[Command] {
        case PollHandshakedPeers =>
          pollHandshakedPeers(); Behaviors.same
        case WrappedHandshakedPeers(peers) =>
          handleHandshakedPeersRateTracking(peers); Behaviors.same
        case WrappedPeerDisconnected(peerId) =>
          handlePeerDisconnectedDebounced(peerId); Behaviors.same
        case FlushPeerDisconnects =>
          flushPeerDisconnects(); Behaviors.same

        case MinPivotBlock(minBlock) =>
          ctx.log.info("Received MinPivotBlock hint: pivot must be >= {}", minBlock)
          minPivotHint = minBlock
          Behaviors.same

        case Start =>
          ctx.log.info("Starting SNAP sync...")
          startSnapSync()

        case GetStatus(replyTo) =>
          replyTo ! SyncProtocol.Status.NotSyncing
          Behaviors.same

        case hint: CLPivotHint =>
          handleCLPivotHint(hint, isStarting = false)
          Behaviors.same

        case GetProgress(replyTo) =>
          replyTo ! progressMonitor.currentProgress
          Behaviors.same

        // ── Unexpected bootstrap signals (should not arrive before sync starts) ────────
        case _: BootstrapComplete =>
          ctx.log.warn("Unexpected BootstrapComplete in idle — dropped")
          Behaviors.same
        case msg: PivotBootstrapFailed =>
          ctx.log.warn("Unexpected PivotBootstrapFailed({}) in idle — dropped", msg.reason)
          Behaviors.same

        // ── Stale SNAP network responses (only valid while syncing) ───────────────────
        case _: AccountRangeResponse  => ctx.log.debug("Dropping stale AccountRangeResponse in idle"); Behaviors.same
        case _: ByteCodesResponse     => ctx.log.debug("Dropping stale ByteCodesResponse in idle"); Behaviors.same
        case _: StorageRangesResponse => ctx.log.debug("Dropping stale StorageRangesResponse in idle"); Behaviors.same
        case _: TrieNodesResponse     => ctx.log.debug("Dropping stale TrieNodesResponse in idle"); Behaviors.same

        // ── Stale chain-downloader messages ───────────────────────────────────────────
        case _: ChainDownloaderProgress =>
          ctx.log.debug("Dropping stale ChainDownloaderProgress in idle"); Behaviors.same
        case ChainDownloaderDone => ctx.log.debug("Dropping stale ChainDownloaderDone in idle"); Behaviors.same

        // ── Syncing-state retry timers (stale after sync stops) ───────────────────────
        case RetrySnapSyncStart       => ctx.log.debug("Dropping stale RetrySnapSyncStart in idle"); Behaviors.same
        case RetryPivotRefresh        => ctx.log.debug("Dropping stale RetryPivotRefresh in idle"); Behaviors.same
        case _: RetryBootstrapAtBlock => ctx.log.debug("Dropping stale RetryBootstrapAtBlock in idle"); Behaviors.same
        case CheckSnapCapability      => ctx.log.debug("Dropping stale CheckSnapCapability in idle"); Behaviors.same
        case TuneRateTracker          => ctx.log.debug("Dropping stale TuneRateTracker in idle"); Behaviors.same
        case EvictNonSnapPeers        => ctx.log.debug("Dropping stale EvictNonSnapPeers in idle"); Behaviors.same
        case _: PivotProbeTimeout     => ctx.log.debug("Dropping stale PivotProbeTimeout in idle"); Behaviors.same
        case DormantWakeUp            => ctx.log.debug("Dropping stale DormantWakeUp in idle"); Behaviors.same
        case _: DelayedRestart        => ctx.log.debug("Dropping stale DelayedRestart in idle"); Behaviors.same

        // ── Coordinator progress (stale after sync stops) ────────────────────────────
        case _: AccountRangeProgressCmd =>
          ctx.log.debug("Dropping stale AccountRangeProgressCmd in idle"); Behaviors.same
        case CheckDownloadStagnation => ctx.log.debug("Dropping stale CheckDownloadStagnation in idle"); Behaviors.same
        case _: AccountCoordinatorProgress =>
          ctx.log.debug("Dropping stale AccountCoordinatorProgress in idle"); Behaviors.same
        case _: StorageCoordinatorProgress =>
          ctx.log.debug("Dropping stale StorageCoordinatorProgress in idle"); Behaviors.same
        case _: ByteCodeCoordinatorProgress =>
          ctx.log.debug("Dropping stale ByteCodeCoordinatorProgress in idle"); Behaviors.same

        // ── Periodic request ticks (silent — frequent, expected stale after sync stops)
        case RequestAccountRanges           => Behaviors.same
        case RequestByteCodes               => Behaviors.same
        case RequestStorageRanges           => Behaviors.same
        case RequestTrieNodeHealing         => Behaviors.same
        case EnsureSnapServerPeersConnected => Behaviors.same

        // ── Stale trie-walk results ───────────────────────────────────────────────────
        case _: TrieWalkResult   => ctx.log.debug("Dropping stale TrieWalkResult in idle"); Behaviors.same
        case _: TrieWalkBatch    => ctx.log.debug("Dropping stale TrieWalkBatch in idle"); Behaviors.same
        case _: TrieWalkComplete => ctx.log.debug("Dropping stale TrieWalkComplete in idle"); Behaviors.same
        case _: TrieWalkFailed   => ctx.log.debug("Dropping stale TrieWalkFailed in idle"); Behaviors.same
        // The TNHC seed-guard (#1371) can emit HealingRootUnservable; if it lands after the controller has left
        // StateHealing it must be dropped, not MatchError-crash the idle behavior (the StateHealing handler is guarded).
        case _: HealingRootUnservable => ctx.log.debug("Dropping stale HealingRootUnservable in idle"); Behaviors.same
        case _: ValidateAccountTrieResult =>
          ctx.log.debug("Dropping stale ValidateAccountTrieResult in idle"); Behaviors.same
        case _: ValidateStorageTriesResult =>
          ctx.log.debug("Dropping stale ValidateStorageTriesResult in idle"); Behaviors.same
        case _: ValidationRetry => ctx.log.debug("Dropping stale ValidationRetry in idle"); Behaviors.same
        case ScheduledTrieWalk  => ctx.log.debug("Dropping stale ScheduledTrieWalk in idle"); Behaviors.same

        // ── Unexpected sync-completion signals ────────────────────────────────────────
        case AccountRangeSyncComplete =>
          ctx.log.warn("Unexpected AccountRangeSyncComplete in idle — dropped")
          Behaviors.same
        case ByteCodeSyncComplete =>
          ctx.log.warn("Unexpected ByteCodeSyncComplete in idle — dropped")
          Behaviors.same
        case StorageRangeSyncComplete =>
          ctx.log.warn("Unexpected StorageRangeSyncComplete in idle — dropped")
          Behaviors.same
        case StorageRangeSyncForceCompleted =>
          ctx.log.warn("Unexpected StorageRangeSyncForceCompleted in idle — dropped")
          Behaviors.same
        case _: IncrementalContractData =>
          ctx.log.debug("Dropping stale IncrementalContractData in idle"); Behaviors.same
        case StateHealingComplete =>
          ctx.log.warn("Unexpected StateHealingComplete in idle — dropped")
          Behaviors.same
        case HealingAllPeersStateless =>
          ctx.log.debug("Dropping stale HealingAllPeersStateless in idle"); Behaviors.same
        case StateValidationComplete =>
          ctx.log.warn("Unexpected StateValidationComplete in idle — dropped")
          Behaviors.same

        // ── Stale healing/serve-root messages ─────────────────────────────────────────
        case _: HealingServeRoot     => ctx.log.debug("Dropping stale HealingServeRoot in idle"); Behaviors.same
        case _: PivotStateUnservable => ctx.log.debug("Dropping stale PivotStateUnservable in idle"); Behaviors.same

        // ── Progress deltas (silent — frequent, stale after sync stops) ───────────────
        case _: ProgressAccountsSynced      => Behaviors.same
        case ProgressAccountsFinalizingTrie => Behaviors.same
        case ProgressAccountsTrieFinalized  => Behaviors.same
        case _: AccountTrieFinalized => ctx.log.debug("Dropping stale AccountTrieFinalized in idle"); Behaviors.same
        case _: AccountTrieFinalizationFailed =>
          ctx.log.warn("Unexpected AccountTrieFinalizationFailed in idle — dropped")
          Behaviors.same
        case _: ProgressBytecodesDownloaded => Behaviors.same
        case _: ProgressStorageSlotsSynced  => Behaviors.same
        case _: ProgressNodesHealed         => Behaviors.same
        case _: ProgressAccountEstimate     => Behaviors.same
        case _: ProgressStorageContracts    => Behaviors.same

        // ── Stale backpressure / stagnation signals ───────────────────────────────────
        case _: StorageBackpressureChanged =>
          ctx.log.debug("Dropping stale StorageBackpressureChanged in idle"); Behaviors.same
        case _: ByteCodeBackpressureChanged =>
          ctx.log.debug("Dropping stale ByteCodeBackpressureChanged in idle"); Behaviors.same
        case _: HealingStagnated => ctx.log.debug("Dropping stale HealingStagnated in idle"); Behaviors.same
      }
      .receiveSignal { case (_, PostStop) =>
        onStop(); Behaviors.same
      }

  def syncing(): Behavior[Command] = Behaviors
    .receiveMessage[Command] {
      // C1: inlined rate-tracking peer-list arms
      case WrappedHandshakedPeers(peers) =>
        handleHandshakedPeersRateTracking(peers); Behaviors.same
      case WrappedPeerDisconnected(peerId) =>
        handlePeerDisconnectedDebounced(peerId); Behaviors.same
      case FlushPeerDisconnects =>
        flushPeerDisconnects(); Behaviors.same
      case PollHandshakedPeers =>
        pollHandshakedPeers(); Behaviors.same
      case hint: CLPivotHint =>
        // CL advanced its head while we're mid-snap. Update the buffer; the proactive
        // pivot-rolling watcher (geth-style: re-pivot when `head > pivot + 2*offset - 8`)
        // consumes this on its next tick. We deliberately don't restart the pipeline here
        // — content-addressed trie nodes are ~99.9% valid across pivot changes.
        handleCLPivotHint(hint, isStarting = false)
        Behaviors.same

      // Periodic rate tracker tuning (geth msgrate alignment)
      case TuneRateTracker =>
        requestTracker.rateTracker.tune()
        Behaviors.same

      // Periodic SNAP peer eviction: disconnect non-SNAP outgoing peers to free slots
      case EvictNonSnapPeers =>
        evictNonSnapPeers()
        Behaviors.same

      // Delayed restart after critical failure backoff
      case DelayedRestart(reason) =>
        if currentPhase == AccountRangeSync || currentPhase == ByteCodeAndStorageSync then restartSnapSync(reason)
        else Behaviors.same

      // Snap capability grace period check: if still no snap/1 peers, go dormant and retry on a fresh pivot
      case CheckSnapCapability =>
        val snapPeerCount = peersToDownloadFrom.count { case (_, p) =>
          p.peerInfo.remoteStatus.supportsSnap
        }
        if snapPeerCount > 0 then
          val effectiveConcurrency = snapSyncConfig.accountConcurrency.max(1)
          ctx.log.info(
            s"Found $snapPeerCount snap-capable peer(s) during grace period, starting account range sync (concurrency=$effectiveConcurrency, peers=$snapPeerCount)"
          )
          stateRoot.foreach(launchAccountRangeWorkers(_, effectiveConcurrency))
          Behaviors.same
        else enterDormantMode("no snap-capable peers after the capability grace period")

      // Periodic request triggers
      case RequestAccountRanges =>
        requestAccountRanges()
        Behaviors.same

      case RequestByteCodes =>
        requestByteCodes()
        Behaviors.same

      case RequestStorageRanges =>
        requestStorageRanges()
        Behaviors.same

      case RequestTrieNodeHealing =>
        // The 1-s scheduler keeps emitting these even after we transition out of
        // healing. Once validation is async, these can fire concurrently with
        // the validation Future and would feed a coordinator that's supposed to
        // be quiescent. The phase gate is defensive; we also cancel the
        // scheduler explicitly in `validateState()` callers.
        if currentPhase == StateHealing then
          requestTrieNodeHealing()
          // spec 004 T011/U1: piggyback the serve-root staleness check on the existing 1-s healing tick (the only
          // dispatch hook already present during healing). This does NOT issue a request per block — it only fires
          // the ~1s parent bootstrap when the serve root is > HealingServeRootMarginBlocks behind the network head
          // and no request is already in flight.
          maybeRequestHealingServeRoot()
        Behaviors.same

      // spec 004 T011/T012: parent's reply to RequestHealingServeRoot. Push the newest-servable root to the healing
      // coordinator as HealingServeRootRefresh — NOT HealingPivotRefreshed (which would mutate the walk root). U2: a
      // None/zero reply means no servable root could be fetched; KEEP the current serve root (do not push), and clear
      // the in-flight latch so a later tick can retry.
      case SNAPSyncController.HealingServeRoot(blockNumber, rootOpt) =>
        healingServeRootRequestInFlight = false
        // spec 009 T014/FR-010: under moving-root delta heal the stale-move trigger re-pegs the single heal root via
        // refreshPivotInPlace → HealingPivotRefreshed (H-S6), NOT a serve-root push, so this reply is no longer
        // solicited on the flag path. A late HealingServeRoot (e.g. an in-flight RequestHealingServeRoot from before the
        // flag engaged) must NOT push a HealingServeRootRefresh — under single-root heal the serve root IS the walk root
        // and a serve-root-only move would be meaningless. Flag OFF: byte-identical to the spec-004 push below.
        if snapSyncConfig.movingRootDeltaHeal then
          ctx.log.debug(
            "[HEAL] late HealingServeRoot reply ignored — moving-root delta heal re-pegs via HealingPivotRefreshed"
          )
        else
          rootOpt match
            case Some(root) if root.value.nonEmpty =>
              lastHealingServeRootBlock = Some(blockNumber)
              ctx.log.info(
                s"[HEAL-SERVE-ROOT] Pushing newest-servable serve root ${root.value.take(4).toHex} (block $blockNumber) " +
                  s"to healing coordinator (walk root unchanged)."
              )
              trieNodeHealingCoordinator.foreach(
                _ ! actors.TrieNodeHealingCoordinator.HealingServeRootRefresh(root.value)
              )
            case _ =>
              ctx.log.info(
                "[HEAL-SERVE-ROOT] Parent could not fetch a newest-servable root (no peers / bootstrap failed). " +
                  "Keeping the current serve root; will retry on a later healing tick."
              )
        Behaviors.same

      case EnsureSnapServerPeersConnected =>
        ensureSnapServerPeersConnected()
        Behaviors.same

      // Handle SNAP protocol responses (wrapped in Command ADT by NetworkPeerManagerActor)
      case AccountRangeResponse(msg) =>
        // Intercept pivot readiness probe responses before forwarding to coordinator.
        pivotProbeRequestId match
          case Some(probeId) if msg.requestId == probeId =>
            pivotProbeRequestId = None
            if msg.accounts.nonEmpty then
              pendingProbeCommit.foreach { case (block, header, commitReason) =>
                pendingProbeCommit = None
                val attemptsNote = if probeAttemptCount > 0 then s" after $probeAttemptCount prior deferral(s)" else ""
                ctx.log.info(
                  s"[PIVOT-PROBE] Snapshot ready at root ${header.stateRoot.value.take(4).toHex} block $block$attemptsNote — committing roll"
                )
                probeAttemptCount = 0
                completePivotRefreshWithStateRoot(block, header, commitReason)
              }
            else
              probeAttemptCount += 1
              val commitArgs = pendingProbeCommit
              pendingProbeCommit = None
              if probeAttemptCount >= MaxProbeAttempts then
                commitArgs.foreach { case (block, header, commitReason) =>
                  val pivotAge = currentNetworkBestFromSnapPeers().map(_ - block).getOrElse(BigInt(-1))
                  ctx.log.warn(
                    s"[PIVOT-PROBE] Max deferral reached ($MaxProbeAttempts/$MaxProbeAttempts attempts) — " +
                      s"forcing roll at root ${header.stateRoot.value.take(4).toHex} block $block (pivotAge≈$pivotAge). " +
                      s"Snapshot readiness unconfirmed — brief post-roll window may occur."
                  )
                  probeAttemptCount = 0
                  completePivotRefreshWithStateRoot(block, header, s"$commitReason (forced — max probe attempts)")
                }
              else
                ctx.log.info(
                  s"[PIVOT-PROBE] Snapshot not indexed yet " +
                    s"(attempt $probeAttemptCount/$MaxProbeAttempts) — " +
                    s"deferring roll, coordinators unaffected (retry in ${ProbeCooldownMs / 1000}s)"
                )
          // lastProbeAttemptMs stays set; next stagnation tick after cooldown will re-probe
          case _ =>
            ctx.log.debug(s"Received AccountRange response: requestId=${msg.requestId}, accounts=${msg.accounts.size}")
            // Forward to the account range coordinator (it owns the workers).
            accountRangeCoordinator.foreach(_ ! actors.AccountRangeCoordinator.AccountRangeResponseMsg(msg))
        Behaviors.same

      case ByteCodesResponse(msg) =>
        ctx.log.debug(s"Received ByteCodes response: requestId=${msg.requestId}, codes=${msg.codes.size}")

        // Forward to the bytecode coordinator (it owns the workers).
        bytecodeCoordinator.foreach(_ ! actors.ByteCodeCoordinator.ByteCodesResponseMsg(msg))
        Behaviors.same

      case StorageRangesResponse(msg) =>
        ctx.log.debug(s"Received StorageRanges response: requestId=${msg.requestId}, slots=${msg.slots.size}")

        // Forward to the storage range coordinator (it owns the workers).
        storageRangeCoordinator.foreach(_ ! actors.StorageRangeCoordinator.StorageRangesResponseMsg(msg))
        Behaviors.same

      case TrieNodesResponse(msg) =>
        ctx.log.debug(s"Received TrieNodes response: requestId=${msg.requestId}, nodes=${msg.nodes.size}")

        // Forward to the trie node healing coordinator (it owns the workers).
        // Don't forward during validation — the healing coordinator has already
        // signalled complete and any responses still arriving from peers are
        // late chatter that must not race with the validation walk.
        if currentPhase != StateValidation then
          trieNodeHealingCoordinator.foreach(_ ! actors.TrieNodeHealingCoordinator.TrieNodesResponseMsg(msg))
        Behaviors.same

      case ProgressAccountsSynced(count) =>
        progressMonitor.incrementAccountsSynced(count)
        // Real account downloads mean SNAP is making progress — reset the pivot refresh counter.
        if count > 0 then
          consecutivePivotRefreshes = 0
          if criticalFailureCount > 0 then
            val currentTotal = progressMonitor.currentProgress.accountsSynced
            if currentTotal - accountsAtLastCriticalFailure >= AccountProgressResetThreshold then
              ctx.log.info(
                s"Resetting criticalFailureCount ($criticalFailureCount -> 0): " +
                  s"${currentTotal - accountsAtLastCriticalFailure} accounts since last critical failure"
              )
              criticalFailureCount = 0
              accountsAtLastCriticalFailure = currentTotal
              dormantRetryCount = 0
        Behaviors.same

      case AccountRangeProgressCmd(progress) =>
        preservedRangeProgress = progress
        if preservedAtPivotBlock.isEmpty then preservedAtPivotBlock = pivotBlock
        val completedCount = progress.count { case (last, next) =>
          // A range is "complete" when next >= last (entire keyspace traversed)
          next == last || BigInt(1, next.toArray.padTo(32, 0.toByte)) >= BigInt(1, last.toArray.padTo(32, 0.toByte))
        }
        ctx.log.info(
          s"Preserved account range progress: ${progress.size} ranges ($completedCount fully complete)"
        )
        // Persist to disk for crash recovery. writeAccountCursors preserves any storage cursors
        // written concurrently by StorageRangeCoordinator (read-modify-write on the same JSON blob).
        val effectivePivot = preservedAtPivotBlock.getOrElse(BigInt(0))
        stateRoot.foreach { sr =>
          snapProgressStorage.writeAccountCursors(
            sr.value,
            effectivePivot.toLong,
            progress.map { case (k, v) => k.toHex -> v.toHex }
          )
        }
        Behaviors.same

      case ProgressAccountsFinalizingTrie =>
        progressMonitor.setFinalizingTrie(true)
        // AccountRange can legitimately produce empty segments (count=0) while still progressing.
        // Treat any progress update as a liveness signal.
        lastAccountProgressMs = System.currentTimeMillis()
        Behaviors.same

      case AccountTrieFinalized(finalizedRoot) =>
        // Persist the finalized trie root hash so we can recover after restart.
        // With pivot refreshes, the finalized root differs from the pivot block header's stateRoot.
        // On startup, SyncController substitutes this root into the pivot block header.
        ctx.log.info(
          "Persisting finalized account trie root: {}",
          finalizedRoot.take(8).toArray.map("%02x".format(_)).mkString
        )
        appStateStorage.putSnapSyncFinalizedRoot(finalizedRoot).commit()
        Behaviors.same

      case ProgressAccountsTrieFinalized =>
        progressMonitor.setFinalizingTrie(false)
        ctx.log.info("Account range trie finalization complete")
        Behaviors.same

      case AccountTrieFinalizationFailed(error) =>
        // Root mismatch: the trie we built doesn't hash to the pivot's state root.
        // This means peers returned empty/wrong data (e.g., snapshot not ready).
        // Restart with a fresh pivot rather than entering healing with corrupt state.
        ctx.log.error(s"Account trie finalization failed ($error) — restarting SNAP sync with fresh pivot")
        progressMonitor.setFinalizingTrie(false)
        restartSnapSync(s"account trie finalization failed: $error")

      case ProgressBytecodesDownloaded(count) =>
        progressMonitor.incrementBytecodesDownloaded(count)
        lastBytecodeProgressMs = System.currentTimeMillis()
        Behaviors.same

      case ProgressStorageSlotsSynced(count) =>
        progressMonitor.incrementStorageSlotsSynced(count)
        // Only reset stagnation timer on meaningful progress (>10 slots).
        // Trickle progress from stale in-flight responses or pivot refresh cycles
        // shouldn't keep the stagnation timer from eventually firing.
        if count > 10 then
          lastStorageProgressMs = System.currentTimeMillis()
          if count > 100 then storageStagnationRefreshAttempted = false
        Behaviors.same

      case ProgressNodesHealed(count) =>
        progressMonitor.incrementNodesHealed(count)
        Behaviors.same

      case ProgressAccountEstimate(estimatedTotal) =>
        progressMonitor.updateEstimates(accounts = estimatedTotal)
        Behaviors.same

      case ProgressStorageContracts(completed, total) =>
        if total > 0 then storageContractProgressPct = completed.toDouble / total
        progressMonitor.updateStorageContracts(completed, total)
        if completed > 0 && total > 0 then
          val currentSlots = progressMonitor.getStorageSlotsSynced
          val estimatedTotalSlots = (currentSlots.toDouble / completed * total).toLong
          progressMonitor.updateEstimates(slots = estimatedTotalSlots)
        Behaviors.same

      case StorageBackpressureChanged(paused) =>
        // Forward the storage coordinator's pause/resume signal to the account coordinator so it
        // stops dispatching new account-range requests when storage is over its high-water mark.
        accountRangeCoordinator.foreach(_ ! actors.AccountRangeCoordinator.StorageQueuePressure(paused))
        Behaviors.same

      case ByteCodeBackpressureChanged(paused) =>
        // Same pattern for bytecodes — account-range completions enqueue bytecode tasks (in addition
        // to storage tasks), so the account coordinator must also pause when the bytecode queue is
        // over its high-water mark.
        accountRangeCoordinator.foreach(_ ! actors.AccountRangeCoordinator.ByteCodeQueuePressure(paused))
        Behaviors.same

      case PivotStateUnservable(rootHash, reason, emptyResponses) =>
        // When peers can no longer serve the current state root, refresh the pivot in-place
        // instead of restarting. This preserves downloaded trie data (content-addressed nodes
        // are ~99.9% valid across pivot changes) and avoids the download-stall-restart loop.
        var earlyBehavior: Option[Behavior[Command]] = None
        val now = System.currentTimeMillis()
        if now - lastPivotRestartMs < MinPivotRestartInterval.toMillis then
          // Within the debounce window a pivot refresh is already in flight (or just produced a
          // same-root no-op). Do NOT swallow the escalation — that left the coordinators' stateless
          // set full so they re-escalated forever and the node wedged on a dead pivot. Re-arm them
          // against the current root so stateless tracking clears and dispatch resumes.
          // INVARIANT: do not increment consecutivePivotRefreshes and do not call any destructive
          // path here — only the consecutivePivotRefreshes>=Max branch may reach restart/dormant.
          ctx.log.info(
            s"Debouncing PivotStateUnservable (refresh in flight, phase=$currentPhase, " +
              s"emptyResponses=$emptyResponses, reason=$reason) — re-arming coordinators"
          )
          stateRoot.foreach { root =>
            accountRangeCoordinator.foreach(_ ! actors.AccountRangeCoordinator.PivotRefreshed(root.value))
            storageRangeCoordinator.foreach(_ ! actors.StorageRangeCoordinator.StoragePivotRefreshed(root.value))
          }
        else if currentPhase == AccountRangeSync || currentPhase == ByteCodeAndStorageSync then
          lastPivotRestartMs = now
          // Record the current pivot block as failed so completePivotRefreshWithStateRoot can
          // detect when refreshPivotInPlace resolves back to the same block.
          pivotBlock.foreach(failedPivotBlocks.add)
          consecutivePivotRefreshes += 1
          ctx.log.info(
            s"Consecutive stateless pivot refreshes: $consecutivePivotRefreshes/$MaxConsecutivePivotRefreshes"
          )
          if consecutivePivotRefreshes >= MaxConsecutivePivotRefreshes then
            if accountsComplete then
              // Geth/Besu aligned: NEVER restart after accounts complete.
              // Accounts are the most expensive phase (~85.9M on ETC). Bytecodes are
              // content-addressed and don't depend on state root. Storage tasks can be
              // refreshed in-place. Refresh pivot for storage coordinator only.
              ctx.log.warn(
                s"$consecutivePivotRefreshes consecutive unservable pivots during bytecode/storage. " +
                  "Refreshing in-place (preserving accounts)."
              )
              consecutivePivotRefreshes = 0 // Reset — accounts completing IS progress
              refreshPivotInPlace(reason)
            else
              // Accounts still in progress. Enter dormant retry mode — preserving all RocksDB
              // data and waiting for peers with exponential backoff.
              ctx.log.warn(
                s"$consecutivePivotRefreshes consecutive pivot refreshes without progress. " +
                  "Peers likely lack snapshot databases."
              )
              if recordCriticalFailure(s"$consecutivePivotRefreshes consecutive stateless pivot refreshes") then
                enterDormantMode(
                  s"critical failure threshold reached: $consecutivePivotRefreshes consecutive stateless pivot refreshes"
                )
              else
                val restartDelay = math.min(30 * criticalFailureCount, 120).seconds
                if restartDelay > Duration.Zero then
                  ctx.log.info(
                    s"Delaying SNAP restart by ${restartDelay.toSeconds}s " +
                      s"(criticalFailureCount=$criticalFailureCount)"
                  )
                  timers.startSingleTimer(
                    "delayed-restart",
                    DelayedRestart(
                      s"consecutive stateless pivots ($consecutivePivotRefreshes): $reason"
                    ),
                    restartDelay
                  )
                else
                  earlyBehavior =
                    Some(restartSnapSync(s"consecutive stateless pivots ($consecutivePivotRefreshes): $reason"))
          else refreshPivotInPlace(reason)
        else ctx.log.info(s"Ignoring PivotStateUnservable in phase=$currentPhase (reason=$reason)")
        earlyBehavior.getOrElse(Behaviors.same)

      // Handle pivot header bootstrap completion during active sync.
      // This arrives when refreshPivotInPlace() requested a header from a peer because
      // it wasn't available locally. The coordinator is still alive with all its state.
      case BootstrapComplete(pivotHeaderOpt) if pendingPivotRefresh.isDefined =>
        val (pendingPivot, reason) = pendingPivotRefresh.get
        pendingPivotRefresh = None
        pivotHeaderOpt match
          case Some(header) =>
            ctx.log.info(s"Pivot header bootstrap complete for block ${header.number} (requested $pendingPivot)")
            completePivotRefreshWithStateRoot(pendingPivot, header, reason)
            Behaviors.same
          case None =>
            ctx.log.warn(
              s"Pivot header bootstrap for block $pendingPivot returned no header. Falling back to full restart."
            )
            restartSnapSync(s"pivot refresh bootstrap returned no header for $pendingPivot: $reason")

      // Handle pivot header bootstrap failure. Instead of recalculating from network-best (which advances
      // the pivot to a block peers don't have yet), backtrack by pivotBlockOffset blocks (Besu pattern).
      case PivotBootstrapFailed(reason) if pendingPivotRefresh.isDefined =>
        val (pendingPivot, originalReason) = pendingPivotRefresh.get
        pendingPivotRefresh = None
        timers.cancel(PivotBootstrapRetryKey)
        val backtrackedPivot = pendingPivot - snapSyncConfig.pivotBlockOffset
        if backtrackedPivot > 0 then
          ctx.log.warn(
            s"Pivot header bootstrap failed for block $pendingPivot (reason: $reason, original: $originalReason). " +
              s"Backtracking pivot to $backtrackedPivot (Besu pattern: decrement by ${snapSyncConfig.pivotBlockOffset})."
          )
          timers.startSingleTimer(PivotBootstrapRetryKey, RetryBootstrapAtBlock(backtrackedPivot), 5.seconds)
        else
          ctx.log.warn(
            s"Pivot header bootstrap failed for block $pendingPivot (reason: $reason). " +
              s"Backtracked pivot below 0 — falling back to network-best recalculation after 60s."
          )
          timers.startSingleTimer(PivotBootstrapRetryKey, RetryPivotRefresh, 60.seconds)
        Behaviors.same

      case PivotProbeTimeout(requestId) =>
        if pivotProbeRequestId.contains(requestId) then
          probeAttemptCount += 1
          val commitArgs = pendingProbeCommit
          pivotProbeRequestId = None
          pendingProbeCommit = None
          if probeAttemptCount >= MaxProbeAttempts then
            commitArgs.foreach { case (block, header, commitReason) =>
              ctx.log.warn(
                s"[PIVOT-PROBE] Max deferral reached after timeout ($MaxProbeAttempts/$MaxProbeAttempts) — " +
                  s"forcing roll at root ${header.stateRoot.value.take(4).toHex} block $block. " +
                  s"Snapshot readiness unconfirmed — brief post-roll window may occur."
              )
              probeAttemptCount = 0
              completePivotRefreshWithStateRoot(block, header, s"$commitReason (forced — max probe attempts)")
            }
          else
            ctx.log.info(
              s"[PIVOT-PROBE] Probe timed out (attempt $probeAttemptCount/$MaxProbeAttempts) — " +
                s"no response from probe peer, deferring roll (retry in ${ProbeCooldownMs / 1000}s)"
            )
            // lastProbeAttemptMs stays set — ProbeCooldownMs enforces a 30s minimum before next retry
        Behaviors.same

      case RetryPivotRefresh =>
        if currentPhase == AccountRangeSync || currentPhase == ByteCodeAndStorageSync || currentPhase == StateHealing
        then
          ctx.log.info("Retrying pivot refresh after bootstrap failure...")
          refreshPivotInPlace("retry after bootstrap failure")
        else ctx.log.info(s"Skipping pivot refresh retry — phase=$currentPhase no longer needs it")
        Behaviors.same

      case RetryBootstrapAtBlock(blockNumber) =>
        if currentPhase == AccountRangeSync || currentPhase == ByteCodeAndStorageSync || currentPhase == StateHealing
        then
          ctx.log.info(s"Retrying bootstrap at backtracked block $blockNumber...")
          blockchainReader.getBlockHeaderByNumber(blockNumber) match
            case Some(header) =>
              completePivotRefreshWithStateRoot(blockNumber, header, "backtracked pivot (local header)")
            case None =>
              chainDownloader.foreach(_ ! ChainDownloader.Pause)
              pendingPivotRefresh = Some((blockNumber, "backtracked pivot"))
              syncController ! StartRegularSyncBootstrap(blockNumber)
              lastAccountProgressMs = System.currentTimeMillis()
        else ctx.log.info(s"Skipping backtracked bootstrap — phase=$currentPhase no longer needs it")
        Behaviors.same

      // Geth-aligned: bytecodes and storage are dispatched inline from each account batch.
      // IncrementalContractData arrives from AccountRangeCoordinator after every identifyContractAccounts() call.
      case IncrementalContractData(codeHashes, storageTasks) =>
        if codeHashes.nonEmpty then
          bytecodeCoordinator.foreach(_ ! actors.ByteCodeCoordinator.AddByteCodeTasks(codeHashes))
          // Accumulate the running total of unique codeHashes for the dashboard. `codeHashes` is
          // already deduplicated upstream (Bloom filter in AccountRangeCoordinator), so summing
          // batch sizes gives the unique total.
          bytecodesEstimatedTotal += codeHashes.size
          progressMonitor.updateEstimates(bytecodes = bytecodesEstimatedTotal)
        if storageTasks.nonEmpty then
          storageRangeCoordinator.foreach(_ ! actors.StorageRangeCoordinator.AddStorageTasks(storageTasks))
        Behaviors.same

      case AccountRangeSyncComplete =>
        if accountsComplete then ctx.log.info("Ignoring duplicate AccountRangeSyncComplete")
        else
          accountsComplete = true
          progressMonitor.startPhase(AccountRangeSync)
          ctx.log.info("Account range sync complete. Signaling NoMore to bytecode/storage coordinators.")

          // Persist accounts-complete flag for crash recovery (Step 7)
          appStateStorage.putSnapSyncAccountsComplete(true).commit()

          // Persist temp file paths for crash recovery (non-blocking ask — if this fails,
          // recovery will do a full restart which is acceptable since account trie data survives)
          accountRangeCoordinator.foreach { coordinator =>
            import org.apache.pekko.actor.typed.scaladsl.AskPattern.*
            import org.apache.pekko.util.Timeout
            given timeout: Timeout = Timeout(5.seconds)
            given typedScheduler: org.apache.pekko.actor.typed.Scheduler = ctx.system.scheduler
            coordinator
              .ask[actors.AccountRangeCoordinator.StorageFileInfoResponse](replyTo =>
                actors.AccountRangeCoordinator.AccountGetStorageFileInfo(replyTo)
              )
              .foreach { info =>
                appStateStorage.putSnapSyncStorageFilePath(info.filePath.toString).commit()
                ctx.log.info(s"Persisted storage file path for recovery: ${info.filePath} (${info.count} entries)")
              }
            coordinator
              .ask[actors.AccountRangeCoordinator.CodeHashesFileInfoResponse](replyTo =>
                actors.AccountRangeCoordinator.AccountGetCodeHashesFileInfo(replyTo)
              )
              .foreach { info =>
                appStateStorage.putSnapSyncCodeHashesPath(info.filePath.toString).commit()
                ctx.log.info(s"Persisted codeHashes file path for recovery: ${info.filePath} (${info.count} entries)")
              }
          }

          // Clear persisted range progress — account phase is done, no need to resume it
          stateRoot.foreach(root => snapProgressStorage.clearProgress(root.value))
          preservedRangeProgress = Map.empty
          preservedAtPivotBlock = None

          // Reset consecutive pivot refreshes — account completion IS progress
          consecutivePivotRefreshes = 0

          // Signal that no more work will arrive (sentinel pattern — prevents premature completion)
          bytecodeCoordinator.foreach(_ ! actors.ByteCodeCoordinator.NoMoreByteCodeTasks)
          storageRangeCoordinator.foreach(_ ! actors.StorageRangeCoordinator.NoMoreStorageTasks)

          // Transition to ByteCodeAndStorageSync for status reporting and stagnation checks
          currentPhase = ByteCodeAndStorageSync
          progressMonitor.startPhase(ByteCodeAndStorageSync)

          // Redistribute per-peer budget: accounts done, give storage+bytecode more bandwidth.
          // Global budget remains 5 per peer: storage=3, bytecode=2.
          storageRangeCoordinator.foreach(_ ! actors.StorageRangeCoordinator.UpdateMaxInFlightPerPeer(3))
          // ByteCode budget 8 per peer: with 2 local ETC-capable peers (Besu + core-geth) that gives
          // 16 concurrent requests vs 4 at budget=2. External ETH-mainnet peers respond with empty quickly
          // and cool down; local peers handle the full load at <1ms RTT.
          bytecodeCoordinator.foreach(_ ! actors.ByteCodeCoordinator.UpdateMaxInFlightPerPeer(8))
          // Clear accumulated peer cooldowns and seed initial dispatch — without this, peers on
          // 2-min backoff from AccountRange skip ByteCode dispatch indefinitely (Layer 2 stall).
          bytecodeCoordinator.foreach(_ ! actors.ByteCodeCoordinator.ByteCodePivotRefreshed)
          requestByteCodes()

          // Cancel account-phase schedulers (no longer relevant)
          timers.cancel(CheckDownloadStagnation)
          timers.cancel(RequestAccountRanges)

          // Start storage + bytecode stagnation watchdogs now that accounts are done
          lastStorageProgressMs = System.currentTimeMillis()
          lastBytecodeProgressMs = System.currentTimeMillis()
          lastBytecodeProgressCount = 0L
          scheduleStagnationChecks()

          checkAllDownloadsComplete()
        Behaviors.same

      case ByteCodeSyncComplete if !bytecodePhaseComplete =>
        bytecodePhaseComplete = true
        appStateStorage.putSnapSyncBytecodeComplete(true).commit()
        progressMonitor.setBytecodeComplete()
        val downloaded = progressMonitor.currentProgress.bytecodesDownloaded
        ctx.log.info(
          s"ByteCode sync complete ($downloaded bytecodes). Storage: $storagePhaseComplete, Accounts: $accountsComplete"
        )
        // Bytecode done — give storage the full per-peer budget (was 3/5, now 5/5).
        // On peer-limited networks (Mordor: ~10 peers), this nearly doubles storage throughput.
        if !storagePhaseComplete then
          storageRangeCoordinator.foreach { coord =>
            coord ! actors.StorageRangeCoordinator.UpdateMaxInFlightPerPeer(snapSyncConfig.maxInFlightPerPeer)
            ctx.log.info(
              s"Storage per-peer budget boosted to ${snapSyncConfig.maxInFlightPerPeer} (bytecode complete, full budget)"
            )
          }
        checkAllDownloadsComplete()
        Behaviors.same

      case StorageRangeSyncComplete if !storagePhaseComplete =>
        storagePhaseComplete = true
        storagePhaseForceCompleted = false
        appStateStorage.putSnapSyncStorageComplete(true).commit()
        ctx.log.info(s"Storage range sync complete. ByteCode: $bytecodePhaseComplete, Accounts: $accountsComplete")
        checkAllDownloadsComplete()
        Behaviors.same

      case StorageRangeSyncForceCompleted if !storagePhaseComplete =>
        if currentPhase == ByteCodeAndStorageSync || currentPhase == StateHealing then
          storagePhaseComplete = true
          storagePhaseForceCompleted = true
          ctx.log.warn(
            s"Storage range sync was force-completed. ByteCode: $bytecodePhaseComplete, " +
              s"Accounts: $accountsComplete. SNAP will run healing/validation before handoff."
          )
          checkAllDownloadsComplete()
        else
          ctx.log.warn(
            s"StorageRangeSyncForceCompleted received during phase $currentPhase " +
              s"(consecutive-failures transient path) — ignoring storagePhaseComplete flag. " +
              s"Storage stagnation recovery remains active for ByteCodeAndStorageSync phase."
          )
        Behaviors.same

      case HealingAllPeersStateless if currentPhase == StateHealing =>
        ctx.log.warn("All healing peers stateless — refreshing pivot in-place for healing")
        refreshPivotInPlace("all healing peers stateless")
        Behaviors.same

      // Coordinator detected no healing progress (MaxConsecutiveStagnations 2-min cycles, or the
      // healingStagnationTimeoutMs path). Stagnation means "healing is SLOW", NOT "the root is unservable".
      //
      // LIVELOCK (fixed by heal-hold-pivot-on-stagnation): rolling the pivot on stagnation resets the
      // coordinator's verificationPassComplete=false and re-seeds a pending task, so a slow verification BFS
      // (~16-20h on a slow SSD) is orphaned by a pivot roll (~28-min snap serve window) and can NEVER coincide
      // with a quiet inter-roll window → completion gate never satisfied, regular sync never reached. The
      // missing nodes DO heal (durable, content-addressed), but the gate can't close.
      //
      // FIX: HOLD the healing pivot fixed on stagnation — resume/retry dispatch against the held root instead
      // of rolling. Consensus-safe: the healing pivot is a SYNC target, not a consensus rule. GetTrieNodes
      // fetches missing nodes BY HASH (content-addressed), so a stale root's missing nodes stay ~99.9% servable
      // by current peers. After healing converges against the held root → StateValidation → regular sync, which
      // executes blocks forward and fetches any residual missing node on-demand by hash. No state-root / EVM /
      // gas / reward / RLP output changes — this only changes WHEN the pivot rolls during the healing phase.
      //
      // The GENUINE-unservable path (HealingAllPeersStateless, above) is UNCHANGED: if the held root truly
      // becomes unservable by ALL peers, we still MUST roll or healing stalls.
      //
      // Set heal-hold-pivot-on-stagnation = false to restore the legacy roll-on-stagnation behaviour.
      case HealingStagnated(healed, pending) if currentPhase == StateHealing =>
        if snapSyncConfig.healHoldPivotOnStagnation then
          ctx.log.warn(
            s"[HEAL-STAGNATED] Healing slow (healed=$healed pending=$pending) — HOLDING pivot (not rolling); " +
              s"resuming dispatch on held root so the verification pass can converge"
          )
          trieNodeHealingCoordinator.foreach(_ ! actors.TrieNodeHealingCoordinator.HealingResumeDispatch)
        else
          // Legacy behaviour: refresh pivot — coordinator receives HealingPivotRefreshed, clears stale tasks +
          // stateless peers, re-seeds new root top-down (Besu-aligned). Do NOT stop coordinator —
          // refreshPivotInPlace sends HealingPivotRefreshed to it directly.
          ctx.log.warn(
            s"[HEAL-STAGNATED] Healing stuck: healed=$healed pending=$pending — " +
              s"refreshing pivot for fresh healing round (legacy roll-on-stagnation)"
          )
          refreshPivotInPlace("healing-stagnated")
        Behaviors.same

      case StateHealingComplete =>
        progressMonitor.startPhase(StateHealing)
        ctx.log.info("Healing coordinator signaled complete (no pending tasks, no active requests).")
        if trieWalkInProgress then
          // A trie walk is already running — its result will determine next step
          ctx.log.info("Trie walk in progress, waiting for result...")
        else
          // ARCH-WALK-HEAL-INTERLEAVE: Start walk with coordinator alive (if still running) or
          // create a fresh coordinator before the walk so inline discovery can run concurrently.
          startStateHealingWithInterleave()
        Behaviors.same

      // Complementary guard (root-cause w98gfx4wn): the heal walk root's own bytes are absent from local node storage,
      // so the coordinator REFUSED to seed it (seeding an unservable root stalls at "exactly 1 node, healed=0" forever —
      // a root cannot be reconstructed from nothing and cannot be fetched against an advancing serve root). This fires at
      // the SEED, so it covers every entry into healing — crucially the BootstrapComplete RESTART handlers that call
      // startStateHealing() directly and bypass shouldSkipHealingAfterDownloads (the whole point of the seed-site guard).
      //
      // Hand off to lazy on-demand healing exactly as shouldSkipHealingAfterDownloads's deferred path does: stop the
      // (idle) coordinator and call completeSnapSync(). The missing trie nodes are then fetched on-demand via GetTrieNodes
      // during block execution (BlockImporter/StateNodeFetcher, with real parent-root context) — the established
      // post-SNAP regular-sync fallback. This converges to a REAL Completed/regular-sync state; it is NOT a silent
      // skip-and-mark-done (finalizeSnapSync still enforces the snapStateRoot == pivotHeader.stateRoot anchor guard).
      //
      // spec 009 FR-001/FR-008: under `movingRootDeltaHeal` this handler is the BOUNDED last-resort, NOT the default.
      // The coordinator's absent-root branch SEEDS the served root and fetches it (batch-2 T006) instead of emitting
      // HealingRootUnservable, so under the flag this is reached only when the controller's own re-peg budget is
      // exhausted (refreshPivotInPlace's MaxHealRepegNoRootAttempts, batch-4 H-S7) — that branch calls completeSnapSync()
      // directly (the same handoff below). This handler stays for the flag-OFF path (where the coordinator still emits
      // HealingRootUnservable) and as a defensive catch; either way it is fail-SAFE (anchor-guard gated), never fail-open.
      case HealingRootUnservable(root) if currentPhase == StateHealing =>
        ctx.log.warn(
          s"[HEAL-ROOT-UNSERVABLE] Heal walk root ${root.toHex.take(16)} is absent from local storage and cannot be " +
            s"seeded (a heal cannot reconstruct an unservable root). Handing off to lazy on-demand healing via " +
            s"completeSnapSync() — missing trie nodes will be fetched on-demand via GetTrieNodes during block execution."
        )
        // completeSnapSync() → finalizeSnapSync() performs ALL cleanup: stopSnapOnlySchedules() cancels the
        // healing-request scheduler, and stopStateSyncChildren() stops the idle healing coordinator. No manual
        // coordinator/scheduler teardown needed here (it would double-cancel). finalizeSnapSync still enforces the
        // snapStateRoot == pivotHeader.stateRoot anchor guard, so this is NOT a false completion.
        completeSnapSync()
        Behaviors.same

      // Streaming batch from ongoing trie walk — forward immediately to coordinator for early healing
      case TrieWalkBatch(missingNodes) if currentPhase == StateHealing =>
        if missingNodes.nonEmpty then
          ctx.log.info(s"Trie walk batch: ${missingNodes.size} missing nodes — queuing for healing")
          trieNodeHealingCoordinator.foreach { coordinator =>
            coordinator ! actors.TrieNodeHealingCoordinator.QueueMissingNodes(missingNodes)
          }
        Behaviors.same

      // Streaming walk completed — all batches already sent via TrieWalkBatch
      case TrieWalkComplete(totalFound) if currentPhase == StateHealing =>
        trieWalkInProgress = false
        trieNodeHealingCoordinator.foreach(_ ! actors.TrieNodeHealingCoordinator.WalkStateChanged(false))
        if totalFound == 0 then
          ctx.log.info("Trie walk found no missing nodes — healing complete after {} rounds!", healingRoundCount)
          healingRoundCount = 0
          pivotBlock.foreach(b => appStateStorage.putSnapSyncPivotBlock(b).commit())
          stateRoot.foreach(r => appStateStorage.putSnapSyncStateRoot(r.value).commit())
          // #1188: capture clean signal — the walk just visited every node.
          healingValidatedRoot = stateRoot
          // Stop the periodic healing-request scheduler before entering validation.
          // It would otherwise keep firing 1-s ticks against a coordinator that's
          // signalled complete; the phase gate on RequestTrieNodeHealing handles
          // any tick already in the mailbox.
          timers.cancel(RequestTrieNodeHealing)
          progressMonitor.startPhase(StateValidation)
          currentPhase = StateValidation
          validateState()
        else
          // A2: Loop indefinitely until Pending==0 — mirrors go-ethereum sync.go:1400
          healingRoundCount += 1
          ctx.log.info(
            s"Trie walk complete: $totalFound missing nodes queued across batches (round $healingRoundCount)"
          )
          timers.startSingleTimer(ScheduledTrieWalkKey, ScheduledTrieWalk, 2.minutes)
        Behaviors.same

      case TrieWalkResult(missingNodes) if currentPhase == StateHealing =>
        trieWalkInProgress = false
        trieNodeHealingCoordinator.foreach(_ ! actors.TrieNodeHealingCoordinator.WalkStateChanged(false))
        if missingNodes.isEmpty then
          ctx.log.info("Trie walk found no missing nodes — healing complete after {} rounds!", healingRoundCount)
          healingRoundCount = 0
          // Commit final pivot root — deferred from refreshPivotInPlace() to prevent BUG-006.
          // AppStateStorage now reflects the root that healing actually completed against.
          for b <- pivotBlock; r <- stateRoot do
            appStateStorage.putSnapSyncPivotBlock(b).and(appStateStorage.putSnapSyncStateRoot(r.value)).commit()
          // #1188: capture clean signal — same as the streaming TrieWalkComplete(0) path.
          healingValidatedRoot = stateRoot
          // Stop the periodic healing-request scheduler before entering validation.
          // See companion handler above for rationale.
          timers.cancel(RequestTrieNodeHealing)
          progressMonitor.startPhase(StateValidation)
          currentPhase = StateValidation
          validateState()
        else
          healingRoundCount += 1
          ctx.log.info(
            s"Trie walk found ${missingNodes.size} missing nodes — queuing for healing (round $healingRoundCount)"
          )
          trieNodeHealingCoordinator.foreach { coordinator =>
            coordinator ! actors.TrieNodeHealingCoordinator.QueueMissingNodes(missingNodes)
          }
          timers.startSingleTimer(ScheduledTrieWalkKey, ScheduledTrieWalk, 2.minutes)
        Behaviors.same

      case ScheduledTrieWalk if currentPhase == StateHealing =>
        startTrieWalk()
        Behaviors.same

      case TrieWalkFailed(error) if currentPhase == StateHealing =>
        trieWalkInProgress = false
        trieNodeHealingCoordinator.foreach(_ ! actors.TrieNodeHealingCoordinator.WalkStateChanged(false))
        ctx.log.error(s"Trie walk failed: $error. Retrying after delay...")
        timers.startSingleTimer(ScheduledTrieWalkKey, ScheduledTrieWalk, 5.seconds)
        Behaviors.same

      case StateValidationComplete =>
        ctx.log.info("State validation complete. SNAP sync finished!")
        completeSnapSync()
        Behaviors.same

      // Stale-generation drop. Anything that bumps `validationGeneration`
      // (restartSnapSync, completePivotRefreshWithStateRoot, fresh validateState
      // spawn) means an in-flight Future's result is no longer applicable —
      // ignore quietly without mutating state.
      case ValidateAccountTrieResult(gen, _, _) if gen != validationGeneration =>
        ctx.log.debug(s"Dropping stale ValidateAccountTrieResult (gen=$gen, current=$validationGeneration)")
        Behaviors.same

      case ValidateStorageTriesResult(gen, _, _) if gen != validationGeneration =>
        ctx.log.debug(s"Dropping stale ValidateStorageTriesResult (gen=$gen, current=$validationGeneration)")
        Behaviors.same

      case ValidationRetry(retryGen) if retryGen != validationGeneration =>
        ctx.log.debug(s"Dropping stale ValidationRetry (gen=$retryGen, current=$validationGeneration)")
        Behaviors.same

      // Account trie validation result handlers. All gated on phase + generation
      // match. Any state mutation lives only in these handlers (never inside the
      // Future).
      case ValidateAccountTrieResult(_, Right(missing), elapsedMs) if currentPhase == StateValidation =>
        if missing.isEmpty then
          ctx.log.info(s"Account trie validation successful - no missing nodes (${elapsedMs}ms)")
          validationRetryCount = 0
          // Spawn the storage pass on the same generation; result handlers below.
          for root <- stateRoot; pivot <- pivotBlock do spawnStorageValidation(validationGeneration, root.value, pivot)
        else
          ctx.log.warn(
            s"Account trie validation found ${missing.size} missing nodes — triggering healing"
          )
          SNAPSyncMetrics.setMissingNodesDetected(missing.size.toLong)
          validationInProgress = false
          triggerHealingForMissingNodes(missing)
        Behaviors.same

      case ValidateAccountTrieResult(_, Left(error), _) if currentPhase == StateValidation =>
        SNAPSyncMetrics.incrementValidationFailure()
        ctx.log.error(s"Account trie validation failed: $error")
        var earlyBehavior: Option[Behavior[Command]] = None
        if error.contains("Missing root node") then
          validationRetryCount += 1
          // Clear the in-progress flag *before* scheduling the retry. If we left
          // it set, ValidationRetry would refuse to spawn and the path
          // deadlocks silently. The retry handler kicks `validateState()` which
          // bumps the generation again and sets the flag fresh.
          validationInProgress = false
          if validationRetryCount > MaxValidationRetries then
            val retryMsg = s"root node missing after $validationRetryCount validation retries"
            if recordCriticalFailure(retryMsg) then
              ctx.log.error("Too many critical SNAP failures — entering dormant mode")
              enterDormantMode(s"validation retry exhausted: $retryMsg")
            else
              ctx.log.warn(
                s"Root node missing after $validationRetryCount validation attempts. " +
                  "Restarting SNAP sync with a fresh pivot to rebuild the state trie."
              )
              validationRetryCount = 0
              earlyBehavior = Some(restartSnapSync(retryMsg))
          else
            ctx.log.error(s"Root node is missing (retry attempt $validationRetryCount of $MaxValidationRetries)")
            val gen = validationGeneration
            // Schedule on context.dispatcher (the actor's own scheduler), not
            // snapValidationEc — the retry message is cheap and shouldn't be
            // tied to the long-running pool's lifecycle.
            timers.startSingleTimer("validation-retry", ValidationRetry(gen), ValidationRetryDelay)
        else
          ctx.log.error("Recovering through healing phase")
          validationInProgress = false
          currentPhase = StateHealing
          startStateHealing()
        earlyBehavior.getOrElse(Behaviors.same)

      // Storage trie validation result handlers.
      case ValidateStorageTriesResult(_, Right(missing), elapsedMs) if currentPhase == StateValidation =>
        validationInProgress = false
        SNAPSyncMetrics.setMissingNodesDetected(missing.size.toLong)
        if missing.isEmpty then
          ctx.log.info(s"Storage trie validation successful - no missing nodes (${elapsedMs}ms)")
          ctx.log.info("✅ State validation COMPLETE - all tries are intact")
          ctx.self ! StateValidationComplete
        else
          ctx.log.warn(
            s"Storage trie validation found ${missing.size} missing nodes — triggering healing"
          )
          triggerHealingForMissingNodes(missing)
        Behaviors.same

      case ValidateStorageTriesResult(_, Left(error), _) if currentPhase == StateValidation =>
        SNAPSyncMetrics.incrementValidationFailure()
        ctx.log.error(s"Storage trie validation failed: $error. Recovering through healing phase")
        validationInProgress = false
        currentPhase = StateHealing
        startStateHealing()
        Behaviors.same

      case ValidationRetry(_) if currentPhase == StateValidation =>
        // Generation was already verified above by the stale-drop handler.
        // `validateState()` bumps the generation again and spawns a fresh pass.
        validateState()
        Behaviors.same

      // C2: inlined from aroundReceive — stagnation check dispatches to the active coordinator
      case CheckDownloadStagnation =>
        import org.apache.pekko.util.Timeout
        import scala.util.{Success, Failure}
        given timeout: Timeout = Timeout(2.seconds)
        ctx.log.debug(
          s"Stagnation check: phase=$currentPhase, stalledMs=${System.currentTimeMillis() - lastStorageProgressMs}"
        )

        // Proactive pivot roll: keep pivot within core-geth's 128-block snapshot window.
        if (currentPhase == AccountRangeSync || currentPhase == ByteCodeAndStorageSync) &&
          pivotBlock.isDefined
        then
          currentNetworkBestFromSnapPeers().foreach { networkBest =>
            val pivotAge = networkBest - pivotBlock.get
            val recentlyRolled = lastProactivePivotBlock.exists(last => (networkBest - last) <= BigInt(50))
            val storageLateStage =
              currentPhase == ByteCodeAndStorageSync && storageContractProgressPct >= 0.80
            if pivotAge > SnapServeWindowBlocks && !recentlyRolled &&
              pivotProbeRequestId.isEmpty && pendingProbeCommit.isEmpty &&
              !storageLateStage
            then
              val now = System.currentTimeMillis
              if now - lastProbeAttemptMs >= ProbeCooldownMs then
                ctx.log.info(
                  s"[PIVOT-ROLL] Proactive pivot roll: pivot=${pivotBlock.get} " +
                    s"network=$networkBest age=$pivotAge — readiness probe will fire after header fetch"
                )
                proactiveRollNeedsProbe = true
                lastProbeAttemptMs = now
                refreshPivotInPlace("proactive pivot roll")
          }

        val snapPeerCount = peersToDownloadFrom.values.count(_.peerInfo.remoteStatus.supportsSnap)
        val totalPeerCount = peersToDownloadFrom.size
        val stagnantMs = System.currentTimeMillis() - lastAccountProgressMs
        if currentPhase == AccountRangeSync && snapPeerCount == 0 && stagnantMs > ZeroPeerStagnationMs then
          ctx.log.info(
            s"[STAGNATION] Zero SNAP peers for ${stagnantMs / 1000}s during account sync " +
              s"($totalPeerCount total peers) — waiting for peers to reconnect; downloaded state is preserved " +
              s"(no restart)"
          )

        currentPhase match
          case AccountRangeSync =>
            accountRangeCoordinator.foreach { coordinator =>
              import org.apache.pekko.actor.typed.scaladsl.AskPattern.*
              given typedScheduler: org.apache.pekko.actor.typed.Scheduler = ctx.system.scheduler
              ctx.pipeToSelf(
                coordinator.ask[actors.AccountRangeStats](replyTo =>
                  actors.AccountRangeCoordinator.AccountGetProgress(replyTo)
                )
              ) {
                case Success(stats) => AccountCoordinatorProgress(stats)
                case Failure(_)     => AccountCoordinatorProgress(actors.AccountRangeStats(0L, 0L, 0, 0, 0, 0.0, 0L, 0))
              }
            }
          case ByteCodeAndStorageSync =>
            storageRangeCoordinator.foreach { coordinator =>
              import org.apache.pekko.actor.typed.scaladsl.AskPattern.*
              given typedScheduler: org.apache.pekko.actor.typed.Scheduler = ctx.system.scheduler
              ctx.pipeToSelf(
                coordinator.ask[actors.StorageRangeCoordinator.SyncStatistics](replyTo =>
                  actors.StorageRangeCoordinator.StorageGetProgress(replyTo)
                )
              ) {
                case Success(stats) => StorageCoordinatorProgress(stats)
                case Failure(_) =>
                  StorageCoordinatorProgress(actors.StorageRangeCoordinator.SyncStatistics(0, 0, 0, 0, 0, 0, 0.0))
              }
            }
            if !bytecodePhaseComplete then
              bytecodeCoordinator.foreach { coordinator =>
                import org.apache.pekko.actor.typed.scaladsl.AskPattern.*
                given typedScheduler: org.apache.pekko.actor.typed.Scheduler = ctx.system.scheduler
                ctx.pipeToSelf(
                  coordinator.ask[actors.ByteCodeCoordinator.ByteCodeProgress](replyTo =>
                    actors.ByteCodeCoordinator.ByteCodeGetProgress(replyTo)
                  )
                ) {
                  case Success(progress) => ByteCodeCoordinatorProgress(progress)
                  case Failure(_) =>
                    ByteCodeCoordinatorProgress(actors.ByteCodeCoordinator.ByteCodeProgress(0.0, 0L, 0L))
                }
              }
          case _ => // No stagnation check needed in other phases
        Behaviors.same

      case AccountCoordinatorProgress(progress) if currentPhase == AccountRangeSync =>
        if progress.elapsedTimeMs > 0 || progress.tasksPending > 0 || progress.tasksActive > 0 || progress.tasksCompleted > 0
        then maybeRestartIfAccountStagnant(progress)
        Behaviors.same

      case StorageCoordinatorProgress(stats) if currentPhase == ByteCodeAndStorageSync =>
        ctx.log.info(
          s"Storage stagnation check: pending=${stats.tasksPending}, active=${stats.tasksActive}, " +
            s"completed=${stats.tasksCompleted}, stalledMs=${System.currentTimeMillis() - lastStorageProgressMs}, " +
            s"refreshAttempted=$storageStagnationRefreshAttempted"
        )
        maybeRestartIfStorageStagnant(stats)
        Behaviors.same

      case ByteCodeCoordinatorProgress(progress) if currentPhase == ByteCodeAndStorageSync =>
        ctx.log.info(
          s"ByteCode stagnation check: downloaded=${progress.bytecodesDownloaded}, " +
            s"stalledMs=${System.currentTimeMillis() - lastBytecodeProgressMs}"
        )
        maybeForceCompleteIfBytecodeStagnant(progress)
        Behaviors.same

      // Chain download runs in parallel — track progress and completion
      case ChainDownloaderProgress(h, b, r, t) =>
        progressMonitor.updateChainProgress(h, b, r, t)
        Behaviors.same

      case ChainDownloaderDone =>
        ctx.log.info("Parallel chain download completed during SNAP state sync.")
        chainDownloadComplete = true
        Behaviors.same

      case GetStatus(replyTo) =>
        replyTo ! currentSyncStatus
        Behaviors.same

      case GetProgress(replyTo) =>
        replyTo ! progressMonitor.currentProgress
        Behaviors.same

      case msg =>
        ctx.log.debug(s"Unhandled message in syncing state: $msg")
        Behaviors.same
    }
    .receiveSignal { case (_, PostStop) =>
      onStop(); Behaviors.same
    }

  private def scheduleStagnationChecks(): Unit =
    val interval = DownloadStagnationCheckInterval
    timers.startTimerWithFixedDelay(CheckDownloadStagnation, CheckDownloadStagnation, interval)

  /** Handle stagnation for storage phase.
    *
    * When storage has no progress for StorageStagnationThreshold:
    *   - First stall: attempt pivot refresh (cheaper recovery)
    *   - Second stall (30s later, not 20min): force-complete to healing
    *
    * The second check uses a short window because the pivot refresh either works immediately (peers serve new root) or
    * it doesn't (all peers stateless again). Waiting another 20 minutes just delays the inevitable force-complete.
    */
  private def maybeRestartIfStorageStagnant(stats: actors.StorageRangeCoordinator.SyncStatistics): Unit =
    if currentPhase == ByteCodeAndStorageSync then
      // If coordinator responded with real stats, check if work remains.
      // If all stats are zero (ask timeout), assume work IS remaining since
      // we're still in ByteCodeAndStorageSync phase (would have transitioned if truly complete).
      val isTimeoutResponse =
        stats.tasksPending == 0 && stats.tasksActive == 0 && stats.tasksCompleted == 0 && stats.elapsedTimeMs == 0
      // Bug 1 fix: coordinator mailbox was backed up processing a batch — ask timed out and returned
      // all-zeros. This is liveness, not stagnation. Reset the clock and skip this tick so the
      // stagnation timer doesn't advance while the coordinator is actively working.
      if isTimeoutResponse then lastStorageProgressMs = System.currentTimeMillis()
      else
        val workRemaining = stats.tasksPending > 0 || stats.tasksActive > 0

        // Special case: coordinator reports 0 pending + 0 active but never sent StorageRangeSyncComplete.
        // This means trie construction is stuck (accountsInTrieConstruction/pendingAccountSlots not empty).
        // After the stagnation threshold, force-complete to unstick it.
        if !workRemaining && !storagePhaseComplete then
          val now = System.currentTimeMillis()
          val stalledForMs = now - lastStorageProgressMs
          if stalledForMs > StorageStagnationThreshold.toMillis then
            ctx.log.warn(
              s"Storage coordinator reports 0 pending/0 active but never sent StorageRangeSyncComplete " +
                s"(stalled ${stalledForMs / 1000}s). Trie construction likely stuck. Force-completing."
            )
            timers.cancel(RequestStorageRanges)
            if !forceCompleteStorageSent then
              forceCompleteStorageSent = true
              storageRangeCoordinator.foreach(_ ! actors.StorageRangeCoordinator.ForceCompleteStorage)
        else if workRemaining then
          val now = System.currentTimeMillis()
          val stalledForMs = now - lastStorageProgressMs

          if !storageStagnationRefreshAttempted then
            // First stall: needs full threshold before triggering
            if stalledForMs >= StorageStagnationThreshold.toMillis && now - lastPivotRestartMs >= MinPivotRestartInterval.toMillis
            then
              lastPivotRestartMs = now
              storageStagnationRefreshAttempted = true
              ctx.log.warn(
                s"Storage sync stalled: no progress for ${stalledForMs / 1000}s " +
                  s"(threshold=${StorageStagnationThreshold.toSeconds}s). Attempting pivot refresh."
              )
              lastStorageProgressMs = now
              refreshPivotInPlace(s"storage stagnation: no progress for ${stalledForMs / 1000}s")
          else
            // Second stall after refresh: short grace period (2 min), then force-complete.
            // The pivot refresh either works quickly or not at all.
            val postRefreshGrace = 2.minutes
            if stalledForMs >= postRefreshGrace.toMillis then
              ctx.log.warn(
                s"Storage sync stalled after pivot refresh: no progress for ${stalledForMs / 1000}s. " +
                  s"Promoting to healing phase (preserving downloaded state)."
              )
              timers.cancel(RequestStorageRanges)
              if !forceCompleteStorageSent then
                forceCompleteStorageSent = true
                storageRangeCoordinator.foreach(_ ! actors.StorageRangeCoordinator.ForceCompleteStorage)

  private val BytecodeStagnationThreshold: FiniteDuration = 10.minutes

  /** Force-complete bytecode sync if no progress for BytecodeStagnationThreshold.
    *
    * Only fires during ByteCodeAndStorageSync when noMoreTasksExpected is set (post-AccountRange). Missing bytecodes
    * are recovered per-block during import via BytecodeRecoveryActor.
    */
  private def maybeForceCompleteIfBytecodeStagnant(progress: actors.ByteCodeCoordinator.ByteCodeProgress): Unit =
    if currentPhase == ByteCodeAndStorageSync && !bytecodePhaseComplete then
      if progress.bytecodesDownloaded > lastBytecodeProgressCount then
        lastBytecodeProgressCount = progress.bytecodesDownloaded
        lastBytecodeProgressMs = System.currentTimeMillis()
      else
        val stalledForMs = System.currentTimeMillis() - lastBytecodeProgressMs
        if stalledForMs >= BytecodeStagnationThreshold.toMillis then
          ctx.log.warn(
            s"ByteCode sync stalled: no progress for ${stalledForMs / 1000}s " +
              s"(threshold=${BytecodeStagnationThreshold.toSeconds}s, downloaded=${progress.bytecodesDownloaded}). " +
              s"Force-completing — missing bytecodes deferred to import-time recovery."
          )
          bytecodeCoordinator.foreach(_ ! actors.ByteCodeCoordinator.ForceCompleteByteCodes)

  /** Geth-aligned: check if all 3 concurrent download phases are complete. Only transitions to healing when accounts +
    * bytecodes + storage are ALL done. The sentinel pattern (NoMoreByteCodeTasks/NoMoreStorageTasks) ensures bytecodes
    * and storage cannot complete before accounts.
    */
  private def checkAllDownloadsComplete(): Unit =
    if accountsComplete && bytecodePhaseComplete && storagePhaseComplete &&
      currentPhase != StateHealing && currentPhase != ChainDownloadCompletion && currentPhase != Completed
    then
      if SNAPSyncController.shouldSkipHealingAfterDownloads(
          snapSyncConfig,
          resumedStaleCursors
        )
      then
        // With deferred merkleization, trie nodes were never constructed during download —
        // only flat storage was written. A trie walk would find the entire internal trie "missing",
        // taking hours to scan and failing to heal (peers can't serve the full trie via GetTrieNodes).
        //
        // Skip healing/validation entirely. Regular sync's BlockImporter will fetch missing trie
        // nodes on-demand via GetTrieNodes (SNAP protocol) when block execution encounters them.
        // This is the "lazy healing" pattern used by geth's path-based storage.
        ctx.log.info(
          "All state downloads complete (accounts + bytecodes + storage). " +
            "Deferred merkleization enabled — skipping healing/validation phase. " +
            "Missing trie nodes will be fetched on-demand during block execution."
        )
        completeSnapSync()
      else
        if snapSyncConfig.deferredMerkleization && storagePhaseForceCompleted then
          ctx.log.warn(
            "All state downloads reached terminal state, but storage was force-completed with deferred " +
              "merkleization enabled. Starting healing instead of handing off a state with known holes."
          )
        else ctx.log.info("All state downloads complete (accounts + bytecodes + storage). Starting healing...")
        currentPhase = StateHealing
        startStateHealing()

  def bootstrapping(): Behavior[Command] =
    Behaviors
      .receiveMessage[Command] {
        // C1: inlined peer-list arms with bootstrap reactivity
        case WrappedHandshakedPeers(peers) =>
          handleHandshakedPeersBootstrapReactivity(peers); Behaviors.same
        case WrappedPeerDisconnected(peerId) =>
          handlePeerDisconnectedDebounced(peerId); Behaviors.same
        case FlushPeerDisconnects =>
          flushPeerDisconnects(); Behaviors.same
        case PollHandshakedPeers =>
          pollHandshakedPeers(); Behaviors.same

        case hint: CLPivotHint =>
          // CL pushed a (potentially newer) head while we were bootstrapping the previous one.
          // Buffer it; we'll re-evaluate on the next `startSnapSync()` if the in-flight bootstrap
          // fails or the caller decides to re-pivot. We don't tear down a healthy in-flight
          // bootstrap mid-stream — the original head is almost always sufficient.
          handleCLPivotHint(hint, isStarting = false)
          Behaviors.same

        case BootstrapComplete(pivotHeaderOpt) =>
          ctx.log.info("=" * 80)
          ctx.log.info("✅ Bootstrap phase complete - transitioning to SNAP sync")
          ctx.log.info("=" * 80)

          // Get the bootstrap target that we synced to (this is the pivot we wanted)
          val bootstrapTarget = appStateStorage.getSnapSyncBootstrapTarget()
          val localBestBlock = appStateStorage.getBestBlockNumber()

          ctx.log.info(s"Bootstrap target: ${bootstrapTarget.getOrElse("none")}, Local best block: $localBestBlock")

          // Clear bootstrap target from storage now that we've read it
          appStateStorage.clearSnapSyncBootstrapTarget().commit()

          // Reset retry counter
          bootstrapRetryCount = 0

          // Helper: compute current best height from SNAP-capable peers (subject to bootstrapPivot floor).
          // Peers whose STATUS hasn't arrived yet have maxBlockNumber=0 — exclude them, otherwise
          // a fresh-startup race returns Some(0) and the caller commits to a genesis pivot before
          // any real peer height is known.
          def currentNetworkBestFromSnapPeers(bootstrapPivot: BigInt): Option[BigInt] =
            // Peers whose STATUS hasn't arrived yet have maxBlockNumber=0 — exclude them, otherwise a
            // fresh-startup race counts them as "network best=0" → pivot=-64 → genesis fallback.
            val snapPeersForPivot =
              peersToDownloadFrom.values.toList
                .filter(p => p.peerInfo.remoteStatus.supportsSnap && p.peerInfo.forkAccepted)
                .filter(_.peerInfo.maxBlockNumber > 0)
                .filter(p => bootstrapPivot == 0 || p.peerInfo.maxBlockNumber >= bootstrapPivot)

            snapPeersForPivot
              .sortBy(_.peerInfo.maxBlockNumber)(bigIntReverseOrdering)
              .headOption
              .map(_.peerInfo.maxBlockNumber)

          def pivotTooStaleAgainstNetworkHead(pivot: BigInt): Boolean =
            val bootstrapPivot = appStateStorage.getBootstrapPivotBlock()
            currentNetworkBestFromSnapPeers(bootstrapPivot) match
              case Some(networkBest) if networkBest > 0 =>
                val delta = networkBest - pivot
                if delta > snapSyncConfig.maxPivotStalenessBlocks then
                  ctx.log.warn(
                    s"Bootstrapped pivot $pivot is now $delta blocks behind current network best $networkBest; " +
                      s"exceeds maxPivotStaleness=${snapSyncConfig.maxPivotStalenessBlocks}. Re-selecting a fresher pivot."
                  )
                  true
                else
                  ctx.log.info(
                    s"Bootstrapped pivot freshness: pivot=$pivot, networkBest=$networkBest, delta=$delta, " +
                      s"maxPivotStaleness=${snapSyncConfig.maxPivotStalenessBlocks}"
                  )
                  false
              case _ =>
                // If we can't see any suitable SNAP peers right now, don't block on freshness.
                false

          // bestSnapPeerTD removed: pivot TD is no longer seeded from peer wire TD (ETH68_BOOTSTRAP).
          // Using peer TD inflated every stored chain weight by (peerHead − pivot) × avgDifficulty.
          // Pivot now uses pivotBlockNumber as a neutral proxy; real TDs are built by block import.

          pivotHeaderOpt match
            case Some(header) =>
              val targetPivot = header.number.value

              if pivotTooStaleAgainstNetworkHead(targetPivot) then
                // Don't commit a pivot that peers are unlikely to serve.
                startSnapSync()
              else
                // Gate on ETH/Sepolia only — ETC pivot headers use StdBlockHeaderValidator (PoW).
                val pivotHeaderValid =
                  if isPoSChain then
                    given bc: BlockchainConfig =
                      com.chipprbots.ethereum.utils.Config.blockchains.blockchainConfig
                    BlockHeader.validateFieldCount(header, bc) match
                      case Left(msg) =>
                        ctx.log.error(
                          "SNAP bootstrap pivot header field-count mismatch — aborting commit, restarting sync: {}",
                          msg
                        )
                        false
                      case Right(_) =>
                        PoSBlockHeaderValidator.validateHeaderOnly(header) match
                          case Left(err) =>
                            ctx.log.error(
                              "SNAP bootstrap pivot header failed post-merge validation — aborting commit, restarting sync: {}",
                              err
                            )
                            false
                          case Right(_) => true
                  else true

                if !pivotHeaderValid then startSnapSync()
                else
                  pivotBlock = Some(targetPivot)
                  stateRoot = Some(header.stateRoot)
                  appStateStorage
                    .putSnapSyncPivotBlock(targetPivot)
                    .and(appStateStorage.putSnapSyncStateRoot(header.stateRoot.value))
                    .commit()
                  updateBestBlockForPivot(header, targetPivot)

                  SNAPSyncMetrics.setPivotBlockNumber(targetPivot)

                  ctx.log.info("=" * 80)
                  ctx.log.info("🎯 SNAP Sync Ready (from bootstrap)")
                  ctx.log.info("=" * 80)
                  ctx.log.info(s"Local best block: $localBestBlock")
                  ctx.log.info(s"Using bootstrapped pivot block: $targetPivot")
                  ctx.log.info(s"State root: ${header.stateRoot.value.toHex.take(16)}...")
                  ctx.log.info("=" * 80)

                  if accountsComplete && storagePhaseComplete && bytecodePhaseComplete then
                    ctx.log.info("All data phases complete — skipping to state healing with fresh pivot")
                    currentPhase = StateHealing
                    startStateHealing()
                  else
                    ctx.log.info(
                      s"Beginning fast state sync with ${snapSyncConfig.accountConcurrency} concurrent workers"
                    )
                    currentPhase = AccountRangeSync
                    startAccountRangeSync(header.stateRoot)
                  syncing()

            case None =>
              // Backward-compat / fallback: use the stored bootstrap target and local header.
              bootstrapTarget match
                case Some(targetPivot) =>
                  if pivotTooStaleAgainstNetworkHead(targetPivot) then startSnapSync()
                  else
                    blockchainReader.getBlockHeaderByNumber(targetPivot) match
                      case Some(header) =>
                        pivotBlock = Some(targetPivot)
                        stateRoot = Some(header.stateRoot)
                        appStateStorage
                          .putSnapSyncPivotBlock(targetPivot)
                          .and(appStateStorage.putSnapSyncStateRoot(header.stateRoot.value))
                          .commit()
                        updateBestBlockForPivot(header, targetPivot)

                        SNAPSyncMetrics.setPivotBlockNumber(targetPivot)

                        ctx.log.info("=" * 80)
                        ctx.log.info("🎯 SNAP Sync Ready (from bootstrap, local header)")
                        ctx.log.info("=" * 80)
                        ctx.log.info(s"Local best block: $localBestBlock")
                        ctx.log.info(s"Using bootstrapped pivot block: $targetPivot")
                        ctx.log.info(s"State root: ${header.stateRoot.value.toHex.take(16)}...")
                        ctx.log.info("=" * 80)

                        if accountsComplete && storagePhaseComplete && bytecodePhaseComplete then
                          ctx.log.info("All data phases complete — skipping to state healing with fresh pivot")
                          currentPhase = StateHealing
                          startStateHealing()
                        else
                          ctx.log.info(
                            s"Beginning fast state sync with ${snapSyncConfig.accountConcurrency} concurrent workers"
                          )
                          currentPhase = AccountRangeSync
                          startAccountRangeSync(header.stateRoot)
                        syncing()

                      case None =>
                        ctx.log.warn(s"Bootstrap complete but pivot header $targetPivot not available yet")
                        ctx.log.warn("Falling back to recalculating pivot from current network state")
                        startSnapSync()
                case None =>
                  ctx.log.info("No bootstrap target stored - calculating pivot from network state")
                  startSnapSync()

        case RetrySnapSyncStart =>
          ctx.log.info("🔄 Retrying SNAP sync start after bootstrap delay...")
          startSnapSync()

        case GetProgress(replyTo) =>
          // During bootstrap, report that we're preparing for SNAP sync
          val currentBlock = appStateStorage.getBestBlockNumber()
          val targetBlock = appStateStorage.getSnapSyncBootstrapTarget().getOrElse(BigInt(0))

          ctx.log.info(s"Bootstrap progress: $currentBlock / $targetBlock blocks")

          // Send a simple progress indicator - we're in bootstrap mode
          replyTo ! SyncProgress(
            phase = Idle,
            accountsSynced = 0,
            bytecodesDownloaded = 0,
            storageSlotsSynced = 0,
            nodesHealed = 0,
            elapsedSeconds = 0,
            phaseElapsedSeconds = 0,
            accountsPerSec = 0,
            bytecodesPerSec = 0,
            slotsPerSec = 0,
            nodesPerSec = 0,
            recentAccountsPerSec = 0,
            recentBytecodesPerSec = 0,
            recentSlotsPerSec = 0,
            recentNodesPerSec = 0,
            phaseProgress =
              if targetBlock == 0 then 0 else ((currentBlock.toDouble / targetBlock.toDouble) * 100).toInt,
            estimatedTotalAccounts = 0,
            estimatedTotalBytecodes = 0,
            estimatedTotalSlots = 0,
            startTime = System.currentTimeMillis(),
            phaseStartTime = System.currentTimeMillis()
          )
          Behaviors.same

        case GetStatus(replyTo) =>
          // During bootstrap, we're syncing via regular sync
          val currentBlock = appStateStorage.getBestBlockNumber()
          val targetBlock = appStateStorage.getSnapSyncBootstrapTarget().getOrElse(BigInt(0))
          val startingBlock = appStateStorage.getSyncStartingBlock()
          replyTo ! SyncProtocol.Status.Syncing(
            startingBlockNumber = startingBlock,
            blocksProgress = SyncProtocol.Status.Progress(currentBlock, targetBlock),
            stateNodesProgress = None
          )
          Behaviors.same

        case PivotBootstrapFailed(reason) =>
          ctx.log.warn(s"Pivot header bootstrap failed during initial startup: $reason")
          bootstrapRetryCount += 1
          checkBootstrapRetryTimeout(s"bootstrap failed: $reason") match
            case Some(b) => b
            case None =>
              val delay = bootstrapRetryDelay
              ctx.log.info(s"Retrying SNAP sync start in $delay (attempt $bootstrapRetryCount)")
              timers.cancel(BootstrapCheckKey)
              timers.startSingleTimer(BootstrapCheckKey, RetrySnapSyncStart, delay)
              Behaviors.same

        // SNAP peer eviction runs during bootstrap to free slots for SNAP-capable peers
        case EvictNonSnapPeers =>
          evictNonSnapPeers()
          Behaviors.same

        case msg =>
          ctx.log.debug(s"Unhandled message in bootstrapping state: $msg")
          Behaviors.same
      }
      .receiveSignal { case (_, PostStop) =>
        onStop(); Behaviors.same
      }

  def completed(): Behavior[Command] =
    Behaviors
      .receiveMessage[Command] {
        case GetStatus(replyTo) =>
          replyTo ! SyncProtocol.Status.SyncDone
          Behaviors.same

        case GetProgress(replyTo) =>
          replyTo ! progressMonitor.currentProgress
          Behaviors.same

        case _ =>
          ctx.log.debug("SNAP sync is complete, ignoring messages")
          Behaviors.same
      }
      .receiveSignal { case (_, PostStop) =>
        onStop(); Behaviors.same
      }

  /** Buffer a CL-driven head hint and (optionally) react to the change.
    *
    * On post-merge chains: the hint's `headHash` becomes the SNAP pivot when `startSnapSync()` runs next. The hint's
    * `knownHeader`, when present, lets us skip the peer round-trip entirely. When absent, we route through
    * `StartRegularSyncBootstrapByHash` to fetch the header from a peer.
    *
    * On pre-merge chains (TTD = None): we still buffer for diagnostics but don't act — `startSnapSync()` ignores
    * `clPivotHint` when `!isPoSChain`.
    */
  private def handleCLPivotHint(hint: CLPivotHint, isStarting: Boolean): Unit =
    val isNew = !clPivotHint.exists(_.headHash == hint.headHash)
    clPivotHint = Some(hint)
    if isNew then clHintArrivedAtMs = Some(System.currentTimeMillis())
    if isNew then
      ctx.log.info(
        "[CL-PIVOT] Received CL-driven head {} (knownHeader={}, isPoSChain={})",
        com.chipprbots.ethereum.utils.ByteStringUtils.hash2string(hint.headHash),
        hint.knownHeader.map(_.number).getOrElse("unknown"),
        isPoSChain
      )
    // Forward the CL head number to the network peer manager so it can run lagging-peer
    // eviction. Only valid when we know the actual block number (the by-hash bootstrap
    // variant skips this — `knownHeader` is None — and `NetworkPeerManagerActor` correctly
    // treats "never updated" as "pre-merge or unknown" → no-op).
    hint.knownHeader.foreach { header =>
      networkPeerManager ! com.chipprbots.ethereum.network.NetworkPeerManagerActor.UpdateClHeadCmd(header.number.value)
    }
    // Reactive starts: if we're already at idle and a hint arrives during operator-driven
    // startup, the `Start` handler will pick this up. We don't auto-start here because
    // SyncController's startSnapSync() drives the lifecycle.
    val _ = isStarting

  private[snap] def startSnapSync(): Behavior[Command] =
    import scala.util.boundary, boundary.break
    boundary[Behavior[Command]] {
      // Start evicting non-SNAP peers immediately to make room for SNAP-capable peers.
      // This runs during pivot selection and bootstrap, not just after account sync starts.
      startSnapPeerEviction()

      // Step 7: Check for accounts-complete recovery (process crash during bytecode/storage phase).
      // If accounts were previously completed and the pivot is still fresh, skip account download
      // and only re-run bytecodes + storage from the persisted storage file.
      if appStateStorage.isSnapSyncAccountsComplete() then
        val savedPivot = appStateStorage.getSnapSyncPivotBlock()
        val savedRootOpt = appStateStorage.getSnapSyncStateRoot()
        val savedStoragePath = appStateStorage.getSnapSyncStorageFilePath()

        (savedPivot, savedRootOpt) match
          case (Some(pivot), Some(rootBs)) if pivot > 0 =>
            ctx.log.info(s"Recovery: accounts previously completed at pivot $pivot. Checking freshness...")

            // FIX-BUG2-ESCALATION: saved pivot < RegularSync escalation hint → force fresh selection.
            // minPivotHint is the block number where RegularSync got stuck; re-using the saved pivot
            // (whose state trie is incomplete at that block) wastes time presenting a root that peers
            // have already evicted from their serve window. minPivotHint == 0 in non-escalation
            // restarts (crash recovery, normal restart) so this never fires spuriously.
            val belowEscalationHint = minPivotHint > 0 && pivot < minPivotHint
            if belowEscalationHint then
              ctx.log.warn(
                s"Recovery: saved pivot $pivot < RegularSync escalation hint $minPivotHint " +
                  s"— clearing accounts-complete flag and forcing fresh pivot selection"
              )
              appStateStorage
                .putSnapSyncAccountsComplete(false)
                .and(appStateStorage.putSnapSyncStorageComplete(false))
                .and(appStateStorage.putSnapSyncBytecodeComplete(false))
                .commit()
              // Fall through to normal startup (same path as drift-exceeded + phases incomplete)

            // Check if pivot is still fresh enough (skipped when belowEscalationHint forced clear)
            val networkBest = currentNetworkBestFromSnapPeers().getOrElse(BigInt(0))
            val drift = if networkBest > 0 then (networkBest - pivot).abs else BigInt(0)
            if !belowEscalationHint && networkBest > 0 && drift > snapSyncConfig.maxPivotStalenessBlocks then
              val storageAlreadyDone = appStateStorage.isSnapSyncStorageComplete()
              val bytecodeAlreadyDone = appStateStorage.isSnapSyncBytecodeComplete()
              if storageAlreadyDone && bytecodeAlreadyDone then
                // All data phases complete — content-addressed data is valid across pivot changes.
                // Match go-ethereum/Besu: do NOT wipe. Bootstrap acquires a fresh pivot and the
                // BootstrapComplete handler detects all-phases-complete → skips to StateHealing.
                ctx.log.warn(
                  s"Recovery: pivot $pivot drifted $drift blocks, but all phases complete. " +
                    "Requesting fresh pivot for healing (go-ethereum/Besu behavior)."
                )
                accountsComplete = true
                storagePhaseComplete = true
                bytecodePhaseComplete = true
                // Leave pivotBlock and stateRoot unset — bootstrap will set them.
              else
                ctx.log.warn(
                  s"Recovery: pivot $pivot drifted $drift blocks from network best $networkBest. " +
                    "Clearing accounts-complete flag and restarting fresh."
                )
                appStateStorage
                  .putSnapSyncAccountsComplete(false)
                  .and(appStateStorage.putSnapSyncStorageComplete(false))
                  .and(appStateStorage.putSnapSyncBytecodeComplete(false))
                  .commit()
                // Fall through to normal startup
            else if !belowEscalationHint then
              // Pivot is fresh enough — recover bytecodes + storage only
              pivotBlock = Some(pivot)
              stateRoot = Some(TrieRoot(rootBs))
              accountsComplete = true

              val storageAlreadyDone = appStateStorage.isSnapSyncStorageComplete()
              val bytecodeAlreadyDone = appStateStorage.isSnapSyncBytecodeComplete()
              storagePhaseComplete = storageAlreadyDone
              bytecodePhaseComplete = bytecodeAlreadyDone
              storagePhaseForceCompleted = false

              if storageAlreadyDone then ctx.log.info("Recovery: storage phase already complete — skipping re-download")
              if bytecodeAlreadyDone then
                ctx.log.info("Recovery: bytecode phase already complete — skipping re-download")

              ctx.log.info(s"Recovery: resuming bytecodes + storage sync from pivot $pivot (drift=$drift blocks)")

              val storage = getOrCreateMptStorage(pivot)
              coordinatorGeneration += 1

              if !bytecodeAlreadyDone then
                bytecodeCoordinator = Some(
                  ctx.spawn(
                    Behaviors
                      .supervise(
                        actors.ByteCodeCoordinator(
                          evmCodeStorage = evmCodeStorage,
                          networkPeerManager = networkPeerManager,
                          requestTracker = requestTracker,
                          batchSize = ByteCodeTask.DEFAULT_BATCH_SIZE,
                          snapSyncController = ctx.self
                        )
                      )
                      .onFailure[Throwable](
                        SupervisorStrategy.restartWithBackoff(1.second, 10.seconds, 0.2).withMaxRestarts(3)
                      ),
                    s"bytecode-coordinator-$coordinatorGeneration",
                    org.apache.pekko.actor.typed.DispatcherSelector.fromConfig("sync-dispatcher")
                  )
                )
                bytecodeCoordinator.foreach(_ ! actors.ByteCodeCoordinator.StartByteCodeSync(Seq.empty))
                timers.startTimerWithFixedDelay(RequestByteCodes, RequestByteCodes, 1.second)

              if !storageAlreadyDone then
                forceCompleteStorageSent = false
                storageRangeCoordinator = Some(
                  ctx.spawn(
                    Behaviors
                      .supervise(
                        actors.StorageRangeCoordinator(
                          stateRoot = rootBs,
                          networkPeerManager = networkPeerManager,
                          requestTracker = requestTracker,
                          mptStorage = storage,
                          flatSlotStorage = flatSlotStorage,
                          maxAccountsPerBatch = snapSyncConfig.storageBatchSize,
                          maxInFlightRequests = snapSyncConfig.storageConcurrency,
                          requestTimeout = snapSyncConfig.timeout,
                          snapSyncController = ctx.self,
                          initialMaxInFlightPerPeer = 3, // Recovery: accounts done, storage gets 3 of 5 per-peer budget
                          initialResponseBytes = snapSyncConfig.storageInitialResponseBytes,
                          minResponseBytes = snapSyncConfig.storageMinResponseBytes,
                          deferredMerkleization = snapSyncConfig.deferredMerkleization,
                          maxConcurrentStorageAccounts = snapSyncConfig.maxConcurrentStorageAccounts,
                          snapProgressStorage = Some(snapProgressStorage),
                          storageScheme = snapSyncConfig.storageScheme,
                          pathNodeStorage = pathNodeStorageOpt
                        )
                      )
                      .onFailure[Throwable](
                        SupervisorStrategy.restartWithBackoff(1.second, 10.seconds, 0.2).withMaxRestarts(3)
                      ),
                    s"storage-range-coordinator-$coordinatorGeneration",
                    org.apache.pekko.actor.typed.DispatcherSelector.fromConfig("sync-dispatcher")
                  )
                )
                storageRangeCoordinator.foreach(_ ! actors.StorageRangeCoordinator.StartStorageRangeSync(rootBs))
                timers.startTimerWithFixedDelay(RequestStorageRanges, RequestStorageRanges, 1.second)

              // Recovery budget: accounts done, bytecode=2, storage=3 (total 5 per peer)
              bytecodeCoordinator.foreach(_ ! actors.ByteCodeCoordinator.UpdateMaxInFlightPerPeer(2))

              // Stream storage tasks from persisted file if available
              if !storageAlreadyDone then
                savedStoragePath.foreach { pathStr =>
                  val filePath = java.nio.file.Paths.get(pathStr)
                  if java.nio.file.Files.exists(filePath) then
                    val coordinator = storageRangeCoordinator.get
                    import ctx.executionContext
                    scala.concurrent
                      .Future {
                        val emptyRoot = ByteString(com.chipprbots.ethereum.mpt.MerklePatriciaTrie.EmptyRootHash)
                        val zeroHash = ByteString(new Array[Byte](32))
                        val raf = new java.io.RandomAccessFile(filePath.toFile, "r")
                        val buf = new Array[Byte](64)
                        val batch = new scala.collection.mutable.ArrayBuffer[StorageTask](10000)
                        var totalTasks = 0
                        try
                          while raf.getFilePointer < raf.length() do
                            raf.readFully(buf)
                            val accountHash = ByteString(java.util.Arrays.copyOfRange(buf, 0, 32))
                            val storageRoot = ByteString(java.util.Arrays.copyOfRange(buf, 32, 64))
                            if accountHash != zeroHash && storageRoot.nonEmpty && storageRoot != emptyRoot then
                              batch += StorageTask.createStorageTask(accountHash, storageRoot)
                            if batch.size >= 10000 then
                              coordinator ! actors.StorageRangeCoordinator.AddStorageTasks(batch.toSeq)
                              totalTasks += batch.size
                              batch.clear()
                          if batch.nonEmpty then
                            coordinator ! actors.StorageRangeCoordinator.AddStorageTasks(batch.toSeq)
                            totalTasks += batch.size
                        finally raf.close()
                        totalTasks
                      }
                      .foreach { count =>
                        asyncLog.info(s"Recovery: streamed $count storage tasks from ${filePath}")
                        // Signal no more tasks — sentinel allows completion
                        coordinator ! actors.StorageRangeCoordinator.NoMoreStorageTasks
                      }
                  else
                    ctx.log.warn(s"Recovery: storage file $filePath not found. Sending NoMore immediately.")
                    storageRangeCoordinator.foreach(_ ! actors.StorageRangeCoordinator.NoMoreStorageTasks)
                }
              if savedStoragePath.isEmpty then
                ctx.log.warn("Recovery: no storage file path persisted. Sending NoMore immediately.")
                storageRangeCoordinator.foreach(_ ! actors.StorageRangeCoordinator.NoMoreStorageTasks)

              // Bytecodes: stream codeHashes from persisted file if available. Each entry is 32 bytes
              // (raw keccak256 hash, written by AccountRangeCoordinator.uniqueCodeHashesOut).
              val savedCodeHashesPath = appStateStorage.getSnapSyncCodeHashesPath()
              if !bytecodeAlreadyDone then
                savedCodeHashesPath.foreach { pathStr =>
                  val filePath = java.nio.file.Paths.get(pathStr)
                  if java.nio.file.Files.exists(filePath) then
                    val coordinator = bytecodeCoordinator.get
                    import ctx.executionContext
                    scala.concurrent
                      .Future {
                        val raf = new java.io.RandomAccessFile(filePath.toFile, "r")
                        val buf = new Array[Byte](32)
                        val batch = new scala.collection.mutable.ArrayBuffer[ByteString](10000)
                        var totalHashes = 0
                        try
                          while raf.getFilePointer < raf.length() do
                            raf.readFully(buf)
                            batch += ByteString(java.util.Arrays.copyOf(buf, 32))
                            if batch.size >= 10000 then
                              coordinator ! actors.ByteCodeCoordinator.AddByteCodeTasks(batch.toSeq)
                              totalHashes += batch.size
                              batch.clear()
                          if batch.nonEmpty then
                            coordinator ! actors.ByteCodeCoordinator.AddByteCodeTasks(batch.toSeq)
                            totalHashes += batch.size
                        finally raf.close()
                        totalHashes
                      }
                      .foreach { count =>
                        asyncLog.info(s"Recovery: streamed $count codeHashes from ${filePath} for bytecode sync")
                        coordinator ! actors.ByteCodeCoordinator.NoMoreByteCodeTasks
                      }
                  else
                    ctx.log.warn(s"Recovery: codeHashes file $filePath not found. Sending NoMore immediately.")
                    bytecodeCoordinator.foreach(_ ! actors.ByteCodeCoordinator.NoMoreByteCodeTasks)
                }
              if savedCodeHashesPath.isEmpty then
                ctx.log.warn("Recovery: no codeHashes file path persisted. Sending NoMore immediately.")
                bytecodeCoordinator.foreach(_ ! actors.ByteCodeCoordinator.NoMoreByteCodeTasks)

              currentPhase = ByteCodeAndStorageSync
              lastStorageProgressMs = System.currentTimeMillis()
              scheduleStagnationChecks()
              progressMonitor.startPhase(ByteCodeAndStorageSync)

              // Same as fresh ByteCode phase start: raise budget + clear cooldowns so peers on 2-min
              // backoff from AccountRange don't block ByteCode dispatch on resume.
              bytecodeCoordinator.foreach(_ ! actors.ByteCodeCoordinator.UpdateMaxInFlightPerPeer(8))
              bytecodeCoordinator.foreach(_ ! actors.ByteCodeCoordinator.ByteCodePivotRefreshed)
              requestByteCodes()

              // Start parallel chain download during recovery too
              startChainDownloader()
              startSnapServerPeersScheduler()

              // If both phases were already complete, advance to healing immediately
              checkAllDownloadsComplete()
              break(syncing())
          case _ =>
            ctx.log.warn("Recovery: accounts-complete flag set but missing pivot/root. Clearing and restarting fresh.")
            appStateStorage
              .putSnapSyncAccountsComplete(false)
              .and(appStateStorage.putSnapSyncStorageComplete(false))
              .and(appStateStorage.putSnapSyncBytecodeComplete(false))
              .commit()

      // Check if there's an interrupted bootstrap to resume
      appStateStorage.getSnapSyncBootstrapTarget() match
        case Some(bootstrapTarget) =>
          val bestBlockNumber = appStateStorage.getBestBlockNumber()

          // Header-only bootstrap may not advance bestBlockNumber; allow resume if pivot header exists.
          val hasPivotHeader = blockchainReader.getBlockHeaderByNumber(bootstrapTarget).isDefined

          if bestBlockNumber >= bootstrapTarget || hasPivotHeader then
            // Bootstrap already complete - clear the target and proceed with SNAP sync
            ctx.log.info(s"Bootstrap target $bootstrapTarget already reached (current block: $bestBlockNumber)")
            appStateStorage.clearSnapSyncBootstrapTarget().commit()
            // Continue to normal SNAP sync logic below
          else
            // Resume interrupted bootstrap
            ctx.log.info(s"Resuming interrupted bootstrap: current block $bestBlockNumber / target $bootstrapTarget")
            syncController ! StartRegularSyncBootstrap(bootstrapTarget)
            break(bootstrapping())
        case None =>
        // No bootstrap in progress - continue with normal logic

      // CL-wait gate (post-merge chains only). When TTD is configured but no CL hint has
      // arrived yet, either wait indefinitely (`engine-api-required = true`, default) or
      // fall back to peer-best-by-block-number after `cl-wait-timeout` elapsed since the
      // first start attempt. Either way, we must NOT walk into TD-based selection here —
      // TD is frozen at TTD on post-merge chains and pivot selection produces useless
      // targets. Closes #1207.
      if isPoSChain && clPivotHint.isEmpty then
        val firstAttemptMs =
          clHintArrivedAtMs.getOrElse {
            // Reuse the same timestamp pattern as the hint to keep the wait window stable
            // across retry calls. We initialise on the first wait attempt.
            val now = System.currentTimeMillis()
            if clHintArrivedAtMs.isEmpty then clHintArrivedAtMs = Some(now)
            now
          }
        val waitedMs = System.currentTimeMillis() - firstAttemptMs
        if syncConfig.engineApiRequired || waitedMs < syncConfig.clWaitTimeout.toMillis then
          if bootstrapRetryCount % 10 == 0 then
            ctx.log.info(
              s"[CL-PIVOT] Post-merge chain (TTD configured), waiting for engine_forkchoiceUpdated " +
                s"from CL (engineApiRequired=${syncConfig.engineApiRequired}, waited=${waitedMs / 1000}s)"
            )
          bootstrapRetryCount += 1
          val delay = 5.seconds
          timers.cancel(BootstrapCheckKey)
          timers.startSingleTimer(BootstrapCheckKey, RetrySnapSyncStart, delay)
          break(bootstrapping())
        else
          ctx.log.warn(
            s"[CL-PIVOT] cl-wait-timeout elapsed (${syncConfig.clWaitTimeout}); engineApiRequired=false. " +
              "Falling back to peer-best-by-block-number pivot selection."
          )
        // Fallthrough: engineApiRequired=false and timeout elapsed → continue to TD path below.
        clHintArrivedAtMs = None // reset so the wait window restarts on next retry

      // CL-driven pivot path (post-merge chains only). When the consensus layer has pushed a
      // forkchoiceUpdated, prefer its head over TD-based peer selection. TD on post-merge
      // chains is frozen at TerminalTotalDifficulty so peer-best-by-TD is unreliable. This
      // is geth's "BeaconSync" pattern, plumbed via SyncController's BeaconHead listener.
      // Closes #1207.
      if isPoSChain && clPivotHint.isDefined then
        val hint = clPivotHint.get
        hint.knownHeader match
          case Some(header) =>
            ctx.log.info("=" * 80)
            ctx.log.info("🛰  SNAP pivot from CL forkchoiceUpdated")
            ctx.log.info("=" * 80)
            ctx.log.info(
              s"CL head: ${header.number} (${com.chipprbots.ethereum.utils.ByteStringUtils.hash2string(header.hash.value)})"
            )
            ctx.log.info(s"State root: ${header.stateRoot.value.toHex.take(16)}...")
            ctx.log.info(s"Beginning fast state sync with ${snapSyncConfig.accountConcurrency} concurrent workers")
            ctx.log.info("=" * 80)

            pivotBlock = Some(header.number.value)
            stateRoot = Some(header.stateRoot)
            appStateStorage
              .putSnapSyncPivotBlock(header.number.value)
              .and(appStateStorage.putSnapSyncStateRoot(header.stateRoot.value))
              .commit()
            updateBestBlockForPivot(header, header.number.value)

            SNAPSyncMetrics.setPivotBlockNumber(header.number.value)
            bootstrapRetryCount = 0

            currentPhase = AccountRangeSync
            startAccountRangeSync(header.stateRoot)
            break(syncing())

          case None =>
            // CL gave us a head hash but we don't have its header yet. Route through
            // SyncController to fetch by hash (PivotHeaderBootstrap by-hash mode). The
            // by-hash bootstrap reply re-enters via `BootstrapComplete(Some(header))`,
            // and `bootstrapping`'s pivot-staleness check will allow it through (the CL
            // head is by definition the freshest possible target).
            ctx.log.info(
              s"[CL-PIVOT] Head ${com.chipprbots.ethereum.utils.ByteStringUtils.hash2string(hint.headHash)} known by hash only — requesting by-hash bootstrap"
            )
            syncController ! StartRegularSyncBootstrapByHash(hint.headHash)
            break(bootstrapping())

      // Get local and network state for pivot selection
      val localBestBlock = appStateStorage.getBestBlockNumber()

      // If bootstrap checkpoints are configured, we should not start SNAP from a peer that is behind
      // the highest trusted checkpoint. SNAP servers only guarantee serving very recent state; picking
      // an ancient pivot (e.g. millions of blocks behind the network head) will cause peers to emit
      // empty/no-proof AccountRange responses.
      val bootstrapPivotBlock = appStateStorage.getBootstrapPivotBlock()

      // Query SNAP-capable peers to find the highest block in the network.
      // SNAP must NOT start until we have a SNAP-capable peer that is at/above the bootstrap pivot
      // (when configured). Falling back to non-SNAP peers here would select an unreachable state root.
      // Peers whose STATUS hasn't arrived yet have maxBlockNumber=0 — exclude them, otherwise a
      // fresh-startup race counts them as "network best=0" → pivot=-64 → genesis fallback.
      val snapPeersForPivot =
        peersToDownloadFrom.values.toList
          .filter(p => p.peerInfo.remoteStatus.supportsSnap && p.peerInfo.forkAccepted)
          .filter(_.peerInfo.maxBlockNumber > 0)
          .filter(p => bootstrapPivotBlock == 0 || p.peerInfo.maxBlockNumber >= bootstrapPivotBlock)

      val networkBestBlockOpt =
        snapPeersForPivot
          .sortBy(_.peerInfo.maxBlockNumber)(bigIntReverseOrdering)
          .headOption
          .map(_.peerInfo.maxBlockNumber)

      // Diagnostics: show what heights we can actually see from connected peers.
      // If this list tops out near the Core-Geth "start height", it means we simply don't have any peer
      // advertising the real network head yet (or those peers are excluded/blacklisted/disconnected).
      val topPeerHeights =
        peersToDownloadFrom.values.toList
          .sortBy(_.peerInfo.maxBlockNumber)(bigIntReverseOrdering)
          .take(5)
          .map(p => s"${p.peer.remoteAddress}=${p.peerInfo.maxBlockNumber}")
          .mkString(", ")
      ctx.log.info(
        s"SNAP pivot selection: bootstrapPivot=$bootstrapPivotBlock, visible peer heights (top 5) = [$topPeerHeights]"
      )

      // Core-geth approach: Calculate pivot from network height
      // pivot = networkHeight - pivotBlockOffset (e.g., 23M - 64 = ~23M)
      val (baseBlockForPivot, pivotSelectionSource) = networkBestBlockOpt match
        case Some(networkBestBlock) =>
          if networkBestBlock <= localBestBlock + snapSyncConfig.pivotBlockOffset then
            ctx.log.warn(
              s"Network best block ($networkBestBlock) is not significantly ahead of local ($localBestBlock)"
            )
            ctx.log.warn("This may indicate limited peer connectivity or already synced state")
          // Always use network block when available, regardless of gap size
          // This ensures we sync to the actual chain tip, not just our local view
          // Reset bootstrap retry state since we have peers
          bootstrapRetryCount = 0
          (networkBestBlock, NetworkPivot)
        case None =>
          // No peers available yet - fall back to local best block
          ctx.log.warn("No peers available for pivot selection, using local best block")
          ctx.log.warn("SNAP sync may select a suboptimal pivot - will retry if peers become available")
          (localBestBlock, LocalPivot)

      val rawPivot = baseBlockForPivot - snapSyncConfig.pivotBlockOffset
      val pivotBlockNumber = rawPivot.max(minPivotHint)
      if pivotBlockNumber > rawPivot then
        ctx.log.warn(
          s"SNAP pivot raised from $rawPivot to $pivotBlockNumber to satisfy MinPivotBlock constraint " +
            s"(regular sync was stuck near that block)"
        )

      ctx.log.info(
        s"SNAP pivot selection: localBest=$localBestBlock, networkBest=${networkBestBlockOpt.getOrElse("none")}, " +
          s"base=$baseBlockForPivot (source=${pivotSelectionSource.name}), offset=${snapSyncConfig.pivotBlockOffset}, " +
          s"pivot=$pivotBlockNumber"
      )

      // Core-geth behavior: If chain height <= pivot offset, use genesis as pivot
      // This allows SNAP sync to start immediately from any height
      if baseBlockForPivot <= snapSyncConfig.pivotBlockOffset then
        // IMPORTANT: Only apply the "start from genesis" behavior when we actually know
        // the network height (i.e., we have peers). If we have no peers, the network height
        // is unknown and treating it as 0 will cause us to request SNAP data for the genesis
        // state root, which most peers won't serve.
        pivotSelectionSource match
          case CLDrivenPivot =>
            // Unreachable: CLDrivenPivot returns earlier in startSnapSync. Defensive no-op.
            ctx.log.warn("Unexpected CLDrivenPivot reaching TD-based pivot path; ignoring")
            break(bootstrapping())
          case LocalPivot =>
            bootstrapRetryCount += 1
            checkBootstrapRetryTimeout("no peers, network height unknown") match
              case Some(b) => break(b); case None =>
            val delay = bootstrapRetryDelay
            ctx.log.warn(
              s"No peers available yet; network height is unknown. Retrying in $delay (attempt $bootstrapRetryCount)"
            )

            timers.cancel(BootstrapCheckKey)
            timers.startSingleTimer(BootstrapCheckKey, RetrySnapSyncStart, delay)
            break(bootstrapping())

          case NetworkPivot =>
            // Guard: if ALL snap peers have maxBlockNumber=0, heights haven't been populated yet.
            // ETH/63-68 peers initialize to 0 and only update on BlockHeaders/NewBlockHashes.
            // ETH/69 peers may also show 0 if the remote peer is also initializing.
            // Treat this as "heights unknown" — retry rather than committing to genesis.
            // go-ethereum: findBestPeer() requires bestPeer.head > 0 before pivot selection.
            if snapPeersForPivot.forall(_.peerInfo.maxBlockNumber == 0) then
              bootstrapRetryCount += 1
              checkBootstrapRetryTimeout("snap peers present but all heights unknown") match
                case Some(b) => break(b); case None =>
              val delay = 2.seconds
              ctx.log.info(
                s"[SNAP] ${snapPeersForPivot.size} snap peer(s) connected but all heights unknown — " +
                  s"waiting for ETH status exchange. Retrying in $delay (attempt $bootstrapRetryCount)."
              )
              timers.cancel(BootstrapCheckKey)
              timers.startSingleTimer(BootstrapCheckKey, RetrySnapSyncStart, delay)
              break(bootstrapping())
          // else: ETH/69 peers genuinely confirmed low network height — proceed to genesis sync

        ctx.log.info("=" * 80)
        ctx.log.info("🚀 SNAP Sync Starting from Genesis")
        ctx.log.info("=" * 80)
        ctx.log.info(s"Network height: $baseBlockForPivot blocks")
        ctx.log.info(s"Height below minimum threshold (${snapSyncConfig.pivotBlockOffset} blocks)")
        ctx.log.info(s"Using genesis (block 0) as pivot for early chain bootstrap")
        ctx.log.info("SNAP sync will effectively perform full sync for initial blocks")
        ctx.log.info("=" * 80)

        // Use genesis block as pivot (like core-geth does)
        blockchainReader.getBlockHeaderByNumber(0) match
          case Some(genesisHeader) =>
            pivotBlock = Some(BigInt(0))
            stateRoot = Some(genesisHeader.stateRoot)
            appStateStorage
              .putSnapSyncPivotBlock(0)
              .and(appStateStorage.putSnapSyncStateRoot(genesisHeader.stateRoot.value))
              .commit()
            updateBestBlockForPivot(genesisHeader, BigInt(0))

            SNAPSyncMetrics.setPivotBlockNumber(0)

            // Reset bootstrap retry state
            bootstrapRetryCount = 0

            // Start account range sync with genesis state root
            currentPhase = AccountRangeSync
            startAccountRangeSync(genesisHeader.stateRoot)
            break(syncing())

          case None =>
            ctx.log.error("Genesis block header not available - cannot start SNAP sync")
            break(enterDormantMode("genesis block header not available"))

      // With network-based pivot and 64-block offset, pivotBlockNumber should always be > 0
      // The genesis special case (above) handles when baseBlockForPivot <= 64
      // This remaining code handles the normal case where we have a valid pivot > localBestBlock

      if pivotBlockNumber <= localBestBlock then
        // Sanity check: pivot must be ahead of local state
        ctx.log.warn("=" * 80)
        ctx.log.warn("⚠️  SNAP Sync Pivot Issue Detected")
        ctx.log.warn("=" * 80)
        ctx.log.warn(s"Calculated pivot ($pivotBlockNumber) is not ahead of local state ($localBestBlock)")
        ctx.log.warn(
          s"Pivot source: ${pivotSelectionSource.name}, base block: $baseBlockForPivot, offset: ${snapSyncConfig.pivotBlockOffset}"
        )

        // LocalPivot is only used when no peers are available (see match expression above)
        // This check determines whether to retry for peers or transition to regular sync
        pivotSelectionSource match
          case CLDrivenPivot =>
            // Unreachable: CLDrivenPivot returns earlier in startSnapSync. Defensive no-op.
            ctx.log.warn("Unexpected CLDrivenPivot in TD-based pivot-staleness check; ignoring")
            break(bootstrapping())
          case LocalPivot =>
            // No peers available - schedule retry with backoff
            bootstrapRetryCount += 1
            checkBootstrapRetryTimeout("no peers for pivot selection") match
              case Some(b) => break(b); case None =>
            val delay = bootstrapRetryDelay
            ctx.log.warn(s"No peers available for pivot selection. Retrying in $delay (attempt $bootstrapRetryCount)")

            timers.cancel(BootstrapCheckKey)
            timers.startSingleTimer(BootstrapCheckKey, RetrySnapSyncStart, delay)
            break(bootstrapping())

          case NetworkPivot =>
            // Pivot is at or behind local state - this means we're already synced or very close
            // Fall back to regular sync to catch up the remaining blocks
            ctx.log.warn("Pivot block is not ahead of local state - likely already synced")
            ctx.log.warn("Transitioning to regular sync for final block catch-up")
            syncController ! Done // Signal completion, which will transition to regular sync
            break(bootstrapping())
      else
        // Pivot is valid and ahead of local state

        // Even if we have a local header for this block number, fetch the pivot header from the network
        // to avoid starting SNAP from a stale/reorged or otherwise non-canonical header.
        // This is a header-only bootstrap.
        pivotSelectionSource match
          case CLDrivenPivot =>
            // Unreachable: CLDrivenPivot returns earlier in startSnapSync. Defensive no-op.
            ctx.log.warn("Unexpected CLDrivenPivot in TD pivot-bootstrap branch; ignoring")
            break(bootstrapping())
          case NetworkPivot =>
            ctx.log.info(s"Fetching pivot header from network for block $pivotBlockNumber before starting SNAP")
            appStateStorage.putSnapSyncBootstrapTarget(pivotBlockNumber).commit()
            syncController ! StartRegularSyncBootstrap(pivotBlockNumber)
            break(bootstrapping())
          case LocalPivot =>
          // Fall through to local-header availability checks below

        // Check if we have the pivot block header locally
        blockchainReader.getBlockHeaderByNumber(pivotBlockNumber) match
          case Some(header) =>
            // Pivot header is available - proceed with SNAP sync
            pivotBlock = Some(pivotBlockNumber)
            stateRoot = Some(header.stateRoot)
            appStateStorage
              .putSnapSyncPivotBlock(pivotBlockNumber)
              .and(appStateStorage.putSnapSyncStateRoot(header.stateRoot.value))
              .commit()
            updateBestBlockForPivot(header, pivotBlockNumber)

            // Update metrics - pivot block
            SNAPSyncMetrics.setPivotBlockNumber(pivotBlockNumber)

            // Reset bootstrap retry state
            bootstrapRetryCount = 0

            ctx.log.info(s"Local pivot header available for block $pivotBlockNumber, starting SNAP sync")

            // Start account range sync
            currentPhase = AccountRangeSync
            startAccountRangeSync(header.stateRoot)
            break(syncing())

          case None =>
            // Pivot block header not available locally
            // This happens when we select a pivot based on network best block
            // but haven't synced that far yet

            pivotSelectionSource match
              case CLDrivenPivot =>
                // Unreachable: CLDrivenPivot returns earlier in startSnapSync. Defensive no-op.
                ctx.log.warn("Unexpected CLDrivenPivot in pivot-header-fetch branch; ignoring")
                break(bootstrapping())
              case NetworkPivot =>
                // We selected a network-based pivot but don't have the header yet
                // Need to bootstrap/sync to get closer to the pivot
                val targetForBootstrap = pivotBlockNumber

                ctx.log.info("=" * 80)
                ctx.log.info("🔄 SNAP Sync Pivot Header Not Available")
                ctx.log.info("=" * 80)
                ctx.log.info(s"Selected pivot: $pivotBlockNumber (based on network best block)")
                ctx.log.info(s"Local best block: $localBestBlock")
                ctx.log.info(s"Gap: ${pivotBlockNumber - localBestBlock} blocks")
                ctx.log.info("Need to sync headers/blocks to reach pivot point")
                ctx.log.info(s"Continuing regular sync to block $targetForBootstrap")
                ctx.log.info("Will automatically transition to SNAP sync once pivot is reached")
                ctx.log.info("=" * 80)

                // Store the bootstrap target
                appStateStorage.putSnapSyncBootstrapTarget(targetForBootstrap).commit()

                // Request regular sync to continue to the pivot point
                syncController ! StartRegularSyncBootstrap(targetForBootstrap)
                break(bootstrapping())

              case LocalPivot =>
                // Local pivot selected but header still not available
                bootstrapRetryCount += 1
                checkBootstrapRetryTimeout("pivot header not available") match
                  case Some(b) => break(b); case None =>
                val delay = bootstrapRetryDelay
                if bootstrapRetryCount == 1 then ctx.log.info("Waiting for pivot block header to become available...")
                ctx.log.info(
                  s"   Pivot block $pivotBlockNumber not ready yet (attempt $bootstrapRetryCount), retrying in $delay"
                )

                // Schedule a retry and store the cancellable for proper cleanup
                timers.cancel(BootstrapCheckKey)
                timers.startSingleTimer(BootstrapCheckKey, RetrySnapSyncStart, delay)

                break(bootstrapping())
      bootstrapping()
    } // end boundary

  /** Calculate exponential backoff delay for bootstrap retries. Backoff: 2s → 4s → 8s → 16s → 32s → 60s cap.
    */
  private def bootstrapRetryDelay: FiniteDuration =
    val exponent = math.min(bootstrapRetryCount, 5)
    val delaySeconds = BootstrapRetryBaseDelay.toSeconds * math.pow(2, exponent).toLong
    math.min(delaySeconds, BootstrapRetryMaxDelay.toSeconds).seconds

  /** Check if bootstrap retry has exceeded the maximum count. If so, enter dormant mode and yield `Some(<dormant
    * behavior>)` for the caller to `break` on; otherwise `None` (caller continues / stays). The dormant wake-up
    * restarts SNAP on a fresh pivot once a snap-capable peer is connected.
    */
  private def checkBootstrapRetryTimeout(context: String): Option[Behavior[Command]] =
    if bootstrapRetryCount >= MaxBootstrapRetries then
      Some(enterDormantMode(s"no peers found after $bootstrapRetryCount bootstrap retries ($context)"))
    else
      if bootstrapRetryCount > 0 && bootstrapRetryCount % 5 == 0 then
        ctx.log.info(
          s"Bootstrap retry diagnostics ($context): " +
            s"attempt=$bootstrapRetryCount/$MaxBootstrapRetries, " +
            s"handshakedPeers=${handshakedPeers.size}, " +
            s"snapCapable=${handshakedPeers.values.count(_.peerInfo.remoteStatus.supportsSnap)}"
        )
      None

  /** Record a critical failure and check whether the threshold is reached. Critical failures are those that indicate
    * SNAP sync cannot proceed on the current pivot.
    *
    * @param reason
    *   Description of the failure. Yields `true` if the retry limit is exceeded and the caller should enter dormant
    *   mode.
    */
  private def recordCriticalFailure(reason: String): Boolean =
    SNAPSyncMetrics.incrementSyncError()
    criticalFailureCount += 1
    accountsAtLastCriticalFailure = progressMonitor.currentProgress.accountsSynced
    ctx.log.warn(s"Critical SNAP sync failure ($criticalFailureCount/${snapSyncConfig.maxSnapSyncFailures}): $reason")

    if criticalFailureCount >= snapSyncConfig.maxSnapSyncFailures then
      ctx.log.error(s"SNAP sync failed ${criticalFailureCount} times — threshold reached")
      true
    else false

  /** Enter dormant mode: stop all coordinators and scheduled tasks, preserve all RocksDB data, and schedule a wake-up
    * with exponential backoff. SNAP's recovery when it cannot proceed — critical-failure threshold, bootstrap retries
    * exhausted, no snap-capable peer after the capability grace period. The wake-up restarts SNAP on a fresh pivot once
    * a snap-capable peer is connected (`dormantRetry`), and all persisted SNAP progress is kept.
    */
  private def enterDormantMode(reason: String): Behavior[Command] =
    currentPhase = Dormant

    ctx.log.warn(
      s"Entering dormant mode (attempt ${dormantRetryCount + 1}): $reason. " +
        s"All downloaded state preserved. Will retry after backoff."
    )

    timers.cancel(RequestAccountRanges)
    timers.cancel(RequestByteCodes)
    timers.cancel(RequestStorageRanges)
    timers.cancel(CheckDownloadStagnation)
    timers.cancel(RequestTrieNodeHealing)
    timers.cancel(BootstrapCheckKey)
    timers.cancel(PivotBootstrapRetryKey)
    timers.cancel(TuneRateTracker)
    timers.cancel(SnapCapabilityCheckKey)
    timers.cancel(EvictNonSnapPeers)

    accountRangeCoordinator.foreach(ctx.stop); accountRangeCoordinator = None
    bytecodeCoordinator.foreach(ctx.stop); bytecodeCoordinator = None
    storageRangeCoordinator.foreach(ctx.stop); storageRangeCoordinator = None
    trieNodeHealingCoordinator.foreach(ctx.stop); trieNodeHealingCoordinator = None

    requestTracker.clear()
    pendingPivotRefresh = None
    pivotProbeRequestId = None
    pendingProbeCommit = None
    proactiveRollNeedsProbe = false
    probeAttemptCount = 0

    dormantRetryCount += 1
    val backoffMs = math.min(
      DormantBaseDelay.toMillis * (1L << math.min(dormantRetryCount - 1, 10)),
      DormantMaxDelay.toMillis
    )
    val backoff = backoffMs.millis

    ctx.log.info(
      s"Dormant backoff: ${backoff.toSeconds}s " +
        s"(attempt $dormantRetryCount, criticalFailures=$criticalFailureCount)"
    )

    timers.cancel(DormantWakeUpKey)
    timers.startSingleTimer(DormantWakeUpKey, DormantWakeUp, backoff)

    dormantRetry()

  private def wakeFromDormant(): Behavior[Command] =
    ctx.log.info(
      s"Waking from dormant mode after $dormantRetryCount attempt(s). " +
        s"criticalFailureCount=$criticalFailureCount. Restarting SNAP sync with fresh pivot."
    )

    accountsComplete = false
    bytecodePhaseComplete = false
    storagePhaseComplete = false
    storagePhaseForceCompleted = false
    forceCompleteStorageSent = false
    bytecodesEstimatedTotal = 0L
    healingValidatedRoot = None

    pivotBlock = None
    stateRoot = None
    mptStorage = None
    currentPhase = Idle
    coordinatorGeneration += 1
    validationGeneration += 1
    validationInProgress = false
    consecutivePivotRefreshes = 0
    // A fresh retry budget: going dormant on exhausted bootstrap retries leaves the counter at the limit, so without
    // this the first retry after waking (a peer whose height is not known yet, say) would send it straight back.
    bootstrapRetryCount = 0

    startSnapSync()

  def dormantRetry(): Behavior[Command] =
    Behaviors
      .receiveMessage[Command] {
        // C1: rate-tracking peer-list arms
        case WrappedHandshakedPeers(peers) =>
          handleHandshakedPeersRateTracking(peers); Behaviors.same
        case WrappedPeerDisconnected(peerId) =>
          handlePeerDisconnectedDebounced(peerId); Behaviors.same
        case FlushPeerDisconnects =>
          flushPeerDisconnects(); Behaviors.same
        case PollHandshakedPeers =>
          pollHandshakedPeers(); Behaviors.same

        case DormantWakeUp =>
          val snapPeerCount = handshakedPeers.values.count(_.peerInfo.remoteStatus.supportsSnap)
          if snapPeerCount > 0 then
            ctx.log.info(s"Dormant wake-up: $snapPeerCount SNAP peer(s) available. Restarting SNAP sync.")
            wakeFromDormant()
          else
            ctx.log.info(s"Dormant wake-up: still no SNAP peers. Re-entering dormant with extended backoff.")
            enterDormantMode(s"no SNAP peers at wake-up (attempt $dormantRetryCount)")

        case hint: CLPivotHint =>
          handleCLPivotHint(hint, isStarting = false)
          Behaviors.same

        case GetStatus(replyTo) =>
          replyTo ! SyncProtocol.Status.Syncing(
            startingBlockNumber = appStateStorage.getSyncStartingBlock(),
            blocksProgress = SyncProtocol.Status.Progress(
              pivotBlock.getOrElse(BigInt(0)),
              pivotBlock.getOrElse(BigInt(0))
            ),
            stateNodesProgress = None
          )
          Behaviors.same

        case GetProgress(replyTo) =>
          replyTo ! progressMonitor.currentProgress
          Behaviors.same

        case _ => // silently drop stale coordinator messages, SNAP responses, etc.
          Behaviors.same
      }
      .receiveSignal { case (_, PostStop) =>
        onStop(); Behaviors.same
      }

  /** Evict non-SNAP outgoing peers when SNAP peer count is below threshold.
    *
    * Core-Geth completes full SNAP sync in ~5 minutes because it connects to SNAP-capable peers rapidly. Fukuii's peer
    * slots can fill with non-SNAP peers (ETH-only), leaving no room for SNAP-capable peers to connect. This method
    * actively disconnects the oldest non-SNAP outgoing peers to free connection slots, allowing discovery to fill them
    * with SNAP-capable peers.
    */
  private def evictNonSnapPeers(): Unit =
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
  private def startSnapPeerEviction(): Unit =
    if !snapPeerEvictionStarted then
      snapPeerEvictionStarted = true
      timers.startTimerWithFixedDelay(EvictNonSnapPeers, EvictNonSnapPeers, snapSyncConfig.snapPeerEvictionInterval)
      ctx.log.info(
        s"SNAP peer eviction started: checking every ${snapSyncConfig.snapPeerEvictionInterval.toSeconds}s, " +
          s"min ${snapSyncConfig.minSnapPeers} snap peers, max ${snapSyncConfig.maxEvictionsPerCycle} evictions/cycle"
      )

  private def startAccountRangeSync(rootHash: TrieRoot): Unit =
    // Before starting workers, check if any connected peer supports the snap/1 protocol.
    // If no peers support snap, the workers will send requests that are silently ignored,
    // stalling sync until the 3-minute stagnation watchdog fires. Instead, check upfront
    // and schedule a grace period for peers to connect before going dormant.
    val snapPeerCount = peersToDownloadFrom.count { case (_, p) =>
      p.peerInfo.remoteStatus.supportsSnap
    }

    if snapPeerCount == 0 then
      val gracePeriod = snapSyncConfig.snapCapabilityGracePeriod
      ctx.log.warn(s"No peers with snap/1 capability found ($peersToDownloadFrom.size peers connected)")
      ctx.log.warn(s"Scheduling snap capability check in ${gracePeriod.toSeconds}s before entering dormant mode")
      timers.startSingleTimer(SnapCapabilityCheckKey, CheckSnapCapability, gracePeriod)
    else
      // Use the full configured account concurrency regardless of startup peer count.
      // AccountRangeCoordinator's dispatch loop is asymmetric: peers serve ranges, not the
      // other way around, so 16 ranges with 4 peers just means each peer gets 4 ranges queued
      // and worker count scales up as more peers connect (see `maxWorkers` in
      // AccountRangeCoordinator). The previous `min(configured, peers@start)` froze the
      // task split at the initial-peer count for the whole sync — observed in production as
      // `4 workers/10 peers, 0/4 ranges done` permanently, leaving 6 peers' worth of
      // throughput on the table after the pool grew. PR #1278's ByteCode-worker-leak and
      // phase-guard fixes removed the original stagnation risk that motivated this cap.
      val effectiveConcurrency = snapSyncConfig.accountConcurrency.max(1)
      ctx.log.info(
        s"Starting account range sync with concurrency $effectiveConcurrency " +
          s"($snapPeerCount snap-capable peers at start, configured max ${snapSyncConfig.accountConcurrency})"
      )
      ctx.log.info("Using actor-based concurrency for account range sync")
      launchAccountRangeWorkers(rootHash, effectiveConcurrency)

      // Start periodic SNAP peer eviction to ensure we maintain enough SNAP-capable peers.
      // Non-SNAP peers filling all slots is the #1 cause of SNAP sync starvation.
      startSnapPeerEviction()

      // Start parallel chain download (headers, bodies, receipts from genesis to pivot)
      // Follows the Geth/Nethermind pattern of overlapping chain + state download.
      startChainDownloader()

      // Start proactive outbound dials to snap-server-peers so they are connected throughout ALL
      // phases (not only at StateHealing). core-geth's StaticNode dial to fukuii fails silently;
      // fukuii must initiate the connection itself.
      startSnapServerPeersScheduler()
    // end else (snapPeerCount > 0)

  private def launchAccountRangeWorkers(rootHash: TrieRoot, concurrency: Int): Unit =
    val effectiveConcurrency = if concurrency > 0 then concurrency else snapSyncConfig.accountConcurrency
    // Reset stagnation tracking for this phase.
    lastAccountProgressMs = System.currentTimeMillis()
    lastAccountTasksCompleted = 0
    lastAccountsDownloaded = 0

    // Safety valve: only preserve range progress if pivot hasn't drifted too far.
    // Content-addressed MPT data is valid across adjacent pivots (~256 blocks apart),
    // but large drift means the state may have changed significantly.
    val currentPivot = pivotBlock.getOrElse(BigInt(0))

    // Try disk recovery first (cross-process restart), then fall back to in-memory.
    // Primary source: SnapSyncProgressStorage (namespace 'p', JSON, account + storage cursors).
    // Migration fallback: AppStateStorage plain-text (namespace 's', account-only, written by older builds).
    if preservedRangeProgress.isEmpty then
      snapProgressStorage.readProgress(rootHash.value) match
        case Some(saved) if saved.accountCursors.nonEmpty =>
          val savedPivot = BigInt(saved.pivotBlock)
          if (currentPivot - savedPivot).abs <= MaxPreservedPivotDistance then
            val ranges = saved.accountCursors.flatMap { case (lastHex, nextHex) =>
              for
                lastBs <- Try(ByteString(Hex.decode(lastHex))).toOption
                nextBs <- Try(ByteString(Hex.decode(nextHex))).toOption
              yield lastBs -> nextBs
            }
            ctx.log.info(
              s"Recovered ${ranges.size} account ranges from SnapSyncProgressStorage " +
                s"(saved pivot=$savedPivot, current=$currentPivot, drift=${(currentPivot - savedPivot).abs})"
            )
            preservedRangeProgress = ranges
            preservedAtPivotBlock = Some(savedPivot)
          else if saved.accountCursors.nonEmpty then
            ctx.log.info(
              s"Discarding stale SnapSyncProgressStorage: pivot drifted ${(currentPivot - savedPivot).abs} blocks " +
                s"(>${MaxPreservedPivotDistance})"
            )
        case _ =>
          // Migration fallback: read legacy AppStateStorage plain-text format (one-time, older builds)
          appStateStorage.getSnapSyncProgress().foreach { saved =>
            deserializeSnapProgress(saved).foreach { case (savedPivot, savedRanges) =>
              if savedRanges.nonEmpty && (currentPivot - savedPivot).abs <= MaxPreservedPivotDistance then
                ctx.log.info(
                  s"Migrated ${savedRanges.size} account ranges from legacy AppStateStorage " +
                    s"(saved pivot=$savedPivot, current=$currentPivot, drift=${(currentPivot - savedPivot).abs})"
                )
                preservedRangeProgress = savedRanges
                preservedAtPivotBlock = Some(savedPivot)
                // Write to new storage immediately so subsequent restarts use the new format
                snapProgressStorage.writeAccountCursors(
                  rootHash.value,
                  savedPivot.toLong,
                  savedRanges.map { case (k, v) => k.toHex -> v.toHex }
                )
              else if savedRanges.nonEmpty then
                ctx.log.info(
                  s"Discarding stale legacy AppStateStorage progress: pivot drifted " +
                    s"${(currentPivot - savedPivot).abs} blocks (>${MaxPreservedPivotDistance})"
                )
            }
          }

    val resumeProgress: Map[ByteString, ByteString] = preservedAtPivotBlock match
      case Some(prevPivot) if (currentPivot - prevPivot).abs <= MaxPreservedPivotDistance =>
        if preservedRangeProgress.nonEmpty then
          ctx.log.info(
            s"Resuming ${preservedRangeProgress.size} account ranges from pivot $prevPivot " +
              s"(current pivot=$currentPivot, drift=${(currentPivot - prevPivot).abs} blocks)"
          )
        preservedRangeProgress
      case Some(prevPivot) =>
        ctx.log.info(
          s"Pivot drifted ${(currentPivot - prevPivot).abs} blocks (>${MaxPreservedPivotDistance}), " +
            s"clearing ${preservedRangeProgress.size} preserved ranges"
        )
        preservedRangeProgress = Map.empty
        preservedAtPivotBlock = None
        snapProgressStorage.clearProgress(rootHash.value)
        Map.empty
      case None =>
        Map.empty

    // Resuming cursors from a prior session ⇒ force the healing walk at completion (see flag decl).
    // Latch: once set, never cleared for the life of this process.
    if resumeProgress.nonEmpty then resumedStaleCursors = true

    val storage = getOrCreateMptStorage(currentPivot)

    accountRangeCoordinator = Some(
      ctx.spawn(
        Behaviors
          .supervise(
            actors.AccountRangeCoordinator(
              stateRoot = rootHash.value,
              networkPeerManager = networkPeerManager,
              requestTracker = requestTracker,
              mptStorage = storage,
              concurrency = effectiveConcurrency,
              snapSyncController = ctx.self,
              resumeProgress = resumeProgress,
              initialMaxInFlightPerPeer =
                5, // Full per-peer budget during AccountRangeSync (storage+bytecode deferred to 0)
              initialResponseBytes = snapSyncConfig.accountInitialResponseBytes,
              minResponseBytes = snapSyncConfig.accountMinResponseBytes,
              storageScheme = snapSyncConfig.storageScheme,
              pathNodeStorage = pathNodeStorageOpt
            )
          )
          .onFailure[Throwable](
            SupervisorStrategy.restartWithBackoff(1.second, 10.seconds, 0.2).withMaxRestarts(3)
          ),
        s"account-range-coordinator-$coordinatorGeneration",
        org.apache.pekko.actor.typed.DispatcherSelector.fromConfig("sync-dispatcher")
      )
    )

    // Start the coordinator
    accountRangeCoordinator.foreach(_ ! actors.AccountRangeCoordinator.StartAccountRangeSync(rootHash.value))

    // Periodically send peer availability notifications
    timers.startTimerWithFixedDelay(RequestAccountRanges, RequestAccountRanges, 1.second)

    scheduleStagnationChecks()

    // Schedule periodic rate tracker tuning (geth msgrate alignment: recalculate median RTT every 5s)
    if !timers.isTimerActive(TuneRateTracker) then
      timers.startTimerWithFixedDelay(TuneRateTracker, TuneRateTracker, 5.seconds)

    // Geth-aligned: create bytecode + storage coordinators upfront so they can receive
    // IncrementalContractData from the first account batch response. No phase gap.
    if bytecodeCoordinator.isEmpty then
      bytecodeCoordinator = Some(
        ctx.spawn(
          Behaviors
            .supervise(
              actors.ByteCodeCoordinator(
                evmCodeStorage = evmCodeStorage,
                networkPeerManager = networkPeerManager,
                requestTracker = requestTracker,
                batchSize = ByteCodeTask.DEFAULT_BATCH_SIZE,
                snapSyncController = ctx.self
              )
            )
            .onFailure[Throwable](
              SupervisorStrategy.restartWithBackoff(1.second, 10.seconds, 0.2).withMaxRestarts(3)
            ),
          s"bytecode-coordinator-$coordinatorGeneration",
          org.apache.pekko.actor.typed.DispatcherSelector.fromConfig("sync-dispatcher")
        )
      )
      // Start with empty codeHashes — tasks arrive incrementally via AddByteCodeTasks
      bytecodeCoordinator.foreach(_ ! actors.ByteCodeCoordinator.StartByteCodeSync(Seq.empty))

      timers.startTimerWithFixedDelay(RequestByteCodes, RequestByteCodes, 1.second)

    if storageRangeCoordinator.isEmpty then
      val storage = getOrCreateMptStorage(currentPivot)

      forceCompleteStorageSent = false
      storageRangeCoordinator = Some(
        ctx.spawn(
          Behaviors
            .supervise(
              actors.StorageRangeCoordinator(
                stateRoot = rootHash.value,
                networkPeerManager = networkPeerManager,
                requestTracker = requestTracker,
                mptStorage = storage,
                flatSlotStorage = flatSlotStorage,
                maxAccountsPerBatch = snapSyncConfig.storageBatchSize,
                maxInFlightRequests = snapSyncConfig.storageConcurrency,
                requestTimeout = snapSyncConfig.timeout,
                snapSyncController = ctx.self,
                // 2-per-peer during AccountRangeSync. Original design used 0 here to defer storage
                // dispatch until accounts completed (prevents stale-root timeouts triggering false
                // pivot refreshes). That assumption breaks on huge chains like sepolia: account
                // ranges never complete within a pivot serve window, so storage never gets a
                // non-zero budget and the queue grows unbounded until OOM. PR #1237's strike-counted
                // stateless detection + PR #1241's backpressure-release-on-pivot now make stale-root
                // timeouts a recoverable event rather than a failure cascade. Bump default ensures
                // storage can drain concurrently with account.
                initialMaxInFlightPerPeer = 2,
                initialResponseBytes = snapSyncConfig.storageInitialResponseBytes,
                minResponseBytes = snapSyncConfig.storageMinResponseBytes,
                deferredMerkleization = snapSyncConfig.deferredMerkleization,
                maxConcurrentStorageAccounts = snapSyncConfig.maxConcurrentStorageAccounts,
                snapProgressStorage = Some(snapProgressStorage),
                storageScheme = snapSyncConfig.storageScheme,
                pathNodeStorage = pathNodeStorageOpt
              )
            )
            .onFailure[Throwable](
              SupervisorStrategy.restartWithBackoff(1.second, 10.seconds, 0.2).withMaxRestarts(3)
            ),
          s"storage-range-coordinator-$coordinatorGeneration",
          org.apache.pekko.actor.typed.DispatcherSelector.fromConfig("sync-dispatcher")
        )
      )
      // Start with empty tasks — tasks arrive incrementally via AddStorageTasks
      storageRangeCoordinator.foreach(_ ! actors.StorageRangeCoordinator.StartStorageRangeSync(rootHash.value))

      timers.startTimerWithFixedDelay(RequestStorageRanges, RequestStorageRanges, 1.second)

    // ByteCode and storage start with budget=2 each during account phase (per-peer concurrent
    // limit). On huge chains (sepolia, ETH mainnet) account ranges never fully complete within
    // a pivot serve window, so the old budget=0 path left storage/bytecode queues to grow until
    // OOM. PR #1237's strike-counted demotion + PR #1241's backpressure-release-on-pivot make
    // stale-root timeouts recoverable rather than failure cascades. See PR #1252.
    // During AccountRangeSync: accounts=5, storage=2, bytecode=2 per peer.
    bytecodeCoordinator.foreach(_ ! actors.ByteCodeCoordinator.UpdateMaxInFlightPerPeer(2))
    storageRangeCoordinator.foreach(_ ! actors.StorageRangeCoordinator.UpdateMaxInFlightPerPeer(2))

    progressMonitor.startPhase(AccountRangeSync)

  private def requestAccountRanges(): Unit =
    // Notify coordinator of available peers. Filter does NOT require maxBlockNumber >= pivot
    // for the same reason as requestStorageRanges — stale-tracked-head false negatives starve
    // the coordinator. Stateless detection on the coordinator (strike-counted) handles peers
    // that actually can't serve the current pivot's state.
    accountRangeCoordinator.foreach { coordinator =>
      val snapPeers = peersToDownloadFrom.collect {
        case (_, peerWithInfo) if SNAPSyncController.servesSnapState(peerWithInfo.peerInfo) => peerWithInfo.peer
      }

      SNAPSyncMetrics.setSnapCapablePeers(snapPeers.size)

      if snapPeers.isEmpty then ctx.log.debug("No SNAP-capable peers available for account range requests")
      else
        snapPeers.foreach { peer =>
          coordinator ! actors.AccountRangeCoordinator.PeerAvailable(peer)
        }
    }

  private def requestByteCodes(): Unit =
    // Notify coordinator of available peers
    bytecodeCoordinator.foreach { coordinator =>
      val snapPeers = peersToDownloadFrom.collect {
        case (_, peerWithInfo) if SNAPSyncController.servesSnapState(peerWithInfo.peerInfo) => peerWithInfo.peer
      }

      if snapPeers.isEmpty then ctx.log.debug("No SNAP-capable peers available for bytecode requests")
      else
        snapPeers.foreach { peer =>
          coordinator ! actors.ByteCodeCoordinator.ByteCodePeerAvailable(peer)
        }
    }

  private def requestStorageRanges(): Unit =
    // Notify coordinator of available peers.
    //
    // Filter intentionally does NOT require maxBlockNumber >= pivot — that filter was the
    // cause of the sepolia 2026-05-14 storage stall. Sepolia's pivot advances every ~3 min
    // (CL-driven) while peer maxBlockNumber only refreshes every 5 min (PR #1238 best-block
    // re-probe). After ~1 pivot cycle, all peers' tracked block was below the new pivot →
    // every broadcast filtered them all out → coordinator received no StoragePeerAvailable
    // → knownAvailablePeers stayed empty → 100K storage tasks pinned forever.
    //
    // Stale maxBlockNumber is a *tracking* artifact, not a *capability* statement. The peer
    // has the same SNAP state regardless of what we believe their head is. If they actually
    // can't serve our pivot's state, the coordinator's strike-counted stateless detection
    // catches it (5 strikes → flagged stateless). Same applies to bytecode/account.
    storageRangeCoordinator.foreach { coordinator =>
      val snapPeers = peersToDownloadFrom.collect {
        case (_, peerWithInfo) if SNAPSyncController.servesSnapState(peerWithInfo.peerInfo) => peerWithInfo.peer
      }

      SNAPSyncMetrics.setSnapCapablePeers(snapPeers.size)

      if snapPeers.isEmpty then ctx.log.info("No SNAP-capable peers available for storage range requests")
      else
        snapPeers.foreach { peer =>
          coordinator ! actors.StorageRangeCoordinator.StoragePeerAvailable(peer)
        }
    }

  /** Start an async trie walk to discover missing nodes. Guards against concurrent walks. Uses streaming to emit
    * batches as they are found — critical for mainnet-scale tries where a full blocking walk can take hours before the
    * coordinator sees any work.
    */
  private def startTrieWalk(): Unit =
    if !trieWalkInProgress && currentPhase == StateHealing then
      trieWalkInProgress = true
      trieNodeHealingCoordinator.foreach(_ ! actors.TrieNodeHealingCoordinator.WalkStateChanged(true))
      stateRoot.foreach { root =>
        ctx.log.info("Starting trie walk to discover missing nodes for healing...")
        val storage = getOrCreateMptStorage(pivotBlock.getOrElse(BigInt(0)))
        val selfRef = ctx.self
        scala.concurrent
          .Future {
            val validator = new StateValidator(storage)
            validator.findMissingNodesStreaming(
              root.value,
              batchSize = 500,
              onBatch = batch => selfRef ! TrieWalkBatch(batch)
            )
          }(ec)
          .foreach {
            case Right(totalFound) => selfRef ! TrieWalkComplete(totalFound)
            case Left(error)       => selfRef ! TrieWalkFailed(error)
          }(ec)
      }
  // end if !trieWalkInProgress && currentPhase == StateHealing

  /** Layer 2: shared, lazily-built persisted-frontier handle for the healing coordinator. `None` (the default,
    * `healing-frontier-persistence = false`) keeps Layer-1 behaviour — no CF writes, always full DFS on restart. Reuses
    * the node's existing RocksDB DataSource (via `flatSlotStorage`); the CF auto-creates on open.
    */
  private lazy val healingFrontierStorageOpt: Option[HealingFrontierStorage] =
    // #4 (restored, spec-005): the pruned post-heal verification also needs the frontier store
    // (prunedHealVerification default true) — without it the first verification reverts to a full
    // ~90M-node trie walk. Dropped by #1384's stale base.
    if snapSyncConfig.healingFrontierPersistence || snapSyncConfig.prunedHealVerification then
      Some(new HealingFrontierStorage(flatSlotStorage.dataSource))
    else None

  private lazy val bfsQueueStorage: BfsQueueStorage =
    new RocksDbBfsQueueStorage(flatSlotStorage.dataSource, Namespaces.BfsQueueNamespace)

  private def startStateHealing(): Unit =
    // Guard: prevent duplicate healing coordinator creation (Bug 27).
    // Can happen when ByteCodeSyncComplete and StorageRangeSyncComplete arrive in quick
    // succession — both call checkAllDownloadsComplete() which calls startStateHealing().
    if trieNodeHealingCoordinator.isDefined then
      ctx.log.warn("startStateHealing called but healing coordinator already exists — ignoring duplicate")
    else
      trieWalkInProgress = false // Reset for fresh healing phase
      healRepegNoRootAttempts = 0 // spec 009 T014: fresh healing phase — reset the bounded re-peg last-resort budget
      ctx.log.info(s"Starting state healing with batch size ${snapSyncConfig.healingBatchSize}")

      stateRoot.foreach { root =>
        ctx.log.info("Using actor-based concurrency for state healing")

        val storage = getOrCreateMptStorage(pivotBlock.getOrElse(BigInt(0)))

        trieNodeHealingCoordinator = Some(
          ctx.spawn(
            Behaviors
              .supervise(
                actors.TrieNodeHealingCoordinator(
                  stateRoot = root.value,
                  networkPeerManager = networkPeerManager,
                  requestTracker = requestTracker,
                  mptStorage = storage,
                  batchSize = snapSyncConfig.healingBatchSize,
                  snapSyncController = ctx.self,
                  concurrency = snapSyncConfig.healingConcurrency,
                  visitedCap = snapSyncConfig.healingVisitedCap,
                  healingFrontierStorage = healingFrontierStorageOpt,
                  // #1319/spec-002 (restored): gate the coordinator's frontier-mirror writes +
                  // completeness markers ON, matching store presence (dropped by #1384's stale base).
                  frontierPersistenceEnabled = snapSyncConfig.healingFrontierPersistence,
                  traversalParallelism = snapSyncConfig.healingTraversalParallelism,
                  healingMinParallelism = snapSyncConfig.healingMinParallelism,
                  healingReservedCores = snapSyncConfig.healingReservedCores,
                  bfsQueueStorageOpt = Some(bfsQueueStorage),
                  storageScheme = snapSyncConfig.storageScheme,
                  pathNodeStorageOpt = pathNodeStorageOpt,
                  frontierHighWater = snapSyncConfig.healingFrontierHighWater,
                  frontierLowWater = snapSyncConfig.healingFrontierLowWater,
                  scopedHealVerification = snapSyncConfig.scopedHealVerification,
                  scopedHealMaxPaths = snapSyncConfig.scopedHealMaxPaths,
                  decoupledHealServeRoot = snapSyncConfig.decoupledHealServeRoot,
                  decoupledHealMaxAttemptsNoRefresh = snapSyncConfig.decoupledHealMaxAttemptsNoRefresh,
                  movingRootDeltaHeal = snapSyncConfig.movingRootDeltaHeal
                )
              )
              .onFailure[Throwable](
                SupervisorStrategy.restartWithBackoff(1.second, 10.seconds, 0.2).withMaxRestarts(3)
              ),
            s"trie-node-healing-coordinator-$coordinatorGeneration",
            org.apache.pekko.actor.typed.DispatcherSelector.fromConfig("sync-dispatcher")
          )
        )

        // Start the coordinator — give healing full per-peer budget (accounts/storage/bytecode done)
        trieNodeHealingCoordinator.foreach { coordinator =>
          coordinator ! actors.TrieNodeHealingCoordinator.StartTrieNodeHealing(root.value)
          coordinator ! actors.TrieNodeHealingCoordinator.UpdateMaxInFlightPerPeer(
            snapSyncConfig.healingMaxInFlightPerPeer
          )
          // Flush current snap peers immediately — the 0-second scheduler delay is async; an explicit
          // flush here ensures peers are available before any StartTrieNodeHealing dispatch attempt.
          peersToDownloadFrom.values
            .filter(p => SNAPSyncController.servesSnapState(p.peerInfo))
            .foreach(p => coordinator ! actors.TrieNodeHealingCoordinator.HealingPeerAvailable(p.peer))
        }

        // Periodically send peer availability notifications (cancel any existing scheduler first)
        startHealingRequestScheduler()

        // Ensure snap-server-peers scheduler is running (idempotent — already started at account sync).
        startSnapServerPeersScheduler()

        progressMonitor.startPhase(StateHealing)
      }
  // end else (healing coordinator not yet defined)

  /** ARCH-WALK-HEAL-INTERLEAVE: Create a healing coordinator BEFORE starting the trie walk. Nodes discovered
    * per-subtree (TrieWalkBatch) are fed to the coordinator immediately, so healing runs in parallel with the walk.
    * With root seeding + discoverMissingChildren, healing starts instantly from the root — the walk is validation-only.
    *
    * If coordinator already exists (e.g. still running after StateHealingComplete), just start the walk — the
    * coordinator is alive and will receive batch nodes.
    */
  private def startStateHealingWithInterleave(): Unit =
    if trieNodeHealingCoordinator.isDefined then
      // Coordinator still running — just start the validation walk
      startTrieWalk()
    else
      stateRoot match
        case Some(root) =>
          val storage = getOrCreateMptStorage(pivotBlock.getOrElse(BigInt(0)))
          trieNodeHealingCoordinator = Some(
            ctx.spawn(
              Behaviors
                .supervise(
                  actors.TrieNodeHealingCoordinator(
                    stateRoot = root.value,
                    networkPeerManager = networkPeerManager,
                    requestTracker = requestTracker,
                    mptStorage = storage,
                    batchSize = snapSyncConfig.healingBatchSize,
                    snapSyncController = ctx.self,
                    concurrency = snapSyncConfig.healingConcurrency,
                    visitedCap = snapSyncConfig.healingVisitedCap,
                    healingFrontierStorage = healingFrontierStorageOpt,
                    // #1319/spec-002 (restored): gate frontier-mirror writes + completeness markers
                    // ON, matching store presence (dropped by #1384's stale base).
                    frontierPersistenceEnabled = snapSyncConfig.healingFrontierPersistence,
                    traversalParallelism = snapSyncConfig.healingTraversalParallelism,
                    healingMinParallelism = snapSyncConfig.healingMinParallelism,
                    healingReservedCores = snapSyncConfig.healingReservedCores,
                    bfsQueueStorageOpt = Some(bfsQueueStorage),
                    storageScheme = snapSyncConfig.storageScheme,
                    pathNodeStorageOpt = pathNodeStorageOpt,
                    frontierHighWater = snapSyncConfig.healingFrontierHighWater,
                    frontierLowWater = snapSyncConfig.healingFrontierLowWater,
                    scopedHealVerification = snapSyncConfig.scopedHealVerification,
                    scopedHealMaxPaths = snapSyncConfig.scopedHealMaxPaths,
                    decoupledHealServeRoot = snapSyncConfig.decoupledHealServeRoot,
                    decoupledHealMaxAttemptsNoRefresh = snapSyncConfig.decoupledHealMaxAttemptsNoRefresh,
                    movingRootDeltaHeal = snapSyncConfig.movingRootDeltaHeal
                  )
                )
                .onFailure[Throwable](
                  SupervisorStrategy.restartWithBackoff(1.second, 10.seconds, 0.2).withMaxRestarts(3)
                ),
              s"trie-node-healing-coordinator-$coordinatorGeneration",
              org.apache.pekko.actor.typed.DispatcherSelector.fromConfig("sync-dispatcher")
            )
          )
          trieNodeHealingCoordinator.foreach { coordinator =>
            coordinator ! actors.TrieNodeHealingCoordinator.StartTrieNodeHealing(root.value)
            coordinator ! actors.TrieNodeHealingCoordinator.UpdateMaxInFlightPerPeer(
              snapSyncConfig.healingMaxInFlightPerPeer
            )
            peersToDownloadFrom.values
              .filter(p => SNAPSyncController.servesSnapState(p.peerInfo))
              .foreach(p => coordinator ! actors.TrieNodeHealingCoordinator.HealingPeerAvailable(p.peer))
          }
          startHealingRequestScheduler()
          ctx.log.info(
            s"[HEAL-INTERLEAVE] Healing coordinator created before walk — " +
              s"root=${root.value.take(8).toHex}, generation=$coordinatorGeneration"
          )
          startTrieWalk()
        case None =>
          ctx.log.warn("[HEAL-INTERLEAVE] stateRoot is None — walking only (no coordinator created)")
          startTrieWalk()

  /** BUG-HEAL-SCHED FIX: Always cancel the existing scheduler before creating a new one. Multiple code paths create
    * this scheduler; without cancel an orphaned scheduler fires every 1s in parallel with the new one.
    */
  private def startHealingRequestScheduler(): Unit =
    timers.cancel(RequestTrieNodeHealing)
    timers.startTimerWithFixedDelay(RequestTrieNodeHealing, RequestTrieNodeHealing, 1.second)

  // Start (or re-use) the snap-server-peers reconnect scheduler.
  // Idempotent: does nothing if already running. 15s initial delay lets inbound connections
  // complete STATUS exchange before we fire an outbound ConnectToPeer (avoids AlreadyConnected races).
  private def startSnapServerPeersScheduler(): Unit =
    if snapSyncConfig.snapServerPeers.nonEmpty && !snapServerPeersSchedulerStarted then
      snapServerPeersSchedulerStarted = true
      timers.startTimerWithFixedDelay(EnsureSnapServerPeersConnected, EnsureSnapServerPeersConnected, 30.seconds)

  private def requestTrieNodeHealing(): Unit =
    // Notify coordinator of available peers
    trieNodeHealingCoordinator.foreach { coordinator =>
      val snapPeers = peersToDownloadFrom.collect {
        case (_, peerWithInfo) if SNAPSyncController.servesSnapState(peerWithInfo.peerInfo) => peerWithInfo.peer
      }

      if snapPeers.isEmpty then ctx.log.debug("No SNAP-capable peers available for healing requests")
      else
        snapPeers.foreach { peer =>
          coordinator ! actors.TrieNodeHealingCoordinator.HealingPeerAvailable(peer)
        }
    }

  /** spec 004 T011/U1: ask the parent for a newest-servable serve root when (and only when) the current serve root has
    * aged > HealingServeRootMarginBlocks behind the network head and no request is already in flight. Reuses the
    * parent's RecentRoot/PivotHeaderBootstrap plumbing via a dedicated requester slot (T012), so it never contends with
    * StorageRecoveryActor's recent-root requester or mutates the walk root. No-op when decoupling is disabled, when not
    * healing, or when no network head / serve target can be computed (U2: keep current serve root rather than pushing
    * an empty one).
    */
  private def maybeRequestHealingServeRoot(): Unit =
    // spec 009 T009/C4 (moving-root re-peg trigger): the staleness MATH below is shared with the spec-004 serve-root
    // path; only the ACTION differs by flag. Flag OFF (decoupledHealServeRoot): push a HealingServeRootRefresh (moves
    // the coordinator's SERVE root only; the walk root and the controller's stateRoot/pivot are untouched). Flag ON
    // (movingRootDeltaHeal): re-peg the SINGLE heal root via refreshPivotInPlace → completePivotRefreshWithStateRoot →
    // HealingPivotRefreshed, moving the controller's stateRoot/pivotBlock AND the coordinator's heal root IN LOCKSTEP
    // against a canonical header stateRoot. The pendingPivotRefresh.isEmpty guard below also prevents stacking a second
    // re-peg header bootstrap while one is in flight.
    if (snapSyncConfig.decoupledHealServeRoot || snapSyncConfig.movingRootDeltaHeal) &&
      currentPhase == StateHealing &&
      trieNodeHealingCoordinator.isDefined &&
      !healingServeRootRequestInFlight &&
      // Don't contend with a pivot-refresh header bootstrap: while one is pending the parent is (or is about to be)
      // in runningPivotHeaderBootstrap, where a concurrent serve-root bootstrap completion could be mis-routed.
      // The request will fire on a later tick once the refresh settles.
      pendingPivotRefresh.isEmpty
    then
      currentNetworkBestFromSnapPeers().foreach { networkBest =>
        // Target a root inside peers' serve window: networkBest − margin (≥1). recentRootTarget caps at 1.
        val serveTarget = SyncController.recentRootTarget(Seq(networkBest), HealingServeRootMarginBlocks)
        serveTarget.foreach { target =>
          // Refresh cadence (U1): a serve root is fetched at `networkBest − margin`, so it STARTS `margin` blocks
          // behind the head. We refresh only once it has drifted a FULL window further back — i.e. when it is
          // > 2×margin behind the current head — giving ~margin blocks of runway between the ~1s peer round-trips
          // (never per-block). An unset lastHealingServeRootBlock means the coordinator is still fetching against
          // the walk root (coupled), so engage immediately.
          val stale = lastHealingServeRootBlock match
            case Some(lastBlock) => (networkBest - lastBlock) > (HealingServeRootMarginBlocks * 2)
            case None            => true
          if stale then
            if snapSyncConfig.movingRootDeltaHeal then
              // spec 009 T009/C4: re-peg the single heal root to a fresh served root. refreshPivotInPlace selects a
              // canonical header (networkBest − margin), fetches it, and emits HealingPivotRefreshed via
              // completePivotRefreshWithStateRoot — moving completeness AND fetch (one root) while RETAINING every
              // persisted verified node and resetting verificationPassComplete so a fresh pruned descent gates
              // completion against the new root. Record the block so the cadence (≤ once per window) matches the
              // serve-root path; the actual root lands when the refresh settles.
              lastHealingServeRootBlock = Some(target)
              ctx.log.info(
                s"[HEAL-REPEG] Heal root stale (networkBest=$networkBest, target=$target, " +
                  s"margin=$HealingServeRootMarginBlocks, lastRepegBlock=${lastHealingServeRootBlock.getOrElse("none")}) " +
                  s"— re-pegging the single heal root via refreshPivotInPlace (spec 009 moving-root delta heal)."
              )
              refreshPivotInPlace("spec009 moving-root re-peg: heal root stale")
            else
              healingServeRootRequestInFlight = true
              ctx.log.info(
                s"[HEAL-SERVE-ROOT] Requesting newest-servable serve root: networkBest=$networkBest target=$target " +
                  s"(margin=$HealingServeRootMarginBlocks, lastServeBlock=${lastHealingServeRootBlock.getOrElse("none")}). " +
                  s"Routing via parent RecentRoot bootstrap."
              )
              syncController ! SNAPSyncController.RequestHealingServeRoot
        }
      }

  /** Select the best probe target peer for pivot readiness probing.
    *
    * Prefers snap-server-peers (configured local clients — Besu, core-geth) because they have archive state and minimal
    * latency, making them the most reliable probe targets. Falls back to the highest-block SNAP-capable external peer
    * when no snap-server-peer is connected.
    */
  private def bestSnapProbeTarget() =
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

  /** Reconnect to any configured snap-server-peers that are not currently connected.
    *
    * snap-server-peers are static SNAP-serving nodes (e.g. local Besu with --snapsync-server-enabled) that are the only
    * source of ETC GetTrieNodes responses. They may disconnect after the storage phase. This method ensures
    * reconnection so they are in the peer pool when healing dispatches requests.
    */
  private def ensureSnapServerPeersConnected(): Unit =
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

  /** Spawn the account-trie validation walk on the dedicated dispatcher.
    *
    * Async: results come back as `ValidateAccountTrieResult(generation, ...)` self-messages so the actor mailbox stays
    * responsive during the multi-minute walk. `onComplete` (not `.foreach`) ensures every Future outcome — including a
    * throw inside `validatorFactory(storage)` — produces a message; otherwise the in-progress flag could stick.
    */
  private def spawnAccountValidation(generation: Long, expectedRoot: ByteString, pivot: BigInt): Unit =
    val storage = getOrCreateMptStorage(pivot)
    val selfRef = ctx.self
    val start = System.currentTimeMillis()
    scala.concurrent
      .Future {
        val v = validatorFactory(storage)
        (v.validateAccountTrie(expectedRoot), System.currentTimeMillis() - start)
      }(snapValidationEc)
      .onComplete {
        case scala.util.Success((result, elapsed)) =>
          selfRef ! ValidateAccountTrieResult(generation, result, elapsed)
        case scala.util.Failure(e) =>
          selfRef ! ValidateAccountTrieResult(generation, Left(e.getMessage), -1L)
      }(snapValidationEc)

  private def spawnStorageValidation(generation: Long, expectedRoot: ByteString, pivot: BigInt): Unit =
    val storage = getOrCreateMptStorage(pivot)
    val selfRef = ctx.self
    val start = System.currentTimeMillis()
    scala.concurrent
      .Future {
        val v = validatorFactory(storage)
        (v.validateAllStorageTries(expectedRoot), System.currentTimeMillis() - start)
      }(snapValidationEc)
      .onComplete {
        case scala.util.Success((result, elapsed)) =>
          selfRef ! ValidateStorageTriesResult(generation, result, elapsed)
        case scala.util.Failure(e) =>
          selfRef ! ValidateStorageTriesResult(generation, Left(e.getMessage), -1L)
      }(snapValidationEc)

  private def validateState(): Unit =
    if !snapSyncConfig.stateValidationEnabled then
      ctx.log.info("State validation disabled, skipping...")
      ctx.self ! StateValidationComplete
    else if validationInProgress then
      ctx.log.info("validateState called while validation is already in progress; ignoring")
    else
      (stateRoot, pivotBlock) match
        case (Some(expectedRoot), Some(pivot)) =>
          // #1188: short-circuit when the round-2 healing trie walk just verified the same root.
          // The walk visits every node in the account trie + every storage trie via DFS — same
          // work `validateAccountTrie + validateAllStorageTries` would redo. Belt-and-suspenders:
          // only honoured when captured root *equals* current `stateRoot`, so any pivot refresh
          // or restart naturally invalidates the signal (root changes → no match → full validation).
          if healingValidatedRoot.contains(expectedRoot) then
            ctx.log.info(
              s"Skipping redundant state validation — healing trie walk verified the entire " +
                s"account+storage trie against ${expectedRoot.value.take(8).toHex} (clean signal)"
            )
            // Consume the signal so a re-entry (e.g. after a future healing-recovery cycle that
            // didn't finish with a clean walk) doesn't reuse a stale positive.
            healingValidatedRoot = None
            ctx.self ! StateValidationComplete
          else
            validationInProgress = true
            validationGeneration += 1
            val gen = validationGeneration
            ctx.log.info(s"Validating state against expected root: ${expectedRoot.value.take(8).toHex} (gen=$gen)")
            spawnAccountValidation(gen, expectedRoot.value, pivot)
        case _ =>
          ctx.log.error("Missing state root or pivot block for validation — cannot complete sync")
          validationInProgress = false
  // end else (stateValidationEnabled && !validationInProgress)

  private def currentNetworkBestFromSnapPeers(): Option[BigInt] =
    val bootstrapPivotBlock = appStateStorage.getBootstrapPivotBlock()
    // Peers whose STATUS hasn't arrived yet have maxBlockNumber=0 — exclude them, otherwise
    // a fresh-startup race returns Some(0) and the caller commits to a genesis pivot before
    // any real peer height is known.
    peersToDownloadFrom.values.toList
      .filter(_.peerInfo.remoteStatus.supportsSnap)
      .filter(_.peerInfo.maxBlockNumber > 0)
      .filter(p => bootstrapPivotBlock == 0 || p.peerInfo.maxBlockNumber >= bootstrapPivotBlock)
      .sortBy(_.peerInfo.maxBlockNumber)(bigIntReverseOrdering)
      .headOption
      .map(_.peerInfo.maxBlockNumber)

  /** Refresh the pivot block and state root without destroying coordinators.
    *
    * Unlike restartSnapSync() which discards all progress, this method:
    *   1. Selects a fresher pivot from current network best 2. Updates internal pivot/stateRoot tracking 3. Sends
    *      PivotRefreshed to the active coordinator 4. Resets stagnation timer so the watchdog doesn't trigger
    *
    * Downloaded trie nodes are content-addressed (keyed by keccak256 hash), so ~99.9% remain valid across pivot
    * changes. Root mismatch (if any) is resolved during the healing phase.
    */
  private def refreshPivotInPlace(reason: String): Unit =
    ctx.log.info(s"Refreshing pivot in-place: $reason")

    // CL-anchored pivot selection for post-merge chains (geth's BeaconSync pattern).
    // Mirrors what startSnapSync does at line 1769 — the CL hint represents authoritative
    // chain tip from forkchoiceUpdated, NOT peer-reported best.
    //
    // Peer `maxBlockNumber` is unreliable on post-merge chains:
    //   - NewBlock gossip is dead (the historic update path)
    //   - ETH/69 BlockRangeUpdate only fires on a minority of peers
    //   - PR #1238's best-block probe only re-checks every 5 min
    //
    // Sepolia 2026-05-15 observation: 2-4 peers all reported `maxBlockNumber=10853243`
    // for ~30 min while the actual chain head was at 10854389+. Selecting from
    // peer-reported best gave us a pivot 1146 blocks BEHIND the CL head, peers couldn't
    // serve that root either, and we looped forever.
    //
    // Pre-merge chains keep peer-reported best because there's no authoritative tip.
    val clHeadNumber: Option[BigInt] =
      if isPoSChain then clPivotHint.flatMap(_.knownHeader).map(_.number.value) else None

    val newPivotOpt: Option[BigInt] = clHeadNumber match
      case Some(clHead) =>
        // Post-merge: pivot = CL head - offset. Strict forward-only: never accept a
        // pivot below our current one (which could happen if the CL hint regressed,
        // though that should not happen for forkchoiceUpdated).
        val target = clHead - snapSyncConfig.pivotBlockOffset
        val currentPivot = pivotBlock.getOrElse(BigInt(0))
        if target <= currentPivot then
          ctx.log.info(
            s"CL-based pivot $target not strictly newer than current $currentPivot " +
              s"(CL head=$clHead, offset=${snapSyncConfig.pivotBlockOffset}). Skipping refresh."
          )
          None
        else
          ctx.log.info(s"Selected CL-anchored pivot $target (CL head=$clHead, current=$currentPivot)")
          Some(target).filter(_ > 0)

      case None =>
        // Pre-merge fallback: peer-reported best subject to freshness floor.
        currentNetworkBestFromSnapPeers()
          .filter { networkBest =>
            SNAPSyncController.pivotPassesFreshnessFloor(
              networkBest = networkBest,
              clHeadNumber = clHeadNumber,
              maxStaleness = snapSyncConfig.maxPivotStalenessBlocks
            ) match
              case Right(()) => true
              case Left(floor) =>
                ctx.log.warn(
                  s"Rejecting stale SNAP peer best=$networkBest as pivot — below CL-anchored " +
                    s"freshness floor=$floor (CL head=${clHeadNumber.getOrElse("?")}, " +
                    s"maxPivotStalenessBlocks=${snapSyncConfig.maxPivotStalenessBlocks}). " +
                    "Waiting for a fresher SNAP-capable peer."
                )
                false
          }
          // Back the roll target off the live tip by at least SnapServeWindowMargin so the
          // new root is one peers have actually indexed. pivotBlockOffset stays a floor (a
          // larger configured offset still wins). Rolling to networkBest exactly (offset=0)
          // froze the ETC pivot on 2026-06-01 — peers emit "not indexed" for the tip root.
          // NOTE: the post-merge (CL-anchored) branch above has the same latent issue at
          // offset=0; deferred — it fires rarely and has its own freshness-floor handling.
          .map(networkBest => networkBest - BigInt(snapSyncConfig.pivotBlockOffset).max(SnapServeWindowMargin))
          .filter(_ > 0)

    if newPivotOpt.isEmpty then
      // spec 009 T014 (Moving-Root Delta Heal — bounded re-peg last-resort). Under `movingRootDeltaHeal`, a re-peg
      // during StateHealing that finds NO suitable served root counts against a budget; once exhausted, take the SAME
      // fail-SAFE lazy-heal handoff the (now-rare) HealingRootUnservable signal uses — completeSnapSync() → on-demand
      // GetTrieNodes during block execution. CONSTRAINT 1 (never fail-OPEN): completeSnapSync → finalizeSnapSync STILL
      // enforces the snapStateRoot == pivotHeader.stateRoot anchor guard, so an incomplete state CANNOT finalize (a
      // mismatch escalates to HealingImpossible/restart) — this is the lazy on-demand-heal handoff (FR-001/FR-008), not
      // a forced completion. CONSTRAINT 2 (return-Behavior): completeSnapSync() returns a narrowing Behavior[Command]
      // but refreshPivotInPlace is Unit; exactly as the HealingRootUnservable handler does, we call it for its side
      // effects (currentPhase=Completed, syncController ! Done, child teardown) and discard the returned Behavior.
      // Outside healing or flag OFF: the unbounded 30s-retry stays byte-identical (SNAP peers are intermittent on ETC).
      if snapSyncConfig.movingRootDeltaHeal && currentPhase == StateHealing then
        healRepegNoRootAttempts += 1
        if healRepegNoRootAttempts >= MaxHealRepegNoRootAttempts then
          ctx.log.warn(
            s"[HEAL-REPEG] No servable root for $healRepegNoRootAttempts consecutive re-peg attempts (budget " +
              s"$MaxHealRepegNoRootAttempts exhausted, ~${MaxHealRepegNoRootAttempts * 30}s). Taking the fail-safe " +
              s"lazy-heal handoff (completeSnapSync) — missing nodes fetched on-demand via GetTrieNodes during block " +
              s"execution; the anchor guard still gates finalization. NOT a false completion."
          )
          timers.cancel(PivotBootstrapRetryKey)
          healRepegNoRootAttempts = 0
          completeSnapSync()
        else
          ctx.log.warn(
            s"Cannot re-peg heal root: no suitable SNAP peers available (attempt " +
              s"$healRepegNoRootAttempts/$MaxHealRepegNoRootAttempts). Scheduling retry in 30s."
          )
          timers.cancel(PivotBootstrapRetryKey)
          timers.startSingleTimer(PivotBootstrapRetryKey, RetryPivotRefresh, 30.seconds)
      else
        ctx.log.warn(
          "Cannot refresh pivot: no suitable SNAP peers available. Scheduling retry in 30s."
        )
        // Don't restart or fallback — SNAP peers are intermittent on ETC mainnet.
        // The serve window is ~28 min; peers will reappear when new blocks are mined.
        // Restarting can't help with no peers, and it destroys all downloaded trie data.
        timers.cancel(PivotBootstrapRetryKey)
        timers.startSingleTimer(PivotBootstrapRetryKey, RetryPivotRefresh, 30.seconds)
    else
      val newPivotBlock = newPivotOpt.get
      val newPivotHeaderOpt = blockchainReader.getBlockHeaderByNumber(newPivotBlock)

      if newPivotHeaderOpt.isEmpty then
        // Header not available locally — request it from a peer via the bootstrap mechanism.
        // The coordinator stays alive (all peers are stateless, so no dispatch will happen).
        // When BootstrapComplete arrives in the syncing state, the refresh is completed.
        ctx.log.info(s"Pivot header for block $newPivotBlock not available locally. Requesting header bootstrap...")
        // Pause chain download to free up peers for the pivot header bootstrap
        chainDownloader.foreach(_ ! ChainDownloader.Pause)
        pendingPivotRefresh = Some((newPivotBlock, reason))
        syncController ! StartRegularSyncBootstrap(newPivotBlock)
        // Reset account stagnation timer while we wait for the header.
        // Note: do NOT reset lastStorageProgressMs here — if storage has been stalled with
        // no actual slot progress, the stagnation timer must continue counting so it can
        // eventually trigger a full restart rather than cycling pivots indefinitely.
        lastAccountProgressMs = System.currentTimeMillis()
      else completePivotRefreshWithStateRoot(newPivotBlock, newPivotHeaderOpt.get, reason)

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
  private def calibratePivotTD(pivotBlockNumber: BigInt): Option[BigInt] =
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

  /** Update best block info and chain weight so ETH status handshake advertises the correct forkId.
    *
    * Without this, getBestBlockNumber() returns 0 (genesis) during SNAP sync, causing peers to reject us with
    * incompatible forkId (e.g. Frontier vs Spiral). This stores the pivot header, chain weight, and best block info so
    * that createStatusMsg() in EthNodeStatus68ExchangeState can build a valid status message referencing the pivot.
    */
  private def updateBestBlockForPivot(
      header: BlockHeader,
      pivotBlockNumber: BigInt
  ): Unit =
    val pivotHash = header.hash
    // Priority:
    //   (1) Real cumulative TD already in DB (ChainDownloader has backfilled it) — never overwrite.
    //   (2) Peer-interpolated TD from a connected ETH68 peer — accurate to <0.0005% for typical gaps.
    //       The old ETH68_BOOTSTRAP approach stored the peer's tip TD directly, inflating every
    //       subsequent block by (peerHead − pivot) × avgDifficulty. Interpolation avoids that by
    //       scaling to the pivot height.
    //   (3) Block-number proxy — last resort when no ETH68 peers are connected yet (very early startup).
    val (estimatedTotalDifficulty, tdSource) =
      blockchainReader
        .getChainWeightByHash(pivotHash)
        .map(cw => (cw.totalDifficulty.value, "REAL_PIVOT_TD"))
        .orElse(calibratePivotTD(pivotBlockNumber).map(td => (td, "PEER_INTERPOLATED_TD")))
        .getOrElse {
          val genesisTD: BigInt = blockchainReader
            .getChainWeightByHash(blockchainReader.genesisHeader.hash)
            .map(_.totalDifficulty.value)
            .getOrElse(blockchainReader.genesisHeader.difficulty.value)
          val proxy: BigInt =
            if pivotBlockNumber == BigInt(0) then header.difficulty.value else pivotBlockNumber.max(genesisTD)
          (proxy, "BLOCK_NUMBER_PROXY")
        }
    blockchainWriter.storeBlockHeader(header).commit()
    blockchainWriter
      .storeChainWeight(pivotHash, ChainWeight.totalDifficultyOnly(estimatedTotalDifficulty))
      .commit()
    // Always advance the self-reported best-block pointer so STATUS messages show the correct
    // pivot block number.
    appStateStorage
      .putBestBlockInfo(com.chipprbots.ethereum.domain.appstate.BlockInfo(pivotHash.value, pivotBlockNumber))
      .commit()
    ctx.log.info(
      s"Updated best block for ETH status: block=$pivotBlockNumber, hash=${pivotHash.value.toHex.take(16)}..., " +
        s"estimatedTD=$estimatedTotalDifficulty (source=$tdSource)"
    )

  /** Complete the pivot refresh once we have the header (either from local storage or bootstrapped from peer). */
  private def completePivotRefreshWithStateRoot(
      newPivotBlock: BigInt,
      newPivotHeader: BlockHeader,
      reason: String
  ): Unit =
    // Async-validation safety: if a validation Future is in flight, the new
    // pivot would invalidate its assumptions about (root, pivot). Refuse the
    // refresh during StateValidation; the validation will run to completion
    // against the original root and the controller will re-enter healing if
    // needed. (PivotStateUnservable normally only fires in earlier phases, but
    // a stray BootstrapComplete from a slow peer can still arrive here.)
    if currentPhase == StateValidation then
      ctx.log.info(
        s"Ignoring pivot refresh during StateValidation: $reason " +
          s"(would mutate root from ${stateRoot.map(_.value.take(4).toHex).getOrElse("none")} to ${newPivotHeader.stateRoot.value.take(4).toHex})"
      )
    else
      val newStateRoot = newPivotHeader.stateRoot
      val oldPivot = pivotBlock.getOrElse(BigInt(0))
      val oldRoot = stateRoot.map(_.value.take(4).toHex).getOrElse("none")
      val newRoot = newStateRoot.value.take(4).toHex

      if stateRoot.contains(newStateRoot) then
        // No-op same-root pivot refresh. PR #1236 fixed this on one path; this is the second
        // path (completePivotRefreshWithStateRoot) that previously called restartSnapSync and
        // wiped in-memory progress. Observed sepolia 2026-05-15 00:50:36: lost ~500K accounts
        // of progress (~67% keyspace) because a stall-recovery pivot landed on the same root.
        //
        // A same-root "refresh" is information-free for the coordinator (their data for this
        // root is unchanged) but does not warrant destroying state. Strikes and snapless have
        // already been cleared at coordinator level via PR #1255; nothing further to do.
        // BUG fix: when the serve window expired and peers can no longer serve THIS root but the
        // refresh resolved to the same root (e.g. a scarce snap-peer pool with no newer servable
        // pivot), returning without notifying the coordinators left their statelessPeers set full
        // → they re-escalated forever and the node wedged. Re-arm them against the current root so
        // stateless tracking clears and dispatch resumes. PivotRefreshed is idempotent on an
        // unchanged root (the handler's `stateRoot = root` is a no-op), so no state is destroyed.
        ctx.log.info(
          s"Pivot refresh produced same root ($newRoot): $reason. Re-arming coordinators to clear stateless peers."
        )
        // If this pivot block is known-failed, fast-track the counter to the threshold so the
        // NEXT PivotStateUnservable triggers dormant mode (2–3 cycles instead of 10). Without
        // this, the churn loop runs the full 10-cycle budget before escalating — ~20 min of
        // 1/12th-throughput degradation when ETH-genesis peers contaminate the storage pool.
        // Re-arm happens unconditionally (prevents coordinator wedge); the counter jump is additive.
        if pivotBlock.exists(failedPivotBlocks.contains) then
          ctx.log.warn(
            s"Same-root refresh resolved to known-failed pivot block ${pivotBlock.getOrElse("?")} " +
              s"(root $newRoot). Fast-tracking consecutivePivotRefreshes to $MaxConsecutivePivotRefreshes."
          )
          consecutivePivotRefreshes = MaxConsecutivePivotRefreshes
        accountRangeCoordinator.foreach(_ ! actors.AccountRangeCoordinator.PivotRefreshed(newStateRoot.value))
        storageRangeCoordinator.foreach(_ ! actors.StorageRangeCoordinator.StoragePivotRefreshed(newStateRoot.value))
      else

        // Pivot readiness probe: for proactive rolls, verify the new root is indexed on at least
        // one peer before notifying coordinators. Coordinators continue on the old root uninterrupted
        // until probe confirms readiness — eliminating the ~8-minute dead window.
        var deferForProbe = false
        if proactiveRollNeedsProbe then
          proactiveRollNeedsProbe = false
          val snapPeerOpt = bestSnapProbeTarget()
          val totalSnapPeers = peersToDownloadFrom.values.count(_.peerInfo.remoteStatus.supportsSnap)
          snapPeerOpt match
            case Some((peerWithInfo, peerKind)) =>
              import com.chipprbots.ethereum.network.NetworkPeerManagerActor
              import com.chipprbots.ethereum.network.p2p.messages.SNAP.GetAccountRange.GetAccountRangeEnc
              val probeId = requestTracker.generateRequestId()
              val probe = GetAccountRange(
                requestId = probeId,
                rootHash = newStateRoot.value,
                startingHash = ByteString(Array.fill[Byte](32)(0)),
                limitHash = ByteString(Array.fill[Byte](32)(0xff.toByte)),
                responseBytes = 1024
              )
              networkPeerManager ! NetworkPeerManagerActor.SendMessageCmd(
                new GetAccountRangeEnc(probe),
                peerWithInfo.peer.id
              )
              pivotProbeRequestId = Some(probeId)
              pendingProbeCommit = Some((newPivotBlock, newPivotHeader, reason))
              // C4: keyed by the probe's BigInt requestId so a stale probe's timeout never cancels a newer one.
              timers.startSingleTimer(s"pivot-probe-$probeId", PivotProbeTimeout(probeId), 15.seconds)
              ctx.log.info(
                s"[PIVOT-PROBE] Probing $peerKind ${peerWithInfo.peer.id.value} " +
                  s"($totalSnapPeers snap peers available) " +
                  s"for root ${newStateRoot.value.take(4).toHex} block $newPivotBlock " +
                  s"pivot=${pivotBlock.getOrElse(0)} — deferring coordinator notification"
              )
              deferForProbe = true
            case None =>
              ctx.log.info(
                s"[PIVOT-PROBE] No SNAP peers available for readiness probe ($totalSnapPeers total) " +
                  s"— committing proactive roll immediately at root ${newStateRoot.value.take(4).toHex}"
              )
        if !deferForProbe then

          // Gate on ETH/Sepolia only — ETC pivot headers use StdBlockHeaderValidator (PoW).
          val posValidationOk = if isPoSChain then
            given bc: BlockchainConfig = com.chipprbots.ethereum.utils.Config.blockchains.blockchainConfig
            BlockHeader.validateFieldCount(newPivotHeader, bc) match
              case Left(msg) =>
                ctx.log.error(
                  "SNAP pivot header field-count mismatch — aborting pivot commit: {}",
                  msg
                )
                false
              case Right(_) =>
                PoSBlockHeaderValidator.validateHeaderOnly(newPivotHeader) match
                  case Left(err) =>
                    ctx.log.error(
                      "SNAP pivot header failed post-merge validation — aborting pivot commit: {}",
                      err
                    )
                    false
                  case Right(_) => true
          else true

          if posValidationOk then
            ctx.log.info(s"Pivot refreshed: block $oldPivot -> $newPivotBlock, root $oldRoot -> $newRoot")

            // Update internal state
            pivotBlock = Some(newPivotBlock)
            // BUG fix: advance the preserved-progress anchor with the live pivot so on-disk range
            // cursors stay within MaxPreservedPivotDistance of the pivot we restart at. The anchor was
            // latched once to the ORIGINAL pivot and never advanced, so after many in-place refreshes a
            // restart found the saved pivot thousands of blocks stale, failed the drift check, and wiped
            // ~10M accounts of cursors (full keyspace re-scan). Account-trie nodes are content-addressed
            // (keccak256-keyed), so a traversal cursor is valid across any pivot jump; healing reconciles
            // the per-leaf delta against the new root.
            if preservedAtPivotBlock.isDefined then
              preservedAtPivotBlock = Some(newPivotBlock)
              if preservedRangeProgress.nonEmpty then
                snapProgressStorage.writeAccountCursors(
                  newStateRoot.value,
                  newPivotBlock.toLong,
                  preservedRangeProgress.map { case (k, v) => k.toHex -> v.toHex }
                )
            stateRoot = Some(newStateRoot)
            // Bump validationGeneration so any in-flight validation Future's result
            // is dropped on arrival rather than applied against the stale root. The
            // phase gate above is the primary defense; this is defense-in-depth for
            // any code path that mutates pivot/root from a different phase.
            validationGeneration += 1
            lastProactivePivotBlock =
              // approximate networkBest at roll time; pivotBlock = networkBest - max(offset, margin)
              pivotBlock.map(_ + BigInt(snapSyncConfig.pivotBlockOffset).max(SnapServeWindowMargin))
            // Note: mptStorage stays the same — content-addressed nodes don't need re-tagging.
            // The backing storage was already created for the original pivot block number,
            // but since nodes are keyed by hash, they're valid for any root.

            // Persist new pivot — but NOT during healing: AppStateStorage is updated only when healing
            // succeeds (trie walk finds 0 missing nodes). Mid-healing writes cause root mismatch (BUG-006):
            // the new root is stored before all its nodes are healed, then validateState() sees a mismatch.
            if currentPhase != StateHealing then
              appStateStorage
                .putSnapSyncPivotBlock(newPivotBlock)
                .and(appStateStorage.putSnapSyncStateRoot(newStateRoot.value))
                .commit()
            updateBestBlockForPivot(newPivotHeader, newPivotBlock)
            SNAPSyncMetrics.setPivotBlockNumber(newPivotBlock)
            SNAPSyncMetrics.incrementPivotRefreshed()

            // Geth-aligned: send refresh signal to ALL active coordinators (all 3 run concurrently)
            accountRangeCoordinator.foreach(_ ! actors.AccountRangeCoordinator.PivotRefreshed(newStateRoot.value))
            storageRangeCoordinator.foreach(
              _ ! actors.StorageRangeCoordinator.StoragePivotRefreshed(newStateRoot.value)
            )
            // Bytecodes are content-addressed (hash-keyed) so pivot changes don't invalidate them,
            // but the coordinator should clear stale peer tracking.
            bytecodeCoordinator.foreach(_ ! actors.ByteCodeCoordinator.ByteCodePivotRefreshed)
            // Healing coordinator: update root, clear pending tasks and stateless peers.
            // Then re-walk the trie with the new root to discover missing nodes.
            // spec 009 T014: a successful re-peg landed a fresh served root — reset the bounded no-root budget so the
            // lazy-heal last-resort only fires after a fresh run of consecutive empty re-peg attempts. Harmless flag-OFF
            // (the budget is never incremented unless movingRootDeltaHeal && StateHealing).
            healRepegNoRootAttempts = 0
            trieNodeHealingCoordinator.foreach { coordinator =>
              coordinator ! actors.TrieNodeHealingCoordinator.HealingPivotRefreshed(newStateRoot.value)
            }
            // Chain download target extends to the new pivot (chain data is canonical, never invalidated)
            if chainDownloader.isDefined then
              chainDownloader.foreach(_ ! ChainDownloader.UpdateTarget(newPivotBlock))
              // Resume chain download if it was paused during pivot header bootstrap
              chainDownloader.foreach(_ ! ChainDownloader.Resume)
            else
              // Start chain downloader if not yet started (e.g. pivot was 0 at initial bootstrap)
              startChainDownloader()

            // Reset account stagnation timer during pivot refresh recovery.
            // Note: do NOT reset lastStorageProgressMs — only actual slot downloads (via
            // ProgressStorageSlotsSynced) should reset the storage stagnation timer.
            // This ensures that repeated stateless-peer pivot cycles don't prevent the
            // stagnation watchdog from eventually triggering a full restart.
            lastAccountProgressMs = System.currentTimeMillis()
          // end if posValidationOk
        // end if !deferForProbe
      // end else (stateRoot changed)
    // end else (not StateValidation phase)

  private def restartSnapSync(reason: String): Behavior[Command] =
    ctx.log.warn(s"Restarting SNAP sync with a fresher pivot: $reason")

    // Clear any pending pivot refresh (we're doing a full restart instead)
    pendingPivotRefresh = None
    pivotProbeRequestId = None
    pendingProbeCommit = None
    proactiveRollNeedsProbe = false
    probeAttemptCount = 0
    lastProbeAttemptMs = 0L
    // spec 004 T011: a full restart spawns a fresh healing coordinator (serveRoot re-inits to the walk root);
    // clear the serve-root bookkeeping so the first healing tick re-engages decoupling against the new round.
    healingServeRootRequestInFlight = false
    lastHealingServeRootBlock = None

    // NOTE: do NOT reset consecutivePivotRefreshes here. restartSnapSync is often called
    // from refreshPivotInPlace when no new pivot is available, which means the counter would
    // reset on every failed cycle and never reach the threshold. The counter only resets on
    // actual account download progress (ProgressAccountsSynced) or at startSnapSync().

    // Cancel periodic phase request ticks
    timers.cancel(RequestAccountRanges)
    timers.cancel(RequestByteCodes)
    timers.cancel(RequestStorageRanges)
    timers.cancel(CheckDownloadStagnation)
    timers.cancel(RequestTrieNodeHealing)
    timers.cancel(BootstrapCheckKey)
    timers.cancel(PivotBootstrapRetryKey)
    timers.cancel(TuneRateTracker)

    // Stop coordinators so we don't double-run phases
    accountRangeCoordinator.foreach(ctx.stop); accountRangeCoordinator = None
    bytecodeCoordinator.foreach(ctx.stop); bytecodeCoordinator = None
    storageRangeCoordinator.foreach(ctx.stop); storageRangeCoordinator = None
    trieNodeHealingCoordinator.foreach(ctx.stop); trieNodeHealingCoordinator = None

    // Clear inflight request timeouts and internal phase state
    requestTracker.clear()

    // Clear concurrent download state and recovery data
    accountsComplete = false
    bytecodePhaseComplete = false
    storagePhaseComplete = false
    storagePhaseForceCompleted = false
    forceCompleteStorageSent = false
    // Reset bytecode-estimate counter so it stays in sync with progressMonitor.reset()
    // (called below). IncrementalContractData will repopulate as accounts are re-identified.
    bytecodesEstimatedTotal = 0L
    // #1188: invalidate clean-walk signal — the trie we're rebuilding is fresh state.
    // Root-equality alone would catch this (stateRoot is reset below) but we clear
    // explicitly so the field doesn't briefly hold a stale positive against a None root.
    healingValidatedRoot = None
    appStateStorage
      .putSnapSyncAccountsComplete(false)
      .and(appStateStorage.putSnapSyncStorageComplete(false))
      .and(appStateStorage.putSnapSyncBytecodeComplete(false))
      .commit()
    appStateStorage.putSnapSyncStorageFilePath("").commit()

    // Reset pivot/state root and storage so a new selection is committed
    pivotBlock = None
    stateRoot = None
    mptStorage = None
    currentPhase = Idle
    coordinatorGeneration += 1
    // Bump validation generation so any in-flight validation Future's result
    // is dropped on arrival. Also clear the in-progress flag — the new sync
    // attempt starts from scratch, no validation is active.
    validationGeneration += 1
    validationInProgress = false

    // Reset progress counters so logs/ETA reflect the new attempt
    progressMonitor.reset()

    // Re-run pivot selection/bootstrap with the latest visible peer set
    startSnapSync()

  /** Trigger healing by re-running the trie walk with path tracking. Called from validateState() when missing nodes are
    * discovered. The passed hashes are just an indicator — we re-walk to get proper paths for GetTrieNodes.
    */
  private def triggerHealingForMissingNodes(missingNodes: Seq[ByteString]): Unit =
    ctx.log.info(s"Validation found ${missingNodes.size} missing nodes — re-running trie walk with paths for healing")
    currentPhase = StateHealing
    stateRoot.foreach { root =>
      val storage = getOrCreateMptStorage(pivotBlock.getOrElse(BigInt(0)))
      scala.concurrent
        .Future {
          val validator = new StateValidator(storage)
          validator.findMissingNodesWithPaths(root.value)
        }(ec)
        .foreach {
          case Right(nodes) => ctx.self ! TrieWalkResult(nodes)
          case Left(error)  => ctx.self ! TrieWalkFailed(error)
        }(ec)
    }

  /** Convert SNAP sync progress to SyncProtocol.Status for eth_syncing RPC endpoint.
    *
    * Returns syncing status with:
    *   - startingBlock: The block number we started syncing from
    *   - currentBlock: The pivot block we're syncing state for
    *   - highestBlock: The pivot block (same as current for SNAP sync)
    *   - knownStates: Estimated total state nodes based on current phase
    *   - pulledStates: Number of state nodes synced so far
    */
  private def currentSyncStatus: SyncProtocol.Status =
    val progress = progressMonitor.currentProgress
    val startingBlock = appStateStorage.getSyncStartingBlock()
    val currentBlock = pivotBlock.getOrElse(startingBlock)

    /** Helper to get estimate or actual count, whichever is larger. Used as a defensive fallback when estimates are not
      * yet available (0).
      */
    def estimateOrActual(estimate: Long, actual: Long): Long = estimate.max(actual)

    // Calculate state progress based on current phase
    // SNAP sync involves multiple phases: accounts, bytecode, storage, healing
    val (pulledStates, knownStates) = currentPhase match
      case AccountRangeSync =>
        // In account range sync, we track accounts synced
        (progress.accountsSynced, estimateOrActual(progress.estimatedTotalAccounts, progress.accountsSynced))

      case ByteCodeAndStorageSync =>
        // Concurrent bytecode + storage sync
        val pulled = progress.accountsSynced + progress.bytecodesDownloaded + progress.storageSlotsSynced
        val known = progress.estimatedTotalAccounts + estimateOrActual(
          progress.estimatedTotalBytecodes,
          progress.bytecodesDownloaded
        ) +
          estimateOrActual(progress.estimatedTotalSlots, progress.storageSlotsSynced)
        (pulled, known)

      case StateHealing =>
        // In healing, we add nodes healed to the pulled count
        // For the known total, use the sum of all previous phases since we don't know
        // how many nodes need healing until validation discovers them
        val pulled = progress.accountsSynced + progress.bytecodesDownloaded +
          progress.storageSlotsSynced + progress.nodesHealed
        // Known is the sum of estimates from previous phases (healing adds no new estimate)
        val known = progress.estimatedTotalAccounts + progress.estimatedTotalBytecodes +
          progress.estimatedTotalSlots
        // Ensure known is at least as much as pulled (consistent with defensive fallback pattern)
        (pulled, estimateOrActual(known, pulled))

      case StateValidation =>
        // During validation, show total state synced
        val total = progress.accountsSynced + progress.bytecodesDownloaded +
          progress.storageSlotsSynced + progress.nodesHealed
        (total, total)

      case ChainDownloadCompletion =>
        // State sync done, just waiting for chain download
        val total = progress.accountsSynced + progress.bytecodesDownloaded +
          progress.storageSlotsSynced + progress.nodesHealed
        (total, total)

      case Idle | Completed | Dormant =>
        (0L, 0L)

    SyncProtocol.Status.Syncing(
      startingBlockNumber = startingBlock,
      blocksProgress = SyncProtocol.Status.Progress(currentBlock, currentBlock),
      stateNodesProgress = Some(SyncProtocol.Status.Progress(BigInt(pulledStates), BigInt(knownStates)))
    )

  // Track consecutive account stall pivot refreshes to detect truly unrecoverable situations.
  // Reset to 0 on real progress (taskProgress || downloadProgress in maybeRestartIfAccountStagnant).
  private var consecutiveAccountStallRefreshes: Int = 0

  // Hard cap so a wedged account phase escalates to dormant mode rather than refreshing
  // forever. Higher than MaxConsecutivePivotRefreshes because account stalls can be transient
  // (peer churn, slow networks) and the stagnation threshold itself already takes minutes to fire.
  private val MaxConsecutiveAccountStallRefreshes = 10

  private def maybeRestartIfAccountStagnant(progress: actors.AccountRangeStats): Unit =
    if currentPhase == AccountRangeSync then
      // Only consider it a stall if there is still work to do.
      val workRemaining = progress.tasksPending > 0 || progress.tasksActive > 0

      if workRemaining then
        // Update liveness based on task completions OR account download progress.
        // The sync is making progress if EITHER metric advances. This prevents false
        // stagnation when accounts are downloading steadily across all 16 chunks but
        // no single chunk has finished its full 1/16th range yet.
        val taskProgress = progress.tasksCompleted > lastAccountTasksCompleted
        val downloadProgress = progress.accountsDownloaded > lastAccountsDownloaded

        if taskProgress || downloadProgress then
          lastAccountTasksCompleted = progress.tasksCompleted
          lastAccountsDownloaded = progress.accountsDownloaded
          lastAccountProgressMs = System.currentTimeMillis()
          consecutiveAccountStallRefreshes = 0 // Reset on real progress
        else
          val now = System.currentTimeMillis()
          val stalledForMs = now - lastAccountProgressMs

          // Avoid noisy rapid refreshes.
          if stalledForMs >= AccountStagnationThreshold.toMillis && now - lastPivotRestartMs >= MinPivotRestartInterval.toMillis
          then
            lastPivotRestartMs = now

            // Check if the stall is due to no peers (not a processing failure).
            // On ETC mainnet, SNAP peer gaps of 30-60 minutes are normal.
            // Don't escalate when the root cause is simply waiting for peers.
            val snapPeerCount = handshakedPeers.values.count(_.peerInfo.remoteStatus.supportsSnap)
            if snapPeerCount == 0 then
              ctx.log.info(
                s"Account sync stalled (${stalledForMs / 1000}s) but no SNAP peers available. " +
                  s"Waiting for peers to reconnect (downloaded=${progress.accountsDownloaded})."
              )
              // Don't increment stall counter — this is a peer availability issue, not a sync failure.
              // Reset the stall timer so we don't immediately escalate when peers reconnect.
              lastAccountProgressMs = System.currentTimeMillis()
            else
              consecutiveAccountStallRefreshes += 1

              val context =
                s"account range sync stalled: no download progress for ${stalledForMs / 1000}s " +
                  s"(threshold=${AccountStagnationThreshold.toSeconds}s), accountsDownloaded=${progress.accountsDownloaded}, " +
                  s"tasksPending=${progress.tasksPending}, tasksActive=${progress.tasksActive}, snapPeers=$snapPeerCount"

              // After enough refresh attempts without download progress, escalate so the node doesn't
              // loop forever. recordCriticalFailure trips dormant mode at the configured threshold;
              // if that hasn't tripped yet, fall through to one more in-place refresh.
              val dormantTriggered =
                if consecutiveAccountStallRefreshes > MaxConsecutiveAccountStallRefreshes then
                  ctx.log.error(
                    s"Account stall pivot-refresh budget exhausted " +
                      s"($consecutiveAccountStallRefreshes > $MaxConsecutiveAccountStallRefreshes). $context"
                  )
                  consecutiveAccountStallRefreshes = 0
                  lastAccountProgressMs = System.currentTimeMillis()
                  if recordCriticalFailure(s"account stall refresh limit exceeded: $context") then
                    enterDormantMode(s"account stall refresh limit exceeded: $context")
                    true
                  else false
                else false

              if !dormantTriggered then
                // In-place pivot refresh to try to find a serveable root.
                ctx.log.warn(
                  s"Account stall detected ($context). " +
                    s"Refreshing pivot in-place to recover (attempt $consecutiveAccountStallRefreshes). " +
                    s"Account data preserved."
                )
                lastAccountProgressMs = System.currentTimeMillis()
                // #1184: ask the coordinator to drain `activeTasks` BEFORE the pivot refresh so any
                // leaked slots are re-queued and visible by the time the resulting `PivotRefreshed`
                // message arrives. Coordinator-side defensive drains (PeerUnavailable / PivotRefreshed
                // / CheckDispatchStalled) cover this independently; this is the explicit controller hook.
                accountRangeCoordinator.foreach(_ ! actors.AccountRangeCoordinator.RecoverStalledAccountTasks)
                refreshPivotInPlace(s"account stall: $context")
              // end if !dormantTriggered
            // end else (snapPeerCount > 0)
          // end if stalledForMs >= threshold
        // end else (no progress)
      // end if workRemaining
  // end if currentPhase == AccountRangeSync

  /** State sync + healing + validation finished — anchor the pivot and hand off to regular sync immediately.
    *
    * Historical chain backfill (genesis → pivot) is decoupled: we do not block here waiting for it. Instead,
    * `finalizeSnapSync()` emits `SnapSyncFinalized(pivot)` to the parent (which starts RegularSync) and either:
    *   - emits `Done` immediately if backfill is disabled or already complete, or
    *   - keeps the controller alive in `completedWithBackfill` so it can forward `ChainDownloader.Progress` /
    *     `ChainDownloader.Done` to the parent without blocking forward sync.
    *
    * Closes #1162.
    */
  private def completeSnapSync(): Behavior[Command] =
    pivotBlock.map(finalizeSnapSync).getOrElse(Behaviors.same)

  /** Anchor the pivot, mark SNAP state done, and hand off to the parent. Always emits `SnapSyncFinalized(pivot)`. Emits
    * `Done` either immediately (no backfill in flight) or later from `completedWithBackfill` after
    * `ChainDownloader.Done` arrives. SNAP-only schedules are cancelled before the handoff so eviction tickers and
    * stagnation checks don't keep firing while regular sync owns the peer pool.
    */
  private def finalizeSnapSync(pivot: BigInt): Behavior[Command] =
    import scala.util.boundary, boundary.break
    boundary[Behavior[Command]] {
      // Look up the pivot header so we can store a complete "best block" anchor.
      // RegularSync's BranchResolution needs: header, body, number→hash mapping,
      // ChainWeight, and BestBlockInfo (hash + number) to accept blocks that chain
      // from the pivot.
      blockchainReader.getBlockHeaderByNumber(pivot) match
        case Some(pivotHeader) =>
          // A5: Root match guard — snapStateRoot must equal pivotHeader.stateRoot before
          // marking sync done. Mirrors Besu SnapWorldDownloadState.saveWorldState() implicit
          // verification. If they diverge (BUG-008 class), restart SNAP rather than committing
          // a broken state.
          appStateStorage.getSnapSyncStateRoot().foreach { snapRoot =>
            if snapRoot != pivotHeader.stateRoot.value then
              ctx.log.error(
                "SNAP finalization aborted: snapStateRoot={} != pivotHeader.stateRoot={}. " +
                  "State trie root mismatch — escalating to SyncController for SNAP restart.",
                snapRoot.toHex,
                pivotHeader.stateRoot.value.toHex
              )
              syncController ! SyncProtocol.HealingImpossible
              break(Behaviors.same)
          }

          val pivotHash = pivotHeader.hash

          // Store the full block (header + empty body) so getBlockByHash(pivotHash) returns
          // a Block AND the number→hash mapping is written. PivotHeaderBootstrap already stored
          // the header, but storeBlock ensures the mapping is present even if the header was
          // stored by a different code path during pivot refresh.
          blockchainWriter.storeBlock(Block(pivotHeader, BlockBody.empty)).commit()

          // Store a ChainWeight so compareBranch() can evaluate new blocks.
          // Priority: (1) real cumulative TD from ChainDownloader — only accepted if it exceeds
          // 1000× genesis TD (rejects genesis-proxy values written by earlier updateBestBlockForPivot
          // calls). (2) Peer-interpolated TD from a connected ETH68 peer. (3) Block-number proxy as
          // last resort (no ETH68 peers at all — rare but possible in isolated test environments).
          val genesisBlockTD: BigInt = blockchainReader
            .getChainWeightByHash(blockchainReader.genesisHeader.hash)
            .map(_.totalDifficulty.value)
            .getOrElse(blockchainReader.genesisHeader.difficulty.value)
          val (finalTD, tdSource) =
            blockchainReader
              .getChainWeightByHash(pivotHash)
              .map(_.totalDifficulty.value)
              .filter(_ > genesisBlockTD * BigInt(1000))
              .map(td => (td, "REAL_DB_TD"))
              .orElse(calibratePivotTD(pivot).map(td => (td, "PEER_INTERPOLATED_TD")))
              .getOrElse((pivot.max(genesisBlockTD), "BLOCK_NUMBER_PROXY"))
          ctx.log.info("SNAP finalize pivot TD: block={} td={} source={}", pivot, finalTD, tdSource)
          blockchainWriter
            .storeChainWeight(
              pivotHash,
              ChainWeight.totalDifficultyOnly(finalTD)
            )
            .commit()

          // Set best block info with BOTH hash and number (putBestBlockNumber only
          // sets the number, leaving getBestBlockInfo().hash empty).
          appStateStorage
            .putBestBlockInfo(
              com.chipprbots.ethereum.domain.appstate.BlockInfo(pivotHash.value, pivot)
            )
            .commit()

          // D4: snapSyncDone written AFTER pivot header and best-block info are stored.
          // Pivot data is written first so a crash between writes leaves the node in a
          // recoverable state (D3 startup gate detects SnapSyncDone=true with unreachable
          // root and restarts SNAP). Mirrors Besu SnapSyncStatePersistenceManager ordering.
          //
          // All three completion flags (snapSyncDone + bytecodeRecoveryDone + storageRecoveryDone)
          // are written atomically in a single fsync-backed commit. A crash before this call
          // leaves all flags absent → startup retries SNAP. A crash after → all flags present
          // → startup proceeds directly to regular sync. There is no inconsistent half-written
          // state. commitSync() flushes the OS write buffer to disk before returning, eliminating
          // the ~5-30s dirty-writeback window that previously caused spurious SNAP-RECOVERY.
          appStateStorage
            .snapSyncDone()
            .and(appStateStorage.bytecodeRecoveryDone())
            .and(appStateStorage.storageRecoveryDone())
            .commitSync()

          ctx.log.info(s"SNAP sync completed successfully at block $pivot (hash=${pivotHash.value.take(8).toHex})")

        case None =>
          // Fallback: shouldn't happen since PivotHeaderBootstrap stored the header
          ctx.log.warn(s"Pivot header for block $pivot not found in storage — setting best block number only")
          appStateStorage.putBestBlockNumber(pivot).commit()

      progressMonitor.complete()
      ctx.log.info(progressMonitor.currentProgress.toString)
      currentPhase = Completed

      // Cancel SNAP-only schedules before handing off so eviction tickers / stagnation checks stop
      // affecting peers while regular sync runs. Stop state-sync child coordinators too — they don't
      // self-stop on completion and would otherwise retain completed task buffers and worker actors
      // for the full background-backfill window. The chain downloader child is intentionally NOT stopped.
      stopSnapOnlySchedules()
      stopStateSyncChildren()

      // Phase 1 of the handshake: tell the parent that pivot/state is anchored. Parent starts RegularSync.
      syncController ! SnapSyncFinalized(pivot)

      val backfillStillRunning =
        snapSyncConfig.chainDownloadEnabled && chainDownloader.isDefined && !chainDownloadComplete

      if backfillStillRunning then
        ctx.log.info(
          s"SNAP state finalised at pivot=$pivot. Starting regular sync; chain backfill continues in background."
        )
        // Yield peer slots to regular sync — backfill keeps a small budget.
        chainDownloader.foreach(
          _ ! ChainDownloader.YieldToRegularSync(snapSyncConfig.chainBackfillConcurrentRequests)
        )
        completedWithBackfill()
      else
        // No backfill in flight — emit Done immediately. Parent poison-pills this actor.
        chainDownloader.foreach(ctx.stop)
        chainDownloader = None
        syncController ! Done
        completed()
    } // end boundary

  /** Receive after SNAP state is finalised but `ChainDownloader` is still backfilling history.
    *
    * Minimal surface — only what `ChainDownloader` and status RPCs need. SNAP-protocol responses, peer-list messages,
    * and stagnation checks are no longer relevant; everything else is silently dropped. The actor's sole remaining job
    * is to propagate `ChainDownloader.Done` to the parent so it can poison-pill us.
    */
  def completedWithBackfill(): Behavior[Command] =
    Behaviors
      .receiveMessage[Command] {
        case ChainDownloaderProgress(h, b, r, t) =>
          progressMonitor.updateChainProgress(h, b, r, t)
          Behaviors.same

        case ChainDownloaderDone =>
          ctx.log.info("Background chain backfill complete; SNAPSyncController shutting down.")
          chainDownloadComplete = true
          chainDownloader.foreach(ctx.stop)
          chainDownloader = None
          syncController ! Done
          completed()

        case GetStatus(replyTo) =>
          replyTo ! SyncProtocol.Status.SyncDone
          Behaviors.same

        case GetProgress(replyTo) =>
          replyTo ! progressMonitor.currentProgress
          Behaviors.same

        case _ =>
          // Stale SNAP messages, leaked tickers, and other noise are silently dropped.
          Behaviors.same
      }
      .receiveSignal { case (_, PostStop) =>
        onStop(); Behaviors.same
      }

  private def startChainDownloader(): Unit =
    if snapSyncConfig.chainDownloadEnabled then
      pivotBlock.filter(_ > 0).foreach { pivot =>
        if chainDownloader.isEmpty then
          ctx.log.info("Starting parallel chain download from genesis to pivot block {}", pivot)
          coordinatorGeneration += 1
          val snapServerNodeIds = snapSyncConfig.snapServerPeers.flatMap { uri =>
            scala.util.Try(ByteString(org.bouncycastle.util.encoders.Hex.decode(uri.getUserInfo))).toOption
          }.toSet
          // ChainDownloader is Pekko Typed Behavior[Command] (Group S6 narrowed).
          // Sends (Pause/Resume/UpdateTarget/YieldToRegularSync/…) are now type-safe against ActorRef[Command].
          import org.apache.pekko.actor.typed.DispatcherSelector
          val downloader = ctx
            .spawn(
              ChainDownloader(
                blockchainReader = blockchainReader,
                blockchainWriter = blockchainWriter,
                appStateStorage = appStateStorage,
                networkPeerManager = networkPeerManager,
                peerEventBus = peerEventBus,
                syncConfig = syncConfig,
                replyTo = chainDownloaderReplyAdapter,
                maxConcurrentRequests = snapSyncConfig.chainDownloadMaxConcurrentRequests,
                requestTimeout = snapSyncConfig.chainDownloadTimeout,
                snapServerPeerNodeIds = snapServerNodeIds
              ),
              s"chain-downloader-$coordinatorGeneration",
              DispatcherSelector.fromConfig("sync-dispatcher")
            )
          downloader ! ChainDownloader.Start(pivot)
          chainDownloader = Some(downloader)
          chainDownloadComplete = false
      }
  // end if chainDownloadEnabled

  // --- SNAP progress persistence helpers ---

  /** Deserialize range progress from legacy AppStateStorage plain-text format (migration fallback). Yields
    * `(pivotBlock, rangeProgress)` or `None` if parsing fails.
    */
  private def deserializeSnapProgress(data: String): Option[(BigInt, Map[ByteString, ByteString])] =
    try
      val lines = data.split('\n').filter(_.nonEmpty)
      if lines.isEmpty then None
      else
        var pivot: Option[BigInt] = None
        val ranges = scala.collection.mutable.Map.empty[ByteString, ByteString]

        lines.foreach { line =>
          val idx = line.indexOf('=')
          if idx > 0 then
            val key = line.substring(0, idx)
            val value = line.substring(idx + 1)
            if key == "pivotBlock" then pivot = Some(BigInt(value))
            else ranges += (ByteString(Hex.decode(key)) -> ByteString(Hex.decode(value)))
        }

        pivot.map(p => (p, ranges.toMap))
    catch case _: Exception => None

object SNAPSyncController:

  /** Whether a handshaked peer can serve SNAP state for our pivot: SNAP-capable, on our fork, and not sitting at its
    * genesis block. Every phase that hands peers to a coordinator filters on this: account ranges, bytecodes, storage
    * ranges, trie-node healing, and the post-sync recovery in `SyncController`. A peer at genesis holds no state and
    * answers every request empty, which costs each phase strikes or retries (#1356). On ETC that is typically an
    * ETH-mainnet node syncing from scratch: the chains share genesis and networkId 1, so it passes the fork-ID check.
    *
    * The test is the peer's best-block hash. It is known from the handshake on for every protocol version, and it moves
    * with `maxBlockNumber` whenever the peer reports a higher block (`withBestBlockData`). It used to be
    * `maxBlockNumber > 0` (#1356), but an eth/68 STATUS carries no block number, so every eth/68 peer read as block 0
    * until a header probe landed. That kept it out of the storage pool for up to the 5-minute re-probe interval, which
    * in a 2-peer ETC pool is the whole pool. Unlike the `>= pivot` guard removed in PR #1238, this does not exclude a
    * peer that is merely behind: one at block N < pivot still serves state up to N.
    */
  private[sync] def servesSnapState(
      peerInfo: com.chipprbots.ethereum.network.NetworkPeerManagerActor.PeerInfo
  ): Boolean =
    peerInfo.remoteStatus.supportsSnap &&
      peerInfo.forkAccepted &&
      peerInfo.bestBlockHash != peerInfo.remoteStatus.genesisHash

  // ───────────────────────────────────────────────────────────────────────────
  // Command ADT (SNAP1 migration — Pekko Classic→Typed)
  //
  // Sealed in this companion: every inbound message the Typed SNAPSyncController
  // accepts extends Command. All subtypes live in this source file, so the trait
  // can be sealed (Scala 3 file-scope sealing). Some private subtypes are still
  // declared in the class body (above) during Phase 1; they extend
  // SNAPSyncController.Command in place and relocate to this companion in Phase 2.
  //
  // See .local/docs/moderization-review-june/SNAP1-SSC-ADT-design.md for the
  // authoritative grouping (Groups 1–10) and the open-question resolutions.
  // ───────────────────────────────────────────────────────────────────────────

  sealed trait Command

  // ── Group 7: SNAP protocol responses (OQ-1) ───────────────────────────────
  // NetworkPeerManagerActor (Typed core, Classic shell) routes raw SNAP wire
  // responses to the SNAPSyncController ref. In Typed they must be Command-wrapped.
  final case class AccountRangeResponse(msg: SNAP.AccountRange) extends Command
  final case class ByteCodesResponse(msg: SNAP.ByteCodes) extends Command
  final case class StorageRangesResponse(msg: SNAP.StorageRanges) extends Command
  final case class TrieNodesResponse(msg: SNAP.TrieNodes) extends Command

  // ── Group: ChainDownloader wrappers (OQ-2) ─────────────────────────────────
  // ChainDownloader is Behavior[Command] (S6 narrowed). Its outbound Progress/Done messages are
  // wrapped so SSC's sealed mailbox accepts them via the Classic adapter bridge.
  final private[snap] case class ChainDownloaderProgress(
      currentBlock: BigInt,
      bodiesDownloaded: BigInt,
      receiptsDownloaded: BigInt,
      targetBlock: BigInt
  ) extends Command
  private[snap] case object ChainDownloaderDone extends Command

  // ── Group: Peer-event wrappers (OQ-3 — PeerListHelper bridge) ──────────────
  // SSC composes PeerListHelper (Group PLN). The handshaked-peers poll reply and
  // the PeerDisconnected event are bridged into the Command ADT via messageAdapters.
  final case class WrappedHandshakedPeers(
      peers: Map[com.chipprbots.ethereum.network.Peer, com.chipprbots.ethereum.network.NetworkPeerManagerActor.PeerInfo]
  ) extends Command
  final case class WrappedPeerDisconnected(peerId: com.chipprbots.ethereum.network.PeerId) extends Command

  enum SyncPhase:
    case Idle, AccountRangeSync, ByteCodeAndStorageSync, StateHealing, StateValidation, ChainDownloadCompletion,
      Completed, Dormant

  /** Source of pivot block selection */
  sealed trait PivotSelectionSource:
    def name: String
  case object NetworkPivot extends PivotSelectionSource:
    val name = "network"
  case object LocalPivot extends PivotSelectionSource:
    val name = "local"

  /** Pivot supplied by the consensus layer via engine_forkchoiceUpdated. Used on post-merge chains where TD is frozen
    * at TerminalTotalDifficulty and TD-based selection cannot produce a useful target. Closes #1207.
    */
  case object CLDrivenPivot extends PivotSelectionSource:
    val name = "cl-driven"

  case object Start extends Command
  case object Done extends SyncProtocol.SyncControllerReply

  /** Hint from `SyncController` that the consensus layer has pushed a fork-choice update. Carries the head's hash
    * (always) and the locally-stored header (when present — usually true if a `newPayload` arrived first; can be `None`
    * if FCU arrived before any `newPayload` for that head, in which case the controller falls back to a by-hash
    * `StartRegularSyncBootstrap` to fetch the header from peers). Closes #1207.
    */
  final case class CLPivotHint(headHash: ByteString, knownHeader: Option[BlockHeader]) extends Command

  /** Minimum block number the pivot must be at or above. Sent by SyncController when regular sync was stuck at a
    * specific block, so re-SNAP can't re-select the same pivot that caused the stall.
    */
  final case class MinPivotBlock(minBlock: BigInt) extends Command

  /** Bootstrap-by-hash variant of `StartRegularSyncBootstrap`. Used when the CL drives sync and we know the head hash
    * but not its block number — `PivotHeaderBootstrap` then fetches by `GetBlockHeaders(Right(hash))`. Closes #1207.
    */
  final case class StartRegularSyncBootstrapByHash(headHash: ByteString) extends SyncProtocol.SyncControllerReply
  // Two-phase handshake with SyncController:
  //   1. SnapSyncFinalized(pivot) — pivot/state anchored, regular sync can start.
  //      SyncController starts RegularSync but does NOT poison-pill SNAPSyncController.
  //   2. Done — backfill complete (or absent/disabled). SyncController poison-pills SNAPSyncController.
  // Sender always emits the same shape: Finalized first, then Done either immediately or after backfill.
  final case class SnapSyncFinalized(pivot: BigInt) extends SyncProtocol.SyncControllerReply
  case class StartRegularSyncBootstrap(targetBlock: BigInt)
      extends SyncProtocol.SyncControllerReply // Request bootstrap from SyncController
  final case class BootstrapComplete(
      pivotHeader: Option[BlockHeader] = None
  ) extends Command // Signal from SyncController that bootstrap is done
  final case class PivotBootstrapFailed(
      reason: String
  ) extends Command // Signal from SyncController that pivot header bootstrap exhausted retries
  // Internal commands (private[snap] so the SNAPSyncControllerImpl class — same package — can reference
  // them). Relocated from the Classic class body in Phase 2. All extend the sealed Command (same file).
  private[snap] case object RetrySnapSyncStart extends Command // retry SNAP sync start after bootstrap
  private[snap] case object FlushPeerDisconnects
      extends Command // Debounce flush: forward batched PeerUnavailable to coordinators
  private[snap] case object RetryPivotRefresh extends Command
  final private[snap] case class RetryBootstrapAtBlock(blockNumber: BigInt) extends Command
  private[snap] case object CheckSnapCapability extends Command
  private[snap] case object TuneRateTracker extends Command
  private[snap] case object EvictNonSnapPeers extends Command
  final private[snap] case class PivotProbeTimeout(requestId: BigInt) extends Command
  private[snap] case object DormantWakeUp extends Command
  final private[snap] case class DelayedRestart(reason: String) extends Command
  // Cursor progress snapshot sent by AccountRangeCoordinator on postStop (crash-recovery path)
  final private[snap] case class AccountRangeProgressCmd(progress: Map[ByteString, ByteString]) extends Command
  // Unified stagnation detection — single timer dispatches to the active coordinator
  private[snap] case object CheckDownloadStagnation extends Command
  final private[snap] case class AccountCoordinatorProgress(progress: actors.AccountRangeStats) extends Command
  final private[snap] case class StorageCoordinatorProgress(stats: actors.StorageRangeCoordinator.SyncStatistics)
      extends Command
  final private[snap] case class ByteCodeCoordinatorProgress(progress: actors.ByteCodeCoordinator.ByteCodeProgress)
      extends Command
  // Periodic peer-request ticks
  private[snap] case object RequestAccountRanges extends Command
  private[snap] case object RequestByteCodes extends Command
  private[snap] case object RequestStorageRanges extends Command
  private[snap] case object RequestTrieNodeHealing extends Command
  private[snap] case object EnsureSnapServerPeersConnected extends Command
  // Async trie-walk results
  final private[snap] case class TrieWalkResult(missingNodes: Seq[(Seq[ByteString], ByteString)]) extends Command
  final private[snap] case class TrieWalkBatch(missingNodes: Seq[(Seq[ByteString], ByteString)]) extends Command
  final private[snap] case class TrieWalkComplete(totalFound: Int) extends Command
  final private[snap] case class TrieWalkFailed(error: String) extends Command
  // Async validation results (generation-stamped — stale results dropped on arrival)
  final private[snap] case class ValidateAccountTrieResult(
      generation: Long,
      result: Either[String, Seq[ByteString]],
      elapsedMs: Long
  ) extends Command
  final private[snap] case class ValidateStorageTriesResult(
      generation: Long,
      result: Either[String, Seq[ByteString]],
      elapsedMs: Long
  ) extends Command
  final private[snap] case class ValidationRetry(generation: Long) extends Command
  private[snap] case object ScheduledTrieWalk extends Command
  // OQ-3: drives the periodic GetHandshakedPeers poll (replaces NPMA's HandshakedPeers broadcast).
  private[snap] case object PollHandshakedPeers extends Command
  case object AccountRangeSyncComplete extends Command
  case object ByteCodeSyncComplete extends Command
  case object StorageRangeSyncComplete extends Command
  case object StorageRangeSyncForceCompleted extends Command

  /** Inline contract data dispatched from AccountRangeCoordinator after each account batch. Geth-aligned: bytecodes and
    * storage tasks are populated inline from processAccountResponse(), not queried after account download completes.
    */
  final case class IncrementalContractData(
      codeHashes: Seq[ByteString],
      storageTasks: Seq[StorageTask]
  ) extends Command
  case object StateHealingComplete extends Command
  case object HealingAllPeersStateless extends Command
  final case class HealingRootUnservable(root: ByteString) extends Command
  case object StateValidationComplete extends Command

  // ── Group 3: Status / progress queries (C3 — explicit replyTo, OQ-5) ───────
  // The 11 sender() call sites collapse into these two query Commands. The reply
  // types (SyncProtocol.Status, SyncProgress) are unchanged; only the request gains
  // a replyTo. Callers update in Phase 3 (Classic callers use the AskPattern adapter).
  final case class GetStatus(replyTo: org.apache.pekko.actor.typed.ActorRef[SyncProtocol.Status]) extends Command
  final case class GetProgress(replyTo: org.apache.pekko.actor.typed.ActorRef[SyncProgress]) extends Command

  /** spec 004 (Decoupled Heal Serve-Root) T011/T012: SNAPSyncController → SyncController (parent). During healing, ask
    * the parent to fetch a newest-servable canonical header (networkBest − RecentRootMarginBlocks) via its own
    * dedicated PivotHeaderBootstrap slot — distinct from `StartRegularSyncBootstrap` (which is the pivot-refresh path
    * that mutates the walk root) and from `StorageRecoveryActor`'s recent-root requester. The parent replies with
    * `HealingServeRoot`.
    */
  case object RequestHealingServeRoot extends SyncProtocol.SyncControllerReply

  /** spec 004 T012: SyncController → SNAPSyncController reply with a newest-servable `(blockNumber, stateRoot)`, or
    * `stateRoot = None` if none could be fetched (no peers / bootstrap failed / timeout). On `None`, the controller
    * keeps the current serve root (U2) and does NOT push a HealingServeRootRefresh.
    */
  final case class HealingServeRoot(blockNumber: BigInt, stateRoot: Option[TrieRoot]) extends Command

  /** Signal from coordinators that the current pivot/stateRoot is likely not serveable by peers.
    *
    * This is analogous to Nethermind's ExpiredRootHash detection (empty payload + empty proofs).
    */
  final case class PivotStateUnservable(rootHash: ByteString, reason: String, consecutiveEmptyResponses: Int)
      extends Command

  /** Progress updates emitted by worker coordinators.
    *
    * These are deltas (increments), not absolute totals.
    */
  final case class ProgressAccountsSynced(count: Long) extends Command
  case object ProgressAccountsFinalizingTrie extends Command
  case object ProgressAccountsTrieFinalized extends Command
  final case class AccountTrieFinalized(finalizedRoot: ByteString) extends Command
  final case class AccountTrieFinalizationFailed(error: String) extends Command
  final case class ProgressBytecodesDownloaded(count: Long) extends Command
  final case class ProgressStorageSlotsSynced(count: Long) extends Command
  final case class ProgressNodesHealed(count: Long) extends Command
  final case class ProgressAccountEstimate(estimatedTotal: Long) extends Command
  final case class ProgressStorageContracts(completedContracts: Int, totalContracts: Int) extends Command

  /** Sent by `StorageRangeCoordinator` to the controller when its pending-task queue crosses a watermark. The
    * controller forwards it to `AccountRangeCoordinator` as a `StorageQueuePressure` message so account workers stop
    * producing new storage tasks during back-pressure. Workers already in flight always run to completion.
    */
  final case class StorageBackpressureChanged(paused: Boolean) extends Command

  /** Sent by `ByteCodeCoordinator` to the controller when its pending-task queue crosses a watermark. Forwarded to
    * `AccountRangeCoordinator` as `ByteCodeQueuePressure`. Bytecode tasks are produced by account-range completions
    * (one task per batch of code hashes), so the pause/resume pattern is the same as storage. AccountRangeCoordinator
    * pauses dispatch if EITHER downstream coordinator is over its high-water mark.
    */
  final case class ByteCodeBackpressureChanged(paused: Boolean) extends Command

  // ── Group 5 (cont.): HealingStagnated — coordinator → SSC progress push ────
  // TNHC sends this OUTBOUND (it was pulled out of TrieNodeHealingCoordinatorMessage
  // in S3, then lived in actors/Messages.scala). SSC receives it, so for the sealed
  // Command ADT it is relocated here. Phase 3 removes actors.Messages.HealingStagnated
  // and updates TNHC's two send sites to SNAPSyncController.HealingStagnated.
  final case class HealingStagnated(healed: Long, pending: Long) extends Command

  private[snap] def shouldSkipHealingAfterDownloads(
      snapSyncConfig: SNAPSyncConfig,
      resumedStaleCursors: Boolean
  ): Boolean =
    // The deferred-merkleization fast path (skip healing, lazy-heal during block execution) is
    // ONLY safe when the trie was built fresh this session. If any account-range cursor was
    // resumed from a prior session (against a possibly drifted root), the delta MUST be walked
    // and re-fetched from the current pivot root before completion — otherwise the state is
    // handed off with silent holes. So a resume forces the full healing walk.
    // #1371 (restored — #1384's stale base re-added the `!storagePhaseForceCompleted` term, which
    // routed force-completed deferred-merkleization back into the full heal walk → the
    // "exactly 1 node, healed=0 forever" stall). Force-completion does not invalidate the
    // freshly-built trie; only a resumed stale cursor does.
    snapSyncConfig.deferredMerkleization && !resumedStaleCursors

  /** Freshness gate for `refreshPivotInPlace`: reject candidate pivots whose source peer is more than `maxStaleness`
    * blocks behind the CL-driven head.
    *
    * Background: the post-merge SNAP refresh path used to take `max(snapPeer.maxBlockNumber)` as the new pivot with no
    * comparison against the actual chain tip. When the only SNAP-capable peers left in the pool were lagging (e.g. one
    * still reporting block 10_447_000 while sepolia's CL head was at 10_847_xxx — observed May 13 2026), the refresh
    * kept picking the stuck-peer's block, then immediately tripped the same-root fallback and restart. This filter
    * blocks that path: on post-merge chains we know the authoritative tip (via `clPivotHint`), so we require pivot
    * sources to be within `maxStaleness` of it. Pre-merge chains pass through unchanged (clHeadNumber=None).
    *
    * @param networkBest
    *   the best SNAP peer's maxBlockNumber
    * @param clHeadNumber
    *   the consensus-layer head block number, when available
    * @param maxStaleness
    *   configured `maxPivotStalenessBlocks` (default 4096) Yields `Right(())` if the candidate is fresh enough, or
    *   `Left(floor)` with the rejected freshness floor for diagnostic logging at the call site.
    */
  private[snap] def pivotPassesFreshnessFloor(
      networkBest: BigInt,
      clHeadNumber: Option[BigInt],
      maxStaleness: Long
  ): Either[BigInt, Unit] = clHeadNumber match
    case Some(clHead) =>
      val floor = clHead - maxStaleness
      if networkBest < floor then Left(floor) else Right(())
    case None =>
      // Pre-merge / pre-CL-hint state: no authoritative tip to compare against. Preserve the
      // legacy "take whatever peer offers" behavior.
      Right(())

  def apply(
      blockchainReader: BlockchainReader,
      blockchainWriter: BlockchainWriter,
      appStateStorage: AppStateStorage,
      stateStorage: StateStorage,
      evmCodeStorage: EvmCodeStorage,
      flatSlotStorage: FlatSlotStorage,
      networkPeerManager: TypedActorRef[com.chipprbots.ethereum.network.NetworkPeerManagerActor.Command],
      peerEventBus: TypedActorRef[com.chipprbots.ethereum.network.PeerEventBusActor.Command],
      syncConfig: SyncConfig,
      snapSyncConfig: SNAPSyncConfig,
      scheduler: Scheduler,
      blacklist: Blacklist,
      syncController: TypedActorRef[SyncProtocol.SyncControllerReply],
      validatorFactory: MptStorage => StateValidator = new StateValidator(_)
  )(implicit ec: ExecutionContext): Behavior[Command] =
    Behaviors.setup[Command] { ctx =>
      Behaviors.withTimers[Command] { timers =>
        new SNAPSyncControllerImpl(
          ctx,
          timers,
          blockchainReader,
          blockchainWriter,
          appStateStorage,
          stateStorage,
          evmCodeStorage,
          flatSlotStorage,
          networkPeerManager,
          peerEventBus,
          syncConfig,
          snapSyncConfig,
          scheduler,
          blacklist,
          syncController,
          validatorFactory
        ).start() // #1378: start() arms the 5s PollHandshakedPeers timer that populates the
        //          controller's peerListHelper. Calling startSnapSync() directly bypasses it,
        //          leaving snapPeersForPivot permanently empty → pivot never selected.
        //          Reverted by #1384's stale-base clobber; restored.
      }
    }

case class SNAPSyncConfig(
    enabled: Boolean = true,
    // How far behind the perceived head to place the pivot.
    // SNAP servers generally only guarantee serving *very recent* state; keeping this small improves storage serving.
    pivotBlockOffset: Long = 64,
    // If bootstrap takes too long and the selected pivot drifts too far behind the current network head,
    // abandon it and re-select a fresher pivot.
    maxPivotStalenessBlocks: Long = 4096,
    accountConcurrency: Int = 16,
    storageConcurrency: Int = 16,
    storageBatchSize: Int = 128,
    storageInitialResponseBytes: Int = 1048576,
    storageMinResponseBytes: Int = 131072,
    healingBatchSize: Int = 16,
    healingConcurrency: Int = 16,
    healingMaxInFlightPerPeer: Int = 1,
    // Cap on the post-SNAP frontier-rebuild DFS `visited` LRU (entries). Bounds heap during the
    // full-state walk (≈ cap × 80 B; 4,000,000 ≈ 320 MB) so the walk completes instead of OOM-looping.
    // Completeness is independent of this value. See docs/design/healing-frontier-scale.md.
    healingVisitedCap: Int = actors.TrieNodeHealingCoordinator.DefaultVisitedCap,
    // Layer 2: persist the outstanding healing frontier so a restart resumes (O(frontier)) instead of
    // re-walking the full state. Default false (ships dark). See docs/design/healing-frontier-scale.md.
    healingFrontierPersistence: Boolean = false,
    prunedHealVerification: Boolean = true, // #4 (restored): pruned post-heal verification vs full ~90M-node walk
    // Operator ceiling for BFS level parallelism. Effective =
    // min(this, min(nproc, max(healingMinParallelism, nproc - healingReservedCores))).
    healingTraversalParallelism: Int = actors.TrieNodeHealingCoordinator.DefaultBfsParallelism,
    // Floor on effective BFS parallelism and cores reserved for the live node + GC (spec 002 R3 §1).
    healingMinParallelism: Int = actors.TrieNodeHealingCoordinator.DefaultMinParallelism,
    healingReservedCores: Int = actors.TrieNodeHealingCoordinator.DefaultReservedCores,
    healingFrontierHighWater: Int = actors.TrieNodeHealingCoordinator.DefaultFrontierHighWater,
    healingFrontierLowWater: Int = actors.TrieNodeHealingCoordinator.DefaultFrontierLowWater,
    // Post-SNAP healing livelock fix. When true (default), a healing STAGNATION (slow progress) holds the
    // healing pivot fixed and resumes dispatch against the held root instead of rolling the pivot. Rolling on
    // stagnation orphans the in-flight verification BFS and resets its completeness gate, so on slow/peer-scarce
    // nodes the gate can never close and regular sync is never reached, even though the missing nodes heal.
    // Holding is consensus-safe: GetTrieNodes fetches missing nodes by hash (content-addressed), so a stale
    // root stays ~99.9% servable; regular sync fills any residual gap on-demand. The GENUINE all-peers-stateless
    // roll (HealingAllPeersStateless) is unaffected. Set false to restore legacy roll-on-stagnation.
    healHoldPivotOnStagnation: Boolean = true,
    // Scoped post-heal verification (spec 003). When true (default), the post-heal completion
    // verification re-walks ONLY the subtrees rooted at the nodes healed this round instead of
    // re-seeding the state root and re-walking the whole trie. It engages only when the durable
    // completeness marker proves a prior full-trie clean walk against the current root AND the
    // healed-paths set is non-empty, in-bound, and same-root; otherwise it falls back to the
    // unchanged full-root verification. Byte-parity of the completion decision/state root/marker is
    // preserved. Set false to force the conservative full-root verification on every round.
    scopedHealVerification: Boolean = true,
    // Upper bound on the in-memory healed-paths set (spec 003 FR-011). Over-bound rounds fall back to
    // full-root verification rather than growing the set, bounding its worst-case heap.
    scopedHealMaxPaths: Int = 200000,
    // Decoupled heal serve-root (spec 004 FR-008). When true (default), the healing fetch targets an advancing
    // newest-servable serve root while the completeness walk stays pinned to the fixed walk root. Off ⇒ coupled
    // behaviour (fetch uses the walk root), byte-identical to today. Consensus-safety rests on the unchanged
    // content-hash check at handleResponse (a node is stored only if keccak256 == the walk root's task hash).
    decoupledHealServeRoot: Boolean = true,
    // FR-006 surfacing threshold: after this many unsatisfied heal attempts with no serve-root advance in
    // between, the coordinator surfaces the stuck task (log + metric). NEVER force-completes.
    decoupledHealMaxAttemptsNoRefresh: Int = 12,
    // spec 009 (Moving-Root Delta Heal). Read by TrieNodeHealingCoordinator (single served heal root in
    // requestNextBatch; seed-absent-root vs the HealingRootUnservable handoff in StartTrieNodeHealing; the
    // HealingServeRootRefresh no-op) and by SNAPSyncController (re-peg trigger in maybeRequestHealingServeRoot;
    // bounded last-resort in refreshPivotInPlace). Default true = the ETC SNAP default; flag-OFF restores the
    // spec-004 decoupled serve-root path byte-for-byte (rollback path, FR-007).
    movingRootDeltaHeal: Boolean = true,
    stateValidationEnabled: Boolean = true,
    maxRetries: Int = 3,
    timeout: FiniteDuration = 30.seconds,
    maxSnapSyncFailures: Int = 5, // Max critical failures before entering dormant mode
    // Grace period after bootstrap to wait for snap/1-capable peers. If no connected peer
    // advertises snap/1 within this window, SNAP enters dormant mode and retries later.
    snapCapabilityGracePeriod: FiniteDuration = 30.seconds,
    // Account stagnation timeout: if no account range tasks complete within this window,
    // record a critical failure (may trigger dormant mode). Reduced from 15 minutes to catch
    // non-snap peers faster.
    accountStagnationTimeout: FiniteDuration = 10.minutes,
    maxInFlightPerPeer: Int = 5,
    accountInitialResponseBytes: Int = 524288,
    accountMinResponseBytes: Int = 102400,
    chainDownloadEnabled: Boolean = true,
    chainDownloadMaxConcurrentRequests: Int = 2,
    chainDownloadBoostedConcurrentRequests: Int = 16,
    // Concurrency budget for chain backfill once SNAP state is finalised and regular sync has started.
    // Smaller than `chainDownloadMaxConcurrentRequests` so backfill yields peer slots to regular sync.
    chainBackfillConcurrentRequests: Int = 2,
    chainDownloadTimeout: FiniteDuration = 10.seconds,
    minSnapPeers: Int = 3,
    snapPeerEvictionInterval: FiniteDuration = 15.seconds,
    maxEvictionsPerCycle: Int = 3,
    deferredMerkleization: Boolean = true,
    // Bug 30b: post-SNAP storage recovery can't refresh the pivot root. If every peer
    // rejects the saved root for this long with no slot progress, abandon recovery and
    // let regular sync's on-demand GetTrieNodes pick up missing subtrees.
    storageRecoveryAbandonTimeout: FiniteDuration = 10.minutes,
    // Recent-root roll: before abandoning, post-SNAP storage recovery rolls its download root onto a
    // recent canonical root that peers can still serve (the aged pivot's ~128-block / ~27-min serve
    // window has long expired by the time a multi-hour recovery scan finishes). Cold contracts —
    // storage unchanged since the pivot, i.e. ~all the randomly SNAP-skipped roots — fill identically
    // because trie nodes are content-addressed. This bounds the number of rolls so a peerless or
    // hot-only residue still terminates into the abandon path instead of rolling forever.
    storageRecoveryMaxRootRolls: Int = 8,
    // Post-SNAP recovery scan: when true (default), one combined parallel single-pass scan
    // (CombinedRecoveryScanner) walks the trie checking BOTH bytecode and storage per account, sharded
    // across `recoveryScanConcurrency` workers, persisting per-shard progress so a crash resumes from the
    // last completed shard. Replaces the two legacy single-threaded full-trie walks. Set false to fall
    // back to the legacy per-phase scan actors.
    parallelRecoveryScan: Boolean = true,
    // Worker count for the combined scan. Default 3 — reserve a core + memory for the live node/GC on a
    // 4-core box (the scan runs read-only against the on-disk trie; the node is otherwise idle during it).
    recoveryScanConcurrency: Int = 3,
    // Branch levels to descend when partitioning the trie into shards (1 ⇒ up to 16 shards).
    recoveryScanShardDepth: Int = 1,
    // Static SNAP server peers: addresses to always maintain a connection with during SNAP sync.
    // Use for local SNAP-serving nodes (e.g. Besu with --snapsync-server-enabled) that may
    // disconnect after storage phase but are needed for trie node healing.
    // Format: enode://PUBKEY@HOST:PORT
    snapServerPeers: List[java.net.URI] = Nil,
    /** Cap on per-account streaming storage tries held in memory at once. Each `SnapHashTrie` wrapper bounds its own
      * working set to ~8 MiB (`SnapHashTrie.DefaultBatchSizeBytes`), so the worst-case storage-processing footprint is
      * `maxConcurrentStorageAccounts × 8 MiB`. Default 256 → ~2 GiB ceiling, independent of chain size. Storage
      * dispatch defers new-account requests when at the cap; continuations for in-flight accounts still proceed. Raise
      * via `sync.snap-sync.max-concurrent-storage-accounts` for larger peer pools.
      */
    maxConcurrentStorageAccounts: Int = 256,
    /** Trie node storage scheme. `Hash` (default) for ETC — nodes keyed by keccak256, no pruning needed. `Path` for ETH
      * full nodes — nodes keyed by nibble path, enabling inline pruning.
      *
      * Override via `sync.snap-sync.storage-scheme = "path"` in the HOCON config. Do NOT add an explicit `= "hash"` to
      * ETC configs; the default is Hash and the DB is scheme-locked on first write (startup guard enforces this).
      */
    storageScheme: StorageScheme = StorageScheme.Hash
)

object SNAPSyncConfig:
  def fromConfig(config: com.typesafe.config.Config): SNAPSyncConfig =
    val snapConfig = config.getConfig("snap-sync")

    SNAPSyncConfig(
      enabled = snapConfig.getBoolean("enabled"),
      pivotBlockOffset = snapConfig.getLong("pivot-block-offset"),
      maxPivotStalenessBlocks =
        if snapConfig.hasPath("max-pivot-staleness-blocks") then snapConfig.getLong("max-pivot-staleness-blocks")
        else 4096,
      accountConcurrency = snapConfig.getInt("account-concurrency"),
      storageConcurrency = snapConfig.getInt("storage-concurrency"),
      storageBatchSize = snapConfig.getInt("storage-batch-size"),
      storageInitialResponseBytes =
        if snapConfig.hasPath("storage-initial-response-bytes") then snapConfig.getInt("storage-initial-response-bytes")
        else 1048576,
      storageMinResponseBytes =
        if snapConfig.hasPath("storage-min-response-bytes") then snapConfig.getInt("storage-min-response-bytes")
        else 131072,
      healingBatchSize = snapConfig.getInt("healing-batch-size"),
      healingConcurrency =
        if snapConfig.hasPath("healing-concurrency") then snapConfig.getInt("healing-concurrency")
        else 16,
      healingMaxInFlightPerPeer =
        if snapConfig.hasPath("healing-max-inflight-per-peer") then snapConfig.getInt("healing-max-inflight-per-peer")
        else 1,
      healingVisitedCap =
        if snapConfig.hasPath("healing-visited-cap") then snapConfig.getInt("healing-visited-cap")
        else actors.TrieNodeHealingCoordinator.DefaultVisitedCap,
      healingFrontierPersistence = snapConfig.hasPath("healing-frontier-persistence") &&
        snapConfig.getBoolean("healing-frontier-persistence"),
      prunedHealVerification =
        if snapConfig.hasPath("pruned-heal-verification") then snapConfig.getBoolean("pruned-heal-verification")
        else true,
      healingTraversalParallelism =
        if snapConfig.hasPath("healing-traversal-parallelism") then snapConfig.getInt("healing-traversal-parallelism")
        else actors.TrieNodeHealingCoordinator.DefaultBfsParallelism,
      healingMinParallelism =
        if snapConfig.hasPath("healing-min-parallelism") then snapConfig.getInt("healing-min-parallelism")
        else actors.TrieNodeHealingCoordinator.DefaultMinParallelism,
      healingReservedCores =
        if snapConfig.hasPath("healing-reserved-cores") then snapConfig.getInt("healing-reserved-cores")
        else actors.TrieNodeHealingCoordinator.DefaultReservedCores,
      healingFrontierHighWater =
        if snapConfig.hasPath("healing-frontier-high-water") then snapConfig.getInt("healing-frontier-high-water")
        else actors.TrieNodeHealingCoordinator.DefaultFrontierHighWater,
      healingFrontierLowWater =
        if snapConfig.hasPath("healing-frontier-low-water") then snapConfig.getInt("healing-frontier-low-water")
        else actors.TrieNodeHealingCoordinator.DefaultFrontierLowWater,
      healHoldPivotOnStagnation =
        if snapConfig.hasPath("heal-hold-pivot-on-stagnation") then
          snapConfig.getBoolean("heal-hold-pivot-on-stagnation")
        else true,
      scopedHealVerification =
        if snapConfig.hasPath("scoped-heal-verification") then snapConfig.getBoolean("scoped-heal-verification")
        else true,
      scopedHealMaxPaths =
        if snapConfig.hasPath("scoped-heal-max-paths") then snapConfig.getInt("scoped-heal-max-paths")
        else 200000,
      decoupledHealServeRoot =
        if snapConfig.hasPath("decoupled-heal-serve-root") then snapConfig.getBoolean("decoupled-heal-serve-root")
        else true,
      decoupledHealMaxAttemptsNoRefresh =
        if snapConfig.hasPath("decoupled-heal-max-attempts-no-refresh") then
          snapConfig.getInt("decoupled-heal-max-attempts-no-refresh")
        else 12,
      movingRootDeltaHeal =
        if snapConfig.hasPath("moving-root-delta-heal") then snapConfig.getBoolean("moving-root-delta-heal")
        else true,
      stateValidationEnabled = snapConfig.getBoolean("state-validation-enabled"),
      maxRetries = snapConfig.getInt("max-retries"),
      timeout = snapConfig.getDuration("timeout").toMillis.millis,
      maxSnapSyncFailures =
        if snapConfig.hasPath("max-snap-sync-failures") then snapConfig.getInt("max-snap-sync-failures")
        else 5,
      snapCapabilityGracePeriod =
        if snapConfig.hasPath("snap-capability-grace-period") then
          snapConfig.getDuration("snap-capability-grace-period").toMillis.millis
        else 30.seconds,
      accountStagnationTimeout =
        if snapConfig.hasPath("account-stagnation-timeout") then
          snapConfig.getDuration("account-stagnation-timeout").toMillis.millis
        else 3.minutes,
      maxInFlightPerPeer =
        if snapConfig.hasPath("max-inflight-per-peer") then snapConfig.getInt("max-inflight-per-peer")
        else 5,
      accountInitialResponseBytes =
        if snapConfig.hasPath("account-initial-response-bytes") then snapConfig.getInt("account-initial-response-bytes")
        else 524288,
      accountMinResponseBytes =
        if snapConfig.hasPath("account-min-response-bytes") then snapConfig.getInt("account-min-response-bytes")
        else 102400,
      chainDownloadEnabled =
        if snapConfig.hasPath("chain-download-enabled") then snapConfig.getBoolean("chain-download-enabled")
        else true,
      chainDownloadMaxConcurrentRequests =
        if snapConfig.hasPath("chain-download-max-concurrent-requests") then
          snapConfig.getInt("chain-download-max-concurrent-requests")
        else 2,
      chainDownloadBoostedConcurrentRequests =
        if snapConfig.hasPath("chain-download-boosted-concurrent-requests") then
          snapConfig.getInt("chain-download-boosted-concurrent-requests")
        else 16,
      chainBackfillConcurrentRequests =
        if snapConfig.hasPath("chain-backfill-concurrent-requests") then
          snapConfig.getInt("chain-backfill-concurrent-requests")
        else 2,
      chainDownloadTimeout =
        if snapConfig.hasPath("chain-download-timeout") then
          snapConfig.getDuration("chain-download-timeout").toMillis.millis
        else 10.seconds,
      minSnapPeers =
        if snapConfig.hasPath("min-snap-peers") then snapConfig.getInt("min-snap-peers")
        else 3,
      snapPeerEvictionInterval =
        if snapConfig.hasPath("snap-peer-eviction-interval") then
          snapConfig.getDuration("snap-peer-eviction-interval").toMillis.millis
        else 15.seconds,
      maxEvictionsPerCycle =
        if snapConfig.hasPath("max-evictions-per-cycle") then snapConfig.getInt("max-evictions-per-cycle")
        else 3,
      deferredMerkleization =
        if snapConfig.hasPath("deferred-merkleization") then snapConfig.getBoolean("deferred-merkleization")
        else true,
      storageRecoveryAbandonTimeout =
        if snapConfig.hasPath("storage-recovery-abandon-timeout") then
          snapConfig.getDuration("storage-recovery-abandon-timeout").toMillis.millis
        else 10.minutes,
      storageRecoveryMaxRootRolls =
        if snapConfig.hasPath("storage-recovery-max-root-rolls") then
          snapConfig.getInt("storage-recovery-max-root-rolls")
        else 8,
      parallelRecoveryScan =
        if snapConfig.hasPath("parallel-recovery-scan") then snapConfig.getBoolean("parallel-recovery-scan")
        else true,
      recoveryScanConcurrency =
        if snapConfig.hasPath("recovery-scan-concurrency") then snapConfig.getInt("recovery-scan-concurrency")
        else 3,
      recoveryScanShardDepth =
        if snapConfig.hasPath("recovery-scan-shard-depth") then snapConfig.getInt("recovery-scan-shard-depth")
        else 1,
      snapServerPeers =
        if snapConfig.hasPath("snap-server-peers") then
          snapConfig
            .getStringList("snap-server-peers")
            .toArray
            .toList
            .flatMap { s =>
              try Some(new java.net.URI(s.toString))
              catch case _: Exception => None
            }
        else Nil,
      maxConcurrentStorageAccounts =
        if snapConfig.hasPath("max-concurrent-storage-accounts") then
          snapConfig.getInt("max-concurrent-storage-accounts")
        else 256,
      storageScheme =
        if snapConfig.hasPath("storage-scheme") then StorageScheme.fromString(snapConfig.getString("storage-scheme"))
        else StorageScheme.Hash
    )

// StateValidator has been extracted to StateValidator.scala
// SyncProgressMonitor and SyncProgress have been extracted to SyncProgressMonitor.scala
