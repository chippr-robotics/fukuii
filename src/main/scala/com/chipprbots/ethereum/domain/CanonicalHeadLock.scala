package com.chipprbots.ethereum.domain

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

import scala.concurrent.duration.*

import com.chipprbots.ethereum.utils.Logger

/** The lock of the head on the hash layout: go-ethereum's `chainmu`, which its `insertChain` holds while a branch is
  * executed and settled, and its `SetCanonical` (a fork choice) and `InsertBlockWithoutSetHead` (a payload) take too
  * (B-511).
  *
  * On the hash layout every writer of the best block or the index of blocks by number reads the index, or the best
  * block, and then writes: a branch the p2p import executes and settles (`ConsensusImpl`: its blocks write their own
  * entries as they are committed, the settle makes the index the head's ancestry), a batch it imports onto the head,
  * the fork choice (`BlockchainWriter.promoteToCanonicalHead`), `engine_newPayload`'s entry for a payload that extends
  * the index, the import's rewind of a fork (`BlockImporter.rewindCanonicalChain`), fast sync's discard of the blocks
  * above a common block, and an operator's strict chain import. Unserialised, a fork choice that moved the head while
  * one of the others ran was written over, and the best block named one chain while the index named another.
  *
  * Held by a branch's whole execution and settle, as `insertChain` holds `chainmu`; a fork choice or a payload's entry
  * waits for it, as in go-ethereum. A wait longer than [[WarnAfter]] is said at WARN, with what holds the lock and for
  * how long, and again once the lock is had: the Engine API's own timeout of a forkchoiceUpdated is 8 s. Reentrant.
  * Taken before [[com.chipprbots.ethereum.ledger.ChainInsertLock]], the per-block gate and the state's commit lock,
  * never inside any of them.
  *
  * Not taken on the unified layout, where the head moves with the state under the state's own lock.
  */
object CanonicalHeadLock extends Logger:

  /** How long a writer waits for the lock before the wait is said at WARN. */
  val WarnAfter: FiniteDuration = 2.seconds

  private val lock = new HeadLock(WarnAfter, message => log.warn(message))

  /** `body` with the lock held; `what` says who holds it, in the WARN of a writer that waits long for it. */
  def held[A](what: => String)(body: => A): A = lock.held(what)(body)

  /** Whether `thread` waits for the lock. For tests that show a writer is held back. */
  private[ethereum] def isWaiting(thread: Thread): Boolean = lock.isWaiting(thread)

  /** Whether any thread waits for the lock. For tests whose writer runs on a thread they do not hold. */
  private[ethereum] def hasWaiters: Boolean = lock.hasWaiters

  /** Whether this thread holds the lock. For tests that show a writer's whole step is under it. */
  private[ethereum] def isHeldByCurrentThread: Boolean = lock.isHeldByCurrentThread

/** [[CanonicalHeadLock]]'s lock, with the wait after which it says so (`warn`) given: a test gives a short one. */
final private[ethereum] class HeadLock(warnAfter: FiniteDuration, warn: String => Unit):
  private val lock = new ReentrantLock()

  // what holds the lock, and since when (System.nanoTime): set by the outermost hold only
  @volatile private var holder: Option[(String, Long)] = None

  def held[A](what: => String)(body: => A): A =
    val asked = System.nanoTime()
    if !lock.tryLock(warnAfter.toNanos, TimeUnit.NANOSECONDS) then
      val waiter = what
      val by = holder.fold("a writer that has let it go since") { (holding, since) =>
        s"$holding, for ${(System.nanoTime() - since) / 1000000} ms"
      }
      warn(
        s"[HEAD-LOCK] $waiter has waited ${(System.nanoTime() - asked) / 1000000} ms for the head's lock, held by " +
          s"$by; it waits on"
      )
      lock.lock()
      warn(s"[HEAD-LOCK] $waiter had the head's lock after ${(System.nanoTime() - asked) / 1000000} ms")
    val outermost = lock.getHoldCount == 1
    if outermost then holder = Some((what, System.nanoTime()))
    try body
    finally
      if outermost then holder = None
      lock.unlock()

  def isWaiting(thread: Thread): Boolean = lock.hasQueuedThread(thread)
  def hasWaiters: Boolean = lock.hasQueuedThreads
  def isHeldByCurrentThread: Boolean = lock.isHeldByCurrentThread
