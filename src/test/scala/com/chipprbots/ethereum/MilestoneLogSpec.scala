package com.chipprbots.ethereum

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags._
import com.chipprbots.ethereum.utils.ForkBlockNumbers

// scalastyle:off magic.number
class MilestoneLogSpec extends AnyFlatSpec with Matchers {

  "MilestoneLog.formatMilestones" should "show '[]' when all blocks are Long.MaxValue" taggedAs UnitTest in {
    val empty = ForkBlockNumbers.Empty.copy(frontierBlockNumber = Long.MaxValue)
    MilestoneLog.formatMilestones(empty) shouldBe "[]"
  }

  it should "include Frontier when set to 0" taggedAs UnitTest in {
    val forks = ForkBlockNumbers.Empty
    val result = MilestoneLog.formatMilestones(forks)
    result should include("Frontier:0")
  }

  it should "omit forks set to Long.MaxValue" taggedAs UnitTest in {
    val forks = ForkBlockNumbers.Empty // only Frontier=0, rest=Long.MaxValue
    val result = MilestoneLog.formatMilestones(forks)
    (result should not).include("Homestead")
    (result should not).include("Olympia")
  }

  it should "include Olympia when activated" taggedAs UnitTest in {
    val forks = ForkBlockNumbers.Empty.copy(olympiaBlockNumber = BigInt(30_000_000))
    val result = MilestoneLog.formatMilestones(forks)
    result should include("Olympia:30000000")
  }

  it should "wrap output in brackets (Besu format)" taggedAs UnitTest in {
    val forks = ForkBlockNumbers.Empty
    val result = MilestoneLog.formatMilestones(forks)
    result should startWith("[")
    result should endWith("]")
  }

  it should "list milestones in order" taggedAs UnitTest in {
    val forks = ForkBlockNumbers.Empty.copy(
      homesteadBlockNumber = BigInt(1_150_000),
      spiralBlockNumber = BigInt(19_250_000),
      olympiaBlockNumber = BigInt(30_000_000)
    )
    val result = MilestoneLog.formatMilestones(forks)
    val homesteadIdx = result.indexOf("Homestead")
    val spiralIdx = result.indexOf("Spiral")
    val olympiaIdx = result.indexOf("Olympia")

    homesteadIdx should be < spiralIdx
    spiralIdx should be < olympiaIdx
  }

  it should "produce correct milestone output for ETC mainnet" taggedAs UnitTest in {
    // Block numbers sourced from core-geth params/config_classic.go
    // ETH-only forks (EIP-106, EIP-161, Byzantium, Constantinople, Istanbul, Petersburg,
    // Muir Glacier, Berlin) use Long.MaxValue — never activated on ETC mainnet
    val forks = ForkBlockNumbers(
      frontierBlockNumber = 0,
      homesteadBlockNumber = 1_150_000,
      eip106BlockNumber = Long.MaxValue,
      eip150BlockNumber = 2_500_000,
      eip155BlockNumber = 3_000_000,
      eip160BlockNumber = 3_000_000,
      eip161BlockNumber = Long.MaxValue,
      difficultyBombPauseBlockNumber = 3_000_000, // ECIP-1010
      difficultyBombContinueBlockNumber = 5_000_000, // ECIP-1010 pause length 2,000,000
      difficultyBombRemovalBlockNumber = 5_900_000, // DisposalBlock
      byzantiumBlockNumber = Long.MaxValue,
      constantinopleBlockNumber = Long.MaxValue,
      istanbulBlockNumber = Long.MaxValue,
      atlantisBlockNumber = 8_772_000,
      aghartaBlockNumber = 9_573_000,
      phoenixBlockNumber = 10_500_839,
      petersburgBlockNumber = Long.MaxValue,
      ecip1099BlockNumber = 11_700_000,
      muirGlacierBlockNumber = Long.MaxValue,
      magnetoBlockNumber = 13_189_133,
      berlinBlockNumber = Long.MaxValue,
      mystiqueBlockNumber = 14_525_000,
      spiralBlockNumber = 19_250_000,
      olympiaBlockNumber = Long.MaxValue // not yet scheduled on ETC mainnet
    )
    val result = MilestoneLog.formatMilestones(forks)
    result should include("Frontier:0")
    result should include("Homestead:1150000")
    result should include("EIP-150:2500000")
    result should include("EIP-155:3000000")
    result should include("DiffBomb-Pause:3000000")
    result should include("DiffBomb-Continue:5000000")
    result should include("DiffBomb-Removal:5900000")
    result should include("Atlantis:8772000")
    result should include("Agharta:9573000")
    result should include("Phoenix:10500839")
    result should include("ECIP-1099:11700000")
    result should include("Magneto:13189133")
    result should include("Mystique:14525000")
    result should include("Spiral:19250000")
    (result should not).include("Olympia")
    (result should not).include("Byzantium")
    (result should not).include("Constantinople")
    (result should not).include("Muir Glacier")
    (result should not).include("Berlin")
  }

