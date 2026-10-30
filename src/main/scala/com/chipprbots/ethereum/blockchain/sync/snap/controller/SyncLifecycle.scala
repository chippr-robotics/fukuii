package com.chipprbots.ethereum.blockchain.sync.snap.controller

import java.util.concurrent.atomic.AtomicBoolean

import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.PostStop
import org.apache.pekko.actor.typed.scaladsl.Behaviors

import scala.concurrent.duration.*

import com.chipprbots.ethereum.blockchain.sync.SyncProtocol
import com.chipprbots.ethereum.blockchain.sync.snap.HeapWatchdogEscape
import com.chipprbots.ethereum.blockchain.sync.snap.HeapWatchdogEscapePolicy
import com.chipprbots.ethereum.blockchain.sync.snap.OldGenReading
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.*
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.SyncPhase.*
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncMetrics
import com.chipprbots.ethereum.blockchain.sync.snap.SnapHeapWatchdog
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.TrieRoot

private[snap] trait SyncLifecycleState:
  var criticalFailureCount: Int
  var accountsAtLastCriticalFailure: Long
  var dormantRetryCount: Int
  var consecutivePivotRefreshes: Int
  var bootstrapRetryCount: Int
  var pendingPivotRefresh: Option[(BigInt, String)]
  var pivotProbeRequestId: Option[BigInt]
  var pendingProbeCommit: Option[(BigInt, BlockHeader, String)]
  var proactiveRollNeedsProbe: Boolean
  var probeAttemptCount: Int
  var lastProbeAttemptMs: Long
  var healingServeRootRequestInFlight: Boolean
  var lastHealingServeRootBlock: Option[BigInt]
  var healingValidatedRoot: Option[TrieRoot]
  def healingWalkLocalOnly: AtomicBoolean
  var bytecodesEstimatedTotal: Long
  var mptStorage: Option[MptStorage]
  var coordinatorGeneration: Long
  var validationGeneration: Long
  var validationInProgress: Boolean
  var snapPeerEvictionStarted: Boolean
  var snapServerPeersSchedulerStarted: Boolean
  def cancelSyncTimers(keys: Any*): Unit
  def BootstrapCheckKey: String
  def PivotBootstrapRetryKey: String
  def SnapCapabilityCheckKey: String
  def peerEventArms: PartialFunction[Command, Behavior[Command]]

private[snap] trait LifecycleApi:
  def recordCriticalFailure(reason: String): Boolean
  def enterDormantMode(reason: String): Behavior[Command]
  def restartSnapSync(reason: String): Behavior[Command]

private[snap] trait HeapWatchdogApi:
  def ensureHeapWatchdog(): Unit

private[snap] trait SnapStopApi:
  def stopSnapOnlySchedules(): Unit
  def stopStateSyncChildren(): Unit

/** SNAP sync lifecycle (spec 016 M9, research.md R6 "Dormant / critical-failure"): the critical-failure count and its
  * threshold (`recordCriticalFailure`), dormant mode (`enterDormantMode`, the `dormantRetry` behaviour and its
  * `DormantWakeUp` wake-up, `wakeFromDormant`), the full restart on a fresh pivot (`restartSnapSync`), and the #1501
  * heap watchdog's lifecycle (`ensureHeapWatchdog`, `stopHeapWatchdog`, and the callbacks it runs on its own thread,
  * `onHeapPressureChange` and `onHeapWatchdogEscape`). The watchdog's start seam is this trait's parameter. The core
  * keeps the call sites: the watchdog starts at the two coordinator-spawn clusters and stops in `onStop` and
  * `stopSnapOnlySchedules`. It also keeps the dispatch: the `DelayedRestart` and `PivotStateUnservable` arms of
  * `commonSyncingArms`.
  */
