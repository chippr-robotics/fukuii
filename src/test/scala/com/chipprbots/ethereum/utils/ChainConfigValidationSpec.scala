package com.chipprbots.ethereum.utils

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.typesafe.config.ConfigFactory

import com.chipprbots.ethereum.testing.Tags._

// scalastyle:off magic.number
/** Validates that ETC mainnet and Mordor chain configurations load correctly from HOCON
  * config files and contain the expected fork block numbers, ECBP-1100 (MESS) activation
  * windows, and monetary policy parameters.
  *
  * Reference: Besu's GenesisConfigClassicTest (18 tests validating config parsing)
  */
class ChainConfigValidationSpec extends AnyFlatSpec with Matchers {

  // Load the full application config (includes blockchains.conf which includes chain configs)
  private val fullConfig = ConfigFactory.load()

  private val etcConfig = BlockchainConfig.fromRawConfig(fullConfig.getConfig("fukuii.blockchains.etc"))
  private val mordorConfig = BlockchainConfig.fromRawConfig(fullConfig.getConfig("fukuii.blockchains.mordor"))

  // ===== ETC Mainnet Chain Identity =====

  "ETC mainnet config" should "have correct chain ID and network ID" taggedAs (UnitTest, ConsensusTest) in {
    etcConfig.chainId shouldBe 61
    etcConfig.networkId shouldBe 1
  }

  // ===== ETC Mainnet Fork Block Numbers =====

  it should "have correct pre-DAO fork block numbers" taggedAs (UnitTest, ConsensusTest) in {
    etcConfig.forkBlockNumbers.frontierBlockNumber shouldBe 0
    etcConfig.forkBlockNumbers.homesteadBlockNumber shouldBe 1150000
    etcConfig.forkBlockNumbers.eip150BlockNumber shouldBe 2500000
    etcConfig.forkBlockNumbers.eip155BlockNumber shouldBe 3000000
    etcConfig.forkBlockNumbers.eip160BlockNumber shouldBe 3000000
  }

  it should "have correct ETC-specific fork block numbers" taggedAs (UnitTest, ConsensusTest) in {
    etcConfig.forkBlockNumbers.atlantisBlockNumber shouldBe 8772000
    etcConfig.forkBlockNumbers.aghartaBlockNumber shouldBe 9573000
    etcConfig.forkBlockNumbers.phoenixBlockNumber shouldBe 10500839
    etcConfig.forkBlockNumbers.magnetoBlockNumber shouldBe 13189133
    etcConfig.forkBlockNumbers.mystiqueBlockNumber shouldBe 14525000
    etcConfig.forkBlockNumbers.spiralBlockNumber shouldBe 19250000
  }

  it should "have correct difficulty bomb configuration" taggedAs (UnitTest, ConsensusTest) in {
    // ECIP-1010: pause at DieHard (3M), continue at Gotham (5M)
    etcConfig.forkBlockNumbers.difficultyBombPauseBlockNumber shouldBe 3000000
    etcConfig.forkBlockNumbers.difficultyBombContinueBlockNumber shouldBe 5000000
    // ECIP-1041: remove at Defuse (5.9M)
    etcConfig.forkBlockNumbers.difficultyBombRemovalBlockNumber shouldBe 5900000
  }

  it should "have correct ECIP-1099 epoch doubling block" taggedAs (UnitTest, ConsensusTest) in {
    etcConfig.forkBlockNumbers.ecip1099BlockNumber shouldBe 11700000
  }

  // ===== ETC Mainnet ECBP-1100 (MESS) Configuration =====

  it should "have correct ECBP-1100 (MESS) activation window" taggedAs (UnitTest, ConsensusTest) in {
    etcConfig.messConfig.activationBlock shouldBe Some(BigInt(11380000))
    etcConfig.messConfig.deactivationBlock shouldBe Some(BigInt(19250000))
  }

  // ===== ETC Mainnet Monetary Policy =====

  it should "have correct monetary policy era duration" taggedAs (UnitTest, ConsensusTest) in {
    etcConfig.monetaryPolicyConfig.eraDuration shouldBe 5000000
  }

  // ===== ETC Mainnet Fork Ordering =====

  it should "have fork block numbers in strictly non-decreasing order" taggedAs (UnitTest, ConsensusTest) in {
    val forks = etcConfig.forkBlockNumbers
    val etcForkBlocks = List(
      forks.frontierBlockNumber,
      forks.homesteadBlockNumber,
      forks.eip150BlockNumber,
      forks.eip155BlockNumber,
      forks.atlantisBlockNumber,
      forks.aghartaBlockNumber,
      forks.phoenixBlockNumber,
      forks.magnetoBlockNumber,
      forks.mystiqueBlockNumber,
      forks.spiralBlockNumber
    )

    etcForkBlocks.sliding(2).foreach { case List(a, b) =>
      b should be >= a
    }
  }

  // ===== Mordor Chain Identity =====

  "Mordor config" should "have correct chain ID and network ID" taggedAs (UnitTest, ConsensusTest) in {
    mordorConfig.chainId shouldBe 63
    mordorConfig.networkId shouldBe 7
  }

  // ===== Mordor Fork Block Numbers =====

  it should "have correct fork block numbers" taggedAs (UnitTest, ConsensusTest) in {
    mordorConfig.forkBlockNumbers.atlantisBlockNumber shouldBe 0
    mordorConfig.forkBlockNumbers.aghartaBlockNumber shouldBe 301243
    mordorConfig.forkBlockNumbers.phoenixBlockNumber shouldBe 999983
  }

  it should "have correct ECIP-1099 epoch doubling block" taggedAs (UnitTest, ConsensusTest) in {
    mordorConfig.forkBlockNumbers.ecip1099BlockNumber shouldBe 2520000
  }

  // ===== Mordor ECBP-1100 (MESS) Configuration =====

  it should "have correct ECBP-1100 (MESS) activation window" taggedAs (UnitTest, ConsensusTest) in {
    mordorConfig.messConfig.activationBlock shouldBe Some(BigInt(2380000))
    mordorConfig.messConfig.deactivationBlock shouldBe Some(BigInt(10400000))
  }

  // ===== Mordor Difficulty Bomb (all removed at genesis) =====

  it should "have difficulty bomb removed from genesis" taggedAs (UnitTest, ConsensusTest) in {
    mordorConfig.forkBlockNumbers.difficultyBombPauseBlockNumber shouldBe 0
    mordorConfig.forkBlockNumbers.difficultyBombContinueBlockNumber shouldBe 0
    mordorConfig.forkBlockNumbers.difficultyBombRemovalBlockNumber shouldBe 0
  }
}
// scalastyle:on magic.number
