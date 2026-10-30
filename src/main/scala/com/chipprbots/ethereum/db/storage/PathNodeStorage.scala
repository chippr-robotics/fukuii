package com.chipprbots.ethereum.db.storage

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.db.dataSource.DataSource
import com.chipprbots.ethereum.db.dataSource.DataSourceUpdateOptimized
import com.chipprbots.ethereum.mpt.HexPrefix

/** Path-keyed trie node storage for PathScheme SNAP sync.
  *
  * Provides separate column families for account-trie nodes and storage-trie nodes, both keyed by their nibble path
  * rather than their keccak256 hash. This matches go-ethereum's path-scheme layout (`rawdb.PathScheme`):
  *
  *   - Account trie nodes: HP-encoded nibble path → RLP blob (in [[Namespaces.StateTriePathNamespace]])
  *   - Storage trie nodes: accountHash (32 bytes) ++ HP-encoded nibble path → RLP blob (in
  *     [[Namespaces.StorageTriePathNamespace]])
  *
  * Path-keyed storage enables inline pruning: old nodes at a path are overwritten when the trie is updated, so no
  * reference-count or garbage-collection phase is needed. The trade-off is that switching between HashScheme and
  * PathScheme on an existing DB is forbidden; [[com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController]]
  * enforces this with a startup guard.
  *
  * ==Key encoding==
  *
  * Nibble paths from [[com.chipprbots.ethereum.mpt.StackTrie]] are HP-encoded with `isLeaf = false` before use as
  * RocksDB keys. This produces compact 2-byte-aligned keys and avoids the ambiguity of odd-length nibble arrays. The HP
  * encoding is the same regardless of node type (leaf vs extension) because the path-scheme storage layer does not
  * distinguish them.
  *
  * ==Thread safety==
  *
  * Not thread-safe. Each method performs a single synchronous RocksDB write. Callers are responsible for serialising
  * concurrent access (the SNAP coordinator actors already provide this guarantee through actor-message ordering).
  *
  * @param dataSource
  *   Shared RocksDB data source. Both column families live in the same data source instance.
  */
