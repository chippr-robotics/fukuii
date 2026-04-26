package com.chipprbots.scalanet.discovery.ethereum.v5

import java.net.{InetAddress, InetSocketAddress}
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.ConcurrentLinkedQueue

import scala.concurrent.duration._

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.chipprbots.scalanet.discovery.crypto.SigAlg
import com.chipprbots.scalanet.discovery.ethereum.{EthereumNodeRecord, Node}
import com.chipprbots.scalanet.discovery.ethereum.codecs.DefaultCodecs
import com.chipprbots.scalanet.discovery.ethereum.v4.mocks.MockSigAlg
import com.chipprbots.scalanet.peergroup.CloseableQueue
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scodec.{Attempt, Codec, DecodeResult, Err}
import scodec.bits.{BitVector, ByteVector}

/** Tests for [[DiscoveryNetwork]] focused on the request/response correlation
  * and the inbound dispatch loop.
  *
  * The full Codec[Payload] for v5 lives in fukuii main; these tests use a
  * stub codec that round-trips a few message types so the dispatch logic
  * can be exercised without the production RLP machinery. End-to-end
  * codec coverage is in fukuii main's `Discv5IntegrationSpec`.
  */
class DiscoveryNetworkSpec extends AnyFlatSpec with Matchers {

  import DefaultCodecs.{given, *}
  given sigalg: SigAlg = new MockSigAlg

  /** Stub Codec[Payload]. Tag byte = message-type discriminator; body holds
    * the payload's request id followed by a small marker for the type.
    *
    * The stub round-trips Pong/Nodes/TalkResponse and accepts Ping/FindNode/
    * TalkRequest as inputs (uses stubbed reqId on decode) — sufficient for
    * exercising request-response correlation. */
  given stubCodec: Codec[Payload] = Codec[Payload](
    (p: Payload) => {
      val tag = p.messageType
      val body = p match {
        case x: Payload.Ping         => ByteVector(x.requestId.toArray)
        case x: Payload.Pong         => ByteVector(x.requestId.toArray)
        case x: Payload.FindNode     => ByteVector(x.requestId.toArray)
        case x: Payload.Nodes        => ByteVector(x.requestId.toArray)
        case x: Payload.TalkRequest  => ByteVector(x.requestId.toArray)
        case x: Payload.TalkResponse => ByteVector(x.requestId.toArray)
      }
      Attempt.successful((ByteVector(tag) ++ body).bits)
    },
    (bits: BitVector) => {
      val bytes = bits.toByteVector
      if (bytes.isEmpty) Attempt.failure(Err("empty"))
      else {
        val tag = bytes(0)
        val rest = bytes.drop(1)
        val payloadOpt: Option[Payload] = tag match {
          case Payload.MessageType.Ping  => Some(Payload.Ping(rest, enrSeq = 1L))
          case Payload.MessageType.Pong  =>
            Some(
              Payload.Pong(
                requestId = rest,
                enrSeq = 7L,
                recipientIp = ByteVector.low(4),
                recipientPort = 30303
              )
            )
          case Payload.MessageType.FindNode =>
            Some(Payload.FindNode(rest, distances = List(0)))
          case Payload.MessageType.Nodes =>
            Some(Payload.Nodes(rest, total = 1, enrs = Nil))
          case Payload.MessageType.TalkReq =>
            Some(Payload.TalkRequest(rest, ByteVector.empty, ByteVector.empty))
          case Payload.MessageType.TalkResp =>
            Some(Payload.TalkResponse(rest, ByteVector.empty))
          case _ => None
        }
        payloadOpt match {
          case Some(p) => Attempt.successful(DecodeResult(p, BitVector.empty))
          case None    => Attempt.failure(Err(s"unknown tag $tag"))
        }
      }
    }
  )

  /** In-memory PeerGroupSender for tests. Records every send so assertions
    * can inspect the wire bytes. */
  final class RecordingSender extends DiscoveryNetwork.PeerGroupSender[InetSocketAddress] {
    private val sent = new ConcurrentLinkedQueue[(InetSocketAddress, ByteVector)]()
    override def sendRaw(remoteAddress: InetSocketAddress, bytes: ByteVector): IO[Unit] =
      IO { val _ = sent.add((remoteAddress, bytes)) }
    def all: List[(InetSocketAddress, ByteVector)] = {
      val out = List.newBuilder[(InetSocketAddress, ByteVector)]
      val it = sent.iterator()
      while (it.hasNext) out += it.next()
      out.result()
    }
  }

