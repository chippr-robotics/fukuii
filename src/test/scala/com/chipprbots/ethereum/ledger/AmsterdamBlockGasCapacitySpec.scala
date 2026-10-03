package com.chipprbots.ethereum.ledger

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.consensus.validators.std.StdSignedTransactionValidator
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.ledger.BlockExecutionError.TxsExecutionError
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig

/** EIP-8037 block gas capacity: execution-specs `check_block_gas_capacity`, go-ethereum `GasPool.CheckGasAmsterdam`.
  * {{{
  * min(TX_MAX_GAS_LIMIT, tx.gas) <= gasLimit - block_execution_gas_used
  * tx.gas                        <= gasLimit - block_state_gas_used
  * }}}
  * replacing, from Amsterdam only, the single-counter `tx.gas + receipt sum <= gasLimit`. The receipt sum adds both
  * dimensions (and is post-refund); the header takes their maximum (and counts execution pre-refund, EIP-7778), so the
  * old rule is wrong in BOTH directions. Each vector below runs whole blocks through the real VM and
  * [[StdSignedTransactionValidator]], with every figure computed by hand:
  *
  *   - `fill` stores 1 into five fresh slots: 15,000 intrinsic + 5 x (3 + 3 + 2,100 cold + 10,000 STORAGE_WRITE) =
  *     75,530 execution, 5 x 97,920 = 489,600 state, receipt 565,130.
  *   - `clear` zeroes five slots that hold 1: 75,530 execution, no state, refund min(75,530 / 5, 5 x 11,616) = 15,106,
  *     receipt 60,424.
  *   - `burn` is a lone INVALID: an exceptional halt that consumes the whole gas limit as execution gas.
  */
