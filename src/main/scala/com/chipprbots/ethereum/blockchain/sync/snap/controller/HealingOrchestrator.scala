package com.chipprbots.ethereum.blockchain.sync.snap.controller

import java.util.concurrent.atomic.AtomicBoolean

import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.SupervisorStrategy
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.util.ByteString

import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*

import com.chipprbots.ethereum.blockchain.sync.SyncController
import com.chipprbots.ethereum.blockchain.sync.snap.ByteCodeTask
import com.chipprbots.ethereum.blockchain.sync.snap.PivotAnchor
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.*
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.SyncPhase.*
import com.chipprbots.ethereum.blockchain.sync.snap.StateValidator
import com.chipprbots.ethereum.blockchain.sync.snap.actors
import com.chipprbots.ethereum.blockchain.sync.snap.heal.HealInvariant
import com.chipprbots.ethereum.db.storage.BfsQueueStorage
import com.chipprbots.ethereum.db.storage.Namespaces
import com.chipprbots.ethereum.db.storage.RocksDbBfsQueueStorage
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.TrieRoot
import com.chipprbots.ethereum.utils.ByteStringUtils.toHex

private[snap] trait HealingOrchestratorState:
  var healRepegNoRootAttempts: Int
  var healingServeRootRequestInFlight: Boolean
  var lastHealingServeRootBlock: Option[BigInt]
  def healingWalkLocalOnly: AtomicBoolean
  var pendingPivotRefresh: Option[(BigInt, String)]
  var clPivotHint: Option[CLPivotHint]
  var healingValidatedRoot: Option[TrieRoot]
  var coordinatorGeneration: Long
  def healedCodeHashes: mutable.LinkedHashSet[ByteString]
  def isPoSChain: Boolean
  def ec: ExecutionContext
  def trieNodesCachedInMemory: Boolean

private[snap] trait HealingApi:
  def triggerHealingForMissingNodes(missingNodes: Seq[ByteString]): Unit
  def startStateHealing(): Unit
  def reopenHealing(): Unit

private[snap] trait HealedCodeApi:
  def dropHealedCodeNowPresent(): Unit
  def queueHealedCode(codeHashes: Seq[ByteString]): Unit

private[snap] trait HealedCodeHoldApi:
  def resetHealedCodeHold(): Unit

private[snap] trait LazyHandoffApi:
  def lazyHandoffBlockedByAbsentPathRoot(): Boolean
  def anchorPivotBeforeLazyHandoff(reason: String): Unit

/** SNAP healing orchestration (spec 016 M6b, research.md R6 "Healing orchestration"): the `StateHealing` arms of
  * `syncing` (`stateHealingArms`: stateless peers, stagnation, the lazy-heal handoffs, validation's re-walk results),
  * the healing-coordinator spawn (`spawnHealingCoordinator`, `startStateHealing`), the 1 s healing tick
  * (`startHealingRequestScheduler`, `requestTrieNodeHealing`) and the serve-root / re-peg staleness check
  * (`maybeRequestHealingServeRoot`), validation's re-walk (`triggerHealingForMissingNodes`), the clean exit
  * (`completeHealingWalkClean`), the lazy-heal anchor (`anchorPivotBeforeLazyHandoff`) and the healed-code helpers
  * (`resetHealedCodeHold`, `dropHealedCodeNowPresent`, `queueHealedCode`). The core keeps the dispatch:
  * `phaseArms(StateHealing)` and the healing arms of `commonSyncingArms` call these members.
  */
