package com.chipprbots.ethereum.blockchain.sync.snap.controller

import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.Behaviors

import scala.concurrent.duration.*

import com.chipprbots.ethereum.blockchain.sync.snap.*
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.*
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.SyncPhase.*

/** SNAP download stagnation watchdog (spec 016 M7, research.md R6 "Download-phase supervision + stagnation"): the 30 s
  * check timer (`scheduleStagnationChecks`), the answers to its progress asks, which are the whole `AccountRangeSync`
  * and `ByteCodeAndStorageSync` phase functions (`accountRangeArms`, `byteCodeAndStorageArms`), and the three phase
  * checks they call: account stall -> in-place pivot refresh, then dormant mode (`maybeRestartIfAccountStagnant`);
  * storage stall or tail livelock -> one pivot refresh, then force-complete (`maybeRestartIfStorageStagnant`); bytecode
  * stall -> force-complete (`maybeForceCompleteIfBytecodeStagnant`). The core keeps the dispatch (`phaseArms`), the
  * `CheckDownloadStagnation` arm of `commonSyncingArms` that sends the asks, and the clock resets at launch, refresh
  * and `startSnapSync`.
  */
private[snap] trait StagnationWatchdog:
  self: SNAPSyncControllerImpl =>

  /** `syncing` arms guarded on `currentPhase == AccountRangeSync` (P4a). */
  private[snap] lazy val accountRangeArms: PartialFunction[Command, Behavior[Command]] = {
    case AccountCoordinatorProgress(progress) =>
      if progress.elapsedTimeMs > 0 || progress.tasksPending > 0 || progress.tasksActive > 0 || progress.tasksCompleted > 0
      then maybeRestartIfAccountStagnant(progress)
      Behaviors.same
  }

  /** `syncing` arms guarded on `currentPhase == ByteCodeAndStorageSync` (P4b). */
  private[snap] lazy val byteCodeAndStorageArms: PartialFunction[Command, Behavior[Command]] = {
    case StorageCoordinatorProgress(stats) =>
      ctx.log.info(
        s"Storage stagnation check: pending=${stats.tasksPending}, active=${stats.tasksActive}, " +
          s"completed=${stats.tasksCompleted}, stalledMs=${System.currentTimeMillis() - lastStorageProgressMs}, " +
          s"refreshAttempted=$storageStagnationRefreshAttempted"
      )
      maybeRestartIfStorageStagnant(stats)
      Behaviors.same

    case ByteCodeCoordinatorProgress(progress) =>
      ctx.log.info(
        s"ByteCode stagnation check: downloaded=${progress.bytecodesDownloaded}, " +
          s"stalledMs=${System.currentTimeMillis() - lastBytecodeProgressMs}"
      )
      maybeForceCompleteIfBytecodeStagnant(progress)
      Behaviors.same
  }

  private[snap] def scheduleStagnationChecks(): Unit =
    val interval = DownloadStagnationCheckInterval
    timers.startTimerWithFixedDelay(CheckDownloadStagnation, CheckDownloadStagnation, interval)

  /** Handle stagnation for storage phase.
    *
    * When storage has no progress for StorageStagnationThreshold:
    *   - First stall: attempt pivot refresh (cheaper recovery)
    *   - Second stall (30s later, not 20min): force-complete to healing
    *
    * The second check uses a short window because the pivot refresh either works immediately (peers serve new root) or
    * it doesn't (all peers stateless again). Waiting another 20 minutes just delays the inevitable force-complete.
    */
  private def maybeRestartIfStorageStagnant(stats: actors.StorageRangeCoordinator.SyncStatistics): Unit =
    if currentPhase == ByteCodeAndStorageSync then
      // If coordinator responded with real stats, check if work remains.
      // If all stats are zero (ask timeout), assume work IS remaining since
      // we're still in ByteCodeAndStorageSync phase (would have transitioned if truly complete).
      val isTimeoutResponse =
        stats.tasksPending == 0 && stats.tasksActive == 0 && stats.tasksCompleted == 0 && stats.elapsedTimeMs == 0
      // Bug 1 fix: coordinator mailbox was backed up processing a batch — ask timed out and returned
      // all-zeros. This is liveness, not stagnation. Reset the clock and skip this tick so the
      // stagnation timer doesn't advance while the coordinator is actively working.
      if isTimeoutResponse then lastStorageProgressMs = System.currentTimeMillis()
      else
        val workRemaining = stats.tasksPending > 0 || stats.tasksActive > 0

        // Special case: coordinator reports 0 pending + 0 active but never sent StorageRangeSyncComplete.
        // This means trie construction is stuck (accountsInTrieConstruction/pendingAccountSlots not empty),
        // most likely because the coordinator actor restarted and lost its in-memory task state. Unlike
        // the `workRemaining` branch below (where waiting first for a pivot refresh is worthwhile), this
        // reading is unambiguous on its own — use the much shorter StorageRestartedEmptyThreshold so the
        // existing force-complete/healing recovery fires in ~1 minute instead of ~10.
        if !workRemaining && !storagePhaseComplete then
          val now = System.currentTimeMillis()
          val stalledForMs = now - lastStorageProgressMs
          if stalledForMs > StorageRestartedEmptyThreshold.toMillis then
            ctx.log.warn(
              s"Storage coordinator reports 0 pending/0 active but never sent StorageRangeSyncComplete " +
                s"(stalled ${stalledForMs / 1000}s). Trie construction likely stuck. Force-completing."
            )
            timers.cancel(RequestStorageRanges)
            if !forceCompleteStorageSent then
              forceCompleteStorageSent = true
              storageRangeCoordinator.foreach(_ ! actors.StorageRangeCoordinator.ForceCompleteStorage)
        else if workRemaining then
          val now = System.currentTimeMillis()
          val stalledForMs = now - lastStorageProgressMs

          // Tail-livelock signal, independent of the slot-count-based one above: remaining work (pending+active)
          // has not set a new low point for a full StorageStagnationThreshold AND the coordinator kept recording
          // stale-local-root verification failures over that window (see evaluateStorageTail).
          val (nextBaseline, progressStalled) = SNAPSyncController.evaluateStorageTail(
            storageTailBaseline,
            remainingWork = stats.tasksPending + stats.tasksActive,
            staleRootFailureEvents = stats.staleRootFailureEvents,
            nowMs = now,
            thresholdMs = StorageStagnationThreshold.toMillis
          )
          storageTailBaseline = nextBaseline
          val remainingWorkStalledForMs = now - storageTailBaseline.sinceMs

          if !storageStagnationRefreshAttempted then
            // First stall: needs full threshold before triggering, on EITHER signal.
            if (stalledForMs >= StorageStagnationThreshold.toMillis || progressStalled) &&
              now - lastPivotRestartMs >= MinPivotRestartInterval.toMillis
            then
              lastPivotRestartMs = now
              storageStagnationRefreshAttempted = true
              val reason =
                if progressStalled && stalledForMs < StorageStagnationThreshold.toMillis then
                  s"remaining work (pending+active) has not shrunk below ${storageTailBaseline.lowWork} " +
                    s"for ${remainingWorkStalledForMs / 1000}s while verification failures kept recurring " +
                    s"(tail livelock signature)"
                else s"no progress for ${stalledForMs / 1000}s"
              ctx.log.warn(
                s"Storage sync stalled: $reason " +
                  s"(threshold=${StorageStagnationThreshold.toSeconds}s). Attempting pivot refresh."
              )
              lastStorageProgressMs = now
              // Give the post-refresh remaining-work baseline a fresh start too, so a refresh that
              // genuinely helps isn't immediately re-flagged by a stale pre-refresh low point.
              storageTailBaseline = SNAPSyncController.StorageTailBaseline
                .fresh(now)
                .copy(
                  lowWork = stats.tasksPending + stats.tasksActive,
                  staleFailuresAtLow = stats.staleRootFailureEvents
                )
              refreshPivotInPlace(s"storage stagnation: $reason")
          else
            // Second stall after refresh: short grace period (2 min), then force-complete.
            // The pivot refresh either works quickly or not at all.
            val postRefreshGrace = 2.minutes
            if stalledForMs >= postRefreshGrace.toMillis then
              ctx.log.warn(
                s"Storage sync stalled after pivot refresh: no progress for ${stalledForMs / 1000}s. " +
                  s"Promoting to healing phase (preserving downloaded state)."
              )
              timers.cancel(RequestStorageRanges)
              if !forceCompleteStorageSent then
                forceCompleteStorageSent = true
                storageRangeCoordinator.foreach(_ ! actors.StorageRangeCoordinator.ForceCompleteStorage)

  /** Force-complete bytecode sync if no progress for BytecodeStagnationThreshold.
    *
    * Only fires during ByteCodeAndStorageSync when noMoreTasksExpected is set (post-AccountRange). The abandoned
    * bytecodes are NOT fetched by anything on the way to regular sync. Two things pick them up: `finalizeSnapSync`
    * leaves `bytecodeRecoveryDone` unset after a force-completion, so the NEXT START's `BytecodeRecoveryActor` scan
    * finds and downloads them; and until then `BlockImporter` fetches the code of a contract a block touches over SNAP
    * GetByteCodes (`MissingCodeException`). This comment used to say they were recovered "per-block during import via
    * BytecodeRecoveryActor", which nothing did: the actor runs only at startup, and finalisation marked its work done.
    */
  private def maybeForceCompleteIfBytecodeStagnant(progress: actors.ByteCodeCoordinator.ByteCodeProgress): Unit =
    if currentPhase == ByteCodeAndStorageSync && !bytecodePhaseComplete then
      if progress.bytecodesDownloaded > lastBytecodeProgressCount then
        lastBytecodeProgressCount = progress.bytecodesDownloaded
        lastBytecodeProgressMs = System.currentTimeMillis()
      else
        val stalledForMs = System.currentTimeMillis() - lastBytecodeProgressMs
        if stalledForMs >= BytecodeStagnationThreshold.toMillis then
          ctx.log.warn(
            s"ByteCode sync stalled: no progress for ${stalledForMs / 1000}s " +
              s"(threshold=${BytecodeStagnationThreshold.toSeconds}s, downloaded=${progress.bytecodesDownloaded}). " +
              s"Force-completing — missing bytecodes left to on-demand fetch at import and the next start's recovery scan."
          )
          bytecodeForceCompleted = true
          bytecodeCoordinator.foreach(_ ! actors.ByteCodeCoordinator.ForceCompleteByteCodes)

  // Track consecutive account stall pivot refreshes to detect truly unrecoverable situations.
  // Reset to 0 on real progress (taskProgress || downloadProgress in maybeRestartIfAccountStagnant).
  private var consecutiveAccountStallRefreshes: Int = 0

  private def maybeRestartIfAccountStagnant(progress: actors.AccountRangeStats): Unit =
    if currentPhase == AccountRangeSync then
      // Only consider it a stall if there is still work to do.
      val workRemaining = progress.tasksPending > 0 || progress.tasksActive > 0

      if workRemaining then
        // Update liveness based on task completions OR account download progress.
        // The sync is making progress if EITHER metric advances. This prevents false
        // stagnation when accounts are downloading steadily across all 16 chunks but
        // no single chunk has finished its full 1/16th range yet.
        val taskProgress = progress.tasksCompleted > lastAccountTasksCompleted
        val downloadProgress = progress.accountsDownloaded > lastAccountsDownloaded

        if taskProgress || downloadProgress then
          lastAccountTasksCompleted = progress.tasksCompleted
          lastAccountsDownloaded = progress.accountsDownloaded
          lastAccountProgressMs = System.currentTimeMillis()
          consecutiveAccountStallRefreshes = 0 // Reset on real progress
        else if progress.dispatchPaused then
          // The coordinator is deliberately not dispatching (downstream storage/bytecode back-pressure or a zero
          // per-peer budget). That is flow control, not a stall: restart the stagnation clock instead of counting.
          lastAccountProgressMs = System.currentTimeMillis()
        else
          val now = System.currentTimeMillis()
          val stalledForMs = now - lastAccountProgressMs

          // Avoid noisy rapid refreshes.
          if stalledForMs >= AccountStagnationThreshold.toMillis && now - lastPivotRestartMs >= MinPivotRestartInterval.toMillis
          then
            lastPivotRestartMs = now

            // Check if the stall is due to no peers (not a processing failure).
            // On ETC mainnet, SNAP peer gaps of 30-60 minutes are normal.
            // Don't escalate when the root cause is simply waiting for peers.
            val snapPeerCount = handshakedPeers.values.count(_.peerInfo.remoteStatus.supportsSnap)
            if snapPeerCount == 0 then
              ctx.log.info(
                s"Account sync stalled (${stalledForMs / 1000}s) but no SNAP peers available. " +
                  s"Waiting for peers to reconnect (downloaded=${progress.accountsDownloaded})."
              )
              // Don't increment stall counter — this is a peer availability issue, not a sync failure.
              // Reset the stall timer so we don't immediately escalate when peers reconnect.
              lastAccountProgressMs = System.currentTimeMillis()
            else
              consecutiveAccountStallRefreshes += 1

              val context =
                s"account range sync stalled: no download progress for ${stalledForMs / 1000}s " +
                  s"(threshold=${AccountStagnationThreshold.toSeconds}s), accountsDownloaded=${progress.accountsDownloaded}, " +
                  s"tasksPending=${progress.tasksPending}, tasksActive=${progress.tasksActive}, snapPeers=$snapPeerCount"

              // After enough refresh attempts without download progress, escalate so the node doesn't
              // loop forever. recordCriticalFailure trips dormant mode at the configured threshold;
              // if that hasn't tripped yet, fall through to one more in-place refresh.
              val dormantTriggered =
                if consecutiveAccountStallRefreshes > MaxConsecutiveAccountStallRefreshes then
                  ctx.log.error(
                    s"Account stall pivot-refresh budget exhausted " +
                      s"($consecutiveAccountStallRefreshes > $MaxConsecutiveAccountStallRefreshes). $context"
                  )
                  consecutiveAccountStallRefreshes = 0
                  lastAccountProgressMs = System.currentTimeMillis()
                  if recordCriticalFailure(s"account stall refresh limit exceeded: $context") then
                    enterDormantMode(s"account stall refresh limit exceeded: $context")
                    true
                  else false
                else false

              if !dormantTriggered then
                // In-place pivot refresh to try to find a serveable root.
                ctx.log.warn(
                  s"Account stall detected ($context). " +
                    s"Refreshing pivot in-place to recover (attempt $consecutiveAccountStallRefreshes). " +
                    s"Account data preserved."
                )
                lastAccountProgressMs = System.currentTimeMillis()
                // #1184: ask the coordinator to drain `activeTasks` BEFORE the pivot refresh so any
                // leaked slots are re-queued and visible by the time the resulting `PivotRefreshed`
                // message arrives. Coordinator-side defensive drains (PeerUnavailable / PivotRefreshed
                // / CheckDispatchStalled) cover this independently; this is the explicit controller hook.
                accountRangeCoordinator.foreach(_ ! actors.AccountRangeCoordinator.RecoverStalledAccountTasks)
                refreshPivotInPlace(s"account stall: $context")
              // end if !dormantTriggered
            // end else (snapPeerCount > 0)
          // end if stalledForMs >= threshold
        // end else (no progress)
      // end if workRemaining
  // end if currentPhase == AccountRangeSync
