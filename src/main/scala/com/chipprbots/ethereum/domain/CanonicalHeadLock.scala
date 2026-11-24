package com.chipprbots.ethereum.domain

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock

import scala.concurrent.duration.*

import io.micrometer.core.instrument.Timer

import com.chipprbots.ethereum.db.storage.ReferenceCountedStateStorage
import com.chipprbots.ethereum.ledger.ChainInsertLock
import com.chipprbots.ethereum.metrics.Metrics
import com.chipprbots.ethereum.utils.Logger

/** The lock of the head on the hash layout: go-ethereum's `chainmu`, which its `insertChain` holds while a branch is
  * executed and settled, and its `SetCanonical` (a fork choice) and `InsertBlockWithoutSetHead` (a payload) take too
  * (B-511).
  *
  * On the hash layout a writer of the best block or the index of blocks by number reads the index, or the best block,
  * and then writes. Those that do so while the node follows the chain take this lock, the read and the write alike:
  *   - the p2p import (`ConsensusImpl`): a branch, executed and settled under one hold (its blocks write their own
  *     entries as they are committed, the settle makes the index the head's ancestry); a batch onto the head, a hold
  *     per [[ImportHoldBudget]] of blocks, each hold asking again that the batch's next block stands on the head;
  *   - the fork choice (`BlockchainWriter.promoteToCanonicalHead`), and `engine_newPayload`'s look at the index and
  *     write of a payload's entry, and its removal of the blocks above an invalid one;
  *   - the import's rewind of a fork (`BlockImporter.rewindCanonicalChain`) and fast sync's discard of the blocks above
  *     a common block (`FastSyncBranchResolver.discardBlocksAfter`), and a block's removal
  *     (`BlockchainImpl.removeBlock`);
  *   - an operator's chain import, strict and lenient, and the checkpoint import's last write;
  *   - SNAP's writes of the best block for its pivot (`PivotSelector`, `SnapFinalization`, `PivotAnchor.reanchor`): a
  *     consensus client's fork choice may come while SNAP runs.
  *
  * Unserialised, a fork choice that moved the head while one of the others ran was written over, and the best block
  * named one chain while the index named another. A writer that runs before any of these can (a start-up repair of the
  * index, `StdNode`; the header download of a sync, which writes entries below its pivot, where no head is followed
  * yet) does not take it.
  *
  * A branch holds it for its whole execution and settle, as `insertChain` holds `chainmu`; a fork choice or a payload's
  * entry waits for it, as in go-ethereum. A batch onto the head lets it go between its holds, so the wait there is at
  * most a hold of [[ImportHoldBudget]] and one block. The lock is fair: a writer that waits has it before a holder that
  * lets it go can take it again. Every wait is measured (`consensus.head.lock.wait.seconds`, a histogram), and one
  * longer than [[WarnAfter]] is said at WARN, with what holds the lock and for how long, and again once the lock is
  * had: the Engine API's own timeout of a forkchoiceUpdated is 8 s. Reentrant.
  *
  * Taken before [[com.chipprbots.ethereum.ledger.ChainInsertLock]], the per-block gate and the state's commit lock,
  * never inside any of them: a thread that holds one of them and asks for this lock first is a deadlock waiting for a
  * second thread, and is said as such at once, at ERROR, or thrown where `fukuii.head-lock.order-check` is `fail`, as
  * the tests run.
  *
  * On the unified layout the head moves with the state, under the state's own lock, and the import and the fork choice
  * do not take this one. The rewind, fast sync's discard and SNAP's writes take it on both layouts: there it is held by
  * nothing else, and costs nothing.
  */
