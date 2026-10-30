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
import com.chipprbots.ethereum.blockchain.sync.snap.heal.ExcludedAccountSeeding
import com.chipprbots.ethereum.blockchain.sync.snap.heal.FlushBuffer
import com.chipprbots.ethereum.blockchain.sync.snap.heal.FlushTicket
import com.chipprbots.ethereum.blockchain.sync.snap.heal.HealRequest
import com.chipprbots.ethereum.blockchain.sync.snap.heal.HealRequestBook
import com.chipprbots.ethereum.blockchain.sync.snap.heal.HealTaskQueue
import com.chipprbots.ethereum.blockchain.sync.snap.heal.HeldNodeSet
import com.chipprbots.ethereum.db.storage.BfsQueueStorage
import com.chipprbots.ethereum.db.storage.ExcludedAccount
import com.chipprbots.ethereum.db.storage.ExcludedReason
import com.chipprbots.ethereum.db.storage.InMemoryBfsQueueStorage
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.mpt.MptNode
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
    walkLocalOnly: Option[java.util.concurrent.atomic.AtomicBoolean] = None,
    // spec 022 D1: the held-memory bound, in bytes (held nodes, the flush buffer and the live requests). At it, no new
    // subtree is opened; fetches that let held nodes commit continue. `sync.snap-sync.healing.held-memory-limit`.
    heldMemoryLimit: Long = TrieNodeHealingCoordinator.DefaultHeldMemoryLimit,
    // spec 022 D5: whether this coordinator works the durable excluded set (`stateIngest.snapProgress`). The production
    // spawn passes true; false (bare construction, most specs) never reads the store.
    excludedAccounts: Boolean = false
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

  // Task management — each task has a pathset (for GetTrieNodes) and a hash (for verification). One queued fetch per
  // node hash (`healRequests` decides when one is needed). spec 022 D1: dispatched deepest first (the depth of the
  // fetch's path), FIFO among equal depths; a fetch is classed when queued as freeing held memory (a request waiting
  // on it has a parent edge, i.e. a held parent) or opening a new subtree, for the held-memory pause policy.
  val pendingTasks: HealTaskQueue[HealingEntry] =
    new HealTaskQueue[HealingEntry](
      e => HealRequest.depthOf(e.pathset),
      _.hash,
      e => healRequests.fetchFreesHeld(e.hash)
    )
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
  // spec 022 review (hunter W1, forge W-r4-2, vault-r3 W1, beacon W1-r4): the token of the last walk this incarnation
  // launched (its `WalkLease` token, set with `verificationBFSRunning`). A walk's end carries its token and is taken only
  // when it matches: a walk another incarnation launched (a supervisor restart keeps the ActorRef, and a walk outlives
  // its incarnation) says nothing about this incarnation's root or scope. Per incarnation: a restart forgets it.
  var currentWalk: Option[Long] = None
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

  // spec 022 D3 (FR-001 to FR-004): served nodes whose subtree is not all present yet. Never on disk; committed with
  // every ancestor they complete, in one atomic write, once their last child edge closes. Dropped on a re-peg or a
  // force-complete, after what is ready has been committed.
  val heldNodes: HeldNodeSet = new HeldNodeSet()

  // spec 022 D1: the held-memory pause, with its last state for the transition log.
  private var newSubtreesPausedLast: Boolean = false

  /** Whether opening new subtrees is paused: something is held, the held nodes, the flush buffer and the live requests
    * (forge N4) together reach `heldMemoryLimit`, and something can still free memory: a queued fetch below a held node
    * or a fetch in flight (spec 022 review beacon W1, hunter W2). Fetches that let held nodes commit are never paused,
    * which is what keeps the bound from deadlocking; with nothing held, nothing is paused (a backlog of requests alone
    * does not shrink by waiting); and held nodes that nothing can complete any more (orphans) do not pause either: the
    * new subtrees go out, and the orphan guard drops them. The bound is therefore soft: the subtrees already open run
    * to completion, deepest first.
    */
  def newSubtreesPaused: Boolean =
    val bytes = heldMemoryBytes
    val paused = HeldNodeSet.pausesNewSubtrees(
      held = heldNodes.nonEmpty,
      bytes = bytes,
      limit = heldMemoryLimit,
      // Also a commit write to retry and a bytecode on its way: each ends with held nodes committed (spec 022 review
      // hunter W2; D4).
      canFree = pendingTasks.hasDispatchable(paused = true) || activeRequests.nonEmpty ||
        heldNodes.hasPendingWrites || heldNodes.awaitingCode
    )
    if paused != newSubtreesPausedLast then
      newSubtreesPausedLast = paused
      if paused then
        log.info(
          s"[HEAL-HELD] Held memory ${bytes / 1024} KiB reached the ${heldMemoryLimit / 1024} KiB limit " +
            s"(${heldNodes.size} held, ${rawNodeBuffer.size} buffered, ${healRequests.size} requests) — no new " +
            s"subtree is opened until held nodes commit; their children are still fetched"
        )
      else if bytes >= heldMemoryLimit && heldNodes.nonEmpty then
        log.info(
          s"[HEAL-HELD] Held memory ${bytes / 1024} KiB is still at the limit, but nothing queued or in flight can " +
            "complete the held nodes — new subtrees are opened again (the orphan guard drops the held nodes)"
        )
      else log.info(s"[HEAL-HELD] Held memory ${bytes / 1024} KiB under the limit — new subtrees are opened again")
    paused

  /** Estimated heap of what healing holds before it is durable: held nodes, the flush buffer and the live requests. */
  def heldMemoryBytes: Long = heldNodes.bytes + rawNodeBuffer.bytes + healRequests.bytes

  // --- spec 022 D5, D4: the durable excluded set and the bytecode healing waits on ---
  // The durable set itself is the truth: this coordinator holds no entry beyond a page (`ExcludedAccountSeeding`). `None`
  // in bare construction, which never reads the store.
  private val excludedSeeding: Option[ExcludedAccountSeeding] =
    Option.when(excludedAccounts)(
      new ExcludedAccountSeeding(stateIngest.snapProgress, readForExcluded, h => stateIngest.bytecode.get(h).isDefined)
    )
  // Whether the completion gate last stayed shut on the set. What a tick looks at, with `heldNodes.awaitingCode`.
  private var excludedBlocking: Boolean = false
  // Code hashes the set's entries asked for since the root last moved: asked for once a pass, and again each tick.
  private val excludedCodeAsked = mutable.LinkedHashSet.empty[ByteString]
  // Minutes healing has spent blocked on the above with no fetch that could help, since a blocker last shrank. Not reset
  // by a re-peg or by a heal, and a walk does not stop the count (review W-1): on a live chain those come far more often
  // than this could ever fill up between them.
  private var blockedTicks: Int = 0
  private var blockedProgress: Boolean = false
  // The user's decision, 2026-10-30 (plan D4): only code blocked for `MaxBlockedTicks`, so the chains waiting on it are
  // written. From here until the root moves the gate walks and hands off lazily. It can still complete, but only if the
  // code turns up and the set empties. `codeHandOffSent` keeps it to one message per tick.
  private var codeHandOffArmed: Boolean = false
  private var codeHandOffSent: Boolean = false
  // The last tick's page of the set could not be resolved, so nothing is known about what it wants.
  private var excludedTickFailed: Boolean = false
  // spec 022 review (T038c): consecutive commit writes that failed (vault V6).
  var consecutiveCommitFailures: Int = 0
  // spec 022 review hunter-r3 N1, vault-r3 N4: pulses the give-up latch has waited for an answer.
  private var giveUpLatchPulses: Int = 0
  // spec 022 T038c (the A-B-A marker, forge N-r4-1, hunter N2): the walk this incarnation no longer counts, by token.
  private var invalidatedWalk: Option[Long] = None

  /** What was true when the running walk started may no longer be: a re-peg has passed under it (even back to the same
    * root: A -> B -> A dropped its frontier twice, and under Path may have overwritten what it read), or a node known
    * missing was dropped, which its scope may not cover. Its end counts for nothing but itself: no marker, no clean
    * pass.
    */
  def invalidateRunningWalk(): Unit =
    if verificationBFSRunning then invalidatedWalk = currentWalk

  private def walkInvalidated: Boolean = invalidatedWalk.isDefined && invalidatedWalk == currentWalk

  /** The node a walk of the current root would read: by hash under Hash, at its own position, hash-checked, under Path.
    * A read that fails throws: the entry stays, and the next pass tries again.
    */
  private def readForExcluded(hash: ByteString, pathset: Seq[ByteString]): Option[MptNode] =
    storageScheme match
      case StorageScheme.Hash =>
        try Some(mptStorage.get(hash.toArray))
        catch case _: MerklePatriciaTrie.MissingNodeException => None
      case StorageScheme.Path => readPathVerifiedNode(hash, pathset)

  /** spec 022 T034, Path, at heal start: delete every account-trie position on each excluded account's key, so healing
    * reaches it from the root and the invariant holds structurally. In batches of whole keys, so at no point is a node
    * left present over a child this removed; a crash re-runs it (the set is durable, the deletes idempotent). A failure
    * is logged and costs the structural guarantee only: the entries still resolve against the root, and seed.
    */
  private def deleteExcludedPositions(seeding: ExcludedAccountSeeding): Unit =
    if storageScheme == StorageScheme.Path then
      stateIngest.pathNodes.foreach { pns =>
        try
          val accounts = seeding.deleteAccountPositions(pns.writeNodesWithDeletes(Nil, _))
          if accounts > 0 then
            log.info(s"[HEAL-EXCLUDED] Deleted the account-trie positions of $accounts excluded account(s)")
        catch
          case scala.util.control.NonFatal(e) =>
            log.error("[HEAL-EXCLUDED] Could not delete the positions of the excluded accounts", e)
      }

  /** One page of the excluded set against the current root: what is complete leaves the set, what is absent is queued
    * or asked for. Its heal's responses send the next check; with nothing in flight, a check goes out while the pass
    * has pages left or removed something (the next look may find the set empty). With nothing changed and no page left
    * it stops: only a code write or the tick can change anything, and each sends a check.
    */
  private def stepExcluded(seeding: ExcludedAccountSeeding): Unit =
    val step = seeding.step(stateRoot)
    if step.removed > 0 || step.demoted > 0 then blockedProgress = true
    if step.seeds.nonEmpty then
      queueNodes(step.seeds)
      tryRedispatchPendingTasks()
    val newCode = step.missingCode.filter(excludedCodeAsked.add)
    if newCode.nonEmpty then snapSyncController ! SNAPSyncController.HealedCodeHashes(newCode)
    if pendingTasks.isEmpty && activeRequests.isEmpty && (step.more || step.changed || !seeding.blocking) then
      self ! HealingCheckCompletion

  /** spec 022 T035, on every adopted root: start the pass over, against it. A failure is logged, and the gate tries
    * again.
    */
  private def adoptRootForExcluded(): Unit =
    excludedSeeding.foreach { seeding =>
      seeding.restart()
      excludedCodeAsked.clear()
      try
        if seeding.blocking then
          excludedBlocking = true
          stepExcluded(seeding)
      catch
        case scala.util.control.NonFatal(e) =>
          excludedBlocking = true
          log.error(
            "[HEAL-EXCLUDED] Could not resolve the excluded set against the new root; the next check tries again",
            e
          )
    }

  /** spec 022 FR-011, the excluded set's part of the completion gate: healing may not complete while an entry counts,
    * nor walk towards completing, unless the code hand-off is armed. Called only with everything drained and flushed. A
    * page is worked whenever the gate stays shut (its work, or its self-check, comes back here); a set that cannot be
    * read shuts the gate (the tick tries again).
    */
  private def excludedGate(): ExcludedGate =
    excludedSeeding.fold(ExcludedGate.Clear) { seeding =>
      try
        excludedBlocking = seeding.blocking
        if !excludedBlocking then ExcludedGate.Clear
        else
          stepExcluded(seeding)
          // Only a pass that found nothing but code, and only if that page queued nothing: the walk starts drained.
          if codeHandOffArmed && seeding.onlyCodeOutstanding && pendingTasks.isEmpty && activeRequests.isEmpty then
            ExcludedGate.HandOff
          else ExcludedGate.Held
      catch
        case scala.util.control.NonFatal(e) =>
          excludedBlocking = true
          log.error(
            "[HEAL-EXCLUDED] Could not read the excluded set: no completion until it can be; retried each tick",
            e
          )
          ExcludedGate.Held
    }

  /** What is blocking healing, looked at only once it has been blocked for `MaxBlockedTicks` idle minutes. `CodeOnly`
    * means every one of these holds:
    *   - nothing is queued or in flight, no commit or flush is failing;
    *   - every held node would commit if its code waits closed (`HeldNodeSet.onlyCodeBlocks`): none waits on an absent
    *     child;
    *   - the set wants code and nothing else at this root: a pass has reached its end here, seeded nothing and read
    *     everything (`ExcludedAccountSeeding.onlyCodeOutstanding`), or it wants nothing.
    * `Unknown` is a pass that has not ended at this root yet: the next tick looks again. Anything else, a set that
    * cannot be read included, is `Other`. A coordinator with no set has no durable record to commit on, so it is
    * `Other` too.
    */
  private def blockerKind(): BlockerKind =
    if pendingTasks.nonEmpty || activeRequests.nonEmpty || heldNodes.hasPendingWrites || !flushedForWalk() ||
      (heldNodes.nonEmpty && !heldNodes.onlyCodeBlocks) || excludedTickFailed
    then BlockerKind.Other
    else
      excludedSeeding.fold(BlockerKind.Other) { seeding =>
        try
          if !seeding.blocking then if heldNodes.awaitingCode then BlockerKind.CodeOnly else BlockerKind.Unknown
          else if !seeding.passEnded then BlockerKind.Unknown
          else if seeding.onlyCodeOutstanding then BlockerKind.CodeOnly
          else BlockerKind.Other
        catch
          case scala.util.control.NonFatal(e) =>
            log.error("[HEAL-EXCLUDED] Could not read the excluded set, so it is not known to want only code", e)
            BlockerKind.Other
      }

  private def continueExcludedPass(): Unit =
    excludedSeeding.foreach { seeding =>
      try stepExcluded(seeding)
      catch
        case scala.util.control.NonFatal(e) =>
          log.error("[HEAL-EXCLUDED] Could not go on with the excluded set; the next tick tries again", e)
    }

  /** The user's decision, 2026-10-30 (plan D4): only code has blocked healing for `MaxBlockedTicks` idle minutes.
    * Record every code hash the held chains wait on, durably and first, so that from here the gate cannot open, even
    * after a crash, a re-peg or a dropped node, until the code is really there. Then close those waits and write the
    * chains. A record that cannot be written changes nothing, and the next tick tries again.
    */
  private def armCodeHandOff(): Unit =
    val awaited = heldNodes.awaitedCode.keys.toSeq
    try
      stateIngest.snapProgress.recordExcluded(awaited.map(ExcludedAccount(_, ExcludedReason.CodeMissing)))
      log.warn(
        s"[HEAL-CODE] Only bytecode has blocked healing for $blockedTicks minutes: writing ${heldNodes.size} node(s) " +
          s"waiting only on ${awaited.size} bytecode(s), then a walk of the rest, then a lazy hand-off. Healing does " +
          "not report complete; the head fetches the code on demand and the next start scans for it."
      )
      codeHandOffArmed = true
      excludedBlocking = true
      awaited.foreach(heldNodes.codeArrived)
      excludedCodeAsked ++= awaited
      commitHeld()
      if rawNodeBuffer.nonEmpty then flushRawNodesAsync()
    catch
      case scala.util.control.NonFatal(e) =>
        log.error(
          "[HEAL-CODE] Could not record the bytecode healing waits on; nothing written, the next tick retries",
          e
        )

  /** The armed gate's end: a clean walk with only code left. Never a completion, and no marker. */
  private def handOffOnCode(): Unit =
    if !codeHandOffSent then
      codeHandOffSent = true
      log.warn("[HEAL-CODE] The walk found nothing else missing: a lazy hand-off with the bytecode still missing")
      snapSyncController ! SNAPSyncController.StateHealingAbandoned

  /** Blocked for `MaxBlockedTicks` idle minutes (FR-006, T037). */
  private def actOnLongBlock(waitingOn: Int): Unit =
    blockerKind() match
      case BlockerKind.Unknown =>
        log.info(s"[HEAL-CODE] Blocked for $blockedTicks minutes, and the set's pass has not ended at this root yet")
      case BlockerKind.CodeOnly =>
        if !codeHandOffArmed then armCodeHandOff()
      case BlockerKind.Other =>
        log.error(
          s"[HEAL-CODE] $blockedTicks minutes with no progress and healing blocked ($waitingOn bytecode(s) missing, " +
            s"excluded set blocking: $excludedBlocking) by more than bytecode — abandoning healing: the lazy hand-off, " +
            "not a completion"
        )
        blockedTicks = 0
        snapSyncController ! SNAPSyncController.StateHealingAbandoned

  /** spec 022 T037: once a minute. If bytecode or the excluded set holds healing up: find any code whose
    * acknowledgement was lost, log what it waits on and ask for it again, look at the set again (the gate cannot while
    * a held chain or a walk keeps it shut), and count the minute unless a fetch could still help. Only a blocker
    * shrinking resets the count: a re-peg and the heal after it do not, nor does a walk stop it (review W-1).
    */
  private def onBlockedTick(): Unit =
    // Any lost acknowledgement (a bytecode coordinator that a supervisor restarted, say).
    if heldNodes.awaitingCode || excludedCodeAsked.nonEmpty then
      try
        val stored =
          (heldNodes.awaitedCode.keys ++ excludedCodeAsked).filter(h => stateIngest.bytecode.get(h).isDefined)
        if stored.nonEmpty then onCodeStored(stored.toSeq)
      catch
        case scala.util.control.NonFatal(e) =>
          log.warn(s"[HEAL-CODE] Could not read the bytecode store: ${e.getMessage}")
    val wasBlocking = excludedBlocking
    // With nothing queued the gate may be shut for hours (a held chain, a walk), and the bytecode download records
    // entries of its own meanwhile, so the tick does not wait for the gate to look.
    if pendingTasks.isEmpty && activeRequests.isEmpty then
      excludedSeeding.foreach { seeding =>
        try
          excludedBlocking = seeding.blocking
          if excludedBlocking then stepExcluded(seeding)
          excludedTickFailed = false
        catch
          case scala.util.control.NonFatal(e) =>
            excludedBlocking = true
            excludedTickFailed = true
            log.error("[HEAL-EXCLUDED] Could not look at the excluded set; the next tick tries again", e)
      }
    if heldNodes.awaitingCode || excludedBlocking then
      val waiting = heldNodes.awaitedCode
      val askFor = (waiting.keys ++ excludedCodeAsked).toSeq.distinct
      if askFor.nonEmpty then
        val shown = waiting.iterator.take(16).map { case (code, accounts) =>
          s"${Hex.toHexString(code.take(4).toArray)} of ${accounts.map(a => Hex.toHexString(a.take(8).toArray)).mkString(",")}"
        }
        log.warn(
          s"[HEAL-CODE] Healing waits on ${askFor.size} bytecode(s), no-progress tick $blockedTicks/$MaxBlockedTicks: " +
            shown.mkString("; ")
        )
        snapSyncController ! SNAPSyncController.HealedCodeHashes(askFor)
      // A fetch that can still go out is the normal stagnation paths' business, not this one's.
      val fetchCanHelp =
        activeRequests.nonEmpty ||
          (pendingTasks.nonEmpty && (!newSubtreesPaused || pendingTasks.hasDispatchable(paused = true)))
      if blockedProgress then blockedTicks = 0
      else if !fetchCanHelp then blockedTicks += 1
      codeHandOffSent = false // a refresh that keeps the root answers nothing: the gate says it again
      if blockedTicks >= MaxBlockedTicks && !fetchCanHelp then actOnLongBlock(askFor.size)
      self ! HealingCheckCompletion // the look at the set, and at whether the wait has ended
    else if wasBlocking then self ! HealingCheckCompletion // the set is out of the way: nothing else would say so
    blockedProgress = false

  /** The bytecode of `codeHashes` has been written (D4). What waited on it commits; the set, on its next look, sees it.
    */
  private def onCodeStored(codeHashes: Seq[ByteString]): Unit =
    val released = codeHashes.map(heldNodes.codeArrived).sum
    val forTheSet = codeHashes.count(excludedCodeAsked.remove)
    if released > 0 || forTheSet > 0 then
      blockedProgress = true
      commitHeld()
      if rawNodeBuffer.nonEmpty then flushRawNodesAsync()
      self ! HealingCheckCompletion

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
  // response, or timeout re-queue). Bounded by the live requests (an entry is only ever incremented for a hash that is
  // being re-queued, dropped once the task is satisfied or given up on, and the whole map is cleared on every serve-root
  // advance and whenever the requests go: `pruneFetchCounters`, spec 022 review hunter-r3 N2; a fill put back after a
  // failed read keeps it; a request a conflicting one retired leaves its entry until then). A task that exceeds
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
  // spec 022 review beacon N2-r3, hunter-r3 N1: a read-failure give-up escalates once per controller answer. Its own
  // latch, not `pivotRefreshRequested`: that one stops every dispatch, and an answer that never comes or leaves it set
  // would freeze unrelated fetches until the watchdog above. Cleared when the controller answers (resume or refresh).
  var giveUpEscalated: Boolean = false

  val peerResponseBytesTarget = mutable.Map.empty[String, BigInt]

  // Peer cooldown
  val peerCooldownUntilMs = mutable.Map[String, Long]()

  // Batched raw node storage: accumulate nodes and flush asynchronously
  // spec 022 D3: nodes committed with their whole subtree, waiting for the asynchronous flush (Hash scheme).
  val rawNodeBuffer: FlushBuffer = new FlushBuffer()
  val rawFlushThreshold = 1000
  // spec 022 review forge C1: the asynchronous flush in flight, by its ticket (a token unique in the process, so a late
  // completion from another flush, or from the instance a supervisor restart replaced, matches nothing). `flushing` is
  // whether it is set.
  var flushInFlight: Option[FlushTicket] = None

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
    timers.startTimerWithFixedDelay(HealingBlockedTick, 1.minute)
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
          // spec 022 T038a: this incarnation is ending, so nobody will read its walk's result. It stops at its next check
          // rather than holding the BFS column family for what may be hours.
          if signal == org.apache.pekko.actor.typed.PostStop || signal == org.apache.pekko.actor.typed.PreRestart then
            cancelRunningWalk()
          target(ctx, signal)
    )(activeBehavior())

  /** Spec 016 C-H0 (T070): `activeBehavior`'s 26 arms, grouped by concern into partial functions (research.md R4c).
    * Each group keeps its arms in their original relative order. Every arm except the three `VerificationBFSComplete`
    * arms and the catch-all matches its own Command type and no other, so the first arm that matches a message is the
    * same arm as before for every message: the three `VerificationBFSComplete` arms sit together in
    * `healVerificationArms`, and the catch-all comes last. Groups are chained in the order of their first arm. Spec 022
    * fix round 4 puts a replaced-walk arm first among the arms of each walk end (`FrontierRebuildComplete`,
    * `FrontierWalkFailed`, `VerificationBFSComplete`), in the same group: it takes only an end whose walk this
    * incarnation did not launch.
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

  /** Drop every held node, unwritten (a re-peg or a force-complete: its subtree is not all present), after the commit
    * the caller made; Hash: their persisted frontier entries go too (spec 022 review vault V5). A commit that failed
    * leaves ready nodes and pending deletes: they are dropped as well, unwritten (vault V6: said at WARN). Discovery
    * from the root finds again whatever the root still needs, deletes included (hash-checked presence).
    */
  private def dropHeld(reason: String): Unit =
    if heldNodes.hasPendingWrites then
      log.warn(
        s"[HEAL-HELD] $reason: the last commit write failed; ${heldNodes.readyCount} ready node(s) and the pending " +
          "deletes are dropped unwritten (discovery finds again what the root still needs)"
      )
    val dropped = heldNodes.heldHashes
    heldNodes.clear()
    unpersistDroppedHeld(dropped)
    if dropped.nonEmpty then log.info(s"[HEAL-HELD] $reason: dropped ${dropped.size} held node(s), never written")

  /** spec 022 review (hunter W1, forge W-r4-2, vault-r3 W1, beacon W1-r4): the end of a walk this incarnation did not
    * launch, `walk`: a walk of an incarnation a supervisor restart replaced, which ran on in the shared BFS queue and
    * reports to the same ActorRef. Its verdict says nothing about this incarnation's root or scope, so it changes
    * nothing here: no flag, no clean pass, no marker. It gave the queue's lease back before reporting, so a walk this
    * incarnation could not start meanwhile can start now: the completion check decides.
    */
  private def ignoreReplacedWalk(walk: Long, what: String): Unit =
    log.info(
      s"[HEAL-BFS] Ignoring the end of $what (walk $walk): a replaced incarnation of this coordinator launched it"
    )
    self ! HealingCheckCompletion

  /** spec 022 review (beacon W1, hunter W2, forge W1): drop the held nodes nothing can complete any more. A held node's
    * count goes down only when a child's subtree commits, and a child arrives only through a fetch queued below a held
    * node or one in flight. With neither (and no commit pending), the held nodes are orphans: a child was lost (it did
    * not decode, or its edge was dropped), and no fetch is coming. Waiting for the whole queue to drain first would let
    * them keep new subtrees paused at the cap (beacon W1); so they are dropped as soon as nothing can free them, never
    * written (nothing on disk is wrong), and the verification walk finds them missing again. With nothing queued
    * either, every live request is orphaned too (live means queued or in flight), and the book is cleared.
    *
    * Their persisted frontier entries stay (vault R2, hunter N3, forge F3): the root has not moved, so the completeness
    * marker may stand, and an entry is what lets a resume from it fetch a dropped node again. The walk persists them
    * again anyway (idempotent), and a flush removes each once its node is written. Only a re-peg or a force-complete,
    * which clear the marker too, remove the entries of the held nodes they drop (`dropHeld`).
    */
  private def dropOrphanedHeld(): Unit =
    // A leaf waiting on its bytecode is not an orphan: the code's arrival, or the tick's give-up, ends the wait.
    if heldNodes.nonEmpty && !heldNodes.hasPendingWrites && !heldNodes.awaitingCode && activeRequests.isEmpty &&
      !pendingTasks.hasDispatchable(paused = true)
    then
      val dropped = heldNodes.heldHashes
      log.error(
        s"[HEAL-HELD] ${dropped.size} held node(s) wait on children that nothing queued or in flight can supply — " +
          s"dropping them, never written (${pendingTasks.size} new-subtree fetch(es) stay queued); the verification " +
          "walk finds them again"
      )
      heldNodes.clear()
      // hunter N2: nodes known missing were dropped, so no earlier clean pass, nor a walk running now, vouches for
      // completion. The next walk finds them, if the current root still needs them.
      verificationPassComplete = false
      invalidateRunningWalk()
      if pendingTasks.isEmpty && !healRequests.isEmpty then
        log.error(s"[HEAL-HELD] ${healRequests.size} live request(s) are neither queued nor in flight — dropping them")
        healRequests.clear()
        pruneFetchCounters()

  /** Heal lifecycle (stays in the core): start, force-complete, walk-root re-peg, the 2-minute stagnation pulse and the
    * progress query. Arms #1, #9, #10, #21, #22 of the spec 016 C-H0 arm-order table (research.md R4c).
    */
  private[actors] lazy val healLifecycleArms: PartialFunction[Command, Behavior[Command]] = {
    case StartTrieNodeHealing(root) =>
      // spec 022 T034: before the root's presence is looked at: on Path, an excluded account's key runs through the root.
      excludedSeeding.foreach(deleteExcludedPositions)
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
                // spec 022 review vault-r3 W3: the fallback is marker-absent, as every other fallback here is. A marker
                // left over entries nobody read would let the completion gate scope its walk by it if the rebuild does
                // not run (a failed flush defers it). Removed synced, before the rebuild is sent (as at a re-peg, V-R5).
                // If the removal fails too, the rebuild still runs: a stalled restart is worse, and the store is failing.
                try store.clearComplete()
                catch
                  case NonFatal(c) =>
                    asyncLog.error("[HEAL-RESTART] Could not remove the completeness marker of that frontier either", c)
                None
          }
          // Route the resume decision back to the actor thread: the start*BFS launchers mutate verificationBFSRunning
          // and spawn more Futures, which must happen on the actor dispatcher (Typed). FrontierRebuilt is already a
          // message, so the entries arm sends directly.
          resumed match
            case Some(entries) if entries.nonEmpty =>
              selfRef ! FrontierRebuilt(entries, root)
            case Some(_) =>
              // Complete-and-empty: rebuild already proven done; verification alone decides completion.
              selfRef ! RestartResumeVerification(root, emptyPath)
            case None =>
              // Mark the persisted frontier authoritative when the full walk is done (all workers complete).
              selfRef ! RestartFullRebuild(root, emptyPath)
        }(ec)
        adoptRootForExcluded()
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
        adoptRootForExcluded()
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
        // Layer 2: healing abandoned — drop the persisted frontier so a restart won't resume it. First, so the
        // completeness marker is gone (synced) before any entry is deleted below (spec 022 review vault V-R5).
        clearPersistedFrontier()
        // spec 022 T025: commit what is complete, flush it (it was never flushed before the stop), drop the rest. A
        // held node's subtree is not all present, so it is never written.
        commitHeld()
        flushRawNodesSync()
        dropHeld("Force-complete")
        activeRequests.keys.foreach(requestTracker.completeRequest(_, 0))
        activeRequests.clear()
        pendingTasks.clear()
        healRequests.clear()
        pruneFetchCounters()
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
      giveUpEscalated = false // the controller has answered (beacon N2-r3): a later give-up may escalate again
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
        invalidateRunningWalk() // T038c: even if the root comes back to the walk's, its frontier was dropped under it
        // The hand-off was for the root just left. The count stands: the next idle minute looks again from this one.
        codeHandOffArmed = false
        codeHandOffSent = false
        // Layer 2: the old-root frontier is stale after the refresh (the reseed below repopulates it). Invalidated
        // first, so the completeness marker is gone (synced) before the flush and `dropHeld` delete any entry (spec 022
        // review vault V-R5). It reads only the pending and in-flight sets, which nothing before it here changes.
        clearPersistedFrontier()
        // spec 022 T025: commit what is complete (with the stale positions already found) and flush it, then drop every
        // held node: its subtree is not all present, so it is never written. Discovery from the new root finds again
        // whatever the new root still needs.
        commitHeld()
        flushRawNodesSync()
        dropHeld("Re-peg")
        pruneFetchCounters()
        // spec 009 T010/C4 — RE-PEG-RETAINS-NODES INVARIANT (consensus-load-bearing): a re-peg MUST NOT delete any
        // persisted trie node. Content-addressed verified nodes (keccak-keyed) carry over unchanged under the new root
        // (~99.9% shared), so every node healed against the old root stays valid under `newStateRoot` and the
        // post-re-peg delta only SHRINKS. The ONLY state cleared on this path is in-memory frontier (pendingTasks /
        // healRequests / activeRequests, below) plus the OPTIONAL frontier-mirror CF via clearPersistedFrontier()
        // (above), which operates solely on `stateIngest.healingFrontier` (CF 'g' — frontier mirror + completeness/subtree
        // records) and NEVER touches the trie-node store `mptStorage`. If a future edit makes a re-peg drop trie nodes
        // it is a consensus bug (a referenced node could go missing under the new root → false completion / import
        // fault).
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
          // spec 022 review forge F1: not while the flush above failed. The old root's buffered nodes are mostly the new
          // root's too, and a walk would read them as missing; startVerificationBFS retries the write first, and if it
          // fails again defers the walk to the completion check (verificationPassComplete is false: it walks).
          startVerificationBFS(newStateRoot, pivotReseedPath)
        adoptRootForExcluded() // spec 022 T035: every adopted root

      Behaviors.same

    case HealedCodeStored(codeHashes) =>
      onCodeStored(codeHashes)
      Behaviors.same

    case HealingBlockedTick =>
      onBlockedTick()
      Behaviors.same

    case HealingStagnationCheck =>
      emitHealingFrontierGauges() // refresh backlog/in-flight gauges even when idle
      // spec 022 review hunter W1: a commit or flush whose write failed is retried by the next one, and the last one by
      // the completion check right after it; if that fails too and nothing is pending or in flight, no later commit or
      // flush comes. Retry it here, every pulse, while unwritten nodes remain (a failure is logged each time). The
      // asynchronous flush runs whether or not healing looks complete (forge F1): with nothing pending a verification
      // walk may be the only other actor, and no walk starts over an unwritten buffer, so this is what writes it.
      if heldNodes.hasPendingWrites || (rawNodeBuffer.nonEmpty && !flushing) then
        log.warn(
          s"[HEAL-COMMIT] Retrying unwritten healed nodes: ${heldNodes.readyCount} ready to commit, " +
            s"${rawNodeBuffer.size} buffered for the flush"
        )
        if rawNodeBuffer.nonEmpty && !flushing then flushRawNodesAsync()
        self ! HealingCheckCompletion
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
          s"pending=${pendingTasks.size} active=${activeRequests.size} held=${heldNodes.size} " +
          s"(${heldMemoryBytes / 1024} KiB) peers=${knownAvailablePeers.size} | " +
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
      // spec 022 D4, D5: not while bytecode or the excluded set holds healing up: the tick looks after those, and no walk
      // can find what they wait on.
      if !trieWalkInProgress && !verificationBFSRunning &&
        pendingTasks.isEmpty && activeRequests.isEmpty && !heldNodes.awaitingCode && !excludedBlocking &&
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
          // spec 022 review forge F1: started only once the flush buffer is written (startVerificationBFS); with a write
          // that keeps failing it is deferred, and the retry above flushes again every pulse.
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

      // spec 022 review hunter-r3 N1, vault-r3 N4: the controller answers a give-up only inside StateHealing, and a refresh
      // that keeps the root sends nothing, so the latch has this watchdog too: after as long as the pivot-refresh one (8
      // pulses of 2 minutes), a later give-up may escalate again.
      if giveUpEscalated then
        giveUpLatchPulses += 1
        if giveUpLatchPulses >= GiveUpLatchMaxPulses then
          log.warn("[HEAL] No answer to a read-failure give-up — a later give-up may escalate again")
          giveUpEscalated = false
          giveUpLatchPulses = 0
      else giveUpLatchPulses = 0

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
    * (research.md R4c), and before #3 and before #4 the replaced-walk arm of each (spec 022 fix round 4): the end of a
    * walk this incarnation did not launch.
    */
  private[actors] lazy val frontierRebuildArms: PartialFunction[Command, Behavior[Command]] = {
    case FrontierRebuilt(entries, walkRoot) =>
      if storageScheme == StorageScheme.Path && walkRoot != stateRoot then
        // spec 022 review W1: a re-peg does not cancel a running walk, and its batches name positions of the root it was
        // launched on. Under Path a request is keyed by position alone, so such an entry would stand in for the current
        // root's request for the same position, whose node it is not. The current root's own discovery and walks find
        // every position it needs. Nothing else changes here (no stagnation reset, no dispatch). Hash scheme:
        // unchanged (a node is keyed by its hash there; see the review notes for forge).
        log.info(
          s"[HEAL-FRONTIER] Dropping ${entries.size} missing nodes from a walk of superseded root " +
            s"${Hex.toHexString(walkRoot.take(4).toArray)} (current ${Hex.toHexString(stateRoot.take(4).toArray)})"
        )
      else
        if entries.isEmpty then
          log.warn(
            "[HEAL-FRONTIER] Empty frontier batch received — trie may already be fully healed or storage is corrupt"
          )
        else log.info(s"[HEAL-FRONTIER] ${entries.size} missing nodes identified — queuing for healing")
        queueNodes(entries.map(e => (e.pathset, e.hash)))
        lastHealedAtMs = System.currentTimeMillis()
        tryRedispatchPendingTasks()

      Behaviors.same

    case FrontierRebuildComplete(_, walkRoot, walk) if !currentWalk.contains(walk) =>
      ignoreReplacedWalk(walk, s"full-state rebuild of root ${Hex.toHexString(walkRoot.take(4).toArray)}")
      Behaviors.same

    case FrontierRebuildComplete(missingEmitted, walkRoot, _) =>
      // The full-state rebuild BFS walked the entire trie; every still-missing node is now persisted.
      // Mark the snapshot complete so a future restart may resume it instead of re-walking (Layer 2).
      verificationBFSRunning = false // rebuild walk finished — release the single-flight gate
      // spec 002/005 marker: gated on frontier persistence. The resume path (StartTrieNodeHealing) reads the store
      // whenever it is present, which it also is for the spec 005 records with persistence off, so this guard is what
      // keeps the marker unwritten then (FR-005 parity; the marker also gates the spec-003 scoped path). Under
      // in-memory pruning the controller passes persistence off and clears any marker before the spawn (spec 022
      // review vault R1): no marker is trusted there.
      // spec 022 review (hunter W2, forge W-r4-1, vault-r3 W2): only for the current root. A re-peg does not stop this
      // walk; it clears the marker and drops the outstanding frontier the walk had found (pending, in flight, held), so
      // a walk of the root it left must not set the marker again: a restart would trust it, and so would the
      // completion gate's scoped walk.
      if frontierPersistenceEnabled then
        if walkRoot == stateRoot && !walkInvalidated && !codeHandOffArmed then
          stateIngest.healingFrontier.foreach { store =>
            store.markComplete()
            log.info("[HEAL-RESTART] Full-state rebuild complete — persisted frontier marked as a complete snapshot")
          }
        else
          log.info(
            s"[HEAL-RESTART] Full-state rebuild of superseded root ${Hex.toHexString(walkRoot.take(4).toArray)} " +
              s"complete — the persisted frontier is not marked complete (the heal has re-pegged to " +
              s"${Hex.toHexString(stateRoot.take(4).toArray)})"
          )
      // spec 006 C2/D2/FR-001/FR-002/FR-003: on a genuinely CLEAN rebuild, declare completion after this
      // single walk instead of waiting for the dead-pulse watchdog to force-start a redundant second
      // (verification) walk over the identical trie (~16-20h on ETC mainnet). The rebuild walk fully
      // recurses into account-leaf storageRoots (the BUG-1 gap the verification walk was built for), so on
      // a clean rebuild it IS the full traversal the verification would re-run. Declare early IFF every
      // conjunct holds — each excludes a specific false-completion path (research D2 + adversarial verdict):
      //   missingEmitted == 0    — the deciding walk found nothing missing (no gap discovered).
      //   totalNodesHealed == 0  — nothing healed during the walk (excludes heal-during-walk mutation;
      //                            conservative `== 0`, D5; cumulative counter preserved across pivots). A fill
      //                            put back or given up on writes nothing, so it is not counted and need not be:
      //                            this arm rests on the clean full walk (missingEmitted == 0), not on the count.
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
        walkRoot == stateRoot && !walkInvalidated
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
      // spec 022 T038a: and on either scheme when this walk no longer counts: what a re-peg deferred behind it (the walk of
      // the current root) starts here, not at the next heal response or the dead-pulse watchdog. And while the excluded
      // set has work: a check is what looks at it next.
      if (storageScheme == StorageScheme.Path && missingEmitted > 0) || walkRoot != stateRoot || walkInvalidated ||
        excludedBlocking
      then self ! HealingCheckCompletion

      Behaviors.same

    case FrontierWalkFailed(walk) if !currentWalk.contains(walk) =>
      ignoreReplacedWalk(walk, "frontier walk (it failed)")
      Behaviors.same

    case FrontierWalkFailed(_) =>
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
      // spec 022 review forge F1: like every frontier walk, the rebuild starts on a written flush buffer. At a fresh start
      // the buffer is empty; if responses filled it meanwhile and its write fails, no rebuild runs: something was healed,
      // so once the write succeeds the completion check runs the full verification walk in its place (full: the resume
      // sends this only when the store holds no completeness marker, or after removing one over a frontier it could not
      // read, vault-r3 W3; one stays only if that removal fails too).
      // spec 022 review (hunter W1, forge W-r4-2, vault-r3 W1, beacon W1-r4): no rebuild of a root a re-peg in the resume
      // window has left (the re-peg seeded or walks the new root), and none while a walk runs (a completion check or a
      // re-peg in the window started one, and two walks on the shared queue cut each other short). Without the rebuild
      // no marker is set; this incarnation has no clean pass yet, so the completion check walks the root once that
      // walk has ended.
      if root != stateRoot then
        log.info(
          s"[HEAL-RESTART] Full-state rebuild of superseded root ${Hex.toHexString(root.take(4).toArray)} dropped: " +
            s"the heal has re-pegged to ${Hex.toHexString(stateRoot.take(4).toArray)}"
        )
      else if trieWalkInProgress || verificationBFSRunning then
        log.info(
          "[HEAL-RESTART] Full-state rebuild not started: a frontier walk is already running. The completion check " +
            "walks the root once it has ended."
        )
      else if flushedForWalk() then
        // Refused only by a walk of another incarnation: that walk's end sends this one a check, which walks.
        val _ = startFrontierBFS(
          root,
          rootPath,
          isStor = false,
          (walk: Long, missingEmitted: Long) => self ! FrontierRebuildComplete(missingEmitted, root, walk)
        )
      else
        log.warn(
          "[HEAL-RESTART] Full-state rebuild not started: healed nodes waiting in the flush buffer could not be written. " +
            "The completion check retries the write and then runs the full verification walk instead."
        )
        self ! HealingCheckCompletion
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
      giveUpEscalated = false // the controller has answered (beacon N2-r3): a later give-up may escalate again
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
        // spec 022 review beacon W1: the stall may be held nodes that nothing can complete; the check drops them.
        self ! HealingCheckCompletion
      else
        log.debug("[HEAL] HealingResumeDispatch with no pending pivot-refresh latch — only a give-up's latch cleared")

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
    case FlushComplete(token, count) =>
      flushCompleted(token, count)
      // Flush what was committed during the write.
      if rawNodeBuffer.nonEmpty then flushRawNodesAsync()
      self ! HealingCheckCompletion

      Behaviors.same

    case FlushFailed(token, cause) =>
      flushFailed(token, cause)
      self ! HealingCheckCompletion

      Behaviors.same
  }

  /** The completion gate, the verification-walk results and the crash-recovery verification launch (C-H3
    * `HealVerification`). Arms #16–#20, #24 (research.md R4c). The `VerificationBFSComplete` arms (the replaced-walk
    * arm spec 022 fix round 4 added, then #17 stale root, #18 dirty pass, #19 clean pass) stay together, in this order,
    * so their guards fall through as before for the end of a walk this incarnation launched.
    */
  private[actors] lazy val healVerificationArms: PartialFunction[Command, Behavior[Command]] = {
    case HealingCheckCompletion =>
      // spec 022 D3: retry a commit whose write failed.
      if heldNodes.hasPendingWrites then commitHeld()
      dropOrphanedHeld()
      // spec 022 FR-011: the excluded set is the gate's last conjunct, after the flush: it is read only with everything
      // drained and written. It holds both branches below: no completion while an entry counts, and no walk until the
      // pass has seeded what the walk would otherwise have to find one node at a time. The armed hand-off is the one
      // exception: it walks, and on a clean pass hands off instead of completing.
      val mayDecide = isComplete && !flushing && !trieWalkInProgress && !verificationBFSRunning
      val gate = if mayDecide && flushRawNodesSync() then excludedGate() else ExcludedGate.Held
      // A held chain or a walk keeps the gate shut for a long time: the set's pass goes on regardless, or a tick would
      // never know what it wants.
      if !mayDecide && pendingTasks.isEmpty && activeRequests.isEmpty && excludedSeeding.exists(_.midPass) then
        continueExcludedPass()
      if gate == ExcludedGate.Clear then blockedTicks = 0
      if gate != ExcludedGate.Held then
        // spec 022 review hunter-r3 C1, forge F6, beacon W1-r3 and N3-r3: completion needs a clean walk of the current
        // root in this incarnation (verificationPassComplete: a verification walk, or the full rebuild walk, that found
        // nothing missing, each started only on a written flush buffer). There is no arm for "nothing was healed": a node
        // given up on is not healed either, and a supervisor-restarted incarnation starts from zero, so in neither case
        // does that count show that nothing is missing. With no clean pass yet, the check walks (the `else` below).
        // spec 022 T026: the gate's flushRawNodesSync() above wrote every buffered node, or the gate stays shut.
        if verificationPassComplete && gate == ExcludedGate.HandOff then handOffOnCode()
        else if verificationPassComplete then
          log.info(s"Healing round complete: $totalNodesHealed total nodes healed. Notifying controller.")
          // FR-002/FR-004: the snapshot is marked complete only here, on a clean walk of the current root in this
          // incarnation (verificationPassComplete: a verification walk, or the clean full rebuild walk, that found
          // nothing missing). This is the single completion chokepoint: VerificationBFSComplete and the rebuild's early
          // completion both route through HealingCheckCompletion, so the marker is written here, not in either.
          stateIngest.healingFrontier.foreach { store =>
            // spec 005 C2/C4/T007/T012: GENUINE zero-missing closure of the whole trie from `stateRoot` — a clean
            // walk just confirmed zero missing descendants (verificationPassComplete && isComplete) and
            // `flushRawNodesSync()` above durably committed every healed node-byte. ONLY now (subtree bytes durable)
            // is it crash-safe to record the root subtree-complete (ordinary WAL-ordered `update`). This is a real
            // closure, NOT a `visitedLru`-faked queue-drain: the gate required isComplete (no pending/active
            // frontier) AND a clean BFS pass. Recorded only under `prunedEnabled` (Hash scheme) so a Path-scheme
            // node never writes a hash-keyed record. The terminal `markComplete()` (fsync) follows last (D3 order).
            if prunedEnabled then store.markSubtreeComplete(stateRoot) // spec 005 root record — gated on prunedEnabled
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

    case VerificationBFSComplete(walkRoot, _, walk) if !currentWalk.contains(walk) =>
      ignoreReplacedWalk(walk, s"verification walk of root ${Hex.toHexString(walkRoot.take(4).toArray)}")
      Behaviors.same

    case VerificationBFSComplete(walkRoot, _, _) if walkRoot == stateRoot && walkInvalidated =>
      // spec 022 T038c: a re-peg passed under this walk and came back to its root, or a node known missing was dropped
      // while it ran. It proves nothing about now; the same root, so it is no sign of a root that outruns the walk.
      verificationBFSRunning = false
      log.info("[HEAL-VERIFY] Discarding a verification walk whose starting point no longer holds — walking again")
      reverifyCurrentRoot()
      Behaviors.same

    case VerificationBFSComplete(walkRoot, _, _) if walkRoot != stateRoot =>
      // STALE: the walk traversed a root the heal has since re-pegged away from. Its result proves nothing about the
      // current root, so it must NOT set verificationPassComplete. Release the single-flight gate and the per-run
      // observability flags, then verify the CURRENT root explicitly (`reverifyCurrentRoot` also clears the stale
      // verdict, so the completion check cannot complete on it).
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

    case VerificationBFSComplete(_, missingEmitted, _) if missingEmitted > 0 =>
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

    case VerificationBFSComplete(_, _, _) =>
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
      // spec 022 review (forge W-r4-2, beacon W1-r4): as for the full rebuild, no walk of a root a re-peg in the resume
      // window has left, and none while a walk runs; the completion check walks once that walk has ended.
      if root != stateRoot then
        log.info(
          s"[HEAL-RESTART] Resume verification of superseded root ${Hex.toHexString(root.take(4).toArray)} dropped: " +
            s"the heal has re-pegged to ${Hex.toHexString(stateRoot.take(4).toArray)}"
        )
      else if trieWalkInProgress || verificationBFSRunning then
        log.info(
          "[HEAL-RESTART] Resume verification not started: a frontier walk is already running. The completion check " +
            "walks the root once it has ended."
        )
        // spec 022 T038c: a scoped walk may not stand in for the full verification this resume asked for, and the check
        // that follows must not start another: the next walk covers the whole root.
        if scopedVerificationActive then invalidateRunningWalk()
        clearHealedPathsSet()
      else startVerificationBFS(root, rootPath)
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

  /** What the excluded set says at the completion gate: nothing against this root, an entry that stands, or an entry
    * that stands with the code hand-off armed.
    */
  private[actors] enum ExcludedGate:
    case Clear, Held, HandOff

  /** What has held healing up for `MaxBlockedTicks` idle minutes (see `blockerKind`). */
  private[actors] enum BlockerKind:
    case CodeOnly, Other, Unknown

  /** Floor for the per-peer in-flight healing budget (UpdateMaxInFlightPerPeer handler). Below this a single slow peer
    * holding the lone outstanding slot can stall a 1-2-peer residual heal. Peer-pool management only — no effect on
    * which nodes are stored or the content-hash gate.
    */
  val MinInFlightPerPeer: Int = 2

  /** spec 022 review vault V6: commit writes in a row that fail before healing abandons, loudly. */
  val MaxConsecutiveCommitFailures: Int = 10

  /** spec 022 review hunter-r3 N1: 2-minute pulses a read-failure give-up's latch waits for an answer. */
  private[actors] val GiveUpLatchMaxPulses: Int = 8

  /** spec 022 T037: minutes (ticks of [[HealingBlockedTick]]) with bytecode or the excluded set holding healing up and
    * no progress at all before healing falls back to `StateHealingAbandoned`.
    */
  val MaxBlockedTicks: Int = 30

  /** spec 022 review forge F2: fills in a row whose stored children could not be read before a healed node is given up
    * on (not fetched again; its parents are left to the orphan guard). The first failures are fetched again: a read can
    * fail once and work the next time.
    */
  val MaxConsecutiveReadFailures: Int = 3

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

  /** spec 022 D4: the bytecode of `codeHashes` has been written (the bytecode coordinator's acknowledgement, through
    * the controller). Each is sent only after its write returned.
    */
  final case class HealedCodeStored(codeHashes: Seq[ByteString]) extends Command
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

  // spec 022 T037: once a minute, while bytecode or the excluded set holds healing up: log, ask again, and give up after
  // MaxBlockedTicks with no progress.
  private[snap] case object HealingBlockedTick extends Command

  // Internal messages — sent only via `selfRef ! …` from Future continuations / timers, so they must be Commands so
  // the Typed behavior can receive them. They were plain `Any` messages under Classic.
  // Internal message for async flush completion
  // `token` is the flush's ticket token (FlushTicket, spec 022 review forge C1): only the flush in flight with that
  // token releases anything (FrontierPersistence.flushCompleted).
  private[actors] case class FlushComplete(token: Long, count: Int) extends Command
  // spec 022 T026 (#1549): the asynchronous flush `token` threw; nothing it was writing is released.
  private[actors] case class FlushFailed(token: Long, cause: Throwable) extends Command

  // Internal message for async frontier rebuild completion (crash-recovery BFS or verification BFS).
  // `walkRoot` is the root the emitting walk was launched on (captured on the actor thread at launch), as
  // FrontierRebuildComplete and VerificationBFSComplete carry it: a re-peg does not cancel a running walk, so a batch
  // can land after `stateRoot` moved, and under the Path scheme such a batch is dropped (spec 022 review W1).
  private[actors] case class FrontierRebuilt(entries: Seq[HealingEntry], walkRoot: ByteString) extends Command
  // Sent by startVerificationBFS when the BFS Future completes — gates verificationPassComplete.
  // Carries the root the walk was LAUNCHED against (`walkRoot`), exactly as `FrontierRebuildComplete` does. A pivot
  // re-peg does not cancel an in-flight verification walk, so its completion can land after `stateRoot` moved; the
  // handler must not credit a clean walk of the OLD root as verification of the NEW one. This is decisive for the
  // per-block-hot system-contract storage tries (EIP-4788/2935/8282): they differ between every pair of roots, while
  // the cold bulk of the state is shared, so an old-root "clean" signal says nothing about them.
  // `missingEmitted` is the number of missing nodes the walk found. A pass that found ANY is not a clean pass even if
  // every one of them has been healed by the time it ends: the walk never descended below a node that was missing when
  // it reached it, so the healed node's present children (and their subtrees) were not verified.
  // `walk` is the walk's lease token (spec 022 review hunter W1, beacon W1-r4): the end is taken only by the incarnation
  // that launched that walk (`currentWalk`).
  private[actors] case class VerificationBFSComplete(walkRoot: ByteString, missingEmitted: Long, walk: Long)
      extends Command

  // Layer 2: the full-state rebuild BFS finished — the persisted frontier is now a COMPLETE snapshot.
  // Sent after the final FrontierRebuilt so the completeness marker is set only once every node is persisted.
  // spec 006 C1/D3/FR-004: carries the rebuild's outcome so the handler can decide early completion soundly.
  //   missingEmitted = the walk's frontierCount (number of missing nodes emitted); 0 ⇒ found everything present.
  //   walkRoot       = the root the finished walk traversed (captured at launch); compared against the live
  //                    stateRoot to exclude a stale completion after a pivot refresh (F-E).
  //   walk           = the walk's lease token: taken only by the incarnation that launched it (`currentWalk`).
  private[actors] case class FrontierRebuildComplete(missingEmitted: Long, walkRoot: ByteString, walk: Long)
      extends Command

  // A frontier walk Future died with an exception. Resets the walk flags WITHOUT setting any
  // completion marker, so the watchdog / HealingCheckCompletion gates can start a fresh walk.
  // Without this, an exception skips onComplete() and verificationBFSRunning stays true forever,
  // permanently blocking every future walk (including the watchdog) until restart. `walk` is the walk's lease token: a
  // failure of a walk another incarnation launched resets nothing here.
  private[actors] case class FrontierWalkFailed(walk: Long) extends Command

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

  /** Default held-memory bound (spec 022 D1, FR-002): 128 MiB, about 250k held nodes. Operator-tunable via
    * `sync.snap-sync.healing.held-memory-limit`.
    */
  val DefaultHeldMemoryLimit: Long = 128L * 1024 * 1024

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

  /** Wait for every sub-range of a walk's level, and only then say how they went (spec 022 T038a). Stopping at the
    * first failure would end the walk, and give its lease back, while the other sub-ranges still write to the queue:
    * into the next walk's queue, at keys of their own wrapper's counter, which overwrites what that walk has enqueued
    * and lets it miss a level's nodes and report clean. A cancelled walk's sub-ranges each stop at their next chunk.
    */
  def awaitSubRanges[A](results: Seq[scala.concurrent.Future[A]]): Seq[A] =
    results
      .map(result => scala.util.Try(scala.concurrent.Await.result(result, scala.concurrent.duration.Duration.Inf)))
      .map(_.get)

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
      walkLocalOnly: Option[java.util.concurrent.atomic.AtomicBoolean] = None,
      heldMemoryLimit: Long = DefaultHeldMemoryLimit,
      excludedAccounts: Boolean = false,
      // spec 022 T038b: the controller's current heal root, which it sets before it sends a re-peg. A supervisor restart
      // runs this factory's setup again: the new incarnation starts on this root, not on the spawn's `stateRoot`.
      currentRoot: Option[java.util.concurrent.atomic.AtomicReference[ByteString]] = None
  ): Behavior[Command] =
    Behaviors.setup { context =>
      Behaviors.withTimers { timers =>
        new TrieNodeHealingCoordinatorImpl(
          context,
          timers,
          // spec 022 T038b: this setup runs again at a supervisor restart. Nothing replays a re-peg then, so the new
          // incarnation starts on the controller's current root, not the spawn's.
          initialStateRoot = currentRoot.flatMap(root => Option(root.get())).getOrElse(stateRoot),
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
          walkLocalOnly = walkLocalOnly,
          heldMemoryLimit = heldMemoryLimit,
          excludedAccounts = excludedAccounts
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
