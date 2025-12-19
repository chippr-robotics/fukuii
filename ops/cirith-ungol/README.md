# Cirith Ungol Configuration - ETC Mainnet Testing

> *Named after the pass of the spider in Mordor, a treacherous path that must be carefully navigated*

This configuration provides a testing environment for Fukuii nodes on ETC mainnet with comprehensive logging and monitoring capabilities.

## Purpose

Cirith Ungol serves as a general-purpose testing environment for:

1. **Test Fukuii implementation** - Validate node behavior on ETC mainnet
2. **Capture detailed logs** - Analyze sync processes and network behavior
3. **Investigate network issues** - Debug protocol implementation and peer interactions
4. **Protocol validation** - Test various sync modes and network configurations

## Architecture

Cirith Ungol now runs a two-node mini harness so we can control both sides of a SNAP exchange:

### Fukuii Node
- ETC mainnet node under test
- Configurable sync modes (SNAP, Fast, Full)
- Ports: 8545 (HTTP), 8546 (WS), 30303 (P2P)

### Core-Geth Reference Node
- Runs [`etclabscore/core-geth:latest`](https://github.com/etclabscore/core-geth)
- Mirrors the command-line used in ad-hoc runs (HTTP/WS enabled, SNAP sync, metrics)
- Ports: 18545 (HTTP), 18546 (WS) from host ➜ 8545/8546 inside container, P2P on 30304 ➜ 30303
- Acts as a managed static peer for Fukuii via `conf/static-nodes.json`

## Configuration Changes from Run 005

### SNAP Sync: ENABLED (Unit Under Test)
```hocon
do-snap-sync = true    # SNAP is the unit under test
do-fast-sync = false   # Disabled to isolate SNAP behavior
```

### Logging: Focused on SNAP and Capability Exchange
```xml
<!-- DEBUG: SNAP sync components (PRIMARY FOCUS) -->
<logger name="com.chipprbots.ethereum.blockchain.sync.snap" level="DEBUG" />

<!-- DEBUG: Handshake and capability exchange (CRITICAL) -->
<logger name="com.chipprbots.ethereum.network.handshaker" level="DEBUG" />

<!-- DEBUG: RLPx and message codec (SNAP message flow) -->
<logger name="com.chipprbots.ethereum.network.rlpx" level="DEBUG" />

<!-- INFO/WARN: Non-SNAP components (reduce noise) -->
<logger name="com.chipprbots.ethereum.blockchain.sync.fast" level="INFO" />
<logger name="com.chipprbots.scalanet" level="INFO" />

### Logging: Configurable for Testing Needs
```xml
<!-- DEBUG: SNAP sync components -->
<logger name="com.chipprbots.ethereum.blockchain.sync.snap" level="DEBUG" />

<!-- DEBUG: Handshake and capability exchange -->
<logger name="com.chipprbots.ethereum.network.handshaker" level="DEBUG" />

<!-- DEBUG: RLPx and message codec -->
<logger name="com.chipprbots.ethereum.network.rlpx" level="DEBUG" />

<!-- INFO/WARN: Other components -->
<logger name="com.chipprbots.ethereum.blockchain.sync.fast" level="INFO" />
<logger name="com.chipprbots.scalanet" level="INFO" />
```

### Timeouts: Standard Configuration
- `peer-response-timeout = 90.seconds`
- `snap-sync.request-timeout = 60.seconds`
- `blacklist-duration = 60.seconds`

## Usage

### Quick Start

You can manage Cirith-Ungol nodes using either the unified CLI or the local start.sh script:

#### Option 1: Unified CLI (Recommended)

```bash
# From repository root
./fukuii.sh start cirith-ungol
./fukuii.sh logs cirith-ungol
./fukuii.sh status cirith-ungol
./fukuii.sh stop cirith-ungol

# Sync static nodes (updates conf/static-nodes.json with all running node enodes)
./fukuii.sh sync-static-nodes cirith-ungol
```

#### Option 2: Local Script

```bash
cd ops/cirith-ungol

# Start the node
./start.sh start

# View logs (live)
./start.sh logs

# Stop the node
./start.sh stop
```

### Monitoring

#### Check Node Status
```bash
# Node status
curl http://localhost:8545/eth_syncing

# Container status
docker compose ps

# Core-Geth status
curl http://localhost:18545/eth_syncing
```

#### View Sync Progress
```bash
# SNAP sync progress
docker compose logs fukuii | grep "SNAP Sync Progress"

# General sync status
docker compose logs fukuii | grep -i "sync"

# Core-Geth state sync
docker compose logs coregeth | grep "Syncing"
```

### Key Monitoring Commands

#### 1. Peer Capability Exchange
```bash
# See what capabilities peers advertise
docker compose logs fukuii | grep "PEER_HANDSHAKE_SUCCESS"
```

#### 2. Sync Behavior
```bash
# Sync initialization
docker compose logs fukuii | grep -i "snap.*start\|snap.*init"

# Sync progress
docker compose logs fukuii | grep "SNAP Sync Progress"
```

#### 3. SNAP Messages
```bash
# See GetAccountRange requests being sent
docker compose logs fukuii | grep "GetAccountRange"

# Check for timeouts
docker compose logs fukuii | grep "SNAP request.*timeout"
```

#### 4. Capability Negotiation Details
```bash
# Detailed handshake logging
docker compose logs fukuii | grep -i "capability\|hello"

# Check if any peers support SNAP
docker compose logs fukuii | grep -i "snap/1\|snap.*protocol"
```

## What to Look For in Logs

### Key Observations

✅ **Peer handshakes complete** - Node can connect to peers  
✅ **Capability exchange logged** - See what peers advertise  
✅ **Sync processes start** - Sync implementations initialize  
✅ **Detailed logs captured** - Data available for analysis  

### Analysis Points

1. **Peer capabilities**
   - Look for advertised capabilities in handshake logs
   - Identify which sync protocols are supported

2. **Sync behavior**
   - How does the code handle different peer capabilities?
   - Are timeouts and retries working correctly?
   - Are error messages clear?

3. **Message formatting**
   - Check message encoding/decoding logs
   - Verify RLP serialization
   - Confirm message codes match protocol specs

4. **Timeout handling**
   - Peer disconnect reasons
   - Retry behavior
   - Blacklist reasons

## Troubleshooting

### If Logs Are Too Noisy

Check logging levels:
```bash
docker compose logs fukuii | grep "level=" | sort | uniq -c
```

### If Missing Sync Logs

Verify sync configuration:
```bash
docker compose exec fukuii cat /app/conf/etc.conf | grep do-snap-sync
docker compose exec fukuii cat /app/conf/etc.conf | grep do-fast-sync
```

### If No Peers Connect

Check network connectivity:
```bash
docker compose logs fukuii | grep "discovered nodes"
docker compose logs fukuii | grep "PEER_HANDSHAKE"
```

## Analysis After Run

After collecting logs, analyze:

1. **Capability Advertisement**
   ```bash
   grep "PEER_HANDSHAKE_SUCCESS" cirith-ungol.log | grep -o "Capability: [A-Z0-9]*" | sort | uniq -c
   ```

2. **SNAP Message Flow**
   ```bash
   grep -E "GetAccountRange|AccountRange.*response" cirith-ungol.log
   ```

3. **Timeout Patterns**
   ```bash
   grep "timeout" cirith-ungol.log | grep -i snap
   ```

4. **Disconnect Reasons**
   ```bash
   grep -i "disconnect\|blacklist" cirith-ungol.log
   ```

## Next Steps

Based on run 006 results:

### If Peers Don't Support SNAP (Expected)
- **Confirmed:** ETC mainnet doesn't use SNAP
- **Action:** Document this for future reference
- **Recommendation:** Use FastSync for actual ETC sync

### If Implementation Issues Found
- **Fix:** Address any SNAP protocol bugs
- **Test:** Re-run with fixes
- **Validate:** Confirm proper behavior

### If Peers DO Support SNAP (Unexpected)
- **Investigate:** Why run-005 didn't see it
- **Continue:** Let SNAP sync proceed
- **Monitor:** Track actual progress

## Related Documentation

- [Run 005 README](../run-005/README.md) - Previous SNAP attempt
- [Run 005 ISSUE_RESOLUTION](../run-005/ISSUE_RESOLUTION.md) - Decompression fix
- [SNAP Protocol Spec](https://github.com/ethereum/devp2p/blob/master/caps/snap.md)

## Conclusion

Cirith Ungol provides a flexible testing environment for validating Fukuii node behavior on ETC mainnet. The configuration supports comprehensive logging and monitoring to aid in debugging and analysis.

Success is measured by:
- Stable node operation
- Proper peer connectivity
- Expected sync behavior
- Clear, actionable logs for troubleshooting

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

## Lifecycle Management with Unified CLI

The Fukuii unified CLI provides consistent commands for managing Cirith-Ungol alongside other networks:

### Quick Start

```bash
# From repository root
./fukuii.sh start cirith-ungol

# View logs (live)
./fukuii.sh logs cirith-ungol

# Monitor FastSync progress
docker compose -f ops/cirith-ungol/docker-compose.yml logs fukuii | grep -i "fast\|pivot\|progress"

# Stop the node
./fukuii.sh stop cirith-ungol
```

Or use the local script:

```bash
cd ops/cirith-ungol

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
