package com.chipprbots.ethereum.network

import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigValueFactory

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.InstanceConfig

/** Tests for `network.server-address.external-ip-detection` config parsing into [[DetectionMode]]. */
class ExternalIpDetectionModeConfigSpec extends AnyFlatSpec with Matchers:

  private def instanceWithMode(value: String): InstanceConfig =
    new InstanceConfig(
      ConfigFactory
        .load()
        .getConfig("fukuii")
        .withValue("network.server-address.external-ip-detection", ConfigValueFactory.fromAnyRef(value)),
      "test-detection-mode"
    )

  "DetectionMode.fromString" should "parse 'none'" taggedAs (UnitTest, NetworkTest) in {
    DetectionMode.fromString("none") shouldBe DetectionMode.None
  }

  it should "parse 'upnp'" taggedAs (UnitTest, NetworkTest) in {
    DetectionMode.fromString("upnp") shouldBe DetectionMode.Upnp
  }

  it should "parse 'full'" taggedAs (UnitTest, NetworkTest) in {
    DetectionMode.fromString("full") shouldBe DetectionMode.Full
  }

  it should "be case-insensitive" taggedAs (UnitTest, NetworkTest) in {
    DetectionMode.fromString("FULL") shouldBe DetectionMode.Full
  }

  it should "reject an invalid value with a message naming the allowed values" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    val ex = intercept[IllegalArgumentException] {
      DetectionMode.fromString("bogus")
    }
    ex.getMessage should include("external-ip-detection")
    ex.getMessage should include("none, upnp, full")
  }

  "InstanceConfig.Server.externalIpDetectionMode" should "default to Upnp from the base config" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    val ic = new InstanceConfig(ConfigFactory.load().getConfig("fukuii"), "test-default")
    ic.Network.Server.externalIpDetectionMode shouldBe DetectionMode.Upnp
  }

  it should "reflect 'none' when configured" taggedAs (UnitTest, NetworkTest) in {
    instanceWithMode("none").Network.Server.externalIpDetectionMode shouldBe DetectionMode.None
  }

  it should "reflect 'full' when configured" taggedAs (UnitTest, NetworkTest) in {
    instanceWithMode("full").Network.Server.externalIpDetectionMode shouldBe DetectionMode.Full
  }

  it should "fail config loading with a clear message on an invalid value" taggedAs (UnitTest, NetworkTest) in {
    val ex = intercept[IllegalArgumentException] {
      instanceWithMode("bogus").Network.Server.externalIpDetectionMode
    }
    ex.getMessage should include("external-ip-detection")
  }
