package com.chipprbots.scalanet.discovery.ethereum.v5

import java.net.{InetAddress, InetSocketAddress}

import com.chipprbots.scalanet.discovery.crypto.{PrivateKey, PublicKey, SigAlg, Signature}
import com.chipprbots.scalanet.discovery.ethereum.{EthereumNodeRecord, Node}
import com.chipprbots.scalanet.discovery.ethereum.v4.mocks.MockSigAlg
import com.chipprbots.scalanet.discovery.ethereum.codecs.DefaultCodecs
import scodec.{Attempt, Codec, DecodeResult, Err}
import scodec.bits.{BitVector, ByteVector}

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Tests for [[Discv5SyncResponder]] focused on the wire-protocol responses.
  *
  * NOTE: these tests use a stub `Codec[Payload]` rather than the real
  * `V5RLPCodecs` (which lives in fukuii main and depends on
  * `com.chipprbots.ethereum.rlp`). The stub round-trips correctly for the
  * payload types we exercise; the production codec is tested in
  * `V5RLPCodecsSpec`.
  *
  * The full end-to-end "real codec + real wire" integration test lives in
  * fukuii main as part of Step 9.
  */
class Discv5SyncResponderSpec extends AnyFlatSpec with Matchers {

  // ---- Test infra: stub Codec[Payload] -------------------------------------

  // Tag bytes — match Payload.MessageType so the responder's pattern matches
  // the same discriminator the real codec produces.
  given stubPayloadCodec: Codec[Payload] = Codec[Payload](
    (p: Payload) => {
      val tag = p.messageType
      val body = p match {
        case _: Payload.Ping        => ByteVector("ping".getBytes)
        case _: Payload.Pong        => ByteVector("pong".getBytes)
        case _: Payload.FindNode    => ByteVector("findnode".getBytes)
        case _: Payload.Nodes       => ByteVector("nodes".getBytes)
        case _: Payload.TalkRequest => ByteVector("talkreq".getBytes)
        case _: Payload.TalkResponse => ByteVector("talkresp".getBytes)
      }
      Attempt.successful((ByteVector(tag) ++ body).bits)
    },
    (bits: BitVector) => {
      // Pull off the type byte and synthesize a stub payload; we never
      // actually inspect content in these tests.
      val bytes = bits.toByteVector
      if (bytes.isEmpty) Attempt.failure(Err("empty"))
      else {
        val tag = bytes(0)
        val payloadOpt: Option[Payload] = tag match {
          case Payload.MessageType.Ping =>
            Some(Payload.Ping(ByteVector.fromValidHex("01020304"), enrSeq = 7L))
          case Payload.MessageType.Pong =>
            Some(
              Payload.Pong(
                ByteVector.fromValidHex("01020304"),
                enrSeq = 7L,
                recipientIp = ByteVector.low(4),
                recipientPort = 30303
              )
            )
          case Payload.MessageType.FindNode =>
            Some(Payload.FindNode(ByteVector.fromValidHex("01020304"), List(0)))
          case Payload.MessageType.TalkReq =>
            Some(
              Payload.TalkRequest(
                ByteVector.fromValidHex("01020304"),
                protocol = ByteVector("p".getBytes),
                message = ByteVector.empty
              )
            )
          case _ =>
            None
        }
        payloadOpt match {
          case Some(p) => Attempt.successful(DecodeResult(p, BitVector.empty))
          case None    => Attempt.failure(Err(s"stub doesn't decode tag $tag"))
        }
      }
    }
  )

  given sigalg: SigAlg = new MockSigAlg

  // ---- Test fixtures ------------------------------------------------------

