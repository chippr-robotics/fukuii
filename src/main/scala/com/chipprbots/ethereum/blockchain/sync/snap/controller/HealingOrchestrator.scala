package com.chipprbots.ethereum.blockchain.sync.snap.controller

import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.SupervisorStrategy
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.util.ByteString

import scala.concurrent.duration.*

import com.chipprbots.ethereum.blockchain.sync.SyncController
import com.chipprbots.ethereum.blockchain.sync.snap.*
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.*
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.SyncPhase.*
import com.chipprbots.ethereum.db.storage.BfsQueueStorage
import com.chipprbots.ethereum.db.storage.HealingFrontierStorage
import com.chipprbots.ethereum.db.storage.Namespaces
import com.chipprbots.ethereum.db.storage.RocksDbBfsQueueStorage
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.TrieRoot
import com.chipprbots.ethereum.utils.ByteStringUtils.ByteStringOps

private[snap] trait PivotRefreshApi:
  def refreshPivotInPlace(
      reason: String,
      countsTowardHealBudget: Boolean = true,
      pivotUnservable: Boolean = false
  ): Unit

/** SNAP healing orchestration (spec 016 M6b, research.md R6 "Healing orchestration"): the `StateHealing` arms of
  * `syncing` (`stateHealingArms`: stateless peers, stagnation, the lazy-heal handoffs, the trie-walk results), the
  * healing-coordinator spawn and its two routes (`spawnHealingCoordinator`, `startStateHealing`,
  * `startStateHealingWithInterleave`), the 1 s healing tick (`startHealingRequestScheduler`, `requestTrieNodeHealing`)
  * and the serve-root / re-peg staleness check (`maybeRequestHealingServeRoot`), the trie walks (`startTrieWalk`,
  * `triggerHealingForMissingNodes`), the Path-scheme clean exit (`completeHealingWalkClean`), the lazy-heal anchor
  * (`anchorPivotBeforeLazyHandoff`) and the healed-code hold helpers (`resetHealedCodeHold`,
  * `dropHealedCodeNowPresent`, `queueHealedCode`). The core keeps the dispatch: `phaseArms(StateHealing)` and the
  * healing arms of `commonSyncingArms` call these members.
  */
