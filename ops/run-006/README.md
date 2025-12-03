# Run 006 Configuration - SNAP Sync Testing

This configuration continues SNAP sync testing from run-005 to validate the SNAP protocol implementation and analyze peer capability exchange behavior.

## Purpose

**Run 006 is a SNAP protocol validation test**, not a production sync attempt. The goal is to:

1. **Test SNAP implementation** - Validate our SNAP protocol code
2. **Capture detailed logs** - Analyze capability exchange and SNAP message flows
3. **Investigate peer behavior** - Understand why ETC peers don't advertise snap/1
4. **Debug protocol issues** - Identify any implementation problems
5. **Compare with core-geth** - Run core-geth alongside for SNAP sync comparison

## Architecture

Run 006 deploys **two nodes** for comparison:

### Fukuii Node
- Our implementation under test
- SNAP sync enabled with detailed logging
- Focus: Validate SNAP protocol implementation
- Ports: 8545 (HTTP), 8546 (WS), 30303 (P2P)

### Core-Geth Node
- Reference implementation (official ETC client)
- SNAP sync enabled (`--syncmode=snap`)
- Purpose: Baseline comparison for SNAP behavior
- Ports: 8555 (HTTP), 8556 (WS), 30313 (P2P)
- Data: Stored in `ops/data/geth/` (gitignored)

This allows us to compare:
- Whether core-geth encounters the same issues with SNAP
- How core-geth handles peers without snap/1 support
- Message flows between the two implementations
- Capability negotiation differences

## Background from Run 005

Run 005 attempted SNAP sync but made zero progress:
- All GetAccountRange requests timed out with no responses
- ETC mainnet peers only advertised "Capability: ETH68", not "snap/1"
- SNAP sync remained at 0 accounts throughout the entire run

While this suggests ETC peers don't support SNAP, we need detailed logs to:
- Verify the capability negotiation flow
- Confirm SNAP messages are being sent correctly
- Understand peer disconnect reasons
- Validate our SNAP protocol implementation

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
```

### Timeouts: Same as Run 005
- `peer-response-timeout = 90.seconds`
- `snap-sync.request-timeout = 60.seconds`
- `blacklist-duration = 60.seconds`

## Expected Behavior

Since run 005 showed peers don't advertise snap/1:

**Expected:** SNAP sync will likely make zero progress again
**Goal:** Capture detailed logs showing:
- Peer Hello messages (which capabilities they advertise)
- SNAP sync initialization
- GetAccountRange requests being sent
- Request timeouts (since peers don't support SNAP)
- Peer disconnect events and reasons

This is **expected behavior for a test** - we're validating that our implementation correctly handles peers without SNAP support.

## Usage

### Quick Start

```bash
cd ops/run-006

# Start both nodes (fukuii and core-geth)
./start.sh start

# View logs (live)
./start.sh logs

# View specific node logs
docker compose logs -f fukuii      # Fukuii node only
docker compose logs -f core-geth   # Core-geth node only

# Stop both nodes
./start.sh stop
```

### Monitoring Both Nodes

#### Check Node Status
```bash
# Fukuii status
curl http://localhost:8545/eth_syncing

# Core-geth status
curl -X POST -H "Content-Type: application/json" \
  --data '{"jsonrpc":"2.0","method":"eth_syncing","params":[],"id":1}' \
  http://localhost:8555

# Both node containers
docker compose ps
```

#### Compare Sync Behavior
```bash
# Fukuii SNAP progress
docker compose logs fukuii | grep "SNAP Sync Progress"

# Core-geth sync status (check if it progresses with SNAP)
docker compose logs core-geth | grep -i "snap\|sync"
```

### Key Monitoring Commands

#### 1. Peer Capability Exchange (Fukuii)
```bash
# See what capabilities peers advertise to fukuii
docker compose logs fukuii | grep "PEER_HANDSHAKE_SUCCESS"

# Should show peers advertising only ETH68, not snap/1
```

#### 2. SNAP Sync Behavior (Both Nodes)
```bash
# Fukuii SNAP sync initialization
docker compose logs fukuii | grep -i "snap.*start\|snap.*init"

# Fukuii SNAP progress (likely 0)
docker compose logs fukuii | grep "SNAP Sync Progress"

