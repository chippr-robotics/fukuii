package com.chipprbots.ethereum.ledger

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags._
import com.chipprbots.ethereum.utils.MonetaryPolicyConfig

/** Explicit ECIP-1017 era boundary tests using named mainnet and testnet configs.
  *
  * BlockRewardCalculatorSpec covers the ECIP-1039 table exhaustively with the standard ETC parameters but without
  * naming the networks. These tests are pinned to production config values for ETC mainnet and Mordor testnet, and
  * verify the reward at real fork block heights (Spiral, era transitions) to serve as regression guards.
  *
  * Reference: ECIP-1017 (ETC monetary policy), core-geth params/config_classic.go (chainId=61), core-geth
  * params/config_mordor.go (chainId=63).
  *
  * ETC mainnet production config (from etc-chain.conf): eraDuration=5,000,000, reductionRate=0.20,
  * firstEraBlockReward=5 ETC, byzantium=10^18 (disabled)
  *
  * Mordor testnet production config (from mordor-chain.conf): eraDuration=2,000,000, reductionRate=0.20,
  * firstEraBlockReward=5 ETC, byzantium=10^18 (disabled)
  */
class ECIP1017EmissionScheduleSpec extends AnyFlatSpec with Matchers {

  // Production configs matching etc-chain.conf and mordor-chain.conf exactly.
  // byzantium and constantinople are set to the sentinel (10^18) so they never activate.
  private val disabled: BigInt = BigInt("1000000000000000000")

  private val etcConfig = MonetaryPolicyConfig(
    eraDuration = 5000000,
    rewardReductionRate = 0.2,
    firstEraBlockReward = BigInt("5000000000000000000"),
    firstEraReducedBlockReward = BigInt("3000000000000000000"),
    firstEraConstantinopleReducedBlockReward = BigInt("2000000000000000000")
  )

  private val mordorConfig = MonetaryPolicyConfig(
    eraDuration = 2000000,
    rewardReductionRate = 0.2,
    firstEraBlockReward = BigInt("5000000000000000000"),
    firstEraReducedBlockReward = BigInt("3000000000000000000"),
    firstEraConstantinopleReducedBlockReward = BigInt("5000000000000000000") // from mordor-chain.conf
  )

  private def etcCalc = new BlockRewardCalculator(etcConfig, disabled, disabled)
  private def mordorCalc = new BlockRewardCalculator(mordorConfig, disabled, disabled)

  // -----------------------------------------------------------------------
  // ETC mainnet era boundaries
  // -----------------------------------------------------------------------

