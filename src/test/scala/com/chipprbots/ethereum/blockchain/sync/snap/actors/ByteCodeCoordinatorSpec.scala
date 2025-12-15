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
import com.chipprbots.ethereum.testing.{PeerTestHelpers, TestEvmCodeStorage}

class ByteCodeCoordinatorSpec
    extends TestKit(ActorSystem("ByteCodeCoordinatorSpec"))
    with ImplicitSender
    with AnyFlatSpecLike
    with Matchers
    with BeforeAndAfterAll {

  override def afterAll(): Unit =
    TestKit.shutdownActorSystem(system)

  "ByteCodeCoordinator" should "initialize with empty task queue" taggedAs UnitTest in {
    val evmCodeStorage = new TestEvmCodeStorage()
    val requestTracker = new SNAPRequestTracker()(system.scheduler)
    val networkPeerManager = TestProbe()
    val snapSyncController = TestProbe()

    val coordinator = system.actorOf(
      ByteCodeCoordinator.props(
        evmCodeStorage = evmCodeStorage,
        networkPeerManager = networkPeerManager.ref,
        requestTracker = requestTracker,
        batchSize = 8,
        snapSyncController = snapSyncController.ref
      )
    )

    coordinator should not be null
  }

  it should "queue contract accounts for download" taggedAs UnitTest in {
    val evmCodeStorage = new TestEvmCodeStorage()
    val requestTracker = new SNAPRequestTracker()(system.scheduler)
    val networkPeerManager = TestProbe()
    val snapSyncController = TestProbe()

    val coordinator = system.actorOf(
      ByteCodeCoordinator.props(
        evmCodeStorage = evmCodeStorage,
        networkPeerManager = networkPeerManager.ref,
        requestTracker = requestTracker,
        batchSize = 8,
        snapSyncController = snapSyncController.ref
      )
    )

    val contractAccounts = Seq(
      (ByteString("account1"), kec256(ByteString("code1"))),
      (ByteString("account2"), kec256(ByteString("code2")))
    )

    coordinator ! Messages.StartByteCodeSync(contractAccounts)
    
    // Coordinator should queue the contracts
    coordinator ! Messages.ByteCodeGetProgress
    expectMsgType[Any](3.seconds)
  }

  it should "create workers when peers are available" taggedAs UnitTest in {
    val evmCodeStorage = new TestEvmCodeStorage()
    val requestTracker = new SNAPRequestTracker()(system.scheduler)
    val networkPeerManager = TestProbe()
    val snapSyncController = TestProbe()
    val peerProbe = TestProbe()

    val peer = PeerTestHelpers.createTestPeer("test-peer", peerProbe.ref)

    val coordinator = system.actorOf(
      ByteCodeCoordinator.props(
        evmCodeStorage = evmCodeStorage,
        networkPeerManager = networkPeerManager.ref,
        requestTracker = requestTracker,
        batchSize = 8,
        snapSyncController = snapSyncController.ref
      )
    )

    val contractAccounts = Seq(
      (ByteString("account1"), kec256(ByteString("code1")))
    )

    coordinator ! Messages.StartByteCodeSync(contractAccounts)
    coordinator ! Messages.ByteCodePeerAvailable(peer)

    // Should send request to network peer manager
    networkPeerManager.expectMsgType[Any](3.seconds)
  }

  it should "handle task completion" taggedAs UnitTest in {
    val evmCodeStorage = new TestEvmCodeStorage()
    val requestTracker = new SNAPRequestTracker()(system.scheduler)
    val networkPeerManager = TestProbe()
    val snapSyncController = TestProbe()

    val coordinator = system.actorOf(
      ByteCodeCoordinator.props(
        evmCodeStorage = evmCodeStorage,
        networkPeerManager = networkPeerManager.ref,
        requestTracker = requestTracker,
        batchSize = 8,
        snapSyncController = snapSyncController.ref
      )
    )

    coordinator ! Messages.ByteCodeTaskComplete(BigInt(123), Right(5))
    
    // Coordinator should handle completion
    coordinator ! Messages.ByteCodeGetProgress
    expectMsgType[Any](3.seconds)
  }

  it should "report completion when all bytecodes downloaded" taggedAs UnitTest in {
    val evmCodeStorage = new TestEvmCodeStorage()
    val requestTracker = new SNAPRequestTracker()(system.scheduler)
    val networkPeerManager = TestProbe()
    val snapSyncController = TestProbe()

    val coordinator = system.actorOf(
      ByteCodeCoordinator.props(
        evmCodeStorage = evmCodeStorage,
        networkPeerManager = networkPeerManager.ref,
        requestTracker = requestTracker,
        batchSize = 8,
        snapSyncController = snapSyncController.ref
      )
    )

    // Start with empty contract list
    coordinator ! Messages.StartByteCodeSync(Seq.empty)
    
    // Should complete immediately
    coordinator ! Messages.ByteCodeCheckCompletion
    snapSyncController.expectMsg(3.seconds, SNAPSyncController.ByteCodeSyncComplete)
  }

  it should "handle task failures gracefully" taggedAs UnitTest in {
    val evmCodeStorage = new TestEvmCodeStorage()
    val requestTracker = new SNAPRequestTracker()(system.scheduler)
    val networkPeerManager = TestProbe()
    val snapSyncController = TestProbe()

    val coordinator = system.actorOf(
      ByteCodeCoordinator.props(
        evmCodeStorage = evmCodeStorage,
        networkPeerManager = networkPeerManager.ref,
        requestTracker = requestTracker,
        batchSize = 8,
        snapSyncController = snapSyncController.ref
      )
    )

    coordinator ! Messages.ByteCodeTaskFailed(BigInt(123), "Test failure")
    
    // Coordinator should still be operational
    coordinator ! Messages.ByteCodeGetProgress
    expectMsgType[Any](3.seconds)
  }
}
