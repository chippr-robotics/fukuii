package com.chipprbots.ethereum.network.p2p

import org.apache.pekko.util.ByteString

import org.scalacheck.Arbitrary
import org.scalacheck.Gen
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.network.rlpx.Frame
import com.chipprbots.ethereum.network.rlpx.FrameCodec
import com.chipprbots.ethereum.network.rlpx.Header
import com.chipprbots.ethereum.network.rlpx.MessageCodec
import com.chipprbots.ethereum.network.rlpx.MessageCodec.CompressionPolicy
import com.chipprbots.ethereum.testing.Tags._

/** Malformed-input resilience tests for MessageCodec.readFrames and FrameCodec.readFrames.
  *
  * The protocol contract: every malformed frame MUST produce a Left(DecodingError) and MUST NOT throw. An unhandled
  * exception on a frame terminates the peer connection without a clean disconnect and is treated as a correctness
  * failure.
  *
  * MessageCodec.readFrames(frames) is tested directly — it does not invoke FrameCodec, so no crypto setup is needed for
  * the payload-level tests. FrameCodec.readFrames(data) is tested with sub-header-length byte strings, which return an
  * empty list without touching the AES cipher.
  */
class MessageCodecMalformedInputSpec
    extends AnyFlatSpec
    with Matchers
    with ScalaCheckPropertyChecks
    with SecureChannelSetup {

  // Fresh codec per call: FrameCodec is stateful (accumulates unprocessedData across readFrames calls).
  // Using a def ensures each test gets an isolated instance with no carry-over bytes.
  private def frameCodec = new FrameCodec(secrets)

  private val decoder: MessageDecoder =
    NetworkMessageDecoder.orElse(EthereumMessageDecoder.ethMessageDecoder(Capability.ETH63))

  private val noCompressionCodec: MessageCodec = new MessageCodec(
    frameCodec,
    decoder,
    remotePeer2PeerVersion = 4L,
    remoteClientId = "TestMalformed/v1.0",
    compressionPolicy = CompressionPolicy.apply(compressOutbound = false, expectInboundCompressed = false)
  )

  private def frame(typeCode: Int, payload: Array[Byte]): Frame =
    Frame(Header(payload.length, 0, None, None), typeCode, ByteString(payload))

  // -----------------------------------------------------------------------
  // Empty payload
  // -----------------------------------------------------------------------

  behavior of "MessageCodec.readFrames with empty payload"

  it should "return Left for an empty payload on a known message type code" taggedAs (UnitTest, NetworkTest) in {
    val frames = Seq(frame(0x10, Array.emptyByteArray)) // StatusCode
    val results = noCompressionCodec.readFrames(frames)
    results should have size 1
    results.head shouldBe a[Left[_, _]]
  }

  it should "return Left for an empty payload on a network-layer message type code" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    val frames = Seq(frame(0x01, Array.emptyByteArray)) // Ping
    val results = noCompressionCodec.readFrames(frames)
    results should have size 1
    results.head shouldBe a[Left[_, _]]
  }

  // -----------------------------------------------------------------------
  // Unknown type codes
  // -----------------------------------------------------------------------

  behavior of "MessageCodec.readFrames with unknown type codes"

  it should "return Left(UnknownMessageTypeError) for type code 0xFF" taggedAs (UnitTest, NetworkTest) in {
    val frames = Seq(frame(0xff, Array[Byte](0xc0.toByte)))
    val results = noCompressionCodec.readFrames(frames)
    results should have size 1
    results.head shouldBe a[Left[_, _]]
    results.head.left.get shouldBe a[MessageDecoder.UnknownMessageTypeError]
  }

  it should "return Left for type code 0x1000 (above all registered protocols)" taggedAs (UnitTest, NetworkTest) in {
    val frames = Seq(frame(0x1000, Array[Byte](0xc0.toByte)))
    val results = noCompressionCodec.readFrames(frames)
    results should have size 1
    results.head shouldBe a[Left[_, _]]
  }

  it should "return Left for type code 0x00 (wire Hello — never sent post-handshake)" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    val frames = Seq(frame(0x00, Array[Byte](0xc0.toByte)))
    val results = noCompressionCodec.readFrames(frames)
    results should have size 1
    results.head shouldBe a[Left[_, _]]
  }

  // -----------------------------------------------------------------------
  // Random / garbage payloads on known type codes
  // -----------------------------------------------------------------------

  behavior of "MessageCodec.readFrames with random bytes on known type codes"

  it should "return Left for random bytes as payload on StatusCode (0x10)" taggedAs (UnitTest, NetworkTest) in {
    val garbage = Array.fill(64)(0xde.toByte)
    val frames = Seq(frame(0x10, garbage))
    val results = noCompressionCodec.readFrames(frames)
    results should have size 1
    results.head shouldBe a[Left[_, _]]
  }

  it should "return Left for a single-byte 0xFF payload on GetBlockHeadersCode (0x13)" taggedAs
    (UnitTest, NetworkTest) in {
      val frames = Seq(frame(0x13, Array[Byte](0xff.toByte)))
      val results = noCompressionCodec.readFrames(frames)
      results should have size 1
      results.head shouldBe a[Left[_, _]]
    }

  it should "return Left for a valid RLP null (0x80) payload on BlockHeadersCode (0x14)" taggedAs
    (UnitTest, NetworkTest) in {
      // 0x80 is a valid RLP encoding of empty bytes — not a valid BlockHeaders structure
      val frames = Seq(frame(0x14, Array[Byte](0x80.toByte)))
      val results = noCompressionCodec.readFrames(frames)
      results should have size 1
      results.head shouldBe a[Left[_, _]]
    }

  // -----------------------------------------------------------------------
  // Multiple frames in one call
  // -----------------------------------------------------------------------

  behavior of "MessageCodec.readFrames with mixed valid and malformed frames"

  it should "return one Left per malformed frame and process all frames independently" taggedAs
    (UnitTest, NetworkTest) in {
      val allGarbage = Seq(
        frame(0xff, Array.fill(10)(0xab.toByte)),
        frame(0x10, Array.emptyByteArray),
        frame(0x1000, Array[Byte](0xc0.toByte))
      )
      val results = noCompressionCodec.readFrames(allGarbage)
      results should have size 3
      all(results) shouldBe a[Left[_, _]]
    }

  // -----------------------------------------------------------------------
  // Property-based: random payloads never throw
  // -----------------------------------------------------------------------

  behavior of "MessageCodec.readFrames (property-based)"

  it should "never throw for any payload on any type code" taggedAs (UnitTest, NetworkTest) in {
    val frameGen = for {
      typeCode <- Gen.choose(0x00, 0x1fff)
      payloadSize <- Gen.choose(0, 512)
      payload <- Gen.listOfN(payloadSize, Arbitrary.arbitrary[Byte])
    } yield frame(typeCode, payload.toArray)

    forAll(Gen.nonEmptyListOf(frameGen)) { (frames: List[Frame]) =>
      noException should be thrownBy {
        noCompressionCodec.readFrames(frames)
      }
    }
  }

  it should "return Left for every frame with random-byte payloads on a known type code" taggedAs
    (UnitTest, NetworkTest) in {
      val payloadGen: Gen[Array[Byte]] = for {
        size <- Gen.choose(1, 256)
        bytes <- Gen.listOfN(size, Arbitrary.arbitrary[Byte])
      } yield bytes.toArray

      forAll(payloadGen) { (payload: Array[Byte]) =>
        // 0x10 = StatusCode — any non-conforming payload must decode to Left
        val results = noCompressionCodec.readFrames(Seq(frame(0x10, payload)))
        results should have size 1
        // Either Left (malformed) or Right (accidentally valid RLP) — crucially must NOT throw
        noException should be thrownBy results.head
      }
    }

  // -----------------------------------------------------------------------
  // FrameCodec.readFrames: sub-header-length data (no crypto path exercised)
  // -----------------------------------------------------------------------

  behavior of "FrameCodec.readFrames with truncated data"

  it should "return empty list for 0 bytes without throwing" taggedAs (UnitTest, NetworkTest) in {
    noException should be thrownBy {
      frameCodec.readFrames(ByteString.empty) shouldBe empty
    }
  }

  it should "return empty list for 1 byte without throwing" taggedAs (UnitTest, NetworkTest) in {
    noException should be thrownBy {
      frameCodec.readFrames(ByteString(0xab.toByte)) shouldBe empty
    }
  }

  it should "return empty list for 31 bytes (one byte short of header) without throwing" taggedAs
    (UnitTest, NetworkTest) in {
      val almostHeader = ByteString(Array.fill(31)(0xff.toByte))
      noException should be thrownBy {
        frameCodec.readFrames(almostHeader) shouldBe empty
      }
    }

  it should "return empty list for any data shorter than 32 bytes" taggedAs (UnitTest, NetworkTest) in {
    forAll(Gen.choose(0, 31).flatMap(n => Gen.listOfN(n, Arbitrary.arbitrary[Byte]))) { (bytes: List[Byte]) =>
      noException should be thrownBy {
        frameCodec.readFrames(ByteString(bytes.toArray)) shouldBe empty
      }
    }
  }
}
