package com.chipprbots.ethereum.db.storage.pathstate

import java.util.ArrayDeque

import org.apache.pekko.util.ByteString

import scala.jdk.CollectionConverters.*
import scala.util.Try

import org.bouncycastle.util.encoders.Hex

import com.chipprbots.ethereum.db.storage.PathNodeStorage

/** A block, named by what a view of its state needs: its hash, its number and its state root. */
final case class BlockRef(hash: ByteString, number: BigInt, stateRoot: ByteString):
  def show: String = s"$number (${Hex.toHexString(hash.toArray).take(16)})"

/** The finalized block, as a consensus client last named it: by its number, for what retention keeps, and by its hash,
  * for what a move of the head must not reverse. A number alone would say "finalized" of a block of another branch at
  * that height.
  */
final case class FinalizedBlock(number: BigInt, hash: ByteString)

/** The state anchor (spec 018 data-model 5.1): the one statement of which block's state the rows of CF `t` and CF `u`
  * hold. It is the AppState string `v1:<blockHash hex>:<number decimal>:<stateRoot hex>`, written in the same batch as
  * the rows it describes and never without them.
  */
object StateAnchor:
  val Key: String = "state.anchor"

  private val Version = "v1"
  private val HashHexLength = 64

  def encode(block: BlockRef): String =
    s"$Version:${Hex.toHexString(block.hash.toArray)}:${block.number}:${Hex.toHexString(block.stateRoot.toArray)}"

  /** The anchor a stored value names. A value this build cannot read is an error and not an absent anchor: with no
    * anchor every in-place writer refuses, and a datadir whose anchor is unreadable must not look like one that has
    * none.
    */
  def decode(value: String): BlockRef =
    def malformed: Nothing = throw new IllegalStateException(s"$Key is not a $Version anchor: '$value'")
    def hash(hex: String): ByteString =
      if hex.length != HashHexLength then malformed
      Try(ByteString(Hex.decode(hex))).getOrElse(malformed)
    value.split(':') match
      case Array(Version, blockHash, number, stateRoot) if number.nonEmpty && number.forall(_.isDigit) =>
        BlockRef(hash(blockHash), BigInt(number), hash(stateRoot))
      case _ => malformed

/** The layout stamp (spec 018 data-model 5.2): the AppState key `state.layout`, and the one value there is. A datadir
  * that has it holds its state in CF `t` and CF `u` under a [[StateAnchor]]. It is written in the batch that first
  * gives a datadir an anchor, and there is no second marker of its kind.
  */
object StateLayout:
  val Key: String = "state.layout"
  val PathLive1: String = "path-live-1"

/** One in-place change of the state on disk (data-model 6.3.1): the anchor it moves to, and for every position its
  * batch writes, the value a reader of the state '''before''' the move must see there (`None`: no row).
  */
final case class StateMove(newAnchor: BlockRef, pre: Map[PositionKey, Option[ByteString]])

/** The clock of in-place moves, as a reader sees it. The epoch is even while the disk is at rest and odd while a batch
  * is being written.
  */
trait StateClock:
  def epoch: Long

  /** The block whose state the disk holds, as of the last even epoch. */
  def anchor: Option[BlockRef]

  /** The moves whose batch began after epoch `from` and no later than epoch `to`, oldest first; `None` when the log no
    * longer reaches back to `from`.
    */
  def movesBetween(from: Long, to: Long): Option[Seq[StateMove]]

/** The clock, with the log of the last `maxMoves` moves (data-model 6.3.1, 6.4).
  *
  * A writer keeps this order, under the state lock: [[publish]] the move, write the batch, then [[settled]]. `publish`
  * puts the move in the log '''before''' it makes the epoch odd, and the batch is written after both, so a reader that
  * sees changed bytes and then reads the epoch always finds the move that changed them. Each move is logged under the
  * odd epoch it set: a reader that samples that odd epoch must find the move in flight.
  *
  * The in-place writers arrive with the staged commit (C3b). Until then nothing in the node calls [[publish]].
  */
