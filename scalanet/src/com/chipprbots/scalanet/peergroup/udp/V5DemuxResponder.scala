package com.chipprbots.scalanet.peergroup.udp

import java.net.InetSocketAddress

import scala.util.control.NonFatal

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import com.chipprbots.scalanet.peergroup.CloseableQueue
import com.typesafe.scalalogging.LazyLogging
import scodec.bits.ByteVector

/** Wraps a discv5 [[StaticUDPPeerGroup.SyncResponder]] with an optional
  * side-channel that publishes v5 bytes to a queue for the async v5
  * pipeline (e.g. for handshake response correlation, outbound request
  * tracking). Without the queue this is just a transparent forwarder.
  *
  * When the wrapped responder returns [[StaticUDPPeerGroup.SyncResult.Stop]]
  * — meaning "I recognize this as v5 but I'm not replying synchronously" —
  * we push the raw bytes to the dispatch queue if one is configured.
  * [[StaticUDPPeerGroup.SyncResult.Reply]] is also v5-claimed; we push its
  * input bytes too so the async pipeline can do post-reply bookkeeping
  * (matching the v4 dedup pattern). [[StaticUDPPeerGroup.SyncResult.Pass]]
  * is non-v5 and is forwarded to the next chain link without enqueueing.
  *
  * The queue is `CloseableQueue` for cooperative shutdown.
  */
object V5DemuxResponder extends LazyLogging {

  /** Build a demuxing wrapper. `queue` may be `None` to make this layer a
    * no-op — useful when wiring up the responder before the async pipeline
    * is ready, or in tests. */
  def apply(
      inner: StaticUDPPeerGroup.SyncResponder,
      queue: Option[CloseableQueue[(InetSocketAddress, ByteVector)]]
  )(implicit runtime: IORuntime): StaticUDPPeerGroup.SyncResponder =
    queue match {
      case None        => inner
      case Some(queue) => withQueue(inner, queue)
    }

  private def withQueue(
      inner: StaticUDPPeerGroup.SyncResponder,
      queue: CloseableQueue[(InetSocketAddress, ByteVector)]
  )(implicit runtime: IORuntime): StaticUDPPeerGroup.SyncResponder = (sender, bits) => {
    val result = inner(sender, bits)

    val isV5 = result match {
      case StaticUDPPeerGroup.SyncResult.Reply(_) => true
      case StaticUDPPeerGroup.SyncResult.Stop     => true
      case StaticUDPPeerGroup.SyncResult.Pass     => false
    }

    if (isV5) {
      try {
        // tryOffer is non-blocking. If the queue is full or closed we drop
        // — better than blocking the netty thread on a slow async consumer.
        val _ = queue.tryOffer((sender, bits.toByteVector)).unsafeRunSync()
      } catch {
        case NonFatal(ex) =>
          logger.warn(s"V5DemuxResponder: failed to enqueue v5 bytes for $sender: ${ex.getClass.getSimpleName}: ${ex.getMessage}")
      }
    }

    result
  }
}
