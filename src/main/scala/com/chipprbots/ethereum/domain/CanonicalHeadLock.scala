package com.chipprbots.ethereum.domain

import java.util.concurrent.locks.ReentrantLock

/** The lock of the head on the hash layout: go-ethereum's `chainmu`, which its `insertChain` holds while a branch is
  * executed and settled, and its `SetCanonical` (a fork choice) and `InsertBlockWithoutSetHead` (a payload) take too
  * (B-511).
  *
  * On the hash layout three writers change the best block or the index of blocks by number, each outside the other two:
  * a branch that leaves the head's chain, executed and then settled by `ConsensusImpl` (its blocks write their own
  * entries as they are committed, and the settle makes the index the head's ancestry), the fork choice
  * (`BlockchainWriter.promoteToCanonicalHead`), and `engine_newPayload`, which writes the entry of a payload that
  * extends the index. Each reads the index and then writes it. Unserialised, a fork choice that moved the head while a
  * branch was executed had its index put back over by the settle, which then named one chain while the best block named
  * another, and a payload's entry could land over a block of the branch.
  *
  * Held by the branch's whole execution and settle, as `insertChain` holds `chainmu`; a fork choice or a payload's
  * entry waits for it, as in go-ethereum. Reentrant. Taken before [[com.chipprbots.ethereum.ledger.ChainInsertLock]],
  * the per-block gate and the state's commit lock, never inside any of them.
  *
  * Not taken on the unified layout, where the head moves with the state under the state's own lock.
  */
object CanonicalHeadLock:
  private val lock = new ReentrantLock()

  def held[A](body: => A): A =
    lock.lock()
    try body
    finally lock.unlock()

  /** Whether `thread` waits for the lock. For tests that show a writer is held back. */
  private[ethereum] def isWaiting(thread: Thread): Boolean = lock.hasQueuedThread(thread)

  /** Whether any thread waits for the lock. For tests whose writer runs on a thread they do not hold. */
  private[ethereum] def hasWaiters: Boolean = lock.hasQueuedThreads
