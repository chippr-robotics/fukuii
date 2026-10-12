package com.chipprbots.ethereum.blockchain.sync.snap.controller

import org.apache.pekko.actor.Scheduler
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.util.ByteString

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*
import scala.util.Try

import com.chipprbots.ethereum.blockchain.sync.snap.AccountResumeCheckpoint
import com.chipprbots.ethereum.blockchain.sync.snap.ContractTaskFiles
import com.chipprbots.ethereum.blockchain.sync.snap.GatedTaskFileReplay
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.*
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.SyncPhase.*
import com.chipprbots.ethereum.blockchain.sync.snap.StorageScheme
import com.chipprbots.ethereum.blockchain.sync.snap.StorageTask
import com.chipprbots.ethereum.blockchain.sync.snap.StorageTaskFile
import com.chipprbots.ethereum.blockchain.sync.snap.actors
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.db.storage.Namespaces
import com.chipprbots.ethereum.db.storage.SnapStorageDoneStorage
import com.chipprbots.ethereum.db.storage.SnapSyncProgressStorage
import com.chipprbots.ethereum.domain.TrieRoot
import com.chipprbots.ethereum.utils.ByteStringUtils.ByteStringOps
import com.chipprbots.ethereum.utils.Hex

private[snap] trait SnapResumePlannerState:
  var mptStorage: Option[MptStorage]
  def storageDoneStorage: SnapStorageDoneStorage
  def scheduler: Scheduler
  def minPivotHint: BigInt
  def isPoSChain: Boolean
  def clPivotHint: Option[CLPivotHint]
  def failedFinalizationPivot: Option[BigInt]
  var coordinatorGeneration: Long
  def recordStorageDone: Boolean
  def SnapServeWindowMargin: BigInt
  def BootstrapCheckKey: String
  var preservedRangeProgress: Map[ByteString, ByteString]
  var preservedAtPivotBlock: Option[BigInt]
  var preservedTaskFiles: Option[ContractTaskFiles]
  def snapProgressStorage: SnapSyncProgressStorage
  def launchedAccountGeneration: Long
  def currentCarrySource: Option[ContractTaskFiles]

private[snap] trait ResumeApi:
  def getOrCreateMptStorage(pivotBlockNumber: BigInt): MptStorage

private[snap] trait TaskFileSweepApi:
  def accountsCompleteTaskFilePaths: Set[String]
  def sweepSupersededTaskFiles(keep: Set[String], reason: String): Unit

private[snap] trait StorageDoneMarkersApi:
  def clearStorageDoneMarkers(reason: String): Unit

private[snap] trait AccountResumeSelectionApi:
  def selectAccountResume(
      rootHash: TrieRoot,
      effectiveConcurrency: Int,
      currentPivot: BigInt
  ): (Map[ByteString, ByteString], Option[ContractTaskFiles])

/** SNAP resume planner (spec 016 M5, research.md R6 "Persistence / resume"): the storage-scheme guard run at start
  * (`checkStorageSchemeMismatch`), the writable trie store for the pivot (`getOrCreateMptStorage`), the storage-task
  * completion markers' clear (`clearStorageDoneMarkers`), the contract task-file bookkeeping
  * (`accountsCompleteTaskFilePaths`, `sweepSupersededTaskFiles`), the gated recovery reader (`runGatedReplay`, #1501)
  * and the legacy progress parser (`deserializeSnapProgress`). The core keeps the resume decisions that sit inside
  * `startSnapSync` and `launchAccountRangeWorkers` and the checkpoint arm of `syncing`; they call these members.
  */
