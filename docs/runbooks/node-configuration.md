# Node Configuration Runbook

**Audience**: Operators and developers configuring Fukuii nodes  
**Estimated Time**: 20-30 minutes  
**Prerequisites**: Basic understanding of HOCON configuration format

## Overview

This runbook provides comprehensive documentation of Fukuii's configuration system, covering chain configuration files, node configuration files, and command line options for launching nodes. Understanding these configuration options is essential for customizing node behavior for different networks, performance tuning, and operational requirements.

## Table of Contents

1. [Configuration System Overview](#configuration-system-overview)
2. [Configuration File Hierarchy](#configuration-file-hierarchy)
3. [Chain Configuration Files](#chain-configuration-files)
4. [Node Configuration Files](#node-configuration-files)
5. [Command Line Options](#command-line-options)
6. [Environment Variables](#environment-variables)
7. [Common Configuration Examples](#common-configuration-examples)
8. [Configuration Reference](#configuration-reference)

## Configuration System Overview

Fukuii uses the Typesafe Config (HOCON) format for configuration management. The configuration system provides:

- **Layered Configuration**: Base settings, network-specific overrides, and custom configurations
- **Environment Variable Support**: Override configuration values using environment variables
- **JVM System Properties**: Set configuration via `-D` flags
- **Type Safety**: Strongly-typed configuration with validation
- **Sensible Defaults**: Production-ready defaults that can be customized as needed

### Configuration File Locations

**Embedded Configurations** (in JAR/distribution):
```
src/main/resources/conf/
├── base.conf              # Base configuration with all defaults
├── app.conf               # Application entry point (includes base.conf)
├── etc.conf               # Ethereum Classic mainnet
├── eth.conf               # Ethereum mainnet
├── mordor.conf            # Mordor testnet
├── testmode.conf          # Test mode configuration
├── metrics.conf           # Metrics configuration
└── chains/
    ├── etc-chain.conf     # ETC chain parameters
    ├── eth-chain.conf     # ETH chain parameters
    ├── mordor-chain.conf  # Mordor chain parameters
    └── ...
```

**Runtime Configurations**:
```
<distribution>/conf/
├── app.conf               # Copied from embedded configs
├── logback.xml            # Logging configuration
└── <custom>.conf          # Your custom configuration files
```

## Configuration File Hierarchy

Fukuii loads configuration in the following order (later sources override earlier ones):

1. **base.conf** - Core defaults for all configurations
2. **Network-specific config** (e.g., etc.conf, mordor.conf) - Includes app.conf and sets network
3. **app.conf** - Application configuration (includes base.conf)
4. **Custom config** - Specified via `-Dconfig.file=<path>`
5. **Environment variables** - Override specific settings
6. **JVM system properties** - Highest priority overrides

### Example Configuration Chain

When starting with `./bin/fukuii etc`:

```
base.conf (defaults)
  ↓
app.conf (includes base.conf)
  ↓
etc.conf (includes app.conf, sets network="etc")
  ↓
etc-chain.conf (loaded automatically for "etc" network)
  ↓
Custom config (if specified with -Dconfig.file)
  ↓
Environment variables
  ↓
JVM system properties
```

## Chain Configuration Files

Chain configuration files define blockchain-specific parameters such as fork block numbers, network IDs, consensus rules, and bootstrap nodes. These files are located in `src/main/resources/conf/chains/`.

### Available Chain Configurations

| Chain File | Network | Network ID | Chain ID |
|------------|---------|------------|----------|
| `etc-chain.conf` | Ethereum Classic | 1 | 0x3d (61) |
| `eth-chain.conf` | Ethereum | 1 | 0x01 (1) |
| `mordor-chain.conf` | Mordor Testnet | 7 | 0x3f (63) |
| `pottery-chain.conf` | Pottery Testnet | 10 | 0xa (10) |
| `test-chain.conf` | Test/Dev | Varies | Varies |

### Chain Configuration Parameters

#### Network Identity
```hocon
{
  # Network identifier for peer discovery and handshaking
  network-id = 1
  
  # Chain ID used for transaction signing (EIP-155)
  chain-id = "0x3d"
  
  # Supported Ethereum protocol capabilities
  capabilities = ["eth/63", "eth/64", "eth/65", "eth/66", "eth/67", "eth/68"]
}
```

#### Hard Fork Block Numbers

Chain configs define when specific protocol upgrades activate:

```hocon
{
  # Frontier (genesis)
  frontier-block-number = "0"
  
  # Homestead fork
  homestead-block-number = "1150000"
  
  # EIP-150 (Gas cost changes)
  eip150-block-number = "2500000"
  
  # EIP-155 (Replay protection)
  eip155-block-number = "3000000"
  
  # Atlantis (ETC-specific, includes Byzantium changes)
  atlantis-block-number = "8772000"
  
  # Agharta (ETC-specific, includes Constantinople + Petersburg)
  agharta-block-number = "9573000"
  
  # Phoenix (ETC-specific, includes Istanbul changes)
  phoenix-block-number = "10500839"
  
  # Magneto (ETC-specific)
  magneto-block-number = "13189133"
  
  # Mystique (ETC-specific, EIP-3529)
  mystique-block-number = "14525000"
  
  # Spiral (ETC-specific, EIP-3855, EIP-3651, EIP-3860)
  spiral-block-number = "19250000"
}
```

#### Consensus and Mining Parameters

```hocon
{
  # Monetary policy (ECIP-1017 for ETC)
  monetary-policy {
    # Initial block reward (5 ETC)
    first-era-block-reward = "5000000000000000000"
    
    # Era duration in blocks
    era-duration = 5000000
    
    # Reward reduction rate per era (20%)
    reward-reduction-rate = 0.2
  }
  
  # Difficulty bomb configuration
  difficulty-bomb-pause-block-number = "3000000"
  difficulty-bomb-continue-block-number = "5000000"
  difficulty-bomb-removal-block-number = "5900000"
}
```

#### Bootstrap Nodes

Chain configs include a list of bootstrap nodes for peer discovery:

```hocon
{
  bootstrap-nodes = [
    "enode://158ac5a4817265d0d8b977660b3dbe9abee5694ed212f7091cbf784ddf47623ed015e1cb54594d10c1c46118747ddabe86ebf569cf24ae91f2daa0f1adaae390@159.203.56.33:30303",
    "enode://942bf2f0754972391467765be1d98206926fc8ad0be8a49cd65e1730420c37fa63355bddb0ae5faa1d3505a2edcf8fad1cf00f3c179e244f047ec3a3ba5dacd7@176.9.51.216:30355",
    # ... more bootstrap nodes
  ]
}
```

## Node Configuration Files

Node configuration files control the operational behavior of the Fukuii client, including networking, storage, RPC endpoints, mining, and synchronization settings.

### Key Configuration Sections

#### Data Directory

```hocon
mantis {
  # Base directory for all node data
  datadir = ${user.home}"/.mantis/"${mantis.blockchains.network}
  
  # Node private key location
  node-key-file = ${mantis.datadir}"/node.key"
  
  # Keystore directory for account keys
  keyStore {
    keystore-dir = ${mantis.datadir}"/keystore"
    minimal-passphrase-length = 7
    allow-no-passphrase = true
  }
}
```

For ETC mainnet, the default data directory is `~/.mantis/etc/`.

#### Network Configuration

**P2P Networking**:
```hocon
mantis {
  network {
    server-address {
      # Listening interface for P2P connections
      interface = "0.0.0.0"
      
      # P2P port
      port = 9076
    }
    
    # Enable UPnP port forwarding
    automatic-port-forwarding = true
    
    discovery {
      # Enable peer discovery
      discovery-enabled = true
      
      # Discovery protocol interface
      interface = "0.0.0.0"
      
      # Discovery port (UDP)
      port = 30303
      
      # Reuse previously known nodes on restart
      reuse-known-nodes = true
      
      # Discovery scan interval
      scan-interval = 1.minutes
    }
  }
}
```

**Peer Management**:
```hocon
mantis {
  network {
    peer {
      # Minimum outgoing peer connections
      min-outgoing-peers = 20
      
      # Maximum outgoing peer connections
      max-outgoing-peers = 50
      
      # Maximum incoming peer connections
      max-incoming-peers = 30
      
      # Connection retry configuration
      connect-retry-delay = 5.seconds
      connect-max-retries = 1
      
      # Timeouts
      wait-for-hello-timeout = 3.seconds
      wait-for-status-timeout = 30.seconds
    }
  }
}
```

#### RPC Configuration

**HTTP JSON-RPC**:
```hocon
mantis {
  network {
    rpc {
      http {
        # Enable HTTP RPC endpoint
        enabled = true
        
        # RPC mode: "http" or "https"
        mode = "http"
        
        # Listening interface (use "localhost" for security)
        interface = "localhost"
        
        # RPC port
        port = 8546
        
        # CORS configuration
        cors-allowed-origins = []
        
        # Rate limiting
        rate-limit {
          enabled = false
          min-request-interval = 10.seconds
        }
      }
      
      # Enabled RPC APIs
      apis = "eth,web3,net,personal,mantis,debug,qa,checkpointing"
    }
  }
}
```

**IPC JSON-RPC**:
```hocon
mantis {
  network {
    rpc {
      ipc {
        # Enable IPC endpoint
        enabled = false
        
        # IPC socket file location
        socket-file = ${mantis.datadir}"/mantis.ipc"
      }
    }
  }
}
```

#### Database Configuration

```hocon
mantis {
  db {
    # Data source: "rocksdb"
    data-source = "rocksdb"
    
    rocksdb {
      # Database path
      path = ${mantis.datadir}"/rocksdb"
      
      # Create if missing
      create-if-missing = true
      
      # Paranoid checks
      paranoid-checks = true
      
      # Block cache size (in bytes)
      block-cache-size = 33554432
    }
  }
}
```

#### Mining Configuration

```hocon
mantis {
  mining {
    # Miner coinbase address
    coinbase = "0011223344556677889900112233445566778899"
    
    # Extra data in mined blocks
    header-extra-data = "mantis"
    
    # Mining protocol: "pow", "mocked", "restricted-pow"
    protocol = pow
    
    # Enable mining on this node
    mining-enabled = false
    
    # Number of parallel mining threads
    num-threads = 1
  }
}
```

#### Sync and Blockchain

```hocon
mantis {
  sync {
    # Perform state sync as part of fast sync
    do-fast-sync = true
    
    # Peers to use for fast sync
    peers-scan-interval = 3.seconds
    
    # Block resolving properties
    max-concurrent-requests = 10
    block-headers-per-request = 128
    block-bodies-per-request = 128
    
    # Pivot block offset for fast sync
    pivot-block-offset = 500
  }
  
  blockchain {
    # Custom genesis file (null = use default)
    custom-genesis-file = null
    
    # Checkpoint configuration
    checkpoint-interval = 1000
  }
}
```

#### Test Mode

```hocon
mantis {
  # Enable test mode (enables test validators and test_ RPC endpoints)
  testmode = false
}
```

## Command Line Options

Fukuii provides several command line options for launching the node with different configurations.

### Main Node Launcher

**Syntax**:
```bash
./bin/fukuii [network] [options]
```

**Network Options** (positional argument):

| Network | Description |
|---------|-------------|
| `etc` | Ethereum Classic mainnet (default if no argument) |
| `eth` | Ethereum mainnet |
| `mordor` | Mordor testnet (ETC testnet) |
| `testnet-internal` | Internal test network |
| (none) | Defaults to ETC mainnet |

**Examples**:
```bash
# Start ETC mainnet node
./bin/fukuii etc

# Start Ethereum mainnet node
./bin/fukuii eth

# Start Mordor testnet node
./bin/fukuii mordor

# Default (ETC mainnet)
./bin/fukuii
```

### Java System Properties

You can override any configuration value using JVM system properties with the `-D` flag:

**Custom Configuration File**:
```bash
./bin/fukuii -Dconfig.file=/path/to/custom.conf etc
```

**Override Specific Values**:
```bash
# Change RPC port
./bin/fukuii -Dmantis.network.rpc.http.port=8545 etc

# Change data directory
./bin/fukuii -Dmantis.datadir=/data/fukuii-etc etc

# Enable test mode
./bin/fukuii -Dmantis.testmode=true testnet-internal

# Change P2P port
./bin/fukuii -Dmantis.network.server-address.port=30303 etc
```

**Multiple Overrides**:
```bash
./bin/fukuii \
  -Dmantis.network.rpc.http.interface=0.0.0.0 \
  -Dmantis.network.rpc.http.port=8545 \
  -Dmantis.datadir=/custom/data \
  etc
```

### JVM Options

Control JVM behavior using options in `.jvmopts` file or via command line:

```bash
# Set heap size
./bin/fukuii -J-Xms2g -J-Xmx8g etc

# Enable GC logging
./bin/fukuii -J-Xlog:gc:file=gc.log etc

# Set custom tmp directory
./bin/fukuii -J-Djava.io.tmpdir=/data/tmp etc
```

### CLI Subcommands

Fukuii includes CLI utilities accessible via the `cli` subcommand:

**Generate Private Key**:
```bash
./bin/fukuii cli generate-private-key
```

**Derive Address from Private Key**:
```bash
./bin/fukuii cli derive-address <private-key-hex>
```

**Generate Key Pairs**:
```bash
./bin/fukuii cli generate-key-pairs <number>
```

**Encrypt Private Key**:
```bash
./bin/fukuii cli encrypt-key <private-key-hex> --passphrase <passphrase>
```

**Generate Genesis Allocs**:
```bash
./bin/fukuii cli generate-allocs --key <private-key> --balance <amount>
```

### Other Launch Modes

The `App.scala` entry point supports additional modes:

**Key Management Tool**:
```bash
./bin/fukuii keytool
```

**Bootstrap Database Download**:
```bash
./bin/fukuii bootstrap <path>
```

**Faucet Server**:
```bash
./bin/fukuii faucet
```

**Signature Validator**:
```bash
./bin/fukuii signature-validator
```

**EC Key Generator**:
```bash
./bin/fukuii eckeygen
```

## Environment Variables

While Fukuii primarily uses configuration files and JVM properties, you can set environment variables that are referenced in configuration files:

**Data Directory**:
```bash
export FUKUII_DATADIR=/data/fukuii-etc
./bin/fukuii -Dmantis.datadir=$FUKUII_DATADIR etc
```

**Test Mode**:
```bash
export FUKUII_TESTMODE=true
./bin/fukuii -Dmantis.testmode=$FUKUII_TESTMODE testnet-internal
```

**User Home** (automatically used):
```bash
# Fukuii respects ${user.home} in config paths
# Default datadir: ${user.home}/.mantis/<network>
```

## Common Configuration Examples

### Example 1: Custom Data Directory

Create a custom configuration file `custom-datadir.conf`:

```hocon
include "base.conf"

mantis {
  datadir = "/data/fukuii-etc"
}
```

Launch:
```bash
./bin/fukuii -Dconfig.file=/path/to/custom-datadir.conf etc
```

### Example 2: Expose RPC to Network

⚠️ **Security Warning**: Only expose RPC on trusted networks with proper firewall rules.

```hocon
include "base.conf"

mantis {
  network {
    rpc {
      http {
        interface = "0.0.0.0"
        port = 8545
        
        # Enable rate limiting for external access
        rate-limit {
          enabled = true
          min-request-interval = 1.second
        }
        
        # Restrict CORS origins
        cors-allowed-origins = ["https://mydapp.example.com"]
      }
    }
  }
}
```

### Example 3: Custom Ports

```hocon
include "base.conf"

mantis {
  network {
    server-address {
      port = 30304  # P2P port
    }
    
    discovery {
      port = 30305  # Discovery port
    }
    
    rpc {
      http {
        port = 8547  # RPC port
      }
    }
  }
}
```

### Example 4: Mining Configuration

```hocon
include "base.conf"

mantis {
  mining {
    # Set your mining address
    coinbase = "0xYOUR_ADDRESS_HERE"
    
    # Enable mining
    mining-enabled = true
    
    # Number of mining threads
    num-threads = 4
    
    # Custom extra data
    header-extra-data = "My Mining Pool"
  }
}
```

### Example 5: Performance Tuning

```hocon
include "base.conf"

mantis {
  # Increase peer limits for better connectivity
  network {
    peer {
      min-outgoing-peers = 30
      max-outgoing-peers = 100
      max-incoming-peers = 50
    }
  }
  
  # Optimize sync settings
  sync {
    max-concurrent-requests = 20
    block-headers-per-request = 256
    block-bodies-per-request = 256
  }
  
  # Larger database cache
  db {
    rocksdb {
      block-cache-size = 134217728  # 128 MB
    }
  }
}
```

Launch with JVM tuning:
```bash
./bin/fukuii \
  -J-Xms4g \
  -J-Xmx16g \
  -J-XX:+UseG1GC \
  -Dconfig.file=/path/to/performance.conf \
  etc
```

### Example 6: Development/Testing Node

```hocon
include "base.conf"

mantis {
  # Enable test mode
  testmode = true
  
  # Local-only RPC
  network {
    rpc {
      http {
        interface = "localhost"
        port = 8545
      }
      
      # Enable all APIs for testing
      apis = "eth,web3,net,personal,mantis,debug,qa,test,checkpointing"
    }
    
    # Minimal peers for faster startup
    peer {
      min-outgoing-peers = 1
      max-outgoing-peers = 5
    }
  }
}
```

## Configuration Reference

### Quick Reference: Common Settings

| Setting | Config Path | Default | Description |
|---------|-------------|---------|-------------|
| Data Directory | `mantis.datadir` | `~/.mantis/<network>` | Base data directory |
| P2P Port | `mantis.network.server-address.port` | `9076` | Ethereum P2P port |
| Discovery Port | `mantis.network.discovery.port` | `30303` | Peer discovery port |
| RPC Port | `mantis.network.rpc.http.port` | `8546` | JSON-RPC HTTP port |
| RPC Interface | `mantis.network.rpc.http.interface` | `localhost` | RPC bind address |
| Min Peers | `mantis.network.peer.min-outgoing-peers` | `20` | Minimum peer connections |
| Max Peers | `mantis.network.peer.max-outgoing-peers` | `50` | Maximum peer connections |
| Test Mode | `mantis.testmode` | `false` | Enable test mode |
| Mining Enabled | `mantis.mining.mining-enabled` | `false` | Enable mining |
| Coinbase | `mantis.mining.coinbase` | - | Mining reward address |

### Configuration File Syntax

HOCON (Human-Optimized Config Object Notation) syntax basics:

**Include Files**:
```hocon
include "base.conf"
```

**Nested Objects**:
```hocon
mantis {
  network {
    peer {
      min-outgoing-peers = 20
    }
  }
}
```

**Dot Notation**:
```hocon
mantis.network.peer.min-outgoing-peers = 20
```

**Variable Substitution**:
```hocon
mantis {
  datadir = ${user.home}"/.mantis/"${mantis.blockchains.network}
  node-key-file = ${mantis.datadir}"/node.key"
}
```

**Lists**:
```hocon
bootstrap-nodes = [
  "enode://...",
  "enode://..."
]
```

**Comments**:
```hocon
# This is a comment
// This is also a comment
```

## Troubleshooting

### Configuration Not Taking Effect

**Problem**: Changed configuration doesn't apply.

**Solutions**:
1. Ensure you're using the correct config file:
   ```bash
   ./bin/fukuii -Dconfig.file=/path/to/your.conf etc
   ```

2. Check configuration precedence - JVM properties override config files:
   ```bash
   # This override takes precedence over config file
   ./bin/fukuii -Dmantis.network.rpc.http.port=8545 etc
   ```

3. Verify HOCON syntax is correct (quotes, braces, commas)

4. Check logs for configuration parsing errors on startup

### Port Already in Use

**Problem**: Node fails to start with "port already in use" error.

**Solution**: Change ports in configuration:
```bash
./bin/fukuii \
  -Dmantis.network.server-address.port=9077 \
  -Dmantis.network.discovery.port=30304 \
  etc
```

### Can't Connect to RPC

**Problem**: RPC requests fail with connection refused.

**Solutions**:
1. Check RPC is enabled:
   ```hocon
   mantis.network.rpc.http.enabled = true
   ```

2. Verify interface binding:
   ```bash
   # For remote access (INSECURE without firewall)
   -Dmantis.network.rpc.http.interface=0.0.0.0
   ```

3. Check firewall allows RPC port (default 8546)

4. Verify node is running:
   ```bash
   curl http://localhost:8546 \
     -X POST \
     -H "Content-Type: application/json" \
     -d '{"jsonrpc":"2.0","method":"web3_clientVersion","params":[],"id":1}'
   ```

## Related Documentation

- [First Start Runbook](first-start.md) - Initial node setup and startup
- [Peering Runbook](peering.md) - Network connectivity and peer management
- [Security Runbook](security.md) - Security configuration and best practices
- [Disk Management](disk-management.md) - Storage configuration and optimization
- [Docker Documentation](../../docker/README.md) - Docker-based deployment

## Additional Resources

- [Typesafe Config Documentation](https://github.com/lightbend/config)
- [HOCON Syntax Guide](https://github.com/lightbend/config/blob/master/HOCON.md)
- [Ethereum Classic ECIPs](https://ecips.ethereumclassic.org/) - Protocol upgrade specifications
- [Fukuii GitHub Repository](https://github.com/chippr-robotics/fukuii)

---

**Document Version**: 1.0  
**Last Updated**: 2025-11-04  
**Maintainer**: Chippr Robotics LLC
