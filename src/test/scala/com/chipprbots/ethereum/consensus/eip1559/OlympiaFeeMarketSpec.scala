package com.chipprbots.ethereum.consensus.eip1559

import org.apache.pekko.util.ByteString

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.domain._
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefEmpty
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefPostOlympia
import com.chipprbots.ethereum.nodebuilder.BlockchainConfigBuilder
import com.chipprbots.ethereum.testing.Tags._
import com.chipprbots.ethereum.utils.BlockchainConfig

/** Tests for EIP-1559 fee market semantics at the Olympia fork boundary.
  *
  * Covers:
  *   - effectiveGasPrice for Type 0, Type 2, and Type 4 transactions
  *   - Miner priority fee (tip) math: effectiveGasPrice - baseFee
  *   - baseFee floor alignment with ETH go-ethereum (.max(0), not .max(1))
  *
  * ETC ECIP-1111 note: baseFee is NOT burned — it is credited to the treasury contract. Miner receives only the tip
  * (effectiveGasPrice - baseFee). These tests verify the validator-layer fee math, not the BlockPreparator treasury
  * credit (see TreasuryBaseFeeSpec).
  */
// scalastyle:off magic.number
class OlympiaFeeMarketSpec
    extends AnyWordSpec
    with Matchers
    with BlockchainConfigBuilder
    with com.chipprbots.ethereum.TestInstanceConfigProvider {

  private val olympiaBlock: BigInt = BigInt(100)

  implicit val config: BlockchainConfig = blockchainConfig
    .withUpdatedForkBlocks(_.copy(olympiaBlockNumber = olympiaBlock, olympiaGasTarget = Some(BigInt(60_000_000))))
    .copy(baseFeeFloor = BaseFeeCalculator.InitialBaseFee)

  private val InitialBaseFee: BigInt = BaseFeeCalculator.InitialBaseFee

  private def olympiaParent(gasLimit: BigInt, gasUsed: BigInt, baseFee: BigInt): BlockHeader =
    Fixtures.Blocks.ValidBlock.header.copy(
      number = olympiaBlock,
      gasLimit = gasLimit,
      gasUsed = gasUsed,
      extraFields = HefPostOlympia(baseFee)
    )

  "Olympia EIP-1559 fee market" when {

    "effectiveGasPrice" should {

      "Type 0 (legacy): return gasPrice regardless of baseFee" taggedAs (UnitTest, OlympiaTest) in {
        val legacyTx = LegacyTransaction(
          nonce = 0,
          gasPrice = BigInt(5_000_000_000L),
          gasLimit = 21000,
          receivingAddress = Address(1),
          value = BigInt(0),
          payload = ByteString.empty
        )
        Transaction.effectiveGasPrice(legacyTx, Some(BigInt(2_000_000_000L))) shouldBe legacyTx.gasPrice
      }

      "Type 2 (dynamic fee): return min(maxFee, baseFee + maxPriority) — uncapped case" taggedAs (
        UnitTest,
        OlympiaTest
      ) in {
        val tx = TransactionWithDynamicFee(
          chainId = config.chainId,
          nonce = BigInt(0),
          maxPriorityFeePerGas = BigInt(1_000_000_000L),
          maxFeePerGas = BigInt(10_000_000_000L),
          gasLimit = BigInt(21000),
          receivingAddress = Some(Address(1)),
          value = BigInt(0),
          payload = ByteString.empty,
          accessList = Nil
        )
        val baseFee = BigInt(2_000_000_000L)
        Transaction.effectiveGasPrice(tx, Some(baseFee)) shouldBe BigInt(3_000_000_000L)
      }

      "Type 2 (dynamic fee): cap at maxFeePerGas when baseFee + maxPriority > maxFee" taggedAs (
        UnitTest,
        OlympiaTest
      ) in {
        val tx = TransactionWithDynamicFee(
          chainId = config.chainId,
          nonce = BigInt(0),
          maxPriorityFeePerGas = BigInt(3_000_000_000L),
          maxFeePerGas = BigInt(10_000_000_000L),
          gasLimit = BigInt(21000),
          receivingAddress = Some(Address(1)),
          value = BigInt(0),
          payload = ByteString.empty,
          accessList = Nil
        )
        val baseFee = BigInt(8_000_000_000L)
        Transaction.effectiveGasPrice(tx, Some(baseFee)) shouldBe BigInt(10_000_000_000L)
      }

      "Type 4 (SetCode): follow same min(maxFee, baseFee + maxPriority) formula as Type 2" taggedAs (
        UnitTest,
        OlympiaTest
      ) in {
        val auth = SetCodeAuthorization(
          chainId = config.chainId,
          address = Address(1),
          nonce = BigInt(0),
          v = BigInt(0),
          r = BigInt(1),
          s = BigInt(2)
        )
        val tx = SetCodeTransaction(
          chainId = config.chainId,
          nonce = BigInt(0),
          maxPriorityFeePerGas = BigInt(1_000_000_000L),
          maxFeePerGas = BigInt(5_000_000_000L),
          gasLimit = BigInt(50000),
          receivingAddress = Some(Address(1)),
          value = BigInt(0),
          payload = ByteString.empty,
          accessList = Nil,
          authorizationList = List(auth)
        )
        val baseFee = BigInt(2_000_000_000L)
        Transaction.effectiveGasPrice(tx, Some(baseFee)) shouldBe BigInt(3_000_000_000L)
      }
    }

    "miner priority fee (tip)" should {

      "Type 0 post-Olympia: miner tip = gasPrice - baseFee (positive when gasPrice > baseFee)" taggedAs (
        UnitTest,
        OlympiaTest
      ) in {
        val gasPrice = BigInt(5_000_000_000L)
        val baseFee = BigInt(2_000_000_000L)
        val legacyTx = LegacyTransaction(
          nonce = 0,
          gasPrice = gasPrice,
          gasLimit = 21000,
          receivingAddress = Address(1),
          value = BigInt(0),
          payload = ByteString.empty
        )
        val effective = Transaction.effectiveGasPrice(legacyTx, Some(baseFee))
        val minerTip = effective - baseFee
        minerTip shouldBe BigInt(3_000_000_000L)
      }

      "Type 2: miner tip = min(maxPriority, maxFee - baseFee)" taggedAs (UnitTest, OlympiaTest) in {
        val baseFee = BigInt(2_000_000_000L)
        val maxPriority = BigInt(1_000_000_000L)
        val maxFee = BigInt(10_000_000_000L)
        val tx = TransactionWithDynamicFee(
          chainId = config.chainId,
          nonce = BigInt(0),
          maxPriorityFeePerGas = maxPriority,
          maxFeePerGas = maxFee,
          gasLimit = BigInt(21000),
          receivingAddress = Some(Address(1)),
          value = BigInt(0),
          payload = ByteString.empty,
          accessList = Nil
        )
        val effective = Transaction.effectiveGasPrice(tx, Some(baseFee))
        val minerTip = effective - baseFee
        minerTip shouldBe maxPriority
      }

      "Type 2: miner tip is 0 when maxFeePerGas == baseFee (entire effective price goes to treasury)" taggedAs (
        UnitTest,
        OlympiaTest
      ) in {
        val baseFee = BigInt(5_000_000_000L)
        val tx = TransactionWithDynamicFee(
          chainId = config.chainId,
          nonce = BigInt(0),
          maxPriorityFeePerGas = BigInt(0),
          maxFeePerGas = baseFee,
          gasLimit = BigInt(21000),
          receivingAddress = Some(Address(1)),
          value = BigInt(0),
          payload = ByteString.empty,
          accessList = Nil
        )
        val effective = Transaction.effectiveGasPrice(tx, Some(baseFee))
        val minerTip = effective - baseFee
        effective shouldBe baseFee
        minerTip shouldBe BigInt(0)
      }
    }

    "baseFee floor per ECIP-1111 (1 gwei minimum)" should {

      "baseFee floors at InitialBaseFee (1 gwei) for ETC/Mordor chains" taggedAs (
        UnitTest,
        OlympiaTest
      ) in {
        val tinyBaseFee = BigInt(1)
        val emptyParent = olympiaParent(gasLimit = BigInt(30_000_000), gasUsed = 0, baseFee = tinyBaseFee)
        val next = BaseFeeCalculator.calcBaseFee(emptyParent, config)
        next should be >= InitialBaseFee
        next shouldBe InitialBaseFee
      }

      "baseFee never falls below InitialBaseFee over sustained empty blocks" taggedAs (
        UnitTest,
        OlympiaTest
      ) in {
        var fee = InitialBaseFee
        for (_ <- 1 to 100) {
          val emptyParent = olympiaParent(gasLimit = BigInt(30_000_000), gasUsed = 0, baseFee = fee)
          fee = BaseFeeCalculator.calcBaseFee(emptyParent, config)
          fee should be >= InitialBaseFee
        }
      }
    }
  }
}
// scalastyle:on magic.number
