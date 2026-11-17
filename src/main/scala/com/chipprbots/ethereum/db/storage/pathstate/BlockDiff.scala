package com.chipprbots.ethereum.db.storage.pathstate

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.crypto.kec256

/** What one block did to one position (spec 018 data-model 3): the stored node the parent's trie held there, and the
  * one this block leaves. Each is the node's RLP; `None` is no stored node.
  */
final case class Change(prior: Option[ByteString], next: Option[ByteString])

/** The unit a block's change to the state is recorded, reversed and replayed in (data-model 3).
  *
  * `changes` has '''one''' entry per position the block changed: its prior as of the block's start and its next as of
  * the block's end, whatever the block did to it in between. Applied forward it turns the parent's rows into this
  * block's, and applied in reverse it turns them back.
  *
  * `incomplete` marks a tombstone: the block's changes could not be recorded (a replaced storage trie or a record above
  * the configured bounds, data-model 3.6), so the record holds none, and no move may cross it.
  *
  * The rules a '''writer''' must keep are in [[validate]]. The record's codec ([[BlockDiffCodec]]) checks structure
  * only, so a diff read back from a record is not thereby known to keep them.
  */
final case class BlockDiff(
    blockHash: ByteString,
    number: BigInt,
    parentHash: ByteString,
    preStateRoot: ByteString,
    postStateRoot: ByteString,
    changes: Map[PositionKey, Change],
    incomplete: Boolean = false
):

  /** The nodes this block leaves, by hash: what `get(hash)` answers from while the block is a layer above the disk. */
  lazy val byHash: Map[ByteString, ByteString] =
    changes.valuesIterator.flatMap(_.next).map(node => ByteString(kec256(node.toArray)) -> node).toMap

  /** What is wrong with this diff as something a writer produced; empty when nothing is.
    *
    *   - a block is named by 32-byte hashes and a number that fits the record's 8 bytes;
    *   - a tombstone has no entries;
    *   - an entry says something: it has a prior or a next, and they differ (rule 2, no no-ops);
    *   - both sides of an entry are '''stored''' nodes (rule 4): at least 32 bytes of RLP, or at a trie's root. A node
    *     that shrinks below 32 bytes moves into its parent, and its position has `next = None`.
    *
    *   - the entry at the root of the account trie is the two state roots (forge, C3a): its prior hashes to the
    *     pre-state root and its next to the post-state root, an empty trie having no row. It is there exactly when the
    *     two roots differ. A diff built from the wrong world of a block (one of the persists inside it, or a world its
    *     builder threw away) fails here.
    *
    * Rule 5 (a position that held a stored node and holds none is listed) is a statement about the parent's state and
    * cannot be read off the diff: the writer's tests hold it against a walk of both states.
    */
  def validate: Seq[String] =
    val header = Seq(
      Option.when(Seq(blockHash, parentHash, preStateRoot, postStateRoot).exists(_.length != BlockDiff.HashLength))(
        "a block hash or state root is not 32 bytes"
      ),
      Option.when(number < 0 || number > BlockDiff.MaxNumber)(s"block number $number does not fit 8 bytes"),
      Option.when(incomplete && changes.nonEmpty)("a tombstone has entries")
    ).flatten
    val entries = changes.iterator.flatMap { case (position, change) =>
      val atRoot = position.hp == PositionKey.AccountRoot.hp
      def embedded(side: Option[ByteString]): Boolean = side.exists(_.length < BlockDiff.StoredNodeLength) && !atRoot
      Seq(
        Option.when(change.prior.isEmpty && change.next.isEmpty)(s"${position.show}: neither a prior nor a next"),
        Option.when(change.prior.isDefined && change.prior == change.next)(s"${position.show}: prior equals next"),
        Option.when(embedded(change.prior))(s"${position.show}: the prior is not a stored node"),
        Option.when(embedded(change.next))(s"${position.show}: the next is not a stored node")
      ).flatten
    }
    header ++ entries ++ (if incomplete then Nil else rootEntry)

  /** The account trie's root row, held to the two state roots. */
  private def rootEntry: Seq[String] =
    def rootOf(node: Option[ByteString]): ByteString =
      node.fold(BlockDiff.EmptyRoot)(n => ByteString(kec256(n.toArray)))
    changes.get(PositionKey.AccountRoot) match
      case None =>
        Option
          .when(preStateRoot != postStateRoot)("the state root changes and the account trie's root has no entry")
          .toSeq
      case Some(change) =>
        Seq(
          Option.when(preStateRoot == postStateRoot)(
            "the state root does not change and the account trie's root has an entry"
          ),
          Option.when(rootOf(change.prior) != preStateRoot)(
            "the prior of the account trie's root is not the pre-state root"
          ),
          Option.when(rootOf(change.next) != postStateRoot)(
            "the next of the account trie's root is not the post-state root"
          )
        ).flatten

object BlockDiff:
  val HashLength: Int = 32

  /** A node has a row of its own from this many bytes of RLP; a smaller one is embedded in its parent. */
  val StoredNodeLength: Int = 32

  /** The root of a trie that holds nothing, and so has no row. */
  val EmptyRoot: ByteString = ByteString(com.chipprbots.ethereum.mpt.MerklePatriciaTrie.EmptyRootHash)

  /** The largest block number the record's 8 bytes hold. */
  val MaxNumber: BigInt = (BigInt(1) << 64) - 1
