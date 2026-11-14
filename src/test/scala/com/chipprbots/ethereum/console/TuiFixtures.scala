package com.chipprbots.ethereum.console

import java.time.Duration
import java.time.Instant
import java.util.Locale

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.LoggingEvent
import org.slf4j.LoggerFactory

import com.chipprbots.ethereum.utils.ForkBlockNumbers
import com.chipprbots.ethereum.utils.ForkTimestamps

/** Named [[TuiState]] fixtures shared by the golden and property tests (spec 027 T004).
  *
  * Every fixture is at one fixed `now`, so a fixture renders the same frame on every run. The data is illustrative
  * (research C-10): it fixes layout and widths, not what a real node reported. Later slices add their own fixtures
  * here.
  */
object TuiFixtures:

  /** The columns the sidebar and its divider take from the body where it is shown (spec 029 FR-006). */
  val SidebarCols: Int = widgets.Sidebar.Cols + 1

  /** A row of a `w` × `h` frame without the sidebar's columns (spec 029 FR-006): what the view's panels drew. The
    * sidebar's text is one column a character, so the cut is by characters.
    */
  def body(row: String, w: Int, h: Int): String =
    if widgets.Sidebar.shown(w, h) then row.drop(widgets.Sidebar.Cols + 1) else row

  /** The rows of a `w` × `h` frame as text, the sidebar's columns cut from the body rows: the header, its separator,
    * the alert row and the footer span the screen and are kept whole. What a spec that reads a panel's rows wants.
    */
  def panelRows(rows: Seq[String], w: Int, h: Int): Seq[String] =
    rows.zipWithIndex.map((row, i) => if i >= 3 && i < rows.size - 1 then body(row, w, h) else row)

  /** The art shipped with the node, read once for every spec that draws it. */
  lazy val art: gfx.ArtStore = gfx.ArtStore.bundled()

  /** A terminal that answered both questions, with the VGA cell the art is drawn for. */
  val Cell: gfx.CellSize = gfx.CellSize(8, 16)
  val KittyCaps: TerminalCaps = TerminalCaps(kittyGraphics = true, cell = Some(Cell), colors = 256, multiplexer = false)

  /** The fixtures' "now". */
  val Now: Instant = Instant.parse("2026-11-07T12:00:00Z")

  /** A clock stopped at [[Now]]: where a test TUI reads its snapshots ([[TuiTestTerminal.tui]]). */
  val clock: java.time.Clock = java.time.Clock.fixed(Now, java.time.ZoneOffset.UTC)

  /** The locale that breaks default case conversion: there "I".toLowerCase is "ı" and "i".toUpperCase is "İ". */
  val Turkish: Locale = Locale.forLanguageTag("tr-TR")

  /** Runs `f` with the JVM's default locale set to `locale`, and restores it afterwards. */
  def withDefaultLocale[A](locale: Locale)(f: => A): A =
    val previous = Locale.getDefault
    Locale.setDefault(locale)
    try f
    finally Locale.setDefault(previous)

  /** The version the L-tier header shows: fixed, so the goldens do not change with each release. */
  val Version: String = "0.11.0"

  private val GiB: Double = 1024.0 * 1024 * 1024

  /** ETC mainnet's block-number forks (core-geth `params/config_classic.go`, as `MilestoneLogSpec` has them); the
    * ETH-only forks and Olympia are not scheduled.
    */
  val etcForks: ForkBlockNumbers = ForkBlockNumbers(
    frontierBlockNumber = 0,
    homesteadBlockNumber = 1_150_000,
    eip106BlockNumber = Long.MaxValue,
    eip150BlockNumber = 2_500_000,
    eip155BlockNumber = 3_000_000,
    eip160BlockNumber = 3_000_000,
    eip161BlockNumber = Long.MaxValue,
    difficultyBombPauseBlockNumber = 3_000_000,
    difficultyBombContinueBlockNumber = 5_000_000,
    difficultyBombRemovalBlockNumber = 5_900_000,
    byzantiumBlockNumber = Long.MaxValue,
    constantinopleBlockNumber = Long.MaxValue,
    istanbulBlockNumber = Long.MaxValue,
    atlantisBlockNumber = 8_772_000,
    aghartaBlockNumber = 9_573_000,
    phoenixBlockNumber = 10_500_839,
    petersburgBlockNumber = Long.MaxValue,
    ecip1099BlockNumber = 11_700_000,
    muirGlacierBlockNumber = Long.MaxValue,
    magnetoBlockNumber = 13_189_133,
    berlinBlockNumber = Long.MaxValue,
    mystiqueBlockNumber = 14_525_000,
    spiralBlockNumber = 19_250_000,
    olympiaBlockNumber = Long.MaxValue
  )

  /** Mordor's block-number forks (core-geth `params/config_mordor.go`). */
  val mordorForks: ForkBlockNumbers = etcForks.copy(
    homesteadBlockNumber = 0,
    eip150BlockNumber = 0,
    eip155BlockNumber = 0,
    eip160BlockNumber = 0,
    difficultyBombPauseBlockNumber = 0,
    difficultyBombContinueBlockNumber = 0,
    difficultyBombRemovalBlockNumber = 0,
    atlantisBlockNumber = 0,
    aghartaBlockNumber = 301_243,
    phoenixBlockNumber = 999_983,
    ecip1099BlockNumber = 2_520_000,
    magnetoBlockNumber = 3_985_893,
    mystiqueBlockNumber = 5_520_000,
    spiralBlockNumber = 9_957_000
  )

  /** Sepolia's timestamp forks (`conf/base/chains/sepolia-chain.conf`), Amsterdam included (C-5). */
  val sepoliaForks: ForkTimestamps = ForkTimestamps(
    shanghaiTimestamp = Some(1_677_557_088L),
    cancunTimestamp = Some(1_706_655_072L),
    pragueTimestamp = Some(1_741_159_776L),
    osakaTimestamp = Some(1_760_427_360L),
    amsterdamTimestamp = Some(1_791_294_816L),
    bpo1Timestamp = Some(1_761_017_184L),
    bpo2Timestamp = Some(1_761_607_008L)
  )

  val chainEtc: ChainFacts = ChainFacts.byBlock(BigInt(61), etcForks, Version)
  val chainMordor: ChainFacts = ChainFacts.byBlock(BigInt(63), mordorForks, Version)
  val chainSepolia: ChainFacts = ChainFacts.byTimestamp(BigInt(11_155_111), sepoliaForks, Version)

  private def settings(
      network: String,
      syncMode: String,
      maxPeers: Int,
      mining: Boolean,
      engine: Boolean = false
  ): NodeSettings =
    NodeSettings(
      dataDir = s"/var/lib/fukuii/$network",
      network = network,
      syncMode = syncMode,
      pruningMode = "archive",
      maxPeers = maxPeers,
      rpcEnabled = true,
      rpcPort = 8545,
      miningEnabled = mining,
      engineEnabled = engine,
      enginePort = 8551
    )

  /** `n` handshaked peers: the first connected longest ago, every third one inbound. */
  def peerList(n: Int): Seq[TuiPeer] =
    (0 until n).map { i =>
      val id = f"${(i.toLong * 2_654_435_761L + 0x3fa1L) & 0xffffffffL}%08x" * 16
      TuiPeer(
        id = id,
        address = s"203.0.113.${10 + i}:30303",
        incoming = i % 3 == 0,
        connectedAt = Now.minusSeconds(15_000L - i * 337L)
      )
    }

  /** What the probe reads on the node itself: disk free in GiB, the JVM, the discovery table. */
  def local(
      diskGiB: Double = 388.0,
      heapGiB: Double = 3.4,
      maxHeapGiB: Double = 8.0,
      gcShare: Double = 0.003,
      headTimestamp: Option[Long] = None,
      snapCounters: Option[SnapCounters] = None,
      snapPivot: Option[Long] = None,
      lastImport: Option[ImportSample] = None,
      cl: Option[ClSample] = None,
      mining: Option[MiningSample] = None
  ): LocalSample =
    LocalSample(
      diskFree = Some((diskGiB * GiB).toLong),
      jvm = Some(JvmSample((heapGiB * GiB).toLong, (maxHeapGiB * GiB).toLong, Some(gcShare))),
      discovery = Some(DiscoverySample(known = 1_912L, pending = 14L, blacklisted = 3L)),
      headTimestamp = headTimestamp,
      snapCounters = snapCounters,
      snapPivot = snapPivot,
      lastImport = lastImport,
      cl = cl,
      mining = mining
    )

  // ---- Slice 4: the peers' details and scores, the txpool, the log tail ----------------------------------------------

  /** Client ids as peers send them in Hello: name, version, platform, runtime. */
  private val clientIds: Vector[String] = Vector(
    "CoreGeth/v1.12.19-stable-c2b6d2a5/linux-amd64/go1.21.5",
    "besu/v24.1.2/linux-x86_64/openjdk-java-21",
    "Geth/v1.13.5-stable-916d6a44/linux-amd64/go1.21.4",
    "fukuii/v0.11.0/linux/java-25",
    "Nethermind/v1.25.4+20b10b35/linux-x64/dotnet8.0.0"
  )

  /** Each peer's client id and best block (`GetHandshakedPeersCmd`): the best block at or just under `best`. */
  def peerDetails(peers: Seq[TuiPeer], best: Long = 21_884_102L): Map[String, PeerDetails] =
    peers.zipWithIndex.map((p, i) => p.id -> PeerDetails(clientIds(i % clientIds.size), best - i % 3)).toMap

  /** Every seventh peer has no published score yet (a peer the scorer has not seen an event of). */
  private def scoredPeers(peers: Seq[TuiPeer]): Seq[(TuiPeer, Int)] = peers.zipWithIndex.filter((_, i) => i % 7 != 6)

  private def scoreOf(i: Int): Double = 0.95 - ((i * 37) % 60) / 100.0

  /** The scores slice 4a published 4 s before [[Now]]. */
  def peerScores(peers: Seq[TuiPeer]): PeerScores =
    PeerScores(Now.minusSeconds(4), scoredPeers(peers).map((p, i) => p.id -> scoreOf(i)).toMap)

  /** The snapshot before, 10 s earlier: by peer, a third of the scores lower (now up), a third equal, a third higher.
    */
  def previousScores(peers: Seq[TuiPeer]): PeerScores =
    PeerScores(
      Now.minusSeconds(14),
      scoredPeers(peers).map { (p, i) =>
        p.id -> (i % 3 match
          case 0 => scoreOf(i) - 0.012
          case 1 => scoreOf(i)
          case _ => scoreOf(i) + 0.009
        )
      }.toMap
    )

  /** Each scored peer's trend between [[previousScores]] and [[peerScores]], the highest score first. */
  def trends(peers: Seq[TuiPeer]): Seq[(TuiPeer, Trend)] =
    scoredPeers(peers)
      .sortBy((_, i) => -scoreOf(i))
      .map((p, i) =>
        p -> (i % 3 match
          case 0 => Trend.Up
          case 1 => Trend.Steady
          case _ => Trend.Down
        )
      )

  /** A logging event at `at`, for a test that feeds a ring directly (the test root logger is at ERROR). */
  def logEvent(
      text: String,
      level: Level = Level.INFO,
      logger: String = "com.chipprbots.ethereum.Fukuii",
      at: Instant = Now
  ): LoggingEvent =
    val context = LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext]
    val event = new LoggingEvent(getClass.getName, context.getLogger(logger), level, text, null, null)
    event.setTimeStamp(at.toEpochMilli)
    event

  private val Sync = "com.chipprbots.ethereum.blockchain.sync"
  private val Net = "com.chipprbots.ethereum.network"

  /** The log ring's newest lines, oldest first, as a node at its head logs them (research §3.2–§3.6): sync and other
    * loggers, a warning and an error, and the worm bar the log draws.
    */
  val recentLogs: Vector[LogLine] = Vector(
    ("11:58:21", "INFO", s"$Net.PeerManagerActor", "Handshaked peer 0x3fa1…e2 CoreGeth/v1.12.19"),
    ("11:58:34", "INFO", s"$Sync.regular.RegularSync", "RegularSync: current=21884097 best=21884102 lag=5"),
    ("11:58:47", "INFO", s"$Sync.regular.BlockImporter", "Imported block 21,884,098 0x1f09…88a1 with 17 txs in 26ms"),
    ("11:59:00", "WARN", s"$Net.PeerActor", "Peer 0x9c0d…77 timed out on GetBlockHeaders, retrying"),
    ("11:59:04", "INFO", s"$Sync.regular.BlockImporter", "Imported block 21,884,099 0x7ab2…d4c7 with 6 txs in 14ms"),
    ("11:59:12", "INFO", "com.chipprbots.ethereum.jsonrpc.EthMiningService", "eth_getWork served for block 21,884,100"),
    ("11:59:17", "INFO", s"$Sync.regular.BlockImporter", "Imported block 21,884,100 0x3301…2e9d with 21 txs in 31ms"),
    ("11:59:25", "INFO", s"$Sync.regular.RegularSync", "[====================🪱]🧠 100% — RegularSync"),
    ("11:59:30", "ERROR", s"$Net.rlpx.RLPxConnectionHandler", "Cannot decode a message from 203.0.113.40:30303"),
    ("11:59:34", "INFO", s"$Sync.regular.BlockImporter", "Imported block 21,884,101 0x9b0c…ff31 with 9 txs in 17ms"),
    ("11:59:47", "INFO", s"$Sync.regular.BlockImporter", "Imported block 21,884,102 0x6d21…1a0e with 14 txs in 22ms"),
    ("11:59:52", "INFO", s"$Net.PeerManagerActor", "Peers: 41 handshaked · 14 in · 27 out"),
    ("12:00:00", "INFO", s"$Sync.regular.RegularSync", "RegularSync: at head, block 21884102")
  ).zipWithIndex.map { case ((time, level, logger, text), i) => LogLine(9_000L + i, time, level, logger, text) }

  /** `state` as the probe saw it at its `now`: every actor answered, `peers` handshaked with their details and scores,
    * the txpool, and `local` read; and the log ring's [[recentLogs]].
    */
  def polled(
      state: TuiState,
      peers: Seq[TuiPeer],
      local: LocalSample,
      txpool: TxpoolSample = TxpoolSample(142, 0)
  ): TuiState =
    val best = Seq(state.bestBlock, state.currentBlock, local.snapPivot.getOrElse(0L)).max
    val scores = peerScores(peers)
    state.copy(
      snapshot = Some(
        NodeStatusSnapshot(
          now = state.now,
          maxPeers = state.maxPeers,
          localBest = state.currentBlock,
          peers = Sourced.Fresh(peers, state.now),
          sync = Sourced.Fresh(
            SyncSample(
              state.currentBlock,
              state.bestBlock,
              state.syncStatus,
              state.snapSyncState,
              state.snapSyncState.map(_ => state.now.minusSeconds(12L)),
              snapRunning = state.snapSyncState.isDefined
            ),
            state.now
          ),
          local = local.copy(peerScores = Some(scores)),
          details = Some(Sourced.Fresh(peerDetails(peers, best), state.now)),
          txpool = Some(Sourced.Fresh(txpool, state.now))
        )
      ),
      scores = ScoreHistory(Some(previousScores(peers)), Some(scores)),
      logs = recentLogs
    )

  /** `state` with its peer source replaced. */
  def withPeers(state: TuiState, peers: Sourced[Seq[TuiPeer]]): TuiState =
    state.copy(snapshot = state.snapshot.map(_.copy(peers = peers)))

  /** ETC mainnet at head: up 12 days, 41 of 50 peers. */
  def syncedEtc(glyphs: GlyphSet = GlyphSet.Emoji): TuiState =
    polled(
      TuiState(
        networkName = "etc",
        connectionStatus = "Connected",
        peerCount = 41,
        maxPeers = 50,
        currentBlock = 21_884_102L,
        bestBlock = 21_884_102L,
        syncStatus = "Synced",
        startTime = Now.minusSeconds(12 * 86_400L + 7 * 3_600L),
        nodeSettings = settings("etc", "SNAP", 50, mining = true),
        glyphs = glyphs,
        now = Now,
        chain = Some(chainEtc)
      ),
      peerList(41),
      local()
    )

  /** The SNAP counters of [[snapStorageMordor]] (research §3.5). */
  val snapCounters: SnapCounters = SnapCounters(
    accountRequests = 48_112L,
    accountFailures = 31L,
    storageRequests = 210_004L,
    storageFailures = 402L,
    bytecodeRequests = 9_331L,
    bytecodeFailures = 12L,
    healingRequests = 0L,
    healingFailures = 0L,
    timeouts = 118L,
    snapCapablePeers = 9L,
    blacklisted = 2L
  )

  /** Mordor in SNAP's bytecode-and-storage phase, as production shows it: the sync controller reports no block target
    * (B-044), the local best block is 0 on a fresh datadir, and the pivot is 9,118,000. Accounts done, storage at 37 %,
    * 412K bytecodes found so far, healing queued.
    */
  def snapStorageMordor(glyphs: GlyphSet = GlyphSet.Emoji): TuiState =
    polled(
      TuiState(
        networkName = "mordor",
        connectionStatus = "Connected",
        peerCount = 11,
        maxPeers = 50,
        currentBlock = 0L,
        bestBlock = 0L,
        syncStatus = "Syncing (SNAP)",
        startTime = Now.minusSeconds(2 * 3_600L + 11 * 60L),
        nodeSettings = settings("mordor", "SNAP", 50, mining = false),
        snapSyncState = Some(
          SnapSyncState(
            phase = "ByteCodeAndStorageSync",
            accountsSynced = 34_100_000L,
            bytecodesDownloaded = 412_000L,
            storageSlotsSynced = 119_600_000L,
            nodesHealed = 0L,
            elapsedSeconds = 7_860.0,
            accountsPerSec = 4_800.0,
            bytecodesPerSec = 288.0,
            slotsPerSec = 18_900.0,
            recentBytecodesPerSec = 310.0,
            recentSlotsPerSec = 21_400.0,
            phaseProgress = 37,
            // SyncProgress.globalProgress for this phase and phaseProgress: (1 + 0.37) / 5, the log's Overall worm.
            overall = 0.274,
            estimatedTotalAccounts = 34_100_000L,
            estimatedTotalBytecodes = 600_000L,
            estimatedTotalSlots = 323_000_000L,
            etaSeconds = Some(7_080L),
            storageContractsCompleted = 2_114,
            storageContractsTotal = 5_702,
            chainHeaders = BigInt(4_210_000),
            chainTarget = BigInt(9_118_000),
            phaseElapsedSeconds = 4_080.0
          )
        ),
        glyphs = glyphs,
        now = Now,
        chain = Some(chainMordor)
      ),
      peerList(11),
      local(
        diskGiB = 412.0,
        heapGiB = 5.1,
        gcShare = 0.012,
        snapCounters = Some(snapCounters),
        snapPivot = Some(9_118_000L)
      )
    )

  /** ETC mainnet in regular sync, 28 % of the way, 101,220 blocks imported in the 549 s since the TUI first looked. */
  def regularSyncEtc(glyphs: GlyphSet = GlyphSet.Emoji): TuiState =
    polled(
      TuiState(
        networkName = "etc",
        connectionStatus = "Connected",
        peerCount = 23,
        maxPeers = 50,
        currentBlock = 18_402_113L,
        bestBlock = 21_884_102L,
        syncStatus = "Syncing",
        startTime = Now.minusSeconds(9 * 60L),
        nodeSettings = settings("etc", "Full", 50, mining = false),
        syncBaseline = Some(SyncBaseline(18_300_893L, Now.minusSeconds(549L))),
        glyphs = glyphs,
        now = Now,
        chain = Some(chainEtc)
      ),
      peerList(23),
      local(diskGiB = 1_240.0, heapGiB = 2.1, lastImport = Some(ImportSample(3, Duration.ofMillis(61))))
    )

  /** [[regularSyncEtc]] early on, at 11 %: on the S track (20 slots) its worm is at cell 2, where the ripple draws
    * ticks 1 and 2 (mod 3) alike (prism, on slice 3).
    */
  def earlyRegularEtc(glyphs: GlyphSet = GlyphSet.Emoji): TuiState =
    val s = regularSyncEtc(glyphs)
    s.copy(currentBlock = 2_407_251L, syncBaseline = Some(SyncBaseline(2_306_031L, Now.minusSeconds(549L))))

  /** Sepolia two blocks behind the head, its head 3 days and 4 hours before Amsterdam. */
  def sepoliaNearHead(glyphs: GlyphSet = GlyphSet.Emoji): TuiState =
    polled(
      TuiState(
        networkName = "sepolia",
        connectionStatus = "Connected",
        peerCount = 23,
        maxPeers = 50,
        currentBlock = 7_412_009L,
        bestBlock = 7_412_011L,
        syncStatus = "Syncing",
        startTime = Now.minusSeconds(3 * 86_400L + 4 * 3_600L),
        nodeSettings = settings("sepolia", "SNAP", 50, mining = false, engine = true),
        syncBaseline = Some(SyncBaseline(7_411_997L, Now.minusSeconds(144L))),
        glyphs = glyphs,
        now = Now,
        chain = Some(chainSepolia)
      ),
      peerList(23),
      local(
        diskGiB = 1_230.0,
        heapGiB = 2.1,
        headTimestamp = Some(1_791_294_816L - (3 * 86_400L + 4 * 3_600L)),
        cl = Some(clConnected)
      ),
      TxpoolSample(1_204, 3)
    )

  // ---- Slice 5: the consensus-layer and mining panels (spec 027 T108-T111, research §3.3, §3.4, §3.9) ---------------

  /** An Engine API call `agoSeconds` before [[Now]]. */
  def clCall(agoSeconds: Long, status: String = "VALID"): ClCall = ClCall(Now.minusSeconds(agoSeconds), status)

  /** A block hash: `0x` and the first four hex digits, then zeros, then the last four. */
  private def hash(first: String, last: String): String = "0x" + first + "0" * 56 + last

  /** The consensus layer of research §3.3: both calls 4 s ago, VALID. The payloads are counted as the node counts them:
    * nothing ever increments SYNCING, and an ACCEPTED payload has its own count, so this is VALID and ACCEPTED, not the
    * mockup's "SYNCING 4".
    */
  val clConnected: ClSample = ClSample(
    forkchoiceUpdated = Some(clCall(4)),
    newPayload = Some(clCall(4)),
    payloads = PayloadCounts(total = 184_203L, valid = 184_199L, syncing = 0L, accepted = 4L, invalid = 0L),
    authFailures = 0L,
    head = Some(hash("7b3e", "a91c")),
    safe = Some(7_411_977L),
    finalized = Some(7_411_945L)
  )

  /** Research §3.9: the last forkchoiceUpdated 6m 3s ago, the last newPayload 6m 5s ago. */
  val clSilent: ClSample = clConnected.copy(forkchoiceUpdated = Some(clCall(363)), newPayload = Some(clCall(365)))

  /** `state`, its probe having read `f` of what it read. */
  def withLocal(state: TuiState, f: LocalSample => LocalSample): TuiState =
    state.copy(snapshot = state.snapshot.map(s => s.copy(local = f(s.local))))

  /** [[sepoliaNearHead]] whose consensus layer has not called for 6m 3s: DISCONNECTED (§3.9). */
  def clDisconnectedSepolia(glyphs: GlyphSet = GlyphSet.Emoji): TuiState =
    withLocal(sepoliaNearHead(glyphs), _.copy(cl = Some(clSilent)))

  /** [[sepoliaNearHead]], 20 s after it started, with no call from a consensus layer yet. */
  def clWaitingSepolia(glyphs: GlyphSet = GlyphSet.Emoji): TuiState =
    withLocal(sepoliaNearHead(glyphs).copy(startTime = Now.minusSeconds(20L)), _.copy(cl = Some(ClSample())))

  /** The mining of research §3.4: 48.2 MH/s, 3 blocks mined (the last 2h 14m ago), no stale share, 1,204 getWork
    * requests, 20 a minute. The DAG is for epoch 364: the ECIP-1099 epoch (60,000 blocks) of block 21,884,102. The
    * mockup's "731" is the 30,000-block number of the same block.
    */
  val miningOn: MiningSample = MiningSample(
    coinbase = Some("0x4f2a0b9c" + "0" * 24 + "0000c91e"),
    hashrate = 48_200_000L,
    blocksMined = 3L,
    lastMinedAt = Some(Now.minusSeconds(2 * 3_600L + 14 * 60L)),
    staleShares = 0L,
    getWork = 1_204L,
    getWorkPerMinute = Some(20L),
    dag = Some(DagSample(epoch = 364L, phase = DagPhase.Generating, progress = 100, loaded = true))
  )

  /** [[syncedEtc]] mining: the panel of research §3.4. */
  def miningEtc(glyphs: GlyphSet = GlyphSet.Emoji): TuiState =
    withLocal(syncedEtc(glyphs), _.copy(mining = Some(miningOn)))

  /** [[miningEtc]] whose DAG is being generated: 37 %, nothing mined or submitted yet. */
  def dagGeneratingEtc(glyphs: GlyphSet = GlyphSet.Emoji): TuiState =
    withLocal(
      syncedEtc(glyphs),
      _.copy(mining =
        Some(
          MiningSample(
            coinbase = miningOn.coinbase,
            dag = Some(DagSample(epoch = 364L, phase = DagPhase.Generating, progress = 37, loaded = false))
          )
        )
      )
    )

  /** [[miningEtc]] with the DAG loaded and no hashrate reported for 12 minutes: the warning. */
  def noHashrateEtc(glyphs: GlyphSet = GlyphSet.Emoji): TuiState =
    withLocal(
      syncedEtc(glyphs).copy(hashrateZeroSince = Some(Now.minusSeconds(12 * 60L))),
      _.copy(mining = Some(miningOn.copy(hashrate = 0L, blocksMined = 0L, lastMinedAt = None, getWorkPerMinute = None)))
    )

  /** An ETC node whose internal miner is off but that external miners use: they submit a hashrate and ask for work. */
  def miningExternalEtc(glyphs: GlyphSet = GlyphSet.Emoji): TuiState =
    val s = withLocal(
      syncedEtc(glyphs),
      _.copy(mining = Some(miningOn.copy(blocksMined = 0L, lastMinedAt = None, dag = None)))
    )
    s.copy(nodeSettings = s.nodeSettings.copy(miningEnabled = false))

  /** An ETC node with mining off: the mining panel is one line. */
  def miningOffEtc(glyphs: GlyphSet = GlyphSet.Emoji): TuiState =
    val s = withLocal(syncedEtc(glyphs), _.copy(mining = Some(MiningSample())))
    s.copy(nodeSettings = s.nodeSettings.copy(miningEnabled = false))

  /** Every fixture by name. */
  val all: Seq[(String, GlyphSet => TuiState)] = Seq(
    "synced" -> (g => syncedEtc(g)),
    "snap-storage" -> (g => snapStorageMordor(g)),
    "regular" -> (g => regularSyncEtc(g)),
    "sepolia-near-head" -> (g => sepoliaNearHead(g)),
    "cl-disconnected" -> (g => clDisconnectedSepolia(g)),
    "cl-waiting" -> (g => clWaitingSepolia(g)),
    "mining" -> (g => miningEtc(g)),
    "dag-generating" -> (g => dagGeneratingEtc(g)),
    "no-hashrate" -> (g => noHashrateEtc(g)),
    "mining-off" -> (g => miningOffEtc(g)),
    "mining-external" -> (g => miningExternalEtc(g))
  )

  /** A poll at `now` that both actors answered: `peers` of 50, the sync controller at `current` of `best`. */
  def snapshot(current: Long, best: Long, now: Instant = Now, peers: Int = 23): NodeStatusSnapshot =
    NodeStatusSnapshot(
      now = now,
      maxPeers = 50,
      localBest = current,
      peers = Sourced.Fresh(peerList(peers), now),
      sync = Sourced.Fresh(SyncSample(current, best, if current >= best then "Synced" else "Syncing"), now)
    )

  /** [[regularSyncEtc]] whose head has not moved for 3m 10s (research §3.9). */
  def stalledEtc(glyphs: GlyphSet = GlyphSet.Emoji): TuiState =
    regularSyncEtc(glyphs).copy(lastAdvanceAt = Some(Now.minusSeconds(190L)))

  // ---- The alert fixtures (spec 027 T066, research §3.9) ------------------------------------------------------------

  /** [[syncedEtc]] with no peer for 4m 12s. */
  def zeroPeersEtc(glyphs: GlyphSet = GlyphSet.Emoji): TuiState =
    withPeers(
      syncedEtc(glyphs).copy(
        peerCount = 0,
        connectionStatus = "Searching for peers",
        zeroPeersSince = Some(Now.minusSeconds(252L))
      ),
      Sourced.Fresh(Nil, Now)
    )

  /** [[regularSyncEtc]] whose peer manager has never answered. */
  def unresponsiveEtc(glyphs: GlyphSet = GlyphSet.Emoji): TuiState =
    withPeers(
      regularSyncEtc(glyphs).copy(peerCount = 0, connectionStatus = "Unresponsive (peer manager)"),
      Sourced.Unresponsive("no reply within 500ms")
    )

  /** [[regularSyncEtc]] with 14.2G free and 6 of 50 peers: two warnings. */
  def lowDiskEtc(glyphs: GlyphSet = GlyphSet.Emoji): TuiState =
    val s = regularSyncEtc(glyphs).copy(peerCount = 6)
    polled(s, peerList(6), local(diskGiB = 14.2, heapGiB = 2.1))

  /** The alert fixtures by name. */
  val alerts: Seq[(String, GlyphSet => TuiState)] = Seq(
    "zero-peers" -> (g => zeroPeersEtc(g)),
    "unresponsive" -> (g => unresponsiveEtc(g)),
    "stalled" -> (g => stalledEtc(g)),
    "low-disk" -> (g => lowDiskEtc(g))
  )

  /** [[syncedEtc]] at `frame` of the celebration that started at tick 0 (research §3.8). */
  def celebration(glyphs: GlyphSet = GlyphSet.Emoji, frame: Int): TuiState =
    syncedEtc(glyphs).copy(tick = frame.toLong, celebrationStartedTick = Some(0L), celebrated = true)

  /** The splash at `tick` of 12, on an ETC node that is starting (research §3.7). */
  def splash(glyphs: GlyphSet = GlyphSet.Emoji, tick: Long): TuiState =
    TuiState(
      networkName = "etc",
      connectionStatus = "Starting node...",
      startTime = Now,
      now = Now,
      glyphs = glyphs,
      tick = tick,
      splashUntilTick = Some(12L)
    )

  /** A clock the test moves. */
  final class TestClock(start: Instant) extends java.time.Clock:
    @volatile private var current: Instant = start
    def advance(seconds: Long): Unit = current = current.plusSeconds(seconds)
    def advanceMillis(millis: Long): Unit = current = current.plusMillis(millis)
    override def instant(): Instant = current
    override def getZone: java.time.ZoneId = java.time.ZoneOffset.UTC
    override def withZone(zone: java.time.ZoneId): java.time.Clock = this
