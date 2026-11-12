package com.chipprbots.ethereum.db.storage.pathstate

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.crypto.kec256

/** What one executed block left of the state of its parent, kept in memory and written to no row: a '''forward layer'''
  * of the overlay (spec 018 data-model 6.1, 7.2). A block that was executed and not adopted (a sibling of the head, a
  * payload a consensus client has not chosen yet, a block of a branch being weighed) is one of these until it is
  * promoted to the disk or dropped.
  *
  * '''It holds what the block left, and not what the block replaced.''' For each position the block's tries touched:
  * the node it left there, or that it left none; and the hash of the node its first event there named as the old one,
  * which is all that is kept of the parent's state. And the storage tries it replaced whole, as their owners: a
  * position of such a trie that the block did not write again holds nothing in this block's state, whatever the rows
  * under the layer hold.
  *
  * So a storage trie that is destroyed costs a layer nothing, however many rows it had. What the block did to the rows
  * (the priors a changeset record lists, the rows of a replaced trie that are deleted, whether such a trie has too many
  * rows to list) is settled when the layer is promoted, against the rows the disk then holds, which are its parent's:
  * exactly as a commit in place settles a block (forge).
  *
  * It is frozen: a view that holds it reads the same from it whatever happens to the overlay afterwards.
  *
  * @param block
  *   the block, with the state root its header carries
  * @param parent
  *   the block whose state it was executed on
  * @param resets
  *   the owners of the storage tries the block replaced whole, in the order it did
  */
final class ForwardLayer private (
    val block: BlockRef,
    val parent: BlockRef,
    private[pathstate] val left: Map[PositionKey, ForwardLayer.Left],
    val resets: Seq[ByteString]
):

  private val replaced: Set[ByteString] = resets.toSet

  /** What this layer says of `position`: `None` when the block's state holds there what its parent's does, and
    * `Some(None)` when it holds no row: the block left none, or replaced the storage trie and wrote nothing there
    * since.
    */
  def lookup(position: PositionKey): Option[Option[ByteString]] =
    left.get(position) match
      case Some(written)                                    => Some(written.next)
      case None if position.owner.exists(replaced.contains) => ForwardLayer.NoRow
      case None                                             => None

  /** The nodes the block leaves, by hash. */
  private[pathstate] lazy val byHash: Map[ByteString, ByteString] =
    left.valuesIterator.flatMap(_.next).map(node => ByteString(kec256(node.toArray)) -> node).toMap

  /** How many positions it holds. */
  def positions: Int = left.size

  /** What it holds in memory, near enough for a bound: the nodes it left, and for each position its key, the hash of
    * its prior and the maps around it, the one by hash among them.
    */
  val bytes: Long =
    left.iterator.map { (position, written) =>
      position.rowKey.length.toLong + written.next.fold(0)(_.length) + ForwardLayer.EntryOverhead
    }.sum + resets.size * ForwardLayer.EntryOverhead

  /** Why what was staged is not what a block does between the state roots of `parent` and `block`, by the account
    * trie's root: the node the block first found there is the parent's root, and the node it left is the root its
    * header carries. Empty when it is.
    */
  private[pathstate] def rootFaults: Seq[String] =
    def named(hash: Option[ByteString]): ByteString = hash.getOrElse(BlockDiff.EmptyRoot)
    left.get(PositionKey.AccountRoot) match
      case None =>
        Option
          .when(parent.stateRoot != block.stateRoot)("the state root changes and the account trie's root has no entry")
          .toSeq
      case Some(root) =>
        Seq(
          Option.when(named(root.prior) != parent.stateRoot)(
            "the prior of the account trie's root is not the pre-state root"
          ),
          Option.when(named(root.next.map(node => ByteString(kec256(node.toArray)))) != block.stateRoot)(
            "the next of the account trie's root is not the post-state root"
          )
        ).flatten

  /** The block's changes with the priors they name, read as `row` holds them: what [[StagedChanges.settle]] takes.
    * `Left`, with the first position found, where a row is not the node the block named as old there: the layer was not
    * built on the state `row` reads.
    *
    * A position of a replaced storage trie is not held to its row: the settling takes the priors of such a trie from a
    * scan of its rows. And where the block named no prior the row is not looked at: a storage trie replaced with too
    * many rows to list leaves them behind, and a block that first writes where one lies names none there, committed in
    * place or kept as a layer. No node of a state names a child at such a position.
    */
  private[pathstate] def changesOver(
      row: PositionKey => Option[ByteString]
  ): Either[PositionKey, Map[PositionKey, Change]] =
    val changes = Map.newBuilder[PositionKey, Change]
    val wrong = left.iterator.collectFirst(Function.unlift { (position, written) =>
      written.prior match
        case Some(hash) if !position.owner.exists(replaced.contains) =>
          row(position) match
            case Some(node) if ByteString(kec256(node.toArray)) == hash =>
              changes += position -> Change(Some(node), written.next)
              None
            case _ => Some(position)
        case _ =>
          changes += position -> Change(None, written.next)
          None
    })
    wrong.toLeft(changes.result())

object ForwardLayer:

  /** What a block left at one position: the hash of the node its first event there named as the old one (none when it
    * named none), and the node it left (none when it left no row).
    */
  final private[pathstate] case class Left(prior: Option[ByteString], next: Option[ByteString])

  private val NoRow: Option[Option[ByteString]] = Some(None)

  /** What an entry is taken to hold beside its node: its key's objects, the hash of its prior, and its share of the
    * maps (by position and by hash).
    */
  private val EntryOverhead: Long = 440L

  /** The layer of `block`, executed on `parent`, from what its execution staged. */
  private[pathstate] def of(block: BlockRef, parent: BlockRef, staged: StagedChanges.Frozen): ForwardLayer =
    new ForwardLayer(
      block,
      parent,
      staged.changes.view
        .mapValues(change => Left(change.prior.map(node => ByteString(kec256(node.toArray))), change.next))
        .toMap,
      staged.resets
    )
