package com.chipprbots.ethereum.db.storage

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.db.storage.NodeStorage.NodeHash
import com.chipprbots.ethereum.mpt.MptNode
import com.chipprbots.ethereum.utils.ByteBoundedLru
import com.chipprbots.ethereum.vm.ImportProfile

/** Decoded trie nodes by node hash, for block execution.
  *
  * A node is content-addressed: under its hash it is the same node forever, so a cached decode is correct wherever the
  * same hash is asked for. What a cache must not do is outlive the node's presence in the database, because the trie
  * code relies on a missing node being missing (a block whose parent state was pruned must fail, not execute against a
  * ghost, and a later write could reference a child the database no longer holds). So the owner calls [[evict]] with
  * the hashes pruning and rollback delete, and only execution reads through this cache: SNAP, healing and the recovery
  * scanners use the storage directly and still see exactly what is on disk.
  */
final class DecodedNodeCache(maxBytes: Long):

  // Decoded objects are several times the size of their RLP; x3 plus a fixed header is a deliberate round estimate.
  private val lru = new ByteBoundedLru[ByteString, MptNode](
    maxBytes,
    node => node.cachedRlpEncoded.fold(512L)(_.length.toLong) * 3 + 160
  )

  def get(hash: ByteString): MptNode = lru.getOrNull(hash)
  def put(hash: ByteString, node: MptNode): Unit = lru.put(hash, node)
  def evict(hashes: Iterable[NodeHash]): Unit = hashes.foreach(lru.remove)
  def clear(): Unit = lru.clear()
  def sizeBytes: Long = lru.sizeBytes
  def entries: Int = lru.entries

/** [[MptStorage]] that answers `get` from a [[DecodedNodeCache]] before the wrapped storage. A miss, including a
  * missing node, goes to the wrapped storage unchanged: its `MissingNodeException` propagates and nothing is cached.
  * Writes, removals and batch lookups pass straight through.
  */
final class CachingMptStorage(underlying: MptStorage, cache: DecodedNodeCache) extends MptStorage:

  override def get(nodeId: Array[Byte]): MptNode =
    val key = ByteString.fromArrayUnsafe(nodeId)
    val hit = cache.get(key)
    if hit != null then
      ImportProfile.nodeHit()
      hit
    else
      val node = underlying.get(nodeId)
      ImportProfile.nodeMiss()
      cache.put(key.compact, node)
      node

  override def updateNodesInStorage(newRoot: Option[MptNode], toRemove: Seq[MptNode]): Option[MptNode] =
    underlying.updateNodesInStorage(newRoot, toRemove)

  override def persist(): Unit = underlying.persist()

  override def multiGetNodes(hashes: Seq[Array[Byte]]): Seq[Option[MptNode]] = underlying.multiGetNodes(hashes)

  override def storeRawNodes(nodes: Seq[(ByteString, Array[Byte])]): Unit = underlying.storeRawNodes(nodes)
