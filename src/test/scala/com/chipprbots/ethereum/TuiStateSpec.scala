package com.chipprbots.ethereum

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.console.*
import com.chipprbots.ethereum.testing.Tags.*

/** Tests for TuiState - state management for TUI data. */
class TuiStateSpec extends AnyFlatSpec with Matchers:

  "TuiState" should "have initial default values" taggedAs (UnitTest) in {
    val state = TuiState.initial
    state.networkName shouldBe "unknown"
    state.connectionStatus shouldBe "Initializing"
    state.peerCount shouldBe 0
    state.maxPeers shouldBe 0
    state.currentBlock shouldBe 0L
    state.bestBlock shouldBe 0L
    state.syncStatus shouldBe "Starting..."
  }

  it should "calculate sync progress correctly" taggedAs (UnitTest) in {
    val state = TuiState.initial
      .withBlockInfo(500, 1000)

    state.syncProgress shouldBe 50.0 +- 0.1
  }

  it should "return 0 sync progress when no blocks" taggedAs (UnitTest) in {
    val state = TuiState.initial
    state.syncProgress shouldBe 0.0
  }

  it should "calculate blocks remaining correctly" taggedAs (UnitTest) in {
    val state = TuiState.initial
      .withBlockInfo(750, 1000)

    state.blocksRemaining shouldBe 250L
  }

  it should "return 0 blocks remaining when synced" taggedAs (UnitTest) in {
    val state = TuiState.initial
      .withBlockInfo(1000, 1000)

    state.blocksRemaining shouldBe 0L
  }

  it should "detect synchronized state" taggedAs (UnitTest) in {
    val synced = TuiState.initial.withBlockInfo(1000, 1000)
    synced.isSynchronized shouldBe true

    val notSynced = TuiState.initial.withBlockInfo(500, 1000)
    notSynced.isSynchronized shouldBe false
  }

  it should "not report synchronized when best block is 0" taggedAs (UnitTest) in {
    val state = TuiState.initial.withBlockInfo(100, 0)
    state.isSynchronized shouldBe false
  }

  it should "update network name immutably" taggedAs (UnitTest) in {
    val original = TuiState.initial
    val updated = original.withNetworkName("ethereum-classic")

    original.networkName shouldBe "unknown"
    updated.networkName shouldBe "ethereum-classic"
  }

  it should "update connection status immutably" taggedAs (UnitTest) in {
    val original = TuiState.initial
    val updated = original.withConnectionStatus("Connected")

    original.connectionStatus shouldBe "Initializing"
    updated.connectionStatus shouldBe "Connected"
  }

  it should "update peer count immutably" taggedAs (UnitTest) in {
    val original = TuiState.initial
    val updated = original.withPeerCount(5, 25)

    original.peerCount shouldBe 0
    original.maxPeers shouldBe 0
    updated.peerCount shouldBe 5
    updated.maxPeers shouldBe 25
  }

  it should "update block info immutably" taggedAs (UnitTest) in {
    val original = TuiState.initial
    val updated = original.withBlockInfo(12345, 20000)

    original.currentBlock shouldBe 0
    original.bestBlock shouldBe 0
    updated.currentBlock shouldBe 12345
    updated.bestBlock shouldBe 20000
  }

  it should "update sync status immutably" taggedAs (UnitTest) in {
    val original = TuiState.initial
    val updated = original.withSyncStatus("Syncing headers")

    original.syncStatus shouldBe "Starting..."
    updated.syncStatus shouldBe "Syncing headers"
  }

  it should "update node settings immutably" taggedAs (UnitTest) in {
    val settings = NodeSettings(
      dataDir = "/data/fukuii",
      network = "etc",
      syncMode = "fast",
      pruningMode = "archive",
      maxPeers = 25,
      rpcEnabled = true,
      rpcPort = 8545,
      miningEnabled = false
    )
    val original = TuiState.initial
    val updated = original.withNodeSettings(settings)

    original.nodeSettings.network shouldBe ""
    updated.nodeSettings.network shouldBe "etc"
    updated.nodeSettings.dataDir shouldBe "/data/fukuii"
    updated.nodeSettings.rpcEnabled shouldBe true
    updated.nodeSettings.rpcPort shouldBe 8545
  }

  it should "support chained updates" taggedAs (UnitTest) in {
    val state = TuiState.initial
      .withNetworkName("mainnet")
      .withConnectionStatus("Connected")
      .withPeerCount(10, 25)
      .withBlockInfo(100000, 200000)
      .withSyncStatus("Syncing blocks")

    state.networkName shouldBe "mainnet"
    state.connectionStatus shouldBe "Connected"
    state.peerCount shouldBe 10
    state.maxPeers shouldBe 25
    state.currentBlock shouldBe 100000
    state.bestBlock shouldBe 200000
    state.syncStatus shouldBe "Syncing blocks"
  }

  it should "create state with network name" taggedAs (UnitTest) in {
    val state = TuiState.withNetwork("mordor")
    state.networkName shouldBe "mordor"
    state.connectionStatus shouldBe "Initializing"
  }

  // Spec 027 C-3, T039: no wall clock. Every duration is measured on the state's `now`, the time of the last snapshot.
  private val now = java.time.Instant.parse("2026-11-07T12:00:00Z")
  private val at = TuiState(startTime = now, now = now)

  it should "measure sync speed from the first observed block, not from genesis" taggedAs (UnitTest) in {
    // A node restarted at block 15M that imported 1,000 blocks in 100 s syncs at 10 blocks/s, not 150,000.
    val state = at
      .copy(syncBaseline = Some(SyncBaseline(15_000_000L, now.minusSeconds(100))))
      .withBlockInfo(15_001_000L, 15_002_000L)
    state.syncSpeedBlocksPerSec shouldBe Some(10.0)
    state.estimatedSyncTimeSeconds shouldBe Some(100L)
  }

  it should "record the first non-zero block as the speed baseline, at the state's now" taggedAs (UnitTest) in {
    val s0 = at.withBlockInfo(0, 100)
    s0.syncBaseline shouldBe None
    val s1 = s0.withBlockInfo(10, 100)
    s1.syncBaseline shouldBe Some(SyncBaseline(10L, now))
    s1.copy(now = now.plusSeconds(5)).withBlockInfo(20, 100).syncBaseline shouldBe Some(SyncBaseline(10L, now))
  }

  // C-3: the reset on a rewind that TuiState.scala:69-72 did with Instant.now() now uses the snapshot's time.
  it should "reset the baseline at the snapshot's time when the chain moves backwards" taggedAs (UnitTest) in {
    val s1 = at.withBlockInfo(10, 100)
    val later = now.plusSeconds(30)
    s1.copy(now = later).withBlockInfo(5, 100).syncBaseline shouldBe Some(SyncBaseline(5L, later))
  }

  it should "not estimate before the minimum observation window" taggedAs (UnitTest) in {
    val state = at.withBlockInfo(10, 100).copy(now = now.plusSeconds(10)).withBlockInfo(50, 100)
    state.syncSpeedBlocksPerSec shouldBe None
    state.estimatedSyncTimeSeconds shouldBe None
    val after = state.copy(now = now.plusSeconds(20))
    after.syncSpeedBlocksPerSec shouldBe Some(2.0) // 40 blocks in 20 s
    after.estimatedSyncTimeSeconds shouldBe Some(25L)
  }

  it should "measure uptime from its start to its now" taggedAs (UnitTest) in {
    at.copy(now = now.plusSeconds(3_725)).uptimeSeconds shouldBe 3_725L
    at.uptimeSeconds shouldBe 0L
    TuiState.initial.uptimeSeconds shouldBe 0L // no snapshot, no start: nothing to measure
  }

  it should "take its now from each snapshot" taggedAs (UnitTest) in {
    val polled = at.withStatus(TuiFixtures.snapshot(current = 10, best = 20, now = now.plusSeconds(61)))
    polled.now shouldBe now.plusSeconds(61)
    polled.uptimeSeconds shouldBe 61L
    polled.syncBaseline shouldBe Some(SyncBaseline(10L, now.plusSeconds(61)))
  }

  it should "start in the emoji glyph set" taggedAs (UnitTest) in {
    TuiState.initial.glyphs shouldBe GlyphSet.Emoji
  }

  it should "apply a node status snapshot" taggedAs (UnitTest) in {
    val state = TuiState.initial.withStatus(TuiFixtures.snapshot(current = 10, best = 20, peers = 4))
    (state.connectionStatus, state.peerCount, state.maxPeers) shouldBe (("Connected", 4, 50))
    (state.currentBlock, state.bestBlock, state.syncStatus) shouldBe ((10L, 20L, "Syncing"))
    state.snapSyncState shouldBe None
  }

  it should "start the frame counter at 0 with full motion and no splash or celebration" taggedAs (UnitTest) in {
    val s = TuiState.initial
    (s.tick, s.motion, s.splashUntilTick, s.celebrationStartedTick, s.celebrated) shouldBe
      ((0L, Motion.Full, None, None, false))
    s.lastAdvanceAt shouldBe None
  }

/** Tests for NodeSettings. */
class NodeSettingsSpec extends AnyFlatSpec with Matchers:

  "NodeSettings" should "have empty default values" taggedAs (UnitTest) in {
    val settings = NodeSettings()
    settings.dataDir shouldBe ""
    settings.network shouldBe ""
    settings.syncMode shouldBe ""
    settings.pruningMode shouldBe ""
    settings.maxPeers shouldBe 0
    settings.rpcEnabled shouldBe false
    settings.rpcPort shouldBe 0
    settings.miningEnabled shouldBe false
  }

  it should "support full configuration" taggedAs (UnitTest) in {
    val settings = NodeSettings(
      dataDir = "/var/lib/fukuii",
      network = "etc",
      syncMode = "full",
      pruningMode = "basic",
      maxPeers = 50,
      rpcEnabled = true,
      rpcPort = 8546,
      miningEnabled = true
    )
    settings.dataDir shouldBe "/var/lib/fukuii"
    settings.network shouldBe "etc"
    settings.syncMode shouldBe "full"
    settings.pruningMode shouldBe "basic"
    settings.maxPeers shouldBe 50
    settings.rpcEnabled shouldBe true
    settings.rpcPort shouldBe 8546
    settings.miningEnabled shouldBe true
  }
