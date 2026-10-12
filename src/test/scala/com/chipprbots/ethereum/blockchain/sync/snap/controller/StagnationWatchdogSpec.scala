package com.chipprbots.ethereum.blockchain.sync.snap.controller

import java.net.InetSocketAddress

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
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPRequestTracker
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncConfig
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.*
import com.chipprbots.ethereum.blockchain.sync.snap.SnapIntakeBudget
import com.chipprbots.ethereum.blockchain.sync.snap.StateValidator
import com.chipprbots.ethereum.blockchain.sync.snap.SyncProgressMonitor
import com.chipprbots.ethereum.blockchain.sync.snap.actors.AccountRangeCoordinator
import com.chipprbots.ethereum.blockchain.sync.snap.actors.AccountRangeStats
import com.chipprbots.ethereum.blockchain.sync.snap.actors.ByteCodeCoordinator
import com.chipprbots.ethereum.blockchain.sync.snap.actors.StorageRangeCoordinator
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.FlatSlotStorage
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.db.storage.PathNodeStorage
import com.chipprbots.ethereum.db.storage.StateStorage
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

/** Everything `StagnationWatchdog` needs, as a stub (spec 016 FR-016 (b)): its `StagnationWatchdogState`, the hubs, the
  * environment, the coordinator handles, the phase flags and the callee Api traits. State is plain vars; the handshaked
  * peers are a settable map; the Api calls are recorded; the members these tests never reach throw.
  */
private[snap] class StubStagnationWatchdogState(
    val ctx: ActorContext[SNAPSyncController.Command],
    val timers: TimerScheduler[SNAPSyncController.Command],
    val snapSyncConfig: SNAPSyncConfig
) extends StagnationWatchdogState
    with SnapSharedState
    with SnapControllerEnv
    with CoordinatorHandles
    with PhaseFlags
    with HandshakedPeersApi
    with PeerPoolApi
    with LifecycleApi
    with PivotRefreshApi
    with PivotRollTriggerApi:

  // StagnationWatchdogState
  var lastStorageProgressMs: Long = 0L
  var lastBytecodeProgressMs: Long = 0L
  var lastBytecodeProgressCount: Long = 0L
  var lastAccountProgressMs: Long = 0L
  var lastAccountTasksCompleted: Int = 0
  var lastAccountsDownloaded: Long = 0L
  var storageTailBaseline: SNAPSyncController.StorageTailBaseline = SNAPSyncController.StorageTailBaseline.fresh(0L)
  var storageStagnationRefreshAttempted: Boolean = false
  var lastPivotRestartMs: Long = 0L
  def MinPivotRestartInterval: FiniteDuration = 30.seconds
  def StorageRestartedEmptyThreshold: FiniteDuration = 60.seconds

  // SnapSharedState
  var pivotBlock: Option[BigInt] = None
  var stateRoot: Option[TrieRoot] = None
  var currentPhase: SyncPhase = SyncPhase.AccountRangeSync
  def progressMonitor: SyncProgressMonitor = notUsed("progressMonitor")
  def requestTracker: SNAPRequestTracker = notUsed("requestTracker")

  // SnapControllerEnv (ctx, timers and snapSyncConfig are constructor vals)
  def syncConfig: SyncConfig = notUsed("syncConfig")
  def asyncLog: org.slf4j.Logger = notUsed("asyncLog")
  def appStateStorage: AppStateStorage = notUsed("appStateStorage")
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

  // CoordinatorHandles
  def intakeBudget: SnapIntakeBudget = notUsed("intakeBudget")
  protected def stopChild(child: ActorRef[Nothing]): Unit = notUsed("stopChild")

  // Callee Api traits
  var peers: Map[PeerId, PeerListSupportNg.PeerWithInfo] = Map.empty
  def handshakedPeers: Map[PeerId, PeerListSupportNg.PeerWithInfo] = peers
  var downloadPeers: Map[PeerId, PeerListSupportNg.PeerWithInfo] = Map.empty
  def peersToDownloadFrom: Map[PeerId, PeerListSupportNg.PeerWithInfo] = downloadPeers
  def calibratePivotTD(pivotBlockNumber: BigInt): Option[BigInt] = notUsed("calibratePivotTD")
  val criticalFailures: mutable.ArrayBuffer[String] = mutable.ArrayBuffer.empty
  var criticalFailureLimitReached: Boolean = false
  def recordCriticalFailure(reason: String): Boolean =
    criticalFailures += reason
    criticalFailureLimitReached
  val dormantEntries: mutable.ArrayBuffer[String] = mutable.ArrayBuffer.empty
  def enterDormantMode(reason: String): Behavior[Command] =
    dormantEntries += reason
    Behaviors.same
  def restartSnapSync(reason: String): Behavior[Command] = notUsed("restartSnapSync")
  val refreshes: mutable.ArrayBuffer[String] = mutable.ArrayBuffer.empty
  def refreshPivotInPlace(
      reason: String,
      countsTowardHealBudget: Boolean = true,
      pivotUnservable: Boolean = false
  ): Unit = refreshes += reason
  var rollChecks: Int = 0
  def maybeProactivePivotRoll(): Unit = rollChecks += 1

  private def notUsed(what: String): Nothing =
    throw new UnsupportedOperationException(s"$what is not reached by StagnationWatchdogSpec")

