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
5. [Advanced Configuration](#advanced-configuration)
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
   - Port: 9076 (default)
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
# Check if admin API is enabled (requires special configuration)
curl -X POST --data '{"jsonrpc":"2.0","method":"admin_peers","params":[],"id":1}' \
  http://localhost:8546
```

Note: `admin_peers` may not be available in production configurations for security reasons.

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
ERROR [ServerActor] - Failed to bind to port 9076
```

### Check Network Connectivity

Verify your node is reachable from the internet:

```bash
# Check if discovery port is open (requires external tool)
# From another machine or online port checker:
nc -zvu <your-public-ip> 30303

# Check if P2P port is open
nc -zv <your-public-ip> 9076
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
   sudo netstat -tulpn | grep -E "30303|9076"
   ```
   
   Expected output:
   ```
   udp6       0      0 :::30303              :::*                  <pid>/java
   tcp6       0      0 :::9076               :::*                  <pid>/java
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
sudo ufw allow 9076/tcp

# RHEL/CentOS with firewalld
sudo firewall-cmd --permanent --add-port=30303/udp
sudo firewall-cmd --permanent --add-port=9076/tcp
sudo firewall-cmd --reload
```

**C. Configure port forwarding**

If behind NAT/router:

1. Log in to your router admin interface
2. Forward port 30303 (UDP) to your node's internal IP
3. Forward port 9076 (TCP) to your node's internal IP
4. Or enable UPnP in Fukuii config:
   ```hocon
   fukuii.network.automatic-port-forwarding = true
   ```

**D. Manually add peers**

If discovery fails, you can manually specify peers in your config:

```hocon
fukuii.network.bootstrap-nodes = [
  "enode://pubkey@ip:port",
  "enode://pubkey@ip:port"
]
```

Find bootstrap nodes from:
- Official ETC documentation
- Community resources
- Other node operators

**E. Reset known nodes**

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

### Optimizing for Fast Sync

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
  
  bootstrap-nodes = [
    "enode://pubkey1@ip1:port1",
    "enode://pubkey2@ip2:port2"
  ]
}
```

**Warning**: Only use this if you have reliable static peers. Otherwise, your node may become isolated.

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
2. **Configure bootstrap nodes** - Point to your network's nodes
3. **Adjust timeout values** - May need tuning for test environments
4. **Document peer topology** - Maintain list of all network nodes

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
curl http://localhost:9095/metrics | grep peer
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
