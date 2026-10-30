package com.chipprbots.ethereum.blockchain.sync.snap.heal

import java.util.concurrent.atomic.AtomicLong

import com.chipprbots.ethereum.db.storage.BfsQueueStorage

/** The right to run a frontier walk on one BFS queue (spec 022 review hunter W1, forge W-r4-2, vault-r3 W1, beacon
  * W1-r4).
  *
  * Every walk clears its queue when it starts and when it ends, deletes each level as it finishes it, and reads the end
  * of its next level from the queue's counter. A second walk on the same queue therefore cuts the first short, and the
  * cut walk reports the missing nodes it found so far, possibly none: a clean pass over a trie no walk covered. The
  * queue is one instance for every coordinator a controller spawns (its lazy `bfsQueueStorageOpt`), and a walk is a
  * Future that outlives the coordinator incarnation that launched it (a supervisor restart keeps the ActorRef and
  * mailbox; a replacement generation shares the queue). A coordinator's own flag cannot see a walk another incarnation
  * launched, so the lease lives with the queue: [[WalkLease.of]] gives every incarnation over one queue the same lease.
  *
  * '''A token unique in the process.''' Each walk that takes the lease gets a token from one counter (as a flush gets a
  * [[FlushTicket]]); its end carries the token, so an incarnation takes the end only of a walk it launched itself.
  *
  * Thread-safe: taken on a coordinator's actor thread, given back on the walk's thread.
  */
final class WalkLease private ():
  private val holder = new AtomicLong(WalkLease.Free)

  /** Take the lease for a new walk: its token, or `None` while another walk holds it. */
  def tryAcquire(): Option[Long] =
    val token = WalkLease.tokens.incrementAndGet()
    Option.when(holder.compareAndSet(WalkLease.Free, token))(token)

  /** Give the lease back: only the walk holding it, by its token, can (a stale token changes nothing). */
  def release(token: Long): Unit =
    holder.compareAndSet(token, WalkLease.Free)
    ()

  /** Whether a walk holds the lease. */
  def held: Boolean = holder.get() != WalkLease.Free

object WalkLease:
  private val Free = 0L
  private val tokens = new AtomicLong(0L)
  // Keyed by queue identity (neither queue implementation overrides equals); weak, so a queue that is gone takes its
  // lease with it.
  private val leases = new java.util.WeakHashMap[BfsQueueStorage, WalkLease]()

  /** The lease of `queue`: one per queue instance, shared by every coordinator incarnation built over it. */
  def of(queue: BfsQueueStorage): WalkLease =
    leases.synchronized(leases.computeIfAbsent(queue, _ => new WalkLease()))
