package com.chipprbots.ethereum.blockchain.sync.snap.controller

import org.apache.pekko.actor.testkit.typed.Effect
import org.apache.pekko.actor.testkit.typed.scaladsl.BehaviorTestKit
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

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.blockchain.sync.PeerListSupportNg
import com.chipprbots.ethereum.blockchain.sync.SyncProtocol
import com.chipprbots.ethereum.blockchain.sync.TestSyncConfig
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPRequestTracker
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncConfig
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.*
import com.chipprbots.ethereum.blockchain.sync.snap.SnapIntakeBudget
import com.chipprbots.ethereum.blockchain.sync.snap.StateValidator
import com.chipprbots.ethereum.blockchain.sync.snap.SyncProgressMonitor
import com.chipprbots.ethereum.blockchain.sync.snap.actors.AccountRangeCoordinator
import com.chipprbots.ethereum.blockchain.sync.snap.actors.StorageRangeCoordinator
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.FlatSlotStorage
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.db.storage.PathNodeStorage
import com.chipprbots.ethereum.db.storage.SnapSyncProgressStorage
import com.chipprbots.ethereum.db.storage.StateStorage
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockNumber
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.BlockchainWriter
import com.chipprbots.ethereum.domain.TrieRoot
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.PeerEventBusActor
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.p2p.messages.SNAP
import com.chipprbots.ethereum.testing.Tags.UnitTest
import com.chipprbots.ethereum.utils.Config.SyncConfig

/** Everything `PivotRefresher` needs, as a stub (spec 016 FR-016 (b)): its `PivotRefresherState`, the hubs, the
  * environment, the coordinator handles, the phase flags and the callee Api traits. State is plain vars; the chain and
  * app-state stores are ephemeral; the network best and the lazy hand-off block are settable; the Api calls are
  * recorded; the members these tests never reach throw.
  */
