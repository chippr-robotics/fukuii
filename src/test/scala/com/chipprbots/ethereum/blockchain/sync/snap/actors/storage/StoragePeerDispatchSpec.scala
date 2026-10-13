package com.chipprbots.ethereum.blockchain.sync.snap.actors.storage

import java.net.InetSocketAddress

import org.apache.pekko.actor.testkit.typed.Effect
import org.apache.pekko.actor.testkit.typed.scaladsl.BehaviorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.TestInbox
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.util.ByteString

import scala.collection.mutable
import scala.concurrent.duration.*

import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.snap.SNAPRequestTracker
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController
import com.chipprbots.ethereum.blockchain.sync.snap.SnapIntakeBudget
import com.chipprbots.ethereum.blockchain.sync.snap.SnapPeerHealth
import com.chipprbots.ethereum.blockchain.sync.snap.SnapTrie
import com.chipprbots.ethereum.blockchain.sync.snap.StorageTask
import com.chipprbots.ethereum.blockchain.sync.snap.actors.StorageRangeCoordinator
import com.chipprbots.ethereum.blockchain.sync.snap.actors.StorageRangeCoordinator.PendingResponse
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerActor
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.testing.Tags.UnitTest

/** Everything `StoragePeerDispatch` needs, as a stub (spec 016 FR-016 (b), C-S1): its `StoragePeerDispatchState`, the
  * coordinator's `StorageRangeEnv` and the `StorageRangeCoreApi` it calls. State is plain fields; `progress` returns a
  * fixed value. The context is a `BehaviorTestKit`'s, so scheduled messages are recorded as effects and messages to
  * `self` land in the kit's self inbox.
  */
private[actors] class StubStoragePeerDispatchState(
    val context: ActorContext[StorageRangeCoordinator.Command],
    val networkPeerManager: ActorRef[NetworkPeerManagerActor.Command],
    val requestTracker: SNAPRequestTracker,
    val snapSyncController: ActorRef[SNAPSyncController.Command],
    val intakeBudget: Option[SnapIntakeBudget]
) extends StoragePeerDispatchState
    with StorageRangeEnv
    with StorageRangeCoreApi:

  // StoragePeerDispatchState
  var stateRoot: ByteString = ByteString(Array.fill[Byte](32)(7))
  var maxInFlightPerPeer: Int = 2
  val tasks: mutable.Queue[StorageTask] = mutable.Queue.empty
  val pendingTaskKeys: mutable.Set[(ByteString, ByteString)] = mutable.Set.empty
  val activeTasks: mutable.Map[BigInt, (Peer, Seq[StorageTask], BigInt)] = mutable.Map.empty
  var completedTaskCount: Long = 0L
  var consecutiveTaskFailures: Int = 0
  val peerCooldownUntilMs: mutable.Map[String, Long] = mutable.Map.empty
  val peerHealth: SnapPeerHealth = new SnapPeerHealth()
  val statelessPeers: mutable.Set[String] = mutable.Set.empty
  val emptyResponseStrikes: mutable.Map[String, Int] = mutable.Map.empty
  var pivotRefreshRequested: Boolean = false
  var consecutiveUnproductiveRefreshes: Int = 0
  var lastDispatchOrResponseMs: Long = System.currentTimeMillis()
  var postRefreshCooldownUntilMs: Long = 0L
  val peerBatchSize: mutable.Map[String, Int] = mutable.Map.empty
  val peerBatchSuccessStreak: mutable.Map[String, Int] = mutable.Map.empty
  val knownAvailablePeers: mutable.Set[Peer] = mutable.Set.empty
  var noMoreTasksExpected: Boolean = false
  val peerResponseBytesTarget: mutable.Map[String, BigInt] = mutable.Map.empty
  val pendingAccountTries: mutable.Map[ByteString, SnapTrie] = mutable.Map.empty
  val pendingResponses: mutable.Queue[PendingResponse] = mutable.Queue.empty
  var pendingResponseBytes: Long = 0L
  var runningProcessingJobs: Int = 0

  // StorageRangeEnv (context, networkPeerManager, requestTracker, snapSyncController and intakeBudget are constructor
  // vals)
  val log: org.slf4j.Logger = org.slf4j.LoggerFactory.getLogger(classOf[StubStoragePeerDispatchState])
  val self: ActorRef[StorageRangeCoordinator.Command] = context.self
  val maxAccountsPerBatch: Int = 4
  val maxInFlightRequests: Int = 10
  val requestTimeout: FiniteDuration = 30.seconds
  val configInitialResponseBytes: Int = 1048576
  val configMinResponseBytes: Int = 131072
  val deferredMerkleization: Boolean = true
  val maxConcurrentStorageAccounts: Int = 256
  val processing: StorageRangeCoordinator.ProcessingSettings = StorageRangeCoordinator.ProcessingSettings.Inline

  // StorageRangeCoreApi
  def progress: Double = 0.5

