package com.chipprbots.ethereum.blockchain.sync.snap

import java.util.concurrent.atomic.AtomicBoolean

import org.apache.pekko.actor.Scheduler
import org.apache.pekko.actor.typed.ActorRef as TypedActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.PostStop
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.TimerScheduler
import org.apache.pekko.util.ByteString

import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*

import com.chipprbots.ethereum.blockchain.sync.Blacklist
import com.chipprbots.ethereum.blockchain.sync.PeerListHelper
import com.chipprbots.ethereum.blockchain.sync.PeerListSupportNg
import com.chipprbots.ethereum.blockchain.sync.SyncProtocol
import com.chipprbots.ethereum.blockchain.sync.snap.controller.ChildFactories
import com.chipprbots.ethereum.blockchain.sync.snap.controller.CoordinatorHandles
import com.chipprbots.ethereum.blockchain.sync.snap.controller.DownloadSupervisor
import com.chipprbots.ethereum.blockchain.sync.snap.controller.DownloadSupervisorState
import com.chipprbots.ethereum.blockchain.sync.snap.controller.HealingOrchestrator
import com.chipprbots.ethereum.blockchain.sync.snap.controller.HealingOrchestratorState
import com.chipprbots.ethereum.blockchain.sync.snap.controller.HeapWatchdogStart
import com.chipprbots.ethereum.blockchain.sync.snap.controller.PhaseFlags
import com.chipprbots.ethereum.blockchain.sync.snap.controller.PivotRefresher
import com.chipprbots.ethereum.blockchain.sync.snap.controller.PivotRefresherState
import com.chipprbots.ethereum.blockchain.sync.snap.controller.PivotSelector
import com.chipprbots.ethereum.blockchain.sync.snap.controller.PivotSelectorState
import com.chipprbots.ethereum.blockchain.sync.snap.controller.ShutdownApi
import com.chipprbots.ethereum.blockchain.sync.snap.controller.SnapControllerEnv
import com.chipprbots.ethereum.blockchain.sync.snap.controller.SnapFinalization
import com.chipprbots.ethereum.blockchain.sync.snap.controller.SnapFinalizationState
import com.chipprbots.ethereum.blockchain.sync.snap.controller.SnapPeerPool
import com.chipprbots.ethereum.blockchain.sync.snap.controller.SnapPeerPoolState
import com.chipprbots.ethereum.blockchain.sync.snap.controller.SnapResumePlanner
import com.chipprbots.ethereum.blockchain.sync.snap.controller.SnapResumePlannerState
import com.chipprbots.ethereum.blockchain.sync.snap.controller.SnapSharedState
import com.chipprbots.ethereum.blockchain.sync.snap.controller.SnapStartApi
import com.chipprbots.ethereum.blockchain.sync.snap.controller.StagnationWatchdog
import com.chipprbots.ethereum.blockchain.sync.snap.controller.StagnationWatchdogState
import com.chipprbots.ethereum.blockchain.sync.snap.controller.StateIngest
import com.chipprbots.ethereum.blockchain.sync.snap.controller.StateValidationModule
import com.chipprbots.ethereum.blockchain.sync.snap.controller.StateValidationState
import com.chipprbots.ethereum.blockchain.sync.snap.controller.SyncLifecycle
import com.chipprbots.ethereum.blockchain.sync.snap.controller.SyncLifecycleState
import com.chipprbots.ethereum.blockchain.sync.snap.controller.SyncingApi
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.CachedReferenceCountedStateStorage
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.FlatSlotStorage
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.db.storage.PathNodeStorage
import com.chipprbots.ethereum.db.storage.SnapStorageDoneStorage
import com.chipprbots.ethereum.db.storage.SnapSyncProgressStorage
import com.chipprbots.ethereum.db.storage.StateStorage
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.BlockchainWriter
import com.chipprbots.ethereum.domain.TrieRoot
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.network.p2p.messages.SNAP
import com.chipprbots.ethereum.utils.ByteStringUtils.ByteStringOps
import com.chipprbots.ethereum.utils.Config.SyncConfig

