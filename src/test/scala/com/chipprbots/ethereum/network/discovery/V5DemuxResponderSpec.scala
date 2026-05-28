package com.chipprbots.ethereum.network.discovery

import java.net.InetAddress
import java.net.InetSocketAddress

import cats.effect.unsafe.IORuntime
import com.chipprbots.scalanet.peergroup.CloseableQueue
import com.chipprbots.scalanet.peergroup.udp.StaticUDPPeerGroup
import com.chipprbots.scalanet.peergroup.udp.V5DemuxResponder
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scodec.bits.BitVector
import scodec.bits.ByteVector

import com.chipprbots.ethereum.testing.Tags._

class V5DemuxResponderSpec extends AnyFlatSpec with Matchers {

  implicit val ioRuntime: IORuntime = IORuntime.global

  private val sender   = new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 30303)
  private val incoming = BitVector(Array[Byte](1, 2, 3, 4))
  private val reply    = BitVector(Array[Byte](5, 6, 7, 8))

  private def freshQueue() =
    CloseableQueue.unbounded[(InetSocketAddress, ByteVector)].unsafeRunSync()

  behavior.of("V5DemuxResponder")

  it should "convert Reply to ClaimedReply to suppress the v4 async decode path" taggedAs (UnitTest, NetworkTest) in {
    val inner: StaticUDPPeerGroup.SyncResponder = (_, _) => StaticUDPPeerGroup.SyncResult.Reply(reply)
    val responder = V5DemuxResponder(inner, queue = Some(freshQueue()))

    responder(sender, incoming) shouldBe StaticUDPPeerGroup.SyncResult.ClaimedReply(reply)
  }

  it should "pass Stop through unchanged" taggedAs (UnitTest, NetworkTest) in {
    val inner: StaticUDPPeerGroup.SyncResponder = (_, _) => StaticUDPPeerGroup.SyncResult.Stop
    val responder = V5DemuxResponder(inner, queue = Some(freshQueue()))

    responder(sender, incoming) shouldBe StaticUDPPeerGroup.SyncResult.Stop
  }

  it should "pass Pass through unchanged" taggedAs (UnitTest, NetworkTest) in {
    val inner: StaticUDPPeerGroup.SyncResponder = (_, _) => StaticUDPPeerGroup.SyncResult.Pass
    val responder = V5DemuxResponder(inner, queue = Some(freshQueue()))

    responder(sender, incoming) shouldBe StaticUDPPeerGroup.SyncResult.Pass
  }

  it should "enqueue incoming bytes when inner returns Reply" taggedAs (UnitTest, NetworkTest) in {
    val queue = freshQueue()
    val inner: StaticUDPPeerGroup.SyncResponder = (_, _) => StaticUDPPeerGroup.SyncResult.Reply(reply)
    val responder = V5DemuxResponder(inner, queue = Some(queue))
    responder(sender, incoming)

    val queued = queue.closeAndKeep.flatMap(_ => queue.next).unsafeRunSync()
    queued shouldBe defined
    queued.get._1 shouldBe sender
    queued.get._2 shouldBe incoming.toByteVector
  }

  it should "enqueue incoming bytes when inner returns Stop" taggedAs (UnitTest, NetworkTest) in {
    val queue = freshQueue()
    val inner: StaticUDPPeerGroup.SyncResponder = (_, _) => StaticUDPPeerGroup.SyncResult.Stop
    val responder = V5DemuxResponder(inner, queue = Some(queue))
    responder(sender, incoming)

    val queued = queue.closeAndKeep.flatMap(_ => queue.next).unsafeRunSync()
    queued shouldBe defined
    queued.get._1 shouldBe sender
    queued.get._2 shouldBe incoming.toByteVector
  }

  it should "not enqueue bytes when inner returns Pass" taggedAs (UnitTest, NetworkTest) in {
    val queue = freshQueue()
    val inner: StaticUDPPeerGroup.SyncResponder = (_, _) => StaticUDPPeerGroup.SyncResult.Pass
    val responder = V5DemuxResponder(inner, queue = Some(queue))
    responder(sender, incoming)

    val queued = queue.closeAndKeep.flatMap(_ => queue.next).unsafeRunSync()
    queued shouldBe None
  }
}
