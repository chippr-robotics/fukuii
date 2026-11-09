package com.chipprbots.ethereum.blockchain.sync.snap.controller

import org.apache.pekko.actor.typed.ActorRef as TypedActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.PostStop
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.util.ByteString

import scala.collection.mutable

import com.chipprbots.ethereum.blockchain.sync.Blacklist
import com.chipprbots.ethereum.blockchain.sync.HeadChainWeight
import com.chipprbots.ethereum.blockchain.sync.StoredBodyCheck
import com.chipprbots.ethereum.blockchain.sync.SyncProtocol
import com.chipprbots.ethereum.blockchain.sync.snap.ChainDownloader
import com.chipprbots.ethereum.blockchain.sync.snap.HeaderChainCheck
import com.chipprbots.ethereum.blockchain.sync.snap.PathToHashExporter
import com.chipprbots.ethereum.blockchain.sync.snap.PivotAnchor
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.*
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.SyncPhase.*
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncMetrics
import com.chipprbots.ethereum.db.storage.RefCountTrust
import com.chipprbots.ethereum.db.storage.StateStorage
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.utils.ByteStringUtils.toHex

private[snap] trait SnapFinalizationState:
  var chainDownloadComplete: Boolean
  var headerHold: Option[ByteString]
  var pathPublish: Option[(BigInt, ByteString)]
  var coordinatorGeneration: Long
  def healedCodeHashes: mutable.LinkedHashSet[ByteString]
  def chainDownloaderReplyAdapter: TypedActorRef[ChainDownloader.Done.type]

  /** The configured terminal total difficulty (None on ETC, Mordor), for [[HeadChainWeight.posFromGenesisWeight]]. */
  def terminalTotalDifficulty: Option[BigInt]

  /** The check the header download applies to each header it stores (HeaderChainCheck.forChain). */
  def headerChainCheck: HeaderChainCheck

  /** The node's shared sync blacklist; the header download adds a peer whose header failed validation to it. */
  def sharedBlacklist: Blacklist

private[snap] trait FinalizationApi:
  def completeSnapSync(): Behavior[Command]

private[snap] trait ChainDownloadStartApi:
  def startChainDownloader(): Unit

private[snap] trait ShutdownApi:
  def onStop(): Unit

/** SNAP finalization (spec 016 M4, research.md R6 "Finalization / header-hold / path-publish / backfill handoff"): the
  * completion entry (`completeSnapSync`, with the healed-code hold), the deferred-backfill header hold
  * (`enterHeaderHold`, `leaveHeaderHold`), the anchor and hand-off (`finalizeSnapSync`), the Path-scheme publish
  * (`startPathPublish`, `onPathPublishDone`), the ChainDownloader launch and release (`startChainDownloader`,
  * `launchChainDownloader`, `releaseDeferredBodiesAndReceipts`) and the terminal behaviours (`completedWithBackfill`,
  * `completed`). The core keeps the dispatch: the path-publish and header-hold guard arms of `syncing` and its
  * ChainDownloader arms call these members.
  */
