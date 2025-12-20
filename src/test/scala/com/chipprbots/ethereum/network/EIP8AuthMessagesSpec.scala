package com.chipprbots.ethereum.network

import org.apache.pekko.util.ByteString

import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.params.ECPublicKeyParameters
import org.bouncycastle.util.encoders.Hex
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.crypto._
import com.chipprbots.ethereum.network.rlpx.AuthInitiateMessageV4
import com.chipprbots.ethereum.network.rlpx.AuthInitiateMessageV4._
import com.chipprbots.ethereum.network.rlpx.AuthResponseMessageV4
import com.chipprbots.ethereum.rlp._
import com.chipprbots.ethereum.security.SecureRandomBuilder
import com.chipprbots.ethereum.testing.Tags._

class EIP8AuthMessagesSpec extends AnyFlatSpec with Matchers with SecureRandomBuilder {

  val testKeyPair = new AsymmetricCipherKeyPair(
    new ECPublicKeyParameters(
      curve.getCurve.decodePoint(
        Hex.decode(
          "0491376c89ba75cc51fd6b63af01083e6cc11f5635620527e254a03374738e1eb344b2221470a4638e670a97a06f3b91c4f517ccc325561148b106407671d5c46d"
        )
      ),
      curve
    ),
    new ECPrivateKeyParameters(
      BigInt("105751695959748236927330749967459856049816015502376529986938855740081063876828").bigInteger,
      curve
    )
  )

  val testNonce: ByteString = ByteString(Array.fill[Byte](32)(9.toByte))

  "AuthInitiateMessageV4" should "decode message with extra fields (EIP-8)" taggedAs (UnitTest, NetworkTest) in {
    // Create a valid message with standard 4 fields
    val signature = ECDSASignature(BigInt(123), BigInt(456), BigInt(0))
    val publicKey = testKeyPair.getPublic.asInstanceOf[ECPublicKeyParameters].getQ
    val nonce = testNonce
    val version = 4

    val standardMsg = AuthInitiateMessageV4(signature, publicKey, nonce, version)
    val standardEncoded = encode(standardMsg.toRLPEncodable)

    // Decode the standard message - should work
    val decodedStandard = standardEncoded.toAuthInitiateMessageV4
    decodedStandard.signature shouldBe signature
    decodedStandard.publicKey shouldBe publicKey
    decodedStandard.nonce shouldBe nonce
    decodedStandard.version shouldBe version

    // Now create a message with extra fields (simulating EIP-8 message from Go-Ethereum/CoreGeth)
    val rlpWithExtraField = RLPList(
      RLPValue(encodeECDSA(signature).toArray),
      RLPValue(publicKey.getEncoded(false).drop(1)),
      RLPValue(nonce.toArray),
      RLPValue(Array(version.toByte)),
      RLPValue(Array[Byte](1, 2, 3)), // Extra field #1 - should be ignored
      RLPValue(Array[Byte](4, 5, 6))  // Extra field #2 - should be ignored
    )
    val encodedWithExtra = encode(rlpWithExtraField)

    // Decode the message with extra fields - should work and ignore extra fields
    val decodedWithExtra = encodedWithExtra.toAuthInitiateMessageV4
    decodedWithExtra.signature shouldBe signature
    decodedWithExtra.publicKey shouldBe publicKey
    decodedWithExtra.nonce shouldBe nonce
    decodedWithExtra.version shouldBe version
  }

  it should "decode message with exactly one extra field (EIP-8)" taggedAs (UnitTest, NetworkTest) in {
    // This specifically tests the issue reported: "WARNING arguments left: 1"
    val signature = ECDSASignature(BigInt(789), BigInt(101112), BigInt(0))
    val publicKey = testKeyPair.getPublic.asInstanceOf[ECPublicKeyParameters].getQ
    val nonce = testNonce
    val version = 5

    val rlpWithOneExtraField = RLPList(
      RLPValue(encodeECDSA(signature).toArray),
      RLPValue(publicKey.getEncoded(false).drop(1)),
      RLPValue(nonce.toArray),
      RLPValue(Array(version.toByte)),
      RLPValue(Array[Byte](0xFF.toByte)) // One extra field - mimics CoreGeth's behavior
    )
    val encoded = encode(rlpWithOneExtraField)

    // Should decode successfully and ignore the extra field
    val decoded = encoded.toAuthInitiateMessageV4
    decoded.signature shouldBe signature
    decoded.publicKey shouldBe publicKey
    decoded.nonce shouldBe nonce
    decoded.version shouldBe version
  }

