package com.chipprbots.ethereum.crypto

import java.nio.charset.StandardCharsets

import org.apache.pekko.util.ByteString

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.util.encoders.Hex
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatest.prop.TableFor2
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import com.chipprbots.ethereum.testing.Tags.*

/** `sha256` backs the SHA256 precompile (0x02) on every ETH and ETC fork, so its output is consensus. It runs on the
  * JDK's `MessageDigest`; BouncyCastle's `SHA256Digest`, the implementation it replaced, is kept here as the oracle.
  */
class Sha256Spec extends AnyFunSuite with ScalaCheckPropertyChecks with Matchers {

  // FIPS 180-4 examples (NIST CSRC "SHA-256" example values) plus the SHA256-precompile input of ethereum/tests
  // static_Call50000_sha256: 50,000 zero bytes.
  val examples: TableFor2[String, String] = Table[String, String](
    ("input", "result"),
    ("", "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),
    ("abc", "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"),
    (
      "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq",
      "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1"
    ),
    (
      "abcdefghbcdefghicdefghijdefghijkefghijklfghijklmghijklmnhijklmnoijklmnopjklmnopqklmnopqrlmnopqrsmnopqrstnopqrstu",
      "cf5b16a778af8380036ce59e7b0492370b249b11e8f07a51afac45037afee9d1"
    ),
    ("a" * 1000000, "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0"),
    ("\u0000" * 50000, "5b4b67b5d68e02c992760de07640472efe53a7f7553865f83262d0a74efc3e5d")
  )

  private def bouncyCastleSha256(input: Array[Byte]): Array[Byte] = {
    val digest = new SHA256Digest()
    val out = Array.ofDim[Byte](digest.getDigestSize)
    digest.update(input, 0, input.length)
    digest.doFinal(out, 0)
    out
  }

  test("SHA-256 matches the FIPS 180-4 examples", UnitTest, CryptoTest) {
    forAll(examples) { (input, result) =>
      val inBytes = input.getBytes(StandardCharsets.ISO_8859_1)
      Hex.toHexString(sha256(inBytes)) shouldEqual result
      Hex.toHexString(sha256(ByteString(inBytes)).toArray) shouldEqual result
    }
  }

  test("SHA-256 is byte-identical to SHA256Digest across padding boundaries", UnitTest, CryptoTest) {
    // Every length class the padding rule distinguishes: empty, one block, the 55/56 boundary where the length field
    // spills into a second block, multi-block, and the 50,000-byte precompile input.
    val lengths = Seq(0, 1, 31, 32, 33, 55, 56, 57, 63, 64, 65, 119, 120, 121, 127, 128, 129, 4095, 4096, 4097, 50000)
    val random = new scala.util.Random(256L) // fixed seed: deterministic input
    lengths.foreach { n =>
      val input = Array.ofDim[Byte](n)
      random.nextBytes(input)
      withClue(s"length $n: ") {
        sha256(input).toSeq shouldEqual bouncyCastleSha256(input).toSeq
      }
    }
  }

  test("SHA-256 is byte-identical to SHA256Digest on arbitrary input", UnitTest, CryptoTest) {
    forAll(Generators.getByteStringGen(0, 1024)) { bytes =>
      sha256(bytes).toArray.toSeq shouldEqual bouncyCastleSha256(bytes.toArray).toSeq
    }
  }

}
