package com.chipprbots.ethereum.console

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** Named [[TuiState]] fixtures shared by the golden and property tests (spec 027 T004).
  *
  * Every fixture reads time from one fixed clock, so a fixture renders the same frame on every run. The data is
  * illustrative (research C-10): it fixes layout and widths, not what a real node reported. Later slices add their own
  * fixtures here.
  */
object TuiFixtures:

  /** The fixtures' "now". */
  val Now: Instant = Instant.parse("2026-11-07T12:00:00Z")

  val clock: Clock = Clock.fixed(Now, ZoneOffset.UTC)

  private def settings(network: String, syncMode: String, maxPeers: Int, mining: Boolean): NodeSettings =
    NodeSettings(
      dataDir = s"/var/lib/fukuii/$network",
      network = network,
      syncMode = syncMode,
      pruningMode = "archive",
      maxPeers = maxPeers,
      rpcEnabled = true,
      rpcPort = 8545,
      miningEnabled = mining
    )

  /** ETC mainnet at head: up 12 days, 41 of 50 peers. */
  def syncedEtc(glyphs: GlyphSet = GlyphSet.Emoji): TuiState =
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
      clock = clock
    )

  /** Mordor in SNAP's bytecode-and-storage phase: accounts done, storage at 37 %, bytecodes at 68 %, healing queued. */
  def snapStorageMordor(glyphs: GlyphSet = GlyphSet.Emoji): TuiState =
    TuiState(
      networkName = "mordor",
      connectionStatus = "Connected",
      peerCount = 11,
      maxPeers = 50,
      currentBlock = 0L,
      bestBlock = 9_118_004L,
      syncStatus = "Syncing",
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
      clock = clock
    )

  /** ETC mainnet in regular sync, 28 % of the way, 101,220 blocks imported in the 549 s since the TUI first looked. */
  def regularSyncEtc(glyphs: GlyphSet = GlyphSet.Emoji): TuiState =
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
      clock = clock
    )

  /** Sepolia two blocks behind the head. */
  def sepoliaNearHead(glyphs: GlyphSet = GlyphSet.Emoji): TuiState =
    TuiState(
      networkName = "sepolia",
      connectionStatus = "Connected",
      peerCount = 23,
      maxPeers = 50,
      currentBlock = 7_412_009L,
      bestBlock = 7_412_011L,
      syncStatus = "Syncing",
      startTime = Now.minusSeconds(3 * 86_400L + 4 * 3_600L),
      nodeSettings = settings("sepolia", "SNAP", 50, mining = false),
      syncBaseline = Some(SyncBaseline(7_411_997L, Now.minusSeconds(144L))),
      glyphs = glyphs,
      clock = clock
    )

  /** Every fixture by name. */
  val all: Seq[(String, GlyphSet => TuiState)] = Seq(
    "synced" -> (g => syncedEtc(g)),
    "snap-storage" -> (g => snapStorageMordor(g)),
    "regular" -> (g => regularSyncEtc(g)),
    "sepolia-near-head" -> (g => sepoliaNearHead(g))
  )
