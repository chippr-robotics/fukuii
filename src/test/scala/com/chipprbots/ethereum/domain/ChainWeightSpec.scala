package com.chipprbots.ethereum.domain

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags._

class ChainWeightSpec extends AnyFlatSpec with Matchers {

  def createHeader(
      number: BigInt,
      difficulty: BigInt,
      hasCheckpoint: Boolean = false
  ): BlockHeader =
    BlockHeader(
      parentHash = ByteString.empty,
      ommersHash = ByteString.empty,
      beneficiary = ByteString.empty,
      stateRoot = ByteString.empty,
      transactionsRoot = ByteString.empty,
      receiptsRoot = ByteString.empty,
      logsBloom = ByteString.empty,
      difficulty = difficulty,
      number = number,
      gasLimit = 0,
      gasUsed = 0,
      unixTimestamp = 0,
      extraData = if (hasCheckpoint) ByteString("ECIP-1066") else ByteString.empty,
      mixHash = ByteString.empty,
      nonce = ByteString.empty
    )

  "ChainWeight without MESS" should "compare based on checkpoint and total difficulty" taggedAs(UnitTest) in {
    val weight1 = ChainWeight(lastCheckpointNumber = 0, totalDifficulty = 1000)
    val weight2 = ChainWeight(lastCheckpointNumber = 0, totalDifficulty = 2000)

    weight1 should be < weight2
    weight2 should be > weight1
  }

  it should "prioritize checkpoint number over difficulty" taggedAs(UnitTest) in {
    val weight1 = ChainWeight(lastCheckpointNumber = 1, totalDifficulty = 1000)
    val weight2 = ChainWeight(lastCheckpointNumber = 0, totalDifficulty = 9000)

    weight1 should be > weight2 // Checkpoint wins despite lower difficulty
  }

  it should "increase weight correctly when adding blocks" taggedAs(UnitTest) in {
    val initialWeight = ChainWeight.zero
    val header = createHeader(number = 1, difficulty = 100)

    val newWeight = initialWeight.increase(header)

    newWeight.totalDifficulty shouldBe BigInt(100)
    newWeight.lastCheckpointNumber shouldBe BigInt(0)
  }

  it should "update checkpoint number when adding checkpoint block" taggedAs(UnitTest) in {
    val initialWeight = ChainWeight.zero
    val checkpointHeader = createHeader(number = 100, difficulty = 100, hasCheckpoint = true)

    val newWeight = initialWeight.increase(checkpointHeader)

    newWeight.totalDifficulty shouldBe BigInt(100)
    newWeight.lastCheckpointNumber shouldBe BigInt(100)
  }

  "ChainWeight with MESS" should "compare using MESS scores when both have them" taggedAs(UnitTest) in {
    val weight1 = ChainWeight(
      lastCheckpointNumber = 0,
      totalDifficulty = 2000,
      messScore = Some(1500) // Lower MESS score due to lateness
    )
    val weight2 = ChainWeight(
      lastCheckpointNumber = 0,
      totalDifficulty = 1000,
      messScore = Some(1000)
    )

    // weight1 has higher MESS score despite higher total difficulty
    weight1 should be > weight2
  }

  it should "fall back to total difficulty when only one has MESS score" taggedAs(UnitTest) in {
    val weight1 = ChainWeight(
      lastCheckpointNumber = 0,
      totalDifficulty = 2000,
      messScore = None
    )
    val weight2 = ChainWeight(
      lastCheckpointNumber = 0,
      totalDifficulty = 1000,
      messScore = Some(500) // Has MESS but other doesn't
    )

    // Falls back to total difficulty comparison
    weight1 should be > weight2
  }

  it should "still prioritize checkpoint over MESS score" in {
    val weight1 = ChainWeight(
      lastCheckpointNumber = 1,
      totalDifficulty = 1000,
      messScore = Some(500)
    )
    val weight2 = ChainWeight(
      lastCheckpointNumber = 0,
      totalDifficulty = 5000,
      messScore = Some(5000)
    )

    // Checkpoint number takes precedence
    weight1 should be > weight2
  }

  it should "increase MESS score when adding block with MESS" in {
    val initialWeight = ChainWeight(
      lastCheckpointNumber = 0,
      totalDifficulty = 100,
      messScore = Some(100)
    )
    val header = createHeader(number = 1, difficulty = 50)
    val messAdjustedDifficulty = BigInt(40) // Slightly lower due to MESS penalty

    val newWeight = initialWeight.increase(header, Some(messAdjustedDifficulty))

    newWeight.totalDifficulty shouldBe BigInt(150) // 100 + 50
    newWeight.messScore shouldBe Some(BigInt(140)) // 100 + 40
  }

  it should "initialize MESS score if first block has MESS adjustment" in {
    val initialWeight = ChainWeight.zero // No MESS score yet
    val header = createHeader(number = 1, difficulty = 100)
    val messAdjustedDifficulty = BigInt(90)

    val newWeight = initialWeight.increase(header, Some(messAdjustedDifficulty))

    newWeight.totalDifficulty shouldBe BigInt(100)
    newWeight.messScore shouldBe Some(BigInt(90))
  }

  it should "return effective score based on MESS availability" in {
    val weightWithMess = ChainWeight(
      lastCheckpointNumber = 0,
      totalDifficulty = 1000,
      messScore = Some(900)
    )
    weightWithMess.effectiveScore shouldBe BigInt(900)

    val weightWithoutMess = ChainWeight(
      lastCheckpointNumber = 0,
      totalDifficulty = 1000,
      messScore = None
    )
    weightWithoutMess.effectiveScore shouldBe BigInt(1000)
  }

  "ChainWeight.zero" should "have zero values and no MESS score" in {
    ChainWeight.zero.lastCheckpointNumber shouldBe BigInt(0)
    ChainWeight.zero.totalDifficulty shouldBe BigInt(0)
    ChainWeight.zero.messScore shouldBe None
  }

  "ChainWeight.totalDifficultyOnly" should "create weight with only difficulty" in {
    val weight = ChainWeight.totalDifficultyOnly(500)
    weight.lastCheckpointNumber shouldBe BigInt(0)
    weight.totalDifficulty shouldBe BigInt(500)
    weight.messScore shouldBe None
  }

  "ChainWeight test API" should "allow increasing total difficulty" in {
    val weight = ChainWeight.zero
    val increased = weight.increaseTotalDifficulty(100)

    increased.totalDifficulty shouldBe BigInt(100)
    increased.messScore shouldBe None
  }

  it should "allow increasing MESS score" in {
    val weight = ChainWeight.zero
    val increased = weight.increaseMessScore(50)

    increased.messScore shouldBe Some(BigInt(50))
    increased.totalDifficulty shouldBe BigInt(0)

    val increasedAgain = increased.increaseMessScore(30)
    increasedAgain.messScore shouldBe Some(BigInt(80))
  }
}
