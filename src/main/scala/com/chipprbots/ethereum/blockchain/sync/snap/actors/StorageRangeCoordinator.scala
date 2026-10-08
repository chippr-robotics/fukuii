package com.chipprbots.ethereum.blockchain.sync.snap.actors

import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.TimerScheduler
import org.apache.pekko.util.ByteString

import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*

import com.chipprbots.ethereum.blockchain.sync.snap.*
import com.chipprbots.ethereum.db.dataSource.DataSourceBatchUpdate
import com.chipprbots.ethereum.db.storage.FlatSlotStorage
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.db.storage.PathNodeStorage
import com.chipprbots.ethereum.db.storage.SnapStorageDoneStorage
import com.chipprbots.ethereum.db.storage.SnapSyncProgressStorage
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.p2p.MessageSerializable
import com.chipprbots.ethereum.network.p2p.messages.SNAP.*
import com.chipprbots.ethereum.utils.ByteStringUtils.ByteStringOps

/** StorageRangeCoordinator manages storage range download workers and orchestrates the storage sync phase.
  *
  * Downloads storage ranges for contract accounts in parallel, verifies storage proofs, and stores storage slots
  * locally. Uses adaptive per-peer tuning for response size, batch size, and stateless peer detection to maximize
  * throughput within snap/1 protocol limits.
  *
  * @param initialStateRoot
  *   State root hash
  * @param networkPeerManager
  *   Network manager
  * @param requestTracker
  *   Request tracker
  * @param mptStorage
  *   MPT storage
  * @param maxAccountsPerBatch
  *   Max accounts per batch
  * @param maxInFlightRequests
  *   Max concurrent in-flight requests
  * @param requestTimeout
  *   Timeout for individual requests
  * @param snapSyncController
  *   Parent controller
  */
