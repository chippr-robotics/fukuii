package com.chipprbots.ethereum.blockchain.sync.snap.actors.account

import java.net.InetSocketAddress

import org.apache.pekko.actor.testkit.typed.Effect
import org.apache.pekko.actor.testkit.typed.scaladsl.BehaviorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.TestInbox
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.TimerScheduler
import org.apache.pekko.util.ByteString

import scala.collection.mutable
import scala.concurrent.duration.*

import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.snap.AccountTask
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPRequestTracker
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController
import com.chipprbots.ethereum.blockchain.sync.snap.SnapIntakeBudget
import com.chipprbots.ethereum.blockchain.sync.snap.StorageScheme
import com.chipprbots.ethereum.blockchain.sync.snap.actors.AccountRangeCoordinator
import com.chipprbots.ethereum.blockchain.sync.snap.actors.AccountRangeCoordinator.FetchAccountRange
import com.chipprbots.ethereum.blockchain.sync.snap.actors.AccountRangeCoordinator.RecheckIntakeGate
import com.chipprbots.ethereum.blockchain.sync.snap.actors.AccountRangeCoordinator.WorkerRef
import com.chipprbots.ethereum.blockchain.sync.snap.actors.AccountRangeWorker
import com.chipprbots.ethereum.blockchain.sync.snap.controller.StateIngest
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.db.storage.SnapStorageDoneStorage
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerActor
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.testing.Tags.UnitTest

/** Everything `AccountPeerDispatch` needs, as a stub (spec 016 FR-016 (b), C-A1): its `AccountPeerDispatchState`, the
  * coordinator's `AccountRangeEnv` and the `AccountTaskLifecycleApi` it calls. State is plain fields; the redispatch
  * Api only counts its calls. The context and timers are a `BehaviorTestKit`'s, so spawns, scheduled messages and
  * timers are recorded as effects.
  */
private[actors] class StubAccountPeerDispatchState(
    val ctx: ActorContext[AccountRangeCoordinator.Command],
    val timers: TimerScheduler[AccountRangeCoordinator.Command],
    val networkPeerManager: ActorRef[NetworkPeerManagerActor.Command],
    val requestTracker: SNAPRequestTracker,
    val snapSyncController: ActorRef[SNAPSyncController.Command],
    val intakeBudget: Option[SnapIntakeBudget]
) extends AccountPeerDispatchState
    with AccountRangeEnv
    with AccountTaskLifecycleApi:

  // AccountPeerDispatchState
  var stateRoot: ByteString = ByteString(Array.fill[Byte](32)(7))
  var maxInFlightPerPeer: Int = 2
  val backpressureSources: mutable.Set[String] = mutable.Set.empty
  val statelessPeers: mutable.Set[PeerId] = mutable.Set.empty
  val snaplessPeers: mutable.Set[PeerId] = mutable.Set.empty
  val emptyResponseStrikes: mutable.Map[PeerId, Int] = mutable.Map.empty
  var pivotRefreshRequested: Boolean = false
  val pendingTasks: mutable.PriorityQueue[AccountTask] =
    mutable.PriorityQueue.empty[AccountTask](Ordering.by[AccountTask, BigInt](_.remainingKeyspace).reverse)
  val activeTasks: mutable.Map[BigInt, (AccountTask, WorkerRef, Peer)] = mutable.Map.empty
  val workers: mutable.ArrayBuffer[WorkerRef] = mutable.ArrayBuffer.empty
  val idleWorkers: mutable.LinkedHashSet[WorkerRef] = mutable.LinkedHashSet.empty
  var lastDispatchOrResponseMs: Long = 0L
  val knownAvailablePeers: mutable.Set[Peer] = mutable.Set.empty
  val peerResponseBytesTarget: mutable.Map[String, BigInt] = mutable.Map.empty
  val peerCooldownUntilMs: mutable.Map[String, Long] = mutable.Map.empty
  val lastDispatchTimeMs: mutable.Map[String, Long] = mutable.Map.empty
  var consecutiveUnproductiveRefreshes: Int = 0

  // AccountRangeEnv (ctx, timers, networkPeerManager, requestTracker, snapSyncController and intakeBudget are
  // constructor vals)
  val log: org.slf4j.Logger = org.slf4j.LoggerFactory.getLogger(classOf[StubAccountPeerDispatchState])
  val concurrency: Int = 1
  val initialResponseBytesConfig: Int = 524288
  val minResponseBytesConfig: Int = 102400
  val futureLog: org.slf4j.Logger = log
  val resumeProgress: Map[ByteString, ByteString] = Map.empty
  val storageScheme: StorageScheme = StorageScheme.Hash
  def mptStorage: MptStorage = throw new UnsupportedOperationException("AccountPeerDispatch read mptStorage")
  def stateIngest: StateIngest = throw new UnsupportedOperationException("AccountPeerDispatch read stateIngest")
  def storageDone: Option[SnapStorageDoneStorage] =
    throw new UnsupportedOperationException("AccountPeerDispatch read storageDone")

  // AccountTaskLifecycleApi
  var redispatchCalls: Int = 0
  def tryRedispatchPendingTasks(): Unit = redispatchCalls += 1