/** Spec 016 C-S1 stub test (FR-016 (b), T066): `StoragePeerDispatch` mixed into a stub of its state interface, the
  * coordinator environment and the core Api, inside a `BehaviorTestKit` so that the context is real. The cases call the
  * module's strike, escalation, dispatch, timeout, redispatch, batch-size, response-budget and cooldown bodies, which
  * read and write `StoragePeerDispatchState`.
  */
class StoragePeerDispatchSpec extends ScalaTestWithActorTestKit with AnyFlatSpecLike with Matchers:

  private val Min = ByteString(Array.fill[Byte](32)(0))
  private val Max = ByteString(Array.fill[Byte](32)(0xff.toByte))

  /** The module on its stub, the kit that runs it, and the inboxes it talks to. */
  final private class Harness:
    val controller: TestInbox[SNAPSyncController.Command] = TestInbox[SNAPSyncController.Command]()
    val peerManager: TestInbox[NetworkPeerManagerActor.Command] = TestInbox[NetworkPeerManagerActor.Command]()
    val tracker = new SNAPRequestTracker()(system.classicSystem.scheduler)
    private var action: () => Unit = () => ()
    private var captured: StubStoragePeerDispatchState & StoragePeerDispatch = null
    val kit: BehaviorTestKit[StorageRangeCoordinator.Command] =
      BehaviorTestKit(Behaviors.setup[StorageRangeCoordinator.Command] { ctx =>
        captured = new StubStoragePeerDispatchState(ctx, peerManager.ref, tracker, controller.ref, None)
          with StoragePeerDispatch
        Behaviors.receiveMessage { _ =>
          action()
          Behaviors.same
        }
      })
    val module: StubStoragePeerDispatchState & StoragePeerDispatch = captured

    /** Run `f` on the module while the kit processes a message, as the coordinator's arms do. */
    def call(f: StubStoragePeerDispatchState & StoragePeerDispatch => Unit): Unit =
      action = () => f(module)
      kit.run(StorageRangeCoordinator.StorageCheckCompletion)

    def effects(): Seq[Effect] = kit.retrieveAllEffects()

    def selfMessages(): Seq[StorageRangeCoordinator.Command] = kit.selfInbox().receiveAll()

    /** The peers the dispatched GetStorageRanges went to. */
    def sentTo(): Seq[PeerId] =
      peerManager.receiveAll().collect { case NetworkPeerManagerActor.SendMessageCmd(_, id) => id }

    /** Stop the request tracker's timers for whatever is still in flight. */
    def cancelInFlight(): Unit = module.activeTasks.keys.foreach(tracker.cancelRequest)

  private def peer(id: String): Peer =
    Peer(PeerId(id), new InetSocketAddress("127.0.0.1", 30303), TestInbox[PeerActor.Command]().ref, false)

  private def account(i: Int): ByteString = ByteString(Array.fill[Byte](32)(i.toByte))

  private def initialTask(i: Int): StorageTask = StorageTask(account(i), account(100 + i), Min, Max)

  private def continuationTask(i: Int): StorageTask = StorageTask(account(i), account(100 + i), account(50), Max)

  "StoragePeerDispatch" should "count empty-response strikes and confirm a peer stateless at the fifth, escalating when every peer is stateless" taggedAs UnitTest in {
    val h = new Harness()
    val m = h.module
    val p = peer("p1")
    m.knownAvailablePeers += p

    (1 to 4).foreach(_ => h.call(_.markPeerStateless(p)))
    m.emptyResponseStrikes(p.id.value) shouldBe 4
    m.statelessPeers shouldBe empty
    m.isPeerStateless(p) shouldBe false
    h.controller.hasMessages shouldBe false

    h.call(_.markPeerStateless(p))
    m.statelessPeers.toSet shouldBe Set(p.id.value)
    m.isPeerStateless(p) shouldBe true
    m.pivotRefreshRequested shouldBe true
    m.consecutiveUnproductiveRefreshes shouldBe 1
    h.controller.receiveAll() shouldBe Seq(
      SNAPSyncController.PivotStateUnservable(
        rootHash = m.stateRoot,
        reason = "all peers stateless for StorageRange root",
        consecutiveEmptyResponses = 1
      )
    )

    // A served answer clears the strikes and ends the global failure run.
    val q = peer("p2")
    h.call(_.markPeerStateless(q))
    m.emptyResponseStrikes(q.id.value) shouldBe 1
    m.consecutiveTaskFailures = 7
    h.call(_.recordPeerSuccess(q.id.value))
    m.emptyResponseStrikes.get(q.id.value) shouldBe None
    m.consecutiveTaskFailures shouldBe 0
  }

  it should "request a pivot refresh once, then schedule a re-check inside the backoff window" taggedAs UnitTest in {
    val h = new Harness()
    val m = h.module
    val (a, b) = (peer("a"), peer("b"))
    m.knownAvailablePeers ++= Seq(a, b)
    m.statelessPeers ++= Seq(a.id.value, b.id.value)

    h.call(_.maybeRequestPivotRefresh())
    h.controller.receiveAll() shouldBe Seq(
      SNAPSyncController.PivotStateUnservable(
        rootHash = m.stateRoot,
        reason = "all peers stateless for StorageRange root",
        consecutiveEmptyResponses = 2
      )
    )
    m.pivotRefreshRequested shouldBe true
    m.consecutiveUnproductiveRefreshes shouldBe 1

    // Already requested: nothing more.
    h.call(_.maybeRequestPivotRefresh())
    h.controller.hasMessages shouldBe false
    val _ = h.effects()

    // What StoragePivotRefreshed does in the core. A refresh so soon is inside the backoff window: a re-check is
    // scheduled instead.
    m.pivotRefreshRequested = false
    h.call(_.maybeRequestPivotRefresh())
    h.controller.hasMessages shouldBe false
    m.pivotRefreshRequested shouldBe false
    m.consecutiveUnproductiveRefreshes shouldBe 1
    h.effects().collect { case s: Effect.Scheduled[?] => s.message } shouldBe Seq(
      StorageRangeCoordinator.StorageCheckCompletion
    )
  }

  it should "escalate on a low-eligible pool, and on a stalled dispatch after re-queueing the in-flight tasks" taggedAs UnitTest in {
    val low = new Harness()
    val peers = (1 to 4).map(i => peer(s"p$i"))
    low.module.knownAvailablePeers ++= peers
    low.module.statelessPeers ++= peers.take(3).map(_.id.value)
    low.module.tasks.enqueue(initialTask(1))
    low.call(_.maybeRequestPivotRefresh())
    low.controller.receiveAll() shouldBe Seq(
      SNAPSyncController.PivotStateUnservable(
        rootHash = low.module.stateRoot,
        reason = "low-eligible peers for StorageRange root",
        consecutiveEmptyResponses = 3
      )
    )

    // No dispatch or response for over two minutes with work pending: the known peers are treated as gone.
    val stalled = new Harness()
    val m = stalled.module
    val p = peer("ghost")
    m.knownAvailablePeers += p
    m.tasks.enqueue(initialTask(1))
    m.activeTasks.put(BigInt(9), (p, Seq(initialTask(2).copy(pending = true)), BigInt(1024)))
    m.lastDispatchOrResponseMs = System.currentTimeMillis() - 10.minutes.toMillis
    stalled.call(_.maybeRequestPivotRefresh())
    m.statelessPeers.toSet shouldBe Set(p.id.value)
    m.activeTasks shouldBe empty
    m.tasks.map(_.accountHash).toSeq shouldBe Seq(account(1), account(2))
    m.tasks.forall(!_.pending) shouldBe true
    m.pivotRefreshRequested shouldBe true
    val reasons =
      stalled.controller.receiveAll().collect { case u: SNAPSyncController.PivotStateUnservable => u.reason }
    reasons shouldBe Seq("all peers stateless for StorageRange root")
  }

  it should "batch initial-range tasks into one GetStorageRanges per request, up to the peer's batch size" taggedAs UnitTest in {
    val h = new Harness()
    val m = h.module
    val p = peer("p1")
    (1 to 3).foreach(i => m.tasks.enqueue(initialTask(i)))
    (1 to 3).foreach(i => m.pendingTaskKeys += ((account(i), Min)))
    m.lastDispatchOrResponseMs = 0L

    h.call(_.dispatchIfPossible(p))

    // maxAccountsPerBatch = 4: the three initial-range tasks go out together in one request.
    m.activeTasks.size shouldBe 1
    val (to, sent, requestedBytes) = m.activeTasks.values.head
    to shouldBe p
    sent.map(_.accountHash) shouldBe Seq(account(1), account(2), account(3))
    sent.forall(_.pending) shouldBe true
    requestedBytes shouldBe BigInt(1048576)
    m.tasks shouldBe empty
    m.pendingTaskKeys shouldBe empty
    m.lastDispatchOrResponseMs should be > 0L
    h.sentTo() shouldBe Seq(p.id)
    h.cancelInFlight()
  }

  it should "dispatch continuations one per request up to the per-peer cap, one slot on probation, and none while gated" taggedAs UnitTest in {
    val h = new Harness()
    val m = h.module
    val p = peer("p1")
    (1 to 4).foreach(i => m.tasks.enqueue(continuationTask(i)))

    // maxInFlightPerPeer = 2.
    h.call(_.dispatchIfPossible(p))
    m.activeTasks.size shouldBe 2
    m.tasks.size shouldBe 2
    h.sentTo() shouldBe Seq(p.id, p.id)

    // A peer demoted for repeated timeouts gets one slot.
    val q = peer("p2")
    val now = System.currentTimeMillis()
    (1 to m.peerHealth.timeoutThreshold).foreach(_ => m.peerHealth.recordTimeout(q.id.value, now))
    m.peerHealth.isOnProbation(q.id.value) shouldBe true
    h.call(_.dispatchIfPossible(q))
    m.activeTasks.values.count(_._1 == q) shouldBe 1
    m.tasks.size shouldBe 1

    // Gated: a requested pivot refresh, the post-refresh cooldown, or a stateless peer.
    val r = peer("p3")
    m.pivotRefreshRequested = true
    h.call(_.dispatchIfPossible(r))
    m.pivotRefreshRequested = false
    m.postRefreshCooldownUntilMs = System.currentTimeMillis() + 1.hour.toMillis
    m.isPostRefreshCooldownActive shouldBe true
    h.call(_.dispatchIfPossible(r))
    m.postRefreshCooldownUntilMs = 0L
    m.statelessPeers += r.id.value
    h.call(_.dispatchIfPossible(r))
    m.activeTasks.values.count(_._1 == r) shouldBe 0
    m.tasks.size shouldBe 1
    h.sentTo() shouldBe Seq(q.id)
    h.cancelInFlight()
  }

  it should "on a timeout, cool and score the peer, halve its budget, re-queue the tasks once, and force-complete at the threshold" taggedAs UnitTest in {
    val h = new Harness()
    val m = h.module
    val p = peer("p1")
    val batch = Seq(continuationTask(1).copy(pending = true), continuationTask(2).copy(pending = true))
    m.activeTasks.put(BigInt(7), (p, batch, BigInt(1048576)))
    // One of the two is already queued again (an overlapping timeout): it is not enqueued twice.
    m.pendingTaskKeys += ((account(2), account(50)))
    m.consecutiveTaskFailures = 99

    h.call(_.handleTimeout(BigInt(7)))

    m.activeTasks shouldBe empty
    m.isPeerCoolingDown(p) shouldBe true
    m.peerHealth.consecutiveTimeouts(p.id.value) shouldBe 1
    m.peerResponseBytesTarget(p.id.value) shouldBe BigInt(524288)
    m.tasks.map(_.accountHash).toSeq shouldBe Seq(account(1))
    m.tasks.forall(!_.pending) shouldBe true
    m.pendingTaskKeys.toSet shouldBe Set((account(1), account(50)), (account(2), account(50)))
    m.consecutiveTaskFailures shouldBe 100
    h.selfMessages() shouldBe Seq(StorageRangeCoordinator.ForceCompleteStorage)

    // An unknown (already answered) request id changes nothing.
    h.call(_.handleTimeout(BigInt(8)))
    m.consecutiveTaskFailures shouldBe 100
    h.selfMessages() shouldBe empty
  }

  it should "revive a cooling peer when none is eligible, and escalate after five idle redispatch checks" taggedAs UnitTest in {
    val h = new Harness()
    val m = h.module
    val p = peer("p1")
    m.knownAvailablePeers += p
    m.tasks.enqueue(continuationTask(1))
    h.call(_.recordPeerCooldown(p, "timeout"))
    m.isPeerCoolingDown(p) shouldBe true

    h.call(_.tryRedispatchPendingTasks())
    m.peerCooldownUntilMs.contains(p.id.value) shouldBe false
    m.activeTasks.values.map(_._1).toSeq shouldBe Seq(p)
    h.sentTo() shouldBe Seq(p.id)
    h.cancelInFlight()

    // No known peer and nothing in flight: the fifth consecutive idle check asks for a pivot refresh.
    val idle = new Harness()
    idle.module.tasks.enqueue(continuationTask(1))
    idle.module.lastDispatchOrResponseMs = 0L
    (1 to 4).foreach(_ => idle.call(_.tryRedispatchPendingTasks()))
    idle.controller.hasMessages shouldBe false
    idle.call(_.tryRedispatchPendingTasks())
    idle.module.pivotRefreshRequested shouldBe true
    idle.controller.receiveAll().size shouldBe 1

    // While a refresh is requested, redispatch does nothing.
    idle.call(_.tryRedispatchPendingTasks())
    idle.module.activeTasks shouldBe empty
  }

  it should "adapt a peer's batch size and response budget within their bounds" taggedAs UnitTest in {
    val h = new Harness()
    val m = h.module
    val p = peer("p1")
    m.batchSizeFor(p) shouldBe 4
    h.call(_.reduceBatchSize(p))
    m.batchSizeFor(p) shouldBe 1
    // A single-account request never counts as packed.
    h.call(_.maybeIncreaseBatchSize(p, servedCount = 1, requestedCount = 1))
    m.peerBatchSuccessStreak.get(p.id.value) shouldBe None
    (1 to 3).foreach(_ => h.call(_.maybeIncreaseBatchSize(p, servedCount = 2, requestedCount = 2)))
    m.batchSizeFor(p) shouldBe 2
    m.peerBatchSuccessStreak(p.id.value) shouldBe 0

    h.call(_.adjustResponseBytesOnFailure(p, "timeout"))
    m.peerResponseBytesTarget(p.id.value) shouldBe BigInt(524288)
    m.adjustResponseBytesOnSuccess(p, requested = BigInt(524288), received = BigInt(524288))
    m.peerResponseBytesTarget(p.id.value) shouldBe BigInt(655360)
    // Under 90 % of the request: unchanged.
    m.adjustResponseBytesOnSuccess(p, requested = BigInt(655360), received = BigInt(100))
    m.peerResponseBytesTarget(p.id.value) shouldBe BigInt(655360)
    (1 to 10).foreach(_ => h.call(_.adjustResponseBytesOnFailure(p, "timeout")))
    m.peerResponseBytesTarget(p.id.value) shouldBe BigInt(131072)
  }
