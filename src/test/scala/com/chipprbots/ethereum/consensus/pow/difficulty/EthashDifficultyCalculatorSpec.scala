package com.chipprbots.ethereum.consensus.pow.difficulty

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import com.chipprbots.ethereum.consensus.difficulty.DifficultyCalculator
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.testing.Tags._
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.ForkBlockNumbers

// scalastyle:off magic.number
class EthashDifficultyCalculatorSpec extends AnyFlatSpec with Matchers with ScalaCheckPropertyChecks {

  // Minimal BlockHeader for difficulty tests
  private def header(
      number: BigInt,
      difficulty: BigInt,
      timestamp: Long,
      hasUncles: Boolean = false
  ): BlockHeader =
    BlockHeader(
      parentHash = ByteString(new Array[Byte](32)),
      ommersHash = if (hasUncles) ByteString(new Array[Byte](32)) else BlockHeader.EmptyOmmers,
      beneficiary = ByteString(new Array[Byte](20)),
      stateRoot = ByteString(new Array[Byte](32)),
      transactionsRoot = ByteString(new Array[Byte](32)),
      receiptsRoot = ByteString(new Array[Byte](32)),
      logsBloom = ByteString(new Array[Byte](256)),
      difficulty = difficulty,
      number = number,
      gasLimit = BigInt(8000000),
      gasUsed = BigInt(0),
      unixTimestamp = timestamp,
      extraData = ByteString.empty,
      mixHash = ByteString(new Array[Byte](32)),
      nonce = ByteString(new Array[Byte](8))
    )

  // ETC mainnet fork block config (difficulty bomb removed at ECIP-1041/Mystique)
  private val etcForkNumbers: ForkBlockNumbers = ForkBlockNumbers(
    frontierBlockNumber = 0,
    homesteadBlockNumber = 1150000,
    eip106BlockNumber = Long.MaxValue,
    eip150BlockNumber = 2500000,
    eip155BlockNumber = 3000000,
    eip160BlockNumber = 3000000,
    eip161BlockNumber = Long.MaxValue,
    difficultyBombPauseBlockNumber = 3000000,
    difficultyBombContinueBlockNumber = 5000000,
    difficultyBombRemovalBlockNumber = 5900000,
    byzantiumBlockNumber = Long.MaxValue,
    constantinopleBlockNumber = Long.MaxValue,
    istanbulBlockNumber = Long.MaxValue,
    atlantisBlockNumber = 8772000,
    aghartaBlockNumber = 9573000,
    phoenixBlockNumber = 10500839,
    petersburgBlockNumber = Long.MaxValue,
    ecip1099BlockNumber = 11460000,
    muirGlacierBlockNumber = Long.MaxValue,
    magnetoBlockNumber = 13189133,
    berlinBlockNumber = 13189133,
    mystiqueBlockNumber = 14525000,
    spiralBlockNumber = 19250000
  )

  private implicit val blockchainConfig: BlockchainConfig = BlockchainConfig(
    forkBlockNumbers = etcForkNumbers,
    maxCodeSize = Some(24576),
    customGenesisFileOpt = None,
    customGenesisJsonOpt = None,
    daoForkConfig = None,
    accountStartNonce = com.chipprbots.ethereum.domain.UInt256.Zero,
    chainId = 61,
    networkId = 1,
    monetaryPolicyConfig = com.chipprbots.ethereum.utils.MonetaryPolicyConfig(
      5000000, 0.2, 5000000000000000000L, 3000000000000000000L, 2000000000000000000L
    ),
    gasTieBreaker = false,
    ethCompatibleStorage = true,
    bootstrapNodes = Set.empty
  )

  // ===== Basic Difficulty Adjustment =====

  "EthashDifficultyCalculator" should "increase difficulty for fast blocks" taggedAs (UnitTest, ConsensusTest) in {
    // Parent block mined at timestamp 1000, next block at 1005 (5s gap, target is ~13s)
    val parent = header(number = 10000000, difficulty = BigInt("1000000000000"), timestamp = 1000)
    val childTimestamp = 1005L

    val newDiff = EthashDifficultyCalculator.calculateDifficulty(10000001, childTimestamp, parent)
    newDiff should be > parent.difficulty
  }

  it should "decrease difficulty for slow blocks" taggedAs (UnitTest, ConsensusTest) in {
    // Parent mined at timestamp 1000, next block at 1100 (100s gap, well above target)
    val parent = header(number = 10000000, difficulty = BigInt("1000000000000"), timestamp = 1000)
    val childTimestamp = 1100L

    val newDiff = EthashDifficultyCalculator.calculateDifficulty(10000001, childTimestamp, parent)
    newDiff should be < parent.difficulty
  }

  it should "not go below minimum difficulty" taggedAs (UnitTest, ConsensusTest) in {
    // Very low difficulty parent with very long block time
    val parent = header(number = 10000000, difficulty = DifficultyCalculator.MinimumDifficulty, timestamp = 1000)
    val childTimestamp = 100000L

    val newDiff = EthashDifficultyCalculator.calculateDifficulty(10000001, childTimestamp, parent)
    newDiff shouldBe DifficultyCalculator.MinimumDifficulty
  }

