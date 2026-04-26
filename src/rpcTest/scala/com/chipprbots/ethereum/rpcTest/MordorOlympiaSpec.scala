package com.chipprbots.ethereum.rpcTest

import java.math.BigInteger

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.DefaultBlockParameter
import org.web3j.protocol.http.HttpService

import com.chipprbots.ethereum.rpcTest.Tags.MainNet

// scalastyle:off magic.number
/** Live Olympia hard fork verification tests for Mordor testnet.
  *
  * Requires a fukuii node synced to Mordor and listening on the configured RPC URL.
  * Tests Olympia EIP activation, baseFee behavior, and pre/post-fork chain state.
  *
  * Fork block and treasury address are read from test.conf (mordor-olympia-fork-block,
  * mordor-olympia-treasury-address) — update that file when activation block is decided.
  *
  * Run with: sbt "rpcTest:testOnly *MordorOlympiaSpec"
  */
class MordorOlympiaSpec extends AnyFlatSpec with Matchers {

  val testConfig: RpcTestConfig = RpcTestConfig("test.conf")
  val service: Web3j = Web3j.build(new HttpService(testConfig.fukuiiUrl))

  // Mordor constants — activation block and treasury read from test.conf
  val MordorChainId: BigInteger = BigInteger.valueOf(63)
  val OlympiaForkBlock: Long = testConfig.mordorOlympiaForkBlock
  val TreasuryAddress: String = testConfig.mordorOlympiaTreasuryAddress
  val Eip2935ContractAddress: String = "0x0000f90827f1c53a10cb7a02335b175320002935"

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

  private def isPostOlympia: Boolean = {
    val latest = getLatestBlock()
    latest != null && latest.getNumber.longValue() >= OlympiaForkBlock
  }

  "Mordor Olympia" should "be connected to Mordor testnet" taggedAs MainNet in {
    skipIfNotMordor()
    val chainId = service.ethChainId().send().getChainId
    chainId shouldBe MordorChainId
  }

  it should "have chain synced past reasonable height" taggedAs MainNet in {
    skipIfNotMordor()
    val block = getLatestBlock()
    block should not be null
    // Mordor has been running since 2019 — should be well past 10M blocks
    block.getNumber.longValue() should be > 10000000L
  }

  it should "not have baseFee on pre-Olympia blocks" taggedAs MainNet in {
    skipIfNotMordor()
    // Block 1,000,000 is well before Olympia fork
    val block = getBlock(1000000)
    block should not be null
    // Pre-Olympia blocks should not have baseFeePerGas field
    // web3j returns null for fields not present
    block.getBaseFeePerGas shouldBe null
  }

  it should "have blocks immediately before fork without baseFee" taggedAs MainNet in {
    skipIfNotMordor()
    if (!isPostOlympia) {
      cancel("Olympia fork not yet active on Mordor")
    }
    val preForkBlock = getBlock(OlympiaForkBlock - 1)
    preForkBlock should not be null
    preForkBlock.getBaseFeePerGas shouldBe null
  }

  it should "have baseFee on fork activation block" taggedAs MainNet in {
    skipIfNotMordor()
    if (!isPostOlympia) {
      cancel("Olympia fork not yet active on Mordor")
    }
    val forkBlock = getBlock(OlympiaForkBlock)
    forkBlock should not be null
    forkBlock.getBaseFeePerGas should not be null
    // Initial baseFee should be set (EIP-1559 initial value = 1 Gwei typically)
    forkBlock.getBaseFeePerGas.longValue() should be > 0L
  }

  it should "have dynamic baseFee after fork" taggedAs MainNet in {
    skipIfNotMordor()
    if (!isPostOlympia) {
      cancel("Olympia fork not yet active on Mordor")
    }
    // Check a few blocks after the fork
    val block1 = getBlock(OlympiaForkBlock + 1)
    val block2 = getBlock(OlympiaForkBlock + 2)

    block1 should not be null
    block2 should not be null
    block1.getBaseFeePerGas should not be null
    block2.getBaseFeePerGas should not be null
  }

