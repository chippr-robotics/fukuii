package com.chipprbots.ethereum.blockchain.sync

import org.scalacheck.Gen
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import com.chipprbots.ethereum.testing.Tags._

/** Unit tests for PeerRateTracker (ARCH-001).
  *
  * Verifies EMA-smoothed capacity tracking for ETH message types (ordinals 4–6). All assertions are deterministic; no
  * actor choreography.
  */
class PeerRateTrackerSpec extends AnyFlatSpec with Matchers with ScalaCheckPropertyChecks {

  import PeerRateTracker._

  "PeerRateTracker" should "return capacity floor before any measurement" taggedAs UnitTest in {
    val tracker = new PeerRateTracker()
    tracker.capacity("p1", MsgGetBlockHeaders, 2000L) shouldBe 1
  }

  it should "increase capacity above floor after a measured update" taggedAs UnitTest in {
    // measured = 3 / 0.050 = 60/s, ema = 0.1 × 60 = 6.0
    // capacity = (1 + 1.01 × 6.0 × 2.0).toInt = 13
    val tracker = new PeerRateTracker()
    tracker.update("p1", MsgGetBlockHeaders, 50L, 3)
    tracker.capacity("p1", MsgGetBlockHeaders, 2000L) shouldBe 13
  }

  it should "not crash or produce negative capacity on zero-item update" taggedAs UnitTest in {
    val tracker = new PeerRateTracker()
    tracker.update("p1", MsgGetBlockHeaders, 50L, 0)
    tracker.capacity("p1", MsgGetBlockHeaders, 2000L) shouldBe 1
  }

  it should "track ETH message types independently per peer" taggedAs UnitTest in {
    val tracker = new PeerRateTracker()
    tracker.update("p1", MsgGetBlockHeaders, 50L, 3)
    tracker.capacity("p1", MsgGetBlockHeaders, 2000L) should be > 1
    tracker.capacity("p1", MsgGetBlockBodies, 2000L) shouldBe 1
  }

  it should "change medianRTT from initial value after tune()" taggedAs UnitTest in {
    val tracker = new PeerRateTracker()
    tracker.addPeer("p1")
    val initial = tracker.currentMedianRTT
    tracker.update("p1", MsgGetBlockHeaders, 1000L, 5)
    tracker.tune()
    (tracker.currentMedianRTT should not).equal(initial)
  }

  it should "increase confidence toward 1.0 after repeated tune() calls" taggedAs UnitTest in {
    val tracker = new PeerRateTracker()
    tracker.addPeer("p1")
    tracker.addPeer("p2") // 2 peers → confidence detunes to 0.5
    for (_ <- 1 to 10) {
      tracker.update("p1", MsgGetBlockHeaders, 2000L, 10)
      tracker.update("p2", MsgGetBlockHeaders, 2000L, 10)
      tracker.tune()
    }
    tracker.currentConfidence should be > 0.5
  }

  it should "return capacity floor immediately after addPeer" taggedAs UnitTest in {
    val tracker = new PeerRateTracker()
    tracker.addPeer("p1")
    tracker.capacity("p1", MsgGetBlockHeaders, 2000L) shouldBe 1
  }

  it should "clear peer state on removePeer" taggedAs UnitTest in {
    val tracker = new PeerRateTracker()
    tracker.addPeer("p1")
    tracker.update("p1", MsgGetBlockHeaders, 50L, 3)
    tracker.removePeer("p1")
    tracker.peerCount shouldBe 0
  }

  it should "converge capacity within 50% of expected after 20 identical updates" taggedAs UnitTest in {
    forAll(Gen.choose(1, 100), Gen.choose(10, 5000)) { (items: Int, elapsed: Int) =>
      val tracker = new PeerRateTracker()
      for (_ <- 1 to 20)
        tracker.update("p1", MsgGetBlockHeaders, elapsed.toLong, items)
      val targetRttMs = 10000L
      val got = tracker.capacity("p1", MsgGetBlockHeaders, targetRttMs)
      val throughput = items.toDouble / (elapsed.toDouble / 1000.0)
      val expected = (1 + CapacityOverestimation * throughput * targetRttMs / 1000.0).toInt.max(1)
      got should be >= 1
      got.toDouble should be >= expected.toDouble * 0.5
      got.toDouble should be <= expected.toDouble * 1.5 + 2
    }
  }
}
