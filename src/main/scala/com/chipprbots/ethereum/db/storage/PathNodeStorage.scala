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

  /** True when neither column family holds a node: one seek each. Part of `HealInvariant.isEmpty` (spec 022 D7). */
  def isEmpty: Boolean = !hasAnyEntry(acctNs) && !hasAnyEntry(storageNs)

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

  // ---- reads by position (spec 018 Track C) ----

  /** The node stored at `position`, or `None` when no row is there. The key is used as it is, never encoded again: it
    * holds the bytes [[writeAccountNode]] and [[writeStorageNode]] write (the hex-prefix path, after the owner for a
    * storage trie). One point read that fills the block cache, as a read on the execution path should. A closed or
    * failing database throws what it throws: neither is an absent row.
    */
  def read(position: com.chipprbots.ethereum.db.storage.pathstate.PositionKey): Option[Array[Byte]] =
    dataSource.getOptimized(if position.owner.isEmpty then acctNs else storageNs, position.rowKey)

  /** The rows of the storage trie of `owner`, in key order, or `None` when there are more than `maxRows` of them or
    * they hold more than `maxBytes`: a seek and a forward scan of CF `u` from the owner's first key (spec 018
    * data-model 3.6). It lists the rows that are there, whatever trie they once made up, so a trie with a hole in it is
    * listed like any other.
    *
    * The scan is made a chunk at a time, each from the key after the last one read. One long scan would hold the data
    * source's read lock from end to end, and a writer queued behind it holds back every other reader. It reads one
    * consistent set of rows only while nothing writes this owner's rows in between, which is the caller's to see to:
    * the canonical commit calls it under the state lock.
    *
    * The scan ends before `owner ++ 0xff`. Every row of the owner sorts below that: the byte after the owner is the
    * first of a hex-prefix path that is not a leaf's, `0x00` or `0x1X`. And every row of another owner sorts outside
    * it.
    */
  def storageRows(
      owner: ByteString,
      maxRows: Int,
      maxBytes: Long = Long.MaxValue,
      chunkRows: Int = PathNodeStorage.StorageRowsChunk
  ): Option[Seq[(pathstate.PositionKey, ByteString)]] =
    require(owner.length == pathstate.PositionKey.OwnerLength, "the owner of a storage trie is a 32-byte account hash")
    require(chunkRows > 0, "a chunk of the scan holds at least one row")
    val until = owner.toArray :+ 0xff.toByte
    val listed = Vector.newBuilder[(pathstate.PositionKey, ByteString)]
    @annotation.tailrec
    def scan(from: Array[Byte], rowsSoFar: Long, bytesSoFar: Long): Boolean =
      val chunk = dataSource.scanRangeLimit(storageNs, from, until, chunkRows).toVector
      val rows = rowsSoFar + chunk.size
      val bytes = bytesSoFar + chunk.iterator.map((key, node) => key.length.toLong + node.length).sum
      if rows > maxRows || bytes > maxBytes then false
      else
        chunk.foreach { (key, node) =>
          listed += pathstate.PositionKey(Some(owner), ByteString(key).drop(owner.length)) -> ByteString(node)
        }
        // a chunk that is not full was the last; the next begins at the first key after the last one read
        if chunk.size < chunkRows then true else scan(chunk.last._1 :+ 0.toByte, rows, bytes)
    Option.when(scan(owner.toArray, 0L, 0L))(listed.result())

  /** The updates that leave each of `positions` holding the node given for it, or no row for `None`: for a caller's
    * batch. A position is written once: the caller gives each at most once.
    */
  def rowUpdates(positions: Iterable[(pathstate.PositionKey, Option[ByteString])]): Seq[DataSourceUpdateOptimized] =
    val (ofAccounts, ofStorage) = positions.toSeq.partition(_._1.owner.isEmpty)
    def updates(namespace: IndexedSeq[Byte], rows: Seq[(pathstate.PositionKey, Option[ByteString])]) =
      Option.when(rows.nonEmpty)(
        DataSourceUpdateOptimized(
          namespace,
          toRemove = rows.collect { case (position, None) => position.rowKey },
          toUpsert = rows.collect { case (position, Some(node)) => position.rowKey -> node.toArray }
        )
      )
    updates(acctNs, ofAccounts).toSeq ++ updates(storageNs, ofStorage)

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

object PathNodeStorage:

  /** How many rows one step of [[PathNodeStorage.storageRows]] reads under the data source's read lock. */
  val StorageRowsChunk: Int = 8192