object CanonicalHeadLock extends Logger:

  /** How long a writer waits for the lock before the wait is said at WARN. */
  val WarnAfter: FiniteDuration = 2.seconds

  /** How long a batch onto the head holds the lock at most before it lets it go between two of its blocks (B-511): a
    * fork choice that waits behind it waits this, and the execution of one block, not the whole batch.
    */
  val ImportHoldBudget: FiniteDuration = 1.second

  /** The system property that makes a wrong lock order throw (`fail`), as the tests set it; otherwise it is said. */
  val OrderCheckProperty: String = "fukuii.head-lock.order-check"

  private val lock =
    new HeadLock(
      WarnAfter,
      message => log.warn(message),
      WaitMetric.record,
      () => heldInsideTheHeadsLock,
      wrongOrder
    )

  /** `body` with the lock held; `what` says who holds it, in the WARN of a writer that waits long for it. */
  def held[A](what: => String)(body: => A): A = lock.held(what)(body)

  /** Whether `thread` waits for the lock. For tests that show a writer is held back. */
  private[ethereum] def isWaiting(thread: Thread): Boolean = lock.isWaiting(thread)

  /** Whether any thread waits for the lock. For tests whose writer runs on a thread they do not hold. */
  private[ethereum] def hasWaiters: Boolean = lock.hasWaiters

  /** Whether this thread holds the lock. For tests that show a writer's whole step is under it. */
  private[ethereum] def isHeldByCurrentThread: Boolean = lock.isHeldByCurrentThread

  /** Whether a writer that waits has the lock before one that asks for it later. For tests. */
  private[ethereum] def isFair: Boolean = lock.isFair

  /** The locks that are taken inside this one, that this thread holds: none, when it may ask for it. */
  private def heldInsideTheHeadsLock: Option[String] =
    val held =
      Option.when(ChainInsertLock.heldByCurrentThread)("the chain insert lock") ++
        Option.when(ReferenceCountedStateStorage.commitLockHeldByCurrentThread)("the state's commit lock")
    Option.when(held.nonEmpty)(held.mkString(" and "))

  private def wrongOrder(message: String): Unit =
    if System.getProperty(OrderCheckProperty, "log").equalsIgnoreCase("fail") then
      throw new IllegalStateException(message)
    else log.error(message, new IllegalStateException(message))

  /** `consensus.head.lock.wait.seconds`: how long each writer waited for the lock, as a histogram, on the registry in
    * use (made again when the registry is another, as `StateMetrics` does, B-425).
    */
  private object WaitMetric:
    private val current = new AtomicReference[(Metrics, Timer)](null)

    def record(nanos: Long): Unit = timer.record(nanos, TimeUnit.NANOSECONDS)

    private def timer: Timer =
      val metrics = Metrics.get()
      val held = current.get()
      if held != null && held._1.eq(metrics) then held._2
      else
        val made = Timer
          .builder(s"${metrics.metricsPrefix}.consensus.head.lock.wait.seconds")
          .description("How long a writer of the head waited for the head's lock (hash layout, B-511)")
          .publishPercentileHistogram()
          .register(metrics.registry)
        current.set((metrics, made))
        made

/** [[CanonicalHeadLock]]'s lock, with the wait after which it says so (`warn`) given: a test gives a short one.
  * `waited` is told how long each outermost hold waited, in nanoseconds; `heldInside` names the locks taken inside this
  * one that the asking thread holds, and `wrongOrder` is told when it holds any as it asks for this one first.
  */
final private[ethereum] class HeadLock(
    warnAfter: FiniteDuration,
    warn: String => Unit,
    waited: Long => Unit = _ => (),
    heldInside: () => Option[String] = () => None,
    wrongOrder: String => Unit = _ => ()
):
  // fair: a fork choice that waits has the lock before the batch that lets it go can take it again (B-511)
  private val lock = new ReentrantLock(true)

  // what holds the lock, and since when (System.nanoTime): set by the outermost hold only
  @volatile private var holder: Option[(String, Long)] = None

  def held[A](what: => String)(body: => A): A =
    if !lock.isHeldByCurrentThread then
      heldInside().foreach { inside =>
        wrongOrder(
          s"[HEAD-LOCK] $what asks for the head's lock while it holds $inside, which is taken inside it: a deadlock " +
            "with a writer that holds the head's lock and asks for that one"
        )
      }
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
    if outermost then
      waited(System.nanoTime() - asked)
      holder = Some((what, System.nanoTime()))
    try body
    finally
      if outermost then holder = None
      lock.unlock()

  def isWaiting(thread: Thread): Boolean = lock.hasQueuedThread(thread)
  def hasWaiters: Boolean = lock.hasQueuedThreads
  def isHeldByCurrentThread: Boolean = lock.isHeldByCurrentThread
  def isFair: Boolean = lock.isFair
