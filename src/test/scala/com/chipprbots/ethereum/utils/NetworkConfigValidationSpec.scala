package com.chipprbots.ethereum.utils

import com.typesafe.config.ConfigFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Test to validate network-specific configuration values are correct.
  *
  * This test suite ensures critical network parameters like network ID are correctly
  * configured for each blockchain network. It prevents configuration errors that could
  * cause peer connection failures and sync issues.
  */
class NetworkConfigValidationSpec extends AnyFlatSpec with Matchers {

  /** Load a chain configuration file and parse it */
  private def loadChainConfig(chainName: String): BlockchainConfig = {
    val configPath = s"conf/chains/$chainName-chain.conf"
    val config = ConfigFactory
      .parseResources(configPath)
      .resolve()

    BlockchainConfig.fromRawConfig(config)
  }

  "ETC mainnet configuration" should "use network ID 61" in {
    val config = loadChainConfig("etc")
    config.networkId shouldBe 61
  }

  it should "use chain ID 0x3d (61 decimal)" in {
    val config = loadChainConfig("etc")
    config.chainId shouldBe 0x3d
  }

  "Ethereum mainnet configuration" should "use network ID 1" in {
    val config = loadChainConfig("eth")
    config.networkId shouldBe 1
  }

  it should "use chain ID 0x01 (1 decimal)" in {
    val config = loadChainConfig("eth")
    config.chainId shouldBe 0x01
  }

  "Mordor testnet configuration" should "use network ID 7" in {
    val config = loadChainConfig("mordor")
    config.networkId shouldBe 7
  }

  it should "use chain ID 0x3f (63 decimal)" in {
    val config = loadChainConfig("mordor")
    config.chainId shouldBe 0x3f
  }

  "All network configurations" should "have matching network ID and chain ID for consistency where applicable" in {
    // Note: For ETC, network ID and chain ID should match (both 61)
    // This is a common pattern in EVM-compatible chains
    val etcConfig = loadChainConfig("etc")
    etcConfig.networkId shouldBe etcConfig.chainId

    // For ETH mainnet, they also match (both 1)
    val ethConfig = loadChainConfig("eth")
    ethConfig.networkId shouldBe ethConfig.chainId

    // For Mordor, network ID is 7 but chain ID is 63 (ETC testnet convention)
    // This is an exception to the rule
    val mordorConfig = loadChainConfig("mordor")
    mordorConfig.networkId shouldBe 7
    mordorConfig.chainId shouldBe 0x3f // 63 in decimal
  }

  "ETC configuration" should "have correct bootstrap nodes" in {
    val config = loadChainConfig("etc")
    config.bootstrapNodes should not be empty
    config.bootstrapNodes.size should be > 10
  }

  it should "have correct fork block numbers" in {
    val config = loadChainConfig("etc")

    // Verify key ETC fork blocks
    config.forkBlockNumbers.phoenixBlockNumber shouldBe BigInt(10500839)
    config.forkBlockNumbers.magnetoBlockNumber shouldBe BigInt(13189133)
    config.forkBlockNumbers.mystiqueBlockNumber shouldBe BigInt(14525000)
    config.forkBlockNumbers.spiralBlockNumber shouldBe BigInt(19250000)
  }

  it should "have bootstrap checkpoints configured" in {
    val config = loadChainConfig("etc")
    config.bootstrapCheckpoints should not be empty
    config.bootstrapCheckpoints.length should be >= 4
  }

  it should "use bootstrap checkpoints by default" in {
    val config = loadChainConfig("etc")
    config.useBootstrapCheckpoints shouldBe true
  }

  it should "report latest fork ID when unsynced" in {
    val config = loadChainConfig("etc")
    config.forkIdReportLatestWhenUnsynced shouldBe true
  }
}
