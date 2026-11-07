package com.chipprbots.ethereum.vm

import org.bouncycastle.util.encoders.Hex
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.ArchiveNodeStorage
import com.chipprbots.ethereum.db.storage.NodeStorage
import com.chipprbots.ethereum.db.storage.SerializingMptStorage
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.EthereumUInt256Mpt
import com.chipprbots.ethereum.domain.StorageKey
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.vm.MockWorldState.PS

/** Spec 021 S0: forge's ETC rows F03 to F05, on the ETC chain config as shipped, by block number (EtcForkMatrix).
  *
  *   - F03 (C12): SSTORE below Phoenix (the Frontier rule), at Phoenix (EIP-2200) and at Magneto (EIP-2929, cold and
  *     warm), with keys and values that touch the sign bit: gas, refund and the storage root, as a golden table.
  *   - F04 (C13): pre-EIP-150 CALL, CALLCODE and DELEGATECALL with a request of 2^64 + 5 or 2^256 - 1: out of gas in
  *     `calcGas`, the stack unchanged, the full request charged (`gasCap` returns the raw request when
  *     `subGasCapDivisor` is `None`).
  *   - F05 (C15): the Olympia opcodes on the ETC table: MCOPY, CLZ, BASEFEE, TLOAD and TSTORE.
  *
  * Pins: today's values, captured on the pre-swap tree, unchanged through S1 to S4.
  */
