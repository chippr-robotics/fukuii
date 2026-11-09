package com.chipprbots.ethereum.blockchain.sync

import org.apache.pekko.actor.Scheduler
import org.apache.pekko.actor.typed.ActorRef as TypedActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.DispatcherSelector
import org.apache.pekko.actor.typed.PostStop
import org.apache.pekko.actor.typed.SupervisorStrategy
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.TimerScheduler
import org.apache.pekko.util.ByteString

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import scala.concurrent.duration.*
import scala.util.control.NonFatal

import com.chipprbots.ethereum.blockchain.sync.regular.ImportLock
import com.chipprbots.ethereum.blockchain.sync.regular.RegularSync
import com.chipprbots.ethereum.blockchain.sync.regular.RegularSyncMetrics
import com.chipprbots.ethereum.blockchain.sync.snap.ChainDownloader
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncConfig
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.BootstrapComplete
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.PivotBootstrapFailed
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.StartRegularSyncBootstrap
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.StartRegularSyncBootstrapByHash
import com.chipprbots.ethereum.blockchain.sync.snap.StateValidator
import com.chipprbots.ethereum.blockchain.sync.snap.StorageScheme
import com.chipprbots.ethereum.blockchain.sync.snap.StorageTaskFile
import com.chipprbots.ethereum.blockchain.sync.snap.controller.StateIngest
import com.chipprbots.ethereum.blockchain.sync.snap.heal.HealInvariant
import com.chipprbots.ethereum.consensus.ConsensusAdapter
import com.chipprbots.ethereum.consensus.ReorgStateHandler
import com.chipprbots.ethereum.consensus.engine.DesignatedHead
import com.chipprbots.ethereum.consensus.engine.ForkChoiceManager
import com.chipprbots.ethereum.consensus.mess.MESSConfig
import com.chipprbots.ethereum.consensus.validators.Validators
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.BfsQueueStorage
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.FastSyncStateStorage
import com.chipprbots.ethereum.db.storage.FlatSlotStorage
import com.chipprbots.ethereum.db.storage.HealingFrontierStorage
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.db.storage.PathNodeStorage
import com.chipprbots.ethereum.db.storage.SnapStorageDoneStorage
import com.chipprbots.ethereum.db.storage.SnapSyncProgressStorage
import com.chipprbots.ethereum.db.storage.StateStorage
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.Blockchain
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.BlockchainWriter
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.ledger.BranchResolution
import com.chipprbots.ethereum.ledger.ChainInsertLock
import com.chipprbots.ethereum.nodebuilder.BlockchainConfigBuilder
import com.chipprbots.ethereum.utils.Config
import com.chipprbots.ethereum.utils.Config.SyncConfig
import com.chipprbots.ethereum.utils.NetworkType

/** Top-level sync orchestrator.
  *
  * Pekko Typed migration (Group ROOT, Phase 2 + OQ-5 complete): converted to a Typed actor with behavior type
  * `Behavior[Command]`. Heterogeneous external case classes from still-Classic senders arrive via two wrappers:
  * `WrappedExternal` (child fire-and-forget replies via a `messageAdapter`) and `WrappedSyncProtocol` (JSON-RPC asks
  * now using Typed ask with embedded `replyTo` in each `SyncProtocol.*` message). Callers include
  * `NetworkPeerManagerActor` (`HandshakedPeers`, `CalibrateChainWeightFromPeer`), `ForkChoiceManager` (`BeaconHead` via
  * `fcmAdapter` — a typed `TypedActorRef[ForkChoiceManager.BeaconHead]`), the JSON-RPC layer
  * (`SyncProtocol.GetStatus`), and its children (`SNAPSyncController`, the recovery actors, `ChainDownloader`,
  * `RegularSync` / `PeersClient` / `PivotHeaderBootstrap`).
  *
  * Each former `context.become(stateX)` becomes a named `Behavior[Command]` factory method on `Impl`. Stored-sender
  * slots (`healingServeRootRequester`, `recentRootRequester`) carry explicit `ActorRef[ReplyType]` fields. Timers
  * (`PollRecoveryPeers`, recent-root / healing-serve-root timeouts, TD calibration) use a `TimerScheduler`.
  * `PivotHeaderBootstrap` (now Typed) is spawned via `ctx.spawn`; the remaining Classic children are spawned via the
  * Classic context's `actorOf`. `syncController` is now a Typed `ActorRef[Command]` in `NodeBuilder` and all JSON-RPC
  * callers; the OQ-5 Classic ask path (sender retrieval via the Classic context) has been eliminated.
  */
