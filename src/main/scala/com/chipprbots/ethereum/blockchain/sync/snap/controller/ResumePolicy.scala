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
