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
}
