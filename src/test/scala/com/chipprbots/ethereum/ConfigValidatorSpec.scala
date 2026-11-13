package com.chipprbots.ethereum

import scala.jdk.CollectionConverters.*

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger as LogbackLogger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.typesafe.config.ConfigFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.slf4j.LoggerFactory

import com.chipprbots.ethereum.testing.Tags.*

// scalastyle:off magic.number
class ConfigValidatorSpec extends AnyFlatSpec with Matchers:

  /** Minimal valid config with no port conflicts. */
  private val baseConfig: String =
    """
      |sync.do-snap-sync = false
      |network.server-address.port = 30303
      |network.rpc.http.enabled = false
      |network.rpc.http.port = 8546
      |network.rpc.ws.enabled = false
      |network.rpc.ws.port = 8552
      |network.engine-api.enabled = false
      |network.engine-api.port = 8551
      |""".stripMargin

  private def cfg(overrides: String) =
    ConfigFactory.parseString(overrides).withFallback(ConfigFactory.parseString(baseConfig))

  /** Runs `f` and returns its result with the WARN messages ConfigValidator logged meanwhile. The test logback config
    * silences everything below ERROR, so the validator's logger is opened to WARN for the duration.
    */
  private def withLoggedWarnings[A](f: => A): (A, List[String]) =
    val logger = LoggerFactory.getLogger(ConfigValidator.getClass).asInstanceOf[LogbackLogger]
    val appender = new ListAppender[ILoggingEvent]
    val previousLevel = logger.getLevel
    appender.start()
    logger.addAppender(appender)
    logger.setLevel(Level.WARN)
    val result =
      try f
      finally
        logger.setLevel(previousLevel)
        logger.detachAppender(appender)
    (result, appender.list.asScala.toList.filter(_.getLevel == Level.WARN).map(_.getFormattedMessage))

  "ConfigValidator" should "pass with valid default config" taggedAs UnitTest in {
    ConfigValidator.validate(cfg("")) shouldBe empty
  }

  it should "pass with SNAP sync on and no fast-sync setting" taggedAs UnitTest in {
    ConfigValidator.validate(cfg("sync.do-snap-sync = true")) shouldBe empty
  }

  it should "warn, not fail, when a config still sets do-fast-sync" taggedAs UnitTest in {
    for value <- Seq("true", "false") do
      val config = cfg(s"sync.do-fast-sync = $value")

      val warnings = ConfigValidator.removedKeyWarnings(config)
      warnings should have size 1
      warnings.head should include("fukuii.sync.do-fast-sync is ignored")
      warnings.head should include("fukuii.sync.do-snap-sync")

      ConfigValidator.validate(config) shouldBe empty
  }

  it should "tell an operator who set do-fast-sync = false how to keep syncing from genesis" taggedAs UnitTest in {
    val snapOn = ConfigValidator.removedKeyWarnings(cfg("sync.do-fast-sync = false\nsync.do-snap-sync = true"))
    snapOn.head should include("will use SNAP sync")
    snapOn.head should include("set fukuii.sync.do-snap-sync = false")

    val snapOff = ConfigValidator.removedKeyWarnings(cfg("sync.do-fast-sync = false\nsync.do-snap-sync = false"))
    snapOff.head should include("imports every block")
    (snapOff.head should not).include("will use SNAP sync")
  }

  it should "log the do-fast-sync warning when it validates the config at startup" taggedAs UnitTest in {
    val (errors, logged) = withLoggedWarnings(ConfigValidator.validate(cfg("sync.do-fast-sync = true")))

    errors shouldBe empty
    logged should have size 1
    logged.head should include("fukuii.sync.do-fast-sync is ignored")
  }

  it should "warn about every other fast-sync key a config still sets" taggedAs UnitTest in {
    val setsAll = ConfigValidator.RemovedFastSyncKeys.map(key => s"sync.$key = 1").mkString("\n")

    val warnings = ConfigValidator.removedKeyWarnings(cfg(setsAll))

    warnings should have size ConfigValidator.RemovedFastSyncKeys.size
    ConfigValidator.RemovedFastSyncKeys.filterNot(key =>
      warnings.exists(_.contains(s"fukuii.sync.$key "))
    ) shouldBe empty
  }

  it should "not mistake SNAP's pivot-block-offset for fast sync's" taggedAs UnitTest in {
    ConfigValidator.removedKeyWarnings(cfg("sync.snap-sync.pivot-block-offset = 64")) shouldBe empty
    ConfigValidator.removedKeyWarnings(cfg("sync.pivot-block-offset = 32")) should have size 1
  }

  it should "not warn about a config that sets no removed key" taggedAs UnitTest in {
    val (errors, logged) = withLoggedWarnings(ConfigValidator.validate(cfg("sync.do-snap-sync = true")))

    errors shouldBe empty
    logged shouldBe empty
  }

  it should "report error when HTTP port equals P2P port" taggedAs UnitTest in {
    val errors = ConfigValidator.validate(
      cfg("network.rpc.http.enabled = true\nnetwork.rpc.http.port = 30303")
    )
    errors should have size 1
    errors.head should include("30303")
    errors.head should include("multiple times")
  }

  it should "report error when WS port equals P2P port" taggedAs UnitTest in {
    val errors = ConfigValidator.validate(
      cfg("network.rpc.ws.enabled = true\nnetwork.rpc.ws.port = 30303")
    )
    errors should have size 1
    errors.head should include("30303")
    errors.head should include("multiple times")
  }

  it should "report error when HTTP and WS ports are equal and both enabled" taggedAs UnitTest in {
    val errors = ConfigValidator.validate(
      cfg(
        "network.rpc.http.enabled = true\nnetwork.rpc.http.port = 9999\n" +
          "network.rpc.ws.enabled = true\nnetwork.rpc.ws.port = 9999"
      )
    )
    errors.exists(_.contains("9999")) shouldBe true
    errors.exists(_.contains("multiple times")) shouldBe true
  }

  it should "not report conflict when HTTP port equals P2P port but HTTP is disabled" taggedAs UnitTest in {
    val errors = ConfigValidator.validate(
      cfg("network.rpc.http.enabled = false\nnetwork.rpc.http.port = 30303")
    )
    errors shouldBe empty
  }

  it should "not report conflict when WS port equals P2P port but WS is disabled" taggedAs UnitTest in {
    val errors = ConfigValidator.validate(
      cfg("network.rpc.ws.enabled = false\nnetwork.rpc.ws.port = 30303")
    )
    errors shouldBe empty
  }

  it should "report error when Engine API port equals P2P port" taggedAs UnitTest in {
    val errors = ConfigValidator.validate(
      cfg("network.engine-api.enabled = true\nnetwork.engine-api.port = 30303")
    )
    errors should have size 1
    errors.head should include("30303")
  }

  it should "accumulate multiple errors" taggedAs UnitTest in {
    val errors = ConfigValidator.validate(
      cfg(
        "network.rpc.http.enabled = true\nnetwork.rpc.http.port = 30303\n" +
          "network.rpc.ws.enabled = true\nnetwork.rpc.ws.port = 30303"
      )
    )
    errors.length should be >= 2
  }
