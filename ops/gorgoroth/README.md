# Gorgoroth Internal Test Network

> *Named after the plateau in Mordor where Sauron trained his armies*

Gorgoroth is an internal test network for validating Fukuii nodes in private network environments. This test network supports multiple Ethereum client implementations and provides various deployment configurations for comprehensive multi-client testing.

## Overview

The Gorgoroth test network provides:

- **Private Network Testing**: Isolated test environment for Fukuii validation
- **Multi-Client Support**: Compatible with Fukuii, Core-Geth, and Hyperledger Besu
- **Flexible Configurations**: 5 different deployment scenarios
- **Discovery Disabled**: Static peer connections for controlled networking
- **Easy Management**: Simple deployment and log collection scripts

## Network Details

| Property | Value |
|----------|-------|
| **Network Name** | Gorgoroth |
| **Network ID** | 1337 |
| **Chain ID** | 0x539 (1337) |
| **Consensus** | Ethash (Proof of Work) |
| **Block Time** | ~15 seconds |
| **Genesis Block** | Custom genesis with pre-funded accounts |

## Available Configurations

### 1. Three Fukuii Nodes (`3nodes`)
Basic configuration with 3 Fukuii nodes for initial testing.

```bash
fukuii-cli start 3nodes
```

**Nodes:**
- `fukuii-node1` - HTTP: 8545, WS: 8546, P2P: 30303
- `fukuii-node2` - HTTP: 8547, WS: 8548, P2P: 30304
- `fukuii-node3` - HTTP: 8549, WS: 8550, P2P: 30305

### 2. Six Fukuii Nodes (`6nodes`)
Larger Fukuii-only network for scalability testing.

```bash
fukuii-cli start 6nodes
```

**Nodes:**
- `fukuii-node1` through `fukuii-node6`
- Ports range from 8545-8556 (HTTP/WS)
- P2P ports: 30303-30308

### 3. Fukuii + Core-Geth (`fukuii-geth`)
Mixed network with 3 Fukuii and 3 Core-Geth nodes.

```bash
fukuii-cli start fukuii-geth
```

**Fukuii Nodes:**
- Ports: 8545-8550

**Core-Geth Nodes:**
- Ports: 8551-8556
- P2P: 30306-30308

### 4. Fukuii + Besu (`fukuii-besu`)
Mixed network with 3 Fukuii and 3 Hyperledger Besu nodes.

```bash
fukuii-cli start fukuii-besu
```

**Fukuii Nodes:**
- Ports: 8545-8550

**Besu Nodes:**
- Ports: 8551-8556
- P2P: 30306-30308

### 5. Full Mixed Network (`mixed`)
Comprehensive test with all three client implementations (9 nodes total).

```bash
fukuii-cli start mixed
```

**Fukuii Nodes:**
- 3 nodes, ports: 8545-8550

**Core-Geth Nodes:**
- 3 nodes, ports: 8551-8556

**Besu Nodes:**
- 3 nodes, ports: 8557-8562

## Quick Start

### Prerequisites

- Docker 20.10+
- Docker Compose 2.0+
- 8GB RAM (for larger configurations)
- 10GB free disk space

### First-Time 3-Node Setup

For first-time deployment, follow this process to establish peer connections:

```bash
cd ops/gorgoroth

# 1. Start the 3-node network
fukuii-cli start 3nodes

# 2. Wait for nodes to fully initialize (30-45 seconds)
sleep 45

# 3. Synchronize static nodes to establish peer connections
fukuii-cli sync-static-nodes

# 4. Verify peer connections
curl -X POST --data '{"jsonrpc":"2.0","method":"net_peerCount","params":[],"id":1}' \
  http://localhost:8546
```

**What happens during sync:**
- Collects actual enode URLs from all running containers
- Generates a consolidated static-nodes.json file
- Distributes the file to each container
- Restarts containers to apply peer configuration

**Note**: All deployment and configuration commands are accessed through the unified `fukuii-cli` toolkit. See `docs/runbooks/node-configuration.md` for installation and usage instructions.

### Starting a Network (Subsequent Runs)

Once static-nodes.json has been synchronized, you can start/stop normally:

```bash
cd ops/gorgoroth

# Start the network
fukuii-cli start 3nodes

# View logs
fukuii-cli logs 3nodes

# Check status
fukuii-cli status 3nodes
```

### Testing the Network

