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
import com.chipprbots.ethereum.blockchain.sync.snap.ContractTaskFiles
import com.chipprbots.ethereum.blockchain.sync.snap.GatedTaskFileReplay
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPRequestTracker
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncConfig
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.*
import com.chipprbots.ethereum.blockchain.sync.snap.SnapIntakeBudget
import com.chipprbots.ethereum.blockchain.sync.snap.StateValidator
import com.chipprbots.ethereum.blockchain.sync.snap.StorageScheme
import com.chipprbots.ethereum.blockchain.sync.snap.SyncProgressMonitor
import com.chipprbots.ethereum.db.dataSource.DataSourceUpdateOptimized
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
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
  def evmCodeStorage: EvmCodeStorage = notUsed("evmCodeStorage")
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

  // CoordinatorHandles (no coordinator is spawned: the recovery spawns are the stub's DownloadRecoveryApi)
  def intakeBudget: SnapIntakeBudget = notUsed("intakeBudget")
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
