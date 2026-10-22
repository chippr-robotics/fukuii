package com.chipprbots.ethereum.blockchain.sync.snap.controller

import org.apache.pekko.actor.testkit.typed.scaladsl.BehaviorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.TestInbox
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.TimerScheduler
import org.apache.pekko.util.ByteString

import scala.collection.mutable

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.blockchain.sync.SyncProtocol
import com.chipprbots.ethereum.blockchain.sync.TestSyncConfig
import com.chipprbots.ethereum.blockchain.sync.snap.ChainDownloader
import com.chipprbots.ethereum.blockchain.sync.snap.PathToHashExporter
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncConfig
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.*
import com.chipprbots.ethereum.blockchain.sync.snap.SyncProgressMonitor
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.components.EphemDataSourceComponent
import com.chipprbots.ethereum.db.components.Storages
import com.chipprbots.ethereum.db.dataSource.DataUpdate
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.db.storage.NodeStorage
import com.chipprbots.ethereum.db.storage.PathNodeStorage
import com.chipprbots.ethereum.db.storage.StateStorage
import com.chipprbots.ethereum.db.storage.pruning.ArchivePruning
import com.chipprbots.ethereum.db.storage.pruning.InMemoryPruning
import com.chipprbots.ethereum.db.storage.pruning.PruningMode
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockBody
import com.chipprbots.ethereum.domain.BlockNumber
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.domain.TrieRoot
import com.chipprbots.ethereum.mpt.LeafNode
import com.chipprbots.ethereum.mpt.MptTraversals
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.PeerEventBusActor
import com.chipprbots.ethereum.testing.Tags.UnitTest

/** An ephemeral data source that records every write in order, and whether it was a synced (`updateSync`) write. */
final private class RecordingDataSource extends EphemDataSource(Map.empty):
  val writes: mutable.ArrayBuffer[(Boolean, Seq[DataUpdate])] = mutable.ArrayBuffer.empty

  override def update(dataSourceUpdates: Seq[DataUpdate]): Unit =
    writes += ((false, dataSourceUpdates))
    super.update(dataSourceUpdates)

  override def updateSync(dataSourceUpdates: Seq[DataUpdate]): Unit =
    writes += ((true, dataSourceUpdates))
    super.update(dataSourceUpdates)

  /** A fresh store holding only the first `n` writes: what a crash after them would leave, with the WAL replayed. */
  def prefix(n: Int): EphemDataSource =
    val replay = EphemDataSource()
    writes.take(n).foreach { case (_, updates) => replay.update(updates) }
    replay

/** Puts a [[RecordingDataSource]] under every store of a storages component. It must come after
  * `EphemDataSourceComponent` and before `Storages.DefaultStorages` in the mix-in order, so the stores are built over
  * it.
  */
private trait RecordingStore extends EphemDataSourceComponent:
  override val dataSource: RecordingDataSource = new RecordingDataSource

/** Sets the pruning mode before `Storages.DefaultStorages` reads it (its `storages` val is built at construction). */
private trait FixedPruningMode(mode: PruningMode) extends Storages.PruningModeComponent:
  override val pruningMode: PruningMode = mode

/** The ephemeral test chain with its stores over a [[RecordingDataSource]]. */
private class RecordingChain(mode: PruningMode = ArchivePruning) extends EphemBlockchainTestSetup with TestSyncConfig:
  override lazy val storagesInstance: EphemDataSourceComponent & LocalPruningConfigBuilder & Storages.DefaultStorages =
    new EphemDataSourceComponent
      with RecordingStore
      with LocalPruningConfigBuilder
      with FixedPruningMode(mode)
      with Storages.DefaultStorages
      with com.chipprbots.ethereum.TestInstanceConfigProvider

  def recording: RecordingDataSource = storagesInstance.dataSource.asInstanceOf[RecordingDataSource]

/** `SnapFinalization` on its stub, over stores that share one [[RecordingDataSource]], with the members the anchor and
  * hand-off reach after the completion flags (progress, schedules, task-file sweep) answering instead of throwing.
  */
