package com.chipprbots.ethereum.blockchain.sync.snap.actors.healing

import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

import org.apache.pekko.actor.testkit.typed.scaladsl.TestInbox
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.util.ByteString

import scala.collection.mutable
import scala.concurrent.ExecutionContext

import org.scalatest.LoneElement
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.snap.SNAPRequestTracker
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController
import com.chipprbots.ethereum.blockchain.sync.snap.StateValidator
import com.chipprbots.ethereum.blockchain.sync.snap.StorageScheme
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator.ActiveRequest
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator.FlushComplete
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator.FlushFailed
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator.HealingEntry
import com.chipprbots.ethereum.blockchain.sync.snap.controller.StateIngest
import com.chipprbots.ethereum.blockchain.sync.snap.heal.FlushBuffer
import com.chipprbots.ethereum.blockchain.sync.snap.heal.FlushTicket
import com.chipprbots.ethereum.blockchain.sync.snap.heal.HealRequest
import com.chipprbots.ethereum.blockchain.sync.snap.heal.HealTaskQueue
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.BfsQueueStorage
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.FlatSlotStorage
import com.chipprbots.ethereum.db.storage.HealingFrontierStorage
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.db.storage.PathNodeStorage
import com.chipprbots.ethereum.db.storage.SnapStorageDoneStorage
import com.chipprbots.ethereum.db.storage.SnapSyncProgressStorage
import com.chipprbots.ethereum.db.storage.StateStorage
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.BlockchainWriter
import com.chipprbots.ethereum.mpt.MptNode
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerActor
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.testing.Tags.*

/** An `MptStorage` that records the healed-node writes in order, and at each `persist()` notes whether the frontier
  * store already holds a subtree-complete record for a node of that write (it must not: spec 005 D3,
  * record-after-persist).
  */
final private class RecordingMptStorage(frontier: => Option[HealingFrontierStorage]) extends MptStorage:
  val events: mutable.ArrayBuffer[String] = mutable.ArrayBuffer.empty
  val stored: mutable.Map[ByteString, Array[Byte]] = mutable.Map.empty
  private var lastWrite: Seq[ByteString] = Nil
  var recordSeenAtPersist: Boolean = false
  // The next `failWrites` node writes throw before storing anything (HealFlushFailureSpec, #1549).
  @volatile var failWrites: Int = 0
  // Node writes under way now, and the most ever under way at once (spec 022 review vault V1, hunter W4: one writer).
  val active: AtomicInteger = new AtomicInteger(0)
  val maxActive: AtomicInteger = new AtomicInteger(0)
  // Called at the start of every node write, with how many are under way (this one included).
  @volatile var onStore: Int => Unit = _ => ()

  def get(nodeId: Array[Byte]): MptNode = throw new UnsupportedOperationException("FrontierPersistence reads no node")
  def updateNodesInStorage(newRoot: Option[MptNode], toRemove: Seq[MptNode]): Option[MptNode] =
    throw new UnsupportedOperationException("FrontierPersistence writes raw nodes only")
  override def storeRawNodes(nodes: Seq[(ByteString, Array[Byte])]): Unit =
    val now = active.incrementAndGet()
    maxActive.accumulateAndGet(now, math.max)
    try
      onStore(now)
      if failWrites > 0 then
        failWrites -= 1
        events.synchronized(events += "store-failed")
        throw new RuntimeException("injected node write failure")
      stored.synchronized(nodes.foreach { case (h, b) => stored(h) = b })
      lastWrite = nodes.map(_._1)
      events.synchronized(events += s"store:${nodes.size}")
    finally active.decrementAndGet()
  def persist(): Unit =
    recordSeenAtPersist ||= frontier.exists(store => lastWrite.exists(store.isSubtreeComplete))
    events.synchronized(events += "persist")

/** An execution context that runs nothing until the test says so ([[runAll]]): a flush's Future, and its completion
  * callback, wait in its queue, so a test decides exactly when an asynchronous write happens.
  */
