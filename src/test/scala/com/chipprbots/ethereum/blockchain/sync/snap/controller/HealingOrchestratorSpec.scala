package com.chipprbots.ethereum.blockchain.sync.snap.controller

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
import com.chipprbots.ethereum.blockchain.sync.snap.ChildSpawn
import com.chipprbots.ethereum.blockchain.sync.snap.RecordingChildFactories
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPRequestTracker
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncConfig
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.*
import com.chipprbots.ethereum.blockchain.sync.snap.SnapIntakeBudget
import com.chipprbots.ethereum.blockchain.sync.snap.StateValidator
import com.chipprbots.ethereum.blockchain.sync.snap.SyncProgressMonitor
import com.chipprbots.ethereum.blockchain.sync.snap.actors.ByteCodeCoordinator
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.BfsQueueStorage
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.FlatSlotStorage
import com.chipprbots.ethereum.db.storage.HealingFrontierStorage
import com.chipprbots.ethereum.db.storage.InMemoryBfsQueueStorage
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.db.storage.PathNodeStorage
import com.chipprbots.ethereum.db.storage.StateStorage
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.BlockchainWriter
import com.chipprbots.ethereum.domain.TrieRoot
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerEventBusActor
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.testing.Tags.UnitTest
import com.chipprbots.ethereum.utils.Config.SyncConfig

/** Everything `HealingOrchestrator` needs, as a stub (spec 016 FR-016 (b)): its `HealingOrchestratorState`, the hubs,
  * the environment, the coordinator handles, the phase flags and the callee Api traits. State is plain vars; one
  * ephemeral data source backs the app-state, flat-slot, code and trie stores; the parent and the peer manager are test
  * inboxes; the Api calls the tests reach are recorded; the members these tests never reach throw.
  */