private class DurabilityStub(
    ctx: ActorContext[SNAPSyncController.Command],
    timers: TimerScheduler[SNAPSyncController.Command],
    config: SNAPSyncConfig,
    parent: ActorRef[SyncProtocol.SyncControllerReply],
    adapter: ActorRef[ChainDownloader.Done.type],
    chain: EphemBlockchainTestSetup & TestSyncConfig,
    pathStore: Option[PathNodeStorage],
    target: MptStorage
) extends StubSnapFinalizationState(
      ctx,
      timers,
      config,
      parent,
      adapter,
      new RecordingChainDownloaderFactory,
      TestInbox[NetworkPeerManagerActor.Command]().ref,
      TestInbox[PeerEventBusActor.Command]().ref,
      chain
    ):
  private val monitor = new SyncProgressMonitor(null)
  override def progressMonitor: SyncProgressMonitor = monitor
  override def pathNodeStorageOpt: Option[PathNodeStorage] = pathStore
  override def stateStorage: StateStorage = chain.storagesInstance.storages.stateStorage
  override def getOrCreateMptStorage(pivotBlockNumber: BigInt): MptStorage =
    mptStorageRequests += pivotBlockNumber
    target
  override def accountsCompleteTaskFilePaths: Set[String] = Set.empty
  override def sweepSupersededTaskFiles(keep: Set[String], reason: String): Unit = ()
  override def stopSnapOnlySchedules(): Unit = ()
  override def stopStateSyncChildren(): Unit = ()

/** Spec 016 T055, FR-044: the order in which SNAP finalization makes state durable, on both schemes, as the code does
  * it today (ADR INF-006).
  *
  * The property: the state execution will read (the Path-to-hash publish's output on the Path scheme, the ingest writes
  * on the Hash scheme) is durable before `snapSyncDone` is. Today that comes from one place: the completion flags are
  * written in one synced commit (`commitSync`), the only synced write on the path, after every ingest and publish
  * write. A synced RocksDB write syncs the WAL up to and including itself, so a crash leaves a prefix of the writes: if
  * the flag survived, everything before it did. These cases pin that ordering with a store that records each write, and
  * check the prefix property directly by replaying every prefix of the recorded writes.
  *
  * Scope of this test (vault review): the prefix replay models the RocksDB sync guarantee, it does not exercise it, so
  * `flagImpliesRoot` pins call order, not durability. The ingest write and the publish are driven by the test, not by
  * production SNAP. The stores run on archive pruning (an ephemeral data source), so the test cannot see the `inmemory`
  * pruning gap, where trie nodes stay in a cache and never reach the WAL before the flags.
  *
  * There is no separate flush/sync barrier before `completeSnapSync` today on either scheme, so these cases do not
  * expect one. The barrier is added by T056, as a hard precondition of any relaxed WAL policy (user decision
  * 2026-10-11), and T056 extends these cases to expect it before the flags.
  */
