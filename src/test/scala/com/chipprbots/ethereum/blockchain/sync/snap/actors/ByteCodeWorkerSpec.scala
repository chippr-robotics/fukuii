package com.chipprbots.ethereum.blockchain.sync.snap.actors

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe
import org.apache.pekko.testkit.TestProbe as ClassicTestProbe
import org.apache.pekko.util.ByteString

import scala.concurrent.duration.*

import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.snap.*
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.PeerScoringManager
import com.chipprbots.ethereum.network.p2p.messages.SNAP.ByteCodes
import com.chipprbots.ethereum.network.p2p.messages.SNAP.GetByteCodes.GetByteCodesEnc
import com.chipprbots.ethereum.testing.PeerTestHelpers
import com.chipprbots.ethereum.testing.Tags.*

class ByteCodeWorkerSpec extends ScalaTestWithActorTestKit with AnyFlatSpecLike with Matchers:

  implicit private val classicSystem: org.apache.pekko.actor.ActorSystem = system.classicSystem

  private def makeCoordinatorProbe(): TestProbe[ByteCodeCoordinator.Command] =
    testKit.createTestProbe[ByteCodeCoordinator.Command]()

  private val codeHash1 = kec256(ByteString("contract1"))
  private val codeHash2 = kec256(ByteString("contract2"))

  private def makeTask(hashes: Seq[ByteString] = Seq(codeHash1)): ByteCodeTask =
    ByteCodeTask.createTask(hashes)

  private def makeWorker(
      coordinator: TestProbe[ByteCodeCoordinator.Command],
      networkPeerManager: TestProbe[NetworkPeerManagerActor.Command],
      requestTracker: SNAPRequestTracker = new SNAPRequestTracker()(using classicSystem.scheduler)
  ): org.apache.pekko.actor.typed.ActorRef[ByteCodeWorker.Command] =
    testKit.spawn(
      ByteCodeWorker(coordinator.ref, networkPeerManager.ref, requestTracker)
    )

  "ByteCodeWorker" should "send GetByteCodes to peer via NetworkPeerManager on ByteCodeWorkerFetchTask" taggedAs UnitTest in {
    val coordinator = makeCoordinatorProbe()
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val peerProbe = ClassicTestProbe()
    val peer = PeerTestHelpers.createTestPeer("bc-peer-1", peerProbe.ref)
    val worker = makeWorker(coordinator, networkPeerManager)

    val task = makeTask()
    val reqId = BigInt(1)
    worker ! ByteCodeCoordinator.ByteCodeWorkerFetchTask(task, peer, reqId, BigInt(1024 * 1024))

    // Worker must have sent GetByteCodes to the network peer manager
    val sendMsg = networkPeerManager.expectMessageType[NetworkPeerManagerActor.SendMessageCmd](1.second)
    sendMsg.peerId shouldBe peer.id
    sendMsg.message shouldBe a[GetByteCodesEnc]
    val encoded = sendMsg.message.asInstanceOf[GetByteCodesEnc]
    encoded.underlyingMsg.requestId shouldBe reqId
    encoded.underlyingMsg.hashes shouldBe task.codeHashes
  }

  it should "forward ByteCodesResponseMsg to coordinator on happy-path response" taggedAs UnitTest in {
    val coordinator = makeCoordinatorProbe()
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val peerProbe = ClassicTestProbe()
    val peer = PeerTestHelpers.createTestPeer("bc-peer-2", peerProbe.ref)
    val worker = makeWorker(coordinator, networkPeerManager)

    val reqId = BigInt(2)
    worker ! ByteCodeCoordinator.ByteCodeWorkerFetchTask(makeTask(), peer, reqId, BigInt(1024 * 1024))
    networkPeerManager.expectMessageType[NetworkPeerManagerActor.SendMessageCmd](1.second)

    val code = ByteString("contract bytecode here")
    val response = ByteCodes(requestId = reqId, codes = Seq(code))
    worker ! ByteCodeCoordinator.ByteCodesResponseMsg(response)

    coordinator.expectMessage(1.second, ByteCodeCoordinator.ByteCodesResponseMsg(response))
  }

  it should "return to idle after response and process a stashed ByteCodeWorkerFetchTask" taggedAs UnitTest in {
    val coordinator = makeCoordinatorProbe()
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val peerProbe = ClassicTestProbe()
    val peer = PeerTestHelpers.createTestPeer("bc-peer-3", peerProbe.ref)
    val worker = makeWorker(coordinator, networkPeerManager)

    // First task
    val reqId1 = BigInt(3)
    worker ! ByteCodeCoordinator.ByteCodeWorkerFetchTask(makeTask(Seq(codeHash1)), peer, reqId1, BigInt(1024 * 1024))
    networkPeerManager.expectMessageType[NetworkPeerManagerActor.SendMessageCmd](1.second)

    // Second task — stashed while working
    val reqId2 = BigInt(4)
    worker ! ByteCodeCoordinator.ByteCodeWorkerFetchTask(makeTask(Seq(codeHash2)), peer, reqId2, BigInt(1024 * 1024))

    // Respond to first task — worker unstashes and processes the second
    val resp1 = ByteCodes(requestId = reqId1, codes = Seq(ByteString("code1")))
    worker ! ByteCodeCoordinator.ByteCodesResponseMsg(resp1)

    coordinator.expectMessage(1.second, ByteCodeCoordinator.ByteCodesResponseMsg(resp1))
    // Unstash triggers second task → GetByteCodes sent for reqId2
    val sendMsg2 = networkPeerManager.expectMessageType[NetworkPeerManagerActor.SendMessageCmd](1.second)
    sendMsg2.message.asInstanceOf[GetByteCodesEnc].underlyingMsg.requestId shouldBe reqId2
  }

  it should "report ByteCodeTaskFailed to coordinator on ByteCodeRequestTimeout" taggedAs UnitTest in {
    val coordinator = makeCoordinatorProbe()
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val peerProbe = ClassicTestProbe()
    val peer = PeerTestHelpers.createTestPeer("bc-peer-4", peerProbe.ref)
    val worker = makeWorker(coordinator, networkPeerManager)

    val reqId = BigInt(5)
    worker ! ByteCodeCoordinator.ByteCodeWorkerFetchTask(makeTask(), peer, reqId, BigInt(1024 * 1024))
    networkPeerManager.expectMessageType[NetworkPeerManagerActor.SendMessageCmd](1.second)

    worker ! ByteCodeCoordinator.ByteCodeRequestTimeout(reqId)

    coordinator.expectMessage(1.second, ByteCodeCoordinator.ByteCodeTaskFailed(reqId, "Timeout"))
  }

  it should "return to idle after timeout and accept a new ByteCodeWorkerFetchTask" taggedAs UnitTest in {
    val coordinator = makeCoordinatorProbe()
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val peerProbe = ClassicTestProbe()
    val peer = PeerTestHelpers.createTestPeer("bc-peer-5", peerProbe.ref)
    val worker = makeWorker(coordinator, networkPeerManager)

    val reqId1 = BigInt(6)
    worker ! ByteCodeCoordinator.ByteCodeWorkerFetchTask(makeTask(), peer, reqId1, BigInt(1024 * 1024))
    networkPeerManager.expectMessageType[NetworkPeerManagerActor.SendMessageCmd](1.second)

    worker ! ByteCodeCoordinator.ByteCodeRequestTimeout(reqId1)
    coordinator.expectMessageType[ByteCodeCoordinator.ByteCodeTaskFailed](1.second)

    // Worker should now be idle — second task accepted
    val reqId2 = BigInt(7)
    worker ! ByteCodeCoordinator.ByteCodeWorkerFetchTask(makeTask(), peer, reqId2, BigInt(1024 * 1024))
    val sendMsg = networkPeerManager.expectMessageType[NetworkPeerManagerActor.SendMessageCmd](1.second)
    sendMsg.message.asInstanceOf[GetByteCodesEnc].underlyingMsg.requestId shouldBe reqId2
  }

  it should "return to idle on ByteCodeWorkerRelease and unstash queued tasks" taggedAs UnitTest in {
    val coordinator = makeCoordinatorProbe()
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val peerProbe = ClassicTestProbe()
    val peer = PeerTestHelpers.createTestPeer("bc-peer-6", peerProbe.ref)
    val worker = makeWorker(coordinator, networkPeerManager)

    val reqId1 = BigInt(8)
    worker ! ByteCodeCoordinator.ByteCodeWorkerFetchTask(makeTask(Seq(codeHash1)), peer, reqId1, BigInt(1024 * 1024))
    networkPeerManager.expectMessageType[NetworkPeerManagerActor.SendMessageCmd](1.second)

    // Stash a second task
    val reqId2 = BigInt(9)
    worker ! ByteCodeCoordinator.ByteCodeWorkerFetchTask(makeTask(Seq(codeHash2)), peer, reqId2, BigInt(1024 * 1024))

    // Coordinator sends explicit Release
    worker ! ByteCodeCoordinator.ByteCodeWorkerRelease(reqId1)

    // Unstash triggers second task → GetByteCodes for reqId2
    val sendMsg2 = networkPeerManager.expectMessageType[NetworkPeerManagerActor.SendMessageCmd](1.second)
    sendMsg2.message.asInstanceOf[GetByteCodesEnc].underlyingMsg.requestId shouldBe reqId2
  }

  // ---- spec 027 slice 4a: the peer scorer hears of a response, never of an abandoned request ----------------------

  private def scoredTracker(): (SNAPRequestTracker, PeerScoringManager) =
    val scorer = new PeerScoringManager(java.time.Clock.fixed(java.time.Instant.EPOCH, java.time.ZoneOffset.UTC))
    (new SNAPRequestTracker(scorer = Some(scorer))(using classicSystem.scheduler), scorer)

  it should "forget a released request without crediting its peer with a response" taggedAs UnitTest in {
    // ByteCodeWorkerRelease is sent when the peer disconnects: the peer has not answered.
    val coordinator = makeCoordinatorProbe()
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val peer = PeerTestHelpers.createTestPeer("bc-peer-8", ClassicTestProbe().ref)
    val (tracker, scorer) = scoredTracker()
    val worker = makeWorker(coordinator, networkPeerManager, tracker)

    val reqId1 = BigInt(20)
    worker ! ByteCodeCoordinator.ByteCodeWorkerFetchTask(makeTask(Seq(codeHash1)), peer, reqId1, BigInt(1024 * 1024))
    networkPeerManager.expectMessageType[NetworkPeerManagerActor.SendMessageCmd](1.second)
    tracker.isPending(reqId1) shouldBe true

    // A second task waits in the stash: its request going out proves the release was handled.
    val reqId2 = BigInt(21)
    worker ! ByteCodeCoordinator.ByteCodeWorkerFetchTask(makeTask(Seq(codeHash2)), peer, reqId2, BigInt(1024 * 1024))
    worker ! ByteCodeCoordinator.ByteCodeWorkerRelease(reqId1)
    val sendMsg2 = networkPeerManager.expectMessageType[NetworkPeerManagerActor.SendMessageCmd](1.second)
    sendMsg2.message.asInstanceOf[GetByteCodesEnc].underlyingMsg.requestId shouldBe reqId2

    tracker.isPending(reqId1) shouldBe false
    scorer.getAllScores shouldBe empty
  }

  it should "credit the peer with a response when the bytecodes arrive" taggedAs UnitTest in {
    val coordinator = makeCoordinatorProbe()
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val peer = PeerTestHelpers.createTestPeer("bc-peer-9", ClassicTestProbe().ref)
    val (tracker, scorer) = scoredTracker()
    val worker = makeWorker(coordinator, networkPeerManager, tracker)

    val reqId = BigInt(22)
    worker ! ByteCodeCoordinator.ByteCodeWorkerFetchTask(makeTask(), peer, reqId, BigInt(1024 * 1024))
    networkPeerManager.expectMessageType[NetworkPeerManagerActor.SendMessageCmd](1.second)

    val response = ByteCodes(requestId = reqId, codes = Seq(ByteString("contract bytecode here")))
    worker ! ByteCodeCoordinator.ByteCodesResponseMsg(response)
    coordinator.expectMessage(1.second, ByteCodeCoordinator.ByteCodesResponseMsg(response))

    scorer.getScore(peer.id).responsesReceived shouldBe 1
  }

  it should "credit no response when it is told a request timed out that the tracker still holds" taggedAs UnitTest in {
    val coordinator = makeCoordinatorProbe()
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val peer = PeerTestHelpers.createTestPeer("bc-peer-10", ClassicTestProbe().ref)
    val (tracker, scorer) = scoredTracker()
    val worker = makeWorker(coordinator, networkPeerManager, tracker)

    val reqId = BigInt(23)
    worker ! ByteCodeCoordinator.ByteCodeWorkerFetchTask(makeTask(), peer, reqId, BigInt(1024 * 1024))
    networkPeerManager.expectMessageType[NetworkPeerManagerActor.SendMessageCmd](1.second)

    worker ! ByteCodeCoordinator.ByteCodeRequestTimeout(reqId)
    coordinator.expectMessage(1.second, ByteCodeCoordinator.ByteCodeTaskFailed(reqId, "Timeout"))

    tracker.isPending(reqId) shouldBe false
    scorer.getAllScores shouldBe empty
  }

  it should "ignore ByteCodesResponseMsg for mismatched request ID" taggedAs UnitTest in {
    val coordinator = makeCoordinatorProbe()
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val peerProbe = ClassicTestProbe()
    val peer = PeerTestHelpers.createTestPeer("bc-peer-7", peerProbe.ref)
    val worker = makeWorker(coordinator, networkPeerManager)

    val reqId = BigInt(10)
    worker ! ByteCodeCoordinator.ByteCodeWorkerFetchTask(makeTask(), peer, reqId, BigInt(1024 * 1024))
    networkPeerManager.expectMessageType[NetworkPeerManagerActor.SendMessageCmd](1.second)

    val wrongReqId = BigInt(999)
    val response = ByteCodes(requestId = wrongReqId, codes = Seq.empty)
    worker ! ByteCodeCoordinator.ByteCodesResponseMsg(response)

    // Coordinator should NOT receive anything for the mismatched response
    coordinator.expectNoMessage(200.millis)
  }
