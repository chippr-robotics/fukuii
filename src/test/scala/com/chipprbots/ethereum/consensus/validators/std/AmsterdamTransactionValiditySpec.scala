package com.chipprbots.ethereum.consensus.validators.std

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.consensus.validators.SignedTransactionError
import com.chipprbots.ethereum.consensus.validators.SignedTransactionError.*
import com.chipprbots.ethereum.consensus.validators.SignedTransactionValid
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.ledger.AmsterdamFixtureVectors
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.ForkTimestamps

/** Amsterdam transaction validity (EIP-7976 / EIP-7981 / EIP-8037), execution-specs `validate_transaction`:
  * {{{
  * intrinsic.execution     > tx.gas            -> invalid  (INTRINSIC_GAS_TOO_LOW)
  * intrinsic.calldata_floor > tx.gas           -> invalid  (INTRINSIC_GAS_BELOW_FLOOR_GAS_COST)
  * intrinsic.execution     > TX_MAX_GAS_LIMIT  -> invalid  (INTRINSIC_GAS_TOO_LOW)
  * intrinsic.calldata_floor > TX_MAX_GAS_LIMIT -> invalid  (INTRINSIC_GAS_TOO_LOW)
  * }}}
  * in that order, in the block validator and in the txpool's stateless pre-filter. Each rule is shown in both
  * directions, and against the same transaction one fork earlier.
  */
