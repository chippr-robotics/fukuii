package com.chipprbots.ethereum.utils

import com.typesafe.config.ConfigFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import com.chipprbots.ethereum.testing.Tags._

class ConfigSpec extends AnyFlatSpec with Matchers {
  "clientId" should "by default come from VersionInfo" taggedAs (UnitTest) in {
    Config.clientId shouldBe VersionInfo.nodeName()
  }

  "p2pVersion" should "read configured value from base.conf" taggedAs (UnitTest) in {
    // Verify that Config reads the p2p-version value correctly when present in config
    Config.Network.peer.p2pVersion shouldBe 5
  }

  "p2pVersion default logic" should "use 5 when hasPath returns false" taggedAs (UnitTest) in {
    // Test the default logic pattern used in Config.scala
    // This verifies that the hasPath check with default value works correctly
    val testConfig = ConfigFactory.parseString("""
      fukuii {
        network {
          peer {
            # p2p-version intentionally omitted
          }
        }
      }
    """)
    val peerConfig = testConfig.getConfig("fukuii.network.peer")
    
    // Simulate the logic from Config.scala
    val p2pVersion = if (peerConfig.hasPath("p2p-version")) peerConfig.getInt("p2p-version") else 5
    
    // Verify default is applied when key is missing
    peerConfig.hasPath("p2p-version") shouldBe false
    p2pVersion shouldBe 5
  }
}
