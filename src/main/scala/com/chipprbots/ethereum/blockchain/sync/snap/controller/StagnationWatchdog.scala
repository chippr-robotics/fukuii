package com.chipprbots.ethereum.blockchain.sync.snap.controller

import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.Behaviors

import scala.concurrent.duration.*

import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.*
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.SyncPhase.*
import com.chipprbots.ethereum.blockchain.sync.snap.actors

private[snap] trait StagnationWatchdogState:
  var lastStorageProgressMs: Long
  var lastBytecodeProgressMs: Long
  var lastBytecodeProgressCount: Long
  var lastAccountProgressMs: Long
  var lastAccountTasksCompleted: Int
  var lastAccountsDownloaded: Long
  var storageTailBaseline: SNAPSyncController.StorageTailBaseline
  var storageStagnationRefreshAttempted: Boolean
  var lastPivotRestartMs: Long
  def MinPivotRestartInterval: FiniteDuration
  def StorageRestartedEmptyThreshold: FiniteDuration

/** SNAP download stagnation watchdog (spec 016 M7, research.md R6 "Download-phase supervision + stagnation"): the 30 s
  * check timer (`scheduleStagnationChecks`), the answers to its progress asks, which are the whole `AccountRangeSync`
  * and `ByteCodeAndStorageSync` phase functions (`accountRangeArms`, `byteCodeAndStorageArms`), and the three phase
  * checks they call: account stall -> in-place pivot refresh, then dormant mode (`maybeRestartIfAccountStagnant`);
  * storage stall or tail livelock -> one pivot refresh, then force-complete (`maybeRestartIfStorageStagnant`); bytecode
  * stall -> force-complete (`maybeForceCompleteIfBytecodeStagnant`). The core keeps the dispatch (`phaseArms`), the
  * `CheckDownloadStagnation` arm (`checkDownloadStagnationArms`) that sends the asks. The clock resets at launch,
  * refresh, `startSnapSync`'s recovery, the storage-phase entry, the bootstrap retry and the dormant wake go through
  * `resetStagnationClocks(reason)`, called from the core.
  */
/** The stagnation watchdog's clock-reset hook (spec 016 plan.md D1 `StagnationResetApi`, M7's `watchdog.reset(reason)`;
  * CQ-SNAP-016-20). A module that moves out of the core and resets the clocks names this trait, not
  * `StagnationWatchdog`.
  */
private[snap] trait StagnationResetApi:
  def resetStagnationClocks(reason: StagnationWatchdog.ResetReason): Unit

private[snap] trait StagnationScheduleApi:
  def scheduleStagnationChecks(): Unit

