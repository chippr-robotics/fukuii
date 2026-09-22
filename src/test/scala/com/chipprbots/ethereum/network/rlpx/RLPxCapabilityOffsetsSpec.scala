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

  // ── hive devp2p `snap`/`snap2` suites: confirmed NOT a fukuii offset bug ──
  // Root-caused against the 2026-09-22 hive artifact (snap: AccountRange/GetByteCodes/
  // GetTrieNodes/GetStorageRanges all fail `i/o timeout`; fukuii's own server log for the
  // same run shows e.g. `DECODE_ERROR: ... Cannot decode GetByteCodes. Expected RLPList[3]`
  // for a request hive sent as GetAccountRange, and `Unknown snap/1 message type: 42/44` for
  // hive's actual GetByteCodes/GetTrieNodes requests). Traced to go-ethereum's OWN
  // `cmd/devp2p/internal/ethtest/protocol.go`, which hardcodes `ethProtoLen = 22` (the slot
  // count for eth/72) for every peering regardless of which eth version was actually
  // negotiated — see `getProto`'s doc comment: "assuming the negotiated capabilities are
  // exactly {eth,snap}". Real go-ethereum servers (`eth/protocols/eth/handler.go` via
  // `p2p.Protocol{Length: protocolLengths[version]}`, `eth/protocols/eth/protocol.go:
  // protocolLengths = {69:18, 70:18, 71:20, 72:22}`) do NOT do this — they size the eth slot
  // per the version actually negotiated, exactly like `ethWireSizeFor` above. Because fukuii
  // only advertises up to eth/69 (`InstanceConfig.scala` never sets an eth70 capability flag),
  // it negotiates eth/69 (Length 18) with this test tool, so its `peerSnapBase` is 0x22 — but
  // the tool writes/reads snap frames assuming 0x26 (0x10 + 0x16), a 4-slot shift. Every real
  // snap message lands on the wrong canonical SNAP slot (GetAccountRange → decoded as
  // GetByteCodes, GetByteCodes → falls outside fukuii's [0x22,0x2a) window entirely = "unknown
  // message type", etc.), so fukuii never sends a reply the tool recognises within its 2s read
  // deadline (`var timeout = 2 * time.Second` in the same file).
  //
  // This is upstream go-ethereum test-tool behaviour, not a fukuii wire defect: fukuii's
  // dynamic per-negotiated-version sizing is what matches the *real* go-ethereum server
  // implementation, and is what protects real interop with any live eth/69 + snap/1 peer.
  // "Fixing" `ethWireSizeFor`/`computeCapabilityOffsets` to hardcode a fixed 0x16 slot count
  // to chase this specific test tool would BREAK real geth/Nethermind/Besu interop at eth/68
  // and eth/69 (see the ETH68 case a few tests above, which correctly gets 0x21, not 0x26).
  // The only way to make the go-ethereum devp2p CLI tool's own assumption hold is for fukuii
  // to negotiate eth/72 with it — i.e. implement eth/71 and eth/72, which is out of scope here
  // (see AGENTS.md herald: "ETH68/69/70 only") and unrelated to Amsterdam (specs/009).
  //
  // This test pins the exact peer shape the hive tool presents (it advertises eth/72 and
  // eth/70 in its Hello alongside eth/69 and snap/1; fukuii doesn't recognise eth/71 or eth/72
  // and silently drops them per `Capability.toCapability`'s EIP-8 leniency, and doesn't
  // advertise eth/70 itself, so negotiation lands on eth/69) so a future "fix" attempt has to
  // consciously break this assertion rather than silently regress it.
  it should "NOT shift SNAP base to please the hive devp2p CLI tool's fixed eth/72-sized offset assumption (peer also advertises eth/70, unrecognised eth/71/72 dropped)" taggedAs UnitTest in {
    val hiveDevp2pToolHello = List(Capability.ETH70, Capability.ETH69, Capability.SNAP1)
    val offsets = RLPxConnectionHandler.computeCapabilityOffsets(
      peerCaps = hiveDevp2pToolHello,
      negotiatedEth = Capability.ETH69, // fukuii only advertises up to eth/69, so eth/69 wins
      supportsSnap = true
    )
    offsets.peerEthBase shouldBe 0x10
    offsets.peerEthSize shouldBe 0x12
    // Correct per real go-ethereum (protocolLengths(eth/69) = 18) — NOT 0x26 (0x10 + 0x16),
    // which is what the hive devp2p CLI tool's own hardcoded `ethProtoLen = 22` would assume.
    offsets.peerSnapBase shouldBe Some(0x22)
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
