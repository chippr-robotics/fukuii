package com.chipprbots.ethereum.consensus.mess

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags._

// ── L6: MESS blockchain-level reorg boundary (ECBP-1100 + Spiral deactivation + Olympia re-activation) ─────
//
// Existing MESScorerSpec covers the polynomial math and the per-call shouldRejectReorg function in isolation.
// These tests combine MESSConfig.isActiveAtBlock with ArtificialFinality.shouldRejectReorg to exercise
// the full decision at realistic ETC mainnet block numbers, including:
//   - Pre-activation gap   : before ECBP-1100 block 11,380,000 — any reorg accepted
//   - Active ECBP-1100 window : [11,380,000, 19,250,000) — polynomial applied
//   - Deactivation at Spiral  : at/after block 19,250,000 — any reorg accepted again
//   - Olympia re-activation   : at/after a hypothetical Olympia block — polynomial re-applied
//
// The guard pattern used in production is:
//   if (messConfig.isActiveAtBlock(localHeadBlock))
//     ArtificialFinality.shouldRejectReorg(timeDelta, localTD, proposedTD)
//   else false  // MESS inactive → never reject
//
// These tests lock that guard so a refactor to MESSConfig cannot silently extend/shrink the
// active window and accidentally reject valid reorgs (or accept invalid ones) outside the window.

class MESSIntegrationSpec extends AnyFlatSpec with Matchers {

  // ETC mainnet ECBP-1100 canonical block numbers
  private val MessActivation = BigInt(11_380_000)
  private val MessDeactivation = BigInt(19_250_000) // Spiral fork deactivates MESS
  private val OlympiaBlock = BigInt(25_000_000) // hypothetical; actual TBD

  private val etcMainnetConfig = MESSConfig(
    enabled = true,
    activationBlock = Some(MessActivation.toLong),
    deactivationBlock = Some(MessDeactivation.toLong),
    reactivationBlock = Some(OlympiaBlock)
  )

  // Helper: the full MESS guard as used in production
  private def wouldRejectReorg(
      localHeadBlock: BigInt,
      timeDeltaSeconds: Long,
      localSubchainTD: BigInt,
      proposedSubchainTD: BigInt
  ): Boolean =
    if (etcMainnetConfig.isActiveAtBlock(localHeadBlock))
      ArtificialFinality.shouldRejectReorg(timeDeltaSeconds, localSubchainTD, proposedSubchainTD)
    else
      false

  // ── Pre-activation: any reorg accepted ───────────────────────────────────

