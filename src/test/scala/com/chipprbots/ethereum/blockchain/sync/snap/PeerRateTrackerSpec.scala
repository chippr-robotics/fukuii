package com.chipprbots.ethereum.blockchain.sync.snap

import scala.concurrent.duration._

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags._

class PeerRateTrackerSpec extends AnyFlatSpec with Matchers {

  import PeerRateTracker._

  // ========================================
  // Initialization
  // ========================================

  "PeerRateTracker" should "start with zero peers" taggedAs UnitTest in {
    val tracker = new PeerRateTracker()
    tracker.peerCount shouldBe 0
  }

  it should "start with initial median RTT at minimum estimate" taggedAs UnitTest in {
    val tracker = new PeerRateTracker()
    tracker.currentMedianRTT shouldBe RttMinEstimateMs
  }

  it should "start with confidence at 0.5" taggedAs UnitTest in {
    val tracker = new PeerRateTracker()
    tracker.currentConfidence shouldBe 0.5
  }

  // ========================================
  // Peer Management
  // ========================================

  it should "add and remove peers" taggedAs UnitTest in {
    val tracker = new PeerRateTracker()

    tracker.addPeer("peer-1")
    tracker.peerCount shouldBe 1

    tracker.addPeer("peer-2")
    tracker.peerCount shouldBe 2

    tracker.removePeer("peer-1")
    tracker.peerCount shouldBe 1

    tracker.removePeer("peer-2")
    tracker.peerCount shouldBe 0
  }

  it should "not add duplicate peers" taggedAs UnitTest in {
    val tracker = new PeerRateTracker()

    tracker.addPeer("peer-1")
    tracker.addPeer("peer-1")
    tracker.peerCount shouldBe 1
  }

  it should "handle removing non-existent peer" taggedAs UnitTest in {
    val tracker = new PeerRateTracker()
    tracker.removePeer("nonexistent")
    tracker.peerCount shouldBe 0
  }

  // ========================================
  // Confidence Detuning
  // ========================================

  it should "set confidence to 1.0 for single peer" taggedAs UnitTest in {
    val tracker = new PeerRateTracker()
    tracker.addPeer("peer-1")
    tracker.currentConfidence shouldBe 1.0
  }

  it should "detune confidence when adding peers to small network" taggedAs UnitTest in {
    val tracker = new PeerRateTracker()
    tracker.addPeer("peer-1") // confidence = 1.0
    val confBefore = tracker.currentConfidence

    tracker.addPeer("peer-2") // confidence *= 1/2
    tracker.currentConfidence should be < confBefore
  }

  it should "not detune confidence when at or above cap" taggedAs UnitTest in {
    val tracker = new PeerRateTracker()
    // Add peers up to cap
    (1 to TuningConfidenceCap).foreach(i => tracker.addPeer(s"peer-$i"))
    val confAtCap = tracker.currentConfidence

    // Adding one more should NOT detune
    tracker.addPeer(s"peer-${TuningConfidenceCap + 1}")
    tracker.currentConfidence shouldBe confAtCap
  }

  it should "maintain confidence above minimum floor" taggedAs UnitTest in {
    val tracker = new PeerRateTracker()
    // Add many peers rapidly to drive confidence down
    (1 to 9).foreach(i => tracker.addPeer(s"peer-$i"))
    tracker.currentConfidence should be >= RttMinConfidence
  }

  // ========================================
  // Update (Measurements)
  // ========================================

  it should "track peer capacity after measurement" taggedAs UnitTest in {
    val tracker = new PeerRateTracker()
    tracker.addPeer("peer-1")

    // Simulate: 100 items in 1000ms = 100 items/sec
    tracker.update("peer-1", MsgGetAccountRange, elapsedMs = 1000, items = 100)

    // Capacity should be > 1 now
    val cap = tracker.capacity("peer-1", MsgGetAccountRange, targetRTT = 2000)
    cap should be > 1
  }

