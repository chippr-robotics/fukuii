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
import com.chipprbots.ethereum.testing.Tags.*

/** EIP-8037: a CALL that transfers value to a non-existent account pays the account-creation state charge (120 x 1,530
  * \= 183,600) BEFORE the child's gas grant is sized, so the charge competes with the call's own execution charge and
  * not with the gas it forwards.
  *
  * The tests@v21.0.0 fixtures reach this only through Amsterdam re-fills of older tests (frontier
  * `test_value_transfer_gas_calculation[gas_shortage_0-callee_opcode_CALL]`,
  * `test_static_call_identity_1_nonzero_value`, `test_multi_selfdestruct[d3, d4]`, `test_wallet_confirm`), whose
  * block-level `gasUsed` only says that something is off. The figures below pin the rule itself. They are derived by
  * hand from execution-specs `forks/amsterdam` `vm/instructions/system.py` `call` (charge `extra_gas + memory`, then
  * `charge_state_gas(NEW_ACCOUNT)`, then `calculate_message_call_gas(..., gas_left, 0, 0)` = min(gas, gas_left -
  * gas_left // 64), plus the 2,300 stipend), `vm/__init__.py` `incorporate_child` and `vm/gas.py`
  * `settle_transaction_gas`. The derivation is in each test.
  *
  * The contract: PUSH1 0 x4 (return and argument windows), PUSH1 1 (value), PUSH20 fresh, GAS, CALL, STOP. The
  * transaction calls it with value 0 and no calldata: intrinsic TX_BASE 12,000 + COLD_ACCOUNT_ACCESS 3,000 = 15,000,
  * reservoir 0 (tx.gas is below 2^24). The six pushes cost 18 and GAS 2, so GAS pushes, and CALL starts with, tx.gas -
  * 15,020. CALL's own charge is COLD_ACCOUNT_ACCESS 3,000 + CALL_VALUE 11,300 = 14,300 (no memory, no delegation).
  */
// scalastyle:off magic.number
class AmsterdamCallNewAccountSpec extends AnyFlatSpec with Matchers with AmsterdamFixtureVectors:

  private val Caller: Address = Address(0x5a11)
  private val Fresh: Address = Address(0xc0ffee)

  private val CallerCode: ByteString = ByteString(
    Array[Byte](0x60, 0x00, 0x60, 0x00, 0x60, 0x00, 0x60, 0x00) ++ // retSize, retOffset, argsSize, argsOffset
      Array[Byte](0x60, 0x01) ++ // value = 1
      Array[Byte](0x73) ++ Fresh.bytes.toArray ++ // PUSH20 fresh
      Array[Byte](0x5a, 0xf1.toByte, 0x00) // GAS, CALL, STOP
  )

  private def world: InMemoryWorldStateProxy =
    block45World
      .saveAccount(
        Caller,
        Account(nonce = UInt256(0), balance = UInt256(1000), codeHash = CodeHash(kec256(CallerCode)))
      )
      .saveCode(Caller, CallerCode)

  private def run(gasLimit: BigInt, amsterdam: Boolean = true) =
    val (config, header) =
      if amsterdam then (amsterdamConfig, amsterdamHeader(500, 5000))
      else (preAmsterdamConfig, preAmsterdamHeader(500, 5000))
    world.getAccount(Fresh) shouldBe None
    val stx = dynamicFeeTx(Some(Caller), value = 0, gasLimit = gasLimit, config = config)
    execute(stx, header.copy(gasLimit = GasAmount(60_000_000)), world, config)

  "a value-bearing CALL to a fresh account" should "charge the state gas before sizing the child's grant" taggedAs (
    VMTest,
    ConsensusTest
  ) in {
    // tx.gas 1,000,000: CALL starts with 984,980. Own charge 14,300 -> 970,680. NEW_ACCOUNT 183,600 from gas_left
    // (reservoir 0) -> 787,080. Child grant min(984,980, 787,080 - 12,298) = 774,782, plus the 2,300 stipend. The
    // child has no code and returns all 777,082: 12,298 + 777,082 = 789,380 left after STOP.
    // gas used = 1,000,000 - 789,380 = 210,620, of which 183,600 is state gas.
    //
    // The previous rule required the charge to fit in what was left AFTER forwarding 63/64 (15,166 here), so this
    // transaction halted and consumed all 1,000,000.
    val result = run(1000000)
    result.vmError shouldBe None
    result.gasUsed shouldBe BigInt(210620)
    result.stateGasUsed shouldBe AmsterdamGas.GasNewAccount
    result.executionGasUsed shouldBe BigInt(210620 - 183600)
    result.worldState.getBalance(Fresh) shouldBe UInt256(1)
    result.worldState.getBalance(Caller) shouldBe UInt256(999)
    // EIP-7708: the one-wei transfer's log, from the caller to the account it created.
    result.logs.size shouldBe 1
  }

  it should "succeed with exactly the charge left after the call's own charge, and forward only the stipend" taggedAs (
    VMTest,
    ConsensusTest
  ) in {
    // tx.gas 212,920: CALL starts with 197,900; own charge -> 183,600; NEW_ACCOUNT takes all of it -> 0. Child grant
    // min(197,900, 0) = 0, plus the 2,300 stipend, all returned: 2,300 left. gas used = 212,920 - 2,300 = 210,620.
    val result = run(212920)
    result.vmError shouldBe None
    result.gasUsed shouldBe BigInt(210620)
    result.stateGasUsed shouldBe AmsterdamGas.GasNewAccount
    result.worldState.getBalance(Fresh) shouldBe UInt256(1)
  }

  it should "halt one gas short of the charge, with the state gas rolled back" taggedAs (VMTest, ConsensusTest) in {
    // tx.gas 212,919: after the own charge 183,599 remain, one short of NEW_ACCOUNT: an exceptional halt of the top
    // frame. Its state gas restores to the baseline (0) and the rest of the gas is forfeited: gas used = the whole
    // 212,919, all of it execution gas, and no account is created.
    val result = run(212919)
    result.vmError shouldBe Some(OutOfGas)
    result.gasUsed shouldBe BigInt(212919)
    result.stateGasUsed shouldBe BigInt(0)
    result.worldState.getAccount(Fresh) shouldBe None
  }

  "the same CALL before Amsterdam" should "still pay G_newaccount in execution gas, unchanged" taggedAs (
    VMTest,
    ConsensusTest
  ) in {
    // Osaka: intrinsic 21,000, CALL starts with 978,980. Own charge cold 2,600 + G_callvalue 9,000 + G_newaccount 25,000
    // = 36,600; grant 942,380 - 14,724 = 927,656 (+2,300), all returned: 14,724 + 929,956 = 944,680 left.
    // gas used = 1,000,000 - 944,680 = 55,320.
    val result = run(1000000, amsterdam = false)
    result.vmError shouldBe None
    result.gasUsed shouldBe BigInt(55320)
    result.stateGasUsed shouldBe BigInt(0)
    result.worldState.getBalance(Fresh) shouldBe UInt256(1)
  }