```bash
# Check block number on node 1
curl -X POST -H "Content-Type: application/json" \
  --data '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}' \
  http://localhost:8545

# Get peer count
curl -X POST -H "Content-Type: application/json" \
  --data '{"jsonrpc":"2.0","method":"net_peerCount","params":[],"id":1}' \
  http://localhost:8545

# Check syncing status
curl -X POST -H "Content-Type: application/json" \
  --data '{"jsonrpc":"2.0","method":"eth_syncing","params":[],"id":1}' \
  http://localhost:8545
```

### Connecting Peers

The network is configured with discovery disabled and uses static peer connections.

**For First-Time Setup:**

Use the `fukuii-cli sync-static-nodes` command to automatically configure peer connections:

```bash
# After starting the network for the first time
fukuii-cli sync-static-nodes
```

This command will:
1. Collect enode URLs from all running containers via RPC
2. Generate a consolidated static-nodes.json file
3. Distribute the file to each container
4. Restart containers to apply the configuration

**For Manual Peer Management:**

You can also manually inspect or add peers using the admin API:

```bash
# Get node info (including enode URL)
curl -X POST -H "Content-Type: application/json" \
  --data '{"jsonrpc":"2.0","method":"admin_nodeInfo","params":[],"id":1}' \
  http://localhost:8546

# Add a peer manually
curl -X POST -H "Content-Type: application/json" \
  --data '{"jsonrpc":"2.0","method":"admin_addPeer","params":["enode://NODE_ID@fukuii-node2:30303"],"id":1}' \
  http://localhost:8546
```

**Note**: The static-nodes.json files in the configuration directories contain placeholder enode IDs for reference only. The `fukuii-cli sync-static-nodes` command handles generating the actual enode URLs from running containers.

### Collecting Logs

```bash
# Collect logs from running network
fukuii-cli collect-logs 3nodes

# Collect logs to custom output directory
fukuii-cli collect-logs 3nodes ./my-logs
```

## Fukuii CLI Usage

All deployment and configuration commands are accessed through the unified `fukuii-cli` tool:

```bash
fukuii-cli {start|stop|restart|status|logs|clean|sync-static-nodes|collect-logs} [config]
```

**Commands:**

- `start [config]` - Start the network (default: 3nodes)
- `stop [config]` - Stop the network
- `restart [config]` - Restart the network
- `status [config]` - Show container status
- `logs [config]` - Follow container logs
- `clean [config]` - Remove containers and volumes (with confirmation)
- `sync-static-nodes` - Synchronize static-nodes.json across all running containers
- `collect-logs [config]` - Collect logs from all containers

**Examples:**

```bash
# Start 6-node network
fukuii-cli start 6nodes

# Synchronize static nodes for peer connections
fukuii-cli sync-static-nodes

# View mixed network logs
fukuii-cli logs mixed

# Check status
fukuii-cli status

# Clean up 3-node network (removes all data)
fukuii-cli clean 3nodes
fukuii-cli clean 3nodes
# Or: fukuii-cli clean 3nodes
```

## Log Collection Script Usage

The `collect-logs.sh` script collects logs from all running containers:

```bash
fukuii-cli collect-logs [config] [output-dir]
```

**What it collects:**

- Container logs from all nodes
- Container inspection data (JSON)
- Container status snapshot
- Resolved docker-compose configuration
- Summary README

**Examples:**

```bash
# Collect logs with automatic timestamped directory
fukuii-cli collect-logs 3nodes

# Collect logs to specific directory
fukuii-cli collect-logs fukuii-geth ./debug-logs
```

## Network Configuration

### Genesis Configuration

The network uses a custom genesis block with:

- **Low difficulty** (0x20000) for fast block production
- **Pre-funded accounts** for testing
- **All ETC forks enabled** at block 0 (Homestead, Atlantis, Agharta, Phoenix)
- **Future forks disabled** for stability

### Node Configuration

Each Fukuii node is configured with:

- **Mining enabled** with unique coinbase addresses
- **Discovery disabled** (static peer connections only)
- **Fast sync disabled** (full sync for test network)
- **SNAP sync disabled** (not needed for small network)
- **JSON-RPC enabled** on all nodes

### Genesis Accounts

Three accounts are pre-funded in the genesis block:

| Address | Balance |
|---------|---------|
| 0x1000...0001 | 1,000,000,000,000 ETC |
| 0x2000...0002 | 1,000,000,000,000 ETC |
| 0x3000...0003 | 1,000,000,000,000 ETC |

