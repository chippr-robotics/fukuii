package com.chipprbots.ethereum.consensus.validators.std

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.consensus.validators.std.StdBlockValidator._
import com.chipprbots.ethereum.testing.Tags._

/** EIP-7934: Verify block RLP size cap validation.
  *
  * EIP-7934 defines MAX_RLP_BLOCK_SIZE = 10 MiB − 2 MiB beacon safety margin = 8,388,608 bytes. The 2 MiB
  * margin is an ETH consensus-layer concern (CL gossip protocol does not propagate blocks above 10 MiB). ETC
  * omits all PoS/beacon elements per ECIP-1121, so the margin does not apply — but Fukuii follows the same
  * 8 MiB enforcement constant as ETH's execution layer for EVM alignment with Fusaka.
  */
class BlockRLPSizeCapSpec extends AnyFlatSpec with Matchers {

  private val MiB: Long = 1024L * 1024

  "BlockRLPSizeCap constant" should "equal MAX_BLOCK_SIZE − BEACON_MARGIN per EIP-7934 (10 MiB − 2 MiB = 8 MiB)" taggedAs (
    OlympiaTest,
    ConsensusTest
  ) in {
    val maxBlockSize  = 10 * MiB // EIP-7934 MAX_BLOCK_SIZE
    val beaconMargin  =  2 * MiB // EIP-7934 SAFETY_MARGIN (ETH CL, omitted on ETC)
    StdBlockValidator.BlockRLPSizeCap shouldBe maxBlockSize - beaconMargin
    StdBlockValidator.BlockRLPSizeCap shouldBe 8_388_608L   // explicit byte count as a cross-check
  }

  "validateHeaderAndBody" should "pass for normal blocks" taggedAs (OlympiaTest, ConsensusTest) in {
    val block = Fixtures.Blocks.ValidBlock.block
    StdBlockValidator.validateHeaderAndBody(block.header, block.body) shouldBe Right(BlockValid)
  }

  "BlockRLPSizeError" should "contain both size and cap" taggedAs (OlympiaTest, ConsensusTest) in {
    val error = BlockRLPSizeError(9_000_000L, 8_388_608L)
    error.size shouldBe 9_000_000L
    error.cap shouldBe 8_388_608L
  }
}