  "MESS integration" should "accept any reorg before the ECBP-1100 activation block" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    val preActivation = MessActivation - 1
    // Even a low-TD 7-hour reorg must be accepted before ECBP-1100 fires.
    wouldRejectReorg(
      localHeadBlock = preActivation,
      timeDeltaSeconds = 25132, // 7 hours — maximum polynomial multiplier
      localSubchainTD = BigInt(1_000_000),
      proposedSubchainTD = BigInt(1) // vanishingly small — would fail MESS if active
    ) shouldBe false
  }

  it should "accept any reorg at block 0 (genesis)" taggedAs (UnitTest, ConsensusTest) in {
    wouldRejectReorg(
      localHeadBlock = BigInt(0),
      timeDeltaSeconds = 100_000,
      localSubchainTD = BigInt(1_000_000),
      proposedSubchainTD = BigInt(1)
    ) shouldBe false
  }

  // ── Active ECBP-1100 window: short recent reorg always accepted ───────────

  it should "accept a short recent reorg (timeDelta<1s) during the MESS-active window" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    // timeDelta=0 → polynomial=128/128=1x → any TD >= local is accepted
    val midWindow = MessActivation + BigInt(1_000_000)
    wouldRejectReorg(
      localHeadBlock = midWindow,
      timeDeltaSeconds = 0,
      localSubchainTD = BigInt(1_000),
      proposedSubchainTD = BigInt(1_000)
    ) shouldBe false

    wouldRejectReorg(
      localHeadBlock = midWindow,
      timeDeltaSeconds = 0,
      localSubchainTD = BigInt(1_000),
      proposedSubchainTD = BigInt(1_001)
    ) shouldBe false
  }

  it should "reject a recent reorg whose TD is lower than local during the MESS-active window" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    val midWindow = MessActivation + BigInt(1_000_000)
    wouldRejectReorg(
      localHeadBlock = midWindow,
      timeDeltaSeconds = 0,
      localSubchainTD = BigInt(1_000),
      proposedSubchainTD = BigInt(999) // lower TD — rejected even without extra multiplier
    ) shouldBe true
  }

  // ── Active ECBP-1100 window: long-range reorg requires 31x TD ────────────

  it should "accept a 7-hour reorg with exactly 31x proposed TD during MESS-active window" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    val midWindow = MessActivation + BigInt(2_000_000)
    // polynomialV(25132) = 3968; 3968/128 = 31x threshold
    wouldRejectReorg(
      localHeadBlock = midWindow,
      timeDeltaSeconds = 25132,
      localSubchainTD = BigInt(1_000),
      proposedSubchainTD = BigInt(31_000) // exactly 31x
    ) shouldBe false
  }

  it should "reject a 7-hour reorg with only 30x proposed TD during MESS-active window" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    val midWindow = MessActivation + BigInt(2_000_000)
    wouldRejectReorg(
      localHeadBlock = midWindow,
      timeDeltaSeconds = 25132,
      localSubchainTD = BigInt(1_000),
      proposedSubchainTD = BigInt(30_000) // 30x is not enough for 31x requirement
    ) shouldBe true
  }

  it should "accept a 1-hour reorg with 3x proposed TD during MESS-active window" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    // At 1 hour (~3600s) the polynomial requires ~2.66x → 3x safely clears it
    val midWindow = MessActivation + BigInt(3_000_000)
    wouldRejectReorg(
      localHeadBlock = midWindow,
      timeDeltaSeconds = 3600,
      localSubchainTD = BigInt(1_000),
      proposedSubchainTD = BigInt(3_000) // 3x
    ) shouldBe false
  }

  it should "reject a 1-hour reorg with 2x proposed TD during MESS-active window" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    val midWindow = MessActivation + BigInt(3_000_000)
    wouldRejectReorg(
      localHeadBlock = midWindow,
      timeDeltaSeconds = 3600,
      localSubchainTD = BigInt(1_000),
      proposedSubchainTD = BigInt(2_000) // 2x — below ~2.66x requirement
    ) shouldBe true
  }

  // ── Deactivation boundary: Spiral block 19,250,000 ───────────────────────

  it should "accept any reorg at the Spiral deactivation block (deactivation is exclusive)" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    // isActiveAtBlock(MessDeactivation) = false (deactivation is exclusive upper bound)
    // → wouldRejectReorg always returns false regardless of TD or timeDelta
    wouldRejectReorg(
      localHeadBlock = MessDeactivation,
      timeDeltaSeconds = 25132,
      localSubchainTD = BigInt(1_000_000),
      proposedSubchainTD = BigInt(1) // trivially low — would fail if MESS were active
    ) shouldBe false
  }

  it should "accept any reorg one block before Spiral deactivation is NOT deactivated" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    // isActiveAtBlock(MessDeactivation - 1) = true → MESS applies
    val lastActiveBlock = MessDeactivation - 1
    // A low-TD 7-hour reorg should be rejected because MESS is still active
    wouldRejectReorg(
      localHeadBlock = lastActiveBlock,
      timeDeltaSeconds = 25132,
      localSubchainTD = BigInt(1_000),
      proposedSubchainTD = BigInt(1) // far too low for 31x
    ) shouldBe true
  }

  it should "accept any reorg after Spiral deactivation block" taggedAs (UnitTest, ConsensusTest) in {
    val postSpiral = MessDeactivation + BigInt(500_000)
    wouldRejectReorg(
      localHeadBlock = postSpiral,
      timeDeltaSeconds = 25132,
      localSubchainTD = BigInt(1_000_000),
      proposedSubchainTD = BigInt(1)
    ) shouldBe false
  }

  // ── Olympia re-activation boundary ───────────────────────────────────────

  it should "accept any reorg in the deactivated gap [Spiral, Olympia)" taggedAs (UnitTest, ConsensusTest) in {
    val inGap = OlympiaBlock - BigInt(100_000)
    wouldRejectReorg(
      localHeadBlock = inGap,
      timeDeltaSeconds = 25132,
      localSubchainTD = BigInt(1_000_000),
      proposedSubchainTD = BigInt(1)
    ) shouldBe false
  }

  it should "apply MESS again starting exactly at Olympia re-activation block" taggedAs (UnitTest, ConsensusTest) in {
    // isActiveAtBlock(OlympiaBlock) = true — second window starts
    // A 7-hour reorg with 30x TD should now be rejected again
    wouldRejectReorg(
      localHeadBlock = OlympiaBlock,
      timeDeltaSeconds = 25132,
      localSubchainTD = BigInt(1_000),
      proposedSubchainTD = BigInt(30_000) // 30x — below 31x → rejected
    ) shouldBe true
  }

  it should "accept a 7-hour reorg with 31x TD at Olympia re-activation block" taggedAs (UnitTest, ConsensusTest) in {
    wouldRejectReorg(
      localHeadBlock = OlympiaBlock,
      timeDeltaSeconds = 25132,
      localSubchainTD = BigInt(1_000),
      proposedSubchainTD = BigInt(31_000) // exactly 31x → accepted
    ) shouldBe false
  }

  it should "apply MESS far past the Olympia re-activation block" taggedAs (UnitTest, ConsensusTest) in {
    val farFuture = OlympiaBlock + BigInt(10_000_000)
    wouldRejectReorg(
      localHeadBlock = farFuture,
      timeDeltaSeconds = 25132,
      localSubchainTD = BigInt(1_000),
      proposedSubchainTD = BigInt(30_000)
    ) shouldBe true
  }

  // ── Full ETC mainnet lifecycle: boundary-by-boundary sweep ───────────────

  it should "correctly toggle MESS across the full ETC mainnet lifecycle" taggedAs (UnitTest, ConsensusTest) in {
    // Use a definitively rejectable reorg: 7-hour, 1x TD vs 1,000,000x local.
    // When MESS is inactive → false. When active → true (1x is nowhere near 31x).
    def rejectsWith1xReorg(block: BigInt): Boolean =
      wouldRejectReorg(block, 25132, BigInt(1_000_000), BigInt(1_000_000))

    // Pre-MESS: accepted
    rejectsWith1xReorg(BigInt(0)) shouldBe false
    rejectsWith1xReorg(MessActivation - 1) shouldBe false

    // MESS active: rejected (1x TD, 7-hour reorg)
    rejectsWith1xReorg(MessActivation) shouldBe true
    rejectsWith1xReorg(MessActivation + BigInt(4_000_000)) shouldBe true
    rejectsWith1xReorg(MessDeactivation - 1) shouldBe true

    // Deactivated: accepted
    rejectsWith1xReorg(MessDeactivation) shouldBe false
    rejectsWith1xReorg(OlympiaBlock - 1) shouldBe false

    // Re-activated: rejected again
    rejectsWith1xReorg(OlympiaBlock) shouldBe true
    rejectsWith1xReorg(OlympiaBlock + BigInt(5_000_000)) shouldBe true
  }
}
