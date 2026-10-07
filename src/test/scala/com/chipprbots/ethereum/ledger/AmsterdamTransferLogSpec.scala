package com.chipprbots.ethereum.ledger

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.TxLogEntry
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.testing.Tags.*

/** Conformance vector **V5** — EIP-7708 value-transfer logs.
  *
  * Measured in the fixture: pre-activation transfer blocks 18, 30 and 31 carry an EMPTY bloom; post-activation blocks
  * 46, 47 and 48 carry bloom popcount **12**; `tx-calltree`'s bloom rises 18 -> 30, the four extra bloom items being
  * its 1-wei CALL's transfer log. Blocks 46 and 48 reconstruct byte-exactly as a single
  * `Transfer(address,address,uint256)` log from `0xfffffffffffffffffffffffffffffffffffffffe`.
  *
  * Popcount 12 is not arbitrary: a bloom entry sets 3 bits per item, and a transfer log has 4 items — the emitter
  * address plus three topics. 4 x 3 = 12 distinct bits, with no collisions here.
  *
  * **Without these logs every receipt root and every bloom after activation is wrong**, which is why EIP-7708 is in
  * slice B rather than deferred: the fixture exercises it from the first post-activation transfer.
  */
// scalastyle:off magic.number
class AmsterdamTransferLogSpec extends AnyFlatSpec with Matchers with AmsterdamFixtureVectors:

  private val recipient: Address = Address(Hex.decode("83c7e323d189f18725ac510004fdc2941f8c4a78"))

  private def transferWorld: InMemoryWorldStateProxy =
    setup.emptyWorld
      .saveAccount(senderAddress, Account(nonce = UInt256(0), balance = UInt256(BigInt("1000000000000000000000"))))
      .saveAccount(recipient, Account(nonce = UInt256(0), balance = UInt256(BigInt(1000))))

  private def leftPad32(addr: Address): ByteString = ByteString(Array.fill[Byte](12)(0) ++ addr.bytes.toArray)

  private def uint256(value: BigInt): ByteString =
    val raw = value.toByteArray.dropWhile(_ == 0.toByte)
    ByteString(Array.fill[Byte](32 - raw.length)(0) ++ raw)

  private def expectedTransferLog(from: Address, to: Address, value: BigInt): TxLogEntry =
    TxLogEntry(
      loggerAddress = SystemAddress,
      logTopics = Seq(TransferTopic, leftPad32(from), leftPad32(to)),
      data = uint256(value)
    )

  private def popcount(bloom: ByteString): Int = bloom.foldLeft(0)((n, b) => n + Integer.bitCount(b & 0xff))

  // ── Pre-activation: no logs at all ────────────────────────────────────────

  "V5 — a pre-activation transfer" should "emit no log and produce an empty bloom" taggedAs (
    ConsensusTest
  ) in {
    val stx = dynamicFeeTx(Some(recipient), value = 1, gasLimit = 21000, config = preAmsterdamConfig)
    val result = execute(stx, preAmsterdamHeader(18, 180), transferWorld, preAmsterdamConfig)
    result.logs shouldBe empty
    popcount(BloomFilter.create(result.logs)) shouldBe 0
  }

  // ── Post-activation: exactly one log, byte-exact ──────────────────────────

  "V5 — a post-activation transfer" should "emit one Transfer log from the system address" taggedAs (
    ConsensusTest
  ) in {
    val stx = dynamicFeeTx(Some(recipient), value = 1, gasLimit = 21000, config = amsterdamConfig)
    val result = execute(stx, amsterdamHeader(46, 460), transferWorld, amsterdamConfig)
    result.logs shouldBe Seq(expectedTransferLog(senderAddress, recipient, 1))
  }

  it should "give bloom popcount 12, the measured figure for blocks 46, 47 and 48" taggedAs (ConsensusTest) in {
    val stx = dynamicFeeTx(Some(recipient), value = 1, gasLimit = 21000, config = amsterdamConfig)
    val result = execute(stx, amsterdamHeader(46, 460), transferWorld, amsterdamConfig)
    popcount(BloomFilter.create(result.logs)) shouldBe 12
  }

  it should "do the same for a legacy transaction — the rule is not transaction-type dependent" taggedAs (
    ConsensusTest
  ) in {
    // Block 48 is the legacy-typed twin of block 46 and carries the identical popcount.
    val stx = legacyTx(Some(recipient), value = 1, gasLimit = 21000, config = amsterdamConfig)
    val result = execute(stx, amsterdamHeader(48, 480), transferWorld, amsterdamConfig)
    result.logs shouldBe Seq(expectedTransferLog(senderAddress, recipient, 1))
    popcount(BloomFilter.create(result.logs)) shouldBe 12
  }

  // ── Zero value and self-transfer emit nothing ─────────────────────────────

  "V5 — a zero-value transaction" should "emit no transfer log" taggedAs (ConsensusTest) in {
    val stx = dynamicFeeTx(Some(recipient), value = 0, gasLimit = 21000, config = amsterdamConfig)
    execute(stx, amsterdamHeader(47, 470), transferWorld, amsterdamConfig).logs shouldBe empty
  }

  "V5 — a self-transfer" should "emit no transfer log, matching its 12,000 intrinsic" taggedAs (ConsensusTest) in {
    // EIP-7708 fires only for a transfer "to a different account". The same condition removes TX_VALUE_COST,
    // which is why the self-transfer costs TX_BASE_COST alone — the two rules have to agree or one is wrong.
    val stx = dynamicFeeTx(Some(senderAddress), value = 1, gasLimit = 21000, config = amsterdamConfig)
    execute(stx, amsterdamHeader(165, 1650), transferWorld, amsterdamConfig).logs shouldBe empty
  }

  // ── The sub-call case, from the fixture's richest block ───────────────────

  "V5 — block 41 (tx-calltree)" should "emit its CALL's transfer log first, ahead of every EVM log" taggedAs (
    ConsensusTest
  ) in {
    // Measured log list for block 41, in order:
    //   0: system-address Transfer, calltree -> callme, 1 wei   <- EIP-7708
    //   1: calltree 'emit' log (via DELEGATECALL)
    //   2: the CREATE'd child's 'child' log
    //   3: calltree's own 'tree' log
    val stx = dynamicFeeTx(Some(CallTree), value = 0, gasLimit = 600000, config = amsterdamConfig)
    val result = execute(stx, amsterdamHeader(41, 410), block41World, amsterdamConfig)
    result.logs.size shouldBe 4
    result.logs.head shouldBe expectedTransferLog(CallTree, CallMe, 1)
  }

  it should "add exactly 12 bloom bits relative to the pre-activation block" taggedAs (ConsensusTest) in {
    // Measured: calltree's bloom popcount rises 18 -> 30 on activation.
    val before = execute(
      dynamicFeeTx(Some(CallTree), value = 0, gasLimit = 600000, config = preAmsterdamConfig),
      preAmsterdamHeader(24, 240),
      block24World,
      preAmsterdamConfig
    )
    val after = execute(
      dynamicFeeTx(Some(CallTree), value = 0, gasLimit = 600000, config = amsterdamConfig),
      amsterdamHeader(41, 410),
      block41World,
      amsterdamConfig
    )
    popcount(BloomFilter.create(after.logs)) - popcount(BloomFilter.create(before.logs)) shouldBe 12
  }
