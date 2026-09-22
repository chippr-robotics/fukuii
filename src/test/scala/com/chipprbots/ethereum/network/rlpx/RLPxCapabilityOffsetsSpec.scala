package com.chipprbots.ethereum.network.rlpx

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.testing.Tags.*

// §8a-E6: Pure unit tests extracted from RLPxConnectionHandlerSpec (no actor dependencies).
// TCP interaction tests remain in RLPxConnectionHandlerSpec (Classic TestKit, Wave 3 gate).
// See DEFERRED-BACKLOG.md §8a-E6 for full context.
class RLPxCapabilityOffsetsSpec extends AnyFlatSpec with Matchers:

  // ── #1189: ETH/SNAP wire-id offsets follow alphabetical capability-name order ──
  // Per devp2p RLPx (https://github.com/ethereum/devp2p/blob/master/rlpx.md#message-id-based-multiplexing),
  // shared cap ids start at 0x10 and are assigned in alphabetical capability-name order, NOT in the
  // order they appear in the peer's Hello list. The wire names are "eth" and "snap"
  // (Capability.scala:17-18); "eth" < "snap" lexicographically, so eth always gets the lower base
  // when both are negotiated. Previously this code derived `snapFirst` from `Hello.capabilities`
  // ordering — Nethermind advertises `snap/1` BEFORE `eth/69`, which tripped that heuristic and
  // mapped the peer's eth/69 Status frame (wire id 0x10, RLPList[7]) onto canonical SNAP
  // GetAccountRange (RLPList[5]), producing `DECODE_ERROR: Cannot decode GetAccountRange ... got
  // RLPList[7]` and disconnects in Hive interop runs.

  "RLPxConnectionHandler.computeCapabilityOffsets" should "place ETH at 0x10 even when the peer advertises snap/1 before eth/69 (Nethermind shape)" taggedAs UnitTest in {
    val nethermindHello = List(Capability.SNAP1, Capability.ETH69)
    val offsets = RLPxConnectionHandler.computeCapabilityOffsets(
      peerCaps = nethermindHello,
      negotiatedEth = Capability.ETH69,
      supportsSnap = true
    )
    offsets.peerEthBase shouldBe 0x10
    // ETH/69 reserves 18 codes (adds BlockRangeUpdate at 0x11), so SNAP starts at 0x10 + 0x12 = 0x22.
    offsets.peerSnapBase shouldBe Some(0x22)
    offsets.peerEthSize shouldBe 0x12
  }

  it should "place ETH at 0x10 when the peer advertises eth/69 before snap/1 (geth-style)" taggedAs UnitTest in {
    val gethHello = List(Capability.ETH69, Capability.SNAP1)
    val offsets = RLPxConnectionHandler.computeCapabilityOffsets(
      peerCaps = gethHello,
      negotiatedEth = Capability.ETH69,
      supportsSnap = true
    )
    offsets.peerEthBase shouldBe 0x10
    offsets.peerSnapBase shouldBe Some(0x22)
  }

  it should "produce identical offsets regardless of peer Hello ordering (Hello order must be irrelevant per devp2p)" taggedAs UnitTest in {
    val negotiated = Capability.ETH69
    val ethFirst = RLPxConnectionHandler.computeCapabilityOffsets(
      peerCaps = List(Capability.ETH69, Capability.SNAP1),
      negotiatedEth = negotiated,
      supportsSnap = true
    )
    val snapFirst = RLPxConnectionHandler.computeCapabilityOffsets(
      peerCaps = List(Capability.SNAP1, Capability.ETH69),
      negotiatedEth = negotiated,
      supportsSnap = true
    )
    ethFirst shouldBe snapFirst
  }

  it should "use 17-code ETH wire size for ETH/68 (no BlockRangeUpdate) and shift SNAP base accordingly" taggedAs UnitTest in {
    val offsets = RLPxConnectionHandler.computeCapabilityOffsets(
      peerCaps = List(Capability.SNAP1, Capability.ETH68),
      negotiatedEth = Capability.ETH68,
      supportsSnap = true
    )
    offsets.peerEthBase shouldBe 0x10
    offsets.peerEthSize shouldBe 0x11
    // ETH/68 reserves 17 codes → SNAP starts at 0x10 + 0x11 = 0x21.
    offsets.peerSnapBase shouldBe Some(0x21)
  }

  it should "use 18-code ETH wire size for ETH/70, matching ETH/69, and shift SNAP base accordingly" taggedAs UnitTest in {
    val offsets = RLPxConnectionHandler.computeCapabilityOffsets(
      peerCaps = List(Capability.SNAP1, Capability.ETH70),
      negotiatedEth = Capability.ETH70,
      supportsSnap = true
    )
    offsets.peerEthBase shouldBe 0x10
    // go-ethereum protocolLengths: {69:18, 70:18}. ETH70 previously fell through to 17 here,
    // which would have placed SNAP one slot low and misrouted every snap message on an
    // ETH70 peering. Dormant in hive only because that profile caps advertisement at eth/69.
    offsets.peerEthSize shouldBe 0x12
    offsets.peerSnapBase shouldBe Some(0x22)
  }

  it should "disable SNAP routing when supportsSnap=false even if peer advertises snap/1" taggedAs UnitTest in {
    val offsets = RLPxConnectionHandler.computeCapabilityOffsets(
      peerCaps = List(Capability.SNAP1, Capability.ETH69),
      negotiatedEth = Capability.ETH69,
      supportsSnap = false
    )
    offsets.peerSnapBase shouldBe None
    offsets.peerEthBase shouldBe 0x10
  }

  it should "place ETH at 0x10 when peer advertises only eth (no snap)" taggedAs UnitTest in {
    val offsets = RLPxConnectionHandler.computeCapabilityOffsets(
      peerCaps = List(Capability.ETH69),
      negotiatedEth = Capability.ETH69,
      supportsSnap = false
    )
    offsets.peerEthBase shouldBe 0x10
    offsets.peerSnapBase shouldBe None
  }

  // Regression: locks the inverse of the old buggy mapping. With the bug, Nethermind shape
  // produced peerSnapBase=0x10 + peerEthBase=0x18; the eth/69 Status frame (wire id 0x10,
  // RLPList[7]) was translated onto canonical SNAP GetAccountRange (0x30, RLPList[5]) and the
  // decoder disconnected the peer. The fix flips the mapping so eth/69 Status stays at 0x10.
  it should "NOT regress: Nethermind shape with old behaviour would have mapped 0x10 to SNAP GetAccountRange" taggedAs UnitTest in {
    val offsets = RLPxConnectionHandler.computeCapabilityOffsets(
      peerCaps = List(Capability.SNAP1, Capability.ETH69),
      negotiatedEth = Capability.ETH69,
      supportsSnap = true
    )
    // The OLD code would have set peerSnapBase to Some(0x10) (= CanonicalEthBase) — meaning a
    // wire id of 0x10 (eth/69 Status) would translate to canonical SNAP id 0x30 (GetAccountRange).
    // Lock that this never happens again.
    offsets.peerSnapBase should not be Some(0x10)
    offsets.peerSnapBase.foreach { snapBase =>
      // SNAP must start strictly above the eth wire range so eth Status (0x10) never collides.
      snapBase should be >= (0x10 + offsets.peerEthSize)
    }
  }
