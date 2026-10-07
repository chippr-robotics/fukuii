package com.chipprbots.ethereum.vm

import org.bouncycastle.util.encoders.Hex
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.prop.TableFor2
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import com.chipprbots.ethereum.testing.Tags.*

class BlakeCompressionSpec extends AnyFlatSpec with Matchers with ScalaCheckPropertyChecks:
  // test vectors from: https://eips.ethereum.org/EIPS/eip-152
  val testVectors: TableFor2[String, Option[String]] = Table[String, Option[String]](
    ("value", "result"),
    (
      "00000c48c9bdf267e6096a3ba7ca8485ae67bb2bf894fe72f36e3cf1361d5f3af54fa5d182e6ad7f520e511f6c3e2b8c68059b6bbd41fbabd9831f79217e1319cde05b61626300000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000300000000000000000000000000000001",
      None
    ),
    (
      "000000000c48c9bdf267e6096a3ba7ca8485ae67bb2bf894fe72f36e3cf1361d5f3af54fa5d182e6ad7f520e511f6c3e2b8c68059b6bbd41fbabd9831f79217e1319cde05b61626300000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000300000000000000000000000000000001",
      None
    ),
    (
      "0000000c48c9bdf267e6096a3ba7ca8485ae67bb2bf894fe72f36e3cf1361d5f3af54fa5d182e6ad7f520e511f6c3e2b8c68059b6bbd41fbabd9831f79217e1319cde05b61626300000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000300000000000000000000000000000002",
      None
    ),
    (
      "0000000048c9bdf267e6096a3ba7ca8485ae67bb2bf894fe72f36e3cf1361d5f3af54fa5d182e6ad7f520e511f6c3e2b8c68059b6bbd41fbabd9831f79217e1319cde05b61626300000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000300000000000000000000000000000001",
      Some(
        "08c9bcf367e6096a3ba7ca8485ae67bb2bf894fe72f36e3cf1361d5f3af54fa5d282e6ad7f520e511f6c3e2b8c68059b9442be0454267ce079217e1319cde05b"
      )
    ),
    (
      "0000000c48c9bdf267e6096a3ba7ca8485ae67bb2bf894fe72f36e3cf1361d5f3af54fa5d182e6ad7f520e511f6c3e2b8c68059b6bbd41fbabd9831f79217e1319cde05b61626300000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000300000000000000000000000000000001",
      Some(
        "ba80a53f981c4d0d6a2797b69f12f6e94c212f14685ac4b74b12bb6fdbffa2d17d87c5392aab792dc252d5de4533cc9518d38aa8dbf1925ab92386edd4009923"
      )
    ),
    (
      "0000000c48c9bdf267e6096a3ba7ca8485ae67bb2bf894fe72f36e3cf1361d5f3af54fa5d182e6ad7f520e511f6c3e2b8c68059b6bbd41fbabd9831f79217e1319cde05b61626300000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000300000000000000000000000000000000",
      Some(
        "75ab69d3190a562c51aef8d88f1c2775876944407270c42c9844252c26d2875298743e7f6d5ea2f2d3e8d226039cd31b4e426ac4f2d3d666a610c2116fde4735"
      )
    ),
    (
      "0000000148c9bdf267e6096a3ba7ca8485ae67bb2bf894fe72f36e3cf1361d5f3af54fa5d182e6ad7f520e511f6c3e2b8c68059b6bbd41fbabd9831f79217e1319cde05b61626300000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000300000000000000000000000000000001",
      Some(
        "b63a380cb2897d521994a85234ee2c181b5f844d2c624c002677e9703449d2fba551b3a8333bcdf5f2f7e08993d53923de3d64fcc68c034e717b9293fed7a421"
      )
    )
  )

  "Blake2b compression function" should "handle all test vectors" taggedAs (UnitTest, VMTest) in {
    forAll(testVectors) { (value, expectedResult) =>
      val asBytes = Hex.decode(value)
      val result = Blake2bCompression.blake2bCompress(asBytes)
      val resultAsString = result.map(str => Hex.toHexString(str))
      assert(resultAsString == expectedResult)
    }
  }

  it should "handle empty input" taggedAs (UnitTest, VMTest) in {
    val result = Blake2bCompression.blake2bCompress(Array())
    assert(result.isEmpty)
  }

  // The array-and-`mix` compression `Blake2bCompression` used before it moved to locals, kept as the oracle.
  private object ReferenceF:
    private val IV = Array(0x6a09e667f3bcc908L, 0xbb67ae8584caa73bL, 0x3c6ef372fe94f82bL, 0xa54ff53a5f1d36f1L,
      0x510e527fade682d1L, 0x9b05688c2b3e6c1fL, 0x1f83d9abfb41bd6bL, 0x5be0cd19137e2179L)
    private val Sigma: Array[Array[Int]] = Array(
      Array(0, 2, 4, 6, 1, 3, 5, 7, 8, 10, 12, 14, 9, 11, 13, 15),
      Array(14, 4, 9, 13, 10, 8, 15, 6, 1, 0, 11, 5, 12, 2, 7, 3),
      Array(11, 12, 5, 15, 8, 0, 2, 13, 10, 3, 7, 9, 14, 6, 1, 4),
      Array(7, 3, 13, 11, 9, 1, 12, 14, 2, 5, 4, 15, 6, 10, 0, 8),
      Array(9, 5, 2, 10, 0, 7, 4, 15, 14, 11, 6, 3, 1, 12, 8, 13),
      Array(2, 6, 0, 8, 12, 10, 11, 3, 4, 7, 15, 1, 13, 5, 14, 9),
      Array(12, 1, 14, 4, 5, 15, 13, 10, 0, 6, 9, 8, 7, 3, 2, 11),
      Array(13, 7, 12, 3, 11, 14, 1, 9, 5, 15, 8, 2, 0, 4, 6, 10),
      Array(6, 14, 11, 0, 15, 9, 3, 8, 12, 13, 1, 10, 2, 7, 4, 5),
      Array(10, 8, 7, 1, 2, 4, 6, 5, 15, 9, 3, 13, 11, 14, 12, 0)
    )
    private def le(b: Array[Byte], off: Int): Long =
      (0 until 8).foldRight(0L)((i, acc) => (acc << 8) | (b(off + i) & 0xffL))
    private def mix(v: Array[Long], a: Long, b: Long, i: Int, j: Int, k: Int, l: Int): Unit =
      v(i) += a + v(j)
      v(l) = java.lang.Long.rotateLeft(v(l) ^ v(i), -32)
      v(k) += v(l)
      v(j) = java.lang.Long.rotateLeft(v(j) ^ v(k), -24)
      v(i) += b + v(j)
      v(l) = java.lang.Long.rotateLeft(v(l) ^ v(i), -16)
      v(k) += v(l)
      v(j) = java.lang.Long.rotateLeft(v(j) ^ v(k), -63)
    def apply(input: Array[Byte]): Array[Byte] =
      val rounds = Integer.toUnsignedLong(java.nio.ByteBuffer.wrap(input, 0, 4).getInt)
      val h = Array.tabulate(8)(i => le(input, 4 + 8 * i))
      val m = Array.tabulate(16)(i => le(input, 68 + 8 * i))
      val v = h ++ IV
      v(12) ^= le(input, 196)
      v(13) ^= le(input, 204)
      if input(212) != 0 then v(14) ^= 0xffffffffffffffffL
      var j = 0L
      while j < rounds do
        val s = Sigma((j % 10).toInt)
        mix(v, m(s(0)), m(s(4)), 0, 4, 8, 12)
        mix(v, m(s(1)), m(s(5)), 1, 5, 9, 13)
        mix(v, m(s(2)), m(s(6)), 2, 6, 10, 14)
        mix(v, m(s(3)), m(s(7)), 3, 7, 11, 15)
        mix(v, m(s(8)), m(s(12)), 0, 5, 10, 15)
        mix(v, m(s(9)), m(s(13)), 1, 6, 11, 12)
        mix(v, m(s(10)), m(s(14)), 2, 7, 8, 13)
        mix(v, m(s(11)), m(s(15)), 3, 4, 9, 14)
        j += 1
      (0 until 8).toArray.flatMap { i =>
        val x = h(i) ^ v(i) ^ v(i + 8)
        (0 until 8).map(k => (x >>> (8 * k)).toByte)
      }

  it should "match the array-and-mix compression on arbitrary input and round counts" taggedAs (UnitTest, VMTest) in {
    import org.scalacheck.Gen
    // Every round-count class mod 10 many times over, a final flag of 0 or 1, and arbitrary state/message/counters.
    val inputGen: Gen[Array[Byte]] = for
      rounds <- Gen.oneOf(Gen.choose(0, 30), Gen.choose(0, 3000))
      body <- Gen.listOfN(208, Gen.choose(Byte.MinValue, Byte.MaxValue))
      flag <- Gen.oneOf(0.toByte, 1.toByte)
    yield Array[Byte]((rounds >>> 24).toByte, (rounds >>> 16).toByte, (rounds >>> 8).toByte, rounds.toByte) ++
      body.toArray :+ flag

    forAll(inputGen, minSuccessful(1000)) { input =>
      Blake2bCompression.blake2bCompress(input).map(_.toSeq) shouldBe Some(ReferenceF(input).toSeq)
    }
  }
