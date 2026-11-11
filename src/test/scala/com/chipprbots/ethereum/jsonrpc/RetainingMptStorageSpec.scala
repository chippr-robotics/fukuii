package com.chipprbots.ethereum.jsonrpc

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.mpt.PositionTestKit
import com.chipprbots.ethereum.testing.Tags.*

/** A simulation's node store never removes the nodes a trie update replaced, so that the world an access-list index
  * started from stays readable (see `EthSimulateService.RetainingMptStorage`). The trie reports through `updateNodesAt`
  * since spec 018 Track C, C1, and this pins that the removals are still withheld on that route.
  */
class RetainingMptStorageSpec extends AnyFlatSpec with Matchers:

  "RetainingMptStorage" should "hand the store it wraps no removals, whatever the trie replaces" taggedAs (
    UnitTest,
    MPTTest
  ) in {
    val (removals, root, plainRoot) =
      PositionTestKit.removalsSeenThrough(new EthSimulateService.RetainingMptStorage(_))
    removals should not be empty
    all(removals) shouldBe empty
    root shouldBe plainRoot
  }
