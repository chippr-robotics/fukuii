# Troubleshooting Peer Disconnects with Reason 0x10 (SubprotocolError)

## Problem Description

When connecting to the Ethereum Classic network (particularly Mordor testnet), peers may disconnect immediately after the Status exchange with reason code `0x10` (SubprotocolError/Other). This typically occurs during initial sync when the node is at or near the genesis block.

### Typical Connection Flow

1. ✅ RLPx handshake completes successfully
2. ✅ Hello exchange - protocol version negotiated (e.g., ETH68)
3. ✅ Status exchange - node sends Status message with correct protocolVersion
4. ❌ Peer disconnects with reason `0x10` immediately after receiving Status
5. ❌ No handshaked peers available
6. ❌ Sync cannot proceed

## Root Cause Analysis

Based on analysis of the core-geth implementation (the predominant ETC client), reason code `0x10` represents "SubprotocolError" which indicates an error at the eth protocol layer, not the p2p layer.

### Common Causes

1. **Genesis Block Rejection**: Some peer implementations may reject nodes that are at the genesis block (totalDifficulty = 131,072 for Mordor) as they are considered not useful for syncing.

2. **Sync Stage Mismatch**: Peers may have selection policies that filter out nodes too far behind the current chain tip.

3. **ForkId Validation**: Even though ForkId validation may pass on our side, the remote peer may have stricter validation rules.

4. **Peer Selection Policy**: CoreGeth and other clients may implement peer selection policies that prioritize nodes closer to the chain tip.

## Diagnostic Logging

The following logging has been added to help diagnose connection issues:

### Hello Exchange Logs
```
HELLO_EXCHANGE: Sending Hello - clientId='...', capabilities=[...], p2pVersion=4
HELLO_EXCHANGE: Received Hello from client='...', capabilities=[...], p2pVersion=...
HELLO_EXCHANGE: Negotiated eth/68 with client '...'
```

### Status Exchange Logs
```
STATUS_EXCHANGE: Sending ETH Status - negotiated=ETH68, protocolVersion=68, networkId=7, bestBlock=0, totalDifficulty=131072, forkId=...
STATUS_EXCHANGE: Received ETH Status - negotiated=ETH68, protocolVersion=68, networkId=7, totalDifficulty=...
```

### Disconnect Logs
```
PEER_DISCONNECT: Peer disconnected during handshake - reason: 0x10 (Some other reason specific to a subprotocol). This may indicate protocol incompatibility or peer selection policy.
PEER_DISCONNECT: Received 0x10 (Other/SubprotocolError) during handshake. This typically means the peer rejected us after Status exchange. Possible causes: incompatible fork, peer at different sync stage, or peer selection policy.
```

## Workarounds and Solutions

### 1. Configure Static Peers

Instead of relying solely on discovery, configure known good static peers that are more likely to accept connections from nodes at genesis:

Edit your configuration file (e.g., `conf/mordor.conf`) or use the `--static-nodes` command line option:

```hocon
fukuii {
  network {
    discovery {
      # Keep discovery enabled but supplement with static peers
      discovery-enabled = true
      reuse-known-nodes = true
    }
  }
}
```

Add trusted bootstrap nodes from the Mordor configuration as static peers. The bootstrap nodes are specifically designed to help new nodes sync.

### 2. Use Bootstrap Checkpoints (Enabled by Default)

Fukuii v1.1.0+ includes bootstrap checkpoints that allow the node to start syncing from well-known fork activation blocks instead of genesis:

```hocon
fukuii.blockchains.mordor-chain {
  use-bootstrap-checkpoints = true  # Enabled by default
}
```

This reduces the "distance" between your node and the network, making you more attractive to peers.

To disable and force pivot sync from genesis (not recommended):
```bash
./bin/fukuii mordor --force-pivot-sync
```

### 3. Increase Peer Connection Attempts

Adjust peer configuration to be more aggressive about finding compatible peers:

```hocon
fukuii.network.peer {
  # Increase minimum peers to try connecting to more nodes
  min-outgoing-peers = 30  # default: 20
  
  # Increase maximum to allow more connection attempts
  max-outgoing-peers = 60  # default: 50
  
  # Reduce retry delay to reconnect faster
  connect-retry-delay = 3.seconds  # default: 5.seconds
  
  # Reduce update interval to try new peers more frequently
  update-nodes-interval = 20.seconds  # default: 30.seconds
}
```

### 4. Wait for Peer Diversity

When starting a fresh node, it may take several minutes to discover a diverse set of peers willing to sync with a node at genesis. Be patient and monitor the logs for successful connections:

- Look for `PEER_CONNECTION: Handshake completed successfully` messages
- Once 1-2 peers are successfully handshaked, sync should begin

### 5. Pre-sync from Trusted Source (Advanced)

For production deployments, consider pre-populating the database from a trusted snapshot or checkpoint to avoid the genesis block issue entirely. See [Backup & Restore documentation](../runbooks/backup-restore.md) for details.

## Monitoring Connection Health

Use the health check endpoints to monitor peer connections:

```bash
# Check if node is ready (has peers and is syncing)
curl http://localhost:8546/readiness

# Detailed health status including peer count
curl http://localhost:8546/healthcheck
```

Expected output when peers are connected:
```json
{
  "checks": [
    {
      "name": "peerCount",
      "status": "OK",
      "info": "5"
    },
    ...
  ]
}
```

## Expected Behavior

With the enhanced logging, you should now see:
1. Clear indication of which protocol version is being negotiated
2. Details of Status messages being exchanged
3. Specific disconnect reasons with context
4. Ability to identify patterns in which peers accept vs. reject connections

## Still Having Issues?

If you continue to experience widespread peer rejections:

1. **Verify Network Configuration**:
   - Ensure networkId is correct (7 for Mordor, 61 for ETC mainnet)
   - Check that your genesis hash matches the network
   - Verify clock synchronization (peers reject nodes with significant time drift)

2. **Check Firewall/NAT**:
   - Ensure port 30303 (UDP for discovery) is accessible
   - Ensure port 9076 (TCP for RLPx) is accessible
   - Enable automatic port forwarding: `fukuii.network.automatic-port-forwarding = true`

3. **Review Bootstrap Nodes**:
   - Ensure bootstrap nodes in configuration are active
   - Try connecting directly to specific enodes using static peer configuration

4. **Gather More Information**:
   - Enable debug logging: `fukuii.logging.level = DEBUG`
   - Share logs (with sensitive information redacted) when seeking help

## References

- [ADR-012: Bootstrap Checkpoints](../adr/012-bootstrap-checkpoints.md)
- [Peering Runbook](../runbooks/peering.md)
- [Core-geth p2p implementation](https://github.com/etclabscore/core-geth/tree/master/p2p)
- [Core-geth eth protocol](https://github.com/etclabscore/core-geth/tree/master/eth/protocols/eth)

## Technical Details

### Disconnect Reason Codes

| Code | Name | Description |
|------|------|-------------|
| 0x00 | DisconnectRequested | Clean disconnect requested |
| 0x03 | UselessPeer | Peer doesn't serve required data |
| 0x04 | TooManyPeers | Peer has reached connection limit |
| 0x06 | IncompatibleP2pProtocolVersion | P2P protocol version mismatch |
| 0x10 | Other/SubprotocolError | Error in eth protocol layer (Status exchange) |

### Status Message Validation (CoreGeth)

CoreGeth validates the following in Status messages:
1. **NetworkID** must match
2. **ProtocolVersion** must match negotiated version from Hello
3. **Genesis hash** must match
4. **ForkId** must pass validation (compatible fork history)

Any mismatch results in disconnect with reason 0x10.
