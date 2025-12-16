package com.chipprbots.ethereum.rlp

import org.bouncycastle.util.encoders.Hex
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.utils.ByteUtils

/** Test RLP encoding of numeric values to ensure they match Ethereum/Core-Geth encoding.
  */
class RLPNumericEncodingSpec extends AnyFlatSpec with Matchers {

  "ByteUtils.bigIntToUnsignedByteArray" should "encode zero as empty array" in {
    val zero = BigInt(0)
    val bytes = ByteUtils.bigIntToUnsignedByteArray(zero)
    bytes.length shouldBe 0
    println(s"Zero: ${bytes.length} bytes")
  }

  it should "encode small positive integers correctly" in {
    val one = BigInt(1)
    val bytes = ByteUtils.bigIntToUnsignedByteArray(one)
    bytes shouldBe Array[Byte](0x01)
    println(s"One: ${Hex.toHexString(bytes)}")
  }

  it should "encode difficulty 0x20000 correctly" in {
    val difficulty = BigInt("131072") // 0x20000
    val bytes = ByteUtils.bigIntToUnsignedByteArray(difficulty)
    Hex.toHexString(bytes) shouldBe "020000"
    println(s"Difficulty 0x20000: ${Hex.toHexString(bytes)} ({bytes.length} bytes)")
  }

  it should "encode timestamp as 4 bytes not 8" in {
    val timestamp: Long = 1733402624L  // 0x6751a000
    val timestampBigInt = BigInt(timestamp)
    val bytes = ByteUtils.bigIntToUnsignedByteArray(timestampBigInt)
    
    println(s"Timestamp $timestamp (0x${timestamp.toHexString}):")
    println(s"  BigInt.toByteArray: ${Hex.toHexString(timestampBigInt.toByteArray)}")
    println(s"  After bigIntToUnsignedByteArray: ${Hex.toHexString(bytes)} (${bytes.length} bytes)")
    
    // Should be 4 bytes: 6751a000
    Hex.toHexString(bytes) shouldBe "6751a000"
    bytes.length shouldBe 4
  }

  it should "handle gasLimit correctly" in {
    val gasLimit = BigInt("8000000") // 0x7a1200
    val bytes = ByteUtils.bigIntToUnsignedByteArray(gasLimit)
    println(s"GasLimit 0x7a1200: ${Hex.toHexString(bytes)} (${bytes.length} bytes)")
    Hex.toHexString(bytes) shouldBe "7a1200"
  }
}