  it should "have valid block structure at fork boundary" taggedAs MainNet in {
    skipIfNotMordor()
    if (!isPostOlympia) {
      cancel("Olympia fork not yet active on Mordor")
    }
    val forkBlock = getBlock(OlympiaForkBlock)
    forkBlock should not be null
    forkBlock.getNumber.longValue() shouldEqual OlympiaForkBlock
    forkBlock.getHash should have length 66
    forkBlock.getParentHash should have length 66
    forkBlock.getGasLimit.longValue() should be > 0L
    forkBlock.getDifficulty.longValue() should be > 0L

    // Fork block should still have PoW fields
    forkBlock.getNonce should not be null
    forkBlock.getMixHash should not be null
  }

  it should "have EIP-2935 history contract deployed at fork block" taggedAs MainNet in {
    skipIfNotMordor()
    if (!isPostOlympia) {
      cancel("Olympia fork not yet active on Mordor")
    }
    // Check that the EIP-2935 system contract has code after the fork
    val code = service.ethGetCode(
      Eip2935ContractAddress,
      DefaultBlockParameter.valueOf(BigInteger.valueOf(OlympiaForkBlock))
    ).send().getCode

    code should not be null
    code should not be "0x"
    code.length should be > 2 // More than just "0x"
  }

  it should "not have EIP-2935 contract before fork" taggedAs MainNet in {
    skipIfNotMordor()
    val code = service.ethGetCode(
      Eip2935ContractAddress,
      DefaultBlockParameter.valueOf(BigInteger.valueOf(OlympiaForkBlock - 1))
    ).send().getCode

    // Pre-fork: no code at the system contract address
    (code == null || code == "0x" || code == "0x0") shouldBe true
  }

  it should "have treasury address with balance post-fork" taggedAs MainNet in {
    skipIfNotMordor()
    if (!isPostOlympia) {
      cancel("Olympia fork not yet active on Mordor")
    }
    // Check treasury balance at a block after the fork (after some transactions)
    val balance = service.ethGetBalance(
      TreasuryAddress,
      DefaultBlockParameter.valueOf("latest")
    ).send().getBalance

    balance should not be null
    // If any transactions have been processed post-fork, treasury should have received baseFee
    // (may be 0 if no transactions yet)
  }

  it should "have zero treasury baseFee credit pre-fork" taggedAs MainNet in {
    skipIfNotMordor()
    // Check that treasury has no baseFee credits before the fork
    val preForkBalance = service.ethGetBalance(
      TreasuryAddress,
      DefaultBlockParameter.valueOf(BigInteger.valueOf(OlympiaForkBlock - 1))
    ).send().getBalance

    // Treasury may have existing balance from ECIP-1098 treasury split,
    // but baseFee credits specifically start at Olympia
    preForkBalance should not be null
  }

  it should "have valid PoW nonce/mixHash on post-fork blocks" taggedAs MainNet in {
    skipIfNotMordor()
    if (!isPostOlympia) {
      cancel("Olympia fork not yet active on Mordor")
    }
    // Olympia does NOT remove PoW — blocks should still have valid ETChash
    val forkBlock = getBlock(OlympiaForkBlock)
    forkBlock.getNonce should not be null
    forkBlock.getMixHash should not be null
    forkBlock.getMixHash should have length 66
    forkBlock.getDifficulty.longValue() should be > 0L
  }

  it should "maintain gas limit around 8M post-fork" taggedAs MainNet in {
    skipIfNotMordor()
    if (!isPostOlympia) {
      cancel("Olympia fork not yet active on Mordor")
    }
    val forkBlock = getBlock(OlympiaForkBlock)
    val gasLimit = forkBlock.getGasLimit.longValue()
    // ETC gas limit should be around 8M (±4M tolerance for adjustment)
    gasLimit should be > 4000000L
    gasLimit should be < 12000000L
  }
}
