package com.chipprbots.ethereum.blockchain.sync.snap.actors.bytecode

import java.net.InetSocketAddress

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.TestInbox
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.util.ByteString

import scala.collection.mutable
import scala.concurrent.duration.*

import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.snap.ByteCodeTask
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPRequestTracker
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController
import com.chipprbots.ethereum.blockchain.sync.snap.SnapIntakeBudget
import com.chipprbots.ethereum.blockchain.sync.snap.actors.ByteCodeCoordinator
import com.chipprbots.ethereum.blockchain.sync.snap.actors.ByteCodeCoordinator.ActiveByteCodeRequest
import com.chipprbots.ethereum.blockchain.sync.snap.actors.ByteCodeCoordinator.ByteCodeWorkerFetchTask
import com.chipprbots.ethereum.blockchain.sync.snap.actors.ByteCodeCoordinator.WorkerRef
import com.chipprbots.ethereum.blockchain.sync.snap.actors.ByteCodeWorker
import com.chipprbots.ethereum.blockchain.sync.snap.controller.StateIngest
import com.chipprbots.ethereum.blockchain.sync.snap.heal.ExcludedRecorder
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerActor
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.testing.Tags.UnitTest

/** Everything `ByteCodeDispatch` needs, as a stub (spec 016 FR-016 (b), C-B1): its `ByteCodeDispatchState`, the
  * coordinator's `ByteCodeEnv` and the core's `ByteCodeQueueApi`. State is plain fields; the environment members these
  * tests never reach throw.
  */
private[actors] class StubByteCodeDispatchState(val requestTracker: SNAPRequestTracker, val maxWorkers: Int)
    extends ByteCodeDispatchState
    with ByteCodeEnv
    with ByteCodeQueueApi:

  // ByteCodeDispatchState
  val activeTasks: mutable.Map[BigInt, ActiveByteCodeRequest] = mutable.Map.empty
  val idleWorkers: mutable.LinkedHashSet[WorkerRef] = mutable.LinkedHashSet.empty
  val knownAvailablePeers: mutable.Set[Peer] = mutable.Set.empty
  var maxInFlightPerPeer: Int = 2
  val peerCooldownUntilMillis: mutable.Map[PeerId, Long] = mutable.Map.empty
  val peerFailureCounts: mutable.Map[PeerId, Int] = mutable.Map.empty
  val peerResponseBytesTarget: mutable.Map[PeerId, BigInt] = mutable.Map.empty
  val pendingTasks: mutable.Queue[ByteCodeTask] = mutable.Queue.empty
  val workers: mutable.ArrayBuffer[WorkerRef] = mutable.ArrayBuffer.empty

  // ByteCodeEnv
  def context: ActorContext[ByteCodeCoordinator.Command] = notUsed("context")
  val log: org.slf4j.Logger = org.slf4j.LoggerFactory.getLogger(classOf[StubByteCodeDispatchState])
  def networkPeerManager: ActorRef[NetworkPeerManagerActor.Command] = notUsed("networkPeerManager")
  val cooldownConfig: ByteCodeCoordinator.ByteCodePeerCooldownConfig =
    ByteCodeCoordinator.ByteCodePeerCooldownConfig.default
  def snapSyncController: ActorRef[SNAPSyncController.Command] = notUsed("snapSyncController")
  def intakeBudget: Option[SnapIntakeBudget] = None
  def stateIngest: StateIngest = notUsed("stateIngest")
  val excludedRecorder: ExcludedRecorder = new ExcludedRecorder(notUsed("snapProgress"), enabled = false, log)

  // ByteCodeQueueApi
  def pendingHashCount: Long = pendingTasks.iterator.map(_.codeHashes.size.toLong).sum

  private def notUsed(what: String): Nothing =
    throw new UnsupportedOperationException(s"$what is not reached by ByteCodeDispatchSpec")

/** Spec 016 C-B1 stub test (FR-016 (b), T061): `ByteCodeDispatch` mixed into a stub of its state interface, the
  * coordinator environment and the core's queue Api. The cases call the module's cooldown, response-budget and dispatch
  * bodies, which read and write `ByteCodeDispatchState`.
  */
