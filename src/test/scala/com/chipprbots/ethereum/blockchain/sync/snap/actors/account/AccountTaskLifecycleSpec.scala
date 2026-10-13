package com.chipprbots.ethereum.blockchain.sync.snap.actors.account

import java.net.InetSocketAddress

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
import com.chipprbots.ethereum.blockchain.sync.snap.actors.AccountRangeCoordinator.StoreAccountChunk
import com.chipprbots.ethereum.blockchain.sync.snap.actors.AccountRangeCoordinator.WorkerRef
import com.chipprbots.ethereum.blockchain.sync.snap.actors.AccountRangeCoordinator.WorkerRequestCancelled
import com.chipprbots.ethereum.blockchain.sync.snap.actors.AccountRangeWorker
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerActor
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.testing.Tags.UnitTest

/** Everything `AccountTaskLifecycle` needs, as a stub (spec 016 FR-016 (b), C-A2): its `AccountTaskLifecycleState`, the
  * coordinator's `AccountRangeEnv`, and the `AccountPeerDispatchApi`, `AccountProgressApi` and `ContractWorkCarryApi`
  * it calls. State is plain fields; the Api members record their calls and answer from the state. The context is a
  * `BehaviorTestKit`'s, so messages to `ctx.self` land in its self inbox.
  */
