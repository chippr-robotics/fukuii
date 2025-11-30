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
    nodeSettings: NodeSettings = NodeSettings()
):
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

  /** Estimate sync time based on current progress. */
  def estimatedSyncTimeSeconds: Option[Long] =
    val uptime = uptimeSeconds
    if uptime > 10 && currentBlock > 0 then
      val blocksPerSecond = currentBlock.toDouble / uptime.toDouble
      if blocksPerSecond > 0 then Some((blocksRemaining / blocksPerSecond).toLong)
      else None
    else None

  /** Calculate sync speed in blocks per second. */
  def syncSpeedBlocksPerSec: Option[Double] =
    val uptime = uptimeSeconds
    if uptime > 10 && currentBlock > 0 then Some(currentBlock.toDouble / uptime.toDouble)
    else None

  // Update methods return new instances (immutable)
  def withNetworkName(name: String): TuiState = copy(networkName = name)
  def withConnectionStatus(status: String): TuiState = copy(connectionStatus = status)
  def withPeerCount(count: Int, max: Int): TuiState = copy(peerCount = count, maxPeers = max)
  def withBlockInfo(current: Long, best: Long): TuiState = copy(currentBlock = current, bestBlock = best)
  def withSyncStatus(status: String): TuiState = copy(syncStatus = status)
  def withNodeSettings(settings: NodeSettings): TuiState = copy(nodeSettings = settings)

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

object TuiState:
  /** Create an initial TUI state. */
  def initial: TuiState = TuiState()

  /** Create TUI state with network name. */
  def withNetwork(networkName: String): TuiState =
    TuiState(networkName = networkName)
