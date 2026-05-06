package com.chipprbots.ethereum.network.discovery

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.typesafe.config.ConfigFactory

import com.chipprbots.ethereum.testing.Tags._
import com.chipprbots.ethereum.utils.BlockchainConfig

class DnsDiscoverySpec extends AnyFlatSpec with Matchers {

  "DnsDiscovery" should "resolve enodes from Mordor DNS tree" taggedAs (IntegrationTest, NetworkTest) in {
    val enodes = DnsDiscovery.resolveEnodes("all.mordor.blockd.info")
    enodes should not be empty
    enodes.size should be >= 10
    enodes.foreach { enode =>
      enode should startWith("enode://")
      enode should include("@")
      val nodeId = enode.stripPrefix("enode://").takeWhile(_ != '@')
      nodeId.length shouldBe 128
    }
    info(s"Resolved ${enodes.size} Mordor enode(s)")
  }

  it should "resolve at least 200 enodes from the live ETC mainnet DNS tree" taggedAs (
    IntegrationTest,
    NetworkTest
  ) in {
    val enodes = DnsDiscovery.resolveEnodes("all.classic.etcdisco.net")
    enodes.size should be >= 200
    enodes.foreach { enode =>
      enode should startWith("enode://")
      enode should include("@")
      val nodeId = enode.stripPrefix("enode://").takeWhile(_ != '@')
      nodeId.length shouldBe 128
    }
    info(s"Resolved ${enodes.size} ETC mainnet enode(s)")
  }

  // Regression guard: catches stale domain regression (PR #1200).
  // A stale domain's TXT record still exists so no WARN fires — yield silently drops to 0.
  // This test fails loudly when any configured DNS domain yields 0 peers.
  it should "yield at least 1 enode from every domain configured in etc-chain.conf" taggedAs (
    IntegrationTest,
    NetworkTest
  ) in {
    val fullConfig = ConfigFactory.load()
    val etcConfig = BlockchainConfig.fromRawConfig(fullConfig.getConfig("fukuii.blockchains.etc"))
    etcConfig.dnsDiscoveryDomains should not be empty
    etcConfig.dnsDiscoveryDomains.foreach { domain =>
      val enodes = DnsDiscovery.resolveEnodes(domain)
      withClue(s"Domain '$domain' yielded 0 enodes (stale domain? rotate to a live registry)") {
        enodes should not be empty
      }
      info(s"Domain '$domain': ${enodes.size} enode(s)")
    }
  }

  it should "return empty set for non-existent domain" taggedAs UnitTest in {
    val enodes = DnsDiscovery.resolveEnodes("nonexistent.invalid.domain.example.com")
    enodes shouldBe empty
  }

  it should "return empty set for domain with no ENR tree" taggedAs UnitTest in {
    val enodes = DnsDiscovery.resolveEnodes("google.com")
    enodes shouldBe empty
  }

  "parseEnrToEnode" should "return None for invalid base64 ENR" taggedAs UnitTest in {
    val result = DnsDiscovery.parseEnrToEnode("enr:invalid-base64!!!")
    result shouldBe None
  }

  it should "return None for empty ENR payload" taggedAs UnitTest in {
    val result = DnsDiscovery.parseEnrToEnode("enr:")
    result shouldBe None
  }

  it should "return None for truncated ENR" taggedAs UnitTest in {
    val result = DnsDiscovery.parseEnrToEnode("enr:AAAA")
    result shouldBe None
  }
}