// scalastyle:off magic.number
class AmsterdamTransactionValiditySpec extends AnyFlatSpec with Matchers with AmsterdamFixtureVectors:

  private val recipient: Address = Address(Hex.decode("83c7e323d189f18725ac510004fdc2941f8c4a78"))
  private val sender: Account = Account(nonce = UInt256(0), balance = UInt256(BigInt(10).pow(30)))

  private def nonZeros(n: Int): ByteString = ByteString(Array.fill[Byte](n)(0x01))

  private def validate(
      stx: SignedTransaction,
      header: BlockHeader,
      config: BlockchainConfig
  ): Either[SignedTransactionError, SignedTransactionValid] =
    val upfront = UInt256(stx.tx.gasLimit.value * BigInt(1_000_000_000))
    StdSignedTransactionValidator.validate(stx, sender, header, upfront, 0)(config)

  /** 1,000 non-zero bytes to an EOA, zero value. Amsterdam: intrinsic 15,000 + 16,000 = 31,000, floor 15,000 + 64,000 =
    * 79,000. Osaka: intrinsic 21,000 + 16,000 = 37,000, EIP-7623 floor 21,000 + 4,000 x 10 = 61,000.
    */
  private def heavyCall(gasLimit: BigInt, config: BlockchainConfig): SignedTransaction =
    dynamicFeeTx(Some(recipient), value = 0, gasLimit = gasLimit, payload = nonZeros(1000), config = config)

  private val atAmsterdam = amsterdamHeader(46, 460)
  private val atOsaka = preAmsterdamHeader(46, 300)

  // ── tx.gas >= calldata floor ──────────────────────────────────────────────

  "Under Amsterdam, a transaction below its calldata floor" should "be invalid, reported as the floor error" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    val result = validate(heavyCall(78999, amsterdamConfig), atAmsterdam, amsterdamConfig)
    result shouldBe Left(TransactionNotEnoughGasForFloorError(78999, 79000))
    result.left.toOption.get.toString should startWith("INTRINSIC_GAS_BELOW_FLOOR_GAS_COST")
  }

  it should "be valid with exactly the floor" taggedAs (UnitTest, ConsensusTest) in {
    validate(heavyCall(79000, amsterdamConfig), atAmsterdam, amsterdamConfig) shouldBe Right(SignedTransactionValid)
  }

  it should "report the intrinsic error first when it is below both (execution-specs order)" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    validate(heavyCall(30999, amsterdamConfig), atAmsterdam, amsterdamConfig) shouldBe
      Left(TransactionNotEnoughGasForIntrinsicError(30999, 31000))
  }

  "The same transaction before Amsterdam" should "not be held to the Amsterdam floor" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    // 61,000 covers EIP-7623's floor and Osaka's intrinsic cost, and is 18,000 short of Amsterdam's floor.
    validate(heavyCall(61000, preAmsterdamConfig), atOsaka, preAmsterdamConfig) shouldBe Right(SignedTransactionValid)
    validate(heavyCall(61000, amsterdamConfig), atAmsterdam, amsterdamConfig) shouldBe
      Left(TransactionNotEnoughGasForFloorError(61000, 79000))
  }

  // ── EIP-7981: the access list's data cost is intrinsic ────────────────────

  "Under Amsterdam, the access-list data cost" should "count toward the intrinsic check" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    // 15,000 + 2,900 + 2,000 + (1,280 + 2,048) = 23,228; the floor, 15,000 + 3,328 = 18,328, does not bind.
    val accessList = List(AccessListItem(recipient, List(StorageKey(BigInt(7)))))
    def tx(gasLimit: BigInt) =
      dynamicFeeTx(Some(recipient), 0, gasLimit, accessList = accessList, config = amsterdamConfig)
    validate(tx(23227), atAmsterdam, amsterdamConfig) shouldBe Left(
      TransactionNotEnoughGasForIntrinsicError(23227, 23228)
    )
    validate(tx(23228), atAmsterdam, amsterdamConfig) shouldBe Right(SignedTransactionValid)
  }

  // ── EIP-8037: the floor is capped at TX_MAX_GAS_LIMIT, tx.gas is not ──────

  "Under Amsterdam, a calldata floor above TX_MAX_GAS_LIMIT" should "be invalid even when tx.gas covers it" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    // EEST `test_calldata_floor_exceeding_tx_gas_limit_cap`. 261,910 non-zero bytes: floor 15,000 + 16,762,240 =
    // 16,777,240 > 2^24, intrinsic 15,000 + 4,190,560 = 4,205,560 < 2^24, and tx.gas funds the floor in full so only
    // the cap can reject it.
    val stx = dynamicFeeTx(Some(recipient), 0, 17777240, payload = nonZeros(261910), config = amsterdamConfig)
    val result = validate(stx, atAmsterdam, amsterdamConfig)
    result shouldBe Left(TransactionIntrinsicCostExceedsCap(4205560, 16777240, 16777216))
    result.left.toOption.get.toString should startWith("INTRINSIC_GAS_TOO_LOW")
  }

  it should "admit the largest floor that fits, at tx.gas = 2^24" taggedAs (UnitTest, ConsensusTest) in {
    // 261,909 bytes: floor 15,000 + 16,762,176 = 16,777,176 <= 16,777,216.
    val stx = dynamicFeeTx(Some(recipient), 0, 16777216, payload = nonZeros(261909), config = amsterdamConfig)
    validate(stx, atAmsterdam, amsterdamConfig) shouldBe Right(SignedTransactionValid)
  }

  // ── The txpool's stateless pre-filter, where it already selects Amsterdam ──

  /** Every timestamp fork at genesis, so the pre-filter's "latest configured fork" is Amsterdam. */
  private val amsterdamAtGenesis: BlockchainConfig = amsterdamConfig.copy(forkTimestamps =
    ForkTimestamps(
      shanghaiTimestamp = Some(0L),
      cancunTimestamp = Some(0L),
      pragueTimestamp = Some(0L),
      osakaTimestamp = Some(0L),
      amsterdamTimestamp = Some(0L)
    )
  )
  private val osakaAtGenesis: BlockchainConfig =
    amsterdamAtGenesis.copy(forkTimestamps = amsterdamAtGenesis.forkTimestamps.copy(amsterdamTimestamp = None))

  "The txpool pre-filter under Amsterdam" should "drop a transaction below its calldata floor and keep one at it" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    SignedTransactionWithSender.getStatelessValidTransactions(Seq(heavyCall(78999, amsterdamAtGenesis)))(
      amsterdamAtGenesis
    ) shouldBe empty
    SignedTransactionWithSender.getStatelessValidTransactions(Seq(heavyCall(79000, amsterdamAtGenesis)))(
      amsterdamAtGenesis
    ) should have size 1
  }

  it should "drop a transaction whose floor exceeds TX_MAX_GAS_LIMIT" taggedAs (UnitTest, ConsensusTest) in {
    val stx = dynamicFeeTx(Some(recipient), 0, 17777240, payload = nonZeros(261910), config = amsterdamAtGenesis)
    SignedTransactionWithSender.getStatelessValidTransactions(Seq(stx))(amsterdamAtGenesis) shouldBe empty
  }

  it should "keep applying the intrinsic rule alone before Amsterdam" taggedAs (UnitTest, ConsensusTest) in {
    SignedTransactionWithSender.getStatelessValidTransactions(Seq(heavyCall(61000, osakaAtGenesis)))(
      osakaAtGenesis
    ) should have size 1
  }
