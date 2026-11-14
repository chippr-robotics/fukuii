package com.chipprbots.ethereum.console

import java.time.Duration
import java.time.Instant

import org.apache.pekko.util.ByteString

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
  *   the view on screen (`1`–`9`, `l`, `[`, `]`, the prompt)
  * @param helpOpen
  *   Help is open over [[view]] (`?`, `h`, F1); any key but `q`, `r` and `d` closes it
  * @param screens
  *   the screens opened over [[view]] by Enter, the one on screen first; Esc closes it (spec 029 FR-005)
  * @param overlay
  *   what has the keyboard instead of the view: the prompt, a filter being typed, a dialog (spec 029 FR-007 to FR-009)
  * @param selectedPeer
  *   the id of the Peers list's selected row; kept by id, so the selection follows the peer when the order changes
  * @param filters
  *   the `/` filter of each view's list; an absent or empty one shows every row (spec 029 FR-008)
  * @param promptHistory
  *   the lines run at the prompt in this session, oldest first
  * @param notice
  *   what the last command had to say (an unknown command, a peer not found); it shows in the footer until the next key
  * @param chain
  *   the chain's identity and fork timeline, once the node has read its configuration
  * @param snapshot
  *   the latest status snapshot applied: each source's freshness, the peers, and what the probe read on the node
  * @param zeroPeersSince
  *   when the peer count, known, was last seen to become 0: the alert row's "0 peers for …" (§2.3)
  * @param logs
  *   the log ring's newest lines, oldest first ([[TuiLogRingAppender]]): the Logs view and the Recent strips
  * @param logScroll
  *   how many lines the Logs view is scrolled back from the newest; 0 follows the tail
  * @param scores
  *   the last two peer-score snapshots slice 4a published: the score column and its trend (FR-019, D-1)
  * @param hashrateZeroSince
  *   when the node's reported hashrate, with its DAG loaded, was last seen to become 0: the alert row's "no hashrate
  *   reported for …" (§2.3). It does not run while the DAG is being built, which submits nothing
  * @param clSeenSince
  *   when the probe first read a forkchoiceUpdated from the consensus layer: a payload never recorded counts from then,
  *   or from the node's start if that is later, so a consensus layer that attaches to a node that has been up for an
  *   hour is not measured against the hour
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
    zeroPeersSince: Option[Instant] = None,
    logs: Vector[LogLine] = Vector.empty,
    logScroll: Int = 0,
    scores: ScoreHistory = ScoreHistory(),
    hashrateZeroSince: Option[Instant] = None,
    clSeenSince: Option[Instant] = None,
    screens: List[Screen] = Nil,
    overlay: Overlay = Overlay.None,
    selectedPeer: Option[String] = None,
    filters: Map[TuiView, String] = Map.empty,
    promptHistory: Vector[String] = Vector.empty,
    notice: Option[String] = None
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
    // A reported hashrate of 0 with the DAG loaded starts the clock; a hashrate, or a DAG that is building, stops it; a
    // reading the probe could not take leaves it. The internal miner submits nothing while the DAG is built, so the
    // clock does not count that time (forge, slice 5).
    val zeroHashrateSince = status.local.mining match
      case Some(m) if m.dag.exists(_.loaded) && m.hashrate == 0L => hashrateZeroSince.orElse(Some(status.now))
      case Some(_)                                               => None
      case None                                                  => hashrateZeroSince
    val seenSince = clSeenSince.orElse(status.local.cl.flatMap(_.forkchoiceUpdated).map(_ => status.now))
    val next = copy(now = status.now)
      .withPeerCount(status.peerCount, status.maxPeers)
      .withBlockInfo(status.currentBlock, status.bestBlock)
      .withSyncStatus(status.syncStatus)
      .withConnectionStatus(status.connectionStatus)
      .copy(
        snapSyncState = snap,
        snapshot = Some(status),
        zeroPeersSince = zeroSince,
        scores = scores.next(status.local.peerScores),
        hashrateZeroSince = zeroHashrateSince,
        clSeenSince = seenSince
      )
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

  /** `1`–`5`, `l`: the view, with Help closed. A change of view cancels a catch-up on screen (FR-014); opening the Logs
    * view shows its newest lines.
    */
  def withView(next: TuiView): TuiState =
    if next == view && !helpOpen && screens.isEmpty then this
    else if next == view then copy(helpOpen = false, screens = Nil)
    else
      copy(
        view = next,
        helpOpen = false,
        screens = Nil,
        logScroll = if next == TuiView.Logs then 0 else logScroll
      ).cancelCatchup

  /** `]`: the next view, wrapping. */
  def nextView: TuiState = withView(view.next)

  /** `[`: the previous view, wrapping. */
  def previousView: TuiState = withView(view.previous)

  /** The filter of `v`'s list, trimmed; empty when there is none. */
  def filterOf(v: TuiView): String = filters.getOrElse(v, "").trim

  /** The handshaked peers as the Peers list shows them: highest score first, at the precision the column shows, so
    * peers it draws alike go by age; the unscored last; then the longest connected. With a filter, only the peers whose
    * id, address or client contains it.
    */
  def orderedPeers: Seq[TuiPeer] =
    val all = snapshot.flatMap(_.peers.value).getOrElse(Nil)
    val wanted = filterOf(TuiView.Peers).toLowerCase(java.util.Locale.ROOT)
    val kept =
      if wanted.isEmpty then all
      else
        val clients = snapshot.flatMap(_.details).flatMap(_.value).getOrElse(Map.empty)
        all.filter { p =>
          val text = s"${p.id} ${p.address} ${clients.get(p.id).fold("")(_.clientId)}"
          text.toLowerCase(java.util.Locale.ROOT).contains(wanted)
        }
    kept.sortBy { p =>
      val units = peerScore(p.id).map(scoreUnits)
      (units.isEmpty, units.fold(0L)(-_), p.connectedAt, p.id)
    }

  /** The selected row of the Peers list, if the selected peer is still listed. */
  def selectedPeerIndex: Option[Int] =
    selectedPeer.map(id => orderedPeers.indexWhere(_.id == id)).filter(_ >= 0)

  /** The log lines the Logs view lists: all of them, or with a filter the ones whose text or logger contains it. */
  def visibleLogs: Vector[LogLine] =
    val wanted = filterOf(TuiView.Logs).toLowerCase(java.util.Locale.ROOT)
    if wanted.isEmpty then logs
    else logs.filter(l => s"${l.level} ${l.logger} ${l.text}".toLowerCase(java.util.Locale.ROOT).contains(wanted))

  /** The screen opened over the view, if any. */
  def screen: Option[Screen] = if helpOpen then None else screens.headOption

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

  /** The log ring's lines, newest last. While the Logs view is scrolled back, the lines on screen stay on screen: the
    * scroll grows by the lines that arrived since.
    */
  def withLogs(lines: Vector[LogLine]): TuiState =
    val arrived = (for
      newest <- lines.lastOption
      before <- logs.lastOption
    yield (newest.seq - before.seq).max(0L)).getOrElse(0L)
    val scroll = if logScroll <= 0 then 0 else (logScroll + arrived).min((lines.size - 1).max(0).toLong).toInt
    copy(logs = lines, logScroll = scroll)

  /** `-` (`lines` > 0, older) and `+` (`lines` < 0, newer): the Logs view moves by `lines`, from the newest page (0) to
    * the oldest, whose first line is the ring's oldest.
    */
  def scrollLogs(lines: Int, page: Int): TuiState =
    // A scroll the arrival of new lines carried past the bound counts as the bound: the first `+` moves a page from the
    // oldest page, not from a place no key reaches (prism, slice 4 review).
    val bound = (visibleLogs.size - page.max(1)).max(0)
    copy(logScroll = (logScroll.min(bound) + lines).max(0).min(bound))

  /** A peer's score in the latest published snapshot; none for a peer it does not have, never zero (Q5), and none for a
    * score that is not finite: a NaN would draw as `0.000` and an infinity as a 19-digit number, so it is a score the
    * scorer has not given, which draws `—` and sorts last.
    */
  def peerScore(peerId: String): Option[Double] = scores.current.flatMap(_.scores.get(peerId)).filter(_.isFinite)

  /** The sign of a peer's score change from the previous published snapshot, at the precision the column shows
    * ([[TuiState.ScoreDigits]] decimals): the recency term drifts every score down a little between snapshots, which is
    * not a trend.
    */
  def scoreTrend(peerId: String): Option[Trend] =
    for
      now <- peerScore(peerId)
      before <- scores.previous.flatMap(_.scores.get(peerId)).filter(_.isFinite)
    yield scoreUnits(now).compareTo(scoreUnits(before)) match
      case d if d > 0 => Trend.Up
      case d if d < 0 => Trend.Down
      case _          => Trend.Steady

  /** The view's name, as the header shows it. */
  def viewTitle: String =
    if helpOpen then "Help"
    else
      screen match
        case Some(Screen.PeerDetail(_)) => "Peer"
        case None                       => view.title

  /** SNAP's pivot block, while SNAP's rows are on screen: the Block cell's target and the fork timeline's position
    * then, since the sync controller reports no block target and the local best block is far behind (B-044).
    */
  def snapPivot: Option[Long] =
    if bestBlock > 0 then None // a block target: another sync has taken over, its rows lingering or not
    else snapSyncState.flatMap(_ => snapshot.flatMap(_.local.snapPivot)).filter(_ > 0)

  /** How old the SNAP sample on screen is, if the probe said when it was taken. */
  def snapSampleAge: Option[Duration] =
    snapshot.flatMap(_.sync.value).flatMap(_.snapSampledAt).map(at => Duration.between(at, now))

