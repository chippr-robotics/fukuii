package com.chipprbots.ethereum.db.storage.pathstate

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

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

  /** The executed blocks whose state is on no row (data-model 6.1), within their bounds (6.4). Nothing in the node
    * makes one yet.
    */
  private[pathstate] val overlay = new StateOverlay(
    StateOverlay.Budget(
      settings.maxForwardLayers,
      settings.maxForwardBytes,
      settings.maxLayerBytes,
      settings.pinnedMaxBytes
    )
  )

  /** The blocks behind the anchor that the changeset records lead to, when they have been looked up: built at the first
    * need of them, moved on by each commit, and dropped whenever the anchor moves in any other way.
    */
  private val window = new AtomicReference[Option[AncestorWindow]](None)

  /** The reverse indexes last built ([[ReverseIndexCache]]): those an execution built, and those a reader did. */
  private val indexes = new ReverseIndexCache(MaxIndexesKept, settings.maxReversePoolBytes)

  /** The indexes that are being built: who asks for one of them meanwhile waits for it, and is given it. */
  private val indexesBeingBuilt = new ConcurrentHashMap[(ByteString, ByteString, Long), IndexBuild]()
  private val indexBuildsAwaited = new AtomicInteger(0)

  /** How many indexes the readers of each kind may be building at once. A reader is anyone who can reach the node, and
    * each kind has its own few: a caller of the JSON-RPC who asks about many old blocks does not have a peer refused.
    */
  private val readerBuilds: Map[Reader, Semaphore] =
    Reader.values.iterator.map(_ -> new Semaphore(MaxReaderIndexBuilds)).toMap

  /** The window of `held`, which is the anchor, for a writer: built from the records' headers if there is none of that
    * anchor. Under the lock, which a writer holds anyway: once after a start, and after an anchor move that was no
    * commit; a commit moves the window on with it.
    */
  private def windowOf(held: BlockRef)(using StateLockHeld): AncestorWindow =
    window.get match
      case Some(known) if known.anchor == held => known
      case _ =>
        val built = AncestorWindow.of(changesets, held, settings.maxReverseHops)
        window.set(Some(built))
        built

  /** The window of `held` for a reader, which saw `held` as the anchor at `epoch`: the one there is, or one built now,
    * '''with no lock held'''. A reader is anyone who can reach the node, and the walk reads a record for each block
    * behind the anchor: under the state lock it would hold up every commit (beacon).
    *
    * `None` when the disk moved while it was built: what was read may be of records a commit was deleting, and the view
    * is made again. A window that is built is kept only if nothing moved; and one that is kept is of its anchor and of
    * no other, so one that slipped in beside a move is not used, and is replaced.
    */
  private def windowFor(held: BlockRef, epoch: Long): Option[AncestorWindow] =
    val seen = window.get
    seen.filter(_.anchor == held).orElse {
      val built = AncestorWindow.of(changesets, held, settings.maxReverseHops)
      Option.when(clock.epoch == epoch && clock.anchor.contains(held)) {
        // not over a window a writer put there meanwhile, which is of a later anchor
        window.compareAndSet(seen, Some(built))
        built
      }
    }

  /** The index between `anchor` and `fork`, for a view that observed `epoch`: the one that was built at that epoch, or
    * one built now.
    *
    * An index is of the records as they were at one epoch. A view whose epoch has moved by the time it is made is
    * thrown away ([[viewOf]]), but its index may have been built across the move, from records a commit was pruning,
    * and it is put here all the same, possibly after the move emptied the cache. So an index is kept with its epoch and
    * given only to a view of that epoch: one built across a move is never met again, since no later view observes an
    * earlier epoch, also when a head move brings the anchor back to a block it has been at (beacon).
    *
    * '''An index is built once for those who ask for it together''': who asks for an index that is being built waits
    * for it and is given it, also when it is not kept, and is told what the one who built it was told when it could not
    * be built. Each would otherwise walk the same records and hold the same bytes (beacon).
    *
    * And '''readers build few at once''', each kind its own: a reader that finds as many being built as its kind may
    * build is told the state is not available, and asks again. One that waits for an index another builds needs no such
    * leave. An execution is held to no limit: what a peer or a caller of the JSON-RPC asks for must not keep a block
    * from being executed (beacon, conduit).
    *
    * What this bounds is what is being built and what is kept. An index that is not kept, or no longer, lives as long
    * as the views that read through it: how many reads behind the head run at once is for those who serve them to
    * bound.
    *
    * @param reader
    *   who it is for when it is for a reader (a peer served a state, a JSON-RPC call), and not for an execution
    */
  private def indexBetween(anchor: BlockRef, fork: BlockRef, epoch: Long, reader: Option[Reader]): ReverseIndex =
    val key = (anchor.hash, fork.hash, epoch)
    indexes.find(anchor, fork, epoch, reader.isDefined).getOrElse {
      val mine = new IndexBuild
      Option(indexesBeingBuilt.putIfAbsent(key, mine)) match
        case Some(build) =>
          indexBuildsAwaited.incrementAndGet()
          val ended =
            try build.await()
            finally indexBuildsAwaited.decrementAndGet()
          ended match
            case Some(Right(index)) =>
              // an execution that was given an index a reader built takes it over, if it is kept
              if reader.isEmpty then indexes.find(anchor, fork, epoch, reader = false)
              index
            case Some(Left(why)) => throw new StateUnavailable(why)
            // it ended in a way that says nothing of the records (a reader that was refused): asked again
            case None => indexBetween(anchor, fork, epoch, reader)
        case None =>
          var ended: Option[Either[String, ReverseIndex]] = None
          try
            val leave = reader.map(readerBuilds)
            if leave.exists(!_.tryAcquire()) then
              told("an index a reader was refused")(settings.observer.indexRefused())
              throw new StateUnavailable(
                s"the state of block ${fork.show} is not available right now: as many states behind the head as " +
                  "may be read at once are being read. Ask again",
                askAgain = true
              )
            try
              // built and kept between the look above and now
              val built = indexes.find(anchor, fork, epoch, reader.isDefined).getOrElse {
                val made =
                  ReverseIndex.between(changesets, anchor, fork, settings.maxReverseHops, settings.maxReverseIndexBytes)
                indexes.keep(made, epoch, reader.isDefined)
                told("an index that was built")(settings.observer.indexBuilt(made.bytes))
                made
              }
              ended = Some(Right(built))
              built
            catch
              case unavailable: StateUnavailable =>
                ended = Some(Left(unavailable.reason))
                throw unavailable
            finally leave.foreach(_.release())
          finally
            indexesBeingBuilt.remove(key, mine)
            mine.end(ended)
    }

  /** How many indexes are being built right now, and how many callers wait for one of them: none when no view is being
    * made.
    */
  private[pathstate] def indexBuildsInFlight: Int = indexesBeingBuilt.size
  private[pathstate] def indexBuildsWaitedFor: Int = indexBuildsAwaited.get

  /** What the overlay holds right now (data-model 6.4, 11). */
  def overlayUsage: OverlayUsage =
    overlay.usage.copy(reverseIndexes = indexes.size, reverseBytes = indexes.bytes)

  /** Tells the observer what the overlay holds, and of the layers that were evicted since it was last told: each
    * eviction once, whichever thread comes to tell of it, where a count worked out from two totals would count again
    * when two tellings cross (beacon).
    */
  private def overlayTold(): Unit =
    told("what the overlay holds") {
      val evicted = overlay.evictionsSinceAsked()
      if evicted > 0 then settings.observer.evicted(evicted)
      settings.observer.overlay(overlayUsage)
    }

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
        val chain = overlay.chainFrom(other, block).map(_._1)
        if clock.epoch != epoch then
          if triesLeft > 0 then
            told("a view made again")(settings.observer.viewRetried())
            viewAfter(block, rested, triesLeft - 1)
          else
            told("a view that could not be made")(settings.observer.viewStale())
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

  /** A view of the state of any block this store can give one of: the anchor; a block forward layers lead to; '''a
    * block behind the anchor''' that its changeset records lead back to, read through the reverse index between them;
    * and a block on '''a fork''', through the index back to the block the fork left the anchor's chain at and the
    * fork's layers from there (data-model 6.1, 6.2).
    *
    * [[view]] gives the first two, and it is what the node's readers and its execution ask. This one is for the
    * overlay's own use until the callers that may take a state behind the head are changed with it (C4a's last part): a
    * reader handed such a view today would answer where it refuses, and an execution would run to a commit that is
    * refused.
    *
    * The epoch, the anchor, the window of its ancestors, the layers and the records are one observation, as in
    * [[viewAfter]]: when the epoch has moved by the end, the view is made again. A record that is not there while the
    * epoch stands is a state that is not held; one that went with a moved epoch is asked for again.
    *
    * @throws StateUnavailable
    *   when no layers and no records lead to `block`'s state
    */
  private[pathstate] def viewOf(
      block: BlockRef,
      reader: Option[Reader] = None,
      triesLeft: Int = ViewTries,
      // told, before anything is read for it, that the block is not the one the disk holds: at the look at the anchor
      // the view itself is made from, so that whoever counts such views counts exactly those that are made
      notTheAnchor: () => Unit = () => ()
  ): StateView =
    // The view may wait for an index another builds, and reads records to build one: under the state lock that is
    // every commit held up behind one read.
    if lock.isHeldByCurrentThread then
      throw new IllegalStateException(
        s"A view of block ${block.show} cannot be made by the holder of the state lock " +
          s"('${lock.holder.getOrElse("?")}'): it may wait for records to be read"
      )
    val (epoch, held) = clock.awaitRest()
    held match
      case None => throw new StateUnavailable(s"the state of block ${block.show} is not held: there is no state anchor")
      case Some(`block`) => new StateView(block, epoch, clock, rows)
      case Some(anchor) =>
        notTheAnchor()
        val made =
          try
            windowFor(anchor, epoch).map { behind =>
              overlay.chainFrom(anchor, block, behind.contains) match
                case Some((layers, on)) =>
                  val reverse = Option.when(on != anchor)(indexBetween(anchor, on, epoch, reader))
                  new StateView(block, epoch, clock, rows, layers, reverse)
                case None if behind.contains(block) =>
                  new StateView(block, epoch, clock, rows, Nil, Some(indexBetween(anchor, block, epoch, reader)))
                case None =>
                  throw new StateUnavailable(
                    s"the state of block ${block.show} is not held: the disk holds ${anchor.show}, and neither its " +
                      "changeset records nor the executed blocks kept beside it lead there"
                  )
            }
          catch case _: StateUnavailable if clock.epoch != epoch => None
        made.filter(_ => clock.epoch == epoch).getOrElse {
          if clock.epoch == epoch then
            // the window was of another anchor and the epoch has not moved: the clock is read again
            if triesLeft > 0 then viewOf(block, reader, triesLeft - 1, notTheAnchor)
            else
              throw new StateUnavailable(s"the state of block ${block.show} is not held: the anchor could not be read")
          else if triesLeft > 0 then
            told("a view made again")(settings.observer.viewRetried())
            viewOf(block, reader, triesLeft - 1, notTheAnchor)
          else
            told("a view that could not be made")(settings.observer.viewStale())
            throw new StaleStateViewException(
              block,
              epoch,
              clock.epoch,
              "the disk moved on each time a view of it was being made"
            )
        }

  /** [[stage]] over [[viewOf]]: a layer for the execution of a block on a fork, or on a block behind the anchor. */
  private[pathstate] def stageOf(parent: BlockRef): PathStagedState = staged(parent, viewOf(parent))

  /** An execution is staged on `view`: the layers it reads through are in use (data-model 6.4). */
  private def staged(parent: BlockRef, view: StateView): PathStagedState =
    overlay.used(view.layers)
    new PathStagedState(parent, view)

  /** `block`'s state for a reader that writes nothing. */
  def readOnly(block: BlockRef): MptStorage = PathMptStorage.readOnly(view(block))

  /** The state of the anchor, or of a block '''behind it''' that its changeset records lead back to, for a reader that
    * writes nothing: a block of the anchor's own chain, read through the records and never through an executed block
    * that is kept beside the disk. So what a caller finds by a block's number on the canonical chain is that block's
    * state or no state, also while a branch is under execution.
    *
    * It is read through a view like any other: it reads the same while the disk moves on, for as many moves as a view
    * outlives, and then it is stale.
    *
    * @throws StateUnavailable
    *   when `block` is not the anchor and the records do not lead back to it: it is too far behind, a record on the way
    *   is pruned or lists nothing, or the block is not of the anchor's chain; or as many states behind the head as
    *   readers of this kind may have made at once are being made, and the caller asks again
    * @throws IllegalStateException
    *   when the calling thread holds the state lock
    */
  def readOnlyBehind(block: BlockRef, reader: Reader): MptStorage = PathMptStorage.readOnly(viewBehind(block, reader))

  /** [[readOnlyBehind]] for an execution that commits nothing (`eth_call`, a simulation, a trace at a block of the
    * chain): what it persists is kept beside the view, read back by that execution, and dropped with the storage.
    * Nothing is written, no layer is kept, and no layer is read through or counted as used.
    *
    * @throws StateUnavailable
    *   as [[readOnlyBehind]]
    * @throws IllegalStateException
    *   when the calling thread holds the state lock
    */
  def throwawayBehind(block: BlockRef, reader: Reader): MptStorage =
    PathMptStorage.throwaway(viewBehind(block, reader))

  /** How many reads of a state behind the anchor callers of the JSON-RPC may have in flight. */
  private val readsBehind = new Semaphore(settings.maxReadsBehindHead)

  /** How many reads behind the anchor are in flight in the scopes of JSON-RPC callers right now. */
  def readsBehindInFlight: Int = settings.maxReadsBehindHead - readsBehind.availablePermits

  /** [[readOnlyBehind]] for a caller of the JSON-RPC, and for anyone whose reads are not one at a time: '''the state of
    * a block behind the anchor is handed out only inside a [[ReadScope]], and to no more than `maxReadsBehindHead`
    * blocks' readers at once.'''
    *
    * What is made of the records for such a state lives as long as anything reads through it, kept or not, so the
    * builds and the kept indexes being bounded does not bound what callers hold between them: the reads in flight do. A
    * scope is counted once for each block it reads behind the anchor, however many states of that block it takes, and
    * is counted no more when it ends. One more is refused, at once and not queued: the caller asks again, and nothing a
    * caller asks for is waited for by anyone else.
    *
    * The anchor's own state is handed out as ever: with no scope and no count.
    *
    * @throws StateUnavailable
    *   as [[readOnlyBehind]]; when `block` is behind the anchor and the caller is in no scope; and, to be asked again
    *   (`askAgain`), when as many reads behind the anchor as may be are in flight
    */
  def readOnlyInScope(block: BlockRef): MptStorage = PathMptStorage.readOnly(viewInScope(block))

  /** [[readOnlyInScope]] for an execution that commits nothing: see [[throwawayBehind]]. */
  def throwawayInScope(block: BlockRef): MptStorage = PathMptStorage.throwaway(viewInScope(block))

  private def viewInScope(block: BlockRef): StateView =
    def counted(): Unit = ReadScope.ambient match
      case None =>
        throw new StateUnavailable(
          s"the state of block ${block.show} is not the state the disk holds, and this caller reads no other"
        )
      case Some(scope) =>
        val has = scope.leave(this, block.hash) {
          Option.when(readsBehind.tryAcquire())(() => readsBehind.release())
        }
        if !has then
          told("a read behind the head that was refused")(settings.observer.readRefused())
          throw new StateUnavailable(
            s"the state of block ${block.show} is not available right now: as many states behind the head as may " +
              "be read at once are being read. Ask again",
            askAgain = true
          )
    // Counted when the view finds the block is not the one the disk holds, and before it reads a record for it: one
    // more than may be is refused before it costs a walk.
    viewBehind(block, Reader.Rpc, () => counted())

  /** The view of the anchor, or of a block behind it on its own chain, for a reader. */
  private def viewBehind(block: BlockRef, reader: Reader, notTheAnchor: () => Unit = () => ()): StateView =
    val view = viewOf(block, Some(reader), notTheAnchor = notTheAnchor)
    if view.layers.nonEmpty then
      throw new StateUnavailable(
        s"the state of block ${block.show} is not held as a state of the chain the disk is on: it is an executed " +
          "block kept beside it"
      )
    view

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
  def stage(parent: BlockRef): PathStagedState = staged(parent, view(parent))

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
              movedTo(held, block, applied)
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
        overlayTold()
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
          // executed blocks kept beside a state that is gone are of no state, and neither is what was derived from
          // its records
          overlay.clear()
          window.set(None)
          indexes.clear()
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
    * '''Within the bounds of data-model 6.4.''' A layer that is by itself more than a layer may hold is not kept, and
    * one that does not fit among those that are kept has room made for it by the eviction of others, or is not kept
    * either ([[OverlayOutcome.NotKept]]): the block's state is then not held, and whoever needs it executes the block
    * again. Nothing is wrong with the block.
    *
    * With a `pin` ([[withPinnedBranch]]) the layer, and every kept layer it stands on, is held by that branch until it
    * is released: not evicted, and bounded only by what all pinned layers may hold together. A branch that is more than
    * that is refused with [[BranchTooLarge]], and nothing of this block is then kept.
    *
    * Nothing in the node calls this yet.
    *
    * @throws StateUnavailable
    *   when there is no anchor, or the state of the block's parent is not held any more
    * @throws BranchTooLarge
    *   with a pin, when the pinned layers would hold more than they may
    * @throws IllegalStateException
    *   when what was staged is not what a block does between the two state roots
    * @throws IllegalArgumentException
    *   when the block is not at the height after its parent's
    */
  def commitOverlay(staged: PathStagedState, block: BlockRef, pin: Option[BranchPin] = None): OverlayOutcome =
    val parent = staged.parent
    val known = overlay.layerOf(block).filter(_.parent == parent)
    known match
      case Some(layer) if pin.isEmpty =>
        // delivered again: it is in use
        overlay.used(Seq(layer))
        OverlayOutcome.Kept(layer)
      case _ =>
        // a layer there already is, which a branch now takes hold of, is pinned under the lock like a new one
        val layer = known.getOrElse {
          require(block.number == parent.number + 1, s"block ${block.show} does not follow ${parent.show}")
          val frozen = staged.changes.frozen
          val made = ForwardLayer.of(block, parent, frozen)
          // The account trie's root is held to the two state roots, as a commit in place holds it: changes that are
          // not this block's final world, or were not made on this parent, stop here, before anything is kept. So
          // does everything else of a diff's validation that needs no row of the parent's state: what is kept can be
          // promoted, unless the disk is not the state it was built on.
          val faults = ForwardLayer.shapeFaults(block, parent, frozen) ++ made.rootFaults
          if faults.nonEmpty then
            throw new IllegalStateException(
              s"The staged changes of block ${block.show} are not what a block does to the state of ${parent.show}: " +
                faults.take(MaxFaultsShown).mkString("; ")
            )
          made
        }
        val outcome = lock.locked(
          s"overlay commit of block ${block.show}",
          nanos => told("the wait for the state lock")(settings.observer.lockWaited(nanos))
        ) {
          clock.anchor match
            case None =>
              throw new StateUnavailable(
                s"block ${block.show} cannot be kept: there is no state anchor, so there is no state to keep it beside"
              )
            case Some(held) if held.hash == block.hash => OverlayOutcome.AlreadyApplied
            case Some(held) =>
              val behind = windowOf(held)
              // a block of the anchor's own chain is on disk or in the records: it is not kept as a layer beside them
              if behind.contains(block) then OverlayOutcome.BehindTheAnchor
              else
                // The parent is the anchor, a block behind it that the records lead to, or a block layers lead to
                // from one of those: a layer on anything else would be kept for nothing, and for good.
                val parentHeld = held == parent || behind.contains(parent) ||
                  overlay.chainFrom(held, parent, behind.contains).isDefined
                if !parentHeld then
                  throw new StateUnavailable(
                    s"block ${block.show} was executed on the state of ${parent.show}, which is not held any more: " +
                      s"the disk holds ${held.show}"
                  )
                overlay.register(layer, pin, held, behind.contains, behind.ancestors.lastOption) match
                  case StateOverlay.Registered.Kept(kept) => OverlayOutcome.Kept(kept)
                  case StateOverlay.Registered.TooLarge(bytes, max) =>
                    OverlayOutcome.NotKept(NotKeptReason.TooLarge(bytes, max))
                  case StateOverlay.Registered.NoRoom => OverlayOutcome.NotKept(NotKeptReason.NoRoom)
        }
        overlayTold()
        outcome

  /** Runs `body` with a pin for one branch under execution, and releases the pin when `body` ends, however it ends
    * (data-model 6.4). The layers [[commitOverlay]] keeps with the pin are held meanwhile: none is evicted, and they
    * are bounded together by what pinned layers may hold. After the release they are layers like any other, and the
    * bounds apply to them from the next one that is kept: so a caller that adopts the branch promotes it first, and
    * releases after.
    *
    * @throws IllegalStateException
    *   when the calling thread holds the state lock: the pin is released under it, and a release that could not take it
    *   would leave the branch pinned for good
    */
  def withPinnedBranch[A](name: String)(body: BranchPin => A): A =
    if lock.isHeldByCurrentThread then
      throw new IllegalStateException(
        s"The branch '$name' cannot be pinned by the holder of the state lock ('${lock.holder.getOrElse("?")}'): " +
          "its pin is released under that lock"
      )
    val pin = new BranchPin(name)
    def release(): Unit =
      lock.locked(s"the release of the pinned branch $name")(overlay.release(pin))
      overlayTold()
    val result =
      try body(pin)
      catch
        case failure: Throwable =>
          // the execution's own failure is the one that is thrown
          try release()
          catch case NonFatal(also) => failure.addSuppressed(also)
          throw failure
    release()
    result

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
              dropUnreachable(held)
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
                    dropUnreachable(held)
                    throw fault
              val applied = commitInPlace(finalised, diff, block, extra, finalized)
              movedTo(held, block, applied)
              applied
    }
    outcome match
      case CommitOutcome.Applied(commit) =>
        told(s"the commit of block ${block.show}")(settings.observer.committed(commit))
        overlayTold()
      case CommitOutcome.AlreadyApplied => ()
    outcome

  /** Drops the forward layer of `blockHash`, if it has one, and the layers built on it: its execution was found not to
    * stand (data-model 7.2). Views that hold them go on reading them; no new view is made through them.
    */
  def dropLayer(blockHash: ByteString): Unit =
    lock.locked("the drop of an executed block") {
      overlay.drop(blockHash)
      clock.anchor.fold(overlay.clear())(dropUnreachable)
    }
    overlayTold()

  /** The anchor has moved from `parent` to `block`, whose batch has returned. The window of ancestors follows it, when
    * there is one: `parent` is now the nearest, unless the block's record is a tombstone, which nothing is read across;
    * and the ancestors whose records the batch pruned are gone. Then the layers: the block's own, and every one that
    * nothing leads to any more. Only now: a view that is being made finds either the layer or a moved epoch (see
    * [[viewAfter]]).
    */
  private def movedTo(parent: BlockRef, block: BlockRef, outcome: CommitOutcome)(using StateLockHeld): Unit =
    val advanced = (window.get, outcome) match
      case (Some(known), CommitOutcome.Applied(commit)) if known.anchor == parent =>
        Some(
          known.advancedTo(block, commit.tombstone.isDefined, floor = commit.prunedTo - 1, settings.maxReverseHops)
        )
      case _ => None
    window.set(advanced)
    // an index is of one anchor, and that was the last: the views that read through one hold it themselves
    indexes.clear()
    dropUnreachable(block)

  /** Drops the layers nothing leads to from `held`, the anchor. With no layer there is nothing to look at, and the
    * window is not built for it: a node that keeps none pays nothing here.
    */
  private def dropUnreachable(held: BlockRef)(using StateLockHeld): Unit =
    if overlay.size > 0 then
      overlay.dropUnreachableFrom(held, windowOf(held).contains)
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

  /** How many executed blocks that no branch pins are kept beside the state on disk, unless the configuration says. */
  val MaxForwardLayers: Int = 32

  /** How many reverse indexes are kept at once. */
  private val MaxIndexesKept = 4

  /** How many reverse indexes the readers of one kind may be building at once. */
  private val MaxReaderIndexBuilds = 2

  /** Who reads a state behind the head, when it is no execution. */
  enum Reader:
    /** A peer that is served a state by its root. */
    case Peer

    /** A caller of the JSON-RPC, or of another interface the node answers on. */
    case Rpc

  /** An index that is being built, and what its building came to: the index, or why the records do not give one. */
  final private class IndexBuild:
    private val over = new CountDownLatch(1)
    @volatile private var result: Option[Either[String, ReverseIndex]] = None

    /** Said once, by the one who builds: `None` when it ended in a way that says nothing of the records. */
    def end(result: Option[Either[String, ReverseIndex]]): Unit =
      this.result = result
      over.countDown()

    def await(): Option[Either[String, ReverseIndex]] =
      over.await()
      result

  /** What [[PathStateStorage.commitOverlay]] did with a block. */
  enum OverlayOutcome:
    /** The block is kept as this forward layer: the one just made, or the one there already was. It is kept as of now:
      * a layer that is not pinned can be evicted by the next one.
      */
    case Kept(layer: ForwardLayer)

    /** The block is not kept: its state is not held, and is to be had by executing it again. Nothing is wrong with it.
      */
    case NotKept(reason: NotKeptReason)

    /** The block is the one whose state the disk holds: there is nothing to keep beside it. */
    case AlreadyApplied

    /** The block is one of the anchor's own chain, behind it: its state is what the changeset records lead back to, and
      * it is not the head.
      */
    case BehindTheAnchor

  /** Why an executed block is not kept as a forward layer. */
  enum NotKeptReason:
    /** It holds more than one layer may: `bytes`, above `max`. */
    case TooLarge(bytes: Long, max: Long)

    /** There is no room among the layers that are kept, and none of them can be evicted for it. */
    case NoRoom

  /** What the overlay holds (data-model 6.4, 11).
    *
    * @param layers
    *   the forward layers that no branch pins
    * @param bytes
    *   what they hold, as [[ForwardLayer.bytes]] counts it
    * @param evictions
    *   how many layers were evicted since the store was opened
    * @param reverseIndexes
    *   the reverse indexes that are kept for the next view of the same fork point
    */
  final case class OverlayUsage(
      layers: Int,
      bytes: Long,
      pinnedLayers: Int,
      pinnedBytes: Long,
      evictions: Long,
      reverseIndexes: Int = 0,
      reverseBytes: Long = 0L
  )

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
      // The bounds of the forward layers no branch pins: how many, how much together, how much each.
      maxForwardLayers: Int = MaxForwardLayers,
      maxForwardBytes: Long = 128L * 1024 * 1024,
      maxLayerBytes: Long = 64L * 1024 * 1024,
      // How much the layers of the branches under execution hold together.
      pinnedMaxBytes: Long = 256L * 1024 * 1024,
      // How many blocks back a view is made through the changeset records, how much one reverse index may hold, and
      // how much the indexes that are kept hold together.
      maxReverseHops: Int = 1024,
      maxReverseIndexBytes: Long = 64L * 1024 * 1024,
      maxReversePoolBytes: Long = 128L * 1024 * 1024,
      // How many reads of a state behind the anchor callers of the JSON-RPC may have in flight: each may hold an
      // index of maxReverseIndexBytes that is not kept. A constant until it is measured (M5).
      maxReadsBehindHead: Int = 4
  )

  object CommitSettings:
    def of(config: StateConfig, finalized: () => Option[BigInt], observer: CommitObserver): CommitSettings =
      require(
        config.overlay.maxLayerBytes <= config.overlay.maxBytes / 2,
        s"state.overlay.max-layer-bytes (${config.overlay.maxLayerBytes}) is more than the executed blocks kept beside " +
          s"the disk may hold together, which is half of state.overlay.max-bytes (${config.overlay.maxBytes}): no " +
          "block of that size could be kept"
      )
      CommitSettings(
        config.changeset.maxEntriesPerOwnerDelete,
        config.changeset.maxRecordBytes,
        RetentionPolicy(config),
        finalized,
        observer,
        maxForwardLayers = config.overlay.maxForwardLayers,
        // the overlay's bytes are the forward layers' and the reverse indexes', half each (data-model 6.4)
        maxForwardBytes = config.overlay.maxBytes / 2,
        maxLayerBytes = config.overlay.maxLayerBytes,
        pinnedMaxBytes = config.overlay.pinnedMaxBytes,
        // How far behind the anchor a view reaches (data-model 4.5): as far as the records that are kept, which in
        // the archive mode are all of them, so that there it is a number of its own.
        maxReverseHops = config.pruning.mode match
          case StateConfig.Mode.Archive => config.archiveViewReach
          case StateConfig.Mode.Basic   => config.retention.maxBlocks
        ,
        maxReverseIndexBytes = (config.overlay.maxBytes / 2).min(64L * 1024 * 1024),
        maxReversePoolBytes = config.overlay.maxBytes / 2
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

    /** What the overlay holds, after something of it changed. */
    def overlay(usage: OverlayUsage): Unit = ()

    /** A reverse index of `bytes` was built: a state behind the anchor, or on a fork, was asked for and none was kept.
      */
    def indexBuilt(bytes: Long): Unit = ()

    /** A reader was refused a state behind the anchor: as many indexes as readers may build at once were being built.
      */
    def indexRefused(): Unit = ()

    /** A caller was refused a state behind the anchor: as many reads behind it as may be were in flight. */
    def readRefused(): Unit = ()

    /** `layers` executed blocks were evicted to make room: each eviction is told once. */
    def evicted(layers: Long): Unit = ()

    /** A view was made again, because the disk moved while it was being made. */
    def viewRetried(): Unit = ()

    /** A view could not be made: the disk moved on each time. */
    def viewStale(): Unit = ()

  object CommitObserver:
    val Silent: CommitObserver = new CommitObserver {}
