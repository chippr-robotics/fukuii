package com.chipprbots.ethereum.blockchain.sync.snap

import scala.collection.mutable
import scala.concurrent.duration._

import com.chipprbots.ethereum.utils.Logger

/** Per-peer message rate tracker for adaptive SNAP sync timeouts.
  *
  * Port of geth's p2p/msgrate package. Tracks per-peer throughput (items/sec) and round-trip times, then computes
  * adaptive request timeouts and per-peer request capacities.
  *
  * Algorithm:
  *   - Each peer has an EMA-smoothed capacity (items/sec per message type) and roundtrip estimate
  *   - Aggregated median RTT across all peers determines the global target timeout
  *   - Confidence starts at 0.5 and converges toward 1.0 as more measurements arrive
  *   - Timeout = min(ttlLimit, ttlScaling * medianRTT / confidence)
  *
  * Reference: go-ethereum/p2p/msgrate/msgrate.go
  */
class PeerRateTracker extends Logger {
  import PeerRateTracker._

  // Per-peer rate tracking
  private val peers = mutable.Map[String, PeerStats]()

  // Aggregated state
  private var medianRTT: Long = RttMinEstimateMs // milliseconds
  private var confidence: Double = 0.5
  private var lastTuneMs: Long = System.currentTimeMillis()

  /** Record a measurement after receiving a response from a peer.
    *
    * @param peerId
    *   the peer that responded
    * @param msgType
    *   SNAP message type ordinal
    * @param elapsedMs
    *   time from request send to response receive (milliseconds)
    * @param items
    *   number of items in the response (0 = timeout/failure)
    */
  def update(peerId: String, msgType: Int, elapsedMs: Long, items: Int): Unit = synchronized {
    val stats = peers.getOrElseUpdate(peerId, PeerStats())

    if (items == 0) {
      // Timeout or unavailable — slash capacity to zero (geth: Update with 0,0)
      stats.capacity.update(msgType, 0.0)
    } else {
      val elapsed = elapsedMs.max(1) // prevent division by zero
      val measured = items.toDouble / (elapsed.toDouble / 1000.0)

      val old = stats.capacity.getOrElse(msgType, 0.0)
      stats.capacity.update(msgType, (1 - MeasurementImpact) * old + MeasurementImpact * measured)

      val oldRtt = stats.roundtripMs
      stats.roundtripMs = ((1 - MeasurementImpact) * oldRtt + MeasurementImpact * elapsed).toLong
    }
  }

  /** Calculate the maximum number of items to request from a peer.
    *
    * @param peerId
    *   the peer
    * @param msgType
    *   SNAP message type ordinal
    * @param targetRTT
    *   target round-trip time in milliseconds (from targetRoundTrip())
    * @return
    *   number of items to request (minimum 1)
    */
  def capacity(peerId: String, msgType: Int, targetRTT: Long): Int = synchronized {
    val stats = peers.get(peerId)
    val cap = stats match {
      case Some(s) =>
        val throughput = s.capacity.getOrElse(msgType, 0.0) * targetRTT.toDouble / 1000.0
        (1 + CapacityOverestimation * throughput).toInt
      case None => 1
    }
    cap.max(1)
  }

  /** Calculate the adaptive timeout for SNAP requests.
    *
    * Formula: min(ttlLimit, ttlScaling * medianRTT / confidence)
    *
    * @return
    *   timeout as FiniteDuration
    */
  def targetTimeout(): FiniteDuration = synchronized {
    val rawMs = (TtlScaling * medianRTT.toDouble / confidence).toLong
    rawMs.min(TtlLimitMs).max(RttMinEstimateMs).millis
  }

  /** Calculate the target round-trip time (pushed down for efficiency).
    *
    * @return
    *   target RTT in milliseconds
    */
  def targetRoundTrip(): Long = synchronized {
    (medianRTT * RttPushdownFactor).toLong.max(RttMinEstimateMs)
  }

