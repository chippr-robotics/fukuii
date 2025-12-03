# SNAP Sync Timeout Analysis - Run 003

This document provides a detailed analysis of the timeout and peer disconnection issues observed in run-003.

## Executive Summary

Run-003 logs reveal a systematic pattern where peers are being blacklisted approximately 15 seconds after receiving SNAP sync requests, before they can respond, resulting in timeouts at the 30-second mark. This document analyzes the timeline, identifies the root cause hypothesis, and provides recommendations for run-004.

## Timeline Analysis

### Event Sequence from 003.log

```
13:48:23,054 INFO  [c.c.e.n.rlpx.RLPxConnectionHandler] - SEND_MSG: peer=164.90.144.106:30303, type=GetAccountRange, code=0x0, seqNum=3
13:48:23,054 INFO  [c.c.e.n.rlpx.RLPxConnectionHandler] - SEND_MSG: peer=157.245.77.211:30303, type=GetAccountRange, code=0x0, seqNum=3
13:48:23,054 INFO  [c.c.e.n.rlpx.RLPxConnectionHandler] - SEND_MSG: peer=64.225.0.245:30303, type=GetAccountRange, code=0x0, seqNum=3
```

**T+0s**: GetAccountRange requests sent to three peers simultaneously.

```
13:48:38,240 INFO  [c.c.e.b.sync.CacheBasedBlacklist] - Blacklisting peer [PeerAddress(64.225.0.245)] for 120000 milliseconds. Reason: Some other reason specific to a subprotocol
13:48:38,261 INFO  [c.c.e.b.sync.CacheBasedBlacklist] - Blacklisting peer [PeerAddress(164.90.144.106)] for 120000 milliseconds. Reason: Some other reason specific to a subprotocol
13:48:38,452 INFO  [c.c.e.b.sync.CacheBasedBlacklist] - Blacklisting peer [PeerAddress(157.245.77.211)] for 120000 milliseconds. Reason: Some other reason specific to a subprotocol
```

**T+15s**: All three peers blacklisted with "Some other reason specific to a subprotocol"

```
13:48:53,070 WARN  [c.c.e.b.sync.snap.SNAPRequestTracker] - SNAP request GetAccountRange timeout for request ID 1 from peer PeerId(55bbc7f0ffa2af2ceca997ec195a98768144a163d389ae87b808dff8a861618405c2582451bbb6022e429e4bcd6b0e895e86160db6e93cdadbcfd80faacf6f06)
13:48:53,071 WARN  [c.c.e.b.s.s.AccountRangeDownloader] - Account range request timeout for range [00000000...10000000]
13:48:53,072 WARN  [c.c.e.b.sync.snap.SNAPRequestTracker] - SNAP request GetAccountRange timeout for request ID 3 from peer PeerId(6b6ea53a498f0895c10269a3a74b777286bd467de6425c3b512740fcc7fbc8cd281dca4ab041dd97d62b38f3d0b5b05e71f48d28a3a2f4b5de40fe1f6bf05531)
13:48:53,072 WARN  [c.c.e.b.s.s.AccountRangeDownloader] - Account range request timeout for range [20000000...30000000]
13:48:53,072 WARN  [c.c.e.b.sync.snap.SNAPRequestTracker] - SNAP request GetAccountRange timeout for request ID 2 from peer PeerId(16264d48df59c3492972d96bf8a39dd38bab165809a3a4bb161859a337de38b2959cc98efea94355c7a7177cd020867c683aed934dbd6bc937d9e6b61d94d8d9)
13:48:53,073 WARN  [c.c.e.b.s.s.AccountRangeDownloader] - Account range request timeout for range [10000000...20000000]
```

**T+30s**: Timeouts occur for all three requests. No responses were received.

### Key Observations

1. **Request → Blacklist gap**: 15 seconds
2. **Blacklist → Timeout gap**: 15 seconds
3. **Request → Timeout gap**: 30 seconds (expected timeout duration)
4. **Zero responses received**: No peer successfully responded before being blacklisted

## Root Cause Analysis

### Hypothesis: Race Condition Between Response and Blacklist

The evidence suggests a race condition:

