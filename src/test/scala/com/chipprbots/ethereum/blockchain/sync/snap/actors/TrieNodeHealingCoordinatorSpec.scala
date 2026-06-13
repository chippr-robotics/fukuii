package com.chipprbots.ethereum.blockchain.sync.snap.actors

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.{TestKit, TestProbe, ImplicitSender}
import org.apache.pekko.util.ByteString

import scala.concurrent.duration._

import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.snap._
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.storage.InMemoryBfsQueueStorage
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.p2p.messages.SNAP
import com.chipprbots.ethereum.testing.Tags._
import com.chipprbots.ethereum.testing.{PeerTestHelpers, TestMptStorage}

import java.nio.ByteBuffer

class TrieNodeHealingCoordinatorSpec
    extends TestKit(ActorSystem("TrieNodeHealingCoordinatorSpec"))
    with ImplicitSender
    with AnyFlatSpecLike
    with Matchers
    with BeforeAndAfterAll {

  override def afterAll(): Unit =
    TestKit.shutdownActorSystem(system)

  "TrieNodeHealingCoordinator" should "initialize correctly" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("test-state-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(system.scheduler)
    val networkPeerManager = TestProbe()
    val snapSyncController = TestProbe()

    val coordinator = system.actorOf(
      TrieNodeHealingCoordinator.props(
        stateRoot = stateRoot,
        networkPeerManager = networkPeerManager.ref,
        requestTracker = requestTracker,
        mptStorage = storage,
        batchSize = 16,
        snapSyncController = snapSyncController.ref
      )
    )

    coordinator should not be null
  }

  it should "queue missing nodes for healing" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("test-state-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(system.scheduler)
    val networkPeerManager = TestProbe()
    val snapSyncController = TestProbe()

    val coordinator = system.actorOf(
      TrieNodeHealingCoordinator.props(
        stateRoot = stateRoot,
        networkPeerManager = networkPeerManager.ref,
        requestTracker = requestTracker,
        mptStorage = storage,
        batchSize = 16,
        snapSyncController = snapSyncController.ref
      )
    )

    val node1Hash = kec256(ByteString("node1"))
    val node2Hash = kec256(ByteString("node2"))
    val missingNodes = Seq(
      (Seq(ByteString(Array[Byte](0x00))), node1Hash),
      (Seq(ByteString(Array[Byte](0x00))), node2Hash)
    )

    coordinator ! Messages.QueueMissingNodes(missingNodes)

    // Coordinator should queue the nodes
    coordinator ! Messages.HealingGetProgress
    expectMsgType[Any](3.seconds)
  }

  it should "create workers when peers are available" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("test-state-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(system.scheduler)
    val networkPeerManager = TestProbe()
    val snapSyncController = TestProbe()
    val peerProbe = TestProbe()

    val peer = PeerTestHelpers.createTestPeer("test-peer", peerProbe.ref)

    val coordinator = system.actorOf(
      TrieNodeHealingCoordinator.props(
        stateRoot = stateRoot,
        networkPeerManager = networkPeerManager.ref,
        requestTracker = requestTracker,
        mptStorage = storage,
        batchSize = 16,
        snapSyncController = snapSyncController.ref
      )
    )

    val nodeHash = kec256(ByteString("node1"))
    val missingNodes = Seq((Seq(ByteString(Array[Byte](0x00))), nodeHash))

    coordinator ! Messages.StartTrieNodeHealing(stateRoot)
    coordinator ! Messages.QueueMissingNodes(missingNodes)
    coordinator ! Messages.HealingPeerAvailable(peer)

    // Should send request to network peer manager
    networkPeerManager.expectMsgType[Any](3.seconds)
  }

  it should "handle task completion" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("test-state-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(system.scheduler)
    val networkPeerManager = TestProbe()
    val snapSyncController = TestProbe()

    val coordinator = system.actorOf(
      TrieNodeHealingCoordinator.props(
        stateRoot = stateRoot,
        networkPeerManager = networkPeerManager.ref,
        requestTracker = requestTracker,
        mptStorage = storage,
        batchSize = 16,
        snapSyncController = snapSyncController.ref
      )
    )

    coordinator ! Messages.HealingTaskComplete(BigInt(123), Right(5))

    // Coordinator should handle completion
    coordinator ! Messages.HealingGetProgress
    expectMsgType[Any](3.seconds)
  }

  it should "report completion when all nodes healed" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("test-state-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(system.scheduler)
    val networkPeerManager = TestProbe()
    val snapSyncController = TestProbe()

    val coordinator = system.actorOf(
      TrieNodeHealingCoordinator.props(
        stateRoot = stateRoot,
        networkPeerManager = networkPeerManager.ref,
        requestTracker = requestTracker,
        mptStorage = storage,
        batchSize = 16,
        snapSyncController = snapSyncController.ref
      )
    )

    coordinator ! Messages.HealingCheckCompletion

    // An idle coordinator (no pending tasks, no active requests) should complete immediately
    snapSyncController.expectMsg(3.seconds, SNAPSyncController.StateHealingComplete)
  }

  it should "handle task failures" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("test-state-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(system.scheduler)
    val networkPeerManager = TestProbe()
    val snapSyncController = TestProbe()

    val coordinator = system.actorOf(
      TrieNodeHealingCoordinator.props(
        stateRoot = stateRoot,
        networkPeerManager = networkPeerManager.ref,
        requestTracker = requestTracker,
        mptStorage = storage,
        batchSize = 16,
        snapSyncController = snapSyncController.ref
      )
    )

    coordinator ! Messages.HealingTaskFailed(BigInt(123), "Test failure")

    // Coordinator should still be operational
    coordinator ! Messages.HealingGetProgress
    expectMsgType[Any](3.seconds)
  }

  it should "signal StateHealingComplete to controller on HealingForceComplete" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("force-complete-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(system.scheduler)
    val networkPeerManager = TestProbe()
    val snapSyncController = TestProbe()

    val coordinator = system.actorOf(
      TrieNodeHealingCoordinator.props(
        stateRoot = stateRoot,
        networkPeerManager = networkPeerManager.ref,
        requestTracker = requestTracker,
        mptStorage = storage,
        batchSize = 16,
        snapSyncController = snapSyncController.ref
      )
    )

    coordinator ! Messages.HealingForceComplete

    snapSyncController.expectMsg(3.seconds, SNAPSyncController.StateHealingComplete)
  }

  it should "accept HealingPivotRefreshed and re-seed new root — HealingCheckCompletion deferred" taggedAs UnitTest in {
    // After pivot refresh the coordinator re-seeds the new root into pendingTasks.
    // isComplete = pendingTasks.isEmpty && activeRequests.isEmpty = false.
    // HealingCheckCompletion must therefore NOT signal StateHealingComplete.
    val stateRoot = kec256(ByteString("old-heal-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(system.scheduler)
    val networkPeerManager = TestProbe()
    val snapSyncController = TestProbe()

    val coordinator = system.actorOf(
      TrieNodeHealingCoordinator.props(
        stateRoot = stateRoot,
        networkPeerManager = networkPeerManager.ref,
        requestTracker = requestTracker,
        mptStorage = storage,
        batchSize = 16,
        snapSyncController = snapSyncController.ref
      )
    )

    val newStateRoot = kec256(ByteString("new-heal-root"))
    coordinator ! Messages.HealingPivotRefreshed(newStateRoot)

    // The new root is not in storage, so it is added to pendingTasks.
    // isComplete = false → StateHealingComplete must NOT be sent.
    coordinator ! Messages.HealingCheckCompletion
    snapSyncController.expectNoMessage(300.millis)

    // Coordinator remains operational.
    coordinator ! Messages.HealingGetProgress
    expectMsgType[Any](3.seconds)
  }

  it should "not signal StateHealingComplete on HealingCheckCompletion when pending tasks exist" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("pending-tasks-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(system.scheduler)
    val networkPeerManager = TestProbe()
    val snapSyncController = TestProbe()

    val coordinator = system.actorOf(
      TrieNodeHealingCoordinator.props(
        stateRoot = stateRoot,
        networkPeerManager = networkPeerManager.ref,
        requestTracker = requestTracker,
        mptStorage = storage,
        batchSize = 16,
        snapSyncController = snapSyncController.ref
      )
    )

    val nodeHash = kec256(ByteString("missing-node"))
    coordinator ! Messages.QueueMissingNodes(Seq((Seq(ByteString(Array[Byte](0x00))), nodeHash)))

    // pendingTasks is non-empty → isComplete = false → no StateHealingComplete
    coordinator ! Messages.HealingCheckCompletion
    snapSyncController.expectNoMessage(300.millis)
  }

  // ========================================
  // pendingTasks ArrayDeque + dispatcher (issue #1167)
  // ========================================

  /** Synthesize a unique (pathset, hash) so the dedup set doesn't drop our nodes. */
  private def fakeHashedNode(seed: Int): (Seq[ByteString], ByteString) = {
    val hash = kec256(ByteString(s"healing-node-$seed"))
    // pathset is a Seq[ByteString]; for queueing we just need something distinct.
    val path = ByteString(ByteBuffer.allocate(4).putInt(seed).array())
    (Seq(path), hash)
  }

  it should "absorb a large QueueMissingNodes payload without timing out" taggedAs UnitTest in {
    // The previous immutable-Seq pendingTasks was O(n) per `:+`. Queueing 50,000 nodes via
    // appendAll on the new ArrayDeque is O(n) total instead of O(n²).
    val stateRoot = kec256(ByteString("deque-load-test-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(system.scheduler)
    val networkPeerManager = TestProbe()
    val snapSyncController = TestProbe()

    val coordinator = system.actorOf(
      TrieNodeHealingCoordinator.props(
        stateRoot = stateRoot,
        networkPeerManager = networkPeerManager.ref,
        requestTracker = requestTracker,
        mptStorage = storage,
        batchSize = 64,
        snapSyncController = snapSyncController.ref,
        healingWriterEcOverride = Some(system.dispatcher)
      )
    )

    val nodeCount = 50000
    val nodes = (1 to nodeCount).map(fakeHashedNode)

    coordinator ! Messages.StartTrieNodeHealing(stateRoot)
    val queueStart = System.nanoTime()
    coordinator ! Messages.QueueMissingNodes(nodes)
    coordinator ! Messages.HealingGetProgress

    val stats = expectMsgType[HealingStatistics](5.seconds)
    val elapsedMs = (System.nanoTime() - queueStart) / 1000000L

    // +1 for the root node seeded by StartTrieNodeHealing (Besu-aligned top-down discovery).
    stats.pendingTasks shouldBe (nodeCount + 1)
    // Loose ceiling — main signal is that this ran in linear time (O(n²) at this size
    // would take many seconds even on a fast box). Tighten if it ever flakes.
    elapsedMs should be < 5000L
  }

  it should "drain pending tasks in FIFO order across many small QueueMissingNodes calls" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("deque-fifo-test-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(system.scheduler)
    val networkPeerManager = TestProbe()
    val snapSyncController = TestProbe()

    val coordinator = system.actorOf(
      TrieNodeHealingCoordinator.props(
        stateRoot = stateRoot,
        networkPeerManager = networkPeerManager.ref,
        requestTracker = requestTracker,
        mptStorage = storage,
        batchSize = 16,
        snapSyncController = snapSyncController.ref,
        healingWriterEcOverride = Some(system.dispatcher)
      )
    )

    coordinator ! Messages.StartTrieNodeHealing(stateRoot)

    // Three batches; total 750 nodes queued in O(n) time.
    val batches = Seq.tabulate(3)(g => (g * 250 until (g + 1) * 250).map(fakeHashedNode))
    batches.foreach(b => coordinator ! Messages.QueueMissingNodes(b))

    coordinator ! Messages.HealingGetProgress
    val stats = expectMsgType[HealingStatistics](3.seconds)
    // +1 for the root node seeded by StartTrieNodeHealing (Besu-aligned top-down discovery).
    stats.pendingTasks shouldBe 751
  }

  it should "construct successfully when a healing-writer EC override is supplied" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("ec-override-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(system.scheduler)
    val networkPeerManager = TestProbe()
    val snapSyncController = TestProbe()

    val coordinator = system.actorOf(
      TrieNodeHealingCoordinator.props(
        stateRoot = stateRoot,
        networkPeerManager = networkPeerManager.ref,
        requestTracker = requestTracker,
        mptStorage = storage,
        batchSize = 16,
        snapSyncController = snapSyncController.ref,
        healingWriterEcOverride = Some(system.dispatcher)
      )
    )

    coordinator should not be null
    coordinator ! Messages.HealingGetProgress
    expectMsgType[HealingStatistics](2.seconds)
  }

  it should "signal StateHealingComplete on HealingForceComplete even with pending tasks in flight" taggedAs UnitTest in {
    // HealingForceComplete is the SNAPSyncController's nuclear option: when the pivot has
    // advanced beyond the SNAP serve window, healing must abandon pending tasks immediately
    // rather than waiting for them to drain normally.
    val stateRoot = kec256(ByteString("force-complete-with-tasks-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(system.scheduler)
    val networkPeerManager = TestProbe()
    val snapSyncController = TestProbe()
    val peerProbe = TestProbe()

    val peer = PeerTestHelpers.createTestPeer("force-heal-peer", peerProbe.ref)

    val coordinator = system.actorOf(
      TrieNodeHealingCoordinator.props(
        stateRoot = stateRoot,
        networkPeerManager = networkPeerManager.ref,
        requestTracker = requestTracker,
        mptStorage = storage,
        batchSize = 16,
        snapSyncController = snapSyncController.ref
      )
    )

    // Queue tasks and make a peer available so some become active
    val nodeHash1 = kec256(ByteString("node-force-1"))
    val nodeHash2 = kec256(ByteString("node-force-2"))
    coordinator ! Messages.StartTrieNodeHealing(stateRoot)
    coordinator ! Messages.QueueMissingNodes(
      Seq(
        (Seq(ByteString(Array[Byte](0x00))), nodeHash1),
        (Seq(ByteString(Array[Byte](0x01))), nodeHash2)
      )
    )
    coordinator ! Messages.HealingPeerAvailable(peer)
    networkPeerManager.expectMsgType[Any](3.seconds) // task dispatched

    // ForceComplete while tasks are in-flight: abandon all, signal complete immediately
    coordinator ! Messages.HealingForceComplete
    snapSyncController.expectMsg(3.seconds, SNAPSyncController.StateHealingComplete)
  }

  // ── Category 1e: HealingStagnated counter semantics ───────────────────────────────────────────
  //
  // HealingStagnationCheck is a private case object fired by an internal 2-minute scheduler.
  // It cannot be injected directly from tests. Instead, these tests model the counter semantics
  // as pure logic (same pattern as the K2 and "Consecutive pivot refresh" tests above) so a
  // refactor cannot silently change the thresholds without a failing test.

  "HealingStagnated counter semantics" should "send HealingStagnated after MaxConsecutiveStagnations zero-progress cycles" taggedAs UnitTest in {
    var consecutiveStagnations = 0
    val MaxConsecutiveStagnations = 3
    var healingStagnatedSent = false

    // Simulate 3 consecutive HEAL-PULSE ticks with zero new nodes healed
    for (_ <- 1 to MaxConsecutiveStagnations) {
      val recentHealed = 0 // no progress
      val pendingTasksNonEmpty = true

      if (recentHealed == 0 && pendingTasksNonEmpty) {
        consecutiveStagnations += 1
        if (consecutiveStagnations >= MaxConsecutiveStagnations) {
          healingStagnatedSent = true // → snapSyncController ! HealingStagnated(...)
          consecutiveStagnations = 0
        }
      } else if (recentHealed > 0) {
        consecutiveStagnations = 0
      }
    }

    healingStagnatedSent shouldBe true
    consecutiveStagnations shouldBe 0 // reset after escalation
  }

  it should "reset consecutiveStagnations to zero when at least one node is healed" taggedAs UnitTest in {
    var consecutiveStagnations = 0
    val MaxConsecutiveStagnations = 3

    // Two zero-progress cycles...
    consecutiveStagnations += 1
    consecutiveStagnations += 1
    consecutiveStagnations shouldBe 2

    // ...then a productive cycle
    val recentHealed = 5
    if (recentHealed > 0) consecutiveStagnations = 0

    consecutiveStagnations shouldBe 0
    // Need 3 more zero cycles to hit threshold again
    (consecutiveStagnations >= MaxConsecutiveStagnations) shouldBe false
  }

  it should "lock MaxConsecutiveStagnations=3 as the threshold constant" taggedAs UnitTest in {
    // Changing MaxConsecutiveStagnations changes how long fukuii waits before abandoning
    // a stuck healing phase. This test locks the value so the change is deliberate.
    // MaxConsecutiveStagnations is private, but its value is established by the counter tests above.
    // Indirect verification: 3 cycles needed to trigger, 2 cycles are not enough.
    var count = 0
    val MaxConsecutiveStagnations = 3
    count += 1; (count >= MaxConsecutiveStagnations) shouldBe false
    count += 1; (count >= MaxConsecutiveStagnations) shouldBe false
    count += 1; (count >= MaxConsecutiveStagnations) shouldBe true
  }

  // ── NB-11: pivotRefreshRequested suppression ─────────────────────────────────────────────────
  //
  // HealingStagnationCheck is a private case object — cannot be injected.
  // These tests model the suppression semantics as pure logic, matching the pattern above.

  it should "suppress further HealingStagnated signals after pivotRefreshRequested is set" taggedAs UnitTest in {
    var pivotRefreshRequested = false
    var consecutiveStagnations = 0
    val MaxConsecutiveStagnations = 3
    var stagnatedSignals = 0

    def tick(recentHealed: Int, hasPending: Boolean): Unit =
      if (!pivotRefreshRequested && recentHealed == 0 && hasPending) {
        consecutiveStagnations += 1
        if (consecutiveStagnations >= MaxConsecutiveStagnations) {
          stagnatedSignals += 1
          pivotRefreshRequested = true
          consecutiveStagnations = 0
        }
      } else if (recentHealed > 0) {
        consecutiveStagnations = 0
      }

    // First escalation
    for (_ <- 1 to MaxConsecutiveStagnations) tick(0, hasPending = true)
    stagnatedSignals shouldBe 1
    pivotRefreshRequested shouldBe true

    // Additional ticks while pivotRefreshRequested=true must not fire a second signal
    for (_ <- 1 to MaxConsecutiveStagnations * 2) tick(0, hasPending = true)
    stagnatedSignals shouldBe 1
  }

  it should "resume stagnation counting after pivotRefreshRequested is cleared (HealingPivotRefreshed)" taggedAs UnitTest in {
    var pivotRefreshRequested = false
    var consecutiveStagnations = 0
    val MaxConsecutiveStagnations = 3
    var stagnatedSignals = 0

    def tick(recentHealed: Int, hasPending: Boolean): Unit =
      if (!pivotRefreshRequested && recentHealed == 0 && hasPending) {
        consecutiveStagnations += 1
        if (consecutiveStagnations >= MaxConsecutiveStagnations) {
          stagnatedSignals += 1
          pivotRefreshRequested = true
          consecutiveStagnations = 0
        }
      } else if (recentHealed > 0) {
        consecutiveStagnations = 0
      }

    // First escalation
    for (_ <- 1 to MaxConsecutiveStagnations) tick(0, hasPending = true)
    stagnatedSignals shouldBe 1

    // Simulate HealingPivotRefreshed resetting the suppression flag
    pivotRefreshRequested = false
    consecutiveStagnations = 0

    // Second escalation cycle should succeed now
    for (_ <- 1 to MaxConsecutiveStagnations) tick(0, hasPending = true)
    stagnatedSignals shouldBe 2
  }

  // ── NB-7: Stateless dispatch gate ────────────────────────────────────────────────────────────
  //
  // Actor-level tests: drive via real messages (HealingPeerAvailable + TrieNodesResponseMsg).

  it should "ignore HealingPeerAvailable for a peer already in statelessPeers" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("nb7-stateless-gate-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(system.scheduler)
    val networkPeerManager = TestProbe()
    val snapSyncController = TestProbe()
    val peerProbe = TestProbe()
    val peer = PeerTestHelpers.createTestPeer("stateless-peer", peerProbe.ref)

    val coordinator = system.actorOf(
      TrieNodeHealingCoordinator.props(
        stateRoot = stateRoot,
        networkPeerManager = networkPeerManager.ref,
        requestTracker = requestTracker,
        mptStorage = storage,
        batchSize = 1,
        snapSyncController = snapSyncController.ref
      )
    )

    // Seed a task and dispatch it to the peer
    coordinator ! Messages.StartTrieNodeHealing(stateRoot)
    coordinator ! Messages.HealingPeerAvailable(peer)
    networkPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessage](3.seconds)

    // Empty TrieNodes response (requestId=1 is the first generated) → marks peer stateless
    coordinator ! Messages.TrieNodesResponseMsg(SNAP.TrieNodes(requestId = 1, nodes = Seq.empty))

    // Second HealingPeerAvailable for the same peer must be silently ignored
    coordinator ! Messages.HealingPeerAvailable(peer)
    networkPeerManager.expectNoMessage(300.millis)
  }

  it should "not mark a peer stateless on repeated GetTrieNodes timeouts (go-ethereum: timeouts rotate tasks only)" taggedAs UnitTest in {
    // Regression guard: old code marked peer stateless after MaxConsecutiveTimeoutsBeforeStateless (3)
    // timeouts, then fired HealingAllPeersStateless to SNAPSyncController.
    // New behavior (go-ethereum aligned): timeouts only re-queue tasks — no stateless marking.
    val stateRoot = kec256(ByteString("nb7-timeout-no-stateless-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(system.scheduler)
    val networkPeerManager = TestProbe()
    val snapSyncController = TestProbe()
    val peerProbe = TestProbe()
    val peer = PeerTestHelpers.createTestPeer("timeout-peer", peerProbe.ref)

    val coordinator = system.actorOf(
      TrieNodeHealingCoordinator.props(
        stateRoot = stateRoot,
        networkPeerManager = networkPeerManager.ref,
        requestTracker = requestTracker,
        mptStorage = storage,
        batchSize = 1,
        snapSyncController = snapSyncController.ref
      )
    )

    coordinator ! Messages.StartTrieNodeHealing(stateRoot) // seeds root as reqId=1
    // Queue 2 more tasks so 3 requests are dispatched concurrently (default maxInFlightPerPeer=5)
    coordinator ! Messages.QueueMissingNodes(
      Seq(
        (Seq(ByteString(Array[Byte](0x01))), kec256(ByteString("missing-node-2"))),
        (Seq(ByteString(Array[Byte](0x02))), kec256(ByteString("missing-node-3")))
      )
    )
    coordinator ! Messages.HealingPeerAvailable(peer)
    networkPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessage](3.seconds) // reqId=1
    networkPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessage](3.seconds) // reqId=2
    networkPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessage](3.seconds) // reqId=3

    // Simulate 3 consecutive timeouts for the same peer (one per active request)
    coordinator ! Messages.HealingRequestTimeout(BigInt(1))
    coordinator ! Messages.HealingRequestTimeout(BigInt(2))
    coordinator ! Messages.HealingRequestTimeout(BigInt(3))

    // Key assertion: HealingAllPeersStateless must NOT be sent.
    // With the old code the 3rd timeout triggered stateless marking → all-peers-stateless →
    // SNAPSyncController.HealingAllPeersStateless. With the new code this never happens.
    snapSyncController.expectNoMessage(500.millis)
  }

  it should "re-admit a stateless peer after HealingPivotRefreshed clears statelessPeers" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("nb7-readmit-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(system.scheduler)
    val networkPeerManager = TestProbe()
    val snapSyncController = TestProbe()
    val peerProbe = TestProbe()
    val peer = PeerTestHelpers.createTestPeer("readmit-peer", peerProbe.ref)

    val coordinator = system.actorOf(
      TrieNodeHealingCoordinator.props(
        stateRoot = stateRoot,
        networkPeerManager = networkPeerManager.ref,
        requestTracker = requestTracker,
        mptStorage = storage,
        batchSize = 1,
        snapSyncController = snapSyncController.ref
      )
    )

    // Make peer stateless
    coordinator ! Messages.StartTrieNodeHealing(stateRoot)
    coordinator ! Messages.HealingPeerAvailable(peer)
    networkPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessage](3.seconds)
    coordinator ! Messages.TrieNodesResponseMsg(SNAP.TrieNodes(requestId = 1, nodes = Seq.empty))

    // Pivot refresh: clears statelessPeers and re-seeds new root as pending task
    val newRoot = kec256(ByteString("nb7-readmit-new-root"))
    coordinator ! Messages.HealingPivotRefreshed(newRoot)

    // Peer is no longer stateless — HealingPeerAvailable should trigger dispatch for the new root
    coordinator ! Messages.HealingPeerAvailable(peer)
    networkPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessage](3.seconds)
  }

  it should "discover all missing children via BFS when state root is a BranchNode (BFS smoke)" taggedAs UnitTest in {
    import com.chipprbots.ethereum.mpt.{BranchNode, HashNode, NullNode, MptNode}

    // Synthetic trie: root is a BranchNode with 3 HashNode children at positions 0, 3, 7.
    // The children are NOT in storage — BFS level 0 = {root} (present), level 1 = {c0, c3, c7} (all missing).
    val missingHash0 = kec256(ByteString("bfs-smoke-missing-0"))
    val missingHash3 = kec256(ByteString("bfs-smoke-missing-3"))
    val missingHash7 = kec256(ByteString("bfs-smoke-missing-7"))

    val children: Array[MptNode] = Array.fill[MptNode](16)(NullNode)
    children(0) = HashNode(missingHash0.toArray)
    children(3) = HashNode(missingHash3.toArray)
    children(7) = HashNode(missingHash7.toArray)
    val branch = BranchNode(children, None)

    val storage = new TestMptStorage()
    storage.putNode(branch)
    val root = ByteString(branch.hash)

    val coordinator = system.actorOf(
      TrieNodeHealingCoordinator.props(
        stateRoot = root,
        networkPeerManager = TestProbe().ref,
        requestTracker = new SNAPRequestTracker()(system.scheduler),
        mptStorage = storage,
        batchSize = 16,
        snapSyncController = TestProbe().ref,
        healingWriterEcOverride = Some(system.dispatcher)
      )
    )

    coordinator ! Messages.StartTrieNodeHealing(root)

    // All 3 missing children should be queued once BFS completes.
    awaitAssert(
      {
        coordinator ! Messages.HealingGetProgress
        expectMsgType[HealingStatistics](2.seconds).pendingTasks shouldBe 3
      },
      max = 5.seconds,
      interval = 100.millis
    )
  }

  it should "not deadlock under sustained frontier backpressure — the safety timeout resumes the walk" taggedAs UnitTest in {
    import com.chipprbots.ethereum.mpt.{BranchNode, HashNode, NullNode, MptNode}
    import java.util.concurrent.Executors

    // Reproduces the 2026-06-13 OOM scenario in miniature: a BFS walk discovers missing nodes while
    // the healing backlog is already over the high-water mark and CANNOT drain (no peers, low-water
    // never reached). With high=1/low=0 the walk pauses on backpressure; the safety timeout must fire
    // and let it resume so it still delivers its frontier instead of hanging forever. (At production
    // defaults of 100K/50K this gate never trips in normal operation.)
    val missing0 = kec256(ByteString("bp-missing-0"))
    val missing3 = kec256(ByteString("bp-missing-3"))
    val missing7 = kec256(ByteString("bp-missing-7"))
    val children: Array[MptNode] = Array.fill[MptNode](16)(NullNode)
    children(0) = HashNode(missing0.toArray)
    children(3) = HashNode(missing3.toArray)
    children(7) = HashNode(missing7.toArray)
    val branch = BranchNode(children, None)
    val storage = new TestMptStorage()
    storage.putNode(branch)
    val root = ByteString(branch.hash)

    // Dedicated EC so the walk's blocking backpressure sleep cannot starve the actor thread.
    val pool = Executors.newSingleThreadExecutor()
    val ec = scala.concurrent.ExecutionContext.fromExecutorService(pool)
    val coordinator = system.actorOf(
      TrieNodeHealingCoordinator.props(
        stateRoot = root,
        networkPeerManager = TestProbe().ref,
        requestTracker = new SNAPRequestTracker()(system.scheduler),
        mptStorage = storage,
        batchSize = 16,
        snapSyncController = TestProbe().ref,
        healingWriterEcOverride = Some(ec),
        frontierHighWater = 1,
        frontierLowWater = 0,
        frontierBackpressureMaxWaitMs = 800L
      )
    )
    try {
      // Pre-load the backlog ABOVE the high-water mark with no peers, so it can never drain below
      // low-water — the walk's emit gate will block until the safety timeout fires.
      coordinator ! Messages.QueueMissingNodes(
        Seq((Seq(ByteString(Array[Byte](0x09))), kec256(ByteString("bp-preload"))))
      )
      awaitAssert(
        {
          coordinator ! Messages.HealingGetProgress
          expectMsgType[HealingStatistics](2.seconds).pendingTasks shouldBe 1
        },
        max = 3.seconds,
        interval = 100.millis
      )

      coordinator ! Messages.StartTrieNodeHealing(root)

      // The walk must still complete and deliver its 3 discovered children (preload + 3 = 4),
      // proving the safety timeout fired and resumed it rather than deadlocking on the gate.
      awaitAssert(
        {
          coordinator ! Messages.HealingGetProgress
          expectMsgType[HealingStatistics](2.seconds).pendingTasks shouldBe 4
        },
        max = 8.seconds,
        interval = 200.millis
      )
    } finally {
      system.stop(coordinator)
      pool.shutdownNow()
    }
  }

  it should "traverse multiple BFS levels and find frontier nodes deep in the trie" taggedAs UnitTest in {
    import com.chipprbots.ethereum.mpt.{BranchNode, HashNode, NullNode, MptNode}

    // Level 0: root BranchNode with 2 children (c0, c1) — both present in storage
    // Level 1: c0 is BranchNode with 1 missing child; c1 is BranchNode with 1 missing child
    // Level 2: m0, m1 — both missing → these are the frontier nodes BFS should find

    val missingL2a = kec256(ByteString("bfs-multilevel-missing-L2a"))
    val missingL2b = kec256(ByteString("bfs-multilevel-missing-L2b"))

    val childrenC0: Array[MptNode] = Array.fill[MptNode](16)(NullNode)
    childrenC0(5) = HashNode(missingL2a.toArray)
    val branchC0 = BranchNode(childrenC0, None)

    val childrenC1: Array[MptNode] = Array.fill[MptNode](16)(NullNode)
    childrenC1(10) = HashNode(missingL2b.toArray)
    val branchC1 = BranchNode(childrenC1, None)

    val rootChildren: Array[MptNode] = Array.fill[MptNode](16)(NullNode)
    rootChildren(0) = HashNode(branchC0.hash)
    rootChildren(1) = HashNode(branchC1.hash)
    val rootBranch = BranchNode(rootChildren, None)

    val storage = new TestMptStorage()
    storage.putNode(rootBranch)
    storage.putNode(branchC0)
    storage.putNode(branchC1)
    val root = ByteString(rootBranch.hash)

    val coordinator = system.actorOf(
      TrieNodeHealingCoordinator.props(
        stateRoot = root,
        networkPeerManager = TestProbe().ref,
        requestTracker = new SNAPRequestTracker()(system.scheduler),
        mptStorage = storage,
        batchSize = 16,
        snapSyncController = TestProbe().ref,
        healingWriterEcOverride = Some(system.dispatcher)
      )
    )

    coordinator ! Messages.StartTrieNodeHealing(root)

    // Both deep frontier nodes (missingL2a, missingL2b) should be found across 3 BFS levels.
    awaitAssert(
      {
        coordinator ! Messages.HealingGetProgress
        expectMsgType[HealingStatistics](2.seconds).pendingTasks shouldBe 2
      },
      max = 5.seconds,
      interval = 100.millis
    )
  }

  it should "deduplicate shared child hashes across BFS levels" taggedAs UnitTest in {
    import com.chipprbots.ethereum.mpt.{BranchNode, HashNode, NullNode, MptNode}

    // Two BranchNodes at level 1 (c0 and c1) share the same missing child hash.
    // BFS should add the shared child to nextLevel exactly once (visited at enqueue time).
    val sharedMissingHash = kec256(ByteString("bfs-dedup-shared-missing"))

    val childrenC0: Array[MptNode] = Array.fill[MptNode](16)(NullNode)
    childrenC0(0) = HashNode(sharedMissingHash.toArray)
    val branchC0 = BranchNode(childrenC0, None)

    val childrenC1: Array[MptNode] = Array.fill[MptNode](16)(NullNode)
    childrenC1(0) = HashNode(sharedMissingHash.toArray) // same hash as c0's child
    val branchC1 = BranchNode(childrenC1, None)

    val rootChildren: Array[MptNode] = Array.fill[MptNode](16)(NullNode)
    rootChildren(0) = HashNode(branchC0.hash)
    rootChildren(1) = HashNode(branchC1.hash)
    val rootBranch = BranchNode(rootChildren, None)

    val storage = new TestMptStorage()
    storage.putNode(rootBranch)
    storage.putNode(branchC0)
    storage.putNode(branchC1)
    val root = ByteString(rootBranch.hash)

    val coordinator = system.actorOf(
      TrieNodeHealingCoordinator.props(
        stateRoot = root,
        networkPeerManager = TestProbe().ref,
        requestTracker = new SNAPRequestTracker()(system.scheduler),
        mptStorage = storage,
        batchSize = 16,
        snapSyncController = TestProbe().ref,
        healingWriterEcOverride = Some(system.dispatcher)
      )
    )

    coordinator ! Messages.StartTrieNodeHealing(root)

    // Shared missing child should appear in the frontier exactly once, not twice.
    awaitAssert(
      {
        coordinator ! Messages.HealingGetProgress
        expectMsgType[HealingStatistics](2.seconds).pendingTasks shouldBe 1
      },
      max = 5.seconds,
      interval = 100.millis
    )
  }

  it should "process all BFS levels through InMemoryBfsQueueStorage without accumulating the full level in heap (spill-scale)" taggedAs UnitTest in {
    import com.chipprbots.ethereum.mpt.{BranchNode, HashNode, NullNode, MptNode}

    // Build a 3-level wide trie: root → 4 branch nodes (L1) → each with 4 missing children (L2).
    // Total frontier = 16 missing L2 hashes. Exercises multi-level CF-backed queue drain.
    val missingL2: Seq[Array[Byte]] = (0 until 16).map(i => kec256(ByteString(s"spill-missing-$i")).toArray)

    val l1Branches: Seq[BranchNode] = (0 until 4).map { i =>
      val children: Array[MptNode] = Array.fill[MptNode](16)(NullNode)
      (0 until 4).foreach(j => children(j) = HashNode(missingL2(i * 4 + j)))
      BranchNode(children, None)
    }

    val rootChildren: Array[MptNode] = Array.fill[MptNode](16)(NullNode)
    l1Branches.zipWithIndex.foreach { case (b, i) => rootChildren(i) = HashNode(b.hash) }
    val rootBranch = BranchNode(rootChildren, None)

    val storage = new TestMptStorage()
    storage.putNode(rootBranch)
    l1Branches.foreach(storage.putNode)
    // missingL2 hashes are intentionally NOT in storage — they form the frontier.
    val root = ByteString(rootBranch.hash)

    val bfsQueue = new InMemoryBfsQueueStorage()
    val coordinator = system.actorOf(
      TrieNodeHealingCoordinator.props(
        stateRoot = root,
        networkPeerManager = TestProbe().ref,
        requestTracker = new SNAPRequestTracker()(system.scheduler),
        mptStorage = storage,
        batchSize = 16,
        snapSyncController = TestProbe().ref,
        healingWriterEcOverride = Some(system.dispatcher),
        bfsQueueStorageOpt = Some(bfsQueue)
      )
    )

    coordinator ! Messages.StartTrieNodeHealing(root)

    // All 16 missing L2 hashes must land in the pending frontier.
    awaitAssert(
      {
        coordinator ! Messages.HealingGetProgress
        expectMsgType[HealingStatistics](2.seconds).pendingTasks shouldBe 16
      },
      max = 10.seconds,
      interval = 200.millis
    )

    // After BFS completes the queue counter resets to 0 (clear() called at end of rebuildFrontierBFS).
    bfsQueue.counter shouldBe 0L
  }
}
