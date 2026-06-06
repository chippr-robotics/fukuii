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

  implicit val config: BlockchainConfig = blockchainConfig
    .withUpdatedForkBlocks(_.copy(olympiaBlockNumber = olympiaBlock))
    .copy(baseFeeFloor = BaseFeeCalculator.InitialBaseFee)

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
        val firstOlympiaBlock =
          header(olympiaBlock, gasLimit = BigInt(16_000_000), gasUsed = 0, HefPostOlympia(InitialBaseFee))
        val result = BaseFeeCalculator.calcBaseFee(firstOlympiaBlock, config)
        // gasTarget = 16M / 2 = 8M; gasUsed = 0 < 8M → would decrease by
        // 1_000_000_000 * 8_000_000 / 8_000_000 / 8 = 125_000_000, but the ECIP-1111 floor
        // clamps the result back up to InitialBaseFee (1 gwei) — matching this test's name.
        result shouldBe InitialBaseFee
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
        // 2 gwei — above the 1-gwei ECIP-1111 floor — so the 1/8 decrease to 1.75 gwei is
        // observable rather than being clamped back up to the floor.
        val baseFee = BigInt(2_000_000_000)
        val emptyParent = header(olympiaBlock, gasLimit = gasLimit, gasUsed = 0, HefPostOlympia(baseFee))
        val next = BaseFeeCalculator.calcBaseFee(emptyParent, config)
        next should be < baseFee
        next should be >= InitialBaseFee
      }
    }

    "baseFee floors at InitialBaseFee (1 gwei) per ECIP-1111" should {
      "clamp to InitialBaseFee when computed value would be below floor" taggedAs (UnitTest, OlympiaTest) in {
        val gasLimit = BigInt(30_000_000)
        val tinyBaseFee = BigInt(1) // 1 wei — would decay to 0 without floor
        val emptyParent = header(olympiaBlock, gasLimit = gasLimit, gasUsed = 0, HefPostOlympia(tinyBaseFee))
        val next = BaseFeeCalculator.calcBaseFee(emptyParent, config)
        next should be >= InitialBaseFee
        next shouldBe InitialBaseFee
      }
    }
  }
}