private[snap] class StubPivotRefresherState(
    val ctx: ActorContext[SNAPSyncController.Command],
    val timers: TimerScheduler[SNAPSyncController.Command],
    val snapSyncConfig: SNAPSyncConfig,
    chain: EphemBlockchainTestSetup & TestSyncConfig
) extends PivotRefresherState
    with SnapSharedState
    with SnapControllerEnv
    with CoordinatorHandles
    with PhaseFlags
    with LifecycleApi
    with PeerPoolApi
    with PivotSelectionApi
    with PivotBestBlockApi
    with ProbeTargetApi
    with FinalizationApi
    with ChainDownloadStartApi
    with LazyHandoffApi
    with StagnationResetApi:

  // PivotRefresherState
  var clPivotHint: Option[CLPivotHint] = None
  var posChain: Boolean = false
  def isPoSChain: Boolean = posChain
  var pendingPivotRefresh: Option[(BigInt, String)] = None
  var healRepegNoRootAttempts: Int = 0
  var validationGeneration: Long = 0L
  var preservedRangeProgress: Map[ByteString, ByteString] = Map.empty
  var preservedAtPivotBlock: Option[BigInt] = None
  var lastProactivePivotBlock: Option[BigInt] = None
  var pivotProbeRequestId: Option[BigInt] = None
  var proactiveRollNeedsProbe: Boolean = false
  var pendingProbeCommit: Option[(BigInt, BlockHeader, String)] = None
  var lastProbeAttemptMs: Long = 0L
  var probeAttemptCount: Int = 0
  var consecutivePivotRefreshes: Int = 0
  var criticalFailureCount: Int = 0
  var lastPivotRestartMs: Long = 0L
  def MinPivotRestartInterval: FiniteDuration = 30.seconds
  def PivotBootstrapRetryKey: String = "pivot-bootstrap-retry"
  def ProbeCooldownMs: Long = 30_000L
  def SnapServeWindowMargin: BigInt = BigInt(50)
  var storageContractProgressPct: Double = 0.0

  // SnapSharedState
  var pivotBlock: Option[BigInt] = None
  var stateRoot: Option[TrieRoot] = None
  var currentPhase: SyncPhase = SyncPhase.AccountRangeSync
  def progressMonitor: SyncProgressMonitor = notUsed("progressMonitor")
  def requestTracker: SNAPRequestTracker = notUsed("requestTracker")

  // SnapControllerEnv (ctx, timers and snapSyncConfig are constructor vals)
  def syncConfig: SyncConfig = chain.syncConfig
  def asyncLog: org.slf4j.Logger = notUsed("asyncLog")
  val appStateStorage: AppStateStorage = chain.storagesInstance.storages.appStateStorage
  // PivotRefresherState's progress store, declared after the app-state store whose data source it shares
  val snapProgressStorage: SnapSyncProgressStorage = new SnapSyncProgressStorage(appStateStorage.dataSource)
  def stateStorage: StateStorage = notUsed("stateStorage")
  def evmCodeStorage: EvmCodeStorage = notUsed("evmCodeStorage")
  def flatSlotStorage: FlatSlotStorage = notUsed("flatSlotStorage")
  def validatorFactory: MptStorage => StateValidator = notUsed("validatorFactory")
  def snapValidationEc: ExecutionContext = notUsed("snapValidationEc")
  def networkPeerManager: ActorRef[NetworkPeerManagerActor.Command] = notUsed("networkPeerManager")
  def blockchainReader: BlockchainReader = chain.blockchainReader
  def blockchainWriter: BlockchainWriter = chain.blockchainWriter
  def peerEventBus: ActorRef[PeerEventBusActor.Command] = notUsed("peerEventBus")
  var syncControllerRef: Option[ActorRef[SyncProtocol.SyncControllerReply]] = None
  def syncController: ActorRef[SyncProtocol.SyncControllerReply] =
    syncControllerRef.getOrElse(notUsed("syncController"))
  def childFactories: ChildFactories = notUsed("childFactories")
  def pathNodeStorageOpt: Option[PathNodeStorage] = None

  // StateIngest (spec 016 T055): forwards to the stores above, read on each call.
  val stateIngest: StateIngest = new ForwardingStateIngest(
    appStateStorage,
    stateStorage,
    flatSlotStorage,
    pathNodeStorageOpt,
    evmCodeStorage,
    blockchainReader,
    blockchainWriter,
    validatorFactory
  )

  // CoordinatorHandles (the ChainDownloader handle is real and empty, so `tell` is a no-op)
  def intakeBudget: SnapIntakeBudget = notUsed("intakeBudget")
  protected def stopChild(child: ActorRef[Nothing]): Unit = notUsed("stopChild")

  // Callee Api traits
  var criticalFailureLimitReached: Boolean = false
  val criticalFailures: mutable.ArrayBuffer[String] = mutable.ArrayBuffer.empty
  def recordCriticalFailure(reason: String): Boolean =
    criticalFailures += reason
    criticalFailureLimitReached
  var dormantBehavior: Behavior[Command] = Behaviors.same
  val dormantReasons: mutable.ArrayBuffer[String] = mutable.ArrayBuffer.empty
  def enterDormantMode(reason: String): Behavior[Command] =
    dormantReasons += reason
    dormantBehavior
  val restarts: mutable.ArrayBuffer[String] = mutable.ArrayBuffer.empty
  def restartSnapSync(reason: String): Behavior[Command] =
    restarts += reason
    Behaviors.same
  def peersToDownloadFrom: Map[PeerId, PeerListSupportNg.PeerWithInfo] = Map.empty
  def calibratePivotTD(pivotBlockNumber: BigInt): Option[BigInt] = notUsed("calibratePivotTD")
  var networkBest: Option[BigInt] = None
  def currentNetworkBestFromSnapPeers(): Option[BigInt] = networkBest
  val bestBlockUpdates: mutable.ArrayBuffer[(BlockHeader, BigInt)] = mutable.ArrayBuffer.empty
  def updateBestBlockForPivot(header: BlockHeader, pivotBlockNumber: BigInt): Unit =
    bestBlockUpdates += ((header, pivotBlockNumber))
  def bestSnapProbeTarget(): Option[(PeerListSupportNg.PeerWithInfo, String)] = notUsed("bestSnapProbeTarget")
  var snapSyncCompletions: Int = 0
  def completeSnapSync(): Behavior[Command] =
    snapSyncCompletions += 1
    Behaviors.same
  var chainDownloaderStarts: Int = 0
  def startChainDownloader(): Unit = chainDownloaderStarts += 1
  var lazyHandoffBlocked: Boolean = false
  def lazyHandoffBlockedByAbsentPathRoot(): Boolean = lazyHandoffBlocked
  val lazyHandoffAnchors: mutable.ArrayBuffer[String] = mutable.ArrayBuffer.empty
  def anchorPivotBeforeLazyHandoff(reason: String): Unit = lazyHandoffAnchors += reason
  val stagnationResets: mutable.ArrayBuffer[StagnationWatchdog.ResetReason] = mutable.ArrayBuffer.empty
  def resetStagnationClocks(reason: StagnationWatchdog.ResetReason): Unit = stagnationResets += reason

  private def notUsed(what: String): Nothing =
    throw new UnsupportedOperationException(s"$what is not reached by PivotRefresherSpec")

