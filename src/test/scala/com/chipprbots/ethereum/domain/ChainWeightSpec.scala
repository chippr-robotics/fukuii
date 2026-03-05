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

  "ChainWeight" should "compare based on total difficulty" taggedAs (UnitTest) in {
    val weight1 = ChainWeight(totalDifficulty = 1000)
    val weight2 = ChainWeight(totalDifficulty = 2000)

    weight1 should be < weight2
    weight2 should be > weight1
  }

  it should "be equal when total difficulty is the same" taggedAs (UnitTest) in {
    val weight1 = ChainWeight(totalDifficulty = 1000)
    val weight2 = ChainWeight(totalDifficulty = 1000)

    weight1.compare(weight2) shouldBe 0
  }

  it should "increase weight correctly when adding blocks" taggedAs (UnitTest) in {
    val initialWeight = ChainWeight.zero
    val header = createHeader(number = 1, difficulty = 100)

    val newWeight = initialWeight.increase(header)

    newWeight.totalDifficulty shouldBe BigInt(100)
  }

  it should "accumulate difficulty across multiple blocks" taggedAs (UnitTest) in {
    val header1 = createHeader(number = 1, difficulty = 100)
    val header2 = createHeader(number = 2, difficulty = 200)
    val header3 = createHeader(number = 3, difficulty = 300)

    val weight = ChainWeight.zero
      .increase(header1)
      .increase(header2)
      .increase(header3)

    weight.totalDifficulty shouldBe BigInt(600)
  }

  "ChainWeight.zero" should "have zero total difficulty" in {
    ChainWeight.zero.totalDifficulty shouldBe BigInt(0)
  }

  "ChainWeight.totalDifficultyOnly" should "create weight with specified difficulty" in {
    val weight = ChainWeight.totalDifficultyOnly(500)
    weight.totalDifficulty shouldBe BigInt(500)
  }

  "ChainWeight test API" should "allow increasing total difficulty" in {
    val weight = ChainWeight.zero
    val increased = weight.increaseTotalDifficulty(100)

    increased.totalDifficulty shouldBe BigInt(100)
  }
}
