package com.chipprbots.ethereum.blockchain.sync.snap.controller

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.blockchain.sync.snap.AccountTask
import com.chipprbots.ethereum.blockchain.sync.snap.ContractTaskFiles

/** Pure resume decisions (spec 016 M1). Re-exported by `object SNAPSyncController`. */
private[snap] object ResumePolicy:

  private[snap] def taskFilePaths(f: ContractTaskFiles): Set[String] = Set(f.storagePath, f.codeHashesPath)

  /** Whether cursors + contract task files can drive a mid-range resume: every range of this concurrency's layout has a
    * cursor (a range restarted from its start would duplicate carried contract work) and both task files hold at least
    * their counted entries.
    */
  private[snap] def checkResumable(
      cursors: Map[ByteString, ByteString],
      files: ContractTaskFiles,
      concurrency: Int
  ): Either[String, Unit] =
    val expected = AccountTask.createInitialTasks(ByteString.empty, concurrency).map(_.last).toSet
    if cursors.keySet != expected then
      Left(
        s"range layout mismatch: ${cursors.size} saved cursors vs ${expected.size} ranges at concurrency $concurrency"
      )
    else files.validate()

  /** Complete a saved cursor map to this concurrency's range layout (#1597), before [[checkResumable]].
    *
    * Builds before the #1597 fix dropped a range that was already complete when the coordinator resumed from every
    * later checkpoint (its snapshot listed pending, active, storing and completed ranges, and the skipped range was in
    * none of them). Every other range is always in one of those, so a layout key missing from such a record is a range
    * that was complete: it is filled as done (`last -> last`). Returned with the filled ranges so the caller can log
    * them.
    *
    * Rejected (`Left`), as a range layout mismatch:
    *   - no cursors at all (filling would mark the whole keyspace done);
    *   - a key outside this layout (another concurrency's ranges);
    *   - a keyset that is exactly another concurrency's layout. The layout for `k` ranges is a subset of the layout for
    *     `n` ranges when `k` divides `n` (the 8-range keys are among the 16-range keys), so without this an 8 -> 16
    *     concurrency change would mark half the keyspace done. A strict subset of size `k` can only equal layout `k`.
    */
  private[snap] def fillResumeLayout(
      cursors: Map[ByteString, ByteString],
      concurrency: Int
  ): Either[String, (Map[ByteString, ByteString], Seq[AccountTask])] =
    val layout = AccountTask.createInitialTasks(ByteString.empty, concurrency)
    val expected = layout.map(_.last).toSet
    val keys = cursors.keySet
    if keys == expected then Right((cursors, Seq.empty))
    else if keys.isEmpty then
      Left(s"range layout mismatch: no saved cursors vs ${expected.size} ranges at concurrency $concurrency")
    else if !keys.subsetOf(expected) then
      Left(
        s"range layout mismatch: ${keys.diff(expected).size} of ${keys.size} saved cursors are outside the " +
          s"${expected.size}-range layout at concurrency $concurrency"
      )
    else if AccountTask.createInitialTasks(ByteString.empty, keys.size).map(_.last).toSet == keys then
      Left(
        s"range layout mismatch: the ${keys.size} saved cursors are the ${keys.size}-range layout, not the " +
          s"${expected.size}-range layout at concurrency $concurrency"
      )
    else
      val missing = layout.filterNot(t => keys.contains(t.last))
      Right((cursors ++ missing.map(t => t.last -> t.last), missing))
