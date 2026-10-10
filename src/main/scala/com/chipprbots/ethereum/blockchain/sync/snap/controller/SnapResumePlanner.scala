package com.chipprbots.ethereum.blockchain.sync.snap.controller

import org.apache.pekko.util.ByteString

import scala.concurrent.ExecutionContext

import com.chipprbots.ethereum.blockchain.sync.snap.*
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.*
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.db.storage.Namespaces
import com.chipprbots.ethereum.utils.Hex

/** SNAP resume planner (spec 016 M5, research.md R6 "Persistence / resume"): the storage-scheme guard run at start
  * (`checkStorageSchemeMismatch`), the writable trie store for the pivot (`getOrCreateMptStorage`), the storage-task
  * completion markers' clear (`clearStorageDoneMarkers`), the contract task-file bookkeeping
  * (`accountsCompleteTaskFilePaths`, `sweepSupersededTaskFiles`), the gated recovery reader (`runGatedReplay`, #1501)
  * and the legacy progress parser (`deserializeSnapProgress`). The core keeps the resume decisions that sit inside
  * `startSnapSync` and `launchAccountRangeWorkers` and the checkpoint arm of `syncing`; they call these members.
  */
private[snap] trait SnapResumePlanner:
  self: SNAPSyncControllerImpl =>

  /** Guard: fail fast if the DB was written with PathScheme but config says HashScheme (or vice versa).
    *
    * Path-scheme data lives in [[Namespaces.StateTriePathNamespace]] (namespace 't'). We check for the root node at
    * empty nibble path — HP([]) = 0x20. If it exists, a PathScheme SNAP sync was performed on this datadir. A
    * HashScheme config at that point would silently ignore all path-keyed nodes, so we throw immediately.
    *
    * The reverse (HashScheme data + PathScheme config) is safe: PathScheme simply ignores the existing hash-keyed nodes
    * and starts fresh. No silent data loss — just wasted disk space.
    */
  private[snap] def checkStorageSchemeMismatch(): Unit =
    val emptyHp = com.chipprbots.ethereum.mpt.HexPrefix.encode(Array.empty[Byte], isLeaf = false)
    val hasPathRoot = flatSlotStorage.dataSource
      .getOptimized(Namespaces.StateTriePathNamespace, emptyHp)
      .isDefined
    if hasPathRoot && snapSyncConfig.storageScheme == StorageScheme.Hash then
      throw new IllegalStateException(
        "Storage scheme mismatch: DB contains path-scheme account trie data (root at empty path) " +
          "but config has storage-scheme = hash. " +
          "Either set storage-scheme = path in snap-sync config, or wipe the datadir and resync."
      )

  def getOrCreateMptStorage(pivotBlockNumber: BigInt): MptStorage =
    mptStorage.getOrElse {
      val storage = stateStorage.getBackingStorage(pivotBlockNumber)
      mptStorage = Some(storage)
      ctx.log.info(s"Created writable MptStorage for pivot block $pivotBlockNumber")
      storage
    }

  /** Drop every completion marker (a cheap range tombstone, here), then compact the range off the actor thread so the
    * tombstoned space (~80 B per marker, ~750 MB on Sepolia) is reclaimed now. The compaction is blocking I/O of about
    * the markers' size; it runs on the single-thread snap-validation dispatcher, delaying a validation walk queued
    * behind it by that long at most. A failure only leaves the space to background compaction.
    */
  private[snap] def clearStorageDoneMarkers(reason: String): Unit =
    storageDoneStorage.clear()
    ctx.log.info(s"Cleared storage-task completion markers ($reason); compacting their key range in the background")
    scala.concurrent
      .Future(scala.concurrent.blocking(storageDoneStorage.compact()))(snapValidationEc)
      .failed
      .foreach(e => asyncLog.warn(s"Compacting cleared storage-task completion markers failed: ${e.getMessage}"))(
        snapValidationEc
      )

  /** Task files handed to accounts-complete recovery (never swept while referenced). */
  def accountsCompleteTaskFilePaths: Set[String] =
    (appStateStorage.getSnapSyncStorageFilePath().toSet ++ appStateStorage.getSnapSyncCodeHashesPath().toSet)
      .filter(_.nonEmpty)

  /** Delete contract task files in the task-file dir that are not in `keep`. IO errors are logged per file. */
  def sweepSupersededTaskFiles(keep: Set[String], reason: String): Unit =
    snapSyncConfig.taskFileDir.foreach { dir =>
      val deleted = SNAPSyncController.sweepTaskFiles(dir, keep, (p, e) => ctx.log.warn(s"Could not delete $p: $e"))
      if deleted.nonEmpty then ctx.log.info(s"Deleted ${deleted.size} superseded SNAP contract task file(s) ($reason)")
    }

  /** Drive a [[GatedTaskFileReplay]] to the end on Futures (spec 014). A paused step re-checks the gate after
    * `RecoveryReplayPausedRetry` via the scheduler, holding no thread while it waits; file reads run under `blocking`.
    * The pause is logged at most every 30 s with the gate's reason.
    */
  private[snap] def runGatedReplay(replay: GatedTaskFileReplay, what: String)(emit: Vector[Array[Byte]] => Unit)(using
      replayEc: ExecutionContext
  ): scala.concurrent.Future[Unit] =
    def loop(lastPauseLogMs: Long): scala.concurrent.Future[Unit] =
      scala.concurrent.Future(scala.concurrent.blocking(replay.step(emit))).flatMap {
        case GatedTaskFileReplay.Step.Done    => scala.concurrent.Future.unit
        case GatedTaskFileReplay.Step.Read(_) => loop(lastPauseLogMs)
        case GatedTaskFileReplay.Step.Paused(reason) =>
          val now = System.currentTimeMillis()
          val loggedAt =
            if now - lastPauseLogMs >= 30000L then
              asyncLog.info(s"Recovery: $what replay waiting at entry ${replay.position} ($reason)")
              now
            else lastPauseLogMs
          val resumed = scala.concurrent.Promise[Unit]()
          val _ = scheduler.scheduleOnce(RecoveryReplayPausedRetry) {
            resumed.completeWith(loop(loggedAt))
            ()
          }
          resumed.future
      }
    loop(0L)

  // --- SNAP progress persistence helpers ---

  /** Deserialize range progress from legacy AppStateStorage plain-text format (migration fallback). Yields
    * `(pivotBlock, rangeProgress)` or `None` if parsing fails.
    */
  private[snap] def deserializeSnapProgress(data: String): Option[(BigInt, Map[ByteString, ByteString])] =
    try
      val lines = data.split('\n').filter(_.nonEmpty)
      if lines.isEmpty then None
      else
        var pivot: Option[BigInt] = None
        val ranges = scala.collection.mutable.Map.empty[ByteString, ByteString]

        lines.foreach { line =>
          val idx = line.indexOf('=')
          if idx > 0 then
            val key = line.substring(0, idx)
            val value = line.substring(idx + 1)
            if key == "pivotBlock" then pivot = Some(BigInt(value))
            else ranges += (ByteString(Hex.decode(key)) -> ByteString(Hex.decode(value)))
        }

        pivot.map(p => (p, ranges.toMap))
    catch case _: Exception => None
