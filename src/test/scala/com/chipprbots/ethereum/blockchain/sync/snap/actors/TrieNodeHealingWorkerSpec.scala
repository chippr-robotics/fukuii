package com.chipprbots.ethereum.blockchain.sync.snap.actors

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe
import org.apache.pekko.testkit.TestProbe as ClassicTestProbe
import org.apache.pekko.util.ByteString

import scala.concurrent.duration.*

import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.snap.*
import com.chipprbots.ethereum.network.p2p.messages.SNAP.TrieNodes
import com.chipprbots.ethereum.testing.PeerTestHelpers
import com.chipprbots.ethereum.testing.Tags.*

class TrieNodeHealingWorkerSpec extends ScalaTestWithActorTestKit with AnyFlatSpecLike with Matchers:

  implicit private val classicSystem: org.apache.pekko.actor.ActorSystem = system.classicSystem

  private def makeCoordinatorProbe(): TestProbe[TrieNodeHealingCoordinator.Command] =
    testKit.createTestProbe[TrieNodeHealingCoordinator.Command]()

  private val dummyHash = ByteString(Array.fill(32)(0xab.toByte))

  private def makeHealingTask() = HealingTask(
    path = Seq(dummyHash),
    hash = dummyHash,
    rootHash = dummyHash
  )

  private def makeWorker(
      coordinator: TestProbe[TrieNodeHealingCoordinator.Command]
  ): org.apache.pekko.actor.typed.ActorRef[TrieNodeHealingWorker.Command] =
    val networkPeerManager = ClassicTestProbe()
    val requestTracker = new SNAPRequestTracker()(using classicSystem.scheduler)
    testKit.spawn(
      TrieNodeHealingWorker(coordinator.ref, networkPeerManager.ref, requestTracker)
    )

  "TrieNodeHealingWorker" should "announce peer availability to coordinator on FetchTrieNodes" taggedAs UnitTest in {
    val coordinator = makeCoordinatorProbe()
    val peerProbe = ClassicTestProbe()
    val peer = PeerTestHelpers.createTestPeer("heal-peer-1", peerProbe.ref)
    val worker = makeWorker(coordinator)

    worker ! TrieNodeHealingCoordinator.FetchTrieNodes(makeHealingTask(), peer)

    coordinator.expectMessage(1.second, TrieNodeHealingCoordinator.HealingPeerAvailable(peer))
  }

  it should "forward TrieNodesResponseMsg to coordinator while working" taggedAs UnitTest in {
    val coordinator = makeCoordinatorProbe()
    val peerProbe = ClassicTestProbe()
    val peer = PeerTestHelpers.createTestPeer("heal-peer-2", peerProbe.ref)
    val worker = makeWorker(coordinator)

    worker ! TrieNodeHealingCoordinator.FetchTrieNodes(makeHealingTask(), peer)
    coordinator.expectMessageType[TrieNodeHealingCoordinator.HealingPeerAvailable](1.second)

    val response = TrieNodes(requestId = BigInt(7), nodes = Seq(dummyHash))
    worker ! TrieNodeHealingCoordinator.TrieNodesResponseMsg(response)

    coordinator.expectMessage(1.second, TrieNodeHealingCoordinator.TrieNodesResponseMsg(response))
  }

  it should "return to idle after forwarding response (accept a second FetchTrieNodes)" taggedAs UnitTest in {
    val coordinator = makeCoordinatorProbe()
    val peerProbe = ClassicTestProbe()
    val peer = PeerTestHelpers.createTestPeer("heal-peer-3", peerProbe.ref)
    val worker = makeWorker(coordinator)

    // First cycle
    worker ! TrieNodeHealingCoordinator.FetchTrieNodes(makeHealingTask(), peer)
    coordinator.expectMessageType[TrieNodeHealingCoordinator.HealingPeerAvailable](1.second)
    val resp1 = TrieNodes(requestId = BigInt(1), nodes = Seq.empty)
    worker ! TrieNodeHealingCoordinator.TrieNodesResponseMsg(resp1)
    coordinator.expectMessage(1.second, TrieNodeHealingCoordinator.TrieNodesResponseMsg(resp1))

    // Second cycle — must be back in idle
    worker ! TrieNodeHealingCoordinator.FetchTrieNodes(makeHealingTask(), peer)
    coordinator.expectMessage(1.second, TrieNodeHealingCoordinator.HealingPeerAvailable(peer))
  }

  it should "ignore HealingRequestTimeout for unknown request ID (no currentRequestId set)" taggedAs UnitTest in {
    val coordinator = makeCoordinatorProbe()
    val peerProbe = ClassicTestProbe()
    val peer = PeerTestHelpers.createTestPeer("heal-peer-4", peerProbe.ref)
    val worker = makeWorker(coordinator)

    worker ! TrieNodeHealingCoordinator.FetchTrieNodes(makeHealingTask(), peer)
    coordinator.expectMessageType[TrieNodeHealingCoordinator.HealingPeerAvailable](1.second)

    // currentRequestId is never explicitly set in TrieNodeHealingWorker (proxy pattern),
    // so a timeout for any ID is silently ignored.
    worker ! TrieNodeHealingCoordinator.HealingRequestTimeout(BigInt(999))
    coordinator.expectNoMessage(200.millis)
  }

  it should "transition back to idle via HealingCheckIdle when no request is pending" taggedAs UnitTest in {
    val coordinator = makeCoordinatorProbe()
    val peerProbe = ClassicTestProbe()
    val peer = PeerTestHelpers.createTestPeer("heal-peer-5", peerProbe.ref)
    val worker = makeWorker(coordinator)

    worker ! TrieNodeHealingCoordinator.FetchTrieNodes(makeHealingTask(), peer)
    coordinator.expectMessageType[TrieNodeHealingCoordinator.HealingPeerAvailable](1.second)

    // HealingCheckIdle while currentRequestId is None → return to idle
    worker ! TrieNodeHealingCoordinator.HealingCheckIdle

    // Worker should now accept a new FetchTrieNodes
    worker ! TrieNodeHealingCoordinator.FetchTrieNodes(makeHealingTask(), peer)
    coordinator.expectMessage(1.second, TrieNodeHealingCoordinator.HealingPeerAvailable(peer))
  }