  it should "produce correct milestone output for Mordor testnet" taggedAs UnitTest in {
    // Block numbers sourced from core-geth params/config_mordor.go
    // All genesis forks (Frontier through Atlantis) activated at block 0
    // ETH-only forks use Long.MaxValue — never activated on Mordor
    val forks = ForkBlockNumbers(
      frontierBlockNumber = 0,
      homesteadBlockNumber = 0,
      eip106BlockNumber = Long.MaxValue,
      eip150BlockNumber = 0,
      eip155BlockNumber = 0,
      eip160BlockNumber = 0,
      eip161BlockNumber = Long.MaxValue,
      difficultyBombPauseBlockNumber = 0,
      difficultyBombContinueBlockNumber = 0,
      difficultyBombRemovalBlockNumber = 0,
      byzantiumBlockNumber = Long.MaxValue,
      constantinopleBlockNumber = Long.MaxValue,
      istanbulBlockNumber = Long.MaxValue,
      atlantisBlockNumber = 0,
      aghartaBlockNumber = 301_243,
      phoenixBlockNumber = 999_983,
      petersburgBlockNumber = Long.MaxValue,
      ecip1099BlockNumber = 2_520_000,
      muirGlacierBlockNumber = Long.MaxValue,
      magnetoBlockNumber = 3_985_893,
      berlinBlockNumber = Long.MaxValue,
      mystiqueBlockNumber = 5_520_000,
      spiralBlockNumber = 9_957_000,
      olympiaBlockNumber = Long.MaxValue // not yet activated on Mordor
    )
    val result = MilestoneLog.formatMilestones(forks)
    result should include("Frontier:0")
    result should include("Homestead:0")
    result should include("EIP-150:0")
    result should include("Atlantis:0")
    result should include("Agharta:301243")
    result should include("Phoenix:999983")
    result should include("ECIP-1099:2520000")
    result should include("Magneto:3985893")
    result should include("Mystique:5520000")
    result should include("Spiral:9957000")
    (result should not).include("Olympia")
    (result should not).include("Byzantium")
    (result should not).include("Berlin")
    (result should not).include("Muir Glacier")
  }

  it should "include all 24 named milestones in the ETC mainnet-equivalent config" taggedAs UnitTest in {
    val forks = ForkBlockNumbers(
      frontierBlockNumber = 0,
      homesteadBlockNumber = 1_150_000,
      eip106BlockNumber = 2_463_000,
      eip150BlockNumber = 2_500_000,
      eip155BlockNumber = 3_000_000,
      eip160BlockNumber = 3_000_000,
      eip161BlockNumber = 3_000_000,
      difficultyBombPauseBlockNumber = 3_000_000,
      difficultyBombContinueBlockNumber = 5_900_000,
      difficultyBombRemovalBlockNumber = 5_900_000,
      byzantiumBlockNumber = 8_772_000,
      constantinopleBlockNumber = 9_573_000,
      istanbulBlockNumber = 10_500_839,
      atlantisBlockNumber = 8_772_000,
      aghartaBlockNumber = 9_573_000,
      phoenixBlockNumber = 10_500_839,
      petersburgBlockNumber = 9_573_000,
      ecip1099BlockNumber = 11_700_000,
      muirGlacierBlockNumber = Long.MaxValue,
      magnetoBlockNumber = 13_189_133,
      berlinBlockNumber = 13_189_133,
      mystiqueBlockNumber = 14_525_000,
      spiralBlockNumber = 19_250_000,
      olympiaBlockNumber = Long.MaxValue // pending
    )
    val result = MilestoneLog.formatMilestones(forks)
    result should include("Frontier:0")
    result should include("Homestead:1150000")
    result should include("Spiral:19250000")
    (result should not).include("Olympia") // not activated
    (result should not).include("Muir Glacier") // not activated
  }
}
// scalastyle:on magic.number
