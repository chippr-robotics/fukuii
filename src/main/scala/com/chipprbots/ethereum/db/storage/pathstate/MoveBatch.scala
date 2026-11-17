package com.chipprbots.ethereum.db.storage.pathstate

import org.apache.pekko.util.ByteString

import scala.collection.mutable

import com.chipprbots.ethereum.crypto.kec256

/** The rows one batch of a head move writes ([[HeadMovePlan.Chunk]]): the blocks it reverses and the blocks it applies,
  * coalesced to one value for each position (spec 018 data-model 7.3, plan.md "C4b: the move core").
  *
  * '''It is built against a virtual disk''': the rows as they are, under what the batch has done so far. A block that
  * is applied from its layer has its priors, and the rows of the storage tries it replaces, read from its parent's
  * state, and inside one batch that state is on no disk: it is the disk with the blocks before it reversed and applied.
  * Settled against the disk itself, its record would list the wrong rows, and a later move back by that record would
  * write a wrong state.
  *
  * '''Every step is held to that virtual disk before anything is written.''' What a reversed block left at a position
  * must be what is there; what an applied record found at a position must be what is there. A step that is not is this
  * data directory's corruption, and the batch is not built. Those reads are the check of the interior that a look at
  * the root row alone would not be.
  *
  * It is made and used under the state lock, by one thread, for one chunk.
  *
  * @param read
  *   the row at a position, as the disk holds it
  * @param ownerRows
  *   the rows of an owner's storage trie as the disk holds them, or `None` when there are more of them than the count
  *   given, or they hold more than the bytes given
  * @param recordOf
  *   the changeset record of a block, by its hash
  * @param diffOf
  *   what a block settled as, as its diff on its parent, held to the two state roots
  * @param maxOwnerRows
  *   a replaced storage trie with more rows than this keeps them
  * @param maxOwnerScanBytes
  *   and so does one whose rows hold more than this
  * @param maxRecordBytes
  *   a record above this is written as a tombstone
  */
