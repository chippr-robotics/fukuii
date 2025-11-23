# ETH67 NewPooledTransactionHashes Decode Failure - Diagnostic Enhancement

## Problem Summary

**Issue**: Core-geth nodes immediately disconnect after successful handshake when sending `NewPooledTransactionHashes` (0x18) messages.

**Error**: `java.lang.ArrayIndexOutOfBoundsException` during message decode

**Impact**: All peers get blacklisted → 0 handshaked peers available → blockchain sync cannot proceed

## Root Cause Analysis

From log review (2025-11-22 21:07:52):
1. ✅ RLPx auth handshake succeeds
2. ✅ ETH68 protocol negotiation succeeds  
3. ✅ Status exchange completes
4. ✅ Fork ID validation passes (Right(Connect))
5. ❌ Peer sends NewPooledTransactionHashes (0x18)
6. ❌ ArrayIndexOutOfBoundsException thrown during decode
7. ❌ Peer blacklisted for 360 seconds
8. Result: No available peers for sync

## Validation Against Core-Geth Reference

Verified our implementation matches core-geth source (`eth/protocols/eth/protocol.go`):

```go
// Core-Geth (Reference Implementation)
type NewPooledTransactionHashesPacket struct {
    Types  []byte        // RLP: single byte string (RLPValue)
    Sizes  []uint32      // RLP: list of integers (RLPList)
    Hashes []common.Hash // RLP: list of 32-byte values (RLPList)
}
```

```scala
// Our Implementation (ETH67.scala)
case RLPList(RLPValue(typesBytes), sizesList: RLPList, hashesList: RLPList) =>
  NewPooledTransactionHashes(
    types = typesBytes.toSeq,      // ✅ Matches
    sizes = fromRlpList[BigInt](sizesList),  // ✅ Matches  
    hashes = fromRlpList[ByteString](hashesList) // ✅ Matches
  )
```

**Conclusion**: Structure matches the reference implementation.

## Diagnostic Enhancements Implemented

### 1. Custom Exception Type
Created `ETH67DecodeException` with:
- "ETH67_DECODE_ERROR" prefix for easy log filtering
- Separate constructors for with/without cause
- Object-scope definition (avoids recreation overhead)

### 2. Enhanced Error Messages
All exceptions now include:
- **RLP Structure**: e.g., "RLPList[3]: [RLPValue(10b), RLPList(10), RLPList(10)]"
- **Exception Type**: e.g., "ArrayIndexOutOfBoundsException"
- **Array Sizes**: e.g., "Types=10, Sizes=10, Hashes=10"
- **Hex Dump**: First 100 bytes of payload (only appends '...' if truncated)
- **Decode Path**: Which format matched (ETH67/68 vs ETH65 legacy)

### 3. Code Quality Improvements
- Extracted `hexDump` helper function (eliminates duplication)
- Created `HexDumpMaxBytes` constant (improves maintainability)
- Optimized pattern matching (avoids varargs overhead)
- Comprehensive exception handling at all decode paths

## Example Enhanced Error Output

**Before**:
```
ERROR [c.c.e.n.rlpx.RLPxConnectionHandler] - Cannot decode message from 164.90.144.106:30303, 
because of java.lang.ArrayIndexOutOfBoundsException
```

**After** (expected):
```
ERROR [c.c.e.n.rlpx.RLPxConnectionHandler] - Cannot decode message from 164.90.144.106:30303, 
because of ETH67_DECODE_ERROR: ArrayIndexOutOfBoundsException in ETH67/68 format. 
Structure: RLPList[3]: [RLPValue(10b), RLPList(10), RLPList(10)]. 
Types=10, Sizes=10, Hashes=10. 
Hex: f856830102030a8400000fa08400001f4083...
```

## Next Steps

1. **Deploy**: Run node with enhanced diagnostics
2. **Capture**: Collect error messages with ETH67_DECODE_ERROR prefix
3. **Analyze**: Identify exact failure point from structure and hex data
4. **Fix**: Implement solution based on diagnostic output
5. **Verify**: Confirm successful connection to core-geth nodes

## Files Modified

- `src/main/scala/com/chipprbots/ethereum/network/p2p/messages/ETH67.scala`
  - Added ETH67DecodeException class
  - Added HexDumpMaxBytes constant  
  - Enhanced decoder with comprehensive error handling
  - Optimized pattern matching
  - Improved code quality per review feedback

## Commits

1. `cd357f6` - Add enhanced error handling to ETH67 NewPooledTransactionHashes decoder
2. `dd83406` - Add detailed debug logging to ETH67 decoder to diagnose RLP structure
3. `515c026` - Improve ETH67 error messages to be more compact and informative
4. `5c9fa14` - Address code review: extract hex dump helper and use custom exception type
5. `e529f5f` - Address code review: move exception class outside method and use named constant
6. `52edad5` - Final code review fixes: improve hexDump and exception handling

## Testing

- ✅ All code review feedback addressed
- ✅ CodeQL security check passed (no issues)
- ⏳ Awaiting deployment for live testing with core-geth peers

## References

- Core-geth repository: https://github.com/etclabscore/core-geth
- Core-geth protocol definition: `eth/protocols/eth/protocol.go`
- ETH protocol spec: https://github.com/ethereum/devp2p/blob/master/caps/eth.md
- Issue: chippr-robotics/fukuii#555
- PR: chippr-robotics/fukuii (copilot/review-core-geth-logs branch)