class StateIngestDurabilitySpec extends AnyFlatSpec with Matchers:

  private val pivot = BigInt(1_000)

  /** One finalization run over a recording store. */
  final private class Harness(pathScheme: Boolean, mode: PruningMode = ArchivePruning):
    val chain: RecordingChain = new RecordingChain(mode)
    val recording: RecordingDataSource = chain.recording
    val target: MptStorage = chain.storagesInstance.storages.stateStorage.getBackingStorage(pivot)
    val pathStore: Option[PathNodeStorage] = Option.when(pathScheme)(new PathNodeStorage(recording))
    val parent: TestInbox[SyncProtocol.SyncControllerReply] = TestInbox[SyncProtocol.SyncControllerReply]()
    private var action: () => Unit = () => ()
    private var captured: DurabilityStub & SnapFinalization = null
    val kit: BehaviorTestKit[Command] = BehaviorTestKit(Behaviors.setup[Command] { ctx =>
      Behaviors.withTimers { timers =>
        captured = new DurabilityStub(
          ctx,
          timers,
          SNAPSyncConfig(chainDownloadEnabled = false),
          parent.ref,
          TestInbox[ChainDownloader.Done.type]().ref,
          chain,
          pathStore,
          target
        ) with SnapFinalization
        Behaviors.receiveMessage { _ =>
          action()
          Behaviors.same
        }
      }
    })
    val module: DurabilityStub & SnapFinalization = captured

    /** Run `f` on the module while the kit processes a message. */
    def call(f: DurabilityStub & SnapFinalization => Unit): Unit =
      action = () => f(module)
      kit.run(PollHandshakedPeers)

    /** A leaf node standing for the pivot's state trie, and its hash. */
    val rootNode: Array[Byte] =
      MptTraversals.encodeNode(LeafNode(ByteString(Array.fill[Byte](64)(1)), ByteString(Array.fill[Byte](40)(7))))
    val root: ByteString = ByteString(kec256(rootNode))

    /** Genesis and the pivot header (state root = `root`), stored before anything the test measures. */
    def storeChain(): Unit =
      val genesis = Fixtures.Blocks.Genesis.header
      chain.blockchainWriter.save(
        Block(genesis, BlockBody.empty),
        Nil,
        ChainWeight.totalDifficultyOnly(genesis.difficulty.value),
        saveAsBestBlock = true
      )
      val header = genesis.copy(number = BlockNumber(pivot), stateRoot = TrieRoot(root))
      chain.blockchainWriter.storeBlockHeader(header).commit()

    def flagSetAfter(n: Int): Boolean = new AppStateStorage(recording.prefix(n)).isSnapSyncDone()

    def rootNodeDurableAfter(n: Int): Boolean = new NodeStorage(recording.prefix(n)).get(root).isDefined

  /** Every prefix of the recorded writes that holds `snapSyncDone` also holds the pivot root node. */
  private def flagImpliesRoot(h: Harness): Unit =
    (0 to h.recording.writes.size).foreach { n =>
      if h.flagSetAfter(n) then withClue(s"after the first $n writes: ")(h.rootNodeDurableAfter(n) shouldBe true)
    }

  "SNAP finalization" should "sync the completion flags after the Hash-scheme ingest (FR-044)" taggedAs UnitTest in {
    val h = new Harness(pathScheme = false)
    h.storeChain()
    // The ingest write SNAP made before finalization: the pivot's state trie, written straight into the store
    // execution reads (Hash scheme has no export step).
    h.target.storeRawNodes(Seq(h.root -> h.rootNode))
    val ingestWrites = h.recording.writes.size

    h.call(_.finalizeSnapSync(pivot))

    h.module.currentPhase shouldBe SyncPhase.Completed
    val synced = h.recording.writes.indices.filter(i => h.recording.writes(i)._1)
    synced should have size 1
    val flags = synced.head
    flags should be >= ingestWrites
    h.recording.writes.take(ingestWrites).forall(!_._1) shouldBe true
    h.flagSetAfter(flags) shouldBe false
    h.flagSetAfter(flags + 1) shouldBe true
    h.rootNodeDurableAfter(flags) shouldBe true
    flagImpliesRoot(h)
  }

  it should "sync the completion flags after the Path-scheme publish (FR-044)" taggedAs UnitTest in {
    val h = new Harness(pathScheme = true)
    h.storeChain()
    // SNAP wrote the state path-keyed; the publish copies it into the hash-keyed store execution reads.
    h.pathStore.get.writeAccountNode(Array.emptyByteArray, h.rootNode)
    val beforePublish = h.recording.writes.size
    val result = PathToHashExporter.publish(h.pathStore.get, h.target)
    val afterPublish = h.recording.writes.size
    result.accountNodes shouldBe 1L
    afterPublish should be > beforePublish
    h.module.pathPublish = Some((pivot, h.root))

    h.call(_.onPathPublishDone(result, 0L))

    h.module.pathPublish shouldBe None
    h.module.currentPhase shouldBe SyncPhase.Completed
    val synced = h.recording.writes.indices.filter(i => h.recording.writes(i)._1)
    synced should have size 1
    val flags = synced.head
    flags should be >= afterPublish
    h.recording.writes.slice(beforePublish, afterPublish).forall(!_._1) shouldBe true
    h.flagSetAfter(flags) shouldBe false
    h.flagSetAfter(flags + 1) shouldBe true
    h.rootNodeDurableAfter(flags) shouldBe true
    flagImpliesRoot(h)
  }

  it should "sync the completion flags after the Path-scheme publish under inmemory pruning (#1581)" taggedAs UnitTest in {
    val h = new Harness(pathScheme = true, mode = InMemoryPruning(10))
    h.storeChain()
    // SNAP wrote the state path-keyed; the publish copies it into the hash-keyed store execution reads.
    h.pathStore.get.writeAccountNode(Array.emptyByteArray, h.rootNode)
    val result = PathToHashExporter.publish(h.pathStore.get, h.target)
    result.accountNodes shouldBe 1L
    h.module.pathPublish = Some((pivot, h.root))

    h.call(_.onPathPublishDone(result, 0L))

    h.module.pathPublish shouldBe None
    h.module.currentPhase shouldBe SyncPhase.Completed
    val synced = h.recording.writes.indices.filter(i => h.recording.writes(i)._1)
    synced should have size 1
    val flags = synced.head
    h.flagSetAfter(flags) shouldBe false
    h.flagSetAfter(flags + 1) shouldBe true
    h.rootNodeDurableAfter(flags) shouldBe true
    flagImpliesRoot(h)
  }

  it should "write the ingested nodes to the data source before the completion flags under inmemory pruning (#1581)" taggedAs UnitTest in {
    val h = new Harness(pathScheme = false, mode = InMemoryPruning(10))
    h.storeChain()
    // Under inmemory pruning this write lands in the LRU cache only; nothing reaches the data source yet.
    h.target.storeRawNodes(Seq(h.root -> h.rootNode))

    h.call(_.finalizeSnapSync(pivot))

    h.module.currentPhase shouldBe SyncPhase.Completed
    val flags = h.recording.writes.indices.filter(i => h.recording.writes(i)._1).head
    h.flagSetAfter(flags + 1) shouldBe true
    h.rootNodeDurableAfter(flags) shouldBe true
    flagImpliesRoot(h)
  }
