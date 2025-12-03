# Run 005 Configuration

This configuration tests the fix for SNAP sync peer disconnection issues identified in run-004.

## Root Cause Analysis (from Run 004)

Run-004 logs revealed that peers were being disconnected ~15 seconds after SNAP sync requests with reason "Some other reason specific to a subprotocol" (Disconnect.Reasons.Other). The actual root cause was:

1. **Snappy Decompression Failures**: When peers sent SNAP protocol responses (e.g., AccountRange), some messages had Snappy compression issues
2. **Immediate Connection Closure**: Failed decompression triggered immediate connection closure in RLPxConnectionHandler
3. **Premature Blacklisting**: Connection closure caused peers to be blacklisted with generic "Other" reason
4. **Timeout Pattern**: SNAP requests timed out at 30s, but peers were already disconnected at ~15s

## Code Changes in Run 005

### 1. MessageCodec.scala - Improved Decompression Handling

**Before:**
```scala
// Decompression failure → connection closed
if (looksLikeRLP(frameData)) {
  log.warn("Using uncompressed data")
  Success(frameData)
} else {
  log.error("Rejecting")
  Failure(ex)  // Leads to connection closure
}
```

**After:**
```scala
// Better diagnostics and more lenient RLP detection
if (looksLikeRLP(frameData)) {
  log.warn("Peer sent uncompressed data - accepting")
  Success(frameData)
} else {
  val dataSample = s"firstByte=0x${...}, size=${...}"
  log.error(s"Corrupt data ($dataSample) - rejecting")
  Failure(ex)
}
```

**Changes:**
- Enhanced RLP detection heuristic to be more lenient for SNAP messages
- Added diagnostic logging (frame type, data size, first byte)
- Clearer log messages to distinguish peer protocol deviations from corrupt data

### 2. RLPxConnectionHandler.scala - Tolerate Decompression Failures

**Before:**
```scala
case Left(ex) =>
  log.error("Cannot decode message - closing connection")
  connection ! Close  // Always closes on any decode error
```

**After:**
```scala
case Left(ex) =>
  val isDecompressionFailure = errorMsg.contains("FAILED_TO_UNCOMPRESS")
  if (isDecompressionFailure) {
    log.warn("Decompression failed - skipping message, keeping connection")
    // Connection stays alive!
  } else {
    log.error("Malformed message - closing connection")
    connection ! Close
  }
```

**Changes:**
- **Critical**: Do NOT close connection on decompression failures
- Skip individual malformed messages but keep peer connection alive
- Still close connection for truly malformed RLP or unknown message types
- Let timeout/blacklist mechanisms handle consistently failing peers

## Why This Should Fix SNAP Sync

### Old Behavior (Run 004)
```
T+0s:  Send GetAccountRange to Peer A
T+15s: Peer A sends response with compression issue
       → Decompression fails
       → Connection closed immediately
       → Peer A blacklisted with "Other" reason
T+30s: SNAP request timeout (no peer to respond)
```

### New Behavior (Run 005)
```
T+0s:  Send GetAccountRange to Peer A
T+15s: Peer A sends response with compression issue
       → Decompression fails
       → Message skipped
       → Connection STAYS ALIVE ✓
       → Peer A can send other messages
T+20s: Peer A sends another message (e.g., StorageRange)
       → Successfully decoded
       → SNAP sync progresses! ✓
```

**OR**, if Peer A consistently fails:
```
T+0s:  Send GetAccountRange to Peer A
T+15s: Peer A sends response with compression issue → skipped
T+20s: Peer A sends response with compression issue → skipped
T+25s: Peer A sends response with compression issue → skipped
T+30s: SNAP request timeout
       → Peer A blacklisted through normal timeout mechanism
       → Try different peer
```

## Configuration Details

### Network Configuration
- **Network**: ETC (Ethereum Classic mainnet)
- **Purpose**: Test SNAP sync fix for decompression failures
- **Image**: `ghcr.io/chippr-robotics/fukuii:latest` (with run-005 fixes)

### Timeout Configuration (Same as Run 004)

| Setting | Base | Run 005 | Rationale |
|---------|------|---------|-----------|
| peer-response-timeout | 45s | 90s | Give peers time to respond |
| snap-sync.request-timeout | 30s | 60s | Account for slow responses |
| blacklist-duration | 120s | 60s | Faster peer retry |

### Enhanced Logging (Same as Run 004)

The following components have DEBUG logging enabled:
- All SNAP sync components
- All network components  
- All RLPx components
- CacheBasedBlacklist (detailed blacklist reasons)
- SNAPRequestTracker (request lifecycle)
- **NEW**: Enhanced decompression failure logging in MessageCodec

## Expected Improvements

With the code fixes, we expect to see:

### Success Indicators

1. **Fewer Premature Disconnections**
   - Peers should stay connected even if individual messages fail
   - Logs: `DECODE_ERROR: Peer {} sent message that failed to decompress... Skipping this message but keeping connection alive`

2. **More Successful SNAP Responses**
   - AccountRange, StorageRange responses actually arrive
   - Progress: `📈 SNAP Sync Progress: phase=AccountRange, accounts=250000@5000/s`

