package com.chipprbots.ethereum.console

import java.time.Duration
import java.time.Instant

/** How the TUI moves (spec 027 FR-012, research §4.5). */
enum Motion:
  /** Ripple, pulse, spinner, splash and celebration, at 4 fps while anything moves. */
  case Full

  /** Nothing moves except the worm with progress (`FUKUII_TUI_MOTION=reduced`, `fukuii.tui.reduced-motion`). */
  case Reduced

  /** `p`: like [[Reduced]], and the frame counter stops; polling continues, so values still change. */
  case Paused

/** What the synced Blocks row draws on a catch-up (spec 027 FR-014, Q8, research §3.8). */
enum Catchup:
  /** The once-per-process celebration under full motion, at `frame` of [[TuiState.CelebrationFrames]]. */
  case Celebrating(frame: Int)

  /** The celebration as one frame, under reduced motion. */
  case Celebrated

  /** A later catch-up: one quiet frame, no header flash. */
  case BackAtHead

/** State management for TUI data: the frame's whole input (spec 027 research §7.1).
  *
  * State is immutable - all updates return a new instance. Nothing here reads the wall clock (C-3): durations are
  * measured on [[now]], the time of the latest status snapshot, and motion counts [[tick]]s.
  *
  * @param startTime
  *   when the TUI started; uptime is measured from it to [[now]]
  * @param now
  *   the time of the latest status snapshot ([[NodeStatusSnapshot.now]]), or [[startTime]] before the first one
  * @param glyphs
  *   the glyph set every row is drawn with
  * @param tick
  *   the frame counter: the only input of motion. It stops while [[motion]] is [[Motion.Paused]]
  * @param splashUntilTick
  *   the splash is drawn while `tick` is below it, then handed over to the dashboard (§3.7)
  * @param celebrationStartedTick
  *   the tick the once-per-process celebration started at
  * @param backAtHeadTick
  *   the tick of a later catch-up's quiet frame
  * @param celebrated
  *   the celebration has started once in this process (Q8)
  * @param lastAdvanceAt
  *   when the current block last moved; a bar unchanged for [[TuiState.StallAfter]] is stalled
  * @param view
  *   the view on screen (`1`–`4`, `Tab`)
  * @param helpOpen
  *   Help is open over [[view]] (`?`, `h`); any key but `q`, `r` and `d` closes it
  * @param chain
  *   the chain's identity and fork timeline, once the node has read its configuration
  * @param snapshot
  *   the latest status snapshot applied: each source's freshness, the peers, and what the probe read on the node
  * @param zeroPeersSince
  *   when the peer count, known, was last seen to become 0: the alert row's "0 peers for …" (§2.3)
  */
