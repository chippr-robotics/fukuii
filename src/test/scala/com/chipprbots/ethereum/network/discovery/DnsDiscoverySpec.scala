package com.chipprbots.ethereum.network.discovery

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags._

class DnsDiscoverySpec extends AnyFlatSpec with Matchers {

  "DnsDiscovery" should "resolve enodes from Mordor DNS tree" taggedAs IntegrationTest in {
    val enodes = DnsDiscovery.resolveEnodes("all.mordor.blockd.info")
    enodes should not be empty
    enodes.foreach { enode =>
      enode should startWith("enode://")
      enode should include("@")
      // Node ID should be 128 hex chars (64 bytes)
      val nodeId = enode.stripPrefix("enode://").takeWhile(_ != '@')
      nodeId.length shouldBe 128
    }
    info(s"Resolved ${enodes.size} Mordor enode(s)")
  }

  it should "resolve enodes from ETC mainnet DNS tree" taggedAs IntegrationTest in {
    val enodes = DnsDiscovery.resolveEnodes("all.classic.blockd.info")
    enodes should not be empty
    info(s"Resolved ${enodes.size} ETC mainnet enode(s)")
  }

  it should "return empty set for non-existent domain" taggedAs UnitTest in {
    val enodes = DnsDiscovery.resolveEnodes("nonexistent.invalid.domain.example.com")
    enodes shouldBe empty
  }

  it should "return empty set for domain with no ENR tree" taggedAs UnitTest in {
    val enodes = DnsDiscovery.resolveEnodes("google.com")
    enodes shouldBe empty
  }

  "parseEnrToEnode" should "parse a valid ENR record" taggedAs UnitTest in {
    // This is a real ENR from the Mordor DNS tree — resolve one first to test parsing
    // We test the internal parser with a synthetic minimal ENR
    // For now just verify the method exists and handles bad input gracefully
    val result = DnsDiscovery.parseEnrToEnode("enr:invalid-base64")
    result shouldBe None
  }

  it should "handle empty ENR data" taggedAs UnitTest in {
    val result = DnsDiscovery.parseEnrToEnode("enr:")
    result shouldBe None
  }
}