private[snap] trait SnapResumePlanner
    extends ResumeApi
    with TaskFileSweepApi
    with StorageDoneMarkersApi
    with AccountResumeSelectionApi:
  self: SnapResumePlannerState & SnapSharedState & SnapControllerEnv & CoordinatorHandles & PhaseFlags &
    PivotSelectionApi & BootstrappingApi & SyncingApi & HeapWatchdogApi & DownloadRecoveryApi & ByteCodeRequestApi &
    StagnationResetApi & StagnationScheduleApi & ChainDownloadStartApi & SnapServerPeersApi =>

  private lazy val MaxPreservedPivotDistance: BigInt = 50_000

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
    val hasPathRoot = stateIngest.flatSlots.dataSource
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
      val storage = stateIngest.stateTries.getBackingStorage(pivotBlockNumber)
      mptStorage = Some(storage)
      ctx.log.info(s"Created writable MptStorage for pivot block $pivotBlockNumber")
      storage
    }

  /** Drop every completion marker (a cheap range tombstone, here), then compact the range off the actor thread so the
    * tombstoned space (~80 B per marker, ~750 MB on Sepolia) is reclaimed now. The compaction is blocking I/O of about
    * the markers' size; it runs on the single-thread snap-validation dispatcher, delaying a validation walk queued
    * behind it by that long at most. A failure only leaves the space to background compaction.
    */
  def clearStorageDoneMarkers(reason: String): Unit =
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
    (stateIngest.appState.getSnapSyncStorageFilePath().toSet ++ stateIngest.appState.getSnapSyncCodeHashesPath().toSet)
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

  /** `recoverAccountsComplete`'s #1555 branch (PoS, every download phase complete): heal at a pivot newer than
    * `excluded` instead of resuming on the saved one. Like the drift branch's "all phases complete" case, the data
    * phases are marked complete and the new pivot's header is bootstrapped by number, so `bootstrapping`'s
    * `BootstrapComplete` commits it and goes straight to `StateHealing`. With no newer target yet (CL head and snap
    * peers not ahead), retry the start in 30 s; every retry re-enters this branch, because the saved pivot is still the
    * excluded one.
    */
  private def healAtNewerPivot(excluded: BigInt, reason: String)(using
      scala.util.boundary.Label[Behavior[Command]]
  ): Unit =
    import scala.util.boundary.break
    accountsComplete = true
    storagePhaseComplete = true
    bytecodePhaseComplete = true
    val clHead = clPivotHint.flatMap(_.knownHeader).map(_.number.value)
    val peerBest = currentNetworkBestFromSnapPeers()
    val target = clHead.flatMap(h =>
      SNAPSyncController.healPivotAfterExcluded(
        excluded,
        h,
        peerBest,
        snapSyncConfig.pivotBlockOffset,
        SnapServeWindowMargin
      )
    )
    target match
      case Some(t) =>
        ctx.log.warn(
          s"Recovery: not resuming healing on pivot $excluded ($reason). All data phases are complete; healing at " +
            s"pivot $t instead (CL head=${clHead.getOrElse("?")}, snap peer best=${peerBest.getOrElse("none")}). " +
            "Fetching its header."
        )
        stateIngest.appState.putSnapSyncBootstrapTarget(t).commit()
        syncController ! StartRegularSyncBootstrap(t)
        break(bootstrapping())
      case None =>
        ctx.log.warn(
          s"Recovery: not resuming healing on pivot $excluded ($reason), and no newer pivot is available yet " +
            s"(CL head=${clHead.getOrElse("unknown")}, snap peer best=${peerBest.getOrElse("none")}). Retrying in 30s."
        )
        timers.cancel(BootstrapCheckKey)
        timers.startSingleTimer(BootstrapCheckKey, RetrySnapSyncStart, 30.seconds)
        break(bootstrapping())

  /** `startSnapSync`, step 1 (spec 016 restructure, CQ-SNAP-016-17): the accounts-complete recovery branch. When a
    * previous run completed the account phase, either resume bytecodes + storage on the saved pivot (and break out of
    * `startSnapSync`'s boundary with `syncing()`), or clear the flags and fall through to the next step. Extracted
    * verbatim; `break` reaches the caller's boundary through the `Label`.
    */
  private[snap] def recoverAccountsComplete()(using scala.util.boundary.Label[Behavior[Command]]): Unit =
    import scala.util.boundary.break
    // Step 7: Check for accounts-complete recovery (process crash during bytecode/storage phase).
    // If accounts were previously completed and the pivot is still fresh, skip account download
    // and only re-run bytecodes + storage from the persisted storage file.
    if stateIngest.appState.isSnapSyncAccountsComplete() then
      val savedPivot = stateIngest.appState.getSnapSyncPivotBlock()
      val savedRootOpt = stateIngest.appState.getSnapSyncStateRoot()
      val savedStoragePath = stateIngest.appState.getSnapSyncStorageFilePath()

      (savedPivot, savedRootOpt) match
        case (Some(pivot), Some(rootBs)) if pivot > 0 =>
          ctx.log.info(s"Recovery: accounts previously completed at pivot $pivot. Checking freshness...")

          // FIX-BUG2-ESCALATION: saved pivot < RegularSync escalation hint → force fresh selection.
          // minPivotHint is the block number where RegularSync got stuck; re-using the saved pivot
          // (whose state trie is incomplete at that block) wastes time presenting a root that peers
          // have already evicted from their serve window. minPivotHint == 0 in non-escalation
          // restarts (crash recovery, normal restart) so this never fires spuriously.
          val belowEscalationHint = minPivotHint > 0 && pivot < minPivotHint
          if belowEscalationHint then
            ctx.log.warn(
              s"Recovery: saved pivot $pivot < RegularSync escalation hint $minPivotHint " +
                s"— clearing accounts-complete flag and forcing fresh pivot selection"
            )
            stateIngest.appState
              .putSnapSyncAccountsComplete(false)
              .and(stateIngest.appState.putSnapSyncStorageComplete(false))
              .and(stateIngest.appState.putSnapSyncBytecodeComplete(false))
              .commit()
            // Fall through to normal startup (same path as drift-exceeded + phases incomplete)

          // The persisted storage-task file is missing, unreadable, truncated or empty while storage is incomplete
          // (a reboot wiped /tmp, the path was never persisted, ...). Storage must not be skipped for that, and the
          // tasks cannot be rebuilt reliably from an unhealed account trie, so restart the accounts phase.
          val storageTaskFileUnusable =
            !belowEscalationHint && !stateIngest.appState.isSnapSyncStorageComplete() &&
              !savedStoragePath
                .filter(_.nonEmpty)
                .exists(p =>
                  StorageTaskFile.isUsable(
                    java.nio.file.Paths.get(p),
                    expectedCount = stateIngest.appState.getSnapSyncStorageFileCount()
                  )
                )
          val codeHashesFileUnusable =
            !belowEscalationHint && !stateIngest.appState.isSnapSyncBytecodeComplete() &&
              !stateIngest.appState
                .getSnapSyncCodeHashesPath()
                .filter(_.nonEmpty)
                .exists(p =>
                  StorageTaskFile.isUsable(
                    java.nio.file.Paths.get(p),
                    StorageTaskFile.CodeHashEntrySize,
                    stateIngest.appState.getSnapSyncCodeHashesCount()
                  )
                )
          val taskFilesUnusable = storageTaskFileUnusable || codeHashesFileUnusable
          if taskFilesUnusable then
            ctx.log.warn(
              s"Recovery: persisted task file unusable while its phase is incomplete " +
                s"(storage: ${savedStoragePath.filter(_.nonEmpty).getOrElse("<none persisted>")} unusable=$storageTaskFileUnusable; " +
                s"codeHashes: ${stateIngest.appState.getSnapSyncCodeHashesPath().filter(_.nonEmpty).getOrElse("<none persisted>")} unusable=$codeHashesFileUnusable). " +
                "A missing, empty or truncated file must not complete its phase. Restarting the accounts phase: clearing " +
                "accounts/storage/bytecode-complete flags and the persisted storage and bytecode file paths."
            )
            stateIngest.appState
              .putSnapSyncAccountsComplete(false)
              .and(stateIngest.appState.putSnapSyncStorageComplete(false))
              .and(stateIngest.appState.putSnapSyncBytecodeComplete(false))
              .and(stateIngest.appState.putSnapSyncStorageFilePath(""))
              .and(stateIngest.appState.putSnapSyncCodeHashesPath(""))
              .and(stateIngest.appState.putSnapSyncStorageFileCount(None))
              .and(stateIngest.appState.putSnapSyncCodeHashesCount(None))
              .commit()

          // #1555 (PoS): a heal-only recovery (every download phase complete) must not resume healing on a pivot that
          // just failed finalization (Platåberget 2026-10-10: a frozen CL kept the heal on root 450887ee at 358880, no
          // peer served it, the Path publish failed and this branch resumed on 358880 again, every 17-24 minutes), nor
          // on one the CL head shows to be past maxPivotStalenessBlocks. Right after a restart no snap peer has been
          // polled, so the peer-based drift check below sees networkBest = 0; the CL head SyncController primes before
          // Start is the authoritative tip on PoS. Only this heal-only case uses it: with downloads incomplete, a
          // CL-based drift must not trigger the wipe below (that would discard complete accounts that an in-place
          // pivot refresh keeps). ETC/pre-merge (isPoSChain = false): unchanged.
          val clHeadForRecovery: Option[BigInt] =
            if isPoSChain then clPivotHint.flatMap(_.knownHeader).map(_.number.value) else None
          if isPoSChain && !belowEscalationHint && !taskFilesUnusable &&
            stateIngest.appState.isSnapSyncStorageComplete() && stateIngest.appState.isSnapSyncBytecodeComplete()
          then
            val failedHere = failedFinalizationPivot.exists(pivot <= _)
            val clDrift = clHeadForRecovery.map(_ - pivot)
            if failedHere then
              healAtNewerPivot(
                failedFinalizationPivot.fold(pivot)(_.max(pivot)),
                s"finalization failed at pivot ${failedFinalizationPivot.getOrElse(pivot)}"
              )
            else if clDrift.exists(_ > snapSyncConfig.maxPivotStalenessBlocks) then
              healAtNewerPivot(
                pivot,
                s"the CL head ${clHeadForRecovery.getOrElse("?")} is ${clDrift.getOrElse("?")} blocks ahead " +
                  s"(> maxPivotStalenessBlocks=${snapSyncConfig.maxPivotStalenessBlocks})"
              )
          else if failedFinalizationPivot.exists(pivot <= _) then
            ctx.log.warn(
              s"Recovery: pivot $pivot failed finalization, but it is resumed: " +
                (if !isPoSChain then "not a PoS chain (ETC keeps resuming on the saved pivot)"
                 else "a download phase is incomplete or the saved state is being discarded") + "."
            )

          // Check if pivot is still fresh enough (skipped when belowEscalationHint forced clear)
          val networkBest = currentNetworkBestFromSnapPeers().getOrElse(BigInt(0))
          val drift = if networkBest > 0 then (networkBest - pivot).abs else BigInt(0)
          if !belowEscalationHint && !taskFilesUnusable && networkBest > 0 && drift > snapSyncConfig.maxPivotStalenessBlocks
          then
            val storageAlreadyDone = stateIngest.appState.isSnapSyncStorageComplete()
            val bytecodeAlreadyDone = stateIngest.appState.isSnapSyncBytecodeComplete()
            if storageAlreadyDone && bytecodeAlreadyDone then
              // All data phases complete — content-addressed data is valid across pivot changes.
              // Match go-ethereum/Besu: do NOT wipe. Bootstrap acquires a fresh pivot and the
              // BootstrapComplete handler detects all-phases-complete → skips to StateHealing.
              ctx.log.warn(
                s"Recovery: pivot $pivot drifted $drift blocks, but all phases complete. " +
                  "Requesting fresh pivot for healing (go-ethereum/Besu behavior)."
              )
              accountsComplete = true
              storagePhaseComplete = true
              bytecodePhaseComplete = true
              // Leave pivotBlock and stateRoot unset — bootstrap will set them.
            else
              ctx.log.warn(
                s"Recovery: pivot $pivot drifted $drift blocks from network best $networkBest. " +
                  "Clearing accounts-complete flag and restarting fresh."
              )
              stateIngest.appState
                .putSnapSyncAccountsComplete(false)
                .and(stateIngest.appState.putSnapSyncStorageComplete(false))
                .and(stateIngest.appState.putSnapSyncBytecodeComplete(false))
                .commit()
              // Fall through to normal startup
          else if !belowEscalationHint && !taskFilesUnusable then
            // Pivot is fresh enough — recover bytecodes + storage only
            pivotBlock = Some(pivot)
            stateRoot = Some(TrieRoot(rootBs))
            accountsComplete = true

            val storageAlreadyDone = stateIngest.appState.isSnapSyncStorageComplete()
            val bytecodeAlreadyDone = stateIngest.appState.isSnapSyncBytecodeComplete()
            storagePhaseComplete = storageAlreadyDone
            bytecodePhaseComplete = bytecodeAlreadyDone
            reset(PhaseFlags.ResetKind.Start)

            if storageAlreadyDone then ctx.log.info("Recovery: storage phase already complete — skipping re-download")
            if bytecodeAlreadyDone then ctx.log.info("Recovery: bytecode phase already complete — skipping re-download")

            // #1555: with no snap peer polled yet the drift is unknown, not 0.
            val driftText =
              if networkBest > 0 then s"drift=$drift blocks"
              else
                "drift unknown: no snap peer polled yet" +
                  clHeadForRecovery.fold("")(h => s"; CL head $h is ${h - pivot} blocks ahead")
            ctx.log.info(s"Recovery: resuming bytecodes + storage sync from pivot $pivot ($driftText)")

            val storage = getOrCreateMptStorage(pivot)
            coordinatorGeneration += 1
            ensureHeapWatchdog()

            spawnRecoveryCoordinators(rootBs, storage, bytecodeAlreadyDone, storageAlreadyDone)

            // Stream storage tasks from the persisted file. If it is missing or damaged, re-derive the tasks from the
            // account trie. Storage is never marked complete here without having been downloaded.
            // Spec 014: read in bounded chunks and only while the intake gate is open. The previous single Future
            // pushed the whole file (12M entries on Sepolia) into the coordinator's mailbox at once.
            if !storageAlreadyDone then
              val coordinator = storageRangeCoordinator.get
              import ctx.executionContext
              // Usable per the pre-check above: whole 64-byte entries, non-empty.
              val filePath = java.nio.file.Paths.get(savedStoragePath.get)
              val emptyRoot = ByteString(com.chipprbots.ethereum.mpt.MerklePatriciaTrie.EmptyRootHash)
              val zeroHash = ByteString(new Array[Byte](32))
              val replay = new GatedTaskFileReplay(
                path = filePath,
                entrySize = StorageTaskFile.EntrySize,
                endEntry = java.nio.file.Files.size(filePath) / StorageTaskFile.EntrySize,
                chunkEntries = GatedTaskFileReplay.DefaultChunkEntries,
                gate = () => intakeBudget.intakeBlockedReason()
              )
              var totalTasks = 0L
              var skippedFinished = 0L
              runGatedReplay(replay, s"storage-task file $filePath") { entries =>
                val batch = entries.flatMap { entry =>
                  val accountHash = ByteString(java.util.Arrays.copyOfRange(entry, 0, 32))
                  val storageRoot = ByteString(java.util.Arrays.copyOfRange(entry, 32, 64))
                  Option.when(accountHash != zeroHash && storageRoot != emptyRoot)(
                    StorageTask.createStorageTask(accountHash, storageRoot)
                  )
                }
                // Tasks finished before the restart carry a completion marker: re-queue only the rest.
                val unfinished =
                  if recordStorageDone then storageDoneStorage.unfinished(batch)(t => (t.accountHash, t.storageRoot))
                  else batch
                skippedFinished += batch.size - unfinished.size
                if unfinished.nonEmpty then
                  intakeBudget.reserve(unfinished.size, 0)
                  coordinator ! actors.StorageRangeCoordinator.AddStorageTasks(unfinished)
                  totalTasks += unfinished.size
              }.onComplete {
                case scala.util.Success(_) =>
                  asyncLog.info(
                    s"Recovery: streamed $totalTasks storage tasks from ${filePath} " +
                      s"($skippedFinished skipped as already finished)"
                  )
                  // Signal no more tasks — sentinel allows completion
                  coordinator ! actors.StorageRangeCoordinator.NoMoreStorageTasks
                case scala.util.Failure(e) =>
                  // No NoMoreStorageTasks: storage cannot complete on a partial stream (the stagnation watchdog acts).
                  asyncLog.error(
                    s"Recovery: storage-task stream from $filePath FAILED at entry ${replay.position}: ${e.getMessage}",
                    e
                  )
              }

            // Bytecodes: stream codeHashes from persisted file if available. Each entry is 32 bytes
            // (raw keccak256 hash, written by AccountRangeCoordinator.uniqueCodeHashesOut). Same gated chunking.
            val savedCodeHashesPath = stateIngest.appState.getSnapSyncCodeHashesPath()
            if !bytecodeAlreadyDone then
              savedCodeHashesPath.foreach { pathStr =>
                // Usable per the pre-check above (non-empty, whole 32-byte entries).
                val filePath = java.nio.file.Paths.get(pathStr)
                val coordinator = bytecodeCoordinator.get
                import ctx.executionContext
                val replay = new GatedTaskFileReplay(
                  path = filePath,
                  entrySize = StorageTaskFile.CodeHashEntrySize,
                  endEntry = java.nio.file.Files.size(filePath) / StorageTaskFile.CodeHashEntrySize,
                  chunkEntries = GatedTaskFileReplay.DefaultChunkEntries,
                  gate = () => intakeBudget.intakeBlockedReason()
                )
                var totalHashes = 0L
                var alreadyPresent = 0L
                runGatedReplay(replay, s"codeHash file $filePath") { entries =>
                  // Bytecode is content-addressed: a codeHash already in EvmCodeStorage was fetched (and
                  // hash-checked) before the restart. Re-queue only the missing ones, as the carried replay does.
                  // This runs on the replay's Future, off the controller thread.
                  val missing = entries.map(ByteString(_)).filter(h => stateIngest.bytecode.get(h).isEmpty)
                  alreadyPresent += entries.size - missing.size
                  if missing.nonEmpty then
                    intakeBudget.reserve(0, missing.size)
                    coordinator ! actors.ByteCodeCoordinator.AddByteCodeTasks(missing)
                    totalHashes += missing.size
                }.onComplete {
                  case scala.util.Success(_) =>
                    asyncLog.info(
                      s"Recovery: streamed $totalHashes codeHashes from ${filePath} for bytecode sync " +
                        s"($alreadyPresent already present)"
                    )
                    coordinator ! actors.ByteCodeCoordinator.NoMoreByteCodeTasks
                  case scala.util.Failure(e) =>
                    asyncLog.error(
                      s"Recovery: codeHash stream from $filePath FAILED at entry ${replay.position}: ${e.getMessage}",
                      e
                    )
                }
              }

            currentPhase = ByteCodeAndStorageSync
            resetStagnationClocks(StagnationWatchdog.ResetReason.AccountsCompleteRecovery)
            scheduleStagnationChecks()
            progressMonitor.startPhase(ByteCodeAndStorageSync)

            // Same as fresh ByteCode phase start: raise budget + clear cooldowns so peers on 2-min
            // backoff from AccountRange don't block ByteCode dispatch on resume.
            bytecodeCoordinator.foreach(_ ! actors.ByteCodeCoordinator.UpdateMaxInFlightPerPeer(8))
            bytecodeCoordinator.foreach(_ ! actors.ByteCodeCoordinator.ByteCodePivotRefreshed)
            requestByteCodes()

            // Start parallel chain download during recovery too
            startChainDownloader()
            startSnapServerPeersScheduler()

            // If both phases were already complete, advance to healing immediately
            checkAllDownloadsComplete()
            break(syncing())
        case _ =>
          ctx.log.warn("Recovery: accounts-complete flag set but missing pivot/root. Clearing and restarting fresh.")
          stateIngest.appState
            .putSnapSyncAccountsComplete(false)
            .and(stateIngest.appState.putSnapSyncStorageComplete(false))
            .and(stateIngest.appState.putSnapSyncBytecodeComplete(false))
            .commit()

  /** The account phase's resume selection (spec 016 lift before M11b, CQ-SNAP-016-33), extracted verbatim from
    * `launchAccountRangeWorkers`, which calls it at the same point: the versioned resume checkpoint, then the progress
    * store, then the legacy record (each subject to the `MaxPreservedPivotDistance` drift cap), the drift check on the
    * preserved cursors, the `resumedStaleCursors` latch, the carried contract task files and the storage-done marker
    * clear. It returns the cursors to resume from and the task files to carry; the account coordinator spawn and
    * everything after it stay in `launchAccountRangeWorkers`. `currentPivot` is the caller's value, read once.
    */
  def selectAccountResume(
      rootHash: TrieRoot,
      effectiveConcurrency: Int,
      currentPivot: BigInt
  ): (Map[ByteString, ByteString], Option[ContractTaskFiles]) =
    // Try disk recovery first (cross-process restart), then fall back to in-memory.
    // Primary source: SnapSyncProgressStorage (namespace 'p', JSON, account + storage cursors).
    // Migration fallback: AppStateStorage plain-text (namespace 's', account-only, written by older builds).
    //
    // First choice since 2026-10-06: the versioned account resume checkpoint (fixed key, cursors + contract task
    // files). Anything unusable about it — other version, missing/short task file, range layout that does not match
    // this concurrency, drift past the cap — is logged and the record dropped; the phase then falls back to the legacy
    // record / a fresh start. It never resumes partially.
    if preservedRangeProgress.isEmpty then
      stateIngest.appState.getSnapAccountResumeCheckpoint().foreach { json =>
        AccountResumeCheckpoint
          .decode(json)
          .flatMap { cp =>
            SNAPSyncController
              .checkResumable(cp.cursors, cp.taskFiles, effectiveConcurrency)
              .flatMap { _ =>
                val drift = (currentPivot - cp.pivotBlock).abs
                if drift <= MaxPreservedPivotDistance then Right(cp)
                else Left(s"pivot drifted $drift blocks (>$MaxPreservedPivotDistance)")
              }
          } match
          case Right(cp) =>
            ctx.log.info(
              s"Recovered account resume checkpoint: ${cp.cursors.size} ranges from pivot ${cp.pivotBlock} " +
                s"(current=$currentPivot), ${cp.taskFiles.storageCount} storage-task entries, " +
                s"${cp.taskFiles.codeHashesCount} codeHashes"
            )
            preservedRangeProgress = cp.cursors
            preservedTaskFiles = Some(cp.taskFiles)
            preservedAtPivotBlock = Some(cp.pivotBlock)
          case Left(why) =>
            ctx.log.warn(s"Ignoring account resume checkpoint: $why. The account phase does not resume from it.")
            stateIngest.appState.removeSnapAccountResumeCheckpoint().commit()
      }
    if preservedRangeProgress.isEmpty then
      snapProgressStorage.readProgress(rootHash.value) match
        case Some(saved) if saved.accountCursors.nonEmpty =>
          val savedPivot = BigInt(saved.pivotBlock)
          if (currentPivot - savedPivot).abs <= MaxPreservedPivotDistance then
            val ranges = saved.accountCursors.flatMap { case (lastHex, nextHex) =>
              for
                lastBs <- Try(ByteString(Hex.decode(lastHex))).toOption
                nextBs <- Try(ByteString(Hex.decode(nextHex))).toOption
              yield lastBs -> nextBs
            }
            ctx.log.info(
              s"Recovered ${ranges.size} account ranges from SnapSyncProgressStorage " +
                s"(saved pivot=$savedPivot, current=$currentPivot, drift=${(currentPivot - savedPivot).abs})"
            )
            preservedRangeProgress = ranges
            preservedAtPivotBlock = Some(savedPivot)
          else if saved.accountCursors.nonEmpty then
            ctx.log.info(
              s"Discarding stale SnapSyncProgressStorage: pivot drifted ${(currentPivot - savedPivot).abs} blocks " +
                s"(>${MaxPreservedPivotDistance})"
            )
        case _ =>
          // Migration fallback: read legacy AppStateStorage plain-text format (one-time, older builds)
          stateIngest.appState.getSnapSyncProgress().foreach { saved =>
            deserializeSnapProgress(saved).foreach { case (savedPivot, savedRanges) =>
              if savedRanges.nonEmpty && (currentPivot - savedPivot).abs <= MaxPreservedPivotDistance then
                ctx.log.info(
                  s"Migrated ${savedRanges.size} account ranges from legacy AppStateStorage " +
                    s"(saved pivot=$savedPivot, current=$currentPivot, drift=${(currentPivot - savedPivot).abs})"
                )
                preservedRangeProgress = savedRanges
                preservedAtPivotBlock = Some(savedPivot)
                // Write to new storage immediately so subsequent restarts use the new format
                snapProgressStorage.writeAccountCursors(
                  rootHash.value,
                  savedPivot.toLong,
                  savedRanges.map { case (k, v) => k.toHex -> v.toHex }
                )
              else if savedRanges.nonEmpty then
                ctx.log.info(
                  s"Discarding stale legacy AppStateStorage progress: pivot drifted " +
                    s"${(currentPivot - savedPivot).abs} blocks (>${MaxPreservedPivotDistance})"
                )
            }
          }

    val resumeProgress: Map[ByteString, ByteString] = preservedAtPivotBlock match
      case Some(prevPivot) if (currentPivot - prevPivot).abs <= MaxPreservedPivotDistance =>
        if preservedRangeProgress.nonEmpty then
          ctx.log.info(
            s"Resuming ${preservedRangeProgress.size} account ranges from pivot $prevPivot " +
              s"(current pivot=$currentPivot, drift=${(currentPivot - prevPivot).abs} blocks)"
          )
        preservedRangeProgress
      case Some(prevPivot) =>
        ctx.log.info(
          s"Pivot drifted ${(currentPivot - prevPivot).abs} blocks (>${MaxPreservedPivotDistance}), " +
            s"clearing ${preservedRangeProgress.size} preserved ranges"
        )
        preservedRangeProgress = Map.empty
        preservedAtPivotBlock = None
        preservedTaskFiles = None
        snapProgressStorage.clearProgress(rootHash.value)
        stateIngest.appState.removeSnapAccountResumeCheckpoint().commit()
        Map.empty
      case None =>
        Map.empty

    // Resuming cursors from a prior session ⇒ force the healing walk at completion (see flag decl).
    // Latch: once set, never cleared for the life of this process.
    if resumeProgress.nonEmpty then resumedStaleCursors = true

    // Carry the contract task files only when they are provably consistent with the cursors; otherwise partial
    // ranges fall back to re-downloading from their start (never a partial resume without its contract work).
    val carriedTaskFiles: Option[ContractTaskFiles] =
      if resumeProgress.isEmpty then None
      else
        preservedTaskFiles.flatMap { files =>
          SNAPSyncController.checkResumable(resumeProgress, files, effectiveConcurrency) match
            case Right(()) => Some(files)
            case Left(why) =>
              ctx.log.warn(s"Not carrying contract task files ($why): partial ranges re-download from their start")
              None
        }

    // Without carried task files the account phase re-identifies every contract from scratch and nothing consults
    // the completion markers; drop them so a later resume never trusts markers from before this point (an earlier SNAP
    // cycle, whose storage tries may since have been rewritten by healing or block import).
    // A superseded storage coordinator that is still flushing may write a marker after this clear: it is keyed by
    // (account, storageRoot) and states a download this cycle really finished, so it can only skip that exact task.
    if carriedTaskFiles.isEmpty then clearStorageDoneMarkers("account phase starts without carried task files")

    (resumeProgress, carriedTaskFiles)

  // Storage-file path of the last persisted checkpoint for which superseded task files were already swept.
  private var lastSweptForRecord: Option[String] = None

  /** A `commonSyncingArms` arm lifted into a class member (spec 016 T053c, CQ-SNAP-016-36), body unchanged:
    * `AccountRangeProgressCmd`, the account coordinator's progress snapshot: the superseded-generation and
    * accounts-complete drops, the `preserved*` update and the durable `AccountResumeCheckpoint` write with its
    * once-per-record task-file sweep. `commonSyncingArms` delegates to it at the arm's original position, so the arm
    * order is unchanged.
    */
  private[snap] lazy val accountProgressCheckpointArms: PartialFunction[Command, Behavior[Command]] = {
    case AccountRangeProgressCmd(progress, taskFiles, durable, generation) =>
      if generation < launchedAccountGeneration then
        // A superseded coordinator's PostStop snapshot arriving after its successor launched: the successor already
        // carries newer (or equal) progress, and this one may describe files the successor does not own.
        ctx.log.debug(s"Dropping account progress snapshot from superseded coordinator generation $generation")
      else if accountsComplete then
        // The account phase is done and its checkpoint cleared; a late PostStop snapshot must not resurrect it.
        ctx.log.debug("Dropping account progress snapshot after accounts complete")
      else
        preservedRangeProgress = progress
        preservedTaskFiles = taskFiles
        // Track the pivot the cursors were last advanced against. Drift is a perf heuristic only (see
        // MaxPreservedPivotDistance); measuring it from the FIRST pivot of a multi-day account phase would discard
        // every partial range once the phase itself outlasted the cap.
        pivotBlock.orElse(preservedAtPivotBlock).foreach(p => preservedAtPivotBlock = Some(p))
        if durable then
          val completedCount = progress.count { case (last, next) =>
            // A range is "complete" when next >= last (entire keyspace traversed)
            BigInt(1, next.toArray.padTo(32, 0.toByte)) >= BigInt(1, last.toArray.padTo(32, 0.toByte))
          }
          // Persist the versioned checkpoint under a FIXED key (not keyed by root): a process restart selects a new
          // pivot, and a root-keyed record was never found again (2026-10-05 22:12 restart began from zero).
          (stateRoot, preservedAtPivotBlock, taskFiles) match
            case (Some(sr), Some(pivot), Some(files)) =>
              stateIngest.appState
                .putSnapAccountResumeCheckpoint(
                  AccountResumeCheckpoint.encode(AccountResumeCheckpoint(sr.value, pivot, progress, files))
                )
                .commit()
              ctx.log.info(
                s"Account resume checkpoint persisted: ${progress.size} ranges ($completedCount fully complete), " +
                  s"${files.storageCount} storage-task entries, ${files.codeHashesCount} codeHashes, pivot $pivot"
              )
              // The persisted record now references this coordinator's own files, so the files it was carried
              // from (and any older ones) are no longer needed for a resume. Delete them only NOW — never before
              // this commit, so a crash between the copy and here still resumes from the previous record. Keep the
              // carry source of the live generation (a supervisor restart re-copies from it), whatever the
              // in-memory snapshot points at, and the accounts-complete handoff files.
              if !lastSweptForRecord.contains(files.storagePath) then
                lastSweptForRecord = Some(files.storagePath)
                sweepSupersededTaskFiles(
                  keep = SNAPSyncController.taskFilePaths(files) ++
                    currentCarrySource.toSet.flatMap(SNAPSyncController.taskFilePaths) ++
                    preservedTaskFiles.toSet.flatMap(SNAPSyncController.taskFilePaths) ++
                    accountsCompleteTaskFilePaths,
                  reason = "new account resume checkpoint"
                )
            case _ =>
              // Expected for the PostStop snapshot of a coordinator stopped by restartSnapSync (root already
              // cleared); the progress is kept in memory and persisted by the next coordinator's checkpoint.
              ctx.log.info(
                s"Account resume checkpoint kept in memory only (stateRoot=${stateRoot.isDefined}, " +
                  s"pivot=${preservedAtPivotBlock.isDefined}, taskFiles=${taskFiles.isDefined})"
              )
      Behaviors.same
  }
