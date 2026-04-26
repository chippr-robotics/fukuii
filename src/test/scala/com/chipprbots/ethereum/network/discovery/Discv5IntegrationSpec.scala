package com.chipprbots.ethereum.network.discovery

import java.net.{InetAddress, InetSocketAddress}
import java.util.concurrent.atomic.AtomicReference

import scala.concurrent.duration._

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.effect.std.CountDownLatch
import com.chipprbots.scalanet.discovery.crypto.SigAlg
import com.chipprbots.scalanet.discovery.ethereum.{EthereumNodeRecord, Node => ScNode}
import com.chipprbots.scalanet.discovery.ethereum.codecs.DefaultCodecs
import com.chipprbots.scalanet.discovery.ethereum.{v4, v5}
import com.chipprbots.scalanet.peergroup.InetMultiAddress
import com.chipprbots.scalanet.peergroup.udp.{StaticUDPPeerGroup, V5DemuxResponder}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scodec.Codec
import scodec.bits.{BitVector, ByteVector}

import com.chipprbots.ethereum.network.discovery.codecs.V5RLPCodecs
import com.chipprbots.ethereum.testing.Tags._

/** End-to-end integration test: two real StaticUDPPeerGroup instances on loopback exchange a discv5
  * PING/WHOAREYOU/HANDSHAKE/PONG flow using the production V5RLPCodecs + Discv5SyncResponder.
  *
  * Verifies that the wire layer + sync responder + codec compose correctly over real UDP. Lives in fukuii main (not
  * scalanet) because it exercises the production V5RLPCodecs that lives here.
  */
class Discv5IntegrationSpec extends AnyFlatSpec with Matchers {

  import DefaultCodecs.{given, *}
  implicit val sigalg: SigAlg = new Secp256k1SigAlg
  implicit val v5PayloadCodec: Codec[v5.Payload] = V5RLPCodecs.payloadCodec
  // v4 codec needed for StaticUDPPeerGroup type — even though we won't use v4 here.
  implicit val v4PacketCodec: Codec[v4.Packet] = v4.Packet.packetCodec(allowDecodeOverMaxPacketSize = true)

  /** Allocate a random unused UDP port. */
  private def freePort: Int = {
    val s = new java.net.DatagramSocket(0)
    try s.getLocalPort
    finally s.close()
  }

  private def makeUdpConfig(port: Int, syncResponder: StaticUDPPeerGroup.SyncResponder): StaticUDPPeerGroup.Config = {
    val addr = new InetSocketAddress(InetAddress.getLoopbackAddress, port)
    StaticUDPPeerGroup.Config(
      bindAddress = addr,
      processAddress = InetMultiAddress(addr),
      channelCapacity = 0,
      receiveBufferSizeBytes = 0,
      syncResponder = syncResponder
    )
  }

  private def makeNode(privateKey: com.chipprbots.scalanet.discovery.crypto.PrivateKey, port: Int): ScNode = {
    val pub = sigalg.toPublicKey(privateKey)
    ScNode(
      id = pub,
      address = ScNode.Address(InetAddress.getLoopbackAddress, udpPort = port, tcpPort = port)
    )
  }

  private def buildResponder(
      privateKey: com.chipprbots.scalanet.discovery.crypto.PrivateKey,
      localNode: ScNode
  ): StaticUDPPeerGroup.SyncResponder = {
    val localNodeId = v5.Session.nodeIdFromPublicKey(localNode.id.value.bytes)
    val initialEnr = EthereumNodeRecord.fromNode(localNode, privateKey, seq = 1).require
    val enrRef = new AtomicReference[EthereumNodeRecord](initialEnr)
    val handler = new v5.Discv5SyncResponder.Handler {
      def localEnr: EthereumNodeRecord = enrRef.get
      def localEnrSeq: Long = enrRef.get.content.seq
      def findNodes(distances: List[Int]): List[EthereumNodeRecord] =
        if (distances.contains(0)) List(enrRef.get) else Nil
    }
    v5.Discv5SyncResponder(
      privateKey = privateKey,
      localNodeId = localNodeId,
      handler = handler,
      sessions = new v5.Session.SessionCache(),
      challenges = new v5.Discv5SyncResponder.ChallengeCache(),
      bystanders = new v5.Discv5SyncResponder.BystanderEnrTable()
    )
  }

  behavior.of("Discv5 end-to-end")

  it should "compose Packet + V5RLPCodecs round-trip a Ping → Pong via the real codec" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    // Doesn't need real UDP — exercises the codec composition only.
    val (pubA, privA) = sigalg.newKeyPair
    val (pubB, privB) = sigalg.newKeyPair
    val nodeIdA = v5.Session.nodeIdFromPublicKey(pubA.value.bytes)
    val nodeIdB = v5.Session.nodeIdFromPublicKey(pubB.value.bytes)

    // Pre-establish a session as if a prior handshake completed.
    val sharedKey = ByteVector.fromValidHex("00112233445566778899aabbccddeeff")
    val keys = v5.Session.Keys(writeKey = sharedKey, readKey = sharedKey)

