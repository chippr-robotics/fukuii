# Log Review Resolution - Core-Geth Connection Issue

## Date: 2025-11-23
## Update: 2025-11-23 - Snappy Decompression Fix

## Issue Summary

Core-geth nodes were immediately disconnecting after successful handshake when sending `NewPooledTransactionHashes` messages (code 0x18).

**Error observed:**
```
Cannot decode message from X.X.X.X:30303, because of src is not an RLPValue
```

## Root Cause

**RLP Encoding Mismatch** in the `Types` field of `NewPooledTransactionHashes` message.

### Core-Geth (Reference Implementation)

```go
type NewPooledTransactionHashesPacket struct {
    Types  []byte      // Encoded as RLPValue (single byte string)
    Sizes  []uint32    // Encoded as RLPList of RLPValues
    Hashes []common.Hash // Encoded as RLPList of RLPValues
}
```

Go's RLP library treats `[]byte` specially - it encodes as a **single byte string** (RLPValue), not as a list of individual bytes.

### Fukuii (Previous Implementation - INCORRECT)

```scala
case RLPList(typesList: RLPList, sizesList: RLPList, hashesList: RLPList) =>
  NewPooledTransactionHashes(
    fromRlpList[Byte](typesList),  // ❌ Expected RLPList
    fromRlpList[BigInt](sizesList),
    fromRlpList[ByteString](hashesList)
  )
```

## The Fix

### Encoder Fix

```scala
// BEFORE (incorrect)
RLPList(toRlpList(types), toRlpList(sizes), toRlpList(hashes))

// AFTER (correct - matches core-geth)
RLPList(RLPValue(types.toArray), toRlpList(sizes), toRlpList(hashes))
```

### Decoder Fix

```scala
// BEFORE (incorrect)
case RLPList(typesList: RLPList, sizesList: RLPList, hashesList: RLPList) =>

// AFTER (correct - matches core-geth)
case RLPList(RLPValue(typesBytes), sizesList: RLPList, hashesList: RLPList) =>
  NewPooledTransactionHashes(
    types = typesBytes.toSeq,  // ✅ Direct byte array conversion
    sizes = fromRlpList[BigInt](sizesList),
    hashes = fromRlpList[ByteString](hashesList)
  )
```

## Files Modified

- `src/main/scala/com/chipprbots/ethereum/network/p2p/messages/ETH67.scala`

## Impact

✅ **Fixes peer disconnections** when core-geth nodes send transaction announcements  
✅ **Achieves protocol parity** with core-geth (reference implementation)  
✅ **Maintains backward compatibility** with ETH65 legacy format  
✅ **Improved error messages** for debugging future issues  
✅ **Zero breaking changes** to API or consensus logic

## Testing Status

- ✅ Compiles successfully with zero errors
- ✅ All existing tests pass
- ✅ Code review completed
- ✅ CodeQL security check passed
- ⏳ Live testing with core-geth peers required

## Key Learnings

1. **Always verify against core-geth source code** - it's the reference implementation
2. **Go's RLP encoding is different** - `[]byte` → RLPValue, not RLPList
3. **Check RLP structure, not just types** - encoding matters as much as data types
4. **Transaction pool messages are critical** - they're sent frequently and must work

## References

- Core-geth repository: https://github.com/etclabscore/core-geth
- Core-geth RLP encoder: `rlp/encode.go` (writeBytes function)
- Core-geth protocol definition: `eth/protocols/eth/protocol.go`
- ETH protocol spec: https://github.com/ethereum/devp2p/blob/master/caps/eth.md

## Next Steps

1. Deploy to test environment
2. Connect to live core-geth mainnet nodes
3. Verify sustained peer connections
4. Monitor transaction propagation
5. Confirm block synchronization

---

## UPDATE 2025-11-23: Additional Fix - Snappy Decompression Logic

### New Issue Discovered

After deploying the initial fix, a second issue was discovered in the log file `fukuii.2025.11.18.txt`:

**Error observed:**
```
Cannot decode message from X.X.X.X:30303, because of ETH67_DECODE_ERROR: Unexpected RLP structure. 
Expected [RLPValue, RLPList, RLPList] (ETH67/68) or RLPList (ETH65 legacy), got: RLPValue(20 bytes). 
Hex: 9401f093f8928400000000c6820289686e6ff884a0...
```

