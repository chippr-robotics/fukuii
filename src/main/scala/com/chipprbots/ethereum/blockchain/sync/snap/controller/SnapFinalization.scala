package com.chipprbots.ethereum.blockchain.sync.snap.controller

import org.apache.pekko.actor.typed.ActorRef as TypedActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.PostStop
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.util.ByteString

import scala.collection.mutable
import scala.concurrent.duration.*

import com.chipprbots.ethereum.blockchain.sync.SyncProtocol
import com.chipprbots.ethereum.blockchain.sync.snap.ChainDownloader
import com.chipprbots.ethereum.blockchain.sync.snap.PathToHashExporter
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.*
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.SyncPhase.*
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockBody
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.utils.ByteStringUtils.ByteStringOps

private[snap] trait SnapFinalizationState:
  var chainDownloadComplete: Boolean
  var headerHold: Option[ByteString]
  var pathPublish: Option[(BigInt, ByteString)]
  var coordinatorGeneration: Long
  def healedCodeHashes: mutable.LinkedHashSet[ByteString]
  def chainDownloaderReplyAdapter: TypedActorRef[ChainDownloader.Done.type]

private[snap] trait TaskFileSweepApi:
  def accountsCompleteTaskFilePaths: Set[String]
  def sweepSupersededTaskFiles(keep: Set[String], reason: String): Unit

private[snap] trait HealedCodeApi:
  def dropHealedCodeNowPresent(): Unit
  def queueHealedCode(codeHashes: Seq[ByteString]): Unit

private[snap] trait ShutdownApi:
  def onStop(): Unit
  def stopSnapOnlySchedules(): Unit
  def stopStateSyncChildren(): Unit

/** SNAP finalization (spec 016 M4, research.md R6 "Finalization / header-hold / path-publish / backfill handoff"): the
  * completion entry (`completeSnapSync`, with the healed-code hold), the deferred-backfill header hold
  * (`enterHeaderHold`, `leaveHeaderHold`), the anchor and hand-off (`finalizeSnapSync`), the Path-scheme publish
  * (`startPathPublish`, `onPathPublishDone`), the ChainDownloader launch and release (`startChainDownloader`,
  * `launchChainDownloader`, `releaseDeferredBodiesAndReceipts`) and the terminal behaviours (`completedWithBackfill`,
  * `completed`). The core keeps the dispatch: the path-publish and header-hold guard arms of `syncing` and its
  * ChainDownloader arms call these members.
  */
