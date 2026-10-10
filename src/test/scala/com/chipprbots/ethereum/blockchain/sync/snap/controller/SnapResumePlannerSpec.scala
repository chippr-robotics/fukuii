package com.chipprbots.ethereum.blockchain.sync.snap.controller

import java.nio.file.Files
import java.nio.file.Path

import org.apache.pekko.actor.Scheduler
import org.apache.pekko.actor.testkit.typed.scaladsl.BehaviorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.ManualTime
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.TimerScheduler
import org.apache.pekko.util.ByteString

import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.util.Success

import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.SyncProtocol
import com.chipprbots.ethereum.blockchain.sync.snap.GatedTaskFileReplay
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncConfig
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.*
import com.chipprbots.ethereum.blockchain.sync.snap.StateValidator
import com.chipprbots.ethereum.blockchain.sync.snap.StorageScheme
import com.chipprbots.ethereum.db.dataSource.DataSourceUpdateOptimized
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.FlatSlotStorage
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.db.storage.Namespaces
import com.chipprbots.ethereum.db.storage.PathNodeStorage
import com.chipprbots.ethereum.db.storage.SnapStorageDoneStorage
import com.chipprbots.ethereum.db.storage.StateStorage
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.BlockchainWriter
import com.chipprbots.ethereum.mpt.HexPrefix
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.PeerEventBusActor
import com.chipprbots.ethereum.testing.Tags.UnitTest
import com.chipprbots.ethereum.utils.Config.SyncConfig

/** Everything `SnapResumePlanner` needs, as a stub (spec 016 FR-016 (b)): its `SnapResumePlannerState` and the
  * environment. One ephemeral data source backs the app-state, flat-slot, done-marker and trie stores, as one RocksDB
  * does in production; the compaction dispatcher runs inline; the members these tests never reach throw.
  */
private[snap] class StubSnapResumePlannerState(
    val ctx: ActorContext[SNAPSyncController.Command],
    val timers: TimerScheduler[SNAPSyncController.Command],
    val snapSyncConfig: SNAPSyncConfig,
    val scheduler: Scheduler
) extends SnapResumePlannerState
    with SnapControllerEnv:

  private val dataSource = EphemDataSource()

  // SnapResumePlannerState (scheduler is a constructor val)
  var mptStorage: Option[MptStorage] = None
  val storageDoneStorage: SnapStorageDoneStorage = new SnapStorageDoneStorage(dataSource)

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
  def syncController: ActorRef[SyncProtocol.SyncControllerReply] = notUsed("syncController")
  def childFactories: ChildFactories = notUsed("childFactories")
  def pathNodeStorageOpt: Option[PathNodeStorage] = None

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

  private def tempDir(prefix: String): Path = Files.createTempDirectory(prefix)

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
