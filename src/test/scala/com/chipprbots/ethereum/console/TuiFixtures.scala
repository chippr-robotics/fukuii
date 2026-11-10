package com.chipprbots.ethereum.console

import java.time.Instant
import java.util.Locale

import com.chipprbots.ethereum.utils.ForkBlockNumbers
import com.chipprbots.ethereum.utils.ForkTimestamps

/** Named [[TuiState]] fixtures shared by the golden and property tests (spec 027 T004).
  *
  * Every fixture is at one fixed `now`, so a fixture renders the same frame on every run. The data is illustrative
  * (research C-10): it fixes layout and widths, not what a real node reported. Later slices add their own fixtures
  * here.
  */
object TuiFixtures:

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
      snapPivot: Option[Long] = None
  ): LocalSample =
    LocalSample(
      diskFree = Some((diskGiB * GiB).toLong),
      jvm = Some(JvmSample((heapGiB * GiB).toLong, (maxHeapGiB * GiB).toLong, Some(gcShare))),
      discovery = Some(DiscoverySample(known = 1_912L, pending = 14L, blacklisted = 3L)),
      headTimestamp = headTimestamp,
      snapCounters = snapCounters,
      snapPivot = snapPivot
    )

  /** `state` as the probe saw it at its `now`: both actors answered, `peers` handshaked, and `local` read. */
  def polled(state: TuiState, peers: Seq[TuiPeer], local: LocalSample): TuiState =
    state.copy(snapshot =
      Some(
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
          local = local
        )
      )
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
      local(diskGiB = 1_240.0, heapGiB = 2.1)
    )

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
      local(diskGiB = 1_230.0, heapGiB = 2.1, headTimestamp = Some(1_791_294_816L - (3 * 86_400L + 4 * 3_600L)))
    )

  /** Every fixture by name. */
  val all: Seq[(String, GlyphSet => TuiState)] = Seq(
    "synced" -> (g => syncedEtc(g)),
    "snap-storage" -> (g => snapStorageMordor(g)),
    "regular" -> (g => regularSyncEtc(g)),
    "sepolia-near-head" -> (g => sepoliaNearHead(g))
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
