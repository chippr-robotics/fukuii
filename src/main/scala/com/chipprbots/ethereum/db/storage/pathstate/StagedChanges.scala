package com.chipprbots.ethereum.db.storage.pathstate

import org.apache.pekko.util.ByteString

import scala.collection.mutable

import com.chipprbots.ethereum.crypto.kec256
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

  // The positions of `changes`, by storage trie. A reset and the commit each ask for one owner's positions, and a
  // block can replace many storage tries (some 1,600 SELFDESTRUCTs fit an 8M-gas ETC block): looking through every
  // change for each of them, the second time under the state lock, is a cost a block's sender chooses (forge).
  private val ofOwner = mutable.HashMap.empty[ByteString, mutable.HashSet[PositionKey]]

  private def put(position: PositionKey, change: Change): Unit =
    if !changes.contains(position) then
      position.owner.foreach(owner => ofOwner.getOrElseUpdate(owner, mutable.HashSet.empty) += position)
    changes.update(position, change)

  private def positionsOf(owner: ByteString): Iterable[PositionKey] = ofOwner.getOrElse(owner, Nil)

  /** One change of the trie of `trie` (no owner: the account trie): `olds` are the nodes it names as replaced or
    * absorbed, each at its position, and `created` the stored nodes it now has, as `MptTraversals.collapseTrieAt`
    * returns them.
    */
  def record(
      trie: Option[ByteString],
      olds: Seq[(NibblePath, MptNode)],
      created: Seq[(NibblePath, ByteString, Array[Byte])]
  ): Unit =
    linear(trie, olds, created)
    // Olds first: a position that is both named as old and written anew in one change ends on the new node.
    olds.foreach { (path, node) =>
      val encoded = node.encode
      if StagedChanges.isStored(path, encoded.length) then
        val position = PositionKey.at(trie, path)
        changes.get(position) match
          case None        => put(position, Change(prior = Some(ByteString(encoded)), next = None))
          case Some(known) => put(position, known.copy(next = None))
    }
    created.foreach { (path, _, encoded) =>
      val position = PositionKey.at(trie, path)
      val next = Some(ByteString(encoded))
      changes.get(position) match
        case None        => put(position, Change(prior = None, next = next))
        case Some(known) => put(position, known.copy(next = next))
    }

  /** Holds a change to follow the last one this block made to the same trie (beacon): the root it replaces is the root
    * the block last left there.
    *
    * A block's worlds are one line, each persisted from the one before. Two worlds that branch from one and are both
    * persisted here (a retry on a layer that was not dropped, a builder's world on an importer's layer) would each
    * write its own nodes below a root, and the last root written would decide the state root while the rows under it
    * were a mix of both. The two state roots would still check, the batch would be written, and the first read to meet
    * a row of the other world would call the datadir corrupt. So the second branch is refused here, where it is still a
    * defect of the caller and nothing has been written.
    */
  private def linear(
      trie: Option[ByteString],
      olds: Seq[(NibblePath, MptNode)],
      created: Seq[(NibblePath, ByteString, Array[Byte])]
  ): Unit =
    val root = PositionKey.at(trie, NibblePath.Empty)
    changes.get(root).foreach { known =>
      val replaced = olds.collectFirst { case (path, node) if path.length == 0 => ByteString(node.encode) }
      val writesRoot = created.exists((path, _, _) => path.length == 0)
      // a change that neither names a root nor writes one says nothing of the root, and is not a change of this trie
      if (replaced.isDefined || writesRoot) && replaced != known.next then
        throw new IllegalStateException(
          s"A change to ${root.show} does not follow the last one this block made there: it replaces " +
            s"${StagedChanges.shown(replaced)}, and the block last left ${StagedChanges.shown(known.next)}. " +
            "Two worlds were persisted on one staged layer."
        )
    }

  /** The storage trie of `owner` is replaced whole. Whatever this block had written for it is gone; the priors stand.
    */
  def reset(owner: ByteString): Unit =
    resets += owner
    positionsOf(owner).foreach(position => put(position, changes(position).copy(next = None)))

  /** The owners whose storage trie this block replaced whole, in the order it did. */
  def resetOwners: Seq[ByteString] = resets.toSeq

  /** The block's changes, given the rows the parent's state holds for each owner it reset (`parentRows`, a scan of that
    * owner's rows; rule 6). `None` for an owner whose rows are too many to list: see [[StagedChanges.Final]].
    *
    * For a reset owner the prior of every position is the parent's row there, or none where the scan returned none,
    * whatever the first event said: a trie created again starts empty, so its first write at a position the dead trie
    * occupied names no old node, and a prior of none would have a reverse delete that row where it must put the dead
    * trie's node back.
    *
    * An entry that says nothing is left out (rule 2).
    */
  def finalise(parentRows: ByteString => Option[Iterable[(PositionKey, ByteString)]]): StagedChanges.Final =
    val out = mutable.HashMap.empty[PositionKey, Change]
    out ++= changes
    val unrecorded = Map.newBuilder[PositionKey, ByteString]
    val unlisted = Seq.newBuilder[ByteString]
    resets.foreach { owner =>
      val written = positionsOf(owner).toSet
      parentRows(owner) match
        case Some(scanned) =>
          val rows = scanned.toMap
          // one rule for every position of the owner, written or only scanned: the prior is the parent's row, or none
          (rows.keySet ++ written).foreach { position =>
            out.update(position, Change(prior = rows.get(position), next = out.get(position).flatMap(_.next)))
          }
        case None =>
          // What the parent held for this owner is not known, so nothing can be said of what the block changed there.
          // What the block leaves there is known, and is all a forward write needs.
          unlisted += owner
          written.foreach { position =>
            out.remove(position).flatMap(_.next).foreach(node => unrecorded += position -> node)
          }
    }
    out.filterInPlace((_, change) => change.prior != change.next)
    StagedChanges.Final(out.toMap, unrecorded.result(), unlisted.result())

  /** [[finalise]] where every reset owner's rows are listed. */
  def diff(parentRows: ByteString => Iterator[(PositionKey, ByteString)]): Map[PositionKey, Change] =
    finalise(owner => Some(parentRows(owner).toSeq)).changes

  /** How many positions the block has touched so far: for a metric. */
  def size: Int = changes.size

private[pathstate] object StagedChanges:

  /** What a block did to the state, as it is when the block commits.
    *
    * @param changes
    *   one entry for each position the block changed, of every trie but those of `unlisted`
    * @param unrecorded
    *   the nodes the block leaves in the storage tries of `unlisted`, by position: rows to write, of which no prior is
    *   known
    * @param unlisted
    *   the owners whose storage trie the block replaced and whose rows in the parent's state were too many to list.
    *   Their old rows are not deleted, and a block that has one cannot be recorded (data-model 3.6).
    */
  final case class Final(
      changes: Map[PositionKey, Change],
      unrecorded: Map[PositionKey, ByteString],
      unlisted: Seq[ByteString]
  )

  private def shown(root: Option[ByteString]): String =
    root.fold("no root")(node => s"the root ${org.bouncycastle.util.encoders.Hex.toHexString(kec256(node.toArray))}")

  /** Whether a node of `encodedLength` bytes of RLP at `path` of its trie has a row of its own. */
  def isStored(path: NibblePath, encodedLength: Int): Boolean =
    encodedLength >= BlockDiff.StoredNodeLength || path.length == 0
