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

class StorageRangeCoordinatorSpec
    extends TestKit(ActorSystem("StorageRangeCoordinatorSpec"))
    with ImplicitSender
    with AnyFlatSpecLike
    with Matchers
    with BeforeAndAfterAll {

  override def afterAll(): Unit =
    TestKit.shutdownActorSystem(system)

  "StorageRangeCoordinator" should "initialize correctly" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("test-state-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(system.scheduler)
    val networkPeerManager = TestProbe()
    val snapSyncController = TestProbe()

    val coordinator = system.actorOf(
      StorageRangeCoordinator.props(
        stateRoot = stateRoot,
        networkPeerManager = networkPeerManager.ref,
        requestTracker = requestTracker,
        mptStorage = storage,
        maxAccountsPerBatch = 8,
        snapSyncController = snapSyncController.ref
      )
    )

    coordinator should not be null
  }

  it should "handle peer availability" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("test-state-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(system.scheduler)
    val networkPeerManager = TestProbe()
    val snapSyncController = TestProbe()
    val peerProbe = TestProbe()

    val peer = PeerTestHelpers.createTestPeer("test-peer", peerProbe.ref)

    val coordinator = system.actorOf(
      StorageRangeCoordinator.props(
        stateRoot = stateRoot,
        networkPeerManager = networkPeerManager.ref,
        requestTracker = requestTracker,
        mptStorage = storage,
        maxAccountsPerBatch = 8,
        snapSyncController = snapSyncController.ref
      )
    )

    coordinator ! Messages.StartStorageRangeSync(stateRoot)
    coordinator ! Messages.StoragePeerAvailable(peer)
    
    // Should handle peer availability (may or may not send request depending on tasks)
    coordinator ! Messages.StorageGetProgress
    expectMsgType[Any](3.seconds)
  }

  it should "handle task completion" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("test-state-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(system.scheduler)
    val networkPeerManager = TestProbe()
    val snapSyncController = TestProbe()

    val coordinator = system.actorOf(
      StorageRangeCoordinator.props(
        stateRoot = stateRoot,
        networkPeerManager = networkPeerManager.ref,
        requestTracker = requestTracker,
        mptStorage = storage,
        maxAccountsPerBatch = 8,
        snapSyncController = snapSyncController.ref
      )
    )

    coordinator ! Messages.StorageTaskComplete(BigInt(123), Right(10))
    
    // Coordinator should handle completion
    coordinator ! Messages.StorageGetProgress
    expectMsgType[Any](3.seconds)
  }

  it should "report completion when no storage tasks" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("test-state-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(system.scheduler)
    val networkPeerManager = TestProbe()
    val snapSyncController = TestProbe()

    val coordinator = system.actorOf(
      StorageRangeCoordinator.props(
        stateRoot = stateRoot,
        networkPeerManager = networkPeerManager.ref,
        requestTracker = requestTracker,
        mptStorage = storage,
        maxAccountsPerBatch = 8,
        snapSyncController = snapSyncController.ref
      )
    )

    coordinator ! Messages.StartStorageRangeSync(stateRoot)
    coordinator ! Messages.StorageCheckCompletion
    
    // Should complete immediately if no tasks
    snapSyncController.expectMsg(3.seconds, SNAPSyncController.StorageRangeSyncComplete)
  }

  it should "handle task failures" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("test-state-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()(system.scheduler)
    val networkPeerManager = TestProbe()
    val snapSyncController = TestProbe()

    val coordinator = system.actorOf(
      StorageRangeCoordinator.props(
        stateRoot = stateRoot,
        networkPeerManager = networkPeerManager.ref,
        requestTracker = requestTracker,
        mptStorage = storage,
        maxAccountsPerBatch = 8,
        snapSyncController = snapSyncController.ref
      )
    )

    coordinator ! Messages.StorageTaskFailed(BigInt(123), "Test failure")
    
    // Coordinator should still be operational
    coordinator ! Messages.StorageGetProgress
    expectMsgType[Any](3.seconds)
  }
}
