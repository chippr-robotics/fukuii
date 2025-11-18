# Block Synchronization Troubleshooting Guide

## Issue Summary

This document provides troubleshooting steps for resolving block synchronization failures where the node cannot establish stable peer connections.

## Symptoms Observed

Based on the log analysis from production deployment:

1. **Peer Connection Pattern**:
   - Node discovers peers successfully (29 discovered nodes)
   - TCP connections establish successfully to ETH68 peers
   - RLPx auth handshake completes successfully
   - Protocol handshake with ETH68 (CoreGeth v1.12.20) completes
   - Status message exchange occurs
   - **Peers immediately disconnect with reason code 0x10**
   - All peers get blacklisted for 360 seconds
   - Node left with 0 available peers for synchronization

2. **Log Patterns**:
   ```
   INFO  [c.c.ethereum.network.PeerActor] - DISCONNECT_DEBUG: Received disconnect from <ip>:30303 - reason code: 0x10 (Some other reason specific to a subprotocol)
   INFO  [c.c.e.b.sync.CacheBasedBlacklist] - Blacklisting peer [PeerAddress(<ip>)] for 360000 milliseconds
   DEBUG [c.c.e.blockchain.sync.PeersClient] - No suitable peer found to issue a request (handshaked: 0, available: 0)
   ```

3. **Continuous Retry Loop**:
   - HeadersFetcher continuously retries fetching headers
   - No suitable peers available
   - Sync cannot proceed

## Root Cause: ForkId Mismatch

### Analysis

The disconnect reason code `0x10` ("Some other reason specific to a subprotocol") indicates the peer is rejecting our node due to an ETH protocol-specific incompatibility.

**Key Evidence from Logs**:

Our node sends:
```
forkId=ForkId(0xfc64ec04, Some(1150000))
```

Peer responds with:
```
forkId=ForkId(0xbe46d57c, None)
```

