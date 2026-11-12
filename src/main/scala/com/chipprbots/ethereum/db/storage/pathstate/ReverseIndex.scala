package com.chipprbots.ethereum.db.storage.pathstate

import org.apache.pekko.util.ByteString

import scala.collection.mutable

import com.chipprbots.ethereum.utils.LazyLogger

/** What the state of a block behind the anchor held at every position the blocks after it changed: the '''reverse
  * index''' between the two (spec 018 data-model 6.1). It is built from the changeset records of the anchor's own
  * chain, and it is the state of `to` laid over the state of `from`: where it has a position, that is what `to`'s state
  * held there (a node, or no row); where it has none, the two states hold the same.
  *
  * It is a fact about two states, and does not change: a record that is pruned afterwards takes nothing from it.
  *
  * '''It is what a head move back would leave on disk.''' Reversing the blocks after `to` writes each record's priors,
  * the earliest block's last, with "no prior" as a delete; this index is that, kept in memory. The two agree at every
  * position, the rows a replaced storage trie left behind among them: where a block first wrote over such a row its
  * prior is "none", and a reverse deletes the row as this index says it was not there. No node of a state names a child
  * at such a position, so no read of a state comes to one (beacon).
  *
  * @param from
  *   the anchor it was built from
  * @param to
  *   the ancestor of `from` whose state it gives
  */
final private[pathstate] class ReverseIndex private (
    val from: BlockRef,
    val to: BlockRef,
    byTrie: Map[Option[ByteString], Map[ByteString, Option[ByteString]]],
    val positions: Int,
    val bytes: Long
):

  /** What `to`'s state held at `position`: `None` when no block after it changed that position. */
  def lookup(position: PositionKey): Option[Option[ByteString]] =
    byTrie.get(position.owner).flatMap(_.get(position.hp))

private[pathstate] object ReverseIndex extends LazyLogger:

  /** What an entry of the index is taken to hold beside its node: the position's key and the map around it. */
  private val EntryOverhead: Long = 288L

  /** The index between `anchor` and its ancestor `fork`, from the records of the blocks after `fork` up to `anchor`:
    * the anchor's record first, then its parent's, by the parent each record names. The canonical index of blocks by
    * number is never asked (data-model 7.0). `fork`'s own record is not read: its state needs only what came after it.
    *
    * Each record is held to the block it is asked for: its number, and the state root it leaves. The walk ends on the
    * record whose parent is exactly `fork`: its hash, its number and its state root.
    *
    * @throws StateUnavailable
    *   when `fork` is not an ancestor the records lead to within `maxHops`; when a record on the way is not there (it
    *   was pruned, or never written: the genesis, a state a sync left), is a tombstone, or cannot be read; when the
    *   index would hold more than `maxBytes`. Never an index with a block missing from it
    */
  def between(
      records: ChangesetStorage,
      anchor: BlockRef,
      fork: BlockRef,
      maxHops: Int,
      maxBytes: Long
  ): ReverseIndex =
    val merged = mutable.HashMap.empty[PositionKey, Option[ByteString]]
    var held = 0L
    var at = anchor
    var hops = 0
    def unavailable(why: String): Nothing =
      throw new StateUnavailable(
        s"the state of block ${fork.show} cannot be made from the state of ${anchor.show}: $why"
      )
    while at != fork do
      if at.number <= fork.number then unavailable("it is not a block the anchor's records lead back to")
      if hops >= maxHops then unavailable(s"it is more than $maxHops blocks back")
      // a record that is by itself more than an index may hold is not decoded to find that out
      records.getForWalk(at.hash, maxBytes) match
        case ChangesetStorage.Walked.Absent => unavailable(s"the changeset record of block ${at.show} is not there")
        case ChangesetStorage.Walked.TooLarge(encoded) =>
          unavailable(
            s"the changeset record of block ${at.show} is $encoded bytes, and what the blocks between them changed " +
              s"would be more than $maxBytes bytes"
          )
        case ChangesetStorage.Walked.Unreadable(rejected) =>
          log.error(
            s"The changeset record of block ${at.show} cannot be read (${rejected.reason}: ${rejected.detail}): " +
              "this data directory's changeset records are corrupt"
          )
          unavailable(s"the changeset record of block ${at.show} cannot be read")
        case ChangesetStorage.Walked.Read(record) =>
          if record.number != at.number || record.postStateRoot != at.stateRoot then
            log.error(
              s"The changeset record of block ${at.show} is of block ${record.number} and leaves the state root " +
                s"${org.bouncycastle.util.encoders.Hex.toHexString(record.postStateRoot.toArray)}: this data " +
                "directory's changeset records are corrupt"
            )
            unavailable(s"the changeset record of block ${at.show} is not that block's")
          if record.incomplete then
            unavailable(s"block ${at.show} changed more than a record holds, and its record lists nothing")
          record.changes.foreach { (position, change) =>
            // an earlier block is walked later, and its prior stands: it is what the state before it held
            val before = merged.put(position, change.prior)
            held += change.prior.fold(0L)(_.length.toLong) - before.flatten.fold(0L)(_.length.toLong)
            if before.isEmpty then held += EntryOverhead + position.rowKey.length
          }
          if held > maxBytes then unavailable(s"what the blocks between them changed is more than $maxBytes bytes")
          at = BlockRef(record.parentHash, record.number - 1, record.preStateRoot)
          hops += 1
    val byTrie = merged.groupBy(_._1.owner).view.mapValues(_.map((position, held) => position.hp -> held).toMap).toMap
    new ReverseIndex(anchor, fork, byTrie, merged.size, held)

