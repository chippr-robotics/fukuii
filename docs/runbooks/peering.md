# Peering Runbook

**Audience**: Operators managing network connectivity and peer relationships  
**Estimated Time**: 15-30 minutes  
**Prerequisites**: Running Fukuii node

## Overview

This runbook covers peer discovery, network connectivity troubleshooting, and optimization of peer relationships in Fukuii. A healthy peer network is essential for reliable blockchain synchronization and staying up-to-date with the network.

## Table of Contents

1. [Understanding Peering](#understanding-peering)
2. [Peer Discovery Process](#peer-discovery-process)
3. [Monitoring Peer Health](#monitoring-peer-health)
4. [Troubleshooting Connectivity](#troubleshooting-connectivity)
5. [Advanced Configuration](#advanced-configuration), including the [Consortium Node Allowlist](#consortium-node-allowlist)
6. [Best Practices](#best-practices)

## Understanding Peering

### Peer Types

Fukuii distinguishes between two types of peer connections:

1. **Outgoing Peers**: Connections initiated by your node
   - Default min: 20 peers
   - Default max: 50 peers
   - Your node actively seeks these connections

2. **Incoming Peers**: Connections from other nodes to yours
   - Default max: 30 peers
   - Requires open/forwarded ports
   - Indicates your node is publicly accessible

### Network Protocols

Fukuii uses two network protocols:

1. **Discovery Protocol** (UDP)
   - Port: 30303 (default)
   - Purpose: Find peers on the network
   - Protocol: Ethereum Node Discovery Protocol v4

2. **Ethereum Protocol** (TCP)
   - Port: 30303 (default)
   - Purpose: Exchange blockchain data
   - Protocol: RLPx with ETH/66 capability

### Healthy Peer Count

- **Minimum**: 5-10 peers for basic operation
- **Typical**: 20-40 peers for stable synchronization
- **Maximum**: 80 total peers (50 outgoing + 30 incoming)

## Peer Discovery Process

### Bootstrap Process

When Fukuii starts, it follows this discovery sequence:

1. **Load Known Nodes**
   - Reads previously discovered peers from: `~/.fukuii/<network>/knownNodes.json`
   - Enabled by default with `reuse-known-nodes = true`

2. **Contact Bootstrap Nodes**
   - Connects to hardcoded bootstrap nodes in network configuration
   - Bootstrap nodes are maintained by the ETC community

3. **Perform Kademlia Lookup**
   - Uses DHT (Distributed Hash Table) to discover more peers
   - Gradually builds routing table of network peers

4. **Establish Connections**
   - Attempts TCP connections to discovered peers
   - Performs RLPx handshake
   - Exchanges status and capabilities

5. **Persist Known Nodes**
   - Periodically saves discovered peers to disk
   - Interval: 20 seconds (default)
   - Max persisted: 200 nodes (default)

### Configuration Parameters

Key configuration parameters (in `base.conf`):

```hocon
fukuii.network {
  discovery {
    discovery-enabled = true
    reuse-known-nodes = true
    scan-interval = 2.minutes        # Reduced network overhead
    request-timeout = 3.seconds      # More tolerant of latency
    kademlia-timeout = 10.seconds    # More time for responses
    kademlia-bucket-size = 16
  }
  
  peer {
    min-outgoing-peers = 20
    max-outgoing-peers = 50
    max-incoming-peers = 30
    connect-retry-delay = 15.seconds  # Reduced connection churn
    connect-max-retries = 2           # Fail faster, try new peers
    wait-for-handshake-timeout = 10.seconds  # More tolerant of latency
    wait-for-tcp-ack-timeout = 15.seconds    # Prevent premature failures
    update-nodes-interval = 60.seconds       # Reduced reconnection attempts
    short-blacklist-duration = 3.minutes     # Faster retry for TooManyPeers
    long-blacklist-duration = 60.minutes     # Reasonable recovery time
  }
  
  known-nodes {
    persist-interval = 20.seconds
    max-persisted-nodes = 200
  }
}

fukuii.sync {
  peers-scan-interval = 5.seconds        # Reduced overhead
  blacklist-duration = 120.seconds       # Faster retry for transient issues
  critical-blacklist-duration = 60.minutes  # Still a penalty but allows recovery
  peer-response-timeout = 45.seconds     # More tolerant of peer load
}
```

## Monitoring Peer Health

### Check Current Peer Count

Using JSON-RPC:

```bash
curl -X POST --data '{"jsonrpc":"2.0","method":"net_peerCount","params":[],"id":1}' \
  http://localhost:8546
```

Expected response:
```json
{
  "jsonrpc":"2.0",
  "id":1,
  "result":"0x14"  # Hex number, e.g., 0x14 = 20 peers
}
```

### Get Detailed Peer Information

```bash
# Needs the `admin` namespace on this endpoint (see the note below)
curl -X POST --data '{"jsonrpc":"2.0","method":"admin_peers","params":[],"id":1}' \
  http://localhost:8546
```

Note: the `admin` namespace is off by default over HTTP and WebSocket (and on over IPC), so `admin_peers` answers
`-32601 Method not found` until you opt in: `fukuii.network.rpc.http.apis = "eth,net,web3,fukuii,admin"`. Enable it
only on a loopback endpoint, or grant it to one operator's client certificate with an identity rule on an mTLS
listener ([TLS Operations](tls-operations.md#identity-rules-grant-namespaces)). `net_peerCount` needs only `net`; the other `net_*` peer methods (`net_listPeers`,
`net_connectToPeer`, `net_nodeInfo`, ...) are served with `admin` too.

### Monitor Logs for Peer Activity

```bash
tail -f ~/.fukuii/etc/logs/fukuii.log | grep -i peer
```

Key log patterns:

**Good signs:**
```
INFO  [PeerManagerActor] - Connected to peer: Peer(...)
INFO  [PeerActor] - Successfully handshaked with peer
INFO  [PeerDiscoveryManager] - Discovered X peers
```

**Warning signs:**
```
WARN  [PeerManagerActor] - Disconnected from peer: reason=...
WARN  [PeerActor] - Handshake timeout with peer
ERROR [ServerActor] - Failed to bind to port 30303
```

### Check Network Connectivity

Verify your node is reachable from the internet:

```bash
# Check if discovery port is open (requires external tool)
# From another machine or online port checker:
nc -zvu <your-public-ip> 30303

# Check if P2P port is open
nc -zv <your-public-ip> 30303
```

Online port checkers:
- https://canyouseeme.org/
- https://www.yougetsignal.com/tools/open-ports/

## Troubleshooting Connectivity

### Problem: Zero or Very Few Peers

**Symptoms:**
- `net_peerCount` returns 0 or very low number (< 5)
- Logs show `No peers available`
- Sync is not progressing

**Diagnostic Steps:**

1. **Verify network connectivity**
   ```bash
   ping 8.8.8.8
   curl -I https://www.google.com
   ```

2. **Check if discovery is enabled**
   
   Verify in your configuration or logs:
   ```bash
   grep "discovery-enabled" ~/.fukuii/etc/logs/fukuii.log
   ```

3. **Check ports are not blocked**
   ```bash
   # Check locally if ports are listening
   sudo netstat -tulpn | grep -E "30303|30303"
   ```
   
   Expected output:
   ```
   udp6       0      0 :::30303              :::*                  <pid>/java
   tcp6       0      0 :::30303               :::*                  <pid>/java
   ```

4. **Check firewall rules**
   ```bash
   # Ubuntu/Debian
   sudo ufw status
   
   # RHEL/CentOS
   sudo firewall-cmd --list-all
   ```

**Solutions:**

**A. Enable discovery if disabled**

Edit your configuration to ensure:
```hocon
fukuii.network.discovery.discovery-enabled = true
```

**B. Open firewall ports**

```bash
# Ubuntu/Debian with ufw
sudo ufw allow 30303/udp
sudo ufw allow 30303/tcp

# RHEL/CentOS with firewalld
sudo firewall-cmd --permanent --add-port=30303/udp
sudo firewall-cmd --permanent --add-port=30303/tcp
sudo firewall-cmd --reload
```

**C. Configure port forwarding**

If behind NAT/router:

1. Log in to your router admin interface
2. Forward port 30303 (UDP) to your node's internal IP
3. Forward port 30303 (TCP) to your node's internal IP
4. Or enable UPnP in Fukuii config:
   ```hocon
   fukuii.network.automatic-port-forwarding = true
   ```

**D. Configure external IP detection**

The address Fukuii advertises to peers (in its enode URL) is controlled by
`fukuii.network.server-address.external-ip-detection`:

- `upnp` (default) — asks the local gateway router for its WAN address, then falls back to a local
  network interface address. No traffic leaves the LAN.
- `full` — adds a STUN probe and an HTTPS probe ahead of the local-interface fallback. Use this behind
  carrier-grade NAT (CGNAT) or when UPnP is disabled on the gateway.
- `none` — no detection at all; you must set `advertised-address` yourself.

Every detected candidate is validated as a public IPv4 address before it is advertised. If detection
fails entirely (or the mode is `none`), Fukuii advertises loopback and logs a warning. In containers or
enterprise deployments where outbound STUN/HTTPS probing is undesirable, set the address explicitly and
skip detection:

```hocon
fukuii.network.server-address.advertised-address = "203.0.113.10"
```

**E. Manually add peers**

If discovery fails, list peers in `<datadir>/static-nodes.json` (dialled at every start and kept connected):

```json
[
  "enode://pubkey@ip:port",
  "enode://pubkey@ip:port"
]
```

or add one at runtime with `admin_addPeer` (needs the `admin` namespace). `bootstrap-nodes` is a key of the chain
configuration (`conf/base/chains/<network>-chain.conf`), not of `fukuii.network`.

Find bootstrap nodes from:
- Official ETC documentation
- Community resources
- Other node operators

**F. Reset known nodes**

If `knownNodes.json` is corrupted:

```bash
# Stop Fukuii
# Backup and remove known nodes
mv ~/.fukuii/etc/knownNodes.json ~/.fukuii/etc/knownNodes.json.bak
# Restart Fukuii
```

### Problem: Peers Connecting but Quickly Disconnecting

**Symptoms:**
- Peer count fluctuates rapidly
- Logs show many disconnect messages
- Synchronization is unstable

**Common Causes:**

1. **Network incompatibility** - Your node is on a different fork/network
2. **Clock skew** - System time is incorrect
3. **Resource exhaustion** - Node is overloaded
4. **Firewall issues** - Intermittent blocking

**Diagnostic Steps:**

1. **Check system time**
   ```bash
   date
   # Should be accurate to within a few seconds
   ```
   
   Sync time if needed:
   ```bash
   sudo ntpdate pool.ntp.org
   # Or
   sudo systemctl restart systemd-timesyncd
   ```

2. **Check for network mismatch**
   
   Verify you're running the correct network:
   ```bash
   # Check logs for network ID
   grep "network" ~/.fukuii/etc/logs/fukuii.log | head -5
   ```

3. **Monitor resource usage**
   ```bash
   # Check CPU, memory, disk I/O
   top
   iostat -x 1
   ```

**Solutions:**

**A. Fix system time**
```bash
# Install NTP
sudo apt-get install ntp  # Ubuntu/Debian
sudo systemctl enable ntp
sudo systemctl start ntp
```

**B. Verify network configuration**

Ensure you're running the correct network:
```bash
./bin/fukuii etc  # For ETC mainnet
```

**C. Increase timeouts (if network latency is high)**

In your configuration (values shown are examples of increased timeouts):
```hocon
fukuii.network.peer {
  wait-for-hello-timeout = 10.seconds     # increase from default 5s
  wait-for-status-timeout = 45.seconds    # increase from default 30s
  wait-for-handshake-timeout = 15.seconds # increase from default 10s
  wait-for-tcp-ack-timeout = 20.seconds   # increase from default 15s
}
```

### Problem: Only Outgoing Peers (No Incoming)

**Symptoms:**
- All peers are outgoing connections
- `max-incoming-peers` is never reached
- Node works but is not contributing to network health

**Cause**: Your node is not publicly accessible (behind NAT without port forwarding)

**Impact**: 
- Your node works fine for syncing
- Network health suffers if many nodes are not publicly accessible
- You don't help other nodes discover the network

**Solutions:**

See "Configure port forwarding" section above. This is optional for personal nodes but recommended for public infrastructure.

### Problem: High Peer Churn

**Symptoms:**
- Constant connect/disconnect in logs
- Peer count is unstable
- Frequent "blacklisted peer" messages

**Diagnostic Steps:**

```bash
# Check for blacklist activity in logs
grep -i blacklist ~/.fukuii/etc/logs/fukuii.log | tail -20
```

**Causes:**
- Incompatible peers (wrong network, old version)
- Misbehaving peers
- Network instability

**Solutions:**

This is usually normal behavior as Fukuii filters incompatible peers. However, if excessive:

1. **Update to latest version** - May have better peer filtering
2. **Adjust peer limits** - Temporarily increase max peers to compensate:
   ```hocon
   fukuii.network.peer.max-outgoing-peers = 60
   ```

## Advanced Configuration

### Optimizing for Initial Sync

For initial synchronization, maximize peers:

```hocon
fukuii.network.peer {
  min-outgoing-peers = 30
  max-outgoing-peers = 60
}
```

After sync completes, reduce to stable values.

### Optimizing for Bandwidth Conservation

For limited bandwidth scenarios:

```hocon
fukuii.network.peer {
  min-outgoing-peers = 10
  max-outgoing-peers = 15
  max-incoming-peers = 10
}
```

### Disabling Discovery (Static Peers Only)

For private networks or when you have a fixed set of peers:

```hocon
fukuii.network {
  discovery.discovery-enabled = false
  discovery.reuse-known-nodes = false
}
```

and list the peers in `<datadir>/static-nodes.json`:

```json
[
  "enode://pubkey1@ip1:port1",
  "enode://pubkey2@ip2:port2"
]
```

(`bootstrap-nodes` is a key of the chain configuration, `conf/base/chains/<network>-chain.conf`, not of
`fukuii.network`.)

**Warning**: Only use this if you have reliable static peers. Otherwise, your node may become isolated.

Disabling discovery only stops the node looking for peers: any node that knows the address can still connect. To
refuse every node that is not a member, use the allowlist below.

### Consortium Node Allowlist

On a private or consortium network, `fukuii.network.peer.node-allowlist` admits only listed node IDs (spec 030). It
is the P2P counterpart of mutual TLS: RLPx is not TLS, but its handshake already proves that the remote side holds the
private key of its node ID, so the node checks that ID against the list.

```hocon
fukuii.network.peer.node-allowlist {
  enabled = true                       # default false
  nodes = [                            # "enode://<128 hex>@host:port" (also dialled) or a bare 128-hex id (inbound only)
    "enode://<128 hex>@192.168.1.101:30303"
  ]
  file = "/etc/fukuii/allowlist.json"  # optional; JSON array in the static-nodes.json format; needed for admin_ edits
  reload-interval = 1 minute           # how often the file is checked for changes (SHA-256 of its content)
}
```

What it does when `enabled = true`:

- **Inbound**: right after the RLPx auth handshake, before the devp2p Hello, a node whose ID is not listed is
  disconnected. **Outbound**: every dial (static nodes, known nodes, `admin_addPeer`, `admin_addTrustedPeer`,
  `net_connectToPeer`) is checked first; those methods answer an error `node <id prefix>... is not on the node
  allowlist` for an unlisted node, and the peer manager logs `Not dialling <enode>: ...` at INFO.
- **Discovery is off**: discv4, discv5 and the EIP-1459 DNS tree walk do not run and no UDP port is bound, whatever
  `network.discovery.discovery-enabled` says (the node logs a WARN saying so). Persisted known nodes are still dialled
  if they are listed. Entries with an address (`enode://...@host:port`) are dial candidates.
- **Startup check**: every node in `<datadir>/static-nodes.json` and every `bootstrap-nodes` entry of the chain
  configuration must be on the list (in `nodes` or the file), or the node refuses to start:
  `Refusing to start: static node <id prefix> is not on fukuii.network.peer.node-allowlist`. This applies under the
  `enterprise` modifier too, although that modifier otherwise ignores bootstrap nodes. An entry that does not parse
  also refuses the start. The node's own ID on the list is ignored. IDs are case-insensitive.
- At start the node logs `P2P node allowlist on: <n> node(s), reload every <interval>; discovery is off` (INFO).
  A change to the list logs `Node allowlist changed (generation <g>): <n> node(s)`, and each peer dropped by a removal
  `ALLOWLIST_REMOVED: disconnecting <id>: it is no longer on the node allowlist`.

**Editing the list without a restart.** Two ways, both needing `file`:

1. Edit the file. It is re-read every `reload-interval` when its content changes; a version that does not parse is
   refused (counted in `app_p2p_allowlist_reload_failures_total`) and the last good list stays.
2. Call the `admin_` methods below. Each call is all-or-nothing: under one lock the node re-reads the file if it changed
   on disk, applies the change, writes the file atomically (temporary file, fsync, rename), then swaps the in-memory
   list. The file is never left half-written, and a failed write changes nothing.

| Method | Params | Result |
| --- | --- | --- |
| `admin_addNodesToAllowlist` | `[["enode://<128 hex>@<host>:<port>" or "<128 hex>", ...]]`, 1 to 256 entries | `true`; listed nodes with an address become dial candidates |
| `admin_removeNodesFromAllowlist` | the same | `true`; connected peers no longer listed are disconnected |
| `admin_getNodesAllowlist` | `[]` | `{ "enabled", "file", "nodes": [ { "id", "enode", "sources" } ] }`, sorted by id; `sources` among `config`, `file`, `static`, `bootnode` |

They are in the `admin` namespace, which no HTTP or WebSocket default includes. Call them over the IPC socket (its
default serves `admin`), or over HTTP/WS for a client certificate whose identity rule grants `admin`
([TLS Operations](tls-operations.md#identity-rules-grant-namespaces)). Without `admin` the caller gets `-32601`. Each
edit is logged at INFO with the caller's rule name or transport:
`Allowlist: added <n> node(s) [<id prefix>...] by <rule name | transport>`.

```bash
IPC=$HOME/.fukuii/<network>/fukuii.ipc      # network.rpc.ipc.enabled = true
echo '{"jsonrpc":"2.0","id":1,"method":"admin_addNodesToAllowlist","params":[["enode://<128 hex>@192.168.1.102:30303"]]}' \
  | socat - UNIX-CONNECT:$IPC
echo '{"jsonrpc":"2.0","id":2,"method":"admin_getNodesAllowlist","params":[]}' | socat - UNIX-CONNECT:$IPC | jq .
echo '{"jsonrpc":"2.0","id":3,"method":"admin_removeNodesFromAllowlist","params":[["<128 hex>"]]}' \
  | socat - UNIX-CONNECT:$IPC
```

Errors (`-32000` unless noted; the message names the first offending entry):

| Case | Message |
| --- | --- |
| Allowlist off | `node allowlist is disabled (fukuii.network.peer.node-allowlist.enabled = false)` |
| No `file` configured | `node allowlist has no file; runtime edits need fukuii.network.peer.node-allowlist.file` |
| Bad entry | `-32602` `invalid enode or node id: <entry>` |
| Empty, or more than 256 entries | `-32602` `expected 1 to 256 entries` |
| Duplicate in the request | `-32602` `duplicate entry: <id prefix>` |
| Add: already in the file | `already on the allowlist: <id prefix>` |
| Remove: not listed | `not on the allowlist: <id prefix>` |
| Remove: from `nodes`, static nodes or bootnodes | `<id prefix> comes from <source> and cannot be removed at runtime` (change that source and restart) |
| File edited by hand and no longer parses | `allowlist file <path> does not parse (<reason>); fix it before editing` |
| Write or rename failed | `could not write <path>: <reason>; nothing changed` |

**Metrics** (`fukuii.metrics.enabled = true`):

| Metric | Labels | Meaning |
| --- | --- | --- |
| `app_p2p_allowlist_rejections_total` | `direction` (`inbound`, `outbound`) | Connections and dials refused |
| `app_p2p_allowlist_size` | none | Listed node IDs |
| `app_p2p_allowlist_reload_failures_total` | none | File versions that did not parse |
| `app_p2p_allowlist_edits_total` | `op` (`add`, `remove`), `result` (`ok`, `refused`, `failed`) | `admin_` edits |

An inbound rejection is logged at DEBUG per connection; watch `app_p2p_allowlist_rejections_total` rather than the
log. A sustained inbound rate means a node outside the consortium keeps trying to connect.

### Custom Discovery Settings

For specialized network environments:

```hocon
fukuii.network.discovery {
  # Increase scan frequency for faster peer discovery (not recommended for production)
  scan-interval = 1.minute  # default: 2.minutes
  
  # Adjust Kademlia parameters
  kademlia-bucket-size = 20  # default: 16
  kademlia-alpha = 5  # default: 3 (higher = more aggressive discovery)
  
  # Adjust timeouts for high-latency networks
  request-timeout = 5.seconds  # default: 3.seconds
  kademlia-timeout = 15.seconds  # default: 10.seconds
}
```

### Setting External Address

If your node has a public IP that differs from its local IP:

```hocon
fukuii.network {
  discovery {
    host = "your.public.ip.address"
  }
  
  server-address {
    interface = "0.0.0.0"  # Listen on all interfaces
  }
}
```

## Best Practices

### For Home/Personal Nodes

1. **Open ports if possible** - Helps network health
2. **Use default peer limits** - Balanced for typical home connections
3. **Enable discovery** - Automatic peer management
4. **Enable UPnP** - Simplifies NAT traversal

### For Production/Infrastructure Nodes

1. **Allocate sufficient bandwidth** - 1-10 Mbps minimum
2. **Open all ports** - Be a good network citizen
3. **Monitor peer count** - Alert if < 10 peers
4. **Use static IP** - Configure external address
5. **Increase peer limits** - Handle more connections if resources allow
6. **Regular monitoring** - Check peer health daily

### For Private/Test Networks

1. **Disable public discovery** - Use static peers only
2. **Configure bootstrap or static nodes** - Point to your network's nodes
3. **Consortium networks: turn on the [node allowlist](#consortium-node-allowlist)** - Refuse every node that is not a
   member
4. **Adjust timeout values** - May need tuning for test environments
5. **Document peer topology** - Maintain list of all network nodes

### General Recommendations

1. **Keep system time accurate** - Use NTP
2. **Monitor connection quality** - Watch for high latency peers
3. **Update regularly** - New versions may improve peer management
4. **Log peer activity** - Helps diagnose issues
5. **Backup known nodes** - Can speed up recovery after restarts

## Monitoring and Alerting

### Metrics to Monitor

Set up alerts for:

```bash
# Peer count below threshold
net_peerCount < 10

# No peers for extended period
net_peerCount == 0 for > 5 minutes

# Excessive peer churn
peer_disconnect_rate > 10 per minute
```

### Using Prometheus

If metrics are enabled, query peer metrics:

```bash
# Default port 13798 (fukuii.metrics.port), bound to localhost (fukuii.metrics.interface)
curl http://localhost:13798/metrics | grep -E "peer|p2p_allowlist"
```

Example Prometheus alert:
```yaml
- alert: LowPeerCount
  expr: ethereum_peer_count < 10
  for: 5m
  annotations:
    summary: "Fukuii node has low peer count"
    description: "Node {{ $labels.instance }} has only {{ $value }} peers"
```

## Related Runbooks

- [First Start](first-start.md) - Initial node setup including network configuration
- [Log Triage](log-triage.md) - Analyzing peer-related log messages
- [Known Issues](known-issues.md) - Common networking problems

## Further Reading

- [Ethereum Node Discovery Protocol](https://github.com/ethereum/devp2p/blob/master/discv4.md)
- [RLPx Transport Protocol](https://github.com/ethereum/devp2p/blob/master/rlpx.md)
- [ETH Wire Protocol](https://github.com/ethereum/devp2p/blob/master/caps/eth.md)

---

**Document Version**: 1.1  
**Last Updated**: 2025-12-01  
**Maintainer**: Chippr Robotics LLC