final private[snap] class ManualExecutionContext extends ExecutionContext:
  private val queue = mutable.Queue.empty[Runnable]
  def execute(runnable: Runnable): Unit = queue.synchronized(queue.enqueue(runnable))
  def reportFailure(cause: Throwable): Unit = throw cause
  def pending: Int = queue.synchronized(queue.size)
  def runAll(): Unit =
    var next = queue.synchronized(Option.when(queue.nonEmpty)(queue.dequeue()))
    while next.isDefined do
      next.foreach(_.run())
      next = queue.synchronized(Option.when(queue.nonEmpty)(queue.dequeue()))

  /** Run only the runnable queued first. A test whose actor queues more work as it reacts runs one at a time and lets
    * the actor settle in between ([[runAll]] would race it).
    */
  def runNext(): Unit =
    queue.synchronized(Option.when(queue.nonEmpty)(queue.dequeue())).foreach(_.run())

  /** Run only the runnable queued last (a test that must run a flush queued behind a walk before that walk). */
  def runNewest(): Unit =
    queue.synchronized(Option.when(queue.nonEmpty)(queue.removeLast())).foreach(_.run())

/** The coordinator state and environment `FrontierPersistence` reads (spec 016 C-H1), as plain fields over a recording
  * trie store and an in-memory frontier store. `stateIngest` hands out only `healingFrontier`.
  */
private class StubFrontierPersistenceState(
    val frontierStore: Option[HealingFrontierStorage],
    val frontierPersistenceEnabled: Boolean,
    val prunedEnabled: Boolean,
    val healingWriterEc: ExecutionContext,
    val frontierHighWater: Int = 100,
    val frontierLowWater: Int = 10,
    val frontierBackpressureMaxWaitMs: Long = 0L,
    val decoupledHealServeRoot: Boolean = false,
    val decoupledHealMaxAttemptsNoRefresh: Int = 3
) extends FrontierPersistenceState
    with TrieNodeHealingEnv:

  // TrieNodeHealingEnv
  val log: org.slf4j.Logger = org.slf4j.LoggerFactory.getLogger(classOf[FrontierPersistenceSpec])
  val asyncLog: org.slf4j.Logger = log
  val storageScheme: StorageScheme = StorageScheme.Hash
  val prunedHealVerification: Boolean = true
  def networkPeerManager: ActorRef[NetworkPeerManagerActor.Command] =
    throw new UnsupportedOperationException("FrontierPersistence read networkPeerManager")
  def requestTracker: SNAPRequestTracker = throw new UnsupportedOperationException(
    "FrontierPersistence read requestTracker"
  )
  def snapSyncController: ActorRef[SNAPSyncController.Command] =
    throw new UnsupportedOperationException("FrontierPersistence read snapSyncController")
  val batchSize: Int = 32
  val movingRootDeltaHeal: Boolean = false
  val scopedHealMaxPaths: Int = 200_000
  val healedCodeCheck: Boolean = false
  val selfInbox: TestInbox[TrieNodeHealingCoordinator.Command] = TestInbox[TrieNodeHealingCoordinator.Command]()
  val self: ActorRef[TrieNodeHealingCoordinator.Command] = selfInbox.ref
  val mptStorage: RecordingMptStorage = new RecordingMptStorage(frontierStore)
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

  // FrontierPersistenceState
  val pendingTasks: HealTaskQueue[HealingEntry] =
    new HealTaskQueue[HealingEntry](e => HealRequest.depthOf(e.pathset), _.hash, _ => false)
  val activeRequests: mutable.Map[BigInt, ActiveRequest] = mutable.Map.empty
  val pendingBackpressure: AtomicInteger = new AtomicInteger(0)
  var crossRootHealCount: Long = 0L
  val healAttempts: HealAttemptMap = new HealAttemptMap(() => decoupledHealMaxAttemptsNoRefresh)
  val rawNodeBuffer: FlushBuffer = new FlushBuffer()
  var flushInFlight: Option[FlushTicket] = None
  val pendingSubtreeRecords: mutable.Set[ByteString] = mutable.Set.empty
  var totalNodesHealed: Int = 0

  private def notReached(member: String): Nothing =
    throw new UnsupportedOperationException(s"FrontierPersistence read StateIngest.$member")