private[snap] trait HealingOrchestrator:
  self: SNAPSyncControllerImpl =>

  private var healingRoundCount: Int = 0

  /** `syncing` arms guarded on `currentPhase == StateHealing` (P4c). */
  private[snap] lazy val stateHealingArms: PartialFunction[Command, Behavior[Command]] = {
    case HealingAllPeersStateless =>
      ctx.log.warn("All healing peers stateless — refreshing pivot in-place for healing")
      refreshPivotInPlace("all healing peers stateless")
      Behaviors.same

    // Coordinator detected no healing progress (MaxConsecutiveStagnations 2-min cycles, or the
    // healingStagnationTimeoutMs path). Stagnation means "healing is SLOW", NOT "the root is unservable".
    //
    // LIVELOCK (fixed by heal-hold-pivot-on-stagnation): rolling the pivot on stagnation resets the
    // coordinator's verificationPassComplete=false and re-seeds a pending task, so a slow verification BFS
    // (~16-20h on a slow SSD) is orphaned by a pivot roll (~28-min snap serve window) and can NEVER coincide
    // with a quiet inter-roll window → completion gate never satisfied, regular sync never reached. The
    // missing nodes DO heal (durable, content-addressed), but the gate can't close.
    //
    // FIX: HOLD the healing pivot fixed on stagnation — resume/retry dispatch against the held root instead
    // of rolling. Consensus-safe: the healing pivot is a SYNC target, not a consensus rule. GetTrieNodes
    // fetches missing nodes BY HASH (content-addressed), so a stale root's missing nodes stay ~99.9% servable
    // by current peers. After healing converges against the held root → StateValidation → regular sync, which
    // executes blocks forward and fetches any residual missing node on-demand by hash. No state-root / EVM /
    // gas / reward / RLP output changes — this only changes WHEN the pivot rolls during the healing phase.
    //
    // The GENUINE-unservable path (HealingAllPeersStateless, above) is UNCHANGED: if the held root truly
    // becomes unservable by ALL peers, we still MUST roll or healing stalls.
    //
    // Set heal-hold-pivot-on-stagnation = false to restore the legacy roll-on-stagnation behaviour.
    case HealingStagnated(healed, pending) =>
      if snapSyncConfig.healHoldPivotOnStagnation then
        ctx.log.warn(
          s"[HEAL-STAGNATED] Healing slow (healed=$healed pending=$pending) — HOLDING pivot (not rolling); " +
            s"resuming dispatch on held root so the verification pass can converge"
        )
        trieNodeHealingCoordinator.foreach(_ ! actors.TrieNodeHealingCoordinator.HealingResumeDispatch)
      else
        // Legacy behaviour: refresh pivot — coordinator receives HealingPivotRefreshed, clears stale tasks +
        // stateless peers, re-seeds new root top-down (Besu-aligned). Do NOT stop coordinator —
        // refreshPivotInPlace sends HealingPivotRefreshed to it directly.
        ctx.log.warn(
          s"[HEAL-STAGNATED] Healing stuck: healed=$healed pending=$pending — " +
            s"refreshing pivot for fresh healing round (legacy roll-on-stagnation)"
        )
        refreshPivotInPlace("healing-stagnated")
      Behaviors.same

    // Complementary guard (root-cause w98gfx4wn): the heal walk root's own bytes are absent from local node storage,
    // so the coordinator REFUSED to seed it (seeding an unservable root stalls at "exactly 1 node, healed=0" forever —
    // a root cannot be reconstructed from nothing and cannot be fetched against an advancing serve root). This fires at
    // the SEED, so it covers every entry into healing — crucially the BootstrapComplete RESTART handlers that call
    // startStateHealing() directly and bypass shouldSkipHealingAfterDownloads (the whole point of the seed-site guard).
    //
    // Hand off to lazy on-demand healing exactly as shouldSkipHealingAfterDownloads's deferred path does: stop the
    // (idle) coordinator and call completeSnapSync(). The missing trie nodes are then fetched on-demand via GetTrieNodes
    // during block execution (BlockImporter/StateNodeFetcher, with real parent-root context) — the established
    // post-SNAP regular-sync fallback. This converges to a REAL Completed/regular-sync state; it is NOT a silent
    // skip-and-mark-done (finalizeSnapSync still enforces the snapStateRoot == pivotHeader.stateRoot anchor guard).
    //
    // spec 009 FR-001/FR-008: under `movingRootDeltaHeal` this handler is the BOUNDED last-resort, NOT the default.
    // The coordinator's absent-root branch SEEDS the served root and fetches it (batch-2 T006) instead of emitting
    // HealingRootUnservable, so under the flag this is reached only when the controller's own re-peg budget is
    // exhausted (refreshPivotInPlace's MaxHealRepegNoRootAttempts, batch-4 H-S7) — that branch calls completeSnapSync()
    // directly (the same handoff below). This handler stays for the flag-OFF path (where the coordinator still emits
    // HealingRootUnservable) and as a defensive catch; either way it is fail-SAFE (anchor-guard gated), never fail-open.
    case StateHealingAbandoned =>
      ctx.log.warn(
        "[HEAL-ABANDONED] Healing coordinator force-completed without a verification walk (Path scheme). The trie is " +
          "NOT verified, so this is not a clean walk: handing off to lazy on-demand healing via completeSnapSync()."
      )
      anchorPivotBeforeLazyHandoff("HEAL-ABANDONED")
      completeSnapSync()
      Behaviors.same

    case HealingRootUnservable(root) =>
      ctx.log.warn(
        s"[HEAL-ROOT-UNSERVABLE] Heal walk root ${root.toHex.take(16)} is absent from local storage and cannot be " +
          s"seeded (a heal cannot reconstruct an unservable root). Handing off to lazy on-demand healing via " +
          s"completeSnapSync() — missing trie nodes will be fetched on-demand via GetTrieNodes during block execution."
      )
      // completeSnapSync() → finalizeSnapSync() performs ALL cleanup: stopSnapOnlySchedules() cancels the
      // healing-request scheduler, and stopStateSyncChildren() stops the idle healing coordinator. No manual
      // coordinator/scheduler teardown needed here (it would double-cancel). finalizeSnapSync still enforces the
      // snapStateRoot == pivotHeader.stateRoot anchor guard, so this is NOT a false completion.
      //
      // Platåberget soak, 2026-09-27: re-pegs during StateHealing deliberately skip persisting pivotBlock/
      // stateRoot (BUG-006 guard, see completePivotRefreshWithStateRoot ~4222), so by the time a lazy handoff
      // reaches here the persisted anchor can be several re-pegs behind the in-memory pivot. The A5 guard then
      // compares that stale anchor against the CURRENT pivot's header and aborts on a self-inflicted mismatch
      // between two different pivots. anchorPivotBeforeLazyHandoff re-anchors the persisted keys to the
      // in-memory pivot/root immediately before this terminal, one-way handoff — see its doc for why this does
      // not reintroduce BUG-006.
      anchorPivotBeforeLazyHandoff("HEAL-ROOT-UNSERVABLE")
      completeSnapSync()
      Behaviors.same

    // Streaming batch from ongoing trie walk — forward immediately to coordinator for early healing
    case TrieWalkBatch(missingNodes) =>
      if missingNodes.nonEmpty then
        ctx.log.info(s"Trie walk batch: ${missingNodes.size} missing nodes — queuing for healing")
        trieNodeHealingCoordinator.foreach { coordinator =>
          coordinator ! actors.TrieNodeHealingCoordinator.QueueMissingNodes(missingNodes)
        }
      Behaviors.same

    // Streaming walk completed — all batches already sent via TrieWalkBatch
    case TrieWalkComplete(totalFound) =>
      trieWalkInProgress = false
      trieNodeHealingCoordinator.foreach(_ ! actors.TrieNodeHealingCoordinator.WalkStateChanged(false))
      if totalFound == 0 then
        ctx.log.info("Trie walk found no missing nodes — healing complete after {} rounds!", healingRoundCount)
        healingRoundCount = 0
        pivotBlock.foreach(b => appStateStorage.putSnapSyncPivotBlock(b).commit())
        stateRoot.foreach(r => appStateStorage.putSnapSyncStateRoot(r.value).commit())
        // #1188: capture clean signal — the walk just visited every node.
        healingValidatedRoot = stateRoot
        // Stop the periodic healing-request scheduler before entering validation.
        // It would otherwise keep firing 1-s ticks against a coordinator that's
        // signalled complete; the phase gate on RequestTrieNodeHealing handles
        // any tick already in the mailbox.
        timers.cancel(RequestTrieNodeHealing)
        progressMonitor.startPhase(StateValidation)
        currentPhase = StateValidation
        validateState()
      else
        // A2: Loop indefinitely until Pending==0 — mirrors go-ethereum sync.go:1400
        healingRoundCount += 1
        ctx.log.info(
          s"Trie walk complete: $totalFound missing nodes queued across batches (round $healingRoundCount)"
        )
        timers.startSingleTimer(ScheduledTrieWalkKey, ScheduledTrieWalk, 2.minutes)
      Behaviors.same

    case TrieWalkResult(missingNodes) =>
      trieWalkInProgress = false
      trieNodeHealingCoordinator.foreach(_ ! actors.TrieNodeHealingCoordinator.WalkStateChanged(false))
      if missingNodes.isEmpty then
        ctx.log.info("Trie walk found no missing nodes — healing complete after {} rounds!", healingRoundCount)
        healingRoundCount = 0
        // Commit final pivot root — deferred from refreshPivotInPlace() to prevent BUG-006.
        // AppStateStorage now reflects the root that healing actually completed against.
        for b <- pivotBlock; r <- stateRoot do
          appStateStorage.putSnapSyncPivotBlock(b).and(appStateStorage.putSnapSyncStateRoot(r.value)).commit()
        // #1188: capture clean signal — same as the streaming TrieWalkComplete(0) path.
        healingValidatedRoot = stateRoot
        // Stop the periodic healing-request scheduler before entering validation.
        // See companion handler above for rationale.
        timers.cancel(RequestTrieNodeHealing)
        progressMonitor.startPhase(StateValidation)
        currentPhase = StateValidation
        validateState()
      else
        healingRoundCount += 1
        ctx.log.info(
          s"Trie walk found ${missingNodes.size} missing nodes — queuing for healing (round $healingRoundCount)"
        )
        trieNodeHealingCoordinator.foreach { coordinator =>
          coordinator ! actors.TrieNodeHealingCoordinator.QueueMissingNodes(missingNodes)
        }
        timers.startSingleTimer(ScheduledTrieWalkKey, ScheduledTrieWalk, 2.minutes)
      Behaviors.same

    case ScheduledTrieWalk =>
      startTrieWalk()
      Behaviors.same

    case TrieWalkFailed(error) =>
      trieWalkInProgress = false
      trieNodeHealingCoordinator.foreach(_ ! actors.TrieNodeHealingCoordinator.WalkStateChanged(false))
      ctx.log.error(s"Trie walk failed: $error. Retrying after delay...")
      timers.startSingleTimer(ScheduledTrieWalkKey, ScheduledTrieWalk, 5.seconds)
      Behaviors.same
  }

  /** Path-scheme exit from state healing: same effects as the clean `TrieWalkComplete(0)` branch (commit the pivot
    * anchor, record the clean-walk signal, stop the heal scheduler, enter validation — which short-circuits on
    * `healingValidatedRoot`), driven by the coordinator's verified `StateHealingComplete` instead of a controller walk.
    */
  private[snap] def completeHealingWalkClean(): Unit =
    ctx.log.info(
      "Path scheme: coordinator verification found no missing nodes — healing complete (no controller walk)."
    )
    healingRoundCount = 0
    pivotBlock.foreach(b => appStateStorage.putSnapSyncPivotBlock(b).commit())
    stateRoot.foreach(r => appStateStorage.putSnapSyncStateRoot(r.value).commit())
    healingValidatedRoot = stateRoot
    timers.cancel(RequestTrieNodeHealing)
    progressMonitor.startPhase(StateValidation)
    currentPhase = StateValidation
    validateState()

  /** Start an async trie walk to discover missing nodes. Guards against concurrent walks. Uses streaming to emit
    * batches as they are found — critical for mainnet-scale tries where a full blocking walk can take hours before the
    * coordinator sees any work.
    */
  private def startTrieWalk(): Unit =
    if !trieWalkInProgress && currentPhase == StateHealing then
      trieWalkInProgress = true
      trieNodeHealingCoordinator.foreach(_ ! actors.TrieNodeHealingCoordinator.WalkStateChanged(true))
      stateRoot.foreach { root =>
        ctx.log.info("Starting trie walk to discover missing nodes for healing...")
        val storage = getOrCreateMptStorage(pivotBlock.getOrElse(BigInt(0)))
        val selfRef = ctx.self
        scala.concurrent
          .Future {
            val validator = new StateValidator(storage)
            validator.findMissingNodesStreaming(
              root.value,
              batchSize = 500,
              onBatch = batch => selfRef ! TrieWalkBatch(batch)
            )
          }(ec)
          .foreach {
            case Right(totalFound) => selfRef ! TrieWalkComplete(totalFound)
            case Left(error)       => selfRef ! TrieWalkFailed(error)
          }(ec)
      }
  // end if !trieWalkInProgress && currentPhase == StateHealing

  /** Layer 2: shared, lazily-built persisted-frontier handle for the healing coordinator. `None` (the default,
    * `healing-frontier-persistence = false`) keeps Layer-1 behaviour — no CF writes, always full DFS on restart. Reuses
    * the node's existing RocksDB DataSource (via `flatSlotStorage`); the CF auto-creates on open.
    */
  private lazy val healingFrontierStorageOpt: Option[HealingFrontierStorage] =
    // #4 (restored, spec-005): the pruned post-heal verification also needs the frontier store
    // (prunedHealVerification default true) — without it the first verification reverts to a full
    // ~90M-node trie walk. Dropped by #1384's stale base.
    if snapSyncConfig.healingFrontierPersistence || snapSyncConfig.prunedHealVerification then
      Some(new HealingFrontierStorage(flatSlotStorage.dataSource))
    else None

  private lazy val bfsQueueStorage: BfsQueueStorage =
    new RocksDbBfsQueueStorage(flatSlotStorage.dataSource, Namespaces.BfsQueueNamespace)

  /** spec 016 M6a: the healing-coordinator spawn shared by both healing routes, `startStateHealing` and
    * `startStateHealingWithInterleave`. It opens the pivot's trie store, spawns the supervised coordinator with the
    * forwarded healing settings, sends it `StartTrieNodeHealing`, the healing in-flight budget and the current snap
    * peers, and (re)starts the 1 s peer-availability timer. Each route keeps its own guard, logs and follow-up around
    * the call. `prunedHealVerification` is still not forwarded (#1502).
    */
  private def spawnHealingCoordinator(root: TrieRoot): Unit =
    val storage = getOrCreateMptStorage(pivotBlock.getOrElse(BigInt(0)))

    trieNodeHealingCoordinator = Some(
      ctx.spawn(
        Behaviors
          .supervise(
            childFactories.trieNodeHealingCoordinator(
              stateRoot = root.value,
              networkPeerManager = networkPeerManager,
              requestTracker = requestTracker,
              mptStorage = storage,
              batchSize = snapSyncConfig.healingBatchSize,
              snapSyncController = ctx.self,
              concurrency = snapSyncConfig.healingConcurrency,
              visitedCap = snapSyncConfig.healingVisitedCap,
              healingFrontierStorage = healingFrontierStorageOpt,
              // #1319/spec-002 (restored): gate the coordinator's frontier-mirror writes +
              // completeness markers ON, matching store presence (dropped by #1384's stale base).
              frontierPersistenceEnabled = snapSyncConfig.healingFrontierPersistence,
              traversalParallelism = snapSyncConfig.healingTraversalParallelism,
              healingMinParallelism = snapSyncConfig.healingMinParallelism,
              healingReservedCores = snapSyncConfig.healingReservedCores,
              bfsQueueStorageOpt = Some(bfsQueueStorage),
              storageScheme = snapSyncConfig.storageScheme,
              pathNodeStorageOpt = pathNodeStorageOpt,
              frontierHighWater = snapSyncConfig.healingFrontierHighWater,
              frontierLowWater = snapSyncConfig.healingFrontierLowWater,
              scopedHealVerification = snapSyncConfig.scopedHealVerification,
              scopedHealMaxPaths = snapSyncConfig.scopedHealMaxPaths,
              decoupledHealServeRoot = snapSyncConfig.decoupledHealServeRoot,
              decoupledHealMaxAttemptsNoRefresh = snapSyncConfig.decoupledHealMaxAttemptsNoRefresh,
              movingRootDeltaHeal = snapSyncConfig.movingRootDeltaHeal,
              evmCodeStorage = Some(evmCodeStorage),
              walkLocalOnly = Some(healingWalkLocalOnly)
            )
          )
          .onFailure[Throwable](
            SupervisorStrategy.restartWithBackoff(1.second, 10.seconds, 0.2).withMaxRestarts(3)
          ),
        s"trie-node-healing-coordinator-$coordinatorGeneration",
        org.apache.pekko.actor.typed.DispatcherSelector.fromConfig("sync-dispatcher")
      )
    )

    // Start the coordinator — give healing full per-peer budget (accounts/storage/bytecode done)
    trieNodeHealingCoordinator.foreach { coordinator =>
      coordinator ! actors.TrieNodeHealingCoordinator.StartTrieNodeHealing(root.value)
      coordinator ! actors.TrieNodeHealingCoordinator.UpdateMaxInFlightPerPeer(
        snapSyncConfig.healingMaxInFlightPerPeer
      )
      // Flush current snap peers immediately — the 0-second scheduler delay is async; an explicit
      // flush here ensures peers are available before any StartTrieNodeHealing dispatch attempt.
      peersToDownloadFrom.values
        .filter(p => SNAPSyncController.servesSnapState(p.peerInfo))
        .foreach(p => coordinator ! actors.TrieNodeHealingCoordinator.HealingPeerAvailable(p.peer))
    }

    // Periodically send peer availability notifications (cancel any existing scheduler first)
    startHealingRequestScheduler()

  def startStateHealing(): Unit =
    // Guard: prevent duplicate healing coordinator creation (Bug 27).
    // Can happen when ByteCodeSyncComplete and StorageRangeSyncComplete arrive in quick
    // succession — both call checkAllDownloadsComplete() which calls startStateHealing().
    if trieNodeHealingCoordinator.isDefined then
      ctx.log.warn("startStateHealing called but healing coordinator already exists — ignoring duplicate")
    else
      trieWalkInProgress = false // Reset for fresh healing phase
      healRepegNoRootAttempts = 0 // spec 009 T014: fresh healing phase — reset the bounded re-peg last-resort budget
      ctx.log.info(s"Starting state healing with batch size ${snapSyncConfig.healingBatchSize}")

      stateRoot.foreach { root =>
        ctx.log.info("Using actor-based concurrency for state healing")

        spawnHealingCoordinator(root)

        // Ensure snap-server-peers scheduler is running (idempotent — already started at account sync).
        startSnapServerPeersScheduler()

        progressMonitor.startPhase(StateHealing)
      }
  // end else (healing coordinator not yet defined)

  /** ARCH-WALK-HEAL-INTERLEAVE: Create a healing coordinator BEFORE starting the trie walk. Nodes discovered
    * per-subtree (TrieWalkBatch) are fed to the coordinator immediately, so healing runs in parallel with the walk.
    * With root seeding + discoverMissingChildren, healing starts instantly from the root — the walk is validation-only.
    *
    * If coordinator already exists (e.g. still running after StateHealingComplete), just start the walk — the
    * coordinator is alive and will receive batch nodes.
    */
  private[snap] def startStateHealingWithInterleave(): Unit =
    if trieNodeHealingCoordinator.isDefined then
      // Coordinator still running — just start the validation walk
      startTrieWalk()
    else
      stateRoot match
        case Some(root) =>
          spawnHealingCoordinator(root)
          ctx.log.info(
            s"[HEAL-INTERLEAVE] Healing coordinator created before walk — " +
              s"root=${root.value.take(8).toHex}, generation=$coordinatorGeneration"
          )
          startTrieWalk()
        case None =>
          ctx.log.warn("[HEAL-INTERLEAVE] stateRoot is None — walking only (no coordinator created)")
          startTrieWalk()

  /** BUG-HEAL-SCHED FIX: Always cancel the existing scheduler before creating a new one. Multiple code paths create
    * this scheduler; without cancel an orphaned scheduler fires every 1s in parallel with the new one.
    */
  private def startHealingRequestScheduler(): Unit =
    timers.cancel(RequestTrieNodeHealing)
    timers.startTimerWithFixedDelay(RequestTrieNodeHealing, RequestTrieNodeHealing, 1.second)

  private[snap] def requestTrieNodeHealing(): Unit =
    // Notify coordinator of available peers
    trieNodeHealingCoordinator.foreach { coordinator =>
      val snapPeers = snapServingPeers()

      if snapPeers.isEmpty then ctx.log.debug("No SNAP-capable peers available for healing requests")
      else
        snapPeers.foreach { peer =>
          coordinator ! actors.TrieNodeHealingCoordinator.HealingPeerAvailable(peer)
        }
    }

  /** spec 004 T011/U1: ask the parent for a newest-servable serve root when (and only when) the current serve root has
    * aged > HealingServeRootMarginBlocks behind the network head and no request is already in flight. Reuses the
    * parent's RecentRoot/PivotHeaderBootstrap plumbing via a dedicated requester slot (T012), so it never contends with
    * StorageRecoveryActor's recent-root requester or mutates the walk root. No-op when decoupling is disabled, when not
    * healing, or when no network head / serve target can be computed (U2: keep current serve root rather than pushing
    * an empty one).
    */
  private[snap] def maybeRequestHealingServeRoot(): Unit =
    // spec 009 T009/C4 (moving-root re-peg trigger): the staleness MATH below is shared with the spec-004 serve-root
    // path; only the ACTION differs by flag. Flag OFF (decoupledHealServeRoot): push a HealingServeRootRefresh (moves
    // the coordinator's SERVE root only; the walk root and the controller's stateRoot/pivot are untouched). Flag ON
    // (movingRootDeltaHeal): re-peg the SINGLE heal root via refreshPivotInPlace → completePivotRefreshWithStateRoot →
    // HealingPivotRefreshed, moving the controller's stateRoot/pivotBlock AND the coordinator's heal root IN LOCKSTEP
    // against a canonical header stateRoot. The pendingPivotRefresh.isEmpty guard below also prevents stacking a second
    // re-peg header bootstrap while one is in flight.
    if (snapSyncConfig.decoupledHealServeRoot || snapSyncConfig.movingRootDeltaHeal) &&
      currentPhase == StateHealing &&
      trieNodeHealingCoordinator.isDefined &&
      !healingServeRootRequestInFlight &&
      // spec 009: a verification walk with no heal work pending needs no peers; re-pegging now only supersedes it
      // (the stale walk is discarded and restarted, so a walk longer than the re-peg interval never finishes). Once a
      // pass leaves pending tasks the flag clears and the normal re-peg rules apply again. Gated on movingRootDeltaHeal:
      // the spec-004 serve-root path moves only the serve root and never invalidates the walk.
      !SNAPSyncController.healRepegSuppressedByLocalWalk(
        snapSyncConfig.movingRootDeltaHeal,
        healingWalkLocalOnly.get()
      ) &&
      // Don't contend with a pivot-refresh header bootstrap: while one is pending the parent is (or is about to be)
      // in runningPivotHeaderBootstrap, where a concurrent serve-root bootstrap completion could be mis-routed.
      // The request will fire on a later tick once the refresh settles.
      pendingPivotRefresh.isEmpty
    then
      currentNetworkBestFromSnapPeers().foreach { networkBest =>
        // Target a root inside peers' serve window: networkBest − margin (≥1). recentRootTarget caps at 1.
        val serveTarget = SyncController.recentRootTarget(Seq(networkBest), HealingServeRootMarginBlocks)
        serveTarget.foreach { target =>
          // Staleness clock — see SNAPSyncController.staleReferenceHead's doc (BUG-BC3): CL-anchored instead of
          // networkBest-anchored under movingRootDeltaHeal on a PoS chain with a live CL hint; byte-identical
          // (networkBest) for ETC/pre-merge and for the decoupledHealServeRoot serve-root path.
          val staleClockNow: BigInt = SNAPSyncController.staleReferenceHead(
            movingRootDeltaHeal = snapSyncConfig.movingRootDeltaHeal,
            isPoSChain = isPoSChain,
            clHeadNumber = clPivotHint.flatMap(_.knownHeader).map(_.number.value),
            networkBest = networkBest
          )
          // Refresh cadence (U1): a serve root is fetched at `networkBest − margin`, so it STARTS `margin` blocks
          // behind the head. We refresh only once it has drifted a FULL window further back — i.e. when it is
          // > 2×margin behind the current head — giving ~margin blocks of runway between the ~1s peer round-trips
          // (never per-block). An unset lastHealingServeRootBlock means the coordinator is still fetching against
          // the walk root (coupled), so engage immediately.
          val stale = lastHealingServeRootBlock match
            case Some(lastBlock) => (staleClockNow - lastBlock) > (HealingServeRootMarginBlocks * 2)
            case None            => true
          if stale then
            if snapSyncConfig.movingRootDeltaHeal then
              // spec 009 T009/C4: re-peg the single heal root to a fresh served root. refreshPivotInPlace selects a
              // canonical header (networkBest − margin), fetches it, and emits HealingPivotRefreshed via
              // completePivotRefreshWithStateRoot — moving completeness AND fetch (one root) while RETAINING every
              // persisted verified node and resetting verificationPassComplete so a fresh pruned descent gates
              // completion against the new root. Record the bookkeeping baseline via the SAME rule
              // staleReferenceHead used to pick the clock (see lastHealingServeRootBlockToRecord's doc — BUG-BC3
              // follow-up): non-CL-anchored checks (ETC/pre-merge, or PoS before a CL hint arrives) MUST keep
              // recording `target`, byte-identical to base, or the effective re-trigger threshold silently
              // doubles (networkBest must then drift > 2×margin instead of > margin, roughly doubling the
              // interval between re-pegs on ETC mainnet); the actual root lands when the refresh settles.
              lastHealingServeRootBlock = Some(
                SNAPSyncController.lastHealingServeRootBlockToRecord(staleClockNow, networkBest, target)
              )
              ctx.log.info(
                // clock is CL-anchored iff it differs from networkBest (see staleReferenceHead); both are logged
                // so an operator can tell which source drove this check without guessing from the numbers alone.
                s"[HEAL-REPEG] Heal root stale (clock=$staleClockNow, networkBest=$networkBest, target=$target, " +
                  s"margin=$HealingServeRootMarginBlocks, lastRepegBlock=${lastHealingServeRootBlock.getOrElse("none")}) " +
                  s"— re-pegging the single heal root via refreshPivotInPlace (spec 009 moving-root delta heal)."
              )
              // countsTowardHealBudget=false: this is a proactive "is there a fresher CL pivot" probe, not a
              // report that the current root is unservable. See refreshPivotInPlace's handling for the full
              // rationale (BUG-BC3). HealingAllPeersStateless — the GENUINE unservable-root signal — still calls
              // refreshPivotInPlace with the default (true), so the budget stays intact for that case.
              refreshPivotInPlace("spec009 moving-root re-peg: heal root stale", countsTowardHealBudget = false)
            else
              healingServeRootRequestInFlight = true
              ctx.log.info(
                s"[HEAL-SERVE-ROOT] Requesting newest-servable serve root: networkBest=$networkBest target=$target " +
                  s"(margin=$HealingServeRootMarginBlocks, lastServeBlock=${lastHealingServeRootBlock.getOrElse("none")}). " +
                  s"Routing via parent RecentRoot bootstrap."
              )
              syncController ! SNAPSyncController.RequestHealingServeRoot
        }
      }

  /** Trigger healing by re-running the trie walk with path tracking. Called from validateState() when missing nodes are
    * discovered. The passed hashes are just an indicator — we re-walk to get proper paths for GetTrieNodes.
    */
  def triggerHealingForMissingNodes(missingNodes: Seq[ByteString]): Unit =
    ctx.log.info(s"Validation found ${missingNodes.size} missing nodes — re-running trie walk with paths for healing")
    currentPhase = StateHealing
    stateRoot.foreach { root =>
      val storage = getOrCreateMptStorage(pivotBlock.getOrElse(BigInt(0)))
      scala.concurrent
        .Future {
          val validator = new StateValidator(storage)
          validator.findMissingNodesWithPaths(root.value)
        }(ec)
        .foreach {
          case Right(nodes) => ctx.self ! TrieWalkResult(nodes)
          case Left(error)  => ctx.self ! TrieWalkFailed(error)
        }(ec)
    }

  /** Persist the CURRENT in-memory pivot/root anchor immediately before a lazy-heal handoff to `completeSnapSync()`.
    *
    * Background (Platåberget soak, 2026-09-27): `completePivotRefreshWithStateRoot()` deliberately does NOT persist
    * `pivotBlock`/`stateRoot` while `currentPhase == StateHealing` (the BUG-006 guard, ~4222) — persisting an advancing
    * root mid-walk, before healing has proven it complete, is what BUG-006 was. That leaves `AppStateStorage`'s
    * `SnapSyncPivotBlock`/`SnapSyncStateRoot` pinned to whatever pivot was current when `StateHealing` began, even
    * after any number of in-place re-pegs (`HealingPivotRefreshed`) during healing.
    *
    * The lazy-heal handoffs (`HealingRootUnservable`, and the moving-root-delta-heal re-peg-budget exhaustion) call
    * `completeSnapSync()` -> `finalizeSnapSync()` directly from inside `StateHealing`. `finalizeSnapSync`'s A5 guard
    * reads the PERSISTED `SnapSyncStateRoot` and compares it against `pivotHeader.stateRoot`, where `pivotHeader` is
    * looked up via the CURRENT in-memory `pivotBlock`. After any re-peg the two sides of that comparison describe two
    * DIFFERENT pivots, so the guard trips — even though `pivotBlock`/`stateRoot` are, by construction, already
    * self-consistent with a real, durably-stored header (`completePivotRefreshWithStateRoot` only ever sets them
    * together, from a header already fetched via `blockchainReader`). That is a false positive, not a completeness
    * failure: A5 exists to catch a genuine BUG-008-class divergence between the anchor and the block we are about to
    * finalize on, not to re-prove healing completeness — the lazy handoff already documents that it is NOT claiming
    * full completeness; it defers residual gaps to on-demand `GetTrieNodes` fetches during block execution, exactly as
    * it did before this fix.
    *
    * Does this reintroduce BUG-006? No. BUG-006 was a MID-walk write: a root persisted while the walk could still
    * re-peg again, and while `validateState()` might read the persisted value and assume it was already healed. A lazy
    * handoff is a ONE-WAY terminal exit from walk-based healing — the healing coordinator is torn down inside
    * `completeSnapSync()`/`finalizeSnapSync()`, `currentPhase` moves to `Completed`, and no further re-peg of THIS sync
    * attempt can occur. There is no "next write" left to race, and this makes no new completeness claim — it only makes
    * the persisted anchor match the pivot that is about to be finalized, exactly as the clean walk-complete paths
    * (`TrieWalkComplete(0)`, `TrieWalkResult(empty)`, ~1462-1463/1491-1492) already do before THEY reach
    * `completeSnapSync()` via `StateValidation`.
    */
  private[snap] def anchorPivotBeforeLazyHandoff(reason: String): Unit =
    for
      b <- pivotBlock
      r <- stateRoot
    do
      ctx.log.info(
        "[HEAL-LAZY-ANCHOR] pivot={} root={} reason={} - anchoring persisted SnapSyncPivotBlock/SnapSyncStateRoot " +
          "to the in-memory pivot before the lazy-heal handoff, so finalizeSnapSync's A5 guard compares like-for-like",
        b,
        r.value.toHex.take(16),
        reason
      )
      appStateStorage.putSnapSyncPivotBlock(b).and(appStateStorage.putSnapSyncStateRoot(r.value)).commit()

  /** Forget everything the healed-code hold knows. A restart or re-peg starts a fresh sync: a stale hold, or a
    * `HealedCodeWaitTimeout` still in flight from the old one, must not finalise it mid-download.
    */
  private[snap] def resetHealedCodeHold(): Unit =
    healedCodeHashes.clear()
    awaitingHealedCode = false
    healedCodeWaitExhausted = false
    bytecodeForceCompleted = false
    timers.cancel(HealedCodeWaitTimerKey)

  /** Forget healed codeHashes whose bytecode has since been stored. */
  def dropHealedCodeNowPresent(): Unit =
    healedCodeHashes.filterInPlace(h => evmCodeStorage.get(h).isEmpty)

  /** Record the codeHashes the healing coordinator reported and send the missing ones to the bytecode coordinator,
    * spawning one if the SNAP children were already torn down.
    */
  def queueHealedCode(codeHashes: Seq[ByteString]): Unit =
    val missing = codeHashes.filter(h => h != Account.EmptyCodeHash.value && evmCodeStorage.get(h).isEmpty)
    if missing.nonEmpty then
      healedCodeHashes ++= missing
      ctx.log.info(
        s"[HEAL-CODE] Fetching ${missing.size} bytecode(s) of healed accounts (outstanding: ${healedCodeHashes.size})"
      )
      if bytecodeCoordinator.isEmpty then
        coordinatorGeneration += 1
        bytecodeCoordinator = Some(
          ctx.spawn(
            Behaviors
              .supervise(
                childFactories.byteCodeCoordinator(
                  evmCodeStorage = evmCodeStorage,
                  networkPeerManager = networkPeerManager,
                  requestTracker = requestTracker,
                  batchSize = ByteCodeTask.DEFAULT_BATCH_SIZE,
                  snapSyncController = ctx.self,
                  intakeBudget = Some(intakeBudget)
                )
              )
              .onFailure[Throwable](
                SupervisorStrategy.restartWithBackoff(1.second, 10.seconds, 0.2).withMaxRestarts(3)
              ),
            s"bytecode-coordinator-$coordinatorGeneration",
            org.apache.pekko.actor.typed.DispatcherSelector.fromConfig("sync-dispatcher")
          )
        )
        bytecodeCoordinator.foreach(_ ! actors.ByteCodeCoordinator.StartByteCodeSync(Seq.empty))
      bytecodeCoordinator.foreach { coordinator =>
        coordinator ! actors.ByteCodeCoordinator.AddByteCodeTasks(missing)
        // Answers `ByteCodeSyncComplete` when the queue drains, and keeps the coordinator from waiting for more.
        coordinator ! actors.ByteCodeCoordinator.NoMoreByteCodeTasks
      }
      if !timers.isTimerActive(RequestByteCodes) then
        timers.startTimerWithFixedDelay(RequestByteCodes, RequestByteCodes, 1.second)
      requestByteCodes()