case class TuiState(
    networkName: String = "unknown",
    connectionStatus: String = "Initializing",
    peerCount: Int = 0,
    maxPeers: Int = 0,
    currentBlock: Long = 0,
    bestBlock: Long = 0,
    syncStatus: String = "Starting...",
    startTime: Instant = TuiState.Origin,
    now: Instant = TuiState.Origin,
    nodeSettings: NodeSettings = NodeSettings(),
    snapSyncState: Option[SnapSyncState] = None,
    syncBaseline: Option[SyncBaseline] = None,
    glyphs: GlyphSet = GlyphSet.Emoji,
    tick: Long = 0,
    motion: Motion = Motion.Full,
    splashUntilTick: Option[Long] = None,
    celebrationStartedTick: Option[Long] = None,
    backAtHeadTick: Option[Long] = None,
    celebrated: Boolean = false,
    lastAdvanceAt: Option[Instant] = None,
    view: TuiView = TuiView.Overview,
    helpOpen: Boolean = false,
    chain: Option[ChainFacts] = None,
    snapshot: Option[NodeStatusSnapshot] = None,
    zeroPeersSince: Option[Instant] = None
):

  import TuiState.*

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
    Duration.between(startTime, now).getSeconds.max(0L)

  /** Estimate sync time based on the observed sync speed. */
  def estimatedSyncTimeSeconds: Option[Long] =
    syncSpeedBlocksPerSec.map(speed => (blocksRemaining / speed).toLong)

  /** Sync speed in blocks per second, measured from the first block observed by this TUI (not from genesis — a node
    * restarted at block 15M has not imported 15M blocks this session). Requires MinUptimeForEstimationSeconds of
    * observation and forward progress.
    */
  def syncSpeedBlocksPerSec: Option[Double] =
    syncBaseline.flatMap { baseline =>
      val elapsed = Duration.between(baseline.observedAt, now).getSeconds
      val imported = currentBlock - baseline.block
      if elapsed > MinUptimeForEstimationSeconds && imported > 0 then Some(imported.toDouble / elapsed.toDouble)
      else None
    }

  /** Blocks remain and the current block has not moved for [[StallAfter]] of snapshot time (§4.1, §3.9). */
  def stalled: Boolean =
    blocksRemaining > 0 && lastAdvanceAt.exists(at => Duration.between(at, now).compareTo(StallAfter) >= 0)

  /** How much of the splash is on screen at `height` rows: None once it is over; `Some(0)` while it is drawn whole; and
    * during the hand-over `Some(k)`, the top `k` rows already the dashboard's, `k = 4 × ticksSinceSplashEnd` (§3.7).
    */
  def splashCover(height: Int): Option[Int] =
    splashUntilTick.flatMap { until =>
      if tick < until then Some(0)
      else if motion == Motion.Full then
        val rows = HandoverRowsPerTick * (tick - until + 1)
        if rows < height then Some(rows.toInt) else None
      else None
    }

  /** What the synced Blocks row draws this frame, if a catch-up is on screen. */
  def catchup: Option[Catchup] =
    val celebration = celebrationStartedTick.map(tick - _).collect {
      case frame if motion == Motion.Full && frame >= 0 && frame < CelebrationFrames => Catchup.Celebrating(frame.toInt)
      case 0L if motion != Motion.Full                                               => Catchup.Celebrated
    }
    celebration.orElse(backAtHeadTick.filter(_ == tick).map(_ => Catchup.BackAtHead))

  /** The header band flashes on frames 2–6 of the celebration (§3.8); never under reduced motion. */
  def headerFlash: Boolean = catchup.exists {
    case Catchup.Celebrating(frame) => HeaderFlashFrames.contains(frame)
    case _                          => false
  }

  // Update methods return new instances (immutable)
  def withNetworkName(name: String): TuiState = copy(networkName = name)
  def withConnectionStatus(status: String): TuiState = copy(connectionStatus = status)
  def withPeerCount(count: Int, max: Int): TuiState = copy(peerCount = count, maxPeers = max)

  /** The blocks, at [[now]]: records the first non-zero block as the speed baseline and resets it if the chain moved
    * backwards (rewind/reorg); any move of the current block, or the first sight of a target, restarts the stall clock.
    */
  def withBlockInfo(current: Long, best: Long): TuiState =
    val baseline = syncBaseline match
      case Some(b) if current >= b.block => Some(b)
      case _ if current > 0              => Some(SyncBaseline(current, now))
      case _                             => None
    val advanced =
      if current != currentBlock || (lastAdvanceAt.isEmpty && best > 0) then Some(now) else lastAdvanceAt
    copy(currentBlock = current, bestBlock = best, syncBaseline = baseline, lastAdvanceAt = advanced)

  def withSyncStatus(status: String): TuiState = copy(syncStatus = status)
  def withNodeSettings(settings: NodeSettings): TuiState = copy(nodeSettings = settings)
  def withSnapSyncState(state: SnapSyncState): TuiState = copy(snapSyncState = Some(state))
  def clearSnapSyncState(): TuiState = copy(snapSyncState = None)

  /** Apply a status snapshot polled from the running node, at its time. A catch-up (a known target, not reached, then
    * reached) starts the celebration the first time in this process and the quiet "back at head" frame after that (Q8).
    * While paused, a catch-up shows nothing: `p` cancels a celebration (FR-014).
    */
  def withStatus(status: NodeStatusSnapshot): TuiState =
    // A known peer count of 0 starts the 0-peers clock; a known count above 0 stops it; an unknown one leaves it.
    val zeroSince = status.peers.value.map(_.isEmpty) match
      case Some(true)  => zeroPeersSince.orElse(Some(status.now))
      case Some(false) => None
      case None        => zeroPeersSince
    // A SNAP sample too old to show keeps the rows it last drew on screen, which then say how old they are (§2.3),
    // while SNAP still counts as running; after that, or once another sync has taken over, the rows go.
    val snap = status.snapSync.orElse(
      snapSyncState.filter(_ => status.sync.value.exists(s => s.snap.isEmpty && s.snapRunning))
    )
    val next = copy(now = status.now)
      .withPeerCount(status.peerCount, status.maxPeers)
      .withBlockInfo(status.currentBlock, status.bestBlock)
      .withSyncStatus(status.syncStatus)
      .withConnectionStatus(status.connectionStatus)
      .copy(snapSyncState = snap, snapshot = Some(status), zeroPeersSince = zeroSince)
    val caughtUp = bestBlock > 0 && !isSynchronized && next.isSynchronized
    if !caughtUp then next
    else if motion == Motion.Paused then next.copy(celebrated = true)
    else if !celebrated then next.copy(celebrationStartedTick = Some(tick), celebrated = true)
    else next.copy(backAtHeadTick = Some(tick))

  /** The next frame: the counter moves on (unless paused), then the latest snapshot, if any, applies. */
  def nextFrame(snapshot: Option[NodeStatusSnapshot]): TuiState =
    val ticked = if motion == Motion.Paused then this else copy(tick = tick + 1)
    snapshot.fold(ticked)(ticked.withStatus)

  /** `p`: pause, or resume to `configured` motion. Pausing cancels a celebration on screen (FR-014). */
  def togglePause(configured: Motion): TuiState =
    if motion == Motion.Paused then copy(motion = configured)
    else copy(motion = Motion.Paused, celebrationStartedTick = None, backAtHeadTick = None)

  /** Any key on the splash: the dashboard, at once. */
  def skipSplash: TuiState = copy(splashUntilTick = None)

  /** `1`–`4`: the view, with Help closed. A change of view cancels a catch-up on screen (FR-014). */
  def withView(next: TuiView): TuiState =
    if next == view && !helpOpen then this
    else if next == view then copy(helpOpen = false)
    else copy(view = next, helpOpen = false).cancelCatchup

  /** `Tab`: the next view, wrapping. */
  def nextView: TuiState = withView(view.next)

  /** `?`, `h`: Help over the current view; it cancels a catch-up on screen, as a view change does. */
  def openHelp: TuiState = copy(helpOpen = true).cancelCatchup

  /** Any key in Help but `q`, `r` and `d`: back to the view under it. */
  def closeHelp: TuiState = copy(helpOpen = false)

  /** `g`: the other glyph set, with the same column budgets (§3.10). */
  def toggleGlyphs: TuiState =
    val other = glyphs match
      case GlyphSet.Emoji => GlyphSet.Ascii
      case GlyphSet.Ascii => GlyphSet.Emoji
    copy(glyphs = other)

  private def cancelCatchup: TuiState = copy(celebrationStartedTick = None, backAtHeadTick = None)

  /** The view's name, as the header shows it. */
  def viewTitle: String = if helpOpen then "Help" else view.title

  /** SNAP's pivot block, while SNAP's rows are on screen: the Block cell's target and the fork timeline's position
    * then, since the sync controller reports no block target and the local best block is far behind (B-044).
    */
  def snapPivot: Option[Long] =
    snapSyncState.flatMap(_ => snapshot.flatMap(_.local.snapPivot)).filter(_ > 0)

  /** How old the SNAP sample on screen is, if the probe said when it was taken. */
  def snapSampleAge: Option[Duration] =
    snapshot.flatMap(_.sync.value).flatMap(_.snapSampledAt).map(at => Duration.between(at, now))

