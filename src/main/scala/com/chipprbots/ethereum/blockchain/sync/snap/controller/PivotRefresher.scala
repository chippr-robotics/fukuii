package com.chipprbots.ethereum.blockchain.sync.snap.controller

import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.util.ByteString

import scala.collection.mutable
import scala.concurrent.duration.*

import com.chipprbots.ethereum.blockchain.sync.snap.ChainDownloader
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.*
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.SyncPhase.*
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncMetrics
import com.chipprbots.ethereum.consensus.engine.PoSBlockHeaderValidator
import com.chipprbots.ethereum.db.storage.SnapSyncProgressStorage
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.network.p2p.messages.SNAP
import com.chipprbots.ethereum.network.p2p.messages.SNAP.GetAccountRange
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.ByteStringUtils.ByteStringOps

private[snap] trait PivotRefresherState:
  var clPivotHint: Option[CLPivotHint]
  def isPoSChain: Boolean
  var pendingPivotRefresh: Option[(BigInt, String)]
  var healRepegNoRootAttempts: Int
  var validationGeneration: Long
  var preservedRangeProgress: Map[ByteString, ByteString]
  var preservedAtPivotBlock: Option[BigInt]
  def snapProgressStorage: SnapSyncProgressStorage
  var lastProactivePivotBlock: Option[BigInt]
  var pivotProbeRequestId: Option[BigInt]
  var proactiveRollNeedsProbe: Boolean
  var pendingProbeCommit: Option[(BigInt, BlockHeader, String)]
  var lastProbeAttemptMs: Long
  var probeAttemptCount: Int
  var consecutivePivotRefreshes: Int
  var criticalFailureCount: Int
  var lastPivotRestartMs: Long
  def MinPivotRestartInterval: FiniteDuration
  def PivotBootstrapRetryKey: String
  def ProbeCooldownMs: Long
  def SnapServeWindowMargin: BigInt
  def storageContractProgressPct: Double

private[snap] trait PivotRefreshApi:
  def refreshPivotInPlace(
      reason: String,
      countsTowardHealBudget: Boolean = true,
      pivotUnservable: Boolean = false
  ): Unit

private[snap] trait PivotRollTriggerApi:
  def maybeProactivePivotRoll(): Unit

private[snap] trait PivotRefreshCommitApi:
  def completePivotRefreshWithStateRoot(newPivotBlock: BigInt, newPivotHeader: BlockHeader, reason: String): Unit

private[snap] trait PivotProbeResponseApi:
  def handlePivotProbeResponse(msg: SNAP.AccountRange): Unit

/** SNAP pivot refresh (spec 016 M10, research.md R6 "Pivot selection + refresh" (b)): the in-place re-peg of the pivot
  * block and state root without tearing down the coordinators (`refreshPivotInPlace`: CL-anchored or peer-best target,
  * the #1555 unservable-pivot fallback, the bounded heal re-peg budget and the 30-s retry; the header fetch through the
  * pivot bootstrap), its commit (`completePivotRefreshWithStateRoot`: the StateValidation refusal, the same-root
  * re-arm, the proactive roll's readiness probe, the post-merge header gate, the persisted pivot and preserved-cursor
  * anchor, the coordinator and ChainDownloader notifications), the probe's answer and timeout
  * (`handlePivotProbeResponse`, `pivotProbeTimeoutArms`), the refresh side of the pivot-header bootstrap
  * (`pivotRefreshBootstrapCompleteArms`, `pivotRefreshBootstrapFailedArms`, guarded by `pendingPivotRefresh`), the
  * refresh retry (`retryPivotRefreshArms`, with the timer's provenance `retryRefreshCounts` and
  * `retryRefreshUnservable`) and a coordinator's unservable-root report (`pivotStateUnservableArms`). The core keeps
  * the dispatch: `commonSyncingArms` delegates to the arms at their positions, and its `AccountRangeResponse` arm still
  * matches the probe's request id. It also keeps the proactive roll's trigger (the `CheckDownloadStagnation` arm) and
  * the `DelayedRestart` arm.
  */
