package com.chipprbots.ethereum.db.storage.pathstate

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock

import org.apache.pekko.util.ByteString

import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.dataSource.DataSource
import com.chipprbots.ethereum.db.dataSource.DataSourceBatchUpdate
import com.chipprbots.ethereum.db.dataSource.DataSourceUpdateOptimized
import com.chipprbots.ethereum.db.dataSource.DataUpdate
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.BadEntryDelete
import com.chipprbots.ethereum.db.storage.BlockHeadersStorage
import com.chipprbots.ethereum.db.storage.BlockNumberMappingStorage
import com.chipprbots.ethereum.db.storage.ChainWeightStorage
import com.chipprbots.ethereum.db.storage.DecodedNodeCache
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.db.storage.Namespaces
import com.chipprbots.ethereum.db.storage.PathNodeStorage
import com.chipprbots.ethereum.db.storage.PrefetchNodeReader
import com.chipprbots.ethereum.db.storage.RawNodeRead
import com.chipprbots.ethereum.db.storage.RepairOutcome
import com.chipprbots.ethereum.db.storage.SnapStorageDoneStorage
import com.chipprbots.ethereum.db.storage.SnapSyncProgressStorage
import com.chipprbots.ethereum.db.storage.StateStorage
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
) extends LazyLogger
    with PositionRepairs:
  import PathStateStorage.*

  private val dataSource: DataSource = rows.dataSource
  require(appState.dataSource eq dataSource, "the anchor and the rows it describes are written in one batch")

  private val changesets = new ChangesetStorage(dataSource)

  /** The clock of in-place moves, started from the anchor the datadir holds. */
  val clock: StateMoveLog = new StateMoveLog(maxViewAgeMoves, appState.getStateAnchor(), settings.maxMoveLogBytes)

  /** The lock of the in-place writers (data-model 7.0). */
  val lock: StateLock = new StateLock

  // the anchor the datadir opens with is told, so that its gauge does not read 0 until the first commit (B-328)
  told("the anchor at open")(settings.observer.anchorAt(clock.anchor))

  /** One move of the head at a time, from its plan to its last batch; and no commit of a block in between two batches
    * of it. A move of many blocks is a chain of batches, and the state lock is free between them: `moving` is what a
    * writer that adopts a block looks at under the state lock, and this lock is what it then waits on, with the state
    * lock let go ([[adopting]]). A move takes this one first and the state lock for each batch; nothing takes this one
    * with the state lock held.
    */
  private val moves = new ReentrantLock(true)
  @volatile private var moving = false
  private val movesBegun = new java.util.concurrent.atomic.AtomicLong(0L)

  /** Set when a row read back after a write was not the row written (the store wrote other bytes than it was given), or
    * when a caller found this datadir's own records corrupt ([[failClosed]]). Nothing is written in place after that,
    * by a commit or by a move, and no state is given out, until the state is cleared or destroyed (plan.md "C4b: the
    * move core": it never rolls back onto such a store).
    *
    * It is recorded on disk as it is found ([[StateFaultMark]], B-252), and read here: a restart opens the store closed
    * again, and the fork choice's refusals then escalate to a clear as they would have without the restart.
    */
  @volatile private var faulted: Option[String] = appState.getStateFault()
  faulted.foreach(why =>
    log.error(
      s"[STATE-MOVE] This node's state failed closed before this start: $why. It takes no write and is not read " +
        "until it is cleared"
    )
  )

  /** The finalized block named with a fork choice this node followed ([[moveHead]]'s `finalized`), read from AppState
    * at start (spec 018 C4d part 1). Before one is named, what the settings name, which is none in the node.
    */
  @volatile private var named: Option[FinalizedBlock] = appState.getStateFinalized()

  private def finalizedNow(): Option[FinalizedBlock] = named.orElse(settings.finalized())

  /** The finalized block this store holds its moves and its retention to. */
  def finalizedBlock: Option[FinalizedBlock] = finalizedNow()

  /** True from the first move of the head that moves it in the current call of [[moveHead]]: a move of nothing is not
    * counted ([[movesStarted]]).
    */
  private var countedThisMove = false

  // The head's generation (see [[headGeneration]]): even while the head is settled, odd while a move of it or an
  // attempt that holds the records is open. `openAttempts` counts them all, whatever thread opened each: a fork
  // choice's move inside an adoption only nests. The monitor is a leaf: nothing is locked with it held.
  private val generation = new java.util.concurrent.atomic.AtomicLong(0L)
  private val attempts = new Object
  private var openAttempts = 0

  private def openAttempt(): Unit = attempts.synchronized {
    openAttempts += 1
    if openAttempts == 1 then generation.incrementAndGet()
    ()
  }

  private def closeAttempt(): Unit = attempts.synchronized {
    openAttempts -= 1
    if openAttempts == 0 then
      generation.incrementAndGet()
      attempts.notifyAll()
  }

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
  override def anchor: Option[BlockRef] = clock.anchor

  /** Whether a view of `block`'s state can be made, as [[viewOf]] makes one, and an execution staged on it: `block` is
    * the anchor, a block behind it that its changeset records lead back to, or a block that forward layers lead to from
    * one of those. It is what a recovery of a state that is no longer held walks down to (spec 018 T094, C4d part 3).
    *
    * Only the window of the anchor's ancestors is read: no index of what the records hold is built for the answer. A
    * state that has failed closed holds no block's.
    *
    * '''With no lock held''': the window reads a record for each block behind the anchor.
    */
  def stateAvailable(block: BlockRef): Boolean = heldDepth(block).isDefined

  /** How many forward layers `block`'s state stands on above the disk's, when it is held: none for the anchor and a
    * block behind it that the records lead back to, `n` for a block `n` layers lead to. What a recovery adds on top of
    * it is bounded with it (spec 018 C4d part 4). `None` when it is not held, as for [[stateAvailable]].
    */
  def heldDepth(block: BlockRef): Option[Int] = heldDepthAt(block, ViewTries)

  @annotation.tailrec
  private def heldDepthAt(block: BlockRef, triesLeft: Int): Option[Int] =
    if lock.isHeldByCurrentThread then
      throw new IllegalStateException(
        s"Whether the state of block ${block.show} is held cannot be asked by the holder of the state lock " +
          s"('${lock.holder.getOrElse("?")}'): it may read records"
      )
    if hasFailedClosed then None
    else
      val (epoch, held) = clock.awaitRest()
      held match
        case None            => None
        case Some(`block`)   => Some(0)
        case Some(anchorNow) =>
          // the window, the layers and the clock are one observation, as for a view: when the disk moved, asked again
          val seen = windowFor(anchorNow, epoch).map { behind =>
            if behind.contains(block) then Some(0)
            else overlay.chainFrom(anchorNow, block, behind.contains).map((layers, _) => layers.size)
          }
          seen.filter(_ => clock.epoch == epoch) match
            case Some(answer)          => answer
            case None if triesLeft > 0 => heldDepthAt(block, triesLeft - 1)
            case None                  => None

  /** The forward layer of `blockHash`, when it has one: it was executed, and its state is on no row. */
  def forwardLayer(blockHash: ByteString): Option[ForwardLayer] = overlay.layerOf(blockHash)

  /** How the disk's state would get to `target`'s ([[HeadMovePlan]]): the blocks to reverse by their records, down to
    * the highest block the anchor's chain and the target's share, and the blocks to apply from there, or why that
    * cannot be done. It changes nothing and holds no lock; it is made at a moment when the disk is at rest, and is of
    * the anchor and the epoch of that moment.
    *
    * @param link
    *   a block as its header gives it, by its hash. Never by its number: the index of canonical blocks may run ahead of
    *   the anchor or lie behind it
    */
  private[pathstate] def planMove(
      target: BlockRef,
      link: ByteString => Option[HeadMovePlan.Link]
  ): HeadMovePlan.Outcome = planMoveAfter(target, link, () => ())

  /** Why the head could not be moved to `target` as things are, when the plan of that move is refused: asked before a
    * branch is executed, so that a fork deeper than the records reach costs no execution (spec 018 C4e, the reverse
    * depth and not the branch's length). `None` when the move is planned, and when a block on the way is not held: that
    * is for the execution to find.
    */
  def refusalOfMoveTo(target: BlockRef, link: ByteString => Option[HeadMovePlan.Link]): Option[ReorgTooDeep] =
    planMove(target, link) match
      case HeadMovePlan.Outcome.Refused(reason, detail) => Some(new ReorgTooDeep(reason, detail))
      case _                                            => None

  /** [[planMove]], and the size of each changeset record it read, by block hash: what a move cuts its batches by. */
  private def planMoveSized(
      target: BlockRef,
      link: ByteString => Option[HeadMovePlan.Link]
  ): (HeadMovePlan.Outcome, collection.Map[ByteString, Long]) =
    val sizes = collection.concurrent.TrieMap.empty[ByteString, Long]
    (planMoveAfter(target, link, () => (), sizes = Some(sizes)), sizes)

  /** [[planMove]], with `walked` run between the walk and the second look at the clock: a test stands there.
    *
    * The anchor, the mark of what was pruned and the records are one observation only if nothing was committed while
    * they were read. A commit prunes records and moves the mark in one batch: a walk that began before it and met a
    * record it deleted would call the records corrupt. So a plan, and above all a refusal, is given out only if the
    * epoch has not moved; otherwise it is made again, a few times.
    */
  private[pathstate] def planMoveAfter(
      target: BlockRef,
      link: ByteString => Option[HeadMovePlan.Link],
      walked: () => Unit,
      triesLeft: Int = ViewTries,
      sizes: Option[collection.mutable.Map[ByteString, Long]] = None
  ): HeadMovePlan.Outcome =
    val (epoch, held) = clock.awaitRest()
    val outcome = planAt(epoch, held, target, link, sizes)
    walked()
    if clock.epoch == epoch then outcome
    else if triesLeft > 0 then planMoveAfter(target, link, walked, triesLeft - 1, sizes)
    else
      HeadMovePlan.Outcome.NotHeld(
        s"no move to block ${target.show} could be planned: the state moved under every plan of it",
        None
      )

  private def planAt(
      epoch: Long,
      held: Option[BlockRef],
      target: BlockRef,
      link: ByteString => Option[HeadMovePlan.Link],
      sizes: Option[collection.mutable.Map[ByteString, Long]]
  ): HeadMovePlan.Outcome =
    held match
      case None =>
        HeadMovePlan.Outcome.NotHeld(s"no move to block ${target.show} can be made: there is no state anchor", None)
      case Some(anchor) =>
        val finalized = finalizedNow()
        val headers = link
        val sources = new HeadMovePlan.Sources:
          def recordHeader(
              blockHash: ByteString
          ): Option[Either[BlockDiffCodec.Rejected, BlockDiffCodec.Header]] =
            changesets.headerAndLength(blockHash).map { (header, length) =>
              sizes.foreach(_.update(blockHash, length))
              header
            }
          def layer(block: BlockRef): Option[ForwardLayer] = overlay.layerOf(block)
          def link(blockHash: ByteString): Option[HeadMovePlan.Link] = headers(blockHash)
        val limits = HeadMovePlan.Limits(
          // An attempt that holds the records above its fork may go back to it, however deep that has become: the
          // policy bounds what a move may undo, and not the way back from one the node made itself (forge)
          maxReverse = heldFrom.fold(settings.maxReorgDepth(anchor.number, finalized.map(_.number))) { from =>
            settings.maxReorgDepth(anchor.number, finalized.map(_.number)).max((anchor.number - from + 1).toInt)
          },
          // above the anchor a block is applied from a layer, or from the record of a branch that was the head
          maxForward = (settings.maxReverseHops.toLong + settings.maxForwardLayers).min(Int.MaxValue.toLong).toInt,
          prunedTo = appState.getChangesetPrunedTo(),
          origin = appState.getStateOrigin(),
          finalized = finalized
        )
        HeadMovePlan.of(anchor, epoch, target, sources, limits)

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
    refuseIfFaulted()
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
      notTheAnchor: () => Unit = () => (),
      // a reader of the chain the disk is on ([[viewBehind]]): a block that forward layers lead to is refused as soon
      // as the layers are found, before an index is built for a fork the reader would never be handed
      layersRefused: Boolean = false
  ): StateView =
    // The view may wait for an index another builds, and reads records to build one: under the state lock that is
    // every commit held up behind one read.
    if lock.isHeldByCurrentThread then
      throw new IllegalStateException(
        s"A view of block ${block.show} cannot be made by the holder of the state lock " +
          s"('${lock.holder.getOrElse("?")}'): it may wait for records to be read"
      )
    refuseIfFaulted()
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
                case Some(_) if layersRefused =>
                  throw new StateUnavailable(
                    s"the state of block ${block.show} is not held as a state of the chain the disk is on: it is an " +
                      "executed block kept beside it"
                  )
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
            if triesLeft > 0 then viewOf(block, reader, triesLeft - 1, notTheAnchor, layersRefused)
            else
              throw new StateUnavailable(s"the state of block ${block.show} is not held: the anchor could not be read")
          else if triesLeft > 0 then
            told("a view made again")(settings.observer.viewRetried())
            viewOf(block, reader, triesLeft - 1, notTheAnchor, layersRefused)
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
  private[pathstate] def readOnlyBehind(block: BlockRef, reader: Reader): MptStorage =
    PathMptStorage.readOnly(viewBehind(block, reader))

  /** [[readOnlyBehind]] for a peer that is served a state by its root: one request at a time, each done with its state
    * before the next is taken, so that what peers hold is one state. Whoever reads otherwise reads in a scope
    * ([[readOnlyInScope]]), where what is in flight is counted: this door counts nothing.
    */
  def readOnlyForPeer(block: BlockRef): MptStorage = readOnlyBehind(block, Reader.Peer)

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

  /** [[readOnlyInScope]] for an execution that commits nothing (`eth_call`, a simulation, a trace at a block of the
    * chain): what it persists is kept beside the view, read back by that execution, and dropped with the storage.
    * Nothing is written, no layer is kept, and no layer is read through or counted as used.
    */
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
          // A scope that holds every leave itself is not helped by asking again: it reads more blocks behind the
          // head at once than anyone may.
          if scope.holds(this) >= settings.maxReadsBehindHead then
            throw new StateUnavailable(
              s"the state of block ${block.show} is not read: one request reads the states of more than " +
                s"${settings.maxReadsBehindHead} blocks behind the head at once"
            )
          throw new StateUnavailable(
            s"the state of block ${block.show} is not available right now: as many states behind the head as may " +
              "be read at once are being read. Ask again",
            askAgain = true
          )
    // Counted when the view finds the block is not the one the disk holds, and before it reads a record for it: one
    // more than may be is refused before it costs a walk.
    viewBehind(block, Reader.Rpc, () => counted())

  /** The view of the anchor, or of a block behind it on its own chain, for a reader. A block that forward layers lead
    * to is refused when they are found, before any index is built for it (B-239): on a fork that index would be built
    * for a view the reader is never handed.
    */
  private def viewBehind(block: BlockRef, reader: Reader, notTheAnchor: () => Unit = () => ()): StateView =
    val view = viewOf(block, Some(reader), notTheAnchor = notTheAnchor, layersRefused = true)
    // refused in viewOf already; checked again so that no view through a layer is ever handed to a reader
    if view.layers.nonEmpty then
      throw new IllegalStateException(s"a reader's view of block ${block.show} reads through forward layers")
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
    // a store that failed closed takes no genesis either, unless its state was destroyed first (test mode)
    refuseIfFaulted()
    lock.locked(
      s"genesis commit of block ${genesis.show}",
      nanos => told("the wait for the state lock")(settings.observer.lockWaited(nanos))
    ) {
      val held = Seq(
        clock.anchor.map(anchor => s"the state of block ${anchor.show}"),
        Option.when(!rows.isEmpty)("rows of a state"),
        Option.when(!changesets.isEmpty)("changeset records"),
        appState.getChangesetPrunedTo().map(height => s"a count of pruned changesets ($height)"),
        appState.getStateOrigin().map(origin => s"a state that began at block ${origin.show}"),
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
          // the block this state began at: no move of the head goes below it
          appState.putStateOrigin(genesis).updates ++
          appState.putStateAnchor(genesis).updates
      clock.inFlight(StateMove(genesis, written.view.mapValues(_ => Option.empty[ByteString]).toMap))(
        dataSource.updateSync(updates)
      )
      told("the anchor of the genesis")(settings.observer.anchorAt(Some(genesis)))
    }

  /** Commits the end of a sync from scratch (spec 018 C5a part 5; data-model 7.4): the rows the sync wrote are the
    * state of `pivot` from here on. '''One batch''', synced: the caller's own updates (the pivot's header and weight,
    * the best-block pointer, what the sync says of its recoveries), the anchor, the block the state began at, and that
    * the sync is done. Nothing is copied and no row is written: the rows are there already, and the synced write makes
    * every write before it durable with it (they are in the one log, and no write of this store goes round it).
    *
    * It is one of the writers that make an anchor where there was none, so it runs with no anchor, under the lock of
    * the moves and the state lock, as a barrier of the clock: a view made before it is stale.
    *
    * There is no changeset record for the pivot: nothing this node holds came before it, and no move of the head goes
    * below it ([[AppStateStorage.getStateOrigin]]).
    *
    * '''What it does not vouch for.''' That the rows are the whole of the pivot's state: only that the node at the root
    * of the account trie is the one the pivot's state root names. The rest is the sync's, and the sample's
    * ([[com.chipprbots.ethereum.db.storage.PathHandoffSample]]), which the caller runs first.
    *
    * @throws IllegalStateException
    *   when the datadir already holds anything of a state: an anchor, a changeset record, a count of pruned records,
    *   the block a state began at, a finalized block, or a sync that says it is done; when the mark of a clear is set
    *   (the clear is finished first, [[finishPendingClear]]: its deletes take the flat copies of another state with
    *   them, which no read checks by hash); when the datadir is not stamped as this layout's; or when the row at the
    *   root of the account trie is not the node `pivot`'s state root names, or the root is the empty trie's and there
    *   are rows
    */
  def commitHandoff(pivot: BlockRef, extra: Option[DataSourceBatchUpdate]): Unit =
    extra.foreach(other =>
      require(other.dataSource eq dataSource, "a block and its state are written in one batch, to one data source")
    )
    require(pivot.number > 0, "a sync hands off at a block above the genesis")
    refuseIfFaulted()
    moves.lock()
    try
      lock.locked(
        s"the hand-off of a sync at block ${pivot.show}",
        nanos => told("the wait for the state lock")(settings.observer.lockWaited(nanos))
      ) {
        val held = Seq(
          clock.anchor.map(anchor => s"the state of block ${anchor.show}"),
          appState.getStateAnchor().map(anchor => s"a state anchor on disk (${anchor.show})"),
          Option.when(!changesets.isEmpty)("changeset records"),
          appState.getChangesetPrunedTo().map(height => s"a count of pruned changesets ($height)"),
          appState.getStateOrigin().map(origin => s"a state that began at block ${origin.show}"),
          appState.getStateFinalized().map(block => s"a finalized block (${block.number})"),
          Option.when(appState.isSnapSyncDone())("a sync that says it is done"),
          Option.when(appState.isStateResetPending())(
            s"the mark of a clear that was not finished (${StateResetPending.Key})"
          )
        ).flatten
        if held.nonEmpty then
          throw new IllegalStateException(
            s"A sync cannot hand off at block ${pivot.show} on a data directory that already holds " +
              held.mkString(", ")
          )
        if !appState.getStateLayout().contains(StateLayout.PathLive1) then
          throw new IllegalStateException(
            s"A sync cannot hand off at block ${pivot.show}: the data directory is not stamped as the unified " +
              s"layout's (${appState.getStateLayout().fold("no stamp")(stamp => s"'$stamp'")})"
          )
        // The one node a position store finds by its hash alone. It is read here, under the lock, and not taken on
        // the caller's word: an anchor over a root that is not there is a state no block can be executed on.
        val rootIsTheOneNamed =
          if pivot.stateRoot == BlockDiff.EmptyRoot then rows.isEmpty
          else rows.read(PositionKey.AccountRoot).exists(node => ByteString(kec256(node)) == pivot.stateRoot)
        if !rootIsTheOneNamed then
          throw new IllegalStateException(
            s"A sync cannot hand off at block ${pivot.show}: the row at the root of the account trie is not the " +
              "node its state root names (for the root of the empty trie: there are rows)"
          )

        val updates: Seq[DataUpdate] =
          extra.toSeq.flatMap(_.updates) ++
            appState.snapSyncDone().updates ++
            // the block this state began at: no move of the head goes below it
            appState.putStateOrigin(pivot).updates ++
            appState.putStateAnchor(pivot).updates
        clock.inFlightBarrier(pivot)(dataSource.updateSync(updates))
        told("the anchor of a hand-off")(settings.observer.anchorAt(Some(pivot)))
        // nothing was kept in memory of a datadir with no state; said again, so that it does not rest on that
        overlay.clear()
        window.set(None)
        indexes.clear()
        named = None
      }
    finally moves.unlock()

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
    *   when the block does not extend the anchor, or there is no anchor: nothing is written
    * @throws StateBusy
    *   while another thread's attempt that holds the records is open ([[withRecordsHeld]]): nothing is written, and the
    *   caller asks again once it has ended
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
    val finalized = finalizedNow().map(_.number)
    val outcome = adopting(s"canonical commit of block ${block.show}") {
      refuseInAnotherAttempt(s"the commit of block ${block.show}")
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
              bestIsTheAnchor(held, s"the commit of block ${block.show}")
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

  // ---- repair and on-demand writes by position (data-model 7.5, C5b) ----

  override def rawRowsAt(requests: Seq[(PositionKey, ByteString)]): Seq[RawNodeRead] =
    rows
      .readManyNoFill(requests.map(_._1))
      .map(_.fold[RawNodeRead](RawNodeRead.Absent)(RawNodeRead.Bytes(_)))

  /** A repair of one row (data-model 7.5): a state move, so under the state lock, with the anchor compare-and-set, and
    * one point write, in one batch. `bytes` go in only when they are the node `expectedHash` names, the anchor's root
    * still names `expectedHash` at `position`, and the row there is absent or does not decode. It is judged under the
    * lock, from the rows as they are then: a verdict the caller formed earlier, from a read that straddled a move,
    * writes nothing. A row that decodes and is not `expectedHash` is overwritten ([[RepairOutcome.Overwrote]]): the
    * anchor's root names `expectedHash` there, so the row is wrong for the state this store holds, and the old row is
    * recorded in the move. It is logged at ERROR with the old row's hash, and counted.
    *
    * Held for the walk from the root to `position` and one write. The worst case is about 128 uncached point reads (a
    * path is at most 64 nibbles, in the account trie and again in a storage trie; a mainnet trie is typically far
    * shallower, some tens of reads), each a RocksDB point lookup that bypasses the caches. It waits for a move of the
    * head under way, as any adopter does, which can be a whole multi-batch head move, and for no other lock. So it is
    * called off any actor thread, and it throws [[StateUnavailable]] on a store that has failed closed.
    */
  override def repairNodeAt(position: PositionKey, expectedHash: ByteString, bytes: Array[Byte]): RepairOutcome =
    if !StateStorage.hashesTo(expectedHash, bytes) then RepairOutcome.Rejected
    else
      adopting(s"repair of ${position.show}") {
        underAnchor(position, expectedHash) match
          case Left(declined) => declined
          case Right(held) =>
            rows.readNoFill(position) match
              case Some(row) if StateStorage.hashesTo(expectedHash, row) => RepairOutcome.AlreadyValid
              case prior =>
                writeRowInPlace(held, position, Some(ByteString(bytes)), prior)
                prior match
                  case Some(row) if decodesAsNode(row) =>
                    log.error(
                      s"[STATE-REPAIR] ${position.show} held a node that decodes and is not ${hexOfBytes(expectedHash.toArray)}, " +
                        s"which the anchor's root names there: overwritten. The old row hashed to " +
                        s"${hexOfBytes(kec256(row))}"
                    )
                    told("an overwrite of a row that decodes")(settings.observer.repairOverwrote())
                    RepairOutcome.Overwrote
                  case _ => RepairOutcome.Written
      }

  /** The delete of a row that is provably bad (data-model 6.3, 7.5), position-scoped. Under the state lock, from a read
    * of its own, so a stale verdict of the caller's deletes nothing: the row is read, the anchor's root is asked
    * whether it still reaches `expectedHash` at `position` (the real `stillReached`), and the row is read again before
    * the delete, which is the same row or no delete. Only an entry that does not decode as a node is provably bad here:
    * bytes that decode and are not `expectedHash` are another view's node, never deleted
    * ([[BadEntryDelete.DifferentNode]]). No anchor, or one that does not reach `expectedHash` there:
    * [[BadEntryDelete.NotReached]]. The same lock and the same worst-case hold as [[repairNodeAt]].
    */
  override def deleteBadNodeAt(position: PositionKey, expectedHash: ByteString): BadEntryDelete =
    def kept(row: Option[Array[Byte]]): Option[BadEntryDelete] = row match
      case None                                                      => Some(BadEntryDelete.NoLongerBad)
      case Some(bytes) if StateStorage.hashesTo(expectedHash, bytes) => Some(BadEntryDelete.NoLongerBad)
      case Some(bytes) if decodesAsNode(bytes)                       => Some(BadEntryDelete.DifferentNode)
      case Some(_)                                                   => None
    adopting(s"delete of a bad row at ${position.show}") {
      val first = rows.readNoFill(position)
      kept(first).getOrElse {
        underAnchor(position, expectedHash) match
          case Left(_) => BadEntryDelete.NotReached
          case Right(held) =>
            val again = rows.readNoFill(position)
            kept(again).getOrElse {
              if !sameRow(first, again) then BadEntryDelete.NoLongerBad
              else
                writeRowInPlace(held, position, None, again)
                BadEntryDelete.Deleted
            }
      }
    }

  /** The anchor, if the anchor's root still names `expectedHash` at `position`; the outcome to decline with otherwise.
    * Under the state lock: it is the compare-and-set of data-model 7.0 (an absent anchor is a failure of it).
    */
  private def underAnchor(position: PositionKey, expectedHash: ByteString)(using
      StateLockHeld
  ): Either[RepairOutcome, BlockRef] =
    clock.anchor match
      case None => Left(RepairOutcome.NotReached)
      case Some(held) =>
        AnchorReach.reach(held.stateRoot, position, rows.readNoFill) match
          case AnchorReach.Reach.Names(hash) if hash == expectedHash => Right(held)
          case _                                                     => Left(RepairOutcome.NotReached)

  private def hexOfBytes(bytes: Array[Byte]): String = bytes.map("%02x".format(_)).mkString

  private def decodesAsNode(row: Array[Byte]): Boolean =
    try
      com.chipprbots.ethereum.mpt.MptTraversals.decodeNode(row)
      true
    catch case NonFatal(_) => false

  private def sameRow(a: Option[Array[Byte]], b: Option[Array[Byte]]): Boolean = (a, b) match
    case (None, None)       => true
    case (Some(x), Some(y)) => java.util.Arrays.equals(x, y)
    case _                  => false

  /** One row, written or deleted in place, in the batch of a move that leaves the anchor where it is: a view made
    * before it sees the prior row through the log (data-model 6.3.1). Nothing else is in the batch: a repair is the
    * node and nothing it describes.
    */
  private def writeRowInPlace(
      held: BlockRef,
      position: PositionKey,
      next: Option[ByteString],
      prior: Option[Array[Byte]]
  )(using StateLockHeld): Unit =
    val updates = rows.rowUpdates(Seq(position -> next))
    clock.inFlight(StateMove(held, Map(position -> prior.map(ByteString(_)))))(dataSource.update(updates))

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
          told("no anchor")(settings.observer.anchorAt(None))
          // executed blocks kept beside a state that is gone are of no state, and neither is what was derived from
          // its records
          overlay.clear()
          window.set(None)
          indexes.clear()
          // the state a fault was found in is gone with it: the next genesis is committed on a store that holds none
          faulted = None
          // and so is what was finalized on it (its row goes with the state's marks)
          named = None
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

  /** Clears the state for a sync from scratch (spec 018 C5a part 3; data-model D.3, the ordered clear): for an
    * escalation, when the chain cannot be followed from the state that is held or that state is at fault, and for the
    * genesis state as SNAP starts. Outside test mode, on a datadir of any size, and the datadir stays the unified
    * layout's.
    *
    * In this order, under the lock of the moves and the state lock:
    *   1. one synced batch ([[AppStateStorage.stateClearBatch]]), written as a move of the clock to no state: the sync
    *      is no longer done, the anchor and the state's other marks are removed, SNAP's record of its last run is
    *      removed, the best block is the genesis, the escalation is recorded, and `state.reset-pending` is set. From
    *      here the datadir holds no state, whatever is still in its rows: every view made before is stale, and none can
    *      be made;
    *   1. what is kept in memory of the state that is gone is dropped;
    *   1. the rows and the changeset records are deleted, a range delete for each column family;
    *   1. `state.reset-pending` is removed, synced: that write makes the range deletes before it durable with it, so
    *      the mark never goes while a delete could be lost.
    *
    * A kill at any step: before the batch, nothing has changed; after it, the node starts with no anchor and with the
    * mark, and [[finishPendingClear]] deletes the rows again before anything reads them; after the last step, a datadir
    * SNAP starts on as on a new one. A batch that fails leaves the datadir and the anchor as they were.
    *
    * The caller's, and not checked here: nothing may be reading the rows through an iterator of its own (the audit, a
    * hand-off sample, a walker: a view goes stale, an iterator of its own goes on reading rows that are gone); no
    * import and no fork choice may be committing; and no sync may write rows until this has returned. If it throws
    * after its first batch the mark is left set: nothing may sync until [[finishPendingClear]] has run.
    *
    * '''Not while an attempt holds the records''' ([[withRecordsHeld]], B-465): an adoption of a heavier branch may be
    * moving the head to a state the chain then follows, and a clear in its middle would give that state up for a sync
    * from scratch. The clear is refused, with nothing written ([[StateClearRefused]]), and asked for again later. It
    * does not wait for the attempt: it holds the lock of the moves, which the attempt's next move takes.
    *
    * @param genesisHash
    *   the block at height 0, which the best block is set back to
    * @param atMillis
    *   the wall clock now
    * @param escalation
    *   why, when this is an escalation: recorded for the cool-down between two of them
    * @param givenUpFor
    *   on proof of work, the branch the escalation is for ([[GivenUpBranches]]): recorded in the same batch
    */
  @throws[StateClearRefused]("when an attempt holds the records: nothing was written")
  def clearForResync(
      genesisHash: ByteString,
      atMillis: Long,
      escalation: Option[StateEscalation],
      givenUpFor: Option[GivenUpBranch] = None
  ): Unit =
    val _ = clearUnderTheLocks(genesisHash, atMillis, escalation, givenUpFor, again = false)

  /** Clears the state once more, for a sync whose headers were of another chain than the branch its pivot is to be on
    * ([[PivotOnBranch]], B-307): the rows that sync wrote, the entries it stored in the index of blocks by number above
    * the block the branch stands on, and its pivot; the header download goes back to that block. One more attempt is
    * counted, in the same synced write that begins the clear. No escalation is recorded: this is the one that was.
    *
    * As for [[clearForResync]], the caller has stopped the sync, and nothing writes rows until this has returned; and
    * it is refused while an attempt holds the records.
    *
    * @return
    *   the requirement as counted, or none when no sync is bound (nothing is cleared then)
    */
  @throws[StateClearRefused]("when an attempt holds the records: nothing was written")
  def clearForBranchRetry(genesisHash: ByteString, atMillis: Long): Option[PivotOnBranch] =
    clearUnderTheLocks(genesisHash, atMillis, escalation = None, givenUpFor = None, again = true)

  private def clearUnderTheLocks(
      genesisHash: ByteString,
      atMillis: Long,
      escalation: Option[StateEscalation],
      givenUpFor: Option[GivenUpBranch],
      again: Boolean
  ): Option[PivotOnBranch] =
    moves.lock()
    try
      lock.locked(
        "a clear of the state for a sync from scratch",
        nanos => told("the wait for the state lock")(settings.observer.lockWaited(nanos))
      ) {
        // asked under the state lock, where a hold is opened: an attempt that is not listed here begins after the clear
        attemptThreads.values().iterator().asScala.nextOption().foreach { holder =>
          throw new StateClearRefused(
            s"an adoption on thread '${holder.thread.getName}' holds the records and is moving the head, which the chain may " +
              "follow from where it ends. Ask again once it has ended"
          )
        }
        val index = new BlockNumberMappingStorage(dataSource)
        val retry =
          if again then
            appState
              .getPivotOnBranch()
              // the wait for a peer on the branch is of the sync that ended: the next one begins its own (forge)
              .map(bound => bound.copy(attempts = bound.attempts + 1, firstMissMillis = None))
          else None
        if again && retry.isEmpty then None
        else
          val branch = retry.map(bound => GivenUpBranch(bound.number, bound.hash)).orElse(givenUpFor)
          val trim = Option.when(escalation.isDefined || retry.isDefined)(
            (trimAbove(branch, index), (height: BigInt) => index.get(height))
          )
          val begins: Seq[DataUpdate] =
            appState.stateClearBatch(genesisHash, atMillis, escalation, givenUpFor, trim, retry).updates
          clock.inFlightToNoState(dataSource.updateSync(begins))
          told("no anchor")(settings.observer.anchorAt(None))
          // executed blocks kept beside a state that is gone are of no state, and neither is what was derived from its
          // records, the fault that was found in it, or the block that was finalized on it
          overlay.clear()
          window.set(None)
          indexes.clear()
          faulted = None
          named = None
          deleteEveryRowAndRecord()
          deleteTheIndexAboveTheCommonBlock()
          appState.removeStateResetTrim().and(appState.removeStateResetPending()).commitSync()
          retry
      }
    finally moves.unlock()

  /** What a clear for an escalation deletes of the index of blocks by number (B-303; vault's design).
    *
    * '''The block the two chains share''': on proof of work, the block under the first block of the refused branch; on
    * proof of stake, the block that was finalized on the state that is given up, which no chain the consensus client
    * names leaves; where neither is known, the genesis. It is taken no higher than the index reaches, and lowered to a
    * block whose weight is stored, as far as [[PathStateStorage.CommonBlockWalk]] blocks and then to the genesis: the
    * header download works out each header's weight from its parent's, and stores nothing on a block that has none.
    */
  private def trimAbove(givenUpFor: Option[GivenUpBranch], index: BlockNumberMappingStorage): StateResetTrim =
    val upper = highestIndexedHeight()
    val known = givenUpFor
      .map(forkUnder(_, index))
      .orElse(appState.getStateFinalized().map(_.number))
      .getOrElse(BigInt(0))
      .max(0)
      .min(upper)
    val weights = new ChainWeightStorage(dataSource)
    def weighed(height: BigInt): Boolean = index.get(height).exists(weights.get(_).isDefined)
    val common = Iterator
      .iterate(known)(_ - 1)
      .take(PathStateStorage.CommonBlockWalk)
      .takeWhile(_ > 0)
      .find(weighed)
      .getOrElse(BigInt(0))
    if common == 0 && known > 0 then
      log.warn(
        s"[STATE-CLEAR] No block with a stored weight within ${PathStateStorage.CommonBlockWalk} blocks below block " +
          s"$known, which the chain that was left shares with the one to follow: the index of blocks by number is " +
          s"deleted from the genesis up to block $upper, and the headers are downloaded again."
      )
    StateResetTrim(common, upper)

  // The block a branch leaves the indexed chain at: under its first block where that block's parent is the indexed
  // one, as it is when the node's own chain was left for the branch. After a sync whose headers were of another chain
  // the index is that chain's, which may have left the branch lower down (forge): then the branch's stored headers
  // are walked down to the first whose parent the index has. A header that is not stored ends the walk where it is.
  private def forkUnder(branch: GivenUpBranch, index: BlockNumberMappingStorage): BigInt =
    val headers = new BlockHeadersStorage(dataSource)
    @scala.annotation.tailrec
    def walk(number: BigInt, hash: ByteString, left: Int): BigInt =
      headers.get(hash) match
        case Some(header) if left > 0 && number > 1 && !index.get(number - 1).contains(header.parentHash.value) =>
          walk(number - 1, header.parentHash.value, left - 1)
        case _ => number - 1
    walk(branch.number, branch.hash, PathStateStorage.CommonBlockWalk)

  // the highest height the index by number may have an entry at, the stored header of a pivot named by hash with it
  private def highestIndexedHeight(): BigInt =
    val headers = new BlockHeadersStorage(dataSource)
    appState.highestIndexedHeight(hash => headers.get(hash).map(_.number.value))

  // The entries of the index by number that [[StateResetTrim]] names, deleted a chunk at a time: the keys of the index
  // are not in the order of the heights, so no range delete says "above a height" (vault). Deleting one that is not
  // there changes nothing, so it is repeated whole after a kill. Not synced: whoever calls this writes synced after.
  private def deleteTheIndexAboveTheCommonBlock()(using StateLockHeld): Unit =
    appState.getStateResetTrim().foreach { trim =>
      val index = new BlockNumberMappingStorage(dataSource)
      val heights = trim.upper - trim.commonBlock
      if heights > PathStateStorage.IndexTrimSaid then
        log.info(
          s"[STATE-CLEAR] Deleting the index of blocks by number above block ${trim.commonBlock}: $heights heights"
        )
      Iterator
        .iterate(trim.commonBlock + 1)(_ + PathStateStorage.IndexTrimChunk)
        .takeWhile(_ <= trim.upper)
        .zipWithIndex
        .foreach { (from, chunk) =>
          val to = (from + PathStateStorage.IndexTrimChunk - 1).min(trim.upper)
          index.update(toRemove = (from to to), toUpsert = Nil).commit()
          if chunk > 0 && chunk % PathStateStorage.IndexTrimSaidEvery == 0 then
            log.info(s"[STATE-CLEAR] The index of blocks by number is deleted up to block $to of ${trim.upper}")
        }
    }

  /** As SNAP starts on a datadir whose state is its genesis (spec 018 C5a part 3; data-model 5.4, T205): the genesis
    * state is cleared, so that the sync writes its rows into families that hold none and the datadir reads as one it
    * starts on new. True when it was. A datadir whose state is any other block's, or that has none, is left as it is:
    * SNAP does not start over a state a node has followed the chain to, and a run that is resumed has no anchor.
    *
    * It is [[clearForResync]] with no escalation, mark and all: a kill between the batch and the range deletes leaves
    * the mark, and the start finishes it ([[finishPendingClear]]). Deleting the rows first and the anchor after, as the
    * data model had it, would leave a genesis anchor over rows that are gone if killed between the two (vault).
    *
    * It may throw [[StateClearRefused]], as [[clearForResync]] does, when an attempt holds the records: not met in
    * practice, since no adoption runs while SNAP starts on a genesis state.
    */
  @throws[StateClearRefused]("when an attempt holds the records; none does as a sync starts (B-465)")
  def clearGenesisStateForSync(genesis: BlockRef, atMillis: Long): Boolean =
    val heldIsGenesis = anchor.contains(genesis)
    if heldIsGenesis then clearForResync(genesis.hash, atMillis, escalation = None)
    heldIsGenesis

  /** At a start: finishes a clear that a kill interrupted ([[clearForResync]]), before anything reads the state. True
    * when there was one.
    *
    * It refuses a datadir that has the mark and a state: an anchor, or a sync that says it is done. The clear's first
    * batch removes both with the mark's own write, and a sync does not hand off while the mark is set
    * ([[commitHandoff]]), so the three together are no interrupted clear, and deleting the rows of a state that is in
    * use would destroy it for nothing. The node does not start; an operator looks.
    *
    * @param genesisHash
    *   the block at height 0, when the datadir has one: the best block is set back to it
    */
  @throws[IllegalStateException]("when the mark is set on a datadir that holds a state")
  def finishPendingClear(genesisHash: Option[ByteString]): Boolean =
    if !appState.isStateResetPending() then false
    else
      moves.lock()
      try
        lock.locked("the end of an interrupted clear of the state") {
          if appState.getStateAnchor().isDefined || appState.isSnapSyncDone() then
            throw new IllegalStateException(
              s"${StateResetPending.Key} is set on a data directory that holds a state (an anchor, or a sync that is " +
                "done): that is no interrupted clear, and its rows are not deleted. Take a copy of the data " +
                "directory first. Then either restore it, or move its rocksdb directory away to sync again."
            )
          // A sync that ran with the mark still set (the clear failed after its first batch, and the node went on)
          // has written its own record of what it downloaded, over rows that are deleted below: that record goes
          // first, again, or the next sync would skip what it says is done (vault).
          // and so with the header download's cursors, where the clear trims the index by number (B-303): a sync that
          // ran meanwhile stored headers above the block the chains share, whose entries are deleted below
          val index = new BlockNumberMappingStorage(dataSource)
          // What a sync reached since is read before the write that takes its record away (vault): the entries it
          // stored above what the clear wrote down are of the same chain that was left, or stand on it.
          val reached = highestIndexedHeight()
          val again = appState.getStateResetTrim().foldLeft(appState.stateClearRemovals(genesisHash)) { (batch, trim) =>
            batch
              .and(appState.putStateResetTrim(trim.copy(upper = trim.upper.max(reached))))
              .and(appState.headerCursorsBackTo(trim.commonBlock, index.get(_)))
          }
          dataSource.updateSync(again.updates)
          deleteEveryRowAndRecord()
          deleteTheIndexAboveTheCommonBlock()
          appState.removeStateResetTrim().and(appState.removeStateResetPending()).commitSync()
          log.warn("[STATE-CLEAR] A clear of the state that was interrupted has been finished: the rows are deleted")
          true
        }
      finally moves.unlock()

  // Every row of the account trie and of the storage tries and every changeset record, and with them what a sync
  // kept that is of those rows and of no others: the ranges it had downloaded (by state root: a sync that pivots to a
  // root it had been at would skip them over rows that are gone), the accounts it left out, the storage tries it
  // marked done, the queue of its healing walk, and the flat copies of accounts and slots. A range delete each,
  // whatever it holds. Not synced, and not in a batch: whoever calls this has set the mark, and writes synced after.
  private def deleteEveryRowAndRecord()(using StateLockHeld): Unit =
    val from = Array.emptyByteArray
    // above every key of these families: the longest is a storage row's 32-byte owner and its path
    val to = Array.fill[Byte](PathStateStorage.KeyBoundBytes)(0xff.toByte)
    Seq(
      Namespaces.StateChangesetNamespace,
      Namespaces.StateTriePathNamespace,
      Namespaces.StorageTriePathNamespace,
      Namespaces.SnapSyncProgressNamespace,
      Namespaces.BfsQueueNamespace,
      Namespaces.FlatAccountNamespace,
      Namespaces.FlatSlotNamespace
    ).foreach(dataSource.deleteRange(_, from, to))
    new SnapSyncProgressStorage(dataSource).clearExcluded()
    new SnapStorageDoneStorage(dataSource).clear()

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
          refuseIfFaulted()
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
        outcome match
          // the block is valid and its state is not held: whoever needs it executes the block again (beacon)
          case OverlayOutcome.NotKept(reason) => log.info(s"[STATE-OVERLAY] Block ${block.show} not kept: $reason")
          case _                              => ()
        outcome

  /** Whether the forward layer of `block` is kept: its state is held beside the disk's. */
  def holdsLayer(block: BlockRef): Boolean = overlay.layerOf(block).isDefined

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
    * @throws StateBusy
    *   while another thread's attempt that holds the records is open: nothing is written, and the caller asks again
    */
  def promote(block: BlockRef, extra: Option[DataSourceBatchUpdate]): CommitOutcome =
    extra.foreach(other =>
      require(other.dataSource eq dataSource, "a block and its state are written in one batch, to one data source")
    )
    val finalized = finalizedNow().map(_.number)
    val outcome = adopting(s"promotion of block ${block.show}") {
      refuseInAnotherAttempt(s"the promotion of block ${block.show}")
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
          // a move of one block up, from its layer: the one batch of a head move, with nothing reversed
          val step = HeadMovePlan.Forwarded(block, held, HeadMovePlan.Source.Layer(layer))
          val wrote = writeChunk(HeadMovePlan.Chunk(held, block, Vector.empty, Vector(step)), extra, finalized)
          val applied = CommitOutcome.Applied(wrote.commitOf(block))
          movedTo(held, block, applied)
          refuseIfFaulted()
          applied
    }
    outcome match
      case CommitOutcome.Applied(commit) =>
        told(s"the commit of block ${block.show}")(settings.observer.committed(commit))
        overlayTold()
      case CommitOutcome.AlreadyApplied => ()
    outcome

  /** That the best block is `anchor`, the block whose state the disk holds, before a batch that moves both is written
    * (data-model 5.1, I1): the two move together or not at all on this layout. A writer that moved the best block alone
    * (the rows a caller gave a move were built from an index another writer then changed; a writer the table of head
    * writers missed) is a defect, and nothing is written over it (beacon, forge). Asked only of a store that keeps the
    * node's own best block beside it ([[CommitSettings.checksBestBlock]]).
    */
  private def bestIsTheAnchor(anchor: BlockRef, what: String)(using StateLockHeld): Unit =
    if settings.checksBestBlock then
      val best = appState.getBestBlockInfo()
      if best.hash != anchor.hash || best.number != anchor.number then
        log.error(
          s"[STATE-MOVE] The best block is ${best.number} and the state is held at ${anchor.show}: a writer moved the " +
            s"best block without the state. $what was not written."
        )
        throw new IllegalStateException(
          s"$what was not written: the best block (${best.number}) is not the block the state is held at " +
            s"(${anchor.show})"
        )

  /** `body`, as a writer that adopts a block: under the state lock, and not in between two batches of a move of the
    * head. A writer that finds a move under way lets the state lock go, waits for the move to end, and asks again; one
    * that finds none pays nothing for the asking, and waits where every writer waits, in the state lock's own queue.
    * Nothing is written by a store that failed a check after a write.
    */
  @annotation.tailrec
  private def adopting[A](holder: String)(body: StateLockHeld ?=> A): A =
    val done: Option[A] =
      lock.locked(holder, nanos => told("the wait for the state lock")(settings.observer.lockWaited(nanos))) {
        if moving && !moves.isHeldByCurrentThread then None
        else
          refuseIfFaulted()
          Some(body)
      }
    done match
      case Some(result) => result
      case None =>
        moves.lock()
        moves.unlock()
        adopting(holder)(body)

  /** '''This store takes no further write''', and gives out no state, until its state is cleared: a caller found this
    * datadir's own changeset records corrupt (a plan refused `Corrupt`), and nothing is built over that (plan.md "C4b
    * part 1": `Corrupt` fails closed at the Engine API, C4d). It is logged at ERROR; the escalation is C4d's.
    *
    * It takes no lock: a write already past its check under the state lock completes, and no write that starts after
    * this does. The fault is recorded on disk, synced, so that a restart opens the store closed again (B-252).
    */
  def failClosed(why: String): Unit =
    if faulted.isEmpty then
      faulted = Some(why)
      log.error(s"[STATE-MOVE] This node's state takes no further write and is not read: $why")
      recordFault(why)

  /** The fault, written synced to AppState ([[StateFaultMark]]). A store that cannot record it is closed all the same,
    * in memory, and says at ERROR that a restart would open it.
    */
  private def recordFault(why: String): Unit =
    try appState.putStateFault(System.currentTimeMillis(), why).commitSync()
    catch
      case NonFatal(failed) =>
        log.error(
          s"[STATE-MOVE] The fault of this node's state could not be recorded ($failed): it is closed until it is " +
            "cleared or the node is started again, and a restart would open it"
        )

  /** Whether this store has failed closed ([[failClosed]], or a row read back that was not the row written). */
  def hasFailedClosed: Boolean = faulted.isDefined

  /** A store that wrote other bytes than it was given, or that failed closed, takes no write and gives out no state: a
    * block executed on it could be judged invalid, and that verdict is kept by whoever is told it (beacon).
    */
  private def refuseIfFaulted(): Unit =
    faulted.foreach { why =>
      throw new StateUnavailable(
        s"this node's state takes no write and is not read: $why. Start the node again"
      )
    }

  /** The heights whose changeset records an attempt holds against retention, by the attempt: see [[withRecordsHeld]].
    */
  private val recordHolds = new ConcurrentHashMap[Object, BigInt]()

  /** Each attempt that holds the records, by the attempt (B-294): the thread that opened it, what it holds and when it
    * was opened. An attempt adopts its branch on the thread that opened it: its moves and its commits are made there,
    * one after another.
    */
  private val attemptThreads = new ConcurrentHashMap[Object, OpenAttempt]()

  /** How long the oldest attempt that is open has been, in milliseconds of [[CommitSettings.nowMillis]]: none when none
    * is open (B-462). Read at any time, from any thread, without the state lock: what the gauge of its age reads.
    */
  def oldestAttemptAgeMillis: Option[Long] =
    attemptThreads
      .values()
      .iterator()
      .asScala
      .map(_.openedAt)
      .minOption
      .map(opened => math.max(0L, settings.nowMillis() - opened))

  // the gauge of the open attempt's age reads this store, by its own clock (B-462)
  told("the age of the open attempt")(settings.observer.attemptAgeOf(() => oldestAttemptAgeMillis))

  /** Whether an attempt holds the records right now ([[withRecordsHeld]]), read without a lock: for a caller that would
    * undo work of its own before a clear that is then refused (B-465). The clear's own check, under the state lock, is
    * the one that counts: an attempt may begin or end just after this is read.
    */
  def recordsHeld: Boolean = !attemptThreads.isEmpty

  /** The lowest height an attempt holds the records of, if any does. */
  private def heldFrom: Option[BigInt] =
    recordHolds.values().iterator().asScala.minOption

  /** '''No block is adopted, and no attempt begins, while another thread's attempt is open''' (spec 018 C4e part 4,
    * B-294). Such an attempt moves the head to a branch and commits its blocks in place, and may move it back: in the
    * meantime the head stands on blocks it may not stay on, and a block committed on one of them would be moved off by
    * the attempt's next write, or would take its place. The writer is refused, with nothing written, and asks again
    * once the attempt has ended. It does not wait for the attempt, which may be waiting for what that writer holds (the
    * gate of a block it executes; forge). A commit or a promotion may still wait before it gets here, as any adopter
    * does ([[adopting]]): for one move of the head under way on another thread, which takes no gate, and is then
    * refused. Asked under the state lock, where an attempt is opened: a writer that passes it finds the head settled,
    * or its own attempt's.
    *
    * The refusal is a [[StateBusy]] (B-462): no fault of this node, never counted by the escalation, said at WARN. An
    * attempt found open for longer than [[CommitSettings.attemptAgeWarnMillis]] is said once more, at WARN, the first
    * time a writer is refused by it: an adoption that holds the head that long is stuck, and every writer it refuses
    * meanwhile is asked to ask again, without end.
    */
  private def refuseInAnotherAttempt(what: String)(using StateLockHeld): Unit =
    val self = Thread.currentThread()
    attemptThreads.values().iterator().asScala.find(_.thread ne self).foreach { other =>
      val holder = s"the adoption on thread '${other.thread.getName}' (${other.what})"
      val age = math.max(0L, settings.nowMillis() - other.openedAt)
      if age > settings.attemptAgeWarnMillis && other.warned.compareAndSet(false, true) then
        log.warn(
          s"[STATE-BUSY] $holder has held the head for ${age / 1000}s, more than " +
            s"${settings.attemptAgeWarnMillis / 1000}s: every writer is refused until it ends, and $what was not made"
        )
      throw new StateBusy(
        holder,
        s"$what was not made: an adoption on thread '${other.thread.getName}' is moving the head, which stands on a " +
          "block it may not stay on until the adoption ends. Ask again then"
      )
    }

  /** `body`, with the changeset records of every block above `above` kept from retention for as long as it runs.
    *
    * For the adoption of a heavier branch by reverse-then-commit (the user's decision of 2026-11-12, plan.md "C4b: the
    * move core"): the head is moved down to the fork point, the branch is committed in place block by block, and if a
    * block of it fails the head is moved back to the old head by the records. Every in-place commit prunes, so without
    * this a branch longer than what is retained would delete the old branch's records, which are the way back, and its
    * own first ones. Nothing is pruned while it runs that the hold covers; the commit after it catches up.
    *
    * '''The attempt is the calling thread's''' (B-294): while it runs, no other thread adopts a block, moves the head
    * (but a fork choice) or begins an attempt ([[refuseInAnotherAttempt]]). So `body` must run synchronously on the
    * calling thread, its moves and commits included: one it hands to another thread, or makes on one (an `IO` shifted
    * off this thread, a future), is refused as another thread's write, with a [[StateBusy]].
    */
  @throws[ReorgTooDeep]("when the records above `above` are not all kept already: the way back is not there")
  @throws[StateBusy]("when another thread's attempt is open: nothing was held, and the caller asks again")
  def withRecordsHeld[A](above: BlockRef)(body: => A): A =
    val hold = new Object
    // under the lock, so that no commit prunes between the look at what is kept and the hold
    lock.locked(s"a hold of the records above block ${above.show}") {
      refuseInAnotherAttempt(s"the hold of the records above block ${above.show}")
      appState.getChangesetPrunedTo().filter(_ > above.number + 1).foreach { prunedTo =>
        throw new ReorgTooDeep(
          ReorgRefusal.Deep,
          s"the records above block ${above.show} cannot be held: retention has pruned them up to height $prunedTo"
        )
      }
      recordHolds.put(hold, above.number + 1)
      attemptThreads.put(
        hold,
        new OpenAttempt(Thread.currentThread(), s"the records above block ${above.show}", settings.nowMillis())
      )
    }
    // the hold is the attempt: the head is not settled until it ends, whatever moves and commits it makes. Opened
    // inside the `try`, so that nothing between the hold and its release can leave the hold, or its thread, behind
    try
      openAttempt()
      body
    finally
      recordHolds.remove(hold)
      attemptThreads.remove(hold)
      closeAttempt()

  /** Whether a move of the head is under way: from its plan to its last batch, the time between two batches included.
    * The head then steps through blocks that are not the one the move ends on, and nothing should be built on them.
    */
  def moveInProgress: Boolean = moving

  /** How many moves of the head have begun that move it: a confirmation of the head as it is, a move of nothing, is not
    * one (B-249). One is counted under the state lock before its first batch is built, so a build that overlaps any of
    * its writes sees the count change. A move refused before its first batch is built (by its plan, or by its caller's
    * rows) is not counted; one whose first batch is refused while it is built is, with nothing written, which is the
    * safe side. The count is one per move, not per batch: who builds on the head reads it before and after, and pairs
    * it with [[moveInProgress]], since a build that starts between two batches of a move sees no change in it (forge,
    * beacon).
    */
  def movesStarted: Long = movesBegun.get

  /** The generation of the head, for who builds a block on it (spec 018 C4e part 4, T190). Even while the head is
    * settled; odd from the first batch of a move of the head to its end, and for the whole of an attempt that holds the
    * records ([[withRecordsHeld]]): an adoption that moves the head to a branch and commits its blocks in place, and
    * may move it back. The head then stands on blocks it may not stay on. It changes by two for each of them, however
    * many batches, moves and commits it makes; a block committed on the head as it is does not change it, and neither
    * does a confirmation or a move refused before its first batch.
    *
    * The rule for a builder (a sequence lock): read it settled before the head is read ([[awaitHeadSettled]]), build,
    * read it again, and keep the build only if it is the same. [[moveInProgress]] and [[movesStarted]] are a move's own
    * and say nothing of an attempt.
    */
  def headGeneration: Long = generation.get

  /** The generation once the head is settled, waiting for at most `maxMillis` for a move or an attempt to end; none
    * when one is still open then. Not to be asked from inside a move or an attempt: it would wait for itself.
    */
  def awaitHeadSettled(maxMillis: Long): Option[Long] =
    val deadline = System.nanoTime() + maxMillis * 1000000L
    attempts.synchronized {
      @annotation.tailrec
      def settled(): Option[Long] =
        if openAttempts == 0 then Some(generation.get)
        else
          val left = (deadline - System.nanoTime()) / 1000000L
          if left <= 0 then None
          else
            attempts.wait(left)
            settled()
      settled()
    }

  /** '''Moves the head to `target`''': the disk's state becomes that block's, by the changeset records of the blocks
    * that are reversed and by the records or the layers of the blocks that are applied (spec 018 data-model 7.3,
    * plan.md "C4b: the move core").
    *
    * The move is planned from the anchor ([[planMove]]) and written as one batch, or as a chain of batches of at most
    * `state.move.max-blocks-per-batch` blocks. Each batch holds the rows, the records of the blocks that had none, the
    * anchor, and '''what the caller gives for it''': its index of blocks by number and its best-block pointer, so that
    * after any batch, and after a crash between two, the anchor and the best block are one block, on the way from the
    * old head down to the fork and up to the target.
    *
    * Before a batch is written every block of it is held to the disk as the batch so far leaves it, and the account
    * trie's root to the state root of the block it ends on ([[MoveBatch]]). A move that fails that writes nothing of
    * that batch; the batches before it stand.
    *
    * @param link
    *   a block as its header gives it, by its hash ([[HeadMovePlan.Sources.link]])
    * @param finalized
    *   the finalized block the fork choice behind this move named (spec 018 C4d part 1): its row is written in the
    *   batch that ends on `target`, and it is this store's under the state lock right after that batch, so a move or a
    *   commit that follows sees it, and one that was refused names nothing. Not written again when it is the one named
    * @param admit
    *   why the move as planned is not to be made, when it is not: asked of each plan before anything of it is written
    *   or counted, and a refusal ends the move with a [[StateUnavailable]] and nothing written (a fork choice moves the
    *   head up or across, never down to an ancestor of the head: C4d part 2b)
    * @param forkChoice
    *   a move a consensus client's fork choice asks for, which is made while another thread's attempt is open: the
    *   attempt follows the head the consensus client names, and stops when another is named (`StateRecovery`). Any
    *   other move is refused then, before its first batch, with a [[StateBusy]] ([[refuseInAnotherAttempt]], B-294)
    * @param extraFor
    *   the caller's rows for the batch that ends on a block. It is called with no lock of this store held but the one
    *   that keeps other moves and commits out, once for each batch, and again if the move is planned again; what it
    *   returns is written in that batch or not at all. For a target that is the head already it is called once, with a
    *   batch that moves nothing. '''It must take no lock that a thread committing a block may hold''': that thread
    *   waits for this move. And it must not move the head itself
    * @throws IllegalStateException
    *   when called from inside a move: by `extraFor`
    * @throws ReorgTooDeep
    *   when the move is refused: nothing was changed
    * @throws StateUnavailable
    *   when a block on the way up has neither record nor layer (`heldTo` says how far a move could be made), when the
    *   target's chain does not lead to this node's state, or when a layer was found not to stand and was dropped; and,
    *   asking again, when the state was moved by another writer between two of its batches, or under every plan of it
    * @throws StateBusy
    *   when it is no fork choice and another thread's attempt is open: nothing was written, and the caller asks again
    */
  def moveHead(
      target: BlockRef,
      link: ByteString => Option[HeadMovePlan.Link],
      extraFor: HeadMovePlan.Chunk => Option[DataSourceBatchUpdate],
      finalized: Option[FinalizedBlock] = None,
      admit: HeadMovePlan => Option[String] = _ => None,
      forkChoice: Boolean = false
  ): MoveOutcome =
    if moves.isHeldByCurrentThread then
      throw new IllegalStateException(s"A move of the head to ${target.show} cannot start inside another")
    // It may be long, and it waits: for a thread that may block (beacon).
    moves.lock()
    moving = true
    countedThisMove = false
    // the layers of each plan, held against eviction until the move is over
    val pin = new BranchPin(s"the move of the head to ${target.show}")
    try
      val outcome =
        try move(target, link, extraFor, finalized, admit, forkChoice, pin, MoveReplans)
        catch
          case NonFatal(failed) =>
            // A move that stopped part of the way kept the layers its later batches were to apply. Those of the
            // blocks it did apply go now, as they would have at its end.
            try lock.locked("the end of a move that failed")(clock.anchor.foreach(dropUnreachable))
            catch
              case NonFatal(inCleanup) => failed.addSuppressed(inCleanup)
              // released whatever came of it; a failure to release it is not the one that is thrown
            try lock.locked("the end of a move that failed")(overlay.release(pin))
            catch case NonFatal(inRelease) => failed.addSuppressed(inRelease)
            throw failed
      // before what the overlay holds is told
      lock.locked("the end of a move")(overlay.release(pin))
      outcome match
        case moved: MoveOutcome.Moved => told(s"the move of the head to ${target.show}")(settings.observer.moved(moved))
        case MoveOutcome.AlreadyThere => ()
      overlayTold()
      outcome
    finally
      // the generation is settled only once the move is whole, or has ended where it stopped
      if countedThisMove then closeAttempt()
      moving = false
      moves.unlock()

  private def move(
      target: BlockRef,
      link: ByteString => Option[HeadMovePlan.Link],
      extraFor: HeadMovePlan.Chunk => Option[DataSourceBatchUpdate],
      namesFinalized: Option[FinalizedBlock],
      admit: HeadMovePlan => Option[String],
      forkChoice: Boolean,
      pin: BranchPin,
      replansLeft: Int
  ): MoveOutcome =
    val (planned, sizes) = planMoveSized(target, link)
    val plan = planned match
      case HeadMovePlan.Outcome.Planned(plan) => plan
      case HeadMovePlan.Outcome.Refused(reason, detail) =>
        told("a move that was refused")(settings.observer.moveRefused(reason))
        throw new ReorgTooDeep(reason, s"to block ${target.show}: $detail")
      case HeadMovePlan.Outcome.NotHeld(detail, heldTo) => throw new StateUnavailable(detail, heldTo = heldTo)
    admit(plan).foreach(why => throw new StateUnavailable(why))
    lock.locked("the layers of a move's plan")(
      overlay.pinAll(
        plan.forward.collect { case HeadMovePlan.Forwarded(_, _, HeadMovePlan.Source.Layer(layer)) => layer },
        pin
      )
    )
    val finalized = finalizedNow().map(_.number)
    val started = System.nanoTime()
    val chunks =
      if plan.isNoMove then Vector(HeadMovePlan.Chunk(plan.anchor, plan.anchor, Vector.empty, Vector.empty))
      else
        // a batch is cut by its blocks and by what it reads: the records it reverses or applies, the layers it applies
        plan.chunks(
          settings.maxBlocksPerMove,
          settings.maxMoveBatchBytes,
          {
            case Left(step) => sizes.getOrElse(step.block.hash, 0L)
            case Right(HeadMovePlan.Forwarded(block, _, HeadMovePlan.Source.Record)) => sizes.getOrElse(block.hash, 0L)
            case Right(HeadMovePlan.Forwarded(_, _, HeadMovePlan.Source.Layer(layer))) => layer.bytes
          }
        )

    // The batches from `at` on, written one after the other; `epoch` is the epoch the state must still be at.
    @annotation.tailrec
    def write(at: Int, epoch: Long): Boolean =
      if at == chunks.size then true
      else
        val chunk = chunks(at)
        // the caller's rows are asked for outside the state lock: what makes them reads the caller's own stores
        val extra = extraFor(chunk)
        extra.foreach(other =>
          require(other.dataSource eq dataSource, "a move and its head are written in one batch, to one data source")
        )
        // the finalized block the fork choice named, in the batch that ends on the target (C4d part 1)
        val naming = namesFinalized.filter(_ => at == chunks.size - 1).filterNot(named.contains)
        val rows = naming.fold(extra) { block =>
          val row = appState.putStateFinalized(block)
          Some(extra.fold(row)(_.and(row)))
        }
        val next: Option[Long] = lock.locked(
          s"head move to block ${chunk.head.show}",
          nanos => told("the wait for the state lock")(settings.observer.lockWaited(nanos))
        ) {
          refuseIfFaulted()
          // before anything of it is written: a move under way is not stopped part of the way by an attempt opened
          // between two of its batches, whose own moves and commits wait for it to end
          if at == 0 && !forkChoice then refuseInAnotherAttempt(s"the move of the head to ${target.show}")
          val layersStand = chunk.forward.forall { step =>
            step.source match
              case HeadMovePlan.Source.Layer(layer) => overlay.layerOf(step.block).exists(_ eq layer)
              case HeadMovePlan.Source.Record       => true
          }
          if !clock.anchor.contains(chunk.from) || clock.epoch != epoch || !layersStand then None
          else if chunk.reverse.isEmpty && chunk.forward.isEmpty then
            // the caller's rows for the head as it is: written over a best block that is the anchor, as any batch is
            bestIsTheAnchor(chunk.from, s"the rows of the head at block ${chunk.from.show}")
            rows.foreach(_.commit())
            naming.foreach(block => named = Some(block))
            Some(epoch)
          else
            // counted under the lock, before its first batch is built: a confirmation of the head as it is moves
            // nothing, and a move refused before this moved nothing either; neither is a move to whoever builds on the
            // head (B-249)
            if !countedThisMove then
              // opened before it is said to be: the end of the move closes what was opened, and nothing else
              openAttempt()
              countedThisMove = true
              movesBegun.incrementAndGet()
            val wrote = writeChunk(chunk, rows, finalized)
            naming.foreach(block => named = Some(block))
            movedBy(chunk, wrote, last = at == chunks.size - 1)
            // after what is kept in memory has followed the disk: a store that failed its check is not left with the
            // window and the layers of another head
            refuseIfFaulted()
            Some(clock.epoch)
        }
        next match
          case Some(epochNow)  => write(at + 1, epochNow)
          case None if at == 0 => false
          case None =>
            throw new StateUnavailable(
              s"the move of the head to ${target.show} stopped at ${chunk.from.show}: the state was moved by another " +
                "writer between two batches of it",
              askAgain = true
            )

    if write(0, plan.epoch) then
      if plan.isNoMove then MoveOutcome.AlreadyThere
      else
        MoveOutcome.Moved(
          from = plan.anchor,
          to = target,
          fork = plan.fork,
          reversed = plan.reverse.size,
          applied = plan.forward.size,
          recorded = plan.newRecords.size,
          batches = chunks.size,
          nanos = System.nanoTime() - started
        )
    else if replansLeft > 0 then move(target, link, extraFor, namesFinalized, admit, forkChoice, pin, replansLeft - 1)
    else
      throw new StateUnavailable(
        s"no move of the head to ${target.show} could be made: the state, or an executed block of the plan, changed " +
          "under every plan of it",
        askAgain = true
      )

  /** Writes one batch of a head move, built against the disk as the batch so far leaves it ([[MoveBatch]]).
    *
    * A layer that does not stand is dropped, with what was built on it, and nothing is written. Retention prunes in a
    * batch that only applies blocks, as the commit of a block does, and never a height the batch itself records; a
    * batch that reverses a block prunes nothing: the records above the fork are what the way back is made of.
    */
  private def writeChunk(
      chunk: HeadMovePlan.Chunk,
      extra: Option[DataSourceBatchUpdate],
      finalized: Option[BigInt]
  )(using StateLockHeld): ChunkWritten =
    require(chunk.reverse.nonEmpty || chunk.forward.nonEmpty, "a batch of a head move reverses or applies a block")
    bestIsTheAnchor(chunk.from, s"the move to block ${chunk.head.show}")
    // In the node the state moves with the best block, so a batch that moves the state brings the caller's rows: one
    // that did not would leave the best block behind, and only the next batch would find it (beacon).
    if settings.checksBestBlock && extra.isEmpty then
      throw new IllegalStateException(
        s"the move to block ${chunk.head.show} was not written: it came without the rows that move the best block with it"
      )
    val batch = new MoveBatch(
      position => rows.read(position).map(ByteString(_)),
      (owner, maxRows, maxBytes) => rows.storageRows(owner, maxRows, maxBytes),
      changesets.getUncached,
      diffOf,
      maxOwnerRows,
      settings.maxOwnerScanBytes,
      settings.maxRecordBytes
    )
    val built =
      try
        chunk.reverse.foreach(batch.reverse)
        chunk.forward.foreach(batch.forward)
        batch.result(chunk.head)
      catch
        case refused: MoveBatch.LayerRefused =>
          overlay.drop(refused.block.hash)
          dropUnreachable(chunk.from)
          throw refused.cause
        case corrupt: ReorgTooDeep =>
          log.error(s"[STATE-MOVE] The move from ${chunk.from.show} to ${chunk.head.show} was not written: $corrupt")
          throw corrupt

    val (changesetUpdates, prunedHeights, prunedTo) = changesetIndexed(chunk.head) {
      val pruned =
        if chunk.reverse.isEmpty then pruning(chunk.forward.head.block.number, chunk.head.number, finalized)
        else
          // a block that was reversed had a record, so the count of pruned heights was begun
          val reached = appState.getChangesetPrunedTo().getOrElse {
            throw new IllegalStateException(
              s"A block was reversed on the way to ${chunk.head.show} on a datadir that never recorded one"
            )
          }
          Pruning(Nil, movedTo = None, reached = reached, heights = 0)
      // The deletes come before the records: a batch applies its updates in order, and no delete may follow the write
      // of a key it names.
      val moved = pruned.movedTo.toSeq.flatMap(appState.putChangesetPrunedTo(_).updates)
      (
        pruned.deletes ++ built.records.flatMap(written => changesets.recordUpdates(written.record)) ++ moved,
        pruned.heights,
        pruned.reached
      )
    }
    val updates: Seq[DataUpdate] =
      extra.toSeq.flatMap(_.updates) ++ rows.rowUpdates(built.written) ++ changesetUpdates ++
        appState.putStateAnchor(chunk.head).updates

    val started = System.nanoTime()
    // Nothing but the one write happens between the move's announcement and its end: a view asked for in there would
    // wait for this thread.
    // What a reader of the state before this batch must be given back is kept in memory for as long as the log keeps
    // the move. Above a bound it is not kept: the batch is a barrier, and every view made before it is stale (vault).
    val preBytes =
      built.before.iterator.map((position, node) => position.rowKey.length.toLong + node.fold(0)(_.length)).sum
    if preBytes > settings.maxMovePreBytes then clock.inFlightBarrier(chunk.head)(dataSource.update(updates))
    else clock.inFlight(StateMove(chunk.head, built.before))(dataSource.update(updates))
    val nanos = System.nanoTime() - started

    // A look at some of what was written. It cannot fail unless the store wrote other bytes than it was given, and
    // then nothing more is written to it, and no state is given out: the caller says so once what is kept in memory
    // has followed the disk.
    built.written.iterator
      .take(RowsReadBack)
      .find((position, node) => rows.read(position).map(ByteString(_)) != node)
      .foreach { (position, _) =>
        val why = s"${position.show}, after the move to block ${chunk.head.show}"
        val fault = s"a row read back after a write was not the row written ($why)"
        faulted = Some(fault)
        log.error(
          s"[STATE-MOVE] The row at $why is not the row that was written. This node's state takes no further write."
        )
        recordFault(fault)
      }

    ChunkWritten(
      positions = built.written.size,
      stateBytes = built.written.iterator
        .map((position, node) => position.rowKey.length.toLong + node.fold(0)(_.length))
        .sum,
      records = built.records,
      prunedHeights = prunedHeights,
      prunedTo = prunedTo,
      nanos = nanos
    )

  /** The anchor has moved by `chunk`, whose batch has returned. The window of ancestors is of another anchor now, and
    * is built again from the records when it is next needed; the indexes were of the old anchor; and, after the last
    * batch of a move, the layers that were applied go, with every one nothing leads to any more. Only now: see
    * [[movedTo]].
    */
  private def movedBy(chunk: HeadMovePlan.Chunk, wrote: ChunkWritten, last: Boolean)(using StateLockHeld): Unit =
    (chunk.reverse, chunk.forward) match
      case (Vector(), Vector(HeadMovePlan.Forwarded(block, parent, HeadMovePlan.Source.Layer(_)))) if last =>
        movedTo(parent, block, CommitOutcome.Applied(wrote.commitOf(block)))
      case _ =>
        window.set(None)
        indexes.clear()
        // Not between two batches: a layer a later batch applies may stand on a block this one has not come to yet,
        // and would be taken for one nothing leads to (beacon).
        if last then dropUnreachable(chunk.head)

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
    if before.iterator.map((position, node) => position.rowKey.length.toLong + node.fold(0)(_.length)).sum >
        settings.maxMovePreBytes
    then clock.inFlightBarrier(block)(dataSource.update(updates))
    else clock.inFlight(StateMove(block, before))(dataSource.update(updates))

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
    pruning(block.number, block.number, finalized)

  /** [[pruning]], in a batch that records the blocks from height `first` up to `head`. No height from `first` up is
    * pruned in it: the index of a height is read from the disk, which does not hold what this batch writes there, and a
    * record written and deleted in one batch would be left behind with nothing to name it (beacon).
    */
  private def pruning(first: BigInt, head: BigInt, finalized: Option[BigInt])(using StateLockHeld): Pruning =
    appState.getChangesetPrunedTo() match
      case None       => Pruning(Nil, movedTo = Some(first), reached = first, heights = 0)
      case Some(from) =>
        // and nothing a move's attempt holds (see [[withRecordsHeld]])
        settings.retention.floor(head, finalized).map(_.min(first)).map(floor => heldFrom.fold(floor)(floor.min)) match
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

  /** A key above every key of the state's column families, in bytes of 0xff: the bound of a range delete of one. */
  val KeyBoundBytes: Int = 128

  /** How far down a clear looks, from the block the two chains are known to share, for one whose weight is stored. */
  val CommonBlockWalk: Int = 4096

  /** How many entries of the index by number a clear deletes in one write. */
  val IndexTrimChunk: Int = 10000

  /** From how many heights a trim of the index is said, and after how many chunks its progress. */
  val IndexTrimSaid: Int = 100000
  val IndexTrimSaidEvery: Int = 100

  private val MaxFaultsShown = 3

  /** How often a move of the head is planned again because the state, or a layer of the plan, changed under it. */
  private val MoveReplans = 3

  /** How many of the rows a batch of a move wrote are read back after it. */
  private val RowsReadBack = 64

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

  /** How long an attempt that holds the records (an adoption by reverse-then-commit, or the fork choice's recovery) may
    * be open before a writer it refuses says so, once, at WARN (B-462): ten minutes. Such an attempt moves the head
    * down to a fork and commits the branch above it in place, a branch no longer than what the changeset records reach
    * and regular sync fetches whole: at the speed blocks are executed again (several a second, even on mainnet),
    * seconds to a few minutes. Ten minutes is well past that and short of what an operator would take for a node that
    * stopped following the chain. It changes nothing but the log: the refusal is never counted, however old the
    * attempt.
    */
  val AttemptAgeWarnMillis: Long = 10L * 60 * 1000

  /** An attempt that holds the records ([[PathStateStorage.withRecordsHeld]]), by the thread that opened it, what it
    * holds and when it was opened; `warned` once a writer it refused has said it is old (B-462).
    */
  final private[pathstate] class OpenAttempt(val thread: Thread, val what: String, val openedAt: Long):
    val warned = new java.util.concurrent.atomic.AtomicBoolean(false)

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
    *   the finalized block, where a consensus client names one. Asked once for each commit, before the state lock is
    *   taken, and once for each plan of a move: it must answer from what it holds, and not wait
    * @param maxOwnerScanBytes
    *   a replaced storage trie whose rows hold more than this keeps them too: the rows are read into memory under the
    *   state lock, and a count of rows alone does not bound that
    */
  final case class CommitSettings(
      maxEntriesPerOwnerDelete: Long = 1000000L,
      maxRecordBytes: Long = 256L * 1024 * 1024,
      retention: RetentionPolicy = RetentionPolicy.KeepAll,
      finalized: () => Option[FinalizedBlock] = () => None,
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
      // How many blocks one batch of a move of the head reverses and applies (`state.move.max-blocks-per-batch`).
      maxBlocksPerMove: Int = 256,
      // What one batch of a move may read, by its records' and its layers' bytes: a block that is more goes alone.
      // And above how many bytes of prior values a batch is a barrier that makes older views stale rather than keeping
      // them. Constants until they are measured (M6).
      maxMoveBatchBytes: Long = 128L * 1024 * 1024,
      maxMovePreBytes: Long = 64L * 1024 * 1024,
      // What the log of moves holds of prior values together (each move above `maxMovePreBytes` is a barrier and holds
      // none): the oldest moves go first, and the views older than them are then stale.
      maxMoveLogBytes: Long = 128L * 1024 * 1024,
      // Whether the node's best block is held to the anchor before a batch that moves both (I1): the node's own store
      // keeps its best block beside the state; a test of this package that writes none does not.
      checksBestBlock: Boolean = false,
      // Policy: how many blocks a move of the head may reverse, given the anchor's number and the finalized block's
      // (`state.reorg.max-depth`). What can be reversed at all is decided by the records that are there.
      maxReorgDepth: (BigInt, Option[BigInt]) => Int = (_, _) => Int.MaxValue,
      maxReverseIndexBytes: Long = 64L * 1024 * 1024,
      maxReversePoolBytes: Long = 128L * 1024 * 1024,
      // How many reads of a state behind the anchor callers of the JSON-RPC may have in flight: each may hold an
      // index of maxReverseIndexBytes that is not kept. A constant until it is measured (M5).
      maxReadsBehindHead: Int = 4,
      // The wall clock, in milliseconds: what the age of an attempt that holds the records is read by (B-462).
      nowMillis: () => Long = () => System.currentTimeMillis(),
      // How long an attempt may hold the records before a writer it refuses says so at WARN, once (B-462).
      attemptAgeWarnMillis: Long = AttemptAgeWarnMillis
  )

  object CommitSettings:
    def of(config: StateConfig, finalized: () => Option[FinalizedBlock], observer: CommitObserver): CommitSettings =
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
        maxBlocksPerMove = config.maxBlocksPerMoveBatch,
        checksBestBlock = true,
        // The archive mode keeps every record, so there the floor of retention is no bound: a depth that is
        // configured is, and otherwise what one plan holds.
        maxReorgDepth = (config.pruning.mode, config.reorgMaxDepth) match
          case (StateConfig.Mode.Archive, StateConfig.ReorgDepth.Floor) => (_, _) => HeadMovePlan.MaxBlocks
          case _                                                        => config.maxReorgDepth
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

  /** One batch of a head move, as it was written. */
  final private[pathstate] case class ChunkWritten(
      positions: Int,
      stateBytes: Long,
      records: Vector[MoveBatch.Recorded],
      prunedHeights: Int,
      prunedTo: BigInt,
      nanos: Long
  ):

    /** The batch as the commit of `block`, which it applied from its layer and which was all it did. */
    def commitOf(block: BlockRef): Commit =
      val written = records.find(_.block == block)
      Commit(
        block = block,
        positions = positions,
        stateBytes = stateBytes,
        recordBytes = written.fold(0L)(recorded => BlockDiffCodec.encodedLength(recorded.record)),
        tombstone = written.flatMap(_.tombstone),
        unlistedOwners = written.fold(Seq.empty[ByteString])(_.unlisted),
        prunedHeights = prunedHeights,
        prunedTo = prunedTo,
        nanos = nanos
      )

  /** What came of a move of the head. */
  enum MoveOutcome:

    /** The disk holds `to`'s state.
      *
      * @param fork
      *   the highest block the old head's chain and the new one's share
      * @param recorded
      *   the applied blocks that had no changeset record, and have one now
      * @param batches
      *   how many batches it was written as
      */
    case Moved(
        from: BlockRef,
        to: BlockRef,
        fork: BlockRef,
        reversed: Int,
        applied: Int,
        recorded: Int,
        batches: Int,
        nanos: Long
    )

    /** The target was the head already: the caller's own rows were written, and nothing else. */
    case AlreadyThere

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

    /** The block whose state the disk holds, where it is set otherwise than by a commit or a move (B-328): at open, at
      * a genesis commit, at a sync's hand-off, and none at a clear.
      */
    def anchorAt(anchor: Option[BlockRef]): Unit = ()

    /** The head was moved, by a move of more than the commit of one block. */
    def moved(move: MoveOutcome.Moved): Unit = ()

    /** A move of the head was refused. */
    def moveRefused(reason: ReorgRefusal): Unit = ()

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

    /** A repair overwrote a row that decodes and is not the node the anchor's root names there (data-model 7.5). */
    def repairOverwrote(): Unit = ()

    /** How to read the age of the oldest attempt that holds the records, in milliseconds, none when none is open: told
      * once, when the store is made (B-462).
      */
    def attemptAgeOf(ageMillis: () => Option[Long]): Unit = ()

  object CommitObserver:
    val Silent: CommitObserver = new CommitObserver {}