final class StateMoveLog(maxMoves: Int, initialAnchor: Option[BlockRef], maxBytes: Long = Long.MaxValue)
    extends StateClock:
  require(maxMoves > 0, "the move log keeps at least one move")

  @volatile private var current: Long = 0L
  @volatile private var anchorNow: Option[BlockRef] = initialAnchor
  private val log = new ArrayDeque[(Long, StateMove)]()

  /** What the moves in the log hold of prior values: the log keeps as many moves as it may, and no more bytes than it
    * may. A move it lets go makes the views older than it stale, never wrong (forge, vault).
    */
  private var bytesHeld: Long = 0L
  private def bytesOf(move: StateMove): Long =
    move.pre.iterator.map((position, node) => position.rowKey.length.toLong + node.fold(0)(_.length)).sum

  override def epoch: Long = current

  override def anchor: Option[BlockRef] = anchorNow

  /** The epoch and the anchor of a disk at rest, or `None` while a batch is being written. */
  def atRest: Option[(Long, Option[BlockRef])] =
    val before = current
    if before % 2 != 0 then None
    else
      val held = anchorNow
      Option.when(current == before)((before, held))

  /** The epoch and the anchor of a disk at rest, waiting out a batch that is being written: one batch at most. */
  def awaitRest(): (Long, Option[BlockRef]) = synchronized {
    while current % 2 != 0 do wait()
    (current, anchorNow)
  }

  /** A batch is about to be written. Only [[inFlight]] and tests of this package call it: a writer that published and
    * did not settle would leave every later view waiting.
    */
  private[pathstate] def publish(move: StateMove): Unit = synchronized {
    require(current % 2 == 0, "a state move is already being written")
    log.addLast((current + 1, move))
    bytesHeld += bytesOf(move)
    // the newest move stays whatever it holds: a reader of the batch in flight must find it
    while log.size > maxMoves || (bytesHeld > maxBytes && log.size > 1) do bytesHeld -= bytesOf(log.removeFirst()._2)
    current += 1
  }

  /** The batch [[publish]] announced has returned. `committed = false` when it failed and the disk is as it was: the
    * move stays in the log, where its prior values are simply the values still on disk.
    */
  private[pathstate] def settled(committed: Boolean = true): Unit = synchronized {
    require(current % 2 == 1, "no state move is being written")
    if committed then anchorNow = Some(log.peekLast()._2.newAnchor)
    current += 1
    notifyAll()
  }

  /** `write`, as the batch of `move`: published before it, settled after it, also when it throws. This is how a writer
    * makes a move; [[publish]] and [[settled]] are apart only so that a test can stand between them.
    *
    * A `write` that throws is taken not to have written anything, which holds for one `DataSource.update`: a RocksDB
    * WriteBatch is applied whole or not at all. The move then stays in the log with the disk unchanged, the anchor is
    * kept, and the epoch still advances, so a view made before it is no longer the view of what is on disk and a
    * mismatch it meets is stale, never corrupt.
    */
  def inFlight[A](move: StateMove)(write: => A): A =
    publish(move)
    var committed = false
    try
      val result = write
      committed = true
      result
    finally settled(committed)

  /** `write`, as the batch of a move whose prior values are too many to keep: a '''barrier'''. The log is emptied
    * before the epoch is made odd, so every view made before it finds that the log does not reach back to it, and is
    * stale: never wrong, as a move logged without its prior values would leave it. The disk's anchor is `newAnchor`
    * once the batch has returned. A `write` that throws is taken to have written nothing, as in [[inFlight]].
    */
  private[pathstate] def inFlightBarrier[A](newAnchor: BlockRef)(write: => A): A =
    synchronized {
      require(current % 2 == 0, "a state move is already being written")
      log.clear()
      bytesHeld = 0L
      current += 1
    }
    var committed = false
    try
      val result = write
      committed = true
      result
    finally
      synchronized {
        if committed then anchorNow = Some(newAnchor)
        current += 1
        notifyAll()
      }

  /** `write`, as the batch that leaves the disk with '''no state''': every row deleted, and the anchor with them. It is
    * not a move: a move has an anchor to move to, and prior values for what it changed, and this has neither.
    *
    * The log is emptied '''before''' the epoch is made odd. A view made earlier that reads a row the batch has deleted,
    * and then the epoch, must not find a log that reaches back to it with no move for that row: it would take "no row"
    * for its state. With the log empty it finds that the log does not reach back, and is stale. When the batch has
    * returned there is no anchor, so no new view can be made until a genesis is committed.
    *
    * A `write` that throws is taken to have written nothing, as in [[inFlight]]: the anchor is kept and a new view
    * reads the disk as it still is. The views made before it are stale all the same.
    */
  private[pathstate] def inFlightToNoState[A](write: => A): A =
    synchronized {
      require(current % 2 == 0, "a state move is already being written")
      log.clear()
      bytesHeld = 0L
      current += 1
    }
    var committed = false
    try
      val result = write
      committed = true
      result
    finally
      synchronized {
        if committed then anchorNow = None
        current += 1
        notifyAll()
      }

  override def movesBetween(from: Long, to: Long): Option[Seq[StateMove]] = synchronized {
    if to <= from then Some(Nil)
    else
      // The moves are logged under consecutive odd epochs, so the first one after an even `from` is `from + 1`.
      val firstNeeded = if from % 2 == 0 then from + 1 else from + 2
      val oldest = Option(log.peekFirst()).map(_._1)
      if firstNeeded <= to && !oldest.exists(_ <= firstNeeded) then None
      else Some(log.iterator.asScala.collect { case (at, move) if at > from && at <= to => move }.toSeq)
  }

