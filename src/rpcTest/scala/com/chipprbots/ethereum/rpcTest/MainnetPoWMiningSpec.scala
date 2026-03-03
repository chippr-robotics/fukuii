package com.chipprbots.ethereum.rpcTest

import java.math.BigInteger

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.DefaultBlockParameter
import org.web3j.protocol.http.HttpService

import com.chipprbots.ethereum.rpcTest.Tags.MainNet

// scalastyle:off magic.number
/** Live PoW mining verification tests for ETC mainnet.
  *
  * Requires a fukuii node synced to ETC mainnet and listening on the configured RPC URL. These tests verify
  * PoW/ETChash mining correctness on the live production chain.
  *
  * Run with: sbt "rpcTest:testOnly *MainnetPoWMiningSpec"
  */
class MainnetPoWMiningSpec extends AnyFlatSpec with Matchers {

  val testConfig: RpcTestConfig = RpcTestConfig("test.conf")
  val service: Web3j = Web3j.build(new HttpService(testConfig.fukuiiUrl))

  // ETC mainnet constants
  val ETCChainId: BigInteger = BigInteger.valueOf(61)
  val ETCEraDuration: Long = 5000000L
  val ETCEcip1099Block: Long = 11460000L
  val ETCGenesisHash: String = "0xd4e56740f876aef8c010b86a40d5f56745a118d0906a34e69aec8c0db1cb8fa3"

  // ETC mainnet gas limit target
  val ExpectedGasLimitApprox: BigInteger = BigInteger.valueOf(8000000L)
  val GasLimitTolerance: BigInteger = BigInteger.valueOf(2000000L) // ±2M tolerance

  private def skipIfNotETCMainnet(): Unit = {
    val chainId = service.ethChainId().send().getChainId
    if (chainId.compareTo(ETCChainId) != 0) {
      cancel(s"Not connected to ETC mainnet (chain ID $chainId, expected 61)")
    }
  }

  private def getBlock(number: Long, fullTxs: Boolean = false) = {
    val param = DefaultBlockParameter.valueOf(BigInteger.valueOf(number))
    service.ethGetBlockByNumber(param, fullTxs).send().getBlock
  }

  private def getLatestBlock(fullTxs: Boolean = false) = {
    val param = DefaultBlockParameter.valueOf("latest")
    service.ethGetBlockByNumber(param, fullTxs).send().getBlock
  }

  "ETC mainnet PoW" should "be connected to ETC mainnet (chain ID 61)" taggedAs MainNet in {
    skipIfNotETCMainnet()
    val chainId = service.ethChainId().send().getChainId
    chainId shouldBe ETCChainId
  }

  it should "have correct genesis block hash" taggedAs MainNet in {
    skipIfNotETCMainnet()
    val genesis = getBlock(0)
    genesis should not be null
    genesis.getHash shouldBe ETCGenesisHash
  }

  it should "have recent blocks with valid PoW structure" taggedAs MainNet in {
    skipIfNotETCMainnet()
    val block = getLatestBlock()
    block should not be null
    block.getNumber.longValue() should be > 0L
    block.getDifficulty.compareTo(BigInteger.ZERO) should be > 0
    block.getNonce should not be null
    block.getMixHash should not be null
    block.getMixHash should have length 66
  }

  it should "have difficulty in expected range for ETC mainnet" taggedAs MainNet in {
    skipIfNotETCMainnet()
    val block = getLatestBlock()
    val difficulty = block.getDifficulty

    // ETC mainnet difficulty is typically 10^12 to 10^15
    difficulty.compareTo(BigInteger.TEN.pow(9)) should be > 0
    difficulty.compareTo(BigInteger.TEN.pow(18)) should be < 0
  }

  it should "have gas limit around 8M" taggedAs MainNet in {
    skipIfNotETCMainnet()
    val block = getLatestBlock()
    val gasLimit = block.getGasLimit

    // ETC gas limit targets ~8M (vs ETH 30M)
    val lowerBound = ExpectedGasLimitApprox.subtract(GasLimitTolerance)
    val upperBound = ExpectedGasLimitApprox.add(GasLimitTolerance)

    gasLimit.compareTo(lowerBound) should be >= 0
    gasLimit.compareTo(upperBound) should be <= 0
  }

  it should "be in era 4 at current block height" taggedAs MainNet in {
    skipIfNotETCMainnet()
    val block = getLatestBlock()
    val blockNumber = block.getNumber.longValue()

    // As of 2026, ETC mainnet is in era 4 (blocks 20M-25M)
    val era = (blockNumber - 1) / ETCEraDuration
    era should be >= 4L
    // Before era 6 at block 30M
    era should be <= 6L
  }

  it should "verify a known era boundary block" taggedAs MainNet in {
    skipIfNotETCMainnet()
    val latestBlock = getLatestBlock()
    val latestNumber = latestBlock.getNumber.longValue()

    if (latestNumber < 20000001) cancel("Node not synced past era 4 boundary")

    // First block of era 4
    val era4Block = getBlock(20000001)
    era4Block should not be null
    era4Block.getNumber.longValue() shouldBe 20000001L
    era4Block.getDifficulty.compareTo(BigInteger.ZERO) should be > 0

    // Verify era calculation
    val era = (20000001L - 1) / ETCEraDuration
    era shouldBe 4L
  }

  it should "have ECIP-1099 fork block with valid structure" taggedAs MainNet in {
    skipIfNotETCMainnet()
    val latestBlock = getLatestBlock()
    val latestNumber = latestBlock.getNumber.longValue()

    if (latestNumber < ETCEcip1099Block) cancel("Node not synced past ECIP-1099 fork")

    val forkBlock = getBlock(ETCEcip1099Block)
    forkBlock should not be null
    forkBlock.getNumber.longValue() shouldBe ETCEcip1099Block
    forkBlock.getDifficulty.compareTo(BigInteger.ZERO) should be > 0
    forkBlock.getHash should have length 66
  }
}
// scalastyle:on magic.number
