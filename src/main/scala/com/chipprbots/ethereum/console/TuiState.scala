package com.chipprbots.ethereum.console

import java.time.Duration
import java.time.Instant

/** State management for TUI data.
  *
  * This class holds all the state information displayed by the TUI, including network status, peer count, block
  * information, sync progress, and node settings.
  *
  * State is immutable - all updates return a new instance.
  */
case class TuiState(
    networkName: String = "unknown",
    connectionStatus: String = "Initializing",
    peerCount: Int = 0,
    maxPeers: Int = 0,
    currentBlock: Long = 0,
    bestBlock: Long = 0,
    syncStatus: String = "Starting...",
    startTime: Instant = Instant.now(),
    nodeSettings: NodeSettings = NodeSettings(),
    snapSyncState: Option[SnapSyncState] = None
):

  import TuiState.MinUptimeForEstimationSeconds

  /** Calculate sync progress as a percentage. */
  def syncProgress: Double =
    if bestBlock > 0 && currentBlock > 0 then (currentBlock.toDouble / bestBlock.toDouble) * 100.0
    else 0.0

  /** Calculate blocks remaining to sync. */
  def blocksRemaining: Long =
    if bestBlock > currentBlock then bestBlock - currentBlock
    else 0L

  /** Check if the node is synchronized. */
  def isSynchronized: Boolean =
    bestBlock > 0 && currentBlock >= bestBlock

  /** Get uptime in seconds. */
  def uptimeSeconds: Long =
    Duration.between(startTime, Instant.now()).getSeconds

  /** Estimate sync time based on current progress.
    * Requires minimum uptime of MinUptimeForEstimationSeconds to provide accurate estimates.
    */
  def estimatedSyncTimeSeconds: Option[Long] =
    val uptime = uptimeSeconds
    if uptime > MinUptimeForEstimationSeconds && currentBlock > 0 then
      val blocksPerSecond = currentBlock.toDouble / uptime.toDouble
      if blocksPerSecond > 0 then Some((blocksRemaining / blocksPerSecond).toLong)
      else None
    else None

  /** Calculate sync speed in blocks per second.
    * Requires minimum uptime of MinUptimeForEstimationSeconds to provide accurate estimates.
    */
  def syncSpeedBlocksPerSec: Option[Double] =
    val uptime = uptimeSeconds
    if uptime > MinUptimeForEstimationSeconds && currentBlock > 0 then Some(currentBlock.toDouble / uptime.toDouble)
    else None

  // Update methods return new instances (immutable)
  def withNetworkName(name: String): TuiState = copy(networkName = name)
  def withConnectionStatus(status: String): TuiState = copy(connectionStatus = status)
  def withPeerCount(count: Int, max: Int): TuiState = copy(peerCount = count, maxPeers = max)
  def withBlockInfo(current: Long, best: Long): TuiState = copy(currentBlock = current, bestBlock = best)
  def withSyncStatus(status: String): TuiState = copy(syncStatus = status)
  def withNodeSettings(settings: NodeSettings): TuiState = copy(nodeSettings = settings)
  def withSnapSyncState(state: SnapSyncState): TuiState = copy(snapSyncState = Some(state))
  def clearSnapSyncState(): TuiState = copy(snapSyncState = None)

/** Node settings displayed in the TUI. */
case class NodeSettings(
    dataDir: String = "",
    network: String = "",
    syncMode: String = "",
    pruningMode: String = "",
    maxPeers: Int = 0,
    rpcEnabled: Boolean = false,
    rpcPort: Int = 0,
    miningEnabled: Boolean = false
)

/** SNAP sync state displayed in the TUI. */
case class SnapSyncState(
    phase: String = "Idle",
    accountsSynced: Long = 0,
    bytecodesDownloaded: Long = 0,
    storageSlotsSynced: Long = 0,
    nodesHealed: Long = 0,
    elapsedSeconds: Double = 0,
    accountsPerSec: Double = 0,
    bytecodesPerSec: Double = 0,
    slotsPerSec: Double = 0,
    nodesPerSec: Double = 0,
    recentAccountsPerSec: Double = 0,
    recentBytecodesPerSec: Double = 0,
    recentSlotsPerSec: Double = 0,
    recentNodesPerSec: Double = 0,
    phaseProgress: Int = 0,
    estimatedTotalAccounts: Long = 0,
    estimatedTotalBytecodes: Long = 0,
    estimatedTotalSlots: Long = 0,
    etaSeconds: Option[Long] = None
):
  /** Calculate overall progress across all phases (rough estimate). */
  def overallProgress: Double =
    phase match
      case "AccountRangeSync" if estimatedTotalAccounts > 0 =>
        (accountsSynced.toDouble / estimatedTotalAccounts * 25.0) // 25% of total
      case "ByteCodeSync" if estimatedTotalBytecodes > 0 =>
        25.0 + (bytecodesDownloaded.toDouble / estimatedTotalBytecodes * 15.0) // 15% of total
      case "StorageRangeSync" if estimatedTotalSlots > 0 =>
        40.0 + (storageSlotsSynced.toDouble / estimatedTotalSlots * 40.0) // 40% of total
      case "StateHealing" => 80.0 // Healing is variable but assume 80-95%
      case "StateValidation" => 95.0
      case "Completed" => 100.0
      case _ => 0.0

  /** Get formatted phase description. */
  def phaseDescription: String = phase match
    case "AccountRangeSync" => "📦 Downloading accounts"
    case "ByteCodeSync" => "💾 Downloading bytecodes"
    case "StorageRangeSync" => "🗄️  Downloading storage"
    case "StateHealing" => "🔧 Healing trie nodes"
    case "StateValidation" => "✓ Validating state"
    case "Completed" => "✅ SNAP sync complete"
    case _ => "⏸️  Idle"

  /** Get primary metric for current phase. */
  def primaryMetric: (String, Long, Double) = phase match
    case "AccountRangeSync" => ("Accounts", accountsSynced, recentAccountsPerSec)
    case "ByteCodeSync" => ("Bytecodes", bytecodesDownloaded, recentBytecodesPerSec)
    case "StorageRangeSync" => ("Slots", storageSlotsSynced, recentSlotsPerSec)
    case "StateHealing" => ("Nodes", nodesHealed, recentNodesPerSec)
    case _ => ("Items", 0L, 0.0)

object TuiState:
  /** Minimum uptime in seconds before sync estimates are provided.
    * This ensures we have enough data points for accurate speed calculations.
    */
  val MinUptimeForEstimationSeconds: Long = 10

  /** Create an initial TUI state. */
  def initial: TuiState = TuiState()

  /** Create TUI state with network name. */
  def withNetwork(networkName: String): TuiState =
    TuiState(networkName = networkName)