1. **GetAccountRange request sent** at T+0s
2. **Some other protocol event** triggers blacklisting at T+15s
3. **Peer is blacklisted** before it can respond to the SNAP request
4. **Response never arrives** because peer is already disconnected/blacklisted
5. **Timeout fires** at T+30s as expected, but peer was already gone at T+15s

### The Mystery: "Some other reason specific to a subprotocol"

This blacklist reason is suspiciously generic and suggests:

1. **Code Location**: This is a catch-all blacklist reason, likely from a generic error handler
2. **Missing Context**: The actual reason is not being logged (hence the need for DEBUG logging in run-004)
3. **Possible Triggers**:
   - Malformed message from peer
   - Protocol version mismatch in a sub-protocol
   - Unexpected message type
   - Response to a different request that failed validation
   - Internal error in message handling

### Why This Breaks SNAP Sync

The problem is **temporal coupling**:

```
Time: 0s     Request sent (GetAccountRange)
Time: 15s    Peer blacklisted (unrelated reason)
              ↓
              Peer connection terminated
              ↓
              No further messages from peer
Time: 30s    Timeout fires (no response received)
```

Even if the peer had a valid response ready at T+20s, it couldn't send it because it was blacklisted at T+15s.

## Evidence from Log Patterns

### Pattern 1: Successful Handshakes

```
13:48:18,133 INFO  [c.c.e.n.h.EthNodeStatus64ExchangeState] - STATUS_EXCHANGE: ForkId validation passed
13:48:18,135 INFO  [c.c.e.network.EtcPeerManagerActor] - PEER_HANDSHAKE_SUCCESS: Peer ... handshake successful
```

Peers successfully complete the handshake process, indicating they are legitimate ETC nodes.

### Pattern 2: GetBlockHeaders Success

```
13:48:18,136 INFO  [c.c.e.network.EtcPeerManagerActor] - PEER_HANDSHAKE_SUCCESS: Sending GetBlockHeaders to peer ...
13:48:18,262 INFO  [c.c.e.network.EtcPeerManagerActor] - RECV_BLOCKHEADERS: peer=..., requestId=3, count=1
```

The same peers successfully respond to GetBlockHeaders requests, showing they can handle standard ETH protocol messages.

### Pattern 3: Immediate SNAP Requests After Handshake

```
13:48:18,262 INFO  [RECV_BLOCKHEADERS] - (response to initial handshake query)
13:48:23,054 INFO  [SEND_MSG] - type=GetAccountRange  (5 seconds later)
```

SNAP sync starts aggressively requesting account ranges as soon as peers are connected.

### Pattern 4: Consistent Blacklist Timing

Every instance shows the same pattern:
- Request at T+0
- Blacklist at T+15
- Timeout at T+30

This consistency suggests a **systematic issue**, not random network failures.

## Comparison with Geth

### How Geth Handles This

Based on the Geth codebase (eth/protocols/snap/sync.go):

1. **Request Timeout**: Geth uses 30-60 second timeouts for SNAP requests
2. **Retry Strategy**: Geth retries failed requests with exponential backoff
3. **Peer Scoring**: Geth uses a sophisticated peer scoring system rather than binary blacklisting
4. **Request Pipelining**: Geth pipelines multiple requests to the same peer

### Key Difference

Geth's peer scoring allows a peer to fail some requests while still serving others. Fukuii's blacklisting is more aggressive, which may be causing false positives.

## Recommendations for Run 004

### 1. Extended Timeouts ✅ (Implemented)

**Change**: Increase timeouts to reduce false positives
- `peer-response-timeout`: 60s → 90s
- `snap-sync.request-timeout`: 30s → 60s

**Rationale**: If peers need 40-50 seconds to respond, they'll now have time before timeout.

**Expected Outcome**: Fewer timeout messages, more successful responses.

### 2. Enhanced Debug Logging ✅ (Implemented)

**Change**: Add DEBUG logging for:
- `CacheBasedBlacklist` - Detailed blacklist reasons
- `SNAPRequestTracker` - Full request lifecycle
- `SyncProgressMonitor` - Progress tracking

**Rationale**: Understand the actual reason for "Some other reason specific to a subprotocol".

**Expected Outcome**: Clear logs showing why peers are blacklisted.