final private[pathstate] class MoveBatch(
    read: PositionKey => Option[ByteString],
    ownerRows: (ByteString, Int, Long) => Option[Iterable[(PositionKey, ByteString)]],
    recordOf: ByteString => Option[Either[BlockDiffCodec.Rejected, BlockDiff]],
    diffOf: (StagedChanges.Final, BlockRef, BlockRef) => BlockDiff,
    maxOwnerRows: Int,
    maxOwnerScanBytes: Long,
    maxRecordBytes: Long
):
  import MoveBatch.*

  /** The disk's row at each position looked at, read once: what a reader of the state before the move sees there. */
  private val pre = mutable.HashMap.empty[PositionKey, Option[ByteString]]

  /** What the batch so far leaves at each position it has changed. */
  private val virtual = mutable.HashMap.empty[PositionKey, Option[ByteString]]

  /** The positions of `virtual` in each storage trie: for the rows of an owner as the virtual disk holds them. */
  private val changedOf = mutable.HashMap.empty[ByteString, mutable.Set[PositionKey]]

  private val recorded = Vector.newBuilder[Recorded]

  private def before(position: PositionKey): Option[ByteString] = pre.getOrElseUpdate(position, read(position))

  /** The row at `position` on the virtual disk. */
  def now(position: PositionKey): Option[ByteString] = virtual.getOrElse(position, before(position))

  private def set(position: PositionKey, node: Option[ByteString]): Unit =
    virtual.update(position, node)
    position.owner.foreach(owner => changedOf.getOrElseUpdate(owner, mutable.Set.empty) += position)

  /** The rows of `owner`'s storage trie on the virtual disk, or `None` when they are more, or hold more, than a
    * replaced trie's rows may: '''the same answer a scan of that state on a disk would give'''. The bounds are those of
    * the virtual rows, so the disk is scanned for as many more as the batch so far has changed of this owner's: an
    * owner the batch has shrunk is listed as a commit on its parent's state would list it, and one it has grown is not.
    * Else the record of a block, and whether its old rows stay, would depend on how a move was cut into batches (forge,
    * beacon).
    */
  private def rowsNow(owner: ByteString): Option[Iterable[(PositionKey, ByteString)]] =
    def bytesOf(position: PositionKey, node: ByteString): Long = position.rowKey.length.toLong + node.length
    val changed: collection.Set[PositionKey] = changedOf.getOrElse(owner, Set.empty)
    // what the disk holds at the positions the batch changed: a scan of the disk meets those rows too
    val changedOnDisk = changed.iterator.flatMap(position => before(position).map(bytesOf(position, _))).toVector
    val rowsBound = (maxOwnerRows.toLong + changedOnDisk.size).min(Int.MaxValue.toLong).toInt
    val bytesBound =
      if maxOwnerScanBytes > Long.MaxValue - changedOnDisk.sum then Long.MaxValue
      else maxOwnerScanBytes + changedOnDisk.sum
    ownerRows(owner, rowsBound, bytesBound).flatMap { onDisk =>
      val rows =
        if changed.isEmpty then onDisk.toVector
        else
          onDisk.iterator.filterNot((position, _) => changed(position)).toVector ++
            changed.iterator.flatMap(position => virtual(position).map(position -> _))
      val bytes = rows.iterator.map(bytesOf).sum
      Option.when(rows.size <= maxOwnerRows && bytes <= maxOwnerScanBytes)(rows)
    }

  private def corrupt(detail: String): Nothing = throw new ReorgTooDeep(ReorgRefusal.Corrupt, detail)

  /** The complete record of `block` on `parent`, held to both: the plan read its header, and the state lock has been
    * held since the anchor and the epoch were found to be the plan's, so a record that is not this is corruption.
    */
  private def complete(block: BlockRef, parent: BlockRef): BlockDiff =
    recordOf(block.hash) match
      case None => corrupt(s"the changeset record of block ${block.show} is not there")
      case Some(Left(rejected)) =>
        corrupt(s"the changeset record of block ${block.show} cannot be read (${rejected.reason}: ${rejected.detail})")
      case Some(Right(diff)) =>
        if diff.incomplete then corrupt(s"the changeset record of block ${block.show} lists nothing")
        else if diff.number != block.number || diff.postStateRoot != block.stateRoot ||
          diff.parentHash != parent.hash || diff.preStateRoot != parent.stateRoot
        then corrupt(s"the changeset record of block ${block.show} is not that block's on ${parent.show}")
        else diff

  /** `step`'s block is reversed: each position it changed goes back to what it held before the block. */
  def reverse(step: HeadMovePlan.Reversed): Unit =
    val diff = complete(step.block, step.parent)
    diff.changes.foreach { (position, change) =>
      if now(position) != change.next then
        corrupt(
          s"the row at ${position.show} is not the one block ${step.block.show} left there: the state on disk is " +
            "not the state the changeset records describe"
        )
      set(position, change.prior)
    }

  /** `step`'s block is applied: from its record, or from the layer its execution left, which is settled here and given
    * a record.
    *
    * @throws LayerRefused
    *   when the layer was not built on the state its parent has on the virtual disk, or does not settle as the diff of
    *   its block: nothing was changed, and the layer is the caller's to drop
    */
  def forward(step: HeadMovePlan.Forwarded): Unit = step.source match
    case HeadMovePlan.Source.Record =>
      val diff = complete(step.block, step.parent)
      diff.changes.foreach { (position, change) =>
        // Where the record names no prior the row is not looked at, as a layer's is not: a storage trie replaced with
        // too many rows to list leaves them behind, and the block that first writes where one lies names none there.
        if change.prior.isDefined && now(position) != change.prior then
          corrupt(
            s"the row at ${position.show} is not the one block ${step.block.show} was applied over: the state on " +
              "disk is not the state the changeset records describe"
          )
        set(position, change.next)
      }
    case HeadMovePlan.Source.Layer(layer) =>
      layer.changesOver(now) match
        case Left(position) =>
          throw new LayerRefused(
            step.block,
            new StateUnavailable(
              s"the executed block ${step.block.show} was not built on the state the disk holds: it found another " +
                s"node at ${position.show} than the row there. It is dropped, and is executed again"
            )
          )
        case Right(changes) =>
          val finalised = StagedChanges.settle(changes, layer.resets, rowsNow)
          val diff =
            try diffOf(finalised, step.parent, step.block)
            catch
              case fault: (IllegalStateException | IllegalArgumentException) =>
                throw new LayerRefused(step.block, fault)
          val tombstone: Option[PathStateStorage.TombstoneCause] =
            if finalised.unlisted.nonEmpty then Some(PathStateStorage.TombstoneCause.OwnerDeleteBound)
            else if BlockDiffCodec.encodedLength(diff) > maxRecordBytes then
              Some(PathStateStorage.TombstoneCause.RecordSize)
            else None
          val record = if tombstone.isEmpty then diff else diff.copy(changes = Map.empty, incomplete = true)
          diff.changes.foreach((position, change) => set(position, change.next))
          finalised.unrecorded.foreach((position, node) => set(position, Some(node)))
          recorded += Recorded(step.block, record, tombstone, finalised.unlisted)

  /** The batch, for a move that ends on `head`: the rows it writes, what each held, and the records of the blocks that
    * had none. The account trie's root row on the virtual disk must be `head`'s state root, '''before''' anything is
    * written.
    *
    * @throws ReorgTooDeep
    *   (`Corrupt`) when it is not: what was reversed and applied does not make up that block's state
    */
  def result(head: BlockRef): Built =
    val root = now(PositionKey.AccountRoot).fold(BlockDiff.EmptyRoot)(node => ByteString(kec256(node.toArray)))
    if root != head.stateRoot then
      corrupt(
        s"the blocks reversed and applied on the way to ${head.show} do not leave its state: the account trie's " +
          "root would be another"
      )
    val written = virtual.iterator.filter((position, node) => before(position) != node).toMap
    Built(written, written.map((position, _) => position -> before(position)), recorded.result())

private[pathstate] object MoveBatch:

  /** A block applied from its layer, and the record written for it.
    *
    * @param unlisted
    *   the owners whose storage trie it replaced with too many rows to list
    */
  final case class Recorded(
      block: BlockRef,
      record: BlockDiff,
      tombstone: Option[PathStateStorage.TombstoneCause],
      unlisted: Seq[ByteString]
  )

  /** @param written
    *   what the batch leaves at each position it changes (`None`: no row)
    * @param before
    *   what the disk held at each of them
    */
  final case class Built(
      written: Map[PositionKey, Option[ByteString]],
      before: Map[PositionKey, Option[ByteString]],
      records: Vector[Recorded]
  )

  /** A layer that cannot be applied: it is dropped, and its block executed again. Never the fault of a record. */
  final class LayerRefused(val block: BlockRef, val cause: RuntimeException)
      extends RuntimeException(cause.getMessage, cause, false, false)
