package com.chipprbots.scalanet.discovery.ethereum.v5

import scodec.bits.ByteVector

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Wire-level tests for [[Packet]]. The acceptance gate is byte-for-byte
  * compatibility with the four discv5 test vectors that ship with geth's
  * `p2p/discover/v5wire` package. If those round-trip cleanly, our codec is
  * wire-compatible with the reference implementation; the rest is plumbing.
  *
  * Vectors at `/home/dontpanic/go/pkg/mod/github.com/ethereum/go-ethereum@v1.16.4/p2p/discover/v5wire/testdata/`:
  *   - v5.1-whoareyou.txt        — bare WHOAREYOU challenge
  *   - v5.1-ping-message.txt     — ordinary message (encrypted PING)
  *   - v5.1-ping-handshake.txt   — handshake-flagged PING
  *   - v5.1-ping-handshake-enr.txt — handshake with embedded ENR
  *
  * Each vector defines `src-node-id`, `dest-node-id`, etc. as `# key = 0xhex`
  * comments followed by the encoded packet bytes. We hardcode them rather
  * than read at runtime so the test stays self-contained.
  */
class PacketSpec extends AnyFlatSpec with Matchers {

  // ---- Geth test vectors --------------------------------------------------

  /** v5.1-whoareyou.txt */
  private val WhoareyouVector = TestVector(
    description = "v5.1-whoareyou",
    srcNodeId = ByteVector.fromValidHex("aaaa8419e9f49d0083561b48287df592939a8d19947d8c0ef88f2a4856a69fbb"),
    destNodeId = ByteVector.fromValidHex("bbbb9d047f0488c0b5a93c1c3f2d8bafc7c8ff337024a55434a0d0555de64db9"),
    encoded = ByteVector.fromValidHex(
      "00000000000000000000000000000000" +
        "088b3d434277464933a1ccc59f5967ad" +
        "1d6035f15e528627dde75cd68292f9e6" +
        "c27d6b66c8100a873fcbaed4e16b8d"
    ),
    expectedFlag = Packet.Flag.Whoareyou,
    expectedNonce = ByteVector.fromValidHex("0102030405060708090a0b0c"),
    extra = WhoareyouExtra(
      idNonce = ByteVector.fromValidHex("0102030405060708090a0b0c0d0e0f10"),
      recordSeq = 0L
    )
  )

  /** v5.1-ping-message.txt */
  private val PingMessageVector = TestVector(
    description = "v5.1-ping-message",
    srcNodeId = ByteVector.fromValidHex("aaaa8419e9f49d0083561b48287df592939a8d19947d8c0ef88f2a4856a69fbb"),
    destNodeId = ByteVector.fromValidHex("bbbb9d047f0488c0b5a93c1c3f2d8bafc7c8ff337024a55434a0d0555de64db9"),
    encoded = ByteVector.fromValidHex(
      "00000000000000000000000000000000" +
        "088b3d4342774649325f313964a39e55" +
        "ea96c005ad52be8c7560413a7008f16c" +
        "9e6d2f43bbea8814a546b7409ce783d3" +
        "4c4f53245d08dab84102ed931f66d149" +
        "2acb308fa1c6715b9d139b81acbdcc"
    ),
    expectedFlag = Packet.Flag.Message,
    expectedNonce = ByteVector.fromValidHex("ffffffffffffffffffffffff"),
    extra = MessageExtra(srcNodeId = ByteVector.fromValidHex("aaaa8419e9f49d0083561b48287df592939a8d19947d8c0ef88f2a4856a69fbb"))
  )

  /** v5.1-ping-handshake.txt — has signature, ephemeralPubkey, no ENR. */
  private val HandshakeVector = TestVector(
    description = "v5.1-ping-handshake",
    srcNodeId = ByteVector.fromValidHex("aaaa8419e9f49d0083561b48287df592939a8d19947d8c0ef88f2a4856a69fbb"),
    destNodeId = ByteVector.fromValidHex("bbbb9d047f0488c0b5a93c1c3f2d8bafc7c8ff337024a55434a0d0555de64db9"),
    encoded = ByteVector.fromValidHex(
      "00000000000000000000000000000000" +
        "088b3d4342774649305f313964a39e55" +
        "ea96c005ad521d8c7560413a7008f16c" +
        "9e6d2f43bbea8814a546b7409ce783d3" +
        "4c4f53245d08da4bb252012b2cba3f4f" +
        "374a90a75cff91f142fa9be3e0a5f3ef" +
        "268ccb9065aeecfd67a999e7fdc137e0" +
        "62b2ec4a0eb92947f0d9a74bfbf44dfb" +
        "a776b21301f8b65efd5796706adff216" +
        "ab862a9186875f9494150c4ae06fa4d1" +
        "f0396c93f215fa4ef524f1eadf5f0f41" +
        "26b79336671cbcf7a885b1f8bd2a5d83" +
        "9cf8"
    ),
    expectedFlag = Packet.Flag.Handshake,
    expectedNonce = ByteVector.fromValidHex("ffffffffffffffffffffffff"),
    extra = HandshakeExtra(
      srcNodeId = ByteVector.fromValidHex("aaaa8419e9f49d0083561b48287df592939a8d19947d8c0ef88f2a4856a69fbb"),
      ephemeralPubkeyLen = 33,
      hasRecord = false
    )
  )

