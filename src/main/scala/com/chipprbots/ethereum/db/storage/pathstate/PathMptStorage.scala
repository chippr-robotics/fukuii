package com.chipprbots.ethereum.db.storage.pathstate

import java.util.Arrays
import java.util.concurrent.ConcurrentHashMap

import org.apache.pekko.util.ByteString

import scala.util.control.NonFatal

import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.db.storage.RawNodeRead
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.CorruptNodeException
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingNodeException
import com.chipprbots.ethereum.mpt.MptNode
import com.chipprbots.ethereum.mpt.MptTraversals
import com.chipprbots.ethereum.mpt.NibblePath

/** The trie-node storage of one [[StateView]] of the position-keyed store: a node is found at the position its trie
  * looks for it, and verified against the hash its parent holds (spec 018 data-model 6.2, 6.3, 9).
  *
  * A trie reaches it through [[forOwner]], which says which trie it is. A read or an update on the storage of the whole
  * state names no trie and is refused: a path starts at its own trie's root.
  *
  * '''Reads.''' `getAt(path, hash)` asks the layer first, by hash, and then the view, by position:
  *   - no row there: `MissingNodeException`, with the path as its location;
  *   - a row that hashes to `hash`: the node. Bytes that pass the hash and do not decode are a `CorruptNodeException`;
  *   - a row that is another node: `CorruptNodeException` when this is the view of what is on disk and nothing has
  *     moved since it was made, and [[StaleStateViewException]] otherwise. Which of the two is decided '''after''' the
  *     read: decided before, a move that commits in between would have its new bytes called corrupt.
  *
  * `get(hash)` finds a node by hash alone only where a position store can: the layer, and the root of the account trie.
  *
  * '''Writes.''' There are two kinds of this storage, and neither writes a row.
  *   - [[PathMptStorage.readOnly]] refuses every update.
  *   - [[PathMptStorage.throwaway]] keeps what the tries write in a layer of its own, by hash, every version: an
  *     execution that commits nothing (`eth_call`, a simulation, a trace) still persists its worlds and reads them
  *     back, and an Amsterdam block's access list is built by reading the world before a transaction after the world
  *     following it was persisted (data-model 6.2). The layer dies with the storage.
  *
  * A block's own commit (its changes by position, its replaced storage tries, one batch) is the staged layer of C3b,
  * which adds to the layer here.
  */
