package com.chipprbots.ethereum.blockchain.sync.snap.actors

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.{TestKit, TestProbe, ImplicitSender}
import org.apache.pekko.util.ByteString

import scala.concurrent.duration._

import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.snap._
import com.chipprbots.ethereum.network.p2p.messages.SNAP.TrieNodes
import com.chipprbots.ethereum.testing.PeerTestHelpers
import com.chipprbots.ethereum.testing.Tags._

class TrieNodeHealingWorkerSpec
    extends TestKit(ActorSystem("TrieNodeHealingWorkerSpec"))
    with ImplicitSender
    with AnyFlatSpecLike
    with Matchers
    with BeforeAndAfterAll {

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  private val dummyHash = ByteString(Array.fill(32)(0xab.toByte))

  private def makeHealingTask() = HealingTask(
    path = Seq(dummyHash),
    hash = dummyHash,
    rootHash = dummyHash
  )

  private def makeWorker(coordinator: TestProbe): org.apache.pekko.actor.ActorRef = {
    val networkPeerManager = TestProbe()
    val requestTracker = new SNAPRequestTracker()(system.scheduler)
    system.actorOf(
      TrieNodeHealingWorker.props(coordinator.ref, networkPeerManager.ref, requestTracker)
    )
  }

  "TrieNodeHealingWorker" should "announce peer availability to coordinator on FetchTrieNodes" taggedAs UnitTest in {
    val coordinator = TestProbe()
    val peerProbe = TestProbe()
    val peer = PeerTestHelpers.createTestPeer("heal-peer-1", peerProbe.ref)
    val worker = makeWorker(coordinator)

    worker ! Messages.FetchTrieNodes(makeHealingTask(), peer)

    coordinator.expectMsg(1.second, Messages.HealingPeerAvailable(peer))
  }

  it should "forward TrieNodesResponseMsg to coordinator while working" taggedAs UnitTest in {
    val coordinator = TestProbe()
    val peerProbe = TestProbe()
    val peer = PeerTestHelpers.createTestPeer("heal-peer-2", peerProbe.ref)
    val worker = makeWorker(coordinator)

    worker ! Messages.FetchTrieNodes(makeHealingTask(), peer)
    coordinator.expectMsgType[Messages.HealingPeerAvailable](1.second)

    val response = TrieNodes(requestId = BigInt(7), nodes = Seq(dummyHash))
    worker ! Messages.TrieNodesResponseMsg(response)

    coordinator.expectMsg(1.second, Messages.TrieNodesResponseMsg(response))
  }

  it should "return to idle after forwarding response (accept a second FetchTrieNodes)" taggedAs UnitTest in {
    val coordinator = TestProbe()
    val peerProbe = TestProbe()
    val peer = PeerTestHelpers.createTestPeer("heal-peer-3", peerProbe.ref)
    val worker = makeWorker(coordinator)

    // First cycle
    worker ! Messages.FetchTrieNodes(makeHealingTask(), peer)
    coordinator.expectMsgType[Messages.HealingPeerAvailable](1.second)
    val resp1 = TrieNodes(requestId = BigInt(1), nodes = Seq.empty)
    worker ! Messages.TrieNodesResponseMsg(resp1)
    coordinator.expectMsg(1.second, Messages.TrieNodesResponseMsg(resp1))

    // Second cycle — must be back in idle
    worker ! Messages.FetchTrieNodes(makeHealingTask(), peer)
    coordinator.expectMsg(1.second, Messages.HealingPeerAvailable(peer))
  }

  it should "ignore HealingRequestTimeout for unknown request ID (no currentRequestId set)" taggedAs UnitTest in {
    val coordinator = TestProbe()
    val peerProbe = TestProbe()
    val peer = PeerTestHelpers.createTestPeer("heal-peer-4", peerProbe.ref)
    val worker = makeWorker(coordinator)

    worker ! Messages.FetchTrieNodes(makeHealingTask(), peer)
    coordinator.expectMsgType[Messages.HealingPeerAvailable](1.second)

    // currentRequestId is never explicitly set in TrieNodeHealingWorker (proxy pattern),
    // so a timeout for any ID is silently ignored.
    worker ! Messages.HealingRequestTimeout(BigInt(999))
    coordinator.expectNoMessage(200.millis)
  }

  it should "transition back to idle via HealingCheckIdle when no request is pending" taggedAs UnitTest in {
    val coordinator = TestProbe()
    val peerProbe = TestProbe()
    val peer = PeerTestHelpers.createTestPeer("heal-peer-5", peerProbe.ref)
    val worker = makeWorker(coordinator)

    worker ! Messages.FetchTrieNodes(makeHealingTask(), peer)
    coordinator.expectMsgType[Messages.HealingPeerAvailable](1.second)

    // HealingCheckIdle while currentRequestId is None → return to idle
    worker ! Messages.HealingCheckIdle

    // Worker should now accept a new FetchTrieNodes
    worker ! Messages.FetchTrieNodes(makeHealingTask(), peer)
    coordinator.expectMsg(1.second, Messages.HealingPeerAvailable(peer))
  }
}
