package com.chipprbots.ethereum.vm

import org.apache.pekko.util.ByteString

import scala.collection.mutable.ArrayBuffer

import org.json4s.JsonAST.JNothing
import org.json4s.JsonAST.JValue
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.Timestamp
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.vm.MockWorldState.PS

/** Spec 021 S0: the opcode-level edge rows O01 to O15 (GT-1 part A §5), FR-010's truncation invariants, and forge's F01
  * (`calcMemCost`) and F02 (`wordsForBytes`).
  *
  * These are pins: every expected value is today's behaviour, captured on the pre-swap tree (`BigInt`-backed `UInt256`,
  * list stack, immutable memory), and they must stay green, unchanged, through S1 to S4. A pin that turns red after a
  * code commit means the code is wrong, not the pin.
  *
  * Chain-neutral rows run on ETH mainnet at Osaka and on ETC at Spiral, each as `forBlock` selects it, with a header at
  * that block.
  */
// scalastyle:off magic.number
class OpCodeEdgeRowsSpec extends AnyFunSuite with Matchers:
  import PinHarness.*

  private def pow2(n: Int): BigInt = BigInt(2).pow(n)
  private def u(n: BigInt): UInt256 = UInt256(n)
  private val Max: UInt256 = UInt256.MaxValue
  private val Zero: UInt256 = UInt256.Zero

  /** `calcMemCost`'s out-of-gas sentinel: a value (structLog prints it as `gasCost`), the exact `BigInt` 2^255 - 1. */
  private val Sentinel: BigInt = pow2(255) - 1

  private val StartGas: BigInt = 1_000_000

  private val (osaka, osakaHeader) = ethOsaka
  private val (cancun, cancunHeader) = ethCancun

  /** Chain-neutral rows: ETH Osaka and ETC Spiral. */
  private val neutral: Seq[(String, EvmConfig, BlockHeader)] = Seq(
    ("ETH Osaka", osaka, osakaHeader),
    ("ETC Spiral", EtcForkMatrix.Spiral.evmConfig, EtcForkMatrix.Spiral.header)
  )

  private def gasUsed(out: PS): BigInt = StartGas - out.gas

  /** 0x01, 0x02, ..., 0x20. */
  private val bytes32: ByteString = ByteString((1 to 32).map(_.toByte).toArray)
  private val zeros32: ByteString = ByteString(new Array[Byte](32))

  /** Records the gas each child frame is handed (`onCallEnter`). */
  final private class CallGasRecorder extends ExecutionTracer:
    val forwarded: ArrayBuffer[BigInt] = ArrayBuffer.empty
    override def onCallEnter(
        opCode: String,
        from: Address,
        to: Address,
        gas: BigInt,
        value: BigInt,
        input: ByteString
    ): Unit = forwarded += gas
    override def getResult: JValue = JNothing

  // ── O01 ────────────────────────────────────────────────────────────────────────────────────────────────────────

  test("O01 CALLDATALOAD zero-pads, and an offset past the data reads 0 however large it is", UnitTest, VMTest) {
    for (chain, config, hdr) <- neutral do
      def load(offset: UInt256): PS =
        CALLDATALOAD.execute(state(config, hdr, operands(offset), inputData = bytes32))
      withClue(s"$chain, offset len-1: ") {
        val out = load(u(31))
        words(out.stack) shouldBe Seq(UInt256(ByteString(32.toByte) ++ ByteString(new Array[Byte](31))))
        gasUsed(out) shouldBe 3
      }
      // 2^32 + 1: a toInt-first rewrite would read from offset 1.
      for offset <- Seq(u(32), u(pow2(31)), u(pow2(32) + 1), u(pow2(64)), u(pow2(255)), Max) do
        withClue(s"$chain, offset ${offset.toHexString}: ") {
          val out = load(offset)
          out.error shouldBe None
          words(out.stack) shouldBe Seq(Zero)
          gasUsed(out) shouldBe 3
        }
  }

  // ── O02 ────────────────────────────────────────────────────────────────────────────────────────────────────────

  test("O02 a copy of size 0 at offsets 2^256-1 expands nothing and costs its base and access gas", UnitTest, VMTest) {
    val eoaWord = UInt256(Eoa.bytes)
    for (chain, config, hdr) <- neutral do
      val cases: Seq[(OpCode, Stack, BigInt)] = Seq(
        (CALLDATACOPY, operands(Max, Max, Zero), 3),
        (CODECOPY, operands(Max, Max, Zero), 3),
        (EXTCODECOPY, operands(eoaWord, Max, Max, Zero), 2600) // a cold account: EIP-2929 on both chains here
      )
      for (op, stack, gas) <- cases do
        withClue(s"$chain, $op: ") {
          val out = op.execute(state(config, hdr, stack, inputData = bytes32, code = bytes32))
          out.error shouldBe None
          out.memory.size shouldBe 0
          words(out.stack) shouldBe empty
          gasUsed(out) shouldBe gas
        }
    // MCOPY exists from ETH Cancun and ETC Olympia (F05 runs it on ETC).
    val out = MCOPY.execute(state(osaka, osakaHeader, operands(Max, Max, Zero)))
    out.error shouldBe None
    out.memory.size shouldBe 0
    gasUsed(out) shouldBe 3
  }

  // ── O03 ────────────────────────────────────────────────────────────────────────────────────────────────────────

  test(
    "O03 RETURNDATACOPY halts when offset + size exceeds the data, the sum saturating, never wrapping",
    UnitTest,
    VMTest
  ) {
    for (chain, config, hdr) <- neutral do
      def copy(memOffset: UInt256, dataOffset: UInt256, size: UInt256): PS =
        RETURNDATACOPY.execute(state(config, hdr, operands(memOffset, dataOffset, size), returnData = bytes32))
      withClue(s"$chain, (0, 2^256-1, 1): ") {
        // A wrapping sum gives 0 <= 32 and would copy.
        val out = copy(Zero, Max, u(1))
        out.error shouldBe Some(ReturnDataOverflow)
        words(out.stack) shouldBe empty
        out.memory.size shouldBe 0
        gasUsed(out) shouldBe 9 // 3 + memory 3 + one copied word 3
      }
      withClue(s"$chain, (0, len + 1, 0): ") {
        val out = copy(Zero, u(33), Zero)
        out.error shouldBe Some(ReturnDataOverflow)
        out.memory.size shouldBe 0
        gasUsed(out) shouldBe 3
      }
      withClue(s"$chain, (0, len, 0): ") {
        val out = copy(Zero, u(32), Zero)
        out.error shouldBe None
        out.memory.size shouldBe 0
        gasUsed(out) shouldBe 3
      }
      withClue(s"$chain, (0, 0, len): ") {
        val out = copy(Zero, Zero, u(32))
        out.error shouldBe None
        out.memory shouldBe memoryOf(bytes32)
        gasUsed(out) shouldBe 9
      }
  }

  // ── O04 ────────────────────────────────────────────────────────────────────────────────────────────────────────

  test("O04 JUMP and JUMPI: 2^32 + d does not alias d, and a jump that fails keeps the stack", UnitTest, VMTest) {
    // A JUMPDEST at 4.
    val code = ByteString(0x00, 0x00, 0x00, 0x00, 0x5b, 0x00)
    for (chain, config, hdr) <- neutral do
      def run(op: OpCode, stack: Stack): PS = op.execute(state(config, hdr, stack, code = code))
      withClue(s"$chain, JUMP 4: ") {
        val out = run(JUMP, operands(u(4)))
        out.error shouldBe None
        out.pc shouldBe 4
        words(out.stack) shouldBe empty
        gasUsed(out) shouldBe 8
      }
      for dest <- Seq(u(3), u(pow2(32) + 4), u(pow2(31)), u(pow2(64) + 4), Max) do
        withClue(s"$chain, JUMP ${dest.toHexString}: ") {
          val out = run(JUMP, operands(dest))
          out.error shouldBe Some(InvalidJump(dest))
          words(out.stack) shouldBe Seq(dest)
          gasUsed(out) shouldBe 8
        }
      withClue(s"$chain, JUMPI (2^256-1, 0): ") {
        val out = run(JUMPI, operands(Max, Zero))
        out.error shouldBe None
        out.pc shouldBe 1
        words(out.stack) shouldBe empty
        gasUsed(out) shouldBe 10
      }
      withClue(s"$chain, JUMPI (4, 2^255): ") {
        val out = run(JUMPI, operands(u(4), u(pow2(255))))
        out.error shouldBe None
        out.pc shouldBe 4
        gasUsed(out) shouldBe 10
      }
      withClue(s"$chain, JUMPI (2^32 + 4, 1): ") {
        val out = run(JUMPI, operands(u(pow2(32) + 4), u(1)))
        out.error shouldBe Some(InvalidJump(u(pow2(32) + 4)))
        words(out.stack) shouldBe Seq(u(pow2(32) + 4), u(1))
        gasUsed(out) shouldBe 10
      }
    // The error's rendering, which structLog and the RPC error carry.
    InvalidJump(u(pow2(32) + 4)).toString shouldBe "InvalidJump(0x0100000004)"
    InvalidJump(Max).toString shouldBe "InvalidJump(0x" + "ff" * 32 + ")"
    InvalidJump(Zero).toString shouldBe "InvalidJump(0x00)"
  }

  // ── O05 ────────────────────────────────────────────────────────────────────────────────────────────────────────

  test("O05 BLOBHASH: an index at or past the hash count pushes 0, 2^32 included", UnitTest, VMTest) {
    val hash = ByteString(0x01.toByte) ++ ByteString(Array.fill[Byte](31)(0xab.toByte))
    def blobhash(index: UInt256): PS =
      BLOBHASH.execute(state(cancun, cancunHeader, operands(index), blobHashes = Seq(hash)))
    val first = blobhash(Zero)
    words(first.stack) shouldBe Seq(UInt256(hash))
    gasUsed(first) shouldBe 3
    for index <- Seq(u(1), u(pow2(31)), u(pow2(32)), u(pow2(64)), Max) do
      withClue(s"index ${index.toHexString}: ") {
        val out = blobhash(index)
        words(out.stack) shouldBe Seq(Zero)
        gasUsed(out) shouldBe 3
      }
  }

  // ── O06 ────────────────────────────────────────────────────────────────────────────────────────────────────────

  test("O06 MCOPY expands to max(src, dst) + size unbounded, and size 0 expands nothing", UnitTest, VMTest) {
    for (dst, src) <- Seq((Zero, Max), (Max, Zero)) do
      withClue(s"dst ${dst.toHexString}, src ${src.toHexString}, size 1: ") {
        val stack = operands(dst, src, u(1))
        val out = MCOPY.execute(state(osaka, osakaHeader, stack, gas = 10_000_000))
        out.error shouldBe Some(OutOfGas)
        out.gas shouldBe 0
        out.stack shouldBe stack
        out.memory.size shouldBe 0
      }
    val out = MCOPY.execute(state(osaka, osakaHeader, operands(Max, Max, Zero)))
    out.error shouldBe None
    out.memory.size shouldBe 0
    gasUsed(out) shouldBe 3
  }

  // ── O07 ────────────────────────────────────────────────────────────────────────────────────────────────────────

  test("O07 memory expansion: priced to 2^31 - 1, the exact sentinel 2^255 - 1 beyond it", UnitTest, VMTest) {
    for (chain, config, hdr) <- neutral do
      withClue(s"$chain: ") {
        config.calcMemCost(0, Zero, u(pow2(31) - 1)) shouldBe BigInt("8796294348800")
        config.calcMemCost(0, Zero, u(pow2(31))) shouldBe Sentinel
        config.calcMemCost(0, Max, u(1)) shouldBe Sentinel
        config.calcMemCost(0, Max, Zero) shouldBe 0
        val ok = MSTORE.execute(state(config, hdr, operands(Zero, Max)))
        ok.error shouldBe None
        ok.memory.size shouldBe 32
        gasUsed(ok) shouldBe 6
        val stack = operands(u(pow2(31) - 32), Max)
        val oog = MSTORE.execute(state(config, hdr, stack))
        oog.error shouldBe Some(OutOfGas)
        oog.stack shouldBe stack
        oog.memory.size shouldBe 0
      }
  }

  // ── O08 ────────────────────────────────────────────────────────────────────────────────────────────────────────

  test("O08 CALL forwards min(request, all but 1/64): 2^64 + 5 is never 5", UnitTest, VMTest) {
    // Post-EIP-150 on both chains. The target is a warm-less existing account: 2,600 cold access, nothing else.
    val cap: BigInt = (StartGas - 2600) - (StartGas - 2600) / 64
    cap shouldBe 981816
    for (chain, config, hdr) <- neutral do
      for (request, forwarded) <- Seq(u(5) -> BigInt(5), u(pow2(63)) -> cap, u(pow2(64) + 5) -> cap, Max -> cap) do
        withClue(s"$chain, request ${request.toHexString}: ") {
          val recorder = new CallGasRecorder
          val stack = operands(request, UInt256(Eoa.bytes), Zero, Zero, Zero, Zero, Zero)
          val out = CALL.execute(state(config, hdr, stack, tracer = Some(recorder)))
          out.error shouldBe None
          recorder.forwarded.toSeq shouldBe Seq(forwarded)
          words(out.stack) shouldBe Seq(UInt256.One)
          gasUsed(out) shouldBe 2600 // the child returns all it was given
        }
  }

  // ── O09 ────────────────────────────────────────────────────────────────────────────────────────────────────────

  test("O09 BLOCKHASH covers the last 256 blocks only", UnitTest, VMTest) {
    val current = BigInt(1000)
    val configs = Seq(
      "ETH" -> EvmConfig.forBlock(current, Timestamp(0L), ethMainnet),
      "ETC" -> EvmConfig.forBlock(current, EtcForkMatrix.blockchainConfig)
    )
    val hashes = PinHarness.world().copy(numberOfHashes = u(current - 1))
    def hashOf(n: BigInt): UInt256 = UInt256(kec256(n.toString.getBytes))
    for (chain, config) <- configs do
      for (n, expected) <- Seq(
          u(current) -> Zero,
          u(current - 1) -> hashOf(current - 1),
          u(current - 256) -> hashOf(current - 256),
          u(current - 257) -> Zero,
          Max -> Zero
        )
      do
        withClue(s"$chain, n ${n.toBigInt}: ") {
          val out = BLOCKHASH.execute(state(config, header(current), operands(n), world = hashes))
          words(out.stack) shouldBe Seq(expected)
          gasUsed(out) shouldBe 20
        }
  }

  // ── O10 ────────────────────────────────────────────────────────────────────────────────────────────────────────

  test("O10 TIMESTAMP pushes the unsigned uint64, at and above 2^63 too", UnitTest, VMTest) {
    for (raw, expected) <- Seq(
        -1L -> (pow2(64) - 1),
        Long.MinValue -> pow2(63),
        Long.MaxValue -> (pow2(63) - 1),
        ethTimestamp("osaka") -> BigInt(ethTimestamp("osaka"))
      )
    do
      withClue(s"raw $raw: ") {
        val out = TIMESTAMP.execute(state(osaka, osakaHeader.copy(unixTimestamp = Timestamp(raw))))
        words(out.stack) shouldBe Seq(u(expected))
        gasUsed(out) shouldBe 2
      }
  }

  // ── O11 ────────────────────────────────────────────────────────────────────────────────────────────────────────

  test(
    "O11 CHAINID pushes the exact chain id, Platåberget's 7091047534 (wider than 32 bits) included",
    UnitTest,
    VMTest
  ) {
    // Each chain's config on a header of that chain (CHAINID reads only the config).
    val cases: Seq[(EvmConfig, BlockHeader, BigInt)] = Seq(
      (EvmConfig.forBlock(1, Timestamp(0L), plataberget), header(1), BigInt(7091047534L)),
      (osaka, osakaHeader, BigInt(1)),
      (sepoliaAmsterdam._1, sepoliaAmsterdam._2, BigInt(11155111)),
      (EtcForkMatrix.Spiral.evmConfig, EtcForkMatrix.Spiral.header, BigInt(61))
    )
    for (config, hdr, chainId) <- cases do
      withClue(s"chain id $chainId: ") {
        val out = CHAINID.execute(state(config, hdr))
        words(out.stack) shouldBe Seq(u(chainId))
        gasUsed(out) shouldBe 2
      }
  }

  // ── O12 ────────────────────────────────────────────────────────────────────────────────────────────────────────

  test("O12 CREATE2 hashes the salt as 32 big-endian bytes", UnitTest, VMTest) {
    val w = PinHarness.world()
    // Captured on the pre-swap tree.
    val expected = Seq(
      Zero -> Address("0x36fd63ce1cb5ee2993f19d1fae4e84d52f6f1595"),
      u(pow2(255)) -> Address("0x513e85e763dde2cd59aa50ab83f64ab43ed2511b"),
      Max -> Address("0x72de00cb667764d6038e360e05d1a7f5ed700950")
    )
    expected.map((salt, _) => w.create2Address(Owner, salt, ByteString.empty)) shouldBe expected.map(_._2)
    // An independent source: EIP-1014's own examples 0 to 6 (sender, salt, init code -> address). Examples 4 and 5
    // have the salt 0xcafebabe, 28 leading zero bytes: the sharpest test of the salt's left padding.
    def hex(s: String): ByteString = ByteString(org.bouncycastle.util.encoders.Hex.decode(s))
    val cafebabe = UInt256(hex("00" * 28 + "cafebabe"))
    val eip1014 = Seq(
      ("00" * 20, Zero, "00", "4d1a2e2bb4f88f0250f26ffff098b0b30b26bf38"),
      ("deadbeef" + "00" * 16, Zero, "00", "b928f69bb1d91cd65274e3c79d8986362984fda3"),
      (
        "deadbeef" + "00" * 16,
        UInt256(hex("000000000000000000000000feed000000000000000000000000000000000000")),
        "00",
        "d04116cdd17bebe565eb2422f2497e06cc1c9833"
      ),
      ("00" * 20, Zero, "deadbeef", "70f2b2914a2a4b783faefb75f459a580616fcb5e"),
      ("00" * 16 + "deadbeef", cafebabe, "deadbeef", "60f3f640a8508fc6a86d45df051962668e1e8ac7"),
      ("00" * 16 + "deadbeef", cafebabe, "deadbeef" * 11, "1d8bfdc5d46dc4f61d6b6115972536ebe6a8854c"),
      ("00" * 20, Zero, "", "e33c0c7f7df4809055c3eba6c09cfe4baf1bd9e0")
    )
    for (sender, salt, initCode, address) <- eip1014 do
      withClue(s"EIP-1014 sender $sender, init code 0x$initCode: ") {
        w.create2Address(Address("0x" + sender), salt, hex(initCode)) shouldBe Address("0x" + address)
      }
    // The opcode, end to end: CREATE2(value 0, offset 0, size 0, salt 2^256 - 1) pushes that address.
    for (chain, config, hdr) <- neutral do
      withClue(s"$chain: ") {
        val out = CREATE2.execute(state(config, hdr, operands(Zero, Zero, Zero, Max)))
        out.error shouldBe None
        words(out.stack) shouldBe Seq(UInt256(expected.last._2.bytes))
      }
  }

  // ── O13 ────────────────────────────────────────────────────────────────────────────────────────────────────────

  test("O13 an address taken from a word is its low 20 bytes", UnitTest, VMTest) {
    Address(u(pow2(160) + BigInt(1, Eoa.bytes.toArray))) shouldBe Eoa
    Address(Max) shouldBe Address("0x" + "ff" * 20)
    Address(u(pow2(255))) shouldBe Address("0x" + "00" * 20)
    for (chain, config, hdr) <- neutral do
      withClue(s"$chain: ") {
        val out = BALANCE.execute(state(config, hdr, operands(u(pow2(160) + BigInt(1, Eoa.bytes.toArray)))))
        words(out.stack) shouldBe Seq(u(1))
        gasUsed(out) shouldBe 2600
      }
  }

  // ── O14 ────────────────────────────────────────────────────────────────────────────────────────────────────────

  test("O14 a transfer debits b + (2^256 - v) mod 2^256 = b - v", UnitTest, VMTest) {
    val to = Address(0xbeef)
    for (b, v) <- Seq(
        (pow2(255) + 7, pow2(255)),
        (pow2(256) - 1, pow2(256) - 1),
        (BigInt(1), BigInt(1)),
        (pow2(256) - 2, pow2(255) + 1),
        (BigInt(5), BigInt(0))
      )
    do
      withClue(s"b $b, v $v: ") {
        val w0 = MockWorldState().saveAccount(Owner, Account.empty().increaseBalance(u(b)))
        val w1 = w0.transfer(Owner, to, u(v))
        w1.getBalance(Owner) shouldBe u(b - v)
        w1.getBalance(to) shouldBe u(v)
      }
  }

  // ── O15 ────────────────────────────────────────────────────────────────────────────────────────────────────────

  test("O15 SHL, SHR and SAR take the shift from the top of the stack", UnitTest, VMTest) {
    val x = u(pow2(255) + 1)
    val cases: Seq[(OpCode, UInt256, UInt256, UInt256)] = Seq(
      (SHL, u(1), x, u(2)),
      (SHR, u(1), x, u(pow2(254))),
      (SAR, u(1), x, u(pow2(255) + pow2(254))),
      (SAR, u(255), u(pow2(255)), Max),
      (SAR, u(256), u(pow2(255)), Max),
      (SAR, u(pow2(64)), u(1), Zero),
      (SAR, Max, Max, Max),
      (SHL, u(256), Max, Zero),
      (SHL, u(255), u(1), u(pow2(255))),
      (SHR, u(pow2(255)), Max, Zero),
      (SHR, u(255), Max, u(1))
    )
    for (chain, config, hdr) <- neutral; (op, shift, value, result) <- cases do
      withClue(s"$chain, $op(shift ${shift.toHexString}, value ${value.toHexString}): ") {
        val out = op.execute(state(config, hdr, operands(shift, value)))
        words(out.stack) shouldBe Seq(result)
        gasUsed(out) shouldBe 3
      }
  }

  // ── FR-010: sliceBytes takes the minimum before it truncates ────────────────────────────────────────────────────

  test("FR-010 sliceBytes bounds the offset and the end before any Int truncation", UnitTest, VMTest) {
    def slice(offset: UInt256, size: Int): ByteString = OpCode.sliceBytes(bytes32, offset, u(size))
    slice(Zero, 32) shouldBe bytes32
    slice(u(31), 32) shouldBe ByteString(32.toByte) ++ ByteString(new Array[Byte](31))
    slice(u(1), 0) shouldBe ByteString.empty
    // At or past the end: zeros, whatever the offset. 2^32 + 1 would read byte 1 on with a truncate-first bound, and the
    // wrapped end of 2^256 - 1 (+ 32 = 31) lies below its start.
    for offset <- Seq(u(32), u(pow2(31)), u(pow2(32) + 1), u(pow2(64)), u(pow2(255)), Max - u(15), Max) do
      withClue(s"offset ${offset.toHexString}: ")(slice(offset, 32) shouldBe zeros32)
    slice(u(pow2(32) + 1), 4) shouldBe ByteString(new Array[Byte](4))
  }

  // ── F01: calcMemCost at its exact values (forge C9, C10) ────────────────────────────────────────────────────────

  test("F01 calcMemCost: every S7 vector at its exact BigInt value, the sentinel included", UnitTest, VMTest) {
    for (chain, config, _) <- neutral do
      withClue(s"$chain: ") {
        config.calcMemCost(0, Max, u(1)) shouldBe Sentinel
        config.calcMemCost(0, Max, Zero) shouldBe 0
        config.calcMemCost(0, u(pow2(64)), u(pow2(64))) shouldBe Sentinel
        // The UInt256 sum wraps to exactly 0 here; the cost must not.
        (u(pow2(31)) + u(pow2(256) - pow2(31))) shouldBe Zero
        config.calcMemCost(0, u(pow2(31)), u(pow2(256) - pow2(31))) shouldBe Sentinel
        config.calcMemCost(0, Zero, u(pow2(31) - 1)) shouldBe BigInt("8796294348800")
        config.calcMemCost(0, u(1), u(pow2(31) - 1)) shouldBe Sentinel
        config.calcMemCost(0, u(pow2(31) - 1), Zero) shouldBe 0
        config.calcMemCost(1024, Zero, u(32)) shouldBe 0 // memNeeded <= memSize
        config.calcMemCost(1024, u(992), u(32)) shouldBe 0
        config.calcMemCost(32, Zero, u(64)) shouldBe 3
        config.calcMemCost(0, Zero, u(32)) shouldBe 3
        config.calcMemCost(0, Zero, u(1)) shouldBe 3
        config.calcMemCost(0, Zero, u(1024 * 1024)) shouldBe BigInt(3 * 32768 + 32768L * 32768 / 512)
      }
  }

  // ── F02: wordsForBytes, exact and unbounded (forge C10, C11) ────────────────────────────────────────────────────

  test("F02 wordsForBytes is exact and unbounded, across the 2^63 boundary", UnitTest, VMTest) {
    val cases: Seq[(BigInt, BigInt)] = Seq(
      BigInt(0) -> BigInt(0),
      BigInt(1) -> BigInt(1),
      BigInt(32) -> BigInt(1),
      BigInt(33) -> BigInt(2),
      (pow2(63) - 1) -> pow2(58),
      pow2(63) -> pow2(58),
      (pow2(64) - 1) -> pow2(59),
      pow2(64) -> pow2(59),
      (pow2(256) - 1) -> pow2(251)
    )
    for (n, words) <- cases do withClue(s"n $n: ")(wordsForBytes(u(n)) shouldBe words)
  }
