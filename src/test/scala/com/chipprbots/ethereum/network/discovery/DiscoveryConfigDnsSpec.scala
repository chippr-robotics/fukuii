package com.chipprbots.ethereum.network.discovery

import scala.concurrent.duration.*

import com.typesafe.config.ConfigFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.Config

/** Config load keeps the EIP-1459 trees, each with the key its root must be signed with, and does nothing else with
  * them (H9): the walk is PeerDiscoveryManager's job, after start-up. These domains cannot resolve, so a load that
  * tried would either fail or sit in a DNS timeout; what it must do is hand them on untouched. An entry with no key to
  * verify the tree with is not walked.
  */
class DiscoveryConfigDnsSpec extends AnyFlatSpec with Matchers:

  private val keyed = Seq(
    s"enrtree://${DnsTrustAnchors.EtcKey}@all.one.invalid",
    s"enrtree://${DnsTrustAnchors.EthKey}@all.two.invalid"
  )

  // `useBootstrapNodes` is passed explicitly: its default reads a JVM-wide system property that AppSpec flips, and test
  // suites run in parallel in one JVM.
  private def load(useBootstrapNodes: Boolean, dnsDomains: Seq[String] = keyed): DiscoveryConfig =
    DiscoveryConfig(
      Config.config,
      bootstrapNodes = Set.empty,
      dnsDiscoveryDomains = dnsDomains,
      useBootstrapNodes = useBootstrapNodes
    )

  "DiscoveryConfig" should "keep the DNS trees with their keys and add no nodes for them" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    val config = load(useBootstrapNodes = true)

    config.dnsTrees.map(_.url) shouldBe keyed
    config.bootstrapNodes shouldBe empty
  }

  it should "give each shipped domain the key it is published with" taggedAs (UnitTest, NetworkTest) in {
    val shipped = Seq(
      "all.classic.etcdisco.net",
      "all.classic.blockd.info",
      "all.mordor.etcdisco.net",
      "all.mordor.blockd.info",
      "all.sepolia.ethdisco.net",
      "all.hoodi.ethdisco.net"
    )

    val config = load(useBootstrapNodes = true, dnsDomains = shipped)

    config.dnsTrees.map(_.domain) shouldBe shipped
    config.dnsTrees.map(_.url).foreach(url => url should startWith("enrtree://A"))
  }

  it should "not walk a bare domain it has no key for, nor a malformed URL" taggedAs (UnitTest, NetworkTest) in {
    val config = load(
      useBootstrapNodes = true,
      dnsDomains = Seq("nokey.example.test", "enrtree://not-a-key@broken.test", keyed.head)
    )

    config.dnsTrees.map(_.domain) shouldBe Seq("all.one.invalid")
  }

  it should "keep no trees when none are configured" taggedAs (UnitTest, NetworkTest) in {
    load(useBootstrapNodes = true, dnsDomains = Seq.empty).dnsTrees shouldBe empty
  }

  it should "ignore the trees when bootstrap nodes are disabled" taggedAs (UnitTest, NetworkTest) in {
    load(useBootstrapNodes = false).dnsTrees shouldBe empty
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
      dnsDiscoveryDomains = keyed,
      useBootstrapNodes = true
    )

    config.dnsWalk shouldBe DnsDiscovery.WalkSettings(treeDeadline = 45.seconds, concurrency = 3)
  }
