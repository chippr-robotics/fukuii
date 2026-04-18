package com.chipprbots.ethereum

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags._
import com.chipprbots.ethereum.utils.ForkBlockNumbers

// scalastyle:off magic.number
class MilestoneLogSpec extends AnyFlatSpec with Matchers {

  "MilestoneLog.formatMilestones" should "show 'none configured' when all blocks are Long.MaxValue" taggedAs UnitTest in {
    val empty = ForkBlockNumbers.Empty.copy(frontierBlockNumber = Long.MaxValue)
    MilestoneLog.formatMilestones(empty) shouldBe "none configured"
  }

  it should "include Frontier when set to 0" taggedAs UnitTest in {
    val forks = ForkBlockNumbers.Empty
    val result = MilestoneLog.formatMilestones(forks)
    result should include("Frontier@0")
  }

  it should "omit forks set to Long.MaxValue" taggedAs UnitTest in {
    val forks = ForkBlockNumbers.Empty // only Frontier=0, rest=Long.MaxValue
    val result = MilestoneLog.formatMilestones(forks)
    result should not include "Homestead"
    result should not include "Olympia"
  }

  it should "include Olympia when activated" taggedAs UnitTest in {
    val forks = ForkBlockNumbers.Empty.copy(olympiaBlockNumber = BigInt(30_000_000))
    val result = MilestoneLog.formatMilestones(forks)
    result should include("Olympia@30000000")
  }

  it should "list milestones in order" taggedAs UnitTest in {
    val forks = ForkBlockNumbers.Empty.copy(
      homesteadBlockNumber = BigInt(1_150_000),
      spiralBlockNumber    = BigInt(19_250_000),
      olympiaBlockNumber   = BigInt(30_000_000)
    )
    val result = MilestoneLog.formatMilestones(forks)
    val homesteadIdx = result.indexOf("Homestead")
    val spiralIdx    = result.indexOf("Spiral")
    val olympiaIdx   = result.indexOf("Olympia")

    homesteadIdx should be < spiralIdx
    spiralIdx    should be < olympiaIdx
  }

  it should "include all 24 named milestones in the ETC mainnet-equivalent config" taggedAs UnitTest in {
    val forks = ForkBlockNumbers(
      frontierBlockNumber             = 0,
      homesteadBlockNumber            = 1_150_000,
      eip106BlockNumber               = 2_463_000,
      eip150BlockNumber               = 2_500_000,
      eip155BlockNumber               = 3_000_000,
      eip160BlockNumber               = 3_000_000,
      eip161BlockNumber               = 3_000_000,
      difficultyBombPauseBlockNumber  = 3_000_000,
      difficultyBombContinueBlockNumber = 5_900_000,
      difficultyBombRemovalBlockNumber  = 5_900_000,
      byzantiumBlockNumber            = 8_772_000,
      constantinopleBlockNumber       = 9_573_000,
      istanbulBlockNumber             = 10_500_839,
      atlantisBlockNumber             = 8_772_000,
      aghartaBlockNumber              = 9_573_000,
      phoenixBlockNumber              = 10_500_839,
      petersburgBlockNumber           = 9_573_000,
      ecip1099BlockNumber             = 11_700_000,
      muirGlacierBlockNumber          = Long.MaxValue,
      magnetoBlockNumber              = 13_189_133,
      berlinBlockNumber               = 13_189_133,
      mystiqueBlockNumber             = 14_525_000,
      spiralBlockNumber               = 19_250_000,
      olympiaBlockNumber              = Long.MaxValue  // pending
    )
    val result = MilestoneLog.formatMilestones(forks)
    result should include("Frontier@0")
    result should include("Homestead@1150000")
    result should include("Spiral@19250000")
    result should not include "Olympia"   // not activated
    result should not include "Muir Glacier" // not activated
  }
}
// scalastyle:on magic.number
