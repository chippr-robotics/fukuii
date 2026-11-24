# Enterprise Network Deployment Guide

**Audience**: Enterprise architects and DevOps engineers deploying private/permissioned EVM networks  
**Estimated Time**: 1-2 hours  
**Prerequisites**: 
- Understanding of Ethereum/EVM fundamentals
- Knowledge of network architecture and security best practices
- Familiarity with blockchain consensus mechanisms
- Access to infrastructure for deploying multiple nodes

## Overview

This guide explains how to deploy Fukuii as a private, permissioned Ethereum-compatible blockchain network for enterprise use cases. The `enterprise` modifier automatically configures Fukuii with industry best practices for private DLT deployments.

### When to Use Enterprise Mode

Enterprise mode is designed for:

- **Private Consortium Networks**: Multiple organizations collaborating on a shared blockchain
- **Internal Corporate Networks**: Single organization blockchain for internal processes
- **Development/Testing Environments**: Controlled environments for application development
- **Regulatory Compliance**: Networks requiring data privacy and access controls
- **High-Performance Use Cases**: Optimized settings for controlled environments

### Enterprise Mode Features

When you launch Fukuii with the `enterprise` modifier, it automatically configures:

1. **Disabled Public Discovery**: `network.discovery.discovery-enabled = false`
2. **Static-Nodes-Only Peering**: the node dials only the nodes in `<datadir>/static-nodes.json`; the chain's
   `bootstrap-nodes` and DNS discovery are ignored
