package com.chipprbots.ethereum.blockchain.sync.snap.heal

import org.apache.pekko.util.ByteString

import scala.collection.mutable

/** Healed nodes committed under the Hash scheme and waiting for the asynchronous flush to the trie store (spec 022 D3,
  * #1549): `(hash, rlp)` in commit order, children before parents.
  *
  * A node enters only once its whole subtree has been committed (see [[HeldNodeSet]]), so any prefix of the buffer is
  * itself complete: a flush that writes the first `n` entries in one batch never puts a node on disk before its
  * children. Entries leave only once the write that holds them has returned ([[dropWritten]]); a write that throws
  * leaves the buffer as it was. [[contains]] lets child discovery count a buffered node as present: it is complete,
  * merely not on disk yet.
  *
  * Actor-confined: not thread-safe.
  */
final class FlushBuffer
    extends mutable.AbstractIterable[(ByteString, Array[Byte])]
    with mutable.Growable[(ByteString, Array[Byte])]:

  private val entries = mutable.ArrayDeque.empty[(ByteString, Array[Byte])]
  private val counts = mutable.HashMap.empty[ByteString, Int]
  private var heldBytes: Long = 0L

  override def addOne(elem: (ByteString, Array[Byte])): this.type =
    entries += elem
    counts(elem._1) = counts.getOrElse(elem._1, 0) + 1
    heldBytes += FlushBuffer.entryBytes(elem)
    this

  /** Whether a node of hash `hash` is buffered. */
  def contains(hash: ByteString): Boolean = counts.contains(hash)

  /** Forget the entries `written` holds, a snapshot of the buffer's first entries that a flush wrote: each is dropped
    * only while it is the very entry the snapshot took (`eq`, spec 022 review forge C1), never another that happens to
    * sit at its place. Returns how many were dropped; fewer than `written.size` means the buffer no longer starts with
    * the snapshot, and the caller must say so loudly (the rest stays, for the next flush).
    */
  def dropWritten(written: IndexedSeq[(ByteString, Array[Byte])]): Int =
    var i = 0
    while i < written.size && entries.nonEmpty && (entries.head eq written(i)) do
      val elem = entries.removeHead()
      counts.get(elem._1).foreach(c => if c <= 1 then counts.remove(elem._1) else counts(elem._1) = c - 1)
      heldBytes -= FlushBuffer.entryBytes(elem)
      i += 1
    if entries.isEmpty then heldBytes = 0L
    i

  /** The buffered entries in commit order, as a flush writes them: the very entry objects, for [[dropWritten]]. */
  def snapshot: IndexedSeq[(ByteString, Array[Byte])] = entries.toIndexedSeq

  /** Estimated heap of the buffered nodes, in bytes. */
  def bytes: Long = heldBytes

  override def clear(): Unit =
    entries.clear()
    counts.clear()
    heldBytes = 0L

  override def size: Int = entries.size
  override def knownSize: Int = entries.size
  override def isEmpty: Boolean = entries.isEmpty
  override def iterator: Iterator[(ByteString, Array[Byte])] = entries.iterator

object FlushBuffer:
  // The tuple, the hash ByteString and the array header, a deque slot and a map entry.
  private def entryBytes(elem: (ByteString, Array[Byte])): Long = 160L + elem._1.length + elem._2.length

/** One asynchronous flush of a [[FlushBuffer]] snapshot (spec 022 review forge C1, vault V1, hunter W4).
  *
  * '''A token unique in the process.''' `token` comes from one counter for every flush of every coordinator, so a
  * completion matches only the flush that sent it: not another flush of the same coordinator, and not a flush of a
  * coordinator instance that a supervisor restart replaced (the new instance keeps the ActorRef, so the old one's late
  * messages reach it).
  *
  * '''Superseded.''' A synchronous flush writes the whole buffer, this snapshot included. It marks the ticket
  * superseded, then takes the store's write lock; the asynchronous write checks the mark under the same lock. So either
  * the asynchronous write ran to its end first ([[written]]: its nodes are durable, and the synchronous flush releases
  * them instead of writing them again), or it never writes. Two writes never run at once, and none runs after the
  * synchronous flush has written what it would.
  */
final class FlushTicket private (val token: Long, val snapshot: IndexedSeq[(ByteString, Array[Byte])]):
  private val supersededFlag = new java.util.concurrent.atomic.AtomicBoolean(false)
  @volatile private var wroteAll: Boolean = false

  /** A synchronous flush took this snapshot over: the asynchronous write must not run. */
  def supersede(): Unit = supersededFlag.set(true)
  def superseded: Boolean = supersededFlag.get()

  /** The asynchronous write returned: the snapshot is durable, its records written, its frontier entries gone. */
  def markWritten(): Unit = wroteAll = true
  def written: Boolean = wroteAll

object FlushTicket:
  private val tokens = new java.util.concurrent.atomic.AtomicLong(0L)

  def apply(snapshot: IndexedSeq[(ByteString, Array[Byte])]): FlushTicket =
    new FlushTicket(tokens.incrementAndGet(), snapshot)