/** Spec 016 M7 stub test (FR-016 (b), T049): `StagnationWatchdog` mixed into a stub of its state interface,
  * capabilities and callee Api traits, inside a `BehaviorTestKit` so that `ctx.log` and the timers are real (timers are
  * recorded as effects). Each module call runs while the kit processes a message, as it would in the controller. The
  * clocks are set far in the past, so each stall is past its wall-clock threshold without waiting: the tests are
  * deterministic. The account check reads and writes `lastAccountTasksCompleted`, `lastAccountsDownloaded`,
  * `lastAccountProgressMs` and `lastPivotRestartMs`; the storage check reads and writes `lastStorageProgressMs`,
  * `storageTailBaseline`, `storageStagnationRefreshAttempted` and `lastPivotRestartMs`; the bytecode check reads and
  * writes `lastBytecodeProgressCount` and `lastBytecodeProgressMs`.
  */
class StagnationWatchdogSpec extends ScalaTestWithActorTestKit(ManualTime.config) with AnyFlatSpecLike with Matchers:

  /** The module on its stub, and the kit that runs it. */
  final private class Harness(config: SNAPSyncConfig = SNAPSyncConfig()):
    private var action: () => Unit = () => ()
    private var captured: StubStagnationWatchdogState & StagnationWatchdog = null
    val kit: BehaviorTestKit[Command] = BehaviorTestKit(Behaviors.setup[Command] { ctx =>
      Behaviors.withTimers { timers =>
        captured = new StubStagnationWatchdogState(ctx, timers, config) with StagnationWatchdog
        Behaviors.receiveMessage { _ =>
          action()
          Behaviors.same
        }
      }
    })
    val module: StubStagnationWatchdogState & StagnationWatchdog = captured
    val accounts: TestInbox[AccountRangeCoordinator.Command] = TestInbox[AccountRangeCoordinator.Command]()
    val storage: TestInbox[StorageRangeCoordinator.Command] = TestInbox[StorageRangeCoordinator.Command]()
    val bytecodes: TestInbox[ByteCodeCoordinator.Command] = TestInbox[ByteCodeCoordinator.Command]()
    module.accountRangeCoordinator = Some(accounts.ref)
    module.storageRangeCoordinator = Some(storage.ref)
    module.bytecodeCoordinator = Some(bytecodes.ref)

    /** Run `f` on the module while the kit processes a message. */
    def call(f: StubStagnationWatchdogState & StagnationWatchdog => Unit): Unit =
      action = () => f(module)
      kit.run(PollHandshakedPeers)

    def effects(): Seq[Effect] = kit.retrieveAllEffects()

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

  private def accountStats(downloaded: Long, completed: Int, pending: Int): AccountRangeStats =
    AccountRangeStats(
      accountsDownloaded = downloaded,
      bytesDownloaded = 0L,
      tasksCompleted = completed,
      tasksActive = 0,
      tasksPending = pending,
      progress = 0.0,
      elapsedTimeMs = 1_000L,
      contractAccountsFound = 0L
    )

  private def storageStats(
      completed: Int,
      active: Int,
      pending: Int,
      elapsedMs: Long = 1_000L
  ): StorageRangeCoordinator.SyncStatistics =
    StorageRangeCoordinator.SyncStatistics(
      slotsDownloaded = 0L,
      bytesDownloaded = 0L,
      tasksCompleted = completed,
      tasksActive = active,
      tasksPending = pending,
      elapsedTimeMs = elapsedMs,
      progress = 0.0
    )

  "StagnationWatchdog" should "schedule the stagnation check every 30 seconds" taggedAs UnitTest in {
    val h = new Harness()

    h.call(_.scheduleStagnationChecks())

    h.effects().collect { case t: Effect.TimerScheduled[?] => (t.key, t.msg, t.delay) } shouldBe Seq(
      (CheckDownloadStagnation, CheckDownloadStagnation, 30.seconds)
    )
  }

  it should "record account progress, then wait for SNAP peers on a stall without refreshing" taggedAs UnitTest in {
    val h = new Harness()

    h.call(_.accountRangeArms(AccountCoordinatorProgress(accountStats(downloaded = 100L, completed = 2, pending = 3))))

    h.module.lastAccountTasksCompleted shouldBe 2
    h.module.lastAccountsDownloaded shouldBe 100L
    h.module.lastAccountProgressMs should be > 0L
    h.module.refreshes shouldBe empty

    // No progress since, the clock far in the past, and no SNAP peer: wait, do not refresh.
    h.module.lastAccountProgressMs = 0L
    h.call(_.accountRangeArms(AccountCoordinatorProgress(accountStats(downloaded = 100L, completed = 2, pending = 3))))

    h.module.lastPivotRestartMs should be > 0L
    h.module.lastAccountProgressMs should be > 0L
    h.module.refreshes shouldBe empty
    h.module.criticalFailures shouldBe empty
    h.accounts.hasMessages shouldBe false
  }

  it should "recover stalled account tasks and refresh the pivot on an account stall with SNAP peers" taggedAs UnitTest in {
    val h = new Harness()
    h.module.peers = Map(snapPeer("snap-1"))
    h.module.lastAccountsDownloaded = 100L

    h.call(_.accountRangeArms(AccountCoordinatorProgress(accountStats(downloaded = 100L, completed = 0, pending = 3))))

    h.module.lastPivotRestartMs should be > 0L
    h.module.lastAccountProgressMs should be > 0L
    h.accounts.receiveAll() shouldBe Seq(AccountRangeCoordinator.RecoverStalledAccountTasks)
    h.module.refreshes should have size 1
    h.module.refreshes.head should startWith("account stall: account range sync stalled")
    h.module.criticalFailures shouldBe empty

    // A second stall inside the 30 s debounce does nothing.
    h.module.lastAccountProgressMs = 0L
    h.call(_.accountRangeArms(AccountCoordinatorProgress(accountStats(downloaded = 100L, completed = 0, pending = 3))))

    h.module.refreshes should have size 1
    h.module.lastAccountProgressMs shouldBe 0L
  }

  it should "escalate to dormant mode once the account stall refresh budget is spent" taggedAs UnitTest in {
    val h = new Harness()
    h.module.peers = Map(snapPeer("snap-1"))
    h.module.criticalFailureLimitReached = true
    val stalled = AccountCoordinatorProgress(accountStats(downloaded = 0L, completed = 0, pending = 3))

    (1 to 10).foreach { _ =>
      h.module.lastAccountProgressMs = 0L
      h.module.lastPivotRestartMs = 0L
      h.call(_.accountRangeArms(stalled))
    }

    h.module.refreshes should have size 10
    h.module.criticalFailures shouldBe empty

    h.module.lastAccountProgressMs = 0L
    h.module.lastPivotRestartMs = 0L
    h.call(_.accountRangeArms(stalled))

    h.module.refreshes should have size 10
    h.module.criticalFailures should have size 1
    h.module.criticalFailures.head should startWith("account stall refresh limit exceeded")
    h.module.dormantEntries should have size 1
    h.module.lastAccountProgressMs should be > 0L
  }

  it should "refresh the pivot once on a storage stall, then force-complete storage" taggedAs UnitTest in {
    val h = new Harness()
    h.module.currentPhase = SyncPhase.ByteCodeAndStorageSync

    // An all-zero answer is an ask timeout: liveness, so the clock restarts.
    h.call(
      _.byteCodeAndStorageArms(
        StorageCoordinatorProgress(storageStats(completed = 0, active = 0, pending = 0, elapsedMs = 0L))
      )
    )

    h.module.lastStorageProgressMs should be > 0L
    h.module.refreshes shouldBe empty

    h.module.lastStorageProgressMs = 0L
    h.call(_.byteCodeAndStorageArms(StorageCoordinatorProgress(storageStats(completed = 5, active = 2, pending = 4))))

    h.module.storageStagnationRefreshAttempted shouldBe true
    h.module.lastPivotRestartMs should be > 0L
    h.module.lastStorageProgressMs should be > 0L
    h.module.storageTailBaseline.lowWork shouldBe 6
    h.module.refreshes should have size 1
    h.module.refreshes.head should startWith("storage stagnation: no progress for")
    h.storage.hasMessages shouldBe false

    // Still no progress after the refresh: past the 2-minute grace, storage is force-completed, once.
    h.module.lastStorageProgressMs = 0L
    h.call(_.byteCodeAndStorageArms(StorageCoordinatorProgress(storageStats(completed = 5, active = 2, pending = 4))))
    h.call(_.byteCodeAndStorageArms(StorageCoordinatorProgress(storageStats(completed = 5, active = 2, pending = 4))))

    h.module.forceCompleteStorageSent shouldBe true
    h.storage.receiveAll() shouldBe Seq(StorageRangeCoordinator.ForceCompleteStorage)
    h.module.refreshes should have size 1
  }

  it should "force-complete storage quickly when the coordinator reports no work but never completed" taggedAs UnitTest in {
    val h = new Harness()
    h.module.currentPhase = SyncPhase.ByteCodeAndStorageSync
    h.module.storagePhaseComplete = false

    h.call(_.byteCodeAndStorageArms(StorageCoordinatorProgress(storageStats(completed = 5, active = 0, pending = 0))))

    h.module.forceCompleteStorageSent shouldBe true
    h.storage.receiveAll() shouldBe Seq(StorageRangeCoordinator.ForceCompleteStorage)
    h.module.refreshes shouldBe empty
    h.module.storageStagnationRefreshAttempted shouldBe false
  }

  it should "track bytecode progress, then force-complete bytecodes on a stall" taggedAs UnitTest in {
    val h = new Harness()
    h.module.currentPhase = SyncPhase.ByteCodeAndStorageSync

    h.call(_.byteCodeAndStorageArms(ByteCodeCoordinatorProgress(ByteCodeCoordinator.ByteCodeProgress(0.5, 7L, 0L))))

    h.module.lastBytecodeProgressCount shouldBe 7L
    h.module.lastBytecodeProgressMs should be > 0L
    h.module.bytecodeForceCompleted shouldBe false

    h.module.lastBytecodeProgressMs = 0L
    h.call(_.byteCodeAndStorageArms(ByteCodeCoordinatorProgress(ByteCodeCoordinator.ByteCodeProgress(0.5, 7L, 0L))))

    h.module.bytecodeForceCompleted shouldBe true
    h.bytecodes.receiveAll() shouldBe Seq(ByteCodeCoordinator.ForceCompleteByteCodes)
    h.module.lastBytecodeProgressCount shouldBe 7L
  }

  it should "not force-complete a recovered bytecode phase on its first tick after a stale clock (#1551 item 1)" taggedAs UnitTest in {
    val h = new Harness()
    // Left by a bootstrap that ran far longer than the bytecode threshold, and a count from an earlier coordinator.
    h.module.lastBytecodeProgressMs = 0L
    h.module.lastBytecodeProgressCount = 500L

    // startSnapSync's accounts-complete recovery spawns a new bytecode coordinator, then resets the clocks.
    h.call { m =>
      m.currentPhase = SyncPhase.ByteCodeAndStorageSync
      m.resetStagnationClocks(StagnationWatchdog.ResetReason.AccountsCompleteRecovery)
    }

    h.module.lastBytecodeProgressCount shouldBe 0L
    h.module.lastBytecodeProgressMs should be > 0L

    // The new coordinator's first tick: nothing downloaded yet. The clock was just reset, so this is no stall.
    h.call(_.byteCodeAndStorageArms(ByteCodeCoordinatorProgress(ByteCodeCoordinator.ByteCodeProgress(0.0, 0L, 0L))))

    h.module.bytecodeForceCompleted shouldBe false
    h.bytecodes.hasMessages shouldBe false

    // Its first real progress counts from zero, not from the old coordinator's 500.
    h.call(_.byteCodeAndStorageArms(ByteCodeCoordinatorProgress(ByteCodeCoordinator.ByteCodeProgress(0.1, 3L, 0L))))

    h.module.lastBytecodeProgressCount shouldBe 3L
    h.module.bytecodeForceCompleted shouldBe false
  }

  it should "refresh the pivot first on the first storage stall after a dormant wake (#1551 item 2)" taggedAs UnitTest in {
    val h = new Harness()
    // The round that went dormant had spent its storage refresh.
    h.module.storageStagnationRefreshAttempted = true

    h.call(_.resetStagnationClocks(StagnationWatchdog.ResetReason.Wake))

    h.module.storageStagnationRefreshAttempted shouldBe false

    // The new round reaches the storage phase and stalls past the threshold: refresh, not force-complete.
    h.module.currentPhase = SyncPhase.ByteCodeAndStorageSync
    h.module.lastStorageProgressMs = 0L
    h.call(_.byteCodeAndStorageArms(StorageCoordinatorProgress(storageStats(completed = 5, active = 2, pending = 4))))

    h.module.refreshes should have size 1
    h.module.refreshes.head should startWith("storage stagnation: no progress for")
    h.module.storageStagnationRefreshAttempted shouldBe true
    h.module.forceCompleteStorageSent shouldBe false
    h.storage.hasMessages shouldBe false
  }

  it should "keep the spent storage refresh across a restart, so the next storage stall force-completes" taggedAs UnitTest in {
    val h = new Harness()
    h.module.currentPhase = SyncPhase.ByteCodeAndStorageSync
    h.module.lastStorageProgressMs = 0L
    h.call(_.byteCodeAndStorageArms(StorageCoordinatorProgress(storageStats(completed = 5, active = 2, pending = 4))))
    h.module.refreshes should have size 1

    // The refresh's header bootstrap returns nothing and restartSnapSync runs. It calls no reset; the new round
    // resets only through its phase entries (account launch, then the storage-phase entry).
    h.call { m =>
      m.resetStagnationClocks(StagnationWatchdog.ResetReason.AccountLaunch)
      m.resetStagnationClocks(StagnationWatchdog.ResetReason.StoragePhaseEntry)
    }
    h.module.storageStagnationRefreshAttempted shouldBe true

    // The new round's storage stalls again: past the 2-minute grace it is force-completed, with no second refresh.
    h.module.lastStorageProgressMs = 0L
    h.call(_.byteCodeAndStorageArms(StorageCoordinatorProgress(storageStats(completed = 5, active = 2, pending = 4))))

    h.module.refreshes should have size 1
    h.module.forceCompleteStorageSent shouldBe true
    h.storage.receiveAll() shouldBe Seq(StorageRangeCoordinator.ForceCompleteStorage)
  }

  it should "start the account stall budget from 0 after a dormant wake (#1551 item 3)" taggedAs UnitTest in {
    val h = new Harness()
    h.module.peers = Map(snapPeer("snap-1"))
    h.module.criticalFailureLimitReached = true
    // The round that went dormant had spent 9 of its 10 account stall refreshes.
    h.module.consecutiveAccountStallRefreshes = 9

    h.call(_.resetStagnationClocks(StagnationWatchdog.ResetReason.Wake))

    h.module.consecutiveAccountStallRefreshes shouldBe 0

    // The new round launches its accounts and stalls from the start: a full budget of 10 refreshes before escalating.
    h.call(_.resetStagnationClocks(StagnationWatchdog.ResetReason.AccountLaunch))
    val stalled = AccountCoordinatorProgress(accountStats(downloaded = 0L, completed = 0, pending = 3))
    (1 to 10).foreach { _ =>
      h.module.lastAccountProgressMs = 0L
      h.module.lastPivotRestartMs = 0L
      h.call(_.accountRangeArms(stalled))
    }

    h.module.refreshes should have size 10
    h.module.consecutiveAccountStallRefreshes shouldBe 10
    h.module.criticalFailures shouldBe empty
    h.module.dormantEntries shouldBe empty
  }

  it should "keep the account stall budget across a restart, so a stall -> refresh -> restart loop still goes dormant" taggedAs UnitTest in {
    val h = new Harness()
    h.module.peers = Map(snapPeer("snap-1"))
    h.module.criticalFailureLimitReached = true
    val stalled = AccountCoordinatorProgress(accountStats(downloaded = 0L, completed = 0, pending = 3))
    def stall(): Unit =
      h.module.lastAccountProgressMs = 0L
      h.module.lastPivotRestartMs = 0L
      h.call(_.accountRangeArms(stalled))

    // Each refresh's header bootstrap returns nothing and restartSnapSync runs. It calls no reset; the new round
    // resets only through its account launch.
    (1 to 10).foreach { _ =>
      stall()
      h.call(_.resetStagnationClocks(StagnationWatchdog.ResetReason.AccountLaunch))
    }

    h.module.refreshes should have size 10
    h.module.consecutiveAccountStallRefreshes shouldBe 10
    h.module.criticalFailures shouldBe empty

    // The 11th stall, in the 11th round, spends the budget: critical failure, then dormant.
    stall()

    h.module.refreshes should have size 10
    h.module.criticalFailures should have size 1
    h.module.criticalFailures.head should startWith("account stall refresh limit exceeded")
    h.module.dormantEntries should have size 1
  }

  it should "ignore progress reports outside their phase" taggedAs UnitTest in {
    val h = new Harness()
    h.module.currentPhase = SyncPhase.StateHealing

    h.call(_.accountRangeArms(AccountCoordinatorProgress(accountStats(downloaded = 100L, completed = 2, pending = 3))))
    h.call(_.byteCodeAndStorageArms(StorageCoordinatorProgress(storageStats(completed = 5, active = 2, pending = 4))))
    h.call(_.byteCodeAndStorageArms(ByteCodeCoordinatorProgress(ByteCodeCoordinator.ByteCodeProgress(0.5, 7L, 0L))))

    h.module.lastAccountTasksCompleted shouldBe 0
    h.module.lastAccountProgressMs shouldBe 0L
    h.module.lastStorageProgressMs shouldBe 0L
    h.module.lastBytecodeProgressCount shouldBe 0L
    h.module.refreshes shouldBe empty
    h.storage.hasMessages shouldBe false
    h.bytecodes.hasMessages shouldBe false
  }

  /** Run the stagnation tick on the module in the kit; yields the behaviour it returns. */
  private def tick(h: Harness): Behavior[Command] =
    var next: Behavior[Command] = null
    h.call(m => next = m.checkDownloadStagnationArms(CheckDownloadStagnation))
    next

  /** The kit's captured log lines that report the zero-SNAP-peer wait. */
  private def zeroPeerLines(h: Harness): Seq[String] =
    h.kit.logEntries().map(_.message).filter(_.startsWith("[STAGNATION] Zero SNAP peers"))

  it should "define the stagnation tick for CheckDownloadStagnation only" taggedAs UnitTest in {
    val h = new Harness()

    h.module.checkDownloadStagnationArms.isDefinedAt(CheckDownloadStagnation) shouldBe true
    h.module.checkDownloadStagnationArms.isDefinedAt(PollHandshakedPeers) shouldBe false
  }

  it should "run the proactive roll trigger on every tick, and ask no coordinator outside the download phases" taggedAs UnitTest in {
    val h = new Harness()
    h.module.currentPhase = SyncPhase.StateHealing

    tick(h) shouldBe Behaviors.same
    h.module.rollChecks shouldBe 1

    h.module.currentPhase = SyncPhase.Completed
    tick(h) shouldBe Behaviors.same
    h.module.rollChecks shouldBe 2

    h.accounts.hasMessages shouldBe false
    h.storage.hasMessages shouldBe false
    h.bytecodes.hasMessages shouldBe false
    h.module.lastAccountProgressMs shouldBe 0L
    h.module.lastStorageProgressMs shouldBe 0L
    h.module.refreshes shouldBe empty
  }

  it should "log the zero-SNAP-peer wait after 3 minutes without account progress, and only in account sync" taggedAs UnitTest in {
    val h = new Harness()
    h.module.accountRangeCoordinator = None
    h.module.storageRangeCoordinator = None
    h.module.bytecodeCoordinator = None

    // No SNAP peer and no account progress since long ago: the wait is logged, nothing is restarted.
    tick(h) shouldBe Behaviors.same
    zeroPeerLines(h) should have size 1
    zeroPeerLines(h).head should include("waiting for peers to reconnect")
    h.module.rollChecks shouldBe 1
    h.module.refreshes shouldBe empty
    h.module.criticalFailures shouldBe empty

    // A SNAP peer to download from: no wait to report.
    h.kit.clearLog()
    h.module.downloadPeers = Map(snapPeer("snap-1"))
    tick(h)
    zeroPeerLines(h) shouldBe empty

    // No SNAP peer, but account progress just now: not yet 3 minutes.
    h.module.downloadPeers = Map.empty
    h.module.lastAccountProgressMs = System.currentTimeMillis()
    tick(h)
    zeroPeerLines(h) shouldBe empty

    // No SNAP peer and a stale clock, but in the storage phase: not reported.
    h.module.lastAccountProgressMs = 0L
    h.module.currentPhase = SyncPhase.ByteCodeAndStorageSync
    tick(h)
    zeroPeerLines(h) shouldBe empty
    h.module.rollChecks shouldBe 4
  }

  /** The module in a live actor, so the progress asks run for real: `CheckDownloadStagnation` runs the tick, and every
    * other message (the piped answers) is forwarded to `piped`.
    */
  private def spawnTick(piped: ActorRef[Command])(setUp: StubStagnationWatchdogState => Unit): ActorRef[Command] =
    spawn(Behaviors.setup[Command] { ctx =>
      Behaviors.withTimers { timers =>
        val m = new StubStagnationWatchdogState(ctx, timers, SNAPSyncConfig()) with StagnationWatchdog
        setUp(m)
        Behaviors.receiveMessagePartial {
          case CheckDownloadStagnation => m.checkDownloadStagnationArms(CheckDownloadStagnation)
          case other =>
            piped ! other
            Behaviors.same
        }
      }
    })

  it should "ask the account coordinator for progress and pipe its answer, or zero stats after 2 seconds" taggedAs UnitTest in {
    val manualTime = ManualTime()
    val accounts = createTestProbe[AccountRangeCoordinator.Command]()
    val piped = createTestProbe[Command]()
    val live = spawnTick(piped.ref) { m =>
      m.currentPhase = SyncPhase.AccountRangeSync
      m.accountRangeCoordinator = Some(accounts.ref)
    }

    live ! CheckDownloadStagnation
    val stats = accountStats(100L, 2, 3)
    accounts.expectMessageType[AccountRangeCoordinator.AccountGetProgress].replyTo ! stats
    piped.expectMessage(AccountCoordinatorProgress(stats))

    // Unanswered: the 2-second ask fails and the arm pipes all-zero stats (which the account check reads as liveness).
    live ! CheckDownloadStagnation
    accounts.expectMessageType[AccountRangeCoordinator.AccountGetProgress]
    manualTime.timePasses(2001.millis)
    piped.expectMessage(AccountCoordinatorProgress(AccountRangeStats(0L, 0L, 0, 0, 0, 0.0, 0L, 0)))
  }

  it should "ask the storage and bytecode coordinators in the storage phase, and skip a completed bytecode phase" taggedAs UnitTest in {
    val manualTime = ManualTime()
    val storage = createTestProbe[StorageRangeCoordinator.Command]()
    val bytecodes = createTestProbe[ByteCodeCoordinator.Command]()
    val piped = createTestProbe[Command]()
    val live = spawnTick(piped.ref) { m =>
      m.currentPhase = SyncPhase.ByteCodeAndStorageSync
      m.storageRangeCoordinator = Some(storage.ref)
      m.bytecodeCoordinator = Some(bytecodes.ref)
    }

    live ! CheckDownloadStagnation
    val storageAsk = storage.expectMessageType[StorageRangeCoordinator.StorageGetProgress]
    bytecodes.expectMessageType[ByteCodeCoordinator.ByteCodeGetProgress]
    val stats = storageStats(5, 2, 4)
    storageAsk.replyTo ! stats
    piped.expectMessage(StorageCoordinatorProgress(stats))
    // The bytecode ask is left unanswered: after 2 seconds the arm pipes zero bytecode progress.
    manualTime.timePasses(2001.millis)
    piped.expectMessage(ByteCodeCoordinatorProgress(ByteCodeCoordinator.ByteCodeProgress(0.0, 0L, 0L)))

    val done = spawnTick(piped.ref) { m =>
      m.currentPhase = SyncPhase.ByteCodeAndStorageSync
      m.storageRangeCoordinator = Some(storage.ref)
      m.bytecodeCoordinator = Some(bytecodes.ref)
      m.bytecodePhaseComplete = true
    }
    done ! CheckDownloadStagnation
    storage.expectMessageType[StorageRangeCoordinator.StorageGetProgress].replyTo ! stats
    piped.expectMessage(StorageCoordinatorProgress(stats))
    bytecodes.expectNoMessage()
  }
