package com.chipprbots.ethereum.consensus.eip1559

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields._
import com.chipprbots.ethereum.nodebuilder.BlockchainConfigBuilder
import com.chipprbots.ethereum.testing.Tags._
import com.chipprbots.ethereum.utils.BlockchainConfig

class BaseFeeCalculatorSpec
    extends AnyFlatSpec
    with Matchers
    with BlockchainConfigBuilder
    with com.chipprbots.ethereum.TestInstanceConfigProvider {

  val olympiaBlock: BigInt = 10

  val config: BlockchainConfig = blockchainConfig.withUpdatedForkBlocks(
    _.copy(olympiaBlockNumber = olympiaBlock)
  )

  def makeHeader(
      number: BigInt,
      gasLimit: BigInt,
      gasUsed: BigInt,
      baseFee: Option[BigInt] = None
  ): BlockHeader = {
    val extraFields = baseFee match {
      case Some(fee) => HefPostOlympia(fee)
      case None      => HefEmpty
    }
    Fixtures.Blocks.ValidBlock.header.copy(
      number = number,
      gasLimit = gasLimit,
      gasUsed = gasUsed,
      extraFields = extraFields
    )
  }

  "BaseFeeCalculator" should "return initial baseFee (1 gwei) when parent is pre-Olympia" taggedAs (
    OlympiaTest,
    ConsensusTest
  ) in {
    val parent = makeHeader(number = olympiaBlock - 1, gasLimit = 8000000, gasUsed = 4000000)
    val result = BaseFeeCalculator.calcBaseFee(parent, config)
    result shouldBe BaseFeeCalculator.InitialBaseFee
    result shouldBe BigInt(1000000000)
  }

  it should "keep baseFee unchanged when parent gasUsed equals target" taggedAs (OlympiaTest, ConsensusTest) in {
    val gasLimit = BigInt(8000000)
    val gasTarget = gasLimit / BaseFeeCalculator.ElasticityMultiplier
    val parentBaseFee = BigInt(1000000000)

    val parent = makeHeader(
      number = olympiaBlock,
      gasLimit = gasLimit,
      gasUsed = gasTarget,
      baseFee = Some(parentBaseFee)
    )

    BaseFeeCalculator.calcBaseFee(parent, config) shouldBe parentBaseFee
  }

  it should "increase baseFee when parent gasUsed exceeds target" taggedAs (OlympiaTest, ConsensusTest) in {
    val gasLimit = BigInt(8000000)
    val parentBaseFee = BigInt(1000000000)

    val parent = makeHeader(
      number = olympiaBlock,
      gasLimit = gasLimit,
      gasUsed = gasLimit,
      baseFee = Some(parentBaseFee)
    )

    val result = BaseFeeCalculator.calcBaseFee(parent, config)
    result shouldBe parentBaseFee + parentBaseFee / 8
    result should be > parentBaseFee
  }

  it should "decrease baseFee when parent gasUsed is below target" taggedAs (OlympiaTest, ConsensusTest) in {
    val gasLimit = BigInt(8000000)
    val parentBaseFee = BigInt(1000000000)

    val parent = makeHeader(
      number = olympiaBlock,
      gasLimit = gasLimit,
      gasUsed = 0,
      baseFee = Some(parentBaseFee)
    )

    val result = BaseFeeCalculator.calcBaseFee(parent, config)
    result shouldBe parentBaseFee - parentBaseFee / 8
    result should be < parentBaseFee
  }

  it should "increase baseFee by at least 1 even with small parentBaseFee" taggedAs (OlympiaTest, ConsensusTest) in {
    val gasLimit = BigInt(8000000)
    val parentBaseFee = BigInt(7)

    val parent = makeHeader(
      number = olympiaBlock,
      gasLimit = gasLimit,
      gasUsed = gasLimit,
      baseFee = Some(parentBaseFee)
    )

    val result = BaseFeeCalculator.calcBaseFee(parent, config)
    result shouldBe parentBaseFee + 1
  }

  it should "never decrease baseFee below 0" taggedAs (OlympiaTest, ConsensusTest) in {
    val gasLimit = BigInt(8000000)
    val parentBaseFee = BigInt(1)

    val parent = makeHeader(
      number = olympiaBlock,
      gasLimit = gasLimit,
      gasUsed = 0,
      baseFee = Some(parentBaseFee)
    )

    val result = BaseFeeCalculator.calcBaseFee(parent, config)
    result should be >= BigInt(0)
  }
}
