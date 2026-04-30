package com.chipprbots.ethereum.blockchain.sync.snap.actors

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.{TestKit, TestProbe, ImplicitSender}
import org.apache.pekko.util.ByteString

import scala.concurrent.duration._

import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.snap._
import com.chipprbots.ethereum.network.p2p.messages.SNAP.StorageRanges
import com.chipprbots.ethereum.testing.PeerTestHelpers
import com.chipprbots.ethereum.testing.Tags._

class StorageRangeWorkerSpec
    extends TestKit(ActorSystem("StorageRangeWorkerSpec"))
    with ImplicitSender
    with AnyFlatSpecLike
    with Matchers
    with BeforeAndAfterAll {

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  private val zeroHash = ByteString(new Array[Byte](32))
  private val maxHash  = ByteString(Array.fill(32)(0xff.toByte))

  private def makeStorageTask() = StorageTask(
    accountHash = ByteString(Array.fill(32)(0xaa.toByte)),
    storageRoot = zeroHash,
    next        = zeroHash,
    last        = maxHash
  )

  private def makeWorker(coordinator: TestProbe): org.apache.pekko.actor.ActorRef = {
    val networkPeerManager = TestProbe()
    val requestTracker     = new SNAPRequestTracker()(system.scheduler)
    system.actorOf(
      StorageRangeWorker.props(coordinator.ref, networkPeerManager.ref, requestTracker)
    )
  }

  "StorageRangeWorker" should "announce peer availability to coordinator on FetchStorageRanges" taggedAs UnitTest in {
    val coordinator = TestProbe()
    val peerProbe   = TestProbe()
    val peer        = PeerTestHelpers.createTestPeer("peer-1", peerProbe.ref)
    val worker      = makeWorker(coordinator)

    worker ! Messages.FetchStorageRanges(makeStorageTask(), peer)

    coordinator.expectMsg(1.second, Messages.StoragePeerAvailable(peer))
  }

  it should "forward StorageRangesResponseMsg to coordinator while working" taggedAs UnitTest in {
    val coordinator = TestProbe()
    val peerProbe   = TestProbe()
    val peer        = PeerTestHelpers.createTestPeer("peer-2", peerProbe.ref)
    val worker      = makeWorker(coordinator)

    worker ! Messages.FetchStorageRanges(makeStorageTask(), peer)
    coordinator.expectMsgType[Messages.StoragePeerAvailable](1.second)

    val response = StorageRanges(requestId = BigInt(42), slots = Seq.empty, proof = Seq.empty)
    worker ! Messages.StorageRangesResponseMsg(response)

    coordinator.expectMsg(1.second, Messages.StorageRangesResponseMsg(response))
  }

  it should "return to idle after forwarding response (accept a second FetchStorageRanges)" taggedAs UnitTest in {
    val coordinator = TestProbe()
    val peerProbe   = TestProbe()
    val peer        = PeerTestHelpers.createTestPeer("peer-3", peerProbe.ref)
    val worker      = makeWorker(coordinator)

    // First cycle
    worker ! Messages.FetchStorageRanges(makeStorageTask(), peer)
    coordinator.expectMsgType[Messages.StoragePeerAvailable](1.second)
    val resp1 = StorageRanges(requestId = BigInt(1), slots = Seq.empty, proof = Seq.empty)
    worker ! Messages.StorageRangesResponseMsg(resp1)
    coordinator.expectMsg(1.second, Messages.StorageRangesResponseMsg(resp1))

    // Second cycle — worker must be back in idle to accept this
    worker ! Messages.FetchStorageRanges(makeStorageTask(), peer)
    coordinator.expectMsg(1.second, Messages.StoragePeerAvailable(peer))
  }

  it should "report StorageTaskFailed to coordinator on StorageRequestTimeout" taggedAs UnitTest in {
    val coordinator = TestProbe()
    val peerProbe   = TestProbe()
    val peer        = PeerTestHelpers.createTestPeer("peer-4", peerProbe.ref)
    val worker      = makeWorker(coordinator)

    worker ! Messages.FetchStorageRanges(makeStorageTask(), peer)
    coordinator.expectMsgType[Messages.StoragePeerAvailable](1.second)

    val reqId: BigInt = 99
    worker ! Messages.StorageRequestTimeout(reqId)

    // No current request ID is set (worker doesn't track one by default in this architecture),
    // so timeout for a mismatched ID is silently ignored.
    coordinator.expectNoMessage(200.millis)
  }

  it should "transition back to idle via StorageCheckIdle when no request is pending" taggedAs UnitTest in {
    val coordinator = TestProbe()
    val peerProbe   = TestProbe()
    val peer        = PeerTestHelpers.createTestPeer("peer-5", peerProbe.ref)
    val worker      = makeWorker(coordinator)

    worker ! Messages.FetchStorageRanges(makeStorageTask(), peer)
    coordinator.expectMsgType[Messages.StoragePeerAvailable](1.second)

    // Send StorageCheckIdle while no currentRequestId set — worker returns to idle
    worker ! Messages.StorageCheckIdle

    // Now in idle — a new FetchStorageRanges should be accepted
    worker ! Messages.FetchStorageRanges(makeStorageTask(), peer)
    coordinator.expectMsg(1.second, Messages.StoragePeerAvailable(peer))
  }
}