## Directory Structure

```
ops/gorgoroth/
├── conf/
│   ├── node1/
│   │   ├── gorgoroth.conf
│   │   └── static-nodes.json
│   ├── node2/
│   ├── node3/
│   ├── node4/
│   ├── node5/
│   ├── node6/
│   ├── besu/
│   │   └── genesis.json
│   └── geth/
│       └── genesis.json
├── docker-compose-3nodes.yml
├── docker-compose-6nodes.yml
├── docker-compose-fukuii-geth.yml
├── docker-compose-fukuii-besu.yml
├── docker-compose-mixed.yml
└── README.md
```

**Note**: `fukuii-cli` is located at `ops/tools/fukuii-cli.sh` and can be used directly or installed system-wide.

## Troubleshooting

### Containers Won't Start

```bash
# Check if ports are already in use
docker ps -a

# View container logs
fukuii-cli logs 3nodes

# Clean and restart
fukuii-cli clean 3nodes
fukuii-cli start 3nodes
```

### Nodes Not Connecting

The network uses discovery disabled mode. Nodes should connect automatically via Docker networking (using service names as hostnames). If nodes are not connecting:

1. **Check if admin API is available**:
   ```bash
   curl -X POST -H "Content-Type: application/json" \
     --data '{"jsonrpc":"2.0","method":"admin_nodeInfo","params":[],"id":1}' \
     http://localhost:8545
   ```

2. **Manually add peers** using the admin API with the enode URL from step 1

3. **For 3-node configuration**: The static-nodes.json files contain placeholder enode IDs. You'll need to either:
   - Generate proper node keys and update static-nodes.json
   - Remove the static-nodes.json volume mounts and use manual peering
   - Use the 6-node or mixed configurations which rely on Docker networking

4. **Check Docker networking**:
   ```bash
   docker network inspect gorgoroth_gorgoroth
   ```

### No Blocks Being Mined

Check that mining is enabled on at least one node:

```bash
# Check mining status
curl -X POST -H "Content-Type: application/json" \
  --data '{"jsonrpc":"2.0","method":"eth_mining","params":[],"id":1}' \
  http://localhost:8545
```

### Collect Logs for Debugging

```bash
# Collect all logs for analysis
fukuii-cli collect-logs 3nodes ./debug-logs

# Check the collected logs
ls -lh ./debug-logs/
```

## Port Reference

### 3-Node Configuration

| Node | HTTP RPC | WebSocket | P2P |
|------|----------|-----------|-----|
| fukuii-node1 | 8545 | 8546 | 30303 |
| fukuii-node2 | 8547 | 8548 | 30304 |
| fukuii-node3 | 8549 | 8550 | 30305 |

### 6-Node Configuration

| Node | HTTP RPC | WebSocket | P2P |
|------|----------|-----------|-----|
| fukuii-node1 | 8545 | 8546 | 30303 |
| fukuii-node2 | 8547 | 8548 | 30304 |
| fukuii-node3 | 8549 | 8550 | 30305 |
| fukuii-node4 | 8551 | 8552 | 30306 |
| fukuii-node5 | 8553 | 8554 | 30307 |
| fukuii-node6 | 8555 | 8556 | 30308 |

### Mixed Configuration (fukuii-geth)

| Node | HTTP RPC | WebSocket | P2P |
|------|----------|-----------|-----|
| fukuii-node1 | 8545 | 8546 | 30303 |
| fukuii-node2 | 8547 | 8548 | 30304 |
| fukuii-node3 | 8549 | 8550 | 30305 |
| geth-node1 | 8551 | 8552 | 30306 |
| geth-node2 | 8553 | 8554 | 30307 |
| geth-node3 | 8555 | 8556 | 30308 |

## Use Cases

### Development Testing

Use the 3-node configuration for quick iteration:

```bash
fukuii-cli start 3nodes
# Make code changes
fukuii-cli restart 3nodes
```

### Multi-Client Compatibility

Test Fukuii compatibility with other clients:

```bash
# Test with Core-Geth
fukuii-cli start fukuii-geth

# Test with Besu
fukuii-cli start fukuii-besu

# Test with both
fukuii-cli start mixed
```

### Performance Testing

Use the 6-node configuration for load testing:

```bash
fukuii-cli start 6nodes
# Run performance tests against multiple nodes
```

