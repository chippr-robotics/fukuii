package com.chipprbots.ethereum.blockchain.sync.snap.controller

import java.nio.file.Files
import java.nio.file.Path

import org.apache.pekko.actor.Scheduler
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
import scala.util.Success
import scala.util.boundary

import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.blockchain.sync.SyncProtocol
import com.chipprbots.ethereum.blockchain.sync.snap.AccountResumeCheckpoint
import com.chipprbots.ethereum.blockchain.sync.snap.AccountTask
import com.chipprbots.ethereum.blockchain.sync.snap.ContractTaskFiles
import com.chipprbots.ethereum.blockchain.sync.snap.GatedTaskFileReplay
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPRequestTracker
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncConfig
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.*
import com.chipprbots.ethereum.blockchain.sync.snap.SnapIntakeBudget
import com.chipprbots.ethereum.blockchain.sync.snap.StateValidator
import com.chipprbots.ethereum.blockchain.sync.snap.StorageScheme
import com.chipprbots.ethereum.blockchain.sync.snap.StorageTask
import com.chipprbots.ethereum.blockchain.sync.snap.SyncProgressMonitor
import com.chipprbots.ethereum.blockchain.sync.snap.actors.ByteCodeCoordinator
import com.chipprbots.ethereum.blockchain.sync.snap.actors.StorageRangeCoordinator
import com.chipprbots.ethereum.db.dataSource.DataSourceUpdateOptimized
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.ExcludedAccount
import com.chipprbots.ethereum.db.storage.ExcludedReason
import com.chipprbots.ethereum.db.storage.FlatSlotStorage
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.db.storage.Namespaces
import com.chipprbots.ethereum.db.storage.PathNodeStorage
import com.chipprbots.ethereum.db.storage.SnapStorageDoneStorage
import com.chipprbots.ethereum.db.storage.SnapSyncProgressStorage
import com.chipprbots.ethereum.db.storage.StateStorage
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.BlockchainWriter
import com.chipprbots.ethereum.domain.TrieRoot
import com.chipprbots.ethereum.mpt.HexPrefix
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerEventBusActor
import com.chipprbots.ethereum.testing.Tags.UnitTest
import com.chipprbots.ethereum.utils.Config.SyncConfig
import com.chipprbots.ethereum.utils.Hex

/** Everything `SnapResumePlanner` needs, as a stub (spec 016 FR-016 (b)): its `SnapResumePlannerState`, the hubs, the
  * environment, the coordinator handles, the phase flags and the callee Api traits. One ephemeral data source backs the
  * app-state, flat-slot, done-marker, progress and trie stores, as one RocksDB does in production; the compaction
  * dispatcher runs inline; the parent SyncController is a test inbox; the Api calls are recorded; the members these
  * tests never reach throw.
  */
private[snap] class StubSnapResumePlannerState(
    val ctx: ActorContext[SNAPSyncController.Command],
    val timers: TimerScheduler[SNAPSyncController.Command],
    val snapSyncConfig: SNAPSyncConfig,
    val scheduler: Scheduler
) extends SnapResumePlannerState
    with SnapSharedState
    with SnapControllerEnv
    with CoordinatorHandles
    with PhaseFlags
    with PivotSelectionApi
    with BootstrappingApi
    with SyncingApi
    with HeapWatchdogApi
    with DownloadRecoveryApi
    with ByteCodeRequestApi
    with StagnationResetApi
    with StagnationScheduleApi
    with ChainDownloadStartApi
    with SnapServerPeersApi:

  private val dataSource = EphemDataSource()

  // SnapResumePlannerState (scheduler is a constructor val)
  var mptStorage: Option[MptStorage] = None
  val storageDoneStorage: SnapStorageDoneStorage = new SnapStorageDoneStorage(dataSource)
  var minPivotHint: BigInt = BigInt(0)
  var isPoSChain: Boolean = false
  var clPivotHint: Option[CLPivotHint] = None
  var failedFinalizationPivot: Option[BigInt] = None
  var coordinatorGeneration: Long = 0L
  var recordStorageDone: Boolean = false
  def SnapServeWindowMargin: BigInt = BigInt(50)
  def BootstrapCheckKey: String = "bootstrap-check"
  var preservedRangeProgress: Map[ByteString, ByteString] = Map.empty
  var preservedAtPivotBlock: Option[BigInt] = None
  var preservedTaskFiles: Option[ContractTaskFiles] = None
  val snapProgressStorage: SnapSyncProgressStorage = new SnapSyncProgressStorage(dataSource)
  var launchedAccountGeneration: Long = -1L
  var currentCarrySource: Option[ContractTaskFiles] = None

  // SnapSharedState
  var pivotBlock: Option[BigInt] = None
  var stateRoot: Option[TrieRoot] = None
  var currentPhase: SyncPhase = SyncPhase.Idle
  val progressMonitor: SyncProgressMonitor = new SyncProgressMonitor(scheduler)
  def requestTracker: SNAPRequestTracker = notUsed("requestTracker")

  // SnapControllerEnv (ctx, timers and snapSyncConfig are constructor vals)
  def syncConfig: SyncConfig = notUsed("syncConfig")
  val asyncLog: org.slf4j.Logger = org.slf4j.LoggerFactory.getLogger(classOf[SnapResumePlannerSpec])
  val appStateStorage: AppStateStorage = new AppStateStorage(dataSource)
  val stateStorage: StateStorage = StateStorage.createTestStateStorage(dataSource)._1
  val evmCodeStorage: EvmCodeStorage = new EvmCodeStorage(dataSource)
  val flatSlotStorage: FlatSlotStorage = new FlatSlotStorage(dataSource)
  def validatorFactory: MptStorage => StateValidator = notUsed("validatorFactory")
  def snapValidationEc: ExecutionContext = ExecutionContext.parasitic
  def networkPeerManager: ActorRef[NetworkPeerManagerActor.Command] = notUsed("networkPeerManager")
  def blockchainReader: BlockchainReader = notUsed("blockchainReader")
  def blockchainWriter: BlockchainWriter = notUsed("blockchainWriter")
  def peerEventBus: ActorRef[PeerEventBusActor.Command] = notUsed("peerEventBus")
  val parent: TestInbox[SyncProtocol.SyncControllerReply] = TestInbox[SyncProtocol.SyncControllerReply]()
  def syncController: ActorRef[SyncProtocol.SyncControllerReply] = parent.ref
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

  // CoordinatorHandles (no coordinator is spawned: the recovery spawns are the stub's DownloadRecoveryApi)
  val intakeBudget: SnapIntakeBudget = new SnapIntakeBudget(1000L, 1000L)
  protected def stopChild(child: ActorRef[Nothing]): Unit = notUsed("stopChild")

  // Callee Api traits
  var networkBest: Option[BigInt] = None
  def currentNetworkBestFromSnapPeers(): Option[BigInt] = networkBest
  var bootstrappingEntries: Int = 0
  def bootstrapping(): Behavior[Command] =
    bootstrappingEntries += 1
    Behaviors.same
  var syncingEntries: Int = 0
  def syncing(): Behavior[Command] =
    syncingEntries += 1
    Behaviors.same
  var heapWatchdogStarts: Int = 0
  def ensureHeapWatchdog(): Unit = heapWatchdogStarts += 1
  val recoverySpawns: mutable.ArrayBuffer[(ByteString, MptStorage, Boolean, Boolean)] = mutable.ArrayBuffer.empty
  def spawnRecoveryCoordinators(
      rootBs: ByteString,
      storage: MptStorage,
      bytecodeAlreadyDone: Boolean,
      storageAlreadyDone: Boolean
  ): Unit = recoverySpawns += ((rootBs, storage, bytecodeAlreadyDone, storageAlreadyDone))
  var downloadChecks: Int = 0
  def checkAllDownloadsComplete(): Unit = downloadChecks += 1
  var byteCodeRequests: Int = 0
  def requestByteCodes(): Unit = byteCodeRequests += 1
  val stagnationResets: mutable.ArrayBuffer[StagnationWatchdog.ResetReason] = mutable.ArrayBuffer.empty
  def resetStagnationClocks(reason: StagnationWatchdog.ResetReason): Unit = stagnationResets += reason
  var stagnationSchedules: Int = 0
  def scheduleStagnationChecks(): Unit = stagnationSchedules += 1
  var chainDownloaderStarts: Int = 0
  def startChainDownloader(): Unit = chainDownloaderStarts += 1
  def snapServingPeers(): List[Peer] = notUsed("snapServingPeers")
  var snapServerSchedulerStarts: Int = 0
  def startSnapServerPeersScheduler(): Unit = snapServerSchedulerStarts += 1

  private def notUsed(what: String): Nothing =
    throw new UnsupportedOperationException(s"$what is not reached by SnapResumePlannerSpec")

