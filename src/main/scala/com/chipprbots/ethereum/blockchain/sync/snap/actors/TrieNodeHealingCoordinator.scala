package com.chipprbots.ethereum.blockchain.sync.snap.actors

import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.TimerScheduler
import org.apache.pekko.util.ByteString

import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

import org.bouncycastle.util.encoders.Hex

import com.chipprbots.ethereum.blockchain.sync.snap.*
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController
import com.chipprbots.ethereum.blockchain.sync.snap.actors.healing.*
import com.chipprbots.ethereum.blockchain.sync.snap.controller.StateIngest
import com.chipprbots.ethereum.blockchain.sync.snap.heal.HealRequestBook
import com.chipprbots.ethereum.db.storage.BfsQueueStorage
import com.chipprbots.ethereum.db.storage.InMemoryBfsQueueStorage
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.p2p.messages.SNAP.TrieNodes

// The adoption map for the snap-split verify adoption mode (spec 016 C-H1): the dropped parameter and its member.
// adopt-map: healingFrontierStorage -> healingFrontier

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
    val networkPeerManager: org.apache.pekko.actor.typed.ActorRef[NetworkPeerManagerActor.Command],
    val requestTracker: SNAPRequestTracker,
    val mptStorage: MptStorage,
    val batchSize: Int,
    val snapSyncController: org.apache.pekko.actor.typed.ActorRef[SNAPSyncController.Command],
    concurrency: Int,
    visitedCap: Int = TrieNodeHealingCoordinator.DefaultVisitedCap,
    val stateIngest: StateIngest,
    healingWriterEcOverride: Option[ExecutionContext] = None,
    healingReaderEcOverride: Option[ExecutionContext] = None,
    traversalParallelism: Int = TrieNodeHealingCoordinator.DefaultBfsParallelism,
    healingMinParallelism: Int = TrieNodeHealingCoordinator.DefaultMinParallelism,
    healingReservedCores: Int = TrieNodeHealingCoordinator.DefaultReservedCores,
    val storageScheme: StorageScheme = StorageScheme.Hash,
    val frontierHighWater: Int = TrieNodeHealingCoordinator.DefaultFrontierHighWater,
    val frontierLowWater: Int = TrieNodeHealingCoordinator.DefaultFrontierLowWater,
    val frontierBackpressureMaxWaitMs: Long = TrieNodeHealingCoordinator.FrontierBackpressureMaxWaitMs,
    scopedHealVerification: Boolean = true,
    val scopedHealMaxPaths: Int = TrieNodeHealingCoordinator.DefaultScopedHealMaxPaths,
    // spec 005 (Pruned descend-and-stop verification). When true AND `storageScheme == Hash`, the completeness walk
    // prunes any present node that has a durable subtree-complete record (HealingFrontierStorage CF 'g'), making a
    // fresh node's first verification O(missing-frontier). Off OR Path scheme ⇒ the unchanged full-trie walk. Consensus
    // safety rests on the never-false-prune invariant: prune iff present AND recorded-complete (records written only
    // AFTER the subtree's bytes are durably committed). See `prunedEnabled`.
    val prunedHealVerification: Boolean = true,
    // spec 002/003 (frontier persistence + resume + completeness marker). Gates the LAYER-2 frontier MIRROR writes
    // (persistFrontier/unpersistFrontier/clearPersistedFrontier), the [HEAL-RESTART] resume-from-persisted-frontier
    // path, and the spec-002 `markComplete()` snapshot marker. Decoupled from store PRESENCE: spec 005 makes the store
    // present whenever `prunedHealVerification` is on (to host the subtree-complete records), but these persistence
    // features must stay dark unless `healing-frontier-persistence` is explicitly enabled (default off), preserving
    // FR-005 byte-for-byte parity with pre-spec-005 behavior. The spec-005 subtree records (markSubtreeComplete /
    // isSubtreeComplete / the descend-and-stop oracle) are gated on `prunedEnabled`, NOT on this flag.
    val frontierPersistenceEnabled: Boolean = false,
    // spec 004 (Decoupled Heal Serve-Root). When true, the GetTrieNodes fetch targets `serveRoot` (an advancing
    // newest-servable root) instead of the walk root `stateRoot`; when false, the fetch uses `stateRoot` (coupled,
    // byte-identical to pre-spec-004). Completeness is ALWAYS judged against `stateRoot`, regardless of this flag.
    val decoupledHealServeRoot: Boolean = false,
    // FR-006 surfacing threshold: after this many unsatisfied attempts with no serve-root advance, surface (log +
    // metric). Never force-completes.
    val decoupledHealMaxAttemptsNoRefresh: Int = TrieNodeHealingCoordinator.DefaultDecoupledHealMaxAttemptsNoRefresh,
    // spec 009 (Moving-Root Delta Heal). When true, the heal completes toward AND fetches against ONE current served
    // root: requestNextBatch (T005) collapses the fetch root to the completeness root `stateRoot`, and
    // StartTrieNodeHealing (T006) seeds an absent heal root as a frontier task and fetches it instead of handing off to
    // lazy healing. When false, the heal uses the spec-004 walk/serve split (byte-identical to pre-spec-009). Impl
    // default false (bare construction stays on the spec-004 path); the production default-on flows from SNAPSyncConfig
    // via the spawn sites. The content-hash store gate and the finalizeSnapSync anchor guard are byte-untouched on BOTH
    // paths — under the flag the gate matches BY CONSTRUCTION (fetch root == completeness root), never by weakening it.
    val movingRootDeltaHeal: Boolean = false,
    // The healed-code check. When true, a healed account leaf whose codeHash is not in `stateIngest.bytecode` is
    // reported to the controller (SNAPSyncController.HealedCodeHashes) so the bytecode is fetched before SNAP
    // finalises. The production spawn passes true; false (bare construction, most specs) reports nothing and never
    // reads the store.
    val healedCodeCheck: Boolean = false,
    // Shared with the controller. True while a frontier walk is running and nothing is pending or in flight, i.e. the
    // heal needs no peers and any re-peg of the heal root can only invalidate the walk. The controller's proactive
    // heal-root re-peg (moving-root delta heal) is suppressed while it is set; None (bare construction) never suppresses.
    walkLocalOnly: Option[java.util.concurrent.atomic.AtomicBoolean] = None
) extends FrontierPersistence
    with FrontierRebuildBfs
    with HealVerification
    with HealDispatch
    with HealResponseProcessing
    with TrieNodeHealingEnv
    with FrontierPersistenceState
    with FrontierRebuildBfsState
    with HealVerificationState
    with HealDispatchState
    with HealResponseProcessingState:

  import TrieNodeHealingCoordinator.*

  val log = context.log
  // `self` was a Classic field; under Typed it is `context.self`. Captured here so the Future continuations,
  // scheduled timers and the request-tracker timeout callback reference the same value the old code expected.
  val self: org.apache.pekko.actor.typed.ActorRef[Command] = context.self
  // Thread-confined `context.log` is unsafe inside Future/BFS bodies that run off the actor dispatcher. Off-thread
  // logging (the HEAL-RESTART resume Future, startFrontierBFS Future, and rebuildFrontierBFS/processSubRange which
  // execute on healingWriterEc/healingReaderEc) uses this plain SLF4J logger instead. Actor-thread handlers use `log`.
  val asyncLog: org.slf4j.Logger =
    org.slf4j.LoggerFactory.getLogger(classOf[TrieNodeHealingCoordinatorImpl])

  // Mutable state root — updated in-place when the controller refreshes the pivot (HealingPivotRefreshed).
  var stateRoot: ByteString = initialStateRoot

  // Task management — each task has a pathset (for GetTrieNodes) and a hash (for verification).
  // ArrayDeque (circular buffer) gives O(1) amortized head/tail operations (#1167). The previous
  // immutable `Seq` did O(n) on every `:+` and head-drop — quadratic at healing scale.
  val pendingTasks: mutable.ArrayDeque[HealingEntry] = mutable.ArrayDeque.empty
  // Thread-safe mirror of pendingTasks.size, refreshed by emitHealingFrontierGauges() on the actor
  // thread. The frontier-rebuild BFS walk runs on healingWriterEc and reads this (never pendingTasks
  // directly) to apply backpressure — it pauses emission when the healing backlog is large so a
  // peer-scarce drain rate can't let discovered-but-unhealed nodes pile up to an OOM (see
  // awaitFrontierDrain). geth bounds the analogous queue with trie.Sync maxFetchesPerDepth.
  val pendingBackpressure: java.util.concurrent.atomic.AtomicInteger =
    new java.util.concurrent.atomic.AtomicInteger(0)
  var completedTaskCount: Int = 0
  private var healingMilestonePct: Int = -1

  /** Dedicated dispatcher for the batched raw-node RocksDB flush. Tests inject their own EC; production looks up
    * `healing-writer-dispatcher` from the actor system. Keeps the blocking write off `sync-dispatcher` so other sync
    * actors don't stall during healing-heavy bursts.
    */
  val healingWriterEc: ExecutionContext =
    healingWriterEcOverride.getOrElse(context.system.classicSystem.dispatchers.lookup("healing-writer-dispatcher"))

  /** Dedicated dispatcher for the BFS sub-range *reader* Futures (`processSubRange`). Distinct from `healingWriterEc`
    * to break a latent thread-starvation deadlock: the parent walk Future parks a `healingWriterEc` thread inside
    * `Await.result(...)` (rebuildFrontierBFS) while waiting for the N sub-range Futures to finish. If those sub-range
    * Futures shared `healingWriterEc`, raising parallelism toward the pool size would leave the parked parent waiting
    * on Futures queued behind it on the same pool → deadlock. Running the sub-ranges on a separate pool removes the
    * dependency. Tests inject their own EC (a same-thread EC forces deterministic serial execution); production looks
    * up `healing-reader-dispatcher`. See spec 002 R3 §2 (T032/T033).
    */
  val healingReaderEc: ExecutionContext =
    healingReaderEcOverride.getOrElse(context.system.classicSystem.dispatchers.lookup("healing-reader-dispatcher"))

  // Global stagnation detection: if no nodes healed for this duration, declare
  // healing complete with a warning. Prevents infinite loops when all peers lack
  // GetTrieNodes support (ETH68 networks). Regular sync fetches missing nodes on-demand.
  var lastHealedAtMs: Long = System.currentTimeMillis()
  val healingStagnationTimeoutMs: Long = 5 * 60 * 1000 // 5 minutes

  // Periodic idle stagnation escalation: fires even when no requests are active.
  // The per-timeout stagnation check at handleTimeout() requires a live request to time out;
  // if pendingTasks is non-empty but activeRequests is empty (all peers cooling down),
  // healing can stall silently. After 5 consecutive 2-minute ticks with no active requests
  // and no progress (10 minutes total), force-complete healing.
  // Reference: Besu markAsStalled() is a TODO no-op; this is a fukuii-specific liveness guarantee.
  private var consecutiveIdleChecks: Int = 0
  private var lastPulseHealedCount: Int = 0
  // FIX-STAGNATION-LIMIT: Track consecutive 2-min cycles with zero healed nodes (even when active).
  // After MaxConsecutiveStagnations, notify controller to restart with fresh pivot.
  private var consecutiveStagnations: Int = 0
  private val MaxConsecutiveStagnations: Int = 3
  var trieWalkInProgress: Boolean = false
  // Verification BFS state: gates StateHealingComplete and catches storage sub-trie gaps (Fix BUG-1/BUG-2).
  // verificationPassComplete = true only after a BFS traversal finds zero missing nodes.
  // verificationBFSRunning = true while ANY frontier walk Future (crash-recovery rebuild OR
  // verification) is executing on healingWriterEc. Set inside startFrontierBFS so both walk kinds
  // are covered — the crash-recovery rebuild previously set NO flag, so HEAL-PULSE reported
  // walkRunning=false and the dead-pulse watchdog force-started a verification walk 6 minutes into
  // a running rebuild (observed live 2026-06-11): two walks interleaving on the shared bfsQueue,
  // both producing garbage coverage. @volatile: set from the walk's EC thread, read on the actor
  // thread by the watchdog/completion/pivot gates.
  var verificationPassComplete: Boolean = false
  // Consecutive verification walks discarded because the heal root moved under them; reset by any walk of the current
  // root that completes. Bounded so a root that outruns the walk cannot livelock the heal.
  private var consecutiveStaleDiscards: Int = 0
  private val MaxConsecutiveStaleDiscards: Int = 3
  // Was `@volatile` under Classic: the walk's EC thread wrote it and the actor thread read it. Under Typed all
  // reads AND writes happen on the actor dispatcher — the field is set true on the actor thread before launching a
  // walk Future (startFrontierBFS/startVerificationBFS/startScopedVerification) and set false only inside the
  // FrontierRebuildComplete / VerificationBFSComplete / FrontierWalkFailed message handlers. The Future bodies never
  // touch it; they communicate completion via `selfRef ! …`. So a plain var is correct (constraint #6).
  var verificationBFSRunning: Boolean = false
  // Dead-loop watchdog (Fix BUG-2): consecutive HEAL-PULSE cycles with no walk/pending/active/healed.
  private var consecutiveDeadPulses: Int = 0

  // Active request tracking: maps requestId -> (tasks, peer, requestedBytes, sentAtMs)
  val activeRequests = mutable.Map[BigInt, ActiveRequest]()

  // Concurrency: per-peer limit (like StorageRangeCoordinator) + global safety cap
  val maxConcurrentRequests = concurrency
  var maxInFlightPerPeer: Int = 5

  // Statistics
  var totalNodesHealed: Int = 0
  var totalBytesReceived: Long = 0
  private val startTime = System.currentTimeMillis()

  // Adaptive healing throttle (geth p2p/msgrate alignment)
  // When pending nodes exceed 2× the processing rate, throttle increases (slow down requests).
  // When below, throttle decreases (speed up). Prevents pending queue overflow / OOM.
  var healRate: Double = 0.0 // items/sec EMA
  var lastThrottleAdjustMs: Long = System.currentTimeMillis()

  // Crash-recovery BFS: emit frontier in batches so healing starts before traversal completes.
  // go-ethereum trie.Sync.Missing() alignment — bounded working set rather than full upfront BFS.
  val FrontierBatchSize = 1000

  // Cap on the frontier-rebuild walk's `visited` set. The walk covers the full state trie (accounts +
  // every storage trie — tens of millions of nodes on ETC mainnet); an unbounded visited set grew
  // to ~2.9 GB and OOM-looped the node. A fixed-capacity FIFO/insertion-order set (NOT an LRU — it
  // evicts the earliest-INSERTED entry regardless of recent access) bounds the heap; budget for
  // ~cap × ~120-150 B (a 32-byte ByteString key + its wrapper + the LinkedHashMap entry), i.e. the
  // 4M default is ~480-640 MB, NOT the 320 MB an 80 B/entry estimate would suggest. Completeness is
  // preserved: an evicted present node is only RE-WALKED if reached again via a shared reference
  // (extra work, never a skip), and any missing node it re-discovers is de-duplicated by
  // `healRequests`. See docs/design/healing-frontier-scale.md. Operator-tunable via
  // `sync.snap-sync.healing-visited-cap`; raise it only from a measured `inflation_ratio` (US2) and
  // within the heap budget — do NOT set it to 20M (~2.4-3.2 GB) on a 6 GB heap (it OOMs).
  val HealingVisitedCap: Int = visitedCap
  val HealingTraversalParallelism: Int = traversalParallelism
  val HealingMinParallelism: Int = healingMinParallelism
  val HealingReservedCores: Int = healingReservedCores
  val bfsQueue: BfsQueueStorage = stateIngest.bfsQueue.getOrElse(new InMemoryBfsQueueStorage())
  // Track last known available peers for re-dispatch after failures
  val knownAvailablePeers = mutable.Set[Peer]()

  // spec 022 D2: the live heal requests, queued or in flight, keyed by trie position (Path) or node hash (Hash). A
  // node hash is fetched once however many requests wait on it, and the served node fills them all: under Path every
  // position that needs it (identical subtrees under two accounts), so no position is lost to a de-dup by hash. A
  // request stays live until its node arrives, so a parent that finds a child already in flight joins that fetch
  // instead of queueing a second one; under Hash each such parent is one more edge to the child. Replaces the
  // `pendingHashSet` de-dup set (pending only, by hash) and the Path `pathAliases` map.
  val healRequests: HealRequestBook = new HealRequestBook()

  // --- spec 003: scoped post-heal verification (FR-001) ---
  // Bounded, per-round, in-memory accumulator of the nodes HEALED this round (their HealingEntry,
  // captured at the single heal site in handleResponse). Mirrors the pendingTasks/healRequests
  // pairing: a LinkedHashMap keyed by request key (the node hash; the position under Path, spec 022)
  // dedups re-served/re-queued nodes while preserving the
  // authoritative HealingEntry value and a stable insertion order for deterministic seeding. The
  // completion gate uses this as the scope for the post-heal verification BFS (re-walking only the
  // healed subtrees) when the durable completeness marker proves full-trie coverage; otherwise it
  // falls back to full-root verification. NOT persisted (actor field state, like pendingTasks); a
  // restart loses it and the gate falls back to full-root (correct, slower). See spec 003 C1/C4.
  val healedPathsThisRound: mutable.LinkedHashMap[ByteString, HealingEntry] =
    mutable.LinkedHashMap.empty
  // The state root the current round's healed paths were healed against (FR-009 / F5 guard). Tagged on
  // the first capture of the round; a scoped verification is launched only when this == stateRoot.
  var healedPathsRoot: ByteString = ByteString.empty
  // Latched true once the round would exceed scopedHealMaxPaths (FR-011 / F4). Once set, no further
  // capture occurs and the gate falls back to full-root verification (which covers everything).
  var healedPathsOverflowed: Boolean = false

  // spec 003 C6/T016: observability state for the IN-FLIGHT scoped verification run. When a scoped
  // verification is launched, scopedVerificationActive is set with its start time and seed count so the
  // VerificationBFSComplete handler can emit the [HEAL-VERIFY-SCOPED] completion log + duration gauge.
  // Cleared (None) on the full-root path so the completion handler does not mis-attribute a full-root run.
  var scopedVerificationStartMs: Long = 0L
  var scopedVerificationSeedCount: Int = 0
  var scopedVerificationActive: Boolean = false

  // --- spec 005 (Pruned descend-and-stop verification, D5/T005) ---
  // Single scheme gate consulted at the verification entry and inside the per-child descent oracle: pruning is
  // effective ONLY when the config flag is on AND the node uses Hash addressing (the subtree-complete record keys on
  // the bare keccak hash — well-defined only under Hash scheme; under Path the nodes are nibble-path-keyed). Off OR
  // Path scheme ⇒ the unchanged full-trie walk (`startVerificationBFS`), byte-identical to today. A healing frontier
  // store must also be present for any record read/write — `prunedEnabled` is necessary but
  // `stateIngest.healingFrontier` is the actual record sink, so each record site additionally guards on it being
  // defined.
  val prunedEnabled: Boolean =
    prunedHealVerification && storageScheme == StorageScheme.Hash && stateIngest.healingFrontier.isDefined

  // spec 005 C8/FR-009: observability for the IN-FLIGHT pruned verification run — count of present subtrees pruned
  // (descend-and-stop hits) and the run start time, set when the verification entry takes the pruned path and read by
  // the VerificationBFSComplete handler to emit the [HEAL-VERIFY-PRUNED] completion log + duration/pruned-count gauges.
  var prunedVerificationActive: Boolean = false
  var prunedVerificationStartMs: Long = 0L
  // Pruned-subtree hits accumulated across the BFS sub-ranges of the current verification walk. AtomicLong because the
  // oracle fires on `healingReaderEc` sub-range threads (parallel levels). Reset at the start of each pruned walk.
  val prunedSubtreeCount: java.util.concurrent.atomic.AtomicLong =
    new java.util.concurrent.atomic.AtomicLong(0L)

  // --- spec 004: decoupled heal serve-root (FR-001/FR-002) ---
  // T008: the SERVE root used to fetch missing nodes (GetTrieNodes). Initialized to the walk root so that when
  // the feature is OFF (or before any serve-root has been obtained) the fetch is byte-identical to the coupled
  // path. Advances ONLY via the HealingServeRootRefresh handler; read ONLY at the fetch build site in
  // requestNextBatch (and only when `decoupledHealServeRoot` is true). The completeness walk and gate never read
  // it — they key off `stateRoot` (the walk root) exclusively, preserving FR-007 parity by construction.
  var serveRoot: ByteString = stateRoot
  // T015 / FR-006: per-task count of fetch attempts that did not satisfy the task (content-mismatch drop, empty
  // response, or timeout re-queue). Bounded by the pending-task set (an entry is only ever incremented for a hash
  // that is being re-queued, and the whole map is cleared on every serve-root advance). A task that exceeds
  // `decoupledHealMaxAttemptsNoRefresh` with no serve-root advance in between is surfaced (log + metric) — it is
  // NEVER force-completed: the content-hash check guarantees a wrong/missing node can never be accepted, so a stuck
  // node simply keeps the walk from finding zero, which is the correct (no-false-completion) behaviour.
  // A HealAttemptMap also keeps how many tasks are past that threshold, for the gauges (#1675).
  val healAttempts: HealAttemptMap = new HealAttemptMap(() => decoupledHealMaxAttemptsNoRefresh)
  // T019 / FR-010: count of nodes healed via a serve root that differs from the walk root (cross-root heals).
  var crossRootHealCount: Long = 0L
  // T020: number of serve-root refreshes engaged this coordinator lifetime (observability only).
  private var serveRootRefreshCount: Long = 0L

  // Stateless peer tracking (geth-aligned: peers that return empty TrieNodes for current root)
  val statelessPeers = mutable.Set[String]()
  // Soft-exile strike counter (mirrors AccountRangeCoordinator.emptyResponseStrikes). A SINGLE empty
  // TrieNodes response used to permanently exile a peer for the whole root, which on a 2-peer-eligible
  // residual drains the pool to zero and stalls the heal one node short (observed live: only 2 of ~10
  // connected peers ever tried). Instead we strike on each consecutive empty response and only exile at
  // the threshold; any successful heal from a peer wipes its strikes. Peers rotate — one that returns
  // empty now may serve after a refresh — so the set is also auto-cleared periodically (see
  // HealingStagnationCheck). 5 strikes mirrors Account/Storage's EmptyResponseStrikeThreshold.
  val emptyResponseStrikes = mutable.Map.empty[String, Int]
  var pivotRefreshRequested: Boolean = false
  var pivotRefreshRequestedAt: Long = 0L
  private val PivotRefreshWatchdogMs: Long = 15.minutes.toMillis

  val peerResponseBytesTarget = mutable.Map.empty[String, BigInt]

  // Peer cooldown
  val peerCooldownUntilMs = mutable.Map[String, Long]()

  // Batched raw node storage: accumulate nodes and flush asynchronously
  val rawNodeBuffer = mutable.ArrayBuffer[(ByteString, Array[Byte])]()
  val rawFlushThreshold = 1000
  var flushing: Boolean = false

  // spec 005 C3b/D2/T009/T012: heal-side subtree-complete seeding, DEFERRED until durable. discoverMissingChildren
  // identifies a candidate X (a just-healed node whose direct children are ALL present on disk AND themselves
  // recorded subtree-complete ⇒ X's whole subtree is present by induction). But X's OWN bytes are still in
  // `rawNodeBuffer` (NOT durable) at discovery time, so recording then would be unsound (crash before flush ⇒ record
  // present but X's bytes lost ⇒ false prune). Instead we stage candidates here and write the record ONLY in the
  // flush path, AFTER `mptStorage.persist()` durably commits the buffered nodes (T012 record-after-persist). A crash
  // before the flush drops the in-memory candidate ⇒ safe descend. Records are written iff `prunedEnabled`.
  val pendingSubtreeRecords = mutable.Set[ByteString]()

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

  /** Spec 016 C-H0 (T070): `activeBehavior`'s 26 arms, grouped by concern into partial functions (research.md R4c).
    * Each group keeps its arms in their original relative order. Every arm except the three `VerificationBFSComplete`
    * arms and the catch-all matches its own Command type and no other, so the first arm that matches a message is the
    * same arm as before for every message: the three `VerificationBFSComplete` arms sit together in
    * `healVerificationArms`, and the catch-all comes last. Groups are chained in the order of their first arm.
    */
  private def activeBehavior(): Behavior[Command] =
    Behaviors.receiveMessage[Command](
      healLifecycleArms
        .orElse(frontierRebuildArms)
        .orElse(healResponseArms)
        .orElse(healDispatchArms)
        .orElse(frontierPersistenceArms)
        .orElse(healVerificationArms)
        .orElse(unhandledCommandArms)
    )

  /** Heal lifecycle (stays in the core): start, force-complete, walk-root re-peg, the 2-minute stagnation pulse and the
    * progress query. Arms #1, #9, #10, #21, #22 of the spec 016 C-H0 arm-order table (research.md R4c).
    */
  private[actors] lazy val healLifecycleArms: PartialFunction[Command, Behavior[Command]] = {
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
        val frontierStore = stateIngest.healingFrontier
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
        val seedEntry = HealingEntry(Seq(emptyPath), root)
        if !healRequests.contains(healRequestFor(seedEntry).key) && !isNodeInStorage(root, Seq(emptyPath)) &&
          enqueueRequest(seedEntry)
        then
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
        healRequests.clear()
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
        // healRequests / activeRequests, below) plus the OPTIONAL frontier-mirror CF via clearPersistedFrontier(),
        // which operates solely on `stateIngest.healingFrontier` (CF 'g' — frontier mirror + completeness/subtree
        // records) and NEVER touches the trie-node store `mptStorage`. If a future edit makes a re-peg drop trie nodes
        // it is a consensus bug (a referenced node could go missing under the new root → false completion / import
        // fault).
        clearPersistedFrontier() // Layer 2: old-root frontier is stale after refresh — reseed (below) repopulates it
        clearHealedPathsSet() // spec 003 C1/F5: old-root healed paths are stale — clear before next gate
        pendingTasks.clear() // Will be re-populated by root reseed + inline discovery / trie walk from controller
        // spec 022 FR-007/FR-010: every request (and Path alias) of the old root goes; discovery from the new root finds
        // each position that still needs a node again, each as its own request.
        healRequests.clear()
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
        val reseedEntry = HealingEntry(Seq(pivotReseedPath), newStateRoot)
        if !healRequests.contains(healRequestFor(reseedEntry).key) &&
          !isNodeInStorage(newStateRoot, Seq(pivotReseedPath)) && enqueueRequest(reseedEntry)
        then
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
  }

  /** Frontier-rebuild BFS results and the crash-recovery full rebuild (C-H2 `FrontierRebuildBfs`). Arms #2, #3, #4, #25
    * (research.md R4c).
    */
  private[actors] lazy val frontierRebuildArms: PartialFunction[Command, Behavior[Command]] = {
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
        stateIngest.healingFrontier.foreach { store =>
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
  }

  /** Missing-node intake and peer responses (C-H5 `HealResponseProcessing`). Arms #5, #13, #15 (research.md R4c). */
  private[actors] lazy val healResponseArms: PartialFunction[Command, Behavior[Command]] = {
    case QueueMissingNodes(nodes) =>
      log.info(s"Queuing ${nodes.size} missing nodes for healing")
      queueNodes(nodes)
      // Immediately dispatch to any known available peers
      tryRedispatchPendingTasks()

      Behaviors.same

    case TrieNodesResponseMsg(response) =>
      handleResponse(response)

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
  }

  /** Peer pool, per-peer budget, serve root, resume and request timeouts (C-H4 `HealDispatch`). Arms #6, #7, #8, #11,
    * #12, #23 (research.md R4c).
    */
  private[actors] lazy val healDispatchArms: PartialFunction[Command, Behavior[Command]] = {
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
            // Back to the queue: each entry is the one fetch of a live request (`healRequests`), out of the queue
            // while it was in flight, so it is queued exactly once.
            pendingTasks ++= req.tasks
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

    case HealingRequestTimeout(requestId) =>
      // Timeout delivered via actor mailbox (BUG-H4 fix): runs on actor thread,
      // safe to read/write activeRequests and pendingTasks.
      activeRequests.get(requestId) match
        case Some(req) => handleTimeout(requestId, req.tasks, req.peer)
        case None      => // Response already arrived — stale timeout, nothing to do
      Behaviors.same
  }

  /** Async raw-node flush completion (C-H1 `FrontierPersistence`). Arm #14 (research.md R4c). */
  private[actors] lazy val frontierPersistenceArms: PartialFunction[Command, Behavior[Command]] = {
    case FlushComplete(count) =>
      flushing = false
      log.info(s"Async flush complete: $count healed nodes written to disk (total: $totalNodesHealed)")
      // Check if buffer filled up again during the flush
      if rawNodeBuffer.size >= rawFlushThreshold then flushRawNodesAsync()
      self ! HealingCheckCompletion

      Behaviors.same
  }

  /** The completion gate, the verification-walk results and the crash-recovery verification launch (C-H3
    * `HealVerification`). Arms #16–#20, #24 (research.md R4c). The three `VerificationBFSComplete` arms (#17 stale
    * root, #18 dirty pass, #19 clean pass) are the only Command with more than one arm; they stay together, in this
    * order, so their guards fall through exactly as before.
    */
  private[actors] lazy val healVerificationArms: PartialFunction[Command, Behavior[Command]] = {
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
            stateIngest.healingFrontier.foreach { store =>
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
              stateIngest.healingFrontier.exists(_.isComplete) && // F2/F6: full-coverage precondition proven
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

    case RestartResumeVerification(root, rootPath) =>
      // Marshalled back from the HEAL-RESTART crash-recovery Future so the walk launch runs on the actor thread.
      startVerificationBFS(root, rootPath)
      Behaviors.same
  }

  /** The catch-all (arm #26). Total, so it must stay last in `activeBehavior`'s chain. */
  private[actors] lazy val unhandledCommandArms: PartialFunction[Command, Behavior[Command]] = {
    // Defensive: `Command` is non-sealed (cross-file constraint via Messages.scala), so the compiler cannot prove
    // exhaustiveness. No production sender emits an un-handled Command; treat any as unhandled. HealingStagnated
    // (outbound to SSC) is NOT a Command, so it can never arrive here.
    case other =>
      log.debug(s"TrieNodeHealingCoordinator received unhandled command: $other")
      Behaviors.unhandled
  }

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

  // spec 016 C-H0: the Impl's actor-local messages and helper types, moved here from the Impl so the C-H1..C-H5
  // modules can name them (a module cannot name an Impl member type). None of them leaves the process.
  private[actors] case class HealingEntry(pathset: Seq[ByteString], hash: ByteString)
  private[actors] case class ActiveRequest(
      tasks: Seq[HealingEntry],
      peer: Peer,
      requestedBytes: BigInt,
      sentAtMs: Long = System.currentTimeMillis()
  )
  private[actors] case object HealingStagnationCheck extends Command

  // Internal messages — sent only via `selfRef ! …` from Future continuations / timers, so they must be Commands so
  // the Typed behavior can receive them. They were plain `Any` messages under Classic.
  // Internal message for async flush completion
  private[actors] case class FlushComplete(count: Int) extends Command

  // Internal message for async frontier rebuild completion (crash-recovery BFS or verification BFS)
  private[actors] case class FrontierRebuilt(entries: Seq[HealingEntry]) extends Command
  // Sent by startVerificationBFS when the BFS Future completes — gates verificationPassComplete.
  // Carries the root the walk was LAUNCHED against (`walkRoot`), exactly as `FrontierRebuildComplete` does. A pivot
  // re-peg does not cancel an in-flight verification walk, so its completion can land after `stateRoot` moved; the
  // handler must not credit a clean walk of the OLD root as verification of the NEW one. This is decisive for the
  // per-block-hot system-contract storage tries (EIP-4788/2935/8282): they differ between every pair of roots, while
  // the cold bulk of the state is shared, so an old-root "clean" signal says nothing about them.
  // `missingEmitted` is the number of missing nodes the walk found. A pass that found ANY is not a clean pass even if
  // every one of them has been healed by the time it ends: the walk never descended below a node that was missing when
  // it reached it, so the healed node's present children (and their subtrees) were not verified.
  private[actors] case class VerificationBFSComplete(walkRoot: ByteString, missingEmitted: Long) extends Command

  // Layer 2: the full-state rebuild BFS finished — the persisted frontier is now a COMPLETE snapshot.
  // Sent after the final FrontierRebuilt so the completeness marker is set only once every node is persisted.
  // spec 006 C1/D3/FR-004: carries the rebuild's outcome so the handler can decide early completion soundly.
  //   missingEmitted = the walk's frontierCount (number of missing nodes emitted); 0 ⇒ found everything present.
  //   walkRoot       = the root the finished walk traversed (captured at launch); compared against the live
  //                    stateRoot to exclude a stale completion after a pivot refresh (F-E).
  private[actors] case class FrontierRebuildComplete(missingEmitted: Long, walkRoot: ByteString) extends Command

  // A frontier walk Future died with an exception. Resets the walk flags WITHOUT setting any
  // completion marker, so the watchdog / HealingCheckCompletion gates can start a fresh walk.
  // Without this, an exception skips onComplete() and verificationBFSRunning stays true forever,
  // permanently blocking every future walk (including the watchdog) until restart.
  private[actors] case object FrontierWalkFailed extends Command

  // Routes the HEAL-RESTART resume decision back onto the actor thread. The crash-recovery Future (in
  // StartTrieNodeHealing) reads the persisted frontier off-thread, then sends ONE of these so the
  // verificationBFSRunning mutation + walk launch happen on the actor dispatcher (Typed: no off-thread state
  // mutation). Under Classic the Future called start*BFS directly — safe only because verificationBFSRunning was
  // @volatile. Now @volatile is gone, so the launch must be marshalled back to the actor thread.
  private[actors] case class RestartResumeVerification(root: ByteString, rootPath: ByteString) extends Command
  private[actors] case class RestartFullRebuild(root: ByteString, rootPath: ByteString) extends Command

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
      stateIngest: StateIngest,
      healingWriterEcOverride: Option[ExecutionContext] = None,
      healingReaderEcOverride: Option[ExecutionContext] = None,
      traversalParallelism: Int = DefaultBfsParallelism,
      healingMinParallelism: Int = DefaultMinParallelism,
      healingReservedCores: Int = DefaultReservedCores,
      storageScheme: StorageScheme = StorageScheme.Hash,
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
      healedCodeCheck: Boolean = false,
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
          stateIngest = stateIngest,
          healingWriterEcOverride = healingWriterEcOverride,
          healingReaderEcOverride = healingReaderEcOverride,
          traversalParallelism = traversalParallelism,
          healingMinParallelism = healingMinParallelism,
          healingReservedCores = healingReservedCores,
          storageScheme = storageScheme,
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
          healedCodeCheck = healedCodeCheck,
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
