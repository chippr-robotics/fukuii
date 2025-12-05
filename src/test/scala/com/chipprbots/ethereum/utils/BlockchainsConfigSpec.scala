package com.chipprbots.ethereum.utils

import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.typesafe.config.ConfigFactory

import com.chipprbots.ethereum.testing.Tags._

class BlockchainsConfigSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {

  var tempDir: File = _

  override def beforeEach(): Unit = {
    tempDir = Files.createTempDirectory("fukuii-test-chains").toFile
    tempDir.deleteOnExit()
  }

  override def afterEach(): Unit = {
    if (tempDir != null && tempDir.exists()) {
      deleteDirectory(tempDir)
    }
  }

  private def deleteDirectory(dir: File): Unit = {
    if (dir.isDirectory) {
      dir.listFiles().foreach(deleteDirectory)
    }
    dir.delete()
  }

  "BlockchainsConfig" should "load built-in blockchain configurations" taggedAs (UnitTest) in {
    val config = ConfigFactory.parseString("""
      network = "etc"
      etc {
        network-id = 1
        chain-id = "0x3d"
        frontier-block-number = "0"
        homestead-block-number = "1150000"
        eip106-block-number = "1000000000000000000"
        eip150-block-number = "2500000"
        eip155-block-number = "3000000"
        eip160-block-number = "3000000"
        eip161-block-number = "1000000000000000000"
        byzantium-block-number = "1000000000000000000"
        constantinople-block-number = "1000000000000000000"
        petersburg-block-number = "1000000000000000000"
        istanbul-block-number = "1000000000000000000"
        atlantis-block-number = "8772000"
        agharta-block-number = "9573000"
        phoenix-block-number = "10500839"
        ecip1098-block-number = "1000000000000000000"
        ecip1097-block-number = "1000000000000000000"
        ecip1099-block-number = "11700000"
        muir-glacier-block-number = "1000000000000000000"
        magneto-block-number = "13189133"
        berlin-block-number = "1000000000000000000"
        mystique-block-number = "14525000"
        spiral-block-number = "19250000"
        difficulty-bomb-pause-block-number = "3000000"
        difficulty-bomb-continue-block-number = "5000000"
        difficulty-bomb-removal-block-number = "5900000"
        max-code-size = "24576"
        account-start-nonce = "0"
        treasury-address = "0011223344556677889900112233445566778899"
        gas-tie-breaker = false
        eth-compatible-storage = true
        bootstrap-nodes = []
        monetary-policy {
          first-era-block-reward = "5000000000000000000"
          first-era-reduced-block-reward = "3000000000000000000"
          first-era-constantinople-reduced-block-reward = "2000000000000000000"
          era-duration = 5000000
          reward-reduction-rate = 0.2
        }
      }
    """)

    val blockchainsConfig = BlockchainsConfig(config)

    blockchainsConfig.network shouldBe "etc"
    blockchainsConfig.blockchains should contain key "etc"
    blockchainsConfig.blockchainConfig.networkId shouldBe 1
    // Chain ID 0x3d (61 in decimal) fits within byte range and is the ETC mainnet chain ID
    blockchainsConfig.blockchainConfig.chainId shouldBe 0x3d.toByte
  }

  it should "load custom blockchain configurations from external directory" taggedAs (UnitTest) in {
    // Create a custom chain config file
    val customChainConfig = """
      {
        network-id = 9999
        chain-id = "0x270f"
        frontier-block-number = "0"
        homestead-block-number = "0"
        eip106-block-number = "1000000000000000000"
        eip150-block-number = "0"
        eip155-block-number = "0"
        eip160-block-number = "0"
        eip161-block-number = "0"
        byzantium-block-number = "0"
        constantinople-block-number = "0"
        petersburg-block-number = "0"
        istanbul-block-number = "0"
        atlantis-block-number = "1000000000000000000"
        agharta-block-number = "1000000000000000000"
        phoenix-block-number = "1000000000000000000"
        ecip1098-block-number = "1000000000000000000"
        ecip1097-block-number = "1000000000000000000"
        ecip1099-block-number = "1000000000000000000"
        muir-glacier-block-number = "1000000000000000000"
        magneto-block-number = "1000000000000000000"
        berlin-block-number = "1000000000000000000"
        mystique-block-number = "1000000000000000000"
        spiral-block-number = "1000000000000000000"
        difficulty-bomb-pause-block-number = "0"
        difficulty-bomb-continue-block-number = "0"
        difficulty-bomb-removal-block-number = "0"
        max-code-size = "24576"
        account-start-nonce = "0"
        treasury-address = "0011223344556677889900112233445566778899"
        gas-tie-breaker = false
        eth-compatible-storage = true
        bootstrap-nodes = []
        monetary-policy {
          first-era-block-reward = "5000000000000000000"
          first-era-reduced-block-reward = "5000000000000000000"
          first-era-constantinople-reduced-block-reward = "5000000000000000000"
          era-duration = 500000000
          reward-reduction-rate = 0
        }
      }
    """

    val chainFile = new File(tempDir, "customnet-chain.conf")
    Files.write(chainFile.toPath, customChainConfig.getBytes)
    
    // Verify file was created successfully
    chainFile.exists() shouldBe true
    chainFile.length() should be > 0L

    val config = ConfigFactory.parseString(s"""
      network = "customnet"
      custom-chains-dir = "${tempDir.getAbsolutePath}"
    """)

    val blockchainsConfig = BlockchainsConfig(config)

    blockchainsConfig.network shouldBe "customnet"
    blockchainsConfig.blockchains should contain key "customnet"
    blockchainsConfig.blockchainConfig.networkId shouldBe 9999
    // Chain ID 0x270f (9999) will overflow when converted to byte, resulting in 15
    // This is expected behavior as chain IDs are stored as signed bytes
    blockchainsConfig.blockchainConfig.chainId shouldBe 15.toByte
  }

