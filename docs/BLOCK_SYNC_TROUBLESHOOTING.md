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

### Option 1: Update Blockchain Configuration (Recommended)

The blockchain configuration needs to include all ETC mainnet forks. Check:

1. **Genesis Configuration**: Verify `src/main/resources/blockchain/default-genesis.json` is correct for ETC mainnet

2. **Fork Block Numbers**: Ensure `BlockchainConfig` includes all ETC forks:
   - Homestead (1150000)
   - DAO Fork (1920000) - if applicable
   - Tangerine Whistle (2500000)
   - Spurious Dragon (3000000)
   - Byzantium (5900000)
   - Constantinople (5900000) 
   - Petersburg (8772000)
   - Istanbul (9573000)
   - Agharta (9573000)
   - Phoenix (10500839)
   - Thanos (11700000)
   - Magneto (13189133)
   - Mystique (14525000)
   - Spiral (19250000)

3. **Verify Configuration**:
   ```bash
   # Check which network is configured
   grep "network =" src/main/resources/conf/etc.conf
   
   # Check blockchain config loading
   grep -r "forkBlockNumbers" src/main/scala/
   ```

### Option 2: Verify Genesis Hash

Ensure the genesis hash matches ETC mainnet:
```
Expected: d4e56740f876aef8c010b86a40d5f56745a118d0906a34e69aec8c0db1cb8fa3
```

From logs:
```
genesisHash: d4e56740f876aef8c010b86a40d5f56745a118d0906a34e69aec8c0db1cb8fa3
```
✅ Genesis hash is correct

### Option 3: Calculate Expected ForkId

To verify what our ForkId should be, you can:

1. Get the genesis hash (confirmed correct from logs)
2. List all fork block numbers for ETC mainnet  
3. Calculate CRC32 of genesis + forks
4. Should result in `0xbe46d57c` for fully synced configuration

### Option 4: Compare with Reference Client

Check CoreGeth or other ETC client configurations:
- https://github.com/etclabscore/core-geth

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

- [Issue 14: ETH68 Peer Connection Failures](docs/runbooks/known-issues.md#issue-14-eth68-peer-connection-failures)
- [EIP-2124: Fork identifier for chain compatibility checks](https://eips.ethereum.org/EIPS/eip-2124)
- [Peering Runbook](docs/runbooks/peering.md)
- [Log Triage Runbook](docs/runbooks/log-triage.md)

## Additional Notes

- The message "Unknown network message type: 16" in logs is harmless - it's the normal decoder chain trying NetworkMessageDecoder first, then falling back to ETH68MessageDecoder for Status (code 0x10)
- The Warning "Peer sent uncompressed RLP data despite p2pVersion >= 4" is a protocol deviation by the peer but doesn't cause disconnection
- Disconnect reason 0x10 specifically means ForkId incompatibility in practice, though spec says "other subprotocol reason"

## Contact

For additional support or if this guide doesn't resolve the issue:
- Open an issue at https://github.com/chippr-robotics/fukuii/issues
- Check the [Known Issues](docs/runbooks/known-issues.md) documentation