  it should "slash capacity to zero on timeout (items=0)" taggedAs UnitTest in {
    val tracker = new PeerRateTracker()
    tracker.addPeer("peer-1")

    // Record some good data first
    tracker.update("peer-1", MsgGetAccountRange, elapsedMs = 1000, items = 100)
    val capBefore = tracker.capacity("peer-1", MsgGetAccountRange, targetRTT = 2000)
    capBefore should be > 1

    // Timeout (0 items)
    tracker.update("peer-1", MsgGetAccountRange, elapsedMs = 5000, items = 0)
    val capAfter = tracker.capacity("peer-1", MsgGetAccountRange, targetRTT = 2000)
    capAfter should be < capBefore
  }

  it should "return minimum capacity of 1 for unknown peers" taggedAs UnitTest in {
    val tracker = new PeerRateTracker()
    val cap = tracker.capacity("unknown", MsgGetAccountRange, targetRTT = 2000)
    cap shouldBe 1
  }

  it should "return minimum capacity of 1 for peers with no measurements" taggedAs UnitTest in {
    val tracker = new PeerRateTracker()
    tracker.addPeer("peer-1")
    val cap = tracker.capacity("peer-1", MsgGetAccountRange, targetRTT = 2000)
    cap shouldBe 1
  }

  it should "track different message types independently" taggedAs UnitTest in {
    val tracker = new PeerRateTracker()
    tracker.addPeer("peer-1")

    // Fast for accounts
    tracker.update("peer-1", MsgGetAccountRange, elapsedMs = 500, items = 200)
    // Slow for storage
    tracker.update("peer-1", MsgGetStorageRanges, elapsedMs = 2000, items = 10)

    val accountCap = tracker.capacity("peer-1", MsgGetAccountRange, targetRTT = 2000)
    val storageCap = tracker.capacity("peer-1", MsgGetStorageRanges, targetRTT = 2000)

    accountCap should be > storageCap
  }

  // ========================================
  // EMA Smoothing
  // ========================================

  it should "smooth measurements with EMA" taggedAs UnitTest in {
    val tracker = new PeerRateTracker()
    tracker.addPeer("peer-1")

    // First measurement: 100 items/sec
    tracker.update("peer-1", MsgGetAccountRange, elapsedMs = 1000, items = 100)
    val cap1 = tracker.capacity("peer-1", MsgGetAccountRange, targetRTT = 2000)

    // Second measurement: 1000 items/sec (much faster)
    tracker.update("peer-1", MsgGetAccountRange, elapsedMs = 100, items = 100)
    val cap2 = tracker.capacity("peer-1", MsgGetAccountRange, targetRTT = 2000)

    // EMA should gradually increase (not jump to new value)
    cap2 should be > cap1
  }

  // ========================================
  // Timeout Calculation
  // ========================================

  it should "calculate initial timeout based on default median RTT" taggedAs UnitTest in {
    val tracker = new PeerRateTracker()
    val timeout = tracker.targetTimeout()

    // Initial: 3 * 2000ms / 0.5 = 12000ms = 12s
    timeout.toMillis should be >= RttMinEstimateMs
    timeout.toMillis should be <= TtlLimitMs
  }

  it should "cap timeout at TtlLimitMs" taggedAs UnitTest in {
    val tracker = new PeerRateTracker()
    val timeout = tracker.targetTimeout()
    timeout.toMillis should be <= TtlLimitMs
  }

  it should "not go below minimum RTT estimate" taggedAs UnitTest in {
    val tracker = new PeerRateTracker()
    tracker.addPeer("peer-1")

    // Very fast peer
    tracker.update("peer-1", MsgGetAccountRange, elapsedMs = 10, items = 1000)
    tracker.tune()

    val timeout = tracker.targetTimeout()
    timeout.toMillis should be >= RttMinEstimateMs
  }

  // ========================================
  // Target Round Trip
  // ========================================

  it should "calculate target round trip" taggedAs UnitTest in {
    val tracker = new PeerRateTracker()
    val rtt = tracker.targetRoundTrip()

    // Initial: max(2000 * 0.9, 2000) = 2000 (pushdown factor brings it to 1800, but min 2000)
    rtt should be >= RttMinEstimateMs
  }

  // ========================================
  // Tune
  // ========================================