/** First block observed by the TUI and when — the reference point for sync-speed estimates. */
case class SyncBaseline(block: Long, observedAt: Instant)

/** What the sync controller said, read against the local best block (see [[TuiStatusProbe.describe]]).
  *
  * @param best
  *   highest known block of the network, or 0 when unknown (e.g. the sync controller reports NotSyncing)
  * @param snap
  *   the SNAP progress, while its sample is young enough to show
  * @param snapSampledAt
  *   when the SNAP monitor recorded its latest sample of a running phase (`SNAPSyncMetrics.latestSample`), at any age:
  *   the alert row warns when it is old (§2.3)
  * @param snapRunning
  *   SNAP is running: the latest sample is of a running phase and younger than
  *   [[TuiStatusProbe.SnapRunningMaxAgeMillis]]. The one test of it the TUI makes: the SNAP rows stay on screen past
  *   their sample's 90 s, and the alert row warns of the sample's age, only while it holds
  */
final case class SyncSample(
    current: Long,
    best: Long,
    status: String,
    snap: Option[SnapSyncState] = None,
    snapSampledAt: Option[Instant] = None,
    snapRunning: Boolean = false
)

/** A point-in-time view of the running node, produced by a status source (see [[TuiStatusProbe]], polled by
  * [[TuiProbeThread]]) and applied to the TUI state by [[TuiUpdater]].
  *
  * Each source carries its own freshness ([[Sourced]]), so a slow peer manager degrades the peer line only (FR-011).
  * The strings and numbers the dashboard drew from earlier snapshots are derived.
  *
  * @param now
  *   when the probe took the snapshot: the TUI's only clock
  * @param localBest
  *   the local best block, read from storage (no actor): the current block when the sync controller does not answer
  * @param peers
  *   handshaked peers, from the peer manager
  * @param sync
  *   the sync status, from the sync controller
  * @param local
  *   what the probe read on the node itself, at `now`: no actor, so always as fresh as the snapshot
  */
