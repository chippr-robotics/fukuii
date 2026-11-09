package com.chipprbots.ethereum.vm

import org.apache.pekko.util.ByteString

import org.scalacheck.Gen
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.vm.MockWorldState.PS

import Fixtures.blockchainConfig

/** #1727 PR 7a: a stack error carries go-ethereum's counts.
  *
  * go-ethereum validates the stack before an instruction's gas (core/vm/interpreter.go:204-208):
  * `ErrStackUnderflow{len, minStack}` when `len < minStack`, `ErrStackOverflow{len, maxStack}` when `len > maxStack`,
  * where `minStack = pops` and `maxStack = 1024 + pops - pushes` (jump_table.go). fukuii checks the same bounds from
  * `delta` (pops) and `alpha` (pushes), in `OpCode.execute`, `CreateOp.execute` and `CallOp.execute`. EIP-8024's DUPN,
  * SWAPN and EXCHANGE check the depth their immediate reaches inside the instruction, as go-ethereum's do
  * (instructions.go:1027, 1055, 1088: `n`, `n + 1`, `max(n, m) + 1`).
  *
  * Only the error's fields are new. Each case asserts the whole state the error leaves, which is the state it left
  * before: no gas spent, nothing popped, or for the EIP-8024 instructions their constant gas spent.
  */
// scalastyle:off magic.number
class StackErrorCountsSpec extends AnyFlatSpec with Matchers:

  private val etcOlympia: EvmConfig = EvmConfig.OlympiaConfigBuilder(blockchainConfig)
  private val ethAmsterdam: EvmConfig = EvmConfig
    .BerlinConfigBuilder(blockchainConfig)
    .copy(
      opCodeList = EvmConfig.AmsterdamOpCodes,
      feeSchedule = new FeeSchedule.AmsterdamFeeSchedule,
      amsterdamEnabled = true
    )

  private def stackOf(depth: Int): Stack = (1 to depth).foldLeft(Stack.empty())((s, i) => s.push(UInt256(i)))

  private def stateFor(config: EvmConfig, code: ByteString, depth: Int): PS =
    Generators
      .getProgramStateGen(evmConfig = config, codeGen = Gen.const(code))
      .sample
      .get
      .withStack(stackOf(depth))
      .copy(gas = 1000000)

  private def run(op: OpCode, depth: Int, config: EvmConfig = etcOlympia, immediate: Option[Int] = None): (PS, PS) =
    val in = stateFor(config, ByteString(op.code) ++ ByteString(immediate.map(_.toByte).toArray), depth)
    (in, op.execute(in))

  "Every instruction that pops" should "report (depth <=> pops) one item short, before its gas" taggedAs (
    UnitTest,
    VMTest
  ) in {
    for
      config <- Seq(etcOlympia, ethAmsterdam)
      op <- config.opCodes if op.delta > 0
    do
      withClue(s"$op on ${if config.amsterdamEnabled then "ETH Amsterdam" else "ETC Olympia"}: ") {
        val (in, out) = run(op, op.delta - 1, config)
        out shouldBe in.withError(StackUnderflow(op.delta - 1, op.delta))
      }
  }

  "Every instruction that pushes more than it pops" should "report the limit 1024 + pops - pushes on a full stack" taggedAs (
    UnitTest,
    VMTest
  ) in {
    for
      config <- Seq(etcOlympia, ethAmsterdam)
      op <- config.opCodes if op.alpha > op.delta
    do
      withClue(s"$op on ${if config.amsterdamEnabled then "ETH Amsterdam" else "ETC Olympia"}: ") {
        val (in, out) = run(op, Stack.DefaultMaxSize, config)
        out shouldBe in.withError(StackOverflow(1024, 1024 + op.delta - op.alpha))
      }
  }

  // The numbers go-ethereum's jump table gives these instructions (minStack, maxStack), written out rather than derived
  // from delta and alpha, so the formula itself is pinned.
  "The counts" should "be go-ethereum's for the instructions with their own stack check" taggedAs (
    UnitTest,
    VMTest
  ) in {
    val underflows = Seq(
      ADD -> (1, 2),
      SWAP16 -> (16, 17), // minSwapStack(17)
      DUP16 -> (15, 16), // minDupStack(16)
      LOG4 -> (5, 6),
      CREATE -> (2, 3), // CreateOp.execute
      CREATE2 -> (3, 4),
      CALL -> (6, 7), // CallOp.execute
      CALLCODE -> (6, 7),
      DELEGATECALL -> (5, 6),
      STATICCALL -> (5, 6)
    )
    for (op, (depth, required)) <- underflows do
      withClue(s"$op: ") {
        val (in, out) = run(op, depth)
        out shouldBe in.withError(StackUnderflow(depth, required))
      }
    // maxStack(0, 1), maxDupStack(n) = maxStack(n, n + 1): 1023 for every one
    for op <- Seq(PUSH1, PUSH32, DUP1, DUP16, ADDRESS, GAS) do
      withClue(s"$op: ") {
        val (in, out) = run(op, 1024)
        out shouldBe in.withError(StackOverflow(1024, 1023))
      }
    val (pushIn, pushOut) = run(PUSH0, 1024, ethAmsterdam)
    pushOut shouldBe pushIn.withError(StackOverflow(1024, 1023))
  }

  "DUPN, SWAPN and EXCHANGE" should "report the depth their immediate reaches, after their constant gas" taggedAs (
    UnitTest,
    VMTest
  ) in {
    // immediate 0x80 decodes to n = 17; 0x8f to (n, m) = (1, 29)
    val (dupIn, dupOut) = run(DUPN, 16, ethAmsterdam, Some(0x80))
    dupOut shouldBe dupIn.withError(StackUnderflow(16, 17)).spendGas(3) // instructions.go:1027, n
    val (swapIn, swapOut) = run(SWAPN, 17, ethAmsterdam, Some(0x80))
    swapOut shouldBe swapIn.withError(StackUnderflow(17, 18)).spendGas(3) // instructions.go:1055, n + 1
    val (exIn, exOut) = run(EXCHANGE, 29, ethAmsterdam, Some(0x8f))
    exOut shouldBe exIn.withError(StackUnderflow(29, 30)).spendGas(3) // instructions.go:1088, max(n, m) + 1
    // DUPN's one push is bounded in `execute`, before the gas: maxStack(0, 1)
    val (fullIn, fullOut) = run(DUPN, 1024, ethAmsterdam, Some(0x80))
    fullOut shouldBe fullIn.withError(StackOverflow(1024, 1023))
  }

  "A frame that halts on a stack error" should "consume its gas, as it did" taggedAs (UnitTest, VMTest) in {
    // ADD on an empty stack, then the frame's result
    val in = stateFor(etcOlympia, ByteString(ADD.code), 0)
    val result = in.vm.exec(in).toResult
    result.error shouldBe Some(StackUnderflow(0, 2))
    result.gasRemaining shouldBe 0
    result.returnData shouldBe ByteString.empty
  }
