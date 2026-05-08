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
import com.chipprbots.scalanet.peergroup.udp.StaticUDPPeerGroup
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

  /** Extract reply bytes from a [[StaticUDPPeerGroup.SyncResult.Reply]], or fail. */
  private def replyBitsOf(result: StaticUDPPeerGroup.SyncResult): BitVector = result match {
    case StaticUDPPeerGroup.SyncResult.Reply(bits) => bits
    case other                                     => fail(s"expected SyncResult.Reply, got $other")
  }

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
      enrSeqRef: AtomicReference[Option[Long]] = new AtomicReference(None),
      dedup: Discv4SyncResponder.PingDedup = new Discv4SyncResponder.PingDedup
  ) =
    Discv4SyncResponder(privateKey, expirationSeconds, maxClockDriftSeconds, enrSeqRef, dedup)

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

    val replyBits = replyBitsOf(responder(sender, incomingBits))
    val replyPacket = packetCodec.decode(replyBits).require.value
    val (replyPayload, _) = Packet.unpack(replyPacket).require
    replyPayload shouldBe a[Payload.Pong]
    val pong = replyPayload.asInstanceOf[Payload.Pong]
    pong.pingHash shouldBe pingPacket.hash
    // Per discv4.md, Pong.to is "the address from which the packet was received" —
    // i.e., the SENDER's address, NOT a copy of Ping.to.
    pong.to.ip shouldBe sender.getAddress
    pong.to.udpPort shouldBe sender.getPort
    pong.to.tcpPort shouldBe 0
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
    val replyBits = replyBitsOf(responder(sender, encodePacket(pingPacket)))
    val replyPacket = packetCodec.decode(replyBits).require.value
    val pong = Packet.unpack(replyPacket).require._1.asInstanceOf[Payload.Pong]
    pong.enrSeq shouldBe Some(42L)
  }

  it should "return Pass for an expired Ping" taggedAs (UnitTest, NetworkTest) in {
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
    responder(sender, encodePacket(expiredPacket)) shouldBe StaticUDPPeerGroup.SyncResult.Pass
  }

  it should "return Pass for a non-Ping payload (FindNode)" taggedAs (UnitTest, NetworkTest) in {
    val (_, privateKey) = freshKeyPair
    val (otherPub, _) = freshKeyPair
    val responder = buildResponder(privateKey)

    val findNode = Payload.FindNode(
      target = otherPub,
      expiration = System.currentTimeMillis() / 1000 + expirationSeconds
    )
    val findNodePacket = Packet.pack(findNode, privateKey).require
    responder(sender, encodePacket(findNodePacket)) shouldBe StaticUDPPeerGroup.SyncResult.Pass
  }

  it should "return Pass for malformed bytes" taggedAs (UnitTest, NetworkTest) in {
    val (_, privateKey) = freshKeyPair
    val responder = buildResponder(privateKey)
    val tooShort = BitVector(Array.ofDim[Byte](16))
    responder(sender, tooShort) shouldBe StaticUDPPeerGroup.SyncResult.Pass
  }

  it should "return Pass for bytes that decode but fail unpack (corrupt hash)" taggedAs (UnitTest, NetworkTest) in {
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
    responder(sender, encodePacket(corrupt)) shouldBe StaticUDPPeerGroup.SyncResult.Pass
  }

  it should "drop sync-path responses once the global rate limit is exhausted" taggedAs (UnitTest, NetworkTest) in {
    // Tight rate limit: 1 token/sec, burst 2. Three Pings in quick succession —
    // first two consume the burst, third hits the empty bucket and falls through.
    val (_, privateKey) = freshKeyPair
    val limiter = new Discv4SyncResponder.RateLimiter(tokensPerSecond = 1, maxBurst = 2)
    val responder = Discv4SyncResponder(
      privateKey = privateKey,
      expirationSeconds = expirationSeconds,
      maxClockDriftSeconds = maxClockDriftSeconds,
      localEnrSeqRef = new java.util.concurrent.atomic.AtomicReference[Option[Long]](None),
      dedup = new Discv4SyncResponder.PingDedup,
      rateLimiter = limiter
    )

    def freshPingBits(): BitVector = {
      val ping = Payload.Ping(
        version = 4,
        from = makeAddress(31000),
        to = makeAddress(31002),
        expiration = System.currentTimeMillis() / 1000 + expirationSeconds,
        enrSeq = None
      )
      encodePacket(Packet.pack(ping, privateKey).require)
    }

    // Burst of 2 — both should be served.
    replyBitsOf(responder(sender, freshPingBits()))
    replyBitsOf(responder(sender, freshPingBits()))

    // Third within the same second — bucket empty, sync path drops.
    responder(sender, freshPingBits()) shouldBe StaticUDPPeerGroup.SyncResult.Pass
  }

  it should "refill rate-limit tokens lazily based on elapsed time" taggedAs (UnitTest, NetworkTest) in {
    // 100 tokens/sec → 1 token per 10 ms. Burst of 1.
    val limiter = new Discv4SyncResponder.RateLimiter(tokensPerSecond = 100, maxBurst = 1)
    limiter.tryAcquire() shouldBe true
    limiter.tryAcquire() shouldBe false
    Thread.sleep(20) // wait for at least 1 token to refill
    limiter.tryAcquire() shouldBe true
  }

  it should "mark Ping hashes in PingDedup so the async pipeline skips its Pong" taggedAs (UnitTest, NetworkTest) in {
    val (_, privateKey) = freshKeyPair
    val dedup = new Discv4SyncResponder.PingDedup
    val responder = buildResponder(privateKey, dedup = dedup)

    val ping = Payload.Ping(
      version = 4,
      from = makeAddress(31000),
      to = makeAddress(31002),
      expiration = System.currentTimeMillis() / 1000 + expirationSeconds,
      enrSeq = None
    )
    val pingPacket = Packet.pack(ping, privateKey).require

    dedup.isAlreadyResponded(pingPacket.hash) shouldBe false
    replyBitsOf(responder(sender, encodePacket(pingPacket)))
    dedup.isAlreadyResponded(pingPacket.hash) shouldBe true
  }
}