private class SNAPSyncControllerImpl(
    val ctx: ActorContext[SNAPSyncController.Command],
    val timers: TimerScheduler[SNAPSyncController.Command],
    val blockchainReader: BlockchainReader,
    val blockchainWriter: BlockchainWriter,
    val appStateStorage: AppStateStorage,
    val stateStorage: StateStorage,
    val evmCodeStorage: EvmCodeStorage,
    val flatSlotStorage: FlatSlotStorage,
    val networkPeerManager: TypedActorRef[com.chipprbots.ethereum.network.NetworkPeerManagerActor.Command],
    val peerEventBus: TypedActorRef[com.chipprbots.ethereum.network.PeerEventBusActor.Command],
    val syncConfig: SyncConfig,
    val snapSyncConfig: SNAPSyncConfig,
    val scheduler: Scheduler,
    blacklist: Blacklist,
    val syncController: TypedActorRef[SyncProtocol.SyncControllerReply],
    // Factory for `StateValidator` so unit tests can inject a fake. Production
    // default is a thin `new StateValidator(_)` wrapper; tests can supply a
    // `FakeStateValidator` that returns canned results, delays, or throws.
    val validatorFactory: MptStorage => StateValidator,
    // Test seam: None (production) reads the process-global chain config exactly as before. The "test" network
    // config has no terminal-total-difficulty, so without this no actor in this module's suite could ever
    // exercise the PoS/CL-anchored paths live.
    isPoSChainOverride: Option[Boolean] = None,
    // Test seams (spec 016 T012). Production defaults build exactly what the code built before they existed.
    val childFactories: ChildFactories = ChildFactories.production,
    heapWatchdogStart: HeapWatchdogStart = HeapWatchdogStart.production,
    intakeBudgetOverride: Option[SnapIntakeBudget] = None,
    // #1555: the pivot whose finalization failed in the previous SNAP controller (SyncController's HealingImpossible
    // restart), or None. On PoS, the accounts-complete recovery will not resume healing at or below it.
    val failedFinalizationPivot: Option[BigInt] = None
)(implicit val ec: ExecutionContext)
    extends CoordinatorHandles
    with PhaseFlags
    with SnapSharedState
    with SnapControllerEnv
    with StateValidationModule
    with StateValidationState
    with SnapPeerPool
    with SnapPeerPoolState
    with SnapFinalization
    with SnapFinalizationState
    with ShutdownApi
    with SnapResumePlanner
    with SnapResumePlannerState
    with HealingOrchestrator
    with HealingOrchestratorState
    with StagnationWatchdog
    with StagnationWatchdogState
    with PivotSelector
    with PivotSelectorState
    with SnapStartApi
    with SyncingApi
    with SyncLifecycle(heapWatchdogStart)
    with SyncLifecycleState
    with PivotRefresher
    with PivotRefresherState
    with DownloadSupervisor
    with DownloadSupervisorState:

  import SNAPSyncController.*
  import SyncPhase.*

  // P11: plain SLF4J logger, safe to call from Future callbacks (off the actor thread).
  // Used ONLY at the two Recovery-streaming Future `.foreach` sites; all on-thread logging uses ctx.log.
  // Implements `SnapControllerEnv.asyncLog` (spec 016 T041a).
  val asyncLog: org.slf4j.Logger = org.slf4j.LoggerFactory.getLogger(getClass)

  // ── Memory bounds (spec 014) ─────────────────────────────────────────────────────────────────
  // Shared admission gate for SNAP contract work. Producers (AccountRangeCoordinator's account dispatch and carried
  // replay, the accounts-complete recovery stream) read it synchronously before adding work; the storage and bytecode
  // coordinators acknowledge receipt and publish their queue depth. Never a mailbox hop on the pause path.
  // Implements `CoordinatorHandles.intakeBudget` (spec 016 P1). A strict val: the initializer must run at construction.
  val intakeBudget: SnapIntakeBudget = intakeBudgetOverride.getOrElse(
    new SnapIntakeBudget(
      maxPendingStorageTasks = snapSyncConfig.maxPendingStorageTasks,
      maxPendingByteCodeHashes = snapSyncConfig.maxPendingByteCodeHashes
    )
  )

  // ── Timer keys (Behaviors.withTimers; replaces the Classic Cancellable fields) ───────────────
  // Recurring keys reuse the Command case object itself; one-shot keys are string literals.
  val BootstrapCheckKey = "bootstrap-check"
  val PivotBootstrapRetryKey = "pivot-bootstrap-retry"
  val SnapCapabilityCheckKey = "snap-capability-check"
  private val PollHandshakedPeersKey = "poll-handshaked-peers"
  // Probe timeouts are keyed by the probe's BigInt requestId (C4) — see startPivotProbe.

  // Dedicated dispatcher for the long-running synchronous trie walks inside
  // `validateState()`. See `pekko.conf` for the rationale. Implements `SnapControllerEnv.snapValidationEc`.
  val snapValidationEc: ExecutionContext =
    ctx.system.classicSystem.dispatchers.lookup("snap-validation-dispatcher")

  // Typed-compatible peer-list management (Group PLN), replacing the Classic PeerListSupportNg mixin.
  // The handshaked-peers poll (PollHandshakedPeers timer) and the PeerDisconnected bridge live in the
  // behaviors; bestEth68PeerForCalibration tracking is preserved via the onPeerListUpdated override.
  val peerListHelper: PeerListHelper =
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

  val bigIntReverseOrdering: Ordering[BigInt] = Ordering[BigInt].reverse

  // SNAP download progress storage (namespace 'p'). Shares the same RocksDB DataSource as
  // AppStateStorage but uses a dedicated namespace so progress survives crash-restart without
  // interfering with other state. Replaces AppStateStorage's plain-text SnapSyncProgress entry
  // (namespace 's', account-only). Storage cursor persistence is new.
  val snapProgressStorage = new SnapSyncProgressStorage(appStateStorage.dataSource)

  ctx.log.info("SNAPSyncController started with shared blacklist (cross-mode penalty propagation enabled)")

  // Writable MptStorage, lazily created when pivot block number is known.
  // Uses getBackingStorage(pivotBlockNumber) to ensure nodes are tagged with the
  // correct block number for proper reference counting in pruning modes.
  var mptStorage: Option[MptStorage] = None

  // PathScheme: create PathNodeStorage backed by the same RocksDB data source as flat storage.
  // None for HashScheme (default/ETC). Shared across coordinator restarts (data source is long-lived).
  // The Path store `stateIngest.pathNodes` hands to the modules (spec 016 T055); a strict val, built at construction.
  val pathNodeStorageOpt: Option[PathNodeStorage] =
    if snapSyncConfig.storageScheme == StorageScheme.Path then Some(new PathNodeStorage(flatSlotStorage.dataSource))
    else None

  // Implements `SnapControllerEnv.stateIngest` (spec 016 T055): the modules' only way to the stores. A strict val built
  // at construction from the constructor's stores and `pathNodeStorageOpt` above (so it must follow it); building it
  // only stores the references.
  val stateIngest: StateIngest = StateIngest(
    appStateStorage,
    stateStorage,
    flatSlotStorage,
    pathNodeStorageOpt,
    evmCodeStorage,
    blockchainReader,
    blockchainWriter,
    validatorFactory,
    StateIngest.Durability.Default,
    snapProgressStorage,
    storageDoneStorage,
    healingFrontierStorageOpt,
    bfsQueueStorageOpt
  )

  // Storage-task completion markers (prefixed keys in the app-state column family): written by StorageRangeCoordinator
  // with each finished account's last flat slots; read on a resume so only unfinished storage tasks are re-queued.
  // Scoped to one SNAP cycle — cleared when the account phase starts without carried task files and when the storage
  // phase completes. See SnapStorageDoneStorage for exactly what a marker guarantees in each mode.
  val storageDoneStorage: SnapStorageDoneStorage = new SnapStorageDoneStorage(flatSlotStorage.dataSource)

  // Whether trie nodes written through the state storage reach its in-memory node cache rather than RocksDB (cached,
  // "inmemory" pruning): they reach disk one by one as the cache evicts them, or all at once at a forced persist. Read by
  // the storage-task markers below and by the healing spawn (spec 022 review vault V3).
  val trieNodesCachedInMemory: Boolean = stateStorage.isInstanceOf[CachedReferenceCountedStateStorage]

  // Markers are recorded only where a marker's claim holds once written: under Hash scheme + building the trie during
  // the download (deferredMerkleization = false) + cached ("inmemory") pruning, storage trie nodes go through
  // CachedReferenceCountedStorage.update into an in-memory cache, not RocksDB, so they could be lost with a marker on
  // disk. Path scheme writes through PathNodeStorage; archive/basic pruning write through; deferred builds no trie.
  val recordStorageDone: Boolean =
    snapSyncConfig.storageScheme == StorageScheme.Path || snapSyncConfig.deferredMerkleization || !trieNodesCachedInMemory
  if !recordStorageDone then
    ctx.log.info(
      "Storage-task completion markers off (Hash scheme + in-memory pruning buffers storage trie nodes): " +
        "a SNAP resume re-downloads every carried storage task"
    )

  // Actor-based coordinators (OQ-6 Option A: Typed refs, spawned via ctx.spawn) and the ChainDownloader live in
  // `CoordinatorHandles` (spec 016 P1): `accountRangeCoordinator`, `bytecodeCoordinator`, `storageRangeCoordinator`,
  // `trieNodeHealingCoordinator` and the `chainDownloader` handle.

  // Implements `CoordinatorHandles.stopChild`.
  protected def stopChild(child: TypedActorRef[Nothing]): Unit = ctx.stop(child)

  var chainDownloadComplete: Boolean = false
  // Deferred-backfill finalisation hold: Some(pivot header hash) while finalisation waits for the forward header
  // download to reach the pivot. Keyed by hash, not number, so a pivot that changed is never mistaken for the old one.
  var headerHold: Option[ByteString] = None

  // Monotonic counter appended to coordinator actor names so restarts don't collide
  // with still-stopping actors from the previous cycle.
  var coordinatorGeneration: Long = 0

  // Buffered CL-driven pivot hint. Populated whenever a `CLPivotHint` message arrives
  // from `SyncController`. Consumed by `startSnapSync()` to skip TD-based pivot selection
  // on post-merge chains. Only meaningful when `isPoSChain == true`. Closes #1207.
  var clPivotHint: Option[CLPivotHint] = None

  // Minimum pivot block enforced when re-entering SNAP from a RegularSyncStuck escape.
  // Prevents re-selecting the same pivot that caused the regular-sync stall.
  var minPivotHint: BigInt = BigInt(0)
  var clHintArrivedAtMs: Option[Long] = None

  // Captured once at construction. ETC mainnet has TTD=None and never goes down the
  // CL-driven path; Sepolia/mainnet have TTD set and switch off TD-based pivot entirely.
  val isPoSChain: Boolean =
    isPoSChainOverride.getOrElse(
      com.chipprbots.ethereum.utils.Config.blockchains.blockchainConfig.terminalTotalDifficulty.isDefined
    )

  // The hubs implement `SnapSharedState` (spec 016 T041a).
  val requestTracker: SNAPRequestTracker = new SNAPRequestTracker()(scheduler)

  var currentPhase: SyncPhase = Idle

  // Path scheme: set while the async path->hash publish runs (pivot, pivot state root). See startPathPublish.
  var pathPublish: Option[(BigInt, ByteString)] = None
  var pivotBlock: Option[BigInt] = None
  var stateRoot: Option[TrieRoot] = None

  // Preserved account range progress across SNAP sync restarts (core-geth parity).
  // Maps range `last` hash → current `next` position for ALL ranges (not just completed ones).
  // Content-addressed MPT storage survives pivot changes (accounts keyed by keccak256 hash),
  // so already-traversed keyspace doesn't need re-downloading if the pivot
  // hasn't drifted too far (within MaxPreservedPivotDistance blocks).
  var preservedRangeProgress: Map[ByteString, ByteString] = Map.empty
  var preservedAtPivotBlock: Option[BigInt] = None
  // Contract task files consistent with `preservedRangeProgress` (see AccountResumeCheckpoint). With them, partial
  // ranges resume from their cursor and the storage/bytecode work of the downloaded prefix is replayed, not re-derived
  // from the network. None => legacy behaviour (partial ranges restart from their start).
  var preservedTaskFiles: Option[ContractTaskFiles] = None
  // Generation of the most recently launched account coordinator; snapshots from older instances are dropped.
  var launchedAccountGeneration: Long = -1L
  // Task files the live account coordinator was carried from (its supervisor may re-copy from them).
  var currentCarrySource: Option[ContractTaskFiles] = None

  // Resume saved account-range cursors across this much pivot drift before falling back to a
  // full re-walk. Raised from 256 (~55 min ETC) to 50_000 (~1 week ETC) on 2026-06-01. The cap is
  // only a perf heuristic (very large drift => large healing delta where a cold re-walk may be
  // comparable), NOT a correctness boundary. Correctness comes from `resumedStaleCursors`, which
  // forces the healing walk from the new pivot root even under deferred-merkleization: complete
  // ranges and the prefixes of partial ranges (resumed mid-range since 2026-10-06, see
  // AccountRangeCoordinator "Resume from a mid-range cursor") are both reconciled by it.

  // The phase-complete and force-complete flags (`resumedStaleCursors`, `accountsComplete`, `bytecodePhaseComplete`,
  // `storagePhaseComplete`, `storagePhaseForceCompleted`, `awaitingHealedCode`, `healedCodeWaitExhausted`,
  // `bytecodeForceCompleted`, `forceCompleteStorageSent`) live in `PhaseFlags` (spec 016 P2), with `reset(kind)`.

  // Bytecode of accounts that arrived through trie HEALING. Account-range sync builds its code-hash list from the
  // account responses it receives, so an account created after the pivot and delivered only by healing is invisible to
  // it: the bytecode phase finishes with that code never requested. The healing coordinator reports those codeHashes
  // (HealedCodeHashes); they are fetched through the bytecode coordinator and SNAP is not finalised until they are
  // present (or `HealedCodeWaitMs` passes). Whatever is still missing at finalisation keeps `bytecodeRecoveryDone`
  // unset, so the next start's recovery scan finds it, and the importer fetches it on demand in the meantime.
  val healedCodeHashes: mutable.LinkedHashSet[ByteString] = mutable.LinkedHashSet.empty

  val progressMonitor: SyncProgressMonitor = new SyncProgressMonitor(scheduler)

  // Failure tracking — critical failures trigger dormant retry with exponential backoff.
  var criticalFailureCount: Int = 0
  var accountsAtLastCriticalFailure: Long = 0L

  // Dormant retry: when critical failures exhaust, preserve all RocksDB data and wait
  // for peers to re-index their snapshots with exponential backoff (3min–20min).
  var dormantRetryCount: Int = 0

  // Async validation state. `validationInProgress` is the re-entrance guard
  // for the trie-walk Future. `validationGeneration` is bumped at every
  // (a) fresh validation spawn, (b) `restartSnapSync`, and (c) post-pivot
  // mutation in `completePivotRefreshWithStateRoot`, so a long-running Future's
  // result that arrives after such a transition is dropped on the floor rather
  // than being applied against the wrong root. Both result-message handlers
  // and the scheduled `ValidationRetry` carry the generation they were
  // spawned/scheduled at and only honour matching values.
  var validationInProgress: Boolean = false
  var validationGeneration: Long = 0L

  // #1188: when the round-2 healing trie walk returns 0 missing, the entire
  // account+storage trie has just been DFS-walked end-to-end. Capture the root
  // it was clean against so `validateState()` can short-circuit the redundant
  // `validateAccountTrie + validateAllStorageTries` passes (which together can
  // exceed the time of the SNAP download itself on populated states).
  // Belt-and-suspenders: only honoured when the captured root *equals* the
  // current `stateRoot`, so any pivot refresh / restart naturally invalidates
  // the signal and full validation runs.
  var healingValidatedRoot: Option[TrieRoot] = None

  // Running total of unique codeHashes streamed in via `IncrementalContractData`. Used to set
  // `progressMonitor.estimatedTotalBytecodes` so the SNAP-sync dashboard's
  // `100 * downloaded / clamp_min(estimated_total, 1)` formula has a real denominator. Without
  // this, `estimated_total` stays at 0, the formula divides by 1 (the clamp floor), and the
  // dashboard reads `5,239,500%` for a normal in-flight bytecodes count.
  var bytecodesEstimatedTotal: Long = 0L

  // Retry counter for bootstrap-to-SNAP transition (exponential backoff: 2s→60s cap, max 10 retries)
  var bootstrapRetryCount: Int = 0

  // Pivot restart guard (prevents noisy rapid restarts if peer head fluctuates)
  var lastPivotRestartMs: Long = 0L
  val MinPivotRestartInterval: FiniteDuration = 30.seconds

  // Proactive pivot rolling: keep pivot within core-geth's 128-block snapshot window.
  // ETC network is predominantly core-geth peers; once the pivot ages beyond 128 blocks,
  // all external peers respond with accounts=[], proof=[] and only local Besu can serve.
  // Rolling proactively at 100 blocks preserves all downloaded state (unlike go-ethereum).
  var lastProactivePivotBlock: Option[BigInt] = None

  // Roll target margin: a proactive/reactive roll picks networkBest - max(pivotBlockOffset,
  // SnapServeWindowMargin) so the new root lands INSIDE peers' indexed snapshot window.
  // Rolling to the live tip (pivot-block-offset=0) probes a root core-geth peers haven't
  // indexed yet (their snapshot lags head by ~128 blocks) → readiness probe returns
  // "not indexed" forever → the pivot freezes (observed 2026-06-01: ETC stalled 70+ min
  // at 16.8M accounts). Margin ≈ half the serve window keeps the new pivot servable with
  // ~50 blocks of runway before it ages past the 100-block roll trigger.
  val SnapServeWindowMargin: BigInt = BigInt(50)

  // Pivot readiness probe: before committing a proactive pivot roll, we probe one peer with
  // the candidate root. Only if the peer's snapshot is indexed (returns ≥1 account) do we
  // dispatch PivotRefreshed to coordinators. Otherwise we defer and retry every 30s, letting
  // coordinators keep downloading on the old root. This eliminates the ~8-minute dead window.
  var pivotProbeRequestId: Option[BigInt] = None
  var proactiveRollNeedsProbe: Boolean = false // flag: stagnation check → completePivotRefresh
  var pendingProbeCommit: Option[(BigInt, BlockHeader, String)] = None // deferred commit args
  var lastProbeAttemptMs: Long = 0L
  val ProbeCooldownMs: Long = 30_000L // match DownloadStagnationCheckInterval
  var probeAttemptCount: Int = 0

  // Consecutive pivot refresh counter: when all peers are repeatedly stateless after
  // pivot refreshes, it strongly indicates no peer has a snapshot database. Each
  // PivotStateUnservable increments this; any successful account download resets it.
  // After MaxConsecutivePivotRefreshes, we record a critical failure and enter dormant
  // retry mode. Set to 10 (was 3) to tolerate ETC mainnet's 1-5 SNAP peers needing
  // 5+ minutes to re-index after serve-window expiry.
  var consecutivePivotRefreshes: Int = 0

  // Pending pivot refresh: when refreshPivotInPlace() needs a header from a peer,
  // it requests a bootstrap and stores the pending pivot here. When BootstrapComplete
  // arrives in the syncing state, the refresh is completed.
  var pendingPivotRefresh: Option[(BigInt, String)] = None

  var storageStagnationRefreshAttempted: Boolean = false
  var trieWalkInProgress: Boolean = false
  // spec 004 (Decoupled Heal Serve-Root) T011: serve-root refresh bookkeeping. The healing coordinator fetches
  // missing nodes against an advancing SERVE root while its completeness walk stays pinned to the walk root. We
  // ask the parent for a newest-servable root (networkBest − RecentRootMarginBlocks) on the healing tick when the
  // current serve root has aged > HealingServeRootMarginBlocks behind the network head. A single in-flight latch
  // (the bootstrap is a ~1s peer round-trip — never per-block) and a block number of the last pushed serve root.
  var healingServeRootRequestInFlight: Boolean = false
  // Set by the healing coordinator while a frontier walk runs with nothing pending or in flight. The walk is local-only,
  // so serve-window freshness is irrelevant, and a proactive heal-root re-peg would only supersede (and discard) it.
  val healingWalkLocalOnly: AtomicBoolean = new java.util.concurrent.atomic.AtomicBoolean(false)
  var lastHealingServeRootBlock: Option[BigInt] = None
  // spec 009 T009/T014 (Moving-Root Delta Heal — BOUNDED re-peg last-resort). Under `movingRootDeltaHeal` the heal
  // re-pegs the single heal root via refreshPivotInPlace (from maybeRequestHealingServeRoot). If a re-peg attempt
  // during StateHealing finds NO suitable served root (refreshPivotInPlace's newPivotOpt.isEmpty branch), this counter
  // increments; once it reaches the budget the controller takes the SAME fail-SAFE lazy-heal handoff (completeSnapSync
  // → on-demand GetTrieNodes during block execution, anchor guard STILL enforced) rather than looping the 30s
  // RetryPivotRefresh forever (the heal-churn #1371 fought). Fail-SAFE (never force-marks-done / weakens any gate),
  // never fail-OPEN. Reset on any successful re-peg (completePivotRefreshWithStateRoot) and on entering healing.
  var healRepegNoRootAttempts: Int = 0
  // Tracks whether the SNAP peer eviction recurring timer is active (replaces the snapPeerEvictionTask
  // Cancellable). startSnapPeerEviction() is idempotent — it only starts the timer once per session.
  var snapPeerEvictionStarted: Boolean = false
  // Tracks whether the snap-server-peers reconnect timer is active (replaces the snapServerPeersScheduler
  // Cancellable presence check). Reset on stopSnapOnlySchedules so a later phase can restart it.
  var snapServerPeersSchedulerStarted: Boolean = false

  // Best ETH68 peer (TD, maxBlockNumber) seen at any point during this SNAP session.
  // Preserved across peer disconnects so calibratePivotTD can use it at finalization even
  // if those peers have long since disconnected. Only updated when maxBlockNumber > 0
  // (eager probe has fired) — guards against the ETH68_BOOTSTRAP inflation pattern where
  // peerTD used directly without knowing peerBlock.
  var bestEth68PeerForCalibration: Option[(BigInt, BigInt)] = None

  // Distinct, much shorter threshold for the "coordinator reports 0 pending/0 active but never
  // sent StorageRangeSyncComplete" signature specifically. That reading is unambiguous: it means
  // the coordinator actor was restarted (RestartSupervisor wiping its in-memory tasks/tries — see
  // StorageRangeCoordinatorImpl's StackTrie-ordering crash history) or otherwise lost its state,
  // NOT that it is merely slow — a genuinely busy coordinator always reports pending>0 or
  // active>0, and a stats-fetch timeout is already excluded separately (isTimeoutResponse resets
  // the clock instead of falling through here). Waiting the full StorageStagnationThreshold in
  // this specific case is pure dead time: there is no in-flight work for a pivot refresh or
  // anything else to wait on, so nothing is gained by waiting longer than it takes to confirm the
  // reading is stable across a couple of poll ticks. Force-complete triggers the SAME existing
  // healing-recovery path either way (ForceCompleteStorage) — only the trigger latency changes.
  val StorageRestartedEmptyThreshold: FiniteDuration = 60.seconds
  // Platåberget soak v6 (2026-09-28): lastStorageProgressMs (below) resets on ANY
  // ProgressStorageSlotsSynced message carrying >10 slots — but that resets on progress from ANY
  // account, not necessarily the ones actually stuck. A handful of accounts retried indefinitely
  // (the unbounded-verification-failure-retry livelock, since bounded in
  // StorageRangeCoordinatorImpl's staleRootFailuresByAccount) can loop forever while OTHER,
  // unrelated accounts keep completing and resetting this clock — soak evidence: completed crept
  // from 67268 to 67616 (~15/min) while [STORAGE-STATE] sat at a fixed 99% for over 25 minutes,
  // pending oscillating 1-33 without ever draining. That is real, if slow, throughput and legitimately
  // resets lastStorageProgressMs — masking a stall the way it's currently defined.
  //
  // storageTailBaseline tracks a DIFFERENT signal: pending+active (the actual
  // remaining-work count), sampled every stagnation tick (DownloadStagnationCheckInterval, 30s).
  // The baseline only advances when remaining work is STRICTLY SMALLER than the last baseline —
  // oscillation without ever going lower doesn't count as progress. Reuses StorageStagnationThreshold
  // as its window rather than a new constant, per "build on the existing thresholds".
  //
  // Queue depth alone is NOT sufficient evidence of a livelock: pending+active also stays flat through huge-account
  // continuation chains and regrows after subtask splits, so a depth-only trigger fires on healthy tails (and bypasses
  // the late-stage guard on proactive pivot rolls). The signal therefore also requires the coordinator to report
  // repeated stale-local-root verification failures accumulated over the same window — see
  // SNAPSyncController.evaluateStorageTail. Reset at every storage-phase entry (restartSnapSync / wakeFromDormant
  // reuse this instance, so a stale baseline would otherwise fire a spurious refresh on a second phase's first tick).
  var storageTailBaseline: SNAPSyncController.StorageTailBaseline =
    SNAPSyncController.StorageTailBaseline.fresh(System.currentTimeMillis())
  var lastStorageProgressMs: Long = System.currentTimeMillis()
  var lastBytecodeProgressMs: Long = System.currentTimeMillis()
  var lastBytecodeProgressCount: Long = 0L
  var lastAccountProgressMs: Long = System.currentTimeMillis()
  var lastAccountTasksCompleted: Int = 0
  var lastAccountsDownloaded: Long = 0
  // Tracks storage contract completion (0.0–1.0) from push-based ProgressStorageContracts messages.
  // Used to suppress proactive pivot rolls when storage is nearly done (Bug 2 guard).
  var storageContractProgressPct: Double = 0.0

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
  val handshakedPeersAdapter: org.apache.pekko.actor.typed.ActorRef[
    com.chipprbots.ethereum.network.NetworkPeerManagerActor.HandshakedPeers
  ] =
    ctx
      .messageAdapter[com.chipprbots.ethereum.network.NetworkPeerManagerActor.HandshakedPeers](m =>
        WrappedHandshakedPeers(m.peers)
      )

  /** OQ-2: reply target for ChainDownloader's `Done`. ChainDownloader (Behavior[Command], S6 narrowed) sends Done to
    * this typed adapter, which bridges into SSC's sealed mailbox as ChainDownloaderDone. Progress is polled separately
    * via GetProgress — this adapter handles Done only.
    */
  val chainDownloaderReplyAdapter: TypedActorRef[ChainDownloader.Done.type] =
    ctx.messageAdapter[ChainDownloader.Done.type](_ => ChainDownloaderDone)

  def onStop(): Unit =
    stopSnapOnlySchedules()
    stopHeapWatchdog()
    // dormantWakeUp is now a timer — auto-cancelled on stop.
    ctx.log.info("SNAP Sync Controller stopped")

  /** Cancel the given timers in the given order (spec 016 P2, FR-011). Each caller passes its own list: the lists
    * differ on purpose and are kept exactly as they were. `timers.cancel` tolerates unknown keys.
    */
  def cancelSyncTimers(keys: Any*): Unit =
    keys.foreach(timers.cancel)

  /** Spec 016 P3 (FR-013): the peer-event arms shared by `idle`, `syncing`, `bootstrapping` and `dormantRetry`. Each of
    * those four behaviours carried an identical copy of these six arms. `bootstrapping` keeps its own
    * `WrappedHandshakedPeers` arm (bootstrap reactivity) and its own `GetProgress` arm (bootstrap progress) ahead of
    * this function. `syncing` consults it only after its path-publish and header-hold guards, exactly where the copies
    * stood. `completed` and `completedWithBackfill` do not use it.
    */
  lazy val peerEventArms: PartialFunction[Command, Behavior[Command]] = {
    case WrappedHandshakedPeers(peers) =>
      handleHandshakedPeersRateTracking(peers); Behaviors.same
    case WrappedPeerDisconnected(peerId) =>
      handlePeerDisconnectedDebounced(peerId); Behaviors.same
    case FlushPeerDisconnects =>
      flushPeerDisconnects(); Behaviors.same
    case PollHandshakedPeers =>
      pollHandshakedPeers(); Behaviors.same
    case hint: CLPivotHint =>
      handleCLPivotHint(hint)
      Behaviors.same
    case GetProgress(replyTo) =>
      replyTo ! progressMonitor.currentProgress
      Behaviors.same
  }

  // ── Behaviors ────────────────────────────────────────────────────────────────────────────────
  // Each behavior is `Behaviors.receiveMessage` over the sealed Command, with a PostStop signal for
  // cleanup. `context.become(X)` becomes `break(X())`; sender() becomes replyTo; the two peer-list
  // partials are inlined as explicit arms (C1); aroundReceive's stagnation dispatch is inlined into
  // `syncing` (C2).

  def idle(): Behavior[Command] =
    Behaviors
      .receiveMessage[Command](peerEventArms.orElse[Command, Behavior[Command]] {
        // Peer events, CLPivotHint and GetProgress: peerEventArms (P3).

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
        case StateHealingAbandoned    => ctx.log.debug("Dropping stale StateHealingAbandoned in idle"); Behaviors.same
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
        case _: StorageFileInfoReply =>
          ctx.log.warn("Unexpected StorageFileInfoReply in idle — dropped, storage file path not persisted")
          Behaviors.same
        case _: CodeHashesFileInfoReply =>
          ctx.log.warn("Unexpected CodeHashesFileInfoReply in idle — dropped, codeHashes file path not persisted")
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
        case IncrementalContractData(codeHashes, storageTasks, _) =>
          // Reserved by the producer in the intake gate; no coordinator will acknowledge it.
          intakeBudget.release(storageTasks.size, codeHashes.size)
          ctx.log.debug("Dropping stale IncrementalContractData in idle"); Behaviors.same
        case StateHealingComplete =>
          ctx.log.warn("Unexpected StateHealingComplete in idle — dropped")
          Behaviors.same
        case _: HealedCodeHashes =>
          ctx.log.debug("Dropping stale HealedCodeHashes in idle"); Behaviors.same
        case _: ByteCodesStored =>
          ctx.log.debug("Dropping stale ByteCodesStored in idle"); Behaviors.same
        case HealedCodeWaitTimeout =>
          ctx.log.debug("Dropping stale HealedCodeWaitTimeout in idle"); Behaviors.same
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
      })
      .receiveSignal { case (_, PostStop) =>
        onStop(); Behaviors.same
      }

  /** Spec 016 P4 (FR-014, plan.md D3): the arms `syncing` runs only in one `SyncPhase`, one partial function per phase.
    * `syncing` consults the current phase's function after its guard arms and before its common arms. P4a moved the
    * `AccountRangeSync` arms, P4b the `ByteCodeAndStorageSync` arms, P4c the `StateHealing` arms and P4d the
    * `StateValidation` arms; P4e added the (empty) `ChainDownloadCompletion` function. No `syncing` arm carries a
    * `currentPhase` guard any more; the arms that test the phase inside their body stay in `commonSyncingArms`
    * (research.md R4b note 2, decided per arm in P4e). The arm order is recorded in research.md R4b.
    */
  private def phaseArms(phase: SyncPhase): PartialFunction[Command, Behavior[Command]] = phase match
    case AccountRangeSync        => accountRangeArms
    case ByteCodeAndStorageSync  => byteCodeAndStorageArms
    case StateHealing            => stateHealingArms
    case StateValidation         => stateValidationArms
    case ChainDownloadCompletion => chainDownloadCompletionArms
    case _                       => PartialFunction.empty

  /** The `ChainDownloadCompletion` phase function (P4e). It is empty: no `syncing` arm was ever guarded on this phase,
    * and no code assigns it (research.md R4b note 3, CQ-SNAP-016-9), so in that phase every message goes on to
    * `commonSyncingArms`, as it did before P4.
    */
  private lazy val chainDownloadCompletionArms: PartialFunction[Command, Behavior[Command]] = PartialFunction.empty

  /** A `commonSyncingArms` arm lifted into a class member (spec 016 restructure, CQ-SNAP-016-18/19), body unchanged:
    * `RequestTrieNodeHealing` (#15), the 1-s healing tick: request healing and check the serve root, in `StateHealing`
    * only (the phase test stays in the body). `commonSyncingArms` delegates to it at the arm's original position, so
    * the arm order is unchanged.
    */
  private[snap] lazy val requestTrieNodeHealingArms: PartialFunction[Command, Behavior[Command]] = {
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
  }

  /** A `commonSyncingArms` arm lifted into a class member (spec 016 restructure, CQ-SNAP-016-18/19), body unchanged:
    * `HealingServeRoot` (#16), the parent's reply to `RequestHealingServeRoot`. `commonSyncingArms` delegates to it at
    * the arm's original position, so the arm order is unchanged.
    */
  private[snap] lazy val healingServeRootArms: PartialFunction[Command, Behavior[Command]] = {
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
  }

  /** A `commonSyncingArms` arm lifted into a class member (spec 016 restructure, CQ-SNAP-016-18/19), body unchanged:
    * `HealedCodeHashes`: bytecode of accounts that arrived only through healing. `commonSyncingArms` delegates to it at
    * the arm's original position, so the arm order is unchanged.
    */
  private[snap] lazy val healedCodeHashesArms: PartialFunction[Command, Behavior[Command]] = {
    case HealedCodeHashes(codeHashes) =>
      queueHealedCode(codeHashes)
      Behaviors.same
  }

  /** A `commonSyncingArms` arm lifted into a class member (spec 016 restructure, CQ-SNAP-016-18/19), body unchanged:
    * the healed-code finalisation hold: `ByteCodeSyncComplete` while `bytecodePhaseComplete && awaitingHealedCode`, and
    * `HealedCodeWaitTimeout` while `awaitingHealedCode`. A message whose guard is false falls through to the next arm
    * of `commonSyncingArms`, as before. `commonSyncingArms` delegates to it at the arm's original position, so the arm
    * order is unchanged.
    */
  private[snap] lazy val healedCodeHoldArms: PartialFunction[Command, Behavior[Command]] = {
    // The bytecode coordinator re-announces completion every time its queue drains, so this is the answer to the
    // tasks `queueHealedCode` added.
    case ByteCodeSyncComplete if bytecodePhaseComplete && awaitingHealedCode =>
      dropHealedCodeNowPresent()
      if healedCodeHashes.isEmpty then
        ctx.log.info("[HEAL-CODE] All bytecode of healed accounts is present — finalising SNAP")
        completeSnapSync()
      else ctx.log.info(s"[HEAL-CODE] ${healedCodeHashes.size} healed-account codeHash(es) still missing — waiting")
      Behaviors.same

    case HealedCodeWaitTimeout if awaitingHealedCode =>
      dropHealedCodeNowPresent()
      ctx.log.warn(
        s"[HEAL-CODE] Gave up waiting for the bytecode of healed accounts after $HealedCodeWaitMs ms: " +
          s"${healedCodeHashes.size} codeHash(es) still missing. Finalising SNAP without marking bytecode recovery " +
          "done; the next start's recovery scan fetches them, and block import fetches them on demand."
      )
      healedCodeWaitExhausted = true
      completeSnapSync()
      Behaviors.same
  }

  /** A `commonSyncingArms` arm lifted into a class member (spec 016 restructure, CQ-SNAP-016-18/19), body unchanged:
    * `StateHealingComplete`, the healing coordinator's completion signal. `commonSyncingArms` delegates to it at the
    * arm's original position, so the arm order is unchanged.
    */
  private[snap] lazy val stateHealingCompleteArms: PartialFunction[Command, Behavior[Command]] = {
    case StateHealingComplete =>
      progressMonitor.startPhase(StateHealing)
      ctx.log.info("Healing coordinator signaled complete (no pending tasks, no active requests).")
      if snapSyncConfig.storageScheme == StorageScheme.Path then
        // Path scheme: the controller's trie walk and StateValidator read the hash-keyed store, which Path never
        // populates, so they would report the root missing every round and never reach "0 missing". The coordinator
        // only sends StateHealingComplete once a path-aware walk of its current root in that incarnation found zero
        // missing nodes (verificationPassComplete: a clean verification walk, or the clean full rebuild walk), so
        // that signal IS the clean walk: take the same exit as TrieWalkComplete(0) rather than running a second,
        // blind, full-trie walk.
        if currentPhase == StateHealing then completeHealingWalkClean()
        else ctx.log.debug("Ignoring StateHealingComplete outside StateHealing (phase={})", currentPhase)
      else if trieWalkInProgress then
        // A trie walk is already running — its result will determine next step
        ctx.log.info("Trie walk in progress, waiting for result...")
      else
        // ARCH-WALK-HEAL-INTERLEAVE: Start walk with coordinator alive (if still running) or
        // create a fresh coordinator before the walk so inline discovery can run concurrently.
        startStateHealingWithInterleave()
      Behaviors.same
  }

  /** Dispatch order (spec 016 P4, research.md R4b): `syncingGuardArms` (the path-publish and header-hold arms with
    * their two guarded wildcards, then the `peerEventArms` delegation), then `phaseArms(currentPhase)`, then
    * `commonSyncingArms`, then the "Unhandled message in syncing state" catch-all. `currentPhase` is read per message.
    */
  def syncing(): Behavior[Command] =
    val syncingGuardArms: PartialFunction[Command, Behavior[Command]] = {
      // Path publish in flight: only its own messages and status/progress queries are served; stale SNAP responses,
      // tickers and peer churn are dropped (the publish is terminal — nothing here can change the anchored state).
      case PathPublishProgress(a, st) =>
        ctx.log.info(s"[PATH-PUBLISH] account=$a storage=$st")
        Behaviors.same
      case PathPublishDone(result, millis) =>
        onPathPublishDone(result, millis)
      case PathPublishFailed(cause) =>
        ctx.log.error("[PATH-PUBLISH] failed: escalating to SyncController for SNAP restart", cause)
        pathPublish = None
        syncController ! SyncProtocol.HealingImpossible
        Behaviors.same
      case msg if pathPublish.isDefined && !msg.isInstanceOf[GetStatus] && !msg.isInstanceOf[GetProgress] =>
        Behaviors.same
      // Deferred-backfill hold (see enterHeaderHold): only the tick, status/progress and the downloader's reports run.
      case HeaderHoldTick =>
        if headerHold.isDefined then
          pivotBlock.map(p => finalizeSnapSync(p, pathPublished = true)).getOrElse(Behaviors.same)
        else Behaviors.same
      case msg
          if headerHold.isDefined && !msg.isInstanceOf[GetStatus] && !msg.isInstanceOf[GetProgress] &&
            !msg.isInstanceOf[ChainDownloaderProgress] && msg != ChainDownloaderDone =>
        Behaviors.same
      // Peer events, CLPivotHint and GetProgress: peerEventArms (P3), after the two guards above, where the
      // inlined copies stood. CLPivotHint: CL advanced its head while we're mid-snap. Update the buffer; the proactive
      // pivot-rolling watcher (geth-style: re-pivot when `head > pivot + 2*offset - 8`)
      // consumes this on its next tick. We deliberately don't restart the pipeline here
      // — content-addressed trie nodes are ~99.9% valid across pivot changes.
      case msg if peerEventArms.isDefinedAt(msg) =>
        peerEventArms(msg)
    }

    // The arms that run in every phase (or test the phase inside their body), in their original order.
    val commonSyncingArms: PartialFunction[Command, Behavior[Command]] = {

      // Periodic rate tracker tuning (geth msgrate alignment)
      case TuneRateTracker =>
        requestTracker.rateTracker.tune()
        Behaviors.same

      // Periodic SNAP peer eviction: disconnect non-SNAP outgoing peers to free slots
      case EvictNonSnapPeers =>
        evictNonSnapPeers()
        Behaviors.same

      // delayedRestartArms (spec 016 lift before M10): delegated here, at its original position.
      case msg if delayedRestartArms.isDefinedAt(msg) =>
        delayedRestartArms(msg)

      // checkSnapCapabilityArms (spec 016 lift before M11b): delegated here, at its original position.
      case msg if checkSnapCapabilityArms.isDefinedAt(msg) =>
        checkSnapCapabilityArms(msg)

      // downloadTickArms (spec 016 lift before M11b): delegated here, at its original position.
      case msg if downloadTickArms.isDefinedAt(msg) =>
        downloadTickArms(msg)

      // requestTrieNodeHealingArms (spec 016 restructure): delegated here, at its original position.
      case msg if requestTrieNodeHealingArms.isDefinedAt(msg) =>
        requestTrieNodeHealingArms(msg)

      // healingServeRootArms (spec 016 restructure): delegated here, at its original position.
      case msg if healingServeRootArms.isDefinedAt(msg) =>
        healingServeRootArms(msg)

      case EnsureSnapServerPeersConnected =>
        ensureSnapServerPeersConnected()
        Behaviors.same

      // snapResponseArms (spec 016 lift before M11b): delegated here, at its original position.
      case msg if snapResponseArms.isDefinedAt(msg) =>
        snapResponseArms(msg)

      // progressAccountsSyncedArms (spec 016 lift before M11b): delegated here, at its original position.
      case msg if progressAccountsSyncedArms.isDefinedAt(msg) =>
        progressAccountsSyncedArms(msg)

      // accountProgressCheckpointArms (spec 016 T053c): delegated here, at its original position.
      case msg if accountProgressCheckpointArms.isDefinedAt(msg) =>
        accountProgressCheckpointArms(msg)

      // accountTrieFinalizationArms (spec 016 lift before M11b): delegated here, at its original position.
      case msg if accountTrieFinalizationArms.isDefinedAt(msg) =>
        accountTrieFinalizationArms(msg)

      // downloadProgressArms (spec 016 lift before M11b): delegated here, at its original position.
      case msg if downloadProgressArms.isDefinedAt(msg) =>
        downloadProgressArms(msg)

      // downloadBackpressureArms (spec 016 lift before M11b): delegated here, at its original position.
      case msg if downloadBackpressureArms.isDefinedAt(msg) =>
        downloadBackpressureArms(msg)

      // pivotStateUnservableArms (spec 016 lift before M10): delegated here, at its original position.
      case msg if pivotStateUnservableArms.isDefinedAt(msg) =>
        pivotStateUnservableArms(msg)

      // pivotRefreshBootstrapCompleteArms (spec 016 lift before M10): delegated here, at its original position.
      case msg if pivotRefreshBootstrapCompleteArms.isDefinedAt(msg) =>
        pivotRefreshBootstrapCompleteArms(msg)

      // pivotRefreshBootstrapFailedArms (spec 016 lift before M10): delegated here, at its original position.
      case msg if pivotRefreshBootstrapFailedArms.isDefinedAt(msg) =>
        pivotRefreshBootstrapFailedArms(msg)

      // pivotProbeTimeoutArms (spec 016 lift before M10): delegated here, at its original position.
      case msg if pivotProbeTimeoutArms.isDefinedAt(msg) =>
        pivotProbeTimeoutArms(msg)

      // retryPivotRefreshArms (spec 016 lift before M10): delegated here, at its original position.
      case msg if retryPivotRefreshArms.isDefinedAt(msg) =>
        retryPivotRefreshArms(msg)

      // retryBootstrapAtBlockArms (spec 016 restructure): delegated here, at its original position.
      case msg if retryBootstrapAtBlockArms.isDefinedAt(msg) =>
        retryBootstrapAtBlockArms(msg)

      // incrementalContractDataArms (spec 016 lift before M11b): delegated here, at its original position.
      case msg if incrementalContractDataArms.isDefinedAt(msg) =>
        incrementalContractDataArms(msg)

      // accountRangeSyncCompleteArms (spec 016 lift before M11b): delegated here, at its original position.
      case msg if accountRangeSyncCompleteArms.isDefinedAt(msg) =>
        accountRangeSyncCompleteArms(msg)

      // healedCodeHashesArms (spec 016 restructure): delegated here, at its original position.
      case msg if healedCodeHashesArms.isDefinedAt(msg) =>
        healedCodeHashesArms(msg)

      // healedCodeHoldArms (spec 016 restructure): delegated here, at its original position.
      case msg if healedCodeHoldArms.isDefinedAt(msg) =>
        healedCodeHoldArms(msg)

      // downloadPhaseCompleteArms (spec 016 lift before M11b): delegated here, at its original position.
      case msg if downloadPhaseCompleteArms.isDefinedAt(msg) =>
        downloadPhaseCompleteArms(msg)

      // HealingAllPeersStateless, HealingStagnated (StateHealing only): stateHealingArms (P4c).

      // stateHealingCompleteArms (spec 016 restructure): delegated here, at its original position.
      case msg if stateHealingCompleteArms.isDefinedAt(msg) =>
        stateHealingCompleteArms(msg)

      // StateHealingAbandoned, HealingRootUnservable, TrieWalkBatch/Complete/Result, ScheduledTrieWalk, TrieWalkFailed
      // (StateHealing only): stateHealingArms (P4c).

      case StateValidationComplete =>
        ctx.log.info("State validation complete. SNAP sync finished!")
        completeSnapSync()
        Behaviors.same

      // Stale-generation drops (#60-#62): staleValidationDropArms (P4d), consulted here for every phase and first in
      // stateValidationArms for StateValidation.
      case msg if staleValidationDropArms.isDefinedAt(msg) =>
        staleValidationDropArms(msg)

      // ValidateAccountTrieResult, ValidateStorageTriesResult, ValidationRetry (StateValidation only, current
      // generation): stateValidationArms (P4d).

      // checkDownloadStagnationArms (spec 016 restructure): delegated here, at its original position.
      case msg if checkDownloadStagnationArms.isDefinedAt(msg) =>
        checkDownloadStagnationArms(msg)

      // AccountCoordinatorProgress (AccountRangeSync only): accountRangeArms (P4a).
      // Storage/ByteCodeCoordinatorProgress (ByteCodeAndStorageSync only): byteCodeAndStorageArms (P4b).

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
    }

    val unhandledInSyncing: Command => Behavior[Command] = msg =>
      ctx.log.debug(s"Unhandled message in syncing state: $msg")
      Behaviors.same

    Behaviors
      .receiveMessage[Command] { message =>
        syncingGuardArms
          .orElse(phaseArms(currentPhase))
          .orElse(commonSyncingArms)
          .applyOrElse(message, unhandledInSyncing)
      }
      .receiveSignal { case (_, PostStop) =>
        onStop(); Behaviors.same
      }

  def startSnapSync(): Behavior[Command] =
    import scala.util.boundary
    boundary[Behavior[Command]] {
      // Start evicting non-SNAP peers immediately to make room for SNAP-capable peers.
      // This runs during pivot selection and bootstrap, not just after account sync starts.
      startSnapPeerEviction()

      // Each step either breaks out of this boundary with the next behaviour or falls through to the next step.
      recoverAccountsComplete()
      resumeInterruptedBootstrap()
      selectPivotAndStart()
      bootstrapping()
    } // end boundary

