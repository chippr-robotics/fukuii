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
import scala.util.Failure
import scala.util.Success

import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.PeerListSupportNg
import com.chipprbots.ethereum.blockchain.sync.SyncProtocol
import com.chipprbots.ethereum.blockchain.sync.snap.ChildSpawn
import com.chipprbots.ethereum.blockchain.sync.snap.ContractTaskFiles
import com.chipprbots.ethereum.blockchain.sync.snap.RecordingChildFactories
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPRequestTracker
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncConfig
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.*
import com.chipprbots.ethereum.blockchain.sync.snap.SnapIntakeBudget
import com.chipprbots.ethereum.blockchain.sync.snap.StateValidator
import com.chipprbots.ethereum.blockchain.sync.snap.StorageTask
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
import com.chipprbots.ethereum.db.storage.SnapStorageDoneStorage
import com.chipprbots.ethereum.db.storage.SnapSyncProgressStorage
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
import com.chipprbots.ethereum.network.p2p.messages.SNAP
import com.chipprbots.ethereum.testing.Tags.UnitTest
import com.chipprbots.ethereum.utils.Config.SyncConfig

/** Everything `DownloadSupervisor` needs, as a stub (spec 016 FR-016 (b)): its `DownloadSupervisorState`, the hubs, the
  * environment, the coordinator handles, the phase flags and the callee Api traits. State is plain vars; one ephemeral
  * data source backs the app-state, progress, flat-slot, code and trie stores; the peer manager is a test inbox; the
  * peer lists are settable; the Api calls are recorded; the members these tests never reach throw.
  */
private[snap] class StubDownloadSupervisorState(
    val ctx: ActorContext[SNAPSyncController.Command],
    val timers: TimerScheduler[SNAPSyncController.Command],
    val snapSyncConfig: SNAPSyncConfig,
    val networkPeerManager: ActorRef[NetworkPeerManagerActor.Command],
    val childFactories: ChildFactories,
    scheduler: org.apache.pekko.actor.Scheduler
) extends DownloadSupervisorState
    with SnapSharedState
    with SnapControllerEnv
    with CoordinatorHandles
    with PhaseFlags
    with PeerPoolApi
    with SnapServerPeersApi
    with SnapPeerEvictionApi
    with ChainDownloadStartApi
    with FinalizationApi
    with HealingApi
    with LifecycleApi
    with PivotProbeResponseApi
    with StagnationResetApi
    with StagnationScheduleApi
    with StorageDoneMarkersApi
    with ResumeApi
    with HeapWatchdogApi
    with AccountResumeSelectionApi:

  private val dataSource = EphemDataSource()

  // DownloadSupervisorState
  var coordinatorGeneration: Long = 0L
  val snapProgressStorage: SnapSyncProgressStorage = new SnapSyncProgressStorage(dataSource)
  var recordStorageDone: Boolean = false
  def SnapCapabilityCheckKey: String = "snap-capability-check"
  var pivotProbeRequestId: Option[BigInt] = None
  var consecutivePivotRefreshes: Int = 0
  var criticalFailureCount: Int = 0
  var accountsAtLastCriticalFailure: Long = 0L
  var dormantRetryCount: Int = 0
  var lastAccountProgressMs: Long = 0L
  var lastBytecodeProgressMs: Long = 0L
  var lastStorageProgressMs: Long = 0L
  var storageStagnationRefreshAttempted: Boolean = false
  var storageContractProgressPct: Double = 0.0
  var bytecodesEstimatedTotal: Long = 0L
  var preservedRangeProgress: Map[ByteString, ByteString] = Map.empty
  var preservedAtPivotBlock: Option[BigInt] = None
  var preservedTaskFiles: Option[ContractTaskFiles] = None
  var launchedAccountGeneration: Long = -1L
  var currentCarrySource: Option[ContractTaskFiles] = None
  val storageDoneStorage: SnapStorageDoneStorage = new SnapStorageDoneStorage(dataSource)
  def ec: ExecutionContext = notUsed("ec")

  // SnapSharedState
  var pivotBlock: Option[BigInt] = None
  var stateRoot: Option[TrieRoot] = None
  var currentPhase: SyncPhase = SyncPhase.AccountRangeSync
  val progressMonitor: SyncProgressMonitor = new SyncProgressMonitor(scheduler)
  val requestTracker: SNAPRequestTracker = new SNAPRequestTracker()(scheduler)

  // SnapControllerEnv (ctx, timers, snapSyncConfig, networkPeerManager and childFactories are constructor vals)
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
  def syncController: ActorRef[SyncProtocol.SyncControllerReply] = notUsed("syncController")
  var pathNodeStorageOpt: Option[PathNodeStorage] = None

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
  val intakeBudget: SnapIntakeBudget =
    new SnapIntakeBudget(maxPendingStorageTasks = 10L, maxPendingByteCodeHashes = 10L)
  protected def stopChild(child: ActorRef[Nothing]): Unit = notUsed("stopChild")

  // Callee Api traits
  var downloadPeers: Map[PeerId, PeerListSupportNg.PeerWithInfo] = Map.empty
  def peersToDownloadFrom: Map[PeerId, PeerListSupportNg.PeerWithInfo] = downloadPeers
  def calibratePivotTD(pivotBlockNumber: BigInt): Option[BigInt] = notUsed("calibratePivotTD")
  var servingPeers: List[Peer] = Nil
  def snapServingPeers(): List[Peer] = servingPeers
  var snapServerSchedulerStarts: Int = 0
  def startSnapServerPeersScheduler(): Unit = snapServerSchedulerStarts += 1
  var evictionStarts: Int = 0
  def startSnapPeerEviction(): Unit = evictionStarts += 1
  var chainDownloaderStarts: Int = 0
  def startChainDownloader(): Unit = chainDownloaderStarts += 1
  var completions: Int = 0
  def completeSnapSync(): Behavior[Command] =
    completions += 1
    Behaviors.same
  def triggerHealingForMissingNodes(missingNodes: Seq[ByteString]): Unit = notUsed("triggerHealingForMissingNodes")
  var healingStarts: Int = 0
  def startStateHealing(): Unit = healingStarts += 1
  val launches: mutable.ArrayBuffer[(TrieRoot, Int)] = mutable.ArrayBuffer.empty
  def recordCriticalFailure(reason: String): Boolean = notUsed("recordCriticalFailure")
  val dormantReasons: mutable.ArrayBuffer[String] = mutable.ArrayBuffer.empty
  def enterDormantMode(reason: String): Behavior[Command] =
    dormantReasons += reason
    Behaviors.same
  val restartReasons: mutable.ArrayBuffer[String] = mutable.ArrayBuffer.empty
  def restartSnapSync(reason: String): Behavior[Command] =
    restartReasons += reason
    Behaviors.same
  val probeAnswers: mutable.ArrayBuffer[SNAP.AccountRange] = mutable.ArrayBuffer.empty
  def handlePivotProbeResponse(msg: SNAP.AccountRange): Unit = probeAnswers += msg
  val stagnationResets: mutable.ArrayBuffer[StagnationWatchdog.ResetReason] = mutable.ArrayBuffer.empty
  def resetStagnationClocks(reason: StagnationWatchdog.ResetReason): Unit = stagnationResets += reason
  var stagnationSchedules: Int = 0
  def scheduleStagnationChecks(): Unit = stagnationSchedules += 1
  val markerClears: mutable.ArrayBuffer[String] = mutable.ArrayBuffer.empty
  def clearStorageDoneMarkers(reason: String): Unit = markerClears += reason
  lazy val launchStorage: MptStorage = stateStorage.getBackingStorage(BigInt(0))
  val mptStorageRequests: mutable.ArrayBuffer[BigInt] = mutable.ArrayBuffer.empty
  def getOrCreateMptStorage(pivotBlockNumber: BigInt): MptStorage =
    mptStorageRequests += pivotBlockNumber
    launchStorage
  var heapWatchdogStarts: Int = 0
  def ensureHeapWatchdog(): Unit = heapWatchdogStarts += 1
  var resumeSelection: (Map[ByteString, ByteString], Option[ContractTaskFiles]) = (Map.empty, None)
  val resumeSelections: mutable.ArrayBuffer[(TrieRoot, Int, BigInt)] = mutable.ArrayBuffer.empty
  def selectAccountResume(
      rootHash: TrieRoot,
      effectiveConcurrency: Int,
      currentPivot: BigInt
  ): (Map[ByteString, ByteString], Option[ContractTaskFiles]) =
    resumeSelections += ((rootHash, effectiveConcurrency, currentPivot))
    resumeSelection
  var heldResumeReleases: Int = 0
  def releaseHeldResumeCheckpoint(): Unit = heldResumeReleases += 1

  private def notUsed(what: String): Nothing =
    throw new UnsupportedOperationException(s"$what is not reached by DownloadSupervisorSpec")

