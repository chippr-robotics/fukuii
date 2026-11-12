package com.chipprbots.ethereum.db.storage.pathstate

import org.apache.pekko.util.ByteString

import scala.util.control.NonFatal

import com.chipprbots.ethereum.db.dataSource.DataSource
import com.chipprbots.ethereum.db.dataSource.DataSourceBatchUpdate
import com.chipprbots.ethereum.db.dataSource.DataSourceUpdateOptimized
import com.chipprbots.ethereum.db.dataSource.DataUpdate
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.DecodedNodeCache
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.db.storage.PathNodeStorage
import com.chipprbots.ethereum.db.storage.PrefetchNodeReader
import com.chipprbots.ethereum.utils.LazyLogger

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
) extends LazyLogger:
  import PathStateStorage.*

  private val dataSource: DataSource = rows.dataSource
  require(appState.dataSource eq dataSource, "the anchor and the rows it describes are written in one batch")

  private val changesets = new ChangesetStorage(dataSource)

  /** The clock of in-place moves, started from the anchor the datadir holds. */
  val clock: StateMoveLog = new StateMoveLog(maxViewAgeMoves, appState.getStateAnchor())

  /** The lock of the in-place writers (data-model 7.0). */
  val lock: StateLock = new StateLock

  /** The executed blocks whose state is on no row (data-model 6.1). Nothing in the node makes one yet. */
  private[pathstate] val overlay = new StateOverlay(settings.maxForwardLayers)

  /** The block whose state the disk holds, or `None` when the datadir has no state yet (SNAP in progress). */
  def anchor: Option[BlockRef] = clock.anchor

  /** Whether `block` is the block whose state the disk holds. (A block kept as a forward layer has a view too:
    * [[view]]. What takes a layer for a state that is there comes with the callers, in C4a's last part.)
    */
  def stateAvailable(block: BlockRef): Boolean = anchor.contains(block)

  /** The forward layer of `blockHash`, when it has one: it was executed, and its state is on no row. */
  def forwardLayer(blockHash: ByteString): Option[ForwardLayer] = overlay.layerOf(blockHash)

  /** A view of `block`'s state, made at a moment when the disk is at rest: a view is made at an even epoch (data-model
    * 6.3.1), so one made while a batch is being written waits for that batch. The anchor's view reads the rows; the
    * view of a block executed on the anchor's state reads through its forward layers first.
    *
    * @throws StateUnavailable
    *   when `block` is not the anchor and no forward layers lead to it from the anchor, or there is no anchor
    */
  def view(block: BlockRef): StateView = viewAfter(block, () => ())

  /** [[view]], with `rested` run between the look at the clock and the look at the layers: a test stands there.
    *
    * The epoch, the anchor and the layers are one observation. A layer is taken away only after the batch that promoted
    * it has settled, so layers found after the clock was read are the ones that lead from that anchor '''unless the
    * epoch has moved''', and then the view is made again. Without the second look a promotion in between would give a
    * view its new, shorter chain over rows it takes for the old anchor's, and the log of moves would then take the
    * promoted block's rows back out from under its own descendants: a wrong state, and no fault (beacon).
    */
  private[pathstate] def viewAfter(block: BlockRef, rested: () => Unit, triesLeft: Int = ViewTries): StateView =
    val (epoch, held) = clock.awaitRest()
    rested()
    held match
      case None => throw new StateUnavailable(s"the state of block ${block.show} is not held: there is no state anchor")
      case Some(`block`) => new StateView(block, epoch, clock, rows)
      case Some(other) =>
        val chain = overlay.chainFrom(other, block)
        if clock.epoch != epoch then
          if triesLeft > 0 then viewAfter(block, rested, triesLeft - 1)
          else
            throw new StaleStateViewException(
              block,
              epoch,
              clock.epoch,
              "the disk moved on each time a view of it was being made"
            )
        else
          chain match
            case Some(layers) => new StateView(block, epoch, clock, rows, layers)
            case None =>
              throw new StateUnavailable(s"the state of block ${block.show} is not held: the disk holds ${other.show}")

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

  /** A layer to build a genesis state on. Making one changes nothing and asks nothing of the datadir. */
  def genesisLayer(): GenesisLayer = new GenesisLayer

  /** Commits the genesis built on `layer` as the first state of a datadir that holds none (data-model 5.4): '''one
    * batch''', synced, of the caller's own updates (the genesis block, its weight, the best-block pointer), the rows,
    * the marks of a datadir this build created, the layout stamp, and the anchor. It is one of the writers that make an
    * anchor where there was none, so it runs with no anchor, under the state lock, as a move of the clock.
    *
    * There is no changeset record for a genesis: nothing came before it.
    *
    * @throws IllegalStateException
    *   when the datadir already holds a state anchor, a row of `t` or `u`, a changeset record or a layout stamp; or
    *   when what was built is not the state `genesis` names
    */
  def commitGenesis(layer: GenesisLayer, genesis: BlockRef, extra: Option[DataSourceBatchUpdate]): Unit =
    extra.foreach(other =>
      require(other.dataSource eq dataSource, "a block and its state are written in one batch, to one data source")
    )
    require(genesis.number == 0, s"a genesis block is block 0, not ${genesis.number}")
    lock.locked(
      s"genesis commit of block ${genesis.show}",
      nanos => told("the wait for the state lock")(settings.observer.lockWaited(nanos))
    ) {
      val held = Seq(
        clock.anchor.map(anchor => s"the state of block ${anchor.show}"),
        Option.when(!rows.isEmpty)("rows of a state"),
        Option.when(!changesets.isEmpty)("changeset records"),
        appState.getChangesetPrunedTo().map(height => s"a count of pruned changesets ($height)"),
        appState.getStateLayout().map(stamp => s"the layout stamp '$stamp'")
      ).flatten
      if held.nonEmpty then
        throw new IllegalStateException(
          s"A genesis cannot be committed to a data directory that already holds ${held.mkString(", ")}"
        )

      if layer.changes.resetOwners.nonEmpty then
        throw new IllegalStateException(
          "The build of a genesis replaced a storage trie whole: a genesis has no account to remove"
        )
      val built = layer.changes.finalise(_ => Some(Nil))
      // nothing came before a genesis, and its account trie's root is the state root its header carries (an empty
      // allocation has no row, and the root of the empty trie)
      val diff = BlockDiff(
        genesis.hash,
        genesis.number,
        ByteString(new Array[Byte](BlockDiff.HashLength)),
        BlockDiff.EmptyRoot,
        genesis.stateRoot,
        built.changes
      )
      val faults = diff.validate ++ built.changes.collect {
        case (position, change) if change.prior.isDefined => s"${position.show}: a node before the genesis"
      }
      if faults.nonEmpty then
        throw new IllegalStateException(
          s"What was built is not the genesis state of block ${genesis.show}: " +
            faults.take(MaxFaultsShown).mkString("; ")
        )

      val written: Map[PositionKey, Option[ByteString]] = built.changes.view.mapValues(_.next).toMap
      val updates: Seq[DataUpdate] =
        extra.toSeq.flatMap(_.updates) ++ rows.rowUpdates(written) ++
          // who created the datadir and that its chain weights follow from the genesis's; never that reference counts
          // are exact, which nothing on this layout has (forge)
          appState.markDatadirCreated(countsExact = false).updates ++
          appState.putStateLayout().updates ++
          appState.putStateAnchor(genesis).updates
      clock.inFlight(StateMove(genesis, written.view.mapValues(_ => Option.empty[ByteString]).toMap))(
        dataSource.updateSync(updates)
      )
    }

  /** A fresh layer over `parent`'s state for one execution of a block that builds on it.
    *
    * @throws StateUnavailable
    *   when `parent`'s state is not held (it is not the anchor and no forward layers lead to it), or there is no anchor
    */
  def stage(parent: BlockRef): PathStagedState = new PathStagedState(parent, view(parent))

  /** Commits `block`, executed on `staged`, as the block whose state the disk holds (data-model 7.1): '''one batch'''
    * of the caller's own updates (`extra`: the block, its receipts and weight, the best-block pointer), the rows the
    * block changed, its changeset record and the index of its height, the records retention deletes, and the anchor. It
    * is written under the state lock, as a move of the clock.
    *
    *   - '''The block is the anchor already''': the state half is dropped and `extra` alone is written. This is the
    *     only case a caller with nothing staged can be in: one that found the block executed and applied, and has only
    *     the block's own records to write. Whether it is still applied is decided here, under the lock. The block, its
    *     receipts and its weight went to the disk with the batch that moved the anchor, so all `extra` can add here is
    *     '''a best-block pointer, and it is written''': a caller that is not entitled to move the head (a payload sent
    *     again, on a chain whose head is the consensus client's to choose) must hand an `extra` without one (beacon).
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
    // Read before the lock is asked for: nothing runs under it but this class and the data source (vault), and what
    // names the finalized block is not this class's.
    val finalized = settings.finalized()
    val outcome = lock.locked(
      s"canonical commit of block ${block.show}",
      nanos => told("the wait for the state lock")(settings.observer.lockWaited(nanos))
    ) {
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
            case Some(executed) if executed.parent == held =>
              // The rows of each storage trie the block replaced, as the disk holds them: the parent's (data-model
              // 3.6). Scanned here, under the lock, so that they are the rows this batch is written over; and bounded
              // in rows and in bytes, since they are held in memory and the lock is held meanwhile.
              val finalised =
                executed.changes.finalise(owner => rows.storageRows(owner, maxOwnerRows, settings.maxOwnerScanBytes))
              val applied = commitInPlace(finalised, diffOf(finalised, held, block), block, extra, finalized)
              // the block is on disk: a layer an earlier execution left of it is of no more use, nor is one that
              // no chain now leads to
              layersAfterMoveTo(block)
              applied
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
    // Told once the lock is free. The block is committed whatever its observer makes of it.
    outcome match
      case CommitOutcome.Applied(commit) =>
        told(s"the commit of block ${block.show}")(settings.observer.committed(commit))
      case CommitOutcome.AlreadyApplied => ()
    outcome

  /** '''Destroys the state this datadir holds''', so that a genesis can be committed to it again: every row of both
    * column families, every changeset record and index entry, the count of pruned records, the layout stamp and the
    * anchor, in '''one batch''', synced, under the state lock. Returns how many keys it deleted.
    *
    * This is for test mode and nothing else. A test harness (retesteth) sets a new chain on one datadir before each
    * test (`test_setChainParams`), and on this layout a genesis is committed only to a datadir that holds no state
    * ([[commitGenesis]]). A node that is not in test mode cannot call it: the store is built with
    * `allowsStateDestruction` only there, and without it this throws before it reads anything.
    *
    * One batch, and so point deletes and not a range delete, which is no part of a batch: there is no moment with the
    * rows gone and the anchor still there, or the reverse. That needs the keys in memory, which is right for the states
    * test mode makes and for no other: above [[PathStateStorage.MaxKeysDestroyed]] keys it refuses.
    *
    * A view made before it is stale from the moment it starts ([[StateMoveLog.inFlightToNoState]]); after it there is
    * no anchor and no view until the next genesis commit. A write that fails leaves the datadir and the anchor as they
    * were. On a datadir that holds no state it deletes nothing.
    *
    * @throws IllegalStateException
    *   when the store was not built for test mode, or the state has more keys than may be deleted this way
    */
  private[ethereum] def destroyStateForNewGenesis(): Int =
    if !settings.allowsStateDestruction then throw new IllegalStateException(NotBuiltForDestruction)
    lock.locked(
      "destruction of the state for a new genesis",
      nanos => told("the wait for the state lock")(settings.observer.lockWaited(nanos))
    ) {
      removalOfTheState() match
        case None => throw new IllegalStateException(tooManyKeysToDestroy)
        case Some(deletes) =>
          val updates: Seq[DataUpdate] = deletes ++ appState.removeStateMarks().updates
          clock.inFlightToNoState(dataSource.updateSync(updates))
          // executed blocks kept beside a state that is gone are of no state
          overlay.clear()
          deletes.map(_.toRemove.size).sum
    }

  /** Why [[destroyStateForNewGenesis]] would refuse this datadir as it is now, or `None` when it would not. It reads,
    * under the state lock, and writes nothing: for a caller that has something to take away before the state, and must
    * not take it away from a datadir whose state will stay.
    */
  private[ethereum] def refusalToDestroyState(): Option[String] =
    if !settings.allowsStateDestruction then Some(NotBuiltForDestruction)
    else
      lock.locked("the look at the state before its destruction") {
        Option.when(removalOfTheState().isEmpty)(tooManyKeysToDestroy)
      }

  /** The deletes of every row and every changeset key, or `None` when either is more than may be deleted in a batch. */
  private def removalOfTheState()(using StateLockHeld): Option[Seq[DataSourceUpdateOptimized]] =
    for
      ofRows <- rows.removalOfEveryRow(settings.maxKeysDestroyed)
      ofRecords <- changesets.removalOfEverything(settings.maxKeysDestroyed)
    yield ofRows ++ ofRecords

  private def tooManyKeysToDestroy: String =
    s"The state holds more than ${settings.maxKeysDestroyed} rows or changeset keys: too many to delete in one " +
      "batch. Test mode's new genesis is for the states test mode makes; use another data directory."

  /** `tell`, whose failure is the observer's own: it is logged, and changes nothing of the commit it was told of. */
  private def told(what: String)(tell: => Unit): Unit =
    try tell
    catch case NonFatal(e) => log.warn(s"The observer of the state's commits failed on $what: $e")

  private def maxOwnerRows: Int = settings.maxEntriesPerOwnerDelete.min(Int.MaxValue.toLong).toInt

  /** `finalised` as the diff of `block` on `parent`, held to the two state roots by the account trie's root row:
    * changes that are not this block's final world, or were not made on this parent, stop here, before anything is kept
    * or written.
    */
  private def diffOf(finalised: StagedChanges.Final, parent: BlockRef, block: BlockRef): BlockDiff =
    require(block.number == parent.number + 1, s"block ${block.show} does not follow ${parent.show}")
    val diff = BlockDiff(block.hash, block.number, parent.hash, parent.stateRoot, block.stateRoot, finalised.changes)
    val faults = diff.validate
    if faults.nonEmpty then
      throw new IllegalStateException(
        s"The staged changes of block ${block.show} are not what a block does to the state of ${parent.show}: " +
          faults.take(MaxFaultsShown).mkString("; ")
      )
    diff

  /** Keeps `block`, executed on `staged`, as a forward layer (data-model 7.2): its changes are frozen and kept in
    * memory, and '''nothing is written''': no row, no record, no anchor, and the clock does not move. The block's own
    * records (header, body, receipts) are the caller's to write, as they are today.
    *
    * The block's parent may be the anchor or a forward layer. The layer holds what the block left and the hashes of
    * what it found ([[ForwardLayer]]): nothing of the parent's state is read to make it, and a storage trie the block
    * replaced whole is a mark in it, not a list of that trie's rows. What the block did to the rows is settled when the
    * layer is promoted. The lock is taken for the look at the anchor and the registration, which are one step: a block
    * that has become the anchor meanwhile is not kept as a layer beside itself, and a block whose parent's state is no
    * longer held is not kept at all (vault, beacon).
    *
    * A block that is a layer already stays the layer it is: one block, one layer.
    *
    * Nothing in the node calls this yet. What does must come with the bounds of data-model 6.4.
    *
    * @throws StateUnavailable
    *   when there is no anchor, or the state of the block's parent is not held any more, or no more layers are kept
    * @throws IllegalStateException
    *   when what was staged is not what a block does between the two state roots
    * @throws IllegalArgumentException
    *   when the block is not at the height after its parent's
    */
  def commitOverlay(staged: PathStagedState, block: BlockRef): OverlayOutcome =
    val parent = staged.parent
    overlay.layerOf(block) match
      case Some(known) if known.parent == parent => OverlayOutcome.Layered(known)
      case _ =>
        require(block.number == parent.number + 1, s"block ${block.show} does not follow ${parent.show}")
        val frozen = staged.changes.frozen
        val layer = ForwardLayer.of(block, parent, frozen)
        // The account trie's root is held to the two state roots, as a commit in place holds it: changes that are not
        // this block's final world, or were not made on this parent, stop here, before anything is kept. So does
        // everything else of a diff's validation that needs no row of the parent's state: what is kept can be
        // promoted, unless the disk is not the state it was built on.
        val faults = ForwardLayer.shapeFaults(block, parent, frozen) ++ layer.rootFaults
        if faults.nonEmpty then
          throw new IllegalStateException(
            s"The staged changes of block ${block.show} are not what a block does to the state of ${parent.show}: " +
              faults.take(MaxFaultsShown).mkString("; ")
          )
        lock.locked(
          s"overlay commit of block ${block.show}",
          nanos => told("the wait for the state lock")(settings.observer.lockWaited(nanos))
        ) {
          clock.anchor match
            case None =>
              throw new StateUnavailable(
                s"block ${block.show} cannot be kept: there is no state anchor, so there is no state to keep it beside"
              )
            case Some(held) if held.hash == block.hash => OverlayOutcome.AlreadyApplied
            case Some(held)                            =>
              // the parent is the anchor, or layers lead to it from the anchor: a layer on a layer the anchor has
              // left behind would be kept for nothing, and for good
              if held != parent && overlay.chainFrom(held, parent).isEmpty then
                throw new StateUnavailable(
                  s"block ${block.show} was executed on the state of ${parent.show}, which is not held any more: " +
                    s"the disk holds ${held.show}"
                )
              OverlayOutcome.Layered(overlay.register(layer))
        }

  /** Commits the forward layer of `block` as the block whose state the disk holds (data-model 7.1, the second of the
    * cases that are not a staged block on the anchor): the batch an in-place commit of the block writes. The layer goes
    * once the batch has returned, and not when it fails: the disk is then as it was.
    *
    * Only a layer '''whose parent is the anchor''' is promoted here. Moving the disk to any other is a head move (C4b).
    *
    * '''The block is settled here, against the rows the disk holds''', which are its parent's, under the lock, as a
    * commit in place settles one: the prior of each position is the row, read now and held to the hash the layer names
    * for it; the rows of a storage trie the block replaced whole are scanned now, and listed or left by the bound a
    * commit in place applies. So the record, the rows that are deleted and the priors of the move are those the same
    * block writes committed in place over this disk. Where a row is another node than the one the layer names, or none,
    * the layer was not built on this state: it is dropped, with what was built on it, nothing is written, and the block
    * is executed again (beacon, forge).
    *
    * '''A layer that cannot be promoted is not kept.''' The same holds where the settled changes are not a block's
    * diff, which the checks made when the layer was kept leave to a defect: the layer goes, with what stands on it, and
    * the fault is thrown. Kept, it would be the answer to every later execution of its block, and a branch with that
    * block in it could never be adopted (forge). A write that fails is another matter: the disk is as it was, and the
    * layer stays.
    *
    * It moves the anchor, so it is for a caller that '''adopts''' the block: one that only has the block's own records
    * to write must not come here (forge).
    *
    * @throws StateUnavailable
    *   when `block` has no forward layer, or its parent is not the anchor, or its layer was not built on the state the
    *   disk holds
    */
  def promote(block: BlockRef, extra: Option[DataSourceBatchUpdate]): CommitOutcome =
    extra.foreach(other =>
      require(other.dataSource eq dataSource, "a block and its state are written in one batch, to one data source")
    )
    val finalized = settings.finalized()
    val outcome = lock.locked(
      s"promotion of block ${block.show}",
      nanos => told("the wait for the state lock")(settings.observer.lockWaited(nanos))
    ) {
      (clock.anchor, overlay.layerOf(block)) match
        case (None, _) =>
          throw new StateUnavailable(s"block ${block.show} cannot be committed: there is no state anchor")
        case (Some(held), _) if held.hash == block.hash =>
          extra.foreach(_.commit())
          CommitOutcome.AlreadyApplied
        case (Some(held), None) =>
          throw new StateUnavailable(
            s"block ${block.show} is not kept as an executed block, and the disk holds ${held.show}"
          )
        case (Some(held), Some(layer)) if layer.parent != held =>
          throw new StateUnavailable(
            s"block ${block.show} was executed on the state of ${layer.parent.show}, and the disk holds ${held.show}"
          )
        case (Some(held), Some(layer)) =>
          layer.changesOver(position => rows.read(position).map(ByteString(_))) match
            case Left(position) =>
              overlay.drop(block.hash)
              overlay.dropUnreachableFrom(held)
              throw new StateUnavailable(
                s"the executed block ${block.show} was not built on the state the disk holds: it found another node " +
                  s"at ${position.show} than the row there. It is dropped, and is executed again"
              )
            case Right(changes) =>
              val finalised = StagedChanges.settle(
                changes,
                layer.resets,
                owner => rows.storageRows(owner, maxOwnerRows, settings.maxOwnerScanBytes)
              )
              val diff =
                try diffOf(finalised, held, block)
                catch
                  case fault: (IllegalStateException | IllegalArgumentException) =>
                    overlay.drop(block.hash)
                    overlay.dropUnreachableFrom(held)
                    throw fault
              val applied = commitInPlace(finalised, diff, block, extra, finalized)
              layersAfterMoveTo(block)
              applied
    }
    outcome match
      case CommitOutcome.Applied(commit) =>
        told(s"the commit of block ${block.show}")(settings.observer.committed(commit))
      case CommitOutcome.AlreadyApplied => ()
    outcome

  /** Drops the forward layer of `blockHash`, if it has one, and the layers built on it: its execution was found not to
    * stand (data-model 7.2). Views that hold them go on reading them; no new view is made through them.
    */
  def dropLayer(blockHash: ByteString): Unit =
    lock.locked("the drop of an executed block") {
      overlay.drop(blockHash)
      clock.anchor.fold(overlay.clear()) { held =>
        overlay.dropUnreachableFrom(held); ()
      }
    }

  /** The anchor has moved to `block`, whose batch has returned: its own layer goes, and every layer no chain leads to
    * any more. Only now: a view that is being made finds either the layer or a moved epoch (see [[viewAfter]]).
    */
  private def layersAfterMoveTo(block: BlockRef)(using StateLockHeld): Unit =
    // the block's own layer is one of them: no chain leads from a block to itself
    overlay.dropUnreachableFrom(block)
    ()

  /** Writes `diff`, which is `finalised` as the diff of `block` on the block the disk holds ([[diffOf]]). */
  private def commitInPlace(
      finalised: StagedChanges.Final,
      diff: BlockDiff,
      block: BlockRef,
      extra: Option[DataSourceBatchUpdate],
      finalized: Option[BigInt]
  )(using StateLockHeld): CommitOutcome =
    val tombstone: Option[TombstoneCause] =
      if finalised.unlisted.nonEmpty then Some(TombstoneCause.OwnerDeleteBound)
      else if BlockDiffCodec.encodedLength(diff) > settings.maxRecordBytes then Some(TombstoneCause.RecordSize)
      else None
    val record = if tombstone.isEmpty then diff else diff.copy(changes = Map.empty, incomplete = true)

    // What the batch leaves at each position it writes, and what a reader of the parent's state must go on seeing
    // there. For a trie whose old rows were not listed, the second is read: one point read for each row written.
    //
    // Elsewhere the second is the entry's prior, which is the row, with one exception that does no harm. Where an
    // unlisted trie left a row behind, a later block's first write records no prior, and the row is there. No reader
    // of the parent's state comes to that position: a read descends from a root through hashes, and no node of the
    // parent's state names a child there.
    val unrecorded = finalised.unrecorded
    val written: Map[PositionKey, Option[ByteString]] =
      diff.changes.view.mapValues(_.next).toMap ++ unrecorded.view.mapValues(Some(_))
    val before: Map[PositionKey, Option[ByteString]] =
      diff.changes.view.mapValues(_.prior).toMap ++
        unrecorded.keysIterator.map(position => position -> rows.read(position).map(ByteString(_)))

    val (changesetUpdates, prunedHeights, prunedTo) = changesetIndexed(block) {
      val pruned = pruning(block, finalized)
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

    CommitOutcome.Applied(
      Commit(
        block = block,
        positions = written.size,
        stateBytes =
          written.iterator.map((position, node) => position.rowKey.length.toLong + node.fold(0)(_.length)).sum,
        recordBytes = BlockDiffCodec.encodedLength(record),
        tombstone = tombstone,
        unlistedOwners = finalised.unlisted,
        prunedHeights = prunedHeights,
        prunedTo = prunedTo,
        nanos = System.nanoTime() - started
      )
    )

  /** What retention does in this block's batch. The first block committed on a datadir starts the count at itself: no
    * height below it has a record.
    */
  private def pruning(block: BlockRef, finalized: Option[BigInt])(using StateLockHeld): Pruning =
    appState.getChangesetPrunedTo() match
      case None => Pruning(Nil, movedTo = Some(block.number), reached = block.number, heights = 0)
      case Some(from) =>
        settings.retention.floor(block.number, finalized) match
          case Some(floor) if floor > from =>
            val (deletes, next) = changesets.pruneUpdates(from, floor, settings.retention.heightsPerBatch(floor - from))
            Pruning(deletes, Option.when(next > from)(next), reached = next, heights = (next - from).toInt)
          case _ => Pruning(Nil, movedTo = None, reached = from, heights = 0)

  /** `build`, which reads the index of changeset records. An index that cannot be read is this datadir's corruption,
    * and is said as that, with the block that met it: it is no fault of the block.
    */
  private def changesetIndexed[A](block: BlockRef)(build: => A): A =
    try build
    catch
      case e: ChangesetStorage.CorruptIndex =>
        throw new IllegalStateException(
          s"The index of changeset records is corrupt; block ${block.show} was not committed: ${e.getMessage}",
          e
        )

object PathStateStorage:

  private val MaxFaultsShown = 3

  /** How often a view is made again because the disk moved between the look at the clock and the look at the layers. */
  private val ViewTries = 3

  /** How many executed blocks are kept beside the state on disk, until the budgets of data-model 6.4 replace it. */
  val MaxForwardLayers: Int = 32

  /** What [[PathStateStorage.commitOverlay]] did with a block. */
  enum OverlayOutcome:
    /** The block is kept as this forward layer: the one just made, or the one there already was. */
    case Layered(layer: ForwardLayer)

    /** The block is the one whose state the disk holds: there is nothing to keep beside it. */
    case AlreadyApplied

  /** The most rows, and the most changeset keys, [[PathStateStorage.destroyStateForNewGenesis]] deletes: what one batch
    * can hold in memory, and far more than a test's state has.
    */
  val MaxKeysDestroyed: Long = 1000000L

  private val NotBuiltForDestruction =
    "The state of a data directory is destroyed only in test mode (fukuii.testmode), for a new genesis"

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
    *   the number of the finalized block, where a consensus client names one. Asked once for each commit, before the
    *   state lock is taken: it must answer from what it holds, and not wait
    * @param maxOwnerScanBytes
    *   a replaced storage trie whose rows hold more than this keeps them too: the rows are read into memory under the
    *   state lock, and a count of rows alone does not bound that
    */
  final case class CommitSettings(
      maxEntriesPerOwnerDelete: Long = 1000000L,
      maxRecordBytes: Long = 256L * 1024 * 1024,
      retention: RetentionPolicy = RetentionPolicy.KeepAll,
      finalized: () => Option[BigInt] = () => None,
      observer: CommitObserver = CommitObserver.Silent,
      maxOwnerScanBytes: Long = 64L * 1024 * 1024,
      // Whether [[PathStateStorage.destroyStateForNewGenesis]] may run: test mode only.
      allowsStateDestruction: Boolean = false,
      // The most rows, and the most changeset keys, it deletes.
      maxKeysDestroyed: Long = MaxKeysDestroyed,
      // How many forward layers are kept (a stopgap: see [[MaxForwardLayers]]).
      maxForwardLayers: Int = MaxForwardLayers
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
    /** A storage trie it replaced has more rows, or more bytes of them, than may be listed; they were left in place. */
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

  /** What is told of the commits: for metrics, which this package does not know. What an observer throws is logged and
    * goes no further.
    */
  trait CommitObserver:
    /** A writer waited `nanos` for the state lock. */
    def lockWaited(nanos: Long): Unit = ()
    def committed(commit: Commit): Unit = ()

  object CommitObserver:
    val Silent: CommitObserver = new CommitObserver {}
