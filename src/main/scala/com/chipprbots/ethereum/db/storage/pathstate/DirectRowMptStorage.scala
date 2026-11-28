package com.chipprbots.ethereum.db.storage.pathstate

import org.apache.pekko.util.ByteString

import scala.util.control.NonFatal

import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.db.storage.RawNodeRead
import com.chipprbots.ethereum.db.storage.StateStorage
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.CorruptNodeException
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingNodeException
import com.chipprbots.ethereum.mpt.MptNode
import com.chipprbots.ethereum.mpt.NibblePath

/** The anchor's tries read by position, one row at a time, straight from CF `t` and `u` (spec 018 C5b, T219): what the
  * recovery scans walk on the unified layout. No view, no layer, no cache: a scan reads every node once, so a cache
  * would only be filled and evicted, and what a scan looks for is a row that is not there, which only a direct read can
  * tell from a node that is held in memory.
  *
  * A read is judged against the hash its parent names, as a view judges it:
  *   - no row: [[MissingNodeException]] with the path, a hole;
  *   - a row that hashes to the hash and decodes: the node;
  *   - a row that is another node, or does not decode: [[CorruptNodeException]], an end of the walk and no hole.
  *
  * Which of the two a mismatch or an absent row is, is decided '''after''' the read, from the anchor: the rows are the
  * anchor's, and a read is not made under the state lock, so a move that commits in between would have its new rows
  * called absent or corrupt. When the anchor's state root is no longer `root`, the read throws [[StateUnavailable]],
  * which ends the scan as one that could not run, and settles nothing.
  *
  * Read-only: it refuses every update. Where [[PathMptStorage]] reads through a view, this reads rows and nothing else,
  * so it needs [[PositionRepairs]] and no more.
  *
  * @param root
  *   the state root of the anchor the scan was started on
  */
final class DirectRowMptStorage private (
    positions: PositionRepairs,
    root: ByteString,
    scope: Option[Option[ByteString]]
) extends MptStorage:

  override def forOwner(owner: Option[ByteString]): MptStorage = new DirectRowMptStorage(positions, root, Some(owner))

  /** Each account has a storage trie of its own here: one found whole says nothing of another with the same root. */
  override def nodesSharedAcrossTries: Boolean = false

  private def trie(what: String): Option[ByteString] =
    scope.getOrElse(
      throw new IllegalStateException(s"$what on the storage of the whole state: the trie did not say which trie it is")
    )

  /** Whether the anchor is still the state this scan began on. Asked after a read, never before. */
  private def anchorMoved: Boolean = !positions.anchor.exists(_.stateRoot == root)

  private def moved(at: PositionKey): StateUnavailable =
    new StateUnavailable(
      s"the anchor moved off the state root the scan began on (${root.take(4).map("%02x".format(_)).mkString}...) while it read ${at.show}"
    )

  override def getAt(path: NibblePath, hash: Array[Byte]): MptNode =
    val position = PositionKey.at(trie(s"A read at [${path.show}]"), path)
    val wanted = ByteString.fromArrayUnsafe(hash)
    positions.rawRowsAt(Seq(position -> wanted)).head match
      case RawNodeRead.Absent =>
        if anchorMoved then throw moved(position)
        else throw new MissingNodeException(wanted).withLocation(position.hp)
      case RawNodeRead.Bytes(encoded) if StateStorage.hashesTo(wanted, encoded) =>
        try MptStorage.decodeNode(encoded, hash)
        catch
          case NonFatal(e) =>
            throw new CorruptNodeException(
              wanted,
              s"the row of ${position.show} does not decode: ${e.getClass.getSimpleName}: ${e.getMessage}",
              location = Some(position.hp),
              cause = Some(e)
            )
      case _ =>
        if anchorMoved then throw moved(position)
        else
          throw new CorruptNodeException(
            wanted,
            s"the row of ${position.show} is another node",
            location = Some(position.hp)
          )

  /** Only the root of the account trie is found by its hash alone. */
  override def get(nodeId: Array[Byte]): MptNode =
    if java.util.Arrays.equals(nodeId, root.toArray) then forOwner(None).getAt(NibblePath.Empty, nodeId)
    else throw DirectRowMptStorage.byHashAlone

  /** The rows at the positions asked for, as they are: for a caller that judges each entry itself. */
  override def rawNodesAt(requests: Seq[(NibblePath, ByteString)]): Seq[RawNodeRead] =
    val owner = trie("A raw read")
    val asked = requests.map((path, hash) => PositionKey.at(owner, path) -> hash)
    val answered = positions.rawRowsAt(asked)
    // an answer that says "no such node" is believed only if the anchor did not move while it was read
    val doubtful = asked.zip(answered).collectFirst {
      case ((at, _), RawNodeRead.Absent)                                                     => at
      case ((at, hash), RawNodeRead.Bytes(encoded)) if !StateStorage.hashesTo(hash, encoded) => at
      case ((at, _), RawNodeRead.Undecodable)                                                => at
    }
    doubtful.foreach(at => if anchorMoved then throw moved(at))
    answered

  override def rawNodes(hashes: Seq[ByteString]): Seq[RawNodeRead] = throw DirectRowMptStorage.byHashAlone
  override def multiGetNodes(hashes: Seq[Array[Byte]]): Seq[Option[MptNode]] = throw DirectRowMptStorage.byHashAlone

  override def updateNodesInStorage(newRoot: Option[MptNode], toRemove: Seq[MptNode]): Option[MptNode] =
    throw DirectRowMptStorage.readOnly
  override def updateNodesAt(
      newRoot: Option[MptNode],
      replaced: Seq[(NibblePath, MptNode)],
      absorbed: Seq[(NibblePath, MptNode)]
  ): Option[MptNode] = throw DirectRowMptStorage.readOnly
  override def storeRawNodes(nodes: Seq[(ByteString, Array[Byte])]): Unit = throw DirectRowMptStorage.readOnly
  override def persist(): Unit = ()

object DirectRowMptStorage:

  /** The anchor's rows, for the scan of the state whose root is `root`. */
  def apply(positions: PositionRepairs, root: ByteString): MptStorage = new DirectRowMptStorage(positions, root, None)

  private def byHashAlone: UnsupportedOperationException =
    new UnsupportedOperationException("A position store cannot find nodes by hash alone: read them by position")

  private def readOnly: UnsupportedOperationException =
    new UnsupportedOperationException("The scan's view of the rows is read-only")