// scalastyle:off magic.number
class AmsterdamBlockGasCapacitySpec extends AnyFlatSpec with Matchers with AmsterdamFixtureVectors:

  private val preparator =
    new BlockPreparator(setup.vm, StdSignedTransactionValidator, setup.blockchain, setup.blockchainReader)

  private val recipient: Address = Address(Hex.decode("83c7e323d189f18725ac510004fdc2941f8c4a78"))
  private val Fill: Address = Address(Hex.decode("00000000000000000000000000000000000f1111"))
  private val Clear: Address = Address(Hex.decode("00000000000000000000000000000000000c1ea4"))
  private val Burn: Address = Address(Hex.decode("00000000000000000000000000000000000b0412"))

  /** `PUSH1 value PUSH1 slot SSTORE` for slots 1..5, then STOP. */
  private def storeFive(value: Int): ByteString =
    ByteString(Hex.decode((1 to 5).map(slot => f"60$value%02x60$slot%02x55").mkString + "00"))

  private def withContract(
      world: InMemoryWorldStateProxy,
      address: Address,
      code: ByteString,
      slots: Seq[Int] = Nil
  ): InMemoryWorldStateProxy =
    val w = world
      .saveAccount(address, Account(nonce = UInt256(0), balance = UInt256(0), codeHash = CodeHash(kec256(code))))
      .saveCode(address, code)
    w.saveStorage(address, slots.foldLeft(w.getStorage(address))((s, k) => s.store(UInt256(k), UInt256(1))))

  private def world: InMemoryWorldStateProxy =
    val funded = setup.emptyWorld
      .saveAccount(senderAddress, Account(nonce = UInt256(0), balance = UInt256(BigInt(10).pow(24))))
      .saveAccount(recipient, Account(nonce = UInt256(0), balance = UInt256(1)))
    val withFill = withContract(funded, Fill, storeFive(1))
    val withClear = withContract(withFill, Clear, storeFive(0), slots = 1 to 5)
    withContract(withClear, Burn, ByteString(Hex.decode("fe")))

  private def header(gasLimit: BigInt, config: BlockchainConfig): BlockHeader =
    if config.forkTimestamps.amsterdamTimestamp.isDefined then
      amsterdamHeader(50, 500).copy(gasLimit = GasAmount(gasLimit))
    else preAmsterdamHeader(50, 300).copy(gasLimit = GasAmount(gasLimit))

  private def call(to: Address, gasLimit: BigInt, nonce: Int, config: BlockchainConfig): SignedTransaction =
    dynamicFeeTx(Some(to), value = 0, gasLimit = gasLimit, nonce = nonce, config = config)

  private def transfer(gasLimit: BigInt, nonce: Int, config: BlockchainConfig): SignedTransaction =
    dynamicFeeTx(Some(recipient), value = 1, gasLimit = gasLimit, nonce = nonce, config = config)

  private def run(
      txs: Seq[SignedTransaction],
      blockGasLimit: BigInt,
      config: BlockchainConfig
  ): Either[TxsExecutionError, BlockResult] =
    preparator.executeTransactions(txs, world, header(blockGasLimit, config))(config)

  private def rejection(result: Either[TxsExecutionError, BlockResult]): String =
    result.fold(_.reason, r => fail(s"expected the block to be rejected, it executed: $r"))

  // ── Direction 1: valid blocks the receipt-sum rule rejected ───────────────

  "Under Amsterdam, a block whose receipt sum exceeds gasLimit - tx.gas" should "still be valid" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    // After `fill`: execution 75,530, state 489,600, receipts 565,130. A 500,000-gas transfer fits both dimensions
    // (500,000 <= 1,000,000 - 75,530 and <= 1,000,000 - 489,600) though 500,000 + 565,130 > 1,000,000.
    val result =
      run(Seq(call(Fill, 600000, 0, amsterdamConfig), transfer(500000, 1, amsterdamConfig)), 1000000, amsterdamConfig)
    val block = result.fold(e => fail(s"valid block rejected: ${e.reason}"), identity)
    block.executionGasUsed shouldBe BigInt(75530 + 21000)
    block.stateGasUsed shouldBe BigInt(489600)
    block.gasUsed shouldBe BigInt(489600) // the header: max of the two dimensions
    block.receipts.map(_.cumulativeGasUsed) shouldBe Seq(BigInt(565130), BigInt(586130)) // receipts: the sum
  }

  it should "be valid at the exact state-dimension boundary and invalid one gas above it" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    // 1,000,000 - 489,600 = 510,400 of state room left.
    run(
      Seq(call(Fill, 600000, 0, amsterdamConfig), transfer(510400, 1, amsterdamConfig)),
      1000000,
      amsterdamConfig
    ).isRight shouldBe true
    val over = run(
      Seq(call(Fill, 600000, 0, amsterdamConfig), transfer(510401, 1, amsterdamConfig)),
      1000000,
      amsterdamConfig
    )
    rejection(over) shouldBe
      "GAS_ALLOWANCE_EXCEEDED: Tx gas limit (510401) + block state gas used (489600) > block gas limit (1000000)"
  }

  it should "reserve at most TX_MAX_GAS_LIMIT of execution gas for a transaction above it" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    // `burn` spends all 3,000,000 as execution, leaving 17,000,000 of execution room. A 17,000,001-gas transfer needs
    // only min(2^24, 17,000,001) = 16,777,216 of it; its full gas limit is checked against the state room, 20,000,000.
    // Both the receipt-sum rule (20,000,001) and an uncapped execution check (17,000,001) would reject this block.
    val result = run(
      Seq(call(Burn, 3000000, 0, amsterdamConfig), transfer(17000001, 1, amsterdamConfig)),
      20000000,
      amsterdamConfig
    )
    val block = result.fold(e => fail(s"valid block rejected: ${e.reason}"), identity)
    block.executionGasUsed shouldBe BigInt(3000000 + 21000)
    block.stateGasUsed shouldBe BigInt(0)
  }

  // ── Direction 2: invalid blocks the receipt-sum rule accepted ─────────────

  "Under Amsterdam, a transaction that overflows the pre-refund execution dimension" should "invalidate the block" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    // After `clear`: execution 75,530 (EIP-7778: before refunds), receipts 60,424 (after them). A 930,000-gas transfer
    // passes the receipt-sum rule (930,000 + 60,424 <= 1,000,000) but not the execution dimension
    // (930,000 > 1,000,000 - 75,530 = 924,470).
    val result =
      run(Seq(call(Clear, 100000, 0, amsterdamConfig), transfer(930000, 1, amsterdamConfig)), 1000000, amsterdamConfig)
    rejection(result) shouldBe
      "GAS_ALLOWANCE_EXCEEDED: Tx execution gas reservation (930000) + block execution gas used (75530) > block gas " +
      "limit (1000000)"
  }

  it should "leave the block valid at the exact execution-dimension boundary" taggedAs (UnitTest, ConsensusTest) in {
    val result =
      run(Seq(call(Clear, 100000, 0, amsterdamConfig), transfer(924470, 1, amsterdamConfig)), 1000000, amsterdamConfig)
    val block = result.fold(e => fail(s"valid block rejected: ${e.reason}"), identity)
    block.executionGasUsed shouldBe BigInt(75530 + 21000)
    block.receipts.map(_.cumulativeGasUsed) shouldBe Seq(BigInt(60424), BigInt(81424))
  }

  // ── Before Amsterdam: the receipt-sum rule, unchanged ─────────────────────

  "Before Amsterdam, block capacity" should "still be tx.gas + receipt sum <= gasLimit" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    // Osaka `fill`: 21,000 + 5 x (3 + 3 + 2,100 + 20,000) = 131,530, all of it in the one counter.
    val over = run(
      Seq(call(Fill, 600000, 0, preAmsterdamConfig), transfer(868471, 1, preAmsterdamConfig)),
      1000000,
      preAmsterdamConfig
    )
    rejection(over) shouldBe
      "GAS_LIMIT_EXCEEDS_BLOCK_GAS_LIMIT: Tx gas limit (868471) + gas accum (131530) > block gas limit (1000000)"
    run(
      Seq(call(Fill, 600000, 0, preAmsterdamConfig), transfer(868470, 1, preAmsterdamConfig)),
      1000000,
      preAmsterdamConfig
    ).isRight shouldBe true
  }

  // ── The rule function the payload builder shares ──────────────────────────

  "blockGasCapacityError" should "select the rule by the block's timestamp" taggedAs (UnitTest, ConsensusTest) in {
    def check(ts: Long, txGas: BigInt) =
      StdSignedTransactionValidator.blockGasCapacityError(txGas, 1000000, Timestamp(ts), 565130, 75530, 489600)(
        amsterdamConfig
      )
    check(359, 500000).isDefined shouldBe true // Osaka: 500,000 + 565,130 > 1,000,000
    check(360, 500000) shouldBe None // Amsterdam: fits both dimensions
    check(360, 510401).isDefined shouldBe true // Amsterdam: state dimension
  }