  it should "give priority to custom configurations over built-in ones" taggedAs (UnitTest) in {
    // Create a custom etc-chain.conf that overrides the built-in one
    val customEtcConfig = """
      {
        network-id = 99999
        chain-id = "0x3d"
        frontier-block-number = "0"
        homestead-block-number = "1150000"
        eip106-block-number = "1000000000000000000"
        eip150-block-number = "2500000"
        eip155-block-number = "3000000"
        eip160-block-number = "3000000"
        eip161-block-number = "1000000000000000000"
        byzantium-block-number = "1000000000000000000"
        constantinople-block-number = "1000000000000000000"
        petersburg-block-number = "1000000000000000000"
        istanbul-block-number = "1000000000000000000"
        atlantis-block-number = "8772000"
        agharta-block-number = "9573000"
        phoenix-block-number = "10500839"
        ecip1098-block-number = "1000000000000000000"
        ecip1097-block-number = "1000000000000000000"
        ecip1099-block-number = "11700000"
        muir-glacier-block-number = "1000000000000000000"
        magneto-block-number = "13189133"
        berlin-block-number = "1000000000000000000"
        mystique-block-number = "14525000"
        spiral-block-number = "19250000"
        difficulty-bomb-pause-block-number = "3000000"
        difficulty-bomb-continue-block-number = "5000000"
        difficulty-bomb-removal-block-number = "5900000"
        max-code-size = "24576"
        account-start-nonce = "0"
        treasury-address = "0011223344556677889900112233445566778899"
        gas-tie-breaker = false
        eth-compatible-storage = true
        bootstrap-nodes = []
        monetary-policy {
          first-era-block-reward = "5000000000000000000"
          first-era-reduced-block-reward = "3000000000000000000"
          first-era-constantinople-reduced-block-reward = "2000000000000000000"
          era-duration = 5000000
          reward-reduction-rate = 0.2
        }
      }
    """

    val chainFile = new File(tempDir, "etc-chain.conf")
    Files.write(chainFile.toPath, customEtcConfig.getBytes)

    val config = ConfigFactory.parseString(s"""
      network = "etc"
      custom-chains-dir = "${tempDir.getAbsolutePath}"
      etc {
        network-id = 1
        chain-id = "0x3d"
        frontier-block-number = "0"
        homestead-block-number = "1150000"
        eip106-block-number = "1000000000000000000"
        eip150-block-number = "2500000"
        eip155-block-number = "3000000"
        eip160-block-number = "3000000"
        eip161-block-number = "1000000000000000000"
        byzantium-block-number = "1000000000000000000"
        constantinople-block-number = "1000000000000000000"
        petersburg-block-number = "1000000000000000000"
        istanbul-block-number = "1000000000000000000"
        atlantis-block-number = "8772000"
        agharta-block-number = "9573000"
        phoenix-block-number = "10500839"
        ecip1098-block-number = "1000000000000000000"
        ecip1097-block-number = "1000000000000000000"
        ecip1099-block-number = "11700000"
        muir-glacier-block-number = "1000000000000000000"
        magneto-block-number = "13189133"
        berlin-block-number = "1000000000000000000"
        mystique-block-number = "14525000"
        spiral-block-number = "19250000"
        difficulty-bomb-pause-block-number = "3000000"
        difficulty-bomb-continue-block-number = "5000000"
        difficulty-bomb-removal-block-number = "5900000"
        max-code-size = "24576"
        account-start-nonce = "0"
        treasury-address = "0011223344556677889900112233445566778899"
        gas-tie-breaker = false
        eth-compatible-storage = true
        bootstrap-nodes = []
        monetary-policy {
          first-era-block-reward = "5000000000000000000"
          first-era-reduced-block-reward = "3000000000000000000"
          first-era-constantinople-reduced-block-reward = "2000000000000000000"
          era-duration = 5000000
          reward-reduction-rate = 0.2
        }
      }
    """)

    val blockchainsConfig = BlockchainsConfig(config)

    // Custom config should override the built-in one
    blockchainsConfig.blockchainConfig.networkId shouldBe 99999
  }