// scalastyle:on magic.number

  // ── The in-development guard of the unified state layout (spec 018 Track C) ──────────────────────────────────

  // With the guard on a SNAP sync has nowhere to hand its state off to before C5a, and SNAP is not advertised until
  // the node follows the head: a capability is mutual, so a SNAP sync would wait for a snap-capable peer for ever. A
  // node that would start and never sync does not start.
  it should "refuse state.path-live with SNAP sync on, and name both keys" taggedAs UnitTest in {
    val errors = ConfigValidator.validate(cfg("state.path-live = true\nsync.do-snap-sync = true"))
    errors should have size 1
    errors.head should (include("fukuii.state.path-live").and(include("fukuii.sync.do-snap-sync = false")))
    errors.head should include("C5a")
  }

  // A block is built over a view of its parent's state, which the builders do not take before the proposer view.
  it should "refuse state.path-live with mining on, and name both keys" taggedAs UnitTest in {
    val errors = ConfigValidator.validate(cfg("state.path-live = true\nmining.mining-enabled = true"))
    errors should have size 1
    errors.head should (include("fukuii.state.path-live").and(include("fukuii.mining.mining-enabled = false")))
    // each thing the guard cannot do yet is said
    ConfigValidator.stateGuardErrors(
      cfg("state.path-live = true\nmining.mining-enabled = true\nsync.do-snap-sync = true")
    ) should have size 2
    ConfigValidator.stateGuardErrors(cfg("state.path-live = false\nmining.mining-enabled = true")) shouldBe empty
    ConfigValidator.stateGuardErrors(cfg("state.path-live = true\nmining.mining-enabled = false")) shouldBe empty
  }

  // Both are taken on the unified layout since C3c, in its linear subset: the chain file block by block onto the
  // head, and test mode with a chain set on a datadir whose state the chain before it no longer has.
  it should "take state.path-live with a chain file imported at start, and with test mode" taggedAs UnitTest in {
    ConfigValidator.stateGuardErrors(
      cfg("state.path-live = true\nimport-chain-file = \"/data/chain.rlp\"")
    ) shouldBe empty
    ConfigValidator.stateGuardErrors(cfg("state.path-live = true\nimport-chain-file = \"\"")) shouldBe empty
    ConfigValidator.stateGuardErrors(
      cfg("state.path-live = false\nimport-chain-file = \"/data/chain.rlp\"")
    ) shouldBe empty

    ConfigValidator.stateGuardErrors(cfg("state.path-live = true\ntestmode = true")) shouldBe empty
    ConfigValidator.stateGuardErrors(cfg("state.path-live = false\ntestmode = true")) shouldBe empty
    // what the guard still refuses is refused in test mode too
    ConfigValidator.stateGuardErrors(
      cfg("state.path-live = true\ntestmode = true\nmining.mining-enabled = true")
    ) should have size 1
  }

  it should "accept the guard with SNAP sync off, and SNAP sync with the guard off or not set" taggedAs UnitTest in {
    ConfigValidator.validate(cfg("state.path-live = true\nsync.do-snap-sync = false")) shouldBe empty
    ConfigValidator.validate(cfg("state.path-live = false\nsync.do-snap-sync = true")) shouldBe empty
    ConfigValidator.validate(cfg("sync.do-snap-sync = true")) shouldBe empty
    // the configuration the node ships with
    ConfigValidator.stateGuardErrors(ConfigFactory.load().getConfig("fukuii")) shouldBe empty
  }

  it should "refuse it for an instance of a multi-instance run too" taggedAs UnitTest in {
    val instance = cfg("state.path-live = true\nsync.do-snap-sync = true")
    val root = ConfigFactory.empty().withValue("fukuii-runtime.instances.guarded.fukuii", instance.root())
    val refused = the[IllegalArgumentException] thrownBy com.chipprbots.ethereum.runtime.FukuiiRuntime.fromConfig(root)
    refused.getMessage should (include("Instance guarded").and(include("fukuii.state.path-live")))
  }

  "the Ephemery refresh-timeout" should "be accepted from 10 s to 300 s, and refused outside" taggedAs UnitTest in {
    List("10s", "60s", "300s", "5 minutes").foreach { t =>
      ConfigValidator.validate(cfg(s"ephemery.refresh-timeout = $t")) shouldBe empty
    }
    List("9s", "1s", "301s", "1 hour").foreach { t =>
      val errors = ConfigValidator.validate(cfg(s"ephemery.refresh-timeout = $t"))
      errors should have size 1
      errors.head should include("fukuii.ephemery.refresh-timeout")
      errors.head should include("between 10 seconds and 300 seconds")
    }
    // the shipped default is inside the bounds
    ConfigValidator.ephemeryErrors(
      ConfigFactory.load(ConfigFactory.parseResources("conf/ephemery.conf")).getConfig("fukuii")
    ) shouldBe empty
  }
