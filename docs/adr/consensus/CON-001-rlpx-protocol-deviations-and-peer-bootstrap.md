# ADR-011: RLPx Protocol Deviations and Peer Bootstrap Challenge

## Status

Accepted (Updated 2025-11-23: Fix 2 revised - see Amendments section)

## Context

During investigation of persistent `FAILED_TO_UNCOMPRESS(5)` errors and peer handshake failures, we discovered multiple protocol deviations by remote peers (primarily CoreGeth clients) and identified a fundamental bootstrap challenge for nodes starting from genesis.

### Initial Problem Statement

- Nodes experiencing decompression failures: `FAILED_TO_UNCOMPRESS(5)` errors from Snappy library
- Status message code 0x10 suspected of causing issues
- Peer handshakes completing but connections immediately terminated
- Zero maintained peer connections despite successful discovery and status exchanges

### Investigation Findings

#### 1. RLPx Protocol Deviations by Remote Peers

Through systematic debugging and packet-level analysis, we discovered FOUR distinct protocol deviations by CoreGeth clients:

**Deviation 1: Wire Protocol Message Compression**
- **Observed**: CoreGeth clients compressing Wire Protocol messages (Hello, Disconnect, Ping, Pong - codes 0x00-0x03)
- **Specification**: Per [RLPx v5 specification](https://github.com/ethereum/devp2p/blob/master/rlpx.md#framing), wire protocol messages (0x00-0x03) MUST NEVER be compressed regardless of p2pVersion
- **Impact**: Snappy decompression failing on received wire protocol frames

**Deviation 2: Uncompressed Capability Messages**
- **Observed**: CoreGeth clients sending uncompressed RLP data for capability messages (e.g., Status 0x10) when p2pVersion >= 4
- **Specification**: For p2pVersion >= 4, all capability messages (>= 0x10) MUST be Snappy-compressed before framing
- **Impact**: Receiving raw RLP data when compressed data expected, causing decompression failures

**Deviation 3: Malformed Disconnect Messages**
- **Observed**: Disconnect messages sent as single-byte values (e.g., `0x10`) instead of RLP lists
- **Specification**: Disconnect messages should be encoded as `RLPList(reason)` per devp2p specification
- **Impact**: Decoder expecting RLPList pattern failed on single RLPValue, causing "Cannot decode Disconnect" errors

**Deviation 4: P2P Protocol Version Mismatch**
- **Observed**: When Fukuii advertised p2pVersion 4, CoreGeth clients would send and expect uncompressed messages, but Fukuii was compressing messages
- **Specification**: RLPx v5 spec suggests compression for p2pVersion >= 4, but CoreGeth implementation uses >= 5
- **Impact**: CoreGeth clients could not decode compressed messages from Fukuii, leading to immediate disconnection with reason 0x10
- **Root Cause**: CoreGeth uses `snappyProtocolVersion = 5` (defined in `p2p/peer.go`), while Fukuii was using threshold of >= 4 for compression
- **Solution**: Aligned Fukuii's p2pVersion from 4 to 5 to match CoreGeth's compression threshold

#### 2. The Peer Bootstrap Challenge

After eliminating all decoding errors, we discovered peers were still disconnecting immediately after successful status exchange. Analysis revealed:

**Root Cause**: Genesis Block Advertisement
- Fukuii starting from genesis advertises:
  - `totalDifficulty`: 17,179,869,184 (2^34, genesis difficulty)
  - `bestHash`: d4e56740... (genesis block hash)
  - `bestHash == genesisHash` (indicating zero blockchain data)

**Peer Response**: Immediate Disconnection
- CoreGeth and other clients identify Fukuii as having no useful blockchain data
- Disconnect with reason `0x10` (Other - "Some other reason specific to a subprotocol")
- This is **correct behavior** per Ethereum protocol: peers should disconnect from useless peers to conserve resources

**The Bootstrap Paradox**:
```
┌─────────────────────────────────────────────────────────┐
│  Start from Genesis → No Data → Peers Disconnect       │
│         ↑                                      ↓         │
│    Can't Sync ←─────── Need 3 Peers ──────────┘         │
└─────────────────────────────────────────────────────────┘
```

- Fast sync (snap sync) requires minimum 3 peers to select pivot block
- Regular peers disconnect from genesis-only nodes
- Cannot sync without peers, cannot get peers without synced data

### Network Testing Results

**ETC Mainnet (Ethereum Classic)**:
- Discovered 29 nodes, all CoreGeth clients
- Successfully completed status exchanges with multiple peers
- All three protocol deviations observed consistently
- All peers disconnected with reason 0x10 after detecting genesis-only status
- 0 handshaked peers maintained after 60 seconds

**ETH Mainnet (Ethereum)**:
- Discovered 6 nodes
- Connections remain in "pending" state indefinitely
- No protocol activity observed
- Different behavior suggests ETH network peers may have stricter connection policies

### Code Locations

**MessageCodec.scala** (`/workspaces/fukuii/src/main/scala/com/chipprbots/ethereum/network/rlpx/MessageCodec.scala`):
- Handles frame decoding and Snappy compression/decompression
- Key method: `readFrames()` - processes incoming frames and applies compression

**WireProtocol.scala** (`/workspaces/fukuii/src/main/scala/com/chipprbots/ethereum/network/p2p/messages/WireProtocol.scala`):
- Defines wire protocol messages and their encoding/decoding
- `DisconnectDec` - decoder for Disconnect messages

**EtcNodeStatusExchangeState.scala** (`/workspaces/fukuii/src/main/scala/com/chipprbots/ethereum/network/handshaker/EtcNodeStatusExchangeState.scala`):
- `getBestBlockHeader()` - returns genesis header when blockchain is empty
- `createStatusMsg()` - builds status message advertised to peers

**PeerActor.scala** (`/workspaces/fukuii/src/main/scala/com/chipprbots/ethereum/network/PeerActor.scala`):
- `handleDisconnectMsg()` - processes disconnect reasons and triggers blacklisting

## Decision

### Implemented: Defensive Protocol Handling

We implement defensive programming to handle protocol deviations gracefully while maintaining specification compliance:

#### Fix 1: Wire Protocol Message Compression Detection
```scala
// In MessageCodec.readFrames()
val isWireProtocolMessage = frame.`type` >= 0x00 && frame.`type` <= 0x03
val shouldDecompress = !isWireProtocolMessage && p2pVersion >= 4
```
- **Rationale**: Explicitly exclude wire protocol messages from compression regardless of p2pVersion
- **Impact**: Prevents decompression attempts on Hello, Disconnect, Ping, Pong messages

#### Fix 2: RLP Detection for Uncompressed Data
```scala
// In MessageCodec.readFrames()
val looksLikeRLP = frameData.nonEmpty && {
  val firstByte = frameData(0) & 0xFF
  firstByte >= 0xc0 || (firstByte >= 0x80 && firstByte < 0xc0)
}

if (shouldDecompress && !looksLikeRLP) {
  // Decompress
} else if (shouldDecompress && looksLikeRLP) {
  log.warn(s"Frame type 0x${frame.`type`.toHexString}: Peer sent uncompressed RLP data despite p2pVersion >= 4 (protocol deviation)")
  // Use raw data
}
```
- **Rationale**: RLP encoding has predictable first-byte patterns (0xc0-0xff for lists, 0x80-0xbf for strings)
- **Impact**: Gracefully handles peers with protocol deviations sending uncompressed data
- **Trade-off**: False positives theoretically possible but practically unlikely (compressed data rarely starts with RLP-like bytes)

#### Fix 3: Flexible Disconnect Message Decoding
```scala
// In WireProtocol.DisconnectDec
def toDisconnect: Disconnect = rawDecode(bytes) match {
  case RLPList(RLPValue(reasonBytes), _*) =>
    // Spec-compliant case
    Disconnect(reason = ByteUtils.bytesToBigInt(reasonBytes).toLong)
  case RLPValue(reasonBytes) =>
    // Protocol deviation: single value instead of list
    Disconnect(reason = ByteUtils.bytesToBigInt(reasonBytes).toLong)
  case _ => throw new RuntimeException("Cannot decode Disconnect")
}
```
- **Rationale**: Accept both spec-compliant RLPList and non-standard single RLPValue
- **Impact**: Successfully decode disconnect messages from peers with protocol deviations

#### Fix 4: P2P Protocol Version Alignment with CoreGeth
```scala
// In EtcHelloExchangeState.scala
object EtcHelloExchangeState {
  // Use p2pVersion 5 to align with CoreGeth and enable Snappy compression
  // CoreGeth (and go-ethereum) only enable Snappy when p2pVersion >= 5
  // See: https://github.com/etclabscore/core-geth/blob/master/p2p/peer.go#L54
  val P2pVersion = 5
}
```
- **Rationale**: CoreGeth uses `snappyProtocolVersion = 5` and only enables Snappy compression when `p2pVersion >= 5`. Our previous p2pVersion 4 caused a mismatch where we compressed messages but CoreGeth expected uncompressed messages.
- **Impact**: Aligning to p2pVersion 5 ensures both sides agree on when to enable Snappy compression, preventing decode failures and disconnections
- **Root Cause**: When we advertised p2pVersion 4, CoreGeth clients would NOT enable Snappy compression (since 4 < 5), but our MessageCodec was compressing messages (since we used >= 4 threshold). CoreGeth couldn't decode the compressed messages and disconnected with reason 0x10.
- **Solution**: Changed from p2pVersion 4 to 5 to match CoreGeth's snappyProtocolVersion threshold

### Documented: Bootstrap Challenge

We document the bootstrap challenge but **do not implement a workaround** at this time because:

1. **This is expected behavior**: Peers correctly disconnect from useless (genesis-only) peers
2. **Standard Ethereum behavior**: All clients face this challenge when starting from genesis
3. **Existing solutions**: 
   - Fast sync requires 3+ peers willing to provide pivot block
   - Full sync requires peers tolerant of genesis-only nodes
   - Bootstrap/sync nodes specifically designed to help new nodes
4. **Infrastructure solution**: Operators should run dedicated bootstrap nodes or use checkpoints

## Consequences

### Positive

1. **Protocol Deviations Handled**: All four CoreGeth protocol deviations now handled gracefully (wire protocol compression, uncompressed capability messages, malformed disconnect messages, and p2pVersion compression threshold mismatch)
2. **Decode Errors Eliminated**: Zero "Cannot decode" or "FAILED_TO_UNCOMPRESS" errors in testing
3. **Status Exchanges Succeed**: Handshake protocol completing successfully through status exchange
4. **Defensive But Compliant**: Code handles deviations while remaining specification-compliant
5. **Well-Documented**: Bootstrap challenge clearly documented for operators
6. **Network Interoperability**: Can communicate with CoreGeth and other clients despite their protocol deviations

### Negative

1. **Bootstrap Challenge Remains**: Nodes starting from genesis still cannot maintain peers
2. **RLP Detection Heuristic**: First-byte RLP detection is a heuristic, not foolproof
3. **Protocol Tolerance**: By accepting protocol deviations, we may enable continued non-standard implementations
4. **Blacklisting Churn**: Genesis-only nodes will repeatedly connect and get blacklisted

### Neutral

1. **Requires Infrastructure**: Operators must either:
   - Import blockchain checkpoint
   - Run dedicated bootstrap nodes
   - Use fast sync with established nodes
2. **Not a Bug**: Bootstrap challenge is a feature, not a bug - prevents network spam from useless peers

## Implementation Details

### Testing Methodology

**Test Environment**: ETC Mainnet (primary), ETH Mainnet (comparison)
**Test Duration**: 60-120 second runs
**Metrics Collected**:
- Peer discovery count
- Connection attempt count
- Status exchange success count
- Disconnect reason codes
- Protocol deviation frequency

**Key Log Analysis Commands**:
```bash
# Check status exchanges
grep -E "(Sending status|Successfully received|Peer returned status)" /tmp/fukuii_test.log

# Verify no decode errors
grep -E "Cannot decode|Unknown eth|FAILED_TO_UNCOMPRESS" /tmp/fukuii_test.log

# Check disconnect reasons
grep -E "Received Disconnect|Blacklisting" /tmp/fukuii_test.log

# Monitor handshake progress
grep -E "Handshaked" /tmp/fukuii_test.log
```

### Validation Results

**Before Fixes**:
- Persistent `FAILED_TO_UNCOMPRESS(5)` errors
- "Cannot decode Disconnect" errors
- "Unknown network message type: 16" warnings
- Connection handlers terminating unexpectedly
- Dead letter messages to TCP actors

**After Fixes**:
- ✅ Zero decompression errors
- ✅ Zero decode errors
- ✅ Successful status exchanges
- ✅ Clean connection termination
- ✅ Proper disconnect reason logging
- ❌ Still 0 handshaked peers (expected due to genesis-only status)

### CoreGeth Analysis

**Observed Client**: CoreGeth/v1.12.20-stable-c2fb4412/linux-amd64/go1.21.10
**Protocol Deviations**: All three deviations consistently observed
**Capabilities Advertised**: ETH68 (but negotiates to ETH64)
**Disconnect Reason**: 0x10 (Other) after genesis-only status detected

**Hypothesis**: CoreGeth implementation may have:
1. Different wire protocol compression logic
2. Alternative p2pVersion handling for capability messages
3. Non-standard Disconnect message encoding

## Alternatives Considered

### Alternative 1: Strict Spec Enforcement
**Description**: Reject all messages with protocol deviations and disconnect peers
**Rejected Because**: Would eliminate most ETC mainnet peers (CoreGeth dominance)

### Alternative 2: Fake Blockchain Status
**Description**: Advertise non-genesis block even when at genesis to avoid disconnects
**Rejected Because**: Violates protocol honesty, would cause sync failures, unethical

### Alternative 3: Checkpoint Import
**Description**: Bundle trusted checkpoint in client, import on first start
**Rejected Because**: 
- Centralization concern (who controls checkpoints?)
- Blockchain should be verifiable from genesis
- Infrastructure problem, not protocol problem

### Alternative 4: Bootstrap Node Mode
**Description**: Add special "bootstrap node" mode that accepts genesis-only peers
**Deferred Because**: Infrastructure solution better handled by dedicated bootstrap nodes

## References

### Specifications
1. [RLPx Protocol v5](https://github.com/ethereum/devp2p/blob/master/rlpx.md)
2. [Ethereum devp2p Specifications](https://github.com/ethereum/devp2p)
3. [Ethereum Wire Protocol (ETH)](https://github.com/ethereum/devp2p/blob/master/caps/eth.md)
4. [Snappy Compression Format](https://github.com/google/snappy/blob/master/format_description.txt)

### Implementation References
1. Go Ethereum (Geth) - devp2p implementation
2. CoreGeth - ETC-focused fork with observed protocol deviations
3. Besu - Java-based Ethereum client
4. [RLP Encoding Specification](https://ethereum.org/en/developers/docs/data-structures-and-encoding/rlp/)

### Related Documentation
1. [Known Issues Runbook](../runbooks/known-issues.md#issue-14-eth68-peer-connection-failures)
2. [Peering Runbook](../runbooks/peering.md#troubleshooting-connectivity)
3. [First Start Runbook](../runbooks/first-start.md#initial-synchronization)

## Future Work

### Short Term
1. **Enhanced Protocol Logging**: Add metrics for protocol deviation frequency
2. **Client Detection**: Identify and track which client implementations have protocol deviations
3. **Automated Testing**: Create test suite with peers exhibiting various protocol deviations

### Medium Term
1. **Bootstrap Node Implementation**: Add dedicated bootstrap mode that tolerates genesis-only peers
2. **Checkpoint Support**: Add optional trusted checkpoint import for faster bootstrap
3. **Protocol Deviation Documentation**: Share findings with CoreGeth project for potential alignment

### Long Term
1. **Snap Sync Enhancement**: Optimize snap sync to work with fewer peers
2. **Protocol Hardening**: Evaluate moving to stricter protocol enforcement once ecosystem improves
3. **Community Engagement**: Work with ETC community to improve protocol compliance across clients

## Lessons Learned

1. **Real-World Protocols Are Messy**: Specifications and implementations often diverge; defensive programming essential
2. **Heuristics Have Value**: First-byte RLP detection is simple but effective for real-world protocol variations
3. **Bootstrap Is Hard**: All blockchain clients face the genesis bootstrap challenge; no perfect solution
4. **Testing Reveals Truth**: Comprehensive logging and real-network testing revealed issues unit tests missed
5. **Protocol Deviations Are Common**: Even widely-deployed clients (CoreGeth) can deviate from specifications
6. **Infrastructure Matters**: Some problems are better solved with infrastructure than code changes

## Decision Log

- **2025-11-05**: Initial investigation started after persistent FAILED_TO_UNCOMPRESS errors
- **2025-11-05**: Identified code:16 as Status message (not a bug)
- **2025-11-05**: Implemented wire protocol compression fix
- **2025-11-05**: Added RLP detection for uncompressed data
- **2025-11-05**: Fixed Disconnect message decoder
- **2025-11-06**: Confirmed all decode errors eliminated
- **2025-11-06**: Identified bootstrap challenge as root cause of peer maintenance failures
- **2025-11-06**: Tested on both ETC and ETH mainnet
- **2025-11-06**: Documented findings in ADR-011
- **2025-11-23**: Amendment - Fix 2 revised to address false positive issue (see Amendments section)

## Amendments

### Amendment 2025-11-23: Fix 2 Revision - looksLikeRLP False Positives

**Issue Discovered**: The original Fix 2 implementation had a critical flaw. The `looksLikeRLP` check was performed BEFORE attempting decompression, which caused false positives when compressed data started with bytes in the 0x80-0xff range.

**Specific Case**: Snappy-compressed `NewPooledTransactionHashes` messages starting with byte `0x94`:
- Byte `0x94` is in the RLP range (0x80-0xbf), triggering `looksLikeRLP=true`
- Decompression was skipped, raw Snappy data passed to RLP decoder
- RLP decoder interpreted `0x94` as "string of 20 bytes", causing decode error
- Error: `ETH67_DECODE_ERROR: Unexpected RLP structure. Expected [RLPValue, RLPList, RLPList] (ETH67/68) or RLPList (ETH65 legacy), got: RLPValue(20 bytes)`

**Original Trade-off Assessment**: The original ADR stated "False positives theoretically possible but practically unlikely (compressed data rarely starts with RLP-like bytes)". This assumption proved incorrect - compressed data frequently starts with bytes in the 0x80-0xff range.

**Revised Implementation**:
```scala
// NEW: Always attempt decompression first
if (shouldCompress) {
  decompressData(frameData, frame).recoverWith { case ex =>
    // Fall back to uncompressed ONLY if decompression fails AND looks like RLP
    if (looksLikeRLP(frameData)) {
      log.warn("Decompression failed but data looks like RLP - using as uncompressed (peer protocol deviation)")
      Success(frameData)
    } else {
      Failure(ex)  // Reject invalid data
    }
  }
}
```

**Key Changes**:
1. Always attempt decompression when `shouldCompress=true`
2. Only check `looksLikeRLP` as fallback after decompression fails
3. This correctly handles both:
   - Compressed data (including when it starts with 0x80-0xff bytes)
   - Uncompressed RLP from protocol-deviating peers

**Impact**:
- ✅ Fixes peer disconnections when receiving `NewPooledTransactionHashes` messages
- ✅ Correctly decompresses messages regardless of starting byte
- ✅ Maintains graceful handling of protocol deviations (uncompressed data)
- ✅ More robust and correct than original implementation

**Files Modified**:
- `src/main/scala/com/chipprbots/ethereum/network/rlpx/MessageCodec.scala`
- `LOG_REVIEW_RESOLUTION.md` (detailed analysis)

**Reference**: See `LOG_REVIEW_RESOLUTION.md` for full technical analysis of the issue and fix.