class PathNodeStorage(val dataSource: DataSource):

  private val acctNs: IndexedSeq[Byte] = Namespaces.StateTriePathNamespace
  private val storageNs: IndexedSeq[Byte] = Namespaces.StorageTriePathNamespace

  // ---- account trie (STATE_TRIE_PATH column family) ----

  /** Write an account-trie node at `nibblePath`.
    *
    * `nibblePath` is the raw nibble array emitted by [[com.chipprbots.ethereum.mpt.StackTrie]]'s `onTrieNode` callback.
    * It is HP-encoded before storage so that odd-length paths map unambiguously to fixed-length keys.
    *
    * @param nibblePath
    *   raw nibble array (each byte 0x00–0x0f)
    * @param rlp
    *   RLP-encoded node bytes
    */
  def writeAccountNode(nibblePath: Array[Byte], rlp: Array[Byte]): Unit =
    dataSource.update(Seq(accountNodeUpsert(nibblePath, rlp)))

  /** The single-node update [[writeAccountNode]] commits, for a caller that groups several updates into one write batch
    * (`dataSource.update` applies the updates of one call in order, in one RocksDB WriteBatch). Same key and value
    * bytes as [[writeAccountNode]]; the account-trie mirror of [[storageNodeUpsert]].
    */
  def accountNodeUpsert(nibblePath: Array[Byte], rlp: Array[Byte]): DataSourceUpdateOptimized =
    DataSourceUpdateOptimized(namespace = acctNs, toRemove = Nil, toUpsert = Seq(encodePath(nibblePath) -> rlp))

  /** Read an account-trie node by nibble path. Returns `None` if no node is stored at that path. */
  def readAccountNode(nibblePath: Array[Byte]): Option[Array[Byte]] =
    val key = encodePath(nibblePath)
    dataSource.getOptimized(acctNs, key)

  /** Delete the single account-trie node at exactly `nibblePath`. No-op if the path does not exist. */
  def deleteAccountNode(nibblePath: Array[Byte]): Unit =
    dataSource.update(Seq(accountNodeRemoval(nibblePath)))

  /** The single-node update [[deleteAccountNode]] commits; see [[accountNodeUpsert]]. */
  def accountNodeRemoval(nibblePath: Array[Byte]): DataSourceUpdateOptimized =
    DataSourceUpdateOptimized(namespace = acctNs, toRemove = Seq(encodePath(nibblePath)), toUpsert = Nil)

  // ---- storage trie (STORAGE_TRIE_PATH column family) ----

  /** Write a storage-trie node, scoped by account.
    *
    * Key layout: `accountHash (32 bytes) ++ HP-encoded nibble path`.
    *
    * @param accountHash
    *   32-byte account hash that owns this storage trie
    * @param nibblePath
    *   raw nibble array for the node's position in the storage trie
    * @param rlp
    *   RLP-encoded node bytes
    */
  def writeStorageNode(accountHash: ByteString, nibblePath: Array[Byte], rlp: Array[Byte]): Unit =
    dataSource.update(Seq(storageNodeUpsert(accountHash, nibblePath, rlp)))

  /** The single-node update [[writeStorageNode]] commits, for a caller that groups many nodes into one write batch
    * (`dataSource.update` applies the updates of one call in order, in one RocksDB WriteBatch). Same key and value
    * bytes as [[writeStorageNode]].
    */
  def storageNodeUpsert(accountHash: ByteString, nibblePath: Array[Byte], rlp: Array[Byte]): DataSourceUpdateOptimized =
    DataSourceUpdateOptimized(
      namespace = storageNs,
      toRemove = Nil,
      toUpsert = Seq(storageKey(accountHash, nibblePath) -> rlp)
    )

  /** Read a storage-trie node by account hash and nibble path. Returns `None` if absent. */
  def readStorageNode(accountHash: ByteString, nibblePath: Array[Byte]): Option[Array[Byte]] =
    val key = storageKey(accountHash, nibblePath)
    dataSource.getOptimized(storageNs, key)

  /** Delete the single storage-trie node for `accountHash` at exactly `nibblePath`. No-op if absent. */
  def deleteStorageNode(accountHash: ByteString, nibblePath: Array[Byte]): Unit =
    dataSource.update(Seq(storageNodeRemoval(accountHash, nibblePath)))

  /** The single-node update [[deleteStorageNode]] commits; see [[storageNodeUpsert]]. */
  def storageNodeRemoval(accountHash: ByteString, nibblePath: Array[Byte]): DataSourceUpdateOptimized =
    DataSourceUpdateOptimized(
      namespace = storageNs,
      toRemove = Seq(storageKey(accountHash, nibblePath)),
      toUpsert = Nil
    )

  // ---- both tries ----

  /** Write one healed node and delete the positions that write leaves dangling, in ONE write batch (spec 022 FR-008,
    * D10; geth `trie/sync.go` `children()` and `hasNode`): one `dataSource.update`, so a crash keeps all of it or none.
    *
    * A position is `(owner, nibblePath)`: `owner` empty for the account trie, else the account hash whose storage trie
    * it is in. The node goes to `(owner, nibblePath)` with the same key and value bytes as [[writeAccountNode]] /
    * [[writeStorageNode]]. `deletes` may name either trie (an account leaf's write deletes a stale storage root in the
    * other column family). The removals are applied first: a position in `deletes` that is also the written one keeps
    * the written node. Healing never passes one, since every dangling position lies strictly below the written node.
    */
  def writeNodeWithDeletes(
      owner: Option[ByteString],
      nibblePath: Array[Byte],
      rlp: Array[Byte],
      deletes: Seq[(Option[ByteString], Array[Byte])]
  ): Unit =
    writeNodesWithDeletes(Seq((owner, nibblePath, rlp)), deletes)

  /** Write healed nodes and delete stale positions, all in ONE write batch (spec 022 D3 and FR-008): one
    * `dataSource.update`, so a crash keeps all of it or none. Healing passes a whole commit here: a node together with
    * every ancestor its subtree completes (children first), every alias position of a node, and the deletes found while
    * discovering children since the last commit.
    *
    * A node is `(owner, nibblePath, rlp)`, a position `(owner, nibblePath)`, as in [[writeNodeWithDeletes]]. Every
    * removal is applied before any write, and a position that is both deleted and written keeps the written node: a
    * stale position found under one node of the batch may be where another node of the same batch belongs.
    */
  def writeNodesWithDeletes(
      nodes: Seq[(Option[ByteString], Array[Byte], Array[Byte])],
      deletes: Seq[(Option[ByteString], Array[Byte])]
  ): Unit =
    // Point deletes, not deleteRange: HP-encoded keys are not prefix-ordered (a position's key does not prefix its
    // descendants'), and deleteRange is not part of the one atomic `update` batch.
    val written = nodes.iterator.map { case (o, path, _) => positionKey(o, path) }.toSet
    val removals = deletes.collect {
      case (o, path) if !written.contains(positionKey(o, path)) =>
        o.fold(accountNodeRemoval(path))(storageNodeRemoval(_, path))
    }
    val upserts = nodes.map { case (o, path, rlp) =>
      o.fold(accountNodeUpsert(path, rlp))(storageNodeUpsert(_, path, rlp))
    }
    if removals.nonEmpty || upserts.nonEmpty then dataSource.update(removals ++ upserts)

  /** True if the StateTriePathNamespace column family has at least one entry. Used by the startup guard. */
  def hasAccountData: Boolean = hasAnyEntry(acctNs)

  /** Stream every stored `(key, rlp)` of the account-trie CF (`key` = HP-encoded path) in key order, in chunks of
    * `chunkSize`, calling `f` once per chunk. Used to publish a Path-scheme trie to the hash-keyed store.
    */
  def foreachAccountNodeChunk(chunkSize: Int)(f: Seq[(Array[Byte], Array[Byte])] => Unit): Unit =
    foreachChunk(acctNs, chunkSize)(f)

  /** As [[foreachAccountNodeChunk]] for the storage-trie CF; `key` = accountHash (32 bytes) ++ HP-encoded path. */
  def foreachStorageNodeChunk(chunkSize: Int)(f: Seq[(Array[Byte], Array[Byte])] => Unit): Unit =
    foreachChunk(storageNs, chunkSize)(f)

  private def foreachChunk(ns: IndexedSeq[Byte], chunkSize: Int)(f: Seq[(Array[Byte], Array[Byte])] => Unit): Unit =
    import cats.effect.unsafe.implicits.global
    // RocksDbDataSource yields keys WITHOUT the namespace byte; the in-memory EphemDataSource test double keeps it
    // (see HealingFrontierStorageSpec). Normalise so callers always see the bare key.
    val strip = if dataSource.isInstanceOf[com.chipprbots.ethereum.db.dataSource.EphemDataSource] then ns.length else 0
    dataSource
      .iterate(ns)
      .chunkN(chunkSize)
      .evalMap { chunk =>
        cats.effect.IO {
          f(chunk.toVector.map {
            case Right((k, v)) => (k.drop(strip), v)
            case Left(err)     => throw new IllegalStateException("Path node iteration failed", err.ex)
          })
        }
      }
      .compile
      .drain
      .unsafeRunSync()

  // ---- internals ----

  private def encodePath(nibbles: Array[Byte]): Array[Byte] =
    HexPrefix.encode(nibbles, isLeaf = false)

  private def storageKey(accountHash: ByteString, nibbles: Array[Byte]): Array[Byte] =
    accountHash.toArray ++ encodePath(nibbles)

  /** A position as one comparable value: a trie tag, the owner for a storage trie, then the HP-encoded path. */
  private def positionKey(owner: Option[ByteString], nibbles: Array[Byte]): ByteString =
    owner.fold(ByteString(0.toByte) ++ ByteString(encodePath(nibbles)))(o =>
      ByteString(1.toByte) ++ o ++ ByteString(encodePath(nibbles))
    )

  private def hasAnyEntry(ns: IndexedSeq[Byte]): Boolean =
    import cats.effect.unsafe.implicits.global
    dataSource.iterate(ns).take(1).compile.last.unsafeRunSync().isDefined
