package com.chipprbots.ethereum.network

import java.time.Instant
import scala.concurrent.duration._

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags._

class PeerScoreSpec extends AnyFlatSpec with Matchers {

  "PeerScore" should "start with neutral score for empty metrics" taggedAs (UnitTest, NetworkTest) in {
    val score = PeerScore.empty
    // Empty score has neutral values (0.5) for most metrics except recency (0.0)
    // (0.5 * 0.3) + (0.5 * 0.25) + (0.5 * 0.2) + (1.0 * 0.15) + (0.0 * 0.1) = 0.525
    score.score shouldBe 0.525 +- 0.01
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
      lastSeen = Some(Instant.now())
    )
    score.score should be > 0.8
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
      lastSeen = Some(Instant.now().minusSeconds(7200))
    )
    score.score should be < 0.3
  }

  it should "record successful handshake" in {
    val initial = PeerScore.empty
    val updated = initial.recordSuccessfulHandshake

    updated.successfulHandshakes shouldBe 1
    updated.firstSeen should be(defined)
    updated.lastSeen should be(defined)
  }

  it should "record failed handshake" in {
    val initial = PeerScore.empty
    val updated = initial.recordFailedHandshake

    updated.failedHandshakes shouldBe 1
  }

  it should "record response with latency tracking" in {
    val initial = PeerScore.empty
    val updated = initial.recordResponse(bytes = 1024, latencyMs = 100)

    updated.responsesReceived shouldBe 1
    updated.bytesDownloaded shouldBe 1024
    updated.averageLatencyMs shouldBe Some(100.0)
  }

  it should "update average latency with exponential moving average" in {
    val score1 = PeerScore.empty.recordResponse(bytes = 100, latencyMs = 100)
    val score2 = score1.recordResponse(bytes = 100, latencyMs = 200)

    score2.averageLatencyMs.get should be > 100.0
    score2.averageLatencyMs.get should be < 200.0
  }

  it should "record timeout" in {
    val initial = PeerScore.empty
    val updated = initial.recordTimeout

    updated.requestsTimedOut shouldBe 1
  }

  it should "record protocol violation" in {
    val initial = PeerScore.empty
    val updated = initial.recordProtocolViolation

    updated.protocolViolations shouldBe 1
  }

  it should "record blacklist" in {
    val initial = PeerScore.empty
    val updated = initial.recordBlacklist

    updated.blacklistCount shouldBe 1
  }

  it should "allow retry after sufficient time for non-blacklisted peer" in {
    val score = PeerScore.empty
    score.shouldRetry(60.seconds) shouldBe true
  }

  it should "prevent retry before blacklist duration expires" in {
    val score = PeerScore(
      blacklistCount = 1,
      lastSeen = Some(Instant.now())
    )
    score.shouldRetry(60.seconds) shouldBe false
  }

  it should "allow retry after blacklist duration expires" in {
    val score = PeerScore(
      blacklistCount = 1,
      lastSeen = Some(Instant.now().minusSeconds(120))
    )
    score.shouldRetry(60.seconds) shouldBe true
  }

  it should "apply exponential penalty for multiple blacklists" in {
    val score1 = PeerScore(blacklistCount = 1, lastSeen = Some(Instant.now().minusSeconds(60)))
    val score2 = PeerScore(blacklistCount = 2, lastSeen = Some(Instant.now().minusSeconds(60)))
    val score3 = PeerScore(blacklistCount = 3, lastSeen = Some(Instant.now().minusSeconds(60)))

    // With 60s base duration:
    // blacklistCount=1: needs 120s (2^1 * 60)
    // blacklistCount=2: needs 240s (2^2 * 60)
    // blacklistCount=3: needs 480s (2^3 * 60)

    score1.shouldRetry(60.seconds) shouldBe false // Only 60s passed, needs 120s
    score2.shouldRetry(60.seconds) shouldBe false // Only 60s passed, needs 240s
    score3.shouldRetry(60.seconds) shouldBe false // Only 60s passed, needs 480s
  }

  it should "calculate handshake score correctly" in {
    val good = PeerScore(successfulHandshakes = 9, failedHandshakes = 1)
    val bad = PeerScore(successfulHandshakes = 1, failedHandshakes = 9)

    good.score should be > bad.score
  }

  it should "calculate response score correctly" in {
    val good = PeerScore(responsesReceived = 90, requestsTimedOut = 10)
    val bad = PeerScore(responsesReceived = 10, requestsTimedOut = 90)

    good.score should be > bad.score
  }

  it should "calculate latency score correctly" in {
    val fast = PeerScore(averageLatencyMs = Some(100.0))
    val slow = PeerScore(averageLatencyMs = Some(2000.0))

    fast.score should be > slow.score
  }

  it should "penalize protocol violations" in {
    val clean = PeerScore(responsesReceived = 100, protocolViolations = 0)
    val violator = PeerScore(responsesReceived = 100, protocolViolations = 50)

    clean.score should be > violator.score
  }

  it should "favor recently seen peers" in {
    val recent = PeerScore(lastSeen = Some(Instant.now()))
    val old = PeerScore(lastSeen = Some(Instant.now().minusSeconds(3600)))

    recent.score should be > old.score
  }
}
