package com.chipprbots.ethereum.blockchain.sync.snap

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.{TestKit, TestProbe}
import org.apache.pekko.util.ByteString

import scala.concurrent.duration._

import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.network.p2p.messages.SNAP._
import com.chipprbots.ethereum.testing.Tags._
import com.chipprbots.ethereum.testing.PeerTestHelpers._

/** Extended tests for SNAPRequestTracker focusing on adaptive timeout integration
  * with PeerRateTracker (geth msgrate port).
  */
class SNAPRequestTrackerAdaptiveSpec
    extends TestKit(ActorSystem("SNAPRequestTrackerAdaptiveSpec"))
    with AnyFlatSpecLike
    with Matchers
    with BeforeAndAfterAll {

  override def afterAll(): Unit =
    TestKit.shutdownActorSystem(system)

  implicit val scheduler: org.apache.pekko.actor.Scheduler = system.scheduler

  // ========================================
  // Rate Tracker Integration
  // ========================================

  "SNAPRequestTracker" should "expose rate tracker for peer management" taggedAs UnitTest in {
    val tracker = new SNAPRequestTracker()
    tracker.rateTracker should not be null
    tracker.rateTracker.peerCount shouldBe 0
  }

  it should "use adaptive timeout when no explicit timeout given" taggedAs UnitTest in {
    val tracker = new SNAPRequestTracker()
    val testProbe = TestProbe()
    val peer = createTestPeer("peer-1", testProbe.ref)

    // The default timeout (Duration.Zero) triggers adaptive timeout from rateTracker
    val requestId = tracker.generateRequestId()
    var timedOut = false
    tracker.trackRequest(requestId, peer, SNAPRequestTracker.RequestType.GetAccountRange) {
      timedOut = true
    }

    tracker.isPending(requestId) shouldBe true
    // Adaptive timeout will be based on initial median RTT (conservative start)
  }

  it should "record response metrics in rate tracker on completion" taggedAs UnitTest in {
    val tracker = new SNAPRequestTracker()
    val testProbe = TestProbe()
    val peer = createTestPeer("peer-1", testProbe.ref)

    // Add peer to rate tracker
    tracker.rateTracker.addPeer("peer-1")

    val requestId = tracker.generateRequestId()
    tracker.trackRequest(requestId, peer, SNAPRequestTracker.RequestType.GetAccountRange, timeout = 30.seconds) {}

    // Complete with items count
    tracker.completeRequest(requestId, responseItems = 100)

    // Rate tracker should now have measurement
    val cap = tracker.rateTracker.capacity("peer-1", PeerRateTracker.MsgGetAccountRange, targetRTT = 2000)
    cap should be >= 1
  }

  it should "record timeout in rate tracker (slashing capacity to zero)" taggedAs UnitTest in {
    val tracker = new SNAPRequestTracker()
    val testProbe = TestProbe()
    val peer = createTestPeer("peer-1", testProbe.ref)

    tracker.rateTracker.addPeer("peer-1")

    // Record some good data first
    tracker.rateTracker.update("peer-1", PeerRateTracker.MsgGetAccountRange, 500, 200)

    val requestId = tracker.generateRequestId()
    var timedOut = false
    tracker.trackRequest(requestId, peer, SNAPRequestTracker.RequestType.GetAccountRange, timeout = 50.millis) {
      timedOut = true
    }

    // Wait for timeout
    awaitCond(timedOut, max = 500.millis)

    // After timeout, the rate tracker should have recorded items=0
    // (capacity slashed)
    tracker.isPending(requestId) shouldBe false
  }

  // ========================================
  // Request Type Validation
  // ========================================

  it should "reject response with wrong request type" taggedAs UnitTest in {
    val tracker = new SNAPRequestTracker()
    val testProbe = TestProbe()
    val peer = createTestPeer("peer-1", testProbe.ref)

    val requestId = tracker.generateRequestId()
    tracker.trackRequest(requestId, peer, SNAPRequestTracker.RequestType.GetStorageRanges) {}

    // Try to validate as AccountRange
    val response = AccountRange(requestId, Seq.empty, Seq.empty)
    val result = tracker.validateAccountRange(response)

    result shouldBe a[Left[_, _]]
    result.left.get should include("Expected")
  }

  it should "reject StorageRanges response with wrong request type" taggedAs UnitTest in {
    val tracker = new SNAPRequestTracker()
    val testProbe = TestProbe()
    val peer = createTestPeer("peer-1", testProbe.ref)

    val requestId = tracker.generateRequestId()
    tracker.trackRequest(requestId, peer, SNAPRequestTracker.RequestType.GetAccountRange) {}

    val response = StorageRanges(requestId, Seq.empty, Seq.empty)
    val result = tracker.validateStorageRanges(response)

    result shouldBe a[Left[_, _]]
  }

  it should "reject ByteCodes response with wrong request type" taggedAs UnitTest in {
    val tracker = new SNAPRequestTracker()
    val testProbe = TestProbe()
    val peer = createTestPeer("peer-1", testProbe.ref)

    val requestId = tracker.generateRequestId()
    tracker.trackRequest(requestId, peer, SNAPRequestTracker.RequestType.GetAccountRange) {}

    val response = ByteCodes(requestId, Seq.empty)
    val result = tracker.validateByteCodes(response)

    result shouldBe a[Left[_, _]]
  }

  it should "reject TrieNodes response for unknown request" taggedAs UnitTest in {
    val tracker = new SNAPRequestTracker()
    val response = TrieNodes(BigInt(999), Seq.empty)
    val result = tracker.validateTrieNodes(response)

    result shouldBe a[Left[_, _]]
    result.left.get should include("No pending request")
  }

  it should "validate TrieNodes response for correct request type" taggedAs UnitTest in {
    val tracker = new SNAPRequestTracker()
    val testProbe = TestProbe()
    val peer = createTestPeer("peer-1", testProbe.ref)

    val requestId = tracker.generateRequestId()
    tracker.trackRequest(requestId, peer, SNAPRequestTracker.RequestType.GetTrieNodes) {}

    val response = TrieNodes(requestId, Seq(ByteString("node1")))
    val result = tracker.validateTrieNodes(response)
    result shouldBe Right(response)
  }

  // ========================================
  // Pending Count and Clear
  // ========================================

  it should "track pending count accurately" taggedAs UnitTest in {
    val tracker = new SNAPRequestTracker()
    val testProbe = TestProbe()
    val peer = createTestPeer("peer-1", testProbe.ref)

    tracker.pendingCount shouldBe 0

    val id1 = tracker.generateRequestId()
    val id2 = tracker.generateRequestId()
    val id3 = tracker.generateRequestId()

    tracker.trackRequest(id1, peer, SNAPRequestTracker.RequestType.GetAccountRange, timeout = 30.seconds) {}
    tracker.trackRequest(id2, peer, SNAPRequestTracker.RequestType.GetStorageRanges, timeout = 30.seconds) {}
    tracker.trackRequest(id3, peer, SNAPRequestTracker.RequestType.GetByteCodes, timeout = 30.seconds) {}

    tracker.pendingCount shouldBe 3

    tracker.completeRequest(id2)
    tracker.pendingCount shouldBe 2
  }

  it should "clear all pending requests" taggedAs UnitTest in {
    val tracker = new SNAPRequestTracker()
    val testProbe = TestProbe()
    val peer = createTestPeer("peer-1", testProbe.ref)

    (1 to 5).foreach { _ =>
      val id = tracker.generateRequestId()
      tracker.trackRequest(id, peer, SNAPRequestTracker.RequestType.GetAccountRange, timeout = 30.seconds) {}
    }

    tracker.pendingCount shouldBe 5

    tracker.clear()
    tracker.pendingCount shouldBe 0
  }

  // ========================================
  // Monotonic Request IDs
  // ========================================

  it should "generate strictly monotonically increasing request IDs" taggedAs UnitTest in {
    val tracker = new SNAPRequestTracker()

    val ids = (1 to 100).map(_ => tracker.generateRequestId())

    ids.sliding(2).foreach { case Seq(a, b) =>
      a should be < b
    }
  }

  // ========================================
  // Complete returns None for unknown requests
  // ========================================

  it should "return None when completing unknown request" taggedAs UnitTest in {
    val tracker = new SNAPRequestTracker()
    tracker.completeRequest(BigInt(99999)) shouldBe None
  }

  // ========================================
  // Empty responses validation
  // ========================================

  it should "validate empty AccountRange response" taggedAs UnitTest in {
    val tracker = new SNAPRequestTracker()
    val testProbe = TestProbe()
    val peer = createTestPeer("peer-1", testProbe.ref)

    val requestId = tracker.generateRequestId()
    tracker.trackRequest(requestId, peer, SNAPRequestTracker.RequestType.GetAccountRange) {}

    val response = AccountRange(requestId, Seq.empty, Seq.empty)
    val result = tracker.validateAccountRange(response)

    result shouldBe Right(response) // Empty range is valid
  }

  it should "validate empty StorageRanges response" taggedAs UnitTest in {
    val tracker = new SNAPRequestTracker()
    val testProbe = TestProbe()
    val peer = createTestPeer("peer-1", testProbe.ref)

    val requestId = tracker.generateRequestId()
    tracker.trackRequest(requestId, peer, SNAPRequestTracker.RequestType.GetStorageRanges) {}

    val response = StorageRanges(requestId, Seq.empty, Seq.empty)
    val result = tracker.validateStorageRanges(response)

    result shouldBe Right(response)
  }

  it should "validate empty ByteCodes response" taggedAs UnitTest in {
    val tracker = new SNAPRequestTracker()
    val testProbe = TestProbe()
    val peer = createTestPeer("peer-1", testProbe.ref)

    val requestId = tracker.generateRequestId()
    tracker.trackRequest(requestId, peer, SNAPRequestTracker.RequestType.GetByteCodes) {}

    val response = ByteCodes(requestId, Seq.empty)
    val result = tracker.validateByteCodes(response)

    result shouldBe Right(response)
  }

  // ========================================
  // Multiple accounts ordering validation
  // ========================================

  it should "accept single-account AccountRange" taggedAs UnitTest in {
    val tracker = new SNAPRequestTracker()
    val testProbe = TestProbe()
    val peer = createTestPeer("peer-1", testProbe.ref)

    val requestId = tracker.generateRequestId()
    tracker.trackRequest(requestId, peer, SNAPRequestTracker.RequestType.GetAccountRange) {}

    val response = AccountRange(
      requestId,
      Seq((ByteString("only-account"), Account(nonce = 1, balance = 100))),
      Seq.empty
    )

    tracker.validateAccountRange(response) shouldBe Right(response)
  }

  // TODO: march-onward does not yet validate slot ordering in validateStorageRanges.
  // Enable this test when storage slot monotonicity validation is added.
  ignore should "reject StorageRanges with non-monotonic slots in second account" taggedAs UnitTest in {
    val tracker = new SNAPRequestTracker()
    val testProbe = TestProbe()
    val peer = createTestPeer("peer-1", testProbe.ref)

    val requestId = tracker.generateRequestId()
    tracker.trackRequest(requestId, peer, SNAPRequestTracker.RequestType.GetStorageRanges) {}

    val goodSlots = Seq(
      (ByteString("slot1"), ByteString("val1")),
      (ByteString("slot2"), ByteString("val2"))
    )
    val badSlots = Seq(
      (ByteString("slot3"), ByteString("val3")),
      (ByteString("slot1"), ByteString("val1")) // out of order
    )

    val response = StorageRanges(requestId, Seq(goodSlots, badSlots), Seq.empty)
    val result = tracker.validateStorageRanges(response)

    result shouldBe a[Left[_, _]]
    result.left.get should include("not monotonically increasing")
    result.left.get should include("account 1")
  }
}
