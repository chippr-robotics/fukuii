package com.chipprbots.ethereum.blockchain.sync.snap.actors

import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.TimerScheduler
import org.apache.pekko.util.ByteString

import scala.collection.mutable
import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.blocking
import scala.concurrent.duration.*
import scala.concurrent.duration.Duration
import scala.jdk.CollectionConverters.*

import org.bouncycastle.util.encoders.Hex

import com.chipprbots.ethereum.blockchain.sync.snap.*
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.storage.BfsEntry
import com.chipprbots.ethereum.db.storage.BfsQueueStorage
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.HealingFrontierStorage
import com.chipprbots.ethereum.db.storage.InMemoryBfsQueueStorage
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.db.storage.PathNodeStorage
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.p2p.messages.SNAP.GetTrieNodes
import com.chipprbots.ethereum.network.p2p.messages.SNAP.TrieNodes

/** TrieNodeHealingCoordinator manages the healing phase of SNAP sync.
  *
  * State healing downloads missing trie nodes (intermediate branch/extension nodes that snap sync skips) by requesting
  * them from peers via GetTrieNodes. Nodes are identified by their trie path (HP-encoded) and stored directly by hash
  * in the node storage.
  *
  * The coordinator receives missing node descriptions from SNAPSyncController (which discovers them via trie walks) and
  * dispatches GetTrieNodes requests to available peers.
  */