private[snap] class StubHealingOrchestratorState(
    val ctx: ActorContext[SNAPSyncController.Command],
    val timers: TimerScheduler[SNAPSyncController.Command],
    val snapSyncConfig: SNAPSyncConfig,
    val syncController: ActorRef[SyncProtocol.SyncControllerReply],
    val networkPeerManager: ActorRef[NetworkPeerManagerActor.Command],
    val childFactories: ChildFactories,
    scheduler: org.apache.pekko.actor.Scheduler
) extends HealingOrchestratorState
    with SnapSharedState
    with SnapControllerEnv
    with CoordinatorHandles
    with PhaseFlags
    with ResumeApi
    with PeerPoolApi
    with SnapServerPeersApi
    with ValidationApi
    with FinalizationApi
    with PivotRefreshApi
    with PivotSelectionApi
    with ByteCodeRequestApi:

  // Records whether each write was synced (`updateSync`), and the keys it wrote (spec 022 T027).
  val syncedWrites: mutable.ArrayBuffer[(Boolean, Seq[ByteString])] = mutable.ArrayBuffer.empty
  private val dataSource = new EphemDataSource(Map.empty):
    private def keys(updates: Seq[com.chipprbots.ethereum.db.dataSource.DataUpdate]): Seq[ByteString] =
      updates.flatMap {
        case com.chipprbots.ethereum.db.dataSource.DataSourceUpdateOptimized(_, _, up) => up.map(u => ByteString(u._1))
        case com.chipprbots.ethereum.db.dataSource.DataSourceUpdate(_, _, up) => up.map(u => ByteString(u._1.toArray))
      }
    override def update(updates: Seq[com.chipprbots.ethereum.db.dataSource.DataUpdate]): Unit =
      syncedWrites += ((false, keys(updates)))
      super.update(updates)
    override def updateSync(updates: Seq[com.chipprbots.ethereum.db.dataSource.DataUpdate]): Unit =
      syncedWrites += ((true, keys(updates)))
      super.update(updates)

  // HealingOrchestratorState
  var trieWalkInProgress: Boolean = false
  var healRepegNoRootAttempts: Int = 0
  var healingServeRootRequestInFlight: Boolean = false
  var lastHealingServeRootBlock: Option[BigInt] = None
  val healingWalkLocalOnly: AtomicBoolean = new AtomicBoolean(false)
  var pendingPivotRefresh: Option[(BigInt, String)] = None
  var clPivotHint: Option[CLPivotHint] = None
  var healingValidatedRoot: Option[TrieRoot] = None
  var coordinatorGeneration: Long = 0
  val healedCodeHashes: mutable.LinkedHashSet[ByteString] = mutable.LinkedHashSet.empty
  def isPoSChain: Boolean = false
  def ec: ExecutionContext = notUsed("ec")
  def trieNodesCachedInMemory: Boolean = false

  // SnapSharedState
  var pivotBlock: Option[BigInt] = None
  var stateRoot: Option[TrieRoot] = None
  var currentPhase: SyncPhase = SyncPhase.StateHealing
  val progressMonitor: SyncProgressMonitor = new SyncProgressMonitor(scheduler)
  val requestTracker: SNAPRequestTracker = new SNAPRequestTracker()(scheduler)

  // SnapControllerEnv (ctx, timers, snapSyncConfig, syncController, networkPeerManager and childFactories are
  // constructor vals)
  def syncConfig: SyncConfig = notUsed("syncConfig")
  def asyncLog: org.slf4j.Logger = notUsed("asyncLog")
  val appStateStorage: AppStateStorage = new AppStateStorage(dataSource)
  val stateStorage: StateStorage = StateStorage.createTestStateStorage(dataSource)._1
  val evmCodeStorage: EvmCodeStorage = new EvmCodeStorage(dataSource)
  val flatSlotStorage: FlatSlotStorage = new FlatSlotStorage(dataSource)
  def validatorFactory: MptStorage => StateValidator = notUsed("validatorFactory")
  def snapValidationEc: ExecutionContext = notUsed("snapValidationEc")
  def blockchainReader: BlockchainReader = notUsed("blockchainReader")
  def blockchainWriter: BlockchainWriter = notUsed("blockchainWriter")
  def peerEventBus: ActorRef[PeerEventBusActor.Command] = notUsed("peerEventBus")
  var pathNodeStorageOpt: Option[PathNodeStorage] = None

  // The healing stores the healing spawn reads through `stateIngest` (spec 016 C-H1); in production they are the
  // controller's `HealingOrchestrator` lazy vals, here plain stores over the stub's data source.
  lazy val healingFrontierStore: Option[HealingFrontierStorage] = Some(new HealingFrontierStorage(dataSource))
  lazy val bfsQueueStore: Option[BfsQueueStorage] = Some(new InMemoryBfsQueueStorage())

  // StateIngest (spec 016 T055): forwards to the stores above, read on each call.
  val stateIngest: StateIngest = new ForwardingStateIngest(
    appStateStorage,
    stateStorage,
    flatSlotStorage,
    pathNodeStorageOpt,
    evmCodeStorage,
    blockchainReader,
    blockchainWriter,
    validatorFactory,
    healingFrontierStore,
    bfsQueueStore
  )

  // CoordinatorHandles
  val intakeBudget: SnapIntakeBudget =
    new SnapIntakeBudget(maxPendingStorageTasks = 10L, maxPendingByteCodeHashes = 10L)
  protected def stopChild(child: ActorRef[Nothing]): Unit = notUsed("stopChild")

  // Callee Api traits
  val mptStorage: MptStorage = stateStorage.getBackingStorage(BigInt(0))
  val mptStorageRequests: mutable.ArrayBuffer[BigInt] = mutable.ArrayBuffer.empty
  def getOrCreateMptStorage(pivotBlockNumber: BigInt): MptStorage =
    mptStorageRequests += pivotBlockNumber
    mptStorage
  def peersToDownloadFrom: Map[PeerId, PeerListSupportNg.PeerWithInfo] = Map.empty
  def calibratePivotTD(pivotBlockNumber: BigInt): Option[BigInt] = notUsed("calibratePivotTD")
  def snapServingPeers(): List[Peer] = Nil
  var snapServerSchedulerStarts: Int = 0
  def startSnapServerPeersScheduler(): Unit = snapServerSchedulerStarts += 1
  var validations: Int = 0
  def validateState(): Unit = validations += 1
  var completions: Int = 0
  def completeSnapSync(): Behavior[Command] =
    completions += 1
    Behaviors.same
  val refreshes: mutable.ArrayBuffer[(String, Boolean, Boolean)] = mutable.ArrayBuffer.empty
  def refreshPivotInPlace(
      reason: String,
      countsTowardHealBudget: Boolean = true,
      pivotUnservable: Boolean = false
  ): Unit = refreshes += ((reason, countsTowardHealBudget, pivotUnservable))
  var networkBest: Option[BigInt] = None
  def currentNetworkBestFromSnapPeers(): Option[BigInt] = networkBest
  var byteCodeRequests: Int = 0
  def requestByteCodes(): Unit = byteCodeRequests += 1

  private def notUsed(what: String): Nothing =
    throw new UnsupportedOperationException(s"$what is not reached by HealingOrchestratorSpec")

