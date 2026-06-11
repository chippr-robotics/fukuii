package com.chipprbots.ethereum.blockchain.sync.snap.actors

import org.apache.pekko.actor.{Actor, ActorLogging, ActorRef, Props, SupervisorStrategy, OneForOneStrategy}
import org.apache.pekko.actor.SupervisorStrategy._
import org.apache.pekko.util.ByteString
import org.bouncycastle.util.encoders.Hex

import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

import scala.concurrent.{Await, Future}
import scala.concurrent.duration.Duration

import com.chipprbots.ethereum.blockchain.sync.snap._
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController
import com.chipprbots.ethereum.db.storage.{BfsQueueStorage, HealingFrontierStorage, InMemoryBfsQueueStorage, MptStorage}
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
    visitedCap: Int = TrieNodeHealingCoordinator.DefaultVisitedCap,
    healingFrontierStorage: Option[HealingFrontierStorage] = None,
    healingWriterEcOverride: Option[ExecutionContext] = None,
    traversalParallelism: Int = TrieNodeHealingCoordinator.DefaultDfsParallelism,
    bfsQueueStorageOpt: Option[BfsQueueStorage] = None
) extends Actor
    with ActorLogging {

  import Messages._

  // Task management — each task has a pathset (for GetTrieNodes) and a hash (for verification).
  // ArrayDeque (circular buffer) gives O(1) amortized head/tail operations (#1167). The previous
  // immutable `Seq` did O(n) on every `:+` and head-drop — quadratic at healing scale.
  private case class HealingEntry(pathset: Seq[ByteString], hash: ByteString)
  private val pendingTasks: mutable.ArrayDeque[HealingEntry] = mutable.ArrayDeque.empty
  private var completedTaskCount: Int = 0
  private var healingMilestonePct: Int = -1

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
  // FIX-STAGNATION-LIMIT: Track consecutive 2-min cycles with zero healed nodes (even when active).
  // After MaxConsecutiveStagnations, notify controller to restart with fresh pivot.
  private var consecutiveStagnations: Int = 0
  private val MaxConsecutiveStagnations: Int = 3
  private var trieWalkInProgress: Boolean = false
  // Verification DFS state: gates StateHealingComplete and catches storage sub-trie gaps (Fix BUG-1/BUG-2).
  // verificationPassComplete = true only after a DFS traversal finds zero missing nodes.
  // verificationDFSRunning = true while the DFS Future is executing on healingWriterEc.
  private var verificationPassComplete: Boolean = false
  private var verificationDFSRunning: Boolean = false
  // Dead-loop watchdog (Fix BUG-2): consecutive HEAL-PULSE cycles with no walk/pending/active/healed.
  private var consecutiveDeadPulses: Int = 0
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

  // Crash-recovery DFS: emit frontier in batches so healing starts before traversal completes.
  // go-ethereum trie.Sync.Missing() alignment — bounded working set rather than full upfront BFS.
  private val FrontierBatchSize = 1000

  // Cap on the frontier-rebuild DFS `visited` set. The DFS walks the full state trie (accounts +
  // every storage trie — tens of millions of nodes on ETC mainnet); an unbounded visited set grew
  // to ~2.9 GB and OOM-looped the node. A fixed-capacity LRU (insertion-order eviction) bounds it
  // to ~cap × 80 B ≈ 320 MB. Completeness is preserved: any missing node re-discovered after an
  // eviction is de-duplicated by `pendingHashSet`, and an evicted present node is only re-walked
  // if reached again via a shared reference. See docs/design/healing-frontier-scale.md.
  // Operator-tunable via `sync.snap-sync.healing-visited-cap`; defaults to DefaultVisitedCap.
  private val HealingVisitedCap: Int = visitedCap
  private val HealingTraversalParallelism: Int = traversalParallelism
  private val bfsQueue: BfsQueueStorage = bfsQueueStorageOpt.getOrElse(new InMemoryBfsQueueStorage())
// --- Layer 2: persisted frontier (sync.snap-sync.healing-frontier-persistence) ---
  // When `healingFrontierStorage` is defined, the outstanding frontier is mirrored to a dedicated RocksDB
  // CF so a restart resumes (O(frontier)) instead of re-walking the full state. Invariant: the persisted
  // set equals `pendingTasks ∪ in-flight` — write on every new enqueue (queueNodes / inline child discovery
  // / pivot reseed), delete on heal-flush (NOT on dispatch), clear on force-complete / pivot-refresh.
  // See docs/design/healing-frontier-scale.md. No-ops when persistence is disabled (the common default).

  /** Persist newly-queued frontier entries (hash -> pathset). Idempotent: re-persisting an existing entry overwrites it
    * identically, so re-queues and resume-loads are harmless.
    */
  private def persistFrontier(entries: Seq[HealingEntry]): Unit =
    healingFrontierStorage.foreach { store =>
      if (entries.nonEmpty) store.update(Nil, entries.map(e => e.hash -> e.pathset)).commit()
    }

  /** Delete healed nodes from the persisted frontier. Safe to call from the healing-writer thread (touches only the
    * immutable storage handle + thread-safe RocksDB). Removing an absent key is a no-op.
    */
  private def unpersistFrontier(hashes: Seq[ByteString]): Unit =
    healingFrontierStorage.foreach { store =>
      if (hashes.nonEmpty) store.update(hashes, Nil).commit()
    }

  /** Clear the entire persisted frontier by deleting every outstanding hash. Because the persisted set equals
    * `pendingTasks ∪ in-flight`, deleting those hashes empties the CF without a namespace-wipe primitive. MUST be
    * called BEFORE the in-memory `pendingTasks`/`activeRequests` are cleared.
    */
  private def clearPersistedFrontier(): Unit =
    healingFrontierStorage.foreach { store =>
      val outstanding =
        pendingTasks.iterator.map(_.hash).toSeq ++ activeRequests.values.iterator.flatMap(_.tasks.iterator.map(_.hash))
      if (outstanding.nonEmpty) store.update(outstanding, Nil).commit()
      // The snapshot is no longer valid/complete — drop the marker so a restart re-walks rather than resuming stale.
      store.clearComplete()
    }

  /** Publish the live healing backlog/in-flight gauges for the Grafana healing-analytics section. */
  private def emitHealingFrontierGauges(): Unit = {
    SNAPSyncMetrics.setHealingFrontierPending(pendingTasks.size.toLong)
    SNAPSyncMetrics.setHealingActiveRequests(activeRequests.size.toLong)
  }

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

  // Internal message for async frontier rebuild completion (crash-recovery BFS or verification DFS)
  private case class FrontierRebuilt(entries: Seq[HealingEntry])
  // Sent by startVerificationDFS when the DFS Future completes — gates verificationPassComplete.
  private case object VerificationDFSComplete

  // Layer 2: the full-state rebuild DFS finished — the persisted frontier is now a COMPLETE snapshot.
  // Sent after the final FrontierRebuilt so the completeness marker is set only once every node is persisted.
  private case object FrontierRebuildComplete

  // A frontier walk Future died with an exception. Resets the walk flags WITHOUT setting any
  // completion marker, so the watchdog / HealingCheckCompletion gates can start a fresh walk.
  // Without this, an exception skips onComplete() and verificationDFSRunning stays true forever,
  // permanently blocking every future walk (including the watchdog) until restart.
  private case object FrontierWalkFailed

  /** Synchronous flush — used only for final completion flush (small buffer, safe to block). */
  private def flushRawNodesSync(): Unit =
    if (rawNodeBuffer.nonEmpty) {
      val flushed = rawNodeBuffer.toSeq
      mptStorage.storeRawNodes(flushed)
      mptStorage.persist()
      unpersistFrontier(flushed.map(_._1)) // Layer 2: healed nodes leave the persisted frontier
      val count = flushed.size
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
          unpersistFrontier(nodes.map(_._1)) // Layer 2: healed nodes leave the persisted frontier (post-durable-write)
          nodes.size
        }
      }(ec).foreach(n => selfRef ! FlushComplete(n))(ec)
    }

  override def preStart(): Unit = {
    log.info(s"TrieNodeHealingCoordinator starting (concurrency=$concurrency)")
    context.system.scheduler.scheduleWithFixedDelay(2.minutes, 2.minutes, self, HealingStagnationCheck)(
      context.dispatcher,
      ActorRef.noSender
    )
  }

  override val supervisorStrategy: SupervisorStrategy =
    OneForOneStrategy(maxNrOfRetries = 3, withinTimeRange = 1.minute) { case _: Exception =>
      log.warning("Healing worker failed, restarting")
      Restart
    }

  override def receive: Receive = {
    case StartTrieNodeHealing(root) =>
      val emptyPath = ByteString(com.chipprbots.ethereum.mpt.HexPrefix.encode(Array.empty[Byte], isLeaf = false))
      if (isNodeInStorage(root)) {
        // ARCH-HEAL-RESTART: Root already healed — crash/restart mid-healing detected.
        // Rebuild the frontier by traversing locally-stored trie nodes instead of re-requesting
        // known nodes from the network (go-ethereum trie.Sync.Missing() analogue).
        // Recovery cost: O(healed_nodes × local_read) vs O(healed_nodes × network_rtt).
        log.info(
          s"[HEAL-RESTART] Root ${Hex.toHexString(root.take(8).toArray)} already in local storage " +
            s"— rebuilding frontier via local DFS in batches of $FrontierBatchSize " +
            s"(crash recovery, go-ethereum trie.Sync.Missing() depth-first pattern)"
        )
        val selfRef = self
        val ec = healingWriterEc
        val frontierStore = healingFrontierStorage
        import scala.concurrent.Future
        import scala.util.control.NonFatal
        Future {
          // Layer 2: if a persisted frontier exists, resume from it (O(frontier)) and skip the full-state walk.
          // Empty / absent / unreadable ⇒ fail-safe fallback to the provably-complete DFS (logged loudly).
          val resumed: Option[Seq[HealingEntry]] = frontierStore.flatMap { store =>
            try {
              val loaded = store.loadAll().map { case (h, ps) => HealingEntry(pathset = ps, hash = h) }
              if (loaded.nonEmpty && store.isComplete) {
                // COMPLETE snapshot (the prior rebuild DFS finished) — safe to skip the full-state walk.
                log.info(
                  s"[HEAL-RESTART] Resumed ${loaded.size} frontier entries from a complete persisted snapshot — skipping full-state DFS"
                )
                Some(loaded)
              } else if (loaded.nonEmpty) {
                // PARTIAL frontier: the prior rebuild DFS was interrupted before completion, so the un-walked
                // region's missing nodes are not yet recorded. Skipping the DFS would silently leave gaps —
                // re-run the full walk (it re-persists idempotently and sets the marker on completion).
                log.warning(
                  s"[HEAL-RESTART] Persisted frontier has ${loaded.size} entries but no completeness marker " +
                    s"(prior rebuild interrupted) — re-running full-state DFS to avoid skipping un-walked nodes"
                )
                None
              } else {
                log.info("[HEAL-RESTART] Persisted frontier empty — falling back to full-state DFS")
                None
              }
            } catch {
              case NonFatal(e) =>
                log.error(e, "[HEAL-RESTART] Failed to load persisted frontier — falling back to full-state DFS")
                None
            }
          }
          resumed match {
            case Some(entries) =>
              selfRef ! FrontierRebuilt(entries)
            case None =>
              // Mark the persisted frontier authoritative when the full walk is done (all workers complete).
              startFrontierBFS(root, emptyPath, isStor = false, () => selfRef ! FrontierRebuildComplete)
          }
        }(ec)
      } else {
        // ARCH-ROOT-SEED: Fresh start — seed root and let inline discovery populate the queue.
        log.info(
          s"[HEAL] Root ${Hex.toHexString(root.take(8).toArray)} not yet in storage " +
            s"— seeding root for inline child discovery (Besu-aligned top-down)"
        )
        queueNodes(Seq((Seq(emptyPath), root)))
        lastHealedAtMs = System.currentTimeMillis()
      }

    case FrontierRebuilt(entries) =>
      if (entries.isEmpty)
        log.warning(
          "[HEAL-FRONTIER] Empty frontier batch received — trie may already be fully healed or storage is corrupt"
        )
      else
        log.info(s"[HEAL-FRONTIER] ${entries.size} missing nodes identified — queuing for healing")
      queueNodes(entries.map(e => (e.pathset, e.hash)))
      lastHealedAtMs = System.currentTimeMillis()
      tryRedispatchPendingTasks()

    case FrontierRebuildComplete =>
      // The full-state rebuild DFS walked the entire trie; every still-missing node is now persisted.
      // Mark the snapshot complete so a future restart may resume it instead of re-walking (Layer 2).
      healingFrontierStorage.foreach { store =>
        store.markComplete()
        log.info("[HEAL-RESTART] Full-state rebuild complete — persisted frontier marked as a complete snapshot")
      }

    case FrontierWalkFailed =>
      verificationDFSRunning = false
      trieWalkInProgress = false
      log.warning(
        "[HEAL-BFS] Frontier walk failed — flags reset; verification will be retried by " +
          "HealingCheckCompletion or the dead-pulse watchdog (no completion marker was set)"
      )

    case QueueMissingNodes(nodes) =>
      log.info(s"Queuing ${nodes.size} missing nodes for healing")
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
        log.debug(s"Peer $peerId disconnected — re-queuing ${inFlight.size} in-flight healing request(s)")
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
      log.info(s"Healing per-peer budget: $maxInFlightPerPeer -> $newLimit")
      maxInFlightPerPeer = newLimit
      if (newLimit > 0) tryRedispatchPendingTasks()

    case HealingForceComplete =>
      log.warning(
        s"[HEAL-FORCE-COMPLETE] Pivot advanced beyond SNAP serve window — " +
          s"clearing ${pendingTasks.size} pending tasks + ${activeRequests.size} in-flight. " +
          s"Signaling completion with $totalNodesHealed healed nodes."
      )
      clearPersistedFrontier() // Layer 2: healing abandoned — drop the persisted frontier so a restart won't resume it
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
      clearPersistedFrontier() // Layer 2: old-root frontier is stale after refresh — reseed (below) repopulates it
      pendingTasks.clear() // Will be re-populated by root reseed + inline discovery / trie walk from controller
      pendingHashSet.clear()
      statelessPeers.clear()
      peerCooldownUntilMs.clear()
      peerResponseBytesTarget.clear()
      // Cancel active requests (they're for the old root)
      activeRequests.keys.foreach(requestTracker.completeRequest(_, 0))
      activeRequests.clear()
      pivotRefreshRequested = false
      consecutiveIdleChecks = 0
      consecutiveStagnations = 0
      consecutiveDeadPulses = 0
      verificationPassComplete = false // new pivot root → must re-verify trie completeness
      lastPulseHealedCount = totalNodesHealed
      lastHealedAtMs = System.currentTimeMillis() // BUG-4: give fresh pivot a full stagnation window
      // ARCH-PIVOT-RESEED: Re-seed with new root for top-down discovery of trie delta.
      // Content-addressed inline tasks (~99% valid) were cleared — new root seeds a fresh
      // top-down traversal of the updated trie.
      val pivotReseedPath = ByteString(com.chipprbots.ethereum.mpt.HexPrefix.encode(Array.empty[Byte], isLeaf = false))
      if (!pendingHashSet.contains(newStateRoot) && !isNodeInStorage(newStateRoot)) {
        val reseedEntry = HealingEntry(Seq(pivotReseedPath), newStateRoot)
        pendingTasks += reseedEntry
        pendingHashSet += newStateRoot
        persistFrontier(Seq(reseedEntry)) // Layer 2: the new pivot root is a new frontier entry
        log.info(
          s"[HEAL] Re-seeded with new root ${Hex.toHexString(newStateRoot.take(4).toArray)} " +
            s"for inline discovery of pivot delta"
        )
      } else {
        // FIX-BUG2-PIVOT: Root already in local storage — run a verification DFS to discover
        // any missing children instead of dead-looping with zero pending tasks.
        // Without this, walkRunning stays false and pending stays 0 → 316-pulse dead loop (RUN10).
        // discoverMissingChildren skips locally-held storage roots without recursing into their
        // children, so the new pivot root may be local yet have gaps in storage sub-tries.
        if (trieWalkInProgress || verificationDFSRunning) {
          // A walk is already running on the SHARED bfsQueue — rebuildFrontierBFS clears the queue
          // on entry, so starting a second walk here would corrupt the running one. The pivot's
          // verificationPassComplete=false (set above) guarantees HealingCheckCompletion starts a
          // fresh verification once the current walk's flags clear.
          log.info(
            s"[HEAL] New root ${Hex.toHexString(newStateRoot.take(4).toArray)} already in storage, " +
              s"but a frontier walk is running — verification deferred until it completes"
          )
        } else {
          log.info(
            s"[HEAL] New root ${Hex.toHexString(newStateRoot.take(4).toArray)} already in storage " +
              s"— starting verification DFS to find missing children"
          )
          startVerificationDFS(newStateRoot, pivotReseedPath)
        }
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
      if (isComplete && !flushing && !trieWalkInProgress && !verificationDFSRunning) {
        // FIX-BUG1-VERIFY: gate: skip verification when no inline healing was done.
        // If totalNodesHealed == 0 the coordinator was never given nodes to heal (idle case) OR
        // all nodes were already in local storage — either way the trie is complete from our
        // perspective. The BUG 2 pivot-reseed path (root held locally) is handled separately by
        // HealingPivotRefreshed calling startVerificationDFS directly, not through this gate.
        if (verificationPassComplete || totalNodesHealed == 0) {
          flushRawNodesSync()
          log.info(s"Healing round complete: $totalNodesHealed total nodes healed. Notifying controller.")
          snapSyncController ! SNAPSyncController.StateHealingComplete
        } else {
          // Inline tasks done with actual healing work — run a full DFS to catch storage sub-trie
          // gaps that discoverMissingChildren silently skips when the storage root is in storage.
          // Analogous to go-ethereum's trie.Sync.Missing() depth-first traversal.
          val emptyPath = ByteString(com.chipprbots.ethereum.mpt.HexPrefix.encode(Array.empty[Byte], isLeaf = false))
          log.info(
            s"[HEAL-VERIFY] All inline tasks done ($totalNodesHealed healed). " +
              s"Starting verification DFS on locally-held trie to catch storage sub-trie gaps..."
          )
          startVerificationDFS(stateRoot, emptyPath)
        }
      }

    case VerificationDFSComplete =>
      verificationDFSRunning = false
      if (isComplete) {
        // DFS traversed all locally-held nodes and found zero missing descendants — trie is complete.
        verificationPassComplete = true
        log.info(
          s"[HEAL-VERIFY] Verification DFS complete — no missing nodes found. " +
            s"Trie is fully healed ($totalNodesHealed nodes). Declaring completion."
        )
        self ! HealingCheckCompletion
      } else {
        // DFS found missing nodes queued via FrontierRebuilt — healing needs to continue.
        log.info(
          s"[HEAL-VERIFY] Verification DFS found additional missing nodes " +
            s"(pending=${pendingTasks.size} active=${activeRequests.size}) — resuming healing."
        )
        tryRedispatchPendingTasks()
      }

    case WalkStateChanged(inProgress) =>
      trieWalkInProgress = inProgress

    case HealingStagnationCheck =>
      emitHealingFrontierGauges() // refresh backlog/in-flight gauges even when idle
      val recentHealed = totalNodesHealed - lastPulseHealedCount
      val healTotal = completedTaskCount.toLong + pendingTasks.size.toLong + activeRequests.size.toLong
      val healPct = if (healTotal > 0) ((completedTaskCount.toDouble / healTotal) * 100).toInt else 0
      log.info(
        s"[HEAL-PULSE] $healPct% (est) | healed=$totalNodesHealed (+$recentHealed last 2min) | " +
          s"pending=${pendingTasks.size} active=${activeRequests.size} peers=${knownAvailablePeers.size} | " +
          s"rate=${healRate.toInt} nodes/s walkRunning=$trieWalkInProgress pivotRefreshPending=$pivotRefreshRequested"
      )
      val (newM, crossed) =
        com.chipprbots.ethereum.blockchain.sync.ProgressMilestones
          .crossed(completedTaskCount.toLong, healTotal, healingMilestonePct)
      healingMilestonePct = newM
      crossed.foreach { m =>
        log.info(
          s"[HEAL-MILESTONE] $m% (est) — ${completedTaskCount} healed | ${healRate.toInt} nodes/s"
        )
      }
      lastPulseHealedCount = totalNodesHealed

      // FIX-BUG2-WATCHDOG: Dead-loop safety net — fires when the coordinator has no walk, no
      // verification DFS, no pending tasks, no active requests, and zero healing progress.
      // Primary fix is startVerificationDFS in HealingCheckCompletion and HealingPivotRefreshed;
      // this watchdog catches any residual edge case (e.g. stale state after pivot race).
      if (
        !trieWalkInProgress && !verificationDFSRunning &&
        pendingTasks.isEmpty && activeRequests.isEmpty &&
        recentHealed == 0 && !pivotRefreshRequested && !verificationPassComplete
      ) {
        consecutiveDeadPulses += 1
        log.warning(
          s"[HEAL-WATCHDOG] Dead pulse $consecutiveDeadPulses/3: " +
            s"walkRunning=false verifyRunning=false pending=0 active=0 healed=0 in last 2min"
        )
        if (consecutiveDeadPulses >= 3) {
          log.warning("[HEAL-WATCHDOG] 3 consecutive dead pulses — force-starting verification DFS")
          consecutiveDeadPulses = 0
          val emptyPath = ByteString(com.chipprbots.ethereum.mpt.HexPrefix.encode(Array.empty[Byte], isLeaf = false))
          startVerificationDFS(stateRoot, emptyPath)
        }
      } else if (
        recentHealed > 0 || pendingTasks.nonEmpty || activeRequests.nonEmpty ||
        trieWalkInProgress || verificationDFSRunning
      ) {
        consecutiveDeadPulses = 0
      }

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
    persistFrontier(entries) // Layer 2: mirror new frontier entries to the persisted CF
    val dedupStr = if (deduped > 0) s" ($deduped duplicates filtered)" else ""
    log.info(s"Queued ${entries.size} nodes for healing$dedupStr. Total pending: ${pendingTasks.size}")
    emitHealingFrontierGauges()
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
    emitHealingFrontierGauges()
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
      // Flush immediately after every response rather than waiting for the 1000-node threshold.
      // Sparse healing runs (small gap counts) would otherwise stall writes in the buffer
      // indefinitely if they never hit the count gate.
      flushRawNodesAsync()
    }

    // Dispatch more work to this peer if available (pipeline multiple requests)
    dispatchIfPossible(peer)

    self ! HealingCheckCompletion
  }

  private def handleTimeout(requestId: BigInt, tasks: Seq[HealingEntry], peer: Peer): Unit = {
    // go-ethereum reference: timeouts rotate tasks back to queue, peer returns to idle — no stateless marking.
    // (Stateless is only for empty responses.) forkAccepted filter already screens out ETH mainnet peers
    // that advertise snap/1 but have no ETC state, so the original timeout→stateless rationale no longer applies.
    log.warning(s"Healing request timed out: reqId=$requestId, tasks=${tasks.size}, peer=${peer.id.value} — re-queuing")

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

  /** Rebuild the healing frontier from locally-stored trie nodes using level-order BFS.
    *
    * Issues one `multiGetNodes` per chunk (BfsChunkSize = 50K) instead of one get per node. Level queue is persisted to
    * the `BfsQueueNamespace` CF so memory is O(chunk_size) ≈ 4 MB, eliminating the OOM that the previous ArrayBuffer
    * caused at L7 (~73M entries × 175 B/tuple).
    *
    * For large levels (> BfsChunkSize) the level is split into N sub-ranges processed in parallel on `healingWriterEc`
    * (N = min(HealingTraversalParallelism, available processors - 2)). Each sub-range enqueues its children directly
    * into the shared CF queue (AtomicLong counter assigns unique keys). Runs entirely on healingWriterEc.
    */
  private def rebuildFrontierBFS(
      startHash: ByteString,
      startPathset: Seq[ByteString],
      isStor: Boolean,
      selfRef: ActorRef,
      queue: BfsQueueStorage,
      effectiveParallelism: Int
  ): Unit = {
    import com.chipprbots.ethereum.mpt.{BranchNode, ExtensionNode, HashNode, LeafNode}
    import com.chipprbots.ethereum.mpt.HexPrefix
    import com.chipprbots.ethereum.domain.Account
    import scala.util.control.NonFatal

    // LRU-bounded visited set (companion boundedVisitedSet): at the cap the ELDEST entry is
    // evicted instead of refusing new entries. Refusing (the previous ConcurrentHashMap gate)
    // silently TRUNCATED the traversal on tries larger than the cap — children past the cap were
    // never enqueued, the queue drained early, and the walk reported "Complete" (and set the
    // Layer-2 completeness marker) having covered only `cap` of the trie. Eviction trades that
    // correctness hole for bounded re-walks of shared subtries (de-duplicated downstream by
    // pendingHashSet). Access is synchronized: worker threads only touch it via markIfNew, and
    // per-check lock cost is negligible against the 50K-node multiGet I/O per chunk.
    val visitedLru = TrieNodeHealingCoordinator.boundedVisitedSet(HealingVisitedCap)
    def markIfNew(h: ByteString): Boolean = visitedLru.synchronized {
      if (visitedLru.contains(h)) false
      else {
        visitedLru += h
        true
      }
    }
    markIfNew(startHash)

    val visitedCount = new java.util.concurrent.atomic.AtomicLong(0L)
    val frontierCount = new java.util.concurrent.atomic.AtomicLong(0L)

    // Process a sub-range [subFrom, subTo) of the current level; returns frontier entries.
    def processSubRange(subFrom: Long, subTo: Long, levelIndex: Int): Seq[HealingEntry] = {
      val subFrontier = mutable.Buffer.empty[HealingEntry]

      queue.iterateRange(subFrom, subTo).foreach { chunk =>
        val results = mptStorage.multiGetNodes(chunk.map(_.hash))
        val nextBuf = mutable.ArrayBuffer[(Array[Byte], Seq[Array[Byte]], Boolean)]()

        chunk.zip(results).foreach { case (entry, nodeOpt) =>
          val v = visitedCount.incrementAndGet()
          if (v % 100_000 == 0) {
            log.info(
              s"[HEAL-BFS] Level $levelIndex: $v nodes visited, ${frontierCount.get()} frontier found, " +
                s"${queue.counter - subTo} L${levelIndex + 1} queued"
            )
            SNAPSyncMetrics.setHealingRebuildVisited(v)
          }

          val pathset = entry.pathset.map(ByteString(_))
          val nibbles = HexPrefix.decode(pathset.last.toArray)._1

          nodeOpt match {
            case None =>
              subFrontier += HealingEntry(pathset, ByteString(entry.hash))
              frontierCount.incrementAndGet()

            case Some(node) =>
              try
                node match {
                  case branch: BranchNode =>
                    for (i <- 0 until 16) branch.children(i) match {
                      case hashChild: HashNode =>
                        val childHash = ByteString(hashChild.hashNode)
                        if (markIfNew(childHash)) {
                          val childNibbles = nibbles :+ i.toByte
                          val childCompact = ByteString(HexPrefix.encode(childNibbles, isLeaf = false))
                          val childPathset =
                            if (entry.isStorage) Seq(pathset.head.toArray, childCompact.toArray)
                            else Seq(childCompact.toArray)
                          nextBuf += ((hashChild.hashNode, childPathset, entry.isStorage))
                        }
                      case _ =>
                    }

                  case ext: ExtensionNode =>
                    ext.next match {
                      case hashChild: HashNode =>
                        val childHash = ByteString(hashChild.hashNode)
                        if (markIfNew(childHash)) {
                          val childNibbles = nibbles ++ ext.sharedKey.toArray
                          val childCompact = ByteString(HexPrefix.encode(childNibbles, isLeaf = false))
                          val childPathset =
                            if (entry.isStorage) Seq(pathset.head.toArray, childCompact.toArray)
                            else Seq(childCompact.toArray)
                          nextBuf += ((hashChild.hashNode, childPathset, entry.isStorage))
                        }
                      case _ =>
                    }

                  case leaf: LeafNode if !entry.isStorage =>
                    Account(leaf.value).foreach { account =>
                      if (
                        account.storageRoot != Account.EmptyStorageRootHash &&
                        markIfNew(account.storageRoot)
                      ) {
                        val allNibbles = nibbles ++ leaf.key.toArray
                        if (allNibbles.length == 64) {
                          val accountHashBytes =
                            allNibbles.grouped(2).map(g => ((g(0) << 4) | g(1)).toByte).toArray
                          val accountHash = ByteString(accountHashBytes)
                          val emptyStoragePath = ByteString(HexPrefix.encode(Array.empty[Byte], isLeaf = false))
                          nextBuf += (
                            (
                              account.storageRoot.toArray,
                              Seq(accountHash.toArray, emptyStoragePath.toArray),
                              true
                            )
                          )
                        }
                      }
                    }

                  case _ => // storage trie leaf, NullNode, inline HashNode
                }
              catch {
                case NonFatal(e) =>
                  log.debug(
                    s"[HEAL-BFS] Cannot traverse ${Hex.toHexString(entry.hash.take(4))}: ${e.getMessage} — skipping"
                  )
              }
          }
        }
        if (nextBuf.nonEmpty) queue.enqueueBatch(nextBuf.toSeq)
      }
      subFrontier.toSeq
    }

    queue.clear()
    queue.enqueueBatch(Seq((startHash.toArray, startPathset.map(_.toArray), isStor)))
    var levelStart = 0L
    var levelEnd = queue.counter // = 1L after root enqueued
    var levelIndex = 0

    while (levelStart < levelEnd) {
      val levelSize = levelEnd - levelStart

      val allFrontier: Seq[HealingEntry] =
        if (effectiveParallelism <= 1 || levelSize <= TrieNodeHealingCoordinator.BfsChunkSize.toLong) {
          processSubRange(levelStart, levelEnd, levelIndex)
        } else {
          val rangeSize = math.ceil(levelSize.toDouble / effectiveParallelism).toLong
          val subRanges = (0 until effectiveParallelism)
            .map { i =>
              val from = levelStart + i * rangeSize
              val to = math.min(levelEnd, from + rangeSize)
              (from, to)
            }
            .filter { case (from, to) => from < to }

          val futures = subRanges.map { case (from, to) =>
            Future(processSubRange(from, to, levelIndex))(healingWriterEc)
          }
          futures.flatMap(f => Await.result(f, Duration.Inf))
        }

      allFrontier.grouped(FrontierBatchSize).foreach(batch => selfRef ! FrontierRebuilt(batch))

      val fc = frontierCount.get()
      val queued = queue.counter - levelEnd
      log.info(
        s"[HEAL-BFS] Level $levelIndex complete: $levelSize processed, $fc frontier total, $queued queued for L${levelIndex + 1}"
      )
      SNAPSyncMetrics.setHealingRebuildVisited(visitedCount.get())

      levelStart = levelEnd
      levelEnd = queue.counter
      levelIndex += 1
    }

    queue.clear()

    log.info(
      s"[HEAL-BFS] Complete: ${visitedCount.get()} nodes across $levelIndex levels, ${frontierCount.get()} missing nodes identified"
    )
  }

  /** Launch a frontier rebuild or verification BFS on the healing writer executor.
    *
    * Replaces startParallelFrontierDFS. A single Future on healingWriterEc is sufficient — BFS naturally maximises I/O
    * batching per level without needing keyspace splitting.
    */
  private def startFrontierBFS(
      root: ByteString,
      rootPath: ByteString,
      isStor: Boolean,
      onComplete: () => Unit
  ): Unit = {
    val selfRef = self
    val effectiveParallelism = math.min(
      HealingTraversalParallelism,
      math.max(1, Runtime.getRuntime.availableProcessors() - 2)
    )
    Future {
      try {
        rebuildFrontierBFS(root, Seq(rootPath), isStor, selfRef, bfsQueue, effectiveParallelism)
        onComplete()
      } catch {
        case scala.util.control.NonFatal(e) =>
          // Never call onComplete() on failure — for the crash-recovery rebuild that would set the
          // Layer-2 completeness marker on a walk that did NOT cover the full state. Reset flags
          // through the actor instead so a fresh walk can be started.
          log.error(e, "[HEAL-BFS] Frontier walk FAILED before completion — sending FrontierWalkFailed")
          selfRef ! FrontierWalkFailed
      }
    }(healingWriterEc)
  }

  /** Start a verification BFS (see [[startFrontierBFS]]).
    *
    * Traverses all locally-held trie nodes starting at `root` / `rootPath` and queues any missing descendants as
    * `FrontierRebuilt` messages. Sends `VerificationDFSComplete` when done.
    *
    * Needed because `discoverMissingChildren` skips storage roots that are already in local storage without recursing
    * into their children — a storage sub-trie with a locally-held root can still have gaps deeper in the tree (RUN10:
    * account 888157b2 had 11 missing storage nodes after StateHeal declared completion). BFS catches every missing
    * descendant in O(levels × chunk_reads) instead of O(total_nodes) point-reads.
    */
  private def startVerificationDFS(root: ByteString, rootPath: ByteString): Unit = {
    verificationDFSRunning = true
    val selfRef = self
    startFrontierBFS(root, rootPath, isStor = false, () => selfRef ! VerificationDFSComplete)
  }

  /** Inline child discovery after each healed node — Besu/geth scheduler-driven alignment. Decodes the healed node,
    * discovers child hashes, checks storage, queues missing children. Makes healing self-feeding: root → children →
    * grandchildren top-down without trie walk.
    *
    * B3 FIX: branch children are checked with a single multiGetNodes call instead of up to 16 serial isNodeInStorage
    * calls on the actor thread. Extension child uses the same pattern for consistency.
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
          // Collect all non-pending HashNode children, then check storage in one multiGetNodes call.
          val toCheck = (0 until 16)
            .collect {
              case i if branch.children(i).isInstanceOf[HashNode] =>
                (i, ByteString(branch.children(i).asInstanceOf[HashNode].hashNode))
            }
            .filterNot { case (_, h) => pendingHashSet.contains(h) }
          if (toCheck.nonEmpty) {
            val storageResults = mptStorage.multiGetNodes(toCheck.map(_._2.toArray))
            toCheck.zip(storageResults).foreach { case ((i, childHash), nodeOpt) =>
              if (nodeOpt.isEmpty) {
                val childNibbles = parentNibbles :+ i.toByte
                val childCompact = ByteString(HexPrefix.encode(childNibbles, isLeaf = false))
                val childPathset = if (isStorageTrie) Seq(pathset.head, childCompact) else Seq(childCompact)
                newEntries += HealingEntry(childPathset, childHash)
              }
            }
          }

        case ext: ExtensionNode =>
          ext.next match {
            case hash: HashNode =>
              val childHash = ByteString(hash.hashNode)
              if (!pendingHashSet.contains(childHash)) {
                val storageResults = mptStorage.multiGetNodes(Seq(childHash.toArray))
                if (storageResults.headOption.flatten.isEmpty) {
                  val childNibbles = parentNibbles ++ ext.sharedKey.toArray
                  val childCompact = ByteString(HexPrefix.encode(childNibbles, isLeaf = false))
                  val childPathset = if (isStorageTrie) Seq(pathset.head, childCompact) else Seq(childCompact)
                  newEntries += HealingEntry(childPathset, childHash)
                }
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
        persistFrontier(newEntries.toSeq) // Layer 2: inline-discovered children are new frontier entries
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

  /** Default cap on the frontier-rebuild DFS `visited` LRU: 4M entries ≈ 320 MB. Used when
    * `sync.snap-sync.healing-visited-cap` is unset. See docs/design/healing-frontier-scale.md.
    */
  val DefaultVisitedCap: Int = 4_000_000

  /** Operator-configurable ceiling for BFS level parallelism. Effective parallelism is `min(DefaultDfsParallelism,
    * max(1, availableProcessors - 2))` so large levels are split across sub-ranges on `healingWriterEc`. See
    * `healing-traversal-parallelism` in `sync.conf`.
    */
  val DefaultDfsParallelism: Int = 4

  /** Maximum number of node hashes per `multiGetNodes` call inside `rebuildFrontierBFS`. Keeps each Java list under
    * ~1.6 MB (50K × 32B) and prevents a single enormous call when a trie level spans hundreds of thousands of nodes.
    */
  val BfsChunkSize: Int = 50_000

  /** Heap-bounded `visited` set for the frontier-rebuild DFS: a `LinkedHashMap`-backed LRU that evicts the
    * earliest-inserted (already-completed) subtries once it exceeds `cap` (insertion-order eviction). Exposed on the
    * companion so the eviction contract (size never exceeds `cap`; eldest dropped first) is unit-testable without
    * instantiating the actor.
    */
  def boundedVisitedSet(cap: Int): mutable.Set[ByteString] = {
    val lru = new java.util.LinkedHashMap[ByteString, java.lang.Boolean](1024, 0.75f, false) {
      override def removeEldestEntry(eldest: java.util.Map.Entry[ByteString, java.lang.Boolean]): Boolean =
        size() > cap
    }
    java.util.Collections.newSetFromMap[ByteString](lru).asScala
  }

  def props(
      stateRoot: ByteString,
      networkPeerManager: ActorRef,
      requestTracker: SNAPRequestTracker,
      mptStorage: MptStorage,
      batchSize: Int,
      snapSyncController: ActorRef,
      concurrency: Int = 16,
      visitedCap: Int = DefaultVisitedCap,
      healingFrontierStorage: Option[HealingFrontierStorage] = None,
      healingWriterEcOverride: Option[ExecutionContext] = None,
      traversalParallelism: Int = DefaultDfsParallelism,
      bfsQueueStorageOpt: Option[BfsQueueStorage] = None
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
        visitedCap,
        healingFrontierStorage,
        healingWriterEcOverride,
        traversalParallelism,
        bfsQueueStorageOpt
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
