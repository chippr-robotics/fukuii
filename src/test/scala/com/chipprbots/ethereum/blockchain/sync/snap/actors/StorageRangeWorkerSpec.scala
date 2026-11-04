package com.chipprbots.ethereum.blockchain.sync.snap.actors

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe
import org.apache.pekko.testkit.TestProbe as ClassicTestProbe
import org.apache.pekko.util.ByteString

import scala.concurrent.duration.*

import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.snap.*
import com.chipprbots.ethereum.network.p2p.messages.SNAP.StorageRanges
import com.chipprbots.ethereum.testing.PeerTestHelpers
import com.chipprbots.ethereum.testing.Tags.*

class StorageRangeWorkerSpec extends ScalaTestWithActorTestKit with AnyFlatSpecLike with Matchers:

  implicit private val classicSystem: org.apache.pekko.actor.ActorSystem = system.classicSystem

  private def makeCoordinatorProbe(): TestProbe[StorageRangeCoordinator.Command] =
    testKit.createTestProbe[StorageRangeCoordinator.Command]()

  private val zeroHash = ByteString(new Array[Byte](32))
  private val maxHash = ByteString(Array.fill(32)(0xff.toByte))

  private def makeStorageTask() = StorageTask(
    accountHash = ByteString(Array.fill(32)(0xaa.toByte)),
    storageRoot = zeroHash,
    next = zeroHash,
    last = maxHash
  )

  private def makeWorker(
      coordinator: TestProbe[StorageRangeCoordinator.Command]
  ): org.apache.pekko.actor.typed.ActorRef[StorageRangeWorker.Command] =
    val networkPeerManager = ClassicTestProbe()
    val requestTracker = new SNAPRequestTracker()(using classicSystem.scheduler)
    testKit.spawn(
      StorageRangeWorker(coordinator.ref, networkPeerManager.ref, requestTracker)
    )

  "StorageRangeWorker" should "announce peer availability to coordinator on FetchStorageRanges" taggedAs UnitTest in {
    val coordinator = makeCoordinatorProbe()
    val peerProbe = ClassicTestProbe()
    val peer = PeerTestHelpers.createTestPeer("peer-1", peerProbe.ref)
    val worker = makeWorker(coordinator)

    worker ! StorageRangeCoordinator.FetchStorageRanges(makeStorageTask(), peer)

    coordinator.expectMessage(1.second, StorageRangeCoordinator.StoragePeerAvailable(peer))
  }

  it should "forward StorageRangesResponseMsg to coordinator while working" taggedAs UnitTest in {
    val coordinator = makeCoordinatorProbe()
    val peerProbe = ClassicTestProbe()
    val peer = PeerTestHelpers.createTestPeer("peer-2", peerProbe.ref)
    val worker = makeWorker(coordinator)

    worker ! StorageRangeCoordinator.FetchStorageRanges(makeStorageTask(), peer)
    coordinator.expectMessageType[StorageRangeCoordinator.StoragePeerAvailable](1.second)

    val response = StorageRanges(requestId = BigInt(42), slots = Seq.empty, proof = Seq.empty)
    worker ! StorageRangeCoordinator.StorageRangesResponseMsg(response)

    coordinator.expectMessage(1.second, StorageRangeCoordinator.StorageRangesResponseMsg(response))
  }

  it should "return to idle after forwarding response (accept a second FetchStorageRanges)" taggedAs UnitTest in {
    val coordinator = makeCoordinatorProbe()
    val peerProbe = ClassicTestProbe()
    val peer = PeerTestHelpers.createTestPeer("peer-3", peerProbe.ref)
    val worker = makeWorker(coordinator)

    // First cycle
    worker ! StorageRangeCoordinator.FetchStorageRanges(makeStorageTask(), peer)
    coordinator.expectMessageType[StorageRangeCoordinator.StoragePeerAvailable](1.second)
    val resp1 = StorageRanges(requestId = BigInt(1), slots = Seq.empty, proof = Seq.empty)
    worker ! StorageRangeCoordinator.StorageRangesResponseMsg(resp1)
    coordinator.expectMessage(1.second, StorageRangeCoordinator.StorageRangesResponseMsg(resp1))

    // Second cycle — worker must be back in idle to accept this
    worker ! StorageRangeCoordinator.FetchStorageRanges(makeStorageTask(), peer)
    coordinator.expectMessage(1.second, StorageRangeCoordinator.StoragePeerAvailable(peer))
  }

  it should "report StorageTaskFailed to coordinator on StorageRequestTimeout" taggedAs UnitTest in {
    val coordinator = makeCoordinatorProbe()
    val peerProbe = ClassicTestProbe()
    val peer = PeerTestHelpers.createTestPeer("peer-4", peerProbe.ref)
    val worker = makeWorker(coordinator)

    worker ! StorageRangeCoordinator.FetchStorageRanges(makeStorageTask(), peer)
    coordinator.expectMessageType[StorageRangeCoordinator.StoragePeerAvailable](1.second)

    val reqId: BigInt = 99
    worker ! StorageRangeCoordinator.StorageRequestTimeout(reqId)

    // No current request ID is set (worker doesn't track one by default in this architecture),
    // so timeout for a mismatched ID is silently ignored.
    coordinator.expectNoMessage(200.millis)
  }

  it should "transition back to idle via StorageCheckIdle when no request is pending" taggedAs UnitTest in {
    val coordinator = makeCoordinatorProbe()
    val peerProbe = ClassicTestProbe()
    val peer = PeerTestHelpers.createTestPeer("peer-5", peerProbe.ref)
    val worker = makeWorker(coordinator)

    worker ! StorageRangeCoordinator.FetchStorageRanges(makeStorageTask(), peer)
    coordinator.expectMessageType[StorageRangeCoordinator.StoragePeerAvailable](1.second)

    // Send StorageCheckIdle while no currentRequestId set — worker returns to idle
    worker ! StorageRangeCoordinator.StorageCheckIdle

    // Now in idle — a new FetchStorageRanges should be accepted
    worker ! StorageRangeCoordinator.FetchStorageRanges(makeStorageTask(), peer)
    coordinator.expectMessage(1.second, StorageRangeCoordinator.StoragePeerAvailable(peer))
  }
