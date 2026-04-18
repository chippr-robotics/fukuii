package com.chipprbots.ethereum.consensus.blocks

import org.apache.pekko.util.ByteString

import scala.concurrent.duration._

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
/** Tests gas limit convergence logic in BlockGeneratorSkeleton.calculateGasLimit().
  *
  * Verifies that the miner's gas limit calculation converges toward the configured target at ±1/1024 per block,
  * matching core-geth's CalcGasLimit() and besu's OlympiaTargetingGasLimitCalculator.
  */
class GasLimitCalculationSpec extends AnyFlatSpec with Matchers {

  private val GasLimitBoundDivisor = BlockHeaderValidator.GasLimitBoundDivisor

  /** Minimal BlockGeneratorSkeleton subclass that exposes calculateGasLimit for testing. */
  private class TestableBlockGenerator(config: MiningConfig)
      extends BlockGeneratorSkeleton(
        config,
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
      throw new UnsupportedOperationException("not needed for gas limit tests")
    def withBlockTimestampProvider(btp: BlockTimestampProvider): TestBlockGenerator =
      throw new UnsupportedOperationException("not needed for gas limit tests")

    // Expose the protected method for testing
    def calcGasLimit(parentGas: BigInt): BigInt = calculateGasLimit(parentGas)
  }

  private def makeGenerator(target: BigInt): TestableBlockGenerator =
    new TestableBlockGenerator(
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
      )
    )

  "calculateGasLimit" should "converge upward from 1M to 8M target" taggedAs (ConsensusTest) in {
    val gen = makeGenerator(BigInt(8_000_000))
    var limit = BigInt(1_000_000)
    val target = BigInt(8_000_000)

    // First step: should increase by parent/1024 - 1
    val firstStep = gen.calcGasLimit(limit)
    val expectedDelta = limit / GasLimitBoundDivisor - 1
    firstStep shouldBe limit + expectedDelta

    // Run until convergence
    var blocks = 0
    while (limit < target * 99 / 100 && blocks < 100_000) {
      limit = gen.calcGasLimit(limit)
      blocks += 1
    }
    limit should be >= target * BigInt(99) / BigInt(100)
    info(s"converged from 1M to 99% of 8M in $blocks blocks")
  }

  it should "be stable at target (8M → 8M)" taggedAs (ConsensusTest) in {
    val target = BigInt(8_000_000)
    val gen = makeGenerator(target)
    gen.calcGasLimit(target) shouldBe target
  }

  it should "converge downward from 10M to 8M target" taggedAs (ConsensusTest) in {
    val gen = makeGenerator(BigInt(8_000_000))
    var limit = BigInt(10_000_000)
    val target = BigInt(8_000_000)

    // First step: should decrease
    val firstStep = gen.calcGasLimit(limit)
    firstStep should be < limit

    // Run until convergence
    var blocks = 0
    while (limit > target * 101 / 100 && blocks < 100_000) {
      limit = gen.calcGasLimit(limit)
      blocks += 1
    }
    limit should be <= target * BigInt(101) / BigInt(100)
    info(s"converged from 10M to 101% of 8M in $blocks blocks")
  }

  it should "respect ±1/1024 bound per block" taggedAs (ConsensusTest) in {
    val gen = makeGenerator(BigInt(60_000_000))
    val parentGas = BigInt(8_000_000)
    val maxDelta = parentGas / GasLimitBoundDivisor - 1

    val next = gen.calcGasLimit(parentGas)
    val actualDelta = (next - parentGas).abs
    actualDelta should be <= maxDelta
  }

  it should "not go below MinGasLimit" taggedAs (ConsensusTest) in {
    val gen = makeGenerator(BlockHeaderValidator.MinGasLimit)
    // Start just above MinGasLimit — convergence should stop at target, not below
    val limit = gen.calcGasLimit(BigInt(6000))
    limit should be >= BlockHeaderValidator.MinGasLimit
  }

  it should "converge from ETC 8M to post-Olympia 60M target" taggedAs (ConsensusTest) in {
    val gen = makeGenerator(BigInt(60_000_000))
    var limit = BigInt(8_000_000)
    val target = BigInt(60_000_000)
    val threshold = target * 99 / 100

    var blocks = 0
    while (limit < threshold && blocks < 200_000) {
      limit = gen.calcGasLimit(limit)
      blocks += 1
    }
    limit should be >= threshold
    // Should match core-geth's convergence: ~2,055 blocks
    blocks should be < 3000
    info(s"converged from 8M to 99% of 60M in $blocks blocks (~${blocks * 13.0 / 3600} hours at 13s/block)")
  }

  it should "snap to target when within one delta" taggedAs (ConsensusTest) in {
    val target = BigInt(8_000_000)
    val gen = makeGenerator(target)
    // Parent gas is very close to target (within one delta)
    val nearTarget = target - 100
    val next = gen.calcGasLimit(nearTarget)
    // Delta = nearTarget/1024 - 1 = ~7811, which is > 100, so it should snap to target
    next shouldBe target
  }
}
// scalastyle:on magic.number
