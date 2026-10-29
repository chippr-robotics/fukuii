package com.chipprbots.ethereum.transactions

import org.apache.pekko.util.ByteString

import scala.collection.mutable

import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.Transaction

/** The pool's transactions by sender, for the pool-state rules ([[TxAdmission.poolRejection]]) while one batch of peer
  * transactions is admitted (#1675).
  *
  * The rules read, for each transaction admitted, the sender's pooled transactions (and, for a set-code transaction,
  * each authority's). Collecting those by copying and scanning the whole pool for every transaction made one batch
  * O(batch x pool). This index is built from the pool at most once per batch, when the first transaction reaches the
  * rules ([[PoolSenderIndex.Deferred]]), and stays exact while the batch is admitted:
  *   - a transaction the batch puts in the pool is [[add]]ed, so a sender's later transactions in the batch see it;
  *   - a lookup skips every indexed transaction that `isPooled` no longer reports (a later put size-evicted it, its
  *     sidecar was dropped, or it expired), so nothing that has left the pool is read.
  *
  * Plain mutable state, owned by the pool actor for the duration of one admission.
  */
final private[transactions] class PoolSenderIndex(isPooled: ByteString => Boolean):
  private val bySender = mutable.HashMap.empty[Address, mutable.ArrayBuffer[(ByteString, Transaction)]]
  private val indexed = mutable.HashSet.empty[ByteString]

  /** Index `tx` (hash `hash`) under `sender`. A hash already indexed is ignored: the pool holds one entry per hash. */
  def add(sender: Address, hash: ByteString, tx: Transaction): Unit =
    if indexed.add(hash) then bySender.getOrElseUpdate(sender, mutable.ArrayBuffer.empty) += (hash -> tx)

  /** `sender`'s transactions still in the pool, in the order indexed, leaving out `excluding` (the transaction being
    * admitted). O(the sender's indexed transactions).
    */
  def pooledFrom(sender: Address, excluding: Option[ByteString] = None): Seq[Transaction] =
    bySender.get(sender) match
      case None => Vector.empty
      case Some(entries) =>
        entries.iterator.collect { case (hash, tx) if !excluding.contains(hash) && isPooled(hash) => tx }.toVector

object PoolSenderIndex:

  /** An index of `pooled` (sender, hash, transaction), read in one pass. */
  def apply(
      pooled: IterableOnce[(Address, ByteString, Transaction)],
      isPooled: ByteString => Boolean
  ): PoolSenderIndex =
    val index = new PoolSenderIndex(isPooled)
    pooled.iterator.foreach { case (sender, hash, tx) => index.add(sender, hash, tx) }
    index

  /** A [[PoolSenderIndex]] built on first use, so a batch in which no transaction reaches the pool-state rules (the
    * head's state was not read for its sender) does not pay the O(pool) build.
    *
    * Built late, the index reads the pool as it is then, which already holds every transaction the batch has put; so a
    * put needs [[PoolSenderIndex.add]] only once the index is [[built]].
    */
  final private[transactions] class Deferred(build: () => PoolSenderIndex):
    private var index: Option[PoolSenderIndex] = None

    /** The index, built on the first call. */
    def get: PoolSenderIndex = index.getOrElse {
      val fresh = build()
      index = Some(fresh)
      fresh
    }

    /** The index, if [[get]] has built it. */
    def built: Option[PoolSenderIndex] = index
