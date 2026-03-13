package com.chipprbots.ethereum.consensus.validators.std

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.ledger.BlockPreparator
import com.chipprbots.ethereum.testing.Tags._

/** EIP-7623: Verify floor data gas calculation. */
class EIP7623FloorDataGasSpec extends AnyFlatSpec with Matchers {

  "calcFloorDataGas" should "return 21000 for empty payload" taggedAs (OlympiaTest, ConsensusTest) in {
    BlockPreparator.calcFloorDataGas(ByteString.empty) shouldBe BigInt(21000)
  }

  it should "calculate correctly for all-nonzero bytes" taggedAs (OlympiaTest, ConsensusTest) in {
    // 1000 nonzero bytes: tokens = 1000*4 + 0 = 4000, floor = 21000 + 4000*10 = 61000
    val payload = ByteString(Array.fill(1000)(0x01.toByte))
    BlockPreparator.calcFloorDataGas(payload) shouldBe BigInt(61000)
  }

  it should "calculate correctly for all-zero bytes" taggedAs (OlympiaTest, ConsensusTest) in {
    // 1000 zero bytes: tokens = 0*4 + 1000 = 1000, floor = 21000 + 1000*10 = 31000
    val payload = ByteString(new Array[Byte](1000))
    BlockPreparator.calcFloorDataGas(payload) shouldBe BigInt(31000)
  }

  it should "calculate correctly for mixed payload" taggedAs (OlympiaTest, ConsensusTest) in {
    // 200 nonzero + 300 zero bytes: tokens = 200*4 + 300 = 1100, floor = 21000 + 1100*10 = 32000
    val payload = ByteString(Array.fill(200)(0xff.toByte) ++ new Array[Byte](300))
    BlockPreparator.calcFloorDataGas(payload) shouldBe BigInt(32000)
  }

  it should "return base cost of 21000 for single zero byte" taggedAs (OlympiaTest, ConsensusTest) in {
    // 1 zero byte: tokens = 0*4 + 1 = 1, floor = 21000 + 1*10 = 21010
    val payload = ByteString(new Array[Byte](1))
    BlockPreparator.calcFloorDataGas(payload) shouldBe BigInt(21010)
  }
}
