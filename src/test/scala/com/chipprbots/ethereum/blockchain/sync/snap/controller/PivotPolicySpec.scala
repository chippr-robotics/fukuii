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
