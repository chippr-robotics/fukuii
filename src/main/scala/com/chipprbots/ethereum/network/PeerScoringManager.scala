package com.chipprbots.ethereum.network

import java.time.Clock
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.FiniteDuration

import com.chipprbots.ethereum.utils.Logger

/** Manager for peer scoring system
  *
  * Tracks a [[PeerScore]] for every peer the node has had dealings with, and publishes the scores to
  * [[NetworkMetrics]]. It observes: `getBestPeers`, `getBestPeersExcluding` and `shouldRetry` exist but nothing calls
  * them, and nothing that dials, selects, blacklists or disconnects reads a score (spec 027 non-goal).
  *
  * Thread safety. Events arrive from several actors and threads (PeerManagerActor, PeerActors, PeersClient,
  * SNAPRequestTracker, the blacklist). An update of a peer already tracked is one `TrieMap.updateWith`, an atomic
  * read-modify-write, so no event is lost. A peer new to the map is admitted under a lock that also evicts, so the map
  * never holds more than `maxPeers` entries. Time is read only from `clock`.
  *
  * @param clock
  *   the only source of time; `Clock.systemUTC()` in production, a fixed or mutable clock in tests
  * @param maxPeers
  *   the most peers tracked; when a new peer would exceed it, the peer with the oldest `lastSeen` (or `firstSeen`, for
  *   a peer never seen alive) is evicted. Every failed handshake of a crawler adds a node id, so the map must be
  *   bounded. The default is a few times the connection limits, so an idle connected peer keeps its entry for hours.
  * @param exporter
  *   receives a [[PeerScoreSnapshot]] on [[publish]] and, throttled to once per `PublishIntervalMs` of `clock`, after
  *   an update. The default exports nothing; NodeBuilder passes `NetworkMetrics.publishPeerScores`.
  */
class PeerScoringManager(
    clock: Clock = Clock.systemUTC(),
    maxPeers: Int = PeerScoringManager.DefaultMaxPeers,
    exporter: PeerScoreSnapshot => Unit = PeerScoringManager.NoExport
) extends Logger:
  require(maxPeers > 0, s"maxPeers must be positive, was $maxPeers")

  import PeerScoringManager.*

  private val scores = TrieMap.empty[PeerId, PeerScore]

  /** `clock.millis()` of the last export, [[NeverPublished]] before the first. */
  private val lastPublishedMs = new AtomicLong(NeverPublished)

  /** Get current score for a peer; the neutral score of a new peer if none is recorded. Does not record it. */
  def getScore(peerId: PeerId): PeerScore =
    scores.getOrElse(peerId, PeerScore.initial(clock.instant()))

  /** Get scores for all peers */
  def getAllScores: Map[PeerId, PeerScore] = scores.toMap

  /** The score of every peer, read at one instant from one consistent view of the map. */
  def snapshot(): PeerScoreSnapshot =
    val now = clock.instant()
    val values = scores.readOnlySnapshot().iterator.map { case (id, s) => id -> s.score(now) }.toMap
    PeerScoreSnapshot(now, values, ScoringStatistics.of(values.values))

  /** Hand a fresh [[PeerScoreSnapshot]] to the exporter now, whatever the throttle says, and restart the throttle. Run
    * on a timer so the recency decay shows without events.
    */
  def publish(): Unit =
    lastPublishedMs.set(clock.millis())
    exporter(snapshot())

  /** Export after an update, at most once per `PublishIntervalMs` of the clock. */
  private def publishIfDue(): Unit =
    val nowMs = clock.millis()
    val last = lastPublishedMs.get()
    if (last == NeverPublished || nowMs - last >= PublishIntervalMs) && lastPublishedMs.compareAndSet(last, nowMs) then
      exporter(snapshot())

  /** Get best N peers based on score. Not used by any decision. */
  def getBestPeers(count: Int): Seq[PeerId] =
    val now = clock.instant()
    scores.toSeq
      .sortBy { case (_, score) => -score.score(now) } // negative for descending
      .take(count)
      .map(_._1)

  /** Get best peers excluding blacklisted ones. Not used by any decision. */
  def getBestPeersExcluding(count: Int, blacklisted: Set[PeerId]): Seq[PeerId] =
    val now = clock.instant()
    scores.toSeq
      .filterNot { case (peerId, _) => blacklisted.contains(peerId) }
      .sortBy { case (_, score) => -score.score(now) }
      .take(count)
      .map(_._1)

  /** Check if peer should be retried despite blacklist. Not used by any decision. */
  def shouldRetry(peerId: PeerId, blacklistDuration: FiniteDuration): Boolean =
    getScore(peerId).shouldRetry(blacklistDuration, clock.instant())

  /** Record successful handshake */
  def recordSuccessfulHandshake(peerId: PeerId): Unit =
    update(peerId, "successful handshake")(_.recordSuccessfulHandshake(_))

  /** Record failed handshake */
  def recordFailedHandshake(peerId: PeerId): Unit =
    update(peerId, "failed handshake")(_.recordFailedHandshake(_))

  /** Record successful response. `bytes` is 0 where the caller does not know the size of the reply; it is not part of
    * the score.
    */
  def recordResponse(peerId: PeerId, bytes: Long, latencyMs: Long): Unit =
    update(peerId, s"response (${bytes}B, ${latencyMs}ms)")(_.recordResponse(bytes, latencyMs, _))

  /** Record request timeout */
  def recordTimeout(peerId: PeerId): Unit =
    update(peerId, "timeout")(_.recordTimeout(_))

  /** Record protocol violation */
  def recordProtocolViolation(peerId: PeerId): Unit =
    update(peerId, "protocol violation")(_.recordProtocolViolation(_))

  /** Record blacklist event */
  def recordBlacklist(peerId: PeerId): Unit =
    update(peerId, "blacklist")(_.recordBlacklist(_))

  /** Remove peer from scoring (e.g., after permanent disconnect). Nothing calls it: a peer's history is kept, for the
    * bounded map to evict.
    */
  def removePeer(peerId: PeerId): Unit =
    scores.remove(peerId)
    log.debug(s"Removed peer ${peerId.value} from scoring")

  /** Get statistics summary */
  def getStatistics: ScoringStatistics = snapshot().statistics

  /** Apply one event to one peer, atomically, then export if due. */
  private def update(peerId: PeerId, event: String)(change: (PeerScore, Instant) => PeerScore): Unit =
    val now = clock.instant()
    val (before, after) = updateExisting(peerId, now, change).getOrElse(admit(peerId, now, change))
    if log.underlying.isDebugEnabled then logScoreUpdate(peerId, before, after, now, event)
    publishIfDue()

  /** Update `peerId` if it is tracked; leave the map unchanged if not. The remapping function may run more than once
    * under contention, so it is pure and the result it recorded last (the one that committed) is returned.
    */
  private def updateExisting(
      peerId: PeerId,
      now: Instant,
      change: (PeerScore, Instant) => PeerScore
  ): Option[(PeerScore, PeerScore)] =
    var transition: Option[(PeerScore, PeerScore)] = None
    scores.updateWith(peerId) {
      case Some(before) =>
        val after = change(before, now)
        transition = Some(before -> after)
        Some(after)
      case None =>
        transition = None
        None
    }
    transition

  /** Track a peer new to the map. The lock makes room and inserts as one step, so concurrent admissions cannot exceed
    * `maxPeers`; an event for a peer another thread has just admitted is applied to that entry instead.
    */
  private def admit(peerId: PeerId, now: Instant, change: (PeerScore, Instant) => PeerScore): (PeerScore, PeerScore) =
    synchronized {
      updateExisting(peerId, now, change).getOrElse {
        while scores.size >= maxPeers do evictOldest()
        val created = change(PeerScore.empty, now)
        scores.put(peerId, created)
        (PeerScore.empty, created)
      }
    }

  /** Drop the entry that was seen least recently, ties broken by id so that the choice is reproducible. */
  private def evictOldest(): Unit =
    scores.iterator
      .minByOption { case (id, s) => (s.lastSeen.orElse(s.firstSeen).getOrElse(Instant.MIN), id.value) }
      .foreach { case (id, _) =>
        scores.remove(id)
        log.debug(s"Evicted peer ${id.value} from scoring: the map is at its cap of $maxPeers")
      }

  private def logScoreUpdate(
      peerId: PeerId,
      oldScore: PeerScore,
      newScore: PeerScore,
      now: Instant,
      event: String
  ): Unit =
    val before = oldScore.score(now)
    val after = newScore.score(now)
    val direction = if after > before then "↑" else if after < before then "↓" else "→"
    log.debug(s"Peer ${peerId.value} score $direction ${f"$before%.3f"} → ${f"$after%.3f"} after $event")