  "ETC mainnet ECIP-1017 emission" should "pay 5 ETC in era 0 (blocks 1-5,000,000)" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    val reward5ETC = BigInt("5000000000000000000")
    etcCalc.calculateMiningRewardForBlock(1) shouldBe reward5ETC
    etcCalc.calculateMiningRewardForBlock(5000000) shouldBe reward5ETC
  }

  it should "pay 4 ETC at the first block of era 1 (block 5,000,001)" taggedAs (UnitTest, ConsensusTest) in {
    etcCalc.calculateMiningRewardForBlock(5000001) shouldBe BigInt("4000000000000000000")
  }

  it should "pay 4 ETC in era 1 (blocks 5,000,001-10,000,000)" taggedAs (UnitTest, ConsensusTest) in {
    val reward4ETC = BigInt("4000000000000000000")
    etcCalc.calculateMiningRewardForBlock(5000001) shouldBe reward4ETC
    etcCalc.calculateMiningRewardForBlock(10000000) shouldBe reward4ETC
  }

  it should "pay 3.2 ETC at the first block of era 2 (block 10,000,001)" taggedAs (UnitTest, ConsensusTest) in {
    etcCalc.calculateMiningRewardForBlock(10000001) shouldBe BigInt("3200000000000000000")
  }

  it should "pay 2.56 ETC at the first block of era 3 (block 15,000,001)" taggedAs (UnitTest, ConsensusTest) in {
    etcCalc.calculateMiningRewardForBlock(15000001) shouldBe BigInt("2560000000000000000")
  }

  it should "pay 2.56 ETC at ETC Spiral fork block (19,250,000 — era 3)" taggedAs (UnitTest, ConsensusTest) in {
    // Spiral fork (ECIP-1109) is at block 19,250,000 on ETC mainnet.
    // Era = (19250000 - 1) / 5000000 = 3 → block reward = 5 * (8/10)^3 = 2.56 ETC
    etcCalc.calculateMiningRewardForBlock(19250000) shouldBe BigInt("2560000000000000000")
  }

  it should "pay 2.048 ETC at the first block of era 4 (block 20,000,001)" taggedAs (UnitTest, ConsensusTest) in {
    etcCalc.calculateMiningRewardForBlock(20000001) shouldBe BigInt("2048000000000000000")
  }

  it should "confirm the era boundary is at the last block of era 0, not the first block" taggedAs
    (UnitTest, ConsensusTest) in {
      // Block 5,000,000 is still era 0: (5000000 - 1) / 5000000 = 0
      etcCalc.calculateMiningRewardForBlock(5000000) shouldBe BigInt("5000000000000000000")
      // Block 5,000,001 is era 1: (5000001 - 1) / 5000000 = 1
      etcCalc.calculateMiningRewardForBlock(5000001) shouldBe BigInt("4000000000000000000")
    }

  // -----------------------------------------------------------------------
  // ETC mainnet uncle inclusion reward (1/32 of block reward)
  // -----------------------------------------------------------------------

  it should "include one uncle in era 1 at 1/32 of the block reward" taggedAs (UnitTest, ConsensusTest) in {
    // In era 1: block reward = 4 ETC; uncle inclusion reward = 4 ETC / 32 = 0.125 ETC
    val expected = BigInt("4000000000000000000") / 32
    etcCalc.calculateMiningRewardForOmmers(5000001, ommersCount = 1) shouldBe expected
  }

  it should "pay uncle miners 1/32 of block reward in era 2+" taggedAs (UnitTest, ConsensusTest) in {
    val blockRewardEra2 = BigInt("3200000000000000000")
    val expectedOmmerReward = blockRewardEra2 / 32
    etcCalc.calculateOmmerRewardForInclusion(10000001, 10000000) shouldBe expectedOmmerReward
  }

  // -----------------------------------------------------------------------
  // Mordor testnet era boundaries (eraDuration = 2,000,000)
  // -----------------------------------------------------------------------

  "Mordor testnet ECIP-1017 emission" should "pay 5 ETC in era 0 (blocks 1-2,000,000)" taggedAs
    (UnitTest, ConsensusTest) in {
      mordorCalc.calculateMiningRewardForBlock(1) shouldBe BigInt("5000000000000000000")
      mordorCalc.calculateMiningRewardForBlock(2000000) shouldBe BigInt("5000000000000000000")
    }

  it should "pay 4 ETC at the first block of era 1 (block 2,000,001)" taggedAs (UnitTest, ConsensusTest) in {
    mordorCalc.calculateMiningRewardForBlock(2000001) shouldBe BigInt("4000000000000000000")
  }

  it should "pay 3.2 ETC at the first block of era 2 (block 4,000,001)" taggedAs (UnitTest, ConsensusTest) in {
    mordorCalc.calculateMiningRewardForBlock(4000001) shouldBe BigInt("3200000000000000000")
  }

  it should "pay 2.048 ETC at the first block of era 4 (block 8,000,001)" taggedAs (UnitTest, ConsensusTest) in {
    // Era 4: 5 * (8/10)^4 = 5 * 0.4096 = 2.048 ETC
    mordorCalc.calculateMiningRewardForBlock(8000001) shouldBe BigInt("2048000000000000000")
  }

  it should "pay 2.048 ETC at Mordor Spiral fork block (9,957,000 — era 4)" taggedAs (UnitTest, ConsensusTest) in {
    // Mordor Spiral is at block 9,957,000. Era = (9957000 - 1) / 2000000 = 4.
    // Block reward = 5 * (8/10)^4 = 2.048 ETC
    mordorCalc.calculateMiningRewardForBlock(9957000) shouldBe BigInt("2048000000000000000")
  }

  // -----------------------------------------------------------------------
  // Per-network: era duration differs between ETC and Mordor
  // -----------------------------------------------------------------------

  "ETC vs Mordor era schedule" should "use different era durations (5M vs 2M blocks)" taggedAs
    (UnitTest, ConsensusTest) in {
      // Block 3,000,000: ETC still in era 0 (5 ETC), Mordor already in era 1 (4 ETC)
      etcCalc.calculateMiningRewardForBlock(3000000) shouldBe BigInt("5000000000000000000")
      mordorCalc.calculateMiningRewardForBlock(3000000) shouldBe BigInt("4000000000000000000")
    }

  it should "converge on the same reward in matching eras" taggedAs (UnitTest, ConsensusTest) in {
    // Both networks use the same reduction ratio — era N reward is the same regardless of eraDuration.
    // ETC era 2 starts at block 10,000,001; Mordor era 2 starts at block 4,000,001.
    // Both should pay 3.2 ETC.
    etcCalc.calculateMiningRewardForBlock(10000001) shouldBe mordorCalc.calculateMiningRewardForBlock(4000001)
  }
}
