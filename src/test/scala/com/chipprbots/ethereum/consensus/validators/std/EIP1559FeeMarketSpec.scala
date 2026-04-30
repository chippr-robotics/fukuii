package com.chipprbots.ethereum.consensus.validators.std

import java.security.SecureRandom

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.consensus.validators.SignedTransactionError.TransactionSyntaxError
import com.chipprbots.ethereum.crypto
import com.chipprbots.ethereum.domain._
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefPostOlympia
import com.chipprbots.ethereum.nodebuilder.BlockchainConfigBuilder
import com.chipprbots.ethereum.testing.Tags._
import com.chipprbots.ethereum.utils.BlockchainConfig

/** L9 — EIP-1559 fee market validation at the transaction validator layer.
  *
  * Covers validateMaxFeeAgainstBaseFee for Type-2 (dynamic fee), Type-3 (blob), and legacy transactions on ETC
  * post-Mystique (EIP-1559 active). All fee-market validations happen before state access.
  */
class EIP1559FeeMarketSpec
    extends AnyFlatSpec
    with Matchers
    with BlockchainConfigBuilder
    with com.chipprbots.ethereum.TestInstanceConfigProvider {

  implicit val config: BlockchainConfig = blockchainConfig.withUpdatedForkBlocks(
    _.copy(
      homesteadBlockNumber = 0,
      eip155BlockNumber = 0,
      olympiaBlockNumber = 0
    )
  )

  private val secureRandom = new SecureRandom()
  private val senderKeys   = crypto.generateKeyPair(secureRandom)
  private val senderAccount: Account = Account(nonce = 0, balance = UInt256(BigInt("1000000000000000000000")))

  private val baseFee    = BigInt("10000000000") // 10 gwei
  private val maxFee     = BigInt("20000000000") // 20 gwei
  private val priorityFee = BigInt("2000000000") // 2 gwei

  private val postMystiqueHeader = Fixtures.Blocks.ValidBlock.header.copy(
    number = BigInt(20_000_000),
    gasLimit = BigInt(8_000_000),
    gasUsed = 0,
    extraFields = HefPostOlympia(baseFee)
  )

  private def validate(stx: SignedTransaction, header: BlockHeader = postMystiqueHeader) =
    StdSignedTransactionValidator.validate(
      stx = stx,
      senderAccount = senderAccount,
      blockHeader = header,
      upfrontGasCost = UInt256(0),
      accumGasUsed = BigInt(0)
    )

  private def signType2(
      maxPriority: BigInt = priorityFee,
      maxFeePerGas: BigInt = maxFee
  ): SignedTransaction = {
    val tx = TransactionWithDynamicFee(
      chainId = config.chainId,
      nonce = 0,
      maxPriorityFeePerGas = maxPriority,
      maxFeePerGas = maxFeePerGas,
      gasLimit = 21000,
      receivingAddress = Some(Address(1)),
      value = BigInt(0),
      payload = ByteString.empty,
      accessList = Nil
    )
    SignedTransaction.sign(tx, senderKeys, Some(config.chainId))
  }

  private def signLegacy(gasPrice: BigInt): SignedTransaction = {
    val tx = LegacyTransaction(
      nonce = 0,
      gasPrice = gasPrice,
      gasLimit = 21000,
      receivingAddress = Address(1),
      value = BigInt(0),
      payload = ByteString.empty
    )
    SignedTransaction.sign(tx, senderKeys, Some(config.chainId))
  }

  // ── Type-2 happy path ──────────────────────────────────────────────────────

  "EIP-1559 fee market" should "accept Type-2 tx when maxFee >= baseFee and priority <= maxFee" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    validate(signType2()) shouldBe a[Right[?, ?]]
  }

  it should "accept Type-2 tx when maxFee exactly equals baseFee" taggedAs (UnitTest, ConsensusTest) in {
    validate(signType2(maxPriority = BigInt(0), maxFeePerGas = baseFee)) shouldBe a[Right[?, ?]]
  }

  // ── Fee inversion: priority > maxFee ──────────────────────────────────────

  it should "reject Type-2 tx when maxPriorityFeePerGas > maxFeePerGas" taggedAs (UnitTest, ConsensusTest) in {
    val result = validate(signType2(maxPriority = maxFee + 1, maxFeePerGas = maxFee))
    result shouldBe a[Left[?, ?]]
    result.left.toOption.get shouldBe a[TransactionSyntaxError]
    result.left.toOption.get.asInstanceOf[TransactionSyntaxError].reason should include("maxPriorityFeePerGas")
  }

  it should "reject Type-2 tx when maxPriorityFeePerGas equals maxFeePerGas plus one" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    val stx = signType2(maxPriority = maxFee + 1, maxFeePerGas = maxFee)
    validate(stx) shouldBe a[Left[?, ?]]
  }

  it should "accept Type-2 tx when maxPriorityFeePerGas equals maxFeePerGas (no tip)" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    validate(signType2(maxPriority = maxFee, maxFeePerGas = maxFee)) shouldBe a[Right[?, ?]]
  }

  // ── maxFee below baseFee ───────────────────────────────────────────────────

  it should "reject Type-2 tx when maxFeePerGas < baseFee" taggedAs (UnitTest, ConsensusTest) in {
    val result = validate(signType2(maxFeePerGas = baseFee - 1))
    result shouldBe a[Left[?, ?]]
    result.left.toOption.get shouldBe a[TransactionSyntaxError]
    result.left.toOption.get.asInstanceOf[TransactionSyntaxError].reason should include("INSUFFICIENT_MAX_FEE_PER_GAS")
  }

  it should "reject Type-2 tx when maxFeePerGas is zero and baseFee is non-zero" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    val result = validate(signType2(maxPriority = BigInt(0), maxFeePerGas = BigInt(0)))
    result shouldBe a[Left[?, ?]]
    result.left.toOption.get.asInstanceOf[TransactionSyntaxError].reason should include("INSUFFICIENT_MAX_FEE_PER_GAS")
  }

  // ── Legacy tx in EIP-1559 blocks ──────────────────────────────────────────

  it should "not apply baseFee check to legacy transactions (they pay gasPrice post-execution)" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    // Legacy tx with gasPrice below baseFee — fee market check does NOT apply to legacy
    val lowGasPrice = baseFee / 2
    validate(signLegacy(gasPrice = lowGasPrice)) match {
      case Left(TransactionSyntaxError(reason)) if reason.contains("INSUFFICIENT_MAX_FEE_PER_GAS") =>
        fail("Legacy tx should not be checked against baseFee")
      case _ => succeed
    }
  }

  it should "not apply priority fee inversion check to legacy transactions" taggedAs (UnitTest, ConsensusTest) in {
    validate(signLegacy(gasPrice = maxFee)) match {
      case Left(TransactionSyntaxError(reason)) if reason.contains("maxPriorityFeePerGas") =>
        fail("Legacy tx should not be checked for priority fee inversion")
      case _ => succeed
    }
  }

  // ── Zero baseFee block (pre-Mystique / no extraFields) ────────────────────

  it should "accept Type-2 tx in a pre-Mystique block with no baseFee (defaults to 0)" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    val preMystiqueHeader = postMystiqueHeader.copy(extraFields = BlockHeader.HeaderExtraFields.HefEmpty)
    // baseFee defaults to 0 when not in extraFields — any maxFee >= 0 passes
    validate(signType2(), preMystiqueHeader) match {
      case Left(TransactionSyntaxError(reason)) if reason.contains("INSUFFICIENT_MAX_FEE_PER_GAS") =>
        fail("Type-2 tx should not be rejected for baseFee when block has no baseFee")
      case _ => succeed
    }
  }
}
