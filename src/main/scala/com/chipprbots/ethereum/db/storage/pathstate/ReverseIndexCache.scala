package com.chipprbots.ethereum.db.storage.pathstate

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

import org.apache.pekko.util.ByteString

import scala.jdk.CollectionConverters.*

/** The reverse indexes that are kept for the next view of the same block (spec 018 data-model 6.4): by the anchor and
  * the ancestor they lie between, each with the epoch it was built at.
  *
  * '''Two kinds of caller keep indexes here, and one cannot push out the other's.''' An ''execution'' builds an index
  * to run a block on a state behind the anchor or on a fork: the blocks of one branch are each executed on a view of
  * the same fork point. A ''reader'' builds one to answer a peer or a JSON-RPC call about a block behind the head, and
  * a reader is anyone who can reach the node. So each kind keeps its own few, least recently used first to go; a
  * reader's index makes room only among readers', and an execution's takes a reader's place when it must. A sweep of
  * reads over many old blocks then costs the readers their own indexes and nothing else. All of them together hold no
  * more than `maxBytes`.
  *
  * An index is of the records as they were at one epoch, and is given only to a view of that epoch (see
  * [[PathStateStorage]]). The readers of every kind share the readers' few.
  *
  * @param maxKept
  *   how many indexes of each kind are kept
  * @param maxBytes
  *   how much all of them hold together, as [[ReverseIndex.bytes]] counts it; an index that is by itself more is not
  *   kept
  */
final private[pathstate] class ReverseIndexCache(maxKept: Int, maxBytes: Long):

  final private class Kept(val epoch: Long, val index: ReverseIndex, @volatile var reader: Boolean):
    @volatile var used: Long = 0L

  private val kept = new ConcurrentHashMap[(ByteString, ByteString), Kept]()
  private val uses = new AtomicLong(0L)

  /** The epoch of the newest index that was kept. */
  private var newest = Long.MinValue

  /** The index between `anchor` and `fork` that was built at `epoch`, when it is kept. An execution that finds an index
    * a reader built takes it over: it is an execution's from then on. That is done as a keep is, one at a time: an
    * index is not chosen to go as a reader's while an execution takes it.
    */
  def find(anchor: BlockRef, fork: BlockRef, epoch: Long, reader: Boolean): Option[ReverseIndex] =
    def found: Option[Kept] =
      Option(kept.get((anchor.hash, fork.hash)))
        .filter(entry => entry.epoch == epoch && entry.index.from == anchor && entry.index.to == fork)
    def used(entry: Kept): ReverseIndex =
      entry.used = uses.incrementAndGet()
      entry.index
    if reader then found.map(used)
    else
      synchronized {
        found.map { entry =>
          entry.reader = false
          used(entry)
        }
      }

  /** Keeps `index`, built at `epoch` by a reader or by an execution, if there is room for it or room can be made.
    *
    * No view is made of an epoch that is past, so an index of an earlier epoch than this one is of no more use, and
    * goes: one that was built across a move, or before a write that failed. And one that comes after a later one was
    * kept is not kept.
    */
  def keep(index: ReverseIndex, epoch: Long, reader: Boolean): Unit = synchronized {
    if index.bytes <= maxBytes && epoch >= newest then
      newest = epoch
      kept.values.removeIf(_.epoch < epoch)
      val key = (index.from.hash, index.to.hash)
      def ofKind: Int = kept.values.asScala.count(_.reader == reader)
      def fits: Boolean = ofKind < maxKept && bytes + index.bytes <= maxBytes
      // What may go for it, least recently used first. For a reader: a reader's. For an execution: one of its own
      // when it has as many as it may; otherwise a reader's, and its own only when no reader's is left.
      def next: Option[(ByteString, ByteString)] =
        val all = kept.entrySet.asScala.toSeq
        val own = all.filter(_.getValue.reader == reader)
        val readers = all.filter(_.getValue.reader)
        (if reader || ofKind >= maxKept || readers.isEmpty then own else readers)
          .minByOption(_.getValue.used)
          .map(_.getKey)
      var going = if fits then None else next
      while !fits && going.isDefined do
        kept.remove(going.get)
        going = if fits then None else next
      if fits then
        val entry = new Kept(epoch, index, reader)
        entry.used = uses.incrementAndGet()
        kept.put(key, entry)
  }

  def clear(): Unit = kept.clear()

  def size: Int = kept.size

  def bytes: Long = kept.values.asScala.iterator.map(_.index.bytes).sum