private[snap] trait SyncLifecycle(val heapWatchdogStart: HeapWatchdogStart)
    extends LifecycleApi
    with HeapWatchdogApi
    with SnapStopApi:
  self: SyncLifecycleState & SnapSharedState & SnapControllerEnv & CoordinatorHandles & PhaseFlags &
    HandshakedPeersApi & HealedCodeHoldApi & ShutdownApi & SnapStartApi & StagnationResetApi =>

  private lazy val DormantBaseDelay: FiniteDuration = 3.minutes
  private lazy val DormantMaxDelay: FiniteDuration = 20.minutes
  private lazy val DormantWakeUpKey = "dormant-wakeup"

  // Started with the first SNAP coordinators (not at construction: a node that never runs SNAP needs no watchdog).
  private var heapWatchdog: Option[SnapHeapWatchdog.Handle] = None

  def ensureHeapWatchdog(): Unit =
    if heapWatchdog.isEmpty && snapSyncConfig.heapWatchdogEnabled then
      heapWatchdog = heapWatchdogStart(
        highFraction = snapSyncConfig.heapWatchdogPauseFraction,
        lowFraction = snapSyncConfig.heapWatchdogResumeFraction,
        pollInterval = snapSyncConfig.heapWatchdogPollInterval,
        onChange = onHeapPressureChange,
        queuesEmpty = () => intakeBudget.pendingStorageTasks == 0L && intakeBudget.pendingByteCodeHashes == 0L,
        policy = HeapWatchdogEscapePolicy(
          ineffectiveAfterMs = snapSyncConfig.heapWatchdogIneffectiveAfter.toMillis,
          maxPauseMs = snapSyncConfig.heapWatchdogMaxPause.toMillis
        ),
        onEscape = onHeapWatchdogEscape
      )

  /** Runs on the watchdog thread, like `onHeapPressureChange`. */
  private def onHeapWatchdogEscape(escape: HeapWatchdogEscape, reading: OldGenReading): Unit =
    val mib = 1024L * 1024L
    val occupancy =
      s"old gen after GC ${reading.postGcUsed / mib} MiB (${(reading.postGcFraction * 100).round}% of " +
        s"${reading.max / mib} MiB), now ${reading.currentUsed / mib} MiB"
    escape match
      case HeapWatchdogEscape.PauseIneffective(pausedMs, idleMs) =>
        asyncLog.warn(
          s"[SNAP-HEAP] SNAP intake paused for ${pausedMs / 1000}s but the storage/bytecode queues have been empty " +
            s"for ${idleMs / 1000}s: the heap is held by something the pause cannot drain - $occupancy. " +
            s"Pending: ${intakeBudget.describe}" +
            (if snapSyncConfig.heapWatchdogMaxPause.toMillis > 0 then
               s". Force-release after ${snapSyncConfig.heapWatchdogMaxPause.toSeconds}s paused"
             else ". heap-watchdog-max-pause = 0: staying paused")
        )
      case HeapWatchdogEscape.ForcedRelease(pausedMs) =>
        intakeBudget.setHeapPressure(false)
        intakeBudget.publishMetrics()
        asyncLog.error(
          s"[SNAP-HEAP] FORCE-RELEASING SNAP intake after ${pausedMs / 1000}s of ineffective heap pause - " +
            s"$occupancy. Pending: ${intakeBudget.describe}. The pending-work ceilings still apply; the heap watchdog " +
            "re-arms once occupancy falls below the resume threshold. Investigate non-SNAP heap use."
        )

  // Stopped only by stopSnapOnlySchedules (SNAP completion, before regular sync) and onStop. Every coordinator launch
  // (account phase, accounts-complete recovery) calls ensureHeapWatchdog, so a SNAP restart re-arms it.
  private[snap] def stopHeapWatchdog(): Unit =
    heapWatchdog.foreach(_.stop())
    heapWatchdog = None
    if intakeBudget.heapPressureActive then intakeBudget.setHeapPressure(false)

  /** Runs on the watchdog / JMX notification thread: touches only the thread-safe gate, metrics and the SLF4J logger.
    */
  private def onHeapPressureChange(engaged: Boolean, reading: OldGenReading): Unit =
    intakeBudget.setHeapPressure(engaged)
    intakeBudget.publishMetrics()
    val mib = 1024L * 1024L
    val occupancy =
      s"old gen after GC ${reading.postGcUsed / mib} MiB (${(reading.postGcFraction * 100).round}% of " +
        s"${reading.max / mib} MiB), now ${reading.currentUsed / mib} MiB"
    if engaged then
      asyncLog.warn(
        s"[SNAP-HEAP] heap pressure: PAUSING SNAP intake (account dispatch, carried replay, recovery stream) - " +
          s"$occupancy. Storage/bytecode downloads keep draining. Pending: ${intakeBudget.describe}"
      )
    else
      asyncLog.warn(
        s"[SNAP-HEAP] heap pressure cleared: RESUMING SNAP intake - $occupancy. Pending: ${intakeBudget.describe}"
      )

  /** Record a critical failure and check whether the threshold is reached. Critical failures are those that indicate
    * SNAP sync cannot proceed on the current pivot.
    *
    * @param reason
    *   Description of the failure. Yields `true` if the retry limit is exceeded and the caller should enter dormant
    *   mode.
    */
  def recordCriticalFailure(reason: String): Boolean =
    SNAPSyncMetrics.incrementSyncError()
    criticalFailureCount += 1
    accountsAtLastCriticalFailure = progressMonitor.currentProgress.accountsSynced
    ctx.log.warn(s"Critical SNAP sync failure ($criticalFailureCount/${snapSyncConfig.maxSnapSyncFailures}): $reason")

    if criticalFailureCount >= snapSyncConfig.maxSnapSyncFailures then
      ctx.log.error(s"SNAP sync failed ${criticalFailureCount} times — threshold reached")
      true
    else false

  /** Enter dormant mode: stop all coordinators and scheduled tasks, preserve all RocksDB data, and schedule a wake-up
    * with exponential backoff. SNAP's recovery when it cannot proceed — critical-failure threshold, bootstrap retries
    * exhausted, no snap-capable peer after the capability grace period. The wake-up restarts SNAP on a fresh pivot once
    * a snap-capable peer is connected (`dormantRetry`), and all persisted SNAP progress is kept.
    */
  def enterDormantMode(reason: String): Behavior[Command] =
    currentPhase = Dormant

    ctx.log.warn(
      s"Entering dormant mode (attempt ${dormantRetryCount + 1}): $reason. " +
        s"All downloaded state preserved. Will retry after backoff."
    )

    cancelSyncTimers(
      RequestAccountRanges,
      RequestByteCodes,
      RequestStorageRanges,
      CheckDownloadStagnation,
      RequestTrieNodeHealing,
      BootstrapCheckKey,
      PivotBootstrapRetryKey,
      TuneRateTracker,
      SnapCapabilityCheckKey,
      EvictNonSnapPeers
    )

    stopAll()
    healingWalkLocalOnly.set(false)

    requestTracker.clear()
    pendingPivotRefresh = None
    pivotProbeRequestId = None
    pendingProbeCommit = None
    proactiveRollNeedsProbe = false
    probeAttemptCount = 0
    // Dormancy leaves every phase flag as it is (an empty reset set, research.md R3b).
    reset(PhaseFlags.ResetKind.Dormant)

    dormantRetryCount += 1
    val backoffMs = math.min(
      DormantBaseDelay.toMillis * (1L << math.min(dormantRetryCount - 1, 10)),
      DormantMaxDelay.toMillis
    )
    val backoff = backoffMs.millis

    ctx.log.info(
      s"Dormant backoff: ${backoff.toSeconds}s " +
        s"(attempt $dormantRetryCount, criticalFailures=$criticalFailureCount)"
    )

    timers.cancel(DormantWakeUpKey)
    timers.startSingleTimer(DormantWakeUpKey, DormantWakeUp, backoff)

    dormantRetry()

  private def wakeFromDormant(): Behavior[Command] =
    ctx.log.info(
      s"Waking from dormant mode after $dormantRetryCount attempt(s). " +
        s"criticalFailureCount=$criticalFailureCount. Restarting SNAP sync with fresh pivot."
    )

    resetHealedCodeHold()
    reset(PhaseFlags.ResetKind.Wake)
    bytecodesEstimatedTotal = 0L
    healingValidatedRoot = None

    pivotBlock = None
    stateRoot = None
    mptStorage = None
    currentPhase = Idle
    coordinatorGeneration += 1
    validationGeneration += 1
    validationInProgress = false
    consecutivePivotRefreshes = 0
    // A fresh retry budget: going dormant on exhausted bootstrap retries leaves the counter at the limit, so without
    // this the first retry after waking (a peer whose height is not known yet, say) would send it straight back.
    bootstrapRetryCount = 0
    // #1551: the stagnation stall state of the round that went dormant does not carry into the new round.
    resetStagnationClocks(StagnationWatchdog.ResetReason.Wake)

    startSnapSync()

  def dormantRetry(): Behavior[Command] =
    Behaviors
      .receiveMessage[Command] {
        // Peer events, CLPivotHint and GetProgress: peerEventArms (P3).
        case msg if peerEventArms.isDefinedAt(msg) =>
          peerEventArms(msg)

        case DormantWakeUp =>
          val snapPeerCount = handshakedPeers.values.count(_.peerInfo.remoteStatus.supportsSnap)
          if snapPeerCount > 0 then
            ctx.log.info(s"Dormant wake-up: $snapPeerCount SNAP peer(s) available. Restarting SNAP sync.")
            wakeFromDormant()
          else
            ctx.log.info(s"Dormant wake-up: still no SNAP peers. Re-entering dormant with extended backoff.")
            enterDormantMode(s"no SNAP peers at wake-up (attempt $dormantRetryCount)")

        case GetStatus(replyTo) =>
          replyTo ! SyncProtocol.Status.Syncing(
            startingBlockNumber = stateIngest.appState.getSyncStartingBlock(),
            blocksProgress = SyncProtocol.Status.Progress(
              pivotBlock.getOrElse(BigInt(0)),
              pivotBlock.getOrElse(BigInt(0))
            ),
            stateNodesProgress = None
          )
          Behaviors.same

        case _ => // silently drop stale coordinator messages, SNAP responses, etc.
          Behaviors.same
      }
      .receiveSignal { case (_, PostStop) =>
        onStop(); Behaviors.same
      }

  def restartSnapSync(reason: String): Behavior[Command] =
    ctx.log.warn(s"Restarting SNAP sync with a fresher pivot: $reason")

    // Clear any pending pivot refresh (we're doing a full restart instead)
    pendingPivotRefresh = None
    pivotProbeRequestId = None
    pendingProbeCommit = None
    proactiveRollNeedsProbe = false
    probeAttemptCount = 0
    lastProbeAttemptMs = 0L
    // spec 004 T011: a full restart spawns a fresh healing coordinator (serveRoot re-inits to the walk root);
    // clear the serve-root bookkeeping so the first healing tick re-engages decoupling against the new round.
    healingServeRootRequestInFlight = false
    lastHealingServeRootBlock = None

    // NOTE: do NOT reset consecutivePivotRefreshes here. restartSnapSync is often called
    // from refreshPivotInPlace when no new pivot is available, which means the counter would
    // reset on every failed cycle and never reach the threshold. The counter only resets on
    // actual account download progress (ProgressAccountsSynced) or at startSnapSync().

    // Cancel periodic phase request ticks
    cancelSyncTimers(
      RequestAccountRanges,
      RequestByteCodes,
      RequestStorageRanges,
      CheckDownloadStagnation,
      RequestTrieNodeHealing,
      BootstrapCheckKey,
      PivotBootstrapRetryKey,
      TuneRateTracker
    )

    // Stop coordinators so we don't double-run phases
    stopAll()
    healingWalkLocalOnly.set(false)

    // Clear inflight request timeouts and internal phase state
    requestTracker.clear()

    // Clear concurrent download state and recovery data
    resetHealedCodeHold()
    reset(PhaseFlags.ResetKind.Restart)
    // Reset bytecode-estimate counter so it stays in sync with progressMonitor.reset()
    // (called below). IncrementalContractData will repopulate as accounts are re-identified.
    bytecodesEstimatedTotal = 0L
    // #1188: invalidate clean-walk signal — the trie we're rebuilding is fresh state.
    // Root-equality alone would catch this (stateRoot is reset below) but we clear
    // explicitly so the field doesn't briefly hold a stale positive against a None root.
    healingValidatedRoot = None
    stateIngest.appState
      .putSnapSyncAccountsComplete(false)
      .and(stateIngest.appState.putSnapSyncStorageComplete(false))
      .and(stateIngest.appState.putSnapSyncBytecodeComplete(false))
      .commit()
    stateIngest.appState
      .putSnapSyncStorageFilePath("")
      .and(stateIngest.appState.putSnapSyncStorageFileCount(None))
      .and(stateIngest.appState.putSnapSyncCodeHashesCount(None))
      .commit()
    stateIngest.snapProgress.clearExcluded() // spec 022 D5: a cycle from nothing; no earlier decision still stands

    // Reset pivot/state root and storage so a new selection is committed
    pivotBlock = None
    stateRoot = None
    mptStorage = None
    currentPhase = Idle
    coordinatorGeneration += 1
    // Bump validation generation so any in-flight validation Future's result
    // is dropped on arrival. Also clear the in-progress flag — the new sync
    // attempt starts from scratch, no validation is active.
    validationGeneration += 1
    validationInProgress = false

    // Reset progress counters so logs/ETA reflect the new attempt
    progressMonitor.reset()

    // Re-run pivot selection/bootstrap with the latest visible peer set
    startSnapSync()

  /** Cancel every SNAP-only scheduled timer. Idempotent — `timers.cancel` tolerates unknown keys. Called both at the
    * lifecycle transition into `completedWithBackfill` (so eviction/tickers don't keep running while regular sync owns
    * the peer pool) and from the PostStop signal handler.
    */
  def stopSnapOnlySchedules(): Unit =
    cancelSyncTimers(
      RequestAccountRanges,
      RequestByteCodes,
      RequestStorageRanges,
      CheckDownloadStagnation,
      RequestTrieNodeHealing,
      BootstrapCheckKey,
      PivotBootstrapRetryKey,
      SnapCapabilityCheckKey,
      EvictNonSnapPeers,
      TuneRateTracker,
      EnsureSnapServerPeersConnected
    )
    snapServerPeersSchedulerStarted = false
    snapPeerEvictionStarted = false
    progressMonitor.stopPeriodicLogging()
    stopHeapWatchdog()

  /** Stop the SNAP state-sync child coordinators (account/bytecode/storage/healing) and clear their references. They do
    * not self-stop on completion, so when `SNAPSyncController` is kept alive past `finalizeSnapSync()` for background
    * chain backfill (#1162), failing to stop them retains completed task buffers, worker actors, and rate trackers for
    * the entire backfill window. The previous `PoisonPill` to the parent used to clean these up implicitly.
    *
    * `chainDownloader` is NOT stopped here — it keeps running in `completedWithBackfill`.
    */
  def stopStateSyncChildren(): Unit =
    stopAll()
    forceCompleteStorageSent = false
    healingWalkLocalOnly.set(false)

  /** A `commonSyncingArms` arm lifted into a class member (spec 016 lift before M10, CQ-SNAP-016-27), body unchanged:
    * `DelayedRestart`, the backed-off restart armed by `PivotStateUnservable`'s critical-failure path: restart in the
    * two download phases, ignored in any other (the phase test stays in the body). `commonSyncingArms` delegates to it
    * at the arm's original position, so the arm order is unchanged.
    */
  private[snap] lazy val delayedRestartArms: PartialFunction[Command, Behavior[Command]] = {
    // Delayed restart after critical failure backoff
    case DelayedRestart(reason) =>
      if currentPhase == AccountRangeSync || currentPhase == ByteCodeAndStorageSync then restartSnapSync(reason)
      else Behaviors.same
  }
