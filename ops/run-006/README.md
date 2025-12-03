# Run 006 Configuration

This configuration addresses the findings from run-005 investigation into why SNAP sync is not progressing on ETC mainnet.

## Problem Statement (from Run 005)

Run 005 attempted to use SNAP sync on ETC mainnet but sync never progressed past the account retrieval phase:
- GetAccountRange requests were sent to peers
- All requests timed out after 60s
- No responses were ever received
- Peers were blacklisted with "Some other reason specific to a subprotocol"
- SNAP sync progress remained at 0 accounts

## Root Cause Analysis

### Investigation Findings

1. **ETC Mainnet Peers Do Not Advertise SNAP/1**
   - All connected peers only advertised `ETH68` capability
   - No peers advertised `snap/1` capability
   - Examined 3 primary peers repeatedly throughout run-005:
     - 164.90.144.106:30303 - only ETH68
     - 157.245.77.211:30303 - only ETH68
     - 64.225.0.245:30303 - only ETH68

2. **Fukuii Correctly Configured for SNAP**
   - Node properly advertises `snap/1` in capabilities list
   - Configuration in `src/main/resources/conf/chains/etc-chain.conf`:
     ```
     capabilities = ["eth/63", "eth/64", "eth/65", "eth/66", "eth/67", "eth/68", "snap/1"]
     ```
   - SNAP sync components are properly initialized
   - GetAccountRange requests are correctly formatted

3. **Core-Geth Implementation Verified**
   - Examined core-geth source code (official ETC client)
   - SNAP/1 protocol IS implemented in core-geth
   - Default sync mode is SnapSync
   - SNAP protocol is enabled when `SnapshotCache > 0` (default: 102)
   - However, actual ETC mainnet nodes appear to run with different configurations

### Why SNAP Doesn't Work on ETC Mainnet

**The ETC network ecosystem has not widely adopted SNAP sync**. Possible reasons:

1. **Operational Decisions**: Many ETC node operators may:
   - Disable snapshot cache to reduce disk/memory usage
   - Use older node software versions without SNAP
   - Explicitly configure nodes for FastSync or FullSync only

2. **Network Maturity**: SNAP sync is a relatively newer protocol:
   - Designed for Ethereum mainnet's large state size
   - ETC has smaller state, making FastSync adequate
   - Less urgency to upgrade to SNAP on ETC

3. **Client Distribution**: While core-geth supports SNAP:
   - Not all ETC nodes run latest core-geth versions
   - Some may run alternative clients
   - Default configurations may vary

### Impact

- **SNAP sync is not viable for ETC mainnet** at this time
- Attempting SNAP sync results in:
  - All requests timing out (no SNAP-capable peers)
  - Peers being unnecessarily blacklisted
  - No sync progress whatsoever
  - Wasted network resources

## Solution for Run 006

### Configuration Changes

Run 006 disables SNAP sync and enables FastSync instead:

```hocon
fukuii {
  sync {
    # DISABLED: SNAP not supported by ETC mainnet peers
    do-snap-sync = false
    
    # ENABLED: FastSync is well-supported on ETC mainnet
    do-fast-sync = true
  }
}
```

### Why FastSync is Appropriate

1. **Wide Peer Support**: All ETC peers support ETH63-ETH68, which includes FastSync
2. **Proven Reliability**: FastSync has been used on ETC for years
3. **Adequate Performance**: ETC state size is manageable with FastSync
4. **Peer Availability**: Many peers available for FastSync requests

## Expected Behavior in Run 006

### Success Indicators

1. **Pivot Block Selection**
   ```
   FastSync: Selected pivot block #21418000
   ```

2. **State Download Progress**
   ```
   FastSync: Downloaded 1000 state nodes (10%)
   FastSync: Downloaded 5000 state nodes (50%)
   ```

3. **Block Download Progress**
   ```
   FastSync: Downloaded blocks 21400000-21410000
   ```

4. **Peer Stability**
   - Peers should stay connected (60s+, not ~15s)
   - ETH protocol message exchanges succeed
   - Blacklist reasons are specific (if any blacklisting occurs)

### Monitoring Commands

```bash
# Check sync progress
docker compose logs fukuii | grep "FastSync\|SYNC"

# Monitor peer connections
docker compose logs fukuii | grep "PEER_HANDSHAKE_SUCCESS"

# Check for errors
docker compose logs fukuii | grep -E "ERROR|WARN" | tail -20

# View overall progress
docker compose logs fukuii | grep -i "progress\|downloaded"
```

## Network Configuration

| Setting | Value | Rationale |
|---------|-------|-----------|
| **Network** | etc | ETC mainnet |
| **Sync Mode** | FastSync | Only mode with peer support |
| **SNAP Sync** | Disabled | No SNAP-capable peers available |
| **Peer Timeout** | 90s | From run-005, works well for ETH protocol |
| **Blacklist Duration** | 60s | From run-005, allows quick retry |

## Comparison: Run 005 vs Run 006

