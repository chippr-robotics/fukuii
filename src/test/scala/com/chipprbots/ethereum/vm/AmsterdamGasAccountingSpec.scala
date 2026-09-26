package com.chipprbots.ethereum.vm

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.ledger.AmsterdamFixtureVectors
import com.chipprbots.ethereum.testing.Tags.*

/** Conformance vectors V1–V3 from `specs/009-amsterdam-fork-support/contracts/gas-accounting.md`, executed against the
  * reference fixture's own bytecode rather than asserted from a table.
  *
  * **Stage 0 first.** Before any Amsterdam figure is claimed, the harness is validated on two *pre-Amsterdam* block
  * totals whose answers are already published in the fixture: block 8 = 165,447 and block 24 = 168,247, both
  * `tx-calltree`. If those two do not reproduce to the gas unit, nothing downstream means anything — the same
  * discipline `scripts/amsterdam-fixture/verify.py` applies before it reports a single Amsterdam number.
  *
  * The two blocks differ only in storage shape: at block 8 the DELEGATECALL'd `emit` writes zero to a zero slot (a
  * no-op) and creates slot 0; at block 24 it creates the hash-keyed slot. That 2,800-gas difference between two
  * measured totals is itself a check that the harness is modelling SSTORE state transitions and not a constant.
  */
