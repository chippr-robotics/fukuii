package com.chipprbots.ethereum.blockchain.sync.snap.actors.healing

import java.util.concurrent.atomic.AtomicInteger

import org.apache.pekko.actor.testkit.typed.scaladsl.TestInbox
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.util.ByteString

import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.jdk.CollectionConverters.*

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.snap.StateValidator
import com.chipprbots.ethereum.blockchain.sync.snap.StorageScheme
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator.ActiveRequest
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator.FlushComplete
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator.HealingEntry
import com.chipprbots.ethereum.blockchain.sync.snap.controller.StateIngest
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
import com.chipprbots.ethereum.testing.Tags.*

/** An `MptStorage` that, at each `persist()`, takes a snapshot of the frontier store for the nodes of the write being
  * persisted: whether any already has a subtree-complete record (it must not: spec 005 D3, record-after-persist) and
  * whether every one is still a frontier entry (it must: a healed node leaves the persisted frontier only after its
  * bytes are persisted).
  */
final private class OrderRecordingMptStorage(frontier: => Option[HealingFrontierStorage]) extends MptStorage:
  val events: mutable.ArrayBuffer[String] = mutable.ArrayBuffer.empty
  private var lastWrite: Seq[ByteString] = Nil
  var recordSeenAtPersist: Option[Boolean] = None
  var frontierHeldAtPersist: Option[Boolean] = None

  def get(nodeId: Array[Byte]): MptNode = throw new UnsupportedOperationException("FrontierPersistence reads no node")
  def updateNodesInStorage(newRoot: Option[MptNode], toRemove: Seq[MptNode]): Option[MptNode] =
    throw new UnsupportedOperationException("FrontierPersistence writes raw nodes only")
  override def storeRawNodes(nodes: Seq[(ByteString, Array[Byte])]): Unit =
    lastWrite = nodes.map(_._1)
    events += s"store:${nodes.size}"
  def persist(): Unit =
    recordSeenAtPersist = Some(frontier.exists(store => lastWrite.exists(store.isSubtreeComplete)))
    frontierHeldAtPersist = Some(frontier.exists(store => lastWrite.forall(h => store.get(h).isDefined)))
    events += "persist"

/** `FrontierPersistence`'s state and environment as plain fields (spec 016 C-H1), with persistence and pruning on, an
  * in-memory frontier store, the order-recording trie store and a logger of its own. `stateIngest` hands out only
  * `healingFrontier`.
  */
private class StubFrontierOrderState(val healingWriterEc: ExecutionContext)
    extends FrontierPersistenceState
    with TrieNodeHealingEnv:

  val frontierStore: HealingFrontierStorage = new HealingFrontierStorage(EphemDataSource())

  // TrieNodeHealingEnv: high-water 100, low-water 10, and a 0 ms safety timeout, so the drain wait never sleeps
  val log: org.slf4j.Logger =
    org.slf4j.LoggerFactory.getLogger(
      s"${classOf[FrontierPersistenceOrderSpec].getName}.stub-${java.util.UUID.randomUUID()}"
    )
  val selfInbox: TestInbox[TrieNodeHealingCoordinator.Command] = TestInbox[TrieNodeHealingCoordinator.Command]()
  val asyncLog: org.slf4j.Logger = log
  val self: ActorRef[TrieNodeHealingCoordinator.Command] = selfInbox.ref
  val storageScheme: StorageScheme = StorageScheme.Hash
  val prunedHealVerification: Boolean = true
  val mptStorage: OrderRecordingMptStorage = new OrderRecordingMptStorage(Some(frontierStore))
  val frontierPersistenceEnabled: Boolean = true
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
    def healingFrontier: Option[HealingFrontierStorage] = Some(frontierStore)
    def bfsQueue: Option[BfsQueueStorage] = notReached("bfsQueue")

  // FrontierPersistenceState
  val pendingTasks: mutable.ArrayDeque[HealingEntry] = mutable.ArrayDeque.empty
  val activeRequests: mutable.Map[BigInt, ActiveRequest] = mutable.Map.empty
  val pendingBackpressure: AtomicInteger = new AtomicInteger(0)
  var crossRootHealCount: Long = 0L
  val healAttempts: mutable.Map[ByteString, Int] = mutable.Map.empty
  val rawNodeBuffer: mutable.ArrayBuffer[(ByteString, Array[Byte])] = mutable.ArrayBuffer.empty
  var flushing: Boolean = false
  val pendingSubtreeRecords: mutable.Set[ByteString] = mutable.Set.empty
  var totalNodesHealed: Int = 0
  val prunedEnabled: Boolean = true

  private def notReached(member: String): Nothing =
    throw new UnsupportedOperationException(s"FrontierPersistence read StateIngest.$member")

