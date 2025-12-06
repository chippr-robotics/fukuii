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

1. **Disabled Public Discovery**: No connection to public Ethereum networks
2. **Bootstrap-Only Peering**: Peers discovered only through configured bootstrap nodes
3. **Disabled Port Forwarding**: No UPnP/NAT-PMP (unnecessary in enterprise LANs)
4. **Localhost RPC Binding**: API endpoints bound to localhost by default for security
5. **Disabled Peer Blacklisting**: Allows faster recovery in controlled environments
6. **Optimized Sync Settings**: Configuration tuned for private network characteristics
7. **TLS Support**: For production deployments, configure TLS/HTTPS for RPC endpoints (see [TLS Operations Guide](tls-operations.md))

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
  
  # Enable ETH-specific forks for maximum compatibility
  muir-glacier-block-number = "0"
  berlin-block-number = "0"
  
  # ECIP checkpointing/treasury forks - may not be relevant for private enterprise chains
  ecip1098-block-number = "1000000000000000000"
  ecip1097-block-number = "1000000000000000000"
  ecip1099-block-number = "1000000000000000000"
  ecip1049-block-number = "1000000000000000000"
  
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
  
  gas-tie-breaker = false
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
      http {
        # Override enterprise default to expose on network if needed
        # WARNING: Only do this on trusted networks with proper firewall rules
        # interface = "0.0.0.0"
      }
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

Query the first node for its enode URL:

```bash
curl -X POST --data '{
  "jsonrpc":"2.0",
  "method":"admin_nodeInfo",
  "params":[],
  "id":1
}' http://localhost:8546 | jq '.result.enode'
```

Output will be something like:
```
"enode://abcd1234...@192.168.1.100:30303"
```

### 5. Update Chain Configuration with Bootstrap Nodes

Edit `/opt/fukuii/chains/myenterprise-chain.conf` and add the bootstrap node:

```hocon
bootstrap-nodes = [
  "enode://abcd1234...@192.168.1.100:30303"
]
```

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
- TLS/mTLS for inter-service communication

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
- Mutual TLS authentication between organizations
- Each organization controls their own validator keys
- Shared governance for network changes

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

**Use Case**: Private network with occasional public network interaction

**Architecture**:
- Private enterprise network for internal operations
- Bridge nodes connecting to public networks
- Separate key management for private and public operations

## Best Practices

### Network Configuration

1. **Unique Network ID**: Always use a unique `network-id` not used by public networks
   ```hocon
   # Guidelines for selecting network-id:
   #   - Avoid 1 (Ethereum mainnet), 61 (ETC mainnet), 3 (Ropsten), etc.
   #   - Check https://chainlist.org/ for existing network IDs
   #   - For private networks, use IDs > 10000 to avoid conflicts
   #   - Common practice: use your organization ID or random 5-digit number
   network-id = 88888  # Choose your own unique ID
   ```

2. **Unique Chain ID**: Essential for preventing replay attacks
   ```hocon
   chain-id = "0x15B38"  # Must be unique
   ```

3. **Bootstrap Nodes**: Maintain at least 2-3 bootstrap nodes for reliability
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
   sudo ufw allow from 192.168.1.0/24 to any port 9076 proto tcp
   sudo ufw allow from 192.168.1.0/24 to any port 30303 proto udp
   sudo ufw allow from 192.168.1.0/24 to any port 30303 proto tcp
   
   # Allow RPC only from application servers
   sudo ufw allow from 192.168.2.0/24 to any port 8546 proto tcp
   ```

2. **RPC Security**:
   ```hocon
   network {
     rpc {
       http {
         # Bind to specific interface, not 0.0.0.0
         interface = "192.168.2.10"
         
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

3. **TLS/HTTPS for RPC**:
   ```hocon
   network {
     rpc {
       http {
         mode = "https"
         certificate {
           keystore-path = "/opt/fukuii/tls/keystore.p12"
           keystore-type = "pkcs12"
           password-file = "/opt/fukuii/tls/password"
         }
       }
     }
   }
   ```

4. **Node Key Management**:
   ```bash
   # Secure node key file
   chmod 600 ~/.fukuii/myenterprise/node.key
   
   # Regular backup of keys
   cp ~/.fukuii/myenterprise/node.key /secure/backup/location/
   ```

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
   Enable Prometheus metrics for monitoring:
   ```hocon
   metrics {
     enabled = true
     port = 9095
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
1. Verify bootstrap nodes are reachable:
   ```bash
   nc -zv 192.168.1.100 30303
   ```

2. Check firewall rules allow P2P ports

3. Verify network IDs match across all nodes

4. Manually add peer:
   ```bash
   curl -X POST --data '{
     "jsonrpc":"2.0",
     "method":"admin_addPeer",
     "params":["enode://...@IP:PORT"],
     "id":1
   }' http://localhost:8546
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
4. Update all nodes' bootstrap node lists

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
- [TLS Operations](tls-operations.md) - Setting up HTTPS for RPC

## Support and Resources

- GitHub Issues: https://github.com/chippr-robotics/fukuii/issues
- Documentation: https://github.com/chippr-robotics/fukuii/tree/main/docs
- Configuration Templates: `src/main/resources/conf/enterprise-template.conf`

---

**Document Version**: 1.0  
**Last Updated**: 2025-12-06  
**Maintainer**: Chippr Robotics LLC
