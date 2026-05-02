package com.chipprbots.ethereum.blockchain.sync.snap.actors

import org.apache.pekko.actor.{Actor, ActorLogging, ActorRef, Props, SupervisorStrategy, OneForOneStrategy}
import org.apache.pekko.actor.SupervisorStrategy._
import org.apache.pekko.util.ByteString
import org.bouncycastle.util.encoders.Hex

import scala.collection.mutable
import scala.concurrent.ExecutionContext
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
    concurrency: Int,
    healingWriterEcOverride: Option[ExecutionContext] = None
) extends Actor
    with ActorLogging {

  import Messages._

  // Task management — each task has a pathset (for GetTrieNodes) and a hash (for verification).
  // ArrayDeque (circular buffer) gives O(1) amortized head/tail operations (#1167). The previous
  // immutable `Seq` did O(n) on every `:+` and head-drop — quadratic at healing scale.
  private case class HealingEntry(pathset: Seq[ByteString], hash: ByteString, retries: Int = 0)
  private val pendingTasks: mutable.ArrayDeque[HealingEntry] = mutable.ArrayDeque.empty
  private var completedTaskCount: Int = 0
  private var abandonedTaskCount: Int = 0

  /** Dedicated dispatcher for the batched raw-node RocksDB flush. Tests inject their own EC; production looks up
    * `healing-writer-dispatcher` from the actor system. Keeps the blocking write off `sync-dispatcher` so other sync
    * actors don't stall during healing-heavy bursts.
    */
  private val healingWriterEc: ExecutionContext =
    healingWriterEcOverride.getOrElse(context.system.dispatchers.lookup("healing-writer-dispatcher"))

  // Per-task retry limit: after this many timeouts/failures, skip the task.
  // At ~6s per timeout cycle, 20 retries = ~2 minutes of trying per node.
  private val maxRetriesPerTask: Int = 20

  // Global stagnation detection: if no nodes healed for this duration, declare
  // healing complete with a warning. Prevents infinite loops when all peers lack
  // GetTrieNodes support (ETH68 networks). Regular sync fetches missing nodes on-demand.
  private var lastHealedAtMs: Long = System.currentTimeMillis()
  private val healingStagnationTimeoutMs: Long = 5 * 60 * 1000 // 5 minutes

  // Periodic idle stagnation escalation: fires even when no requests are active.
  // The per-timeout stagnation check at handleTimeout() requires a live request to time out;
  // if pendingTasks is non-empty but activeRequests is empty (all peers cooling down),
  // healing can stall silently. After 5 consecutive 2-minute ticks with no active requests
  // and no progress (10 minutes total), force-complete healing.
  // Reference: Besu markAsStalled() is a TODO no-op; this is a fukuii-specific liveness guarantee.
  private case object HealingStagnationCheck
  private var consecutiveIdleChecks: Int = 0
  private var lastPulseHealedCount: Int = 0
  // FIX-STAGNATION-LIMIT: Track consecutive 2-min cycles with zero healed nodes (even when active).
  // After MaxConsecutiveStagnations, notify controller to restart with fresh pivot.
  private var consecutiveStagnations: Int = 0
  private val MaxConsecutiveStagnations: Int = 3
  // Inline child discovery counter (Besu-aligned scheduler approach)
  private var childrenDiscoveredTotal: Long = 0

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

  // Adaptive healing throttle (geth p2p/msgrate alignment)
  // When pending nodes exceed 2× the processing rate, throttle increases (slow down requests).
  // When below, throttle decreases (speed up). Prevents pending queue overflow / OOM.
  private var healRate: Double = 0.0 // items/sec EMA
  private var healThrottle: Double = 1.0 // divisor (1 = full speed, 4096 = one node at a time)
  private var healPending: Long = 0 // nodes queued for DB write (rawNodeBuffer.size)
  private var lastThrottleAdjustMs: Long = System.currentTimeMillis()

  private val ThrottleIncrease = 1.33
  private val ThrottleDecrease = 1.25
  // MaxThrottle caps the divisor on `batchSize` (default 32). With MaxThrottle=4 the floor
  // is 32/4 = 8 paths per GetTrieNodes request, well above the previous floor of 2 that
  // throttled healing to ~6 nodes/sec on Mordor (issue #1159). The cap is high enough to
  // brake hard if the disk-flush thread genuinely can't keep up, but doesn't permanently
  // pin batches at a wire-inefficient size.
  private val MaxThrottle = 4.0
  private val MinThrottle = 1.0
  // Throttle up only when the unflushed buffer is genuinely contended — at 80% of the
  // flush threshold. The previous heuristic (`healPending > 2 * healRate`) compared an
  // absolute buffer size (max ~1000) against a rate-derived target (~10 at 5 nodes/sec),
  // which the buffer almost always exceeds, locking healThrottle at MaxThrottle forever.
  private val ThrottleUpFillRatio = 0.8
  private val RateMeasurementImpact = 0.005 // geometric EMA weight per node

  // Track last known available peers for re-dispatch after failures
  private val knownAvailablePeers = mutable.Set[Peer]()

  // Dedup set for pending tasks — prevents the same missing node from being queued multiple times
  private val pendingHashSet = mutable.Set[ByteString]()

  // Stateless peer tracking (geth-aligned: peers that return empty TrieNodes for current root)
  private val statelessPeers = mutable.Set[String]()
  private var pivotRefreshRequested: Boolean = false
  private var pivotRefreshRequestedAt: Long = 0L
  private val PivotRefreshWatchdogMs: Long = 15.minutes.toMillis

  // Consecutive timeout tracking: peers that time out repeatedly are treated as stateless.
  // Timeouts on ETH mainnet peers (which advertise snap/1 but lack ETC state) produce 60s hangs
  // without ever returning an empty response — the empty-response path alone is insufficient.
  private val peerConsecutiveTimeouts = mutable.Map[String, Int]()
  private val MaxConsecutiveTimeoutsBeforeStateless = 3

  // Per-peer adaptive byte budgeting
  private val minResponseBytes: BigInt = 50 * 1024
  private val maxResponseBytes: BigInt = 2 * 1024 * 1024
  private val initialResponseBytes: BigInt = 512 * 1024
  private val increaseFactor: Double = 1.25
  private val decreaseFactor: Double = 0.5

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
      s"Reducing healing responseBytes target for peer ${peer.id.value}: $cur -> ${peerResponseBytesTarget(peer.id.value)} ($reason)"
    )
  }

  // Peer cooldown
  private val peerCooldownUntilMs = mutable.Map[String, Long]()
  private val peerCooldownDefault = 30.seconds

  private def isPeerCoolingDown(peer: Peer): Boolean =
    peerCooldownUntilMs.get(peer.id.value).exists(_ > System.currentTimeMillis())

  private def recordPeerCooldown(peer: Peer, reason: String): Unit = {
    val until = System.currentTimeMillis() + peerCooldownDefault.toMillis
    peerCooldownUntilMs.put(peer.id.value, until)
    log.debug(s"Cooling down peer ${peer.id.value} for ${peerCooldownDefault.toSeconds}s: $reason")
  }

  /** Count in-flight requests for a given peer (pipelining support). */
  private def inFlightForPeer(peer: Peer): Int =
    activeRequests.values.count(_.peer.id == peer.id)

  /** Dispatch up to maxInFlightPerPeer requests to a single peer (pipelining). */
  private def dispatchIfPossible(peer: Peer): Unit = {
    if (pivotRefreshRequested) return
    if (statelessPeers.contains(peer.id.value)) return
    if (isPeerCoolingDown(peer)) return
    var inflight = inFlightForPeer(peer)
    while (pendingTasks.nonEmpty && inflight < maxInFlightPerPeer && activeRequests.size < maxConcurrentRequests)
      requestNextBatch(peer) match {
        case Some(_) => inflight += 1
        case None    => return
      }
  }

  // Batched raw node storage: accumulate nodes and flush asynchronously
  private val rawNodeBuffer = mutable.ArrayBuffer[(ByteString, Array[Byte])]()
  private val rawFlushThreshold = 1000
  private var flushing: Boolean = false

  // Internal message for async flush completion
  private case class FlushComplete(count: Int)

  /** Synchronous flush — used only for final completion flush (small buffer, safe to block). */
  private def flushRawNodesSync(): Unit =
    if (rawNodeBuffer.nonEmpty) {
      mptStorage.storeRawNodes(rawNodeBuffer.toSeq)
      mptStorage.persist()
      val count = rawNodeBuffer.size
      rawNodeBuffer.clear()
      log.info(s"Flushed $count healed nodes to disk (total: $totalNodesHealed)")
    }

  /** Async flush — copies buffer, clears it, writes on the dedicated `healing-writer-dispatcher` so the blocking
    * RocksDB write doesn't compete with sync actors on `sync-dispatcher`.
    */
  private def flushRawNodesAsync(): Unit =
    if (rawNodeBuffer.nonEmpty && !flushing) {
      flushing = true
      val nodes = rawNodeBuffer.toSeq
      rawNodeBuffer.clear()
      import scala.concurrent.{Future, blocking}
      val selfRef = self
      val ec = healingWriterEc
      Future {
        blocking {
          mptStorage.storeRawNodes(nodes)
          mptStorage.persist()
          nodes.size
        }
      }(ec).foreach(n => selfRef ! FlushComplete(n))(ec)
    }

  override def preStart(): Unit = {
    log.info(s"TrieNodeHealingCoordinator starting (concurrency=$concurrency)")
    context.system.scheduler.scheduleWithFixedDelay(2.minutes, 2.minutes, self, HealingStagnationCheck)(
      context.dispatcher
    )
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
      // Prior: waited 3+ hours for walk results. Now: healing starts instantly.
      // When root is healed, discoverMissingChildren() discovers all children inline.
      // Walk becomes validation-only — its results remain additive (dedup prevents duplicates).
      val emptyPath = ByteString(com.chipprbots.ethereum.mpt.HexPrefix.encode(Array.empty[Byte], isLeaf = false))
      queueNodes(Seq((Seq(emptyPath), root)))
      log.info(
        s"[HEAL] Root ${Hex.toHexString(root.take(8).toArray)} seeded — " +
          s"inline child discovery will populate work queue top-down (Besu-aligned)"
      )
      lastHealedAtMs = System.currentTimeMillis()

    case QueueMissingNodes(nodes) =>
      log.info(s"Queuing ${nodes.size} missing nodes for healing")
      queueNodes(nodes)
      // Immediately dispatch to any known available peers
      tryRedispatchPendingTasks()

    case HealingPeerAvailable(peer) =>
      // Evict stale entry for same physical node (reconnection creates new PeerId)
      knownAvailablePeers.filterInPlace(_.remoteAddress != peer.remoteAddress)
      knownAvailablePeers += peer
      dispatchIfPossible(peer)

    case HealingPeerUnavailable(peerId) =>
      // Peer disconnected — remove from available set and immediately re-queue its in-flight
      // tasks so other peers can pick them up without waiting for the 30s request timeout.
      // Mirrors AccountRangeCoordinator.PeerUnavailable (go-ethereum revertRequests pattern).
      knownAvailablePeers.filterInPlace(_.id.value != peerId)
      peerConsecutiveTimeouts.remove(peerId)
      val inFlight = activeRequests.filter { case (_, req) => req.peer.id.value == peerId }.keys.toSeq
      if (inFlight.nonEmpty) {
        log.debug(s"Peer $peerId disconnected — re-queuing ${inFlight.size} in-flight healing request(s)")
        inFlight.foreach { reqId =>
          activeRequests.remove(reqId).foreach { req =>
            requestTracker.completeRequest(reqId, 0)
            req.tasks.foreach { task =>
              if (!pendingHashSet.contains(task.hash)) {
                pendingHashSet += task.hash
                pendingTasks = pendingTasks :+ task
              }
            }
          }
        }
      }
      tryRedispatchPendingTasks()

    case UpdateMaxInFlightPerPeer(newLimit) =>
      log.info(s"Healing per-peer budget: $maxInFlightPerPeer -> $newLimit")
      maxInFlightPerPeer = newLimit
      if (newLimit > 0) tryRedispatchPendingTasks()

    case HealingForceComplete =>
      log.warning(
        s"[HEAL-FORCE-COMPLETE] Pivot advanced beyond SNAP serve window — " +
          s"clearing ${pendingTasks.size} pending tasks + ${activeRequests.size} in-flight. " +
          s"Signaling completion with $totalNodesHealed healed nodes."
      )
      activeRequests.keys.foreach(requestTracker.completeRequest(_, 0))
      activeRequests.clear()
      pendingTasks.clear()
      pendingHashSet.clear()
      snapSyncController ! SNAPSyncController.StateHealingComplete
      context.stop(self)

    case HealingPivotRefreshed(newStateRoot) =>
      val oldRoot = Hex.toHexString(stateRoot.take(4).toArray)
      val newRootHex = Hex.toHexString(newStateRoot.take(4).toArray)
      log.info(
        s"Healing pivot refreshed: $oldRoot -> $newRootHex. " +
          s"Clearing ${pendingTasks.size} pending tasks, ${statelessPeers.size} stateless peers."
      )
      stateRoot = newStateRoot
      flushRawNodesSync() // Flush any buffered nodes before clearing state
      pendingTasks.clear() // Will be re-populated by root reseed + inline discovery / trie walk from controller
      pendingHashSet.clear()
      statelessPeers.clear()
      peerConsecutiveTimeouts.clear()
      peerCooldownUntilMs.clear()
      peerResponseBytesTarget.clear()
      // Cancel active requests (they're for the old root)
      activeRequests.keys.foreach(requestTracker.completeRequest(_, 0))
      activeRequests.clear()
      pivotRefreshRequested = false
      consecutiveIdleChecks = 0
      consecutiveStagnations = 0
      lastPulseHealedCount = totalNodesHealed
      lastHealedAtMs = System.currentTimeMillis() // BUG-4: give fresh pivot a full stagnation window
      // ARCH-PIVOT-RESEED: Re-seed with new root for top-down discovery of trie delta.
      // Content-addressed inline tasks (~99% valid) were cleared — new root seeds a fresh
      // top-down traversal of the updated trie.
      val pivotReseedPath = ByteString(com.chipprbots.ethereum.mpt.HexPrefix.encode(Array.empty[Byte], isLeaf = false))
      if (!pendingHashSet.contains(newStateRoot) && !isNodeInStorage(newStateRoot)) {
        pendingTasks += HealingEntry(Seq(pivotReseedPath), newStateRoot)
        pendingHashSet += newStateRoot
        log.info(
          s"[HEAL] Re-seeded with new root ${Hex.toHexString(newStateRoot.take(4).toArray)} " +
            s"for inline discovery of pivot delta"
        )
      } else {
        log.info(s"[HEAL] New root already in storage or pending — skipping pivot reseed")
      }

    case TrieNodesResponseMsg(response) =>
      handleResponse(response)

    case FlushComplete(count) =>
      flushing = false
      log.info(s"Async flush complete: $count healed nodes written to disk (total: $totalNodesHealed)")
      // Check if buffer filled up again during the flush
      if (rawNodeBuffer.size >= rawFlushThreshold) {
        flushRawNodesAsync()
      }
      self ! HealingCheckCompletion

    case HealingTaskComplete(requestId, result) =>
      result match {
        case Right(count) =>
          totalNodesHealed += count
          log.info(s"Healing task completed: $count nodes")
          self ! HealingCheckCompletion
        case Left(error) =>
          log.warning(s"Healing task failed: $error")
      }

    case HealingCheckCompletion =>
      if (isComplete && !flushing) {
        flushRawNodesSync()
        log.info(s"Healing round complete: $totalNodesHealed total nodes healed. Notifying controller.")
        snapSyncController ! SNAPSyncController.StateHealingComplete
      }

    case HealingStagnationCheck =>
      val recentHealed = totalNodesHealed - lastPulseHealedCount
      log.info(
        s"[HEAL-PULSE] healed=$totalNodesHealed (+$recentHealed last 2min) | " +
          s"pending=${pendingTasks.size} active=${activeRequests.size} peers=${knownAvailablePeers.size} | " +
          s"pivotRefreshPending=$pivotRefreshRequested"
      )
      lastPulseHealedCount = totalNodesHealed

      // BUG-S4 watchdog: if pivotRefreshRequested=true for >15 min, SNAPSyncController is stuck
      // in refreshPivotInPlace's no-peer retry loop (Path A: 30s interval, HealingPivotRefreshed
      // never sent). Safe to reset: stateRoot stays valid; stagnation re-fires if still stuck.
      if (pivotRefreshRequested) {
        val waitedMs = System.currentTimeMillis() - pivotRefreshRequestedAt
        if (waitedMs > PivotRefreshWatchdogMs) {
          log.warning(
            s"[HEAL] Pivot refresh watchdog: pivotRefreshRequested=true for ${waitedMs / 1000}s — " +
              s"SNAPSyncController refresh stalled (no-peer retry loop). Resetting and resuming dispatch."
          )
          pivotRefreshRequested = false
          tryRedispatchPendingTasks()
        }
      }

      // FIX-STAGNATION-LIMIT: Track consecutive zero-progress cycles (independent of active count).
      // After MaxConsecutiveStagnations, notify controller to restart with fresh pivot.
      // Catches the case where active > 0 but all responses are empty (stale root, ETH mainnet peers).
      if (recentHealed == 0 && pendingTasks.nonEmpty && !pivotRefreshRequested) {
        consecutiveStagnations += 1
        log.warning(
          s"[HEAL-STAGNATION] Zero progress in last 2min — stagnation $consecutiveStagnations/$MaxConsecutiveStagnations. " +
            s"healed=$totalNodesHealed pending=${pendingTasks.size} peers=${knownAvailablePeers.size}"
        )
        if (consecutiveStagnations >= MaxConsecutiveStagnations) {
          log.warning(
            s"[HEAL-STAGNATION] $MaxConsecutiveStagnations consecutive zero-progress cycles — " +
              s"notifying controller to restart healing with fresh pivot"
          )
          snapSyncController ! HealingStagnated(totalNodesHealed.toLong, pendingTasks.size.toLong)
          consecutiveStagnations = 0
        }
      } else if (recentHealed > 0) {
        consecutiveStagnations = 0
      }

      if (pendingTasks.nonEmpty && activeRequests.isEmpty && !pivotRefreshRequested) {
        consecutiveIdleChecks += 1
        if (consecutiveIdleChecks >= 5) {
          val pendingCount = pendingTasks.size
          log.warning(
            s"[HEAL] No active requests for ${consecutiveIdleChecks * 2} minutes with " +
              s"$pendingCount pending tasks and ${knownAvailablePeers.size} known peers. " +
              s"Clearing stateless/cooldown peer state and retrying before abandoning."
          )
          // Clear all peer-failure state so the next healing round gets fresh dispatch eligibility.
          // All peers were marked stateless because they returned empty TrieNodes responses for the
          // current root (e.g. v1.12.20 core-geth, ETH mainnet peers without ETC state). A new peer
          // (e.g. v1.13.0 core-geth with SNAP serving fixes) may now be connected and able to serve.
          // Without this clear, eligiblePeers stays empty forever — HealingAllPeersStateless can't
          // fire because fresh peers keep arriving, keeping knownAvailablePeers.size > statelessPeers.size.
          statelessPeers.clear()
          peerConsecutiveTimeouts.clear()
          peerCooldownUntilMs.clear()
          consecutiveIdleChecks = 0 // Reset so we don't immediately force-complete on the next tick
          log.info(
            s"[HEAL] Stateless/cooldown peer state cleared. Attempting dispatch to ${knownAvailablePeers.size} peers."
          )
          tryRedispatchPendingTasks()
        } else {
          tryRedispatchPendingTasks()
        }
      } else {
        consecutiveIdleChecks = 0
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

    case HealingRequestTimeout(requestId) =>
      // Timeout delivered via actor mailbox (BUG-H4 fix): runs on actor thread,
      // safe to read/write activeRequests and pendingTasks.
      activeRequests.get(requestId) match {
        case Some(req) => handleTimeout(requestId, req.tasks, req.peer)
        case None      => // Response already arrived — stale timeout, nothing to do
      }
  }

  private def queueNodes(pathsAndHashes: Seq[(Seq[ByteString], ByteString)]): Unit = {
    val entries = pathsAndHashes.collect {
      case (pathset, hash) if !pendingHashSet.contains(hash) =>
        pendingHashSet += hash
        HealingEntry(pathset = pathset, hash = hash)
    }
    val deduped = pathsAndHashes.size - entries.size
    pendingTasks ++= entries
    val dedupStr = if (deduped > 0) s" ($deduped duplicates filtered)" else ""
    log.info(s"Queued ${entries.size} nodes for healing$dedupStr. Total pending: ${pendingTasks.size}")
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
      // Throttle up only when the disk-flush thread is genuinely behind — i.e. the buffer
      // is filling toward its flush threshold. Comparing buffer fill (an absolute count)
      // against the rate-derived target was a category error: the buffer almost always
      // exceeds 2*rate, which locked healThrottle at MaxThrottle and pinned the batch at
      // batchSize/MaxThrottle paths per request. See issue #1159.
      val flushBackpressure = healPending > rawFlushThreshold * ThrottleUpFillRatio
      if (flushBackpressure) {
        healThrottle = (healThrottle * ThrottleIncrease).min(MaxThrottle)
      } else {
        healThrottle = (healThrottle / ThrottleDecrease).max(MinThrottle)
      }
      if (oldThrottle != healThrottle) {
        log.debug(
          f"Healing throttle adjusted: $oldThrottle%.1f -> $healThrottle%.1f " +
            f"(rate=$healRate%.1f nodes/s, bufferFill=$healPending/$rawFlushThreshold, batch=${effectiveBatchSize})"
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
    val takeCount = effectiveBatch.min(pendingTasks.size)
    val batch: Seq[HealingEntry] = pendingTasks.iterator.take(takeCount).toSeq
    pendingTasks.dropInPlace(takeCount)
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

    // Send timeout as an actor message so handleTimeout runs on the actor thread,
    // not the scheduler thread. Direct callback would race against receive() handlers
    // that mutate activeRequests/pendingTasks concurrently (BUG-H4).
    requestTracker.trackRequest(requestId, peer, SNAPRequestTracker.RequestType.GetTrieNodes) {
      self ! HealingRequestTimeout(requestId)
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
        log.warning(s"No active healing request found for requestId=$requestId")
        return
    }

    val tasksForRequest = activeReq.tasks
    val peer = activeReq.peer
    val requestedBytes = activeReq.requestedBytes

    var healedCount = 0
    var receivedBytes: Long = 0

    // Hash-based matching — NOT positional. Servers (core-geth handler.go:546-547)
    // omit entries for storage pathsets whose account is missing, returning sparse
    // responses. Positional zip would align later nodes against the wrong tasks.
    val keccak = new org.bouncycastle.jcajce.provider.digest.Keccak.Digest256()
    val taskByHash = tasksForRequest.map(t => t.hash -> t).toMap
    val healedHashes = mutable.Set[ByteString]()

    nodes.foreach { nodeData =>
      if (nodeData.nonEmpty) {
        keccak.reset()
        val nodeHash = ByteString(keccak.digest(nodeData.toArray))
        if (taskByHash.contains(nodeHash)) {
          rawNodeBuffer += ((nodeHash, nodeData.toArray))
          healedCount += 1
          totalNodesHealed += 1
          receivedBytes += nodeData.length
          totalBytesReceived += nodeData.length
          healedHashes += nodeHash
          // Inline child discovery — Besu/geth aligned scheduler approach.
          // Each healed node is decoded to find missing children; queue them directly
          // without waiting for a full 3h trie walk. Walk becomes validation-only.
          taskByHash.get(nodeHash).foreach(task => discoverMissingChildren(nodeData, task.pathset))
        } else {
          log.debug(
            s"Healing response node not in request set (unexpected): ${Hex.toHexString(nodeHash.take(4).toArray)}"
          )
        }
      }
    }

    // Re-queue tasks not satisfied by this response (server skipped or didn't have them).
    // Restore to dedup set so QueueMissingNodes doesn't add duplicates (BUG-H1 fix).
    tasksForRequest.foreach { task =>
      if (!healedHashes.contains(task.hash)) {
        pendingHashSet += task.hash
        pendingTasks += task
      }
    }

    completedTaskCount += healedCount
    activeRequests.remove(requestId)
    requestTracker.completeRequest(requestId, nodes.size.max(1))

    // Update healing throttle (geth msgrate alignment)
    val elapsedMs = System.currentTimeMillis() - activeReq.sentAtMs
    updateHealThrottle(healedCount, elapsedMs)

    // Adaptive byte budget + stateless tracking
    if (healedCount > 0) {
      adjustResponseBytesOnSuccess(peer, requestedBytes, BigInt(receivedBytes))
      // Successful response — clear stateless marking and reset stagnation timer
      statelessPeers -= peer.id.value
      peerConsecutiveTimeouts.remove(peer.id.value)
      lastHealedAtMs = System.currentTimeMillis()
    } else {
      adjustResponseBytesOnFailure(peer, "empty healing response")
      recordPeerCooldown(peer, "empty healing response")
      // Mark peer stateless for current root (geth-aligned)
      statelessPeers += peer.id.value
      log.info(
        s"Peer ${peer.id.value} marked stateless for healing root " +
          s"${Hex.toHexString(stateRoot.take(4).toArray)} (${statelessPeers.size}/${knownAvailablePeers.size} stateless)"
      )
      // Check if all known peers are stateless — request pivot refresh
      if (statelessPeers.size >= knownAvailablePeers.size && knownAvailablePeers.nonEmpty && !pivotRefreshRequested) {
        pivotRefreshRequested = true
        pivotRefreshRequestedAt = System.currentTimeMillis()
        log.warning(
          s"All ${statelessPeers.size} peers stateless for healing root " +
            s"${Hex.toHexString(stateRoot.take(4).toArray)}. Requesting pivot refresh."
        )
        snapSyncController ! SNAPSyncController.HealingAllPeersStateless
      }
    }

    log.info(
      s"Healed $healedCount/${nodes.size} trie nodes from peer ${peer.id.value} " +
        s"(total: $totalNodesHealed, pending: ${pendingTasks.size}, active: ${activeRequests.size} reqs, " +
        s"responseBytes=${responseBytesTargetFor(peer)})"
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

    // Escalate repeated timeouts to stateless: ETH mainnet peers advertise snap/1 but lack ETC
    // state and never return an empty response — they simply time out indefinitely. After
    // MaxConsecutiveTimeoutsBeforeStateless timeouts we treat the peer as stateless, the same as
    // an empty response, so that HealingAllPeersStateless fires instead of looping forever.
    val timeoutCount = peerConsecutiveTimeouts.getOrElse(peer.id.value, 0) + 1
    peerConsecutiveTimeouts.update(peer.id.value, timeoutCount)
    if (timeoutCount >= MaxConsecutiveTimeoutsBeforeStateless && !statelessPeers.contains(peer.id.value)) {
      statelessPeers += peer.id.value
      log.info(
        s"Peer ${peer.id.value} marked stateless after $timeoutCount consecutive timeouts " +
          s"(${statelessPeers.size}/${knownAvailablePeers.size} stateless)"
      )
      if (statelessPeers.size >= knownAvailablePeers.size && knownAvailablePeers.nonEmpty && !pivotRefreshRequested) {
        pivotRefreshRequested = true
        pivotRefreshRequestedAt = System.currentTimeMillis()
        log.warning(
          s"All ${statelessPeers.size} healing peers stateless (via timeouts) for root " +
            s"${Hex.toHexString(stateRoot.take(4).toArray)}. Requesting pivot refresh."
        )
        snapSyncController ! SNAPSyncController.HealingAllPeersStateless
      }
    }

    // Re-queue all timed-out tasks unconditionally — aligned with go-ethereum and Besu pipeline
    // behaviour. Both reference clients never permanently abandon nodes: go-ethereum puts them
    // straight back into trieTasks (no counter); Besu re-queues at the pipeline level after each
    // RetryingGetTrieNodeFromPeerTask attempt. Permanently unservable nodes are handled by the
    // stagnation → pivot-refresh path, not a per-node retry cap.
    var requeued = 0
    tasks.foreach { task =>
      pendingHashSet += task.hash
      pendingTasks += task
      requeued += 1
    }

    if (requeued > 0) {
      log.info(s"Re-queued $requeued timed-out healing tasks (pending: ${pendingTasks.size})")
    }

    // Check global stagnation: no nodes healed for healingStagnationTimeoutMs.
    // BUG-3 fix: escalate to pivot refresh instead of abandoning. The old behaviour
    // (abandon + StateHealingComplete) raced with the consecutiveStagnations path (6 min)
    // and always won at 5 min, bypassing pivot refresh entirely.
    val stagnantMs = System.currentTimeMillis() - lastHealedAtMs
    if (stagnantMs > healingStagnationTimeoutMs && pendingTasks.nonEmpty && !pivotRefreshRequested) {
      log.warning(
        s"[HEAL] Stagnation: no nodes healed in ${stagnantMs / 1000}s — requesting pivot refresh"
      )
      lastHealedAtMs = System.currentTimeMillis() // prevent re-firing while refresh in flight
      snapSyncController ! actors.Messages.HealingStagnated(totalNodesHealed.toLong, pendingTasks.size.toLong)
      pivotRefreshRequested = true
      pivotRefreshRequestedAt = System.currentTimeMillis()
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
    if (eligiblePeers.isEmpty) return

    for (peer <- eligiblePeers if pendingTasks.nonEmpty)
      dispatchIfPossible(peer)
  }

  private def calculateProgress(): Double = {
    val activeTaskCount = activeRequests.values.map(_.tasks.size).sum
    val total = pendingTasks.size + activeTaskCount + completedTaskCount
    if (total == 0) 1.0
    else completedTaskCount.toDouble / total
  }

  private def calculateNodesPerSecond(): Double = {
    val elapsedSec = (System.currentTimeMillis() - startTime) / 1000.0
    if (elapsedSec > 0) totalNodesHealed / elapsedSec else 0.0
  }

  private def calculateKilobytesPerSecond(): Double = {
    val elapsedSec = (System.currentTimeMillis() - startTime) / 1000.0
    if (elapsedSec > 0) (totalBytesReceived / 1024.0) / elapsedSec else 0.0
  }

  private def isComplete: Boolean =
    pendingTasks.isEmpty && activeRequests.isEmpty

  /** Inline child discovery after each healed node — Besu/geth scheduler-driven alignment. Decodes the healed node,
    * discovers child hashes, checks storage, queues missing children. Makes healing self-feeding: root → children →
    * grandchildren top-down without trie walk.
    */
  private def discoverMissingChildren(nodeData: ByteString, pathset: Seq[ByteString]): Unit = {
    import com.chipprbots.ethereum.mpt.{MptTraversals, BranchNode, ExtensionNode, HashNode, LeafNode}
    import com.chipprbots.ethereum.mpt.HexPrefix
    import com.chipprbots.ethereum.domain.Account
    import scala.util.control.NonFatal

    if (pathset.isEmpty) return

    try {
      val decoded = MptTraversals.decodeNode(nodeData.toArray)
      val parentCompact = pathset.last.toArray
      val parentNibbles = HexPrefix.decode(parentCompact)._1
      val isStorageTrie = pathset.size > 1 // Seq(accountHash, path) vs Seq(path)

      val newEntries = mutable.Buffer.empty[HealingEntry]

      decoded match {
        case branch: BranchNode =>
          for (i <- 0 until 16)
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

        case ext: ExtensionNode =>
          ext.next match {
            case hash: HashNode =>
              val childHash = ByteString(hash.hashNode)
              if (!pendingHashSet.contains(childHash) && !isNodeInStorage(childHash)) {
                val childNibbles = parentNibbles ++ ext.sharedKey.toArray
                val childCompact = ByteString(HexPrefix.encode(childNibbles, isLeaf = false))
                val childPathset = if (isStorageTrie) Seq(pathset.head, childCompact) else Seq(childCompact)
                newEntries += HealingEntry(childPathset, childHash)
              }
            case _ => // Already inline-encoded — no missing child
          }

        case leaf: LeafNode if !isStorageTrie =>
          // ARCH-LEAF-SEED: Account trie leaf — decode account, seed storage trie if missing.
          // Besu equivalent: getChildRequests() → getStorageTrieNodeRequests() on account leaf values.
          Account(leaf.value).foreach { account =>
            if (
              account.storageRoot != Account.EmptyStorageRootHash &&
              !pendingHashSet.contains(account.storageRoot) &&
              !isNodeInStorage(account.storageRoot)
            ) {
              val leafNibbles = leaf.key.toArray
              val allNibbles = parentNibbles ++ leafNibbles
              if (allNibbles.length == 64) {
                val accountHashBytes = allNibbles
                  .grouped(2)
                  .map { g =>
                    ((g(0) << 4) | g(1)).toByte
                  }
                  .toArray
                val accountHash = ByteString(accountHashBytes)
                val emptyStoragePath = ByteString(HexPrefix.encode(Array.empty[Byte], isLeaf = false))
                newEntries += HealingEntry(Seq(accountHash, emptyStoragePath), account.storageRoot)
                log.debug(
                  s"[HEAL-LEAF] Seeded storage trie root ${Hex.toHexString(account.storageRoot.take(4).toArray)} " +
                    s"for account ${Hex.toHexString(accountHashBytes.take(4))}"
                )
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
            s"[HEAL-DISCOVER] Inline children queued: $childrenDiscoveredTotal total " +
              s"(+${newEntries.size} from this node, pending: ${pendingTasks.size})"
          )
      }
    } catch {
      case NonFatal(e) =>
        log.debug(
          s"[HEAL] Cannot decode healed node for child discovery: ${e.getMessage}. " +
            s"Skipping — trie walk will find these nodes."
        )
    }
  }

  private def isNodeInStorage(hash: ByteString): Boolean =
    try { mptStorage.get(hash.toArray); true }
    catch { case _: Exception => false }
}

object TrieNodeHealingCoordinator {
  def props(
      stateRoot: ByteString,
      networkPeerManager: ActorRef,
      requestTracker: SNAPRequestTracker,
      mptStorage: MptStorage,
      batchSize: Int,
      snapSyncController: ActorRef,
      concurrency: Int = 16,
      healingWriterEcOverride: Option[ExecutionContext] = None
  ): Props =
    Props(
      new TrieNodeHealingCoordinator(
        stateRoot,
        networkPeerManager,
        requestTracker,
        mptStorage,
        batchSize,
        snapSyncController,
        concurrency,
        healingWriterEcOverride
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
