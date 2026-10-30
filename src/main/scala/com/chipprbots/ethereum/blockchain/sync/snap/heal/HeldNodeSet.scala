package com.chipprbots.ethereum.blockchain.sync.snap.heal

import org.apache.pekko.util.ByteString

import scala.collection.mutable

/** The healed nodes that are not on disk yet because part of their subtree is missing (spec 022 FR-001 to FR-004, plan
  * D3, data-model.md "Held node").
  *
  * '''Invariant.''' A node healing writes reaches disk only together with or after every node of its subtree. A served
  * node with missing children is held here, never written. Each entry counts its outstanding child edges and lists the
  * parent edges it closes when committed. When an entry's count reaches 0 it is ready: it is committed, and so is every
  * ancestor whose last outstanding edge it closes, all in one atomic write ([[planCommit]], then [[applyCommit]] once
  * the write has returned). A parent's count goes down when a child's whole subtree is committed, never when the child
  * merely arrives (forge N5): a held child keeps its parent held.
  *
  * '''Identity.''' An entry is one node at one place: under the Hash scheme the node hash (its request key), under the
  * Path scheme its position key followed by its hash ([[idOf]]). Parent edges, in the request book and here, name the
  * parent by this id, so an edge from a node that has since been dropped (a position filled again with another node,
  * beacon N7) names no live entry and decrements nothing. [[heldAt]] finds the node held at a position.
  *
  * '''Two phases, nothing lost on a failed write (#1549).''' [[planCommit]] works out what to write without changing
  * anything; [[applyCommit]] releases the committed entries and applies the decrements, and is called only after the
  * write has returned. A write that throws leaves every entry, and the pending deletes, exactly as they were: the next
  * commit retries them.
  *
  * '''Pending deletes (Path, FR-008).''' Positions found stale while discovering children (a child position holding
  * another node's bytes, the positions inside an extension's key, a parentless node's own position) are deleted in the
  * next commit write, before any later write at those positions can land, as geth's `syncMemBatch` orders a `delNode`
  * before the node later written there. They are not deferred to the parent's commit: by then the child has been
  * written at its position, and the delete would remove it.
  *
  * '''Memory.''' [[bytes]] estimates the heap the entries use. The bound and the pause it drives are the caller's.
  *
  * Actor-confined: not thread-safe.
  */
