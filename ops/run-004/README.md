# Run 004 Configuration

This configuration sets up a Fukuii node on the **ETC mainnet** (Ethereum Classic) with extended timeouts and enhanced debugging to address the peer disconnection/timeout issue identified in run-003.

## Analysis of Run 003 Results

### Issue Identified

Based on analysis of the run-003 log file (`003.log`), the following pattern was observed:

1. **GetAccountRange requests sent** at 13:48:23 to three peers:
   - 164.90.144.106:30303
   - 157.245.77.211:30303
   - 64.225.0.245:30303

2. **Peers blacklisted ~15 seconds later** at 13:48:38 with reason: "Some other reason specific to a subprotocol"

3. **SNAP request timeouts** at 13:48:53 (30 seconds after sending):
   ```
   WARN  [c.c.e.b.sync.snap.SNAPRequestTracker] - SNAP request GetAccountRange timeout for request ID 1
   WARN  [c.c.e.b.s.s.AccountRangeDownloader] - Account range request timeout for range [00000000...10000000]
   ```

4. **No AccountRange responses received** before timeout

### Root Cause Hypothesis

The timeline suggests that peers are being **blacklisted before they can respond** to the SNAP sync requests:

- Request sent at T+0s
- Peer blacklisted at T+15s with "Some other reason specific to a subprotocol"
- Timeout occurs at T+30s (no response possible because peer is already blacklisted)

This indicates one of two problems:
1. The blacklisting is **too aggressive** - peers are being blacklisted for reasons unrelated to their ability to serve SNAP sync data
2. The **timeout is too short** - peers need more than 30 seconds to respond with account range data

### Run 003 Notes

From the issue description, the run-003 operator noted:
- "gchr image is working"
- "did a short regular sync then restart to get snap sync primed and running"
- "captured logs"
- "initial impression is that peers are being disconnected maybe before returning data and then the timeout happens"

This observation aligns perfectly with our log analysis.

## Key Changes from Run 003

Based on the analysis above, run-004 implements the following targeted changes:

### 1. Extended Timeouts

**Problem**: Peers may need more time to respond to SNAP sync requests, especially for large account ranges.

**Solution**:
- `peer-response-timeout`: **60s → 90s** (50% increase)
- `snap-sync.request-timeout`: **30s → 60s** (100% increase)

**Rationale**: By doubling the SNAP sync timeout and increasing the general peer response timeout, we give peers significantly more time to respond before being timed out. This should reduce false-positive timeouts.

### 2. Enhanced Blacklist Debugging

**Problem**: We don't know why peers are being blacklisted with "Some other reason specific to a subprotocol".

**Solution**: Added DEBUG logging for:
- `CacheBasedBlacklist` - Will show exactly why each peer is blacklisted
- `SNAPRequestTracker` - Will show the full lifecycle of SNAP requests
- `SyncProgressMonitor` - Will show detailed progress information

**Rationale**: These debug logs will help us understand:
- What triggers the "Some other reason specific to a subprotocol" blacklisting
- Whether peers are responding but responses are being rejected
- The exact timing of request/response/timeout events

### 3. Retained Run-003 Optimizations

We keep the following improvements from run-003:
- Reduced blacklist durations (60s instead of 120s)
- ETC mainnet for better peer availability
- SNAP sync only (no fast sync)
- DEBUG logging for network, RLPx, and SNAP sync components

## Configuration Details

### Network Configuration
- **Network**: ETC (Ethereum Classic mainnet)
- **Purpose**: Diagnose and fix SNAP sync timeout/blacklist issues
- **Image**: `ghcr.io/chippr-robotics/fukuii:latest` (GitHub Container Registry)

### Logging Configuration

#### NEW for Run 004 (Enhanced Debugging)
- `com.chipprbots.ethereum.blockchain.sync.CacheBasedBlacklist` - DEBUG
- `com.chipprbots.ethereum.blockchain.sync.snap.SNAPRequestTracker` - DEBUG
- `com.chipprbots.ethereum.blockchain.sync.snap.SyncProgressMonitor` - DEBUG

#### Continued from Run 003
- All SNAP sync components - DEBUG
- All network components - DEBUG
- All RLPx components - DEBUG
- All peer discovery components - DEBUG
- Fast sync components - INFO (not being used)
- Regular sync components - DEBUG

### Timeout Configuration

| Setting | Base | Run 003 | Run 004 | Change |
|---------|------|---------|---------|---------|
| peer-response-timeout | 45s | 60s | **90s** | +50% from run-003 |
| snap-sync.request-timeout | 30s | 30s | **60s** | +100% from base |
| blacklist-duration | 120s | 60s | 60s | Same as run-003 |

## Directory Structure

```
ops/run-004/
├── conf/
│   ├── etc.conf          # Network configuration with extended timeouts
│   └── logback.xml       # Enhanced logging configuration
├── docker-compose.yml    # Docker Compose configuration
├── start.sh             # Quick start/stop script
├── README.md            # This file
└── TIMEOUT_ANALYSIS.md  # Detailed analysis of run-003 timeout issue
```

## Usage

### Quick Start (Recommended)

Use the provided convenience script:

```bash
cd ops/run-004

# Start the node
./start.sh start

# View logs
./start.sh logs

# Check status
./start.sh status

# Stop the node
./start.sh stop

# Remove all data (including blockchain)
./start.sh clean

# Show help
./start.sh help
```

### Using Docker Compose Manually

1. Navigate to this directory:
   ```bash
   cd ops/run-004
   ```

2. Start the node:
   ```bash
   docker compose up -d
   ```

3. View logs:
   ```bash
   docker compose logs -f fukuii
   ```

