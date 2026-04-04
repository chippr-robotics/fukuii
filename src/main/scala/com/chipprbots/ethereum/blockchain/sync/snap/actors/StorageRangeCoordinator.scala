package com.chipprbots.ethereum.blockchain.sync.snap.actors

import org.apache.pekko.actor.{Actor, ActorLogging, ActorRef, Props, SupervisorStrategy, OneForOneStrategy}
import org.apache.pekko.actor.SupervisorStrategy._
import org.apache.pekko.util.ByteString

import java.io.{BufferedOutputStream, FileOutputStream}
import java.nio.file.{Files, Path}

import scala.collection.mutable
import scala.concurrent.duration._
import scala.math.Ordered.orderingToOrdered

import com.chipprbots.ethereum.blockchain.sync.snap._
import com.chipprbots.ethereum.db.storage.{DeferredWriteMptStorage, FlatSlotStorage, MptStorage}
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.p2p.MessageSerializable
import com.chipprbots.ethereum.network.p2p.messages.SNAP._
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
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
    deferredMerkleization: Boolean = true
) extends Actor
    with ActorLogging {

  import Messages._

  // Mutable state root — updated in-place when the controller refreshes the pivot.
  private var stateRoot: ByteString = initialStateRoot

  // Per-peer concurrency budget — dynamically adjusted by SNAPSyncController via UpdateMaxInFlightPerPeer.
  private var maxInFlightPerPeer: Int = initialMaxInFlightPerPeer

  // Task management
  private val tasks = mutable.Queue[StorageTask]()
  private val activeTasks =
    mutable.Map[BigInt, (Peer, Seq[StorageTask], BigInt)]() // requestId -> (peer, tasks, requestedBytes)
  private val completedTasks = mutable.ArrayBuffer[StorageTask]()

  // Track consecutive timeouts per peer. When a peer hits the threshold, it's marked stateless.
  // This handles the case where ETC mainnet peers silently stop responding (timeout) when
  // their snap serve window expires, rather than returning empty responses with proofs.
  private val peerConsecutiveTimeouts = mutable.Map[String, Int]()
  // Dynamic threshold: scales with workers/peers ratio to prevent premature stateless marking
  // when few peers are available. With 14 workers / 2 peers, threshold becomes max(3, 3*7)=21.
  private val baseConsecutiveTimeoutThreshold = 3
  private def consecutiveTimeoutThreshold: Int = {
    val peers = math.max(knownAvailablePeers.size, 1)
    val workers = math.max(maxInFlightRequests, 1)
    math.max(baseConsecutiveTimeoutThreshold, baseConsecutiveTimeoutThreshold * (workers / peers))
  }

  // R3: Track recent task-peer failures to avoid re-dispatching the same task to a peer that
  // just timed out on it. Key: (accountHash, peerId), Value: failure timestamp.
  // Entries expire after taskPeerCooldownMs (30s — 3x the base per-peer cooldown).
  private val recentTaskPeerFailures = mutable.Map.empty[(ByteString, String), Long]
  private val taskPeerCooldownMs: Long = 30_000L

  // Peer cooldown (best-effort): used for transient errors (timeouts, verification failures).
  // This is separate from stateless peer detection — cooldowns are short and per-error-type.
  private val peerCooldownUntilMs = mutable.Map[String, Long]()
  private val peerCooldownDefault = 10.seconds

  // Binary stateless peer detection: peers that cannot serve the current state root.
  // When ALL known peers become stateless, request a pivot refresh from the controller.
  // This replaces the slow counter-based global backoff (was: 10 empties → 2 min pause).
  private val statelessPeers = mutable.Set[String]()
  private var pivotRefreshRequested = false

  // Contract completion tracking for progress estimation.
  // totalStorageContracts counts unique contracts added via AddStorageTasks.
  // completedAccountHashes tracks unique contracts that have been fully synced.
  // R6 fix: File-backed persistence — completedAccountHashes are appended to a temp file
  // so crash recovery can skip already-completed accounts instead of re-downloading everything.
  private var totalStorageContracts: Int = 0
  private var storageSyncCompleteReported: Boolean = false
  private val completedAccountHashes = mutable.Set[ByteString]()
  private val completedAccountsFile: Path = Files.createTempFile("fukuii-completed-storage-", ".bin")
  private val completedAccountsOut = new BufferedOutputStream(new FileOutputStream(completedAccountsFile.toFile), 32768)
  private var completedAccountsFileCount: Long = 0

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
  private val postRefreshCooldownMs: Long = 10000L // 10 seconds after pivot refresh

  private def isPostRefreshCooldownActive: Boolean =
    System.currentTimeMillis() < postRefreshCooldownUntilMs

  private def isPeerStateless(peer: Peer): Boolean =
    statelessPeers.contains(peer.id.value)

  private def markPeerStateless(peer: Peer): Unit = {
    if (peer.isStatic) {
      log.debug(s"[STATIC] Skipping penalization for static peer ${peer.remoteAddress}")
      return
    }
    statelessPeers.add(peer.id.value)
    log.info(
      s"Peer ${peer.id.value} marked stateless for storage root ${stateRoot.take(4).toHex} " +
        s"(${statelessPeers.size}/${knownAvailablePeers.size} stateless)"
    )
    maybeRequestPivotRefresh()
  }

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
    val dispatchStalled = !allStateless && tasks.nonEmpty &&
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

  /** Scale batch size back up after consecutive successful packed responses.
    * Doubles the batch size per peer, capped at maxAccountsPerBatch.
    */
  private def maybeIncreaseBatchSize(peer: Peer, servedCount: Int, requestedCount: Int): Unit = {
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
          log.info(s"Peer ${peer.id.value} batch size increased: $current -> $next (after $streak consecutive successes)")
        }
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

  // Track proof verification failures per task. Same pattern as emptyResponsesByTask:
  // after repeated proof mismatches (stale peer state), skip the task and let healing fix it.
  private val proofFailuresByTask = mutable.HashMap.empty[StorageTaskKey, Int]
  private val maxProofFailuresPerTask: Int = 5

  // Fast-fail for unservable storage roots (BUG-SU1).
  // A proof root mismatch means the peer's current state cannot serve this storage root —
  // it was recorded at a pivot now beyond the peer's pruning window (Besu bonsai: 8,192 blocks).
  // When MinPeersForStaleDecision distinct peers confirm the mismatch for the same storageRoot,
  // all tasks for that root are skipped immediately rather than cycling through pivot refreshes.
  private val staleRootMismatchPeers = mutable.HashMap.empty[ByteString, mutable.Set[String]]
  private val MinPeersForStaleDecision: Int = 2

  // Sentinel: when true, no more AddStorageTasks will arrive (all accounts downloaded).
  // Completion is only reported after this is set AND pending+active tasks drain.
  // Geth-aligned: coordinators run from start, tasks arrive inline during account download.
  private var noMoreTasksExpected: Boolean = false

  // Statistics
  private var slotsDownloaded: Long = 0
  private var bytesDownloaded: Long = 0
  private val startTime = System.currentTimeMillis()
  private var lastProgressLogAt: Long = 0
  private val ProgressLogInterval: Long = 10_000

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
  // Two-phase storage: raw slot buffering + deferred trie construction
  // ========================================
  //
  // Phase 1 (during response): buffer raw (slotHash, slotValue) pairs per account.
  // Phase 2 (on account complete): sort by key, build trie on background thread.
  //
  // Small contracts (all slots in one response, < smallContractThreshold) skip MPT
  // entirely — flat storage only. ~95% of ETC contracts are small (< 100 slots).
  //
  // Large contracts accumulate slots across continuations, then build the trie
  // on a bounded thread pool to control RocksDB write pressure.

  /** Raw slot buffer per account hash. Accumulated during download, consumed during trie construction. */
  private val pendingAccountSlots = mutable.Map[ByteString, mutable.ArrayBuffer[(ByteString, ByteString)]]()

  /** Accounts currently having their tries built asynchronously. Prevents double-building. */
  private val accountsInTrieConstruction = mutable.Set[ByteString]()

  /** Maximum buffered slots across all accounts before forcing an incremental trie build.
    * Prevents OOM when downloading mainnet with millions of storage slots.
    * At ~64 bytes per slot (32 hash + 32 value), 1M slots ≈ 64MB.
    */
  private val maxBufferedSlots: Long = 1000000
  private var totalBufferedSlots: Long = 0

  /** Per-account memory limit: flush large accounts incrementally when they exceed this.
    * Mainnet DeFi contracts can have 10-50M slots. Without per-account limits, a single
    * contract could buffer several GB before trie construction starts.
    * At 64 bytes/slot, 500K slots = ~32MB per account.
    */
  private val maxSlotsPerAccount: Long = 500000

  /** Threshold for triggering incremental builds of complete accounts.
    * When we have this many complete accounts buffered, build their tries without waiting.
    */
  private val trieConstructionBatchSize = 64

  /** Accounts whose slots have been fully downloaded (no continuation) and are ready for trie construction.
    * Using LinkedHashSet for dedup + insertion order (FIFO processing).
    */
  private val accountsReadyForBuild = mutable.LinkedHashSet[ByteString]()

  /** StackTrie shortcut threshold: contracts with fewer slots than this skip MPT construction
    * entirely — only flat storage is written. The trie is reconstructed lazily from flat data
    * during the healing phase or deferred Merkleization pass. ~95% of ETC contracts qualify.
    */
  private val smallContractThreshold = 1024

  /** Bounded thread pool for trie construction — controls RocksDB write pressure.
    * Using 3 threads avoids overwhelming the single-threaded RocksDB compaction while
    * still enabling parallel trie builds. Unbounded Futures on sync-dispatcher caused
    * thread starvation under heavy load.
    */
  private val trieBuilderPool: java.util.concurrent.ExecutorService =
    java.util.concurrent.Executors.newFixedThreadPool(3, (r: Runnable) => {
      val t = new Thread(r, "storage-trie-builder")
      t.setDaemon(true)
      t
    })
  private val trieBuilderEc = scala.concurrent.ExecutionContext.fromExecutorService(trieBuilderPool)

  /** Write only to flat storage for small contracts — skip MPT construction.
    * Called synchronously from the actor for accounts that arrived in a single response
    * with fewer slots than smallContractThreshold. These are ~95% of ETC contracts.
    * The MPT will be built lazily from flat data during healing or post-sync Merkleization.
    */
  private def writeSmallContractFlatOnly(accountHash: ByteString, slots: mutable.ArrayBuffer[(ByteString, ByteString)]): Unit = {
    val sorted = slots.sortBy(_._1)(ByteStringOrdering)
    flatSlotStorage.putSlotsBatch(accountHash, sorted.toSeq).commit()
    pendingAccountSlots.remove(accountHash)
    totalBufferedSlots -= slots.size
  }

  /** Build tries for a batch of complete accounts on a background thread.
    * Sorts slots by key for better trie locality, builds tries, and flushes to storage.
    * Uses a batch-local DeferredWriteMptStorage to avoid thread-safety issues with the
    * shared mptStorage — each batch gets its own write buffer that flushes independently.
    * Trie nodes and flat slot data are written in a single combined RocksDB batch.
    */
  private def buildAccountTriesAsync(accountHashes: Seq[ByteString]): Unit = {
    if (accountHashes.isEmpty) return

    // Extract slots from buffer (move, not copy)
    val accountData = accountHashes.flatMap { hash =>
      pendingAccountSlots.remove(hash).map { slots =>
        totalBufferedSlots -= slots.size
        (hash, slots)
      }
    }

    if (accountData.isEmpty) return

    accountData.foreach { case (hash, _) => accountsInTrieConstruction.add(hash) }

    val selfRef = self
    val totalSlotCount = accountData.map(_._2.size).sum.toLong
    val localMptStorage = mptStorage // capture for Future
    val localFlatSlotStorage = flatSlotStorage // capture for Future
    val constructionStateRoot = stateRoot // tag with current pivot root

    import scala.concurrent.{Future, blocking}
    Future {
      blocking {
        val startMs = System.currentTimeMillis()

        // Batch-local deferred storage — thread-safe: only this Future writes to it.
        val batchStorage = new DeferredWriteMptStorage(localMptStorage)

        // Flat slot storage: accumulate all (accountHash++slotHash → value) writes
        // for a single atomic batch commit alongside trie nodes.
        var flatBatch = localFlatSlotStorage.emptyBatchUpdate

        accountData.foreach { case (accountHash, slots) =>
          // Sort by key for better trie locality — sequential keys share prefixes,
          // reducing node reconstructions during insertion
          val sortedSlots = slots.sortBy(_._1)(ByteStringOrdering)
          import com.chipprbots.ethereum.mpt.byteStringSerializer
          var trie = MerklePatriciaTrie[ByteString, ByteString](batchStorage)
          sortedSlots.foreach { case (slotHash, slotValue) =>
            trie = trie.put(slotHash, slotValue)
          }

          // Write to flat storage: accountHash ++ slotHash → slotValue
          flatBatch = flatBatch.and(localFlatSlotStorage.putSlotsBatch(accountHash, sortedSlots.toSeq))
        }

        // Flush trie nodes to RocksDB
        batchStorage.flush()

        // Commit flat slot writes — single additional RocksDB write batch
        flatBatch.commit()

        val elapsedMs = System.currentTimeMillis() - startMs
        selfRef ! TrieConstructionComplete(accountHashes, totalSlotCount, elapsedMs, constructionStateRoot)
      }
    }(trieBuilderEc).recover { case e: Exception =>
      selfRef ! TrieConstructionFailed(accountHashes, e.getMessage, constructionStateRoot)
    }(trieBuilderEc)
  }

  /** Check if we should trigger a trie construction batch (enough complete accounts or memory pressure). */
  private def maybeStartTrieConstruction(): Unit = {
    val readyNotBuilding = accountsReadyForBuild.diff(accountsInTrieConstruction)
    val memoryPressure = totalBufferedSlots >= maxBufferedSlots

    if (readyNotBuilding.size >= trieConstructionBatchSize || (readyNotBuilding.nonEmpty && memoryPressure)) {
      val batch = readyNotBuilding.take(trieConstructionBatchSize).toSeq
      accountsReadyForBuild --= batch
      log.info(s"Starting async trie construction for ${batch.size} accounts ($totalBufferedSlots buffered slots)")
      buildAccountTriesAsync(batch)
    }
  }

  /** Check if a large account needs incremental flushing (per-account memory limit). */
  private def maybeFlushLargeAccount(accountHash: ByteString): Unit = {
    pendingAccountSlots.get(accountHash).foreach { slots =>
      if (slots.size >= maxSlotsPerAccount) {
        // Large account — build trie incrementally to prevent OOM
        log.info(
          s"Large account ${accountHash.take(4).toArray.map("%02x".format(_)).mkString}: " +
            s"${slots.size} buffered slots exceeds per-account limit ($maxSlotsPerAccount). " +
            s"Triggering incremental trie build."
        )
        accountsReadyForBuild += accountHash
        maybeStartTrieConstruction()
      }
    }
  }

  /** Force build all remaining buffered accounts (e.g., on sync completion or force-complete). */
  private def flushAllPendingTrieBuilds(): Unit = {
    val remaining = accountsReadyForBuild.diff(accountsInTrieConstruction).toSeq
    if (remaining.nonEmpty) {
      accountsReadyForBuild.clear()
      log.info(s"Flushing ${remaining.size} remaining accounts for trie construction")
      buildAccountTriesAsync(remaining)
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

  /** Record a completed account hash: add to in-memory set AND append to file for crash recovery. */
  private def markAccountCompleted(accountHash: ByteString): Unit = {
    if (completedAccountHashes.add(accountHash)) {
      completedAccountsOut.write(accountHash.toArray.padTo(32, 0.toByte), 0, 32)
      completedAccountsFileCount += 1
      // Flush periodically (every 100 completions) to limit data loss on crash
      if (completedAccountsFileCount % 100 == 0) {
        completedAccountsOut.flush()
      }
    }
  }

  /** Skip all queued tasks for a given storageRoot (BUG-SU1 fast-fail path).
    * Called when enough peers have confirmed a root mismatch to declare it definitively unservable.
    * Returns the count of tasks skipped.
    */
  private def skipAllTasksForRoot(storageRoot: ByteString): Int = {
    var skipped = 0
    val keep = mutable.Queue.empty[StorageTask]
    while (tasks.nonEmpty) {
      val t = tasks.dequeue()
      if (t.storageRoot == storageRoot) {
        t.done = true
        t.pending = false
        completedTasks += t
        markAccountCompleted(t.accountHash)
        skipped += 1
      } else {
        keep.enqueue(t)
      }
    }
    tasks.enqueueAll(keep)
    skipped
  }

  /** Shut down the trie builder thread pool and close the completed-accounts file. */
  override def postStop(): Unit = {
    try { completedAccountsOut.flush(); completedAccountsOut.close() } catch { case _: Exception => }
    trieBuilderPool.shutdown()
    super.postStop()
  }

  // Storage management
  private val proofVerifiers = mutable.Map[ByteString, MerkleProofVerifier]()

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

    case InitCompletedStorageAccounts(hashes) =>
      completedAccountHashes ++= hashes
      // Write all recovered hashes to the new file so it's a superset going forward
      hashes.foreach { h =>
        completedAccountsOut.write(h.toArray.padTo(32, 0.toByte), 0, 32)
        completedAccountsFileCount += 1
      }
      completedAccountsOut.flush()
      log.info(s"Initialized ${hashes.size} completed storage accounts from recovery")

    case GetCompletedStorageFilePath =>
      try completedAccountsOut.flush() catch { case _: Exception => }
      sender() ! CompletedStorageFilePath(completedAccountsFile)

    case AddStorageTasks(storageTasks) =>
      tasks.enqueueAll(storageTasks)
      totalStorageContracts += storageTasks.map(_.accountHash).distinct.size
      log.info(
        s"Added ${storageTasks.size} storage tasks to queue (total pending: ${tasks.size}, contracts: $totalStorageContracts)"
      )

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

    case UpdateMaxInFlightPerPeer(newLimit) =>
      if (newLimit != maxInFlightPerPeer) {
        log.info(s"Storage per-peer budget: $maxInFlightPerPeer -> $newLimit")
        maxInFlightPerPeer = newLimit
        if (newLimit > 0) tryRedispatchPendingTasks()
      }

    case StorageRangesResponseMsg(response) =>
      handleResponse(response)

    case StorageTaskComplete(requestId, result) =>
      result match {
        case Right(count) =>
          slotsDownloaded += count
          log.debug(s"Storage task completed: $count slots")
          // Periodic progress summary (every 10K slots)
          if (slotsDownloaded - lastProgressLogAt >= ProgressLogInterval) {
            val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
            val rate = if (elapsed > 0) (slotsDownloaded / elapsed).toLong else 0L
            val completed = completedTasks.map(_.accountHash).toSet.size
            log.info(
              s"Storage progress: $slotsDownloaded slots (${"%.1f".format(bytesDownloaded / 1048576.0)} MB) " +
                s"($completed/$totalStorageContracts contracts, " +
                s"${tasks.size} pending, ${activeTasks.values.map(_._2.size).sum} active, " +
                s"$rate slots/sec)"
            )
            lastProgressLogAt = slotsDownloaded
          }
          self ! StorageCheckCompletion
        case Left(error) =>
          log.warning(s"Storage task failed: $error")
      }

    case StorageCheckCompletion =>
      // Update contract completion progress for the progress monitor
      updateContractProgress()
      // When all downloads complete, flush remaining buffered accounts for trie construction
      if (noMoreTasksExpected && tasks.isEmpty && activeTasks.isEmpty &&
        accountsReadyForBuild.nonEmpty && accountsInTrieConstruction.isEmpty) {
        flushAllPendingTrieBuilds()
      }
      if (isComplete) {
        if (!storageSyncCompleteReported) {
          storageSyncCompleteReported = true
          log.info("Storage range sync complete!")
          snapSyncController ! SNAPSyncController.StorageRangeSyncComplete
        }
      } else if (tasks.nonEmpty) {
        // Try to dispatch pending tasks — per-peer and global limits enforced in dispatchIfPossible().
        // Previously guarded by activeTasks.isEmpty which defeated pipelining.
        maybeRequestPivotRefresh()
        tryRedispatchPendingTasks()
        log.info(
          s"Storage progress [30s]: ${completedAccountHashes.size}/$totalStorageContracts contracts, " +
            s"$slotsDownloaded slots, ${tasks.size} pending, ${activeTasks.size} active"
        )
      } else if (noMoreTasksExpected) {
        log.info(
          s"Storage trie builds in progress: ${completedAccountHashes.size}/$totalStorageContracts contracts, " +
            s"$slotsDownloaded slots, ready=${accountsReadyForBuild.size}, building=${accountsInTrieConstruction.size}"
        )
      }

    case NoMoreStorageTasks =>
      noMoreTasksExpected = true
      log.info(
        s"No more storage tasks expected. Pending: ${tasks.size}, active: ${activeTasks.size}, " +
          s"buffered accounts: ${pendingAccountSlots.size}, ready for build: ${accountsReadyForBuild.size}"
      )
      // Trigger final trie builds if all downloads are done
      if (tasks.isEmpty && activeTasks.isEmpty) {
        flushAllPendingTrieBuilds()
      }
      if (isComplete) {
        if (!storageSyncCompleteReported) {
          storageSyncCompleteReported = true
          log.info("Storage range sync complete!")
          snapSyncController ! SNAPSyncController.StorageRangeSyncComplete
        }
      }

    case ForceCompleteStorage =>
      val abandoned = tasks.size + activeTasks.size
      val bufferedAccounts = pendingAccountSlots.size + accountsReadyForBuild.size
      log.warning(
        s"Force-completing storage sync: $slotsDownloaded slots downloaded, " +
          s"abandoning $abandoned remaining tasks, $bufferedAccounts buffered accounts " +
          s"(healing phase will recover missing data)"
      )
      // Build tries for any fully-downloaded accounts before force-completing
      flushAllPendingTrieBuilds()
      // Discard partially-downloaded accounts (continuations won't arrive)
      pendingAccountSlots.clear()
      totalBufferedSlots = 0
      log.info("Storage range sync force-completed (promoting to healing phase)")
      snapSyncController ! SNAPSyncController.StorageRangeSyncComplete

    case StoragePivotRefreshed(newStateRoot) =>
      log.info(s"Storage pivot refreshed: ${stateRoot.take(4).toHex} -> ${newStateRoot.take(4).toHex}")
      stateRoot = newStateRoot

      // Cancel all in-flight requests: their responses are for the old root and will
      // contaminate stateless detection if processed. Re-queue tasks for the new root,
      // but skip tasks for accounts whose storage has already been fully downloaded —
      // re-downloading completed storage under a new root is wasteful (R2 redundancy fix).
      val cancelledCount = activeTasks.size
      var requeued = 0
      var skippedCompleted = 0
      activeTasks.values.foreach { case (_, batchTasks, _) =>
        batchTasks.foreach { task =>
          if (completedAccountHashes.contains(task.accountHash)) {
            skippedCompleted += 1
          } else {
            task.pending = false
            tasks.enqueue(task)
            requeued += 1
          }
        }
      }
      activeTasks.clear()
      if (cancelledCount > 0) {
        log.info(s"Cancelled $cancelledCount in-flight storage requests (stale root): " +
          s"$requeued re-queued, $skippedCompleted skipped (already completed)")
      }

      // Clear all per-peer adaptive state — fresh start with new root
      statelessPeers.clear()
      pivotRefreshRequested = false
      storageSyncCompleteReported = false
      lastDispatchOrResponseMs = System.currentTimeMillis()
      peerCooldownUntilMs.clear()
      peerConsecutiveTimeouts.clear()
      peerBatchSize.clear()
      peerBatchSuccessStreak.clear()
      peerResponseBytesTarget.clear()
      // NOTE: emptyResponsesByTask is intentionally NOT cleared on pivot refresh (BUG-S1 fix).
      // Clearing it allowed accounts with permanently-unserveable storage roots (e.g. high-frequency
      // contracts whose root was recorded 13,000+ blocks ago — beyond Besu's 8192-block bonsai window)
      // to cycle indefinitely through pivot refreshes. The per-task skip threshold (maxEmptyResponsesPerTask)
      // now correctly accumulates across pivots and permanently skips unserveable accounts after 5 failures,
      // deferring them to the healing phase which reconstructs missing slots via GetTrieNodes.
      proofFailuresByTask.clear()
      // BUG-SU1: Clear stale-root tracking on pivot refresh — new pivot has a different state root
      // so previously-unservable roots may become servable (or will be re-detected quickly).
      staleRootMismatchPeers.clear()
      proofVerifiers.clear()
      recentTaskPeerFailures.clear()

      // Clear two-phase storage buffers — data for old root is stale.
      // Any in-progress trie constructions will complete harmlessly (writes are content-addressed),
      // but we discard the tracking so we don't wait for them.
      pendingAccountSlots.clear()
      accountsReadyForBuild.clear()
      accountsInTrieConstruction.clear()
      totalBufferedSlots = 0

      // Set post-refresh cooldown: peers need time to sync to the new root.
      // Dispatching immediately causes all peers to return empty → marked stateless →
      // another pivot refresh → infinite tight loop (Bug 24).
      postRefreshCooldownUntilMs = System.currentTimeMillis() + postRefreshCooldownMs
      log.info(s"Post-refresh cooldown active for ${postRefreshCooldownMs / 1000}s — waiting for peers to sync to new root")

      // Schedule dispatch after the cooldown period instead of dispatching immediately
      import context.dispatcher
      context.system.scheduler.scheduleOnce(postRefreshCooldownMs.millis) {
        self ! StorageCheckCompletion
      }

    case StorageGetProgress =>
      val stats = StorageRangeCoordinator.SyncStatistics(
        slotsDownloaded = slotsDownloaded,
        bytesDownloaded = bytesDownloaded,
        tasksCompleted = completedTasks.size,
        tasksActive = activeTasks.values.map(_._2.size).sum,
        tasksPending = tasks.size,
        elapsedTimeMs = System.currentTimeMillis() - startTime,
        progress = progress
      )
      sender() ! stats

    case TrieConstructionComplete(accountHashes, totalSlots, elapsedMs, forStateRoot) =>
      if (forStateRoot != stateRoot) {
        // Stale completion from before a pivot refresh — trie nodes are content-addressed
        // so the writes are harmless, but don't track these as completed for the current root.
        log.info(
          s"Ignoring stale trie construction (${accountHashes.size} accounts, root ${forStateRoot.take(4).toHex}) " +
            s"— current root is ${stateRoot.take(4).toHex}"
        )
      } else {
        accountHashes.foreach { hash =>
          accountsInTrieConstruction.remove(hash)
          markAccountCompleted(hash)
        }
        val rate = if (elapsedMs > 0) totalSlots * 1000 / elapsedMs else totalSlots
        log.info(
          s"Trie construction complete: ${accountHashes.size} accounts, $totalSlots slots in ${elapsedMs}ms " +
            s"(${rate} slots/s). Remaining: ${accountsReadyForBuild.size} ready, " +
            s"${accountsInTrieConstruction.size} building, ${pendingAccountSlots.size} buffered"
        )
        maybeStartTrieConstruction()
      }
      self ! StorageCheckCompletion

    case TrieConstructionFailed(accountHashes, error, forStateRoot) =>
      if (forStateRoot != stateRoot) {
        log.debug(s"Ignoring stale trie construction failure (root ${forStateRoot.take(4).toHex})")
      } else {
        log.error(
          s"Trie construction failed for ${accountHashes.size} accounts: $error. " +
            s"Healing phase will recover missing storage."
        )
        accountHashes.foreach { hash =>
          accountsInTrieConstruction.remove(hash)
          pendingAccountSlots.remove(hash)
        }
      }
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

    // R3: Evict expired task-peer failure entries and skip if the front task recently failed with this peer.
    val now = System.currentTimeMillis()
    recentTaskPeerFailures.filterInPlace { case (_, ts) => now - ts < taskPeerCooldownMs }
    if (tasks.nonEmpty) {
      val front = tasks.front
      if (recentTaskPeerFailures.contains((front.accountHash, peer.id.value))) {
        log.debug(s"R3: Skipping task for account ${front.accountHash.take(4).toArray.map("%02x".format(_)).mkString} — " +
          s"peer ${peer.id.value} recently timed out on it")
        return None
      }
    }

    val min = ByteString(Array.fill(32)(0.toByte))
    val max = ByteString(Array.fill(32)(0xff.toByte))
    def isInitialRange(t: StorageTask): Boolean = t.next == min && t.last == max

    val peerBatch = batchSizeFor(peer)

    // snap/1 origin/limit semantics apply to the first account only. To avoid incorrect continuation
    // behavior, only batch tasks that request the initial full range.
    val first = tasks.dequeue()
    val batchTasks: Seq[StorageTask] =
      if (!isInitialRange(first) || peerBatch <= 1) {
        Seq(first)
      } else {
        val buf = mutable.ArrayBuffer[StorageTask](first)
        while (buf.size < peerBatch && tasks.nonEmpty && isInitialRange(tasks.front))
          buf += tasks.dequeue()
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

          case Some(pendingReq) =>
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
      var requeued = 0
      tasks.foreach { task =>
        // BUG-SU1 fast-path: if a proof root mismatch has already been confirmed for this
        // storage root by another peer, skip immediately without incrementing empty counter.
        if (staleRootMismatchPeers.contains(task.storageRoot)) {
          skipped += 1
          task.done = true
          task.pending = false
          completedTasks += task
          markAccountCompleted(task.accountHash)
          log.debug(
            s"Fast-skipping empty-response task for known-stale root ${task.storageRoot.take(4).toHex}: " +
              s"account=${task.accountHash.take(4).toHex}"
          )
        } else {
        val key = StorageTaskKey(task.accountHash, task.next, task.last)
        val attempts = emptyResponsesByTask.getOrElse(key, 0) + 1
        emptyResponsesByTask.update(key, attempts)

        if (attempts >= maxEmptyResponsesPerTask) {
          skipped += 1
          task.done = true
          task.pending = false
          completedTasks += task
          log.warning(
            s"Skipping storage task after $attempts empty StorageRanges replies: " +
              s"account=${task.accountHash.toHex} storageRoot=${task.storageRoot.toHex} range=${task.rangeString}"
          )
        } else {
          requeued += 1
          task.pending = false
          this.tasks.enqueue(task)
          log.debug(
            s"Empty StorageRanges for task (attempt $attempts/$maxEmptyResponsesPerTask); re-queueing: " +
              s"account=${task.accountHash.take(4).toHex} range=${task.rangeString}"
          )
        }
        } // end BUG-SU1 fast-path else
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
    peerConsecutiveTimeouts.remove(peer.id.value)
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

      val verifier = getOrCreateVerifier(task.storageRoot)
      verifier.verifyStorageRange(accountSlots, proofForThisTask, task.next, task.last) match {
        case Left(error) =>
          val key = StorageTaskKey(task.accountHash, task.next, task.last)

          // BUG-SU1: Proof root mismatch is a structural signal — the peer's state cannot serve
          // this storage root (recorded at a stale pivot beyond the pruning window). Two distinct
          // peers confirming the same mismatch is definitive: skip all tasks for this root immediately
          // rather than cycling through 5 failures × N ranges × multiple pivot refreshes.
          val isRootMismatch = error.contains("root mismatch") || error.contains("Proof root")
          if (isRootMismatch) {
            val storageRoot = task.storageRoot
            val mismatchPeers = staleRootMismatchPeers.getOrElseUpdate(storageRoot, mutable.Set.empty)
            mismatchPeers += peer.id.value
            val mismatchCount = mismatchPeers.size
            val available = math.max(knownAvailablePeers.size, 1)

            if (mismatchCount >= math.min(MinPeersForStaleDecision, available)) {
              val skipped = skipAllTasksForRoot(storageRoot)
              log.warning(
                s"Storage root ${storageRoot.take(4).toHex} unservable: $mismatchCount/$available peers " +
                s"returned proof root mismatch (root beyond pruning window). " +
                s"Skipping $skipped tasks immediately — healing phase will recover."
              )
              staleRootMismatchPeers.remove(storageRoot)
              // DO NOT markPeerStateless — peer is healthy, the storage root is the problem
              self ! StorageCheckCompletion
            } else {
              if (mismatchCount == 1)
                log.warning(
                  s"Proof root mismatch for ${task.accountString} from peer ${peer.id.value} " +
                  s"($mismatchCount/$MinPeersForStaleDecision confirmations to declare unservable) — re-queuing"
                )
              recordPeerCooldown(peer, s"root mismatch: $error")
              task.pending = false
              this.tasks.enqueue(task)
            }
          } else {
            // Non-mismatch proof failure: existing per-task failure counter
            val failCount = proofFailuresByTask.getOrElse(key, 0) + 1
            proofFailuresByTask.update(key, failCount)

            if (failCount >= maxProofFailuresPerTask) {
              log.warning(
                s"Storage proof verification failed ${failCount}x for account ${task.accountString}, " +
                  s"skipping (healing will recover). Last error: $error"
              )
              proofFailuresByTask.remove(key)
              // Mark task done — healing phase will fill any gaps
              task.done = true
              completedTasks += task
              markAccountCompleted(task.accountHash)
            } else {
              if (failCount == 1) {
                log.warning(s"Storage proof verification failed for account ${task.accountString}: $error")
              }
              recordPeerCooldown(peer, s"verification failed: $error")
              adjustResponseBytesOnFailure(peer, s"verification failed: $error")
              task.pending = false
              this.tasks.enqueue(task)
            }
          }

        case Right(_) =>
          val slotBytes = accountSlots.map { case (hash, value) => hash.size + value.size }.sum
          totalReceivedBytes += slotBytes

          if (accountSlots.nonEmpty) {
            // Buffer raw slots — Phase 1 of two-phase storage
            val slotBuffer = pendingAccountSlots.getOrElseUpdate(task.accountHash, mutable.ArrayBuffer.empty)
            slotBuffer ++= accountSlots
            totalBufferedSlots += accountSlots.size
            slotsDownloaded += accountSlots.size
            bytesDownloaded += accountSlots.map { case (hash, value) => hash.size + value.size }.sum

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

              // Per-account memory limit: flush large accounts incrementally
              maybeFlushLargeAccount(task.accountHash)
            } else {
              // Account fully downloaded — decide: flat-only or full trie build
              if (deferredMerkleization || slotBuffer.size < smallContractThreshold) {
                // Flat-only write — skip MPT construction during download.
                // deferredMerkleization: ALL contracts skip MPT (Paprika approach).
                // smallContractThreshold: ~95% of ETC contracts have < 1024 slots.
                // MPT built from flat data in post-download Merkleization pass or healing.
                writeSmallContractFlatOnly(task.accountHash, slotBuffer)
                markAccountCompleted(task.accountHash)
                log.debug(
                  s"Account ${task.accountHash.take(4).toArray.map("%02x".format(_)).mkString}: " +
                    s"flat-only write (${slotBuffer.size} slots" +
                    s"${if (deferredMerkleization) ", deferred merkleization" else ", small contract"})"
                )
              } else {
                // Large contract with deferred merkleization disabled — queue for async trie
                accountsReadyForBuild += task.accountHash
                log.debug(
                  s"Account ${task.accountHash.take(4).toArray.map("%02x".format(_)).mkString} fully buffered " +
                    s"(${slotBuffer.size} total slots) — queued for trie construction"
                )
              }
            }

            task.done = true
            task.pending = false
            completedTasks += task

            // Check if we should trigger a batch trie build
            maybeStartTrieConstruction()
          } else {
            // No slots to store — mark task done
            task.done = true
            task.pending = false
            completedTasks += task
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

  private def getOrCreateVerifier(storageRoot: ByteString): MerkleProofVerifier =
    proofVerifiers.getOrElseUpdate(storageRoot, MerkleProofVerifier(storageRoot))

  private def handleTimeout(requestId: BigInt): Unit = {
    activeTasks.remove(requestId).foreach { case (peer, batchTasks, _) =>
      log.warning(s"Storage range request timeout for ${batchTasks.size} accounts from peer ${peer.id.value}")
      recordPeerCooldown(peer, "request timeout")
      adjustResponseBytesOnFailure(peer, "request timeout")

      // Track consecutive timeouts — on ETC mainnet, peers silently stop responding when
      // the snap serve window expires. After N consecutive timeouts, treat as stateless.
      val count = peerConsecutiveTimeouts.getOrElse(peer.id.value, 0) + 1
      peerConsecutiveTimeouts.update(peer.id.value, count)
      if (count >= consecutiveTimeoutThreshold) {
        log.info(s"Peer ${peer.id.value} hit $count consecutive storage timeouts — treating as stateless")
        markPeerStateless(peer)
      }

      val failTs = System.currentTimeMillis()
      batchTasks.foreach { task =>
        task.pending = false
        // R3: Record this task-peer failure so we don't immediately re-dispatch to the same peer
        recentTaskPeerFailures.put((task.accountHash, peer.id.value), failTs)
        tasks.enqueue(task)
      }
    }
    // Re-dispatch re-queued tasks to any known available peer that isn't stateless or on cooldown.
    tryRedispatchPendingTasks()
  }

  /** Dispatch up to maxInFlightPerPeer requests to a single peer (pipelining). */
  private def dispatchIfPossible(peer: Peer): Unit = {
    var inflight = inFlightForPeer(peer)
    while (tasks.nonEmpty && inflight < maxInFlightPerPeer && activeTasks.size < maxInFlightRequests) {
      requestNextRanges(peer) match {
        case Some(_) => inflight += 1
        case None    => return
      }
    }
  }

  private def tryRedispatchPendingTasks(): Unit = {
    if (tasks.isEmpty) return
    if (isPostRefreshCooldownActive) return
    if (pivotRefreshRequested) return
    val eligiblePeers = knownAvailablePeers
      .filterNot(p => isPeerStateless(p) || isPeerCoolingDown(p))
      .toList
    if (eligiblePeers.isEmpty) return

    for (peer <- eligiblePeers if tasks.nonEmpty)
      dispatchIfPossible(peer)
  }

  private def progress: Double = {
    val activeCount = activeTasks.values.map(_._2.size).sum
    val total = completedTasks.size + activeCount + tasks.size
    if (total == 0) 1.0
    else completedTasks.size.toDouble / total
  }

  private def isComplete: Boolean =
    noMoreTasksExpected && tasks.isEmpty && activeTasks.isEmpty &&
      accountsReadyForBuild.isEmpty && accountsInTrieConstruction.isEmpty && pendingAccountSlots.isEmpty

  /** Update contract completion counts and send progress to controller. */
  private def updateContractProgress(): Unit = {
    if (totalStorageContracts <= 0) return
    // Count unique completed accounts from completedTasks
    val uniqueCompleted = completedTasks.map(_.accountHash).toSet.size
    if (uniqueCompleted != completedAccountHashes.size) {
      completedAccountHashes.clear()
      completedTasks.foreach(t => completedAccountHashes.add(t.accountHash))
      snapSyncController ! SNAPSyncController.ProgressStorageContracts(
        completedAccountHashes.size,
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
      deferredMerkleization: Boolean = true
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
        deferredMerkleization = deferredMerkleization
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
