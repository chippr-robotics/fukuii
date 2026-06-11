package com.chipprbots.ethereum.consensus.blocks

import org.apache.pekko.util.ByteString

import scala.concurrent.duration._

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.consensus.difficulty.DifficultyCalculator
import com.chipprbots.ethereum.consensus.eip1559.BaseFeeCalculator
import com.chipprbots.ethereum.consensus.mining.MiningConfig
import com.chipprbots.ethereum.consensus.mining.Protocol
import com.chipprbots.ethereum.consensus.pow.blocks.Ommers
import com.chipprbots.ethereum.consensus.pow.validators.MockedPowBlockHeaderValidator
import com.chipprbots.ethereum.consensus.validators.BlockHeaderValid
import com.chipprbots.ethereum.consensus.validators.BlockHeaderError.HeaderGasLimitError
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.BlockBody
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefEmpty
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefPostOlympia
import com.chipprbots.ethereum.domain.SignedTransaction
import com.chipprbots.ethereum.nodebuilder.BlockchainConfigBuilder
import com.chipprbots.ethereum.testing.Tags._
import com.chipprbots.ethereum.utils.BlockchainConfig

/** Simulates the Spiral → Olympia gas limit transition for operators running the legacy 8M gasLimitTarget setting (e.g.
  * gas-limit-target = 8000000 in mining.conf).
  *
  * Two critical properties verified:
  *   1. calculateGasLimit() automatically applies the 60M Olympia floor via the fork gas schedule
  *      (gasLimitAdjustmentStartAt) — no config change needed for the operator. 2. Every miner-generated gas limit
  *      passes MockedPowBlockHeaderValidator at each step — miner and validator are always consistent across the fork
  *      boundary.
  *
  * The validateGasLimit ETH London 2× bug would have caused Fukuii to reject every block it produced at Olympia
  * activation: miner → 8,007,811 (1/1024), old validator expected ~16,000,000 (ETH London 2× rule). This spec is the
  * regression guard for that fix.
  */
