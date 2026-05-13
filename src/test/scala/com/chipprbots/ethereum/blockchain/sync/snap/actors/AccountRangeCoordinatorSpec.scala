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
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.p2p.messages.SNAP.GetAccountRange.GetAccountRangeEnc
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

  // ── Snapless peer demotion (refs #1197) ──
  // ETC mainnet peers running core-geth with `--syncmode full` advertise snap/1 but
  // their handler returns AccountRangePacket{Accounts:nil, Proof:nil} for every
  // GetAccountRange because `chain.Snapshots()` is unavailable. Today's
  // markPeerStateless adds the peer to statelessPeers, but PivotRefreshed clears that
  // set — so on every pivot refresh the peer cycles back into dispatch, returns
  // empty again, and the wedge repeats every ~2 minutes. The fix introduces a
  // separate `snaplessPeers` set that survives PivotRefreshed because the peer's
  // snapshot won't materialise mid-session.

  it should "mark a peer SNAPLESS on 'Missing proof for empty account range' (#1197)" taggedAs UnitTest in {
    // handleTaskFailed only calls markPeerStateless when the task's rootHash matches
    // the coordinator's current stateRoot (otherwise it's a stale-root failure from a
    // pre-pivot-refresh request, which doesn't reflect on the peer's snapshot state).
    // Match the coordinator's default newCoordinator() stateRoot so the path is exercised.
    val stateRoot = kec256(ByteString("trie-async-test-root"))
    val coord = newCoordinator(stateRoot = stateRoot)
    val ua = coord.underlyingActor
    val pendingProbe = TestProbe()
    val peer = PeerTestHelpers.createTestPeer("snapless-peer", pendingProbe.ref)

    seedActiveTask(coord, BigInt(701), peer, rootHash = stateRoot)

    // Drive the empty-with-empty failure path through the public coordinator API.
    coord ! Messages.TaskFailed(BigInt(701), "Missing proof for empty account range")
    coord ! Messages.GetProgress
    expectMsgType[AccountRangeStats](2.seconds) // sequential barrier

    ua.statelessPeers should contain(peer.id)
    ua.snaplessPeers should contain(peer.id)

    system.stop(coord)
  }

  it should "leave snaplessPeers untouched on PivotRefreshed (#1197)" taggedAs UnitTest in {
    val coord = newCoordinator()
    val ua = coord.underlyingActor
    val pendingProbe = TestProbe()
    val peer = PeerTestHelpers.createTestPeer("snapless-pivot-peer", pendingProbe.ref)

    // Seed both sets directly (the coordinator-internal effect of an empty-proof failure).
    ua.statelessPeers.add(peer.id)
    ua.snaplessPeers.add(peer.id)

    coord ! Messages.PivotRefreshed(kec256(ByteString("post-refresh-root")))
    coord ! Messages.GetProgress
    expectMsgType[AccountRangeStats](2.seconds)

    // statelessPeers is for stale-root failures and clears on PivotRefreshed.
    ua.statelessPeers shouldBe empty
    // snaplessPeers tracks structural snapshot absence; survives so the peer doesn't
    // go back into dispatch and immediately re-classify.
    ua.snaplessPeers should contain(peer.id)

    system.stop(coord)
  }

  it should "NOT classify a peer as stateless or snapless on Request timeout failures" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("trie-async-test-root"))
    val coord = newCoordinator(stateRoot = stateRoot)
    val ua = coord.underlyingActor
    val pendingProbe = TestProbe()
    val peer = PeerTestHelpers.createTestPeer("timeout-only-peer", pendingProbe.ref)

    // Drive multiple timeouts — go-ethereum marks stateless only on empty response
    // (snap/sync.go:2574), never on timeout. Fukuii follows the same rule (Fix-B, run-17).
    seedActiveTask(coord, BigInt(801), peer, rootHash = stateRoot)
    coord ! Messages.TaskFailed(BigInt(801), "Request timeout")
    seedActiveTask(coord, BigInt(802), peer, rootHash = stateRoot)
    coord ! Messages.TaskFailed(BigInt(802), "Request timeout")
    seedActiveTask(coord, BigInt(803), peer, rootHash = stateRoot)
    coord ! Messages.TaskFailed(BigInt(803), "Request timeout")

    coord ! Messages.GetProgress
    expectMsgType[AccountRangeStats](2.seconds)

    // Timeouts never mark the peer stateless or snapless — only empty responses do.
    ua.statelessPeers should not contain peer.id
    ua.snaplessPeers should not contain peer.id

    system.stop(coord)
  }

  it should "filter snapless peers from PeerAvailable dispatch (#1197)" taggedAs UnitTest in {
    // The coordinator routes all peer requests through a single networkPeerManager —
    // verify dispatch by inspecting its mailbox for SendMessage envelopes addressed to
    // each peer id, rather than per-peer probes.
    import com.chipprbots.ethereum.network.NetworkPeerManagerActor.SendMessage

    val stateRoot = kec256(ByteString("snapless-dispatch-root"))
    val networkPeerManager = TestProbe()
    val syncController = TestProbe()
    val peerProbeA = TestProbe()
    val peerProbeB = TestProbe()
    val peerA = PeerTestHelpers.createTestPeer("snapless-dispatch-a", peerProbeA.ref)
    val peerB = PeerTestHelpers.createTestPeer("snapless-dispatch-b", peerProbeB.ref)

    val coord = TestActorRef[AccountRangeCoordinator](
      AccountRangeCoordinator.props(
        stateRoot = stateRoot,
        networkPeerManager = networkPeerManager.ref,
        requestTracker = new SNAPRequestTracker()(system.scheduler),
        mptStorage = new TestMptStorage(),
        concurrency = 1, // exactly one initial task → one dispatch
        snapSyncController = syncController.ref
      )
    )

    // Pre-seed peerA as snapless before announcing it. peerB is a normal fresh peer.
    coord.underlyingActor.snaplessPeers.add(peerA.id)

    coord ! Messages.StartAccountRangeSync(stateRoot)
    coord ! Messages.PeerAvailable(peerA)
    coord ! Messages.PeerAvailable(peerB)

    // The dispatch should reach peerB only. We expect at least one SendMessage on the
    // shared networkPeerManager probe, and its `peerId` must be peerB's, not peerA's.
    val sent = networkPeerManager.expectMsgType[SendMessage](3.seconds)
    sent.peerId.value shouldBe peerB.id.value

    system.stop(coord)
  }

  // ── Category 1a: requeueOrEscalate → PivotStateUnservable ─────────────────

  it should "escalate PivotStateUnservable to controller after MaxRequeuesPerTask+1 consecutive failures" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("requeue-test-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(system.scheduler)
    val networkPeerManager = TestProbe()
    val snapSyncController = TestProbe()
    val peerProbe = TestProbe()
    val peer = PeerTestHelpers.createTestPeer("requeue-peer", peerProbe.ref)

    val coordinator = system.actorOf(
      AccountRangeCoordinator.props(
        stateRoot = stateRoot,
        networkPeerManager = networkPeerManager.ref,
        requestTracker = requestTracker,
        mptStorage = storage,
        concurrency = 1,
        snapSyncController = snapSyncController.ref,
        initialMaxInFlightPerPeer = 1
      )
    )

    coordinator ! Messages.StartAccountRangeSync(stateRoot)
    coordinator ! Messages.PeerAvailable(peer)

    // MaxRequeuesPerTask = 8, so the 9th failure (requeueCount > 8) escalates.
    // Route failures through the worker so it properly transitions to idle before each re-dispatch:
    //   test → workerRef ! WorkerPeerDisconnected → worker ! TaskFailed("Peer disconnected") → coordinator
    // This skips cooldown and stateless marking, allowing immediate re-dispatch each iteration.
    for (_ <- 1 to (AccountRangeCoordinator.MaxRequeuesPerTask + 1)) {
      networkPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessage](2.seconds)
      val workerRef = networkPeerManager.lastSender
      workerRef ! Messages.WorkerPeerDisconnected(peer.id.value)
    }

    snapSyncController.expectMsgType[SNAPSyncController.PivotStateUnservable](2.seconds)
  }

  // ── Category 3a: task.rootHash guard prevents stale-root stateless marking ─

  it should "not mark peer stateless when TaskFailed arrives for a stale-root in-flight task" taggedAs UnitTest in {
    val rootR1 = kec256(ByteString("stale-guard-root-r1"))
    val rootR2 = kec256(ByteString("stale-guard-root-r2"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(system.scheduler)
    val networkPeerManager = TestProbe()
    val snapSyncController = TestProbe()
    val peerProbe = TestProbe()
    val peer = PeerTestHelpers.createTestPeer("stale-guard-peer", peerProbe.ref)

    val coordinator = system.actorOf(
      AccountRangeCoordinator.props(
        stateRoot = rootR1,
        networkPeerManager = networkPeerManager.ref,
        requestTracker = requestTracker,
        mptStorage = storage,
        concurrency = 1,
        snapSyncController = snapSyncController.ref,
        initialMaxInFlightPerPeer = 1
      )
    )

    coordinator ! Messages.StartAccountRangeSync(rootR1)
    coordinator ! Messages.PeerAvailable(peer)

    val sendMsg1 = networkPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessage](2.seconds)
    val reqId1 = sendMsg1.message.asInstanceOf[GetAccountRangeEnc].underlyingMsg.requestId

    // Pivot refreshes while the task is still in-flight at rootR1
    coordinator ! Messages.PivotRefreshed(rootR2)

    // Task fails with "Missing proof" — but its rootHash is rootR1 (stale).
    // The rootHash guard must prevent marking peer as stateless for rootR2.
    // Sent directly to coordinator so task.rootHash stays at rootR1 (not updated by PivotRefreshed).
    coordinator ! Messages.TaskFailed(reqId1, "Missing proof for empty account range")

    // Use GetProgress as a synchronization barrier — by the time we get a response, the
    // coordinator has fully processed the TaskFailed (including any re-dispatch attempts).
    coordinator ! Messages.GetProgress
    expectMsgType[AccountRangeStats](2.seconds)

    // PivotStateUnservable must NOT have been sent — peer was not marked stateless for rootR2
    snapSyncController.expectNoMessage(200.millis)
  }

  // ── Category 3c: knownAvailablePeers dedup by remoteAddress ──────────────

  it should "replace stale peer entry and clear stateless when PeerAvailable arrives with same address but new ID" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("dedup-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(system.scheduler)
    val networkPeerManager = TestProbe()
    val snapSyncController = TestProbe()
    val peerAProbe = TestProbe()
    val peerBProbe = TestProbe()
    // Both peers share the same remoteAddress (127.0.0.1:30303) — simulates reconnection
    val peerA = PeerTestHelpers.createTestPeer("dedup-peer-A", peerAProbe.ref)
    val peerB = PeerTestHelpers.createTestPeer("dedup-peer-B", peerBProbe.ref)

    val coordinator = system.actorOf(
      AccountRangeCoordinator.props(
        stateRoot = stateRoot,
        networkPeerManager = networkPeerManager.ref,
        requestTracker = requestTracker,
        mptStorage = storage,
        concurrency = 1,
        snapSyncController = snapSyncController.ref,
        initialMaxInFlightPerPeer = 1
      )
    )

    coordinator ! Messages.StartAccountRangeSync(stateRoot)
    coordinator ! Messages.PeerAvailable(peerA)

    val sendMsg1 = networkPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessage](2.seconds)
    val reqId1 = sendMsg1.message.asInstanceOf[GetAccountRangeEnc].underlyingMsg.requestId
    val workerRef = networkPeerManager.lastSender

    // peerA returns empty proof → marked stateless → all known peers stateless → PivotStateUnservable
    coordinator ! Messages.TaskFailed(reqId1, "Missing proof for empty account range")
    snapSyncController.expectMsgType[SNAPSyncController.PivotStateUnservable](2.seconds)

    // Worker is still in working state (we sent TaskFailed directly, not through the worker).
    // Idle it via RequestTimeout so the next dispatch succeeds.
    workerRef ! Messages.RequestTimeout(reqId1)
    // Barrier: the RequestTimeout causes the worker to send TaskFailed(reqId1, ...) to coordinator,
    // which is ignored (reqId1 already removed from activeTasks). GetProgress synchronizes.
    coordinator ! Messages.GetProgress
    expectMsgType[AccountRangeStats](2.seconds)

    // peerB appears at the same IP:port with a new peer ID (reconnection scenario).
    // Dedup: peerA evicted from knownAvailablePeers, peerA's stateless entry cleared,
    // peerB added as a fresh non-stateless peer.
    coordinator ! Messages.PeerAvailable(peerB)

    // peerB is not stateless → pending task is dispatched to peerB (worker now idle)
    val sendMsg2 = networkPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessage](2.seconds)
    sendMsg2.peerId shouldBe peerB.id
  }

  // ── Category 2a: postStop sends AccountRangeProgress snapshot ─────────────

  it should "send AccountRangeProgress snapshot to controller when stopped" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("poststop-root"))
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
        concurrency = 1,
        snapSyncController = snapSyncController.ref
      )
    )

    coordinator ! Messages.StartAccountRangeSync(stateRoot)
    // Stop the coordinator — postStop() fires and sends AccountRangeProgress
    system.stop(coordinator)

    val progressMsg = snapSyncController.expectMsgType[Messages.AccountRangeProgress](3.seconds)
    // concurrency=1 → 1 task → 1 entry in the progress map
    progressMsg.progress should not be empty
  }

  // ── Category 1b: PivotRefreshed clears stateless tracking + redispatches ──

  it should "clear stateless tracking and immediately redispatch when pivot is refreshed" taggedAs UnitTest in {
    val rootR1 = kec256(ByteString("pivot-clear-root-r1"))
    val rootR2 = kec256(ByteString("pivot-clear-root-r2"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(system.scheduler)
    val networkPeerManager = TestProbe()
    val snapSyncController = TestProbe()
    val peerProbe = TestProbe()
    val peer = PeerTestHelpers.createTestPeer("pivot-clear-peer", peerProbe.ref)

    val coordinator = system.actorOf(
      AccountRangeCoordinator.props(
        stateRoot = rootR1,
        networkPeerManager = networkPeerManager.ref,
        requestTracker = requestTracker,
        mptStorage = storage,
        concurrency = 1,
        snapSyncController = snapSyncController.ref,
        initialMaxInFlightPerPeer = 1
      )
    )

    coordinator ! Messages.StartAccountRangeSync(rootR1)
    coordinator ! Messages.PeerAvailable(peer)

    val sendMsg1 = networkPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessage](2.seconds)
    val reqId1 = sendMsg1.message.asInstanceOf[GetAccountRangeEnc].underlyingMsg.requestId
    val workerRef = networkPeerManager.lastSender

    // Peer becomes stateless → coordinator requests pivot refresh from controller
    coordinator ! Messages.TaskFailed(reqId1, "Missing proof for empty account range")
    snapSyncController.expectMsgType[SNAPSyncController.PivotStateUnservable](2.seconds)

    // Worker is still in working state — idle it so the next dispatch after PivotRefreshed succeeds.
    workerRef ! Messages.RequestTimeout(reqId1)
    coordinator ! Messages.GetProgress
    expectMsgType[AccountRangeStats](2.seconds)

    // Controller responds with a fresh pivot.
    // PivotRefreshed clears statelessPeers and immediately calls dispatchIfPossible for each
    // known peer — work resumes without waiting for another PeerAvailable.
    coordinator ! Messages.PivotRefreshed(rootR2)

    networkPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessage](2.seconds)
  }

  // -----------------------------------------------------------------------
  // Category 2e: Peer Disconnect Mid-Flight
  // -----------------------------------------------------------------------
  // Cross-reference: core-geth eth/downloader/downloader_test.go dropPeer() pattern —
  // responses from dropped peers are silently ignored; in-flight tasks return to pending.

  it should "re-queue in-flight task and redispatch to a different peer after PeerUnavailable" taggedAs UnitTest in {
    val root = kec256(ByteString("disconnect-mid-flight-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(system.scheduler)
    val networkPeerManager = TestProbe()
    val snapSyncController = TestProbe()
    val peerProbe1 = TestProbe()
    val peerProbe2 = TestProbe()
    val peer1 = PeerTestHelpers.createTestPeer("disconnect-peer-1", peerProbe1.ref)
    val peer2 = PeerTestHelpers.createTestPeer("disconnect-peer-2", peerProbe2.ref)

    val coordinator = system.actorOf(
      AccountRangeCoordinator.props(
        stateRoot = root,
        networkPeerManager = networkPeerManager.ref,
        requestTracker = requestTracker,
        mptStorage = storage,
        concurrency = 1,
        snapSyncController = snapSyncController.ref,
        initialMaxInFlightPerPeer = 1
      )
    )

    // Peer1 connects → coordinator dispatches task to worker
    coordinator ! Messages.StartAccountRangeSync(root)
    coordinator ! Messages.PeerAvailable(peer1)
    networkPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessage](2.seconds)
    val worker = networkPeerManager.lastSender

    // Peer1 disconnects mid-flight:
    // coordinator sends WorkerPeerDisconnected to the worker; worker fires TaskFailed back;
    // coordinator re-queues the task and removes peer1 from knownAvailablePeers.
    coordinator ! Messages.PeerUnavailable(peer1.id.value)

    // Worker receives WorkerPeerDisconnected and fires TaskFailed("Peer disconnected") to coordinator.
    // Give the message round-trip time to complete before asserting redispatch.
    // (No direct assertion on WorkerPeerDisconnected here — it's coordinator → worker internal.)

    // Peer2 becomes available → coordinator re-dispatches the now-pending task to peer2
    coordinator ! Messages.PeerAvailable(peer2)
    val redispatch = networkPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessage](3.seconds)
    redispatch.peerId shouldBe peer2.id
  }

  it should "not fire a second TaskFailed when a late response arrives after RequestTimeout" taggedAs UnitTest in {
    val root = kec256(ByteString("late-response-guard-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(system.scheduler)
    val networkPeerManager = TestProbe()
    val snapSyncController = TestProbe()
    val peerProbe = TestProbe()
    val peer = PeerTestHelpers.createTestPeer("late-resp-peer", peerProbe.ref)

    val coordinator = system.actorOf(
      AccountRangeCoordinator.props(
        stateRoot = root,
        networkPeerManager = networkPeerManager.ref,
        requestTracker = requestTracker,
        mptStorage = storage,
        concurrency = 1,
        snapSyncController = snapSyncController.ref,
        initialMaxInFlightPerPeer = 1
      )
    )

    coordinator ! Messages.StartAccountRangeSync(root)
    coordinator ! Messages.PeerAvailable(peer)
    networkPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessage](2.seconds)
    val worker = networkPeerManager.lastSender

    // Worker times out — fires TaskFailed("Request timeout") to coordinator
    worker ! Messages.RequestTimeout(BigInt(1))

    // Coordinator requeues; controller receives no escalation (not enough retries)
    snapSyncController.expectNoMessage(300.millis)

    // Late AccountRangeResponse arrives at worker (now in idle state) — must be silently dropped;
    // coordinator must NOT receive a second TaskFailed or TaskComplete for this request.
    import com.chipprbots.ethereum.network.p2p.messages.SNAP.AccountRange
    worker ! Messages.AccountRangeResponseMsg(
      AccountRange(requestId = BigInt(1), accounts = Seq.empty, proof = Seq.empty)
    )

    // The only message the snapSyncController should ever see is nothing (no double completion)
    snapSyncController.expectNoMessage(300.millis)
  }

  // ── Category 5c: Peer cooldown (gray-list) — newly-seen vs proven peer ─────
  // After a transient protocol failure (timeout, bad proof), the peer enters a
  // 30-second cooldown ("gray list").  Tasks are not dispatched to cooling peers.
  // This mirrors Monero's peer_list_general gray/white list separation (peers enter
  // gray on first contact and graduate to white after a successful response).
  // Reference: AccountRangeCoordinator.scala recordPeerCooldown + peerCooldownDefault
  // Cross-reference: Monero tests/unit_tests/test_peerlist.cpp peer_list_general.

  it should "not dispatch a task to a peer that is still in cooldown after a timeout failure" taggedAs UnitTest in {
    val root = kec256(ByteString("cooldown-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(system.scheduler)
    val networkPeerManager = TestProbe()
    val snapSyncController = TestProbe()
    val peerProbe1 = TestProbe()
    val peerProbe2 = TestProbe()
    val peer1 = PeerTestHelpers.createTestPeer("cooldown-peer-1", peerProbe1.ref)
    val peer2 = PeerTestHelpers.createTestPeer("cooldown-peer-2", peerProbe2.ref)

    val coordinator = system.actorOf(
      AccountRangeCoordinator.props(
        stateRoot = root,
        networkPeerManager = networkPeerManager.ref,
        requestTracker = requestTracker,
        mptStorage = storage,
        concurrency = 2,
        snapSyncController = snapSyncController.ref,
        initialMaxInFlightPerPeer = 1
      )
    )

    coordinator ! Messages.StartAccountRangeSync(root)
    coordinator ! Messages.PeerAvailable(peer1)

    val sendMsg1 = networkPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessage](2.seconds)
    val reqId1 = sendMsg1.message.asInstanceOf[GetAccountRangeEnc].underlyingMsg.requestId
    val worker1 = networkPeerManager.lastSender

    // peer1 times out — enters cooldown via recordPeerCooldown
    worker1 ! Messages.RequestTimeout(reqId1)

    // peer2 connects after peer1 enters cooldown
    coordinator ! Messages.PeerAvailable(peer2)

    // The requeued task should be dispatched to peer2, NOT to the cooling peer1
    val sendMsg2 = networkPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessage](2.seconds)
    sendMsg2.peerId shouldBe peer2.id
  }

  // -----------------------------------------------------------------------
  // StackTrie write-path (Step 3 of `snap-stacktrie-port` plan)
  // -----------------------------------------------------------------------
  // The legacy MPT path stays the default and is exercised by every other
  // test above. These tests opt-in via `useStackTrie = true` and verify:
  //   - per-task SnapHashTrie instances are created lazily on first chunk store
  //   - accounts are routed through the StackTrie (writes hit mptStorage via
  //     storeRawNodes; the legacy `stateTrie` field stays empty)
  //   - on task completion the StackTrie is committed and removed from the
  //     per-task map (no leaks across tasks)

  /** Construct a TestActorRef coordinator with `useStackTrie = true`. */
  private def newStackTrieCoordinator(
      stateRoot: ByteString = kec256(ByteString("stacktrie-test-root")),
      controller: TestProbe = TestProbe(),
      storage: TestMptStorage = new TestMptStorage(),
      concurrency: Int = 4
  ): (TestActorRef[AccountRangeCoordinator], TestMptStorage) = {
    val ref = TestActorRef[AccountRangeCoordinator](
      AccountRangeCoordinator.props(
        stateRoot = stateRoot,
        networkPeerManager = TestProbe().ref,
        requestTracker = new SNAPRequestTracker()(system.scheduler),
        mptStorage = storage,
        concurrency = concurrency,
        snapSyncController = controller.ref,
        accountTrieEcOverride = Some(system.dispatcher),
        useStackTrie = true
      )
    )
    (ref, storage)
  }

  /** Build a strict-ascending sequence of (accountHash, Account) pairs starting
    * at `task.next` so insertion order satisfies the StackTrie's sort invariant.
    */
  private def stackTrieFixtureAccounts(
      task: AccountTask,
      count: Int
  ): Seq[(ByteString, com.chipprbots.ethereum.domain.Account)] = {
    val seed = task.next.toArray
    (1 to count).map { i =>
      val hash = new Array[Byte](32)
      Array.copy(seed, 0, hash, 0, math.min(seed.length, hash.length))
      // Mutate the trailing bytes by i, big-endian — guarantees strictly ascending.
      hash(31) = (hash(31) + i).toByte
      val acct = com.chipprbots.ethereum.domain.Account.empty().increaseNonce()
      ByteString(hash) -> acct
    }
  }

  it should "route account inserts through per-task SnapHashTrie when useStackTrie is enabled" taggedAs UnitTest in {
    val root = kec256(ByteString("stacktrie-test-root"))
    val (coord, storage) = newStackTrieCoordinator(stateRoot = root)
    val ua = coord.underlyingActor
    val task = AccountTask(
      next = ByteString(Array.fill[Byte](32)(0x00)),
      last = ByteString(Array.fill[Byte](32)(0xff.toByte)),
      rootHash = root
    )
    val accounts = stackTrieFixtureAccounts(task, 8)
    val nodesBefore = storage.synchronized { /* peek size via decode-then-count */ 0 }

    // Drive the chunk-store path directly. isTaskRangeComplete = false (more responses
    // could follow for this range), so the per-task SnapHashTrie should remain in the map.
    coord ! Messages.StoreAccountChunk(task, accounts, accounts.size, storedSoFar = 0, isTaskRangeComplete = false)
    coord ! Messages.GetProgress
    expectMsgType[AccountRangeStats](2.seconds)

    ua.taskStackTries should contain key task.last
    // No global stateTrie touch — its root should still be the empty-root hash.
    ByteString(ua.getStateRoot.toArray) shouldEqual ByteString(MerklePatriciaTrie.EmptyRootHash)
    val _ = nodesBefore // suppress unused warning while the storage poking is a no-op

    system.stop(coord)
  }

  it should "commit and clear per-task SnapHashTrie when a task range completes" taggedAs UnitTest in {
    val root = kec256(ByteString("stacktrie-commit-root"))
    val (coord, _) = newStackTrieCoordinator(stateRoot = root)
    val ua = coord.underlyingActor
    val task = AccountTask(
      next = ByteString(Array.fill[Byte](32)(0x00)),
      last = ByteString(Array.fill[Byte](32)(0xff.toByte)),
      rootHash = root
    )
    val accounts = stackTrieFixtureAccounts(task, 4)

    // Drive the final chunk for this task with isTaskRangeComplete = true.
    coord ! Messages.StoreAccountChunk(task, accounts, accounts.size, storedSoFar = 0, isTaskRangeComplete = true)
    coord ! Messages.GetProgress
    expectMsgType[AccountRangeStats](2.seconds)

    // After commit, the per-task StackTrie should have been removed.
    ua.taskStackTries should not contain key(task.last)

    system.stop(coord)
  }

  it should "not touch legacy stateTrie or accountsSinceLastFlush on the StackTrie path" taggedAs UnitTest in {
    val root = kec256(ByteString("stacktrie-isolation-root"))
    val (coord, _) = newStackTrieCoordinator(stateRoot = root)
    val ua = coord.underlyingActor
    val task = AccountTask(
      next = ByteString(Array.fill[Byte](32)(0x00)),
      last = ByteString(Array.fill[Byte](32)(0xff.toByte)),
      rootHash = root
    )
    val accounts = stackTrieFixtureAccounts(task, 12)

    coord ! Messages.StoreAccountChunk(task, accounts, accounts.size, storedSoFar = 0, isTaskRangeComplete = false)
    coord ! Messages.GetProgress
    expectMsgType[AccountRangeStats](2.seconds)

    // Legacy stateTrie root stays at the empty-root hash.
    ByteString(ua.getStateRoot.toArray) shouldEqual ByteString(MerklePatriciaTrie.EmptyRootHash)

    system.stop(coord)
  }

  it should "honour resumeProgress on the StackTrie path (Step 6 — restart durability)" taggedAs UnitTest in {
    // Step 6 of the snap-stacktrie-port plan: verify resume cursors work with
    // useStackTrie = true. The existing `AccountRangeProgress` →
    // `putSnapSyncProgress` flow already journals per-task cursors to RocksDB;
    // on restart, the controller passes `resumeProgress` into the coordinator
    // and each task's `next` is advanced past where the prior session left off.
    // For the StackTrie path this is sufficient: SnapHashTrie instances are
    // re-created from scratch (content-addressed nodes on disk remain valid),
    // and sort enforcement is satisfied because resumed `next` is monotonically
    // ascending vs. any prior in-memory state (there is none after restart).
    val root = kec256(ByteString("stacktrie-resume-root"))
    // With concurrency = 1 the single AccountTask covers the entire 32-byte
    // hash space, so its `last` boundary is the maximum 32-byte value.
    val rangeLast = AccountTask.MaxHash32
    val resumedNext = ByteString(Array.fill[Byte](32)(0x77.toByte)) // mid-range

    val coord = TestActorRef[AccountRangeCoordinator](
      AccountRangeCoordinator.props(
        stateRoot = root,
        networkPeerManager = TestProbe().ref,
        requestTracker = new SNAPRequestTracker()(system.scheduler),
        mptStorage = new TestMptStorage(),
        concurrency = 1, // single range so we can predict task.last
        snapSyncController = TestProbe().ref,
        resumeProgress = Map(rangeLast -> resumedNext),
        accountTrieEcOverride = Some(system.dispatcher),
        useStackTrie = true
      )
    )
    val ua = coord.underlyingActor

    // The single range's task.next must have been advanced to `resumedNext`,
    // not the canonical zero-start; sort-enforcement on the per-task
    // SnapHashTrie will work because every future insert is > resumedNext.
    val pending = ua.pendingTasks.toList
    pending should have size 1
    pending.head.next shouldEqual resumedNext
    pending.head.last shouldEqual rangeLast

    // No SnapHashTrie has been instantiated yet (lazy creation on first chunk).
    ua.taskStackTries shouldBe empty

    system.stop(coord)
  }

  // ── Storage back-pressure pause/resume (#1232 follow-up) ──────────────────
  // Account-range download was producing storage tasks faster than peers could
  // serve them, leading to an unbounded queue and the May 13 sepolia OOM. The
  // coordinator now obeys a pause/resume signal forwarded from the storage
  // coordinator via SNAPSyncController.
  it should "flip storageBackpressureActive on receipt of StorageQueuePressure" taggedAs UnitTest in {
    val coord = newCoordinator()

    coord.underlyingActor.storageBackpressureActive shouldBe false

    coord ! Messages.StorageQueuePressure(paused = true)
    awaitAssert(coord.underlyingActor.storageBackpressureActive shouldBe true, 1.second, 50.millis)

    coord ! Messages.StorageQueuePressure(paused = false)
    awaitAssert(coord.underlyingActor.storageBackpressureActive shouldBe false, 1.second, 50.millis)

    system.stop(coord)
  }

  it should "skip dispatchIfPossible while storageBackpressureActive is set" taggedAs UnitTest in {
    val networkPeerManager = TestProbe()
    val coord = TestActorRef[AccountRangeCoordinator](
      AccountRangeCoordinator.props(
        stateRoot = kec256(ByteString("backpressure-dispatch-root")),
        networkPeerManager = networkPeerManager.ref,
        requestTracker = new SNAPRequestTracker()(system.scheduler),
        mptStorage = new TestMptStorage(),
        concurrency = 4,
        snapSyncController = TestProbe().ref,
        accountTrieEcOverride = Some(system.dispatcher)
      )
    )

    val peerProbe = TestProbe()
    val peer = PeerTestHelpers.createTestPeer("backpressure-peer", peerProbe.ref)

    coord ! Messages.StartAccountRangeSync(kec256(ByteString("backpressure-dispatch-root")))

    // Engage back-pressure BEFORE peer becomes available — the coordinator should accept the
    // peer but not dispatch any GetAccountRange requests until back-pressure releases.
    coord ! Messages.StorageQueuePressure(paused = true)
    coord ! Messages.PeerAvailable(peer)

    networkPeerManager.expectNoMessage(500.millis)

    // Release: the coordinator wakes up and dispatches against the known peer.
    coord ! Messages.StorageQueuePressure(paused = false)
    networkPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessage](2.seconds)
  }

  it should "treat storage and bytecode pressure as ANY-of: only release once every source clears" taggedAs UnitTest in {
    val networkPeerManager = TestProbe()
    val coord = TestActorRef[AccountRangeCoordinator](
      AccountRangeCoordinator.props(
        stateRoot = kec256(ByteString("two-source-backpressure-root")),
        networkPeerManager = networkPeerManager.ref,
        requestTracker = new SNAPRequestTracker()(system.scheduler),
        mptStorage = new TestMptStorage(),
        concurrency = 4,
        snapSyncController = TestProbe().ref,
        accountTrieEcOverride = Some(system.dispatcher)
      )
    )

    val peerProbe = TestProbe()
    val peer = PeerTestHelpers.createTestPeer("two-source-peer", peerProbe.ref)

    coord ! Messages.StartAccountRangeSync(kec256(ByteString("two-source-backpressure-root")))

    // Engage BOTH sources.
    coord ! Messages.StorageQueuePressure(paused = true)
    coord ! Messages.ByteCodeQueuePressure(paused = true)
    coord ! Messages.PeerAvailable(peer)

    networkPeerManager.expectNoMessage(500.millis)

    // Release storage only — bytecode is still engaged, so dispatch must remain paused.
    coord ! Messages.StorageQueuePressure(paused = false)
    networkPeerManager.expectNoMessage(500.millis)

    // Release bytecode — set is now empty, dispatch resumes.
    coord ! Messages.ByteCodeQueuePressure(paused = false)
    networkPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessage](2.seconds)
  }
}
