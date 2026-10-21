package com.chipprbots.ethereum.blockchain.sync.snap

import java.util.concurrent.atomic.AtomicLong

import scala.concurrent.duration.MILLISECONDS

import com.google.common.util.concurrent.AtomicDouble

import com.chipprbots.ethereum.metrics.MetricsContainer

/** Prometheus metrics for SNAP sync.
  *
  * Provides comprehensive observability for SNAP synchronization including:
  *   - Sync phase tracking
  *   - Account range download progress
  *   - Bytecode download progress
  *   - Storage range download progress
  *   - State healing progress
  *   - Peer performance metrics
  *   - Throughput and timing metrics
  *
  * Metrics are exposed via Prometheus endpoint and can be visualized in Grafana.
  */
object SNAPSyncMetrics extends MetricsContainer:

  // ===== Sync Phase Metrics =====

  /** Current SNAP sync phase (0=Idle, 1=AccountRange, 2=ByteCode, 3=ByteCode+Storage, 4=Storage, 5=StateHealing,
    * 6=StateValidation, 7=ChainDownload, 8=Completed)
    */
  final private val CurrentPhaseGauge =
    metrics.registry.gauge("snapsync.phase.current.gauge", new AtomicDouble(0d))

  /** Total time spent in SNAP sync (minutes) */
  final private val TotalSyncTimeMinutesGauge =
    metrics.registry.gauge("snapsync.totaltime.minutes.gauge", new AtomicDouble(0d))

  /** Time spent in current phase (seconds) */
  final private val PhaseTimeSecondsGauge =
    metrics.registry.gauge("snapsync.phase.time.seconds.gauge", new AtomicDouble(0d))

  // ===== Pivot Block Metrics =====

  /** Pivot block number selected for SNAP sync */
  final private val PivotBlockNumberGauge =
    metrics.registry.gauge("snapsync.pivot.block.number.gauge", new AtomicDouble(0d))

  // ===== Account Range Sync Metrics =====

  /** Total accounts synced */
  final private val AccountsSyncedGauge =
    metrics.registry.gauge("snapsync.accounts.synced.gauge", new AtomicLong(0L))

  /** Estimated total accounts to sync */
  final private val AccountsEstimatedTotalGauge =
    metrics.registry.gauge("snapsync.accounts.estimated.total.gauge", new AtomicLong(0L))

  /** Accounts sync throughput (accounts/second) - overall */
  final private val AccountsThroughputOverallGauge =
    metrics.registry.gauge("snapsync.accounts.throughput.overall.gauge", new AtomicDouble(0d))

  /** Accounts sync throughput (accounts/second) - recent (last 60s) */
  final private val AccountsThroughputRecentGauge =
    metrics.registry.gauge("snapsync.accounts.throughput.recent.gauge", new AtomicDouble(0d))

  /** Account range download timer */
  final private val AccountRangeDownloadTimer =
    metrics.registry.timer("snapsync.accounts.download.timer")

  /** Counter for total account range requests */
  final private val AccountRangeRequestsCounter =
    metrics.counter("snapsync.accounts.requests.total")

  /** Counter for failed account range requests */
  final private val AccountRangeFailuresCounter =
    metrics.counter("snapsync.accounts.requests.failed")

  // ===== Bytecode Download Metrics =====

  /** Total bytecodes downloaded */
  final private val BytecodesDownloadedGauge =
    metrics.registry.gauge("snapsync.bytecodes.downloaded.gauge", new AtomicLong(0L))

  /** Estimated total bytecodes to download */
  final private val BytecodesEstimatedTotalGauge =
    metrics.registry.gauge("snapsync.bytecodes.estimated.total.gauge", new AtomicLong(0L))

  /** Bytecode download throughput (codes/second) - overall */
  final private val BytecodesThroughputOverallGauge =
    metrics.registry.gauge("snapsync.bytecodes.throughput.overall.gauge", new AtomicDouble(0d))

  /** Bytecode download throughput (codes/second) - recent (last 60s) */
  final private val BytecodesThroughputRecentGauge =
    metrics.registry.gauge("snapsync.bytecodes.throughput.recent.gauge", new AtomicDouble(0d))

  /** Bytecode download timer */
  final private val BytecodeDownloadTimer =
    metrics.registry.timer("snapsync.bytecodes.download.timer")

  /** Counter for total bytecode requests */
  final private val BytecodeRequestsCounter =
    metrics.counter("snapsync.bytecodes.requests.total")

  /** Counter for failed bytecode requests */
  final private val BytecodeFailuresCounter =
    metrics.counter("snapsync.bytecodes.requests.failed")

  // ===== Storage Range Sync Metrics =====

  /** Total storage slots synced */
  final private val StorageSlotsSyncedGauge =
    metrics.registry.gauge("snapsync.storage.slots.synced.gauge", new AtomicLong(0L))

  /** Estimated total storage slots to sync */
  final private val StorageSlotsEstimatedTotalGauge =
    metrics.registry.gauge("snapsync.storage.slots.estimated.total.gauge", new AtomicLong(0L))

  /** Storage slots sync throughput (slots/second) - overall */
  final private val StorageSlotsThroughputOverallGauge =
    metrics.registry.gauge("snapsync.storage.throughput.overall.gauge", new AtomicDouble(0d))

  /** Storage slots sync throughput (slots/second) - recent (last 60s) */
  final private val StorageSlotsThroughputRecentGauge =
    metrics.registry.gauge("snapsync.storage.throughput.recent.gauge", new AtomicDouble(0d))

  /** Storage range download timer */
  final private val StorageRangeDownloadTimer =
    metrics.registry.timer("snapsync.storage.download.timer")

  /** Counter for total storage range requests */
  final private val StorageRangeRequestsCounter =
    metrics.counter("snapsync.storage.requests.total")

  /** Counter for failed storage range requests */
  final private val StorageRangeFailuresCounter =
    metrics.counter("snapsync.storage.requests.failed")

  // ===== State Healing Metrics =====

  /** Total trie nodes healed */
  final private val NodesHealedGauge =
    metrics.registry.gauge("snapsync.healing.nodes.healed.gauge", new AtomicLong(0L))

  /** Nodes healing throughput (nodes/second) - overall */
  final private val NodesHealingThroughputOverallGauge =
    metrics.registry.gauge("snapsync.healing.throughput.overall.gauge", new AtomicDouble(0d))

  /** Nodes healing throughput (nodes/second) - recent (last 60s) */
  final private val NodesHealingThroughputRecentGauge =
    metrics.registry.gauge("snapsync.healing.throughput.recent.gauge", new AtomicDouble(0d))

  /** State healing timer */
  final private val StateHealingTimer =
    metrics.registry.timer("snapsync.healing.timer")

  /** Counter for total healing requests */
  final private val HealingRequestsCounter =
    metrics.counter("snapsync.healing.requests.total")

  /** Counter for failed healing requests */
  final private val HealingFailuresCounter =
    metrics.counter("snapsync.healing.requests.failed")

  /** Number of missing nodes detected during validation */
  final private val MissingNodesDetectedGauge =
    metrics.registry.gauge("snapsync.validation.missing.nodes.gauge", new AtomicLong(0L))

  /** Current healing frontier backlog — missing nodes queued and awaiting fetch (pendingTasks). */
  final private val HealingFrontierPendingGauge =
    metrics.registry.gauge("snapsync.healing.frontier.pending.gauge", new AtomicLong(0L))

  /** In-flight GetTrieNodes healing requests. */
  final private val HealingActiveRequestsGauge =
    metrics.registry.gauge("snapsync.healing.active.requests.gauge", new AtomicLong(0L))

  /** Nodes visited so far by the post-SNAP frontier-rebuild BFS walk (`[HEAL-BFS]` log lines). Climbs during a
    * full-state walk (resets when a new walk starts); flat once healing is steady-state or resumed from a complete
    * persisted frontier.
    */
  final private val HealingRebuildVisitedGauge =
    metrics.registry.gauge("snapsync.healing.rebuild.visited.gauge", new AtomicLong(0L))

  // ===== Frontier-Rebuild Walk Observability (spec 002 US2 — observation-only, FR-005..FR-008) =====
  //
  // Per-level (or windowed) diagnostics for the post-SNAP `[HEAL-BFS]` walk, so an operator can read off
  // whether a slow walk is cache-, GC-, or disk-bound and whether shared-subtrie re-walk is inflating it.
  // These are pushed by `TrieNodeHealingCoordinator.rebuildFrontierBFS` at each level boundary; they never
  // influence which nodes the walk enqueues/visits/declares missing.

  /** Per-level wall/CPU time (ms) spent in queue reads (`iterateRange` chunk fetch). Aggregate CPU across readers when
    * the level is processed in parallel sub-ranges.
    */
  final private val HealingPhaseQueueReadMsGauge =
    metrics.registry.gauge("snapsync.healing.phase.queue_read_ms.gauge", new AtomicLong(0L))

  /** Per-level time (ms) spent in `multiGetNodes` (the dominant random trie read). Aggregate CPU when parallel.
    */
  final private val HealingPhaseTrieReadMsGauge =
    metrics.registry.gauge("snapsync.healing.phase.trie_read_ms.gauge", new AtomicLong(0L))

  /** Per-level time (ms) spent in `enqueueBatch` (queue writes). Aggregate CPU when parallel. */
  final private val HealingPhaseQueueWriteMsGauge =
    metrics.registry.gauge("snapsync.healing.phase.queue_write_ms.gauge", new AtomicLong(0L))

  /** GC pause time (ms) accumulated in the walk window, sampled via `GcPressureSampler`. JVM-wide GC during the walk
    * window (the walk dominates), not attributed solely to the walk.
    */
  final private val HealingGcPauseMsGauge =
    metrics.registry.gauge("snapsync.healing.gc.pause_ms.gauge", new AtomicLong(0L))

  /** GC pause fraction (GC pause ms ÷ wall ms) in the walk window. */
  final private val HealingGcFractionGauge =
    metrics.registry.gauge("snapsync.healing.gc.fraction.gauge", new AtomicDouble(0d))

  /** Per-level re-walk inflation ratio: `childRefsSeen ÷ max(1, distinctEnqueued)`. 1.0 = no shared-subtrie inflation;
    * > 1 means child references were seen more than once (the visited-set de-dup gate's workload). Feeds SC-004's 1.5×
    * check.
    */
  final private val HealingInflationRatioGauge =
    metrics.registry.gauge("snapsync.healing.inflation_ratio.gauge", new AtomicDouble(0d))

  // ===== Scoped Post-Heal Verification (spec 003 C6 — observation-only, FR-010) =====
  //
  // Distinguish the scoped post-heal verification path (re-walks only the healed subtrees) from the
  // full-root fallback, and report how much it covered / how long it took. These NEVER gate any
  // consensus or completion decision — pure instrumentation pushed by `TrieNodeHealingCoordinator`.

  /** 1 = the last post-heal verification engaged the SCOPED path; 0 = it took the full-root fallback. */
  final private val HealingScopedVerificationGauge =
    metrics.registry.gauge("snapsync.healing.scoped_verification.gauge", new AtomicLong(0L))

  /** Number of healed subtrees (seed count) the scoped verification re-walked. */
  final private val HealingScopedSubtreesGauge =
    metrics.registry.gauge("snapsync.healing.scoped_subtrees.gauge", new AtomicLong(0L))

  /** Wall time (ms) of the last scoped verification, from launch to the clean-pass completion. */
  final private val HealingScopedDurationMsGauge =
    metrics.registry.gauge("snapsync.healing.scoped_duration_ms.gauge", new AtomicLong(0L))

  // ===== Pruned (descend-and-stop) Verification (spec 005 C8/FR-009 — observation-only) =====
  //
  // Report whether the last verification engaged the descend-and-stop oracle (vs the full-trie walk), how many
  // present subtrees it pruned (the savings), how many nodes it visited, and how long it took. These NEVER gate any
  // consensus or completion decision — pure instrumentation pushed by `TrieNodeHealingCoordinator`. Nodes-visited
  // reuses the existing `HealingRebuildVisitedGauge` (`snapsync.healing.rebuild_visited.gauge`).

  /** 1 = the last verification engaged the PRUNED (descend-and-stop) path; 0 = full-trie walk (flag off / Path / no
    * store).
    */
  final private val HealingPrunedVerificationGauge =
    metrics.registry.gauge("snapsync.healing.pruned_verification.gauge", new AtomicLong(0L))

  /** Number of present, recorded-complete subtrees the pruned verification skipped (descend-and-stop hits). */
  final private val HealingPrunedSubtreesGauge =
    metrics.registry.gauge("snapsync.healing.pruned_subtrees.gauge", new AtomicLong(0L))

  /** Wall time (ms) of the last pruned verification, from launch to the clean-pass completion. */
  final private val HealingPrunedDurationMsGauge =
    metrics.registry.gauge("snapsync.healing.pruned_duration_ms.gauge", new AtomicLong(0L))

  // ===== Decoupled Heal Serve-Root (spec 004 C9/FR-010 — observation-only) =====
  //
  // Distinguish the fixed completeness WALK root from the advancing SERVE root used to fetch missing nodes,
  // and report cross-root heals and currently-unservable tasks. These NEVER gate any consensus or completion
  // decision — pure instrumentation pushed by `TrieNodeHealingCoordinator`. The root gauges carry the leading
  // 6 bytes of each root hash as a non-negative long "short label" (always >= 0 and exactly representable as a
  // Prometheus double) so an operator can eyeball-correlate them with the `[HEAL]` log lines; 0 = not yet set / off.

  /** 1 = decoupled serve-root is engaged (feature on); 0 = single-root (coupled) heal. */
  final private val HealingDecoupledEngagedGauge =
    metrics.registry.gauge("snapsync.healing.decoupled.engaged.gauge", new AtomicLong(0L))

  /** Leading 8 bytes of the fixed completeness WALK root (`stateRoot`), as a short numeric label. */
  final private val HealingWalkRootGauge =
    metrics.registry.gauge("snapsync.healing.decoupled.walk_root.gauge", new AtomicLong(0L))

  /** Leading 8 bytes of the advancing SERVE root used to fetch missing nodes, as a short numeric label. */
  final private val HealingServeRootGauge =
    metrics.registry.gauge("snapsync.healing.decoupled.serve_root.gauge", new AtomicLong(0L))

  /** Count of nodes healed via a serve root that differs from the walk root (cross-root heals). */
  final private val HealingCrossRootHealsGauge =
    metrics.registry.gauge("snapsync.healing.decoupled.cross_root_heals.gauge", new AtomicLong(0L))

  /** Count of heal tasks currently unservable (over the FR-006 attempts-without-refresh threshold). */
  final private val HealingUnservableTasksGauge =
    metrics.registry.gauge("snapsync.healing.decoupled.unservable_tasks.gauge", new AtomicLong(0L))

  // ===== Peer Performance Metrics =====

  /** Number of SNAP-capable peers currently connected */
  final private val SnapCapablePeersGauge =
    metrics.registry.gauge("snapsync.peers.capable.gauge", new AtomicLong(0L))

  /** Counter for peer blacklisting events */
  final private val PeerBlacklistCounter =
    metrics.counter("snapsync.peers.blacklisted.total")

  /** Counter for request timeouts */
  final private val RequestTimeoutsCounter =
    metrics.counter("snapsync.requests.timeouts.total")

  /** Counter for request retries */
  final private val RequestRetriesCounter =
    metrics.counter("snapsync.requests.retries.total")

  // ===== Error and Failure Metrics =====

  /** Counter for total sync errors */
  final private val SyncErrorsCounter =
    metrics.counter("snapsync.errors.total")

  /** Counter for state validation failures */
  final private val ValidationFailuresCounter =
    metrics.counter("snapsync.validation.failures.total")

  /** Counter for invalid proof responses */
  final private val InvalidProofsCounter =
    metrics.counter("snapsync.proofs.invalid.total")

  /** Counter for malformed responses */
  final private val MalformedResponsesCounter =
    metrics.counter("snapsync.responses.malformed.total")

  // ===== Queue Backpressure Metrics (PR #1233, #1241) =====

  /** Storage coordinator pending-task queue depth */
  final private val StorageQueueDepthGauge =
    metrics.registry.gauge("snapsync.storage.queue.depth.gauge", new AtomicLong(0L))

  /** Storage coordinator backpressure state (1=engaged, 0=released) */
  final private val StorageBackpressureGauge =
    metrics.registry.gauge("snapsync.storage.backpressure.gauge", new AtomicLong(0L))

  /** Number of per-account streaming storage tries currently held in memory. Each instance is bounded to ~8 MiB by
    * `SnapHashTrie.DefaultBatchSizeBytes`, so this × 8 MiB is the worst-case storage-processing heap footprint.
    */
  final private val StoragePendingTriesGauge =
    metrics.registry.gauge("snapsync.storage.pending_tries.size.gauge", new AtomicLong(0L))

  /** Bytecode coordinator pending-task queue depth */
  final private val ByteCodeQueueDepthGauge =
    metrics.registry.gauge("snapsync.bytecode.queue.depth.gauge", new AtomicLong(0L))

  /** Bytecode coordinator backpressure state (1=engaged, 0=released) */
  final private val ByteCodeBackpressureGauge =
    metrics.registry.gauge("snapsync.bytecode.backpressure.gauge", new AtomicLong(0L))

  // ===== Memory bounds (spec 014) =====

  /** Storage tasks counted by the intake gate: in transit to StorageRangeCoordinator plus queued there. */
  final private val IntakeStoragePendingGauge =
    metrics.registry.gauge("snapsync.memory.storage.pending.gauge", new AtomicLong(0L))

  /** CodeHashes counted by the intake gate: in transit to ByteCodeCoordinator plus queued there (hashes, not tasks). */
  final private val IntakeByteCodePendingGauge =
    metrics.registry.gauge("snapsync.memory.bytecode.pending.gauge", new AtomicLong(0L))

  /** 1 while the intake gate holds new SNAP contract work back (ceiling reached or heap pressure), else 0. */
  final private val IntakePausedGauge =
    metrics.registry.gauge("snapsync.memory.intake.paused.gauge", new AtomicLong(0L))

  /** 1 while the heap watchdog reports old-gen pressure, else 0. */
  final private val HeapPressureGauge =
    metrics.registry.gauge("snapsync.memory.heap.pressure.gauge", new AtomicLong(0L))

  /** Old-gen occupancy after the last collection of that pool, in bytes and as a fraction of the max heap. */
  final private val OldGenPostGcBytesGauge =
    metrics.registry.gauge("snapsync.memory.oldgen.postgc.bytes.gauge", new AtomicLong(0L))
  final private val OldGenPostGcRatioGauge =
    metrics.registry.gauge("snapsync.memory.oldgen.postgc.ratio.gauge", new AtomicDouble(0d))

  /** Live in-flight SNAP requests per coordinator. */
  final private val StorageInFlightRequestsGauge =
    metrics.registry.gauge("snapsync.storage.inflight.requests.gauge", new AtomicLong(0L))
  final private val ByteCodeInFlightRequestsGauge =
    metrics.registry.gauge("snapsync.bytecode.inflight.requests.gauge", new AtomicLong(0L))

  /** Counter for total pivot refreshes since SNAP sync start */
  final private val PivotRefreshedCounter =
    metrics.counter("snapsync.pivot.refreshed.total")

  /** Counter for lagging-peer evictions (NetworkPeerManagerActor.CheckLaggingPeers) */
  final private val LaggingPeerEvictedCounter =
    metrics.counter("network.lagging_peer.evicted.total")

  /** Counter for peers with confirmed snapless demotion (after 3 strikes) */
  final private val SnaplessPeersConfirmedCounter =
    metrics.counter("snapsync.peers.snapless.confirmed.total")

  /** Counter for peers with confirmed stateless demotion (after 3 strikes) */
  final private val StatelessPeersConfirmedCounter =
    metrics.counter("snapsync.peers.stateless.confirmed.total")

  /** Active SNAP peers per coordinator (knownAvailable - stateless - snapless - coolingDown) */
  final private val AccountActivePeersGauge =
    metrics.registry.gauge("snapsync.accounts.active_peers.gauge", new AtomicLong(0L))
  final private val StorageActivePeersGauge =
    metrics.registry.gauge("snapsync.storage.active_peers.gauge", new AtomicLong(0L))
  final private val ByteCodeActivePeersGauge =
    metrics.registry.gauge("snapsync.bytecode.active_peers.gauge", new AtomicLong(0L))

  // ===== Public API for Metrics Updates =====

  /** Update current sync phase (0-6 as defined in documentation) */
  def setCurrentPhase(phase: Int): Unit = CurrentPhaseGauge.set(phase.toDouble)

  /** Update pivot block number */
  def setPivotBlockNumber(blockNumber: BigInt): Unit = PivotBlockNumberGauge.set(blockNumber.toDouble)

  /** Update total sync time in minutes */
  def setTotalSyncTime(minutes: Double): Unit = TotalSyncTimeMinutesGauge.set(minutes)

  /** Update current phase time in seconds */
  def setPhaseTime(seconds: Double): Unit = PhaseTimeSecondsGauge.set(seconds)

  @volatile private var latest: Option[(SyncProgress, Long)] = None

  /** The most recent progress passed to [[measure]], if recorded within `maxAgeMillis` — read by the TUI, which has no
    * handle on the SNAP controller. The age bound keeps a finished or abandoned SNAP run from being shown forever.
    */
  def latestProgress(maxAgeMillis: Long): Option[SyncProgress] =
    latest.collect { case (p, at) if System.currentTimeMillis() - at <= maxAgeMillis => p }

  /** Record full sync progress from SyncProgress object */
  def measure(progress: SyncProgress): Unit =
    latest = Some((progress, System.currentTimeMillis()))
    // Phase
    import SNAPSyncController.SyncPhase.*
    val phaseValue = progress.phase match
      case Idle                    => 0
      case AccountRangeSync        => 1
      case ByteCodeAndStorageSync  => 3
      case StateHealing            => 5
      case StateValidation         => 6
      case ChainDownloadCompletion => 7
      case Completed               => 8
      case Dormant                 => 9
    setCurrentPhase(phaseValue)

    // Accounts
    AccountsSyncedGauge.set(progress.accountsSynced)
    AccountsEstimatedTotalGauge.set(progress.estimatedTotalAccounts)
    AccountsThroughputOverallGauge.set(progress.accountsPerSec)
    AccountsThroughputRecentGauge.set(progress.recentAccountsPerSec)

    // Bytecodes
    BytecodesDownloadedGauge.set(progress.bytecodesDownloaded)
    BytecodesEstimatedTotalGauge.set(progress.estimatedTotalBytecodes)
    BytecodesThroughputOverallGauge.set(progress.bytecodesPerSec)
    BytecodesThroughputRecentGauge.set(progress.recentBytecodesPerSec)

    // Storage
    StorageSlotsSyncedGauge.set(progress.storageSlotsSynced)
    StorageSlotsEstimatedTotalGauge.set(progress.estimatedTotalSlots)
    StorageSlotsThroughputOverallGauge.set(progress.slotsPerSec)
    StorageSlotsThroughputRecentGauge.set(progress.recentSlotsPerSec)

    // Healing
    NodesHealedGauge.set(progress.nodesHealed)
    NodesHealingThroughputOverallGauge.set(progress.nodesPerSec)
    NodesHealingThroughputRecentGauge.set(progress.recentNodesPerSec)

    // Time
    val totalMinutes = (System.currentTimeMillis() - progress.startTime) / 60000.0
    setTotalSyncTime(totalMinutes)

    val phaseSeconds = (System.currentTimeMillis() - progress.phaseStartTime) / 1000.0
    setPhaseTime(phaseSeconds)

  // ===== Timers for Download Operations =====

  def recordAccountRangeDownloadTime(timeMs: Long): Unit = AccountRangeDownloadTimer.record(timeMs, MILLISECONDS)
  def recordBytecodeDownloadTime(timeMs: Long): Unit = BytecodeDownloadTimer.record(timeMs, MILLISECONDS)
  def recordStorageRangeDownloadTime(timeMs: Long): Unit = StorageRangeDownloadTimer.record(timeMs, MILLISECONDS)
  def recordStateHealingTime(timeMs: Long): Unit = StateHealingTimer.record(timeMs, MILLISECONDS)

  // ===== Counters for Requests and Failures =====

  def incrementAccountRangeRequests(): Unit = AccountRangeRequestsCounter.increment()
  def incrementAccountRangeFailures(): Unit = AccountRangeFailuresCounter.increment()

  def incrementBytecodeRequests(): Unit = BytecodeRequestsCounter.increment()
  def incrementBytecodeFailures(): Unit = BytecodeFailuresCounter.increment()

  def incrementStorageRangeRequests(): Unit = StorageRangeRequestsCounter.increment()
  def incrementStorageRangeFailures(): Unit = StorageRangeFailuresCounter.increment()

  def incrementHealingRequests(): Unit = HealingRequestsCounter.increment()
  def incrementHealingFailures(): Unit = HealingFailuresCounter.increment()
  def setHealingFrontierPending(count: Long): Unit = HealingFrontierPendingGauge.set(count)
  def setHealingActiveRequests(count: Long): Unit = HealingActiveRequestsGauge.set(count)
  def setHealingRebuildVisited(count: Long): Unit = HealingRebuildVisitedGauge.set(count)

  // Frontier-rebuild walk observability (spec 002 US2 — observation-only)
  def setHealingPhaseQueueReadMs(ms: Long): Unit = HealingPhaseQueueReadMsGauge.set(ms)
  def setHealingPhaseTrieReadMs(ms: Long): Unit = HealingPhaseTrieReadMsGauge.set(ms)
  def setHealingPhaseQueueWriteMs(ms: Long): Unit = HealingPhaseQueueWriteMsGauge.set(ms)
  def setHealingGcPauseMs(ms: Long): Unit = HealingGcPauseMsGauge.set(ms)
  def setHealingGcFraction(fraction: Double): Unit = HealingGcFractionGauge.set(fraction)
  def setHealingInflationRatio(ratio: Double): Unit = HealingInflationRatioGauge.set(ratio)

  // spec 003 C6/T016 — scoped post-heal verification (observation-only)
  def setHealingScopedVerification(scoped: Long): Unit = HealingScopedVerificationGauge.set(scoped)
  def setHealingScopedSubtrees(count: Long): Unit = HealingScopedSubtreesGauge.set(count)
  def setHealingScopedDurationMs(ms: Long): Unit = HealingScopedDurationMsGauge.set(ms)

  // spec 005 C8/FR-009 — pruned (descend-and-stop) verification observability (never gates completion).
  def setHealingPrunedVerification(pruned: Long): Unit = HealingPrunedVerificationGauge.set(pruned)
  def setHealingPrunedSubtrees(count: Long): Unit = HealingPrunedSubtreesGauge.set(count)
  def setHealingPrunedDurationMs(ms: Long): Unit = HealingPrunedDurationMsGauge.set(ms)

  // spec 004 C9/T019 — decoupled heal serve-root (observation-only)
  def setHealingDecoupledEngaged(engaged: Boolean): Unit = HealingDecoupledEngagedGauge.set(if engaged then 1L else 0L)
  def setHealingWalkRoot(shortLabel: Long): Unit = HealingWalkRootGauge.set(shortLabel)
  def setHealingServeRoot(shortLabel: Long): Unit = HealingServeRootGauge.set(shortLabel)
  def setHealingCrossRootHeals(count: Long): Unit = HealingCrossRootHealsGauge.set(count)
  def setHealingUnservableTasks(count: Long): Unit = HealingUnservableTasksGauge.set(count)

  // ===== Peer and Network Metrics =====

  def setSnapCapablePeers(count: Int): Unit = SnapCapablePeersGauge.set(count.toLong)
  def incrementPeerBlacklisted(): Unit = PeerBlacklistCounter.increment()
  def incrementRequestTimeout(): Unit = RequestTimeoutsCounter.increment()
  def incrementRequestRetry(): Unit = RequestRetriesCounter.increment()

  // ===== Error Metrics =====

  def incrementSyncError(): Unit = SyncErrorsCounter.increment()
  def incrementValidationFailure(): Unit = ValidationFailuresCounter.increment()
  def incrementInvalidProof(): Unit = InvalidProofsCounter.increment()
  def incrementMalformedResponse(): Unit = MalformedResponsesCounter.increment()
  def setMissingNodesDetected(count: Long): Unit = MissingNodesDetectedGauge.set(count)

  // ===== Backpressure / Pivot / Peer-Pool Metrics (PR #1233, #1237, #1239, #1241, #1242) =====

  def setStorageQueueDepth(depth: Long): Unit = StorageQueueDepthGauge.set(depth)
  def setStorageBackpressure(engaged: Boolean): Unit = StorageBackpressureGauge.set(if engaged then 1L else 0L)
  def setStoragePendingTries(count: Long): Unit = StoragePendingTriesGauge.set(count)

  def setByteCodeQueueDepth(depth: Long): Unit = ByteCodeQueueDepthGauge.set(depth)
  def setByteCodeBackpressure(engaged: Boolean): Unit = ByteCodeBackpressureGauge.set(if engaged then 1L else 0L)

  def incrementPivotRefreshed(): Unit = PivotRefreshedCounter.increment()
  def incrementLaggingPeerEvicted(): Unit = LaggingPeerEvictedCounter.increment()
  def incrementSnaplessPeerConfirmed(): Unit = SnaplessPeersConfirmedCounter.increment()
  def incrementStatelessPeerConfirmed(): Unit = StatelessPeersConfirmedCounter.increment()

  // ===== Memory bounds (spec 014) =====

  def setIntakeStoragePending(count: Long): Unit = IntakeStoragePendingGauge.set(count)
  def setIntakeByteCodePending(count: Long): Unit = IntakeByteCodePendingGauge.set(count)
  def setIntakePaused(paused: Boolean): Unit = IntakePausedGauge.set(if paused then 1L else 0L)
  def setHeapPressure(active: Boolean): Unit = HeapPressureGauge.set(if active then 1L else 0L)
  def setOldGenPostGc(bytes: Long, ratio: Double): Unit =
    OldGenPostGcBytesGauge.set(bytes)
    OldGenPostGcRatioGauge.set(ratio)
  def setStorageInFlightRequests(count: Int): Unit = StorageInFlightRequestsGauge.set(count.toLong)
  def setByteCodeInFlightRequests(count: Int): Unit = ByteCodeInFlightRequestsGauge.set(count.toLong)

  def setAccountActivePeers(count: Int): Unit = AccountActivePeersGauge.set(count.toLong)
  def setStorageActivePeers(count: Int): Unit = StorageActivePeersGauge.set(count.toLong)
  def setByteCodeActivePeers(count: Int): Unit = ByteCodeActivePeersGauge.set(count.toLong)
