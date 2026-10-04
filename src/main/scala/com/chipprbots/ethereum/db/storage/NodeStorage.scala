package com.chipprbots.ethereum.db.storage

import org.apache.pekko.util.ByteString

import cats.effect.IO

import fs2.Stream

import com.chipprbots.ethereum.db.cache.Cache
import com.chipprbots.ethereum.db.dataSource.DataSource
import com.chipprbots.ethereum.db.dataSource.DataSourceBatchUpdate
import com.chipprbots.ethereum.db.dataSource.DataSourceUpdateOptimized
import com.chipprbots.ethereum.db.dataSource.RocksDbDataSource.IterationError
import com.chipprbots.ethereum.db.storage.NodeStorage.NodeEncoded
import com.chipprbots.ethereum.db.storage.NodeStorage.NodeHash

sealed trait NodesStorage:
  def get(key: NodeHash): Option[NodeEncoded]
  def update(toRemove: Seq[NodeHash], toUpsert: Seq[(NodeHash, NodeEncoded)]): NodesStorage
  def updateCond(toRemove: Seq[NodeHash], toUpsert: Seq[(NodeHash, NodeEncoded)], inMemory: Boolean): NodesStorage
  def multiGet(keys: Seq[NodeHash]): Seq[Option[NodeEncoded]] = keys.map(get)

/** This class is used to store Nodes (defined in mpt/Node.scala), by using: Key: hash of the RLP encoded node Value:
  * the RLP encoded node
  */
class NodeStorage(val dataSource: DataSource)
    extends KeyValueStorage[NodeHash, NodeEncoded, NodeStorage]
    with NodesStorage:

  val namespace: IndexedSeq[Byte] = Namespaces.NodeNamespace
  def keySerializer: NodeHash => IndexedSeq[Byte] = _.toIndexedSeq
  def keyDeserializer: IndexedSeq[Byte] => NodeHash = h => ByteString(h.toArray)
  def valueSerializer: NodeEncoded => IndexedSeq[Byte] = _.toIndexedSeq
  def valueDeserializer: IndexedSeq[Byte] => NodeEncoded = _.toArray

  override def get(key: NodeHash): Option[NodeEncoded] = dataSource.getOptimized(namespace, key.toArray)

  override def multiGet(keys: Seq[NodeHash]): Seq[Option[NodeEncoded]] =
    dataSource.multiGetOptimized(namespace, keys.map(_.toArray))

  /** This function updates the KeyValueStorage by deleting, updating and inserting new (key-value) pairs in the current
    * namespace.
    *
    * @param toRemove
    *   which includes all the keys to be removed from the KeyValueStorage.
    * @param toUpsert
    *   which includes all the (key-value) pairs to be inserted into the KeyValueStorage. If a key is already in the
    *   DataSource its value will be updated.
    * @return
    *   the new KeyValueStorage after the removals and insertions were done.
    */
  override def update(toRemove: Seq[NodeHash], toUpsert: Seq[(NodeHash, NodeEncoded)]): NodeStorage =
    dataSource.update(
      Seq(
        DataSourceUpdateOptimized(
          namespace = Namespaces.NodeNamespace,
          toRemove = toRemove.map(_.toArray),
          toUpsert = toUpsert.map(values => values._1.toArray -> values._2)
        )
      )
    )
    apply(dataSource)

  override def storageContent: Stream[IO, Either[IterationError, (NodeHash, NodeEncoded)]] =
    dataSource.iterate(namespace).map { result =>
      result.map { case (key, value) => (ByteString.fromArrayUnsafe(key), value) }
    }

  protected def apply(dataSource: DataSource): NodeStorage = new NodeStorage(dataSource)

  def updateCond(toRemove: Seq[NodeHash], toUpsert: Seq[(NodeHash, NodeEncoded)], inMemory: Boolean): NodesStorage =
    update(toRemove, toUpsert)

class CachedNodeStorage(val storage: NodeStorage, val cache: Cache[NodeHash, NodeEncoded])
    extends CachedKeyValueStorage[NodeHash, NodeEncoded, CachedNodeStorage]
    with NodesStorage:
  override type I = NodeStorage
  override def apply(cache: Cache[NodeHash, NodeEncoded], storage: NodeStorage): CachedNodeStorage =
    new CachedNodeStorage(storage, cache)

/** A write buffer over a [[NodeStorage]]: reads see the buffered writes first, nothing reaches the database until the
  * caller commits [[pending]] (typically together with the block it belongs to, in ONE atomic batch), and dropping the
  * buffer throws the lot away.
  *
  * It sits BELOW [[ReferenceCountNodeStorage]], which therefore computes reference counts, snapshots and death rows
  * exactly as it does against the database: it re-reads `dr<bn>` and `sck<bn>` on every update to accumulate, and those
  * reads are served from this buffer.
  */
final class BufferedNodeStorage(val base: NodeStorage) extends NodesStorage:
  private val buffer = scala.collection.mutable.LinkedHashMap.empty[NodeHash, Option[NodeEncoded]]

  override def get(key: NodeHash): Option[NodeEncoded] = buffer.get(key) match
    case Some(buffered) => buffered
    case None           => base.get(key)

  override def multiGet(keys: Seq[NodeHash]): Seq[Option[NodeEncoded]] =
    val misses = keys.filterNot(buffer.contains)
    val fetched =
      if misses.isEmpty then Map.empty[NodeHash, Option[NodeEncoded]] else misses.zip(base.multiGet(misses)).toMap
    keys.map(k => buffer.getOrElse(k, fetched.getOrElse(k, None)))

  override def update(toRemove: Seq[NodeHash], toUpsert: Seq[(NodeHash, NodeEncoded)]): NodesStorage =
    toRemove.foreach(k => buffer.update(k, None))
    toUpsert.foreach { case (k, v) => buffer.update(k, Some(v)) }
    this

  override def updateCond(
      toRemove: Seq[NodeHash],
      toUpsert: Seq[(NodeHash, NodeEncoded)],
      inMemory: Boolean
  ): NodesStorage = update(toRemove, toUpsert)

  /** Every key written or removed, for evicting a discarded block's nodes from caches. */
  def touchedKeys: Seq[NodeHash] = buffer.keys.toSeq

  def isEmpty: Boolean = buffer.isEmpty

  /** The buffered writes as one update on the node namespace, empty batch when nothing was written. */
  def pending: DataSourceBatchUpdate =
    val removes = buffer.collect { case (k, None) => k.toArray }.toSeq
    val upserts = buffer.collect { case (k, Some(v)) => k.toArray -> v }.toSeq
    if removes.isEmpty && upserts.isEmpty then DataSourceBatchUpdate(base.dataSource)
    else
      DataSourceBatchUpdate(
        base.dataSource,
        Array(DataSourceUpdateOptimized(Namespaces.NodeNamespace, removes, upserts))
      )

object NodeStorage:
  type NodeHash = ByteString
  type NodeEncoded = Array[Byte]