private[actors] class StubAccountTaskLifecycleState(
    val ctx: ActorContext[AccountRangeCoordinator.Command],
    val timers: TimerScheduler[AccountRangeCoordinator.Command],
    val networkPeerManager: ActorRef[NetworkPeerManagerActor.Command],
    val requestTracker: SNAPRequestTracker,
    val snapSyncController: ActorRef[SNAPSyncController.Command]
) extends AccountTaskLifecycleState
    with AccountRangeEnv
    with AccountPeerDispatchApi
    with AccountProgressApi
    with ContractWorkCarryApi:

  // AccountTaskLifecycleState
  var stateRoot: ByteString = ByteString(Array.fill[Byte](32)(7))
  var maxInFlightPerPeer: Int = 2
  val statelessPeers: mutable.Set[PeerId] = mutable.Set.empty
  val snaplessPeers: mutable.Set[PeerId] = mutable.Set.empty
  val emptyResponseStrikes: mutable.Map[PeerId, Int] = mutable.Map.empty
  var pivotRefreshRequested: Boolean = false
  val pendingTasks: mutable.PriorityQueue[AccountTask] =
    mutable.PriorityQueue.empty[AccountTask](Ordering.by[AccountTask, BigInt](_.remainingKeyspace).reverse)
  val activeTasks: mutable.Map[BigInt, (AccountTask, WorkerRef, Peer)] = mutable.Map.empty
  val completedTasks: mutable.ArrayBuffer[AccountTask] = mutable.ArrayBuffer.empty
  var lastDispatchOrResponseMs: Long = 0L
  var accountsDownloaded: Long = 0L
  var bytesDownloaded: Long = 0L
  var consumedKeyspace: BigInt = BigInt(0)
  val storingTasks: mutable.Map[ByteString, AccountTask] = mutable.Map.empty
  val knownAvailablePeers: mutable.Set[Peer] = mutable.Set.empty
  val peerCooldownUntilMs: mutable.Map[String, Long] = mutable.Map.empty
  val lastDispatchTimeMs: mutable.Map[String, Long] = mutable.Map.empty
  var consecutiveUnproductiveRefreshes: Int = 0

  // AccountRangeEnv (ctx, timers, networkPeerManager, requestTracker and snapSyncController are constructor vals)
  val log: org.slf4j.Logger = org.slf4j.LoggerFactory.getLogger(classOf[StubAccountTaskLifecycleState])
  val concurrency: Int = 1
  val initialResponseBytesConfig: Int = 524288
  val minResponseBytesConfig: Int = 102400
  val intakeBudget: Option[SnapIntakeBudget] = None
  val futureLog: org.slf4j.Logger = log
  val resumeProgress: Map[ByteString, ByteString] = Map.empty
  val storageScheme: StorageScheme = StorageScheme.Hash
  def mptStorage: MptStorage = throw new UnsupportedOperationException("AccountTaskLifecycle read mptStorage")

  // AccountPeerDispatchApi
  val markedStateless: mutable.ArrayBuffer[(PeerId, String)] = mutable.ArrayBuffer.empty
  val successes: mutable.ArrayBuffer[PeerId] = mutable.ArrayBuffer.empty
  val cooldowns: mutable.ArrayBuffer[(String, FiniteDuration)] = mutable.ArrayBuffer.empty
  var successAdjustments: Int = 0
  val failureAdjustments: mutable.ArrayBuffer[String] = mutable.ArrayBuffer.empty
  val idled: mutable.ArrayBuffer[WorkerRef] = mutable.ArrayBuffer.empty
  val dispatched: mutable.ArrayBuffer[String] = mutable.ArrayBuffer.empty
  def isPeerStateless(peer: Peer): Boolean = statelessPeers.contains(peer.id)
  def isPeerSnapless(peer: Peer): Boolean = snaplessPeers.contains(peer.id)
  def markPeerStateless(peer: Peer, reason: String): Unit = markedStateless += ((peer.id, reason))
  def recordPeerSuccess(peerId: PeerId): Unit = successes += peerId
  def effectivePeerCooldown: FiniteDuration = 8.seconds
  def isPeerCoolingDown(peer: Peer): Boolean =
    peerCooldownUntilMs.get(peer.id.value).exists(_ > System.currentTimeMillis())
  def recordPeerCooldown(peer: Peer, reason: String, duration: FiniteDuration): Unit =
    cooldowns += ((peer.id.value, duration))
  def responseBytesTargetFor(peer: Peer): BigInt = BigInt(524288)
  def adjustResponseBytesOnSuccess(peer: Peer, requested: BigInt, received: BigInt): Unit = successAdjustments += 1
  def adjustResponseBytesOnFailure(peer: Peer, reason: String): Unit = failureAdjustments += peer.id.value
  def inFlightForPeer(peer: Peer): Int = activeTasks.values.count(_._3.id == peer.id)
  def markWorkerIdle(worker: WorkerRef): Unit = idled += worker
  def dispatchIfPossible(peer: Peer): Unit = dispatched += peer.id.value

  // AccountProgressApi
  val snapshots: mutable.ArrayBuffer[Boolean] = mutable.ArrayBuffer.empty
  def sendProgressSnapshot(durableRequested: Boolean = false): Unit = snapshots += durableRequested

  // ContractWorkCarryApi
  val identified: mutable.ArrayBuffer[Int] = mutable.ArrayBuffer.empty
  def identifyContractAccounts(accounts: Seq[(ByteString, Account)]): Unit = identified += accounts.size

/** Spec 016 C-A2 stub test (FR-016 (b), T063): `AccountTaskLifecycle` mixed into a stub of its state interface, the
  * coordinator environment and the Apis it calls, inside a `BehaviorTestKit` so that the context is real. The cases
  * call the module's task-complete, task-failed, requeue, escalation, redispatch and drain bodies, which read and write
  * `AccountTaskLifecycleState`.
  */