private[actors] class StorageRangeCoordinatorImpl(
    context: ActorContext[StorageRangeCoordinator.Command],
    timers: TimerScheduler[StorageRangeCoordinator.Command],
    initialStateRoot: ByteString,
    networkPeerManager: org.apache.pekko.actor.typed.ActorRef[NetworkPeerManagerActor.Command],
    requestTracker: SNAPRequestTracker,
    mptStorage: MptStorage,
    flatSlotStorage: FlatSlotStorage,
    maxAccountsPerBatch: Int,
    maxInFlightRequests: Int,
    requestTimeout: FiniteDuration,
    snapSyncController: org.apache.pekko.actor.typed.ActorRef[SNAPSyncController.Command],
    initialMaxInFlightPerPeer: Int = 5,
    configInitialResponseBytes: Int = 1048576,
    configMinResponseBytes: Int = 131072,
    deferredMerkleization: Boolean = true,
    flatBatchEntryThreshold: Int = 1000,
    flatBatchEcOverride: Option[ExecutionContext] = None,
    // Back-pressure watermarks — overridable in tests so we don't have to enqueue 100K StorageTasks
    // just to verify the pause/resume transition. Production defaults match the values quoted in
    // the design discussion.
    backpressureHighWatermark: Int = 100000,
    backpressureLowWatermark: Int = 50000,
    // Streaming storage-trie cap: how many per-account `SnapHashTrie` instances may be live at
    // once. The trie wrapper bounds each instance to ~8 MiB (DefaultBatchSizeBytes), so the
    // worst-case storage-processing footprint is `maxConcurrentStorageAccounts × 8 MiB`. Default
    // 256 → ~2 GiB ceiling, independent of chain size. Raise via sync.conf if the peer pool
    // can justify a larger working set; lower if running with smaller `-Xmx`.
    maxConcurrentStorageAccounts: Int = 256,
    snapProgressStorage: Option[SnapSyncProgressStorage] = None,
    storageScheme: StorageScheme = StorageScheme.Hash,
    pathNodeStorage: Option[PathNodeStorage] = None,
    // Persist a completion marker per fully-downloaded account (SnapStorageDoneStorage) so a resume re-queues only
    // unfinished storage tasks. Off by default: only SNAPSyncController's account-phase and recovery coordinators read
    // the markers back.
    recordStorageDone: Boolean = false,
    // Spec 014: shared admission gate. This coordinator acknowledges received tasks and publishes its queue depth so the
    // producers can wait synchronously instead of through the mailbox-borne watermark signal. None = not wired (tests).
    intakeBudget: Option[SnapIntakeBudget] = None
):

  import StorageRangeCoordinator.*

  private val log = context.log
  // `self` was a Classic field; under Typed it is `context.self`. Captured here so Future
  // continuations and scheduled timers reference the same value the old code expected.
  private val self: org.apache.pekko.actor.typed.ActorRef[Command] = context.self

  // Mutable state root — updated in-place when the controller refreshes the pivot.
  private var stateRoot: ByteString = initialStateRoot

  // Per-peer concurrency budget — dynamically adjusted by SNAPSyncController via UpdateMaxInFlightPerPeer.
  private var maxInFlightPerPeer: Int = initialMaxInFlightPerPeer

  // Task management
  private[actors] val tasks = mutable.Queue[StorageTask]()
  // Dedup gate: (accountHash, next) uniquely identifies a pending task. Prevents duplicate
  // enqueues when concurrent timeout re-queues overlap (two timeouts for the same batch).
  private val pendingTaskKeys = mutable.Set[(ByteString, ByteString)]()
  private[actors] val activeTasks =
    mutable.Map[BigInt, (Peer, Seq[StorageTask], BigInt)]() // requestId -> (peer, tasks, requestedBytes)

  // Bookkeeping counters — replace the previously unbounded `completedTasks: ArrayBuffer[StorageTask]`
  // (which retained every completed StorageTask ref forever and contributed ~4 GB to the May 13 sepolia
  // OOM at 22M completed tasks). Only the aggregate counts are ever consumed downstream; we don't need
  // to hold the task structs themselves.
  private var completedTaskCount: Long = 0L
  // Unique accounts whose full storage has been written (small-contract flat path) OR whose async
  // trie construction has finished. Incremented exactly once per account at the "no more
  // continuations expected" transition, so this is bounded by the number of contracts in the snapshot
  // rather than the number of range requests issued — replaces the previously unbounded
  // `completedAccountHashes: Set[ByteString]` and its O(N²) progress-rebuild.
  private[actors] var completedAccountCount: Long = 0L

  // ========================================
  // Large-storage subtask parallelism (spec 005)
  // ========================================
  // When a contract's first SNAP response returns a continuation proof (more slots exist),
  // StorageRangeCoordinator splits the remaining slot range into N parallel subtasks.
  // Mirrors go-ethereum accountTask.SubTasks / cleanStorageTasks() (sync.go:299-330, 982-1015).
  //
  // accountSubtaskCounters: accountHash → (totalSubtasks, completedSubtasks).
  // completedAccountCount is only incremented once ALL subtasks for an account finish.
  private[actors] val accountSubtaskCounters: scala.collection.mutable.Map[ByteString, (Int, Int)] =
    scala.collection.mutable.Map.empty

  // Number of parallel subtasks to create per large-storage contract.
  // Matches go-ethereum storageConcurrency = 16 (sync.go:108).
  private val storageConcurrency: Int = 16

  // ========================================
  // Storage Queue Backpressure (#1232 follow-up — sepolia OOM)
  // ========================================
  //
  // AccountRangeCoordinator produces storage tasks as account ranges complete; if SNAP peers stop
  // serving (or simply can't keep up), this queue grew without bound — 2.8M tasks at the May 13
  // sepolia OOM. Watermarks below trigger an explicit pause/resume signal that's forwarded to
  // AccountRangeCoordinator via SNAPSyncController.
  //
  // Workers already in flight always complete; only the next-dispatch decision is gated.
  // (backpressureHighWatermark / backpressureLowWatermark live on the constructor for test override.)
  private[actors] var backpressureActive: Boolean = false

  // Global consecutive task failure counter: triggers ForceCompleteStorage when all SNAP peers
  // stop serving storage data. Resets to zero on any successful slot download.
  private[actors] var consecutiveTaskFailures: Int = 0
  private val maxConsecutiveTaskFailures: Int = 100

  // Idempotency guard: ForceCompleteStorage may arrive 10× simultaneously if SNAPSyncController
  // queues multiple stagnation-check responses before the first ForceCompleted reply comes back.
  // The handler does not clear `tasks`/`activeTasks`, so all duplicates would see identical state
  // and log the same WARN. Execute at most once per coordinator lifetime.
  private var forceCompleteExecuted: Boolean = false

  // Peer cooldown (best-effort): used for transient errors (timeouts, verification failures).
  // This is separate from stateless peer detection — cooldowns are short and per-error-type.
  // 5 s (was 10 s) — storage shares the peer pool with the account coordinator and we
  // observed `pending=N active=0 workers-known=10 eligible=8-11` snapshots where most of
  // the eligible peers were parked here. Halving the cooldown keeps storage dispatch
  // closer to the request budget the SNAP server can actually sustain.
  private val peerCooldownUntilMs = mutable.Map[String, Long]()
  private val peerCooldownDefault = 5.seconds

  // Liveness score per peer (see SnapPeerHealth). The 5 s cooldown above handles a transient hiccup; this handles a peer
  // that never answers. A peer with `timeoutThreshold` consecutive timeouts and no success is penalised (2 min, doubling
  // to 30 min; capped at 5 min while a majority of the pool is penalised) and is skipped by normal dispatch AND by the
  // cooldown floor; once the penalty lapses it gets one request slot until it answers. A pivot refresh lifts penalties
  // but keeps levels; a disconnect keeps everything (the key is the node ID, so a reconnect does not reset it).
  private[actors] val peerHealth = new com.chipprbots.ethereum.blockchain.sync.snap.SnapPeerHealth()
  private var lastPenalisedFloorLogMs: Long = 0L

  // Stateless peer tracking: peers CONFIRMED unable to serve the current state root after
  // crossing the strike threshold below. When ALL known peers are stateless, request a
  // pivot refresh from the controller.
  // The previous single-failure binary mark caused the same peer-pool collapse pathology
  // we observed in AccountRangeCoordinator on sepolia 2026-05-13. Strike counting gives
  // a transient peer hiccup the same recovery path used elsewhere in the codebase.
  private val statelessPeers = mutable.Set[String]()
  // Strike counter for empty storage-range responses. Mirror of AccountRangeCoordinator's
  // emptyResponseStrikes: a peer must miss N=5 consecutive empties (no intervening success)
  // before being marked stateless. Cleared on PivotRefreshed, PeerUnavailable, or any
  // successful (slot-bearing) response.
  //
  // Threshold raised 3 → 5 on 2026-05-14 after sepolia observation: on small peer pools
  // (3-8 peers), 3 strikes drains peers into stateless faster than pivot refreshes can
  // clear them. Result: eligible=0 windows recur every pivot cycle. Mirrors the small-pool
  // fix shape applied to account (PR-2 / #1255 cleared sticky snapless on PivotRefreshed).
  // Reference clients use 5 (Nethermind) or unlimited+throughput-tracker (Geth); 3 was
  // overly aggressive for our policy.
  private val emptyResponseStrikes = mutable.Map.empty[String, Int]
  private val EmptyResponseStrikeThreshold: Int = 5
  private var pivotRefreshRequested = false

  // Contract completion tracking for progress estimation.
  // totalStorageContracts counts unique contracts added via AddStorageTasks.
  // Unique completed accounts are tracked via the bounded `completedAccountCount` counter below
  // (incremented once per account at the final-response "no continuation" branch of
  // `processStorageRanges`).
  private var totalStorageContracts: Int = 0

  // Pivot refresh backoff: prevents rapid refresh loops when no peers can serve any recent root.
  // After each unproductive refresh (one that doesn't yield real slot data), the backoff interval
  // doubles from 60s up to 5 minutes. Resets to 0 when we receive actual storage slots.
  private var consecutiveUnproductiveRefreshes: Int = 0
  private var lastPivotRefreshTimeMs: Long = 0
  private val minRefreshIntervalMs: Long = 60000L // 1 minute minimum between refreshes
  private val maxRefreshIntervalMs: Long = 300000L // 5 minutes maximum backoff

  // No-activity timeout: detects stalls caused by "ghost" peers in knownAvailablePeers
  // that disconnected without being removed and thus never get marked stateless.
  // When tasks are pending, nothing is in-flight, and no dispatch/response has occurred
  // for this duration, we treat it as all-stateless and request a pivot refresh.
  private var lastDispatchOrResponseMs: Long = System.currentTimeMillis()
  private val noActivityTimeoutMs: Long = 120000L // 2 minutes

  // Post-pivot-refresh cooldown: after a pivot refresh, peers need time to sync to the new root.
  // Dispatching immediately causes all peers to return empty → marked stateless → another pivot
  // refresh → infinite tight loop. This cooldown prevents ALL dispatch paths (tryRedispatchPendingTasks,
  // StoragePeerAvailable, StorageCheckCompletion) from sending requests until peers have had time.
  private var postRefreshCooldownUntilMs: Long = 0
  // 5 s (was 10 s) post-pivot cooldown. With ~22 pivot refreshes per long sync, every
  // second here is `22 ×` lost throughput. The fresh pivot is typically only ~30 blocks
  // newer than the previous one; peers that served the old root almost always serve the
  // new one within a couple of seconds. 5 s is enough to avoid the tight "empty →
  // mark-stateless → refresh" loop without burning aggregate sync time.
  private val postRefreshCooldownMs: Long = 5000L

  // Consecutive idle dispatch checks: counts tryRedispatchPendingTasks() calls where tasks are
  // pending but zero eligible peers and zero active requests exist. At threshold, requests a
  // pivot refresh as an escape valve — same pattern as TrieNodeHealingCoordinator BUG-M3 fix.
  private var storageIdleChecks: Int = 0
  private val storageIdleEscapeThreshold: Int = 5

  private def isPostRefreshCooldownActive: Boolean =
    System.currentTimeMillis() < postRefreshCooldownUntilMs

  private def isPeerStateless(peer: Peer): Boolean =
    statelessPeers.contains(peer.id.value)

  private def markPeerStateless(peer: Peer): Unit =
    val id = peer.id.value
    // Already-confirmed peers stay confirmed; extra strikes are noise.
    if !statelessPeers.contains(id) then
      val priorStrikes = emptyResponseStrikes.getOrElse(id, 0)
      val strikes = priorStrikes + 1
      emptyResponseStrikes(id) = strikes

      if strikes < EmptyResponseStrikeThreshold then
        log.info(
          s"Peer $id empty-storage strike $strikes/$EmptyResponseStrikeThreshold for root " +
            s"${stateRoot.take(4).toHex}. Still eligible for dispatch."
        )
      else
        val wasStateless = statelessPeers.contains(id)
        statelessPeers.add(id)
        if !wasStateless then
          com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncMetrics.incrementStatelessPeerConfirmed()
        log.info(
          s"Peer $id marked stateless after $strikes consecutive empty storage responses for root " +
            s"${stateRoot.take(4).toHex} (${statelessPeers.size}/${knownAvailablePeers.size} stateless)"
        )
        maybeRequestPivotRefresh()

  /** Reset strike counter when peer produces a useful response. Cheap to over-invoke. */
  private def recordPeerSuccess(peerId: String): Unit =
    emptyResponseStrikes.remove(peerId)
    recordPeerAnswered(peerId)

  /** The peer answered a request with usable data: feed the liveness score and log a recovery from demotion. */
  private def recordPeerAnswered(peerId: String): Unit =
    peerHealth.recordSuccess(peerId).foreach { level =>
      log.info(
        s"[STORAGE-PEER-HEALTH] recovered peer=${peerId.take(8)} level=$level->${peerHealth.level(peerId)} " +
          s"penalised=${peerHealth.penalisedCount(System.currentTimeMillis())}"
      )
    }

  // Low-eligible recovery: trigger pivot refresh when almost all peers are stateless but
  // one peer remains, preventing allStateless from ever becoming true. With the parallel
  // subtask dispatch added in spec 005 (storageConcurrency=16), ETH-genesis peers (connecting
  // due to shared networkId=1) can accumulate 5 strikes each within one pivot cycle. If 11 of
  // 12 peers become stateless the one remaining peer serves all tasks at 1/12th throughput
  // with no automatic recovery until the next natural pivot roll (≈4 min observed in RUN02).
  // Threshold: trigger when ≤1 peer eligible AND pool has ≥4 total peers.
  // Backoff: uses the existing minRefreshIntervalMs/maxRefreshIntervalMs schedule, so it
  // cannot churn faster than allStateless recovery.
  private val lowEligibleMaxCount: Int = 1
  private val lowEligibleMinPoolSize: Int = 4

  private def maybeRequestPivotRefresh(): Unit = if !pivotRefreshRequested then
    val allStateless = knownAvailablePeers.nonEmpty &&
      knownAvailablePeers.forall(p => statelessPeers.contains(p.id.value))

    val eligibleCount = (knownAvailablePeers.size - statelessPeers.size).max(0)
    val lowEligible = !allStateless &&
      knownAvailablePeers.size >= lowEligibleMinPoolSize &&
      eligibleCount <= lowEligibleMaxCount &&
      tasks.nonEmpty

    // Secondary trigger: tasks pending but no dispatch/response activity for 2 minutes.
    // Catches "ghost" peers that remain in knownAvailablePeers after disconnecting
    // without being marked stateless (preventing allStateless from ever being true).
    // Note: activeTasks may be non-empty if requests to ghost peers never time out
    // (SNAPRequestTracker timeouts are poll-based, not scheduled), so we check
    // activity time regardless of in-flight count.
    val now = System.currentTimeMillis()
    val dispatchStalled = !allStateless && !lowEligible && tasks.nonEmpty && maxInFlightPerPeer > 0 &&
      (now - lastDispatchOrResponseMs) > noActivityTimeoutMs

    if dispatchStalled then
      log.warn(
        s"Storage dispatch stalled: ${tasks.size} pending, ${activeTasks.size} active, " +
          s"no activity for ${(now - lastDispatchOrResponseMs) / 1000}s. " +
          s"Peers: ${knownAvailablePeers.size} known, ${statelessPeers.size} stateless. " +
          s"Marking remaining peers as stateless (likely disconnected)."
      )
      knownAvailablePeers.foreach(p => statelessPeers.add(p.id.value))
      // Re-queue any stale in-flight tasks from ghost peers
      if activeTasks.nonEmpty then
        val staleCount = activeTasks.size
        activeTasks.values.foreach { case (_, batchTasks, _) =>
          batchTasks.foreach { task =>
            tasks.enqueue(task.copy(pending = false))
          }
        }
        activeTasks.clear()
        log.info(s"Re-queued $staleCount stale in-flight requests from ghost peers")

    if allStateless || lowEligible || dispatchStalled then
      val backoffMs = math.min(
        maxRefreshIntervalMs,
        minRefreshIntervalMs * (1L << math.min(consecutiveUnproductiveRefreshes, 3))
      )
      val elapsed = now - lastPivotRefreshTimeMs
      if lastPivotRefreshTimeMs > 0 && elapsed < backoffMs then
        val remainingMs = backoffMs - elapsed
        log.info(
          s"All peers stateless but backing off pivot refresh " +
            s"(${elapsed / 1000}s / ${backoffMs / 1000}s, attempt ${consecutiveUnproductiveRefreshes + 1}). " +
            s"Retrying in ${remainingMs / 1000}s."
        )
        // Schedule a retry after the backoff period (one-shot self-send; triggers re-evaluation)
        context.scheduleOnce(remainingMs.millis, self, StorageCheckCompletion)
      else
        pivotRefreshRequested = true
        consecutiveUnproductiveRefreshes += 1
        lastPivotRefreshTimeMs = now
        if lowEligible then
          log.warn(
            s"Low-eligible storage peers: ${eligibleCount}/${knownAvailablePeers.size} eligible, " +
              s"${statelessPeers.size} stateless for root ${stateRoot.take(4).toHex}. " +
              s"Requesting pivot refresh to restore peer pool (attempt $consecutiveUnproductiveRefreshes)."
          )
          snapSyncController ! SNAPSyncController.PivotStateUnservable(
            rootHash = stateRoot,
            reason = "low-eligible peers for StorageRange root",
            consecutiveEmptyResponses = statelessPeers.size
          )
        else
          log.warn(
            s"All ${statelessPeers.size} known peers are stateless for root ${stateRoot.take(4).toHex}. " +
              s"Requesting pivot refresh from controller (attempt $consecutiveUnproductiveRefreshes)."
          )
          snapSyncController ! SNAPSyncController.PivotStateUnservable(
            rootHash = stateRoot,
            reason = "all peers stateless for StorageRange root",
            consecutiveEmptyResponses = statelessPeers.size
          )

  // Per-peer adaptive batch size: tracks which peers support multi-account batching.
  // Starts at maxAccountsPerBatch, ratchets down on empty batched responses, scales back up
  // on successful packed responses. This allows recovery from transient issues rather than
  // permanently degrading to batch=1 for the lifetime of the sync.
  private val peerBatchSize = mutable.Map.empty[String, Int]
  private val peerBatchSuccessStreak = mutable.Map.empty[String, Int]
  private val batchRecoveryStreak = 3 // Consecutive successes before scaling up

  private def batchSizeFor(peer: Peer): Int =
    peerBatchSize.getOrElseUpdate(peer.id.value, maxAccountsPerBatch)

  private def reduceBatchSize(peer: Peer): Unit =
    peerBatchSize.update(peer.id.value, 1)
    peerBatchSuccessStreak.remove(peer.id.value)

  /** Scale batch size back up after consecutive successful packed responses. Doubles the batch size per peer, capped at
    * maxAccountsPerBatch.
    */
  private def maybeIncreaseBatchSize(peer: Peer, servedCount: Int, requestedCount: Int): Unit =
    // Only count as "packed" if the response served most of the requested accounts
    if requestedCount > 1 && servedCount >= requestedCount / 2 then
      val streak = peerBatchSuccessStreak.getOrElse(peer.id.value, 0) + 1
      peerBatchSuccessStreak.update(peer.id.value, streak)
      if streak >= batchRecoveryStreak then
        val current = batchSizeFor(peer)
        val next = math.min(current * 2, maxAccountsPerBatch)
        if next > current then
          peerBatchSize.update(peer.id.value, next)
          peerBatchSuccessStreak.update(peer.id.value, 0)
          log.info(
            s"Peer ${peer.id.value} batch size increased: $current -> $next (after $streak consecutive successes)"
          )

  // Track last known available peers so we can re-dispatch after task failures
  // without waiting for the next StoragePeerAvailable message.
  private val knownAvailablePeers = mutable.Set[Peer]()

  /** Count in-flight requests for a given peer (pipelining support). */
  private def inFlightForPeer(peer: Peer): Int =
    activeTasks.values.count(_._1.id == peer.id)

  // Per-task empty-response tracking.
  // Some peers legitimately return empty slotSets+proofs in cases we can't easily distinguish
  // from "can't serve this state". If we keep re-queuing forever, sync can livelock.
  // Track empty responses per (accountHash,next,last) and skip after a small threshold.
  private case class StorageTaskKey(accountHash: ByteString, next: ByteString, last: ByteString)
  private val emptyResponsesByTask = mutable.HashMap.empty[StorageTaskKey, Int]
  private val maxEmptyResponsesPerTask: Int = 5

  // Platåberget soak v6 (2026-09-28): an account whose account-range record was fetched at an
  // OLDER pivot carries a stale `storageRoot` — no peer, however honest, can ever produce a
  // complete-range proof that verifies against it, because the account's REAL storage root (at the
  // CURRENT state root we're syncing) has moved. Before this guard, `processServedTasks`'s
  // verification-failure branch re-queued such a task unconditionally, forever: soak evidence
  // showed ~1245 identical "Processing storage ranges for 18 accounts" responses over 25+ minutes,
  // the same handful of accounts (by account-hash prefix) re-failing every cycle with
  // "complete-range hash mismatch" / "root node missing from proof", while `pending` oscillated
  // between 1 and 33 without ever draining — a livelock in the storage phase's tail, masked from
  // the (slot-count-based) stagnation watchdog because OTHER, unrelated accounts kept completing
  // and resetting its clock (see SNAPSyncController's ProgressStorageSlotsSynced handler).
  //
  // Tracks, per account, EACH occurrence of one of these two specific "stale local expectation"
  // diagnostic signatures, together with which peer served it and which state root was in effect.
  // After `maxStaleRootFailuresPerAccount` (3) such failures, if they collectively came from at
  // least 2 distinct peers OR spanned at least 2 distinct roots (evidence the failure tracks the
  // ACCOUNT, not one peer or one transient root), the account is dropped to healing — see
  // `giveUpOnAccountForHealing`. K=3 mirrors the order of magnitude of the existing
  // maxEmptyResponsesPerTask precedent (5) but is slightly stricter: this diagnostic signature is
  // MORE likely to be a persistent, unrecoverable-by-retry condition (a stale local pointer) than a
  // transient "peer had nothing to say this time", so bounding the retry storm sooner is
  // appropriate — each attempt is cheap to allow (never rules out a genuine transient blip on the
  // first or second try), but unbounded retries of an unrecoverable condition are not: the soak's
  // handful of stuck accounts alone produced over a thousand pointless round trips.
  //
  // A response matching this signature is explicitly NOT evidence of PEER fault — the peer may be
  // serving entirely correct, current data; our own expectation is what's wrong — so (unlike the
  // generic verification-failure branch) neither recordPeerCooldown nor adjustResponseBytesOnFailure
  // is called for it.
  private[actors] case class StaleRootFailure(peerId: String, root: ByteString)
  private[actors] val staleRootFailuresByAccount: mutable.Map[ByteString, Vector[StaleRootFailure]] = mutable.Map.empty
  private val maxStaleRootFailuresPerAccount: Int = 3
  private def isStaleLocalRootSignature(error: String): Boolean =
    error == "complete-range hash mismatch" || error == "root node missing from proof"

  /** Give up on an account's storage after repeated stale-local-root verification failures (see
    * `staleRootFailuresByAccount`'s doc). Reuses EXACTLY the hand-off `handleEmptyResponse` already uses to defer an
    * unrecoverable-by-retry task to healing: mark the task done, discard any partial trie/ordering-gate state
    * (already-flushed content-addressed nodes stay on disk; healing reconciles), and do NOT re-queue. Deliberately
    * mirrors that method rather than inventing a second hand-off convention.
    */
  private def giveUpOnAccountForHealing(
      task: StorageTask,
      failureCount: Int,
      distinctPeers: Int,
      distinctRoots: Int
  ): Unit =
    val accountHash = task.accountHash
    staleRootFailuresByAccount.remove(accountHash)
    // Give up the WHOLE account, not just the task that tripped the cap: a large account may be split into
    // parallel subtasks sharing one stale `storageRoot`; if only this subtask were dropped, each sibling would need K
    // more failures of its own (the failure history is keyed by account and was just cleared). Drop every queued
    // sibling and tombstone the account so in-flight siblings (already dispatched, answering later) and
    // timeout/peer-loss re-queues are ignored instead of rebuilding a partial trie that could never commit correctly.
    val queuedSiblings = tasks.filter(_.accountHash == accountHash)
    if queuedSiblings.nonEmpty then
      val keep = tasks.filterNot(_.accountHash == accountHash)
      tasks.clear()
      tasks.enqueueAll(keep)
      queuedSiblings.foreach(t => pendingTaskKeys -= ((t.accountHash, t.next)))
    abandonedAccounts += accountHash
    accountSubtaskCounters.remove(accountHash)
    val doneTask = task.copy(done = true, pending = false)
    recordCompletedTask(doneTask)
    resetAccountTrie(accountHash)
    com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncMetrics
      .setStoragePendingTries(pendingAccountTries.size.toLong)
    log.warn(
      s"Giving up on storage for account ${accountHash.toHex} after $failureCount consecutive " +
        s"stale-local-root verification failures across $distinctPeers peer(s)/$distinctRoots root(s) " +
        s"(storageRoot=${task.storageRoot.toHex}; dropped ${queuedSiblings.size} queued sibling task(s)) — deferring to healing"
    )

  /** Accounts given up on for healing (see `giveUpOnAccountForHealing`). Tasks for these are ignored wherever they
    * resurface (late responses, timeout/peer-loss re-queues).
    */
  private[actors] val abandonedAccounts: mutable.Set[ByteString] = mutable.Set.empty

  /** Cumulative count of stale-local-root verification failures observed (never reset on give-up). Reported to the
    * controller so its tail-livelock backstop can require repeated verification failures as evidence, rather than
    * inferring a stall from queue depth alone.
    */
  private[actors] var staleRootFailureEvents: Long = 0L

  // Sentinel: when true, no more AddStorageTasks will arrive (all accounts downloaded).
  // Completion is only reported after this is set AND pending+active tasks drain.
  // Geth-aligned: coordinators run from start, tasks arrive inline during account download.
  private var noMoreTasksExpected: Boolean = false
  private var storageMilestonePct: Int = -1

  // Statistics
  private var slotsDownloaded: Long = 0
  private var bytesDownloaded: Long = 0
  private val startTime = System.currentTimeMillis()

  // Per-peer adaptive byte budgeting (ported from ByteCodeCoordinator).
  // Geth's snap handler supports up to 2MB responses. Starting at 512KB and probing upward
  // on responsive peers, scaling down on failures.
  private val minResponseBytes: BigInt = configMinResponseBytes // Configurable floor (avoid excessive small requests)
  private val maxResponseBytes: BigInt = 2 * 1024 * 1024 // 2MB ceiling (Geth handler limit)
  private val initialResponseBytes: BigInt = configInitialResponseBytes // Configurable starting point
  private val increaseFactor: Double = 1.25 // Scale up when 90%+ fill
  private val decreaseFactor: Double = 0.5 // Scale down on failure/empty

  private val peerResponseBytesTarget = mutable.Map.empty[String, BigInt]

  private def responseBytesTargetFor(peer: Peer): BigInt =
    peerResponseBytesTarget
      .getOrElseUpdate(peer.id.value, initialResponseBytes)
      .max(minResponseBytes)
      .min(maxResponseBytes)

  private def adjustResponseBytesOnSuccess(peer: Peer, requested: BigInt, received: BigInt): Unit =
    if requested > 0 && received * 10 >= requested * 9 && requested < maxResponseBytes then
      val next = (requested.toDouble * increaseFactor).toLong
      peerResponseBytesTarget.update(peer.id.value, BigInt(next).min(maxResponseBytes))

  private def adjustResponseBytesOnFailure(peer: Peer, reason: String): Unit =
    val cur = responseBytesTargetFor(peer)
    val next = (cur.toDouble * decreaseFactor).toLong
    peerResponseBytesTarget.update(peer.id.value, BigInt(next).max(minResponseBytes))
    log.debug(
      s"Reducing storage responseBytes target for peer ${peer.id.value}: $cur -> ${peerResponseBytesTarget(peer.id.value)} ($reason)"
    )

  // ========================================
  // Streaming storage-trie construction (replaces the legacy two-phase Phase 1 slot buffer)
  // ========================================
  //
  // Per-account `SnapHashTrie` instances stream verified slots straight into a bounded
  // stack-trie. The wrapper auto-flushes emitted nodes to RocksDB at the 8 MiB threshold
  // (`SnapHashTrie.DefaultBatchSizeBytes`), so a contract with millions of slots never holds
  // more than ~8 MiB of buffered nodes in heap. When the account's full storage range has
  // been served, `commit()` finalises the right boundary, flushes the remaining batch, and
  // returns the trie root. Aborted accounts (pivot refresh, max-empty skip, force-complete)
  // call `reset()`; partially-flushed content-addressed nodes are left on disk and unreferenced
  // until pruning collects them — they don't have to be rolled back.
  //
  // Memory ceiling: `maxConcurrentStorageAccounts × 8 MiB` (default 256 × 8 MiB = 2 GiB),
  // **regardless of chain size**. Replaces the pre-PR pattern where per-account
  // `ArrayBuffer[(slotHash, slotValue)]` grew proportional to contract size and total
  // concurrent-contract count, OOM'ing ETC mainnet at ~12M accounts on -Xmx3g.

  /** Per-account streaming storage trie. Populated incrementally as verified slots arrive, committed on the final
    * response (no continuation), reset on abort. Bounded by `maxConcurrentStorageAccounts` via the dispatch gate in
    * `requestNextRanges`.
    */
  private[actors] val pendingAccountTries: mutable.Map[ByteString, SnapTrie] = mutable.Map.empty

  // ========================================
  // Range-ascending ordering gate for parallel storage subtasks
  // ========================================
  //
  // A large-storage account is split into `storageConcurrency` (16) parallel StorageTask
  // subtasks, each covering a disjoint, range-ascending slice of the account's key space
  // (see StorageTask.createSubTasks, called from the `needsContinuation` branch below). Each
  // subtask is dispatched independently to whichever peer becomes available — requestNextRanges'
  // `acceptsNewAccount` explicitly allows unlimited concurrent dispatch for an account already in
  // `pendingAccountTries`. Nothing about dispatch order or peer response latency guarantees
  // sibling responses are PROCESSED back in range order: a peer serving a higher sub-range can
  // answer before a peer serving a lower one.
  //
  // But every account streams into ONE shared StackTrie (`pendingAccountTries`, keyed only by
  // accountHash) whose `update` requires strictly-ascending inserts across the FULL key space, not
  // just within one response (StackTrie.scala:66). Applying an out-of-order chunk directly trips
  // that `require` and aborts the actor — see StorageRangeCoordinatorImpl crash history, "StackTrie
  // keys must be strictly ascending".
  //
  // `storageTrieCursor` tracks, per account, the exact `next` value the trie is currently willing
  // to accept — advanced ONLY by `applyReadyStorageChunk`, and always set EXPLICITLY at the moment
  // a chunk/continuation is created (never inferred from "whichever chunk happens to arrive
  // first", which would be just as racy as the bug this replaces). A chunk whose `task.next`
  // doesn't match is buffered in `pendingOrderedChunks` (ordered by range start) rather than
  // applied, and is drained once every earlier chunk has landed.
  //
  // Memory bound: buffering holds already-verified slot data, never partially-applied trie state.
  // At most `storageConcurrency` (16) chunk responses can be buffered per account, each capped by
  // the peer's response-bytes target (<=2MiB) — a few tens of MiB worst case for a single
  // in-progress large account, bounded further by `maxConcurrentStorageAccounts`.
  private[actors] case class ReadyStorageChunk(
      peer: Peer,
      task: StorageTask,
      accountSlots: Seq[(ByteString, ByteString)],
      proof: Seq[ByteString]
  )
  private[actors] val storageTrieCursor: mutable.Map[ByteString, ByteString] = mutable.Map.empty
  private[actors] val pendingOrderedChunks: mutable.Map[ByteString, mutable.TreeMap[ByteString, ReadyStorageChunk]] =
    mutable.Map.empty
  private[actors] val zeroSlotHash: ByteString = ByteString(Array.fill(32)(0.toByte))

  // Tracks, per account, the (key, value) of the LAST slot actually inserted into that account's
  // trie — a different thing from `storageTrieCursor` (which tracks the next CHUNK-RANGE boundary,
  // not individual slot keys). SNAP/1's `startingHash` origin is documented as inclusive, and
  // go-ethereum's own genTrie/stacktrie boundary handling anticipates exactly this: a continuation
  // or sub-range response whose FIRST key duplicates the last key this account's trie already has.
  // Used by applyReadyStorageChunk to drop an exact (key, value) repeat silently (idempotent
  // re-serve) rather than let it reach StackTrie.update's ascending-order `require`, while a
  // DIFFERENT value under the same already-applied key rejects the whole response instead of
  // silently accepting inconsistent peer data.
  private[actors] val lastAppliedSlot: mutable.Map[ByteString, (ByteString, ByteString)] = mutable.Map.empty

  /** Discard the ordering-gate state for one account: its cursor, last-applied-slot marker, and any buffered (verified
    * but not-yet-applied) chunks. Called everywhere `pendingAccountTries` is reset/removed for that account, so the
    * three stay consistent. Returns the discarded buffer (possibly empty) so callers can decide whether to re-queue or
    * abandon its contents.
    */
  private[actors] def clearStorageOrderingState(accountHash: ByteString): Seq[StorageTask] =
    storageTrieCursor.remove(accountHash)
    lastAppliedSlot.remove(accountHash)
    pendingOrderedChunks.remove(accountHash) match
      case Some(buffered) => buffered.values.map(_.task).toSeq
      case None           => Seq.empty

  /** Get-or-create the per-account [[SnapTrie]]. Each contract's trie streams emitted nodes to storage.
    *
    * HashScheme (default): nodes flush to `mptStorage` via `storeRawNodes`. PathScheme: nodes written path-keyed to
    * `PathNodeStorage`, scoped by `accountHash` (so multiple storage tries don't collide on path keys).
    */
  private def getOrCreateAccountTrie(accountHash: ByteString): SnapTrie =
    pendingAccountTries.getOrElseUpdate(
      accountHash,
      storageScheme match
        case StorageScheme.Hash =>
          new SnapHashTrie(batch => mptStorage.storeRawNodes(batch))
        case StorageScheme.Path =>
          val pns = pathNodeStorage.getOrElse(
            throw new IllegalStateException("PathScheme requires pathNodeStorage to be set")
          )
          new SnapPathTrie(
            owner = accountHash,
            skipLeftBoundary = false, // storage tasks are always fresh (no per-slot resume cursor)
            writePath = (path, _, blob) => pns.writeStorageNode(accountHash, path, blob),
            deleteExact = path => pns.deleteStorageNode(accountHash, path)
          )
    )

  /** Commit a fully-downloaded contract trie. Compares the computed root against the task's claimed `storageRoot`;
    * mismatches log a warning but are otherwise accepted — healing reconciles. Returns the contract's storage root.
    */
  private def commitAccountTrie(accountHash: ByteString, claimedRoot: ByteString): ByteString =
    pendingAccountTries.remove(accountHash) match
      case Some(trie) =>
        val computedRoot = trie.commit()
        if computedRoot != claimedRoot then
          log.warn(
            s"Storage root mismatch for account ${accountHash.take(4).toHex}: " +
              s"computed=${computedRoot.take(4).toHex} claimed=${claimedRoot.take(4).toHex} — healing will reconcile"
          )
        com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncMetrics
          .setStoragePendingTries(pendingAccountTries.size.toLong)
        computedRoot
      case None =>
        // First/only response for an account with no slots, or proof-of-absence path —
        // no trie was ever created. Caller still wants a non-null root reference.
        claimedRoot

  /** Discard a partial trie when the account is aborted (pivot refresh, max-empty skip, force-complete).
    * Already-flushed content-addressed nodes stay on disk; only the in-memory stack-trie state is dropped.
    */
  private def resetAccountTrie(accountHash: ByteString): Unit =
    pendingAccountTries.remove(accountHash).foreach { trie =>
      trie.reset()
    }
    // Abandon this account's ordering-gate state too — any buffered (not-yet-applied) sibling
    // chunks are dropped along with the trie, not re-queued: this path is reached only after
    // maxEmptyResponsesPerTask consecutive empty replies for ONE chunk, i.e. a deliberate
    // "give up on this account, healing reconciles" decision, matching the trie-discard above.
    val _ = clearStorageOrderingState(accountHash)

  // ========================================
  // Aggregated flat-slot writes (small-contract path)
  // ========================================
  //
  // ~95% of ETC contracts hit the small-contract path. Doing a synchronous
  // RocksDB commit per contract on the actor mailbox produces a commit storm
  // and starves the coordinator's other work. Instead, accumulate completed
  // small contracts here and flush them off-thread in batches.
  //
  // Stale flushes after a pivot refresh are tagged with the state root they
  // were aggregated under and dropped at the completion-message handler — the
  // data is still written (writes are idempotent and any account that
  // legitimately needs different slot values at a later root will be re-fetched
  // and overwrite), the bookkeeping just doesn't double-count.

  /** Pending (accountHash, sortedSlots) pairs awaiting flush. Package-private for unit tests. */
  private[actors] val pendingFlatBatchAccounts =
    mutable.ArrayBuffer.empty[(ByteString, Seq[(ByteString, ByteString)])]

  /** Total slot entries currently buffered in `pendingFlatBatchAccounts`. Package-private for unit tests. */
  private[actors] var pendingFlatBatchEntries: Int = 0

  /** Number of in-flight async flat-batch flushes — used to gate completion. Package-private for unit tests. */
  private[actors] var inFlightFlatBatches: Int = 0

  // ── Storage-task completion markers (a resume skips finished tasks; see SnapStorageDoneStorage) ──
  //
  // Crash-consistency rule: a marker may only become durable after every byte of the account's data.
  //   - Trie nodes: written synchronously on this thread before the marker is staged (`commitAccountTrie`).
  //   - Flat slots: every flat batch gets a sequence number. When the marker is staged, the account's slots are in
  //     batches <= `flatBatchSeq` (already submitted) or still in `pendingFlatBatchAccounts`, which the NEXT batch
  //     (`flatBatchSeq + 1`) carries. The marker records that number as `lastSlotBatch` and may ride in batch `b` only
  //     if every batch <= lastSlotBatch has committed, or `b` IS lastSlotBatch (same atomic WriteBatch) and every
  //     earlier one has. This holds however many threads the writer dispatcher has, and a marker never waits on
  //     batches submitted after it (no starvation under sustained load).
  // A batch that fails disables markers for the rest of this coordinator's life: its slots are lost and any staged
  // account could have had a chunk in it. Losing a staged marker (crash before its batch commits) only means the task
  // is downloaded again after a restart — today's behaviour.
  private val storageDoneStorage: Option[SnapStorageDoneStorage] =
    Option.when(recordStorageDone)(new SnapStorageDoneStorage(flatSlotStorage.dataSource))

  /** Fully-downloaded `(accountHash, storageRoot, lastSlotBatch)` whose marker is not written yet. Package-private for
    * tests.
    */
  private[actors] val pendingDoneMarkers = mutable.ArrayBuffer.empty[(ByteString, ByteString, Long)]

  /** Set once a flat batch failed: this coordinator writes no further markers. Package-private for tests. */
  private[actors] var doneMarkersDisabled: Boolean = false

  /** Sequence number of the last submitted flat batch (1-based). */
  private var flatBatchSeq: Long = 0L

  /** Every flat batch with a sequence number <= this has committed. Package-private for tests. */
  private[actors] var flatBatchesDoneThrough: Long = 0L
  private val flatBatchesDoneAhead = mutable.Set.empty[Long]

  private def recordFlatBatchDone(seq: Long): Unit =
    if seq > flatBatchesDoneThrough then
      flatBatchesDoneAhead += seq
      while flatBatchesDoneAhead.remove(flatBatchesDoneThrough + 1) do flatBatchesDoneThrough += 1

  /** Stage the completion marker of an account whose data was just fully handed to storage. */
  private def stageDoneMarker(accountHash: ByteString, storageRoot: ByteString): Unit =
    if storageDoneStorage.isDefined && !doneMarkersDisabled then
      pendingDoneMarkers += ((accountHash, storageRoot, flatBatchSeq + 1))

  /** Remove and return the staged markers that may ride in batch `batchSeq` (see the rule above). */
  private def takeDoneMarkersFor(batchSeq: Long): Seq[(ByteString, ByteString)] =
    if storageDoneStorage.isEmpty || doneMarkersDisabled || pendingDoneMarkers.isEmpty then Seq.empty
    else
      val (ride, wait) = pendingDoneMarkers.partition { case (_, _, lastSlotBatch) =>
        flatBatchesDoneThrough >= lastSlotBatch ||
        (batchSeq == lastSlotBatch && flatBatchesDoneThrough >= lastSlotBatch - 1)
      }
      pendingDoneMarkers.clear()
      pendingDoneMarkers ++= wait
      ride.map { case (account, root, _) => (account, root) }.toList

  /** Dedicated dispatcher for flat-batch RocksDB commits. Tests can inject their own ExecutionContext to keep timing
    * deterministic; production looks up `storage-writer-dispatcher` from the actor system.
    */
  private val flatBatchEc: ExecutionContext =
    flatBatchEcOverride.getOrElse(context.system.classicSystem.dispatchers.lookup("storage-writer-dispatcher"))

  /** Append a per-response chunk of verified slots to the flat-slot accumulator. The streaming storage-trie path
    * commits the trie incrementally inside `SnapHashTrie`; flat-slot writes remain batched (sorted within each chunk)
    * and are flushed to RocksDB off-actor once the accumulator hits `flatBatchEntryThreshold` or the actor stops.
    */
  private[actors] def stageFlatSlotChunk(
      accountHash: ByteString,
      slots: Seq[(ByteString, ByteString)]
  ): Unit =
    if slots.nonEmpty then
      val sorted = slots.sortBy(_._1)(ByteStringOrdering)
      pendingFlatBatchAccounts += ((accountHash, sorted))
      pendingFlatBatchEntries += sorted.size
      if pendingFlatBatchEntries >= flatBatchEntryThreshold then flushPendingFlatBatch()

  /** Hand the current accumulator off to the storage-writer dispatcher and reset it. The Future builds the combined
    * `DataSourceBatchUpdate` and commits it in one RocksDB write batch, then notifies the actor with
    * `FlatBatchFlushComplete` (or `FlatBatchFlushFailed`). The completion message carries `forStateRoot` so the actor
    * can drop bookkeeping for batches that pre-date a pivot refresh.
    */
  private def flushPendingFlatBatch(): Unit =
    val batchSeq = flatBatchSeq + 1
    val doneMarkers = takeDoneMarkersFor(batchSeq)
    if pendingFlatBatchAccounts.nonEmpty || doneMarkers.nonEmpty then
      val batchAccounts = pendingFlatBatchAccounts.toList // immutable snapshot
      val entries = pendingFlatBatchEntries
      val forStateRoot = stateRoot
      pendingFlatBatchAccounts.clear()
      pendingFlatBatchEntries = 0
      inFlightFlatBatches += 1
      flatBatchSeq = batchSeq

      val selfRef = self
      val storage = flatSlotStorage // capture for Future
      val doneStorage = storageDoneStorage
      val ec = flatBatchEc
      import scala.concurrent.{Future, blocking}
      Future {
        blocking {
          val startMs = System.currentTimeMillis()
          var combined: DataSourceBatchUpdate = storage.emptyBatchUpdate
          batchAccounts.foreach { case (accountHash, slots) =>
            combined = combined.and(storage.putSlotsBatch(accountHash, slots))
          }
          // Same WriteBatch as the slots: the markers commit atomically with them, never before.
          if doneMarkers.nonEmpty then doneStorage.foreach(d => combined = combined.and(d.markDone(doneMarkers)))
          combined.commit()
          System.currentTimeMillis() - startMs
        }
      }(ec).onComplete {
        case scala.util.Success(elapsedMs) =>
          selfRef ! FlatBatchFlushComplete(forStateRoot, entries, elapsedMs, batchSeq)
        case scala.util.Failure(e) =>
          selfRef ! FlatBatchFlushFailed(forStateRoot, entries, e.getMessage)
      }(ec)

  /** Aggregate-counter sink for completed StorageTask objects. Previously this appended into an unbounded
    * `mutable.ArrayBuffer[StorageTask]` (one of the leak vectors behind the May 13 sepolia OOM at ~22M completed
    * tasks). All downstream consumers — progress %, SyncStatistics.tasksCompleted, the completion check — only ever
    * read the count, never the task data itself.
    */
  private def recordCompletedTask(task: StorageTask): Unit =
    val _ = task // explicitly unused; kept in the signature to make call-site intent unambiguous
    completedTaskCount += 1L

  /** Emit a StorageQueuePressure transition if the pending-task queue depth has just crossed a watermark. Called after
    * every enqueue and dequeue. Forwarded to AccountRangeCoordinator via SNAPSyncController so account workers stop
    * producing new storage tasks during back-pressure.
    */
  private def notifyBackpressureIfChanged(): Unit =
    val pending = tasks.size
    com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncMetrics.setStorageQueueDepth(pending.toLong)
    com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncMetrics.setStorageInFlightRequests(activeTasks.size)
    intakeBudget.foreach { budget =>
      budget.storageQueueDepth(pending.toLong)
      budget.publishMetrics()
    }
    com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncMetrics.setStorageActivePeers(
      (knownAvailablePeers.size - statelessPeers.size).max(0)
    )
    if !backpressureActive && pending >= backpressureHighWatermark then
      backpressureActive = true
      com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncMetrics.setStorageBackpressure(true)
      log.info(
        s"Storage queue back-pressure ENGAGED at $pending pending tasks (high-water=$backpressureHighWatermark). " +
          s"Signalling AccountRangeCoordinator to pause dispatch."
      )
      snapSyncController ! SNAPSyncController.StorageBackpressureChanged(paused = true)
    else if backpressureActive && pending <= backpressureLowWatermark then
      backpressureActive = false
      com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncMetrics.setStorageBackpressure(false)
      log.info(
        s"Storage queue back-pressure RELEASED at $pending pending tasks (low-water=$backpressureLowWatermark). " +
          s"Signalling AccountRangeCoordinator to resume dispatch."
      )
      snapSyncController ! SNAPSyncController.StorageBackpressureChanged(paused = false)

  /** ByteString ordering for sorted insertion — compares bytes lexicographically. */
  private object ByteStringOrdering extends Ordering[ByteString]:
    def compare(a: ByteString, b: ByteString): Int =
      val len = math.min(a.length, b.length)
      var i = 0
      var result = 0
      while i < len && result == 0 do
        val diff = (a(i) & 0xff) - (b(i) & 0xff)
        if diff != 0 then result = diff
        i += 1
      if result != 0 then result else a.length - b.length

  /** Discard any in-memory streaming tries and flush the tail of accumulated flat-slot writes when the actor stops.
    * Formerly `postStop`; now invoked from the `PostStop` signal handler in `active()`.
    */
  private def onPostStop(): Unit =
    // The tracker's timers outlive this actor; cancel ours so none fires for a stopped coordinator.
    activeTasks.keys.foreach(requestTracker.cancelRequest)
    // Discard any in-memory streaming tries — already-flushed nodes stay on disk
    // (content-addressed; healing reconciles).
    if pendingAccountTries.nonEmpty then
      log.info(s"postStop: discarding ${pendingAccountTries.size} in-flight per-account storage tries")
      pendingAccountTries.values.foreach(_.reset())
      pendingAccountTries.clear()
      com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncMetrics.setStoragePendingTries(0L)
    // Ordering-gate state (cursors, last-applied-slot markers, buffered out-of-order chunks) is
    // discarded along with the tries above — the actor is stopping, so there is nothing left to
    // drain or re-queue into.
    storageTrieCursor.clear()
    lastAppliedSlot.clear()
    pendingOrderedChunks.clear()
    staleRootFailuresByAccount.clear()
    abandonedAccounts.clear()
    // Best-effort: flush any tail of accumulated flat-slot entries synchronously here so we
    // don't lose data when the actor terminates (force-complete, restart).
    // This synchronous commit is the next batch in sequence; the same rule picks which staged markers may ride in it
    // (an async batch still in flight blocks the markers whose slots it carries). The rest are dropped: re-downloaded.
    val stopBatchSeq = flatBatchSeq + 1
    val doneMarkers = takeDoneMarkersFor(stopBatchSeq)
    flatBatchSeq = stopBatchSeq
    pendingDoneMarkers.clear()
    if pendingFlatBatchAccounts.nonEmpty || doneMarkers.nonEmpty then
      try
        var combined: DataSourceBatchUpdate = flatSlotStorage.emptyBatchUpdate
        pendingFlatBatchAccounts.foreach { case (accountHash, slots) =>
          combined = combined.and(flatSlotStorage.putSlotsBatch(accountHash, slots))
        }
        if doneMarkers.nonEmpty then storageDoneStorage.foreach(d => combined = combined.and(d.markDone(doneMarkers)))
        combined.commit()
        log.info(
          s"postStop: flushed final ${pendingFlatBatchEntries} flat slot entries, ${doneMarkers.size} completion markers"
        )
      catch
        case e: Exception =>
          log.error(s"postStop: failed to flush final flat batch: ${e.getMessage}")
      pendingFlatBatchAccounts.clear()
      pendingFlatBatchEntries = 0

  // Storage management.
  // MerkleProofVerifier is constructed inline per response (see verifyStorageRange call).
  // It was previously cached per storage root in a mutable.Map cleared only on pivot
  // refresh — but the verifier holds just a 32-byte root + Logger, so the cache saved
  // nothing and leaked one entry per distinct contract storage root (~0.3–0.5GiB of dead
  // heap at ETC's 13M+ contracts). Inline construction makes the heap O(in-flight), not
  // O(contracts-seen-since-last-pivot).

  // preStart equivalent: log and schedule the recurring liveness pulse. Called once by the behavior
  // factory. The Typed `startTimerWithFixedDelay` replaces the Classic Cancellable; it auto-cancels
  // when the behavior stops. The old Classic `supervisorStrategy` is dropped — SRC spawns no child
  // workers (it dispatches requests directly to networkPeerManager), so it supervised nothing.
  def start(): Behavior[Command] =
    log.info(s"StorageRangeCoordinator starting (concurrency=$maxInFlightRequests, batchSize=$maxAccountsPerBatch)")
    // Periodic liveness: re-evaluate dispatch and pivot refresh even when no events flow.
    // Without this, ghost peers cause a silent stall with no incoming messages to trigger re-evaluation.
    timers.startTimerWithFixedDelay(StorageCheckCompletion, 30.seconds)
    // Bounds how long a completion marker waits for a batch to ride in while slots trickle in below the threshold.
    if recordStorageDone then timers.startTimerWithFixedDelay(FlushStorageDoneMarkers, DoneMarkerFlushInterval)
    // A new (or supervisor-restarted) instance starts with an empty queue: drop the previous instance's counts.
    intakeBudget.foreach(_.attachStorageConsumer())
    active()

  def active(): Behavior[Command] = Behaviors
    .receiveMessage[Command] {
      case StartStorageRangeSync(root) =>
        log.info(s"Starting storage range sync for state root ${root.take(8).toHex}")

        Behaviors.same

      case AddStorageTasks(storageTasks) =>
        tasks.enqueueAll(storageTasks)
        intakeBudget.foreach(_.storageReceived(storageTasks.size, tasks.size.toLong))
        totalStorageContracts += storageTasks.map(_.accountHash).distinct.size
        log.info(
          s"Added ${storageTasks.size} storage tasks to queue (total pending: ${tasks.size}, contracts: $totalStorageContracts)"
        )
        // Account-range completion is the only path that can grow the queue faster than dispatch.
        // Check watermarks immediately so the AccountRangeCoordinator pause signal goes out before
        // the next account-range batch lands.
        notifyBackpressureIfChanged()
        Behaviors.same

      case AddStorageTask(task) =>
        tasks.enqueue(task)
        log.debug(s"Added storage task for account ${task.accountString} to queue")
        Behaviors.same

      case StoragePeerAvailable(peer) =>
        // Evict stale entry for same physical node (reconnection creates new PeerId).
        // Only clear stateless marking for peers that actually reconnected with a NEW ID.
        // If the same peer is re-reported (same id), preserve its stateless marking —
        // otherwise StoragePeerAvailable from AccountRangeCoordinator clears stateless
        // every ~1s, bypassing the backoff mechanism entirely (Bug 24).
        val evicted = knownAvailablePeers.filter(_.remoteAddress == peer.remoteAddress)
        knownAvailablePeers --= evicted
        evicted.foreach { p =>
          if p.id.value != peer.id.value then statelessPeers -= p.id.value
        }
        knownAvailablePeers += peer
        if isPostRefreshCooldownActive then
          log.debug(s"Ignoring StoragePeerAvailable(${peer.id.value}) - post-refresh cooldown active")
        else if pivotRefreshRequested then
          log.debug(s"Ignoring StoragePeerAvailable(${peer.id.value}) - pivot refresh pending")
        else if isPeerStateless(peer) then
          log.debug(s"Ignoring StoragePeerAvailable(${peer.id.value}) - peer is stateless for current root")
        else if isPeerCoolingDown(peer) then
          log.debug(s"Ignoring StoragePeerAvailable(${peer.id.value}) due to cooldown")
        else if peerHealth.isPenalised(peer.id.value, System.currentTimeMillis()) then
          log.debug(s"Ignoring StoragePeerAvailable(${peer.id.value}) - peer is penalised for repeated timeouts")
        else if !isComplete && tasks.nonEmpty then
          // Pipeline multiple requests per peer (core-geth parity).
          dispatchIfPossible(peer)
        Behaviors.same

      case StoragePeerUnavailable(peerId) =>
        // Peer disconnected — remove from available set and immediately re-queue its in-flight
        // tasks so other peers can pick them up without waiting for the 30s request timeout.
        // Mirrors AccountRangeCoordinator.PeerUnavailable (go-ethereum revertRequests pattern).
        knownAvailablePeers.find(_.id.value == peerId).foreach(knownAvailablePeers -= _)
        peerCooldownUntilMs.remove(peerId)
        emptyResponseStrikes.remove(peerId)
        val inFlight = activeTasks.filter { case (_, (peer, _, _)) => peer.id.value == peerId }.keys.toSeq
        if inFlight.nonEmpty then
          log.debug(s"Peer $peerId disconnected — re-queuing ${inFlight.size} in-flight storage request(s)")
          inFlight.foreach { reqId =>
            activeTasks.remove(reqId).foreach { case (_, batchTasks, _) =>
              batchTasks.foreach { task =>
                val key = (task.accountHash, task.next)
                if !pendingTaskKeys.contains(key) then
                  pendingTaskKeys += key
                  tasks.enqueue(task.copy(pending = false))
              }
            }
          }
        tryRedispatchPendingTasks()
        Behaviors.same

      case UpdateMaxInFlightPerPeer(newLimit) =>
        log.info(s"Storage per-peer budget: $maxInFlightPerPeer -> $newLimit")
        maxInFlightPerPeer = newLimit
        if newLimit > 0 then tryRedispatchPendingTasks()
        Behaviors.same

      case StorageRangesResponseMsg(response) =>
        handleResponse(response)
        Behaviors.same

      case StorageTaskComplete(requestId, result) =>
        result match
          case Right(count) =>
            slotsDownloaded += count
            consecutiveTaskFailures = 0
            log.info(s"Storage task completed: $count slots")
            self ! StorageCheckCompletion
          case Left(error) =>
            log.warn(s"Storage task failed: $error")
        Behaviors.same

      case StorageCheckCompletion =>
        // Update contract completion progress for the progress monitor
        updateContractProgress()
        // Drain side of the back-pressure watermark — if dispatches and completions have shrunk
        // the queue below the low-water mark, release AccountRangeCoordinator's pause.
        notifyBackpressureIfChanged()
        // Drain the flat-batch accumulator once no more downloads are coming.
        if noMoreTasksExpected && tasks.isEmpty && activeTasks.isEmpty then flushPendingFlatBatch()
        if isComplete then
          log.debug("Storage range sync complete!")
          snapSyncController ! SNAPSyncController.StorageRangeSyncComplete
        else if tasks.nonEmpty then
          // Try to dispatch pending tasks — per-peer and global limits enforced in dispatchIfPossible().
          // Previously guarded by activeTasks.isEmpty which defeated pipelining.
          maybeRequestPivotRefresh()
          tryRedispatchPendingTasks()
        Behaviors.same

      case NoMoreStorageTasks =>
        noMoreTasksExpected = true
        log.info(
          s"No more storage tasks expected. Pending: ${tasks.size}, active: ${activeTasks.size}, " +
            s"in-flight tries: ${pendingAccountTries.size}"
        )
        // Flush the final flat-slot tail if all downloads are done.
        if tasks.isEmpty && activeTasks.isEmpty then flushPendingFlatBatch()
        if isComplete then
          log.debug("Storage range sync complete!")
          snapSyncController ! SNAPSyncController.StorageRangeSyncComplete
        Behaviors.same

      case ForceCompleteStorage =>
        if forceCompleteExecuted then log.debug("ForceCompleteStorage: already executed — ignoring duplicate")
        else
          forceCompleteExecuted = true
          val abandoned = tasks.size + activeTasks.size
          val abandonedTries = pendingAccountTries.size
          log.warn(
            s"Force-completing storage sync: $slotsDownloaded slots downloaded, " +
              s"abandoning $abandoned remaining tasks, $abandonedTries in-flight per-account tries " +
              s"(healing phase will recover missing data)"
          )
          // Discard in-flight streaming tries — already-flushed nodes stay on disk; healing reconciles.
          pendingAccountTries.values.foreach(_.reset())
          pendingAccountTries.clear()
          com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncMetrics.setStoragePendingTries(0L)
          // Ordering-gate state follows the same "abandon, healing recovers" contract as the tries.
          storageTrieCursor.clear()
          lastAppliedSlot.clear()
          pendingOrderedChunks.clear()
          staleRootFailuresByAccount.clear()
          abandonedAccounts.clear()
          // The in-flight requests are abandoned with the tasks: cancel their tracker timers so a timeout cannot
          // re-count failures (and re-send ForceCompleteStorage) for work this coordinator no longer owns.
          activeTasks.keys.foreach(requestTracker.cancelRequest)
          activeTasks.clear()
          // Hand off any flat-slot tail still in the accumulator.
          flushPendingFlatBatch()
          log.info("Storage range sync force-completed (promoting to healing phase)")
          snapSyncController ! SNAPSyncController.StorageRangeSyncForceCompleted
        Behaviors.same

      case StoragePivotRefreshed(newStateRoot) =>
        log.info(s"Storage pivot refreshed: ${stateRoot.take(4).toHex} -> ${newStateRoot.take(4).toHex}")
        log.debug(s"Storage consecutive task failures reset on pivot refresh (was $consecutiveTaskFailures)")
        consecutiveTaskFailures = 0

        // Flush before mutating `stateRoot` so the off-actor commit is tagged with the OLD root.
        // The completion message will then arrive when `stateRoot` is the NEW root, take the stale
        // branch in the handler, and emit the "bookkeeping ignored, data on disk" debug line. Done
        // before the buffer-clears below so we don't have to coordinate the order with `pendingFlatBatchAccounts`.
        flushPendingFlatBatch()

        stateRoot = newStateRoot

        // Cancel all in-flight requests: their responses are for the old root and will
        // contaminate stateless detection if processed. Re-queue tasks for the new root.
        //
        // Also track, per account, the LOWEST `next` among survivors (this loop plus the buffered
        // chunks handled below) — this becomes the re-derived ordering-gate cursor once the tries
        // are wiped further down. Chunks that had already fully completed before the refresh are
        // NOT survivors (they are neither re-queued here nor buffered) and are correctly excluded:
        // they are abandoned along with the discarded trie, exactly like today's pre-fix behaviour.
        val survivingChunkStarts = mutable.Map.empty[ByteString, ByteString]
        def trackSurvivor(t: StorageTask): Unit =
          if t.next != zeroSlotHash then
            val isNewMinimum = survivingChunkStarts.get(t.accountHash).forall(cur => ByteStringOrdering.lt(t.next, cur))
            if isNewMinimum then survivingChunkStarts(t.accountHash) = t.next

        val cancelledCount = activeTasks.size
        activeTasks.values.foreach { case (_, batchTasks, _) =>
          batchTasks.foreach { task =>
            tasks.enqueue(task.copy(pending = false))
            trackSurvivor(task)
          }
        }
        activeTasks.clear()
        if cancelledCount > 0 then log.info(s"Cancelled $cancelledCount in-flight storage requests (stale root)")

        // Clear all per-peer adaptive state — fresh start with new root
        statelessPeers.clear()
        emptyResponseStrikes.clear()
        pivotRefreshRequested = false

        // Force-release storage back-pressure. Observed deadlock on sepolia 2026-05-14:
        // the queue locks at >100K pending → backpressure ENGAGED → AccountRangeCoordinator
        // dispatch paused → storage can't drain (no usable peers for current root) →
        // backpressure never releases (low-water = 50K is unreachable) → account stalls →
        // 5/5 critical SNAP failures → the fast-sync fallback of the time, which was just as
        // stuck on sepolia because peers don't serve GetNodeData on ETH/68+ (fast sync has
        // since been removed; the failures now put SNAP into dormant mode).
        //
        // The fix: pivot refresh is the natural recovery moment. New root → maybe new
        // peers can serve the queued tasks → drain might resume. Let account dispatch
        // resume; if the queue overflows past the high-water mark again, the next
        // notifyBackpressureIfChanged() call from AddStorageTasks will re-engage.
        if backpressureActive then
          backpressureActive = false
          com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncMetrics.setStorageBackpressure(false)
          log.info(
            s"Storage queue back-pressure RELEASED on pivot refresh (queue depth=${tasks.size}). " +
              s"Will re-engage if queue crosses high-water=$backpressureHighWatermark again."
          )
          snapSyncController ! SNAPSyncController.StorageBackpressureChanged(paused = false)
        lastDispatchOrResponseMs = System.currentTimeMillis()
        peerCooldownUntilMs.clear()
        // A new root is a fresh chance: lift timeout penalties (levels kept, so a dead peer re-penalises on its next
        // timeout). A stale root or a local stall times out every peer at once; without this the next root would be
        // served by a single probe slot while the pool sits out 8-30 min penalties.
        val liftedPenalties = peerHealth.liftPenalties()
        if liftedPenalties > 0 then
          log.info(s"[STORAGE-PEER-HEALTH] pivot refreshed: lifted timeout penalties for $liftedPenalties peer(s)")
        peerBatchSize.clear()
        peerBatchSuccessStreak.clear()
        peerResponseBytesTarget.clear()
        emptyResponsesByTask.clear()

        // Discard streaming per-account tries — already-flushed content-addressed nodes
        // remain on disk and will be referenced by the new root's healing pass if still valid,
        // unreferenced and pruned otherwise. Only the in-memory stack-trie state is dropped.
        if pendingAccountTries.nonEmpty then
          log.info(s"Pivot refresh: resetting ${pendingAccountTries.size} in-flight per-account storage tries")
          pendingAccountTries.values.foreach(_.reset())
          pendingAccountTries.clear()
          com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncMetrics.setStoragePendingTries(0L)

        // Re-queue any chunks that were verified but still buffered awaiting their turn (see the
        // ordering gate near `pendingAccountTries`) — otherwise that slice of the account's storage
        // would be silently dropped (unlike the cancelled activeTasks above, buffered chunks are
        // not tracked anywhere else). Fold them into the same survivor-minimum as activeTasks.
        val bufferedChunkCount = pendingOrderedChunks.valuesIterator.map(_.size).sum
        if bufferedChunkCount > 0 then
          pendingOrderedChunks.values.foreach { buffered =>
            buffered.values.foreach { chunk =>
              tasks.enqueue(chunk.task.copy(pending = false))
              trackSurvivor(chunk.task)
            }
          }
          log.info(s"Pivot refresh: re-queued $bufferedChunkCount buffered out-of-order storage chunk(s)")
        pendingOrderedChunks.clear()
        // Unlike storageTrieCursor (re-derived below), lastAppliedSlot has no re-derivation: the
        // tries were just wiped above, so NOTHING has been applied to the fresh trie generation yet
        // — a plain clear is correct here, not a partial one.
        lastAppliedSlot.clear()
        // Deliberately NOT cleared here: staleRootFailuresByAccount must PERSIST across a pivot
        // refresh. Its whole purpose is to detect "this account still fails the same way even
        // against a DIFFERENT root" (the distinctRoots >= 2 condition) — clearing it on refresh
        // would erase exactly the evidence that makes that diagnosis possible, and a genuinely
        // stale account-range record would then just re-accumulate 3 more failures against the new
        // root before being caught, unbounded across however many refreshes occur.
        // Re-derive the cursor from this refresh's survivors — NOT a plain clear. A stale (or
        // missing-then-absent-check-bypassed) cursor would let a re-queued chunk skip the gate
        // entirely; explicitly setting it to each account's lowest surviving `next` keeps the
        // invariant applyOrderedStorageChunk relies on: a non-zeroSlotHash task with no tracked
        // cursor is always genuinely first-of-its-generation.
        storageTrieCursor.clear()
        storageTrieCursor ++= survivingChunkStarts

        // Set post-refresh cooldown: peers need time to sync to the new root.
        // Dispatching immediately causes all peers to return empty → marked stateless →
        // another pivot refresh → infinite tight loop (Bug 24).
        postRefreshCooldownUntilMs = System.currentTimeMillis() + postRefreshCooldownMs
        log.info(
          s"Post-refresh cooldown active for ${postRefreshCooldownMs / 1000}s — waiting for peers to sync to new root"
        )

        // Schedule dispatch after the cooldown period instead of dispatching immediately
        context.scheduleOnce(postRefreshCooldownMs.millis, self, StorageCheckCompletion)
        Behaviors.same

      case StorageGetProgress(replyTo) =>
        val stats = StorageRangeCoordinator.SyncStatistics(
          slotsDownloaded = slotsDownloaded,
          bytesDownloaded = bytesDownloaded,
          tasksCompleted = completedTaskCount.toInt,
          tasksActive = activeTasks.values.map(_._2.size).sum,
          tasksPending = tasks.size,
          elapsedTimeMs = System.currentTimeMillis() - startTime,
          progress = progress,
          staleRootFailureEvents = staleRootFailureEvents
        )
        replyTo ! stats
        Behaviors.same

      case FlushStorageDoneMarkers =>
        // Submits the staged slots a marker waits on, and every marker whose slots have committed.
        if pendingDoneMarkers.nonEmpty then flushPendingFlatBatch()
        Behaviors.same

      case FlatBatchFlushComplete(forStateRoot, entryCount, elapsedMs, seq) =>
        inFlightFlatBatches = (inFlightFlatBatches - 1).max(0)
        recordFlatBatchDone(seq)
        if forStateRoot != stateRoot then
          log.debug(
            s"Flat batch flush completed for stale root ${forStateRoot.take(4).toHex} " +
              s"($entryCount entries) — bookkeeping ignored, data on disk"
          )
        else
          val rate = if elapsedMs > 0 then entryCount * 1000L / elapsedMs else entryCount.toLong
          log.debug(s"Flat batch flushed: $entryCount slots in ${elapsedMs}ms ($rate slots/s)")
        // A drained flush may have unblocked completion (NoMore + empty queues).
        self ! StorageCheckCompletion
        Behaviors.same

      case FlatBatchFlushFailed(forStateRoot, entryCount, error) =>
        inFlightFlatBatches = (inFlightFlatBatches - 1).max(0)
        if storageDoneStorage.isDefined && !doneMarkersDisabled then
          // The failed batch's slots are gone, and any staged account may have had a chunk in it: stop vouching.
          doneMarkersDisabled = true
          log.warn(
            s"Flat batch failed: disabling storage completion markers for this coordinator " +
              s"(${pendingDoneMarkers.size} staged markers dropped; those tasks are re-downloaded after a restart)"
          )
          pendingDoneMarkers.clear()
        log.error(
          s"Flat batch flush failed for $entryCount slots " +
            s"(root ${forStateRoot.take(4).toHex}): $error. Healing phase will recover."
        )
        self ! StorageCheckCompletion
        Behaviors.same

      case StorageRequestTimedOut(requestId) =>
        // A request already completed or abandoned (force-complete) is no longer in activeTasks: nothing to retry.
        if activeTasks.contains(requestId) then handleTimeout(requestId)
        Behaviors.same

      // Defensive: `Command` is non-sealed (cross-file constraint), so the compiler cannot prove
      // exhaustiveness. No production sender emits an un-handled Command; treat any as unhandled.
      case other =>
        log.debug(s"StorageRangeCoordinator received unhandled command: $other")
        Behaviors.unhandled
    }
    .receiveSignal { case (_, org.apache.pekko.actor.typed.PostStop) =>
      // Formerly `postStop`: the recurring liveness timer auto-cancels with the behavior.
      onPostStop()
      Behaviors.same
    }

  private def requestNextRanges(peer: Peer): Option[BigInt] =
    val min = ByteString(Array.fill(32)(0.toByte))
    val max = ByteString(Array.fill(32)(0xff.toByte))
    def isInitialRange(t: StorageTask): Boolean = t.next == min && t.last == max

    // Streaming-trie memory cap: a new account's trie costs up to ~8 MiB worst-case.
    // Continuations for accounts already in `pendingAccountTries` are free — they reuse
    // the existing trie. Reject only the dispatch of brand-new accounts when at the cap.
    // This puts a hard ceiling on storage-processing memory regardless of chain size.
    def acceptsNewAccount(t: StorageTask): Boolean =
      deferredMerkleization ||
        pendingAccountTries.contains(t.accountHash) ||
        pendingAccountTries.size < maxConcurrentStorageAccounts

    // Pre-dispatch guards. Each blocks dispatch (returns None) without mutating queue state.
    // Peek-ahead at the front of the queue: if the head task would force a new account
    // open beyond the cap, leave it queued and skip this dispatch cycle. Once an in-flight
    // trie commits (or aborts), the cap relaxes and the next dispatch will pick it up.
    val blocked: Boolean =
      if tasks.isEmpty then
        log.debug("No more storage tasks available")
        true
      else if isPostRefreshCooldownActive || pivotRefreshRequested || isPeerStateless(peer) then true
      else if !acceptsNewAccount(tasks.front) then
        log.debug(
          s"Storage dispatch gated by max-concurrent-storage-accounts=$maxConcurrentStorageAccounts " +
            s"(in-flight tries=${pendingAccountTries.size}); deferring new-account dispatch"
        )
        true
      else false

    if blocked then None
    else
      val peerBatch = batchSizeFor(peer)

      // snap/1 origin/limit semantics apply to the first account only. To avoid incorrect continuation
      // behavior, only batch tasks that request the initial full range.
      val first = tasks.dequeue()
      pendingTaskKeys -= ((first.accountHash, first.next))
      val batchTasks: Seq[StorageTask] =
        if !isInitialRange(first) || peerBatch <= 1 then Seq(first)
        else
          val buf = mutable.ArrayBuffer[StorageTask](first)
          while buf.size < peerBatch && tasks.nonEmpty && isInitialRange(tasks.front) && acceptsNewAccount(tasks.front)
          do
            val t = tasks.dequeue()
            pendingTaskKeys -= ((t.accountHash, t.next))
            buf += t
          buf.toSeq
      // Dispatch drains the queue: let a waiting producer see the room right away.
      intakeBudget.foreach(_.storageQueueDepth(tasks.size.toLong))

      if batchTasks.isEmpty then None
      else
        val requestedBytes = responseBytesTargetFor(peer)
        val requestId = requestTracker.generateRequestId()
        val accountHashes = batchTasks.map(_.accountHash)
        val firstTask = batchTasks.head

        val request = GetStorageRanges(
          requestId = requestId,
          rootHash = stateRoot,
          accountHashes = accountHashes,
          startingHash = firstTask.next,
          limitHash = firstTask.last,
          responseBytes = requestedBytes
        )

        val activeBatchTasks = batchTasks.map(_.copy(pending = true))
        activeTasks.put(requestId, (peer, activeBatchTasks, requestedBytes))

        requestTracker.trackRequest(
          requestId,
          peer,
          SNAPRequestTracker.RequestType.GetStorageRanges,
          timeout = requestTimeout
        ) {
          // Runs on the scheduler's thread, not this actor's: hop onto the mailbox. Calling handleTimeout here mutated
          // actor state from a foreign thread and, because the tracker timer outlives the actor, kept re-firing
          // [STORAGE-FORCE-COMPLETE] for a coordinator that was already stopped (devnet-8, 2026-10-03).
          self ! StorageRequestTimedOut(requestId)
        }

        log.info(
          s"GetStorageRanges: peer=${peer.id.value} accounts=${batchTasks.size} bytes=$requestedBytes requestId=$requestId"
        )

        // Full request details at DEBUG level for troubleshooting
        log.debug(
          s"GetStorageRanges detail: requestId=$requestId root=${stateRoot.toHex} " +
            s"start=${firstTask.next.toHex} limit=${firstTask.last.toHex} " +
            s"accounts=${accountHashes.map(_.take(4).toHex).mkString(",")}"
        )

        import com.chipprbots.ethereum.network.p2p.messages.SNAP.GetStorageRanges.GetStorageRangesEnc
        val messageSerializable: MessageSerializable = new GetStorageRangesEnc(request)
        networkPeerManager ! NetworkPeerManagerActor.SendMessageCmd(messageSerializable, peer.id)
        lastDispatchOrResponseMs = System.currentTimeMillis()

        Some(requestId)

  private def handleResponse(response: StorageRanges): Unit =
    requestTracker.validateStorageRanges(response) match
      case Left(error) =>
        log.warn(s"Invalid StorageRanges response: $error")

      case Right(validResponse) =>
        val slotCount = validResponse.slots.map(_.size).sum
        requestTracker.completeRequest(response.requestId, slotCount.max(1)) match
          case None =>
            log.warn(s"Received response for unknown request ID ${response.requestId}")

          case Some(_) =>
            activeTasks.remove(response.requestId) match
              case None =>
                log.warn(s"No active tasks for request ID ${response.requestId}")

              case Some((peer, batchTasks, requestedBytes)) =>
                processStorageRanges(peer, batchTasks, requestedBytes, validResponse)

  private def processStorageRanges(
      peer: Peer,
      tasks: Seq[StorageTask],
      requestedBytes: BigInt,
      response: StorageRanges
  ): Unit =
    // Count only responses that actually contain slot data as "served".
    // Proof-only responses (0 slot-sets, non-empty proofs) are NOT counted as served because:
    //  1. After a pivot refresh, peers may return proof-of-absence for stale task roots
    //  2. The proof root may not match the task's storageRoot (undetected by lenient verification)
    //  3. Treating proof-only as served prevents stateless detection, causing indefinite stalls
    // Legitimate empty-storage accounts will be completed via the empty-response skip mechanism
    // after maxEmptyResponsesPerTask attempts.
    val servedCount: Int = response.slots.count(_.nonEmpty)

    log.info(
      s"Processing storage ranges for ${tasks.size} accounts from peer ${peer.id.value}, " +
        s"received ${response.slots.size} slot sets (served=$servedCount, proofs=${response.proof.size})"
    )

    // Proof-of-absence: server returned 0 slots WITH proof nodes. Per the snap/1 protocol,
    // this is a valid cryptographic proof that no slots exist in [startingHash, limitHash]
    // at the current state root. The account's storage is empty or was modified/cleared
    // since the original pivot. Healing will validate the final trie.
    // IMPORTANT: do NOT mark the peer stateless — it served a valid, well-formed response.
    // Only fall through to stateless marking when proofs == 0 (peer gave us nothing at all).
    def handleProofOfAbsence(): Unit =
      // Route through the same ordered-completion/cursor bookkeeping as any other served chunk
      // (applyOrderedStorageChunk / applyReadyStorageChunk): a proof-of-absence response is a
      // complete, valid answer for THIS chunk's [next, last] — like a served chunk whose slots
      // reach task.last, just with zero slots — so it must advance storageTrieCursor and count
      // towards accountSubtaskCounters the same way. Continuation and subtask chunks are always
      // dispatched solo (requestNextRanges/isInitialRange), so this is reached for genuinely
      // mid-range, multi-chunk accounts, not just fresh/whole-account requests: without this, a
      // legitimately-empty sub-range of a sparse account permanently blocks that account's
      // completion — and any higher-range siblings already buffered behind it — until the whole
      // storage phase stalls out to the force-complete fallback.
      val task = tasks.head
      applyOrderedStorageChunk(peer, task, Seq.empty, response.proof)
      log.warn(
        s"Storage proof-of-absence accepted: account=${task.accountString} " +
          s"storageRoot=${task.storageRoot.take(4).toHex} range=${task.rangeString} " +
          s"proofNodes=${response.proof.size} peer=${peer.id.value}. " +
          s"Account storage empty/changed at current pivot — healing will validate."
      )
      // Peer is healthy — clear any penalty state it accumulated.
      statelessPeers.remove(peer.id.value)
      recordPeerAnswered(peer.id.value)
      lastDispatchOrResponseMs = System.currentTimeMillis()
      consecutiveUnproductiveRefreshes = 0
      self ! StorageCheckCompletion
      dispatchIfPossible(peer)

    // Empty response with no usable proof-of-absence: re-queue/skip tasks and mark peer stateless.
    def handleEmptyResponse(): Unit =
      // Per-peer batch reduction: only reduce for the specific peer that failed
      if tasks.size > 1 && batchSizeFor(peer) > 1 then
        log.info(
          s"Received empty StorageRanges for a batched request from peer ${peer.id.value} (accounts=${tasks.size}); " +
            s"falling back to single-account requests for this peer"
        )
        reduceBatchSize(peer)

      adjustResponseBytesOnFailure(peer, "empty response")

      // Track empties per task to avoid re-queueing forever.
      // If the same task yields empty responses repeatedly, skip it with a loud warning.
      var skipped = 0
      tasks.filterNot(t => abandonedAccounts.contains(t.accountHash)).foreach { task =>
        val key = StorageTaskKey(task.accountHash, task.next, task.last)
        val attempts = emptyResponsesByTask.getOrElse(key, 0) + 1
        emptyResponsesByTask.update(key, attempts)

        if attempts >= maxEmptyResponsesPerTask then
          skipped += 1
          val doneTask = task.copy(done = true, pending = false)
          recordCompletedTask(doneTask)
          // Discard any partial streaming trie for this account — committing now would
          // produce a wrong root (missing slots). Already-flushed content-addressed nodes
          // stay on disk and healing reconciles when the contract is revisited.
          resetAccountTrie(task.accountHash)
          com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncMetrics
            .setStoragePendingTries(pendingAccountTries.size.toLong)
          log.warn(
            s"Skipping storage task after $attempts empty StorageRanges replies: " +
              s"account=${task.accountHash.toHex} storageRoot=${task.storageRoot.toHex} range=${task.rangeString}"
          )
        else
          this.tasks.enqueue(task.copy(pending = false))
          log.debug(
            s"Empty StorageRanges for task (attempt $attempts/$maxEmptyResponsesPerTask); re-queueing: " +
              s"account=${task.accountHash.take(4).toHex} range=${task.rangeString}"
          )
      }

      // Always mark this peer as stateless for the current root on empty response.
      // Even if some tasks were skipped, the peer still couldn't serve any data.
      // This ensures stateless detection triggers pivot refresh when ALL peers fail,
      // rather than silently draining tasks as "empty" one by one.
      markPeerStateless(peer)

      if skipped > 0 then self ! StorageCheckCompletion

    if servedCount == 0 then
      if response.proof.nonEmpty && tasks.size == 1 then handleProofOfAbsence()
      else handleEmptyResponse()
    else processServedTasks(peer, tasks, requestedBytes, response, servedCount)

  /** Handle the non-empty (served) branch of a StorageRanges response: clear stateless marking, verify proofs, stream
    * slots into per-account tries, and stage flat-slot writes.
    */
  private[actors] def processServedTasks(
      peer: Peer,
      tasks: Seq[StorageTask],
      requestedBytes: BigInt,
      response: StorageRanges,
      servedCount: Int
  ): Unit =
    // Non-empty response with actual slot data — clear stateless marking and reset backoff.
    statelessPeers.remove(peer.id.value)
    recordPeerSuccess(peer.id.value)
    consecutiveUnproductiveRefreshes = 0
    lastDispatchOrResponseMs = System.currentTimeMillis()

    // Adaptive batch scaling: track successes for this peer, scale up after consecutive packed responses
    maybeIncreaseBatchSize(peer, servedCount, tasks.size)

    // Clear empty-response counters for tasks that are now being served.
    tasks.foreach { task =>
      emptyResponsesByTask.remove(StorageTaskKey(task.accountHash, task.next, task.last))
    }

    val servedTasks = tasks.take(servedCount)
    val unservedTasks = tasks.drop(servedCount)

    if unservedTasks.nonEmpty then
      log.debug(s"Re-queueing ${unservedTasks.size} unserved storage tasks")
      unservedTasks.filterNot(t => abandonedAccounts.contains(t.accountHash)).foreach { task =>
        this.tasks.enqueue(task.copy(pending = false))
      }

    // Track total received bytes across all served tasks for adaptive byte budgeting
    var totalReceivedBytes: Long = 0

    // Given-up accounts (see giveUpOnAccountForHealing): a late/in-flight sibling response is ignored, not verified,
    // applied or re-queued. Index alignment with `response.slots` is preserved by filtering AFTER zipWithIndex.
    servedTasks.zipWithIndex
      .filter { case (t, _) =>
        val abandoned = abandonedAccounts.contains(t.accountHash)
        if abandoned then recordCompletedTask(t.copy(done = true, pending = false))
        !abandoned
      }
      .foreach { case (task0, idx) =>
        val accountSlots =
          if response.slots.nonEmpty && idx < response.slots.size then response.slots(idx)
          else Seq.empty

        // Best-practice: apply proof nodes only to the last served slot-set.
        val proofForThisTask = if idx == servedCount - 1 then response.proof else Seq.empty

        val task = task0.copy(slots = accountSlots, proof = proofForThisTask)

        val verifier = MerkleProofVerifier(task.storageRoot)
        val storageEndHash = accountSlots.lastOption.map(_._1).getOrElse(task.last)
        verifier.verifyStorageRange(accountSlots, proofForThisTask, task.next, storageEndHash) match
          case Left(error) if isStaleLocalRootSignature(error) =>
            // Not evidence of peer fault (see staleRootFailuresByAccount's doc) — no peer penalty.
            val failures = staleRootFailuresByAccount.getOrElse(task.accountHash, Vector.empty) :+
              StaleRootFailure(peer.id.value, stateRoot)
            staleRootFailuresByAccount(task.accountHash) = failures
            staleRootFailureEvents += 1
            val distinctPeers = failures.map(_.peerId).distinct.size
            val distinctRoots = failures.map(_.root).distinct.size
            log.warn(
              s"Storage proof verification failed for account ${task.accountString}: $error " +
                s"(stale-local-root attempt ${failures.size}/$maxStaleRootFailuresPerAccount, " +
                s"peers=$distinctPeers, roots=$distinctRoots)"
            )
            if failures.size >= maxStaleRootFailuresPerAccount && (distinctPeers >= 2 || distinctRoots >= 2) then
              giveUpOnAccountForHealing(task, failures.size, distinctPeers, distinctRoots)
              self ! StorageCheckCompletion
            else this.tasks.enqueue(task.copy(pending = false))

          case Left(error) =>
            log.warn(s"Storage proof verification failed for account ${task.accountString}: $error")
            recordPeerCooldown(peer, s"verification failed: $error")
            adjustResponseBytesOnFailure(peer, s"verification failed: $error")
            this.tasks.enqueue(task.copy(pending = false))

          case Right(_) =>
            // A successful verification clears any stale-local-root failure history for this
            // account (requirement: "an account that fails once and then succeeds is not dropped") —
            // whatever caused the earlier mismatch no longer applies.
            staleRootFailuresByAccount.remove(task.accountHash)
            val slotBytes = accountSlots.map { case (hash, value) => hash.size + value.size }.sum
            totalReceivedBytes += slotBytes
            // Cross-chunk range ordering (storageConcurrency parallel subtasks racing on response
            // arrival) is enforced by the gate, not here — see applyOrderedStorageChunk.
            applyOrderedStorageChunk(peer, task, accountSlots, proofForThisTask)
      }

    // Adjust per-peer byte budget based on total received bytes
    if totalReceivedBytes > 0 then adjustResponseBytesOnSuccess(peer, requestedBytes, BigInt(totalReceivedBytes))

    // Check completion after processing all served tasks
    self ! StorageCheckCompletion

    // Immediately pipeline more work to this peer — don't wait for StoragePeerAvailable
    dispatchIfPossible(peer)

  /** Ordering gate: apply a verified storage-range chunk to its account's shared trie immediately if it is the next
    * range-ascending chunk expected, otherwise buffer it until earlier sibling chunks have landed. See the
    * "Range-ascending ordering gate" field-block (near `pendingAccountTries`) for the full rationale.
    *
    * A task whose `next` is the account's true starting point (`zeroSlotHash`) is always the very first data ever seen
    * for that account's CURRENT trie generation — subtask splitting only happens AFTER this response is processed (see
    * `applyReadyStorageChunk`), so nothing can race it, and no cursor is tracked for it. Skipping the gate for this
    * case keeps the common (non-subtasked, small-account) path free of any bookkeeping.
    *
    * A `next` for which NO cursor is currently tracked (despite not being `zeroSlotHash`) means this account's ordering
    * state was just wiped (pivot refresh / force-complete / postStop) while this chunk was in flight — those wipes
    * discard ALL prior trie progress for the account, so whichever surviving chunk arrives first really is the first
    * the (fresh) trie will ever see, and applying it immediately is correct. `StoragePivotRefreshed` additionally
    * re-derives an explicit cursor (the minimum `next` among survivors) for chunks it re-queues, so this fallback is
    * only exercised for chunks that out-race that re-derivation.
    */
  private[actors] def applyOrderedStorageChunk(
      peer: Peer,
      task: StorageTask,
      accountSlots: Seq[(ByteString, ByteString)],
      proofForThisTask: Seq[ByteString]
  ): Unit =
    val accountHash = task.accountHash
    val outOfOrder =
      task.next != zeroSlotHash && storageTrieCursor.get(accountHash).exists(_ != task.next)
    if outOfOrder then
      val perAccount =
        pendingOrderedChunks.getOrElseUpdate(
          accountHash,
          mutable.TreeMap.empty[ByteString, ReadyStorageChunk](ByteStringOrdering)
        )
      perAccount.update(task.next, ReadyStorageChunk(peer, task, accountSlots, proofForThisTask))
      log.debug(
        s"Storage chunk out of range-order for account ${task.accountString}: " +
          s"got next=${task.next.take(4).toHex} expected=${storageTrieCursor.get(accountHash).map(_.take(4).toHex)}; " +
          s"buffering (${perAccount.size} chunk(s) now waiting for this account)"
      )
    else
      applyReadyStorageChunk(peer, task, accountSlots, proofForThisTask)
      drainOrderedStorageChunks(accountHash)

  /** After applying a chunk (and possibly advancing the account's cursor), release any buffered chunks that are now
    * next-in-line — repeating until either the buffer is empty or the next required position hasn't arrived yet. Each
    * drained chunk carries its OWN originating peer (stored in `ReadyStorageChunk`) — NOT necessarily the peer that
    * answered whichever response triggered this drain — so a duplicate-value rejection discovered here penalises the
    * peer that actually served the bad data.
    */
  private[actors] def drainOrderedStorageChunks(accountHash: ByteString): Unit =
    var draining = true
    while draining do
      val ready = for
        perAccount <- pendingOrderedChunks.get(accountHash)
        expected <- storageTrieCursor.get(accountHash)
        chunk <- perAccount.get(expected)
      yield (expected, chunk)
      ready match
        case Some((chunkStart, chunk)) =>
          val perAccount = pendingOrderedChunks(accountHash)
          perAccount.remove(chunkStart)
          // Prune the now-possibly-empty per-account TreeMap so `pendingOrderedChunks.get(acct)`
          // is `Some` only when a chunk is genuinely buffered — keeps clearStorageOrderingState's
          // "leftover" check and any external inspection (tests, metrics) accurate without relying
          // on every caller to also check `.isEmpty` on the inner map.
          if perAccount.isEmpty then pendingOrderedChunks.remove(accountHash)
          applyReadyStorageChunk(chunk.peer, chunk.task, chunk.accountSlots, chunk.proof)
        case None => draining = false

  /** Apply one verified, in-order storage chunk: insert its slots into the account's shared streaming trie, stage the
    * flat-slot mirror, and either enqueue a continuation/subtask split or — once every subtask for the account has
    * landed — commit the trie. Only ever called (via `applyOrderedStorageChunk`) for a chunk whose `task.next` is
    * exactly the position the trie is currently expecting, so cross-CHUNK raciness can never violate ascending order
    * here (see StackTrie.scala) — but a single chunk's response can still legitimately repeat the boundary slot the
    * trie already has (see `lastAppliedSlot`'s doc), so that case is filtered/validated before any insert happens.
    */
  private[actors] def applyReadyStorageChunk(
      peer: Peer,
      task0: StorageTask,
      accountSlots: Seq[(ByteString, ByteString)],
      proofForThisTask: Seq[ByteString]
  ): Unit =
    val accountHash = task0.accountHash
    var task = task0

    // Drop an exact repeat of the last-applied (key, value) — SNAP/1's origin is inclusive, so a
    // continuation or sub-range response's first slot legitimately CAN be the boundary slot this
    // account's trie already has. A DIFFERENT value under that same key means the peer's data is
    // inconsistent: reject the WHOLE response (peer penalty, retry) rather than accepting a
    // possibly-wrong value or letting the mismatch reach StackTrie.update's `require`. This check
    // runs before any insertion, so a rejected response never partially applies.
    val dedupedOrRejected: Either[String, Seq[(ByteString, ByteString)]] =
      lastAppliedSlot.get(accountHash) match
        case None => Right(accountSlots)
        case Some((lastKey, lastValue)) =>
          accountSlots.find(_._1 == lastKey) match
            case Some((_, repeatedValue)) if repeatedValue != lastValue =>
              Left(
                s"peer re-served slot ${lastKey.take(4).toHex} with a different value " +
                  s"than already applied (had ${lastValue.take(4).toHex}, got ${repeatedValue.take(4).toHex})"
              )
            case _ => Right(accountSlots.filterNot(_._1 == lastKey))

    dedupedOrRejected match
      case Left(reason) =>
        log.warn(s"Storage chunk rejected for account ${task.accountString}: $reason")
        recordPeerCooldown(peer, s"duplicate-key value mismatch: $reason")
        adjustResponseBytesOnFailure(peer, s"duplicate-key value mismatch: $reason")
        this.tasks.enqueue(task.copy(pending = false))

      case Right(dedupedSlots) =>
        if dedupedSlots.nonEmpty then
          // Stream slots directly into the per-account `SnapHashTrie`. Within one response, ordering
          // is already validated by MerkleProofVerifier before verification succeeds; across
          // responses for the same account, the caller (applyOrderedStorageChunk) guarantees this
          // call only happens in range-ascending order, and the dedup above has already removed any
          // exact repeat of the trie's current boundary key.
          if !deferredMerkleization then
            val trie = getOrCreateAccountTrie(accountHash)
            dedupedSlots.foreach { case (slotHash, slotValue) =>
              trie.update(slotHash.toArray, slotValue.toArray)
            }
            com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncMetrics
              .setStoragePendingTries(pendingAccountTries.size.toLong)

          // Flat-slot mirror — accountHash ++ slotHash → slotValue. Sorted in
          // `stageFlatSlotChunk` and accumulated for an off-actor batched commit.
          stageFlatSlotChunk(accountHash, dedupedSlots)
          lastAppliedSlot(accountHash) = dedupedSlots.last

          slotsDownloaded += dedupedSlots.size
          bytesDownloaded += dedupedSlots.map { case (hash, value) => hash.size + value.size }.sum

          snapSyncController ! SNAPSyncController.ProgressStorageSlotsSynced(dedupedSlots.size.toLong)

        // Whether THIS chunk needs a further continuation. A response with slots and a non-empty
        // proof that stops short of task.last means more remains in (lastSlot, task.last]. An EMPTY
        // response — whether a batched fresh account's genuine "no storage at all", a solo
        // subtask/continuation chunk's proof-of-absence for its OWN sub-range (routed here from
        // handleProofOfAbsence, realistic for a sparse contract's sub-range and NOT rare), or this
        // response's only slot having just been dropped as a boundary repeat — always means this
        // chunk's range is fully served: per SNAP spec, a peer omits the proof only when returning
        // the entire remainder, and includes one to prove absence otherwise, but either way there is
        // nothing left to ask this chunk's [next, last] for. Deliberately uses the ORIGINAL
        // `accountSlots` (not `dedupedSlots`): whether more data remains is a property of what the
        // PEER reported, independent of whether this response's first entry duplicated the boundary.
        val needsContinuation = accountSlots.nonEmpty && proofForThisTask.nonEmpty && {
          val lastSlot = accountSlots.last._1
          java.util.Arrays.compareUnsigned(lastSlot.toArray, task.last.toArray) < 0
        }

        if needsContinuation then
          val lastSlot = accountSlots.last._1
          // Explicitly (not lazily) fix the next-expected position: this is the only moment a new
          // chunk/continuation for this account is created, so it is the only safe place to set the
          // cursor. Inferring it later from "whichever sibling answers first" would reintroduce
          // exactly the race this gate exists to prevent.
          storageTrieCursor(accountHash) = StorageTask.incrementHash32(lastSlot)
          if accountSubtaskCounters.contains(accountHash) then
            // Already split into subtasks — this is a within-subtask continuation.
            val cont = StorageTask.createContinuation(task, lastSlot)
            this.tasks.enqueue(cont)
            log.debug(s"Within-subtask continuation for account ${task.accountString}")
          else
            // First continuation for this account → split into N parallel subtasks.
            val subtasks = createStorageSubTasks(task, lastSlot)
            subtasks.foreach(st => this.tasks.enqueue(st))
            accountSubtaskCounters(accountHash) = (subtasks.size, 0)
            log.debug(
              s"Large-storage account ${task.accountString}: " +
                s"split into ${subtasks.size} parallel subtasks"
            )
          // Persist the advancing storage cursor for crash recovery. Best-effort: concurrent
          // subtask writes for the same account may race, but worst case is a partial
          // re-download on resume, never data corruption.
          snapProgressStorage.foreach(
            _.writeStorageCursor(stateRoot, accountHash, StorageTask.incrementHash32(lastSlot))
          )
        else
          // This CHUNK's own assigned range is exhausted — either its slots reached task.last, or it
          // is legitimately empty (proof-of-absence for its own sub-range, or a fresh account with no
          // storage at all). Advance the cursor to the chunk's own upper bound, NOT
          // incrementHash32(lastSlot): for a non-empty completing response nothing more will ever
          // arrive between lastSlot and task.last, and for an empty one there is no lastSlot at all.
          // Either way the next sibling chunk's `next` is defined as incrementHash32(task.last)
          // (StorageTask.createSubTasks), so that is the only value that safely unblocks it — and the
          // ONLY value, whether this chunk had slots or not: a sparse account with a legitimately-empty
          // middle sub-range must complete like any other, not stall its siblings forever.
          storageTrieCursor(accountHash) = StorageTask.incrementHash32(task.last)

          // Gate the commit on the WHOLE ACCOUNT being done, not just this chunk: with
          // storageConcurrency parallel subtasks, this chunk finishing first does not mean its
          // siblings covering higher sub-ranges have landed yet. Committing unconditionally here (the
          // pre-fix behaviour) would remove/finalise the shared trie while siblings are still in
          // flight, corrupting the on-disk path-keyed nodes for this account.
          val accountFullyDone =
            if accountSubtaskCounters.contains(accountHash) then recordSubtaskCompletion(accountHash)
            else
              completedAccountCount += 1; true

          if accountFullyDone then
            // By construction this chunk was the account's range-highest surviving chunk (the gate
            // only ever lets chunks apply in ascending order), so nothing should still be buffered.
            // Defensive: re-queue anything found anyway rather than silently losing it.
            val leftover = clearStorageOrderingState(accountHash)
            if leftover.nonEmpty then
              log.warn(
                s"Account ${task.accountString} completed with ${leftover.size} orphaned buffered " +
                  s"storage chunk(s) — re-queueing rather than discarding"
              )
              leftover.foreach(t => this.tasks.enqueue(t.copy(pending = false)))

            val rootVerified =
              if deferredMerkleization then
                log.debug(
                  s"Account ${accountHash.take(4).toHex} fully downloaded (deferred merkleization — flat-only)"
                )
                true
              else
                val computedRoot = commitAccountTrie(accountHash, task.storageRoot)
                log.debug(
                  s"Account ${accountHash.take(4).toHex} streaming trie committed: root=${computedRoot.take(4).toHex}"
                )
                computedRoot == task.storageRoot
            // Completion marker — ONLY on this path (every subtask applied in order; when not deferring merkleization,
            // also trie committed with a matching root — deferred mode builds no trie, so the marker then vouches for
            // the flat slots alone, see SnapStorageDoneStorage). The give-up,
            // max-empty skip, abandoned and force-complete paths never mark: a resume downloads those again. A root
            // mismatch is not marked either, so a restart retries it rather than leaving it all to healing.
            if rootVerified then stageDoneMarker(accountHash, task.storageRoot)

        task = task.copy(done = true, pending = false)
        recordCompletedTask(task)

  private def handleTimeout(requestId: BigInt): Unit =
    activeTasks.remove(requestId).foreach { case (peer, batchTasks, _) =>
      log.warn(s"Storage range request timeout for ${batchTasks.size} accounts from peer ${peer.id.value}")
      recordPeerCooldown(peer, "request timeout")
      peerHealth.recordTimeout(peer.id.value, System.currentTimeMillis(), knownAvailablePeers.size).foreach { penalty =>
        // At most once per penalty window per peer: a penalised peer is not dispatched to, so it cannot time out again
        // until the penalty lapses.
        log.info(
          s"[STORAGE-PEER-HEALTH] demoted peer=${peer.id.value.take(8)} " +
            s"consecutiveTimeouts=${peerHealth.consecutiveTimeouts(peer.id.value)} " +
            s"level=${peerHealth.level(peer.id.value)} penalty=${penalty.toSeconds}s " +
            s"penalised=${peerHealth.penalisedCount(System.currentTimeMillis())}/${knownAvailablePeers.size}"
        )
      }
      adjustResponseBytesOnFailure(peer, "request timeout")

      batchTasks.foreach { task =>
        val key = (task.accountHash, task.next)
        if !pendingTaskKeys.contains(key) then
          pendingTaskKeys += key
          tasks.enqueue(task.copy(pending = false))
      }

      consecutiveTaskFailures += 1
      log.debug(s"Storage consecutive task failures: $consecutiveTaskFailures/$maxConsecutiveTaskFailures")
      if consecutiveTaskFailures >= maxConsecutiveTaskFailures then
        log.warn(
          s"[STORAGE-FORCE-COMPLETE] $consecutiveTaskFailures consecutive task failures — " +
            s"SNAP peers not serving storage data. Sending ForceCompleteStorage. " +
            s"Missing storage deferred to healing phase."
        )
        self ! ForceCompleteStorage
    }
    // Re-dispatch re-queued tasks to any known available peer that isn't stateless or on cooldown.
    tryRedispatchPendingTasks()

  /** Dispatch up to maxInFlightPerPeer requests to a single peer (pipelining). */
  private def dispatchIfPossible(peer: Peer): Unit =
    var inflight = inFlightForPeer(peer)
    var continue = true
    val peerLimit = perPeerInFlightLimit(peer)
    while continue && tasks.nonEmpty && inflight < peerLimit && activeTasks.size < maxInFlightRequests do
      requestNextRanges(peer) match
        case Some(_) => inflight += 1
        case None    => continue = false

  // Periodic state-dump cadence — at most one INFO snapshot every 30 seconds. tryRedispatchPendingTasks
  // can be called many times per second under heavy storage flow (each AddStorageTasks call
  // chains into it), so modulo-based throttling produced log floods that overflowed the live
  // monitor pipe; time-based throttling is robust against call-rate spikes.
  private var lastStateLogMs: Long = 0L
  private val StateLogIntervalMs: Long = 30_000L

  private def tryRedispatchPendingTasks(): Unit =
    if tasks.nonEmpty && !isPostRefreshCooldownActive && !pivotRefreshRequested then redispatchEligible()

  private def redispatchEligible(): Unit =
    val now = System.currentTimeMillis()
    var eligiblePeers = knownAvailablePeers
      .filterNot(p => isPeerStateless(p) || isPeerCoolingDown(p) || peerHealth.isPenalised(p.id.value, now))
      .toList
    if eligiblePeers.isEmpty then
      StorageRangeCoordinator.selectFloorPeer(
        knownAvailablePeers.filterNot(isPeerStateless).toList,
        isCooling = isPeerCoolingDown,
        isPenalised = p => peerHealth.isPenalised(p.id.value, now),
        cooldownUntilMs = p => peerCooldownUntilMs.getOrElse(p.id.value, 0L),
        penaltyUntilMs = p => peerHealth.penaltyUntilMs(p.id.value)
      ) match
        case Some(StorageRangeCoordinator.FloorPick.Revive(peer)) =>
          peerCooldownUntilMs.remove(peer.id.value)
          log.info(
            s"[STORAGE-FLOOR] All servable peers were cooling and none eligible — " +
              s"reviving ${peer.id.value.take(8)} to keep the pipe fed (peer-scarce floor)"
          )
          eligiblePeers = List(peer)
        case Some(StorageRangeCoordinator.FloorPick.LastResortProbe(peer)) =>
          // Every servable peer is penalised for repeated timeouts. Probe the one whose penalty lapses first, without
          // lifting its penalty and with a single request slot (perPeerInFlightLimit), so a dead peer costs at most one
          // timeout slot at a time instead of the full per-peer budget.
          if now - lastPenalisedFloorLogMs >= StateLogIntervalMs then
            lastPenalisedFloorLogMs = now
            log.info(
              s"[STORAGE-FLOOR] Only penalised peers remain (${peerHealth.penalisedCount(now)}) — " +
                s"probing ${peer.id.value.take(8)} with a single request slot"
            )
          eligiblePeers = List(peer)
        case None => ()
    val shouldLog = now - lastStateLogMs >= StateLogIntervalMs
    if shouldLog then
      lastStateLogMs = now
      val pctInt = (progress * 100).toInt
      log.info(
        s"[STORAGE-STATE] $pctInt% | pending=${tasks.size} active=${activeTasks.size} " +
          s"completed=$completedTaskCount " +
          s"workers-known=${knownAvailablePeers.size} stateless=${statelessPeers.size} " +
          s"cooling=${knownAvailablePeers.count(isPeerCoolingDown)} eligible=${eligiblePeers.size} " +
          s"strikes=${emptyResponseStrikes.size} penalised=${peerHealth.penalisedCount(now)} " +
          s"root=${stateRoot.take(4).toHex}"
      )
      if noMoreTasksExpected then
        val activeCount = activeTasks.values.map(_._2.size).sum
        val total = completedTaskCount + activeCount + tasks.size
        val (newM, crossed) =
          com.chipprbots.ethereum.blockchain.sync.ProgressMilestones
            .crossed(completedTaskCount, total, storageMilestonePct)
        storageMilestonePct = newM
        crossed.foreach { m =>
          log.info(s"[SNAP-PROGRESS] STORAGE-COORD MILESTONE $m% — $completedTaskCount / $total tasks complete")
        }
    if eligiblePeers.isEmpty then
      if shouldLog then
        log.info(
          s"[STORAGE-REDISPATCH] No eligible peers — ${knownAvailablePeers.size} known, " +
            s"${statelessPeers.size} stateless, " +
            s"${knownAvailablePeers.count(isPeerCoolingDown)} cooling. pending: ${tasks.size}"
        )
      if tasks.nonEmpty && activeTasks.isEmpty then
        storageIdleChecks += 1
        if storageIdleChecks >= storageIdleEscapeThreshold then
          log.warn(
            s"[STORAGE] No eligible peers for $storageIdleChecks consecutive redispatch checks with " +
              s"${tasks.size} pending tasks and no active requests — requesting pivot refresh"
          )
          storageIdleChecks = 0
          maybeRequestPivotRefresh()
    else
      storageIdleChecks = 0
      for peer <- eligiblePeers if tasks.nonEmpty do dispatchIfPossible(peer)

  private def progress: Double =
    val activeCount = activeTasks.values.map(_._2.size).sum
    val total = completedTaskCount + activeCount + tasks.size
    if total == 0 then 1.0
    else completedTaskCount.toDouble / total

  private def isComplete: Boolean =
    noMoreTasksExpected && tasks.isEmpty && activeTasks.isEmpty &&
      pendingAccountTries.isEmpty &&
      pendingFlatBatchAccounts.isEmpty && inFlightFlatBatches == 0

  /** Update contract completion counts and send progress to controller. The counter is incremented exactly once per
    * account at the "no continuation needed" branch of `processStorageRanges`; we just broadcast its current value,
    * avoiding the previous O(N) rebuild over completedTasks that ran on every progress check.
    */
  private var lastReportedCompletedAccountCount: Long = 0L
  private def updateContractProgress(): Unit =
    if totalStorageContracts > 0 && completedAccountCount != lastReportedCompletedAccountCount then
      lastReportedCompletedAccountCount = completedAccountCount
      snapSyncController ! SNAPSyncController.ProgressStorageContracts(
        completedAccountCount.toInt,
        totalStorageContracts
      )

  private def isPeerCoolingDown(peer: Peer): Boolean =
    peerCooldownUntilMs.get(peer.id.value).exists(_ > System.currentTimeMillis())

  /** A peer demoted for repeated timeouts gets one request slot until it answers again; everyone else gets the budget.
    */
  private def perPeerInFlightLimit(peer: Peer): Int =
    if peerHealth.isOnProbation(peer.id.value) then maxInFlightPerPeer.min(1) else maxInFlightPerPeer

  private def recordPeerCooldown(peer: Peer, reason: String): Unit =
    val until = System.currentTimeMillis() + peerCooldownDefault.toMillis
    peerCooldownUntilMs.put(peer.id.value, until)
    log.debug(s"Cooling down peer ${peer.id.value} for ${peerCooldownDefault.toSeconds}s: $reason")

  /** Split a large-storage account's remaining slot range into parallel subtasks.
    *
    * Called on first continuation detection — when a SNAP response has a proof but doesn't cover `task.last`,
    * indicating the contract has more slots than fit in one 512 KB packet. Creates N parallel StorageTask objects
    * covering consecutive disjoint ranges. Mirrors go-ethereum's subtask creation in `assignStorageTasks()`
    * (sync.go:2118-2197) and `newHashRange()` (sync.go:2144-2193).
    *
    * @param task
    *   The original task that triggered the continuation (covers [task.next, task.last])
    * @param lastSlotReceived
    *   The last slot hash in the partial response (split starts at `incrementHash32(lastSlotReceived)`)
    * @return
    *   Sequence of StorageTask objects covering [lastSlotReceived+1, task.last] in equal segments
    */
  private def createStorageSubTasks(task: StorageTask, lastSlotReceived: ByteString): Seq[StorageTask] =
    val chunks = storageConcurrency
    StorageTask.createSubTasks(
      accountHash = task.accountHash,
      storageRoot = task.storageRoot,
      from = StorageTask.incrementHash32(lastSlotReceived),
      to = task.last,
      numChunks = chunks
    )

  /** Record completion of one subtask and increment completedAccountCount when all done.
    *
    * Mirrors go-ethereum's `cleanStorageTasks()` (sync.go:982-1015) pend-decrement logic.
    *
    * @param accountHash
    *   Hash of the account whose subtask just completed
    * @return
    *   `true` iff this was the account's LAST outstanding subtask (or the account was never tracked as subtasked at
    *   all) — i.e. the account as a whole is now fully downloaded. Callers use this to gate `commitAccountTrie`:
    *   committing on a single chunk's own completion (instead of the account's) would remove/finalise the shared trie
    *   while sibling chunks covering higher sub-ranges are still in flight, corrupting the on-disk path-keyed nodes for
    *   this account.
    */
  private[actors] def recordSubtaskCompletion(accountHash: ByteString): Boolean =
    accountSubtaskCounters.get(accountHash) match
      case None =>
        completedAccountCount += 1
        true
      case Some((total, done)) =>
        val newDone = done + 1
        if newDone >= total then
          completedAccountCount += 1
          accountSubtaskCounters.remove(accountHash)
          log.debug(
            s"All $total storage subtasks complete for account ${accountHash.take(4).toHex} — " +
              s"advancing completedAccountCount to $completedAccountCount"
          )
          true
        else
          accountSubtaskCounters(accountHash) = (total, newDone)
          log.debug(
            s"Storage subtask $newDone/$total done for account ${accountHash.take(4).toHex}"
          )
          false

