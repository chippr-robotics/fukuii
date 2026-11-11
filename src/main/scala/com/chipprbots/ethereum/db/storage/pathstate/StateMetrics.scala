package com.chipprbots.ethereum.db.storage.pathstate

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

import com.chipprbots.ethereum.metrics.MetricsContainer

/** The metrics of the unified state layout that belong to its commit (spec 018 data-model 11), as an observer of the
  * path state storage. Prometheus shows the names with `app_` before them and `_` for each dot.
  *
  *   - `db.state.anchor.number`: the block whose state the disk holds;
  *   - `db.state.commit.seconds`: the canonical commit's batch;
  *   - `db.state.commit.positions`, `db.state.commit.bytes`: rows written by a block, and their bytes with the
  *     record's;
  *   - `db.state.changeset.records`: heights that keep their changeset record;
  *   - `db.state.prune.records`: heights whose records retention has deleted;
  *   - `db.state.lock.wait.seconds`: time spent waiting for the state lock;
  *   - `db.state.record.tombstones`: records written with no entries, by cause. '''A head move cannot cross one''', so
  *     on a chain that follows a consensus client this must stay at zero;
  *   - `db.state.dead-trie.skipped`: replaced storage tries whose rows were left in place.
  *
  * `db.state.anchor.lag.blocks` and `db.state.commit.degraded` arrive with what gives them a value other than zero: the
  * block importer's wiring and the overlay.
  */
object StateMetrics extends MetricsContainer with PathStateStorage.CommitObserver:
  import PathStateStorage.*

  private val anchorNumber = new AtomicLong(0L)
  private val recordsKept = new AtomicLong(0L)
  metrics.gauge("db.state.anchor.number", () => anchorNumber.get.toDouble)
  metrics.gauge("db.state.changeset.records", () => recordsKept.get.toDouble)

  private val commitTimer = metrics.timer("db.state.commit.seconds")
  private val commitPositions = metrics.distribution("db.state.commit.positions")
  private val commitBytes = metrics.distribution("db.state.commit.bytes")
  private val prunedRecords = metrics.counter("db.state.prune.records")
  private val lockWait = metrics.timer("db.state.lock.wait.seconds", "holder", "commit")
  // a counter with a tag is made on the registry, which does not put the prefix before the name
  private val tombstones = s"${metrics.metricsPrefix}.db.state.record.tombstones"
  private val tombstonesByOwnerBound = metrics.registry.counter(tombstones, "cause", "owner-delete-bound")
  private val tombstonesByRecordSize = metrics.registry.counter(tombstones, "cause", "record-size")
  private val deadTriesSkipped = metrics.counter("db.state.dead-trie.skipped")

  override def lockWaited(nanos: Long): Unit = lockWait.record(nanos, TimeUnit.NANOSECONDS)

  override def committed(commit: Commit): Unit =
    anchorNumber.set(commit.block.number.toLong)
    recordsKept.set((commit.block.number - commit.prunedTo + 1).toLong)
    commitTimer.record(commit.nanos, TimeUnit.NANOSECONDS)
    commitPositions.record(commit.positions.toDouble)
    commitBytes.record((commit.stateBytes + commit.recordBytes).toDouble)
    if commit.prunedHeights > 0 then prunedRecords.increment(commit.prunedHeights.toDouble)
    commit.tombstone.foreach {
      case TombstoneCause.OwnerDeleteBound => tombstonesByOwnerBound.increment()
      case TombstoneCause.RecordSize       => tombstonesByRecordSize.increment()
    }
    if commit.unlistedOwners.nonEmpty then deadTriesSkipped.increment(commit.unlistedOwners.size.toDouble)
