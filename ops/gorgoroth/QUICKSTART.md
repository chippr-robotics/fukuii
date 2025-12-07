# Gorgoroth 3-Node Network - Quick Start Guide

This guide provides step-by-step instructions for setting up and running a 3-node Fukuii test network with the gorgoroth configuration.

## Prerequisites

- Docker 20.10+
- Docker Compose 2.0+
- 4GB RAM minimum
- 5GB free disk space
- fukuii-cli installed (from `ops/tools/fukuii-cli.sh`)

## Quick Start (5 Minutes)

### 1. Pull Latest Images

```bash
docker pull ghcr.io/chippr-robotics/fukuii:latest
```

### 2. Start the 3-Node Network

From the repository root:

```bash
cd ops/gorgoroth
../../ops/tools/fukuii-cli.sh start 3nodes
```

Or if you have fukuii-cli installed globally:

```bash
fukuii-cli start 3nodes
```

### 3. Wait for Nodes to Initialize

The nodes need about 30-45 seconds to fully initialize and establish peer connections.

```bash
sleep 45
```

### 4. Verify Nodes are Running

Check the status of all containers:

```bash
fukuii-cli status 3nodes
```

You should see all 3 containers running and healthy.

### 5. Test Peer Connections

Check that nodes are connected to each other:

```bash
# Check peer count on node 1
curl -X POST -H "Content-Type: application/json" \
  --data '{"jsonrpc":"2.0","method":"net_peerCount","params":[],"id":1}' \
  http://localhost:8546

# Expected result: {"jsonrpc":"2.0","result":"0x2","id":1}
# This means node1 is connected to 2 peers (node2 and node3)
```

### 6. Check Sync Status

```bash
curl -X POST -H "Content-Type: application/json" \
  --data '{"jsonrpc":"2.0","method":"eth_syncing","params":[],"id":1}' \
  http://localhost:8546
```

### 7. Test Block Production

Wait a few minutes for blocks to be mined, then check the block number:

```bash
curl -X POST -H "Content-Type: application/json" \
  --data '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}' \
  http://localhost:8546
```

## Accessing the Nodes

### HTTP JSON-RPC Endpoints

- Node 1: http://localhost:8545
- Node 2: http://localhost:8547
- Node 3: http://localhost:8549

### WebSocket Endpoints

- Node 1: ws://localhost:8546
- Node 2: ws://localhost:8548
- Node 3: ws://localhost:8550

### P2P Ports (for external peers)

- Node 1: 30303
- Node 2: 30304
- Node 3: 30305

## Using with Insomnia

The repository includes an Insomnia workspace with a pre-configured environment for the Gorgoroth 3-node network.

1. Import `insomnia_workspace.json` into Insomnia
2. Select the "Gorgoroth 3-Node Test Network" environment
3. Use the pre-configured variables:
   - `node1_http`, `node1_ws` - Node 1 endpoints
   - `node2_http`, `node2_ws` - Node 2 endpoints
   - `node3_http`, `node3_ws` - Node 3 endpoints
   - `node_url` - Default node (points to node1 WS)
   - `address`, `recipient`, `contract` - Pre-funded genesis accounts

## Monitoring and Logs

### View Logs

```bash
# Follow all logs
fukuii-cli logs 3nodes

# View logs for a specific node
docker logs gorgoroth-fukuii-node1 -f
```

### Collect Logs for Debugging

```bash
fukuii-cli collect-logs 3nodes
```

This creates a timestamped directory with logs from all containers.

## Stopping and Cleaning Up

### Stop the Network

```bash
fukuii-cli stop 3nodes
```

### Clean Up (Remove Containers and Data)

```bash
fukuii-cli clean 3nodes
```

⚠️ **Warning**: This will delete all blockchain data and node keys. The network will start fresh on next run.

## Troubleshooting

### Nodes Not Connecting

1. Check that all containers are running:
   ```bash
   fukuii-cli status 3nodes
   ```

2. Check logs for connection errors:
   ```bash
   fukuii-cli logs 3nodes | grep -i "peer\|connection"
   ```

3. Verify static nodes configuration:
   ```bash
   docker exec gorgoroth-fukuii-node1 cat /app/data/static-nodes.json
   ```

### No Blocks Being Mined

1. Check that mining is enabled:
   ```bash
   curl -X POST -H "Content-Type: application/json" \
     --data '{"jsonrpc":"2.0","method":"eth_mining","params":[],"id":1}' \
     http://localhost:8546
   ```

2. Check for mining-related errors in logs:
   ```bash
   docker logs gorgoroth-fukuii-node1 | grep -i "mining\|miner"
   ```

### Port Already in Use

If you see port conflict errors, another service might be using the ports. You can either:

1. Stop the conflicting service
2. Modify the port mappings in `docker-compose-3nodes.yml`

## Network Configuration

### Network Details

- **Network Name**: Gorgoroth
- **Chain ID**: 1337 (0x539)
- **Consensus**: Ethash (Proof of Work)
- **Block Time**: ~15 seconds
- **Discovery**: Disabled (static nodes only)

### Pre-funded Genesis Accounts

Three accounts are pre-funded in the genesis block:

| Address | Balance |
|---------|---------|
| 0x1000000000000000000000000000000000000001 | 1,000,000,000,000 ETC |
| 0x2000000000000000000000000000000000000002 | 1,000,000,000,000 ETC |
| 0x3000000000000000000000000000000000000003 | 1,000,000,000,000 ETC |

## Recent Fixes (December 2025)

This setup includes recent fixes for:

1. ✅ **Configuration Loading Bug**: Fixed App.scala to properly load gorgoroth.conf from classpath
2. ✅ **Port Mismatch**: Updated static-nodes.json to use correct port (30303)
3. ✅ **Persistent Node Keys**: Configured datadir to persist node keys in Docker volumes

## Next Steps

- Read the full [Gorgoroth README](README.md) for advanced configuration options
- Check the [TROUBLESHOOTING_REPORT.md](TROUBLESHOOTING_REPORT.md) for detailed issue analysis
- Explore multi-client setups with Core-Geth and Besu
- Set up monitoring with Prometheus and Grafana

## Support

For issues or questions:

- Check the [Troubleshooting](#troubleshooting) section
- Review the [TROUBLESHOOTING_REPORT.md](TROUBLESHOOTING_REPORT.md)
- Open an issue on [GitHub](https://github.com/chippr-robotics/fukuii/issues)
