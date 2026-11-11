package com.chipprbots.ethereum.db.storage.pathstate

import java.util.concurrent.locks.ReentrantLock

/** The one lock of the in-place writers of the state (spec 018 data-model 7.0).
  *
  * The rows of the state hold absolute values, for one block. Two batches built at the same time from the same anchor
  * would each write its own block's rows over the other's. So every batch that writes those rows in place is built and
  * written under this lock, from the check of the anchor to the moment the clock is at rest again: the canonical
  * commit, and later a head move, a repair, the genesis commit, SNAP's finalization and a checkpoint import's last
  * batch. Execution does not hold it. A commit does, for one batch.
  *
  * '''It is the innermost lock.''' The import locks are taken outside it (`ImportLock`, then `ChainInsertLock`, then
  * `BlockImportGate`, which a commit already holds when it gets here), and nothing that runs under it may take one of
  * them, ask an actor, or wait for anything but the data source. The data source's own lock is taken inside it, by the
  * write.
  *
  * '''It is not reentrant.''' An in-place write that starts inside another is a defect, not something to wait for, and
  * it is refused where a reentrant lock would let it through.
  *
  * Fair: writers are served in the order they asked, so a stream of commits cannot starve a head move.
  *
  * The lock is also the only thing that makes a [[StateLockHeld]]: what must not be built outside it asks for one.
  */
final class StateLock:

  private val lock = new ReentrantLock(true)
  @volatile private var held: Option[String] = None

  /** `body` with the lock held, as `holder` (a name for the log and the wait metric: "canonical commit of block N").
    *
    * @param onWait
    *   told how long this caller waited for the lock, in nanoseconds, once it has it
    * @throws IllegalStateException
    *   when the calling thread already holds the lock
    */
  def locked[A](holder: String, onWait: Long => Unit = _ => ())(body: StateLockHeld ?=> A): A =
    if lock.isHeldByCurrentThread then
      throw new IllegalStateException(
        s"The state lock is asked for by its own holder: '$holder' inside '${held.getOrElse("?")}'. " +
          "An in-place write of the state cannot start inside another."
      )
    val asked = System.nanoTime()
    lock.lock()
    try
      held = Some(holder)
      onWait(System.nanoTime() - asked)
      body(using new StateLockHeld())
    finally
      held = None
      lock.unlock()

  /** Who holds the lock right now, if anyone: for a log line or a metric, not for a decision. */
  def holder: Option[String] = held

  /** How many writers are waiting for the lock right now: an estimate, for a metric or a test that must know someone is
    * waiting.
    */
  def waiting: Int = lock.getQueueLength

  /** Whether the calling thread holds the lock: for an assertion that something runs under it, or does not. */
  def isHeldByCurrentThread: Boolean = lock.isHeldByCurrentThread
