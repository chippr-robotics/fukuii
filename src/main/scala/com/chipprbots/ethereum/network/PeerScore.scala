package com.chipprbots.ethereum.network

import java.time.Duration as JDuration
import java.time.Instant

import scala.concurrent.duration.*

/** Peer scoring system.
  *
  * Tracks peer reliability metrics. Based on investigation report recommendations (Priority 1). Spec 027 slice 4a wires
  * it into the peer events as an observer: nothing that dials, selects, blacklists or disconnects reads a score.
  *
  * Every time is passed in (`now`), so a score is a pure function of the history and the instant it is read.
  *
  * Only events that show the peer alive and well ([[recordSuccessfulHandshake]], [[recordResponse]]) refresh
  * `lastSeen`. A failed handshake, a timeout, a protocol violation and a blacklisting never raise a score: each only
  * increments a counter that lowers a term, or leaves the score as it was.
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
    firstSeen: Option[Instant] = None,
    lastBlacklisted: Option[Instant] = None
):

  /** Calculate composite score (0.0 to 1.0, higher is better) at `now`.
    *
    * Factors:
    *   - Handshake success rate (30%)
    *   - Response rate (25%)
    *   - Latency (20%)
    *   - Protocol compliance (15%)
    *   - Recency (10%)
    */
  def score(now: Instant): Double =
    val handshakeScore = calculateHandshakeScore
    val responseScore = calculateResponseScore
    val latencyScore = calculateLatencyScore
    val complianceScore = calculateComplianceScore
    val recencyScore = calculateRecencyScore(now)

    (handshakeScore * 0.3) +
      (responseScore * 0.25) +
      (latencyScore * 0.2) +
      (complianceScore * 0.15) +
      (recencyScore * 0.1)

  private def calculateHandshakeScore: Double =
    val total = successfulHandshakes + failedHandshakes
    if total == 0 then 0.5 // neutral score for new peers
    else successfulHandshakes.toDouble / total

  private def calculateResponseScore: Double =
    val total = responsesReceived + requestsTimedOut
    if total == 0 then 0.5 // neutral score for new peers
    else responsesReceived.toDouble / total

  private def calculateLatencyScore: Double =
    averageLatencyMs match
      case None          => 0.5 // neutral score if no data
      case Some(latency) =>
        // Score decreases with latency: 0ms=1.0, 1000ms=0.5, >2000ms=0.0
        math.max(0.0, 1.0 - (latency / 2000.0))

  /** Violations and blacklistings per response received (plus one, to avoid a division by zero), doubled and taken from
    * 1.0. A blacklisting counts like a violation: the peer misbehaved badly enough for us to exclude it. The divisor
    * ignores timeouts on purpose: counting them made every extra timeout dilute the violation rate, so a timeout could
    * raise the score.
    */
  private def calculateComplianceScore: Double =
    val violationRate = (protocolViolations + blacklistCount).toDouble / (responsesReceived + 1)
    math.max(0.0, 1.0 - (violationRate * 2.0)) // penalty for violations

  /** Decays linearly, by whole minutes, from 1.0 when the peer was last seen at `now` to 0.0 an hour or more earlier
    * (30 minutes = 0.5). A `lastSeen` after `now` (a clock stepped back) counts as `now`.
    */
  private def calculateRecencyScore(now: Instant): Double =
    lastSeen match
      case None => 0.0 // never seen
      case Some(timestamp) =>
        val ageMinutes = JDuration.between(timestamp, now).toMinutes
        math.min(1.0, math.max(0.0, 1.0 - (ageMinutes / 60.0)))

  private def seenFirst(now: Instant): Option[Instant] = firstSeen.orElse(Some(now))

  /** Update score after successful handshake */
  def recordSuccessfulHandshake(now: Instant): PeerScore =
    copy(
      successfulHandshakes = successfulHandshakes + 1,
      lastSeen = Some(now),
      firstSeen = seenFirst(now)
    )

  /** Update score after failed handshake */
  def recordFailedHandshake(now: Instant): PeerScore =
    copy(
      failedHandshakes = failedHandshakes + 1,
      firstSeen = seenFirst(now)
    )

  /** Update score after successful response */
  def recordResponse(bytes: Long, latencyMs: Long, now: Instant): PeerScore =
    val newAverageLatency = averageLatencyMs match
      case None      => latencyMs.toDouble
      case Some(avg) =>
        // Exponential moving average with alpha=0.3
        (avg * 0.7) + (latencyMs * 0.3)
    copy(
      responsesReceived = responsesReceived + 1,
      bytesDownloaded = bytesDownloaded + bytes,
      averageLatencyMs = Some(newAverageLatency),
      lastSeen = Some(now),
      firstSeen = seenFirst(now)
    )

  /** Update score after request timeout */
  def recordTimeout(now: Instant): PeerScore =
    copy(
      requestsTimedOut = requestsTimedOut + 1,
      firstSeen = seenFirst(now)
    )

  /** Update score after protocol violation */
  def recordProtocolViolation(now: Instant): PeerScore =
    copy(
      protocolViolations = protocolViolations + 1,
      firstSeen = seenFirst(now)
    )

  /** Update score after blacklist event. `lastSeen` is not refreshed (that would raise the recency term), so the time
    * of the blacklisting is kept apart, in `lastBlacklisted`, for [[shouldRetry]].
    */
  def recordBlacklist(now: Instant): PeerScore =
    copy(
      blacklistCount = blacklistCount + 1,
      lastBlacklisted = Some(now),
      firstSeen = seenFirst(now)
    )

  /** Check if peer should be retried despite blacklist history. Not used by any decision (spec 027 non-goal). */
  def shouldRetry(currentBlacklistDuration: FiniteDuration, now: Instant): Boolean =
    // Exponential penalty based on blacklist count, capped at 1 hour.
    // Cap the exponent first to avoid Int/Long overflow: 2^30 ≈ 1e9 seconds >> 1h already.
    val maxBackoff = 3600.seconds
    val safeExponent = math.min(blacklistCount, 30)
    val penaltyFactor = math.pow(2.0, safeExponent.toDouble)
    val adjustedMs = math.min(currentBlacklistDuration.toMillis * penaltyFactor, maxBackoff.toMillis.toDouble).toLong

    // The time of the last blacklisting; for a score built without one, the last time the peer was seen.
    lastBlacklisted.orElse(lastSeen) match
      case None => true // never blacklisted, can retry
      case Some(timestamp) =>
        val elapsed = JDuration.between(timestamp, now)
        elapsed.toMillis >= adjustedMs

object PeerScore:
  val empty: PeerScore = PeerScore()

  /** Create initial score for newly discovered peer */
  def initial(now: Instant): PeerScore = PeerScore(
    firstSeen = Some(now),
    lastSeen = Some(now)
  )
