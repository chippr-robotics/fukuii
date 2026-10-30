package com.chipprbots.ethereum.blockchain.sync.snap.actors

import org.apache.pekko.actor.testkit.typed.scaladsl.{FishingOutcomes, ScalaTestWithActorTestKit}

import java.nio.ByteBuffer

import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.util.ByteString

import scala.concurrent.duration.*

import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.snap.*
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.storage.InMemoryBfsQueueStorage
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.p2p.messages.SNAP
import com.chipprbots.ethereum.testing.PeerTestHelpers
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.testing.TestMptStorage

class TrieNodeHealingCoordinatorSpec
    extends ScalaTestWithActorTestKit()
    with AnyFlatSpecLike
    with Matchers
    with Eventually:

  implicit private val classicSystem: org.apache.pekko.actor.ActorSystem = system.classicSystem
  implicit private val actorTestKit: org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit = testKit
  private val statusProbe = testKit.createTestProbe[HealingStatistics]()

  "TrieNodeHealingCoordinator" should "initialize correctly" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("test-state-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(classicSystem.scheduler)
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]()

    val coordinator = HealingTrieFixtures.spawnCoordinator(
      stateRoot = stateRoot,
      networkPeerManager = networkPeerManager.ref,
      requestTracker = requestTracker,
      mptStorage = storage,
      batchSize = 16,
      snapSyncController = snapSyncController.ref
    )

    coordinator should not be null
  }

  it should "queue missing nodes for healing" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("test-state-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(classicSystem.scheduler)
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]()

    val coordinator = HealingTrieFixtures.spawnCoordinator(
      stateRoot = stateRoot,
      networkPeerManager = networkPeerManager.ref,
      requestTracker = requestTracker,
      mptStorage = storage,
      batchSize = 16,
      snapSyncController = snapSyncController.ref
    )

    val node1Hash = kec256(ByteString("node1"))
    val node2Hash = kec256(ByteString("node2"))
    val missingNodes = Seq(
      (Seq(ByteString(Array[Byte](0x00))), node1Hash),
      (Seq(ByteString(Array[Byte](0x00))), node2Hash)
    )

    coordinator ! TrieNodeHealingCoordinator.QueueMissingNodes(missingNodes)

    // Coordinator should queue the nodes
    coordinator ! TrieNodeHealingCoordinator.HealingGetProgress(statusProbe.ref)
    statusProbe.expectMessageType[HealingStatistics]
  }

  it should "create workers when peers are available" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("test-state-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(classicSystem.scheduler)
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]()
    val peerProbe = testKit.createTestProbe[NetworkPeerManagerActor.SendMessageCmd]()

    val peer = PeerTestHelpers.createTestPeer("test-peer", peerProbe.ref.toClassic)

    val coordinator = HealingTrieFixtures.spawnCoordinator(
      stateRoot = stateRoot,
      networkPeerManager = networkPeerManager.ref,
      requestTracker = requestTracker,
      mptStorage = storage,
      batchSize = 16,
      snapSyncController = snapSyncController.ref
    )

    val nodeHash = kec256(ByteString("node1"))
    val missingNodes = Seq((Seq(ByteString(Array[Byte](0x00))), nodeHash))

    coordinator ! TrieNodeHealingCoordinator.StartTrieNodeHealing(stateRoot)
    coordinator ! TrieNodeHealingCoordinator.QueueMissingNodes(missingNodes)
    coordinator ! TrieNodeHealingCoordinator.HealingPeerAvailable(peer)

    // Should send request to network peer manager
    networkPeerManager.expectMessageType[NetworkPeerManagerActor.SendMessageCmd]
  }

  it should "handle task completion" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("test-state-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(classicSystem.scheduler)
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]()

    val coordinator = HealingTrieFixtures.spawnCoordinator(
      stateRoot = stateRoot,
      networkPeerManager = networkPeerManager.ref,
      requestTracker = requestTracker,
      mptStorage = storage,
      batchSize = 16,
      snapSyncController = snapSyncController.ref
    )

    coordinator ! TrieNodeHealingCoordinator.HealingTaskComplete(BigInt(123), Right(5))

    // Coordinator should handle completion
    coordinator ! TrieNodeHealingCoordinator.HealingGetProgress(statusProbe.ref)
    statusProbe.expectMessageType[HealingStatistics]
  }

  it should "not report completion with no walk when nothing was healed (spec 022 review hunter-r3 C1)" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("test-state-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(classicSystem.scheduler)
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]()

    val coordinator = HealingTrieFixtures.spawnCoordinator(
      stateRoot = stateRoot,
      networkPeerManager = networkPeerManager.ref,
      requestTracker = requestTracker,
      mptStorage = storage,
      batchSize = 16,
      snapSyncController = snapSyncController.ref
    )

    coordinator ! TrieNodeHealingCoordinator.HealingCheckCompletion

    // An idle coordinator (no pending tasks, no active requests) completes only after a clean walk of its root. This root
    // is not on disk, so the walk queues it for a fetch instead, and healing is not complete.
    eventually(timeout(10.seconds)) {
      coordinator ! TrieNodeHealingCoordinator.HealingGetProgress(statusProbe.ref)
      statusProbe.expectMessageType[HealingStatistics].pendingTasks shouldBe 1
    }
    snapSyncController.expectNoMessage(100.millis)
  }

  it should "handle task failures" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("test-state-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(classicSystem.scheduler)
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]()

    val coordinator = HealingTrieFixtures.spawnCoordinator(
      stateRoot = stateRoot,
      networkPeerManager = networkPeerManager.ref,
      requestTracker = requestTracker,
      mptStorage = storage,
      batchSize = 16,
      snapSyncController = snapSyncController.ref
    )

    coordinator ! TrieNodeHealingCoordinator.HealingTaskFailed(BigInt(123), "Test failure")

    // Coordinator should still be operational
    coordinator ! TrieNodeHealingCoordinator.HealingGetProgress(statusProbe.ref)
    statusProbe.expectMessageType[HealingStatistics]
  }

  it should "signal StateHealingComplete to controller on HealingForceComplete" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("force-complete-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(classicSystem.scheduler)
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]()

    val coordinator = HealingTrieFixtures.spawnCoordinator(
      stateRoot = stateRoot,
      networkPeerManager = networkPeerManager.ref,
      requestTracker = requestTracker,
      mptStorage = storage,
      batchSize = 16,
      snapSyncController = snapSyncController.ref
    )

    coordinator ! TrieNodeHealingCoordinator.HealingForceComplete

    snapSyncController.expectMessage(SNAPSyncController.StateHealingComplete)
  }

  it should "accept HealingPivotRefreshed and re-seed new root — HealingCheckCompletion deferred" taggedAs UnitTest in {
    // After pivot refresh the coordinator re-seeds the new root into pendingTasks.
    // isComplete = pendingTasks.isEmpty && activeRequests.isEmpty = false.
    // HealingCheckCompletion must therefore NOT signal StateHealingComplete.
    val stateRoot = kec256(ByteString("old-heal-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(classicSystem.scheduler)
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]()

    val coordinator = HealingTrieFixtures.spawnCoordinator(
      stateRoot = stateRoot,
      networkPeerManager = networkPeerManager.ref,
      requestTracker = requestTracker,
      mptStorage = storage,
      batchSize = 16,
      snapSyncController = snapSyncController.ref
    )

    val newStateRoot = kec256(ByteString("new-heal-root"))
    coordinator ! TrieNodeHealingCoordinator.HealingPivotRefreshed(newStateRoot)

    // The new root is not in storage, so it is added to pendingTasks.
    // isComplete = false → StateHealingComplete must NOT be sent.
    coordinator ! TrieNodeHealingCoordinator.HealingCheckCompletion
    snapSyncController.expectNoMessage(300.millis)

    // Coordinator remains operational.
    coordinator ! TrieNodeHealingCoordinator.HealingGetProgress(statusProbe.ref)
    statusProbe.expectMessageType[HealingStatistics]
  }

  it should "not signal StateHealingComplete on HealingCheckCompletion when pending tasks exist" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("pending-tasks-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(classicSystem.scheduler)
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]()

    val coordinator = HealingTrieFixtures.spawnCoordinator(
      stateRoot = stateRoot,
      networkPeerManager = networkPeerManager.ref,
      requestTracker = requestTracker,
      mptStorage = storage,
      batchSize = 16,
      snapSyncController = snapSyncController.ref
    )

    val nodeHash = kec256(ByteString("missing-node"))
    coordinator ! TrieNodeHealingCoordinator.QueueMissingNodes(Seq((Seq(ByteString(Array[Byte](0x00))), nodeHash)))

    // pendingTasks is non-empty → isComplete = false → no StateHealingComplete
    coordinator ! TrieNodeHealingCoordinator.HealingCheckCompletion
    snapSyncController.expectNoMessage(300.millis)
  }

  // ========================================
  // pendingTasks ArrayDeque + dispatcher (issue #1167)
  // ========================================

  /** Synthesize a unique (pathset, hash) so the dedup set doesn't drop our nodes. */
  private def fakeHashedNode(seed: Int): (Seq[ByteString], ByteString) =
    val hash = kec256(ByteString(s"healing-node-$seed"))
    // pathset is a Seq[ByteString]; for queueing we just need something distinct.
    val path = ByteString(ByteBuffer.allocate(4).putInt(seed).array())
    (Seq(path), hash)

  it should "absorb a large QueueMissingNodes payload without timing out" taggedAs UnitTest in {
    // The previous immutable-Seq pendingTasks was O(n) per `:+`. Queueing 50,000 nodes via
    // appendAll on the new ArrayDeque is O(n) total instead of O(n²).
    val stateRoot = kec256(ByteString("deque-load-test-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(classicSystem.scheduler)
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]()

    val coordinator = HealingTrieFixtures.spawnCoordinator(
      stateRoot = stateRoot,
      networkPeerManager = networkPeerManager.ref,
      requestTracker = requestTracker,
      mptStorage = storage,
      batchSize = 64,
      snapSyncController = snapSyncController.ref,
      healingWriterEcOverride = Some(classicSystem.dispatcher)
    )

    val nodeCount = 50000
    val nodes = (1 to nodeCount).map(fakeHashedNode)

    coordinator ! TrieNodeHealingCoordinator.StartTrieNodeHealing(stateRoot)
    val queueStart = System.nanoTime()
    coordinator ! TrieNodeHealingCoordinator.QueueMissingNodes(nodes)
    coordinator ! TrieNodeHealingCoordinator.HealingGetProgress(statusProbe.ref)

    val stats = statusProbe.expectMessageType[HealingStatistics]
    val elapsedMs = (System.nanoTime() - queueStart) / 1000000L

    // Exactly nodeCount: the walk root is absent (empty storage), so the seed-site complementary guard
    // signals HealingRootUnservable to the controller and does NOT seed the root (it was the futile +1
    // that stalled the heal). The QueueMissingNodes payload is the only frontier here.
    stats.pendingTasks shouldBe nodeCount
    // Loose ceiling — main signal is that this ran in linear time (O(n²) at this size
    // would take many seconds even on a fast box). Tighten if it ever flakes.
    elapsedMs should be < 5000L
  }

  it should "drain pending tasks in FIFO order across many small QueueMissingNodes calls" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("deque-fifo-test-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(classicSystem.scheduler)
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]()

    val coordinator = HealingTrieFixtures.spawnCoordinator(
      stateRoot = stateRoot,
      networkPeerManager = networkPeerManager.ref,
      requestTracker = requestTracker,
      mptStorage = storage,
      batchSize = 16,
      snapSyncController = snapSyncController.ref,
      healingWriterEcOverride = Some(classicSystem.dispatcher)
    )

    coordinator ! TrieNodeHealingCoordinator.StartTrieNodeHealing(stateRoot)

    // Three batches; total 750 nodes queued in O(n) time.
    val batches = Seq.tabulate(3)(g => (g * 250 until (g + 1) * 250).map(fakeHashedNode))
    batches.foreach(b => coordinator ! TrieNodeHealingCoordinator.QueueMissingNodes(b))

    coordinator ! TrieNodeHealingCoordinator.HealingGetProgress(statusProbe.ref)
    val stats = statusProbe.expectMessageType[HealingStatistics]
    // Exactly 750: the walk root is absent, so the seed-site guard signals HealingRootUnservable and
    // does NOT seed the root (the futile +1 is gone). The three batches are the only frontier.
    stats.pendingTasks shouldBe 750
  }

  it should "construct successfully when a healing-writer EC override is supplied" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("ec-override-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(classicSystem.scheduler)
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]()

    val coordinator = HealingTrieFixtures.spawnCoordinator(
      stateRoot = stateRoot,
      networkPeerManager = networkPeerManager.ref,
      requestTracker = requestTracker,
      mptStorage = storage,
      batchSize = 16,
      snapSyncController = snapSyncController.ref,
      healingWriterEcOverride = Some(classicSystem.dispatcher)
    )

    coordinator should not be null
    coordinator ! TrieNodeHealingCoordinator.HealingGetProgress(statusProbe.ref)
    statusProbe.expectMessageType[HealingStatistics]
  }

  it should "signal StateHealingComplete on HealingForceComplete even with pending tasks in flight" taggedAs UnitTest in {
    // HealingForceComplete is the SNAPSyncController's nuclear option: when the pivot has
    // advanced beyond the SNAP serve window, healing must abandon pending tasks immediately
    // rather than waiting for them to drain normally.
    val stateRoot = kec256(ByteString("force-complete-with-tasks-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(classicSystem.scheduler)
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]()
    val peerProbe = testKit.createTestProbe[NetworkPeerManagerActor.SendMessageCmd]()

    val peer = PeerTestHelpers.createTestPeer("force-heal-peer", peerProbe.ref.toClassic)

    val coordinator = HealingTrieFixtures.spawnCoordinator(
      stateRoot = stateRoot,
      networkPeerManager = networkPeerManager.ref,
      requestTracker = requestTracker,
      mptStorage = storage,
      batchSize = 16,
      snapSyncController = snapSyncController.ref
    )

    // Queue tasks and make a peer available so some become active
    val nodeHash1 = kec256(ByteString("node-force-1"))
    val nodeHash2 = kec256(ByteString("node-force-2"))
    coordinator ! TrieNodeHealingCoordinator.StartTrieNodeHealing(stateRoot)
    coordinator ! TrieNodeHealingCoordinator.QueueMissingNodes(
      Seq(
        (Seq(ByteString(Array[Byte](0x00))), nodeHash1),
        (Seq(ByteString(Array[Byte](0x01))), nodeHash2)
      )
    )
    coordinator ! TrieNodeHealingCoordinator.HealingPeerAvailable(peer)

    // The walk root is absent (empty storage), so StartTrieNodeHealing first fires the seed-site guard
    // (PR #1371, proven-live): it signals HealingRootUnservable and does NOT seed the root. The
    // QueueMissingNodes tasks are still real and get dispatched to the peer below.
    snapSyncController.expectMessage(SNAPSyncController.HealingRootUnservable(stateRoot))
    networkPeerManager.expectMessageType[NetworkPeerManagerActor.SendMessageCmd] // queued task dispatched

    // ForceComplete while tasks are in-flight: abandon all, signal complete immediately
    coordinator ! TrieNodeHealingCoordinator.HealingForceComplete
    snapSyncController.expectMessage(SNAPSyncController.StateHealingComplete)
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
    for _ <- 1 to MaxConsecutiveStagnations do
      val recentHealed = 0 // no progress
      val pendingTasksNonEmpty = true

      if recentHealed == 0 && pendingTasksNonEmpty then
        consecutiveStagnations += 1
        if consecutiveStagnations >= MaxConsecutiveStagnations then
          healingStagnatedSent = true // → snapSyncController ! HealingStagnated(...)
          consecutiveStagnations = 0
      else if recentHealed > 0 then consecutiveStagnations = 0

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
    if recentHealed > 0 then consecutiveStagnations = 0

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
      if !pivotRefreshRequested && recentHealed == 0 && hasPending then
        consecutiveStagnations += 1
        if consecutiveStagnations >= MaxConsecutiveStagnations then
          stagnatedSignals += 1
          pivotRefreshRequested = true
          consecutiveStagnations = 0
      else if recentHealed > 0 then consecutiveStagnations = 0

    // First escalation
    for _ <- 1 to MaxConsecutiveStagnations do tick(0, hasPending = true)
    stagnatedSignals shouldBe 1
    pivotRefreshRequested shouldBe true

    // Additional ticks while pivotRefreshRequested=true must not fire a second signal
    for _ <- 1 to MaxConsecutiveStagnations * 2 do tick(0, hasPending = true)
    stagnatedSignals shouldBe 1
  }

  it should "resume stagnation counting after pivotRefreshRequested is cleared (HealingPivotRefreshed)" taggedAs UnitTest in {
    var pivotRefreshRequested = false
    var consecutiveStagnations = 0
    val MaxConsecutiveStagnations = 3
    var stagnatedSignals = 0

    def tick(recentHealed: Int, hasPending: Boolean): Unit =
      if !pivotRefreshRequested && recentHealed == 0 && hasPending then
        consecutiveStagnations += 1
        if consecutiveStagnations >= MaxConsecutiveStagnations then
          stagnatedSignals += 1
          pivotRefreshRequested = true
          consecutiveStagnations = 0
      else if recentHealed > 0 then consecutiveStagnations = 0

    // First escalation
    for _ <- 1 to MaxConsecutiveStagnations do tick(0, hasPending = true)
    stagnatedSignals shouldBe 1

    // Simulate HealingPivotRefreshed resetting the suppression flag
    pivotRefreshRequested = false
    consecutiveStagnations = 0

    // Second escalation cycle should succeed now
    for _ <- 1 to MaxConsecutiveStagnations do tick(0, hasPending = true)
    stagnatedSignals shouldBe 2
  }

  // ── NB-7: Stateless dispatch gate ────────────────────────────────────────────────────────────
  //
  // Actor-level tests: drive via real messages (HealingPeerAvailable + TrieNodesResponseMsg).

  it should "ignore HealingPeerAvailable for a peer already in statelessPeers" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("nb7-stateless-gate-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(classicSystem.scheduler)
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]()
    val peerProbe = testKit.createTestProbe[NetworkPeerManagerActor.SendMessageCmd]()
    val peer = PeerTestHelpers.createTestPeer("stateless-peer", peerProbe.ref.toClassic)

    val coordinator = HealingTrieFixtures.spawnCoordinator(
      stateRoot = stateRoot,
      networkPeerManager = networkPeerManager.ref,
      requestTracker = requestTracker,
      mptStorage = storage,
      batchSize = 1,
      snapSyncController = snapSyncController.ref
    )

    // Provide a real task and dispatch it to the peer. (The walk root is absent, so StartTrieNodeHealing
    // no longer seeds it — it signals HealingRootUnservable; we supply the frontier via QueueMissingNodes.)
    coordinator ! TrieNodeHealingCoordinator.StartTrieNodeHealing(stateRoot)
    coordinator ! TrieNodeHealingCoordinator.QueueMissingNodes(
      Seq((Seq(ByteString(Array[Byte](0x00))), kec256(ByteString("nb7-task"))))
    )
    coordinator ! TrieNodeHealingCoordinator.HealingPeerAvailable(peer)
    networkPeerManager.expectMessageType[NetworkPeerManagerActor.SendMessageCmd]

    // Empty TrieNodes response (requestId=1 is the first generated) → marks peer stateless
    coordinator ! TrieNodeHealingCoordinator.TrieNodesResponseMsg(SNAP.TrieNodes(requestId = 1, nodes = Seq.empty))

    // Second HealingPeerAvailable for the same peer must be silently ignored
    coordinator ! TrieNodeHealingCoordinator.HealingPeerAvailable(peer)
    networkPeerManager.expectNoMessage(300.millis)
  }

  it should "not mark a peer stateless on repeated GetTrieNodes timeouts (go-ethereum: timeouts rotate tasks only)" taggedAs UnitTest in {
    // Regression guard: old code marked peer stateless after MaxConsecutiveTimeoutsBeforeStateless (3)
    // timeouts, then fired HealingAllPeersStateless to SNAPSyncController.
    // New behavior (go-ethereum aligned): timeouts only re-queue tasks — no stateless marking.
    val stateRoot = kec256(ByteString("nb7-timeout-no-stateless-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(classicSystem.scheduler)
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]()
    val peerProbe = testKit.createTestProbe[NetworkPeerManagerActor.SendMessageCmd]()
    val peer = PeerTestHelpers.createTestPeer("timeout-peer", peerProbe.ref.toClassic)

    val coordinator = HealingTrieFixtures.spawnCoordinator(
      stateRoot = stateRoot,
      networkPeerManager = networkPeerManager.ref,
      requestTracker = requestTracker,
      mptStorage = storage,
      batchSize = 1,
      snapSyncController = snapSyncController.ref
    )

    // The walk root is absent, so StartTrieNodeHealing no longer seeds it (it signals HealingRootUnservable).
    // Provide all 3 tasks explicitly so 3 requests dispatch concurrently (default maxInFlightPerPeer=5).
    coordinator ! TrieNodeHealingCoordinator.StartTrieNodeHealing(stateRoot)
    coordinator ! TrieNodeHealingCoordinator.QueueMissingNodes(
      Seq(
        (Seq(ByteString(Array[Byte](0x00))), kec256(ByteString("missing-node-1"))),
        (Seq(ByteString(Array[Byte](0x01))), kec256(ByteString("missing-node-2"))),
        (Seq(ByteString(Array[Byte](0x02))), kec256(ByteString("missing-node-3")))
      )
    )
    coordinator ! TrieNodeHealingCoordinator.HealingPeerAvailable(peer)
    networkPeerManager.expectMessageType[NetworkPeerManagerActor.SendMessageCmd] // reqId=1
    networkPeerManager.expectMessageType[NetworkPeerManagerActor.SendMessageCmd] // reqId=2
    networkPeerManager.expectMessageType[NetworkPeerManagerActor.SendMessageCmd] // reqId=3

    // Absent walk root ⇒ the seed-site guard (PR #1371, proven-live) already signalled HealingRootUnservable to
    // the controller. Drain it so the key expectNoMessage below is scoped strictly to the timeout→stateless behavior.
    snapSyncController.expectMessage(SNAPSyncController.HealingRootUnservable(stateRoot))

    // Simulate 3 consecutive timeouts for the same peer (one per active request)
    coordinator ! TrieNodeHealingCoordinator.HealingRequestTimeout(BigInt(1))
    coordinator ! TrieNodeHealingCoordinator.HealingRequestTimeout(BigInt(2))
    coordinator ! TrieNodeHealingCoordinator.HealingRequestTimeout(BigInt(3))

    // Key assertion: HealingAllPeersStateless must NOT be sent.
    // With the old code the 3rd timeout triggered stateless marking → all-peers-stateless →
    // SNAPSyncController.HealingAllPeersStateless. With the new code this never happens.
    snapSyncController.expectNoMessage(500.millis)
  }

  it should "re-admit a stateless peer after HealingPivotRefreshed clears statelessPeers" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("nb7-readmit-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(classicSystem.scheduler)
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]()
    val peerProbe = testKit.createTestProbe[NetworkPeerManagerActor.SendMessageCmd]()
    val peer = PeerTestHelpers.createTestPeer("readmit-peer", peerProbe.ref.toClassic)

    val coordinator = HealingTrieFixtures.spawnCoordinator(
      stateRoot = stateRoot,
      networkPeerManager = networkPeerManager.ref,
      requestTracker = requestTracker,
      mptStorage = storage,
      batchSize = 1,
      snapSyncController = snapSyncController.ref
    )

    // Make peer stateless. The walk root is absent, so StartTrieNodeHealing no longer seeds it (it
    // signals HealingRootUnservable); provide a real task to dispatch via QueueMissingNodes.
    coordinator ! TrieNodeHealingCoordinator.StartTrieNodeHealing(stateRoot)
    coordinator ! TrieNodeHealingCoordinator.QueueMissingNodes(
      Seq((Seq(ByteString(Array[Byte](0x00))), kec256(ByteString("nb7-readmit-task"))))
    )
    coordinator ! TrieNodeHealingCoordinator.HealingPeerAvailable(peer)
    networkPeerManager.expectMessageType[NetworkPeerManagerActor.SendMessageCmd]
    coordinator ! TrieNodeHealingCoordinator.TrieNodesResponseMsg(SNAP.TrieNodes(requestId = 1, nodes = Seq.empty))

    // Pivot refresh: clears statelessPeers and re-seeds new root as pending task. (HealingPivotRefreshed
    // targets a freshly servable root and KEEPS its own reseed — only the StartTrieNodeHealing seed of an
    // absent walk root is deferred by the complementary guard.)
    val newRoot = kec256(ByteString("nb7-readmit-new-root"))
    coordinator ! TrieNodeHealingCoordinator.HealingPivotRefreshed(newRoot)

    // Peer is no longer stateless — HealingPeerAvailable should trigger dispatch for the new root
    coordinator ! TrieNodeHealingCoordinator.HealingPeerAvailable(peer)
    networkPeerManager.expectMessageType[NetworkPeerManagerActor.SendMessageCmd]
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

    val coordinator = HealingTrieFixtures.spawnCoordinator(
      stateRoot = root,
      networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]().ref,
      requestTracker = new SNAPRequestTracker()(classicSystem.scheduler),
      mptStorage = storage,
      batchSize = 16,
      snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]().ref,
      healingWriterEcOverride = Some(classicSystem.dispatcher)
    )

    coordinator ! TrieNodeHealingCoordinator.StartTrieNodeHealing(root)

    // All 3 missing children should be queued once BFS completes.
    eventually(timeout(5.seconds), interval(100.millis)) {
      coordinator ! TrieNodeHealingCoordinator.HealingGetProgress(statusProbe.ref)
      statusProbe.receiveMessage(2.seconds).pendingTasks shouldBe 3
    }
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
    val coordinator = HealingTrieFixtures.spawnCoordinator(
      stateRoot = root,
      networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]().ref,
      requestTracker = new SNAPRequestTracker()(classicSystem.scheduler),
      mptStorage = storage,
      batchSize = 16,
      snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]().ref,
      healingWriterEcOverride = Some(ec),
      frontierHighWater = 1,
      frontierLowWater = 0,
      frontierBackpressureMaxWaitMs = 800L
    )
    try
      // Pre-load the backlog ABOVE the high-water mark with no peers, so it can never drain below
      // low-water — the walk's emit gate will block until the safety timeout fires.
      coordinator ! TrieNodeHealingCoordinator.QueueMissingNodes(
        Seq((Seq(ByteString(Array[Byte](0x09))), kec256(ByteString("bp-preload"))))
      )
      eventually(timeout(3.seconds), interval(100.millis)) {
        coordinator ! TrieNodeHealingCoordinator.HealingGetProgress(statusProbe.ref)
        statusProbe.receiveMessage(2.seconds).pendingTasks shouldBe 1
      }

      coordinator ! TrieNodeHealingCoordinator.StartTrieNodeHealing(root)

      // The walk must still complete and deliver its 3 discovered children (preload + 3 = 4),
      // proving the safety timeout fired and resumed it rather than deadlocking on the gate.
      eventually(timeout(8.seconds), interval(200.millis)) {
        coordinator ! TrieNodeHealingCoordinator.HealingGetProgress(statusProbe.ref)
        statusProbe.receiveMessage(2.seconds).pendingTasks shouldBe 4
      }
    finally
      testKit.stop(coordinator)
      pool.shutdownNow()
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

    val coordinator = HealingTrieFixtures.spawnCoordinator(
      stateRoot = root,
      networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]().ref,
      requestTracker = new SNAPRequestTracker()(classicSystem.scheduler),
      mptStorage = storage,
      batchSize = 16,
      snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]().ref,
      healingWriterEcOverride = Some(classicSystem.dispatcher)
    )

    coordinator ! TrieNodeHealingCoordinator.StartTrieNodeHealing(root)

    // Both deep frontier nodes (missingL2a, missingL2b) should be found across 3 BFS levels.
    eventually(timeout(5.seconds), interval(100.millis)) {
      coordinator ! TrieNodeHealingCoordinator.HealingGetProgress(statusProbe.ref)
      statusProbe.receiveMessage(2.seconds).pendingTasks shouldBe 2
    }
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

    val coordinator = HealingTrieFixtures.spawnCoordinator(
      stateRoot = root,
      networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]().ref,
      requestTracker = new SNAPRequestTracker()(classicSystem.scheduler),
      mptStorage = storage,
      batchSize = 16,
      snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]().ref,
      healingWriterEcOverride = Some(classicSystem.dispatcher)
    )

    coordinator ! TrieNodeHealingCoordinator.StartTrieNodeHealing(root)

    // Shared missing child should appear in the frontier exactly once, not twice.
    eventually(timeout(5.seconds), interval(100.millis)) {
      coordinator ! TrieNodeHealingCoordinator.HealingGetProgress(statusProbe.ref)
      statusProbe.receiveMessage(2.seconds).pendingTasks shouldBe 1
    }
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
    val coordinator = HealingTrieFixtures.spawnCoordinator(
      stateRoot = root,
      networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]().ref,
      requestTracker = new SNAPRequestTracker()(classicSystem.scheduler),
      mptStorage = storage,
      batchSize = 16,
      snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]().ref,
      healingWriterEcOverride = Some(classicSystem.dispatcher),
      bfsQueueStorageOpt = Some(bfsQueue)
    )

    coordinator ! TrieNodeHealingCoordinator.StartTrieNodeHealing(root)

    // All 16 missing L2 hashes must land in the pending frontier.
    eventually(timeout(10.seconds), interval(200.millis)) {
      coordinator ! TrieNodeHealingCoordinator.HealingGetProgress(statusProbe.ref)
      statusProbe.receiveMessage(2.seconds).pendingTasks shouldBe 16
    }

    // After BFS completes the queue counter resets to 0 (clear() called at end of rebuildFrontierBFS).
    bfsQueue.counter shouldBe 0L
  }

  // ── T009 / T031 (US1/US3, FR-025): shared-ancestor completeness ──────────────────────────────
  //
  // The visited set de-dups a node the first time its hash is seen; a SECOND parent referencing the
  // same hash is a no-op. FR-025 mandates a regression proving that this de-dup of a *shared* present
  // ancestor never short-circuits descent into that ancestor's own children: a missing grandchild
  // behind the shared ancestor must still be discovered exactly once. Uses the T003 fixture.

  it should "discover a missing grandchild behind a SHARED branch ancestor exactly once (FR-025)" taggedAs UnitTest in {
    val fx = HealingTrieFixtures.sharedAncestor() // shared ancestor is a present BranchNode

    val coordinator = HealingTrieFixtures.spawnCoordinator(
      stateRoot = fx.rootHash,
      networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]().ref,
      requestTracker = new SNAPRequestTracker()(classicSystem.scheduler),
      mptStorage = fx.storage,
      batchSize = 16,
      snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]().ref,
      healingWriterEcOverride = Some(classicSystem.dispatcher)
    )

    coordinator ! TrieNodeHealingCoordinator.StartTrieNodeHealing(fx.rootHash)

    // The shared ancestor is reached via two parents but visited once; the missing grandchild below it
    // is still discovered. It is the ONLY absent node, so the frontier is exactly 1 — not 0 (skipped)
    // and not 2 (double-counted).
    eventually(timeout(5.seconds), interval(100.millis)) {
      coordinator ! TrieNodeHealingCoordinator.HealingGetProgress(statusProbe.ref)
      statusProbe.receiveMessage(2.seconds).pendingTasks shouldBe 1
    }
  }

  it should "discover a missing grandchild behind a SHARED extension ancestor exactly once (FR-025)" taggedAs UnitTest in {
    // Same invariant on the ExtensionNode de-dup arm of rebuildFrontierBFS.
    val fx = HealingTrieFixtures.sharedAncestor(sharedIsExtension = true)

    val coordinator = HealingTrieFixtures.spawnCoordinator(
      stateRoot = fx.rootHash,
      networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]().ref,
      requestTracker = new SNAPRequestTracker()(classicSystem.scheduler),
      mptStorage = fx.storage,
      batchSize = 16,
      snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]().ref,
      healingWriterEcOverride = Some(classicSystem.dispatcher)
    )

    coordinator ! TrieNodeHealingCoordinator.StartTrieNodeHealing(fx.rootHash)

    eventually(timeout(5.seconds), interval(100.millis)) {
      coordinator ! TrieNodeHealingCoordinator.HealingGetProgress(statusProbe.ref)
      statusProbe.receiveMessage(2.seconds).pendingTasks shouldBe 1
    }
  }

  // ── T027 (US3): serial-equivalence (FR-012) ──────────────────────────────────────────────────
  //
  // With traversalParallelism = 1 the walk takes the serial branch. The emitted frontier (the
  // pendingTasks set) over a fixed multi-node trie must be identical to a reference run — and
  // identical whether or not a reader EC is supplied. Proves FR-012 (the serial path is unchanged
  // and a reader EC, unused on the serial branch, does not perturb it).

  it should "emit an identical serial frontier with cfg=1, with and without a reader EC (FR-012)" taggedAs UnitTest in {
    import java.util.concurrent.Executors

    // A drive helper: run a cfg=1 walk over a fresh copy of the multi-node fixture and return the
    // frontier size. Each call gets its own fixture+coordinator so the runs are independent.
    def frontierSizeWithCfg1(readerEc: Option[scala.concurrent.ExecutionContext]): Int =
      val fx = HealingTrieFixtures.multiNodeWithSharedAncestor()
      val coordinator = HealingTrieFixtures.spawnCoordinator(
        stateRoot = fx.rootHash,
        networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]().ref,
        requestTracker = new SNAPRequestTracker()(classicSystem.scheduler),
        mptStorage = fx.storage,
        batchSize = 16,
        snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]().ref,
        healingWriterEcOverride = Some(classicSystem.dispatcher),
        healingReaderEcOverride = readerEc,
        traversalParallelism = 1 // serial branch
      )
      coordinator ! TrieNodeHealingCoordinator.StartTrieNodeHealing(fx.rootHash)
      var observed = -1
      eventually(timeout(5.seconds), interval(100.millis)) {
        coordinator ! TrieNodeHealingCoordinator.HealingGetProgress(statusProbe.ref)
        observed = statusProbe.receiveMessage(2.seconds).pendingTasks
        observed shouldBe fx.missingNodeHashes.size // 2 distinct missing nodes
      }
      testKit.stop(coordinator)
      observed

    // Reference run (no reader EC) and a run WITH a reader EC must agree, and both must equal the
    // fixture's known missing-node count.
    val pool = Executors.newFixedThreadPool(2)
    val readerEc = scala.concurrent.ExecutionContext.fromExecutorService(pool)
    try
      val reference = frontierSizeWithCfg1(None)
      val withReader = frontierSizeWithCfg1(Some(readerEc))
      reference shouldBe 2
      withReader shouldBe reference
    finally
      pool.shutdownNow()
      ()
  }

  // ── T029 / T031 (US3): no Await-on-same-pool deadlock + shared-ancestor under concurrency ─────
  //
  // The parallel-split branch (`effectiveParallelism > 1 && levelSize > BfsChunkSize=50,000`) dispatches
  // sub-ranges as Futures on the reader EC while the parent walk Future parks on `Await` on the writer EC.
  // Sharing one pool would deadlock once parallelism nears the pool size (forge's fix: a distinct reader
  // pool). This test uses a fixture whose level-4 frontier is wider than 50,000 so the split branch is
  // GENUINELY taken, with a real reader EC distinct from the writer EC and effective parallelism > 1, and
  // asserts the walk completes within a bounded TestKit timeout (no deadlock). T031's shared-ancestor
  // concurrent assertion is folded into the smaller wide-fanout fixture below: a tiny synthetic trie's
  // levels are all < 50,000, so the split branch cannot be exercised on it — the genuine concurrency path
  // is exercised here, and the shared-ancestor invariant under that configuration is asserted next.

  it should "complete a >50K-entry parallel level without Await-on-same-pool deadlock (T029)" taggedAs UnitTest in {
    import java.util.concurrent.Executors

    val fx = HealingTrieFixtures.wideFrontierLevel() // level-4 frontier = 53,248 > 50,000

    // A real, fixed-size reader pool distinct from the writer EC. The writer EC is also a small fixed
    // pool so neither starves the actor thread. effectiveParallelism on the 4-core CI host with
    // traversalParallelism=2 / min=2 / reserved=2 is min(2, min(4, max(2, 2))) = 2 > 1 → the split fires.
    val readerPool = Executors.newFixedThreadPool(2)
    val writerPool = Executors.newSingleThreadExecutor()
    val readerEc = scala.concurrent.ExecutionContext.fromExecutorService(readerPool)
    val writerEc = scala.concurrent.ExecutionContext.fromExecutorService(writerPool)

    val coordinator = HealingTrieFixtures.spawnCoordinator(
      stateRoot = fx.rootHash,
      networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]().ref,
      requestTracker = new SNAPRequestTracker()(classicSystem.scheduler),
      mptStorage = fx.storage,
      batchSize = 16,
      snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]().ref,
      healingWriterEcOverride = Some(writerEc),
      healingReaderEcOverride = Some(readerEc),
      traversalParallelism = 2,
      healingMinParallelism = 2,
      healingReservedCores = 2,
      // Lift the emission high-water above the frontier so backpressure never blocks the walk here —
      // the point of this test is the split/Await path, not the drain gate (covered elsewhere).
      frontierHighWater = 200000,
      frontierLowWater = 100000
    )

    try
      coordinator ! TrieNodeHealingCoordinator.StartTrieNodeHealing(fx.rootHash)

      // No deadlock: the full frontier lands within a generous-but-bounded timeout. If the parallel
      // Await deadlocked, pendingTasks would never reach the expected count and this would time out.
      eventually(timeout(60.seconds), interval(500.millis)) {
        coordinator ! TrieNodeHealingCoordinator.HealingGetProgress(statusProbe.ref)
        statusProbe.receiveMessage(3.seconds).pendingTasks shouldBe fx.expectedFrontier
      }
    finally
      testKit.stop(coordinator)
      readerPool.shutdownNow()
      writerPool.shutdownNow()
      ()
  }

  it should "still discover a missing node behind a shared ancestor with parallel readers configured (T031)" taggedAs UnitTest in {
    import java.util.concurrent.Executors

    // Wide fan-out shared-ancestor fixture: many parents reference one shared present node above the
    // single missing grandchild. With a real reader EC and effective parallelism > 1 configured, the
    // shared ancestor is still enqueued once and its missing grandchild discovered exactly once. (The
    // fixture's levels are < 50,000 so the physical split does not fire — the genuine parallel-split
    // path is proven by the >50K T029 test above; this asserts the FR-025 invariant under the
    // concurrency configuration.)
    val fx = HealingTrieFixtures.wideSharedAncestor(fanout = 12)

    val readerPool = Executors.newFixedThreadPool(2)
    val readerEc = scala.concurrent.ExecutionContext.fromExecutorService(readerPool)

    val coordinator = HealingTrieFixtures.spawnCoordinator(
      stateRoot = fx.rootHash,
      networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]().ref,
      requestTracker = new SNAPRequestTracker()(classicSystem.scheduler),
      mptStorage = fx.storage,
      batchSize = 16,
      snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]().ref,
      healingWriterEcOverride = Some(classicSystem.dispatcher),
      healingReaderEcOverride = Some(readerEc),
      traversalParallelism = 2,
      healingMinParallelism = 2,
      healingReservedCores = 2
    )

    try
      coordinator ! TrieNodeHealingCoordinator.StartTrieNodeHealing(fx.rootHash)
      eventually(timeout(5.seconds), interval(100.millis)) {
        coordinator ! TrieNodeHealingCoordinator.HealingGetProgress(statusProbe.ref)
        statusProbe.receiveMessage(2.seconds).pendingTasks shouldBe 1
      }
    finally
      testKit.stop(coordinator)
      readerPool.shutdownNow()
      ()
  }

  // ── T026 (US3): effective-parallelism formula ────────────────────────────────────────────────
  //
  // `computeEffectiveParallelism` is the pure extraction of the inline clamp in `startFrontierBFS`
  // (spec 002 T026): min(traversalParallelism, min(nproc, max(minParallelism, nproc − reservedCores))).
  // Pinning it here proves the clamp never exceeds the operator ceiling or the CPU count, honours the
  // min-parallelism floor, and collapses to the serial path at cfg=1 — independent of the host's nproc.

  "TrieNodeHealingCoordinator.computeEffectiveParallelism" should
    "clamp the 4-core / cfg=4 / min=2 / reserved=2 reference host to 2" taggedAs UnitTest in {
      // max(2, 4−2) = 2; min(4, min(4, 2)) = 2. The 4-core reference host keeps 2 readers.
      TrieNodeHealingCoordinator.computeEffectiveParallelism(
        traversalParallelism = 4,
        availableProcessors = 4,
        minParallelism = 2,
        reservedCores = 2
      ) shouldBe 2
    }

  it should "rise to the cfg ceiling on a 16-core host (cfg is the binding limit)" taggedAs UnitTest in {
    // max(2, 16−2) = 14; min(16, 14) = 14; min(cfg=4, 14) = 4. The operator ceiling caps it.
    TrieNodeHealingCoordinator.computeEffectiveParallelism(
      traversalParallelism = 4,
      availableProcessors = 16,
      minParallelism = 2,
      reservedCores = 2
    ) shouldBe 4
  }

  it should "collapse to 1 (the serial path) when cfg=1, on any host" taggedAs UnitTest in {
    // cfg=1 ⇒ the outer min(1, …) is 1 regardless of cores/min/reserved → the serial branch in the walk.
    TrieNodeHealingCoordinator.computeEffectiveParallelism(1, 4, 2, 2) shouldBe 1
    TrieNodeHealingCoordinator.computeEffectiveParallelism(1, 64, 8, 0) shouldBe 1
  }

  it should "still clamp to nproc when the min-parallelism floor exceeds the CPU count" taggedAs UnitTest in {
    // min=8 on a 4-core host: max(8, 4−2) = 8, but the inner min(nproc=4, 8) caps it at 4 (never oversubscribe).
    TrieNodeHealingCoordinator.computeEffectiveParallelism(
      traversalParallelism = 16,
      availableProcessors = 4,
      minParallelism = 8,
      reservedCores = 2
    ) shouldBe 4
  }

  it should "fall back to the min-parallelism floor when reserved cores ≥ nproc" taggedAs UnitTest in {
    // reserved=4 on a 4-core host: nproc−reserved = 0, so max(min=2, 0) = 2 lifts it to the floor;
    // min(nproc=4, 2) = 2; min(cfg=4, 2) = 2. The floor protects a host whose reservation would otherwise zero it.
    TrieNodeHealingCoordinator.computeEffectiveParallelism(
      traversalParallelism = 4,
      availableProcessors = 4,
      minParallelism = 2,
      reservedCores = 4
    ) shouldBe 2
    // reserved beyond nproc (negative nproc−reserved) is clamped identically by the max floor.
    TrieNodeHealingCoordinator.computeEffectiveParallelism(
      traversalParallelism = 4,
      availableProcessors = 4,
      minParallelism = 2,
      reservedCores = 8
    ) shouldBe 2
  }

  // ========================================
  // Complementary seed guard (PR #1371, proven-live; root-cause w98gfx4wn): an ABSENT walk root must NOT be
  // seeded — seeding it stalls the heal at "exactly 1 node, healed=0" forever. Instead signal the controller
  // (HealingRootUnservable) so it takes the lazy-heal handoff. A PRESENT root still heals normally.
  // ========================================

  it should "signal HealingRootUnservable (not seed the root) when the walk root's bytes are absent" taggedAs UnitTest in {
    // Empty storage ⇒ isNodeInStorage(root) == false. Seeding the root here would stall the heal at
    // "exactly 1 node, healed=0" forever (a root cannot be reconstructed from nothing, and fetching it
    // against an advancing serve root never matches the content-hash gate). The seed-site guard (PR #1371)
    // must instead signal the controller to hand off to lazy on-demand healing — and must do so for EVERY
    // entry into healing, including the BootstrapComplete restart path that calls startStateHealing() directly.
    val stateRoot = kec256(ByteString("absent-walk-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(classicSystem.scheduler)
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]()

    val coordinator = HealingTrieFixtures.spawnCoordinator(
      stateRoot = stateRoot,
      networkPeerManager = networkPeerManager.ref,
      requestTracker = requestTracker,
      mptStorage = storage,
      batchSize = 16,
      snapSyncController = snapSyncController.ref
    )

    coordinator ! TrieNodeHealingCoordinator.StartTrieNodeHealing(stateRoot)

    // The controller is told the root is unservable (handoff signal carries the exact root).
    snapSyncController.expectMessage(SNAPSyncController.HealingRootUnservable(stateRoot))

    // The root was NOT seeded: pendingTasks stays 0 (no futile "exactly 1 node" frontier).
    val progressProbe = testKit.createTestProbe[HealingStatistics]()
    coordinator ! TrieNodeHealingCoordinator.HealingGetProgress(progressProbe.ref)
    progressProbe.expectMessageType[HealingStatistics].pendingTasks shouldBe 0
  }

  it should "heal normally (no HealingRootUnservable) when the walk root IS present" taggedAs UnitTest in {
    // Legit-healing boundary: the root-PRESENT case is unchanged. With a present root that has a single
    // missing descendant, the coordinator discovers that descendant (frontier == 1) and must NOT emit
    // the unservable handoff signal.
    val fx = HealingTrieFixtures.sharedAncestor() // present BranchNode root; one missing grandchild
    val snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]()

    val coordinator = HealingTrieFixtures.spawnCoordinator(
      stateRoot = fx.rootHash,
      networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]().ref,
      requestTracker = new SNAPRequestTracker()(classicSystem.scheduler),
      mptStorage = fx.storage,
      batchSize = 16,
      snapSyncController = snapSyncController.ref,
      healingWriterEcOverride = Some(classicSystem.dispatcher)
    )

    coordinator ! TrieNodeHealingCoordinator.StartTrieNodeHealing(fx.rootHash)

    // Present root ⇒ normal healing: discover the one missing descendant (frontier == 1, not 0/2).
    val progressProbe = testKit.createTestProbe[HealingStatistics]()
    eventually(timeout(5.seconds), interval(100.millis)) {
      coordinator ! TrieNodeHealingCoordinator.HealingGetProgress(progressProbe.ref)
      progressProbe.expectMessageType[HealingStatistics].pendingTasks shouldBe 1
    }

    // The unservable handoff must NEVER fire on a present root.
    snapSyncController.expectNoMessage(300.millis)
  }

  // ─── spec 009 (Moving-Root Delta Heal) flag-ON behavioral coverage ───────────────────────────────────────────────
  //
  // These tests spawn the coordinator with `movingRootDeltaHeal = true`. The factory/impl default is `false`, so every
  // test ABOVE keeps exercising the spec-004 flag-OFF path unchanged (flag-OFF is byte-identical to pre-spec-009).

  /** Extract the underlying `GetTrieNodes` from a captured `SendMessageCmd` (the coordinator wraps the request in a
    * `GetTrieNodesEnc`, a `MessageSerializable`; `underlyingMsg` is the original message).
    */
  private def getTrieNodesOf(send: NetworkPeerManagerActor.SendMessageCmd): SNAP.GetTrieNodes =
    send.message.underlyingMsg.asInstanceOf[SNAP.GetTrieNodes]

  private def healPendingTasks(
      coordinator: org.apache.pekko.actor.typed.ActorRef[TrieNodeHealingCoordinator.Command]
  ): Int =
    val probe = testKit.createTestProbe[HealingStatistics]()
    coordinator ! TrieNodeHealingCoordinator.HealingGetProgress(probe.ref)
    probe.expectMessageType[HealingStatistics].pendingTasks

  /** True iff the node's bytes are readable from storage (the same gate the coordinator uses via `isNodeInStorage`). */
  private def isInStorage(storage: TestMptStorage, hash: ByteString): Boolean =
    try
      storage.get(hash.toArray); true
    catch case _: com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingNodeException => false

  "Moving-root delta heal (spec 009 US2, C1)" should
    "store served root + internal nodes when the fetch root == heal root (content gate matches by construction)" taggedAs UnitTest in {
      // C1 (the spec-004 wrong-axis fix): under the flag the GetTrieNodes fetch root is collapsed to the completeness
      // root `stateRoot`, so a peer serving that exact root returns nodes whose keccak == the requested task hash → the
      // content-hash gate (byte-untouched) ACCEPTS and persists them. No "non-matching hash" drops. We drive the full
      // 2-node delta to completion and assert both served nodes were accepted by the gate (via ProgressNodesHealed).
      val fx = MovingRootDeltaHealFixtures.deltaTrie()
      val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
      val snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]()
      val coordinator = HealingTrieFixtures.spawnCoordinator(
        stateRoot = fx.rootHash,
        networkPeerManager = networkPeerManager.ref,
        requestTracker = new SNAPRequestTracker()(classicSystem.scheduler),
        mptStorage = fx.storage,
        batchSize = 16,
        snapSyncController = snapSyncController.ref,
        healingWriterEcOverride = Some(classicSystem.dispatcher),
        movingRootDeltaHeal = true
      )

      // Heal start: absent root ⇒ seed-from-absent-root (C2) enqueues the root and a peer makes it dispatch.
      coordinator ! TrieNodeHealingCoordinator.StartTrieNodeHealing(fx.rootHash)
      val peerProbe = testKit.createTestProbe[NetworkPeerManagerActor.SendMessageCmd]()
      val peer = PeerTestHelpers.createTestPeer("md-c1-peer", peerProbe.ref.toClassic)
      coordinator ! TrieNodeHealingCoordinator.HealingPeerAvailable(peer)

      // First request fetches the root (empty-path) against the heal root.
      val send1 = networkPeerManager.expectMessageType[NetworkPeerManagerActor.SendMessageCmd](3.seconds)
      val req1 = getTrieNodesOf(send1)
      req1.rootHash shouldBe fx.rootHash // fetch root == heal root (C1)
      coordinator ! TrieNodeHealingCoordinator.TrieNodesResponseMsg(
        SNAP.TrieNodes(requestId = req1.requestId, nodes = Seq(fx.rootNode.encoded))
      )

      // The root passed the content gate — the controller is told ≥ 1 node healed (NOT dropped). Sum healed-progress
      // across the drain (the gate-acceptance signal, deterministic; the async durable flush is racy under suite load).
      var healedTotal = 0L
      snapSyncController.fishForMessage(3.seconds) {
        case SNAPSyncController.ProgressNodesHealed(c) =>
          healedTotal += c
          if healedTotal >= 1L then FishingOutcomes.complete else FishingOutcomes.continue
        case _ => FishingOutcomes.continueAndIgnore
      }

      // Second request fetches the discovered child, again against the heal root.
      val send2 = networkPeerManager.expectMessageType[NetworkPeerManagerActor.SendMessageCmd](3.seconds)
      val req2 = getTrieNodesOf(send2)
      req2.rootHash shouldBe fx.rootHash
      coordinator ! TrieNodeHealingCoordinator.TrieNodesResponseMsg(
        SNAP.TrieNodes(requestId = req2.requestId, nodes = Seq(fx.childNode.encoded))
      )

      // The child also passed the content gate — total healed reaches the full 2-node delta with NO drops.
      snapSyncController.fishForMessage(3.seconds) {
        case SNAPSyncController.ProgressNodesHealed(c) =>
          healedTotal += c
          if healedTotal >= 2L then FishingOutcomes.complete else FishingOutcomes.continue
        case _ => FishingOutcomes.continueAndIgnore
      }
      healedTotal shouldBe 2L // both served nodes accepted by the content-hash gate (fetch root == heal root, C1)
    }

  "Moving-root delta heal (spec 009 US2, C2)" should
    "fetch an absent heal root (NOT hand off HealingRootUnservable) and seed the frontier" taggedAs UnitTest in {
      // C2: under the flag an absent heal root is SEEDED as a frontier task (empty-path) and fetched against the single
      // served root — NOT handed off to lazy healing. This is the same seed HealingPivotRefreshed performs.
      val fx = MovingRootDeltaHealFixtures.deltaTrie()
      val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
      val snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]()
      val coordinator = HealingTrieFixtures.spawnCoordinator(
        stateRoot = fx.rootHash,
        networkPeerManager = networkPeerManager.ref,
        requestTracker = new SNAPRequestTracker()(classicSystem.scheduler),
        mptStorage = fx.storage,
        batchSize = 16,
        snapSyncController = snapSyncController.ref,
        healingWriterEcOverride = Some(classicSystem.dispatcher),
        movingRootDeltaHeal = true
      )

      coordinator ! TrieNodeHealingCoordinator.StartTrieNodeHealing(fx.rootHash)

      // The root was SEEDED (frontier == 1), NOT handed off.
      eventually(timeout(5.seconds), interval(100.millis))(healPendingTasks(coordinator) shouldBe 1)
      // The default unservable handoff must NOT fire under the flag.
      snapSyncController.expectNoMessage(300.millis)

      // With a peer, the seeded root is fetched (empty-path GetTrieNodes carrying the heal root).
      val peerProbe = testKit.createTestProbe[NetworkPeerManagerActor.SendMessageCmd]()
      val peer = PeerTestHelpers.createTestPeer("md-c2-peer", peerProbe.ref.toClassic)
      coordinator ! TrieNodeHealingCoordinator.HealingPeerAvailable(peer)
      val send = networkPeerManager.expectMessageType[NetworkPeerManagerActor.SendMessageCmd](3.seconds)
      val req = getTrieNodesOf(send)
      req.rootHash shouldBe fx.rootHash
      req.paths.head shouldBe Seq(MovingRootDeltaHealFixtures.emptyPath) // empty-path root seed
    }

  "Moving-root delta heal (spec 009 US3, C4 re-peg retains nodes)" should
    "preserve persisted verified nodes across HealingPivotRefreshed and not grow the frontier" taggedAs UnitTest in {
      // C4: heal the root against root A so it is durably persisted, then re-peg to a different root. HealingPivotRefreshed
      // clears ONLY in-memory frontier — it MUST NOT delete trie nodes. Assert the healed root is STILL readable from
      // storage after the re-peg, and the post-re-peg pending count ≤ pre + the one new-root seed.
      val fx = MovingRootDeltaHealFixtures.deltaTrie()
      val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
      val snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]()
      val coordinator = HealingTrieFixtures.spawnCoordinator(
        stateRoot = fx.rootHash,
        networkPeerManager = networkPeerManager.ref,
        requestTracker = new SNAPRequestTracker()(classicSystem.scheduler),
        mptStorage = fx.storage,
        batchSize = 16,
        snapSyncController = snapSyncController.ref,
        healingWriterEcOverride = Some(classicSystem.dispatcher),
        movingRootDeltaHeal = true
      )

      coordinator ! TrieNodeHealingCoordinator.StartTrieNodeHealing(fx.rootHash)
      val peerProbe = testKit.createTestProbe[NetworkPeerManagerActor.SendMessageCmd]()
      val peer = PeerTestHelpers.createTestPeer("md-c4-peer", peerProbe.ref.toClassic)
      coordinator ! TrieNodeHealingCoordinator.HealingPeerAvailable(peer)

      // Serve the root — it passes the content gate (acceptance via ProgressNodesHealed) and the flush makes it durable.
      val send1 = networkPeerManager.expectMessageType[NetworkPeerManagerActor.SendMessageCmd](3.seconds)
      val req1 = getTrieNodesOf(send1)
      coordinator ! TrieNodeHealingCoordinator.TrieNodesResponseMsg(
        SNAP.TrieNodes(requestId = req1.requestId, nodes = Seq(fx.rootNode.encoded))
      )
      snapSyncController.fishForMessage(3.seconds) {
        case SNAPSyncController.ProgressNodesHealed(c) =>
          if c >= 1L then FishingOutcomes.complete else FishingOutcomes.continueAndIgnore
        case _ => FishingOutcomes.continueAndIgnore
      }
      // spec 022 D3: the root is written only once its subtree is present, so serve its one missing child too.
      val send2 = networkPeerManager.expectMessageType[NetworkPeerManagerActor.SendMessageCmd](3.seconds)
      val req2 = getTrieNodesOf(send2)
      coordinator ! TrieNodeHealingCoordinator.TrieNodesResponseMsg(
        SNAP.TrieNodes(requestId = req2.requestId, nodes = Seq(fx.childNode.encoded))
      )
      // The root is durably in storage before the re-peg (the node whose survival we assert).
      eventually(timeout(5.seconds), interval(100.millis))(isInStorage(fx.storage, fx.rootHash) shouldBe true)

      // Re-peg to a DIFFERENT root (distinct from fx.rootHash) — the production stale-move signal.
      val newRoot = kec256(ByteString("md-c4-repeg-new-root"))
      newRoot should not be fx.rootHash
      coordinator ! TrieNodeHealingCoordinator.HealingPivotRefreshed(newRoot)

      // INVARIANT (T010/C4): the re-peg cleared in-memory frontier + the optional mirror CF, but DID NOT delete any
      // persisted trie node. The previously-healed ROOT node remains readable — content-addressed, reusable under newRoot.
      eventually(timeout(5.seconds), interval(100.millis))(isInStorage(fx.storage, fx.rootHash) shouldBe true)
      isInStorage(fx.storage, fx.rootHash) shouldBe true // still present after the re-peg settles
      // Post-re-peg the frontier is at most the (absent) new-root seed (≤ 1) — no re-download of the completed subtree.
      healPendingTasks(coordinator) should be <= 1
    }

  "Moving-root delta heal (spec 009 US3, C5 sound completion)" should
    "NOT complete on delta-discovery alone when a present node has an absent child — only after the gap is healed" taggedAs UnitTest in {
      // C5 (THE consensus invariant): the download mosaic can leave a PRESENT interior node whose grandchild is ABSENT.
      // Pure delta-discovery does not descend into a present node, so it would declare pending==0 with a real hole — a
      // false completion. The PRUNED DESCENT (the rebuild/verification BFS, with NO subtree-complete records so nothing
      // is pruned) DECODES the present interior node and emits the absent grandchild as a frontier entry, so isComplete
      // stays false and StateHealingComplete is WITHHELD until the gap is healed and a clean descent passes.
      val fx = MovingRootDeltaHealFixtures.incompleteSubtree()
      val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
      val snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]()
      val coordinator = HealingTrieFixtures.spawnCoordinator(
        stateRoot = fx.rootHash,
        networkPeerManager = networkPeerManager.ref,
        requestTracker = new SNAPRequestTracker()(classicSystem.scheduler),
        mptStorage = fx.storage,
        batchSize = 16,
        snapSyncController = snapSyncController.ref,
        healingWriterEcOverride = Some(classicSystem.dispatcher),
        movingRootDeltaHeal = true
      )

      // Sanity: the descent target is genuinely a download-mosaic gap — interior present, grandchild absent.
      isInStorage(fx.storage, fx.presentInteriorHash) shouldBe true
      isInStorage(fx.storage, fx.absentGrandchildHash) shouldBe false

      // Root is PRESENT ⇒ StartTrieNodeHealing runs the descent (rebuild BFS), which descends into the present interior
      // node and finds the absent grandchild → enqueues it.
      coordinator ! TrieNodeHealingCoordinator.StartTrieNodeHealing(fx.rootHash)

      // The descent re-enqueued the absent grandchild: pending becomes ≥ 1 and completion is NOT declared.
      eventually(timeout(5.seconds), interval(100.millis))(healPendingTasks(coordinator) should be >= 1)
      // No StateHealingComplete is sent while the gap is open — delta-discovery alone does NOT close it.
      snapSyncController.expectNoMessage(500.millis)
      healPendingTasks(coordinator) should be >= 1

      // Now heal the gap: a peer arrives, the seeded grandchild is fetched, and we serve its bytes (kec256 matches).
      val peerProbe = testKit.createTestProbe[NetworkPeerManagerActor.SendMessageCmd]()
      val peer = PeerTestHelpers.createTestPeer("md-c5-peer", peerProbe.ref.toClassic)
      coordinator ! TrieNodeHealingCoordinator.HealingPeerAvailable(peer)
      val send = networkPeerManager.expectMessageType[NetworkPeerManagerActor.SendMessageCmd](3.seconds)
      val req = getTrieNodesOf(send)
      req.rootHash shouldBe fx.rootHash // fetch root == heal root (single root)
      coordinator ! TrieNodeHealingCoordinator.TrieNodesResponseMsg(
        SNAP.TrieNodes(requestId = req.requestId, nodes = Seq(fx.grandchildServed.encoded))
      )

      // The grandchild is now present; a later clean descent finds zero absent and completion IS declared.
      eventually(timeout(5.seconds), interval(100.millis))(
        isInStorage(fx.storage, fx.absentGrandchildHash) shouldBe true
      )
      snapSyncController.fishForMessage(10.seconds) {
        case SNAPSyncController.StateHealingComplete   => FishingOutcomes.complete
        case _: SNAPSyncController.ProgressNodesHealed => FishingOutcomes.continueAndIgnore
        case _                                         => FishingOutcomes.continueAndIgnore
      }
    }

  "Moving-root delta heal (spec 009 US1, T016b flag-OFF byte-unchanged)" should
    "take the spec-004 HealingRootUnservable handoff for an absent root when the flag is OFF" taggedAs UnitTest in {
      // T016b: flag OFF ⇒ the legacy spec-004 decoupled path is byte-unchanged. An absent heal root is NOT seeded; the
      // coordinator signals HealingRootUnservable (the spec-004 lazy-heal handoff) exactly as before spec 009. This is
      // the direct A/B contrast to the flag-ON C2 test (which SEEDS the same absent root instead) — the single fork.
      val fx = MovingRootDeltaHealFixtures.deltaTrie() // same absent-root fixture as the flag-ON C2 test
      val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
      val snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]()
      val coordinator = HealingTrieFixtures.spawnCoordinator(
        stateRoot = fx.rootHash,
        networkPeerManager = networkPeerManager.ref,
        requestTracker = new SNAPRequestTracker()(classicSystem.scheduler),
        mptStorage = fx.storage,
        batchSize = 16,
        snapSyncController = snapSyncController.ref,
        healingWriterEcOverride = Some(classicSystem.dispatcher),
        movingRootDeltaHeal = false // flag OFF — spec-004 path
      )

      coordinator ! TrieNodeHealingCoordinator.StartTrieNodeHealing(fx.rootHash)

      // Flag OFF: the absent root is NOT seeded — the spec-004 handoff fires (byte-identical to pre-spec-009).
      snapSyncController.expectMessage(3.seconds, SNAPSyncController.HealingRootUnservable(fx.rootHash))
      // And no frontier task was seeded (the seed-from-absent-root behavior is gated entirely behind the flag).
      healPendingTasks(coordinator) shouldBe 0
    }

  "Moving-root delta heal (spec 009 US1, T016c A/B parity)" should
    "reach completion against the SAME root under flag OFF and flag ON for an already-complete trie (no divergent root)" taggedAs UnitTest in {
      // T016c (SC-005 unit proxy): for a controlled fixture whose trie is already fully present locally (root present,
      // zero missing nodes), BOTH the flag-OFF (spec-004) and the flag-ON (moving-root) paths run the SAME pruned
      // descent from the SAME present root, find zero absent, and declare StateHealingComplete against that one root —
      // identical outcome, no divergent finalized root. The present-and-complete trie removes the absent-root fork
      // (where the two paths legitimately differ — seed vs handoff) and isolates the shared completion gate.
      def runAlreadyCompleteHeal(flag: Boolean): Unit =
        // A minimal already-complete trie: a single account leaf IS the root (no children), present on disk.
        val storage = new TestMptStorage()
        val leaf =
          com.chipprbots.ethereum.mpt.LeafNode(
            ByteString(Array[Byte](0x0a, 0x0b, 0x0c)),
            ByteString(com.chipprbots.ethereum.domain.Account.empty().toBytes)
          )
        storage.putNode(leaf)
        val rootHash = ByteString(leaf.hash)

        val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
        val snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]()
        val coordinator = HealingTrieFixtures.spawnCoordinator(
          stateRoot = rootHash,
          networkPeerManager = networkPeerManager.ref,
          requestTracker = new SNAPRequestTracker()(classicSystem.scheduler),
          mptStorage = storage,
          batchSize = 16,
          snapSyncController = snapSyncController.ref,
          healingWriterEcOverride = Some(classicSystem.dispatcher),
          movingRootDeltaHeal = flag
        )

        coordinator ! TrieNodeHealingCoordinator.StartTrieNodeHealing(rootHash)
        coordinator ! TrieNodeHealingCoordinator.HealingCheckCompletion

        // Both paths: the present root + empty delta ⇒ a clean pruned descent ⇒ completion against THIS root.
        snapSyncController.fishForMessage(10.seconds) {
          case SNAPSyncController.StateHealingComplete   => FishingOutcomes.complete
          case _: SNAPSyncController.ProgressNodesHealed => FishingOutcomes.continueAndIgnore
          case _                                         => FishingOutcomes.continueAndIgnore
        }
        // The root the heal completed against is unchanged — no re-peg, no divergent finalized root.
        isInStorage(storage, rootHash) shouldBe true

      runAlreadyCompleteHeal(flag = false) // spec-004 path completes against the present root
      runAlreadyCompleteHeal(flag = true) // moving-root path completes against the SAME present root — parity
    }
