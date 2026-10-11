package com.chipprbots.ethereum.blockchain.sync.snap.controller

import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

import org.apache.pekko.actor.testkit.typed.Effect
import org.apache.pekko.actor.testkit.typed.scaladsl.BehaviorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.ManualTime
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.TestInbox
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.TimerScheduler
import org.apache.pekko.util.ByteString

import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*

import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.PeerListSupportNg
import com.chipprbots.ethereum.blockchain.sync.SyncProtocol
import com.chipprbots.ethereum.blockchain.sync.snap.ChainDownloader
import com.chipprbots.ethereum.blockchain.sync.snap.HeapPressureHysteresis
import com.chipprbots.ethereum.blockchain.sync.snap.OldGenReading
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPRequestTracker
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncConfig
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.*
import com.chipprbots.ethereum.blockchain.sync.snap.SnapHeapWatchdog
import com.chipprbots.ethereum.blockchain.sync.snap.SnapIntakeBudget
import com.chipprbots.ethereum.blockchain.sync.snap.StateValidator
import com.chipprbots.ethereum.blockchain.sync.snap.SyncProgressMonitor
import com.chipprbots.ethereum.blockchain.sync.snap.actors.AccountRangeCoordinator
import com.chipprbots.ethereum.blockchain.sync.snap.actors.ByteCodeCoordinator
import com.chipprbots.ethereum.blockchain.sync.snap.actors.StorageRangeCoordinator
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.FlatSlotStorage
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.db.storage.PathNodeStorage
import com.chipprbots.ethereum.db.storage.StateStorage
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.BlockchainWriter
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.domain.TrieRoot
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.PeerInfo
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.RemoteStatus
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerActor
import com.chipprbots.ethereum.network.PeerEventBusActor
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.testing.Tags.UnitTest
import com.chipprbots.ethereum.utils.Config.SyncConfig

/** Everything `SyncLifecycle` needs, as a stub (spec 016 FR-016 (b)): its `SyncLifecycleState`, the hubs, the
  * environment, the coordinator handles, the phase flags and the callee Api traits. State is plain vars; an ephemeral
  * data source backs the app-state store; stopped children, cancelled timer keys and the Api calls are recorded, the
  * calls in one ordered log; the members these tests never reach throw.
  */