class AccountTaskLifecycleSpec extends ScalaTestWithActorTestKit with AnyFlatSpecLike with Matchers:

  /** The module on its stub, the kit that runs it, and the inboxes it talks to. */
  final private class Harness:
    val controller: TestInbox[SNAPSyncController.Command] = TestInbox[SNAPSyncController.Command]()
    private val peerManager = TestInbox[NetworkPeerManagerActor.Command]()
    private val tracker = new SNAPRequestTracker()(system.classicSystem.scheduler)
    private var action: () => Unit = () => ()
    private var captured: StubAccountTaskLifecycleState & AccountTaskLifecycle = null
    val kit: BehaviorTestKit[AccountRangeCoordinator.Command] =
      BehaviorTestKit(Behaviors.setup[AccountRangeCoordinator.Command] { ctx =>
        Behaviors.withTimers { timers =>
          captured = new StubAccountTaskLifecycleState(ctx, timers, peerManager.ref, tracker, controller.ref)
            with AccountTaskLifecycle
          Behaviors.receiveMessage { _ =>
            action()
            Behaviors.same
          }
        }
      })
    val module: StubAccountTaskLifecycleState & AccountTaskLifecycle = captured

    /** Run `f` on the module while the kit processes a message, as the coordinator's arms do. */
    def call(f: StubAccountTaskLifecycleState & AccountTaskLifecycle => Unit): Unit =
      action = () => f(module)
      kit.run(AccountRangeCoordinator.CheckCompletion)

    /** What the module sent to `ctx.self`. */
    def selfMessages(): Seq[AccountRangeCoordinator.Command] = kit.selfInbox().receiveAll()

  private def peer(id: String): Peer =
    Peer(PeerId(id), new InetSocketAddress("127.0.0.1", 30303), TestInbox[PeerActor.Command]().ref, false)

  private def hash(last: Int): ByteString = ByteString(Array.fill[Byte](31)(0) :+ last.toByte)

  /** The whole keyspace as one task, at the stub's state root. */
  private def wholeRange(m: StubAccountTaskLifecycleState): AccountTask =
    AccountTask.createInitialTasks(m.stateRoot, 1).head

  "AccountTaskLifecycle" should "advance a completed task's cursor past its last account and hand the accounts to the chunked store" taggedAs UnitTest in {
    val h = new Harness
    val m = h.module
    val p = peer("p1")
    val w = TestInbox[AccountRangeWorker.Command]()
    val t = wholeRange(m)
    m.activeTasks(BigInt(1)) = (t.copy(pending = true, requeueCount = 3), w.ref, p)
    m.consecutiveUnproductiveRefreshes = 2
    val accounts = Seq(hash(1) -> Account(), hash(5) -> Account())

    h.call(_.handleTaskComplete(BigInt(1), Right((2, accounts, Seq.empty))))

    val advanced = t.copy(next = hash(6))
    m.activeTasks shouldBe empty
    m.idled.toSeq shouldBe Seq(w.ref)
    m.lastDispatchOrResponseMs should be > 0L
    m.successAdjustments shouldBe 1
    m.successes.toSeq shouldBe Seq(p.id)
    m.consecutiveUnproductiveRefreshes shouldBe 0
    m.identified.toSeq shouldBe Seq(2)
    m.consumedKeyspace shouldBe BigInt(6)
    m.bytesDownloaded shouldBe 128L
    m.storingTasks.toMap shouldBe Map(t.last -> advanced)
    h.selfMessages() shouldBe Seq(
      StoreAccountChunk(advanced, accounts, 2, storedSoFar = 0, isTaskRangeComplete = false)
    )
  }

  it should "complete a range on an empty response with a proof of absence, durably" taggedAs UnitTest in {
    val h = new Harness
    val m = h.module
    val p = peer("p1")
    val w = TestInbox[AccountRangeWorker.Command]()
    val t = wholeRange(m)
    m.activeTasks(BigInt(1)) = (t.copy(pending = true), w.ref, p)

    h.call(_.handleTaskComplete(BigInt(1), Right((0, Seq.empty, Seq(ByteString(Array[Byte](1, 2, 3)))))))

    m.completedTasks.toSeq shouldBe Seq(t.copy(next = t.last, done = true))
    m.consumedKeyspace shouldBe t.remainingKeyspace
    m.snapshots.toSeq shouldBe Seq(true)
    m.successes.toSeq shouldBe Seq(p.id)
    m.pendingTasks shouldBe empty
    h.selfMessages() shouldBe Seq(AccountRangeCoordinator.CheckCompletion)
  }

  it should "cool the peer and requeue on an empty response without proof or an error, and ignore an unknown request" taggedAs UnitTest in {
    val h = new Harness
    val m = h.module
    val p = peer("p1")
    val w = TestInbox[AccountRangeWorker.Command]()
    val t = wholeRange(m)
    m.activeTasks(BigInt(1)) = (t.copy(pending = true), w.ref, p)
    m.consecutiveUnproductiveRefreshes = 2

    h.call(_.handleTaskComplete(BigInt(1), Right((0, Seq.empty, Seq.empty))))
    m.cooldowns.toSeq shouldBe Seq(p.id.value -> 5.seconds)
    m.successes shouldBe empty
    m.consecutiveUnproductiveRefreshes shouldBe 2
    m.pendingTasks.dequeue() shouldBe t.copy(requeueCount = 1)

    m.activeTasks(BigInt(2)) = (t.copy(pending = true), w.ref, p)
    h.call(_.handleTaskComplete(BigInt(2), Left("boom")))
    m.pendingTasks.dequeue() shouldBe t.copy(requeueCount = 1)
    m.cooldowns.size shouldBe 1

    h.call(_.handleTaskComplete(BigInt(99), Right((0, Seq.empty, Seq.empty))))
    m.pendingTasks shouldBe empty
    m.idled.size shouldBe 2
    h.selfMessages() shouldBe empty
  }

  it should "mark the peer stateless only for a current-root failure, and cool it unless it disconnected" taggedAs UnitTest in {
    val h = new Harness
    val m = h.module
    val p = peer("p1")
    val w = TestInbox[AccountRangeWorker.Command]()
    val t = wholeRange(m)

    m.activeTasks(BigInt(1)) = (t.copy(pending = true), w.ref, p)
    h.call(_.handleTaskFailed(BigInt(1), "timeout"))
    m.markedStateless.toSeq shouldBe Seq(p.id -> "timeout")
    m.cooldowns.toSeq shouldBe Seq(p.id.value -> 8.seconds)
    m.failureAdjustments.toSeq shouldBe Seq(p.id.value)
    m.pendingTasks.dequeue() shouldBe t.copy(requeueCount = 1)

    // A request at a superseded root: no stateless mark, and the requeued task takes the current root.
    val staleRoot = ByteString(Array.fill[Byte](32)(9))
    m.activeTasks(BigInt(2)) = (t.copy(pending = true, rootHash = staleRoot), w.ref, p)
    h.call(_.handleTaskFailed(BigInt(2), "Peer disconnected"))
    m.markedStateless.size shouldBe 1
    m.cooldowns.size shouldBe 1
    m.failureAdjustments.size shouldBe 1
    m.pendingTasks.dequeue() shouldBe t.copy(requeueCount = 1)
    m.idled.toSeq shouldBe Seq(w.ref, w.ref)
    m.lastDispatchOrResponseMs should be > 0L
  }

  it should "escalate once per pivot when a task exhausts its requeue budget, with the cause the pool's evidence gives" taggedAs UnitTest in {
    val h = new Harness
    val m = h.module
    val p = peer("p1")
    val w = TestInbox[AccountRangeWorker.Command]()
    val t = wholeRange(m)
    val exhausted = t.copy(pending = true, requeueCount = AccountRangeCoordinator.MaxRequeuesPerTask)
    def unservable(cause: SNAPSyncController.UnservableCause) =
      SNAPSyncController.PivotStateUnservable(
        rootHash = m.stateRoot,
        reason = s"task ${t.rangeString} hit MaxRequeuesPerTask: Peer disconnected",
        consecutiveEmptyResponses = AccountRangeCoordinator.MaxRequeuesPerTask,
        cause = cause
      )

    m.activeTasks(BigInt(1)) = (exhausted, w.ref, p)
    h.call(_.handleTaskFailed(BigInt(1), "Peer disconnected"))
    h.controller.receiveAll() shouldBe Seq(unservable(SNAPSyncController.UnservableCause.PeerScarcity))
    m.pivotRefreshRequested shouldBe true
    m.pendingTasks.dequeue() shouldBe t

    // Already requested for this pivot: requeued, not escalated again.
    m.activeTasks(BigInt(2)) = (exhausted, w.ref, p)
    h.call(_.handleTaskFailed(BigInt(2), "Peer disconnected"))
    h.controller.hasMessages shouldBe false
    m.pendingTasks.dequeue() shouldBe t

    // What PivotRefreshed does in the core; with strike evidence in the pool the cause is Stateless.
    m.pivotRefreshRequested = false
    m.emptyResponseStrikes(p.id) = 1
    m.activeTasks(BigInt(3)) = (exhausted, w.ref, p)
    h.call(_.handleTaskFailed(BigInt(3), "Peer disconnected"))
    h.controller.receiveAll() shouldBe Seq(unservable(SNAPSyncController.UnservableCause.Stateless))
  }

  it should "redispatch to eligible peers, least loaded first, and revive the soonest cooling peer when none is eligible" taggedAs UnitTest in {
    val h = new Harness
    val m = h.module
    val (a, b, c, d, e) = (peer("a"), peer("b"), peer("c"), peer("d"), peer("e"))
    val w = TestInbox[AccountRangeWorker.Command]()
    val now = System.currentTimeMillis()
    m.knownAvailablePeers ++= Seq(a, b, c, d, e)
    m.statelessPeers += b.id
    m.snaplessPeers += c.id
    m.peerCooldownUntilMs(d.id.value) = now + 60000L
    m.activeTasks(BigInt(1)) = (wholeRange(m), w.ref, a)

    // Nothing pending: nothing to do.
    h.call(_.tryRedispatchPendingTasks())
    m.dispatched shouldBe empty

    m.pendingTasks.enqueue(wholeRange(m))
    h.call(_.tryRedispatchPendingTasks())
    m.dispatched.toSeq shouldBe Seq("e", "a")

    val h2 = new Harness
    val m2 = h2.module
    m2.knownAvailablePeers ++= Seq(a, b, c)
    m2.peerCooldownUntilMs ++= Seq(a.id.value -> (now + 60000L), b.id.value -> (now + 30000L))
    m2.peerCooldownUntilMs(c.id.value) = now + 10000L
    m2.statelessPeers += c.id
    m2.pendingTasks.enqueue(wholeRange(m2))
    h2.call(_.tryRedispatchPendingTasks())
    m2.dispatched.toSeq shouldBe Seq("b")
    m2.peerCooldownUntilMs.keySet shouldBe Set(a.id.value, c.id.value)
  }

  it should "drain one peer's or every in-flight request back to the queue, cancelling each worker's request" taggedAs UnitTest in {
    val h = new Harness
    val m = h.module
    val (a, b) = (peer("a"), peer("b"))
    def worker() = TestInbox[AccountRangeWorker.Command]()
    val (w1, w2, w3) = (worker(), worker(), worker())
    val tasks = AccountTask.createInitialTasks(m.stateRoot, 3).map(_.copy(pending = true))
    m.activeTasks(BigInt(1)) = (tasks(0), w1.ref, a)
    m.activeTasks(BigInt(2)) = (tasks(1), w2.ref, b)
    m.activeTasks(BigInt(3)) = (tasks(2), w3.ref, a)

    var drained = 0
    h.call(module => drained = module.drainActiveTasks("peer a gone", Some(a.id.value)))
    drained shouldBe 2
    m.activeTasks.keySet shouldBe Set(BigInt(2))
    w1.receiveAll() shouldBe Seq(WorkerRequestCancelled(BigInt(1)))
    w3.receiveAll() shouldBe Seq(WorkerRequestCancelled(BigInt(3)))
    w2.hasMessages shouldBe false
    m.idled.toSet shouldBe Set(w1.ref, w3.ref)
    m.pendingTasks.toSet shouldBe Set(tasks(0), tasks(2)).map(_.copy(pending = false))

    h.call(module => drained = module.drainActiveTasks("dispatch stalled"))
    drained shouldBe 1
    m.activeTasks shouldBe empty
    w2.receiveAll() shouldBe Seq(WorkerRequestCancelled(BigInt(2)))
    m.pendingTasks.size shouldBe 3
    m.pendingTasks.forall(!_.pending) shouldBe true

    h.call(module => drained = module.drainActiveTasks("dispatch stalled"))
    drained shouldBe 0
  }
