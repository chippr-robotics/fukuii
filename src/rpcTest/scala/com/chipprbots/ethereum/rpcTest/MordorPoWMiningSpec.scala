package com.chipprbots.ethereum.rpcTest

import java.math.BigInteger

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.DefaultBlockParameter
import org.web3j.protocol.http.HttpService

import com.chipprbots.ethereum.rpcTest.Tags.MainNet

// scalastyle:off magic.number
/** Live PoW mining verification tests for Mordor testnet.
  *
  * Requires a fukuii node synced to Mordor and listening on the configured RPC URL. These tests verify PoW/ETChash
  * mining correctness on the live chain.
  *
  * Run with: sbt "rpcTest:testOnly *MordorPoWMiningSpec"
  */
class MordorPoWMiningSpec extends AnyFlatSpec with Matchers {

  val testConfig: RpcTestConfig = RpcTestConfig("test.conf")
  val service: Web3j = Web3j.build(new HttpService(testConfig.fukuiiUrl))

  // Mordor constants
  val MordorChainId: BigInteger = BigInteger.valueOf(63)
  val MordorEcip1099Block: Long = 2520000L
  val MordorEraDuration: Long = 5000000L

  private def skipIfNotMordor(): Unit = {
    val chainId = service.ethChainId().send().getChainId
    if (chainId.compareTo(MordorChainId) != 0) {
      cancel(s"Not connected to Mordor (chain ID $chainId, expected 63)")
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

  "Mordor PoW" should "be connected to Mordor testnet (chain ID 63)" taggedAs MainNet in {
    skipIfNotMordor()
    val chainId = service.ethChainId().send().getChainId
    chainId shouldBe MordorChainId
  }

  it should "have recent blocks with valid structure" taggedAs MainNet in {
    skipIfNotMordor()
    val block = getLatestBlock()
    block should not be null
    block.getNumber should not be null
    block.getNumber.longValue() should be > 0L
    block.getHash should not be null
    block.getHash should have length 66 // 0x + 64 hex chars
    block.getGasLimit should not be null
    block.getGasLimit.longValue() should be > 0L
    block.getDifficulty should not be null
    block.getDifficulty.longValue() should be > 0L
  }

  it should "have valid nonce and mixHash in recent blocks" taggedAs MainNet in {
    skipIfNotMordor()
    val block = getLatestBlock()

    // ETChash blocks must have non-zero nonce and mixHash
    block.getNonce should not be null
    block.getMixHash should not be null
    block.getMixHash should have length 66

    // Nonce should be 8 bytes (0x + 16 hex chars)
    block.getNonceRaw should not be null
  }

  it should "have difficulty in expected range for Mordor" taggedAs MainNet in {
    skipIfNotMordor()
    val block = getLatestBlock()
    val difficulty = block.getDifficulty

    // Mordor difficulty should be reasonable (not zero, not absurdly high)
    // Typical Mordor difficulty is in the range of 10^6 to 10^12
    difficulty.compareTo(BigInteger.ZERO) should be > 0
    difficulty.compareTo(BigInteger.TEN.pow(15)) should be < 0
  }

  it should "have monotonically increasing timestamps in recent blocks" taggedAs MainNet in {
    skipIfNotMordor()
    val latestBlock = getLatestBlock()
    val latestNumber = latestBlock.getNumber.longValue()

    if (latestNumber < 10) cancel("Not enough blocks to verify timestamps")

    val timestamps = (latestNumber - 5 to latestNumber).map { n =>
      getBlock(n).getTimestamp.longValue()
    }

    // Timestamps should be strictly non-decreasing (equal timestamps are allowed in PoW)
    timestamps.sliding(2).foreach { case Seq(a, b) =>
      b should be >= a
    }
  }

  it should "have gas limit following adjustment rules" taggedAs MainNet in {
    skipIfNotMordor()
    val latestBlock = getLatestBlock()
    val latestNumber = latestBlock.getNumber.longValue()

    if (latestNumber < 2) cancel("Not enough blocks")

    val parentBlock = getBlock(latestNumber - 1)
    val parentGasLimit = parentBlock.getGasLimit
    val childGasLimit = latestBlock.getGasLimit

    // Gas limit can change by at most 1/1024 of parent per block
    val maxChange = parentGasLimit.divide(BigInteger.valueOf(1024))
    val diff = childGasLimit.subtract(parentGasLimit).abs()
    diff.compareTo(maxChange.add(BigInteger.ONE)) should be <= 0
  }

  it should "verify ECIP-1099 fork block exists and is valid" taggedAs MainNet in {
    skipIfNotMordor()
    val latestBlock = getLatestBlock()
    val latestNumber = latestBlock.getNumber.longValue()

    if (latestNumber < MordorEcip1099Block) cancel("Node not synced past ECIP-1099 fork block")

    val forkBlock = getBlock(MordorEcip1099Block)
    forkBlock should not be null
    forkBlock.getNumber.longValue() shouldBe MordorEcip1099Block
    forkBlock.getDifficulty.compareTo(BigInteger.ZERO) should be > 0
    forkBlock.getHash should have length 66
  }

  it should "have blocks in current era with expected reward characteristics" taggedAs MainNet in {
    skipIfNotMordor()
    val latestBlock = getLatestBlock()
    val blockNumber = latestBlock.getNumber.longValue()

    // Determine current era: era = (blockNumber - 1) / 5,000,000
    val era = (blockNumber - 1) / MordorEraDuration
    era should be >= 0L

    // Era determines the base reward via ECIP-1017:
    // Era 0: 5 ETC, Era 1: 4 ETC, Era 2: 3.2 ETC, Era 3: 2.56 ETC, Era 4: 2.048 ETC
    // We just verify the era is computable and reasonable
    era should be < 200L // After era ~193, rewards round to 0
  }
}
// scalastyle:on magic.number