private[snap] class StubSyncLifecycleState(
    val ctx: ActorContext[SNAPSyncController.Command],
    val timers: TimerScheduler[SNAPSyncController.Command],
    val snapSyncConfig: SNAPSyncConfig,
    scheduler: org.apache.pekko.actor.Scheduler
) extends SyncLifecycleState
    with SnapSharedState
    with SnapControllerEnv
    with CoordinatorHandles
    with PhaseFlags
    with HandshakedPeersApi
    with HealedCodeHoldApi
    with ShutdownApi
    with SnapStartApi
    with StagnationResetApi:

  /** Every recorded call, in order. */
  val calls: mutable.ArrayBuffer[String] = mutable.ArrayBuffer.empty

  // SyncLifecycleState
  var criticalFailureCount: Int = 0
  var accountsAtLastCriticalFailure: Long = 0L
  var dormantRetryCount: Int = 0
  var consecutivePivotRefreshes: Int = 0
  var bootstrapRetryCount: Int = 0
  var pendingPivotRefresh: Option[(BigInt, String)] = None
  var pivotProbeRequestId: Option[BigInt] = None
  var pendingProbeCommit: Option[(BigInt, BlockHeader, String)] = None
  var proactiveRollNeedsProbe: Boolean = false
  var probeAttemptCount: Int = 0
  var lastProbeAttemptMs: Long = 0L
  var healingServeRootRequestInFlight: Boolean = false
  var lastHealingServeRootBlock: Option[BigInt] = None
  var healingValidatedRoot: Option[TrieRoot] = None
  val healingWalkLocalOnly: AtomicBoolean = new AtomicBoolean(false)
  var bytecodesEstimatedTotal: Long = 0L
  var mptStorage: Option[MptStorage] = None
  var coordinatorGeneration: Long = 0L
  var validationGeneration: Long = 0L
  var validationInProgress: Boolean = false
  var snapPeerEvictionStarted: Boolean = false
  var snapServerPeersSchedulerStarted: Boolean = false
  val cancelledTimerKeys: mutable.ArrayBuffer[Any] = mutable.ArrayBuffer.empty
  def cancelSyncTimers(keys: Any*): Unit = cancelledTimerKeys ++= keys
  def BootstrapCheckKey: String = "bootstrap-check"
  def PivotBootstrapRetryKey: String = "pivot-bootstrap-retry"
  def SnapCapabilityCheckKey: String = "snap-capability-check"
  def peerEventArms: PartialFunction[Command, Behavior[Command]] = { case PollHandshakedPeers =>
    calls += "peerEventArms(PollHandshakedPeers)"
    Behaviors.same
  }

  // SnapSharedState
  var pivotBlock: Option[BigInt] = None
  var stateRoot: Option[TrieRoot] = None
  var currentPhase: SyncPhase = SyncPhase.AccountRangeSync
  val progressMonitor: SyncProgressMonitor = new SyncProgressMonitor(scheduler)
  val requestTracker: SNAPRequestTracker = new SNAPRequestTracker()(scheduler)

  // SnapControllerEnv (ctx, timers and snapSyncConfig are constructor vals)
  private val dataSource = EphemDataSource()
  def syncConfig: SyncConfig = notUsed("syncConfig")
  val asyncLog: org.slf4j.Logger = org.slf4j.LoggerFactory.getLogger(classOf[SyncLifecycleSpec])
  val appStateStorage: AppStateStorage = new AppStateStorage(dataSource)
  def stateStorage: StateStorage = notUsed("stateStorage")
  def evmCodeStorage: EvmCodeStorage = notUsed("evmCodeStorage")
  def flatSlotStorage: FlatSlotStorage = notUsed("flatSlotStorage")
  def validatorFactory: MptStorage => StateValidator = notUsed("validatorFactory")
  def snapValidationEc: ExecutionContext = notUsed("snapValidationEc")
  def networkPeerManager: ActorRef[NetworkPeerManagerActor.Command] = notUsed("networkPeerManager")
  def blockchainReader: BlockchainReader = notUsed("blockchainReader")
  def blockchainWriter: BlockchainWriter = notUsed("blockchainWriter")
  def peerEventBus: ActorRef[PeerEventBusActor.Command] = notUsed("peerEventBus")
  def syncController: ActorRef[SyncProtocol.SyncControllerReply] = notUsed("syncController")
  def childFactories: ChildFactories = notUsed("childFactories")
  def pathNodeStorageOpt: Option[PathNodeStorage] = None

  // CoordinatorHandles
  val intakeBudget: SnapIntakeBudget =
    new SnapIntakeBudget(maxPendingStorageTasks = 10L, maxPendingByteCodeHashes = 10L)
  val stoppedChildren: mutable.ArrayBuffer[ActorRef[Nothing]] = mutable.ArrayBuffer.empty
  protected def stopChild(child: ActorRef[Nothing]): Unit = stoppedChildren += child

  // Callee Api traits
  var peers: Map[PeerId, PeerListSupportNg.PeerWithInfo] = Map.empty
  def handshakedPeers: Map[PeerId, PeerListSupportNg.PeerWithInfo] = peers
  def resetHealedCodeHold(): Unit = calls += "resetHealedCodeHold"
  def onStop(): Unit = calls += "onStop"
  def startSnapSync(): Behavior[Command] =
    calls += "startSnapSync"
    Behaviors.same
  def resetStagnationClocks(reason: StagnationWatchdog.ResetReason): Unit = calls += s"resetStagnationClocks($reason)"

  private def notUsed(what: String): Nothing =
    throw new UnsupportedOperationException(s"$what is not reached by SyncLifecycleSpec")

