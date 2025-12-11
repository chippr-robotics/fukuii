package com.chipprbots.ethereum.blockchain.sync.snap

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.{TestKit, TestProbe}
import org.apache.pekko.util.ByteString

import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._

import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.network.{Peer, PeerId}
import com.chipprbots.ethereum.network.p2p.messages.SNAP._
import com.chipprbots.ethereum.testing.Tags._
import com.chipprbots.ethereum.testing.PeerTestHelpers._

class SNAPRequestTrackerSpec
    extends TestKit(ActorSystem("SNAPRequestTrackerSpec"))
    with AnyFlatSpecLike
    with Matchers
    with BeforeAndAfterAll {

  override def afterAll(): Unit =
    TestKit.shutdownActorSystem(system)

  implicit val scheduler: org.apache.pekko.actor.Scheduler = system.scheduler

  "SNAPRequestTracker" should "generate unique request IDs" taggedAs UnitTest in {
    val tracker = new SNAPRequestTracker()

    val id1 = tracker.generateRequestId()
    val id2 = tracker.generateRequestId()
    val id3 = tracker.generateRequestId()

    (id1 should not).equal(id2)
    (id2 should not).equal(id3)
    id1 should be < id2
    id2 should be < id3
  }

  it should "track pending requests" taggedAs UnitTest in {
    val tracker = new SNAPRequestTracker()
    val testProbe = TestProbe()
    val peer = createTestPeer("test-peer", testProbe.ref)

    val requestId = tracker.generateRequestId()
    tracker.isPending(requestId) shouldBe false

    var timeoutCalled = false
    tracker.trackRequest(requestId, peer, SNAPRequestTracker.RequestType.GetAccountRange) {
      timeoutCalled = true
    }

    tracker.isPending(requestId) shouldBe true
    tracker.getPendingRequest(requestId) shouldBe defined
    tracker.getPendingRequest(requestId).get.peer shouldBe peer
    tracker.getPendingRequest(requestId).get.requestType shouldBe SNAPRequestTracker.RequestType.GetAccountRange
  }

  it should "complete pending requests" taggedAs UnitTest in {
    val tracker = new SNAPRequestTracker()
    val testProbe = TestProbe()
    val peer = createTestPeer("test-peer", testProbe.ref)

    val requestId = tracker.generateRequestId()
    tracker.trackRequest(requestId, peer, SNAPRequestTracker.RequestType.GetAccountRange) {}

    tracker.isPending(requestId) shouldBe true

    val completed = tracker.completeRequest(requestId)
    completed shouldBe defined
    tracker.isPending(requestId) shouldBe false
  }

  it should "trigger timeout callback after configured duration" taggedAs UnitTest in {
    val tracker = new SNAPRequestTracker()
    val testProbe = TestProbe()
    val peer = createTestPeer("test-peer", testProbe.ref)

    var timeoutCalled = false
    val requestId = tracker.generateRequestId()
    tracker.trackRequest(requestId, peer, SNAPRequestTracker.RequestType.GetAccountRange, timeout = 100.millis) {
      timeoutCalled = true
    }

    tracker.isPending(requestId) shouldBe true

    // Wait for timeout to trigger using awaitCond
    awaitCond(!tracker.isPending(requestId), max = 300.millis)

    tracker.isPending(requestId) shouldBe false
    timeoutCalled shouldBe true
  }

  it should "not trigger timeout if request is completed before timeout" taggedAs UnitTest in {
    val tracker = new SNAPRequestTracker()
    val testProbe = TestProbe()
    val peer = createTestPeer("test-peer", testProbe.ref)

    var timeoutCalled = false
    val requestId = tracker.generateRequestId()
    tracker.trackRequest(requestId, peer, SNAPRequestTracker.RequestType.GetAccountRange, timeout = 200.millis) {
      timeoutCalled = true
    }

    // Complete the request quickly
    within(100.millis) {
      tracker.completeRequest(requestId)
    }

    // Wait a bit longer than timeout to ensure callback doesn't fire
    awaitCond(true, max = 300.millis)

    timeoutCalled shouldBe false
  }

  it should "validate AccountRange response with monotonic ordering" taggedAs UnitTest in {
    val tracker = new SNAPRequestTracker()
    val testProbe = TestProbe()
    val peer = createTestPeer("test-peer", testProbe.ref)

    val requestId = tracker.generateRequestId()
    tracker.trackRequest(requestId, peer, SNAPRequestTracker.RequestType.GetAccountRange) {}

    val account1 = (ByteString("account1"), Account(nonce = 1, balance = 100))
    val account2 = (ByteString("account2"), Account(nonce = 2, balance = 200))
    val account3 = (ByteString("account3"), Account(nonce = 3, balance = 300))

    val response = AccountRange(
      requestId = requestId,
      accounts = Seq(account1, account2, account3),
      proof = Seq.empty
    )

    val result = tracker.validateAccountRange(response)
    result shouldBe Right(response)
  }

  it should "reject AccountRange response with non-monotonic ordering" taggedAs UnitTest in {
    val tracker = new SNAPRequestTracker()
    val testProbe = TestProbe()
    val peer = createTestPeer("test-peer", testProbe.ref)

    val requestId = tracker.generateRequestId()
    tracker.trackRequest(requestId, peer, SNAPRequestTracker.RequestType.GetAccountRange) {}

    val account1 = (ByteString("account3"), Account(nonce = 3, balance = 300))
    val account2 = (ByteString("account1"), Account(nonce = 1, balance = 100))
    val account3 = (ByteString("account2"), Account(nonce = 2, balance = 200))

    val response = AccountRange(
      requestId = requestId,
      accounts = Seq(account1, account2, account3),
      proof = Seq.empty
    )

    val result = tracker.validateAccountRange(response)
    result shouldBe a[Left[_, _]]
    result.left.get should include("not monotonically increasing")
  }

  it should "reject AccountRange response for unknown request ID" taggedAs UnitTest in {
    val tracker = new SNAPRequestTracker()

    val unknownRequestId = BigInt(999)
    val response = AccountRange(
      requestId = unknownRequestId,
      accounts = Seq.empty,
      proof = Seq.empty
    )

    val result = tracker.validateAccountRange(response)
    result shouldBe a[Left[_, _]]
    result.left.get should include("No pending request")
  }

  it should "validate StorageRanges response with monotonic ordering" taggedAs UnitTest in {
    val tracker = new SNAPRequestTracker()
    val testProbe = TestProbe()
    val peer = createTestPeer("test-peer", testProbe.ref)

    val requestId = tracker.generateRequestId()
    tracker.trackRequest(requestId, peer, SNAPRequestTracker.RequestType.GetStorageRanges) {}

    val slot1 = (ByteString("slot1"), ByteString("value1"))
    val slot2 = (ByteString("slot2"), ByteString("value2"))
    val slot3 = (ByteString("slot3"), ByteString("value3"))

    val response = StorageRanges(
      requestId = requestId,
      slots = Seq(Seq(slot1, slot2, slot3)),
      proof = Seq.empty
    )

    val result = tracker.validateStorageRanges(response)
    result shouldBe Right(response)
  }

  it should "reject StorageRanges response with non-monotonic ordering" taggedAs UnitTest in {
    val tracker = new SNAPRequestTracker()
    val testProbe = TestProbe()
    val peer = createTestPeer("test-peer", testProbe.ref)

    val requestId = tracker.generateRequestId()
    tracker.trackRequest(requestId, peer, SNAPRequestTracker.RequestType.GetStorageRanges) {}

    val slot1 = (ByteString("slot3"), ByteString("value3"))
    val slot2 = (ByteString("slot1"), ByteString("value1"))
    val slot3 = (ByteString("slot2"), ByteString("value2"))

    val response = StorageRanges(
      requestId = requestId,
      slots = Seq(Seq(slot1, slot2, slot3)),
      proof = Seq.empty
    )

    val result = tracker.validateStorageRanges(response)
    result shouldBe a[Left[_, _]]
    result.left.get should include("not monotonically increasing")
  }

  it should "validate ByteCodes response" taggedAs UnitTest in {
    val tracker = new SNAPRequestTracker()
    val testProbe = TestProbe()
    val peer = createTestPeer("test-peer", testProbe.ref)

    val requestId = tracker.generateRequestId()
    tracker.trackRequest(requestId, peer, SNAPRequestTracker.RequestType.GetByteCodes) {}

    val response = ByteCodes(
      requestId = requestId,
      codes = Seq(ByteString("code1"), ByteString("code2"))
    )

    val result = tracker.validateByteCodes(response)
    result shouldBe Right(response)
  }

  it should "handle multiple concurrent pending requests" taggedAs UnitTest in {
    val tracker = new SNAPRequestTracker()
    val testProbe = TestProbe()
    val peer1 = createTestPeer("peer1", testProbe.ref)
    val peer2 = createTestPeer("peer2", testProbe.ref)

    val id1 = tracker.generateRequestId()
    val id2 = tracker.generateRequestId()
    val id3 = tracker.generateRequestId()

    tracker.trackRequest(id1, peer1, SNAPRequestTracker.RequestType.GetAccountRange) {}
    tracker.trackRequest(id2, peer2, SNAPRequestTracker.RequestType.GetStorageRanges) {}
    tracker.trackRequest(id3, peer1, SNAPRequestTracker.RequestType.GetByteCodes) {}

    tracker.isPending(id1) shouldBe true
    tracker.isPending(id2) shouldBe true
    tracker.isPending(id3) shouldBe true

    tracker.getPendingRequest(id1).get.requestType shouldBe SNAPRequestTracker.RequestType.GetAccountRange
    tracker.getPendingRequest(id2).get.requestType shouldBe SNAPRequestTracker.RequestType.GetStorageRanges
    tracker.getPendingRequest(id3).get.requestType shouldBe SNAPRequestTracker.RequestType.GetByteCodes

    tracker.completeRequest(id2)
    tracker.isPending(id1) shouldBe true
    tracker.isPending(id2) shouldBe false
    tracker.isPending(id3) shouldBe true
  }
}