private[actors] class TrieNodeHealingCoordinatorImpl(
    context: ActorContext[TrieNodeHealingCoordinator.Command],
    timers: TimerScheduler[TrieNodeHealingCoordinator.Command],
    initialStateRoot: ByteString,
    networkPeerManager: org.apache.pekko.actor.typed.ActorRef[NetworkPeerManagerActor.Command],
    requestTracker: SNAPRequestTracker,
    mptStorage: MptStorage,
    batchSize: Int,
    snapSyncController: org.apache.pekko.actor.typed.ActorRef[SNAPSyncController.Command],
    concurrency: Int,
    visitedCap: Int = TrieNodeHealingCoordinator.DefaultVisitedCap,
    healingFrontierStorage: Option[HealingFrontierStorage] = None,
    healingWriterEcOverride: Option[ExecutionContext] = None,
    healingReaderEcOverride: Option[ExecutionContext] = None,
    traversalParallelism: Int = TrieNodeHealingCoordinator.DefaultBfsParallelism,
    healingMinParallelism: Int = TrieNodeHealingCoordinator.DefaultMinParallelism,
    healingReservedCores: Int = TrieNodeHealingCoordinator.DefaultReservedCores,
    bfsQueueStorageOpt: Option[BfsQueueStorage] = None,
    storageScheme: StorageScheme = StorageScheme.Hash,
    pathNodeStorageOpt: Option[PathNodeStorage] = None,
    frontierHighWater: Int = TrieNodeHealingCoordinator.DefaultFrontierHighWater,
    frontierLowWater: Int = TrieNodeHealingCoordinator.DefaultFrontierLowWater,
    frontierBackpressureMaxWaitMs: Long = TrieNodeHealingCoordinator.FrontierBackpressureMaxWaitMs,
    scopedHealVerification: Boolean = true,
    scopedHealMaxPaths: Int = TrieNodeHealingCoordinator.DefaultScopedHealMaxPaths,
    // spec 005 (Pruned descend-and-stop verification). When true AND `storageScheme == Hash`, the completeness walk
    // prunes any present node that has a durable subtree-complete record (HealingFrontierStorage CF 'g'), making a
    // fresh node's first verification O(missing-frontier). Off OR Path scheme ⇒ the unchanged full-trie walk. Consensus
    // safety rests on the never-false-prune invariant: prune iff present AND recorded-complete (records written only
    // AFTER the subtree's bytes are durably committed). See `prunedEnabled`.
    prunedHealVerification: Boolean = true,
    // spec 002/003 (frontier persistence + resume + completeness marker). Gates the LAYER-2 frontier MIRROR writes
    // (persistFrontier/unpersistFrontier/clearPersistedFrontier), the [HEAL-RESTART] resume-from-persisted-frontier
    // path, and the spec-002 `markComplete()` snapshot marker. Decoupled from store PRESENCE: spec 005 makes the store
    // present whenever `prunedHealVerification` is on (to host the subtree-complete records), but these persistence
    // features must stay dark unless `healing-frontier-persistence` is explicitly enabled (default off), preserving
    // FR-005 byte-for-byte parity with pre-spec-005 behavior. The spec-005 subtree records (markSubtreeComplete /
    // isSubtreeComplete / the descend-and-stop oracle) are gated on `prunedEnabled`, NOT on this flag.
    frontierPersistenceEnabled: Boolean = false,
    // spec 004 (Decoupled Heal Serve-Root). When true, the GetTrieNodes fetch targets `serveRoot` (an advancing
    // newest-servable root) instead of the walk root `stateRoot`; when false, the fetch uses `stateRoot` (coupled,
    // byte-identical to pre-spec-004). Completeness is ALWAYS judged against `stateRoot`, regardless of this flag.
    decoupledHealServeRoot: Boolean = false,
    // FR-006 surfacing threshold: after this many unsatisfied attempts with no serve-root advance, surface (log +
    // metric). Never force-completes.
    decoupledHealMaxAttemptsNoRefresh: Int = TrieNodeHealingCoordinator.DefaultDecoupledHealMaxAttemptsNoRefresh,
    // spec 009 (Moving-Root Delta Heal). When true, the heal completes toward AND fetches against ONE current served
    // root: requestNextBatch (T005) collapses the fetch root to the completeness root `stateRoot`, and
    // StartTrieNodeHealing (T006) seeds an absent heal root as a frontier task and fetches it instead of handing off to
    // lazy healing. When false, the heal uses the spec-004 walk/serve split (byte-identical to pre-spec-009). Impl
    // default false (bare construction stays on the spec-004 path); the production default-on flows from SNAPSyncConfig
    // via the spawn sites. The content-hash store gate and the finalizeSnapSync anchor guard are byte-untouched on BOTH
    // paths — under the flag the gate matches BY CONSTRUCTION (fetch root == completeness root), never by weakening it.
    movingRootDeltaHeal: Boolean = false,
    // Where contract bytecode lives. A healed account leaf whose codeHash is not in here is reported to the controller
    // (SNAPSyncController.HealedCodeHashes) so the bytecode is fetched before SNAP finalises. None (bare construction,
    // most specs) reports nothing, byte-identical to before.
    evmCodeStorage: Option[EvmCodeStorage] = None,
    // Shared with the controller. True while a frontier walk is running and nothing is pending or in flight, i.e. the
    // heal needs no peers and any re-peg of the heal root can only invalidate the walk. The controller's proactive
    // heal-root re-peg (moving-root delta heal) is suppressed while it is set; None (bare construction) never suppresses.
    walkLocalOnly: Option[java.util.concurrent.atomic.AtomicBoolean] = None
):

  import TrieNodeHealingCoordinator.*

  private val log = context.log
  // `self` was a Classic field; under Typed it is `context.self`. Captured here so the Future continuations,
  // scheduled timers and the request-tracker timeout callback reference the same value the old code expected.
  private val self: org.apache.pekko.actor.typed.ActorRef[Command] = context.self
  // Thread-confined `context.log` is unsafe inside Future/BFS bodies that run off the actor dispatcher. Off-thread
  // logging (the HEAL-RESTART resume Future, startFrontierBFS Future, and rebuildFrontierBFS/processSubRange which
  // execute on healingWriterEc/healingReaderEc) uses this plain SLF4J logger instead. Actor-thread handlers use `log`.
  private val asyncLog: org.slf4j.Logger =
    org.slf4j.LoggerFactory.getLogger(classOf[TrieNodeHealingCoordinatorImpl])

  // Mutable state root — updated in-place when the controller refreshes the pivot (HealingPivotRefreshed).
  private var stateRoot: ByteString = initialStateRoot

  // Task management — each task has a pathset (for GetTrieNodes) and a hash (for verification).
  // ArrayDeque (circular buffer) gives O(1) amortized head/tail operations (#1167). The previous
  // immutable `Seq` did O(n) on every `:+` and head-drop — quadratic at healing scale.
  private case class HealingEntry(pathset: Seq[ByteString], hash: ByteString)
  private val pendingTasks: mutable.ArrayDeque[HealingEntry] = mutable.ArrayDeque.empty
  // Thread-safe mirror of pendingTasks.size, refreshed by emitHealingFrontierGauges() on the actor
  // thread. The frontier-rebuild BFS walk runs on healingWriterEc and reads this (never pendingTasks
  // directly) to apply backpressure — it pauses emission when the healing backlog is large so a
  // peer-scarce drain rate can't let discovered-but-unhealed nodes pile up to an OOM (see
  // awaitFrontierDrain). geth bounds the analogous queue with trie.Sync maxFetchesPerDepth.
  private val pendingBackpressure: java.util.concurrent.atomic.AtomicInteger =
    new java.util.concurrent.atomic.AtomicInteger(0)
  private var completedTaskCount: Int = 0
  private var healingMilestonePct: Int = -1

  // codeHashes of account leaves healed in the response being processed whose bytecode is not in `evmCodeStorage`.
  // Filled by discoverMissingChildren, drained and sent to the controller once per response.
  private val healedCodeHashesThisResponse: mutable.LinkedHashSet[ByteString] = mutable.LinkedHashSet.empty

  /** Dedicated dispatcher for the batched raw-node RocksDB flush. Tests inject their own EC; production looks up
    * `healing-writer-dispatcher` from the actor system. Keeps the blocking write off `sync-dispatcher` so other sync
    * actors don't stall during healing-heavy bursts.
    */
  private val healingWriterEc: ExecutionContext =
    healingWriterEcOverride.getOrElse(context.system.classicSystem.dispatchers.lookup("healing-writer-dispatcher"))

  /** Dedicated dispatcher for the BFS sub-range *reader* Futures (`processSubRange`). Distinct from `healingWriterEc`
    * to break a latent thread-starvation deadlock: the parent walk Future parks a `healingWriterEc` thread inside
    * `Await.result(...)` (rebuildFrontierBFS) while waiting for the N sub-range Futures to finish. If those sub-range
    * Futures shared `healingWriterEc`, raising parallelism toward the pool size would leave the parked parent waiting
    * on Futures queued behind it on the same pool → deadlock. Running the sub-ranges on a separate pool removes the
    * dependency. Tests inject their own EC (a same-thread EC forces deterministic serial execution); production looks
    * up `healing-reader-dispatcher`. See spec 002 R3 §2 (T032/T033).
    */
  private val healingReaderEc: ExecutionContext =
    healingReaderEcOverride.getOrElse(context.system.classicSystem.dispatchers.lookup("healing-reader-dispatcher"))

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
  private case object HealingStagnationCheck extends Command
  private var consecutiveIdleChecks: Int = 0
  private var lastPulseHealedCount: Int = 0
  // FIX-STAGNATION-LIMIT: Track consecutive 2-min cycles with zero healed nodes (even when active).
  // After MaxConsecutiveStagnations, notify controller to restart with fresh pivot.
  private var consecutiveStagnations: Int = 0
  private val MaxConsecutiveStagnations: Int = 3
  private var trieWalkInProgress: Boolean = false
  // Verification BFS state: gates StateHealingComplete and catches storage sub-trie gaps (Fix BUG-1/BUG-2).
  // verificationPassComplete = true only after a BFS traversal finds zero missing nodes.
  // verificationBFSRunning = true while ANY frontier walk Future (crash-recovery rebuild OR
  // verification) is executing on healingWriterEc. Set inside startFrontierBFS so both walk kinds
  // are covered — the crash-recovery rebuild previously set NO flag, so HEAL-PULSE reported
  // walkRunning=false and the dead-pulse watchdog force-started a verification walk 6 minutes into
  // a running rebuild (observed live 2026-06-11): two walks interleaving on the shared bfsQueue,
  // both producing garbage coverage. @volatile: set from the walk's EC thread, read on the actor
  // thread by the watchdog/completion/pivot gates.
  private var verificationPassComplete: Boolean = false
  // Consecutive verification walks discarded because the heal root moved under them; reset by any walk of the current
  // root that completes. Bounded so a root that outruns the walk cannot livelock the heal.
  private var consecutiveStaleDiscards: Int = 0
  private val MaxConsecutiveStaleDiscards: Int = 3
  // Was `@volatile` under Classic: the walk's EC thread wrote it and the actor thread read it. Under Typed all
  // reads AND writes happen on the actor dispatcher — the field is set true on the actor thread before launching a
  // walk Future (startFrontierBFS/startVerificationBFS/startScopedVerification) and set false only inside the
  // FrontierRebuildComplete / VerificationBFSComplete / FrontierWalkFailed message handlers. The Future bodies never
  // touch it; they communicate completion via `selfRef ! …`. So a plain var is correct (constraint #6).
  private var verificationBFSRunning: Boolean = false
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

  // Crash-recovery BFS: emit frontier in batches so healing starts before traversal completes.
  // go-ethereum trie.Sync.Missing() alignment — bounded working set rather than full upfront BFS.
  private val FrontierBatchSize = 1000

  // Cap on the frontier-rebuild walk's `visited` set. The walk covers the full state trie (accounts +
  // every storage trie — tens of millions of nodes on ETC mainnet); an unbounded visited set grew
  // to ~2.9 GB and OOM-looped the node. A fixed-capacity FIFO/insertion-order set (NOT an LRU — it
  // evicts the earliest-INSERTED entry regardless of recent access) bounds the heap; budget for
  // ~cap × ~120-150 B (a 32-byte ByteString key + its wrapper + the LinkedHashMap entry), i.e. the
  // 4M default is ~480-640 MB, NOT the 320 MB an 80 B/entry estimate would suggest. Completeness is
  // preserved: an evicted present node is only RE-WALKED if reached again via a shared reference
  // (extra work, never a skip), and any missing node it re-discovers is de-duplicated by
  // `pendingHashSet`. See docs/design/healing-frontier-scale.md. Operator-tunable via
  // `sync.snap-sync.healing-visited-cap`; raise it only from a measured `inflation_ratio` (US2) and
  // within the heap budget — do NOT set it to 20M (~2.4-3.2 GB) on a 6 GB heap (it OOMs).
  private val HealingVisitedCap: Int = visitedCap
  private val HealingTraversalParallelism: Int = traversalParallelism
  private val HealingMinParallelism: Int = healingMinParallelism
  private val HealingReservedCores: Int = healingReservedCores
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
    if frontierPersistenceEnabled then
      healingFrontierStorage.foreach { store =>
        if entries.nonEmpty then store.update(Nil, entries.map(e => e.hash -> e.pathset)).commit()
      }

  /** Delete healed nodes from the persisted frontier. Safe to call from the healing-writer thread (touches only the
    * immutable storage handle + thread-safe RocksDB). Removing an absent key is a no-op.
    */
  private def unpersistFrontier(hashes: Seq[ByteString]): Unit =
    if frontierPersistenceEnabled then
      healingFrontierStorage.foreach { store =>
        if hashes.nonEmpty then store.update(hashes, Nil).commit()
      }

  /** Clear the entire persisted frontier by deleting every outstanding hash. Because the persisted set equals
    * `pendingTasks ∪ in-flight`, deleting those hashes empties the CF without a namespace-wipe primitive. MUST be
    * called BEFORE the in-memory `pendingTasks`/`activeRequests` are cleared.
    */
  private def clearPersistedFrontier(): Unit =
    // FR/T013: a same-root HealingPivotRefreshed no longer reaches here — its early guard returns first.
    // Reaching this method therefore always means a genuine invalidation (differing-root refresh or
    // abandonment), so dropping the completeness marker below is always correct.
    if frontierPersistenceEnabled then
      healingFrontierStorage.foreach { store =>
        val outstanding =
          pendingTasks.iterator.map(_.hash).toSeq ++ activeRequests.values.iterator
            .flatMap(_.tasks.iterator.map(_.hash))
        if outstanding.nonEmpty then store.update(outstanding, Nil).commit()
        // The snapshot is no longer valid/complete — drop the marker so a restart re-walks rather than resuming stale.
        store.clearComplete()
      }

  /** Publish the live healing backlog/in-flight gauges for the Grafana healing-analytics section. */
  private def emitHealingFrontierGauges(): Unit =
    pendingBackpressure.set(pendingTasks.size)
    SNAPSyncMetrics.setHealingFrontierPending(pendingTasks.size.toLong)
    SNAPSyncMetrics.setHealingActiveRequests(activeRequests.size.toLong)
    // spec 004 T019/FR-010: refresh the decoupling observability gauges on every pulse (cheap O(1) reads).
    if decoupledHealServeRoot then
      SNAPSyncMetrics.setHealingCrossRootHeals(crossRootHealCount)
      SNAPSyncMetrics.setHealingUnservableTasks(
        healAttempts.count { case (_, n) => n > decoupledHealMaxAttemptsNoRefresh }.toLong
      )

  /** Frontier-emission backpressure, called from the BFS walk thread (healingWriterEc) before each FrontierRebuilt
    * batch. The walk reads the locally-stored trie fast (thousands of nodes/s) while healing drains over the network on
    * a handful of SNAP peers (tens-to-hundreds/s). With no gate, a verification walk that re-discovers a large frontier
    * floods pendingTasks/the actor mailbox until the heap is exhausted (observed live 2026-06-13: OOM at L7 under peer
    * scarcity). This pauses the walk when the backlog reaches the high-water mark and resumes once it drains below the
    * low-water mark. Reads only the AtomicInteger mirror + immutable vals — touches no actor state. Blocking is safe
    * here: healingWriterEc is the blocking healing dispatcher. A hard timeout guarantees the walk can never deadlock if
    * the drain stalls (fail loud, then resume).
    */
  private def awaitFrontierDrain(): Unit =
    if pendingBackpressure.get() >= frontierHighWater then
      val startWait = System.currentTimeMillis()
      log.info(
        s"[HEAL-BFS] Backpressure: healing backlog ${pendingBackpressure.get()} >= high-water " +
          s"$frontierHighWater — pausing frontier emission until it drains below $frontierLowWater"
      )
      while pendingBackpressure.get() > frontierLowWater &&
        System.currentTimeMillis() - startWait < frontierBackpressureMaxWaitMs
      do
        // blocking{} lets a ForkJoin-backed EC (e.g. the test injected EC) compensate with a spare
        // thread while parked here. On the production healing-writer-dispatcher (fixed thread pool)
        // this is a no-op, but documents that the sleep is intentionally blocking — consistent with
        // the Await.result(blocking{...}) pattern already used in rebuildFrontierBFS.
        blocking(Thread.sleep(200))
      val waitedMs = System.currentTimeMillis() - startWait
      if pendingBackpressure.get() > frontierLowWater then
        log.warn(
          s"[HEAL-BFS] Backpressure wait hit the " +
            s"${frontierBackpressureMaxWaitMs / 1000}s safety timeout " +
            s"(backlog still ${pendingBackpressure.get()}) — resuming emission to avoid deadlock"
        )
      else log.info(s"[HEAL-BFS] Backpressure released after ${waitedMs}ms — resuming frontier emission")

  // Track last known available peers for re-dispatch after failures
  private val knownAvailablePeers = mutable.Set[Peer]()

  // Dedup set for pending tasks — prevents the same missing node from being queued multiple times
  private val pendingHashSet = mutable.Set[ByteString]()

  // Path scheme only: a node hash can be missing at MANY paths (identical subtrees / shared nodes). pendingHashSet
  // dedups the queue by hash, so only one path per hash is requested; the others were previously dropped and the
  // next verification walk found them missing again (fixed ~10/pass convergence). Remember the dropped paths here
  // and write the one fetched node at every path when it arrives.
  private val pathAliases = mutable.Map[ByteString, mutable.LinkedHashMap[Seq[ByteString], HealingEntry]]()

  // --- spec 003: scoped post-heal verification (FR-001) ---
  // Bounded, per-round, in-memory accumulator of the nodes HEALED this round (their HealingEntry,
  // captured at the single heal site in handleResponse). Mirrors the pendingTasks/pendingHashSet
  // pairing: a LinkedHashMap keyed by node hash dedups re-served/re-queued nodes while preserving the
  // authoritative HealingEntry value and a stable insertion order for deterministic seeding. The
  // completion gate uses this as the scope for the post-heal verification BFS (re-walking only the
  // healed subtrees) when the durable completeness marker proves full-trie coverage; otherwise it
  // falls back to full-root verification. NOT persisted (actor field state, like pendingTasks); a
  // restart loses it and the gate falls back to full-root (correct, slower). See spec 003 C1/C4.
  private val healedPathsThisRound: mutable.LinkedHashMap[ByteString, HealingEntry] =
    mutable.LinkedHashMap.empty
  // The state root the current round's healed paths were healed against (FR-009 / F5 guard). Tagged on
  // the first capture of the round; a scoped verification is launched only when this == stateRoot.
  private var healedPathsRoot: ByteString = ByteString.empty
  // Latched true once the round would exceed scopedHealMaxPaths (FR-011 / F4). Once set, no further
  // capture occurs and the gate falls back to full-root verification (which covers everything).
  private var healedPathsOverflowed: Boolean = false

  // spec 003 C6/T016: observability state for the IN-FLIGHT scoped verification run. When a scoped
  // verification is launched, scopedVerificationActive is set with its start time and seed count so the
  // VerificationBFSComplete handler can emit the [HEAL-VERIFY-SCOPED] completion log + duration gauge.
  // Cleared (None) on the full-root path so the completion handler does not mis-attribute a full-root run.
  private var scopedVerificationStartMs: Long = 0L
  private var scopedVerificationSeedCount: Int = 0
  private var scopedVerificationActive: Boolean = false

  // --- spec 005 (Pruned descend-and-stop verification, D5/T005) ---
  // Single scheme gate consulted at the verification entry and inside the per-child descent oracle: pruning is
  // effective ONLY when the config flag is on AND the node uses Hash addressing (the subtree-complete record keys on
  // the bare keccak hash — well-defined only under Hash scheme; under Path the nodes are nibble-path-keyed). Off OR
  // Path scheme ⇒ the unchanged full-trie walk (`startVerificationBFS`), byte-identical to today. A healing frontier
  // store must also be present for any record read/write — `prunedEnabled` is necessary but `healingFrontierStorage`
  // is the actual record sink, so each record site additionally guards on it being defined.
  private val prunedEnabled: Boolean =
    prunedHealVerification && storageScheme == StorageScheme.Hash && healingFrontierStorage.isDefined

  // spec 005 C8/FR-009: observability for the IN-FLIGHT pruned verification run — count of present subtrees pruned
  // (descend-and-stop hits) and the run start time, set when the verification entry takes the pruned path and read by
  // the VerificationBFSComplete handler to emit the [HEAL-VERIFY-PRUNED] completion log + duration/pruned-count gauges.
  private var prunedVerificationActive: Boolean = false
  private var prunedVerificationStartMs: Long = 0L
  // Pruned-subtree hits accumulated across the BFS sub-ranges of the current verification walk. AtomicLong because the
  // oracle fires on `healingReaderEc` sub-range threads (parallel levels). Reset at the start of each pruned walk.
  private val prunedSubtreeCount: java.util.concurrent.atomic.AtomicLong =
    new java.util.concurrent.atomic.AtomicLong(0L)

  /** Reset the scoped-verification healed-paths set (spec 003 C1). Called at the round-invalidation / round-close sites
    * — differing-root HealingPivotRefreshed, HealingForceComplete, and after a verified StateHealingComplete — NOT on a
    * same-root refresh (that round is still valid). Idempotent.
    */
  private def clearHealedPathsSet(): Unit =
    healedPathsThisRound.clear()
    healedPathsRoot = ByteString.empty
    healedPathsOverflowed = false

  // --- spec 004: decoupled heal serve-root (FR-001/FR-002) ---
  // T008: the SERVE root used to fetch missing nodes (GetTrieNodes). Initialized to the walk root so that when
  // the feature is OFF (or before any serve-root has been obtained) the fetch is byte-identical to the coupled
  // path. Advances ONLY via the HealingServeRootRefresh handler; read ONLY at the fetch build site in
  // requestNextBatch (and only when `decoupledHealServeRoot` is true). The completeness walk and gate never read
  // it — they key off `stateRoot` (the walk root) exclusively, preserving FR-007 parity by construction.
  private var serveRoot: ByteString = stateRoot
  // T015 / FR-006: per-task count of fetch attempts that did not satisfy the task (content-mismatch drop, empty
  // response, or timeout re-queue). Bounded by the pending-task set (an entry is only ever incremented for a hash
  // that is being re-queued, and the whole map is cleared on every serve-root advance). A task that exceeds
  // `decoupledHealMaxAttemptsNoRefresh` with no serve-root advance in between is surfaced (log + metric) — it is
  // NEVER force-completed: the content-hash check guarantees a wrong/missing node can never be accepted, so a stuck
  // node simply keeps the walk from finding zero, which is the correct (no-false-completion) behaviour.
  private val healAttempts = mutable.Map.empty[ByteString, Int]
  // T019 / FR-010: count of nodes healed via a serve root that differs from the walk root (cross-root heals).
  private var crossRootHealCount: Long = 0L
  // T020: number of serve-root refreshes engaged this coordinator lifetime (observability only).
  private var serveRootRefreshCount: Long = 0L

  /** spec 004 T019: encode the leading 6 bytes of a root hash as a numeric "short label" gauge value so an operator can
    * eyeball-correlate the walk-root / serve-root gauges with the `[HEAL]` log lines. This is observation-only — never
    * read by any walk / completeness / fetch decision. Empty ⇒ 0. Six bytes (48 bits) is deliberate: it is always
    * non-negative and is exactly representable in a Prometheus gauge's IEEE-754 double (53-bit mantissa). Eight bytes
    * pushed the leading byte's high bit into the Long sign (rendering a spurious negative ~-3.7e18) AND exceeded the
    * mantissa (the displayed double no longer matched the actual bytes).
    */
  private def shortRootLabel(root: ByteString): Long =
    var acc = 0L
    val n = root.length.min(6)
    var i = 0
    while i < n do
      acc = (acc << 8) | (root(i) & 0xffL)
      i += 1
    acc

  /** spec 004 T015/C5/FR-006: record one unsatisfied fetch attempt for a heal task (content-mismatch drop, empty
    * response, or timeout re-queue). When a task crosses `decoupledHealMaxAttemptsNoRefresh` attempts WITHOUT a
    * serve-root advance in between (a serve-root refresh clears the whole map), surface it once at the crossing (WARN
    * log + unservable-count metric). This is OBSERVATION ONLY — it MUST NOT force-complete, abandon, or declare
    * completion: the content-hash check (C4) guarantees a wrong/missing node can never be accepted, so a stuck node
    * simply keeps the walk from finding zero, which is the correct no-false-completion behaviour (SC-002). The map is
    * bounded by the pending-task set (only ever keyed by hashes being re-queued; cleared on every serve-root advance
    * and decayed when a task is satisfied). No-op when decoupling is disabled — the coupled path's behaviour is
    * byte-identical to today (SC-006).
    */
  private def noteUnservableAttempt(hash: ByteString): Unit =
    if decoupledHealServeRoot then
      val attempts = healAttempts.getOrElse(hash, 0) + 1
      healAttempts.update(hash, attempts)
      if attempts == decoupledHealMaxAttemptsNoRefresh + 1 then
        // Crossed the threshold for the first time since the last serve-root advance — surface it once.
        val unservable = healAttempts.count { case (_, n) => n > decoupledHealMaxAttemptsNoRefresh }
        log.warn(
          s"[HEAL-SERVE-ROOT] Heal task ${Hex.toHexString(hash.take(4).toArray)} unservable after $attempts " +
            s"attempts with no serve-root advance (threshold=$decoupledHealMaxAttemptsNoRefresh). " +
            s"NOT force-completing (content-hash check keeps completion gated on the walk). " +
            s"unservable-now=$unservable serve=${Hex.toHexString(serveRoot.take(4).toArray)} " +
            s"walk=${Hex.toHexString(stateRoot.take(4).toArray)}"
        )
        SNAPSyncMetrics.setHealingUnservableTasks(unservable.toLong)

  // Stateless peer tracking (geth-aligned: peers that return empty TrieNodes for current root)
  private val statelessPeers = mutable.Set[String]()
  // Soft-exile strike counter (mirrors AccountRangeCoordinator.emptyResponseStrikes). A SINGLE empty
  // TrieNodes response used to permanently exile a peer for the whole root, which on a 2-peer-eligible
  // residual drains the pool to zero and stalls the heal one node short (observed live: only 2 of ~10
  // connected peers ever tried). Instead we strike on each consecutive empty response and only exile at
  // the threshold; any successful heal from a peer wipes its strikes. Peers rotate — one that returns
  // empty now may serve after a refresh — so the set is also auto-cleared periodically (see
  // HealingStagnationCheck). 5 strikes mirrors Account/Storage's EmptyResponseStrikeThreshold.
  private val emptyResponseStrikes = mutable.Map.empty[String, Int]
  private val EmptyResponseStrikeThreshold: Int = 5
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
    if requested > 0 && received * 10 >= requested * 9 && requested < maxResponseBytes then
      val next = (requested.toDouble * increaseFactor).toLong
      peerResponseBytesTarget.update(peer.id.value, BigInt(next).min(maxResponseBytes))

  private def adjustResponseBytesOnFailure(peer: Peer, reason: String): Unit =
    val cur = responseBytesTargetFor(peer)
    val next = (cur.toDouble * decreaseFactor).toLong
    peerResponseBytesTarget.update(peer.id.value, BigInt(next).max(minResponseBytes))
    log.debug(
      s"Reducing healing responseBytes target for peer ${peer.id.value}: $cur -> ${peerResponseBytesTarget(peer.id.value)} ($reason)"
    )

  // Peer cooldown
  private val peerCooldownUntilMs = mutable.Map[String, Long]()
  private val peerCooldownDefault = 30.seconds

  private def isPeerCoolingDown(peer: Peer): Boolean =
    peerCooldownUntilMs.get(peer.id.value).exists(_ > System.currentTimeMillis())

  private def recordPeerCooldown(peer: Peer, reason: String): Unit =
    val until = System.currentTimeMillis() + peerCooldownDefault.toMillis
    peerCooldownUntilMs.put(peer.id.value, until)
    log.debug(s"Cooling down peer ${peer.id.value} for ${peerCooldownDefault.toSeconds}s: $reason")

  /** Count in-flight requests for a given peer (pipelining support). */
  private def inFlightForPeer(peer: Peer): Int =
    activeRequests.values.count(_.peer.id == peer.id)

  /** Dispatch up to maxInFlightPerPeer requests to a single peer (pipelining). */
  private def dispatchIfPossible(peer: Peer): Unit =
    if !pivotRefreshRequested && !statelessPeers.contains(peer.id.value) && !isPeerCoolingDown(peer) then
      var inflight = inFlightForPeer(peer)
      var blocked = false
      while !blocked && pendingTasks.nonEmpty && inflight < maxInFlightPerPeer && activeRequests.size < maxConcurrentRequests
      do
        requestNextBatch(peer) match
          case Some(_) => inflight += 1
          case None    => blocked = true

  // Batched raw node storage: accumulate nodes and flush asynchronously
  private val rawNodeBuffer = mutable.ArrayBuffer[(ByteString, Array[Byte])]()
  private val rawFlushThreshold = 1000
  private var flushing: Boolean = false

  // spec 005 C3b/D2/T009/T012: heal-side subtree-complete seeding, DEFERRED until durable. discoverMissingChildren
  // identifies a candidate X (a just-healed node whose direct children are ALL present on disk AND themselves
  // recorded subtree-complete ⇒ X's whole subtree is present by induction). But X's OWN bytes are still in
  // `rawNodeBuffer` (NOT durable) at discovery time, so recording then would be unsound (crash before flush ⇒ record
  // present but X's bytes lost ⇒ false prune). Instead we stage candidates here and write the record ONLY in the
  // flush path, AFTER `mptStorage.persist()` durably commits the buffered nodes (T012 record-after-persist). A crash
  // before the flush drops the in-memory candidate ⇒ safe descend. Records are written iff `prunedEnabled`.
  private val pendingSubtreeRecords = mutable.Set[ByteString]()

  // Internal messages — sent only via `selfRef ! …` from Future continuations / timers, so they must be Commands so
  // the Typed behavior can receive them. They were plain `Any` messages under Classic.
  // Internal message for async flush completion
  private case class FlushComplete(count: Int) extends Command

  // Internal message for async frontier rebuild completion (crash-recovery BFS or verification BFS)
  private case class FrontierRebuilt(entries: Seq[HealingEntry]) extends Command
  // Sent by startVerificationBFS when the BFS Future completes — gates verificationPassComplete.
  // Carries the root the walk was LAUNCHED against (`walkRoot`), exactly as `FrontierRebuildComplete` does. A pivot
  // re-peg does not cancel an in-flight verification walk, so its completion can land after `stateRoot` moved; the
  // handler must not credit a clean walk of the OLD root as verification of the NEW one. This is decisive for the
  // per-block-hot system-contract storage tries (EIP-4788/2935/8282): they differ between every pair of roots, while
  // the cold bulk of the state is shared, so an old-root "clean" signal says nothing about them.
  // `missingEmitted` is the number of missing nodes the walk found. A pass that found ANY is not a clean pass even if
  // every one of them has been healed by the time it ends: the walk never descended below a node that was missing when
  // it reached it, so the healed node's present children (and their subtrees) were not verified.
  private case class VerificationBFSComplete(walkRoot: ByteString, missingEmitted: Long) extends Command

  // Layer 2: the full-state rebuild BFS finished — the persisted frontier is now a COMPLETE snapshot.
  // Sent after the final FrontierRebuilt so the completeness marker is set only once every node is persisted.
  // spec 006 C1/D3/FR-004: carries the rebuild's outcome so the handler can decide early completion soundly.
  //   missingEmitted = the walk's frontierCount (number of missing nodes emitted); 0 ⇒ found everything present.
  //   walkRoot       = the root the finished walk traversed (captured at launch); compared against the live
  //                    stateRoot to exclude a stale completion after a pivot refresh (F-E).
  private case class FrontierRebuildComplete(missingEmitted: Long, walkRoot: ByteString) extends Command

  // A frontier walk Future died with an exception. Resets the walk flags WITHOUT setting any
  // completion marker, so the watchdog / HealingCheckCompletion gates can start a fresh walk.
  // Without this, an exception skips onComplete() and verificationBFSRunning stays true forever,
  // permanently blocking every future walk (including the watchdog) until restart.
  private case object FrontierWalkFailed extends Command

  // Routes the HEAL-RESTART resume decision back onto the actor thread. The crash-recovery Future (in
  // StartTrieNodeHealing) reads the persisted frontier off-thread, then sends ONE of these so the
  // verificationBFSRunning mutation + walk launch happen on the actor dispatcher (Typed: no off-thread state
  // mutation). Under Classic the Future called start*BFS directly — safe only because verificationBFSRunning was
  // @volatile. Now @volatile is gone, so the launch must be marshalled back to the actor thread.
  private case class RestartResumeVerification(root: ByteString, rootPath: ByteString) extends Command
  private case class RestartFullRebuild(root: ByteString, rootPath: ByteString) extends Command

  /** spec 005 C3b/D2/T009/T012: write the staged heal-side subtree-complete records whose node-bytes were just durably
    * committed by a flush. `toRecord` is the already-computed (on the actor thread) intersection of this flush's hashes
    * with `pendingSubtreeRecords`. This MUST be called ONLY after `mptStorage.persist()` returns (the subtree bytes are
    * durable) so the record-after-persist ordering (D3) holds; a `HealingFrontierStorage.markSubtreeComplete` is an
    * ordinary WAL-ordered `update` (loss ⇒ safe descend). No-op unless `prunedEnabled`.
    */
  private def writeDurableSubtreeRecords(toRecord: Seq[ByteString]): Unit =
    if prunedEnabled && toRecord.nonEmpty then
      healingFrontierStorage.foreach { store =>
        toRecord.foreach(store.markSubtreeComplete)
        log.debug(s"[HEAL-VERIFY-PRUNED] Seeded ${toRecord.size} heal-closed subtree-complete records (post-flush)")
      }

  /** Synchronous flush — used only for final completion flush (small buffer, safe to block). */
  private def flushRawNodesSync(): Unit =
    if rawNodeBuffer.nonEmpty then
      val flushed = rawNodeBuffer.toSeq
      // spec 005 T012: compute the staged records this flush makes durable BEFORE persist, write them AFTER.
      val toRecord = flushed.iterator.map(_._1).filter(pendingSubtreeRecords.contains).toSeq
      toRecord.foreach(pendingSubtreeRecords.remove)
      mptStorage.storeRawNodes(flushed)
      mptStorage.persist()
      writeDurableSubtreeRecords(toRecord) // post-persist (D3) — bytes durable before the record
      unpersistFrontier(flushed.map(_._1)) // Layer 2: healed nodes leave the persisted frontier
      val count = flushed.size
      rawNodeBuffer.clear()
      log.info(s"Flushed $count healed nodes to disk (total: $totalNodesHealed)")

  /** Async flush — copies buffer, clears it, writes on the dedicated `healing-writer-dispatcher` so the blocking
    * RocksDB write doesn't compete with sync actors on `sync-dispatcher`.
    */
  private def flushRawNodesAsync(): Unit =
    if rawNodeBuffer.nonEmpty && !flushing then
      flushing = true
      val nodes = rawNodeBuffer.toSeq
      rawNodeBuffer.clear()
      // spec 005 T012: snapshot the staged records this flush will make durable on the ACTOR thread (pendingSubtreeRecords
      // is actor state), then write them on the worker thread AFTER persist. Crash before persist ⇒ records dropped ⇒ safe.
      val toRecord = nodes.iterator.map(_._1).filter(pendingSubtreeRecords.contains).toSeq
      toRecord.foreach(pendingSubtreeRecords.remove)
      import scala.concurrent.{Future, blocking}
      val selfRef = self
      val ec = healingWriterEc
      Future {
        blocking {
          mptStorage.storeRawNodes(nodes)
          mptStorage.persist()
          writeDurableSubtreeRecords(
            toRecord
          ) // post-persist (D3); HealingFrontierStorage writes are DB-lock-safe off-thread
          unpersistFrontier(nodes.map(_._1)) // Layer 2: healed nodes leave the persisted frontier (post-durable-write)
          nodes.size
        }
      }(ec).foreach(n => selfRef ! FlushComplete(n))(ec)

  // preStart equivalent: log the decoupling mode once, seed gauges, and start the recurring stagnation pulse. Called
  // once by the behavior factory. The Typed `startTimerWithFixedDelay` replaces the Classic `scheduleWithFixedDelay`
  // Cancellable; it auto-cancels when the behavior stops. The old Classic `supervisorStrategy` (OneForOneStrategy) is
  // dropped — TNHC spawns no child workers (it dispatches GetTrieNodes directly to networkPeerManager), so it
  // supervised nothing (constraint #10).
  def start(): Behavior[Command] =
    log.info(s"TrieNodeHealingCoordinator starting (concurrency=$concurrency)")
    // spec 004 T020/T019: surface the decoupling mode once at start, and seed the walk-root / serve-root gauges.
    // serveRoot == stateRoot here (T008 init), so until the controller pushes a HealingServeRootRefresh the fetch
    // stays on the walk root (coupled) even when the feature is on.
    if decoupledHealServeRoot then
      log.info(
        s"[HEAL-SERVE-ROOT] Decoupled heal serve-root ENABLED — completeness walk pinned to walk root " +
          s"${Hex.toHexString(stateRoot.take(4).toArray)}; missing nodes fetched against an advancing serve root " +
          s"(content-hash-verified before store). max-attempts-no-refresh=$decoupledHealMaxAttemptsNoRefresh"
      )
    else log.info("[HEAL-SERVE-ROOT] Decoupling disabled — using single-root heal (fetch uses the walk root)")
    SNAPSyncMetrics.setHealingDecoupledEngaged(decoupledHealServeRoot)
    SNAPSyncMetrics.setHealingWalkRoot(shortRootLabel(stateRoot))
    SNAPSyncMetrics.setHealingServeRoot(shortRootLabel(serveRoot))
    timers.startTimerWithFixedDelay(HealingStagnationCheck, 2.minutes)
    active()

  /** Publish whether the heal is currently a purely local walk (see `walkLocalOnly`) after every message. */
  private def publishWalkLocality(): Unit =
    walkLocalOnly.foreach(_.set(verificationBFSRunning && isComplete))

  def active(): Behavior[Command] =
    org.apache.pekko.actor.typed.scaladsl.Behaviors.intercept[Command, Command](() =>
      new org.apache.pekko.actor.typed.BehaviorInterceptor[Command, Command]():
        override def aroundReceive(
            ctx: org.apache.pekko.actor.typed.TypedActorContext[Command],
            msg: Command,
            target: org.apache.pekko.actor.typed.BehaviorInterceptor.ReceiveTarget[Command]
        ): Behavior[Command] =
          val next = target(ctx, msg)
          // A stopping coordinator (e.g. HealingForceComplete) must not leave `true` behind for a dead actor.
          if org.apache.pekko.actor.typed.Behavior.isAlive(next) then publishWalkLocality()
          else walkLocalOnly.foreach(_.set(false))
          next

        override def aroundSignal(
            ctx: org.apache.pekko.actor.typed.TypedActorContext[Command],
            signal: org.apache.pekko.actor.typed.Signal,
            target: org.apache.pekko.actor.typed.BehaviorInterceptor.SignalTarget[Command]
        ): Behavior[Command] =
          if signal == org.apache.pekko.actor.typed.PostStop then walkLocalOnly.foreach(_.set(false))
          target(ctx, signal)
    )(activeBehavior())

  private def activeBehavior(): Behavior[Command] = Behaviors.receiveMessage[Command] {
    case StartTrieNodeHealing(root) =>
      val emptyPath = ByteString(com.chipprbots.ethereum.mpt.HexPrefix.encode(Array.empty[Byte], isLeaf = false))
      if isNodeInStorage(root, Seq(emptyPath)) then
        // ARCH-HEAL-RESTART: Root already healed — crash/restart mid-healing detected.
        // Rebuild the frontier by traversing locally-stored trie nodes instead of re-requesting
        // known nodes from the network (go-ethereum trie.Sync.Missing() analogue).
        // Recovery cost: O(healed_nodes × local_read) vs O(healed_nodes × network_rtt).
        log.info(
          s"[HEAL-RESTART] Root ${Hex.toHexString(root.take(8).toArray)} already in local storage " +
            s"— rebuilding frontier via local BFS in batches of $FrontierBatchSize " +
            s"(crash recovery, go-ethereum trie.Sync.Missing() trie traversal pattern)"
        )
        val selfRef = self
        val ec = healingWriterEc
        val frontierStore = healingFrontierStorage
        import scala.concurrent.Future
        import scala.util.control.NonFatal
        Future {
          // Layer 2: if a persisted frontier exists, resume from it (O(frontier)) and skip the full-state walk.
          // Empty / absent / unreadable ⇒ fail-safe fallback to the provably-complete BFS (logged loudly).
          val resumed: Option[Seq[HealingEntry]] = frontierStore.flatMap { store =>
            try
              val loaded = store.loadAll().map { case (h, ps) => HealingEntry(pathset = ps, hash = h) }
              if store.isComplete && loaded.nonEmpty then
                // COMPLETE snapshot (the prior rebuild BFS finished) — safe to skip the full-state walk.
                asyncLog.info(
                  s"[HEAL-RESTART] Resumed ${loaded.size} frontier entries from a complete persisted snapshot — skipping full-state BFS"
                )
                Some(loaded)
              else if store.isComplete then
                // COMPLETE snapshot with an EMPTY frontier: the prior rebuild ran to completion AND every
                // node it discovered was healed (entries are unpersisted as they heal). This is the
                // best-possible restart state, but the old `loaded.nonEmpty && isComplete` gate fell
                // through to a ~119M-node full re-walk (~24-36h on ETC mainnet) that can discover
                // nothing new. Nothing to re-queue — skip the rebuild and run the verification pass,
                // which still gates StateHealingComplete. (A pivot refresh clears the marker, so the
                // marker being set implies the current root is the one the completed walk covered.)
                asyncLog.info(
                  "[HEAL-RESTART] Complete persisted snapshot with empty frontier (all discovered nodes " +
                    "healed) — skipping full-state walk, running verification pass directly"
                )
                Some(Seq.empty)
              else if loaded.nonEmpty then
                // PARTIAL frontier: the prior rebuild BFS was interrupted before completion, so the un-walked
                // region's missing nodes are not yet recorded. Skipping the BFS would silently leave gaps —
                // re-run the full walk (it re-persists idempotently and sets the marker on completion).
                asyncLog.warn(
                  s"[HEAL-RESTART] Persisted frontier has ${loaded.size} entries but no completeness marker " +
                    s"(prior rebuild interrupted) — re-running full-state BFS to avoid skipping un-walked nodes"
                )
                None
              else
                asyncLog.info("[HEAL-RESTART] Persisted frontier empty — falling back to full-state BFS")
                None
            catch
              case NonFatal(e) =>
                asyncLog.error("[HEAL-RESTART] Failed to load persisted frontier — falling back to full-state BFS", e)
                None
          }
          // Route the resume decision back to the actor thread: the start*BFS launchers mutate verificationBFSRunning
          // and spawn more Futures, which must happen on the actor dispatcher (Typed). FrontierRebuilt is already a
          // message, so the entries arm sends directly.
          resumed match
            case Some(entries) if entries.nonEmpty =>
              selfRef ! FrontierRebuilt(entries)
            case Some(_) =>
              // Complete-and-empty: rebuild already proven done; verification alone decides completion.
              selfRef ! RestartResumeVerification(root, emptyPath)
            case None =>
              // Mark the persisted frontier authoritative when the full walk is done (all workers complete).
              selfRef ! RestartFullRebuild(root, emptyPath)
        }(ec)
      else if movingRootDeltaHeal then
        // spec 009 T006/C2 (Moving-Root Delta Heal): the heal root node's OWN bytes are absent locally, but the root
        // IS fetchable — GetTrieNodes(rootHash=stateRoot, paths=[[emptyPath]]) returns S's root node, whose keccak ==
        // stateRoot, so the content-hash gate (byte-untouched) accepts it. The local mosaic is only a content-addressed
        // CACHE that lets discovery prune already-present subtrees. SEED the root as a frontier task (empty-path) and
        // fetch it against the single served root `stateRoot` (T005), EXACTLY as the HealingPivotRefreshed re-peg seed
        // below already does for an absent re-pegged root — do NOT hand off to lazy healing. Persisted nodes are NOT
        // discarded (this only ADDS the root task). `discoverMissingChildren` then drives the top-down delta from here.
        // Start-of-heal and re-peg therefore seed an absent root IDENTICALLY (one moving-root mechanism).
        if !pendingHashSet.contains(root) && !isNodeInStorage(root, Seq(emptyPath)) then
          val seedEntry = HealingEntry(Seq(emptyPath), root)
          pendingTasks += seedEntry
          pendingHashSet += root
          persistFrontier(Seq(seedEntry)) // Layer 2: the absent heal root is a new frontier entry (mirror only)
          log.info(
            s"[HEAL] Root ${Hex.toHexString(root.take(8).toArray)} absent locally — seeding it as a frontier task " +
              s"and fetching against the single served root (spec 009 moving-root delta heal); discovery drives the " +
              s"top-down delta from the root. NOT handing off to lazy healing."
          )
          tryRedispatchPendingTasks()
        else
          // Root became present (or already seeded) between the outer check and here — nothing to seed; let normal
          // discovery / completion proceed (mirrors HealingPivotRefreshed's already-present branch).
          log.info(
            s"[HEAL] Root ${Hex.toHexString(root.take(8).toArray)} already seeded or present — no re-seed needed."
          )
          tryRedispatchPendingTasks()
      else
        // COMPLEMENTARY GUARD (root-cause w98gfx4wn / PR #1371 seed-guard): the walk-root node's OWN bytes are absent
        // from local storage.
        //
        // Seeding the root here is FUTILE and the source of every observed "exactly 1 node, healed=0" heal stall:
        //   - A root node is content-retrievable ONLY at the empty path of ITS OWN trie. Under deferred merkleization
        //     (or after a force-completed download / aged pivot) the account/storage trie was never built locally, so
        //     the pivot's state root is absent from the hash-keyed CF.
        //   - The fetch targets an ADVANCING serve root (decoupled heal) or an aged walk root outside peers' ~128-block
        //     serve window, so every reply is some OTHER root's node → the content-hash gate (keccak == task hash)
        //     correctly drops it → healed stays 0.
        //   - discoverMissingChildren never re-enqueues a root, so pendingTasks stays at exactly 1 forever.
        //
        // A root cannot be reconstructed from nothing (it has no parent to walk down from), so there is no in-place
        // heal that can make progress. Instead, SIGNAL the controller that this root is unservable so it takes the
        // lazy-heal handoff (completeSnapSync()) — the SAME path SNAPSyncController.shouldSkipHealingAfterDownloads
        // uses. The missing state is then filled on-demand via GetTrieNodes during block execution (BlockImporter /
        // StateNodeFetcher, which walk the LOCAL trie from the real parent state root and so have the root context the
        // heal lacks). We do NOT seed and do NOT touch the content-hash gate.
        //
        // CRITICAL: firing the guard HERE (at the seed) — not at the SNAPSyncController routing decision — is what
        // makes it cover EVERY entry into healing, including the BootstrapComplete RESTART handlers that call
        // startStateHealing() directly and bypass shouldSkipHealingAfterDownloads. The root-PRESENT branch above is
        // unchanged: a servable root still heals normally (rebuild/seed the frontier and heal missing descendants).
        log.warn(
          s"[HEAL] Root ${Hex.toHexString(root.take(8).toArray)} not in storage and unservable — a heal cannot " +
            s"reconstruct a walk root from nothing. Signalling SNAPSyncController to hand off to lazy on-demand " +
            s"healing (completeSnapSync); missing nodes fetched via GetTrieNodes during block execution."
        )
        snapSyncController ! SNAPSyncController.HealingRootUnservable(root)

      Behaviors.same

    case FrontierRebuilt(entries) =>
      if entries.isEmpty then
        log.warn(
          "[HEAL-FRONTIER] Empty frontier batch received — trie may already be fully healed or storage is corrupt"
        )
      else log.info(s"[HEAL-FRONTIER] ${entries.size} missing nodes identified — queuing for healing")
      queueNodes(entries.map(e => (e.pathset, e.hash)))
      lastHealedAtMs = System.currentTimeMillis()
      tryRedispatchPendingTasks()

      Behaviors.same

    case FrontierRebuildComplete(missingEmitted, walkRoot) =>
      // The full-state rebuild BFS walked the entire trie; every still-missing node is now persisted.
      // Mark the snapshot complete so a future restart may resume it instead of re-walking (Layer 2).
      verificationBFSRunning = false // rebuild walk finished — release the single-flight gate
      // spec 002/005 marker: gated on frontier persistence (default off). With it off, `FrontierRebuildComplete` is
      // never reached via the resume path anyway (that path requires `frontierStore` defined), but guard defensively
      // so the snapshot marker is only ever written when persistence is enabled (FR-005 parity; the marker also gates
      // the spec-003 scoped path, which must stay dark by default).
      if frontierPersistenceEnabled then
        healingFrontierStorage.foreach { store =>
          store.markComplete()
          log.info("[HEAL-RESTART] Full-state rebuild complete — persisted frontier marked as a complete snapshot")
        }
      // spec 006 C2/D2/FR-001/FR-002/FR-003: on a genuinely CLEAN rebuild, declare completion after this
      // single walk instead of waiting for the dead-pulse watchdog to force-start a redundant second
      // (verification) walk over the identical trie (~16-20h on ETC mainnet). The rebuild walk fully
      // recurses into account-leaf storageRoots (the BUG-1 gap the verification walk was built for), so on
      // a clean rebuild it IS the full traversal the verification would re-run. Declare early IFF every
      // conjunct holds — each excludes a specific false-completion path (research D2 + adversarial verdict):
      //   missingEmitted == 0    — the deciding walk found nothing missing (no gap discovered).
      //   totalNodesHealed == 0  — nothing healed during the walk (excludes heal-during-walk mutation;
      //                            conservative `== 0`, D5; cumulative counter preserved across pivots).
      //   isComplete             — pendingTasks.isEmpty && activeRequests.isEmpty: no outstanding frontier
      //                            or in-flight request (excludes outstanding work).
      //   !flushing              — no async raw-node write/flush in flight (mirrors the HealingCheckCompletion
      //                            gate's flush guard; never mark complete ahead of durable state).
      //   !trieWalkInProgress    — the controller's interleave trie walk is not running. Defense-in-depth: makes
      //                            the early precondition EXACTLY match the HealingCheckCompletion gate, so
      //                            guard-passes ⇒ gate-passes (no benign "guard fires, gate blocks, watchdog
      //                            suppressed" interleave case on the re-heal path). First-heal: always false.
      //   walkRoot == stateRoot  — LOAD-BEARING (D4/FR-005): a HealingPivotRefreshed to a new root does NOT
      //                            cancel this in-flight walk, so a stale completion can land against a new
      //                            stateRoot; this excludes that stale-root completion. Explicit guard — does
      //                            NOT rely on the incidental "pivot re-seeds pendingTasks" property.
      // Route via `self ! HealingCheckCompletion` (NOT a direct StateHealingComplete) so the marker write
      // and the StateHealingComplete send flow through the single existing chokepoint (D1, byte-parity).
      // verificationPassComplete = true also suppresses the dead-pulse watchdog (no walk #2, FR-006). The
      // else (do nothing new) is byte-identical to today — the node idles and the watchdog runs walk #2.
      if missingEmitted == 0 && totalNodesHealed == 0 && isComplete && !flushing && !trieWalkInProgress &&
        walkRoot == stateRoot
      then
        verificationPassComplete = true
        self ! HealingCheckCompletion
        log.info(
          "[HEAL-RESTART] Clean rebuild (0 missing, 0 healed, current root) — declaring completion after one " +
            "walk via HealingCheckCompletion; skipping the redundant verification walk (spec 006)"
        )

      // Path scheme: a heal response can land while this walk is still flagged running, in which case its
      // HealingCheckCompletion was skipped (`!verificationBFSRunning`) and nothing else re-triggers it until the
      // dead-pulse watchdog (minutes). Re-evaluate now that the flag is clear. Hash scheme: unchanged.
      if storageScheme == StorageScheme.Path && missingEmitted > 0 then self ! HealingCheckCompletion

      Behaviors.same

    case FrontierWalkFailed =>
      verificationBFSRunning = false
      trieWalkInProgress = false
      log.warn(
        "[HEAL-BFS] Frontier walk failed — flags reset; verification will be retried by " +
          "HealingCheckCompletion or the dead-pulse watchdog (no completion marker was set)"
      )

      Behaviors.same

    case QueueMissingNodes(nodes) =>
      log.info(s"Queuing ${nodes.size} missing nodes for healing")
      queueNodes(nodes)
      // Immediately dispatch to any known available peers
      tryRedispatchPendingTasks()

      Behaviors.same

    case HealingPeerAvailable(peer) =>
      // NB-7: Skip peers already in statelessPeers — they returned empty TrieNodes for this root and
      // will do so again until the pivot refreshes. Re-adding them on every 1s scheduler tick wastes
      // active request slots and creates a rapid [SNAP/1 enabled] + [stateless] log cycle.
      if statelessPeers.contains(peer.id.value) then
        log.debug(
          "Ignoring HealingPeerAvailable for stateless peer {} — will re-admit on next pivot refresh",
          peer.id.value.take(8)
        )
      else
        // Evict stale entry for same physical node (reconnection creates new PeerId)
        knownAvailablePeers.filterInPlace(_.remoteAddress != peer.remoteAddress)
        knownAvailablePeers += peer
        dispatchIfPossible(peer)

      Behaviors.same

    case HealingPeerUnavailable(peerId) =>
      // Peer disconnected — remove from available set and immediately re-queue its in-flight
      // tasks so other peers can pick them up without waiting for the 30s request timeout.
      // Mirrors AccountRangeCoordinator.PeerUnavailable (go-ethereum revertRequests pattern).
      knownAvailablePeers.filterInPlace(_.id.value != peerId)
      val inFlight = activeRequests.filter { case (_, req) => req.peer.id.value == peerId }.keys.toSeq
      if inFlight.nonEmpty then
        log.debug(s"Peer $peerId disconnected — re-queuing ${inFlight.size} in-flight healing request(s)")
        inFlight.foreach { reqId =>
          activeRequests.remove(reqId).foreach { req =>
            requestTracker.completeRequest(reqId, 0)
            req.tasks.foreach { task =>
              if !pendingHashSet.contains(task.hash) then
                pendingHashSet += task.hash
                pendingTasks += task
            }
          }
        }
      tryRedispatchPendingTasks()

      Behaviors.same

    case UpdateMaxInFlightPerPeer(newLimit) =>
      // Floor the per-peer budget at MinInFlightPerPeer so a single slow peer can never throttle the
      // whole residual dispatch to one outstanding request. The controller pushes 1 by default
      // (healing-max-inflight-per-peer), which serialised the residual onto whichever peer happened to
      // hold the lone slot; with a 2-peer-eligible residual that is enough to stall. Flooring keeps at
      // least MinInFlightPerPeer requests pipelined per peer so the residual fans out across the pool.
      val floored = newLimit.max(TrieNodeHealingCoordinator.MinInFlightPerPeer)
      if floored != newLimit then
        log.info(s"Healing per-peer budget: $maxInFlightPerPeer -> $floored (floored from requested $newLimit)")
      else log.info(s"Healing per-peer budget: $maxInFlightPerPeer -> $floored")
      maxInFlightPerPeer = floored
      if floored > 0 then tryRedispatchPendingTasks()

      Behaviors.same

    case HealingForceComplete =>
      // spec 004 T016/C5/SC-002: under decoupling, HealingForceComplete must NEVER declare completion while any
      // task is unsatisfied. Its original purpose — abandon pending tasks because the pivot aged beyond the SNAP
      // serve window (Besu reloadTrieHeal) — is exactly what decoupling makes obsolete: the serve root advances
      // independently, so an aged serve window is no longer a reason to abandon the walk root's missing nodes.
      // Abandoning here would send StateHealingComplete with a real gap (consensus-unsafe). Refuse: keep the
      // frontier, keep healing; the serve-root refresh path supplies a servable root. (Flag off ⇒ unchanged.)
      if decoupledHealServeRoot && !isComplete then
        log.warn(
          s"[HEAL-FORCE-COMPLETE] IGNORED under decoupled-heal-serve-root: ${pendingTasks.size} pending + " +
            s"${activeRequests.size} in-flight task(s) still unsatisfied. NOT declaring completion (SC-002) — " +
            s"healing continues against the walk root; serve-root refresh supplies a servable fetch root."
        )
        Behaviors.same
      else
        log.warn(
          s"[HEAL-FORCE-COMPLETE] Pivot advanced beyond SNAP serve window — " +
            s"clearing ${pendingTasks.size} pending tasks + ${activeRequests.size} in-flight. " +
            s"Signaling completion with $totalNodesHealed healed nodes."
        )
        clearPersistedFrontier() // Layer 2: healing abandoned — drop the persisted frontier so a restart won't resume it
        activeRequests.keys.foreach(requestTracker.completeRequest(_, 0))
        activeRequests.clear()
        pendingTasks.clear()
        pendingHashSet.clear()
        pathAliases.clear()
        clearHealedPathsSet() // spec 003 C1: abandonment — drop the scoped-verification scope (hygiene)
        // Path scheme: the controller treats StateHealingComplete as a VERIFIED clean walk (it runs no walk of its own),
        // and a force-complete has not verified anything — signal abandonment so it hands off to lazy healing instead.
        // Hash scheme: unchanged (the controller's own trie walk verifies).
        storageScheme match
          case StorageScheme.Hash => snapSyncController ! SNAPSyncController.StateHealingComplete
          case StorageScheme.Path => snapSyncController ! SNAPSyncController.StateHealingAbandoned
        // Classic `context.stop(self)` → Typed: return a stopped behavior so the actor terminates after this message.
        Behaviors.stopped

    case HealingPivotRefreshed(newStateRoot) =>
      // FR-003: a same-root refresh is a no-op. ByteString `==` is full 32-byte value equality (NOT the
      // 4-byte log prefix). Returning here preserves a valid completeness marker and the persisted
      // frontier — the old body would clear both via clearPersistedFrontier(), wiping a good snapshot.
      if newStateRoot == stateRoot then
        log.info(
          s"[HEAL] Pivot refresh to same root ${Hex.toHexString(stateRoot.take(4).toArray)} — " +
            s"no-op, preserving completeness marker and frontier"
        )
      else
        val oldRoot = Hex.toHexString(stateRoot.take(4).toArray)
        val newRootHex = Hex.toHexString(newStateRoot.take(4).toArray)
        log.info(
          s"Healing pivot refreshed: $oldRoot -> $newRootHex. " +
            s"Clearing ${pendingTasks.size} pending tasks, ${statelessPeers.size} stateless peers."
        )
        stateRoot = newStateRoot
        flushRawNodesSync() // Flush any buffered nodes before clearing state
        // spec 009 T010/C4 — RE-PEG-RETAINS-NODES INVARIANT (consensus-load-bearing): a re-peg MUST NOT delete any
        // persisted trie node. Content-addressed verified nodes (keccak-keyed) carry over unchanged under the new root
        // (~99.9% shared), so every node healed against the old root stays valid under `newStateRoot` and the
        // post-re-peg delta only SHRINKS. The ONLY state cleared on this path is in-memory frontier (pendingTasks /
        // pendingHashSet / activeRequests, below) plus the OPTIONAL frontier-mirror CF via clearPersistedFrontier(),
        // which operates solely on `healingFrontierStorage` (CF 'g' — frontier mirror + completeness/subtree records)
        // and NEVER touches the trie-node store `mptStorage`. If a future edit makes a re-peg drop trie nodes it is a
        // consensus bug (a referenced node could go missing under the new root → false completion / import fault).
        clearPersistedFrontier() // Layer 2: old-root frontier is stale after refresh — reseed (below) repopulates it
        clearHealedPathsSet() // spec 003 C1/F5: old-root healed paths are stale — clear before next gate
        pendingTasks.clear() // Will be re-populated by root reseed + inline discovery / trie walk from controller
        pendingHashSet.clear()
        pathAliases.clear()
        statelessPeers.clear()
        emptyResponseStrikes.clear() // fresh slate on the new root (mirrors statelessPeers clear)
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
        val pivotReseedPath =
          ByteString(com.chipprbots.ethereum.mpt.HexPrefix.encode(Array.empty[Byte], isLeaf = false))
        if !pendingHashSet.contains(newStateRoot) && !isNodeInStorage(newStateRoot, Seq(pivotReseedPath)) then
          val reseedEntry = HealingEntry(Seq(pivotReseedPath), newStateRoot)
          pendingTasks += reseedEntry
          pendingHashSet += newStateRoot
          persistFrontier(Seq(reseedEntry)) // Layer 2: the new pivot root is a new frontier entry
          log.info(
            s"[HEAL] Re-seeded with new root ${Hex.toHexString(newStateRoot.take(4).toArray)} " +
              s"for inline discovery of pivot delta"
          )
        else
        // FIX-BUG2-PIVOT: Root already in local storage — run a verification BFS to discover
        // any missing children instead of dead-looping with zero pending tasks.
        // Without this, walkRunning stays false and pending stays 0 → 316-pulse dead loop (RUN10).
        // discoverMissingChildren skips locally-held storage roots without recursing into their
        // children, so the new pivot root may be local yet have gaps in storage sub-tries.
        if trieWalkInProgress || verificationBFSRunning then
          // A walk is already running on the SHARED bfsQueue — rebuildFrontierBFS clears the queue
          // on entry, so starting a second walk here would corrupt the running one. The pivot's
          // verificationPassComplete=false (set above) guarantees HealingCheckCompletion starts a
          // fresh verification once the current walk's flags clear.
          log.info(
            s"[HEAL] New root ${Hex.toHexString(newStateRoot.take(4).toArray)} already in storage, " +
              s"but a frontier walk is running — verification deferred until it completes"
          )
        else
          log.info(
            s"[HEAL] New root ${Hex.toHexString(newStateRoot.take(4).toArray)} already in storage " +
              s"— starting verification BFS to find missing children"
          )
          startVerificationBFS(newStateRoot, pivotReseedPath)

      Behaviors.same

    case HealingServeRootRefresh(newServeRoot) =>
      // spec 004 T009/C2: advance the SERVE root ONLY. This is the narrow, side-effect-free counterpart to
      // HealingPivotRefreshed: it MUST NOT touch the walk root (`stateRoot`), the frontier (`pendingTasks` /
      // persisted frontier), `verificationPassComplete`, or re-seed the walk. Completeness stays anchored to the
      // unchanged walk root (FR-001), so byte-for-byte completion parity with the coupled path is preserved by
      // construction. No-op when the feature is disabled (the fetch would ignore `serveRoot` anyway, but skipping
      // keeps the observability/counter state inert so the OFF path is byte-identical to today, SC-006).
      if movingRootDeltaHeal then
        // spec 009 T014/FR-010: under moving-root delta heal the serve root IS the walk root (the fetch collapses to
        // `stateRoot` in requestNextBatch, T005), so the spec-004 serve-root machinery is SUPERSEDED. The controller
        // re-pegs via HealingPivotRefreshed and stops requesting serve roots (H-S6/H-S2), but a late in-flight
        // HealingServeRootRefresh could still arrive — treat it as a documented no-op (serveRoot / serveRootRefreshCount
        // stay inert). Flag OFF: byte-identical to the spec-004 path below (kept one release for A/B, FR-007).
        log.debug("[HEAL] HealingServeRootRefresh ignored — single moving root (spec 009)")
      else if !decoupledHealServeRoot then
        log.debug("[HEAL-SERVE-ROOT] HealingServeRootRefresh ignored — decoupled-heal-serve-root disabled")
      else if newServeRoot.isEmpty || newServeRoot == serveRoot then
        // T011 U2: never adopt an empty/zero serve root; a same-root refresh is a no-op (no counter churn).
        log.debug(
          s"[HEAL-SERVE-ROOT] No-op serve-root refresh (empty=${newServeRoot.isEmpty}, " +
            s"same=${newServeRoot == serveRoot})"
        )
      else
        val oldServe = Hex.toHexString(serveRoot.take(4).toArray)
        val newServe = Hex.toHexString(newServeRoot.take(4).toArray)
        serveRoot = newServeRoot
        serveRootRefreshCount += 1
        // T015 / FR-006: a serve-root advance is the legitimate retry trigger — clear the per-task attempt
        // counters so a node that was unservable under the old serve root gets a fresh budget under the new one.
        healAttempts.clear()
        // T020: engagement log (old -> new serve root). The walk root is logged for contrast so an operator can
        // see the two roots diverge. blocks-behind-head is not known here (the controller picks the target);
        // the controller's own T011 log carries it.
        log.info(
          s"[HEAL-SERVE-ROOT] Serve root advanced $oldServe -> $newServe " +
            s"(walk root held at ${Hex.toHexString(stateRoot.take(4).toArray)}, refresh #$serveRootRefreshCount). " +
            s"Walk/frontier/completeness untouched."
        )
        SNAPSyncMetrics.setHealingServeRoot(shortRootLabel(serveRoot))
        SNAPSyncMetrics.setHealingUnservableTasks(0L) // counters just cleared
        // Nodes still pending may now be servable by the new serve root — nudge dispatch (does not re-seed).
        tryRedispatchPendingTasks()

      Behaviors.same

    case HealingResumeDispatch =>
      // Controller declined to roll the pivot in response to our HealingStagnated (heal-hold-pivot-on-stagnation).
      // The held root stays valid and its missing nodes are still ~99.9% servable by current peers (content-
      // addressed GetTrieNodes by hash). Clear the pivotRefreshRequested latch the stagnation path set when it
      // fired HealingStagnated, give a fresh stagnation window, and resume dispatching the EXISTING pending tasks.
      // NOTHING is cleared (root, pendingTasks, frontier, verificationPassComplete all preserved) — that is the
      // whole point: a slow-but-servable verification pass must survive so it can converge against one stable root.
      if pivotRefreshRequested then
        log.info(
          s"[HEAL] Resume dispatch on held root ${Hex.toHexString(stateRoot.take(4).toArray)} " +
            s"(stagnation hold — pivot NOT rolled). pending=${pendingTasks.size} peers=${knownAvailablePeers.size}"
        )
        pivotRefreshRequested = false
        // Reset only the re-fire guards so the next genuine stagnation can still escalate; we are NOT
        // claiming progress was made (totalNodesHealed/lastPulseHealedCount untouched).
        consecutiveStagnations = 0
        consecutiveIdleChecks = 0
        lastHealedAtMs = System.currentTimeMillis() // give the held root a fresh healingStagnationTimeoutMs window
        tryRedispatchPendingTasks()
      else log.debug("[HEAL] HealingResumeDispatch with no pending pivot-refresh latch — ignoring")

      Behaviors.same

    case TrieNodesResponseMsg(response) =>
      handleResponse(response)

      Behaviors.same

    case FlushComplete(count) =>
      flushing = false
      log.info(s"Async flush complete: $count healed nodes written to disk (total: $totalNodesHealed)")
      // Check if buffer filled up again during the flush
      if rawNodeBuffer.size >= rawFlushThreshold then flushRawNodesAsync()
      self ! HealingCheckCompletion

      Behaviors.same

    case HealingTaskComplete(requestId, result) =>
      result match
        case Right(count) =>
          totalNodesHealed += count
          log.info(s"Healing task completed: $count nodes")
          self ! HealingCheckCompletion
        case Left(error) =>
          log.warn(s"Healing task failed: $error")

      Behaviors.same

    case HealingCheckCompletion =>
      if isComplete && !flushing && !trieWalkInProgress && !verificationBFSRunning then
        // FIX-BUG1-VERIFY: gate: skip verification when no inline healing was done.
        // If totalNodesHealed == 0 the coordinator was never given nodes to heal (idle case) OR
        // all nodes were already in local storage — either way the trie is complete from our
        // perspective. The BUG 2 pivot-reseed path (root held locally) is handled separately by
        // HealingPivotRefreshed calling startVerificationBFS directly, not through this gate.
        if verificationPassComplete || totalNodesHealed == 0 then
          flushRawNodesSync()
          log.info(s"Healing round complete: $totalNodesHealed total nodes healed. Notifying controller.")
          // FR-002: mark the snapshot complete ONLY on the verified-complete path — a verification BFS
          // actually walked the trie and found zero missing nodes (verificationPassComplete == true).
          // FR-004: do NOT mark on the pure `totalNodesHealed == 0` idle arm: the coordinator was never
          // given work, so the trie may be untraversed and we cannot assert completeness. Setting the
          // marker here (gated on verificationPassComplete) is the single completion chokepoint for the
          // verification path — VerificationBFSComplete routes through HealingCheckCompletion, so this is
          // equivalent to (and cleaner than) writing the marker inside VerificationBFSComplete.
          if verificationPassComplete then
            healingFrontierStorage.foreach { store =>
              // spec 005 C2/C4/T007/T012: GENUINE zero-missing closure of the whole trie from `stateRoot` — the
              // verification BFS just confirmed zero missing descendants (verificationPassComplete && isComplete) and
              // `flushRawNodesSync()` above durably committed every healed node-byte. ONLY now (subtree bytes durable)
              // is it crash-safe to record the root subtree-complete (ordinary WAL-ordered `update`). This is a real
              // closure, NOT a `visitedLru`-faked queue-drain: the gate required isComplete (no pending/active
              // frontier) AND a clean BFS pass. Recorded only under `prunedEnabled` (Hash scheme) so a Path-scheme
              // node never writes a hash-keyed record. The terminal `markComplete()` (fsync) follows last (D3 order).
              if prunedEnabled then
                store.markSubtreeComplete(stateRoot) // spec 005 root record — gated on prunedEnabled
              // spec 002 snapshot marker — gated on frontier persistence (default off). MUST stay separate from the
              // spec-005 root record above: with persistence off, the marker is never written, so the spec-003 scoped
              // path (which keys off `store.isComplete`) stays dark and behavior is byte-for-byte pre-spec-005 (FR-005).
              if frontierPersistenceEnabled then
                store.markComplete()
                log.info(
                  "[HEAL-RESTART] Verification BFS complete — persisted frontier marked as a complete snapshot"
                )
            }
          snapSyncController ! SNAPSyncController.StateHealingComplete
          clearHealedPathsSet() // spec 003 C1: round closed — next round starts with a fresh scope
        else
          // Inline tasks done with actual healing work — verify before declaring completion to catch
          // storage sub-trie gaps that discoverMissingChildren silently skips when the storage root is
          // already in storage. Analogous to go-ethereum's trie.Sync.Missing() trie traversal.
          //
          // spec 003 C4/FR-004/FR-005/FR-009/FR-011: scope the verification to ONLY the healed subtrees
          // when full-trie coverage is durably proven and a valid in-bound same-root healed scope exists;
          // otherwise fall back to the UNCHANGED full-root verification. The predicate is pure, evaluated
          // at gate time (live reads), and all five operands are cheap local reads.
          val useScoped =
            scopedHealVerification && // F1: scoping not disabled
              healingFrontierStorage.exists(_.isComplete) && // F2/F6: full-coverage precondition proven
              healedPathsThisRound.nonEmpty && // F3: scope present (not restart-lost / pre-first-heal)
              !healedPathsOverflowed && // F4: within the configured bound (FR-011)
              healedPathsRoot == stateRoot // F5: same root the scope was healed against (FR-009)

          if useScoped then startScopedVerification(healedPathsThisRound.values.toSeq)
          else
            // spec 003 C5/T017 (US3 AS2): when the fallback engaged specifically because scoping is
            // disabled by config, surface it once per round so an operator can see the conservative path
            // is in effect (distinct from the precondition-not-proven / over-bound / root-changed cases).
            if !scopedHealVerification then
              log.info("[HEAL-VERIFY-SCOPED] scoped verification disabled by config — using full-root verification")
            val emptyPath =
              ByteString(com.chipprbots.ethereum.mpt.HexPrefix.encode(Array.empty[Byte], isLeaf = false))
            log.info(
              s"[HEAL-VERIFY] All inline tasks done ($totalNodesHealed healed). " +
                s"Starting verification BFS on locally-held trie to catch storage sub-trie gaps..."
            )
            startVerificationBFS(stateRoot, emptyPath)

      Behaviors.same

    case VerificationBFSComplete(walkRoot, _) if walkRoot != stateRoot =>
      // STALE: the walk traversed a root the heal has since re-pegged away from. Its result proves nothing about the
      // current root, so it must NOT set verificationPassComplete. Release the single-flight gate and the per-run
      // observability flags, then verify the CURRENT root explicitly. This cannot go through HealingCheckCompletion's
      // `|| totalNodesHealed == 0` arm, which would declare completion with no walk at all.
      verificationBFSRunning = false
      consecutiveStaleDiscards += 1
      log.warn(
        s"[HEAL-VERIFY] Discarding stale verification result (#$consecutiveStaleDiscards): walk of root ${Hex.toHexString(walkRoot.take(4).toArray)} " +
          s"finished after re-peg to ${Hex.toHexString(stateRoot.take(4).toArray)} — re-verifying the current root"
      )
      if consecutiveStaleDiscards > MaxConsecutiveStaleDiscards then
        // The root keeps moving faster than a verification walk can finish: this heal cannot converge to a verified
        // root. Never declare clean on a stale root; hand off to lazy on-demand healing (fail-safe, anchor-guarded),
        // exactly as a force-complete does.
        log.error(
          s"[HEAL-VERIFY] $consecutiveStaleDiscards consecutive verification walks were superseded by a re-peg — " +
            "abandoning verified healing and handing off to lazy on-demand healing"
        )
        snapSyncController ! SNAPSyncController.StateHealingAbandoned
      else reverifyCurrentRoot()
      Behaviors.same

    case VerificationBFSComplete(_, missingEmitted) if missingEmitted > 0 =>
      consecutiveStaleDiscards = 0
      // DIRTY pass: the walk found (and queued) missing nodes. They may all have been healed by now, so `isComplete`
      // can already be true, but the walk did not descend below any node that was missing when it reached it, and
      // inline discovery only checks a healed node's direct children for presence. A present child whose own subtree
      // has a hole (the storage-force-complete mosaic) is therefore unverified. Only a pass that finds NOTHING proves
      // completeness (go-ethereum: loop until the sync scheduler has nothing pending, then re-walk).
      verificationBFSRunning = false
      log.info(
        s"[HEAL-VERIFY] Verification pass found $missingEmitted missing nodes — not a clean pass; " +
          s"re-verifying once pending=${pendingTasks.size} active=${activeRequests.size} drain"
      )
      if isComplete then reverifyCurrentRoot() else tryRedispatchPendingTasks()
      Behaviors.same

    case VerificationBFSComplete(_, _) =>
      verificationBFSRunning = false
      consecutiveStaleDiscards = 0
      if isComplete then
        // BFS traversed all locally-held nodes and found zero missing descendants — trie is complete.
        verificationPassComplete = true
        // spec 003 C6/T016: if this clean pass was the SCOPED path, emit the completion log + duration
        // gauge so an operator can confirm engagement and the time saved vs a full-root re-walk.
        if scopedVerificationActive then
          val elapsedMs = System.currentTimeMillis() - scopedVerificationStartMs
          log.info(
            s"[HEAL-VERIFY-SCOPED] Scoped verification complete in ${elapsedMs}ms " +
              s"over $scopedVerificationSeedCount subtrees — declaring completion"
          )
          SNAPSyncMetrics.setHealingScopedDurationMs(elapsedMs)
          scopedVerificationActive = false
        // spec 005 C8/FR-009: pruned-path completion observability — emit the savings (subtrees pruned vs nodes
        // visited) + elapsed so an operator can confirm engagement. Same single chokepoint (C5); no new completion
        // path. The actual root record (T007) is written crash-safely in HealingCheckCompletion AFTER the node-bytes
        // flush, alongside the terminal markComplete (D3/T012).
        if prunedVerificationActive then
          val prunedElapsedMs = System.currentTimeMillis() - prunedVerificationStartMs
          val pruned = prunedSubtreeCount.get()
          log.info(
            s"[HEAL-VERIFY-PRUNED] Pruned verification complete in ${prunedElapsedMs}ms — " +
              s"$pruned present subtrees pruned (descend-and-stop), zero missing found — declaring completion"
          )
          SNAPSyncMetrics.setHealingPrunedSubtrees(pruned)
          SNAPSyncMetrics.setHealingPrunedDurationMs(prunedElapsedMs)
          prunedVerificationActive = false
        log.info(
          s"[HEAL-VERIFY] Verification BFS complete — no missing nodes found. " +
            s"Trie is fully healed ($totalNodesHealed nodes). Declaring completion."
        )
        self ! HealingCheckCompletion
      else
        // BFS found missing nodes queued via FrontierRebuilt — healing needs to continue.
        log.info(
          s"[HEAL-VERIFY] Verification BFS found additional missing nodes " +
            s"(pending=${pendingTasks.size} active=${activeRequests.size}) — resuming healing."
        )
        tryRedispatchPendingTasks()

      Behaviors.same

    case WalkStateChanged(inProgress) =>
      trieWalkInProgress = inProgress

      Behaviors.same

    case HealingStagnationCheck =>
      emitHealingFrontierGauges() // refresh backlog/in-flight gauges even when idle
      // Periodic soft-exile auto-clear (mirrors the Account/Storage "fresh slate on the next round" intent,
      // but on every 2-min tick rather than only on pivot refresh): peers rotate and a peer that returned
      // empty for the current root earlier may now be in its serve window. While there is residual work and
      // peers exist but the stateless set has eaten into the eligible pool, wipe the stateless set + strikes
      // so every connected SNAP peer is retried. Pure peer-pool management — does not touch any task/store
      // state. The strike threshold keeps a genuinely useless peer out within the 2-min window; this re-arms
      // it for the next. Without this the residual heal was stuck retrying only the 2 not-yet-exiled peers.
      if pendingTasks.nonEmpty && statelessPeers.nonEmpty && knownAvailablePeers.nonEmpty && !pivotRefreshRequested then
        log.info(
          s"[HEAL] Auto-clearing ${statelessPeers.size} stateless peer(s) + ${emptyResponseStrikes.size} strike(s) " +
            s"on periodic tick — retrying all ${knownAvailablePeers.size} known peers for the residual frontier " +
            s"(pending=${pendingTasks.size})."
        )
        statelessPeers.clear()
        emptyResponseStrikes.clear()
        tryRedispatchPendingTasks(allowCooldownFloor = true) // periodic tick: anti-starvation floor permitted
      val recentHealed = totalNodesHealed - lastPulseHealedCount
      val healTotal = completedTaskCount.toLong + pendingTasks.size.toLong + activeRequests.size.toLong
      val healPct = if healTotal > 0 then ((completedTaskCount.toDouble / healTotal) * 100).toInt else 0
      // walkRunning must reflect ANY active frontier walk (inline trie walk OR the BFS
      // rebuild/verification Future) — the same OR the watchdog gates use. Rendering only
      // trieWalkInProgress reported walkRunning=false during a live BFS rebuild, which misled
      // operators (and matched the pre-fix watchdog bug that double-started walks).
      val anyWalkRunning = trieWalkInProgress || verificationBFSRunning
      log.info(
        s"[HEAL-PULSE] $healPct% (est) | healed=$totalNodesHealed (+$recentHealed last 2min) | " +
          s"pending=${pendingTasks.size} active=${activeRequests.size} peers=${knownAvailablePeers.size} | " +
          s"rate=${healRate.toInt} nodes/s walkRunning=$anyWalkRunning pivotRefreshPending=$pivotRefreshRequested"
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
      // verification BFS, no pending tasks, no active requests, and zero healing progress.
      // Primary fix is startVerificationBFS in HealingCheckCompletion and HealingPivotRefreshed;
      // this watchdog catches any residual edge case (e.g. stale state after pivot race).
      if !trieWalkInProgress && !verificationBFSRunning &&
        pendingTasks.isEmpty && activeRequests.isEmpty &&
        recentHealed == 0 && !pivotRefreshRequested && !verificationPassComplete
      then
        consecutiveDeadPulses += 1
        log.warn(
          s"[HEAL-WATCHDOG] Dead pulse $consecutiveDeadPulses/3: " +
            s"walkRunning=false verifyRunning=false pending=0 active=0 healed=0 in last 2min"
        )
        if consecutiveDeadPulses >= 3 then
          log.warn("[HEAL-WATCHDOG] 3 consecutive dead pulses — force-starting verification BFS")
          consecutiveDeadPulses = 0
          val emptyPath = ByteString(com.chipprbots.ethereum.mpt.HexPrefix.encode(Array.empty[Byte], isLeaf = false))
          startVerificationBFS(stateRoot, emptyPath)
      else if recentHealed > 0 || pendingTasks.nonEmpty || activeRequests.nonEmpty ||
        trieWalkInProgress || verificationBFSRunning
      then consecutiveDeadPulses = 0

      // BUG-S4 watchdog: if pivotRefreshRequested=true for >15 min, SNAPSyncController is stuck
      // in refreshPivotInPlace's no-peer retry loop (Path A: 30s interval, HealingPivotRefreshed
      // never sent). Safe to reset: stateRoot stays valid; stagnation re-fires if still stuck.
      if pivotRefreshRequested then
        val waitedMs = System.currentTimeMillis() - pivotRefreshRequestedAt
        if waitedMs > PivotRefreshWatchdogMs then
          log.warn(
            s"[HEAL] Pivot refresh watchdog: pivotRefreshRequested=true for ${waitedMs / 1000}s — " +
              s"SNAPSyncController refresh stalled (no-peer retry loop). Resetting and resuming dispatch."
          )
          pivotRefreshRequested = false
          tryRedispatchPendingTasks(allowCooldownFloor = true) // periodic tick: anti-starvation floor permitted

      // FIX-STAGNATION-LIMIT: Track consecutive zero-progress cycles (independent of active count).
      // After MaxConsecutiveStagnations, notify controller to restart with fresh pivot.
      // Catches the case where active > 0 but all responses are empty (stale root, ETH mainnet peers).
      if recentHealed == 0 && pendingTasks.nonEmpty && !pivotRefreshRequested then
        consecutiveStagnations += 1
        log.warn(
          s"[HEAL-STAGNATION] Zero progress in last 2min — stagnation $consecutiveStagnations/$MaxConsecutiveStagnations. " +
            s"healed=$totalNodesHealed pending=${pendingTasks.size} peers=${knownAvailablePeers.size}"
        )
        if consecutiveStagnations >= MaxConsecutiveStagnations then
          log.warn(
            s"[HEAL-STAGNATION] $MaxConsecutiveStagnations consecutive zero-progress cycles — " +
              s"notifying controller to restart healing with fresh pivot"
          )
          snapSyncController ! SNAPSyncController.HealingStagnated(totalNodesHealed.toLong, pendingTasks.size.toLong)
          consecutiveStagnations = 0
          // NB-11: Suppress further stagnation counting until the pivot refresh arrives (HealingPivotRefreshed
          // resets this flag). Prevents redundant HealingStagnated fires while bootstrap is in-flight.
          pivotRefreshRequested = true
          pivotRefreshRequestedAt = System.currentTimeMillis()
      else if recentHealed > 0 then consecutiveStagnations = 0

      if pendingTasks.nonEmpty && activeRequests.isEmpty && !pivotRefreshRequested then
        consecutiveIdleChecks += 1
        if consecutiveIdleChecks >= 5 then
          val pendingCount = pendingTasks.size
          log.warn(
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
          emptyResponseStrikes.clear()
          peerCooldownUntilMs.clear()
          consecutiveIdleChecks = 0 // Reset so we don't immediately force-complete on the next tick
          log.info(
            s"[HEAL] Stateless/cooldown peer state cleared. Attempting dispatch to ${knownAvailablePeers.size} peers."
          )
          tryRedispatchPendingTasks(allowCooldownFloor = true) // periodic tick: anti-starvation floor permitted
        else tryRedispatchPendingTasks(allowCooldownFloor = true) // periodic tick: anti-starvation floor permitted
      else consecutiveIdleChecks = 0

      Behaviors.same

    case HealingGetProgress(replyTo) =>
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
      replyTo ! stats

      Behaviors.same

    case HealingRequestTimeout(requestId) =>
      // Timeout delivered via actor mailbox (BUG-H4 fix): runs on actor thread,
      // safe to read/write activeRequests and pendingTasks.
      activeRequests.get(requestId) match
        case Some(req) => handleTimeout(requestId, req.tasks, req.peer)
        case None      => // Response already arrived — stale timeout, nothing to do
      Behaviors.same

    case RestartResumeVerification(root, rootPath) =>
      // Marshalled back from the HEAL-RESTART crash-recovery Future so the walk launch runs on the actor thread.
      startVerificationBFS(root, rootPath)
      Behaviors.same

    case RestartFullRebuild(root, rootPath) =>
      // Marshalled back from the HEAL-RESTART crash-recovery Future so the walk launch runs on the actor thread.
      // spec 006 C1/D3/FR-004: forward the walk's missing-node count (missingEmitted) and the root this walk
      // traversed (`root`, captured here) so the FrontierRebuildComplete handler can decide early completion and
      // exclude a stale completion after a pivot refresh (walkRoot == stateRoot guard).
      startFrontierBFS(
        root,
        rootPath,
        isStor = false,
        (missingEmitted: Long) => self ! FrontierRebuildComplete(missingEmitted, root)
      )
      Behaviors.same

    // Defensive: `Command` is non-sealed (cross-file constraint via Messages.scala), so the compiler cannot prove
    // exhaustiveness. No production sender emits an un-handled Command; treat any as unhandled. HealingStagnated
    // (outbound to SSC) is NOT a Command, so it can never arrive here.
    case other =>
      log.debug(s"TrieNodeHealingCoordinator received unhandled command: $other")
      Behaviors.unhandled
  }

  private def queueNodes(pathsAndHashes: Seq[(Seq[ByteString], ByteString)]): Unit =
    val entries = pathsAndHashes.collect {
      case (pathset, hash) if !pendingHashSet.contains(hash) =>
        pendingHashSet += hash
        HealingEntry(pathset = pathset, hash = hash)
    }
    if storageScheme == StorageScheme.Path then
      // Only the paths NOT selected above are aliases (the selected path is written as the task itself).
      val selected = entries.map(_.pathset).toSet
      pathsAndHashes.foreach { case (pathset, hash) =>
        if pendingHashSet.contains(hash) && !selected.contains(pathset) then
          pathAliases.getOrElseUpdate(hash, mutable.LinkedHashMap.empty).update(pathset, HealingEntry(pathset, hash))
      }
    val deduped = pathsAndHashes.size - entries.size
    pendingTasks ++= entries
    persistFrontier(entries) // Layer 2: mirror new frontier entries to the persisted CF
    val dedupStr = if deduped > 0 then s" ($deduped duplicates filtered)" else ""
    log.info(s"Queued ${entries.size} nodes for healing$dedupStr. Total pending: ${pendingTasks.size}")
    emitHealingFrontierGauges()

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
  private def updateHealThrottle(delivered: Int, elapsedMs: Long): Unit =
    // Update rate (geometric EMA — geth trienodeHealRateMeasurementImpact = 0.005)
    val elapsedSec = elapsedMs.max(1).toDouble / 1000.0
    val measured = delivered.toDouble / elapsedSec
    healRate = math.pow(1 - RateMeasurementImpact, delivered) * (healRate - measured) + measured
    healRate = healRate.max(0.0)

    // Only backpressure on unflushed buffer — pending queue being large is normal after trie walks
    healPending = rawNodeBuffer.size

    // Adjust throttle every 1 second
    val now = System.currentTimeMillis()
    if now - lastThrottleAdjustMs > 1000 then
      val oldThrottle = healThrottle
      // Throttle up only when the disk-flush thread is genuinely behind — i.e. the buffer
      // is filling toward its flush threshold. Comparing buffer fill (an absolute count)
      // against the rate-derived target was a category error: the buffer almost always
      // exceeds 2*rate, which locked healThrottle at MaxThrottle and pinned the batch at
      // batchSize/MaxThrottle paths per request. See issue #1159.
      val flushBackpressure = healPending > rawFlushThreshold * ThrottleUpFillRatio
      if flushBackpressure then healThrottle = (healThrottle * ThrottleIncrease).min(MaxThrottle)
      else healThrottle = (healThrottle / ThrottleDecrease).max(MinThrottle)
      if oldThrottle != healThrottle then
        log.debug(
          f"Healing throttle adjusted: $oldThrottle%.1f -> $healThrottle%.1f " +
            f"(rate=$healRate%.1f nodes/s, bufferFill=$healPending/$rawFlushThreshold, batch=${effectiveBatchSize})"
        )
      lastThrottleAdjustMs = now
    emitHealingFrontierGauges()

  /** Calculate effective batch size after applying throttle divisor. Returns at least 1 node per request.
    */
  private def effectiveBatchSize: Int =
    (batchSize.toDouble / healThrottle).toInt.max(1)

  private def requestNextBatch(peer: Peer): Option[BigInt] =
    if pendingTasks.isEmpty then
      log.debug("No pending healing tasks")
      None
    else if pivotRefreshRequested then None
    else if statelessPeers.contains(peer.id.value) then None
    else
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

      // spec 004 T010/C3: the ONLY behavioral fetch change. When decoupling is enabled, fetch missing nodes against
      // the advancing SERVE root (which peers can still serve); otherwise (or before a serve root is obtained,
      // `serveRoot == stateRoot` by the T008 init) fetch against the walk root, byte-identical to the coupled path.
      // GetTrieNodes is path-addressed, so the returned node is content-verified (keccak == task hash) downstream
      // before it is ever stored — see handleResponse (C4). The walk root used for completeness is unchanged.
      val request = GetTrieNodes(
        requestId = requestId,
        // spec 009 T005/C1: under `movingRootDeltaHeal` collapse the fetch root to the completeness root `stateRoot`
        // so the content-hash gate (keccak(node) == requested task hash) matches BY CONSTRUCTION — the spec-004
        // wrong-axis fix. Flag OFF: byte-identical to spec-004 (serve root when decoupled, else the walk root).
        rootHash = if movingRootDeltaHeal then stateRoot else if decoupledHealServeRoot then serveRoot else stateRoot,
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
      networkPeerManager ! NetworkPeerManagerActor.SendMessageCmd(messageSerializable, peer.id)

      log.debug(
        s"Requested ${batch.size} trie nodes from peer ${peer.id.value} " +
          s"(reqId=$requestId, responseBytes=$responseBytes, pending=${pendingTasks.size})"
      )

      Some(requestId)

  private def handleResponse(response: TrieNodes): Unit =
    val requestId = response.requestId
    val nodes = response.nodes

    log.debug(s"Received TrieNodes response: reqId=$requestId, nodes=${nodes.size}")

    activeRequests.get(requestId) match
      case None =>
        log.warn(s"No active healing request found for requestId=$requestId")
      case Some(activeReq) =>
        processActiveResponse(requestId, response, activeReq)

  private def processActiveResponse(requestId: BigInt, response: TrieNodes, activeReq: ActiveRequest): Unit =
    val nodes = response.nodes
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
      if nodeData.nonEmpty then
        keccak.reset()
        val nodeHash = ByteString(keccak.digest(nodeData.toArray))
        // ┌─ CONSENSUS-SAFETY INVARIANT (spec 004 T014/C4/FR-004 — DO NOT WEAKEN OR BYPASS) ─────────────────────┐
        // │ A node returned by ANY serve root is accepted IFF its keccak256 equals a requested task hash (the     │
        // │ walk root's expectation). GetTrieNodes is path-addressed, so a serve root may resolve a path to a     │
        // │ DIFFERENT-content node; that node fails this hash match and is dropped (never stored, never counted   │
        // │ as healed). This is the single guardrail that makes decoupled (cross-root) fetching safe — it holds   │
        // │ identically on the coupled and decoupled paths. No decoupled branch may skip it.                      │
        // └──────────────────────────────────────────────────────────────────────────────────────────────────────┘
        if taskByHash.contains(nodeHash) then
          storageScheme match
            case StorageScheme.Hash =>
              rawNodeBuffer += ((nodeHash, nodeData.toArray))
            case StorageScheme.Path =>
              // PathScheme: write directly by nibble path (no batching needed — healing is low-volume).
              // pathset = [HP-path] for account trie, [accountHash32, HP-path] for storage trie.
              pathNodeStorageOpt.foreach { pns =>
                def writeAt(task: HealingEntry): Unit =
                  val nibbles = com.chipprbots.ethereum.mpt.HexPrefix.decode(task.pathset.last.toArray)._1
                  if task.pathset.size > 1 then
                    pns.writeStorageNode(ByteString(task.pathset.head), nibbles, nodeData.toArray)
                  else pns.writeAccountNode(nibbles, nodeData.toArray)
                taskByHash.get(nodeHash).foreach(writeAt)
                // Same hash missing at other paths (deduped out of the queue): write at those paths too.
                pathAliases.remove(nodeHash).foreach(_.values.foreach(writeAt))
              }
          healedCount += 1
          totalNodesHealed += 1
          // spec 004 T019/FR-010: count a heal sourced from a serve root that differs from the walk root.
          // Observation-only; never affects completion. (Equal roots ⇒ coupled-equivalent, not counted.)
          if decoupledHealServeRoot && serveRoot != stateRoot then crossRootHealCount += 1
          // T015 / FR-006: this task is satisfied — drop its attempt counter (bounded by pendingTasks).
          healAttempts.remove(nodeHash)
          // spec 003 C1/FR-001: capture this healed node's HealingEntry as a scoped-verification seed.
          // This is the ONLY site that increments totalNodesHealed for network-served nodes, so the
          // accumulator is exactly {nodes whose bytes were written this round}. Tag the round's root on
          // first capture (F5), dedup by hash, and latch overflow at scopedHealMaxPaths (F4/FR-011).
          // spec 004 T017: healedPathsRoot is tagged with the WALK root `stateRoot` (NOT `serveRoot`), so the
          // scoped-verification F5 predicate `healedPathsRoot == stateRoot` is unaffected by decoupling.
          taskByHash.get(nodeHash).foreach { task =>
            if healedPathsThisRound.isEmpty then healedPathsRoot = stateRoot
            if !healedPathsOverflowed && !healedPathsThisRound.contains(task.hash) then
              if healedPathsThisRound.size >= scopedHealMaxPaths then healedPathsOverflowed = true
              else healedPathsThisRound.update(task.hash, task)
          }
          receivedBytes += nodeData.length
          totalBytesReceived += nodeData.length
          healedHashes += nodeHash
          // Inline child discovery — Besu/geth aligned scheduler approach.
          // Each healed node is decoded to find missing children; queue them directly
          // without waiting for a full 3h trie walk. Walk becomes validation-only.
          taskByHash.get(nodeHash).foreach(task => discoverMissingChildren(nodeData, task.pathset, nodeHash))
        else
          log.debug(
            s"Healing response node not in request set (unexpected): ${Hex.toHexString(nodeHash.take(4).toArray)}"
          )
    }

    // A healed account leaf brings an account that account-range sync never saw, so nothing else will ever fetch its
    // bytecode (the code-hash list is built from account-range responses). Hand the missing ones to the controller,
    // which fetches them before it finalises SNAP. Sent BEFORE any completion signal this actor emits afterwards
    // (same sender, ordered), so the controller always knows of them by the time it is told healing is done.
    flushHealedCodeHashes()

    // Re-queue tasks not satisfied by this response (server skipped or didn't have them).
    // Restore to dedup set so QueueMissingNodes doesn't add duplicates (BUG-H1 fix).
    tasksForRequest.foreach { task =>
      if !healedHashes.contains(task.hash) then
        pendingHashSet += task.hash
        pendingTasks += task
        // spec 004 T015/FR-006: an unsatisfied task (server skipped it / content-mismatch drop) bumps its
        // attempt counter and may surface if it stays unservable across the bounded threshold WITHOUT a
        // serve-root advance (which clears the map). This NEVER force-completes — see noteUnservableAttempt.
        noteUnservableAttempt(task.hash)
    }

    completedTaskCount += healedCount
    activeRequests.remove(requestId)
    requestTracker.completeRequest(requestId, nodes.size.max(1))

    // Update healing throttle (geth msgrate alignment)
    val elapsedMs = System.currentTimeMillis() - activeReq.sentAtMs
    updateHealThrottle(healedCount, elapsedMs)

    // Adaptive byte budget + stateless tracking
    if healedCount > 0 then
      adjustResponseBytesOnSuccess(peer, requestedBytes, BigInt(receivedBytes))
      // Successful response — clear stateless marking + strikes and reset stagnation timer.
      // Any forward progress from this peer wipes its prior strikes (mirrors recordPeerSuccess).
      statelessPeers -= peer.id.value
      emptyResponseStrikes.remove(peer.id.value)
      lastHealedAtMs = System.currentTimeMillis()
    else
      adjustResponseBytesOnFailure(peer, "empty healing response")
      recordPeerCooldown(peer, "empty healing response")
      // Soft-exile (mirrors AccountRangeCoordinator.markPeerStateless): a SINGLE empty response no
      // longer permanently exiles the peer. Strike it; only at EmptyResponseStrikeThreshold consecutive
      // empties (no intervening heal) is it confirmed stateless. A peer that returns empty now may serve
      // after the pivot rolls into its serve window — keeping it eligible across a transient empty cycle
      // is the difference between draining a 2-peer pool to zero and finishing the residual.
      if !statelessPeers.contains(peer.id.value) then
        val strikes = emptyResponseStrikes.getOrElse(peer.id.value, 0) + 1
        emptyResponseStrikes(peer.id.value) = strikes
        if strikes < EmptyResponseStrikeThreshold then
          log.info(
            s"Peer ${peer.id.value} empty-response strike $strikes/$EmptyResponseStrikeThreshold for healing root " +
              s"${Hex.toHexString(stateRoot.take(4).toArray)} — still eligible for dispatch."
          )
        else
          statelessPeers += peer.id.value
          // NB-7: Evict from knownAvailablePeers immediately so the 1s HealingPeerAvailable scheduler
          // tick doesn't re-add and re-dispatch to this peer until the next pivot refresh / auto-clear.
          knownAvailablePeers.filterInPlace(_.id != peer.id)
          log.info(
            s"Peer ${peer.id.value} marked stateless after $strikes consecutive empty responses for healing root " +
              s"${Hex.toHexString(stateRoot.take(4).toArray)} (${statelessPeers.size}/${knownAvailablePeers.size} stateless)"
          )
          // Check if all known peers are stateless — request pivot refresh.
          // Use statelessPeers.nonEmpty (not knownAvailablePeers.nonEmpty): filterInPlace above
          // removes this peer from knownAvailablePeers BEFORE the check, so a single-peer set
          // leaves knownAvailablePeers empty and the old guard silently swallowed the trigger.
          if statelessPeers.size >= knownAvailablePeers.size && statelessPeers.nonEmpty && !pivotRefreshRequested then
            pivotRefreshRequested = true
            pivotRefreshRequestedAt = System.currentTimeMillis()
            log.warn(
              s"All ${statelessPeers.size} peers stateless for healing root " +
                s"${Hex.toHexString(stateRoot.take(4).toArray)}. Requesting pivot refresh."
            )
            snapSyncController ! SNAPSyncController.HealingAllPeersStateless

    log.info(
      s"Healed $healedCount/${nodes.size} trie nodes from peer ${peer.id.value} " +
        s"(total: $totalNodesHealed, pending: ${pendingTasks.size}, active: ${activeRequests.size} reqs, " +
        s"responseBytes=${responseBytesTargetFor(peer)})"
    )

    if healedCount > 0 then
      snapSyncController ! SNAPSyncController.ProgressNodesHealed(healedCount.toLong)
      // Flush immediately after every response rather than waiting for the 1000-node threshold.
      // Sparse healing runs (small gap counts) would otherwise stall writes in the buffer
      // indefinitely if they never hit the count gate.
      flushRawNodesAsync()

    // Dispatch more work to this peer if available (pipeline multiple requests)
    dispatchIfPossible(peer)

    self ! HealingCheckCompletion

  private def handleTimeout(requestId: BigInt, tasks: Seq[HealingEntry], peer: Peer): Unit =
    // go-ethereum reference: timeouts rotate tasks back to queue, peer returns to idle — no stateless marking.
    // (Stateless is only for empty responses.) forkAccepted filter already screens out ETH mainnet peers
    // that advertise snap/1 but have no ETC state, so the original timeout→stateless rationale no longer applies.
    log.warn(s"Healing request timed out: reqId=$requestId, tasks=${tasks.size}, peer=${peer.id.value} — re-queuing")

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
      // spec 004 T015/FR-006: a timed-out task is unsatisfied — bump its attempt counter (surfacing only).
      noteUnservableAttempt(task.hash)
    }

    if requeued > 0 then log.info(s"Re-queued $requeued timed-out healing tasks (pending: ${pendingTasks.size})")

    // Check global stagnation: no nodes healed for healingStagnationTimeoutMs.
    // BUG-3 fix: escalate to pivot refresh instead of abandoning. The old behaviour
    // (abandon + StateHealingComplete) raced with the consecutiveStagnations path (6 min)
    // and always won at 5 min, bypassing pivot refresh entirely.
    val stagnantMs = System.currentTimeMillis() - lastHealedAtMs
    if stagnantMs > healingStagnationTimeoutMs && pendingTasks.nonEmpty && !pivotRefreshRequested then
      log.warn(
        s"[HEAL] Stagnation: no nodes healed in ${stagnantMs / 1000}s — requesting pivot refresh"
      )
      lastHealedAtMs = System.currentTimeMillis() // prevent re-firing while refresh in flight
      snapSyncController ! SNAPSyncController.HealingStagnated(totalNodesHealed.toLong, pendingTasks.size.toLong)
      pivotRefreshRequested = true
      pivotRefreshRequestedAt = System.currentTimeMillis()

    tryRedispatchPendingTasks()
    self ! HealingCheckCompletion

  private def tryRedispatchPendingTasks(allowCooldownFloor: Boolean = false): Unit =
    if pendingTasks.nonEmpty && !pivotRefreshRequested then
      var eligiblePeers = knownAvailablePeers.toList
        .filterNot(isPeerCoolingDown)
        .filterNot(p => statelessPeers.contains(p.id.value))
      // Eligible-set floor (peer-retention): if the only thing excluding every non-stateless peer is a cooldown,
      // revive the soonest-to-expire one rather than stalling at zero dispatchable peers. On a residual heal down to
      // 1-2 servable SNAP peers this is the difference between forward progress and a dead stall. We only override
      // cooldown — confirmed-stateless peers stay excluded (the soft-exile threshold + periodic auto-clear handle
      // re-admission). GATED on `allowCooldownFloor` (periodic tick only): on the synchronous timeout/redispatch path
      // the cooldown is load-bearing — a just-timed-out peer must serve its penalty before retry, so the floor must
      // NOT fire there.
      if allowCooldownFloor && eligiblePeers.isEmpty then
        knownAvailablePeers.toList
          .filterNot(p => statelessPeers.contains(p.id.value))
          .filter(isPeerCoolingDown)
          .sortBy(p => peerCooldownUntilMs.getOrElse(p.id.value, 0L))
          .headOption
          .foreach { peer =>
            peerCooldownUntilMs.remove(peer.id.value)
            log.info(
              s"[HEAL-FLOOR] All servable peers were cooling and none eligible — " +
                s"reviving ${peer.id.value.take(8)} to keep the residual heal fed (peer-scarce floor)"
            )
            eligiblePeers = List(peer)
          }
      if eligiblePeers.nonEmpty then for peer <- eligiblePeers if pendingTasks.nonEmpty do dispatchIfPossible(peer)

  private def calculateProgress(): Double =
    val activeTaskCount = activeRequests.values.map(_.tasks.size).sum
    val total = pendingTasks.size + activeTaskCount + completedTaskCount
    if total == 0 then 1.0
    else completedTaskCount.toDouble / total

  private def calculateNodesPerSecond(): Double =
    val elapsedSec = (System.currentTimeMillis() - startTime) / 1000.0
    if elapsedSec > 0 then totalNodesHealed / elapsedSec else 0.0

  private def calculateKilobytesPerSecond(): Double =
    val elapsedSec = (System.currentTimeMillis() - startTime) / 1000.0
    if elapsedSec > 0 then (totalBytesReceived / 1024.0) / elapsedSec else 0.0

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

  /** Multi-seed frontier-rebuild BFS kernel (spec 003 C2/FR-002/FR-003). Seeds the level-0 frontier with EVERY
    * `(startHash, startPathset, isStorage)` in `seeds` instead of a single root, then runs the identical level-order
    * traversal: each seed's HP-encoded `startPathset` re-anchors the walk at that node and the unchanged per-child
    * nibble arithmetic extends it into that subtree. For a single-element `seeds` this is byte-identical to the prior
    * single-seed walk (same visited set, level expansion, child-path arithmetic, FrontierRebuilt emission,
    * backpressure). The walk performs NO state writes — it is a pure local read (`multiGetNodes`) that emits
    * `FrontierRebuilt` for missing nodes.
    */
  private def rebuildFrontierBFS(
      seeds: Seq[(ByteString, Seq[ByteString], Boolean)],
      selfRef: org.apache.pekko.actor.typed.ActorRef[Command],
      queue: BfsQueueStorage,
      effectiveParallelism: Int
  ): Long =
    import com.chipprbots.ethereum.mpt.{BranchNode, ExtensionNode, HashNode, LeafNode}
    import com.chipprbots.ethereum.mpt.HexPrefix
    import com.chipprbots.ethereum.domain.Account
    import scala.util.control.NonFatal

    // FIFO/insertion-order bounded visited set (companion boundedVisitedSet): at the cap the
    // earliest-INSERTED entry is evicted (NOT an LRU — recent access does not protect an entry)
    // instead of refusing new entries. Refusing (the previous ConcurrentHashMap gate)
    // silently TRUNCATED the traversal on tries larger than the cap — children past the cap were
    // never enqueued, the queue drained early, and the walk reported "Complete" (and set the
    // Layer-2 completeness marker) having covered only `cap` of the trie. Eviction trades that
    // correctness hole for bounded re-walks of shared subtries (de-duplicated downstream by
    // pendingHashSet). Access is synchronized: worker threads only touch it via markIfNew, and
    // per-check lock cost is negligible against the 50K-node multiGet I/O per chunk.
    // What the set is keyed by depends on the storage scheme — see the `*VisitKey` helpers: the node hash under Hash,
    // the node's location under Path.
    val visitedLru = TrieNodeHealingCoordinator.boundedVisitedSet(HealingVisitedCap)
    def markIfNew(h: ByteString): Boolean = visitedLru.synchronized {
      if visitedLru.contains(h) then false
      else
        visitedLru += h
        true
    }
    // spec 003 C2: mark every seed hash as visited (level 0). For one seed this is the prior
    // markIfNew(startHash); for many it pre-loads the shared visited set so cross-seed shared
    // subtries are de-duplicated exactly as within a single walk.
    seeds.foreach { case (h, ps, _) => markIfNew(seedVisitKey(h, ps)) }

    val visitedCount = new java.util.concurrent.atomic.AtomicLong(0L)
    val frontierCount = new java.util.concurrent.atomic.AtomicLong(0L)

    // --- spec 005 (Pruned descend-and-stop oracle, C2/T006/T014) ---
    // The store is consulted ONLY when `prunedEnabled` (config on AND Hash scheme AND store present). When pruning is
    // disabled, `prunedStore` is None and the oracle below is inert — the walk descends EVERY present child exactly as
    // today (byte-identical full walk). The oracle's contract (FR-001/FR-004, never-false-prune): a present child X is
    // pruned (treated as a verified leaf, NOT enqueued, so its whole subtree is skipped) IFF `prunedEnabled` AND X has
    // a durable subtree-complete record. A present-but-not-recorded child and any missing child are ALWAYS descended /
    // emitted — pruning narrows the walk only where completeness is durably proven, never where it is merely assumed.
    // Returns true when the child was pruned (caller must NOT enqueue it). Increments the pruned-subtree counter on a
    // hit. `markIfNew` has already de-duplicated the child, so each pruned subtree is counted once per walk.
    val prunedStore: Option[HealingFrontierStorage] = if prunedEnabled then healingFrontierStorage else None
    def pruneIfSubtreeComplete(childHash: ByteString): Boolean =
      prunedStore.exists { store =>
        val recorded = store.isSubtreeComplete(childHash)
        if recorded then prunedSubtreeCount.incrementAndGet()
        recorded
      }

    // --- spec 002 US2 observability (observation-only, FR-006/FR-007/FR-008) ---
    // Per-level coarse-phase timers (nanos) accumulated across chunks/sub-ranges, and re-walk inflation
    // counters. These are read and reset at each level boundary. They are pure instrumentation: they never
    // gate or change which nodes the walk enqueues/visits/declares missing (the markIfNew gate and enqueue
    // logic below are untouched). queueReadNanos/trieReadNanos/queueWriteNanos are summed over all chunks of
    // the level (aggregate CPU when sub-ranges run in parallel); childRefsSeen counts every HashNode child
    // reference observed BEFORE the markIfNew de-dup gate, distinctEnqueued counts only the references that
    // pass the gate, so childRefsSeen / max(1, distinctEnqueued) is the faithful re-walk inflation ratio.
    val queueReadNanos = new java.util.concurrent.atomic.AtomicLong(0L)
    val trieReadNanos = new java.util.concurrent.atomic.AtomicLong(0L)
    val queueWriteNanos = new java.util.concurrent.atomic.AtomicLong(0L)
    val childRefsSeen = new java.util.concurrent.atomic.AtomicLong(0L)
    val distinctEnqueued = new java.util.concurrent.atomic.AtomicLong(0L)
    // Windowed GC-pressure sampler: baseline captured at walk start; sampled per level.
    val gcSampler = new GcPressureSampler()

    // Process a sub-range [subFrom, subTo) of the current level; returns frontier entries.
    def processSubRange(subFrom: Long, subTo: Long, levelIndex: Int): Seq[HealingEntry] =
      val subFrontier = mutable.Buffer.empty[HealingEntry]

      // Time the queue-read (chunk fetch) per chunk WITHOUT changing iteration semantics: drive the lazy
      // iterator explicitly and time only the production of each chunk (one nanoTime pair per chunk fetch,
      // not per node). The body that follows is byte-identical to the prior `.foreach { chunk => ... }`.
      val chunkIterator = queue.iterateRange(subFrom, subTo)
      var moreChunks = true
      while moreChunks do
        val readStart = System.nanoTime()
        val hasNext = chunkIterator.hasNext
        val chunk = if hasNext then chunkIterator.next() else Seq.empty[BfsEntry]
        queueReadNanos.addAndGet(System.nanoTime() - readStart)
        if !hasNext then moreChunks = false
        else
          val trieReadStart = System.nanoTime()
          // Hash scheme: the original batched, content-addressed multiGetNodes (unchanged). Path scheme: PathNodeStorage
          // is keyed by PATH, not hash, so a hash-keyed batch lookup can never find a Path-scheme node. Each entry
          // carries its own pathset (it was enqueued with it), so read it at THAT path and count it present only if the
          // stored bytes hash to the entry's hash (`readPathVerifiedNode`). Without this the walk read an empty
          // hash-keyed store, reported the very first node missing, and never descended into a present trie.
          val results: Seq[Option[com.chipprbots.ethereum.mpt.MptNode]] = storageScheme match
            case StorageScheme.Hash =>
              mptStorage.multiGetNodes(chunk.map(_.hash))
            case StorageScheme.Path =>
              chunk.map(e => readPathVerifiedNode(ByteString(e.hash), e.pathset.map(ByteString(_))))
          trieReadNanos.addAndGet(System.nanoTime() - trieReadStart)
          val nextBuf = mutable.ArrayBuffer[(Array[Byte], Seq[Array[Byte]], Boolean)]()

          chunk.zip(results).foreach { case (entry, nodeOpt) =>
            val v = visitedCount.incrementAndGet()
            if v % 100_000 == 0 then
              asyncLog.info(
                s"[HEAL-BFS] Level $levelIndex: $v nodes visited, ${frontierCount.get()} frontier found, " +
                  s"${queue.counter - subTo} L${levelIndex + 1} queued"
              )
              SNAPSyncMetrics.setHealingRebuildVisited(v)

            val pathset = entry.pathset.map(ByteString(_))
            val nibbles = HexPrefix.decode(pathset.last.toArray)._1

            nodeOpt match
              case None =>
                subFrontier += HealingEntry(pathset, ByteString(entry.hash))
                frontierCount.incrementAndGet()

              case Some(node) =>
                try
                  node match
                    case branch: BranchNode =>
                      for i <- 0 until 16 do
                        branch.children(i) match
                          case hashChild: HashNode =>
                            val childHash = ByteString(hashChild.hashNode)
                            // Observation-only (FR-008/FR-023): count this child reference before the de-dup gate.
                            childRefsSeen.incrementAndGet()
                            if markIfNew(branchChildVisitKey(childHash, entry, nibbles, i)) then
                              // spec 005 C2/T006: prune a present, recorded-complete child — do NOT enqueue it, so its
                              // whole subtree is skipped (descend-and-stop). Else descend exactly as today.
                              if !pruneIfSubtreeComplete(childHash) then
                                distinctEnqueued.incrementAndGet()
                                val childNibbles = nibbles :+ i.toByte
                                val childCompact = ByteString(HexPrefix.encode(childNibbles, isLeaf = false))
                                val childPathset =
                                  if entry.isStorage then Seq(pathset.head.toArray, childCompact.toArray)
                                  else Seq(childCompact.toArray)
                                nextBuf += ((hashChild.hashNode, childPathset, entry.isStorage))
                          case _ =>

                    case ext: ExtensionNode =>
                      ext.next match
                        case hashChild: HashNode =>
                          val childHash = ByteString(hashChild.hashNode)
                          // Observation-only (FR-008/FR-023): count this child reference before the de-dup gate.
                          childRefsSeen.incrementAndGet()
                          if markIfNew(extChildVisitKey(childHash, entry, nibbles, ext.sharedKey)) then
                            // spec 005 C2/T006: prune a present, recorded-complete child (descend-and-stop); else descend.
                            if !pruneIfSubtreeComplete(childHash) then
                              distinctEnqueued.incrementAndGet()
                              val childNibbles = nibbles ++ ext.sharedKey.toArray
                              val childCompact = ByteString(HexPrefix.encode(childNibbles, isLeaf = false))
                              val childPathset =
                                if entry.isStorage then Seq(pathset.head.toArray, childCompact.toArray)
                                else Seq(childCompact.toArray)
                              nextBuf += ((hashChild.hashNode, childPathset, entry.isStorage))
                        case _ =>

                    case leaf: LeafNode if !entry.isStorage =>
                      Account(leaf.value).foreach { account =>
                        // Observation-only (FR-008/FR-023): count the account-leaf storageRoot reference
                        // before the de-dup gate, when there is a non-empty storage root to follow.
                        if account.storageRoot != Account.EmptyStorageRootHash then childRefsSeen.incrementAndGet()
                        if account.storageRoot != Account.EmptyStorageRootHash &&
                          markIfNew(storageRootVisitKey(account.storageRoot.value, nibbles, leaf.key))
                        then
                          // spec 005 C2/T006: prune a present, recorded-complete storage-trie root (descend-and-stop) —
                          // do NOT enqueue the storage subtree. Else descend it exactly as today.
                          if !pruneIfSubtreeComplete(account.storageRoot.value) then
                            distinctEnqueued.incrementAndGet()
                            val allNibbles = nibbles ++ leaf.key.toArray
                            if allNibbles.length == 64 then
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

                    case _ => // storage trie leaf, NullNode, inline HashNode
                catch
                  case NonFatal(e) =>
                    asyncLog.debug(
                      s"[HEAL-BFS] Cannot traverse ${Hex.toHexString(entry.hash.take(4))}: ${e.getMessage} — skipping"
                    )
          }
          if nextBuf.nonEmpty then
            val writeStart = System.nanoTime()
            queue.enqueueBatch(nextBuf.toSeq)
            queueWriteNanos.addAndGet(System.nanoTime() - writeStart)
        // end else (non-empty chunk)
      // end while (moreChunks)
      subFrontier.toSeq

    queue.clear()
    // spec 003 C2: enqueue ALL seeds as level 0. For one seed this is the prior single-entry enqueue;
    // queue.counter (levelEnd) now reflects seeds.size as level 0.
    queue.enqueueBatch(seeds.map { case (h, ps, s) => (h.toArray, ps.map(_.toArray), s) })
    var levelStart = 0L
    var levelEnd = queue.counter // = seeds.size after the level-0 enqueue (1 for the single-seed wrapper)
    var levelIndex = 0

    while levelStart < levelEnd do
      val levelSize = levelEnd - levelStart

      val allFrontier: Seq[HealingEntry] =
        if effectiveParallelism <= 1 || levelSize <= TrieNodeHealingCoordinator.BfsChunkSize.toLong then
          processSubRange(levelStart, levelEnd, levelIndex)
        else
          val rangeSize = math.ceil(levelSize.toDouble / effectiveParallelism).toLong
          val subRanges = (0 until effectiveParallelism)
            .map { i =>
              val from = levelStart + i * rangeSize
              val to = math.min(levelEnd, from + rangeSize)
              (from, to)
            }
            .filter { case (from, to) => from < to }

          // Sub-range readers run on healingReaderEc (NOT healingWriterEc): the parent walk Future already
          // holds a healingWriterEc thread and parks it on the Await below. Sharing the pool would let the
          // parked parent wait on sub-range Futures queued behind it on the same pool once parallelism nears
          // the pool size (thread-starvation deadlock). The dedicated reader pool removes that dependency
          // (spec 002 R3 §2, T033). `scala.concurrent.blocking` marks the park as a managed-blocking section
          // so a fork-join-backed EC (e.g. a test EC) compensates; on the production thread-pool dispatcher it
          // is a harmless no-op but documents intent.
          val futures = subRanges.map { case (from, to) =>
            Future(processSubRange(from, to, levelIndex))(healingReaderEc)
          }
          scala.concurrent.blocking {
            futures.flatMap(f => Await.result(f, Duration.Inf))
          }

      allFrontier.grouped(FrontierBatchSize).foreach { batch =>
        awaitFrontierDrain() // pause the walk if the healing backlog is over the high-water mark
        selfRef ! FrontierRebuilt(batch)
      }

      val fc = frontierCount.get()
      val queued = queue.counter - levelEnd

      // --- spec 002 US2 observability: derive + push the per-level signals (observation-only) ---
      val queueReadMs = queueReadNanos.get() / 1_000_000L
      val trieReadMs = trieReadNanos.get() / 1_000_000L
      val queueWriteMs = queueWriteNanos.get() / 1_000_000L
      val refsSeen = childRefsSeen.get()
      val enqueued = distinctEnqueued.get()
      val inflationRatio = refsSeen.toDouble / math.max(1L, enqueued).toDouble
      val (gcPauseMs, gcFraction) = gcSampler.sample()

      asyncLog.info(
        s"[HEAL-BFS] Level $levelIndex complete: $levelSize processed, $fc frontier total, $queued queued for L${levelIndex + 1}" +
          f" | phase(ms) queueRead=$queueReadMs trieRead=$trieReadMs queueWrite=$queueWriteMs" +
          f" | inflation childRefsSeen=$refsSeen distinctEnqueued=$enqueued ratio=$inflationRatio%.2f" +
          f" | gc pauseMs=$gcPauseMs fraction=$gcFraction%.4f"
      )
      SNAPSyncMetrics.setHealingRebuildVisited(visitedCount.get())
      SNAPSyncMetrics.setHealingPhaseQueueReadMs(queueReadMs)
      SNAPSyncMetrics.setHealingPhaseTrieReadMs(trieReadMs)
      SNAPSyncMetrics.setHealingPhaseQueueWriteMs(queueWriteMs)
      SNAPSyncMetrics.setHealingInflationRatio(inflationRatio)
      SNAPSyncMetrics.setHealingGcPauseMs(gcPauseMs)
      SNAPSyncMetrics.setHealingGcFraction(gcFraction)

      // Reset the per-level accumulators so the next level's gauges/log reflect only that level.
      queueReadNanos.set(0L)
      trieReadNanos.set(0L)
      queueWriteNanos.set(0L)
      childRefsSeen.set(0L)
      distinctEnqueued.set(0L)

      // Free the consumed level immediately (one range tombstone). Levels are processed exactly
      // once and never re-read, and on ETC mainnet the live queue otherwise accumulates the whole
      // walk (~140M entries, >10 GB) until the end-of-walk clear().
      queue.deleteRange(levelStart, levelEnd)

      levelStart = levelEnd
      levelEnd = queue.counter
      levelIndex += 1

    queue.clear()

    val totalMissing = frontierCount.get()
    asyncLog.info(
      s"[HEAL-BFS] Complete: ${visitedCount.get()} nodes across $levelIndex levels, $totalMissing missing nodes identified"
    )
    // spec 006 C1/D3/FR-004: return the count of missing nodes this walk emitted so the launcher
    // can pass it to onComplete (→ FrontierRebuildComplete.missingEmitted). The walk does NO state
    // writes; this is a pure read of the in-kernel AtomicLong.
    totalMissing

  /** Launch a frontier rebuild or verification BFS on the healing writer executor.
    *
    * Replaces startParallelFrontierDFS. A single Future on healingWriterEc is sufficient — BFS naturally maximises I/O
    * batching per level without needing keyspace splitting.
    */
  private def startFrontierBFS(
      root: ByteString,
      rootPath: ByteString,
      isStor: Boolean,
      onComplete: Long => Unit
  ): Unit =
    // spec 003 C2/C3: byte-identical thin wrapper over the multi-seed launcher for a single seed. The
    // full-root / crash-recovery / pivot-reseed callers reach the SAME walk (the kernel collapses one
    // seed to the prior single-entry enqueue).
    startFrontierBFS(Seq((root, Seq(rootPath), isStor)), onComplete)

  /** Multi-seed frontier-rebuild / verification launcher (spec 003 C3). Launches the multi-seed `rebuildFrontierBFS`
    * kernel (C2) on `healingWriterEc`, reusing `verificationBFSRunning`, the shared `bfsQueue`, and the same
    * `computeEffectiveParallelism` clamp. Routes success to `onComplete()` and any walk exception to
    * `FrontierWalkFailed` exactly as the single-seed launcher did. For a single-element `seeds` this is byte-identical
    * to the prior launcher.
    */
  private def startFrontierBFS(
      seeds: Seq[(ByteString, Seq[ByteString], Boolean)],
      onComplete: Long => Unit
  ): Unit =
    val selfRef = self
    // Effective parallelism floor (spec 002 R3 §1, T034): min(cfg, max(minParallelism, nproc − reservedCores)).
    // Never exceeds the operator ceiling (HealingTraversalParallelism) or the CPU count — `min` with `cfg`
    // clamps first, and `cfg <= 1` forces the serial branch (`effectiveParallelism <= 1`) below, preserving
    // the serial baseline (FR-012). The minParallelism floor lifts the previous `max(1, …)` so a host with
    // few spare cores still splits large levels; reservedCores keeps headroom for the live node + GC. On the
    // 4-core reference host the default (min 2, reserved 2) still yields max(2, 4−2) = 2.
    val nproc = Runtime.getRuntime.availableProcessors()
    // Extracted to the pure companion `computeEffectiveParallelism` (spec 002 T026) so the clamp is
    // unit-testable without instantiating the actor. byte-identical to the prior inline expression.
    val effectiveParallelism =
      TrieNodeHealingCoordinator.computeEffectiveParallelism(
        HealingTraversalParallelism,
        nproc,
        HealingMinParallelism,
        HealingReservedCores
      )
    // Guard BOTH walk kinds (rebuild + verification): the watchdog, HealingCheckCompletion and the
    // pivot-refresh path gate on this flag, and the bfsQueue is shared (cleared on walk entry), so
    // a second concurrent walk corrupts the first. Cleared by FrontierRebuildComplete /
    // VerificationBFSComplete / FrontierWalkFailed.
    verificationBFSRunning = true
    Future {
      try
        val missingEmitted = rebuildFrontierBFS(seeds, selfRef, bfsQueue, effectiveParallelism)
        onComplete(missingEmitted)
      catch
        case scala.util.control.NonFatal(e) =>
          // Never call onComplete() on failure — for the crash-recovery rebuild that would set the
          // Layer-2 completeness marker on a walk that did NOT cover the full state. Reset flags
          // through the actor instead so a fresh walk can be started.
          asyncLog.error("[HEAL-BFS] Frontier walk FAILED before completion — sending FrontierWalkFailed", e)
          selfRef ! FrontierWalkFailed
    }(healingWriterEc)

  /** Discard the verdict of the last verification pass and walk the CURRENT root again. Used when that pass was run
    * against a superseded root or found missing nodes. Starts the walk directly (not via HealingCheckCompletion): the
    * gate's `totalNodesHealed == 0` arm would declare completion without any walk. When work is still pending or a
    * flush is in flight the normal gate re-verifies once it drains (`verificationPassComplete == false` forces a walk).
    */
  private def reverifyCurrentRoot(): Unit =
    scopedVerificationActive = false
    prunedVerificationActive = false
    verificationPassComplete = false
    if isComplete && !flushing && !trieWalkInProgress && !verificationBFSRunning then
      startVerificationBFS(
        stateRoot,
        ByteString(com.chipprbots.ethereum.mpt.HexPrefix.encode(Array.empty[Byte], isLeaf = false))
      )
    else self ! HealingCheckCompletion

  /** Start a verification BFS (see [[startFrontierBFS]]).
    *
    * Traverses all locally-held trie nodes starting at `root` / `rootPath` and queues any missing descendants as
    * `FrontierRebuilt` messages. Sends `VerificationBFSComplete` when done.
    *
    * Needed because `discoverMissingChildren` skips storage roots that are already in local storage without recursing
    * into their children — a storage sub-trie with a locally-held root can still have gaps deeper in the tree (RUN10:
    * account 888157b2 had 11 missing storage nodes after StateHeal declared completion). BFS catches every missing
    * descendant in O(levels × chunk_reads) instead of O(total_nodes) point-reads.
    */
  private def startVerificationBFS(root: ByteString, rootPath: ByteString): Unit =
    verificationBFSRunning = true
    val selfRef = self
    // spec 003 C6/T016: full-root path — clear the scoped engagement gauge so a dashboard can
    // distinguish the two paths, and mark the in-flight run as NOT scoped for the completion handler.
    scopedVerificationActive = false
    SNAPSyncMetrics.setHealingScopedVerification(0L)
    // spec 005 C2/C8/T024: this full-root walk is the SAME traversal whether or not pruning is engaged — the
    // descend-and-stop oracle (pruneIfSubtreeComplete) is inert unless `prunedEnabled`. Reset the per-run pruned
    // counter and record engagement so the completion handler can emit the [HEAL-VERIFY-PRUNED] savings, and surface
    // the engaged/disabled path here at the verification entry (FR-009/FR-007).
    prunedSubtreeCount.set(0L)
    prunedVerificationActive = prunedEnabled
    prunedVerificationStartMs = System.currentTimeMillis()
    SNAPSyncMetrics.setHealingPrunedVerification(if prunedEnabled then 1L else 0L)
    SNAPSyncMetrics.setHealingPrunedSubtrees(0L)
    if prunedEnabled then
      log.info(
        s"[HEAL-VERIFY-PRUNED] Pruned (descend-and-stop) verification engaged on root " +
          s"${Hex.toHexString(root.take(4).toArray)} — present, recorded-complete subtrees are pruned " +
          s"(O(missing-frontier)); present-but-unrecorded and missing nodes are descended"
      )
    else
      log.info(
        s"[HEAL-VERIFY-PRUNED] Pruning disabled (flag=$prunedHealVerification scheme=$storageScheme " +
          s"store=${healingFrontierStorage.isDefined}) — full-trie verification on root " +
          s"${Hex.toHexString(root.take(4).toArray)}"
      )
    startFrontierBFS(
      root,
      rootPath,
      isStor = false,
      (missing: Long) => selfRef ! VerificationBFSComplete(root, missing)
    )

  /** Launch a SCOPED verification BFS seeded from the healed-paths set (spec 003 C3/FR-002/FR-006). Each healed node's
    * subtree is re-walked to completion; any missing descendant is emitted via `FrontierRebuilt`. Sends
    * `VerificationBFSComplete` on done — the SAME completion path the full-root verification uses, so completion flows
    * through the single `verificationPassComplete` chokepoint (no new completion message, no new marker set-point).
    * Each `HealingEntry` maps to `(hash, pathset, pathset.size > 1)`: a `pathset.size > 1` entry is a storage-trie seed
    * `(storageRootHash, Seq(accountHash32, compactStoragePath), isStorage = true)`, mirroring
    * `discoverMissingChildren`'s `pathset.size > 1` storage test. Reuses `verificationBFSRunning`, the shared
    * `bfsQueue`, and `startFrontierBFS`.
    */
  private def startScopedVerification(seeds: Seq[HealingEntry]): Unit =
    verificationBFSRunning = true
    val selfRef = self
    // spec 003 C6/T016: record the in-flight scoped run for the completion log + duration gauge, and
    // emit the engagement signal on entry.
    scopedVerificationActive = true
    scopedVerificationSeedCount = seeds.size
    scopedVerificationStartMs = System.currentTimeMillis()
    log.info(
      s"[HEAL-VERIFY-SCOPED] Scoped verification engaged — ${seeds.size} healed subtrees " +
        s"(root ${Hex.toHexString(stateRoot.take(4).toArray)}); skipping full-root re-walk"
    )
    SNAPSyncMetrics.setHealingScopedVerification(1L)
    SNAPSyncMetrics.setHealingScopedSubtrees(seeds.size.toLong)
    val bfsSeeds = seeds.map(e => (e.hash, e.pathset, e.pathset.size > 1))
    val walkRoot = stateRoot
    startFrontierBFS(bfsSeeds, (missing: Long) => selfRef ! VerificationBFSComplete(walkRoot, missing))

  /** Remember `account`'s codeHash for the controller if it names code this node does not hold. */
  private def recordHealedCodeHash(account: Account): Unit =
    evmCodeStorage.foreach { codes =>
      val codeHash = account.codeHash
      if codeHash != Account.EmptyCodeHash && codes.get(codeHash.value).isEmpty then
        healedCodeHashesThisResponse += codeHash.value
    }

  private def flushHealedCodeHashes(): Unit =
    if healedCodeHashesThisResponse.nonEmpty then
      val hashes = healedCodeHashesThisResponse.toSeq
      healedCodeHashesThisResponse.clear()
      log.info(s"[HEAL-CODE] ${hashes.size} healed account(s) lack bytecode — reporting codeHashes to the controller")
      snapSyncController ! SNAPSyncController.HealedCodeHashes(hashes)

  /** Inline child discovery after each healed node — Besu/geth scheduler-driven alignment. Decodes the healed node,
    * discovers child hashes, checks storage, queues missing children. Makes healing self-feeding: root → children →
    * grandchildren top-down without trie walk.
    *
    * B3 FIX: branch children are checked with a single multiGetNodes call instead of up to 16 serial isNodeInStorage
    * calls on the actor thread. Extension child uses the same pattern for consistency.
    *
    * Presence is scheme-dependent. Hash scheme: content-addressed lookup by the child's hash (unchanged). Path scheme:
    * `PathNodeStorage` is keyed by PATH, so each child is looked up at ITS OWN path (the same path it would be queued
    * and fetched with) and counts as present only when the bytes stored there hash to the child's hash — see
    * `readPathVerifiedRlp`. That is what lets a re-peg re-fetch only the delta instead of the whole trie.
    */
  private def discoverMissingChildren(nodeData: ByteString, pathset: Seq[ByteString], nodeHash: ByteString): Unit =
    import com.chipprbots.ethereum.mpt.{MptTraversals, BranchNode, ExtensionNode, HashNode, LeafNode}
    import com.chipprbots.ethereum.mpt.HexPrefix
    import com.chipprbots.ethereum.domain.Account
    import scala.util.control.NonFatal

    if pathset.nonEmpty then
      try
        val decoded = MptTraversals.decodeNode(nodeData.toArray)
        val parentCompact = pathset.last.toArray
        val parentNibbles = HexPrefix.decode(parentCompact)._1
        val isStorageTrie = pathset.size > 1 // Seq(accountHash, path) vs Seq(path)

        val newEntries = mutable.Buffer.empty[HealingEntry]

        decoded match
          case branch: BranchNode =>
            // The path a child at branch slot `i` is queued, fetched and (Path scheme) stored under.
            def childPathsetAt(i: Int): Seq[ByteString] =
              val childNibbles = parentNibbles :+ i.toByte
              val childCompact = ByteString(HexPrefix.encode(childNibbles, isLeaf = false))
              if isStorageTrie then Seq(pathset.head, childCompact) else Seq(childCompact)

            // Collect all non-pending HashNode children, then check storage for them.
            val toCheck = branch.children.zipWithIndex
              .collect { case (hn: HashNode, i) =>
                (i, ByteString(hn.hashNode))
              }
              .filterNot { case (_, h) => pendingHashSet.contains(h) }
            if toCheck.nonEmpty then
              // Hash scheme: one batched content-addressed multiGetNodes call (unchanged). Path scheme: each child is
              // looked up at its OWN path — a hash-keyed lookup can never find a Path-scheme node, which made every
              // child look missing and re-queued the whole trie below the walk root on every re-peg.
              val present: Seq[Boolean] = storageScheme match
                case StorageScheme.Hash =>
                  mptStorage.multiGetNodes(toCheck.map(_._2.toArray)).map(_.isDefined)
                case StorageScheme.Path =>
                  toCheck.toSeq.map { case (i, childHash) => isNodeInStorage(childHash, childPathsetAt(i)) }
              toCheck.zip(present).foreach { case ((i, childHash), isPresent) =>
                if !isPresent then newEntries += HealingEntry(childPathsetAt(i), childHash)
              }

          case ext: ExtensionNode =>
            ext.next match
              case hash: HashNode =>
                val childHash = ByteString(hash.hashNode)
                if !pendingHashSet.contains(childHash) then
                  val childNibbles = parentNibbles ++ ext.sharedKey.toArray
                  val childCompact = ByteString(HexPrefix.encode(childNibbles, isLeaf = false))
                  val childPathset = if isStorageTrie then Seq(pathset.head, childCompact) else Seq(childCompact)
                  val present = storageScheme match
                    case StorageScheme.Hash =>
                      mptStorage.multiGetNodes(Seq(childHash.toArray)).headOption.flatten.isDefined
                    case StorageScheme.Path =>
                      isNodeInStorage(childHash, childPathset)
                  if !present then newEntries += HealingEntry(childPathset, childHash)
              case _ => // Already inline-encoded — no missing child
          case leaf: LeafNode if !isStorageTrie =>
            // ARCH-LEAF-SEED: Account trie leaf — decode account, seed storage trie if missing.
            // Besu equivalent: getChildRequests() → getStorageTrieNodeRequests() on account leaf values.
            Account(leaf.value).foreach { account =>
              recordHealedCodeHash(account)
              if account.storageRoot != Account.EmptyStorageRootHash &&
                !pendingHashSet.contains(account.storageRoot.value)
              then
                val leafNibbles = leaf.key.toArray
                val allNibbles = parentNibbles ++ leafNibbles
                if allNibbles.length == 64 then
                  val accountHashBytes = allNibbles
                    .grouped(2)
                    .map { g =>
                      ((g(0) << 4) | g(1)).toByte
                    }
                    .toArray
                  val accountHash = ByteString(accountHashBytes)
                  val emptyStoragePath = ByteString(HexPrefix.encode(Array.empty[Byte], isLeaf = false))
                  // Presence is checked here, once the account hash — hence the storage root's path — is known. Hash
                  // scheme: content-addressed lookup by hash, exactly as before (the pathset is ignored). Path scheme:
                  // the storage-trie root lives at (accountHash, empty path) and is checked THERE.
                  if !isNodeInStorage(account.storageRoot.value, Seq(accountHash, emptyStoragePath)) then
                    newEntries += HealingEntry(Seq(accountHash, emptyStoragePath), account.storageRoot.value)
                    log.debug(
                      s"[HEAL-LEAF] Seeded storage trie root ${Hex.toHexString(account.storageRoot.value.take(4).toArray)} " +
                        s"for account ${Hex.toHexString(accountHashBytes.take(4))}"
                    )
            }

          case _ => // storage trie LeafNode, NullNode, HashNode — no children to discover
        if newEntries.nonEmpty then
          newEntries.foreach(e => pendingHashSet += e.hash)
          pendingTasks ++= newEntries
          persistFrontier(newEntries.toSeq) // Layer 2: inline-discovered children are new frontier entries
          childrenDiscoveredTotal += newEntries.size
          if childrenDiscoveredTotal % 100 == 0 || childrenDiscoveredTotal <= 20 then
            log.info(
              s"[HEAL-DISCOVER] Inline children queued: $childrenDiscoveredTotal total " +
                s"(+${newEntries.size} from this node, pending: ${pendingTasks.size})"
            )

        // spec 005 C3b/D2/T009/T012 — heal-side subtree-complete SEED (consensus-load-bearing; the single highest
        // chain-split risk of this port). Stage this just-healed node's OWN hash as a subtree-complete candidate IFF
        // its subtree is durably closed by INDUCTION: every hash-referenced child is PRESENT on disk AND itself
        // recorded subtree-complete (isSubtreeComplete). The isSubtreeComplete clause is load-bearing — mere PRESENCE is
        // NEVER enough: a download-mosaic node can be present yet its subtree incomplete, so seeding on presence alone
        // would write a false record → the pruned descent would skip a real hole → false completion → divergent
        // finalized state. "present AND recorded" SUBSUMES "no missing child" and "no pending child": a still-pending /
        // in-flight child is either absent (fails isNodeInStorage) or present-but-unrecorded (fails isSubtreeComplete).
        // For a leaf (no hash children) the closure is VACUOUS — the inductive BASE (PrunedHealCrashSafetySpec T-3).
        // Inline children carry NO hash references (a 32-byte hash makes a node ≥32 bytes, so it is never inlined), so
        // direct HashNode children + the account-leaf storage root are the COMPLETE set of subtree roots under this
        // node. Gated on prunedEnabled (else inert ⇒ OFF-path byte-identical). STAGED only — the durable record is
        // written by writeDurableSubtreeRecords strictly AFTER mptStorage.persist() (D3 record-after-persist); a crash
        // before the flush drops the in-memory candidate ⇒ safe descend. The `newEntries.isEmpty` pre-gate is a cheap
        // necessary short-circuit (a found missing non-pending child means not closed); the forall over actual
        // storage+record state is the AUTHORITATIVE gate and can never false-record regardless of pending/filter state.
        if prunedEnabled && newEntries.isEmpty then
          val subtreeRoots: Seq[ByteString] = decoded match
            case branch: BranchNode =>
              branch.children.collect { case hn: HashNode => ByteString(hn.hashNode) }.toSeq
            case ext: ExtensionNode =>
              ext.next match
                case hn: HashNode => Seq(ByteString(hn.hashNode))
                case _            => Seq.empty
            case leaf: LeafNode if !isStorageTrie =>
              Account(leaf.value).toOption match
                case Some(account) if account.storageRoot != Account.EmptyStorageRootHash =>
                  Seq(account.storageRoot.value)
                case _ => Seq.empty
            case _ => Seq.empty
          // `prunedEnabled` (this block's guard) requires the Hash scheme, so `isNodeInStorage` here is always the
          // content-addressed lookup and takes no path. If pruning is ever enabled under Path, the empty pathset makes
          // it conservatively false — no record is staged, the walk descends — never a false "closed".
          val subtreeClosed =
            subtreeRoots.forall(c =>
              isNodeInStorage(c, Seq.empty) && healingFrontierStorage.exists(_.isSubtreeComplete(c))
            )
          if subtreeClosed then
            pendingSubtreeRecords += nodeHash
            log.debug(
              s"[HEAL-VERIFY-PRUNED] Staged subtree-complete candidate ${Hex.toHexString(nodeHash.take(4).toArray)} " +
                s"(${subtreeRoots.size} child subtree(s) present+recorded) — durable record written post-flush (D3)."
            )
      catch
        case NonFatal(e) =>
          log.debug(
            s"[HEAL] Cannot decode healed node for child discovery: ${e.getMessage}. " +
              s"Skipping — trie walk will find these nodes."
          )

  // ---- frontier-walk de-dup keys -----------------------------------------------------------------------------------
  // `rebuildFrontierBFS` de-dups the nodes it enqueues in a bounded `visited` set, so a subtrie reachable by two
  // references is walked once. What counts as "the same node" depends on the storage scheme:
  //   - Hash: the node HASH. Storage is content-addressed — one copy per hash — so a second reference to a node already
  //     walked needs no second visit. Unchanged.
  //   - Path: the node's LOCATION (trie kind + account hash + compact path). `PathNodeStorage` holds one physical copy
  //     per location, so two locations that happen to carry the same hash — e.g. the identical storage tries of two
  //     accounts — are DISTINCT stored nodes and each must be verified. De-duping them by hash would let a hole at the
  //     second location go undetected: the walk would report "complete" over missing state.
  // The helpers take cheap references (no precomputed paths), so under Hash they return their hash argument at once —
  // no allocation and no behavioural change on the ETC path.

  /** Location identity of a node: trie kind (account / storage), account hash for a storage node, and the HP-encoded
    * nibble path. Prefix-free (the account hash is a fixed 32 bytes), so distinct locations never collide.
    */
  private def locationKey(accountHash: Option[Array[Byte]], nibbles: Array[Byte]): ByteString =
    val builder = ByteString.newBuilder
    accountHash match
      case Some(hash) => builder.putByte(1).putBytes(hash)
      case None       => builder.putByte(0)
    builder.putBytes(com.chipprbots.ethereum.mpt.HexPrefix.encode(nibbles, isLeaf = false)).result()

  /** The account hash scoping `parent`'s storage trie, or `None` for an account-trie entry. */
  private def storageOwner(parent: BfsEntry): Option[Array[Byte]] =
    if parent.isStorage then Some(parent.pathset.head) else None

  /** De-dup key for a walk seed (`seeds` carry HP-encoded pathsets, as `HealingEntry` does). */
  private def seedVisitKey(hash: ByteString, pathset: Seq[ByteString]): ByteString =
    storageScheme match
      case StorageScheme.Hash => hash
      case StorageScheme.Path =>
        val owner = if pathset.size > 1 then Some(pathset.head.toArray) else None
        locationKey(owner, com.chipprbots.ethereum.mpt.HexPrefix.decode(pathset.last.toArray)._1)

  /** De-dup key for the child in branch slot `slot` of `parent`, whose own nibble path is `parentNibbles`. */
  private def branchChildVisitKey(
      childHash: ByteString,
      parent: BfsEntry,
      parentNibbles: Array[Byte],
      slot: Int
  ): ByteString =
    storageScheme match
      case StorageScheme.Hash => childHash
      case StorageScheme.Path => locationKey(storageOwner(parent), parentNibbles :+ slot.toByte)

  /** De-dup key for the child behind an extension node of `parent` (path `parentNibbles` ++ `sharedKey`). */
  private def extChildVisitKey(
      childHash: ByteString,
      parent: BfsEntry,
      parentNibbles: Array[Byte],
      sharedKey: ByteString
  ): ByteString =
    storageScheme match
      case StorageScheme.Hash => childHash
      case StorageScheme.Path => locationKey(storageOwner(parent), parentNibbles ++ sharedKey.toArray)

  /** De-dup key for the storage-trie root an account leaf references. Under Path it lives at (accountHash, empty path),
    * the account hash being the leaf's full 64-nibble path packed into 32 bytes. A path of any other length is not a
    * real account position; the enqueue below skips it, so its key is immaterial and falls back to the hash.
    */
  private def storageRootVisitKey(
      storageRoot: ByteString,
      parentNibbles: Array[Byte],
      leafKey: ByteString
  ): ByteString =
    storageScheme match
      case StorageScheme.Hash => storageRoot
      case StorageScheme.Path =>
        val accountNibbles = parentNibbles ++ leafKey.toArray
        if accountNibbles.length == 64 then
          val accountHash = accountNibbles.grouped(2).map(g => ((g(0) << 4) | g(1)).toByte).toArray
          locationKey(Some(accountHash), Array.empty[Byte])
        else storageRoot

  /** Path scheme only: the bytes stored at the task's OWN path in [[PathNodeStorage]], returned ONLY when their
    * keccak256 equals `hash` — otherwise `None`.
    *
    * `pathset` is the same shape `HealingEntry` / `GetTrieNodes` use, HP/compact-encoded: `Seq(compactPath)` for an
    * account-trie node, `Seq(accountHash32, compactStoragePath)` for a storage-trie node. It is decoded to the nibble
    * path `PathNodeStorage.readAccountNode` / `readStorageNode` take (they HP-encode it back to the RocksDB key).
    *
    * Why the hash comparison is mandatory: `PathNodeStorage` keeps exactly ONE node per path slot, and a re-peg REUSES
    * paths — a node healed against the previous root can sit at the very path the new root needs, with DIFFERENT
    * content. "Something is stored at this path" is therefore NOT presence; only "the bytes at this path are the bytes
    * whose keccak256 the parent references" is. A stale node, or the right bytes at the wrong path, both fall through
    * to `None` and get re-fetched (an idempotent overwrite).
    *
    * Also `None`: no `PathNodeStorage` configured, nothing stored at the path, or a pathset of an unexpected shape (no
    * path context ⇒ conservatively absent, never present).
    */
  private def readPathVerifiedRlp(hash: ByteString, pathset: Seq[ByteString]): Option[Array[Byte]] =
    import com.chipprbots.ethereum.mpt.HexPrefix
    pathNodeStorageOpt.flatMap { pns =>
      val stored: Option[Array[Byte]] = pathset match
        case Seq(compactPath) =>
          pns.readAccountNode(HexPrefix.decode(compactPath.toArray)._1)
        case Seq(accountHash, compactPath) =>
          pns.readStorageNode(accountHash, HexPrefix.decode(compactPath.toArray)._1)
        case _ => None
      stored.filter(rlp => ByteString.fromArrayUnsafe(kec256(rlp)) == hash)
    }

  /** `readPathVerifiedRlp` plus decode, for the frontier-rebuild walk, which must traverse the node's children. Bytes
    * that hash correctly but do not decode are reported absent (so they are re-fetched and overwritten) and logged: it
    * should be unreachable — a node referenced by a valid trie decodes — but must never be counted present.
    */
  private def readPathVerifiedNode(
      hash: ByteString,
      pathset: Seq[ByteString]
  ): Option[com.chipprbots.ethereum.mpt.MptNode] =
    readPathVerifiedRlp(hash, pathset).flatMap { rlp =>
      try Some(MptStorage.decodeNode(rlp, hash.toArray))
      catch
        case scala.util.control.NonFatal(e) =>
          asyncLog.warn(
            s"[HEAL-BFS] Path-scheme node ${Hex.toHexString(hash.take(4).toArray)} hashes correctly at its path but " +
              s"does not decode (${e.getMessage}) — treating as missing so it is re-fetched"
          )
          None
    }

  /** Presence check that gates healing queue/skip decisions.
    *
    * Hash scheme: content-addressed lookup through `mptStorage` — byte-identical to the pre-existing behaviour,
    * `pathset` is ignored. Path scheme: present ONLY when the bytes stored at `pathset`'s exact path hash to `hash`
    * (`readPathVerifiedRlp`); path existence alone is never sufficient. An empty `pathset` means the call site has no
    * path context, which under Path is conservatively absent (healing re-requests it; writes are idempotent).
    */
  private def isNodeInStorage(hash: ByteString, pathset: Seq[ByteString]): Boolean =
    storageScheme match
      case StorageScheme.Hash =>
        try
          mptStorage.get(hash.toArray); true
        catch case _: Exception => false
      case StorageScheme.Path =>
        readPathVerifiedRlp(hash, pathset).isDefined

object TrieNodeHealingCoordinator:

  /** Floor for the per-peer in-flight healing budget (UpdateMaxInFlightPerPeer handler). Below this a single slow peer
    * holding the lone outstanding slot can stall a 1-2-peer residual heal. Peer-pool management only — no effect on
    * which nodes are stored or the content-hash gate.
    */
  val MinInFlightPerPeer: Int = 2

  sealed trait Command
  case class StartTrieNodeHealing(stateRoot: ByteString) extends Command
  case class QueueMissingNodes(nodes: Seq[(Seq[ByteString], ByteString)]) extends Command
  case class HealingPeerAvailable(peer: Peer) extends Command
  case class HealingPeerUnavailable(peerId: String) extends Command
  case class HealingTaskComplete(requestId: BigInt, result: Either[String, Int]) extends Command
  case class HealingTaskFailed(requestId: BigInt, reason: String) extends Command
  case class HealingGetProgress(replyTo: org.apache.pekko.actor.typed.ActorRef[HealingStatistics]) extends Command
  case object HealingCheckCompletion extends Command
  case class HealingPivotRefreshed(newStateRoot: ByteString) extends Command
  final case class HealingServeRootRefresh(newServeRoot: ByteString) extends Command
  case object HealingResumeDispatch extends Command
  case object HealingForceComplete extends Command
  case class WalkStateChanged(inProgress: Boolean) extends Command
  case class UpdateMaxInFlightPerPeer(newLimit: Int) extends Command
  sealed trait WorkerMessage
  case class FetchTrieNodes(task: HealingTask, peer: Peer) extends WorkerMessage
  case class TrieNodesResponseMsg(response: TrieNodes) extends WorkerMessage with Command
  case class HealingRequestTimeout(requestId: BigInt) extends WorkerMessage with Command
  case object HealingCheckIdle extends WorkerMessage

  /** Default cap on the frontier-rebuild walk's FIFO `visited` set: 4M entries ≈ 480-640 MB (a 32-byte ByteString key +
    * wrapper + LinkedHashMap entry is ~120-150 B, not the 80 B an "≈320 MB" estimate assumed). Insertion-order
    * eviction, NOT LRU. Used when `sync.snap-sync.healing-visited-cap` is unset. Raise only from a measured
    * `inflation_ratio` and within the heap budget. See docs/design/healing-frontier-scale.md.
    */
  val DefaultVisitedCap: Int = 4_000_000

  // Frontier-emission backpressure watermarks (entries in pendingTasks). The BFS walk pauses
  // emitting discovered missing nodes once the healing backlog reaches the high-water mark and
  // resumes below the low-water mark, bounding discovered-but-unhealed heap to ~high-water entries
  // (~100K HealingEntry ≈ tens of MB) instead of growing unbounded under a peer-scarce drain rate.
  val DefaultFrontierHighWater: Int = 100_000
  val DefaultFrontierLowWater: Int = 50_000
  // Safety valve: the walk never blocks on backpressure longer than this before resuming (and
  // warning), so a stalled drain (no peers, dead actor) can't deadlock the walk.
  val FrontierBackpressureMaxWaitMs: Long = 10.minutes.toMillis

  /** Default upper bound on the in-memory healed-paths set used for scoped post-heal verification (spec 003 FR-011). A
    * round that heals more than this many distinct nodes falls back to full-root verification rather than growing the
    * set, bounding its worst-case heap (~200K × HealingEntry ≈ tens of MB). Operator-tunable via
    * `sync.snap-sync.scoped-heal-max-paths`.
    */
  val DefaultScopedHealMaxPaths: Int = 200_000

  /** Default FR-006 surfacing threshold (spec 004): after this many unsatisfied heal attempts with no serve-root
    * advance in between, the coordinator surfaces the stuck task (log + metric). It NEVER force-completes — a task no
    * serve root can supply keeps the completeness walk from finding zero, so completion can never be falsely declared.
    * Operator-tunable via `sync.snap-sync.decoupled-heal-max-attempts-no-refresh`.
    */
  val DefaultDecoupledHealMaxAttemptsNoRefresh: Int = 12

  /** Operator-configurable ceiling for BFS level parallelism. Effective parallelism is `min(DefaultBfsParallelism,
    * max(1, availableProcessors - 2))` so large levels are split across sub-ranges on `healingWriterEc`. See
    * `healing-traversal-parallelism` in `sync.conf`.
    */
  val DefaultBfsParallelism: Int = 4

  /** Floor on BFS level parallelism (spec 002 R3 §1, T034): `effectiveParallelism = min(traversalParallelism,
    * min(nproc, max(DefaultMinParallelism, nproc − DefaultReservedCores)))`. Lifts the old `max(1, …)` so a host with
    * few spare cores still splits large levels. Operator-tunable via `sync.snap-sync.healing-min-parallelism`.
    */
  val DefaultMinParallelism: Int = 2

  /** Cores reserved for the live node + GC, subtracted from `availableProcessors` before the min-parallelism floor
    * (spec 002 R3 §1, T034). On the 4-core reference host this yields max(2, 4−2) = 2. Operator-tunable via
    * `sync.snap-sync.healing-reserved-cores`.
    */
  val DefaultReservedCores: Int = 2

  /** Maximum number of node hashes per `multiGetNodes` call inside `rebuildFrontierBFS`. Keeps each Java list under
    * ~1.6 MB (50K × 32B) and prevents a single enormous call when a trie level spans hundreds of thousands of nodes.
    */
  val BfsChunkSize: Int = 50_000

  /** Heap-bounded `visited` set for the frontier-rebuild walk: a `LinkedHashMap`-backed FIFO that evicts the
    * earliest-INSERTED (already-completed) subtries once it exceeds `cap` (insertion-order eviction — NOT an LRU;
    * recent access does not protect an entry). Exposed on the companion so the eviction contract (size never exceeds
    * `cap`; eldest dropped first) is unit-testable without instantiating the actor.
    */
  def boundedVisitedSet(cap: Int): mutable.Set[ByteString] =
    val lru = new java.util.LinkedHashMap[ByteString, java.lang.Boolean](1024, 0.75f, false):
      override def removeEldestEntry(eldest: java.util.Map.Entry[ByteString, java.lang.Boolean]): Boolean =
        size() > cap
    java.util.Collections.newSetFromMap[ByteString](lru).asScala

  /** Effective BFS level parallelism (spec 002 R3 §1, T026/T034): `min(traversalParallelism, min(availableProcessors,
    * max(minParallelism, availableProcessors − reservedCores)))`.
    *
    * Pure — no side effects, no I/O, no system reads (the caller supplies `availableProcessors`). Extracted from the
    * inline expression in `startFrontierBFS` so the clamp is unit-testable without instantiating the actor; the result
    * is byte-identical to that prior expression for all inputs. It is a performance/scheduling knob only: it controls
    * how a BFS level is split into sub-ranges for parallel reading and never changes which nodes the walk
    * enqueues/visits/declares missing (`<= 1` ⇒ the serial branch). See `DefaultMinParallelism`/`DefaultReservedCores`.
    */
  def computeEffectiveParallelism(
      traversalParallelism: Int,
      availableProcessors: Int,
      minParallelism: Int,
      reservedCores: Int
  ): Int =
    math.min(
      traversalParallelism,
      math.min(availableProcessors, math.max(minParallelism, availableProcessors - reservedCores))
    )

  /** Behavior factory (Group S3). SSC is still Classic at S3 time, so it spawns this via `PropsAdapter` and holds a
    * Classic `ActorRef`; its `!` routes Command-typed messages. TNHC spawns no child workers — it dispatches
    * GetTrieNodes directly to `networkPeerManager` (still Classic) and runs its BFS walks on dedicated dispatchers.
    */
  def apply(
      stateRoot: ByteString,
      networkPeerManager: org.apache.pekko.actor.typed.ActorRef[NetworkPeerManagerActor.Command],
      requestTracker: SNAPRequestTracker,
      mptStorage: MptStorage,
      batchSize: Int,
      snapSyncController: org.apache.pekko.actor.typed.ActorRef[SNAPSyncController.Command],
      concurrency: Int = 16,
      visitedCap: Int = DefaultVisitedCap,
      healingFrontierStorage: Option[HealingFrontierStorage] = None,
      healingWriterEcOverride: Option[ExecutionContext] = None,
      healingReaderEcOverride: Option[ExecutionContext] = None,
      traversalParallelism: Int = DefaultBfsParallelism,
      healingMinParallelism: Int = DefaultMinParallelism,
      healingReservedCores: Int = DefaultReservedCores,
      bfsQueueStorageOpt: Option[BfsQueueStorage] = None,
      storageScheme: StorageScheme = StorageScheme.Hash,
      pathNodeStorageOpt: Option[PathNodeStorage] = None,
      frontierHighWater: Int = DefaultFrontierHighWater,
      frontierLowWater: Int = DefaultFrontierLowWater,
      frontierBackpressureMaxWaitMs: Long = FrontierBackpressureMaxWaitMs,
      scopedHealVerification: Boolean = true,
      scopedHealMaxPaths: Int = DefaultScopedHealMaxPaths,
      // spec 005 pruned descend-and-stop. Exposed through the factory (the #1373 typed rewrite dropped this forward)
      // so PrunedHealFallbackSpec's flag-OFF case can drive `prunedEnabled = false`. Production omits it and keeps the
      // impl default (true) ⇒ byte-for-byte no-op. `prunedEnabled` also requires Hash scheme + a present frontier store.
      prunedHealVerification: Boolean = true,
      // spec 002 frontier persistence (Layer-2 mirror writes + completeness markers). Exposed here so production
      // (SNAPSyncController, wired from sync.conf's healing-frontier-persistence) and tests can enable it. Defaults
      // OFF to match the impl default and the spec-005 decoupling (store may be present for pruned-heal records only).
      frontierPersistenceEnabled: Boolean = false,
      decoupledHealServeRoot: Boolean = false,
      decoupledHealMaxAttemptsNoRefresh: Int = DefaultDecoupledHealMaxAttemptsNoRefresh,
      // spec 009 (Moving-Root Delta Heal) — plumbing only; no behavior reads it yet (see impl ctor).
      movingRootDeltaHeal: Boolean = false,
      evmCodeStorage: Option[EvmCodeStorage] = None,
      walkLocalOnly: Option[java.util.concurrent.atomic.AtomicBoolean] = None
  ): Behavior[Command] =
    Behaviors.setup { context =>
      Behaviors.withTimers { timers =>
        new TrieNodeHealingCoordinatorImpl(
          context,
          timers,
          initialStateRoot = stateRoot,
          networkPeerManager = networkPeerManager,
          requestTracker = requestTracker,
          mptStorage = mptStorage,
          batchSize = batchSize,
          snapSyncController = snapSyncController,
          concurrency = concurrency,
          visitedCap = visitedCap,
          healingFrontierStorage = healingFrontierStorage,
          healingWriterEcOverride = healingWriterEcOverride,
          healingReaderEcOverride = healingReaderEcOverride,
          traversalParallelism = traversalParallelism,
          healingMinParallelism = healingMinParallelism,
          healingReservedCores = healingReservedCores,
          bfsQueueStorageOpt = bfsQueueStorageOpt,
          storageScheme = storageScheme,
          pathNodeStorageOpt = pathNodeStorageOpt,
          frontierHighWater = frontierHighWater,
          frontierLowWater = frontierLowWater,
          frontierBackpressureMaxWaitMs = frontierBackpressureMaxWaitMs,
          scopedHealVerification = scopedHealVerification,
          scopedHealMaxPaths = scopedHealMaxPaths,
          prunedHealVerification = prunedHealVerification,
          frontierPersistenceEnabled = frontierPersistenceEnabled,
          decoupledHealServeRoot = decoupledHealServeRoot,
          decoupledHealMaxAttemptsNoRefresh = decoupledHealMaxAttemptsNoRefresh,
          movingRootDeltaHeal = movingRootDeltaHeal,
          evmCodeStorage = evmCodeStorage,
          walkLocalOnly = walkLocalOnly
        ).start()
      }
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