// scalastyle:off magic.number
class EtcOpCodeEdgeRowsSpec extends AnyFunSuite with Matchers:
  import PinHarness.*

  private def pow2(n: Int): BigInt = BigInt(2).pow(n)
  private def u(n: BigInt): UInt256 = UInt256(n)
  private val Max: UInt256 = UInt256.MaxValue
  private val Zero: UInt256 = UInt256.Zero
  private val StartGas: BigInt = 1_000_000

  // ── F03 ────────────────────────────────────────────────────────────────────────────────────────────────────────

  /** The Ethereum storage-trie root of `storage`: keccak(pad32(key)) -> RLP(value), as the world state commits it. */
  private def storageRoot(storage: MockStorage): String =
    val nodes = new SerializingMptStorage(new ArchiveNodeStorage(new NodeStorage(EphemDataSource())))
    val empty = EthereumUInt256Mpt.storageMpt(Account.EmptyStorageRootHash.value, nodes)
    val trie = storage.data.toSeq.sortBy(_._1).foldLeft(empty) { case (t, (k, v)) => t.put(k, v) }
    Hex.toHexString(trie.getRootHash)

  private val SstoreWords: Seq[UInt256] = Seq(Zero, u(1), u(pow2(255)), Max)

  /** (name, original value, current value) of the slot before the SSTORE. */
  private val SstoreScenarios: Seq[(String, UInt256, UInt256)] = Seq(
    ("fresh-zero", Zero, Zero), // original = current = 0
    ("fresh-2^255", u(pow2(255)), u(pow2(255))), // original = current, non-zero
    ("dirty-1-to-max", u(1), Max) // written earlier in the transaction: original 1, current 2^256 - 1
  )

  /** (label, era, whether the slot is already warm). */
  private val SstoreEras: Seq[(String, EtcForkMatrix.Era, Boolean)] = Seq(
    ("below-Phoenix", EtcForkMatrix.Agharta, false),
    ("Phoenix", EtcForkMatrix.Phoenix, false),
    ("Magneto-cold", EtcForkMatrix.Magneto, false),
    ("Magneto-warm", EtcForkMatrix.Magneto, true)
  )

  private def withSlot(key: UInt256, value: UInt256): MockWorldState =
    PinHarness.world().saveStorage(Owner, MockStorage.Empty.store(key.toBigInt, value.toBigInt))

  private def sstoreTable: String =
    val lines =
      for
        (label, era, warm) <- SstoreEras
        (scenario, original, current) <- SstoreScenarios
        key <- SstoreWords
        value <- SstoreWords
      yield
        val warmSlots: Set[(com.chipprbots.ethereum.domain.Address, StorageKey)] =
          if warm then Set((Owner, StorageKey(key.toBigInt))) else Set.empty
        val in = state(
          era.evmConfig,
          era.header,
          operands(key, value),
          world = withSlot(key, current),
          originalWorld = Some(withSlot(key, original)),
          warmStorage = warmSlots
        )
        val out = SSTORE.execute(in)
        s"$label block=${era.block} $scenario key=${key.toHexString} value=${value.toHexString} " +
          s"gas=${StartGas - out.gas} refund=${out.gasRefund} error=${out.error.getOrElse("-")} " +
          s"root=${storageRoot(out.storage)}"
    lines.mkString(
      "# spec 021 F03: SSTORE on the shipped ETC config by block number (EtcOpCodeEdgeRowsSpec). Captured on the\n" +
        "# pre-swap tree; never edited by a slice. Columns: era, block, scenario (original/current), key, value ->\n" +
        "# gas used, refund, error, storage root after.\n",
      "\n",
      "\n"
    )

  test("F03 SSTORE on ETC below Phoenix, at Phoenix and at Magneto: gas, refund and storage root", UnitTest, VMTest) {
    EtcForkMatrix.Agharta.block should be < EtcForkMatrix.Phoenix.block
    assertGolden("spec021/etc-sstore.golden", sstoreTable)
  }

  // ── F04 ────────────────────────────────────────────────────────────────────────────────────────────────────────

  test("F04 pre-EIP-150 CALL gas on ETC Homestead: the raw request is charged in full", UnitTest, VMTest) {
    val era = EtcForkMatrix.Homestead
    era.evmConfig.subGasCapDivisor shouldBe None
    val eoa = UInt256(Eoa.bytes)
    val GCall = BigInt(40) // Frontier/Homestead G_call; the target exists and no value moves, so nothing else
    for
      op <- Seq(CALL, CALLCODE, DELEGATECALL)
      request <- Seq(u(pow2(64) + 5), Max)
    do
      val stack =
        if op == DELEGATECALL then operands(request, eoa, Zero, Zero, Zero, Zero)
        else operands(request, eoa, Zero, Zero, Zero, Zero, Zero)
      val charged = request.toBigInt + GCall
      withClue(s"$op, request ${request.toHexString}, 10^5 gas: ") {
        val out = op.execute(state(era.evmConfig, era.header, stack, gas = 100_000))
        out.error shouldBe Some(OutOfGas)
        out.gas shouldBe 0
        out.stack shouldBe stack
        out.memory.size shouldBe 0
      }
      withClue(s"$op, request ${request.toHexString}, one gas short of the full request: ") {
        val out = op.execute(state(era.evmConfig, era.header, stack, gas = charged - 1))
        out.error shouldBe Some(OutOfGas)
        out.stack shouldBe stack
      }
      withClue(s"$op, request ${request.toHexString}, exactly the full request: ") {
        val out = op.execute(state(era.evmConfig, era.header, stack, gas = charged))
        out.error shouldBe None
        words(out.stack) shouldBe Seq(UInt256.One)
        out.gas shouldBe charged - GCall // the child is handed the whole request and returns it
      }
  }

  // ── F05 ────────────────────────────────────────────────────────────────────────────────────────────────────────

  test("F05 the Olympia opcodes on the ETC table: MCOPY, CLZ, BASEFEE, TLOAD, TSTORE", UnitTest, VMTest) {
    val era = EtcForkMatrix.Olympia
    val config = era.evmConfig
    config.opCodeList shouldBe EvmConfig.EtcOlympiaOpCodes
    for op <- Seq(MCOPY, CLZ, BASEFEE, TLOAD, TSTORE) do config.opCodeFor(op.code) shouldBe Some(op)
    def run(op: OpCode, stack: Stack, gas: BigInt = StartGas, memory: Memory = Memory.empty): PS =
      op.execute(state(config, era.header, stack, gas = gas, memory = memory))

    // MCOPY: O06's vectors, and one plain copy.
    for (dst, src) <- Seq((Zero, Max), (Max, Zero)) do
      withClue(s"MCOPY dst ${dst.toHexString}, src ${src.toHexString}, size 1: ") {
        val stack = operands(dst, src, u(1))
        val out = run(MCOPY, stack, gas = 10_000_000)
        out.error shouldBe Some(OutOfGas)
        out.stack shouldBe stack
        out.memory.size shouldBe 0
      }
    val none = run(MCOPY, operands(Max, Max, Zero))
    none.error shouldBe None
    none.memory.size shouldBe 0
    (StartGas - none.gas) shouldBe 3
    val word = UInt256(pow2(255) + 0xabcdef)
    val copied = run(MCOPY, operands(u(32), Zero, u(32)), memory = memoryOf(word.bytes))
    copied.memory shouldBe memoryOf(word.bytes ++ word.bytes)
    (StartGas - copied.gas) shouldBe 9 // 3 + one copied word 3 + expansion from 32 to 64 bytes 3

    // CLZ (EIP-7939).
    for (value, count) <- Seq(Zero -> 256, u(1) -> 255, u(pow2(64)) -> 191, u(pow2(255)) -> 0, Max -> 0) do
      withClue(s"CLZ ${value.toHexString}: ") {
        val out = run(CLZ, operands(value))
        words(out.stack) shouldBe Seq(u(count))
        (StartGas - out.gas) shouldBe 5
      }

    // BASEFEE (EIP-3198): the header's base fee.
    val baseFee = run(BASEFEE, Stack.empty())
    words(baseFee.stack) shouldBe Seq(u(EtcForkMatrix.OlympiaBaseFee))
    (StartGas - baseFee.gas) shouldBe 2

    // TSTORE then TLOAD (EIP-1153), with keys on the sign bit and at the top.
    for (key, value) <- Seq(u(pow2(255)) -> Max, Max -> u(pow2(255)), u(1) -> u(1)) do
      withClue(s"TSTORE/TLOAD key ${key.toHexString}: ") {
        val stored = run(TSTORE, operands(key, value))
        stored.error shouldBe None
        (StartGas - stored.gas) shouldBe 100
        stored.transientStorage((Owner, StorageKey(key.toBigInt))) shouldBe value.toBigInt
        val loaded = TLOAD.execute(stored.withStack(operands(key)))
        words(loaded.stack) shouldBe Seq(value)
        (stored.gas - loaded.gas) shouldBe 100
      }
    words(run(TLOAD, operands(Max)).stack) shouldBe Seq(Zero)
  }
