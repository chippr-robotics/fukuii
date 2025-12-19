# Network Protocol Compatibility Guide

This document provides technical reference for network protocol compatibility. All issues documented here have been resolved.

## Protocol Compatibility Summary

| Protocol | Status | Notes |
|----------|--------|-------|
| ETH63 | ✅ Supported | Legacy support |
| ETH64/65 | ✅ Supported | Full compatibility |
| ETH66 | ✅ Supported | Request-id wrapped messages |
| ETH67 | ✅ Supported | NewPooledTransactionHashes v2 |
| ETH68 | ✅ Supported | Current production version |

---

## ETH66+ Message Adaptation

### Overview

ETH66 and later protocols wrap requests with a request-id for request/response matching. The message adaptation layer automatically handles this.

### How It Works

The `PeersClient.adaptMessageForPeer` method adapts messages based on negotiated capabilities:

```scala
private def adaptMessageForPeer[RequestMsg <: Message](peer: Peer, message: RequestMsg): Message =
  handshakedPeers.get(peer.id) match {
    case Some(peerWithInfo) =>
      val usesRequestId = Capability.usesRequestId(peerWithInfo.peerInfo.remoteStatus.capability)
      message match {
        // GetBlockHeaders adaptation
        case eth66: ETH66GetBlockHeaders if !usesRequestId =>
          ETH62.GetBlockHeaders(eth66.block, eth66.maxHeaders, eth66.skip, eth66.reverse)
        case eth62: ETH62.GetBlockHeaders if usesRequestId =>
          ETH66GetBlockHeaders(ETH66.nextRequestId, eth62.block, eth62.maxHeaders, eth62.skip, eth62.reverse)
        // GetBlockBodies adaptation
        case eth66: ETH66GetBlockBodies if !usesRequestId =>
          ETH62.GetBlockBodies(eth66.hashes)
        case eth62: ETH62.GetBlockBodies if usesRequestId =>
          ETH66GetBlockBodies(ETH66.nextRequestId, eth62.hashes)
        // GetReceipts adaptation
        case eth66: ETH66GetReceipts if !usesRequestId =>
          ETH63.GetReceipts(eth66.blockHashes)
        case eth63: ETH63.GetReceipts if usesRequestId =>
          ETH66GetReceipts(ETH66.nextRequestId, eth63.blockHashes)
        case _ => message
      }
```

### Verification

1. **Check message format in logs**:
   ```bash
   ./bin/fukuii etc 2>&1 | grep "ENCODE_MSG"
   ```
   GetReceipts to ETH66+ peer should show format: `f8...<requestId><[hashes]>`

2. **Monitor successful responses**:
   ```bash
   ./bin/fukuii etc 2>&1 | grep -E "Received.*block bodies|Received.*receipts"
   ```
   Should see "(ETH66)" suffix for responses from ETH66+ peers

---

## ForkId Compatibility

### Overview

ForkId validation ensures peers are on compatible chains. Nodes starting from block 0 now use bootstrap pivot for ForkId calculation.

### Implementation

```scala
val forkIdBlockNumber = if (bootstrapPivotBlock > 0) {
  val threshold = math.min(bootstrapPivotBlock / 10, BigInt(100000))
  val shouldUseBootstrap = bestBlockNumber < (bootstrapPivotBlock - threshold)
  if (shouldUseBootstrap) bootstrapPivotBlock else bestBlockNumber
} else bestBlockNumber
```

### Verification

1. **Check ForkId at Startup**:
   ```bash
   ./bin/fukuii etc 2>&1 | grep "Sending status"
   ```
   At block 0, should show: `forkId=ForkId(0xbe46d57c, None)` for ETC mainnet

2. **Monitor Peer Connections**:
   ```bash
   ./bin/fukuii etc 2>&1 | grep -E "PEER_HANDSHAKE_SUCCESS|DISCONNECT_DEBUG"
   ```
   Should see sustained peer connections without immediate 0x10 disconnects

---

## Snappy Compression

### Overview

All ETH protocol messages use Snappy compression (p2pVersion >= 5). The implementation correctly handles both compressed and uncompressed data.

### Logic

```scala
// Always attempt decompression first
Try(Snappy.uncompress(data)) match {
  case Success(decompressed) => decompressed
  case Failure(_) if looksLikeRLP(data) => data  // Fallback for uncompressed
  case Failure(ex) => throw ex
}
```

---

## ETH67 Transaction Announcements

### Overview

ETH67 introduced a new format for `NewPooledTransactionHashes` with types and sizes arrays.

### Implementation