  it should "handle missing custom-chains-dir gracefully" taggedAs (UnitTest) in {
    val config = ConfigFactory.parseString("""
      network = "etc"
      etc {
        network-id = 1
        chain-id = "0x3d"
        frontier-block-number = "0"
        homestead-block-number = "1150000"
        eip106-block-number = "1000000000000000000"
        eip150-block-number = "2500000"
        eip155-block-number = "3000000"
        eip160-block-number = "3000000"
        eip161-block-number = "1000000000000000000"
        byzantium-block-number = "1000000000000000000"
        constantinople-block-number = "1000000000000000000"
        petersburg-block-number = "1000000000000000000"
        istanbul-block-number = "1000000000000000000"
        atlantis-block-number = "8772000"
        agharta-block-number = "9573000"
        phoenix-block-number = "10500839"
        ecip1098-block-number = "1000000000000000000"
        ecip1097-block-number = "1000000000000000000"
        ecip1099-block-number = "11700000"
        muir-glacier-block-number = "1000000000000000000"
        magneto-block-number = "13189133"
        berlin-block-number = "1000000000000000000"
        mystique-block-number = "14525000"
        spiral-block-number = "19250000"
        difficulty-bomb-pause-block-number = "3000000"
        difficulty-bomb-continue-block-number = "5000000"
        difficulty-bomb-removal-block-number = "5900000"
        max-code-size = "24576"
        account-start-nonce = "0"
        treasury-address = "0011223344556677889900112233445566778899"
        gas-tie-breaker = false
        eth-compatible-storage = true
        bootstrap-nodes = []
        monetary-policy {
          first-era-block-reward = "5000000000000000000"
          first-era-reduced-block-reward = "3000000000000000000"
          first-era-constantinople-reduced-block-reward = "2000000000000000000"
          era-duration = 5000000
          reward-reduction-rate = 0.2
        }
      }
    """)

    // Should not throw exception when custom-chains-dir is not set
    noException should be thrownBy BlockchainsConfig(config)
  }

  it should "handle non-existent custom-chains-dir gracefully" taggedAs (UnitTest) in {
    val config = ConfigFactory.parseString("""
      network = "etc"
      custom-chains-dir = "/nonexistent/directory"
      etc {
        network-id = 1
        chain-id = "0x3d"
        frontier-block-number = "0"
        homestead-block-number = "1150000"
        eip106-block-number = "1000000000000000000"
        eip150-block-number = "2500000"
        eip155-block-number = "3000000"
        eip160-block-number = "3000000"
        eip161-block-number = "1000000000000000000"
        byzantium-block-number = "1000000000000000000"
        constantinople-block-number = "1000000000000000000"
        petersburg-block-number = "1000000000000000000"
        istanbul-block-number = "1000000000000000000"
        atlantis-block-number = "8772000"
        agharta-block-number = "9573000"
        phoenix-block-number = "10500839"
        ecip1098-block-number = "1000000000000000000"
        ecip1097-block-number = "1000000000000000000"
        ecip1099-block-number = "11700000"
        muir-glacier-block-number = "1000000000000000000"
        magneto-block-number = "13189133"
        berlin-block-number = "1000000000000000000"
        mystique-block-number = "14525000"
        spiral-block-number = "19250000"
        difficulty-bomb-pause-block-number = "3000000"
        difficulty-bomb-continue-block-number = "5000000"
        difficulty-bomb-removal-block-number = "5900000"
        max-code-size = "24576"
        account-start-nonce = "0"
        treasury-address = "0011223344556677889900112233445566778899"
        gas-tie-breaker = false
        eth-compatible-storage = true
        bootstrap-nodes = []
        monetary-policy {
          first-era-block-reward = "5000000000000000000"
          first-era-reduced-block-reward = "3000000000000000000"
          first-era-constantinople-reduced-block-reward = "2000000000000000000"
          era-duration = 5000000
          reward-reduction-rate = 0.2
        }
      }
    """)

    // Should not throw exception when directory doesn't exist
    noException should be thrownBy BlockchainsConfig(config)
  }
}