/** Spec 016 M9 stub test (FR-016 (b), T051): `SyncLifecycle` mixed into a stub of its state interface, capabilities and
  * callee Api traits, inside a `BehaviorTestKit` so that `ctx.log` and the timers are real (timers are recorded as
  * effects, never fired). Each module call runs while the kit processes a message, as it would in the controller; a
  * call that returns a behaviour (`enterDormantMode`) becomes the kit's behaviour, so the dormant `DormantWakeUp` arm
  * is driven through the kit. No clock is waited on. The critical-failure count reads and writes `criticalFailureCount`
  * and `accountsAtLastCriticalFailure`; dormancy reads and writes `dormantRetryCount` and clears the refresh and probe
  * state; the wake and the restart reset the round's state (`mptStorage`, the generations, `bytecodesEstimatedTotal`,
  * ...); the heap watchdog is started through the trait's `heapWatchdogStart` seam.
  */
class SyncLifecycleSpec extends ScalaTestWithActorTestKit(ManualTime.config) with AnyFlatSpecLike with Matchers:

  private val pivot = BigInt(1_000)
  private val root = TrieRoot(ByteString(Array.fill[Byte](32)(7)))

  /** The module on its stub, the kit that runs it, and a recording heap-watchdog seam. */
  final private class Harness(config: SNAPSyncConfig = SNAPSyncConfig()):
    var watchdogStarts: Int = 0
    var watchdogStops: Int = 0
    var watchdogOnChange: Option[(Boolean, OldGenReading) => Unit] = None
    private val seam: HeapWatchdogStart = (highFraction, lowFraction, _, onChange, _, _, _) =>
      watchdogStarts += 1
      watchdogOnChange = Some(onChange)
      val watchdog = new SnapHeapWatchdog(new HeapPressureHysteresis(highFraction, lowFraction), () => None, onChange)
      // The executor never gets a task, so it never starts a thread; Handle.stop shuts it down.
      Some(new SnapHeapWatchdog.Handle(watchdog, Executors.newSingleThreadScheduledExecutor(), () => countStop()))
    private def countStop(): Unit = watchdogStops += 1

    private var action: () => Behavior[Command] = () => Behaviors.same
    private var captured: StubSyncLifecycleState & SyncLifecycle = null
    val kit: BehaviorTestKit[Command] = BehaviorTestKit(Behaviors.setup[Command] { ctx =>
      Behaviors.withTimers { timers =>
        captured =
          new StubSyncLifecycleState(ctx, timers, config, system.classicSystem.scheduler) with SyncLifecycle(seam)
        Behaviors.receiveMessage { _ =>
          action()
        }
      }
    })
    val module: StubSyncLifecycleState & SyncLifecycle = captured

    /** Run `f` on the module while the kit processes a message. */
    def call(f: StubSyncLifecycleState & SyncLifecycle => Unit): Unit =
      action = () =>
        f(module)
        Behaviors.same
      kit.run(PollHandshakedPeers)

    /** Run `f` on the module while the kit processes a message, and make the behaviour it returns the kit's. */
    def become(f: StubSyncLifecycleState & SyncLifecycle => Behavior[Command]): Unit =
      action = () => f(module)
      kit.run(PollHandshakedPeers)

    def effects(): Seq[Effect] = kit.retrieveAllEffects()

  private def scheduledTimers(effects: Seq[Effect]): Seq[(Any, Any, FiniteDuration)] =
    effects.collect { case t: Effect.TimerScheduled[?] => (t.key, t.msg, t.delay) }

  private def snapPeer(id: String): (PeerId, PeerListSupportNg.PeerWithInfo) =
    val peer = Peer(PeerId(id), new InetSocketAddress("127.0.0.1", 1), TestInbox[PeerActor.Command]().ref, false)
    val status = RemoteStatus(
      capability = Capability.ETH68,
      networkId = 1,
      chainWeight = ChainWeight.zero,
      bestHash = ByteString("head"),
      genesisHash = ByteString("genesis"),
      supportsSnap = true
    )
    val info =
      PeerInfo(status, ChainWeight.zero, forkAccepted = true, maxBlockNumber = 42, bestBlockHash = status.bestHash)
    peer.id -> PeerListSupportNg.PeerWithInfo(peer, info)

  /** The module with all four coordinators set, a refresh and a probe pending, and the walk flag raised. */
  private def midRound(h: Harness): Unit =
    val m = h.module
    m.accountRangeCoordinator = Some(TestInbox[AccountRangeCoordinator.Command]().ref)
    m.bytecodeCoordinator = Some(TestInbox[ByteCodeCoordinator.Command]().ref)
    m.storageRangeCoordinator = Some(TestInbox[StorageRangeCoordinator.Command]().ref)
    m.trieNodeHealingCoordinator = Some(TestInbox[TrieNodeHealingCoordinator.Command]().ref)
    m.pendingPivotRefresh = Some((pivot + 64, "refresh"))
    m.pivotProbeRequestId = Some(BigInt(9))
    m.proactiveRollNeedsProbe = true
    m.probeAttemptCount = 3
    m.lastProbeAttemptMs = 123L
    m.healingServeRootRequestInFlight = true
    m.lastHealingServeRootBlock = Some(pivot)
    m.healingWalkLocalOnly.set(true)
    m.pivotBlock = Some(pivot)
    m.stateRoot = Some(root)
    m.accountsComplete = true
    m.bytecodePhaseComplete = true
    m.storagePhaseComplete = true
    m.forceCompleteStorageSent = true
    m.bytecodesEstimatedTotal = 500L
    m.healingValidatedRoot = Some(root)
    m.coordinatorGeneration = 4L
    m.validationGeneration = 6L
    m.validationInProgress = true
    m.consecutivePivotRefreshes = 7
    m.bootstrapRetryCount = 5

  "SyncLifecycle" should "count a critical failure, record the accounts synced, and report the threshold" taggedAs UnitTest in {
    val h = new Harness(SNAPSyncConfig(maxSnapSyncFailures = 2))
    h.module.progressMonitor.incrementAccountsSynced(500L)
    var first = true
    var second = false
    h.call(m => first = m.recordCriticalFailure("first"))
    first shouldBe false
    h.module.criticalFailureCount shouldBe 1
    h.module.accountsAtLastCriticalFailure shouldBe 500L
    h.module.progressMonitor.incrementAccountsSynced(250L)
    h.call(m => second = m.recordCriticalFailure("second"))
    second shouldBe true
    h.module.criticalFailureCount shouldBe 2
    h.module.accountsAtLastCriticalFailure shouldBe 750L
  }

  it should "enter dormant mode: stop the children, clear the refresh and probe state, keep the phase flags, arm the wake-up" taggedAs UnitTest in {
    val h = new Harness()
    midRound(h)
    h.effects()
    h.become(_.enterDormantMode("no snapshot peers"))
    val m = h.module
    m.currentPhase shouldBe SyncPhase.Dormant
    m.dormantRetryCount shouldBe 1
    m.stoppedChildren should have size 4
    m.accountRangeCoordinator shouldBe None
    m.trieNodeHealingCoordinator shouldBe None
    m.healingWalkLocalOnly.get() shouldBe false
    m.pendingPivotRefresh shouldBe None
    m.pivotProbeRequestId shouldBe None
    m.pendingProbeCommit shouldBe None
    m.proactiveRollNeedsProbe shouldBe false
    m.probeAttemptCount shouldBe 0
    m.accountsComplete shouldBe true
    m.storagePhaseComplete shouldBe true
    m.pivotBlock shouldBe Some(pivot)
    m.cancelledTimerKeys.toSeq shouldBe Seq(
      RequestAccountRanges,
      RequestByteCodes,
      RequestStorageRanges,
      CheckDownloadStagnation,
      RequestTrieNodeHealing,
      "bootstrap-check",
      "pivot-bootstrap-retry",
      TuneRateTracker,
      "snap-capability-check",
      EvictNonSnapPeers
    )
    scheduledTimers(h.effects()) shouldBe Seq(("dormant-wakeup", DormantWakeUp, 3.minutes))
    m.calls shouldBe empty
  }

  it should "stay dormant at wake-up without a SNAP peer, doubling the backoff" taggedAs UnitTest in {
    val h = new Harness()
    h.become(_.enterDormantMode("first"))
    h.effects()
    h.kit.run(DormantWakeUp)
    h.module.dormantRetryCount shouldBe 2
    h.module.currentPhase shouldBe SyncPhase.Dormant
    scheduledTimers(h.effects()) shouldBe Seq(("dormant-wakeup", DormantWakeUp, 6.minutes))
    h.module.calls shouldBe empty
  }

  it should "wake with a SNAP peer: reset the round, reset the stagnation clocks for Wake, then start SNAP again" taggedAs UnitTest in {
    val h = new Harness()
    h.become(_.enterDormantMode("first"))
    midRound(h)
    h.module.criticalFailureCount = 3
    h.module.peers = Map(snapPeer("p1"))
    h.kit.run(DormantWakeUp)
    val m = h.module
    m.calls.toSeq shouldBe Seq("resetHealedCodeHold", "resetStagnationClocks(Wake)", "startSnapSync")
    m.currentPhase shouldBe SyncPhase.Idle
    m.pivotBlock shouldBe None
    m.stateRoot shouldBe None
    m.mptStorage shouldBe None
    m.accountsComplete shouldBe false
    m.bytecodePhaseComplete shouldBe false
    m.storagePhaseComplete shouldBe false
    m.forceCompleteStorageSent shouldBe false
    m.bytecodesEstimatedTotal shouldBe 0L
    m.healingValidatedRoot shouldBe None
    m.coordinatorGeneration shouldBe 5L
    m.validationGeneration shouldBe 7L
    m.validationInProgress shouldBe false
    m.consecutivePivotRefreshes shouldBe 0
    m.bootstrapRetryCount shouldBe 0
    m.dormantRetryCount shouldBe 1
    m.criticalFailureCount shouldBe 3
  }

  it should "answer GetStatus while dormant with the pivot as progress, and hand peer events to peerEventArms" taggedAs UnitTest in {
    val h = new Harness()
    h.module.pivotBlock = Some(pivot)
    h.become(_.enterDormantMode("first"))
    val status = TestInbox[SyncProtocol.Status]()
    h.kit.run(GetStatus(status.ref))
    status.receiveMessage() shouldBe SyncProtocol.Status.Syncing(
      startingBlockNumber = BigInt(0),
      blocksProgress = SyncProtocol.Status.Progress(pivot, pivot),
      stateNodesProgress = None
    )
    h.kit.run(PollHandshakedPeers)
    h.module.calls.toSeq shouldBe Seq("peerEventArms(PollHandshakedPeers)")
  }

  it should "restart: clear the refresh, probe and serve-root state, wipe the persisted phase flags, keep the stateless count" taggedAs UnitTest in {
    val h = new Harness()
    midRound(h)
    val m = h.module
    m.appStateStorage
      .putSnapSyncAccountsComplete(true)
      .and(m.appStateStorage.putSnapSyncStorageComplete(true))
      .and(m.appStateStorage.putSnapSyncBytecodeComplete(true))
      .and(m.appStateStorage.putSnapSyncStorageFileCount(Some(12L)))
      .and(m.appStateStorage.putSnapSyncCodeHashesCount(Some(34L)))
      .commit()
    m.progressMonitor.incrementAccountsSynced(900L)
    h.become(_.restartSnapSync("stalled"))
    m.pendingPivotRefresh shouldBe None
    m.pivotProbeRequestId shouldBe None
    m.proactiveRollNeedsProbe shouldBe false
    m.probeAttemptCount shouldBe 0
    m.lastProbeAttemptMs shouldBe 0L
    m.healingServeRootRequestInFlight shouldBe false
    m.lastHealingServeRootBlock shouldBe None
    m.stoppedChildren should have size 4
    m.healingWalkLocalOnly.get() shouldBe false
    m.accountsComplete shouldBe false
    m.forceCompleteStorageSent shouldBe false
    m.bytecodesEstimatedTotal shouldBe 0L
    m.healingValidatedRoot shouldBe None
    m.appStateStorage.isSnapSyncAccountsComplete() shouldBe false
    m.appStateStorage.isSnapSyncStorageComplete() shouldBe false
    m.appStateStorage.isSnapSyncBytecodeComplete() shouldBe false
    m.appStateStorage.getSnapSyncStorageFileCount() shouldBe None
    m.appStateStorage.getSnapSyncCodeHashesCount() shouldBe None
    m.pivotBlock shouldBe None
    m.stateRoot shouldBe None
    m.currentPhase shouldBe SyncPhase.Idle
    m.coordinatorGeneration shouldBe 5L
    m.validationGeneration shouldBe 7L
    m.validationInProgress shouldBe false
    m.consecutivePivotRefreshes shouldBe 7
    m.bootstrapRetryCount shouldBe 5
    m.progressMonitor.currentProgress.accountsSynced shouldBe 0L
    m.cancelledTimerKeys.toSeq shouldBe Seq(
      RequestAccountRanges,
      RequestByteCodes,
      RequestStorageRanges,
      CheckDownloadStagnation,
      RequestTrieNodeHealing,
      "bootstrap-check",
      "pivot-bootstrap-retry",
      TuneRateTracker
    )
    m.calls.toSeq shouldBe Seq("resetHealedCodeHold", "startSnapSync")
  }

  it should "start the heap watchdog once when enabled, and stop it with the intake pressure released" taggedAs UnitTest in {
    val h = new Harness(SNAPSyncConfig(heapWatchdogEnabled = true))
    h.call(_.ensureHeapWatchdog())
    h.call(_.ensureHeapWatchdog())
    h.watchdogStarts shouldBe 1
    h.watchdogOnChange.foreach(_(true, OldGenReading(postGcUsed = 90L, currentUsed = 95L, max = 100L)))
    h.module.intakeBudget.heapPressureActive shouldBe true
    h.call(_.stopHeapWatchdog())
    h.watchdogStops shouldBe 1
    h.module.intakeBudget.heapPressureActive shouldBe false
    h.call(_.ensureHeapWatchdog())
    h.watchdogStarts shouldBe 2
  }

  it should "not start the heap watchdog when it is disabled" taggedAs UnitTest in {
    val h = new Harness(SNAPSyncConfig(heapWatchdogEnabled = false))
    h.call(_.ensureHeapWatchdog())
    h.watchdogStarts shouldBe 0
    h.call(_.stopHeapWatchdog())
    h.watchdogStops shouldBe 0
  }

  it should "define the DelayedRestart arm for DelayedRestart only" taggedAs UnitTest in {
    val h = new Harness()
    h.module.delayedRestartArms.isDefinedAt(DelayedRestart("backoff")) shouldBe true
    h.module.delayedRestartArms.isDefinedAt(DormantWakeUp) shouldBe false
    h.module.delayedRestartArms.isDefinedAt(PollHandshakedPeers) shouldBe false
  }

  it should "restart on DelayedRestart in the account and the bytecode-and-storage phases" taggedAs UnitTest in {
    for phase <- Seq(SyncPhase.AccountRangeSync, SyncPhase.ByteCodeAndStorageSync) do
      val h = new Harness()
      midRound(h)
      h.module.currentPhase = phase
      h.become(_.delayedRestartArms(DelayedRestart("critical failure backoff")))
      val m = h.module
      m.calls.toSeq shouldBe Seq("resetHealedCodeHold", "startSnapSync")
      m.currentPhase shouldBe SyncPhase.Idle
      m.pivotBlock shouldBe None
      m.stateRoot shouldBe None
      m.pendingPivotRefresh shouldBe None
      m.stoppedChildren should have size 4
      m.accountsComplete shouldBe false
      m.coordinatorGeneration shouldBe 5L
      m.validationGeneration shouldBe 7L
  }

  it should "do nothing on DelayedRestart in any other phase" taggedAs UnitTest in {
    val others = Seq(
      SyncPhase.Idle,
      SyncPhase.StateHealing,
      SyncPhase.StateValidation,
      SyncPhase.ChainDownloadCompletion,
      SyncPhase.Completed,
      SyncPhase.Dormant
    )
    for phase <- others do
      val h = new Harness()
      midRound(h)
      h.module.currentPhase = phase
      h.become(_.delayedRestartArms(DelayedRestart("critical failure backoff")))
      val m = h.module
      m.calls shouldBe empty
      m.currentPhase shouldBe phase
      m.pivotBlock shouldBe Some(pivot)
      m.pendingPivotRefresh shouldBe Some((pivot + 64, "refresh"))
      m.stoppedChildren shouldBe empty
      m.accountsComplete shouldBe true
      m.coordinatorGeneration shouldBe 4L
  }

  it should "stop the SNAP-only schedules: clear both scheduler flags and stop the heap watchdog, idempotently" taggedAs UnitTest in {
    val h = new Harness(SNAPSyncConfig(heapWatchdogEnabled = true))
    h.call(_.ensureHeapWatchdog())
    h.watchdogOnChange.foreach(_(true, OldGenReading(postGcUsed = 90L, currentUsed = 95L, max = 100L)))
    val m = h.module
    m.intakeBudget.heapPressureActive shouldBe true
    m.snapPeerEvictionStarted = true
    m.snapServerPeersSchedulerStarted = true
    h.call(_.stopSnapOnlySchedules())
    m.snapPeerEvictionStarted shouldBe false
    m.snapServerPeersSchedulerStarted shouldBe false
    h.watchdogStops shouldBe 1
    m.intakeBudget.heapPressureActive shouldBe false
    m.stoppedChildren shouldBe empty
    m.calls shouldBe empty
    h.call(_.stopSnapOnlySchedules())
    h.watchdogStops shouldBe 1
    m.snapPeerEvictionStarted shouldBe false
  }

  it should "stop the state-sync children, clear the force-complete and local-walk flags, and keep the ChainDownloader" taggedAs UnitTest in {
    val h = new Harness()
    midRound(h)
    val downloader = TestInbox[ChainDownloader.Command]()
    val m = h.module
    m.chainDownloader.attach(downloader.ref)
    h.call(_.stopStateSyncChildren())
    m.stoppedChildren should have size 4
    m.stoppedChildren should not contain downloader.ref
    m.accountRangeCoordinator shouldBe None
    m.bytecodeCoordinator shouldBe None
    m.storageRangeCoordinator shouldBe None
    m.trieNodeHealingCoordinator shouldBe None
    m.chainDownloader.isDefined shouldBe true
    m.forceCompleteStorageSent shouldBe false
    m.healingWalkLocalOnly.get() shouldBe false
    m.accountsComplete shouldBe true
    m.storagePhaseComplete shouldBe true
    m.pivotBlock shouldBe Some(pivot)
    m.pendingPivotRefresh shouldBe Some((pivot + 64, "refresh"))
    m.calls shouldBe empty
  }
