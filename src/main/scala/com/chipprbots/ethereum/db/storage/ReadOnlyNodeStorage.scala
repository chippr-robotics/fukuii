package com.chipprbots.ethereum.db.storage

import scala.collection.mutable

import com.chipprbots.ethereum.db.storage.NodeStorage.NodeEncoded
import com.chipprbots.ethereum.db.storage.NodeStorage.NodeHash
import com.chipprbots.ethereum.mpt.NodesKeyValueStorage

/** This storage allows to read from another NodesKeyValueStorage but doesn't remove or upsert into database. To do so,
  * it uses an internal in memory cache to apply all the changes.
  */
class ReadOnlyNodeStorage private (wrapped: NodesKeyValueStorage) extends NodesKeyValueStorage:
  val buffer: mutable.Map[NodeHash, Option[NodeEncoded]] = mutable.Map.empty[NodeHash, Option[NodeEncoded]]

  private def changes: (Seq[NodeHash], Seq[(NodeHash, NodeEncoded)]) =
    // Use List prepend (O(1)) instead of Seq append (O(n)) to avoid O(n²) for large buffers
    val (removeAcc, upsertAcc) =
      buffer.foldLeft((List.empty[NodeHash], List.empty[(NodeHash, NodeEncoded)])) { (acc, cachedItem) =>
        cachedItem match
          case (key, Some(value)) => (acc._1, (key -> value) :: acc._2)
          case (key, None)        => (key :: acc._1, acc._2)
      }
    (removeAcc, upsertAcc)

  /** This function obtains the value asociated with the key passed, if there exists one.
    *
    * @param key
    * @return
    *   Option object with value if there exists one.
    */
  override def get(key: NodeHash): Option[NodeEncoded] = buffer.getOrElse(key, wrapped.get(key))

  /** Batched read with buffer-shadows-wrapped semantics matching `get`: keys present in the in-memory buffer take their
    * buffered value (which may be `None` for a deleted key); the remaining keys are fetched from the wrapped store in a
    * SINGLE `multiGet` rather than N serial `get`s. Order and results are identical to `keys.map(get)`. (spec 002 US7 /
    * FR-022)
    */
  override def multiGet(keys: Seq[NodeHash]): Seq[Option[NodeEncoded]] =
    val missKeys = keys.filterNot(buffer.contains)
    val missResults: Map[NodeHash, Option[NodeEncoded]] =
      if missKeys.isEmpty then Map.empty
      else missKeys.zip(wrapped.multiGet(missKeys)).toMap
    keys.map(k => buffer.getOrElse(k, missResults.getOrElse(k, None)))

  /** This function updates the KeyValueStore by deleting, updating and inserting new (key-value) pairs.
    *
    * @param toRemove
    *   which includes all the keys to be removed from the KeyValueStore.
    * @param toUpsert
    *   which includes all the (key-value) pairs to be inserted into the KeyValueStore. If a key is already in the
    *   DataSource its value will be updated.
    * @return
    *   the new DataSource after the removals and insertions were done.
    */
  override def update(toRemove: Seq[NodeHash], toUpsert: Seq[(NodeHash, NodeEncoded)]): NodesKeyValueStorage =
    // Removals are ignored on purpose (#1607). Trie nodes are content-addressed: a node an update replaces can still be
    // referenced by an earlier root this session persisted, or be re-created later, and dropping it from the buffer
    // makes that root unreadable ("State node doesn't exist"). The removal never reached the DB anyway (`persist` only
    // forwards upserts, since nothing writes `None` into the buffer), so ignoring it is exact; memory stays bounded by
    // the request's lifetime.
    toUpsert.foreach { case (toUpsertKey, toUpsertValue) => buffer += (toUpsertKey -> Some(toUpsertValue)) }
    this

  override def persist(): Unit =
    val (toRemove, toUpsert) = changes
    wrapped.update(toRemove, toUpsert)
    buffer.clear()

object ReadOnlyNodeStorage:
  def apply(nodesKeyValueStorage: NodesKeyValueStorage): ReadOnlyNodeStorage = new ReadOnlyNodeStorage(
    nodesKeyValueStorage
  )
