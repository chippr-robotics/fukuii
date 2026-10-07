package com.chipprbots.ethereum.crypto

import org.apache.pekko.util.ByteString

import org.bouncycastle.crypto.params.ECPublicKeyParameters
import org.bouncycastle.util.encoders.Hex
import org.scalacheck.Arbitrary
import org.scalacheck.Arbitrary.arbitrary
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.ByteStringUtils

class ECDSASignatureSpec extends AnyFlatSpec with Matchers with ScalaCheckPropertyChecks with SecureRandomBuilder {
  "ECDSASignature" should "recover public key correctly for go ethereum transaction" taggedAs (
    UnitTest,
    CryptoTest
  ) in {
    val bytesToSign = Hex.decode("5a1465f4683bf2c18fc72c0789239c0f52b3ceac666ca9551cf265a11abe912c")
    val signatureRandom = ByteString(Hex.decode("f3af65a23fbf207b933d3c962381aa50e0ac19649c59c1af1655e592a8d95401"))
    val signature = ByteString(Hex.decode("53629a403579f5ce57bcbefba2616b1c6156d308ddcd37372c94943fdabeda97"))
    val pointSign = 28

    val sig =
      ECDSASignature(signatureRandom, signature, pointSign.toByte)

    sig.publicKey(bytesToSign).isEmpty shouldBe false
  }

  it should "not recover a public key when recovery yields the point at infinity" taggedAs (UnitTest, CryptoTest) in {
    // Q = r^-1 (s*R - e*G). R = G (r = Gx, even y -> v = 27), s = 1, e = 1 gives Q = O; so does the mirror R = -G
    // (v = 28) with s = n - 1. libsecp256k1 secp256k1_ecdsa_sig_recover returns 0 for Q = O, so go-ethereum/core-geth
    // Ecrecover fails. It must be None here, not the empty encoding (whose keccak256 is a fake address).
    val n = BigInt("fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141", 16)
    val gx = BigInt("79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798", 16)
    val hash = Array.fill[Byte](31)(0) :+ 1.toByte

    ECDSASignature(gx, BigInt(1), BigInt(27)).publicKey(hash) shouldBe None
    ECDSASignature(gx, n - 1, BigInt(28)).publicKey(hash) shouldBe None
    // Same R and s with a different hash is an ordinary recovery.
    ECDSASignature(gx, BigInt(1), BigInt(27)).publicKey(Array.fill[Byte](31)(0) :+ 2.toByte).map(_.length) shouldBe
      Some(64)
  }

  it should "fail on case from transaction 74c45d0cf2332cc021bebdfee6b1c1da0b58e8f4154537adb79b025f722920a4" taggedAs (
    UnitTest,
    CryptoTest
  ) in {
    val bytesToSign = Hex.decode("2bb3925f178aa22c11435c61899e134fb7b1227016274b5f7b9d85c4469130ba")
    val signatureRandom = ByteString(Hex.decode("fbe3df0cf030655d817a89936850d1cc00c07c35d3b21be73cfe9a730ea8b753"))
    val signature = ByteString(Hex.decode("62d73b6a92ac23ff514315fad795bbac6d485481d356329d71467e93c87dfa42"))
    val pointSign = 0x1f

    val sig =
      ECDSASignature(signatureRandom, signature, pointSign.toByte)

    sig.publicKey(bytesToSign).isEmpty shouldBe true
  }

  it should "fail public key recover without throwing when signature is bad (Invalid point compression)" taggedAs (
    UnitTest,
    CryptoTest
  ) in {
    val sig = ECDSASignature(
      ByteStringUtils.string2hash("149a2046f51f5d043633664d76eef4f99cdba8e53851dcda57224dfe8770f98a"),
      ByteStringUtils.string2hash("a8898478e9aae9fadb71c7ab5451d47d2efa4199fc26ecc1da62ce8fb77e06f1"),
      28.toByte
    )
    val messageHash = ByteStringUtils.string2hash("a1ede9cdf0b6fe37a384b265dce6b74a7464f11799dcee022f628450a19cf4eb")

    sig.publicKey(messageHash).isEmpty shouldBe true
  }

  it should "sign message and recover public key" taggedAs (UnitTest, CryptoTest) in {
    forAll(arbitrary[Array[Byte]], Arbitrary.arbitrary[Unit].map(_ => generateKeyPair(secureRandom))) {
      (message, keys) =>
        val pubKey = keys.getPublic.asInstanceOf[ECPublicKeyParameters].getQ
        val msg = kec256(message)

        val signature = ECDSASignature.sign(msg, keys)
        val recPubKey = signature.publicKey(msg)

        val result = recPubKey
          .map(a => ECDSASignature.UncompressedIndicator +: a)
          .map(curve.getCurve.decodePoint)
          .map(_.getEncoded(true))
          .map(ByteString(_))
        val expected = Some(pubKey.getEncoded(true)).map(ByteString(_))

        result shouldBe expected
    }
  }
}
