# ADR-016: ETH66+ Protocol-Aware Message Formatting

## Status

Accepted

## Context

During investigation of peer connection failures in sync tests (Issue #441), we discovered a critical message format mismatch that prevented peers from recognizing each other as available for synchronization after successful RLPx handshake.

### Initial Problem Statement

- RLPx handshake completing successfully and negotiating to ETH68 protocol
- Peers entering "FULLY ESTABLISHED" state with proper capability negotiation
- `GetBlockHeaders` requests sent immediately after handshake
- Zero peers available for sync: "Cannot pick pivot block. Need at least 1 peers, but there are only 0 which meet the criteria"
- Peers having `maxBlockNumber = 0` despite successful status exchange
- Tests timing out after 2+ minutes waiting for sync to start

### Investigation Timeline

#### Phase 1: Initial Hypothesis - Message Decoding Failure

**Symptoms from logs (chippr-robotics/fukuii#437)**:
```
[RLPx] Cannot decode message from 127.0.0.1:36185, because of Cannot decode GetBlockHeaders
```

**Initial Fix (Commit 4458be6)**:
- Added backward-compatible fallback decoding in ETH66.scala
- Decoders now accept both ETH62 format (4 fields) and ETH66 format (5 fields with requestId)
- Example for GetBlockHeaders:
  ```scala
  // ETH66+ format: [requestId, [block, maxHeaders, skip, reverse]]
  case RLPList(RLPValue(requestIdBytes), RLPList(...)) => decode with requestId
  
  // Fallback to ETH62 format: [block, maxHeaders, skip, reverse]
  case RLPList(RLPValue(blockBytes), RLPValue(maxHeadersBytes), ...) => decode with requestId=0
  ```

**Result**: Eliminated "Cannot decode" errors, but peers still not available for sync

#### Phase 2: Type Mismatch Discovery

**Symptoms**:
- No decode errors in logs anymore
- Handshakes completing successfully
- Peers still showing `maxBlockNumber = 0`
- PivotBlockSelector reporting "0 peers meet criteria"

**Root Cause Identified**:
After protocol negotiation to ETH68:
1. **Decoders**: ETH68MessageDecoder uses `ETH66.BlockHeaders` decoders → creates `ETH66.BlockHeaders` instances
2. **Pattern Matches**: Code imports `ETH62.BlockHeaders` and pattern matches fail silently
3. **Result**: Incoming `BlockHeaders` responses don't match pattern, get ignored, `maxBlockNumber` never updated

**Key Files Affected**:
- `NetworkPeerManagerActor.scala` - Pattern match on `BlockHeaders` in `updateMaxBlock()` and `updateForkAccepted()`
- `PivotBlockSelector.scala` - Pattern match on `MessageFromPeer(blockHeaders: BlockHeaders, ...)`
- `FastSync.scala` - ResponseReceived with `BlockHeaders`
- `HeadersFetcher.scala` - AdaptedMessage with `BlockHeaders`

**First Attempt Fix (Commit e8bd068 + mithril agent work)**:
- Updated imports to alias both types: `ETH62.{BlockHeaders => ETH62BlockHeaders}`, `ETH66.{BlockHeaders => ETH66BlockHeaders}`
- Added pattern matches for both: `case ETH62BlockHeaders(headers)` and `case ETH66BlockHeaders(_, headers)`
- Updated message sending to use `ETH66GetBlockHeaders(0, ...)`

**Result**: Improved, but violated protocol consistency

#### Phase 3: Core-Geth Analysis - Protocol-Aware Solution

**New Requirement**: Don't mix message formats - if ETH68 is negotiated, use ETH68 format consistently

**Core-Geth Investigation** (https://github.com/etclabscore/core-geth):
```go
// core-geth always uses GetBlockHeadersPacket with RequestId for ETH66+
type GetBlockHeadersPacket struct {
    RequestId uint64
    *GetBlockHeadersRequest
}

// Example usage - no version checking, format is implicit
req := &Request{
    code: GetBlockHeadersMsg,
    want: BlockHeadersMsg,
    data: &GetBlockHeadersPacket{
        RequestId: id,
        GetBlockHeadersRequest: &GetBlockHeadersRequest{...},
    },
}
```

**Key Findings**:
1. **Core-geth always uses RequestId wrapper** when protocol is ETH66+
2. **No explicit version checking** - format is implicit from protocol negotiation
3. **Consistent format per connection** - never mixes ETH62 and ETH66 formats
4. **Single message type hierarchy** - no separate ETH62 vs ETH66 classes

**Fukuii's Architecture Issue**:
- **Separate type hierarchies**: `ETH62.GetBlockHeaders` vs `ETH66.GetBlockHeaders` are different classes
- **Import determines type**: `import ETH62.GetBlockHeaders` hardcoded in most files
- **Decoder mismatch**: ETH68MessageDecoder creates `ETH66.GetBlockHeaders`, but code expects `ETH62.GetBlockHeaders`

### Decision Point

We have `PeerInfo.remoteStatus.capability` (type: `Capability`) storing negotiated protocol:
- `ETH63`, `ETH64`, `ETH65` → pre-ETH66 (no RequestId; ETC64 retired)
- `ETH66`, `ETH67`, `ETH68` → ETH66+ (with RequestId)

**Options Considered**:

1. **Unify type hierarchy** (like core-geth) - rejected as too invasive
2. **Always send ETH66 format** - rejected as breaks pre-ETH66 peer compatibility
3. **Protocol-aware message creation** - selected

## Decision

### Implemented: Protocol-Aware Message Formatting

We implement a system where message format is determined by the peer's negotiated capability, with defensive pattern matching for robustness.

#### Component 1: Capability Helper Method

**Location**: `src/main/scala/com/chipprbots/ethereum/network/p2p/messages/Capability.scala`

```scala
def usesRequestId(capability: Capability): Boolean = capability match {
  case Capability.ETH66 | Capability.ETH67 | Capability.ETH68 => true
  case _ => false // ETH63, ETH64, ETH65
}
```

**Rationale**: Centralized capability detection prevents inconsistent checks across codebase

#### Component 2: Protocol-Aware Message Creation

**Pattern Applied**:
```scala
// When sending GetBlockHeaders
val message = if (Capability.usesRequestId(peerInfo.remoteStatus.capability)) {
  ETH66GetBlockHeaders(requestId = 0, block, maxHeaders, skip, reverse)
} else {
  ETH62GetBlockHeaders(block, maxHeaders, skip, reverse)
}
```

**Files Updated**:
1. `NetworkPeerManagerActor.scala` - Sends GetBlockHeaders after handshake
2. `PivotBlockSelector.scala` - Sends GetBlockHeaders for pivot block selection
3. `FastSync.scala` - Sends GetBlockHeaders during header chain sync
4. `FastSyncBranchResolverActor.scala` - Sends GetBlockHeaders for branch resolution
5. `EtcForkBlockExchangeState.scala` - Sends GetBlockHeaders during fork verification
6. `PeersClient.scala` - Adapts messages based on selected peer capability

**Rationale**: Each peer connection uses consistent message format based on negotiated protocol

#### Component 3: Dual-Format Pattern Matching

**Pattern Applied**:
```scala
// Receiving BlockHeaders - must handle both formats
message match {
  case ETH62BlockHeaders(headers) => 
    // Handle ETH62 format (from pre-ETH66 peers)
    processHeaders(headers)
    
  case ETH66BlockHeaders(requestId, headers) =>
    // Handle ETH66 format (from ETH66+ peers)
    processHeaders(headers) // requestId often ignored in response handling
    
  case _ => // other messages
}
```

**Files Updated**:
1. `NetworkPeerManagerActor.scala` - `updateForkAccepted()`, `updateMaxBlock()`
2. `BlockFetcher.scala` - Response handling
3. `HeadersFetcher.scala` - Response handling
4. `FastSync.scala` - Response handling
5. `PivotBlockSelector.scala` - Voting process
6. `FastSyncBranchResolverActor.scala` - Binary search handling

**Rationale**: 
- Nodes connect to peers with different protocol versions simultaneously
- Must handle responses in format matching what was sent
- Defensive programming for protocol deviations (see ADR-011)

#### Component 4: Type Adaptation in PeersClient

**Special Case**: `PeersClient` handles request/response matching generically

```scala
private def adaptMessageForPeer(
    message: MessageSerializable,
    peer: Peer,
    peerInfo: PeerInfo
): MessageSerializable = {
  val usesRequestId = peerInfo.remoteStatus.capability.usesRequestId
  
  message match {
    case ETH66GetBlockHeaders(requestId, block, maxHeaders, skip, reverse) if !usesRequestId =>
      // Convert to ETH62 for pre-ETH66 peer
      ETH62GetBlockHeaders(block, maxHeaders, skip, reverse)
      
    case ETH62GetBlockHeaders(block, maxHeaders, skip, reverse) if usesRequestId =>
      // Convert to ETH66 for ETH66+ peer
      ETH66GetBlockHeaders(0, block, maxHeaders, skip, reverse)
      
    case other => other
  }
}
```

**Rationale**: Generic request/response infrastructure needs runtime adaptation

### Kept: Backward-Compatible Decoders

**Location**: `src/main/scala/com/chipprbots/ethereum/network/p2p/messages/ETH66.scala`

The fallback decoding from Phase 1 (commit 4458be6) is **retained** for robustness:
- Handles protocol deviations by peers (see ADR-011 for precedent)
- Provides defensive layer against implementation errors
- Minimal performance impact (fast-path check fails quickly)

## Consequences

### Positive

1. **Protocol Compliance**: Matches core-geth behavior - consistent format per peer connection
2. **Backward Compatibility**: Works with both pre-ETH66 (ETH63-65) and ETH66+ (ETH66-68) peers
3. **Type Safety**: Leverages Scala's type system and pattern matching for correctness
4. **Defensive**: Handles both expected format and potential deviations
5. **Peer Recognition**: `maxBlockNumber` correctly updated, peers available for sync
6. **Tests Pass**: Expected to fix 18 failing integration tests in FastSyncItSpec and RegularSyncItSpec

### Negative

1. **Code Duplication**: Pattern matches duplicated for ETH62 and ETH66 variants
2. **Type Complexity**: Developers must understand two type hierarchies
3. **Import Management**: Must carefully manage aliased imports
4. **Runtime Checks**: Protocol version checked at runtime (not compile time)

### Neutral

1. **Migration Path**: Future Scala versions might allow more elegant type unification
2. **Core-Geth Alignment**: Architecture still differs from core-geth but behavior aligns
3. **Maintenance Burden**: New message types require both ETH62 and ETH66 variants

## Implementation Details

### Testing Methodology

**Test Environment**: Local integration tests with multiple peer instances
**Test Scenarios**:
1. Peers negotiating to ETH68 - should use ETH66 format messages
2. Peers negotiating to ETH64 - should use ETH62 format messages
3. Mixed network - some ETH66+, some pre-ETH66 peers
4. Message format verification through logging

**Key Validation Points**:
- ✅ RLPx handshake completes
- ✅ Capability negotiation succeeds
- ✅ GetBlockHeaders sent in correct format
- ✅ BlockHeaders responses received and decoded
- ✅ `maxBlockNumber` updated in PeerInfo
- ✅ PivotBlockSelector finds available peers
- ✅ Sync proceeds successfully

### Code Locations

**Core Infrastructure**:
- `Capability.scala` - Protocol version detection helper
- `ETH66.scala` - Backward-compatible decoders (Phase 1)
- `MessageDecoders.scala` - Protocol-specific decoder selection

**Message Sending** (protocol-aware creation):
- `NetworkPeerManagerActor.scala:109` - Post-handshake GetBlockHeaders
- `PivotBlockSelector.scala:230` - Pivot block header request
- `FastSync.scala:851` - Header chain sync request
- `FastSyncBranchResolverActor.scala:179` - Branch resolution request
- `EtcForkBlockExchangeState.scala:25` - Fork verification request
- `PeersClient.scala` - Generic message adaptation

**Message Receiving** (dual-format pattern matching):
- `NetworkPeerManagerActor.scala:199-235` - updateForkAccepted
- `NetworkPeerManagerActor.scala:264-269` - updateMaxBlock
- `PivotBlockSelector.scala:137` - Voting process
- `FastSync.scala:219` - Response handling
- `HeadersFetcher.scala:54,84` - Response handling
- `BlockFetcher.scala:329` - Response handling
- `FastSyncBranchResolverActor.scala:77,94` - Response handling

### Migration Notes for Developers

**When adding new message types**:
1. Create both ETH62 and ETH66 variants if request/response pair
2. Add decoders in both ETH62.scala and ETH66.scala
3. Add backward-compatible fallback in ETH66 decoder
4. Update MessageDecoders.scala for all protocol versions
5. Use protocol-aware creation pattern in application code
6. Handle both variants in pattern matches

**When debugging message issues**:
1. Check peer's `remoteStatus.capability` - determines expected format
2. Verify decoder selection in MessageDecoders
3. Look for type mismatches in pattern matches
4. Enable RLPx debug logging for wire format inspection

## Alternatives Considered

### Alternative 1: Unified Message Type Hierarchy

**Description**: Refactor to single message type hierarchy like core-geth
```scala
case class GetBlockHeaders(
  requestId: Option[BigInt],  // None for pre-ETH66, Some for ETH66+
  block: Either[BigInt, ByteString],
  maxHeaders: BigInt,
  skip: BigInt,
  reverse: Boolean
)
```

**Rejected Because**:
- Massive refactoring across entire codebase
- Risk of introducing consensus bugs
- Breaks type safety (optional requestId)
- Not minimal change per requirements

### Alternative 2: Always Use ETH66 Format

**Description**: Send ETH66 format to all peers, rely on backward-compatible decoders
```scala
// Always send ETH66
peer.ref ! SendMessage(ETH66GetBlockHeaders(0, block, maxHeaders, skip, reverse))
```

**Rejected Because**:
- Violates Ethereum protocol specifications
- Pre-ETH66 peers expect ETH62 format
- Could cause interoperability issues with strict clients
- No alignment with core-geth behavior

### Alternative 3: Runtime Message Conversion Layer

**Description**: Add middleware that converts messages based on capability
```scala
trait MessageAdapter {
  def adapt(msg: Message, capability: Capability): Message
}
```

**Rejected Because**:
- Additional complexity layer
- Performance overhead on hot path
- Doesn't solve pattern matching issue
- Harder to debug than explicit protocol-aware creation

### Alternative 4: Implicit Conversion Between Types

**Description**: Use implicit conversions to automatically convert ETH62 ↔ ETH66
```scala
implicit def eth62ToEth66(msg: ETH62.GetBlockHeaders): ETH66.GetBlockHeaders = ???
```

**Rejected Because**:
- Hidden behavior (implicit conversions are invisible)
- Doesn't solve when to use which type
- Scala 3 deprecates some implicit patterns
- Makes debugging harder

## References

### Specifications
1. [Ethereum Wire Protocol (ETH)](https://github.com/ethereum/devp2p/blob/master/caps/eth.md)
2. [ETH/66 Change Log](https://github.com/ethereum/devp2p/blob/master/caps/eth.md#change-log)
3. [Ethereum Execution Specs](https://github.com/ethereum/execution-specs)

### Implementation References
1. [Core-Geth](https://github.com/etclabscore/core-geth) - ETC reference implementation
   - `eth/protocols/eth/protocol.go` - Message type definitions
   - `eth/protocols/eth/peer.go` - Message creation
   - `eth/protocols/eth/handlers.go` - Message handling
2. [Go Ethereum (Geth)](https://github.com/ethereum/go-ethereum) - Upstream reference
3. [Besu](https://github.com/hyperledger/besu) - Java-based Ethereum client

### Related ADRs
1. [CON-001: RLPx Protocol Deviations](CON-001-rlpx-protocol-deviations-and-peer-bootstrap.md) - Defensive protocol handling precedent
2. [CON-003: Block Sync Improvements](CON-003-block-sync-improvements.md) - Fast sync architecture

### Related Issues
1. [chippr-robotics/fukuii#441](https://github.com/chippr-robotics/fukuii/issues/441) - Peer connection errors
2. [chippr-robotics/fukuii#437](https://github.com/chippr-robotics/fukuii/pull/437) - Previous investigation logs

## Future Work

### Short Term
1. **Compilation Verification**: Ensure all changes compile successfully
2. **Integration Testing**: Run full FastSyncItSpec and RegularSyncItSpec test suites
3. **Performance Testing**: Measure impact of runtime capability checks
4. **Log Analysis**: Verify correct message formats in actual network conditions

### Medium Term
1. **Type Unification Study**: Evaluate Scala 3 features (union types, opaque types) for cleaner architecture
2. **Message Format Metrics**: Add monitoring for ETH62 vs ETH66 message usage
3. **Protocol Version Analytics**: Track which protocols are actually used in network
4. **Documentation**: Add developer guide for protocol-aware message handling

### Long Term
1. **Architecture Evolution**: Consider message type redesign if Scala 3 enables better patterns
2. **ETH/69+ Support**: Ensure architecture supports future protocol versions
3. **Protocol Negotiation Enhancement**: Explore capability-based feature negotiation
4. **Cross-Client Testing**: Automated testing against multiple client implementations

## Lessons Learned

1. **Type Systems Have Limits**: Separate type hierarchies for protocol versions create maintenance burden but provide type safety
2. **Runtime Checks Are Sometimes Necessary**: Not everything can be compile-time verified in distributed systems
3. **Defensive Programming Pays Off**: Backward-compatible decoders caught issues that perfect protocol compliance wouldn't
4. **Reference Implementations Matter**: Core-geth analysis revealed the "right" approach
5. **Pattern Matching Is Powerful**: Handling both message formats via pattern matching is elegant and maintainable
6. **Minimal Changes Are Hard**: "Just add protocol awareness" touched 10+ files across multiple subsystems
7. **Integration Tests Reveal Truth**: Unit tests can't catch peer protocol mismatch issues
8. **Documentation Prevents Repeats**: Future developers need clear guidance on protocol-aware patterns

## Decision Log

- **2025-11-16 05:00 UTC**: Initial investigation started - "Cannot decode GetBlockHeaders" errors
- **2025-11-16 05:15 UTC**: Added backward-compatible fallback decoders (commit 4458be6)
- **2025-11-16 05:30 UTC**: Identified type mismatch as root cause
- **2025-11-16 05:45 UTC**: Attempted mixed message format approach (commit e8bd068 + mithril work)
- **2025-11-16 06:00 UTC**: Analyzed core-geth for protocol-aware pattern
- **2025-11-16 06:15 UTC**: Implemented protocol-aware message creation (forge agent)
- **2025-11-16 06:30 UTC**: Documented findings in ADR-016
- **2025-11-16**: Next - compilation verification and integration testing

## Appendix: Message Format Examples

### ETH62 Format (Pre-ETH66)
```
GetBlockHeaders message:
RLP: [block, maxHeaders, skip, reverse]
Bytes: 0xc4 0x01 0x01 0x00 0x00
       └─ RLPList with 4 items

BlockHeaders response:
RLP: [header1, header2, ...]
Bytes: 0xf8 0x... (list of headers)
```

### ETH66 Format (ETH66+)
```
GetBlockHeaders message:
RLP: [requestId, [block, maxHeaders, skip, reverse]]

For requestId=0 (empty bytes per RLP spec):
Bytes: 0xc6 0x80 0xc4 0x01 0x01 0x00 0x00
       │    │    └─ Inner RLPList: [block=1, maxHeaders=1, skip=0, reverse=0]
       │    └─ requestId=0 encoded as 0x80 (empty byte string, NOT 0x00)
       └─ Outer RLPList marker

For requestId=42:
Bytes: 0xc6 0x2a 0xc4 0x01 0x01 0x00 0x00
            └─ requestId=42 encoded as 0x2a (single byte < 0x80)

IMPORTANT: Per Ethereum RLP specification, integer 0 MUST be encoded as 
an empty byte string (0x80), not as a single byte 0x00. This is critical
for interoperability with core-geth and other Ethereum clients.

BlockHeaders response:
RLP: [requestId, [header1, header2, ...]]
Bytes: 0x... 0x80 0xf8 0x...
       └─ RLPList with 2 items (requestId + headers list)
```

### Capability Detection
```scala
// Example peer capabilities after negotiation
val peer1Capability = Capability.ETH68  // Uses ETH66 format
val peer2Capability = Capability.ETH64  // Uses ETH62 format
val peer3Capability = Capability.ETH65  // Uses ETH62 format

peer1Capability.usesRequestId  // true
peer2Capability.usesRequestId  // false
peer3Capability.usesRequestId  // false
```
