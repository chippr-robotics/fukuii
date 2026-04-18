package com.chipprbots.ethereum.vm

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.nodebuilder.BlockchainConfigBuilder
import com.chipprbots.ethereum.testing.Tags._
import com.chipprbots.ethereum.utils.BlockchainConfig

/** EIP-7702: intrinsic gas is `PER_EMPTY_ACCOUNT_COST` (25,000) per authorization
  * tuple. A `REFUND_PER_EXISTING_ACCOUNT` (12,500) is returned during execution
  * for each auth whose target account already exists, capped at gasUsed/5. This
  * test covers the intrinsic side only; refund accounting is covered elsewhere.
  *
  * See EELS prague/transactions.py `calculate_intrinsic_cost` and
  * eips.ethereum.org/EIPS/eip-7702 (final).
  */
class EIP7702AuthGasSpec
    extends AnyFlatSpec
    with Matchers
    with BlockchainConfigBuilder
    with com.chipprbots.ethereum.TestInstanceConfigProvider {

  val olympiaBlock: BigInt = 10

  val config: BlockchainConfig = blockchainConfig.withUpdatedForkBlocks(
    _.copy(olympiaBlockNumber = olympiaBlock)
  )

  val evmConfig: EvmConfig = EvmConfig.forBlock(olympiaBlock, config)

  val emptyPayload: ByteString = ByteString.empty

  "EIP-7702 auth tuple gas" should "be 25000 per authorization" taggedAs (OlympiaTest, VMTest) in {
    val gasWithAuth = evmConfig.calcTransactionIntrinsicGas(emptyPayload, isContractCreation = false, Nil, 1)
    val gasWithoutAuth = evmConfig.calcTransactionIntrinsicGas(emptyPayload, isContractCreation = false, Nil, 0)
    (gasWithAuth - gasWithoutAuth) shouldBe BigInt(25000)
  }

  it should "scale linearly with authorization list size" taggedAs (OlympiaTest, VMTest) in {
    val gas0 = evmConfig.calcTransactionIntrinsicGas(emptyPayload, isContractCreation = false, Nil, 0)
    val gas1 = evmConfig.calcTransactionIntrinsicGas(emptyPayload, isContractCreation = false, Nil, 1)
    val gas3 = evmConfig.calcTransactionIntrinsicGas(emptyPayload, isContractCreation = false, Nil, 3)

    (gas1 - gas0) shouldBe BigInt(25000)
    (gas3 - gas0) shouldBe BigInt(75000)
  }

  it should "combine with access list and calldata costs" taggedAs (OlympiaTest, VMTest) in {
    import com.chipprbots.ethereum.domain.{AccessListItem, Address}

    val payload = ByteString(Array.fill(100)(0x01.toByte)) // 100 nonzero bytes
    val accessList = List(AccessListItem(Address(1), List(BigInt(0), BigInt(1))))

    val gasBase = evmConfig.calcTransactionIntrinsicGas(emptyPayload, isContractCreation = false, Nil, 0)
    val gasFull = evmConfig.calcTransactionIntrinsicGas(payload, isContractCreation = false, accessList, 2)

    // Full = base + calldata(100 * 16) + accessList(1 addr * 2400 + 2 keys * 1900) + auth(2 * 25000)
    val calldataCost = BigInt(100) * 16 // G_txdatanonzero = 16
    val accessListCost = BigInt(2400) + BigInt(2) * 1900 // 1 addr + 2 keys
    val authCost = BigInt(2) * 25000

    gasFull shouldBe (gasBase + calldataCost + accessListCost + authCost)
  }
}
