package com.chipprbots.ethereum.db.storage

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.db.storage.MptStorageDecorator.Removals
import com.chipprbots.ethereum.mpt.MptNode
import com.chipprbots.ethereum.mpt.NibblePath

/** An [[MptStorage]] that wraps another and passes every call on to it, for a storage that adds something around the
  * one it wraps: a cache, a count, a translated exception, a record of what was read.
  *
  * [[MptStorage]] has several members that reach the same nodes, and a wrapper written against the trait itself has to
  * override each of them or miss calls: a cache in front of `get` alone is not consulted by a trie that reads with
  * `getAt`, and a wrapper that withholds removals in `updateNodesInStorage` alone does not withhold them from
  * `updateNodesAt`. This base closes both gaps:
  *
  *   - '''Reads.''' `get` and `getAt` are final and both go through [[read]]. Override [[read]] and no node read gets
  *     past the wrapper, whether the caller named a position or only a hash.
  *   - '''Updates.''' `updateNodesInStorage` and `updateNodesAt` are final and both go through [[update]].
  *   - '''One trie.''' `forOwner` returns this same kind of wrapper around the wrapped storage's view of that trie
  *     ([[decorate]]), so the reads and updates of an owner-scoped trie pass through the wrapper too.
  *
  * The batch reads (`multiGetNodes`, `rawNodes`, `rawNodesAt`) are passed on as they are and do '''not''' go through
  * [[read]]: SNAP, healing and the audit use them, block execution does not. A wrapper that must see those too
  * overrides them.
  *
  * `MptStorageDecoratorSpec` fails when [[MptStorage]] gains a member this trait does not forward.
  */
trait MptStorageDecorator extends MptStorage:

  /** The storage this one wraps. */
  protected def underlying: MptStorage

  /** This wrapper around `scoped`, the wrapped storage's view of one trie. A wrapper that keeps state (a cache, a
    * record) hands the same state to the new instance.
    */
  protected def decorate(scoped: MptStorage): MptStorage

  /** Every node read passes through here. `path` is where the node sits in the trie being read, or null when the caller
    * named the node by its hash alone.
    */
  protected def read(hash: Array[Byte], path: NibblePath | Null): MptNode =
    if path == null then underlying.get(hash) else underlying.getAt(path.nn, hash)

  /** Every trie update passes through here, with the nodes it replaces as its caller named them. A wrapper whose
    * wrapped storage must keep every node (a build that deletes nothing, a replay whose earlier worlds stay readable)
    * passes on `removals.none`.
    */
  protected def update(newRoot: Option[MptNode], removals: Removals): Option[MptNode] =
    removals match
      case Removals.Positioned(replaced, absorbed) => underlying.updateNodesAt(newRoot, replaced, absorbed)
      case Removals.Unpositioned(toRemove)         => underlying.updateNodesInStorage(newRoot, toRemove)

  final override def get(nodeId: Array[Byte]): MptNode = read(nodeId, null)

  final override def getAt(path: NibblePath, hash: Array[Byte]): MptNode = read(hash, path)

  final override def updateNodesInStorage(newRoot: Option[MptNode], toRemove: Seq[MptNode]): Option[MptNode] =
    update(newRoot, Removals.Unpositioned(toRemove))

  final override def updateNodesAt(
      newRoot: Option[MptNode],
      replaced: Seq[(NibblePath, MptNode)],
      absorbed: Seq[(NibblePath, MptNode)]
  ): Option[MptNode] =
    update(newRoot, Removals.Positioned(replaced, absorbed))

  override def forOwner(owner: Option[ByteString]): MptStorage =
    val scoped = underlying.forOwner(owner)
    if scoped eq underlying then this else decorate(scoped)

  override def resetOwner(owner: ByteString): Unit = underlying.resetOwner(owner)

  override def persist(): Unit = underlying.persist()

  override def multiGetNodes(hashes: Seq[Array[Byte]]): Seq[Option[MptNode]] = underlying.multiGetNodes(hashes)

  override def rawNodes(hashes: Seq[ByteString]): Seq[RawNodeRead] = underlying.rawNodes(hashes)

  override def rawNodesAt(requests: Seq[(NibblePath, ByteString)]): Seq[RawNodeRead] = underlying.rawNodesAt(requests)

  override def storeRawNodes(nodes: Seq[(ByteString, Array[Byte])]): Unit = underlying.storeRawNodes(nodes)

object MptStorageDecorator:

  /** The nodes a trie update replaces, as its caller named them: with their positions ([[MptStorage.updateNodesAt]]) or
    * without ([[MptStorage.updateNodesInStorage]]). Two `Positioned` values are equal only when they hold the same path
    * objects, since a [[NibblePath]] compares by identity.
    */
  enum Removals:
    case Unpositioned(toRemove: Seq[MptNode])
    case Positioned(replaced: Seq[(NibblePath, MptNode)], absorbed: Seq[(NibblePath, MptNode)])

    /** The same kind of update with no node named as replaced. */
    def none: Removals = this match
      case _: Unpositioned => Unpositioned(Nil)
      case _: Positioned   => Positioned(Nil, Nil)
