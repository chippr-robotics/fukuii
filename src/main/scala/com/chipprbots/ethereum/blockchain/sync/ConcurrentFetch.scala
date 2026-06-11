package com.chipprbots.ethereum.blockchain.sync

import org.apache.pekko.event.LoggingAdapter

import com.chipprbots.ethereum.blockchain.sync.PeerListSupportNg.PeerWithInfo
import com.chipprbots.ethereum.network.PeerId

/** Result of delivering a response to a concurrent fetch queue. */
sealed trait DeliveryResult
object DeliveryResult {
  final case class Delivered(items: Int) extends DeliveryResult
  final case class Invalid(reason: String) extends DeliveryResult
  case object Duplicate extends DeliveryResult
}

/** Snapshot of an in-flight request sent to a peer.
  *
  * @param requestId
  *   ETH request ID from the sent message (for response matching)
  * @param peer
  *   the peer that was assigned this request
  * @param sentMs
  *   wall-clock time the request was sent (millis since epoch)
  * @param deadlineMs
  *   wall-clock time after which the request is considered stale
  */
final case class InFlightRequest[Req](
    req: Req,
    peer: PeerWithInfo,
    sentMs: Long,
    deadlineMs: Long
)

/** Type-safe concurrent download queue — Scala port of go-ethereum's typedQueue.
  *
  * Models one message-type pipeline (headers, bodies, or receipts). Tracks pending items and in-flight assignments;
  * drives the dispatch loop. Integrates with [[PeerRateTracker]] for adaptive capacity.
  *
  * Thread safety: all mutable state access must be guarded by the implementing class.
  *
  * Reference: go-ethereum/eth/downloader/fetchers_concurrent.go — typedQueue interface.
  */
trait ConcurrentFetch[Req, Resp] {

  /** Number of items waiting to be assigned to a peer (not yet in-flight). */
  def pending: Int

  /** In-flight request for a peer, if one exists. */
  def inFlight(peerId: PeerId): Option[InFlightRequest[Req]]

  /** Set of peer IDs that currently have an in-flight request from this queue. */
  def inFlightPeers: Set[PeerId]

  /** Total number of in-flight requests. */
  def inFlightCount: Int

  /** Estimate of how many items this peer can deliver within targetRttMs, using the rate tracker. */
  def capacity(peer: PeerWithInfo, targetRttMs: Long): Int

  /** Remove up to `items` pending tasks and assign them to `peer`. Returns the composed request, or None if the queue
    * is empty. Records the assignment in the in-flight tracker.
    */
  def reserve(peer: PeerWithInfo, items: Int): Option[Req]

  /** Return a peer's in-flight assignment to the pending queue (on timeout or disconnect). No-op if the peer has no
    * in-flight request.
    */
  def unreserve(peerId: PeerId): Unit

  /** Process a delivered response: validate it matches the in-flight assignment, update the rate tracker, mark
    * in-flight as complete, and return the delivery result for the caller to consume.
    *
    * @param peer
    *   the responding peer
    * @param resp
    *   the wire response
    * @param elapsedMs
    *   milliseconds from request send to response receive
    */
  def deliver(peer: PeerWithInfo, resp: Resp, elapsedMs: Long): DeliveryResult

  /** Scan in-flight requests and return any whose deadline has passed. The expired entries are removed from the
    * in-flight tracker and their items are returned to the pending queue.
    *
    * @param nowMs
    *   current time in milliseconds (System.currentTimeMillis())
    * @return
    *   (peerId, originalReq) pairs that expired
    */
  def expireStale(nowMs: Long): Seq[(PeerId, Req)]
}

object ConcurrentFetch {

  /** Core dispatch step — port of the assignment loop in go-ethereum's concurrentFetch().
    *
    * Scans available peers, skips those already in-flight, and calls reserve() for each idle peer proportional to its
    * estimated capacity. Returns (peer, req) pairs for the caller to send over the wire. Peers are sorted by descending
    * capacity so highest-throughput peers are assigned first.
    *
    * The caller is responsible for actually sending the requests (via PeersClient or direct send) and for expiring
    * stale in-flight entries via expireStale().
    *
    * Logging (parameterized — no string allocation when debug is off):
    *   - debug: dispatch summary (idle count, pending, in-flight, targetRtt)
    *   - debug: each reserve assignment (peer, items, queue label)
    *
    * @param queue
    *   the typed queue to dispatch from
    * @param availablePeers
    *   all handshaked peers eligible for work
    * @param targetRttMs
    *   target round-trip time from the shared rate tracker (globalMedianRttMs)
    * @param label
    *   short label for log lines ("headers", "bodies", "receipts")
    * @param log
    *   Pekko LoggingAdapter from the calling actor
    */
  def dispatchTo[Req, Resp](
      queue: ConcurrentFetch[Req, Resp],
      availablePeers: Iterable[PeerWithInfo],
      targetRttMs: Long,
      label: String,
      log: LoggingAdapter
  ): Seq[(PeerWithInfo, Req)] = {
    if (queue.pending == 0) return Seq.empty

    val idlePeers = availablePeers
      .filterNot(p => queue.inFlightPeers.contains(p.peer.id))
      .toSeq

    // label is always a static literal — safe to interpolate into the template string
    log.debug(
      s"ConcurrentFetch[$label] dispatch: idlePeers={}, pending={}, inFlight={}, targetRtt={}ms",
      idlePeers.size,
      queue.pending,
      queue.inFlightCount,
      targetRttMs
    )

    if (idlePeers.isEmpty) return Seq.empty

    // Sort by descending capacity — highest-throughput peers get first pick.
    val sorted = idlePeers.sortBy(p => -queue.capacity(p, targetRttMs))

    val assignments = scala.collection.mutable.Buffer[(PeerWithInfo, Req)]()
    for (peer <- sorted if queue.pending > 0) {
      val items = queue.capacity(peer, targetRttMs).max(1)
      queue.reserve(peer, items).foreach { req =>
        log.debug(
          s"ConcurrentFetch[$label] reserve: peer={}, items={}",
          peer.peer.id,
          items
        )
        assignments += (peer -> req)
      }
    }
    assignments.toSeq
  }
}
