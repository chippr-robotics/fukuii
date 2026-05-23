package com.chipprbots.ethereum.vm

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags._
import com.chipprbots.ethereum.vm.PrecompiledContracts.P256Verify

/** EIP-7951: secp256r1 (P-256) precompile execution behavioral tests.
  *
  * Tests drive [[PrecompiledContracts.P256Verify]] via its `exec` method using Wycheproof ECDSA
  * P-256 SHA-256 test vectors from `/EIPs/assets/eip-7951/test-vectors.json`. Covers valid
  * signature, invalid signature, short input, degenerate r/s values, and an off-curve public key.
  */
// scalastyle:off line.size.limit
class P256VerifySpec extends AnyFlatSpec with Matchers {

  private def h(s: String): ByteString = ByteString(Hex.decode(s))

  // 32-byte success / failure sentinels (EIP-7951: returns 1 on success, 0 on failure)
  private val success32 = h("0000000000000000000000000000000000000000000000000000000000000001")
  private val failure32 = h("0000000000000000000000000000000000000000000000000000000000000000")

  "P256Verify" should "return 0x01 for a valid secp256r1 signature (Wycheproof SHA-256 #1)" taggedAs (OlympiaTest, VMTest) in {
    // Input layout: hash(32) + r(32) + s(32) + Qx(32) + Qy(32) = 160 bytes
    // Source: wycheproof/ecdsa_secp256r1_sha256_p1363_test.json — "signature malleability" #1
    val input = h(
      "bb5a52f42f9c9261ed4361f59422a1e30036e7c32b270c8807a419feca605023" + // hash
      "2ba3a8be6b94d5ec80a6d9d1190a436effe50d85a1eee859b8cc6af9bd5c2e18" + // r
      "4cd60b855d442f5b3c7b11eb6c4e0ae7525fe710fab9aa7c77a67f79e6fadd76" + // s
      "2927b10512bae3eddcfe467828128bad2903269919f7086069c8c4df6c732838" + // Qx
      "c7787964eaac00e5921fb1498a60f4606766b3d9685001558d1a974e7341513e"   // Qy
    )
    P256Verify.exec(input) shouldBe Some(success32)
  }

  it should "return 0x00 for a modified-r/s signature (Wycheproof SHA-256 #3)" taggedAs (OlympiaTest, VMTest) in {
    // Same hash and public key as #1; r and s modified by adding/subtracting the group order.
    val input = h(
      "bb5a52f42f9c9261ed4361f59422a1e30036e7c32b270c8807a419feca605023" + // hash
      "d45c5740946b2a147f59262ee6f5bc90bd01ed280528b62b3aed5fc93f06f739" + // r (modified)
      "b329f479a2bbd0a5c384ee1493b1f5186a87139cac5df4087c134b49156847db" + // s (modified)
      "2927b10512bae3eddcfe467828128bad2903269919f7086069c8c4df6c732838" + // Qx
      "c7787964eaac00e5921fb1498a60f4606766b3d9685001558d1a974e7341513e"   // Qy
    )
    P256Verify.exec(input) shouldBe Some(failure32)
  }

  it should "return Some(empty) for input shorter than 160 bytes" taggedAs (OlympiaTest, VMTest) in {
    // EIP-7951: inputs shorter than 160 bytes are rejected before attempting verification.
    P256Verify.exec(ByteString(new Array[Byte](159))) shouldBe Some(ByteString.empty)
    P256Verify.exec(ByteString.empty)                 shouldBe Some(ByteString.empty)
  }

  it should "return 0x00 for all-zero r and s (Wycheproof SHA-256 #9)" taggedAs (OlympiaTest, VMTest) in {
    // r=0 and s=0 are not valid scalars; JDK P-256 verification rejects them.
    val input = h(
      "bb5a52f42f9c9261ed4361f59422a1e30036e7c32b270c8807a419feca605023" + // hash
      "0000000000000000000000000000000000000000000000000000000000000000" + // r = 0
      "0000000000000000000000000000000000000000000000000000000000000000" + // s = 0
      "2927b10512bae3eddcfe467828128bad2903269919f7086069c8c4df6c732838" + // Qx
      "c7787964eaac00e5921fb1498a60f4606766b3d9685001558d1a974e7341513e"   // Qy
    )
    P256Verify.exec(input) shouldBe Some(failure32)
  }

  it should "return 0x00 when the public key is not on the P-256 curve" taggedAs (OlympiaTest, VMTest) in {
    // (Qx=0, Qy=1) is not a valid point on secp256r1; the JDK throws on key construction,
    // which Secp256r1.verify catches and maps to false.
    val input = h(
      "bb5a52f42f9c9261ed4361f59422a1e30036e7c32b270c8807a419feca605023" + // hash
      "2ba3a8be6b94d5ec80a6d9d1190a436effe50d85a1eee859b8cc6af9bd5c2e18" + // r (valid)
      "4cd60b855d442f5b3c7b11eb6c4e0ae7525fe710fab9aa7c77a67f79e6fadd76" + // s (valid)
      "0000000000000000000000000000000000000000000000000000000000000000" + // Qx = 0
      "0000000000000000000000000000000000000000000000000000000000000001"   // Qy = 1 (not on curve)
    )
    P256Verify.exec(input) shouldBe Some(failure32)
  }
}
// scalastyle:on line.size.limit
