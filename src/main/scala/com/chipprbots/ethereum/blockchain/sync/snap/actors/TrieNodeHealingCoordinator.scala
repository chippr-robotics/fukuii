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
import com.chipprbots.ethereum.network.p2p.messages.SNAP.{GetTrieNodes, TrieNodes}

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
  private case class HealingEntry(pathset: Seq[ByteString], hash: ByteString, retries: Int = 0)
  private var pendingTasks: Seq[HealingEntry] = Seq.empty
  private var completedTaskCount: Int = 0
  private var abandonedTaskCount: Int = 0

  // Per-task retry limit: after this many timeouts/failures, skip the task.
  // At ~6s per timeout cycle, 20 retries = ~2 minutes of trying per node.
  private val maxRetriesPerTask: Int = 20

  // Global stagnation detection: if no nodes healed for this duration, declare
  // healing complete with a warning. Prevents infinite loops when all peers lack
  // GetTrieNodes support (ETH68 networks). Regular sync fetches missing nodes on-demand.
  private var lastHealedAtMs: Long = System.currentTimeMillis()
  private val healingStagnationTimeoutMs: Long = 5 * 60 * 1000 // 5 minutes

  // Pivot refresh stall detection: tracks when pivotRefreshRequested was set and when
  // dispatch or responses last occurred, enabling the periodic HealingStagnationCheck to
  // detect and recover from controller-side retry failures (Bug 1 defense-in-depth).
  private var pivotRefreshRequestedAtMs: Long = 0L
  private var lastDispatchOrResponseMs: Long = System.currentTimeMillis()
  private val noActivityTimeoutMs: Long = 120_000L        // 2 min: ghost-peer detection (matches StorageRangeCoordinator)
  private val pivotRefreshStallTimeoutMs: Long = 600_000L // 10 min: re-trigger if controller retry is stuck

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
  private var maxInFlightPerPeer: Int = 5

  // Statistics
  private var totalNodesHealed: Int = 0
  private var totalBytesReceived: Long = 0
  private val startTime = System.currentTimeMillis()
  private var lastProgressLogAt: Long = 0
  private val ProgressLogInterval: Long = 500
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

  // Stateless peer tracking (geth-aligned: peers that return empty TrieNodes for current root)
  private val statelessPeers = mutable.Set[String]()
  // Persistent stateless tracking by remote address — survives peer reconnect with new session ID.
  // Non-static peers that return empty GetTrieNodes are blocked even after reconnection.
  private val statelessRemoteAddresses = mutable.Set[String]()
  private var pivotRefreshRequested: Boolean = false
  // Post-pivot-refresh cooldown: prevents immediate re-dispatch before peers sync to new root.
  // Without this, all peers return empty → stateless → another HealingAllPeersStateless → tight loop.
  private var postRefreshCooldownUntilMs: Long = 0L
  private val postRefreshCooldownMs: Long = 10_000L // 10s (matches StorageRangeCoordinator)

  // Per-peer adaptive byte budgeting
  private val minResponseBytes: BigInt = 50 * 1024
  private val maxResponseBytes: BigInt = 2 * 1024 * 1024
  private val initialResponseBytes: BigInt = 512 * 1024
  private val increaseFactor: Double = 1.25
  private val decreaseFactor: Double = 0.5

  private val peerResponseBytesTarget = mutable.Map.empty[String, BigInt]

  // HW1 self-feeding: tracks total missing trie children discovered across all healed nodes
  private var childrenDiscoveredTotal: Int = 0

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
      s"Reducing healing responseBytes target for peer ${peer.id.value}: $cur -> ${peerResponseBytesTarget(peer.id.value)} ($reason)"
    )
  }

  // Peer cooldown
  private val peerCooldownUntilMs = mutable.Map[String, Long]()
  private val peerCooldownDefault = 30.seconds

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

    case QueueMissingNodes(nodes) =>
      log.info(s"[HEAL-RESUME] Received ${nodes.size} persisted missing nodes from prior session")
      lastHealedAtMs = System.currentTimeMillis() // reset stagnation timer at start of each healing round
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
      // Skip if this remote address has been marked stateless — blocks reconnected dead peers
      if (statelessRemoteAddresses.contains(peer.remoteAddress.toString)) {
        log.debug(
          s"[HEALING] Skipping ${peer.remoteAddress} — address known stateless (prior GetTrieNodes failure)"
        )
      } else {
        // Evict stale entry for same physical node (reconnection creates new PeerId).
        // Also clean up stale session-ID entries from statelessPeers and cooldown map.
        val evicted = knownAvailablePeers.filter(_.remoteAddress == peer.remoteAddress)
        statelessPeers --= evicted.map(_.id.value)
        peerCooldownUntilMs --= evicted.map(_.id.value)
        knownAvailablePeers.filterInPlace(_.remoteAddress != peer.remoteAddress)
        knownAvailablePeers += peer
        dispatchIfPossible(peer)
      }

    case UpdateMaxInFlightPerPeer(newLimit) =>
      if (newLimit != maxInFlightPerPeer) {
        log.info(s"Healing per-peer budget: $maxInFlightPerPeer -> $newLimit")
        maxInFlightPerPeer = newLimit
        if (newLimit > 0) tryRedispatchPendingTasks()
      }

    case HealingPivotRefreshed(newStateRoot) =>
      val oldRoot = Hex.toHexString(stateRoot.take(4).toArray)
      val newRootHex = Hex.toHexString(newStateRoot.take(4).toArray)
      log.info(
        s"Healing pivot refreshed: $oldRoot -> $newRootHex. " +
          s"Preserving ${pendingTasks.size} pending tasks (content-addressed, ~99% valid for new root). " +
          s"Clearing ${statelessPeers.size} stateless peers."
      )
      stateRoot = newStateRoot
      flushRawNodesSync() // Flush any buffered nodes before pivot switch
      // BUG-H3: Do NOT clear pendingTasks/pendingHashSet on pivot refresh.
      // Trie nodes are content-addressed (keccak256), so ~99% of pending items remain valid
      // for any consecutive pivot. The new trie walk adds only the delta; dedup via
      // pendingHashSet prevents duplicates. Clearing here caused the heal→walk→heal→walk cycle.
      statelessPeers.clear()
      statelessRemoteAddresses.clear() // new root — peers that failed old root may serve new one
      peerCooldownUntilMs.clear()
      peerResponseBytesTarget.clear()
      // Cancel active requests (they're for the old root)
      activeRequests.keys.foreach(requestTracker.completeRequest(_, 0))
      activeRequests.clear()
      pivotRefreshRequested = false
      pivotRefreshRequestedAtMs = 0L
      // Post-refresh cooldown: peers need time to sync to new root before we dispatch.
      // Without this, immediate dispatch → all stateless → another HealingAllPeersStateless → tight loop.
      postRefreshCooldownUntilMs = System.currentTimeMillis() + postRefreshCooldownMs
      log.info(s"Post-refresh cooldown active for ${postRefreshCooldownMs / 1000}s — waiting for peers to sync to new root")
      import context.dispatcher
      context.system.scheduler.scheduleOnce(postRefreshCooldownMs.millis, self, HealingStagnationCheck)

    case TrieNodesResponseMsg(response) =>
      handleResponse(response)

    case FlushComplete(count) =>
      flushing = false
      log.info(s"Async flush complete: $count healed nodes written to disk (total: $totalNodesHealed)")
      // Snapshot pending queue for crash recovery (piggybacks on existing flush cadence)
      val snapshot = pendingTasks.map(e => (e.pathset, e.hash))
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
      val now = System.currentTimeMillis()
      log.debug(
        s"[HEAL-CHECK] pivotRefreshRequested=$pivotRefreshRequested " +
        s"pending=${pendingTasks.size} active=${activeRequests.size} " +
        s"peers=${knownAvailablePeers.size} stateless=${statelessPeers.size} " +
        s"idleFor=${(now - lastDispatchOrResponseMs) / 1000}s"
      )

      // 1. Detect pivot refresh stall: pivotRefreshRequested set but unresolved for >10min.
      // Defense-in-depth — normally Bug 1 fix (RetryPivotRefresh handling StateHealing) resolves it.
      if (pivotRefreshRequested && pivotRefreshRequestedAtMs > 0 &&
          (now - pivotRefreshRequestedAtMs) > pivotRefreshStallTimeoutMs) {
        log.warning(
          s"[HEAL-STALL] Pivot refresh has been pending for ${(now - pivotRefreshRequestedAtMs) / 60000}min " +
          s"without resolution. Re-triggering — controller retry may be stuck."
        )
        // Reset all stateless state and re-request so the controller gets another HealingAllPeersStateless
        pivotRefreshRequested = false
        pivotRefreshRequestedAtMs = 0L
        statelessPeers.clear()
        peerCooldownUntilMs.clear()
        val allStillStateless = knownAvailablePeers.nonEmpty &&
          knownAvailablePeers.forall(p => statelessPeers.contains(p.id.value))
        if (allStillStateless || knownAvailablePeers.isEmpty) {
          pivotRefreshRequested = true
          pivotRefreshRequestedAtMs = now
          log.warning("[HEAL-STALL] Still no capable peers after reset — re-sending HealingAllPeersStateless")
          snapSyncController ! SNAPSyncController.HealingAllPeersStateless
        } else {
          tryRedispatchPendingTasks()
        }
      }

      // 2. Detect no-activity stall from ghost peers (present in knownAvailablePeers but disconnected).
      // If tasks are pending but no dispatch/response has occurred for 2min and dispatch is not blocked,
      // the remaining peers are likely disconnected ghosts — mark stateless to trigger pivot refresh.
      if (!pivotRefreshRequested && pendingTasks.nonEmpty && activeRequests.isEmpty &&
          knownAvailablePeers.nonEmpty &&
          (now - lastDispatchOrResponseMs) > noActivityTimeoutMs) {
        log.warning(
          s"[HEAL-STALL] No healing activity for ${(now - lastDispatchOrResponseMs) / 1000}s " +
          s"with ${pendingTasks.size} pending tasks and ${knownAvailablePeers.size} known peers. " +
          s"Assuming ghost connections — marking all peers stateless to trigger pivot refresh."
        )
        knownAvailablePeers.foreach(p => statelessPeers.add(p.id.value))
        if (!pivotRefreshRequested) {
          pivotRefreshRequested = true
          pivotRefreshRequestedAtMs = now
          snapSyncController ! SNAPSyncController.HealingAllPeersStateless
        }
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
    pendingTasks = pendingTasks ++ entries
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
    val batch = pendingTasks.take(effectiveBatch)
    pendingTasks = pendingTasks.drop(effectiveBatch)
    // Remove dispatched hashes from dedup set (they'll be re-added if re-queued on failure)
    batch.foreach(e => pendingHashSet -= e.hash)

    val requestId = requestTracker.generateRequestId()
    val responseBytes = responseBytesTargetFor(peer)

    // Build the paths list for GetTrieNodes — each entry's pathset is a Seq[ByteString]
    val paths = batch.map(_.pathset)

    val request = GetTrieNodes(
      requestId = requestId,
      rootHash = stateRoot,
      paths = paths,
      responseBytes = responseBytes
    )

    activeRequests(requestId) = ActiveRequest(batch, peer, responseBytes)

    // OPT-P3: Use adaptive timeout clamped between 30s–60s for GetTrieNodes.
    // The adaptive floor (6s) is too short for static peers busy after a long account download.
    // 30s floor: accounts for Besu/core-geth RocksDB compaction spikes.
    // 60s ceiling: prevents indefinite waits on stale/slow peers.
    val healingTimeout = requestTracker.rateTracker.targetTimeout().max(30.seconds).min(60.seconds)
    requestTracker.trackRequest(requestId, peer, SNAPRequestTracker.RequestType.GetTrieNodes, healingTimeout) {
      handleTimeout(requestId, batch, peer)
    }

    import com.chipprbots.ethereum.network.p2p.messages.SNAP.GetTrieNodes.GetTrieNodesEnc
    val messageSerializable: com.chipprbots.ethereum.network.p2p.MessageSerializable = new GetTrieNodesEnc(request)
    networkPeerManager ! NetworkPeerManagerActor.SendMessage(messageSerializable, peer.id)

    log.debug(
      s"Requested ${batch.size} trie nodes from peer ${peer.id.value} " +
        s"(reqId=$requestId, responseBytes=$responseBytes, pending=${pendingTasks.size})"
    )
    lastDispatchOrResponseMs = System.currentTimeMillis()

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
    val requestedBytes = activeReq.requestedBytes

    var healedCount = 0
    var receivedBytes: Long = 0

    // Match response nodes to request tasks positionally
    for ((nodeData, task) <- nodes.zip(tasksForRequest)) {
      val nodeHash = ByteString(org.bouncycastle.jcajce.provider.digest.Keccak.Digest256().digest(nodeData.toArray))
      if (nodeHash == task.hash) {
        // Valid node — store directly by hash
        rawNodeBuffer += ((nodeHash, nodeData.toArray))
        discoverMissingChildren(nodeData, task.pathset)
        healedCount += 1
        totalNodesHealed += 1
        receivedBytes += nodeData.length
        totalBytesReceived += nodeData.length
      } else {
        log.warning(
          s"Node hash mismatch: expected ${Hex.toHexString(task.hash.take(4).toArray)}, " +
            s"got ${Hex.toHexString(nodeHash.take(4).toArray)}"
        )
        // Re-queue this task for retry
        pendingTasks = pendingTasks :+ task
      }
    }

    // Re-queue unmatched tasks (server returned fewer nodes than requested)
    val unmatchedTasks = tasksForRequest.drop(nodes.size)
    unmatchedTasks.foreach { task =>
      pendingTasks = pendingTasks :+ task
    }

    completedTaskCount += healedCount
    activeRequests.remove(requestId)
    requestTracker.completeRequest(requestId, nodes.size.max(1))
    lastDispatchOrResponseMs = System.currentTimeMillis()

    // Update healing throttle (geth msgrate alignment)
    val elapsedMs = System.currentTimeMillis() - activeReq.sentAtMs
    updateHealThrottle(healedCount, elapsedMs)

    // Adaptive byte budget + stateless tracking
    if (healedCount > 0) {
      adjustResponseBytesOnSuccess(peer, requestedBytes, BigInt(receivedBytes))
      // Successful response — clear stateless marking and reset stagnation timer
      statelessPeers -= peer.id.value
      lastHealedAtMs = System.currentTimeMillis()
    } else {
      adjustResponseBytesOnFailure(peer, "empty healing response")
      recordPeerCooldown(peer, "empty healing response")
      // Mark peer stateless for current root (geth-aligned) — skip for static peers
      if (!peer.isStatic) {
        statelessPeers += peer.id.value
        statelessRemoteAddresses += peer.remoteAddress.toString // persist across reconnects
        log.info(
          s"Peer ${peer.id.value}@${peer.remoteAddress} marked stateless for healing root " +
            s"${Hex.toHexString(stateRoot.take(4).toArray)} (${statelessPeers.size}/${knownAvailablePeers.size} stateless)"
        )
        // Check if all known peers are stateless — request pivot refresh
        if (statelessPeers.size >= knownAvailablePeers.size && knownAvailablePeers.nonEmpty && !pivotRefreshRequested) {
          pivotRefreshRequested = true
          pivotRefreshRequestedAtMs = System.currentTimeMillis()
          log.warning(
            s"All ${statelessPeers.size} peers stateless for healing root " +
              s"${Hex.toHexString(stateRoot.take(4).toArray)}. Requesting pivot refresh."
          )
          snapSyncController ! SNAPSyncController.HealingAllPeersStateless
        }
      } else {
        log.debug(s"[STATIC] Skipping stateless marking for static peer ${peer.remoteAddress} (healing)")
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
    adjustResponseBytesOnFailure(peer, "request timeout")

    // Increment retry count and skip exhausted tasks
    var requeued = 0
    var abandoned = 0
    tasks.foreach { task =>
      val updated = task.copy(retries = task.retries + 1)
      if (updated.retries >= maxRetriesPerTask) {
        abandoned += 1
        abandonedTaskCount += 1
        log.warning(
          s"Giving up on trie node ${Hex.toHexString(task.hash.take(4).toArray)} after $maxRetriesPerTask retries. " +
            s"Deferring to state validation phase — node will be re-discovered and fetched during trie walk."
        )
      } else {
        pendingTasks = pendingTasks :+ updated
        requeued += 1
      }
    }

    if (requeued > 0) {
      log.info(s"Re-queued $requeued timed-out healing tasks (pending: ${pendingTasks.size})" +
        (if (abandoned > 0) s", abandoned $abandoned" else ""))
    }

    // Check global stagnation: no nodes healed for healingStagnationTimeoutMs
    val stagnantMs = System.currentTimeMillis() - lastHealedAtMs
    if (stagnantMs > healingStagnationTimeoutMs && pendingTasks.nonEmpty) {
      val pendingCount = pendingTasks.size
      log.warning(
        s"Healing stagnation timeout (${stagnantMs / 1000}s, no progress). " +
          s"Likely cause: peers lack GetTrieNodes support or all peers offline. " +
          s"Abandoning $pendingCount remaining tasks. Nodes will be re-discovered during state validation trie walk."
      )
      abandonedTaskCount += pendingCount
      // Persist the abandoned list before discarding — controller will use it to skip the trie
      // re-walk and retry healing directly (OPT-H2). force=true bypasses the counter gate.
      val abandonedSnapshot = pendingTasks.map(e => (e.pathset, e.hash))
      snapSyncController ! SNAPSyncController.PersistHealingQueue(abandonedSnapshot, force = true)
      pendingTasks = Seq.empty
      pendingHashSet.clear()
    }

    tryRedispatchPendingTasks()
    self ! HealingCheckCompletion
  }

  private def tryRedispatchPendingTasks(): Unit = {
    if (pendingTasks.isEmpty) return
    if (pivotRefreshRequested) return
    val eligiblePeers = knownAvailablePeers.toList
      .filterNot(isPeerCoolingDown)
      .filterNot(p => statelessPeers.contains(p.id.value))
      .filterNot(p => statelessRemoteAddresses.contains(p.remoteAddress.toString))
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
  private def discoverMissingChildren(nodeData: ByteString, pathset: Seq[ByteString]): Unit = {
    import com.chipprbots.ethereum.mpt.{MptTraversals, BranchNode, ExtensionNode, HashNode}
    import com.chipprbots.ethereum.mpt.HexPrefix
    import scala.util.control.NonFatal

    if (pathset.isEmpty) return

    try {
      val decoded = MptTraversals.decodeNode(nodeData.toArray)
      val parentCompact = pathset.last.toArray
      val parentNibbles = HexPrefix.decode(parentCompact)._1
      val isStorageTrie = pathset.size > 1  // Seq(accountHash, path) vs Seq(path)

      val newEntries = mutable.Buffer.empty[HealingEntry]

      decoded match {
        case branch: BranchNode =>
          for (i <- 0 until 16) {
            branch.children(i) match {
              case hash: HashNode =>
                val childHash = ByteString(hash.hashNode)
                if (!pendingHashSet.contains(childHash) && !isNodeInStorage(childHash)) {
                  val childNibbles = parentNibbles :+ i.toByte
                  val childCompact = ByteString(HexPrefix.encode(childNibbles, isLeaf = false))
                  val childPathset = if (isStorageTrie) Seq(pathset.head, childCompact) else Seq(childCompact)
                  newEntries += HealingEntry(childPathset, childHash)
                }
              case _ => // Inline-encoded or null — already resolved
            }
          }

        case ext: ExtensionNode =>
          ext.next match {
            case hash: HashNode =>
              val childHash = ByteString(hash.hashNode)
              if (!pendingHashSet.contains(childHash) && !isNodeInStorage(childHash)) {
                // ext.sharedKey is already HP-decoded nibbles (ByteString of nibble bytes)
                val childNibbles = parentNibbles ++ ext.sharedKey.toArray
                val childCompact = ByteString(HexPrefix.encode(childNibbles, isLeaf = false))
                val childPathset = if (isStorageTrie) Seq(pathset.head, childCompact) else Seq(childCompact)
                newEntries += HealingEntry(childPathset, childHash)
              }
            case _ => // Already inline-encoded — no missing child
          }

        case _ => // LeafNode, NullNode — no children to discover
      }

      if (newEntries.nonEmpty) {
        newEntries.foreach(e => pendingHashSet += e.hash)
        pendingTasks = pendingTasks ++ newEntries
        childrenDiscoveredTotal += newEntries.size
        if (childrenDiscoveredTotal % 100 == 0 || childrenDiscoveredTotal <= 20)
          log.info(
            s"[HEAL] Missing trie children queued: $childrenDiscoveredTotal total " +
              s"(+${newEntries.size} from this node, pending: ${pendingTasks.size})"
          )
      }
    } catch {
      case NonFatal(e) =>
        log.debug(s"[HEAL] Cannot decode healed node for child discovery: ${e.getMessage}. Skipping incremental discovery — full trie walk will find these nodes.")
        // Non-fatal — healing still works via final validation walk as safety net
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
