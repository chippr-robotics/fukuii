package com.chipprbots.ethereum.consensus.validators.std

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.consensus.validators.std.StdBlockValidator._
import com.chipprbots.ethereum.domain._
import com.chipprbots.ethereum.testing.Tags._

/** EIP-7934: Verify block RLP size cap validation. */
class BlockRLPSizeCapSpec extends AnyFlatSpec with Matchers {

  "BlockRLPSizeCap constant" should "be 8388608 (10 MiB - 2 MiB)" taggedAs (OlympiaTest, ConsensusTest) in {
    StdBlockValidator.BlockRLPSizeCap shouldBe 8388608L
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