// scalastyle:off magic.number
class SpiralToOlympiaGasTransitionSpec
    extends AnyWordSpec
    with Matchers
    with BlockchainConfigBuilder
    with com.chipprbots.ethereum.TestInstanceConfigProvider {

  private val olympiaBlock: BigInt = BigInt(100)

  // olympiaGasTarget = Some(60M) is required so gasLimitAdjustmentStartAt(olympiaBlock)
  // returns Some(60M) rather than falling back to miningConfig.gasLimitTarget.
  implicit val config: BlockchainConfig = blockchainConfig.withUpdatedForkBlocks(
    _.copy(olympiaBlockNumber = olympiaBlock, olympiaGasTarget = Some(BigInt(60_000_000)))
  )

  private val SpiralGasLimit: BigInt = BigInt(8_000_000)
  private val OlympiaGasTarget: BigInt = BigInt(60_000_000)
  private val InitialBaseFee: BigInt = BaseFeeCalculator.InitialBaseFee
  private val baseExtraData: ByteString = ByteString("test".getBytes)

  // 1/1024 step sizes from the activation parent onward:
  //   delta = parentGas / 1024 - 1
  private val StepOneGasLimit: BigInt = SpiralGasLimit + SpiralGasLimit / 1024 - 1 // 8,007,811

  private val legacyMiner = new TestableGen(SpiralGasLimit)

  private def validate(header: BlockHeader, parent: BlockHeader) =
    MockedPowBlockHeaderValidator.validate(header, parent)

  private def spiralHeader(number: BigInt, gasLimit: BigInt, timestamp: Long): BlockHeader =
    Fixtures.Blocks.ValidBlock.header.copy(
      number = number,
      gasLimit = gasLimit,
      gasUsed = 0,
      unixTimestamp = timestamp,
      difficulty = 0,
      extraData = baseExtraData,
      extraFields = HefEmpty
    )

  private def olympiaHeader(
      number: BigInt,
      gasLimit: BigInt,
      baseFee: BigInt,
      parentHash: ByteString,
      timestamp: Long
  ): BlockHeader =
    Fixtures.Blocks.ValidBlock.header.copy(
      parentHash = parentHash,
      number = number,
      gasLimit = gasLimit,
      gasUsed = 0,
      unixTimestamp = timestamp,
      difficulty = 0,
      extraData = baseExtraData,
      extraFields = HefPostOlympia(baseFee)
    )

  private class TestableGen(target: BigInt)
      extends BlockGeneratorSkeleton(
        MiningConfig(
          protocol = Protocol.PoW,
          coinbase = Address(42),
          headerExtraData = ByteString.empty,
          blockCacheSize = 1,
          miningEnabled = false,
          gasLimitTarget = target,
          notifyUrls = Seq.empty,
          staleThreshold = 7,
          recommitInterval = 0.seconds
        ),
        new DifficultyCalculator {
          def calculateDifficulty(blockNumber: BigInt, blockTimestamp: Long, parent: BlockHeader)(implicit
              blockchainConfig: BlockchainConfig
          ): BigInt = BigInt(1)
        }
      ) {
    type X = Ommers
    override protected def newBlockBody(transactions: Seq[SignedTransaction], x: Ommers): BlockBody =
      BlockBody(transactions, Nil)
    override protected def prepareHeader(
        blockNumber: BigInt,
        parent: com.chipprbots.ethereum.domain.Block,
        beneficiary: Address,
        blockTimestamp: Long,
        x: Ommers
    )(implicit blockchainConfig: BlockchainConfig): BlockHeader =
      defaultPrepareHeader(blockNumber, parent, beneficiary, blockTimestamp, x)
    def emptyX: Ommers = Nil
    def generateBlock(
        parent: com.chipprbots.ethereum.domain.Block,
        transactions: Seq[SignedTransaction],
        beneficiary: Address,
        x: Ommers,
        initialWorldStateBeforeExecution: Option[com.chipprbots.ethereum.ledger.InMemoryWorldStateProxy]
    )(implicit blockchainConfig: BlockchainConfig): PendingBlockAndState =
      throw new UnsupportedOperationException
    def withBlockTimestampProvider(btp: BlockTimestampProvider): TestBlockGenerator =
      throw new UnsupportedOperationException

    def calcGasLimit(parentGas: BigInt, blockNumber: BigInt)(implicit bc: BlockchainConfig): BigInt =
      calculateGasLimit(parentGas, blockNumber)
  }

  "Spiral-to-Olympia gas transition (EIP-7935)" when {

    "operator has gasLimitTarget = 8M (legacy Spiral node config)" should {

      "keep gas flat at 8M for all pre-Olympia Spiral blocks" taggedAs (UnitTest, OlympiaTest, ConsensusTest) in {
        for (blockNum <- Seq(BigInt(0), olympiaBlock - 10, olympiaBlock - 2, olympiaBlock - 1))
          withClue(s"block $blockNum: ") {
            legacyMiner.calcGasLimit(SpiralGasLimit, blockNum) shouldBe SpiralGasLimit
          }
      }

      "step up by 1/1024 at the Olympia activation block — no config change needed" taggedAs (
        UnitTest,
        OlympiaTest,
        ConsensusTest
      ) in {
        val next = legacyMiner.calcGasLimit(SpiralGasLimit, olympiaBlock)
        next shouldBe StepOneGasLimit
        next should be > SpiralGasLimit
      }

      "converge to 60M in 2,055 blocks despite legacy 8M gasLimitTarget" taggedAs (
        UnitTest,
        OlympiaTest,
        ConsensusTest
      ) in {
        val threshold = OlympiaGasTarget * 99 / 100
        var gas = SpiralGasLimit
        var blocks = 0
        while (gas < threshold && blocks < 10_000) {
          gas = legacyMiner.calcGasLimit(gas, olympiaBlock + blocks)
          blocks += 1
        }
        gas should be >= threshold
        blocks shouldBe 2055
        info(s"legacy 8M config → 99% of 60M in $blocks blocks (~${blocks * 13.0 / 3600.0}h at 13s/block)")
      }

      "produce an identical trajectory to an operator who explicitly set gasLimitTarget = 60M" taggedAs (
        UnitTest,
        OlympiaTest,
        ConsensusTest
      ) in {
        val explicitMiner = new TestableGen(OlympiaGasTarget)
        val legacy = (0 until 200).scanLeft(SpiralGasLimit)((g, i) => legacyMiner.calcGasLimit(g, olympiaBlock + i))
        val explicit = (0 until 200).scanLeft(SpiralGasLimit)((g, i) => explicitMiner.calcGasLimit(g, olympiaBlock + i))
        legacy shouldBe explicit
      }
    }

    "end-to-end miner/validator consistency at the fork boundary" should {

      "accept a Spiral block with 8M gas limit (pre-Olympia baseline)" taggedAs (
        UnitTest,
        OlympiaTest,
        ConsensusTest
      ) in {
        val parent = spiralHeader(olympiaBlock - 2, SpiralGasLimit, timestamp = 1000L)
        val child = spiralHeader(olympiaBlock - 1, SpiralGasLimit, timestamp = 2000L).copy(
          parentHash = parent.hash
        )
        validate(child, parent) shouldBe Right(BlockHeaderValid)
      }

      "accept the first Olympia block with 1/1024-stepped gas limit from legacy miner" taggedAs (
        UnitTest,
        OlympiaTest,
        ConsensusTest
      ) in {
        val parent = spiralHeader(olympiaBlock - 1, SpiralGasLimit, timestamp = 1000L)
        val generatedGas = legacyMiner.calcGasLimit(SpiralGasLimit, olympiaBlock)
        val child = olympiaHeader(
          number = olympiaBlock,
          gasLimit = generatedGas,
          baseFee = InitialBaseFee,
          parentHash = parent.hash,
          timestamp = 2000L
        )
        validate(child, parent) shouldBe Right(BlockHeaderValid)
      }

      "accept the second Olympia block with continued 1/1024 step and derived baseFee" taggedAs (
        UnitTest,
        OlympiaTest,
        ConsensusTest
      ) in {
        val spiralParent = spiralHeader(olympiaBlock - 1, SpiralGasLimit, timestamp = 1000L)
        val firstOlympia = olympiaHeader(
          number = olympiaBlock,
          gasLimit = StepOneGasLimit,
          baseFee = InitialBaseFee,
          parentHash = spiralParent.hash,
          timestamp = 2000L
        )
        val secondGas = legacyMiner.calcGasLimit(StepOneGasLimit, olympiaBlock + 1)
        val expectedBaseFee = BaseFeeCalculator.calcBaseFee(firstOlympia, config)
        val secondOlympia = olympiaHeader(
          number = olympiaBlock + 1,
          gasLimit = secondGas,
          baseFee = expectedBaseFee,
          parentHash = firstOlympia.hash,
          timestamp = 3000L
        )
        validate(secondOlympia, firstOlympia) shouldBe Right(BlockHeaderValid)
      }

      "reject a 2× gas jump at Olympia activation (ETH London semantics must not apply to ETC)" taggedAs (
        UnitTest,
        OlympiaTest,
        ConsensusTest
      ) in {
        val parent = spiralHeader(olympiaBlock - 1, SpiralGasLimit, timestamp = 1000L)
        val doubledGas = olympiaHeader(
          number = olympiaBlock,
          gasLimit = SpiralGasLimit * 2,
          baseFee = InitialBaseFee,
          parentHash = parent.hash,
          timestamp = 2000L
        )
        validate(doubledGas, parent) shouldBe Left(HeaderGasLimitError)
      }
    }

    "fork gas schedule override/fallback behavior" should {

      "gasLimitAdjustmentStartAt overrides operator gasLimitTarget — 60M config ignored pre-Olympia when schedule set" taggedAs (
        UnitTest,
        OlympiaTest,
        ConsensusTest
      ) in {
        val configWithSpiralSchedule = blockchainConfig.withUpdatedForkBlocks(
          _.copy(
            spiralBlockNumber = BigInt(0),
            olympiaBlockNumber = olympiaBlock,
            olympiaGasTarget = Some(BigInt(60_000_000)),
            spiralGasTarget = Some(BigInt(8_000_000))
          )
        )
        val minerWith60MConfig = new TestableGen(BigInt(60_000_000))
        val preOlympiaBlock = olympiaBlock - 1
        val result = minerWith60MConfig.calcGasLimit(SpiralGasLimit, preOlympiaBlock)(configWithSpiralSchedule)
        result shouldBe SpiralGasLimit
      }

      "falls back to miningConfig.gasLimitTarget when gasLimitAdjustmentStartAt returns None" taggedAs (
        UnitTest,
        OlympiaTest,
        ConsensusTest
      ) in {
        val configNoSchedule = blockchainConfig.withUpdatedForkBlocks(
          _.copy(
            olympiaBlockNumber = olympiaBlock,
            olympiaGasTarget = None,
            spiralGasTarget = None
          )
        )
        val minerWith60MConfig = new TestableGen(BigInt(60_000_000))
        val preOlympiaBlock = olympiaBlock - 1
        val result = minerWith60MConfig.calcGasLimit(SpiralGasLimit, preOlympiaBlock)(configNoSchedule)
        result should be > SpiralGasLimit
      }
    }
  }
}
// scalastyle:on magic.number
