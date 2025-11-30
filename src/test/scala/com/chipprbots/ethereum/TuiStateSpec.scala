package com.chipprbots.ethereum

import java.time.Instant

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.console._
import com.chipprbots.ethereum.testing.Tags._

/** Tests for TuiState - state management for TUI data. */
class TuiStateSpec extends AnyFlatSpec with Matchers {

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
}

/** Tests for NodeSettings. */
class NodeSettingsSpec extends AnyFlatSpec with Matchers {

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
}