### Bug Reproduction

Create isolated environment for debugging:

```bash
# Start network
fukuii-cli start 3nodes

# Reproduce issue
# ...

# Collect logs
fukuii-cli collect-logs 3nodes ./bug-reproduction-logs
```

## Compatibility Testing

The Gorgoroth network includes comprehensive testing infrastructure for validating Fukuii compatibility with Core-Geth and Hyperledger Besu:

### Automated Test Suite

Run the complete compatibility test suite:

```bash
cd ops/gorgoroth/test-scripts
./run-test-suite.sh fukuii-geth
```

The test suite includes:
- Network connectivity validation
- Block propagation testing
- Mining compatibility checks
- Consensus maintenance monitoring
- Fast sync functionality validation

### Individual Tests

Run specific tests:

```bash
# Test network connectivity
./test-connectivity.sh

# Test block propagation
./test-block-propagation.sh

# Test mining compatibility
./test-mining.sh

# Test consensus (30 minute run)
./test-consensus.sh 30

# Test fast sync (NEW)
./test-fast-sync.sh

# 3-Node log review with RLPx and propagation analysis (NEW)
./test-3node-log-review.sh

# Monitor message decompression (NEW)
./monitor-decompression.sh [container_name]
```

### 3-Node Log Review Test

The 3-node log review test provides comprehensive testing and analysis for a network where only one node is mining:

```bash
cd ops/gorgoroth/test-scripts
./test-3node-log-review.sh
```

**What it does:**
- Configures node1 with mining enabled, node2/3 with mining disabled
- Starts the network and synchronizes static peers
- Monitors block generation for 120 seconds
- Captures logs from all nodes
- Analyzes logs for RLPx protocol errors (handshake, encoding, compression)
- Analyzes block header propagation patterns
- Generates comprehensive reports

**Output:**
Creates a timestamped directory with:
- Full logs from all nodes
- Automated error analysis
- Block propagation statistics
- Test metadata and summary report

See [3-Node Log Review Documentation](test-scripts/README-3node-log-review.md) for complete details.

### Documentation

- [**Compatibility Testing Guide**](../../docs/testing/GORGOROTH_COMPATIBILITY_TESTING.md) - Comprehensive testing procedures
- [**Faucet Testing Guide**](../../docs/testing/GORGOROTH_FAUCET_TESTING.md) - Faucet validation procedures
- [**Fast Sync Testing Plan**](../../docs/testing/FAST_SYNC_TESTING_PLAN.md) - Fast sync testing for 6-node network
- [**Validation Status**](../../docs/validation/GORGOROTH_VALIDATION_STATUS.md) - Current validation status and roadmap
- [**Implementation Summary**](../../docs/validation/GORGOROTH_IMPLEMENTATION_SUMMARY.md) - Complete implementation overview
- [**Verification Complete**](VERIFICATION_COMPLETE.md) - Initial validation results
- [**Quick Start**](QUICKSTART.md) - Getting started guide

## Related Resources

- [Fukuii Documentation](../../README.md)
- [Docker Deployment Guide](../../docs/deployment/docker.md)
- [Operations Runbooks](../../docs/runbooks/README.md)
- [**Cirith Ungol Real-World Sync Testing**](../../docs/testing/CIRITH_UNGOL_TESTING_GUIDE.md) - Bonus trial for advanced testers

## Bonus Trial: Cirith Ungol

For community members ready to test **real-world sync capabilities**:

**Cirith Ungol** provides a single-node environment for testing Fukuii sync with **ETC mainnet** (20M+ blocks) and **Mordor testnet**.

**Why use Cirith Ungol after Gorgoroth?**
- Test SNAP/Fast sync with real network history
- Validate long-term stability (24+ hours)
- Measure production performance
- Connect to diverse public peers

**Quick Start:**
```bash
cd ops/cirith-ungol
./start.sh start    # Sync with ETC mainnet
./start.sh logs     # Monitor progress
```

**Full guide:** [Cirith Ungol Testing Guide](../../docs/testing/CIRITH_UNGOL_TESTING_GUIDE.md)

## Support

For issues or questions:

- Check the [Troubleshooting](#troubleshooting) section
- Review container logs with `fukuii-cli logs [config]`
- Open an issue on [GitHub](https://github.com/chippr-robotics/fukuii/issues)

## License

This configuration is part of the Fukuii project and is distributed under the Apache 2.0 License.