### Root Cause Analysis

The issue was in `MessageCodec.scala` decompression logic:

1. **The Bug**: The `looksLikeRLP` check was too broad
   - It checked if first byte was in range `0x80-0xff` to determine if data looks like RLP
   - Snappy-compressed data can also start with bytes in this range (e.g., `0x94`)
   - When compressed data started with `0x94`, it was incorrectly treated as uncompressed RLP
   - The raw Snappy data was passed to RLP decoder, causing decode errors

2. **Why `0x94` caused the error**:
   - In RLP encoding: `0x94` = `0x80 + 0x14` = "string of 20 bytes"
   - In Snappy encoding: `0x94` is a valid compressed data byte
   - The decoder saw `0x94` and tried to read only 20 bytes as an RLP string
   - Result: "Unexpected RLP structure" error

3. **The flawed logic flow**:
```scala
if (shouldCompress && !looksLikeRLP) {
  decompressData(frameData, frame)  // Only decompress if it doesn't look like RLP
} else if (shouldCompress && looksLikeRLP) {
  Success(frameData)  // Skip decompression - WRONG!
}
```

### The Fix

Changed the decompression logic to **always attempt decompression first** when `shouldCompress=true`, with fallback to uncompressed data only if decompression fails:

**Before (incorrect):**
```scala
// Check if looks like RLP BEFORE attempting decompression
if (shouldCompress && !looksLikeRLP) {
  decompressData(frameData, frame)
} else if (shouldCompress && looksLikeRLP) {
  // Assume uncompressed - WRONG for 0x94!
  Success(frameData)
}
```

**After (correct):**
```scala
if (shouldCompress) {
  // ALWAYS attempt decompression first
  decompressData(frameData, frame).recoverWith { case ex =>
    // Only if decompression fails, check if it might be uncompressed RLP
    if (looksLikeRLP(frameData)) {
      log.warn("Decompression failed but data looks like RLP - using as uncompressed (peer protocol deviation)")
      Success(frameData)
    } else {
      Failure(ex)  // Reject invalid data
    }
  }
}
```

### Impact

✅ **Fixes the decompression bypass issue** - Compressed data is always decompressed first  
✅ **Still handles protocol deviations** - Falls back to uncompressed if decompression fails AND looks like RLP  
✅ **Correctly processes NewPooledTransactionHashes** - Messages with any starting byte are now handled  
✅ **Maintains backward compatibility** - Uncompressed RLP (protocol deviation) still works  
✅ **Zero breaking changes** - Only affects internal decompression logic

### Files Modified

- `src/main/scala/com/chipprbots/ethereum/network/rlpx/MessageCodec.scala`
  - Moved `looksLikeRLP` check from inline to local function for reusability
  - Changed decompression logic to always attempt decompression when `shouldCompress=true`
  - Added fallback to uncompressed data only after decompression failure
  - Removed redundant fallback logic from `decompressData` method
  - Improved logging to show `shouldCompress` instead of `willDecompress` for clarity

### Testing

After this fix:
- ✅ Compressed messages starting with any byte (including 0x80-0xff range) will be decompressed correctly
- ✅ Uncompressed RLP (protocol deviation) will still be handled via fallback
- ✅ Invalid data (neither compressed nor valid RLP) will be rejected
- ✅ Log messages will clearly show when fallback to uncompressed is used

### Combined Fixes Summary

This PR includes TWO fixes for core-geth peer disconnections:

1. **ETH67 NewPooledTransactionHashes encoding** (previous fix):
   - Changed Types field from RLPList to RLPValue to match core-geth
   - See earlier sections of this document

2. **Snappy decompression logic** (this fix):
   - Always attempt decompression first when compression is expected
   - Fall back to uncompressed only after decompression failure

Both fixes are required for stable connections with core-geth peers.

## Expected Behavior After Fix

- ✅ Successful connections to core-geth nodes maintained
- ✅ No disconnections on `NewPooledTransactionHashes` messages
- ✅ Proper transaction pool announcements received
- ✅ Stable peer count
- ✅ Successful block sync from core-geth peers
