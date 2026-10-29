package com.chipprbots.ethereum.blockchain.sync.snap.actors.healing

import java.util.concurrent.atomic.AtomicLong

import org.apache.pekko.actor.testkit.typed.scaladsl.TestInbox
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.util.ByteString

import scala.collection.mutable
import scala.concurrent.ExecutionContext

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.snap.SNAPRequestTracker
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController
import com.chipprbots.ethereum.blockchain.sync.snap.StateValidator
import com.chipprbots.ethereum.blockchain.sync.snap.StorageScheme
import com.chipprbots.ethereum.blockchain.sync.snap.actors.HealingTrieFixtures
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator.FrontierRebuilt
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator.FrontierWalkFailed
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator.HealingEntry
import com.chipprbots.ethereum.blockchain.sync.snap.controller.StateIngest
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.BfsQueueStorage
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.FlatSlotStorage
import com.chipprbots.ethereum.db.storage.HealingFrontierStorage
import com.chipprbots.ethereum.db.storage.InMemoryBfsQueueStorage
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.db.storage.PathNodeStorage
import com.chipprbots.ethereum.db.storage.SnapStorageDoneStorage
import com.chipprbots.ethereum.db.storage.SnapSyncProgressStorage
import com.chipprbots.ethereum.db.storage.StateStorage
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.BlockchainWriter
import com.chipprbots.ethereum.mpt.HexPrefix
import com.chipprbots.ethereum.mpt.MptNode
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.testing.TestMptStorage

/** The fixture's trie store, counting its batched hash reads, so a test can tell the Hash-scheme read from the Path
  * one.
  */
private class CountingMptStorage(underlying: TestMptStorage) extends MptStorage:
  var multiGets: Int = 0
  def get(nodeId: Array[Byte]): MptNode = underlying.get(nodeId)
  def updateNodesInStorage(newRoot: Option[MptNode], toRemove: Seq[MptNode]): Option[MptNode] =
    underlying.updateNodesInStorage(newRoot, toRemove)
  def persist(): Unit = underlying.persist()
  override def multiGetNodes(hashes: Seq[Array[Byte]]): Seq[Option[MptNode]] =
    multiGets += 1
    super.multiGetNodes(hashes)

/** A BFS queue whose writes fail, to drive the walk's failure path. */
private class FailingBfsQueue extends InMemoryBfsQueueStorage:
  override def enqueueBatch(entries: Seq[(Array[Byte], Seq[Array[Byte]], Boolean)]): Unit =
    throw new IllegalStateException("bfs queue write failed")

/** `FrontierRebuildBfs`'s state, environment, Path read and drain wait as plain fields (spec 016 C-H2). Both
  * dispatchers are `ExecutionContext.parasitic`, so the launcher's Future runs the walk inline, and the walk caps keep
  * it on the serial branch. `readPathVerifiedNode` records each read and serves the node by hash from the trie store;
  * `awaitFrontierDrain` counts its calls. `stateIngest` hands out only `healingFrontier`.
  */
