package com.chipprbots.ethereum.ledger

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.mpt.PositionTestKit
import com.chipprbots.ethereum.testing.Tags.*

/** An Amsterdam replay's node store keeps every node an update would remove, so that each world of the replay stays
  * readable (see `StxLedger.ReplayMptStorage`). The trie reports through `updateNodesAt` since spec 018 Track C, C1,
  * and this pins that the removals are still withheld on that route.
  */
class ReplayMptStorageSpec extends AnyFlatSpec with Matchers:

  "ReplayMptStorage" should "hand the store it wraps no removals, whatever the trie replaces" taggedAs (
    UnitTest,
    MPTTest
  ) in {
    val (removals, root, plainRoot) = PositionTestKit.removalsSeenThrough(new StxLedger.ReplayMptStorage(_))
    removals should not be empty
    all(removals) shouldBe empty
    root shouldBe plainRoot
  }

  // Spec 018 C2a (T041): the view of one trie is wrapped the same way, so a trie built over `forOwner` keeps its
  // replaced nodes too.
  it should "withhold removals from the view of one trie as well" taggedAs (UnitTest, MPTTest) in {
    val seen = PositionTestKit.removalsSeenThroughOwnerView(new StxLedger.ReplayMptStorage(_))
    seen should not be empty
    all(seen) shouldBe 0
  }
