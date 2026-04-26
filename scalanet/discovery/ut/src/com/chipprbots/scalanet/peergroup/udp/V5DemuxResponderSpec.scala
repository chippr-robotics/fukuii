package com.chipprbots.scalanet.peergroup.udp

import java.net.{InetAddress, InetSocketAddress}

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.chipprbots.scalanet.peergroup.CloseableQueue
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scodec.bits.{BitVector, ByteVector}

/** Tests for [[V5DemuxResponder]]: forwards results from the wrapped
  * responder, side-channels v5-claimed bytes to a dispatch queue. */
class V5DemuxResponderSpec extends AnyFlatSpec with Matchers {

  private val sender = new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 30303)

  private def runIO[A](io: IO[A]): A = io.unsafeRunSync()

  // Stub responders for each result type.
  private def passResponder: StaticUDPPeerGroup.SyncResponder =
    (_, _) => StaticUDPPeerGroup.SyncResult.Pass

  private def replyResponder(reply: BitVector): StaticUDPPeerGroup.SyncResponder =
    (_, _) => StaticUDPPeerGroup.SyncResult.Reply(reply)

  private def stopResponder: StaticUDPPeerGroup.SyncResponder =
    (_, _) => StaticUDPPeerGroup.SyncResult.Stop

  behavior.of("V5DemuxResponder")

  it should "be a transparent forwarder when no queue is configured" in {
    val expected = BitVector.fromValidHex("deadbeef")
    val demux = V5DemuxResponder(replyResponder(expected), queue = None)
    demux(sender, BitVector.empty) shouldBe StaticUDPPeerGroup.SyncResult.Reply(expected)

    val demuxStop = V5DemuxResponder(stopResponder, queue = None)
    demuxStop(sender, BitVector.empty) shouldBe StaticUDPPeerGroup.SyncResult.Stop

    val demuxPass = V5DemuxResponder(passResponder, queue = None)
    demuxPass(sender, BitVector.empty) shouldBe StaticUDPPeerGroup.SyncResult.Pass
  }

  it should "enqueue input bytes when wrapped responder returns Stop (v5 silent claim)" in {
    val q = runIO(CloseableQueue.unbounded[(InetSocketAddress, ByteVector)])
    val demux = V5DemuxResponder(stopResponder, Some(q))
    val payload = BitVector.fromValidHex("0011223344")

    demux(sender, payload) shouldBe StaticUDPPeerGroup.SyncResult.Stop

    val received = runIO(q.next).get
    received._1 shouldBe sender
    received._2 shouldBe payload.toByteVector
  }

  it should "enqueue input bytes when wrapped responder returns Reply (v5 reply + bookkeeping)" in {
    val q = runIO(CloseableQueue.unbounded[(InetSocketAddress, ByteVector)])
    val reply = BitVector.fromValidHex("aabbcc")
    val demux = V5DemuxResponder(replyResponder(reply), Some(q))
    val payload = BitVector.fromValidHex("01020304")

    demux(sender, payload) shouldBe StaticUDPPeerGroup.SyncResult.Reply(reply)
    val received = runIO(q.next).get
    received._2 shouldBe payload.toByteVector
  }

  it should "NOT enqueue when wrapped responder returns Pass (non-v5)" in {
    val q = runIO(CloseableQueue.unbounded[(InetSocketAddress, ByteVector)])
    val demux = V5DemuxResponder(passResponder, Some(q))
    demux(sender, BitVector.fromValidHex("aabbcc")) shouldBe StaticUDPPeerGroup.SyncResult.Pass

    // q.next would block on an empty queue; race a 50 ms timeout to confirm
    // the queue is empty without blocking the test forever.
    import scala.concurrent.duration._
    val raced = runIO(IO.race(q.next, IO.sleep(50.millis).as("timeout")))
    raced shouldBe Right("timeout")
  }

  it should "preserve all SyncResult shapes verbatim" in {
    val q = runIO(CloseableQueue.unbounded[(InetSocketAddress, ByteVector)])
    val replyBits = BitVector.fromValidHex("11" * 16)
    val demux = V5DemuxResponder(replyResponder(replyBits), Some(q))
    demux(sender, BitVector.fromValidHex("aa")) match {
      case StaticUDPPeerGroup.SyncResult.Reply(b) => b shouldBe replyBits
      case other                                  => fail(s"expected Reply, got $other")
    }
  }
}