final class HeldNodeSet:
  import HeldNodeSet.*

  private val entries = mutable.HashMap.empty[ByteString, Entry]
  private val byKey = mutable.HashMap.empty[ByteString, ByteString]
  // Ids of the entries whose count is 0 and that are not committed yet, in the order they became ready.
  private val ready = mutable.LinkedHashSet.empty[ByteString]
  private val deletes = mutable.ArrayBuffer.empty[(Option[ByteString], Array[Byte])]
  private var heldBytes: Long = 0L

  /** Held entries, ready ones included. */
  def size: Int = entries.size
  def isEmpty: Boolean = entries.isEmpty
  def nonEmpty: Boolean = entries.nonEmpty

  /** Estimated heap of the entries and the pending deletes, in bytes. */
  def bytes: Long = heldBytes

  /** Entries whose count is 0, waiting for the next commit write. */
  def readyCount: Int = ready.size

  /** Whether the next commit has anything to write: a ready entry or a pending delete. */
  def hasPendingWrites: Boolean = ready.nonEmpty || deletes.nonEmpty

  def get(id: ByteString): Option[Entry] = entries.get(id)

  def contains(id: ByteString): Boolean = entries.contains(id)

  /** The entry held at `request`'s key (a position under Path, the node under Hash), whatever node it is. */
  def heldAt(key: ByteString): Option[Entry] = byKey.get(key).flatMap(entries.get)

  /** Hold `request`'s node, `node`, waiting on `outstanding` child edges, closing `parents` when committed. With
    * `outstanding` 0 it is ready at once. Any node held at the same key is dropped first (see [[drop]]).
    */
  def hold(request: HealRequest, node: ByteString, outstanding: Int, parents: Seq[ByteString]): Entry =
    val id = idOf(request)
    byKey.get(request.key).foreach(drop)
    val entry = new Entry(id, request, node, outstanding, parents.toList)
    entries(id) = entry
    byKey(request.key) = id
    heldBytes += entry.estimatedBytes
    if outstanding <= 0 then ready += id
    entry

  /** Give the held `child` an edge from each of `parents` (a re-processed node's request was satisfied again, or a
    * parent found the child held). Returns how many edges were added.
    */
  def addParents(child: ByteString, parents: Seq[ByteString]): Int =
    entries.get(child) match
      case None => 0
      case Some(e) =>
        e.parents = e.parents ++ parents
        e.estimatedBytes += EdgeBytes * parents.size
        heldBytes += EdgeBytes * parents.size
        parents.size

  /** Give the held `child` edges from `parent` up to `multiplicity` (the times `parent` references it), counting those
    * it already has (forge N1). Returns how many were added.
    */
  def attachParent(child: ByteString, parent: ByteString, multiplicity: Int): Int =
    entries.get(child) match
      case None => 0
      case Some(e) =>
        val add = math.max(0, multiplicity - e.parents.count(_ == parent))
        if add > 0 then addParents(child, Seq.fill(add)(parent)) else 0

  /** Remember positions to delete in the next commit write (Path). */
  def addDeletes(positions: Seq[(Option[ByteString], Array[Byte])]): Unit =
    deletes ++= positions
    heldBytes += positions.iterator.map(p => DeleteBytes + p._2.length).sum

  /** What the next commit writes, worked out without changing anything: every ready entry and every ancestor whose last
    * outstanding edge they close, children before parents, and the pending deletes.
    */
  def planCommit(): CommitPlan =
    val committed = mutable.LinkedHashMap.empty[ByteString, Entry]
    val decrements = mutable.HashMap.empty[ByteString, Int]
    val queue = mutable.ArrayDeque.from(ready)
    while queue.nonEmpty do
      val id = queue.removeHead()
      if !committed.contains(id) then
        entries.get(id).foreach { e =>
          committed(id) = e
          e.parents.foreach { p =>
            entries.get(p).foreach { pe =>
              val d = decrements.getOrElse(p, 0) + 1
              decrements(p) = d
              if pe.outstanding - d == 0 then queue += p
            }
          }
        }
    CommitPlan(committed.values.toVector, decrements.toMap, deletes.toVector)

  /** The commit write of `plan` returned: release its entries, decrement the parents it leaves held, and forget the
    * deletes it carried.
    */
  def applyCommit(plan: CommitPlan): Unit =
    plan.nodes.foreach { e =>
      if entries.get(e.id).exists(_ eq e) then
        entries.remove(e.id)
        if byKey.get(e.request.key).contains(e.id) then byKey.remove(e.request.key)
        ready -= e.id
        heldBytes -= e.estimatedBytes
    }
    plan.decrements.foreach { case (p, d) =>
      entries.get(p).foreach { pe =>
        pe.outstanding -= d
        if pe.outstanding <= 0 then ready += p
      }
    }
    if plan.deletes.nonEmpty then
      deletes.remove(0, math.min(plan.deletes.size, deletes.size))
      heldBytes -= plan.deletes.iterator.map(p => DeleteBytes + p._2.length).sum
    if entries.isEmpty && deletes.isEmpty then heldBytes = 0L

  /** Drop the entry `id` without writing it (a position filled again with another node, beacon N7). Edges naming it
    * decrement nothing from now on; its own parents are left waiting.
    */
  def drop(id: ByteString): Unit =
    entries.remove(id).foreach { e =>
      if byKey.get(e.request.key).contains(id) then byKey.remove(e.request.key)
      ready -= id
      heldBytes -= e.estimatedBytes
    }

  /** The node hashes of the held entries, ready ones included (spec 022 review vault V5: the persisted frontier entries
    * of the entries a caller is about to drop).
    */
  def heldHashes: Seq[ByteString] = entries.valuesIterator.map(_.request.hash).toVector

  /** Drop everything, ready entries and pending deletes included: a re-peg, a force-complete or the orphan guard. The
    * pending deletes go too (beacon N1): they are positions stale for the old root, and a root change re-checks every
    * position it still needs against the new root (hash-checked presence), deleting again what is still stale there.
    */
  def clear(): Unit =
    entries.clear()
    byKey.clear()
    ready.clear()
    deletes.clear()
    heldBytes = 0L

object HeldNodeSet:

  /** The pause policy (spec 022 plan D1; review beacon W1, hunter W2): opening new subtrees pauses while something is
    * `held`, the held memory `bytes` reach `limit`, and something `canFree` it (a fetch below a held node queued, or a
    * fetch in flight). Without the last condition, held nodes that nothing can complete would keep new subtrees paused
    * forever: no fetch frees them, and the paused fetches keep the queue from draining.
    */
  def pausesNewSubtrees(held: Boolean, bytes: Long, limit: Long, canFree: Boolean): Boolean =
    held && bytes >= limit && canFree

  /** The id of `request`'s node: its key under the Hash scheme (the node hash), its position key followed by its hash
    * under the Path scheme.
    */
  def idOf(request: HealRequest): ByteString =
    if request.key == request.hash then request.key else request.key ++ request.hash

  /** One held node. `outstanding` and `parents` change as children commit and parents attach. */
  final class Entry private[HeldNodeSet] (
      val id: ByteString,
      val request: HealRequest,
      val node: ByteString,
      var outstanding: Int,
      var parents: List[ByteString]
  ):
    private[HeldNodeSet] var estimatedBytes: Long =
      EntryBytes + node.length + id.length + request.key.length + request.hash.length +
        request.pathset.iterator.map(_.length + 16L).sum + EdgeBytes * parents.size

  /** One commit write: `nodes` children first, the parent decrements it applies, and the deletes it carries. */
  final case class CommitPlan(
      nodes: Seq[Entry],
      decrements: Map[ByteString, Int],
      deletes: Seq[(Option[ByteString], Array[Byte])]
  ):
    def isEmpty: Boolean = nodes.isEmpty && deletes.isEmpty

  // Heap estimates (compressed oops): the Entry and its HealRequest, two map entries, the ready-set slot, and the
  // ByteString wrappers; a parent edge (a list cell); a pending delete (tuple, option, array header).
  private val EntryBytes: Long = 240L
  private val EdgeBytes: Long = 24L
  private val DeleteBytes: Long = 80L
