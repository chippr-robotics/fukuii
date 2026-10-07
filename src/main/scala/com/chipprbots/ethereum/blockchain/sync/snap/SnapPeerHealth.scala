package com.chipprbots.ethereum.blockchain.sync.snap

import scala.collection.mutable
import scala.concurrent.duration.*

/** Per-peer liveness score for the SNAP request coordinators: does this peer answer, or does every request we send it
  * burn a full request-timeout slot?
  *
  * Why it exists (Sepolia v0.9.0, 2026-10-07): peer `aedda9` received 386 `GetStorageRanges`, answered none and timed
  * out 370 times. The coordinator's only reaction to a timeout was a flat 5 s cooldown, and the eligible-set floor
  * ("all servable peers were cooling — reviving") picked it again 230 times, each pick parking two or three requests
  * for the 30 s timeout. A cooldown is the right response to a transient hiccup; it is the wrong response to a peer
  * that never answers.
  *
  * Policy:
  *   - Timeouts are counted per peer; any answered request (success) resets the count.
  *   - At `timeoutThreshold` consecutive timeouts with no intervening success the peer is *demoted*: it is penalised
  *     for `basePenalty`, doubling with each further demotion up to `maxPenalty`.
  *   - When the penalty lapses the peer is on *probation*: callers give it a single request slot. One more timeout
  *     re-penalises it immediately (doubled); one success clears the penalty, steps the level down by one and ends
  *     probation.
  *   - Empty responses are deliberately neutral here: an empty answer proves the peer is alive, and "alive but cannot
  *     serve this root" is already handled by the coordinators' stateless strike counting.
  *
  * Keys are `PeerId.value`, which is the node-ID hex for a handshaked peer, so the score survives a reconnect — a dead
  * peer that drops and re-dials does not get a clean slate. The map is bounded: peers that recover to level 0 are
  * removed and the least recently touched entry is evicted past `maxTracked`.
  *
  * Not thread-safe: owned by a single actor.
  */
final class SnapPeerHealth(
    val timeoutThreshold: Int = SnapPeerHealth.DefaultTimeoutThreshold,
    val basePenalty: FiniteDuration = SnapPeerHealth.DefaultBasePenalty,
    val maxPenalty: FiniteDuration = SnapPeerHealth.DefaultMaxPenalty,
    val maxTracked: Int = SnapPeerHealth.DefaultMaxTracked
):
  require(timeoutThreshold >= 1, s"timeoutThreshold must be >= 1, got $timeoutThreshold")
  require(basePenalty > Duration.Zero && maxPenalty >= basePenalty, s"invalid penalty bounds $basePenalty..$maxPenalty")
  require(maxTracked >= 1, s"maxTracked must be >= 1, got $maxTracked")

  import SnapPeerHealth.*

  // Insertion-ordered so the least recently touched entry is evicted first when the map exceeds maxTracked.
  private val states = mutable.LinkedHashMap.empty[String, PeerState]

  /** Record an answered request. Returns `Some(level)` when this success recovers a demoted peer (for the caller's
    * recovery log line), `None` otherwise.
    */
  def recordSuccess(peerId: String): Option[Int] =
    states.get(peerId) match
      case None => None
      case Some(s) =>
        val recovered = if s.level > 0 then Some(s.level) else None
        val next = s.copy(consecutiveTimeouts = 0, level = (s.level - 1).max(0), penaltyUntilMs = 0L, probation = false)
        if next.level == 0 then states.remove(peerId) else states.update(peerId, next)
        recovered

  /** Record a request timeout. Returns `Some(penalty)` when this timeout demotes the peer (newly, or again after
    * probation), `None` while it is still below the threshold or already serving a penalty.
    */
  def recordTimeout(peerId: String, nowMs: Long): Option[FiniteDuration] =
    val s = states.getOrElse(peerId, PeerState.Fresh)
    val timeouts = s.consecutiveTimeouts + 1
    val alreadyPenalised = s.penaltyUntilMs > nowMs
    val demote = !alreadyPenalised && (timeouts >= timeoutThreshold || s.probation)
    val next =
      if demote then
        val level = s.level + 1
        s.copy(
          consecutiveTimeouts = timeouts,
          level = level,
          penaltyUntilMs = nowMs + penaltyFor(level).toMillis,
          probation = true
        )
      else s.copy(consecutiveTimeouts = timeouts)
    states.remove(peerId) // re-insert at the tail: the most recently touched entry is evicted last
    states.update(peerId, next)
    evictIfOversized()
    if demote then Some(penaltyFor(next.level)) else None

  /** The peer is serving a demotion penalty and must not be selected (normal dispatch or the cooldown floor). */
  def isPenalised(peerId: String, nowMs: Long): Boolean =
    states.get(peerId).exists(_.penaltyUntilMs > nowMs)

  /** The peer has been demoted and has not answered since. Callers cap it at one in-flight request. */
  def isOnProbation(peerId: String): Boolean =
    states.get(peerId).exists(_.probation)

  /** Epoch-ms at which the peer's penalty lapses, or 0 if it has none. */
  def penaltyUntilMs(peerId: String): Long =
    states.get(peerId).map(_.penaltyUntilMs).getOrElse(0L)

  def level(peerId: String): Int = states.get(peerId).map(_.level).getOrElse(0)

  def consecutiveTimeouts(peerId: String): Int =
    states.get(peerId).map(_.consecutiveTimeouts).getOrElse(0)

  def penalisedCount(nowMs: Long): Int = states.valuesIterator.count(_.penaltyUntilMs > nowMs)

  def trackedCount: Int = states.size

  private[snap] def penaltyFor(level: Int): FiniteDuration =
    // basePenalty × 2^(level-1), capped at maxPenalty. The shift is clamped so it cannot overflow.
    val factor = 1L << (level - 1).max(0).min(20)
    (basePenalty.toMillis * factor).min(maxPenalty.toMillis).millis

  private def evictIfOversized(): Unit =
    while states.size > maxTracked do states.remove(states.head._1)

object SnapPeerHealth:
  val DefaultTimeoutThreshold: Int = 3
  val DefaultBasePenalty: FiniteDuration = 2.minutes
  val DefaultMaxPenalty: FiniteDuration = 30.minutes
  val DefaultMaxTracked: Int = 1024

  final private case class PeerState(consecutiveTimeouts: Int, level: Int, penaltyUntilMs: Long, probation: Boolean)
  private object PeerState:
    val Fresh: PeerState = PeerState(0, 0, 0L, probation = false)
