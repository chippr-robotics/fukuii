package com.chipprbots.ethereum.network.discovery

import scala.concurrent.duration.*

import com.typesafe.config.ConfigFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.Config

/** Config load keeps the EIP-1459 domain names and does nothing else with them (H9): the walk is PeerDiscoveryManager's
  * job, after start-up. These domains cannot resolve, so a load that tried would either fail or sit in a DNS timeout;
  * what it must do is hand them on untouched.
  */
class DiscoveryConfigDnsSpec extends AnyFlatSpec with Matchers:

  private val domains = Seq("all.one.invalid", "all.two.invalid")

  // `useBootstrapNodes` is passed explicitly: its default reads a JVM-wide system property that AppSpec flips, and test
  // suites run in parallel in one JVM.
  private def load(useBootstrapNodes: Boolean, dnsDomains: Seq[String] = domains): DiscoveryConfig =
    DiscoveryConfig(
      Config.config,
      bootstrapNodes = Set.empty,
      dnsDiscoveryDomains = dnsDomains,
      useBootstrapNodes = useBootstrapNodes
    )

  "DiscoveryConfig" should "keep the DNS domains and add no nodes for them" taggedAs (UnitTest, NetworkTest) in {
    val config = load(useBootstrapNodes = true)

    config.dnsDomains shouldBe domains
    config.bootstrapNodes shouldBe empty
  }

  it should "keep no domains when none are configured" taggedAs (UnitTest, NetworkTest) in {
    load(useBootstrapNodes = true, dnsDomains = Seq.empty).dnsDomains shouldBe empty
  }

  it should "ignore the domains when bootstrap nodes are disabled" taggedAs (UnitTest, NetworkTest) in {
    load(useBootstrapNodes = false).dnsDomains shouldBe empty
  }

  it should "use the default DNS walk settings when network.discovery does not set them" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    val config = load(useBootstrapNodes = true)

    config.dnsWalk shouldBe DnsDiscovery.WalkSettings(treeDeadline = 120.seconds, concurrency = 8)
    config.dnsWalk shouldBe DnsDiscovery.WalkSettings.Default
  }

  it should "read the DNS walk settings from network.discovery when it sets them" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    val overridden = ConfigFactory
      .parseString("network.discovery { dns-tree-deadline = 45.seconds, dns-concurrency = 3 }")
      .withFallback(Config.config)

    val config = DiscoveryConfig(
      overridden,
      bootstrapNodes = Set.empty,
      dnsDiscoveryDomains = domains,
      useBootstrapNodes = true
    )

    config.dnsWalk shouldBe DnsDiscovery.WalkSettings(treeDeadline = 45.seconds, concurrency = 3)
  }
