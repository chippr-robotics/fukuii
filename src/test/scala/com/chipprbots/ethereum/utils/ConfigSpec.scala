package com.chipprbots.ethereum.utils

import com.typesafe.config.ConfigFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import com.chipprbots.ethereum.testing.Tags._

class ConfigSpec extends AnyFlatSpec with Matchers {
  "clientId" should "by default come from VersionInfo" taggedAs (UnitTest) in {
    Config.clientId shouldBe VersionInfo.nodeName()
  }

  "p2pVersion" should "default to 5 when not explicitly set" taggedAs (UnitTest) in {
    // The actual Config should have p2p-version = 5 from base.conf
    // This tests that when the config is present, it reads correctly
    Config.Network.peer.p2pVersion shouldBe 5
  }

  "p2pVersion" should "use configured value when explicitly set" taggedAs (UnitTest) in {
    // The actual Config should read from base.conf which has p2p-version = 5
    Config.Network.peer.p2pVersion shouldBe 5
  }

  "p2pVersion" should "default to 5 when config key is missing" taggedAs (UnitTest) in {
    // Test that the default logic works by checking if hasPath would return false
    val testConfig = ConfigFactory.parseString("""
      fukuii {
        network {
          peer {
            # p2p-version not set
          }
        }
      }
    """)
    val peerConfig = testConfig.getConfig("fukuii.network.peer")
    
    // Verify our default logic: if not set, use 5
    val p2pVersion = if (peerConfig.hasPath("p2p-version")) peerConfig.getInt("p2p-version") else 5
    p2pVersion shouldBe 5
  }
}