final class PathMptStorage private (
    view: StateView,
    layer: Option[PathMptStorage.Layer],
    scope: Option[Option[ByteString]]
) extends MptStorage:

  override def forOwner(owner: Option[ByteString]): MptStorage = new PathMptStorage(view, layer, Some(owner))

  /** Each account has a storage trie of its own here: one found whole says nothing of another with the same root. */
  override def nodesSharedAcrossTries: Boolean = false

  private def owner(what: String): Option[ByteString] =
    scope.getOrElse(
      throw new IllegalStateException(s"$what on the storage of the whole state: the trie did not say which trie it is")
    )

  private def written(hash: Array[Byte]): Option[MptNode] =
    layer.flatMap(l => Option(l.byHash.get(ByteString.fromArrayUnsafe(hash)))).map(MptStorage.decodeNode(_, hash))

  override def getAt(path: NibblePath, hash: Array[Byte]): MptNode =
    val trie = owner(s"A read at [${path.show}]")
    written(hash).getOrElse(stored(PositionKey.at(trie, path), hash))

  override def get(nodeId: Array[Byte]): MptNode =
    written(nodeId)
      // a node one of the view's forward layers leaves: its bytes were hashed when the layer was made
      .orElse(view.layerNode(ByteString.fromArrayUnsafe(nodeId)).map(MptStorage.decodeNode(_, nodeId)))
      .getOrElse {
        view.read(PositionKey.AccountRoot) match
          case Some(encoded) if Arrays.equals(kec256(encoded), nodeId) =>
            decoded(PositionKey.AccountRoot, encoded, nodeId)
          case _ => throw new MissingNodeException(ByteString(nodeId))
      }

  /** The node at `position` in the view, which must be the one `hash` names. */
  private def stored(position: PositionKey, hash: Array[Byte]): MptNode =
    view.readFrom(position) match
      // What a layer or the reverse index says does not change, and the hash was read through this same view: asked
      // again, it is the same. It is never a missing node: that is fetched and written to the row, and the row is
      // another state's (beacon).
      case (None, source) if source != StateView.Source.Disk =>
        throw new LayerInconsistent(
          view.block,
          s"${PathMptStorage.named(source)} leaves no node at ${position.show}, where one is named"
        )
      case (None, _) =>
        // No row where neither a layer nor the records say anything: this state holds there what the base holds, and
        // the base's disk has a hole. It is a missing node. It is said with its position only on a view of the rows
        // alone: what is fetched for a position is written to that row, and the row is the anchor's (beacon).
        val missing = new MissingNodeException(ByteString(hash))
        throw (if view.layered then missing else missing.withLocation(position.hp))
      case (Some(encoded), source) =>
        if Arrays.equals(kec256(encoded), hash) then decoded(position, encoded, hash)
        else if source != StateView.Source.Disk then
          throw new LayerInconsistent(
            view.block,
            s"${PathMptStorage.named(source)} leaves another node at ${position.show} than is named"
          )
        else if view.isAnchorViewNow then
          throw new CorruptNodeException(
            ByteString(hash),
            s"the row of ${position.show} is another node",
            location = Some(position.hp)
          )
        else if view.layered && view.diskHalfAtRestNow then
          // The disk is at rest on this view's base, so the row is the base's: the layers were not built on it. Never
          // a corrupt row: what that is answered with deletes it, and it may be another state's good node.
          throw new LayerInconsistent(
            view.block,
            s"the row of ${position.show} in the state of block ${view.base.show} is another node than is named"
          )
        else
          throw new StaleStateViewException(
            view.block,
            view.createdAt,
            view.epochNow,
            s"the row of ${position.show} is no longer the node this view expects there"
          )

  private def decoded(position: PositionKey, encoded: Array[Byte], hash: Array[Byte]): MptNode =
    try MptStorage.decodeNode(encoded, hash)
    catch
      case NonFatal(e) =>
        throw new CorruptNodeException(
          ByteString(hash),
          s"the row of ${position.show} does not decode: ${e.getClass.getSimpleName}: ${e.getMessage}",
          location = Some(position.hp),
          cause = Some(e)
        )

  override def updateNodesAt(
      newRoot: Option[MptNode],
      replaced: Seq[(NibblePath, MptNode)],
      absorbed: Seq[(NibblePath, MptNode)]
  ): Option[MptNode] =
    val trie = owner("An update")
    val into = layer.getOrElse(throw PathMptStorage.readOnlyView)
    val collapsed = newRoot.map(root => MptTraversals.collapseTrieAt(root, NibblePath.Empty))
    val created = collapsed.fold(Seq.empty[(NibblePath, ByteString, Array[Byte])])(_._2)
    created.foreach { case (_, hash, encoded) => into.byHash.put(hash, encoded) }
    // a block that commits also keeps what it did to each position (data-model 3)
    into.changes.foreach(_.record(trie, replaced ++ absorbed, created))
    collapsed.map(_._1)

  override def updateNodesInStorage(newRoot: Option[MptNode], toRemove: Seq[MptNode]): Option[MptNode] =
    throw new IllegalStateException("An update without positions: a position store cannot place its nodes")

  /** Nothing to write: a throwaway layer is never committed, and a read-only view holds nothing. */
  override def persist(): Unit = ()

  /** No row is dropped here: this storage owns none, and what its layer holds by hash stays, whatever trie it was in
    * (an older world of the same block may still read it). A block that commits notes the reset, for its diff.
    */
  override def resetOwner(owner: ByteString): Unit = layer.flatMap(_.changes).foreach(_.reset(owner))

  override def storeRawNodes(nodes: Seq[(ByteString, Array[Byte])]): Unit =
    throw new UnsupportedOperationException("A position store cannot place a node it is given by hash alone")

  /** The rows at the positions asked for, as the view holds them, for a caller that judges each entry itself. */
  override def rawNodesAt(requests: Seq[(NibblePath, ByteString)]): Seq[RawNodeRead] =
    val trie = owner("A raw read")
    requests.map { case (path, _) =>
      view.read(PositionKey.at(trie, path)).fold[RawNodeRead](RawNodeRead.Absent)(RawNodeRead.Bytes(_))
    }

  // A batch of hashes says nothing of where its nodes sit. The trait's defaults would answer "absent" for nearly every
  // one of them, and their callers (healing, the audit) read "absent" as "fetch it" or "it is gone".
  override def multiGetNodes(hashes: Seq[Array[Byte]]): Seq[Option[MptNode]] = throw PathMptStorage.byHashAlone

  override def rawNodes(hashes: Seq[ByteString]): Seq[RawNodeRead] = throw PathMptStorage.byHashAlone

object PathMptStorage:

  private def named(source: StateView.Source): String = source match
    case StateView.Source.Layer => "a layer"
    case StateView.Source.Index => "the changeset records"
    case StateView.Source.Disk  => "the disk"

  /** What the tries of one execution wrote: every node, in every version it had, by hash. And, for a block that is to
    * commit, what it did to each position.
    */
  final private[pathstate] class Layer(val changes: Option[StagedChanges] = None):
    val byHash = new ConcurrentHashMap[ByteString, Array[Byte]]()

  /** `view` as a storage nothing can be written to. */
  def readOnly(view: StateView): MptStorage = new PathMptStorage(view, None, None)

  /** `view` under a fresh layer that takes an execution's writes and is dropped with it. */
  def throwaway(view: StateView): MptStorage = new PathMptStorage(view, Some(new Layer), None)

  /** `view` under a fresh layer for a block that is to commit: the layer of [[throwaway]], which also records in
    * `changes` what the block does to each position.
    */
  private[pathstate] def staged(view: StateView, changes: StagedChanges): MptStorage =
    new PathMptStorage(view, Some(new Layer(Some(changes))), None)

  private def readOnlyView: UnsupportedOperationException =
    new UnsupportedOperationException("This view of the state is read-only")

  private def byHashAlone: UnsupportedOperationException =
    new UnsupportedOperationException("A position store cannot find nodes by hash alone: read them by position")