Types are encoded as a byte string (matching Go's `[]byte`):
```scala
RLPValue(types.toArray)  // Not RLPList
```

---

## Key Files

| File | Purpose |
|------|---------|
| `PeersClient.scala` | Message adaptation |
| `FastSync.scala` | Sync request handling |
| `EthNodeStatus64ExchangeState.scala` | ForkId calculation |
| `MessageCodec.scala` | Snappy compression |
| `ETH67.scala` | Transaction announcement encoding |

---

## Related Documentation

- [Block Sync Guide](BLOCK_SYNC_TROUBLESHOOTING.md)
- [SNAP Sync State Storage Review](../architecture/SNAP_SYNC_STATE_STORAGE_REVIEW.md)
- [Known Issues](../runbooks/known-issues.md)
- [devp2p Specification](https://github.com/ethereum/devp2p)

---

## UPDATE 2025-12-02: SNAP Sync State Storage Integration Review

### Issue Reviewed
Expert review of SNAP sync state storage integration implementation by forge agent. Reviewed 5 critical open questions regarding state root verification, storage root handling, trie initialization, thread safety, and memory management.

### Review Findings

**Critical Issues Identified:**
1. **State Root Mismatch Handling** - Currently logs error and continues, should block sync and trigger healing
2. **Thread Safety** - Incorrect synchronization lock (`mptStorage` instead of `this`), potential data corruption

**High Priority Issues:**
3. **Storage Root Verification** - Should queue accounts for healing on mismatch
4. **Trie Initialization** - No exception handling for missing root nodes

**Medium Priority:**
5. **Memory Usage** - Unbounded storage trie map can cause OOM on mainnet (10M+ contracts)

### Recommendations

**Phase 1 - Critical (P0):**
- Fix thread safety: Change `mptStorage.synchronized` to `this.synchronized`
- Fix state root verification: Block sync on mismatch, trigger healing, retry if needed

**Phase 2 - High Priority (P1):**
- Queue accounts with storage root mismatches for healing
- Add exception handling for `MissingRootNodeException` in trie initialization

**Phase 3 - Performance (P2):**
- Implement LRU cache for storage tries (max 10K entries) to prevent OOM

### Implementation Guide

**Detailed review document created:**
`docs/architecture/SNAP_SYNC_STATE_STORAGE_REVIEW.md`

Contains:
- Complete code examples for all 5 fixes
- Rationale based on SNAP protocol spec and core-geth patterns
- Testing recommendations for each fix
- Memory usage analysis and cache design
- Implementation roadmap (~1 week effort)

### Impact

**Security & Correctness:**
- ✅ Prevents accepting corrupted or malicious state (state root verification)
- ✅ Prevents data corruption from concurrent updates (thread safety)
- ✅ Enables proper healing of incomplete storage tries (storage root verification)

**Robustness:**
- ✅ Enables clean resume after storage clear (exception handling)
- ✅ Prevents OOM during mainnet sync (LRU cache)

**Protocol Compliance:**
- ✅ Matches core-geth SNAP sync behavior
- ✅ Follows SNAP protocol specification requirements
- ✅ Ensures network safety and peer interoperability

### Files Referenced
- `src/main/scala/com/chipprbots/ethereum/blockchain/sync/snap/AccountRangeDownloader.scala`
- `src/main/scala/com/chipprbots/ethereum/blockchain/sync/snap/StorageRangeDownloader.scala`
- `src/main/scala/com/chipprbots/ethereum/blockchain/sync/snap/SNAPSyncController.scala`
- `src/main/scala/com/chipprbots/ethereum/db/storage/MptStorage.scala`
- `src/main/scala/com/chipprbots/ethereum/mpt/MerklePatriciaTrie.scala`

### Next Steps
1. Implement Phase 1 critical fixes immediately
2. Add comprehensive test coverage
3. Schedule Phases 2 and 3 before mainnet deployment
4. Test against core-geth peers for interoperability

---

## UPDATE 2025-12-19: Unknown Message Type Handling

### Issue Discovered
Nightly build logs showed "Unknown snap/1 message type: 29 – DECODE_ERROR" causing peer connections to close. Message code 29 (0x1D) is outside valid protocol ranges:
- Wire protocol: 0x00-0x0f (0-15)
- ETH protocol: 0x10-0x20 (16-32)  
- SNAP protocol: 0x21-0x28 (33-40)

Message 29 is in the gap, suggesting malformed/non-standard message from peer.

### Root Cause
When `UnknownMessageTypeError` was encountered, `processMessage()` in RLPxConnectionHandler closed the connection immediately (line 417-424). This was too aggressive - unknown message types should be tolerated to maintain peer diversity.

### The Fix

**Before:**
```scala
case Left(ex) =>
  val isDecompressionFailure = MessageDecoder.isDecompressionFailure(ex)
  
  if (isDecompressionFailure) {
    // Log warning, skip message, keep connection
  } else {
    // Close connection for ANY other decoding error
    connection ! Close
  }
```

**After:**
```scala
case Left(ex) =>
  val isDecompressionFailure = MessageDecoder.isDecompressionFailure(ex)
  val isUnknownMessageType = ex.isInstanceOf[UnknownMessageTypeError]
  
  if (isDecompressionFailure) {
    // Log warning, skip message, keep connection
  } else if (isUnknownMessageType) {
    // Log warning with message code details, skip message, keep connection
    val msgCode = ex.asInstanceOf[UnknownMessageTypeError].messageType
    log.warning("Peer {} sent unknown message type 0x{} ({})", peerId, msgCode.toHexString, msgCode)
  } else {
    // Close connection only for truly malformed RLP
    connection ! Close
  }
```

### Impact
- ✅ Maintains connections with peers implementing protocol extensions
- ✅ Increases network resilience against diverse peer implementations
- ✅ Prevents premature disconnection from future protocol versions
- ✅ Logs detailed message code information for debugging
- ✅ Maintains strict handling of truly malformed RLP data

### Behavior Changes
- **Unknown message types**: Log warning, skip message, keep connection alive
- **Decompression failures**: Log warning, skip message, keep connection alive (existing)
- **Malformed RLP/structure**: Log error, close connection (existing)

### Files Modified
- `src/main/scala/com/chipprbots/ethereum/network/rlpx/RLPxConnectionHandler.scala` - Enhanced `processMessage()` error handling

### Testing
After deployment, monitor logs for:
```bash
# Should see warnings instead of connection closures
grep "unknown message type" /var/log/fukuii/fukuii.log

# Verify peer count remains stable
grep "PEER_HANDSHAKE_SUCCESS" /var/log/fukuii/fukuii.log | wc -l
```

### Related Issues
- Fixes nightly build "snap/1 message type 29" errors
- Aligns with Herald agent philosophy: robust protocol deviation handling
- Complements existing decompression failure tolerance

---
