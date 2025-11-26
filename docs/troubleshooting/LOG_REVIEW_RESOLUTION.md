# Log Review Resolution Guide

This document provides technical analysis and resolutions for network-related issues discovered through log review.

## UPDATE 2025-11-26: TCP Disconnect During Block Sync (Issue #578)

### Issue Discovered
Peers disconnect with reason code 0x10 ("Some other reason specific to a subprotocol") after the initial handshake once block exchange begins. The disconnect happens after:
1. TCP connection establishes
2. RLPx auth handshake completes
3. Protocol handshake with ETH68 (CoreGeth v1.12.20) completes
4. Status message exchange occurs
5. **Peer immediately disconnects with reason code 0x10**

### Root Cause Analysis

#### Primary Cause: ForkId Mismatch (Addressed in previous fix)
When a node is at block 0, it would report ForkId `0xfc64ec04` with `next: 1150000` per EIP-2124. However, peers at block 19,250,000+ expect ForkId `0xbe46d57c`. This causes immediate rejection.

**Solution Implemented:**
- Use bootstrap pivot block (19,250,000) for ForkId calculation when syncing
- This is configured via `use-bootstrap-checkpoints = true` and `bootstrap-checkpoints` in chain config
- Code location: `EthNodeStatus64ExchangeState.scala:117-142`

#### Secondary Causes (Addressed in previous fixes)

1. **Snappy Decompression Logic**
   - Fixed: Always attempt decompression first, fall back to uncompressed only if it fails AND data looks like RLP
   - Code location: `MessageCodec.scala:84-111`

2. **ETH67 NewPooledTransactionHashes Encoding**
   - Fixed: Types field encoded as `RLPValue(types.toArray)` to match Go's `[]byte` encoding
   - Code location: `ETH67.scala:46`

3. **Request-id=0 Encoding**
   - Fixed: Uses `bigIntToUnsignedByteArray` which returns empty bytes for 0
   - Code location: `ETH66.scala:39`

### Enhanced Diagnostics Added

Added enhanced logging to help diagnose future disconnect issues:

1. **EtcPeerManagerActor** - Logs raw RLP bytes when debug is enabled
   ```scala
   log.debug("PEER_HANDSHAKE_SUCCESS: GetBlockHeaders RLP bytes (len={}): {}", ...)
   ```

2. **PeerActor** - Enhanced disconnect logging with connection status
   ```scala
   log.info("DISCONNECT_DEBUG: ... status: {}", status)
   ```
   Additional context for 0x10 disconnects explaining common causes.

### Impact
- ✅ ForkId mismatch issue resolved for nodes starting from block 0
- ✅ Snappy decompression handles all edge cases
- ✅ ETH67/68 messages correctly encoded for core-geth compatibility
- ✅ Enhanced diagnostics for future debugging

### Files Modified
- `src/main/scala/com/chipprbots/ethereum/network/EtcPeerManagerActor.scala` - Added debug RLP logging
- `src/main/scala/com/chipprbots/ethereum/network/PeerActor.scala` - Enhanced disconnect diagnostics
- `docs/troubleshooting/LOG_REVIEW_RESOLUTION.md` - This documentation

### Verification Steps

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

3. **Enable Debug Logging for RLP Analysis** (if needed):
   ```
   In logback.xml, set:
   <logger name="com.chipprbots.ethereum.network.EtcPeerManagerActor" level="DEBUG"/>
   ```

### Related Documentation

- [BLOCK_SYNC_TROUBLESHOOTING.md](BLOCK_SYNC_TROUBLESHOOTING.md) - General block sync issues
- [Issue #578](https://github.com/chippr-robotics/fukuii/issues/578) - Original issue
- [Core-geth ForkId](https://github.com/etclabscore/core-geth/blob/master/core/forkid/forkid.go) - Reference implementation

---

## Historical Fixes

### 2025-11-23: Snappy Decompression Logic

**Error:** `ETH67_DECODE_ERROR: Unexpected RLP structure... got: RLPValue(20 bytes)`

**Cause:** `looksLikeRLP` check was performed before decompression, causing compressed data starting with RLP-like bytes (0x80-0xff) to be incorrectly treated as uncompressed.

**Fix:** Always decompress first, fallback only if it fails and data looks like RLP.

### 2025-11-23: ETH67 NewPooledTransactionHashes Encoding

**Error:** Core-geth peers disconnect after receiving message

**Cause:** Types field was encoded as `RLPList` instead of `RLPValue`. Go's `[]byte` encodes as a single byte string, not a list of bytes.

**Fix:** Changed to `RLPValue(types.toArray)` to match Go encoding.
