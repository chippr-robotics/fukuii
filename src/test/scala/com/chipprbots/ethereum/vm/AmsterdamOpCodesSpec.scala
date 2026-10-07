package com.chipprbots.ethereum.vm

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex
import org.scalacheck.Gen
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefPostAmsterdam
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefPostPrague
import com.chipprbots.ethereum.domain.Timestamp
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.Config
import com.chipprbots.ethereum.utils.ForkTimestamps
import com.chipprbots.ethereum.vm.MockWorldState.PS

import Fixtures.blockchainConfig

/** The opcodes Amsterdam adds: EIP-7843 SLOTNUM (0x4b) and EIP-8024 DUPN, SWAPN, EXCHANGE (0xe6..0xe8).
  *
  * Reference: execution-specs `forks/amsterdam` `vm/instructions/block.py` `slot_number`, `vm/instructions/stack.py`
  * `dupn`/`swapn`/`exchange` and `vm/stack.py` `decode_single`/`decode_pair`. The EIP-8024 assembly and execution
  * vectors are the EIP's own ("Test Cases"), every expected stack as the EIP states it.
  */
// scalastyle:off magic.number
class AmsterdamOpCodesSpec extends AnyFlatSpec with Matchers:

  private val amsterdam: EvmConfig = EvmConfig
    .BerlinConfigBuilder(blockchainConfig)
    .copy(
      opCodeList = EvmConfig.AmsterdamOpCodes,
      feeSchedule = new FeeSchedule.AmsterdamFeeSchedule,
      amsterdamEnabled = true
    )
  private val osaka: EvmConfig = EvmConfig
    .BerlinConfigBuilder(blockchainConfig)
    .copy(opCodeList = EvmConfig.OsakaOpCodes, feeSchedule = new FeeSchedule.OsakaFeeSchedule)

  private val StartGas = BigInt(1000000)

  private def stateFor(config: EvmConfig, code: ByteString): PS =
    Generators
      .getProgramStateGen(
        evmConfig = config,
        codeGen = Gen.const(code),
        blockNumberGen = Gen.const(UInt256(1000)),
        isTopHeader = true
      )
      .sample
      .get
      .withStack(Stack.empty())
      .copy(gas = StartGas)

  /** Runs `hex` from pc 0 until it halts or runs off its end, as a frame does. */
  private def run(hex: String, config: EvmConfig = amsterdam): PS =
    val state = stateFor(config, ByteString(Hex.decode(hex)))
    state.vm.exec(state)

  private def topFirst(s: PS): Seq[BigInt] = s.stack.toSeq.map(_.toBigInt)

  private def zeros(n: Int): Seq[BigInt] = Seq.fill(n)(BigInt(0))

  // ── EIP-8024 immediates ─────────────────────────────────────────────────────

  // The EIP's reference encoders, to check decoding against.
  private def encodeSingle(n: Int): Int = (n + 111) % 256
  private def encodePair(n: Int, m: Int): Int =
    val (q, r) = if m <= 16 then (n - 1, m - 1) else (29 - m, n - 1)
    (16 * q + r) ^ 143

  "EIP-8024 immediate decoding" should "map the 219 valid DUPN/SWAPN bytes one-to-one onto 17..235" taggedAs (
    UnitTest,
    VMTest
  ) in {
    val valid = (0 to 255).filter(OpCode.isValidSingleImmediate)
    valid shouldBe ((0 to 90) ++ (128 to 255))
    valid.map(OpCode.decodeSingle).sorted shouldBe (17 to 235)
    for x <- valid do encodeSingle(OpCode.decodeSingle(x)) shouldBe x
  }

  it should "map the 210 valid EXCHANGE bytes one-to-one onto pairs 1 <= n < m <= 30 - n" taggedAs (
    UnitTest,
    VMTest
  ) in {
    val valid = (0 to 255).filter(OpCode.isValidPairImmediate)
    valid shouldBe ((0 to 81) ++ (128 to 255))
    val pairs = valid.map(OpCode.decodePair)
    pairs.distinct.size shouldBe 210
    for ((n, m), x) <- pairs.zip(valid) do
      withClue(s"x = $x -> ($n, $m): ") {
        n should be >= 1
        n should be < m
        m should be <= 30 - n
        encodePair(n, m) shouldBe x
      }
  }

  /** The EIP's disassembler rule: an instruction whose immediate is forbidden decodes as one byte (INVALID_*), and the
    * next byte is an instruction of its own.
    */
  private def disassemble(hex: String): Seq[String] =
    val code = Hex.decode(hex).map(_ & 0xff)
    def imm(pc: Int): Int = if pc + 1 < code.length then code(pc + 1) else 0
    def go(pc: Int): List[String] =
      if pc >= code.length then Nil
      else
        code(pc) match
          case 0xe6 if OpCode.isValidSingleImmediate(imm(pc)) => s"DUPN ${OpCode.decodeSingle(imm(pc))}" :: go(pc + 2)
          case 0xe7 if OpCode.isValidSingleImmediate(imm(pc)) => s"SWAPN ${OpCode.decodeSingle(imm(pc))}" :: go(pc + 2)
          case 0xe8 if OpCode.isValidPairImmediate(imm(pc)) =>
            val (n, m) = OpCode.decodePair(imm(pc))
            s"EXCHANGE $n $m" :: go(pc + 2)
          case 0xe6 => "INVALID_DUPN" :: go(pc + 1)
          case 0xe7 => "INVALID_SWAPN" :: go(pc + 1)
          case 0xe8 => "INVALID_EXCHANGE" :: go(pc + 1)
          case 0x5b => "JUMPDEST" :: go(pc + 1)
          case 0x5f => "PUSH0" :: go(pc + 1)
          case 0x52 => "MSTORE" :: go(pc + 1)
          case op if op >= 0x60 && op <= 0x7f =>
            val n = op - 0x5f
            val data = code.slice(pc + 1, pc + 1 + n).map(b => f"$b%02x").mkString
            s"PUSH$n 0x$data" :: go(pc + 1 + n)
          case op => fail(f"unexpected opcode 0x$op%02x")
    go(0)

  "the EIP-8024 assembly vectors" should "disassemble as the EIP lists them" taggedAs (UnitTest, VMTest) in {
    disassemble("e680") shouldBe Seq("DUPN 17")
    disassemble("e7db") shouldBe Seq("SWAPN 108")
    disassemble("e6805b") shouldBe Seq("DUPN 17", "JUMPDEST")
    disassemble("e75b") shouldBe Seq("INVALID_SWAPN", "JUMPDEST")
    disassemble("e6605b") shouldBe Seq("INVALID_DUPN", "PUSH1 0x5b")
    disassemble("e7610000") shouldBe Seq("INVALID_SWAPN", "PUSH2 0x0000")
    disassemble("e65f") shouldBe Seq("INVALID_DUPN", "PUSH0")
    disassemble("e89d") shouldBe Seq("EXCHANGE 2 3")
    disassemble("e82f") shouldBe Seq("EXCHANGE 1 19")
    disassemble("e850") shouldBe Seq("EXCHANGE 14 16")
    disassemble("e851") shouldBe Seq("EXCHANGE 14 15")
    disassemble("e852") shouldBe Seq("INVALID_EXCHANGE", "MSTORE")
  }

  "JUMPDEST analysis" should "be unchanged: an immediate is not skipped, PUSH data still is" taggedAs (
    UnitTest,
    VMTest
  ) in {
    def jumpdests(hex: String): Set[Int] = Program(ByteString(Hex.decode(hex))).validJumpDestinations.toSet
    jumpdests("e6805b") shouldBe Set(2)
    jumpdests("e75b") shouldBe Set(1) // EIP-8024: "then code[i + 1] is a valid jump target"
    jumpdests("e6605b") shouldBe Set.empty // 0x5b is PUSH1's data
    jumpdests("600456e65b") shouldBe Set(4)
  }

  // ── EIP-8024 execution vectors ──────────────────────────────────────────────

  "the EIP-8024 execution vectors" should "leave the stacks the EIP states" taggedAs (UnitTest, VMTest) in {
    // "18 stack items, the top of the stack valued 1, the bottom of the stack valued 1, the rest valued 0"
    val dupn = run("60016000808080808080808080808080808080e680")
    dupn.error shouldBe None
    topFirst(dupn) shouldBe (BigInt(1) +: zeros(16)) :+ BigInt(1)
    StartGas - dupn.gas shouldBe BigInt(54) // 2 PUSH1, 15 DUP1, DUPN: 18 x 3

    // "18 stack items, the top of the stack valued 1, the bottom of the stack valued 2, the rest valued 0"
    val swapn = run("600160008080808080808080808080808080806002e780")
    swapn.error shouldBe None
    topFirst(swapn) shouldBe (BigInt(1) +: zeros(16)) :+ BigInt(2)

    // At the end of code, so the immediate reads 0: EXCHANGE 9 16. "17 stack items, the bottom of the stack valued 1,
    // the 10th stack item from the top valued 2, the rest valued 0"
    val atEnd = run("600260008080808080600160008080808080808080e8")
    atEnd.error shouldBe None
    topFirst(atEnd) shouldBe (zeros(9) :+ BigInt(2)) ++ (zeros(6) :+ BigInt(1))

    // "3 stack items, from top to bottom: [2, 0, 1]"
    val three = run("600060016002e88e")
    three.error shouldBe None
    topFirst(three) shouldBe Seq(BigInt(2), BigInt(0), BigInt(1))

    // "30 stack items, the top of the stack valued 2, the bottom of the stack valued 1, the rest valued 0"
    val deepest = run("600080808080808080808080808080808080808080808080808080808060016002e88f")
    deepest.error shouldBe None
    topFirst(deepest) shouldBe (BigInt(2) +: zeros(28)) :+ BigInt(1)

    // "PUSH 04 JUMP INVALID_DUPN JUMPDEST" executes successfully
    val jump = run("600456e65b")
    jump.error shouldBe None
    topFirst(jump) shouldBe empty

    // "3 stack items, the top of the stack valued 1, the rest valued 0"
    val iszero = run("60008080e88e15")
    iszero.error shouldBe None
    topFirst(iszero) shouldBe Seq(BigInt(1), BigInt(0), BigInt(0))
  }

  it should "halt exceptionally where the EIP says so" taggedAs (UnitTest, VMTest) in {
    run("e75b").error shouldBe Some(InvalidOpCode(0xe7.toByte)) // 0x5b is a forbidden immediate
    run("e852").error shouldBe Some(InvalidOpCode(0xe8.toByte)) // 82 is a forbidden immediate
    // 16 items, one short of DUPN 17.
    run("6000808080808080808080808080808080e680").error shouldBe Some(StackUnderflow)
  }

  "DUPN, SWAPN and EXCHANGE" should "cost 3 and move pc past the immediate" taggedAs (UnitTest, VMTest) in {
    val full = (1 to 40).foldLeft(Stack.empty())((s, i) => s.push(UInt256(i)))
    for (op, imm) <- Seq((DUPN, 0x80), (SWAPN, 0x80), (EXCHANGE, 0x8f)) do
      withClue(s"$op: ") {
        val in = stateFor(amsterdam, ByteString(op.code, imm.toByte)).withStack(full)
        val out = op.execute(in)
        out.error shouldBe None
        in.gas - out.gas shouldBe BigInt(3)
        out.pc shouldBe 2
      }
  }

  it should "halt on a missing stack item, and DUPN on a full stack" taggedAs (UnitTest, VMTest) in {
    // SWAPN 17 needs 18 items; EXCHANGE 1 29 needs 30.
    val seventeen = (1 to 17).foldLeft(Stack.empty())((s, i) => s.push(UInt256(i)))
    SWAPN.execute(stateFor(amsterdam, ByteString(0xe7.toByte, 0x80.toByte)).withStack(seventeen)).error shouldBe
      Some(StackUnderflow)
    val twentyNine = (1 to 29).foldLeft(Stack.empty())((s, i) => s.push(UInt256(i)))
    EXCHANGE.execute(stateFor(amsterdam, ByteString(0xe8.toByte, 0x8f.toByte)).withStack(twentyNine)).error shouldBe
      Some(StackUnderflow)
    val full = (1 to 1024).foldLeft(Stack.empty())((s, i) => s.push(UInt256(i)))
    DUPN.execute(stateFor(amsterdam, ByteString(0xe6.toByte, 0x80.toByte)).withStack(full)).error shouldBe
      Some(StackOverflow)
  }

  "0x4b and 0xe6..0xe8" should "be undefined before Amsterdam" taggedAs (UnitTest, VMTest) in {
    for hex <- Seq("4b", "e680", "e780", "e88f") do
      withClue(s"$hex on Osaka: ") {
        run(hex, osaka).error shouldBe Some(InvalidOpCode(Hex.decode(hex).head))
      }
  }

  // ── EIP-7843 SLOTNUM ────────────────────────────────────────────────────────

  private def headerWithSlot(slot: Option[BigInt]) =
    val zero32 = ByteString(Array.fill[Byte](32)(0))
    val extraFields = slot match
      case Some(s) => HefPostAmsterdam(7, zero32, 0, 0, zero32, zero32, zero32, s)
      case None    => HefPostPrague(7, zero32, 0, 0, zero32, zero32)
    Generators.exampleBlockHeader.copy(extraFields = extraFields)

  private def slotnum(slot: Option[BigInt]): PS =
    val state = stateFor(amsterdam, ByteString(SLOTNUM.code))
    SLOTNUM.execute(state.copy(env = state.env.copy(blockHeader = headerWithSlot(slot))))

  "SLOTNUM" should "push the header's slot number for 2 gas" taggedAs (UnitTest, VMTest) in {
    val out = slotnum(Some(BigInt(123456789)))
    out.error shouldBe None
    topFirst(out) shouldBe Seq(BigInt(123456789))
    StartGas - out.gas shouldBe BigInt(2)
    out.pc shouldBe 1
  }

  it should "push a slot in the top half of uint64 unsigned" taggedAs (UnitTest, VMTest) in {
    val maxUint64 = BigInt(2).pow(64) - 1
    topFirst(slotnum(Some(maxUint64))) shouldBe Seq(maxUint64)
  }

  it should "push 0 under a header without the field, as go-ethereum does" taggedAs (UnitTest, VMTest) in {
    topFirst(slotnum(None)) shouldBe Seq(BigInt(0))
  }

  // ── The timestamp cascade ───────────────────────────────────────────────────

  "EvmConfig.forBlock" should "select the Amsterdam table at the Amsterdam timestamp, and only there" taggedAs (
    UnitTest,
    VMTest,
    ConsensusTest
  ) in {
    val config = Config.blockchains.blockchainConfig.copy(
      forkTimestamps = ForkTimestamps(
        shanghaiTimestamp = Some(1000L),
        cancunTimestamp = Some(2000L),
        pragueTimestamp = Some(3000L),
        osakaTimestamp = Some(4000L),
        amsterdamTimestamp = Some(5000L)
      )
    )
    EvmConfig.forBlock(0, Timestamp(5000L), config).opCodeList shouldBe EvmConfig.AmsterdamOpCodes
    EvmConfig.forBlock(0, Timestamp(4999L), config).opCodeList shouldBe EvmConfig.OsakaOpCodes
    val table = EvmConfig.forBlock(0, Timestamp(5000L), config)
    table.opCodeFor(0x4b.toByte) shouldBe Some(SLOTNUM)
    table.opCodeFor(0xe6.toByte) shouldBe Some(DUPN)
    table.opCodeFor(0xe7.toByte) shouldBe Some(SWAPN)
    table.opCodeFor(0xe8.toByte) shouldBe Some(EXCHANGE)
  }
