package com.chipprbots.ethereum.blockchain.sync.snap.actors

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe
import org.apache.pekko.testkit.TestProbe as ClassicTestProbe
import org.apache.pekko.util.ByteString

import scala.concurrent.duration.*

import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.snap.*
import com.chipprbots.ethereum.mpt.LeafNode
import com.chipprbots.ethereum.mpt.MptTraversals
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.p2p.messages.SNAP.AccountRange
import com.chipprbots.ethereum.network.p2p.messages.SNAP.GetAccountRange.GetAccountRangeEnc
import com.chipprbots.ethereum.testing.PeerTestHelpers
import com.chipprbots.ethereum.testing.Tags.*

class AccountRangeWorkerSpec extends ScalaTestWithActorTestKit with AnyFlatSpecLike with Matchers:

  implicit private val classicSystem: org.apache.pekko.actor.ActorSystem = system.classicSystem

  // accountRangeCoordinator is now a typed ref — use typed TestProbe
  private def makeCoordinatorProbe(): TestProbe[AccountRangeCoordinator.Command] =
    testKit.createTestProbe[AccountRangeCoordinator.Command]()

  private val zeroHash = ByteString(new Array[Byte](32))
  private val maxHash = ByteString(Array.fill(32)(0xff.toByte))
  private val dummyRoot = ByteString(Array.fill(32)(0xca.toByte))
  private val defaultBytes = BigInt(1024 * 1024)

  private def makeTask(root: ByteString = dummyRoot): AccountTask =
    AccountTask(next = zeroHash, last = maxHash, rootHash = root)

  private def proofOnlyRange(): (ByteString, Seq[ByteString]) =
    val proofNode = LeafNode(ByteString(0x01.toByte), ByteString("value"))
    ByteString(proofNode.hash) -> Seq(ByteString(MptTraversals.encodeNode(proofNode)))

  private def makeWorker(
      coordinator: TestProbe[AccountRangeCoordinator.Command],
      networkPeerManager: TestProbe[NetworkPeerManagerActor.Command]
  ): org.apache.pekko.actor.typed.ActorRef[AccountRangeWorker.Command] =
    val requestTracker = new SNAPRequestTracker()(using classicSystem.scheduler)
    testKit.spawn(
      AccountRangeWorker(coordinator.ref, networkPeerManager.ref, requestTracker)
    )

  "AccountRangeWorker" should "send GetAccountRange to peer via NetworkPeerManager on FetchAccountRange" taggedAs UnitTest in {
    val coordinator = makeCoordinatorProbe()
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val peerProbe = ClassicTestProbe()
    val peer = PeerTestHelpers.createTestPeer("ar-peer-1", peerProbe.ref)
    val worker = makeWorker(coordinator, networkPeerManager)

    val reqId = BigInt(1)
    worker ! AccountRangeCoordinator.FetchAccountRange(makeTask(), peer, reqId, defaultBytes)

    val sendMsg = networkPeerManager.expectMessageType[NetworkPeerManagerActor.SendMessageCmd](1.second)
    sendMsg.peerId shouldBe peer.id
    sendMsg.message shouldBe a[GetAccountRangeEnc]
    val msg = sendMsg.message.asInstanceOf[GetAccountRangeEnc].underlyingMsg
    msg.requestId shouldBe reqId
    msg.rootHash shouldBe dummyRoot
    msg.responseBytes shouldBe defaultBytes
  }

  it should "report TaskComplete to coordinator on proof-only empty-range response" taggedAs UnitTest in {
    val coordinator = makeCoordinatorProbe()
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val peerProbe = ClassicTestProbe()
    val peer = PeerTestHelpers.createTestPeer("ar-peer-2", peerProbe.ref)
    val worker = makeWorker(coordinator, networkPeerManager)
    val (root, rangeProof) = proofOnlyRange()

    val reqId = BigInt(2)
    worker ! AccountRangeCoordinator.FetchAccountRange(makeTask(root), peer, reqId, defaultBytes)
    networkPeerManager.expectMessageType[NetworkPeerManagerActor.SendMessageCmd](1.second)

    val emptyResponse = AccountRange(requestId = reqId, accounts = Seq.empty, proof = rangeProof)
    worker ! AccountRangeCoordinator.AccountRangeResponseMsg(emptyResponse)

    val msg = coordinator.expectMessageType[AccountRangeCoordinator.TaskComplete](1.second)
    msg.requestId shouldBe reqId
    msg.result.isRight shouldBe true
    val (count, accounts, returnedProof) = msg.result.toOption.get
    count shouldBe 0
    accounts shouldBe empty
    returnedProof should not be empty
  }

  it should "report TaskComplete on terminal empty account range for non-empty root" taggedAs UnitTest in {
    // go-ethereum accepts accounts=0 + proof=0 unconditionally as a valid terminal-empty range.
    // The worker should complete the task rather than failing it.
    val coordinator = makeCoordinatorProbe()
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val peerProbe = ClassicTestProbe()
    val peer = PeerTestHelpers.createTestPeer("ar-peer-2b", peerProbe.ref)
    val worker = makeWorker(coordinator, networkPeerManager)

    val reqId = BigInt(20)
    worker ! AccountRangeCoordinator.FetchAccountRange(makeTask(), peer, reqId, defaultBytes)
    networkPeerManager.expectMessageType[NetworkPeerManagerActor.SendMessageCmd](1.second)

    worker ! AccountRangeCoordinator.AccountRangeResponseMsg(
      AccountRange(requestId = reqId, accounts = Seq.empty, proof = Seq.empty)
    )

    val msg = coordinator.expectMessageType[AccountRangeCoordinator.TaskComplete](1.second)
    msg.requestId shouldBe reqId
  }

  it should "report TaskFailed to coordinator on RequestTimeout" taggedAs UnitTest in {
    val coordinator = makeCoordinatorProbe()
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val peerProbe = ClassicTestProbe()
    val peer = PeerTestHelpers.createTestPeer("ar-peer-3", peerProbe.ref)
    val worker = makeWorker(coordinator, networkPeerManager)

    val reqId = BigInt(3)
    worker ! AccountRangeCoordinator.FetchAccountRange(makeTask(), peer, reqId, defaultBytes)
    networkPeerManager.expectMessageType[NetworkPeerManagerActor.SendMessageCmd](1.second)

    worker ! AccountRangeCoordinator.RequestTimeout(reqId)

    val failed = coordinator.expectMessageType[AccountRangeCoordinator.TaskFailed](1.second)
    failed.requestId shouldBe reqId
    failed.reason shouldBe "Request timeout"
  }

  it should "report TaskFailed to coordinator on WorkerPeerDisconnected" taggedAs UnitTest in {
    val coordinator = makeCoordinatorProbe()
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val peerProbe = ClassicTestProbe()
    val peer = PeerTestHelpers.createTestPeer("ar-peer-4", peerProbe.ref)
    val worker = makeWorker(coordinator, networkPeerManager)

    val reqId = BigInt(4)
    worker ! AccountRangeCoordinator.FetchAccountRange(makeTask(), peer, reqId, defaultBytes)
    networkPeerManager.expectMessageType[NetworkPeerManagerActor.SendMessageCmd](1.second)

    worker ! AccountRangeCoordinator.WorkerPeerDisconnected(peer.id.value)

    val failed = coordinator.expectMessageType[AccountRangeCoordinator.TaskFailed](1.second)
    failed.requestId shouldBe reqId
    failed.reason shouldBe "Peer disconnected"
  }

  it should "report TaskFailed(0, Worker busy) when FetchAccountRange arrives while already working" taggedAs UnitTest in {
    val coordinator = makeCoordinatorProbe()
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val peerProbe = ClassicTestProbe()
    val peer = PeerTestHelpers.createTestPeer("ar-peer-5", peerProbe.ref)
    val worker = makeWorker(coordinator, networkPeerManager)

    val reqId1 = BigInt(5)
    worker ! AccountRangeCoordinator.FetchAccountRange(makeTask(), peer, reqId1, defaultBytes)
    networkPeerManager.expectMessageType[NetworkPeerManagerActor.SendMessageCmd](1.second)

    // Send a second task while still working
    val reqId2 = BigInt(6)
    worker ! AccountRangeCoordinator.FetchAccountRange(makeTask(), peer, reqId2, defaultBytes)

    val failed = coordinator.expectMessageType[AccountRangeCoordinator.TaskFailed](1.second)
    failed.requestId shouldBe 0
    failed.reason shouldBe "Worker busy"
  }

  it should "return to idle after timeout and accept a new task" taggedAs UnitTest in {
    val coordinator = makeCoordinatorProbe()
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val peerProbe = ClassicTestProbe()
    val peer = PeerTestHelpers.createTestPeer("ar-peer-6", peerProbe.ref)
    val worker = makeWorker(coordinator, networkPeerManager)

    val reqId1 = BigInt(7)
    worker ! AccountRangeCoordinator.FetchAccountRange(makeTask(), peer, reqId1, defaultBytes)
    networkPeerManager.expectMessageType[NetworkPeerManagerActor.SendMessageCmd](1.second)
    worker ! AccountRangeCoordinator.RequestTimeout(reqId1)
    coordinator.expectMessageType[AccountRangeCoordinator.TaskFailed](1.second)

    // Worker should now be in idle — second task accepted
    val reqId2 = BigInt(8)
    worker ! AccountRangeCoordinator.FetchAccountRange(makeTask(), peer, reqId2, defaultBytes)
    val sendMsg2 = networkPeerManager.expectMessageType[NetworkPeerManagerActor.SendMessageCmd](1.second)
    sendMsg2.message.asInstanceOf[GetAccountRangeEnc].underlyingMsg.requestId shouldBe reqId2
  }

  it should "ignore response with mismatched request ID" taggedAs UnitTest in {
    val coordinator = makeCoordinatorProbe()
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val peerProbe = ClassicTestProbe()
    val peer = PeerTestHelpers.createTestPeer("ar-peer-7", peerProbe.ref)
    val worker = makeWorker(coordinator, networkPeerManager)

    val reqId = BigInt(9)
    worker ! AccountRangeCoordinator.FetchAccountRange(makeTask(), peer, reqId, defaultBytes)
    networkPeerManager.expectMessageType[NetworkPeerManagerActor.SendMessageCmd](1.second)

    // Respond with the wrong request ID
    val wrongResponse = AccountRange(requestId = BigInt(999), accounts = Seq.empty, proof = Seq.empty)
    worker ! AccountRangeCoordinator.AccountRangeResponseMsg(wrongResponse)

    coordinator.expectNoMessage(200.millis)
  }

  // Category 2d: late response after timeout — no double completion
  // Cross-reference: core-geth eth/downloader/downloader_test.go — responses from dropped peers
  // are silently ignored (dropped atomic flag). AccountRangeWorker achieves the same via idle state.
  it should "silently drop a late response (correct reqId) that arrives after RequestTimeout" taggedAs UnitTest in {
    val coordinator = makeCoordinatorProbe()
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val peerProbe = ClassicTestProbe()
    val peer = PeerTestHelpers.createTestPeer("ar-peer-8", peerProbe.ref)
    val worker = makeWorker(coordinator, networkPeerManager)

    val reqId = BigInt(10)
    worker ! AccountRangeCoordinator.FetchAccountRange(makeTask(), peer, reqId, defaultBytes)
    networkPeerManager.expectMessageType[NetworkPeerManagerActor.SendMessageCmd](1.second)

    // Timeout fires — worker sends TaskFailed and transitions to idle
    worker ! AccountRangeCoordinator.RequestTimeout(reqId)
    coordinator.expectMessage(1.second, AccountRangeCoordinator.TaskFailed(reqId, "Request timeout"))

    // Late response arrives with the correct reqId — worker is now idle (handles only FetchAccountRange)
    // → message is unhandled/dropped; coordinator receives NO second message
    worker ! AccountRangeCoordinator.AccountRangeResponseMsg(
      AccountRange(requestId = reqId, accounts = Seq.empty, proof = Seq.empty)
    )
    coordinator.expectNoMessage(200.millis)
  }
