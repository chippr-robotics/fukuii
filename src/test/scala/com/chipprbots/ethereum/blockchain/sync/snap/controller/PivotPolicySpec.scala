package com.chipprbots.ethereum.blockchain.sync.snap.controller

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.UnitTest

/** B-326: the proof-of-work pivot for a heal-only recovery that must not resume on a pivot that failed finalization. */
class PivotPolicySpec extends AnyFlatSpec with Matchers:

  private val Excluded = BigInt(1_000)
  private val Offset = 64L

  private def target(peerBest: Option[BigInt], floor: Option[BigInt] = None): Option[BigInt] =
    PivotPolicy.powHealPivotAfterExcluded(Excluded, peerBest, Offset, floor)

  "PivotPolicy.powHealPivotAfterExcluded" should "take the snap peers' best minus the pivot offset when it is newer" taggedAs UnitTest in {
    target(Some(BigInt(1_200))) shouldBe Some(BigInt(1_136))
    target(Some(Excluded + Offset + 1)) shouldBe Some(Excluded + 1)
  }

  it should "give no target while the peers' best minus the offset is not strictly newer than the excluded pivot" taggedAs UnitTest in {
    target(Some(Excluded + Offset)) shouldBe None
    target(Some(Excluded)) shouldBe None
    target(Some(BigInt(10))) shouldBe None
  }

  it should "give no target when no peer height is known" taggedAs UnitTest in {
    target(None) shouldBe None
    target(Some(BigInt(0))) shouldBe None
  }

  it should "raise the target to the branch's first block while a sync is bound to one" taggedAs UnitTest in {
    // The offset would put the pivot under the branch: its first block is taken, still at or below the peers' best.
    target(Some(BigInt(1_200)), floor = Some(BigInt(1_190))) shouldBe Some(BigInt(1_190))
    // A floor under the offset target changes nothing.
    target(Some(BigInt(1_200)), floor = Some(BigInt(900))) shouldBe Some(BigInt(1_136))
    target(Some(BigInt(1_200)), floor = Some(BigInt(1_200))) shouldBe Some(BigInt(1_200))
  }

  it should "give no target when the peers' best is under the branch's first block" taggedAs UnitTest in {
    target(Some(BigInt(1_200)), floor = Some(BigInt(1_201))) shouldBe None
  }

  it should "give no target when the floor-raised target is still not newer than the excluded pivot" taggedAs UnitTest in {
    target(Some(BigInt(1_000)), floor = Some(BigInt(1_000))) shouldBe None
    target(Some(BigInt(1_050)), floor = Some(BigInt(990))) shouldBe None
  }

  // B-031: the proactive roll at 3/4 of the 128-block serve window, with a spacing after a roll that never holds the
  // next one back past that age (the fixed 50-block spacing held it to 115 with the default 64-block depth)
  "PivotPolicy.proactiveRollDue" should "roll a pivot more than 96 blocks old, 3/4 of the 128-block serve window" taggedAs UnitTest in {
    PivotPolicy.RootRollAgeBlocks shouldBe BigInt(96)
    PivotPolicy.proactiveRollDue(BigInt(96), BigInt(1_096), None, BigInt(64)) shouldBe false
    PivotPolicy.proactiveRollDue(BigInt(97), BigInt(1_097), None, BigInt(64)) shouldBe true
  }

  it should "let the next roll come at the roll age after a roll to the default depth, not at 115" taggedAs UnitTest in {
    // the last roll took the pivot 1_000 at a head of 1_064
    PivotPolicy.proactiveRollDue(BigInt(97), BigInt(1_097), Some(BigInt(1_064)), BigInt(64)) shouldBe true
    PivotPolicy.proactiveRollDue(BigInt(96), BigInt(1_096), Some(BigInt(1_064)), BigInt(64)) shouldBe false
  }

  it should "space rolls a quarter of the window apart when the configured depth is past the roll age" taggedAs UnitTest in {
    // a 200-block offset: the new pivot is already 200 old, so only the spacing stops a roll on every tick
    PivotPolicy.proactiveRollDue(BigInt(232), BigInt(1_232), Some(BigInt(1_200)), BigInt(200)) shouldBe false
    PivotPolicy.proactiveRollDue(BigInt(233), BigInt(1_233), Some(BigInt(1_200)), BigInt(200)) shouldBe true
  }

  "PivotPolicy.rollReferenceHead" should "measure on the CL head on a PoS chain once known, else on the peers' best" taggedAs UnitTest in {
    PivotPolicy.rollReferenceHead(isPoSChain = true, Some(BigInt(1_000)), BigInt(900)) shouldBe BigInt(1_000)
    PivotPolicy.rollReferenceHead(isPoSChain = true, None, BigInt(900)) shouldBe BigInt(900)
    PivotPolicy.rollReferenceHead(isPoSChain = false, Some(BigInt(1_000)), BigInt(900)) shouldBe BigInt(900)
  }