/** Spec 016 C-H1 review follow-up (stub test, FR-016 (b)), next to `FrontierPersistenceSpec`. It pins the healed-node
  * flush's full durability order on both flushes: at `persist()` no subtree-complete record is written yet and every
  * flushed node is still a persisted frontier entry; after it, the records are written and the nodes leave the
  * frontier. It also pins the frontier walk's drain wait: no wait and no log below the high-water mark, and the INFO
  * pause and WARN safety-timeout lines above it. The async flush runs on `ExecutionContext.parasitic`.
  */
class FrontierPersistenceOrderSpec extends AnyFlatSpec with Matchers:

  private def node(i: Int): (ByteString, Array[Byte]) =
    val bytes = Array.fill[Byte](8)(i.toByte)
    kec256(ByteString(bytes)) -> bytes

  private def entry(i: Int): HealingEntry = HealingEntry(Seq(ByteString(Array(i.toByte))), node(i)._1)

  private def stub(): StubFrontierOrderState & FrontierPersistence =
    new StubFrontierOrderState(ExecutionContext.parasitic) with FrontierPersistence

  private def held(s: StubFrontierOrderState, i: Int): Boolean = s.frontierStore.get(entry(i).hash).isDefined

  /** The (level, message) lines `run` logs through the stub's own logger, at INFO. */
  private def logLines(s: StubFrontierOrderState)(run: => Unit): Seq[(Level, String)] =
    val logger = s.log.asInstanceOf[ch.qos.logback.classic.Logger]
    val appender = new ListAppender[ILoggingEvent]
    appender.start()
    logger.setLevel(Level.INFO)
    logger.addAppender(appender)
    try run
    finally logger.detachAppender(appender)
    appender.list.asScala.toSeq.map(e => e.getLevel -> e.getFormattedMessage)

  private def staged(s: StubFrontierOrderState & FrontierPersistence): Unit =
    s.persistFrontier(Seq(entry(1), entry(2), entry(3)))
    s.rawNodeBuffer ++= Seq(node(1), node(2))
    s.pendingSubtreeRecords += node(1)._1

  "FrontierPersistence's synchronous flush" should "persist the nodes before it unpersists them or writes their records" taggedAs UnitTest in {
    val s = stub()
    staged(s)
    s.flushRawNodesSync()

    s.mptStorage.events.toSeq shouldBe Seq("store:2", "persist")
    s.mptStorage.frontierHeldAtPersist shouldBe Some(true)
    s.mptStorage.recordSeenAtPersist shouldBe Some(false)
    (held(s, 1), held(s, 2), held(s, 3)) shouldBe ((false, false, true))
    s.frontierStore.isSubtreeComplete(node(1)._1) shouldBe true
  }

  "FrontierPersistence's asynchronous flush" should "persist the nodes before it unpersists them or writes their records" taggedAs UnitTest in {
    val s = stub()
    staged(s)
    s.flushRawNodesAsync()

    s.selfInbox.receiveAll() shouldBe Seq(FlushComplete(2))
    s.mptStorage.events.toSeq shouldBe Seq("store:2", "persist")
    s.mptStorage.frontierHeldAtPersist shouldBe Some(true)
    s.mptStorage.recordSeenAtPersist shouldBe Some(false)
    (held(s, 1), held(s, 2), held(s, 3)) shouldBe ((false, false, true))
    s.frontierStore.isSubtreeComplete(node(1)._1) shouldBe true
  }

  "FrontierPersistence's drain wait" should "not wait or log below the high-water mark" taggedAs UnitTest in {
    val s = stub()
    s.pendingBackpressure.set(99)
    logLines(s)(s.awaitFrontierDrain()) shouldBe empty
  }

  it should "pause above the high-water mark and give up at the safety timeout" taggedAs UnitTest in {
    val s = stub()
    s.pendingBackpressure.set(1000)
    val lines = logLines(s)(s.awaitFrontierDrain())
    lines.map(_._1) shouldBe Seq(Level.INFO, Level.WARN)
    lines(0)._2 should include("Backpressure: healing backlog 1000 >= high-water 100")
    lines(1)._2 should include("Backpressure wait hit the 0s safety timeout (backlog still 1000)")
  }