final case class NodeStatusSnapshot(
    now: Instant,
    maxPeers: Int,
    localBest: Long,
    peers: Sourced[Seq[TuiPeer]],
    sync: Sourced[SyncSample],
    local: LocalSample = LocalSample()
):
  def peerCount: Int = peers.value.fold(0)(_.size)

  def connectionStatus: String = peers match
    case Sourced.Unresponsive(_) => "Unresponsive (peer manager)"
    case _                       => if peerCount > 0 then "Connected" else "Searching for peers"

  def currentBlock: Long = sync.value.fold(localBest)(_.current)

  /** Highest known block of the network, or 0 when unknown. */
  def bestBlock: Long = sync.value.fold(0L)(_.best)

  def syncStatus: String = sync match
    case Sourced.Unresponsive(_) => "Unresponsive (sync controller)"
    case _                       => sync.value.fold("")(_.status)

  def snapSync: Option[SnapSyncState] = sync.value.flatMap(_.snap)

  /** This snapshot read at `now`. A snapshot no newer poll has replaced for `staleAfter` has both sources Stale, and
    * Unresponsive past [[Sourced.UnresponsiveAfter]], so a probe that hangs or dies shows instead of leaving its last
    * answers on screen as Fresh. With `staleAfter` at 5 s or more there is no Stale step: a late snapshot's sources go
    * straight to Unresponsive.
    */
  def agedAt(now: Instant, staleAfter: Duration): NodeStatusSnapshot =
    val age = Duration.between(this.now, now)
    if age.compareTo(staleAfter) <= 0 then this
    else
      def aged[A](source: Sourced[A]): Sourced[A] =
        if age.compareTo(Sourced.UnresponsiveAfter) > 0 then
          Sourced.Unresponsive(s"no status poll for ${age.toSeconds}s")
        else
          source match
            case Sourced.Fresh(last, at) => Sourced.Stale(last, at)
            case other                   => other
      copy(peers = aged(peers), sync = aged(sync))

