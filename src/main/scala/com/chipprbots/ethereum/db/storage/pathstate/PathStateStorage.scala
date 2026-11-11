package com.chipprbots.ethereum.db.storage.pathstate

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.db.dataSource.DataSource
import com.chipprbots.ethereum.db.dataSource.DataSourceBatchUpdate
import com.chipprbots.ethereum.db.dataSource.DataUpdate
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.DecodedNodeCache
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.db.storage.PathNodeStorage
import com.chipprbots.ethereum.db.storage.PrefetchNodeReader

/** The state of the unified layout (spec 018 Track C): the position-keyed rows of CF `t` and CF `u`, the anchor that
  * says which block's state they hold, and the views a reader takes of them.
  *
  * '''Reads.''' It serves the state of the anchor and nothing else: the states of other blocks come with the overlay
  * (C4a).
  *
  * '''Writes.''' One: the commit of a block that extends the anchor ([[stage]], then [[commitCanonical]]), which moves
  * the anchor to that block in the batch that writes its rows. A head move and a repair come later (C4b, C5b), and are
  * the other holders of [[lock]].
  *
  * '''One of these for a data source.''' The clock and the lock are this object's: a second one over the same data
  * source would have its own, and neither would see the other's moves. The node builds one, in `Storages`.
  *
  * It exists only behind the in-development guard `fukuii.state.path-live`.
  *
  * @param maxViewAgeMoves
  *   how many state moves a view outlives (`state.overlay.max-view-age-moves`)
  * @param settings
  *   what a commit is bounded by, keeps and reports
  */