/** Which way a peer's score went between the last two published snapshots (spec 027 FR-019, research §6). */
enum Trend:
  case Up, Down, Steady

/** Every peer's score at one instant, as slice 4a published it to `NetworkMetrics`, by peer id (the remote node id in
  * hex, as [[TuiPeer.id]]).
  */
final case class PeerScores(takenAt: Instant, scores: Map[String, Double])

/** The two latest published score snapshots: the trend is the change from `previous` to `current` (T098). The probe
  * reads the same snapshot every poll until the scorer publishes the next one (at most once a second on an event, every
  * 10 s on the peer manager's tick), so history moves on only when the snapshot's time changes.
  */
final case class ScoreHistory(previous: Option[PeerScores] = None, current: Option[PeerScores] = None):
  def next(published: Option[PeerScores]): ScoreHistory = published match
    case Some(p) if !current.exists(_.takenAt == p.takenAt) => ScoreHistory(current, Some(p))
    case _                                                  => this

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
  *   SNAP counts as running: the latest sample is of a running phase and younger than
  *   [[TuiStatusProbe.SnapRunningMaxAgeMillis]]. The SNAP rows stay on screen past their sample's 90 s, and the alert
  *   row warns of the sample's age, only while it holds; "Syncing (SNAP)" has its own, shorter test
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
  * @param details
  *   each handshaked peer's client id and best block, by peer id, from the network peer manager
  *   (`GetHandshakedPeersCmd`); None when the probe does not ask it
  * @param txpool
  *   the txpool's size and blob count, from the pending transactions manager; None when the probe does not ask it
  */