class ByteCodeDispatchSpec extends ScalaTestWithActorTestKit with AnyFlatSpecLike with Matchers:

  private def newModule(maxWorkers: Int = 4): StubByteCodeDispatchState & ByteCodeDispatch =
    val tracker = new SNAPRequestTracker()(system.classicSystem.scheduler)
    new StubByteCodeDispatchState(tracker, maxWorkers) with ByteCodeDispatch

  private def peer(id: String): Peer =
    Peer(PeerId(id), new InetSocketAddress("127.0.0.1", 30303), TestInbox[PeerActor.Command]().ref, false)

  private def task(n: Int): ByteCodeTask = ByteCodeTask(Seq(ByteString(Array.fill[Byte](32)(n.toByte))))

  "ByteCodeDispatch" should "back a failing peer off exponentially up to the cap, and clear it on success" taggedAs UnitTest in {
    val m = newModule()
    val p = peer("p1")
    // Default config: max 30 s, so 10 s, 20 s, then 40 s capped at 30 s.
    m.recordPeerCooldown(p, 10.seconds, "first") shouldBe 10.seconds
    m.recordPeerCooldown(p, 10.seconds, "second") shouldBe 20.seconds
    m.recordPeerCooldown(p, 10.seconds, "third") shouldBe 30.seconds
    m.peerFailureCounts(p.id) shouldBe 3
    m.isPeerCoolingDown(p) shouldBe true
    m.isPeerCoolingDown(peer("p2")) shouldBe false

    m.clearPeerFailures(p)
    m.peerFailureCounts shouldBe empty
    m.peerCooldownUntilMillis shouldBe empty
    m.isPeerCoolingDown(p) shouldBe false
  }

  it should "grow a peer's response budget on a near-full response and halve it on failure, within the clamps" taggedAs UnitTest in {
    val m = newModule()
    val p = peer("p1")
    m.responseBytesTargetFor(p) shouldBe BigInt(512 * 1024)
    m.peerResponseBytesTarget(p.id) shouldBe BigInt(512 * 1024)

    m.adjustResponseBytesTargetOnSuccess(p, requested = BigInt(512 * 1024), received = BigInt(512 * 1024))
    m.responseBytesTargetFor(p) shouldBe BigInt(640 * 1024)
    // Under 90 % of the request: unchanged.
    m.adjustResponseBytesTargetOnSuccess(p, requested = BigInt(640 * 1024), received = BigInt(100))
    m.responseBytesTargetFor(p) shouldBe BigInt(640 * 1024)
    // At the 2 MiB cap: no growth.
    m.peerResponseBytesTarget.update(p.id, BigInt(2 * 1024 * 1024))
    m.adjustResponseBytesTargetOnSuccess(p, requested = BigInt(2 * 1024 * 1024), received = BigInt(2 * 1024 * 1024))
    m.responseBytesTargetFor(p) shouldBe BigInt(2 * 1024 * 1024)

    m.adjustResponseBytesTargetOnFailure(p, "timeout")
    m.responseBytesTargetFor(p) shouldBe BigInt(1024 * 1024)
    (1 to 10).foreach(_ => m.adjustResponseBytesTargetOnFailure(p, "timeout"))
    m.responseBytesTargetFor(p) shouldBe BigInt(50 * 1024)
  }

  it should "return only the pool's own workers to the idle set" taggedAs UnitTest in {
    val m = newModule()
    val own = TestInbox[ByteCodeWorker.Command]()
    val stranger = TestInbox[ByteCodeWorker.Command]()
    m.workers += own.ref

    m.markWorkerIdle(own.ref)
    m.markWorkerIdle(stranger.ref)

    m.idleWorkers.toSeq shouldBe Seq(own.ref)
  }

  it should "assign pending tasks to idle workers up to the per-peer in-flight cap" taggedAs UnitTest in {
    val m = newModule(maxWorkers = 3)
    val w = Seq.fill(3)(TestInbox[ByteCodeWorker.Command]())
    w.foreach { inbox =>
      m.workers += inbox.ref
      m.idleWorkers += inbox.ref
    }
    m.pendingTasks.enqueueAll(Seq(task(1), task(2), task(3)))
    val p = peer("p1")

    m.dispatchIfPossible(p)

    // maxInFlightPerPeer = 2: two tasks go out, one stays queued, one worker stays idle.
    m.activeTasks.size shouldBe 2
    m.pendingTasks.toSeq shouldBe Seq(task(3))
    m.idleWorkers.toSeq shouldBe Seq(w(2).ref)
    m.activeTasks.values.map(_.task).toSet shouldBe Set(task(1).copy(pending = true), task(2).copy(pending = true))
    m.activeTasks.values.foreach(a => a.peer shouldBe p)
    val sent = w.take(2).flatMap(_.receiveAll())
    sent.collect { case f: ByteCodeWorkerFetchTask => f.maxResponseSize } shouldBe Seq.fill(2)(BigInt(512 * 1024))
    w(2).hasMessages shouldBe false
  }

  it should "redispatch to known peers that are not cooling down" taggedAs UnitTest in {
    val m = newModule(maxWorkers = 2)
    val w = Seq.fill(2)(TestInbox[ByteCodeWorker.Command]())
    w.foreach { inbox =>
      m.workers += inbox.ref
      m.idleWorkers += inbox.ref
    }
    m.pendingTasks.enqueueAll(Seq(task(1), task(2)))
    val cooling = peer("cooling")
    val ready = peer("ready")
    m.knownAvailablePeers ++= Seq(cooling, ready)
    m.recordPeerCooldown(cooling, 10.seconds, "test")
    m.maxInFlightPerPeer = 1

    m.tryRedispatchPendingTasks()

    m.activeTasks.values.map(_.peer).toSeq shouldBe Seq(ready)
    m.pendingTasks.toSeq shouldBe Seq(task(2))
  }
