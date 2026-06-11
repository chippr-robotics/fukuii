package com.chipprbots.ethereum.blockchain.sync

import scala.collection.mutable

import com.chipprbots.ethereum.blockchain.sync.PeerListSupportNg.PeerWithInfo
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.BlockHeaders
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.GetBlockHeaders
import com.chipprbots.ethereum.utils.Logger

/** Concurrent fetch queue for block headers.
  *
  * Pending items are block numbers (BigInt). On reserve(), consecutive block numbers are batched into a single
  * GetBlockHeaders range request. On unreserve() or expireStale(), the block numbers are returned to the front of the
  * pending queue so they are retried before later work.
  *
  * Thread-safe: all mutable access is synchronized.
  *
  * Reference: go-ethereum/eth/downloader/queue.go — headerTaskQueue
  */
class HeadersFetcherQueue(tracker: PeerRateTracker) extends ConcurrentFetch[GetBlockHeaders, BlockHeaders] with Logger {

  import HeadersFetcherQueue._

  private val pendingQueue = mutable.Queue[BigInt]()
  private val inFlightMap = mutable.Map[PeerId, InFlightEntry]()

  /** Add block numbers to the pending queue.
    *
    * @param blockNumbers
    *   block numbers to request headers for, in ascending order
    */
  def enqueue(blockNumbers: Seq[BigInt]): Unit = synchronized {
    blockNumbers.foreach(pendingQueue.enqueue)
    log.debug(
      s"HeadersFetcherQueue enqueue: added ${blockNumbers.size} blocks, pending=${pendingQueue.size}"
    )
  }

  def pending: Int = synchronized(pendingQueue.size)

  def inFlight(peerId: PeerId): Option[InFlightRequest[GetBlockHeaders]] = synchronized {
    inFlightMap.get(peerId).map(_.snapshot)
  }

  def inFlightPeers: Set[PeerId] = synchronized(inFlightMap.keySet.toSet)

  def inFlightCount: Int = synchronized(inFlightMap.size)

  def capacity(peer: PeerWithInfo, targetRttMs: Long): Int =
    tracker
      .capacity(peer.peer.id.value, PeerRateTracker.MsgGetBlockHeaders, targetRttMs)
      .min(MaxHeadersPerRequest)
      .max(1)

  def reserve(peer: PeerWithInfo, items: Int): Option[GetBlockHeaders] = synchronized {
    if (pendingQueue.isEmpty) return None

    val count = items.min(MaxHeadersPerRequest).min(pendingQueue.size)
    val taken = (0 until count).map(_ => pendingQueue.dequeue()).toVector
    val startBlock = taken.head
    val requestId = ETHPackets.nextRequestId
    val req = GetBlockHeaders(requestId, Left(startBlock), count, skip = 0, reverse = false)
    val nowMs = System.currentTimeMillis()
    val deadlineMs = nowMs + tracker.targetTimeout().toMillis
    val entry = InFlightEntry(req, peer, taken, nowMs, deadlineMs, consecutiveFailures = 0)
    inFlightMap.put(peer.peer.id, entry)

    log.debug(
      s"HeadersFetcherQueue reserve: peer=${peer.peer.id}, startBlock=$startBlock, count=$count, " +
        s"deadline=${deadlineMs - nowMs}ms, pending=${pendingQueue.size}"
    )
    Some(req)
  }

  def unreserve(peerId: PeerId): Unit = synchronized {
    inFlightMap.remove(peerId).foreach { entry =>
      entry.taken.reverseIterator.foreach(pendingQueue.prepend)
      log.warn(
        s"HeadersFetcherQueue unreserve (timeout/disconnect): peer=$peerId, " +
          s"returned=${entry.taken.size} blocks, consecutiveFailures=${entry.consecutiveFailures + 1}, " +
          s"pending=${pendingQueue.size}"
      )
      // Record timeout as a capacity slash — items=0 penalizes the peer
      tracker.update(peerId.value, PeerRateTracker.MsgGetBlockHeaders, elapsedMs = 0L, items = 0)
    }
  }

  def deliver(peer: PeerWithInfo, resp: BlockHeaders, elapsedMs: Long): DeliveryResult = synchronized {
    inFlightMap.get(peer.peer.id) match {
      case None =>
        DeliveryResult.Duplicate

      case Some(entry) if entry.req.requestId != resp.requestId =>
        DeliveryResult.Invalid(
          s"HeadersFetcherQueue requestId mismatch: expected ${entry.req.requestId}, got ${resp.requestId}"
        )

      case Some(_) =>
        inFlightMap.remove(peer.peer.id)
        tracker.update(peer.peer.id.value, PeerRateTracker.MsgGetBlockHeaders, elapsedMs, resp.headers.size)
        log.debug(
          s"HeadersFetcherQueue deliver: peer=${peer.peer.id}, items=${resp.headers.size}, " +
            s"elapsed=${elapsedMs}ms, pending=${pendingQueue.size}"
        )
        DeliveryResult.Delivered(resp.headers.size)
    }
  }

  def expireStale(nowMs: Long): Seq[(PeerId, GetBlockHeaders)] = synchronized {
    val expired = inFlightMap.filter { case (_, entry) => nowMs > entry.deadlineMs }.toSeq
    expired.foreach { case (peerId, entry) =>
      inFlightMap.remove(peerId)
      entry.taken.reverseIterator.foreach(pendingQueue.prepend)
      log.warn(
        s"HeadersFetcherQueue expireStale: peer=$peerId, blocks=${entry.taken.size}, " +
          s"overdue=${nowMs - entry.deadlineMs}ms, returned to queue, pending=${pendingQueue.size}"
      )
      tracker.update(peerId.value, PeerRateTracker.MsgGetBlockHeaders, elapsedMs = 0L, items = 0)
    }
    expired.map { case (peerId, entry) => (peerId, entry.req) }
  }

  private case class InFlightEntry(
      req: GetBlockHeaders,
      peer: PeerWithInfo,
      taken: Vector[BigInt],
      sentMs: Long,
      deadlineMs: Long,
      consecutiveFailures: Int
  ) {
    def snapshot: InFlightRequest[GetBlockHeaders] =
      InFlightRequest(req, peer, sentMs, deadlineMs)
  }
}

object HeadersFetcherQueue {

  /** ETH protocol maximum headers per request (matches go-ethereum/eth/handler.go maxHeadersServe). */
  val MaxHeadersPerRequest: Int = 1024
}
