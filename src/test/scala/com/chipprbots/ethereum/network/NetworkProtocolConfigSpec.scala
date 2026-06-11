package com.chipprbots.ethereum.network

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.ParallelTestExecution

import com.typesafe.config.{ConfigFactory, ConfigValueFactory}

import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.testing.Tags._
import com.chipprbots.ethereum.utils.{Config, InstanceConfig}

// scalastyle:off magic.number
/** Tests for NetworkProtocolConfig case class and config-driven InstanceConfig.supportedCapabilities.
  *
  * Coverage:
  *   - Default values match the conservative spec table
  *   - All 6 fields parse correctly from HOCON
  *   - InstanceConfig.supportedCapabilities reflects config values
  *   - ETC global defaults yield [ETH68, ETH69, SNAP1] only
  *   - Startup validation does not abort on misconfigured combinations
  */
class NetworkProtocolConfigSpec extends AnyFlatSpec with Matchers with ParallelTestExecution {

  // ── NetworkProtocolConfig.default ─────────────────────────────────────────

  "NetworkProtocolConfig.default" should "have eth68=true (universal baseline)" taggedAs UnitTest in {
    NetworkProtocolConfig.default.eth68 shouldBe true
  }

  it should "have eth69=true (EIP-7642, all modern clients)" taggedAs UnitTest in {
    NetworkProtocolConfig.default.eth69 shouldBe true
  }

  it should "have eth70=false (opt-in; absent from ETC peer set)" taggedAs UnitTest in {
    NetworkProtocolConfig.default.eth70 shouldBe false
  }

  it should "have eth71=false (opt-in; Besu 26.6.0 only, geth not yet released)" taggedAs UnitTest in {
    NetworkProtocolConfig.default.eth71 shouldBe false
  }

  it should "have snap1=true (universal SNAP baseline)" taggedAs UnitTest in {
    NetworkProtocolConfig.default.snap1 shouldBe true
  }

  it should "have snap2=false (geth: not safe to advertise unconditionally yet)" taggedAs UnitTest in {
    NetworkProtocolConfig.default.snap2 shouldBe false
  }

  // ── NetworkProtocolConfig.fromConfig ──────────────────────────────────────

  "NetworkProtocolConfig.fromConfig" should "parse all six fields from HOCON" taggedAs UnitTest in {
    val c = ConfigFactory.parseString("""
      { eth68=true, eth69=true, eth70=true, eth71=false, snap1=true, snap2=true }
    """)
    val p = NetworkProtocolConfig.fromConfig(c)
    p.eth68 shouldBe true
    p.eth69 shouldBe true
    p.eth70 shouldBe true
    p.eth71 shouldBe false
    p.snap1 shouldBe true
    p.snap2 shouldBe true
  }

  it should "parse all fields as false when every flag is disabled" taggedAs UnitTest in {
    val c = ConfigFactory.parseString("""
      { eth68=false, eth69=false, eth70=false, eth71=false, snap1=false, snap2=false }
    """)
    val p = NetworkProtocolConfig.fromConfig(c)
    p.eth68 shouldBe false
    p.eth69 shouldBe false
    p.eth70 shouldBe false
    p.eth71 shouldBe false
    p.snap1 shouldBe false
    p.snap2 shouldBe false
  }

  it should "parse the conservative ETC global defaults from the loaded config" taggedAs UnitTest in {
    val p = Config.networkProtocols
    p.eth68 shouldBe true
    p.eth69 shouldBe true
    p.eth70 shouldBe false // absent from ETC peer set
    p.eth71 shouldBe false
    p.snap1 shouldBe true
    p.snap2 shouldBe false
  }

  // ── InstanceConfig.supportedCapabilities — ETC defaults ───────────────────

  "InstanceConfig.supportedCapabilities (ETC global defaults)" should
    "contain ETH68, ETH69, SNAP1" taggedAs UnitTest in {
      Config.supportedCapabilities should contain(Capability.ETH68)
      Config.supportedCapabilities should contain(Capability.ETH69)
      Config.supportedCapabilities should contain(Capability.SNAP1)
    }

  it should "have exactly three entries — no undeclared capabilities" taggedAs UnitTest in {
    (Config.supportedCapabilities should have).length(3)
  }

  // ── InstanceConfig.supportedCapabilities — flag toggling ──────────────────

  "InstanceConfig.supportedCapabilities" should
    "exclude ETH68 when eth68=false" taggedAs UnitTest in {
      val ic = instanceWithOverride("network.protocols.eth68", false)
      ic.supportedCapabilities should not contain Capability.ETH68
      ic.supportedCapabilities should contain(Capability.ETH69)
      ic.supportedCapabilities should contain(Capability.SNAP1)
    }

  it should "exclude ETH69 when eth69=false" taggedAs UnitTest in {
    val ic = instanceWithOverride("network.protocols.eth69", false)
    ic.supportedCapabilities should not contain Capability.ETH69
    ic.supportedCapabilities should contain(Capability.ETH68)
    ic.supportedCapabilities should contain(Capability.SNAP1)
  }

  it should "exclude SNAP1 when snap1=false" taggedAs UnitTest in {
    val ic = instanceWithOverride("network.protocols.snap1", false)
    ic.supportedCapabilities should not contain Capability.SNAP1
    ic.supportedCapabilities should contain(Capability.ETH68)
    ic.supportedCapabilities should contain(Capability.ETH69)
  }

  it should "be empty when all flags are false" taggedAs UnitTest in {
    val base = ConfigFactory.load().getConfig("fukuii")
    val allOff = base
      .withValue("network.protocols.eth68", ConfigValueFactory.fromAnyRef(false))
      .withValue("network.protocols.eth69", ConfigValueFactory.fromAnyRef(false))
      .withValue("network.protocols.snap1", ConfigValueFactory.fromAnyRef(false))
    val ic = new InstanceConfig(allOff, "test-all-off")
    ic.supportedCapabilities shouldBe empty
  }

  // ── Startup validation — no abort on misconfigured combinations ────────────

  "InstanceConfig startup validation" should
    "not throw when eth70=true and eth69=false" taggedAs UnitTest in {
      val base = ConfigFactory.load().getConfig("fukuii")
      val cfg = base
        .withValue("network.protocols.eth70", ConfigValueFactory.fromAnyRef(true))
        .withValue("network.protocols.eth69", ConfigValueFactory.fromAnyRef(false))
      noException should be thrownBy new InstanceConfig(cfg, "test-warn-eth70")
    }

  it should "not throw when eth71=true and eth70=false" taggedAs UnitTest in {
    val base = ConfigFactory.load().getConfig("fukuii")
    val cfg = base
      .withValue("network.protocols.eth71", ConfigValueFactory.fromAnyRef(true))
      .withValue("network.protocols.eth70", ConfigValueFactory.fromAnyRef(false))
    noException should be thrownBy new InstanceConfig(cfg, "test-warn-eth71")
  }

  // ── Helper ────────────────────────────────────────────────────────────────

  private def instanceWithOverride(path: String, value: Boolean): InstanceConfig =
    new InstanceConfig(
      ConfigFactory.load().getConfig("fukuii").withValue(path, ConfigValueFactory.fromAnyRef(value)),
      "test-override"
    )
}
