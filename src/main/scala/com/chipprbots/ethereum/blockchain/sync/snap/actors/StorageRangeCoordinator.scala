package com.chipprbots.ethereum.blockchain.sync.snap.actors

import org.apache.pekko.actor.{Actor, ActorLogging, ActorRef, Props, SupervisorStrategy, OneForOneStrategy}
import org.apache.pekko.actor.SupervisorStrategy._
import org.apache.pekko.util.ByteString

import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.math.Ordered.orderingToOrdered

import com.chipprbots.ethereum.blockchain.sync.snap._
import com.chipprbots.ethereum.db.dataSource.DataSourceBatchUpdate
import com.chipprbots.ethereum.db.storage.{FlatSlotStorage, MptStorage}
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.p2p.MessageSerializable
import com.chipprbots.ethereum.network.p2p.messages.SNAP._
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
class StorageRangeCoordinator(
    initialStateRoot: ByteString,
    networkPeerManager: ActorRef,
    requestTracker: SNAPRequestTracker,
    mptStorage: MptStorage,
    flatSlotStorage: FlatSlotStorage,
    maxAccountsPerBatch: Int,
    maxInFlightRequests: Int,
    requestTimeout: FiniteDuration,
    snapSyncController: ActorRef,
    initialMaxInFlightPerPeer: Int = 5,
    configInitialResponseBytes: Int = 1048576,
    configMinResponseBytes: Int = 131072,
    deferredMerkleization: Boolean = true,
    flatBatchEntryThreshold: Int = 1000,
    flatBatchEcOverride: Option[ExecutionContext] = None,
    /** Legacy toggle retained for source compatibility — the streaming `SnapHashTrie` path is now unconditional, with
      * `deferredMerkleization` controlling whether per-contract tries are built at all. The field exists so callers
      * (`SNAPSyncController`, tests) keep compiling without churn while the storage path is finalised; the value itself
      * is no longer read.
      */
    @annotation.unused useStackTrie: Boolean = false,
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
    maxConcurrentStorageAccounts: Int = 256
) extends Actor
    with ActorLogging {

  import Messages._

  // Mutable state root — updated in-place when the controller refreshes the pivot.
  private var stateRoot: ByteString = initialStateRoot

  // Per-peer concurrency budget — dynamically adjusted by SNAPSyncController via UpdateMaxInFlightPerPeer.
  private var maxInFlightPerPeer: Int = initialMaxInFlightPerPeer

  // Task management
  private[actors] val tasks = mutable.Queue[StorageTask]()
  // Dedup gate: (accountHash, next) uniquely identifies a pending task. Prevents duplicate
  // enqueues when concurrent timeout re-queues overlap (two timeouts for the same batch).
  private val pendingTaskKeys = mutable.Set[(ByteString, ByteString)]()
  private val activeTasks =
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
  private var completedAccountCount: Long = 0L

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

  // Peer cooldown (best-effort): used for transient errors (timeouts, verification failures).
  // This is separate from stateless peer detection — cooldowns are short and per-error-type.
  // 5 s (was 10 s) — storage shares the peer pool with the account coordinator and we
  // observed `pending=N active=0 workers-known=10 eligible=8-11` snapshots where most of
  // the eligible peers were parked here. Halving the cooldown keeps storage dispatch
  // closer to the request budget the SNAP server can actually sustain.
  private val peerCooldownUntilMs = mutable.Map[String, Long]()
  private val peerCooldownDefault = 5.seconds

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

  private def markPeerStateless(peer: Peer): Unit = {
    val id = peer.id.value
    // Already-confirmed peers stay confirmed; extra strikes are noise.
    if (statelessPeers.contains(id)) return

    val priorStrikes = emptyResponseStrikes.getOrElse(id, 0)
    val strikes = priorStrikes + 1
    emptyResponseStrikes(id) = strikes

    if (strikes < EmptyResponseStrikeThreshold) {
      log.info(
        s"Peer $id empty-storage strike $strikes/$EmptyResponseStrikeThreshold for root " +
          s"${stateRoot.take(4).toHex}. Still eligible for dispatch."
      )
      return
    }

    val wasStateless = statelessPeers.contains(id)
    statelessPeers.add(id)
    if (!wasStateless) com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncMetrics.incrementStatelessPeerConfirmed()
    log.info(
      s"Peer $id marked stateless after $strikes consecutive empty storage responses for root " +
        s"${stateRoot.take(4).toHex} (${statelessPeers.size}/${knownAvailablePeers.size} stateless)"
    )
    maybeRequestPivotRefresh()
  }

  /** Reset strike counter when peer produces a useful response. Cheap to over-invoke. */
  private def recordPeerSuccess(peerId: String): Unit =
    emptyResponseStrikes.remove(peerId)

  private def maybeRequestPivotRefresh(): Unit = {
    if (pivotRefreshRequested) return
    val allStateless = knownAvailablePeers.nonEmpty &&
      knownAvailablePeers.forall(p => statelessPeers.contains(p.id.value))

    // Secondary trigger: tasks pending but no dispatch/response activity for 2 minutes.
    // Catches "ghost" peers that remain in knownAvailablePeers after disconnecting
    // without being marked stateless (preventing allStateless from ever being true).
    // Note: activeTasks may be non-empty if requests to ghost peers never time out
    // (SNAPRequestTracker timeouts are poll-based, not scheduled), so we check
    // activity time regardless of in-flight count.
    val now = System.currentTimeMillis()
    val dispatchStalled = !allStateless && tasks.nonEmpty && maxInFlightPerPeer > 0 &&
      (now - lastDispatchOrResponseMs) > noActivityTimeoutMs

    if (dispatchStalled) {
      log.warning(
        s"Storage dispatch stalled: ${tasks.size} pending, ${activeTasks.size} active, " +
          s"no activity for ${(now - lastDispatchOrResponseMs) / 1000}s. " +
          s"Peers: ${knownAvailablePeers.size} known, ${statelessPeers.size} stateless. " +
          s"Marking remaining peers as stateless (likely disconnected)."
      )
      knownAvailablePeers.foreach(p => statelessPeers.add(p.id.value))
      // Re-queue any stale in-flight tasks from ghost peers
      if (activeTasks.nonEmpty) {
        val staleCount = activeTasks.size
        activeTasks.values.foreach { case (_, batchTasks, _) =>
          batchTasks.foreach { task =>
            task.pending = false
            tasks.enqueue(task)
          }
        }
        activeTasks.clear()
        log.info(s"Re-queued $staleCount stale in-flight requests from ghost peers")
      }
    }

    if (allStateless || dispatchStalled) {
      val backoffMs = math.min(
        maxRefreshIntervalMs,
        minRefreshIntervalMs * (1L << math.min(consecutiveUnproductiveRefreshes, 3))
      )
      val elapsed = now - lastPivotRefreshTimeMs
      if (lastPivotRefreshTimeMs > 0 && elapsed < backoffMs) {
        val remainingMs = backoffMs - elapsed
        log.info(
          s"All peers stateless but backing off pivot refresh " +
            s"(${elapsed / 1000}s / ${backoffMs / 1000}s, attempt ${consecutiveUnproductiveRefreshes + 1}). " +
            s"Retrying in ${remainingMs / 1000}s."
        )
        // Schedule a retry after the backoff period
        import context.dispatcher
        context.system.scheduler.scheduleOnce(remainingMs.millis) {
          self ! StorageCheckCompletion // triggers re-evaluation
        }
        return
      }

      pivotRefreshRequested = true
      consecutiveUnproductiveRefreshes += 1
      lastPivotRefreshTimeMs = now
      log.warning(
        s"All ${statelessPeers.size} known peers are stateless for root ${stateRoot.take(4).toHex}. " +
          s"Requesting pivot refresh from controller (attempt $consecutiveUnproductiveRefreshes)."
      )
      snapSyncController ! SNAPSyncController.PivotStateUnservable(
        rootHash = stateRoot,
        reason = "all peers stateless for StorageRange root",
        consecutiveEmptyResponses = statelessPeers.size
      )
    }
  }

  // Per-peer adaptive batch size: tracks which peers support multi-account batching.
  // Starts at maxAccountsPerBatch, ratchets down on empty batched responses, scales back up
  // on successful packed responses. This allows recovery from transient issues rather than
  // permanently degrading to batch=1 for the lifetime of the sync.
  private val peerBatchSize = mutable.Map.empty[String, Int]
  private val peerBatchSuccessStreak = mutable.Map.empty[String, Int]
  private val batchRecoveryStreak = 3 // Consecutive successes before scaling up

  private def batchSizeFor(peer: Peer): Int =
    peerBatchSize.getOrElseUpdate(peer.id.value, maxAccountsPerBatch)

  private def reduceBatchSize(peer: Peer): Unit = {
    peerBatchSize.update(peer.id.value, 1)
    peerBatchSuccessStreak.remove(peer.id.value)
  }

  /** Scale batch size back up after consecutive successful packed responses. Doubles the batch size per peer, capped at
    * maxAccountsPerBatch.
    */
  private def maybeIncreaseBatchSize(peer: Peer, servedCount: Int, requestedCount: Int): Unit =
    // Only count as "packed" if the response served most of the requested accounts
    if (requestedCount > 1 && servedCount >= requestedCount / 2) {
      val streak = peerBatchSuccessStreak.getOrElse(peer.id.value, 0) + 1
      peerBatchSuccessStreak.update(peer.id.value, streak)
      if (streak >= batchRecoveryStreak) {
        val current = batchSizeFor(peer)
        val next = math.min(current * 2, maxAccountsPerBatch)
        if (next > current) {
          peerBatchSize.update(peer.id.value, next)
          peerBatchSuccessStreak.update(peer.id.value, 0)
          log.info(
            s"Peer ${peer.id.value} batch size increased: $current -> $next (after $streak consecutive successes)"
          )
        }
      }
    }

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

  // Sentinel: when true, no more AddStorageTasks will arrive (all accounts downloaded).
  // Completion is only reported after this is set AND pending+active tasks drain.
  // Geth-aligned: coordinators run from start, tasks arrive inline during account download.
  private var noMoreTasksExpected: Boolean = false

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
    if (requested > 0 && received * 10 >= requested * 9 && requested < maxResponseBytes) {
      val next = (requested.toDouble * increaseFactor).toLong
      peerResponseBytesTarget.update(peer.id.value, BigInt(next).min(maxResponseBytes))
    }

  private def adjustResponseBytesOnFailure(peer: Peer, reason: String): Unit = {
    val cur = responseBytesTargetFor(peer)
    val next = (cur.toDouble * decreaseFactor).toLong
    peerResponseBytesTarget.update(peer.id.value, BigInt(next).max(minResponseBytes))
    log.debug(
      s"Reducing storage responseBytes target for peer ${peer.id.value}: $cur -> ${peerResponseBytesTarget(peer.id.value)} ($reason)"
    )
  }

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
  private[actors] val pendingAccountTries: mutable.Map[ByteString, SnapHashTrie] = mutable.Map.empty

  /** Get-or-create the per-account `SnapHashTrie`. Each contract's trie streams emitted nodes through
    * `mptStorage.storeRawNodes`, which routes via `FastSyncNodeStorage` to pick up pivot-block-number tagging for
    * pruning. Modelled on `AccountRangeCoordinator.getOrCreateTaskStackTrie`.
    */
  private def getOrCreateAccountTrie(accountHash: ByteString): SnapHashTrie =
    pendingAccountTries.getOrElseUpdate(
      accountHash,
      new SnapHashTrie(batch => mptStorage.storeRawNodes(batch))
    )

  /** Commit a fully-downloaded contract trie. Compares the computed root against the task's claimed `storageRoot`;
    * mismatches log a warning but are otherwise accepted — healing reconciles. Returns the contract's storage root.
    */
  private def commitAccountTrie(accountHash: ByteString, claimedRoot: ByteString): ByteString =
    pendingAccountTries.remove(accountHash) match {
      case Some(trie) =>
        val computedRoot = trie.commit()
        if (computedRoot != claimedRoot) {
          log.warning(
            s"Storage root mismatch for account ${accountHash.take(4).toHex}: " +
              s"computed=${computedRoot.take(4).toHex} claimed=${claimedRoot.take(4).toHex} — healing will reconcile"
          )
        }
        com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncMetrics
          .setStoragePendingTries(pendingAccountTries.size.toLong)
        computedRoot
      case None =>
        // First/only response for an account with no slots, or proof-of-absence path —
        // no trie was ever created. Caller still wants a non-null root reference.
        claimedRoot
    }

  /** Discard a partial trie when the account is aborted (pivot refresh, max-empty skip, force-complete).
    * Already-flushed content-addressed nodes stay on disk; only the in-memory stack-trie state is dropped.
    */
  private def resetAccountTrie(accountHash: ByteString): Unit =
    pendingAccountTries.remove(accountHash).foreach { trie =>
      trie.reset()
    }

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

  /** Dedicated dispatcher for flat-batch RocksDB commits. Tests can inject their own ExecutionContext to keep timing
    * deterministic; production looks up `storage-writer-dispatcher` from the actor system.
    */
  private val flatBatchEc: ExecutionContext =
    flatBatchEcOverride.getOrElse(context.system.dispatchers.lookup("storage-writer-dispatcher"))

  /** Append a per-response chunk of verified slots to the flat-slot accumulator. The streaming storage-trie path
    * commits the trie incrementally inside `SnapHashTrie`; flat-slot writes remain batched (sorted within each chunk)
    * and are flushed to RocksDB off-actor once the accumulator hits `flatBatchEntryThreshold` or the actor stops.
    */
  private[actors] def stageFlatSlotChunk(
      accountHash: ByteString,
      slots: Seq[(ByteString, ByteString)]
  ): Unit = {
    if (slots.isEmpty) return
    val sorted = slots.sortBy(_._1)(ByteStringOrdering)
    pendingFlatBatchAccounts += ((accountHash, sorted))
    pendingFlatBatchEntries += sorted.size
    if (pendingFlatBatchEntries >= flatBatchEntryThreshold) {
      flushPendingFlatBatch()
    }
  }

  /** Hand the current accumulator off to the storage-writer dispatcher and reset it. The Future builds the combined
    * `DataSourceBatchUpdate` and commits it in one RocksDB write batch, then notifies the actor with
    * `FlatBatchFlushComplete` (or `FlatBatchFlushFailed`). The completion message carries `forStateRoot` so the actor
    * can drop bookkeeping for batches that pre-date a pivot refresh.
    */
  private def flushPendingFlatBatch(): Unit = {
    if (pendingFlatBatchAccounts.isEmpty) return
    val batchAccounts = pendingFlatBatchAccounts.toList // immutable snapshot
    val entries = pendingFlatBatchEntries
    val forStateRoot = stateRoot
    pendingFlatBatchAccounts.clear()
    pendingFlatBatchEntries = 0
    inFlightFlatBatches += 1

    val selfRef = self
    val storage = flatSlotStorage // capture for Future
    val ec = flatBatchEc
    import scala.concurrent.{Future, blocking}
    Future {
      blocking {
        val startMs = System.currentTimeMillis()
        var combined: DataSourceBatchUpdate = storage.emptyBatchUpdate
        batchAccounts.foreach { case (accountHash, slots) =>
          combined = combined.and(storage.putSlotsBatch(accountHash, slots))
        }
        combined.commit()
        System.currentTimeMillis() - startMs
      }
    }(ec).onComplete {
      case scala.util.Success(elapsedMs) =>
        selfRef ! FlatBatchFlushComplete(forStateRoot, entries, elapsedMs)
      case scala.util.Failure(e) =>
        selfRef ! FlatBatchFlushFailed(forStateRoot, entries, e.getMessage)
    }(ec)
  }

  /** Aggregate-counter sink for completed StorageTask objects. Previously this appended into an unbounded
    * `mutable.ArrayBuffer[StorageTask]` (one of the leak vectors behind the May 13 sepolia OOM at ~22M completed
    * tasks). All downstream consumers — progress %, SyncStatistics.tasksCompleted, the completion check — only ever
    * read the count, never the task data itself.
    */
  private def recordCompletedTask(task: StorageTask): Unit = {
    val _ = task // explicitly unused; kept in the signature to make call-site intent unambiguous
    completedTaskCount += 1L
  }

  /** Emit a StorageQueuePressure transition if the pending-task queue depth has just crossed a watermark. Called after
    * every enqueue and dequeue. Forwarded to AccountRangeCoordinator via SNAPSyncController so account workers stop
    * producing new storage tasks during back-pressure.
    */
  private def notifyBackpressureIfChanged(): Unit = {
    val pending = tasks.size
    com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncMetrics.setStorageQueueDepth(pending.toLong)
    if (!backpressureActive && pending >= backpressureHighWatermark) {
      backpressureActive = true
      com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncMetrics.setStorageBackpressure(true)
      log.info(
        s"Storage queue back-pressure ENGAGED at $pending pending tasks (high-water=$backpressureHighWatermark). " +
          s"Signalling AccountRangeCoordinator to pause dispatch."
      )
      snapSyncController ! SNAPSyncController.StorageBackpressureChanged(paused = true)
    } else if (backpressureActive && pending <= backpressureLowWatermark) {
      backpressureActive = false
      com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncMetrics.setStorageBackpressure(false)
      log.info(
        s"Storage queue back-pressure RELEASED at $pending pending tasks (low-water=$backpressureLowWatermark). " +
          s"Signalling AccountRangeCoordinator to resume dispatch."
      )
      snapSyncController ! SNAPSyncController.StorageBackpressureChanged(paused = false)
    }
  }

  /** ByteString ordering for sorted insertion — compares bytes lexicographically. */
  private object ByteStringOrdering extends Ordering[ByteString] {
    def compare(a: ByteString, b: ByteString): Int = {
      val len = math.min(a.length, b.length)
      var i = 0
      while (i < len) {
        val diff = (a(i) & 0xff) - (b(i) & 0xff)
        if (diff != 0) return diff
        i += 1
      }
      a.length - b.length
    }
  }

  /** Discard any in-memory streaming tries and flush the tail of accumulated flat-slot writes when the actor stops. */
  override def postStop(): Unit = {
    // Discard any in-memory streaming tries — already-flushed nodes stay on disk
    // (content-addressed; healing reconciles).
    if (pendingAccountTries.nonEmpty) {
      log.info(s"postStop: discarding ${pendingAccountTries.size} in-flight per-account storage tries")
      pendingAccountTries.values.foreach(_.reset())
      pendingAccountTries.clear()
      com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncMetrics.setStoragePendingTries(0L)
    }
    // Best-effort: flush any tail of accumulated flat-slot entries synchronously here so we
    // don't lose data when the actor terminates (force-complete, restart).
    if (pendingFlatBatchAccounts.nonEmpty) {
      try {
        var combined: DataSourceBatchUpdate = flatSlotStorage.emptyBatchUpdate
        pendingFlatBatchAccounts.foreach { case (accountHash, slots) =>
          combined = combined.and(flatSlotStorage.putSlotsBatch(accountHash, slots))
        }
        combined.commit()
        log.info(s"postStop: flushed final ${pendingFlatBatchEntries} flat slot entries")
      } catch {
        case e: Exception =>
          log.error(s"postStop: failed to flush final flat batch: ${e.getMessage}")
      }
      pendingFlatBatchAccounts.clear()
      pendingFlatBatchEntries = 0
    }
    super.postStop()
  }

  // Storage management.
  // MerkleProofVerifier is constructed inline per response (see verifyStorageRange call).
  // It was previously cached per storage root in a mutable.Map cleared only on pivot
  // refresh — but the verifier holds just a 32-byte root + Logger, so the cache saved
  // nothing and leaked one entry per distinct contract storage root (~0.3–0.5GiB of dead
  // heap at ETC's 13M+ contracts). Inline construction makes the heap O(in-flight), not
  // O(contracts-seen-since-last-pivot).

  override def preStart(): Unit = {
    log.info(s"StorageRangeCoordinator starting (concurrency=$maxInFlightRequests, batchSize=$maxAccountsPerBatch)")
    // Periodic liveness: re-evaluate dispatch and pivot refresh even when no events flow.
    // Without this, ghost peers cause a silent stall with no incoming messages to trigger re-evaluation.
    import context.dispatcher
    context.system.scheduler.scheduleWithFixedDelay(30.seconds, 30.seconds, self, StorageCheckCompletion)
  }

  override val supervisorStrategy: SupervisorStrategy =
    OneForOneStrategy(maxNrOfRetries = 3, withinTimeRange = 1.minute) { case _: Exception =>
      log.warning("Storage worker failed, restarting")
      Restart
    }

  override def receive: Receive = {
    case StartStorageRangeSync(root) =>
      log.info(s"Starting storage range sync for state root ${root.take(8).toHex}")

    case AddStorageTasks(storageTasks) =>
      tasks.enqueueAll(storageTasks)
      totalStorageContracts += storageTasks.map(_.accountHash).distinct.size
      log.info(
        s"Added ${storageTasks.size} storage tasks to queue (total pending: ${tasks.size}, contracts: $totalStorageContracts)"
      )
      // Account-range completion is the only path that can grow the queue faster than dispatch.
      // Check watermarks immediately so the AccountRangeCoordinator pause signal goes out before
      // the next account-range batch lands.
      notifyBackpressureIfChanged()

    case AddStorageTask(task) =>
      tasks.enqueue(task)
      log.debug(s"Added storage task for account ${task.accountString} to queue")

    case StoragePeerAvailable(peer) =>
      // Evict stale entry for same physical node (reconnection creates new PeerId).
      // Only clear stateless marking for peers that actually reconnected with a NEW ID.
      // If the same peer is re-reported (same id), preserve its stateless marking —
      // otherwise StoragePeerAvailable from AccountRangeCoordinator clears stateless
      // every ~1s, bypassing the backoff mechanism entirely (Bug 24).
      val evicted = knownAvailablePeers.filter(_.remoteAddress == peer.remoteAddress)
      knownAvailablePeers --= evicted
      evicted.foreach { p =>
        if (p.id.value != peer.id.value) {
          statelessPeers -= p.id.value
        }
      }
      knownAvailablePeers += peer
      if (isPostRefreshCooldownActive) {
        log.debug(s"Ignoring StoragePeerAvailable(${peer.id.value}) - post-refresh cooldown active")
      } else if (pivotRefreshRequested) {
        log.debug(s"Ignoring StoragePeerAvailable(${peer.id.value}) - pivot refresh pending")
      } else if (isPeerStateless(peer)) {
        log.debug(s"Ignoring StoragePeerAvailable(${peer.id.value}) - peer is stateless for current root")
      } else if (isPeerCoolingDown(peer)) {
        log.debug(s"Ignoring StoragePeerAvailable(${peer.id.value}) due to cooldown")
      } else if (!isComplete && tasks.nonEmpty) {
        // Pipeline multiple requests per peer (core-geth parity).
        dispatchIfPossible(peer)
      }

    case StoragePeerUnavailable(peerId) =>
      // Peer disconnected — remove from available set and immediately re-queue its in-flight
      // tasks so other peers can pick them up without waiting for the 30s request timeout.
      // Mirrors AccountRangeCoordinator.PeerUnavailable (go-ethereum revertRequests pattern).
      knownAvailablePeers.find(_.id.value == peerId).foreach(knownAvailablePeers -= _)
      peerCooldownUntilMs.remove(peerId)
      emptyResponseStrikes.remove(peerId)
      val inFlight = activeTasks.filter { case (_, (peer, _, _)) => peer.id.value == peerId }.keys.toSeq
      if (inFlight.nonEmpty) {
        log.debug(s"Peer $peerId disconnected — re-queuing ${inFlight.size} in-flight storage request(s)")
        inFlight.foreach { reqId =>
          activeTasks.remove(reqId).foreach { case (_, batchTasks, _) =>
            batchTasks.foreach { task =>
              task.pending = false
              val key = (task.accountHash, task.next)
              if (!pendingTaskKeys.contains(key)) {
                pendingTaskKeys += key
                tasks.enqueue(task)
              }
            }
          }
        }
      }
      tryRedispatchPendingTasks()

    case UpdateMaxInFlightPerPeer(newLimit) =>
      log.info(s"Storage per-peer budget: $maxInFlightPerPeer -> $newLimit")
      maxInFlightPerPeer = newLimit
      if (newLimit > 0) tryRedispatchPendingTasks()

    case StorageRangesResponseMsg(response) =>
      handleResponse(response)

    case StorageTaskComplete(requestId, result) =>
      result match {
        case Right(count) =>
          slotsDownloaded += count
          consecutiveTaskFailures = 0
          log.info(s"Storage task completed: $count slots")
          self ! StorageCheckCompletion
        case Left(error) =>
          log.warning(s"Storage task failed: $error")
      }

    case StorageCheckCompletion =>
      // Update contract completion progress for the progress monitor
      updateContractProgress()
      // Drain side of the back-pressure watermark — if dispatches and completions have shrunk
      // the queue below the low-water mark, release AccountRangeCoordinator's pause.
      notifyBackpressureIfChanged()
      // Drain the flat-batch accumulator once no more downloads are coming.
      if (noMoreTasksExpected && tasks.isEmpty && activeTasks.isEmpty) {
        flushPendingFlatBatch()
      }
      if (isComplete) {
        log.debug("Storage range sync complete!")
        snapSyncController ! SNAPSyncController.StorageRangeSyncComplete
      } else if (tasks.nonEmpty) {
        // Try to dispatch pending tasks — per-peer and global limits enforced in dispatchIfPossible().
        // Previously guarded by activeTasks.isEmpty which defeated pipelining.
        maybeRequestPivotRefresh()
        tryRedispatchPendingTasks()
      }

    case NoMoreStorageTasks =>
      noMoreTasksExpected = true
      log.info(
        s"No more storage tasks expected. Pending: ${tasks.size}, active: ${activeTasks.size}, " +
          s"in-flight tries: ${pendingAccountTries.size}"
      )
      // Flush the final flat-slot tail if all downloads are done.
      if (tasks.isEmpty && activeTasks.isEmpty) {
        flushPendingFlatBatch()
      }
      if (isComplete) {
        log.debug("Storage range sync complete!")
        snapSyncController ! SNAPSyncController.StorageRangeSyncComplete
      }

    case ForceCompleteStorage =>
      val abandoned = tasks.size + activeTasks.size
      val abandonedTries = pendingAccountTries.size
      log.warning(
        s"Force-completing storage sync: $slotsDownloaded slots downloaded, " +
          s"abandoning $abandoned remaining tasks, $abandonedTries in-flight per-account tries " +
          s"(healing phase will recover missing data)"
      )
      // Discard in-flight streaming tries — already-flushed nodes stay on disk; healing reconciles.
      pendingAccountTries.values.foreach(_.reset())
      pendingAccountTries.clear()
      com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncMetrics.setStoragePendingTries(0L)
      // Hand off any flat-slot tail still in the accumulator.
      flushPendingFlatBatch()
      log.info("Storage range sync force-completed (promoting to healing phase)")
      snapSyncController ! SNAPSyncController.StorageRangeSyncForceCompleted

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
      val cancelledCount = activeTasks.size
      activeTasks.values.foreach { case (_, batchTasks, _) =>
        batchTasks.foreach { task =>
          task.pending = false
          tasks.enqueue(task)
        }
      }
      activeTasks.clear()
      if (cancelledCount > 0) {
        log.info(s"Cancelled $cancelledCount in-flight storage requests (stale root)")
      }

      // Clear all per-peer adaptive state — fresh start with new root
      statelessPeers.clear()
      emptyResponseStrikes.clear()
      pivotRefreshRequested = false

      // Force-release storage back-pressure. Observed deadlock on sepolia 2026-05-14:
      // the queue locks at >100K pending → backpressure ENGAGED → AccountRangeCoordinator
      // dispatch paused → storage can't drain (no usable peers for current root) →
      // backpressure never releases (low-water = 50K is unreachable) → account stalls →
      // 5/5 critical SNAP failures → fallback to FastSync (which is also stuck on sepolia
      // because peers don't serve GetNodeData on ETH/68+).
      //
      // The fix: pivot refresh is the natural recovery moment. New root → maybe new
      // peers can serve the queued tasks → drain might resume. Let account dispatch
      // resume; if the queue overflows past the high-water mark again, the next
      // notifyBackpressureIfChanged() call from AddStorageTasks will re-engage.
      if (backpressureActive) {
        backpressureActive = false
        com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncMetrics.setStorageBackpressure(false)
        log.info(
          s"Storage queue back-pressure RELEASED on pivot refresh (queue depth=${tasks.size}). " +
            s"Will re-engage if queue crosses high-water=$backpressureHighWatermark again."
        )
        snapSyncController ! SNAPSyncController.StorageBackpressureChanged(paused = false)
      }
      lastDispatchOrResponseMs = System.currentTimeMillis()
      peerCooldownUntilMs.clear()
      peerBatchSize.clear()
      peerBatchSuccessStreak.clear()
      peerResponseBytesTarget.clear()
      emptyResponsesByTask.clear()

      // Discard streaming per-account tries — already-flushed content-addressed nodes
      // remain on disk and will be referenced by the new root's healing pass if still valid,
      // unreferenced and pruned otherwise. Only the in-memory stack-trie state is dropped.
      if (pendingAccountTries.nonEmpty) {
        log.info(s"Pivot refresh: resetting ${pendingAccountTries.size} in-flight per-account storage tries")
        pendingAccountTries.values.foreach(_.reset())
        pendingAccountTries.clear()
        com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncMetrics.setStoragePendingTries(0L)
      }

      // Set post-refresh cooldown: peers need time to sync to the new root.
      // Dispatching immediately causes all peers to return empty → marked stateless →
      // another pivot refresh → infinite tight loop (Bug 24).
      postRefreshCooldownUntilMs = System.currentTimeMillis() + postRefreshCooldownMs
      log.info(
        s"Post-refresh cooldown active for ${postRefreshCooldownMs / 1000}s — waiting for peers to sync to new root"
      )

      // Schedule dispatch after the cooldown period instead of dispatching immediately
      import context.dispatcher
      context.system.scheduler.scheduleOnce(postRefreshCooldownMs.millis) {
        self ! StorageCheckCompletion
      }

    case StorageGetProgress =>
      val stats = StorageRangeCoordinator.SyncStatistics(
        slotsDownloaded = slotsDownloaded,
        bytesDownloaded = bytesDownloaded,
        tasksCompleted = completedTaskCount.toInt,
        tasksActive = activeTasks.values.map(_._2.size).sum,
        tasksPending = tasks.size,
        elapsedTimeMs = System.currentTimeMillis() - startTime,
        progress = progress
      )
      sender() ! stats

    case FlatBatchFlushComplete(forStateRoot, entryCount, elapsedMs) =>
      inFlightFlatBatches = (inFlightFlatBatches - 1).max(0)
      if (forStateRoot != stateRoot) {
        log.debug(
          s"Flat batch flush completed for stale root ${forStateRoot.take(4).toHex} " +
            s"($entryCount entries) — bookkeeping ignored, data on disk"
        )
      } else {
        val rate = if (elapsedMs > 0) entryCount * 1000L / elapsedMs else entryCount.toLong
        log.debug(s"Flat batch flushed: $entryCount slots in ${elapsedMs}ms ($rate slots/s)")
      }
      // A drained flush may have unblocked completion (NoMore + empty queues).
      self ! StorageCheckCompletion

    case FlatBatchFlushFailed(forStateRoot, entryCount, error) =>
      inFlightFlatBatches = (inFlightFlatBatches - 1).max(0)
      log.error(
        s"Flat batch flush failed for $entryCount slots " +
          s"(root ${forStateRoot.take(4).toHex}): $error. Healing phase will recover."
      )
      self ! StorageCheckCompletion
  }

  private def requestNextRanges(peer: Peer): Option[BigInt] = {
    if (tasks.isEmpty) {
      log.debug("No more storage tasks available")
      return None
    }

    if (isPostRefreshCooldownActive) {
      return None
    }

    if (pivotRefreshRequested) {
      return None
    }

    if (isPeerStateless(peer)) {
      return None
    }

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

    // Peek-ahead at the front of the queue: if the head task would force a new account
    // open beyond the cap, leave it queued and skip this dispatch cycle. Once an in-flight
    // trie commits (or aborts), the cap relaxes and the next dispatch will pick it up.
    if (tasks.nonEmpty && !acceptsNewAccount(tasks.front)) {
      log.debug(
        s"Storage dispatch gated by max-concurrent-storage-accounts=$maxConcurrentStorageAccounts " +
          s"(in-flight tries=${pendingAccountTries.size}); deferring new-account dispatch"
      )
      return None
    }

    val peerBatch = batchSizeFor(peer)

    // snap/1 origin/limit semantics apply to the first account only. To avoid incorrect continuation
    // behavior, only batch tasks that request the initial full range.
    val first = tasks.dequeue()
    pendingTaskKeys -= ((first.accountHash, first.next))
    val batchTasks: Seq[StorageTask] =
      if (!isInitialRange(first) || peerBatch <= 1) {
        Seq(first)
      } else {
        val buf = mutable.ArrayBuffer[StorageTask](first)
        while (
          buf.size < peerBatch && tasks.nonEmpty && isInitialRange(tasks.front) && acceptsNewAccount(tasks.front)
        ) {
          val t = tasks.dequeue()
          pendingTaskKeys -= ((t.accountHash, t.next))
          buf += t
        }
        buf.toSeq
      }

    if (batchTasks.isEmpty) {
      return None
    }

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

    batchTasks.foreach(_.pending = true)
    activeTasks.put(requestId, (peer, batchTasks, requestedBytes))

    requestTracker.trackRequest(
      requestId,
      peer,
      SNAPRequestTracker.RequestType.GetStorageRanges,
      timeout = requestTimeout
    ) {
      handleTimeout(requestId)
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
    networkPeerManager ! NetworkPeerManagerActor.SendMessage(messageSerializable, peer.id)
    lastDispatchOrResponseMs = System.currentTimeMillis()

    Some(requestId)
  }

  private def handleResponse(response: StorageRanges): Unit =
    requestTracker.validateStorageRanges(response) match {
      case Left(error) =>
        log.warning(s"Invalid StorageRanges response: $error")

      case Right(validResponse) =>
        val slotCount = validResponse.slots.map(_.size).sum
        requestTracker.completeRequest(response.requestId, slotCount.max(1)) match {
          case None =>
            log.warning(s"Received response for unknown request ID ${response.requestId}")

          case Some(_) =>
            activeTasks.remove(response.requestId) match {
              case None =>
                log.warning(s"No active tasks for request ID ${response.requestId}")

              case Some((peer, batchTasks, requestedBytes)) =>
                processStorageRanges(peer, batchTasks, requestedBytes, validResponse)
            }
        }
    }

  private def processStorageRanges(
      peer: Peer,
      tasks: Seq[StorageTask],
      requestedBytes: BigInt,
      response: StorageRanges
  ): Unit = {
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

    if (servedCount == 0) {
      // Proof-of-absence: server returned 0 slots WITH proof nodes. Per the snap/1 protocol,
      // this is a valid cryptographic proof that no slots exist in [startingHash, limitHash]
      // at the current state root. The account's storage is empty or was modified/cleared
      // since the original pivot. Healing will validate the final trie.
      // IMPORTANT: do NOT mark the peer stateless — it served a valid, well-formed response.
      // Only fall through to stateless marking when proofs == 0 (peer gave us nothing at all).
      if (response.proof.nonEmpty && tasks.size == 1) {
        val task = tasks.head
        task.done = true
        task.pending = false
        recordCompletedTask(task)
        log.warning(
          s"Storage proof-of-absence accepted: account=${task.accountString} " +
            s"storageRoot=${task.storageRoot.take(4).toHex} range=${task.rangeString} " +
            s"proofNodes=${response.proof.size} peer=${peer.id.value}. " +
            s"Account storage empty/changed at current pivot — healing will validate."
        )
        // Peer is healthy — clear any penalty state it accumulated.
        statelessPeers.remove(peer.id.value)
        lastDispatchOrResponseMs = System.currentTimeMillis()
        consecutiveUnproductiveRefreshes = 0
        self ! StorageCheckCompletion
        dispatchIfPossible(peer)
        return
      }

      // Per-peer batch reduction: only reduce for the specific peer that failed
      if (tasks.size > 1 && batchSizeFor(peer) > 1) {
        log.info(
          s"Received empty StorageRanges for a batched request from peer ${peer.id.value} (accounts=${tasks.size}); " +
            s"falling back to single-account requests for this peer"
        )
        reduceBatchSize(peer)
      }

      adjustResponseBytesOnFailure(peer, "empty response")

      // Track empties per task to avoid re-queueing forever.
      // If the same task yields empty responses repeatedly, skip it with a loud warning.
      var skipped = 0
      tasks.foreach { task =>
        val key = StorageTaskKey(task.accountHash, task.next, task.last)
        val attempts = emptyResponsesByTask.getOrElse(key, 0) + 1
        emptyResponsesByTask.update(key, attempts)

        if (attempts >= maxEmptyResponsesPerTask) {
          skipped += 1
          task.done = true
          task.pending = false
          recordCompletedTask(task)
          // Discard any partial streaming trie for this account — committing now would
          // produce a wrong root (missing slots). Already-flushed content-addressed nodes
          // stay on disk and healing reconciles when the contract is revisited.
          resetAccountTrie(task.accountHash)
          com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncMetrics
            .setStoragePendingTries(pendingAccountTries.size.toLong)
          log.warning(
            s"Skipping storage task after $attempts empty StorageRanges replies: " +
              s"account=${task.accountHash.toHex} storageRoot=${task.storageRoot.toHex} range=${task.rangeString}"
          )
        } else {
          task.pending = false
          this.tasks.enqueue(task)
          log.debug(
            s"Empty StorageRanges for task (attempt $attempts/$maxEmptyResponsesPerTask); re-queueing: " +
              s"account=${task.accountHash.take(4).toHex} range=${task.rangeString}"
          )
        }
      }

      // Always mark this peer as stateless for the current root on empty response.
      // Even if some tasks were skipped, the peer still couldn't serve any data.
      // This ensures stateless detection triggers pivot refresh when ALL peers fail,
      // rather than silently draining tasks as "empty" one by one.
      markPeerStateless(peer)

      if (skipped > 0) {
        self ! StorageCheckCompletion
      }
      return
    }

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

    if (unservedTasks.nonEmpty) {
      log.debug(s"Re-queueing ${unservedTasks.size} unserved storage tasks")
      unservedTasks.foreach { task =>
        task.pending = false
        this.tasks.enqueue(task)
      }
    }

    // Track total received bytes across all served tasks for adaptive byte budgeting
    var totalReceivedBytes: Long = 0

    servedTasks.zipWithIndex.foreach { case (task, idx) =>
      val accountSlots =
        if (response.slots.nonEmpty && idx < response.slots.size) response.slots(idx)
        else Seq.empty

      // Best-practice: apply proof nodes only to the last served slot-set.
      val proofForThisTask = if (idx == servedCount - 1) response.proof else Seq.empty

      task.slots = accountSlots
      task.proof = proofForThisTask

      val verifier = MerkleProofVerifier(task.storageRoot)
      verifier.verifyStorageRange(accountSlots, proofForThisTask, task.next, task.last) match {
        case Left(error) =>
          log.warning(s"Storage proof verification failed for account ${task.accountString}: $error")
          recordPeerCooldown(peer, s"verification failed: $error")
          adjustResponseBytesOnFailure(peer, s"verification failed: $error")
          task.pending = false
          this.tasks.enqueue(task)

        case Right(_) =>
          val slotBytes = accountSlots.map { case (hash, value) => hash.size + value.size }.sum
          totalReceivedBytes += slotBytes

          if (accountSlots.nonEmpty) {
            // Stream slots directly into the per-account `SnapHashTrie`. The validator
            // enforces strictly-ascending slot order within and across responses (via
            // `SNAPRequestTracker.validateStorageRanges` + `StorageTask.createContinuation`),
            // so no pre-insert sort is needed — the wrapper's underlying StackTrie throws on
            // out-of-order keys. Emitted RLP-node batches flush to RocksDB at the 8 MiB
            // threshold inside the wrapper, capping in-heap working set per contract.
            if (!deferredMerkleization) {
              val trie = getOrCreateAccountTrie(task.accountHash)
              accountSlots.foreach { case (slotHash, slotValue) =>
                trie.update(slotHash.toArray, slotValue.toArray)
              }
              com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncMetrics
                .setStoragePendingTries(pendingAccountTries.size.toLong)
            }

            // Flat-slot mirror — accountHash ++ slotHash → slotValue. Sorted in
            // `stageFlatSlotChunk` and accumulated for an off-actor batched commit.
            stageFlatSlotChunk(task.accountHash, accountSlots)

            slotsDownloaded += accountSlots.size
            bytesDownloaded += slotBytes

            snapSyncController ! SNAPSyncController.ProgressStorageSlotsSynced(accountSlots.size.toLong)

            // Handle continuation: only create when proof indicates a partial range.
            // Per SNAP spec: empty proof = full storage served, no continuation needed.
            val needsContinuation = if (proofForThisTask.nonEmpty) {
              val lastSlot = accountSlots.last._1
              lastSlot.toSeq.compare(task.last.toSeq) < 0
            } else false

            if (needsContinuation) {
              val lastSlot = accountSlots.last._1
              val continuationTask = StorageTask.createContinuation(task, lastSlot)
              this.tasks.enqueue(continuationTask)
              log.debug(s"Created continuation task for account ${task.accountString} (partial range, proof present)")
            } else {
              // Account fully downloaded — commit the streaming trie if one exists.
              // For deferred-merkleization mode no trie was built; flat-slot writes alone
              // are sufficient and the MPT is rebuilt later from flat data.
              if (deferredMerkleization) {
                log.debug(
                  s"Account ${task.accountHash.take(4).toHex} fully downloaded " +
                    s"(deferred merkleization — flat-only)"
                )
              } else {
                val computedRoot = commitAccountTrie(task.accountHash, task.storageRoot)
                log.debug(
                  s"Account ${task.accountHash.take(4).toHex} streaming trie committed: " +
                    s"root=${computedRoot.take(4).toHex}"
                )
              }
              completedAccountCount += 1
            }

            task.done = true
            task.pending = false
            recordCompletedTask(task)
          } else {
            // No slots to store — mark task done
            task.done = true
            task.pending = false
            recordCompletedTask(task)
          }
      }
    }

    // Adjust per-peer byte budget based on total received bytes
    if (totalReceivedBytes > 0) {
      adjustResponseBytesOnSuccess(peer, requestedBytes, BigInt(totalReceivedBytes))
    }

    // Check completion after processing all served tasks
    self ! StorageCheckCompletion

    // Immediately pipeline more work to this peer — don't wait for StoragePeerAvailable
    dispatchIfPossible(peer)
  }

  private def handleTimeout(requestId: BigInt): Unit = {
    activeTasks.remove(requestId).foreach { case (peer, batchTasks, _) =>
      log.warning(s"Storage range request timeout for ${batchTasks.size} accounts from peer ${peer.id.value}")
      recordPeerCooldown(peer, "request timeout")
      adjustResponseBytesOnFailure(peer, "request timeout")

      batchTasks.foreach { task =>
        task.pending = false
        val key = (task.accountHash, task.next)
        if (!pendingTaskKeys.contains(key)) {
          pendingTaskKeys += key
          tasks.enqueue(task)
        }
      }

      consecutiveTaskFailures += 1
      log.debug(s"Storage consecutive task failures: $consecutiveTaskFailures/$maxConsecutiveTaskFailures")
      if (consecutiveTaskFailures >= maxConsecutiveTaskFailures) {
        log.warning(
          s"[STORAGE-FORCE-COMPLETE] $consecutiveTaskFailures consecutive task failures — " +
            s"SNAP peers not serving storage data. Sending ForceCompleteStorage. " +
            s"Missing storage deferred to healing phase."
        )
        self ! ForceCompleteStorage
      }
    }
    // Re-dispatch re-queued tasks to any known available peer that isn't stateless or on cooldown.
    tryRedispatchPendingTasks()
  }

  /** Dispatch up to maxInFlightPerPeer requests to a single peer (pipelining). */
  private def dispatchIfPossible(peer: Peer): Unit = {
    var inflight = inFlightForPeer(peer)
    while (tasks.nonEmpty && inflight < maxInFlightPerPeer && activeTasks.size < maxInFlightRequests)
      requestNextRanges(peer) match {
        case Some(_) => inflight += 1
        case None    => return
      }
  }

  // Periodic state-dump cadence — at most one INFO snapshot every 30 seconds. tryRedispatchPendingTasks
  // can be called many times per second under heavy storage flow (each AddStorageTasks call
  // chains into it), so modulo-based throttling produced log floods that overflowed the live
  // monitor pipe; time-based throttling is robust against call-rate spikes.
  private var lastStateLogMs: Long = 0L
  private val StateLogIntervalMs: Long = 30_000L

  private def tryRedispatchPendingTasks(): Unit = {
    if (tasks.isEmpty) return
    if (isPostRefreshCooldownActive) return
    if (pivotRefreshRequested) return
    var eligiblePeers = knownAvailablePeers
      .filterNot(p => isPeerStateless(p) || isPeerCoolingDown(p))
      .toList
    // Eligible-set floor (peer-retention): if the only thing excluding every non-stateless peer is a cooldown, revive
    // the soonest-to-expire one rather than stalling at zero dispatchable peers. Mirrors AccountRangeCoordinator.
    if (eligiblePeers.isEmpty) {
      knownAvailablePeers
        .filterNot(isPeerStateless)
        .filter(isPeerCoolingDown)
        .toList
        .sortBy(p => peerCooldownUntilMs.getOrElse(p.id.value, 0L))
        .headOption
        .foreach { peer =>
          peerCooldownUntilMs.remove(peer.id.value)
          log.info(
            s"[STORAGE-FLOOR] All servable peers were cooling and none eligible — " +
              s"reviving ${peer.id.value.take(8)} to keep the pipe fed (peer-scarce floor)"
          )
          eligiblePeers = List(peer)
        }
    }
    val now = System.currentTimeMillis()
    val shouldLog = now - lastStateLogMs >= StateLogIntervalMs
    if (shouldLog) {
      lastStateLogMs = now
      log.info(
        s"[STORAGE-STATE] pending=${tasks.size} active=${activeTasks.size} " +
          s"workers-known=${knownAvailablePeers.size} stateless=${statelessPeers.size} " +
          s"cooling=${knownAvailablePeers.count(isPeerCoolingDown)} eligible=${eligiblePeers.size} " +
          s"strikes=${emptyResponseStrikes.size} root=${stateRoot.take(4).toHex}"
      )
    }
    if (eligiblePeers.isEmpty) {
      if (shouldLog) {
        log.info(
          s"[STORAGE-REDISPATCH] No eligible peers — ${knownAvailablePeers.size} known, " +
            s"${statelessPeers.size} stateless, " +
            s"${knownAvailablePeers.count(isPeerCoolingDown)} cooling. pending: ${tasks.size}"
        )
      }
      if (tasks.nonEmpty && activeTasks.isEmpty) {
        storageIdleChecks += 1
        if (storageIdleChecks >= storageIdleEscapeThreshold) {
          log.warning(
            s"[STORAGE] No eligible peers for $storageIdleChecks consecutive redispatch checks with " +
              s"${tasks.size} pending tasks and no active requests — requesting pivot refresh"
          )
          storageIdleChecks = 0
          maybeRequestPivotRefresh()
        }
      }
      return
    } else {
      storageIdleChecks = 0
    }

    for (peer <- eligiblePeers if tasks.nonEmpty)
      dispatchIfPossible(peer)
  }

  private def progress: Double = {
    val activeCount = activeTasks.values.map(_._2.size).sum
    val total = completedTaskCount + activeCount + tasks.size
    if (total == 0) 1.0
    else completedTaskCount.toDouble / total
  }

  private def isComplete: Boolean =
    noMoreTasksExpected && tasks.isEmpty && activeTasks.isEmpty &&
      pendingAccountTries.isEmpty &&
      pendingFlatBatchAccounts.isEmpty && inFlightFlatBatches == 0

  /** Update contract completion counts and send progress to controller. The counter is incremented exactly once per
    * account at the "no continuation needed" branch of `processStorageRanges`; we just broadcast its current value,
    * avoiding the previous O(N) rebuild over completedTasks that ran on every progress check.
    */
  private var lastReportedCompletedAccountCount: Long = 0L
  private def updateContractProgress(): Unit = {
    if (totalStorageContracts <= 0) return
    if (completedAccountCount != lastReportedCompletedAccountCount) {
      lastReportedCompletedAccountCount = completedAccountCount
      snapSyncController ! SNAPSyncController.ProgressStorageContracts(
        completedAccountCount.toInt,
        totalStorageContracts
      )
    }
  }

  private def isPeerCoolingDown(peer: Peer): Boolean =
    peerCooldownUntilMs.get(peer.id.value).exists(_ > System.currentTimeMillis())

  private def recordPeerCooldown(peer: Peer, reason: String): Unit = {
    val until = System.currentTimeMillis() + peerCooldownDefault.toMillis
    peerCooldownUntilMs.put(peer.id.value, until)
    log.debug(s"Cooling down peer ${peer.id.value} for ${peerCooldownDefault.toSeconds}s: $reason")
  }
}

object StorageRangeCoordinator {
  def props(
      stateRoot: ByteString,
      networkPeerManager: ActorRef,
      requestTracker: SNAPRequestTracker,
      mptStorage: MptStorage,
      flatSlotStorage: FlatSlotStorage,
      maxAccountsPerBatch: Int,
      maxInFlightRequests: Int,
      requestTimeout: FiniteDuration,
      snapSyncController: ActorRef,
      initialMaxInFlightPerPeer: Int = 5,
      initialResponseBytes: Int = 1048576,
      minResponseBytes: Int = 131072,
      deferredMerkleization: Boolean = true,
      flatBatchEntryThreshold: Int = 1000,
      flatBatchEcOverride: Option[ExecutionContext] = None,
      useStackTrie: Boolean = false,
      backpressureHighWatermark: Int = 100000,
      backpressureLowWatermark: Int = 50000,
      maxConcurrentStorageAccounts: Int = 256
  ): Props =
    Props(
      new StorageRangeCoordinator(
        initialStateRoot = stateRoot,
        networkPeerManager,
        requestTracker,
        mptStorage,
        flatSlotStorage,
        maxAccountsPerBatch,
        maxInFlightRequests,
        requestTimeout,
        snapSyncController,
        initialMaxInFlightPerPeer,
        configInitialResponseBytes = initialResponseBytes,
        configMinResponseBytes = minResponseBytes,
        deferredMerkleization = deferredMerkleization,
        flatBatchEntryThreshold = flatBatchEntryThreshold,
        flatBatchEcOverride = flatBatchEcOverride,
        useStackTrie = useStackTrie,
        backpressureHighWatermark = backpressureHighWatermark,
        backpressureLowWatermark = backpressureLowWatermark,
        maxConcurrentStorageAccounts = maxConcurrentStorageAccounts
      )
    )

  /** Sync statistics for storage range download */
  case class SyncStatistics(
      slotsDownloaded: Long,
      bytesDownloaded: Long,
      tasksCompleted: Int,
      tasksActive: Int,
      tasksPending: Int,
      elapsedTimeMs: Long,
      progress: Double
  ) {
    def throughputSlotsPerSec: Double =
      if (elapsedTimeMs > 0) slotsDownloaded.toDouble / (elapsedTimeMs / 1000.0)
      else 0.0

    def throughputBytesPerSec: Double =
      if (elapsedTimeMs > 0) bytesDownloaded.toDouble / (elapsedTimeMs / 1000.0)
      else 0.0

    override def toString: String =
      f"Progress: ${progress * 100}%.1f%%, Slots: $slotsDownloaded, " +
        f"Bytes: ${bytesDownloaded / 1024}KB, Tasks: $tasksCompleted done, $tasksActive active, $tasksPending pending, " +
        f"Speed: ${throughputSlotsPerSec}%.1f slots/s, ${throughputBytesPerSec / 1024}%.1f KB/s"
  }
}
