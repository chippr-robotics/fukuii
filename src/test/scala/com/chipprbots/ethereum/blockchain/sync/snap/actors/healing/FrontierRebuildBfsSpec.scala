package com.chipprbots.ethereum.blockchain.sync.snap.actors.healing

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
import com.chipprbots.ethereum.blockchain.sync.snap.heal.WalkLease
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
    frontierStore: Option[HealingFrontierStorage],
    val FrontierBatchSize: Int
) extends FrontierRebuildBfsState
    with TrieNodeHealingEnv
    with PathVerifiedReadApi
    with FrontierDrainApi:

  // FrontierRebuildBfsState
  var stateRoot: ByteString = ByteString(Array.fill[Byte](32)(7))
  var verificationBFSRunning: Boolean = false
  var currentWalk: Option[Long] = None
  val walkSkips: java.util.concurrent.atomic.AtomicLong = new java.util.concurrent.atomic.AtomicLong(0L)
  val healingWriterEc: ExecutionContext = ExecutionContext.parasitic
  val healingReaderEc: ExecutionContext = ExecutionContext.parasitic
  val HealingVisitedCap: Int = 1000
  val HealingTraversalParallelism: Int = 1
  val HealingMinParallelism: Int = 1
  val HealingReservedCores: Int = 0

  // TrieNodeHealingEnv
  val log: org.slf4j.Logger = org.slf4j.LoggerFactory.getLogger(classOf[FrontierRebuildBfsSpec])
  val asyncLog: org.slf4j.Logger = log
  def networkPeerManager: ActorRef[NetworkPeerManagerActor.Command] =
    throw new UnsupportedOperationException("FrontierRebuildBfs read networkPeerManager")
  def requestTracker: SNAPRequestTracker = throw new UnsupportedOperationException(
    "FrontierRebuildBfs read requestTracker"
  )
  def snapSyncController: ActorRef[SNAPSyncController.Command] =
    throw new UnsupportedOperationException("FrontierRebuildBfs read snapSyncController")
  val batchSize: Int = 32
  val movingRootDeltaHeal: Boolean = false
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
  def awaitFrontierDrain(cancelled: () => Boolean): Unit = drainCalls += 1

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
      store: Option[HealingFrontierStorage] = None,
      queue: BfsQueueStorage = new InMemoryBfsQueueStorage(),
      batchSize: Int = 1000
  ): StubFrontierRebuildState & FrontierRebuildBfs =
    val trie = new CountingMptStorage(fixture.storage)
    new StubFrontierRebuildState(trie, queue, scheme, store, batchSize) with FrontierRebuildBfs

  private def emitted(s: StubFrontierRebuildState): Seq[Seq[HealingEntry]] =
    s.selfInbox.receiveAll().collect { case FrontierRebuilt(entries, _) => entries }

  "FrontierRebuildBfs" should "emit the missing node behind a shared branch once and hand its count to onComplete" taggedAs UnitTest in {
    val fixture = HealingTrieFixtures.sharedAncestor()
    val s = stub(fixture)
    var completed: Option[Long] = None

    s.verificationBFSRunning shouldBe false
    s.startFrontierBFS(fixture.rootHash, emptyPath, isStor = false, (_: Long, n: Long) => completed = Some(n))

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

    s.startFrontierBFS(fixture.rootHash, emptyPath, isStor = false, (_: Long, n: Long) => completed = Some(n))

    completed shouldBe Some(1L)
    // the extension's shared key is the single nibble 4
    emitted(s) shouldBe Seq(Seq(HealingEntry(Seq(compact(0, 0, 4)), fixture.missingNodeHash)))
  }

  it should "emit nothing and wait for no drain over a complete trie" taggedAs UnitTest in {
    val (storage, root) = HealingTrieFixtures.childlessLeafRoot()
    val fixture = HealingTrieFixtures.SharedAncestorFixture(storage, root, ByteString.empty, ByteString.empty)
    val s = stub(fixture)
    var completed: Option[Long] = None

    s.startFrontierBFS(root, emptyPath, isStor = false, (_: Long, n: Long) => completed = Some(n))

    completed shouldBe Some(0L)
    emitted(s) shouldBe empty
    s.drainCalls shouldBe 0
  }

  it should "report a walk that throws as FrontierWalkFailed and never call onComplete" taggedAs UnitTest in {
    val fixture = HealingTrieFixtures.sharedAncestor()
    val s = stub(fixture, queue = new FailingBfsQueue())
    var completed: Option[Long] = None

    s.startFrontierBFS(fixture.rootHash, emptyPath, isStor = false, (_: Long, n: Long) => completed = Some(n))

    completed shouldBe None
    s.selfInbox.receiveAll() shouldBe Seq(FrontierWalkFailed(s.currentWalk.get))
    s.verificationBFSRunning shouldBe true // left for the core's FrontierWalkFailed arm
  }

  // spec 022 D6, T043: this is what makes the walk a repair pass. An older binary's records say nothing it can trust.
  it should "descend a present subtree an older binary recorded as complete, and find the hole in it" taggedAs UnitTest in {
    val fixture = HealingTrieFixtures.sharedAncestor()
    val store = new HealingFrontierStorage(EphemDataSource())
    store.markSubtreeComplete(fixture.sharedAncestorHash)
    store.markSubtreeComplete(fixture.rootHash)

    val s = stub(fixture, store = Some(store))
    var completed: Option[Long] = None
    s.startFrontierBFS(fixture.rootHash, emptyPath, isStor = false, (_: Long, n: Long) => completed = Some(n))
    completed shouldBe Some(1L)
    emitted(s).flatten.map(_.hash) shouldBe Seq(fixture.missingNodeHash)
  }

  // ---- the storage descent, on Hash (spec 022 review forge F3): no other test pins it now the oracle's are gone --------

  private def packed(nibbles: Array[Byte]): ByteString =
    ByteString(nibbles.grouped(2).map(g => ((g(0) << 4) | g(1)).toByte).toArray)

  private def accountLeaf(key: Array[Byte], storageRoot: Option[ByteString]): LeafNode =
    val account = storageRoot.fold(Account(nonce = UInt256(1L)))(r =>
      Account(nonce = UInt256(1L), storageRoot = com.chipprbots.ethereum.domain.TrieRoot(r))
    )
    LeafNode(ByteString(key), ByteString(Account.accountSerializer.toBytes(account)))

  private def branchOver(slots: (Int, Array[Byte])*): BranchNode =
    val children = Array.fill[MptNode](16)(NullNode)
    slots.foreach { case (i, hash) => children(i) = HashNode(hash) }
    BranchNode(children, None)

  private def rooted(storage: TestMptStorage, root: MptNode): HealingTrieFixtures.SharedAncestorFixture =
    HealingTrieFixtures.SharedAncestorFixture(storage, ByteString(root.hash), ByteString.empty, ByteString.empty)

  private def absentNode(seed: String): Array[Byte] = kec256(ByteString(seed).toArray)

  it should "follow an account leaf over a present storage root over a branch to the node absent below, under Hash" taggedAs UnitTest in {
    val storage = new TestMptStorage()
    val absent = absentNode("s022/forge-f3/absent")
    val inner = branchOver(5 -> absent)
    val storageRoot = branchOver(3 -> inner.hash)
    val accountNibbles = Array.fill[Byte](64)(7)
    val leaf = accountLeaf(accountNibbles, Some(ByteString(storageRoot.hash)))
    Seq[MptNode](inner, storageRoot, leaf).foreach(storage.putNode)
    val fixture = rooted(storage, leaf)
    val s = stub(fixture)
    var completed: Option[Long] = None

    s.startFrontierBFS(fixture.rootHash, emptyPath, isStor = false, (_: Long, n: Long) => completed = Some(n))

    completed shouldBe Some(1L)
    emitted(s) shouldBe Seq(Seq(HealingEntry(Seq(packed(accountNibbles), compact(3, 5)), ByteString(absent))))
    s.walkSkips.get() shouldBe 0L
  }

  it should "emit a storage root two accounts share once, under Hash" taggedAs UnitTest in {
    val storage = new TestMptStorage()
    val sharedRoot = absentNode("s022/forge-f3/shared-root") // absent: a second visit would emit it a second time
    val first = accountLeaf(Array.fill[Byte](63)(1), Some(ByteString(sharedRoot)))
    val second = accountLeaf(Array.fill[Byte](63)(2), Some(ByteString(sharedRoot)))
    val root = branchOver(0 -> first.hash, 1 -> second.hash)
    Seq[MptNode](first, second, root).foreach(storage.putNode)
    val fixture = rooted(storage, root)
    val s = stub(fixture)
    var completed: Option[Long] = None

    s.startFrontierBFS(fixture.rootHash, emptyPath, isStor = false, (_: Long, n: Long) => completed = Some(n))

    completed shouldBe Some(1L)
    emitted(s).flatten.map(_.hash) shouldBe Seq(ByteString(sharedRoot))
  }

  it should "not descend an account's empty storage root, which no store holds, under Hash" taggedAs UnitTest in {
    val storage = new TestMptStorage()
    val leaf = accountLeaf(Array.fill[Byte](64)(9), None)
    storage.putNode(leaf)
    val fixture = rooted(storage, leaf)
    val s = stub(fixture)
    var completed: Option[Long] = None

    s.startFrontierBFS(fixture.rootHash, emptyPath, isStor = false, (_: Long, n: Long) => completed = Some(n))

    completed shouldBe Some(0L)
    emitted(s) shouldBe empty
    s.walkSkips.get() shouldBe 0L
  }

  // ---- a walk that skipped something proves nothing (spec 022 review beacon 2) ------------------------------------------

  /** The walk's end, and the WARN lines it logged, over a trie rooted at `root`. */
  private def walkOf(storage: TestMptStorage, root: ByteString): (StubFrontierRebuildState, Option[Long], Seq[String]) =
    val s = stub(HealingTrieFixtures.SharedAncestorFixture(storage, root, ByteString.empty, ByteString.empty))
    val logger = s.log.asInstanceOf[ch.qos.logback.classic.Logger]
    val appender = new ch.qos.logback.core.read.ListAppender[ch.qos.logback.classic.spi.ILoggingEvent]
    appender.start()
    val levelBefore = logger.getLevel
    logger.setLevel(ch.qos.logback.classic.Level.WARN)
    logger.addAppender(appender)
    var completed: Option[Long] = None
    try s.startFrontierBFS(root, emptyPath, isStor = false, (_: Long, n: Long) => completed = Some(n))
    finally
      logger.detachAppender(appender)
      logger.setLevel(levelBefore)
    val warnings = appender.list.toArray.toSeq.collect {
      case e: ch.qos.logback.classic.spi.ILoggingEvent if e.getLevel == ch.qos.logback.classic.Level.WARN =>
        e.getFormattedMessage
    }
    (s, completed, warnings)

  it should "count an account leaf it cannot read as a skip, and say so" taggedAs UnitTest in {
    val storage = new TestMptStorage()
    val unreadable = LeafNode(ByteString(Array.fill[Byte](64)(4)), ByteString(Array[Byte](0x01, 0x02, 0x03)))
    storage.putNode(unreadable)
    val (s, completed, warnings) = walkOf(storage, ByteString(unreadable.hash))
    completed shouldBe Some(0L)
    s.walkSkips.get() shouldBe 1L
    warnings.count(_.contains("could not follow 1")) shouldBe 1
  }

  it should "count a storage root it has no account path for as a skip" taggedAs UnitTest in {
    val storage = new TestMptStorage()
    val shortPath = accountLeaf(Array.fill[Byte](10)(4), Some(ByteString(branchOver(1 -> absentNode("x")).hash)))
    storage.putNode(shortPath)
    val (s, _, warnings) = walkOf(storage, ByteString(shortPath.hash))
    s.walkSkips.get() shouldBe 1L
    warnings.count(_.contains("could not follow 1")) shouldBe 1
  }

  it should "count a node it cannot traverse as a skip, and go on" taggedAs UnitTest in {
    val rootHash = ByteString(absentNode("s022/beacon-2/untraversable"))
    val storage = new TestMptStorage:
      // An extension with no shared key: following its child throws inside the walk's per-node catch.
      override def get(key: Array[Byte]): MptNode =
        if ByteString(key) == rootHash then ExtensionNode(null, HashNode(absentNode("s022/beacon-2/below")))
        else super.get(key)
    val (s, completed, warnings) = walkOf(storage, rootHash)
    completed shouldBe Some(0L)
    s.walkSkips.get() shouldBe 1L
    warnings.count(_.contains("could not follow 1")) shouldBe 1
  }

  it should "start each walk's count from nothing" taggedAs UnitTest in {
    val storage = new TestMptStorage()
    val unreadable = LeafNode(ByteString(Array.fill[Byte](64)(4)), ByteString(Array[Byte](0x01, 0x02, 0x03)))
    val readable = accountLeaf(Array.fill[Byte](64)(5), None)
    Seq[MptNode](unreadable, readable).foreach(storage.putNode)
    val s = stub(
      HealingTrieFixtures.SharedAncestorFixture(
        storage,
        ByteString(unreadable.hash),
        ByteString.empty,
        ByteString.empty
      )
    )
    s.startFrontierBFS(ByteString(unreadable.hash), emptyPath, isStor = false, (_: Long, _: Long) => ())
    s.walkSkips.get() shouldBe 1L
    s.verificationBFSRunning = false
    s.startFrontierBFS(ByteString(readable.hash), emptyPath, isStor = false, (_: Long, _: Long) => ())
    s.walkSkips.get() shouldBe 0L
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
      (_: Long, n: Long) => completed = Some(n)
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
    rootWalk.startFrontierBFS(fixture.rootHash, emptyPath, isStor = false, (_: Long, _: Long) => ())
    roots(rootWalk) shouldBe Seq(fixture.rootHash)

    // A subtree walk (scoped verification) carries the walk root at launch.
    val root = fixture.storage.get(fixture.rootHash.toArray).asInstanceOf[com.chipprbots.ethereum.mpt.BranchNode]
    val seed = ByteString(root.children(0).asInstanceOf[com.chipprbots.ethereum.mpt.HashNode].hashNode)
    val subtreeWalk = stub(fixture)
    val atLaunch = ByteString(Array.fill[Byte](32)(5))
    subtreeWalk.stateRoot = atLaunch
    subtreeWalk.startFrontierBFS(Seq((seed, Seq(compact(0)), false)), (_: Long, _: Long) => ())
    roots(subtreeWalk) shouldBe Seq(atLaunch)
  }

  it should "start no walk while its own runs or another walk holds the queue, and give the queue back as a walk ends (spec 022 fix round 4)" taggedAs UnitTest in {
    val fixture = HealingTrieFixtures.sharedAncestor()
    val queue = new InMemoryBfsQueueStorage()
    val lease = WalkLease.of(queue)
    val s = stub(fixture, queue = queue)
    var ends: Seq[(Long, Long)] = Nil
    def launch(): Unit =
      s.startFrontierBFS(fixture.rootHash, emptyPath, isStor = false, (walk: Long, n: Long) => ends :+= (walk -> n))

    // A replaced incarnation's walk holds the queue: nothing starts, nothing is set or sent.
    val other = lease.tryAcquire().get
    launch()
    ends shouldBe empty
    s.verificationBFSRunning shouldBe false
    s.currentWalk shouldBe None
    s.selfInbox.hasMessages shouldBe false

    // Once it has ended, the walk starts, holds the queue while it runs, and gives it back before reporting its end.
    lease.release(other)
    launch()
    s.verificationBFSRunning shouldBe true
    s.currentWalk shouldBe defined
    ends shouldBe Seq(s.currentWalk.get -> 1L)
    lease.held shouldBe false

    // Its own walk is flagged running until the core takes its end: no second walk.
    s.selfInbox.receiveAll()
    launch()
    ends should have size 1
    s.selfInbox.hasMessages shouldBe false
  }

  it should "give the queue back when a walk throws, and tag its failure with the walk's token" taggedAs UnitTest in {
    val fixture = HealingTrieFixtures.sharedAncestor()
    val queue = new FailingBfsQueue()
    val s = stub(fixture, queue = queue)

    s.startFrontierBFS(fixture.rootHash, emptyPath, isStor = false, (_: Long, _: Long) => fail("onComplete called"))

    s.selfInbox.receiveAll() shouldBe Seq(FrontierWalkFailed(s.currentWalk.get))
    WalkLease.of(queue).held shouldBe false
  }

  it should "read by path and de-dup by location under the Path scheme" taggedAs UnitTest in {
    val fixture = HealingTrieFixtures.sharedAncestor()
    val s = stub(fixture, scheme = StorageScheme.Path, batchSize = 1)
    var completed: Option[Long] = None

    s.startFrontierBFS(fixture.rootHash, emptyPath, isStor = false, (_: Long, n: Long) => completed = Some(n))

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
