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
./deploy.sh start 3nodes
```

**Nodes:**
- `fukuii-node1` - HTTP: 8545, WS: 8546, P2P: 30303
- `fukuii-node2` - HTTP: 8547, WS: 8548, P2P: 30304
- `fukuii-node3` - HTTP: 8549, WS: 8550, P2P: 30305

### 2. Six Fukuii Nodes (`6nodes`)
Larger Fukuii-only network for scalability testing.

```bash
./deploy.sh start 6nodes
```

**Nodes:**
- `fukuii-node1` through `fukuii-node6`
- Ports range from 8545-8556 (HTTP/WS)
- P2P ports: 30303-30308

### 3. Fukuii + Core-Geth (`fukuii-geth`)
Mixed network with 3 Fukuii and 3 Core-Geth nodes.

```bash
./deploy.sh start fukuii-geth
```

**Fukuii Nodes:**
- Ports: 8545-8550

**Core-Geth Nodes:**
- Ports: 8551-8556
- P2P: 30306-30308

### 4. Fukuii + Besu (`fukuii-besu`)
Mixed network with 3 Fukuii and 3 Hyperledger Besu nodes.

```bash
./deploy.sh start fukuii-besu
```

**Fukuii Nodes:**
- Ports: 8545-8550

**Besu Nodes:**
- Ports: 8551-8556
- P2P: 30306-30308

### 5. Full Mixed Network (`mixed`)
Comprehensive test with all three client implementations (9 nodes total).

```bash
./deploy.sh start mixed
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

### Starting a Network

```bash
cd ops/gorgoroth

# Start 3-node Fukuii network
./deploy.sh start 3nodes

# View logs
./deploy.sh logs 3nodes

# Check status
./deploy.sh status 3nodes
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

### Collecting Logs

```bash
# Collect logs from running network
./collect-logs.sh 3nodes

# Specify custom output directory
./collect-logs.sh fukuii-geth ./my-logs
```

## Deployment Script Usage

The `deploy.sh` script provides easy network management:

```bash
./deploy.sh {start|stop|restart|status|logs|clean} [config]
```

**Commands:**

- `start [config]` - Start the network (default: 3nodes)
- `stop [config]` - Stop the network
- `restart [config]` - Restart the network
- `status [config]` - Show container status
- `logs [config]` - Follow container logs
- `clean [config]` - Remove containers and volumes (with confirmation)

**Examples:**

```bash
# Start 6-node network
./deploy.sh start 6nodes

# View mixed network logs
./deploy.sh logs mixed

# Restart fukuii-geth network
./deploy.sh restart fukuii-geth

# Clean up 3-node network (removes all data)
./deploy.sh clean 3nodes
```

## Log Collection Script Usage

The `collect-logs.sh` script collects logs from all running containers:

```bash
./collect-logs.sh [config] [output-dir]
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
./collect-logs.sh 3nodes

# Collect logs to specific directory
./collect-logs.sh fukuii-geth ./debug-logs
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
├── deploy.sh
├── collect-logs.sh
└── README.md
```

## Troubleshooting

### Containers Won't Start

```bash
# Check if ports are already in use
docker ps -a

# View container logs
./deploy.sh logs 3nodes

# Clean and restart
./deploy.sh clean 3nodes
./deploy.sh start 3nodes
```

### Nodes Not Connecting

For Fukuii nodes in the 3-node configuration, peer discovery is managed via `static-nodes.json`. Ensure the static node files are properly mounted.

For other configurations, nodes use Docker networking and should connect automatically via the bridge network.

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
./collect-logs.sh 3nodes ./debug-logs

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
./deploy.sh start 3nodes
# Make code changes
./deploy.sh restart 3nodes
```

### Multi-Client Compatibility

Test Fukuii compatibility with other clients:

```bash
# Test with Core-Geth
./deploy.sh start fukuii-geth

# Test with Besu
./deploy.sh start fukuii-besu

# Test with both
./deploy.sh start mixed
```

### Performance Testing

Use the 6-node configuration for load testing:

```bash
./deploy.sh start 6nodes
# Run performance tests against multiple nodes
```

### Bug Reproduction

Create isolated environment for debugging:

```bash
# Start network
./deploy.sh start 3nodes

# Reproduce issue
# ...

# Collect logs
./collect-logs.sh 3nodes ./bug-reproduction-logs
```

## Related Resources

- [Fukuii Documentation](../../README.md)
- [Docker Deployment Guide](../../docs/deployment/docker.md)
- [Operations Runbooks](../../docs/runbooks/README.md)

## Support

For issues or questions:

- Check the [Troubleshooting](#troubleshooting) section
- Review container logs with `./deploy.sh logs [config]`
- Open an issue on [GitHub](https://github.com/chippr-robotics/fukuii/issues)

## License

This configuration is part of the Fukuii project and is distributed under the Apache 2.0 License.
