# First Start Runbook

**Audience**: Operators deploying Fukuii for the first time  
**Estimated Time**: 30-60 minutes (plus sync time)  
**Prerequisites**: Basic Linux command-line knowledge

## Overview

This runbook guides you through the initial setup and first-time startup of a Fukuii Ethereum Classic node. After completing this guide, you will have a fully operational node synchronizing with the ETC network.

## Table of Contents

1. [Prerequisites](#prerequisites)
2. [Installation Methods](#installation-methods)
3. [Initial Configuration](#initial-configuration)
4. [First Startup](#first-startup)
5. [Verification](#verification)
6. [Post-Startup Configuration](#post-startup-configuration)
7. [Troubleshooting](#troubleshooting)

## Prerequisites

### System Requirements

**Minimum Requirements:**
- **CPU**: 4 cores
- **RAM**: 8 GB
- **Disk**: 500 GB SSD (recommended)
- **Network**: Stable internet connection with at least 10 Mbps

**Recommended Requirements:**
- **CPU**: 8+ cores
- **RAM**: 16 GB
- **Disk**: 1 TB NVMe SSD
- **Network**: 100 Mbps or higher

### Software Requirements

**For Docker deployment:**
- Docker 20.10+
- docker-compose (optional, for multi-container setups)

**For source/binary deployment:**
- JDK 21 (OpenJDK or Oracle JDK)
- (Optional) Python 3.x for auxiliary scripts

### Network Requirements

Ensure the following ports are accessible:
- **30303/UDP** - Discovery protocol (inbound/outbound)
- **9076/TCP** - Ethereum P2P protocol (inbound/outbound)
- **8546/TCP** - JSON-RPC HTTP API (inbound, if exposing API)

## Installation Methods

Choose one of the following installation methods based on your deployment needs.

### Method 1: Docker (Recommended for Production)

Docker is the recommended deployment method as it provides isolation, easier updates, and signed images.

#### Step 1: Pull the Docker Image

```bash
# Pull a specific version (recommended - official releases are signed)
docker pull ghcr.io/chippr-robotics/chordodes_fukuii:v1.0.0

# Verify the image signature (requires cosign)
cosign verify \
  --certificate-identity-regexp=https://github.com/chippr-robotics/fukuii \
  --certificate-oidc-issuer=https://token.actions.githubusercontent.com \
  ghcr.io/chippr-robotics/chordodes_fukuii:v1.0.0
```

#### Step 2: Create Data Directories

```bash
# Create persistent volumes
docker volume create fukuii-data
docker volume create fukuii-conf
```

#### Step 3: Start the Container

```bash
docker run -d \
  --name fukuii \
  --restart unless-stopped \
  -p 9076:9076 \
  -p 30303:30303/udp \
  -v fukuii-data:/app/data \
  -v fukuii-conf:/app/conf \
  ghcr.io/chippr-robotics/chordodes_fukuii:v1.0.0
  # ⚠️ SECURITY WARNING: Do NOT expose RPC port 8546 to public internet
  # For internal RPC access, use: -p 127.0.0.1:8546:8546
  # See docs/runbooks/security.md for details
```

#### Step 4: View Logs

```bash
docker logs -f fukuii
```

For more Docker options, see [docker/README.md](../../docker/README.md).

### Method 2: GitHub Codespaces (Recommended for Development)

For development and testing:

1. Navigate to the Fukuii repository on GitHub
2. Click the green "Code" button
3. Select "Open with Codespaces"
4. Wait for the environment to initialize
5. Run `sbt dist` to build

See [.devcontainer/README.md](../../.devcontainer/README.md) for details.

### Method 3: Building from Source

#### Step 1: Install Dependencies

```bash
# Install JDK 21
# Ubuntu/Debian:
sudo apt-get update
sudo apt-get install openjdk-21-jdk

# macOS (using Homebrew):
brew install openjdk@21

# Verify installation
java -version  # Should show version 21.x
```

#### Step 2: Install SBT

```bash
# Ubuntu/Debian:
echo "deb https://repo.scala-sbt.org/scalasbt/debian all main" | sudo tee /etc/apt/sources.list.d/sbt.list
curl -sL "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x2EE0EA64E40A89B84B2DF73499E82A75642AC823" | sudo apt-key add
sudo apt-get update
sudo apt-get install sbt

# macOS:
brew install sbt
```

#### Step 3: Clone and Build

```bash
# Clone the repository
git clone https://github.com/chippr-robotics/fukuii.git
cd fukuii

# Update submodules
git submodule update --init --recursive

# Build the distribution
sbt dist
```

The distribution will be created in `target/universal/fukuii-<version>.zip`.

#### Step 4: Extract and Prepare

```bash
# Extract the distribution
cd target/universal
unzip fukuii-*.zip
cd fukuii-*/

# Make the launcher executable (if needed)
chmod +x bin/fukuii
```

## Initial Configuration

### Default Configuration

By default, Fukuii uses configuration from `src/main/resources/conf/base.conf` and network-specific configs (e.g., `etc.conf`). The default data directory is:

```
~/.fukuii/<network>/
```

For the ETC mainnet, this becomes `~/.fukuii/etc/`.

### Custom Configuration (Optional)

To customize the configuration:

#### Option 1: Environment Variables

```bash
# Set custom data directory
export FUKUII_DATADIR=/data/fukuii-etc

# Enable test mode
export FUKUII_TESTMODE=true
```

#### Option 2: Configuration File

Create a custom configuration file (e.g., `custom.conf`):

```hocon
include "base.conf"

fukuii {
  datadir = "/custom/path/to/data"
  
  network {
    server-address {
      port = 9076
    }
    
    discovery {
      port = 30303
    }
  }
}
```

Start with the custom config:

```bash
./bin/fukuii -Dconfig.file=/path/to/custom.conf etc
```

### Generate Node Key (Optional)

Each node has a unique identifier. To generate a custom node key:

```bash
./bin/fukuii cli generate-private-key > ~/.fukuii/etc/node.key
chmod 600 ~/.fukuii/etc/node.key
```

If not provided, Fukuii generates one automatically on first start.

## First Startup

### Start the Node

For the Ethereum Classic mainnet:

```bash
./bin/fukuii etc
```

For other networks:
- **Ethereum mainnet**: `./bin/fukuii eth`
- **Mordor testnet**: `./bin/fukuii mordor`
- **Test mode**: `./bin/fukuii testnet-internal`

### What Happens on First Start

1. **Node key generation** (if not exists)
2. **Genesis data loading** - Initializes the blockchain with genesis block
3. **Database initialization** - Creates RocksDB database structure
4. **Peer discovery** - Begins discovering peers on the network
5. **Blockchain synchronization** - Starts downloading blocks

### Expected Startup Log Output

```
INFO  [Fukuii] - Starting Fukuii client version: x.x.x
INFO  [NodeBuilder] - Fixing database...
INFO  [GenesisDataLoader] - Loading genesis data...
INFO  [NodeBuilder] - Starting peer manager...
INFO  [NodeBuilder] - Starting server...
INFO  [NodeBuilder] - Starting sync controller...
INFO  [NodeBuilder] - Starting JSON-RPC HTTP server on 0.0.0.0:8546...
INFO  [DiscoveryService] - Discovery service started
INFO  [SyncController] - Starting blockchain synchronization...
```

### Initial Synchronization

The first sync can take several hours to days depending on:
- Network speed
- Hardware performance (especially disk I/O)
- Number of available peers

**Mainnet ETC blockchain size**: ~200-400 GB (as of 2025)

### Bootstrap Checkpoints (Default Behavior)

**New in v1.1.0**: Fukuii now includes bootstrap checkpoints that significantly improve initial sync times.

#### What are Bootstrap Checkpoints?

Bootstrap checkpoints are trusted block references at known heights (typically major fork activation blocks) that allow your node to begin syncing immediately without waiting for peer consensus. This solves the "bootstrap problem" where a new node had to wait for at least 3 peers before it could determine where to start syncing.

#### Benefits

- **Faster Initial Sync**: Node begins syncing immediately without waiting for peers
- **Improved Reliability**: Less dependent on network conditions and peer availability
- **Better User Experience**: See sync progress much sooner after starting

#### How It Works

1. When starting with an empty database, Fukuii loads pre-configured checkpoint block references
2. These checkpoints serve as trusted starting points for the sync process
3. The node can begin validating and syncing blocks immediately
4. All blocks are still fully validated; checkpoints are just starting hints

#### Configuration

Bootstrap checkpoints are **enabled by default** and configured in the network chain configuration files:
- **ETC Mainnet**: Uses major fork blocks (Spiral, Mystique, Magneto, Phoenix)
- **Mordor Testnet**: Uses testnet fork blocks

To disable bootstrap checkpoints and force traditional pivot sync:

```bash
# Using command-line flag
./bin/fukuii etc --force-pivot-sync

# Or via configuration
fukuii.blockchains.use-bootstrap-checkpoints = false
```

#### When to Disable Checkpoints

You might want to use `--force-pivot-sync` if:
- You want to verify the node syncs without any trusted hints
- You're testing sync behavior
- You're running on a private network without configured checkpoints

For more details, see [ADR-012: Bootstrap Checkpoints](../adr/012-bootstrap-checkpoints.md).

## Verification

### Check Node is Running

```bash
# Check process
ps aux | grep fukuii

# For Docker
docker ps | grep fukuii
```

### Verify Network Connectivity

```bash
# Check if RPC is responding
curl -X POST --data '{"jsonrpc":"2.0","method":"web3_clientVersion","params":[],"id":1}' \
  http://localhost:8546
```

Expected response:
```json
{
  "jsonrpc":"2.0",
  "id":1,
  "result":"Fukuii/v<version>/..."
}
```

### Check Synchronization Status

```bash
# Check sync status
curl -X POST --data '{"jsonrpc":"2.0","method":"eth_syncing","params":[],"id":1}' \
  http://localhost:8546
```

**If syncing:**
```json
{
  "jsonrpc":"2.0",
  "id":1,
  "result":{
    "startingBlock":"0x0",
    "currentBlock":"0x1a2b3c",
    "highestBlock":"0xffffff"
  }
}
```

**If fully synced:**
```json
{
  "jsonrpc":"2.0",
  "id":1,
  "result":false
}
```

### Check Peer Count

```bash
curl -X POST --data '{"jsonrpc":"2.0","method":"net_peerCount","params":[],"id":1}' \
  http://localhost:8546
```

Healthy nodes typically have 10-50 peers. See [peering.md](peering.md) if peer count is low.

### Monitor Logs

```bash
# For binary installation
tail -f ~/.fukuii/etc/logs/fukuii.log

# For Docker
docker logs -f fukuii
```

Key log indicators of healthy operation:
- `Starting blockchain synchronization...`
- `Imported X blocks in Y seconds`
- `Connected to peer: ...`

## Post-Startup Configuration

### Configure Log Rotation (Binary Installation)

Fukuii automatically rotates logs when they reach 10 MB, keeping up to 50 archived logs. To adjust:

Edit the logging configuration or set environment variables before starting:

```bash
export FUKUII_LOG_LEVEL=INFO
./bin/fukuii etc
```

### Enable Metrics (Optional)

Fukuii supports Prometheus metrics for monitoring. To enable:

1. Configure metrics in your config file:

```hocon
fukuii {
  metrics {
    enabled = true
    port = 9095
  }
}
```

2. Access metrics:

```bash
curl http://localhost:9095/metrics
```

See [docker/fukuii/docker-compose.yml](../../docker/fukuii/docker-compose.yml) for a complete monitoring stack with Prometheus and Grafana.

### Configure Firewall

```bash
# Ubuntu/Debian with ufw
sudo ufw allow 30303/udp comment "Fukuii discovery"
sudo ufw allow 9076/tcp comment "Fukuii P2P"

# Optional: Allow RPC (only if needed externally - SECURITY RISK)
# sudo ufw allow 8546/tcp comment "Fukuii RPC"
```

**Security Warning**: Do NOT expose RPC ports (8546/8545) to the public internet without proper authentication and rate limiting.

## Troubleshooting

### Node Won't Start

**Symptom**: Process exits immediately after startup

**Common Causes**:

1. **Port already in use**
   ```bash
   # Check what's using the port
   sudo lsof -i :9076
   sudo lsof -i :30303
   ```
   Solution: Stop conflicting service or change Fukuii ports

2. **Insufficient disk space**
   ```bash
   df -h ~/.fukuii/
   ```
   Solution: Free up disk space (see [disk-management.md](disk-management.md))

3. **Java version mismatch**
   ```bash
   java -version
   ```
   Solution: Install JDK 21

4. **Corrupted database**
   
   See [known-issues.md](known-issues.md) for RocksDB recovery procedures

### No Peers Connecting

If `net_peerCount` returns 0 after 5-10 minutes:

1. Verify network connectivity
2. Check firewall rules
3. Verify ports are open: https://canyouseeme.org/
4. See [peering.md](peering.md) for detailed troubleshooting

### Slow Synchronization

If sync is very slow (< 10 blocks/minute on mainnet):

1. Check disk I/O performance (use `iotop` or `iostat`)
2. Verify sufficient peers connected
3. Consider SSD upgrade if using HDD
4. Check [disk-management.md](disk-management.md) for optimization tips

### High Memory Usage

If the node consumes excessive memory:

1. Check JVM heap settings in `.jvmopts`:
   ```
   -Xms1g
   -Xmx4g
   ```
   
2. Adjust based on available RAM (recommended: 4-8 GB heap)

See [known-issues.md](known-issues.md) for JVM tuning guidance.

### Logs Show Errors

See [log-triage.md](log-triage.md) for detailed log analysis and error resolution.

## Next Steps

After your node is running:

1. **Monitor sync progress** - Wait for full synchronization
2. **Set up monitoring** - Configure metrics and alerting
3. **Configure backups** - See [backup-restore.md](backup-restore.md)
4. **Learn peering** - Read [peering.md](peering.md) to optimize network connectivity
5. **Plan disk management** - Review [disk-management.md](disk-management.md)

## Related Runbooks

- [Peering](peering.md) - Network connectivity and peer management
- [Disk Management](disk-management.md) - Managing blockchain data growth
- [Backup & Restore](backup-restore.md) - Data protection strategies
- [Log Triage](log-triage.md) - Understanding and debugging logs
- [Known Issues](known-issues.md) - Common problems and solutions

---

**Document Version**: 1.0  
**Last Updated**: 2025-11-02  
**Maintainer**: Chippr Robotics LLC