final class PathStateStorage(
    rows: PathNodeStorage,
    appState: AppStateStorage,
    maxViewAgeMoves: Int = 8,
    settings: PathStateStorage.CommitSettings = PathStateStorage.CommitSettings()
):
  import PathStateStorage.*

  private val dataSource: DataSource = rows.dataSource
  require(appState.dataSource eq dataSource, "the anchor and the rows it describes are written in one batch")

  private val changesets = new ChangesetStorage(dataSource)

  /** The clock of in-place moves, started from the anchor the datadir holds. */
  val clock: StateMoveLog = new StateMoveLog(maxViewAgeMoves, appState.getStateAnchor())

  /** The lock of the in-place writers (data-model 7.0). */
  val lock: StateLock = new StateLock

  /** The block whose state the disk holds, or `None` when the datadir has no state yet (SNAP in progress). */
  def anchor: Option[BlockRef] = clock.anchor

  /** Whether a view of `block`'s state can be made right now. */
  def stateAvailable(block: BlockRef): Boolean = anchor.contains(block)

  /** A view of `block`'s state, made at a moment when the disk is at rest: a view is made at an even epoch (data-model
    * 6.3.1), so one made while a batch is being written waits for that batch.
    *
    * @throws StateUnavailable
    *   when `block` is not the anchor, or there is no anchor
    */
  def view(block: BlockRef): StateView =
    val (epoch, held) = clock.awaitRest()
    held match
      case Some(`block`) => new StateView(block, epoch, clock, rows)
      case Some(other) =>
        throw new StateUnavailable(s"the state of block ${block.show} is not held: the disk holds ${other.show}")
      case None => throw new StateUnavailable(s"the state of block ${block.show} is not held: there is no state anchor")

  /** `block`'s state for a reader that writes nothing. */
  def readOnly(block: BlockRef): MptStorage = PathMptStorage.readOnly(view(block))

  /** `block`'s state for an execution that commits nothing: what it persists is kept beside the view and dropped with
    * the storage (`eth_call`, a simulation, a trace, a payload being built).
    */
  def throwaway(block: BlockRef): MptStorage = PathMptStorage.throwaway(view(block))

  /** A reader of `parent`'s state for the access-list prefetch, or `None` when that state cannot be viewed: a prefetch
    * only warms caches, and a block whose parent is not the anchor simply executes without one.
    */
  def prefetchReader(
      parent: BlockRef,
      cache: Option[DecodedNodeCache],
      budgetBytes: Long,
      onLoaded: ByteString => Unit
  ): Option[PrefetchNodeReader] =
    try Some(PathPrefetchReader(view(parent), cache, budgetBytes, onLoaded))
    catch case _: StateFault => None

  /** Whether `blockHash` is the block whose state the disk holds: its changes are committed and in force. */
  def isApplied(blockHash: ByteString): Boolean = anchor.exists(_.hash == blockHash)

  /** A fresh layer over `parent`'s state for one execution of a block that builds on it.
    *
    * @throws StateUnavailable
    *   when `parent` is not the anchor, or there is no anchor
    */
  def stage(parent: BlockRef): PathStagedState = new PathStagedState(parent, view(parent))

  /** Commits `block`, executed on `staged`, as the block whose state the disk holds (data-model 7.1): '''one batch'''
    * of the caller's own updates (`extra`: the block, its receipts and weight, the best-block pointer), the rows the
    * block changed, its changeset record and the index of its height, the records retention deletes, and the anchor. It
    * is written under the state lock, as a move of the clock.
    *
    *   - '''The block is the anchor already''': the state half is dropped and `extra` alone is written. This is the
    *     only case a caller with nothing staged can be in: one that found the block executed and applied, and has only
    *     the block's own records to write. Whether it is still applied is decided here, under the lock.
    *   - '''There is no anchor''', or the anchor is not the block `staged` was built on: refused, with nothing written.
    *     Until the overlay (C4a) a block is committed in place or not at all.
    *
    * A failure of the write leaves the disk as it was, and is the storage's failure, thrown as it came: it is never a
    * verdict on the block.
    *
    * @param block
    *   the block being committed, with the state root its header carries
    * @throws StateUnavailable
    *   when the block does not extend the anchor, or there is no anchor
    */
  def commitCanonical(
      staged: Option[PathStagedState],
      block: BlockRef,
      extra: Option[DataSourceBatchUpdate]
  ): CommitOutcome =
    extra.foreach(other =>
      require(other.dataSource eq dataSource, "a block and its state are written in one batch, to one data source")
    )
    lock.locked(s"canonical commit of block ${block.show}", settings.observer.lockWaited) {
      // Every in-place writer holds this lock, so the clock is at rest and the anchor is the disk's.
      clock.anchor match
        case None =>
          throw new StateUnavailable(
            s"block ${block.show} cannot be committed: there is no state anchor, so the disk holds no block's state"
          )
        case Some(held) if held.hash == block.hash =>
          extra.foreach(_.commit())
          CommitOutcome.AlreadyApplied
        case Some(held) =>
          staged match
            case Some(executed) if executed.parent == held => commitInPlace(executed, held, block, extra)
            case Some(executed) =>
              throw new StateUnavailable(
                s"block ${block.show} was executed on the state of ${executed.parent.show}, " +
                  s"and the disk holds ${held.show}"
              )
            case None =>
              throw new StateUnavailable(
                s"block ${block.show} was not executed for this commit and is not the block the disk holds, " +
                  s"which is ${held.show}"
              )
    }

  private def commitInPlace(
      staged: PathStagedState,
      parent: BlockRef,
      block: BlockRef,
      extra: Option[DataSourceBatchUpdate]
  )(using StateLockHeld): CommitOutcome =
    require(block.number == parent.number + 1, s"block ${block.show} does not follow ${parent.show}")

    // The rows of each storage trie the block replaced, as the disk holds them: the parent's (data-model 3.6). Scanned
    // here, under the lock, so that they are the rows this batch is written over.
    val scanLimit = settings.maxEntriesPerOwnerDelete.min(Int.MaxValue - 1L).toInt
    val finalised = staged.changes.finalise { owner =>
      val scanned = rows.storageRows(owner, scanLimit + 1)
      Option.when(scanned.sizeIs <= scanLimit)(scanned.map((position, node) => position -> ByteString(node)))
    }

    val diff = BlockDiff(block.hash, block.number, parent.hash, parent.stateRoot, block.stateRoot, finalised.changes)
    // The account trie's root row is held to the two state roots: a layer that is not this block's final world, or not
    // built on this parent, stops here, before a row is written.
    val faults = diff.validate
    if faults.nonEmpty then
      throw new IllegalStateException(
        s"The staged changes of block ${block.show} are not what a block does to the state of ${parent.show}: " +
          faults.take(MaxFaultsShown).mkString("; ")
      )

    val tombstone: Option[TombstoneCause] =
      if finalised.unlisted.nonEmpty then Some(TombstoneCause.OwnerDeleteBound)
      else if BlockDiffCodec.encodedLength(diff) > settings.maxRecordBytes then Some(TombstoneCause.RecordSize)
      else None
    val record = if tombstone.isEmpty then diff else diff.copy(changes = Map.empty, incomplete = true)

    // What the batch leaves at each position it writes, and what a reader of the parent's state must go on seeing
    // there. For a trie whose old rows were not listed, the second is read: one point read for each row written.
    val unrecorded = finalised.unrecorded
    val written: Map[PositionKey, Option[ByteString]] =
      diff.changes.view.mapValues(_.next).toMap ++ unrecorded.view.mapValues(Some(_))
    val before: Map[PositionKey, Option[ByteString]] =
      diff.changes.view.mapValues(_.prior).toMap ++
        unrecorded.keysIterator.map(position => position -> rows.read(position).map(ByteString(_)))

    val (changesetUpdates, prunedHeights, prunedTo) = changesetIndexed(block) {
      val pruned = pruning(block)
      // The deletes come before the record: a batch applies its updates in order, and no delete may follow the write
      // of a key it names.
      val moved = pruned.movedTo.toSeq.flatMap(appState.putChangesetPrunedTo(_).updates)
      (pruned.deletes ++ changesets.recordUpdates(record) ++ moved, pruned.heights, pruned.reached)
    }

    val updates: Seq[DataUpdate] =
      extra.toSeq.flatMap(_.updates) ++ rows.rowUpdates(written) ++ changesetUpdates ++
        appState.putStateAnchor(block).updates

    val started = System.nanoTime()
    // Nothing but the one write happens between the move's announcement and its end: a view asked for in there would
    // wait for this thread.
    clock.inFlight(StateMove(block, before))(dataSource.update(updates))

    val commit = Commit(
      block = block,
      positions = written.size,
      stateBytes = written.iterator.map((position, node) => position.rowKey.length.toLong + node.fold(0)(_.length)).sum,
      recordBytes = BlockDiffCodec.encodedLength(record),
      tombstone = tombstone,
      unlistedOwners = finalised.unlisted,
      prunedHeights = prunedHeights,
      prunedTo = prunedTo,
      nanos = System.nanoTime() - started
    )
    settings.observer.committed(commit)
    CommitOutcome.Applied(commit)

  /** What retention does in this block's batch. The first block committed on a datadir starts the count at itself: no
    * height below it has a record.
    */
  private def pruning(block: BlockRef)(using StateLockHeld): Pruning =
    appState.getChangesetPrunedTo() match
      case None => Pruning(Nil, movedTo = Some(block.number), reached = block.number, heights = 0)
      case Some(from) =>
        settings.retention.floor(block.number, settings.finalized()) match
          case Some(floor) if floor > from =>
            val (deletes, next) = changesets.pruneUpdates(from, floor, settings.retention.heightsPerBatch(floor - from))
            Pruning(deletes, Option.when(next > from)(next), reached = next, heights = (next - from).toInt)
          case _ => Pruning(Nil, movedTo = None, reached = from, heights = 0)

  /** `build`, which reads the index of changeset records. An index that cannot be read is this datadir's corruption,
    * and is said as that: it is no fault of the block.
    */
  private def changesetIndexed[A](block: BlockRef)(build: => A): A =
    try build
    catch
      case e: IllegalArgumentException =>
        throw new IllegalStateException(
          s"The index of changeset records is corrupt; block ${block.show} was not committed: ${e.getMessage}",
          e
        )

