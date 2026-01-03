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
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.testing.Tags._
import com.chipprbots.ethereum.testing.{PeerTestHelpers, TestEvmCodeStorage}
import com.chipprbots.ethereum.network.p2p.messages.SNAP.ByteCodes
import com.chipprbots.ethereum.network.p2p.messages.SNAP.GetByteCodes.GetByteCodesEnc

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

  it should "accept ByteCodes as a subsequence and re-queue missing hashes" taggedAs UnitTest in {
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

    val code1 = ByteString("code1")
    val code2 = ByteString("code2")
    val code3 = ByteString("code3")
    val h1 = kec256(code1)
    val h2 = kec256(code2)
    val h3 = kec256(code3)

    val contractAccounts = Seq(
      (ByteString("account1"), h1),
      (ByteString("account2"), h2),
      (ByteString("account3"), h3)
    )

    coordinator ! Messages.StartByteCodeSync(contractAccounts)
    coordinator ! Messages.ByteCodePeerAvailable(peer)

    val send1 = networkPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessage](3.seconds)
    val req1 = send1.message.asInstanceOf[GetByteCodesEnc].underlyingMsg
    req1.hashes shouldEqual Seq(h1, h2, h3)

    // Respond with a single middle element (gap allowed by snap/1 semantics)
    system.actorSelection(coordinator.path / "*") ! Messages.ByteCodesResponseMsg(ByteCodes(req1.requestId, Seq(code2)))

    // Ensure the returned code got persisted
    within(3.seconds) {
      awaitAssert(evmCodeStorage.get(h2) shouldEqual Some(code2))
    }

    // Drive next dispatch and assert the missing hashes were re-queued
    coordinator ! Messages.ByteCodePeerAvailable(peer)
    val send2 = networkPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessage](3.seconds)
    val req2 = send2.message.asInstanceOf[GetByteCodesEnc].underlyingMsg
    req2.hashes shouldEqual Seq(h1, h3)
  }

  it should "reject out-of-order ByteCodes responses and retry" taggedAs UnitTest in {
    val evmCodeStorage = new TestEvmCodeStorage()
    val requestTracker = new SNAPRequestTracker()(system.scheduler)
    val networkPeerManager = TestProbe()
    val snapSyncController = TestProbe()
    val peerProbe = TestProbe()

    val peer = PeerTestHelpers.createTestPeer("test-peer", peerProbe.ref)

    val cooldownConfig = ByteCodeCoordinator.ByteCodePeerCooldownConfig(
      baseEmpty = 50.millis,
      baseTimeout = 50.millis,
      baseInvalid = 50.millis,
      maxInFlightPerPeer = 2,
      max = 200.millis,
      exponentCap = 3
    )

    val coordinator = system.actorOf(
      ByteCodeCoordinator.props(
        evmCodeStorage = evmCodeStorage,
        networkPeerManager = networkPeerManager.ref,
        requestTracker = requestTracker,
        batchSize = 8,
        snapSyncController = snapSyncController.ref,
        cooldownConfig = cooldownConfig
      )
    )

    val code1 = ByteString("code1")
    val code2 = ByteString("code2")
    val h1 = kec256(code1)
    val h2 = kec256(code2)

    val contractAccounts = Seq(
      (ByteString("account1"), h1),
      (ByteString("account2"), h2)
    )

    coordinator ! Messages.StartByteCodeSync(contractAccounts)
    coordinator ! Messages.ByteCodePeerAvailable(peer)

    val send1 = networkPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessage](3.seconds)
    val req1 = send1.message.asInstanceOf[GetByteCodesEnc].underlyingMsg
    req1.hashes shouldEqual Seq(h1, h2)

    // Respond out-of-order (violates snap/1 ordering requirement)
    system.actorSelection(coordinator.path / "*") ! Messages.ByteCodesResponseMsg(ByteCodes(req1.requestId, Seq(code2, code1)))

    // Ensure nothing was persisted
    within(3.seconds) {
      awaitAssert {
        evmCodeStorage.get(h1) shouldEqual None
        evmCodeStorage.get(h2) shouldEqual None
      }
    }

    // Verify peer is in cooldown by attempting immediate retry
    coordinator ! Messages.ByteCodePeerAvailable(peer)
    networkPeerManager.expectNoMessage(80.millis)

    // Drive retry after cooldown expires
    coordinator ! Messages.ByteCodePeerAvailable(peer)
    val send2 = networkPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessage](3.seconds)
    val req2 = send2.message.asInstanceOf[GetByteCodesEnc].underlyingMsg
    req2.hashes shouldEqual Seq(h1, h2)
  }

  it should "reject duplicate bytecodes in a ByteCodes response" taggedAs UnitTest in {
    val evmCodeStorage = new TestEvmCodeStorage()
    val requestTracker = new SNAPRequestTracker()(system.scheduler)
    val networkPeerManager = TestProbe()
    val snapSyncController = TestProbe()
    val peerProbe = TestProbe()

    val peer = PeerTestHelpers.createTestPeer("test-peer", peerProbe.ref)

    val cooldownConfig = ByteCodeCoordinator.ByteCodePeerCooldownConfig(
      baseEmpty = 50.millis,
      baseTimeout = 50.millis,
      baseInvalid = 50.millis,
      maxInFlightPerPeer = 2,
      max = 200.millis,
      exponentCap = 3
    )

    val coordinator = system.actorOf(
      ByteCodeCoordinator.props(
        evmCodeStorage = evmCodeStorage,
        networkPeerManager = networkPeerManager.ref,
        requestTracker = requestTracker,
        batchSize = 8,
        snapSyncController = snapSyncController.ref,
        cooldownConfig = cooldownConfig
      )
    )

    val code1 = ByteString("code1")
    val h1 = kec256(code1)

    val contractAccounts = Seq(
      (ByteString("account1"), h1)
    )

    coordinator ! Messages.StartByteCodeSync(contractAccounts)
    coordinator ! Messages.ByteCodePeerAvailable(peer)

    val send1 = networkPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessage](3.seconds)
    val req1 = send1.message.asInstanceOf[GetByteCodesEnc].underlyingMsg
    req1.hashes shouldEqual Seq(h1)

    // Duplicate code for the same hash should be rejected
    system.actorSelection(coordinator.path / "*") ! Messages.ByteCodesResponseMsg(ByteCodes(req1.requestId, Seq(code1, code1)))

    within(3.seconds) {
      awaitAssert(evmCodeStorage.get(h1) shouldEqual None)
    }

    // Verify peer is in cooldown by attempting immediate retry
    coordinator ! Messages.ByteCodePeerAvailable(peer)
    networkPeerManager.expectNoMessage(80.millis)

    // Drive retry after cooldown expires (task should be re-queued)
    coordinator ! Messages.ByteCodePeerAvailable(peer)
    val send2 = networkPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessage](3.seconds)
    val req2 = send2.message.asInstanceOf[GetByteCodesEnc].underlyingMsg
    req2.hashes shouldEqual Seq(h1)
  }

  it should "cool down peers after empty ByteCodes responses" taggedAs UnitTest in {
    val evmCodeStorage = new TestEvmCodeStorage()
    val requestTracker = new SNAPRequestTracker()(system.scheduler)
    val networkPeerManager = TestProbe()
    val snapSyncController = TestProbe()
    val peerProbe = TestProbe()

    val peer = PeerTestHelpers.createTestPeer("test-peer", peerProbe.ref)

    val cooldownConfig = ByteCodeCoordinator.ByteCodePeerCooldownConfig(
      baseEmpty = 50.millis,
      baseTimeout = 50.millis,
      baseInvalid = 50.millis,
      maxInFlightPerPeer = 2,
      max = 200.millis,
      exponentCap = 3
    )

    val coordinator = system.actorOf(
      ByteCodeCoordinator.props(
        evmCodeStorage = evmCodeStorage,
        networkPeerManager = networkPeerManager.ref,
        requestTracker = requestTracker,
        batchSize = 8,
        snapSyncController = snapSyncController.ref,
        cooldownConfig = cooldownConfig
      )
    )

    val code1 = ByteString("code1")
    val h1 = kec256(code1)
    val contractAccounts = Seq((ByteString("account1"), h1))

    coordinator ! Messages.StartByteCodeSync(contractAccounts)
    coordinator ! Messages.ByteCodePeerAvailable(peer)

    val send1 = networkPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessage](3.seconds)
    val req1 = send1.message.asInstanceOf[GetByteCodesEnc].underlyingMsg
    req1.hashes shouldEqual Seq(h1)

    // Respond with empty ByteCodes (peer had none of the requested hashes)
    system.actorSelection(coordinator.path / "*") ! Messages.ByteCodesResponseMsg(ByteCodes(req1.requestId, Seq.empty))

    // Immediately advertising the same peer should not trigger a re-request due to cooldown
    coordinator ! Messages.ByteCodePeerAvailable(peer)
    networkPeerManager.expectNoMessage(80.millis)

    // After cooldown elapses, the coordinator should be willing to send again
    Thread.sleep(70)
    coordinator ! Messages.ByteCodePeerAvailable(peer)
    networkPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessage](3.seconds)
  }
}
