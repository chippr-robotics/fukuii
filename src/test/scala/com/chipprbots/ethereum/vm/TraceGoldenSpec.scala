package com.chipprbots.ethereum.vm

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex
import org.json4s.JsonAST.*
import org.json4s.native.JsonMethods.pretty
import org.json4s.native.JsonMethods.render
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.vm.Assembly.*
import com.chipprbots.ethereum.vm.MockWorldState.*

/** Spec 021 S0: the trace goldens (T018). What `debug_trace*` and `trace_*` show a user, captured on the pre-swap tree,
  * over a frozen fixture set that includes the error steps DC-8 and FR-012 care about:
  *   - out of gas, an invalid jump (to 2^32 + d), a stack underflow;
  *   - RETURNDATACOPY's overflow (offset 2^256 - 1);
  *   - CREATE's EIP-3860 initcode limit, and (Amsterdam) its EIP-8037 preflight out of gas;
  *   - the EIP-8037 shortfall on a CALL, and on an SSTORE after `exec` (the path S3's checkpoint restores);
  *   - a CALL whose child halts without REVERT, and one whose child reverts;
  *   - a mixed program: full-range arithmetic, memory, SHA3, storage, LOG, a nested CALL with input and output,
  *     BALANCE.
  *
  * Each fixture runs on ETH mainnet at Osaka and on ETC at Spiral (the Amsterdam ones on Sepolia at Amsterdam), once
  * per format: structLog with memory capture off and on, callTracer, prestateTracer (default and diff mode), and the
  * `trace_*` vmTrace. The frame runs as `StxLedger` drives a tracer: `onTxStart`, the VM with the tracer, `onTxEnd`
  * with the frame's gas used, return data and error string.
  *
  * Goldens: `src/test/resources/trace-goldens/<fixture>.<chain>.json`. S2 renders the structLog stack with
  * `toHexQuantity`, S3 swaps the stack and S4 the memory: none of them may change a byte here.
  */
