package com.chipprbots.ethereum.blockchain.sync

import scala.collection.mutable

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.blockchain.sync.PeerListSupportNg.PeerWithInfo
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.GetReceipts
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.Receipts68
import com.chipprbots.ethereum.utils.Logger

/** Concurrent fetch queue for block receipts (ETH68 — bloom fields present).
  *
  * Pending items are block hashes (ByteString). On reserve(), up to MaxReceiptsPerRequest hashes are batched into a
  * single GetReceipts request. On unreserve() or expireStale(), the hashes are returned to the front of the pending
  * queue.
  *
  * Thread-safe: all mutable access is synchronized.
  *
  * Reference: go-ethereum/eth/downloader/queue.go — receiptTaskQueue
  */
class ReceiptsFetcherQueue(tracker: PeerRateTracker) extends ConcurrentFetch[GetReceipts, Receipts68] with Logger {

  import ReceiptsFetcherQueue._

  private val pendingQueue = mutable.Queue[ByteString]()
  private val inFlightMap = mutable.Map[PeerId, InFlightEntry]()

  /** Add block hashes to the pending receipts queue. */
  def enqueue(hashes: Seq[ByteString]): Unit = synchronized {
    hashes.foreach(pendingQueue.enqueue)
    log.debug(
      s"ReceiptsFetcherQueue enqueue: added ${hashes.size} hashes, pending=${pendingQueue.size}"
    )
  }

  def pending: Int = synchronized(pendingQueue.size)

  def inFlight(peerId: PeerId): Option[InFlightRequest[GetReceipts]] = synchronized {
    inFlightMap.get(peerId).map(_.snapshot)
  }

  def inFlightPeers: Set[PeerId] = synchronized(inFlightMap.keySet.toSet)

  def inFlightCount: Int = synchronized(inFlightMap.size)

  def capacity(peer: PeerWithInfo, targetRttMs: Long): Int =
    tracker
      .capacity(peer.peer.id.value, PeerRateTracker.MsgGetReceipts, targetRttMs)
      .min(MaxReceiptsPerRequest)
      .max(1)

  def reserve(peer: PeerWithInfo, items: Int): Option[GetReceipts] = synchronized {
    if (pendingQueue.isEmpty) return None

    val count = items.min(MaxReceiptsPerRequest).min(pendingQueue.size)
    val taken = (0 until count).map(_ => pendingQueue.dequeue()).toVector
    val requestId = ETHPackets.nextRequestId
    val req = GetReceipts(requestId, taken)
    val nowMs = System.currentTimeMillis()
    val deadlineMs = nowMs + tracker.targetTimeout().toMillis
    inFlightMap.put(peer.peer.id, InFlightEntry(req, peer, taken, nowMs, deadlineMs))

    log.debug(
      s"ReceiptsFetcherQueue reserve: peer=${peer.peer.id}, count=$count, " +
        s"deadline=${deadlineMs - nowMs}ms, pending=${pendingQueue.size}"
    )
    Some(req)
  }

  def unreserve(peerId: PeerId): Unit = synchronized {
    inFlightMap.remove(peerId).foreach { entry =>
      entry.taken.reverseIterator.foreach(pendingQueue.prepend)
      log.warn(
        s"ReceiptsFetcherQueue unreserve (timeout/disconnect): peer=$peerId, " +
          s"returned=${entry.taken.size} hashes, pending=${pendingQueue.size}"
      )
      tracker.update(peerId.value, PeerRateTracker.MsgGetReceipts, elapsedMs = 0L, items = 0)
    }
  }

  def deliver(peer: PeerWithInfo, resp: Receipts68, elapsedMs: Long): DeliveryResult = synchronized {
    inFlightMap.get(peer.peer.id) match {
      case None =>
        DeliveryResult.Duplicate

      case Some(entry) if entry.req.requestId != resp.requestId =>
        DeliveryResult.Invalid(
          s"ReceiptsFetcherQueue requestId mismatch: expected ${entry.req.requestId}, got ${resp.requestId}"
        )

      case Some(_) =>
        inFlightMap.remove(peer.peer.id)
        // receiptsForBlocks is an RLPList — each item is one block's receipt list
        val blockCount = resp.receiptsForBlocks.items.size
        tracker.update(peer.peer.id.value, PeerRateTracker.MsgGetReceipts, elapsedMs, blockCount)
        log.debug(
          s"ReceiptsFetcherQueue deliver: peer=${peer.peer.id}, blocks=$blockCount, " +
            s"elapsed=${elapsedMs}ms, pending=${pendingQueue.size}"
        )
        DeliveryResult.Delivered(blockCount)
    }
  }

  def expireStale(nowMs: Long): Seq[(PeerId, GetReceipts)] = synchronized {
    val expired = inFlightMap.filter { case (_, entry) => nowMs > entry.deadlineMs }.toSeq
    expired.foreach { case (peerId, entry) =>
      inFlightMap.remove(peerId)
      entry.taken.reverseIterator.foreach(pendingQueue.prepend)
      log.warn(
        s"ReceiptsFetcherQueue expireStale: peer=$peerId, hashes=${entry.taken.size}, " +
          s"overdue=${nowMs - entry.deadlineMs}ms, returned to queue, pending=${pendingQueue.size}"
      )
      tracker.update(peerId.value, PeerRateTracker.MsgGetReceipts, elapsedMs = 0L, items = 0)
    }
    expired.map { case (peerId, entry) => (peerId, entry.req) }
  }

  private case class InFlightEntry(
      req: GetReceipts,
      peer: PeerWithInfo,
      taken: Vector[ByteString],
      sentMs: Long,
      deadlineMs: Long
  ) {
    def snapshot: InFlightRequest[GetReceipts] =
      InFlightRequest(req, peer, sentMs, deadlineMs)
  }
}

object ReceiptsFetcherQueue {

  /** ETH protocol maximum receipt batches per request (matches go-ethereum/eth/handler.go maxReceiptsServe). */
  val MaxReceiptsPerRequest: Int = 256
}
