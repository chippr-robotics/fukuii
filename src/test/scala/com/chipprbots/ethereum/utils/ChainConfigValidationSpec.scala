package com.chipprbots.ethereum.utils

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.typesafe.config.ConfigFactory

import com.chipprbots.ethereum.testing.Tags._

// scalastyle:off magic.number
/** Validates that ETC mainnet and Mordor chain configurations load correctly from HOCON config files and contain the
  * expected fork block numbers, ECBP-1100 (MESS) activation windows, and monetary policy parameters.
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

    etcForkBlocks.sliding(2).foreach {
      case List(a, b) => b should be >= a
      case _          => ()
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

// scalastyle:off magic.number
/** L3 — ETC DAO fork exclusion.
  *
  * ETC's defining property: it did NOT follow the ETH DAO bailout. The DAO config exists in the chain config ONLY to
  * anchor the fork ID list (so ETC nodes reject ETH peers). The drain list and refund contract are absent, meaning no
  * funds are ever moved at block 1,920,000.
  *
  * On Mordor there is no DAO config at all.
  */
class ETCDaoExclusionSpec extends AnyFlatSpec with Matchers {

  private val fullConfig = ConfigFactory.load()
  private val etcConfig = BlockchainConfig.fromRawConfig(fullConfig.getConfig("fukuii.blockchains.etc"))
  private val mordorConfig = BlockchainConfig.fromRawConfig(fullConfig.getConfig("fukuii.blockchains.mordor"))

  "ETC mainnet DAO config" should "be present (for fork ID tracking)" taggedAs (UnitTest, ConsensusTest) in {
    etcConfig.daoForkConfig shouldBe defined
  }

  it should "record fork block number 1,920,000" taggedAs (UnitTest, ConsensusTest) in {
    etcConfig.daoForkConfig.get.forkBlockNumber shouldBe BigInt(1_920_000)
  }

  it should "have an empty drain list (no funds moved on ETC)" taggedAs (UnitTest, ConsensusTest) in {
    etcConfig.daoForkConfig.get.drainList shouldBe empty
  }

  it should "have no refund contract (no drain destination)" taggedAs (UnitTest, ConsensusTest) in {
    etcConfig.daoForkConfig.get.refundContract shouldBe None
  }

  it should "exclude the DAO fork from the fork ID list" taggedAs (UnitTest, ConsensusTest) in {
    // ETC explicitly rejected the ETH DAO fork — it must NOT appear in fork ID negotiation
    etcConfig.daoForkConfig.get.includeOnForkIdList shouldBe false
  }

  it should "have no block extra data for the fork block" taggedAs (UnitTest, ConsensusTest) in {
    // ETH put 'dao-hard-fork' in extra data; ETC did not
    etcConfig.daoForkConfig.get.blockExtraData shouldBe None
  }

  "Mordor DAO config" should "be absent (Mordor has no DAO fork)" taggedAs (UnitTest, ConsensusTest) in {
    mordorConfig.daoForkConfig shouldBe None
  }
}
// scalastyle:on magic.number

// scalastyle:off magic.number
/** Validates that ETC mainnet and Mordor chain configs reference live DNS discovery domains and carry sufficient static
  * bootstrap nodes. Catches the class of regression where a stale domain is silently left in config (the domain's TXT
  * record still exists so no WARN fires, but yield drops to 0 peers).
  *
  * Reference: PR #1200 — all.classic.blockd.info went stale (0 enodes); switched to all.classic.etcdisco.net (296
  * enodes). The failure was silent: TXT record still exists, so DnsDiscovery returned empty set with no log.
  */
class EtcDiscoveryConfigSpec extends AnyFlatSpec with Matchers {

  private val fullConfig = ConfigFactory.load()
  private val etcConfig = BlockchainConfig.fromRawConfig(fullConfig.getConfig("fukuii.blockchains.etc"))
  private val mordorConfig = BlockchainConfig.fromRawConfig(fullConfig.getConfig("fukuii.blockchains.mordor"))

  // ===== ETC Mainnet DNS Discovery =====

  "ETC mainnet config" should "reference the live etcdisco.net discovery domain as primary" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    etcConfig.dnsDiscoveryDomains should contain("all.classic.etcdisco.net")
  }

  it should "include blockd.info as a fallback discovery domain for resilience" taggedAs (UnitTest, NetworkTest) in {
    etcConfig.dnsDiscoveryDomains should contain("all.classic.blockd.info")
  }

  it should "have at least 30 static bootstrap nodes" taggedAs (UnitTest, NetworkTest) in {
    etcConfig.bootstrapNodes.size should be >= 30
  }

  // ===== Mordor DNS Discovery =====

  "Mordor config" should "not reference the stale ETC mainnet blockd.info domain" taggedAs (UnitTest, NetworkTest) in {
    mordorConfig.dnsDiscoveryDomains should not contain "all.classic.blockd.info"
  }

  it should "have at least 10 static bootstrap nodes" taggedAs (UnitTest, NetworkTest) in {
    mordorConfig.bootstrapNodes.size should be >= 10
  }
}
// scalastyle:on magic.number
