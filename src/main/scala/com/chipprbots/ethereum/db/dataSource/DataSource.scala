package com.chipprbots.ethereum.db.dataSource

import cats.effect.IO

import fs2.Stream

import com.chipprbots.ethereum.db.dataSource.RocksDbDataSource.IterationError

trait DataSource {
  import DataSource._

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

  /** Batch point-lookup for multiple keys in the same namespace. Returns one `Option` per key — `None` for a cache
    * miss. The default implementation calls [[getOptimized]] sequentially; [[RocksDbDataSource]] overrides with a
    * single `multiGetAsList` JNI call, amortising per-call overhead and bloom-filter evaluation across the batch.
    */
  def multiGetOptimized(namespace: Namespace, keys: Seq[Array[Byte]]): Seq[Option[Array[Byte]]] =
    keys.map(k => getOptimized(namespace, k))

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

}

object DataSource {
  type Key = IndexedSeq[Byte]
  type Value = IndexedSeq[Byte]
  type Namespace = IndexedSeq[Byte]
}
