# Testbed - SNAP Sync Protocol Validation Testing

## Issue ID
- **GitHub Issue**: #704
- **Title**: testbed - SNAP sync testing and protocol validation

## Purpose

Run 006 is a **SNAP protocol validation test**, not a production sync attempt. After run 005 showed zero SNAP sync progress, run 006 continues testing to:

1. **Validate SNAP implementation** - Confirm our SNAP protocol code works correctly
2. **Capture detailed logs** - Analyze capability exchange and message flows  
3. **Understand peer behavior** - Document why ETC peers don't advertise snap/1
4. **Test error handling** - Verify graceful handling of missing SNAP support

## Background from Run 005

Run 005 attempted SNAP sync on ETC mainnet but made zero progress:

```
21:25:54 - SNAP sync started
21:25:54 - accounts=0@0/s
21:27:15 - GetAccountRange requests sent to peers
21:27:45 - All requests timed out (no responses)
... pattern continued with 0 progress
```

**Key observations:**
- All connected peers advertised only "Capability: ETH68"
- No peers advertised "snap/1" capability
- 100% of GetAccountRange requests timed out
- Zero account responses received

## Investigation Summary

While run 005 logs suggest ETC mainnet peers don't support SNAP, we need to:

1. **Verify the finding** - Confirm capability exchange is working correctly
2. **Test our implementation** - Validate SNAP protocol message handling
3. **Capture diagnostics** - Get detailed logs for root cause analysis
4. **Document behavior** - Record how our code handles missing SNAP support

This is why run 006 **enables SNAP sync** with detailed logging, rather than switching to FastSync.

## Configuration for Run 006

### SNAP Sync: ENABLED (Primary Test Target)

```hocon
fukuii {
  sync {
    # SNAP sync is the unit under test
    do-snap-sync = true
    
    # Disable FastSync to isolate SNAP behavior
    do-fast-sync = false
    
    # Extended timeouts to capture full request lifecycle
    peer-response-timeout = 90.seconds
    
    snap-sync {
      request-timeout = 60.seconds
    }
  }
}
```

**Rationale:** We're testing SNAP protocol implementation, not trying to achieve sync.

### Logging: Focused on SNAP Protocol

```xml
<!-- PRIMARY FOCUS: SNAP sync components -->
<logger name="com.chipprbots.ethereum.blockchain.sync.snap" level="DEBUG" />
<logger name="com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController" level="DEBUG" />
<logger name="com.chipprbots.ethereum.blockchain.sync.snap.AccountRangeDownloader" level="DEBUG" />
<logger name="com.chipprbots.ethereum.blockchain.sync.snap.SNAPRequestTracker" level="DEBUG" />

<!-- CRITICAL: Capability exchange and handshake -->
<logger name="com.chipprbots.ethereum.network.handshaker" level="DEBUG" />
<logger name="com.chipprbots.ethereum.network.handshaker.EtcHelloExchangeState" level="DEBUG" />

<!-- IMPORTANT: RLPx message encoding/decoding -->
<logger name="com.chipprbots.ethereum.network.rlpx" level="DEBUG" />
<logger name="com.chipprbots.ethereum.network.rlpx.MessageCodec" level="DEBUG" />

<!-- REDUCED: Non-SNAP components to minimize noise -->
<logger name="com.chipprbots.ethereum.blockchain.sync.fast" level="INFO" />
<logger name="com.chipprbots.scalanet" level="INFO" />
```

**Rationale:** Focus logs on SNAP-related flows, suppress unrelated components.

## Expected Behavior (Test Success Criteria)

Since run 005 showed peers don't advertise snap/1, run 006 is expected to:

✅ **Connect to peers** - Network layer functions correctly  
✅ **Complete handshakes** - Capability exchange works  
✅ **Log peer capabilities** - Capture what peers advertise  
✅ **Initialize SNAP sync** - Our SNAP code starts correctly  
✅ **Send GetAccountRange** - SNAP messages are formatted properly  
✅ **Timeout gracefully** - Handle missing SNAP support correctly  
✅ **Generate detailed logs** - Provide data for analysis  

**Note:** Zero sync progress is EXPECTED for this test. We're validating implementation, not attempting to sync.

## Analysis Goals

Run 006 logs should answer:

### 1. Capability Advertisement
- What capabilities do ETC peers actually advertise?
- Do ANY peers advertise snap/1?
- Is capability negotiation working correctly in our code?

