package com.chipprbots.ethereum.domain

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.rlp // Package object for encode/decode methods
import com.chipprbots.ethereum.rlp.RLPImplicits.given
import com.chipprbots.ethereum.rlp._
import com.chipprbots.ethereum.testing.Tags._

class BigIntSerializationSpec extends AnyFlatSpec with Matchers {

  "ArbitraryIntegerMpt.bigIntSerializer" should "handle empty byte arrays" in {
    val emptyBytes = Array.empty[Byte]
    val result = ArbitraryIntegerMpt.bigIntSerializer.fromBytes(emptyBytes)
    result shouldBe BigInt(0)
  }

  it should "handle zero value serialization round-trip" in {
    val zero = BigInt(0)
    val bytes = ArbitraryIntegerMpt.bigIntSerializer.toBytes(zero)
    val deserialized = ArbitraryIntegerMpt.bigIntSerializer.fromBytes(bytes)
    deserialized shouldBe zero
  }

  it should "handle single zero byte" in {
    val singleZeroByte = Array[Byte](0)
    val result = ArbitraryIntegerMpt.bigIntSerializer.fromBytes(singleZeroByte)
    result shouldBe BigInt(0)
  }

  it should "handle multiple zero bytes" in {
    val multipleZeroBytes = Array[Byte](0, 0, 0, 0)
    val result = ArbitraryIntegerMpt.bigIntSerializer.fromBytes(multipleZeroBytes)
    result shouldBe BigInt(0)
  }

  it should "handle positive values correctly" in {
    val value = BigInt(12345)
    val bytes = ArbitraryIntegerMpt.bigIntSerializer.toBytes(value)
    val deserialized = ArbitraryIntegerMpt.bigIntSerializer.fromBytes(bytes)
    deserialized shouldBe value
  }

  it should "handle large positive values" in {
    val largeValue = BigInt("123456789012345678901234567890")
    val bytes = ArbitraryIntegerMpt.bigIntSerializer.toBytes(largeValue)
    val deserialized = ArbitraryIntegerMpt.bigIntSerializer.fromBytes(bytes)
    deserialized shouldBe largeValue
  }

  it should "handle negative values correctly" in {
    val negativeValue = BigInt(-12345)
    val bytes = ArbitraryIntegerMpt.bigIntSerializer.toBytes(negativeValue)
    val deserialized = ArbitraryIntegerMpt.bigIntSerializer.fromBytes(bytes)
    deserialized shouldBe negativeValue
  }

  "EthereumUInt256Mpt.rlpBigIntSerializer" should "handle empty byte arrays" in {
    // Test that the RLP decoder can handle empty bytes directly
    val emptyBytes = Array.empty[Byte]
    val emptyRlpValue = RLPValue(emptyBytes)

    // Decoding empty RLPValue should work without throwing exceptions
    val decoded = RLPImplicits.bigIntEncDec.decode(emptyRlpValue)
    decoded shouldBe BigInt(0)
  }

  it should "handle zero value round-trip through RLP" in {
    val zero = BigInt(0)
    val encoded = rlp.encode[BigInt](zero)
    val decoded = rlp.decode[BigInt](encoded)
    decoded shouldBe zero
  }

  it should "handle RLP encoded empty value" in {
    // Simulate RLP-encoded empty value (0x80 is RLP encoding of empty string)
    val rlpEmptyEncoded = Array[Byte](0x80.toByte)
    val decoded = rlp.decode[BigInt](rlpEmptyEncoded)
    decoded shouldBe BigInt(0)
  }

  "ByteUtils" should "handle empty ByteString to BigInt conversion" in {
    val emptyByteString = ByteString.empty
    val result = com.chipprbots.ethereum.utils.ByteUtils.toBigInt(emptyByteString)
    result shouldBe BigInt(0)
  }

  it should "handle zero byte in ByteString" in {
    val singleZero = ByteString(0)
    val result = com.chipprbots.ethereum.utils.ByteUtils.toBigInt(singleZero)
    result shouldBe BigInt(0)
  }

  it should "handle multiple zeros in ByteString" in {
    val multipleZeros = ByteString(0, 0, 0, 0)
    val result = com.chipprbots.ethereum.utils.ByteUtils.toBigInt(multipleZeros)
    result shouldBe BigInt(0)
  }

