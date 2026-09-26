package com.chipprbots.ethereum.vm

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.GasAmount
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.ledger.AmsterdamFixtureVectors
import com.chipprbots.ethereum.ledger.InMemoryWorldStateProxy
import com.chipprbots.ethereum.ledger.VMImpl
import com.chipprbots.ethereum.testing.Tags.*

/** **R-1 made testable.**
  *
  * Every transaction in the reference fixture has a gas limit below `TX_MAX_GAS_LIMIT = 2^24` — the largest anywhere in
  * the chain is 1,628,065 — so `state_gas_reservoir` is 0 in all 612 of them. The fixture therefore cannot distinguish
  * a correct reservoir implementation from one that never seeds it at all, and "fixture green" says nothing about any
  * of the behaviour below.
  *
  * These are hand-written vectors, not extracted ones. They are weaker evidence than a measured figure and are labelled
  * as such: each one states what it establishes and, where the EIP's rule is not observable end to end, says so rather
  * than implying coverage it does not have.
  *
  * What remains UNCOVERED even here, recorded so the gap is not mistaken for closed:
  *   - the reservoir being *necessary* — `gas_left` exhausted while the reservoir funds a state charge. Reaching it
  *     requires an intrinsic cost near 2^24, i.e. roughly a megabyte of calldata, since `gas_left` is
  *     `min(TX_MAX_GAS_LIMIT - intrinsic, evm_gas)`.
  *   - `state_gas_committed` and the EIP-7702 interaction that is its only consumer.
  *   - `STORAGE_CLEAR_REFUND` 11,616 in a block-accounting context (EIP-7778's no-refund rule).
  */
