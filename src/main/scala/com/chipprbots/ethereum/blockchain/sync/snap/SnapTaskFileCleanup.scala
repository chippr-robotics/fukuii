package com.chipprbots.ethereum.blockchain.sync.snap

import com.chipprbots.ethereum.db.storage.AppStateStorage

/** The clean-up of a finished SNAP's task files (B-523). Its own object, outside the controller, so the controller's
  * storage-reference count (spec 016 ratchet) does not carry it.
  */
private[sync] object SnapTaskFileCleanup:

  /** Every task-file prefix, for the sweep of a finished sync. The contract-accounts file is added: its owner deletes
    * it on stop, but a crash leaves it, and once SNAP is done no coordinator holds a live one (#1601).
    */
  private val FinishedTaskFilePrefixes: Seq[String] =
    SNAPSyncController.SweepableTaskFilePrefixes :+ "fukuii-contract-accounts-"

  /** What a finished sync still holds on disk. Nothing re-reads the task files after `SnapSyncDone` (the recovery scans
    * read the stores), and on ETC mainnet the storage file alone is about 4.4 GB (B-523).
    *
    * Only once `SnapSyncDone` is durable: before it a crash still resumes from these files, so with the flag unset this
    * does nothing. Order: delete the files (idempotent: a missing file is a success), and only if every deletion
    * succeeded clear the AppState paths and counts in one batch. A kill between the two leaves paths that name missing
    * files, which nothing reads once done, and the next start runs this again. A failed deletion keeps the paths, so
    * that retry still knows where the file is. Returns true when the paths are clear on return.
    */
  def discardFinishedTaskFiles(
      appState: AppStateStorage,
      dir: Option[java.nio.file.Path],
      onError: (java.nio.file.Path, String) => Unit
  ): Boolean =
    if !appState.isSnapSyncDone() then false
    else
      val recorded =
        (appState.getSnapSyncStorageFilePath().toSeq ++ appState.getSnapSyncCodeHashesPath().toSeq)
          .filter(_.nonEmpty)
          .map(java.nio.file.Path.of(_))
      var failed = false
      recorded.foreach { p =>
        try java.nio.file.Files.deleteIfExists(p)
        catch
          case e: java.io.IOException =>
            failed = true
            onError(p, e.getMessage)
      }
      dir.foreach { d =>
        val kept = SNAPSyncController.sweepTaskFiles(
          d,
          Set.empty,
          (p, e) =>
            failed = true; onError(p, e)
          ,
          FinishedTaskFilePrefixes
        )
        val _ = kept
      }
      if failed then false
      else
        val hadAny = recorded.nonEmpty || appState.getSnapSyncStorageFileCount().isDefined ||
          appState.getSnapSyncCodeHashesCount().isDefined
        if hadAny then
          appState
            .putSnapSyncStorageFilePath("")
            .and(appState.putSnapSyncCodeHashesPath(""))
            .and(appState.putSnapSyncStorageFileCount(None))
            .and(appState.putSnapSyncCodeHashesCount(None))
            .commit()
        true