private[snap] trait PivotRefresher
    extends PivotRefreshApi
    with PivotRollTriggerApi
    with PivotRefreshCommitApi
    with PivotProbeResponseApi:
  self: PivotRefresherState & SnapSharedState & SnapControllerEnv & CoordinatorHandles & PhaseFlags & LifecycleApi &
    PeerPoolApi & PivotSelectionApi & PivotBestBlockApi & ProbeTargetApi & FinalizationApi & ChainDownloadStartApi &
    LazyHandoffApi & StagnationResetApi =>

  private lazy val MaxProbeAttempts: Int = 5 // 5 × 30s = 150s max deferral before forced roll
  private lazy val MaxConsecutivePivotRefreshes = 10
  private lazy val SnapServeWindowBlocks: BigInt = BigInt(100)
  // Pivot blocks confirmed unservable in this session. When a refreshPivotInPlace resolves
  // to the same block as one in this set, fast-track consecutivePivotRefreshes to the
  // threshold so dormant mode activates after the next coordinator escalation (2–3 cycles
  // instead of 10). Entries are never cleared — a failed pivot block should not be retried
  // within the same session.
  private lazy val failedPivotBlocks: mutable.Set[BigInt] = mutable.Set.empty
  private lazy val MaxHealRepegNoRootAttempts: Int = 10 // 10 × 30s ≈ 5 min of no servable root before the lazy handoff
  // Provenance of the armed PivotBootstrapRetryKey/RetryPivotRefresh timer (BUG-BC3 3rd follow-up): true only when
  // armed by a counted StateHealing attempt. A timer armed in an earlier phase (e.g. a storage-phase stall) carries
  // false, so firing after StateHealing starts cannot spend the heal budget on a stalled CL. Consulted on PoS only.
  private var retryRefreshCounts: Boolean = false
  // #1555: whether the counted attempt that armed that timer reported the pivot as KNOWN unservable (all healing peers
  // stateless), so its replays keep the #1478 fallback. Set only together with retryRefreshCounts = true; cleared
  // wherever retryRefreshCounts is cleared. Read on PoS only.
  private var retryRefreshUnservable: Boolean = false

  /** A `commonSyncingArms` arm lifted into a class member (spec 016 lift before M10, CQ-SNAP-016-27), body unchanged:
    * `PivotStateUnservable`, a coordinator's report that peers no longer serve the root: the debounce, the
    * peer-scarcity refresh, the counted stateless refresh and, at `MaxConsecutivePivotRefreshes`, the in-place refresh
    * (accounts complete) or the critical failure (dormant or `DelayedRestart`). `commonSyncingArms` delegates to it at
    * the arm's original position, so the arm order is unchanged.
    */
  private[snap] lazy val pivotStateUnservableArms: PartialFunction[Command, Behavior[Command]] = {
    case PivotStateUnservable(rootHash, reason, emptyResponses, cause) =>
      // When peers can no longer serve the current state root, refresh the pivot in-place
      // instead of restarting. This preserves downloaded trie data (content-addressed nodes
      // are ~99.9% valid across pivot changes) and avoids the download-stall-restart loop.
      var earlyBehavior: Option[Behavior[Command]] = None
      val now = System.currentTimeMillis()
      if now - lastPivotRestartMs < MinPivotRestartInterval.toMillis then
        // Within the debounce window a pivot refresh is already in flight (or just produced a
        // same-root no-op). Do NOT swallow the escalation — that left the coordinators' stateless
        // set full so they re-escalated forever and the node wedged on a dead pivot. Re-arm them
        // against the current root so stateless tracking clears and dispatch resumes.
        // INVARIANT: do not increment consecutivePivotRefreshes and do not call any destructive
        // path here — only the consecutivePivotRefreshes>=Max branch may reach restart/dormant.
        ctx.log.info(
          s"Debouncing PivotStateUnservable (refresh in flight, phase=$currentPhase, " +
            s"emptyResponses=$emptyResponses, reason=$reason) — re-arming coordinators"
        )
        stateRoot.foreach(root => reArmRangeCoordinators(root.value))
      else if !SNAPSyncController.countsTowardRestart(cause) &&
        (currentPhase == AccountRangeSync || currentPhase == ByteCodeAndStorageSync)
      then
        // Peer scarcity / cooling: nothing could be dispatched, but no peer said the root is gone. Refresh the
        // pivot so the root stays inside the serve window, but do NOT count it toward the restart threshold and do
        // NOT blacklist the pivot block — a restart cannot create peers, it only throws work away.
        lastPivotRestartMs = now
        ctx.log.info(
          s"Pivot refresh for peer scarcity (not stateless; not counted toward restart, " +
            s"stateless count stays $consecutivePivotRefreshes/$MaxConsecutivePivotRefreshes): $reason"
        )
        refreshPivotInPlace(reason)
      else if currentPhase == AccountRangeSync || currentPhase == ByteCodeAndStorageSync then
        lastPivotRestartMs = now
        // Record the current pivot block as failed so completePivotRefreshWithStateRoot can
        // detect when refreshPivotInPlace resolves back to the same block.
        pivotBlock.foreach(failedPivotBlocks.add)
        consecutivePivotRefreshes += 1
        ctx.log.info(
          s"Consecutive stateless pivot refreshes: $consecutivePivotRefreshes/$MaxConsecutivePivotRefreshes"
        )
        if consecutivePivotRefreshes >= MaxConsecutivePivotRefreshes then
          if accountsComplete then
            // Geth/Besu aligned: NEVER restart after accounts complete.
            // Accounts are the most expensive phase (~85.9M on ETC). Bytecodes are
            // content-addressed and don't depend on state root. Storage tasks can be
            // refreshed in-place. Refresh pivot for storage coordinator only.
            ctx.log.warn(
              s"$consecutivePivotRefreshes consecutive unservable pivots during bytecode/storage. " +
                "Refreshing in-place (preserving accounts)."
            )
            consecutivePivotRefreshes = 0 // Reset — accounts completing IS progress
            refreshPivotInPlace(reason, pivotUnservable = true)
          else
            // Accounts still in progress. Enter dormant retry mode — preserving all RocksDB
            // data and waiting for peers with exponential backoff.
            ctx.log.warn(
              s"$consecutivePivotRefreshes consecutive pivot refreshes without progress. " +
                "Peers likely lack snapshot databases."
            )
            if recordCriticalFailure(s"$consecutivePivotRefreshes consecutive stateless pivot refreshes") then
              enterDormantMode(
                s"critical failure threshold reached: $consecutivePivotRefreshes consecutive stateless pivot refreshes"
              )
            else
              val restartDelay = math.min(30 * criticalFailureCount, 120).seconds
              if restartDelay > Duration.Zero then
                ctx.log.info(
                  s"Delaying SNAP restart by ${restartDelay.toSeconds}s " +
                    s"(criticalFailureCount=$criticalFailureCount)"
                )
                timers.startSingleTimer(
                  "delayed-restart",
                  DelayedRestart(
                    s"consecutive stateless pivots ($consecutivePivotRefreshes): $reason"
                  ),
                  restartDelay
                )
              else
                earlyBehavior = Some(
                  restartSnapSync(s"consecutive stateless pivots ($consecutivePivotRefreshes): $reason")
                )
        else refreshPivotInPlace(reason, pivotUnservable = true)
      else ctx.log.info(s"Ignoring PivotStateUnservable in phase=$currentPhase (reason=$reason)")
      earlyBehavior.getOrElse(Behaviors.same)
  }

  /** A `commonSyncingArms` arm lifted into a class member (spec 016 lift before M10), body unchanged:
    * `BootstrapComplete` while `pendingPivotRefresh` is set, the header an in-place pivot refresh asked for: commit the
    * refresh, or restart when no header came back. With no refresh pending the guard is false and the message falls
    * through to the next arm of `commonSyncingArms`, as before. `commonSyncingArms` delegates to it at the arm's
    * original position, so the arm order is unchanged.
    */
  private[snap] lazy val pivotRefreshBootstrapCompleteArms: PartialFunction[Command, Behavior[Command]] = {
    // Handle pivot header bootstrap completion during active sync.
    // This arrives when refreshPivotInPlace() requested a header from a peer because
    // it wasn't available locally. The coordinator is still alive with all its state.
    case BootstrapComplete(pivotHeaderOpt) if pendingPivotRefresh.isDefined =>
      val (pendingPivot, reason) = pendingPivotRefresh.get
      pendingPivotRefresh = None
      pivotHeaderOpt match
        case Some(header) =>
          ctx.log.info(s"Pivot header bootstrap complete for block ${header.number} (requested $pendingPivot)")
          completePivotRefreshWithStateRoot(pendingPivot, header, reason)
          Behaviors.same
        case None =>
          ctx.log.warn(
            s"Pivot header bootstrap for block $pendingPivot returned no header. Falling back to full restart."
          )
          restartSnapSync(s"pivot refresh bootstrap returned no header for $pendingPivot: $reason")
  }

  /** A `commonSyncingArms` arm lifted into a class member (spec 016 lift before M10), body unchanged:
    * `PivotBootstrapFailed` while `pendingPivotRefresh` is set: backtrack by `pivotBlockOffset`
    * (`RetryBootstrapAtBlock` in 5 s), or retry the refresh in 60 s when the backtracked block is not positive. With no
    * refresh pending the guard is false and the message falls through to the next arm of `commonSyncingArms`, as
    * before. `commonSyncingArms` delegates to it at the arm's original position, so the arm order is unchanged.
    */
  private[snap] lazy val pivotRefreshBootstrapFailedArms: PartialFunction[Command, Behavior[Command]] = {
    // Handle pivot header bootstrap failure. Instead of recalculating from network-best (which advances
    // the pivot to a block peers don't have yet), backtrack by pivotBlockOffset blocks (Besu pattern).
    case PivotBootstrapFailed(reason) if pendingPivotRefresh.isDefined =>
      val (pendingPivot, originalReason) = pendingPivotRefresh.get
      pendingPivotRefresh = None
      timers.cancel(PivotBootstrapRetryKey)
      val backtrackedPivot = pendingPivot - snapSyncConfig.pivotBlockOffset
      if backtrackedPivot > 0 then
        ctx.log.warn(
          s"Pivot header bootstrap failed for block $pendingPivot (reason: $reason, original: $originalReason). " +
            s"Backtracking pivot to $backtrackedPivot (Besu pattern: decrement by ${snapSyncConfig.pivotBlockOffset})."
        )
        timers.startSingleTimer(PivotBootstrapRetryKey, RetryBootstrapAtBlock(backtrackedPivot), 5.seconds)
      else
        ctx.log.warn(
          s"Pivot header bootstrap failed for block $pendingPivot (reason: $reason). " +
            s"Backtracked pivot below 0 — falling back to network-best recalculation after 60s."
        )
        retryRefreshCounts = false
        retryRefreshUnservable = false
        timers.startSingleTimer(PivotBootstrapRetryKey, RetryPivotRefresh, 60.seconds)
      Behaviors.same
  }

  /** A `commonSyncingArms` arm lifted into a class member (spec 016 lift before M10), body unchanged:
    * `PivotProbeTimeout`, the 15-s timeout of a proactive roll's readiness probe: defer the roll, or force it after
    * `MaxProbeAttempts`; a timeout for a probe that is no longer pending does nothing. `commonSyncingArms` delegates to
    * it at the arm's original position, so the arm order is unchanged.
    */
  private[snap] lazy val pivotProbeTimeoutArms: PartialFunction[Command, Behavior[Command]] = {
    case PivotProbeTimeout(requestId) =>
      if pivotProbeRequestId.contains(requestId) then
        probeAttemptCount += 1
        val commitArgs = pendingProbeCommit
        pivotProbeRequestId = None
        pendingProbeCommit = None
        if probeAttemptCount >= MaxProbeAttempts then
          commitArgs.foreach { case (block, header, commitReason) =>
            ctx.log.warn(
              s"[PIVOT-PROBE] Max deferral reached after timeout ($MaxProbeAttempts/$MaxProbeAttempts) — " +
                s"forcing roll at root ${header.stateRoot.value.take(4).toHex} block $block. " +
                s"Snapshot readiness unconfirmed — brief post-roll window may occur."
            )
            probeAttemptCount = 0
            completePivotRefreshWithStateRoot(block, header, s"$commitReason (forced — max probe attempts)")
          }
        else
          ctx.log.info(
            s"[PIVOT-PROBE] Probe timed out (attempt $probeAttemptCount/$MaxProbeAttempts) — " +
              s"no response from probe peer, deferring roll (retry in ${ProbeCooldownMs / 1000}s)"
          )
          // lastProbeAttemptMs stays set — ProbeCooldownMs enforces a 30s minimum before next retry
      Behaviors.same
  }

  /** A `commonSyncingArms` arm lifted into a class member (spec 016 lift before M10), body unchanged:
    * `RetryPivotRefresh`, the 30-s/60-s pivot-refresh retry: refresh in place in the download and healing phases (the
    * phase test stays in the body), passing on whether the timer was armed by a counted attempt (`retryRefreshCounts`)
    * and that attempt's unservable-pivot fallback (`retryRefreshUnservable`, #1555). `commonSyncingArms` delegates to
    * it at the arm's original position, so the arm order is unchanged.
    */
  private[snap] lazy val retryPivotRefreshArms: PartialFunction[Command, Behavior[Command]] = {
    case RetryPivotRefresh =>
      if currentPhase == AccountRangeSync || currentPhase == ByteCodeAndStorageSync || currentPhase == StateHealing
      then
        ctx.log.info("Retrying pivot refresh after bootstrap failure...")
        // PoS only: a replay counts toward the heal budget iff the timer was armed by a counted attempt.
        // ETC/pre-merge (isPoSChain=false) keeps the unconditional default (byte-identical to base).
        // #1555: a replay of a timer armed by a counted "all healing peers stateless" attempt is still about a root
        // every peer refused, so it keeps that attempt's pivotUnservable (PoS only; ETC passes false as before).
        refreshPivotInPlace(
          "retry after bootstrap failure",
          countsTowardHealBudget = !isPoSChain || retryRefreshCounts,
          pivotUnservable = isPoSChain && retryRefreshCounts && retryRefreshUnservable
        )
      else ctx.log.info(s"Skipping pivot refresh retry — phase=$currentPhase no longer needs it")
      Behaviors.same
  }

  /** The readiness-probe branch of `commonSyncingArms`' `AccountRangeResponse` arm (spec 016 lift before M10),
    * extracted verbatim: the answer to the pending probe (`msg.requestId == pivotProbeRequestId`) commits the deferred
    * roll when it carries accounts, else defers it again, or forces it after `MaxProbeAttempts`. The arm still decides
    * whether `msg` is the probe's answer, and still forwards any other response to the account coordinator.
    */
  def handlePivotProbeResponse(msg: SNAP.AccountRange): Unit =
    pivotProbeRequestId = None
    if msg.accounts.nonEmpty then
      pendingProbeCommit.foreach { case (block, header, commitReason) =>
        pendingProbeCommit = None
        val attemptsNote = if probeAttemptCount > 0 then s" after $probeAttemptCount prior deferral(s)" else ""
        ctx.log.info(
          s"[PIVOT-PROBE] Snapshot ready at root ${header.stateRoot.value.take(4).toHex} block $block$attemptsNote — committing roll"
        )
        probeAttemptCount = 0
        completePivotRefreshWithStateRoot(block, header, commitReason)
      }
    else
      probeAttemptCount += 1
      val commitArgs = pendingProbeCommit
      pendingProbeCommit = None
      if probeAttemptCount >= MaxProbeAttempts then
        commitArgs.foreach { case (block, header, commitReason) =>
          val pivotAge = currentNetworkBestFromSnapPeers().map(_ - block).getOrElse(BigInt(-1))
          ctx.log.warn(
            s"[PIVOT-PROBE] Max deferral reached ($MaxProbeAttempts/$MaxProbeAttempts attempts) — " +
              s"forcing roll at root ${header.stateRoot.value.take(4).toHex} block $block (pivotAge≈$pivotAge). " +
              s"Snapshot readiness unconfirmed — brief post-roll window may occur."
          )
          probeAttemptCount = 0
          completePivotRefreshWithStateRoot(block, header, s"$commitReason (forced — max probe attempts)")
        }
      else
        ctx.log.info(
          s"[PIVOT-PROBE] Snapshot not indexed yet " +
            s"(attempt $probeAttemptCount/$MaxProbeAttempts) — " +
            s"deferring roll, coordinators unaffected (retry in ${ProbeCooldownMs / 1000}s)"
        )

  /** Refresh the pivot block and state root without destroying coordinators.
    *
    * Unlike restartSnapSync() which tears down every coordinator (account ranges then resume from their cursors with
    * the carried contract work; in-flight storage/bytecode/healing state is lost), this method:
    *   1. Selects a fresher pivot from current network best 2. Updates internal pivot/stateRoot tracking 3. Sends
    *      PivotRefreshed to the active coordinator 4. Resets stagnation timer so the watchdog doesn't trigger
    *
    * Downloaded trie nodes are content-addressed (keyed by keccak256 hash), so ~99.9% remain valid across pivot
    * changes. Root mismatch (if any) is resolved during the healing phase.
    */
  def refreshPivotInPlace(
      reason: String,
      countsTowardHealBudget: Boolean = true,
      pivotUnservable: Boolean = false
  ): Unit =
    ctx.log.info(s"Refreshing pivot in-place: $reason")

    // CL-anchored pivot selection for post-merge chains (geth's BeaconSync pattern).
    // Mirrors what startSnapSync does at line 1769 — the CL hint represents authoritative
    // chain tip from forkchoiceUpdated, NOT peer-reported best.
    //
    // Peer `maxBlockNumber` is unreliable on post-merge chains:
    //   - NewBlock gossip is dead (the historic update path)
    //   - ETH/69 BlockRangeUpdate only fires on a minority of peers
    //   - PR #1238's best-block probe only re-checks every 5 min
    //
    // Sepolia 2026-05-15 observation: 2-4 peers all reported `maxBlockNumber=10853243`
    // for ~30 min while the actual chain head was at 10854389+. Selecting from
    // peer-reported best gave us a pivot 1146 blocks BEHIND the CL head, peers couldn't
    // serve that root either, and we looped forever.
    //
    // Pre-merge chains keep peer-reported best because there's no authoritative tip.
    val clHeadNumber: Option[BigInt] =
      if isPoSChain then clPivotHint.flatMap(_.knownHeader).map(_.number.value) else None

    val newPivotOpt: Option[BigInt] = clHeadNumber match
      case Some(clHead) =>
        // Post-merge: pivot = CL head - offset. Strict forward-only: never accept a
        // pivot below our current one (which could happen if the CL hint regressed,
        // though that should not happen for forkchoiceUpdated).
        val target = clHead - snapSyncConfig.pivotBlockOffset
        val currentPivot = pivotBlock.getOrElse(BigInt(0))
        if SNAPSyncController.clPivotNotYetAdvanced(clHead, snapSyncConfig.pivotBlockOffset, currentPivot) then
          // The current pivot is KNOWN unservable (every snap peer answered empty-without-proof for its root) yet the
          // CL head has not moved far enough to give a newer one: the CL can lag the network (Lighthouse catching up
          // on Platåberget lagged ~110 blocks), and the roots peers still serve are newer than the CL's head. Never
          // leave such a pivot in place: take a smaller offset below the CL head, or the snap peers' advertised
          // tip (capped to a bounded lead over the CL head), both minus the serve-window margin. The header is
          // still fetched by the normal pivot bootstrap; the anchor guard does NOT authenticate it (snap root and
          // stateRoot come from the same peer header), which is why the peer tip is capped.
          val fallback =
            if pivotUnservable then
              SNAPSyncController.unservablePivotTarget(
                clHead,
                currentPivot,
                currentNetworkBestFromSnapPeers(),
                SnapServeWindowMargin
              )
            else None
          fallback match
            case Some(t) =>
              ctx.log.warn(
                s"CL-based pivot $target not newer than unservable pivot $currentPivot (CL head=$clHead); " +
                  s"re-pivoting to $t (clHead-margin / capped peer tip) instead of keeping a pivot peers cannot serve."
              )
              Some(t)
            case None =>
              ctx.log.info(
                s"CL-based pivot $target not strictly newer than current $currentPivot " +
                  s"(CL head=$clHead, offset=${snapSyncConfig.pivotBlockOffset}). Skipping refresh."
              )
              None
        else
          ctx.log.info(s"Selected CL-anchored pivot $target (CL head=$clHead, current=$currentPivot)")
          Some(target).filter(_ > 0)

      case None =>
        // Pre-merge fallback: peer-reported best subject to freshness floor.
        currentNetworkBestFromSnapPeers()
          .filter { networkBest =>
            SNAPSyncController.pivotPassesFreshnessFloor(
              networkBest = networkBest,
              clHeadNumber = clHeadNumber,
              maxStaleness = snapSyncConfig.maxPivotStalenessBlocks
            ) match
              case Right(()) => true
              case Left(floor) =>
                ctx.log.warn(
                  s"Rejecting stale SNAP peer best=$networkBest as pivot — below CL-anchored " +
                    s"freshness floor=$floor (CL head=${clHeadNumber.getOrElse("?")}, " +
                    s"maxPivotStalenessBlocks=${snapSyncConfig.maxPivotStalenessBlocks}). " +
                    "Waiting for a fresher SNAP-capable peer."
                )
                false
          }
          // Back the roll target off the live tip by at least SnapServeWindowMargin so the
          // new root is one peers have actually indexed. pivotBlockOffset stays a floor (a
          // larger configured offset still wins). Rolling to networkBest exactly (offset=0)
          // froze the ETC pivot on 2026-06-01 — peers emit "not indexed" for the tip root.
          // NOTE: the post-merge (CL-anchored) branch above has the same latent issue at
          // offset=0; deferred — it fires rarely and has its own freshness-floor handling.
          .map(networkBest => networkBest - BigInt(snapSyncConfig.pivotBlockOffset).max(SnapServeWindowMargin))
          .filter(_ > 0)

    if newPivotOpt.isEmpty then
      // spec 009 T014 (Moving-Root Delta Heal — bounded re-peg last-resort). Under `movingRootDeltaHeal`, a re-peg
      // during StateHealing that finds NO suitable served root counts against a budget; once exhausted, take the SAME
      // fail-SAFE lazy-heal handoff the (now-rare) HealingRootUnservable signal uses — completeSnapSync() → on-demand
      // GetTrieNodes during block execution. CONSTRAINT 1 (never fail-OPEN): completeSnapSync → finalizeSnapSync STILL
      // enforces the snapStateRoot == pivotHeader.stateRoot anchor guard, so an incomplete state CANNOT finalize (a
      // mismatch escalates to HealingImpossible/restart) — this is the lazy on-demand-heal handoff (FR-001/FR-008), not
      // a forced completion. CONSTRAINT 2 (return-Behavior): completeSnapSync() returns a narrowing Behavior[Command]
      // but refreshPivotInPlace is Unit; exactly as the HealingRootUnservable handler does, we call it for its side
      // effects (currentPhase=Completed, syncController ! Done, child teardown) and discard the returned Behavior.
      // Outside healing or flag OFF: the unbounded 30s-retry stays byte-identical (SNAP peers are intermittent on ETC).
      if snapSyncConfig.movingRootDeltaHeal && currentPhase == StateHealing then
        if !countsTowardHealBudget then
          // Platåberget ePBS-devnet soak, 2026-09-27/28 (BUG-BC3 + follow-ups): this attempt is NOT a report that
          // the current heal root is unservable (that signal is HealingAllPeersStateless, which counts). Either it
          // is the proactive "heal root stale" probe, or a PoS leftover retry timer armed outside StateHealing
          // (see retryRefreshCounts). "No newer CL pivot yet" is the ordinary outcome while the CL is stalled
          // (Lighthouse cannot advance while its EL reports SYNCING) and healing keeps progressing on the
          // still-served root. Do not touch healRepegNoRootAttempts and do not re-arm PivotBootstrapRetryKey:
          // maybeRequestHealingServeRoot's own per-tick cadence already re-checks, and a genuine unservable-root
          // report (HealingAllPeersStateless) arms its own COUNTED retry chain. Deliberately NOT keyed on WHY
          // newPivotOpt is empty: with a live CL hint it is empty only via the CL-stalled branch, so a
          // reason-keyed exemption would also swallow genuine stateless reports and wedge the handoff.
          ctx.log.info(
            s"No newer pivot available yet ($reason) — continuing healing on the current root " +
              s"(pivot=${pivotBlock.getOrElse("?")}). Not counted against the re-peg budget."
          )
        else
          healRepegNoRootAttempts += 1
          if healRepegNoRootAttempts >= MaxHealRepegNoRootAttempts && lazyHandoffBlockedByAbsentPathRoot() then
            // #1555: under the Path scheme the hand-off publishes the trie to the hash-keyed store and verifies it from
            // the pivot root; with that root's node stored nowhere the publish scans the whole state (6-12 minutes on
            // Platåberget), fails "Root node not found", and SyncController restarts SNAP. Keep healing on the current
            // root instead and keep retrying the re-peg: a fresh budget, and the same counted, unservable-preserving
            // 30 s retry as below. This repeats (one WARN per exhausted budget) until the CL head or a snap peer
            // offers a newer target. Hash scheme (ETC) never reaches this branch.
            ctx.log.warn(
              s"[HEAL-REPEG] No servable root for $healRepegNoRootAttempts consecutive re-peg attempts, but the heal " +
                s"root ${stateRoot.map(_.value.toHex.take(16)).getOrElse("?")} at pivot ${pivotBlock.getOrElse("?")} " +
                "is not stored locally (Path scheme), so the lazy hand-off could not publish it. NOT handing off: " +
                "continuing to heal and retrying the re-peg every 30s with a fresh budget."
            )
            healRepegNoRootAttempts = 0
            timers.cancel(PivotBootstrapRetryKey)
            retryRefreshCounts = true
            retryRefreshUnservable = pivotUnservable
            timers.startSingleTimer(PivotBootstrapRetryKey, RetryPivotRefresh, 30.seconds)
          else if healRepegNoRootAttempts >= MaxHealRepegNoRootAttempts then
            ctx.log.warn(
              s"[HEAL-REPEG] No servable root for $healRepegNoRootAttempts consecutive re-peg attempts (budget " +
                s"$MaxHealRepegNoRootAttempts exhausted, ~${MaxHealRepegNoRootAttempts * 30}s). Taking the fail-safe " +
                s"lazy-heal handoff (completeSnapSync) — missing nodes fetched on-demand via GetTrieNodes during block " +
                s"execution; the anchor guard still gates finalization. NOT a false completion."
            )
            timers.cancel(PivotBootstrapRetryKey)
            healRepegNoRootAttempts = 0
            // Platåberget soak, 2026-09-27: see anchorPivotBeforeLazyHandoff's doc (near completeSnapSync). This
            // handoff fires after zero or more successful re-pegs that (correctly, per BUG-006) never persisted —
            // anchor now, before the terminal handoff, so the A5 guard in finalizeSnapSync compares like-for-like.
            anchorPivotBeforeLazyHandoff("HEAL-REPEG budget exhausted")
            completeSnapSync()
          else
            ctx.log.warn(
              s"Cannot re-peg heal root: no suitable SNAP peers available (attempt " +
                s"$healRepegNoRootAttempts/$MaxHealRepegNoRootAttempts). Scheduling retry in 30s."
            )
            timers.cancel(PivotBootstrapRetryKey)
            retryRefreshCounts = true // armed by a COUNTED attempt: its replays count too
            retryRefreshUnservable = pivotUnservable // #1555: ... and keep its unservable-pivot fallback
            timers.startSingleTimer(PivotBootstrapRetryKey, RetryPivotRefresh, 30.seconds)
      else
        ctx.log.warn(
          "Cannot refresh pivot: no suitable SNAP peers available. Scheduling retry in 30s."
        )
        // Don't restart or fallback — SNAP peers are intermittent on ETC mainnet.
        // The serve window is ~28 min; peers will reappear when new blocks are mined.
        // Restarting can't help with no peers, and it destroys all downloaded trie data.
        timers.cancel(PivotBootstrapRetryKey)
        retryRefreshCounts = false // armed outside StateHealing: a replay landing in healing must not count (PoS)
        retryRefreshUnservable = false
        timers.startSingleTimer(PivotBootstrapRetryKey, RetryPivotRefresh, 30.seconds)
    else
      val newPivotBlock = newPivotOpt.get
      val newPivotHeaderOpt = stateIngest.chainReader.getBlockHeaderByNumber(newPivotBlock)

      if newPivotHeaderOpt.isEmpty then
        // Header not available locally — request it from a peer via the bootstrap mechanism.
        // The coordinator stays alive (all peers are stateless, so no dispatch will happen).
        // When BootstrapComplete arrives in the syncing state, the refresh is completed.
        ctx.log.info(s"Pivot header for block $newPivotBlock not available locally. Requesting header bootstrap...")
        // Pause chain download to free up peers for the pivot header bootstrap
        chainDownloader.tell(ChainDownloader.Pause)
        pendingPivotRefresh = Some((newPivotBlock, reason))
        syncController ! StartRegularSyncBootstrap(newPivotBlock)
        // Reset account stagnation timer while we wait for the header.
        // Note: do NOT reset lastStorageProgressMs here — if storage has been stalled with
        // no actual slot progress, the stagnation timer must continue counting so it can
        // eventually trigger a full restart rather than cycling pivots indefinitely.
        resetStagnationClocks(StagnationWatchdog.ResetReason.PivotRefreshHeaderFetch)
      else completePivotRefreshWithStateRoot(newPivotBlock, newPivotHeaderOpt.get, reason)

  /** Complete the pivot refresh once we have the header (either from local storage or bootstrapped from peer). */
  def completePivotRefreshWithStateRoot(
      newPivotBlock: BigInt,
      newPivotHeader: BlockHeader,
      reason: String
  ): Unit =
    // Async-validation safety: if a validation Future is in flight, the new
    // pivot would invalidate its assumptions about (root, pivot). Refuse the
    // refresh during StateValidation; the validation will run to completion
    // against the original root and the controller will re-enter healing if
    // needed. (PivotStateUnservable normally only fires in earlier phases, but
    // a stray BootstrapComplete from a slow peer can still arrive here.)
    if currentPhase == StateValidation then
      ctx.log.info(
        s"Ignoring pivot refresh during StateValidation: $reason " +
          s"(would mutate root from ${stateRoot.map(_.value.take(4).toHex).getOrElse("none")} to ${newPivotHeader.stateRoot.value.take(4).toHex})"
      )
    else
      val newStateRoot = newPivotHeader.stateRoot
      val oldPivot = pivotBlock.getOrElse(BigInt(0))
      val oldRoot = stateRoot.map(_.value.take(4).toHex).getOrElse("none")
      val newRoot = newStateRoot.value.take(4).toHex

      if stateRoot.contains(newStateRoot) then
        // No-op same-root pivot refresh. PR #1236 fixed this on one path; this is the second
        // path (completePivotRefreshWithStateRoot) that previously called restartSnapSync and
        // wiped in-memory progress. Observed sepolia 2026-05-15 00:50:36: lost ~500K accounts
        // of progress (~67% keyspace) because a stall-recovery pivot landed on the same root.
        //
        // A same-root "refresh" is information-free for the coordinator (their data for this
        // root is unchanged) but does not warrant destroying state. Strikes and snapless have
        // already been cleared at coordinator level via PR #1255; nothing further to do.
        // BUG fix: when the serve window expired and peers can no longer serve THIS root but the
        // refresh resolved to the same root (e.g. a scarce snap-peer pool with no newer servable
        // pivot), returning without notifying the coordinators left their statelessPeers set full
        // → they re-escalated forever and the node wedged. Re-arm them against the current root so
        // stateless tracking clears and dispatch resumes. PivotRefreshed is idempotent on an
        // unchanged root (the handler's `stateRoot = root` is a no-op), so no state is destroyed.
        ctx.log.info(
          s"Pivot refresh produced same root ($newRoot): $reason. Re-arming coordinators to clear stateless peers."
        )
        // If this pivot block is known-failed, fast-track the counter to the threshold so the
        // NEXT PivotStateUnservable triggers dormant mode (2–3 cycles instead of 10). Without
        // this, the churn loop runs the full 10-cycle budget before escalating — ~20 min of
        // 1/12th-throughput degradation when ETH-genesis peers contaminate the storage pool.
        // Re-arm happens unconditionally (prevents coordinator wedge); the counter jump is additive.
        if pivotBlock.exists(failedPivotBlocks.contains) then
          ctx.log.warn(
            s"Same-root refresh resolved to known-failed pivot block ${pivotBlock.getOrElse("?")} " +
              s"(root $newRoot). Fast-tracking consecutivePivotRefreshes to $MaxConsecutivePivotRefreshes."
          )
          consecutivePivotRefreshes = MaxConsecutivePivotRefreshes
        reArmRangeCoordinators(newStateRoot.value)
      else

        // Pivot readiness probe: for proactive rolls, verify the new root is indexed on at least
        // one peer before notifying coordinators. Coordinators continue on the old root uninterrupted
        // until probe confirms readiness — eliminating the ~8-minute dead window.
        var deferForProbe = false
        if proactiveRollNeedsProbe then
          proactiveRollNeedsProbe = false
          val snapPeerOpt = bestSnapProbeTarget()
          val totalSnapPeers = peersToDownloadFrom.values.count(_.peerInfo.remoteStatus.supportsSnap)
          snapPeerOpt match
            case Some((peerWithInfo, peerKind)) =>
              import com.chipprbots.ethereum.network.NetworkPeerManagerActor
              import com.chipprbots.ethereum.network.p2p.messages.SNAP.GetAccountRange.GetAccountRangeEnc
              val probeId = requestTracker.generateRequestId()
              val probe = GetAccountRange(
                requestId = probeId,
                rootHash = newStateRoot.value,
                startingHash = ByteString(Array.fill[Byte](32)(0)),
                limitHash = ByteString(Array.fill[Byte](32)(0xff.toByte)),
                responseBytes = 1024
              )
              networkPeerManager ! NetworkPeerManagerActor.SendMessageCmd(
                new GetAccountRangeEnc(probe),
                peerWithInfo.peer.id
              )
              pivotProbeRequestId = Some(probeId)
              pendingProbeCommit = Some((newPivotBlock, newPivotHeader, reason))
              // C4: keyed by the probe's BigInt requestId so a stale probe's timeout never cancels a newer one.
              timers.startSingleTimer(s"pivot-probe-$probeId", PivotProbeTimeout(probeId), 15.seconds)
              ctx.log.info(
                s"[PIVOT-PROBE] Probing $peerKind ${peerWithInfo.peer.id.value} " +
                  s"($totalSnapPeers snap peers available) " +
                  s"for root ${newStateRoot.value.take(4).toHex} block $newPivotBlock " +
                  s"pivot=${pivotBlock.getOrElse(0)} — deferring coordinator notification"
              )
              deferForProbe = true
            case None =>
              ctx.log.info(
                s"[PIVOT-PROBE] No SNAP peers available for readiness probe ($totalSnapPeers total) " +
                  s"— committing proactive roll immediately at root ${newStateRoot.value.take(4).toHex}"
              )
        if !deferForProbe then

          // Gate on ETH/Sepolia only — ETC pivot headers use StdBlockHeaderValidator (PoW).
          val posValidationOk = if isPoSChain then
            given bc: BlockchainConfig = com.chipprbots.ethereum.utils.Config.blockchains.blockchainConfig
            BlockHeader.validateFieldCount(newPivotHeader, bc) match
              case Left(msg) =>
                ctx.log.error(
                  "SNAP pivot header field-count mismatch — aborting pivot commit: {}",
                  msg
                )
                false
              case Right(_) =>
                PoSBlockHeaderValidator.validateHeaderOnly(newPivotHeader) match
                  case Left(err) =>
                    ctx.log.error(
                      "SNAP pivot header failed post-merge validation — aborting pivot commit: {}",
                      err
                    )
                    false
                  case Right(_) => true
          else true

          if posValidationOk then
            ctx.log.info(s"Pivot refreshed: block $oldPivot -> $newPivotBlock, root $oldRoot -> $newRoot")

            // Update internal state
            pivotBlock = Some(newPivotBlock)
            // BUG fix: advance the preserved-progress anchor with the live pivot so on-disk range
            // cursors stay within MaxPreservedPivotDistance of the pivot we restart at. The anchor was
            // latched once to the ORIGINAL pivot and never advanced, so after many in-place refreshes a
            // restart found the saved pivot thousands of blocks stale, failed the drift check, and wiped
            // ~10M accounts of cursors (full keyspace re-scan). Account-trie nodes are content-addressed
            // (keccak256-keyed), so a traversal cursor is valid across any pivot jump; healing reconciles
            // the per-leaf delta against the new root.
            if preservedAtPivotBlock.isDefined then
              preservedAtPivotBlock = Some(newPivotBlock)
              if preservedRangeProgress.nonEmpty then
                snapProgressStorage.writeAccountCursors(
                  newStateRoot.value,
                  newPivotBlock.toLong,
                  preservedRangeProgress.map { case (k, v) => k.toHex -> v.toHex }
                )
            stateRoot = Some(newStateRoot)
            // Bump validationGeneration so any in-flight validation Future's result
            // is dropped on arrival rather than applied against the stale root. The
            // phase gate above is the primary defense; this is defense-in-depth for
            // any code path that mutates pivot/root from a different phase.
            validationGeneration += 1
            lastProactivePivotBlock =
              // approximate networkBest at roll time; pivotBlock = networkBest - max(offset, margin)
              pivotBlock.map(_ + BigInt(snapSyncConfig.pivotBlockOffset).max(SnapServeWindowMargin))
            // Note: mptStorage stays the same — content-addressed nodes don't need re-tagging.
            // The backing storage was already created for the original pivot block number,
            // but since nodes are keyed by hash, they're valid for any root.

            // Persist new pivot — but NOT during healing: AppStateStorage is updated only when healing
            // succeeds (trie walk finds 0 missing nodes). Mid-healing writes cause root mismatch (BUG-006):
            // the new root is stored before all its nodes are healed, then validateState() sees a mismatch.
            if currentPhase != StateHealing then
              stateIngest.appState
                .putSnapSyncPivotBlock(newPivotBlock)
                .and(stateIngest.appState.putSnapSyncStateRoot(newStateRoot.value))
                .commit()
            updateBestBlockForPivot(newPivotHeader, newPivotBlock)
            SNAPSyncMetrics.setPivotBlockNumber(newPivotBlock)
            SNAPSyncMetrics.incrementPivotRefreshed()

            // Geth-aligned: send refresh signal to ALL active coordinators (all 3 run concurrently), in order:
            // account, storage, bytecode, healing. Bytecodes are content-addressed (hash-keyed) so pivot changes
            // don't invalidate them, but the coordinator should clear stale peer tracking. Healing coordinator:
            // update root, clear pending tasks and stateless peers, then re-walk the trie with the new root to
            // discover missing nodes.
            pivotRefreshed(newStateRoot.value)
            // spec 009 T014: a successful re-peg landed a fresh served root — reset the bounded no-root budget so the
            // lazy-heal last-resort only fires after a fresh run of consecutive empty re-peg attempts. Harmless flag-OFF
            // (the budget is never incremented unless movingRootDeltaHeal && StateHealing).
            healRepegNoRootAttempts = 0
            // PoS only (ETC/pre-merge byte-identical): a counted retry timer armed before this successful re-peg
            // must not replay afterwards and count again against a stalled CL (forge, BUG-BC3 3rd follow-up).
            if isPoSChain then
              retryRefreshCounts = false
              retryRefreshUnservable = false
              timers.cancel(PivotBootstrapRetryKey)
            // Chain download target extends to the new pivot (chain data is canonical, never invalidated)
            if chainDownloader.isDefined then
              chainDownloader.tell(ChainDownloader.UpdateTarget(newPivotBlock))
              // Resume chain download if it was paused during pivot header bootstrap
              chainDownloader.tell(ChainDownloader.Resume)
            else
              // Start chain downloader if not yet started (e.g. pivot was 0 at initial bootstrap)
              startChainDownloader()

            // Reset account stagnation timer during pivot refresh recovery.
            // Note: do NOT reset lastStorageProgressMs — only actual slot downloads (via
            // ProgressStorageSlotsSynced) should reset the storage stagnation timer.
            // This ensures that repeated stateless-peer pivot cycles don't prevent the
            // stagnation watchdog from eventually triggering a full restart.
            resetStagnationClocks(StagnationWatchdog.ResetReason.PivotRefreshCommit)
          // end if posValidationOk
        // end if !deferForProbe
      // end else (stateRoot changed)
    // end else (not StateValidation phase)

  /** The proactive pivot roll's trigger (spec 016 T053c, CQ-SNAP-016-31), extracted verbatim from
    * `checkDownloadStagnationArms`, which calls it at the same point of the 30-s stagnation tick: in the two download
    * phases, roll the pivot in place (with a readiness probe) once it is more than `SnapServeWindowBlocks` behind the
    * SNAP peers' best block, unless a roll landed within 50 blocks, a probe is in flight or pending commit, storage is
    * at least 80% done, or the 30-s probe cooldown has not elapsed. Pivot-refresh policy (`[PIVOT-ROLL]`): M7b moves it
    * to `PivotRefresher` and the arm to `StagnationWatchdog`.
    */
  def maybeProactivePivotRoll(): Unit =
    // Proactive pivot roll: keep pivot within core-geth's 128-block snapshot window.
    if (currentPhase == AccountRangeSync || currentPhase == ByteCodeAndStorageSync) &&
      pivotBlock.isDefined
    then
      currentNetworkBestFromSnapPeers().foreach { networkBest =>
        val pivotAge = networkBest - pivotBlock.get
        val recentlyRolled = lastProactivePivotBlock.exists(last => (networkBest - last) <= BigInt(50))
        val storageLateStage =
          currentPhase == ByteCodeAndStorageSync && storageContractProgressPct >= 0.80
        if pivotAge > SnapServeWindowBlocks && !recentlyRolled &&
          pivotProbeRequestId.isEmpty && pendingProbeCommit.isEmpty &&
          !storageLateStage
        then
          val now = System.currentTimeMillis
          if now - lastProbeAttemptMs >= ProbeCooldownMs then
            ctx.log.info(
              s"[PIVOT-ROLL] Proactive pivot roll: pivot=${pivotBlock.get} " +
                s"network=$networkBest age=$pivotAge — readiness probe will fire after header fetch"
            )
            proactiveRollNeedsProbe = true
            lastProbeAttemptMs = now
            refreshPivotInPlace("proactive pivot roll")
      }
