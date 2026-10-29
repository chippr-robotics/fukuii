package com.chipprbots.ethereum.blockchain.sync.snap.heal

import org.apache.pekko.util.ByteString

import scala.collection.mutable

/** Healed nodes committed under the Hash scheme and waiting for the asynchronous flush to the trie store (spec 022 D3,
  * #1549): `(hash, rlp)` in commit order, children before parents.
  *
  * A node enters only once its whole subtree has been committed (see [[HeldNodeSet]]), so any prefix of the buffer is
  * itself complete: a flush that writes the first `n` entries in one batch never puts a node on disk before its
  * children. Entries leave only once the write that holds them has returned ([[dropFirst]]); a write that throws leaves
  * the buffer as it was. [[contains]] lets child discovery count a buffered node as present: it is complete, merely not
  * on disk yet.
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

  /** Forget the first `n` entries: a flush that wrote them returned. */
  def dropFirst(n: Int): Unit =
    var i = 0
    while i < n && entries.nonEmpty do
      val elem = entries.removeHead()
      counts.get(elem._1).foreach(c => if c <= 1 then counts.remove(elem._1) else counts(elem._1) = c - 1)
      heldBytes -= FlushBuffer.entryBytes(elem)
      i += 1
    if entries.isEmpty then heldBytes = 0L

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