object StorageRangeCoordinator:

  /** Outcome of the eligible-set floor when no peer is dispatchable. */
  private[actors] enum FloorPick[+P]:
    /** A peer excluded only by its short cooldown: lift the cooldown and dispatch normally. */
    case Revive(peer: P)

    /** Every servable peer is penalised for repeated timeouts: probe one with a single slot, penalty kept. */
    case LastResortProbe(peer: P)

  /** Eligible-set floor (peer-retention): when every non-stateless peer is excluded, pick one rather than stalling at
    * zero dispatchable peers.
    *
    * A peer penalised for repeated timeouts is never revived while any other servable peer exists: before this rule the
    * floor picked the soonest-to-expire cooldown, which is exactly the peer that just timed out, so a dead peer was
    * revived over and over (Sepolia 2026-10-07: 230 revivals of a peer with 0/386 answers). Only when the penalised
    * peers are all that is left is one probed, the one whose penalty lapses first, as [[FloorPick.LastResortProbe]].
    *
    * @param servable
    *   known peers that are not stateless for the current root
    */
  private[actors] def selectFloorPeer[P](
      servable: List[P],
      isCooling: P => Boolean,
      isPenalised: P => Boolean,
      cooldownUntilMs: P => Long,
      penaltyUntilMs: P => Long
  ): Option[FloorPick[P]] =
    val (penalised, healthy) = servable.partition(isPenalised)
    healthy.filter(isCooling).sortBy(cooldownUntilMs).headOption match
      case Some(peer) => Some(FloorPick.Revive(peer))
      case None if healthy.isEmpty =>
        penalised.sortBy(penaltyUntilMs).headOption.map(FloorPick.LastResortProbe(_))
      case None => None

  /** Command protocol for the Typed coordinator (Group S3). All subtypes live in this companion so the trait is sealed
    * — Scala 3 file-scope sealing enables exhaustive match checking at every call site.
    */
  sealed trait Command

  // ── Coordinator Commands (SSC-sent and external) ───────────────────────────

  case class StartStorageRangeSync(stateRoot: ByteString) extends Command
  case class AddStorageTasks(tasks: Seq[StorageTask]) extends Command
  case class AddStorageTask(task: StorageTask) extends Command
  case class StoragePeerAvailable(peer: Peer) extends Command
  case class StoragePeerUnavailable(peerId: String) extends Command
  case class StorageTaskComplete(requestId: BigInt, result: Either[String, Int]) extends Command
  case class StorageTaskFailed(requestId: BigInt, reason: String) extends Command
  case class StorageGetProgress(replyTo: org.apache.pekko.actor.typed.ActorRef[SyncStatistics]) extends Command
  case object StorageCheckCompletion extends Command

  /** Sent by SNAPSyncController when a fresher pivot has been selected during storage sync. Coordinator updates state
    * root and clears per-peer adaptive state.
    */
  case class StoragePivotRefreshed(newStateRoot: ByteString) extends Command

  /** Signal that no more storage tasks will arrive (all accounts downloaded). Coordinator may now report completion
    * when pending + active tasks drain.
    */
  case object NoMoreStorageTasks extends Command

  /** Sent by SNAPSyncController when storage sync has stagnated and should promote to healing. Coordinator flushes
    * deferred writes and reports StorageRangeSyncForceCompleted.
    */
  case object ForceCompleteStorage extends Command

  /** Dynamically adjust per-peer concurrency budget. Sent by SNAPSyncController at phase transitions (Geth-aligned:
    * total 5 requests per peer across all coordinators).
    */
  case class UpdateMaxInFlightPerPeer(newLimit: Int) extends Command

  /** An aggregated flat-slot batch (small-contract writes) finished committing on the storage-writer dispatcher.
    * `forStateRoot` lets the coordinator drop completion messages from a superseded generation. `seq` is the batch's
    * sequence number (completion markers wait on it); 0 = unnumbered.
    */
  private[actors] case class FlatBatchFlushComplete(
      forStateRoot: ByteString,
      entryCount: Int,
      elapsedMs: Long,
      seq: Long = 0L
  ) extends Command

  /** Periodic: submit buffered flat slots that staged completion markers wait on, and every marker that may ride. */
  private[actors] case object FlushStorageDoneMarkers extends Command
  private[actors] val DoneMarkerFlushInterval: FiniteDuration = 10.seconds

  /** Aggregated flat-slot batch failed to commit. Healing phase is expected to re-fetch the missing slots. */
  private[actors] case class FlatBatchFlushFailed(
      forStateRoot: ByteString,
      entryCount: Int,
      error: String
  ) extends Command

  // ── Worker message protocol ────────────────────────────────────────────────

  sealed trait WorkerMessage
  case class FetchStorageRanges(task: StorageTask, peer: Peer) extends WorkerMessage
  // Sent to the coordinator (SSC forwards it via the Classic `!`), so it is also a Command.
  case class StorageRangesResponseMsg(response: StorageRanges) extends WorkerMessage with Command
  case class StorageRequestTimeout(requestId: BigInt) extends WorkerMessage

  /** The request tracker's timeout for `requestId` fired; delivered through the mailbox. */
  private[actors] case class StorageRequestTimedOut(requestId: BigInt) extends Command
  case object StorageCheckIdle extends WorkerMessage

  /** Behavior factory (Group S3). SSC and StorageRecoveryActor are still Classic / Classic-spawned at S3 time, so they
    * spawn this via `PropsAdapter` and hold a Classic `ActorRef`; their `!` routes Command-typed messages. SRC spawns
    * no child workers — it dispatches GetStorageRanges directly to `networkPeerManager` (still Classic).
    */
  def apply(
      stateRoot: ByteString,
      networkPeerManager: org.apache.pekko.actor.typed.ActorRef[NetworkPeerManagerActor.Command],
      requestTracker: SNAPRequestTracker,
      mptStorage: MptStorage,
      flatSlotStorage: FlatSlotStorage,
      maxAccountsPerBatch: Int,
      maxInFlightRequests: Int,
      requestTimeout: FiniteDuration,
      snapSyncController: org.apache.pekko.actor.typed.ActorRef[SNAPSyncController.Command],
      initialMaxInFlightPerPeer: Int = 5,
      initialResponseBytes: Int = 1048576,
      minResponseBytes: Int = 131072,
      deferredMerkleization: Boolean = true,
      flatBatchEntryThreshold: Int = 1000,
      flatBatchEcOverride: Option[ExecutionContext] = None,
      backpressureHighWatermark: Int = 100000,
      backpressureLowWatermark: Int = 50000,
      maxConcurrentStorageAccounts: Int = 256,
      snapProgressStorage: Option[SnapSyncProgressStorage] = None,
      storageScheme: StorageScheme = StorageScheme.Hash,
      pathNodeStorage: Option[PathNodeStorage] = None,
      recordStorageDone: Boolean = false,
      intakeBudget: Option[SnapIntakeBudget] = None
  ): Behavior[Command] =
    Behaviors.setup { context =>
      Behaviors.withTimers { timers =>
        new StorageRangeCoordinatorImpl(
          context,
          timers,
          initialStateRoot = stateRoot,
          networkPeerManager = networkPeerManager,
          requestTracker = requestTracker,
          mptStorage = mptStorage,
          flatSlotStorage = flatSlotStorage,
          maxAccountsPerBatch = maxAccountsPerBatch,
          maxInFlightRequests = maxInFlightRequests,
          requestTimeout = requestTimeout,
          snapSyncController = snapSyncController,
          initialMaxInFlightPerPeer = initialMaxInFlightPerPeer,
          configInitialResponseBytes = initialResponseBytes,
          configMinResponseBytes = minResponseBytes,
          deferredMerkleization = deferredMerkleization,
          flatBatchEntryThreshold = flatBatchEntryThreshold,
          flatBatchEcOverride = flatBatchEcOverride,
          backpressureHighWatermark = backpressureHighWatermark,
          backpressureLowWatermark = backpressureLowWatermark,
          maxConcurrentStorageAccounts = maxConcurrentStorageAccounts,
          snapProgressStorage = snapProgressStorage,
          storageScheme = storageScheme,
          pathNodeStorage = pathNodeStorage,
          recordStorageDone = recordStorageDone,
          intakeBudget = intakeBudget
        ).start()
      }
    }

  /** Sync statistics for storage range download */
  case class SyncStatistics(
      slotsDownloaded: Long,
      bytesDownloaded: Long,
      tasksCompleted: Int,
      tasksActive: Int,
      tasksPending: Int,
      elapsedTimeMs: Long,
      progress: Double,
      staleRootFailureEvents: Long = 0L
  ):
    def throughputSlotsPerSec: Double =
      if elapsedTimeMs > 0 then slotsDownloaded.toDouble / (elapsedTimeMs / 1000.0)
      else 0.0

    def throughputBytesPerSec: Double =
      if elapsedTimeMs > 0 then bytesDownloaded.toDouble / (elapsedTimeMs / 1000.0)
      else 0.0

    override def toString: String =
      f"Progress: ${progress * 100}%.1f%%, Slots: $slotsDownloaded, " +
        f"Bytes: ${bytesDownloaded / 1024}KB, Tasks: $tasksCompleted done, $tasksActive active, $tasksPending pending, " +
        f"Speed: ${throughputSlotsPerSec}%.1f slots/s, ${throughputBytesPerSec / 1024}%.1f KB/s"