3. **Disabled Port Forwarding**: No UPnP/NAT-PMP (unnecessary in enterprise LANs)
4. **Localhost RPC Binding**: JSON-RPC HTTP is bound to `localhost`. The modifier sets
   `fukuii.network.rpc.http.interface` as a JVM system property, which takes precedence over your configuration file
   (see [Exposing RPC](#exposing-rpc-beyond-the-host))
5. **Disabled Sync Blacklisting**: `sync.blacklist-duration = 0`, so peers are retried at once in a controlled network

The modifier sets nothing else. Security for traffic between services and organizations is configured explicitly:

- **TLS and mutual TLS on the node's listeners**: JSON-RPC HTTP and WebSocket, the Engine API and metrics take a
  `tls { enabled, profile, client-auth }` block; `client-auth = "need"` authenticates every client by certificate, and
  identity rules grant each client certificate its own JSON-RPC namespaces. See the
  [TLS Operations runbook](tls-operations.md).
- **The P2P node allowlist**: `network.peer.node-allowlist` admits only listed node IDs, inbound and outbound, and
  turns discovery off. See [Peering](peering.md#consortium-node-allowlist).
- **The bind rule**: a listener on a non-loopback interface refuses to start in plaintext unless its
  `allow-insecure-bind = true` is set ([migration](tls-operations.md#migration)).

## Quick Start

### 1. Prepare Network Configuration

Create a custom chain configuration file for your enterprise network:

```bash
# Create directory for chain configs
mkdir -p /opt/fukuii/chains

# Create your enterprise network chain config
cat > /opt/fukuii/chains/myenterprise-chain.conf << 'EOF'
{
  network-id = 88888
  chain-id = "0x15B38"  # 88888 in hex
  
  # Enable all modern EVM features from genesis for latest capabilities
  # All forks enabled at block 0 to ensure enterprise network starts with latest EVM
  frontier-block-number = "0"
  homestead-block-number = "0"
  eip150-block-number = "0"
  eip155-block-number = "0"
  eip160-block-number = "0"
  eip161-block-number = "0"
  byzantium-block-number = "0"
  constantinople-block-number = "0"
  petersburg-block-number = "0"
  istanbul-block-number = "0"
  
  # Enable ETC-specific forks at genesis for full compatibility
  atlantis-block-number = "0"
  agharta-block-number = "0"
  phoenix-block-number = "0"
  magneto-block-number = "0"
  mystique-block-number = "0"
  spiral-block-number = "0"
  # Olympia (ECIP-1111/1112/1121) is unscheduled, as on ETC mainnet and Mordor: deferred on
  # every chain until a confirmed test block number exists.
  olympia-block-number = "1000000000000000000"

  # Enable ETH-specific forks for maximum compatibility
  muir-glacier-block-number = "0"
  berlin-block-number = "0"
  
  # ECIP forks - may not be relevant for private enterprise chains
  ecip1099-block-number = "1000000000000000000"
  
  # Disable difficulty bomb (not needed in private networks)
  difficulty-bomb-pause-block-number = "0"
  difficulty-bomb-continue-block-number = "0"
  difficulty-bomb-removal-block-number = "0"
  
  max-code-size = "24576"
  dao = null
  account-start-nonce = "0"
  custom-genesis-file = null
  treasury-address = "0011223344556677889900112233445566778899"
  
  # Monetary policy configuration
  # NOTE: In private enterprise chains, monetary policy may not be relevant
  # as block rewards can be set to zero or configured based on business requirements
  monetary-policy {
    first-era-block-reward = "2000000000000000000"
    first-era-reduced-block-reward = "2000000000000000000"
    first-era-constantinople-reduced-block-reward = "2000000000000000000"
    era-duration = 500000000
    reward-reduction-rate = 0
  }
  
  eth-compatible-storage = true
  
  bootstrap-nodes = [
    # Will be populated after validator nodes are started
  ]
}
EOF
```

### 2. Create Node Configuration

```bash
cat > /opt/fukuii/enterprise-node.conf << 'EOF'
include "app.conf"

fukuii {
  blockchains {
    network = "myenterprise"
    custom-chains-dir = "/opt/fukuii/chains"
  }
  
  mining {
    # Set to your validator address
    coinbase = "YOUR_VALIDATOR_ADDRESS"
    mining-enabled = true  # Enable for validator nodes
  }
  
  network {
    rpc {
      # Local administration (admin_nodeInfo, admin_addPeer, the allowlist methods) over the IPC socket,
      # which file permissions protect; IPC serves the admin namespace by default
      ipc.enabled = true
      # JSON-RPC HTTP stays on localhost under the enterprise modifier; see "Exposing RPC beyond the host"
    }
  }
}
EOF
```

### 3. Launch First Validator Node

```bash
# Launch the first node with enterprise modifier
fukuii enterprise -Dconfig.file=/opt/fukuii/enterprise-node.conf
```

### 4. Get Bootstrap Node Information

Query the first node for its enode URL over the IPC socket (`admin` is not served over HTTP by default):

```bash
echo '{"jsonrpc":"2.0","method":"admin_nodeInfo","params":[],"id":1}' \
  | socat - UNIX-CONNECT:$HOME/.fukuii/myenterprise/fukuii.ipc | jq '.result.enode'
```

Output will be something like:
```
"enode://abcd1234...@192.168.1.100:30303"
```

### 5. List the Members in static-nodes.json

Under the `enterprise` modifier the node dials only the nodes in `static-nodes.json` in its datadir; the chain's
`bootstrap-nodes` are ignored. On each node, list the other members:

```bash
cat > ~/.fukuii/myenterprise/static-nodes.json << 'EOF'
[
  "enode://abcd1234...@192.168.1.100:30303"
]
EOF
```

See [Static Nodes Configuration](../for-operators/static-nodes-configuration.md). To also refuse every node that is
not a member, turn on the [node allowlist](#p2p-node-allowlist).

### 6. Launch Additional Nodes

On other machines, use the same configuration and launch:

```bash
fukuii enterprise -Dconfig.file=/opt/fukuii/enterprise-node.conf
```

## Architecture Patterns

### Pattern 1: Single Organization Private Network

**Use Case**: Internal blockchain for a single company

**Architecture**:
- 3-5 validator nodes for redundancy
- Multiple non-validator nodes for applications
- All nodes in same private network/VPN

**Security**:
- Firewall rules restrict P2P ports to internal network
- RPC endpoints exposed only to application servers
- mTLS between the application servers and the node: the RPC listener has `tls { enabled = true, profile, client-auth
  = "need" }` and a truststore holding the internal CA, and each application server presents its own client
  certificate ([Security Hardening](#security-hardening), step 3)
- Metrics scraped over TLS (`metrics.tls`), or kept on loopback

**Example Deployment**:
```
┌─────────────────────────────────────┐
│         Corporate Network           │
│  ┌──────────┐  ┌──────────┐         │
│  │Validator1│  │Validator2│         │
│  │ Mining   │  │ Mining   │         │
│  └──────────┘  └──────────┘         │
│       │              │               │
│       └──────┬───────┘               │
│              │                       │
│       ┌──────▼───────┐               │
│       │  Full Node   │               │
│       │  RPC Exposed │               │
│       └──────────────┘               │
│              │                       │
│       ┌──────▼───────┐               │
│       │ Application  │               │
│       │   Servers    │               │
│       └──────────────┘               │
└─────────────────────────────────────┘
```

### Pattern 2: Consortium Network

**Use Case**: Multiple organizations sharing a blockchain

**Architecture**:
- Each organization runs 1-2 validator nodes
- Each organization has application nodes
- Cross-organization VPN or dedicated network

**Security**:
- **Between the organizations' nodes (P2P)**: the [node allowlist](#p2p-node-allowlist). RLPx, the peer protocol, is
  not TLS: its handshake already proves each node's key, and the allowlist admits only the consortium's node IDs. It
  is enforced right after the handshake and on every dial, and turns discovery off
- **Into each organization's RPC**: mutual TLS (`client-auth = "need"`) with a truststore holding the CAs of the
  organizations allowed to call, and identity rules granting each one its namespaces
- Each organization controls their own validator keys
- Shared governance for network changes, including the membership list (the allowlist file)

**Example Deployment**:
```
┌─────────────┐    ┌─────────────┐    ┌─────────────┐
│   Org A     │    │   Org B     │    │   Org C     │
│ ┌─────────┐ │    │ ┌─────────┐ │    │ ┌─────────┐ │
│ │Validator│ │◄──►│ │Validator│ │◄──►│ │Validator│ │
│ └─────────┘ │    │ └─────────┘ │    │ └─────────┘ │
│      │      │    │      │      │    │      │      │
│ ┌────▼────┐ │    │ ┌────▼────┐ │    │ ┌────▼────┐ │
│ │ Full    │ │    │ │ Full    │ │    │ │ Full    │ │
│ │ Node    │ │    │ │ Node    │ │    │ │ Node    │ │
│ └─────────┘ │    │ └─────────┘ │    │ └─────────┘ │
└─────────────┘    └─────────────┘    └─────────────┘
```

### Pattern 3: Hybrid Public-Private

**Use Case**: Private network with selective interaction with public networks

**Architecture**:
- Private enterprise network for internal operations
- Bridge/relay nodes that connect to BOTH private and public networks
- Bridge nodes facilitate cross-chain transfers or data verification
- Separate key management for private and public operations
- Example: Using public Ethereum for final settlement while keeping internal transactions private

**Important Notes**:
- This pattern does NOT mean running a private node connected to the public network
- Bridge nodes act as intermediaries, maintaining separate connections to each network
- Requires careful security design to prevent exposure of private data
- Use case: Enterprise wanting to anchor private chain state to public blockchain for immutability

## Best Practices

### Network Configuration

1. **Unique Network ID**: Always use a unique `network-id` not used by public networks
   ```hocon
   # Guidelines for selecting network-id:
   #   - Avoid 1 (Ethereum mainnet), 61 (ETC mainnet), 63 (Mordor testnet), etc.
   #   - Check https://chainlist.org/ for existing network IDs
   #   - For private networks, use IDs > 10000 to avoid conflicts
   #   - Common practice: use your organization ID or random 5-digit number
   network-id = 88888  # Choose your own unique ID
   ```

2. **Unique Chain ID**: Essential for preventing replay attacks
   ```hocon
   chain-id = "0x15B38"  # Must be unique
   ```

3. **Peer List**: keep at least 2-3 well-connected members listed for reliability. Under the `enterprise` modifier
   that list is `<datadir>/static-nodes.json` (same enode format); the chain's `bootstrap-nodes` below are used only
   by nodes launched without the modifier
   ```hocon
   bootstrap-nodes = [
     "enode://node1@192.168.1.100:30303",
     "enode://node2@192.168.1.101:30303",
     "enode://node3@192.168.1.102:30303"
   ]
   ```

### Security Hardening

1. **Firewall Configuration**:
   ```bash
   # Allow P2P only from trusted network
   sudo ufw allow from 192.168.1.0/24 to any port 30303 proto tcp
   sudo ufw allow from 192.168.1.0/24 to any port 30303 proto udp
   sudo ufw allow from 192.168.1.0/24 to any port 30303 proto tcp
   
   # Allow RPC only from application servers
   sudo ufw allow from 192.168.2.0/24 to any port 8546 proto tcp
   ```

2. **RPC Security**: least privilege per transport. HTTP and WebSocket serve `eth,net,web3,fukuii` by default;
   `admin`, `personal` and `debug` belong on the IPC socket or behind an identity rule, never in a network
   listener's `apis`.
   ```hocon
   network {
     rpc {
       http {
         apis = "eth,net,web3"
         
         # Enable rate limiting
         rate-limit {
           enabled = true
           min-request-interval = 1.second
         }
         
         # Restrict CORS
         cors-allowed-origins = ["https://yourdapp.example.com"]
       }
     }
   }
   ```

3. **Mutual TLS for RPC**: issue the node a server certificate and each application server a client certificate from
   your CA (or, for a test, from the CA `tls/gen-cert.sh ca` makes; see
   [TLS Operations](tls-operations.md#a-test-ca-server-and-client-certificates-mtls)), then:
   ```hocon
   fukuii {
     tls.profiles.rpc {
       keystore   { path = "/opt/fukuii/tls/node.p12", type = "pkcs12", password-file = "/opt/fukuii/tls/node.password" }
       truststore { path = "/opt/fukuii/tls/fukuii-ca-truststore.p12", type = "pkcs12", password-file = "/opt/fukuii/tls/fukuii-ca-truststore.password" }
     }
     network.rpc {
       http.tls { enabled = true, profile = "rpc", client-auth = "need" }
       ws.tls   { enabled = true, profile = "rpc", client-auth = "need" }
       # Optional: one operator certificate gets admin on top of the listener's apis
       client-identities = [
         { name = "ops", match { san-dns = "ops.internal" }, apis = "eth,net,web3,admin" }
       ]
     }
   }
   ```
   The node refuses to start on any TLS misconfiguration, reloads renewed certificates without a restart, and
   exports `app_tls_handshake_failures_total` and `app_tls_certificate_expiry_seconds`. The deprecated
   `mode = "https"` with a `certificate` block still maps to a server-only profile until it is removed in 0.13.0; do
   not use it for new deployments.

4. **P2P Node Allowlist**: see [P2P Node Allowlist](#p2p-node-allowlist) below.

5. **Node Key Management**: the node key is the node's P2P identity, which the allowlist lists:
   ```bash
   # Secure node key file
   chmod 600 ~/.fukuii/myenterprise/node.key
   
   # Regular backup of keys
   cp ~/.fukuii/myenterprise/node.key /secure/backup/location/
   ```

### Exposing RPC Beyond the Host

The `enterprise` modifier sets `fukuii.network.rpc.http.interface = localhost` as a JVM system property at launch,
which overrides the configuration file and any earlier `-D`. To serve JSON-RPC HTTP to application servers on other
hosts, launch **without** the modifier and set what it would have set yourself, then add the interface and TLS:

```hocon
fukuii {
  network {
    discovery {
      discovery-enabled = false
      reuse-known-nodes = true
    }
    automatic-port-forwarding = false
    rpc.http {
      interface = "192.168.2.10"
      tls { enabled = true, profile = "rpc", client-auth = "need" }
    }
  }
  sync.blacklist-duration = 0.seconds
}
```

Without the modifier the node also dials the chain's `bootstrap-nodes`, not only `static-nodes.json`; with the node
allowlist on, every bootstrap node and static node must be listed anyway. The WebSocket listener
(`network.rpc.ws.interface`) is not pinned by the modifier.

On a non-loopback interface, TLS is required: without `tls.enabled = true` the node refuses to start unless
`allow-insecure-bind = true` is set for that listener, which accepts plaintext deliberately (and logs a WARN line at
every start). See [the migration section](tls-operations.md#migration).

### P2P Node Allowlist

For a consortium, admit only the members' node IDs:

```hocon
fukuii.network.peer.node-allowlist {
  enabled = true
  file = "/opt/fukuii/allowlist.json"     # JSON array in the static-nodes.json format; runtime edits write here
  # nodes = [ "enode://<128 hex>@192.168.1.101:30303" ]   # entries that only a restart changes
}
```

- The remote node's ID is checked right after the RLPx handshake (before any devp2p message) and before every dial;
  an unlisted node is dropped. Discovery (discv4, discv5, DNS) does not run while the allowlist is on.
- Every node in `static-nodes.json` and every chain bootstrap node must be on the list, or the node refuses to start.
- Changes take effect without a restart: edit the file (checked every `reload-interval`, default one minute), or call
  `admin_addNodesToAllowlist` / `admin_removeNodesFromAllowlist` over IPC (or for a client certificate whose identity
  rule grants `admin`). A removed node is disconnected.

Full reference: [Peering → Consortium Node Allowlist](peering.md#consortium-node-allowlist).

### Performance Optimization

1. **JVM Tuning**:
   ```bash
   # Allocate appropriate heap memory (8GB example)
   export JAVA_OPTS="-Xms8g -Xmx8g -XX:+UseG1GC"
   fukuii enterprise -Dconfig.file=/opt/fukuii/enterprise-node.conf
   ```

2. **Database Configuration**:
   ```hocon
   db {
     rocksdb {
       # Increase cache size for better performance
       cache-size = 536870912  # 512MB
       
       # Tune for SSD storage
       max-open-files = 1024
     }
   }
   ```

3. **Peer Connection Tuning**:
   ```hocon
   network {
     peer {
       # Adjust based on network size
       min-outgoing-peers = 10
       max-outgoing-peers = 30
       max-incoming-peers = 20
     }
   }
   ```

### Monitoring and Operations

1. **Health Checks**:
   ```bash
   # Check node is syncing
   curl -X POST --data '{
     "jsonrpc":"2.0",
     "method":"eth_syncing",
     "params":[],
     "id":1
   }' http://localhost:8546
   
   # Check peer count
   curl -X POST --data '{
     "jsonrpc":"2.0",
     "method":"net_peerCount",
     "params":[],
     "id":1
   }' http://localhost:8546
   ```

2. **Metrics Collection**:
   Enable Prometheus metrics for monitoring. The endpoint binds `localhost` by default; a Prometheus server on
   another host needs `interface` set, and then TLS (or `allow-insecure-bind = true`):
   ```hocon
   metrics {
     enabled = true
     port = 9095
     # interface = "192.168.2.10"
     # tls { enabled = true, profile = "rpc", client-auth = "need" }   # Prometheus presents a client certificate
   }
   ```

3. **Log Management**:
   ```bash
   # Configure log rotation in logback.xml
   # Monitor logs for issues
   tail -f ~/.fukuii/myenterprise/logs/fukuii.log
   ```

## Common Use Cases

### Development Network

For rapid development and testing:

```bash
# Launch with instant block generation (mocked consensus)
cat > dev-network.conf << 'EOF'
include "app.conf"

fukuii {
  blockchains {
    network = "devnet"
    custom-chains-dir = "/opt/fukuii/chains"
  }
  
  mining {
    protocol = mocked  # Instant blocks
    mining-enabled = true
    coinbase = "0x1234567890123456789012345678901234567890"
  }
  
  network {
    rpc {
      http {
        # Enable test APIs for development
        apis = "eth,web3,net,personal,fukuii,debug,qa,test"
      }
    }
  }
}
EOF

fukuii enterprise -Dconfig.file=dev-network.conf
```

### Production Deployment

For production enterprise deployments:

```bash
# Validator node configuration
cat > validator.conf << 'EOF'
include "app.conf"

fukuii {
  blockchains {
    network = "production-enterprise"
    custom-chains-dir = "/opt/fukuii/chains"
  }
  
  mining {
    protocol = pow
    mining-enabled = true
    coinbase = "0xYOUR_VALIDATOR_ADDRESS"
  }
  
  network {
    rpc {
      http {
        # Restrict API surface
        apis = "eth,web3,net"
        
        # Enable rate limiting
        rate-limit {
          enabled = true
          min-request-interval = 1.second
        }
      }
    }
  }
  
  # Enable metrics
  metrics {
    enabled = true
  }
}
EOF

fukuii enterprise -Dconfig.file=validator.conf
```

## Troubleshooting

### Nodes Not Discovering Each Other

**Symptoms**: Peer count remains 0

**Solutions**:
1. Verify the nodes in `static-nodes.json` are reachable (under the `enterprise` modifier the chain's
   `bootstrap-nodes` are not used):
   ```bash
   nc -zv 192.168.1.100 30303
   ```

2. Check firewall rules allow P2P ports

3. Verify network IDs match across all nodes

4. With the node allowlist on, check both sides list each other: `admin_getNodesAllowlist`, and
   `app_p2p_allowlist_rejections_total{direction}` rising on the node that refuses

5. Manually add peer (over IPC, where `admin` is served; with the allowlist on, the node must be listed first):
   ```bash
   echo '{"jsonrpc":"2.0","method":"admin_addPeer","params":["enode://...@IP:PORT"],"id":1}' \
     | socat - UNIX-CONNECT:$HOME/.fukuii/myenterprise/fukuii.ipc
   ```

### Genesis Hash Mismatch

**Symptoms**: Peers disconnect immediately after handshake

**Solution**: Ensure all nodes use identical genesis configuration

```bash
# Check genesis hash on each node
curl -X POST --data '{
  "jsonrpc":"2.0",
  "method":"eth_getBlockByNumber",
  "params":["0x0", false],
  "id":1
}' http://localhost:8546 | jq '.result.hash'
```

### Performance Issues

**Symptoms**: Slow block processing, high CPU usage

**Solutions**:
1. Increase JVM heap memory
2. Verify SSD storage is used
3. Reduce peer connection limits
4. Check database cache size
5. Monitor system resources

## Migration and Upgrades

### Adding New Validators

1. Launch new node with same chain configuration
2. Sync with network (will download full chain)
3. Enable mining once synced
4. Add the new node to every member's `static-nodes.json` and, with the node allowlist on, to the allowlist
   (`admin_addNodesToAllowlist` or the allowlist file) before it first connects

### Network Upgrades

For scheduled fork activations:

1. Update chain configuration with new fork block number
2. Deploy updated configuration to all nodes before fork block
3. Monitor activation and verify all nodes follow new rules

Example:
```hocon
# Schedule upgrade at block 100,000
berlin-block-number = "100000"
```

## Related Documentation

- [Custom Networks Runbook](custom-networks.md) - Detailed configuration reference
- [Node Configuration](node-configuration.md) - General node settings
- [Security Runbook](security.md) - Security best practices
- [TLS Operations](tls-operations.md) - TLS profiles, mutual TLS, identity rules and rotation on every listener
- [Peering](peering.md#consortium-node-allowlist) - The P2P node allowlist and its `admin_` methods

## Support and Resources

- GitHub Issues: https://github.com/chippr-robotics/fukuii/issues
- Documentation: https://github.com/chippr-robotics/fukuii/tree/main/docs
- Configuration Templates: `src/main/resources/conf/enterprise-template.conf`

---

**Document Version**: 1.0  
**Last Updated**: 2025-12-06  
**Maintainer**: Chippr Robotics LLC