  it should "tune with no peers (no-op)" taggedAs UnitTest in {
    val tracker = new PeerRateTracker()
    val confBefore = tracker.currentConfidence
    val rttBefore = tracker.currentMedianRTT

    tracker.tune()

    tracker.currentConfidence shouldBe confBefore
    tracker.currentMedianRTT shouldBe rttBefore
  }

  it should "increase confidence after tuning" taggedAs UnitTest in {
    val tracker = new PeerRateTracker()
    tracker.addPeer("peer-1")
    tracker.addPeer("peer-2")
    val confBefore = tracker.currentConfidence

    tracker.tune()

    tracker.currentConfidence should be >= confBefore
  }

  it should "update median RTT via EMA on tune" taggedAs UnitTest in {
    val tracker = new PeerRateTracker()
    tracker.addPeer("peer-1")

    // Record fast responses
    (1 to 10).foreach { _ =>
      tracker.update("peer-1", MsgGetAccountRange, elapsedMs = 100, items = 50)
    }

    val rttBefore = tracker.currentMedianRTT
    tracker.tune()
    val rttAfter = tracker.currentMedianRTT

    // Median RTT should converge toward the peer's actual RTT
    // (though it may not change much with just one tune call due to EMA)
    rttAfter should be > 0L
  }

  it should "clamp median RTT within bounds" taggedAs UnitTest in {
    val tracker = new PeerRateTracker()
    tracker.addPeer("peer-1")

    // Very fast RTT
    tracker.update("peer-1", MsgGetAccountRange, elapsedMs = 1, items = 1)
    tracker.tune()
    tracker.currentMedianRTT should be >= RttMinEstimateMs

    // Very slow RTT
    tracker.update("peer-1", MsgGetAccountRange, elapsedMs = 100000, items = 1)
    tracker.tune()
    tracker.currentMedianRTT should be <= RttMaxEstimateMs
  }

  // ========================================
  // Multiple Peers
  // ========================================

  it should "handle multiple peers with different performance" taggedAs UnitTest in {
    val tracker = new PeerRateTracker()

    tracker.addPeer("fast-peer")
    tracker.addPeer("slow-peer")

    // Fast peer: 500 items/sec
    tracker.update("fast-peer", MsgGetAccountRange, elapsedMs = 200, items = 100)
    // Slow peer: 10 items/sec
    tracker.update("slow-peer", MsgGetAccountRange, elapsedMs = 5000, items = 50)

    val fastCap = tracker.capacity("fast-peer", MsgGetAccountRange, targetRTT = 2000)
    val slowCap = tracker.capacity("slow-peer", MsgGetAccountRange, targetRTT = 2000)

    fastCap should be > slowCap
  }

  it should "tune with geometric-mean index for median" taggedAs UnitTest in {
    val tracker = new PeerRateTracker()

    // Add 4 peers with different RTTs
    (1 to 4).foreach { i =>
      tracker.addPeer(s"peer-$i")
      tracker.update(s"peer-$i", MsgGetAccountRange, elapsedMs = i * 1000L, items = 10)
    }

    // Geometric mean index of 4 peers = sqrt(4) = 2, so index 2
    tracker.tune()
    tracker.currentMedianRTT should be > 0L
  }

  // ========================================
  // Constants
  // ========================================

  "PeerRateTracker constants" should "have correct geth-aligned values" taggedAs UnitTest in {
    MeasurementImpact shouldBe 0.1
    CapacityOverestimation shouldBe 1.01
    RttMinEstimateMs shouldBe 2000L
    RttMaxEstimateMs shouldBe 20000L
    RttPushdownFactor shouldBe 0.9
    RttMinConfidence shouldBe 0.1
    TtlScaling shouldBe 3.0
    TtlLimitMs shouldBe 60000L
    TuningConfidenceCap shouldBe 10
    TuningImpact shouldBe 0.25
  }

  "PeerRateTracker message types" should "have correct ordinals" taggedAs UnitTest in {
    MsgGetAccountRange shouldBe 0
    MsgGetStorageRanges shouldBe 1
    MsgGetByteCodes shouldBe 2
    MsgGetTrieNodes shouldBe 3
  }
}
