package com.chipprbots.ethereum.db.dataSource

import cats.effect.IO

import fs2.Stream

import com.chipprbots.ethereum.db.dataSource.RocksDbDataSource.IterationError

trait DataSource:
  import DataSource.*

  /** This function obtains the associated value to a key. It requires the (key-value) pair to be in the DataSource
    *
    * @param namespace
    *   which will be searched for the key.
    * @param key
    *   the key retrieve the value.
    * @return
    *   the value associated with the passed key.
    */
  def apply(namespace: Namespace, key: Key): Value = get(namespace, key).getOrElse(
    throw new NoSuchElementException(s"Key not found in namespace $namespace")
  )

  /** This function obtains the associated value to a key, if there exists one.
    *
    * @param namespace
    *   which will be searched for the key.
    * @param key
    *   the key retrieve the value.
    * @return
    *   the value associated with the passed key.
    */
  def get(namespace: Namespace, key: Key): Option[Value]

  /** This function obtains the associated value to a key, if there exists one. It assumes that caller already properly
    * serialized key. Useful when caller knows some pattern in data to avoid generic serialization.
    *
    * @param key
    *   the key retrieve the value.
    * @return
    *   the value associated with the passed key.
    */
  def getOptimized(namespace: Namespace, key: Array[Byte]): Option[Array[Byte]]

  /** [[getOptimized]] for a read whose value the caller keeps in its own cache (contract code): the storage should not
    * also keep the block in its block cache, where one 64 KB value displaces a few dozen index, filter and data blocks
    * of the state column families. The default is a plain [[getOptimized]]; [[RocksDbDataSource]] reads with
    * `fill_cache=false`. The answer is identical either way.
    */
  def getOptimizedNoFill(namespace: Namespace, key: Array[Byte]): Option[Array[Byte]] = getOptimized(namespace, key)

  /** Batch point-lookup for multiple keys in the same namespace. Returns one `Option` per key — `None` for a cache
    * miss. The default implementation calls [[getOptimized]] sequentially; [[RocksDbDataSource]] overrides with a
    * single `multiGetAsList` JNI call, amortising per-call overhead and bloom-filter evaluation across the batch.
    */
  def multiGetOptimized(namespace: Namespace, keys: Seq[Array[Byte]]): Seq[Option[Array[Byte]]] =
    keys.map(k => getOptimized(namespace, k))

  /** Forward range scan over `[fromKey, toKeyExclusive)` in ascending unsigned-lexicographic key order, within
    * `namespace`. Synchronous; the returned `Iterator` is materialized from the bounded window so no storage-native
    * iterator/resource outlives this call (close-on-return — abort-safe even if the caller stops consuming).
    *
    * Unlike [[multiGetOptimized]] (independent random point lookups, one bloom probe per key), implementations should
    * use a single forward seek+next — the right primitive for a dense, sequentially-keyed column family such as the BFS
    * level queue, where every key is present and contiguous. Keys with high bytes (>= 0x80) MUST order correctly
    * (unsigned compare).
    */
  def scanRange(
      namespace: Namespace,
      fromKey: Array[Byte],
      toKeyExclusive: Array[Byte]
  ): Iterator[(Array[Byte], Array[Byte])]

  /** Delete every key in `[fromKey, toKeyExclusive)` (lexicographic byte order) within `namespace`.
    *
    * Implementations should prefer a storage-native range delete over per-key tombstones: RocksDB writes a SINGLE range
    * tombstone and reclaims space during compaction, whereas deleting N keys point-by-point writes N tombstones —
    * observed live at ~140M keys this ground for ~30 minutes at full CPU and drove the container to the edge of its
    * memory cgroup before the BFS healing walk could start.
    */
  def deleteRange(namespace: Namespace, fromKey: Array[Byte], toKeyExclusive: Array[Byte]): Unit

  /** This function updates the DataSource by deleting, updating and inserting new (key-value) pairs. Implementations
    * should guarantee that the whole operation is atomic.
    */
  def update(dataSourceUpdates: Seq[DataUpdate]): Unit

  /** Fsync-backed variant of [[update]]: flushes the OS write buffer to disk before returning. Used for critical
    * one-time writes (e.g. SNAP finalization) where losing the write on power-loss would force an expensive recovery.
    * Default implementation falls back to [[update]] for non-RocksDB backends (e.g. ephemeral in-memory stores used in
    * tests).
    */
  def updateSync(dataSourceUpdates: Seq[DataUpdate]): Unit = update(dataSourceUpdates)

  /** This function updates the DataSource by deleting all the (key-value) pairs in it.
    */
  def clear(): Unit

  /** This function closes the DataSource, without deleting the files used by it.
    */
  def close(): Unit

  /** This function closes the DataSource, if it is not yet closed, and deletes all the files used by it.
    */
  def destroy(): Unit

  /** Return key-value pairs until first error or until whole db has been iterated
    */
  def iterate(): Stream[IO, Either[IterationError, (Array[Byte], Array[Byte])]]

  /** Return key-value pairs until first error or until whole namespace has been iterated
    */
  def iterate(namespace: Namespace): Stream[IO, Either[IterationError, (Array[Byte], Array[Byte])]]

object DataSource:
  type Key = IndexedSeq[Byte]
  type Value = IndexedSeq[Byte]
  type Namespace = IndexedSeq[Byte]