object PeerScoringManager:

  /** The most peers tracked unless told otherwise. A few times the connection limits (tens of peers), because failed
    * handshakes of crawlers also add entries and the oldest go first; small enough that the once-a-second snapshot is
    * cheap (about 100 KB).
    */
  final val DefaultMaxPeers: Int = 4096

  /** The least time between two exports caused by updates. */
  final val PublishIntervalMs: Long = 1000L

  private val NeverPublished: Long = Long.MinValue

  val NoExport: PeerScoreSnapshot => Unit = _ => ()

/** Every peer's score at one instant, as the scorer exports it. */
final case class PeerScoreSnapshot(takenAt: Instant, scores: Map[PeerId, Double], statistics: ScoringStatistics)

object PeerScoreSnapshot:
  val empty: PeerScoreSnapshot = PeerScoreSnapshot(Instant.EPOCH, Map.empty, ScoringStatistics.empty)

/** Statistics about peer scoring */
final case class ScoringStatistics(
    totalPeers: Int,
    averageScore: Double,
    highScoringPeers: Int,
    mediumScoringPeers: Int,
    lowScoringPeers: Int
):
  override def toString: String =
    s"Scoring: $totalPeers peers, avg=${f"$averageScore%.3f"}, high=$highScoringPeers, med=$mediumScoringPeers, low=$lowScoringPeers"

object ScoringStatistics:
  val empty: ScoringStatistics = ScoringStatistics(0, 0.0, 0, 0, 0)

  /** High is 0.7 and above, medium is 0.4 up to 0.7, low is below 0.4. */
  def of(scoreValues: Iterable[Double]): ScoringStatistics =
    val values = scoreValues.toSeq
    ScoringStatistics(
      totalPeers = values.size,
      averageScore = if values.nonEmpty then values.sum / values.size else 0.0,
      highScoringPeers = values.count(_ >= 0.7),
      mediumScoringPeers = values.count(s => s >= 0.4 && s < 0.7),
      lowScoringPeers = values.count(_ < 0.4)
    )