/** A view of the state of one block (data-model 6.1, 6.3), able to say what any position held in that state.
  *
  * The view of the '''anchor''', the block whose state is on disk when the view is made, has no layers: a read is a
  * point read of the row, corrected by the log of moves when the disk has moved since. The view of a block that was
  * executed on the anchor's state and is not on disk has that block's '''forward layers''' in front of the same read:
  * the newest layer that wrote a position answers for it (data-model 6.2). The view holds its layers itself, so what it
  * reads does not change when a layer is promoted to the disk or dropped: the rows under it stay those of its [[base]]
  * through the log of moves, for as long as the log reaches back. The view of a block '''behind the anchor''', or of a
  * block on a fork that left the anchor's chain there, has a '''reverse index''' behind its layers: what that block's
  * state held at every position the blocks after it changed, from their records.
  *
  * @param createdAt
  *   the even epoch at which the view was made
  * @param forward
  *   the layers between this view's state and the state they stand on (the disk's, or the one the index gives), newest
  *   first; none for the view of the anchor or of a block behind it
  */
final class StateView private[pathstate] (
    val block: BlockRef,
    val createdAt: Long,
    clock: StateClock,
    rows: PathNodeStorage,
    forward: List[ForwardLayer] = Nil,
    reverse: Option[ReverseIndex] = None
):

  /** The block whose state the disk held when the view was made: what the rows under everything else are the state of.
    * With a reverse index it is the anchor the index was built from, whatever block the layers stand on.
    */
  val base: BlockRef = reverse.map(_.from).getOrElse(forward.lastOption.fold(block)(_.parent))

  /** Whether this view reads through anything but the rows: forward layers, a reverse index, or both. */
  def layered: Boolean = forward.nonEmpty || reverse.nonEmpty

  /** What position `p` held in this view's state: the stored node's RLP, or `None` for no row.
    *
    * The row is read first and the epoch second. If the epoch is the one the view was made at, no batch began in
    * between and the row is the view's. Otherwise the earliest move since then that wrote `p` knows what was there
    * before it; if none wrote `p`, the row has not changed and stands, also while a batch is in flight.
    *
    * @throws StaleStateViewException
    *   when the log no longer reaches back to the view
    */
  def read(p: PositionKey): Option[Array[Byte]] = readFrom(p)._1

  /** [[read]], and what answered. What a layer or the reverse index gives is this view's whatever the disk does: the
    * newest layer that wrote the position, else the index (what the state the layers stand on held there), else the row
    * as the base held it.
    */
  private[pathstate] def readFrom(p: PositionKey): (Option[Array[Byte]], StateView.Source) =
    // the anchor's view, which every block of a node that follows the head is executed on, has nothing to look through
    if forward.isEmpty && reverse.isEmpty then (onDiskAsOfBase(p), StateView.Source.Disk)
    else
      forward.iterator.map(_.lookup(p)).collectFirst { case Some(left) => left.map(_.toArray) } match
        case Some(answer) => (answer, StateView.Source.Layer)
        case None =>
          reverse.flatMap(_.lookup(p)) match
            case Some(held) => (held.map(_.toArray), StateView.Source.Index)
            case None       => (onDiskAsOfBase(p), StateView.Source.Disk)

  /** A node one of this view's layers leaves, by its hash. */
  private[pathstate] def layerNode(hash: ByteString): Option[Array[Byte]] =
    forward.iterator.map(_.byHash.get(hash)).collectFirst { case Some(node) => node.toArray }

  private def onDiskAsOfBase(p: PositionKey): Option[Array[Byte]] =
    val onDisk = rows.read(p)
    val now = clock.epoch
    if now == createdAt then onDisk
    else
      clock.movesBetween(createdAt, now) match
        case None =>
          throw new StaleStateViewException(
            block,
            createdAt,
            now,
            "the log of state moves no longer reaches back to it"
          )
        case Some(moves) =>
          moves.collectFirst { case move if move.pre.contains(p) => move.pre(p) } match
            case Some(before) => before.map(_.toArray)
            case None         => onDisk

  /** Whether this is, right now, the view of what is on disk: nothing has moved since it was made and its block is the
    * anchor. Decided when asked, never remembered: a mismatch found on such a view is the disk's own fault, while on
    * any other view it only means the disk has moved on.
    */
  def isAnchorViewNow: Boolean =
    forward.isEmpty && reverse.isEmpty && clock.epoch == createdAt && clock.anchor.contains(block)

  /** The forward layers this view reads through, newest first. */
  private[pathstate] def layers: List[ForwardLayer] = forward

  /** Whether the rows under this view are, right now, the state it takes them for: nothing has moved since it was made
    * and its base is the anchor. A mismatch met then will be met again by the next read.
    */
  def diskHalfAtRestNow: Boolean = clock.epoch == createdAt && clock.anchor.contains(base)

  /** The epoch right now, for a fault that reports how far the disk has moved on. */
  def epochNow: Long = clock.epoch

object StateView:

  /** What answered a read of a view. */
  private[pathstate] enum Source:
    case Layer, Index, Disk

  /** The block before any block: what a view of no state is a view of. */
  private val BeforeGenesis: BlockRef =
    BlockRef(
      ByteString(new Array[Byte](32)),
      BigInt(-1),
      ByteString(com.chipprbots.ethereum.mpt.MerklePatriciaTrie.EmptyRootHash)
    )

  /** A clock that never moves, for a view that reads no disk. */
  private object Still extends StateClock:
    override def epoch: Long = 0L
    override def anchor: Option[BlockRef] = None
    override def movesBetween(from: Long, to: Long): Option[Seq[StateMove]] = Some(Nil)

  /** A view of no state: there is no row at any position. What a genesis is built over, on a storage that then holds
    * only what the build writes (it reads back nothing else).
    */
  private[pathstate] def ofNothing: StateView =
    new StateView(
      BeforeGenesis,
      0L,
      Still,
      new PathNodeStorage(com.chipprbots.ethereum.db.dataSource.EphemDataSource())
    )
