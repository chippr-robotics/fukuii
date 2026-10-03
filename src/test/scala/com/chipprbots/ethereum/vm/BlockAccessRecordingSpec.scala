package com.chipprbots.ethereum.vm

import org.apache.pekko.util.ByteString

import org.scalacheck.Gen
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.vm.MockWorldState.PS

import Fixtures.blockchainConfig

/** EIP-7928 "Gas Validation Before State Access", at the opcode level: a state-accessing opcode records its target
  * exactly when execution-specs has read it — once the state-independent part of the charge is affordable — and not
  * before. fukuii prices the whole charge before its out-of-gas check, so SSTORE and SELFDESTRUCT, whose charge has a
  * state-dependent part, split the decision into `recordReadsBeforeOutOfGas`; BALANCE, EXTCODE and SLOAD reach `exec`
  * only when their (state-independent) charge is paid, so recording there is the same rule.
  *
  * Reference: execution-specs `forks/amsterdam` `vm/instructions/{environment,storage,system}.py`. The CALL family,
  * CREATE, reverts and the system indices are exercised end to end by the eip7928 corpus (the `_and_oog` opcode
  * fixtures, `bal_parent_revert_state_access`, the `bal_4788` and `bal_7002` system-call fixtures); this isolates the
  * per-opcode gas boundary.
  */
// scalastyle:off magic.number
class BlockAccessRecordingSpec extends AnyFunSuite with Matchers:

  private val amsterdam: EvmConfig = EvmConfig
    .BerlinConfigBuilder(blockchainConfig)
    .copy(
      opCodeList = EvmConfig.AmsterdamOpCodes,
      feeSchedule = new FeeSchedule.AmsterdamFeeSchedule,
      amsterdamEnabled = true
    )

  private val target = Address(0xdead)

  /** A top-level frame at block 1,000 (past Berlin, so EIP-2929 cold/warm applies) with a recorder attached and `gas`
    * set, owner holding `ownerBalance`.
    */
  private def stateWith(stack: Stack, gas: BigInt, ownerBalance: UInt256 = UInt256.Zero): (PS, BlockAccessRecorder) =
    val recorder = new BlockAccessRecorder
    val base = Generators
      .getProgramStateGen(evmConfig = amsterdam, blockNumberGen = Gen.const(UInt256(1000)), isTopHeader = true)
      .sample
      .get
    val owner = base.ownAddress
    val world = base.world.saveAccount(owner, Account.empty().increaseBalance(ownerBalance))
    val s = base
      .withStack(stack)
      .withWorld(world)
      .copy(gas = gas, env = base.env.copy(accessRecorder = Some(recorder)))
    (s, recorder)

  private def addressStack = Stack.empty().push(target.toUInt256)
  // SSTORE(slot=1, value=9): pop order is (slot, value), so push value then slot.
  private def sstoreStack = Stack.empty().push(Seq(UInt256(9), UInt256(1)))

  test("BALANCE records its target once it reaches exec", UnitTest, VMTest) {
    val (state, rec) = stateWith(addressStack, gas = 100000)
    BALANCE.execute(state).error shouldBe None
    rec.addresses should contain(target)
  }

  test("SLOAD records its slot once it reaches exec", UnitTest, VMTest) {
    val (state, rec) = stateWith(Stack.empty().push(UInt256(7)), gas = 100000)
    SLOAD.execute(state).error shouldBe None
    rec.slots.get(state.ownAddress).map(_.toSet) shouldBe Some(Set(UInt256(7)))
  }

  test("SSTORE on success records the slot", UnitTest, VMTest) {
    // Enough to cover the 12,100 execution charge AND the GAS_STORAGE_SET (97,920) state charge for a fresh slot.
    val (state, rec) = stateWith(sstoreStack, gas = 200000)
    SSTORE.execute(state).error shouldBe None
    rec.slots.get(state.ownAddress).map(_.toSet) shouldBe Some(Set(UInt256(1)))
  }

  test("SSTORE that can pay the stipend sentry but not the write still records the slot", UnitTest, VMTest) {
    // A fresh cold slot set non-zero costs COLD_SLOAD 2,100 + STORAGE_WRITE 10,000 = 12,100 of execution gas; the
    // pre-state sentry is max(2,100, CALL_STIPEND + 1 = 2,301) = 2,301. 5,000 covers the sentry, not the write, so it
    // is an out-of-gas halt AFTER the current-value read execution-specs makes (bal_sstore_and_oog).
    val (state, rec) = stateWith(sstoreStack, gas = 5000)
    val out = SSTORE.execute(state)
    out.error shouldBe Some(OutOfGas)
    rec.slots.get(state.ownAddress).map(_.toSet) shouldBe Some(Set(UInt256(1)))
  }

  test("SSTORE below the stipend sentry records nothing", UnitTest, VMTest) {
    val (state, rec) = stateWith(sstoreStack, gas = 2000)
    val out = SSTORE.execute(state)
    out.error shouldBe Some(OutOfGas)
    rec.isEmpty shouldBe true
  }

  test("SELFDESTRUCT on success records the beneficiary", UnitTest, VMTest) {
    // Enough for the 17,000 execution charge AND the GAS_NEW_ACCOUNT (183,600) state charge for the new beneficiary.
    val (state, rec) = stateWith(addressStack, gas = 300000, ownerBalance = UInt256(1))
    SELFDESTRUCT.execute(state).error shouldBe None
    rec.addresses should contain(target)
  }

  test(
    "SELFDESTRUCT that can pay base + cold access but not the account write still records the beneficiary",
    UnitTest,
    VMTest
  ) {
    // With a positive balance sweeping to a dead beneficiary the full charge is SELFDESTRUCT base 5,000 + cold access
    // 3,000 + ACCOUNT_WRITE 9,000 = 17,000; the pre-state part execution-specs checks first is base + cold = 8,000.
    // 10,000 covers that but not the write, so it halts having read the beneficiary.
    val (state, rec) = stateWith(addressStack, gas = 10000, ownerBalance = UInt256(1))
    val out = SELFDESTRUCT.execute(state)
    out.error shouldBe Some(OutOfGas)
    rec.addresses should contain(target)
  }

  test("SELFDESTRUCT below base + cold access records nothing", UnitTest, VMTest) {
    val (state, rec) = stateWith(addressStack, gas = 7000, ownerBalance = UInt256(1))
    val out = SELFDESTRUCT.execute(state)
    out.error shouldBe Some(OutOfGas)
    rec.isEmpty shouldBe true
  }

  test("a recorder is untouched by an opcode that reads no state", UnitTest, VMTest) {
    // ADD: pure stack op, no account or slot access.
    val (state, rec) = stateWith(Stack.empty().push(Seq(UInt256(1), UInt256(2))), gas = 100000)
    ADD.execute(state).error shouldBe None
    rec.isEmpty shouldBe true
  }
