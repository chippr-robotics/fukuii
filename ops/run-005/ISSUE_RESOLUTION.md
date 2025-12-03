# SNAP Sync Decompression Issue - Resolution Summary

## Issue ID
- **GitHub Issue**: #702
- **Title**: run 005 - SNAP sync configuration and troubleshooting

## Problem Statement
SNAP sync in the fukuii node was failing to make progress on ETC mainnet. Peers were being disconnected approximately 15 seconds after SNAP sync requests with the reason "Some other reason specific to a subprotocol" (Disconnect.Reasons.Other), preventing them from responding before the 30-second timeout.

## Root Cause Analysis

### Timeline from Run 004 Logs
1. **T+0s**: GetAccountRange requests sent to peers
2. **T+15s**: Peers blacklisted with "Other/subprotocol" reason
3. **T+30s**: SNAP request timeouts (no responses received)

### Actual Root Cause
Investigation revealed the following chain of events:

1. Peers received SNAP sync requests (GetAccountRange, StorageRange, etc.)
2. Peers attempted to respond with Snappy-compressed SNAP protocol messages
3. Some responses had Snappy compression format issues or were malformed
4. MessageCodec.decompressData() failed with "FAILED_TO_UNCOMPRESS" error
5. RLPxConnectionHandler.processMessage() closed the connection immediately on any decode error
6. Connection closure triggered PeerActor to send PeerClosedConnection with Disconnect.Reasons.Other
7. PeerManagerActor blacklisted the peer with generic "Some other reason specific to a subprotocol"
8. SNAP request timeout fired at T+30s, but peer was already gone at T+15s

## Solution Implemented

### Code Changes

#### 1. MessageCodec.scala
**File**: `src/main/scala/com/chipprbots/ethereum/network/rlpx/MessageCodec.scala`

**Changes**:
- Enhanced RLP detection heuristic for better fallback handling
- Improved diagnostic logging (frame type, data size, first byte)
- Clearer log messages distinguishing protocol deviations from corrupt data

**Impact**: Better visibility into decompression failures and more accurate fallback detection.

#### 2. RLPxConnectionHandler.scala  
**File**: `src/main/scala/com/chipprbots/ethereum/network/rlpx/RLPxConnectionHandler.scala`

**Changes**:
- Modified `processMessage()` to distinguish decompression failures from other decode errors
- For decompression failures: Skip the message, keep connection alive, log as WARNING
- For other decode errors: Close connection as before, log as ERROR

**Impact**: **Critical fix** - Connections stay alive even if individual SNAP messages fail decompression, allowing peers to continue serving other valid messages.

#### 3. Run 005 Configuration
**Directory**: `ops/run-005/`

**Contents**:
- Test configuration based on run-004
- Comprehensive README explaining the issue and fix
- Same timeout settings as run-004 (90s peer timeout, 60s SNAP timeout)
- Enhanced logging configuration

## Why This Fixes The Issue

### Before (Broken)
```
T+0s:  Send GetAccountRange to Peer A
T+15s: Peer A sends response with compression issue
       ↓ Decompression fails
       ↓ Connection closed immediately
       ↓ Peer A blacklisted with "Other" reason
T+30s: SNAP request timeout (no peer available)
       ↓ Retry with another peer
       ↓ Same issue repeats
       ↓ No progress in SNAP sync
```

### After (Fixed)
```
T+0s:  Send GetAccountRange to Peer A
T+15s: Peer A sends response with compression issue
       ↓ Decompression fails
       ↓ Message skipped (logged as WARNING)
       ↓ Connection STAYS ALIVE ✓
T+20s: Peer A sends another message (e.g., StorageRange)
       ↓ Successfully decompressed
       ↓ SNAP sync progresses! ✓
```

**OR**, if Peer A consistently fails:
```
T+0s:  Send GetAccountRange to Peer A
T+15s-25s: Multiple messages fail decompression → all skipped
T+30s: Natural timeout occurs
       ↓ Peer A blacklisted through timeout mechanism
       ↓ Try different peer
       ↓ System self-corrects
```

## Security Considerations

The fix maintains security by:

1. **Still closing connections for truly malformed data**: If the decode error is NOT a decompression failure (e.g., invalid RLP structure, unknown message type), the connection is still closed immediately.

2. **Natural peer filtering**: Peers that consistently send bad data will still be blacklisted through normal timeout mechanisms.

3. **Logging**: All decompression failures are logged for monitoring and potential peer reputation analysis.

## Testing Plan

### Deploy to Run 005
1. Build image with the fixes
2. Deploy using `ops/run-005/` configuration
3. Monitor logs for expected behavior

### Success Criteria
- [ ] Sustained peer connections (avg >60s, not ~15s)
- [ ] SNAP sync progress messages (`accounts=X@Y/s`)
- [ ] Decompression failures logged but tolerated
- [ ] Specific blacklist reasons (not generic "Other")
- [ ] SNAP sync completes successfully

### What to Monitor
```bash
# Decompression tolerance
docker compose logs fukuii | grep "Skipping this message but keeping connection alive"

# SNAP progress
docker compose logs fukuii | grep "SNAP Sync Progress"

# Peer stability  
docker compose logs fukuii | grep "PEER_HANDSHAKE_SUCCESS\|Blacklisting peer"

# Successful responses
docker compose logs fukuii | grep -E "AccountRange.*response|StorageRange.*response"
```

## Commits

1. **6a74c0b**: Initial analysis and planning
2. **d9d7720**: Fix SNAP sync peer disconnection issue (core fix)
3. **6143adc**: Add run-005 configuration for testing
4. **c434fc3**: Fix code review issues

## Related Files

- `src/main/scala/com/chipprbots/ethereum/network/rlpx/MessageCodec.scala`
- `src/main/scala/com/chipprbots/ethereum/network/rlpx/RLPxConnectionHandler.scala`
- `ops/run-004/README.md` - Previous configuration
- `ops/run-004/TIMEOUT_ANALYSIS.md` - Detailed analysis leading to fix
- `ops/run-005/README.md` - Test configuration documentation

## References

- [SNAP Protocol Specification](https://github.com/ethereum/devp2p/blob/master/caps/snap.md)
- [Snappy Compression](https://github.com/google/snappy)
- [RLPx Protocol](https://github.com/ethereum/devp2p/blob/master/rlpx.md)

## Future Improvements

Potential enhancements identified during code review (non-critical):

1. Use exception type checking instead of string matching for decompression failure detection
2. Add bit mask operation clarity in RLP detection comments
3. Enhance shell script error handling with `set -euo pipefail`
4. Consider implementing peer reputation scoring for compression failure patterns

## Conclusion

The SNAP sync issue was caused by overly aggressive connection closure on Snappy decompression failures. The fix allows the node to tolerate individual message failures while maintaining security and peer quality through natural timeout and blacklist mechanisms. This should enable SNAP sync to progress successfully on ETC mainnet.

**Status**: Ready for deployment and testing in run-005.