  /** v5.1-ping-handshake-enr.txt — handshake with an embedded ENR record. */
  private val HandshakeEnrVector = TestVector(
    description = "v5.1-ping-handshake-enr",
    srcNodeId = ByteVector.fromValidHex("aaaa8419e9f49d0083561b48287df592939a8d19947d8c0ef88f2a4856a69fbb"),
    destNodeId = ByteVector.fromValidHex("bbbb9d047f0488c0b5a93c1c3f2d8bafc7c8ff337024a55434a0d0555de64db9"),
    encoded = ByteVector.fromValidHex(
      "00000000000000000000000000000000" +
        "088b3d4342774649305f313964a39e55" +
        "ea96c005ad539c8c7560413a7008f16c" +
        "9e6d2f43bbea8814a546b7409ce783d3" +
        "4c4f53245d08da4bb23698868350aaad" +
        "22e3ab8dd034f548a1c43cd246be9856" +
        "2fafa0a1fa86d8e7a3b95ae78cc2b988" +
        "ded6a5b59eb83ad58097252188b902b2" +
        "1481e30e5e285f19735796706adff216" +
        "ab862a9186875f9494150c4ae06fa4d1" +
        "f0396c93f215fa4ef524e0ed04c3c21e" +
        "39b1868e1ca8105e585ec17315e755e6" +
        "cfc4dd6cb7fd8e1a1f55e49b4b5eb024" +
        "221482105346f3c82b15fdaae36a3bb1" +
        "2a494683b4a3c7f2ae41306252fed847" +
        "85e2bbff3b022812d0882f06978df84a" +
        "80d443972213342d04b9048fc3b1d5fc" +
        "b1df0f822152eced6da4d3f6df27e70e" +
        "4539717307a0208cd208d65093ccab5a" +
        "a596a34d7511401987662d8cf62b139471"
    ),
    expectedFlag = Packet.Flag.Handshake,
    expectedNonce = ByteVector.fromValidHex("ffffffffffffffffffffffff"),
    extra = HandshakeExtra(
      srcNodeId = ByteVector.fromValidHex("aaaa8419e9f49d0083561b48287df592939a8d19947d8c0ef88f2a4856a69fbb"),
      ephemeralPubkeyLen = 33,
      hasRecord = true
    )
  )

  private val AllVectors =
    List(WhoareyouVector, PingMessageVector, HandshakeVector, HandshakeEnrVector)

  // ---- Tests --------------------------------------------------------------

  behavior.of("Packet")

  it should "have the spec's wire constants" in {
    Packet.ProtocolId shouldBe ByteVector.view("discv5".getBytes("US-ASCII"))
    Packet.Version shouldBe 1
    Packet.MaskingIVSize shouldBe 16
    Packet.StaticHeaderSize shouldBe 23
    Packet.MinPacketSize shouldBe 63
    Packet.MaxPacketSize shouldBe 1280
    Packet.Flag.Message shouldBe 0
    Packet.Flag.Whoareyou shouldBe 1
    Packet.Flag.Handshake shouldBe 2
  }

  it should "reject packets shorter than 63 bytes" in {
    val shortBytes = ByteVector.low(62)
    val anyDestId = ByteVector.low(32)
    Packet.decode(shortBytes, anyDestId).isFailure shouldBe true
  }

  it should "reject packets longer than 1280 bytes" in {
    val longBytes = ByteVector.low(1281)
    val anyDestId = ByteVector.low(32)
    Packet.decode(longBytes, anyDestId).isFailure shouldBe true
  }

  it should "reject packets whose unmasked magic is not 'discv5'" in {
    val bytes = ByteVector.low(80) // all-zero, mask key all-zero -> unmasked still zero
    val anyDestId = ByteVector.low(32)
    val r = Packet.decode(bytes, anyDestId)
    r.isFailure shouldBe true
  }

  it should "round-trip a synthetic WHOAREYOU through encode/decode" in {
    val destId = ByteVector.fromValidHex("bbbb9d047f0488c0b5a93c1c3f2d8bafc7c8ff337024a55434a0d0555de64db9")
    val iv = Packet.randomIv
    val pkt = Packet.WhoareyouPacket(
      Packet.Header.Whoareyou(
        iv = iv,
        nonce = ByteVector.fromValidHex("0102030405060708090a0b0c"),
        idNonce = ByteVector.fromValidHex("0102030405060708090a0b0c0d0e0f10"),
        recordSeq = 42L
      )
    )
    val encoded = Packet.encode(pkt, destId).require
    val decoded = Packet.decode(encoded, destId).require
    decoded shouldBe a[Packet.WhoareyouPacket]
    val w = decoded.asInstanceOf[Packet.WhoareyouPacket]
    w.header.iv shouldBe iv
    w.header.nonce shouldBe pkt.header.nonce
    w.header.idNonce shouldBe pkt.header.idNonce
    w.header.recordSeq shouldBe 42L
  }