  it should "decode message with random padding bytes after RLP structure (EIP-8)" taggedAs (UnitTest, NetworkTest) in {
    // This test validates the actual EIP-8 issue: padding bytes OUTSIDE the RLP list
    // Core Geth and go-ethereum add random padding after the RLP structure for security
    val signature = ECDSASignature(BigInt(123), BigInt(456), BigInt(0))
    val publicKey = testKeyPair.getPublic.asInstanceOf[ECPublicKeyParameters].getQ
    val nonce = testNonce
    val version = 4

    val standardMsg = AuthInitiateMessageV4(signature, publicKey, nonce, version)
    val standardEncoded = encode(standardMsg.toRLPEncodable)

    // Add random padding bytes AFTER the complete RLP structure
    // This is what EIP-8 allows and what was causing "arguments left" errors
    val paddingBytes = Array[Byte](0x99.toByte, 0xAA.toByte, 0xBB.toByte, 0xCC.toByte)
    val encodedWithPadding = standardEncoded ++ paddingBytes

    // Should decode successfully and ignore the padding bytes
    val decoded = encodedWithPadding.toAuthInitiateMessageV4
    decoded.signature shouldBe signature
    decoded.publicKey shouldBe publicKey
    decoded.nonce shouldBe nonce
    decoded.version shouldBe version
  }

  "AuthResponseMessageV4" should "decode message with extra fields (EIP-8)" taggedAs (UnitTest, NetworkTest) in {
    val ephemeralPublicKey = testKeyPair.getPublic.asInstanceOf[ECPublicKeyParameters].getQ
    val nonce = testNonce
    val version = 4

    // Standard message
    val standardMsg = AuthResponseMessageV4(ephemeralPublicKey, nonce, version)
    val standardEncoded = encode(standardMsg)

    // Decode standard - should work
    val decodedStandard = decode[AuthResponseMessageV4](standardEncoded)
    decodedStandard.ephemeralPublicKey shouldBe ephemeralPublicKey
    decodedStandard.nonce shouldBe nonce
    decodedStandard.version shouldBe version

    // Message with extra fields
    val rlpWithExtraFields = RLPList(
      RLPValue(ephemeralPublicKey.getEncoded(false).drop(1)),
      RLPValue(nonce.toArray),
      RLPValue(Array(version.toByte)), // Use consistent encoding with other tests
      RLPValue(Array[Byte](7, 8, 9)), // Extra field - should be ignored
      RLPList(RLPValue(Array[Byte](10))) // Extra nested field - should be ignored
    )
    val encodedWithExtra = encode(rlpWithExtraFields)

    // Decode with extra fields - should work
    val decodedWithExtra = decode[AuthResponseMessageV4](encodedWithExtra)
    decodedWithExtra.ephemeralPublicKey shouldBe ephemeralPublicKey
    decodedWithExtra.nonce shouldBe nonce
    decodedWithExtra.version shouldBe version
  }

  it should "decode message with random padding bytes after RLP structure (EIP-8)" taggedAs (UnitTest, NetworkTest) in {
    // This test validates the actual EIP-8 issue: padding bytes OUTSIDE the RLP list
    // Core Geth and go-ethereum add random padding after the RLP structure for security
    val ephemeralPublicKey = testKeyPair.getPublic.asInstanceOf[ECPublicKeyParameters].getQ
    val nonce = testNonce
    val version = 4

    val standardMsg = AuthResponseMessageV4(ephemeralPublicKey, nonce, version)
    val standardEncoded = encode(standardMsg)

    // Add random padding bytes AFTER the complete RLP structure
    // This is what EIP-8 allows and what was causing decode errors
    val paddingBytes = Array[Byte](0xDE.toByte, 0xAD.toByte, 0xBE.toByte, 0xEF.toByte)
    val encodedWithPadding = standardEncoded ++ paddingBytes

    // Should decode successfully and ignore the padding bytes
    val decoded = decode[AuthResponseMessageV4](encodedWithPadding)
    decoded.ephemeralPublicKey shouldBe ephemeralPublicKey
    decoded.nonce shouldBe nonce
    decoded.version shouldBe version
  }

  it should "fail to decode message with too few fields" taggedAs (UnitTest, NetworkTest) in {
    // Message with only 2 fields (missing version)
    val ephemeralPublicKey = testKeyPair.getPublic.asInstanceOf[ECPublicKeyParameters].getQ
    val nonce = testNonce

    val rlpWithTooFewFields = RLPList(
      RLPValue(ephemeralPublicKey.getEncoded(false).drop(1)),
      RLPValue(nonce.toArray)
      // Missing version field
    )
    val encoded = encode(rlpWithTooFewFields)

    // Should throw an exception
    assertThrows[RuntimeException] {
      decode[AuthResponseMessageV4](encoded)
    }
  }

  "AuthInitiateMessageV4" should "fail to decode message with too few fields" taggedAs (UnitTest, NetworkTest) in {
    val signature = ECDSASignature(BigInt(123), BigInt(456), BigInt(0))
    val publicKey = testKeyPair.getPublic.asInstanceOf[ECPublicKeyParameters].getQ
    val nonce = testNonce

    // Message with only 3 fields (missing version)
    val rlpWithTooFewFields = RLPList(
      RLPValue(encodeECDSA(signature).toArray),
      RLPValue(publicKey.getEncoded(false).drop(1)),
      RLPValue(nonce.toArray)
      // Missing version field
    )
    val encoded = encode(rlpWithTooFewFields)

    // Should throw an exception
    assertThrows[RuntimeException] {
      encoded.toAuthInitiateMessageV4
    }
  }
}
