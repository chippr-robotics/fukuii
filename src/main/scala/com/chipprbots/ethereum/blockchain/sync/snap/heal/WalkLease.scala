package com.chipprbots.ethereum.blockchain.sync.snap.heal

import java.util.concurrent.atomic.AtomicLong

import scala.collection.mutable

import com.chipprbots.ethereum.db.storage.BfsQueueStorage

/** The right to run a frontier walk on one BFS column family (spec 022 T038a; review hunter W1, forge W-r4-2, vault-r3
  * W1, beacon W1-r4).
  *
  * Every walk clears its queue when it starts and when it ends, deletes each level as it finishes it, and reads the end
  * of its next level from its queue's counter. A second walk on the same column family therefore cuts the first short,
  * and the cut walk reports the missing nodes it found so far, possibly none: a clean pass over a trie no walk covered.
  * A walk is a Future that outlives the coordinator incarnation that launched it, and the queue wrapper it runs on
  * belongs to one controller instance, while the column family is the process's. So the lease is keyed to the column
  * family ([[WalkLease.of]], by the queue's `walkScope`), not to the wrapper: every incarnation of every controller
  * over it gets the same lease.
  *
  * '''Cancelled, not waited out.''' A walk's incarnation ending (stopped, or restarted by its supervisor) cancels its
  * walk ([[cancel]]): nobody will read its result, and a mainnet walk can run for hours. The walk checks
  * [[isCancelled]] as it goes and holds the lease until it has stopped, so nothing can run beside it. [[release]] then
  * wakes the incarnations the lease refused meanwhile.
  *
  * '''A token unique in the process.''' Each walk that takes the lease gets a token from one counter (as a flush gets a
  * [[FlushTicket]]); its end carries the token, so an incarnation takes the end only of a walk it launched itself.
  *
  * Thread-safe: taken on a coordinator's actor thread, given back on the walk's thread.
  */
final class WalkLease private ():
  private var holder: Long = WalkLease.Free
  @volatile private var cancelled: Long = WalkLease.Free
  private val waiters = mutable.LinkedHashSet.empty[() => Unit]

  /** Take the lease for a new walk: its token, or `None` while another walk holds it. A refused caller's `onFree`, if
    * any, runs once when the walk holding the lease gives it back (see [[release]]).
    */
  def tryAcquire(onFree: Option[() => Unit] = None): Option[Long] =
    val token = WalkLease.tokens.incrementAndGet()
    synchronized {
      if holder == WalkLease.Free then
        holder = token
        Some(token)
      else
        onFree.foreach(waiters += _)
        None
    }

  /** Give the lease back: only the walk holding it, by its token, can (a stale token changes nothing). Wakes whoever it
    * refused meanwhile, on this thread, after the lease is free.
    */
  def release(token: Long): Unit =
    val woken = synchronized {
      if holder == token then
        holder = WalkLease.Free
        val refused = waiters.toList
        waiters.clear()
        refused
      else Nil
    }
    woken.foreach(_())

  /** Ask the walk `token` to stop: it checks [[isCancelled]] as it goes, and holds the lease until it has stopped. A
    * walk that no longer holds the lease is not affected.
    */
  def cancel(token: Long): Unit =
    synchronized(if holder == token then cancelled = token)

  def isCancelled(token: Long): Boolean = cancelled == token

  /** Whether a walk holds the lease. */
  def held: Boolean = synchronized(holder != WalkLease.Free)

object WalkLease:
  private val Free = 0L
  private val tokens = new AtomicLong(0L)
  // Keyed by the identity of the queue's data source (none overrides equals), then its namespace; weak, so a data source
  // that is gone takes its leases with it.
  private val leases = new java.util.WeakHashMap[AnyRef, mutable.Map[Seq[Byte], WalkLease]]()

  /** The lease of `queue`'s column family: one per data source and namespace, shared by every queue wrapper over it. */
  def of(queue: BfsQueueStorage): WalkLease =
    val (source, namespace) = queue.walkScope
    leases.synchronized {
      leases.computeIfAbsent(source, _ => mutable.Map.empty).getOrElseUpdate(namespace, new WalkLease())
    }

/** What a walk throws to stop when its lease says it is cancelled: not a failure, and it carries no stack. */
final class WalkCancelled extends RuntimeException("frontier walk cancelled") with scala.util.control.NoStackTrace
