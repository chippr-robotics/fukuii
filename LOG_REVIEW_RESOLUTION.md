# Log Review Resolution - Core-Geth Connection Issue

## Date: 2025-11-23

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

## Expected Behavior After Fix

- ✅ Successful connections to core-geth nodes maintained
- ✅ No disconnections on `NewPooledTransactionHashes` messages
- ✅ Proper transaction pool announcements received
- ✅ Stable peer count
- ✅ Successful block sync from core-geth peers
