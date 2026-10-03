package com.chipprbots.ethereum.vm

import org.scalacheck.Gen
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.vm.MockWorldState.PS

import Fixtures.blockchainConfig

/** EIP-8038's code-read surcharge: from Amsterdam, EXTCODESIZE and EXTCODECOPY pay one WARM_ACCESS (100) on top of
  * their cold (3,000) or warm (100) account access, for the second database read that loads the code.
  *
  * Reference: execution-specs `forks/amsterdam` `vm/instructions/environment.py` — `extcodesize` and `extcodecopy` add
  * `GasCosts.WARM_ACCESS` ("Code reading cost (EIP-8038)") and `extcodehash` does not; EIP-8038's own table agrees. The
  * CALL family pays nothing extra (`system.py`). Every figure below is the literal gas the opcode charges, not a
  * formula restated.
  */
// scalastyle:off magic.number
class AmsterdamCodeReadSurchargeSpec extends AnyFunSuite with Matchers:

  // Block 1,000 is past Fixtures' Berlin (700), so EIP-2929 cold/warm pricing applies.
  private val osaka: EvmConfig =
    EvmConfig.BerlinConfigBuilder(blockchainConfig).copy(feeSchedule = new FeeSchedule.OsakaFeeSchedule)
  private val amsterdam: EvmConfig =
    osaka.copy(feeSchedule = new FeeSchedule.AmsterdamFeeSchedule, amsterdamEnabled = true)

  private val target = Address(0xdead)

  private def stateWith(config: EvmConfig, stack: Stack, warm: Boolean): PS =
    val s = Generators
      .getProgramStateGen(evmConfig = config, blockNumberGen = Gen.const(UInt256(1000)), isTopHeader = true)
      .sample
      .get
      .withStack(stack)
      .copy(gas = 100000)
    if warm then s.addAccessedAddress(target) else s

  private def gasCharged(op: OpCode, config: EvmConfig, stack: Stack, warm: Boolean): BigInt =
    val in = stateWith(config, stack, warm)
    in.accessedAddresses.contains(target) shouldBe warm
    val out = op.execute(in)
    out.error shouldBe None
    in.gas - out.gas

  private val addressOnly = Stack.empty().push(target.toUInt256)

  /** EXTCODECOPY(target, memOffset 0, codeOffset 0, size). */
  private def copyStack(size: Int) =
    Stack.empty().push(Seq(UInt256(size), UInt256.Zero, UInt256.Zero, target.toUInt256))

  test("EXTCODESIZE pays the access plus 100 at Amsterdam: 3,100 cold, 200 warm", UnitTest, VMTest) {
    gasCharged(EXTCODESIZE, amsterdam, addressOnly, warm = false) shouldBe BigInt(3100)
    gasCharged(EXTCODESIZE, amsterdam, addressOnly, warm = true) shouldBe BigInt(200)
  }

  test("EXTCODECOPY pays the access plus 100 at Amsterdam, then copy and memory as before", UnitTest, VMTest) {
    gasCharged(EXTCODECOPY, amsterdam, copyStack(0), warm = false) shouldBe BigInt(3100)
    gasCharged(EXTCODECOPY, amsterdam, copyStack(0), warm = true) shouldBe BigInt(200)
    // One word: G_copy 3 + one word of fresh memory 3.
    gasCharged(EXTCODECOPY, amsterdam, copyStack(32), warm = false) shouldBe BigInt(3106)
    gasCharged(EXTCODECOPY, amsterdam, copyStack(32), warm = true) shouldBe BigInt(206)
  }

  test("EXTCODEHASH and BALANCE read only the account: no surcharge at Amsterdam", UnitTest, VMTest) {
    for op <- Seq(EXTCODEHASH, BALANCE) do
      withClue(s"$op: ") {
        gasCharged(op, amsterdam, addressOnly, warm = false) shouldBe BigInt(3000)
        gasCharged(op, amsterdam, addressOnly, warm = true) shouldBe BigInt(100)
      }
  }

  test("before Amsterdam neither opcode pays it: 2,600 cold, 100 warm on Osaka", UnitTest, VMTest) {
    gasCharged(EXTCODESIZE, osaka, addressOnly, warm = false) shouldBe BigInt(2600)
    gasCharged(EXTCODESIZE, osaka, addressOnly, warm = true) shouldBe BigInt(100)
    gasCharged(EXTCODECOPY, osaka, copyStack(0), warm = false) shouldBe BigInt(2600)
    gasCharged(EXTCODECOPY, osaka, copyStack(0), warm = true) shouldBe BigInt(100)
  }

  test("the surcharge is checked before the code is read: one gas short is an out-of-gas halt", UnitTest, VMTest) {
    // execution-specs charges the whole access cost, surcharge included, before `get_code`; fukuii's `execute`
    // compares the full charge with the gas left before `exec` reads the code. 3,099 therefore halts.
    val in = stateWith(amsterdam, addressOnly, warm = false).copy(gas = 3099)
    val out = EXTCODESIZE.execute(in)
    out.error shouldBe Some(OutOfGas)
    out.gas shouldBe BigInt(0)
  }
