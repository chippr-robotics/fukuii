package com.chipprbots.ethereum.vm

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.vm.PrecompiledContracts.P256Verify

/** EIP-7951: secp256r1 (P-256) precompile execution behavioral tests.
  *
  * Tests drive [[PrecompiledContracts.P256Verify]] via its `exec` method using Wycheproof ECDSA P-256 SHA-256 test
  * vectors. Covers valid signature, invalid signature, short and long input, degenerate r/s values, out-of-range
  * components, and an off-curve public key.
  *
  * Every failure is EMPTY output, as in execution-specs `osaka/vm/precompiled_contracts/p256verify.py` and go-ethereum
  * `p256Verify.Run` (`return nil, nil`): the call succeeds and RETURNDATASIZE is 0. These tests used to expect a
  * 32-byte zero word for a signature that does not verify, which is what fukuii returned (#1439).
  */
// scalastyle:off line.size.limit
class P256VerifySpec extends AnyFlatSpec with Matchers:

  private def h(s: String): ByteString = ByteString(Hex.decode(s))

  private val success32 = h("0000000000000000000000000000000000000000000000000000000000000001")

  // Wycheproof ECDSA P-256 SHA-256 #1, the valid vector below, split into its five words.
  private val hash = "bb5a52f42f9c9261ed4361f59422a1e30036e7c32b270c8807a419feca605023"
  private val r = "2ba3a8be6b94d5ec80a6d9d1190a436effe50d85a1eee859b8cc6af9bd5c2e18"
  private val s = "4cd60b855d442f5b3c7b11eb6c4e0ae7525fe710fab9aa7c77a67f79e6fadd76"
  private val qx = "2927b10512bae3eddcfe467828128bad2903269919f7086069c8c4df6c732838"
  private val qy = "c7787964eaac00e5921fb1498a60f4606766b3d9685001558d1a974e7341513e"

  // execution-specs SECP256R1N and SECP256R1P.
  private val n = "ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551"
  private val p = "ffffffff00000001000000000000000000000000ffffffffffffffffffffffff"

  "P256Verify" should "return 0x01 for a valid secp256r1 signature (Wycheproof SHA-256 #1)" taggedAs (
    OlympiaTest,
    VMTest
  ) in {
    // Input layout: hash(32) + r(32) + s(32) + Qx(32) + Qy(32) = 160 bytes
    val input = h(
      "bb5a52f42f9c9261ed4361f59422a1e30036e7c32b270c8807a419feca605023" + // hash
        "2ba3a8be6b94d5ec80a6d9d1190a436effe50d85a1eee859b8cc6af9bd5c2e18" + // r
        "4cd60b855d442f5b3c7b11eb6c4e0ae7525fe710fab9aa7c77a67f79e6fadd76" + // s
        "2927b10512bae3eddcfe467828128bad2903269919f7086069c8c4df6c732838" + // Qx
        "c7787964eaac00e5921fb1498a60f4606766b3d9685001558d1a974e7341513e" // Qy
    )
    P256Verify.exec(input) shouldBe Some(success32)
  }

  it should "return empty output for a modified-r/s signature (Wycheproof SHA-256 #3)" taggedAs (
    OlympiaTest,
    VMTest
  ) in {
    val input = h(
      "bb5a52f42f9c9261ed4361f59422a1e30036e7c32b270c8807a419feca605023" + // hash
        "d45c5740946b2a147f59262ee6f5bc90bd01ed280528b62b3aed5fc93f06f739" + // r (modified)
        "b329f479a2bbd0a5c384ee1493b1f5186a87139cac5df4087c134b49156847db" + // s (modified)
        "2927b10512bae3eddcfe467828128bad2903269919f7086069c8c4df6c732838" + // Qx
        "c7787964eaac00e5921fb1498a60f4606766b3d9685001558d1a974e7341513e" // Qy
    )
    P256Verify.exec(input) shouldBe Some(ByteString.empty)
  }

  it should "return Some(empty) for input shorter than 160 bytes" taggedAs (OlympiaTest, VMTest) in {
    P256Verify.exec(ByteString(new Array[Byte](159))) shouldBe Some(ByteString.empty)
    P256Verify.exec(ByteString.empty) shouldBe Some(ByteString.empty)
  }

  it should "return empty output for input longer than 160 bytes, even when its first 160 bytes verify" taggedAs (
    OlympiaTest,
    VMTest
  ) in {
    // execution-specs: `if len(data) != 160: return`. fukuii used to verify the first 160 bytes and answer 0x01.
    P256Verify.exec(h(hash + r + s + qx + qy + "00")) shouldBe Some(ByteString.empty)
    P256Verify.exec(h(hash + r + s + qx + qy + qy)) shouldBe Some(ByteString.empty)
  }

  it should "return empty output for all-zero r and s" taggedAs (OlympiaTest, VMTest) in {
    val input = h(
      "bb5a52f42f9c9261ed4361f59422a1e30036e7c32b270c8807a419feca605023" + // hash
        "0000000000000000000000000000000000000000000000000000000000000000" + // r = 0
        "0000000000000000000000000000000000000000000000000000000000000000" + // s = 0
        "2927b10512bae3eddcfe467828128bad2903269919f7086069c8c4df6c732838" + // Qx
        "c7787964eaac00e5921fb1498a60f4606766b3d9685001558d1a974e7341513e" // Qy
    )
    P256Verify.exec(input) shouldBe Some(ByteString.empty)
  }

  it should "return empty output when the public key is not on the P-256 curve" taggedAs (OlympiaTest, VMTest) in {
    val input = h(
      "bb5a52f42f9c9261ed4361f59422a1e30036e7c32b270c8807a419feca605023" + // hash
        "2ba3a8be6b94d5ec80a6d9d1190a436effe50d85a1eee859b8cc6af9bd5c2e18" + // r (valid)
        "4cd60b855d442f5b3c7b11eb6c4e0ae7525fe710fab9aa7c77a67f79e6fadd76" + // s (valid)
        "0000000000000000000000000000000000000000000000000000000000000000" + // Qx = 0
        "0000000000000000000000000000000000000000000000000000000000000001" // Qy = 1 (not on curve)
    )
    P256Verify.exec(input) shouldBe Some(ByteString.empty)
  }

  it should "return empty output when r or s is outside (0, n)" taggedAs (OlympiaTest, VMTest) in {
    P256Verify.exec(h(hash + n + s + qx + qy)) shouldBe Some(ByteString.empty) // r = n
    P256Verify.exec(h(hash + r + n + qx + qy)) shouldBe Some(ByteString.empty) // s = n
    P256Verify.exec(h(hash + "00" * 32 + s + qx + qy)) shouldBe Some(ByteString.empty) // r = 0
    P256Verify.exec(h(hash + r + "00" * 32 + qx + qy)) shouldBe Some(ByteString.empty) // s = 0
  }

  it should "return empty output when a public-key coordinate is not below p, or the key is (0, 0)" taggedAs (
    OlympiaTest,
    VMTest
  ) in {
    P256Verify.exec(h(hash + r + s + p + qy)) shouldBe Some(ByteString.empty) // qx = p
    P256Verify.exec(h(hash + r + s + qx + p)) shouldBe Some(ByteString.empty) // qy = p
    P256Verify.exec(h(hash + r + s + "00" * 64)) shouldBe Some(ByteString.empty) // (0, 0)
  }

  it should "still verify the valid vector once it is rebuilt from its words" taggedAs (OlympiaTest, VMTest) in {
    P256Verify.exec(h(hash + r + s + qx + qy)) shouldBe Some(success32)
  }
// scalastyle:on line.size.limit
