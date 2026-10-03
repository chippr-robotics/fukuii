package com.chipprbots.ethereum.vm

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.CodeHash
import com.chipprbots.ethereum.domain.GasAmount
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.ledger.AmsterdamFixtureVectors
import com.chipprbots.ethereum.ledger.InMemoryWorldStateProxy
import com.chipprbots.ethereum.ledger.InMemoryWorldStateProxyStorage
import com.chipprbots.ethereum.ledger.VMImpl
import com.chipprbots.ethereum.testing.Tags.*

/** #1437: a precompile frame hands the caller's EIP-8037 counters back unchanged.
  *
  * execution-specs `process_call` runs a precompile in an ordinary child frame: the child is handed the caller's whole
  * reservoir, the precompile charges execution gas only (`charge_gas`), and `incorporate_child` gives the reservoir
  * back whether the precompile succeeded or halted. fukuii's caller adopts the child's counters instead of adding them,
  * so a precompile result that left them at 0 wiped the caller's unspent reservoir and the state gas already charged —
  * 3,478 of the 5,403 failing `tests@v21.0.0` Amsterdam fixtures showed exactly that signature.
  */
// scalastyle:off magic.number
class AmsterdamPrecompileStateGasSpec extends AnyFlatSpec with Matchers with AmsterdamFixtureVectors:

  type W = InMemoryWorldStateProxy
  type S = InMemoryWorldStateProxyStorage

  private val Caller: Address = Address(0x5252)
  private val Identity: Address = PrecompiledContracts.IdAddr // 15 + 3/word
  private val EcRecover: Address = PrecompiledContracts.EcDsaRecAddr // 3,000 flat

  /** `CALL(gas, target, 0, 0, 32, 0, 0); STOP`, with `gas` a PUSH2. */
  private def callCode(target: Address, gas: Int): Array[Byte] =
    Array[Byte](0x60, 0x00, 0x60, 0x00, 0x60, 0x20, 0x60, 0x00, 0x60, 0x00) ++ // retSize retOffset argsSize argsOff val
      Array[Byte](0x60, target.bytes.last) ++ // PUSH1 target (a precompile's address fits one byte)
      Array[Byte](0x61, ((gas >> 8) & 0xff).toByte, (gas & 0xff).toByte) ++
      Array[Byte](0xf1.toByte, 0x00) // CALL; STOP

  /** `SSTORE(1, 1)` on a fresh slot — 97,920 of state gas, drawn from the reservoir — then the CALL above. */
  private def storeThenCall(target: Address, gas: Int): ByteString =
    ByteString(Array[Byte](0x60, 0x01, 0x60, 0x01, 0x55) ++ callCode(target, gas))

  private def worldWith(code: ByteString): W =
    block45World
      .saveAccount(Caller, Account(nonce = UInt256(1), balance = UInt256(0), codeHash = CodeHash(kec256(code))))
      .saveCode(Caller, code)

  /** A frame running `code` at `Caller` with a reservoir and a transaction-level state-gas total already in flight. */
  private def frameContext(code: ByteString, reservoir: BigInt, stateGasUsed: BigInt): ProgramContext[W, S] =
    val header = amsterdamHeader(500, 5000).copy(gasLimit = GasAmount(60_000_000))
    val stx = dynamicFeeTx(Some(Caller), value = 0, gasLimit = 1_000_000, config = amsterdamConfig)
    val evmConfig = EvmConfig.forBlock(header.number.value, header.unixTimestamp, amsterdamConfig)
    ProgramContext[W, S](stx, header, senderAddress, worldWith(code), evmConfig)
      .copy(
        startGas = 500_000,
        stateGasReservoir = reservoir,
        evmStateGasUsed = stateGasUsed,
        stateGasBaselineOverride = Some(reservoir)
      )

  "a CALL to a precompile" should "leave the caller's reservoir and state gas used unchanged" taggedAs (
    VMTest,
    ConsensusTest
  ) in {
    val result = new VMImpl().run(frameContext(ByteString(callCode(Identity, 0xffff)), 700_000, 97_920))

    result.error shouldBe None
    result.stateGasReservoir shouldBe BigInt(700_000)
    result.evmStateGasUsed shouldBe BigInt(97_920)
  }

  it should "leave them unchanged when the precompile itself fails" taggedAs (VMTest, ConsensusTest) in {
    // 0 gas forwarded to ecrecover (3,000): the child halts out of gas, the CALL pushes 0, the caller carries on.
    // execution-specs: the halted child restores its state gas to the baseline it was handed — the caller's whole
    // reservoir — and `incorporate_child` gives that back.
    val result = new VMImpl().run(frameContext(ByteString(callCode(EcRecover, 0)), 700_000, 97_920))

    result.error shouldBe None
    result.stateGasReservoir shouldBe BigInt(700_000)
    result.evmStateGasUsed shouldBe BigInt(97_920)
  }

  it should "not seed or move any counter before Amsterdam" taggedAs (VMTest, ConsensusTest) in {
    // The ETC / pre-Amsterdam guarantee, stated at an assertion site: the context carries zeros, so the precompile
    // result carries zeros — the shape it always had.
    val header = preAmsterdamHeader(500, 300).copy(gasLimit = GasAmount(60_000_000))
    val stx = dynamicFeeTx(Some(Caller), value = 0, gasLimit = 1_000_000, config = preAmsterdamConfig)
    val evmConfig = EvmConfig.forBlock(header.number.value, header.unixTimestamp, preAmsterdamConfig)
    val ctx =
      ProgramContext[W, S](stx, header, senderAddress, worldWith(ByteString(callCode(Identity, 0xffff))), evmConfig)
    val result = new VMImpl().run(ctx)

    result.error shouldBe None
    result.stateGasReservoir shouldBe BigInt(0)
    result.evmStateGasUsed shouldBe BigInt(0)
    result.stateGasFromGasLeft shouldBe BigInt(0)
    result.stateGasBaseline shouldBe BigInt(0)
  }

  // ── End to end: the settlement a lost reservoir used to corrupt ──────────────────────

  private val Reservoir: BigInt = 200_000
  private val header = amsterdamHeader(500, 5000).copy(gasLimit = GasAmount(60_000_000))

  "a transaction above TX_MAX_GAS_LIMIT that calls a precompile" should "keep its state gas and get its unspent reservoir back" taggedAs (
    VMTest,
    ConsensusTest
  ) in {
    // intrinsic 15,000 (TX_BASE + COLD_ACCOUNT_ACCESS) + PUSH1 x2 6 + SSTORE fresh cold slot 2,100 + 10,000 + PUSH x7 21
    // + CALL warm precompile 100 + memory 3 + identity 15 + 3 x 1 word = 27,248 of execution gas, and 97,920 of state
    // gas from the reservoir. The other 102,080 of the reservoir goes back to the sender.
    val stx = dynamicFeeTx(
      Some(Caller),
      value = 0,
      gasLimit = AmsterdamGas.TxMaxGasLimit + Reservoir,
      config = amsterdamConfig
    )
    val result = execute(stx, header, worldWith(storeThenCall(Identity, 0xffff)), amsterdamConfig)

    result.vmError shouldBe None
    result.stateGasUsed shouldBe AmsterdamGas.GasStorageSet
    result.executionGasUsed shouldBe BigInt(27_248)
    result.gasUsed shouldBe BigInt(27_248) + AmsterdamGas.GasStorageSet
  }

  it should "settle the same way when the precompile fails inside the transaction" taggedAs (VMTest, ConsensusTest) in {
    // As above, but the CALL forwards 0 gas to ecrecover: 103 for the CALL instead of 121.
    val stx = dynamicFeeTx(
      Some(Caller),
      value = 0,
      gasLimit = AmsterdamGas.TxMaxGasLimit + Reservoir,
      config = amsterdamConfig
    )
    val result = execute(stx, header, worldWith(storeThenCall(EcRecover, 0)), amsterdamConfig)

    result.vmError shouldBe None
    result.stateGasUsed shouldBe AmsterdamGas.GasStorageSet
    result.executionGasUsed shouldBe BigInt(27_230)
    result.gasUsed shouldBe BigInt(27_230) + AmsterdamGas.GasStorageSet
  }

  "a transaction sent directly to a precompile" should "keep the pre-execution account-creation charge" taggedAs (
    VMTest,
    ConsensusTest
  ) in {
    // EEST `value_move_to_precompiles[...not_funded-non-zero_value]`: the precompile has no account, so the value
    // transfer creates one — 183,600 of state gas charged in EIP-2780's pre-execution phase, from the reservoir.
    // intrinsic 12,000 + 3,000 + TX_VALUE_COST 6,000 = 21,000, + identity 15.
    val stx = dynamicFeeTx(
      Some(Identity),
      value = 1,
      gasLimit = AmsterdamGas.TxMaxGasLimit + Reservoir,
      config = amsterdamConfig
    )
    val result = execute(stx, header, block45World, amsterdamConfig)

    result.vmError shouldBe None
    result.stateGasUsed shouldBe AmsterdamGas.GasNewAccount
    result.executionGasUsed shouldBe BigInt(21_015)
    result.gasUsed shouldBe BigInt(21_015) + AmsterdamGas.GasNewAccount
    result.worldState.getBalance(Identity) shouldBe UInt256(1)
  }

  it should "refill that charge when the precompile fails, as any failed top-level frame does" taggedAs (
    VMTest,
    ConsensusTest
  ) in {
    // ecAdd of (1, 1) — not on the curve — fails. The frame halts and its account creation is rolled back, so the
    // 183,600 goes back to the reservoir (execution-specs `restore_state_gas`) and the whole reservoir is returned;
    // the execution grant is forfeited. The transaction therefore uses exactly TX_MAX_GAS_LIMIT: intrinsic plus a
    // grant of TX_MAX_GAS_LIMIT - intrinsic.
    val notOnCurve = ByteString(Array.fill[Byte](31)(0) :+ 1.toByte) ++ ByteString(Array.fill[Byte](31)(0) :+ 1.toByte)
    val stx = dynamicFeeTx(
      Some(PrecompiledContracts.Bn128AddAddr),
      value = 1,
      gasLimit = AmsterdamGas.TxMaxGasLimit + Reservoir,
      payload = notOnCurve ++ ByteString(Array.fill[Byte](64)(0)),
      config = amsterdamConfig
    )
    val result = execute(stx, header, block45World, amsterdamConfig)

    result.vmError shouldBe Some(PreCompiledContractFail)
    result.stateGasUsed shouldBe BigInt(0)
    result.gasUsed shouldBe AmsterdamGas.TxMaxGasLimit
    result.worldState.getAccount(PrecompiledContracts.Bn128AddAddr) shouldBe None
  }
