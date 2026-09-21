package com.chipprbots.ethereum.vm

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.ledger.InMemoryWorldStateProxy
import com.chipprbots.ethereum.ledger.AmsterdamFixtureVectors
import com.chipprbots.ethereum.testing.Tags.*

/** Conformance vector **V4** — EIP-2780 moves intrinsic gas in THREE directions, not one.
  *
  * | Case                                        | Pre-Amsterdam | Amsterdam          | Fixture witness |
  * |:--------------------------------------------|:--------------|:-------------------|:----------------|
  * | transfer to an existing EOA                 | 21,000        | 21,000 (unchanged) | block 46        |
  * | self-transfer                               | 21,000        | **12,000**         | block 165       |
  * | `tx-callrevert` (zero value, to a contract) | 23,201        | **17,201**         | blocks 23 / 40  |
  *
  * A blanket change in either direction is wrong; the self-transfer row is the sharpest and it is measured from the
  * chain, not read out of the EIP. These are driven end to end through `executeTransaction`, so the figure asserted is
  * the one the sender is actually charged.
  */
// scalastyle:off magic.number
class AmsterdamIntrinsicGasSpec extends AnyFlatSpec with Matchers with AmsterdamFixtureVectors:

  private val recipient: Address = Address(Hex.decode("83c7e323d189f18725ac510004fdc2941f8c4a78"))

  /** An existing, code-less recipient plus a funded sender — block 46's shape. */
  private def transferWorld: InMemoryWorldStateProxy =
    setup.emptyWorld
      .saveAccount(senderAddress, Account(nonce = UInt256(0), balance = UInt256(BigInt("1000000000000000000000"))))
      .saveAccount(recipient, Account(nonce = UInt256(0), balance = UInt256(BigInt(1000))))

  // ── Direction 1: unchanged ────────────────────────────────────────────────

  "V4 — a value transfer to an existing EOA" should "still cost exactly 21,000 under Amsterdam" taggedAs (
    VMTest,
    ConsensusTest
  ) in {
    // TX_BASE_COST 12,000 + COLD_ACCOUNT_ACCESS 3,000 + TX_VALUE_COST 6,000 = 21,000. The decomposition
    // lands back on the legacy constant; that it does is the check, not a coincidence to wave through.
    val stx = dynamicFeeTx(Some(recipient), value = 1, gasLimit = 21000, config = amsterdamConfig)
    execute(stx, amsterdamHeader(46, 460), transferWorld, amsterdamConfig).gasUsed shouldBe BigInt(21000)
  }

  it should "have cost 21,000 before activation too" taggedAs (VMTest, ConsensusTest) in {
    val stx = dynamicFeeTx(Some(recipient), value = 1, gasLimit = 21000, config = preAmsterdamConfig)
    execute(stx, preAmsterdamHeader(18, 180), transferWorld, preAmsterdamConfig).gasUsed shouldBe BigInt(21000)
  }

  // ── Direction 2: down ─────────────────────────────────────────────────────

  "V4 — a self-transfer" should "drop to 12,000, TX_BASE_COST alone" taggedAs (VMTest, ConsensusTest) in {
    // EIP-2780: tx.to == tx.sender charges nothing for the recipient and nothing for the value.
    // Note this also fixes the EIP-7623 calldata floor at 12,000 — a floor still anchored at 21,000
    // would force the answer back up and silently defeat the rule.
    val stx = dynamicFeeTx(Some(senderAddress), value = 1, gasLimit = 21000, config = amsterdamConfig)
    execute(stx, amsterdamHeader(165, 1650), transferWorld, amsterdamConfig).gasUsed shouldBe BigInt(12000)
  }

  it should "have cost the full 21,000 before activation" taggedAs (VMTest, ConsensusTest) in {
    val stx = dynamicFeeTx(Some(senderAddress), value = 1, gasLimit = 21000, config = preAmsterdamConfig)
    execute(stx, preAmsterdamHeader(19, 190), transferWorld, preAmsterdamConfig).gasUsed shouldBe BigInt(21000)
  }

  // ── Direction 3: down, but for a different reason ─────────────────────────

  "V4 — tx-callrevert" should "drop from 23,201 to 17,201 on activation" taggedAs (VMTest, ConsensusTest) in {
    // Zero value to a contract: TX_BASE_COST 12,000 + COLD_ACCOUNT_ACCESS 3,000 = 15,000, no TX_VALUE_COST.
    // The 2,185 of contract execution (a cold SLOAD plus a REVERT) is identical on both sides of the fork,
    // so the whole 6,000 delta is intrinsic.
    val payload = ByteString(Hex.decode("01"))
    val before = execute(
      dynamicFeeTx(Some(CallRevert), 0, 100000, payload = payload, config = preAmsterdamConfig),
      preAmsterdamHeader(23, 230),
      callRevertWorld,
      preAmsterdamConfig
    )
    val after = execute(
      dynamicFeeTx(Some(CallRevert), 0, 100000, payload = payload, config = amsterdamConfig),
      amsterdamHeader(40, 400),
      callRevertWorld,
      amsterdamConfig
    )
    before.gasUsed shouldBe BigInt(23201)
    after.gasUsed shouldBe BigInt(17201)
    (before.gasUsed - after.gasUsed) shouldBe BigInt(6000)
  }

  // ── A zero-value call to a contract ───────────────────────────────────────

  "V4 — a zero-value call" should "pay 15,000 of intrinsic, not 21,000" taggedAs (VMTest, ConsensusTest) in {
    // `blockhashes` returns immediately after 125 gas of execution, so the total isolates the intrinsic term.
    val stx = dynamicFeeTx(Some(BlockHashes), value = 0, gasLimit = 100000, config = amsterdamConfig)
    execute(stx, amsterdamHeader(50, 500), block41World, amsterdamConfig).gasUsed shouldBe BigInt(15125)
  }
