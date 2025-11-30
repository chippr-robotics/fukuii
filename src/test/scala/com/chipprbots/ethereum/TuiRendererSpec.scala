package com.chipprbots.ethereum

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.console._
import com.chipprbots.ethereum.testing.Tags._

/** Tests for TuiRenderer - rendering logic for terminal output. */
class TuiRendererSpec extends AnyFlatSpec with Matchers {

  val defaultConfig: TuiConfig = TuiConfig.default
  val minimalConfig: TuiConfig = TuiConfig.minimal
  val renderer: TuiRenderer = TuiRenderer(defaultConfig)
  val minimalRenderer: TuiRenderer = TuiRenderer(minimalConfig)

  "TuiRenderer" should "render content with correct dimensions" taggedAs (UnitTest) in {
    val state = TuiState.initial
    val width = 80
    val height = 24

    val lines = renderer.render(state, width, height)

    // All lines should fill the height (minus 1 for the last line)
    lines.length should be >= height - 1
  }

  it should "render startup banner" taggedAs (UnitTest) in {
    val width = 80
    val bannerLines = renderer.renderStartupBanner(width)

    bannerLines should not be empty
    // Banner should contain Fukuii branding
    val bannerText = bannerLines.map(_.toString).mkString("\n")
    bannerText should include("FUKUII")
  }

  it should "include network information" taggedAs (UnitTest) in {
    val state = TuiState.initial.withNetworkName("ethereum-classic")
    val width = 100
    val height = 40

    val lines = renderer.render(state, width, height)
    val output = lines.map(_.toString).mkString("\n").toUpperCase

    output should include("NETWORK")
    output should include("ETHEREUM-CLASSIC")
  }

  it should "include peer status" taggedAs (UnitTest) in {
    val state = TuiState.initial.withPeerCount(5, 25)
    val width = 100
    val height = 40

    val lines = renderer.render(state, width, height)
    val output = lines.map(_.toString).mkString("\n")

    output should include("Peers")
    output should include("5")
    output should include("25")
  }

  it should "include block information" taggedAs (UnitTest) in {
    val state = TuiState.initial.withBlockInfo(12345, 100000)
    val width = 100
    val height = 40

    val lines = renderer.render(state, width, height)
    val output = lines.map(_.toString).mkString("\n")

    output should include("Current Block")
    output should include("Best Block")
  }

  it should "show synchronized status when synced" taggedAs (UnitTest) in {
    val state = TuiState.initial.withBlockInfo(100000, 100000)
    val width = 100
    val height = 40

    val lines = renderer.render(state, width, height)
    val output = lines.map(_.toString).mkString("\n")

    output should include("SYNCHRONIZED")
  }

  it should "show progress bar when syncing (with config enabled)" taggedAs (UnitTest) in {
    val state = TuiState.initial.withBlockInfo(50000, 100000)
    val config = TuiConfig(showProgressBar = true)
    val rendererWithProgress = TuiRenderer(config)
    val width = 100
    val height = 40

    val lines = rendererWithProgress.render(state, width, height)
    val output = lines.map(_.toString).mkString("\n")

    output should include("Sync Progress")
    output should include("Blocks Remaining")
  }

  it should "not show progress bar when disabled" taggedAs (UnitTest) in {
    val state = TuiState.initial.withBlockInfo(50000, 100000)
    val config = TuiConfig(showProgressBar = false)
    val rendererNoProgress = TuiRenderer(config)
    val width = 100
    val height = 40

    val lines = rendererNoProgress.render(state, width, height)
    val output = lines.map(_.toString).mkString("\n")

    output should not include "Sync Progress"
  }

  it should "include runtime section with uptime" taggedAs (UnitTest) in {
    val state = TuiState.initial
    val width = 100
    val height = 40

    val lines = renderer.render(state, width, height)
    val output = lines.map(_.toString).mkString("\n")

    output should include("RUNTIME")
    output should include("Uptime")
  }

  it should "include footer with keyboard commands" taggedAs (UnitTest) in {
    val state = TuiState.initial
    val width = 100
    val height = 40

    val lines = renderer.render(state, width, height)
    val output = lines.map(_.toString).mkString("\n")

    output should include("Commands")
    output should include("Q")
    output should include("R")
    output should include("D")
  }

  it should "show node settings when enabled and available" taggedAs (UnitTest) in {
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
    val state = TuiState.initial.withNodeSettings(settings)
    val config = TuiConfig(showNodeSettings = true)
    val rendererWithSettings = TuiRenderer(config)
    val width = 100
    val height = 50

    val lines = rendererWithSettings.render(state, width, height)
    val output = lines.map(_.toString).mkString("\n")

    output should include("NODE SETTINGS")
    output should include("Data Dir")
    output should include("Sync Mode")
    output should include("Pruning")
  }

  it should "not show node settings when disabled" taggedAs (UnitTest) in {
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
    val state = TuiState.initial.withNodeSettings(settings)
    val config = TuiConfig(showNodeSettings = false)
    val rendererNoSettings = TuiRenderer(config)
    val width = 100
    val height = 50

    val lines = rendererNoSettings.render(state, width, height)
    val output = lines.map(_.toString).mkString("\n")

    output should not include "NODE SETTINGS"
  }

  it should "create renderer with default config" taggedAs (UnitTest) in {
    val defaultRenderer = TuiRenderer.default
    defaultRenderer should not be null
  }

  it should "handle small terminal dimensions gracefully" taggedAs (UnitTest) in {
    val state = TuiState.initial
    val width = 40
    val height = 10

    // Should not throw
    val lines = renderer.render(state, width, height)
    lines should not be empty
  }

  it should "handle large terminal dimensions" taggedAs (UnitTest) in {
    val state = TuiState.initial
    val width = 200
    val height = 60

    // Should not throw
    val lines = renderer.render(state, width, height)
    lines should not be empty
    lines.length should be >= height - 1
  }
}
