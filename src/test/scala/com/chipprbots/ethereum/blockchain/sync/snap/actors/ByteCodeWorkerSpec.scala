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
import com.chipprbots.ethereum.network.p2p.messages.SNAP.{ByteCodes, GetByteCodes}
import com.chipprbots.ethereum.network.p2p.messages.SNAP.GetByteCodes.GetByteCodesEnc
import com.chipprbots.ethereum.testing.PeerTestHelpers
import com.chipprbots.ethereum.testing.Tags._

class ByteCodeWorkerSpec
    extends TestKit(ActorSystem("ByteCodeWorkerSpec"))
    with ImplicitSender
    with AnyFlatSpecLike
    with Matchers
    with BeforeAndAfterAll {

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  private val codeHash1 = kec256(ByteString("contract1"))
  private val codeHash2 = kec256(ByteString("contract2"))

  private def makeTask(hashes: Seq[ByteString] = Seq(codeHash1)): ByteCodeTask =
    ByteCodeTask.createTask(hashes)

  private def makeWorker(coordinator: TestProbe, networkPeerManager: TestProbe): org.apache.pekko.actor.ActorRef = {
    val requestTracker = new SNAPRequestTracker()(system.scheduler)
    system.actorOf(
      ByteCodeWorker.props(coordinator.ref, networkPeerManager.ref, requestTracker)
    )
  }

  "ByteCodeWorker" should "send GetByteCodes to peer via NetworkPeerManager on ByteCodeWorkerFetchTask" taggedAs UnitTest in {
    val coordinator = TestProbe()
    val networkPeerManager = TestProbe()
    val peerProbe = TestProbe()
    val peer = PeerTestHelpers.createTestPeer("bc-peer-1", peerProbe.ref)
    val worker = makeWorker(coordinator, networkPeerManager)

    val task = makeTask()
    val reqId = BigInt(1)
    worker ! Messages.ByteCodeWorkerFetchTask(task, peer, reqId, BigInt(1024 * 1024))

    // Worker must have sent GetByteCodes to the network peer manager
    val sendMsg = networkPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessage](1.second)
    sendMsg.peerId shouldBe peer.id
    sendMsg.message shouldBe a[GetByteCodesEnc]
    val encoded = sendMsg.message.asInstanceOf[GetByteCodesEnc]
    encoded.underlyingMsg.requestId shouldBe reqId
    encoded.underlyingMsg.hashes shouldBe task.codeHashes
  }

  it should "forward ByteCodesResponseMsg to coordinator on happy-path response" taggedAs UnitTest in {
    val coordinator = TestProbe()
    val networkPeerManager = TestProbe()
    val peerProbe = TestProbe()
    val peer = PeerTestHelpers.createTestPeer("bc-peer-2", peerProbe.ref)
    val worker = makeWorker(coordinator, networkPeerManager)

    val reqId = BigInt(2)
    worker ! Messages.ByteCodeWorkerFetchTask(makeTask(), peer, reqId, BigInt(1024 * 1024))
    networkPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessage](1.second)

    val code = ByteString("contract bytecode here")
    val response = ByteCodes(requestId = reqId, codes = Seq(code))
    worker ! Messages.ByteCodesResponseMsg(response)

    coordinator.expectMsg(1.second, Messages.ByteCodesResponseMsg(response))
  }

  it should "return to idle after response and process a stashed ByteCodeWorkerFetchTask" taggedAs UnitTest in {
    val coordinator = TestProbe()
    val networkPeerManager = TestProbe()
    val peerProbe = TestProbe()
    val peer = PeerTestHelpers.createTestPeer("bc-peer-3", peerProbe.ref)
    val worker = makeWorker(coordinator, networkPeerManager)

    // First task
    val reqId1 = BigInt(3)
    worker ! Messages.ByteCodeWorkerFetchTask(makeTask(Seq(codeHash1)), peer, reqId1, BigInt(1024 * 1024))
    networkPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessage](1.second)

    // Second task — stashed while working
    val reqId2 = BigInt(4)
    worker ! Messages.ByteCodeWorkerFetchTask(makeTask(Seq(codeHash2)), peer, reqId2, BigInt(1024 * 1024))

    // Respond to first task — worker unstashes and processes the second
    val resp1 = ByteCodes(requestId = reqId1, codes = Seq(ByteString("code1")))
    worker ! Messages.ByteCodesResponseMsg(resp1)

    coordinator.expectMsg(1.second, Messages.ByteCodesResponseMsg(resp1))
    // Unstash triggers second task → GetByteCodes sent for reqId2
    val sendMsg2 = networkPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessage](1.second)
    sendMsg2.message.asInstanceOf[GetByteCodesEnc].underlyingMsg.requestId shouldBe reqId2
  }

  it should "report ByteCodeTaskFailed to coordinator on ByteCodeRequestTimeout" taggedAs UnitTest in {
    val coordinator = TestProbe()
    val networkPeerManager = TestProbe()
    val peerProbe = TestProbe()
    val peer = PeerTestHelpers.createTestPeer("bc-peer-4", peerProbe.ref)
    val worker = makeWorker(coordinator, networkPeerManager)

    val reqId = BigInt(5)
    worker ! Messages.ByteCodeWorkerFetchTask(makeTask(), peer, reqId, BigInt(1024 * 1024))
    networkPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessage](1.second)

    worker ! Messages.ByteCodeRequestTimeout(reqId)

    coordinator.expectMsg(1.second, Messages.ByteCodeTaskFailed(reqId, "Timeout"))
  }

  it should "return to idle after timeout and accept a new ByteCodeWorkerFetchTask" taggedAs UnitTest in {
    val coordinator = TestProbe()
    val networkPeerManager = TestProbe()
    val peerProbe = TestProbe()
    val peer = PeerTestHelpers.createTestPeer("bc-peer-5", peerProbe.ref)
    val worker = makeWorker(coordinator, networkPeerManager)

    val reqId1 = BigInt(6)
    worker ! Messages.ByteCodeWorkerFetchTask(makeTask(), peer, reqId1, BigInt(1024 * 1024))
    networkPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessage](1.second)

    worker ! Messages.ByteCodeRequestTimeout(reqId1)
    coordinator.expectMsgType[Messages.ByteCodeTaskFailed](1.second)

    // Worker should now be idle — second task accepted
    val reqId2 = BigInt(7)
    worker ! Messages.ByteCodeWorkerFetchTask(makeTask(), peer, reqId2, BigInt(1024 * 1024))
    val sendMsg = networkPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessage](1.second)
    sendMsg.message.asInstanceOf[GetByteCodesEnc].underlyingMsg.requestId shouldBe reqId2
  }

  it should "return to idle on ByteCodeWorkerRelease and unstash queued tasks" taggedAs UnitTest in {
    val coordinator = TestProbe()
    val networkPeerManager = TestProbe()
    val peerProbe = TestProbe()
    val peer = PeerTestHelpers.createTestPeer("bc-peer-6", peerProbe.ref)
    val worker = makeWorker(coordinator, networkPeerManager)

    val reqId1 = BigInt(8)
    worker ! Messages.ByteCodeWorkerFetchTask(makeTask(Seq(codeHash1)), peer, reqId1, BigInt(1024 * 1024))
    networkPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessage](1.second)

    // Stash a second task
    val reqId2 = BigInt(9)
    worker ! Messages.ByteCodeWorkerFetchTask(makeTask(Seq(codeHash2)), peer, reqId2, BigInt(1024 * 1024))

    // Coordinator sends explicit Release
    worker ! Messages.ByteCodeWorkerRelease(reqId1)

    // Unstash triggers second task → GetByteCodes for reqId2
    val sendMsg2 = networkPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessage](1.second)
    sendMsg2.message.asInstanceOf[GetByteCodesEnc].underlyingMsg.requestId shouldBe reqId2
  }

  it should "ignore ByteCodesResponseMsg for mismatched request ID" taggedAs UnitTest in {
    val coordinator = TestProbe()
    val networkPeerManager = TestProbe()
    val peerProbe = TestProbe()
    val peer = PeerTestHelpers.createTestPeer("bc-peer-7", peerProbe.ref)
    val worker = makeWorker(coordinator, networkPeerManager)

    val reqId = BigInt(10)
    worker ! Messages.ByteCodeWorkerFetchTask(makeTask(), peer, reqId, BigInt(1024 * 1024))
    networkPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessage](1.second)

    val wrongReqId = BigInt(999)
    val response = ByteCodes(requestId = wrongReqId, codes = Seq.empty)
    worker ! Messages.ByteCodesResponseMsg(response)

    // Coordinator should NOT receive anything for the mismatched response
    coordinator.expectNoMessage(200.millis)
  }
}
