package com.chipprbots.ethereum.db.storage.pathstate

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

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
  *   - `db.state.dead-trie.skipped`: replaced storage tries whose rows were left in place;
  *   - `db.state.overlay.forward.layers`, `.bytes`: the executed blocks kept beside the disk that no branch pins, and
  *     what they hold; `db.state.overlay.pinned.layers`, `.bytes`: those a branch under execution holds;
  *   - `db.state.overlay.reverse.resident`, `.bytes`: the reverse indexes that are kept, and what they hold;
  *     `db.state.overlay.reverse.builds`, `.build.bytes`: indexes that were built because a state behind the head, or
  *     on a fork, was asked for and none was kept; `db.state.overlay.reverse.refused`: readers told to ask again
  *     because as many indexes as readers may build at once were being built;
  *   - `db.state.overlay.evictions`: executed blocks that were evicted to make room, and are executed again if their
  *     state is needed;
  *   - `db.state.view.retries`, `db.state.view.stale`: views that were made again because the disk moved meanwhile, and
  *     those that could not be made for it. (A read that goes stale after its view was made is counted by what catches
  *     it, which comes with the callers; `db.state.move.seconds` comes with the head move.)
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

  // The overlay (data-model 6.4, 11): what the last change of it left.
  private val overlayNow = new AtomicReference(OverlayUsage(0, 0L, 0, 0L, 0L))
  metrics.gauge("db.state.overlay.forward.layers", () => overlayNow.get.layers.toDouble)
  metrics.gauge("db.state.overlay.forward.bytes", () => overlayNow.get.bytes.toDouble)
  metrics.gauge("db.state.overlay.pinned.layers", () => overlayNow.get.pinnedLayers.toDouble)
  metrics.gauge("db.state.overlay.pinned.bytes", () => overlayNow.get.pinnedBytes.toDouble)
  metrics.gauge("db.state.overlay.reverse.resident", () => overlayNow.get.reverseIndexes.toDouble)
  metrics.gauge("db.state.overlay.reverse.bytes", () => overlayNow.get.reverseBytes.toDouble)
  private val overlayEvictions = metrics.counter("db.state.overlay.evictions")
  private val indexBuilds = metrics.counter("db.state.overlay.reverse.builds")
  private val indexBuildBytes = metrics.distribution("db.state.overlay.reverse.build.bytes")
  private val indexRefusals = metrics.counter("db.state.overlay.reverse.refused")
  private val viewRetries = metrics.counter("db.state.view.retries")
  private val viewsStale = metrics.counter("db.state.view.stale")

  override def lockWaited(nanos: Long): Unit = lockWait.record(nanos, TimeUnit.NANOSECONDS)

  override def overlay(usage: OverlayUsage): Unit = overlayNow.set(usage)

  override def evicted(layers: Long): Unit = overlayEvictions.increment(layers.toDouble)

  override def indexBuilt(bytes: Long): Unit =
    indexBuilds.increment()
    indexBuildBytes.record(bytes.toDouble)

  override def indexRefused(): Unit = indexRefusals.increment()

  override def viewRetried(): Unit = viewRetries.increment()

  override def viewStale(): Unit = viewsStale.increment()

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