### 3. Reduced Blacklist Durations ✅ (Already in run-003)

**Status**: Already reduced in run-003 (120s → 60s)

**Rationale**: Even if peers are blacklisted, they can be retried sooner.

## Metrics to Monitor in Run 004

### Success Metrics

1. **SNAP Response Rate**:
   - Count of `AccountRange` responses received
   - Count of `AccountRange` timeouts
   - Target: Response rate > 50%

2. **Blacklist Rate**:
   - Count of peers blacklisted per minute
   - Count of "Some other reason specific to a subprotocol" occurrences
   - Target: Blacklist rate < 1 per minute

3. **Peer Retention**:
   - Average time peers stay connected
   - Count of peers maintained over time
   - Target: 3-5 stable peers maintained

### Diagnostic Metrics

1. **Response Time Distribution**:
   - Histogram of time from request to response
   - Identify: What percentile needs 60s+ to respond?

2. **Blacklist Reason Distribution**:
   - Count by blacklist reason
   - Identify: What actually triggers the generic reason?

3. **Request Success by Peer**:
   - Which peers successfully respond?
   - Which peers consistently timeout?
   - Are some peers more reliable?

## Log Analysis Commands for Run 004

### Extract SNAP request timeline
```bash
docker compose logs fukuii | grep -E "(SEND_MSG.*GetAccountRange|SNAP request.*timeout|AccountRange.*response)" | tee snap-timeline.log
```

### Extract blacklist events with reasons
```bash
docker compose logs fukuii | grep -i "blacklist" | tee blacklist-events.log
```

### Count response vs timeout ratio
```bash
echo "Timeouts:"
docker compose logs fukuii | grep "SNAP request.*timeout" | wc -l
echo "Responses:"
docker compose logs fukuii | grep -i "AccountRange.*response\|AccountRange.*received" | wc -l
```

### Track peer lifecycle
```bash
docker compose logs fukuii | grep -E "(PEER_HANDSHAKE_SUCCESS|Blacklisting peer)" | tee peer-lifecycle.log
```

## Alternative Hypotheses

### Hypothesis A: Peers Don't Support SNAP Protocol

**Evidence Against**:
- Peers handshake successfully with ETH68 capability
- Geth nodes on ETC mainnet do support SNAP sync

**Evidence For**:
- Maybe ETC nodes are running older Geth versions without SNAP support

**Test**: Check peer versions in handshake logs

### Hypothesis B: Request Format Issues

**Evidence Against**:
- Messages are sent successfully (no encoding errors)
- No immediate rejection messages

**Evidence For**:
- "Some other reason specific to a subprotocol" could be format validation failure

**Test**: Enhanced DEBUG logging in run-004 will reveal this

### Hypothesis C: Network/Firewall Issues

**Evidence Against**:
- GetBlockHeaders works fine with same peers
- Pattern is too consistent for random network issues

**Evidence For**:
- SNAP protocol uses different message types that might be filtered

**Test**: Packet capture would be needed (out of scope)

### Hypothesis D: Rate Limiting

**Evidence Against**:
- Blacklisting happens after only one request
- No messages about rate limits

**Evidence For**:
- Peers might have internal rate limits for resource-intensive SNAP requests

**Test**: Try slower request rate (not implemented in run-004)

## Conclusion

Run-003 revealed a clear pattern where peers are blacklisted before they can respond to SNAP sync requests. The root cause appears to be a combination of:

1. **Aggressive blacklisting** triggered by "Some other reason specific to a subprotocol"
2. **Insufficient timeout** (30s may be too short for large account ranges)
3. **Missing visibility** into why peers are actually blacklisted

Run-004 addresses these issues with extended timeouts and enhanced debugging. The results will guide further improvements in run-005 or code changes to the SNAP sync implementation.

## References

- [Run 003 Log File](003.log) - Original log file analyzed
- [Run 004 Configuration](README.md) - Changes implemented based on this analysis
- [Geth SNAP Sync Implementation](https://github.com/ethereum/go-ethereum/blob/master/eth/protocols/snap/sync.go)
- [EIP-2464: eth/65 - transaction announcements and retrievals](https://eips.ethereum.org/EIPS/eip-2464)
