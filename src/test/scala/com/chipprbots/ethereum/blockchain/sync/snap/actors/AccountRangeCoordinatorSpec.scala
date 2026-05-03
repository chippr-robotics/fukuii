package com.chipprbots.ethereum.blockchain.sync.snap.actors

import org.apache.pekko.actor.{ActorSystem, Status}
import org.apache.pekko.testkit.{TestActorRef, TestKit, TestProbe, ImplicitSender}
import org.apache.pekko.util.ByteString

import scala.concurrent.duration._

import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.snap._
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.testing.Tags._
import com.chipprbots.ethereum.testing.{PeerTestHelpers, TestMptStorage}

class AccountRangeCoordinatorSpec
    extends TestKit(ActorSystem("AccountRangeCoordinatorSpec"))
    with ImplicitSender
    with AnyFlatSpecLike
    with Matchers
    with BeforeAndAfterAll {

  override def afterAll(): Unit =
    TestKit.shutdownActorSystem(system)

  "AccountRangeCoordinator" should "initialize and create workers on demand" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("test-state-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(system.scheduler)
    val networkPeerManager = TestProbe()
    val snapSyncController = TestProbe()

    val coordinator = system.actorOf(
      AccountRangeCoordinator.props(
        stateRoot = stateRoot,
        networkPeerManager = networkPeerManager.ref,
        requestTracker = requestTracker,
        mptStorage = storage,
        concurrency = 4,
        snapSyncController = snapSyncController.ref
      )
    )

    coordinator ! Messages.StartAccountRangeSync(stateRoot)

    // Coordinator should be running
    coordinator should not be null
  }

  it should "distribute tasks to workers when peers are available" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("test-state-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(system.scheduler)
    val networkPeerManager = TestProbe()
    val snapSyncController = TestProbe()
    val peerProbe = TestProbe()

    val peer = PeerTestHelpers.createTestPeer("test-peer", peerProbe.ref)

    val coordinator = system.actorOf(
      AccountRangeCoordinator.props(
        stateRoot = stateRoot,
        networkPeerManager = networkPeerManager.ref,
        requestTracker = requestTracker,
        mptStorage = storage,
        concurrency = 4,
        snapSyncController = snapSyncController.ref
      )
    )

    coordinator ! Messages.StartAccountRangeSync(stateRoot)
    coordinator ! Messages.PeerAvailable(peer)

    // Should send request to network peer manager
    networkPeerManager.expectMsgType[Any](3.seconds)
  }

  it should "handle task completion and report progress" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("test-state-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(system.scheduler)
    val networkPeerManager = TestProbe()
    val snapSyncController = TestProbe()

    val coordinator = system.actorOf(
      AccountRangeCoordinator.props(
        stateRoot = stateRoot,
        networkPeerManager = networkPeerManager.ref,
        requestTracker = requestTracker,
        mptStorage = storage,
        concurrency = 4,
        snapSyncController = snapSyncController.ref
      )
    )

    coordinator ! Messages.StartAccountRangeSync(stateRoot)

    // Query progress
    coordinator ! Messages.GetProgress
    val progress = expectMsgType[AccountRangeStats](3.seconds)

    progress.accountsDownloaded shouldBe 0
    progress.tasksPending should be > 0
  }

  it should "report completion when all tasks are done" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("test-state-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(system.scheduler)
    val networkPeerManager = TestProbe()
    val snapSyncController = TestProbe()

    val coordinator = system.actorOf(
      AccountRangeCoordinator.props(
        stateRoot = stateRoot,
        networkPeerManager = networkPeerManager.ref,
        requestTracker = requestTracker,
        mptStorage = storage,
        concurrency = 1, // Small concurrency for test
        snapSyncController = snapSyncController.ref
      )
    )

    coordinator ! Messages.StartAccountRangeSync(stateRoot)

    // Check completion - tasks should exist initially
    coordinator ! Messages.CheckCompletion

    // Should not complete immediately (tasks pending)
    snapSyncController.expectNoMessage(500.milliseconds)
  }

  it should "handle task failures gracefully" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("test-state-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(system.scheduler)
    val networkPeerManager = TestProbe()
    val snapSyncController = TestProbe()

    val coordinator = system.actorOf(
      AccountRangeCoordinator.props(
        stateRoot = stateRoot,
        networkPeerManager = networkPeerManager.ref,
        requestTracker = requestTracker,
        mptStorage = storage,
        concurrency = 4,
        snapSyncController = snapSyncController.ref
      )
    )

    coordinator ! Messages.StartAccountRangeSync(stateRoot)

    // Simulate task failure
    coordinator ! Messages.TaskFailed(BigInt(123), "Test failure")

    // Coordinator should still be operational
    coordinator ! Messages.GetProgress
    expectMsgType[AccountRangeStats](3.seconds)
  }

  it should "collect contract accounts for bytecode download" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("test-state-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(system.scheduler)
    val networkPeerManager = TestProbe()
    val snapSyncController = TestProbe()

    val coordinator = system.actorOf(
      AccountRangeCoordinator.props(
        stateRoot = stateRoot,
        networkPeerManager = networkPeerManager.ref,
        requestTracker = requestTracker,
        mptStorage = storage,
        concurrency = 4,
        snapSyncController = snapSyncController.ref
      )
    )

    coordinator ! Messages.StartAccountRangeSync(stateRoot)

    // Query contract accounts
    coordinator ! Messages.GetContractAccounts
    val response = expectMsgType[Messages.ContractAccountsResponse](3.seconds)

    // Initially should be empty
    response.accounts shouldBe empty
  }

  it should "provide statistics on request" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("test-state-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(system.scheduler)
    val networkPeerManager = TestProbe()
    val snapSyncController = TestProbe()

    val coordinator = system.actorOf(
      AccountRangeCoordinator.props(
        stateRoot = stateRoot,
        networkPeerManager = networkPeerManager.ref,
        requestTracker = requestTracker,
        mptStorage = storage,
        concurrency = 4,
        snapSyncController = snapSyncController.ref
      )
    )

    coordinator ! Messages.StartAccountRangeSync(stateRoot)
    coordinator ! Messages.GetProgress

    val progress = expectMsgType[AccountRangeStats](3.seconds)
    progress.progress should be >= 0.0
    progress.progress should be <= 1.0
    progress.elapsedTimeMs should be >= 0L
  }

  // ── K5-ext-a: Empty account range completion --------------------------------

  it should "complete task on proof-only empty account range response" taggedAs UnitTest in {
    val stateRoot = ByteString(MerklePatriciaTrie.EmptyRootHash)
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(system.scheduler)
    val networkPeerManager = TestProbe()
    val snapSyncController = TestProbe()
    val peerProbe = TestProbe()

    val peer = PeerTestHelpers.createTestPeer("empty-range-peer", peerProbe.ref)

    val coordinator = system.actorOf(
      AccountRangeCoordinator.props(
        stateRoot = stateRoot,
        networkPeerManager = networkPeerManager.ref,
        requestTracker = requestTracker,
        mptStorage = storage,
        concurrency = 1,
        snapSyncController = snapSyncController.ref
      )
    )

    coordinator ! Messages.StartAccountRangeSync(stateRoot)
    coordinator ! Messages.PeerAvailable(peer)

    // Worker dispatches a GetAccountRange — consume to keep the probe clean.
    // SNAPRequestTracker starts at nextRequestId=1, so the first task is requestId=BigInt(1).
    networkPeerManager.expectMsgType[Any](3.seconds)

    // Simulate what the AccountRangeWorker sends back when it verifies a proof-only empty AccountRange.
    coordinator ! Messages.TaskComplete(BigInt(1), Right((0, Seq.empty, Seq(ByteString("boundary-proof")))))

    snapSyncController.expectMsgType[Messages.AccountRangeProgress](3.seconds)
    snapSyncController.expectMsg(3.seconds, SNAPSyncController.AccountRangeSyncComplete)
  }

  // ========================================
  // Async trie flush + finalisation generation guard (issue #1166)
  // ========================================

  /** Helper: build a TestActorRef so tests can poke `underlyingActor` state directly and inject `system.dispatcher` as
    * the trie EC for deterministic timing.
    */
  private def newCoordinator(
      stateRoot: ByteString = kec256(ByteString("trie-async-test-root")),
      controller: TestProbe = TestProbe()
  ): TestActorRef[AccountRangeCoordinator] =
    TestActorRef[AccountRangeCoordinator](
      AccountRangeCoordinator.props(
        stateRoot = stateRoot,
        networkPeerManager = TestProbe().ref,
        requestTracker = new SNAPRequestTracker()(system.scheduler),
        mptStorage = new TestMptStorage(),
        concurrency = 4,
        snapSyncController = controller.ref,
        accountTrieEcOverride = Some(system.dispatcher)
      )
    )

  it should "construct successfully when an account-trie EC override is supplied" taggedAs UnitTest in {
    val coord = newCoordinator()
    coord should not be null
    coord.underlyingActor.trieFlushGeneration shouldBe 0L
    system.stop(coord)
  }

  it should "drop a stale TrieFlushComplete (finalisation) silently — no controller messages" taggedAs UnitTest in {
    val controller = TestProbe()
    val coord = newCoordinator(controller = controller)

    // Force the actor into the `finalizing` receive without actually running the heavy work.
    // We simulate post-spawn state: bump the generation and capture it, then bump again so an
    // arriving TrieFlushComplete with the captured generation looks stale.
    coord.underlyingActor.trieFlushGeneration = 7L
    coord.underlyingActor.context.become(coord.underlyingActor.finalizing)

    val staleGen = 5L

    coord ! AccountRangeCoordinator.TrieFlushComplete(staleGen, Right(ByteString("would-be-root")))

    // Stale → controller hears nothing.
    controller.expectNoMessage(300.millis)
    coord.underlyingActor.trieFlushGeneration shouldBe 7L

    system.stop(coord)
  }

  it should "honour a current-generation TrieFlushComplete and notify the controller" taggedAs UnitTest in {
    val controller = TestProbe()
    val coord = newCoordinator(controller = controller)

    coord.underlyingActor.trieFlushGeneration = 3L
    coord.underlyingActor.context.become(coord.underlyingActor.finalizing)

    val rootHash = ByteString(Array.fill[Byte](32)(0x42))
    coord ! AccountRangeCoordinator.TrieFlushComplete(3L, Right(rootHash))

    controller.expectMsg(2.seconds, SNAPSyncController.AccountTrieFinalized(rootHash))
    controller.expectMsg(2.seconds, SNAPSyncController.ProgressAccountsTrieFinalized)
  }

  it should "drop a stale TrieFlushAsyncComplete (periodic flush) and return to normal receive" taggedAs UnitTest in {
    val controller = TestProbe()
    val coord = newCoordinator(controller = controller)

    coord.underlyingActor.trieFlushGeneration = 4L
    coord.underlyingActor.context.become(coord.underlyingActor.flushing)

    val staleGen = 2L

    coord ! AccountRangeCoordinator.TrieFlushAsyncComplete(staleGen, persistedRoot = None, elapsedMs = 5L)

    // The handler self-sends CheckCompletion, which can produce a ProgressAccountEstimate when
    // tasks exist — for a fresh coordinator with empty tasks no message is emitted.
    controller.expectNoMessage(300.millis)

    // After dropping the stale completion the actor must be back on the normal receive,
    // i.e. ready to handle a regular query.
    coord ! Messages.GetProgress
    expectMsgType[AccountRangeStats](2.seconds)

    system.stop(coord)
  }

  it should "drop a stale TrieFlushAsyncFailed and return to normal receive" taggedAs UnitTest in {
    val controller = TestProbe()
    val coord = newCoordinator(controller = controller)

    coord.underlyingActor.trieFlushGeneration = 9L
    coord.underlyingActor.context.become(coord.underlyingActor.flushing)

    coord ! AccountRangeCoordinator.TrieFlushAsyncFailed(generation = 8L, error = "stale failure")

    controller.expectNoMessage(300.millis)

    // Confirm we left `flushing` and are back on normal receive.
    coord ! Messages.GetProgress
    expectMsgType[AccountRangeStats](2.seconds)

    system.stop(coord)
  }

  it should "ignore Status.Failure during finalisation by stopping the actor" taggedAs UnitTest in {
    val controller = TestProbe()
    val coord = newCoordinator(controller = controller)

    coord.underlyingActor.trieFlushGeneration = 1L
    coord.underlyingActor.context.become(coord.underlyingActor.finalizing)

    val watcher = TestProbe()
    watcher.watch(coord)

    coord ! Status.Failure(new RuntimeException("synthetic failure"))

    // Staging escalates trie-finalisation failures via AccountTrieFinalizationFailed (carries the
    // exception message so SyncController can decide whether to restart SNAP). The actor still
    // stops itself on this terminal path.
    controller.expectMsg(2.seconds, SNAPSyncController.AccountTrieFinalizationFailed("synthetic failure"))
    watcher.expectTerminated(coord, 2.seconds)
  }

  // ========================================
  // activeTasks leak fix (#1184)
  // ========================================

  /** Seed an `activeTasks` slot with a `TestProbe` worker. Adds the probe to `workers` too, otherwise `markWorkerIdle`
    * is a silent no-op (`workers.contains` guard) and the "is the worker reusable after drain?" assertion can't be
    * made.
    */
  private def seedActiveTask(
      coord: TestActorRef[AccountRangeCoordinator],
      reqId: BigInt,
      peer: Peer,
      rootHash: ByteString = kec256(ByteString("active-tasks-test-root"))
  ): (AccountTask, TestProbe) = {
    val workerProbe = TestProbe()
    val ua = coord.underlyingActor
    val task = AccountTask(
      next = ByteString(Array.fill[Byte](32)(0x00)),
      last = ByteString(Array.fill[Byte](32)(0xff.toByte)),
      rootHash = rootHash
    )
    task.pending = true
    ua.workers += workerProbe.ref
    ua.idleWorkers -= workerProbe.ref
    ua.activeTasks.put(reqId, (task, workerProbe.ref, peer))
    (task, workerProbe)
  }

  it should "drain activeTasks for a specific peer on PeerUnavailable (#1184)" taggedAs UnitTest in {
    val coord = newCoordinator()
    val pendingProbeA = TestProbe()
    val pendingProbeB = TestProbe()
    val peerA = PeerTestHelpers.createTestPeer("active-tasks-peerA", pendingProbeA.ref)
    val peerB = PeerTestHelpers.createTestPeer("active-tasks-peerB", pendingProbeB.ref)

    val (taskA1, workerA1) = seedActiveTask(coord, BigInt(101), peerA)
    val (taskA2, workerA2) = seedActiveTask(coord, BigInt(102), peerA)
    val (_, workerB1) = seedActiveTask(coord, BigInt(103), peerB)
    val ua = coord.underlyingActor
    val pendingBefore = ua.pendingTasks.size

    coord ! Messages.PeerUnavailable(peerA.id.value)

    // peerA's two slots drained; peerB's single slot untouched.
    ua.activeTasks.size shouldBe 1
    ua.activeTasks.contains(BigInt(103)) shouldBe true
    ua.pendingTasks.size shouldBe (pendingBefore + 2)
    taskA1.pending shouldBe false
    taskA2.pending shouldBe false

    // Drained workers received WorkerRequestCancelled (with their own reqId).
    workerA1.expectMsg(2.seconds, Messages.WorkerRequestCancelled(BigInt(101)))
    workerA2.expectMsg(2.seconds, Messages.WorkerRequestCancelled(BigInt(102)))
    workerB1.expectNoMessage(300.millis)

    // Drained workers are back in idleWorkers, ready for reuse.
    ua.idleWorkers should contain(workerA1.ref)
    ua.idleWorkers should contain(workerA2.ref)

    system.stop(coord)
  }

  it should "drain all activeTasks on RecoverStalledAccountTasks and reset the activity timer" taggedAs UnitTest in {
    val coord = newCoordinator()
    val ua = coord.underlyingActor
    val pendingProbe = TestProbe()
    val peer = PeerTestHelpers.createTestPeer("recover-stalled-peer", pendingProbe.ref)

    val (_, w1) = seedActiveTask(coord, BigInt(201), peer)
    val (_, w2) = seedActiveTask(coord, BigInt(202), peer)
    val (_, w3) = seedActiveTask(coord, BigInt(203), peer)
    val pendingBefore = ua.pendingTasks.size

    // Mark the activity timer as stale so we can verify it gets reset.
    ua.lastDispatchOrResponseMs = 1L
    coord ! Messages.RecoverStalledAccountTasks

    ua.activeTasks shouldBe empty
    ua.pendingTasks.size shouldBe (pendingBefore + 3)
    w1.expectMsgType[Messages.WorkerRequestCancelled](2.seconds)
    w2.expectMsgType[Messages.WorkerRequestCancelled](2.seconds)
    w3.expectMsgType[Messages.WorkerRequestCancelled](2.seconds)
    ua.lastDispatchOrResponseMs should be > 1L

    system.stop(coord)
  }

  it should "fire CheckDispatchStalled on activeTasks.nonEmpty + no activity (matches the observed wedge)" taggedAs UnitTest in {
    val coord = newCoordinator()
    val ua = coord.underlyingActor
    val pendingProbe = TestProbe()
    val peer = PeerTestHelpers.createTestPeer("stalled-peer", pendingProbe.ref)

    // Drain the queue so pendingTasks is empty (matches the observed `tasksPending=0,
    // tasksActive=1` symptom that the earlier `pendingTasks.nonEmpty` requirement would
    // have missed).
    while (ua.pendingTasks.nonEmpty) ua.pendingTasks.dequeue()
    val (_, worker) = seedActiveTask(coord, BigInt(301), peer)

    // Force a stale activity timestamp (>90 s ago).
    ua.lastDispatchOrResponseMs = System.currentTimeMillis() - 100_000L
    val timestampBefore = ua.lastDispatchOrResponseMs

    coord ! Messages.CheckDispatchStalled

    ua.activeTasks shouldBe empty
    ua.pendingTasks.size shouldBe 1
    worker.expectMsg(2.seconds, Messages.WorkerRequestCancelled(BigInt(301)))
    ua.lastDispatchOrResponseMs should be > timestampBefore

    system.stop(coord)
  }

  it should "drain activeTasks AND re-tag pendingTasks rootHash on PivotRefreshed (#1184)" taggedAs UnitTest in {
    val coord = newCoordinator()
    val ua = coord.underlyingActor
    val pendingProbe = TestProbe()
    val peer = PeerTestHelpers.createTestPeer("pivot-refresh-peer", pendingProbe.ref)
    val oldRoot = kec256(ByteString("pivot-refresh-old-root"))
    val newRoot = kec256(ByteString("pivot-refresh-new-root"))

    val (taskA, _) = seedActiveTask(coord, BigInt(401), peer, rootHash = oldRoot)
    val (taskB, _) = seedActiveTask(coord, BigInt(402), peer, rootHash = oldRoot)

    coord ! Messages.PivotRefreshed(newRoot)

    ua.activeTasks shouldBe empty
    // Both drained tasks ended up in pendingTasks (with the rest of the initial task set).
    val drainedRoots = Seq(taskA.rootHash, taskB.rootHash)
    drainedRoots.foreach(_ shouldBe newRoot)
    // All pendingTasks now carry the new root.
    ua.pendingTasks.foreach(_.rootHash shouldBe newRoot)

    system.stop(coord)
  }

  it should "treat a duplicate PeerUnavailable as a clean no-op" taggedAs UnitTest in {
    val coord = newCoordinator()
    val ua = coord.underlyingActor
    val pendingProbe = TestProbe()
    val peer = PeerTestHelpers.createTestPeer("idempotent-peer", pendingProbe.ref)

    seedActiveTask(coord, BigInt(501), peer)
    seedActiveTask(coord, BigInt(502), peer)
    seedActiveTask(coord, BigInt(503), peer)
    val pendingBefore = ua.pendingTasks.size

    coord ! Messages.PeerUnavailable(peer.id.value)
    val pendingAfterFirst = ua.pendingTasks.size

    coord ! Messages.PeerUnavailable(peer.id.value)
    coord ! Messages.GetProgress
    expectMsgType[AccountRangeStats](2.seconds) // forces sequential processing

    ua.pendingTasks.size shouldBe pendingAfterFirst
    pendingAfterFirst shouldBe (pendingBefore + 3)

    system.stop(coord)
  }

  it should "treat a late TaskFailed after drain as a no-op" taggedAs UnitTest in {
    val coord = newCoordinator()
    val ua = coord.underlyingActor
    val pendingProbe = TestProbe()
    val peer = PeerTestHelpers.createTestPeer("late-taskfailed-peer", pendingProbe.ref)

    seedActiveTask(coord, BigInt(601), peer)
    val pendingBefore = ua.pendingTasks.size

    coord ! Messages.PeerUnavailable(peer.id.value)
    val pendingAfterDrain = ua.pendingTasks.size
    pendingAfterDrain shouldBe (pendingBefore + 1)

    // Late TaskFailed for the already-drained slot — handleTaskFailed's
    // activeTasks.remove(...).foreach makes this a no-op.
    coord ! Messages.TaskFailed(BigInt(601), "stale response")
    coord ! Messages.GetProgress
    expectMsgType[AccountRangeStats](2.seconds) // forces sequential processing

    ua.activeTasks shouldBe empty
    ua.pendingTasks.size shouldBe pendingAfterDrain

    system.stop(coord)
  }

  it should "redispatch through a drained worker without TaskFailed(0, \"Worker busy\") (#1184 worker-reuse race)" taggedAs UnitTest in {
    // End-to-end test using REAL coordinator-created workers (not seeded probes) so the
    // assertion observes actual network-send behaviour. Without WorkerRequestCancelled
    // the redispatch step would drive the worker (still in `working` state) through
    // line 162's "Worker is busy, cannot accept new task" branch and emit
    // TaskFailed(0, "Worker busy") — the canonical leak-fix-incomplete signature.
    val stateRoot = kec256(ByteString("worker-reuse-test-root"))
    val networkPeerManager = TestProbe()
    val peerProbeA = TestProbe()
    val peerProbeB = TestProbe()
    val peerA = PeerTestHelpers.createTestPeer("reuse-peerA", peerProbeA.ref)
    val peerB = PeerTestHelpers.createTestPeer("reuse-peerB", peerProbeB.ref)

    // Real coordinator (not the TestActorRef helper — we don't need underlyingActor here).
    val syncController = TestProbe()
    val coordinator = system.actorOf(
      AccountRangeCoordinator.props(
        stateRoot = stateRoot,
        networkPeerManager = networkPeerManager.ref,
        requestTracker = new SNAPRequestTracker()(system.scheduler),
        mptStorage = new TestMptStorage(),
        concurrency = 1, // exactly one worker so reuse is unambiguous
        snapSyncController = syncController.ref
      )
    )

    coordinator ! Messages.StartAccountRangeSync(stateRoot)
    coordinator ! Messages.PeerAvailable(peerA)

    // First dispatch: real worker → networkPeerManager receives a SendMessage.
    networkPeerManager.expectMsgType[Any](3.seconds)

    // Drain via PeerUnavailable. WorkerRequestCancelled goes to the worker (clears
    // currentTask, become(idle)); coordinator re-queues the task.
    coordinator ! Messages.PeerUnavailable(peerA.id.value)

    // Second dispatch via a fresh peer. Without the worker-reuse fix, the still-busy worker
    // would emit TaskFailed(0, "Worker busy") instead of dispatching. We assert that we DO
    // see a second network send.
    coordinator ! Messages.PeerAvailable(peerB)
    networkPeerManager.expectMsgType[Any](3.seconds)

    // Drain side: the syncController probe never sees a "Worker busy" failure escalation.
    // (No specific message for that, but if the cancel had failed the redispatch path
    // wouldn't have produced the second SendMessage above.)

    system.stop(coordinator)
  }
}
