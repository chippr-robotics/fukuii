package com.chipprbots.ethereum.db.storage.pathstate

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*

/** Spec 018 Track C, C3b (T096): what a canonical commit tells the metrics (data-model 11). */
class StateMetricsSpec extends AnyFlatSpec with Matchers:
  import PathStateStorage.*
  import PathStateTestKit.*

  private val registry = StateMetrics.metrics.registry
  private val prefix = StateMetrics.metrics.metricsPrefix
  private def gauge(name: String): Double = registry.get(s"$prefix.$name").gauge().value()
  private def counter(name: String, tags: String*): Double =
    registry.get(s"$prefix.$name").tags(tags*).counter().count()

  private def commit(
      number: Int,
      tombstone: Option[TombstoneCause] = None,
      unlisted: Int = 0,
      pruned: Int = 0
  ): Commit =
    Commit(
      block = block(number, EmptyRoot),
      positions = 7,
      stateBytes = 700,
      recordBytes = 300,
      tombstone = tombstone,
      unlistedOwners = Seq.fill(unlisted)(ByteString(Array.fill[Byte](32)(1))),
      prunedHeights = pruned,
      prunedTo = number - 2,
      nanos = 5000000L
    )

  "The state metrics" should "say which block the disk holds, what a commit wrote and what retention keeps" taggedAs UnitTest in {
    val commits = registry.get(s"$prefix.db.state.commit.seconds").timer()
    val positions = registry.get(s"$prefix.db.state.commit.positions").summary()
    val bytes = registry.get(s"$prefix.db.state.commit.bytes").summary()
    val (count, rows, written, prunedBefore) =
      (commits.count(), positions.totalAmount(), bytes.totalAmount(), counter("db.state.prune.records"))

    StateMetrics.committed(commit(40, pruned = 3))

    gauge("db.state.anchor.number") shouldBe 40d
    // heights 38, 39 and 40 keep their record
    gauge("db.state.changeset.records") shouldBe 3d
    commits.count() shouldBe count + 1
    positions.totalAmount() shouldBe rows + 7
    bytes.totalAmount() shouldBe written + 1000
    counter("db.state.prune.records") shouldBe prunedBefore + 3
  }

  it should "count a tombstone by its cause, and the tries whose rows were left" taggedAs UnitTest in {
    def byBound = counter("db.state.record.tombstones", "cause", "owner-delete-bound")
    def bySize = counter("db.state.record.tombstones", "cause", "record-size")
    def skipped = counter("db.state.dead-trie.skipped")
    val (bound, size, left) = (byBound, bySize, skipped)

    StateMetrics.committed(commit(41))
    (byBound, bySize, skipped) shouldBe (bound, size, left)

    StateMetrics.committed(commit(42, Some(TombstoneCause.OwnerDeleteBound), unlisted = 2))
    (byBound, bySize, skipped) shouldBe (bound + 1, size, left + 2)

    StateMetrics.committed(commit(43, Some(TombstoneCause.RecordSize)))
    (byBound, bySize, skipped) shouldBe (bound + 1, size + 1, left + 2)
  }

  it should "time the wait for the state lock" taggedAs UnitTest in {
    val waits = registry.get(s"$prefix.db.state.lock.wait.seconds").tags("holder", "commit").timer()
    val before = waits.count()
    StateMetrics.lockWaited(1234L)
    waits.count() shouldBe before + 1
  }