3. **Better Blacklist Reasons**
   - If peers are blacklisted, reasons should be specific (timeout, validation failure)
   - NOT generic "Some other reason specific to a subprotocol"

4. **Sustained Peer Connections**
   - Average peer connection time should increase
   - Peer count should remain stable (5-10 peers)

### Diagnostic Improvements

With enhanced logging, we can now see:

```
# Decompression failure (non-fatal)
DECODE_ERROR: Peer 164.90.144.106:30303 sent message that failed to decompress.
  Skipping this message but keeping connection alive.
  Error: FAILED_TO_UNCOMPRESS(InvalidHeader): Cannot read uncompressed length

# Frame-level diagnostics  
Frame type 0x{}: Decompression failed and data doesn't look like RLP 
  (firstByte=0x42, size=1234).
  This may indicate corrupt data or protocol mismatch.
```

## Usage

### Quick Start

```bash
cd ops/run-005

# Start the node
./start.sh start

# View logs
./start.sh logs

# Monitor SNAP sync progress
docker compose logs fukuii | grep "SNAP Sync Progress"

# Check for decompression issues
docker compose logs fukuii | grep "DECODE_ERROR\|FAILED_TO_UNCOMPRESS"

# Stop the node
./start.sh stop
```

### What to Look For

#### 1. Decompression Tolerance
```bash
# Should see messages being skipped without connection closure
docker compose logs fukuii | grep "Skipping this message but keeping connection alive"
```

#### 2. SNAP Progress
```bash
# Should see actual progress
docker compose logs fukuii | grep "accounts=.*@" | tail -5
```

#### 3. Peer Stability
```bash
# Peers should stay connected longer
docker compose logs fukuii | grep "PEER_HANDSHAKE_SUCCESS\|Blacklisting peer" | tail -20
```

#### 4. Successful Responses
```bash
# Should see AccountRange/StorageRange responses
docker compose logs fukuii | grep -E "AccountRange.*response|StorageRange.*response" | wc -l
```

## Comparison with Run 004

| Metric | Run 004 | Run 005 (Expected) | Change |
|--------|---------|-------------------|---------|
| Connection closure on decompress fail | Yes (immediate) | No (skip message) | ✓ Fix |
| Peer avg connection time | ~15s | >60s | ✓ Improved |
| SNAP response success rate | 0% | >50% | ✓ Improved |
| Blacklist reason specificity | Generic "Other" | Specific reasons | ✓ Improved |
| Diagnostic logging | Good | Enhanced | ✓ Better |

## Troubleshooting

### If SNAP sync still not progressing:

1. **Check peer count**
   ```bash
   docker compose logs fukuii | grep "Total number of discovered nodes" | tail -1
   ```
   Need 10+ peers for good performance.

2. **Check for other errors**
   ```bash
   docker compose logs fukuii | grep -E "ERROR|WARN" | grep -v "DECODE_ERROR.*decompress" | tail -20
   ```
   Look for errors other than decompression failures.

3. **Verify fix is active**
   ```bash
   # Should see the new tolerant behavior
   docker compose logs fukuii | grep "Skipping this message but keeping connection alive"
   ```

4. **Check SNAP capability**
   ```bash
   # Verify peers advertise SNAP support
   docker compose logs fukuii | grep -i "snap" | grep -i "capability\|hello" | tail -10
   ```

### If seeing too many decompression failures:

This might indicate a broader protocol compatibility issue. Document:
- Which peers are sending bad data
- What message types are failing
- Whether ANY messages succeed

## Next Steps

### If Run 005 Succeeds

1. Monitor for 2-4 hours to confirm stability
2. Verify SNAP sync completes successfully
3. Document final configuration parameters
4. Consider merging fixes to main branch

### If Run 005 Still Fails

The issue may be:
1. **Compression format mismatch**: Peers might be using different Snappy compression format
2. **Protocol version issues**: Peers might be using incompatible SNAP protocol version
3. **Network-level issues**: Deeper network/firewall problems

Next investigation steps:
1. Capture packet traces (tcpdump/wireshark)
2. Compare with working geth/core-geth implementation
3. Test against specific known-good peer

## Related Documentation

- [Run 004 README](../run-004/README.md) - Previous configuration
- [Run 004 TIMEOUT_ANALYSIS](../run-004/TIMEOUT_ANALYSIS.md) - Detailed analysis that led to this fix
- [SNAP Sync FAQ](../../docs/runbooks/snap-sync-faq.md)
- [Known Issues](../../docs/runbooks/known-issues.md)
- [Log Triage Guide](../../docs/runbooks/log-triage.md)

## Code Changes Reference

**Pull Request**: #[PR_NUMBER]
**Commits**:
- `d9d7720`: Fix SNAP sync peer disconnection issue

**Files Modified**:
- `src/main/scala/com/chipprbots/ethereum/network/rlpx/MessageCodec.scala`
- `src/main/scala/com/chipprbots/ethereum/network/rlpx/RLPxConnectionHandler.scala`
