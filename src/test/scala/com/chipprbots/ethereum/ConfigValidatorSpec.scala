package com.chipprbots.ethereum

import com.typesafe.config.ConfigFactory

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags._

// scalastyle:off magic.number
class ConfigValidatorSpec extends AnyFlatSpec with Matchers {

  /** Minimal valid config with no port conflicts and consistent sync settings. */
  private val baseConfig: String =
    """
      |sync.do-fast-sync = true
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

  "ConfigValidator" should "pass with valid default config" taggedAs UnitTest in {
    ConfigValidator.validate(cfg("")) shouldBe empty
  }

  it should "report error when SNAP sync enabled without fast sync" taggedAs UnitTest in {
    val errors = ConfigValidator.validate(cfg("sync.do-snap-sync = true\nsync.do-fast-sync = false"))
    errors should have size 1
    errors.head should include("SNAP sync")
    errors.head should include("do-fast-sync")
  }

  it should "pass when SNAP sync enabled with fast sync" taggedAs UnitTest in {
    val errors = ConfigValidator.validate(cfg("sync.do-snap-sync = true\nsync.do-fast-sync = true"))
    errors shouldBe empty
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
        "sync.do-snap-sync = true\nsync.do-fast-sync = false\n" +
          "network.rpc.http.enabled = true\nnetwork.rpc.http.port = 30303"
      )
    )
    errors.length should be >= 2
  }
}
// scalastyle:on magic.number
