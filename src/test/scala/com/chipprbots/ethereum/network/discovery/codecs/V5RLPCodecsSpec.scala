package com.chipprbots.ethereum.network.discovery.codecs

import com.chipprbots.scalanet.discovery.ethereum.v5.Payload
import scodec.Codec
import scodec.bits.{BitVector, ByteVector}

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags._

/** Tests for [[V5RLPCodecs]]. The wire shape per discv5-wire.md:
  *   - Each message has a 1-byte type discriminator followed by RLP fields
  *   - Variable-length requestId (1–8 bytes); >8 must be rejected
  *   - recipientIp is raw 4 (IPv4) or 16 (IPv6) bytes — no length prefix
  *
  * geth's `framework.go` rejects requests with `len(reqId) > 8` via `ErrInvalidReqID`; that's what makes hive's
  * `PingLargeRequestID` test pass.
  */
class V5RLPCodecsSpec extends AnyFlatSpec with Matchers {

  import V5RLPCodecs.given

  private val codec: Codec[Payload] = summon[Codec[Payload]]

  private def roundTrip(payload: Payload): Payload = {
    val encoded = codec.encode(payload).require
    codec.decodeValue(encoded).require
  }

  // ---- Per-payload round-trips --------------------------------------------

  behavior.of("V5RLPCodecs.payloadCodec")

  it should "round-trip Ping with a 4-byte requestId" taggedAs (UnitTest, NetworkTest) in {
    val ping = Payload.Ping(requestId = ByteVector.fromValidHex("00000001"), enrSeq = 2L)
    roundTrip(ping) shouldBe ping
  }

  it should "round-trip Ping with a 1-byte and an 8-byte requestId" taggedAs (UnitTest, NetworkTest) in {
    roundTrip(Payload.Ping(ByteVector.fromValidHex("ff"), 0L)) shouldBe Payload.Ping(
      ByteVector.fromValidHex("ff"),
      0L
    )
    val eight = ByteVector.fromValidHex("0123456789abcdef")
    roundTrip(Payload.Ping(eight, 999L)) shouldBe Payload.Ping(eight, 999L)
  }

  it should "round-trip Pong with an IPv4 recipientIp" taggedAs (UnitTest, NetworkTest) in {
    val pong = Payload.Pong(
      requestId = ByteVector.fromValidHex("00000001"),
      enrSeq = 5L,
      recipientIp = ByteVector.fromValidHex("c0a80101"), // 192.168.1.1
      recipientPort = 30303
    )
    roundTrip(pong) shouldBe pong
  }

  it should "round-trip Pong with an IPv6 recipientIp" taggedAs (UnitTest, NetworkTest) in {
    val pong = Payload.Pong(
      requestId = ByteVector.fromValidHex("00000002"),
      enrSeq = 5L,
      recipientIp = ByteVector.fromValidHex("20010db8000000000000000000000001"),
      recipientPort = 30304
    )
    roundTrip(pong) shouldBe pong
  }

  it should "round-trip FindNode" taggedAs (UnitTest, NetworkTest) in {
    val fn = Payload.FindNode(
      requestId = ByteVector.fromValidHex("00000003"),
      distances = List(0, 1, 256)
    )
    roundTrip(fn) shouldBe fn
  }

  it should "round-trip Nodes (empty enrs)" taggedAs (UnitTest, NetworkTest) in {
    val n = Payload.Nodes(
      requestId = ByteVector.fromValidHex("00000004"),
      total = 1,
      enrs = Nil
    )
    roundTrip(n) shouldBe n
  }

  it should "round-trip TalkRequest" taggedAs (UnitTest, NetworkTest) in {
    val tq = Payload.TalkRequest(
      requestId = ByteVector.fromValidHex("00000005"),
      protocol = ByteVector.view("test-protocol".getBytes("US-ASCII")),
      message = ByteVector.fromValidHex("deadbeef")
    )
    roundTrip(tq) shouldBe tq
  }

  it should "round-trip TalkResponse with an empty message (the spec's 'unknown protocol' reply)" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    val tr = Payload.TalkResponse(
      requestId = ByteVector.fromValidHex("00000006"),
      message = ByteVector.empty
    )
    roundTrip(tr) shouldBe tr
  }

  // ---- Discriminator placement --------------------------------------------

  it should "prepend the 1-byte message-type discriminator" taggedAs (UnitTest, NetworkTest) in {
    val ping = Payload.Ping(ByteVector.fromValidHex("01"), 1L)
    val encoded = codec.encode(ping).require.toByteArray
    encoded.head shouldBe Payload.MessageType.Ping
  }

  it should "produce different leading bytes for each message type" taggedAs (UnitTest, NetworkTest) in {
    val reqId = ByteVector.fromValidHex("01")
    def lead(p: Payload): Byte = codec.encode(p).require.toByteArray.head
    lead(Payload.Ping(reqId, 1L)) shouldBe Payload.MessageType.Ping
    lead(Payload.Pong(reqId, 1L, ByteVector.low(4), 30303)) shouldBe Payload.MessageType.Pong
    lead(Payload.FindNode(reqId, Nil)) shouldBe Payload.MessageType.FindNode
    lead(Payload.Nodes(reqId, 1, Nil)) shouldBe Payload.MessageType.Nodes
    lead(Payload.TalkRequest(reqId, ByteVector.empty, ByteVector.empty)) shouldBe Payload.MessageType.TalkReq
    lead(Payload.TalkResponse(reqId, ByteVector.empty)) shouldBe Payload.MessageType.TalkResp
  }

  // ---- The PingLargeRequestID guard ---------------------------------------

  it should "reject decoding a Ping with a 9-byte requestId" taggedAs (UnitTest, NetworkTest) in {
    // Manually build a Ping payload with a 9-byte reqId (bypass case-class
    // validation by encoding the malformed bytes ourselves).
    import com.chipprbots.ethereum.rlp
    import com.chipprbots.ethereum.rlp.{RLPEncoder, RLPList}
    import com.chipprbots.ethereum.rlp.RLPImplicitConversions.toEncodeable
    import com.chipprbots.ethereum.rlp.RLPImplicits.given

    val tooLongReqId = ByteVector.fromValidHex("0102030405060708ff") // 9 bytes
    val pingList = RLPList(
      RLPEncoder.encode(tooLongReqId.toArray),
      RLPEncoder.encode(1L)
    )
    val rlpBytes = rlp.encode(pingList)
    val withTypeByte = ByteVector(Payload.MessageType.Ping +: rlpBytes)

    val attempt = codec.decode(withTypeByte.bits)
    attempt.isFailure shouldBe true
  }

  it should "reject an unknown leading message-type byte" taggedAs (UnitTest, NetworkTest) in {
    // 0x09 is not a valid discv5 message type (we dropped 0x07/0x08/etc).
    val bytes = ByteVector(0x09.toByte) ++ ByteVector.fromValidHex("c0")
    codec.decode(bytes.bits).isFailure shouldBe true
  }

  // ---- requestId fidelity (no padding/truncation) -------------------------

  it should "preserve a 1-byte requestId on round-trip (no padding to 8 bytes)" taggedAs (UnitTest, NetworkTest) in {
    val ping = Payload.Ping(ByteVector.fromValidHex("ab"), 0L)
    val rt = roundTrip(ping).asInstanceOf[Payload.Ping]
    rt.requestId.size shouldBe 1L
    rt.requestId shouldBe ByteVector.fromValidHex("ab")
  }
}
