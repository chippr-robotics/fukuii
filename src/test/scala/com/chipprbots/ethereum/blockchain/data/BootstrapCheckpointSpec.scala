package com.chipprbots.ethereum.blockchain.data

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class BootstrapCheckpointSpec extends AnyFlatSpec with Matchers {

  "BootstrapCheckpoint.fromString" should "parse valid checkpoint string with 0x prefix" in {
    val checkpointStr = "10500839:0x1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef"
    val result = BootstrapCheckpoint.fromString(checkpointStr)

    result shouldBe defined
    result.get.blockNumber shouldEqual BigInt(10500839)
    result.get.blockHash shouldEqual ByteString(
      org.bouncycastle.util.encoders.Hex.decode("1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef")
    )
  }

  it should "parse valid checkpoint string without 0x prefix" in {
    val checkpointStr = "13189133:abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890"
    val result = BootstrapCheckpoint.fromString(checkpointStr)

    result shouldBe defined
    result.get.blockNumber shouldEqual BigInt(13189133)
    result.get.blockHash shouldEqual ByteString(
      org.bouncycastle.util.encoders.Hex.decode("abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890")
    )
  }

  it should "return None for invalid format (missing colon)" in {
    val checkpointStr = "10500839-0x1234567890abcdef"
    val result = BootstrapCheckpoint.fromString(checkpointStr)

    result shouldBe None
  }

  it should "return None for invalid format (no hash)" in {
    val checkpointStr = "10500839:"
    val result = BootstrapCheckpoint.fromString(checkpointStr)

    result shouldBe None
  }

  it should "return None for invalid block number" in {
    val checkpointStr = "notanumber:0x1234567890abcdef"
    val result = BootstrapCheckpoint.fromString(checkpointStr)

    result shouldBe None
  }

  it should "return None for invalid hex hash" in {
    val checkpointStr = "10500839:notahexstring"
    val result = BootstrapCheckpoint.fromString(checkpointStr)

    result shouldBe None
  }

  it should "return None for empty string" in {
    val checkpointStr = ""
    val result = BootstrapCheckpoint.fromString(checkpointStr)

    result shouldBe None
  }

  it should "handle very large block numbers" in {
    val checkpointStr = "999999999999:0xfedcba0987654321fedcba0987654321fedcba0987654321fedcba0987654321"
    val result = BootstrapCheckpoint.fromString(checkpointStr)

    result shouldBe defined
    result.get.blockNumber shouldEqual BigInt("999999999999")
  }

  "BootstrapCheckpoint" should "correctly represent checkpoint data" in {
    val blockNumber = BigInt(19250000)
    val blockHash = ByteString(Array.fill[Byte](32)(0xff.toByte))
    val checkpoint = BootstrapCheckpoint(blockNumber, blockHash)

    checkpoint.blockNumber shouldEqual blockNumber
    checkpoint.blockHash shouldEqual blockHash
  }
}