  "UInt256" should "handle empty byte array construction" in {
    val emptyBytes = Array.empty[Byte]
    val result = UInt256(emptyBytes)
    result shouldBe UInt256.Zero
  }

  it should "handle empty ByteString construction" in {
    val emptyByteString = ByteString.empty
    val result = UInt256(emptyByteString)
    result shouldBe UInt256.Zero
  }

  it should "handle zero byte array" in {
    val zeroBytes = Array[Byte](0)
    val result = UInt256(zeroBytes)
    result shouldBe UInt256.Zero
  }

  it should "handle multiple zero bytes" in {
    val multipleZeros = Array.fill[Byte](32)(0)
    val result = UInt256(multipleZeros)
    result shouldBe UInt256.Zero
  }

  "Network sync edge cases" should "handle empty values in state storage" in {
    // This simulates the actual network sync scenario where empty byte arrays
    // might be stored in the state storage and need to be deserialized

    // Test ArbitraryIntegerMpt serializer with empty input
    val emptyInput = Array.empty[Byte]
    val deserializedValue = ArbitraryIntegerMpt.bigIntSerializer.fromBytes(emptyInput)
    deserializedValue shouldBe BigInt(0)

    // Test that we can serialize and deserialize zero
    val zero = BigInt(0)
    val serialized = ArbitraryIntegerMpt.bigIntSerializer.toBytes(zero)
    val roundTrip = ArbitraryIntegerMpt.bigIntSerializer.fromBytes(serialized)
    roundTrip shouldBe zero
  }

  it should "handle empty RLP values from network" in {
    // Simulate receiving empty RLP-encoded values from network peers
    val emptyRlpValue = RLPValue(Array.empty[Byte])
    val decoded = RLPImplicits.bigIntEncDec.decode(emptyRlpValue)
    decoded shouldBe BigInt(0)

    // Test full encode/decode cycle
    val zero = BigInt(0)
    val encoded = rlp.encode[BigInt](zero)
    val roundTrip = rlp.decode[BigInt](encoded)
    roundTrip shouldBe zero
  }

  it should "handle RLP encoding of zero according to Ethereum spec" in {
    // According to Ethereum RLP spec, integer 0 is encoded as empty byte string (0x80)
    val zero = BigInt(0)
    val encoded = rlp.encode[BigInt](zero)

    // The encoding should be 0x80 (empty byte string in RLP)
    encoded shouldBe Array[Byte](0x80.toByte)

    // Decoding should return zero
    val decoded = rlp.decode[BigInt](encoded)
    decoded shouldBe zero
  }

  it should "handle all integer serialization paths consistently" in {
    // Test that all serialization paths handle zero consistently
    val zero = BigInt(0)

    // Path 1: RLP (network protocol)
    val rlpEncoded = rlp.encode[BigInt](zero)
    val rlpDecoded = rlp.decode[BigInt](rlpEncoded)
    rlpDecoded shouldBe zero

    // Path 2: ArbitraryIntegerMpt (internal storage)
    val arbitraryEncoded = ArbitraryIntegerMpt.bigIntSerializer.toBytes(zero)
    val arbitraryDecoded = ArbitraryIntegerMpt.bigIntSerializer.fromBytes(arbitraryEncoded)
    arbitraryDecoded shouldBe zero

    // Path 3: ByteUtils (utility conversions)
    val emptyByteString = ByteString.empty
    val byteUtilsDecoded = com.chipprbots.ethereum.utils.ByteUtils.toBigInt(emptyByteString)
    byteUtilsDecoded shouldBe zero
  }

  it should "never throw NumberFormatException for any byte array" in {
    // This is the critical test - ensure we never throw the error that was reported
    val testCases = Seq(
      Array.empty[Byte],
      Array[Byte](0),
      Array[Byte](0, 0),
      Array[Byte](0, 0, 0, 0)
    )

    testCases.foreach { bytes =>
      // Should not throw NumberFormatException
      noException should be thrownBy {
        ArbitraryIntegerMpt.bigIntSerializer.fromBytes(bytes)
      }
    }
  }
}