  private val sender = new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 30303)

  // Local node identity — uses the MockSigAlg which has PublicKey == PrivateKey
  // raw bits, making nodeId derivation predictable.
  private val (localPub, localPriv) = sigalg.newKeyPair
  private val localNodeId = Session.nodeIdFromPublicKey(localPub.value.bytes)

  // Stub local ENR — never actually inspected by responder logic in these tests.
  private val stubLocalEnr: EthereumNodeRecord = {
    import DefaultCodecs.{given, *}
    EthereumNodeRecord
      .fromNode(
        Node(
          id = localPub,
          address = Node.Address(InetAddress.getLoopbackAddress, udpPort = 30303, tcpPort = 30303)
        ),
        localPriv,
        seq = 1
      )
      .require
  }

  private val stubHandler: Discv5SyncResponder.Handler = new Discv5SyncResponder.Handler {
    val localEnr = stubLocalEnr
    val localEnrSeq = 1L
    def findNodes(distances: List[Int]): List[EthereumNodeRecord] =
      if (distances.contains(0)) List(stubLocalEnr) else Nil
  }

  private def freshResponder() = {
    val sessions = new Session.SessionCache()
    val challenges = new Discv5SyncResponder.ChallengeCache()
    val bystanders = new Discv5SyncResponder.BystanderEnrTable()
    val responder = Discv5SyncResponder(
      privateKey = localPriv,
      localNodeId = localNodeId,
      handler = stubHandler,
      sessions = sessions,
      challenges = challenges,
      bystanders = bystanders
    )
    (responder, sessions, challenges, bystanders)
  }

  // ---- Tests --------------------------------------------------------------

  behavior.of("Discv5SyncResponder")

  it should "reject non-discv5 bytes (mask mismatch) and return None" in {
    val (responder, _, _, _) = freshResponder()
    val junk = BitVector(Array.fill[Byte](80)(0))
    responder(sender, junk) shouldBe None
  }

  it should "respond to a no-session MessagePacket with a WHOAREYOU and stash a pending challenge" in {
    val (responder, _, challenges, _) = freshResponder()

    // Build a no-session MessagePacket from a different srcId, addressed to us.
    val (_, peerPriv) = sigalg.newKeyPair
    val peerNodeId = Session.nodeIdFromPublicKey(sigalg.toPublicKey(peerPriv).value.bytes)

    val triggerNonce = ByteVector.fromValidHex("0102030405060708090a0b0c")
    val msgPkt = Packet.MessagePacket(
      header = Packet.Header.Message(
        iv = Packet.randomIv,
        nonce = triggerNonce,
        srcId = peerNodeId
      ),
      messageCiphertext = ByteVector.fromValidHex("aabbccddeeff" * 4)
    )
    val incomingBytes = Packet.encode(msgPkt, localNodeId).require

    val reply = responder(sender, incomingBytes.bits)
    reply should not be empty

    // Decode the reply — it should be a WHOAREYOU under the peer's node id mask.
    val replyPkt = Packet.decode(reply.get.toByteVector, peerNodeId).require
    replyPkt shouldBe a[Packet.WhoareyouPacket]
    val whoPkt = replyPkt.asInstanceOf[Packet.WhoareyouPacket]
    whoPkt.header.nonce shouldBe triggerNonce // echoed back per spec

    // Challenge must be stashed so a follow-up handshake can be verified.
    challenges.get(triggerNonce) should not be empty
  }

  it should "return None for incoming WhoareyouPacket (handled by async path)" in {
    val (responder, _, _, _) = freshResponder()

    val (_, peerPriv) = sigalg.newKeyPair
    val peerNodeId = Session.nodeIdFromPublicKey(sigalg.toPublicKey(peerPriv).value.bytes)

    // Peer challenges us — they don't know our session yet, so they'd send a
    // WHOAREYOU. From our side, this means "you should initiate a handshake
    // with me" — only the async path handles outbound handshakes.
    val whoPkt = Packet.WhoareyouPacket(
      header = Packet.Header.Whoareyou(
        iv = Packet.randomIv,
        nonce = ByteVector.fromValidHex("0102030405060708090a0b0c"),
        idNonce = Session.randomIdNonce,
        recordSeq = 0L
      )
    )
    val incomingBytes = Packet.encode(whoPkt, localNodeId).require
    responder(sender, incomingBytes.bits) shouldBe None
  }

  it should "respond to a MessagePacket with an existing session by encrypting the response" in {
    val (responder, sessions, _, _) = freshResponder()

    val (peerPub, peerPriv) = sigalg.newKeyPair
    val peerNodeId = Session.nodeIdFromPublicKey(peerPub.value.bytes)

    // Pre-establish a session as if a prior handshake had succeeded.
    val sharedKey = ByteVector.fromValidHex("00112233445566778899aabbccddeeff")
    val keys = Session.Keys(writeKey = sharedKey, readKey = sharedKey)
    val sid = Session.SessionId(peerNodeId, sender)
    sessions.put(sid, Session.Session(keys, lastSeenMillis = System.currentTimeMillis()))

    // Build a Ping payload encrypted with the session's readKey (the peer's
    // writeKey, which we read with).
    val pingBytes = stubPayloadCodec
      .encode(Payload.Ping(ByteVector.fromValidHex("01020304"), enrSeq = 7L))
      .require
      .toByteVector

    val nonce = ByteVector.fromValidHex("ffffffffffffffffffffffff")
    val iv = Packet.randomIv

    // Assemble the masked region as AAD (matches what the responder uses).
    val staticHeader =
      Packet.ProtocolId ++
        ByteVector.fromInt(Packet.Version, Packet.VersionSize) ++
        ByteVector(Packet.Flag.Message) ++
        nonce ++
        ByteVector.fromInt(peerNodeId.size.toInt, Packet.AuthSizeSize)
    val masked = Packet.aesCtrMask(localNodeId, iv, staticHeader ++ peerNodeId)
    val aad = iv ++ masked

    val ct = Session.encrypt(sharedKey, nonce, pingBytes, aad).get
    val incoming = (aad ++ ct).bits

    val reply = responder(sender, incoming)
    reply should not be empty

    // The reply should decode under the peer's nodeId mask as a MessagePacket.
    val replyPkt = Packet.decode(reply.get.toByteVector, peerNodeId).require
    replyPkt shouldBe a[Packet.MessagePacket]
    val replyMsg = replyPkt.asInstanceOf[Packet.MessagePacket]

    // Decrypt the response using the same shared key.
    val replyMaskedRegionEnd =
      Packet.MaskingIVSize + Packet.StaticHeaderSize + replyMsg.header.authData.size.toInt
    val replyAad = reply.get.toByteVector.take(replyMaskedRegionEnd.toLong)
    val responseBytes =
      Session.decrypt(sharedKey, replyMsg.header.nonce, replyMsg.messageCiphertext, replyAad).get

    // Decode the inner payload — should be a Pong response.
    val response = stubPayloadCodec.decode(responseBytes.bits).require.value
    response shouldBe a[Payload.Pong]
    response.asInstanceOf[Payload.Pong].requestId shouldBe ByteVector.fromValidHex("01020304")
  }

  it should "key sessions by (nodeId, addr) so a fresh IP triggers a new WHOAREYOU" in {
    // The PingMultiIP regression: same peer's nodeId but a different source
    // IP must hit a session miss, not silently succeed under the old session.
    val (responder, sessions, _, _) = freshResponder()

    val (peerPub, _) = sigalg.newKeyPair
    val peerNodeId = Session.nodeIdFromPublicKey(peerPub.value.bytes)

    // Pre-load a session for sender addr.
    val keys = Session.Keys(
      writeKey = ByteVector.fromValidHex("00112233445566778899aabbccddeeff"),
      readKey = ByteVector.fromValidHex("00112233445566778899aabbccddeeff")
    )
    sessions.put(
      Session.SessionId(peerNodeId, sender),
      Session.Session(keys, lastSeenMillis = System.currentTimeMillis())
    )

    // Now arrive from a different IP.
    val differentSender = new InetSocketAddress(InetAddress.getByName("10.0.0.1"), 30303)
    val triggerNonce = ByteVector.fromValidHex("0102030405060708090a0b0c")
    val msgPkt = Packet.MessagePacket(
      header = Packet.Header.Message(
        iv = Packet.randomIv,
        nonce = triggerNonce,
        srcId = peerNodeId
      ),
      messageCiphertext = ByteVector.fromValidHex("aa" * 32)
    )
    val incoming = Packet.encode(msgPkt, localNodeId).require.bits

    val reply = responder(differentSender, incoming)
    reply should not be empty
    val replyPkt = Packet.decode(reply.get.toByteVector, peerNodeId).require
    replyPkt shouldBe a[Packet.WhoareyouPacket] // session miss → fresh WHOAREYOU
  }

  it should "logDistance returns 0 for identical IDs and 256 for max XOR" in {
    val zero = ByteVector.low(32)
    Discv5SyncResponder.logDistance(zero, zero) shouldBe 0
    val high = ByteVector.high(32) // all 0xff
    Discv5SyncResponder.logDistance(zero, high) shouldBe 256
  }

  it should "BystanderEnrTable.atDistance filters by log-distance" in {
    val table = new Discv5SyncResponder.BystanderEnrTable()
    // Same nodeId as local (distance 0)
    table.add(localNodeId, stubLocalEnr)
    table.atDistance(localNodeId, 0) should have size 1
    table.atDistance(localNodeId, 5) shouldBe empty
  }

  it should "rate-limit excessive sync responses" in {
    val limiter = new Discv5SyncResponder.RateLimiter(tokensPerSecond = 1, maxBurst = 2)
    val sessions = new Session.SessionCache()
    val challenges = new Discv5SyncResponder.ChallengeCache()
    val bystanders = new Discv5SyncResponder.BystanderEnrTable()
    val responder = Discv5SyncResponder(
      privateKey = localPriv,
      localNodeId = localNodeId,
      handler = stubHandler,
      sessions = sessions,
      challenges = challenges,
      bystanders = bystanders,
      rateLimiter = limiter
    )

    // Build no-session messages — they each consume a token.
    def freshMessage(): BitVector = {
      val (_, peerPriv) = sigalg.newKeyPair
      val peerNodeId = Session.nodeIdFromPublicKey(sigalg.toPublicKey(peerPriv).value.bytes)
      val msg = Packet.MessagePacket(
        Packet.Header.Message(Packet.randomIv, Packet.randomNonce, peerNodeId),
        ByteVector.fromValidHex("aa" * 32)
      )
      Packet.encode(msg, localNodeId).require.bits
    }

    // 2 tokens in burst → 2 succeed
    responder(sender, freshMessage()) should not be empty
    responder(sender, freshMessage()) should not be empty
    // 3rd consumes empty bucket → drops
    responder(sender, freshMessage()) shouldBe None
  }
}
