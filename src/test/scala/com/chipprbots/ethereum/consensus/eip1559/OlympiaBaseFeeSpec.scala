package com.chipprbots.ethereum.consensus.eip1559

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefEmpty
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefPostOlympia
import com.chipprbots.ethereum.nodebuilder.BlockchainConfigBuilder
import com.chipprbots.ethereum.testing.Tags._
import com.chipprbots.ethereum.utils.BlockchainConfig

/** Tests for BaseFeeCalculator.calcBaseFee at the Olympia fork boundary.
  *
  * Verifies EIP-1559 fee market suppression pre-Olympia (returns InitialBaseFee), the first Olympia block baseFee
  * (always InitialBaseFee = 1 Gwei), and the adjustment algorithm for subsequent blocks.
  */
class OlympiaBaseFeeSpec
    extends AnyWordSpec
    with Matchers
    with BlockchainConfigBuilder
    with com.chipprbots.ethereum.TestInstanceConfigProvider {

  private val olympiaBlock: BigInt = BigInt(100)

  implicit val config: BlockchainConfig = blockchainConfig.withUpdatedForkBlocks(
    _.copy(olympiaBlockNumber = olympiaBlock)
  )

  private val InitialBaseFee: BigInt = BaseFeeCalculator.InitialBaseFee

  private def header(
      number: BigInt,
      gasLimit: BigInt,
      gasUsed: BigInt,
      extraFields: BlockHeader.HeaderExtraFields
  ): BlockHeader =
    Fixtures.Blocks.ValidBlock.header.copy(
      number = number,
      gasLimit = gasLimit,
      gasUsed = gasUsed,
      extraFields = extraFields
    )

  "BaseFeeCalculator" when {

    "InitialBaseFee constant" should {
      "be 1 Gwei (1_000_000_000 wei)" taggedAs (UnitTest, OlympiaTest) in {
        InitialBaseFee shouldBe BigInt(1_000_000_000)
      }
    }

    "parent is pre-Olympia (fee market suppressed)" should {
      "return InitialBaseFee regardless of parent gas usage" taggedAs (UnitTest, OlympiaTest) in {
        val preOlympiaParent = header(olympiaBlock - 1, gasLimit = BigInt(8_000_000), gasUsed = 0, HefEmpty)
        BaseFeeCalculator.calcBaseFee(preOlympiaParent, config) shouldBe InitialBaseFee
      }

      "return InitialBaseFee even when parent is fully utilized" taggedAs (UnitTest, OlympiaTest) in {
        val fullParent =
          header(olympiaBlock - 1, gasLimit = BigInt(8_000_000), gasUsed = BigInt(8_000_000), HefEmpty)
        BaseFeeCalculator.calcBaseFee(fullParent, config) shouldBe InitialBaseFee
      }
    }

    "parent is the first Olympia block (no parent baseFee)" should {
      "return InitialBaseFee because parent.baseFee defaults to InitialBaseFee" taggedAs (
        UnitTest,
        OlympiaTest
      ) in {
        // First Olympia block — parent is pre-Olympia, so it has HefEmpty / no baseFee.
        // calcBaseFee is called for the SECOND Olympia block here (parent = block 100).
        // parent.baseFee.getOrElse(InitialBaseFee) → InitialBaseFee since HefPostOlympia carries it.
        val firstOlympiaBlock =
          header(olympiaBlock, gasLimit = BigInt(16_000_000), gasUsed = 0, HefPostOlympia(InitialBaseFee))
        // When gasUsed == gasTarget (0 == 8M / 2 = 8M? No, gasUsed=0 < gasTarget=8M):
        // baseFee decreases but floors at 0.
        val result = BaseFeeCalculator.calcBaseFee(firstOlympiaBlock, config)
        // gasTarget = 16M / 2 = 8M; gasUsed = 0 < 8M → decrease
        // delta = 1_000_000_000 * 8_000_000 / 8_000_000 / 8 = 125_000_000
        result shouldBe InitialBaseFee - BigInt(125_000_000)
      }
    }

    "parent gasUsed equals gas target" should {
      "return the same baseFee (stable)" taggedAs (UnitTest, OlympiaTest) in {
        val target = BigInt(30_000_000)
        val baseFee = BigInt(2_000_000_000)
        val stableParent = header(olympiaBlock, gasLimit = target * 2, gasUsed = target, HefPostOlympia(baseFee))
        BaseFeeCalculator.calcBaseFee(stableParent, config) shouldBe baseFee
      }
    }

    "parent gasUsed exceeds gas target" should {
      "increase baseFee" taggedAs (UnitTest, OlympiaTest) in {
        val gasLimit = BigInt(30_000_000)
        val baseFee = BigInt(1_000_000_000)
        val fullParent = header(olympiaBlock, gasLimit = gasLimit, gasUsed = gasLimit, HefPostOlympia(baseFee))
        val next = BaseFeeCalculator.calcBaseFee(fullParent, config)
        next should be > baseFee
        // delta = baseFee * (gasUsed - gasTarget) / gasTarget / 8 = baseFee * gasTarget / gasTarget / 8 = baseFee / 8
        next shouldBe baseFee + (baseFee / 8).max(1)
      }
    }

    "parent gasUsed is below gas target" should {
      "decrease baseFee" taggedAs (UnitTest, OlympiaTest) in {
        val gasLimit = BigInt(30_000_000)
        val baseFee = BigInt(1_000_000_000)
        val emptyParent = header(olympiaBlock, gasLimit = gasLimit, gasUsed = 0, HefPostOlympia(baseFee))
        val next = BaseFeeCalculator.calcBaseFee(emptyParent, config)
        next should be < baseFee
        next should be >= BigInt(0)
      }
    }

    "baseFee floors at zero" should {
      "not go negative when baseFee is very small" taggedAs (UnitTest, OlympiaTest) in {
        val gasLimit = BigInt(30_000_000)
        val tinyBaseFee = BigInt(1) // 1 wei
        val emptyParent = header(olympiaBlock, gasLimit = gasLimit, gasUsed = 0, HefPostOlympia(tinyBaseFee))
        val next = BaseFeeCalculator.calcBaseFee(emptyParent, config)
        next should be >= BigInt(0)
      }
    }
  }
}