// scalastyle:off magic.number
class AmsterdamGasAccountingSpec extends AnyFlatSpec with Matchers with AmsterdamFixtureVectors:

  // ── Stage 0: validate the harness on answers already known ────────────────

  "the harness" should "reproduce pre-Amsterdam block 8 (tx-calltree) at exactly 165,447 gas" taggedAs (
    VMTest,
    ConsensusTest
  ) in {
    val stx = dynamicFeeTx(Some(CallTree), value = 0, gasLimit = 600000, config = preAmsterdamConfig)
    val result = execute(stx, preAmsterdamHeader(8, 80), block8World, preAmsterdamConfig)
    result.vmError shouldBe None
    result.gasUsed shouldBe BigInt(165447)
  }

  it should "reproduce pre-Amsterdam block 24 (tx-calltree) at exactly 168,247 gas" taggedAs (
    VMTest,
    ConsensusTest
  ) in {
    val stx = dynamicFeeTx(Some(CallTree), value = 0, gasLimit = 600000, config = preAmsterdamConfig)
    val result = execute(stx, preAmsterdamHeader(24, 240), block24World, preAmsterdamConfig)
    result.vmError shouldBe None
    result.gasUsed shouldBe BigInt(168247)
  }

  it should "reproduce pre-Amsterdam block 23 (tx-callrevert) at exactly 23,201 gas" taggedAs (
    VMTest,
    ConsensusTest
  ) in {
    val stx = dynamicFeeTx(
      Some(CallRevert),
      value = 0,
      gasLimit = 100000,
      payload = ByteString(Hex.decode("01")),
      config = preAmsterdamConfig
    )
    val result = execute(stx, preAmsterdamHeader(23, 230), callRevertWorld, preAmsterdamConfig)
    result.gasUsed shouldBe BigInt(23201)
  }

  // ── V1: the header reports a MAXIMUM, the receipt reports a SUM ────────────

  "V1 — block 41 (tx-calltree)" should "charge the sender exactly 326,947, the measured receipt figure" taggedAs (
    VMTest,
    ConsensusTest
  ) in {
    val stx = dynamicFeeTx(Some(CallTree), value = 0, gasLimit = 600000, config = amsterdamConfig)
    val result = execute(stx, amsterdamHeader(41, 410), block41World, amsterdamConfig)
    result.vmError shouldBe None
    // receipt.cumulativeGasUsed — measured byte-exactly from the fixture's receipts root.
    result.gasUsed shouldBe BigInt(326947)
  }

  it should "put 183,600 in the state dimension and 143,347 in the execution dimension" taggedAs (
    VMTest,
    ConsensusTest
  ) in {
    val stx = dynamicFeeTx(Some(CallTree), value = 0, gasLimit = 600000, config = amsterdamConfig)
    val result = executeBlock(Seq(stx), amsterdamHeader(41, 410), block41World, amsterdamConfig)
    // tx_state_gas = STATE_BYTES_PER_NEW_ACCOUNT(120) x CPSB(1530), from calltree's single CREATE.
    result.stateGasUsed shouldBe BigInt(183600)
    // tx_execution_gas = max(tx_gas_used_before_refund - tx_state_gas, floor) = 326,947 - 183,600.
    result.executionGasUsed shouldBe BigInt(143347)
  }

  it should "report the header as a MAXIMUM and the receipt as a SUM, disagreeing" taggedAs (
    VMTest,
    ConsensusTest
  ) in {
    // THE decisive assertion for slice B. A single-counter implementation satisfies exactly one of these
    // two lines and passes every weaker test in this file.
    val stx = dynamicFeeTx(Some(CallTree), value = 0, gasLimit = 600000, config = amsterdamConfig)
    val result = executeBlock(Seq(stx), amsterdamHeader(41, 410), block41World, amsterdamConfig)

    result.gasUsed shouldBe BigInt(183600) // header.gasUsed  = max(143,347, 183,600)
    result.receipts.last.cumulativeGasUsed shouldBe BigInt(326947) // receipt = the SUM

    result.gasUsed should not be result.receipts.last.cumulativeGasUsed
    result.gasUsed shouldBe result.executionGasUsed.max(result.stateGasUsed)
  }

  it should "keep the header equal to the receipt sum on the SAME transaction before activation" taggedAs (
    VMTest,
    ConsensusTest
  ) in {
    // The control. Pre-Amsterdam the two counters collapse: state stays 0, execution accumulates exactly
    // what the old single counter did, and header == receipt to the gas unit. If this ever diverges, the
    // new counters have leaked onto a path that never activated the fork.
    val stx = dynamicFeeTx(Some(CallTree), value = 0, gasLimit = 600000, config = preAmsterdamConfig)
    val result = executeBlock(Seq(stx), preAmsterdamHeader(24, 240), block24World, preAmsterdamConfig)

    result.stateGasUsed shouldBe BigInt(0)
    result.executionGasUsed shouldBe BigInt(168247)
    result.gasUsed shouldBe BigInt(168247)
    result.receipts.last.cumulativeGasUsed shouldBe BigInt(168247)
  }

  // ── V2: the measured out-of-gas ───────────────────────────────────────────

  "V2 — block 45 (tx-emit-legacy)" should "halt out of gas consuming the whole 100,000 limit" taggedAs (
    VMTest,
    ConsensusTest
  ) in {
    val payload = ByteString(Hex.decode("580abd8a903ed7d3656d6974"))
    val stx = legacyTx(Some(Emit), value = 2, gasLimit = 100000, payload = payload, config = amsterdamConfig)
    val result = execute(stx, amsterdamHeader(45, 450), block45World, amsterdamConfig)
    // Measured: status 0, empty bloom, zero logs, the full limit consumed.
    result.vmError shouldBe Some(OutOfGas)
    result.logs shouldBe empty
    result.gasUsed shouldBe BigInt(100000)
  }

  it should "leave the emit counter untouched — the state change is rolled back" taggedAs (VMTest, ConsensusTest) in {
    val payload = ByteString(Hex.decode("580abd8a903ed7d3656d6974"))
    val stx = legacyTx(Some(Emit), value = 2, gasLimit = 100000, payload = payload, config = amsterdamConfig)
    val result = execute(stx, amsterdamHeader(45, 450), block45World, amsterdamConfig)
    // The fixture's emit counter is frozen at 8 = the pre-Amsterdam invocation count.
    result.worldState.getStorage(Emit).load(UInt256(0)) shouldBe BigInt(8)
  }

  it should "succeed at a gas limit that can afford the state charge, proving the halt is the limit" taggedAs (
    VMTest,
    ConsensusTest
  ) in {
    // 144,888 is the measured full cost had the gas existed (research.md Decision 3). One more than that
    // must succeed; the point is that the same transaction is not intrinsically broken — it is under-funded.
    val payload = ByteString(Hex.decode("580abd8a903ed7d3656d6974"))
    val stx = legacyTx(Some(Emit), value = 2, gasLimit = 200000, payload = payload, config = amsterdamConfig)
    val result = execute(stx, amsterdamHeader(45, 450), block45World, amsterdamConfig)
    result.vmError shouldBe None
    result.gasUsed shouldBe BigInt(144888)
  }

  // ── Contract-creation transactions ────────────────────────
  //
  // 131 of the fixture's 522 post-activation transactions are contract creations, and the chain deploys
  // three initcodes on BOTH sides of the fork. That makes them measured pre/post pairs: the pre-Amsterdam
  // figure validates the harness on the creation path, and the post-Amsterdam figure is the answer.
  //
  // All three post-activation deployments consume their entire gas limit. That is not a coincidence and
  // not an estimator artefact: under EIP-2780 the new-account charge of 183,600 state gas is a RUNTIME
  // charge in the pre-execution phase, and none of these transactions is funded for it. EIP-2780 is
  // explicit that running out there does not invalidate the transaction — it is included, charged for
  // everything consumed, and its state changes reverted.

  private val Deploy256ByteCode: ByteString = ByteString(
    Hex.decode("43600052600060205260405b604060002060208051600101905281526020016101408110600b57506101006040f3")
  )
  private val DeployLoggingLoop: ByteString =
    ByteString(Hex.decode("4360005260006020525b604060002060208051600101905260206020a15a61271010600957"))
  private val DeployStorageLoop: ByteString =
    ByteString(Hex.decode("435b8080556001015a6161a810600157"))

  private def fundedOnly = setup.emptyWorld.saveAccount(
    senderAddress,
    com.chipprbots.ethereum.domain.Account(nonce = UInt256(0), balance = UInt256(BigInt("1000000000000000000000")))
  )

  "the harness" should "reproduce the three pre-Amsterdam contract deployments exactly" taggedAs (
    VMTest,
    ConsensusTest
  ) in {
    // Block 4: deploys 256 bytes of code, so its 51,200 of code deposit at 200/byte dominates the total.
    execute(
      legacyTx(None, 0, 113692, payload = Deploy256ByteCode, config = preAmsterdamConfig),
      preAmsterdamHeader(4, 40),
      fundedOnly,
      preAmsterdamConfig
    ).gasUsed shouldBe BigInt(105782)

    // Block 5: a LOG-emitting loop that runs until GAS drops below 10,000, then halts.
    execute(
      legacyTx(None, 0, 73560, payload = DeployLoggingLoop, config = preAmsterdamConfig),
      preAmsterdamHeader(5, 50),
      fundedOnly,
      preAmsterdamConfig
    ).gasUsed shouldBe BigInt(64613)

    // Block 6: an SSTORE loop that runs until GAS drops below 25,000 — the sharpest of the three,
    // because its termination depends on the running gas figure being right at every step.
    execute(
      legacyTx(None, 0, 133258, payload = DeployStorageLoop, config = preAmsterdamConfig),
      preAmsterdamHeader(6, 60),
      fundedOnly,
      preAmsterdamConfig
    ).gasUsed shouldBe BigInt(119662)
  }

  "a post-activation contract creation" should "consume its whole limit when it cannot fund the new account" taggedAs (
    VMTest,
    ConsensusTest
  ) in {
    // Blocks 50, 38 and 39 — the same three initcodes, measured after activation.
    val cases = Seq(
      (Deploy256ByteCode, BigInt(84692), 50L, 500L),
      (DeployLoggingLoop, BigInt(44560), 38L, 380L),
      (DeployStorageLoop, BigInt(104258), 39L, 390L)
    )
    cases.foreach { case (initcode, limit, number, timestamp) =>
      val result = execute(
        legacyTx(None, 0, limit, payload = initcode, config = amsterdamConfig),
        amsterdamHeader(number, timestamp),
        fundedOnly,
        amsterdamConfig
      )
      withClue(s"block $number: ") {
        result.vmError shouldBe Some(OutOfGas)
        result.gasUsed shouldBe limit
        // The charge was rolled back with the pre-execution phase, so nothing lands in the state
        // dimension — exactly as for the `tx-emit-*` halt.
        result.stateGasUsed shouldBe BigInt(0)
        result.logs shouldBe empty
      }
    }
  }

  it should "still deploy, and charge state gas per byte, when it IS funded" taggedAs (VMTest, ConsensusTest) in {
    // The positive control for the same path: given enough gas the deployment succeeds, and the state
    // dimension carries the new account leaf plus CPSB for every deposited byte.
    val result = execute(
      legacyTx(None, 0, 1500000, payload = Deploy256ByteCode, config = amsterdamConfig),
      amsterdamHeader(50, 500),
      fundedOnly,
      amsterdamConfig
    )
    result.vmError shouldBe None
    // GAS_NEW_ACCOUNT + CPSB x 256 deposited bytes.
    result.stateGasUsed shouldBe (AmsterdamGas.GasNewAccount + AmsterdamGas.Cpsb * 256)
    result.stateGasUsed shouldBe BigInt(575280)
    // And the header would report the state dimension, because it dominates.
    result.stateGasUsed should be > result.executionGasUsed
  }

  // ── V3: the state-dimension constants ─────────────────────────────────────

  "V3 — the state-creation constants" should "make GAS_STORAGE_SET exactly 97,920" taggedAs (VMTest) in {
    // STATE_BYTES_PER_STORAGE_SET(64) x CPSB(1530). Blocks 36 and 37 are exact multiples of this:
    // 783,360 = 8 x 97,920 and 489,600 = 5 x 97,920.
    AmsterdamGas.StateBytesPerStorageSet * AmsterdamGas.Cpsb shouldBe BigInt(97920)
    BigInt(783360) / BigInt(97920) shouldBe BigInt(8)
    BigInt(489600) / BigInt(97920) shouldBe BigInt(5)
  }

  it should "make GAS_NEW_ACCOUNT exactly 183,600" taggedAs (VMTest) in {
    // STATE_BYTES_PER_NEW_ACCOUNT(120) x CPSB(1530) — block 41's whole header figure.
    AmsterdamGas.StateBytesPerNewAccount * AmsterdamGas.Cpsb shouldBe BigInt(183600)
  }

  it should "make a block of N fresh-slot writes report exactly N x 97,920 in the header" taggedAs (
    VMTest,
    ConsensusTest
  ) in {
    // The shape of blocks 36 (8 x 97,920) and 37 (5 x 97,920): the state dimension is the maximum, so the
    // header is an exact multiple of the storage-set charge while the receipts sum something else entirely.
    // Driven here with two `emit` transactions, each creating one hash-keyed slot.
    val payload1 = ByteString(Hex.decode("580abd8a903ed7d3656d6974"))
    val payload2 = ByteString(Hex.decode("580abd8a903ed7d4656d6974"))
    val txs = Seq(
      legacyTx(Some(Emit), value = 2, gasLimit = 200000, payload = payload1, nonce = 0, config = amsterdamConfig),
      legacyTx(Some(Emit), value = 2, gasLimit = 200000, payload = payload2, nonce = 1, config = amsterdamConfig)
    )
    val result = executeBlock(txs, amsterdamHeader(36, 360), block45World, amsterdamConfig)

    result.stateGasUsed shouldBe BigInt(2) * AmsterdamGas.GasStorageSet // 195,840
    result.gasUsed shouldBe BigInt(195840)
    (result.gasUsed % AmsterdamGas.GasStorageSet) shouldBe BigInt(0)
    // The execution dimension is genuinely smaller — this is a state-bound block, not a coincidence.
    result.executionGasUsed should be < result.stateGasUsed
    // And the receipt still reports the per-transaction TOTAL, which is larger than the header.
    result.receipts.last.cumulativeGasUsed shouldBe BigInt(2) * BigInt(144888)
  }
