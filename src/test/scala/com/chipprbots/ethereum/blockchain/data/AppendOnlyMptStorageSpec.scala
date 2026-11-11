package com.chipprbots.ethereum.blockchain.data

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.mpt.PositionTestKit
import com.chipprbots.ethereum.testing.Tags.*

/** The genesis loader's node store drops what a trie update reports as replaced (see `AppendOnlyMptStorage`: two
  * accounts whose storage tries share a node would otherwise delete each other's). The trie reports through
  * `updateNodesAt` since spec 018 Track C, C1, and this pins that the removals are still dropped on that route.
  */
class AppendOnlyMptStorageSpec extends AnyFlatSpec with Matchers:

  "AppendOnlyMptStorage" should "hand the store it wraps no removals, whatever the trie replaces" taggedAs (
    UnitTest,
    MPTTest
  ) in {
    val (removals, root, plainRoot) = PositionTestKit.removalsSeenThrough(GenesisDataLoader.AppendOnlyMptStorage(_))
    removals should not be empty
    all(removals) shouldBe empty
    root shouldBe plainRoot
  }