// scalastyle:off magic.number
class AmsterdamStateGasReservoirSpec extends AnyFlatSpec with Matchers with AmsterdamFixtureVectors:

  private val TxMaxGasLimit: BigInt = AmsterdamGas.TxMaxGasLimit // 16,777,216

  private def contextFor(
      gasLimit: BigInt,
      world: InMemoryWorldStateProxy,
      to: Option[Address],
      value: BigInt,
      payload: ByteString = ByteString.empty
  ): ProgramContext[InMemoryWorldStateProxy, com.chipprbots.ethereum.ledger.InMemoryWorldStateProxyStorage] =
    val header = amsterdamHeader(500, 5000).copy(gasLimit = GasAmount(60_000_000))
    val stx = dynamicFeeTx(to, value, gasLimit, payload = payload, config = amsterdamConfig)
    val evmConfig = EvmConfig.forBlock(header.number.value, header.unixTimestamp, amsterdamConfig)
    ProgramContext(stx, header, senderAddress, world, evmConfig)

  // ── The split ─────────────────────────────────────────────────────────────

  "the reservoir split" should "stay empty below TX_MAX_GAS_LIMIT — R-1's condition" taggedAs (VMTest) in {
    // 1,628,065 is the largest gas limit anywhere in the reference chain. This is the case the fixture
    // exercises, 612 times, and the ONLY case it exercises.
    val ctx = contextFor(BigInt(1628065), block45World, Some(Emit), value = 0)
    ctx.stateGasReservoir shouldBe BigInt(0)
    ctx.startGas shouldBe BigInt(1628065) - 15000 // TX_BASE_COST + COLD_ACCOUNT_ACCESS, no value
  }

  it should "seed the excess above TX_MAX_GAS_LIMIT into the reservoir, not into gas_left" taggedAs (VMTest) in {
    val reservoirWanted = AmsterdamGas.GasStorageSet // 97,920
    val gasLimit = TxMaxGasLimit + reservoirWanted
    val ctx = contextFor(gasLimit, block45World, Some(Emit), value = 0)

    val intrinsic = BigInt(15000)
    // gas_left = min(TX_MAX_GAS_LIMIT - intrinsic, evm_gas); the reservoir is the remainder.
    ctx.startGas shouldBe TxMaxGasLimit - intrinsic
    ctx.stateGasReservoir shouldBe reservoirWanted
    (ctx.startGas + ctx.stateGasReservoir) shouldBe (gasLimit - intrinsic)
  }

  it should "put nothing in the reservoir exactly AT the threshold" taggedAs (VMTest) in {
    // The boundary, asserted rather than assumed: tx.gas == TX_MAX_GAS_LIMIT gives an empty reservoir.
    val ctx = contextFor(TxMaxGasLimit, block45World, Some(Emit), value = 0)
    ctx.stateGasReservoir shouldBe BigInt(0)
    ctx.startGas shouldBe TxMaxGasLimit - 15000
  }

  it should "seed nothing at all when the fork is not active" taggedAs (VMTest) in {
    // The ETC/pre-Amsterdam guarantee: no split, `startGas` keeps its previous meaning exactly.
    val header = preAmsterdamHeader(500, 300).copy(gasLimit = GasAmount(60_000_000))
    val gasLimit = TxMaxGasLimit + BigInt(500000)
    val stx = dynamicFeeTx(Some(Emit), value = 0, gasLimit = gasLimit, config = preAmsterdamConfig)
    val evmConfig = EvmConfig.forBlock(header.number.value, header.unixTimestamp, preAmsterdamConfig)
    val ctx = ProgramContext(stx, header, senderAddress, block45World, evmConfig)

    ctx.stateGasReservoir shouldBe BigInt(0)
    ctx.startGas shouldBe gasLimit - 21000 // the undecomposed flat intrinsic
  }

  // ── The counter algebra ───────────────────────────────────────────────────
  //
  // These drive ProgramState's state-gas methods directly. They are the only place the LIFO ordering and
  // the merge step can be pinned down precisely, because end to end those operations are invisible
  // whenever nothing runs out of gas.

  private def frame(reservoir: BigInt, gas: BigInt) =
    val stx = dynamicFeeTx(Some(Emit), value = 0, gasLimit = 100000, config = amsterdamConfig)
    val header = amsterdamHeader(500, 5000)
    val evmConfig = EvmConfig.forBlock(header.number.value, header.unixTimestamp, amsterdamConfig)
    val ctx = ProgramContext(stx, header, senderAddress, block45World, evmConfig)
      // The baseline override has to move with the reservoir. A real top-level context takes it from the
      // reservoir it was built with; overriding one without the other would leave the frame with a
      // baseline of 0 and make every rollback assertion below measure the wrong thing.
      .copy(stateGasReservoir = reservoir, startGas = gas, stateGasBaselineOverride = Some(reservoir))
    ProgramState(new VMImpl, ctx, ExecEnv(ctx, EmitCode, Emit)).copy(gas = gas)

  "a state charge" should "draw from the reservoir first and leave gas_left untouched" taggedAs (VMTest) in {
    val charged = frame(reservoir = 100000, gas = 50000).chargeStateGas(97920)
    charged.stateGasReservoir shouldBe BigInt(2080)
    charged.gas shouldBe BigInt(50000) // NOT reduced — the whole charge fitted in the reservoir
    charged.evmStateGasUsed shouldBe BigInt(97920)
    charged.stateGasFromGasLeft shouldBe BigInt(0)
  }

  it should "spill into gas_left only once the reservoir is exhausted" taggedAs (VMTest) in {
    val charged = frame(reservoir = 20000, gas = 50000).chargeStateGas(97920)
    charged.stateGasReservoir shouldBe BigInt(0)
    charged.gas shouldBe BigInt(50000 - (97920 - 20000))
    charged.stateGasFromGasLeft shouldBe BigInt(77920)
    charged.evmStateGasUsed shouldBe BigInt(97920)
  }

  "a refill" should "credit gas_left FIRST and the reservoir only with the remainder (LIFO)" taggedAs (VMTest) in {
    // The ordering is the whole rule: charges take reservoir-then-gas, so refills must give back
    // gas-then-reservoir. Reversed, a frame that charged from gas_left and refilled would silently
    // convert spendable gas into reservoir it can no longer spend on execution.
    val charged = frame(reservoir = 20000, gas = 50000).chargeStateGas(97920)
    val refilled = charged.refillStateGas(97920)

    refilled.gas shouldBe BigInt(50000) // all 77,920 came back to gas_left first
    refilled.stateGasFromGasLeft shouldBe BigInt(0)
    refilled.stateGasReservoir shouldBe BigInt(20000) // then the remaining 20,000 to the reservoir
    refilled.evmStateGasUsed shouldBe BigInt(0)
  }

  it should "send a refill with no outstanding gas_left draw entirely to the reservoir" taggedAs (VMTest) in {
    // The cross-frame case: a frame may clear a slot an EARLIER frame allocated, so the refill has no
    // matching local charge. EIP-8037 sends it to the reservoir, and the merge step moves it up later.
    val refilled = frame(reservoir = 1000, gas = 50000).refillStateGas(97920)
    refilled.gas shouldBe BigInt(50000)
    refilled.stateGasReservoir shouldBe BigInt(1000 + 97920)
    refilled.evmStateGasUsed shouldBe BigInt(-97920)
  }

  "a rollback" should "restore to the baseline rather than refilling charge by charge" taggedAs (VMTest) in {
    val base = frame(reservoir = 20000, gas = 50000)
    val charged = base.chargeStateGas(97920)
    val restored = charged.restoreStateGasToBaseline

    restored.stateGasReservoir shouldBe BigInt(20000)
    restored.gas shouldBe BigInt(50000)
    restored.stateGasFromGasLeft shouldBe BigInt(0)
    restored.evmStateGasUsed shouldBe BigInt(0)
  }

  it should "INCREASE evmStateGasUsed for a frame that refilled more than it charged" taggedAs (VMTest) in {
    // The counter-intuitive half of EIP-8037's restore, and the reason it is not a loop of refills:
    // `net_state_gas` goes negative, and the rollback correctly restores the state whose removal the
    // refill had credited.
    val base = frame(reservoir = 20000, gas = 50000)
    val refilled = base.refillStateGas(50000) // a slot allocated by an earlier frame, cleared here
    refilled.evmStateGasUsed shouldBe BigInt(-50000)

    val restored = refilled.restoreStateGasToBaseline
    restored.evmStateGasUsed shouldBe BigInt(0) // the clear is undone, so the charge stands again
    restored.stateGasReservoir shouldBe BigInt(20000)
  }

  "the successful-child merge" should "move state gas back to gas_left up to the outstanding draw" taggedAs (
    VMTest
  ) in {
    // Parent funded 30,000 of state gas out of gas_left; the child hands back a reservoir of 50,000.
    // The merge must return 30,000 of that to gas_left — otherwise it is stranded as reservoir the
    // frame can never spend on execution, and the sender is over-charged at settlement.
    val parent = frame(reservoir = 0, gas = 40000).copy(stateGasFromGasLeft = 30000, evmStateGasUsed = 30000)
    val merged = parent.mergeSuccessfulChildStateGas(
      childReservoir = 50000,
      childEvmStateGasUsed = 30000,
      childStateGasFromGasLeft = 0
    )

    merged.gas shouldBe BigInt(70000)
    merged.stateGasReservoir shouldBe BigInt(20000)
    merged.stateGasFromGasLeft shouldBe BigInt(0)
    merged.evmStateGasUsed shouldBe BigInt(30000) // a merge moves gas; it never changes the total
  }

  it should "add the child's own outstanding draw to the parent's before moving anything" taggedAs (VMTest) in {
    val parent = frame(reservoir = 0, gas = 40000).copy(stateGasFromGasLeft = 10000, evmStateGasUsed = 10000)
    val merged = parent.mergeSuccessfulChildStateGas(
      childReservoir = 5000,
      childEvmStateGasUsed = 25000,
      childStateGasFromGasLeft = 15000
    )

    // outstanding = 10,000 + 15,000 = 25,000; reservoir 5,000; so d = 5,000.
    merged.gas shouldBe BigInt(45000)
    merged.stateGasReservoir shouldBe BigInt(0)
    merged.stateGasFromGasLeft shouldBe BigInt(20000)
    merged.evmStateGasUsed shouldBe BigInt(25000)
  }

  // ── End to end, above the threshold ───────────────────────────────────────

  "a transaction above TX_MAX_GAS_LIMIT" should "fund its state charge from the reservoir" taggedAs (
    VMTest,
    ConsensusTest
  ) in {
    // The same `emit` transaction as V2, but funded with exactly GAS_STORAGE_SET of reservoir on top of
    // the execution budget. The total must be identical to the below-threshold successful case (144,888):
    // the reservoir changes WHERE the state gas comes from, never HOW MUCH is charged.
    val payload = ByteString(Hex.decode("580abd8a903ed7d3656d6974"))
    val gasLimit = TxMaxGasLimit + AmsterdamGas.GasStorageSet
    val header = amsterdamHeader(500, 5000).copy(gasLimit = GasAmount(60_000_000))
    val stx = legacyTx(Some(Emit), value = 2, gasLimit = gasLimit, payload = payload, config = amsterdamConfig)
    val result = execute(stx, header, block45World, amsterdamConfig)

    result.vmError shouldBe None
    result.gasUsed shouldBe BigInt(144888)
    result.stateGasUsed shouldBe AmsterdamGas.GasStorageSet
    result.worldState.getStorage(Emit).load(UInt256(0)) shouldBe BigInt(9)
  }

  it should "pass the reservoir to a child frame IN FULL, not at 63/64" taggedAs (VMTest, ConsensusTest) in {
    // The sharpest reservoir vector available without a megabyte of calldata.
    //
    // The caller forwards 25,876 of gas_left to `emit`, whose execution costs exactly 25,776 — a margin of
    // 100. The child's fresh SSTORE then needs GAS_STORAGE_SET of state gas, and the reservoir holds
    // exactly that.
    //
    // Forward the reservoir in full and the charge is met entirely from it: the child finishes with 100
    // gas to spare and the counter reaches 9. Forward it at 63/64 — the rule that governs gas_left — and
    // the child would receive 96,390, be 1,530 short, draw that from its 25,876 of gas_left, and run out
    // before finishing. The two are distinguishable, which is the point.
    val childGas = BigInt(25876)
    val caller = Address(0x5151)
    val parentCode = ByteString(
      Array[Byte](0x60, 0x00) ++ // retSize
        Array[Byte](0x60, 0x00) ++ // retOffset
        Array[Byte](0x60, 0x0c) ++ // argsSize = 12
        Array[Byte](0x60, 0x00) ++ // argsOffset
        Array[Byte](0x60, 0x00) ++ // value = 0
        Array[Byte](0x73) ++ Emit.bytes.toArray ++ // PUSH20 emit
        Array[Byte](0x61, ((childGas >> 8).toInt & 0xff).toByte, (childGas.toInt & 0xff).toByte) ++
        Array[Byte](0xf1.toByte, 0x00) // CALL; STOP
    )

    val world = worldWithParent(caller, parentCode)
    val gasLimit = TxMaxGasLimit + AmsterdamGas.GasStorageSet
    val header = amsterdamHeader(500, 5000).copy(gasLimit = GasAmount(60_000_000))
    val stx = legacyTx(Some(caller), value = 0, gasLimit = gasLimit, config = amsterdamConfig)
    val result = execute(stx, header, world, amsterdamConfig)

    result.vmError shouldBe None
    // The child completed: it wrote the hash-keyed slot AND bumped the counter.
    result.worldState.getStorage(Emit).load(UInt256(0)) shouldBe BigInt(9)
    result.stateGasUsed shouldBe AmsterdamGas.GasStorageSet
  }

  private def worldWithParent(addr: Address, code: ByteString): InMemoryWorldStateProxy =
    import com.chipprbots.ethereum.crypto.kec256
    import com.chipprbots.ethereum.domain.Account
    import com.chipprbots.ethereum.domain.CodeHash
    block45World
      .saveAccount(addr, Account(nonce = UInt256(0), balance = UInt256(0), codeHash = CodeHash(kec256(code))))
      .saveCode(addr, code)
