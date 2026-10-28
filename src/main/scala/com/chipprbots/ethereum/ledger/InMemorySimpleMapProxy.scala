package com.chipprbots.ethereum.ledger

import com.chipprbots.ethereum.common.SimpleMap

object InMemorySimpleMapProxy:
  def wrap[K, V, I <: SimpleMap[K, V, I]](inner: I): InMemorySimpleMapProxy[K, V, I] =
    wrap(inner, new TrieReadMemo[K, V](ReadMemoBudget.fromConfig()))

  /** Wraps `inner`, memoising its reads in `memo`, which must belong to this very trie (this root). */
  def wrap[K, V, I <: SimpleMap[K, V, I]](inner: I, memo: TrieReadMemo[K, V]): InMemorySimpleMapProxy[K, V, I] =
    new InMemorySimpleMapProxy(inner, Map.empty[K, Option[V]], memo)

/** This class keeps holds changes made to the inner [[com.chipprbots.ethereum.common.SimpleMap]] until data is commited
  *
  * @param inner
  *   [[com.chipprbots.ethereum.common.SimpleMap]] to proxy
  * @param cache
  *   InMemory map where data is going to be cached
  * @tparam K
  *   data type of the key to be used within this Proxy
  * @tparam V
  *   data type of the value to be used within this Proxy
  */
class InMemorySimpleMapProxy[K, V, I <: SimpleMap[K, V, I]] private (
    val inner: I,
    val cache: Map[K, Option[V]],
    memo: TrieReadMemo[K, V]
) extends SimpleMap[K, V, InMemorySimpleMapProxy[K, V, I]]:

  type Changes = (Seq[K], Seq[(K, V)])

  /** The keys to remove and the entries to upsert, each in `cache.iterator` order: the order `persist` applies them to
    * the inner map in. One pass over the same iterator the `foldLeft` used before #1675 walked, appending to two
    * builders, so both sequences hold the same elements in the same order as before; only the collection changed. The
    * fold appended with `:+` onto `Seq.empty` (a `List`), copying its accumulator on every append: O(dirty²) per
    * persist, which runs once per touched storage trie plus the account trie.
    */
  def changes: Changes =
    val toRemove = Vector.newBuilder[K]
    val toUpsert = Vector.newBuilder[(K, V)]
    val it = cache.iterator
    while it.hasNext do
      it.next() match
        case (key, Some(value)) => toUpsert += key -> value
        case (key, None)        => toRemove += key
    (toRemove.result(), toUpsert.result())

  /** Persists the changes into the underlying [[com.chipprbots.ethereum.common.SimpleMap]]
    *
    * @return
    *   Updated proxy
    */
  def persist(): InMemorySimpleMapProxy[K, V, I] =
    val changesToApply = changes
    // A new trie, a new root: nothing remembered about the old one applies. (The memo is a budget-sharing sibling.)
    new InMemorySimpleMapProxy[K, V, I](
      inner.update(changesToApply._1, changesToApply._2),
      Map.empty,
      memo.successor
    )

  /** Clears the cache without applying the changes
    *
    * @return
    *   Updated proxy
    */
  def rollback: InMemorySimpleMapProxy[K, V, I] = new InMemorySimpleMapProxy[K, V, I](inner, Map.empty, memo)

  /** This function obtains the value asociated with the key passed, if there exists one.
    *
    * @param key
    * @return
    *   Option object with value if there exists one.
    */
  def get(key: K): Option[V] = cache.getOrElse(key, memo.getOrLoad(key)(inner.get(key)))

  def wrapped: I = inner

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
  override def update(toRemove: Seq[K], toUpsert: Seq[(K, V)]): InMemorySimpleMapProxy[K, V, I] =
    val afterRemoval = toRemove.foldLeft(cache)((updated, key) => updated + (key -> None))
    val afterInserts = toUpsert.foldLeft(afterRemoval) { (updated, toUpsert) =>
      updated + (toUpsert._1 -> Some(toUpsert._2))
    }
    new InMemorySimpleMapProxy[K, V, I](inner, afterInserts, memo)
