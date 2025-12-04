# CON-007: ETC64 RLP Encoding Fix for Peer Compatibility

## Status
Accepted

## Context

### Problem
Issue #707 reported that fukuii nodes could not connect to core-geth peers, receiving "malformed signature" errors. Investigation revealed that the ETC64 Status and NewBlock message encodings were not using proper RLP integer encoding, potentially causing incompatibility with core-geth and violating the RLP specification.

### RLP Specification Requirements
The RLP (Recursive Length Prefix) specification requires that integers be encoded:
1. **Without leading zeros** - Minimal representation only
2. **Using unsigned encoding** - Not two's complement

### The Issue with Two's Complement
Scala/Java's `BigInt.toByteArray` uses two's complement representation, which adds a leading 0x00 byte when the high bit is set:
- Value 128 (0x80) → `[0x00, 0x80]` (2 bytes) ❌ WRONG
- Value 128 (0x80) → `[0x80]` (1 byte) ✅ CORRECT

This violates RLP's requirement for minimal encoding and can cause peer rejection.

### Pattern Inconsistency
Analysis of the codebase revealed:
- **ETH64.Status**: Uses explicit `ByteUtils.bigIntToUnsignedByteArray` wrapping ✅
- **BaseETH6XMessages.Status**: Uses explicit `ByteUtils.bigIntToUnsignedByteArray` wrapping ✅
- **ETC64.Status**: Relied on implicit conversions ❌ **OUTLIER**
- **ETC64.NewBlock**: Relied on implicit conversions ❌ **OUTLIER**

## Decision

### Changes Applied
Modified ETC64.Status and ETC64.NewBlock encodings to use explicit `ByteUtils.bigIntToUnsignedByteArray` wrapping for all integer fields, matching the established pattern in ETH64 and BaseETH6XMessages.

#### ETC64.Status Encoding
**Before:**
```scala
RLPList(
  protocolVersion,                    // Int - implicit conversion
  networkId,                          // Int - implicit conversion
  chainWeight.totalDifficulty,        // BigInt - implicit conversion
  chainWeight.lastCheckpointNumber,   // BigInt - implicit conversion
  RLPValue(bestHash.toArray[Byte]),
  RLPValue(genesisHash.toArray[Byte])
)
```

**After:**
```scala
RLPList(
  RLPValue(ByteUtils.bigIntToUnsignedByteArray(BigInt(protocolVersion))),
  RLPValue(ByteUtils.bigIntToUnsignedByteArray(BigInt(networkId))),
  RLPValue(ByteUtils.bigIntToUnsignedByteArray(chainWeight.totalDifficulty)),
  RLPValue(ByteUtils.bigIntToUnsignedByteArray(chainWeight.lastCheckpointNumber)),
  RLPValue(bestHash.toArray[Byte]),
  RLPValue(genesisHash.toArray[Byte])
)
```

#### ETC64.NewBlock Encoding
**Before:**
```scala
RLPList(
  RLPList(...),
  chainWeight.totalDifficulty,        // Implicit conversion
  chainWeight.lastCheckpointNumber    // Implicit conversion
)
```

**After:**
```scala
RLPList(
  RLPList(...),
  RLPValue(ByteUtils.bigIntToUnsignedByteArray(chainWeight.totalDifficulty)),
  RLPValue(ByteUtils.bigIntToUnsignedByteArray(chainWeight.lastCheckpointNumber))
)
```

### Test Coverage Enhancement
Added test case for ETC64.Status with values >= 128 to verify proper handling of two's complement edge cases:
```scala
"handle values >= 128 correctly (two's complement edge case)" in {
  val msg = ETC64.Status(
    protocolVersion = 128,  // Tests high bit in single byte
    networkId = 256,        // Tests value requiring 2 bytes
    chainWeight = ChainWeight(
      lastCheckpointNumber = BigInt("9000000000000000", 16),
      totalDifficulty = BigInt("8000000000000000", 16)
    ),
    bestHash = ByteString("HASH"),
    genesisHash = ByteString("HASH2")
  )
  verify(msg, (m: ETC64.Status) => m.toBytes, Codes.StatusCode, Capability.ETC64)
}
```

## Consequences

### Benefits
1. **Peer Compatibility**: Fixes "malformed signature" errors preventing connections to core-geth
2. **RLP Compliance**: Ensures wire protocol messages meet RLP specification
3. **Consistency**: Aligns ETC64 encoding with established patterns in ETH64 and BaseETH6XMessages
4. **Explicit > Implicit**: Wire protocol encoding is now explicit and deterministic
5. **Test Coverage**: Edge cases with high-bit values are now tested

### Risks Mitigated
1. **Consensus-Critical**: Wire protocol messages must be byte-perfect for peer communication
2. **Scala 3 Migration**: Implicit resolution changes between Scala 2 and Scala 3 could cause subtle issues
3. **Integer Edge Cases**: Values >= 128 with high bit set are now correctly encoded

### Validation Required
- [x] Unit tests pass (encode/decode round-trip)
- [x] Edge case tests added for values >= 128
- [ ] Integration testing with core-geth peers
- [ ] Verify actual peer connections succeed

## Implementation Details

### Files Modified
- `src/main/scala/com/chipprbots/ethereum/network/p2p/messages/ETC64.scala`
  - Status.toRLPEncodable: Added explicit ByteUtils wrapping with explanatory comments
  - NewBlock.toRLPEncodable: Added explicit ByteUtils wrapping with explanatory comments
- `src/test/scala/com/chipprbots/ethereum/network/p2p/messages/MessagesSerializationSpec.scala`
  - Added ETC64.Status test for values >= 128

### Danger Level
🔥🔥🔥 **Consensus-critical** (wire protocol compliance)

### Related
- Issue: #707 - Peer connection failures
- Related ADR: CON-001 (RLPx protocol deviations)
- Related ADR: CON-005 (ETH66+ protocol-aware message formatting)

## References
- [RLP Specification](https://ethereum.org/en/developers/docs/data-structures-and-encoding/rlp/)
- [Core-Geth ETC64 Implementation](https://github.com/etclabscore/core-geth)
- [ETH64 Protocol](https://github.com/ethereum/devp2p/blob/master/caps/eth.md)
- Issue #707: Capability list change causing malformed signature errors

## Date
2025-12-04

## Author
FORGE (Ethereum Classic Consensus Migration Agent)