/** Spec 016 C-H1 stub test (FR-016 (b)): `FrontierPersistence` mixed into a stub of its state and environment, with an
  * in-memory frontier store (`EphemDataSource`) and a recording trie store. It pins the Layer-2 mirror (persist on
  * enqueue, unpersist after a durable flush, clear on invalidation, each gated by `frontierPersistenceEnabled`), the
  * healed-node flush's record-after-persist (no spec 005 subtree-complete record at `persist()`), the asynchronous
  * flush's message and its single-flight guard, and the gauge mirror. The flush runs on `ExecutionContext.parasitic`,
  * so its Future completes inline. The unpersist-after-persist order and the drain wait's two paths are pinned in
  * `FrontierPersistenceOrderSpec` (review follow-up); the last case here only calls the drain wait.
  */
class FrontierPersistenceSpec extends AnyFlatSpec with Matchers with LoneElement:

  private def node(i: Int): (ByteString, Array[Byte]) =
    val bytes = Array.fill[Byte](8)(i.toByte)
    kec256(ByteString(bytes)) -> bytes

  private def entry(i: Int): HealingEntry = HealingEntry(Seq(ByteString(Array(i.toByte))), node(i)._1)

  private def stub(
      persistence: Boolean = true,
      pruned: Boolean = true,
      store: Boolean = true
  ): StubFrontierPersistenceState & FrontierPersistence =
    val frontier = Option.when(store)(new HealingFrontierStorage(EphemDataSource()))
    new StubFrontierPersistenceState(frontier, persistence, pruned, ExecutionContext.parasitic) with FrontierPersistence

  /** The frontier entries (of `entry(1)`..`entry(9)`) the store holds, read by key. */
  private def persisted(s: StubFrontierPersistenceState): Set[ByteString] =
    (1 to 9).map(entry(_).hash).filter(h => s.frontierStore.exists(_.get(h).isDefined)).toSet

  "FrontierPersistence" should "mirror new frontier entries to the store only when persistence is on" taggedAs UnitTest in {
    val on = stub()
    on.persistFrontier(Seq(entry(1), entry(2)))
    persisted(on) shouldBe Set(entry(1).hash, entry(2).hash)
    on.frontierStore.get.get(entry(1).hash) shouldBe Some(entry(1).pathset)

    val off = stub(persistence = false)
    off.persistFrontier(Seq(entry(1)))
    persisted(off) shouldBe empty

    // no store: nothing to write to, and nothing throws
    stub(store = false).persistFrontier(Seq(entry(1)))
  }

  it should "flush synchronously: nodes, persist, subtree records, then the frontier unpersist" taggedAs UnitTest in {
    val s = stub()
    s.persistFrontier(Seq(entry(1), entry(2), entry(3)))
    s.rawNodeBuffer ++= Seq(node(1), node(2))
    s.pendingSubtreeRecords ++= Set(node(1)._1, node(9)._1)

    s.flushRawNodesSync()

    s.mptStorage.events.toSeq shouldBe Seq("store:2", "persist")
    s.mptStorage.stored.keySet shouldBe Set(node(1)._1, node(2)._1)
    s.mptStorage.recordSeenAtPersist shouldBe false
    s.frontierStore.get.isSubtreeComplete(node(1)._1) shouldBe true
    s.frontierStore.get.isSubtreeComplete(node(2)._1) shouldBe false
    // only the staged record this flush made durable is taken; the other stays staged
    s.pendingSubtreeRecords.toSet shouldBe Set(node(9)._1)
    // the healed nodes leave the persisted frontier; the unhealed entry stays
    persisted(s) shouldBe Set(entry(3).hash)
    s.rawNodeBuffer shouldBe empty
  }

  it should "write no subtree record when pruned verification is off, and do nothing on an empty buffer" taggedAs UnitTest in {
    val s = stub(pruned = false)
    s.rawNodeBuffer += node(1)
    s.pendingSubtreeRecords += node(1)._1
    s.flushRawNodesSync()
    s.frontierStore.get.isSubtreeComplete(node(1)._1) shouldBe false
    s.pendingSubtreeRecords shouldBe empty

    val idle = stub()
    idle.flushRawNodesSync()
    idle.mptStorage.events shouldBe empty
  }

  it should "flush asynchronously once at a time and report FlushComplete to self" taggedAs UnitTest in {
    val s = stub()
    s.persistFrontier(Seq(entry(1), entry(2)))
    s.rawNodeBuffer ++= Seq(node(1), node(2))
    s.pendingSubtreeRecords += node(2)._1

    s.flushRawNodesAsync()

    s.flushing shouldBe true // cleared when the core's FlushComplete arm calls flushCompleted
    // spec 022 T026 (#1549): the nodes stay buffered until the write is reported done.
    s.rawNodeBuffer.map(_._1).toSeq shouldBe Seq(node(1)._1, node(2)._1)
    val completion = s.selfInbox.receiveAll().loneElement
    completion should matchPattern { case FlushComplete(_, 2) => }
    s.mptStorage.events.toSeq shouldBe Seq("store:2", "persist")
    s.mptStorage.recordSeenAtPersist shouldBe false
    s.frontierStore.get.isSubtreeComplete(node(2)._1) shouldBe true
    persisted(s) shouldBe empty

    // while a flush is in flight, a second one does not start
    s.rawNodeBuffer += node(3)
    s.flushRawNodesAsync()
    s.selfInbox.hasMessages shouldBe false

    // the write returned: exactly what it wrote leaves the buffer, and its staged record is unstaged
    deliver(s, completion)
    s.flushing shouldBe false
    s.rawNodeBuffer.map(_._1).toSeq shouldBe Seq(node(3)._1)
    s.pendingSubtreeRecords shouldBe empty
  }

  it should "ignore the completion of an asynchronous flush a synchronous flush overtook (spec 022 T026)" taggedAs UnitTest in {
    val s = stub()
    s.rawNodeBuffer ++= Seq(node(1), node(2))
    s.flushRawNodesAsync() // completes inline; its FlushComplete is not processed yet
    s.rawNodeBuffer += node(3)
    s.flushRawNodesSync() shouldBe true // writes all three and releases them
    s.rawNodeBuffer += node(4)
    s.selfInbox.receiveAll().foreach(deliver(s, _)) // the async flush's late completion: it must not drop node(4)
    s.rawNodeBuffer.map(_._1).toSeq shouldBe Seq(node(4)._1)
    s.flushing shouldBe false
  }

  it should "clear the outstanding pending and in-flight entries and the completeness marker" taggedAs UnitTest in {
    val s = stub()
    s.persistFrontier(Seq(entry(1), entry(2), entry(3)))
    s.frontierStore.get.markComplete()
    s.pendingTasks += entry(1)
    val peer = Peer(
      id = PeerId("c-h1-peer"),
      remoteAddress = new InetSocketAddress("127.0.0.1", 30303),
      ref = TestInbox[PeerActor.Command]().ref,
      incomingConnection = false
    )
    s.activeRequests.put(BigInt(7), ActiveRequest(Seq(entry(2)), peer, BigInt(1024)))

    s.clearPersistedFrontier()

    persisted(s) shouldBe Set(entry(3).hash)
    s.frontierStore.get.isComplete shouldBe false

    val off = stub(persistence = false)
    off.frontierStore.get.markComplete()
    off.clearPersistedFrontier()
    off.frontierStore.get.isComplete shouldBe true
  }

  it should "mirror the backlog into the backpressure counter and return from the drain wait" taggedAs UnitTest in {
    val s = stub()
    s.pendingTasks ++= Seq(entry(1), entry(2), entry(3))
    s.emitHealingFrontierGauges()
    s.pendingBackpressure.get() shouldBe 3

    // below the high-water mark the wait returns at once; above it, the 0 ms safety timeout returns it too
    s.awaitFrontierDrain()
    s.pendingBackpressure.set(1000)
    s.awaitFrontierDrain()
    s.pendingBackpressure.get() shouldBe 1000
  }

  // ---- spec 022 slice 2 review: one writer, and only this coordinator's own flush releases nodes ------------------

  /** Hand `message`, one of the flush messages a coordinator sends itself, to `s`, as the core's arm does. */
  private def deliver(s: StubFrontierPersistenceState & FrontierPersistence, message: Any): Unit =
    message match
      case FlushComplete(token, count) => s.flushCompleted(token, count)
      case FlushFailed(token, cause)   => s.flushFailed(token, cause)
      case other                       => fail(s"not a flush message: $other")

  it should "ignore the completion or failure of a flush that a previous coordinator instance started (forge C1)" taggedAs UnitTest in {
    // A supervisor restart builds a new coordinator on the same ActorRef: the previous instance's late flush messages
    // reach the new one. Here the previous instance wrote two nodes, and another of its flushes failed.
    val previous = stub()
    previous.rawNodeBuffer ++= Seq(node(7), node(8))
    previous.flushRawNodesAsync()
    val staleComplete = previous.selfInbox.receiveAll().loneElement
    val previousFailing = stub()
    previousFailing.rawNodeBuffer += node(9)
    previousFailing.mptStorage.failWrites = 1
    previousFailing.flushRawNodesAsync()
    val staleFailed = previousFailing.selfInbox.receiveAll().loneElement

    // The new instance: committed nodes buffered, and its own flush in flight (its Future has not run yet).
    val ec = new ManualExecutionContext
    val fresh = new StubFrontierPersistenceState(
      Some(new HealingFrontierStorage(EphemDataSource())),
      frontierPersistenceEnabled = true,
      prunedEnabled = true,
      healingWriterEc = ec
    ) with FrontierPersistence
    fresh.rawNodeBuffer ++= Seq(node(1), node(2), node(3))
    fresh.flushRawNodesAsync()
    fresh.rawNodeBuffer += node(4)

    deliver(fresh, staleComplete)
    deliver(fresh, staleFailed)
    withClue("a previous instance's messages release nothing: ") {
      fresh.rawNodeBuffer.map(_._1).toSeq shouldBe Seq(1, 2, 3, 4).map(node(_)._1)
    }
    withClue("and leave this instance's flush in flight: ")(fresh.flushing shouldBe true)

    // Its own flush then completes and releases exactly what it wrote.
    ec.runAll()
    fresh.selfInbox.receiveAll().foreach(deliver(fresh, _))
    fresh.rawNodeBuffer.map(_._1).toSeq shouldBe Seq(node(4)._1)
    fresh.flushing shouldBe false
    fresh.mptStorage.stored.keySet shouldBe Set(1, 2, 3).map(node(_)._1)
  }

  it should "let no asynchronous flush write after a synchronous flush has written its nodes (vault V1, hunter W4)" taggedAs UnitTest in {
    val ec = new ManualExecutionContext
    val s = new StubFrontierPersistenceState(
      Some(new HealingFrontierStorage(EphemDataSource())),
      frontierPersistenceEnabled = true,
      prunedEnabled = true,
      healingWriterEc = ec
    ) with FrontierPersistence
    s.rawNodeBuffer ++= Seq(node(1), node(2))
    s.flushRawNodesAsync() // A: queued, not run
    s.rawNodeBuffer += node(3)
    s.flushRawNodesSync() shouldBe true // a re-peg: writes 1, 2 and 3
    s.rawNodeBuffer += node(4)
    s.flushRawNodesAsync() // B, after the synchronous flush
    ec.runAll() // A, then B
    s.selfInbox.receiveAll().foreach(deliver(s, _))
    withClue("A's nodes were written by the synchronous flush; A writes nothing more: ") {
      s.mptStorage.events.filter(_.startsWith("store")).toSeq shouldBe Seq("store:3", "store:1")
    }
    s.rawNodeBuffer shouldBe empty
    s.flushing shouldBe false
  }

  it should "never run a synchronous flush's write while an asynchronous flush is writing (vault V1, hunter W4)" taggedAs UnitTest in {
    val pool = java.util.concurrent.Executors.newSingleThreadExecutor()
    try
      val s = new StubFrontierPersistenceState(
        Some(new HealingFrontierStorage(EphemDataSource())),
        frontierPersistenceEnabled = true,
        prunedEnabled = true,
        healingWriterEc = ExecutionContext.fromExecutorService(pool)
      ) with FrontierPersistence
      val writing = new java.util.concurrent.CountDownLatch(1)
      val release = new java.util.concurrent.CountDownLatch(1)
      val calls = new AtomicInteger(0)
      s.mptStorage.onStore = underWay =>
        if calls.incrementAndGet() == 1 then
          writing.countDown() // the asynchronous write is under way and waits for the test
          release.await()
        else if underWay > 1 then release.countDown() // a second writer beside the first: let both finish
      s.rawNodeBuffer ++= Seq(node(1), node(2))
      s.flushRawNodesAsync()
      writing.await(30, java.util.concurrent.TimeUnit.SECONDS) shouldBe true
      s.rawNodeBuffer += node(3)
      val result = new java.util.concurrent.atomic.AtomicReference[Option[Boolean]](None)
      val sync = new Thread(() => result.set(Some(s.flushRawNodesSync())))
      sync.start()
      // Until the synchronous flush either waits for the store or writes beside the asynchronous one.
      while sync.isAlive && sync.getState != Thread.State.BLOCKED && s.mptStorage.maxActive.get() < 2 do
        Thread.onSpinWait()
      release.countDown()
      sync.join()
      s.mptStorage.maxActive.get() shouldBe 1
      result.get() shouldBe Some(true)
      s.mptStorage.stored.keySet shouldBe Set(1, 2, 3).map(node(_)._1)
    finally pool.shutdownNow()
  }

  it should "report a failed asynchronous write to self once, and reset flushing only for its own flush (T018, eye F1)" taggedAs UnitTest in {
    val s = stub()
    s.rawNodeBuffer ++= Seq(node(1), node(2))
    s.pendingSubtreeRecords += node(1)._1
    s.mptStorage.failWrites = 1
    s.flushRawNodesAsync()
    val failed = s.selfInbox.receiveAll().loneElement
    failed shouldBe a[FlushFailed]
    s.flushing shouldBe true
    // A failure report that is not this flush's changes nothing.
    deliver(s, FlushFailed(Long.MaxValue, new RuntimeException("not this flush")))
    s.flushing shouldBe true
    deliver(s, failed)
    s.flushing shouldBe false
    s.rawNodeBuffer.map(_._1).toSeq shouldBe Seq(node(1)._1, node(2)._1)
    s.pendingSubtreeRecords should contain(node(1)._1)
    s.mptStorage.stored shouldBe empty
  }

  it should "keep the buffer and the staged records when a synchronous flush's write throws, and write them on the next try (eye F1)" taggedAs UnitTest in {
    val s = stub()
    s.rawNodeBuffer ++= Seq(node(1), node(2))
    s.pendingSubtreeRecords += node(1)._1
    s.mptStorage.failWrites = 1
    s.flushRawNodesSync() shouldBe false
    s.rawNodeBuffer.map(_._1).toSeq shouldBe Seq(node(1)._1, node(2)._1)
    s.pendingSubtreeRecords should contain(node(1)._1)
    s.frontierStore.get.isSubtreeComplete(node(1)._1) shouldBe false
    s.flushing shouldBe false
    s.flushRawNodesSync() shouldBe true
    s.rawNodeBuffer shouldBe empty
    s.mptStorage.stored.keySet shouldBe Set(node(1)._1, node(2)._1)
    s.frontierStore.get.isSubtreeComplete(node(1)._1) shouldBe true
    s.pendingSubtreeRecords shouldBe empty
  }
