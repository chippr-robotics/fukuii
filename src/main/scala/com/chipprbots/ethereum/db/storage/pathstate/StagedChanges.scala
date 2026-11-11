package com.chipprbots.ethereum.db.storage.pathstate

import org.apache.pekko.util.ByteString

import scala.collection.mutable

import com.chipprbots.ethereum.mpt.MptNode
import com.chipprbots.ethereum.mpt.NibblePath

/** What one block has done to the positions of the state so far: one [[Change]] for each position its tries have
  * touched, and the storage tries it has replaced whole (spec 018 data-model 3, rules 1 to 7).
  *
  * Execution persists its tries after every transaction, and a trie tells its storage of each change as it makes it:
  * the nodes that were on the path of the change (`replaced`), the nodes a removal merged into their parent
  * (`absorbed`), and the nodes it now has there. A hot node is rewritten many times a block. What a block does to a
  * position is two things only: what was there in the parent's state (the '''prior'''), and what is there at the end
  * (the '''next''').
  *
  *   - '''The prior is fixed by the first event at a position''' (rule 1). If that event names an old stored node
  *     there, the prior is that node: the trie came to the position through nodes the block had not changed, so it is
  *     the parent's. If the first event is a node written where nothing was named as old, the prior is none, and every
  *     old node named there later is one this block wrote, an intermediate.
  *   - '''The next is the last word''': the last node written at the position, or none when the last event there named
  *     an old node and wrote no new one (rules 5 and 7: a position that held a stored node and holds none is listed).
  *   - '''Only stored nodes count''' (rule 4): a node has a row of its own from 32 bytes of RLP, or at its trie's root.
  *     A smaller one lives inside its parent.
  *
  * A storage trie replaced whole ([[reset]]) loses, at once, everything the block had written for it: the next of each
  * of those positions is none. What a trie created again under the same account writes afterwards are ordinary events.
  * The rows the parent's state holds for that account are found by a scan when the block commits ([[diff]], rule 6).
  *
  * Not thread-safe: a block is executed by one thread, and this is that block's.
  */
final private[pathstate] class StagedChanges:

  private val changes = mutable.HashMap.empty[PositionKey, Change]
  private val resets = mutable.LinkedHashSet.empty[ByteString]

  /** One change of the trie of `trie` (no owner: the account trie): `olds` are the nodes it names as replaced or
    * absorbed, each at its position, and `created` the stored nodes it now has, as `MptTraversals.collapseTrieAt`
    * returns them.
    */
  def record(
      trie: Option[ByteString],
      olds: Seq[(NibblePath, MptNode)],
      created: Seq[(NibblePath, ByteString, Array[Byte])]
  ): Unit =
    // Olds first: a position that is both named as old and written anew in one change ends on the new node.
    olds.foreach { (path, node) =>
      val encoded = node.encode
      if StagedChanges.isStored(path, encoded.length) then
        val position = PositionKey.at(trie, path)
        changes.get(position) match
          case None        => changes.update(position, Change(prior = Some(ByteString(encoded)), next = None))
          case Some(known) => changes.update(position, known.copy(next = None))
    }
    created.foreach { (path, _, encoded) =>
      val position = PositionKey.at(trie, path)
      val next = Some(ByteString(encoded))
      changes.get(position) match
        case None        => changes.update(position, Change(prior = None, next = next))
        case Some(known) => changes.update(position, known.copy(next = next))
    }

  /** The storage trie of `owner` is replaced whole. Whatever this block had written for it is gone; the priors stand.
    */
  def reset(owner: ByteString): Unit =
    resets += owner
    val ofOwner = Some(owner)
    changes.keysIterator.filter(_.owner == ofOwner).toList.foreach { position =>
      changes.update(position, changes(position).copy(next = None))
    }

  /** The owners whose storage trie this block replaced whole, in the order it did. */
  def resetOwners: Seq[ByteString] = resets.toSeq

  /** The block's changes, given the rows the parent's state holds for each owner it reset (`parentRows`, a scan of that
    * owner's rows; rule 6).
    *
    * For a reset owner the prior of every position is the parent's row there, or none where the scan returned none,
    * whatever the first event said: a trie created again starts empty, so its first write at a position the dead trie
    * occupied names no old node, and a prior of none would have a reverse delete that row where it must put the dead
    * trie's node back.
    *
    * An entry that says nothing is left out (rule 2).
    */
  def diff(parentRows: ByteString => Iterator[(PositionKey, ByteString)]): Map[PositionKey, Change] =
    val out = mutable.HashMap.empty[PositionKey, Change]
    out ++= changes
    resets.foreach { owner =>
      val rows = parentRows(owner).toMap
      val ofOwner = Some(owner)
      val written = out.keysIterator.filter(_.owner == ofOwner).toSet
      // one rule for every position of the owner, written or only scanned: the prior is the parent's row, or none
      (rows.keySet ++ written).foreach { position =>
        out.update(position, Change(prior = rows.get(position), next = out.get(position).flatMap(_.next)))
      }
    }
    out.filterInPlace((_, change) => change.prior != change.next)
    out.toMap

  /** How many positions the block has touched so far: for a metric. */
  def size: Int = changes.size

private[pathstate] object StagedChanges:

  /** Whether a node of `encodedLength` bytes of RLP at `path` of its trie has a row of its own. */
  def isStored(path: NibblePath, encodedLength: Int): Boolean =
    encodedLength >= BlockDiff.StoredNodeLength || path.length == 0
