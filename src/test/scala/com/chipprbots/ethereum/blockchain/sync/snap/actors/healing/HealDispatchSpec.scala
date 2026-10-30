package com.chipprbots.ethereum.blockchain.sync.snap.actors.healing

import java.net.InetSocketAddress

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.TestInbox
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.util.ByteString

import scala.collection.mutable

import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.snap.SNAPRequestTracker
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController
import com.chipprbots.ethereum.blockchain.sync.snap.StorageScheme
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator.ActiveRequest
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator.HealingCheckCompletion
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator.HealingEntry
import com.chipprbots.ethereum.blockchain.sync.snap.controller.StateIngest
import com.chipprbots.ethereum.blockchain.sync.snap.heal.FlushBuffer
import com.chipprbots.ethereum.blockchain.sync.snap.heal.HealRequest
import com.chipprbots.ethereum.blockchain.sync.snap.heal.HealRequestBook
import com.chipprbots.ethereum.blockchain.sync.snap.heal.HealTaskQueue
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerActor
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.p2p.messages.SNAP
import com.chipprbots.ethereum.testing.Tags.UnitTest

/** Everything `HealDispatch` needs, as a stub (spec 016 FR-016 (b), C-H4): its `HealDispatchState`, the coordinator's
  * `TrieNodeHealingEnv` and the `FrontierGaugesApi` it calls. State is plain fields; the gauges call is counted.
  * `self`, the peer manager and the controller are test inboxes; the request tracker is real, so a test cancels what it
  * leaves in flight.
  */
private class StubHealDispatchState(
    val networkPeerManager: ActorRef[NetworkPeerManagerActor.Command],
    val requestTracker: SNAPRequestTracker,
    val snapSyncController: ActorRef[SNAPSyncController.Command],
    val batchSize: Int,
    val decoupledHealServeRoot: Boolean,
    val movingRootDeltaHeal: Boolean
) extends HealDispatchState
    with TrieNodeHealingEnv
    with FrontierGaugesApi:

  // HealDispatchState
  var stateRoot: ByteString = ByteString(Array.fill[Byte](32)(7))
  var serveRoot: ByteString = stateRoot
  val pendingTasks: HealTaskQueue[HealingEntry] =
    new HealTaskQueue[HealingEntry](e => HealRequest.depthOf(e.pathset), _.hash, _ => false)
  val healRequests: HealRequestBook = new HealRequestBook()
  val activeRequests: mutable.Map[BigInt, ActiveRequest] = mutable.Map.empty
  var maxInFlightPerPeer: Int = 2
  var maxConcurrentRequests: Int = 16
  var pivotRefreshRequested: Boolean = false
  var pivotRefreshRequestedAt: Long = 0L
  val statelessPeers: mutable.Set[String] = mutable.Set.empty
  val knownAvailablePeers: mutable.Set[Peer] = mutable.Set.empty
  val peerCooldownUntilMs: mutable.Map[String, Long] = mutable.Map.empty
  val peerResponseBytesTarget: mutable.Map[String, BigInt] = mutable.Map.empty
  val healAttempts: HealAttemptMap = new HealAttemptMap(() => decoupledHealMaxAttemptsNoRefresh)
  var healRate: Double = 0.0
  var lastThrottleAdjustMs: Long = 0L
  val rawNodeBuffer: FlushBuffer = new FlushBuffer()
  var newSubtreesPaused: Boolean = false
  val rawFlushThreshold: Int = 1000
  var lastHealedAtMs: Long = System.currentTimeMillis()
  val healingStagnationTimeoutMs: Long = 5 * 60 * 1000
  var totalNodesHealed: Int = 0

  // TrieNodeHealingEnv (networkPeerManager, requestTracker, snapSyncController, batchSize, decoupledHealServeRoot and
  // movingRootDeltaHeal are constructor vals)
  val log: org.slf4j.Logger = org.slf4j.LoggerFactory.getLogger(classOf[HealDispatchSpec])
  val asyncLog: org.slf4j.Logger = log
  val selfInbox: TestInbox[TrieNodeHealingCoordinator.Command] = TestInbox[TrieNodeHealingCoordinator.Command]()
  val self: ActorRef[TrieNodeHealingCoordinator.Command] = selfInbox.ref
  def mptStorage: MptStorage = throw new UnsupportedOperationException("HealDispatch read mptStorage")
  def stateIngest: StateIngest = throw new UnsupportedOperationException("HealDispatch read stateIngest")
  val storageScheme: StorageScheme = StorageScheme.Hash
  val decoupledHealMaxAttemptsNoRefresh: Int = 2
  val frontierHighWater: Int = 100
  val frontierLowWater: Int = 10
  val frontierBackpressureMaxWaitMs: Long = 0L
  val healedCodeCheck: Boolean = false

  // FrontierGaugesApi
  var gaugeEmits: Int = 0
  def emitHealingFrontierGauges(): Unit = gaugeEmits += 1

