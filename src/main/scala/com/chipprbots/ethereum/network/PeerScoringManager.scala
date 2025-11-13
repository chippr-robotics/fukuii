package com.chipprbots.ethereum.network

import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.FiniteDuration

import com.chipprbots.ethereum.utils.Logger

/** Manager for peer scoring system
  *
  * Tracks and updates scores for all known peers to enable intelligent peer selection. Thread-safe implementation using
  * concurrent data structures.
  */
class PeerScoringManager extends Logger {
  private val scores = TrieMap.empty[PeerId, PeerScore]

  /** Get current score for a peer */
  def getScore(peerId: PeerId): PeerScore =
    scores.getOrElse(peerId, PeerScore.initial)

  /** Get scores for all peers */
  def getAllScores: Map[PeerId, PeerScore] = scores.toMap

  /** Get best N peers based on score */
  def getBestPeers(count: Int): Seq[PeerId] =
    scores.toSeq
      .sortBy { case (_, score) => -score.score } // negative for descending
      .take(count)
      .map(_._1)

  /** Get best peers excluding blacklisted ones */
  def getBestPeersExcluding(count: Int, blacklisted: Set[PeerId]): Seq[PeerId] =
    scores.toSeq
      .filterNot { case (peerId, _) => blacklisted.contains(peerId) }
      .sortBy { case (_, score) => -score.score }
      .take(count)
      .map(_._1)

  /** Check if peer should be retried despite blacklist */
  def shouldRetry(peerId: PeerId, blacklistDuration: FiniteDuration): Boolean =
    getScore(peerId).shouldRetry(blacklistDuration)

  /** Record successful handshake */
  def recordSuccessfulHandshake(peerId: PeerId): Unit = {
    val currentScore = getScore(peerId)
    val updatedScore = currentScore.recordSuccessfulHandshake
    scores.put(peerId, updatedScore)
    logScoreUpdate(peerId, currentScore, updatedScore, "successful handshake")
  }

  /** Record failed handshake */
  def recordFailedHandshake(peerId: PeerId): Unit = {
    val currentScore = getScore(peerId)
    val updatedScore = currentScore.recordFailedHandshake
    scores.put(peerId, updatedScore)
    logScoreUpdate(peerId, currentScore, updatedScore, "failed handshake")
  }

  /** Record successful response */
  def recordResponse(peerId: PeerId, bytes: Long, latencyMs: Long): Unit = {
    val currentScore = getScore(peerId)
    val updatedScore = currentScore.recordResponse(bytes, latencyMs)
    scores.put(peerId, updatedScore)
    if (log.underlying.isDebugEnabled) {
      logScoreUpdate(peerId, currentScore, updatedScore, s"response (${bytes}B, ${latencyMs}ms)")
    }
  }

  /** Record request timeout */
  def recordTimeout(peerId: PeerId): Unit = {
    val currentScore = getScore(peerId)
    val updatedScore = currentScore.recordTimeout
    scores.put(peerId, updatedScore)
    logScoreUpdate(peerId, currentScore, updatedScore, "timeout")
  }

  /** Record protocol violation */
  def recordProtocolViolation(peerId: PeerId): Unit = {
    val currentScore = getScore(peerId)
    val updatedScore = currentScore.recordProtocolViolation
    scores.put(peerId, updatedScore)
    logScoreUpdate(peerId, currentScore, updatedScore, "protocol violation")
  }

  /** Record blacklist event */
  def recordBlacklist(peerId: PeerId): Unit = {
    val currentScore = getScore(peerId)
    val updatedScore = currentScore.recordBlacklist
    scores.put(peerId, updatedScore)
    logScoreUpdate(peerId, currentScore, updatedScore, "blacklist")
  }

  /** Remove peer from scoring (e.g., after permanent disconnect) */
  def removePeer(peerId: PeerId): Unit = {
    scores.remove(peerId)
    log.debug(s"Removed peer ${peerId.value} from scoring")
  }

  /** Get statistics summary */
  def getStatistics: ScoringStatistics = {
    val allScores = scores.values.toSeq
    val scoreValues = allScores.map(_.score)

    ScoringStatistics(
      totalPeers = allScores.size,
      averageScore = if (scoreValues.nonEmpty) scoreValues.sum / scoreValues.size else 0.0,
      highScoringPeers = allScores.count(_.score >= 0.7),
      mediumScoringPeers = allScores.count(s => s.score >= 0.4 && s.score < 0.7),
      lowScoringPeers = allScores.count(_.score < 0.4)
    )
  }

  private def logScoreUpdate(peerId: PeerId, oldScore: PeerScore, newScore: PeerScore, event: String): Unit = {
    val scoreDiff = newScore.score - oldScore.score
    val direction = if (scoreDiff > 0) "↑" else if (scoreDiff < 0) "↓" else "→"
    log.debug(
      s"Peer ${peerId.value} score ${direction} ${f"${oldScore.score}%.3f"} → ${f"${newScore.score}%.3f"} after $event"
    )
  }
}

/** Statistics about peer scoring */
final case class ScoringStatistics(
    totalPeers: Int,
    averageScore: Double,
    highScoringPeers: Int,
    mediumScoringPeers: Int,
    lowScoringPeers: Int
) {
  override def toString: String =
    s"Scoring: $totalPeers peers, avg=${f"$averageScore%.3f"}, high=$highScoringPeers, med=$mediumScoringPeers, low=$lowScoringPeers"
}