### 2. SNAP Message Handling
- Are GetAccountRange messages formatted per spec?
- Is RLP encoding correct?
- Are message codes matching SNAP protocol?

### 3. Error Handling
- How does our code handle missing SNAP support?
- Are timeout messages clear and actionable?
- Do we blacklist peers appropriately?

### 4. Protocol Implementation
- Does our SNAP code initialize correctly?
- Are request IDs managed properly?
- Is the request tracking working?

## Monitoring Commands

### Capture Peer Capabilities
```bash
docker compose logs fukuii | grep "PEER_HANDSHAKE_SUCCESS" > peer_capabilities.log

# Analyze capability distribution
grep -o "Capability: [A-Z0-9]*" peer_capabilities.log | sort | uniq -c
```

### Track SNAP Message Flow  
```bash
# See SNAP requests
docker compose logs fukuii | grep "GetAccountRange" > snap_requests.log

# Check for responses (likely none)
docker compose logs fukuii | grep "AccountRange.*response" > snap_responses.log

# Analyze timeouts
docker compose logs fukuii | grep "SNAP request.*timeout" > snap_timeouts.log
```

### Verify Handshake Details
```bash
# Detailed capability exchange
docker compose logs fukuii | grep -i "hello\|capability" > handshake_details.log
```

## Test Validation Checklist

After run 006 completes, verify:

- [ ] Peers connected successfully (5+ peers)
- [ ] Handshakes completed (PEER_HANDSHAKE_SUCCESS logged)
- [ ] Peer capabilities captured (ETH68, snap/1 status documented)
- [ ] SNAP sync initialized (controller started)
- [ ] GetAccountRange requests sent (messages logged)
- [ ] Timeouts occurred as expected (60s timeout logged)
- [ ] No unexpected errors (crashes, panics, etc.)
- [ ] Logs are analyzable (not too noisy, not missing key events)

## Comparison: Run 005 vs Run 006

| Aspect | Run 005 | Run 006 |
|--------|---------|---------|
| **SNAP Sync** | Enabled | Enabled |
| **Purpose** | Attempt sync | Test implementation |
| **SNAP Logging** | DEBUG | DEBUG |
| **Non-SNAP Logging** | DEBUG (all) | INFO/WARN (reduced) |
| **FastSync** | Disabled | Disabled |
| **Success = Sync?** | Yes | No - success = good logs |
| **Expected Progress** | Unknown | Zero (expected) |
| **Focus** | Making progress | Understanding behavior |

## Potential Outcomes

### Outcome 1: Peers Don't Support SNAP (Most Likely)

**Evidence:**
- All peers advertise only ETH68
- Zero snap/1 capabilities observed
- All GetAccountRange timeout

**Conclusion:**
- ETC mainnet doesn't use SNAP protocol
- Our implementation handles this correctly
- FastSync is appropriate for ETC sync

**Action:**
- Document finding for future reference
- Use FastSync for production ETC deployments
- Consider this test successful (implementation validated)

### Outcome 2: Implementation Issue Found

**Evidence:**
- Errors in SNAP message encoding
- Incorrect RLP serialization
- Protocol violations in logs

**Conclusion:**
- Bug in our SNAP implementation
- Needs fixing regardless of peer support

**Action:**
- Fix identified issues
- Re-test with corrections
- Validate against SNAP spec

### Outcome 3: Some Peers Support SNAP (Unexpected)

**Evidence:**
- >0% of peers advertise snap/1
- Some GetAccountRange responses received
- Partial sync progress

**Conclusion:**
- SNAP is partially available on ETC
- Need to investigate further

**Action:**
- Analyze which peers support SNAP
- Continue testing to see full behavior
- May enable actual sync progress

## Log Analysis Commands

After collecting run 006 logs:

```bash
# Capability distribution
grep "PEER_HANDSHAKE_SUCCESS" run006.log | \
  grep -o "Capability: [A-Z0-9]*" | \
  sort | uniq -c | sort -rn

# SNAP message count
echo "GetAccountRange sent:"
grep -c "GetAccountRange" run006.log

echo "AccountRange responses:"
grep -c "AccountRange.*response" run006.log

# Timeout analysis
grep "SNAP request.*timeout" run006.log | \
  awk '{print $NF}' | \
  sort | uniq -c

# Disconnect reasons
grep -i "disconnect\|blacklist" run006.log | \
  grep -o "Reason: [^\"]*" | \
  sort | uniq -c
```

## Success Criteria

Run 006 is successful if we obtain:

✅ Clear evidence of peer capability support (or lack thereof)  
✅ Detailed SNAP protocol message flows  
✅ Validation that our implementation handles the scenario correctly  
✅ Actionable insights for next steps  
✅ Clean logs focused on SNAP-related events  

Progress through sync is NOT required for success.

## Related Files

- `ops/run-006/conf/etc.conf` - SNAP sync enabled configuration
- `ops/run-006/conf/logback.xml` - SNAP-focused logging
- `ops/run-006/README.md` - Detailed testing guide

## References

- [SNAP Protocol Specification](https://github.com/ethereum/devp2p/blob/master/caps/snap.md)
- [Run 005 Logs](https://github.com/user-attachments/files/23917208/run005.log)
- [Core-Geth SNAP Implementation](https://github.com/etclabscore/core-geth/tree/master/eth/protocols/snap)

## Conclusion

Run 006 is configured as a SNAP protocol validation test. SNAP sync is ENABLED with detailed logging to:
- Validate our SNAP implementation works correctly
- Capture comprehensive logs for analysis
- Understand peer capability behavior on ETC mainnet
- Document how our code handles scenarios where peers don't support SNAP

Success is measured by log quality and implementation validation, not by sync progress.

**Status**: Ready for testing - configuration validates SNAP implementation.


## Issue ID
- **GitHub Issue**: Related to run 005 follow-up
- **Title**: testbed - SNAP sync investigation and FastSync configuration

## Problem Statement

Run 005 was configured to use SNAP sync on ETC mainnet but experienced complete stalling at the account retrieval phase:
- SNAP sync started successfully
- GetAccountRange requests were sent to peers
- **Zero responses were received** - all requests timed out
- Peers were blacklisted with generic "Some other reason specific to a subprotocol"
- Accounts downloaded: 0 (remained at 0 throughout entire run)
- No progress after hours of operation

## Investigation Process

### Step 1: Log Analysis

Analyzed run005.log to understand the failure pattern:

```
21:25:54 - SNAP sync started with concurrency 16
21:25:54 - Loading existing state trie
21:25:54 - SNAP Sync Progress: accounts=0@0/s
21:27:15 - SEND_MSG: type=GetAccountRange to 3 peers
21:27:45 - SNAP request timeout (30s later) - no response
21:27:45-50 - All 16 account range requests timeout
21:28:25 - Peers blacklisted with "Some other reason"
... pattern repeats indefinitely ...
```

**Key Observation**: Not a single GetAccountRange response was ever received.

### Step 2: Capability Negotiation Analysis

Examined peer handshake messages:

```
All peers consistently advertised only: Capability: ETH68
No peers advertised: snap/1
```

Sample from logs:
```
21:17:57 - PEER_HANDSHAKE_SUCCESS: Peer ...106:30303
           Capability: ETH68, BestHash: 147b0db4...
21:17:57 - PEER_HANDSHAKE_SUCCESS: Peer ...211:30303  
           Capability: ETH68, BestHash: 147b0db4...
21:17:57 - PEER_HANDSHAKE_SUCCESS: Peer ...245:30303
           Capability: ETH68, BestHash: 147b0db4...
```

**Finding**: Zero peers out of dozens discovered/connected advertised SNAP capability.

### Step 3: Fukuii Configuration Verification

Checked fukuii's capability advertisement:

```scala
// src/main/resources/conf/chains/etc-chain.conf
capabilities = ["eth/63", "eth/64", "eth/65", "eth/66", "eth/67", "eth/68", "snap/1"]
```

**Verified**: Fukuii correctly advertises snap/1 support.

### Step 4: Core-Geth Implementation Review

Examined core-geth (official ETC client) source code:

#### SNAP Protocol Implementation
```go
// eth/protocols/snap/protocol.go
const ProtocolName = "snap"
var ProtocolVersions = []uint{SNAP1}

// eth/backend.go  
func (s *Ethereum) Protocols() []p2p.Protocol {
    protos := eth.MakeProtocols(...)
    if s.config.SnapshotCache > 0 {  // ← Conditional registration
        protos = append(protos, snap.MakeProtocols(...))
    }
    return protos
}
```

#### Default Configuration
```go
// eth/ethconfig/config.go
var Defaults = Config{
    SyncMode: downloader.SnapSync,  // Default mode
    SnapshotCache: 102,               // Default > 0, enables SNAP
    ...
}
```

**Findings**:
- Core-geth DOES implement SNAP/1 protocol
- SNAP is enabled by default in core-geth (SnapshotCache: 102)
- Default sync mode is SnapSync
- **But**: Actual ETC mainnet nodes don't advertise it

### Step 5: Network Reality Check

**Conclusion**: While core-geth supports SNAP sync, the ETC mainnet network ecosystem has not widely adopted it.

Possible reasons:
1. **Operational Configurations**: Node operators may:
   - Set `SnapshotCache = 0` to save resources
   - Use older client versions without SNAP
   - Explicitly configure for FastSync/FullSync only

2. **Network Characteristics**:
   - ETC has smaller state than ETH mainnet
   - Less pressure to adopt newer sync methods
   - FastSync is "good enough" for ETC

3. **Deployment Practices**:
   - Not all nodes run latest core-geth
   - Some nodes may use alternative clients
   - Default configs vary by deployment method

## Root Cause

**ETC mainnet peers do not advertise or support SNAP/1 protocol in practice**, despite:
- Fukuii correctly implementing and advertising SNAP support
- Core-geth having SNAP implementation available
- SNAP being theoretically supported

This creates a situation where:
1. Fukuii attempts SNAP sync (do-snap-sync = true)
2. No peers advertise snap/1 capability
3. Fukuii sends GetAccountRange requests anyway (over ETH68 subprotocol)
4. Peers don't understand SNAP messages on ETH68 connection
5. Peers disconnect or ignore messages
6. All requests timeout
7. No progress is possible

## Solution: Disable SNAP, Enable FastSync

### Configuration Changes

```hocon
# ops/run-006/conf/etc.conf

fukuii {
  sync {
    # CRITICAL: Disable SNAP sync for ETC mainnet
    # Peers do not support snap/1 protocol
    do-snap-sync = false

    # ENABLE: FastSync is the appropriate sync method for ETC
    # All peers support ETH63-ETH68 which includes FastSync
    do-fast-sync = true
    
    # Keep proven timeout settings from run-005
    peer-response-timeout = 90.seconds
    blacklist-duration = 60.seconds
  }
}
```

### Why FastSync is Correct for ETC

1. **Universal Peer Support**
   - All ETC peers advertise ETH63-ETH68
   - FastSync protocol is part of ETH protocol family
   - Works with discovered peers immediately

2. **Proven Reliability**
   - FastSync used on ETC for years
   - Battle-tested on ETC mainnet
   - Known to work with core-geth and other clients

3. **Adequate Performance**
   - ETC state size is manageable (~50-100GB range)
   - FastSync can sync ETC in reasonable time
   - No critical need for SNAP's additional speed

4. **Protocol Compatibility**
   - GetBlockHeaders: ✅ Supported
   - GetBlockBodies: ✅ Supported
   - GetReceipts: ✅ Supported
   - GetNodeData: ✅ Supported (for state sync)

## Testing Plan for Run 006

### Deploy Configuration

1. Use run-006 configuration with FastSync enabled
2. Monitor logs for FastSync initialization
3. Verify pivot block selection occurs
4. Confirm state download begins
5. Track sync progress to completion

### Success Criteria

- [ ] Pivot block selected within first 5 minutes
- [ ] State download begins and progresses
- [ ] Block download occurs in parallel
- [ ] Peers remain connected (avg > 60s, not ~15s)
- [ ] Steady progress messages logged
- [ ] FastSync completes successfully
- [ ] Node transitions to regular sync

### Monitoring Commands

```bash
# FastSync initialization
docker compose logs fukuii | grep -i "fast.*sync.*start\|pivot"

# State download progress
docker compose logs fukuii | grep -i "state.*download\|state.*node"

# Block download progress  
docker compose logs fukuii | grep -i "block.*download\|downloaded.*block"

# Overall sync progress
docker compose logs fukuii | grep -i "progress\|sync.*complete"

# Peer stability
docker compose logs fukuii | grep "PEER_HANDSHAKE_SUCCESS\|Blacklisting" | tail -20
```

## Expected Behavior

### Before (Run 005 with SNAP)
```
T+0m:   SNAP sync starts
T+1m:   GetAccountRange requests sent
T+1.5m: All requests timeout (no SNAP-capable peers)
T+2m:   Retry with same result
T+Xm:   No progress, accounts=0, stuck forever
```

### After (Run 006 with FastSync)
```
T+0m:   FastSync starts
T+1m:   Pivot block selected from peer consensus
T+2m:   State download begins
T+5m:   State: 10% complete, Block: starting
T+30m:  State: 50% complete, Block: 40% complete
T+60m:  State: 90% complete, Block: 80% complete
T+90m:  FastSync complete, transitioning to regular sync
```

## Comparison Table

| Metric | Run 005 (SNAP) | Run 006 (FastSync) |
|--------|----------------|---------------------|
| Peer capability needed | snap/1 | eth/63-68 |
| Peers advertising | 0% | 100% |
| Request success rate | 0% | Expected: >90% |
| Sync progress | 0 accounts | Expected: steady |
| Time to complete | ∞ (never) | Expected: <2 hours |
| Peer connections | ~15s avg | Expected: >60s avg |
| Blacklist reasons | Generic "Other" | Specific (if any) |

## Recommendations

### For ETC Mainnet Deployments

**DO**:
- ✅ Use FastSync (do-fast-sync = true)
- ✅ Disable SNAP sync (do-snap-sync = false)
- ✅ Configure reasonable timeouts (90s peer, 60s request)
- ✅ Use bootstrap checkpoints if available
- ✅ Monitor peer capabilities in logs

**DON'T**:
- ❌ Enable SNAP sync on ETC mainnet (no peer support)
- ❌ Assume protocol support based on client implementation alone
- ❌ Skip verification of actual network behavior
- ❌ Use aggressive timeouts (<60s)

### Configuration Template

Recommended fukuii configuration for ETC mainnet:

```hocon
fukuii {
  blockchains {
    network = "etc"
  }
  
  sync {
    # FastSync is the proven sync method for ETC mainnet
    do-fast-sync = true
    
    # SNAP sync is not supported by ETC mainnet peers
    do-snap-sync = false
    
    # Reasonable timeouts for ETC network
    peer-response-timeout = 90.seconds
    blacklist-duration = 60.seconds
    critical-blacklist-duration = 30.minutes
  }
  
  network {
    peer {
      short-blacklist-duration = 2.minutes
      long-blacklist-duration = 30.minutes
    }
  }
}
```

## Future Considerations

### When to Re-Enable SNAP on ETC

SNAP sync could become viable on ETC mainnet if:

1. **Peer Adoption**: >20% of ETC nodes advertise snap/1
2. **Client Updates**: ETC node operators upgrade to SNAP-enabled versions
3. **Network Growth**: ETC state grows large enough to warrant SNAP's speed
4. **Community Coordination**: ETC community actively promotes SNAP adoption

### Monitoring for SNAP Viability

To check if SNAP becomes viable in the future:

```bash
# Check peer capabilities during handshake
docker compose logs fukuii | grep "PEER_HANDSHAKE_SUCCESS" | grep -c "snap"

# Calculate adoption percentage
# If > 20% of peers advertise snap/1, consider testing SNAP sync
```

## Lessons Learned

1. **Verify Network Reality**
   - Protocol implementation != network adoption
   - Always test against actual network before deployment
   - Monitor real peer behavior, not just specs

2. **Capability Negotiation Matters**
   - Log peer capabilities during handshake
   - Design fallback strategies for missing capabilities
   - Don't send protocol messages peers don't support

3. **Network-Specific Configuration**
   - ETC mainnet ≠ Ethereum mainnet
   - Different networks have different maturity levels
   - One size does not fit all

4. **Importance of Monitoring**
   - Log detailed handshake information
   - Track protocol message success rates
   - Alert on unusual timeout patterns

## Related Files

- `ops/run-006/conf/etc.conf` - FastSync configuration
- `ops/run-006/README.md` - Detailed documentation
- `ops/run-005/ISSUE_RESOLUTION.md` - Previous decompression issue
- `src/main/resources/conf/chains/etc-chain.conf` - ETC capabilities

## References

- [SNAP Protocol Specification](https://github.com/ethereum/devp2p/blob/master/caps/snap.md)
- [Core-Geth Repository](https://github.com/etclabscore/core-geth)
- [Core-Geth SNAP Implementation](https://github.com/etclabscore/core-geth/tree/master/eth/protocols/snap)
- [FastSync Documentation](https://github.com/ethereum/devp2p/blob/master/caps/eth.md)

## Conclusion

Run 005 demonstrated that SNAP sync is not currently viable on ETC mainnet due to lack of peer support, despite being technically implemented in client software. Run 006 addresses this by switching to FastSync, which is universally supported by ETC mainnet peers and should enable successful synchronization.

**Status**: Configuration ready for deployment and testing in run-006.
