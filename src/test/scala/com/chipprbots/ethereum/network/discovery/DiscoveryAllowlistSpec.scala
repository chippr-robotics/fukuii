package com.chipprbots.ethereum.network.discovery

import scala.jdk.CollectionConverters.*

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger as LogbackLogger
import com.typesafe.config.ConfigFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.slf4j.LoggerFactory

import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.Config

/** Spec 030 FR-055: with the node allowlist on, discv4, discv5 and the DNS tree walk do not run and no UDP port is
  * bound, whatever `discovery-enabled` says; if it says true, one warning says why. [[DiscoveryConfig]] is what the
  * discovery manager, the discovery service builder and port forwarding read, so it is where the override lives.
  */
class DiscoveryAllowlistSpec extends AnyFlatSpec with Matchers:

  private val dnsDomains = Seq(
    s"enrtree://${DnsTrustAnchors.EtcKey}@all.one.invalid",
    s"enrtree://${DnsTrustAnchors.EthKey}@all.two.invalid"
  )

  private def withDiscovery(enabled: Boolean) =
    ConfigFactory.parseString(s"network.discovery.discovery-enabled = $enabled").withFallback(Config.config)

  private def load(allowlistEnabled: Boolean, discoveryEnabled: Boolean = true): DiscoveryConfig =
    DiscoveryConfig(
      withDiscovery(discoveryEnabled),
      bootstrapNodes = Set.empty,
      dnsDiscoveryDomains = dnsDomains,
      useBootstrapNodes = true,
      allowlistEnabled = allowlistEnabled
    )

  private def warnings[A](f: => A): (A, List[String]) =
    val logger = LoggerFactory.getLogger(DiscoveryConfig.getClass).asInstanceOf[LogbackLogger]
    val previous = logger.getLevel
    val appender = com.chipprbots.ethereum.testing.LogCapture.open(logger.getName)
    logger.setLevel(Level.TRACE)
    val result =
      try f
      finally
        logger.setLevel(previous)
        appender.close()
    (result, appender.list.asScala.filter(_.getLevel == Level.WARN).map(_.getFormattedMessage).toList)

  "DiscoveryConfig with the allowlist on" should "turn discovery and the DNS walk off though the config enables them" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    val (config, _) = warnings(load(allowlistEnabled = true))
    config.discoveryEnabled shouldBe false
    config.dnsTrees shouldBe empty
  }

  it should "log one warning that discovery is off because the allowlist is on, when the config said true" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    val (_, warns) = warnings(load(allowlistEnabled = true, discoveryEnabled = true))
    warns.filter(_.contains("Discovery is off because the node allowlist is on")) should have size 1
  }

  it should "not warn when the config already had discovery off" taggedAs (UnitTest, NetworkTest) in {
    val (config, warns) = warnings(load(allowlistEnabled = true, discoveryEnabled = false))
    config.discoveryEnabled shouldBe false
    warns.filter(_.contains("node allowlist")) shouldBe empty
  }

  it should "leave a configured chain's discovery and DNS trees as they were when the allowlist is off" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    val config = load(allowlistEnabled = false)
    config.discoveryEnabled shouldBe true
    config.dnsTrees should have size 2
  }