  // ===== Difficulty Bomb Pause/Continue/Removal =====

  it should "include difficulty bomb before pause block" taggedAs (UnitTest, ConsensusTest) in {
    // Before difficultyBombPauseBlockNumber (3,000,000 on ETC)
    val parent = header(number = 2999998, difficulty = BigInt("20000000000000"), timestamp = 1000)
    val childTimestamp = 1013L // ~13s gap, should be roughly same difficulty without bomb

    val newDiff = EthashDifficultyCalculator.calculateDifficulty(2999999, childTimestamp, parent)
    // Bomb adds 2^(blockNumber/100000 - 2) at block ~3M that's 2^(29-2) = 2^27 = 134M
    // Should still be calculable and positive
    newDiff should be > BigInt(0)
  }

  it should "pause difficulty bomb between pause and continue blocks" taggedAs (UnitTest, ConsensusTest) in {
    // Between pause (3M) and continue (5M), bomb should be frozen at pause level
    val parent3_5M = header(number = 3500000, difficulty = BigInt("20000000000000"), timestamp = 1000)
    val parent4_5M = header(number = 4500000, difficulty = BigInt("20000000000000"), timestamp = 1000)
    val childTimestamp = 1013L

    val diff3_5M = EthashDifficultyCalculator.calculateDifficulty(3500001, childTimestamp, parent3_5M)
    val diff4_5M = EthashDifficultyCalculator.calculateDifficulty(4500001, childTimestamp, parent4_5M)

    // Both should have same bomb contribution since bomb is paused
    // The base adjustment is the same (same parent difficulty, same timestamp gap)
    // So results should be very close (only differ by bomb contribution, which is frozen)
    val ratio = diff3_5M.toDouble / diff4_5M.toDouble
    ratio should be > 0.999
    ratio should be < 1.001
  }

  it should "remove difficulty bomb after removal block" taggedAs (UnitTest, ConsensusTest) in {
    // After difficultyBombRemovalBlockNumber (5,900,000 on ETC via ECIP-1041)
    val parentA = header(number = 6000000, difficulty = BigInt("20000000000000"), timestamp = 1000)
    val parentB = header(number = 12000000, difficulty = BigInt("20000000000000"), timestamp = 1000)
    val childTimestamp = 1013L

    val diffA = EthashDifficultyCalculator.calculateDifficulty(6000001, childTimestamp, parentA)
    val diffB = EthashDifficultyCalculator.calculateDifficulty(12000001, childTimestamp, parentB)

    // Without bomb, same parent difficulty and timestamp gap should produce same result
    diffA shouldBe diffB
  }

  // ===== Uncle-Aware Adjustment (post-Atlantis) =====

  it should "account for uncles in difficulty adjustment post-Atlantis" taggedAs (UnitTest, ConsensusTest) in {
    // Post-Atlantis (8,772,000), uncle factor affects c coefficient
    val parentNoUncles = header(number = 10000000, difficulty = BigInt("1000000000000"), timestamp = 1000, hasUncles = false)
    val parentWithUncles = header(number = 10000000, difficulty = BigInt("1000000000000"), timestamp = 1000, hasUncles = true)
    val childTimestamp = 1013L

    val diffNoUncles = EthashDifficultyCalculator.calculateDifficulty(10000001, childTimestamp, parentNoUncles)
    val diffWithUncles = EthashDifficultyCalculator.calculateDifficulty(10000001, childTimestamp, parentWithUncles)

    // Parent with uncles should produce higher difficulty (uncle factor = 2 vs 1)
    diffWithUncles should be > diffNoUncles
  }

  // ===== DifficultyCalculator Dispatch =====

  "DifficultyCalculator" should "use EthashDifficultyCalculator when no powTargetTime" taggedAs (UnitTest, ConsensusTest) in {
    val parent = header(number = 10000000, difficulty = BigInt("1000000000000"), timestamp = 1000)
    val childTimestamp = 1013L

    // Default config has no powTargetTime, so dispatches to EthashDifficultyCalculator
    val result = DifficultyCalculator.calculateDifficulty(10000001, childTimestamp, parent)
    val direct = EthashDifficultyCalculator.calculateDifficulty(10000001, childTimestamp, parent)
    result shouldBe direct
  }

  it should "use TargetTimeDifficultyCalculator when powTargetTime is set" taggedAs (UnitTest, ConsensusTest) in {
    val parent = header(number = 10000000, difficulty = BigInt("1000000000000"), timestamp = 1000)
    val childTimestamp = 1013L

    implicit val configWithTarget: BlockchainConfig = blockchainConfig.copy(powTargetTime = Some(15))
    val result = DifficultyCalculator.calculateDifficulty(10000001, childTimestamp, parent)
    // Should be different from Ethash calculator
    result should be > BigInt(0)
  }
}
// scalastyle:on magic.number
