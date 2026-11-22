package com.chipprbots.ethereum.db.storage.pathstate

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.Timer

import com.chipprbots.ethereum.metrics.Metrics

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
  *     it, which comes with the callers.)
  *   - `db.state.repair.overwrites`: rows that decode, are not the node the anchor's root names at their position, and
  *     were overwritten by a repair (data-model 7.5). Each is also logged at ERROR; on a healthy datadir this is zero;
  *   - `db.state.move.seconds`: a move of the head, from its plan to its last batch; `db.state.move.blocks`: the blocks
  *     it reversed and applied, by side; `db.state.move.batches`: how many batches it was written as;
  *   - `db.state.reorg.refused`: moves of the head that were refused, by reason. `Deep` is the one that is answered by
  *     syncing the state again; `Corrupt` means the changeset records do not describe the disk
  *
  * `db.state.anchor.lag.blocks` and `db.state.commit.degraded` arrive with what gives them a value other than zero: the
  * block importer's wiring and the overlay.
  */
object StateMetrics extends PathStateStorage.CommitObserver:
  import PathStateStorage.*

  private val anchorNumber = new AtomicLong(0L)
  private val recordsKept = new AtomicLong(0L)
  // The overlay (data-model 6.4, 11): what the last change of it left.
  private val overlayNow = new AtomicReference(OverlayUsage(0, 0L, 0, 0L, 0L))

  /** The meters, on the registry that is in use. The object is first touched when the storages are built, before
    * `Metrics.configure` installs the exported registry (B-425, as B-400 for the allowlist), so meters held from first
    * use sat in the default throwaway registry and no `db.state.*` series was exported. The set is made again when the
    * registry in use is another; registering a meter that exists returns it, so a race makes it twice and is harmless.
    * What was counted before the registry changed is not carried over.
    */
  final private class Meters(val metrics: Metrics):
    metrics.gauge("db.state.anchor.number", () => anchorNumber.get.toDouble)
    metrics.gauge("db.state.changeset.records", () => recordsKept.get.toDouble)

    val commitTimer: Timer = metrics.timer("db.state.commit.seconds")
    val commitPositions: DistributionSummary = metrics.distribution("db.state.commit.positions")
    val commitBytes: DistributionSummary = metrics.distribution("db.state.commit.bytes")
    val prunedRecords: Counter = metrics.counter("db.state.prune.records")
    val lockWait: Timer = metrics.timer("db.state.lock.wait.seconds", "holder", "commit")
    // a counter with a tag is made on the registry, which does not put the prefix before the name
    val tombstones: String = s"${metrics.metricsPrefix}.db.state.record.tombstones"
    val tombstonesByOwnerBound: Counter = metrics.registry.counter(tombstones, "cause", "owner-delete-bound")
    val tombstonesByRecordSize: Counter = metrics.registry.counter(tombstones, "cause", "record-size")
    val deadTriesSkipped: Counter = metrics.counter("db.state.dead-trie.skipped")

    metrics.gauge("db.state.overlay.forward.layers", () => overlayNow.get.layers.toDouble)
    metrics.gauge("db.state.overlay.forward.bytes", () => overlayNow.get.bytes.toDouble)
    metrics.gauge("db.state.overlay.pinned.layers", () => overlayNow.get.pinnedLayers.toDouble)
    metrics.gauge("db.state.overlay.pinned.bytes", () => overlayNow.get.pinnedBytes.toDouble)
    metrics.gauge("db.state.overlay.reverse.resident", () => overlayNow.get.reverseIndexes.toDouble)
    metrics.gauge("db.state.overlay.reverse.bytes", () => overlayNow.get.reverseBytes.toDouble)
    val overlayEvictions: Counter = metrics.counter("db.state.overlay.evictions")
    val indexBuilds: Counter = metrics.counter("db.state.overlay.reverse.builds")
    val indexBuildBytes: DistributionSummary = metrics.distribution("db.state.overlay.reverse.build.bytes")
    val indexRefusals: Counter = metrics.counter("db.state.overlay.reverse.refused")
    val readRefusals: Counter = metrics.counter("db.state.view.behind.refused")
    val viewRetries: Counter = metrics.counter("db.state.view.retries")
    val repairOverwrites: Counter = metrics.counter("db.state.repair.overwrites")
    val viewsStale: Counter = metrics.counter("db.state.view.stale")
    val moveTimer: Timer = metrics.timer("db.state.move.seconds")
    val moveBlocks: String = s"${metrics.metricsPrefix}.db.state.move.blocks"
    val moveReversed: Counter = metrics.registry.counter(moveBlocks, "side", "reversed")
    val moveApplied: Counter = metrics.registry.counter(moveBlocks, "side", "applied")
    val moveBatches: DistributionSummary = metrics.distribution("db.state.move.batches")
    val refusedName: String = s"${metrics.metricsPrefix}.db.state.reorg.refused"
    val refusals: Map[ReorgRefusal, io.micrometer.core.instrument.Counter] =
      ReorgRefusal.values.iterator
        .map(reason => reason -> metrics.registry.counter(refusedName, "reason", reason.toString))
        .toMap

    // The recovery of the state and its escalation (spec 018 T174; C5a part 6).
    val recoveryReexecuted: Counter = metrics.counter("db.state.recovery.reexecuted.blocks")
    val triggersName: String = s"${metrics.metricsPrefix}.db.state.recovery.escalation.triggers"
    val escalationTriggers: Map[EscalationKind, io.micrometer.core.instrument.Counter] =
      EscalationKind.values.iterator
        .map(kind => kind -> metrics.registry.counter(triggersName, "kind", kind.text))
        .toMap
    val escalationsName: String = s"${metrics.metricsPrefix}.db.state.recovery.escalations"
    val escalations: Map[EscalationKind, io.micrometer.core.instrument.Counter] =
      EscalationKind.values.iterator
        .map(kind => kind -> metrics.registry.counter(escalationsName, "kind", kind.text))
        .toMap
    val escalationsSuppressed: Counter = metrics.counter("db.state.recovery.escalations.suppressed")

    val pivotOffTheBranch: Counter = metrics.counter("db.state.recovery.pivot-requirement.attempts")
    val pivotRequirementEnded: Counter = metrics.counter("db.state.recovery.pivot-requirement.dropped")
    val pivotOffBranchResponses: Counter = metrics.counter("db.state.recovery.pivot-requirement.refused-responses")

  private val current = new AtomicReference[Meters](new Meters(Metrics.get()))

  private def m: Meters =
    val metrics = Metrics.get()
    val held = current.get()
    if held.metrics.eq(metrics) then held
    else
      val made = new Meters(metrics)
      if current.compareAndSet(held, made) then made else current.get()

  /** Registers every meter on the registry now in use, so each series is exported from the start (at 0). The node calls
    * it once metrics are configured; it is idempotent.
    */
  def register(): Unit =
    val _ = m

  /** A sync after an escalation found another chain at the height of the branch it was to take its pivot on. */
  def pivotRequirementMissed(): Unit = m.pivotOffTheBranch.increment()

  /** The header download did not store a response that held another block at the branch's height. */
  def pivotRequirementRefused(): Unit = m.pivotOffBranchResponses.increment()

  /** The requirement that a sync take its pivot on a branch was dropped: no peer served the branch. */
  def pivotRequirementDropped(): Unit = m.pivotRequirementEnded.increment()

  /** Blocks executed again to bring the state to a head it was not at (a recovery, not an import). */
  def reexecuted(blocks: Long): Unit = m.recoveryReexecuted.increment(blocks.toDouble)

  /** A trigger's verdict was "give the state up", for this reason: counted each time it says so, acted on or not. */
  def escalationTriggered(kind: EscalationKind): Unit = m.escalationTriggers(kind).increment()

  /** The state was given up and a sync from scratch begun. */
  def escalated(kind: EscalationKind): Unit = m.escalations(kind).increment()

  /** A verdict that was not acted on: inside the cool-down after the last escalation, or for a branch that already
    * caused one.
    */
  def escalationSuppressed(): Unit = m.escalationsSuppressed.increment()

  override def lockWaited(nanos: Long): Unit = m.lockWait.record(nanos, TimeUnit.NANOSECONDS)

  override def overlay(usage: OverlayUsage): Unit = overlayNow.set(usage)

  override def evicted(layers: Long): Unit = m.overlayEvictions.increment(layers.toDouble)

  override def indexBuilt(bytes: Long): Unit =
    m.indexBuilds.increment()
    m.indexBuildBytes.record(bytes.toDouble)

  override def indexRefused(): Unit = m.indexRefusals.increment()

  override def readRefused(): Unit = m.readRefusals.increment()

  override def viewRetried(): Unit = m.viewRetries.increment()

  override def viewStale(): Unit = m.viewsStale.increment()

  override def repairOverwrote(): Unit = m.repairOverwrites.increment()

  override def moved(move: MoveOutcome.Moved): Unit =
    anchorNumber.set(move.to.number.toLong)
    m.moveTimer.record(move.nanos, TimeUnit.NANOSECONDS)
    m.moveReversed.increment(move.reversed.toDouble)
    m.moveApplied.increment(move.applied.toDouble)
    m.moveBatches.record(move.batches.toDouble)

  override def moveRefused(reason: ReorgRefusal): Unit = m.refusals(reason).increment()

  // B-328: at open, at a genesis commit, at a hand-off; 0 when no state is held
  override def anchorAt(anchor: Option[BlockRef]): Unit = anchorNumber.set(anchor.fold(0L)(_.number.toLong))

  /** What the anchor gauge reads (tests). */
  private[pathstate] def anchorGauge: Long = anchorNumber.get

  override def committed(commit: Commit): Unit =
    anchorNumber.set(commit.block.number.toLong)
    recordsKept.set((commit.block.number - commit.prunedTo + 1).toLong)
    m.commitTimer.record(commit.nanos, TimeUnit.NANOSECONDS)
    m.commitPositions.record(commit.positions.toDouble)
    m.commitBytes.record((commit.stateBytes + commit.recordBytes).toDouble)
    if commit.prunedHeights > 0 then m.prunedRecords.increment(commit.prunedHeights.toDouble)
    commit.tombstone.foreach {
      case TombstoneCause.OwnerDeleteBound => m.tombstonesByOwnerBound.increment()
      case TombstoneCause.RecordSize       => m.tombstonesByRecordSize.increment()
    }
    if commit.unlistedOwners.nonEmpty then m.deadTriesSkipped.increment(commit.unlistedOwners.size.toDouble)
