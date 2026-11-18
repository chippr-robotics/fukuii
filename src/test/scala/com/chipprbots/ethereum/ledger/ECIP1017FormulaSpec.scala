package com.chipprbots.ethereum.ledger

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.snap.PowHeaderFixtures
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig

/** Spec 018 Track C, C6 (T238a): the ECIP-1017 rewards the node pays, held to the formula and not to a table.
  *
  * ECIP-1017 (with ECIP-1039's rounding, as core-geth computes it in `ecip1017.go`), for block `n` in era `e = (n - 1)
  * / eraLength`:
  *   - the block reward is `5 ETC * 0.8^e`, that is `5 ETC * 4^e / 5^e`, rounded down;
  *   - its miner is paid that, plus `reward / 32` (rounded down) for each ommer it includes;
  *   - the miner of an ommer `u` is paid `(8 + u - n) / 8 * 5 ETC` in era 0, and `reward / 32` from era 1.
  *
  * The era length is read from the shipped chain configuration (5,000,000 blocks on ETC mainnet, 2,000,000 on Mordor),
  * and the node's calculator is the one block import uses ([[BlockRewards.calculator]], under that configuration).
  */
class ECIP1017FormulaSpec extends AnyFlatSpec with Matchers:

  private val FiveEtc = BigInt("5000000000000000000")

  private def era(n: BigInt, eraLength: BigInt): Int = ((n - 1) / eraLength).toInt
  private def blockReward(n: BigInt, eraLength: BigInt): BigInt =
    val e = era(n, eraLength)
    FiveEtc * BigInt(4).pow(e) / BigInt(5).pow(e)
  private def inclusionReward(n: BigInt, eraLength: BigInt): BigInt = blockReward(n, eraLength) / 32
  private def ommerReward(n: BigInt, u: BigInt, eraLength: BigInt): BigInt =
    if era(n, eraLength) == 0 then (8 + u - n) * FiveEtc / 8 else blockReward(n, eraLength) / 32

  /** Every height around each era boundary up to `eras`, and `extra` heights of the chain's own. */
  private def heights(eraLength: BigInt, eras: Int, extra: Seq[BigInt]): Seq[BigInt] =
    (Seq[BigInt](1, 2, 7) ++ (1 to eras).flatMap { k =>
      val boundary = eraLength * k
      Seq(boundary - 1, boundary, boundary + 1, boundary + 2)
    } ++ extra).distinct.sorted

  private def holds(config: BlockchainConfig, eraLength: BigInt, at: Seq[BigInt]): Int =
    given BlockchainConfig = config
    val calculator = BlockRewards.calculator
    // the ETH-only reward steps never apply on these chains, at any height held here
    config.forkBlockNumbers.byzantiumBlockNumber should be > at.max
    config.forkBlockNumbers.constantinopleBlockNumber should be > at.max
    var checked = 0
    at.foreach { n =>
      withClue(s"block $n (era ${era(n, eraLength)}): ") {
        calculator.calculateMiningRewardForBlock(n) shouldBe blockReward(n, eraLength)
        (0 to 2).foreach { k =>
          calculator.calculateMiningRewardForOmmers(n, k) shouldBe inclusionReward(n, eraLength) * k
        }
        // an ommer is 1 to 6 blocks behind the block that includes it
        (1 to 6).map(d => n - d).filter(_ >= 0).foreach { u =>
          withClue(s"ommer $u: ")(
            calculator.calculateOmmerRewardForInclusion(n, u) shouldBe ommerReward(n, u, eraLength)
          )
          checked += 1
        }
      }
      checked += 1
    }
    checked

  "ECIP-1017 on ETC mainnet" should "pay the formula's block, inclusion and ommer rewards across the eras" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    val config = PowHeaderFixtures.etcConfig
    val eraLength = BigInt(config.monetaryPolicyConfig.eraDuration)
    eraLength shouldBe BigInt(5000000)
    config.monetaryPolicyConfig.firstEraBlockReward shouldBe FiveEtc
    config.monetaryPolicyConfig.rewardReductionRate shouldBe 0.2
    // the formula's own anchors: 5, 4, 3.2, 2.56 ETC
    Seq[BigInt](1, 5000001, 10000001, 15000001).map(blockReward(_, eraLength)) shouldBe
      Seq("5000000000000000000", "4000000000000000000", "3200000000000000000", "2560000000000000000").map(BigInt(_))
    val forkHeights = Seq[BigInt](8772000, 9573000, 10500839, 11700000, 13189133, 14525000, 19250000)
    val checked = holds(config, eraLength, heights(eraLength, 40, forkHeights))
    info(s"$checked block and ommer rewards held to the formula, eras 0 to 40")
  }

  it should "pay an ommer by the era of the block that includes it, across the 5,000,000 boundary" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    given BlockchainConfig = PowHeaderFixtures.etcConfig
    val calculator = BlockRewards.calculator
    // block 5,000,000 is the last of era 0: an ommer one behind is paid 7/8 of 5 ETC, the miner 5 + 5/32 ETC
    calculator.calculateOmmerRewardForInclusion(5000000, 4999999) shouldBe BigInt("4375000000000000000")
    calculator.calculateMiningRewardForBlock(5000000) + calculator.calculateMiningRewardForOmmers(5000000, 1) shouldBe
      BigInt("5156250000000000000")
    // block 5,000,001 is the first of era 1: the same distance is paid 4 ETC / 32, the miner 4 + 4/32 ETC
    calculator.calculateOmmerRewardForInclusion(5000001, 5000000) shouldBe BigInt("125000000000000000")
    calculator.calculateMiningRewardForBlock(5000001) + calculator.calculateMiningRewardForOmmers(5000001, 1) shouldBe
      BigInt("4125000000000000000")
    // and in era 0 the ommer reward falls with the distance: 2/8 at six blocks
    calculator.calculateOmmerRewardForInclusion(4999999, 4999993) shouldBe BigInt("1250000000000000000")
  }

  "ECIP-1017 on Mordor" should "pay the formula's rewards with Mordor's era length, read from its configuration" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    val config = PowHeaderFixtures.mordorConfig
    val eraLength = BigInt(config.monetaryPolicyConfig.eraDuration)
    // core-geth's Mordor: ECIP1017EraRounds = 2,000,000
    eraLength shouldBe BigInt(2000000)
    config.monetaryPolicyConfig.firstEraBlockReward shouldBe FiveEtc
    config.monetaryPolicyConfig.rewardReductionRate shouldBe 0.2
    val forkHeights = Seq[BigInt](301243, 999983, 2520000, 3985893, 5520000, 9957000, 5000000, 5000001)
    val checked = holds(config, eraLength, heights(eraLength, 40, forkHeights))
    // Mordor's boundaries are not mainnet's: block 2,000,001 is era 1 there, and era 0 on mainnet
    given BlockchainConfig = config
    BlockRewards.calculator.calculateMiningRewardForBlock(2000001) shouldBe BigInt("4000000000000000000")
    BlockRewards.calculator.calculateOmmerRewardForInclusion(2000001, 2000000) shouldBe BigInt("125000000000000000")
    BlockRewards.calculator.calculateOmmerRewardForInclusion(2000000, 1999999) shouldBe BigInt("4375000000000000000")
    info(s"$checked block and ommer rewards held to the formula, eras 0 to 40")
  }
