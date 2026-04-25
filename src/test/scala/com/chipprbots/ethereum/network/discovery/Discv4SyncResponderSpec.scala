package com.chipprbots.ethereum.network.discovery

import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

import com.chipprbots.scalanet.discovery.crypto.SigAlg
import com.chipprbots.scalanet.discovery.ethereum.Node
import com.chipprbots.scalanet.discovery.ethereum.v4.Discv4SyncResponder
import com.chipprbots.scalanet.discovery.ethereum.v4.Packet
import com.chipprbots.scalanet.discovery.ethereum.v4.Payload
import com.chipprbots.scalanet.discovery.hash.Hash
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scodec.Codec
import scodec.bits.BitVector

import com.chipprbots.ethereum.network.discovery.codecs.RLPCodecs
import com.chipprbots.ethereum.testing.Tags._

class Discv4SyncResponderSpec extends AnyFlatSpec with Matchers {

  // Match the codecs used in DiscoveryServiceBuilder.
  implicit val sigalg: SigAlg = new Secp256k1SigAlg
  implicit val packetCodec: Codec[Packet] = Packet.packetCodec(allowDecodeOverMaxPacketSize = true)
  implicit val payloadCodec: Codec[Payload] = RLPCodecs.payloadCodec

  private val sender = new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 31000)

  private val expirationSeconds: Long = 60L
  private val maxClockDriftSeconds: Long = 15L

  private def makeAddress(port: Int): Node.Address =
    Node.Address(InetAddress.getByName("127.0.0.1"), udpPort = port, tcpPort = port + 1)

  private def freshKeyPair = sigalg.newKeyPair

  private def encodePacket(packet: Packet): BitVector =
    packetCodec.encode(packet).require

  private def buildResponder(
      privateKey: com.chipprbots.scalanet.discovery.crypto.PrivateKey,
      enrSeqRef: AtomicReference[Option[Long]] = new AtomicReference(None)
  ) =
    Discv4SyncResponder(privateKey, expirationSeconds, maxClockDriftSeconds, enrSeqRef)

  behavior.of("Discv4SyncResponder")

  it should "produce a Pong for a valid, non-expired Ping" taggedAs (UnitTest, NetworkTest) in {
    val (_, privateKey) = freshKeyPair
    val responder = buildResponder(privateKey)

    val pingTo = makeAddress(31002)
    val pingFrom = makeAddress(31000)
    val ping = Payload.Ping(
      version = 4,
      from = pingFrom,
      to = pingTo,
      expiration = System.currentTimeMillis() / 1000 + expirationSeconds,
      enrSeq = None
    )
    val pingPacket = Packet.pack(ping, privateKey).require
    val incomingBits = encodePacket(pingPacket)

    val maybeReply = responder(sender, incomingBits)
    maybeReply should not be empty

    val replyPacket = packetCodec.decode(maybeReply.get).require.value
    val (replyPayload, _) = Packet.unpack(replyPacket).require
    replyPayload shouldBe a[Payload.Pong]
    val pong = replyPayload.asInstanceOf[Payload.Pong]
    pong.pingHash shouldBe pingPacket.hash
    pong.to shouldBe pingTo
    pong.expiration should be > (System.currentTimeMillis() / 1000)
    pong.enrSeq shouldBe None
  }

  it should "include the local ENR seq from the AtomicReference" taggedAs (UnitTest, NetworkTest) in {
    val (_, privateKey) = freshKeyPair
    val enrSeqRef = new AtomicReference[Option[Long]](Some(42L))
    val responder = buildResponder(privateKey, enrSeqRef)

    val ping = Payload.Ping(
      version = 4,
      from = makeAddress(31000),
      to = makeAddress(31002),
      expiration = System.currentTimeMillis() / 1000 + expirationSeconds,
      enrSeq = None
    )
    val pingPacket = Packet.pack(ping, privateKey).require
    val replyBits = responder(sender, encodePacket(pingPacket)).get
    val replyPacket = packetCodec.decode(replyBits).require.value
    val pong = Packet.unpack(replyPacket).require._1.asInstanceOf[Payload.Pong]
    pong.enrSeq shouldBe Some(42L)
  }

  it should "return None for an expired Ping" taggedAs (UnitTest, NetworkTest) in {
    val (_, privateKey) = freshKeyPair
    val responder = buildResponder(privateKey)

    val expiredPing = Payload.Ping(
      version = 4,
      from = makeAddress(31000),
      to = makeAddress(31002),
      expiration = System.currentTimeMillis() / 1000 - maxClockDriftSeconds - 60,
      enrSeq = None
    )
    val expiredPacket = Packet.pack(expiredPing, privateKey).require
    responder(sender, encodePacket(expiredPacket)) shouldBe None
  }

  it should "return None for a non-Ping payload (FindNode)" taggedAs (UnitTest, NetworkTest) in {
    val (_, privateKey) = freshKeyPair
    val (otherPub, _) = freshKeyPair
    val responder = buildResponder(privateKey)

    val findNode = Payload.FindNode(
      target = otherPub,
      expiration = System.currentTimeMillis() / 1000 + expirationSeconds
    )
    val findNodePacket = Packet.pack(findNode, privateKey).require
    responder(sender, encodePacket(findNodePacket)) shouldBe None
  }

  it should "return None for malformed bytes" taggedAs (UnitTest, NetworkTest) in {
    val (_, privateKey) = freshKeyPair
    val responder = buildResponder(privateKey)
    val tooShort = BitVector(Array.ofDim[Byte](16))
    responder(sender, tooShort) shouldBe None
  }

  it should "return None for bytes that decode but fail unpack (corrupt hash)" taggedAs (UnitTest, NetworkTest) in {
    val (_, privateKey) = freshKeyPair
    val responder = buildResponder(privateKey)

    val ping = Payload.Ping(
      version = 4,
      from = makeAddress(31000),
      to = makeAddress(31002),
      expiration = System.currentTimeMillis() / 1000 + expirationSeconds,
      enrSeq = None
    )
    val pingPacket = Packet.pack(ping, privateKey).require
    val corruptHashBytes = Array.ofDim[Byte](32)
    new java.util.Random(1).nextBytes(corruptHashBytes)
    val corrupt = pingPacket.copy(hash = Hash(BitVector(corruptHashBytes)))
    responder(sender, encodePacket(corrupt)) shouldBe None
  }
}
