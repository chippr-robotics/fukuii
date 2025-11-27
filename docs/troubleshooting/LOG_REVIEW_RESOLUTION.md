# Log Review Resolution Guide

This document provides technical analysis and resolutions for network-related issues discovered through log review.

## UPDATE 2025-11-27: Enhanced Diagnostics for 5-Second Disconnect Pattern

### Issue Investigated
Peers disconnect simultaneously after ~5 seconds of no activity with reason code 0x10 ("Some other reason specific to a subprotocol"). The pattern observed:

1. Last successful request: GetReceipts sent at 10:35:10.761
2. Disconnection: 10:35:15.780 (5.019 seconds later)
3. All peers disconnect at the same time

### Analysis Findings

#### Correlation with TCP Ack Timeout
The 5-second disconnect pattern matches the `wait-for-tcp-ack-timeout` configuration (default: 5 seconds). When a message is sent via TCP, the system waits for a TCP-level acknowledgment from the Akka IO layer. If this ack is not received within the timeout, the connection is closed.

**Important:** This is NOT a peer response timeout - it's a local TCP write acknowledgment timeout. If this timeout is triggered, it indicates:
- Network congestion or packet loss
- The remote peer has closed the connection
- TCP buffer is full

#### "Did not find 'Hello'" Warnings
These warnings appear when:
1. Data is received after auth handshake
2. The FrameCodec parses the data into frames
3. The first frame is not a Hello message (type 0x00)

This can happen legitimately when:
- Data is incomplete (need to wait for more bytes)
- Frame parsing returns no complete frames yet

### Diagnostic Improvements Made

1. **Enhanced AckTimeout Logging**
   - Now logs the message type and code that was pending when timeout occurred
   - Shows the number of queued messages waiting to be sent
   - Provides clear indication that it's a TCP ack timeout, not a peer response timeout

2. **Enhanced "Did not find 'Hello'" Warning**
   - Now shows the data length received
   - Indicates that the system is waiting for more data or a Hello frame

3. **CancellableAckTimeout Enhancement**
   - Now tracks the message type and code for better diagnostics
   - Allows correlating timeout errors with specific message types

### Verification Steps

1. **Check if disconnects are TCP ack timeouts**:
   ```bash
   grep "SEND_MSG_TIMEOUT" /path/to/fukuii.log
   ```
   If you see these, the disconnect is due to TCP ack timeout, not encoding issues.

2. **Check for frame parsing issues**:
   ```bash
   grep "Did not find 'Hello'" /path/to/fukuii.log
   ```
   The enhanced message will show if frames are being parsed correctly.

3. **Monitor connection health**:
   ```bash
   grep -E "SEND_MSG_ACK|SEND_MSG_TIMEOUT|RECV_MSG" /path/to/fukuii.log
   ```

### Files Modified
- `src/main/scala/com/chipprbots/ethereum/network/rlpx/RLPxConnectionHandler.scala` - Enhanced diagnostic logging
- `docs/troubleshooting/LOG_REVIEW_RESOLUTION.md` - This documentation

### Next Steps for Further Investigation
If the 5-second disconnect persists:
1. Check if `SEND_MSG_TIMEOUT` appears in logs - indicates TCP ack issue
2. Increase `wait-for-tcp-ack-timeout` to 10 seconds temporarily to rule out transient issues
3. Check network connectivity and firewall rules
4. Monitor system resources (CPU, memory) during syncing

---

## UPDATE 2025-11-27: ETH66+ Protocol Adaptation for GetReceipts and GetBlockBodies

### Issue Discovered
Peers disconnect with reason code 0x01 ("TCP sub-system error") during FastSync when requesting block bodies and receipts from ETH66/ETH67/ETH68 peers.

The disconnect sequence:
1. TCP connection establishes
2. RLPx auth handshake completes
3. Protocol handshake with ETH66+ peer completes
4. Status message exchange succeeds
5. GetBlockHeaders (ETH66 format with request-id) works correctly
6. **GetReceipts/GetBlockBodies sent in ETH63/ETH62 format → Peer disconnects**

### Root Cause Analysis

FastSync was creating request messages in ETH63/ETH62 format directly:
```scala
// FastSync.scala line 824
GetReceipts(receiptsToGet)  // ETH63 format: [hash1, hash2, ...]

// FastSync.scala line 848
GetBlockBodies(blockBodiesToGet)  // ETH62 format: [hash1, hash2, ...]
```