/** Spec 016 M6b stub test (FR-016 (b), T048): `HealingOrchestrator` mixed into a stub of its state interface,
  * capabilities and callee Api traits, inside a `BehaviorTestKit` so that `ctx.spawn`, `ctx.log` and the timers are
  * real (spawns and timers are recorded as effects). Each module call runs while the kit processes a message, as it
  * would in the controller. The healing spawn reads `coordinatorGeneration` and `healingWalkLocalOnly` and writes
  * `trieWalkInProgress` and `healRepegNoRootAttempts`; the serve-root check reads `pendingPivotRefresh`, `isPoSChain`
  * and `clPivotHint` and writes `healingServeRootRequestInFlight` or `lastHealingServeRootBlock`; the healed-code hold
  * reads and writes `healedCodeHashes` and `coordinatorGeneration`; the walk-complete arm writes `trieWalkInProgress`
  * and `healingValidatedRoot`.
  */
class HealingOrchestratorSpec extends ScalaTestWithActorTestKit(ManualTime.config) with AnyFlatSpecLike with Matchers:

  private val pivot = BigInt(1_000)
  private val root = TrieRoot(ByteString(Array.fill[Byte](32)(3)))

  /** The module on its stub, the kit that runs it, and the inboxes it talks to. */
  final private class Harness(config: SNAPSyncConfig = SNAPSyncConfig()):
    val parent: TestInbox[SyncProtocol.SyncControllerReply] = TestInbox[SyncProtocol.SyncControllerReply]()
    val spawns: TestInbox[ChildSpawn] = TestInbox[ChildSpawn]()
    val factories: RecordingChildFactories = new RecordingChildFactories(spawns.ref)
    private var action: () => Unit = () => ()
    private var captured: StubHealingOrchestratorState & HealingOrchestrator = null
    val kit: BehaviorTestKit[Command] = BehaviorTestKit(Behaviors.setup[Command] { ctx =>
      Behaviors.withTimers { timers =>
        captured = new StubHealingOrchestratorState(
          ctx,
          timers,
          config,
          parent.ref,
          TestInbox[NetworkPeerManagerActor.Command]().ref,
          factories,
          system.classicSystem.scheduler
        ) with HealingOrchestrator
        Behaviors.receiveMessage { _ =>
          action()
          Behaviors.same
        }
      }
    })
    val module: StubHealingOrchestratorState & HealingOrchestrator = captured

    /** Run `f` on the module while the kit processes a message. */
    def call(f: StubHealingOrchestratorState & HealingOrchestrator => Unit): Unit =
      action = () => f(module)
      kit.run(PollHandshakedPeers)

    /** The children spawned since the last look, by name, and the timer effects. */
    def effects(): Seq[Effect] = kit.retrieveAllEffects()

  private def spawnedNames(effects: Seq[Effect]): Seq[String] =
    effects.collect { case s: Effect.Spawned[?] => s.childName }

  private def scheduledTimers(effects: Seq[Effect]): Seq[(Any, Any, FiniteDuration)] =
    effects.collect { case t: Effect.TimerScheduled[?] => (t.key, t.msg, t.delay) }

  "HealingOrchestrator" should "spawn the healing coordinator once, resetting the walk flag and the re-peg budget" taggedAs UnitTest in {
    val config = SNAPSyncConfig()
    val h = new Harness(config)
    h.module.pivotBlock = Some(pivot)
    h.module.stateRoot = Some(root)
    h.module.trieWalkInProgress = true
    h.module.healRepegNoRootAttempts = 7
    h.module.coordinatorGeneration = 3

    h.call(_.startStateHealing())

    h.module.trieWalkInProgress shouldBe false
    h.module.healRepegNoRootAttempts shouldBe 0
    h.module.trieNodeHealingCoordinator.isDefined shouldBe true
    h.module.mptStorageRequests.toSeq shouldBe Seq(pivot)
    h.module.snapServerSchedulerStarts shouldBe 1
    val effects = h.effects()
    spawnedNames(effects) shouldBe Seq("trie-node-healing-coordinator-3")
    scheduledTimers(effects) shouldBe Seq((RequestTrieNodeHealing, RequestTrieNodeHealing, 1.second))
    val spawn = h.spawns.receiveAll() match
      case Seq(s: ChildSpawn.Healing) => s
      case other                      => fail(s"expected one healing spawn, got $other")
    spawn.stateRoot shouldBe root.value
    (spawn.mptStorage should be).theSameInstanceAs(h.module.mptStorage)
    spawn.walkLocalOnly.exists(_ eq h.module.healingWalkLocalOnly) shouldBe true
    withClue("spec 022 D5, T038b: production works the excluded set, and a restart finds the current root: ") {
      spawn.excludedAccounts shouldBe true
      h.factories.lastHealingCurrentRoot.get().exists(_ eq h.module.healingCurrentRoot) shouldBe true
      h.module.healingCurrentRoot.get() shouldBe spawn.stateRoot
    }
    spawn.frontierPersistenceEnabled shouldBe config.healingFrontierPersistence
    spawn.healingFrontierStorage shouldBe defined
    spawn.bfsQueueStorageOpt shouldBe defined
    h.kit.childInbox[TrieNodeHealingCoordinator.Command]("trie-node-healing-coordinator-3").receiveAll() shouldBe Seq(
      TrieNodeHealingCoordinator.StartTrieNodeHealing(root.value),
      TrieNodeHealingCoordinator.UpdateMaxInFlightPerPeer(config.healingMaxInFlightPerPeer)
    )

    // A second start finds the coordinator in place and changes nothing.
    h.module.trieWalkInProgress = true
    h.call(_.startStateHealing())

    h.module.trieWalkInProgress shouldBe true
    h.factories.healingSpawns.get shouldBe 1
    spawnedNames(h.effects()) shouldBe empty
  }

  it should "ask the parent for a newer serve root once, and not while a pivot refresh is pending" taggedAs UnitTest in {
    val h = new Harness(SNAPSyncConfig(decoupledHealServeRoot = true, movingRootDeltaHeal = false))
    h.module.trieNodeHealingCoordinator = Some(TestInbox[TrieNodeHealingCoordinator.Command]().ref)
    h.module.networkBest = Some(pivot)
    h.module.pendingPivotRefresh = Some((BigInt(900), "pending"))

    h.call(_.maybeRequestHealingServeRoot())

    h.module.healingServeRootRequestInFlight shouldBe false
    h.parent.hasMessages shouldBe false

    h.module.pendingPivotRefresh = None
    h.call(_.maybeRequestHealingServeRoot())

    h.module.healingServeRootRequestInFlight shouldBe true
    h.parent.receiveAll() shouldBe Seq(SNAPSyncController.RequestHealingServeRoot)
    h.module.refreshes shouldBe empty

    // The in-flight latch holds the next tick back.
    h.call(_.maybeRequestHealingServeRoot())

    h.parent.hasMessages shouldBe false
  }

  it should "re-peg the heal root under moving-root delta heal, unless the walk is local-only" taggedAs UnitTest in {
    val h = new Harness(SNAPSyncConfig(movingRootDeltaHeal = true))
    h.module.trieNodeHealingCoordinator = Some(TestInbox[TrieNodeHealingCoordinator.Command]().ref)
    h.module.networkBest = Some(pivot)
    h.module.healingWalkLocalOnly.set(true)

    h.call(_.maybeRequestHealingServeRoot())

    h.module.refreshes shouldBe empty
    h.module.lastHealingServeRootBlock shouldBe None

    h.module.healingWalkLocalOnly.set(false)
    h.call(_.maybeRequestHealingServeRoot())

    val target = pivot - 64
    h.module.refreshes.toSeq shouldBe Seq(("spec009 moving-root re-peg: heal root stale", false, false))
    h.module.lastHealingServeRootBlock shouldBe Some(target)
    h.module.healingServeRootRequestInFlight shouldBe false
    h.parent.hasMessages shouldBe false
  }

  it should "queue the missing bytecode of healed accounts, spawning a bytecode coordinator when none exists" taggedAs UnitTest in {
    val h = new Harness()
    val present = ByteString(Array.fill[Byte](32)(1))
    val missing = ByteString(Array.fill[Byte](32)(2))
    h.module.evmCodeStorage.put(present, ByteString("code")).commit()
    h.module.coordinatorGeneration = 3

    h.call(_.queueHealedCode(Seq(present, missing, Account.EmptyCodeHash.value)))

    h.module.healedCodeHashes.toSeq shouldBe Seq(missing)
    h.module.coordinatorGeneration shouldBe 4L
    h.module.bytecodeCoordinator.isDefined shouldBe true
    h.module.byteCodeRequests shouldBe 1
    val effects = h.effects()
    spawnedNames(effects) shouldBe Seq("bytecode-coordinator-4")
    scheduledTimers(effects) shouldBe Seq((RequestByteCodes, RequestByteCodes, 1.second))
    h.kit.childInbox[ByteCodeCoordinator.Command]("bytecode-coordinator-4").receiveAll() shouldBe Seq(
      ByteCodeCoordinator.StartByteCodeSync(Seq.empty),
      ByteCodeCoordinator.AddByteCodeTasks(Seq(missing), skipQueued = true),
      ByteCodeCoordinator.NoMoreByteCodeTasks
    )

    // Once the bytecode is stored, the hold forgets it.
    h.module.evmCodeStorage.put(missing, ByteString("code")).commit()
    h.call(_.dropHealedCodeNowPresent())

    h.module.healedCodeHashes shouldBe empty
  }

  it should "send healed bytecode to the existing coordinator without a new spawn" taggedAs UnitTest in {
    val h = new Harness()
    val missing = ByteString(Array.fill[Byte](32)(2))
    val coordinator = TestInbox[ByteCodeCoordinator.Command]()
    h.module.bytecodeCoordinator = Some(coordinator.ref)
    h.module.coordinatorGeneration = 3

    h.call(_.queueHealedCode(Seq(missing)))

    h.module.healedCodeHashes.toSeq shouldBe Seq(missing)
    h.module.coordinatorGeneration shouldBe 3L
    spawnedNames(h.effects()) shouldBe empty
    coordinator.receiveAll() shouldBe Seq(
      ByteCodeCoordinator.AddByteCodeTasks(Seq(missing), skipQueued = true),
      ByteCodeCoordinator.NoMoreByteCodeTasks
    )
  }

  it should "forget the healed codeHashes and the bytecode force-complete on reset" taggedAs UnitTest in {
    val h = new Harness()
    h.module.healedCodeHashes += ByteString(Array.fill[Byte](32)(5))
    h.module.bytecodeForceCompleted = true

    h.call(_.resetHealedCodeHold())

    h.module.healedCodeHashes shouldBe empty
    h.module.bytecodeForceCompleted shouldBe false
  }

  // spec 022 FR-011: the Hash controller's own way to completion yields to the excluded set.
  private val excludedEntry =
    com.chipprbots.ethereum.db.storage.ExcludedAccount(
      ByteString(Array.fill[Byte](32)(6)),
      com.chipprbots.ethereum.db.storage.ExcludedReason.StorageSkipped
    )

  for (name, walkResult) <- Seq[(String, Command)](
      "TrieWalkComplete(0)" -> TrieWalkComplete(0),
      "TrieWalkResult(empty)" -> TrieWalkResult(Seq.empty)
    )
  do
    it should s"not complete on $name while the excluded set is not empty: the coordinator goes on with it" taggedAs UnitTest in {
      val h = new Harness()
      val coordinator = TestInbox[TrieNodeHealingCoordinator.Command]()
      h.module.trieNodeHealingCoordinator = Some(coordinator.ref)
      h.module.currentPhase = SyncPhase.StateHealing
      h.module.pivotBlock = Some(BigInt(1000))
      h.module.stateRoot = Some(TrieRoot(ByteString(Array.fill[Byte](32)(7))))
      h.module.stateIngest.snapProgress.recordExcluded(Seq(excludedEntry))

      h.call(_.stateHealingArms(walkResult))

      h.module.currentPhase shouldBe SyncPhase.StateHealing
      h.module.validations shouldBe 0
      h.module.healingValidatedRoot shouldBe None
      coordinator.receiveAll() should contain(TrieNodeHealingCoordinator.HealingCheckCompletion)
    }

  it should "set the current heal root as a re-peg goes out, for a coordinator a supervisor restarts (T038b)" taggedAs UnitTest in {
    val h = new Harness()
    val moved = ByteString(Array.fill[Byte](32)(0x5b))
    h.call(_.pivotRefreshed(moved))
    h.module.healingCurrentRoot.get() shouldBe moved
  }

  it should "start a coordinator to work the excluded set when a clean trie walk finds none running" taggedAs UnitTest in {
    val h = new Harness()
    h.module.currentPhase = SyncPhase.StateHealing
    h.module.pivotBlock = Some(BigInt(1000))
    h.module.stateRoot = Some(TrieRoot(ByteString(Array.fill[Byte](32)(7))))
    h.module.stateIngest.snapProgress.recordExcluded(Seq(excludedEntry))

    h.call(_.stateHealingArms(TrieWalkComplete(0)))

    h.module.trieNodeHealingCoordinator shouldBe defined
    h.module.currentPhase shouldBe SyncPhase.StateHealing
    h.module.validations shouldBe 0
  }

  it should "finish healing on a clean trie walk and enter validation" taggedAs UnitTest in {
    val h = new Harness()
    val coordinator = TestInbox[TrieNodeHealingCoordinator.Command]()
    h.module.trieNodeHealingCoordinator = Some(coordinator.ref)
    h.module.pivotBlock = Some(pivot)
    h.module.stateRoot = Some(root)
    h.module.trieWalkInProgress = true

    h.call(_.stateHealingArms(TrieWalkComplete(0)))

    h.module.trieWalkInProgress shouldBe false
    h.module.healingValidatedRoot shouldBe Some(root)
    h.module.currentPhase shouldBe SyncPhase.StateValidation
    h.module.validations shouldBe 1
    h.module.appStateStorage.getSnapSyncPivotBlock() shouldBe Some(pivot)
    h.module.appStateStorage.getSnapSyncStateRoot() shouldBe Some(root.value)
    coordinator.receiveAll() shouldBe Seq(TrieNodeHealingCoordinator.WalkStateChanged(false))
  }

  it should "persist the healed pivot block and root in one synced write, on every healing exit (spec 022 T027)" taggedAs UnitTest in {
    val exits: Seq[(String, StubHealingOrchestratorState & HealingOrchestrator => Unit)] = Seq(
      "TrieWalkComplete(0)" -> (_.stateHealingArms(TrieWalkComplete(0))),
      "TrieWalkResult(none)" -> (_.stateHealingArms(TrieWalkResult(Seq.empty))),
      "completeHealingWalkClean" -> (_.completeHealingWalkClean())
    )
    exits.foreach { case (exit, run) =>
      withClue(s"$exit: ") {
        val h = new Harness()
        h.module.pivotBlock = Some(pivot)
        h.module.stateRoot = Some(root)
        h.module.trieWalkInProgress = true
        h.module.syncedWrites.clear()
        h.call(run)
        val anchorWrites = h.module.syncedWrites.filter(_._2.size == 2)
        anchorWrites.map(_._1) shouldBe Seq(true) // one write, both anchors, synced
        h.module.syncedWrites.filterNot(_._1) shouldBe empty // nothing else unsynced on the way out
        h.module.appStateStorage.getSnapSyncPivotBlock() shouldBe Some(pivot)
        h.module.appStateStorage.getSnapSyncStateRoot() shouldBe Some(root.value)
      }
    }
  }

  it should "schedule another walk round while nodes are still missing" taggedAs UnitTest in {
    val h = new Harness()
    h.module.stateRoot = Some(root)
    h.module.trieWalkInProgress = true

    h.call(_.stateHealingArms(TrieWalkComplete(5)))

    h.module.trieWalkInProgress shouldBe false
    h.module.healingValidatedRoot shouldBe None
    h.module.currentPhase shouldBe SyncPhase.StateHealing
    h.module.validations shouldBe 0
    scheduledTimers(h.effects()) shouldBe Seq(("scheduled-trie-walk", ScheduledTrieWalk, 2.minutes))
  }

  // #1555 fix (a): "all healing peers stateless" is a report that the root is unservable, so the refresh must carry
  // pivotUnservable = true (the #1478 fallback) and count toward the heal budget.
  it should "refresh the pivot as KNOWN unservable when every healing peer is stateless" taggedAs UnitTest in {
    val h = new Harness()
    h.module.pivotBlock = Some(pivot)
    h.module.stateRoot = Some(root)

    h.call(_.stateHealingArms(HealingAllPeersStateless))

    h.module.refreshes.toSeq shouldBe Seq(("all healing peers stateless", true, true))
    h.module.completions shouldBe 0
  }

  // #1555 fix (b): under the Path scheme a lazy hand-off with the pivot root absent can only fail the Path publish, so
  // HealingRootUnservable re-pegs (as unservable) instead. Hash scheme: the hand-off is unchanged.
  it should "re-peg instead of handing off when the heal root is unservable and absent under the Path scheme" taggedAs UnitTest in {
    val h = new Harness()
    h.module.pathNodeStorageOpt = Some(new PathNodeStorage(EphemDataSource()))
    h.module.pivotBlock = Some(pivot)
    h.module.stateRoot = Some(root)

    h.call(_.stateHealingArms(HealingRootUnservable(root.value)))

    h.module.completions shouldBe 0
    h.module.refreshes.toSeq shouldBe Seq(("heal root absent locally (Path scheme, lazy hand-off blocked)", true, true))
    h.module.appStateStorage.getSnapSyncPivotBlock() shouldBe None
  }

  it should "anchor and hand off to lazy healing when the heal root is unservable under the Hash scheme" taggedAs UnitTest in {
    val h = new Harness()
    h.module.pivotBlock = Some(pivot)
    h.module.stateRoot = Some(root)

    h.call(_.stateHealingArms(HealingRootUnservable(root.value)))

    h.module.completions shouldBe 1
    h.module.refreshes shouldBe empty
    h.module.appStateStorage.getSnapSyncPivotBlock() shouldBe Some(pivot)
  }

  it should "anchor the in-memory pivot and hand off to lazy healing when healing is abandoned" taggedAs UnitTest in {
    val h = new Harness()
    h.module.pivotBlock = Some(pivot)
    h.module.stateRoot = Some(root)

    h.call(_.stateHealingArms(StateHealingAbandoned))

    h.module.completions shouldBe 1
    h.module.appStateStorage.getSnapSyncPivotBlock() shouldBe Some(pivot)
    h.module.appStateStorage.getSnapSyncStateRoot() shouldBe Some(root.value)
  }
