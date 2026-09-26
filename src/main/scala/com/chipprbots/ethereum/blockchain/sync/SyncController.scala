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

import scala.concurrent.duration.*
import scala.util.Try

import com.chipprbots.ethereum.blockchain.sync.regular.RegularSync
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncConfig
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.BootstrapComplete
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.PivotBootstrapFailed
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.StartRegularSyncBootstrap
import com.chipprbots.ethereum.blockchain.sync.snap.ChainDownloader
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.StartRegularSyncBootstrapByHash
import com.chipprbots.ethereum.consensus.ConsensusAdapter
import com.chipprbots.ethereum.consensus.engine.DesignatedHead
import com.chipprbots.ethereum.consensus.engine.ForkChoiceManager
import com.chipprbots.ethereum.consensus.mess.MESSConfig
import com.chipprbots.ethereum.consensus.validators.Validators
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.FastSyncStateStorage
import com.chipprbots.ethereum.db.storage.FlatSlotStorage
import com.chipprbots.ethereum.db.storage.StateStorage
import com.chipprbots.ethereum.domain.Blockchain
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.BlockchainWriter
import com.chipprbots.ethereum.domain.TrieRoot
import com.chipprbots.ethereum.ledger.BranchResolution
import com.chipprbots.ethereum.nodebuilder.BlockchainConfigBuilder
import com.chipprbots.ethereum.utils.Config
import com.chipprbots.ethereum.utils.Config.SyncConfig
import com.chipprbots.ethereum.blockchain.sync.snap.StorageScheme
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
      externalSchedulerOpt: Option[Scheduler] = None
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
          externalSchedulerOpt
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
      externalSchedulerOpt: Option[Scheduler]
  ):
    // scalastyle:on parameter.number

    // Plain SLF4J logger (safe from any thread — used inside scheduled callbacks below).
    private val log = org.slf4j.LoggerFactory.getLogger(getClass)

    // Generation counters for actor names to prevent Pekko name collisions
    // (context.stop is async — new actors can race with still-stopping ones).
    private var bootstrapGeneration: Long = 0
    private var syncGeneration: Long = 0

    // Recovery recent-root roll (Task #5/#6): post-SNAP storage recovery asks for a recent canonical root
    // when the saved pivot has aged out of peers' serve window. We fetch a recent header via
    // PivotHeaderBootstrap (inline in `runningRecovery` — no transition into the deadlock-prone bootstrap
    // state) and reply with StorageRecoveryActor.RecentRoot. Only one request is serviced at a time.
    private var recentRootRequester: Option[TypedActorRef[StorageRecoveryActor.Command]] = None
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

    // Whether SNAP should consume CL-driven pivot selection. Captured once at construction
    // because both `syncConfig` and the chain config are stable for the actor's lifetime.
    private val isPoSChain: Boolean = configBuilder.blockchainConfig.terminalTotalDifficulty.isDefined
    private val clPivotEnabled: Boolean = isPoSChain && forkChoiceManagerOpt.isDefined

    // PoS fork choice for the p2p import path, or None. Same conjunction as clPivotEnabled above, but note which half
    // does the work: a running Node always supplies a ForkChoiceManager (Node.forkChoiceManagerForSync), so the
    // OPERATIVE gate is `isPoSChain` — a configured terminal-total-difficulty, set in eth-chain.conf and
    // sepolia-chain.conf and in NO PoW chain config. On ETC/Mordor/Gorgoroth this is None for the life of the node,
    // so `BranchResolution`'s PoS arm predicate is always false there. See DesignatedHead.
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
            SNAPSyncConfig()
      val networkType = configBuilder.blockchainConfig.networkType
      val expectedScheme = if networkType == NetworkType.ETH then StorageScheme.Path else StorageScheme.Hash
      require(
        config.storageScheme == expectedScheme,
        s"storageScheme=${config.storageScheme} does not match expected $expectedScheme " +
          s"for networkType=$networkType — check sync.snap-sync.storage-scheme in reference.conf"
      )
      config

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
            log.warn(
              "SNAP finalization aborted (state root mismatch). Clearing sync state and restarting SNAP with a fresh pivot."
            )
            // spec 004 MUST-FIX: clear the healing serve-root latch on every exit from runningSnapSync. The new SNAP
            // actor started below gets a fresh latch, so the stale requester here must not linger.
            abortHealingServeRootRequest("SNAP healing impossible — restarting snap sync")
            appStateStorage.clearSnapSyncDone().commit()
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
        case SyncProtocol.RegularSyncStuck(blockNumber, missingHash) =>
          // Regular sync can't make progress: state-node recovery has exhausted on the same hash
          // 3+ times. Local parent state is too far behind canonical tip for any peer's snap-serve
          // window, so trie-node fetches keep returning empty. Only viable recovery is to re-run
          // SNAP from a recent pivot. Kill regular sync, clear both SnapSyncDone AND the legacy
          // FastSyncDone (so a subsequent restart re-evaluates start() and enters SNAP rather than
          // going straight back to regular sync), then start SNAP directly.
          log.error(
            "Regular sync stuck on block {} (missing {}). Re-triggering SNAP sync from a recent pivot.",
            blockNumber,
            missingHash
          )
          ctx.stop(regularSync)
          appStateStorage.clearSnapSyncDone().commit()
          appStateStorage.clearFastSyncDone().commit()
          startSnapSync(minPivotBlock = Some(blockNumber))
        case SyncProtocol.CalibrateChainWeightFromPeer(peerTD, peerMaxBlock) =>
          // Three-tier calibration cascade:
          //   Tier 1 (peerTD > 0, peerMaxBlock > 0): exact interpolation from NewBlock TD+blockNum
          //   Tier 2 (peerTD > 0, peerMaxBlock = 0): ETH68 STATUS only — peerTD direct (<0.05% over)
          //   Tier 3 (peerTD = 0): pure ETH69 sentinel — compute from local chain DB via parentHash traversal
          if peerTD > BigInt(0) then
            // Tier 1 or 2: ETH68 peer TD available
            blockchainReader.getBestBlock.foreach { bestBlock =>
              val genesisWeight: BigInt = blockchainReader
                .getChainWeightByHash(blockchainReader.genesisHeader.hash)
                .map(_.totalDifficulty.value)
                .getOrElse(blockchainReader.genesisHeader.difficulty.value)
              val calibratedTD =
                if peerMaxBlock > BigInt(0) then peerTD * bestBlock.header.number.value / peerMaxBlock
                else peerTD
              if calibratedTD > genesisWeight * BigInt(1000) then
                val storedTD: BigInt = blockchainReader
                  .getChainWeightByHash(bestBlock.header.hash)
                  .map(_.totalDifficulty.value)
                  .getOrElse(BigInt(0))
                blockchainWriter
                  .storeChainWeight(
                    bestBlock.header.hash,
                    com.chipprbots.ethereum.domain.ChainWeight.totalDifficultyOnly(calibratedTD)
                  )
                  .commit()
                networkBestTD = peerTD
                calibrationSucceeded = true
                lastCalibrationSource = if peerMaxBlock > BigInt(0) then "NEWBLOCK_EXACT" else "ETH68_STATUS"
                log.info(
                  "CHAIN_WEIGHT_CALIBRATED_ON_RESUME: bestBlock={} storedTD={} calibratedTD={} source={}",
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
      case _: SyncProtocol.RegularSyncStuck => true
      case _                                => false

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
      * flags (dangling-best recovery, `clearDoneOnStart`), so it sees them as persisted.
      */
    private def adoptLeftoverFastSyncRecord(): Unit =
      if fastSyncStateStorage.hasSyncState then
        val best = appStateStorage.getBestBlockNumber()
        val stranded =
          best > 0 && !appStateStorage.isSnapSyncDone() && !appStateStorage.isFastSyncDone() &&
            !appStateStorage.isSnapSyncAccountsComplete() && !appStateStorage.getSnapSyncPivotBlock().contains(best)
        val floor =
          if stranded then
            log.warn(
              "Fast sync was running when this node was upgraded; fast sync was removed. Its best block {} has no " +
                "state behind it, so SNAP may not take a pivot below {}. Deleting fast sync's leftover progress record.",
              best,
              best + 1
            )
            appStateStorage.putSnapSyncMinPivotBlock(best + 1)
          else
            log.info("Deleting fast sync's leftover progress record: SNAP or a finished sync owns this database")
            appStateStorage.emptyBatchUpdate
        floor.and(fastSyncStateStorage.removeSyncState()).commit()

    /** SNAP's persisted pivot floor, unless it is spent.
      *
      * SNAP commits pivots at or above its floor, so once its saved pivot reaches the floor the floor has done its job.
      * It is cleared then instead of being applied again: a restart mid-heal must not raise the bar above the pivot
      * SNAP already holds.
      */
    private def liveSnapPivotFloor(): Option[BigInt] =
      appStateStorage.getSnapSyncMinPivotBlock().flatMap { floor =>
        if appStateStorage.getSnapSyncPivotBlock().exists(_ >= floor) then
          appStateStorage.clearSnapSyncMinPivotBlock().commit()
          None
        else Some(floor)
      }

    /** SNAP has finalized: whatever pivot floor it started with is spent. */
    private def clearSnapPivotFloor(): Unit =
      appStateStorage.clearSnapSyncMinPivotBlock().commit()

    def start(): Behavior[Command] =
      val startMode = SyncController.selectSyncMode(syncConfig)

      adoptLeftoverFastSyncRecord()

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
        appStateStorage.clearSnapSyncDone().commit()
        appStateStorage.clearFastSyncDone().commit()

      appStateStorage.putSyncStartingBlock(appStateStorage.getBestBlockNumber()).commit()

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
      if syncConfig.doSnapSync && System.getProperty("fukuii.snap.clearDoneOnStart", "false").toBoolean then
        if appStateStorage.isSnapSyncDone() then
          log.warn("fukuii.snap.clearDoneOnStart=true: clearing SnapSyncDone to re-enter SNAP healing")
          appStateStorage.clearSnapSyncDone().commit()

      // FastSyncDone is a legacy flag and does not pick the mode: with SNAP on and not done, SNAP starts even where
      // fast sync once finished. Starting SNAP never cleared the flag, so it can sit on a node that is part-way through
      // SNAP (best = a pivot whose state is still downloading), and fast sync could set it over an incomplete trie.
      // Regular sync on such a node executes blocks against missing state. Nothing sets the flag any more; the
      // recovery paths below still clear it.
      (appStateStorage.isSnapSyncDone(), startMode) match
        case (false, SyncMode.Snap) =>
          // A live floor means the best block came from an interrupted fast sync and has no state: SNAP must pick a
          // pivot above it, as after RegularSyncStuck, or its "pivot not ahead of local state" check takes that block
          // for a synced chain and hands the node to regular sync without state.
          val floor = liveSnapPivotFloor()
          floor.foreach(f => log.info("SNAP starts with a pivot floor of {} (left by an interrupted fast sync)", f))
          startSnapSync(minPivotBlock = floor)
        case (true, SyncMode.Snap) =>
          log.warn("do-snap-sync is true but SNAP sync already completed")
          // Diagnostic: log stored SNAP sync state root vs pivot block state root
          val snapStateRoot = appStateStorage.getSnapSyncStateRoot()
          val bestBlockNum = appStateStorage.getBestBlockNumber()
          val bestBlockHeader = blockchainReader.getBlockHeaderByNumber(bestBlockNum)
          val pivotStateRoot = bestBlockHeader.map(_.stateRoot)
          log.info(
            "SNAP state root diagnostic: stored snapStateRoot={}, pivotBlockStateRoot={}, bestBlock={}, match={}",
            snapStateRoot.map(r => r.take(8).toArray.map("%02x".format(_)).mkString).getOrElse("none"),
            pivotStateRoot.map(r => r.value.take(8).toArray.map("%02x".format(_)).mkString).getOrElse("none"),
            bestBlockNum,
            snapStateRoot == pivotStateRoot.map(_.value)
          )
          // After SNAP sync with deferred merkleization + pivot refreshes, the finalized trie root
          // may differ from the pivot block header's stateRoot. The trie nodes are stored under
          // the finalized root's hash, but the pivot header references the original (now orphaned) root.
          // Fix: substitute the finalized root into the pivot block header.
          bestBlockHeader.foreach { header =>
            val mptStorage = stateStorage.getReadOnlyStorage
            val pivotRootExists =
              try
                mptStorage.get(header.stateRoot.toArray); true
              catch case _: Exception => false
            log.info(
              "State root availability check: pivotRoot({})={}",
              header.stateRoot.value.take(8).toArray.map("%02x".format(_)).mkString,
              if pivotRootExists then "EXISTS" else "MISSING"
            )
            if !pivotRootExists then
              val finalizedRoot = appStateStorage.getSnapSyncFinalizedRoot()
              finalizedRoot match
                case Some(fRoot) =>
                  val fRootExists =
                    try
                      mptStorage.get(fRoot.toArray); true
                    catch case _: Exception => false
                  log.info(
                    "Finalized trie root {} availability: {}",
                    fRoot.take(8).toArray.map("%02x".format(_)).mkString,
                    if fRootExists then "EXISTS" else "MISSING"
                  )
                  if fRootExists then
                    log.warn(
                      "Substituting finalized trie root {} into pivot block header (replacing missing root {})",
                      fRoot.take(8).toArray.map("%02x".format(_)).mkString,
                      header.stateRoot.value.take(8).toArray.map("%02x".format(_)).mkString
                    )
                    val updatedHeader = header.copy(stateRoot = TrieRoot(fRoot))
                    blockchainWriter.storeBlockHeader(updatedHeader).commit()
                case None =>
                  log.error(
                    "Pivot state root {} MISSING and no finalized root stored! " +
                      "Database is in an unrecoverable state — clear data and re-sync.",
                    header.stateRoot.value.take(8).toArray.map("%02x".format(_)).mkString
                  )
            else
              // Symmetric case (Run-26): pivot root EXISTS in MPT but differs from snapStateRoot.
              // The downloaded account trie is stored under snapStateRoot; update the pivot header
              // to match so the startup diagnostic passes and regular sync reads the correct trie.
              snapStateRoot.foreach { snapRoot =>
                if snapRoot != header.stateRoot.value then
                  val snapRootExists =
                    try
                      mptStorage.get(snapRoot.toArray); true
                    catch case _: Exception => false
                  log.info(
                    "snapStateRoot({}) availability: {}",
                    snapRoot.take(8).toArray.map("%02x".format(_)).mkString,
                    if snapRootExists then "EXISTS" else "MISSING"
                  )
                  if snapRootExists then
                    log.warn(
                      "snapStateRoot({}) differs from pivotHeader.stateRoot({}) — " +
                        "updating pivot block header to use downloaded state root.",
                      snapRoot.take(8).toArray.map("%02x".format(_)).mkString,
                      header.stateRoot.value.take(8).toArray.map("%02x".format(_)).mkString
                    )
                    val updatedHeader = header.copy(stateRoot = TrieRoot(snapRoot))
                    blockchainWriter.storeBlockHeader(updatedHeader).commit()
              }
          }
          val needBytecode = !appStateStorage.isBytecodeRecoveryDone()
          val needStorage = !appStateStorage.isStorageRecoveryDone()
          if needBytecode || needStorage then startRecovery(needBytecode, needStorage)
          else startRegularSync()._2
        case (_, SyncMode.Regular) =>
          liveSnapPivotFloor().foreach { floor =>
            log.error(
              "Fast sync was in progress when this node was upgraded; fast sync was removed. Its best block {} " +
                "was downloaded without state, and do-snap-sync is off, so regular sync will have to fetch the " +
                "missing state node by node. Enable fukuii.sync.do-snap-sync to download it with SNAP.",
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
            syncController = snapAdapter
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

      // Operator escape hatch: seed exact chain-weight values before RegularSync starts.
      // Used when a node finished SNAP sync with a proxy TD (e.g. no ETH68 peers at finalize
      // time) and needs correcting without a full re-sync.
      // Format: -Dfukuii.seed-chain-weights=HASH1:TD1,HASH2:TD2 (hash hex, TD decimal)
      // Get canonical values from a trusted local client:
      //   curl -s localhost:8545 -d '{"method":"eth_getBlockByNumber","params":["latest",false],"id":1}' \
      //     | jq -r '.result | "\(.hash):\(.totalDifficulty | ltrimstr("0x") | tonumber)"'
      Option(System.getProperty("fukuii.seed-chain-weights")).foreach { seeds =>
        seeds.split(",").foreach { seed =>
          seed.trim.split(":") match
            case Array(hashHex, tdStr) =>
              // INFO-6: guard against NumberFormatException from a malformed fukuii.seed-chain-weights property.
              Try(BigInt(tdStr.trim)).toOption match
                case Some(td) =>
                  val hash =
                    com.chipprbots.ethereum.domain.BlockHash(
                      ByteString(com.chipprbots.ethereum.utils.Hex.decode(hashHex.stripPrefix("0x")))
                    )
                  blockchainWriter
                    .storeChainWeight(hash, com.chipprbots.ethereum.domain.ChainWeight.totalDifficultyOnly(td))
                    .commit()
                  log.warn("seed-chain-weights: wrote TD={} for hash={}...", td, hashHex.take(16))
                case None =>
                  log.warn("seed-chain-weights: invalid TD value '{}' in entry '{}' — skipping", tdStr.trim, seed.trim)
            case _ =>
              log.warn("seed-chain-weights: invalid entry '{}' (expected HASH:TD)", seed.trim)
        }
      }

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
            ctx.self
          ),
          s"regular-sync-$syncGeneration",
          DispatcherSelector.fromConfig("sync-dispatcher")
        )
      regularSync ! SyncProtocol.Start
      ctx.watchWith(regularSync, RegularSyncTerminated(regularSync))
      // After SNAP completes, chain backfill (#1162) writes headers / bodies / receipts in the
      // background. If the node was killed mid-backfill, persisted cursors (#1169) tell us how
      // far it got — spawn a standalone ChainDownloader to finish the job alongside regular sync.
      // Suppressed when called from the SnapSyncFinalized path: SNAPSyncController already owns
      // the live backfill actor in that flow.
      val nextBehavior =
        if resumeBackfill then maybeStartBackfillResume(regularSync).getOrElse(runningRegularSync(regularSync))
        else runningRegularSync(regularSync)
      (regularSync, nextBehavior)

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

    def startRecovery(needBytecode: Boolean, needStorage: Boolean): Behavior[Command] =
      syncGeneration += 1
      val stateRootOpt = appStateStorage.getSnapSyncStateRoot()
      val pivotBlockOpt = appStateStorage.getSnapSyncPivotBlock()

      (stateRootOpt, pivotBlockOpt) match
        case (Some(stateRoot), Some(pivotBlock)) =>
          log.info(
            s"[SNAP-RECOVERY] Phase starting — bytecodeNeeded=$needBytecode storageNeeded=$needStorage " +
              s"generation=$syncGeneration — polling for snap-capable peers every 5s"
          )

          val snapSyncConfig = loadSnapSyncConfig()

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
            runningCombinedScan(needBytecode, needStorage, stateRoot, pivotBlock, snapSyncConfig)
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
                            stateStorage,
                            evmCodeStorage,
                            appStateStorage,
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
                            stateStorage,
                            appStateStorage,
                            flatSlotStorage,
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

        case _ =>
          log.warn("Cannot run recovery: missing stateRoot or pivotBlock. Marking done and proceeding.")
          if needBytecode then appStateStorage.bytecodeRecoveryDone().commit()
          if needStorage then appStateStorage.storageRecoveryDone().commit()
          startRegularSync()._2

    /** Wait for the combined scan to report both gap sets, then spawn download-only recovery actors for whichever
      * phases still have gaps. Phases the scan found already complete are marked done immediately.
      */
    def runningCombinedScan(
        needBytecode: Boolean,
        needStorage: Boolean,
        stateRoot: ByteString,
        pivotBlock: BigInt,
        snapSyncConfig: com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncConfig
    ): Behavior[Command] = Behaviors.receive { (_, cmd) =>
      val msg = unwrap(cmd)
      msg match
        case CombinedRecoveryScanActor.CombinedScanComplete(byteGaps, storGaps) =>
          val effByte = if needBytecode then byteGaps else Nil
          val effStor = if needStorage then storGaps else Nil
          log.info(s"Combined recovery scan reported ${effByte.size} bytecode gaps, ${effStor.size} storage gaps.")
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
                          stateStorage,
                          evmCodeStorage,
                          appStateStorage,
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
                          stateStorage,
                          appStateStorage,
                          flatSlotStorage,
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
      // Conservative ETC lower bound: ~10^13 difficulty per block.
      // Rejects BLOCK_NUMBER_PROXY (blockNum ≈ 24.7M << correct TD ≈ 24.64×10^21) and
      // RegularSync wrong TDs built on a proxy base.
      val MinTDPerBlock = BigInt("10000000000000")

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
              case Some(cw) if cw.totalDifficulty.value > cur.number.value * MinTDPerBlock =>
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
              blockchainWriter
                .storeChainWeight(bestHeader.hash, com.chipprbots.ethereum.domain.ChainWeight.totalDifficultyOnly(td))
                .commit()
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

    def startRegularSyncForBootstrap(): TypedActorRef[RegularSync.Command] =
      log.info("Starting regular sync for SNAP sync bootstrap")

      // Version the child names so a re-invocation does not collide with a prior
      // instance whose context.stop has not yet completed (InvalidActorNameException).
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
            ctx.self
          ),
          s"regular-sync-bootstrap-$gen"
        )
      regularSync ! SyncProtocol.Start
      regularSync
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