private[snap] trait SnapFinalization:
  self: SnapFinalizationState & SnapSharedState & SnapControllerEnv & CoordinatorHandles & PhaseFlags & ResumeApi &
    TaskFileSweepApi & HealedCodeApi & PeerPoolApi & ShutdownApi =>

  private var lastHeaderHoldWarnMs: Long = 0L
  // Stall watchdog for the hold: highest header cursor seen and when it last advanced.
  private var holdLastCursor: BigInt = 0
  private var holdLastAdvanceMs: Long = 0L

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
  private[snap] def completeSnapSync(): Behavior[Command] =
    // Diagnostic (Platåberget soak, 2026-09-27): the ONLY way to know, after the fact, which of the four
    // completeSnapSync() call sites fired and whether the in-memory pivot/root already matched the persisted
    // anchor at that moment — without this, the shipped logback.xml silenced every INFO/WARN line from this
    // actor's real runtime class (the controller's private impl class), leaving only the eventual A5 ERROR as evidence.
    ctx.log.info(
      "[SNAP-COMPLETE] phase={} pivot={} inMemoryRoot={} persistedRoot={}",
      currentPhase,
      pivotBlock.getOrElse("none"),
      stateRoot.map(_.value.toHex.take(16)).getOrElse("none"),
      appStateStorage.getSnapSyncStateRoot().map(_.toHex.take(16)).getOrElse("none")
    )
    dropHealedCodeNowPresent()
    if healedCodeHashes.nonEmpty && !healedCodeWaitExhausted then
      // Healing delivered accounts whose bytecode is not here. Finalising now hands regular sync a state in which a
      // call to one of them runs as a call to an EOA. Hold until the bytecode arrives or the wait runs out; every
      // caller of this method ignores the Behavior when it is `Behaviors.same`, so holding is just not finalising.
      if !awaitingHealedCode then
        awaitingHealedCode = true
        ctx.log.info(s"[HEAL-CODE] Holding SNAP finalisation for ${healedCodeHashes.size} healed-account bytecode(s)")
        timers.startSingleTimer(HealedCodeWaitTimerKey, HealedCodeWaitTimeout, HealedCodeWaitMs.millis)
        queueHealedCode(healedCodeHashes.toSeq)
      Behaviors.same
    else
      timers.cancel(HealedCodeWaitTimerKey)
      awaitingHealedCode = false
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
      chainDownloader.stop()
      startChainDownloader()
      holdLastAdvanceMs = now
    if now - lastHeaderHoldWarnMs >= SNAPSyncController.HeaderHoldWarnIntervalMs then
      lastHeaderHoldWarnMs = now
      ctx.log.warn(
        s"SNAP finalisation held for the header download: cursor=$cursor pivot=$pivot gap=${pivot - cursor} " +
          s"lastKnownPeers=${peersToDownloadFrom.size} (peer events are not processed during the hold)"
      )
    headerHold = Some(pivotHash)

  private def leaveHeaderHold(): Unit =
    if headerHold.isDefined then
      headerHold = None
      timers.cancel(SNAPSyncController.HeaderHoldTimerKey)
      ctx.log.info("Header download reached the pivot; finalising SNAP")

  private[snap] def finalizeSnapSync(pivot: BigInt, pathPublished: Boolean = false): Behavior[Command] =
    import scala.util.boundary, boundary.break
    boundary[Behavior[Command]] {
      // Look up the pivot header so we can store a complete "best block" anchor.
      // RegularSync's BranchResolution needs: header, body, number→hash mapping,
      // ChainWeight, and BestBlockInfo (hash + number) to accept blocks that chain
      // from the pivot.
      blockchainReader.getBlockHeaderByNumber(pivot) match
        case Some(pivotHeader) =>
          // A5: Root match guard — snapStateRoot must equal pivotHeader.stateRoot before
          // marking sync done. Mirrors Besu SnapWorldDownloadState.saveWorldState() implicit
          // verification. If they diverge (BUG-008 class), restart SNAP rather than committing
          // a broken state.
          appStateStorage.getSnapSyncStateRoot().foreach { snapRoot =>
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
          if pathNodeStorageOpt.isDefined && !pathPublished then
            startPathPublish(pivot, pivotHeader.stateRoot.value)
            break(Behaviors.same)

          // Deferred backfill (switch on): hold until the forward header download has reached the pivot. That gives a
          // contiguous header chain through the pivot, so (a) BLOCKHASH(n) for the first 256 blocks above it, which
          // walks parentHash through stored headers (AncestorBlockHashes), is covered by construction, and (b) the
          // pivot's total difficulty below is the REAL accumulated one (REAL_DB_TD) instead of a peer interpolation
          // (calibratePivotTD scales a peer's TD by pivot/peerBlock over ETH68 peers only, falls back to the block
          // number with ETH69-only peers, and every block above the pivot inherits whatever is stored here).
          // The header cursor is written atomically with each stored header, so cursor >= pivot means contiguous.
          if SNAPSyncController.chainBackfillDeferredToFinalization(snapSyncConfig) then
            val cursor = appStateStorage.getBackfillBestHeader()
            if cursor < pivot then
              enterHeaderHold(pivot, pivotHeader.hash.value, cursor)
              break(Behaviors.same)
            else leaveHeaderHold()

          val pivotHash = pivotHeader.hash

          // Store the full block (header + empty body) so getBlockByHash(pivotHash) returns
          // a Block AND the number→hash mapping is written. PivotHeaderBootstrap already stored
          // the header, but storeBlock ensures the mapping is present even if the header was
          // stored by a different code path during pivot refresh.
          blockchainWriter.storeBlock(Block(pivotHeader, BlockBody.empty)).commit()

          // Store a ChainWeight so compareBranch() can evaluate new blocks.
          // Priority: (1) real cumulative TD from ChainDownloader — only accepted if it exceeds
          // 1000× genesis TD (rejects genesis-proxy values written by earlier updateBestBlockForPivot
          // calls). (2) Peer-interpolated TD from a connected ETH68 peer. (3) Block-number proxy as
          // last resort (no ETH68 peers at all — rare but possible in isolated test environments).
          val genesisBlockTD: BigInt = blockchainReader
            .getChainWeightByHash(blockchainReader.genesisHeader.hash)
            .map(_.totalDifficulty.value)
            .getOrElse(blockchainReader.genesisHeader.difficulty.value)
          val (finalTD, tdSource) =
            blockchainReader
              .getChainWeightByHash(pivotHash)
              .map(_.totalDifficulty.value)
              .filter(_ > genesisBlockTD * BigInt(1000))
              .map(td => (td, "REAL_DB_TD"))
              .orElse(calibratePivotTD(pivot).map(td => (td, "PEER_INTERPOLATED_TD")))
              .getOrElse((pivot.max(genesisBlockTD), "BLOCK_NUMBER_PROXY"))
          ctx.log.info("SNAP finalize pivot TD: block={} td={} source={}", pivot, finalTD, tdSource)
          blockchainWriter
            .storeChainWeight(
              pivotHash,
              ChainWeight.totalDifficultyOnly(finalTD)
            )
            .commit()

          // Set best block info with BOTH hash and number (putBestBlockNumber only
          // sets the number, leaving getBestBlockInfo().hash empty).
          appStateStorage
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
          val bytecodeComplete =
            SNAPSyncController.bytecodeRecoveryComplete(healedCodeHashes.size, bytecodeForceCompleted)
          if !bytecodeComplete then
            ctx.log.warn(
              s"[HEAL-CODE] bytecode incomplete at finalisation (healed-account codeHashes missing: " +
                s"${healedCodeHashes.size}, bytecode phase force-completed: $bytecodeForceCompleted) — " +
                "bytecodeRecoveryDone NOT set"
            )
          val doneFlags = appStateStorage.snapSyncDone().and(appStateStorage.storageRecoveryDone())
          (if bytecodeComplete then doneFlags.and(appStateStorage.bytecodeRecoveryDone()) else doneFlags).commitSync()

          ctx.log.info(s"SNAP sync completed successfully at block $pivot (hash=${pivotHash.value.take(8).toHex})")

        case None =>
          // Fallback: shouldn't happen since PivotHeaderBootstrap stored the header
          ctx.log.warn(s"Pivot header for block $pivot not found in storage — setting best block number only")
          appStateStorage.putBestBlockNumber(pivot).commit()

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
      appStateStorage.removeSnapAccountResumeCheckpoint().commit()
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

  /** Launch the Path-to-hash publish on the blocking dispatcher. The controller stays in its current behavior
    * (`syncing`): the `completeSnapSync()` call sites discard the Behavior this returns, so a behavior switch would be
    * silently lost. Instead `pathPublish` marks the publish in flight; `syncing` then serves only status/progress and
    * the PathPublish* messages until [[PathPublishDone]] re-enters [[finalizeSnapSync]].
    */
  private def startPathPublish(pivot: BigInt, pivotRoot: ByteString): Unit =
    val pns = pathNodeStorageOpt.getOrElse(throw new IllegalStateException("startPathPublish without PathNodeStorage"))
    val target = getOrCreateMptStorage(pivot)
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
        stateRoot = Some(pivotRoot)
      )
    }(blockingEc)
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
      s"[PATH-PUBLISH] done: ${result.accountNodes} account + ${result.storageNodes} storage nodes in $millis ms; " +
        s"verified ${result.sampledAccountLeaves} sampled account leaves and ${result.sampledStorageRoots} storage roots"
    )
    pathPublish match
      case Some((pivot, pivotRoot)) =>
        pathPublish = None
        if scala.util.Try(getOrCreateMptStorage(pivot).get(pivotRoot.toArray)).isSuccess then
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
  private[snap] def startChainDownloader(): Unit =
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
                blockchainReader = blockchainReader,
                blockchainWriter = blockchainWriter,
                appStateStorage = appStateStorage,
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
                  )
              ),
              s"chain-downloader-$coordinatorGeneration",
              DispatcherSelector.fromConfig("sync-dispatcher")
            )
          downloader ! ChainDownloader.Start(pivot)
          chainDownloader.attach(downloader)
          chainDownloadComplete = false
      }
  // end if chainDownloadEnabled