/** The blocks behind the anchor that its changeset records lead to, newest first: the anchor's parent, that block's
  * parent, and so on, for as far as a view could be made (spec 018 data-model 6.1, 7.0). It is what says, without
  * reading a record, whether a block is on the anchor's own chain: which forward layers still stand on it, and which
  * are of a block that is now canonical.
  *
  * It is derived and never stored: built once from the records' headers, then moved on by each commit. It is tagged
  * with its anchor, and a reader that holds another anchor does not use it. An error in it costs a view that could have
  * been made, or keeps a layer no view can be made of: the view itself is made from the records.
  */
final private[pathstate] case class AncestorWindow(anchor: BlockRef, ancestors: Vector[BlockRef]):
  private lazy val known: Set[BlockRef] = ancestors.toSet

  def contains(block: BlockRef): Boolean = known.contains(block)

  /** The window after `block`, whose parent is this window's anchor, was committed with a record: its parent is now the
    * nearest ancestor. A tombstone leads nowhere: no view can be made across it. Ancestors below `floor` are those
    * whose records are gone, and beyond `maxHops` back no view is made: the window holds neither.
    */
  def advancedTo(block: BlockRef, tombstone: Boolean, floor: BigInt, maxHops: Int): AncestorWindow =
    if tombstone then AncestorWindow(block, Vector.empty)
    else AncestorWindow(block, (anchor +: ancestors).iterator.takeWhile(_.number >= floor).take(maxHops).toVector)

private[pathstate] object AncestorWindow:

  /** The window of `anchor`, by a walk of the headers of its records: until a record is not there, is a tombstone or
    * cannot be read, or `maxHops` are made.
    */
  def of(records: ChangesetStorage, anchor: BlockRef, maxHops: Int): AncestorWindow =
    val ancestors = Vector.newBuilder[BlockRef]
    var at = anchor
    var hops = 0
    var more = true
    while more && hops < maxHops do
      records.header(at.hash) match
        case Some(Right(header))
            if !header.incomplete && header.number == at.number && header.postStateRoot == at.stateRoot &&
              header.number > 0 =>
          at = BlockRef(header.parentHash, header.number - 1, header.preStateRoot)
          ancestors += at
          hops += 1
        case _ => more = false
    AncestorWindow(anchor, ancestors.result())