4. Stop the node:
   ```bash
   docker compose down
   ```

5. To remove all data (including blockchain data):
   ```bash
   docker compose down -v
   ```

## Monitoring

### Health Check
```bash
curl http://localhost:8546/health
```

### Readiness Check
```bash
curl http://localhost:8546/readiness
```

### Full Health Status
```bash
curl http://localhost:8546/healthcheck
```

## Exposed Ports

- **8545**: JSON-RPC HTTP endpoint
- **8546**: JSON-RPC WebSocket endpoint  
- **30303**: P2P networking (TCP and UDP)

## Expected Outcomes

With the extended timeouts and enhanced debugging in run-004, we expect to see:

### Success Indicators
1. **Fewer timeout messages** - Peers have more time to respond
2. **More successful AccountRange responses** - Reduced false-positive timeouts
3. **Better understanding of blacklisting** - Debug logs reveal why peers are blacklisted
4. **Higher sustained peer count** - Fewer peers blacklisted prematurely

### Debug Information to Look For

When analyzing run-004 logs, pay attention to:

1. **Blacklist messages** with DEBUG logging:
   ```
   DEBUG [c.c.e.b.sync.CacheBasedBlacklist] - Blacklisting peer [address] for [duration]. Reason: [detailed reason]
   ```

2. **SNAP request lifecycle**:
   ```
   DEBUG [c.c.e.b.s.snap.SNAPRequestTracker] - SNAP request sent/received/timeout
   ```

3. **Timeline of events**:
   - When was the request sent?
   - When was the peer blacklisted?
   - When did the timeout occur?
   - Did any response arrive?

## Investigation Areas for Run 004

Based on run-003 findings, we're investigating:

1. **Timeout vs Blacklist Race Condition**: Are peers being blacklisted before they can respond?
2. **Response Time Distribution**: How long do peers actually take to respond to GetAccountRange requests?
3. **Blacklist Root Cause**: What triggers "Some other reason specific to a subprotocol"?
4. **Optimal Timeout Values**: What timeout values provide the best balance of speed and success rate?

## Comparison Table

| Aspect | Run 003 | Run 004 | Change |
|--------|---------|---------|---------|
| Network | ETC Mainnet | ETC Mainnet | Same |
| Docker Image | `ghcr.io/...` | `ghcr.io/...` | Same |
| peer-response-timeout | 60s | **90s** | +50% |
| snap-sync.request-timeout | 30s | **60s** | +100% |
| Blacklist Logging | DEBUG | **Enhanced DEBUG** | More detailed |
| SNAP Tracker Logging | INFO | **DEBUG** | New |
| Container Name | fukuii-run-003 | fukuii-run-004 | Updated |
| Volume Names | fukuii-run003-* | fukuii-run004-* | Updated |
| Node ID | run-003 | run-004 | Updated |

## Performance Considerations

⚠️ **Warning**: The extended timeouts mean that:
- **Slower failure detection** - Takes longer to detect truly unresponsive peers
- **Potentially slower sync** - More time spent waiting for slow peers
- **Same log volume** - DEBUG logging still generates significant output

However, these tradeoffs are acceptable for diagnostic purposes. If run-004 is successful, we can tune the timeouts to find the optimal balance.

## Troubleshooting Guide

### No improvement in timeout rate
If timeouts still occur frequently with extended timeouts:
1. Check if peers are still being blacklisted early
2. Examine the detailed blacklist logs for root cause
3. Consider that peers may genuinely be unable to serve the data

### Container won't start
Check logs:
```bash
docker compose logs fukuii
```

### No peers connecting
1. Ensure ports 30303 (TCP/UDP) are open in your firewall
2. Check peer discovery logs (DEBUG enabled):
   ```bash
   docker compose logs fukuii | grep -i "discovery\|peer"
   ```

### Sync not progressing
With DEBUG logging enabled, examine the sync-related log entries:
```bash
docker compose logs fukuii | grep -i "sync\|snap"
```

### Investigating blacklist activity
Check blacklist activity with enhanced DEBUG logging:
```bash
docker compose logs fukuii | grep -i "blacklist"
```

### Examining SNAP request timeline
Track the full lifecycle of SNAP requests:
```bash
docker compose logs fukuii | grep -i "SNAPRequestTracker\|AccountRange"
```

## Next Steps

After run-004 completes:

1. **Analyze the logs** for:
   - Did extended timeouts reduce timeout rate?
   - What triggers the "Some other reason specific to a subprotocol" blacklisting?
   - What is the actual response time distribution for AccountRange requests?

2. **Based on findings**, consider:
   - Further timeout tuning
   - Code changes to address blacklisting issues
   - Protocol-level improvements

3. **Document in run-005** (if needed):
   - Implement code fixes based on run-004 findings
   - Test with optimized timeout values
   - Verify SNAP sync works reliably

## Related Documentation

- [Run 003 README](../run-003/README.md) - Previous configuration and findings
- [Run 003 SYNC_BEHAVIOR.md](../run-003/SYNC_BEHAVIOR.md) - Sync mode switching behavior
- [Fukuii Logging Documentation](../../docs/operations/LOGGING.md)
- [Log Level Categorization ADR](../../docs/adr/operations/OPS-002-logging-level-categorization.md)
- [Operations Runbooks](../../docs/runbooks/README.md)
- [Log Triage Guide](../../docs/runbooks/log-triage.md)
- [Metrics & Monitoring](../../docs/operations/metrics-and-monitoring.md)

## Support

For issues or questions about this configuration, please refer to:
- [Known Issues](../../docs/runbooks/known-issues.md)
- [GitHub Issues](https://github.com/chippr-robotics/fukuii/issues)
