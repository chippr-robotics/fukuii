package com.chipprbots.ethereum.blockchain.sync.snap.actors

import org.apache.pekko.actor.{Actor, ActorLogging, ActorRef, Cancellable, Props, SupervisorStrategy, OneForOneStrategy}
import org.apache.pekko.actor.SupervisorStrategy._
import org.apache.pekko.util.ByteString
import org.bouncycastle.util.encoders.Hex

import net.logstash.logback.argument.StructuredArguments.kv

import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.Try

import com.chipprbots.ethereum.blockchain.sync.snap._
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController
import com.chipprbots.ethereum.db.storage.{AppStateStorage, MptStorage}
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
    healingWriterEcOverride: Option[ExecutionContext] = None,
    stagnationCheckInterval: FiniteDuration = 2.minutes,
    maxConsecutiveStagnations: Int = 3,
    appStateStorage: AppStateStorage
) extends Actor
    with ActorLogging {

  private given ActorRef = ActorRef.noSender

  import Messages._

  private val slog = org.slf4j.LoggerFactory.getLogger(getClass)

  // Task management — each task has a pathset (for GetTrieNodes), a hash (for verification), and
  // a depth-first priority. Priority: root=0, children = parent*16 + childIndex (Besu-aligned).
  // Lower value = dispatched first. PriorityQueue is a min-heap via reversed Ordering (#1167 fixed
  // O(n²); B4 upgrades to depth-first for RocksDB block-cache locality).
  private[actors] case class HealingEntry(pathset: Seq[ByteString], hash: ByteString, priority: Long = 0L)
  private[actors] val pendingTasks: mutable.PriorityQueue[HealingEntry] =
    mutable.PriorityQueue.empty[HealingEntry](Ordering.by[HealingEntry, Long](_.priority).reverse)
  private var completedTaskCount: Int = 0

  /** Dedicated dispatcher for the batched raw-node RocksDB flush. Tests inject their own EC; production looks up
    * `healing-writer-dispatcher` from the actor system. Keeps the blocking write off `sync-dispatcher` so other sync
    * actors don't stall during healing-heavy bursts.
    */
  private val healingWriterEc: ExecutionContext =
    healingWriterEcOverride.getOrElse(context.system.dispatchers.lookup("healing-writer-dispatcher"))

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
  private var lastFrontierSize: Long = 0L
  // FIX-STAGNATION-LIMIT: Track consecutive 2-min cycles with zero healed nodes (even when active).
  // After maxConsecutiveStagnations, notify controller to restart with fresh pivot.
  private var consecutiveStagnations: Int = 0
  private var trieWalkInProgress: Boolean = false
  // Inline child discovery counter (Besu-aligned scheduler approach)
  private var childrenDiscoveredTotal: Long = 0

  // Active request tracking: maps requestId -> (tasks, peer, requestedBytes, rootAtDispatch, sentAtMs)
  private case class ActiveRequest(
      tasks: Seq[HealingEntry],
      peer: Peer,
      requestedBytes: BigInt,
      rootAtDispatch: ByteString,
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
  private var healingMilestonePct: Int = -1

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

  // Crash-recovery DFS: emit frontier in batches so healing starts before traversal completes.
  // go-ethereum trie.Sync.Missing() alignment — bounded working set rather than full upfront BFS.
  private val FrontierBatchSize = 1000

  // Track last known available peers for re-dispatch after failures
  private val knownAvailablePeers = mutable.Set[Peer]()

  // Dedup set for pending tasks — prevents the same missing node from being queued multiple times
  private val pendingHashSet = mutable.Set[ByteString]()

  // Stateless peer tracking (geth-aligned: peers that return empty TrieNodes for current root)
  private val statelessPeers = mutable.Set[String]()
  private var pivotRefreshRequested: Boolean = false
  private var pivotRefreshRequestedAt: Long = 0L
  private val PivotRefreshWatchdogMs: Long = 15.minutes.toMillis

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
    slog.debug(
      "Cooling down peer",
      kv("peer", peer.id.value),
      kv("cooldownSecs", peerCooldownDefault.toSeconds),
      kv("reason", reason)
    )
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

  // Raw node buffer: flushed asynchronously after every peer response (per-response strategy —
  // healing is sparse gap-filling so immediate persistence is preferred over batch accumulation).
  // `private[actors]` so TrieNodeHealingCoordinatorSpec can drive flush tests via TestActorRef.
  private[actors] val rawNodeBuffer = mutable.ArrayBuffer[(ByteString, Array[Byte])]()
  private[actors] var flushing: Boolean = false
  // Throttle backpressure: if buffer exceeds this many nodes the write thread is falling behind.
  // Distinct from the (removed) count-based flush threshold — this is a heuristic for dispatch throttling only.
  private val rawBufferBackpressureThreshold = 50

  // Internal message for async flush completion
  private case class FlushComplete(count: Int)

  // Internal message for async frontier rebuild completion (crash-recovery BFS)
  // Retained until C1 removes rebuildFrontierDFS
  private case class FrontierRebuilt(entries: Seq[HealingEntry])
  private case class FrontierRebuildFailed(root: ByteString)

  // Frontier serialization: "hashHex:pathHex1,pathHex2,...|hashHex:..." (pipe-separated entries)
  private def serializeFrontier(entries: Seq[HealingEntry]): String =
    if (entries.isEmpty) ""
    else
      entries.iterator
        .map { e =>
          val hashHex = Hex.toHexString(e.hash.toArray)
          val pathsHex = e.pathset.map(p => Hex.toHexString(p.toArray)).mkString(",")
          s"$hashHex:$pathsHex"
        }
        .mkString("|")

  private def deserializeFrontier(data: String): Seq[HealingEntry] =
    if (data.isEmpty) Seq.empty
    else
      data.split("\\|").toSeq.flatMap { entry =>
        val colonIdx = entry.indexOf(':')
        if (colonIdx < 0) None
        else
          Try {
            val hash = ByteString(Hex.decode(entry.substring(0, colonIdx)))
            val pathsStr = entry.substring(colonIdx + 1)
            val paths =
              if (pathsStr.isEmpty) Seq.empty[ByteString]
              else pathsStr.split(",", -1).toSeq.map(p => ByteString(Hex.decode(p)))
            HealingEntry(paths, hash)
          }.toOption
      }

  private var healingCheckTask: Option[Cancellable] = None

  /** Synchronous flush — used only for final completion flush (small buffer, safe to block). */
  private def flushRawNodesSync(): Unit =
    if (rawNodeBuffer.nonEmpty) {
      mptStorage.storeRawNodes(rawNodeBuffer.toSeq)
      mptStorage.persist()
      val count = rawNodeBuffer.size
      rawNodeBuffer.clear()
      slog.info("Flushed healed nodes to disk", kv("flushed", count), kv("totalHealed", totalNodesHealed))
    }

  /** Async flush — copies buffer, clears it, writes on the dedicated `healing-writer-dispatcher` so the blocking
    * RocksDB write doesn't compete with sync actors on `sync-dispatcher`. `private[actors]` so
    * TrieNodeHealingCoordinatorSpec can trigger flush directly via TestActorRef.
    */
  private[actors] def flushRawNodesAsync(): Unit =
    if (rawNodeBuffer.nonEmpty && !flushing) {
      flushing = true
      val nodes = rawNodeBuffer.toSeq
      val totalBytes = nodes.foldLeft(0L) { case (acc, (k, v)) => acc + k.length + v.length }
      val kb = f"${totalBytes / 1024.0}%.1f"
      slog.info("[HEAL-FLUSH]", kv("nodes", nodes.size), kv("kb", kb), kv("totalHealed", totalNodesHealed))
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
    slog.info("TrieNodeHealingCoordinator starting", kv("concurrency", concurrency))
    healingCheckTask = Some(
      context.system.scheduler
        .scheduleWithFixedDelay(stagnationCheckInterval, stagnationCheckInterval, self, HealingStagnationCheck)(
          context.dispatcher,
          ActorRef.noSender
        )
    )
    log.debug("[HEAL] stagnation check timer scheduled (interval={})", stagnationCheckInterval)
  }

  override def postStop(): Unit = {
    healingCheckTask.foreach(_.cancel())
    healingCheckTask = None
    log.debug("[HEAL] stagnation check timer cancelled in postStop")
    if (rawNodeBuffer.nonEmpty) {
      log.info(
        s"[HEAL] postStop: flushing ${rawNodeBuffer.size} buffered raw nodes synchronously " +
          s"(would be lost on async path at shutdown)"
      )
      flushRawNodesSync()
    }
    super.postStop()
  }

  override val supervisorStrategy: SupervisorStrategy =
    OneForOneStrategy(maxNrOfRetries = 3, withinTimeRange = 1.minute) { case _: Exception =>
      log.warning("Healing worker failed, restarting")
      Restart
    }

  override def receive: Receive = {
    case StartTrieNodeHealing(root) =>
      val emptyPath = ByteString(com.chipprbots.ethereum.mpt.HexPrefix.encode(Array.empty[Byte], isLeaf = false))
      val savedRoot = appStateStorage.getSnapHealingFrontierRoot()
      val savedFrontierOpt = appStateStorage.getSnapHealingFrontierData()

      if (savedRoot.contains(root) && savedFrontierOpt.exists(_.nonEmpty)) {
        // Case A: Saved frontier matches current pivot root — load directly, skip DFS entirely.
        // O(1) restart: deserialise the frontier and resume dispatching immediately.
        val entries = deserializeFrontier(savedFrontierOpt.get)
        if (entries.nonEmpty) {
          slog.info("[HEAL-RESTART] Resuming from saved frontier — DFS skipped", kv("tasks", entries.size))
          queueNodes(entries.map(e => (e.pathset, e.hash)))
          lastHealedAtMs = System.currentTimeMillis()
          tryRedispatchPendingTasks()
        } else {
          // Frontier data present but empty after deserialisation — treat as Case C
          log.warning("[HEAL-RESTART] Saved frontier empty after deserialisation — seeding root")
          queueNodes(Seq((Seq(emptyPath), root)))
          lastHealedAtMs = System.currentTimeMillis()
        }
      } else if (isNodeInStorage(root)) {
        // Case B: Root is healed but saved frontier doesn't match this root (pivot refresh, or
        // first restart with no frontier written yet).  Seed root and let inline child discovery
        // populate the queue top-down — O(missing-only) because discoverMissingChildren only
        // recurses into children that are absent from storage.
        // One extra GetTrieNodes round-trip for the root; infinitely better than the 16h DFS.
        log.info(
          s"[HEAL-RESTART] No matching saved frontier — reseeding root ${Hex.toHexString(root.take(8).toArray)} " +
            s"for inline discovery (O(missing-only), skipping 16h DFS)"
        )
        queueNodes(Seq((Seq(emptyPath), root)))
        lastHealedAtMs = System.currentTimeMillis()
      } else {
        // Case C: Fresh start — root not yet healed. Seed root and let inline discovery run.
        log.info(
          s"[HEAL] Root ${Hex.toHexString(root.take(8).toArray)} not yet in storage " +
            s"— seeding root for inline child discovery (Besu-aligned top-down)"
        )
        queueNodes(Seq((Seq(emptyPath), root)))
        lastHealedAtMs = System.currentTimeMillis()
      }

    case FrontierRebuilt(entries) =>
      log.info(
        s"[HEAL-RESTART] Frontier rebuild complete: ${entries.size} missing nodes identified " +
          s"— resuming healing from crash-recovery frontier"
      )
      if (entries.isEmpty)
        log.warning(
          "[HEAL-RESTART] Frontier is empty after BFS — trie may already be fully healed or storage is corrupt"
        )
      queueNodes(entries.map(e => (e.pathset, e.hash)))
      lastHealedAtMs = System.currentTimeMillis()
      tryRedispatchPendingTasks()

    case FrontierRebuildFailed(root) =>
      log.error("[HEAL-RESTART] DFS failed — seeding root as fallback to allow partial healing")
      val emptyPath = ByteString(com.chipprbots.ethereum.mpt.HexPrefix.encode(Array.empty[Byte], isLeaf = false))
      queueNodes(Seq((Seq(emptyPath), root)))
      lastHealedAtMs = System.currentTimeMillis()

    case QueueMissingNodes(nodes) =>
      slog.info("Queuing missing nodes for healing", kv("count", nodes.size))
      queueNodes(nodes)
      // Immediately dispatch to any known available peers
      tryRedispatchPendingTasks()

    case HealingPeerAvailable(peer) =>
      // NB-7: Skip peers already in statelessPeers — they returned empty TrieNodes for this root and
      // will do so again until the pivot refreshes. Re-adding them on every 1s scheduler tick wastes
      // active request slots and creates a rapid [SNAP/1 enabled] + [stateless] log cycle.
      if (statelessPeers.contains(peer.id.value)) {
        log.debug(
          "Ignoring HealingPeerAvailable for stateless peer {} — will re-admit on next pivot refresh",
          peer.id.value.take(8)
        )
      } else {
        // Evict stale entry for same physical node (reconnection creates new PeerId)
        knownAvailablePeers.filterInPlace(_.remoteAddress != peer.remoteAddress)
        knownAvailablePeers += peer
        dispatchIfPossible(peer)
      }

    case HealingPeerUnavailable(peerId) =>
      // Peer disconnected — remove from available set and immediately re-queue its in-flight
      // tasks so other peers can pick them up without waiting for the 30s request timeout.
      // Mirrors AccountRangeCoordinator.PeerUnavailable (go-ethereum revertRequests pattern).
      knownAvailablePeers.filterInPlace(_.id.value != peerId)
      val inFlight = activeRequests.filter { case (_, req) => req.peer.id.value == peerId }.keys.toSeq
      if (inFlight.nonEmpty) {
        slog.debug(
          "Peer disconnected — re-queuing in-flight healing requests",
          kv("peer", peerId),
          kv("inFlight", inFlight.size)
        )
        inFlight.foreach { reqId =>
          activeRequests.remove(reqId).foreach { req =>
            requestTracker.completeRequest(reqId, 0)
            req.tasks.foreach { task =>
              if (!pendingHashSet.contains(task.hash)) {
                pendingHashSet += task.hash
                pendingTasks += task
              }
            }
          }
        }
      }
      tryRedispatchPendingTasks()

    case UpdateMaxInFlightPerPeer(newLimit) =>
      slog.info("Healing per-peer budget updated", kv("oldLimit", maxInFlightPerPeer), kv("newLimit", newLimit))
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
      appStateStorage.clearSnapHealingFrontier().commit()
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
      appStateStorage.clearSnapHealingFrontier().commit() // Stale frontier for old root must not be loaded on restart
      statelessPeers.clear()
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
        slog.info("[HEAL] New root already in storage or pending — skipping pivot reseed")
      }

    case TrieNodesResponseMsg(response) =>
      handleResponse(response)

    case FlushComplete(count) =>
      // Clear only the nodes that were snapshotted and written; preserve any that
      // accumulated while the flush was in-flight (they are at indices count..end).
      rawNodeBuffer.remove(0, count)
      flushing = false
      slog.debug("[HEAL-FLUSH] complete", kv("written", count), kv("totalHealed", totalNodesHealed))
      // Write frontier checkpoint AFTER the node batch is durably committed (timing invariant:
      // write after persist(), not before — a pre-write checkpoint pointing to uncommitted
      // nodes would corrupt the frontier on a crash between the two writes).
      appStateStorage
        .putSnapHealingFrontierRoot(stateRoot)
        .and(appStateStorage.putSnapHealingFrontierData(serializeFrontier(pendingTasks.toSeq)))
        .commit()
      // Drain any nodes that accumulated while the flush was in-flight
      if (rawNodeBuffer.nonEmpty) flushRawNodesAsync()
      self ! HealingCheckCompletion

    case HealingTaskComplete(requestId, result) =>
      result match {
        case Right(count) =>
          totalNodesHealed += count
          slog.info("Healing task completed", kv("nodes", count))
          self ! HealingCheckCompletion
        case Left(error) =>
          slog.warn("Healing task failed", kv("error", error))
      }

    case HealingCheckCompletion =>
      if (isComplete && !flushing && !trieWalkInProgress) {
        flushRawNodesSync()
        appStateStorage.clearSnapHealingFrontier().commit()
        slog.info("Healing round complete — notifying controller", kv("totalHealed", totalNodesHealed))
        snapSyncController ! SNAPSyncController.StateHealingComplete
      }

    case WalkStateChanged(inProgress) =>
      trieWalkInProgress = inProgress

    case HealingStagnationCheck =>
      if (trieWalkInProgress) {
        log.debug("[HEAL-PULSE] Skipping stagnation check — trie walk in progress")
      } else {
        val recentHealed = totalNodesHealed - lastPulseHealedCount
        val healTotal = completedTaskCount.toLong + pendingTasks.size.toLong + activeRequests.size.toLong
        val healPct = if (healTotal > 0) ((completedTaskCount.toDouble / healTotal) * 100).toInt else 0
        slog.info(
          "[HEAL-PULSE]",
          kv("pct", healPct),
          kv("healed", totalNodesHealed),
          kv("recentHealed", recentHealed),
          kv("frontier", healTotal),
          kv("frontierGrowth", healTotal - lastFrontierSize),
          kv("pending", pendingTasks.size),
          kv("active", activeRequests.size),
          kv("peers", knownAvailablePeers.size),
          kv("rate", healRate.toInt),
          kv("walkRunning", trieWalkInProgress),
          kv("pivotRefreshPending", pivotRefreshRequested)
        )
        val (newM, crossed) =
          com.chipprbots.ethereum.blockchain.sync.ProgressMilestones
            .crossed(completedTaskCount.toLong, healTotal, healingMilestonePct)
        healingMilestonePct = newM
        crossed.foreach { m =>
          slog.info("[HEAL-MILESTONE]", kv("pct", m), kv("healed", completedTaskCount), kv("rate", healRate.toInt))
        }
        lastPulseHealedCount = totalNodesHealed
        lastFrontierSize = healTotal

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
          slog.warn(
            "[HEAL-STAGNATION] Zero progress in last 2min",
            kv("consecutiveStagnations", consecutiveStagnations),
            kv("maxConsecutiveStagnations", maxConsecutiveStagnations),
            kv("healed", totalNodesHealed),
            kv("pending", pendingTasks.size),
            kv("peers", knownAvailablePeers.size)
          )
          if (consecutiveStagnations >= maxConsecutiveStagnations) {
            slog.warn(
              "[HEAL-STAGNATION] Consecutive zero-progress cycles exceeded threshold, notifying controller",
              kv("consecutiveStagnations", maxConsecutiveStagnations),
              kv("healed", totalNodesHealed),
              kv("pending", pendingTasks.size)
            )
            snapSyncController ! HealingStagnated(totalNodesHealed.toLong, pendingTasks.size.toLong)
            consecutiveStagnations = 0
            // NB-11: Suppress further stagnation counting until the pivot refresh arrives (HealingPivotRefreshed
            // resets this flag). Prevents redundant HealingStagnated fires while bootstrap is in-flight.
            pivotRefreshRequested = true
            pivotRefreshRequestedAt = System.currentTimeMillis()
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
      } // end else (trieWalkInProgress guard)

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
    slog.info(
      "Queued nodes for healing",
      kv("queued", entries.size),
      kv("deduped", deduped),
      kv("totalPending", pendingTasks.size)
    )
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
      val flushBackpressure = healPending > rawBufferBackpressureThreshold * ThrottleUpFillRatio
      if (flushBackpressure) {
        healThrottle = (healThrottle * ThrottleIncrease).min(MaxThrottle)
      } else {
        healThrottle = (healThrottle / ThrottleDecrease).max(MinThrottle)
      }
      if (oldThrottle != healThrottle) {
        log.debug(
          f"Healing throttle adjusted: $oldThrottle%.1f -> $healThrottle%.1f " +
            f"(rate=$healRate%.1f nodes/s, bufferFill=$healPending/$rawBufferBackpressureThreshold, batch=${effectiveBatchSize})"
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
    val batchBuilder = Vector.newBuilder[HealingEntry]
    var taken = 0
    var skippedLocal = 0

    // B4: Dequeue in depth-first priority order (lower priority value = dispatched first).
    // A2: Skip tasks already in local storage — avoids redundant network round-trips.
    while (taken < effectiveBatch && pendingTasks.nonEmpty) {
      val task = pendingTasks.dequeue()
      pendingHashSet -= task.hash
      if (isNodeInStorage(task.hash)) {
        completedTaskCount += 1
        skippedLocal += 1
      } else {
        batchBuilder += task
        taken += 1
      }
    }

    if (skippedLocal > 0) {
      lastHealedAtMs = System.currentTimeMillis()
      slog.debug(
        "[HEAL-DISPATCH] A2: tasks already in local storage, counted as completed",
        kv("skipped", skippedLocal)
      )
    }

    val batch = batchBuilder.result()
    if (batch.isEmpty) {
      self ! HealingCheckCompletion
      return None
    }

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

    activeRequests(requestId) = ActiveRequest(batch, peer, responseBytes, stateRoot)

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

    val activeReq = activeRequests.get(requestId) match {
      case Some(req) => req
      case None =>
        slog.warn("No active healing request found", kv("requestId", requestId.toString))
        return
    }

    val tasksForRequest = activeReq.tasks
    val peer = activeReq.peer
    val requestedBytes = activeReq.requestedBytes
    val receivedNodeBytes = nodes.foldLeft(0L)(_ + _.size)

    slog.debug(
      "Received TrieNodes response",
      kv("reqId", requestId.toString),
      kv("nodes", nodes.size),
      kv("bytes", receivedNodeBytes),
      kv("peer", peer.id.value)
    )

    if (activeReq.rootAtDispatch != stateRoot) {
      log.warning(
        s"[HEAL] stale-root response discarded peer=${peer.id.value} " +
          s"(dispatched=${Hex.toHexString(activeReq.rootAtDispatch.take(4).toArray)}, " +
          s"current=${Hex.toHexString(stateRoot.take(4).toArray)}) " +
          s"— re-queuing ${tasksForRequest.size} paths"
      )
      activeRequests.remove(requestId)
      tasksForRequest.foreach { task =>
        pendingHashSet += task.hash
        pendingTasks += task
      }
      requestTracker.completeRequest(requestId, 0)
      return
    }

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
          taskByHash.get(nodeHash).foreach(task => discoverMissingChildren(nodeData, task.pathset, task.priority))
        } else {
          log.debug(
            s"Healing response node not in request set (unexpected): ${Hex.toHexString(nodeHash.take(4).toArray)} peer=${peer.id.value}"
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
      lastHealedAtMs = System.currentTimeMillis()
    } else {
      adjustResponseBytesOnFailure(peer, "empty healing response")
      recordPeerCooldown(peer, "empty healing response")
      // Mark peer stateless only on first empty response (geth-aligned: guard prevents duplicate logs
      // and redundant threshold checks when multiple in-flight responses from the same peer all return empty)
      if (!statelessPeers.contains(peer.id.value)) {
        statelessPeers += peer.id.value
        // NB-7: Evict from knownAvailablePeers immediately so the 1s HealingPeerAvailable scheduler
        // tick doesn't re-add and re-dispatch to this peer until the next pivot refresh.
        knownAvailablePeers.filterInPlace(_.id != peer.id)
        log.info(
          s"Peer ${peer.id.value} marked stateless for healing root " +
            s"${Hex.toHexString(stateRoot.take(4).toArray)} (${statelessPeers.size}/${knownAvailablePeers.size} stateless)"
        )
        // Check if all known peers are stateless — request pivot refresh.
        // Use statelessPeers.nonEmpty (not knownAvailablePeers.nonEmpty): filterInPlace above
        // removes this peer from knownAvailablePeers BEFORE the check, so a single-peer set
        // leaves knownAvailablePeers empty and the old guard silently swallowed the trigger.
        if (statelessPeers.size >= knownAvailablePeers.size && statelessPeers.nonEmpty && !pivotRefreshRequested) {
          pivotRefreshRequested = true
          pivotRefreshRequestedAt = System.currentTimeMillis()
          log.warning(
            s"All ${statelessPeers.size} peers stateless for healing root " +
              s"${Hex.toHexString(stateRoot.take(4).toArray)}. Requesting pivot refresh."
          )
          snapSyncController ! SNAPSyncController.HealingAllPeersStateless
        }
      }
    }

    log.info(
      s"Healed $healedCount/${nodes.size} trie nodes from peer ${peer.id.value} " +
        s"(total: $totalNodesHealed, pending: ${pendingTasks.size}, active: ${activeRequests.size} reqs, " +
        s"responseBytes=${responseBytesTargetFor(peer)})"
    )

    if (healedCount > 0) {
      snapSyncController ! SNAPSyncController.ProgressNodesHealed(healedCount.toLong)
      flushRawNodesAsync() // flush after every peer response — healing is sparse, per-response persistence preferred
    }

    // Dispatch more work to this peer if available (pipeline multiple requests)
    dispatchIfPossible(peer)

    self ! HealingCheckCompletion
  }

  private def handleTimeout(requestId: BigInt, tasks: Seq[HealingEntry], peer: Peer): Unit = {
    // go-ethereum reference: timeouts rotate tasks back to queue, peer returns to idle — no stateless marking.
    // (Stateless is only for empty responses.) forkAccepted filter already screens out ETH mainnet peers
    // that advertise snap/1 but have no ETC state, so the original timeout→stateless rationale no longer applies.
    slog.warn(
      "Healing request timed out — re-queuing",
      kv("reqId", requestId.toString),
      kv("tasks", tasks.size),
      kv("peer", peer.id.value)
    )

    activeRequests.remove(requestId)
    recordPeerCooldown(peer, "request timeout")
    adjustResponseBytesOnFailure(peer, "request timeout")

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
      slog.info("Re-queued timed-out healing tasks", kv("requeued", requeued), kv("pending", pendingTasks.size))
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

  /** Rebuild the healing frontier from locally-stored trie nodes after a crash/restart.
    *
    * Iterative DFS (depth-first via ArrayDeque stack). Mirrors go-ethereum trie.Sync's depth-prioritized queue:
    * drilling deep finds frontier nodes immediately, keeping the working stack O(depth × branching_factor) ≈ O(1024)
    * rather than the O(frontier_width) growth that a BFS queue exhibits on ETC mainnet. Runs on healingWriterEc.
    *
    * For each node hash:
    *   - in local storage → already healed; push children onto the DFS stack
    *   - not in storage → missing; buffer and emit via onBatch every FrontierBatchSize entries
    *
    * onBatch is called inline whenever the buffer reaches FrontierBatchSize. The caller is responsible for emitting any
    * remaining buffered entries after this method returns.
    */
  @annotation.unused
  private def rebuildFrontierDFS(
      startHash: ByteString,
      startPathset: Seq[ByteString],
      isStor: Boolean,
      frontier: mutable.Buffer[HealingEntry],
      visited: mutable.Set[ByteString],
      onBatch: Seq[HealingEntry] => Unit
  ): Unit = {
    import com.chipprbots.ethereum.mpt.{BranchNode, ExtensionNode, HashNode, LeafNode}
    import com.chipprbots.ethereum.mpt.HexPrefix
    import com.chipprbots.ethereum.domain.Account
    import scala.util.control.NonFatal

    // DFS stack — bounded to O(depth × branching_factor) ≈ 1024 entries vs BFS O(frontier_width).
    val stack = mutable.ArrayDeque[(ByteString, Seq[ByteString], Boolean)]()
    stack.append((startHash, startPathset, isStor))
    var visitedCount = 0
    var frontierCount = 0

    while (stack.nonEmpty) {
      val (hash, pathset, isStorageTrie) = stack.removeLast()
      if (!visited.contains(hash)) {
        visited += hash
        visitedCount += 1
        if (visitedCount % 10000 == 0)
          log.info(
            s"[HEAL-RESTART-DFS] Progress: $visitedCount nodes visited, $frontierCount frontier nodes found, " +
              s"stack depth ${stack.size}"
          )

        val nodeOpt =
          try Some(mptStorage.get(hash.toArray))
          catch { case _: Exception => None }
        nodeOpt match {
          case None =>
            // Not in storage — missing node, buffer for healing
            frontier += HealingEntry(pathset, hash)
            frontierCount += 1
            if (frontier.size >= FrontierBatchSize) {
              onBatch(frontier.toSeq)
              frontier.clear()
            }

          case Some(node) =>
            // Already healed — push children onto the DFS stack
            val compact = pathset.last.toArray
            val nibbles = HexPrefix.decode(compact)._1
            try
              node match {
                case branch: BranchNode =>
                  for (i <- 0 until 16)
                    branch.children(i) match {
                      case hashChild: HashNode =>
                        val childHash = ByteString(hashChild.hashNode)
                        if (!visited.contains(childHash)) {
                          val childNibbles = nibbles :+ i.toByte
                          val childCompact = ByteString(HexPrefix.encode(childNibbles, isLeaf = false))
                          val childPathset =
                            if (isStorageTrie) Seq(pathset.head, childCompact) else Seq(childCompact)
                          stack.append((childHash, childPathset, isStorageTrie))
                        }
                      case _ => // inline-encoded child — already resolved
                    }

                case ext: ExtensionNode =>
                  ext.next match {
                    case hashChild: HashNode =>
                      val childHash = ByteString(hashChild.hashNode)
                      if (!visited.contains(childHash)) {
                        val childNibbles = nibbles ++ ext.sharedKey.toArray
                        val childCompact = ByteString(HexPrefix.encode(childNibbles, isLeaf = false))
                        val childPathset =
                          if (isStorageTrie) Seq(pathset.head, childCompact) else Seq(childCompact)
                        stack.append((childHash, childPathset, isStorageTrie))
                      }
                    case _ =>
                  }

                case leaf: LeafNode if !isStorageTrie =>
                  // Account trie leaf — seed storage trie root if not yet healed
                  Account(leaf.value).foreach { account =>
                    if (
                      account.storageRoot != Account.EmptyStorageRootHash &&
                      !visited.contains(account.storageRoot)
                    ) {
                      val leafNibbles = leaf.key.toArray
                      val allNibbles = nibbles ++ leafNibbles
                      if (allNibbles.length == 64) {
                        val accountHashBytes =
                          allNibbles.grouped(2).map(g => ((g(0) << 4) | g(1)).toByte).toArray
                        val accountHash = ByteString(accountHashBytes)
                        val emptyStoragePath =
                          ByteString(HexPrefix.encode(Array.empty[Byte], isLeaf = false))
                        stack.append((account.storageRoot, Seq(accountHash, emptyStoragePath), true))
                      }
                    }
                  }

                case _ => // storage trie leaf, NullNode, HashNode inline — no children to traverse
              }
            catch {
              case NonFatal(e) =>
                log.debug(
                  s"[HEAL-RESTART-DFS] Cannot traverse stored node ${Hex.toHexString(hash.take(4).toArray)}: " +
                    s"${e.getMessage} — skipping"
                )
            }
        }
      }
    }

    log.info(
      s"[HEAL-RESTART-DFS] Complete: $visitedCount nodes traversed, $frontierCount missing nodes identified"
    )
  }

  /** Inline child discovery after each healed node — Besu/geth scheduler-driven alignment. Decodes the healed node,
    * discovers child hashes, checks storage, queues missing children. Makes healing self-feeding: root → children →
    * grandchildren top-down without trie walk.
    */
  private def discoverMissingChildren(nodeData: ByteString, pathset: Seq[ByteString], parentPriority: Long): Unit = {
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
                  newEntries += HealingEntry(childPathset, childHash, parentPriority * 16 + i)
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
                newEntries += HealingEntry(childPathset, childHash, parentPriority * 16)
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
                newEntries += HealingEntry(Seq(accountHash, emptyStoragePath), account.storageRoot, parentPriority * 16)
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
      healingWriterEcOverride: Option[ExecutionContext] = None,
      stagnationCheckInterval: FiniteDuration = 2.minutes,
      maxConsecutiveStagnations: Int = 3,
      appStateStorage: AppStateStorage = new AppStateStorage(com.chipprbots.ethereum.db.dataSource.EphemDataSource())
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
        healingWriterEcOverride,
        stagnationCheckInterval,
        maxConsecutiveStagnations,
        appStateStorage
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