object SyncController:

  /** Bulk bytecode recoveries (triggered by block import) allowed to finish with the triggering code still missing
    * before the node stops repeating the full trie scan on every restart.
    */
  val MaxBulkBytecodeRecoveryFailures: Int = 3

  /** H3: SNAP re-syncs regular sync asks for in a row (no block imported in between) that start at once. From the next
    * one on SNAP waits first ([[resnapBackoff]]).
    */
  val ResnapsBeforeBackoff: Int = 2

  /** H3: the first re-SNAP that waits waits this long; each one after it twice as long, up to [[ResnapBackoffCap]]. */
  val ResnapBackoffBase: FiniteDuration = 1.hour

  val ResnapBackoffCap: FiniteDuration = 24.hours

  /** H3: how often, while a re-SNAP count stands, regular sync's progress is looked at for a block imported. */
  val ResnapCheckInterval: FiniteDuration = 1.minute

  private val ResnapDueKey = "ResnapDue"
  private val ResnapCheckKey = "ResnapCheck"

  /** How long the `resnaps`-th SNAP re-sync in a row, with no block imported in between, waits before SNAP starts:
    * nothing for the first [[ResnapsBeforeBackoff]], then [[ResnapBackoffBase]], doubling each time, up to
    * [[ResnapBackoffCap]].
    */
  def resnapBackoff(resnaps: Int): FiniteDuration =
    if resnaps <= ResnapsBeforeBackoff then Duration.Zero
    else
      val doublings = (resnaps - ResnapsBeforeBackoff - 1).min(16)
      (ResnapBackoffBase * (1L << doublings)).min(ResnapBackoffCap)

  /** How long a regular-sync start tries the chain insert lock for the operator's chain-weight seeds (#1654). Block
    * commits hold it shared for a block, and the chain-weight pass's final step exclusively for a few blocks.
    */
  val SeedLockWait: FiniteDuration = 2.seconds

  /** Whether a missing contract code at import may escalate to a bulk bytecode recovery.
    *
    *   - Only a node whose state came from SNAP: on a node that synced by executing blocks (ETC, Mordor), missing code
    *     means a corrupt database, and a scan of the whole state trie would pause a PoW node for nothing the scan can
    *     repair. It keeps fetching that hash alone.
    *   - Not once the failure cap is reached. NOTE the cap is permanent: three bulk recoveries that failed for a
    *     TRANSIENT reason (no snap peers, say) switch the startup scan off for good, and only single-hash fetching
    *     remains. Clear `BulkBytecodeRecoveryFailures` in the database to re-enable it.
    */
  def bulkCodeRecoveryAllowed(snapSyncDone: Boolean, failures: Int): Boolean =
    snapSyncDone && failures < MaxBulkBytecodeRecoveryFailures

  /** Sealed protocol for the top-level sync orchestrator (ROOT-a narrowing, Phase 1; OQ-5 complete).
    *
    * This ADT covers the messages SyncController OWNS: self/timer ticks, death-watch termination markers, and the
    * sync-protocol queries now routed via `WrappedSyncProtocol`. Heterogeneous external case classes still arrive from
    * many Classic senders (SNAPSyncController, PivotHeaderBootstrap, RegularSync.ProgressProtocol,
    * ForkChoiceManager.BeaconHead, NetworkPeerManagerActor, the recovery actors, CombinedRecoveryScanActor) — those are
    * NOT yet members of this Command ADT; they arrive via `WrappedExternal`. `SyncProtocol.*` messages carry typed
    * `replyTo` fields and arrive via `WrappedSyncProtocol`; Classic sender() is no longer used.
    */
  sealed trait Command

  // `GetProgress` is an internal/unused remnant kept for symmetry; the canonical status query is SyncProtocol.GetStatus
  // wrapped in WrappedSyncProtocol with a typed replyTo field.
  case object GetProgress extends Command

  private case object PollRecoveryPeers extends Command
  // Self-ping: the recovery recent-root header bootstrap for this generation took too long → decline the roll.
  private case class RecentRootTimeout(generation: Int) extends Command
  // spec 004 (Decoupled Heal Serve-Root) T012: self-ping for the HEALING serve-root header bootstrap. Distinct
  // from RecentRootTimeout so the healing serve-root request never contends with storage recovery's requester.
  private case class HealingServeRootTimeout(generation: Int) extends Command

  // Death-watch markers (replace Classic `context.watch` + `Terminated(ref)`). Each watched child gets a distinct
  // marker carrying its Classic ref so the handler can match the specific child that died.
  private case class SnapSyncTerminated(ref: TypedActorRef[SNAPSyncController.Command]) extends Command
  // §7c-D4: STOP-AND-ALERT death-watch for SNAPSyncController while it is actively syncing (runningSnapSync /
  // runningPivotHeaderBootstrap). A SNAP crash here corrupts in-flight session state; restart is unsafe. Registered at
  // spawn; replaced by the benign SnapSyncTerminated watch at the backfill transition, and unwatched before every
  // intentional ctx.stop(snapSync). Distinct marker so an unexpected death loudly alerts instead of being swallowed by
  // isInternalMarker (which silently drops SnapSyncTerminated).
  private case object SnapSyncCriticalFailure extends Command
  private case class RegularSyncTerminated(ref: TypedActorRef[RegularSync.Command]) extends Command
  // H3: a re-SNAP's wait is over (regular sync ran meanwhile); and the check, while the count stands, for a block
  // imported since, which drops the count and any re-SNAP waiting.
  private case class ResnapDue(blockNumber: BigInt) extends Command
  private case object ResnapCheck extends Command
  private case class ResumerTerminated(ref: TypedActorRef[ChainDownloader.Command]) extends Command
  private case class BytecodeRecoveryTerminated(ref: TypedActorRef[BytecodeRecoveryActor.Command]) extends Command
  private case class StorageRecoveryTerminated(ref: TypedActorRef[StorageRecoveryActor.Command]) extends Command

  // ── Phase 2 (ROOT-b): wrappers for heterogeneous external messages ──────────────────────────────────────
  //
  // SyncController is the central sync hub: it receives raw case classes from many senders that do NOT share a common
  // sealed trait (`SNAPSyncController.SnapSyncFinalized`, `PivotHeaderBootstrap.Completed`,
  // `RegularSync.ProgressProtocol.ImportedBlock`, `ForkChoiceManager.BeaconHead`, `NetworkPeerManagerActor.*`, the
  // recovery actors, `ChainDownloader.*`, plus `SyncProtocol.*` from JSON-RPC asks). Introducing per-source umbrella
  // traits would require editing those (mostly still-Classic or mid-S3-migration) child files — out of ROOT-b scope.
  //
  // Phase 2 therefore uses TWO wrappers, mirroring the (since removed) FastSync actor (commit e41f50b7d) but with one
  // umbrella for the sender-independent traffic:
  //
  //   1. `WrappedExternal(msg: Any)` — every CHILD-ORIGINATED, fire-and-forget message (no `sender()` needed). A single
  //      `ctx.messageAdapter[Any]` is registered in `apply()`; we hand its `.toClassic` ref to children as their
  //      `replyTo`/listener (instead of the bare self-ref Classic adapter), so each child reply arrives as `WrappedExternal`.
  //      The handler unwraps and dispatches on the payload type — the existing match arms move under
  //      `case WrappedExternal(msg) => msg match { ... }`. Forwarding catch-alls inside that block still work because
  //      the parent is the message's destination, not a relay needing the original sender.
  //
  //   2. `WrappedSyncProtocol(msg)` — EXTERNAL-SENDER ask / reply-dependent traffic. The JSON-RPC layer sends
  //      `SyncController.WrappedSyncProtocol(SyncProtocol.GetStatus(replyTo))` via Typed ask; each `SyncProtocol.*`
  //      message carries a typed `replyTo: ActorRef[ReplyType]` field. The handler replies directly to `msg.replyTo`
  //      — no `ctx.toClassic.sender()` required. The OQ-5 Classic ask path has been fully eliminated.
  // INFO-4/INFO-11: concrete message types currently routed through WrappedExternal (child fire-and-forget only).
  // Each child uses a narrow per-type adapter declared in apply(). Update this list when new
  // child reply types are added or removed.
  //
  //   From SNAPSyncController (child reply-target = snapAdapter via Typed spawn):
  //     SNAPSyncController.SnapSyncFinalized, SNAPSyncController.Done,
  //     SNAPSyncController.RequestHealingServeRoot, SNAPSyncController.StartRegularSyncBootstrap,
  //     SNAPSyncController.StartRegularSyncBootstrapByHash, SNAPSyncController.BootstrapComplete,
  //     SNAPSyncController.PivotBootstrapFailed, SyncProtocol.HealingImpossible, SyncProtocol.Status.Progress
  //
  //   From PivotHeaderBootstrap (replyTo = pivotBootstrapAdapter — §8k-G4e narrow typed adapter):
  //     PivotHeaderBootstrap.Completed, PivotHeaderBootstrap.Failed
  //
  //   From NetworkPeerManagerActor (reply-target = handshakedPeersAdapter, via GetHandshakedPeersCmd):
  //     NetworkPeerManagerActor.HandshakedPeers
  //     NetworkPeerManagerActor.CalibrateChainWeightFromPeer (RegisterChainWeightCalibrationTarget)
  //
  //   From ForkChoiceManager (listener = fcmAdapter):
  //     ForkChoiceManager.BeaconHead
  //
  //   From RegularSync, CombinedRecoveryScanActor, ChainDownloader (child replies forwarded):
  //     RegularSync.ProgressProtocol.*, recovery scanner events
  //
  //   From NPMA SNAP routing during recovery (§8k-G4c): no SNAPSyncController is live; SyncController
  //   is registered as the SNAP target and relays responses to the recovery coordinators:
  //     SNAPSyncController.ByteCodesResponse  → BytecodeRecoveryActor.ForwardByteCodesResponse
  //     SNAPSyncController.StorageRangesResponse → StorageRecoveryActor.ForwardStorageRangesResponse
  //     SNAPSyncController.AccountRangeResponse, TrieNodesResponse → dropped (not used in recovery)
  final private[sync] case class WrappedExternal(msg: Any)
      extends Command // Any: Pekko messageAdapter boundary — Classic msgs arrive untyped
  // Public: external callers (JSON-RPC layer, miner, NodeBuilder startup) construct this to wrap their raw
  // SyncProtocol.* messages so they survive the Behavior[Command] boundary. Each SyncProtocol.* message carries a
  // typed `replyTo` field; the handler unwraps and replies directly to `msg.replyTo`.
  final case class WrappedSyncProtocol(msg: SyncProtocol.SyncProtocolMsg) extends Command

  // scalastyle:off parameter.number
  def apply(
      blockchain: Blockchain,
      blockchainReader: BlockchainReader,
      blockchainWriter: BlockchainWriter,
      appStateStorage: AppStateStorage,
      evmCodeStorage: EvmCodeStorage,
      stateStorage: StateStorage,
      flatSlotStorage: FlatSlotStorage,
      fastSyncStateStorage: FastSyncStateStorage,
      consensus: ConsensusAdapter,
      validators: Validators,
      peerEventBus: TypedActorRef[com.chipprbots.ethereum.network.PeerEventBusActor.Command],
      pendingTransactionsManager: org.apache.pekko.actor.typed.ActorRef[
        com.chipprbots.ethereum.transactions.PendingTransactionsManager.Command
      ],
      blockTopic: org.apache.pekko.actor.typed.ActorRef[
        org.apache.pekko.actor.typed.pubsub.Topic.Command[com.chipprbots.ethereum.jsonrpc.NewBlockImported]
      ],
      ommersPool: org.apache.pekko.actor.typed.ActorRef[com.chipprbots.ethereum.ommers.OmmersPool.Command],
      networkPeerManager: TypedActorRef[com.chipprbots.ethereum.network.NetworkPeerManagerActor.Command],
      blacklist: Blacklist,
      syncConfig: SyncConfig,
      configBuilder: BlockchainConfigBuilder,
      messConfig: Option[MESSConfig] = None,
      forkChoiceManagerOpt: Option[ForkChoiceManager] = None,
      externalSchedulerOpt: Option[Scheduler] = None,
      reorgState: ReorgStateHandler = ReorgStateHandler.NoOp,
      txGossipGate: com.chipprbots.ethereum.transactions.TxGossipGate =
        com.chipprbots.ethereum.transactions.TxGossipGate.alwaysOpen,
      backgroundAuditEnabled: Boolean = BackgroundAudit.enabledInNodeConfig,
      // #1654: the one-time chain-weight pass. On in production; unit fixtures that set inconsistent weights on purpose,
      // and test something else, turn it off so it does not repair them under the test.
      chainWeightReconcileOn: Boolean = true
  ): Behavior[Command] =
    Behaviors.setup { ctx =>
      Behaviors.withTimers { timers =>
        val impl = new Impl(
          ctx,
          timers,
          blockchain,
          blockchainReader,
          blockchainWriter,
          appStateStorage,
          evmCodeStorage,
          stateStorage,
          flatSlotStorage,
          fastSyncStateStorage,
          consensus,
          validators,
          peerEventBus,
          pendingTransactionsManager,
          blockTopic,
          ommersPool,
          networkPeerManager,
          blacklist,
          syncConfig,
          configBuilder,
          messConfig,
          forkChoiceManagerOpt,
          externalSchedulerOpt,
          reorgState,
          txGossipGate,
          backgroundAuditEnabled,
          chainWeightReconcileOn
        )
        impl.setup()
        impl.withPostStop(impl.idle())
      }
    }
  // scalastyle:on parameter.number

  // scalastyle:off number.of.methods
  // scalastyle:off parameter.number
  private class Impl(
      ctx: ActorContext[Command],
      timers: TimerScheduler[Command],
      blockchain: Blockchain,
      blockchainReader: BlockchainReader,
      blockchainWriter: BlockchainWriter,
      appStateStorage: AppStateStorage,
      evmCodeStorage: EvmCodeStorage,
      stateStorage: StateStorage,
      flatSlotStorage: FlatSlotStorage,
      fastSyncStateStorage: FastSyncStateStorage,
      consensus: ConsensusAdapter,
      validators: Validators,
      peerEventBus: TypedActorRef[com.chipprbots.ethereum.network.PeerEventBusActor.Command],
      pendingTransactionsManager: org.apache.pekko.actor.typed.ActorRef[
        com.chipprbots.ethereum.transactions.PendingTransactionsManager.Command
      ],
      blockTopic: org.apache.pekko.actor.typed.ActorRef[
        org.apache.pekko.actor.typed.pubsub.Topic.Command[com.chipprbots.ethereum.jsonrpc.NewBlockImported]
      ],
      ommersPool: org.apache.pekko.actor.typed.ActorRef[com.chipprbots.ethereum.ommers.OmmersPool.Command],
      networkPeerManager: TypedActorRef[com.chipprbots.ethereum.network.NetworkPeerManagerActor.Command],
      blacklist: Blacklist,
      syncConfig: SyncConfig,
      configBuilder: BlockchainConfigBuilder,
      messConfig: Option[MESSConfig],
      forkChoiceManagerOpt: Option[ForkChoiceManager],
      externalSchedulerOpt: Option[Scheduler],
      reorgState: ReorgStateHandler,
      // Closed while SNAP or state recovery runs (no complete state to validate a peer tx against), opened when
      // regular sync takes over; regular sync marks the node synced once it reaches the network's best block.
      txGossipGate: com.chipprbots.ethereum.transactions.TxGossipGate,
      backgroundAuditEnabled: Boolean,
      chainWeightReconcileOn: Boolean
  ):
    // scalastyle:on parameter.number

    // Plain SLF4J logger (safe from any thread — used inside scheduled callbacks below).
    private val log = org.slf4j.LoggerFactory.getLogger(getClass)

    // Generation counters for actor names to prevent Pekko name collisions
    // (context.stop is async — new actors can race with still-stopping ones).
    private var bootstrapGeneration: Long = 0
    private var syncGeneration: Long = 0

    // #1705: regular sync's import lock, ONE for the node's life. Every RegularSync started here and every importer it
    // spawns take this same one, so an import that a stopped importer, or a stopped RegularSync (`ctx.stop` stops
    // neither's running import), left running ends before a new importer reads its start block or imports.
    private val importLock: ImportLock = ImportLock()

    // #1654: the one-time chain-weight pass (ChainWeightReconcile), a fiber off the critical path. `chainWeightEpoch` is
    // superseded by everything that must win over a running pass (a SNAP start, a seed, a calibration write, this
    // actor's stop), through a monitor the pass also takes for its saves and its final batch, so it never sets the
    // marker or saves a place over what superseded it. That monitor is held for one small commit at a time: nothing on
    // this thread waits behind a repair.
    private val chainWeightEpoch = new ChainWeightReconcile.Epoch
    // One pass at a time (a new pass waits for a superseded one to end), and the running one with its "ended" flag.
    private val chainWeightPassLock: cats.effect.std.Mutex[IO] =
      cats.effect.std.Mutex.in[cats.effect.SyncIO, IO].unsafeRunSync()
    private var chainWeightPass
        : Option[(() => scala.concurrent.Future[Unit], java.util.concurrent.atomic.AtomicBoolean)] =
      None
    private val chainWeightLog = org.slf4j.LoggerFactory.getLogger(ChainWeightReconcile.LoggerName)

    // spec 022 T053: the full-trie walk off the critical path, alive exactly while regular sync is (decision 9).
    private var backgroundAudit: Option[TypedActorRef[BackgroundAudit.Command]] = None
    private var saidAuditOff: Boolean = false

    // Recovery recent-root roll (Task #5/#6): post-SNAP storage recovery asks for a recent canonical root
    // when the saved pivot has aged out of peers' serve window. We fetch a recent header via
    // PivotHeaderBootstrap (inline in `runningRecovery` — no transition into the deadlock-prone bootstrap
    // state) and reply with StorageRecoveryActor.RecentRoot. Only one request is serviced at a time.
    private var recentRootRequester: Option[TypedActorRef[StorageRecoveryActor.Command]] = None

    // H3: the block a re-SNAP waiting out its backoff starts from, while regular sync keeps running.
    private var pendingResnap: Option[BigInt] = None

    // The codeHash that sent regular sync into a bulk bytecode recovery, until that recovery hands back to regular sync.
    private var bulkCodeRecoveryTrigger: Option[ByteString] = None
    // That recovery's scan could not run, so its failure says nothing about the code (spec 022 T050a). Read and cleared with it.
    private var recoveryScanCouldNotRun: Boolean = false
    private var recentRootBootstrap
        : Option[(TypedActorRef[PeersClient.Command], TypedActorRef[PivotHeaderBootstrap.Command])] =
      None // (peersClient, headerBootstrap)
    private var recentRootGeneration: Int = 0

    // spec 004 (Decoupled Heal Serve-Root) T012: a SEPARATE requester slot + bootstrap + generation for the HEALING
    // serve-root request, so it never contends with `recentRootRequester` (storage recovery's single slot). During
    // healing, SNAPSyncController (the child) asks for a newest-servable root via RequestHealingServeRoot; we fetch
    // a recent header with a dedicated PivotHeaderBootstrap (inline in `runningSnapSync` — no transition into the
    // deadlock-prone bootstrap state) and reply HealingServeRoot to the child. Only one is serviced at a time.
    private var healingServeRootRequester: Option[TypedActorRef[SNAPSyncController.Command]] = None
    private var healingServeRootBootstrap
        : Option[(TypedActorRef[PeersClient.Command], TypedActorRef[PivotHeaderBootstrap.Command])] =
      None // (peersClient, headerBootstrap)
    private var healingServeRootGeneration: Int = 0
    // Roll the download root this many blocks back from the network head — comfortably inside core-geth's
    // ~128-block snapshot serve window so peers can serve the recent root, yet recent enough that ~all
    // cold contracts' storage is unchanged since the original pivot (and thus content-identical).
    private val RecentRootMarginBlocks: BigInt = BigInt(64)

    // Latest CL-driven head hint received from ForkChoiceManager. Buffered so that when SNAP
    // sync starts (which may happen after the CL has already pushed several FCUs), the freshest
    // head is available as the pivot target. Only populated on post-merge chains where TTD is
    // configured AND a ForkChoiceManager was supplied — ETC mainnet leaves this `None` forever
    // and the existing TD-based pivot path is unaffected. Closes #1207.
    private var latestBeaconHead: Option[ForkChoiceManager.BeaconHead] = None

    // #1555: the SNAP pivot whose finalization just failed (HealingImpossible), handed to every SNAP controller spawned
    // until SNAP finalizes, so its accounts-complete recovery does not resume healing on that pivot. In memory only:
    // after a process restart the recovery may resume on it once more, then the next failure records it again.
    private var snapPivotFailedFinalization: Option[BigInt] = None

    // Whether SNAP should consume CL-driven pivot selection. Captured once at construction
    // because both `syncConfig` and the chain config are stable for the actor's lifetime.
    private val isPoSChain: Boolean = configBuilder.blockchainConfig.terminalTotalDifficulty.isDefined
    private val clPivotEnabled: Boolean = isPoSChain && forkChoiceManagerOpt.isDefined

    // PoS fork choice for the p2p import path, or None. Same conjunction as clPivotEnabled above, but note which half
    // does the work: a running Node always supplies a ForkChoiceManager (Node.forkChoiceManagerForSync), so the
    // OPERATIVE gate is `isPoSChain` — a configured terminal-total-difficulty, set in eth-chain.conf,
    // sepolia-chain.conf and plataberget-chain.conf and in NO PoW chain config. On ETC/Mordor/Gorgoroth this is None
    // for the life of the node, so `BranchResolution`'s PoS arm predicate is always false there. See DesignatedHead.
    // Reads the REQUESTED head, not getHeadBlockHash (executed-only, so it lags exactly when a CL-designated side
    // branch is being fetched). See ForkChoiceManager.getRequestedHeadBlockHash.
    private val designatedHeadOpt: Option[DesignatedHead] =
      if isPoSChain then forkChoiceManagerOpt.map(fcm => DesignatedHead(() => fcm.getRequestedHeadBlockHash))
      else None

    // TD calibration stats — updated by CalibrateChainWeightFromPeer handler.
    // calibrationSucceeded and networkBestTD are read by the TD_CALIBRATION_STATS periodic log
    // (future enhancement) — suppress unused warnings.
    private var tdCalibrationAttempt: Int = 0 // number of tier-3 (local chain) attempts
    @annotation.unused
    private var calibrationSucceeded: Boolean = false
    private var lastCalibrationSource: String = "NONE"
    @annotation.unused
    private var networkBestTD: BigInt = BigInt(0) // last peerTD pushed by NPA

    /** Construction-time side effects (former `preStart`). */
    def setup(): Unit =
      if clPivotEnabled then
        forkChoiceManagerOpt.foreach { fcm =>
          fcm.setListener(fcmAdapter)
          log.info(
            "Registered SyncController as ForkChoiceManager listener (post-merge chain TTD={}); " +
              "SNAP pivot will be CL-driven once first forkchoiceUpdated arrives.",
            configBuilder.blockchainConfig.terminalTotalDifficulty.get
          )
        }

    /** Former `postStop`. Attached as a `PostStop` signal handler on every behavior via `withPostStop`. */
    private def onPostStop(): Unit =
      forkChoiceManagerOpt.foreach(_.clearListener())
      stopChainWeightReconcile()

    /** Wrap a behavior with the shared `PostStop` cleanup so listener deregistration runs from every state.
      *
      * MIGRATION (W10): replaced the full `BehaviorInterceptor` (whose `aroundReceive` was a pure identity
      * pass-through) with `BehaviorSignalInterceptor` — a built-in Pekko helper that only intercepts signals and lets
      * all messages pass through unmodified (no `aroundReceive` override needed). This eliminates ~15 lines and the
      * unnecessary identity `aroundReceive` allocation.
      */
    def withPostStop(b: Behavior[Command]): Behavior[Command] =
      Behaviors.intercept(() =>
        new org.apache.pekko.actor.typed.BehaviorSignalInterceptor[Command]():
          override def aroundSignal(
              c: org.apache.pekko.actor.typed.TypedActorContext[Command],
              signal: org.apache.pekko.actor.typed.Signal,
              target: org.apache.pekko.actor.typed.BehaviorInterceptor.SignalTarget[Command]
          ): Behavior[Command] =
            if signal == PostStop then onPostStop()
            target(c, signal)
      )(b)

    /** Classic scheduler passed to SNAPSyncController, which manages its own scheduled callbacks. Inline `scheduleOnce`
      * calls use `ctx.system.scheduler` directly. Self-Command timers use `timers`.
      */
    def scheduler: Scheduler = externalSchedulerOpt.getOrElse(ctx.system.classicSystem.scheduler)

    /** Phase 2 (ROOT-b) unwrap shim. Each behavior receives `Command`; the heterogeneous external traffic is wrapped
      * (`WrappedExternal` via the message adapter, `WrappedSyncProtocol` constructed by callers). Unwrapping to the raw
      * payload here lets the existing per-behavior match arms (which key on the concrete external types and on the
      * internal `Command` markers) stay unchanged. Internal Commands (timers, death-watch markers, status queries) fall
      * through the wildcard and match themselves. `WrappedSyncProtocol` preserves the ask `sender()` because callers
      * `.tell` it with the original sender; `WrappedExternal` is fire-and-forget (its arms never read `sender()`).
      */
    private def unwrap(cmd: Command): Any = cmd match // Any: returns Classic msg from WrappedExternal
      case WrappedExternal(m)     => m
      case WrappedSyncProtocol(m) => m
      case m                      => m

    // §8k-G3: per-child narrow typed adapters. Each wraps into WrappedExternal. Pekko's adapter
    // routing table is keyed by Class[T] per actor; multiple adapters on the same ActorContext
    // share the same mailbox. SyncController's unwrap() / WrappedExternal dispatch is UNCHANGED.
    val bytecodeRecoveryAdapter: TypedActorRef[BytecodeRecoveryActor.RecoveryComplete.type] =
      ctx.messageAdapter[BytecodeRecoveryActor.RecoveryComplete.type](WrappedExternal.apply)
    val storageRecoveryAdapter: TypedActorRef[StorageRecoveryActor.SyncControllerMsg] =
      ctx.messageAdapter[StorageRecoveryActor.SyncControllerMsg](WrappedExternal.apply)
    val combinedScanAdapter: TypedActorRef[CombinedRecoveryScanActor.CombinedScanComplete] =
      ctx.messageAdapter[CombinedRecoveryScanActor.CombinedScanComplete](WrappedExternal.apply)
    val pivotBootstrapAdapter: TypedActorRef[PivotHeaderBootstrap.Reply] =
      ctx.messageAdapter[PivotHeaderBootstrap.Reply](WrappedExternal.apply)
    val chainDownloaderAdapter: TypedActorRef[snap.ChainDownloader.Done.type] =
      ctx.messageAdapter[snap.ChainDownloader.Done.type](WrappedExternal.apply)
    // §8k-G3-SSC: narrow typed adapter for SNAPSyncController's 7 reply types (6 in SSC companion +
    // SyncProtocol.HealingImpossible), all sharing the SyncControllerReply marker trait.
    val snapAdapter: TypedActorRef[SyncProtocol.SyncControllerReply] =
      ctx.messageAdapter[SyncProtocol.SyncControllerReply](WrappedExternal.apply)
    // §8k-G4a: narrow typed adapter for ForkChoiceManager's single reply type.
    val fcmAdapter: TypedActorRef[ForkChoiceManager.BeaconHead] =
      ctx.messageAdapter[ForkChoiceManager.BeaconHead](WrappedExternal.apply)
    // §8k-G4b: narrow typed adapter registered with NPMA as the chain-weight calibration target.
    // NPMA pushes SyncProtocol.CalibrateChainWeightFromPeer here on TD-PROXY-GAP / timed calibration;
    // it arrives as WrappedExternal and is dispatched by the existing CalibrateChainWeightFromPeer arm.
    val cwCalibrationAdapter: TypedActorRef[SyncProtocol.CalibrateChainWeightFromPeer] =
      ctx.messageAdapter[SyncProtocol.CalibrateChainWeightFromPeer](WrappedExternal.apply)
    // §8k-G4d: narrow typed adapter for NetworkPeerManagerActor's HandshakedPeers reply.
    // All 3 GetHandshakedPeersCmd call sites use this so the replyTo field is
    // TypedActorRef[HandshakedPeers] rather than TypedActorRef[Any].
    val handshakedPeersAdapter: TypedActorRef[
      com.chipprbots.ethereum.network.NetworkPeerManagerActor.HandshakedPeers
    ] =
      ctx.messageAdapter[com.chipprbots.ethereum.network.NetworkPeerManagerActor.HandshakedPeers](
        WrappedExternal.apply
      )
    // §8k-G4e-final: narrow typed adapter for NPMA SNAP response routing during post-SNAP recovery.
    // No SNAPSyncController exists during recovery; SyncController registers itself as the SNAP target
    // so NPMA routes ByteCodesResponse / StorageRangesResponse here via WrappedExternal dispatch.
    // NPMA calls .toTyped[SNAPSyncController.Command] on the Classic bridge, so the adapter must cover
    // SNAPSyncController.Command (common supertype of all SNAP protocol messages).
    val recoverySnapAdapter: TypedActorRef[SNAPSyncController.Command] =
      ctx.messageAdapter[SNAPSyncController.Command](WrappedExternal.apply)

    /** Load SNAP sync configuration with fallback to defaults */
    private def loadSnapSyncConfig(): SNAPSyncConfig =
      val config =
        try SNAPSyncConfig.fromConfig(Config.config.getConfig("sync"))
        catch
          case e: Exception =>
            log.warn(s"Failed to load SNAP sync config, using defaults: ${e.getMessage}")
            // The constructor's default for the header hold is off (for stub-driven test suites). The shipped
            // default is on for every chain (#1654): a node on this fallback must not end SNAP with an estimated
            // pivot total difficulty.
            SNAPSyncConfig(deferChainBackfillUntilStateComplete = true)
      val networkType = configBuilder.blockchainConfig.networkType
      val expectedScheme = if networkType == NetworkType.ETH then StorageScheme.Path else StorageScheme.Hash
      require(
        config.storageScheme == expectedScheme,
        s"storageScheme=${config.storageScheme} does not match expected $expectedScheme " +
          s"for networkType=$networkType — check sync.snap-sync.storage-scheme in reference.conf"
      )
      // Keep the SNAP storage-task file under the datadir so it survives a reboot (/tmp does not).
      val taskFileDir = scala.util
        .Try(Config.config.getString("datadir"))
        .toOption
        .map(d => StorageTaskFile.dirUnder(java.nio.file.Paths.get(d)))
      config.copy(taskFileDir = taskFileDir)

    def idle(): Behavior[Command] = Behaviors.receive { (_, cmd) =>
      val msg = unwrap(cmd)
      msg match
        case SyncProtocol.Start =>
          start()
        case bh: ForkChoiceManager.BeaconHead =>
          // Buffer for the eventual SNAP startup; idle predates startSnapSync().
          handleBeaconHead(bh, snapSyncOpt = None)
          Behaviors.same
        case _ => Behaviors.unhandled
    }

    def runningSnapSync(snapSync: TypedActorRef[SNAPSyncController.Command]): Behavior[Command] = Behaviors.receive {
      (_, cmd) =>
        val msg = unwrap(cmd)
        msg match
          case SnapSyncCriticalFailure =>
            // §7c-D4: STOP-AND-ALERT — SNAP controller crashed mid-sync. In-flight SNAP session state is corrupt;
            // restart is unsafe. Stop the controller (and the node sync tree) so ops alerting (CRITICAL) triggers a
            // controlled restart rather than a silent degraded state.
            log.error("CRITICAL actor stopped unexpectedly — node restart required: {}", "snap-sync")
            Behaviors.stopped
          case StartRegularSyncBootstrap(targetBlock) =>
            log.info(s"SNAP sync requested bootstrap to pivot ${targetBlock}")

            // Prefer a header-only bootstrap: SNAP only needs the pivot header (stateRoot).
            bootstrapGeneration += 1
            val gen = bootstrapGeneration
            val peersClient =
              ctx.spawn(
                Behaviors
                  .supervise(PeersClient.behavior(networkPeerManager, peerEventBus, blacklist, syncConfig))
                  .onFailure[Throwable](
                    // PR #1378: PeersClient is a sync backbone actor. No `.withMaxRestarts` —
                    // a cap would silently stop it forever and stall sync (validated live on Mordor).
                    SupervisorStrategy.restartWithBackoff(500.millis, 10.seconds, 0.2)
                  ),
                s"peers-client-bootstrap-$gen"
              )
            val headerBootstrap =
              ctx
                .spawn(
                  Behaviors
                    .supervise(
                      PivotHeaderBootstrap(
                        peersClient,
                        blockchainWriter,
                        targetBlock,
                        replyTo = pivotBootstrapAdapter,
                        syncConfig,
                        preferSnapPeers = true
                      )
                    )
                    // PivotHeaderBootstrap must keep retrying until the pivot header arrives — no cap.
                    .onFailure[Throwable](SupervisorStrategy.restartWithBackoff(2.seconds, 60.seconds, 0.1)),
                  s"pivot-header-bootstrap-$gen"
                )

            // spec 004 MUST-FIX: clear any in-flight healing serve-root request as part of the transition so no healing
            // bootstrap survives into runningPivotHeaderBootstrap (where its Completed/Failed/Timeout would dead-letter).
            abortHealingServeRootRequest("entering pivot header bootstrap")
            runningPivotHeaderBootstrap(peersClient, headerBootstrap, targetBlock, snapSync)

          case StartRegularSyncBootstrapByHash(headHash) =>
            // CL-driven bootstrap path (#1207): fetch the head header by hash from peers.
            // The block number isn't known until the header arrives.
            log.info(
              "SNAP requested by-hash pivot header bootstrap for {}",
              com.chipprbots.ethereum.utils.ByteStringUtils.hash2string(headHash)
            )
            bootstrapGeneration += 1
            val gen = bootstrapGeneration
            val peersClient =
              ctx.spawn(
                Behaviors
                  .supervise(PeersClient.behavior(networkPeerManager, peerEventBus, blacklist, syncConfig))
                  .onFailure[Throwable](
                    // PR #1378: PeersClient is a sync backbone actor — no `.withMaxRestarts` cap.
                    SupervisorStrategy.restartWithBackoff(500.millis, 10.seconds, 0.2)
                  ),
                s"peers-client-bootstrap-$gen"
              )
            val headerBootstrap =
              ctx
                .spawn(
                  Behaviors
                    .supervise(
                      PivotHeaderBootstrap.applyByHash(
                        peersClient,
                        blockchainWriter,
                        headHash,
                        replyTo = pivotBootstrapAdapter,
                        syncConfig,
                        preferSnapPeers = false
                      )
                    )
                    // PivotHeaderBootstrap must keep retrying until the pivot header arrives — no cap.
                    .onFailure[Throwable](SupervisorStrategy.restartWithBackoff(2.seconds, 60.seconds, 0.1)),
                  s"pivot-header-bootstrap-$gen"
                )
            // We pass `targetBlock = 0` as a placeholder — the bootstrap reply carries the
            // resolved `header.number`. The runningPivotHeaderBootstrap state's matching on
            // `block == targetBlock` is bypassed in by-hash mode by using a wildcard handler;
            // the resolved Completed.targetBlock is preserved when handed to SNAP via
            // BootstrapComplete.
            // spec 004 MUST-FIX: abort any in-flight healing serve-root request BEFORE entering the by-hash bootstrap.
            // With targetBlock == 0 the bootstrap's `Completed` guard accepts ANY block, so a stray healing `Completed`
            // could otherwise be mis-consumed as the pivot header. Clearing here also prevents the dead-letter wedge.
            abortHealingServeRootRequest("entering by-hash pivot header bootstrap")
            runningPivotHeaderBootstrap(peersClient, headerBootstrap, targetBlock = BigInt(0), snapSync)

          case com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.SnapSyncFinalized(pivot) =>
            log.info(
              s"SNAP state finalised at pivot=$pivot. Starting regular sync; chain backfill continues in background."
            )
            clearSnapPivotFloor()
            markChainWeightsAfterSnap(pivot)
            // spec 004 MUST-FIX: clear the healing serve-root latch on every exit from runningSnapSync.
            abortHealingServeRootRequest("SNAP finalised — leaving snap sync")
            // SNAPSyncController already owns the live ChainDownloader child via its
            // `completedWithBackfill` state — don't spawn a duplicate standalone resumer (#1169).
            val (regularSync, _) = startRegularSync(resumeBackfill = false)
            // §7c-D4: SNAP now transitions to benign background backfill — its termination here is expected, not
            // critical. Drop the STOP-AND-ALERT critical watch and re-watch with the benign SnapSyncTerminated marker
            // (watchWith throws IllegalStateException if the prior watch message differs, so unwatch first).
            ctx.unwatch(snapSync)
            ctx.watchWith(snapSync, SnapSyncTerminated(snapSync))
            runningRegularSyncWithBackfill(regularSync, snapSync)

          case com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.Done =>
            // Defensive fallback: with the post-#1162 handshake, SnapSyncFinalized always precedes Done,
            // so this branch should not normally be reached. If it is (e.g., unexpected message ordering),
            // treat as a legacy "SNAP done" signal.
            ctx.unwatch(snapSync) // §7c-D4: intentional stop — drop the critical death-watch first.
            ctx.stop(snapSync)
            log.info("SNAP sync completed (legacy Done path), transitioning to regular sync")
            // spec 004 MUST-FIX: clear the healing serve-root latch on every exit from runningSnapSync.
            abortHealingServeRootRequest("SNAP done (legacy) — leaving snap sync")
            startRegularSync()._2

          case SyncProtocol.HealingImpossible =>
            ctx.unwatch(snapSync) // §7c-D4: intentional stop — drop the critical death-watch first.
            ctx.stop(snapSync)
            // #1555: the pivot finalizeSnapSync just failed on is the persisted SNAP pivot (the lazy hand-offs anchor
            // it first), which is exactly what the accounts-complete recovery would resume on.
            snapPivotFailedFinalization = appStateStorage.getSnapSyncPivotBlock()
            log.warn(
              s"SNAP finalization aborted at pivot ${snapPivotFailedFinalization.getOrElse("unknown")} (state root " +
                "mismatch or Path publish failure). Clearing the SNAP done flags and restarting SNAP; on PoS chains a " +
                "heal-only recovery takes a newer pivot than that one, otherwise recovery resumes on the saved pivot."
            )
            // spec 004 MUST-FIX: clear the healing serve-root latch on every exit from runningSnapSync. The new SNAP
            // actor started below gets a fresh latch, so the stale requester here must not linger.
            abortHealingServeRootRequest("SNAP healing impossible — restarting snap sync")
            // Platåberget soak, 2026-09-27 (BUG-006/A5 stale-anchor investigation): intentionally NOT clearing
            // SnapSyncPivotBlock/SnapSyncStateRoot here, only the two *Done flags. The fresh SNAPSyncController
            // spawned by startSnapSync() below re-reads those two keys via its accounts-complete recovery path
            // (SNAPSyncController.startSnapSync(), the `isSnapSyncAccountsComplete()` branch) and resumes
            // bytecodes/storage/healing from them rather than re-downloading complete, content-addressed data.
            // Clearing them would force that branch's `case _ => ... Clearing and restarting fresh` fallback —
            // a full account/bytecode/storage re-download — which is the wrong trade for what was, before the
            // anchorPivotBeforeLazyHandoff fix above, the actual root cause of every observed HealingImpossible:
            // a self-inflicted stale anchor, not a genuinely bad one. That fix makes the anchor persisted here
            // match the pivot finalizeSnapSync() just tried and failed to commit, so a *residual* HealingImpossible
            // after this fix indicates a real anchor/header divergence — and the resumed instance already has an
            // existing, independent safety net for that: the accounts-complete recovery branch's own
            // networkBest/maxPivotStalenessBlocks drift check, and normal operation's all-peers-stateless
            // escalation, both of which re-peg away from a stale/unservable root without discarding downloaded
            // progress. Revisit only if a residual HealingImpossible is ever observed looping on the same pivot.
            //
            // #1555 (Platåberget, 2026-10-10): it was observed looping on the same pivot. A frozen CL kept the heal
            // pivot on a root no peer served; the lazy hand-off's Path publish then failed (root node absent) and this
            // restart resumed healing on that same pivot, every 17-24 minutes. The keys are still NOT cleared (the
            // downloaded data stays usable); instead the failed pivot is recorded above and passed to the new SNAP
            // controller, whose recovery takes a newer pivot on PoS chains (SNAPSyncController.recoverAccountsComplete).
            appStateStorage.clearSnapSyncDone().commitSync() // spec 022 D7: with the invariant marker
            appStateStorage.clearFastSyncDone().commit()
            startSnapSync()

          case SyncProtocol.Status.Progress(_, _) =>
            log.debug("SNAP sync in progress")
            Behaviors.same

          case bh: ForkChoiceManager.BeaconHead =>
            handleBeaconHead(bh, snapSyncOpt = Some(snapSync))
            Behaviors.same

          // spec 004 (Decoupled Heal Serve-Root) T012: the healing coordinator (via SNAPSyncController) asks for a
          // newest-servable root to fetch missing nodes against, while its completeness walk stays pinned to the walk
          // root. Fetch a recent header via a DEDICATED bootstrap slot (never the storage-recovery `recentRootRequester`)
          // and reply HealingServeRoot to the child. Run inline — no transition into the deadlock-prone bootstrap state.
          case SNAPSyncController.RequestHealingServeRoot =>
            if healingServeRootRequester.isEmpty && healingServeRootBootstrap.isEmpty then
              // Phase 2 (ROOT-b): reply to the known SNAP child ref directly rather than `ctx.toClassic.sender()`, which
              // would resolve to the message adapter now that SNAP routes through a typed adapter.
              healingServeRootRequester = Some(snapSync)
              log.info(
                "[HEAL-SERVE-ROOT] Healing requested a newest-servable root. Polling peers for the network head."
              )
              networkPeerManager ! com.chipprbots.ethereum.network.NetworkPeerManagerActor.GetHandshakedPeersCmd(
                handshakedPeersAdapter
              )
            else log.debug("[HEAL-SERVE-ROOT] Healing serve-root request already in flight; ignoring duplicate.")
            Behaviors.same

          // Peer snapshot used both to feed snap peers and (if waiting) to start the healing serve-root bootstrap.
          case com.chipprbots.ethereum.network.NetworkPeerManagerActor.HandshakedPeers(peers)
              if healingServeRootRequester.isDefined && healingServeRootBootstrap.isEmpty =>
            maybeStartHealingServeRootBootstrap(peers)
            Behaviors.same

          case PivotHeaderBootstrap.Completed(block, header) if healingServeRootRequester.isDefined =>
            val rootHex = header.stateRoot.value.take(4).toArray.map("%02x".format(_)).mkString
            log.info(s"[HEAL-SERVE-ROOT] Fetched header for block $block (root $rootHex). Replying to healing.")
            stopHealingServeRootBootstrap()
            healingServeRootRequester.foreach(
              _ ! SNAPSyncController.HealingServeRoot(block, Some(header.stateRoot))
            )
            healingServeRootRequester = None
            Behaviors.same

          case PivotHeaderBootstrap.Failed(reason) if healingServeRootRequester.isDefined =>
            log.warn(s"[HEAL-SERVE-ROOT] Serve-root bootstrap failed ($reason). Replying None (serve root kept).")
            stopHealingServeRootBootstrap()
            healingServeRootRequester.foreach(_ ! SNAPSyncController.HealingServeRoot(0, None))
            healingServeRootRequester = None
            Behaviors.same

          // spec 004 MUST-FIX: the guard (gen == healingServeRootGeneration && requester.isDefined) makes a late timeout a
          // no-op after abortHealingServeRootRequest — abort clears the requester, and the next request bumps the generation.
          case HealingServeRootTimeout(gen)
              if gen == healingServeRootGeneration && healingServeRootRequester.isDefined =>
            log.warn("[HEAL-SERVE-ROOT] Serve-root bootstrap timed out. Replying None (serve root kept).")
            stopHealingServeRootBootstrap()
            healingServeRootRequester.foreach(_ ! SNAPSyncController.HealingServeRoot(0, None))
            healingServeRootRequester = None
            Behaviors.same

          // W3: HandshakedPeers not consumed by the guarded arm above (bootstrap already in flight, or no healing request
          // in progress) must NOT fall through to the catch-all and be forwarded to snapSync, which does not handle the
          // raw NetworkPeerManagerActor.HandshakedPeers message (it uses its own messageAdapter → WrappedHandshakedPeers).
          // Silence all remaining HandshakedPeers arrivals here (msg is already unwrapped by unwrap(cmd)).
          case com.chipprbots.ethereum.network.NetworkPeerManagerActor.HandshakedPeers(_) =>
            Behaviors.same

          // Stale PivotHeaderBootstrap replies that arrive in runningSnapSync without a healing request in flight
          // (the bootstrap was stopped by abortHealingServeRootRequest before leaving runningPivotHeaderBootstrap,
          // but Pekko stopping is async so a final Completed/Failed can still be in the mailbox). Drop silently.
          case _: PivotHeaderBootstrap.Completed =>
            log.debug(
              "[HEAL-SERVE-ROOT] Stale PivotHeaderBootstrap.Completed in runningSnapSync (no healing in flight)"
            )
            Behaviors.same

          case _: PivotHeaderBootstrap.Failed =>
            log.debug("[HEAL-SERVE-ROOT] Stale PivotHeaderBootstrap.Failed in runningSnapSync (no healing in flight)")
            Behaviors.same

          case msg: SyncProtocol.GetStatus =>
            // JSON-RPC eth_syncing while SNAP is running. SSC doesn't have GetStatus in its Command ADT;
            // reply inline with a generic Syncing status so callers don't time out.
            msg.replyTo ! SyncProtocol.Status.Syncing(
              startingBlockNumber = appStateStorage.getSyncStartingBlock(),
              blocksProgress = SyncProtocol.Status.Progress(appStateStorage.getBestBlockNumber(), BigInt(0)),
              stateNodesProgress = None
            )
            Behaviors.same

          case msg if isInternalMarker(msg) =>
            // Late self/death-watch marker for a child stopped before this transition — drop silently.
            Behaviors.same
          case other =>
            log.warn("Unexpected message in runningSnapSync: {}", other.getClass.getSimpleName)
            Behaviors.same
    }

    /** spec 004 T012: start a one-shot header bootstrap for a newest-servable block (margin back from the network head)
      * on the dedicated healing serve-root slot, and arm a timeout. On `Completed` we reply
      * [[snap.SNAPSyncController.HealingServeRoot]] to the waiting child; if no peer height is known yet, reply None so
      * the child keeps its current serve root (U2). Mirrors `maybeStartRecentRootBootstrap` but on a separate slot.
      */
    private def maybeStartHealingServeRootBootstrap(
        peers: Map[
          com.chipprbots.ethereum.network.Peer,
          com.chipprbots.ethereum.network.NetworkPeerManagerActor.PeerInfo
        ]
    ): Unit =
      val snapHeights = peers.values.filter(_.remoteStatus.supportsSnap).map(_.maxBlockNumber)
      SyncController.recentRootTarget(snapHeights, RecentRootMarginBlocks) match
        case Some(recentBlock) =>
          healingServeRootGeneration += 1
          val gen = healingServeRootGeneration
          val peersClient = ctx.spawn(
            Behaviors
              .supervise(PeersClient.behavior(networkPeerManager, peerEventBus, blacklist, syncConfig))
              .onFailure[Throwable](
                // PR #1378: PeersClient is a sync backbone actor — no `.withMaxRestarts` cap.
                SupervisorStrategy.restartWithBackoff(500.millis, 10.seconds, 0.2)
              ),
            s"healing-serve-root-peers-$gen"
          )
          val bootstrap = ctx
            .spawn(
              Behaviors
                .supervise(
                  PivotHeaderBootstrap(
                    peersClient,
                    blockchainWriter,
                    recentBlock,
                    replyTo = pivotBootstrapAdapter,
                    syncConfig,
                    preferSnapPeers = true
                  )
                )
                // PivotHeaderBootstrap must keep retrying until the pivot header arrives — no cap.
                .onFailure[Throwable](SupervisorStrategy.restartWithBackoff(2.seconds, 60.seconds, 0.1)),
              s"healing-serve-root-bootstrap-$gen"
            )
          healingServeRootBootstrap = Some((peersClient, bootstrap))
          log.info(s"[HEAL-SERVE-ROOT] Fetching header for newest-servable block $recentBlock.")
          timers.startSingleTimer(HealingServeRootTimeout(gen), 20.seconds)
        case None =>
          log.info("[HEAL-SERVE-ROOT] No usable peer height yet; replying None (serve root kept).")
          healingServeRootRequester.foreach(_ ! SNAPSyncController.HealingServeRoot(0, None))
          healingServeRootRequester = None

    private def stopHealingServeRootBootstrap(): Unit =
      healingServeRootBootstrap.foreach { case (peersClient, bootstrap) =>
        ctx.stop(bootstrap)
        ctx.stop(peersClient)
      }
      healingServeRootBootstrap = None

    /** spec 004 (Decoupled Heal Serve-Root) MUST-FIX: abort any in-flight healing serve-root request before the parent
      * leaves `runningSnapSync` for `runningPivotHeaderBootstrap`. The healing handlers (`HandshakedPeers`,
      * `PivotHeaderBootstrap.Completed|Failed`, `HealingServeRootTimeout`) live ONLY in `runningSnapSync`; if a
      * concurrent pivot refresh transitions into `runningPivotHeaderBootstrap` with a healing bootstrap still in
      * flight, those messages would hit that state's catch-all, be forwarded to the child, and dead-letter — leaving
      * `healingServeRootRequester = Some(...)` here and the child's `healingServeRootRequestInFlight = true` forever,
      * silently freezing every future serve-root refresh (the pre-spec-004 deadlock). It also eliminates the ETH
      * by-hash hazard where a `targetBlock == 0` pivot bootstrap could mis-consume the healing `Completed` as its pivot
      * header. We reply `HealingServeRoot(0, None)`: the child clears its latch (SNAPSyncController.scala ~637) and
      * keeps its current serve root (U2), retrying on a later healing tick. Safe no-op when nothing is in flight.
      */
    private def abortHealingServeRootRequest(reason: String): Unit =
      if healingServeRootRequester.isDefined || healingServeRootBootstrap.isDefined then
        log.info(
          s"[HEAL-SERVE-ROOT] Aborting in-flight serve-root request ($reason) — replying None (serve root kept)."
        )
        stopHealingServeRootBootstrap()
        // A HealingServeRootTimeout self-message scheduled by maybeStartHealingServeRootBootstrap may still be in
        // flight, but it is generation-guarded (gen == healingServeRootGeneration && healingServeRootRequester.isDefined):
        // clearing the requester below — and the generation bump on the next request — makes any late timeout a no-op.
        healingServeRootRequester.foreach(_ ! SNAPSyncController.HealingServeRoot(0, None))
        healingServeRootRequester = None

    def runningRegularSync(regularSync: TypedActorRef[RegularSync.Command]): Behavior[Command] = Behaviors.receive {
      (_, cmd) =>
        handleRegularSyncMsg(regularSync, unwrap(cmd))
    }

    /** Shared message handler for the regular-sync states. Returns the next `Behavior[Command]`. Extracted so the
      * backfill variants can delegate to it after handling their own backfill-specific messages (former
      * `runningRegularSync(...) .apply(msg)` Classic partial-function delegation).
      */
    private def handleRegularSyncMsg(
        regularSync: TypedActorRef[RegularSync.Command],
        other: Any
    ): Behavior[Command] = // Any: unwrapped Classic msg
      other match
        case RegularSyncTerminated(actor) if actor == regularSync =>
          log.error("RegularSync actor terminated unexpectedly — restarting regular sync.")
          startRegularSync(resumeBackfill = false)._2
        case SyncProtocol.MissingCodeNeedsBulkRecovery(blockNumber, _)
            if !SyncController.bulkCodeRecoveryAllowed(
              appStateStorage.isSnapSyncDone(),
              appStateStorage.bulkBytecodeRecoveryFailures()
            ) =>
          // BlockImporter checks the same condition before asking, so this is a race or a stale request. Leave regular
          // sync running; the importer fetches the hash on its own.
          log.warn(
            "Ignoring a bulk bytecode recovery request for block {}: snapSyncDone={}, failed bulk recoveries={} " +
              "(cap {}). Regular sync keeps fetching the missing code one hash at a time.",
            blockNumber,
            appStateStorage.isSnapSyncDone(),
            appStateStorage.bulkBytecodeRecoveryFailures(),
            SyncController.MaxBulkBytecodeRecoveryFailures
          )
          Behaviors.same
        case SyncProtocol.MissingCodeNeedsBulkRecovery(blockNumber, codeHash) =>
          // Block import hit contract code this node never stored. Run the existing post-SNAP bytecode recovery now,
          // rather than at the next start: it walks the SNAP state trie, collects EVERY non-empty codeHash absent from
          // EvmCodeStorage, and fetches them in batched GetByteCodes (BytecodeRecoveryActor / CombinedRecoveryScan).
          //
          // Regular sync is stopped for the duration, not run alongside. Recovery registers its own SNAP response relay
          // with NetworkPeerManagerActor and downloads through its own coordinator; regular sync's StateNodeFetcher uses
          // PeersClient for the same GetByteCodes, and the two would compete for the same peers and responses. The node
          // cannot import past the missing code anyway, so nothing is lost by pausing.
          //
          // One scan at a time follows from the state machine: this controller is in `runningRecovery` until the scan
          // and downloads finish, and a repeat of this message there is dropped. BlockImporter also asks only once per
          // process.
          log.error(
            "Block {} needs contract code {} that is not stored. Pausing regular sync for a bulk bytecode recovery.",
            blockNumber,
            com.chipprbots.ethereum.utils.ByteStringUtils.hash2string(codeHash)
          )
          ctx.unwatch(regularSync)
          ctx.stop(regularSync)
          stopBackgroundAudit()
          // Un-done until the recovery finishes: if the node stops part-way, the next start resumes the scan. A checkpoint
          // a failed scan left goes: this scan is after code the best state needs, and `recoveryScanRoot` would keep that
          // scan's older root while it can be read, which may never have held this code.
          appStateStorage.clearBytecodeRecoveryDone().and(appStateStorage.clearRecoveryProgress()).commit()
          bulkCodeRecoveryTrigger = Some(codeHash)
          startRecovery(needBytecode = true, needStorage = false, scanRoot = recoveryScanRoot())
        case SyncProtocol.RegularSyncStuck(blockNumber, missingHash) if pendingResnap.isDefined =>
          // The importer keeps retrying while a re-SNAP waits, and reports each new run of exhausts: nothing new.
          log.info(
            "Regular sync still stuck on block {} (missing {}); the re-SNAP from block {} is still scheduled",
            blockNumber,
            missingHash,
            pendingResnap.getOrElse(blockNumber)
          )
          Behaviors.same
        case SyncProtocol.RegularSyncStuck(blockNumber, missingHash) =>
          // Regular sync can't make progress: state-node recovery has exhausted on the same hash
          // 3+ times. Local parent state is too far behind canonical tip for any peer's snap-serve
          // window, so trie-node fetches keep returning empty. The recovery is to re-run SNAP from a
          // recent pivot (resnapNow).
          //
          // H3: a stored node no fetch repairs sent regular sync here, and the SNAP that followed back to the same
          // node, with nothing to stop it. Count the re-SNAPs with no block imported in between (persisted with the
          // block imports are measured from, so a restart forgets neither). The first two go at once. From the third,
          // regular sync keeps running (on ETC, a node that stops would import nothing for hours) and the re-SNAP waits,
          // an hour doubling up to a day; a block imported meanwhile drops it. Not a stop: a peer-starved chain can be
          // stuck for reasons a later SNAP gets past.
          val best = blockchainReader.getBestBlockNumber
          val resnaps = nextResnapCount(best)
          val wait = SyncController.resnapBackoff(resnaps)
          RegularSyncMetrics.setConsecutiveResnaps(resnaps)
          if wait == Duration.Zero then
            log.error(
              "Regular sync stuck on block {} (missing {}). Re-triggering SNAP sync from a recent pivot.",
              blockNumber,
              missingHash
            )
            // The baseline goes until the first regular sync after this SNAP sets it: SNAP's pivot is no import.
            appStateStorage.putRegularSyncStuck(resnaps, baseline = None).commit()
            resnapNow(regularSync, blockNumber)
          else
            appStateStorage.putRegularSyncStuck(resnaps, baseline = Some(best)).commit()
            RegularSyncMetrics.incrementResnapBackoffs()
            pendingResnap = Some(blockNumber)
            timers.startSingleTimer(ResnapDueKey, ResnapDue(blockNumber), wait)
            timers.startTimerWithFixedDelay(ResnapCheckKey, ResnapCheck, SyncController.ResnapCheckInterval)
            log.error(
              "[SYNC-STUCK] Regular sync stuck on block {} (missing {}): SNAP re-sync {} in a row with no block imported " +
                "in between, so the same state keeps failing. Regular sync keeps running, and the re-SNAP is scheduled in " +
                "{} min (at {}); it is dropped if a block is imported first, and each one in a row waits twice as long, " +
                "up to {} h. A restart resumes regular sync and keeps this count; to re-run SNAP now, restart with " +
                "-Dfukuii.snap.clearDoneOnStart=true (a SNAP repair pass). regularsync.resnap.consecutive.gauge says how many.",
              blockNumber,
              missingHash,
              resnaps,
              wait.toMinutes,
              java.time.Instant.now().plusMillis(wait.toMillis).truncatedTo(java.time.temporal.ChronoUnit.MINUTES),
              SyncController.ResnapBackoffCap.toHours
            )
            Behaviors.same
        case ResnapCheck =>
          // While the count stands: a block imported since starts it over, and drops a re-SNAP that waits.
          if appStateStorage.regularSyncStuckResnaps() == 0 then timers.cancel(ResnapCheckKey)
          else resnapRecovered(): Unit
          Behaviors.same
        case ResnapDue(blockNumber) if pendingResnap.contains(blockNumber) =>
          pendingResnap = None
          if resnapRecovered() then Behaviors.same
          else
            log.error(
              "[SYNC-STUCK] No block imported in the wait: re-triggering SNAP sync from a recent pivot (block {})",
              blockNumber
            )
            appStateStorage.putRegularSyncStuck(appStateStorage.regularSyncStuckResnaps(), baseline = None).commit()
            resnapNow(regularSync, blockNumber)
        case ResnapDue(_) =>
          Behaviors.same
        case SyncProtocol.CalibrateChainWeightFromPeer(peerTD, peerMaxBlock) =>
          // #1654: first ask whether the best block's stored weight is already exact, or can be derived exactly from
          // its parent's. Fork choice compares stored weights, so rewriting an exact head with an estimate leaves a
          // jump that every fork branching below it is weighed across (see HeadChainWeight). Before this check, the
          // timed calibration did that on every restart (Mordor: about ±80M per restart).
          blockchainReader.getBestBlockHeader.map(h => h -> HeadChainWeight.classify(blockchainReader, h)) match
            case _ if appStateStorage.hasChainWeightInvariant() =>
              // #1654: every stored canonical weight is genesis plus the difficulties (the marker), so there is
              // nothing to calibrate, even where the plausibility floor cannot tell (a chain that was PoS from genesis).
              calibrationSucceeded = true
              lastCalibrationSource = "CHAIN_WEIGHT_INVARIANT"
              log.info(
                "TD_CALIBRATION_SKIPPED: reason=CHAIN_WEIGHT_INVARIANT (every stored canonical weight is exact from " +
                  "genesis); peer data td={} block={} not used",
                peerTD,
                peerMaxBlock
              )
            case Some((head, HeadChainWeight.Status.Exact(stored))) =>
              calibrationSucceeded = true
              lastCalibrationSource = "ALREADY_EXACT"
              log.info(
                "TD_CALIBRATION_SKIPPED: block={} storedTD={} reason=ALREADY_EXACT (stored = parent weight + " +
                  "difficulty); peer data td={} block={} not used",
                head.number,
                stored.totalDifficulty,
                peerTD,
                peerMaxBlock
              )
            case Some((head, HeadChainWeight.Status.Derivable(stored, exact))) =>
              if writeCalibratedWeight(head, exact) then
                calibrationSucceeded = true
                lastCalibrationSource = "DERIVED_FROM_PARENT"
                val before = stored.map(_.totalDifficulty.value.toString).getOrElse("none")
                log.warn(
                  "CHAIN_WEIGHT_REPAIRED: block={} before={} after={} source=DERIVED_FROM_PARENT (parent weight + " +
                    "difficulty; peer data td={} block={} not used)",
                  head.number,
                  before,
                  exact.totalDifficulty,
                  peerTD,
                  peerMaxBlock
                )
                log.info(
                  s"TD_CALIBRATION_SUMMARY: block=${head.number} before=$before after=${exact.totalDifficulty} " +
                    s"source=DERIVED_FROM_PARENT attempt=$tdCalibrationAttempt"
                )
            case Some((head, HeadChainWeight.Status.NoExact)) =>
              // #1654: on a chain that was PoS from genesis (Platåberget) the floor says NoExact at every height, yet
              // the exact weight is known: the genesis weight. No terminal TD on ETC/Mordor: the estimate, as before.
              HeadChainWeight.posFromGenesisWeight(
                configBuilder.blockchainConfig.terminalTotalDifficulty,
                blockchainReader
              ) match
                case Some(exact) => calibrateToGenesisWeight(head, exact, peerTD, peerMaxBlock)
                case None        => calibrateChainWeightEstimate(peerTD, peerMaxBlock)
            case _ => calibrateChainWeightEstimate(peerTD, peerMaxBlock)
          Behaviors.same

        case msg if isInternalMarker(msg) =>
          // Late self/death-watch marker for a child stopped before this transition — drop silently.
          Behaviors.same
        case bh: ForkChoiceManager.BeaconHead =>
          // PoS/post-merge only: the CL advanced its canonical head. Buffer the latest beacon head
          // (as every other sync state does) and forward to RegularSync so it can announce the new
          // head to already-connected eth peers (fixes Hive "fukuii as sync server" run/sync bug).
          // On PoW chains (ETC/Mordor) clPivotEnabled=false so BeaconHead is never published and
          // this arm is dead code — there is ZERO PoW/ETC regression risk.
          //
          // Compute novelty BEFORE handleBeaconHead mutates latestBeaconHead: the CL re-sends FCU
          // every slot (~12s) even when the head is unchanged. Only announce on an actual head
          // advance — re-announcing the same head every slot is wasted peer traffic, and peers that
          // connect after the advance already learn the head from STATUS (BestBlockNumber is advanced
          // by the FCU's saveBestKnownBlocks). The push is solely for peers connected at the moment
          // the head changes.
          val isNewBeaconHead = !latestBeaconHead.exists(_.headHash == bh.headHash)
          handleBeaconHead(bh, snapSyncOpt = None)
          if isNewBeaconHead then regularSync ! SyncProtocol.NewCanonicalHead(bh.headHash, bh.knownHeader)
          // The CL named a head whose ancestry we lack. Regular sync only asks peers that have ADVERTISED a height, and
          // a peer that handshook at genesis may never re-advertise (go-ethereum: every 32 blocks), so ask peers for
          // the missing block by hash. Sent on every FCU until the gap closes — the CL re-sends FCU, and a peer that
          // lacked the block last time may have it now. A node keeping up sends nothing: its heads are executed.
          if clPivotEnabled then
            MissingAncestorProbe.target(bh, blockchainReader).foreach { t =>
              networkPeerManager ! com.chipprbots.ethereum.network.NetworkPeerManagerActor
                .ProbeMissingAncestorCmd(t.hash, t.number)
            }
          Behaviors.same
        case msg: SyncProtocol.RegularSyncCommand =>
          // GetStatus (JSON-RPC eth_syncing), MinedBlock (miner), and other RegularSyncCommand subtypes
          // arrive here. RegularSync.Command = SyncProtocol.RegularSyncCommand so this is a typed send.
          regularSync ! msg
          Behaviors.same
        case other =>
          log.warn("Unexpected message in handleRegularSyncMsg: {}", other.getClass.getSimpleName)
          Behaviors.same

    /** Receive used between `SnapSyncFinalized` and `Done` from the lingering SNAPSyncController.
      *
      * Regular sync is the primary owner of peer slots; SNAP backfill runs at low priority in the background. This
      * Receive lets `SNAPSyncController.Done` arrive (so we can poison-pill the SNAP actor) and intercepts restart
      * paths so the lingering backfill actor is cleaned up before a new sync mode takes over. Everything else is
      * delegated to `runningRegularSync(regularSync)`.
      */
    def runningRegularSyncWithBackfill(
        regularSync: TypedActorRef[RegularSync.Command],
        snapSync: TypedActorRef[SNAPSyncController.Command]
    ): Behavior[Command] =
      Behaviors.receive { (_, cmd) =>
        val msg = unwrap(cmd)
        msg match
          case com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.Done =>
            log.info("SNAP background backfill complete; shutting down SNAPSyncController.")
            ctx.unwatch(snapSync)
            ctx.stop(snapSync)
            runningRegularSync(regularSync)

          case SnapSyncTerminated(actor) if actor == snapSync =>
            log.warn("SNAPSyncController died while regular sync was running; chain backfill aborted.")
            runningRegularSync(regularSync)

          case RegularSyncTerminated(actor) if actor == regularSync =>
            log.error("RegularSync actor terminated unexpectedly during backfill — restarting regular sync.")
            ctx.unwatch(snapSync)
            ctx.stop(snapSync)
            startRegularSync(resumeBackfill = false)._2

          case m if isRestartTrigger(m) =>
            log.info("Restart triggered while SNAP backfill was running; poison-pilling SNAP backfill actor first.")
            ctx.unwatch(snapSync)
            ctx.stop(snapSync)
            ctx.self ! cmd // Re-deliver the original Command so the new state handles it.
            runningRegularSync(regularSync)

          case m =>
            handleRegularSyncMsg(regularSync, m)
      }

    /** Restart-style messages that mean the current sync strategy is being abandoned. Used by
      * `runningRegularSyncWithBackfill` to detect when it must terminate the lingering backfill actor before
      * delegating.
      */
    private def isRestartTrigger(msg: Any): Boolean = msg match // Any: Classic msg from adapter
      // H3: only a stuck that re-SNAPs now, or a re-SNAP whose wait ended with nothing imported; one that waits keeps
      // regular sync, and its backfill, running.
      case _: SyncProtocol.RegularSyncStuck =>
        pendingResnap.isEmpty &&
        SyncController.resnapBackoff(nextResnapCount(blockchainReader.getBestBlockNumber)) == Duration.Zero
      case ResnapDue(blockNumber) =>
        pendingResnap.contains(blockNumber) && !importedSinceBaseline(blockchainReader.getBestBlockNumber)
      case _: SyncProtocol.MissingCodeNeedsBulkRecovery => true
      case _                                            => false

    /** H3: whether a block was imported since the re-SNAP count's baseline. No baseline (a re-SNAP runs) is no import.
      */
    private def importedSinceBaseline(best: BigInt): Boolean = appStateStorage.resnapBaseline().exists(best > _)

    /** H3: what the count becomes at a `RegularSyncStuck` now: one more, or one again after a block was imported. */
    private def nextResnapCount(best: BigInt): Int =
      (if importedSinceBaseline(best) then 0 else appStateStorage.regularSyncStuckResnaps()) + 1

    /** H3: a block was imported since the baseline: the count starts over, a re-SNAP that waits is dropped, and the
      * gauge goes to 0, so an alert on it stops. Returns whether it did.
      */
    private def resnapRecovered(): Boolean =
      val best = blockchainReader.getBestBlockNumber
      if appStateStorage.regularSyncStuckResnaps() > 0 && importedSinceBaseline(best) then
        appStateStorage.putRegularSyncStuck(0, baseline = Some(best)).commit()
        RegularSyncMetrics.setConsecutiveResnaps(0)
        timers.cancel(ResnapCheckKey)
        timers.cancel(ResnapDueKey)
        log.warn(
          "[SYNC-STUCK] A block was imported (best {}): the re-SNAP count starts over{}",
          best,
          pendingResnap.fold("")(b => s", and the re-SNAP scheduled from block $b is dropped")
        )
        pendingResnap = None
        true
      else false

    /** Re-run SNAP from a recent pivot above `blockNumber`: stop regular sync, clear both SnapSyncDone AND the legacy
      * FastSyncDone (so a restart re-evaluates start() and enters SNAP rather than going straight back to regular
      * sync), then start SNAP.
      */
    private def resnapNow(regularSync: TypedActorRef[RegularSync.Command], blockNumber: BigInt): Behavior[Command] =
      pendingResnap = None
      timers.cancel(ResnapDueKey)
      timers.cancel(ResnapCheckKey)
      ctx.stop(regularSync)
      stopBackgroundAudit()
      appStateStorage.clearSnapSyncDone().commitSync() // spec 022 D7: with the invariant marker
      appStateStorage.clearFastSyncDone().commit()
      startSnapSync(minPivotBlock = Some(blockNumber))

    /** Internal `Behavior[Command]` self / death-watch markers that must NEVER be forwarded to a Classic child. A
      * watched child can terminate after the parent has already transitioned to a state that does not handle its marker
      * (e.g. `RegularSyncStuck` poison-pills regularSync and enters `runningSnapSync`); the late
      * `RegularSyncTerminated` then lands in `runningSnapSync`'s catch-all. Without this guard it would be
      * `tell`-forwarded to the SNAP child and crash it with a ClassCastException. Every forwarding catch-all drops
      * these silently.
      */
    private def isInternalMarker(msg: Any): Boolean = msg match // Any: Classic msg from adapter
      case _: SnapSyncTerminated         => true
      case _: RegularSyncTerminated      => true
      case _: ResumerTerminated          => true
      case _: BytecodeRecoveryTerminated => true
      case _: StorageRecoveryTerminated  => true
      case PollRecoveryPeers             => true
      case _: RecentRootTimeout          => true
      case _: HealingServeRootTimeout    => true
      case _: ResnapDue                  => true
      case ResnapCheck                   => true
      case _                             => false

    def runningPivotHeaderBootstrap(
        peersClient: TypedActorRef[PeersClient.Command],
        headerBootstrap: TypedActorRef[PivotHeaderBootstrap.Command],
        targetBlock: BigInt,
        originalSnapSyncRef: TypedActorRef[SNAPSyncController.Command]
    ): Behavior[Command] = Behaviors.receive { (_, cmd) =>
      val msg = unwrap(cmd)
      msg match

        case SnapSyncCriticalFailure =>
          // §7c-D4: STOP-AND-ALERT — the active SNAP controller crashed while a pivot header bootstrap was in flight.
          // Stop the sync tree and alert; restart is unsafe with corrupt SNAP session state.
          log.error("CRITICAL actor stopped unexpectedly — node restart required: {}", "snap-sync")
          Behaviors.stopped

        case PivotHeaderBootstrap.Completed(block, header) if block == targetBlock || targetBlock == 0 =>
          // `targetBlock == 0` is the sentinel for by-hash bootstrap (#1207): the actual
          // block number is unknown at request time and resolved from the returned header.
          log.info(
            s"Pivot header bootstrap complete for block ${header.number} (requested $targetBlock) - notifying SNAP sync"
          )
          ctx.stop(headerBootstrap)
          ctx.stop(peersClient)
          originalSnapSyncRef ! BootstrapComplete(Some(header))
          runningSnapSync(originalSnapSyncRef)

        case PivotHeaderBootstrap.Failed(reason) =>
          log.warn(s"Pivot header bootstrap failed (reason: $reason). Notifying SNAP sync controller.")
          ctx.stop(headerBootstrap)
          ctx.stop(peersClient)
          originalSnapSyncRef ! PivotBootstrapFailed(reason)
          runningSnapSync(originalSnapSyncRef)

        case msg: SyncProtocol.GetStatus =>
          // Expose progress as a generic syncing state.
          msg.replyTo ! SyncProtocol.Status.Syncing(
            startingBlockNumber = appStateStorage.getSyncStartingBlock(),
            blocksProgress = SyncProtocol.Status.Progress(appStateStorage.getBestBlockNumber(), targetBlock),
            stateNodesProgress = None
          )
          Behaviors.same

        case StartRegularSyncBootstrap(newTargetBlock) =>
          // A new bootstrap request arrived while one is already in progress.
          // Stop stale bootstrap actors and start fresh ones.
          log.info(
            s"New pivot header bootstrap requested for block $newTargetBlock (was $targetBlock). Restarting bootstrap."
          )
          ctx.stop(headerBootstrap)
          ctx.stop(peersClient)
          bootstrapGeneration += 1
          val gen = bootstrapGeneration
          val newPeersClient =
            ctx.spawn(
              Behaviors
                .supervise(PeersClient.behavior(networkPeerManager, peerEventBus, blacklist, syncConfig))
                .onFailure[Throwable](
                  // PR #1378: PeersClient is a sync backbone actor — no `.withMaxRestarts` cap.
                  SupervisorStrategy.restartWithBackoff(500.millis, 10.seconds, 0.2)
                ),
              s"peers-client-bootstrap-$gen"
            )
          val newHeaderBootstrap =
            ctx
              .spawn(
                Behaviors
                  .supervise(
                    PivotHeaderBootstrap(
                      newPeersClient,
                      blockchainWriter,
                      newTargetBlock,
                      replyTo = pivotBootstrapAdapter,
                      syncConfig,
                      preferSnapPeers = true
                    )
                  )
                  // PivotHeaderBootstrap must keep retrying until the pivot header arrives — no cap.
                  .onFailure[Throwable](SupervisorStrategy.restartWithBackoff(2.seconds, 60.seconds, 0.1)),
                s"pivot-header-bootstrap-$gen"
              )
          runningPivotHeaderBootstrap(newPeersClient, newHeaderBootstrap, newTargetBlock, originalSnapSyncRef)

        case com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.SnapSyncFinalized(pivot) =>
          log.info(
            s"Received SnapSyncFinalized(pivot=$pivot) during pivot header bootstrap. Stopping bootstrap and transitioning to regular sync."
          )
          clearSnapPivotFloor()
          markChainWeightsAfterSnap(pivot)
          ctx.stop(headerBootstrap)
          ctx.stop(peersClient)
          // SNAP finalised mid-bootstrap is an exceptional path; tear down the SNAP actor cleanly
          // (no chain-backfill watch, since the bootstrap state is already racy).
          ctx.stop(originalSnapSyncRef)
          startRegularSync()._2

        case com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.Done =>
          log.info(
            "Received Done from SNAP sync during pivot header bootstrap. Stopping bootstrap and transitioning to regular sync."
          )
          ctx.stop(headerBootstrap)
          ctx.stop(peersClient)
          ctx.stop(originalSnapSyncRef)
          startRegularSync()._2

        case bh: ForkChoiceManager.BeaconHead =>
          handleBeaconHead(bh, snapSyncOpt = Some(originalSnapSyncRef))
          Behaviors.same

        // spec 004 T012: a healing serve-root request that lands during the brief pivot-header bootstrap window
        // (a concurrent pivot refresh) is declined immediately so the child's in-flight latch clears and it can
        // retry on a later healing tick. We never start a second bootstrap here (the pivot bootstrap is already
        // using the slot). U2: declining keeps the child's current serve root.
        case SNAPSyncController.RequestHealingServeRoot =>
          log.debug("[HEAL-SERVE-ROOT] Request arrived during pivot header bootstrap — declining (serve root kept).")
          // Phase 2 (ROOT-b): reply to the known SNAP child ref (the request's origin) rather than `sender()`.
          originalSnapSyncRef ! SNAPSyncController.HealingServeRoot(0, None)
          Behaviors.same

        // SSC requested a hash-based bootstrap while one is already running — restart with new CL hash.
        case StartRegularSyncBootstrapByHash(headHash) =>
          log.info(
            "SNAP requested by-hash pivot header bootstrap during active bootstrap ({}), restarting.",
            com.chipprbots.ethereum.utils.ByteStringUtils.hash2string(headHash)
          )
          ctx.stop(headerBootstrap)
          ctx.stop(peersClient)
          bootstrapGeneration += 1
          val gen = bootstrapGeneration
          val newPeersClient =
            ctx.spawn(
              Behaviors
                .supervise(PeersClient.behavior(networkPeerManager, peerEventBus, blacklist, syncConfig))
                .onFailure[Throwable](
                  // PR #1378: PeersClient is a sync backbone actor — no `.withMaxRestarts` cap.
                  SupervisorStrategy.restartWithBackoff(500.millis, 10.seconds, 0.2)
                ),
              s"peers-client-bootstrap-$gen"
            )
          val newHeaderBootstrap =
            ctx.spawn(
              Behaviors
                .supervise(
                  PivotHeaderBootstrap.applyByHash(
                    newPeersClient,
                    blockchainWriter,
                    headHash,
                    replyTo = pivotBootstrapAdapter,
                    syncConfig,
                    preferSnapPeers = false
                  )
                )
                // PivotHeaderBootstrap must keep retrying until the pivot header arrives — no cap.
                .onFailure[Throwable](SupervisorStrategy.restartWithBackoff(2.seconds, 60.seconds, 0.1)),
              s"pivot-header-bootstrap-$gen"
            )
          runningPivotHeaderBootstrap(newPeersClient, newHeaderBootstrap, targetBlock = BigInt(0), originalSnapSyncRef)

        // Stale completion from a previous bootstrap generation (block doesn't match current targetBlock).
        case PivotHeaderBootstrap.Completed(block, _) =>
          log.debug(
            "Stale PivotHeaderBootstrap.Completed for block {} in runningPivotHeaderBootstrap (expected {}), dropping.",
            block,
            targetBlock
          )
          Behaviors.same

        // SSC declared healing impossible during the pivot header bootstrap window — stale, drop.
        case SyncProtocol.HealingImpossible =>
          log.debug("HealingImpossible in runningPivotHeaderBootstrap — stale, dropping (bootstrap in progress).")
          Behaviors.same

        // Stale HandshakedPeers reply from a GetHandshakedPeersCmd sent before this state transition.
        // SSC polls NPMA directly (OQ-3) so these are not forwarded.
        case _: com.chipprbots.ethereum.network.NetworkPeerManagerActor.HandshakedPeers =>
          Behaviors.same

        // Stale chain-weight calibration push — no calibration during pivot header bootstrap.
        case _: SyncProtocol.CalibrateChainWeightFromPeer =>
          Behaviors.same

        case msg if isInternalMarker(msg) =>
          // Late self/death-watch marker for a child stopped before this transition — drop silently.
          Behaviors.same
        case other =>
          log.warn("Unexpected message in runningPivotHeaderBootstrap: {}", other.getClass.getSimpleName)
          Behaviors.same
    }

    /** Buffer the latest CL-driven head and, when SNAP is currently running, forward it as a `CLPivotHint` so the pivot
      * can be re-anchored on the freshest CL head. Called from the receive handler in every state where a `BeaconHead`
      * can arrive. No-op on chains without TTD or where `forkChoiceManagerOpt` is `None`.
      */
    private def handleBeaconHead(
        bh: ForkChoiceManager.BeaconHead,
        snapSyncOpt: Option[TypedActorRef[SNAPSyncController.Command]]
    ): Unit =
      if clPivotEnabled then
        val isNew = !latestBeaconHead.exists(_.headHash == bh.headHash)
        latestBeaconHead = Some(bh)
        if isNew then
          log.info(
            "Received CL-driven beacon head {} (knownHeader={})",
            com.chipprbots.ethereum.utils.ByteStringUtils.hash2string(bh.headHash),
            bh.knownHeader.map(_.number).getOrElse("unknown")
          )
        snapSyncOpt.foreach { snapSync =>
          snapSync ! com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.CLPivotHint(
            bh.headHash,
            bh.knownHeader
          )
        }

    /** Fast sync's leftover progress record, handled once and then deleted.
      *
      * Fast sync advanced the best-block pointer through blocks it downloaded without executing them, so a node
      * upgraded mid-fast-sync has a best block with no state behind it. SNAP would take that block for a synced chain
      * (its pivot is not ahead of it) and hand the node to regular sync. Such a node gets a pivot floor of best + 1,
      * persisted in SNAP's state so a restart before SNAP commits a pivot keeps it.
      *
      * The record also sits on databases where SNAP once fell back to fast sync and later took over again. There SNAP
      * moved the best block, and a floor above SNAP's saved pivot would trip its `belowEscalationHint` and discard its
      * download. So the floor is set only when nothing says SNAP owns the best block: neither SNAP nor fast sync is
      * done, SNAP's accounts are not complete (a healing pivot roll moves best without saving the pivot, and healing
      * implies all data phases complete), and best is not SNAP's saved pivot (`updateBestBlockForPivot` sets best =
      * pivot).
      *
      * The record is deleted either way, in the same batch, so this runs at most once per database and no later start
      * can mistake a SNAP-moved best block for a stranded one. It runs before anything else in start() touches the done
      * flags (dangling-best recovery, `clearDoneOnStart`), so it sees them as persisted. Returns the floor it wrote, if
      * any: only the one start that finds the stranded node writes one.
      */
    private def adoptLeftoverFastSyncRecord(): Option[BigInt] =
      if !fastSyncStateStorage.hasSyncState then None
      else
        val best = appStateStorage.getBestBlockNumber()
        val stranded =
          best > 0 && !appStateStorage.isSnapSyncDone() && !appStateStorage.isFastSyncDone() &&
            !snapHasStake()
        val floor = Option.when(stranded)(best + 1)
        val floorUpdate = floor match
          case Some(f) =>
            log.warn(
              "Fast sync was running when this node was upgraded; fast sync was removed. Its best block {} has no " +
                "state behind it, so SNAP may not take a pivot below {}. Deleting fast sync's leftover progress record.",
              best,
              f
            )
            appStateStorage.putSnapSyncMinPivotBlock(f)
          case None =>
            log.info("Deleting fast sync's leftover progress record: SNAP or a finished sync owns this database")
            appStateStorage.emptyBatchUpdate
        floorUpdate.and(fastSyncStateStorage.removeSyncState()).commit()
        floor

    /** SNAP's persisted pivot floor, unless it is spent.
      *
      * The floor is spent once SNAP's saved pivot reaches it, or once SNAP's accounts are complete. The floor is only
      * ever written before accounts complete, and a failed pivot refresh backtracks by the pivot offset, so SNAP can
      * commit (and finish its accounts at) a pivot below a floor that was its first pivot. A spent floor is cleared,
      * not applied again: a restart mid-heal must not raise the bar above the pivot SNAP already holds, where
      * `belowEscalationHint` would discard its download.
      */
    private def liveSnapPivotFloor(): Option[BigInt] =
      appStateStorage.getSnapSyncMinPivotBlock().flatMap { floor =>
        if appStateStorage.isSnapSyncAccountsComplete() || appStateStorage.getSnapSyncPivotBlock().exists(_ >= floor)
        then
          appStateStorage.clearSnapSyncMinPivotBlock().commit()
          None
        else Some(floor)
      }

    /** spec 022 D6: the one-time clear of CF `g`, whatever this start goes on to do, since a node past SNAP holds the
      * old records too. A failure costs disk space, and the next start tries again. On an upgraded datadir the space
      * stays taken until something compacts the family (see `HealingFrontierStorage.clearAll`).
      */
    private def retireHealingFrontier(): Unit =
      val frontier = new HealingFrontierStorage(flatSlotStorage.dataSource)
      try
        if HealInvariant.retireFrontierOnce(appStateStorage, frontier) then
          log.info("[HEAL-INVARIANT] Persisted healing frontier (CF g) cleared")
      catch
        case NonFatal(e) =>
          log.error(
            "[HEAL-INVARIANT] Could not clear the persisted healing frontier (CF g); the next start tries again",
            e
          )

    /** Whether the chain here came from a fast sync that finished, with nothing of SNAP's at stake.
      *
      * Such a node goes to regular sync, as it did before fast sync was removed. Starting SNAP there would leave it
      * dormant, importing nothing, while no snap peer is reachable, and a node more than 64 blocks behind would
      * download the whole state again. Regular sync does not need the trie to be complete: a missing node throws, nodes
      * fetched on demand are checked against their keccak256 hash, and repeated failures become RegularSyncStuck, which
      * starts SNAP with a pivot floor. An incomplete trie costs liveness, never a wrong root, so state completeness is
      * not tested here.
      *
      * SNAP has a stake when its saved pivot is the best block (every pivot commit sets best = pivot) or its accounts
      * are complete (healing implies that, and a healing pivot roll moves best without saving the pivot). A node
      * part-way through SNAP therefore resumes SNAP, whatever FastSyncDone says.
      */
    private def fastSyncFinishedWithoutSnapStake(): Boolean =
      appStateStorage.isFastSyncDone() && !snapHasStake()

    /** SNAP holds part of this node's state: its saved pivot is the best block, or its accounts are complete (#1434).
      *
      * `SnapSyncDone` is the only guard against a stale stake: a finished node keeps AccountsComplete = true (and its
      * saved pivot) for good, so every caller must test `isSnapSyncDone()` first, as `start()` does.
      *
      * The same test serves both startup branches: with do-snap-sync off, a restart mid-SNAP must not hand the partial
      * trie to regular sync either.
      */
    private def snapHasStake(): Boolean =
      appStateStorage.isSnapSyncAccountsComplete() ||
        appStateStorage.getSnapSyncPivotBlock().contains(appStateStorage.getBestBlockNumber())

    /** SNAP has finalized: whatever pivot floor it started with is spent. */
    private def clearSnapPivotFloor(): Unit =
      appStateStorage.clearSnapSyncMinPivotBlock().commit()
      snapPivotFailedFinalization = None // #1555: a finalized SNAP has no failed pivot left to avoid

    /** Log-only: compare the stored SNAP state root with the SNAP pivot header and report whether the trie is there. */
    private def logSnapStateRootDiagnostic(): Unit =
      def short(b: ByteString): String = b.take(8).toArray.map("%02x".format(_)).mkString
      val snapStateRoot = appStateStorage.getSnapSyncStateRoot()
      val bestBlockNum = appStateStorage.getBestBlockNumber()
      val mptStorage = stateStorage.getReadOnlyStorage
      def rootExists(root: ByteString): Boolean =
        try
          mptStorage.get(root.toArray); true
        catch case _: Exception => false
      blockchainReader.getSnapSyncPivotBlock.flatMap(blockchainReader.getBlockHeaderByNumber) match
        case None =>
          log.info(
            "SNAP state root diagnostic: stored snapStateRoot={}, no SNAP pivot header stored, bestBlock={}",
            snapStateRoot.map(short).getOrElse("none"),
            bestBlockNum
          )
        case Some(pivot) =>
          val rootsMatch = snapStateRoot.contains(pivot.stateRoot.value)
          log.info(
            "SNAP state root diagnostic: stored snapStateRoot={}, pivotBlock={} pivotStateRoot={}, bestBlock={}, match={}",
            snapStateRoot.map(short).getOrElse("none"),
            pivot.number.value,
            short(pivot.stateRoot.value),
            bestBlockNum,
            rootsMatch
          )
          // A difference is only worth reporting while the best block IS the pivot: above it, the best block's own
          // root is not expected to equal snapStateRoot, and the pivot header is never the one executed against.
          if !rootsMatch && bestBlockNum == pivot.number.value then
            val finalized = appStateStorage.getSnapSyncFinalizedRoot()
            log.warn(
              "snapStateRoot({}) differs from the pivot header's stateRoot({}); header left untouched. " +
                "pivotRoot={}, snapRoot={}, finalizedRoot={}. Regular sync fetches any missing state on demand.",
              snapStateRoot.map(short).getOrElse("none"),
              short(pivot.stateRoot.value),
              if rootExists(pivot.stateRoot.value) then "EXISTS" else "MISSING",
              snapStateRoot.map(r => if rootExists(r) then "EXISTS" else "MISSING").getOrElse("none"),
              finalized.map(r => short(r) + (if rootExists(r) then ":EXISTS" else ":MISSING")).getOrElse("none")
            )

    def start(): Behavior[Command] =
      val startMode = SyncController.selectSyncMode(syncConfig)

      val floorSetThisStart = adoptLeftoverFastSyncRecord()

      // Fast sync was removed, and with it this one-shot override (it cleared FastSyncDone so fast sync would run
      // again). Say so rather than ignore it silently.
      if System.getProperty("fukuii.reset-fast-sync-done", "false").equalsIgnoreCase("true") then
        log.warn("System property fukuii.reset-fast-sync-done is ignored: fast sync was removed")

      // Dangling-best-block recovery. If the persisted best-block hash points to a block that
      // isn't actually in storage, the previous sync was interrupted before the canonical tip
      // could be written (e.g. mid-SNAP container restart while account download was incomplete).
      // Without this, a set done flag sends start() to regular sync, which loops on
      // `Best block ... not found in storage` indefinitely.
      //
      // Recovery: clear ONLY the *SyncDone flags. SNAPSyncController persists its own progress
      // (snapSyncProgress / snapSyncStateRoot / snapSyncFinalizedRoot) and will resume the
      // partial download from where it left off — DO NOT reset bestBlockNumber, that would throw
      // away potentially many hours of completed account/storage/bytecode work and force a
      // genesis re-sync. Trie nodes are content-addressed, so leftover state from the prior run
      // is automatically reused as SNAP fills in the gaps.
      val persistedBest = appStateStorage.getBestBlockNumber()
      if persistedBest > 0 && blockchainReader.getBlockHeaderByNumber(persistedBest).isEmpty then
        log.warn(
          "Persisted best block {} not found in storage — clearing sync-done flags so SNAP can resume from persisted progress",
          persistedBest
        )
        appStateStorage.clearSnapSyncDone().commitSync() // spec 022 D7: with the invariant marker
        appStateStorage.clearFastSyncDone().commit()

      appStateStorage.putSyncStartingBlock(appStateStorage.getBestBlockNumber()).commit()
      retireHealingFrontier()

      // Load bootstrap checkpoints if enabled and DB is fresh (best block = 0).
      // The highest checkpoint becomes the bootstrap pivot — SNAPSyncController uses it
      // for peer filtering and pivot selection, bypassing the peer discovery delay.
      if syncConfig.useBootstrapCheckpoints && appStateStorage.getBestBlockNumber() == 0 then
        val checkpoints = syncConfig.bootstrapCheckpoints
        if checkpoints.nonEmpty then
          val (highestBlock, highestHash) = checkpoints.maxBy(_._1)
          val existingPivot = appStateStorage.getBootstrapPivotBlock()
          if existingPivot == 0 || highestBlock > existingPivot then
            import org.apache.pekko.util.ByteString
            val hashBytes = ByteString(com.chipprbots.ethereum.utils.Hex.decode(highestHash.stripPrefix("0x")))
            appStateStorage.putBootstrapPivotBlock(highestBlock, hashBytes).commit()
            log.info(
              s"Loaded bootstrap checkpoint: block $highestBlock (${highestHash.take(10)}...) " +
                s"from ${checkpoints.size} configured checkpoints"
            )
          else log.info(s"Bootstrap checkpoint already loaded (block $existingPivot), skipping")

      // Checkpoint sync: bootstrap a fresh datadir by importing a `.checkpoint` archive instead
      // of running SNAP. Only fires when DB is fresh (best-block == 0 and SNAP not already done).
      // Resolution order:
      //   1. `checkpoint-sync-file` if set — use the local path directly.
      //   2. else `checkpoint-sync-url` if set — download to `${datadir}/checkpoint.bin`
      //      (resumable via HTTP Range) and import.
      // On success the importer marks SNAP/bytecode/storage as done; the match below routes to
      // RegularSync. On failure we log and fall through to the normal SNAP/Regular path.
      if appStateStorage.getBestBlockNumber() == 0 && !appStateStorage.isSnapSyncDone() then
        val fileOpt: Option[java.nio.file.Path] = syncConfig.checkpointSyncFile.orElse {
          syncConfig.checkpointSyncUrl.flatMap { url =>
            // INFO-5: normalize + validate to prevent path-traversal from a crafted system-property value.
            val rawDatadir = java.nio.file.Paths.get(System.getProperty("fukuii.datadir", "."))
            val datadir = rawDatadir.normalize().toAbsolutePath()
            val target = datadir.resolve("checkpoint.bin").normalize().toAbsolutePath()
            if !target.startsWith(datadir) then
              log.warn(
                "[CHECKPOINT DOWNLOAD] Resolved path {} escapes datadir {} — skipping checkpoint download",
                target,
                datadir
              )
              None
            else
              log.info("[CHECKPOINT DOWNLOAD] {} -> {}", url, target)
              val downloader = new com.chipprbots.ethereum.blockchain.checkpoint.CheckpointDownloader()
              downloader.download(url, target) match
                case Right(_) => Some(target)
                case Left(err) =>
                  log.error("[CHECKPOINT DOWNLOAD] failed: {} — falling through to SNAP/Regular", err)
                  None
          }
        }
        fileOpt.foreach { path =>
          val chainIdBig = configBuilder.blockchainConfig.chainId.value
          log.info("[CHECKPOINT IMPORT] starting from {} (chainId={})", path, chainIdBig)
          val importer = new com.chipprbots.ethereum.blockchain.checkpoint.CheckpointImporter(
            blockchainWriter,
            stateStorage,
            evmCodeStorage,
            appStateStorage
          )
          importer.importFromFile(path, Some(chainIdBig.toLong)) match
            case Right(result) =>
              log.info(
                "[CHECKPOINT IMPORT] success: block={} nodes={} bytecodes={} elapsed={}s",
                result.blockNumber,
                result.nodesImported,
                result.bytecodesImported,
                result.elapsedMs / 1000
              )
            case Left(err) =>
              log.error("[CHECKPOINT IMPORT] failed: {} — falling through to SNAP/Regular", err)
        }
      else if syncConfig.checkpointSyncFile.isDefined || syncConfig.checkpointSyncUrl.isDefined then
        log.info(
          "Checkpoint sync configured but DB already initialized (bestBlock={}, snapDone={}); skipping import",
          appStateStorage.getBestBlockNumber(),
          appStateStorage.isSnapSyncDone()
        )

      // Recovery flag: -Dfukuii.snap.clearDoneOnStart=true clears SnapSyncDone to re-enter healing.
      // Use when healing completed prematurely (BUG-006: root mismatch) to resume without a full re-sync.
      // spec 022 D7: this also takes the invariant marker, done or not, so the next SNAP run is a repair pass.
      if syncConfig.doSnapSync && System.getProperty("fukuii.snap.clearDoneOnStart", "false").toBoolean then
        if HealInvariant.clearDoneOnStart(appStateStorage) then
          log.warn("fukuii.snap.clearDoneOnStart=true: clearing SnapSyncDone to re-enter SNAP healing")

      // #1692: pruning on a store SNAP or a checkpoint filled could delete a trie node another position still used, and
      // a clean audit pass finished before this version says nothing of a hole that came after it. One more full pass
      // per datadir, once, before the audit's gate is read. Its repairs pin the count, so they are never pruned.
      if appStateStorage.forceAuditPassOnce() then
        log.info(
          "[HEAL-AUDIT] One more full audit pass is owed on this datadir (#1692): a trie node pruning deleted by a " +
            "count that was too low is fetched back and repaired. It runs off the critical path."
        )

      // FastSyncDone is a legacy flag; nothing sets it any more, and the recovery paths below still clear it. With SNAP
      // on and not done, it sends the node to regular sync only when SNAP has no stake here (see
      // `fastSyncFinishedWithoutSnapStake`). Starting SNAP never cleared the flag, so a node part-way through SNAP can
      // carry it, and that node must resume SNAP.
      (appStateStorage.isSnapSyncDone(), startMode) match
        case (false, SyncMode.Snap) if fastSyncFinishedWithoutSnapStake() =>
          log.info(
            "Fast sync finished on this node earlier and SNAP has no progress here; continuing with regular sync"
          )
          startRegularSync()._2
        case (false, SyncMode.Snap) =>
          // A live floor means the best block came from an interrupted fast sync and has no state: SNAP must pick a
          // pivot above it, as after RegularSyncStuck, or its "pivot not ahead of local state" check takes that block
          // for a synced chain and hands the node to regular sync without state.
          val floor = liveSnapPivotFloor()
          floor.foreach(f => log.info("SNAP starts with a pivot floor of {} (left by an interrupted fast sync)", f))
          startSnapSync(minPivotBlock = floor)
        case (true, SyncMode.Snap) =>
          log.warn("do-snap-sync is true but SNAP sync already completed")
          // Diagnostic only. Nothing here may write a block header: a header's hash covers its stateRoot, so rewriting
          // it forges a block (new hash, no body) and, because storeBlockHeader also writes number->hash, repoints the
          // canonical index at it. An earlier version did that to the BEST block and broke every node that had
          // imported past its pivot. The comparison is against the SNAP pivot header, not the best block: once regular
          // sync has imported blocks above the pivot, the best block's root legitimately differs from snapStateRoot.
          logSnapStateRootDiagnostic()
          val needBytecode = !appStateStorage.isBytecodeRecoveryDone()
          val needStorage = !appStateStorage.isStorageRecoveryDone()
          if !(needBytecode || needStorage) then startRegularSync()._2
          else if auditGate == BackgroundAudit.Gate.Run then
            // spec 022 decision 14: these flags say a pass ran, and this arm is their only reader. With the audit on, its
            // pass is what pays them, with no block on a node where nothing is missing. Import's bulk recovery stays the
            // backstop for code import finds really missing, so a block remains where something is.
            log.info(
              s"Recovery flags are down (bytecode=${!needBytecode}, storage=${!needStorage}): no scan at this start. The " +
                "background audit pays this debt, with regular sync running."
            )
            startRegularSync()._2
          else startRecovery(needBytecode, needStorage, recoveryScanRoot())
        case (false, SyncMode.Regular) if snapHasStake() =>
          // #1434: do-snap-sync is off, but SNAP was running here and left work behind (a restart mid-heal, or mid-download
          // with best = pivot). Regular sync on that partial trie only fetches nodes on demand, and its stuck handler
          // would re-run SNAP from a new pivot, discarding the download. Resume SNAP where it stopped.
          log.warn("do-snap-sync is off, but SNAP has progress here and has not finished; resuming SNAP")
          startSnapSync(minPivotBlock = liveSnapPivotFloor())
        case (_, SyncMode.Regular) =>
          // Says what actually happens. Regular sync fetches missing nodes on demand (redownload-missing-state-nodes);
          // when peers cannot serve them BlockImporter reports RegularSyncStuck, and that handler starts SNAP whatever
          // do-snap-sync says. Changing that escape to honour do-snap-sync would leave such a node with no way out.
          // Logged at ERROR once, by the start that found the stranded node; later starts note it at INFO.
          liveSnapPivotFloor().foreach { floor =>
            if floorSetThisStart.contains(floor) then
              log.error(
                "Fast sync was in progress when this node was upgraded; fast sync was removed. Its best block {} has " +
                  "no state behind it, and do-snap-sync is off, so regular sync starts there and fetches the missing " +
                  "state from peers node by node. If peers cannot serve it, regular sync reports itself stuck and the " +
                  "node re-syncs with SNAP from a newer pivot, even with do-snap-sync off. Set " +
                  "fukuii.sync.do-snap-sync = true to start with SNAP instead.",
                floor - 1
              )
            else
              log.info(
                "Block {} (reached by an interrupted fast sync) still lacks state; regular sync fetches it on demand",
                floor - 1
              )
          }
          startRegularSync()._2

    def startSnapSync(minPivotBlock: Option[BigInt] = None): Behavior[Command] =
      // MIGRATION: EC.global removed (C2). SNAPSyncController.apply requires an implicit EC;
      // provide the actor's dedicated dispatcher so Futures it creates stay off the global pool.
      given scala.concurrent.ExecutionContext = ctx.executionContext
      log.info("Starting SNAP sync mode")
      syncGeneration += 1
      txGossipGate.stateSyncStarted()
      withdrawChainWeightInvariant("SNAP is starting; the pivot carries an estimate until the headers reach it")

      val snapSyncConfig = loadSnapSyncConfig()

      val snapSync = ctx
        .spawn(
          SNAPSyncController(
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
            syncController = snapAdapter,
            failedFinalizationPivot = snapPivotFailedFinalization
          ),
          s"snap-sync-$syncGeneration",
          DispatcherSelector.fromConfig("sync-dispatcher")
        )

      // §7c-D4: STOP-AND-ALERT — watch the active SNAP controller so an unexpected crash alerts loudly rather than
      // silently corrupting in-flight session state. Replaced by the benign SnapSyncTerminated watch when SNAP finalises
      // into background backfill; unwatched before each intentional ctx.stop(snapSync).
      ctx.watchWith(snapSync, SnapSyncCriticalFailure)

      // Register SNAPSyncController with NetworkPeerManagerActor for message routing
      networkPeerManager ! com.chipprbots.ethereum.network.NetworkPeerManagerActor
        .RegisterSnapSyncControllerCmd(snapSync)

      // If a CL-driven head arrived before SNAP started (post-merge chains), prime the new
      // SNAP actor with it so pivot selection skips the TD-based path entirely.
      if clPivotEnabled then
        latestBeaconHead.foreach { bh =>
          log.info(
            "Priming SNAP sync with buffered CL beacon head {} (knownHeader={})",
            com.chipprbots.ethereum.utils.ByteStringUtils.hash2string(bh.headHash),
            bh.knownHeader.map(_.number).getOrElse("unknown")
          )
          snapSync ! SNAPSyncController.CLPivotHint(bh.headHash, bh.knownHeader)
        }

      minPivotBlock.foreach { minBlock =>
        log.info("Sending MinPivotBlock({}) to new SNAP sync actor", minBlock)
        snapSync ! SNAPSyncController.MinPivotBlock(minBlock)
      }
      snapSync ! SNAPSyncController.Start
      runningSnapSync(snapSync)

    /** Starts (or restarts) regular sync. Returns the spawned `regularSync` ref AND the next `Behavior[Command]` to
      * enter: normally `runningRegularSync`, or — when `resumeBackfill` triggers a standalone backfill resumer —
      * `runningRegularSyncWithStandaloneBackfill`. Callers that need the ref for a death-watch (e.g. the SNAP-finalised
      * path) use `._1`; callers that just transition use `._2`.
      */
    def startRegularSync(resumeBackfill: Boolean = true): (TypedActorRef[RegularSync.Command], Behavior[Command]) =
      syncGeneration += 1
      txGossipGate.stateSyncFinished()
      // H3: while a re-SNAP count stands, imports are measured from a baseline. The first regular sync after a re-SNAP
      // sets it where it starts (SNAP's pivot is no import); any other start keeps it, so a restart forgets no import.
      // A re-SNAP whose wait ended in another state (a bulk code recovery) had its ResnapDue dropped there: forget it,
      // as a node restart does, so the next stuck schedules one again rather than being taken for a repeat.
      if pendingResnap.isDefined && !timers.isTimerActive(ResnapDueKey) then pendingResnap = None
      val resnaps = appStateStorage.regularSyncStuckResnaps()
      RegularSyncMetrics.setConsecutiveResnaps(resnaps)
      if resnaps > 0 then
        if appStateStorage.resnapBaseline().isEmpty then
          appStateStorage.putRegularSyncStuck(resnaps, Some(blockchainReader.getBestBlockNumber)).commit()
        timers.startTimerWithFixedDelay(ResnapCheckKey, ResnapCheck, SyncController.ResnapCheckInterval)

      // spec 022 D7 (review hunter): regular sync fetches a missing node on demand and stores it alone, present over
      // children that may be absent. A clean hand-off has written SnapSyncDone by now, with the walk behind it. Every other
      // route here (SNAP's `Done` with no finalisation, a finalisation that found no pivot header, do-snap-sync off) has
      // not, so what is present is no longer vouched for. No catch: a marker that cannot be removed must not be left to
      // look true while regular sync writes under it.
      if !appStateStorage.isSnapSyncDone() && appStateStorage.isSnapHealInvariantPresent() then
        log.warn("[HEAL-INVARIANT] Regular sync starting with SNAP not done: marker removed")
        appStateStorage.clearSnapHealInvariant().commitSync()

      // Every route out of a bulk bytecode recovery ends here, so this is where its result is checked. The recovery
      // actor marks itself done on abandonment, a crashed coordinator, or a failed scan, so success is read from the
      // code store, not from that flag. FAIL LOUDLY: the importer will hit the same code again.
      bulkCodeRecoveryTrigger.foreach { codeHash =>
        bulkCodeRecoveryTrigger = None
        if evmCodeStorage.get(codeHash).isDefined then
          log.info("Bulk bytecode recovery finished: the code that triggered it is now stored. Resuming regular sync.")
          if appStateStorage.bulkBytecodeRecoveryFailures() != 0 then
            appStateStorage.putBulkBytecodeRecoveryFailures(0).commit()
        else
          val failures = appStateStorage.bulkBytecodeRecoveryFailures() + 1
          log.error(
            "Bulk bytecode recovery finished but contract code {} is STILL missing (failure {} of {}). Possible causes: " +
              "no peer served it; its account is not in the scanned trie; a subtree the scan could not read holds it; or " +
              "the scan could not run at all (logged above). Block import will now fetch that hash on its own.",
            com.chipprbots.ethereum.utils.ByteStringUtils.hash2string(codeHash),
            failures,
            SyncController.MaxBulkBytecodeRecoveryFailures
          )
          // A scan that could not run settles nothing, the cap included (spec 022 T050a): only a pass stops the next start's.
          if failures >= SyncController.MaxBulkBytecodeRecoveryFailures && !recoveryScanCouldNotRun then
            log.error(
              "Giving up on bulk bytecode recovery after {} failures: marking bytecodeRecoveryDone so restarts stop " +
                "repeating a full trie scan. Missing code is still fetched one hash at a time at import.",
              failures
            )
            appStateStorage
              .bytecodeRecoveryDone()
              .and(appStateStorage.putBulkBytecodeRecoveryFailures(failures))
              .commit()
          else
            appStateStorage
              .clearBytecodeRecoveryDone()
              .and(appStateStorage.putBulkBytecodeRecoveryFailures(failures))
              .commit()
      }
      recoveryScanCouldNotRun = false

      // #1654: a SNAP hand-off has consumed its pending mark by now; any other way here drops it. Then the operator's
      // -Dfukuii.seed-chain-weights=HASH1:TD1,... (a trusted weight for a BASE; ChainWeightSeed says where the value
      // comes from).
      if appStateStorage.isChainWeightSnapPending() then appStateStorage.clearChainWeightSnapPending().commitSync()
      applyChainWeightSeeds()

      // Register self as calibration target so NetworkPeerManagerActor can push the correct
      // cumulative TD when it detects a TD-PROXY-GAP at peer handshake (stale genesis-proxy TD
      // stored by SNAP finalization when no ETH68 peers were available at that time).
      networkPeerManager ! com.chipprbots.ethereum.network.NetworkPeerManagerActor
        .RegisterChainWeightCalibrationTargetCmd(cwCalibrationAdapter)

      // Unconditional timed calibration: fire CalibrateChainWeightNow 30s after RegularSync starts.
      // NPA forwards bestNetworkTip (best ETH68 peer TD seen since startup) to this actor.
      // Handles multi-restart TD drift that falls below the TD-PROXY-GAP 10,000× threshold
      // (e.g. Restart #7 ratio=7,411×). For pure ETH69 networks, NPA sends a (0,0) sentinel
      // and tier-3 local chain computation fires instead.
      ctx.system.scheduler.scheduleOnce(
        java.time.Duration.ofSeconds(30),
        () => networkPeerManager ! com.chipprbots.ethereum.network.NetworkPeerManagerActor.CalibrateChainWeightNowCmd,
        ctx.executionContext
      )

      val peersClient =
        ctx.spawn(
          Behaviors
            .supervise(PeersClient.behavior(networkPeerManager, peerEventBus, blacklist, syncConfig))
            .onFailure[Throwable](
              // PR #1378: PeersClient is a sync backbone actor — no `.withMaxRestarts` cap.
              SupervisorStrategy.restartWithBackoff(500.millis, 10.seconds, 0.2)
            ),
          s"peers-client-$syncGeneration",
          DispatcherSelector.fromConfig("sync-dispatcher")
        )
      val regularSync = ctx
        .spawn(
          RegularSync.apply(
            peersClient,
            networkPeerManager,
            peerEventBus,
            consensus,
            blockchain,
            blockchainReader,
            blockchainWriter,
            stateStorage,
            evmCodeStorage,
            { val br = new BranchResolution(blockchainReader, designatedHeadOpt); br.messConfig = messConfig; br },
            validators.blockValidator,
            blacklist,
            syncConfig,
            ommersPool,
            pendingTransactionsManager,
            blockTopic,
            configBuilder,
            ctx.self,
            importLock,
            reorgState,
            onCaughtUp = () => txGossipGate.markSynced("regular sync reached the best block peers announced")
          ),
          s"regular-sync-$syncGeneration",
          DispatcherSelector.fromConfig("sync-dispatcher")
        )
      regularSync ! SyncProtocol.Start
      ctx.watchWith(regularSync, RegularSyncTerminated(regularSync))
      startBackgroundAudit()
      startChainWeightReconcile()
      // After SNAP completes, chain backfill (#1162) writes headers / bodies / receipts in the
      // background. If the node was killed mid-backfill, persisted cursors (#1169) tell us how
      // far it got — spawn a standalone ChainDownloader to finish the job alongside regular sync.
      // Suppressed when called from the SnapSyncFinalized path: SNAPSyncController already owns
      // the live backfill actor in that flow.
      val nextBehavior =
        if resumeBackfill then maybeStartBackfillResume(regularSync).getOrElse(runningRegularSync(regularSync))
        else runningRegularSync(regularSync)
      (regularSync, nextBehavior)

    private def auditGate: BackgroundAudit.Gate =
      BackgroundAudit.gate(backgroundAuditEnabled, stateStorage, appStateStorage)

    /** Every route into regular sync comes here, a restart of it included, so a previous incarnation's audit goes
      * first. A failed audit restarts from its saved place and gives up after a few tries with nothing else harmed: it
      * is off the critical path, and the next start tries again.
      */
    private def startBackgroundAudit(): Unit =
      stopBackgroundAudit()
      auditGate match
        case BackgroundAudit.Gate.Run =>
          backgroundAudit = Some(
            ctx.spawn(
              Behaviors
                .supervise(
                  BackgroundAudit.forNode(
                    blockchainReader,
                    appStateStorage,
                    stateStorage,
                    evmCodeStorage,
                    networkPeerManager,
                    peerEventBus,
                    syncConfig
                  )
                )
                .onFailure[Exception](
                  SupervisorStrategy.restartWithBackoff(1.minute, 30.minutes, 0.2).withMaxRestarts(5)
                ),
              s"background-audit-$syncGeneration",
              DispatcherSelector.blocking()
            )
          )
        case BackgroundAudit.Gate.CannotRepair => sayAuditOff("pruning.mode = inmemory")
        case BackgroundAudit.Gate.Disabled     => sayAuditOff(s"fukuii.sync.${BackgroundAudit.EnabledPath} = false")
        case BackgroundAudit.Gate.NotOwed      => ()

    private def sayAuditOff(why: String): Unit =
      if !saidAuditOff then
        saidAuditOff = true
        log.info(s"[HEAL-AUDIT] Off: $why")

    /** Decision 9. A stopped audit's Futures still finish, and they write nothing. */
    private def stopBackgroundAudit(): Unit =
      backgroundAudit.foreach(ctx.stop)
      backgroundAudit = None

    // ---- stored chain weights (#1654) --------------------------------------------------------------------------------

    /** Every route into regular sync comes here. One pass at a time: a pass still running is left alone, and a new one
      * waits on [[chainWeightPassLock]] for a superseded one to end at its next step. A pass runs unless the
      * chain-weight marker stands, and resumes from its saved place. Nothing of it runs on this actor's thread but the
      * start.
      */
    private def startChainWeightReconcile(): Unit =
      val active = chainWeightPass.exists { case (_, ended) => !ended.get() }
      if chainWeightReconcileOn && !active && !appStateStorage.hasChainWeightInvariant() then
        val ended = new java.util.concurrent.atomic.AtomicBoolean(false)
        val pass = chainWeightPassLock.lock
          .surround {
            def once: IO[ChainWeightReconcile.Outcome] = IO.defer {
              val e = chainWeightEpoch.current
              ChainWeightReconcile.run(
                ChainWeightReconcile.Engine(blockchainReader, blockchainWriter, appStateStorage),
                importLock,
                chainWeightEpoch,
                e,
                appStateStorage,
                chainWeightLog
              )
            }
            // A seeded pass re-derives the blocks above an operator's seed and sets no marker; then the pass from
            // genesis runs, and waits where the headers below the seed are not in yet.
            once.flatMap {
              case ChainWeightReconcile.Outcome.Seeded(_) => once
              case other                                  => IO.pure(other)
            }
          }
          .handleErrorWith { e =>
            // Off the critical path: logged, and the next regular-sync start resumes from the saved place.
            IO(chainWeightLog.error("[CW-RECONCILE] failed; the next regular-sync start resumes it", e))
              .as(ChainWeightReconcile.Outcome.Superseded)
          }
          .guarantee(IO(ended.set(true)))
        chainWeightPass = Some((pass.unsafeRunCancelable()(using IORuntime.global), ended))

    /** Supersede the running pass: it writes nothing more, and ends at its next step. */
    private def stopChainWeightReconcile(): Unit =
      chainWeightEpoch.supersede(())
      chainWeightPass.foreach { case (cancel, _) => cancel() }
      chainWeightPass = None

    /** A SNAP start: the marker goes, and whether the hand-off may set it again is recorded with it
      * ([[ChainWeightReconcile.atSnapStart]]). Atomic against a pass's final batch, with no lock on this thread.
      */
    private def withdrawChainWeightInvariant(why: String): Unit =
      val stood = appStateStorage.hasChainWeightInvariant()
      val handOffMaySet =
        chainWeightEpoch.supersede(ChainWeightReconcile.atSnapStart(blockchainReader, appStateStorage))
      stopChainWeightReconcile()
      if stood then
        chainWeightLog.info(
          s"[CW-RECONCILE] chain-weight marker withdrawn: $why; an exact hand-off sets it again (pending=$handOffMaySet)"
        )

    /** A SNAP hand-off: the marker is set again only if this SNAP began on an exact chain and its pivot weight follows
      * from its parent's ([[ChainWeightReconcile.atSnapHandOff]]). Otherwise the pass checks every weight from genesis.
      */
    private def markChainWeightsAfterSnap(pivot: BigInt): Unit =
      if chainWeightEpoch.supersede(ChainWeightReconcile.atSnapHandOff(blockchainReader, appStateStorage, pivot)) then
        chainWeightLog.info(s"[CW-RECONCILE] SNAP pivot $pivot closes an exact chain: marker set, no pass needed")
      else
        chainWeightLog.info(
          s"[CW-RECONCILE] SNAP pivot $pivot: marker not set (estimated pivot weight, or the chain below was not known " +
            "exact when this SNAP began); a pass checks every weight from genesis once the headers are in"
        )

    /** A calibration write (#1654), on this thread with no lock: atomic against a pass's saves and final batch through
      * the epoch's monitor, which is held only for one small commit. Refused while the marker stands (every weight is
      * exact then). Otherwise it supersedes a running pass, drops a saved place at or above this block so the next pass
      * checks the value written here, and starts the pass again.
      */
    private def writeCalibratedWeight(header: BlockHeader, weight: ChainWeight): Boolean =
      val written = chainWeightEpoch.supersede {
        if appStateStorage.hasChainWeightInvariant() then false
        else
          val placeAbove = appStateStorage
            .getChainWeightReconcileProgress()
            .flatMap(ChainWeightReconcile.decode)
            .exists(_.cursor.number >= header.number.value)
          val write = blockchainWriter.storeChainWeight(header.hash, weight)
          (if placeAbove then write.and(appStateStorage.removeChainWeightReconcileProgress()) else write).commit()
          true
      }
      if written && chainWeightPass.isDefined then
        stopChainWeightReconcile()
        startChainWeightReconcile()
      written

    /** `-Dfukuii.seed-chain-weights` (see [[ChainWeightSeed]]), before regular sync starts: under the chain insert
      * lock, TRIED for at most a moment so this thread never parks behind a block commit or a pass's final step (a seed
      * skipped here is tried again at the next start), and atomic against everything a pass writes. A seed writes only
      * the base's weight (vault N2): an accepted one supersedes the running pass, and the pass started next re-derives
      * the blocks above the base off this thread, then checks from genesis. Refused outright on a chain that was PoS
      * from genesis ([[HeadChainWeight.posFromGenesis]]: terminal TD at or below the genesis difficulty).
      */
    private def applyChainWeightSeeds(): Unit =
      Option(System.getProperty(ChainWeightSeed.Property)).filter(_.trim.nonEmpty).foreach { spec =>
        val posFromGenesis =
          HeadChainWeight.posFromGenesis(configBuilder.blockchainConfig.terminalTotalDifficulty, blockchainReader)
        ChainInsertLock.tryExclusive(SyncController.SeedLockWait) {
          chainWeightEpoch.supersede(
            ChainWeightSeed(spec, blockchainReader, blockchainWriter, appStateStorage, posFromGenesis)
          )
        } match
          case None =>
            log.warn(
              s"seed-chain-weights: not applied: the chain insert lock stayed busy for ${SyncController.SeedLockWait}; " +
                "tried again at the next regular-sync start"
            )
          case Some(outcomes) =>
            outcomes.foreach {
              case ChainWeightSeed.Outcome.Applied(number, hash, td) =>
                log.warn(
                  s"seed-chain-weights: block $number (${com.chipprbots.ethereum.utils.ByteStringUtils.hash2string(hash)}) " +
                    s"anchored at TD=$td; the chain-weight pass re-derives the blocks above it, then checks every " +
                    "weight from genesis once the headers are in"
                )
              case ChainWeightSeed.Outcome.Refused(entry, reason) =>
                log.warn(s"seed-chain-weights: refused '$entry': $reason")
            }
            if outcomes.exists(_.isInstanceOf[ChainWeightSeed.Outcome.Applied]) then stopChainWeightReconcile()
      }

    /** Spawn a standalone `ChainDownloader` to resume background chain backfill from persisted cursors. Returns
      * `Some(runningRegularSyncWithStandaloneBackfill)` when a resumer is spawned; `None` when SNAP has not completed,
      * no `BackfillTarget` was persisted, or all cursors have already reached the target (caller then enters plain
      * `runningRegularSync`). Issues #1162 (background backfill) + #1169 (resume across restarts).
      */
    private def maybeStartBackfillResume(regularSync: TypedActorRef[RegularSync.Command]): Option[Behavior[Command]] =
      if appStateStorage.needsBackfillResume() then
        val target = appStateStorage.getBackfillTarget()
        val headerCursor = appStateStorage.getBackfillBestHeader()
        val bodyCursor = appStateStorage.getBackfillBestBody()
        val receiptCursor = appStateStorage.getBackfillBestReceipt()
        log.info(
          "Resuming background chain backfill: target={}, header={}, body={}, receipt={}",
          target,
          headerCursor,
          bodyCursor,
          receiptCursor
        )
        val snapSyncConfig = loadSnapSyncConfig()
        syncGeneration += 1
        import com.chipprbots.ethereum.blockchain.sync.snap.ChainDownloader
        // ChainDownloader is Pekko Typed (Group S6). Spawn it via ctx.spawn and convert the resulting Typed ref back to
        // Classic so the existing `! ChainDownloader.X` sends below keep compiling.
        val resumer = ctx
          .spawn(
            Behaviors
              .supervise(
                ChainDownloader(
                  blockchainReader = blockchainReader,
                  blockchainWriter = blockchainWriter,
                  appStateStorage = appStateStorage,
                  networkPeerManager = networkPeerManager,
                  peerEventBus = peerEventBus,
                  syncConfig = syncConfig,
                  replyTo = chainDownloaderAdapter,
                  maxConcurrentRequests = snapSyncConfig.chainBackfillConcurrentRequests,
                  requestTimeout = snapSyncConfig.chainDownloadTimeout
                )
              )
              // PR #1378: ChainDownloader must run until backfill completes — backoff, no cap.
              .onFailure[Throwable](SupervisorStrategy.restartWithBackoff(500.millis, 10.seconds, 0.2)),
            s"backfill-resumer-$syncGeneration",
            DispatcherSelector.fromConfig("sync-dispatcher")
          )
        ctx.watchWith(resumer, ResumerTerminated(resumer))
        resumer ! ChainDownloader.Start(target)
        Some(runningRegularSyncWithStandaloneBackfill(regularSync, resumer))
      else None

    /** Receive while regular sync runs alongside a standalone backfill resumer (#1169). Mirrors
      * `runningRegularSyncWithBackfill` but for the post-restart case where we own the backfill actor directly instead
      * of routing through a lingering `SNAPSyncController`.
      */
    def runningRegularSyncWithStandaloneBackfill(
        regularSync: TypedActorRef[RegularSync.Command],
        resumer: TypedActorRef[ChainDownloader.Command]
    ): Behavior[Command] =
      Behaviors.receive { (_, cmd) =>
        val msg = unwrap(cmd)
        msg match
          case com.chipprbots.ethereum.blockchain.sync.snap.ChainDownloader.Done =>
            log.info("Standalone chain backfill resume complete.")
            ctx.unwatch(resumer)
            ctx.stop(resumer)
            runningRegularSync(regularSync)

          case progress: com.chipprbots.ethereum.blockchain.sync.snap.ChainDownloader.Progress =>
            log.debug(
              "Standalone backfill progress: headers={} bodies={} receipts={} target={}",
              progress.headersDownloaded,
              progress.bodiesDownloaded,
              progress.receiptsDownloaded,
              progress.targetBlock
            )
            Behaviors.same

          case ResumerTerminated(actor) if actor == resumer =>
            log.warn("Standalone backfill resumer died; chain backfill aborted (cursors persist for next restart).")
            runningRegularSync(regularSync)

          case m if isRestartTrigger(m) =>
            log.info("Restart triggered while standalone backfill was running; poison-pilling backfill resumer first.")
            ctx.unwatch(resumer)
            ctx.stop(resumer)
            ctx.self ! cmd // Re-deliver the original Command so the new state handles it.
            runningRegularSync(regularSync)

          case m =>
            handleRegularSyncMsg(regularSync, m)
      }

    /** The trie a recovery scans, at a start or for a bulk bytecode recovery: the CURRENT BEST block's state root,
      * falling back to the SNAP pivot's.
      *
      * The best root is the state regular sync executes on, and it covers contracts created after the pivot. The pivot
      * root is gone within minutes: with the default `basic` pruning (history 64) it is pruned about 64 blocks on, and
      * a pivot sits behind the head, so catch-up passes that almost at once. Measured on Mordor (spec 022, 2026-10-31):
      * a node stopped about 3 minutes after its hand-off had imported 87 blocks past the pivot, and the next start's
      * scan found the pivot root absent 6 ms in. The root need not be the pivot's for storage either: the storage phase
      * takes its (account, storage root) pairs from this same scan, asks peers at this same root, and checks each chunk
      * against its pair's root (and rolls to a recent root when peers no longer serve this one).
      *
      * A scan already under way keeps its root while that root can be read, so a restart mid-scan resumes (the tag in
      * `getRecoveryProgressFor` matches) instead of starting hours of walking again. The best block can move under a
      * scan: regular sync is stopped through the scan and the downloads, but the Engine API is not, and with a
      * consensus client feeding the next payload `forkchoiceUpdated` moves it. That path does not prune, and nothing
      * else does until regular sync's first saved block, so the walk is unharmed. Its block number is the best block's,
      * since the progress does not hold one: only what a download writes is tagged with it, and those nodes belong to
      * the best state anyway.
      *
      * `None` when there is no root: `startRecovery` then has nothing to scan.
      */
    private def recoveryScanRoot(): Option[(ByteString, BigInt)] =
      def readable(root: ByteString, number: BigInt): Boolean =
        try
          stateStorage.getBackingStorage(number).get(root.toArray)
          true
        catch case _: Exception => false
      val best = blockchainReader.getBestBlockHeader.map(h => (h.stateRoot.value, h.number.value))
      val pivot = for
        root <- appStateStorage.getSnapSyncStateRoot()
        number <- appStateStorage.getSnapSyncPivotBlock()
      yield (root, number)
      val underWay = for
        progress <- appStateStorage.getRecoveryProgress()
        (bestRoot, number) <- best
        if progress.scanRoot != bestRoot && readable(progress.scanRoot, number)
      yield (progress.scanRoot, number)
      underWay match
        case Some(ok) =>
          log.info("Recovery resumes its scan on the state root it started with, at best block {}.", ok._2)
          Some(ok)
        case None =>
          best.filter { case (r, n) => readable(r, n) } match
            case Some(ok) =>
              log.info("Recovery scans the best block's state root (block {}).", ok._2)
              Some(ok)
            case None =>
              log.error(
                "The best block's state root is not readable; recovery falls back to the SNAP pivot's root, which may be pruned."
              )
              pivot

    def startRecovery(
        needBytecode: Boolean,
        needStorage: Boolean,
        scanRoot: Option[(ByteString, BigInt)]
    ): Behavior[Command] =
      syncGeneration += 1
      txGossipGate.stateSyncStarted()

      // `pivotBlock` from here on is the scanned root's block: the best block's, unless that root could not be read.
      scanRoot match
        case Some((stateRoot, pivotBlock)) =>
          log.info(
            s"[SNAP-RECOVERY] Phase starting — bytecodeNeeded=$needBytecode storageNeeded=$needStorage " +
              s"generation=$syncGeneration — polling for snap-capable peers every 5s"
          )

          val snapSyncConfig = loadSnapSyncConfig()

          // D-T060-2 (spec 016 T060c): the recovery actors reach the stores through one `StateIngest`, built here
          // as SNAPSyncController builds its own: the same constructor stores, `pathNodeStorageOpt` derived as it
          // derives it (`None` on the Hash scheme, i.e. ETC), `validatorFactory` as its `apply` default. Building it
          // only stores the references; on the Path scheme it also wraps the flat-slot data source in a new
          // `PathNodeStorage`, which holds no state of its own and which the recovery actors do not read.
          val pathNodeStorageOpt: Option[PathNodeStorage] =
            if snapSyncConfig.storageScheme == StorageScheme.Path then
              Some(new PathNodeStorage(flatSlotStorage.dataSource))
            else None
          val validatorFactory: MptStorage => StateValidator = new StateValidator(_)
          val snapProgressStorage = new SnapSyncProgressStorage(appStateStorage.dataSource)
          val storageDoneStorage: SnapStorageDoneStorage = new SnapStorageDoneStorage(flatSlotStorage.dataSource)
          // The healing store (spec 016 C-H1): recovery never spawns the healing coordinator, so its capability hands
          // out none (the core's is HealingOrchestrator's lazy val, never built here).
          val bfsQueueStorageOpt: Option[BfsQueueStorage] = None
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
            bfsQueueStorageOpt
          )

          if snapSyncConfig.parallelRecoveryScan then
            // Combined path: ONE parallel, resumable single-pass scan finds both gap sets; downloads start
            // once it reports (in `runningCombinedScan`).
            log.info("Recovery: combined parallel scan enabled — one pass finds bytecode + storage gaps.")
            // Phase gauges are need-aware: a phase already done in a prior run shows Complete (not idle) while the
            // combined scan re-verifies it in the same pass.
            RecoveryMetrics.setBytecodePhase(
              if needBytecode then RecoveryMetrics.PhaseScanning else RecoveryMetrics.PhaseComplete
            )
            RecoveryMetrics.setStoragePhase(
              if needStorage then RecoveryMetrics.PhaseScanning else RecoveryMetrics.PhaseComplete
            )
            ctx.spawn(
              Behaviors
                .supervise(
                  CombinedRecoveryScanActor(
                    stateRoot,
                    stateStorage,
                    evmCodeStorage,
                    appStateStorage,
                    combinedScanAdapter,
                    pivotBlock,
                    snapSyncConfig
                  )
                )
                // PR #1378: CombinedRecoveryScanActor owns a full recovery pass that must complete — no cap.
                .onFailure[Throwable](SupervisorStrategy.restartWithBackoff(1.second, 30.seconds, 0.2)),
              s"combined-recovery-scan-$syncGeneration",
              DispatcherSelector.fromConfig("sync-dispatcher")
            )
            runningCombinedScan(needBytecode, needStorage, stateRoot, pivotBlock, snapSyncConfig, stateIngest)
          else
            // Legacy path: each phase scans the full trie independently, then downloads.
            val bytecodeActor =
              if needBytecode then
                Some(
                  ctx
                    .spawn(
                      Behaviors
                        .supervise(
                          BytecodeRecoveryActor(
                            stateRoot,
                            stateIngest,
                            networkPeerManager,
                            bytecodeRecoveryAdapter,
                            pivotBlock,
                            snapSyncConfig
                          )
                        )
                        .onFailure[Throwable](
                          // PR #1378: state-recovery phase owner — a `.withMaxRestarts` cap would
                          // stall recovery permanently (silently stops the actor forever). No cap.
                          SupervisorStrategy.restartWithBackoff(1.second, 30.seconds, 0.2)
                        ),
                      s"bytecode-recovery-$syncGeneration",
                      DispatcherSelector.fromConfig("sync-dispatcher")
                    )
                )
              else None
            val storageActor =
              if needStorage then
                Some(
                  ctx
                    .spawn(
                      Behaviors
                        .supervise(
                          StorageRecoveryActor(
                            stateRoot,
                            stateIngest,
                            networkPeerManager,
                            storageRecoveryAdapter,
                            pivotBlock,
                            snapSyncConfig
                          )
                        )
                        .onFailure[Throwable](
                          // PR #1378: state-recovery phase owner — a `.withMaxRestarts` cap would
                          // stall recovery permanently (silently stops the actor forever). No cap.
                          SupervisorStrategy.restartWithBackoff(1.second, 30.seconds, 0.2)
                        ),
                      s"storage-recovery-$syncGeneration",
                      DispatcherSelector.fromConfig("sync-dispatcher")
                    )
                )
              else None
            beginRecoveryDownloads(
              bytecodeActor,
              storageActor,
              bytecodeComplete = !needBytecode,
              storageComplete = !needStorage
            )

        case None =>
          recoveryCouldNotRun(
            needBytecode,
            needStorage,
            "there is no state root to scan: no readable best block, no pivot"
          )

    /** spec 022 T050a (the user's decision, 2026-10-31): a scan that could not run settles nothing. Neither flag is
      * written, and the scan's progress stays, so the next start tries again. It resumes only while that root can still
      * be read: regular sync starts here, and under `basic` pruning about 64 blocks later the next start scans afresh
      * on the best root. Archive and in-memory pruning keep the root, so there a failure that keeps failing is paid at
      * every start up to the node that fails. No hard stop: regular sync fetches what it reaches on demand. A pass that
      * did run is another matter: see `runningCombinedScan`.
      */
    private def recoveryCouldNotRun(needBytecode: Boolean, needStorage: Boolean, why: String): Behavior[Command] =
      log.error(
        s"[SNAP-RECOVERY] The recovery scan could not run: $why. Still owed: bytecode=$needBytecode storage=$needStorage. " +
          "Neither recovery flag is written and the scan's progress is kept, so the next start tries again. Regular sync " +
          "starts now and fetches what it reaches on demand."
      )
      recoveryScanCouldNotRun = true
      RecoveryMetrics.setBytecodePhase(
        if needBytecode then RecoveryMetrics.PhaseIdle else RecoveryMetrics.PhaseComplete
      )
      RecoveryMetrics.setStoragePhase(if needStorage then RecoveryMetrics.PhaseIdle else RecoveryMetrics.PhaseComplete)
      startRegularSync()._2

    /** Wait for the combined scan to report both gap sets, then spawn download-only recovery actors for whichever
      * phases still have gaps. Phases the scan found already complete are marked done immediately.
      *
      * A pass that ran settles the flags, holes or not (spec 022 T050a; the user's decision, 2026-10-31): the scan runs
      * once, and rescanning a datadir with holes at every start would block regular sync for hours each time. So a flag
      * means a pass ran, not that everything is there where the pass met a hole, and the WARN says so.
      */
    def runningCombinedScan(
        needBytecode: Boolean,
        needStorage: Boolean,
        stateRoot: ByteString,
        pivotBlock: BigInt,
        snapSyncConfig: com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncConfig,
        stateIngest: StateIngest
    ): Behavior[Command] = Behaviors.receive { (_, cmd) =>
      val msg = unwrap(cmd)
      msg match
        case CombinedRecoveryScanActor.CombinedScanComplete(_, _, _, true) =>
          recoveryCouldNotRun(
            needBytecode,
            needStorage,
            s"state root ${stateRoot.take(8).toArray.map("%02x".format(_)).mkString} at block $pivotBlock is absent, or " +
              "reading the trie failed (logged above)"
          )

        case CombinedRecoveryScanActor.CombinedScanComplete(byteGaps, storGaps, holes, _) =>
          val effByte = if needBytecode then byteGaps else Nil
          val effStor = if needStorage then storGaps else Nil
          log.info(s"Combined recovery scan reported ${effByte.size} bytecode gaps, ${effStor.size} storage gaps.")
          if holes > 0 then
            log.warn(
              "[SNAP-RECOVERY] The recovery scan's pass could not read {} subtree(s) this session: the store holds no node " +
                "at the top of each, so nothing under them was looked at. The recovery flags will mean that a pass ran, not " +
                "that code and storage are complete. The pass is not repeated: block import fetches what it reaches on demand.",
              holes
            )
          // Phases the combined scan found complete (no gaps) are done right now.
          if needBytecode && effByte.isEmpty then appStateStorage.bytecodeRecoveryDone().commit()
          if needStorage && effStor.isEmpty then appStateStorage.storageRecoveryDone().commit()

          val bytecodeActor =
            if needBytecode && effByte.nonEmpty then
              Some(
                ctx
                  .spawn(
                    Behaviors
                      .supervise(
                        BytecodeRecoveryActor.applyPreloaded(
                          stateRoot,
                          stateIngest,
                          networkPeerManager,
                          bytecodeRecoveryAdapter,
                          pivotBlock,
                          snapSyncConfig,
                          effByte
                        )
                      )
                      .onFailure[Throwable](
                        // PR #1378: state-recovery phase owner — a `.withMaxRestarts` cap would
                        // stall recovery permanently (silently stops the actor forever). No cap.
                        SupervisorStrategy.restartWithBackoff(1.second, 30.seconds, 0.2)
                      ),
                    s"bytecode-recovery-dl-$syncGeneration",
                    DispatcherSelector.fromConfig("sync-dispatcher")
                  )
              )
            else None
          val storageActor =
            if needStorage && effStor.nonEmpty then
              Some(
                ctx
                  .spawn(
                    Behaviors
                      .supervise(
                        StorageRecoveryActor.applyPreloaded(
                          stateRoot,
                          stateIngest,
                          networkPeerManager,
                          storageRecoveryAdapter,
                          pivotBlock,
                          snapSyncConfig,
                          effStor
                        )
                      )
                      .onFailure[Throwable](
                        // PR #1378: state-recovery phase owner — a `.withMaxRestarts` cap would
                        // stall recovery permanently (silently stops the actor forever). No cap.
                        SupervisorStrategy.restartWithBackoff(1.second, 30.seconds, 0.2)
                      ),
                    s"storage-recovery-dl-$syncGeneration",
                    DispatcherSelector.fromConfig("sync-dispatcher")
                  )
              )
            else None
          // A phase with no download actor is finished (no gaps / already done) — show Complete, not idle. Phases that
          // will download have their phase set to Downloading by the recovery actor.
          if bytecodeActor.isEmpty then RecoveryMetrics.setBytecodePhase(RecoveryMetrics.PhaseComplete)
          if storageActor.isEmpty then RecoveryMetrics.setStoragePhase(RecoveryMetrics.PhaseComplete)
          beginRecoveryDownloads(
            bytecodeActor,
            storageActor,
            bytecodeComplete = bytecodeActor.isEmpty,
            storageComplete = storageActor.isEmpty
          )

        case bh: ForkChoiceManager.BeaconHead =>
          handleBeaconHead(bh, snapSyncOpt = None)
          Behaviors.same

        case other =>
          log.debug("Ignoring message during combined recovery scan: {}", other.getClass.getSimpleName)
          Behaviors.same
    }

    // Timer key for the recovery peer poller (replaces the Classic `Cancellable` peerPoller field).
    private val RecoveryPollerKey = "recovery-peer-poller"

    /** Wire up download-only recovery: register for SNAP routing, start the peer poller, and enter `runningRecovery`.
      * If there is nothing to download (no gaps), clear the resumable checkpoint and go straight to regular sync.
      */
    private def beginRecoveryDownloads(
        bytecodeActor: Option[TypedActorRef[BytecodeRecoveryActor.Command]],
        storageActor: Option[TypedActorRef[StorageRecoveryActor.Command]],
        bytecodeComplete: Boolean,
        storageComplete: Boolean
    ): Behavior[Command] =
      if bytecodeActor.isEmpty && storageActor.isEmpty then
        log.info("Recovery: no gaps to download. Transitioning to regular sync.")
        appStateStorage.clearRecoveryProgress().commit()
        startRegularSync()._2
      else
        bytecodeActor.foreach(a => ctx.watchWith(a, BytecodeRecoveryTerminated(a)))
        storageActor.foreach(a => ctx.watchWith(a, StorageRecoveryTerminated(a)))
        // §8k-G4c/G4e-final: register recoverySnapAdapter as the SNAP routing target during recovery.
        // No SNAPSyncController exists during recovery — SyncController relays ByteCodesResponse →
        // BytecodeRecoveryActor and StorageRangesResponse → StorageRecoveryActor (see runningRecovery handlers).
        networkPeerManager ! com.chipprbots.ethereum.network.NetworkPeerManagerActor.RegisterSnapSyncControllerCmd(
          recoverySnapAdapter
        )
        timers.startTimerWithFixedDelay(RecoveryPollerKey, PollRecoveryPeers, 2.seconds, 5.seconds)
        runningRecovery(bytecodeActor, storageActor, bytecodeComplete, storageComplete)

    /** Centralised recovery teardown: stop the peer poller, deregister SNAP routing, clear the resumable checkpoint,
      * and start regular sync. Called from every "all recovery complete" path.
      */
    private def completeRecovery(): Behavior[Command] =
      timers.cancel(RecoveryPollerKey)
      networkPeerManager ! com.chipprbots.ethereum.network.NetworkPeerManagerActor.RegisterSnapSyncControllerCmd(
        ctx.system.deadLetters[SNAPSyncController.Command]
      )
      appStateStorage.clearRecoveryProgress().commit()
      log.info("All recovery complete. Transitioning to regular sync.")
      startRegularSync()._2

    def runningRecovery(
        bytecodeActor: Option[TypedActorRef[BytecodeRecoveryActor.Command]],
        storageActor: Option[TypedActorRef[StorageRecoveryActor.Command]],
        bytecodeComplete: Boolean,
        storageComplete: Boolean
    ): Behavior[Command] = Behaviors.receive { (_, cmd) =>
      val msg = unwrap(cmd)
      msg match
        case BytecodeRecoveryActor.RecoveryComplete =>
          log.info(s"[SNAP-RECOVERY] bytecode recovery complete (storage done: $storageComplete)")
          if storageComplete then completeRecovery()
          else runningRecovery(bytecodeActor = None, storageActor, bytecodeComplete = true, storageComplete)

        case StorageRecoveryActor.RecoveryComplete =>
          log.info(s"[SNAP-RECOVERY] storage recovery complete (bytecode done: $bytecodeComplete)")
          if bytecodeComplete then completeRecovery()
          else runningRecovery(bytecodeActor, storageActor = None, bytecodeComplete, storageComplete = true)

        case PollRecoveryPeers =>
          networkPeerManager ! com.chipprbots.ethereum.network.NetworkPeerManagerActor.GetHandshakedPeersCmd(
            handshakedPeersAdapter
          )
          Behaviors.same

        case com.chipprbots.ethereum.network.NetworkPeerManagerActor.HandshakedPeers(peers) =>
          val snapPeers =
            peers.filter { case (_, peerInfo) => SNAPSyncController.servesSnapState(peerInfo) }
          if snapPeers.nonEmpty then
            snapPeers.foreach { case (peer, _) =>
              bytecodeActor.foreach(_ ! BytecodeRecoveryActor.ByteCodePeerAvailable(peer))
              storageActor.foreach(_ ! StorageRecoveryActor.StoragePeerAvailable(peer))
            }
          // If storage recovery is waiting for a recent root and no header fetch is in flight, start one
          // now using the freshest peer height in this snapshot.
          if recentRootRequester.isDefined && recentRootBootstrap.isEmpty then maybeStartRecentRootBootstrap(peers)
          Behaviors.same

        // §8k-G4c: SNAP protocol responses arrive here because beginRecoveryDownloads registers
        // recoverySnapAdapter.toClassic with NPMA (no SNAPSyncController exists during recovery).
        // SyncController acts as the routing relay: forward ByteCodesResponse to BytecodeRecoveryActor
        // → ByteCodeCoordinator, and StorageRangesResponse to StorageRecoveryActor → StorageRangeCoordinator.
        // AccountRangeResponse and TrieNodesResponse are not used in the recovery path — drop them.
        case snap.SNAPSyncController.ByteCodesResponse(msg) =>
          bytecodeActor.foreach(_ ! BytecodeRecoveryActor.ForwardByteCodesResponse(msg))
          Behaviors.same

        case snap.SNAPSyncController.StorageRangesResponse(msg) =>
          storageActor.foreach(_ ! StorageRecoveryActor.ForwardStorageRangesResponse(msg))
          Behaviors.same

        case _: snap.SNAPSyncController.AccountRangeResponse =>
          log.debug("Dropping AccountRangeResponse in recovery mode (not used by recovery coordinators)")
          Behaviors.same

        case _: snap.SNAPSyncController.TrieNodesResponse =>
          log.debug("Dropping TrieNodesResponse in recovery mode (not used by recovery coordinators)")
          Behaviors.same

        // Storage recovery: the saved pivot root has aged out of every peer's serve window. Fetch a recent
        // canonical root so the download can roll onto something peers can still serve, instead of wedging.
        case StorageRecoveryActor.RequestRecentRoot(replyTo) =>
          if recentRootRequester.isEmpty && recentRootBootstrap.isEmpty then
            recentRootRequester = Some(replyTo)
            log.info("Recovery requested a recent root to roll off the aged pivot. Polling peers for the network head.")
            networkPeerManager ! com.chipprbots.ethereum.network.NetworkPeerManagerActor.GetHandshakedPeersCmd(
              handshakedPeersAdapter
            )
          else log.debug("Recovery recent-root request already in flight; ignoring duplicate.")
          Behaviors.same

        case PivotHeaderBootstrap.Completed(block, header) if recentRootRequester.isDefined =>
          val rootHex = header.stateRoot.value.take(4).toArray.map("%02x".format(_)).mkString
          log.info(s"Recovery recent-root: fetched header for block $block (root $rootHex). Replying.")
          stopRecentRootBootstrap()
          recentRootRequester.foreach(_ ! StorageRecoveryActor.RecentRoot(block, Some(header.stateRoot.value)))
          recentRootRequester = None
          Behaviors.same

        case PivotHeaderBootstrap.Failed(reason) if recentRootRequester.isDefined =>
          log.warn(s"Recovery recent-root bootstrap failed ($reason). Declining the roll; abandon path will run.")
          stopRecentRootBootstrap()
          recentRootRequester.foreach(_ ! StorageRecoveryActor.RecentRoot(0, None))
          recentRootRequester = None
          Behaviors.same

        case RecentRootTimeout(gen) if gen == recentRootGeneration && recentRootRequester.isDefined =>
          log.warn("Recovery recent-root bootstrap timed out. Declining the roll; abandon path will run.")
          stopRecentRootBootstrap()
          recentRootRequester.foreach(_ ! StorageRecoveryActor.RecentRoot(0, None))
          recentRootRequester = None
          Behaviors.same

        case BytecodeRecoveryTerminated(actor) if bytecodeActor.contains(actor) =>
          log.error("BytecodeRecoveryActor terminated unexpectedly. Treating as complete to unblock sync.")
          if storageComplete then completeRecovery()
          else runningRecovery(bytecodeActor = None, storageActor, bytecodeComplete = true, storageComplete)

        case StorageRecoveryTerminated(actor) if storageActor.contains(actor) =>
          log.error("StorageRecoveryActor terminated unexpectedly. Treating as complete to unblock sync.")
          if bytecodeComplete then completeRecovery()
          else runningRecovery(bytecodeActor, storageActor = None, bytecodeComplete, storageComplete = true)

        case _: SyncProtocol.MissingCodeNeedsBulkRecovery =>
          log.info("Bulk bytecode recovery already in progress; ignoring a repeated request.")
          Behaviors.same
        case msg if isInternalMarker(msg) =>
          // Late self/death-watch marker for a child stopped before this transition — drop silently.
          Behaviors.same
        case other =>
          // All SNAP response types (ByteCodesResponse, StorageRangesResponse, AccountRangeResponse,
          // TrieNodesResponse) are handled by explicit arms above. This arm fires only for truly
          // unexpected message types.
          log.warn("Unexpected message in runningRecovery: {}", other.getClass.getSimpleName)
          Behaviors.same
    }

    /** Start a one-shot header bootstrap for a recent block (margin back from the network head) and arm a timeout. On
      * `Completed` we reply [[StorageRecoveryActor.RecentRoot]] to the waiting recovery actor; if no peer height is
      * known yet, decline immediately so the actor's abandon path still runs.
      */
    private def maybeStartRecentRootBootstrap(
        peers: Map[
          com.chipprbots.ethereum.network.Peer,
          com.chipprbots.ethereum.network.NetworkPeerManagerActor.PeerInfo
        ]
    ): Unit =
      val snapHeights = peers.values.filter(_.remoteStatus.supportsSnap).map(_.maxBlockNumber)
      SyncController.recentRootTarget(snapHeights, RecentRootMarginBlocks) match
        case Some(recentBlock) =>
          recentRootGeneration += 1
          val gen = recentRootGeneration
          val peersClient = ctx.spawn(
            Behaviors
              .supervise(PeersClient.behavior(networkPeerManager, peerEventBus, blacklist, syncConfig))
              .onFailure[Throwable](
                // PR #1378: PeersClient is a sync backbone actor — no `.withMaxRestarts` cap.
                SupervisorStrategy.restartWithBackoff(500.millis, 10.seconds, 0.2)
              ),
            s"recovery-recent-root-peers-$gen"
          )
          val bootstrap = ctx
            .spawn(
              Behaviors
                .supervise(
                  PivotHeaderBootstrap(
                    peersClient,
                    blockchainWriter,
                    recentBlock,
                    replyTo = pivotBootstrapAdapter,
                    syncConfig,
                    preferSnapPeers = true
                  )
                )
                // PivotHeaderBootstrap must keep retrying until the pivot header arrives — no cap.
                .onFailure[Throwable](SupervisorStrategy.restartWithBackoff(2.seconds, 60.seconds, 0.1)),
              s"recovery-recent-root-bootstrap-$gen"
            )
          recentRootBootstrap = Some((peersClient, bootstrap))
          log.info(s"Recovery recent-root: fetching header for recent block $recentBlock.")
          timers.startSingleTimer(RecentRootTimeout(gen), 20.seconds)
        case None =>
          log.info("Recovery recent-root: no usable peer height yet; declining the roll.")
          recentRootRequester.foreach(_ ! StorageRecoveryActor.RecentRoot(0, None))
          recentRootRequester = None

    private def stopRecentRootBootstrap(): Unit =
      recentRootBootstrap.foreach { case (peersClient, bootstrap) =>
        ctx.stop(bootstrap)
        ctx.stop(peersClient)
      }
      recentRootBootstrap = None

    /** The best block of a chain that was PoS from genesis (#1654, [[HeadChainWeight.posFromGenesisWeight]]). Its
      * weight is the genesis weight, exactly: write it if anything else is stored, and never estimate. Without this,
      * the cascade below ran here: on Platåberget (genesis TD 0) its plausibility gate is `> 0`, so it wrote a peer's
      * TD; and Tier 3 never finds an anchor above the floor, so it retried every 30 minutes until the chain-weight pass
      * set the marker.
      */
    private def calibrateToGenesisWeight(
        head: BlockHeader,
        exact: ChainWeight,
        peerTD: BigInt,
        peerMaxBlock: BigInt
    ): Unit =
      val stored = blockchainReader.getChainWeightByHash(head.hash)
      if stored.contains(exact) then
        calibrationSucceeded = true
        lastCalibrationSource = "GENESIS_WEIGHT"
        log.info(
          "TD_CALIBRATION_SKIPPED: block={} storedTD={} reason=GENESIS_WEIGHT (the chain was PoS from genesis: every " +
            "block weighs the genesis weight); peer data td={} block={} not used",
          head.number,
          exact.totalDifficulty,
          peerTD,
          peerMaxBlock
        )
      else if writeCalibratedWeight(head, exact) then
        calibrationSucceeded = true
        lastCalibrationSource = "GENESIS_WEIGHT"
        val before = stored.map(_.totalDifficulty.value.toString).getOrElse("none")
        log.warn(
          "CHAIN_WEIGHT_REPAIRED: block={} before={} after={} source=GENESIS_WEIGHT (the chain was PoS from genesis: " +
            "every block weighs the genesis weight; peer data td={} block={} not used)",
          head.number,
          before,
          exact.totalDifficulty,
          peerTD,
          peerMaxBlock
        )
        log.info(
          s"TD_CALIBRATION_SUMMARY: block=${head.number} before=$before after=${exact.totalDifficulty} " +
            s"source=GENESIS_WEIGHT attempt=$tdCalibrationAttempt"
        )

    /** The calibration cascade, for a best block with no exact stored weight (#1654: `HeadChainWeight.Status.NoExact`):
      * a pivot whose parent headers are not backfilled, or a chain built on a proxy base. Tiers 1 and 2 write an
      * ESTIMATE and log it as one.
      *   - Tier 1 (peerTD > 0, peerMaxBlock > 0): linear interpolation `peerTD * ourBest / peerMaxBlock`. The pair
      *     comes from a NewBlock or from an ETH68 STATUS plus the peer's probed height; either way the result is a
      *     guess. A post-merge best block (difficulty 0, terminal TD configured) takes the peer's TD unscaled (#1654
      *     H6(1), [[HeadChainWeight.peerEstimate]]).
      *   - Tier 2 (peerTD > 0, peerMaxBlock = 0): the peer's TD as is (ETH68 STATUS only).
      *   - Tier 3 (peerTD = 0): pure ETH69 sentinel — accumulate from a plausible local anchor
      *     ([[calibrateTDFromLocalChain]]).
      */
    private def calibrateChainWeightEstimate(peerTD: BigInt, peerMaxBlock: BigInt): Unit =
      if peerTD > BigInt(0) then
        // Tier 1 or 2: ETH68 peer TD available
        blockchainReader.getBestBlock.foreach { bestBlock =>
          val genesisWeight: BigInt = blockchainReader
            .getChainWeightByHash(blockchainReader.genesisHeader.hash)
            .map(_.totalDifficulty.value)
            .getOrElse(blockchainReader.genesisHeader.difficulty.value)
          val calibratedTD = HeadChainWeight.peerEstimate(
            bestBlock.header,
            peerTD,
            peerMaxBlock,
            configBuilder.blockchainConfig.terminalTotalDifficulty
          )
          val storedTD: BigInt = blockchainReader
            .getChainWeightByHash(bestBlock.header.hash)
            .map(_.totalDifficulty.value)
            .getOrElse(BigInt(0))
          if calibratedTD > genesisWeight * BigInt(1000) &&
            writeCalibratedWeight(
              bestBlock.header,
              com.chipprbots.ethereum.domain.ChainWeight.totalDifficultyOnly(calibratedTD)
            )
          then
            networkBestTD = peerTD
            calibrationSucceeded = true
            lastCalibrationSource =
              if peerMaxBlock > BigInt(0) then "PEER_INTERPOLATED_ESTIMATE" else "PEER_STATUS_ESTIMATE"
            log.warn(
              "CHAIN_WEIGHT_CALIBRATED_ON_RESUME: bestBlock={} storedTD={} calibratedTD={} source={} (an ESTIMATE: " +
                "no exact weight is stored for this block or its parent)",
              bestBlock.header.number,
              storedTD,
              calibratedTD,
              lastCalibrationSource
            )
            val ratio = if storedTD > BigInt(0) then (calibratedTD / storedTD).toString else "∞"
            log.info(
              s"TD_CALIBRATION_SUMMARY: block=${bestBlock.header.number} before=$storedTD after=$calibratedTD ratio=$ratio source=$lastCalibrationSource attempt=$tdCalibrationAttempt"
            )
        }
      else
        // Tier 3: pure ETH69 sentinel (0, 0) — no ETH68 peer TD seen at T+30s (or retry).
        // Walk backward via parentHash to find a ChainDownloader anchor with plausible TD,
        // accumulate forward. Retry every 30min until success or ETH68 peers appear.
        tdCalibrationAttempt += 1
        val succeeded = calibrateTDFromLocalChain()
        if succeeded then
          calibrationSucceeded = true
          lastCalibrationSource = "LOCAL_CHAIN"
        else scheduleTDCalibrationRetry()

    /** Walk backward from bestBlock via parentHash until a header with a plausible stored TD is found (ChainDownloader
      * anchor), then accumulate forward to produce the correct cumulative TD for bestBlock. Writes the result to DB if
      * plausible.
      *
      * MUST NOT use getBlockHeaderByNumber: SNAP-synced nodes lack the number→hash canonical index for pre-pivot
      * blocks. Returns None silently for most such blocks, causing silent TD undercount. Uses parentHash traversal
      * exclusively (same approach as ChainWeightRepair).
      *
      * Returns true if calibration wrote a value; false if deferred (caller schedules retry).
      */
    private def calibrateTDFromLocalChain(): Boolean =
      val MaxWalkBlocks = 10000
      // An anchor's stored TD must clear HeadChainWeight.floor: the genesis weight plus MinimumDifficulty (131072) per
      // block, a bound no real Ethash chain is under. It rejects BLOCK_NUMBER_PROXY and genesis-proxy weights at any
      // height. It replaces a fixed 10^13 per block (#1654), which mainnet cleared but Mordor (~9e6 per block, TD
      // ~1.75e14 at block 15M) never could, so this fallback never found an anchor there.
      val anchorGenesisWeight: BigInt = HeadChainWeight.genesisWeight(blockchainReader)

      blockchainReader.getBestBlockHeader match
        case None =>
          log.warn("TIMED_CALIBRATION_LOCAL: no best block header — skipping (attempt={})", tdCalibrationAttempt)
          false

        case Some(bestHeader) =>
          log.info(
            "TIMED_CALIBRATION_LOCAL: tier3 triggered (attempt={}), bestBlock={}, walking up to {} blocks",
            tdCalibrationAttempt,
            bestHeader.number,
            MaxWalkBlocks
          )

          // Phase 1: walk backward via parentHash collecting headers above the anchor.
          // headersAboveAnchor is DESCENDING (bestBlock first); reversed in Phase 2.
          val headersAboveAnchor =
            scala.collection.mutable.ArrayBuffer.empty[com.chipprbots.ethereum.domain.BlockHeader]
          var cur = bestHeader
          var anchorTD = BigInt(0)
          var anchorFound = false
          var abort = false

          while !anchorFound && !abort do
            blockchainReader.getChainWeightByHash(cur.hash) match
              case Some(cw)
                  if HeadChainWeight.isPlausible(cur.number.value, cw.totalDifficulty.value, anchorGenesisWeight) =>
                anchorTD = cw.totalDifficulty.value
                anchorFound = true
                log.debug(
                  "TIMED_CALIBRATION_LOCAL: anchor found at block={} anchorTD={} (walked {} headers)",
                  cur.number,
                  anchorTD,
                  headersAboveAnchor.size
                )
              case _ =>
                if headersAboveAnchor.size >= MaxWalkBlocks then abort = true
                else
                  headersAboveAnchor += cur
                  blockchainReader.getBlockHeaderByHash(cur.parentHash) match
                    case Some(parent) => cur = parent
                    case None =>
                      log.warn(
                        "TIMED_CALIBRATION_LOCAL: parentHash chain broken at block={} hash={} — aborting (attempt={})",
                        cur.number,
                        cur.hash,
                        tdCalibrationAttempt
                      )
                      abort = true

          if abort then
            log.warn(
              "TIMED_CALIBRATION_LOCAL: no plausible anchor within {} blocks of bestBlock={} — deferring (attempt={})",
              MaxWalkBlocks,
              bestHeader.number,
              tdCalibrationAttempt
            )
            false
          else
            // Phase 2: accumulate forward from anchorTD over headers collected above anchor.
            // All headers guaranteed present (collected via parentHash traversal — no silent skips).
            var td = anchorTD
            headersAboveAnchor.reverseIterator.foreach(h => td += h.difficulty.value)

            val genesisWeight: BigInt = blockchainReader
              .getChainWeightByHash(blockchainReader.genesisHeader.hash)
              .map(_.totalDifficulty.value)
              .getOrElse(blockchainReader.genesisHeader.difficulty.value)

            if td > genesisWeight * BigInt(1000) then
              val storedTD: BigInt = blockchainReader
                .getChainWeightByHash(bestHeader.hash)
                .map(_.totalDifficulty.value)
                .getOrElse(BigInt(0))
              // Not written while the chain-weight invariant stands (#1654): then there is nothing to calibrate.
              if writeCalibratedWeight(bestHeader, com.chipprbots.ethereum.domain.ChainWeight.totalDifficultyOnly(td))
              then
                log.info(
                  "CHAIN_WEIGHT_CALIBRATED_LOCAL: anchor={} gap={} calibratedTD={} source=LOCAL_CHAIN_ACCUMULATION attempt={}",
                  cur.number,
                  headersAboveAnchor.size,
                  td,
                  tdCalibrationAttempt
                )
                val tdRatio = if storedTD > BigInt(0) then (td / storedTD).toString else "∞"
                log.info(
                  s"TD_CALIBRATION_SUMMARY: block=${bestHeader.number} before=$storedTD after=$td ratio=$tdRatio source=LOCAL_CHAIN attempt=$tdCalibrationAttempt"
                )
              true
            else
              log.warn(
                "TIMED_CALIBRATION_LOCAL: computed td={} below plausibility threshold (genesisWeight={}) — aborting write (attempt={})",
                td,
                genesisWeight,
                tdCalibrationAttempt
              )
              false

    /** Schedule a retry of CalibrateChainWeightNow in 30 minutes. Called when tier-3 calibration defers
      * (ChainDownloader gap > 10K blocks). The retry loop continues until calibration succeeds or ETH68 peers appear.
      */
    private def scheduleTDCalibrationRetry(): Unit =
      ctx.system.scheduler.scheduleOnce(
        java.time.Duration.ofMinutes(30),
        () => networkPeerManager ! com.chipprbots.ethereum.network.NetworkPeerManagerActor.CalibrateChainWeightNowCmd,
        ctx.executionContext
      )
      val bestBlockNum = blockchainReader.getBestBlockHeader.map(_.number).getOrElse(BigInt(0))
      log.info(
        "TIMED_CALIBRATION_LOCAL: retry #{} scheduled in 30min (ChainDownloader advancing, current bestBlock={})",
        tdCalibrationAttempt + 1,
        bestBlockNum
      )

  // scalastyle:on number.of.methods

  /** Pick the block to roll the recovery storage download onto: `margin` blocks back from the highest known
    * SNAP-capable peer head (so the target is inside peers' snapshot serve window), clamped to >= 1. Returns None when
    * no peer height is known yet, so the caller declines the roll and lets the abandon path run. Pure for testability.
    */
  private[sync] def recentRootTarget(snapPeerHeights: Iterable[BigInt], margin: BigInt): Option[BigInt] =
    snapPeerHeights.filter(_ > 0).maxOption.map(best => (best - margin).max(1))

  /** Startup sync mode resolved by [[selectSyncMode]]. */
  private[sync] enum SyncMode:
    case Snap, Regular

  /** The startup sync mode: SNAP when `do-snap-sync` is set, otherwise regular sync. Peer counts play no part — SNAP
    * handles small pools itself (a 2-peer ETC pool syncs), and when it cannot proceed it goes dormant and retries on a
    * fresh pivot rather than switching mode.
    */
  private[sync] def selectSyncMode(config: SyncConfig): SyncMode =
    if config.doSnapSync then SyncMode.Snap else SyncMode.Regular
