package com.chipprbots.ethereum.blockchain.sync.snap.heal

import org.apache.pekko.util.ByteString

import scala.collection.mutable
import scala.util.control.NonFatal

import com.chipprbots.ethereum.db.storage.ExcludedAccount
import com.chipprbots.ethereum.db.storage.SnapSyncProgressStorage

/** Spec 022 T033, the download coordinators' way into the durable excluded set.
  *
  * A coordinator's handler decides, here, that an account or a code hash is left incomplete. The decisions of one
  * message are written together when it ends, under one fsync (review vault F2: a handler used to make up to 128 of
  * them in a row, each holding the data source's lock). A write that fails keeps them here, logged, and the next
  * message tries again: it no longer escapes the handler and restarts the actor over work that nothing had decided
  * about (vault F3, hunter W4). The coordinator's completion waits for [[pending]] to clear, so the phase's clean end
  * cannot wipe the only other trace first.
  *
  * A crash between a decision and its write loses the decision with everything else in memory, and then the done marker
  * it would have replaced is absent as well: a resume downloads the account again.
  *
  * `enabled` is false for the recovery actors' coordinators: they run after SNAP has finished and nothing reads what
  * they would write (vault F4). `store` is read only when there is something to write.
  *
  * Memory: one entry per decision not yet written, and each replaces the task it decided about.
  *
  * Actor-confined: not thread-safe.
  */
final class ExcludedRecorder(
    store: => SnapSyncProgressStorage,
    enabled: Boolean,
    log: org.slf4j.Logger,
    nowMs: () => Long = () => System.currentTimeMillis()
):
  private val staged = mutable.LinkedHashMap.empty[ByteString, ExcludedAccount]
  private var failing: Boolean = false
  private var lastFailureMs: Long = 0L

  /** Keep `entries` for the next [[flush]]. An entry of the same place replaces an earlier one. */
  def stage(entries: Iterable[ExcludedAccount]): Unit =
    if enabled then entries.foreach(e => staged(e.cursor) = e)

  /** Write `entries` at once, on the calling thread, which need not be the actor's: nothing here is touched. */
  def recordNow(entries: Seq[ExcludedAccount]): Unit = store.recordExcluded(entries)

  /** Whether a decision has not been written yet. */
  def pending: Boolean = staged.nonEmpty

  /** [[flush]], as the end of every message calls it: after a failure, at most once a second, or a failing store would
    * be written to, under its exclusive lock, for every message of a busy coordinator (review vault N1).
    */
  def flushThrottled(): Boolean =
    if failing && nowMs() - lastFailureMs < ExcludedRecorder.RetryIntervalMs then staged.isEmpty else flush()

  /** Write what is staged, as one durable write. True when nothing is left unwritten. */
  def flush(): Boolean =
    if staged.isEmpty then true
    else
      try
        store.recordExcluded(staged.values.toSeq)
        if failing then
          log.info(s"[HEAL-EXCLUDED] Recorded ${staged.size} entries the excluded set could not take before")
        failing = false
        staged.clear()
        true
      catch
        case NonFatal(e) =>
          if !failing then log.error(s"[HEAL-EXCLUDED] Could not record ${staged.size} entries; retrying", e)
          failing = true
          lastFailureMs = nowMs()
          false

object ExcludedRecorder:
  private val RetryIntervalMs: Long = 1000L