private[snap] trait StagnationWatchdog extends StagnationResetApi with StagnationScheduleApi:
  self: StagnationWatchdogState & SnapSharedState & SnapControllerEnv & CoordinatorHandles & PhaseFlags &
    HandshakedPeersApi & PeerPoolApi & LifecycleApi & PivotRefreshApi & PivotRollTriggerApi =>

  // Storage stagnation watchdog: if storage stops advancing while tasks remain, repivot/restart.
  // This addresses the common case where peers no longer serve the chosen pivot/state window.
  // Threshold must be generous enough to allow large chains to complete within the SNAP serve window.
  // Unified stagnation watchdog thresholds — one check interval, phase-specific thresholds
  private lazy val DownloadStagnationCheckInterval: FiniteDuration = 30.seconds
  private lazy val StorageStagnationThreshold: FiniteDuration =
    10.minutes // CFG-2: 20→10min; second stall force-completes after 30s anyway
  private lazy val AccountStagnationThreshold: FiniteDuration = snapSyncConfig.accountStagnationTimeout
  private lazy val BytecodeStagnationThreshold: FiniteDuration = 10.minutes
  private lazy val ZeroPeerStagnationMs: Long = 3 * 60 * 1000L // 3 min zero-peer short-circuit
  // Hard cap so a wedged account phase escalates to dormant mode rather than refreshing
  // forever. Higher than MaxConsecutivePivotRefreshes because account stalls can be transient
  // (peer churn, slow networks) and the stagnation threshold itself already takes minutes to fire.
  private lazy val MaxConsecutiveAccountStallRefreshes = 10

  /** `syncing` arms guarded on `currentPhase == AccountRangeSync` (P4a). */
  private[snap] lazy val accountRangeArms: PartialFunction[Command, Behavior[Command]] = {
    case AccountCoordinatorProgress(progress) =>
      if progress.elapsedTimeMs > 0 || progress.tasksPending > 0 || progress.tasksActive > 0 || progress.tasksCompleted > 0
      then maybeRestartIfAccountStagnant(progress).getOrElse(Behaviors.same)
      else Behaviors.same
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

  /** Reset the stagnation clocks for `reason` (spec 016 restructure, CQ-SNAP-016-20). Each reason writes exactly what
    * its call site wrote inline before, in the same order, with one `System.currentTimeMillis()` call per write as
    * before. The reasons reset different sets on purpose; a set that looks wrong is fixed in its own PR as a change to
    * this table (#1551), not here.
    *
    *   - `AccountLaunch` (`launchAccountRangeWorkers`): `lastAccountProgressMs`, `lastAccountTasksCompleted`,
    *     `lastAccountsDownloaded`.
    *   - `AccountsCompleteRecovery` (`startSnapSync`'s `recoverAccountsComplete`, bytecode + storage resumed):
    *     `lastStorageProgressMs`, `storageTailBaseline`, `lastBytecodeProgressMs`, `lastBytecodeProgressCount`, the
    *     same set as `StoragePhaseEntry`: the recovery spawns a new bytecode coordinator, so a stale clock would
    *     force-complete it on its first progress tick (#1551 item 1).
    *   - `StoragePhaseEntry` (the `AccountRangeSyncComplete` arm): `lastStorageProgressMs`, `storageTailBaseline`,
    *     `lastBytecodeProgressMs`, `lastBytecodeProgressCount`.
    *   - `PivotRefreshHeaderFetch` (`refreshPivotInPlace`, header not local), `PivotRefreshCommit`
    *     (`completePivotRefreshWithStateRoot`) and `BootstrapRetryAtBlock` (`retryBootstrapAtBlockArms`, header not
    *     local): `lastAccountProgressMs` only.
    *   - `Wake` (`wakeFromDormant`, before its `startSnapSync()`): `storageStagnationRefreshAttempted`,
    *     `consecutiveAccountStallRefreshes`. Dormancy is where the escalation ends, and the wake already gives fresh
    *     budgets (`consecutivePivotRefreshes`, `bootstrapRetryCount`), so the next round's first storage stall gets its
    *     pivot refresh again (#1551 item 2) and its account stall budget starts from 0 (#1551 item 3).
    *
    * No reason resets `lastPivotRestartMs`. `restartSnapSync` is deliberately not a reason: it is also the fallback of
    * a failed in-round pivot refresh (`BootstrapComplete(None)`), so clearing the stall state there would let a stall
    * -> refresh -> no header -> restart loop cycle forever instead of escalating, the reason it already keeps
    * `consecutivePivotRefreshes`.
    */
  def resetStagnationClocks(reason: StagnationWatchdog.ResetReason): Unit =
    reason match
      case StagnationWatchdog.ResetReason.AccountLaunch =>
        lastAccountProgressMs = System.currentTimeMillis()
        lastAccountTasksCompleted = 0
        lastAccountsDownloaded = 0
      case StagnationWatchdog.ResetReason.AccountsCompleteRecovery =>
        lastStorageProgressMs = System.currentTimeMillis()
        storageTailBaseline = SNAPSyncController.StorageTailBaseline.fresh(System.currentTimeMillis())
        lastBytecodeProgressMs = System.currentTimeMillis()
        lastBytecodeProgressCount = 0L
      case StagnationWatchdog.ResetReason.StoragePhaseEntry =>
        lastStorageProgressMs = System.currentTimeMillis()
        storageTailBaseline = SNAPSyncController.StorageTailBaseline.fresh(System.currentTimeMillis())
        lastBytecodeProgressMs = System.currentTimeMillis()
        lastBytecodeProgressCount = 0L
      case StagnationWatchdog.ResetReason.PivotRefreshHeaderFetch =>
        lastAccountProgressMs = System.currentTimeMillis()
      case StagnationWatchdog.ResetReason.PivotRefreshCommit =>
        lastAccountProgressMs = System.currentTimeMillis()
      case StagnationWatchdog.ResetReason.BootstrapRetryAtBlock =>
        lastAccountProgressMs = System.currentTimeMillis()
      case StagnationWatchdog.ResetReason.Wake =>
        storageStagnationRefreshAttempted = false
        consecutiveAccountStallRefreshes = 0

  def scheduleStagnationChecks(): Unit =
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
  // Reset to 0 on real progress (taskProgress || downloadProgress in maybeRestartIfAccountStagnant), on budget
  // exhaustion, and on a dormant wake (ResetReason.Wake, #1551); deliberately not on restartSnapSync.
  // private[snap] (not private) only so the reset-table pin test can read it.
  private[snap] var consecutiveAccountStallRefreshes: Int = 0

  /** Check the account phase for a stall. `Some(<dormant behaviour>)` when the stall budget trips the critical-failure
    * limit and dormant mode is entered (#1515: the caller must continue in that behaviour, the one that handles
    * `DormantWakeUp`); `None` otherwise.
    */
  private def maybeRestartIfAccountStagnant(progress: actors.AccountRangeStats): Option[Behavior[Command]] =
    var dormant: Option[Behavior[Command]] = None
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
                    dormant = Some(enterDormantMode(s"account stall refresh limit exceeded: $context"))
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

    dormant

  /** A `commonSyncingArms` arm lifted into a class member (spec 016 restructure, CQ-SNAP-016-18/19), body unchanged:
    * `CheckDownloadStagnation`, the 30-s stagnation tick: the proactive pivot roll, the zero-SNAP-peer log and the
    * progress `ask`s whose answers `accountRangeArms` and `byteCodeAndStorageArms` handle. `commonSyncingArms`
    * delegates to it at the arm's original position, so the arm order is unchanged.
    */
  private[snap] lazy val checkDownloadStagnationArms: PartialFunction[Command, Behavior[Command]] = {
    // C2: inlined from aroundReceive — stagnation check dispatches to the active coordinator
    case CheckDownloadStagnation =>
      import org.apache.pekko.util.Timeout
      import scala.util.{Success, Failure}
      given timeout: Timeout = Timeout(2.seconds)
      ctx.log.debug(
        s"Stagnation check: phase=$currentPhase, stalledMs=${System.currentTimeMillis() - lastStorageProgressMs}"
      )

      maybeProactivePivotRoll()

      val snapPeerCount = peersToDownloadFrom.values.count(_.peerInfo.remoteStatus.supportsSnap)
      val totalPeerCount = peersToDownloadFrom.size
      val stagnantMs = System.currentTimeMillis() - lastAccountProgressMs
      if currentPhase == AccountRangeSync && snapPeerCount == 0 && stagnantMs > ZeroPeerStagnationMs then
        ctx.log.info(
          s"[STAGNATION] Zero SNAP peers for ${stagnantMs / 1000}s during account sync " +
            s"($totalPeerCount total peers) — waiting for peers to reconnect; downloaded state is preserved " +
            s"(no restart)"
        )

      currentPhase match
        case AccountRangeSync =>
          accountRangeCoordinator.foreach { coordinator =>
            import org.apache.pekko.actor.typed.scaladsl.AskPattern.*
            given typedScheduler: org.apache.pekko.actor.typed.Scheduler = ctx.system.scheduler
            ctx.pipeToSelf(
              coordinator.ask[actors.AccountRangeStats](replyTo =>
                actors.AccountRangeCoordinator.AccountGetProgress(replyTo)
              )
            ) {
              case Success(stats) => AccountCoordinatorProgress(stats)
              case Failure(_)     => AccountCoordinatorProgress(actors.AccountRangeStats(0L, 0L, 0, 0, 0, 0.0, 0L, 0))
            }
          }
        case ByteCodeAndStorageSync =>
          storageRangeCoordinator.foreach { coordinator =>
            import org.apache.pekko.actor.typed.scaladsl.AskPattern.*
            given typedScheduler: org.apache.pekko.actor.typed.Scheduler = ctx.system.scheduler
            ctx.pipeToSelf(
              coordinator.ask[actors.StorageRangeCoordinator.SyncStatistics](replyTo =>
                actors.StorageRangeCoordinator.StorageGetProgress(replyTo)
              )
            ) {
              case Success(stats) => StorageCoordinatorProgress(stats)
              case Failure(_) =>
                StorageCoordinatorProgress(actors.StorageRangeCoordinator.SyncStatistics(0, 0, 0, 0, 0, 0, 0.0))
            }
          }
          if !bytecodePhaseComplete then
            bytecodeCoordinator.foreach { coordinator =>
              import org.apache.pekko.actor.typed.scaladsl.AskPattern.*
              given typedScheduler: org.apache.pekko.actor.typed.Scheduler = ctx.system.scheduler
              ctx.pipeToSelf(
                coordinator.ask[actors.ByteCodeCoordinator.ByteCodeProgress](replyTo =>
                  actors.ByteCodeCoordinator.ByteCodeGetProgress(replyTo)
                )
              ) {
                case Success(progress) => ByteCodeCoordinatorProgress(progress)
                case Failure(_) =>
                  ByteCodeCoordinatorProgress(actors.ByteCodeCoordinator.ByteCodeProgress(0.0, 0L, 0L))
              }
            }
        case _ => // No stagnation check needed in other phases
      Behaviors.same
  }

private[snap] object StagnationWatchdog:

  /** The call sites that reset the stagnation clocks (spec 016 restructure, CQ-SNAP-016-20), one reason per site. */
  enum ResetReason:
    case AccountLaunch, AccountsCompleteRecovery, StoragePhaseEntry, PivotRefreshHeaderFetch, PivotRefreshCommit,
      BootstrapRetryAtBlock, Wake
