package com.chipprbots.ethereum.network.rlpx

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.network.p2p.messages.Capability

/** Per devp2p RLPx spec, capability offsets are assigned in alphabetical order of capability name, regardless of HELLO
  * order. "eth" < "snap", so eth always gets the lower base. Real-world clients (notably nethermind) list snap before
  * eth in HELLO; reading HELLO order produced wrong offsets and broke peer interop.
  */
class RLPxCapabilityOffsetsSpec extends AnyFlatSpec with Matchers {

  private val EthBase = 0x10

  it should "place eth at the lower base when peer lists snap before eth (nethermind-style)" in {
    val peerCaps = Seq(Capability.SNAP1, Capability.ETH68)
    val (ethBase, ethSize, snapBase) =
      RLPxConnectionHandler.capabilityOffsets(peerCaps, Capability.ETH68, supportsSnap = true)

    ethBase shouldBe EthBase
    ethSize shouldBe 0x11
    snapBase shouldBe Some(EthBase + 0x11)
  }

  it should "place eth at the lower base when peer lists eth before snap (geth-style)" in {
    val peerCaps = Seq(Capability.ETH68, Capability.SNAP1)
    val (ethBase, ethSize, snapBase) =
      RLPxConnectionHandler.capabilityOffsets(peerCaps, Capability.ETH68, supportsSnap = true)

    ethBase shouldBe EthBase
    ethSize shouldBe 0x11
    snapBase shouldBe Some(EthBase + 0x11)
  }

  it should "use ETH/69's larger wire size (18 codes) when negotiated" in {
    val peerCaps = Seq(Capability.ETH69, Capability.SNAP1)
    val (ethBase, ethSize, snapBase) =
      RLPxConnectionHandler.capabilityOffsets(peerCaps, Capability.ETH69, supportsSnap = true)

    ethBase shouldBe EthBase
    ethSize shouldBe 0x12
    snapBase shouldBe Some(EthBase + 0x12)
  }

  it should "place snap at base 0x10 for snap-only peers" in {
    val peerCaps = Seq(Capability.SNAP1)
    val (ethBase, _, snapBase) =
      RLPxConnectionHandler.capabilityOffsets(peerCaps, Capability.ETH68, supportsSnap = true)

    ethBase shouldBe EthBase
    snapBase shouldBe Some(EthBase)
  }

  it should "return no snap base when supportsSnap=false" in {
    val peerCaps = Seq(Capability.ETH68, Capability.SNAP1)
    val (ethBase, _, snapBase) =
      RLPxConnectionHandler.capabilityOffsets(peerCaps, Capability.ETH68, supportsSnap = false)

    ethBase shouldBe EthBase
    snapBase shouldBe None
  }
}
