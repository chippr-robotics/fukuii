package com.chipprbots.ethereum.network

import java.time.Instant

import scala.concurrent.duration.*

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*

class PeerScoreSpec extends AnyFlatSpec with Matchers:

  private val now: Instant = Instant.parse("2026-11-09T12:00:00Z")

  "PeerScore" should "start with neutral score for empty metrics" taggedAs (UnitTest, NetworkTest) in {
    val score = PeerScore.empty
    // Empty score has neutral values (0.5) for most metrics except recency (0.0)
    // (0.5 * 0.3) + (0.5 * 0.25) + (0.5 * 0.2) + (1.0 * 0.15) + (0.0 * 0.1) = 0.525
    score.score(now) shouldBe 0.525 +- 0.01
  }

  it should "have perfect score with all successful operations" taggedAs (UnitTest, NetworkTest) in {
    val score = PeerScore(
      successfulHandshakes = 10,
      failedHandshakes = 0,
      responsesReceived = 100,
      requestsTimedOut = 0,
      averageLatencyMs = Some(50.0),
      protocolViolations = 0,
      blacklistCount = 0,
      lastSeen = Some(now)
    )
    score.score(now) should be > 0.8
  }

  it should "have low score with many failures" taggedAs (UnitTest, NetworkTest) in {
    val score = PeerScore(
      successfulHandshakes = 1,
      failedHandshakes = 10,
      responsesReceived = 10,
      requestsTimedOut = 100,
      averageLatencyMs = Some(3000.0),
      protocolViolations = 20,
      blacklistCount = 5,
      lastSeen = Some(now.minusSeconds(7200))
    )
    score.score(now) should be < 0.3
  }

  it should "record successful handshake" in {
    val initial = PeerScore.empty
    val updated = initial.recordSuccessfulHandshake(now)

    updated.successfulHandshakes shouldBe 1
    updated.firstSeen should be(defined)
    updated.lastSeen should be(defined)
  }

  it should "record failed handshake" in {
    val initial = PeerScore.empty
    val updated = initial.recordFailedHandshake(now)

    updated.failedHandshakes shouldBe 1
  }

  it should "record response with latency tracking" in {
    val initial = PeerScore.empty
    val updated = initial.recordResponse(bytes = 1024, latencyMs = 100, now = now)

    updated.responsesReceived shouldBe 1
    updated.bytesDownloaded shouldBe 1024
    updated.averageLatencyMs shouldBe Some(100.0)
  }

  it should "update average latency with exponential moving average" in {
    val score1 = PeerScore.empty.recordResponse(bytes = 100, latencyMs = 100, now = now)
    val score2 = score1.recordResponse(bytes = 100, latencyMs = 200, now = now)

    score2.averageLatencyMs.get should be > 100.0
    score2.averageLatencyMs.get should be < 200.0
  }

  it should "record timeout" in {
    val initial = PeerScore.empty
    val updated = initial.recordTimeout(now)

    updated.requestsTimedOut shouldBe 1
  }

  it should "record protocol violation" in {
    val initial = PeerScore.empty
    val updated = initial.recordProtocolViolation(now)

    updated.protocolViolations shouldBe 1
  }

  it should "record blacklist" in {
    val initial = PeerScore.empty
    val updated = initial.recordBlacklist(now)

    updated.blacklistCount shouldBe 1
  }

  it should "allow retry after sufficient time for non-blacklisted peer" in {
    val score = PeerScore.empty
    score.shouldRetry(60.seconds, now) shouldBe true
  }

  it should "prevent retry before blacklist duration expires" in {
    val score = PeerScore(
      blacklistCount = 1,
      lastSeen = Some(now)
    )
    score.shouldRetry(60.seconds, now) shouldBe false
  }

  it should "allow retry after blacklist duration expires" in {
    val score = PeerScore(
      blacklistCount = 1,
      lastSeen = Some(now.minusSeconds(120))
    )
    score.shouldRetry(60.seconds, now) shouldBe true
  }

  it should "apply exponential penalty for multiple blacklists" in {
    val score1 = PeerScore(blacklistCount = 1, lastSeen = Some(now.minusSeconds(60)))
    val score2 = PeerScore(blacklistCount = 2, lastSeen = Some(now.minusSeconds(60)))
    val score3 = PeerScore(blacklistCount = 3, lastSeen = Some(now.minusSeconds(60)))

    // With 60s base duration:
    // blacklistCount=1: needs 120s (2^1 * 60)
    // blacklistCount=2: needs 240s (2^2 * 60)
    // blacklistCount=3: needs 480s (2^3 * 60)

    score1.shouldRetry(60.seconds, now) shouldBe false // Only 60s passed, needs 120s
    score2.shouldRetry(60.seconds, now) shouldBe false // Only 60s passed, needs 240s
    score3.shouldRetry(60.seconds, now) shouldBe false // Only 60s passed, needs 480s
  }

  it should "calculate handshake score correctly" in {
    val good = PeerScore(successfulHandshakes = 9, failedHandshakes = 1)
    val bad = PeerScore(successfulHandshakes = 1, failedHandshakes = 9)

    good.score(now) should be > bad.score(now)
  }

  it should "calculate response score correctly" in {
    val good = PeerScore(responsesReceived = 90, requestsTimedOut = 10)
    val bad = PeerScore(responsesReceived = 10, requestsTimedOut = 90)

    good.score(now) should be > bad.score(now)
  }

  it should "calculate latency score correctly" in {
    val fast = PeerScore(averageLatencyMs = Some(100.0))
    val slow = PeerScore(averageLatencyMs = Some(2000.0))

    fast.score(now) should be > slow.score(now)
  }

  it should "penalize protocol violations" in {
    val clean = PeerScore(responsesReceived = 100, protocolViolations = 0)
    val violator = PeerScore(responsesReceived = 100, protocolViolations = 50)

    clean.score(now) should be > violator.score(now)
  }

  it should "favor recently seen peers" in {
    val recent = PeerScore(lastSeen = Some(now))
    val old = PeerScore(lastSeen = Some(now.minusSeconds(3600)))

    recent.score(now) should be > old.score(now)
  }

  // ---- spec 027 slice 4a: what wiring the scorer to live events needs it to guarantee -------------------------

  it should "refresh lastSeen only on a successful handshake and on a response" taggedAs (UnitTest, NetworkTest) in {
    val earlier = now.minusSeconds(600)
    val base = PeerScore(lastSeen = Some(earlier))

    base.recordSuccessfulHandshake(now).lastSeen shouldBe Some(now)
    base.recordResponse(bytes = 1, latencyMs = 1, now = now).lastSeen shouldBe Some(now)

    base.recordFailedHandshake(now).lastSeen shouldBe Some(earlier)
    base.recordTimeout(now).lastSeen shouldBe Some(earlier)
    base.recordProtocolViolation(now).lastSeen shouldBe Some(earlier)
    base.recordBlacklist(now).lastSeen shouldBe Some(earlier)
  }

  it should "note the first time a peer was heard of by any event, and keep it" taggedAs (UnitTest, NetworkTest) in {
    val later = now.plusSeconds(60)
    PeerScore.empty.recordTimeout(now).firstSeen shouldBe Some(now)
    PeerScore.empty.recordBlacklist(now).recordResponse(1, 1, later).firstSeen shouldBe Some(now)
  }

  it should "decay the recency term linearly by the minute, from 1.0 now to 0.0 an hour ago" taggedAs
    (UnitTest, NetworkTest) in {
      def scoreSeen(minutesAgo: Long): Double = PeerScore(lastSeen = Some(now.minusSeconds(minutesAgo * 60))).score(now)
      val never = PeerScore.empty.score(now)

      (scoreSeen(0) - never) shouldBe 0.1 +- 1e-9
      (scoreSeen(30) - never) shouldBe 0.05 +- 1e-9
      (scoreSeen(60) - never) shouldBe 0.0 +- 1e-9
      scoreSeen(600) shouldBe never +- 1e-9
    }

  it should "count a lastSeen after now as now, so the score stays within 0.0 to 1.0" taggedAs
    (UnitTest, NetworkTest) in {
      val ahead = PeerScore(lastSeen = Some(now.plusSeconds(3600)))
      val justNow = PeerScore(lastSeen = Some(now))
      ahead.score(now) shouldBe justNow.score(now) +- 1e-9
    }

  it should "lower the compliance term for a blacklisting, as it does for a protocol violation" taggedAs
    (UnitTest, NetworkTest) in {
      val clean = PeerScore(responsesReceived = 10, lastSeen = Some(now))
      val blacklisted = clean.copy(blacklistCount = 2)
      val violator = clean.copy(protocolViolations = 2)
      blacklisted.score(now) should be < clean.score(now)
      blacklisted.score(now) shouldBe violator.score(now) +- 1e-9
    }

  it should "not let timeouts dilute the violation rate" taggedAs (UnitTest, NetworkTest) in {
    val violator = PeerScore(responsesReceived = 2, protocolViolations = 1, lastSeen = Some(now))
    val withTimeout = violator.recordTimeout(now)
    withTimeout.score(now) should be <= violator.score(now)
  }

  it should "time shouldRetry from the last blacklisting, not from when the peer was last seen" taggedAs
    (UnitTest, NetworkTest) in {
      val seenLongAgo = PeerScore(lastSeen = Some(now.minusSeconds(7200)))
      val blacklisted = seenLongAgo.recordBlacklist(now)
      blacklisted.shouldRetry(60.seconds, now) shouldBe false
      blacklisted.shouldRetry(60.seconds, now.plusSeconds(119)) shouldBe false
      blacklisted.shouldRetry(60.seconds, now.plusSeconds(120)) shouldBe true
    }
