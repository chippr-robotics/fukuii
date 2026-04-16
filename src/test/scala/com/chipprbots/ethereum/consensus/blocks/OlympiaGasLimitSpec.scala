package com.chipprbots.ethereum.consensus.blocks

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.consensus.difficulty.DifficultyCalculator
import com.chipprbots.ethereum.consensus.mining.MiningConfig
import com.chipprbots.ethereum.consensus.mining.Protocol
import com.chipprbots.ethereum.consensus.pow.blocks.Ommers
import com.chipprbots.ethereum.consensus.validators.BlockHeaderValidator
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.BlockBody
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.SignedTransaction
import com.chipprbots.ethereum.testing.Tags._
import com.chipprbots.ethereum.utils.BlockchainConfig

// scalastyle:off magic.number
/** EIP-7935: Verify gas limit convergence behavior for the Olympia hard fork.
  *
  * Post-Olympia, the target gas limit increases from 8M to 60M. These tests verify that calculateGasLimit() correctly
  * converges toward the new target, matching core-geth's CalcGasLimit() and besu's OlympiaTargetingGasLimitCalculator.
  *
  * Cross-client convergence time: 2,055 blocks (~7.4 hours at 13s/block) from 8M to 99% of 60M.
  */
class OlympiaGasLimitSpec extends AnyFlatSpec with Matchers {

  private val OlympiaGasTarget = BigInt(60_000_000)
  private val PreOlympiaGasLimit = BigInt(8_000_000)
  private val GasLimitBoundDivisor = BlockHeaderValidator.GasLimitBoundDivisor

  private class TestableGen(target: BigInt)
      extends BlockGeneratorSkeleton(
        MiningConfig(
          protocol = Protocol.PoW,
          coinbase = Address(42),
          headerExtraData = ByteString.empty,
          blockCacheSize = 1,
          miningEnabled = false,
          gasLimitTarget = target
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

    def calcGasLimit(parentGas: BigInt): BigInt = calculateGasLimit(parentGas)
  }

  "Olympia gas limit (EIP-7935)" should "converge from pre-Olympia 8M to 60M target" taggedAs (
    OlympiaTest,
    ConsensusTest
  ) in {
    val gen = new TestableGen(OlympiaGasTarget)
    var limit = PreOlympiaGasLimit
    val threshold = OlympiaGasTarget * 99 / 100

    var blocks = 0
    while (limit < threshold && blocks < 200_000) {
      limit = gen.calcGasLimit(limit)
      blocks += 1
    }
    limit should be >= threshold
    // Must match core-geth: 2,055 blocks
    blocks shouldBe 2055
    info(s"convergence from 8M to 99% of 60M: $blocks blocks (~${blocks * 13.0 / 3600} hours)")
  }

  it should "be stable at 60M target" taggedAs (OlympiaTest, ConsensusTest) in {
    val gen = new TestableGen(OlympiaGasTarget)
    gen.calcGasLimit(OlympiaGasTarget) shouldBe OlympiaGasTarget
  }

  it should "respect ±1/1024 per-block bound at 60M" taggedAs (OlympiaTest, ConsensusTest) in {
    val gen = new TestableGen(OlympiaGasTarget)
    val maxDelta = PreOlympiaGasLimit / GasLimitBoundDivisor - 1

    // First block adjustment from 8M
    val next = gen.calcGasLimit(PreOlympiaGasLimit)
    val delta = next - PreOlympiaGasLimit
    delta shouldBe maxDelta
    delta shouldBe BigInt(7811) // 8_000_000 / 1024 - 1
  }

  it should "decrease from above 60M target" taggedAs (OlympiaTest, ConsensusTest) in {
    val gen = new TestableGen(OlympiaGasTarget)
    val aboveTarget = BigInt(80_000_000)

    val next = gen.calcGasLimit(aboveTarget)
    next should be < aboveTarget
    next should be >= OlympiaGasTarget
  }

  it should "simulate adversarial 70/30 hashrate split near 60M equilibrium" taggedAs (OlympiaTest, ConsensusTest) in {
    val honestGen = new TestableGen(OlympiaGasTarget)
    val adversaryGen = new TestableGen(BigInt(30_000_000))

    var limit = PreOlympiaGasLimit
    val rng = new scala.util.Random(42)

    // Simulate 10,000 blocks with 70% honest (60M) / 30% adversary (30M)
    for (_ <- 1 to 10_000)
      limit = if (rng.nextInt(100) < 70) honestGen.calcGasLimit(limit) else adversaryGen.calcGasLimit(limit)

    // With 70% honest, should converge near 60M (within 5%)
    limit should be >= OlympiaGasTarget * 95 / 100
    limit should be <= OlympiaGasTarget * 105 / 100
    info(s"70/30 split equilibrium: $limit (${(limit.toDouble / OlympiaGasTarget.toDouble * 100).round}% of 60M)")
  }
}
// scalastyle:on magic.number