# Core-geth SNAP behavior (compare)
docker compose logs core-geth | grep -i "snap"
```

#### 3. SNAP Messages (Fukuii)
```bash
# See GetAccountRange requests being sent
docker compose logs fukuii | grep "GetAccountRange"

# Check for timeouts
docker compose logs fukuii | grep "SNAP request.*timeout"
```

#### 4. Core-Geth Comparison
```bash
# Check if core-geth makes SNAP progress
docker compose logs core-geth | tail -100

# Check core-geth peer count
docker compose exec core-geth geth attach --exec "admin.peers.length"

# Check core-geth sync status
docker compose exec core-geth geth attach --exec "eth.syncing"
```

#### 5. Capability Negotiation Details
```bash
# Detailed handshake logging
docker compose logs fukuii | grep -i "capability\|hello"

# Check if any peers support SNAP
docker compose logs fukuii | grep -i "snap/1\|snap.*protocol"
```

## What to Look For in Logs

### Success Criteria (for this test)

✅ **Peer handshakes complete** - We can connect to peers  
✅ **Capability exchange logged** - We see what peers advertise  
✅ **SNAP sync starts** - Our SNAP implementation initializes  
✅ **GetAccountRange sent** - Our SNAP messages are formatted correctly  
✅ **Detailed logs captured** - We have data for analysis  

### Analysis Points

1. **Do peers advertise snap/1?**
   - Look for "snap/1" or "SNAP1" in capability lists
   - If not found, confirms run-005 observation

2. **How does our code handle missing SNAP support?**
   - Does it gracefully timeout?
   - Are error messages clear?
   - Do we blacklist peers appropriately?

3. **Are SNAP messages formatted correctly?**
   - Check message encoding/decoding logs
   - Verify RLP serialization
   - Confirm message codes match SNAP spec

4. **What happens at timeout?**
   - Peer disconnect reasons
   - Retry behavior
   - Blacklist reasons

## Comparison with Run 005

| Aspect | Run 005 | Run 006 |
|--------|---------|---------|
| **SNAP Sync** | Enabled | Enabled (same) |
| **Purpose** | Try to sync | Test SNAP implementation |
| **SNAP Logging** | DEBUG | DEBUG (same) |
| **Non-SNAP Logging** | DEBUG | INFO/WARN (reduced) |
| **FastSync** | Disabled | Disabled (same) |
| **Expected Result** | Zero progress | Zero progress (expected) |
| **Success Metric** | Sync completes | Detailed logs captured |

## Troubleshooting

### If Logs Are Too Noisy

Check that non-SNAP components are at INFO or WARN:
```bash
docker compose logs fukuii | grep "level=" | sort | uniq -c
```

### If Missing SNAP Logs

Verify SNAP sync is enabled:
```bash
docker compose exec fukuii cat /app/conf/etc.conf | grep do-snap-sync
# Should show: do-snap-sync = true
```

### If No Peers Connect

Check network connectivity:
```bash
docker compose logs fukuii | grep "discovered nodes"
docker compose logs fukuii | grep "PEER_HANDSHAKE"
```

## Analysis After Run

After collecting logs from run 006, analyze:

1. **Capability Advertisement**
   ```bash
   grep "PEER_HANDSHAKE_SUCCESS" run006.log | grep -o "Capability: [A-Z0-9]*" | sort | uniq -c
   ```

2. **SNAP Message Flow**
   ```bash
   grep -E "GetAccountRange|AccountRange.*response" run006.log
   ```

3. **Timeout Patterns**
   ```bash
   grep "timeout" run006.log | grep -i snap
   ```

4. **Disconnect Reasons**
   ```bash
   grep -i "disconnect\|blacklist" run006.log
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

Run 006 is a **test configuration** to validate SNAP protocol implementation and understand peer behavior. Success is measured by capturing detailed logs for analysis, not by achieving sync progress.

The configuration enables SNAP sync with comprehensive logging focused on:
- Peer capability exchange
- SNAP protocol messages
- Request/response flows
- Timeout and error handling

This allows thorough analysis of why SNAP sync doesn't progress on ETC mainnet and validates that our implementation handles this scenario correctly.


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
