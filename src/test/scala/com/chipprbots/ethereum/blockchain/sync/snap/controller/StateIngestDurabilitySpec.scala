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
import com.chipprbots.ethereum.db.storage.PathHandoffSample
import com.chipprbots.ethereum.db.storage.PathNodeStorage
import com.chipprbots.ethereum.db.storage.StateStorage
import com.chipprbots.ethereum.db.storage.pathstate.BlockRef
import com.chipprbots.ethereum.db.storage.pathstate.PathStateStorage
import com.chipprbots.ethereum.db.storage.pathstate.SyncHandoff
import com.chipprbots.ethereum.db.storage.pruning.ArchivePruning
import com.chipprbots.ethereum.db.storage.pruning.BasicPruning
import com.chipprbots.ethereum.db.storage.pruning.InMemoryPruning
import com.chipprbots.ethereum.db.storage.pruning.PruningMode
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockBody
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockNumber
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.domain.Difficulty
import com.chipprbots.ethereum.domain.TrieRoot
import com.chipprbots.ethereum.mpt.LeafNode
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
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
    target: MptStorage,
    terminalTotalDifficultyOf: Option[BigInt] = None
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
  override def terminalTotalDifficulty: Option[BigInt] = terminalTotalDifficultyOf

  /** Set by a test that ends the sync on the unified state layout (spec 018 C5a part 5). */
  var unifiedHandoff: Option[SyncHandoff] = None
  override def handoff: Option[SyncHandoff] = unifiedHandoff
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
  final private class Harness(
      pathScheme: Boolean,
      mode: PruningMode = ArchivePruning,
      terminalTotalDifficulty: Option[BigInt] = None,
      config: SNAPSyncConfig = SNAPSyncConfig(chainDownloadEnabled = false)
  ):
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
          config,
          parent.ref,
          TestInbox[ChainDownloader.Done.type]().ref,
          chain,
          pathStore,
          target,
          terminalTotalDifficulty
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

    // ---- the unified state layout (spec 018 C5a part 5) ----

    private val genesisHeader = Fixtures.Blocks.Genesis.header

    /** The pivot's header on a chain of headers that reaches `below` blocks under it, each naming its parent by hash;
      * genesis is stored too. Nothing of it is the head. The pivot's header is in the index of blocks by number, as the
      * pivot's bootstrap leaves it (`indexThePivot`); the headers under it are stored by their hashes alone.
      */
    def storeChainBelowThePivot(
        below: Int,
        bodiesOfTheLast: Int,
        indexThePivot: Boolean = true,
        weighed: Boolean = true,
        afterTheMerge: Boolean = false
    ): BlockHeader =
      chain.blockchainWriter.save(
        Block(genesisHeader, BlockBody.empty),
        Nil,
        ChainWeight.totalDifficultyOnly(genesisHeader.difficulty.value),
        saveAsBestBlock = true
      )
      val first = pivot - below
      val headers = (first to pivot).foldLeft(Vector.empty[BlockHeader]) { (chained, number) =>
        val parent = chained.lastOption.fold(genesisHeader.hash)(_.hash)
        val stateRoot = if number == pivot then TrieRoot(root) else genesisHeader.stateRoot
        val header = genesisHeader.copy(number = BlockNumber(number), parentHash = parent, stateRoot = stateRoot)
        chained :+ (if afterTheMerge then header.copy(difficulty = Difficulty.Zero) else header)
      }
      headers.foreach(header => chain.blockchainWriter.storeBlockHeaderByHashOnly(header).commit())
      if indexThePivot then chain.blockchainWriter.storeBlockHeader(headers.last).commit()
      // the weight of the pivot's parent, as a header download that came up from the genesis leaves it: the pivot's
      // own is then its parent's and its difficulty, exactly
      if weighed then
        val parent = headers(headers.size - 2)
        chain.blockchainWriter
          .storeChainWeight(
            parent.hash,
            ChainWeight.totalDifficultyOnly(genesisHeader.difficulty.value * parent.number.value)
          )
          .commit()
      headers.takeRight(bodiesOfTheLast).foreach { header =>
        chain.blockchainWriter.storeCheckedBlockBody(header, BlockBody.empty).commit()
      }
      headers.last

    /** A datadir of the unified layout as a sync finds it at its end: created with its genesis, the genesis state
      * cleared as the sync started, and the rows of the pivot's state written by the sync, with no sync of the log.
      */
    def unifiedLayoutAtTheEndOfASync(): PathStateStorage =
      val rows = pathStore.get
      val state = new PathStateStorage(rows, chain.storagesInstance.storages.appStateStorage)
      val genesis = BlockRef(genesisHeader.hash.value, 0, ByteString(MerklePatriciaTrie.EmptyRootHash))
      state.commitGenesis(state.genesisLayer(), genesis, None)
      state.clearGenesisStateForSync(genesis, atMillis = 1L) shouldBe true
      rows.writeAccountNode(Array.emptyByteArray, rootNode)
      val stores = chain.storagesInstance.storages
      module.unifiedHandoff = Some(
        new SyncHandoff(
          state,
          rows,
          stores.appStateStorage,
          stores.blockHeadersStorage,
          stores.blockNumberMappingStorage,
          stores.chainWeightStorage,
          stores.blockBodiesStorage,
          stores.evmCodeStorage
        )
      )
      state

    /** The places, among all the writes, of the synced ones made after the first `n`. */
    def syncedSince(n: Int): Seq[Int] = recording.writes.indices.filter(i => i >= n && recording.writes(i)._1)

    /** The sample has passed for the pivot's root, and finalisation goes on from it: as `syncing` does on its result.
      */
    def handOffFromAPassedSample(): Unit =
      module.pathPublish = Some((pivot, root))
      call(_.onHandoffSampleDone(PathHandoffSample.Result(sampledAccountLeaves = 1)))

    def anchorAfter(n: Int): Option[BlockRef] = new AppStateStorage(recording.prefix(n)).getStateAnchor()

    def rootRowAfter(n: Int): Boolean =
      new PathNodeStorage(recording.prefix(n)).readAccountNode(Array.emptyByteArray).exists(_.sameElements(rootNode))

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

  // ---- the unified state layout (spec 018 C5a part 5; T199, T201; data-model 7.4) ----
  //
  // Nothing is copied: the rows the sync wrote are the state. The barrier is one synced write that carries the anchor
  // with the sync done, the best block and the pivot's header, after every row: a recovered `snapSyncDone` implies the
  // anchor and the rows. The same under `basic` and `archive` pruning: neither keeps state in a cache.

  private def unifiedPrefixProperty(h: Harness): Unit =
    (0 to h.recording.writes.size).foreach { n =>
      withClue(s"after the first $n writes: ") {
        h.flagSetAfter(n) shouldBe h.anchorAfter(n).exists(_.number == pivot)
        if h.flagSetAfter(n) then h.rootRowAfter(n) shouldBe true
      }
    }

  Seq[(String, PruningMode)]("archive" -> ArchivePruning, "basic" -> BasicPruning(64)).foreach { (name, mode) =>
    "SNAP finalization on the unified state layout" should s"hand off in one synced write after the rows, with nothing copied ($name pruning)" taggedAs UnitTest in {
      val h = new Harness(pathScheme = true, mode = mode)
      val pivotHeader = h.storeChainBelowThePivot(below = 256, bodiesOfTheLast = 6)
      val state = h.unifiedLayoutAtTheEndOfASync()
      val before = h.recording.writes.size

      h.handOffFromAPassedSample()

      h.module.currentPhase shouldBe SyncPhase.Completed
      withClue("one synced write, and it is the hand-off's: ")(h.syncedSince(before) should have size 1)
      val handOff = h.syncedSince(before).head
      h.flagSetAfter(handOff) shouldBe false
      h.anchorAfter(handOff) shouldBe None
      h.flagSetAfter(handOff + 1) shouldBe true
      val pivotRef = BlockRef(pivotHeader.hash.value, pivot, h.root)
      h.anchorAfter(handOff + 1) shouldBe Some(pivotRef)
      unifiedPrefixProperty(h)

      // everything the hand-off says, in that one write
      val appState = h.chain.storagesInstance.storages.appStateStorage
      state.anchor shouldBe Some(pivotRef)
      appState.getStateOrigin() shouldBe Some(pivotRef)
      appState.getBestBlockInfo().number shouldBe pivot
      appState.getBestBlockInfo().hash shouldBe pivotHeader.hash.value
      appState.isStorageRecoveryDone() shouldBe true
      appState.isBytecodeRecoveryDone() shouldBe true
      h.chain.blockchainReader.getBlockHeaderByNumber(pivot).map(_.hash) shouldBe Some(pivotHeader.hash)
      h.chain.blockchainReader.getChainWeightByHash(pivotHeader.hash) shouldBe defined
      withClue("nothing was published to the hash store: ")(h.module.mptStorageRequests shouldBe empty)
      h.parent.receiveAll() should contain(SnapSyncFinalized(pivot))
    }
  }

  "SNAP finalization on the unified state layout" should "hold while the window below the pivot is not stored, and hand off when it is" taggedAs UnitTest in {
    val h = new Harness(pathScheme = true)
    // the headers, and the bodies of all but the lowest of the six a block's ommers are checked against
    val pivotHeader = h.storeChainBelowThePivot(below = 256, bodiesOfTheLast = 5)
    h.unifiedLayoutAtTheEndOfASync()
    val before = h.recording.writes.size
    // the sample has passed for this pivot: the hold comes after it, and its tick does not sample again
    h.handOffFromAPassedSample()

    h.module.headerHold shouldBe Some(pivotHeader.hash.value)
    h.module.currentPhase should not be SyncPhase.Completed
    h.syncedSince(before) shouldBe empty
    h.flagSetAfter(h.recording.writes.size) shouldBe false
    h.anchorAfter(h.recording.writes.size) shouldBe None
    h.parent.hasMessages shouldBe false

    // the body arrives; the hold's tick is finalisation again
    val lowest = h.chain.blockchainReader.getBlockHeaderByNumber(pivot - 5)
    val sixth = h.chain.blockchainReader
      .getBlockHeaderByHash(
        (1 to 5)
          .foldLeft(pivotHeader)((header, _) => h.chain.blockchainReader.getBlockHeaderByHash(header.parentHash).get)
          .hash
      )
      .get
    lowest shouldBe None // the window is found by hash, not by the index of blocks by number
    h.chain.blockchainWriter.storeCheckedBlockBody(sixth, BlockBody.empty).commit()

    h.call(_.onHeaderHoldTick(pivot))

    h.module.headerHold shouldBe None
    h.module.currentPhase shouldBe SyncPhase.Completed
    unifiedPrefixProperty(h)
  }

  it should "hold for a header of the 256 below the pivot that is not stored" taggedAs UnitTest in {
    val h = new Harness(pathScheme = true)
    h.storeChainBelowThePivot(below = 255, bodiesOfTheLast = 6)
    h.unifiedLayoutAtTheEndOfASync()

    h.handOffFromAPassedSample()

    h.module.headerHold shouldBe defined
    h.flagSetAfter(h.recording.writes.size) shouldBe false
  }

  it should "ask for no body after the merge: a block there has no ommers" taggedAs UnitTest in {
    val h = new Harness(pathScheme = true, terminalTotalDifficulty = Some(BigInt(0)))
    h.storeChainBelowThePivot(below = 256, bodiesOfTheLast = 0, afterTheMerge = true)
    h.unifiedLayoutAtTheEndOfASync()

    h.handOffFromAPassedSample()

    h.module.headerHold shouldBe None
    h.module.currentPhase shouldBe SyncPhase.Completed
    h.flagSetAfter(h.recording.writes.size) shouldBe true
    unifiedPrefixProperty(h)
  }

  it should "refuse to hand off, and say nothing is synced, when the pivot's header is not stored" taggedAs UnitTest in {
    val h = new Harness(pathScheme = true)
    h.storeChainBelowThePivot(below = 256, bodiesOfTheLast = 6, indexThePivot = false)
    h.unifiedLayoutAtTheEndOfASync()
    val before = h.recording.writes.size

    h.handOffFromAPassedSample()

    h.module.currentPhase should not be SyncPhase.Completed
    h.recording.writes.size shouldBe before
    h.chain.storagesInstance.storages.appStateStorage.getBestBlockNumber() shouldBe 0
    h.parent.receiveAll() shouldBe Seq(SyncProtocol.HealingImpossible)
  }

  it should "go on from a sample that passed, and write nothing when the sample stops the hand-off" taggedAs UnitTest in {
    val passed = new Harness(pathScheme = true)
    passed.storeChainBelowThePivot(below = 256, bodiesOfTheLast = 6)
    passed.unifiedLayoutAtTheEndOfASync()
    passed.handOffFromAPassedSample()

    passed.module.pathPublish shouldBe None
    passed.module.currentPhase shouldBe SyncPhase.Completed
    passed.flagSetAfter(passed.recording.writes.size) shouldBe true

    val stopped = new Harness(pathScheme = true)
    stopped.storeChainBelowThePivot(below = 256, bodiesOfTheLast = 6)
    stopped.unifiedLayoutAtTheEndOfASync()
    stopped.module.pathPublish = Some((pivot, stopped.root))
    val before = stopped.recording.writes.size

    stopped.call(_.onHandoffSampleFailed(new PathHandoffSample.Stopped("a hole")))

    stopped.module.pathPublish shouldBe None
    stopped.recording.writes.size shouldBe before
    stopped.flagSetAfter(before) shouldBe false
    stopped.parent.receiveAll() shouldBe Seq(SyncProtocol.HealingImpossible)
  }

  it should "throw what a store that failed under the sample threw: healing again does not mend it" taggedAs UnitTest in {
    val h = new Harness(pathScheme = true)
    h.storeChainBelowThePivot(below = 256, bodiesOfTheLast = 6)
    h.unifiedLayoutAtTheEndOfASync()
    h.module.pathPublish = Some((pivot, h.root))

    val thrown = the[java.io.IOException] thrownBy h.call(_.onHandoffSampleFailed(new java.io.IOException("the disk")))

    thrown.getMessage shouldBe "the disk"
    h.module.pathPublish shouldBe None
    h.parent.hasMessages shouldBe false
    h.flagSetAfter(h.recording.writes.size) shouldBe false
  }

  // forge (T243): the heaviest chain is chosen by this number for every block above the pivot.
  it should "refuse to hand off with an estimated weight where fork choice is by weight, and write nothing" taggedAs UnitTest in {
    val h = new Harness(pathScheme = true)
    h.storeChainBelowThePivot(below = 256, bodiesOfTheLast = 6, weighed = false)
    h.unifiedLayoutAtTheEndOfASync()
    val before = h.recording.writes.size

    h.handOffFromAPassedSample()

    h.module.currentPhase should not be SyncPhase.Completed
    h.syncedSince(before) shouldBe empty
    h.anchorAfter(h.recording.writes.size) shouldBe None
    h.parent.receiveAll() shouldBe Seq(SyncProtocol.HealingImpossible)
  }

  it should "take an estimated weight after the merge, where no fork is chosen by it" taggedAs UnitTest in {
    val h = new Harness(pathScheme = true, terminalTotalDifficulty = Some(BigInt(0)))
    h.storeChainBelowThePivot(below = 256, bodiesOfTheLast = 0, weighed = false, afterTheMerge = true)
    h.unifiedLayoutAtTheEndOfASync()

    h.handOffFromAPassedSample()

    h.module.currentPhase shouldBe SyncPhase.Completed
  }

  // A chain with a terminal total difficulty whose pivot is still before the merge: blocks above it have ommers.
  it should "hold for the bodies below a pivot that has a difficulty, on a chain with a terminal total difficulty" taggedAs UnitTest in {
    val h = new Harness(pathScheme = true, terminalTotalDifficulty = Some(BigInt("58750000000000000000000")))
    val pivotHeader = h.storeChainBelowThePivot(below = 256, bodiesOfTheLast = 5)
    pivotHeader.difficulty.value should be > BigInt(0)
    h.unifiedLayoutAtTheEndOfASync()

    h.handOffFromAPassedSample()

    h.module.headerHold shouldBe Some(pivotHeader.hash.value)
    h.flagSetAfter(h.recording.writes.size) shouldBe false
  }

  // The write that hands off names a root: it is the one the sample looked at, or the sample is made again.
  it should "not hand off on a sample that passed for another root" taggedAs UnitTest in {
    val h = new Harness(pathScheme = true)
    h.storeChainBelowThePivot(below = 256, bodiesOfTheLast = 6)
    h.unifiedLayoutAtTheEndOfASync()
    val before = h.recording.writes.size
    // a sample passed for this pivot's number, over the root of another block at that height
    h.module.pathPublish = Some((pivot, ByteString(Array.fill[Byte](32)(5))))

    scala.util.Try(h.call(_.onHandoffSampleDone(PathHandoffSample.Result(sampledAccountLeaves = 1))))

    h.module.currentPhase should not be SyncPhase.Completed
    h.syncedSince(before) shouldBe empty
    h.anchorAfter(h.recording.writes.size) shouldBe None
  }

  it should "ignore a sample's result when none is in flight" taggedAs UnitTest in {
    val h = new Harness(pathScheme = true)
    h.storeChainBelowThePivot(below = 256, bodiesOfTheLast = 6)
    h.unifiedLayoutAtTheEndOfASync()
    val before = h.recording.writes.size

    h.call(_.onHandoffSampleDone(PathHandoffSample.Result()))

    h.recording.writes.size shouldBe before
    h.module.currentPhase should not be SyncPhase.Completed
  }

  it should "take the heal's marker away in the same write, when no clean decision stands on this root" taggedAs UnitTest in {
    val h = new Harness(pathScheme = true)
    h.storeChainBelowThePivot(below = 256, bodiesOfTheLast = 6)
    h.unifiedLayoutAtTheEndOfASync()
    val appState = h.chain.storagesInstance.storages.appStateStorage
    appState.stampSnapHealInvariant().commitSync()
    h.module.stateRoot = Some(TrieRoot(h.root))
    val before = h.recording.writes.size

    h.handOffFromAPassedSample()

    appState.isSnapHealInvariantPresent() shouldBe false
    h.syncedSince(before) should have size 1
    val handOff = h.syncedSince(before).head
    withClue("the marker stands until the write that hands off: ") {
      new AppStateStorage(h.recording.prefix(handOff)).isSnapHealInvariantPresent() shouldBe true
    }
  }

  // forge and beacon: the hold is its own. Once the headers are at the pivot every tick leaves the header hold, and a
  // hold that leaned on it began again each time (the download pointed at the pivot again, its first line logged).
  it should "ask the chain download for the missing bodies on every tick of the hold, and begin the hold once" taggedAs UnitTest in {
    // the backfill is held back until the state is complete, and the headers are at the pivot: the route on which
    // every tick finds "the headers have reached the pivot" again
    val config = SNAPSyncConfig(deferChainBackfillUntilStateComplete = true)
    SNAPSyncController.chainBackfillDeferredToFinalization(config) shouldBe true
    val h = new Harness(pathScheme = true, config = config)
    val pivotHeader = h.storeChainBelowThePivot(below = 256, bodiesOfTheLast = 5)
    h.unifiedLayoutAtTheEndOfASync()
    h.chain.storagesInstance.storages.appStateStorage.putBackfillBestHeader(pivot).commit()
    h.module.pivotBlock = Some(pivot)
    val sixth = (1 to 5).foldLeft(pivotHeader) { (header, _) =>
      h.chain.blockchainReader.getBlockHeaderByHash(header.parentHash).get
    }

    h.handOffFromAPassedSample()

    h.module.headerHold shouldBe Some(pivotHeader.hash.value)
    val download = h.kit.childInbox[ChainDownloader.Command]("chain-downloader-1")
    download.receiveAll() shouldBe Seq(
      ChainDownloader.Start(pivot),
      ChainDownloader.UpdateTarget(pivot),
      ChainDownloader.Resume,
      ChainDownloader.FetchBodies(Seq(sixth.hash.value))
    )

    (1 to 3).foreach { _ =>
      h.call(_.onHeaderHoldTick(pivot))
      h.module.headerHold shouldBe Some(pivotHeader.hash.value)
      withClue("the bodies again, and nothing that begins a hold: ") {
        download.receiveAll() shouldBe Seq(ChainDownloader.FetchBodies(Seq(sixth.hash.value)))
      }
    }
    h.parent.hasMessages shouldBe false
  }

  it should "start the chain download again for a window that does not fill, and give the sync up the third time" taggedAs UnitTest in {
    // no time between two looks counts as a stall: every tick that finds the same thing missing
    val h = new Harness(
      pathScheme = true,
      config = SNAPSyncConfig(headerHoldStallTimeout = scala.concurrent.duration.Duration.Zero)
    )
    val pivotHeader = h.storeChainBelowThePivot(below = 256, bodiesOfTheLast = 5)
    h.unifiedLayoutAtTheEndOfASync()
    h.module.pivotBlock = Some(pivot)
    h.handOffFromAPassedSample()
    h.module.headerHold shouldBe Some(pivotHeader.hash.value)
    val before = h.recording.writes.size

    h.call(_.onHeaderHoldTick(pivot))
    h.call(_.onHeaderHoldTick(pivot))
    withClue("twice started again, and still held: ") {
      h.module.coordinatorGeneration shouldBe 3L
      h.module.headerHold shouldBe Some(pivotHeader.hash.value)
      h.parent.hasMessages shouldBe false
    }

    h.call(_.onHeaderHoldTick(pivot))

    h.module.headerHold shouldBe None
    h.parent.receiveAll() shouldBe Seq(SyncProtocol.HealingImpossible)
    h.syncedSince(before) shouldBe empty
    h.flagSetAfter(h.recording.writes.size) shouldBe false
  }
