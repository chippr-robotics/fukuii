package com.chipprbots.ethereum.blockchain.sync.snap.heal

import org.apache.pekko.util.ByteString

import scala.collection.mutable

/** The queued heal fetches, dispatched deepest first (spec 022 plan D1; geth `trie/sync.go`, priority `len(path)`).
  *
  * A node is held until its whole subtree is present (D3), so the order fetches go out in decides how much is held.
  * Breadth first, almost every internal node stays held until the bottom level drains, and the held set approaches the
  * size of the trie. Deepest first, a subtree is finished before the next one is opened: the held set stays about one
  * root-to-leaf path wide per fetch in flight.
  *
  * '''Order.''' The deepest fetch first; among fetches of the same depth, the one queued first. A fetch put back (a
  * timeout, a peer gone, a response that omitted it) is queued anew, behind the fetches of its depth already queued.
  *
  * '''The pause policy (D1).''' Each fetch is classed when it is queued: either it frees held memory (a request waiting
  * on it has a held parent) or it opens a new subtree (a root, a walk or controller entry). [[dequeue]] with `paused`
  * true takes only the first kind: at the held-memory cap no new subtree is opened, while the fetches that let held
  * nodes commit keep going. That is what keeps the cap from deadlocking: the fetches that free memory never wait. A
  * fetch can gain a held parent after it was queued (a parent discovers a child whose fetch is already queued);
  * [[promote]] reclasses it then.
  *
  * Every operation is O(log n), except [[iterator]] (O(n log n): it sorts, for the persisted-frontier clear and tests).
  * Actor-confined: not thread-safe.
  *
  * @param depthOf
  *   a fetch's depth: nibbles from the state root (see [[HealRequest.depth]])
  * @param hashOf
  *   the node hash a fetch is for: the queue holds at most one fetch per hash (the caller's invariant, see
  *   [[HealRequestBook]]); [[promote]] finds a fetch by it
  * @param freesHeld
  *   whether a fetch, when queued, frees held memory (see above)
  */
final class HealTaskQueue[A](depthOf: A => Int, hashOf: A => ByteString, freesHeld: A => Boolean)
    extends mutable.AbstractIterable[A]
    with mutable.Growable[A]:
  import HealTaskQueue.Slot

  private var nextSeq: Long = 0L
  private val freeing = mutable.PriorityQueue.empty[Slot[A]](using HealTaskQueue.dispatchOrder[A])
  private val opening = mutable.PriorityQueue.empty[Slot[A]](using HealTaskQueue.dispatchOrder[A])
  // The slots queued in `opening`, by hash, for `promote`. A promoted slot moves to `freeing` and stays in `opening`'s
  // heap as a tombstone (its `seq` in `promoted`) until it reaches the top, where it is dropped (lazy deletion).
  private val openingByHash = mutable.HashMap.empty[ByteString, List[Slot[A]]]
  private val promoted = mutable.HashSet.empty[Long]

  override def addOne(elem: A): this.type =
    val slot = Slot(depthOf(elem), nextSeq, elem)
    nextSeq += 1
    if freesHeld(elem) then freeing.enqueue(slot)
    else
      opening.enqueue(slot)
      val hash = hashOf(elem)
      openingByHash(hash) = slot :: openingByHash.getOrElse(hash, Nil)
    this

  /** The queued fetch of `hash`, if it waits as one that opens a new subtree, now frees held memory: move it so a
    * paused dispatch takes it. It keeps its place in the order.
    */
  def promote(hash: ByteString): Unit =
    openingByHash
      .remove(hash)
      .foreach(_.foreach { slot =>
        promoted += slot.seq
        freeing.enqueue(slot)
      })

  /** Remove and return up to `max` fetches, deepest first. With `paused`, only fetches that free held memory. */
  def dequeue(max: Int, paused: Boolean): Seq[A] =
    val out = Vector.newBuilder[A]
    var taken = 0
    var more = true
    while taken < max && more do
      dropPromotedOpening()
      val fromFreeing =
        if freeing.isEmpty then false
        else if paused || opening.isEmpty then true
        else HealTaskQueue.dispatchOrder[A].gteq(freeing.head, opening.head)
      if fromFreeing then
        out += freeing.dequeue().entry
        taken += 1
      else if !paused && opening.nonEmpty then
        val slot = opening.dequeue()
        val hash = hashOf(slot.entry)
        openingByHash.get(hash).foreach { slots =>
          val rest = slots.filterNot(_ eq slot)
          if rest.isEmpty then openingByHash.remove(hash) else openingByHash(hash) = rest
        }
        out += slot.entry
        taken += 1
      else more = false
    out.result()

  /** Whether `dequeue(_, paused)` would return anything. */
  def hasDispatchable(paused: Boolean): Boolean = freeing.nonEmpty || (!paused && openingCount > 0)

  /** Queued fetches that open a new subtree (paused at the held-memory cap). */
  def openingCount: Int = opening.size - promoted.size

  override def clear(): Unit =
    freeing.clear()
    opening.clear()
    openingByHash.clear()
    promoted.clear()

  override def size: Int = freeing.size + openingCount
  override def knownSize: Int = size
  override def isEmpty: Boolean = size == 0

  /** Every queued fetch, in dispatch order (unpaused). */
  override def iterator: Iterator[A] =
    (freeing.iterator ++ opening.iterator.filterNot(s => promoted.contains(s.seq))).toVector
      .sorted(using HealTaskQueue.dispatchOrder[A].reverse)
      .iterator
      .map(_.entry)

  private def dropPromotedOpening(): Unit =
    while opening.nonEmpty && promoted.remove(opening.head.seq) do opening.dequeue()

object HealTaskQueue:

  final private case class Slot[A](depth: Int, seq: Long, entry: A)

  // The heap's maximum is the next to dispatch: the deepest, then the one queued first.
  private def dispatchOrder[A]: Ordering[Slot[A]] =
    Ordering.by[Slot[A], (Int, Long)](s => (s.depth, -s.seq))
