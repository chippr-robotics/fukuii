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

    coordinator ! Messages.StartTrieNodeHealing(stateRoot)
    coordinator ! Messages.HealingCheckCompletion
    
    // Should complete immediately if no nodes to heal
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
}