object SNAPSyncController:

  /** Storage per-peer in-flight budget during account sync: the configured value, clamped to `[1, maxInFlightPerPeer]`
    * so a misconfiguration can neither stall storage (0) nor exceed the global per-peer budget.
    */
  private[snap] def storageInFlightDuringAccounts(cfg: SNAPSyncConfig): Int =
    cfg.storageMaxInFlightPerPeerDuringAccounts.max(1).min(cfg.maxInFlightPerPeer.max(1))

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
  private[snap] case object HeaderHoldTick extends Command

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
  /** Account-range cursors (`last -> next`) plus the contract task files consistent with them. `durable` snapshots were
    * taken after the coordinator flushed its trie batches and fsynced the task files, and are the only ones persisted.
    * `generation` identifies the coordinator instance; snapshots from a superseded instance are dropped.
    */
  final private[snap] case class AccountRangeProgressCmd(
      progress: Map[ByteString, ByteString],
      taskFiles: Option[ContractTaskFiles] = None,
      durable: Boolean = false,
      generation: Long = 0L
  ) extends Command
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

  /** The account coordinator's two task-file infos (#1603). It sends them right after `AccountRangeSyncComplete`, from
    * the same handler and before it can stop, having flushed each file. The controller persists the handoff paths and
    * counts on the actor thread. A `Failure` (the coordinator could not flush the file) is not persisted and the
    * controller logs a WARN naming the path.
    */
  final private[snap] case class StorageFileInfoReply(
      result: scala.util.Try[actors.AccountRangeCoordinator.StorageFileInfoResponse]
  ) extends Command
  final private[snap] case class CodeHashesFileInfoReply(
      result: scala.util.Try[actors.AccountRangeCoordinator.CodeHashesFileInfoResponse]
  ) extends Command
  case object ByteCodeSyncComplete extends Command
  case object StorageRangeSyncComplete extends Command
  case object StorageRangeSyncForceCompleted extends Command

  /** Inline contract data dispatched from AccountRangeCoordinator after each account batch. Geth-aligned: bytecodes and
    * storage tasks are populated inline from processAccountResponse(), not queried after account download completes.
    */
  final case class IncrementalContractData(
      codeHashes: Seq[ByteString],
      storageTasks: Seq[StorageTask],
      // True when replayed from a carried task-file prefix (resume from mid-range cursors): codeHashes already in
      // local code storage are dropped instead of being downloaded again.
      replayed: Boolean = false
  ) extends Command
  case object StateHealingComplete extends Command

  /** Coordinator gave up (HealingForceComplete) WITHOUT a verification walk. Never a clean-walk signal: under Path the
    * controller routes it to the lazy-heal handoff instead of declaring the trie validated.
    */
  case object StateHealingAbandoned extends Command

  /** TrieNodeHealingCoordinator -> controller: healed account leaves whose bytecode is not in `EvmCodeStorage`. */
  final case class HealedCodeHashes(codeHashes: Seq[ByteString]) extends Command

  /** spec 022 D4: the bytecode coordinator's write of `codeHashes` has returned. Forwarded to the healing coordinator,
    * where an account leaf waits on its code.
    */
  final case class ByteCodesStored(codeHashes: Seq[ByteString]) extends Command

  /** The bounded wait for that bytecode ran out. */
  private[snap] case object HealedCodeWaitTimeout extends Command

  /** How long finalisation waits for the bytecode of healed accounts before giving up and leaving it to recovery. */
  val HealedCodeWaitMs: Long = 5 * 60 * 1000L
  private[snap] val HealedCodeWaitTimerKey: String = "healed-code-wait"
  case object HealingAllPeersStateless extends Command
  final case class HealingRootUnservable(root: ByteString) extends Command
  case object StateValidationComplete extends Command

  // ── Group 3: Status / progress queries (C3 — explicit replyTo, OQ-5) ───────
  // The 11 sender() call sites collapse into these two query Commands. The reply
  // types (SyncProtocol.Status, SyncProgress) are unchanged; only the request gains
  // a replyTo. Callers update in Phase 3 (Classic callers use the AskPattern adapter).
  final case class GetStatus(replyTo: org.apache.pekko.actor.typed.ActorRef[SyncProtocol.Status]) extends Command
  final case class GetProgress(replyTo: org.apache.pekko.actor.typed.ActorRef[SyncProgress]) extends Command

  // Path scheme: asynchronous publish of the path-keyed trie to hash-keyed storage (see PathToHashExporter).
  final case class PathPublishProgress(accountNodes: Long, storageNodes: Long) extends Command
  final case class PathPublishDone(result: PathToHashExporter.Result, millis: Long) extends Command
  final case class PathPublishFailed(cause: Throwable) extends Command

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
  final case class PivotStateUnservable(
      rootHash: ByteString,
      reason: String,
      consecutiveEmptyResponses: Int,
      cause: UnservableCause = UnservableCause.Stateless
  ) extends Command

  /** Why a coordinator asked for a pivot refresh. Only evidence that the ROOT is unservable may count toward the
    * restart / dormant threshold (`MaxConsecutivePivotRefreshes`).
    *
    *   - `Stateless` — peers answered empty-without-proof for the root (confirmed stateless / strikes). Counts.
    *   - `Snapless` — every peer has no snapshot tree at all. Counts (a fresher root will not help these peers, and the
    *     controller has to be able to give up on the pool).
    *   - `PeerScarcity` — nothing could be dispatched because peers are few, cooling down after timeouts, or absent.
    *     The root itself is not known to be bad. A refresh keeps the root inside the serve window, but a restart cannot
    *     create peers, so this never counts toward the restart threshold. (Sepolia 2026-10-06: ten scarcity refreshes
    *     reached the threshold with `0 stateless` peers and the restart threw away ~9 h of account download.)
    *
    * Downstream back-pressure is not a cause at all: a deliberately paused dispatcher is not stalled, so the
    * coordinator never escalates for it.
    */
  enum UnservableCause:
    case Stateless, Snapless, PeerScarcity

  /** Whether a [[PivotStateUnservable]] of this cause counts toward the consecutive-refresh restart threshold. */
  private[snap] def countsTowardRestart(cause: UnservableCause): Boolean =
    cause != UnservableCause.PeerScarcity

  /** File-name prefixes of the contract task files a coordinator creates (see AccountRangeCoordinator). The
    * contract-accounts file is not listed: its owner deletes it on stop and the live one is in use.
    */
  private[snap] val SweepableTaskFilePrefixes: Seq[String] =
    Seq("fukuii-contract-storage-", "fukuii-unique-codehashes-")

  /** Delete every contract task file directly in `dir` whose normalised absolute path is not in `keep`. Returns the
    * deleted paths; failures are reported to `onError` and skipped (a leftover file is harmless, a crash here is not).
    */
  private[snap] def sweepTaskFiles(
      dir: java.nio.file.Path,
      keep: Set[String],
      onError: (java.nio.file.Path, String) => Unit = (_, _) => ()
  ): Seq[java.nio.file.Path] =
    import scala.jdk.CollectionConverters.*
    def norm(p: java.nio.file.Path): String = p.toAbsolutePath.normalize.toString
    val keepNorm = keep.map(s => norm(java.nio.file.Path.of(s)))
    if !java.nio.file.Files.isDirectory(dir) then Seq.empty
    else
      val stream = java.nio.file.Files.list(dir)
      try
        stream.iterator.asScala.toList
          .filter { p =>
            val name = p.getFileName.toString
            java.nio.file.Files.isRegularFile(p) && SweepableTaskFilePrefixes.exists(name.startsWith) &&
            !keepNorm.contains(norm(p))
          }
          .filter { p =>
            try java.nio.file.Files.deleteIfExists(p)
            catch
              case e: java.io.IOException =>
                onError(p, e.getMessage)
                false
          }
      finally stream.close()

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
  /** How long a gated recovery stream waits before re-checking a closed intake gate (spec 014). */
  private[snap] val RecoveryReplayPausedRetry: FiniteDuration = 1.second

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

  // spec 016 M1: the pure policy helpers live in controller/*Policy.scala. They are re-exported here, so every
  // `SNAPSyncController.<helper>` call site, and the impl class's `import SNAPSyncController.*`, stay unchanged.
  export controller.StagnationPolicy.{evaluateStorageTail, MinStaleFailuresForTailLivelock, StorageTailBaseline}
  export controller.PivotPolicy.{clPivotNotYetAdvanced, MaxPeerTipLead}
  export controller.PivotPolicy.{healPivotAfterExcluded, pivotPassesFreshnessFloor, unservablePivotTarget}
  export controller.HealPolicy.{bytecodeRecoveryComplete, healRepegSuppressedByLocalWalk}
  export controller.HealPolicy.{lastHealingServeRootBlockToRecord, shouldSkipHealingAfterDownloads, staleReferenceHead}
  export controller.ResumePolicy.{checkResumable, fillResumeLayout, taskFilePaths}
  export controller.SnapPeerPolicy.{servesSnapState, snapExclusionReason, SnapExclusionAtGenesis}
  export controller.SnapPeerPolicy.{SnapExclusionLogIntervalMs, SnapExclusionMinLogIntervalMs}
  export controller.FinalizationPolicy.{chainBackfillDeferredToFinalization, EmptyHeaderBackoff}
  export controller.FinalizationPolicy.{HeaderHoldTickInterval, HeaderHoldTimerKey, HeaderHoldWarnIntervalMs}

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
      validatorFactory: MptStorage => StateValidator = new StateValidator(_),
      isPoSChainOverride: Option[Boolean] = None,
      // Test seams (spec 016 T012); the defaults are today's construction.
      childFactories: ChildFactories = ChildFactories.production,
      heapWatchdogStart: HeapWatchdogStart = HeapWatchdogStart.production,
      intakeBudgetOverride: Option[SnapIntakeBudget] = None,
      failedFinalizationPivot: Option[BigInt] = None
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
          validatorFactory,
          isPoSChainOverride,
          childFactories,
          heapWatchdogStart,
          intakeBudgetOverride,
          failedFinalizationPivot
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
    // Cap on the post-SNAP frontier-rebuild BFS `visited` LRU (entries). Bounds heap during the
    // full-state walk (≈ cap × 80 B; 4,000,000 ≈ 320 MB) so the walk completes instead of OOM-looping.
    // Completeness is independent of this value. See docs/design/healing-frontier-scale.md.
    healingVisitedCap: Int = actors.TrieNodeHealingCoordinator.DefaultVisitedCap,
    // spec 022 D1: the held-memory bound in bytes (held nodes, flush buffer, live requests); at it no new subtree is
    // opened. Key: `sync.snap-sync.healing.held-memory-limit` (a HOCON size, e.g. `128 MiB`). Not in base/sync.conf
    // yet: the SNAP-split verifier forbids src/main/resources changes on fix branches, so the default comes from here.
    healingHeldMemoryLimit: Long = actors.TrieNodeHealingCoordinator.DefaultHeldMemoryLimit,
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
    /** Storage per-peer in-flight budget while account ranges are still downloading (the account coordinator holds the
      * rest of the per-peer budget). Clamped to `[1, maxInFlightPerPeer]` by [[storageInFlightDuringAccounts]]. Key:
      * `sync.snap-sync.storage-max-inflight-per-peer-during-accounts`.
      */
    storageMaxInFlightPerPeerDuringAccounts: Int = 3,
    accountInitialResponseBytes: Int = 524288,
    accountMinResponseBytes: Int = 102400,
    chainDownloadEnabled: Boolean = true,
    chainDownloadMaxConcurrentRequests: Int = 2,
    chainDownloadBoostedConcurrentRequests: Int = 16,
    // Concurrency budget for chain backfill once SNAP state is finalised and regular sync has started.
    // Smaller than `chainDownloadMaxConcurrentRequests` so backfill yields peer slots to regular sync.
    chainBackfillConcurrentRequests: Int = 2,
    // Deferred-backfill hold: if the header cursor has not advanced for this long, the chain downloader is restarted.
    headerHoldStallTimeout: FiniteDuration = 5.minutes,
    // When true, the chain downloader fetches headers only while SNAP state sync runs: bodies and receipts are held
    // until the state is finalised, so historical-body writes never compete with account/storage/bytecode/healing
    // writes for disk IO. Off by default; the post-merge ETH chain configs turn it on. The pivot header and the
    // CL-anchored header chain are fetched by PivotHeaderBootstrap, not by the chain downloader, so they are unaffected.
    deferChainBackfillUntilStateComplete: Boolean = false,
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
    storageScheme: StorageScheme = StorageScheme.Hash,
    /** Directory for the persisted storage-task file (`<datadir>/snap`). `None` falls back to java.io.tmpdir, which a
      * reboot wipes, so production wiring (SyncController) always sets it. See [[StorageTaskFile]].
      */
    taskFileDir: Option[java.nio.file.Path] = None,
    /** Memory bounds (spec 014). Ceiling on storage tasks held for StorageRangeCoordinator (in transit + queued) before
      * the producers (account-range dispatch, carried-task replay, accounts-complete recovery stream) wait. ~400 B per
      * task, so 200,000 is about 80 MB whatever the chain size. Key: `sync.snap-sync.max-pending-storage-tasks`.
      */
    maxPendingStorageTasks: Long = 200000L,
    /** Same ceiling for codeHashes held for ByteCodeCoordinator, counted in hashes (not 85-hash tasks). Key:
      * `sync.snap-sync.max-pending-bytecode-hashes`.
      */
    maxPendingByteCodeHashes: Long = 200000L,
    /** Heap watchdog (spec 014): pause SNAP intake while old-gen occupancy after a collection is at or above
      * `heapWatchdogPauseFraction` of the max heap; resume at or below `heapWatchdogResumeFraction`. Keys under
      * `sync.snap-sync.heap-watchdog-*`. Off in this case-class default so unit tests that build a config directly do
      * not install a JVM-wide JMX listener; `base/sync.conf` turns it on for every real node.
      */
    heapWatchdogEnabled: Boolean = false,
    heapWatchdogPauseFraction: Double = 0.75,
    heapWatchdogResumeFraction: Double = 0.60,
    heapWatchdogPollInterval: FiniteDuration = 5.seconds,
    /** A heap pause whose SNAP queues have stayed empty this long is not helping: WARN once. Key:
      * `sync.snap-sync.heap-watchdog-ineffective-after`.
      */
    heapWatchdogIneffectiveAfter: FiniteDuration = 60.seconds,
    /** Force-release such an ineffective pause once it has lasted this long, so a live set held above the resume
      * threshold by non-SNAP memory cannot stall SNAP forever. 0 = never. Key:
      * `sync.snap-sync.heap-watchdog-max-pause`.
      */
    heapWatchdogMaxPause: FiniteDuration = 5.minutes
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
      healingHeldMemoryLimit =
        if snapConfig.hasPath("healing.held-memory-limit") then snapConfig.getBytes("healing.held-memory-limit")
        else actors.TrieNodeHealingCoordinator.DefaultHeldMemoryLimit,
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
      storageMaxInFlightPerPeerDuringAccounts =
        if snapConfig.hasPath("storage-max-inflight-per-peer-during-accounts") then
          snapConfig.getInt("storage-max-inflight-per-peer-during-accounts")
        else 3,
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
      headerHoldStallTimeout =
        if snapConfig.hasPath("header-hold-stall-timeout") then
          snapConfig.getDuration("header-hold-stall-timeout").toMillis.millis
        else 5.minutes,
      deferChainBackfillUntilStateComplete =
        if snapConfig.hasPath("defer-chain-backfill-until-state-complete") then
          snapConfig.getBoolean("defer-chain-backfill-until-state-complete")
        else false,
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
        else StorageScheme.Hash,
      maxPendingStorageTasks =
        if snapConfig.hasPath("max-pending-storage-tasks") then snapConfig.getLong("max-pending-storage-tasks")
        else 200000L,
      maxPendingByteCodeHashes =
        if snapConfig.hasPath("max-pending-bytecode-hashes") then snapConfig.getLong("max-pending-bytecode-hashes")
        else 200000L,
      heapWatchdogEnabled =
        if snapConfig.hasPath("heap-watchdog-enabled") then snapConfig.getBoolean("heap-watchdog-enabled")
        else true,
      heapWatchdogPauseFraction =
        if snapConfig.hasPath("heap-watchdog-pause-fraction") then snapConfig.getDouble("heap-watchdog-pause-fraction")
        else 0.75,
      heapWatchdogResumeFraction =
        if snapConfig.hasPath("heap-watchdog-resume-fraction") then
          snapConfig.getDouble("heap-watchdog-resume-fraction")
        else 0.60,
      heapWatchdogPollInterval =
        if snapConfig.hasPath("heap-watchdog-poll-interval") then
          snapConfig.getDuration("heap-watchdog-poll-interval").toMillis.millis
        else 5.seconds,
      heapWatchdogIneffectiveAfter =
        if snapConfig.hasPath("heap-watchdog-ineffective-after") then
          snapConfig.getDuration("heap-watchdog-ineffective-after").toMillis.millis
        else 60.seconds,
      heapWatchdogMaxPause =
        if snapConfig.hasPath("heap-watchdog-max-pause") then
          snapConfig.getDuration("heap-watchdog-max-pause").toMillis.millis
        else 5.minutes
    )

// StateValidator has been extracted to StateValidator.scala
// SyncProgressMonitor and SyncProgress have been extracted to SyncProgressMonitor.scala