/** Spec 016 C-H4 stub test (FR-016 (b), T074): `HealDispatch` mixed into a stub of its state interface, the coordinator
  * environment and the gauges interface. The cases call the module's throttle, batch-size, dispatch, cooldown, timeout,
  * redispatch, response-budget and unservable-attempt bodies, which read and write `HealDispatchState`.
  */
class HealDispatchSpec extends ScalaTestWithActorTestKit with AnyFlatSpecLike with Matchers:

  private val Initial: BigInt = 512 * 1024
  private val Floor: BigInt = 50 * 1024
  private val Ceiling: BigInt = 2 * 1024 * 1024

  /** The module on its stub and the inboxes it talks to. */
  final private class Harness(
      batchSize: Int = 32,
      decoupled: Boolean = false,
      movingRoot: Boolean = false
  ):
    val controller: TestInbox[SNAPSyncController.Command] = TestInbox[SNAPSyncController.Command]()
    val peerManager: TestInbox[NetworkPeerManagerActor.Command] = TestInbox[NetworkPeerManagerActor.Command]()
    val tracker = new SNAPRequestTracker()(system.classicSystem.scheduler)
    val module: StubHealDispatchState & HealDispatch =
      new StubHealDispatchState(peerManager.ref, tracker, controller.ref, batchSize, decoupled, movingRoot)
        with HealDispatch

    /** The GetTrieNodes requests sent, with the peer each went to. */
    def sent(): Seq[(SNAP.GetTrieNodes, PeerId)] =
      peerManager.receiveAll().collect { case NetworkPeerManagerActor.SendMessageCmd(enc, id) =>
        enc match
          case e: SNAP.GetTrieNodes.GetTrieNodesEnc => (e.underlyingMsg, id)
          case other                                => throw new AssertionError(s"expected a GetTrieNodes, got $other")
      }

    /** Queue `n` single-path tasks, as `queueNodes` does. */
    def queue(n: Int, from: Int = 0): Seq[HealingEntry] =
      val entries = (from until from + n).map(i => HealingEntry(Seq(ByteString(Array(i.toByte))), node(i)))
      module.pendingTasks ++= entries
      entries

    /** Stop the request tracker's timers for whatever is still in flight. */
    def cancelInFlight(): Unit = module.activeRequests.keys.foreach(tracker.cancelRequest)

  private def peer(id: String): Peer =
    Peer(PeerId(id), new InetSocketAddress("127.0.0.1", 30303), TestInbox[PeerActor.Command]().ref, false)

  private def node(i: Int): ByteString = ByteString(Array.fill[Byte](32)(i.toByte))

  /** The batch size the next request carries: one request to a fresh peer, then cleaned up. */
  private def nextBatchSize(h: Harness): Int =
    h.queue(64, from = 100)
    val p = peer(s"probe-${h.tracker.generateRequestId()}")
    h.module.maxInFlightPerPeer = 1
    h.module.dispatchIfPossible(p)
    val size = h.sent().map(_._1.paths.size).head
    h.cancelInFlight()
    h.module.activeRequests.clear()
    h.module.pendingTasks.clear()
    size

  "HealDispatch" should "throttle the batch size up under flush backpressure, cap it, and recover to the full batch" taggedAs UnitTest in {
    val h = new Harness(batchSize = 32)
    val m = h.module
    nextBatchSize(h) shouldBe 32

    // Buffer above 80% of the flush threshold: each adjustment multiplies the divisor by 1.33, capped at 4.
    m.rawNodeBuffer ++= (0 until 900).map(i => (node(i), Array[Byte](1)))
    m.lastThrottleAdjustMs = 0L
    m.updateHealThrottle(delivered = 10, elapsedMs = 1000)
    m.healRate should be > 0.0
    m.lastThrottleAdjustMs should be > 0L
    m.gaugeEmits shouldBe 1
    nextBatchSize(h) shouldBe 24 // 32 / 1.33

    (1 to 10).foreach { _ =>
      m.lastThrottleAdjustMs = 0L
      m.updateHealThrottle(delivered = 10, elapsedMs = 1000)
    }
    nextBatchSize(h) shouldBe 8 // 32 / MaxThrottle

    // Within a second of the last adjustment the divisor holds, even with the buffer drained.
    m.rawNodeBuffer.clear()
    m.lastThrottleAdjustMs = System.currentTimeMillis()
    m.updateHealThrottle(delivered = 10, elapsedMs = 1000)
    nextBatchSize(h) shouldBe 8

    // Drained buffer: the divisor falls by 1.25 per adjustment, floored at 1.
    (1 to 10).foreach { _ =>
      m.lastThrottleAdjustMs = 0L
      m.updateHealThrottle(delivered = 10, elapsedMs = 1000)
    }
    nextBatchSize(h) shouldBe 32
  }

  it should "never request fewer than one node per batch" taggedAs UnitTest in {
    val h = new Harness(batchSize = 2)
    val m = h.module
    m.rawNodeBuffer ++= (0 until 900).map(i => (node(i), Array[Byte](1)))
    (1 to 10).foreach { _ =>
      m.lastThrottleAdjustMs = 0L
      m.updateHealThrottle(delivered = 1, elapsedMs = 1000)
    }
    nextBatchSize(h) shouldBe 1 // 2 / 4 rounds to 0, floored at 1
  }

  it should "send GetTrieNodes against the walk root, the serve root when decoupled, and the walk root under the moving root" taggedAs UnitTest in {
    val serve = ByteString(Array.fill[Byte](32)(9))
    def rootSent(decoupled: Boolean, movingRoot: Boolean): ByteString =
      val h = new Harness(batchSize = 4, decoupled = decoupled, movingRoot = movingRoot)
      val m = h.module
      m.serveRoot = serve
      val entries = h.queue(3)
      m.maxInFlightPerPeer = 1
      val p = peer("p1")
      m.dispatchIfPossible(p)
      val sent = h.sent()
      sent should have size 1
      val (request, to) = sent.head
      to shouldBe p.id
      request.paths shouldBe entries.map(_.pathset)
      request.responseBytes shouldBe Initial
      m.pendingTasks shouldBe empty
      m.activeRequests.keySet shouldBe Set(request.requestId)
      m.activeRequests(request.requestId).tasks shouldBe entries
      h.tracker.isPending(request.requestId) shouldBe true
      h.cancelInFlight()
      request.rootHash

    val walk = ByteString(Array.fill[Byte](32)(7))
    rootSent(decoupled = false, movingRoot = false) shouldBe walk
    rootSent(decoupled = true, movingRoot = false) shouldBe serve
    rootSent(decoupled = true, movingRoot = true) shouldBe walk
  }

  it should "pipeline up to the per-peer and global caps, and skip stateless, cooling and refresh-latched dispatch" taggedAs UnitTest in {
    val h = new Harness(batchSize = 1)
    val m = h.module
    h.queue(10)
    val p = peer("p1")

    m.maxInFlightPerPeer = 3
    m.dispatchIfPossible(p)
    h.sent().map(_._2) shouldBe Seq.fill(3)(p.id)
    m.activeRequests.size shouldBe 3

    // Global cap: a second peer gets only what is left under maxConcurrentRequests.
    m.maxConcurrentRequests = 4
    val q = peer("p2")
    m.dispatchIfPossible(q)
    h.sent().map(_._2) shouldBe Seq(q.id)
    m.maxConcurrentRequests = 16

    val stateless = peer("stateless")
    m.statelessPeers += stateless.id.value
    m.dispatchIfPossible(stateless)

    val cooling = peer("cooling")
    m.recordPeerCooldown(cooling, "test")
    m.dispatchIfPossible(cooling)

    m.pivotRefreshRequested = true
    m.dispatchIfPossible(peer("p3"))

    h.sent() shouldBe empty
    m.pendingTasks.size shouldBe 6
    h.cancelInFlight()
  }

  it should "on a timeout cool the peer, halve its budget, re-queue the tasks and ask for a completion check" taggedAs UnitTest in {
    val h = new Harness(batchSize = 4)
    val m = h.module
    val p = peer("p1")
    m.knownAvailablePeers += p
    val entries = h.queue(2)
    m.maxInFlightPerPeer = 1
    m.dispatchIfPossible(p)
    val sent = h.sent()
    sent should have size 1
    val request = sent.head._1

    entries.foreach(e => m.healRequests.add(HealRequest.ofNode(e.pathset, e.hash)))
    m.handleTimeout(request.requestId, entries, p)
    m.activeRequests shouldBe empty
    m.peerCooldownUntilMs.get(p.id.value).exists(_ > System.currentTimeMillis()) shouldBe true
    m.responseBytesTargetFor(p) shouldBe Initial / 2
    m.pendingTasks.toSeq shouldBe entries
    m.healAttempts shouldBe empty // surfacing counts only under decoupling
    h.sent() shouldBe empty // the only peer is cooling: no synchronous floor revive
    h.module.selfInbox.receiveAll() shouldBe Seq(HealingCheckCompletion)
    h.controller.hasMessages shouldBe false
    h.tracker.cancelRequest(request.requestId)
  }

  it should "count unservable timeouts under decoupling and escalate a stagnant heal to the controller once" taggedAs UnitTest in {
    val h = new Harness(batchSize = 4, decoupled = true)
    val m = h.module
    val p = peer("p1")
    val entries = h.queue(1)
    m.pendingTasks.clear()
    m.totalNodesHealed = 17
    m.lastHealedAtMs = System.currentTimeMillis() - 6 * 60 * 1000

    entries.foreach(e => m.healRequests.add(HealRequest.ofNode(e.pathset, e.hash)))
    m.handleTimeout(BigInt(1), entries, p)
    m.healAttempts(entries.head.hash) shouldBe 1
    m.pivotRefreshRequested shouldBe true
    m.pivotRefreshRequestedAt should be > 0L
    m.lastHealedAtMs should be > System.currentTimeMillis() - 60 * 1000
    h.controller.receiveAll() shouldBe Seq(SNAPSyncController.HealingStagnated(17L, 1L))

    // Latched: a second stagnant timeout does not re-fire.
    m.lastHealedAtMs = System.currentTimeMillis() - 6 * 60 * 1000
    m.handleTimeout(BigInt(2), entries, p)
    m.healAttempts(entries.head.hash) shouldBe 2
    h.controller.hasMessages shouldBe false
  }

  // spec 022 review beacon N6, eye F9 (3): as the response path, not unconditionally.
  it should "put a timed-out fetch back from the next alias position, and not at all when nothing waits on it" taggedAs UnitTest in {
    val h = new Harness()
    val m = h.module
    val p = peer("p")
    val hash = ByteString(Array.fill[Byte](32)(0x21))
    val first = Seq(ByteString(Array[Byte](0, 0x11)))
    val second = Seq(ByteString(Array[Byte](0, 0x22)))
    m.healRequests.add(HealRequest.atPosition(first, hash))
    m.healRequests.add(HealRequest.atPosition(second, hash))

    m.handleTimeout(BigInt(1), Seq(HealingEntry(first, hash)), p)
    withClue("the next alias position: ")(m.pendingTasks.toSeq shouldBe Seq(HealingEntry(second, hash)))

    m.pendingTasks.clear()
    m.handleTimeout(BigInt(2), Seq(HealingEntry(second, hash)), p)
    withClue("and round again: ")(m.pendingTasks.toSeq shouldBe Seq(HealingEntry(first, hash)))

    m.pendingTasks.clear()
    val orphan = HealingEntry(first, ByteString(Array.fill[Byte](32)(0x31)))
    m.handleTimeout(BigInt(3), Seq(orphan), p)
    withClue("no live request waits on this one: ")(m.pendingTasks.toSeq shouldBe empty)
  }

  it should "revive the soonest-expiring cooling peer only on the periodic floor, never a stateless one" taggedAs UnitTest in {
    val h = new Harness(batchSize = 1)
    val m = h.module
    val (a, b, s) = (peer("a"), peer("b"), peer("s"))
    m.knownAvailablePeers ++= Seq(a, b, s)
    m.statelessPeers += s.id.value
    val now = System.currentTimeMillis()
    m.peerCooldownUntilMs ++= Seq(a.id.value -> (now + 20000), b.id.value -> (now + 10000), s.id.value -> (now + 1))
    h.queue(3)
    m.maxInFlightPerPeer = 1

    m.tryRedispatchPendingTasks()
    h.sent() shouldBe empty

    m.tryRedispatchPendingTasks(allowCooldownFloor = true)
    h.sent().map(_._2) shouldBe Seq(b.id)
    m.peerCooldownUntilMs.contains(b.id.value) shouldBe false
    m.peerCooldownUntilMs.contains(a.id.value) shouldBe true

    // Nothing while the pivot-refresh latch is set.
    m.pivotRefreshRequested = true
    m.tryRedispatchPendingTasks(allowCooldownFloor = true)
    h.sent() shouldBe empty
    h.cancelInFlight()
  }

  it should "grow the response budget on a near-full answer, hold it otherwise, and halve it to the floor on failure" taggedAs UnitTest in {
    val h = new Harness()
    val m = h.module
    val p = peer("p1")
    m.responseBytesTargetFor(p) shouldBe Initial

    m.adjustResponseBytesOnSuccess(p, Initial, Initial)
    m.responseBytesTargetFor(p) shouldBe Initial * 5 / 4
    m.adjustResponseBytesOnSuccess(p, Initial * 5 / 4, Initial / 2)
    m.responseBytesTargetFor(p) shouldBe Initial * 5 / 4
    m.adjustResponseBytesOnSuccess(p, Ceiling * 9 / 10, Ceiling)
    m.responseBytesTargetFor(p) shouldBe Ceiling

    (1 to 10).foreach(_ => m.adjustResponseBytesOnFailure(p, "test"))
    m.responseBytesTargetFor(p) shouldBe Floor
  }

  it should "count unservable attempts per task under decoupling, and none with decoupling off" taggedAs UnitTest in {
    val on = new Harness(decoupled = true).module
    val hash = node(1)
    (1 to 4).foreach(_ => on.noteUnservableAttempt(hash))
    on.healAttempts(hash) shouldBe 4

    val off = new Harness().module
    off.noteUnservableAttempt(hash)
    off.healAttempts shouldBe empty
  }

  it should "label a root by its leading six bytes as a non-negative number" taggedAs UnitTest in {
    val m = new Harness().module
    m.shortRootLabel(ByteString.empty) shouldBe 0L
    m.shortRootLabel(ByteString(Array[Byte](1, 2))) shouldBe 0x0102L
    m.shortRootLabel(ByteString(Array.fill[Byte](32)(0xff.toByte))) shouldBe 0xffffffffffffL
  }