  /** Stub DiscoveryRPC handler — records calls; replies with safe defaults. */
  final class RecordingHandler extends DiscoveryRPC[DiscoveryNetwork.Peer[InetSocketAddress]] {
    val pings = new ConcurrentLinkedQueue[(DiscoveryNetwork.Peer[InetSocketAddress], Long)]()
    override def ping(peer: DiscoveryNetwork.Peer[InetSocketAddress], localEnrSeq: Long) = {
      pings.add((peer, localEnrSeq))
      IO.pure(None)
    }
    override def findNode(
        peer: DiscoveryNetwork.Peer[InetSocketAddress],
        distances: List[Int]
    ): IO[Option[List[EthereumNodeRecord]]] = IO.pure(None)
    override def talkRequest(
        peer: DiscoveryNetwork.Peer[InetSocketAddress],
        protocol: ByteVector,
        message: ByteVector
    ): IO[Option[ByteVector]] = IO.pure(None)
  }

  // ---- Test fixtures ------------------------------------------------------

  private val (localPub, localPriv) = sigalg.newKeyPair
  private val localNodeId = Session.nodeIdFromPublicKey(localPub.value.bytes)
  private val localAddr = new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 30303)
  private val localNode = Node(
    id = localPub,
    address = Node.Address(InetAddress.getLoopbackAddress, udpPort = 30303, tcpPort = 30303)
  )
  private val localEnr = EthereumNodeRecord.fromNode(localNode, localPriv, seq = 1).require

  private def buildNetwork(sender: DiscoveryNetwork.PeerGroupSender[InetSocketAddress]) = {
    val sessions = new Session.SessionCache()
    val challenges = new Discv5SyncResponder.ChallengeCache()
    val bystanders = new Discv5SyncResponder.BystanderEnrTable()
    for {
      queue <- CloseableQueue.unbounded[(InetSocketAddress, ByteVector)]
      network <- DiscoveryNetwork[InetSocketAddress](
        peerGroup = sender,
        privateKey = localPriv,
        publicKey = localPub,
        localNodeId = localNodeId,
        localEnrRef = new AtomicReference[EthereumNodeRecord](localEnr),
        sessions = sessions,
        challenges = challenges,
        bystanders = bystanders,
        dispatchQueue = queue,
        config = DiscoveryConfig.default.copy(requestTimeout = 200.millis)
      )
    } yield (network, sessions, queue)
  }

  // ---- Tests --------------------------------------------------------------

  behavior.of("DiscoveryNetwork.ping")

  it should "encrypt and send Ping when a session exists with the peer" in {
    val sender = new RecordingSender
    val (network, sessions, _) = buildNetwork(sender).unsafeRunSync()

    val (peerPub, _) = sigalg.newKeyPair
    val peerNodeId = Session.nodeIdFromPublicKey(peerPub.value.bytes)
    val peerAddr = new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 31000)

    // Pre-establish a session so ping doesn't try to handshake.
    val key = ByteVector.fromValidHex("00112233445566778899aabbccddeeff")
    sessions.put(
      Session.SessionId(peerNodeId, peerAddr),
      Session.Session(Session.Keys(writeKey = key, readKey = key), lastSeenMillis = 0L)
    )

    // Fire ping; will time out (no responder), but the send happens immediately.
    val pingFiber = network.ping(DiscoveryNetwork.Peer(peerNodeId, peerAddr), localEnrSeq = 1L).start.unsafeRunSync()
    Thread.sleep(50)
    val sent = sender.all
    sent should have size 1
    sent.head._1 shouldBe peerAddr

    // The bytes should decode under the peer's nodeId mask as a MessagePacket.
    val pkt = Packet.decode(sent.head._2, peerNodeId).require
    pkt shouldBe a[Packet.MessagePacket]

    // Cancel the ping fiber to avoid waiting on the timeout in test.
    pingFiber.cancel.unsafeRunSync()
  }

  it should "send a random-content trigger when no session exists" in {
    val sender = new RecordingSender
    val (network, _, _) = buildNetwork(sender).unsafeRunSync()

    val (peerPub, _) = sigalg.newKeyPair
    val peerNodeId = Session.nodeIdFromPublicKey(peerPub.value.bytes)
    val peerAddr = new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 31001)

    val pingFiber =
      network.ping(DiscoveryNetwork.Peer(peerNodeId, peerAddr), localEnrSeq = 1L).start.unsafeRunSync()
    Thread.sleep(50)
    val sent = sender.all
    sent should have size 1

    // The bytes should decode as a v5-shaped MessagePacket under peer's mask.
    val pkt = Packet.decode(sent.head._2, peerNodeId).require
    pkt shouldBe a[Packet.MessagePacket]
    // The message ciphertext is random 20 bytes — not actually encrypted under
    // any real key — so we just check the shape.
    pkt.messageCiphertext.size shouldBe 20L

    pingFiber.cancel.unsafeRunSync()
  }

  behavior.of("DiscoveryNetwork.startHandling")

  it should "consume from the dispatch queue and complete pending requests on matching response" in {
    val sender = new RecordingSender
    val (network, sessions, queue) = buildNetwork(sender).unsafeRunSync()

    val handler = new RecordingHandler
    val cancel = network.startHandling(handler).unsafeRunSync()
    // Give the consumer fiber a moment to subscribe to the queue.
    Thread.sleep(50)

    val (peerPub, _) = sigalg.newKeyPair
    val peerNodeId = Session.nodeIdFromPublicKey(peerPub.value.bytes)
    val peerAddr = new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 31002)

    // Pre-establish a session so we can build a response packet the consumer
    // will be able to decrypt.
    val key = ByteVector.fromValidHex("00112233445566778899aabbccddeeff")
    sessions.put(
      Session.SessionId(peerNodeId, peerAddr),
      Session.Session(Session.Keys(writeKey = key, readKey = key), lastSeenMillis = 0L)
    )

    // Issue an outbound ping — registers a pending request internally, sends bytes.
    val pingFiber = network
      .ping(DiscoveryNetwork.Peer(peerNodeId, peerAddr), localEnrSeq = 1L)
      .start
      .unsafeRunSync()
    Thread.sleep(50)

    // Inspect the sent Ping to extract its requestId from the encrypted body.
    val sentPkt = Packet.decode(sender.all.head._2, peerNodeId).require.asInstanceOf[Packet.MessagePacket]
    val maskedRegionEnd =
      Packet.MaskingIVSize + Packet.StaticHeaderSize + sentPkt.header.authData.size.toInt
    val sentAad = sender.all.head._2.take(maskedRegionEnd.toLong)
    val sentPlaintext = Session.decrypt(key, sentPkt.header.nonce, sentPkt.messageCiphertext, sentAad).get
    val sentPing = stubCodec.decode(sentPlaintext.bits).require.value.asInstanceOf[Payload.Ping]

    // Build a Pong response with the matching requestId, encrypt it under
    // the same session, and inject into the dispatch queue.
    val pong = Payload.Pong(
      requestId = sentPing.requestId,
      enrSeq = 99L,
      recipientIp = ByteVector.low(4),
      recipientPort = peerAddr.getPort
    )
    val pongPlaintext = stubCodec.encode(pong).require.toByteVector
    val nonce = ByteVector.fromValidHex("ffffffffffffffffffffffff")
    val iv = Packet.randomIv
    val staticHeader =
      Packet.ProtocolId ++
        ByteVector.fromInt(Packet.Version, Packet.VersionSize) ++
        ByteVector(Packet.Flag.Message) ++
        nonce ++
        ByteVector.fromInt(peerNodeId.size.toInt, Packet.AuthSizeSize)
    val masked = Packet.aesCtrMask(localNodeId, iv, staticHeader ++ peerNodeId)
    val aad = iv ++ masked
    val ct = Session.encrypt(key, nonce, pongPlaintext, aad).get
    val pongBytes = aad ++ ct

    queue.tryOffer((peerAddr, pongBytes)).unsafeRunSync()

    // The ping fiber should now resolve. The stub codec hardcodes the
    // enrSeq value, so we just assert that the request was correlated and
    // returned a result (Some, not None) — proves the dispatch loop pulled
    // from the queue, decrypted, decoded, and matched the requestId.
    val result = pingFiber.join.unsafeRunSync()
    result match {
      case cats.effect.kernel.Outcome.Succeeded(io) =>
        val r = io.unsafeRunSync()
        r should not be empty // request was correlated to a Pong via requestId
      case other => fail(s"expected Succeeded, got $other")
    }

    cancel.complete(()).unsafeRunSync()
  }
}
