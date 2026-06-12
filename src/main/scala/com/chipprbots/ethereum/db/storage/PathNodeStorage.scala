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
class PathNodeStorage(val dataSource: DataSource) {

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
    * @param hash
    *   keccak256 hash of `rlp` (logged but not stored as the key — the path is the key in PathScheme)
    * @param rlp
    *   RLP-encoded node bytes
    */
  def writeAccountNode(nibblePath: Array[Byte], hash: ByteString, rlp: Array[Byte]): Unit = {
    val key = encodePath(nibblePath)
    dataSource.update(
      Seq(DataSourceUpdateOptimized(namespace = acctNs, toRemove = Nil, toUpsert = Seq(key -> rlp)))
    )
  }

  /** Read an account-trie node by nibble path. Returns `None` if no node is stored at that path. */
  def readAccountNode(nibblePath: Array[Byte]): Option[Array[Byte]] = {
    val key = encodePath(nibblePath)
    dataSource.getOptimized(acctNs, key)
  }

  /** Delete the single account-trie node at exactly `nibblePath`. No-op if the path does not exist. */
  def deleteAccountNode(nibblePath: Array[Byte]): Unit = {
    val key = encodePath(nibblePath)
    dataSource.update(
      Seq(DataSourceUpdateOptimized(namespace = acctNs, toRemove = Seq(key), toUpsert = Nil))
    )
  }

  /** Delete all account-trie nodes whose path starts with `nibblePrefix`.
    *
    * Used by [[com.chipprbots.ethereum.blockchain.sync.snap.SnapPathTrie]] to prune left-boundary ancestor stubs after
    * a subtree has been fully committed.
    */
  def deleteAccountNodesByPrefix(nibblePrefix: Array[Byte]): Unit = {
    val prefix = encodePath(nibblePrefix)
    deleteByPrefix(acctNs, prefix)
  }

  // ---- storage trie (STORAGE_TRIE_PATH column family) ----

  /** Write a storage-trie node, scoped by account.
    *
    * Key layout: `accountHash (32 bytes) ++ HP-encoded nibble path`.
    *
    * @param accountHash
    *   32-byte account hash that owns this storage trie
    * @param nibblePath
    *   raw nibble array for the node's position in the storage trie
    * @param hash
    *   keccak256 hash of `rlp`
    * @param rlp
    *   RLP-encoded node bytes
    */
  def writeStorageNode(accountHash: ByteString, nibblePath: Array[Byte], hash: ByteString, rlp: Array[Byte]): Unit = {
    val key = storageKey(accountHash, nibblePath)
    dataSource.update(
      Seq(DataSourceUpdateOptimized(namespace = storageNs, toRemove = Nil, toUpsert = Seq(key -> rlp)))
    )
  }

  /** Read a storage-trie node by account hash and nibble path. Returns `None` if absent. */
  def readStorageNode(accountHash: ByteString, nibblePath: Array[Byte]): Option[Array[Byte]] = {
    val key = storageKey(accountHash, nibblePath)
    dataSource.getOptimized(storageNs, key)
  }

  /** Delete the single storage-trie node for `accountHash` at exactly `nibblePath`. No-op if absent. */
  def deleteStorageNode(accountHash: ByteString, nibblePath: Array[Byte]): Unit = {
    val key = storageKey(accountHash, nibblePath)
    dataSource.update(
      Seq(DataSourceUpdateOptimized(namespace = storageNs, toRemove = Seq(key), toUpsert = Nil))
    )
  }

  /** Delete all storage-trie nodes for `accountHash` whose path starts with `nibblePrefix`. */
  def deleteStorageNodesByPrefix(accountHash: ByteString, nibblePrefix: Array[Byte]): Unit = {
    val prefix = storageKey(accountHash, nibblePrefix)
    deleteByPrefix(storageNs, prefix)
  }

  /** True if the StateTriePathNamespace column family has at least one entry. Used by the startup guard. */
  def hasAccountData: Boolean = hasAnyEntry(acctNs)

  // ---- internals ----

  private def encodePath(nibbles: Array[Byte]): Array[Byte] =
    HexPrefix.encode(nibbles, isLeaf = false)

  private def storageKey(accountHash: ByteString, nibbles: Array[Byte]): Array[Byte] =
    accountHash.toArray ++ encodePath(nibbles)

  private def deleteByPrefix(ns: IndexedSeq[Byte], prefix: Array[Byte]): Unit = {
    import cats.effect.unsafe.implicits.global
    import fs2.Stream

    // Collect keys matching prefix, then batch-delete them.
    val keys: Vector[Array[Byte]] = dataSource
      .iterate(ns)
      .collect { case Right((k, _)) if k.startsWith(prefix) => k }
      .compile
      .toVector
      .unsafeRunSync()

    if (keys.nonEmpty)
      dataSource.update(
        Seq(DataSourceUpdateOptimized(namespace = ns, toRemove = keys, toUpsert = Nil))
      )
  }

  private def hasAnyEntry(ns: IndexedSeq[Byte]): Boolean = {
    import cats.effect.unsafe.implicits.global
    dataSource.iterate(ns).take(1).compile.last.unsafeRunSync().isDefined
  }

  implicit private class ByteArrayOps(val a: Array[Byte]) {
    def startsWith(prefix: Array[Byte]): Boolean =
      prefix.length <= a.length && java.util.Arrays.equals(a, 0, prefix.length, prefix, 0, prefix.length)
  }
}
