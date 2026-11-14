package com.chipprbots.ethereum.console

import java.time.Duration

import com.typesafe.config.ConfigException
import com.typesafe.config.ConfigFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*

/** The alert thresholds (spec 029 FR-046): every key of `fukuii.tui.alerts`, spec 027's values when a key is left out,
  * and an invalid value or an unknown key a start-up error that names the key.
  */
class AlertThresholdsSpec extends AnyFlatSpec with Matchers:

  private def parsed(alerts: String): AlertThresholds =
    AlertThresholds.fromConfig(ConfigFactory.parseString(s"alerts { $alerts }"))

  private def refused(alerts: String): String =
    intercept[ConfigException.BadValue](parsed(alerts)).getMessage

  "The defaults" should "be spec 027's thresholds, and spec 029's for the four new ones" taggedAs (UnitTest) in {
    val d = AlertThresholds.Default
    d.zeroPeersAfter shouldBe Duration.ofSeconds(30)
    d.snapSampleMaxAge shouldBe Duration.ofSeconds(90)
    (d.diskWarnBytes, d.diskErrorBytes) shouldBe ((20L * 1024 * 1024 * 1024, 5L * 1024 * 1024 * 1024))
    (d.clSilentAfter, d.clFirstCallGrace) shouldBe ((ClSample.ClSilentAfter, ClSample.ClFirstCallGrace))
    d.newPayloadSilentAfter shouldBe Duration.ofMinutes(5)
    d.hashrateZeroAfter shouldBe Duration.ofMinutes(10)
    d.stallAfter shouldBe TuiState.StallAfter
    d.reorgDepth shouldBe 3
    d.lowPeersFraction shouldBe 0.25
    (d.heapWarnFraction, d.gcShareWarn, d.rpcErrorRateWarn, d.writeStall) shouldBe ((0.90, 0.20, 0.10, true))
  }

  "A block with no alerts" should "be the defaults, and so should an empty one" taggedAs (UnitTest) in {
    AlertThresholds.fromConfig(ConfigFactory.empty()) shouldBe AlertThresholds.Default
    parsed("") shouldBe AlertThresholds.Default
  }

  "The shipped tui.conf" should "set every key, each to its default" taggedAs (UnitTest) in {
    val shipped = ConfigFactory.parseResources("conf/base/tui.conf").getConfig("tui")
    import scala.jdk.CollectionConverters.*
    shipped.getConfig("alerts").root().keySet().asScala.toSeq.sorted shouldBe AlertThresholds.Keys.sorted
    AlertThresholds.fromConfig(shipped) shouldBe AlertThresholds.Default
  }

  "Every key" should "be read, in the forms an operator writes" taggedAs (UnitTest) in {
    val all = parsed("""
      zero-peers-after = 45s
      low-peers-fraction = 0.5
      stall-after = 3m
      snap-sample-max-age = 2m
      disk-warn = 50 GiB
      disk-error = 10G
      cl-silent-after = 36s
      cl-first-call-grace = 5m
      new-payload-silent-after = 90s
      hashrate-zero-after = 1h
      reorg-depth = 7
      heap-warn-fraction = 0.8
      gc-share-warn = 0.05
      rpc-error-rate-warn = 1
      write-stall = false
    """)
    all shouldBe AlertThresholds(
      zeroPeersAfter = Duration.ofSeconds(45),
      lowPeersFraction = 0.5,
      stallAfter = Duration.ofMinutes(3),
      snapSampleMaxAge = Duration.ofMinutes(2),
      diskWarnBytes = 50L * 1024 * 1024 * 1024,
      diskErrorBytes = 10L * 1024 * 1024 * 1024,
      clSilentAfter = Duration.ofSeconds(36),
      clFirstCallGrace = Duration.ofMinutes(5),
      newPayloadSilentAfter = Duration.ofSeconds(90),
      hashrateZeroAfter = Duration.ofHours(1),
      reorgDepth = 7,
      heapWarnFraction = 0.8,
      gcShareWarn = 0.05,
      rpcErrorRateWarn = 1.0,
      writeStall = false
    )
    // One key set leaves the others at their defaults.
    parsed("disk-warn = 50 GiB") shouldBe AlertThresholds.Default.copy(diskWarnBytes = 50L * 1024 * 1024 * 1024)
  }

  "An invalid value" should "be an error that names its key" taggedAs (UnitTest) in {
    val cases = Seq(
      "zero-peers-after = 0s" -> "zero-peers-after",
      "stall-after = -5s" -> "stall-after",
      "cl-silent-after = soon" -> "cl-silent-after",
      "low-peers-fraction = 0" -> "low-peers-fraction",
      "low-peers-fraction = 1.5" -> "low-peers-fraction",
      "heap-warn-fraction = ninety" -> "heap-warn-fraction",
      "gc-share-warn = -0.1" -> "gc-share-warn",
      "rpc-error-rate-warn = 2" -> "rpc-error-rate-warn",
      "disk-warn = 0" -> "disk-warn",
      "disk-error = lots" -> "disk-error",
      "reorg-depth = 0" -> "reorg-depth",
      "reorg-depth = deep" -> "reorg-depth",
      "write-stall = maybe" -> "write-stall"
    )
    for (alerts, key) <- cases do withClue(s"$alerts: ")(refused(alerts) should include(s"fukuii.tui.alerts.$key"))
  }

  it should "say the key and the reason, without the configuration's origin in front" taggedAs (UnitTest) in {
    val message = refused("disk-warn = lots")
    message should startWith("Invalid value at 'fukuii.tui.alerts.disk-warn': ")
    message should include("'lots'")
    (message should not).include("String:")
    // The path is said once.
    message.split("alerts\\.disk-warn", -1).length shouldBe 2
  }

  "An error that would begin above its warning" should "be refused: it would never be preceded by one" taggedAs (
    UnitTest
  ) in {
    refused("disk-warn = 5 GiB\ndisk-error = 20 GiB") should include("fukuii.tui.alerts.disk-error")
    // The error set alone, above the default warning.
    refused("disk-error = 40 GiB") should include("disk-warn")
    parsed("disk-warn = 10 GiB\ndisk-error = 10 GiB").diskErrorBytes shouldBe 10L * 1024 * 1024 * 1024
  }

  "A key set to null" should "be an error that names it: it is not a key left out" taggedAs (UnitTest) in {
    refused("disk-warn = null") should include("fukuii.tui.alerts.disk-warn")
    refused("write-stall = null") should include("fukuii.tui.alerts.write-stall")
  }

  "An `alerts` that is no block" should "be an error that names it and lists the thresholds" taggedAs (UnitTest) in {
    val message =
      intercept[ConfigException.BadValue](
        AlertThresholds.fromConfig(ConfigFactory.parseString("alerts = on"))
      ).getMessage
    message should include("fukuii.tui.alerts")
    message should include("zero-peers-after")
  }

  "A key that is no threshold" should "be an error that names it and lists the thresholds" taggedAs (UnitTest) in {
    val message = refused("disk-warning = 50 GiB")
    message should include("fukuii.tui.alerts.disk-warning")
    message should include("disk-warn")
    message should include("write-stall")
  }

  "The TUI's configuration" should "carry the thresholds from `fukuii.tui.alerts`, and refuse a bad one at load" taggedAs (
    UnitTest
  ) in {
    val fukuii = ConfigFactory.parseString("tui { alerts { disk-warn = 50 GiB, reorg-depth = 5 } }")
    val config = TuiConfig.load(fukuii, TuiEnv.from(Map.empty, Map.empty))
    config.alerts.diskWarnBytes shouldBe 50L * 1024 * 1024 * 1024
    config.alerts.reorgDepth shouldBe 5
    config.alerts.diskErrorBytes shouldBe AlertThresholds.Default.diskErrorBytes
    TuiConfig.load(ConfigFactory.empty(), TuiEnv.from(Map.empty, Map.empty)).alerts shouldBe AlertThresholds.Default
    intercept[ConfigException.BadValue](
      TuiConfig.load(ConfigFactory.parseString("tui.alerts.stall-after = never"), TuiEnv.from(Map.empty, Map.empty))
    ).getMessage should include("fukuii.tui.alerts.stall-after")
  }

  // What a threshold changes: the alert row at a value between the default and the configured one.
  "A configured threshold" should "move where its alert begins, and leave the others where they were" taggedAs (
    UnitTest
  ) in {
    val GiB = 1024L * 1024 * 1024
    val s = TuiFixtures.withLocal(TuiFixtures.syncedEtc(), _.copy(diskFree = Some(30 * GiB)))
    Alerts.of(s).map(_.text).exists(_.startsWith("disk")) shouldBe false
    val wary = s.copy(thresholds = AlertThresholds.Default.copy(diskWarnBytes = 50 * GiB))
    Alerts.of(wary).map(_.text) should contain("disk 30.0G free (warn < 50G)")
    // The reorg depth, which slice 3 made configurable, still is.
    // A reorg two blocks deep, a minute ago: under the default depth of three, at a depth of two.
    val observed = TuiFixtures.observedSepolia()
    val reorged = TuiFixtures.withLocal(
      observed,
      l =>
        l.copy(chain = l.chain.map(c => c.copy(reorgs = c.reorgs.map(_.copy(at = TuiFixtures.Now.minusSeconds(60))))))
    )
    Alerts.of(reorged).exists(_.text.startsWith("reorg")) shouldBe false
    Alerts
      .of(reorged.copy(thresholds = AlertThresholds.Default.copy(reorgDepth = 2)))
      .exists(_.text.startsWith("reorg")) shouldBe true
    // A stall after a shorter time.
    val slow = TuiFixtures.regularSyncEtc().copy(lastAdvanceAt = Some(TuiFixtures.Now.minusSeconds(40)))
    slow.stalled shouldBe false
    slow.copy(thresholds = AlertThresholds.Default.copy(stallAfter = Duration.ofSeconds(30))).stalled shouldBe true
  }