private[snap] trait SnapFinalization extends FinalizationApi with ChainDownloadStartApi:
  self: SnapFinalizationState & SnapSharedState & SnapControllerEnv & CoordinatorHandles & PhaseFlags & ResumeApi &
    TaskFileSweepApi & HealedCodeApi & PeerPoolApi & ShutdownApi & SnapStopApi =>

  private var lastHeaderHoldWarnMs: Long = 0L
  // Stall watchdog for the hold: highest header cursor seen and when it last advanced.
  private var holdLastCursor: BigInt = 0
  private var holdLastAdvanceMs: Long = 0L
  // The pivot whose anchor check last came out undecided, so the hold tick logs that once per pivot.
  private var anchorUndecidedLoggedFor: Option[BigInt] = None
  // A pivot the consensus layer chose (HeaderChainCheck.trustsPinnedPivot): a rewind of the header download sent and not
  // yet reported applied (ChainDownloader.Rewound). While set the hold does not check the anchor. `escalate`: restart
  // SNAP once it is applied (the pinned pivot diverged a second time).
  private var rewindInFlight: Option[(BigInt, Boolean)] = None
  // The pinned pivot hashes the header download was already rewound for, in this controller's life.
  private var rewoundForPins: Set[ByteString] = Set.empty
  // The pivot whose Path-scheme publish completed (onPathPublishDone), so the hold tick re-enters finalisation past it.
  private var pathPublishedFor: Option[BigInt] = None

  def completed(): Behavior[Command] =
    Behaviors
      .receiveMessage[Command] {
        case GetStatus(replyTo) =>
          replyTo ! SyncProtocol.Status.SyncDone
          Behaviors.same

        case GetProgress(replyTo) =>
          replyTo ! progressMonitor.currentProgress
          Behaviors.same

        case _ =>
          ctx.log.debug("SNAP sync is complete, ignoring messages")
          Behaviors.same
      }
      .receiveSignal { case (_, PostStop) =>
        onStop(); Behaviors.same
      }

  /** State sync + healing + validation finished — anchor the pivot and hand off to regular sync immediately.
    *
    * Historical chain backfill (genesis → pivot) is decoupled: we do not block here waiting for it. Instead,
    * `finalizeSnapSync()` emits `SnapSyncFinalized(pivot)` to the parent (which starts RegularSync) and either:
    *   - emits `Done` immediately if backfill is disabled or already complete, or
    *   - keeps the controller alive in `completedWithBackfill` so it can forward `ChainDownloader.Progress` /
    *     `ChainDownloader.Done` to the parent without blocking forward sync.
    *
    * Closes #1162.
    */
  def completeSnapSync(): Behavior[Command] =
    // Diagnostic (Platåberget soak, 2026-09-27): the ONLY way to know, after the fact, which of the four
    // completeSnapSync() call sites fired and whether the in-memory pivot/root already matched the persisted
    // anchor at that moment — without this, the shipped logback.xml silenced every INFO/WARN line from this
    // actor's real runtime class (the controller's private impl class), leaving only the eventual A5 ERROR as evidence.
    ctx.log.info(
      "[SNAP-COMPLETE] phase={} pivot={} inMemoryRoot={} persistedRoot={}",
      currentPhase,
      pivotBlock.getOrElse("none"),
      stateRoot.map(_.value.toHex.take(16)).getOrElse("none"),
      stateIngest.appState.getSnapSyncStateRoot().map(_.toHex.take(16)).getOrElse("none")
    )
    // spec 022 D4, T037: no hold for bytecode here any more. A healing completion means every healed leaf's code is in
    // (the leaf waited on it); what reaches this point with code missing is a lazy hand-off, which claims no completeness:
    // `bytecodeRecoveryDone` stays unset below, and the importer fetches the code on demand meanwhile.
    pivotBlock.map(p => finalizeSnapSync(p)).getOrElse(Behaviors.same)

  /** Anchor the pivot, mark SNAP state done, and hand off to the parent. Always emits `SnapSyncFinalized(pivot)`. Emits
    * `Done` either immediately (no backfill in flight) or later from `completedWithBackfill` after
    * `ChainDownloader.Done` arrives. SNAP-only schedules are cancelled before the handoff so eviction tickers and
    * stagnation checks don't keep firing while regular sync owns the peer pool.
    */
  /** Hold finalisation for the forward header download. While held, every message except status/progress, the hold tick
    * and the downloader's reports is dropped (like the path-publish window): state is already complete here, so the
    * stagnation checks, pivot rolls, re-pegs and healing/bytecode timers that could otherwise fire (and cycle
    * `restartSnapSync`) have nothing to do and must not run. The downloader is an independent actor and keeps
    * dispatching headers; the tick re-runs finalisation, which re-reads the pivot and the cursor.
    */
  private def enterHeaderHold(pivot: BigInt, pivotHash: ByteString, cursor: BigInt): Unit =
    val now = System.currentTimeMillis()
    if headerHold.isEmpty then
      ctx.log.info(
        s"Holding SNAP finalisation at pivot $pivot until the header download reaches it (cursor=$cursor); " +
          "state sync is complete, bodies and receipts stay deferred"
      )
      timers.startTimerWithFixedDelay(
        SNAPSyncController.HeaderHoldTimerKey,
        HeaderHoldTick,
        SNAPSyncController.HeaderHoldTickInterval
      )
      if chainDownloader.isEmpty then startChainDownloader()
      chainDownloader.tell(ChainDownloader.UpdateTarget(pivot)) // no-op unless the pivot is above its target
      chainDownloader.tell(ChainDownloader.Resume) // no-op unless paused for a pivot bootstrap that no longer matters
      boostHeaderDownload()
      lastHeaderHoldWarnMs = now
      holdLastCursor = cursor
      holdLastAdvanceMs = now
    else if headerHold.exists(_ != pivotHash) then
      ctx.log.info(s"Header hold: pivot changed to $pivot (cursor=$cursor)")
      chainDownloader.tell(ChainDownloader.UpdateTarget(pivot))
    // ANY change counts as progress (a cursor briefly pulled back after a respawn is not a stall).
    if cursor != holdLastCursor then
      holdLastCursor = cursor
      holdLastAdvanceMs = now
    else if now - holdLastAdvanceMs >= snapSyncConfig.headerHoldStallTimeout.toMillis then
      // Never finalise without the headers: restart the downloader (same flags, target = pivot) and keep holding.
      ctx.log.error(
        s"SNAP finalisation hold: header cursor stuck at $cursor (pivot $pivot) for " +
          s"${(now - holdLastAdvanceMs) / 1000}s; restarting the chain downloader"
      )
      // A rewind sent to the stopped downloader may never be applied or reported: the next tick checks the anchor again.
      rewindInFlight = None
      chainDownloader.stop()
      startChainDownloader()
      boostHeaderDownload() // a respawned downloader starts at the SNAP-era budget
      holdLastAdvanceMs = now
    if now - lastHeaderHoldWarnMs >= SNAPSyncController.HeaderHoldWarnIntervalMs then
      lastHeaderHoldWarnMs = now
      ctx.log.warn(
        s"SNAP finalisation held for the header download: cursor=$cursor pivot=$pivot gap=${pivot - cursor} " +
          s"lastKnownPeers=${peersToDownloadFrom.size} (peer events are not processed during the hold)"
      )
    headerHold = Some(pivotHash)

  /** The hold's tick: finalisation again. Past the Path-scheme publish only if it completed for this pivot: where the
    * hold comes first (a validated header chain), the publish has not run yet when the hold ends.
    */
  private[snap] def onHeaderHoldTick(pivot: BigInt): Behavior[Command] =
    finalizeSnapSync(pivot, pathPublished = pathPublishedFor.contains(pivot))

  private def leaveHeaderHold(): Unit =
    if headerHold.isDefined then
      headerHold = None
      timers.cancel(SNAPSyncController.HeaderHoldTimerKey)
      ctx.log.info("Header download reached the pivot; finalising SNAP")

  /** During the hold, state is complete and regular sync has not started, so every peer is free: the downloader gets
    * `chain-download-boosted-concurrent-requests` instead of the SNAP-era budget, and its dispatch tick drops from 2 s
    * to 200 ms (#1654). Headers still go one range at a time (`ChainDownloader.dispatchRequests`), so the extra slots
    * re-send a pending range to another peer sooner; they do not fetch ranges in parallel. `finalizeSnapSync` lowers
    * the budget to `chain-backfill-concurrent-requests` when it hands off.
    */
  private def boostHeaderDownload(): Unit =
    chainDownloader.tell(ChainDownloader.BoostConcurrency(snapSyncConfig.chainDownloadBoostedConcurrentRequests))

  private[snap] def finalizeSnapSync(pivot: BigInt, pathPublished: Boolean = false): Behavior[Command] =
    import scala.util.boundary, boundary.break
    boundary[Behavior[Command]] {
      // Look up the pivot header so we can store a complete "best block" anchor.
      // RegularSync's BranchResolution needs: header, body, number→hash mapping,
      // ChainWeight, and BestBlockInfo (hash + number) to accept blocks that chain
      // from the pivot.
      //
      // Where the header download validates the chain (proof of work), the pivot header is the downloaded chain's, taken
      // after the download reaches the pivot and checked against the persisted pivot (anchoredPivotHeader). The number
      // index is not consulted there: the pivot bootstrap writes it with headers nothing validated.
      val pivotHeaderOpt =
        if headerChainCheck.validatesChain then Some(anchoredPivotHeader(pivot))
        else stateIngest.chainReader.getBlockHeaderByNumber(pivot)
      pivotHeaderOpt match
        case Some(pivotHeader) =>
          // A5: Root match guard — snapStateRoot must equal pivotHeader.stateRoot before
          // marking sync done. Mirrors Besu SnapWorldDownloadState.saveWorldState() implicit
          // verification. If they diverge (BUG-008 class), restart SNAP rather than committing
          // a broken state. (A validated chain: anchoredPivotHeader has compared the state root and the pinned hash.)
          if !headerChainCheck.validatesChain then
            stateIngest.appState.getSnapSyncStateRoot().foreach { snapRoot =>
              if snapRoot != pivotHeader.stateRoot.value then
                ctx.log.error(
                  "SNAP finalization aborted: snapStateRoot={} != pivotHeader.stateRoot={}. " +
                    "State trie root mismatch — escalating to SyncController for SNAP restart.",
                  snapRoot.toHex,
                  pivotHeader.stateRoot.value.toHex
                )
                leaveHeaderHold() // else the hold tick would re-run this guard and re-send HealingImpossible every 2 s
                syncController ! SyncProtocol.HealingImpossible
                break(Behaviors.same)
            }

          // Path scheme only: SNAP/healing wrote the trie path-keyed, but block execution reads hash-keyed nodes.
          // Publish the completed trie to the hash-keyed store BEFORE anchoring/handing off, so the first imported
          // block can read the pivot state. The scan is long (the whole state), so it runs on a blocking-dispatcher
          // Future and this actor keeps serving status/progress queries (`syncing` serves them while `pathPublish` is set); it re-enters here with
          // `pathPublished = true` once PathPublishDone arrives. Hash scheme (ETC) never enters this branch.
          if stateIngest.pathNodes.isDefined && !pathPublished then
            // #1555: never start the whole-state publish when the pivot root's node is stored nowhere: its verification
            // cannot pass, so escalate now instead of after 6-12 minutes of scanning. SyncController's restart then
            // heals at a newer pivot (PoS) rather than this one.
            if FinalizationPolicy.pathPivotRootAbsent(
                stateIngest.pathNodes.get,
                pivotHeader.stateRoot.value,
                getOrCreateMptStorage(pivot)
              )
            then
              ctx.log.error(
                "[PATH-PUBLISH] not publishing: pivot {} state root {} is not stored locally (Path scheme), so the " +
                  "publish could not verify it. Escalating to SyncController for SNAP restart.",
                pivot,
                pivotHeader.stateRoot.value.toHex
              )
              syncController ! SyncProtocol.HealingImpossible
              break(Behaviors.same)
            startPathPublish(pivot, pivotHeader.stateRoot.value)
            break(Behaviors.same)

          // Deferred backfill (switch on): hold until the forward header download has reached the pivot. That gives a
          // contiguous header chain through the pivot, so (a) BLOCKHASH(n) for the first 256 blocks above it, which
          // walks parentHash through stored headers (AncestorBlockHashes), is covered by construction, and (b) the
          // pivot's total difficulty below is the REAL accumulated one (REAL_DB_TD) instead of a peer interpolation
          // (calibratePivotTD scales a peer's TD by pivot/peerBlock over ETH68 peers only, falls back to the block
          // number with ETH69-only peers, and every block above the pivot inherits whatever is stored here).
          // The header cursor is written atomically with each stored header, so cursor >= pivot means contiguous.
          // (A validated chain: anchoredPivotHeader has already held for it.)
          if SNAPSyncController.chainBackfillDeferredToFinalization(snapSyncConfig) && !headerChainCheck.validatesChain
          then
            val cursor = stateIngest.appState.getBackfillBestHeader()
            if cursor < pivot then
              enterHeaderHold(pivot, pivotHeader.hash.value, cursor)
              break(Behaviors.same)
            else leaveHeaderHold()

          val pivotHash = pivotHeader.hash

          // Write the pivot header with its number→hash mapping (PivotHeaderBootstrap stored the header, but a pivot
          // refresh can have stored it by another path). The BODY is never invented here (CQ-020-07): this used to
          // write `BlockBody.empty`, which overwrote the body a newPayload had already stored for a CL-chosen pivot
          // (Ephemery 137,594 lost its 16 withdrawals) and, because ChainDownloader fetches only ABSENT bodies, was
          // never replaced. Keep a stored body that validates; derive it only when the header commits to an empty one;
          // otherwise leave it absent (the backfill target includes the pivot, so it is fetched and validated there).
          // A body-less best block is a state the readers already handle (getBestBlockHeader, #1201).
          stateIngest.chainWriter.storeBlockHeader(pivotHeader).commit()
          applyPivotBodyCheck(pivotHeader)

          // Store a ChainWeight so fork choice can weigh new blocks; every block above the pivot inherits it (#1654).
          // REAL_DB_TD only when the stored weight is exact (HeadChainWeight.classify): the parent's weight clears the
          // plausibility floor and the pivot's is the parent's plus its difficulty. The hold above guarantees that: the
          // downloader stores every header from genesis with its parent's weight plus its difficulty, in the same batch
          // as the cursor. Without the hold:
          //   - Derivable (the parent's weight is plausible, the pivot's is absent or not parent + difficulty): write the
          //     exact value. What was stored is PivotSelector's selection-time estimate.
          //   - NoExact (the parent has no plausible weight): an ESTIMATE, in this order: the selection-time estimate if
          //     it exceeds 1000x the genesis TD (which rejects a genesis or block-number proxy), a peer interpolation now,
          //     the block-number proxy. Before #1654 the first of these was logged as REAL_DB_TD.
          //   - NoExact on a chain that was PoS from genesis (Platåberget), even with the hold: the floor rejects every
          //     weight there, and at genesis difficulty 0 the 1000x check is `> 0`, so the cascade above replaced the
          //     header download's exact 0 with the pivot number. The genesis weight is exact at every height: it goes
          //     first (#1654).
          val genesisBlockTD: BigInt = stateIngest.chainReader
            .getChainWeightByHash(stateIngest.chainReader.genesisHeader.hash)
            .map(_.totalDifficulty.value)
            .getOrElse(stateIngest.chainReader.genesisHeader.difficulty.value)
          val reader = stateIngest.chainReader
          val (finalTD, tdSource) =
            HeadChainWeight.classify(reader, pivotHeader) match
              case HeadChainWeight.Status.Exact(stored) => (stored.totalDifficulty.value, "REAL_DB_TD")
              case HeadChainWeight.Status.Derivable(_, exact) =>
                (exact.totalDifficulty.value, "DERIVED_FROM_PARENT")
              case HeadChainWeight.Status.NoExact =>
                HeadChainWeight
                  .posFromGenesisWeight(terminalTotalDifficulty, reader)
                  .map(w => (w.totalDifficulty.value, "GENESIS_WEIGHT"))
                  .orElse(
                    reader
                      .getChainWeightByHash(pivotHash)
                      .map(_.totalDifficulty.value)
                      .filter(_ > genesisBlockTD * BigInt(1000))
                      .map(td => (td, "PIVOT_SELECTION_ESTIMATE"))
                  )
                  .orElse(calibratePivotTD(pivotHeader).map(td => (td, "PEER_INTERPOLATED_TD")))
                  .getOrElse((pivot.max(genesisBlockTD), "BLOCK_NUMBER_PROXY"))
          ctx.log.info("SNAP finalize pivot TD: block={} td={} source={}", pivot, finalTD, tdSource)
          stateIngest.chainWriter
            .storeChainWeight(
              pivotHash,
              ChainWeight.totalDifficultyOnly(finalTD)
            )
            .commit()

          // Set best block info with BOTH hash and number (putBestBlockNumber only
          // sets the number, leaving getBestBlockInfo().hash empty).
          stateIngest.appState
            .putBestBlockInfo(
              com.chipprbots.ethereum.domain.appstate.BlockInfo(pivotHash.value, pivot)
            )
            .commit()

          // D4: snapSyncDone written AFTER pivot header and best-block info are stored.
          // Pivot data is written first so a crash between writes leaves the node in a
          // recoverable state (D3 startup gate detects SnapSyncDone=true with unreachable
          // root and restarts SNAP). Mirrors Besu SnapSyncStatePersistenceManager ordering.
          //
          // All three completion flags (snapSyncDone + bytecodeRecoveryDone + storageRecoveryDone)
          // are written atomically in a single fsync-backed commit. A crash before this call
          // leaves all flags absent → startup retries SNAP. A crash after → all flags present
          // → startup proceeds directly to regular sync. There is no inconsistent half-written
          // state. commitSync() flushes the OS write buffer to disk before returning, eliminating
          // the ~5-30s dirty-writeback window that previously caused spurious SNAP-RECOVERY.
          //
          // `bytecodeRecoveryDone` is the claim "no account's bytecode is missing". Healing delivered accounts whose
          // bytecode may still be absent (a peer would not serve it within the wait): leave the flag unset so the
          // next start's recovery scan finds them. Previously it was set unconditionally, which is how a node came to
          // hold accounts with a codeHash and no code, forever.
          dropHealedCodeNowPresent()
          // spec 022 D5: what the excluded set still says at a lazy hand-off. Each flag claims only its own kind of entry,
          // and a set that cannot be read claims nothing.
          val progress = stateIngest.snapProgress
          val appState = stateIngest.appState
          val (accountsExcluded, codeExcluded) =
            try (progress.hasExcludedAccounts, progress.hasExcludedCode)
            catch
              case scala.util.control.NonFatal(e) =>
                ctx.log.error("[HEAL-EXCLUDED] Could not read the excluded set at finalisation: neither flag is set", e)
                (true, true)
          // Durability barrier (#1581). Under pruning.mode=inmemory the ingested (and Path-published) trie nodes live only
          // in the node cache, not in the WAL; flush them to the data source BEFORE the flags, so the flags' synced
          // commit (which syncs the WAL up to and including itself) cannot be recovered without the state it claims.
          // A no-op under archive and basic pruning, which write through. The WAL is on, so no memtable flush is needed.
          stateIngest.stateTries.forcePersist(StateStorage.SnapSyncFinalization)
          // spec 022 T043: a hand-off with no clean full walk of this root behind it, whichever route brought it here, takes
          // a marker stamped earlier. From here regular sync fetches what is missing on demand, one node at a time, each
          // present over children that may be absent. Before the flags: a crash in between only resumes SNAP unmarked.
          // spec 022 5d: a coordinator's clean decision on this root keeps it too, walk or no walk. It sets that record on
          // the clean route alone, so every hand-off still takes the marker here.
          if !stateRoot.exists(r => r.value == healingCleanWalkRoot.get() || r.value == healingDecidedRoot.get()) &&
            appState.isSnapHealInvariantPresent()
          then
            ctx.log.warn("[HEAL-INVARIANT] Handing off with no clean decision on this root: marker removed")
            stateIngest.appState.clearSnapHealInvariant().commitSync()
          // spec 022 T050a: a repair run, or a run that lost track, owes the recovery scan what no walk can vouch for, and
          // the flags say nothing of what it lost, so it withholds both: a failed task-file sweep is exactly the case where
          // unfinished accounts were never recorded, so an empty excluded set does not show them complete (forge W1). It
          // costs no second scan: the one pass looks at both.
          //
          // Read here, just before the batch that clears it, because `lostTrack` runs on other actors' threads and one
          // landing between this read and that batch is cleared unseen. That leaves a few instructions. It does not close
          // it: the key has no compare-and-clear. A key that cannot be read counts as owed.
          val codeScanOwed =
            try appState.getSnapCodeScanOwed()
            catch
              case scala.util.control.NonFatal(e) =>
                ctx.log.error("[HEAL-INVARIANT] Could not read whether a code scan is owed: taking it as owed", e)
                Some("the key could not be read")
          val bytecodeComplete =
            SNAPSyncController.bytecodeRecoveryComplete(healedCodeHashes.size, bytecodeForceCompleted) &&
              !codeExcluded && codeScanOwed.isEmpty
          val storageComplete = !accountsExcluded && codeScanOwed.isEmpty
          // The set stays: it is the only record of those accounts, and only a cycle that starts from nothing wipes it.
          // Withheld is written, not left out: a datadir SNAP has been through before can hold `true` from that cycle.
          // The scan's debt moves to these flags here, in one write, so nothing charges a later cycle for it.
          val storageFlag =
            if storageComplete then appState.storageRecoveryDone() else appState.clearStorageRecoveryDone()
          val codeFlag =
            if bytecodeComplete then appState.bytecodeRecoveryDone() else appState.clearBytecodeRecoveryDone()
          appState.snapSyncDone().and(storageFlag).and(codeFlag).and(appState.clearSnapCodeScanOwed()).commitSync()

          if !bytecodeComplete then
            ctx.log.warn(
              s"[HEAL-CODE] bytecode incomplete at finalisation (healed-account codeHashes missing: " +
                s"${healedCodeHashes.size}, bytecode phase force-completed: $bytecodeForceCompleted, " +
                s"code entries: $codeExcluded, scan owed: ${codeScanOwed.getOrElse("no")}) — " +
                "bytecodeRecoveryDone NOT set"
            )
          if !storageComplete then
            ctx.log.warn(
              s"[HEAL-EXCLUDED] storage not shown complete at finalisation (account entries: $accountsExcluded, " +
                s"scan owed: ${codeScanOwed.getOrElse("no")}) — storageRecoveryDone NOT set"
            )

          ctx.log.info(s"SNAP sync completed successfully at block $pivot (hash=${pivotHash.value.take(8).toHex})")

        case None =>
          // Fallback: shouldn't happen since PivotHeaderBootstrap stored the header
          ctx.log.warn(s"Pivot header for block $pivot not found in storage — setting best block number only")
          stateIngest.appState.putBestBlockNumber(pivot).commit()

      progressMonitor.complete()
      ctx.log.info(progressMonitor.currentProgress.toString)
      currentPhase = Completed

      // Cancel SNAP-only schedules before handing off so eviction tickers / stagnation checks stop
      // affecting peers while regular sync runs. Stop state-sync child coordinators too — they don't
      // self-stop on completion and would otherwise retain completed task buffers and worker actors
      // for the full background-backfill window. The chain downloader child is intentionally NOT stopped.
      stopSnapOnlySchedules()
      stopStateSyncChildren()

      // SNAP is done: no account resume can follow. Drop the checkpoint and every superseded contract task file,
      // keeping only the files the accounts-complete recovery path was handed (conservative; they are not re-read).
      stateIngest.appState.removeSnapAccountResumeCheckpoint().commit()
      releaseHeldResumeCheckpoint()
      sweepSupersededTaskFiles(keep = accountsCompleteTaskFilePaths, reason = "SNAP complete")

      // Phase 1 of the handshake: tell the parent that pivot/state is anchored. Parent starts RegularSync.
      syncController ! SnapSyncFinalized(pivot)

      // Deferred backfill: bodies and receipts start only now that the state is complete (no-op unless the switch is on).
      releaseDeferredBodiesAndReceipts()

      val backfillStillRunning =
        snapSyncConfig.chainDownloadEnabled && chainDownloader.isDefined && !chainDownloadComplete

      if backfillStillRunning then
        ctx.log.info(
          s"SNAP state finalised at pivot=$pivot. Starting regular sync; chain backfill continues in background."
        )
        // Yield peer slots to regular sync — backfill keeps a small budget.
        chainDownloader.tell(ChainDownloader.YieldToRegularSync(snapSyncConfig.chainBackfillConcurrentRequests))
        completedWithBackfill()
      else
        // No backfill in flight — emit Done immediately. Parent poison-pills this actor.
        chainDownloader.stop()
        syncController ! Done
        completed()
    } // end boundary

  /** The pivot header for finalisation where the header download validates the chain (proof of work). Holds while the
    * download is below the pivot (the deferred backfill's hold), then compares the persisted pivot with the header the
    * download holds at the pivot's number ([[PivotAnchor.check]]):
    *   - anchored: that header, with the persisted state root (and the pinned hash, when one is pinned), is the pivot
    *     header;
    *   - diverged: the persisted pivot is not on the validated chain. The validated header is persisted as the pivot
    *     ([[PivotAnchor.reanchor]]) and SyncController restarts SNAP, which resumes on it and heals toward its root;
    *   - undecided (the download holds no recorded tip at or above the pivot): keep holding. The hold's stall restart
    *     re-runs the download, which re-validates headers stored without a recorded tip.
    *
    * Where the consensus layer chose the pivot (`headerChainCheck.trustsPinnedPivot`, post-merge chains) the pinned
    * hash is the authority, and the pivot is never moved to the downloaded chain's header:
    *   - no pin that can name this pivot: SyncController restarts SNAP, which chooses (and pins) a pivot again;
    *   - diverged: the downloaded headers above the fork point are discarded and downloaded again
    *     ([[rewindToPinnedChain]]); a second divergence of the same pinned pivot restarts SNAP once that is done.
    */
  private def anchoredPivotHeader(pivot: BigInt)(using scala.util.boundary.Label[Behavior[Command]]): BlockHeader =
    import scala.util.boundary.break
    val appState = stateIngest.appState
    val cursor = appState.getBackfillBestHeader()
    // Identifies this pivot for the hold (a changed pivot re-targets the download).
    val holdKey =
      appState.getSnapSyncPivotHash().orElse(appState.getSnapSyncStateRoot()).getOrElse(ByteString(pivot.toByteArray))
    if rewindInFlight.isDefined then
      enterHeaderHold(pivot, holdKey, cursor)
      break(Behaviors.same)
    if cursor < pivot then
      enterHeaderHold(pivot, holdKey, cursor)
      break(Behaviors.same)
    if headerChainCheck.trustsPinnedPivot && PivotAnchor.currentPin(stateIngest, pivot).isEmpty then
      ctx.log.error(
        s"${HeaderChainCheck.LogTag} SNAP finalisation: no pinned hash names the pivot at block $pivot, and on this " +
          "chain the pivot is the consensus layer's choice; escalating to SyncController for a SNAP restart"
      )
      leaveHeaderHold()
      syncController ! SyncProtocol.HealingImpossible
      break(Behaviors.same)
    PivotAnchor.check(stateIngest, pivot) match
      case PivotAnchor.Check.Anchored(header) =>
        leaveHeaderHold()
        header
      case PivotAnchor.Check.Diverged(downloaded, pinnedHash, persistedRoot) if headerChainCheck.trustsPinnedPivot =>
        if pinnedHash.contains(downloaded.hash.value) then
          // The download reached the pinned pivot, but the persisted state root is not its: the state belongs to another
          // root (the A5 guard's case).
          ctx.log.error(
            s"SNAP finalisation aborted: the pivot at block $pivot (hash ${downloaded.hash.value.toHex}) has state root " +
              s"${downloaded.stateRoot.value.toHex}, the persisted one is " +
              s"${persistedRoot.map(_.toHex).getOrElse("not recorded")}. Escalating to SyncController for SNAP restart."
          )
          leaveHeaderHold()
          syncController ! SyncProtocol.HealingImpossible
        else rewindToPinnedChain(pivot, downloaded, pinnedHash, holdKey, cursor)
        break(Behaviors.same)
      case PivotAnchor.Check.Diverged(validated, pinnedHash, persistedRoot) =>
        ctx.log.error(
          s"SNAP finalisation: the pivot at block $pivot (hash ${pinnedHash.map(_.toHex).getOrElse("not recorded")}, " +
            s"state root ${persistedRoot.map(_.toHex).getOrElse("not recorded")}) is not the validated header chain's " +
            s"(hash ${validated.hash.value.toHex}, state root ${validated.stateRoot.value.toHex}). Anchoring the pivot " +
            "to the validated header and escalating to SyncController for a SNAP restart."
        )
        leaveHeaderHold() // else the hold tick would re-run this and re-send HealingImpossible every 2 s
        PivotAnchor.reanchor(stateIngest, validated)
        syncController ! SyncProtocol.HealingImpossible
        break(Behaviors.same)
      case PivotAnchor.Check.Undecided(reason) =>
        if !anchorUndecidedLoggedFor.contains(pivot) then
          anchorUndecidedLoggedFor = Some(pivot)
          ctx.log.error(
            s"SNAP finalisation at pivot $pivot cannot check the pivot against the validated header chain ($reason); " +
              "holding until the header download records it"
          )
        enterHeaderHold(pivot, holdKey, cursor)
        break(Behaviors.same)

  /** The downloaded chain does not reach the pinned pivot (a pivot the consensus layer chose). Its headers above the
    * fork point ([[PivotAnchor.forkPoint]]: the highest header it shares with the pinned pivot's stored ancestry;
    * genesis when there is none within reach) are discarded and downloaded again ([[ChainDownloader.RewindTo]]), and
    * the hold waits for the download to report it applied. A second divergence of the same pinned pivot also restarts
    * SNAP once the rewind is applied: the consensus layer may have moved off that pivot, and the restart chooses a
    * pivot again.
    */
  private def rewindToPinnedChain(
      pivot: BigInt,
      downloaded: BlockHeader,
      pinnedHash: Option[ByteString],
      holdKey: ByteString,
      cursor: BigInt
  ): Unit =
    val (rewindTo, forkPointFound) = PivotAnchor.rewindTarget(stateIngest, pinnedHash, downloaded)
    val escalate = pinnedHash.exists(rewoundForPins.contains)
    rewoundForPins = rewoundForPins ++ pinnedHash
    ctx.log.error(
      s"${HeaderChainCheck.LogTag} SNAP finalisation: the downloaded header at block $pivot " +
        s"(${downloaded.hash.value.toHex}) is not the pinned pivot ${pinnedHash.map(_.toHex).getOrElse("?")}. " +
        (if forkPointFound then s"Downloading the headers above ${rewindTo.idTag}, where the two chains part, again"
         else
           "No common header within the pinned pivot's stored ancestry: downloading the header chain again from " +
             "genesis"
        ) +
        (if escalate then "; the same pivot diverged before, so SNAP restarts once that is done" else "")
    )
    SNAPSyncMetrics.incrementHeaderRejected(HeaderChainCheck.PinnedPivotReason, HeaderChainCheck.Phase.Finalisation)
    // The hold first: it starts the downloader if none runs, so the rewind reaches one.
    enterHeaderHold(pivot, holdKey, cursor)
    rewindInFlight = Some((rewindTo.number.value, escalate))
    val rewoundAdapter =
      ctx.messageAdapter[ChainDownloader.Rewound](r => ChainDownloaderRewound(r.forkPoint))
    chainDownloader.tell(ChainDownloader.RewindTo(rewindTo, rewoundAdapter))

  /** The header download applied a rewind [[rewindToPinnedChain]] asked for. The hold checks the anchor again on its
    * next tick, or, after a second divergence of the same pinned pivot, SyncController restarts SNAP.
    */
  private[snap] def onHeaderDownloadRewound(forkPoint: BigInt): Behavior[Command] =
    rewindInFlight match
      case Some((_, true)) =>
        rewindInFlight = None
        ctx.log.error(
          s"${HeaderChainCheck.LogTag} SNAP finalisation: the header download is back at block $forkPoint; the pinned " +
            "pivot diverged twice, escalating to SyncController for a SNAP restart"
        )
        leaveHeaderHold()
        syncController ! SyncProtocol.HealingImpossible
      case Some(_) =>
        rewindInFlight = None
        ctx.log.info(
          s"SNAP finalisation: the header download is back at block $forkPoint; holding until it reaches the pivot"
        )
      case None =>
        ctx.log.debug(s"SNAP finalisation: a header download rewind to block $forkPoint reported with none in flight")
    Behaviors.same

  /** CQ-020-07: settle the pivot's body without inventing one (see [[StoredBodyCheck]]). A body a newPayload stored is
    * kept when it validates; a body that contradicts the header is replaced by the derived empty one or removed, so the
    * backfill fetches the real one.
    */
  private def applyPivotBodyCheck(pivotHeader: BlockHeader): Unit =
    val pivotHash = pivotHeader.hash
    StoredBodyCheck.decide(pivotHeader, stateIngest.chainReader.getBlockBodyByHash(pivotHash)) match
      case StoredBodyCheck.Action.Keep =>
        ctx.log.info(
          "SNAP finalize: keeping the stored body of pivot {} (validates against its header)",
          pivotHeader.idTag
        )
      case StoredBodyCheck.Action.Write(body, replacing) =>
        replacing.foreach(reason =>
          ctx.log.warn(
            "SNAP finalize: stored body of pivot {} contradicts its header ({}); replacing",
            pivotHeader.idTag,
            reason
          )
        )
        stateIngest.chainWriter.storeCheckedBlockBody(pivotHeader, body).commit()
      case StoredBodyCheck.Action.LeaveAbsent =>
        ctx.log.info(
          "SNAP finalize: pivot {} body not stored yet; the chain backfill fetches and validates it",
          pivotHeader.idTag
        )
      case StoredBodyCheck.Action.Remove(reason) =>
        ctx.log.warn(
          "SNAP finalize: stored body of pivot {} contradicts its header ({}); removing it so the backfill refetches it",
          pivotHeader.idTag,
          reason
        )
        stateIngest.chainWriter.removeBlockBody(pivotHash).commit()

  /** #1692: before the publish writes anything to the hash store, take the "reference counts are exact" stamp away if
    * this hand-off is not clean. The test is the one `finalizeSnapSync` applies to the invariant marker: a clean
    * decision or walk on this root, and the marker standing. A lazy hand-off publishes over a path store that has
    * holes, and a node of a missing position that also sits at a live one is counted once for the two. One synced
    * write, so a node the publish wrote implies the stamp is gone. See [[RefCountTrust.withdrawAtPathPublish]].
    */
  private[snap] def withdrawRefCountStampForPublish(): Unit =
    val appState = stateIngest.appState
    val clean = stateRoot.exists(r => r.value == healingCleanWalkRoot.get() || r.value == healingDecidedRoot.get()) &&
      appState.hasSnapHealInvariant()
    if RefCountTrust.withdrawAtPathPublish(appState, clean) then
      ctx.log.warn(
        "[PATH-PUBLISH] Handing off with no clean decision on this root, or no marker: the publish counts a node that " +
          "stands over a hole once for each live position, so pruning keeps every trie node from here (#1692)"
      )

  /** Launch the Path-to-hash publish on the blocking dispatcher. The controller stays in its current behavior
    * (`syncing`): the `completeSnapSync()` call sites discard the Behavior this returns, so a behavior switch would be
    * silently lost. Instead `pathPublish` marks the publish in flight; `syncing` then serves only status/progress and
    * the PathPublish* messages until [[PathPublishDone]] re-enters [[finalizeSnapSync]].
    */
  private def startPathPublish(pivot: BigInt, pivotRoot: ByteString): Unit =
    val pns =
      stateIngest.pathNodes.getOrElse(throw new IllegalStateException("startPathPublish without PathNodeStorage"))
    withdrawRefCountStampForPublish()
    // Not the cached per-pivot store: the publish is a bulk load of every node of the state, and gets the store that
    // keeps no per-node undo rows for it. Its counts are the same.
    val target = stateIngest.stateTries.getBulkLoadStorage(pivot)
    // Capture everything the worker needs on the actor thread: ActorContext accessors are actor-thread-only.
    val self = ctx.self
    val blockingEc = ctx.system.dispatchers.lookup(org.apache.pekko.actor.typed.DispatcherSelector.blocking())
    ctx.log.info("[PATH-PUBLISH] publishing Path-scheme trie to hash-keyed storage for block execution (async)")
    pathPublish = Some((pivot, pivotRoot))
    val started = System.currentTimeMillis()
    var lastLogged = 0L
    val work = scala.concurrent.Future {
      PathToHashExporter.publish(
        pns,
        target,
        (a, s) =>
          if a + s - lastLogged >= 1000000L then
            lastLogged = a + s
            self ! PathPublishProgress(a, s),
        stateRoot = Some(pivotRoot),
        codePresent = hash => stateIngest.bytecode.get(hash).isDefined
      )
    }(using blockingEc)
    ctx.pipeToSelf(work) {
      case scala.util.Success(r) => PathPublishDone(r, System.currentTimeMillis() - started)
      case scala.util.Failure(e) => PathPublishFailed(e)
    }

  /** Handle the outcome of the async publish (called from `syncing`). On success re-checks the pivot root is readable
    * (the check must hold before anchoring) and resumes [[finalizeSnapSync]]; on any failure escalates for a SNAP
    * restart, exactly like the A5 root-mismatch guard.
    */
  private[snap] def onPathPublishDone(result: PathToHashExporter.Result, millis: Long): Behavior[Command] =
    ctx.log.info(
      s"[PATH-PUBLISH] done: ${result.accountNodes} account + ${result.storageNodes} storage nodes in $millis ms " +
        s"(export ${result.exportMillis} ms, sample ${result.sampleMillis} ms); looked up " +
        s"${result.sampledAccountLeaves} sampled account leaves (${result.sampledAccountMisses} stale or with a node " +
        s"absent on the way); ${result.sampledStorageRoots} sampled storage roots present; looked up " +
        s"${result.sampledLargeTrieLeaves} leaves of the largest storage tries and ${result.sampledEmbedded} embedded leaves " +
        s"(${result.sampledStale} stale in the path store, ${result.sampledMissing} with a node absent on the way); " +
        s"${result.sampledCodeAbsent} of ${result.sampledCode} sampled code hashes absent"
    )
    val sampleMisses =
      result.sampledAccountMisses + result.sampledStale + result.sampledMissing + result.sampledCodeAbsent
    if sampleMisses > 0 then
      ctx.log.warn(
        s"[PATH-PUBLISH] The hand-off's sample missed $sampleMisses time(s), under the rate that fails a hand-off. A stale " +
          "path node is harmless, but a node absent on the way is a hole: import's on-demand path and the background audit " +
          "are what finds the rest of one"
      )
    pathPublish match
      case Some((pivot, pivotRoot)) =>
        pathPublish = None
        if scala.util.Try(getOrCreateMptStorage(pivot).get(pivotRoot.toArray)).isSuccess then
          pathPublishedFor = Some(pivot)
          finalizeSnapSync(pivot, pathPublished = true)
        else
          ctx.log.error(
            "[PATH-PUBLISH] pivot state root {} absent after publish — path-keyed root node does not match the " +
              "pivot header. Escalating to SyncController for SNAP restart.",
            pivotRoot.toHex
          )
          syncController ! SyncProtocol.HealingImpossible
          Behaviors.same
      case None =>
        ctx.log.warn("[PATH-PUBLISH] PathPublishDone with no publish in flight — ignored")
        Behaviors.same

  /** Receive after SNAP state is finalised but `ChainDownloader` is still backfilling history.
    *
    * Minimal surface — only what `ChainDownloader` and status RPCs need. SNAP-protocol responses, peer-list messages,
    * and stagnation checks are no longer relevant; everything else is silently dropped. The actor's sole remaining job
    * is to propagate `ChainDownloader.Done` to the parent so it can poison-pill us.
    */
  def completedWithBackfill(): Behavior[Command] =
    Behaviors
      .receiveMessage[Command] {
        case ChainDownloaderProgress(h, b, r, t) =>
          progressMonitor.updateChainProgress(h, b, r, t)
          Behaviors.same

        case ChainDownloaderDone =>
          ctx.log.info("Background chain backfill complete; SNAPSyncController shutting down.")
          chainDownloadComplete = true
          chainDownloader.stop()
          syncController ! Done
          completed()

        case GetStatus(replyTo) =>
          replyTo ! SyncProtocol.Status.SyncDone
          Behaviors.same

        case GetProgress(replyTo) =>
          replyTo ! progressMonitor.currentProgress
          Behaviors.same

        case _ =>
          // Stale SNAP messages, leaked tickers, and other noise are silently dropped.
          Behaviors.same
      }
      .receiveSignal { case (_, PostStop) =>
        onStop(); Behaviors.same
      }

  /** Start the chain downloader alongside SNAP state sync. With the backfill deferred it downloads headers only; bodies
    * and receipts are held until [[releaseDeferredBodiesAndReceipts]] runs at finalisation.
    */
  def startChainDownloader(): Unit =
    launchChainDownloader(pivotBlock.filter(_ > 0), snapSyncConfig.chainDownloadMaxConcurrentRequests)

  /** State is finalised: let a deferred downloader start fetching bodies and receipts. */
  private def releaseDeferredBodiesAndReceipts(): Unit =
    if SNAPSyncController.chainBackfillDeferredToFinalization(snapSyncConfig) then
      ctx.log.info("SNAP state finalised; releasing deferred body and receipt backfill")
      chainDownloader.tell(ChainDownloader.ReleaseBodiesAndReceipts)

  private def launchChainDownloader(pivotOpt: Option[BigInt], maxConcurrent: Int): Unit =
    if snapSyncConfig.chainDownloadEnabled then
      pivotOpt.foreach { pivot =>
        if chainDownloader.isEmpty then
          ctx.log.info("Starting parallel chain download from genesis to pivot block {}", pivot)
          coordinatorGeneration += 1
          val snapServerNodeIds = snapSyncConfig.snapServerPeers.flatMap { uri =>
            scala.util.Try(ByteString(org.bouncycastle.util.encoders.Hex.decode(uri.getUserInfo))).toOption
          }.toSet
          // ChainDownloader is Pekko Typed Behavior[Command] (Group S6 narrowed).
          // Sends (Pause/Resume/UpdateTarget/YieldToRegularSync/…) are now type-safe against ActorRef[Command].
          import org.apache.pekko.actor.typed.DispatcherSelector
          val downloader = ctx
            .spawn(
              childFactories.chainDownloader(
                blockchainReader = stateIngest.chainReader,
                blockchainWriter = stateIngest.chainWriter,
                appStateStorage = stateIngest.appState,
                networkPeerManager = networkPeerManager,
                peerEventBus = peerEventBus,
                syncConfig = syncConfig,
                replyTo = chainDownloaderReplyAdapter,
                maxConcurrentRequests = maxConcurrent,
                requestTimeout = snapSyncConfig.chainDownloadTimeout,
                snapServerPeerNodeIds = snapServerNodeIds,
                deferBodiesAndReceipts = SNAPSyncController.chainBackfillDeferredToFinalization(snapSyncConfig),
                emptyHeaderBackoff =
                  Option.when(SNAPSyncController.chainBackfillDeferredToFinalization(snapSyncConfig))(
                    SNAPSyncController.EmptyHeaderBackoff
                  ),
                headerChainCheck = headerChainCheck,
                sharedBlacklist = Some(sharedBlacklist)
              ),
              s"chain-downloader-$coordinatorGeneration",
              DispatcherSelector.fromConfig("sync-dispatcher")
            )
          downloader ! ChainDownloader.Start(pivot)
          chainDownloader.attach(downloader)
          chainDownloadComplete = false
      }
  // end if chainDownloadEnabled