  /** Recalculate median RTT and update confidence. Call periodically (every ~5 seconds).
    *
    * Geth calls tune() every medianRTT interval. We simplify to a fixed interval since actor scheduling is more natural
    * with fixed periods.
    */
  def tune(): Unit = synchronized {
    val now = System.currentTimeMillis()
    if (peers.isEmpty) {
      lastTuneMs = now
      return
    }

    // Collect RTTs from all peers, sort, and pick geometric-mean index (√N)
    val rtts = peers.values.map(_.roundtripMs).toArray.sorted
    val idx = math.sqrt(rtts.length.toDouble).toInt.min(rtts.length - 1)
    val median = rtts(idx).max(RttMinEstimateMs).min(RttMaxEstimateMs)

    // EMA update of median RTT
    medianRTT = ((1 - TuningImpact) * medianRTT + TuningImpact * median).toLong
    medianRTT = medianRTT.max(RttMinEstimateMs).min(RttMaxEstimateMs)

    // Increase confidence toward 1.0
    confidence = confidence + (1.0 - confidence) / 2.0
    confidence = confidence.max(RttMinConfidence).min(1.0)

    lastTuneMs = now
    log.debug(
      s"PeerRateTracker tuned: medianRTT=${medianRTT}ms, confidence=${"%.3f".format(confidence)}, " +
        s"timeout=${targetTimeout().toSeconds}s, peers=${peers.size}"
    )
  }

  /** Notify that a new peer connected. Detunes confidence on small networks.
    *
    * @param peerId
    *   the new peer
    */
  def addPeer(peerId: String): Unit = synchronized {
    if (!peers.contains(peerId)) {
      peers.put(peerId, PeerStats())

      // Detune confidence (geth: detune on new peer)
      val n = peers.size
      if (n == 1) {
        confidence = 1.0 // Single peer is authoritative
      } else if (n < TuningConfidenceCap) {
        confidence = (confidence * (n - 1).toDouble / n).max(RttMinConfidence)
      }
      // If n >= TuningConfidenceCap (10), don't detune (stable network)
    }
  }

  /** Notify that a peer disconnected.
    *
    * @param peerId
    *   the disconnected peer
    */
  def removePeer(peerId: String): Unit = synchronized {
    peers.remove(peerId)
  }

  /** Get current number of tracked peers */
  def peerCount: Int = synchronized(peers.size)

  /** Get current median RTT estimate in milliseconds */
  def currentMedianRTT: Long = synchronized(medianRTT)

  /** Get current confidence */
  def currentConfidence: Double = synchronized(confidence)
}

object PeerRateTracker {

  // --- Geth msgrate constants (from p2p/msgrate/msgrate.go) ---

  /** Weight of new capacity/RTT measurements in EMA */
  val MeasurementImpact: Double = 0.1

  /** Small overshoot in capacity to escape local minima */
  val CapacityOverestimation: Double = 1.01

  /** Minimum RTT estimate (generous for DB lookups + transmission) */
  val RttMinEstimateMs: Long = 2000L

  /** Maximum RTT estimate (covers worst links like satellite) */
  val RttMaxEstimateMs: Long = 20000L

  /** Scale target RTT down (prefer smaller, faster requests) */
  val RttPushdownFactor: Double = 0.9

  /** Minimum confidence floor */
  val RttMinConfidence: Double = 0.1

  /** RTT→timeout multiplier (allow for peer load variance) */
  val TtlScaling: Double = 3.0

  /** Timeout hard cap in milliseconds */
  val TtlLimitMs: Long = 60000L

  /** Peer count threshold — don't detune if >= this many peers */
  val TuningConfidenceCap: Int = 10

  /** Weight of new median RTT in tuning */
  val TuningImpact: Double = 0.25

  // --- SNAP message type ordinals (for capacity tracking) ---
  val MsgGetAccountRange: Int = 0
  val MsgGetStorageRanges: Int = 1
  val MsgGetByteCodes: Int = 2
  val MsgGetTrieNodes: Int = 3

  /** Per-peer statistics */
  private[snap] case class PeerStats(
      capacity: mutable.Map[Int, Double] = mutable.Map.empty,
      var roundtripMs: Long = RttMinEstimateMs * 2 // Start at 4s (conservative)
  )
}