/** Spec 016 M10 stub test (FR-016 (b), T052): `PivotRefresher` mixed into a stub of its state interface, capabilities
  * and callee Api traits, inside a `BehaviorTestKit` so that `ctx.log` and the timers are real (timers are recorded as
  * effects). Each module call runs while the kit processes a message, as it would in the controller. Nothing waits on a
  * clock: the retries are recorded timers, and the one wall-clock comparison (the `PivotStateUnservable` debounce) is
  * set far outside or far inside its window. The bodies read and write `pendingPivotRefresh`, `validationGeneration`,
  * `preservedAtPivotBlock`, `lastProactivePivotBlock`, `healRepegNoRootAttempts`, the probe state
  * (`pivotProbeRequestId`, `pendingProbeCommit`, `probeAttemptCount`), `consecutivePivotRefreshes` and
  * `lastPivotRestartMs`.
  */
class PivotRefresherSpec extends AnyFlatSpec with Matchers:

  private val rootA: ByteString = ByteString(Array.fill[Byte](32)(0x0a))
  private val rootB: ByteString = ByteString(Array.fill[Byte](32)(0x0b))

  private def headerAt(number: BigInt, root: ByteString): BlockHeader =
    Fixtures.Blocks.Genesis.header.copy(number = BlockNumber(number), stateRoot = TrieRoot(root))

  /** The module on its stub, the kit that runs it, and the inboxes of the parent and two coordinators. */
  final private class Harness:
    val parent: TestInbox[SyncProtocol.SyncControllerReply] = TestInbox[SyncProtocol.SyncControllerReply]()
    val accounts: TestInbox[AccountRangeCoordinator.Command] = TestInbox[AccountRangeCoordinator.Command]()
    val storage: TestInbox[StorageRangeCoordinator.Command] = TestInbox[StorageRangeCoordinator.Command]()
    private val chain = new EphemBlockchainTestSetup with TestSyncConfig {}
    private var action: () => Behavior[Command] = () => Behaviors.same
    private var captured: StubPivotRefresherState & PivotRefresher = null
    val kit: BehaviorTestKit[Command] = BehaviorTestKit(Behaviors.setup[Command] { ctx =>
      Behaviors.withTimers { timers =>
        captured = new StubPivotRefresherState(ctx, timers, SNAPSyncConfig(), chain) with PivotRefresher
        captured.syncControllerRef = Some(parent.ref)
        Behaviors.receiveMessage { _ =>
          action()
        }
      }
    })
    val module: StubPivotRefresherState & PivotRefresher = captured
    module.accountRangeCoordinator = Some(accounts.ref)
    module.storageRangeCoordinator = Some(storage.ref)
    module.pivotBlock = Some(BigInt(400))
    module.stateRoot = Some(TrieRoot(rootA))

    /** Run `f` on the module while the kit processes a message. */
    def call(f: StubPivotRefresherState & PivotRefresher => Unit): Unit =
      action = () =>
        f(module)
        Behaviors.same
      kit.run(PollHandshakedPeers)

    /** Run `f` on the module while the kit processes a message, and make the behaviour it returns the kit's. */
    def become(f: StubPivotRefresherState & PivotRefresher => Behavior[Command]): Unit =
      action = () => f(module)
      kit.run(PollHandshakedPeers)

    def scheduledTimers(): Seq[(Any, Command, FiniteDuration)] =
      kit.retrieveAllEffects().collect { case t: Effect.TimerScheduled[?] =>
        (t.key, t.msg.asInstanceOf[Command], t.delay)
      }

  "PivotRefresher" should "commit the header a refresh bootstrap returned and notify every child" taggedAs UnitTest in {
    val h = new Harness()
    val header = headerAt(500, rootB)
    h.module.pendingPivotRefresh = Some((BigInt(500), "stateless peers"))
    h.module.preservedAtPivotBlock = Some(BigInt(400))
    h.module.preservedRangeProgress = Map(ByteString("last") -> ByteString("next"))
    h.module.validationGeneration = 7L
    h.module.healRepegNoRootAttempts = 3

    h.module.pivotRefreshBootstrapCompleteArms.isDefinedAt(BootstrapComplete(Some(header))) shouldBe true
    h.call(m => m.pivotRefreshBootstrapCompleteArms(BootstrapComplete(Some(header))))

    h.module.pendingPivotRefresh shouldBe None
    h.module.pivotBlock shouldBe Some(BigInt(500))
    h.module.stateRoot shouldBe Some(TrieRoot(rootB))
    h.module.validationGeneration shouldBe 8L
    h.module.preservedAtPivotBlock shouldBe Some(BigInt(500))
    h.module.snapProgressStorage.readProgress(rootB).map(_.pivotBlock) shouldBe Some(500L)
    h.module.lastProactivePivotBlock shouldBe Some(BigInt(500 + 64))
    h.module.healRepegNoRootAttempts shouldBe 0
    h.module.appStateStorage.getSnapSyncPivotBlock() shouldBe Some(BigInt(500))
    h.module.appStateStorage.getSnapSyncStateRoot() shouldBe Some(rootB)
    h.module.bestBlockUpdates shouldBe Seq((header, BigInt(500)))
    h.accounts.receiveAll() shouldBe Seq(AccountRangeCoordinator.PivotRefreshed(rootB))
    h.storage.receiveAll() shouldBe Seq(StorageRangeCoordinator.StoragePivotRefreshed(rootB))
    h.module.chainDownloaderStarts shouldBe 1
    h.module.stagnationResets shouldBe Seq(StagnationWatchdog.ResetReason.PivotRefreshCommit)
  }

  it should "restart when the refresh bootstrap returned no header, and leave a bootstrap with no refresh pending alone" taggedAs UnitTest in {
    val h = new Harness()

    h.module.pivotRefreshBootstrapCompleteArms.isDefinedAt(BootstrapComplete(None)) shouldBe false

    h.module.pendingPivotRefresh = Some((BigInt(500), "stateless peers"))
    h.call(m => m.pivotRefreshBootstrapCompleteArms(BootstrapComplete(None)))

    h.module.pendingPivotRefresh shouldBe None
    h.module.restarts shouldBe Seq("pivot refresh bootstrap returned no header for 500: stateless peers")
    h.module.pivotBlock shouldBe Some(BigInt(400))
  }

  it should "backtrack a failed refresh bootstrap by the pivot offset, or retry the refresh when that is not positive" taggedAs UnitTest in {
    val h = new Harness()
    h.module.pendingPivotRefresh = Some((BigInt(500), "stateless peers"))

    h.call(m => m.pivotRefreshBootstrapFailedArms(PivotBootstrapFailed("no header")))

    h.module.pendingPivotRefresh shouldBe None
    h.scheduledTimers() shouldBe Seq(("pivot-bootstrap-retry", RetryBootstrapAtBlock(BigInt(500 - 64)), 5.seconds))

    h.module.pendingPivotRefresh = Some((BigInt(50), "stateless peers"))
    h.call(m => m.pivotRefreshBootstrapFailedArms(PivotBootstrapFailed("no header")))

    h.module.pendingPivotRefresh shouldBe None
    h.scheduledTimers() shouldBe Seq(("pivot-bootstrap-retry", RetryPivotRefresh, 60.seconds))
  }

  it should "count a stateless report, mark the pivot failed, and fast-track a same-root refresh onto it" taggedAs UnitTest in {
    val h = new Harness()

    h.call(m => m.pivotStateUnservableArms(PivotStateUnservable(rootA, "stateless", 3)))

    h.module.consecutivePivotRefreshes shouldBe 1
    h.module.lastPivotRestartMs should be > 0L
    h.scheduledTimers() shouldBe Seq(("pivot-bootstrap-retry", RetryPivotRefresh, 30.seconds))
    h.accounts.hasMessages shouldBe false

    h.call(m => m.completePivotRefreshWithStateRoot(BigInt(400), headerAt(400, rootA), "same root"))

    h.module.consecutivePivotRefreshes shouldBe 10
    h.module.pivotBlock shouldBe Some(BigInt(400))
    h.accounts.receiveAll() shouldBe Seq(AccountRangeCoordinator.PivotRefreshed(rootA))
    h.storage.receiveAll() shouldBe Seq(StorageRangeCoordinator.StoragePivotRefreshed(rootA))
  }

  it should "continue in the dormant behaviour when the stateless-pivot budget trips the critical-failure limit (#1515)" taggedAs UnitTest in {
    val h = new Harness()
    h.module.consecutivePivotRefreshes = 9
    h.module.criticalFailureLimitReached = true
    // The behaviour enterDormantMode returns must become the actor's: the kit stops when it is returned.
    h.module.dormantBehavior = Behaviors.stopped

    h.become(m => m.pivotStateUnservableArms(PivotStateUnservable(rootA, "stateless", 3)))

    h.module.criticalFailures should have size 1
    h.module.dormantReasons should have size 1
    h.kit.isAlive shouldBe false
  }

  it should "re-arm the range coordinators without counting a report inside the debounce window" taggedAs UnitTest in {
    val h = new Harness()
    val lastRestart = System.currentTimeMillis() + 3_600_000L // an hour ahead: inside the window however slow the run
    h.module.lastPivotRestartMs = lastRestart
    h.module.consecutivePivotRefreshes = 2

    h.call(m => m.pivotStateUnservableArms(PivotStateUnservable(rootA, "stateless", 3)))

    h.module.consecutivePivotRefreshes shouldBe 2
    h.module.lastPivotRestartMs shouldBe lastRestart
    h.accounts.receiveAll() shouldBe Seq(AccountRangeCoordinator.PivotRefreshed(rootA))
    h.storage.receiveAll() shouldBe Seq(StorageRangeCoordinator.StoragePivotRefreshed(rootA))
    h.scheduledTimers() shouldBe empty
  }

  it should "commit a probed roll when the probe answers with accounts, and defer it when the answer is empty" taggedAs UnitTest in {
    val h = new Harness()
    h.module.pivotProbeRequestId = Some(BigInt(7))
    h.module.pendingProbeCommit = Some((BigInt(500), headerAt(500, rootB), "proactive pivot roll"))
    h.module.probeAttemptCount = 2

    h.call(m => m.handlePivotProbeResponse(SNAP.AccountRange(7, Seq(ByteString("account") -> Account()), Nil)))

    h.module.pivotProbeRequestId shouldBe None
    h.module.pendingProbeCommit shouldBe None
    h.module.probeAttemptCount shouldBe 0
    h.module.pivotBlock shouldBe Some(BigInt(500))
    h.module.stateRoot shouldBe Some(TrieRoot(rootB))

    h.module.pivotProbeRequestId = Some(BigInt(8))
    h.module.pendingProbeCommit = Some((BigInt(600), headerAt(600, rootA), "proactive pivot roll"))
    h.call(m => m.handlePivotProbeResponse(SNAP.AccountRange(8, Nil, Nil)))

    h.module.pivotProbeRequestId shouldBe None
    h.module.pendingProbeCommit shouldBe None
    h.module.probeAttemptCount shouldBe 1
    h.module.pivotBlock shouldBe Some(BigInt(500))
  }

  it should "force a deferred roll on the fifth probe timeout, and ignore the timeout of a probe no longer pending" taggedAs UnitTest in {
    val h = new Harness()
    val pending = (BigInt(500), headerAt(500, rootB), "proactive pivot roll")
    h.module.pivotProbeRequestId = Some(BigInt(10))
    h.module.pendingProbeCommit = Some(pending)
    h.module.probeAttemptCount = 3

    h.call(m => m.pivotProbeTimeoutArms(PivotProbeTimeout(BigInt(9))))

    h.module.pivotProbeRequestId shouldBe Some(BigInt(10))
    h.module.pendingProbeCommit shouldBe Some(pending)
    h.module.probeAttemptCount shouldBe 3

    h.module.probeAttemptCount = 4
    h.call(m => m.pivotProbeTimeoutArms(PivotProbeTimeout(BigInt(10))))

    h.module.pivotProbeRequestId shouldBe None
    h.module.pendingProbeCommit shouldBe None
    h.module.probeAttemptCount shouldBe 0
    h.module.pivotBlock shouldBe Some(BigInt(500))
    h.module.stateRoot shouldBe Some(TrieRoot(rootB))
  }

  it should "spend the heal re-peg budget while no newer pivot exists, then take the lazy hand-off" taggedAs UnitTest in {
    val h = new Harness()
    h.module.currentPhase = SyncPhase.StateHealing

    h.call(m => m.retryPivotRefreshArms(RetryPivotRefresh))

    h.module.healRepegNoRootAttempts shouldBe 1
    h.scheduledTimers() shouldBe Seq(("pivot-bootstrap-retry", RetryPivotRefresh, 30.seconds))
    h.module.snapSyncCompletions shouldBe 0

    h.module.healRepegNoRootAttempts = 9
    h.call(m => m.retryPivotRefreshArms(RetryPivotRefresh))

    h.module.healRepegNoRootAttempts shouldBe 0
    h.module.lazyHandoffAnchors shouldBe Seq("HEAL-REPEG budget exhausted")
    h.module.snapSyncCompletions shouldBe 1
  }

  it should "skip a refresh retry outside the download and healing phases, and refuse a commit during validation" taggedAs UnitTest in {
    val h = new Harness()
    h.module.currentPhase = SyncPhase.StateValidation
    h.module.validationGeneration = 7L

    h.call(m => m.retryPivotRefreshArms(RetryPivotRefresh))
    h.call(m => m.completePivotRefreshWithStateRoot(BigInt(500), headerAt(500, rootB), "late bootstrap"))

    h.scheduledTimers() shouldBe empty
    h.module.healRepegNoRootAttempts shouldBe 0
    h.module.pivotBlock shouldBe Some(BigInt(400))
    h.module.stateRoot shouldBe Some(TrieRoot(rootA))
    h.module.validationGeneration shouldBe 7L
    h.accounts.hasMessages shouldBe false
  }

  it should "request the header of a CL-anchored refresh target that is not stored" taggedAs UnitTest in {
    val h = new Harness()
    h.module.posChain = true
    h.module.clPivotHint = Some(CLPivotHint(rootB, Some(headerAt(1_000, rootB))))

    h.call(m => m.refreshPivotInPlace("stale pivot"))

    h.module.pendingPivotRefresh shouldBe Some((BigInt(1_000 - 64), "stale pivot"))
    h.parent.receiveAll() shouldBe Seq(StartRegularSyncBootstrap(BigInt(1_000 - 64)))
    h.module.stagnationResets shouldBe Seq(StagnationWatchdog.ResetReason.PivotRefreshHeaderFetch)
    h.module.pivotBlock shouldBe Some(BigInt(400))
  }

  /** Run the proactive roll trigger once with the SNAP peers' best block at `best`. */
  private def rollAt(h: Harness, best: BigInt): Unit =
    h.module.networkBest = Some(best)
    h.call(_.maybeProactivePivotRoll())

  it should "roll the pivot once it is more than 100 blocks behind the SNAP peers' best block" taggedAs UnitTest in {
    val h = new Harness()

    rollAt(h, BigInt(500))
    h.module.proactiveRollNeedsProbe shouldBe false
    h.module.lastProbeAttemptMs shouldBe 0L
    h.module.pendingPivotRefresh shouldBe None
    h.parent.hasMessages shouldBe false

    // Rolled: the probe flag is set, the cooldown clock is stamped, and the in-place refresh asks the parent for the
    // header of the roll target, the best block minus the 64-block pivot offset.
    rollAt(h, BigInt(501))
    h.module.proactiveRollNeedsProbe shouldBe true
    h.module.lastProbeAttemptMs should be > 0L
    h.module.pendingPivotRefresh shouldBe Some((BigInt(501 - 64), "proactive pivot roll"))
    h.parent.receiveAll() shouldBe Seq(StartRegularSyncBootstrap(BigInt(501 - 64)))
    h.module.stagnationResets shouldBe Seq(StagnationWatchdog.ResetReason.PivotRefreshHeaderFetch)
    h.module.pivotBlock shouldBe Some(BigInt(400))
  }

  it should "not roll again inside 50 blocks of the last committed roll, nor without a SNAP peer's best block" taggedAs UnitTest in {
    val h = new Harness()
    h.module.lastProactivePivotBlock = Some(BigInt(551))

    rollAt(h, BigInt(601))
    h.module.proactiveRollNeedsProbe shouldBe false
    h.parent.hasMessages shouldBe false

    h.module.networkBest = None
    h.call(_.maybeProactivePivotRoll())
    h.module.proactiveRollNeedsProbe shouldBe false
    h.parent.hasMessages shouldBe false

    rollAt(h, BigInt(602))
    h.module.proactiveRollNeedsProbe shouldBe true
    h.module.pendingPivotRefresh shouldBe Some((BigInt(602 - 64), "proactive pivot roll"))
    h.parent.receiveAll() shouldBe Seq(StartRegularSyncBootstrap(BigInt(602 - 64)))
  }

  it should "not roll while a readiness probe is in flight or its commit is deferred" taggedAs UnitTest in {
    val h = new Harness()
    h.module.pivotProbeRequestId = Some(BigInt(7))

    rollAt(h, BigInt(600))
    h.module.proactiveRollNeedsProbe shouldBe false
    h.parent.hasMessages shouldBe false

    h.module.pivotProbeRequestId = None
    h.module.pendingProbeCommit = Some((BigInt(500), headerAt(500, rootB), "proactive pivot roll"))
    rollAt(h, BigInt(600))
    h.module.proactiveRollNeedsProbe shouldBe false
    h.module.lastProbeAttemptMs shouldBe 0L
    h.module.pendingPivotRefresh shouldBe None
    h.parent.hasMessages shouldBe false
  }

  it should "roll in the storage phase until 80% of the contracts are done" taggedAs UnitTest in {
    val h = new Harness()
    h.module.currentPhase = SyncPhase.ByteCodeAndStorageSync
    h.module.storageContractProgressPct = 0.80

    rollAt(h, BigInt(600))
    h.module.proactiveRollNeedsProbe shouldBe false
    h.parent.hasMessages shouldBe false

    h.module.storageContractProgressPct = 0.79
    rollAt(h, BigInt(600))
    h.module.proactiveRollNeedsProbe shouldBe true
    h.module.pendingPivotRefresh shouldBe Some((BigInt(600 - 64), "proactive pivot roll"))
    h.parent.receiveAll() shouldBe Seq(StartRegularSyncBootstrap(BigInt(600 - 64)))
  }

  it should "not roll outside the two download phases, without a pivot, or inside the 30-second probe cooldown" taggedAs UnitTest in {
    val h = new Harness()

    h.module.currentPhase = SyncPhase.StateHealing
    rollAt(h, BigInt(600))
    h.module.proactiveRollNeedsProbe shouldBe false

    h.module.currentPhase = SyncPhase.AccountRangeSync
    h.module.pivotBlock = None
    rollAt(h, BigInt(600))
    h.module.proactiveRollNeedsProbe shouldBe false

    // The last probe stamped an hour ahead: inside the cooldown however slow the run.
    h.module.pivotBlock = Some(BigInt(400))
    val stamped = System.currentTimeMillis() + 3_600_000L
    h.module.lastProbeAttemptMs = stamped
    rollAt(h, BigInt(600))
    h.module.proactiveRollNeedsProbe shouldBe false
    h.module.lastProbeAttemptMs shouldBe stamped
    h.module.pendingPivotRefresh shouldBe None
    h.parent.hasMessages shouldBe false
  }
