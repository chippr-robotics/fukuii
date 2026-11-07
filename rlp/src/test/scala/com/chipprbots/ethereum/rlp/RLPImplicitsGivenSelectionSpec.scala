package com.chipprbots.ethereum.rlp

import org.apache.pekko.util.ByteString

import org.scalatest.funsuite.AnyFunSuite

import com.chipprbots.ethereum.rlp.RLPImplicits.given
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.Hex

/** Spec 020 DC-3: pins the encoding, and the given that implicit search selects, for every `RLPImplicits` type that
  * carried a named intersection given plus anonymous `RLPEncoder`/`RLPDecoder` duplicates (Byte, Short, Int, BigInt,
  * Long, String, Array[Byte], ByteString, Boolean, Seq[T]), and for the givens built on top of them (Option, List,
  * tuples).
  *
  * The expected bytes were computed by an independent RLP implementation and pass on the tree before the duplicates
  * were removed, so they are the pre-change encoding. Every value goes through the package-level `encode`/`decode` with
  * `RLPImplicits.given` imported, so the test exercises given resolution, not the codec objects directly.
  */
class RLPImplicitsGivenSelectionSpec extends AnyFunSuite:

  private val Lorem = "Lorem ipsum dolor sit amet, consectetur adipisicing elit" // 56 bytes: first long-string form
  private val LoremHex =
    "4c6f72656d20697073756d20646f6c6f722073697420616d65742c20" + "636f6e7365637465747572206164697069736963696e6720656c6974"

  private def roundTrip[T](value: T, expectedHex: String)(using enc: RLPEncoder[T], dec: RLPDecoder[T]): Unit =
    val bytes = encode[T](value)
    assert(Hex.toHexString(bytes) == expectedHex, s"encode($value)")
    assert(decode[T](bytes) == value, s"decode($expectedHex)")

  private def roundTripBytes(value: Array[Byte], expectedHex: String)(using
      enc: RLPEncoder[Array[Byte]],
      dec: RLPDecoder[Array[Byte]]
  ): Unit =
    val bytes = encode[Array[Byte]](value)
    assert(Hex.toHexString(bytes) == expectedHex, s"encode(${Hex.toHexString(value)})")
    assert(decode[Array[Byte]](bytes).sameElements(value), s"decode($expectedHex)")

  test("scalar types resolve RLPEncoder and RLPDecoder to the named intersection given", UnitTest, RLPTest) {
    assert(summon[RLPEncoder[Byte]] eq RLPImplicits.byteEncDec)
    assert(summon[RLPDecoder[Byte]] eq RLPImplicits.byteEncDec)
    assert(summon[RLPEncoder[Short]] eq RLPImplicits.shortEncDec)
    assert(summon[RLPDecoder[Short]] eq RLPImplicits.shortEncDec)
    assert(summon[RLPEncoder[Int]] eq RLPImplicits.intEncDec)
    assert(summon[RLPDecoder[Int]] eq RLPImplicits.intEncDec)
    assert(summon[RLPEncoder[BigInt]] eq RLPImplicits.bigIntEncDec)
    assert(summon[RLPDecoder[BigInt]] eq RLPImplicits.bigIntEncDec)
    assert(summon[RLPEncoder[Long]] eq RLPImplicits.longEncDec)
    assert(summon[RLPDecoder[Long]] eq RLPImplicits.longEncDec)
    assert(summon[RLPEncoder[String]] eq RLPImplicits.stringEncDec)
    assert(summon[RLPDecoder[String]] eq RLPImplicits.stringEncDec)
    assert(summon[RLPEncoder[Array[Byte]]] eq RLPImplicits.byteArrayEncDec)
    assert(summon[RLPDecoder[Array[Byte]]] eq RLPImplicits.byteArrayEncDec)
    assert(summon[RLPEncoder[ByteString]] eq RLPImplicits.byteStringEncDec)
    assert(summon[RLPDecoder[ByteString]] eq RLPImplicits.byteStringEncDec)
    assert(summon[RLPEncoder[Boolean]] eq RLPImplicits.booleanEncDec)
    assert(summon[RLPDecoder[Boolean]] eq RLPImplicits.booleanEncDec)
  }

  test("Byte encodes to the pre-change bytes and round-trips", UnitTest, RLPTest) {
    roundTrip[Byte](0, "80")
    roundTrip[Byte](1, "01")
    roundTrip[Byte](0x7f, "7f")
    roundTrip[Byte](-128, "8180")
    roundTrip[Byte](-1, "81ff")
  }

  test("Short encodes to the pre-change bytes and round-trips", UnitTest, RLPTest) {
    roundTrip[Short](0, "80")
    roundTrip[Short](1, "01")
    roundTrip[Short](0x7f, "7f")
    roundTrip[Short](0x80, "8180")
    roundTrip[Short](0xff, "81ff")
    roundTrip[Short](0x100, "820100")
    roundTrip[Short](Short.MaxValue, "827fff")
    roundTrip[Short](-1, "82ffff")
  }

  test("Int encodes to the pre-change bytes and round-trips", UnitTest, RLPTest) {
    roundTrip[Int](0, "80")
    roundTrip[Int](1, "01")
    roundTrip[Int](0x7f, "7f")
    roundTrip[Int](0x80, "8180")
    roundTrip[Int](0xff, "81ff")
    roundTrip[Int](0x100, "820100")
    roundTrip[Int](0xffff, "82ffff")
    roundTrip[Int](0x10000, "83010000")
    roundTrip[Int](0xffffff, "83ffffff")
    roundTrip[Int](Int.MaxValue, "847fffffff")
    roundTrip[Int](-1, "84ffffffff")
  }

  test("BigInt encodes to the pre-change bytes and round-trips", UnitTest, RLPTest) {
    roundTrip[BigInt](BigInt(0), "80")
    roundTrip[BigInt](BigInt(1), "01")
    roundTrip[BigInt](BigInt(0x7f), "7f")
    roundTrip[BigInt](BigInt(0x80), "8180")
    roundTrip[BigInt](BigInt(0xff), "81ff")
    roundTrip[BigInt](BigInt(0x100), "820100")
    roundTrip[BigInt](BigInt(2).pow(64), "89010000000000000000")
    roundTrip[BigInt](BigInt(2).pow(256) - 1, "a0" + "ff" * 32)
    roundTrip[BigInt](BigInt(2).pow(448) - 1, "b838" + "ff" * 56)
  }

  test("Long encodes to the pre-change bytes and round-trips", UnitTest, RLPTest) {
    roundTrip[Long](0L, "80")
    roundTrip[Long](1L, "01")
    roundTrip[Long](0x80L, "8180")
    roundTrip[Long](0x100L, "820100")
    roundTrip[Long](0x100000000L, "850100000000")
    roundTrip[Long](Long.MaxValue, "887fffffffffffffff")
  }

  test("String encodes to the pre-change bytes and round-trips", UnitTest, RLPTest) {
    roundTrip[String]("", "80")
    roundTrip[String]("a", "61")
    roundTrip[String]("dog", "83646f67")
    roundTrip[String](Lorem, "b838" + LoremHex)
  }

  test("Array[Byte] encodes to the pre-change bytes and round-trips", UnitTest, RLPTest) {
    roundTripBytes(Array.emptyByteArray, "80")
    roundTripBytes(Hex.decode("00"), "00")
    roundTripBytes(Hex.decode("7f"), "7f")
    roundTripBytes(Hex.decode("80"), "8180")
    roundTripBytes(Hex.decode("deadbeef"), "84deadbeef")
    roundTripBytes(Array.fill[Byte](56)(0), "b838" + "00" * 56)
    val kib = Array.tabulate[Byte](1024)(i => i.toByte)
    roundTripBytes(kib, "b90400" + Hex.toHexString(kib))
  }

  test("ByteString encodes to the pre-change bytes and round-trips", UnitTest, RLPTest) {
    roundTrip[ByteString](ByteString.empty, "80")
    roundTrip[ByteString](ByteString(Hex.decode("00")), "00")
    roundTrip[ByteString](ByteString(Hex.decode("80")), "8180")
    roundTrip[ByteString](ByteString(Hex.decode("deadbeef")), "84deadbeef")
    roundTrip[ByteString](ByteString(Lorem), "b838" + LoremHex)
  }

  test("Boolean encodes to the pre-change bytes and round-trips", UnitTest, RLPTest) {
    roundTrip[Boolean](true, "01")
    roundTrip[Boolean](false, "80")
  }

  test("Seq[T] encodes to the pre-change bytes and round-trips", UnitTest, RLPTest) {
    roundTrip[Seq[Int]](Seq.empty, "c0")
    roundTrip[Seq[Int]](Seq(1, 2, 3), "c3010203")
    roundTrip[Seq[Int]](Seq(0, 128, 1024), "c6808180820400")
    roundTrip[Seq[Byte]](Seq[Byte](0, 1, -1), "c4800181ff")
    roundTrip[Seq[Short]](Seq[Short](1, 0x100), "c401820100")
    roundTrip[Seq[BigInt]](Seq(BigInt(0), BigInt(2).pow(64)), "cb8089010000000000000000")
    roundTrip[Seq[Long]](Seq(1L, 2L), "c20102")
    roundTrip[Seq[Boolean]](Seq(true, false), "c20180")
    roundTrip[Seq[ByteString]](Seq(ByteString("cat"), ByteString("dog")), "c88363617483646f67")
    roundTrip[Seq[String]](Seq(Lorem), "f83ab838" + LoremHex)
    roundTrip[Seq[Seq[Long]]](Seq(Seq.empty, Seq(1L), Seq(1L, 2L)), "c6c0c101c20102")

    val arrays = Seq(Array.emptyByteArray, Hex.decode("deadbeef"))
    val encodedArrays = encode[Seq[Array[Byte]]](arrays)
    assert(Hex.toHexString(encodedArrays) == "c68084deadbeef")
    assert(decode[Seq[Array[Byte]]](encodedArrays).map(_.toSeq) == arrays.map(_.toSeq))
  }

  test("Option, List and tuple givens built on the scalar givens keep their bytes", UnitTest, RLPTest) {
    roundTrip[Option[Int]](Some(5), "c105")
    roundTrip[Option[Int]](None, "c0")
    roundTrip[List[Long]](List(1L, 2L), "c20102")
    roundTrip[(Int, ByteString)]((1, ByteString("cat")), "c50183636174")
  }