  it should "round-trip a synthetic Message packet" in {
    val destId = ByteVector.fromValidHex("bbbb9d047f0488c0b5a93c1c3f2d8bafc7c8ff337024a55434a0d0555de64db9")
    val srcId = ByteVector.fromValidHex("aaaa8419e9f49d0083561b48287df592939a8d19947d8c0ef88f2a4856a69fbb")
    val ciphertext = ByteVector.fromValidHex("deadbeef" * 8) // arbitrary 32 bytes
    val pkt = Packet.MessagePacket(
      Packet.Header.Message(iv = Packet.randomIv, nonce = Packet.randomNonce, srcId = srcId),
      messageCiphertext = ciphertext
    )
    val encoded = Packet.encode(pkt, destId).require
    val decoded = Packet.decode(encoded, destId).require.asInstanceOf[Packet.MessagePacket]
    decoded.header.srcId shouldBe srcId
    decoded.header.nonce shouldBe pkt.header.nonce
    decoded.messageCiphertext shouldBe ciphertext
  }

  it should "round-trip a synthetic Handshake packet (with optional ENR)" in {
    val destId = ByteVector.fromValidHex("bbbb9d047f0488c0b5a93c1c3f2d8bafc7c8ff337024a55434a0d0555de64db9")
    val srcId = ByteVector.fromValidHex("aaaa8419e9f49d0083561b48287df592939a8d19947d8c0ef88f2a4856a69fbb")
    val signature = ByteVector.fromValidHex("11" * 64)
    val ephPubkey = ByteVector.fromValidHex("0288" + "ab" * 32) // compressed = 33 bytes
    val record = Some(ByteVector.fromValidHex("aabbccdd"))
    val ciphertext = ByteVector.fromValidHex("aa" * 32)

    val pkt = Packet.HandshakePacket(
      Packet.Header.Handshake(
        iv = Packet.randomIv,
        nonce = Packet.randomNonce,
        srcId = srcId,
        idSignature = signature,
        ephemeralPubkey = ephPubkey,
        record = record
      ),
      messageCiphertext = ciphertext
    )
    val encoded = Packet.encode(pkt, destId).require
    val decoded = Packet.decode(encoded, destId).require.asInstanceOf[Packet.HandshakePacket]
    decoded.header.srcId shouldBe srcId
    decoded.header.idSignature shouldBe signature
    decoded.header.ephemeralPubkey shouldBe ephPubkey
    decoded.header.record shouldBe record
    decoded.messageCiphertext shouldBe ciphertext
  }

  // ---- Cross-impl: geth test vectors --------------------------------------

  for (v <- AllVectors) {
    it should s"decode the geth ${v.description} test vector under destNodeId mask" in {
      val decoded = Packet.decode(v.encoded, v.destNodeId).require
      decoded.header.flag shouldBe v.expectedFlag
      decoded.header.nonce shouldBe v.expectedNonce
      v.extra match {
        case WhoareyouExtra(idNonce, recordSeq) =>
          decoded shouldBe a[Packet.WhoareyouPacket]
          val w = decoded.asInstanceOf[Packet.WhoareyouPacket]
          w.header.idNonce shouldBe idNonce
          w.header.recordSeq shouldBe recordSeq
        case MessageExtra(srcId) =>
          decoded shouldBe a[Packet.MessagePacket]
          decoded.asInstanceOf[Packet.MessagePacket].header.srcId shouldBe srcId
        case HandshakeExtra(srcId, pubkeyLen, hasRecord) =>
          decoded shouldBe a[Packet.HandshakePacket]
          val h = decoded.asInstanceOf[Packet.HandshakePacket]
          h.header.srcId shouldBe srcId
          h.header.ephemeralPubkey.size shouldBe pubkeyLen.toLong
          h.header.record.isDefined shouldBe hasRecord
      }
    }

    it should s"re-encode the geth ${v.description} vector to identical bytes" in {
      val decoded = Packet.decode(v.encoded, v.destNodeId).require
      val reEncoded = Packet.encode(decoded, v.destNodeId).require
      reEncoded shouldBe v.encoded
    }
  }

  // ---- Test data shapes --------------------------------------------------

  private sealed trait VectorExtra
  private case class WhoareyouExtra(idNonce: ByteVector, recordSeq: Long) extends VectorExtra
  private case class MessageExtra(srcNodeId: ByteVector) extends VectorExtra
  private case class HandshakeExtra(srcNodeId: ByteVector, ephemeralPubkeyLen: Int, hasRecord: Boolean)
      extends VectorExtra

  private case class TestVector(
      description: String,
      srcNodeId: ByteVector,
      destNodeId: ByteVector,
      encoded: ByteVector,
      expectedFlag: Byte,
      expectedNonce: ByteVector,
      extra: VectorExtra
  )
}