**ForkId Explanation**:
- ForkId is a mechanism defined in [EIP-2124](https://eips.ethereum.org/EIPS/eip-2124) to identify chain compatibility
- It's calculated as a CRC32 checksum of the genesis hash + all past fork block numbers
- The `next` field indicates the next upcoming fork block number
- Peers use ForkId to quickly reject incompatible chains without syncing

**Our ForkId** (`0xfc64ec04`, next: 1150000):
- Suggests our node is missing recent fork configurations
- The `next` value of 1150000 is very old (DAO fork era)
- Indicates blockchain configuration is outdated or incomplete

**Peer's ForkId** (`0xbe46d57c`, next: None):
- This is the correct ForkId for current ETC mainnet
- `None` for next means no upcoming forks are scheduled
- Peers running CoreGeth v1.12.20 have complete fork history

### Why Peers Disconnect

When our node sends an incorrect ForkId:
1. Peer receives our Status message with ForkId `0xfc64ec04`
2. Peer validates our ForkId against their chain configuration
3. Peer determines we're on an incompatible/outdated chain
4. Peer sends Disconnect with reason 0x10
5. Our node blacklists the peer (incorrectly, as the issue is on our side)
6. Process repeats with all peers

## Solution

### Comparison with Core-Geth Implementation

After comparing with the [core-geth reference implementation](https://github.com/etclabscore/core-geth), the issue has been identified:

**Core-Geth ForkID Test Cases for ETC Classic:**
```go
// From core-geth core/forkid/forkid_test.go
{19_250_000, 0, ID{Hash: checksumToBytes(0xbe46d57c), Next: 0}}, // Spiral fork and beyond
```

**Our Configuration Analysis:**

✅ **Genesis Hash**: Correct (`d4e56740f876aef8c010b86a40d5f56745a118d0906a34e69aec8c0db1cb8fa3`)

✅ **Fork Blocks Configured** (from `src/main/resources/conf/chains/etc-chain.conf`):
- Homestead: 1,150,000
- EIP-150: 2,500,000
- EIP-155/160: 3,000,000
- Atlantis: 8,772,000
- Agharta: 9,573,000
- Phoenix: 10,500,839
- ECIP-1099: 11,700,000
- Magneto: 13,189,133
- Mystique: 14,525,000
- **Spiral: 19,250,000** ✅

✅ **Code Implementation**: ForkId calculation logic matches core-geth (CRC32 of genesis + fork blocks)

### Root Cause: Synced Block Height vs ForkId

The ForkId `0xfc64ec04` with `next: 1150000` indicates:
- Our node is at **block 0** (unsynced)
- Next fork is Homestead at block 1,150,000
- This matches core-geth's expected behavior for an **unsynced node**

From core-geth test cases:
```go
{0, 0, ID{Hash: checksumToBytes(0xfc64ec04), Next: 1150000}}, // Unsynced - MATCHES OUR NODE
```

The peers with ForkID `0xbe46d57c, Next: 0` are **fully synced** (beyond block 19,250,000).

**The issue is NOT a configuration error** - our ForkId is correct for block 0!

### Why Peers Disconnect

Peers may be disconnecting due to:

1. **Overly Strict ForkId Validation**: Some peer implementations may reject nodes that are "too far behind"
2. **Configuration Mismatch Detection**: Peers might be detecting subtle differences in fork configuration
3. **Network Segmentation**: Temporary network conditions causing validation failures

### Option 1: Verify ForkId Calculation Matches Core-Geth

The configuration is already correct. To verify the ForkId calculation at various block heights:

```bash
# Expected ForkIds at different sync stages (from core-geth):
Block 0:          0xfc64ec04, next: 1150000    (Frontier)
Block 1,150,000:  0x97c2c34c, next: 2500000    (Homestead)
Block 2,500,000:  0x250c3c6a, next: 3000000    (EIP-150)
Block 3,000,000:  0x43ea6b9e, next: 8772000    (EIP-155/160)
Block 8,772,000:  0x13d96d70, next: 9573000    (Atlantis)
Block 9,573,000:  0xef35b156, next: 10500839   (Agharta)
Block 10,500,839: 0x9007bfcc, next: 11700000   (Phoenix)
Block 11,700,000: 0xdb63a1ca, next: 13189133   (ECIP-1099)
Block 13,189,133: 0x0f6bf187, next: 14525000   (Magneto)
Block 14,525,000: 0x7fd1bb25, next: 19250000   (Mystique)
Block 19,250,000: 0xbe46d57c, next: 0          (Spiral - fully synced)
```

### Option 2: Investigate Peer Compatibility

Since our ForkId is correct for block 0, investigate why peers reject us:

1. **Check Peer Implementation**: Verify the peer software versions accepting connections
2. **Network Conditions**: Ensure stable network connectivity to bootstrap nodes
3. **Firewall Rules**: Verify TCP/UDP ports 30303 and 9076 are properly configured
4. **DNS Resolution**: Ensure bootstrap node addresses resolve correctly

### Option 3: Alternative Bootstrap Strategy

If ForkId validation is overly strict on some peers:

1. **Targeted Peering**: Connect to known-compatible peers explicitly
2. **Bootstrap Nodes**: Ensure fukuii.pw bootstrap nodes are accessible
3. **Peer Selection**: May need to implement retry logic for peer selection

### Option 4: Enable Fast Sync

Consider enabling fast/snap sync to quickly advance past block 0:
- This would change ForkId from `0xfc64ec04` to a later value
- May improve peer acceptance rates
- Check `use-bootstrap-checkpoints = true` in configuration

## Verification Steps

After updating configuration:

1. **Check ForkId at Startup**:
   ```bash
   ./bin/fukuii etc 2>&1 | grep -A 2 "Sending status"
   ```
   Should show: `forkId=ForkId(0xbe46d57c, None)` or similar valid ForkId

2. **Monitor Peer Connections**:
   ```bash
   ./bin/fukuii etc 2>&1 | grep -E "handshaked|disconnect"
   ```
   Should see sustained peer connections, not immediate disconnects

3. **Verify Sync Progress**:
   ```bash
   curl -X POST --data '{"jsonrpc":"2.0","method":"eth_syncing","params":[],"id":1}' http://localhost:8546
   ```

## Related Documentation

- [Issue 14: ETH68 Peer Connection Failures](../runbooks/known-issues.md#issue-14-eth68-peer-connection-failures)
- [EIP-2124: Fork identifier for chain compatibility checks](https://eips.ethereum.org/EIPS/eip-2124)
- [Peering Runbook](../runbooks/peering.md)
- [Log Triage Runbook](../runbooks/log-triage.md)

## Additional Notes

- The message "Unknown network message type: 16" in logs is harmless - it's the normal decoder chain trying NetworkMessageDecoder first, then falling back to ETH68MessageDecoder for Status (code 0x10)
- The Warning "Peer sent uncompressed RLP data despite p2pVersion >= 4" is a protocol deviation by the peer but doesn't cause disconnection
- Disconnect reason 0x10 specifically means ForkId incompatibility in practice, though spec says "other subprotocol reason"
- **Our ForkId `0xfc64ec04` is CORRECT for block 0** - it matches core-geth's expected value for unsynced nodes
- The issue may be that some peers overly strict ForkId validation rejecting nodes "too far behind"

## Core-Geth Comparison

Full fork list comparison with core-geth `params/config_classic.go`:

| Fork | Block Number | Core-Geth | Fukuii |
|------|--------------|-----------|---------|
| Homestead (EIP-2/7) | 1,150,000 | ✅ | ✅ |
| EIP-150 | 2,500,000 | ✅ | ✅ |
| EIP-155/160 | 3,000,000 | ✅ | ✅ |
| ECIP-1010 Pause | 3,000,000 | ✅ | ✅ |
| ECIP-1017 | 5,000,000 | ✅ | ✅ |
| Disposal | 5,900,000 | ✅ | ✅ |
| Atlantis (EIP-158/161/170) | 8,772,000 | ✅ | ✅ |
| Agharta (Constantinople) | 9,573,000 | ✅ | ✅ |
| Phoenix (Istanbul) | 10,500,839 | ✅ | ✅ |
| ECIP-1099 (Etchash) | 11,700,000 | ✅ | ✅ |
| Magneto (Berlin) | 13,189,133 | ✅ | ✅ |
| Mystique (London partial) | 14,525,000 | ✅ | ✅ |
| Spiral (Shanghai partial) | 19,250,000 | ✅ | ✅ |

**Result**: Configuration matches core-geth perfectly. ForkId calculation is correct.

## Current Status and Recommended Solutions

### Understanding the Issue

The ForkId mismatch issue occurs because:
1. **Our node** (at block 0) correctly reports ForkId `0xfc64ec04, next: 1150000` per EIP-2124
2. **Peer nodes** (at block 19,250,000+) report ForkId `0xbe46d57c, next: None`
3. **Peers disconnect** with reason code 0x10 because they perceive incompatibility

This is a **peer-side strictness issue**, not a configuration error. Our ForkId is technically correct for an unsynced node.

### Workaround Options

Since the ideal solution (allowing unsynced nodes to report latest fork) is not yet implemented, use these workarounds:

#### Option 1: Use Bootstrap Checkpoints (Recommended)

The node already has bootstrap checkpoints configured in `etc-chain.conf`. Ensure they are enabled:

```hocon
# In etc-chain.conf (should already be set)
use-bootstrap-checkpoints = true

bootstrap-checkpoints = [
  "19250000:0xf302cfb92fd618dac5c69ba85acc945e55d1df63ad60b02d58e217af2b909a68",
  "14525000:0x79a52036a05a0248b6bc449544c23b48994582a59f6f7451891246afc67ac3af",
  "13189133:0x85f67d6db616637bd8b3bf32cea92873f91bac977859e387ad341c1726c14b45",
  "10500839:0x85f67d6db616637bd8b3bf32cea92873f91bac977859e387ad341c1726c14b45",
]
```

**Note**: The current implementation may not automatically advance ForkId based on checkpoints. This is a known limitation.

#### Option 2: Manual Peer Connections

Add known-compatible peers to your configuration:

```hocon
# In application.conf
fukuii.network.peer {
  manual-connections = [
    # Add stable ETC nodes that accept unsynced peers
    "enode://fbcd6fc04fa7ea897558c3f5edf1cd192e3b2c3b5b9b3d00be179b2e9d04e623e017ed6ce6a1369fff126661afa1c5caa12febce92dcb70ff1352b86e9ebb44f@18.193.251.235:9076?discport=30303",
    "enode://1619217a01fb87a745bb104872aa84314a2d42d99c7b915cd187245bfd898d679cbf78b3ea950c32051db860e2c4e3fe7d6329107587be33ab37541ca65046f91@18.198.165.189:9076?discport=30303",
  ]
}
```

#### Option 3: Wait for Improved ForkId Handling (Future Enhancement)

A proper solution would involve:
1. Implementing `fork-id-report-latest-when-unsynced` configuration option
2. Allowing nodes at block 0 with bootstrap checkpoints to report the checkpoint's ForkId
3. More lenient peer acceptance logic that tolerates unsynced nodes

**Tracking**: This enhancement should be tracked in a GitHub issue for future implementation.

### Current Recommendations (v0.1.4)

For users experiencing this issue:

1. **Verify bootstrap checkpoints are enabled** (they should be by default)
2. **Try manual peer connections** to known-stable nodes
3. **Monitor for successful connections** - some peers may accept the connection
4. **Be patient** - the node may eventually find compatible peers
5. **Report persistent issues** - open a GitHub issue with logs if sync remains stuck

### For Developers: Future Enhancement

The following enhancement should be implemented in a future version:

```scala
// In ForkId.create() method
def create(genesisHash: ByteString, config: BlockchainConfig)(head: BigInt): ForkId = {
  // Check if fork-id-report-latest-when-unsynced is enabled
  val reportLatestWhenUnsynced = config.forkIdReportLatestWhenUnsynced.getOrElse(false)
  
  if (head == 0 && reportLatestWhenUnsynced) {
    // Report latest known fork instead of block-0 fork
    val allForks = sortedForks(config).filter(_ > 0)
    if (allForks.nonEmpty) {
      val latestFork = allForks.last
      // Calculate ForkId as if at the latest fork
      create(genesisHash, config)(latestFork)
    } else {
      // Fallback to normal calculation
      calculateForkId(genesisHash, config, head)
    }
  } else {
    calculateForkId(genesisHash, config, head)
  }
}
```

This would allow unsynced nodes to match peer expectations while maintaining security.

## Contact

For additional support or if this guide doesn't resolve the issue:
- Open an issue at https://github.com/chippr-robotics/fukuii/issues
- Check the [Known Issues](../runbooks/known-issues.md) documentation
