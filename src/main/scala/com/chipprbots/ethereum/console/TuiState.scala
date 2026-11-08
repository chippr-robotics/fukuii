package com.chipprbots.ethereum.console

import java.time.Clock
import java.time.Duration
import java.time.Instant

/** State management for TUI data.
  *
  * This class holds all the state information displayed by the TUI, including network status, peer count, block
  * information, sync progress, and node settings.
  *
  * State is immutable - all updates return a new instance.
  *
  * @param glyphs
  *   the glyph set every row is drawn with
  * @param clock
  *   where uptime and sync speed read the time; a fixed clock makes a frame repeatable (spec 027 golden frames). Slice
  *   2 replaces it with the time each status poll records (research C-3).
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
    snapSyncState: Option[SnapSyncState] = None,
    syncBaseline: Option[SyncBaseline] = None,
    glyphs: GlyphSet = GlyphSet.Emoji,
    clock: Clock = Clock.systemUTC()
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
    Duration.between(startTime, clock.instant()).getSeconds

  /** Estimate sync time based on the observed sync speed. */
  def estimatedSyncTimeSeconds: Option[Long] =
    syncSpeedBlocksPerSec.map(speed => (blocksRemaining / speed).toLong)

  /** Sync speed in blocks per second, measured from the first block observed by this TUI (not from genesis — a node
    * restarted at block 15M has not imported 15M blocks this session). Requires MinUptimeForEstimationSeconds of
    * observation and forward progress.
    */
  def syncSpeedBlocksPerSec: Option[Double] =
    syncBaseline.flatMap { baseline =>
      val elapsed = Duration.between(baseline.observedAt, clock.instant()).getSeconds
      val imported = currentBlock - baseline.block
      if elapsed > MinUptimeForEstimationSeconds && imported > 0 then Some(imported.toDouble / elapsed.toDouble)
      else None
    }

  // Update methods return new instances (immutable)
  def withNetworkName(name: String): TuiState = copy(networkName = name)
  def withConnectionStatus(status: String): TuiState = copy(connectionStatus = status)
  def withPeerCount(count: Int, max: Int): TuiState = copy(peerCount = count, maxPeers = max)
  def withBlockInfo(current: Long, best: Long): TuiState =
    // Record the first non-zero block as the speed baseline; reset it if the chain moved backwards (rewind/reorg).
    val baseline = syncBaseline match
      case Some(b) if current >= b.block => Some(b)
      case _ if current > 0              => Some(SyncBaseline(current, clock.instant()))
      case _                             => None
    copy(currentBlock = current, bestBlock = best, syncBaseline = baseline)
  def withSyncStatus(status: String): TuiState = copy(syncStatus = status)
  def withNodeSettings(settings: NodeSettings): TuiState = copy(nodeSettings = settings)
  def withSnapSyncState(state: SnapSyncState): TuiState = copy(snapSyncState = Some(state))
  def clearSnapSyncState(): TuiState = copy(snapSyncState = None)

  /** Apply a status snapshot polled from the running node. */
  def withStatus(status: NodeStatusSnapshot): TuiState =
    withPeerCount(status.peerCount, status.maxPeers)
      .withBlockInfo(status.currentBlock, status.bestBlock)
      .withSyncStatus(status.syncStatus)
      .withConnectionStatus(status.connectionStatus)
      .copy(snapSyncState = status.snapSync)

/** First block observed by the TUI and when — the reference point for sync-speed estimates. */
case class SyncBaseline(block: Long, observedAt: Instant)

/** A point-in-time view of the running node, produced by a status source (see [[TuiStatusProbe]]) and applied to the
  * TUI state by [[TuiUpdater]].
  *
  * @param bestBlock
  *   highest known block of the network, or 0 when unknown (e.g. the sync controller reports NotSyncing)
  */
case class NodeStatusSnapshot(
    connectionStatus: String,
    peerCount: Int,
    maxPeers: Int,
    currentBlock: Long,
    bestBlock: Long,
    syncStatus: String,
    snapSync: Option[SnapSyncState] = None
)

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

/** SNAP sync state displayed in the TUI: the fields of the monitor's `SyncProgress` that the worm rows draw.
  *
  * @param overall
  *   progress over the whole run, 0 to 1: the monitor's `SyncProgress.globalProgress`, the fraction the log's Overall
  *   worm draws, so the TUI's Overall worm is the same bar in every phase
  * @param storageContractsCompleted
  *   contracts whose storage is fully downloaded, of [[storageContractsTotal]]
  * @param chainHeaders
  *   headers downloaded alongside the state, towards [[chainTarget]] (bodies and receipts likewise)
  * @param bytecodeComplete
  *   every bytecode is downloaded, whatever the estimate says
  * @param phaseElapsedSeconds
  *   time in the current phase; [[elapsedSeconds]] is the whole run
  */
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
    overall: Double = 0,
    estimatedTotalAccounts: Long = 0,
    estimatedTotalBytecodes: Long = 0,
    estimatedTotalSlots: Long = 0,
    etaSeconds: Option[Long] = None,
    storageContractsCompleted: Int = 0,
    storageContractsTotal: Int = 0,
    chainHeaders: BigInt = BigInt(0),
    chainBodies: BigInt = BigInt(0),
    chainReceipts: BigInt = BigInt(0),
    chainTarget: BigInt = BigInt(0),
    bytecodeComplete: Boolean = false,
    phaseElapsedSeconds: Double = 0
):

  /** Position of the phase in SNAP's order (accounts, bytecodes and storage, healing, validation, chain download,
    * completed), or -1 when the phase is idle or unknown. A row whose phase comes before this one is finished.
    */
  def phaseIndex: Int = SnapSyncState.PhaseOrder.indexOf(phase)

  /** The phase, with its glyph padded to [[TuiGlyphs.PhaseCols]] columns so both glyph sets line up (§5.2). */
  def phaseDescription(glyphs: TuiGlyphs): String =
    val (glyph, text) = phase match
      case "AccountRangeSync"        => (glyphs.accounts, "Downloading accounts")
      case "ByteCodeAndStorageSync"  => (glyphs.storage, "Downloading bytecodes & storage")
      case "StateHealing"            => (glyphs.healing, "Healing trie nodes")
      case "StateValidation"         => (glyphs.validating, "Validating state")
      case "ChainDownloadCompletion" => (glyphs.validating, "Downloading the remaining chain")
      case "Completed"               => (glyphs.done, "SNAP sync complete")
      case _                         => (glyphs.idle, "Idle")
    glyph.text + " " * (TuiGlyphs.PhaseCols - glyph.cols).max(0) + " " + text

object SnapSyncState:
  /** SNAP's phases in the order they run (`SNAPSyncController.SyncPhase`, by name). */
  val PhaseOrder: Seq[String] = Seq(
    "AccountRangeSync",
    "ByteCodeAndStorageSync",
    "StateHealing",
    "StateValidation",
    "ChainDownloadCompletion",
    "Completed"
  )

object TuiState:
  /** Minimum uptime in seconds before sync estimates are provided. This ensures we have enough data points for accurate
    * speed calculations.
    */
  val MinUptimeForEstimationSeconds: Long = 10

  /** Create an initial TUI state. */
  def initial: TuiState = TuiState()

  /** Create TUI state with network name. */
  def withNetwork(networkName: String): TuiState =
    TuiState(networkName = networkName)
