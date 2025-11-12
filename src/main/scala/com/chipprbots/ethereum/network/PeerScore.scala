package com.chipprbots.ethereum.network

import java.time.Instant
import scala.concurrent.duration._

/** Peer scoring system for enhanced peer selection
  *
  * Tracks peer reliability metrics to prioritize better peers for sync operations.
  * Based on investigation report recommendations (Priority 1).
  */
final case class PeerScore(
    successfulHandshakes: Int = 0,
    failedHandshakes: Int = 0,
    bytesDownloaded: Long = 0,
    bytesUploaded: Long = 0,
    responsesReceived: Int = 0,
    requestsTimedOut: Int = 0,
    averageLatencyMs: Option[Double] = None,
    protocolViolations: Int = 0,
    blacklistCount: Int = 0,
    lastSeen: Option[Instant] = None,
    firstSeen: Option[Instant] = None
) {

  /** Calculate composite score (0.0 to 1.0, higher is better)
    *
    * Factors:
    * - Handshake success rate (30%)
    * - Response rate (25%)
    * - Latency (20%)
    * - Protocol compliance (15%)
    * - Recency (10%)
    */
  def score: Double = {
    val handshakeScore = calculateHandshakeScore
    val responseScore = calculateResponseScore
    val latencyScore = calculateLatencyScore
    val complianceScore = calculateComplianceScore
    val recencyScore = calculateRecencyScore

    (handshakeScore * 0.3) +
      (responseScore * 0.25) +
      (latencyScore * 0.2) +
      (complianceScore * 0.15) +
      (recencyScore * 0.1)
  }

  private def calculateHandshakeScore: Double = {
    val total = successfulHandshakes + failedHandshakes
    if (total == 0) 0.5 // neutral score for new peers
    else successfulHandshakes.toDouble / total
  }

  private def calculateResponseScore: Double = {
    val total = responsesReceived + requestsTimedOut
    if (total == 0) 0.5 // neutral score for new peers
    else responsesReceived.toDouble / total
  }

  private def calculateLatencyScore: Double =
    averageLatencyMs match {
      case None => 0.5 // neutral score if no data
      case Some(latency) =>
        // Score decreases with latency: 0ms=1.0, 1000ms=0.5, >2000ms=0.0
        math.max(0.0, 1.0 - (latency / 2000.0))
    }

  private def calculateComplianceScore: Double = {
    val totalInteractions = responsesReceived + requestsTimedOut + 1 // avoid division by zero
    val violationRate = protocolViolations.toDouble / totalInteractions
    math.max(0.0, 1.0 - (violationRate * 2.0)) // penalty for violations
  }

  private def calculateRecencyScore: Double =
    lastSeen match {
      case None => 0.0 // never seen
      case Some(timestamp) =>
        val ageMinutes = java.time.Duration.between(timestamp, Instant.now()).toMinutes
        // Score decreases with age: <5min=1.0, 30min=0.5, >60min=0.0
        math.max(0.0, 1.0 - (ageMinutes / 60.0))
    }

  /** Update score after successful handshake */
  def recordSuccessfulHandshake: PeerScore =
    copy(
      successfulHandshakes = successfulHandshakes + 1,
      lastSeen = Some(Instant.now()),
      firstSeen = firstSeen.orElse(Some(Instant.now()))
    )

  /** Update score after failed handshake */
  def recordFailedHandshake: PeerScore =
    copy(
      failedHandshakes = failedHandshakes + 1,
      lastSeen = Some(Instant.now()),
      firstSeen = firstSeen.orElse(Some(Instant.now()))
    )

  /** Update score after successful response */
  def recordResponse(bytes: Long, latencyMs: Long): PeerScore = {
    val newAverageLatency = averageLatencyMs match {
      case None    => latencyMs.toDouble
      case Some(avg) =>
        // Exponential moving average with alpha=0.3
        (avg * 0.7) + (latencyMs * 0.3)
    }
    copy(
      responsesReceived = responsesReceived + 1,
      bytesDownloaded = bytesDownloaded + bytes,
      averageLatencyMs = Some(newAverageLatency),
      lastSeen = Some(Instant.now())
    )
  }

  /** Update score after request timeout */
  def recordTimeout: PeerScore =
    copy(
      requestsTimedOut = requestsTimedOut + 1,
      lastSeen = Some(Instant.now())
    )

  /** Update score after protocol violation */
  def recordProtocolViolation: PeerScore =
    copy(
      protocolViolations = protocolViolations + 1,
      lastSeen = Some(Instant.now())
    )

  /** Update score after blacklist event */
  def recordBlacklist: PeerScore =
    copy(
      blacklistCount = blacklistCount + 1,
      lastSeen = Some(Instant.now())
    )

  /** Check if peer should be retried despite blacklist history */
  def shouldRetry(currentBlacklistDuration: FiniteDuration): Boolean = {
    // Reduce retry threshold based on blacklist count
    // More blacklists = longer wait before retry
    val penaltyMultiplier = math.pow(2, blacklistCount.min(5)).toInt
    val adjustedDuration = currentBlacklistDuration * penaltyMultiplier

    lastSeen match {
      case None => true // never blacklisted, can retry
      case Some(timestamp) =>
        val elapsed = java.time.Duration.between(timestamp, Instant.now())
        elapsed.toMillis >= adjustedDuration.toMillis
    }
  }
}

object PeerScore {
  val empty: PeerScore = PeerScore()

  /** Create initial score for newly discovered peer */
  def initial: PeerScore = PeerScore(
    firstSeen = Some(Instant.now()),
    lastSeen = Some(Instant.now())
  )
}
