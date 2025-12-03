package com.chipprbots.ethereum.blockchain.sync.snap

import java.util.concurrent.atomic.AtomicLong

import scala.concurrent.duration.MILLISECONDS

import com.google.common.util.concurrent.AtomicDouble

import com.chipprbots.ethereum.metrics.MetricsContainer

/** Prometheus metrics for SNAP sync.
  * 
  * Provides comprehensive observability for SNAP synchronization including:
  * - Sync phase tracking
  * - Account range download progress
  * - Bytecode download progress
  * - Storage range download progress
  * - State healing progress
  * - Peer performance metrics
  * - Throughput and timing metrics
  * 
  * Metrics are exposed via Prometheus endpoint and can be visualized in Grafana.
  */
object SNAPSyncMetrics extends MetricsContainer {

  // ===== Sync Phase Metrics =====
  
  /** Current SNAP sync phase (0=Idle, 1=AccountRange, 2=Bytecode, 3=StorageRange, 4=StateHealing, 5=StateValidation, 6=Completed) */
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

  // ===== Public API for Metrics Updates =====

  /** Update current sync phase (0-6 as defined in documentation) */
  def setCurrentPhase(phase: Int): Unit = CurrentPhaseGauge.set(phase.toDouble)

  /** Update pivot block number */
  def setPivotBlockNumber(blockNumber: BigInt): Unit = PivotBlockNumberGauge.set(blockNumber.toDouble)

  /** Update total sync time in minutes */
  def setTotalSyncTime(minutes: Double): Unit = TotalSyncTimeMinutesGauge.set(minutes)

  /** Update current phase time in seconds */
  def setPhaseTime(seconds: Double): Unit = PhaseTimeSecondsGauge.set(seconds)

  /** Record full sync progress from SyncProgress object */
  def measure(progress: SyncProgress): Unit = {
    // Phase
    val phaseValue = progress.phase match {
      case SNAPSyncController.Idle => 0
      case SNAPSyncController.AccountRangeSync => 1
      case SNAPSyncController.ByteCodeSync => 2
      case SNAPSyncController.StorageRangeSync => 3
      case SNAPSyncController.StateHealing => 4
      case SNAPSyncController.StateValidation => 5
      case SNAPSyncController.Completed => 6
    }
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
  }

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
}