But ETH66+ peers expect the request-id wrapper format:
```
ETH66 format: [requestId, [hash1, hash2, ...]]
```

The `PeersClient.adaptMessageForPeer` method only handled GetBlockHeaders adaptation, not GetReceipts or GetBlockBodies.

### The Fix

**Before (PeersClient.scala:149-165):**
```scala
private def adaptMessageForPeer[RequestMsg <: Message](peer: Peer, message: RequestMsg): Message =
  handshakedPeers.get(peer.id) match {
    case Some(peerWithInfo) =>
      val usesRequestId = Capability.usesRequestId(peerWithInfo.peerInfo.remoteStatus.capability)
      message match {
        case eth66: ETH66GetBlockHeaders if !usesRequestId =>
          ETH62.GetBlockHeaders(eth66.block, eth66.maxHeaders, eth66.skip, eth66.reverse)
        case eth62: ETH62.GetBlockHeaders if usesRequestId =>
          ETH66GetBlockHeaders(ETH66.nextRequestId, eth62.block, eth62.maxHeaders, eth62.skip, eth62.reverse)
        case _ => message // Only GetBlockHeaders was adapted!
      }
```

**After (PeersClient.scala:149-185):**
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
        // GetBlockBodies adaptation - NEW
        case eth66: ETH66GetBlockBodies if !usesRequestId =>
          ETH62.GetBlockBodies(eth66.hashes)
        case eth62: ETH62.GetBlockBodies if usesRequestId =>
          ETH66GetBlockBodies(ETH66.nextRequestId, eth62.hashes)
        // GetReceipts adaptation - NEW
        case eth66: ETH66GetReceipts if !usesRequestId =>
          ETH63.GetReceipts(eth66.blockHashes)
        case eth63: ETH63.GetReceipts if usesRequestId =>
          ETH66GetReceipts(ETH66.nextRequestId, eth63.blockHashes)
        case _ => message
      }
```

Also updated:
1. `responseClassTag` to handle ETH66BlockBodies and ETH66Receipts response types
2. `responseMsgCode` to return correct codes for all ETH66 request types
3. FastSync `handleResponses` to process ETH66BlockBodies and ETH66Receipts responses

### Impact
- ✅ GetBlockBodies and GetReceipts now correctly adapted for ETH66+ peers
- ✅ Maintains backward compatibility with ETH63-65 peers
- ✅ FastSync can now successfully sync from ETH66/ETH67/ETH68 peers
- ✅ No changes required to existing ETH63/ETH62 message handling

### Files Modified
- `src/main/scala/com/chipprbots/ethereum/blockchain/sync/PeersClient.scala` - Extended message adaptation
- `src/main/scala/com/chipprbots/ethereum/blockchain/sync/fast/FastSync.scala` - Added ETH66 response handlers
- `docs/troubleshooting/LOG_REVIEW_RESOLUTION.md` - This documentation

### Verification Steps

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

### 2025-11-27: ETH66+ Protocol Adaptation for GetReceipts and GetBlockBodies

**Error:** Peer disconnect with reason 0x01 ("TCP sub-system error") during FastSync

**Cause:** GetReceipts and GetBlockBodies messages were sent in ETH63/ETH62 format to ETH66+ peers that expect request-id wrapper format.

**Fix:** Extended `PeersClient.adaptMessageForPeer` to adapt GetReceipts and GetBlockBodies messages, added ETH66 response handlers in FastSync.

### 2025-11-23: Snappy Decompression Logic

**Error:** `ETH67_DECODE_ERROR: Unexpected RLP structure... got: RLPValue(20 bytes)`

**Cause:** `looksLikeRLP` check was performed before decompression, causing compressed data starting with RLP-like bytes (0x80-0xff) to be incorrectly treated as uncompressed.

**Fix:** Always decompress first, fallback only if it fails and data looks like RLP.

### 2025-11-23: ETH67 NewPooledTransactionHashes Encoding

**Error:** Core-geth peers disconnect after receiving message

**Cause:** Types field was encoded as `RLPList` instead of `RLPValue`. Go's `[]byte` encodes as a single byte string, not a list of bytes.

**Fix:** Changed to `RLPValue(types.toArray)` to match Go encoding.
