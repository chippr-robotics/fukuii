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
import com.chipprbots.ethereum.blockchain.sync.snap.ChildSpawn
import com.chipprbots.ethereum.blockchain.sync.snap.RecordingChildFactories
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPRequestTracker
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncConfig
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.*
import com.chipprbots.ethereum.blockchain.sync.snap.SnapIntakeBudget
import com.chipprbots.ethereum.blockchain.sync.snap.StateValidator
import com.chipprbots.ethereum.blockchain.sync.snap.SyncProgressMonitor
import com.chipprbots.ethereum.blockchain.sync.snap.actors.AccountRangeCoordinator
import com.chipprbots.ethereum.blockchain.sync.snap.actors.ByteCodeCoordinator
import com.chipprbots.ethereum.blockchain.sync.snap.actors.StorageRangeCoordinator
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.FlatSlotStorage
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.db.storage.PathNodeStorage
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
    with AccountRangeLaunchApi:

  private val dataSource = EphemDataSource()

  // DownloadSupervisorState
  var coordinatorGeneration: Long = 0L
  val snapProgressStorage: SnapSyncProgressStorage = new SnapSyncProgressStorage(dataSource)
  var recordStorageDone: Boolean = false
  def SnapCapabilityCheckKey: String = "snap-capability-check"

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
  def launchAccountRangeWorkers(rootHash: TrieRoot, concurrency: Int): Unit = launches += ((rootHash, concurrency))

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
  final private class Harness(config: SNAPSyncConfig = SNAPSyncConfig()):
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
        ) with DownloadSupervisor
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