object PathStateStorage:

  private val MaxFaultsShown = 3

  /** What retention does in one batch: the records it deletes, the height the count of pruned heights moves to when it
    * moves, the height it has then reached, and how many heights that is.
    */
  final private case class Pruning(deletes: Seq[DataUpdate], movedTo: Option[BigInt], reached: BigInt, heights: Int)

  /** What a commit is bounded by, keeps and reports (the `state` section of the configuration, data-model 10).
    *
    * @param maxEntriesPerOwnerDelete
    *   a replaced storage trie with more rows than this keeps them (`state.changeset.max-entries-per-owner-delete`)
    * @param maxRecordBytes
    *   a changeset record above this is written as a tombstone (`state.changeset.max-record-bytes`)
    * @param finalized
    *   the number of the finalized block, where a consensus client names one
    */
  final case class CommitSettings(
      maxEntriesPerOwnerDelete: Long = 1000000L,
      maxRecordBytes: Long = 256L * 1024 * 1024,
      retention: RetentionPolicy = RetentionPolicy.KeepAll,
      finalized: () => Option[BigInt] = () => None,
      observer: CommitObserver = CommitObserver.Silent
  )

  object CommitSettings:
    def of(config: StateConfig, finalized: () => Option[BigInt], observer: CommitObserver): CommitSettings =
      CommitSettings(
        config.changeset.maxEntriesPerOwnerDelete,
        config.changeset.maxRecordBytes,
        RetentionPolicy(config),
        finalized,
        observer
      )

  /** Why a block's record is a tombstone: it has no entries, and no head move may cross it (data-model 3.6). */
  enum TombstoneCause:
    /** A storage trie it replaced has more rows than may be listed; they were left in place. */
    case OwnerDeleteBound

    /** Its record would be larger than a record may be. Its rows were all written. */
    case RecordSize

  /** One canonical commit, as it was written.
    *
    * @param positions
    *   rows written or deleted
    * @param prunedHeights
    *   heights whose records retention deleted in this batch
    * @param prunedTo
    *   the lowest height that may still hold a record
    */
  final case class Commit(
      block: BlockRef,
      positions: Int,
      stateBytes: Long,
      recordBytes: Long,
      tombstone: Option[TombstoneCause],
      unlistedOwners: Seq[ByteString],
      prunedHeights: Int,
      prunedTo: BigInt,
      nanos: Long
  )

  enum CommitOutcome:
    /** The block's state is on disk, and the block is the anchor. */
    case Applied(commit: Commit)

    /** The block was the anchor already: the caller's own updates were written and nothing else. */
    case AlreadyApplied

  /** What is told of the commits: for metrics, which this package does not know. */
  trait CommitObserver:
    /** A writer waited `nanos` for the state lock. */
    def lockWaited(nanos: Long): Unit = ()
    def committed(commit: Commit): Unit = ()

  object CommitObserver:
    val Silent: CommitObserver = new CommitObserver {}
