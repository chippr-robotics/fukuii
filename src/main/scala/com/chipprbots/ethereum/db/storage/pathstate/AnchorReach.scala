package com.chipprbots.ethereum.db.storage.pathstate

import org.apache.pekko.util.ByteString

import scala.annotation.tailrec
import scala.util.Success
import scala.util.control.NonFatal

import com.chipprbots.ethereum.db.storage.StateStorage
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.mpt.BranchNode
import com.chipprbots.ethereum.mpt.ExtensionNode
import com.chipprbots.ethereum.mpt.HashNode
import com.chipprbots.ethereum.mpt.HexPrefix
import com.chipprbots.ethereum.mpt.LeafNode
import com.chipprbots.ethereum.mpt.MptNode
import com.chipprbots.ethereum.mpt.MptTraversals

/** What the tries of one root name at a position (spec 018 data-model 7.5): the `stillReached` of a repair or a delete
  * by position.
  *
  * It walks the rows from the root of the account trie down, '''verifying each row against the hash its parent
  * holds''': a row that is absent, does not hash to what its parent names, or does not decode is an ancestor the walk
  * cannot believe, and says [[Reach.Unreadable]], never an answer about what lies below it. For a position in a storage
  * trie it first finds the owner's account leaf in the account trie and takes the storage root from it, so an owner
  * whose account moved (another storage root, or gone) is answered by the account trie as it stands.
  *
  * It reads rows through `read` and holds nothing. It is called under the state lock, which is what makes its answer
  * the anchor's: the rows do not move while it walks.
  */
object AnchorReach:

  enum Reach:

    /** The tries of the root name `hash` at the position. */
    case Names(hash: ByteString)

    /** No stored node sits there: a null child, one embedded in its parent, a path that leaves the trie, an owner that
      * is not in the account trie or whose storage is empty.
      */
    case Nowhere

    /** A row on the way down cannot be believed: absent, another node than its parent names, or undecodable. The root
      * reaches nothing that can be told at the position.
      */
    case Unreadable(at: PositionKey)

  /** What `root` names at `position`. */
  def reach(root: ByteString, position: PositionKey, read: PositionKey => Option[Array[Byte]]): Reach =
    val target = HexPrefix.decode(position.hp.toArray)._1
    if root == BlockDiff.EmptyRoot then Reach.Nowhere // the empty trie has no row, not even at its root
    else
      position.owner match
        case None => walk(None, root, target, read)
        case Some(owner) =>
          val ownerNibbles = HexPrefix.bytesToNibbles(owner.toArray)
          accountLeaf(root, ownerNibbles, read) match
            case Left(unanswered) => unanswered
            case Right(leaf) =>
              Account(leaf.value) match
                case Success(account) if account.storageRoot != Account.EmptyStorageRootHash =>
                  walk(Some(owner), account.storageRoot.value, target, read)
                case _ => Reach.Nowhere

  /** The leaf of the account at `nibbles` (the owner's 64 nibbles), `Left` when the walk cannot say. */
  private def accountLeaf(
      root: ByteString,
      nibbles: Array[Byte],
      read: PositionKey => Option[Array[Byte]]
  ): Either[Reach, LeafNode] =
    @tailrec def down(hash: ByteString, consumed: Int): Either[Reach, LeafNode] =
      nodeAt(None, hash, nibbles.take(consumed), read) match
        case Left(unreadable) => Left(unreadable)
        case Right(node) =>
          val remaining = nibbles.drop(consumed)
          node match
            case leaf: LeafNode =>
              if java.util.Arrays.equals(leaf.key.toArray, remaining) then Right(leaf) else Left(Reach.Nowhere)
            case branch: BranchNode if remaining.nonEmpty =>
              branch.children(remaining.head & 0xf) match
                case HashNode(next) => down(ByteString(next), consumed + 1)
                case _              => Left(Reach.Nowhere)
            case extension: ExtensionNode if remaining.startsWith(extension.sharedKey.toArray) =>
              extension.next match
                case HashNode(next) => down(ByteString(next), consumed + extension.sharedKey.length)
                case _              => Left(Reach.Nowhere)
            case _ => Left(Reach.Nowhere)
    down(root, 0)

  /** From the root of one trie (`rootHash`, at the empty path) down to `target`. */
  private def walk(
      owner: Option[ByteString],
      rootHash: ByteString,
      target: Array[Byte],
      read: PositionKey => Option[Array[Byte]]
  ): Reach =
    @tailrec def down(hash: ByteString, consumed: Int): Reach =
      if consumed == target.length then Reach.Names(hash)
      else
        nodeAt(owner, hash, target.take(consumed), read) match
          case Left(unreadable) => unreadable
          case Right(node) =>
            val remaining = target.drop(consumed)
            node match
              case branch: BranchNode =>
                branch.children(remaining.head & 0xf) match
                  case HashNode(next) => down(ByteString(next), consumed + 1)
                  case _              => Reach.Nowhere
              case extension: ExtensionNode if remaining.startsWith(extension.sharedKey.toArray) =>
                extension.next match
                  case HashNode(next) => down(ByteString(next), consumed + extension.sharedKey.length)
                  case _              => Reach.Nowhere
              case _ => Reach.Nowhere
    down(rootHash, 0)

  /** The node at `path` of a trie, believed only if the row hashes to `hash`: `Left(Unreadable)` otherwise. */
  private def nodeAt(
      owner: Option[ByteString],
      hash: ByteString,
      path: Array[Byte],
      read: PositionKey => Option[Array[Byte]]
  ): Either[Reach, MptNode] =
    val at = PositionKey(owner, ByteString(HexPrefix.encode(path, isLeaf = false)))
    read(at) match
      case Some(encoded) if StateStorage.hashesTo(hash, encoded) =>
        try Right(MptTraversals.decodeNode(encoded))
        catch case NonFatal(_) => Left(Reach.Unreadable(at))
      case _ => Left(Reach.Unreadable(at))