/** Spec 016 M5 stub test (FR-016 (b), T046): `SnapResumePlanner` mixed into a stub of its state interface and the
  * environment. Calls that log through `ctx` run while a `BehaviorTestKit` processes a message, as they would in the
  * controller. `getOrCreateMptStorage` reads and writes `mptStorage`; `clearStorageDoneMarkers` reads
  * `storageDoneStorage`; the gated recovery replay reads `scheduler` (ManualTime) to re-check a closed gate after
  * `RecoveryReplayPausedRetry`.
  */
class SnapResumePlannerSpec extends ScalaTestWithActorTestKit(ManualTime.config) with AnyFlatSpecLike with Matchers:

  private val manualTime: ManualTime = ManualTime()(using system)

  /** The module on its stub and the kit that gives it an actor context. */
  final private class Harness(config: SNAPSyncConfig = SNAPSyncConfig()):
    private var action: () => Unit = () => ()
    private var captured: StubSnapResumePlannerState & SnapResumePlanner = null
    val kit: BehaviorTestKit[Command] = BehaviorTestKit(Behaviors.setup[Command] { ctx =>
      Behaviors.withTimers { timers =>
        captured = new StubSnapResumePlannerState(ctx, timers, config, system.classicSystem.scheduler)
          with SnapResumePlanner
        Behaviors.receiveMessage { _ =>
          action()
          Behaviors.same
        }
      }
    })
    val module: StubSnapResumePlannerState & SnapResumePlanner = captured

    /** Run `f` on the module while the kit processes a message. */
    def call(f: StubSnapResumePlannerState & SnapResumePlanner => Unit): Unit =
      action = () => f(module)
      kit.run(PollHandshakedPeers)

    /** Run `startSnapSync`'s recovery step under a boundary of the core's type, as `startSnapSync` does: the Label
      * reaches the module's members as their `using` parameter. Yields true when the step fell through.
      */
    def recoveryStep(): Boolean =
      var fellThrough = false
      call { m =>
        boundary[Behavior[Command]] {
          m.recoverAccountsComplete()
          fellThrough = true
          Behaviors.same
        }
      }
      fellThrough

    def scheduledTimers(): Seq[(Any, Command, FiniteDuration)] =
      kit.retrieveAllEffects().collect { case t: Effect.TimerScheduled[?] =>
        (t.key, t.msg.asInstanceOf[Command], t.delay)
      }

    /** Persist an accounts-complete recovery at `pivot`, with both download phases complete or not. */
    def saveRecovery(pivot: BigInt, phasesComplete: Boolean): Unit =
      val app = module.appStateStorage
      app
        .putSnapSyncAccountsComplete(true)
        .and(app.putSnapSyncStorageComplete(phasesComplete))
        .and(app.putSnapSyncBytecodeComplete(phasesComplete))
        .and(app.putSnapSyncPivotBlock(pivot))
        .and(app.putSnapSyncStateRoot(savedRoot))
        .commit()

  private def tempDir(prefix: String): Path = Files.createTempDirectory(prefix)

  private val savedRoot: ByteString = ByteString(Array.fill[Byte](32)(7))

  "SnapResumePlanner" should "create the pivot's writable trie store once and keep it in its state" taggedAs UnitTest in {
    val h = new Harness()
    h.module.mptStorage shouldBe None

    var first: MptStorage = null
    h.call(m => first = m.getOrCreateMptStorage(BigInt(1_000)))

    first should not be null
    h.module.mptStorage shouldBe Some(first)

    // A later pivot reuses the store already held (content-addressed nodes need no re-tagging).
    var second: MptStorage = null
    h.call(m => second = m.getOrCreateMptStorage(BigInt(2_000)))

    (second should be).theSameInstanceAs(first)
    (h.module.mptStorage.get should be).theSameInstanceAs(first)
  }

  it should "return the trie store already in its state" taggedAs UnitTest in {
    val h = new Harness()
    val preset = h.module.stateStorage.getBackingStorage(BigInt(7))
    h.module.mptStorage = Some(preset)

    var got: MptStorage = null
    h.call(m => got = m.getOrCreateMptStorage(BigInt(1_000)))

    (got should be).theSameInstanceAs(preset)
    (h.module.mptStorage.get should be).theSameInstanceAs(preset)
  }

  it should "drop every storage-task completion marker" taggedAs UnitTest in {
    val h = new Harness()
    val account = ByteString(Array.fill[Byte](32)(1))
    val root = ByteString(Array.fill[Byte](32)(2))
    h.module.storageDoneStorage.markDone(Seq(account -> root)).commit()
    h.module.storageDoneStorage.isDone(account, root) shouldBe true

    h.call(_.clearStorageDoneMarkers("stub test"))

    h.module.storageDoneStorage.isDone(account, root) shouldBe false
  }

  it should "refuse a Hash-scheme start on a datadir holding a Path-scheme account trie, and only that" taggedAs UnitTest in {
    val pathRoot = HexPrefix.encode(Array.empty[Byte], isLeaf = false)
    def withPathRoot(config: SNAPSyncConfig): Harness =
      val h = new Harness(config)
      h.module.flatSlotStorage.dataSource.update(
        Seq(DataSourceUpdateOptimized(Namespaces.StateTriePathNamespace, Nil, Seq(pathRoot -> Array[Byte](1))))
      )
      h

    val hashOnPath = withPathRoot(SNAPSyncConfig(storageScheme = StorageScheme.Hash))
    an[IllegalStateException] should be thrownBy hashOnPath.module.checkStorageSchemeMismatch()

    val pathOnPath = withPathRoot(SNAPSyncConfig(storageScheme = StorageScheme.Path))
    noException should be thrownBy pathOnPath.module.checkStorageSchemeMismatch()

    val hashOnEmpty = new Harness(SNAPSyncConfig(storageScheme = StorageScheme.Hash))
    noException should be thrownBy hashOnEmpty.module.checkStorageSchemeMismatch()
  }

  it should "sweep superseded task files but keep the accounts-complete handoff files" taggedAs UnitTest in {
    val dir = tempDir("snap-resume-planner-sweep")
    def touch(name: String): Path = Files.write(dir.resolve(name), Array[Byte](0))
    val handoffStorage = touch("fukuii-contract-storage-handoff")
    val handoffCodeHashes = touch("fukuii-unique-codehashes-handoff")
    val superseded = touch("fukuii-contract-storage-superseded")
    val unrelated = touch("unrelated.txt")
    val h = new Harness(SNAPSyncConfig(taskFileDir = Some(dir)))
    h.module.appStateStorage
      .putSnapSyncStorageFilePath(handoffStorage.toString)
      .and(h.module.appStateStorage.putSnapSyncCodeHashesPath(handoffCodeHashes.toString))
      .commit()

    h.module.accountsCompleteTaskFilePaths shouldBe Set(handoffStorage.toString, handoffCodeHashes.toString)

    h.call(m => m.sweepSupersededTaskFiles(m.accountsCompleteTaskFilePaths, "stub test"))

    Files.exists(handoffStorage) shouldBe true
    Files.exists(handoffCodeHashes) shouldBe true
    Files.exists(superseded) shouldBe false
    Files.exists(unrelated) shouldBe true
    Seq(handoffStorage, handoffCodeHashes, unrelated, dir).foreach(Files.deleteIfExists)
  }

  it should "hold a gated recovery replay while the gate is closed and finish it one retry after it opens" taggedAs UnitTest in {
    val h = new Harness()
    val dir = tempDir("snap-resume-planner-replay")
    val file = dir.resolve("fukuii-unique-codehashes-replay")
    val entries = (1 to 3).map(i => Array.fill[Byte](32)(i.toByte))
    Files.write(file, Array.concat(entries*))
    var gateClosed = true
    val replay = new GatedTaskFileReplay(
      path = file,
      entrySize = 32,
      endEntry = 3L,
      chunkEntries = 2,
      gate = () => Option.when(gateClosed)("stub gate closed")
    )
    val emitted = mutable.ArrayBuffer.empty[Seq[Byte]]

    val done =
      h.module.runGatedReplay(replay, "stub file") { chunk =>
        emitted ++= chunk.map(_.toSeq)
        ()
      }(using
        ExecutionContext.parasitic
      )

    done.isCompleted shouldBe false
    replay.position shouldBe 0L
    emitted shouldBe empty

    gateClosed = false
    manualTime.timePasses(RecoveryReplayPausedRetry)

    done.value shouldBe Some(Success(()))
    replay.position shouldBe 3L
    emitted.toSeq shouldBe entries.map(_.toSeq)
    Seq(file, dir).foreach(Files.deleteIfExists)
  }

  it should "fall through when no account phase completed, and clear the flags of a recovery with no saved pivot" taggedAs UnitTest in {
    val h = new Harness()
    h.recoveryStep() shouldBe true
    h.module.syncingEntries shouldBe 0

    h.module.appStateStorage
      .putSnapSyncAccountsComplete(true)
      .and(h.module.appStateStorage.putSnapSyncStorageComplete(true))
      .and(h.module.appStateStorage.putSnapSyncBytecodeComplete(true))
      .commit()

    h.recoveryStep() shouldBe true
    h.module.appStateStorage.isSnapSyncAccountsComplete() shouldBe false
    h.module.appStateStorage.isSnapSyncStorageComplete() shouldBe false
    h.module.appStateStorage.isSnapSyncBytecodeComplete() shouldBe false
    h.module.pivotBlock shouldBe None
    h.module.recoverySpawns shouldBe empty
  }

  it should "resume bytecodes and storage on a fresh saved pivot and break out with syncing" taggedAs UnitTest in {
    val h = new Harness()
    h.saveRecovery(BigInt(1_000), phasesComplete = true)
    h.module.coordinatorGeneration = 4L
    h.module.storagePhaseForceCompleted = true

    h.recoveryStep() shouldBe false

    h.module.syncingEntries shouldBe 1
    h.module.bootstrappingEntries shouldBe 0
    h.module.pivotBlock shouldBe Some(BigInt(1_000))
    h.module.stateRoot shouldBe Some(TrieRoot(savedRoot))
    h.module.accountsComplete shouldBe true
    h.module.storagePhaseComplete shouldBe true
    h.module.bytecodePhaseComplete shouldBe true
    h.module.storagePhaseForceCompleted shouldBe false
    h.module.coordinatorGeneration shouldBe 5L
    h.module.heapWatchdogStarts shouldBe 1
    h.module.mptStorage shouldBe defined
    h.module.recoverySpawns.toSeq shouldBe Seq((savedRoot, h.module.mptStorage.get, true, true))
    h.module.currentPhase shouldBe SyncPhase.ByteCodeAndStorageSync
    h.module.stagnationResets.toSeq shouldBe Seq(StagnationWatchdog.ResetReason.AccountsCompleteRecovery)
    h.module.stagnationSchedules shouldBe 1
    h.module.byteCodeRequests shouldBe 1
    h.module.chainDownloaderStarts shouldBe 1
    h.module.snapServerSchedulerStarts shouldBe 1
    h.module.downloadChecks shouldBe 1
  }

  // spec 022 T050a, H0: the storage file now also names accounts with storage and no code, so its count and the code
  // files' counts differ. Each file is checked against its own count, and both are read back in full.
  it should "resume bytecodes and storage from a mixed run's task files, whose counts differ" taggedAs UnitTest in {
    val dir = tempDir("snap-resume-planner-mixed")
    val h = new Harness()
    def h32(b: Int): ByteString = ByteString(Array.fill[Byte](32)(b.toByte))
    val storagePath = dir.resolve("fukuii-contract-storage-mixed")
    val codeHashesPath = dir.resolve("fukuii-unique-codehashes-mixed")
    Files.write(storagePath, (h32(1) ++ h32(0x11) ++ h32(2) ++ h32(0x22)).toArray) // the second has no code
    Files.write(codeHashesPath, h32(0xc1).toArray)
    h.saveRecovery(BigInt(1_000), phasesComplete = false)
    val app = h.module.appStateStorage
    app
      .putSnapSyncStorageFilePath(storagePath.toString)
      .and(app.putSnapSyncStorageFileCount(Some(2L)))
      .and(app.putSnapSyncCodeHashesPath(codeHashesPath.toString))
      .and(app.putSnapSyncCodeHashesCount(Some(1L)))
      .commit()
    val storage = TestInbox[StorageRangeCoordinator.Command]()
    val bytecode = TestInbox[ByteCodeCoordinator.Command]()
    h.module.storageRangeCoordinator = Some(storage.ref)
    h.module.bytecodeCoordinator = Some(bytecode.ref)

    h.recoveryStep() shouldBe false

    h.module.syncingEntries shouldBe 1
    app.isSnapSyncAccountsComplete() shouldBe true
    // The replay's Futures run on the kit's executor, which only runs what it is told to. Its class is pekko-private.
    val executor = h.module.ctx.executionContext
    executor.getClass.getMethod("runAll").invoke(executor)
    (storage.receiveAll() should contain).allOf(
      StorageRangeCoordinator.AddStorageTasks(
        Seq(StorageTask.createStorageTask(h32(1), h32(0x11)), StorageTask.createStorageTask(h32(2), h32(0x22)))
      ),
      StorageRangeCoordinator.NoMoreStorageTasks
    )
    (bytecode.receiveAll() should contain).allOf(
      ByteCodeCoordinator.AddByteCodeTasks(Seq(h32(0xc1))),
      ByteCodeCoordinator.NoMoreByteCodeTasks
    )
    deleteDir(dir)
  }

  it should "clear the recovery and fall through when the saved pivot is below the escalation hint" taggedAs UnitTest in {
    val h = new Harness()
    h.saveRecovery(BigInt(1_000), phasesComplete = true)
    h.module.minPivotHint = BigInt(2_000)

    h.recoveryStep() shouldBe true

    h.module.appStateStorage.isSnapSyncAccountsComplete() shouldBe false
    h.module.appStateStorage.isSnapSyncStorageComplete() shouldBe false
    h.module.appStateStorage.isSnapSyncBytecodeComplete() shouldBe false
    h.module.pivotBlock shouldBe None
    h.module.syncingEntries shouldBe 0
    h.module.recoverySpawns shouldBe empty
  }

  it should "not resume healing on a PoS pivot that failed finalization, and retry the start in 30 s with no newer pivot" taggedAs UnitTest in {
    val h = new Harness()
    h.saveRecovery(BigInt(1_000), phasesComplete = true)
    h.module.isPoSChain = true
    h.module.failedFinalizationPivot = Some(BigInt(1_000))

    h.recoveryStep() shouldBe false

    h.module.bootstrappingEntries shouldBe 1
    h.module.syncingEntries shouldBe 0
    h.scheduledTimers() shouldBe Seq(("bootstrap-check", RetrySnapSyncStart, 30.seconds))
    h.module.accountsComplete shouldBe true
    h.module.storagePhaseComplete shouldBe true
    h.module.bytecodePhaseComplete shouldBe true
    h.module.pivotBlock shouldBe None
    h.module.appStateStorage.getSnapSyncBootstrapTarget() shouldBe None
    h.module.parent.hasMessages shouldBe false
    h.module.recoverySpawns shouldBe empty
  }

  it should "heal a PoS recovery whose pivot failed finalization at a newer pivot from the CL head" taggedAs UnitTest in {
    val h = new Harness()
    val header = Fixtures.Blocks.ValidBlock.header
    val target = header.number.value - SNAPSyncConfig().pivotBlockOffset
    h.saveRecovery(BigInt(3_000_000), phasesComplete = true)
    h.module.isPoSChain = true
    h.module.failedFinalizationPivot = Some(BigInt(3_000_000))
    h.module.clPivotHint = Some(CLPivotHint(header.hash.value, Some(header)))

    h.recoveryStep() shouldBe false

    h.module.bootstrappingEntries shouldBe 1
    h.module.appStateStorage.getSnapSyncBootstrapTarget() shouldBe Some(target)
    h.module.parent.receiveMessage() shouldBe StartRegularSyncBootstrap(target)
    h.scheduledTimers() shouldBe empty
    h.module.accountsComplete shouldBe true
    h.module.storagePhaseComplete shouldBe true
    h.module.bytecodePhaseComplete shouldBe true
    h.module.syncingEntries shouldBe 0
  }

  it should "resume the preserved account cursors inside the drift cap, latch the stale-cursor flag and drop the markers" taggedAs UnitTest in {
    val h = new Harness()
    val cursors = Map(ByteString(Array.fill[Byte](32)(1)) -> ByteString(Array.fill[Byte](32)(2)))
    h.module.preservedRangeProgress = cursors
    h.module.preservedAtPivotBlock = Some(BigInt(1_000))
    val account = ByteString(Array.fill[Byte](32)(3))
    val storageRoot = ByteString(Array.fill[Byte](32)(4))
    h.module.storageDoneStorage.markDone(Seq(account -> storageRoot)).commit()

    var selected: (Map[ByteString, ByteString], Option[ContractTaskFiles]) = (Map.empty, None)
    h.call(m => selected = m.selectAccountResume(TrieRoot(savedRoot), 4, BigInt(1_100)))

    selected._1 shouldBe cursors
    selected._2 shouldBe None
    h.module.resumedStaleCursors shouldBe true
    h.module.preservedAtPivotBlock shouldBe Some(BigInt(1_000))
    h.module.storageDoneStorage.isDone(account, storageRoot) shouldBe false
  }

  // spec 022 review item 3: where a cycle starts from nothing, and only there.
  private val excludedEntry =
    ExcludedAccount(ByteString(Array.fill[Byte](32)(7)), ExcludedReason.StorageAbandoned)

  it should "clear the excluded set when the account phase starts from nothing" taggedAs UnitTest in {
    val h = new Harness()
    h.module.snapProgressStorage.recordExcluded(Seq(excludedEntry))

    h.call(m => m.selectAccountResume(TrieRoot(savedRoot), 4, BigInt(1_100)))

    h.module.snapProgressStorage.hasAnyExcluded shouldBe false
  }

  it should "keep the excluded set when account cursors are resumed: the accounts behind them are not downloaded again" taggedAs UnitTest in {
    val h = new Harness()
    h.module.preservedRangeProgress = Map(ByteString(Array.fill[Byte](32)(1)) -> ByteString(Array.fill[Byte](32)(2)))
    h.module.preservedAtPivotBlock = Some(BigInt(1_000))
    h.module.snapProgressStorage.recordExcluded(Seq(excludedEntry))

    h.call(m => m.selectAccountResume(TrieRoot(savedRoot), 4, BigInt(1_100)))

    h.module.snapProgressStorage.excludedPage(None, 10) shouldBe Seq(excludedEntry)
  }

  it should "drop preserved account cursors that drifted past the cap, with their progress record and checkpoint" taggedAs UnitTest in {
    val h = new Harness()
    h.module.preservedRangeProgress = Map(ByteString(Array.fill[Byte](32)(1)) -> ByteString(Array.fill[Byte](32)(2)))
    h.module.preservedAtPivotBlock = Some(BigInt(1_000))
    h.module.snapProgressStorage.writeAccountCursors(savedRoot, 1_000L, Map("aa" -> "bb"))
    h.module.appStateStorage.putSnapAccountResumeCheckpoint("{}").commit()

    var selected: (Map[ByteString, ByteString], Option[ContractTaskFiles]) = (Map.empty, None)
    h.call(m => selected = m.selectAccountResume(TrieRoot(savedRoot), 4, BigInt(1_000 + 50_001)))

    selected._1 shouldBe empty
    selected._2 shouldBe None
    h.module.preservedRangeProgress shouldBe empty
    h.module.preservedAtPivotBlock shouldBe None
    h.module.preservedTaskFiles shouldBe None
    h.module.resumedStaleCursors shouldBe false
    h.module.snapProgressStorage.readProgress(savedRoot) shouldBe None
    h.module.appStateStorage.getSnapAccountResumeCheckpoint() shouldBe None
  }

  it should "recover account cursors from the progress store when nothing is preserved in memory" taggedAs UnitTest in {
    val h = new Harness()
    val last = Array.fill[Byte](32)(1)
    val next = Array.fill[Byte](32)(2)
    h.module.snapProgressStorage.writeAccountCursors(
      savedRoot,
      1_000L,
      Map(Hex.toHexString(last) -> Hex.toHexString(next))
    )

    var selected: (Map[ByteString, ByteString], Option[ContractTaskFiles]) = (Map.empty, None)
    h.call(m => selected = m.selectAccountResume(TrieRoot(savedRoot), 4, BigInt(1_100)))

    selected._1 shouldBe Map(ByteString(last) -> ByteString(next))
    selected._2 shouldBe None
    h.module.preservedRangeProgress shouldBe Map(ByteString(last) -> ByteString(next))
    h.module.preservedAtPivotBlock shouldBe Some(BigInt(1_000))
    h.module.resumedStaleCursors shouldBe true
  }

  /** Two account ranges: the first complete (next >= last), the second not. */
  private val checkpointCursors: Map[ByteString, ByteString] = Map(
    ByteString(Array.fill[Byte](32)(0x7f)) -> ByteString(Array.fill[Byte](32)(0x80.toByte)),
    ByteString(Array.fill[Byte](32)(0xff.toByte)) -> ByteString(Array.fill[Byte](32)(0x10))
  )

  /** A coordinator's contract task files, created in `dir` under the swept name prefixes. */
  private def contractTaskFiles(dir: Path, tag: String): ContractTaskFiles =
    ContractTaskFiles(
      storagePath = Files.write(dir.resolve(s"fukuii-contract-storage-$tag"), Array[Byte](0)).toString,
      storageCount = 3L,
      codeHashesPath = Files.write(dir.resolve(s"fukuii-unique-codehashes-$tag"), Array[Byte](0)).toString,
      codeHashesCount = 2L
    )

  /** Run the checkpoint arm on one snapshot; yields the behaviour it returns. */
  private def checkpoint(h: Harness, cmd: AccountRangeProgressCmd): Behavior[Command] =
    var next: Behavior[Command] = null
    h.call(m => next = m.accountProgressCheckpointArms(cmd))
    next

  private def deleteDir(dir: Path): Unit =
    (Files.list(dir).toArray.toSeq.map(_.asInstanceOf[Path]) :+ dir).foreach(Files.deleteIfExists)

  it should "define the checkpoint arm for the account-progress snapshot only" taggedAs UnitTest in {
    val h = new Harness()
    h.module.accountProgressCheckpointArms.isDefinedAt(AccountRangeProgressCmd(Map.empty)) shouldBe true
    h.module.accountProgressCheckpointArms.isDefinedAt(PollHandshakedPeers) shouldBe false
  }

  it should "drop a snapshot from a superseded coordinator generation, and one after accounts complete" taggedAs UnitTest in {
    val dir = tempDir("snap-resume-planner-checkpoint-drop")
    val h = new Harness(SNAPSyncConfig(taskFileDir = Some(dir)))
    val files = contractTaskFiles(dir, "own")
    val stale = Files.write(dir.resolve("fukuii-contract-storage-stale"), Array[Byte](0))
    h.module.pivotBlock = Some(BigInt(1_000))
    h.module.stateRoot = Some(TrieRoot(savedRoot))
    h.module.launchedAccountGeneration = 5L

    val superseded = AccountRangeProgressCmd(checkpointCursors, Some(files), durable = true, generation = 4L)
    checkpoint(h, superseded) shouldBe Behaviors.same

    h.module.preservedRangeProgress shouldBe empty
    h.module.preservedTaskFiles shouldBe None
    h.module.preservedAtPivotBlock shouldBe None
    h.module.appStateStorage.getSnapAccountResumeCheckpoint() shouldBe None
    Files.exists(stale) shouldBe true

    h.module.accountsComplete = true
    val late = AccountRangeProgressCmd(checkpointCursors, Some(files), durable = true, generation = 5L)
    checkpoint(h, late) shouldBe Behaviors.same

    h.module.preservedRangeProgress shouldBe empty
    h.module.preservedTaskFiles shouldBe None
    h.module.preservedAtPivotBlock shouldBe None
    h.module.appStateStorage.getSnapAccountResumeCheckpoint() shouldBe None
    Files.exists(stale) shouldBe true
    deleteDir(dir)
  }

  it should "persist a durable snapshot with task files at the current root and pivot, sweeping once per record" taggedAs UnitTest in {
    val dir = tempDir("snap-resume-planner-checkpoint-persist")
    val h = new Harness(SNAPSyncConfig(taskFileDir = Some(dir)))
    val own = contractTaskFiles(dir, "own")
    val carried = contractTaskFiles(dir, "carried")
    val stale = Files.write(dir.resolve("fukuii-contract-storage-stale"), Array[Byte](0))
    val unrelated = Files.write(dir.resolve("not-a-task-file"), Array[Byte](0))
    h.module.pivotBlock = Some(BigInt(1_000))
    h.module.stateRoot = Some(TrieRoot(savedRoot))
    h.module.launchedAccountGeneration = 3L
    h.module.currentCarrySource = Some(carried)

    val snapshot = AccountRangeProgressCmd(checkpointCursors, Some(own), durable = true, generation = 3L)
    checkpoint(h, snapshot) shouldBe Behaviors.same

    h.module.preservedRangeProgress shouldBe checkpointCursors
    h.module.preservedTaskFiles shouldBe Some(own)
    h.module.preservedAtPivotBlock shouldBe Some(BigInt(1_000))
    h.module.appStateStorage.getSnapAccountResumeCheckpoint().map(AccountResumeCheckpoint.decode) shouldBe Some(
      Right(AccountResumeCheckpoint(savedRoot, BigInt(1_000), checkpointCursors, own))
    )
    Files.exists(stale) shouldBe false
    Seq(own.storagePath, own.codeHashesPath, carried.storagePath, carried.codeHashesPath)
      .map(p => Files.exists(Path.of(p))) shouldBe Seq(true, true, true, true)
    Files.exists(unrelated) shouldBe true

    // The same record again: already swept for it, so a new stray file survives.
    val stray = Files.write(dir.resolve("fukuii-contract-storage-stray"), Array[Byte](0))
    checkpoint(h, snapshot) shouldBe Behaviors.same
    Files.exists(stray) shouldBe true

    // A record over new files sweeps again: the files it superseded go, the live carry source stays.
    val newer = contractTaskFiles(dir, "newer")
    checkpoint(h, snapshot.copy(taskFiles = Some(newer))) shouldBe Behaviors.same
    Files.exists(stray) shouldBe false
    Seq(own.storagePath, own.codeHashesPath, carried.storagePath, carried.codeHashesPath, newer.storagePath)
      .map(p => Files.exists(Path.of(p))) shouldBe Seq(false, false, true, true, true)
    h.module.preservedTaskFiles shouldBe Some(newer)
    h.module.appStateStorage.getSnapAccountResumeCheckpoint().map(AccountResumeCheckpoint.decode) shouldBe Some(
      Right(AccountResumeCheckpoint(savedRoot, BigInt(1_000), checkpointCursors, newer))
    )
    deleteDir(dir)
  }

  it should "keep a snapshot in memory only when it has no task files, no root, or is not durable" taggedAs UnitTest in {
    val dir = tempDir("snap-resume-planner-checkpoint-memory")
    val h = new Harness(SNAPSyncConfig(taskFileDir = Some(dir)))
    val files = contractTaskFiles(dir, "own")
    val stale = Files.write(dir.resolve("fukuii-contract-storage-stale"), Array[Byte](0))
    h.module.stateRoot = Some(TrieRoot(savedRoot))
    h.module.preservedAtPivotBlock = Some(BigInt(900))

    // Durable without task files, and no current pivot: the tracked pivot stays where it was.
    checkpoint(h, AccountRangeProgressCmd(checkpointCursors, None, durable = true)) shouldBe Behaviors.same
    h.module.preservedRangeProgress shouldBe checkpointCursors
    h.module.preservedTaskFiles shouldBe None
    h.module.preservedAtPivotBlock shouldBe Some(BigInt(900))
    h.module.appStateStorage.getSnapAccountResumeCheckpoint() shouldBe None

    // Not durable, with a current pivot: the cursors are tracked against it.
    h.module.pivotBlock = Some(BigInt(1_000))
    checkpoint(h, AccountRangeProgressCmd(Map.empty, Some(files))) shouldBe Behaviors.same
    h.module.preservedRangeProgress shouldBe empty
    h.module.preservedTaskFiles shouldBe Some(files)
    h.module.preservedAtPivotBlock shouldBe Some(BigInt(1_000))
    h.module.appStateStorage.getSnapAccountResumeCheckpoint() shouldBe None

    // Durable with task files but no state root (a coordinator stopped by a restart): kept in memory only.
    h.module.stateRoot = None
    checkpoint(h, AccountRangeProgressCmd(checkpointCursors, Some(files), durable = true)) shouldBe Behaviors.same
    h.module.preservedRangeProgress shouldBe checkpointCursors
    h.module.appStateStorage.getSnapAccountResumeCheckpoint() shouldBe None
    Files.exists(stale) shouldBe true
    deleteDir(dir)
  }

  // ── #1597: the account resume checkpoint reader ──

  /** Task files under the swept name prefixes that hold their counted entries (`checkResumable` validates them). */
  private def validTaskFiles(dir: Path, tag: String): ContractTaskFiles =
    ContractTaskFiles(
      storagePath = Files.write(dir.resolve(s"fukuii-contract-storage-$tag"), Array.fill[Byte](3 * 64)(1)).toString,
      storageCount = 3L,
      codeHashesPath = Files.write(dir.resolve(s"fukuii-unique-codehashes-$tag"), Array.fill[Byte](2 * 32)(2)).toString,
      codeHashesCount = 2L
    )

  private def layoutCursors(concurrency: Int): Map[ByteString, ByteString] =
    AccountTask.createInitialTasks(ByteString.empty, concurrency).map(t => t.last -> t.next).toMap

  it should "complete a 15-of-16 checkpoint to the full layout and resume from it with its task files (#1597)" taggedAs UnitTest in {
    val dir = tempDir("snap-resume-planner-1597-fill")
    val h = new Harness(SNAPSyncConfig(taskFileDir = Some(dir)))
    val files = validTaskFiles(dir, "held")
    val missing = AccountTask.createInitialTasks(ByteString.empty, 16)(4)
    val fifteen = layoutCursors(16) - missing.last
    val json = AccountResumeCheckpoint.encode(AccountResumeCheckpoint(savedRoot, BigInt(1_000), fifteen, files))
    h.module.appStateStorage.putSnapAccountResumeCheckpoint(json).commit()

    var selected: (Map[ByteString, ByteString], Option[ContractTaskFiles]) = (Map.empty, None)
    h.call(m => selected = m.selectAccountResume(TrieRoot(savedRoot), 16, BigInt(1_100)))

    selected._1.size shouldBe 16
    selected._1(missing.last) shouldBe missing.last // filled as complete
    selected._1 - missing.last shouldBe fifteen
    selected._2 shouldBe Some(files)
    h.module.preservedRangeProgress shouldBe selected._1
    h.module.appStateStorage.getSnapAccountResumeCheckpoint() shouldBe Some(json)
    deleteDir(dir)
  }

  it should "ignore an 8-range checkpoint at concurrency 16 but keep the record, its task files and the markers (#1597)" taggedAs UnitTest in {
    val dir = tempDir("snap-resume-planner-1597-held")
    val h = new Harness(SNAPSyncConfig(taskFileDir = Some(dir)))
    val held = validTaskFiles(dir, "held")
    val json = AccountResumeCheckpoint.encode(AccountResumeCheckpoint(savedRoot, BigInt(1_000), layoutCursors(8), held))
    h.module.appStateStorage.putSnapAccountResumeCheckpoint(json).commit()
    val account = ByteString(Array.fill[Byte](32)(3))
    val storageRoot = ByteString(Array.fill[Byte](32)(4))
    h.module.storageDoneStorage.markDone(Seq(account -> storageRoot)).commit()

    var selected: (Map[ByteString, ByteString], Option[ContractTaskFiles]) = (Map.empty, None)
    h.call(m => selected = m.selectAccountResume(TrieRoot(savedRoot), 16, BigInt(1_100)))

    // Not resumed: a fresh account phase at the configured layout, nothing carried.
    selected._1 shouldBe empty
    selected._2 shouldBe None
    // Left in place for a run at the matching layout.
    h.module.appStateStorage.getSnapAccountResumeCheckpoint() shouldBe Some(json)
    h.module.storageDoneStorage.isDone(account, storageRoot) shouldBe true

    // This run's durable snapshot neither overwrites the record (fixed key) nor sweeps the record's files.
    val own = validTaskFiles(dir, "own")
    h.module.pivotBlock = Some(BigInt(1_100))
    h.module.stateRoot = Some(TrieRoot(savedRoot))
    h.module.launchedAccountGeneration = 1L
    val snapshot = AccountRangeProgressCmd(layoutCursors(16), Some(own), durable = true, generation = 1L)
    checkpoint(h, snapshot) shouldBe Behaviors.same
    h.call(m => m.sweepSupersededTaskFiles(keep = Set.empty, reason = "test"))
    h.module.appStateStorage.getSnapAccountResumeCheckpoint() shouldBe Some(json)
    Seq(held.storagePath, held.codeHashesPath).map(p => Files.exists(Path.of(p))) shouldBe Seq(true, true)

    // Once the record is retired (accounts complete), the hold is released and checkpoints persist again.
    h.call(m => m.releaseHeldResumeCheckpoint())
    checkpoint(h, snapshot) shouldBe Behaviors.same
    h.module.appStateStorage.getSnapAccountResumeCheckpoint().map(AccountResumeCheckpoint.decode) shouldBe Some(
      Right(AccountResumeCheckpoint(savedRoot, BigInt(1_100), layoutCursors(16), own))
    )
    deleteDir(dir)
  }

  it should "still drop a checkpoint whose task files are short, and clear the markers (#1597)" taggedAs UnitTest in {
    val dir = tempDir("snap-resume-planner-1597-drop")
    val h = new Harness(SNAPSyncConfig(taskFileDir = Some(dir)))
    val short = validTaskFiles(dir, "short").copy(storageCount = 100L)
    val json =
      AccountResumeCheckpoint.encode(AccountResumeCheckpoint(savedRoot, BigInt(1_000), layoutCursors(16), short))
    h.module.appStateStorage.putSnapAccountResumeCheckpoint(json).commit()
    val account = ByteString(Array.fill[Byte](32)(3))
    val storageRoot = ByteString(Array.fill[Byte](32)(4))
    h.module.storageDoneStorage.markDone(Seq(account -> storageRoot)).commit()

    var selected: (Map[ByteString, ByteString], Option[ContractTaskFiles]) = (Map.empty, None)
    h.call(m => selected = m.selectAccountResume(TrieRoot(savedRoot), 16, BigInt(1_100)))

    selected._2 shouldBe None
    h.module.appStateStorage.getSnapAccountResumeCheckpoint() shouldBe None
    h.module.storageDoneStorage.isDone(account, storageRoot) shouldBe false
    deleteDir(dir)
  }

  it should "hold, not fill, 15 of the 16-range cursors at concurrency 32 (#1597 F2)" taggedAs UnitTest in {
    val dir = tempDir("snap-resume-planner-1597-refine")
    val h = new Harness(SNAPSyncConfig(taskFileDir = Some(dir)))
    val files = validTaskFiles(dir, "held")
    val fifteen = layoutCursors(16) - AccountTask.createInitialTasks(ByteString.empty, 16)(4).last
    val json = AccountResumeCheckpoint.encode(AccountResumeCheckpoint(savedRoot, BigInt(1_000), fifteen, files))
    h.module.appStateStorage.putSnapAccountResumeCheckpoint(json).commit()

    var selected: (Map[ByteString, ByteString], Option[ContractTaskFiles]) = (Map.empty, None)
    h.call(m => selected = m.selectAccountResume(TrieRoot(savedRoot), 32, BigInt(1_100)))

    selected._1 shouldBe empty
    selected._2 shouldBe None
    h.module.appStateStorage.getSnapAccountResumeCheckpoint() shouldBe Some(json)
    deleteDir(dir)
  }

  it should "keep a held record when this run's cursors drift past the cap, and drop it once it is stale too (#1597 F1)" taggedAs UnitTest in {
    val dir = tempDir("snap-resume-planner-1597-held-drift")
    val h = new Harness(SNAPSyncConfig(taskFileDir = Some(dir)))
    val held = validTaskFiles(dir, "held")
    val json =
      AccountResumeCheckpoint.encode(AccountResumeCheckpoint(savedRoot, BigInt(30_000), layoutCursors(8), held))
    h.module.appStateStorage.putSnapAccountResumeCheckpoint(json).commit()

    var selected: (Map[ByteString, ByteString], Option[ContractTaskFiles]) = (Map.empty, None)
    h.call(m => selected = m.selectAccountResume(TrieRoot(savedRoot), 16, BigInt(1_000)))
    selected._1 shouldBe empty
    h.module.appStateStorage.getSnapAccountResumeCheckpoint() shouldBe Some(json)

    // This run's own cursors, tracked in memory at pivot 1,000; the next launch is 50,001 blocks later.
    h.module.preservedRangeProgress = layoutCursors(16)
    h.module.preservedAtPivotBlock = Some(BigInt(1_000))
    h.call(m => selected = m.selectAccountResume(TrieRoot(savedRoot), 16, BigInt(51_001)))
    selected._1 shouldBe empty
    h.module.preservedRangeProgress shouldBe empty
    // The held record (pivot 30,000) is within the cap of 51,001: kept.
    h.module.appStateStorage.getSnapAccountResumeCheckpoint() shouldBe Some(json)

    // Again, now far past the held record's own pivot too: the hold is released and the record dropped.
    h.module.preservedRangeProgress = layoutCursors(16)
    h.module.preservedAtPivotBlock = Some(BigInt(51_001))
    h.call(m => selected = m.selectAccountResume(TrieRoot(savedRoot), 16, BigInt(110_000)))
    h.module.appStateStorage.getSnapAccountResumeCheckpoint() shouldBe None
    // Released: this run's durable checkpoints persist again.
    val own = validTaskFiles(dir, "own")
    h.module.pivotBlock = Some(BigInt(110_000))
    h.module.stateRoot = Some(TrieRoot(savedRoot))
    h.module.launchedAccountGeneration = 1L
    checkpoint(h, AccountRangeProgressCmd(layoutCursors(16), Some(own), durable = true, generation = 1L)) shouldBe
      Behaviors.same
    h.module.appStateStorage.getSnapAccountResumeCheckpoint() shouldBe defined
    deleteDir(dir)
  }