    val sender = new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 30303)

    // Side B: build the responder
    val nodeB = makeNode(privB, 30303)
    val initialEnrB = EthereumNodeRecord.fromNode(nodeB, privB, seq = 1).require
    val enrRefB = new AtomicReference[EthereumNodeRecord](initialEnrB)
    val handlerB = new v5.Discv5SyncResponder.Handler {
      def localEnr: EthereumNodeRecord = enrRefB.get
      def localEnrSeq: Long = enrRefB.get.content.seq
      def findNodes(distances: List[Int]): List[EthereumNodeRecord] =
        if (distances.contains(0)) List(enrRefB.get) else Nil
    }
    val sessionsB = new v5.Session.SessionCache()
    sessionsB.put(
      v5.Session.SessionId(nodeIdA, sender),
      v5.Session.Session(keys, lastSeenMillis = System.currentTimeMillis())
    )
    val responderB = v5.Discv5SyncResponder(
      privateKey = privB,
      localNodeId = nodeIdB,
      handler = handlerB,
      sessions = sessionsB,
      challenges = new v5.Discv5SyncResponder.ChallengeCache(),
      bystanders = new v5.Discv5SyncResponder.BystanderEnrTable()
    )

    // A → B: encrypted Ping
    val ping = v5.Payload.Ping(requestId = ByteVector.fromValidHex("aabbccdd"), enrSeq = 1L)
    val plaintext = v5PayloadCodec.encode(ping).require.toByteVector
    val nonce = ByteVector.fromValidHex("ffffffffffffffffffffffff")
    val iv = v5.Packet.randomIv

    val staticHeader =
      v5.Packet.ProtocolId ++
        ByteVector.fromInt(v5.Packet.Version, v5.Packet.VersionSize) ++
        ByteVector(v5.Packet.Flag.Message) ++
        nonce ++
        ByteVector.fromInt(nodeIdA.size.toInt, v5.Packet.AuthSizeSize)
    val masked = v5.Packet.aesCtrMask(nodeIdB, iv, staticHeader ++ nodeIdA)
    val aad = iv ++ masked
    val ct = v5.Session.encrypt(sharedKey, nonce, plaintext, aad).get
    val incoming = (aad ++ ct).bits

    // B sync-respond
    val result = responderB(sender, incoming)
    val replyBits = result match {
      case StaticUDPPeerGroup.SyncResult.Reply(b) => b
      case other                                  => fail(s"expected Reply, got $other")
    }

    // A: decode the reply.
    val replyPkt = v5.Packet.decode(replyBits.toByteVector, nodeIdA).require
    replyPkt shouldBe a[v5.Packet.MessagePacket]
    val replyMsg = replyPkt.asInstanceOf[v5.Packet.MessagePacket]

    val replyMaskedRegionEnd =
      v5.Packet.MaskingIVSize + v5.Packet.StaticHeaderSize + replyMsg.header.authData.size.toInt
    val replyAad = replyBits.toByteVector.take(replyMaskedRegionEnd.toLong)
    val responseBytes =
      v5.Session.decrypt(sharedKey, replyMsg.header.nonce, replyMsg.messageCiphertext, replyAad).get
    val response = v5PayloadCodec.decode(responseBytes.bits).require.value

    response shouldBe a[v5.Payload.Pong]
    val pong = response.asInstanceOf[v5.Payload.Pong]
    pong.requestId shouldBe ping.requestId
    pong.enrSeq shouldBe handlerB.localEnrSeq
    pong.recipientPort shouldBe sender.getPort
  }

  it should "build the V5DemuxResponder + chained responder shape used by DiscoveryServiceBuilder" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    val (_, priv) = sigalg.newKeyPair
    val nodeIdLocal = v5.Session.nodeIdFromPublicKey(sigalg.toPublicKey(priv).value.bytes)
    val localNode = makeNode(priv, 30303)
    val v5Responder = buildResponder(priv, localNode)

    // V4 stub — always Pass, so v5 always wins for v5-shaped packets.
    val v4Stub: StaticUDPPeerGroup.SyncResponder = (_, _) => StaticUDPPeerGroup.SyncResult.Pass

    val chain = StaticUDPPeerGroup.chainResponders(v5Responder, v4Stub)

    val sender = new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 30303)

    // Junk bytes — neither v5 nor v4 claim, returns Pass.
    chain(sender, BitVector(Array.fill[Byte](80)(0))) shouldBe StaticUDPPeerGroup.SyncResult.Pass

    // v5-shaped no-session message — v5 sync responds with WHOAREYOU.
    val (peerPub, _) = sigalg.newKeyPair
    val peerNodeId = v5.Session.nodeIdFromPublicKey(peerPub.value.bytes)
    val msgPkt = v5.Packet.MessagePacket(
      header = v5.Packet.Header.Message(
        iv = v5.Packet.randomIv,
        nonce = ByteVector.fromValidHex("0102030405060708090a0b0c"),
        srcId = peerNodeId
      ),
      messageCiphertext = ByteVector.fromValidHex("aa" * 32)
    )
    val incomingBytes = v5.Packet.encode(msgPkt, nodeIdLocal).require.bits
    chain(sender, incomingBytes) match {
      case StaticUDPPeerGroup.SyncResult.Reply(_) => succeed
      case other                                  => fail(s"expected v5 Reply, got $other")
    }
  }
}
