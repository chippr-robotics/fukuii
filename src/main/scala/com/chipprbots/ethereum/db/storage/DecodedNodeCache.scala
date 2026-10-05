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
final class DecodedNodeCache private (lru: ByteBoundedLru[DecodedNodeCache.Key, MptNode], owner: Long):

  def get(hash: ByteString): MptNode = lru.getOrNull(DecodedNodeCache.Key(owner, hash))
  def put(hash: ByteString, node: MptNode): Unit = lru.put(DecodedNodeCache.Key(owner, hash), node)

  /** Bumped by every [[evict]]. A reader takes it BEFORE reading the database and hands it to [[putIfCurrent]]. */
  private val evictions = new java.util.concurrent.atomic.AtomicLong

  /** The eviction count to read before going to the database for a node that is then given to [[putIfCurrent]]. */
  def generation: Long = evictions.get

  /** Caches a node read from the database, unless an eviction ran since `generationBeforeRead` was taken.
    *
    * Closes the delete-then-evict race (task #74): a reader that loaded a node just before pruning deleted it could
    * insert it just after [[evict]] ran, leaving a ghost that masks a later `MissingNodeException`. Insert first, then
    * re-check: an eviction that ran before the check bumped the count (and the entry is dropped here); one that runs
    * after the check removes the entry itself. Evictors bump before they remove and run after the delete, so a reader
    * whose database read saw the node necessarily took its generation before that bump.
    */
  def putIfCurrent(hash: ByteString, node: MptNode, generationBeforeRead: Long): Unit =
    val key = DecodedNodeCache.Key(owner, hash)
    lru.put(key, node)
    if evictions.get != generationBeforeRead then lru.remove(key)

  def evict(hashes: Iterable[NodeHash]): Unit =
    evictions.incrementAndGet()
    hashes.foreach(h => lru.remove(DecodedNodeCache.Key(owner, h)))
  def sizeBytes: Long = lru.sizeBytes
  def entries: Int = lru.entries

object DecodedNodeCache:

  /** A cached node belongs to the state storage that read it: two databases (a test builds hundreds) never see each
    * other's nodes, so "this node is missing from my database" stays true however many storages share the cache.
    */
  final case class Key(owner: Long, hash: ByteString)

  private val owners = new java.util.concurrent.atomic.AtomicLong

  /** Retained bytes of a decoded node, measured: a 532-byte branch node (16 hash children) decodes to about 3.9 KB, a
    * little over 7x its RLP (the parsed RLP tree, a `HashNode` and hash array per child, the cached encoding and hash).
    * 8x plus a fixed header is the conservative estimate; the earlier 3x let the cache hold 2.5x its budget.
    */
  private[storage] def weigh(node: MptNode): Long = node.cachedRlpEncoded.fold(512L)(_.length.toLong) * 8 + 160

  /** One cache for the whole process, so the budget bounds the heap however many state storages exist. */
  private lazy val shared: ByteBoundedLru[Key, MptNode] =
    new ByteBoundedLru[Key, MptNode](
      com.chipprbots.ethereum.utils.StateReadCacheConfig.decodedNodeCacheBytes,
      weigh,
      com.chipprbots.ethereum.utils.StateReadCacheConfig.decodedNodeCacheStrong
    )

  /** A view for one state storage on the process-wide cache, or `None` when `enabled` is false or the budget is 0. */
  def forStorage(enabled: Boolean): Option[DecodedNodeCache] =
    Option.when(enabled && shared.maxBytes > 0)(new DecodedNodeCache(shared, owners.incrementAndGet()))

  /** A cache with its own budget, for tests that need a small or isolated one. */
  def withOwnBudget(maxBytes: Long, strongValues: Boolean = false): DecodedNodeCache =
    new DecodedNodeCache(new ByteBoundedLru[Key, MptNode](maxBytes, weigh, strongValues), owners.incrementAndGet())

/** [[MptStorage]] that answers `get` from a [[DecodedNodeCache]] before the wrapped storage. A miss, including a
  * missing node, goes to the wrapped storage unchanged: its `MissingNodeException` propagates and nothing is cached.
  * Writes, removals and batch lookups pass straight through.
  */
final class CachingMptStorage(underlying: MptStorage, cache: DecodedNodeCache) extends MptStorage:

  override def get(nodeId: Array[Byte]): MptNode =
    val key = ByteString.fromArrayUnsafe(nodeId)
    val hit = cache.get(key)
    if hit != null then
      ImportProfile.nodeHit(key)
      hit
    else
      val generation = cache.generation // before the read: see DecodedNodeCache.putIfCurrent
      val node = underlying.get(nodeId)
      ImportProfile.nodeMiss()
      cache.putIfCurrent(key.compact, node, generation)
      node

  override def updateNodesInStorage(newRoot: Option[MptNode], toRemove: Seq[MptNode]): Option[MptNode] =
    underlying.updateNodesInStorage(newRoot, toRemove)

  override def persist(): Unit = underlying.persist()

  override def multiGetNodes(hashes: Seq[Array[Byte]]): Seq[Option[MptNode]] = underlying.multiGetNodes(hashes)

  override def storeRawNodes(nodes: Seq[(ByteString, Array[Byte])]): Unit = underlying.storeRawNodes(nodes)