final case class NodeStatusSnapshot(
    now: Instant,
    maxPeers: Int,
    localBest: Long,
    peers: Sourced[Seq[TuiPeer]],
    sync: Sourced[SyncSample],
    local: LocalSample = LocalSample(),
    details: Option[Sourced[Map[String, PeerDetails]]] = None,
    txpool: Option[Sourced[TxpoolSample]] = None
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
      copy(peers = aged(peers), sync = aged(sync), details = details.map(aged), txpool = txpool.map(aged))

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

/** A handshaked peer's client id (sanitised, FR-020) and best block, as the network peer manager reports them. */
final case class PeerDetails(clientId: String, bestBlock: Long)

/** The txpool: pending transactions, and how many of them carry a blob sidecar. */
final case class TxpoolSample(size: Int, blobs: Int)

/** The last block import's size and how long its consensus call took (`RegularSyncMetrics`, C-7). */
final case class ImportSample(blocks: Int, duration: Duration)

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
  * @param peerScores
  *   the peer scores slice 4a last published to `NetworkMetrics`, once it has
  * @param lastImport
  *   the last block import regular sync timed (`RegularSyncMetrics`), once there is one
  * @param cl
  *   the consensus layer as the Engine API saw it: only on a node whose probe has the Engine API on a proof-of-stake
  *   chain, so its presence is what puts the consensus-layer panel on screen (E1-E3)
  * @param mining
  *   the node's mining metrics: only on an ETC node, so its presence is what puts the mining panel on screen (M1, M2)
  */
final case class LocalSample(
    diskFree: Option[Long] = None,
    jvm: Option[JvmSample] = None,
    discovery: Option[DiscoverySample] = None,
    headTimestamp: Option[Long] = None,
    snapCounters: Option[SnapCounters] = None,
    snapPivot: Option[Long] = None,
    peerScores: Option[PeerScores] = None,
    lastImport: Option[ImportSample] = None,
    cl: Option[ClSample] = None,
    mining: Option[MiningSample] = None
)

/** The Engine API's last call of one method (`EngineApiMetrics`, E1): when, and the status it was answered with. */
final case class ClCall(at: Instant, status: String):

  /** How long ago the call was at `now`: never negative, whichever clock is ahead. */
  def ageAt(now: Instant): Duration =
    val gap = Duration.between(at, now)
    if gap.isNegative then Duration.ZERO else gap

/** The node's count of `engine_newPayload` calls by status. `accepted` is counted apart, so `invalid` is the INVALID
  * ones alone: `engine_newpayload_invalid` has always counted the ACCEPTED ones with them. `syncing` is whatever the
  * node counted as SYNCING, which no caller records today.
  */
final case class PayloadCounts(total: Long, valid: Long, syncing: Long, accepted: Long, invalid: Long)

/** The block hashes of the last fork choice the node executed, as the consensus layer sent them (E2). */
final case class ForkChoiceHashes(head: ByteString, safe: ByteString, finalized: ByteString)

/** Whether the consensus layer is calling the Engine API. */
enum ClLink:
  /** No `engine_forkchoiceUpdated` yet. */
  case Waiting

  /** The last one is no older than [[ClSample.ClSilentAfter]]. */
  case Connected

  /** The last one is older: the consensus layer was calling and has stopped. */
  case Disconnected

/** The consensus layer as the Engine API saw it (spec 027 FR-022): the last call of each method, the payload counts,
  * the failed authentications, and the last executed fork choice.
  *
  * @param forkchoiceUpdated
  *   the last `engine_forkchoiceUpdated` the node recorded
  * @param newPayload
  *   the last `engine_newPayload` the node recorded. A payload the node already held is answered without a record, so
  *   it does not move this
  * @param authFailures
  *   requests the JWT check answered 401
  * @param head
  *   the head of the last fork choice the node executed, `0x` and its hex
  * @param safe
  *   the safe block's number, if the consensus layer named one and the node has its header
  * @param finalized
  *   the finalized block's number, likewise
  */
final case class ClSample(
    forkchoiceUpdated: Option[ClCall] = None,
    newPayload: Option[ClCall] = None,
    payloads: PayloadCounts = PayloadCounts(0L, 0L, 0L, 0L, 0L),
    authFailures: Long = 0L,
    head: Option[String] = None,
    safe: Option[Long] = None,
    finalized: Option[Long] = None
):

  /** The link, at `now`. */
  def link(now: Instant): ClLink = forkchoiceUpdated.map(_.ageAt(now)) match
    case None                                                         => ClLink.Waiting
    case Some(silent) if silent.compareTo(ClSample.ClSilentAfter) > 0 => ClLink.Disconnected
    case Some(_)                                                      => ClLink.Connected

object ClSample:

  /** The consensus layer calls `engine_forkchoiceUpdated` every slot, 12 s: a last call older than this is 5 slots of
    * silence, and the link is down.
    */
  val ClSilentAfter: Duration = Duration.ofSeconds(60)

  /** How long a node may be up, with no forkchoiceUpdated at all, before that is worth a warning: a consensus layer
    * that has not connected yet is not one that was lost.
    */
  val ClFirstCallGrace: Duration = Duration.ofSeconds(60)

/** What a DAG build is doing (`pow_dag_phase`). */
enum DagPhase:
  /** The build has started and does not yet say which it is: the cache is being made. */
  case Pending

  /** Loading the DAG from its file. */
  case Loading

  /** Generating the DAG. */
  case Generating

/** The DAG the miner is building or has built (M2): the ECIP-1099 epoch (60,000 blocks), the build's phase and percent,
  * and whether the DAG is in memory.
  */
final case class DagSample(epoch: Long, phase: DagPhase, progress: Int, loaded: Boolean)

/** The node's mining metrics (`PoWMiningMetrics`, M1 and M2).
  *
  * @param coinbase
  *   the address the miner pays, `0x` and its hex
  * @param hashrate
  *   the hashrate in hashes a second as last submitted (`eth_submitHashrate`, or the internal miner), 0 if none was. It
  *   is only ever set by a submission, so it does not fall when a miner goes quiet
  * @param lastMinedAt
  *   when the node last mined a block, if it has
  * @param getWork
  *   `eth_getWork` requests answered since the node started
  * @param getWorkPerMinute
  *   those over the last minute, once the probe has two readings at least 5 s apart
  * @param dag
  *   the DAG, once a build has started
  */
final case class MiningSample(
    coinbase: Option[String] = None,
    hashrate: Long = 0L,
    blocksMined: Long = 0L,
    lastMinedAt: Option[Instant] = None,
    staleShares: Long = 0L,
    getWork: Long = 0L,
    getWorkPerMinute: Option[Long] = None,
    dag: Option[DagSample] = None
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

  /** The score column's decimals, and the precision of the trend and of the order of the peers. */
  val ScoreDigits: Int = 3

  /** A score at the column's precision, in units of its last decimal: what two peers are compared by, so the order and
    * the trend say what the column shows.
    */
  def scoreUnits(score: Double): Long = math.round(score * math.pow(10, ScoreDigits))

  /** Create an initial TUI state. */
  def initial: TuiState = TuiState()

  /** Create TUI state with network name. */
  def withNetwork(networkName: String): TuiState =
    TuiState(networkName = networkName)
