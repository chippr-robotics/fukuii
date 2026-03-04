package com.chipprbots.ethereum.domain

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags._

class ChainWeightSpec extends AnyFlatSpec with Matchers {

  def createHeader(
      number: BigInt,
      difficulty: BigInt
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
      extraData = ByteString.empty,
      mixHash = ByteString.empty,
      nonce = ByteString.empty
    )

  "ChainWeight without MESS" should "compare based on total difficulty" taggedAs (UnitTest) in {
    val weight1 = ChainWeight(totalDifficulty = 1000)
    val weight2 = ChainWeight(totalDifficulty = 2000)

    weight1 should be < weight2
    weight2 should be > weight1
  }

  it should "increase weight correctly when adding blocks" taggedAs (UnitTest) in {
    val initialWeight = ChainWeight.zero
    val header = createHeader(number = 1, difficulty = 100)

    val newWeight = initialWeight.increase(header)

    newWeight.totalDifficulty shouldBe BigInt(100)
  }

  "ChainWeight with MESS" should "compare using MESS scores when both have them" taggedAs (UnitTest) in {
    val weight1 = ChainWeight(
      totalDifficulty = 2000,
      messScore = Some(1500) // Lower MESS score due to lateness
    )
    val weight2 = ChainWeight(
      totalDifficulty = 1000,
      messScore = Some(1000)
    )

    // weight1 has higher MESS score despite higher total difficulty
    weight1 should be > weight2
  }

  it should "fall back to total difficulty when only one has MESS score" taggedAs (UnitTest) in {
    val weight1 = ChainWeight(
      totalDifficulty = 2000,
      messScore = None
    )
    val weight2 = ChainWeight(
      totalDifficulty = 1000,
      messScore = Some(500) // Has MESS but other doesn't
    )

    // Falls back to total difficulty comparison
    weight1 should be > weight2
  }

  it should "increase MESS score when adding block with MESS" in {
    val initialWeight = ChainWeight(
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
      totalDifficulty = 1000,
      messScore = Some(900)
    )
    weightWithMess.effectiveScore shouldBe BigInt(900)

    val weightWithoutMess = ChainWeight(
      totalDifficulty = 1000,
      messScore = None
    )
    weightWithoutMess.effectiveScore shouldBe BigInt(1000)
  }

  "ChainWeight.zero" should "have zero values and no MESS score" in {
    ChainWeight.zero.totalDifficulty shouldBe BigInt(0)
    ChainWeight.zero.messScore shouldBe None
  }

  "ChainWeight.totalDifficultyOnly" should "create weight with only difficulty" in {
    val weight = ChainWeight.totalDifficultyOnly(500)
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
