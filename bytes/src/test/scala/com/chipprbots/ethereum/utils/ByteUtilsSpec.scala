package com.chipprbots.ethereum.utils

import org.scalacheck.Arbitrary
import org.scalacheck.Gen
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class ByteUtilsSpec extends AnyFunSuite with ScalaCheckPropertyChecks {
  def byteArrayOfNItemsGen(n: Int): Gen[Array[Byte]] =
    Gen.listOfN(n, Arbitrary.arbitrary[Byte]).map(_.toArray)

  test("Convert Bytes to Int in little endian") {
    forAll(byteArrayOfNItemsGen(32)) { bytes =>
      val toInts = ByteUtils.bytesToInts(bytes, bigEndian = false)
      val asBytes = ByteUtils.intsToBytes(toInts, bigEndian = false)
      assert(asBytes.sameElements(bytes))
    }
  }

  test("Convert Bytes to Int in big endian") {
    forAll(byteArrayOfNItemsGen(32)) { bytes =>
      val toInts = ByteUtils.bytesToInts(bytes, bigEndian = true)
      val asBytes = ByteUtils.intsToBytes(toInts, bigEndian = true)
      assert(asBytes.sameElements(bytes))
    }
  }

  test("bytesToBigInt handles empty array") {
    val emptyArray = Array.empty[Byte]
    val result = ByteUtils.bytesToBigInt(emptyArray)
    assert(result == BigInt(0))
  }

  test("bytesToBigInt handles non-empty arrays") {
    val testCases = Seq(
      (Array[Byte](0x01), BigInt(1)),
      (Array[Byte](0x00, 0x01), BigInt(1)),
      (Array[Byte](0x01, 0x00), BigInt(256)),
      (Array[Byte](0xff.toByte), BigInt(255)),
      (Array[Byte](0x01, 0x00, 0x00), BigInt(65536))
    )
    
    testCases.foreach { case (bytes, expected) =>
      val result = ByteUtils.bytesToBigInt(bytes)
      assert(result == expected, s"Failed for bytes ${bytes.mkString("[", ", ", "]")}")
    }
  }
}
