package com.chipprbots.ethereum.network

import java.time.Instant
import scala.concurrent.duration._

import org.scalacheck.Gen
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import com.chipprbots.ethereum.testing.Tags._

/** Multi-step feedback loop tests for PeerScore.
  *
  * PeerScoreSpec covers individual record* methods in isolation. These tests cover the accumulated trajectories that
  * matter for the SNAP sync peer-selection feedback loop: score degrades monotonically on consecutive failures,
  * recovers after successes, and shouldRetry caps at 1 hour regardless of blacklist count.
  */
class PeerScoreFeedbackSpec extends AnyFlatSpec with Matchers with ScalaCheckPropertyChecks {

  // -----------------------------------------------------------------------
  // Score degrades monotonically on consecutive timeouts
  // -----------------------------------------------------------------------

  "PeerScore timeout feedback" should "degrade score monotonically with each timeout" taggedAs
    (UnitTest, NetworkTest) in {
      // A peer that starts neutral and accumulates timeouts — score must strictly decrease each step.
      val initial = PeerScore.initial

      val scores = (1 to 5)
        .scanLeft(initial)((s, _) => s.recordTimeout)
        .map(_.score)

      scores.sliding(2).foreach { case Seq(prev, next) =>
        prev should be >= next // score should not increase on timeout
      }
      scores.last should be < scores.head
    }

  it should "have a lower score after 10 timeouts than after 1 timeout" taggedAs (UnitTest, NetworkTest) in {
    // Establish a baseline with some successes so the response ratio degrades as timeouts accumulate.
    // Pure all-timeout runs (0/1 vs 0/10) produce identical ratios; mixing in successes makes the
    // degradation visible through the response-rate component.
    val base = (1 to 5).foldLeft(PeerScore.initial)((s, _) => s.recordResponse(1024, latencyMs = 50))
    val after1Timeout = base.recordTimeout // ratio: 5/6 ≈ 0.83
    val after10Timeouts = (1 to 10).foldLeft(base)((s, _) => s.recordTimeout) // ratio: 5/15 ≈ 0.33
    after10Timeouts.score should be < after1Timeout.score
  }

  // -----------------------------------------------------------------------
  // Score recovers after successes following failures
  // -----------------------------------------------------------------------

  "PeerScore recovery" should "recover score after successful responses following timeouts" taggedAs
    (UnitTest, NetworkTest) in {
      // Degrade the peer with timeouts, then recover with responses.
      val degraded = (1 to 10).foldLeft(PeerScore.initial)((s, _) => s.recordTimeout)
      val recovered = (1 to 20).foldLeft(degraded)((s, i) => s.recordResponse(1024, latencyMs = 50))

      recovered.score should be > degraded.score
    }

  it should "score higher for a peer with 20 responses after 10 timeouts than 10 timeouts alone" taggedAs
    (UnitTest, NetworkTest) in {
      val base = PeerScore.initial
      val bad = (1 to 10).foldLeft(base)((s, _) => s.recordTimeout)
      val recovering = (1 to 20).foldLeft(bad)((s, _) => s.recordResponse(512, latencyMs = 100))
      recovering.score should be > bad.score
    }

  // -----------------------------------------------------------------------
  // A peer with only successes scores higher than a peer with only timeouts
  // -----------------------------------------------------------------------

  "PeerScore ordering" should "rank a peer with 10 successes above a peer with 10 timeouts" taggedAs
    (UnitTest, NetworkTest) in {
      val goodPeer = (1 to 10).foldLeft(PeerScore.initial)((s, _) => s.recordResponse(1024, 50))
      val badPeer = (1 to 10).foldLeft(PeerScore.initial)((s, _) => s.recordTimeout)
      goodPeer.score should be > badPeer.score
    }

  it should "rank a low-latency peer above an equal-response-rate high-latency peer" taggedAs
    (UnitTest, NetworkTest) in {
      val fast = (1 to 10).foldLeft(PeerScore.initial)((s, _) => s.recordResponse(1024, latencyMs = 10))
      val slow = (1 to 10).foldLeft(PeerScore.initial)((s, _) => s.recordResponse(1024, latencyMs = 1900))
      fast.score should be > slow.score
    }

  // -----------------------------------------------------------------------
  // Score always in [0, 1]
  // -----------------------------------------------------------------------

  "PeerScore.score" should "always be in [0.0, 1.0] regardless of input values" taggedAs
    (UnitTest, NetworkTest) in {
      forAll(
        Gen.choose(0, 1000),
        Gen.choose(0, 1000),
        Gen.choose(0, 1000),
        Gen.choose(0, 1000),
        Gen.choose(0, 100)
      ) { (succ: Int, fail: Int, resp: Int, timeouts: Int, violations: Int) =>
        val s = PeerScore(
          successfulHandshakes = succ,
          failedHandshakes = fail,
          responsesReceived = resp,
          requestsTimedOut = timeouts,
          protocolViolations = violations,
          lastSeen = Some(Instant.now())
        )
        s.score should ((be >= 0.0).and(be <= 1.0))
      }
    }

  // -----------------------------------------------------------------------
  // shouldRetry: exponential backoff caps at 1 hour
  // -----------------------------------------------------------------------

  "PeerScore.shouldRetry" should "cap exponential backoff at 1 hour for high blacklist counts" taggedAs
    (UnitTest, NetworkTest) in {
      // With blacklistCount=100 and base=60s, the raw penalty would be 2^100 * 60s >> 1h.
      // The cap ensures a peer is retryable after at most 1 hour.
      val manyBlacklists = PeerScore(
        blacklistCount = 100,
        lastSeen = Some(Instant.now().minusSeconds(3601))
      )
      // After 1 hour + 1 second, the peer must be retryable regardless of blacklist count
      manyBlacklists.shouldRetry(60.seconds) shouldBe true
    }

  it should "not allow retry for a heavily blacklisted peer seen just now" taggedAs
    (UnitTest, NetworkTest) in {
      val manyBlacklists = PeerScore(
        blacklistCount = 10,
        lastSeen = Some(Instant.now())
      )
      manyBlacklists.shouldRetry(60.seconds) shouldBe false
    }

  // -----------------------------------------------------------------------
  // Latency EMA convergence
  // -----------------------------------------------------------------------

  "PeerScore latency EMA" should "converge toward the recent latency after many responses" taggedAs
    (UnitTest, NetworkTest) in {
      // Start with high-latency history, then receive many low-latency responses.
      // After enough samples, the EMA should be close to the recent low value.
      val highLatency = (1 to 5).foldLeft(PeerScore.initial)((s, _) => s.recordResponse(100, 2000))
      val converged = (1 to 30).foldLeft(highLatency)((s, _) => s.recordResponse(100, 10))
      converged.averageLatencyMs.get should be < 500.0
    }

  it should "weight recent samples more than historical ones (alpha=0.3)" taggedAs
    (UnitTest, NetworkTest) in {
      // First sample establishes baseline
      val s0 = PeerScore.initial.recordResponse(1, 1000)
      // Second sample with very different latency
      val s1 = s0.recordResponse(1, 0)
      // EMA formula: 0.7 * 1000 + 0.3 * 0 = 700
      s1.averageLatencyMs.get shouldBe 700.0 +- 1.0
    }
}
