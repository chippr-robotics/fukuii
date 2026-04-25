package com.chipprbots.ethereum.blockchain.sync.snap.actors

import org.apache.pekko.actor.{Actor, ActorLogging, ActorRef, Props, SupervisorStrategy, OneForOneStrategy}
import org.apache.pekko.actor.SupervisorStrategy._
import org.apache.pekko.util.ByteString
import org.bouncycastle.util.encoders.Hex

import scala.collection.mutable
import scala.concurrent.duration._

import com.chipprbots.ethereum.blockchain.sync.snap._
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.PeerActor
import com.chipprbots.ethereum.network.p2p.messages.SNAP.{GetTrieNodes, TrieNodes}
import com.chipprbots.ethereum.network.p2p.messages.WireProtocol.Disconnect

/** TrieNodeHealingCoordinator manages the healing phase of SNAP sync.
  *
  * State healing downloads missing trie nodes (intermediate branch/extension nodes that snap sync skips) by requesting
  * them from peers via GetTrieNodes. Nodes are identified by their trie path (HP-encoded) and stored directly by hash
  * in the node storage.
  *
  * The coordinator receives missing node descriptions from SNAPSyncController (which discovers them via trie walks) and
  * dispatches GetTrieNodes requests to available peers.
  */
class TrieNodeHealingCoordinator(
    private var stateRoot: ByteString,
    networkPeerManager: ActorRef,
    requestTracker: SNAPRequestTracker,
    mptStorage: MptStorage,
    batchSize: Int,
    snapSyncController: ActorRef,
    concurrency: Int
) extends Actor
    with ActorLogging {

  import Messages._

  // Task management — each task has a pathset (for GetTrieNodes) and a hash (for verification)
  // depth + priority enable DFS traversal: priority = parentPriority * 16 + childIndex (Besu InMemoryTasksPriorityQueues)
  private case class HealingEntry(pathset: Seq[ByteString], hash: ByteString, retries: Int = 0, depth: Int = 0, priority: Long = 0L)
  // DFS priority queue: min-priority first (root=0, leftmost child first), depth DESC breaks ties.
  // Besu uses a min-heap over (priority, -depth). This keeps the working set O(depth×16) ≈ 1K entries vs BFS O(N).
  private val pendingTasks: mutable.PriorityQueue[HealingEntry] =
    mutable.PriorityQueue.empty[HealingEntry](Ordering.by(e => (-e.priority, e.depth)))
  private var completedTaskCount: Int = 0
  private var abandonedTaskCount: Int = 0

  // Per-task retry limit: after this many timeouts/failures, skip the task.
  // Aligned with Besu MAX_RETRIES=4 (RetryingGetTrieNodeFromPeerTask.java).
  // 4 retries × 60s timeout = 4 min max per node before abandonment.
  // Prior value of 20 locked all workers for 20 min on unserviceable nodes (BUG-HEAL-11).
  private val maxRetriesPerTask: Int = 4

  // Global stagnation detection: if no nodes healed for this duration, declare
  // healing complete with a warning. Prevents infinite loops when all peers lack
  // GetTrieNodes support (ETH68 networks). Regular sync fetches missing nodes on-demand.

  // Active request tracking: maps requestId -> (tasks, peer, requestedBytes, sentAtMs)
  private case class ActiveRequest(
      tasks: Seq[HealingEntry],
      peer: Peer,
      requestedBytes: BigInt,
      sentAtMs: Long = System.currentTimeMillis()
  )
  private val activeRequests = mutable.Map[BigInt, ActiveRequest]()

  // Concurrency: per-peer limit (like StorageRangeCoordinator) + global safety cap
  private val maxConcurrentRequests = concurrency
  private var maxInFlightPerPeer: Int = 1 // Besu-aligned D11: 1 request per peer, no pipelining

  // Statistics
  private var totalNodesHealed: Int = 0
  private var totalBytesReceived: Long = 0
  private val startTime = System.currentTimeMillis()
  private var lastProgressLogAt: Long = 0   // for 500-node interval progress log
  private val ProgressLogInterval: Long = 500
  private var lastPulseHealedCount: Int = 0 // for 2-min HEAL-PULSE velocity logging
  // Rolling 60s window for recent throughput (matches controller's SyncProgressMonitor pattern)
  private val recentHistory: mutable.Queue[(Long, Long)] = mutable.Queue.empty
  private val RecentWindowMs: Long = 60_000

  // Adaptive healing throttle (geth p2p/msgrate alignment)
  // When pending nodes exceed 2× the processing rate, throttle increases (slow down requests).
  // When below, throttle decreases (speed up). Prevents pending queue overflow / OOM.
  private var healRate: Double = 0.0 // items/sec EMA
  private var healThrottle: Double = 1.0 // divisor (1 = full speed, 4096 = one node at a time)
  private var healPending: Long = 0 // nodes queued for DB write (rawNodeBuffer.size)
  private var lastThrottleAdjustMs: Long = System.currentTimeMillis()

  private val ThrottleIncrease = 1.33
  private val ThrottleDecrease = 1.25
  private val MaxThrottle = 16.0
  private val MinThrottle = 1.0
  private val RateMeasurementImpact = 0.005 // geometric EMA weight per node

  // Track last known available peers for re-dispatch after failures
  private val knownAvailablePeers = mutable.Set[Peer]()

  // Dedup set for pending tasks — prevents the same missing node from being queued multiple times
  private val pendingHashSet = mutable.Set[ByteString]()

  // Session-level stateless tracking. Cleared on HealingPeerAvailable eviction (~1s).
  // Controls immediate dispatch eligibility; real cooldown is peerCooldownUntilMs (10s).
  private val statelessPeers = mutable.Set[String]()
  // Persistent root-failure tracking — survives HealingPeerAvailable ticks, cleared only on
  // pivot refresh. Tracks which peer IDs have exhausted their retries on the root node specifically.
  // When all known peers have failed on root, HealingAllPeersStateless fires (pivot refresh).
  // Mirrors Besu's WORLD_STATE_ROOT_NOT_AVAILABLE signal from TrieNodeHealingStep.
  private val peersFailedOnRoot = mutable.Set[String]()
  // Per-task peer exclusion — mirrors Besu AbstractRetryingSwitchingPeerTask.triedPeers.
  // Maps task hash → set of peer IDs that have already been tried for that hash.
  // Prevents a node from being assigned to the same peer repeatedly when other peers
  // haven't tried it yet. Cleared on pivot refresh (coordinator is recreated on pivot
  // switch, so field starts fresh automatically; explicit clear in HealingPivotRefreshed
  // for safety).
  private val triedPeersForTask = mutable.HashMap.empty[ByteString, mutable.Set[String]]
  // Besu-aligned PeerReputation: consecutive GetTrieNodes timeout counter per peer.
  // After SnapTimeoutDisconnectThreshold consecutive timeouts, disconnect the peer.
  // Mirrors Besu PeerReputation.recordRequestTimeout() with TIMEOUT_THRESHOLD=5.
  // Resets to 0 on any successful GetTrieNodes response (Besu: recordUsefulResponse).
  // Cleared on pivot refresh. Static peers (configured SNAP servers) are exempt.
  private val consecutiveGetTrieNodeTimeouts = mutable.HashMap.empty[String, Int]
  private val SnapTimeoutDisconnectThreshold = 5
  // Global consecutive-timeout give-up: if ALL dispatched requests keep timing out with no
  // successful responses, the coordinator is making zero progress (ETC SNAP peers not serving
  // old pivot state). After this many consecutive timeouts across all peers, force-complete.
  private var globalConsecutiveTimeouts: Int = 0
  private val MaxGlobalConsecutiveTimeouts: Int = 20
  private var pivotRefreshRequested: Boolean = false
  // Post-pivot-refresh cooldown: prevents immediate re-dispatch before peers sync to new root.
  // Without this, all peers return empty → stateless → another HealingAllPeersStateless → tight loop.
  private var postRefreshCooldownUntilMs: Long = 0L
  private val postRefreshCooldownMs: Long = 10_000L // 10s (matches StorageRangeCoordinator)

  // Besu-aligned D12: fixed request size, no per-peer adaptive ratcheting.
  // Besu uses a fixed REQUEST_SIZE in RequestDataStep.java with no per-peer tracking.
  private val requestResponseBytes: BigInt = 512 * 1024

  // HW1 self-feeding: tracks total missing trie children discovered across all healed nodes
  private var childrenDiscoveredTotal: Int = 0

  // D4 root-retry: cumulative count of times the state root node has exhausted per-peer retries.
  // The state root cannot be recovered by regular sync on-demand — never permanently abandon it.
  private var rootFetchFailures: Int = 0
  // Stagnation escalation: consecutive HealingStagnationCheck ticks where pendingTasks.nonEmpty
  // but activeRequests.isEmpty. After 5 ticks (10 minutes), force-complete healing to avoid
  // indefinite stall when all SNAP peers disconnect and no new ones connect.
  private var consecutiveIdleChecks: Int = 0

  // Peer cooldown
  private val peerCooldownUntilMs = mutable.Map[String, Long]()
  private val peerCooldownDefault = 10.seconds // 30s was too aggressive for ETC's sparse peer set

  private def isPeerCoolingDown(peer: Peer): Boolean =
    peerCooldownUntilMs.get(peer.id.value).exists(_ > System.currentTimeMillis())

  private def recordPeerCooldown(peer: Peer, reason: String): Unit = {
    // OPT-P5: Static peers (core-geth, Besu configured as static) get a shorter cooldown —
    // trusted infrastructure should be re-tried sooner after transient failures.
    val cooldown = if (peer.isStatic) 5.seconds else peerCooldownDefault
    val until = System.currentTimeMillis() + cooldown.toMillis
    peerCooldownUntilMs.put(peer.id.value, until)
    log.debug(s"Cooling down peer ${peer.id.value} for ${cooldown.toSeconds}s (static=${peer.isStatic}): $reason")
  }

  /** Count in-flight requests for a given peer (pipelining support). */
  private def inFlightForPeer(peer: Peer): Int =
    activeRequests.values.count(_.peer.id == peer.id)

  /** Dispatch up to maxInFlightPerPeer requests to a single peer (pipelining). */
  private def dispatchIfPossible(peer: Peer): Unit = {
    if (pivotRefreshRequested) return
    val nowMs = System.currentTimeMillis()
    if (nowMs < postRefreshCooldownUntilMs) {
      log.debug(s"[HEAL] Dispatch to ${peer.id.value} deferred — post-refresh cooldown (${(postRefreshCooldownUntilMs - nowMs) / 1000}s remaining)")
      return
    }
    if (statelessPeers.contains(peer.id.value)) return
    if (isPeerCoolingDown(peer)) return
    var inflight = inFlightForPeer(peer)
    while (pendingTasks.nonEmpty && inflight < maxInFlightPerPeer && activeRequests.size < maxConcurrentRequests) {
      requestNextBatch(peer) match {
        case Some(_) => inflight += 1
        case None    => return
      }
    }
  }

  // Batched raw node storage: accumulate nodes and flush asynchronously
  private val rawNodeBuffer = mutable.ArrayBuffer[(ByteString, Array[Byte])]()
  private val rawFlushThreshold = 1000
  private var flushing: Boolean = false

  // Internal message for async flush completion
  private case class FlushComplete(count: Int)
  // Periodic stagnation and no-activity check (every 2 minutes)
  private case object HealingStagnationCheck

  /** Synchronous flush — used only for final completion flush (small buffer, safe to block). */
  private def flushRawNodesSync(): Unit =
    if (rawNodeBuffer.nonEmpty) {
      mptStorage.storeRawNodes(rawNodeBuffer.toSeq)
      mptStorage.persist()
      val count = rawNodeBuffer.size
      rawNodeBuffer.clear()
      log.info(s"Flushed $count healed nodes to disk (total: $totalNodesHealed)")
    }

  /** Async flush — copies buffer, clears it, writes on background thread. */
  private def flushRawNodesAsync(): Unit =
    if (rawNodeBuffer.nonEmpty && !flushing) {
      flushing = true
      val nodes = rawNodeBuffer.toSeq
      rawNodeBuffer.clear()
      import scala.concurrent.{Future, blocking}
      val selfRef = self
      Future {
        blocking {
          mptStorage.storeRawNodes(nodes)
          mptStorage.persist()
          nodes.size
        }
      }(context.dispatcher).foreach(n => selfRef ! FlushComplete(n))(context.dispatcher)
    }

  override def preStart(): Unit = {
    log.info(s"TrieNodeHealingCoordinator starting (concurrency=$concurrency)")
    import context.dispatcher
    context.system.scheduler.scheduleWithFixedDelay(2.minutes, 2.minutes, self, HealingStagnationCheck)
  }

  override val supervisorStrategy: SupervisorStrategy =
    OneForOneStrategy(maxNrOfRetries = 3, withinTimeRange = 1.minute) { case _: Exception =>
      log.warning("Healing worker failed, restarting")
      Restart
    }

  override def receive: Receive = {
    case StartTrieNodeHealing(root) =>
      log.info(s"Starting trie node healing for state root ${Hex.toHexString(root.take(8).toArray)}")
      // ARCH-ROOT-SEED: Seed immediately with root node (Besu-style top-down discovery).
      // Prior: waited 50+ min for walk results. Now: healing starts instantly.
      // When root is healed, discoverMissingChildren() discovers all children inline.
      // Walk becomes validation-only — its results remain additive (dedup prevents duplicates).
      val emptyPath = ByteString(com.chipprbots.ethereum.mpt.HexPrefix.encode(Array.empty[Byte], isLeaf = false))
      queueNodes(Seq((Seq(emptyPath), root)))
      log.info(
        s"[HEAL] Root ${Hex.toHexString(root.take(8).toArray)} seeded with empty path — " +
        s"inline child discovery will populate the work queue top-down (Besu-aligned)"
      )
      // If root was already in storage (queueNodes skipped it), pendingTasks=0 and
      // activeRequests=0 immediately. Without this, HealingCheckCompletion is never sent
      // and the coordinator waits indefinitely for a response that never comes.
      self ! HealingCheckCompletion

    case QueueMissingNodes(nodes) =>
      log.info(s"[HEAL-RESUME] Received ${nodes.size} persisted missing nodes from prior session")
      queueNodes(nodes)
      // Immediately dispatch to any known available peers
      tryRedispatchPendingTasks()
      if (pendingTasks.isEmpty)
        log.info(
          s"[HEAL] All ${nodes.size} resumed nodes already present in trie DB — " +
            s"no network requests needed, proceeding to trie walk"
        )
      self ! HealingCheckCompletion

    case HealingPeerAvailable(peer) =>
      // Besu-aligned: no persistent IP-level blocking. Evict stale entry for same physical
      // node (reconnection creates new PeerId) and re-admit unconditionally.
      val evicted = knownAvailablePeers.filter(_.remoteAddress == peer.remoteAddress)
      statelessPeers --= evicted.map(_.id.value)
      peerCooldownUntilMs --= evicted.map(_.id.value)
      knownAvailablePeers.filterInPlace(_.remoteAddress != peer.remoteAddress)
      knownAvailablePeers += peer
      dispatchIfPossible(peer)

    case UpdateMaxInFlightPerPeer(newLimit) =>
      if (newLimit != maxInFlightPerPeer) {
        val wasLimit = maxInFlightPerPeer
        log.info(s"Healing per-peer budget: $maxInFlightPerPeer -> $newLimit")
        maxInFlightPerPeer = newLimit
        // LOG-BUDGET-ZERO: Warn when budget drops to 1 — indicates high timeout rate
        if (newLimit <= 1 && wasLimit > 1) {
          log.warning(
            s"[HEAL-BUDGET] In-flight budget dropped to $newLimit/peer — high timeout rate detected. " +
            s"healed=$totalNodesHealed pending=${pendingTasks.size}"
          )
        }
        if (newLimit > 0) tryRedispatchPendingTasks()
      }

    case HealingPivotRefreshed(newStateRoot) =>
      val oldRoot = Hex.toHexString(stateRoot.take(4).toArray)
      val newRootHex = Hex.toHexString(newStateRoot.take(4).toArray)
      log.info(
        s"Healing pivot refreshed: $oldRoot -> $newRootHex. " +
          s"Clearing ${pendingTasks.size} pending tasks + ${pendingHashSet.size} hashes " +
          s"(aligned to Besu reloadTrieHeal: clear all, reseed from new root). " +
          s"Clearing ${statelessPeers.size} stateless peers."
      )
      stateRoot = newStateRoot
      flushRawNodesSync() // Flush any buffered nodes before pivot switch
      // Besu SnapWorldDownloadState.reloadTrieHeal(): pendingTrieNodeRequests.clear() + startTrieHeal()
      // ARCH-ROOT-SEED (already committed) immediately re-seeds the new root, so there is no
      // empty-queue window — BUG-H3's original concern no longer applies.
      pendingTasks.clear()
      pendingHashSet.clear()
      statelessPeers.clear()
      peersFailedOnRoot.clear() // new root — all peers get a fresh chance on the new root
      peerCooldownUntilMs.clear()
      triedPeersForTask.clear() // new root — all peers get fresh chance per task
      consecutiveGetTrieNodeTimeouts.clear() // new root — reset all peer reputation counts
      // Besu-aligned D12: no peerResponseBytesTarget to clear.
      // Cancel active requests (they're for the old root)
      activeRequests.keys.foreach(requestTracker.completeRequest(_, 0))
      activeRequests.clear()
      pivotRefreshRequested = false
      rootFetchFailures = 0 // new root — reset failure counter
      lastPulseHealedCount = totalNodesHealed // start fresh velocity window post-refresh
      // Post-refresh cooldown: peers need time to sync to new root before we dispatch.
      // Without this, immediate dispatch → all stateless → another HealingAllPeersStateless → tight loop.
      postRefreshCooldownUntilMs = System.currentTimeMillis() + postRefreshCooldownMs
      log.info(s"Post-refresh cooldown active for ${postRefreshCooldownMs / 1000}s — waiting for peers to sync to new root")
      // ARCH-PIVOT-RESEED: Re-seed with new root for top-down discovery of new trie.
      // ARCH-ROOT-SEED ensures healing starts from root within the cooldown window.
      val pivotReseedPath = ByteString(com.chipprbots.ethereum.mpt.HexPrefix.encode(Array.empty[Byte], isLeaf = false))
      if (!pendingHashSet.contains(newStateRoot) && !isNodeInStorage(newStateRoot)) {
        pendingTasks += HealingEntry(Seq(pivotReseedPath), newStateRoot)
        pendingHashSet += newStateRoot
        log.info(
          s"[HEAL] Re-seeded with new root ${Hex.toHexString(newStateRoot.take(4).toArray)} " +
          s"(pending: ${pendingTasks.size})"
        )
      } else {
        log.info(s"[HEAL] New root already in storage or pending — skipping pivot reseed")
      }

    case HealingForceComplete =>
      log.warning(
        s"[HEAL-FORCE-COMPLETE] Pivot advanced beyond SNAP serve window — " +
        s"clearing ${pendingTasks.size} pending tasks + ${activeRequests.size} in-flight. " +
        s"Signaling completion with $totalNodesHealed healed nodes."
      )
      // Cancel all in-flight requests
      activeRequests.keys.foreach(requestTracker.completeRequest(_, 0))
      activeRequests.clear()
      // Clear pending queue — these paths are for a root peers have pruned
      pendingTasks.clear()
      pendingHashSet.clear()
      // Signal completion with 0 abandoned (fresh coordinator + walk will start for new root)
      snapSyncController ! SNAPSyncController.StateHealingComplete(0, totalNodesHealed)
      context.stop(self)

    case TrieNodesResponseMsg(response) =>
      handleResponse(response)

    case FlushComplete(count) =>
      flushing = false
      log.info(s"Async flush complete: $count healed nodes written to disk (total: $totalNodesHealed)")
      // Snapshot pending queue for crash recovery (piggybacks on existing flush cadence)
      val snapshot = pendingTasks.toSeq.map(e => (e.pathset, e.hash))
      snapSyncController ! SNAPSyncController.PersistHealingQueue(snapshot)
      // Check if buffer filled up again during the flush
      if (rawNodeBuffer.size >= rawFlushThreshold) {
        flushRawNodesAsync()
      }
      self ! HealingCheckCompletion

    case HealingTaskComplete(requestId, result) =>
      result match {
        case Right(count) =>
          totalNodesHealed += count
          log.info(s"Healing task completed: $count nodes (total: $totalNodesHealed, pending: ${pendingTasks.size})")
          // Periodic progress summary (every 5K nodes)
          if (totalNodesHealed - lastProgressLogAt >= ProgressLogInterval) {
            val rate = calculateNodesPerSecond()
            val activeTaskCount = activeRequests.values.map(_.tasks.size).sum
            log.info(
              s"Healing progress: $totalNodesHealed nodes (${"%.1f".format(totalBytesReceived / 1048576.0)} MB) " +
                s"(${pendingTasks.size} pending, $activeTaskCount active, " +
                s"${"%.0f".format(rate)} nodes/sec)"
            )
            lastProgressLogAt = totalNodesHealed
          }
          self ! HealingCheckCompletion
        case Left(error) =>
          log.warning(s"Healing task failed: $error")
      }

    case HealingCheckCompletion =>
      if (isComplete && !flushing) {
        flushRawNodesSync()
        val abandonedStr = if (abandonedTaskCount > 0) s" ($abandonedTaskCount abandoned — deferred to state validation)" else ""
        log.info(
          s"Healing complete: $totalNodesHealed nodes healed in " +
            s"${(System.currentTimeMillis() - startTime) / 1000}s " +
            s"(${"%.1f".format(totalBytesReceived / 1048576.0)}MB received)$abandonedStr. " +
            s"Signalling controller to begin state validation trie walk."
        )
        snapSyncController ! SNAPSyncController.StateHealingComplete(abandonedTaskCount, totalNodesHealed)
      } else if (!isComplete) {
        log.debug(
          s"[HEAL-CHECK] pending=${pendingTasks.size} active=${activeRequests.size} flushing=$flushing"
        )
      }

    case HealingGetProgress =>
      val activeTaskCount = activeRequests.values.map(_.tasks.size).sum
      val stats = HealingStatistics(
        totalNodes = totalNodesHealed,
        totalBytes = totalBytesReceived,
        pendingTasks = pendingTasks.size,
        activeTasks = activeTaskCount,
        completedTasks = completedTaskCount,
        nodesPerSecond = calculateNodesPerSecond(),
        kilobytesPerSecond = calculateKilobytesPerSecond(),
        progress = calculateProgress()
      )
      sender() ! stats

    case HealingStagnationCheck =>
      val recentHealed = totalNodesHealed - lastPulseHealedCount
      log.info(
        s"[HEAL-PULSE] healed=$totalNodesHealed total, +$recentHealed last 2min | " +
        s"pending=${pendingTasks.size} active=${activeRequests.size} peers=${knownAvailablePeers.size} | " +
        s"pivotRefreshPending=$pivotRefreshRequested"
      )
      lastPulseHealedCount = totalNodesHealed

      // Escalation: if work is pending but zero requests are active, all SNAP peers may have
      // disconnected. Besu's markAsStalled() is a TODO no-op, but Besu still counts non-progressing
      // requests toward a 1000-request threshold. Fukuii has no request count here, so we use
      // consecutive idle ticks (each 2 minutes) as a proxy.
      if (pendingTasks.nonEmpty && activeRequests.isEmpty && !pivotRefreshRequested) {
        consecutiveIdleChecks += 1
        if (consecutiveIdleChecks >= 5) {
          log.warning(
            s"[HEAL] No active requests for ${consecutiveIdleChecks * 2} minutes with " +
              s"${pendingTasks.size} pending tasks and ${knownAvailablePeers.size} known peers. " +
              s"Forcing healing completion — missing nodes deferred to import-time recovery."
          )
          self ! HealingForceComplete
        }
      } else {
        consecutiveIdleChecks = 0
      }
  }

  private def queueNodes(pathsAndHashes: Seq[(Seq[ByteString], ByteString)]): Unit = {
    var skippedExisting = 0
    val entries = pathsAndHashes.collect {
      case (pathset, hash) if !pendingHashSet.contains(hash) =>
        // R5 fix: Check if the node already exists in storage (downloaded during account/storage phase).
        // mptStorage.get() throws MissingRootNodeException for missing nodes — fast O(1) RocksDB lookup.
        val alreadyExists = try { mptStorage.get(hash.toArray); true } catch { case _: Exception => false }
        if (alreadyExists) {
          skippedExisting += 1
          None
        } else {
          pendingHashSet += hash
          Some(HealingEntry(pathset = pathset, hash = hash))
        }
    }.flatten
    val deduped = pathsAndHashes.size - entries.size - skippedExisting
    pendingTasks ++= entries
    val dedupStr = if (deduped > 0) s" ($deduped duplicates filtered)" else ""
    val existsStr = if (skippedExisting > 0) s" ($skippedExisting already in storage)" else ""
    log.info(s"Queued ${entries.size} nodes for healing$dedupStr$existsStr. Total pending: ${pendingTasks.size}")
  }

  /** Update healing rate EMA and adjust throttle (geth p2p/msgrate alignment).
    *
    * Called after each healing response. Uses geometric EMA (0.5% weight per node) and adjusts throttle every 1 second:
    * increase if pending > 2×rate, decrease otherwise.
    *
    * @param delivered
    *   number of nodes received in this response
    * @param elapsedMs
    *   time from request send to response receive
    */
  private def updateHealThrottle(delivered: Int, elapsedMs: Long): Unit = {
    // Update rate (geometric EMA — geth trienodeHealRateMeasurementImpact = 0.005)
    val elapsedSec = elapsedMs.max(1).toDouble / 1000.0
    val measured = delivered.toDouble / elapsedSec
    healRate = math.pow(1 - RateMeasurementImpact, delivered) * (healRate - measured) + measured
    healRate = healRate.max(0.0)

    // Only backpressure on unflushed buffer — pending queue being large is normal after trie walks
    healPending = rawNodeBuffer.size

    // Adjust throttle every 1 second
    val now = System.currentTimeMillis()
    if (now - lastThrottleAdjustMs > 1000) {
      val oldThrottle = healThrottle
      if (healPending > 2 * healRate) {
        healThrottle = (healThrottle * ThrottleIncrease).min(MaxThrottle)
      } else {
        healThrottle = (healThrottle / ThrottleDecrease).max(MinThrottle)
      }
      if (oldThrottle != healThrottle) {
        log.debug(
          f"Healing throttle adjusted: $oldThrottle%.1f -> $healThrottle%.1f " +
            f"(rate=$healRate%.1f nodes/s, pending=$healPending)"
        )
      }
      lastThrottleAdjustMs = now
    }
  }

  /** Calculate effective batch size after applying throttle divisor. Returns at least 1 node per request.
    */
  private def effectiveBatchSize: Int =
    (batchSize.toDouble / healThrottle).toInt.max(1)

  private def requestNextBatch(peer: Peer): Option[BigInt] = {
    if (pendingTasks.isEmpty) {
      log.debug("No pending healing tasks")
      return None
    }

    if (pivotRefreshRequested) {
      return None
    }

    if (statelessPeers.contains(peer.id.value)) {
      return None
    }

    val effectiveBatch = effectiveBatchSize
    // DFS priority queue with per-task peer exclusion (Besu AbstractRetryingSwitchingPeerTask.triedPeers).
    // Dequeue up to effectiveBatch tasks that this peer hasn't tried yet.
    // Tasks already tried by this peer are skipped and re-enqueued for other peers to handle.
    val batch   = mutable.Buffer.empty[HealingEntry]
    val skipped = mutable.Buffer.empty[HealingEntry]
    while (batch.size < effectiveBatch && pendingTasks.nonEmpty) {
      val task = pendingTasks.dequeue()
      pendingHashSet -= task.hash
      val peersTriedForHash = triedPeersForTask.getOrElse(task.hash, Set.empty[String])
      if (!peersTriedForHash.contains(peer.id.value)) {
        batch += task
      } else {
        // This peer has already tried this hash. Check if ALL known peers have tried it.
        // If so, reset (Besu: triedPeers.retainAll(failedPeers) → allows non-failed retry).
        // We clear completely since we don't track failed vs tried separately at this level.
        val allTried = knownAvailablePeers.map(_.id.value).forall(peersTriedForHash.contains)
        if (allTried) {
          triedPeersForTask.remove(task.hash)
          batch += task
        } else {
          skipped += task
        }
      }
    }
    // Re-enqueue skipped tasks (other peers will dispatch them)
    skipped.foreach { task =>
      pendingTasks += task
      pendingHashSet += task.hash
    }
    if (batch.isEmpty) return None // No dispatchable tasks for this peer right now
    // Record this peer as tried for each dispatched hash
    batch.foreach { task =>
      triedPeersForTask.getOrElseUpdate(task.hash, mutable.Set.empty) += peer.id.value
    }

    val requestId = requestTracker.generateRequestId()
    val responseBytes = requestResponseBytes // Besu-aligned D12: fixed size, no per-peer ratcheting

    // Build the paths list for GetTrieNodes — each entry's pathset is a Seq[ByteString]
    val paths = batch.map(_.pathset).toSeq

    val request = GetTrieNodes(
      requestId = requestId,
      rootHash = stateRoot,
      paths = paths,
      responseBytes = responseBytes
    )

    val batchSeq = batch.toSeq
    activeRequests(requestId) = ActiveRequest(batchSeq, peer, responseBytes)

    // Besu RequestDataStep.java: orTimeout(10, TimeUnit.SECONDS) for all request types.
    // Aligned floor: 10s (was 30s). Ceiling: 30s (was 60s). Slow peers released faster.
    val healingTimeout = requestTracker.rateTracker.targetTimeout().max(10.seconds).min(30.seconds)
    requestTracker.trackRequest(requestId, peer, SNAPRequestTracker.RequestType.GetTrieNodes, healingTimeout) {
      handleTimeout(requestId, batchSeq, peer)
    }

    import com.chipprbots.ethereum.network.p2p.messages.SNAP.GetTrieNodes.GetTrieNodesEnc
    val messageSerializable: com.chipprbots.ethereum.network.p2p.MessageSerializable = new GetTrieNodesEnc(request)
    networkPeerManager ! NetworkPeerManagerActor.SendMessage(messageSerializable, peer.id)

    log.debug(
      s"Requested ${batch.size} trie nodes from peer ${peer.id.value} " +
        s"(reqId=$requestId, responseBytes=$responseBytes, pending=${pendingTasks.size})"
    )
    Some(requestId)
  }

  private def handleResponse(response: TrieNodes): Unit = {
    val requestId = response.requestId
    val nodes = response.nodes

    log.debug(s"Received TrieNodes response: reqId=$requestId, nodes=${nodes.size}")

    val activeReq = activeRequests.get(requestId) match {
      case Some(req) => req
      case None =>
        log.debug(s"Received orphaned TrieNodes response (reqId=$requestId). Request was already cancelled (timeout or pivot refresh). Discarding stale response.")
        return
    }

    val tasksForRequest = activeReq.tasks
    val peer = activeReq.peer

    var healedCount = 0
    var receivedBytes: Long = 0

    // Match response nodes to request tasks positionally
    for ((nodeData, task) <- nodes.zip(tasksForRequest)) {
      val nodeHash = ByteString(org.bouncycastle.jcajce.provider.digest.Keccak.Digest256().digest(nodeData.toArray))
      if (nodeHash == task.hash) {
        // Valid node — store directly by hash
        rawNodeBuffer += ((nodeHash, nodeData.toArray))
        discoverMissingChildren(nodeData, task)
        triedPeersForTask.remove(task.hash) // healed — no more per-task tracking needed
        healedCount += 1
        totalNodesHealed += 1
        receivedBytes += nodeData.length
        totalBytesReceived += nodeData.length
      } else {
        log.warning(
          s"Node hash mismatch: expected ${Hex.toHexString(task.hash.take(4).toArray)}, " +
            s"got ${Hex.toHexString(nodeHash.take(4).toArray)}"
        )
        // Re-queue with incremented retry count — abandon after maxRetriesPerTask mismatches.
        // Without this, a ghost node from a prior session's HEAL-RESUME queue (whose path no
        // longer exists in the current trie) causes an infinite loop: peers return the nearest
        // ancestor (the root) for non-existent paths, the mismatch re-queues without progress,
        // and the node can never be healed regardless of how many pivot refreshes occur.
        val updated = task.copy(retries = task.retries + 1)
        if (updated.retries >= maxRetriesPerTask) {
          if (task.hash == stateRoot) {
            // D4 — never permanently abandon the state root node, even on hash mismatches.
            rootFetchFailures += 1
            log.warning(
              s"[HEAL-ROOT-RETRY] Root node ${Hex.toHexString(stateRoot.take(4).toArray)} hash mismatch after " +
                s"$maxRetriesPerTask attempts (got ${Hex.toHexString(nodeHash.take(4).toArray)}). " +
                s"Re-queuing with reset retries (cumulative root failures: $rootFetchFailures)."
            )
            val resetEntry = task.copy(retries = 0)
            pendingTasks += resetEntry
            pendingHashSet += resetEntry.hash
          } else {
            abandonedTaskCount += 1
            log.warning(
              s"Giving up on trie node ${Hex.toHexString(task.hash.take(4).toArray)} after " +
                s"$maxRetriesPerTask consecutive hash mismatches " +
                s"(got ${Hex.toHexString(nodeHash.take(4).toArray)}). " +
                s"Node not present in current trie — deferring to state validation."
            )
          }
        } else {
          pendingTasks += updated
          pendingHashSet += updated.hash
        }
      }
    }

    // Re-queue unmatched tasks (server returned fewer nodes than requested)
    val unmatchedTasks = tasksForRequest.drop(nodes.size)
    unmatchedTasks.foreach { task =>
      pendingTasks += task
      pendingHashSet += task.hash
    }

    completedTaskCount += healedCount
    activeRequests.remove(requestId)
    requestTracker.completeRequest(requestId, nodes.size.max(1))

    // Update healing throttle (geth msgrate alignment)
    val elapsedMs = System.currentTimeMillis() - activeReq.sentAtMs
    updateHealThrottle(healedCount, elapsedMs)

    // Besu-aligned peer tracking: no persistent IP-level blocking.
    // Successful response clears session-stateless marking. Empty response:
    //   - 10s cooldown (recordPeerCooldown)
    //   - session-level statelessPeers entry (cleared on next HealingPeerAvailable eviction)
    //   - if root was in the batch: peersFailedOnRoot (persistent, mirrors WORLD_STATE_ROOT_NOT_AVAILABLE)
    //   - pivot refresh when all known peers have failed on root (peersFailedOnRoot saturates)
    if (healedCount > 0) {
      statelessPeers -= peer.id.value
      // Besu PeerReputation.recordUsefulResponse(): reset consecutive timeout counter on success.
      consecutiveGetTrieNodeTimeouts.remove(peer.id.value)
      globalConsecutiveTimeouts = 0
    } else {
      recordPeerCooldown(peer, "empty healing response")
      statelessPeers += peer.id.value
      val rootInBatch = tasksForRequest.exists(_.hash == stateRoot)
      if (rootInBatch) peersFailedOnRoot += peer.id.value
      log.debug(
        s"Peer ${peer.id.value} returned empty batch (${nodes.size} requested, rootInBatch=$rootInBatch) — " +
          s"cooldown + session-stateless (${statelessPeers.size}/${knownAvailablePeers.size})"
      )
      // Pivot refresh: fire when all known peers have failed on root specifically.
      // Using peersFailedOnRoot (persistent) rather than statelessPeers (ephemeral) for reliability.
      if (peersFailedOnRoot.size >= knownAvailablePeers.size && knownAvailablePeers.nonEmpty && !pivotRefreshRequested) {
        pivotRefreshRequested = true
        log.warning(
          s"All ${peersFailedOnRoot.size} peers failed on root " +
            s"${Hex.toHexString(stateRoot.take(4).toArray)} — requesting pivot refresh."
        )
        snapSyncController ! SNAPSyncController.HealingAllPeersStateless
      }
    }

    log.info(
      s"Healed $healedCount of ${nodes.size} requested trie nodes from ${peer.id.value}. " +
        s"Cumulative: $totalNodesHealed healed, ${pendingTasks.size} pending, ${activeRequests.size} in-flight"
    )

    if (healedCount > 0) {
      snapSyncController ! SNAPSyncController.ProgressNodesHealed(healedCount.toLong)

      // Periodic async flush of raw node buffer
      if (rawNodeBuffer.size >= rawFlushThreshold) {
        flushRawNodesAsync()
      }
    }

    // Dispatch more work to this peer if available (pipeline multiple requests)
    dispatchIfPossible(peer)

    self ! HealingCheckCompletion
  }

  private def handleTimeout(requestId: BigInt, tasks: Seq[HealingEntry], peer: Peer): Unit = {
    log.warning(s"Healing request timed out: reqId=$requestId, tasks=${tasks.size}, peer=${peer.id.value}")

    activeRequests.remove(requestId)
    recordPeerCooldown(peer, "request timeout")
    // Besu PeerReputation: accumulate consecutive timeout count; disconnect on threshold.
    // Mirrors PeerReputation.recordRequestTimeout() with TIMEOUT_THRESHOLD=5.
    // PulseChain/ETH-mainnet nodes time out on every GetTrieNodes — evict them promptly.
    if (!peer.isStatic) {
      val peerId = peer.id.value
      val newCount = consecutiveGetTrieNodeTimeouts.getOrElse(peerId, 0) + 1
      consecutiveGetTrieNodeTimeouts(peerId) = newCount
      if (newCount >= SnapTimeoutDisconnectThreshold) {
        log.warning(
          s"[HEAL-REPUTATION] Peer $peerId accumulated $newCount consecutive GetTrieNodes timeouts " +
            s"— disconnecting (Besu PeerReputation.TIMEOUT_THRESHOLD=$SnapTimeoutDisconnectThreshold)"
        )
        consecutiveGetTrieNodeTimeouts.remove(peerId)
        knownAvailablePeers.retain(_.id.value != peerId)
        peersFailedOnRoot -= peerId
        peer.ref ! PeerActor.DisconnectPeer(Disconnect.Reasons.UselessPeer)
      }
    }
    // Besu-aligned D12: no adaptive byte budget ratcheting on timeout.

    // Increment retry count and skip exhausted tasks
    var requeued = 0
    var abandoned = 0
    tasks.foreach { task =>
      val updated = task.copy(retries = task.retries + 1)
      if (updated.retries >= maxRetriesPerTask) {
        if (task.hash == stateRoot) {
          // D4 — never permanently abandon the state root node.
          // The root cannot be recovered by regular sync on-demand — without it no block can execute.
          // Re-queue with reset retries. Mark the timed-out peer stateless so HealingAllPeersStateless
          // fires when all peers have exhausted root fetch attempts, triggering a pivot refresh.
          rootFetchFailures += 1
          log.warning(
            s"[HEAL-ROOT-RETRY] Root node ${Hex.toHexString(stateRoot.take(4).toArray)} timed out " +
              s"$maxRetriesPerTask times on peer ${peer.id.value} " +
              s"(cumulative root failures: $rootFetchFailures). Re-queuing — root is never abandoned (Besu D4)."
          )
          val resetEntry = task.copy(retries = 0)
          pendingTasks += resetEntry
          pendingHashSet += resetEntry.hash
          requeued += 1
          // Timeout is transient — session-level block + cooldown only (Besu: no IP-level ban).
          // Also track this peer in peersFailedOnRoot (persistent across HealingPeerAvailable ticks,
          // cleared only on pivot refresh). When ALL known peers have failed on root, trigger pivot
          // refresh — mirrors Besu's WORLD_STATE_ROOT_NOT_AVAILABLE signal from TrieNodeHealingStep.
          if (!peer.isStatic) {
            statelessPeers += peer.id.value
            peersFailedOnRoot += peer.id.value
            log.info(
              s"[HEAL-ROOT-RETRY] Peer ${peer.id.value} exhausted $maxRetriesPerTask timeouts on root " +
                s"(${peersFailedOnRoot.size}/${knownAvailablePeers.size} peers failed on root)"
            )
            if (peersFailedOnRoot.size >= knownAvailablePeers.size && knownAvailablePeers.nonEmpty && !pivotRefreshRequested) {
              pivotRefreshRequested = true
              log.warning(
                s"[HEAL-ROOT-RETRY] All ${peersFailedOnRoot.size} peers exhausted on root " +
                  s"${Hex.toHexString(stateRoot.take(4).toArray)} — requesting pivot refresh."
              )
              snapSyncController ! SNAPSyncController.HealingAllPeersStateless
            }
          }
        } else {
          abandoned += 1
          abandonedTaskCount += 1
          log.warning(
            s"Giving up on trie node ${Hex.toHexString(task.hash.take(4).toArray)} after $maxRetriesPerTask retries. " +
              s"Deferring to state validation phase — node will be re-discovered and fetched during trie walk."
          )
        }
      } else {
        pendingTasks += updated
        pendingHashSet += updated.hash
        requeued += 1
      }
    }

    if (requeued > 0) {
      log.info(s"Re-queued $requeued timed-out healing tasks (pending: ${pendingTasks.size})" +
        (if (abandoned > 0) s", abandoned $abandoned" else ""))
    }

    globalConsecutiveTimeouts += 1
    if (globalConsecutiveTimeouts >= MaxGlobalConsecutiveTimeouts) {
      log.warning(
        s"Force-completing healing coordinator after $globalConsecutiveTimeouts consecutive timeouts " +
          s"(${pendingTasks.size} pending, $totalNodesHealed healed) — " +
          s"ETC SNAP peers not serving pivot state. Missing nodes deferred to import-time recovery."
      )
      self ! HealingForceComplete
      return
    }

    // Besu-aligned: no mass-abandonment on timeout. Per-task retry via maxRetriesPerTask=4 only.
    // Peers that exhaust retries are implicitly excluded via blacklistDuration on their tasks.
    tryRedispatchPendingTasks()
    self ! HealingCheckCompletion
  }

  private def tryRedispatchPendingTasks(): Unit = {
    if (pendingTasks.isEmpty) return
    if (pivotRefreshRequested) return
    val eligiblePeers = knownAvailablePeers.toList
      .filterNot(isPeerCoolingDown)
      .filterNot(p => statelessPeers.contains(p.id.value))
    if (eligiblePeers.isEmpty) {
      log.debug(
        s"[REDISPATCH] No eligible peers — ${knownAvailablePeers.size} known, " +
          s"${statelessPeers.size} stateless, rest cooling down. pending: ${pendingTasks.size}"
      )
      return
    }

    for (peer <- eligiblePeers if pendingTasks.nonEmpty)
      dispatchIfPossible(peer)
  }

  private def calculateProgress(): Double = {
    val activeTaskCount = activeRequests.values.map(_.tasks.size).sum
    val total = pendingTasks.size + activeTaskCount + completedTaskCount
    if (total == 0) 1.0
    else completedTaskCount.toDouble / total
  }

  /** Recent nodes/sec using 60s rolling window. Falls back to overall average when insufficient data. */
  private def calculateNodesPerSecond(): Double = {
    val now = System.currentTimeMillis()
    recentHistory.enqueue((now, totalNodesHealed.toLong))
    while (recentHistory.nonEmpty && now - recentHistory.head._1 > RecentWindowMs)
      recentHistory.dequeue()
    if (recentHistory.size >= 2) {
      val oldest = recentHistory.head
      val timeDiff = (now - oldest._1) / 1000.0
      val countDiff = totalNodesHealed - oldest._2
      if (timeDiff > 0) countDiff / timeDiff else 0.0
    } else {
      // Fallback to overall average
      val elapsedSec = (now - startTime) / 1000.0
      if (elapsedSec > 0) totalNodesHealed / elapsedSec else 0.0
    }
  }

  /** Recent KB/sec using overall average (bytes not tracked in rolling window). */
  private def calculateKilobytesPerSecond(): Double = {
    val elapsedSec = (System.currentTimeMillis() - startTime) / 1000.0
    if (elapsedSec > 0) (totalBytesReceived / 1024.0) / elapsedSec else 0.0
  }

  /** Decode a healed node and queue any missing children for healing.
    * Makes healing self-feeding: each healed node expands the work queue without
    * requiring a full periodic trie walk (geth trie.Sync scheduler alignment).
    */
  private def discoverMissingChildren(nodeData: ByteString, parentEntry: HealingEntry): Unit = {
    import com.chipprbots.ethereum.mpt.{MptTraversals, BranchNode, ExtensionNode, HashNode, LeafNode}
    import com.chipprbots.ethereum.mpt.HexPrefix
    import com.chipprbots.ethereum.domain.Account
    import scala.util.control.NonFatal

    val pathset = parentEntry.pathset
    if (pathset.isEmpty) return

    try {
      val decoded = MptTraversals.decodeNode(nodeData.toArray)
      val parentCompact = pathset.last.toArray
      val parentNibbles = HexPrefix.decode(parentCompact)._1
      val isStorageTrie = pathset.size > 1  // Seq(accountHash, path) vs Seq(path)

      val newEntries = mutable.Buffer.empty[HealingEntry]

      decoded match {
        case branch: BranchNode =>
          // DFS priority: child i of parent gets priority = parentPriority * 16 + i (Besu TrieNodeHealingRequest)
          for (i <- 0 until 16) {
            branch.children(i) match {
              case hash: HashNode =>
                val childHash = ByteString(hash.hashNode)
                if (!pendingHashSet.contains(childHash) && !isNodeInStorage(childHash)) {
                  val childNibbles = parentNibbles :+ i.toByte
                  val childCompact = ByteString(HexPrefix.encode(childNibbles, isLeaf = false))
                  val childPathset = if (isStorageTrie) Seq(pathset.head, childCompact) else Seq(childCompact)
                  newEntries += HealingEntry(childPathset, childHash,
                    depth = parentEntry.depth + 1,
                    priority = parentEntry.priority * 16 + i)
                }
              case _ => // Inline-encoded or null — already resolved
            }
          }

        case ext: ExtensionNode =>
          // Extension has a single child; treat as index 0 for priority inheritance
          ext.next match {
            case hash: HashNode =>
              val childHash = ByteString(hash.hashNode)
              if (!pendingHashSet.contains(childHash) && !isNodeInStorage(childHash)) {
                // ext.sharedKey is already HP-decoded nibbles (ByteString of nibble bytes)
                val childNibbles = parentNibbles ++ ext.sharedKey.toArray
                val childCompact = ByteString(HexPrefix.encode(childNibbles, isLeaf = false))
                val childPathset = if (isStorageTrie) Seq(pathset.head, childCompact) else Seq(childCompact)
                newEntries += HealingEntry(childPathset, childHash,
                  depth = parentEntry.depth + 1,
                  priority = parentEntry.priority * 16)
              }
            case _ => // Already inline-encoded — no missing child
          }

        case leaf: LeafNode if !isStorageTrie =>
          // ARCH-LEAF-SEED: Account trie leaf — decode account value, seed storage trie if needed.
          // Besu equivalent: getChildRequests() → getStorageTrieNodeRequests() on account leaf values.
          // leaf.key contains HP-decoded nibbles for this leaf's remaining path segment.
          Account(leaf.value).foreach { account =>
            if (account.storageRoot != Account.EmptyStorageRootHash &&
                !pendingHashSet.contains(account.storageRoot) &&
                !isNodeInStorage(account.storageRoot)) {
              // Reconstruct 32-byte account address hash from the full nibble path.
              // In the account trie, path from root to leaf = keccak256(address) as 64 nibbles.
              val leafNibbles = leaf.key.toArray  // remaining nibbles for this leaf's path segment
              val allNibbles  = parentNibbles ++ leafNibbles
              if (allNibbles.length == 64) {
                val accountHashBytes = allNibbles.grouped(2).map { g =>
                  ((g(0) << 4) | g(1)).toByte
                }.toArray
                val accountHash      = ByteString(accountHashBytes)
                val emptyStoragePath = ByteString(HexPrefix.encode(Array.empty[Byte], isLeaf = false))
                // Storage trie root inherits account's priority; depth=0 (new trie)
                newEntries += HealingEntry(Seq(accountHash, emptyStoragePath), account.storageRoot,
                  depth = 0,
                  priority = parentEntry.priority)
                log.debug(
                  s"[HEAL-LEAF] Seeded storage trie root ${Hex.toHexString(account.storageRoot.take(4).toArray)} " +
                  s"for account ${Hex.toHexString(accountHashBytes.take(4))}"
                )
              } else {
                log.warning(
                  s"[HEAL-LEAF] Unexpected nibble path length ${allNibbles.length} at account leaf (expected 64) — " +
                    s"storage root ${Hex.toHexString(account.storageRoot.take(4).toArray)} not seeded. " +
                    s"Full trie walk will be required."
                )
                abandonedTaskCount += 1
              }
            }
          }

        case _ => // storage trie LeafNode, NullNode, HashNode — no children to discover
      }

      if (newEntries.nonEmpty) {
        newEntries.foreach(e => pendingHashSet += e.hash)
        pendingTasks ++= newEntries
        childrenDiscoveredTotal += newEntries.size
        if (childrenDiscoveredTotal % 100 == 0 || childrenDiscoveredTotal <= 20)
          log.info(
            s"[HEAL] Missing trie children queued: $childrenDiscoveredTotal total " +
              s"(+${newEntries.size} from this node, pending: ${pendingTasks.size})"
          )
      }
    } catch {
      case NonFatal(e) =>
        log.warning(
          s"[HEAL] Cannot decode healed node ${Hex.toHexString(parentEntry.hash.take(4).toArray)} for child discovery: ${e.getMessage}. " +
            s"Subtree excluded from inline discovery — full trie walk required for these nodes."
        )
    }
  }

  private def isNodeInStorage(hash: ByteString): Boolean =
    try { mptStorage.get(hash.toArray); true } catch { case _: Exception => false }

  private def isComplete: Boolean =
    pendingTasks.isEmpty && activeRequests.isEmpty
}

object TrieNodeHealingCoordinator {
  def props(
      stateRoot: ByteString,
      networkPeerManager: ActorRef,
      requestTracker: SNAPRequestTracker,
      mptStorage: MptStorage,
      batchSize: Int,
      snapSyncController: ActorRef,
      concurrency: Int = 16
  ): Props =
    Props(
      new TrieNodeHealingCoordinator(
        stateRoot,
        networkPeerManager,
        requestTracker,
        mptStorage,
        batchSize,
        snapSyncController,
        concurrency
      )
    )
}

case class HealingStatistics(
    totalNodes: Int,
    totalBytes: Long,
    pendingTasks: Int,
    activeTasks: Int,
    completedTasks: Int,
    nodesPerSecond: Double,
    kilobytesPerSecond: Double,
    progress: Double
)