private[snap] trait HealingOrchestrator
    extends HealingApi
    with HealedCodeApi
    with HealedCodeHoldApi
    with LazyHandoffApi:
  self: HealingOrchestratorState & SnapSharedState & SnapControllerEnv & CoordinatorHandles & PhaseFlags & ResumeApi &
    PeerPoolApi & SnapServerPeersApi & ValidationApi & FinalizationApi & PivotRefreshApi & PivotSelectionApi &
    ByteCodeRequestApi =>

  // The serve root is considered stale when it is more than this many blocks behind the network head. Matches
  // SyncController.RecentRootMarginBlocks (64) so the refreshed root lands comfortably inside peers' serve window.
  private lazy val HealingServeRootMarginBlocks: BigInt = BigInt(64)

  /** `syncing` arms guarded on `currentPhase == StateHealing` (P4c). */
  private[snap] lazy val stateHealingArms: PartialFunction[Command, Behavior[Command]] = {
    case HealingAllPeersStateless =>
      ctx.log.warn("All healing peers stateless — refreshing pivot in-place for healing")
      // #1555: every healing peer answered empty for this root, so it is KNOWN unservable. Say so, so that on a PoS
      // chain whose CL head has not advanced (clHead - offset <= pivot) the refresh can still move to the #1478
      // fallback (clHead - margin, or the capped peer tip - margin) instead of skipping and burning the re-peg budget
      // on the same dead root. Inert on ETC/pre-merge: the flag is read only in refreshPivotInPlace's CL-head branch.
      refreshPivotInPlace("all healing peers stateless", pivotUnservable = true)
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
    // #1374 FR-001/FR-008: under `movingRootDeltaHeal` this handler is the BOUNDED last-resort, NOT the default.
    // The coordinator's absent-root branch SEEDS the served root and fetches it (batch-2 T006) instead of emitting
    // HealingRootUnservable, so under the flag this is reached only when the controller's own re-peg budget is
    // exhausted (refreshPivotInPlace's MaxHealRepegNoRootAttempts, batch-4 H-S7) — that branch calls completeSnapSync()
    // directly (the same handoff below). This handler stays for the flag-OFF path (where the coordinator still emits
    // HealingRootUnservable) and as a defensive catch; either way it is fail-SAFE (anchor-guard gated), never fail-open.
    // spec 022 review beacon N4: under the Path scheme with the root absent, a hand-off could not publish it (#1555), and
    // the excluded set's deletes or a held root make that the usual case. Re-peg instead and keep healing: a new root
    // gets a new look, and no completion is claimed. Hash scheme (ETC): unchanged.
    case StateHealingAbandoned if lazyHandoffBlockedByAbsentPathRoot() =>
      ctx.log.warn(
        "[HEAL-ABANDONED] Healing gave up, but the heal root is absent from local storage (Path scheme): the lazy " +
          "hand-off could not publish it. NOT handing off: re-pegging to a newer root and continuing to heal."
      )
      healingDecidedRoot.set(null) // spec 022 5d: giving up on this root takes back any decision on it
      refreshPivotInPlace("healing abandoned with the root absent (Path scheme)", countsTowardHealBudget = false)
      Behaviors.same

    case StateHealingAbandoned =>
      ctx.log.warn(
        "[HEAL-ABANDONED] Healing coordinator gave up, so healing is not complete: handing off to lazy on-demand healing " +
          "via completeSnapSync(). (When only bytecode blocked, nothing else is missing: it has walked the rest, or the " +
          "marker stood. Otherwise the trie is not verified.)"
      )
      anchorPivotBeforeLazyHandoff("HEAL-ABANDONED")
      completeSnapSync()
      Behaviors.same

    // #1555: under the Path scheme this hand-off can never finish: the root is, by this message, absent locally, so the
    // Path-to-hash publish fails its verification after a whole-state scan and SyncController restarts SNAP. Re-peg
    // instead (the root is known unservable) and keep healing; HealingPivotRefreshed seeds an absent new root. Hash
    // scheme (ETC): unchanged.
    case HealingRootUnservable(root) if lazyHandoffBlockedByAbsentPathRoot() =>
      ctx.log.warn(
        s"[HEAL-ROOT-UNSERVABLE] Heal walk root ${root.toHex.take(16)} is absent from local storage (Path scheme): the " +
          "lazy hand-off could not publish it. NOT handing off: re-pegging to a newer root and continuing to heal."
      )
      refreshPivotInPlace("heal root absent locally (Path scheme, lazy hand-off blocked)", pivotUnservable = true)
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

    // Validation's re-walk with paths (`triggerHealingForMissingNodes`). What it found goes to the coordinator, which
    // fetches it and decides again, so completion takes its one route, `StateHealingComplete` (spec 022 T052). The
    // Hash controller's own trie walk, which these results used to feed, is gone (spec 022 slice 6).
    // The peer tick, which the completion cancelled, comes back: the coordinator has work again (beacon).
    case ValidationMissingNodes(missingNodes) =>
      if missingNodes.isEmpty then ctx.log.info("Validation's re-walk found nothing missing: healing decides again")
      else
        trieNodeHealingCoordinator match
          case Some(coordinator) =>
            ctx.log.info(s"Validation's re-walk found ${missingNodes.size} missing nodes: queuing them for healing")
            coordinator ! actors.TrieNodeHealingCoordinator.QueueMissingNodes(missingNodes)
          case None =>
            ctx.log.info(
              s"Validation's re-walk found ${missingNodes.size} missing nodes, and no coordinator is running: a new " +
                "one walks the root, the marker being gone"
            )
      reopenHealing()
      startHealingRequestScheduler()
      Behaviors.same

    case ValidationRewalkFailed(error) =>
      ctx.log.error(s"Validation's re-walk failed: $error. Healing decides again")
      reopenHealing()
      startHealingRequestScheduler()
      Behaviors.same
  }

  /** Have healing decide again, whichever coordinator there is or none: a phase with nothing running would otherwise
    * sit silent.
    */
  private[snap] def askHealingAgain(): Unit =
    trieNodeHealingCoordinator match
      case Some(coordinator) => coordinator ! actors.TrieNodeHealingCoordinator.HealingCheckCompletion
      case None              => startStateHealing()

  /** Validation could not vouch for the state, so healing goes on (spec 022 5d, hunter 1). Nothing on record from
    * before this stands, or finalisation would take its word, and a coordinator that has said so once would not say so
    * again. The coordinator keeps what it knows: where it has walked clean, or the marker stands, it decides again with
    * no new walk. Only here: a second start can come after a completion has been taken, and would cost that completion
    * its marker (beacon W1).
    */
  def reopenHealing(): Unit =
    healingCleanWalkRoot.set(null)
    healingDecidedRoot.set(null)
    askHealingAgain()

  /** Healing has completed clean at this root, and a pass has looked at every dormant entry from it: none is held by
    * this root, and none should cost the next start a recovery scan. A write that fails leaves them, at that cost only.
    */
  private def dropDormantExcluded(): Unit =
    try stateIngest.snapProgress.clearDormantExcluded()
    catch
      case scala.util.control.NonFatal(e) =>
        ctx.log.warn(s"[HEAL-EXCLUDED] Could not drop the dormant entries at a clean completion: ${e.getMessage}")

  /** spec 022 T043, at each clean exit from healing: stamp the invariant marker, if what ends here was a repair pass.
    *
    * That pass is the coordinator's clean full walk, which `FrontierRebuildBfs` makes a full descent since no subtree
    * record can stop it (the records went with CF `g`): every present child, and the storage trie of every account
    * leaf. It is not the Hash controller's own trie walk: that walk reads a present hash node and stops there, so it
    * cannot vouch for what is below (it is gone, spec 022 slice 6). Nor is it a coordinator's completion with no walk
    * (T051): the marker stands by then, and a `lostTrack` after that decision must stay. Hence the root check: no clean
    * full walk of this root, no stamp, and the next SNAP run on this datadir repairs again. A write that fails costs
    * the same and no more, so it does not stop the completion.
    *
    * Nor does the walk look at code. On a datadir written by older binaries, an account's code can be missing with
    * nothing saying so: the marker's claim there covers the trie, and missing code is found as it always was, by the
    * recovery scan or at import (data-model.md "Invariant marker").
    */
  private def stampInvariantAfterCleanWalk(): Unit =
    if markerCanHold && stateRoot.exists(_.value == healingCleanWalkRoot.get()) then
      try
        stateIngest.appState.stampSnapHealInvariant().commitSync()
        ctx.log.info("[HEAL-INVARIANT] Clean full walk of the completed root: marker stamped")
      catch
        case scala.util.control.NonFatal(e) =>
          ctx.log.error("[HEAL-INVARIANT] Could not write the marker; a later SNAP run walks again", e)

  /** Whether the marker can hold here at all (`HealInvariant.canHold`). The one place that asks for this run's
    * settings.
    */
  private def markerCanHold: Boolean =
    HealInvariant.canHold(
      snapSyncConfig.storageScheme,
      snapSyncConfig.deferredMerkleization,
      trieNodesCachedInMemory
    )

  /** The exit from state healing, on both schemes (spec 022 T052): commit the pivot anchor, record the clean signal,
    * stop the heal scheduler and enter validation, which short-circuits on `healingValidatedRoot`. It is driven by the
    * coordinator's `StateHealingComplete`. It stamps only on the coordinator's clean full walk of this root: a
    * completion with no walk leaves the marker as it finds it, so a `lostTrack` after that decision stays.
    */
  private[snap] def completeHealingWalkClean(): Unit =
    ctx.log.info("Coordinator decided this root is complete: healing complete (no controller walk).")
    persistHealedAnchorSync()
    dropDormantExcluded()
    stampInvariantAfterCleanWalk()
    healingValidatedRoot = stateRoot
    timers.cancel(RequestTrieNodeHealing)
    progressMonitor.startPhase(StateValidation)
    currentPhase = StateValidation
    validateState()

  /** Persist the pivot block and state root healing completed against, in ONE write, synced (spec 022 T027, research
    * R-3): this is the record that healing is complete for that root. Under archive and basic pruning every healed node
    * went to RocksDB before it, and the WAL is prefix-consistent, so a synced flag implies those nodes survive a power
    * loss too; an unsynced flag could be lost while the node acts on it. Under in-memory pruning the healed nodes may
    * still be in the node cache (spec 022 review vault V3, V9): the finalization barrier persists the cache before the
    * done flags (#1581), not before this anchor. The anchor (pivot, then root) goes in one batch after the
    * coordinator's completion; the lazy-handoff anchor (`anchorPivotBeforeLazyHandoff`) stays unsynced by design: it
    * claims no completion (eye F12).
    */
  private def persistHealedAnchorSync(): Unit =
    val anchors = pivotBlock.map(stateIngest.appState.putSnapSyncPivotBlock).toSeq ++
      stateRoot.map(r => stateIngest.appState.putSnapSyncStateRoot(r.value)) ++
      (for b <- pivotBlock; r <- stateRoot yield PivotAnchor.pinFor(stateIngest, b, r.value))
    anchors.reduceOption(_.and(_)).foreach(_.commitSync())

  // The frontier-rebuild BFS queue, always `Some` (the coordinator falls back to an in-memory queue only on `None`).
  // The healing coordinator reads it as `stateIngest.bfsQueue` (spec 016 C-H2), by-name: once per coordinator
  // construction (its `bfsQueue` val), so it is built at the first coordinator's setup, on that coordinator's thread.
  private[snap] lazy val bfsQueueStorageOpt: Option[BfsQueueStorage] =
    Some(new RocksDbBfsQueueStorage(stateIngest.flatSlots.dataSource, Namespaces.BfsQueueNamespace))

  /** spec 016 M6a: the healing-coordinator spawn, for `startStateHealing` (the second route, the Hash controller's
    * walk, is gone: spec 022 slice 6). It opens the pivot's trie store, spawns the supervised coordinator with the
    * forwarded healing settings, sends it `StartTrieNodeHealing`, the healing in-flight budget and the current snap
    * peers, and (re)starts the 1 s peer-availability timer.
    *
    * Under in-memory pruning on the Hash scheme (spec 022 review vault V3, R1) healed nodes reach the node cache, not
    * disk: the cache writes them as it evicts them, one by one, least recently used first (parents before children
    * after a top-down walk), and its persist is a no-op. "Present implies complete" and "flushed means durable"
    * therefore do not hold on disk, so the invariant marker cannot hold (`HealInvariant.canHold`): none is stamped or
    * trusted there. Every coordinator completion then follows a clean full walk of the current root in that coordinator
    * incarnation (a verification walk, or the clean rebuild walk), and every restart rebuilds the frontier with a full
    * walk; both read the store and so find whatever a crash lost with the cache. That walk is the completion guarantee
    * for this mode, and `markerMayStand` is what keeps it there (spec 022 T051). So it is under `deferred-merkleization
    * \= true`, where no trie was built, and on any datadir whose marker does not stand. Where one stands, a present
    * root is a complete one and the coordinator's gate completes with no walk.
    */
  private def spawnHealingCoordinator(root: TrieRoot): Unit =
    val storage = getOrCreateMptStorage(pivotBlock.getOrElse(BigInt(0)))
    healingCurrentRoot.set(root.value)
    healingCleanWalkRoot.set(null) // spec 022 T043: this coordinator has walked nothing yet
    healingDecidedRoot.set(null) // and has decided nothing
    if !markerCanHold then
      ctx.log.warn(
        (if snapSyncConfig.deferredMerkleization then
           "[HEAL] deferred-merkleization = true: no trie was built during the download, so a done marker vouches for " +
             "flat slots alone."
         else
           "[HEAL] pruning.mode = inmemory: healed nodes reach disk only as the node cache evicts them, so a crash can " +
             "leave a present node over an absent child."
        ) + " The full verification walk decides completion."
      )

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
              stateIngest = stateIngest,
              traversalParallelism = snapSyncConfig.healingTraversalParallelism,
              healingMinParallelism = snapSyncConfig.healingMinParallelism,
              healingReservedCores = snapSyncConfig.healingReservedCores,
              storageScheme = snapSyncConfig.storageScheme,
              frontierHighWater = snapSyncConfig.healingFrontierHighWater,
              frontierLowWater = snapSyncConfig.healingFrontierLowWater,
              decoupledHealServeRoot = snapSyncConfig.decoupledHealServeRoot,
              decoupledHealMaxAttemptsNoRefresh = snapSyncConfig.decoupledHealMaxAttemptsNoRefresh,
              movingRootDeltaHeal = snapSyncConfig.movingRootDeltaHeal,
              healedCodeCheck = true,
              walkLocalOnly = Some(healingWalkLocalOnly),
              heldMemoryLimit = snapSyncConfig.healingHeldMemoryLimit,
              excludedAccounts = true,
              currentRoot = Some(healingCurrentRoot),
              cleanWalkRoot = Some(healingCleanWalkRoot),
              decidedRoot = Some(healingDecidedRoot),
              markerMayStand = markerCanHold
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
      ctx.watchWith(coordinator, CoordinatorGone(coordinator))
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
      healRepegNoRootAttempts = 0 // #1374 T014: fresh healing phase — reset the bounded re-peg last-resort budget
      ctx.log.info(s"Starting state healing with batch size ${snapSyncConfig.healingBatchSize}")

      stateRoot.foreach { root =>
        ctx.log.info("Using actor-based concurrency for state healing")

        spawnHealingCoordinator(root)

        // Ensure snap-server-peers scheduler is running (idempotent — already started at account sync).
        startSnapServerPeersScheduler()

        progressMonitor.startPhase(StateHealing)
      }
  // end else (healing coordinator not yet defined)

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
    // #1374 T009/C4 (moving-root re-peg trigger): the staleness MATH below is shared with the spec-004 serve-root
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
      // #1374: a verification walk with no heal work pending needs no peers; re-pegging now only supersedes it
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
              // #1374 T009/C4: re-peg the single heal root to a fresh served root. refreshPivotInPlace selects a
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
                  s"— re-pegging the single heal root via refreshPivotInPlace (#1374, moving-root delta heal)."
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

  /** Validation found missing nodes. The hashes it passes have no paths, so the trie is walked again with paths for
    * `GetTrieNodes`, and what that finds goes to the coordinator (`ValidationMissingNodes`), which forgets any earlier
    * clean pass and walks again before it decides. Not reachable today, since every route into `validateState` sets
    * `healingValidatedRoot` first (research.md, "Reopening healing"); kept so a later wiring cannot leave a decision
    * standing over a state validation turned down. On Path the walk reads the hash store, which Path does not fill
    * before the publish, so a finding there means only that: the coordinator's own walk is what decides.
    */
  def triggerHealingForMissingNodes(missingNodes: Seq[ByteString]): Unit =
    ctx.log.info(s"Validation found ${missingNodes.size} missing nodes — re-running trie walk with paths for healing")
    // spec 022 T043 (review vault F2): validation walks every node, so no earlier clean walk stands, and a marker that says
    // everything present is complete has just been contradicted. A clean full walk stamps again.
    healingCleanWalkRoot.set(null)
    healingDecidedRoot.set(null)
    // The coordinator's own clean pass goes too, now and not when the re-walk answers: whatever the re-walk finds, or if
    // it fails, the gate walks again before it decides (forge, spec 022 slice 6b). An empty queue does only that.
    trieNodeHealingCoordinator.foreach(_ ! actors.TrieNodeHealingCoordinator.QueueMissingNodes(Seq.empty))
    if stateIngest.appState.isSnapHealInvariantPresent() then
      ctx.log.warn("[HEAL-INVARIANT] Validation found nodes missing under a stamped datadir: marker removed")
      stateIngest.appState.clearSnapHealInvariant().commitSync()
    currentPhase = StateHealing
    stateRoot.foreach { root =>
      val storage = getOrCreateMptStorage(pivotBlock.getOrElse(BigInt(0)))
      scala.concurrent
        .Future {
          val validator = new StateValidator(storage)
          validator.findMissingNodesWithPaths(root.value)
        }(using ec)
        .foreach {
          case Right(nodes) => ctx.self ! ValidationMissingNodes(nodes)
          case Left(error)  => ctx.self ! ValidationRewalkFailed(error)
        }(using ec)
    }

  /** True when a lazy-heal hand-off is certain to fail (#1555): Path scheme, and the current pivot root's node is
    * stored neither at the empty path nor by hash (see `FinalizationPolicy.pathPivotRootAbsent`). Always false under
    * the Hash scheme (ETC), which publishes nothing at finalization.
    */
  def lazyHandoffBlockedByAbsentPathRoot(): Boolean =
    stateIngest.pathNodes.exists { pathNodes =>
      (pivotBlock, stateRoot) match
        case (Some(p), Some(r)) => FinalizationPolicy.pathPivotRootAbsent(pathNodes, r.value, getOrCreateMptStorage(p))
        case _                  => false
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
    * the persisted anchor match the pivot that is about to be finalized, exactly as the clean completion
    * (`completeHealingWalkClean`) already does before it reaches `completeSnapSync()` via `StateValidation`.
    */
  def anchorPivotBeforeLazyHandoff(reason: String): Unit =
    // spec 022 5d: a lazy hand-off is no clean decision, so one on record does not keep the marker. A clean walk's proof of
    // this root does: that says what is on disk, and a re-peg has already taken any proof of a root left since.
    healingDecidedRoot.set(null)
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
      stateIngest.appState
        .putSnapSyncPivotBlock(b)
        .and(stateIngest.appState.putSnapSyncStateRoot(r.value))
        .and(PivotAnchor.pinFor(stateIngest, b, r.value))
        .commit()

  /** Forget the healed codeHashes and the bytecode force-complete: a restart starts a fresh sync. */
  def resetHealedCodeHold(): Unit =
    healedCodeHashes.clear()
    bytecodeForceCompleted = false

  /** Forget healed codeHashes whose bytecode has since been stored. */
  def dropHealedCodeNowPresent(): Unit =
    healedCodeHashes.filterInPlace(h => stateIngest.bytecode.get(h).isEmpty)

  /** Record the codeHashes the healing coordinator reported and send the missing ones to the bytecode coordinator,
    * spawning one if the SNAP children were already torn down.
    */
  def queueHealedCode(codeHashes: Seq[ByteString]): Unit =
    val missing = codeHashes.filter(h => h != Account.EmptyCodeHash.value && stateIngest.bytecode.get(h).isEmpty)
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
                  stateIngest = stateIngest,
                  networkPeerManager = networkPeerManager,
                  requestTracker = requestTracker,
                  batchSize = ByteCodeTask.DEFAULT_BATCH_SIZE,
                  snapSyncController = ctx.self,
                  intakeBudget = Some(intakeBudget),
                  recordExcluded = true
                )
              )
              .onFailure[Throwable](
                SupervisorStrategy.restartWithBackoff(1.second, 10.seconds, 0.2).withMaxRestarts(3)
              ),
            s"bytecode-coordinator-$coordinatorGeneration",
            org.apache.pekko.actor.typed.DispatcherSelector.fromConfig("sync-dispatcher")
          )
        )
        bytecodeCoordinator.foreach(c => ctx.watchWith(c, CoordinatorGone(c)))
        bytecodeCoordinator.foreach(_ ! actors.ByteCodeCoordinator.StartByteCodeSync(Seq.empty))
      bytecodeCoordinator.foreach { coordinator =>
        coordinator ! actors.ByteCodeCoordinator.AddByteCodeTasks(missing, skipQueued = true)
        // Answers `ByteCodeSyncComplete` when the queue drains, and keeps the coordinator from waiting for more.
        coordinator ! actors.ByteCodeCoordinator.NoMoreByteCodeTasks
      }
      if !timers.isTimerActive(RequestByteCodes) then
        timers.startTimerWithFixedDelay(RequestByteCodes, RequestByteCodes, 1.second)
      requestByteCodes()