/** Spec 016 C-A1 stub test (FR-016 (b), T062): `AccountPeerDispatch` mixed into a stub of its state interface, the
  * coordinator environment and the task-lifecycle Api, inside a `BehaviorTestKit` so that the context and timers are
  * real. The cases call the module's strike, escalation, back-pressure, intake-gate, cooldown, response-budget and
  * dispatch bodies, which read and write `AccountPeerDispatchState`.
  */
class AccountPeerDispatchSpec extends ScalaTestWithActorTestKit with AnyFlatSpecLike with Matchers:

  private val EmptyProof = "Missing proof for empty account range"

  /** The module on its stub, the kit that runs it, and the inboxes it talks to. */
  final private class Harness(intakeBudget: Option[SnapIntakeBudget] = None):
    val controller: TestInbox[SNAPSyncController.Command] = TestInbox[SNAPSyncController.Command]()
    private val peerManager = TestInbox[NetworkPeerManagerActor.Command]()
    private val tracker = new SNAPRequestTracker()(system.classicSystem.scheduler)
    private var action: () => Unit = () => ()
    private var captured: StubAccountPeerDispatchState & AccountPeerDispatch = null
    val kit: BehaviorTestKit[AccountRangeCoordinator.Command] =
      BehaviorTestKit(Behaviors.setup[AccountRangeCoordinator.Command] { ctx =>
        Behaviors.withTimers { timers =>
          captured =
            new StubAccountPeerDispatchState(ctx, timers, peerManager.ref, tracker, controller.ref, intakeBudget)
              with AccountPeerDispatch
          Behaviors.receiveMessage { _ =>
            action()
            Behaviors.same
          }
        }
      })
    val module: StubAccountPeerDispatchState & AccountPeerDispatch = captured

    /** Run `f` on the module while the kit processes a message, as the coordinator's arms do. */
    def call(f: StubAccountPeerDispatchState & AccountPeerDispatch => Unit): Unit =
      action = () => f(module)
      kit.run(AccountRangeCoordinator.CheckCompletion)

    def effects(): Seq[Effect] = kit.retrieveAllEffects()

  private def peer(id: String): Peer =
    Peer(PeerId(id), new InetSocketAddress("127.0.0.1", 30303), TestInbox[PeerActor.Command]().ref, false)

  private def idleWorkers(m: StubAccountPeerDispatchState, n: Int): Seq[TestInbox[AccountRangeWorker.Command]] =
    Seq.fill(n) {
      val inbox = TestInbox[AccountRangeWorker.Command]()
      m.workers += inbox.ref
      m.idleWorkers += inbox.ref
      inbox
    }

  "AccountPeerDispatch" should "count empty-proof strikes and confirm a peer snapless and stateless at the fifth, escalating when every peer is snapless" taggedAs UnitTest in {
    val h = new Harness()
    val m = h.module
    val p = peer("p1")
    m.knownAvailablePeers += p

    (1 to 4).foreach(_ => h.call(_.markPeerStateless(p, EmptyProof)))
    m.emptyResponseStrikes(p.id) shouldBe 4
    m.snaplessPeers shouldBe empty
    m.statelessPeers shouldBe empty
    h.controller.hasMessages shouldBe false

    h.call(_.markPeerStateless(p, EmptyProof))
    m.snaplessPeers.toSet shouldBe Set(p.id)
    m.statelessPeers.toSet shouldBe Set(p.id)
    m.pivotRefreshRequested shouldBe true
    m.consecutiveUnproductiveRefreshes shouldBe 1
    h.controller.receiveAll() shouldBe Seq(
      SNAPSyncController.PivotStateUnservable(
        rootHash = m.stateRoot,
        reason = "all peers snapless (no snapshot tree) for AccountRange root",
        consecutiveEmptyResponses = 1,
        cause = SNAPSyncController.UnservableCause.Snapless
      )
    )

    // Only the empty-proof signal strikes; a success clears the counter.
    val q = peer("p2")
    h.call(_.markPeerStateless(q, "timeout"))
    m.emptyResponseStrikes.get(q.id) shouldBe None
    h.call(_.markPeerStateless(q, EmptyProof))
    m.emptyResponseStrikes(q.id) shouldBe 1
    h.call(_.recordPeerSuccess(q.id))
    m.emptyResponseStrikes.get(q.id) shouldBe None
  }

  it should "request a pivot refresh when every non-snapless peer is stateless, once, then back off" taggedAs UnitTest in {
    val h = new Harness()
    val m = h.module
    val (a, b) = (peer("a"), peer("b"))
    m.knownAvailablePeers ++= Seq(a, b)
    m.statelessPeers ++= Seq(a.id, b.id)

    h.call(_.maybeRequestPivotRefresh())
    h.controller.receiveAll() shouldBe Seq(
      SNAPSyncController.PivotStateUnservable(
        rootHash = m.stateRoot,
        reason = "all peers stateless for AccountRange root",
        consecutiveEmptyResponses = 2,
        cause = SNAPSyncController.UnservableCause.Stateless
      )
    )
    m.pivotRefreshRequested shouldBe true
    m.consecutiveUnproductiveRefreshes shouldBe 1

    // Already requested: nothing more.
    h.call(_.maybeRequestPivotRefresh())
    h.controller.hasMessages shouldBe false
    val _ = h.effects()

    // What PivotRefreshed does in the core. A refresh so soon is inside the backoff window: a re-check is scheduled.
    m.pivotRefreshRequested = false
    h.call(_.maybeRequestPivotRefresh())
    h.controller.hasMessages shouldBe false
    m.pivotRefreshRequested shouldBe false
    m.consecutiveUnproductiveRefreshes shouldBe 1
    h.effects().collect { case s: Effect.Scheduled[?] => s.message } shouldBe Seq(
      AccountRangeCoordinator.CheckCompletion
    )
  }

  it should "pause dispatch while any downstream source holds back-pressure, and redispatch when the last releases" taggedAs UnitTest in {
    val h = new Harness()
    val m = h.module
    m.dispatchDeliberatelyPaused shouldBe false

    h.call(_.applyBackpressureChange("storage", paused = true))
    h.call(_.applyBackpressureChange("bytecode", paused = true))
    m.backpressureSources.toSet shouldBe Set("storage", "bytecode")
    m.storageBackpressureActive shouldBe true
    m.dispatchDeliberatelyPaused shouldBe true

    h.call(_.applyBackpressureChange("storage", paused = false))
    m.backpressureSources.toSet shouldBe Set("bytecode")
    m.downstreamBackpressureActive shouldBe true
    m.redispatchCalls shouldBe 0

    h.call(_.applyBackpressureChange("bytecode", paused = false))
    m.backpressureSources shouldBe empty
    m.downstreamBackpressureActive shouldBe false
    m.redispatchCalls shouldBe 1

    // A zero per-peer budget is also a deliberate pause.
    m.maxInFlightPerPeer = 0
    m.dispatchDeliberatelyPaused shouldBe true
  }

  it should "hold dispatch while the intake gate is closed, arm its re-check timer once, and dispatch when it opens" taggedAs UnitTest in {
    val budget = new SnapIntakeBudget(maxPendingStorageTasks = 10, maxPendingByteCodeHashes = 10)
    val h = new Harness(Some(budget))
    val m = h.module
    val p = peer("p1")
    idleWorkers(m, 1)
    m.pendingTasks.enqueue(AccountTask.createInitialTasks(m.stateRoot, 1)*)

    budget.setHeapPressure(true)
    h.call(_.dispatchIfPossible(p))
    m.intakeGateClosed shouldBe true
    m.activeTasks shouldBe empty
    h.effects().collect { case t: Effect.TimerScheduled[?] => (t.key, t.msg, t.delay) } shouldBe Seq(
      (RecheckIntakeGate, RecheckIntakeGate, AccountRangeCoordinator.IntakeGateRecheckInterval)
    )

    h.call(_.dispatchIfPossible(p))
    h.effects().collect { case t: Effect.TimerScheduled[?] => t.key } shouldBe empty

    budget.setHeapPressure(false)
    h.call(_.dispatchIfPossible(p))
    m.intakeGateClosed shouldBe false
    m.activeTasks.size shouldBe 1
    m.pendingTasks shouldBe empty
  }

  it should "assign pending tasks to idle workers up to the per-peer in-flight cap" taggedAs UnitTest in {
    val h = new Harness()
    val m = h.module
    val p = peer("p1")
    val w = idleWorkers(m, 3)
    m.pendingTasks.enqueue(AccountTask.createInitialTasks(m.stateRoot, 3)*)

    h.call(_.dispatchIfPossible(p))

    // maxInFlightPerPeer = 2: two tasks go out, one stays queued, one worker stays idle.
    m.activeTasks.size shouldBe 2
    m.inFlightForPeer(p) shouldBe 2
    m.pendingTasks.size shouldBe 1
    m.idleWorkers.toSeq shouldBe Seq(w(2).ref)
    m.activeTasks.values.foreach { case (task, _, to) =>
      task.pending shouldBe true
      to shouldBe p
    }
    m.lastDispatchTimeMs(p.id.value) shouldBe m.lastDispatchOrResponseMs
    val sent = w.take(2).flatMap(_.receiveAll())
    sent.collect { case f: FetchAccountRange => f.responseBytes } shouldBe Seq.fill(2)(BigInt(524288))
    w(2).hasMessages shouldBe false
  }

  it should "spawn a watched worker when none is idle, up to maxWorkers" taggedAs UnitTest in {
    val h = new Harness()
    val m = h.module
    val p = peer("p1")
    m.pendingTasks.enqueue(AccountTask.createInitialTasks(m.stateRoot, 3)*)

    // maxWorkers = max(concurrency = 1, 0 known peers) * maxInFlightPerPeer (2) = 2.
    h.call(_.dispatchIfPossible(p))

    m.workers.size shouldBe 2
    m.idleWorkers shouldBe empty
    m.activeTasks.size shouldBe 2
    h.effects().collect { case s: Effect.Spawned[?] => s.childName } shouldBe Seq(
      "account-range-worker-1",
      "account-range-worker-2"
    )
  }

  it should "cool a peer for the scarce or the full duration, and leave cooling and demoted peers out of the active count" taggedAs UnitTest in {
    val h = new Harness()
    val m = h.module
    val peers = (1 to 4).map(i => peer(s"p$i"))
    m.knownAvailablePeers ++= peers.take(3)
    m.effectivePeerCooldown shouldBe 8.seconds
    m.knownAvailablePeers += peers(3)
    m.effectivePeerCooldown shouldBe 30.seconds

    h.call(_.recordPeerCooldown(peers(0), "timeout", 30.seconds))
    m.isPeerCoolingDown(peers(0)) shouldBe true
    m.peerCooldownUntilMs.keySet shouldBe Set(peers(0).id.value)
    m.isPeerCoolingDown(peers(1)) shouldBe false
    m.statelessPeers += peers(1).id
    m.snaplessPeers += peers(2).id
    m.activePeerCount shouldBe 1
  }

  it should "grow a peer's response budget on a near-full response and halve it on failure, within the clamps" taggedAs UnitTest in {
    val h = new Harness()
    val m = h.module
    val p = peer("p1")
    m.responseBytesTargetFor(p) shouldBe BigInt(524288)
    m.peerResponseBytesTarget(p.id.value) shouldBe BigInt(524288)

    m.adjustResponseBytesOnSuccess(p, requested = BigInt(524288), received = BigInt(524288))
    m.responseBytesTargetFor(p) shouldBe BigInt(655360)
    // Under 90 % of the request: unchanged.
    m.adjustResponseBytesOnSuccess(p, requested = BigInt(655360), received = BigInt(100))
    m.responseBytesTargetFor(p) shouldBe BigInt(655360)

    h.call(_.adjustResponseBytesOnFailure(p, "timeout"))
    m.responseBytesTargetFor(p) shouldBe BigInt(327680)
    (1 to 10).foreach(_ => h.call(_.adjustResponseBytesOnFailure(p, "timeout")))
    m.responseBytesTargetFor(p) shouldBe BigInt(102400)
  }
