package com.chipprbots.ethereum.blockchain.sync.snap.actors

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags._

import java.nio.ByteBuffer

/** Unit tests for the Bloom-filter visited set used by the post-SNAP BFS frontier-rebuild walk
  * (`TrieNodeHealingCoordinator.bfsVisitedFilter`).
  *
  * These pin the correctness contract for the Layer-1 BFS visited set:
  *   - No false negatives: a node that was put is always reported as seen (INV-3 / walk completeness).
  *   - Re-discovery returns false: the `markIfNew` idiom must gate duplicate enqueuing.
  *   - Monotonically growing: no eviction occurs regardless of how many nodes are inserted.
  */
class FrontierRebuildSpec extends AnyFlatSpec with Matchers {

  private def hash(i: Int): Array[Byte] =
    ByteBuffer.allocate(4).putInt(i).array()

  "bfsVisitedFilter" should "never report false negatives for inserted entries" taggedAs UnitTest in {
    val filter = TrieNodeHealingCoordinator.bfsVisitedFilter(expectedInsertions = 10_000L)
    val entries = (0 until 1_000).map(hash)
    entries.foreach(filter.put)
    entries.foreach { e => filter.mightContain(e) shouldBe true }
  }

  it should "report mightContain = false for entries that were never inserted (with low FPR)" taggedAs UnitTest in {
    val filter = TrieNodeHealingCoordinator.bfsVisitedFilter(expectedInsertions = 10_000L, fpp = 0.001)
    (0 until 500).foreach(i => filter.put(hash(i)))
    // Entries in [1000, 2000) were never inserted; at 0.1% FPR virtually none should trigger.
    val falsePositives = (1_000 until 2_000).count(i => filter.mightContain(hash(i)))
    falsePositives should be < 5 // 0 expected at 0.1%; allow a tiny margin
  }

  it should "simulate markIfNew: return false on re-encounter, true on first encounter" taggedAs UnitTest in {
    val filter = TrieNodeHealingCoordinator.bfsVisitedFilter(expectedInsertions = 1_000L)

    def markIfNew(h: Array[Byte]): Boolean =
      if (filter.mightContain(h)) false
      else { filter.put(h); true }

    val h1 = hash(42)
    markIfNew(h1) shouldBe true  // first encounter — new
    markIfNew(h1) shouldBe false // second encounter — already seen
    markIfNew(h1) shouldBe false // idempotent
  }

  it should "accumulate without bound — no eviction up to expected capacity" taggedAs UnitTest in {
    // Insert exactly expectedInsertions entries; all must still be present (no eviction).
    val n      = 50_000
    val filter = TrieNodeHealingCoordinator.bfsVisitedFilter(expectedInsertions = n.toLong)
    (0 until n).foreach(i => filter.put(hash(i)))
    val misses = (0 until n).count(i => !filter.mightContain(hash(i)))
    misses shouldBe 0 // zero false negatives — the invariant that ensures walk correctness
  }

  it should "be thread-safe under concurrent puts and mightContain queries" taggedAs UnitTest in {
    import scala.concurrent.{Await, Future}
    import scala.concurrent.ExecutionContext.Implicits.global
    import scala.concurrent.duration._

    val n      = 10_000
    val filter = TrieNodeHealingCoordinator.bfsVisitedFilter(expectedInsertions = n.toLong)

    val writers  = Future.traverse((0 until n / 2).toList)(i => Future(filter.put(hash(i))))
    val readers  = Future.traverse((0 until n / 2).toList)(i => Future(filter.mightContain(hash(i))))
    Await.result(writers.zip(readers), 10.seconds)

    // After writes complete, every written entry must be present.
    (0 until n / 2).foreach(i => filter.mightContain(hash(i)) shouldBe true)
  }
}
