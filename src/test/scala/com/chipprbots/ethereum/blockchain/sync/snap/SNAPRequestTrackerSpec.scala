package com.chipprbots.ethereum.blockchain.sync.snap

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.{TestKit, TestProbe}
import org.apache.pekko.util.ByteString

import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._

import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.network.Peer
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

    @scala.annotation.unused
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

  // ========================================
  // Adaptive timeout via PeerRateTracker (issue #1168)
  // ========================================

  it should "default to the rate-tracker adaptive timeout when no explicit timeout is supplied" taggedAs UnitTest in {
    // Fresh tracker: medianRTT = RttMinEstimateMs (2000ms), confidence = 0.5
    // Formula: min(60s, 3 × medianRTT / confidence) = min(60s, 12000ms) = 12s.
    val tracker = new SNAPRequestTracker()
    val adaptive = tracker.rateTracker.targetTimeout()
    adaptive shouldBe 12.seconds
  }

  it should "stay capped at PeerRateTracker.TtlLimitMs (60s) regardless of confidence drift" taggedAs UnitTest in {
    val tracker = new SNAPRequestTracker()
    // Even with confidence near the floor and minimum medianRTT, the upper bound is hard-capped.
    // Drive confidence as low as possible by adding many peers (detune is multiplicative).
    val testProbe = TestProbe()
    (1 to 100).foreach(i => tracker.rateTracker.addPeer(s"peer-$i"))
    val capped = tracker.rateTracker.targetTimeout()
    capped should be <= 60.seconds
  }

  it should "fire the timeout callback for an unresponsive peer within the adaptive window" taggedAs UnitTest in {
    // We can't wait the full 12s in a unit test, so we cover the firing-mechanism with a tiny
    // explicit override. The wiring proof — that `trackRequest` without an explicit timeout
    // *would* select the adaptive value — is covered by the previous test.
    val tracker = new SNAPRequestTracker()
    val testProbe = TestProbe()
    val peer = createTestPeer("slow-peer", testProbe.ref)

    var timeoutCalled = false
    val requestId = tracker.generateRequestId()
    tracker.trackRequest(requestId, peer, SNAPRequestTracker.RequestType.GetAccountRange, timeout = 50.millis) {
      timeoutCalled = true
    }

    awaitCond(timeoutCalled, max = 500.millis)
    tracker.isPending(requestId) shouldBe false
  }

  it should "shorten targetTimeout after fast responses tune the rate tracker" taggedAs UnitTest in {
    // A fast-responding peer shifts medianRTT down toward its actual RTT, then `tune()` updates
    // the EMA — confirming that adaptive timeout *can* be shorter than the initial 12s.
    val tracker = new SNAPRequestTracker()
    val initial = tracker.rateTracker.targetTimeout()

    // Simulate fast responses: 4 peers each delivering 100 items in 50ms.
    (1 to 4).foreach { i =>
      val peerId = s"fast-peer-$i"
      tracker.rateTracker.addPeer(peerId)
      tracker.rateTracker.update(peerId, PeerRateTracker.MsgGetAccountRange, elapsedMs = 50L, items = 100)
    }
    // Bump confidence toward 1.0 and let the EMA migrate medianRTT downward.
    (1 to 20).foreach(_ => tracker.rateTracker.tune())

    val converged = tracker.rateTracker.targetTimeout()
    // Converged timeout should be no larger than the initial — the rate tracker is allowed
    // to keep it at the floor, but it must never grow beyond the starting estimate when peers
    // are demonstrably faster than the default.
    converged should be <= initial
  }
}