// scalastyle:off magic.number
class TraceGoldenSpec extends AnyFunSuite with Matchers:
  import PinHarness.{Caller, Eoa, Owner, OwnerBalance}

  private def pow2(n: Int): BigInt = BigInt(2).pow(n)
  private def word(n: BigInt): ByteString = UInt256(n).bytes
  private val Max: BigInt = pow2(256) - 1

  // ── Contracts ────────────────────────────────────────────────────────────────────────────────────────────────────

  private val Returner = Address(0xc003)
  private val Reverter = Address(0xc001)
  private val Thrower = Address(0xc002)

  /** Returns one word. */
  private val returner = Assembly(PUSH32, word(pow2(255) + 0xbeef), PUSH1, 0, MSTORE, PUSH1, 32, PUSH1, 0, RETURN)

  /** Reverts with one word. */
  private val reverter = Assembly(PUSH32, word(Max), PUSH1, 0, MSTORE, PUSH1, 32, PUSH1, 0, REVERT)

  /** Writes memory, then halts exceptionally (no REVERT). */
  private val thrower = Assembly(PUSH1, 0xaa, PUSH1, 0, MSTORE, INVALID)

  /** CALL(gas 0xffff, target, value, inOffset, inSize, outOffset, outSize), operands pushed last-popped first. */
  private def call(
      target: Address,
      value: Int,
      inOffset: Int,
      inSize: Int,
      outOffset: Int,
      outSize: Int
  ): Seq[ByteCode] =
    Seq[ByteCode](PUSH1, outSize, PUSH1, outOffset, PUSH1, inSize, PUSH1, inOffset, PUSH1, value) ++
      Seq[ByteCode](PUSH20, target.bytes, PUSH2, ByteString(0xff, 0xff), CALL)

  private def program(parts: Seq[ByteCode]*): ByteString = Assembly(parts.flatten*).code

  // ── Fixtures ─────────────────────────────────────────────────────────────────────────────────────────────────────

  private val oog = program(
    Seq(PUSH32, word(Max), PUSH1, 0, MSTORE),
    Seq(PUSH32, word(pow2(255)), PUSH1, 32, MSTORE),
    Seq(PUSH32, word(Max), SLOAD, POP),
    Seq(PUSH1, 1, PUSH32, word(pow2(128)), SSTORE), // sets an empty cold slot: more than is left
    Seq(STOP)
  )

  /** JUMPDEST at 7; the jump goes to 2^32 + 7. */
  private val invalidJump = program(Seq(PUSH5, ByteString(0x01, 0x00, 0x00, 0x00, 0x07), JUMP, JUMPDEST, STOP))

  private val stackUnderflow = program(Seq(PUSH1, 1, ADD))

  private val returnDataCopyOverflow = program(
    call(Returner, 0, 0, 0, 0, 32),
    Seq(POP, PUSH1, 1, PUSH32, word(Max), PUSH1, 0, RETURNDATACOPY, STOP)
  )

  /** CREATE of 49,153 bytes: one past EIP-3860's 49,152. */
  private val createInitCodeLimit = program(Seq(PUSH3, ByteString(0x00, 0xc0, 0x01), PUSH1, 0, PUSH1, 0, CREATE, STOP))

  private val callChildHalts = program(
    call(Thrower, 0, 0, 0, 0, 32),
    Seq(PUSH1, 0, MSTORE, PUSH1, 32, PUSH1, 0, RETURN)
  )

  private val callChildReverts = program(
    Seq(PUSH32, word(pow2(255) + 1), PUSH1, 0, MSTORE),
    call(Reverter, 0, 0, 32, 64, 32),
    Seq(RETURNDATASIZE, PUSH1, 0, PUSH1, 96, RETURNDATACOPY),
    Seq(PUSH1, 0, MSTORE, PUSH1, 128, PUSH1, 0, RETURN)
  )

  private def mixed(withMcopy: Boolean): ByteString = program(
    Seq(PUSH32, word(Max), PUSH32, word(pow2(255)), ADD),
    Seq(PUSH32, word(pow2(255)), PUSH1, 3, SAR),
    Seq(PUSH1, 12, PUSH32, word(Max), PUSH32, word(Max), MULMOD),
    Seq(PUSH32, word(Max), PUSH32, word(pow2(255)), SDIV),
    Seq(PUSH1, 255, PUSH1, 2, EXP),
    Seq(PUSH32, word(Max), PUSH1, 0, MSTORE),
    Seq(PUSH1, 0xab, PUSH1, 33, MSTORE8),
    Seq(PUSH1, 32, PUSH1, 0, SHA3),
    Seq(PUSH32, word(Max), PUSH32, word(pow2(255)), SSTORE),
    Seq(PUSH32, word(pow2(255)), SLOAD),
    Seq(PUSH1, 64, PUSH1, 0, PUSH1, 64, CALLDATACOPY),
    if withMcopy then Seq(PUSH1, 32, PUSH1, 0, PUSH1, 128, MCOPY) else Seq.empty,
    Seq(PUSH32, word(Max), PUSH1, 64, PUSH1, 0, LOG1),
    call(Returner, 0, 0, 64, 160, 32),
    Seq(PUSH20, Eoa.bytes, BALANCE),
    Seq(PUSH1, 192, PUSH1, 0, RETURN)
  )

  private val createPreflightOog = program(Seq(PUSH1, 0, PUSH1, 0, PUSH1, 0, CREATE, STOP))

  /** A value-bearing CALL to a dead account: EIP-8037's account-creation charge cannot be met. */
  private val callStateGasShortfall = program(call(Address(0xdead), 1, 0, 0, 0, 0), Seq(STOP))

  /** An SSTORE creating a slot: EIP-8037's storage-set charge cannot be met, after `exec`. */
  private val sstoreStateGasShortfall = program(Seq(PUSH1, 1, PUSH1, 7, SSTORE, STOP))

  private val input64: ByteString = ByteString((0 until 64).map(i => (0x40 + i).toByte).toArray)

  private def worldFor(code: ByteString): MockWorldState =
    PinHarness
      .world(code)
      .saveAccount(Owner, Account(nonce = UInt256(128), balance = OwnerBalance))
      .saveAccount(Caller, Account(nonce = UInt256(pow2(32) + 1), balance = OwnerBalance))
      .saveStorage(Owner, MockStorage.Empty.store(Max, pow2(255)).store(pow2(255), 1))
      .saveAccount(Returner, Account.empty())
      .saveCode(Returner, returner.code)
      .saveAccount(Reverter, Account.empty())
      .saveCode(Reverter, reverter.code)
      .saveAccount(Thrower, Account.empty())
      .saveCode(Thrower, thrower.code)

  final private case class Fixture(
      name: String,
      chain: String,
      config: EvmConfig,
      header: BlockHeader,
      code: ByteString,
      gas: BigInt,
      input: ByteString = ByteString.empty
  ):
    val world: MockWorldState = worldFor(code)
    val file: String = s"trace-goldens/$name.$chain.json"

  private val (osaka, osakaHeader) = PinHarness.ethOsaka
  private val (amsterdam, amsterdamHeader) = PinHarness.sepoliaAmsterdam
  private val spiral = EtcForkMatrix.Spiral

  private val chains: Seq[(String, EvmConfig, BlockHeader, Boolean)] = Seq(
    ("eth-osaka", osaka, osakaHeader, true),
    ("etc-spiral", spiral.evmConfig, spiral.header, false)
  )

  private val fixtures: Seq[Fixture] =
    chains.flatMap { (chain, config, hdr, hasMcopy) =>
      Seq(
        Fixture("oog", chain, config, hdr, oog, gas = 20_000),
        Fixture("invalid-jump", chain, config, hdr, invalidJump, gas = 100_000),
        Fixture("stack-underflow", chain, config, hdr, stackUnderflow, gas = 100_000),
        Fixture("returndatacopy-overflow", chain, config, hdr, returnDataCopyOverflow, gas = 200_000),
        Fixture("create-initcode-limit", chain, config, hdr, createInitCodeLimit, gas = 1_000_000),
        Fixture("call-child-halts", chain, config, hdr, callChildHalts, gas = 200_000),
        Fixture("call-child-reverts", chain, config, hdr, callChildReverts, gas = 200_000),
        Fixture("mixed", chain, config, hdr, mixed(hasMcopy), gas = 1_000_000, input = input64)
      )
    } ++ Seq(
      Fixture("create-preflight-oog", "eth-amsterdam", amsterdam, amsterdamHeader, createPreflightOog, gas = 100_000),
      Fixture("call-state-gas-shortfall", "eth-amsterdam", amsterdam, amsterdamHeader, callStateGasShortfall, 100_000),
      Fixture(
        "sstore-state-gas-shortfall",
        "eth-amsterdam",
        amsterdam,
        amsterdamHeader,
        sstoreStateGasShortfall,
        60_000
      ),
      Fixture("mixed", "eth-amsterdam", amsterdam, amsterdamHeader, mixed(withMcopy = true), 1_000_000, input64)
    )

  // ── Running a fixture under a tracer ─────────────────────────────────────────────────────────────────────────────

  /** One top-level frame of `Owner`, driven as StxLedger drives a tracer. */
  private def run(f: Fixture, tracer: ExecutionTracer): PR =
    val context: PC = ProgramContext(
      callerAddr = Caller,
      originAddr = Caller,
      recipientAddr = Some(Owner),
      gasPrice = UInt256.One,
      startGas = f.gas,
      inputData = f.input,
      value = UInt256.Zero,
      endowment = UInt256.Zero,
      doTransfer = true,
      blockHeader = f.header,
      callDepth = 0,
      world = f.world,
      initialAddressesToDelete = Set(),
      evmConfig = f.config,
      originalWorld = f.world,
      warmAddresses = Set.empty,
      warmStorage = Set.empty
    )
    tracer.onTxStart(Caller, Some(Owner), f.gas, 0, f.input)
    val result = new VM[MockWorldState, MockStorage](Some(tracer)).run(context)
    tracer.onTxEnd(f.gas - result.gasRemaining, result.returnData, result.error.map(_.toString))
    result

  private def traced(f: Fixture): JValue =
    val structLog = new StructLogTracer()
    val structLogMemory = new StructLogTracer(enableMemory = true)
    val callTracer = new CallTracer()
    val prestate = new PrestateTracer[MockWorldState, MockStorage](f.world)
    val prestateDiff = new PrestateTracer[MockWorldState, MockStorage](f.world, diffMode = true)
    val vmTrace = new VmTracer()
    val result = run(f, structLog)
    Seq(structLogMemory, callTracer, prestate, vmTrace).foreach(run(f, _))
    prestateDiff.setPostWorld(run(f, prestateDiff).world)
    JObject(
      "fixture" -> JString(f.name),
      "chain" -> JString(f.chain),
      "code" -> JString("0x" + Hex.toHexString(f.code.toArray)),
      "result" -> JObject(
        "error" -> result.error.fold[JValue](JNull)(e => JString(e.toString)),
        "gasRemaining" -> JInt(result.gasRemaining),
        "returnData" -> JString("0x" + Hex.toHexString(result.returnData.toArray))
      ),
      "structLog" -> structLog.getResult,
      "structLogWithMemory" -> structLogMemory.getResult,
      "callTracer" -> callTracer.getResult,
      "prestateTracer" -> prestate.getResult,
      "prestateTracerDiff" -> prestateDiff.getResult,
      "vmTrace" -> vmTrace.getResult
    )

  // ── The goldens ──────────────────────────────────────────────────────────────────────────────────────────────────

  test("the fixtures reach the error steps they are named for", UnitTest, VMTest) {
    def errorOf(name: String, chain: String): Option[String] =
      fixtures
        .find(f => f.name == name && f.chain == chain)
        .map(f => run(f, new CallTracer()).error.map(_.toString))
        .get
    for (chain, _, _, _) <- chains do
      withClue(s"$chain: ") {
        errorOf("oog", chain) shouldBe Some("OutOfGas")
        errorOf("invalid-jump", chain) shouldBe Some("InvalidJump(0x0100000007)")
        errorOf("stack-underflow", chain) shouldBe Some("StackUnderflow")
        errorOf("returndatacopy-overflow", chain) shouldBe Some("ReturnDataOverflow")
        errorOf("create-initcode-limit", chain) shouldBe Some("InitCodeSizeLimit")
        errorOf("call-child-halts", chain) shouldBe None
        errorOf("call-child-reverts", chain) shouldBe None
        errorOf("mixed", chain) shouldBe None
      }
    errorOf("create-preflight-oog", "eth-amsterdam") shouldBe Some("OutOfGas")
    errorOf("call-state-gas-shortfall", "eth-amsterdam") shouldBe Some("OutOfGas")
    errorOf("sstore-state-gas-shortfall", "eth-amsterdam") shouldBe Some("OutOfGas")
    errorOf("mixed", "eth-amsterdam") shouldBe None
  }

  /** Pretty JSON without trailing whitespace (json4s indents an empty object's blank line), so that no editor or hook
    * that trims whitespace can change a golden. Insignificant in JSON: no string value here spans lines.
    */
  private def rendered(json: JValue): String =
    pretty(render(json)).linesIterator.map(_.stripTrailing).mkString("", "\n", "\n")

  for f <- fixtures do
    test(s"trace golden: ${f.name} on ${f.chain}", UnitTest, VMTest) {
      PinHarness.assertGolden(f.file, rendered(traced(f)))
    }
