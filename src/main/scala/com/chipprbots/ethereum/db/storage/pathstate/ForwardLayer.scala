package com.chipprbots.ethereum.db.storage.pathstate

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.crypto.kec256

/** What one executed block did to the state of its parent, kept in memory and written to no row: a '''forward layer'''
  * of the overlay (spec 018 data-model 6.1, 7.2). A block that was executed and not adopted (a sibling of the head, a
  * payload a consensus client has not chosen yet, a block of a branch being weighed) is one of these until it is
  * promoted to the disk or dropped.
  *
  * It is frozen: a view that holds it reads the same from it whatever happens to the overlay afterwards.
  *
  * @param block
  *   the block, with the state root its header carries
  * @param parent
  *   the block whose state it was executed on
  */
final class ForwardLayer private[pathstate] (
    val block: BlockRef,
    val parent: BlockRef,
    private[pathstate] val finalised: StagedChanges.Final
):

  /** What the block leaves at each position it wrote: a node, or no row. It is every entry of the block's changes
    * '''and''' the rows it wrote in a storage trie whose old rows were too many to list
    * ([[StagedChanges.Final.unrecorded]]): those are in no entry, and a descendant reads them all the same (beacon,
    * forge).
    */
  private[pathstate] val written: Map[PositionKey, Option[ByteString]] =
    finalised.changes.view.mapValues(_.next).toMap ++ finalised.unrecorded.view.mapValues(Some(_))

  /** What this layer says of `position`: `None` when the block did not write it, `Some(None)` when it left no row. */
  def lookup(position: PositionKey): Option[Option[ByteString]] = written.get(position)

  /** The nodes the block leaves, by hash. */
  private[pathstate] lazy val byHash: Map[ByteString, ByteString] =
    written.valuesIterator.flatten.map(node => ByteString(kec256(node.toArray)) -> node).toMap

  /** What it wrote in each storage trie, by owner: what a scan of one owner's rows is overlaid with. */
  private lazy val byOwner: Map[ByteString, Map[PositionKey, Option[ByteString]]] =
    written.groupBy(_._1.owner).collect { case (Some(owner), positions) => owner -> positions }

  private[pathstate] def ofOwner(owner: ByteString): Map[PositionKey, Option[ByteString]] =
    byOwner.getOrElse(owner, Map.empty)

  /** How many positions it holds: for a bound, and a metric. */
  def positions: Int = written.size