private class StubFrontierRebuildState(
    val mptStorage: CountingMptStorage,
    val bfsQueue: BfsQueueStorage,
    val storageScheme: StorageScheme,
    val prunedEnabled: Boolean,
    frontierStore: Option[HealingFrontierStorage],
    val FrontierBatchSize: Int
) extends FrontierRebuildBfsState
    with TrieNodeHealingEnv
    with PathVerifiedReadApi
    with FrontierDrainApi:

  // FrontierRebuildBfsState
  var stateRoot: ByteString = ByteString(Array.fill[Byte](32)(7))
  var verificationBFSRunning: Boolean = false
  val prunedSubtreeCount: AtomicLong = new AtomicLong(0L)
  val healingWriterEc: ExecutionContext = ExecutionContext.parasitic
  val healingReaderEc: ExecutionContext = ExecutionContext.parasitic
  val HealingVisitedCap: Int = 1000
  val HealingTraversalParallelism: Int = 1
  val HealingMinParallelism: Int = 1
  val HealingReservedCores: Int = 0

  // TrieNodeHealingEnv
  val log: org.slf4j.Logger = org.slf4j.LoggerFactory.getLogger(classOf[FrontierRebuildBfsSpec])
  val asyncLog: org.slf4j.Logger = log
  val prunedHealVerification: Boolean = true
  def networkPeerManager: ActorRef[NetworkPeerManagerActor.Command] =
    throw new UnsupportedOperationException("FrontierRebuildBfs read networkPeerManager")
  def requestTracker: SNAPRequestTracker = throw new UnsupportedOperationException(
    "FrontierRebuildBfs read requestTracker"
  )
  def snapSyncController: ActorRef[SNAPSyncController.Command] =
    throw new UnsupportedOperationException("FrontierRebuildBfs read snapSyncController")
  val batchSize: Int = 32
  val movingRootDeltaHeal: Boolean = false
  val scopedHealMaxPaths: Int = 200_000
  val healedCodeCheck: Boolean = false
  val selfInbox: TestInbox[TrieNodeHealingCoordinator.Command] = TestInbox[TrieNodeHealingCoordinator.Command]()
  val self: ActorRef[TrieNodeHealingCoordinator.Command] = selfInbox.ref
  val frontierPersistenceEnabled: Boolean = false
  val decoupledHealServeRoot: Boolean = false
  val decoupledHealMaxAttemptsNoRefresh: Int = 3
  val frontierHighWater: Int = 100
  val frontierLowWater: Int = 10
  val frontierBackpressureMaxWaitMs: Long = 0L
  val stateIngest: StateIngest = new StateIngest:
    def appState: AppStateStorage = notReached("appState")
    def stateTries: StateStorage = notReached("stateTries")
    def flatSlots: FlatSlotStorage = notReached("flatSlots")
    def pathNodes: Option[PathNodeStorage] = notReached("pathNodes")
    def bytecode: EvmCodeStorage = notReached("bytecode")
    def chainReader: BlockchainReader = notReached("chainReader")
    def chainWriter: BlockchainWriter = notReached("chainWriter")
    def validatorFactory: MptStorage => StateValidator = notReached("validatorFactory")
    def durability: StateIngest.Durability = StateIngest.Durability.Default
    def trieStore(pivot: BigInt): MptStorage = notReached("trieStore")
    def snapProgress: SnapSyncProgressStorage = notReached("snapProgress")
    def storageDone: SnapStorageDoneStorage = notReached("storageDone")
    def healingFrontier: Option[HealingFrontierStorage] = frontierStore
    def bfsQueue: Option[BfsQueueStorage] = notReached("bfsQueue")

  // PathVerifiedReadApi
  val pathReads: mutable.Buffer[(ByteString, Seq[ByteString])] = mutable.Buffer.empty
  def readPathVerifiedNode(hash: ByteString, pathset: Seq[ByteString]): Option[MptNode] =
    pathReads += (hash -> pathset)
    scala.util.Try(mptStorage.get(hash.toArray)).toOption

  // FrontierDrainApi
  var drainCalls: Int = 0
  def awaitFrontierDrain(): Unit = drainCalls += 1

  private def notReached(member: String): Nothing =
    throw new UnsupportedOperationException(s"FrontierRebuildBfs read StateIngest.$member")

/** Spec 016 C-H2 stub test (FR-016 (b)): `FrontierRebuildBfs` mixed into a stub of its state and environment over the
  * deterministic `HealingTrieFixtures` tries. It pins the module's seams: the launcher sets `verificationBFSRunning`
  * and hands the walk's missing-node count to `onComplete`; each missing node is emitted once as `FrontierRebuilt`
  * (with its pathset) behind one drain wait per batch, on the branch and the extension de-dup arms; the scratch queue
  * is empty after the walk; a walk that throws reports `FrontierWalkFailed` and never calls `onComplete`; the spec 005
  * descend-and-stop oracle prunes a recorded subtree only when `prunedEnabled`; several seeds share one visited set;
  * and the per-scheme reads and de-dup keys (Hash: one batched hash read per chunk and one visit per node hash; Path: a
  * path-verified read per entry and one visit per location).
  */
