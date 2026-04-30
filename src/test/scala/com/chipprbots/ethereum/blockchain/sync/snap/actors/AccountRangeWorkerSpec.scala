package com.chipprbots.ethereum.blockchain.sync.snap.actors

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.{TestKit, TestProbe, ImplicitSender}
import org.apache.pekko.util.ByteString

import scala.concurrent.duration._

import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.snap._
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.p2p.messages.SNAP.{AccountRange, GetAccountRange}
import com.chipprbots.ethereum.network.p2p.messages.SNAP.GetAccountRange.GetAccountRangeEnc
import com.chipprbots.ethereum.testing.PeerTestHelpers
import com.chipprbots.ethereum.testing.Tags._

class AccountRangeWorkerSpec
    extends TestKit(ActorSystem("AccountRangeWorkerSpec"))
    with ImplicitSender
    with AnyFlatSpecLike
    with Matchers
    with BeforeAndAfterAll {

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  private val zeroHash     = ByteString(new Array[Byte](32))
  private val maxHash      = ByteString(Array.fill(32)(0xff.toByte))
  private val dummyRoot    = ByteString(Array.fill(32)(0xca.toByte))
  private val defaultBytes = BigInt(1024 * 1024)

  private def makeTask(root: ByteString = dummyRoot): AccountTask =
    AccountTask(next = zeroHash, last = maxHash, rootHash = root)

  private def makeWorker(
      coordinator: TestProbe,
      networkPeerManager: TestProbe
  ): org.apache.pekko.actor.ActorRef = {
    val requestTracker = new SNAPRequestTracker()(system.scheduler)
    system.actorOf(
      AccountRangeWorker.props(coordinator.ref, networkPeerManager.ref, requestTracker)
    )
  }

  "AccountRangeWorker" should "send GetAccountRange to peer via NetworkPeerManager on FetchAccountRange" taggedAs UnitTest in {
    val coordinator        = TestProbe()
    val networkPeerManager = TestProbe()
    val peerProbe          = TestProbe()
    val peer               = PeerTestHelpers.createTestPeer("ar-peer-1", peerProbe.ref)
    val worker             = makeWorker(coordinator, networkPeerManager)

    val reqId = BigInt(1)
    worker ! Messages.FetchAccountRange(makeTask(), peer, reqId, defaultBytes)

    val sendMsg = networkPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessage](1.second)
    sendMsg.peerId shouldBe peer.id
    sendMsg.message shouldBe a[GetAccountRangeEnc]
    val msg = sendMsg.message.asInstanceOf[GetAccountRangeEnc].underlyingMsg
    msg.requestId     shouldBe reqId
    msg.rootHash      shouldBe dummyRoot
    msg.responseBytes shouldBe defaultBytes
  }

  it should "report TaskComplete to coordinator on empty-range response (valid proof-of-absence)" taggedAs UnitTest in {
    // An empty AccountRange with an empty proof is a valid proof-of-absence.
    // MerkleProofVerifier.verifyAccountRange short-circuits to Right(()) when both are empty.
    val coordinator        = TestProbe()
    val networkPeerManager = TestProbe()
    val peerProbe          = TestProbe()
    val peer               = PeerTestHelpers.createTestPeer("ar-peer-2", peerProbe.ref)
    val worker             = makeWorker(coordinator, networkPeerManager)

    val reqId = BigInt(2)
    worker ! Messages.FetchAccountRange(makeTask(), peer, reqId, defaultBytes)
    networkPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessage](1.second)

    // Empty accounts + empty proof → valid proof-of-absence
    val emptyResponse = AccountRange(requestId = reqId, accounts = Seq.empty, proof = Seq.empty)
    worker ! Messages.AccountRangeResponseMsg(emptyResponse)

    val msg = coordinator.expectMsgType[Messages.TaskComplete](1.second)
    msg.requestId shouldBe reqId
    msg.result.isRight shouldBe true
    val (count, accounts, proof) = msg.result.toOption.get
    count    shouldBe 0
    accounts shouldBe empty
    proof    shouldBe empty
  }

  it should "report TaskFailed to coordinator on RequestTimeout" taggedAs UnitTest in {
    val coordinator        = TestProbe()
    val networkPeerManager = TestProbe()
    val peerProbe          = TestProbe()
    val peer               = PeerTestHelpers.createTestPeer("ar-peer-3", peerProbe.ref)
    val worker             = makeWorker(coordinator, networkPeerManager)

    val reqId = BigInt(3)
    worker ! Messages.FetchAccountRange(makeTask(), peer, reqId, defaultBytes)
    networkPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessage](1.second)

    worker ! Messages.RequestTimeout(reqId)

    val failed = coordinator.expectMsgType[Messages.TaskFailed](1.second)
    failed.requestId shouldBe reqId
    failed.reason    shouldBe "Request timeout"
  }

  it should "report TaskFailed to coordinator on WorkerPeerDisconnected" taggedAs UnitTest in {
    val coordinator        = TestProbe()
    val networkPeerManager = TestProbe()
    val peerProbe          = TestProbe()
    val peer               = PeerTestHelpers.createTestPeer("ar-peer-4", peerProbe.ref)
    val worker             = makeWorker(coordinator, networkPeerManager)

    val reqId = BigInt(4)
    worker ! Messages.FetchAccountRange(makeTask(), peer, reqId, defaultBytes)
    networkPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessage](1.second)

    worker ! Messages.WorkerPeerDisconnected(peer.id.value)

    val failed = coordinator.expectMsgType[Messages.TaskFailed](1.second)
    failed.requestId shouldBe reqId
    failed.reason    shouldBe "Peer disconnected"
  }

  it should "report TaskFailed(0, Worker busy) when FetchAccountRange arrives while already working" taggedAs UnitTest in {
    val coordinator        = TestProbe()
    val networkPeerManager = TestProbe()
    val peerProbe          = TestProbe()
    val peer               = PeerTestHelpers.createTestPeer("ar-peer-5", peerProbe.ref)
    val worker             = makeWorker(coordinator, networkPeerManager)

    val reqId1 = BigInt(5)
    worker ! Messages.FetchAccountRange(makeTask(), peer, reqId1, defaultBytes)
    networkPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessage](1.second)

    // Send a second task while still working
    val reqId2 = BigInt(6)
    worker ! Messages.FetchAccountRange(makeTask(), peer, reqId2, defaultBytes)

    val failed = coordinator.expectMsgType[Messages.TaskFailed](1.second)
    failed.requestId shouldBe 0
    failed.reason    shouldBe "Worker busy"
  }

  it should "return to idle after timeout and accept a new task" taggedAs UnitTest in {
    val coordinator        = TestProbe()
    val networkPeerManager = TestProbe()
    val peerProbe          = TestProbe()
    val peer               = PeerTestHelpers.createTestPeer("ar-peer-6", peerProbe.ref)
    val worker             = makeWorker(coordinator, networkPeerManager)

    val reqId1 = BigInt(7)
    worker ! Messages.FetchAccountRange(makeTask(), peer, reqId1, defaultBytes)
    networkPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessage](1.second)
    worker ! Messages.RequestTimeout(reqId1)
    coordinator.expectMsgType[Messages.TaskFailed](1.second)

    // Worker should now be in idle — second task accepted
    val reqId2 = BigInt(8)
    worker ! Messages.FetchAccountRange(makeTask(), peer, reqId2, defaultBytes)
    val sendMsg2 = networkPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessage](1.second)
    sendMsg2.message.asInstanceOf[GetAccountRangeEnc].underlyingMsg.requestId shouldBe reqId2
  }

  it should "ignore response with mismatched request ID" taggedAs UnitTest in {
    val coordinator        = TestProbe()
    val networkPeerManager = TestProbe()
    val peerProbe          = TestProbe()
    val peer               = PeerTestHelpers.createTestPeer("ar-peer-7", peerProbe.ref)
    val worker             = makeWorker(coordinator, networkPeerManager)

    val reqId = BigInt(9)
    worker ! Messages.FetchAccountRange(makeTask(), peer, reqId, defaultBytes)
    networkPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessage](1.second)

    // Respond with the wrong request ID
    val wrongResponse = AccountRange(requestId = BigInt(999), accounts = Seq.empty, proof = Seq.empty)
    worker ! Messages.AccountRangeResponseMsg(wrongResponse)

    coordinator.expectNoMessage(200.millis)
  }
}