/** Spec 016 M11 stub test (FR-016 (b), T053): `DownloadSupervisor` mixed into a stub of its state interface,
  * capabilities and callee Api traits, inside a `BehaviorTestKit` so that `ctx.spawn`, `ctx.log` and the timers are
  * real (spawns and timers are recorded as effects, never fired). Each module call runs while the kit processes a
  * message, as it would in the controller. Nothing waits on a clock. The bodies read `coordinatorGeneration`,
  * `snapProgressStorage`, `recordStorageDone` and `SnapCapabilityCheckKey`, and write the coordinator handles, the
  * phase and `forceCompleteStorageSent`.
  */
class DownloadSupervisorSpec extends ScalaTestWithActorTestKit(ManualTime.config) with AnyFlatSpecLike with Matchers:

  private val root = TrieRoot(ByteString(Array.fill[Byte](32)(5)))

  /** The module on its stub, the kit that runs it, and the recorded child spawns. */
  final private class Harness(config: SNAPSyncConfig = SNAPSyncConfig(), stubLaunch: Boolean = true):
    val spawns: TestInbox[ChildSpawn] = TestInbox[ChildSpawn]()
    val factories: RecordingChildFactories = new RecordingChildFactories(spawns.ref)
    private var action: () => Unit = () => ()
    private var captured: StubDownloadSupervisorState & DownloadSupervisor = null
    val kit: BehaviorTestKit[Command] = BehaviorTestKit(Behaviors.setup[Command] { ctx =>
      Behaviors.withTimers { timers =>
        captured = new StubDownloadSupervisorState(
          ctx,
          timers,
          config,
          TestInbox[NetworkPeerManagerActor.Command]().ref,
          factories,
          system.classicSystem.scheduler
        ) with DownloadSupervisor:
          // The M11 cases stub the account launch, as the M11 stub did; the M11b launch case runs the module's own.
          override def launchAccountRangeWorkers(rootHash: TrieRoot, concurrency: Int): Unit =
            if stubLaunch then launches += ((rootHash, concurrency))
            else super.launchAccountRangeWorkers(rootHash, concurrency)
        Behaviors.receiveMessage { _ =>
          action()
          Behaviors.same
        }
      }
    })
    val module: StubDownloadSupervisorState & DownloadSupervisor = captured

    /** Run `f` on the module while the kit processes a message. */
    def call(f: StubDownloadSupervisorState & DownloadSupervisor => Unit): Unit =
      action = () => f(module)
      kit.run(PollHandshakedPeers)

    def effects(): Seq[Effect] = kit.retrieveAllEffects()

  private def spawnedNames(effects: Seq[Effect]): Seq[String] =
    effects.collect { case s: Effect.Spawned[?] => s.childName }

  private def scheduledTimers(effects: Seq[Effect]): Seq[(Any, Any, FiniteDuration)] =
    effects.collect { case t: Effect.TimerScheduled[?] => (t.key, t.msg, t.delay) }

  private def peer(id: String, snap: Boolean = true): (PeerId, PeerListSupportNg.PeerWithInfo) =
    val p = Peer(PeerId(id), new InetSocketAddress("127.0.0.1", 1), TestInbox[PeerActor.Command]().ref, false)
    val status = RemoteStatus(
      capability = Capability.ETH68,
      networkId = 1,
      chainWeight = ChainWeight.zero,
      bestHash = ByteString("head"),
      genesisHash = ByteString("genesis"),
      supportsSnap = snap
    )
    val info =
      PeerInfo(
        status,
        ChainWeight.zero,
        forkAccepted = true,
        maxBlockNumber = BigInt(1000),
        bestBlockHash = status.bestHash
      )
    p.id -> PeerListSupportNg.PeerWithInfo(p, info)

  "DownloadSupervisor" should "start the account phase on a snap peer: launch, eviction, chain download, snap-server dials" taggedAs UnitTest in {
    val config = SNAPSyncConfig()
    val h = new Harness(config)
    h.module.downloadPeers = Map(peer("a"), peer("b", snap = false))

    h.call(_.startAccountRangeSync(root))

    h.module.launches.toSeq shouldBe Seq((root, config.accountConcurrency.max(1)))
    h.module.evictionStarts shouldBe 1
    h.module.chainDownloaderStarts shouldBe 1
    h.module.snapServerSchedulerStarts shouldBe 1
    scheduledTimers(h.effects()) shouldBe empty
  }

  it should "schedule the snap-capability check under its key, and launch nothing, when no peer serves snap" taggedAs UnitTest in {
    val config = SNAPSyncConfig(snapCapabilityGracePeriod = 45.seconds)
    val h = new Harness(config)
    h.module.downloadPeers = Map(peer("a", snap = false))

    h.call(_.startAccountRangeSync(root))

    scheduledTimers(h.effects()) shouldBe Seq(("snap-capability-check", CheckSnapCapability, 45.seconds))
    h.module.launches shouldBe empty
    h.module.evictionStarts shouldBe 0
    h.module.chainDownloaderStarts shouldBe 0
    h.module.snapServerSchedulerStarts shouldBe 0
  }

  it should "spawn the recovery bytecode and storage coordinators at the recovery budget" taggedAs UnitTest in {
    val h = new Harness()
    val storage = h.module.stateStorage.getBackingStorage(BigInt(0))
    h.module.coordinatorGeneration = 5L
    h.module.recordStorageDone = true
    h.module.forceCompleteStorageSent = true

    h.call(_.spawnRecoveryCoordinators(root.value, storage, bytecodeAlreadyDone = false, storageAlreadyDone = false))

    h.module.bytecodeCoordinator shouldBe defined
    h.module.storageRangeCoordinator shouldBe defined
    h.module.forceCompleteStorageSent shouldBe false
    val effects = h.effects()
    spawnedNames(effects) shouldBe Seq("bytecode-coordinator-5", "storage-range-coordinator-5")
    scheduledTimers(effects) shouldBe Seq(
      (RequestByteCodes, RequestByteCodes, 1.second),
      (RequestStorageRanges, RequestStorageRanges, 1.second)
    )
    h.spawns.receiveAll() match
      case Seq(b: ChildSpawn.ByteCode, s: ChildSpawn.StorageRange) =>
        b.intakeBudget.exists(_ eq h.module.intakeBudget) shouldBe true
        s.stateRoot shouldBe root.value
        (s.mptStorage should be).theSameInstanceAs(storage)
        s.initialMaxInFlightPerPeer shouldBe 3
        s.recordStorageDone shouldBe true
        s.snapProgressStorage.exists(_ eq h.module.snapProgressStorage) shouldBe true
        s.intakeBudget.exists(_ eq h.module.intakeBudget) shouldBe true
      case other => fail(s"expected a bytecode spawn then a storage spawn, got $other")
    h.kit.childInbox[ByteCodeCoordinator.Command]("bytecode-coordinator-5").receiveAll() shouldBe Seq(
      ByteCodeCoordinator.StartByteCodeSync(Seq.empty),
      ByteCodeCoordinator.UpdateMaxInFlightPerPeer(2)
    )
    h.kit.childInbox[StorageRangeCoordinator.Command]("storage-range-coordinator-5").receiveAll() shouldBe Seq(
      StorageRangeCoordinator.StartStorageRangeSync(root.value)
    )
  }

  it should "spawn nothing for phases already done, and still cap an existing bytecode coordinator at 2" taggedAs UnitTest in {
    val h = new Harness()
    val storage = h.module.stateStorage.getBackingStorage(BigInt(0))
    val bytecode = TestInbox[ByteCodeCoordinator.Command]()
    h.module.bytecodeCoordinator = Some(bytecode.ref)
    h.module.forceCompleteStorageSent = true

    h.call(_.spawnRecoveryCoordinators(root.value, storage, bytecodeAlreadyDone = true, storageAlreadyDone = true))

    val effects = h.effects()
    spawnedNames(effects) shouldBe empty
    scheduledTimers(effects) shouldBe empty
    h.spawns.hasMessages shouldBe false
    h.module.storageRangeCoordinator shouldBe None
    h.module.forceCompleteStorageSent shouldBe true
    bytecode.receiveAll() shouldBe Seq(ByteCodeCoordinator.UpdateMaxInFlightPerPeer(2))
  }

  it should "start healing only once all three download phases are complete" taggedAs UnitTest in {
    val h = new Harness(SNAPSyncConfig(deferredMerkleization = false))
    h.module.currentPhase = SyncPhase.ByteCodeAndStorageSync
    h.module.accountsComplete = true
    h.module.bytecodePhaseComplete = true

    h.call(_.checkAllDownloadsComplete())

    h.module.currentPhase shouldBe SyncPhase.ByteCodeAndStorageSync
    h.module.healingStarts shouldBe 0

    h.module.storagePhaseComplete = true
    h.call(_.checkAllDownloadsComplete())

    h.module.currentPhase shouldBe SyncPhase.StateHealing
    h.module.healingStarts shouldBe 1
    h.module.completions shouldBe 0

    // Already healing: a late completion message changes nothing.
    h.call(_.checkAllDownloadsComplete())

    h.module.healingStarts shouldBe 1
  }

  it should "complete without healing under deferred merkleization, unless a cursor was resumed" taggedAs UnitTest in {
    val fresh = new Harness(SNAPSyncConfig(deferredMerkleization = true))
    fresh.module.currentPhase = SyncPhase.ByteCodeAndStorageSync
    fresh.module.accountsComplete = true
    fresh.module.bytecodePhaseComplete = true
    fresh.module.storagePhaseComplete = true

    fresh.call(_.checkAllDownloadsComplete())

    fresh.module.completions shouldBe 1
    fresh.module.healingStarts shouldBe 0
    fresh.module.currentPhase shouldBe SyncPhase.ByteCodeAndStorageSync

    val resumed = new Harness(SNAPSyncConfig(deferredMerkleization = true))
    resumed.module.currentPhase = SyncPhase.ByteCodeAndStorageSync
    resumed.module.accountsComplete = true
    resumed.module.bytecodePhaseComplete = true
    resumed.module.storagePhaseComplete = true
    resumed.module.resumedStaleCursors = true

    resumed.call(_.checkAllDownloadsComplete())

    resumed.module.completions shouldBe 0
    resumed.module.healingStarts shouldBe 1
    resumed.module.currentPhase shouldBe SyncPhase.StateHealing
  }

  it should "offer every snap-serving peer to each running download coordinator on its tick" taggedAs UnitTest in {
    val h = new Harness()
    val accounts = TestInbox[AccountRangeCoordinator.Command]()
    val bytecode = TestInbox[ByteCodeCoordinator.Command]()
    val storage = TestInbox[StorageRangeCoordinator.Command]()
    val peers = List(peer("a")._2.peer, peer("b")._2.peer)

    // No coordinator yet: the ticks do nothing.
    h.module.servingPeers = peers
    h.call { m =>
      m.requestAccountRanges(); m.requestByteCodes(); m.requestStorageRanges()
    }

    h.module.accountRangeCoordinator = Some(accounts.ref)
    h.module.bytecodeCoordinator = Some(bytecode.ref)
    h.module.storageRangeCoordinator = Some(storage.ref)
    h.call { m =>
      m.requestAccountRanges(); m.requestByteCodes(); m.requestStorageRanges()
    }

    accounts.receiveAll() shouldBe peers.map(AccountRangeCoordinator.PeerAvailable(_))
    bytecode.receiveAll() shouldBe peers.map(ByteCodeCoordinator.ByteCodePeerAvailable(_))
    storage.receiveAll() shouldBe peers.map(StorageRangeCoordinator.StoragePeerAvailable(_))

    // No snap-serving peer: nothing is offered.
    h.module.servingPeers = Nil
    h.call { m =>
      m.requestAccountRanges(); m.requestByteCodes(); m.requestStorageRanges()
    }

    accounts.hasMessages shouldBe false
    bytecode.hasMessages shouldBe false
    storage.hasMessages shouldBe false
  }

  it should "report the pivot and the phase's state progress as the eth_syncing status" taggedAs UnitTest in {
    val h = new Harness()
    h.module.appStateStorage.putSyncStartingBlock(BigInt(7)).commit()
    h.module.pivotBlock = Some(BigInt(500))
    h.module.progressMonitor.incrementAccountsSynced(10L)
    h.module.progressMonitor.updateEstimates(accounts = 100L)
    h.module.currentPhase = SyncPhase.AccountRangeSync

    var status: Option[SyncProtocol.Status] = None
    h.call(m => status = Some(m.currentSyncStatus))

    status shouldBe Some(
      SyncProtocol.Status.Syncing(
        startingBlockNumber = BigInt(7),
        blocksProgress = SyncProtocol.Status.Progress(BigInt(500), BigInt(500)),
        stateNodesProgress = Some(SyncProtocol.Status.Progress(BigInt(10), BigInt(100)))
      )
    )

    h.module.currentPhase = SyncPhase.Idle
    h.call(m => status = Some(m.currentSyncStatus))

    status.collect { case s: SyncProtocol.Status.Syncing => s.stateNodesProgress } shouldBe Some(
      Some(SyncProtocol.Status.Progress(BigInt(0), BigInt(0)))
    )
  }

  // ── M11b (T053b): the arms lifted by T053a and the account launch, moved into the module ──────────────────────

  private def hashes(n: Int): Seq[ByteString] = (1 to n).map(i => ByteString(Array.fill(32)(i.toByte)))

  private def tasks(n: Int): Seq[StorageTask] =
    (1 to n).map(i =>
      StorageTask.createStorageTask(ByteString(Array.fill(32)(i.toByte)), ByteString(Array.fill(32)(9.toByte)))
    )

  it should "launch the account phase from the capability check on a snap peer, and go dormant without one" taggedAs UnitTest in {
    val config = SNAPSyncConfig()
    val h = new Harness(config)
    h.module.stateRoot = Some(root)
    h.module.downloadPeers = Map(peer("a"), peer("b", snap = false))
    var result: Option[Behavior[Command]] = None

    h.call(m => result = Some(m.checkSnapCapabilityArms(CheckSnapCapability)))

    result shouldBe Some(Behaviors.same[Command])
    h.module.launches.toSeq shouldBe Seq((root, config.accountConcurrency.max(1)))
    h.module.dormantReasons shouldBe empty

    h.module.downloadPeers = Map(peer("b", snap = false))
    h.call(m => result = Some(m.checkSnapCapabilityArms(CheckSnapCapability)))

    h.module.launches should have size 1
    h.module.dormantReasons.toSeq shouldBe Seq("no snap-capable peers after the capability grace period")
  }

  it should "not launch a second account coordinator when the capability check fires with one already running (#1516)" taggedAs UnitTest in {
    val h = new Harness()
    h.module.stateRoot = Some(root)
    h.module.downloadPeers = Map(peer("a"))
    val running = TestInbox[AccountRangeCoordinator.Command]()
    h.module.accountRangeCoordinator = Some(running.ref)
    var result: Option[Behavior[Command]] = None

    h.call(m => result = Some(m.checkSnapCapabilityArms(CheckSnapCapability)))

    result shouldBe Some(Behaviors.same[Command])
    h.module.launches shouldBe empty
    h.module.dormantReasons shouldBe empty
    running.hasMessages shouldBe false
  }

  it should "offer the snap-serving peers on each download tick arm" taggedAs UnitTest in {
    val h = new Harness()
    val bytecode = TestInbox[ByteCodeCoordinator.Command]()
    val p = peer("a")._2.peer
    h.module.servingPeers = List(p)
    h.module.bytecodeCoordinator = Some(bytecode.ref)

    h.call(m => m.downloadTickArms(RequestByteCodes))
    h.call(m => m.downloadTickArms(RequestAccountRanges))

    bytecode.receiveAll() shouldBe Seq(ByteCodeCoordinator.ByteCodePeerAvailable(p))
    h.module.downloadTickArms.isDefinedAt(RequestStorageRanges) shouldBe true
  }

  it should "hand the pivot probe's answer to the refresher and route every other SNAP response to its coordinator" taggedAs UnitTest in {
    val h = new Harness()
    val accounts = TestInbox[AccountRangeCoordinator.Command]()
    val healing = TestInbox[TrieNodeHealingCoordinator.Command]()
    h.module.accountRangeCoordinator = Some(accounts.ref)
    h.module.trieNodeHealingCoordinator = Some(healing.ref)
    h.module.pivotProbeRequestId = Some(BigInt(7))
    val probe = SNAP.AccountRange(BigInt(7), Seq.empty, Seq.empty)
    val other = SNAP.AccountRange(BigInt(8), Seq.empty, Seq.empty)
    val nodes = SNAP.TrieNodes(BigInt(9), Seq.empty)

    h.call { m =>
      m.snapResponseArms(AccountRangeResponse(probe)); m.snapResponseArms(AccountRangeResponse(other))
    }

    h.module.probeAnswers.toSeq shouldBe Seq(probe)
    accounts.receiveAll() shouldBe Seq(AccountRangeCoordinator.AccountRangeResponseMsg(other))

    // During validation a late TrieNodes answer is dropped; in healing it is forwarded.
    h.module.currentPhase = SyncPhase.StateValidation
    h.call(m => m.snapResponseArms(TrieNodesResponse(nodes)))
    healing.hasMessages shouldBe false

    h.module.currentPhase = SyncPhase.StateHealing
    h.call(m => m.snapResponseArms(TrieNodesResponse(nodes)))
    healing.receiveAll() shouldBe Seq(TrieNodeHealingCoordinator.TrieNodesResponseMsg(nodes))
  }

  it should "count synced accounts, reset the refresh counter, and reset the failure counters after 100,000 accounts" taggedAs UnitTest in {
    val h = new Harness()
    h.module.consecutivePivotRefreshes = 3
    h.module.criticalFailureCount = 2
    h.module.dormantRetryCount = 4

    h.call(m => m.progressAccountsSyncedArms(ProgressAccountsSynced(10L)))

    h.module.progressMonitor.currentProgress.accountsSynced shouldBe 10L
    h.module.consecutivePivotRefreshes shouldBe 0
    h.module.criticalFailureCount shouldBe 2
    h.module.accountsAtLastCriticalFailure shouldBe 0L
    h.module.dormantRetryCount shouldBe 4

    h.module.consecutivePivotRefreshes = 1
    h.call(m => m.progressAccountsSyncedArms(ProgressAccountsSynced(100000L)))

    h.module.consecutivePivotRefreshes shouldBe 0
    h.module.criticalFailureCount shouldBe 0
    h.module.accountsAtLastCriticalFailure shouldBe 100010L
    h.module.dormantRetryCount shouldBe 0
  }

  it should "track the account trie's finalization, persist its root, and restart when it fails" taggedAs UnitTest in {
    val h = new Harness()
    val finalized = ByteString(Array.fill[Byte](32)(3))

    h.call(m => m.accountTrieFinalizationArms(ProgressAccountsFinalizingTrie))

    h.module.progressMonitor.currentProgress.isFinalizingTrie shouldBe true
    h.module.lastAccountProgressMs should be > 0L

    h.call(m => m.accountTrieFinalizationArms(AccountTrieFinalized(finalized)))
    h.module.appStateStorage.getSnapSyncFinalizedRoot() shouldBe Some(finalized)

    h.call(m => m.accountTrieFinalizationArms(ProgressAccountsTrieFinalized))
    h.module.progressMonitor.currentProgress.isFinalizingTrie shouldBe false

    h.call(m => m.accountTrieFinalizationArms(ProgressAccountsFinalizingTrie))
    h.call(m => m.accountTrieFinalizationArms(AccountTrieFinalizationFailed("root mismatch")))

    h.module.restartReasons.toSeq shouldBe Seq("account trie finalization failed: root mismatch")
    h.module.progressMonitor.currentProgress.isFinalizingTrie shouldBe false
  }

  it should "record bytecode and storage progress, the contract share and the extrapolated slot estimate" taggedAs UnitTest in {
    val h = new Harness()
    h.module.storageStagnationRefreshAttempted = true

    h.call(m => m.downloadProgressArms(ProgressBytecodesDownloaded(3L)))
    h.call(m => m.downloadProgressArms(ProgressStorageSlotsSynced(5L)))

    h.module.lastBytecodeProgressMs should be > 0L
    h.module.lastStorageProgressMs shouldBe 0L
    h.module.storageStagnationRefreshAttempted shouldBe true

    h.call(m => m.downloadProgressArms(ProgressStorageSlotsSynced(195L)))

    h.module.lastStorageProgressMs should be > 0L
    h.module.storageStagnationRefreshAttempted shouldBe false

    h.call(m => m.downloadProgressArms(ProgressStorageContracts(2, 8)))

    h.module.storageContractProgressPct shouldBe 0.25
    val progress = h.module.progressMonitor.currentProgress
    progress.bytecodesDownloaded shouldBe 3L
    progress.storageSlotsSynced shouldBe 200L
    progress.storageContractsCompleted shouldBe 2
    progress.storageContractsTotal shouldBe 8
    progress.estimatedTotalSlots shouldBe 800L
  }

  it should "forward both backpressure signals to the account coordinator, in order" taggedAs UnitTest in {
    val h = new Harness()
    val accounts = TestInbox[AccountRangeCoordinator.Command]()
    h.module.accountRangeCoordinator = Some(accounts.ref)

    h.call { m =>
      m.downloadBackpressureArms(StorageBackpressureChanged(true))
      m.downloadBackpressureArms(ByteCodeBackpressureChanged(false))
    }

    accounts.receiveAll() shouldBe Seq(
      AccountRangeCoordinator.StorageQueuePressure(true),
      AccountRangeCoordinator.ByteCodeQueuePressure(false)
    )
  }

  it should "forward contract work and accumulate the bytecode estimate, releasing the credit of work no coordinator takes" taggedAs UnitTest in {
    val h = new Harness()
    h.module.intakeBudget.reserve(2, 3)

    h.call(m => m.incrementalContractDataArms(IncrementalContractData(hashes(3), tasks(2))))

    h.module.intakeBudget.pendingStorageTasks shouldBe 0L
    h.module.intakeBudget.pendingByteCodeHashes shouldBe 0L
    h.module.bytecodesEstimatedTotal shouldBe 3L

    val bytecode = TestInbox[ByteCodeCoordinator.Command]()
    val storage = TestInbox[StorageRangeCoordinator.Command]()
    h.module.bytecodeCoordinator = Some(bytecode.ref)
    h.module.storageRangeCoordinator = Some(storage.ref)
    h.module.intakeBudget.reserve(1, 2)

    h.call(m => m.incrementalContractDataArms(IncrementalContractData(hashes(2), tasks(1), replayed = true)))

    bytecode.receiveAll() shouldBe Seq(ByteCodeCoordinator.AddByteCodeTasks(hashes(2), skipPresent = true))
    storage.receiveAll() shouldBe Seq(StorageRangeCoordinator.AddStorageTasks(tasks(1)))
    h.module.intakeBudget.pendingStorageTasks shouldBe 1L
    h.module.intakeBudget.pendingByteCodeHashes shouldBe 2L
    h.module.bytecodesEstimatedTotal shouldBe 5L
    h.module.progressMonitor.currentProgress.estimatedTotalBytecodes shouldBe 5L
  }

  it should "enter the storage phase once on AccountRangeSyncComplete: persist, clear the preserved cursors, signal NoMore and rebalance" taggedAs UnitTest in {
    val h = new Harness()
    val bytecode = TestInbox[ByteCodeCoordinator.Command]()
    val storage = TestInbox[StorageRangeCoordinator.Command]()
    h.module.stateRoot = Some(root)
    h.module.bytecodeCoordinator = Some(bytecode.ref)
    h.module.storageRangeCoordinator = Some(storage.ref)
    h.module.preservedRangeProgress = Map(ByteString(1) -> ByteString(2))
    h.module.preservedAtPivotBlock = Some(BigInt(10))
    h.module.preservedTaskFiles = Some(ContractTaskFiles("storage", 1L, "codes", 1L))
    h.module.consecutivePivotRefreshes = 2

    h.call(m => m.accountRangeSyncCompleteArms(AccountRangeSyncComplete))

    h.module.accountsComplete shouldBe true
    h.module.appStateStorage.isSnapSyncAccountsComplete() shouldBe true
    h.module.preservedRangeProgress shouldBe empty
    h.module.preservedAtPivotBlock shouldBe None
    h.module.preservedTaskFiles shouldBe None
    h.module.consecutivePivotRefreshes shouldBe 0
    h.module.currentPhase shouldBe SyncPhase.ByteCodeAndStorageSync
    bytecode.receiveAll() shouldBe Seq(
      ByteCodeCoordinator.NoMoreByteCodeTasks,
      ByteCodeCoordinator.UpdateMaxInFlightPerPeer(8),
      ByteCodeCoordinator.ByteCodePivotRefreshed
    )
    storage.receiveAll() shouldBe Seq(
      StorageRangeCoordinator.NoMoreStorageTasks,
      StorageRangeCoordinator.UpdateMaxInFlightPerPeer(3)
    )
    h.module.stagnationResets.toSeq shouldBe Seq(StagnationWatchdog.ResetReason.StoragePhaseEntry)
    h.module.stagnationSchedules shouldBe 1
    h.module.healingStarts shouldBe 0

    // A duplicate changes nothing.
    h.call(m => m.accountRangeSyncCompleteArms(AccountRangeSyncComplete))

    bytecode.hasMessages shouldBe false
    storage.hasMessages shouldBe false
    h.module.stagnationResets should have size 1
    h.module.stagnationSchedules shouldBe 1
  }

  it should "persist the storage and codeHashes handoff paths and counts from the coordinator's task-file infos (#1603)" taggedAs UnitTest in {
    val h = new Harness()
    val storageFile = java.nio.file.Paths.get("fukuii-contract-storage-1603.bin")
    val codeHashesFile = java.nio.file.Paths.get("fukuii-unique-codehashes-1603.bin")
    h.module.appStateStorage.putSnapAccountResumeCheckpoint("1603").commit()

    h.call(m =>
      m.accountRangeSyncCompleteArms(
        StorageFileInfoReply(Success(AccountRangeCoordinator.StorageFileInfoResponse(storageFile, 42L)))
      )
    )
    h.call(m =>
      m.accountRangeSyncCompleteArms(
        CodeHashesFileInfoReply(Success(AccountRangeCoordinator.CodeHashesFileInfoResponse(codeHashesFile, 7L)))
      )
    )

    h.module.appStateStorage.getSnapSyncStorageFilePath() shouldBe Some(storageFile.toString)
    h.module.appStateStorage.getSnapSyncStorageFileCount() shouldBe Some(42L)
    h.module.appStateStorage.getSnapSyncCodeHashesPath() shouldBe Some(codeHashesFile.toString)
    h.module.appStateStorage.getSnapSyncCodeHashesCount() shouldBe Some(7L)
    // The storage batch still retires the account resume checkpoint.
    h.module.appStateStorage.getSnapAccountResumeCheckpoint() shouldBe None
    val infos = h.kit.logEntries().filter(_.level == org.slf4j.event.Level.INFO).map(_.message)
    infos should contain(s"Persisted storage file path for recovery: $storageFile (42 entries)")
    infos should contain(s"Persisted codeHashes file path for recovery: $codeHashesFile (7 entries)")
  }

  it should "log a WARN naming the path that was not persisted when the coordinator could not flush a task file (#1603)" taggedAs UnitTest in {
    val h = new Harness()

    h.call(m =>
      m.accountRangeSyncCompleteArms(
        StorageFileInfoReply(Failure(new java.io.IOException("No space left on device")))
      )
    )
    h.call(m =>
      m.accountRangeSyncCompleteArms(CodeHashesFileInfoReply(Failure(new java.io.IOException("Stream Closed"))))
    )

    h.module.appStateStorage.getSnapSyncStorageFilePath() shouldBe None
    h.module.appStateStorage.getSnapSyncStorageFileCount() shouldBe None
    h.module.appStateStorage.getSnapSyncCodeHashesPath() shouldBe None
    h.module.appStateStorage.getSnapSyncCodeHashesCount() shouldBe None
    val warns = h.kit.logEntries().filter(_.level == org.slf4j.event.Level.WARN).map(_.message)
    val storageWarns = warns.filter(_.startsWith("Storage file path NOT persisted for recovery"))
    val codeHashesWarns = warns.filter(_.startsWith("CodeHashes file path NOT persisted for recovery"))
    storageWarns should have size 1
    storageWarns.head should include("IOException: No space left on device")
    codeHashesWarns should have size 1
    codeHashesWarns.head should include("IOException: Stream Closed")
  }

  it should "complete the bytecode and storage phases, boost storage while it runs, and skip a force-complete during account sync" taggedAs UnitTest in {
    val config = SNAPSyncConfig()
    val h = new Harness(config)
    val storage = TestInbox[StorageRangeCoordinator.Command]()
    h.module.storageRangeCoordinator = Some(storage.ref)
    h.module.currentPhase = SyncPhase.AccountRangeSync

    h.call(m => m.downloadPhaseCompleteArms(ByteCodeSyncComplete))

    h.module.bytecodePhaseComplete shouldBe true
    h.module.appStateStorage.isSnapSyncBytecodeComplete() shouldBe true
    storage.receiveAll() shouldBe Seq(StorageRangeCoordinator.UpdateMaxInFlightPerPeer(config.maxInFlightPerPeer))
    h.module.downloadPhaseCompleteArms.isDefinedAt(ByteCodeSyncComplete) shouldBe false

    h.call(m => m.downloadPhaseCompleteArms(StorageRangeSyncForceCompleted))

    h.module.storagePhaseComplete shouldBe false
    h.module.storagePhaseForceCompleted shouldBe false

    h.call(m => m.downloadPhaseCompleteArms(StorageRangeSyncComplete))

    h.module.storagePhaseComplete shouldBe true
    h.module.storagePhaseForceCompleted shouldBe false
    h.module.appStateStorage.isSnapSyncStorageComplete() shouldBe true
    h.module.markerClears.toSeq shouldBe Seq("storage phase complete")
    h.module.downloadPhaseCompleteArms.isDefinedAt(StorageRangeSyncComplete) shouldBe false
    h.module.healingStarts shouldBe 0
  }

  it should "launch the account phase on the selected resume: spawns at the launch budgets, timers, carry source and generation" taggedAs UnitTest in {
    val config = SNAPSyncConfig()
    val h = new Harness(config, stubLaunch = false)
    val resume = Map(ByteString(1) -> ByteString(2))
    val carried = ContractTaskFiles("storage", 1L, "codes", 1L)
    h.module.pivotBlock = Some(BigInt(100))
    h.module.coordinatorGeneration = 3L
    h.module.resumeSelection = (resume, Some(carried))

    h.call(_.launchAccountRangeWorkers(root, 0))

    h.module.resumeSelections.toSeq shouldBe Seq((root, config.accountConcurrency, BigInt(100)))
    h.module.stagnationResets.toSeq shouldBe Seq(StagnationWatchdog.ResetReason.AccountLaunch)
    h.module.launchedAccountGeneration shouldBe 3L
    h.module.currentCarrySource shouldBe Some(carried)
    h.module.heapWatchdogStarts shouldBe 1
    h.module.stagnationSchedules shouldBe 1
    h.module.mptStorageRequests.toSeq shouldBe Seq(BigInt(100), BigInt(100))
    h.module.progressMonitor.currentProgress.phase shouldBe SyncPhase.AccountRangeSync
    val effects = h.effects()
    spawnedNames(effects) shouldBe Seq(
      "account-range-coordinator-3",
      "bytecode-coordinator-3",
      "storage-range-coordinator-3"
    )
    scheduledTimers(effects) shouldBe Seq(
      (RequestAccountRanges, RequestAccountRanges, 1.second),
      (TuneRateTracker, TuneRateTracker, 5.seconds),
      (RequestByteCodes, RequestByteCodes, 1.second),
      (RequestStorageRanges, RequestStorageRanges, 1.second)
    )
    h.spawns.receiveAll() match
      case Seq(a: ChildSpawn.AccountRange, b: ChildSpawn.ByteCode, s: ChildSpawn.StorageRange) =>
        a.stateRoot shouldBe root.value
        a.concurrency shouldBe config.accountConcurrency
        a.resumeProgress shouldBe resume
        a.carriedTaskFiles shouldBe Some(carried)
        a.progressGeneration shouldBe 3L
        a.initialMaxInFlightPerPeer shouldBe 5
        a.storageDone shouldBe None
        (a.mptStorage should be).theSameInstanceAs(h.module.launchStorage)
        a.intakeBudget.exists(_ eq h.module.intakeBudget) shouldBe true
        b.intakeBudget.exists(_ eq h.module.intakeBudget) shouldBe true
        s.initialMaxInFlightPerPeer shouldBe 2
        s.snapProgressStorage.exists(_ eq h.module.snapProgressStorage) shouldBe true
      case other => fail(s"expected account, bytecode and storage spawns, got $other")
    h.kit.childInbox[AccountRangeCoordinator.Command]("account-range-coordinator-3").receiveAll() shouldBe Seq(
      AccountRangeCoordinator.StartAccountRangeSync(root.value)
    )
    h.kit.childInbox[ByteCodeCoordinator.Command]("bytecode-coordinator-3").receiveAll() shouldBe Seq(
      ByteCodeCoordinator.StartByteCodeSync(Seq.empty),
      ByteCodeCoordinator.UpdateMaxInFlightPerPeer(2)
    )
    h.kit.childInbox[StorageRangeCoordinator.Command]("storage-range-coordinator-3").receiveAll() shouldBe Seq(
      StorageRangeCoordinator.StartStorageRangeSync(root.value),
      StorageRangeCoordinator.UpdateMaxInFlightPerPeer(SNAPSyncController.storageInFlightDuringAccounts(config))
    )
  }