class FrontierRebuildBfsSpec extends AnyFlatSpec with Matchers:

  private val emptyPath: ByteString = ByteString(HexPrefix.encode(Array.empty[Byte], isLeaf = false))

  private def compact(nibbles: Int*): ByteString = ByteString(HexPrefix.encode(nibbles.map(_.toByte).toArray, false))

  private def stub(
      fixture: HealingTrieFixtures.SharedAncestorFixture,
      scheme: StorageScheme = StorageScheme.Hash,
      pruned: Boolean = false,
      store: Option[HealingFrontierStorage] = None,
      queue: BfsQueueStorage = new InMemoryBfsQueueStorage(),
      batchSize: Int = 1000
  ): StubFrontierRebuildState & FrontierRebuildBfs =
    val trie = new CountingMptStorage(fixture.storage)
    new StubFrontierRebuildState(trie, queue, scheme, pruned, store, batchSize) with FrontierRebuildBfs

  private def emitted(s: StubFrontierRebuildState): Seq[Seq[HealingEntry]] =
    s.selfInbox.receiveAll().collect { case FrontierRebuilt(entries, _) => entries }

  "FrontierRebuildBfs" should "emit the missing node behind a shared branch once and hand its count to onComplete" taggedAs UnitTest in {
    val fixture = HealingTrieFixtures.sharedAncestor()
    val s = stub(fixture)
    var completed: Option[Long] = None

    s.verificationBFSRunning shouldBe false
    s.startFrontierBFS(fixture.rootHash, emptyPath, isStor = false, (n: Long) => completed = Some(n))

    s.verificationBFSRunning shouldBe true // set by the launcher; the core's completion arm clears it
    completed shouldBe Some(1L)
    // root -> [0] parentA -> [0] shared -> [2] missing: the first location in level order
    emitted(s) shouldBe Seq(Seq(HealingEntry(Seq(compact(0, 0, 2)), fixture.missingNodeHash)))
    s.drainCalls shouldBe 1
    s.bfsQueue.counter shouldBe 0L
    s.mptStorage.multiGets should be > 0
    s.pathReads shouldBe empty
  }

  it should "emit the missing node behind a shared extension once" taggedAs UnitTest in {
    val fixture = HealingTrieFixtures.sharedAncestor(sharedIsExtension = true)
    val s = stub(fixture)
    var completed: Option[Long] = None

    s.startFrontierBFS(fixture.rootHash, emptyPath, isStor = false, (n: Long) => completed = Some(n))

    completed shouldBe Some(1L)
    // the extension's shared key is the single nibble 4
    emitted(s) shouldBe Seq(Seq(HealingEntry(Seq(compact(0, 0, 4)), fixture.missingNodeHash)))
  }

  it should "emit nothing and wait for no drain over a complete trie" taggedAs UnitTest in {
    val (storage, root) = HealingTrieFixtures.childlessLeafRoot()
    val fixture = HealingTrieFixtures.SharedAncestorFixture(storage, root, ByteString.empty, ByteString.empty)
    val s = stub(fixture)
    var completed: Option[Long] = None

    s.startFrontierBFS(root, emptyPath, isStor = false, (n: Long) => completed = Some(n))

    completed shouldBe Some(0L)
    emitted(s) shouldBe empty
    s.drainCalls shouldBe 0
  }

  it should "report a walk that throws as FrontierWalkFailed and never call onComplete" taggedAs UnitTest in {
    val fixture = HealingTrieFixtures.sharedAncestor()
    val s = stub(fixture, queue = new FailingBfsQueue())
    var completed: Option[Long] = None

    s.startFrontierBFS(fixture.rootHash, emptyPath, isStor = false, (n: Long) => completed = Some(n))

    completed shouldBe None
    s.selfInbox.receiveAll() shouldBe Seq(FrontierWalkFailed)
    s.verificationBFSRunning shouldBe true // left for the core's FrontierWalkFailed arm
  }

  it should "prune a recorded-complete subtree only when pruning is enabled" taggedAs UnitTest in {
    val fixture = HealingTrieFixtures.sharedAncestor()
    val store = new HealingFrontierStorage(EphemDataSource())
    store.markSubtreeComplete(fixture.sharedAncestorHash)

    val on = stub(fixture, pruned = true, store = Some(store))
    var onCompleted: Option[Long] = None
    on.startFrontierBFS(fixture.rootHash, emptyPath, isStor = false, (n: Long) => onCompleted = Some(n))
    onCompleted shouldBe Some(0L)
    emitted(on) shouldBe empty
    on.prunedSubtreeCount.get() shouldBe 1L // de-duped first, so the shared subtree is counted once

    val off = stub(fixture, pruned = false, store = Some(store))
    var offCompleted: Option[Long] = None
    off.startFrontierBFS(fixture.rootHash, emptyPath, isStor = false, (n: Long) => offCompleted = Some(n))
    offCompleted shouldBe Some(1L)
    emitted(off).flatten.map(_.hash) shouldBe Seq(fixture.missingNodeHash)
    off.prunedSubtreeCount.get() shouldBe 0L
  }

  it should "share one visited set across several seeds" taggedAs UnitTest in {
    val fixture = HealingTrieFixtures.sharedAncestor()
    val root = fixture.storage.get(fixture.rootHash.toArray).asInstanceOf[com.chipprbots.ethereum.mpt.BranchNode]
    def child(i: Int): ByteString =
      ByteString(root.children(i).asInstanceOf[com.chipprbots.ethereum.mpt.HashNode].hashNode)
    val s = stub(fixture)
    var completed: Option[Long] = None

    s.startFrontierBFS(
      Seq((child(0), Seq(compact(0)), false), (child(1), Seq(compact(1)), false)),
      (n: Long) => completed = Some(n)
    )

    completed shouldBe Some(1L)
    emitted(s).flatten shouldBe Seq(HealingEntry(Seq(compact(0, 0, 2)), fixture.missingNodeHash))
  }

  it should "tag its batches with the root the walk was launched on (spec 022 review W1)" taggedAs UnitTest in {
    val fixture = HealingTrieFixtures.sharedAncestor()
    def roots(s: StubFrontierRebuildState): Seq[ByteString] =
      s.selfInbox.receiveAll().collect { case FrontierRebuilt(_, walkRoot) => walkRoot }

    // A root walk carries its seed, whatever the coordinator's root is (a restart rebuild walks the root it was given).
    val rootWalk = stub(fixture)
    rootWalk.stateRoot = ByteString(Array.fill[Byte](32)(9))
    rootWalk.startFrontierBFS(fixture.rootHash, emptyPath, isStor = false, (_: Long) => ())
    roots(rootWalk) shouldBe Seq(fixture.rootHash)

    // A subtree walk (scoped verification) carries the walk root at launch.
    val root = fixture.storage.get(fixture.rootHash.toArray).asInstanceOf[com.chipprbots.ethereum.mpt.BranchNode]
    val seed = ByteString(root.children(0).asInstanceOf[com.chipprbots.ethereum.mpt.HashNode].hashNode)
    val subtreeWalk = stub(fixture)
    val atLaunch = ByteString(Array.fill[Byte](32)(5))
    subtreeWalk.stateRoot = atLaunch
    subtreeWalk.startFrontierBFS(Seq((seed, Seq(compact(0)), false)), (_: Long) => ())
    roots(subtreeWalk) shouldBe Seq(atLaunch)
  }

  it should "read by path and de-dup by location under the Path scheme" taggedAs UnitTest in {
    val fixture = HealingTrieFixtures.sharedAncestor()
    val s = stub(fixture, scheme = StorageScheme.Path, batchSize = 1)
    var completed: Option[Long] = None

    s.startFrontierBFS(fixture.rootHash, emptyPath, isStor = false, (n: Long) => completed = Some(n))

    // the shared node sits at two locations, so each is walked and its missing child found at each
    completed shouldBe Some(2L)
    emitted(s) shouldBe Seq(
      Seq(HealingEntry(Seq(compact(0, 0, 2)), fixture.missingNodeHash)),
      Seq(HealingEntry(Seq(compact(1, 1, 2)), fixture.missingNodeHash))
    )
    s.drainCalls shouldBe 2 // one per emitted batch (batch size 1)
    s.mptStorage.multiGets shouldBe 0
    s.pathReads.head shouldBe (fixture.rootHash -> Seq(emptyPath))
    (s.pathReads.map(_._2) should contain).allOf(Seq(compact(0, 0)), Seq(compact(1, 1)))
  }
