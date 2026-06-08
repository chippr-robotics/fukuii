package com.chipprbots.ethereum.blockchain.sync.snap.actors

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags._

import java.nio.ByteBuffer

/** Unit tests for the heap-bounded `visited` set used by the post-SNAP frontier-rebuild DFS
  * (`TrieNodeHealingCoordinator.boundedVisitedSet`).
  *
  * These pin the Layer-1 OOM fix (docs/design/healing-frontier-scale.md): the DFS `visited` set MUST stay bounded by
  * its cap regardless of how many nodes the walk touches (INV-3), with insertion-order eviction (earliest-inserted /
  * earliest-completed subtries dropped first). Completeness of the rebuilt frontier (INV-1) is provided independently
  * by `pendingHashSet` de-duplication and is exercised by the coordinator-level healing tests.
  */
class FrontierRebuildSpec extends AnyFlatSpec with Matchers {

  private def hash(i: Int): ByteString =
    ByteString(ByteBuffer.allocate(4).putInt(i).array())

  "boundedVisitedSet" should "never exceed its capacity as entries are added" taggedAs UnitTest in {
    forCaps(Seq(1, 16, 100, 4096)) { cap =>
      val visited = TrieNodeHealingCoordinator.boundedVisitedSet(cap)
      // Add far more distinct entries than the cap — emulates a walk over millions of nodes.
      (0 until cap * 4).foreach { i =>
        visited += hash(i)
        visited.size should be <= cap
      }
      visited.size shouldBe cap
    }
  }

  it should "evict the earliest-inserted entries first (insertion-order LRU)" taggedAs UnitTest in {
    val cap = 50
    val total = 200
    val visited = TrieNodeHealingCoordinator.boundedVisitedSet(cap)
    (0 until total).foreach(i => visited += hash(i))

    // The most recent `cap` entries are retained; everything older is evicted.
    (total - cap until total).foreach(i => visited should contain(hash(i)))
    (0 until total - cap).foreach(i => visited should not contain hash(i))
  }

  it should "behave as a set — re-adding an existing entry does not grow it past the cap" taggedAs UnitTest in {
    val cap = 32
    val visited = TrieNodeHealingCoordinator.boundedVisitedSet(cap)
    (0 until cap).foreach(i => visited += hash(i))
    visited.size shouldBe cap

    // Re-discovering already-visited nodes (shared references) must not grow the set.
    (0 until cap).foreach(i => visited += hash(i))
    visited.size shouldBe cap
    visited should contain(hash(0))
  }

  it should "report membership correctly for present and absent entries" taggedAs UnitTest in {
    val visited = TrieNodeHealingCoordinator.boundedVisitedSet(8)
    visited += hash(1)
    visited.contains(hash(1)) shouldBe true
    visited.contains(hash(999)) shouldBe false
  }

  private def forCaps(caps: Seq[Int])(check: Int => Unit): Unit = caps.foreach(check)
}