| Aspect | Run 005 (SNAP) | Run 006 (Fast) |
|--------|----------------|----------------|
| **Sync Method** | SNAP sync | FastSync |
| **Peer Support** | ❌ None (0% of peers) | ✅ All peers (100%) |
| **Expected Progress** | ❌ No progress | ✅ Steady progress |
| **GetAccountRange** | ✅ Sent, ❌ Timeout | N/A (not needed) |
| **GetBlockHeaders** | ✅ Works | ✅ Works |
| **GetBlockBodies** | ✅ Works | ✅ Works |
| **GetReceipts** | ✅ Works | ✅ Works |
| **State Download** | ❌ Cannot start | ✅ Should work |

## Usage

### Quick Start

```bash
cd ops/run-006

# Start the node
./start.sh start

# View logs (live)
./start.sh logs

# Monitor FastSync progress
docker compose logs fukuii | grep -i "fast\|pivot\|progress"

# Stop the node
./start.sh stop
```

### What to Look For

#### 1. Pivot Block Selection
```bash
# Should see pivot being selected within first few minutes
docker compose logs fukuii | grep -i "pivot"
```

#### 2. State Download
```bash
# Should see state nodes being downloaded
docker compose logs fukuii | grep -i "state.*download\|state.*progress"
```

#### 3. Block Download
```bash
# Should see blocks being downloaded
docker compose logs fukuii | grep -i "block.*download\|downloaded.*block"
```

#### 4. Peer Health
```bash
# Peers should stay connected
docker compose logs fukuii | grep "PEER_HANDSHAKE_SUCCESS" | wc -l
```

## Troubleshooting

### If FastSync Is Not Progressing

1. **Check Peer Count**
   ```bash
   docker compose logs fukuii | grep "Total number of discovered nodes" | tail -1
   ```
   Need at least 3 peers for FastSync to start.

2. **Check for Pivot Block Issues**
   ```bash
   docker compose logs fukuii | grep -i "pivot\|ERROR\|WARN" | tail -20
   ```
   Pivot selection must succeed before FastSync can begin.

3. **Verify FastSync is Enabled**
   ```bash
   docker compose logs fukuii | grep -i "fast.*sync.*start\|starting fast"
   ```

4. **Check for Protocol Errors**
   ```bash
   docker compose logs fukuii | grep -E "ERROR|WARN" | grep -v "SNAP" | tail -20
   ```

### If Seeing SNAP-Related Errors

These should not occur in run-006 since SNAP is disabled, but if they do:
```bash
# Verify SNAP is actually disabled
docker compose logs fukuii | grep -i "snap.*enabled\|do-snap-sync"
```

## Next Steps

### If Run 006 Succeeds

1. **Monitor** for 2-4 hours to confirm steady progress
2. **Verify** FastSync completes and transitions to regular sync
3. **Document** final sync time and performance metrics
4. **Consider** this as the recommended sync method for ETC mainnet

### If Run 006 Fails

Additional investigation may be needed for:
1. **Network connectivity issues**: Firewall/NAT problems
2. **Peer quality issues**: All peers are unreliable
3. **State corruption**: Database issues
4. **Fork detection issues**: Chain selection problems

## Recommendations for ETC Mainnet Sync

Based on run-005 and run-006 findings:

### ✅ DO Use FastSync
- Well-supported by all ETC peers
- Adequate performance for ETC state size
- Proven reliability on ETC mainnet
- Wide client compatibility

### ❌ DO NOT Use SNAP Sync
- Not supported by ETC mainnet peers (currently)
- Will result in timeout loops and no progress
- Wastes resources and blacklists good peers
- May work in future if ETC ecosystem adopts it

### Configuration Template

For other ETC mainnet deployments, use:

```hocon
fukuii {
  blockchains {
    network = "etc"
  }
  
  sync {
    do-snap-sync = false    # Not supported on ETC mainnet
    do-fast-sync = true     # Recommended for ETC
    
    peer-response-timeout = 90.seconds
    blacklist-duration = 60.seconds
  }
}
```

## Related Documentation

- [Run 005 README](../run-005/README.md) - Previous SNAP sync attempt
- [Run 005 ISSUE_RESOLUTION](../run-005/ISSUE_RESOLUTION.md) - Decompression fix investigation
- [Run 004 README](../run-004/README.md) - Earlier configuration
- [Core-geth SNAP Implementation](https://github.com/etclabscore/core-geth/tree/master/eth/protocols/snap)

## Key Learnings

1. **Protocol Capability Negotiation Matters**
   - Don't assume all peers support all advertised capabilities
   - Check actual peer Hello messages for capability lists
   - Design sync strategy to fall back gracefully

2. **Network-Specific Behavior**
   - ETC mainnet != Ethereum mainnet in terms of feature adoption
   - SNAP sync works on ETH but not (yet) on ETC
   - Always test against actual network before deploying

3. **Monitoring is Critical**
   - Log peer capabilities during handshake
   - Track request success/failure rates per protocol
   - Alert on unexpected timeout patterns

4. **Configuration Flexibility**
   - Having multiple sync modes (SNAP, Fast, Full) is valuable
   - Easy configuration switches enable rapid troubleshooting
   - Document which modes work on which networks

## Conclusion

Run 006 switches from SNAP sync to FastSync based on empirical evidence that ETC mainnet peers do not support the SNAP/1 protocol. This configuration should allow the node to make steady sync progress and successfully join the ETC mainnet network.

**Expected Outcome**: Successful sync to ETC mainnet using FastSync, completing within a reasonable timeframe depending on network conditions and hardware.
