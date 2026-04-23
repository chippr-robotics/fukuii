package com.chipprbots.ethereum.blockchain.sync.snap.actors

import org.apache.pekko.actor.{Actor, ActorLogging, ActorRef, Props, SupervisorStrategy, OneForOneStrategy}
import org.apache.pekko.actor.SupervisorStrategy._
import org.apache.pekko.util.ByteString

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
    initialMaxInFlightPerPeer: Int = 1, // Besu-aligned D11: 1 request per peer, no pipelining
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
    mutable.Map[
      BigInt,
      (Peer, Seq[StorageTask], BigInt, ByteString)
    ]() // requestId -> (peer, tasks, requestedBytes, dispatchRoot)
  private val completedTasks = mutable.ArrayBuffer[StorageTask]()

  // Track consecutive timeouts per peer. When a peer hits the threshold, it's marked stateless.
  // This handles the case where ETC mainnet peers silently stop responding (timeout) when
  // their snap serve window expires, rather than returning empty responses with proofs.
  private val peerConsecutiveTimeouts = mutable.Map[String, Int]()
  private val consecutiveTimeoutThreshold = 3 // Mark stateless after 3 consecutive timeouts

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
  private var totalStorageContracts: Int = 0
  private val completedAccountHashes = mutable.Set[ByteString]()

  // Pivot refresh backoff: prevents rapid refresh loops when no peers can serve any recent root.
  // After each unproductive refresh (one that doesn't yield real slot data), the backoff interval
  // doubles from 60s up to 5 minutes. Resets to 0 when we receive actual storage slots.
  private var consecutiveUnproductiveRefreshes: Int = 0
  private var lastPivotRefreshTimeMs: Long = 0
  private val minRefreshIntervalMs: Long = 60000L // 1 minute minimum between refreshes
  private val maxRefreshIntervalMs: Long = 300000L // 5 minutes maximum backoff

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
    statelessPeers.add(peer.id.value)
    log.info(
      s"Peer ${peer.id.value} marked stateless for storage root ${stateRoot.take(4).toHex} " +
        s"(${statelessPeers.size}/${knownAvailablePeers.size} stateless)"
    )
    maybeRequestPivotRefresh()
  }

  private def maybeRequestPivotRefresh(): Unit = {
    // Besu-aligned: only trigger pivot refresh when ALL known peers are stateless.
    // No time-based stagnation trigger — Besu has no equivalent stagnation watchdog.
    if (pivotRefreshRequested) return
    val allStateless = knownAvailablePeers.nonEmpty &&
      knownAvailablePeers.forall(p => statelessPeers.contains(p.id.value))

    if (allStateless) {
      val now = System.currentTimeMillis()
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

  // Besu-aligned D12: fixed batch size (maxAccountsPerBatch from config), no per-peer adaptation.

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

  // Large storage pipeline: maximum number of parallel sub-ranges to split a continuation into.
  // Besu: RangeManager.generateRanges() uses a similar approach. Capped at 8 to avoid explosion.
  private val MaxLargeStorageSplits: Int = 8

  // Statistics
  private var slotsDownloaded: Long = 0
  private var bytesDownloaded: Long = 0
  private val startTime = System.currentTimeMillis()

  // Besu-aligned D12: fixed request size from config, no per-peer adaptive ratcheting.
  private val requestResponseBytes: BigInt = BigInt(configInitialResponseBytes)

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

  /** Maximum buffered slots across all accounts before forcing an incremental trie build. Prevents OOM when downloading
    * mainnet with millions of storage slots. At ~64 bytes per slot (32 hash + 32 value), 1M slots ≈ 64MB.
    */
  private val maxBufferedSlots: Long = 1000000
  private var totalBufferedSlots: Long = 0

  /** Per-account memory limit: flush large accounts incrementally when they exceed this. Mainnet DeFi contracts can
    * have 10-50M slots. Without per-account limits, a single contract could buffer several GB before trie construction
    * starts. At 64 bytes/slot, 500K slots = ~32MB per account.
    */
  private val maxSlotsPerAccount: Long = 500000

  /** Threshold for triggering incremental builds of complete accounts. When we have this many complete accounts
    * buffered, build their tries without waiting.
    */
  private val trieConstructionBatchSize = 64

  /** Accounts whose slots have been fully downloaded (no continuation) and are ready for trie construction. Using
    * LinkedHashSet for dedup + insertion order (FIFO processing).
    */
  private val accountsReadyForBuild = mutable.LinkedHashSet[ByteString]()

  /** StackTrie shortcut threshold: contracts with fewer slots than this skip MPT construction entirely — only flat
    * storage is written. The trie is reconstructed lazily from flat data during the healing phase or deferred
    * Merkleization pass. ~95% of ETC contracts qualify.
    */
  private val smallContractThreshold = 1024

  /** Bounded thread pool for trie construction — controls RocksDB write pressure. Using 3 threads avoids overwhelming
    * the single-threaded RocksDB compaction while still enabling parallel trie builds. Unbounded Futures on
    * sync-dispatcher caused thread starvation under heavy load.
    */
  private val trieBuilderPool: java.util.concurrent.ExecutorService =
    java.util.concurrent.Executors.newFixedThreadPool(
      3,
      (r: Runnable) => {
        val t = new Thread(r, "storage-trie-builder")
        t.setDaemon(true)
        t
      }
    )
  private val trieBuilderEc = scala.concurrent.ExecutionContext.fromExecutorService(trieBuilderPool)

  /** Write only to flat storage for small contracts — skip MPT construction. Called synchronously from the actor for
    * accounts that arrived in a single response with fewer slots than smallContractThreshold. These are ~95% of ETC
    * contracts. The MPT will be built lazily from flat data during healing or post-sync Merkleization.
    */
  private def writeSmallContractFlatOnly(
      accountHash: ByteString,
      slots: mutable.ArrayBuffer[(ByteString, ByteString)]
  ): Unit = {
    val sorted = slots.sortBy(_._1)(ByteStringOrdering)
    flatSlotStorage.putSlotsBatch(accountHash, sorted.toSeq).commit()
    pendingAccountSlots.remove(accountHash)
    totalBufferedSlots -= slots.size
  }

  /** Build tries for a batch of complete accounts on a background thread. Sorts slots by key for better trie locality,
    * builds tries, and flushes to storage. Uses a batch-local DeferredWriteMptStorage to avoid thread-safety issues
    * with the shared mptStorage — each batch gets its own write buffer that flushes independently. Trie nodes and flat
    * slot data are written in a single combined RocksDB batch.
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
  private def maybeFlushLargeAccount(accountHash: ByteString): Unit =
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

  /** Shut down the trie builder thread pool when the actor stops. */
  override def postStop(): Unit = {
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
      log.info(s"Storage per-peer budget: $maxInFlightPerPeer -> $newLimit")
      maxInFlightPerPeer = newLimit
      if (newLimit > 0) tryRedispatchPendingTasks()

    case StorageRangesResponseMsg(response) =>
      handleResponse(response)

    case StorageTaskComplete(requestId, result) =>
      result match {
        case Right(count) =>
          slotsDownloaded += count
          log.info(s"Storage task completed: $count slots")
          self ! StorageCheckCompletion
        case Left(error) =>
          log.warning(s"Storage task failed: $error")
      }

    case StorageCheckCompletion =>
      // Update contract completion progress for the progress monitor
      updateContractProgress()
      // When all downloads complete, flush remaining buffered accounts for trie construction
      if (
        noMoreTasksExpected && tasks.isEmpty && activeTasks.isEmpty &&
        accountsReadyForBuild.nonEmpty && accountsInTrieConstruction.isEmpty
      ) {
        flushAllPendingTrieBuilds()
      }
      if (isComplete) {
        log.info("Storage range sync complete!")
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
          s"buffered accounts: ${pendingAccountSlots.size}, ready for build: ${accountsReadyForBuild.size}"
      )
      // Trigger final trie builds if all downloads are done
      if (tasks.isEmpty && activeTasks.isEmpty) {
        flushAllPendingTrieBuilds()
      }
      if (isComplete) {
        log.info("Storage range sync complete!")
        snapSyncController ! SNAPSyncController.StorageRangeSyncComplete
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
      // contaminate stateless detection if processed. Re-queue tasks for the new root.
      val cancelledCount = activeTasks.size
      activeTasks.values.foreach { case (_, batchTasks, _, _) =>
        batchTasks.foreach { task =>
          task.pending = false
          tasks.enqueue(task)
        }
      }
      activeTasks.clear()
      if (cancelledCount > 0) {
        log.info(s"Cancelled $cancelledCount in-flight storage requests (stale root)")
      }

      // Clear per-peer state — fresh start with new root
      statelessPeers.clear()
      pivotRefreshRequested = false
      peerCooldownUntilMs.clear()
      peerConsecutiveTimeouts.clear()
      // Besu-aligned D12: no per-peer batch size or response byte maps to clear.
      emptyResponsesByTask.clear()
      proofVerifiers.clear()

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
          completedAccountHashes.add(hash)
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

    val min = ByteString(Array.fill(32)(0.toByte))
    val max = ByteString(Array.fill(32)(0xff.toByte))
    def isInitialRange(t: StorageTask): Boolean = t.next == min && t.last == max

    // Besu-aligned D12: fixed batch size (maxAccountsPerBatch from config), no per-peer adaptation.
    val peerBatch = maxAccountsPerBatch

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

    val requestedBytes = requestResponseBytes // Besu-aligned D12: fixed size, no per-peer ratcheting
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
    activeTasks.put(requestId, (peer, batchTasks, requestedBytes, stateRoot))

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

              case Some((peer, batchTasks, requestedBytes, _)) =>
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
      // Besu-aligned D12: no per-peer batch size or response byte ratcheting.

      // Track empties per task to avoid re-queueing forever.
      // If the same task yields empty responses repeatedly, skip it with a loud warning.
      var skipped = 0
      var requeued = 0
      tasks.foreach { task =>
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

    // Besu-aligned D12: no adaptive batch scaling.

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
          log.warning(s"Storage proof verification failed for account ${task.accountString}: $error")
          recordPeerCooldown(peer, s"verification failed: $error")
          // Besu-aligned D12: no adaptive byte budget ratcheting.
          task.pending = false
          this.tasks.enqueue(task)

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
              // fetchLargeStorageDataPipeline: split the remaining range into parallel sub-ranges.
              // Besu: RangeManager.generateRanges(missingRightElement, endKeyHash, nbRanges).
              // More sub-ranges → more peer parallelism → prevents tail-stuck on large-storage contracts.
              val remainingStart = StorageTask.incrementHash32Public(lastSlot)
              val remainingEnd = task.last
              val numSplits = math.max(1, math.min(activePeerCount(), MaxLargeStorageSplits))
              val subRanges = splitHashRange(remainingStart, remainingEnd, numSplits)
              subRanges.foreach { case (subStart, subEnd) =>
                // Create fresh task (don't copy runtime state — pending/done/slots/proof must start clean)
                this.tasks.enqueue(StorageTask(task.accountHash, task.storageRoot, subStart, subEnd))
              }
              if (numSplits > 1) {
                log.info(
                  s"LARGE_STORAGE: split remaining range into $numSplits sub-ranges for account ${task.accountString}"
                )
              } else {
                log.debug(
                  s"Created continuation task for account ${task.accountString} (partial range, proof present)"
                )
              }

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
                completedAccountHashes.add(task.accountHash)
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

    // Besu-aligned D12: no adaptive byte budget adjustment.

    // Check completion after processing all served tasks
    self ! StorageCheckCompletion

    // Immediately pipeline more work to this peer — don't wait for StoragePeerAvailable
    dispatchIfPossible(peer)
  }

  private def getOrCreateVerifier(storageRoot: ByteString): MerkleProofVerifier =
    proofVerifiers.getOrElseUpdate(storageRoot, MerkleProofVerifier(storageRoot))

  private def handleTimeout(requestId: BigInt): Unit = {
    activeTasks.remove(requestId).foreach { case (peer, batchTasks, _, dispatchRoot) =>
      log.warning(s"Storage range request timeout for ${batchTasks.size} accounts from peer ${peer.id.value}")
      recordPeerCooldown(peer, "request timeout")
      // Besu-aligned D12: no adaptive byte budget ratcheting.

      // Besu-aligned: only count timeouts toward stateless detection for the CURRENT root.
      // After pivot refresh, requests dispatched under the old root time out harmlessly —
      // they don't mean the peer can't serve the new root.
      // Equivalent to Besu's SnapSyncProcessState.isExpired() check in CompleteTaskStep.
      // Matches AccountRangeCoordinator's BUG-17 guard (task.rootHash == stateRoot).
      if (dispatchRoot == stateRoot) {
        val count = peerConsecutiveTimeouts.getOrElse(peer.id.value, 0) + 1
        peerConsecutiveTimeouts.update(peer.id.value, count)
        if (count >= consecutiveTimeoutThreshold) {
          log.info(s"Peer ${peer.id.value} hit $count consecutive storage timeouts — treating as stateless")
          markPeerStateless(peer)
        }
      } else {
        log.info(
          s"Ignoring timeout from stale-root storage request " +
            s"(dispatch root ${dispatchRoot.take(4).toHex} != current ${stateRoot.take(4).toHex})"
        )
      }

      batchTasks.foreach { task =>
        task.pending = false
        tasks.enqueue(task)
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

  /** Count of eligible (non-stateless, non-cooling-down) peers. Used to size parallel sub-range splits. */
  private def activePeerCount(): Int =
    knownAvailablePeers.count(p => !isPeerStateless(p) && !isPeerCoolingDown(p)).max(1)

  /** Split the hash range [start, end] into n equal sub-ranges.
    *
    * Implements the fetchLargeStorageDataPipeline pattern from Besu (RangeManager.generateRanges): when a storage
    * response is partial (proof present), split the remaining range into multiple parallel sub-ranges. Each sub-range
    * can be served by a different peer, breaking the sequential-continuation bottleneck that causes tail-stuck stalls.
    *
    * @param start
    *   32-byte start hash (inclusive)
    * @param end
    *   32-byte end hash (inclusive)
    * @param n
    *   number of sub-ranges to create
    * @return
    *   sequence of (subStart, subEnd) pairs, each pair covering 1/n of the range
    */
  private def splitHashRange(start: ByteString, end: ByteString, n: Int): Seq[(ByteString, ByteString)] = {
    require(start.length == 32 && end.length == 32, "splitHashRange requires 32-byte hashes")
    val startBig = BigInt(1, start.toArray) // unsigned big-endian
    val endBig = BigInt(1, end.toArray)

    if (n <= 1 || startBig >= endBig) return Seq((start, end))

    val rangeSize = endBig - startBig + 1
    val subSize = rangeSize / n
    if (subSize == 0) return Seq((start, end)) // range too narrow to split meaningfully

    (0 until n).map { i =>
      val subStart = startBig + BigInt(i) * subSize
      val subEnd = if (i == n - 1) endBig else startBig + BigInt(i + 1) * subSize - 1
      (bigIntTo32Bytes(subStart), bigIntTo32Bytes(subEnd))
    }
  }

  /** Encode a BigInt as a 32-byte big-endian ByteString (unsigned, left-padded with zeros). */
  private def bigIntTo32Bytes(n: BigInt): ByteString = {
    val raw = n.toByteArray // two's complement, may have leading 0x00 sign byte
    val trimmed = if (raw.head == 0) raw.tail else raw // strip sign byte if present
    if (trimmed.length == 32) ByteString(trimmed)
    else ByteString(Array.fill(32 - trimmed.length)(0.toByte) ++ trimmed)
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
      initialMaxInFlightPerPeer: Int = 1, // Besu-aligned D11: 1 request per peer, no pipelining
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