/** Node settings displayed in the TUI. */
case class NodeSettings(
    dataDir: String = "",
    network: String = "",
    syncMode: String = "",
    pruningMode: String = "",
    maxPeers: Int = 0,
    rpcEnabled: Boolean = false,
    rpcPort: Int = 0,
    miningEnabled: Boolean = false,
    wsEnabled: Boolean = false,
    wsPort: Int = 0,
    ipcEnabled: Boolean = false,
    engineEnabled: Boolean = false,
    enginePort: Int = 0
)

/** A handshaked peer, as the peer manager's `GetPeersCmd` reports it (`network/Peer.scala`).
  *
  * @param id
  *   the peer id: the remote node id in hex, for a handshaked peer
  * @param address
  *   the remote address, `host:port`
  * @param incoming
  *   the peer dialled us
  * @param connectedAt
  *   when the connection was made
  */
final case class TuiPeer(id: String, address: String, incoming: Boolean, connectedAt: Instant)

/** What the probe reads on the node itself, without an actor (spec 027 research §6, FR-025): each `None` when the read
  * is not wired or failed.
  *
  * @param diskFree
  *   usable bytes on the datadir's file system
  * @param jvm
  *   the heap and the share of time in GC
  * @param discovery
  *   the discovery table (`NetworkMetrics`)
  * @param headTimestamp
  *   the best block header's timestamp, Unix seconds: where the ETH fork timeline stands
  * @param snapCounters
  *   SNAP's request counters (`SNAPSyncMetrics`)
  * @param snapPivot
  *   SNAP's pivot block (`SNAPSyncMetrics`), once one is chosen
  */
final case class LocalSample(
    diskFree: Option[Long] = None,
    jvm: Option[JvmSample] = None,
    discovery: Option[DiscoverySample] = None,
    headTimestamp: Option[Long] = None,
    snapCounters: Option[SnapCounters] = None,
    snapPivot: Option[Long] = None
)

/** The JVM's heap in bytes, and the share of wall time spent in GC between the last two polls (0 to 1). */
final case class JvmSample(heapUsed: Long, heapMax: Long, gcShare: Option[Double])

/** The discovery table: nodes known, pending connections, blacklisted. */
final case class DiscoverySample(known: Long, pending: Long, blacklisted: Long)

/** SNAP's request counters for this run (`SNAPSyncMetrics`), and the snap-capable peers now. */
final case class SnapCounters(
    accountRequests: Long,
    accountFailures: Long,
    storageRequests: Long,
    storageFailures: Long,
    bytecodeRequests: Long,
    bytecodeFailures: Long,
    healingRequests: Long,
    healingFailures: Long,
    timeouts: Long,
    snapCapablePeers: Long,
    blacklisted: Long
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

  /** Progress unchanged this long, in snapshot time, is a stall (§4.1, §2.3). */
  val StallAfter: Duration = Duration.ofSeconds(120)

  /** The celebration's length in frames: 3 s at 4 fps (§3.8). */
  val CelebrationFrames: Int = 12

  /** The celebration frames on which the header band flashes (§3.8). */
  val HeaderFlashFrames: Range = 2 to 6

  /** Dashboard rows the splash hands over per tick (§3.7). */
  val HandoverRowsPerTick: Int = 4

  /** The time of a state that has seen no snapshot and no start time: uptime and rates read 0. */
  val Origin: Instant = Instant.EPOCH

  /** Create an initial TUI state. */
  def initial: TuiState = TuiState()

  /** Create TUI state with network name. */
  def withNetwork(networkName: String): TuiState =
    TuiState(networkName = networkName)
