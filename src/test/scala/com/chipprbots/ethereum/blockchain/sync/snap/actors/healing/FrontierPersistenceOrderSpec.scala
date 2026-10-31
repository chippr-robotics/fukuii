package com.chipprbots.ethereum.blockchain.sync.snap.actors.healing

import java.util.concurrent.atomic.AtomicInteger

import org.apache.pekko.actor.testkit.typed.scaladsl.TestInbox
import org.apache.pekko.actor.typed.ActorRef

import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.jdk.CollectionConverters.*

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.snap.SNAPRequestTracker
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController
import com.chipprbots.ethereum.blockchain.sync.snap.StateValidator
import com.chipprbots.ethereum.blockchain.sync.snap.StorageScheme
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator.ActiveRequest
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator.HealingEntry
import com.chipprbots.ethereum.blockchain.sync.snap.controller.StateIngest
import com.chipprbots.ethereum.blockchain.sync.snap.heal.FlushBuffer
import com.chipprbots.ethereum.blockchain.sync.snap.heal.FlushTicket
import com.chipprbots.ethereum.blockchain.sync.snap.heal.HealRequest
import com.chipprbots.ethereum.blockchain.sync.snap.heal.HealTaskQueue
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
import com.chipprbots.ethereum.testing.Tags.*

/** An `MptStorage` the drain wait never reaches. */
final private class OrderRecordingMptStorage extends MptStorage:
  def get(nodeId: Array[Byte]): MptNode = throw new UnsupportedOperationException("FrontierPersistence reads no node")
  def updateNodesInStorage(newRoot: Option[MptNode], toRemove: Seq[MptNode]): Option[MptNode] =
    throw new UnsupportedOperationException("FrontierPersistence writes raw nodes only")
  def persist(): Unit = ()

/** `FrontierPersistence`'s state and environment as plain fields (spec 016 C-H1), with a logger of its own. */
private class StubFrontierOrderState(val healingWriterEc: ExecutionContext)
    extends FrontierPersistenceState
    with TrieNodeHealingEnv:

  // TrieNodeHealingEnv: high-water 100, low-water 10, and a 0 ms safety timeout, so the drain wait never sleeps
  val log: org.slf4j.Logger =
    org.slf4j.LoggerFactory.getLogger(
      s"${classOf[FrontierPersistenceOrderSpec].getName}.stub-${java.util.UUID.randomUUID()}"
    )
  val selfInbox: TestInbox[TrieNodeHealingCoordinator.Command] = TestInbox[TrieNodeHealingCoordinator.Command]()
  val asyncLog: org.slf4j.Logger = log
  val self: ActorRef[TrieNodeHealingCoordinator.Command] = selfInbox.ref
  val storageScheme: StorageScheme = StorageScheme.Hash
  def networkPeerManager: ActorRef[NetworkPeerManagerActor.Command] =
    throw new UnsupportedOperationException("FrontierPersistence read networkPeerManager")
  def requestTracker: SNAPRequestTracker = throw new UnsupportedOperationException(
    "FrontierPersistence read requestTracker"
  )
  def snapSyncController: ActorRef[SNAPSyncController.Command] =
    throw new UnsupportedOperationException("FrontierPersistence read snapSyncController")
  val batchSize: Int = 32
  val movingRootDeltaHeal: Boolean = false
  val healedCodeCheck: Boolean = false
  val mptStorage: OrderRecordingMptStorage = new OrderRecordingMptStorage
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
    def healingFrontier: Option[HealingFrontierStorage] = notReached("healingFrontier")
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
  var totalNodesHealed: Int = 0

  private def notReached(member: String): Nothing =
    throw new UnsupportedOperationException(s"FrontierPersistence read StateIngest.$member")

/** Spec 016 C-H1 review follow-up (stub test, FR-016 (b)), next to `FrontierPersistenceSpec`. It pins the frontier
  * walk's drain wait: no wait and no log below the high-water mark, and the INFO pause and WARN safety-timeout lines
  * above it. (The flush's order against the persisted frontier and the subtree records it pinned went with them: spec
  * 022 D6.)
  */
class FrontierPersistenceOrderSpec extends AnyFlatSpec with Matchers:

  private def stub(): StubFrontierOrderState & FrontierPersistence =
    new StubFrontierOrderState(ExecutionContext.parasitic) with FrontierPersistence

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

  "FrontierPersistence's drain wait" should "not wait or log below the high-water mark" taggedAs UnitTest in {
    val s = stub()
    s.pendingBackpressure.set(99)
    logLines(s)(s.awaitFrontierDrain(() => false)) shouldBe empty
  }

  it should "pause above the high-water mark and give up at the safety timeout" taggedAs UnitTest in {
    val s = stub()
    s.pendingBackpressure.set(1000)
    val lines = logLines(s)(s.awaitFrontierDrain(() => false))
    lines.map(_._1) shouldBe Seq(Level.INFO, Level.WARN)
    lines(0)._2 should include("Backpressure: healing backlog 1000 >= high-water 100")
    lines(1)._2 should include("Backpressure wait hit the 0s safety timeout (backlog still 1000)")
  }
