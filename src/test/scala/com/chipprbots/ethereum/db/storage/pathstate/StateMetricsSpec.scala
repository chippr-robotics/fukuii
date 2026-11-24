package com.chipprbots.ethereum.db.storage.pathstate

import org.apache.pekko.util.ByteString

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.metrics.Metrics
import com.chipprbots.ethereum.testing.Tags.*

/** Spec 018 Track C, C3b (T096): what a canonical commit tells the metrics (data-model 11). */
class StateMetricsSpec extends AnyFlatSpec with Matchers:
  import PathStateStorage.*
  import PathStateTestKit.*

  private def registry = Metrics.get().registry
  private def prefix = Metrics.get().metricsPrefix
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

  "The state metrics" should "count a refused move of the head under its reason, every reason" taggedAs UnitTest in {
    ReorgRefusal.values.foreach { reason =>
      withClue(s"$reason: ") {
        StateMetrics.moveRefused(reason)
        val before = counter("db.state.reorg.refused", "reason", reason.toString)
        StateMetrics.moveRefused(reason)
        counter("db.state.reorg.refused", "reason", reason.toString) shouldBe before + 1
      }
    }
    // and a move: the blocks it reversed and applied, how many batches, how long, and the block the disk then holds
    val reversed = counter("db.state.move.blocks", "side", "reversed")
    val applied = counter("db.state.move.blocks", "side", "applied")
    val moves = registry.get(s"$prefix.db.state.move.seconds").timer().count()
    StateMetrics.moved(
      MoveOutcome.Moved(block(9, EmptyRoot), block(12, EmptyRoot), block(8, EmptyRoot), 1, 4, 4, 2, 7L)
    )
    counter("db.state.move.blocks", "side", "reversed") shouldBe reversed + 1
    counter("db.state.move.blocks", "side", "applied") shouldBe applied + 4
    registry.get(s"$prefix.db.state.move.seconds").timer().count() shouldBe moves + 1
    gauge("db.state.anchor.number") shouldBe 12d
  }

  it should "say which block the disk holds, what a commit wrote and what retention keeps" taggedAs UnitTest in {
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

  it should "say what the overlay holds, count its evictions as they are told, and count the views made again" taggedAs
    UnitTest in {
      def evictions = counter("db.state.overlay.evictions")
      val before = evictions
      StateMetrics.overlay(OverlayUsage(3, 300L, 2, 200L, evictions = 1000L, reverseIndexes = 1, reverseBytes = 50L))
      // what the store has evicted since it was opened is not what the counter counts
      evictions shouldBe before
      gauge("db.state.overlay.forward.layers") shouldBe 3d
      gauge("db.state.overlay.forward.bytes") shouldBe 300d
      gauge("db.state.overlay.pinned.layers") shouldBe 2d
      gauge("db.state.overlay.pinned.bytes") shouldBe 200d
      gauge("db.state.overlay.reverse.resident") shouldBe 1d
      gauge("db.state.overlay.reverse.bytes") shouldBe 50d

      StateMetrics.evicted(2)
      StateMetrics.overlay(OverlayUsage(1, 100L, 0, 0L, evictions = 1002L))
      evictions shouldBe before + 2
      gauge("db.state.overlay.forward.layers") shouldBe 1d
      gauge("db.state.overlay.pinned.bytes") shouldBe 0d

      val (retries, stale) = (counter("db.state.view.retries"), counter("db.state.view.stale"))
      StateMetrics.viewRetried()
      StateMetrics.viewRetried()
      StateMetrics.viewStale()
      (counter("db.state.view.retries"), counter("db.state.view.stale")) shouldBe (retries + 2, stale + 1)

      val built = registry.get(s"$prefix.db.state.overlay.reverse.build.bytes").summary()
      val (builds, bytesBuilt, refused) =
        (counter("db.state.overlay.reverse.builds"), built.totalAmount(), counter("db.state.overlay.reverse.refused"))
      StateMetrics.indexBuilt(300L)
      StateMetrics.indexBuilt(200L)
      StateMetrics.indexRefused()
      val readsRefused = counter("db.state.view.behind.refused")
      StateMetrics.readRefused()
      counter("db.state.view.behind.refused") shouldBe readsRefused + 1
      counter("db.state.overlay.reverse.builds") shouldBe builds + 2
      built.totalAmount() shouldBe bytesBuilt + 500
      counter("db.state.overlay.reverse.refused") shouldBe refused + 1
    }

  it should "time the wait for the state lock" taggedAs UnitTest in {
    val waits = registry.get(s"$prefix.db.state.lock.wait.seconds").tags("holder", "commit").timer()
    val before = waits.count()
    StateMetrics.lockWaited(1234L)
    waits.count() shouldBe before + 1
  }

  // spec 018 T174 (C5a part 6)
  it should "count a trigger's verdict and an escalation under their kind, a suppressed one, and blocks executed again" taggedAs UnitTest in {
    EscalationKind.values.foreach { kind =>
      withClue(s"$kind: ") {
        val triggers = counter("db.state.recovery.escalation.triggers", "kind", kind.text)
        val made = counter("db.state.recovery.escalations", "kind", kind.text)
        StateMetrics.escalationTriggered(kind)
        StateMetrics.escalationTriggered(kind)
        StateMetrics.escalated(kind)
        counter("db.state.recovery.escalation.triggers", "kind", kind.text) shouldBe triggers + 2
        counter("db.state.recovery.escalations", "kind", kind.text) shouldBe made + 1
      }
    }
    val suppressed = registry.get(s"$prefix.db.state.recovery.escalations.suppressed").counter().count()
    StateMetrics.escalationSuppressed()
    registry.get(s"$prefix.db.state.recovery.escalations.suppressed").counter().count() shouldBe suppressed + 1

    val again = registry.get(s"$prefix.db.state.recovery.reexecuted.blocks").counter().count()
    StateMetrics.reexecuted(7)
    registry.get(s"$prefix.db.state.recovery.reexecuted.blocks").counter().count() shouldBe again + 7
  }

  it should "register its meters in the registry that becomes the default after it was touched (B-425)" taggedAs UnitTest in {
    // the storages touch the object while the node is still building, before Metrics.configure installs the exported
    // registry, which becomes the default; stood in for here so that the JVM's default is put back after
    StateMetrics.anchorAt(None)
    val exported = Metrics("app", new SimpleMeterRegistry())
    Metrics.usingDefault(exported) {
      StateMetrics.register()
      val registry = exported.registry
      Seq("db.state.anchor.number", "db.state.overlay.forward.layers").foreach { name =>
        withClue(s"$name: ")(registry.find(s"app.$name").gauge() should not be null)
      }
      Seq("db.state.commit.seconds", "db.state.move.seconds").foreach { name =>
        withClue(s"$name: ")(registry.find(s"app.$name").timer() should not be null)
      }
      registry.find("app.db.state.reorg.refused").tag("reason", ReorgRefusal.values.head.toString).counter() should
        not be null
      // and what happens afterwards is counted there, with no call of register
      StateMetrics.committed(commit(5))
      registry.get("app.db.state.commit.seconds").timer().count() shouldBe 1L
      registry.get("app.db.state.anchor.number").gauge().value() shouldBe 5d
    }
  }

  // B-462: the age of the oldest attempt that holds the records, 0 when none is open
  it should "read the age of the open attempt, and 0 once none is" taggedAs UnitTest in {
    StateMetrics.register()
    StateMetrics.attemptOpenSince(Some(1000000L))
    StateMetrics.attemptAgeSeconds(1090000L) shouldBe 90d
    StateMetrics.attemptAgeSeconds(999000L) shouldBe 0d
    // exported from the start
    registry.find(s"$prefix.db.state.attempt.age.seconds").gauge() should not be null
    StateMetrics.attemptOpenSince(None)
    StateMetrics.attemptAgeSeconds(1090000L) shouldBe 0d
  }
