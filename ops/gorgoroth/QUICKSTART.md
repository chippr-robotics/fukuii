# Gorgoroth 3-Node Network - Quick Start Guide

This guide provides step-by-step instructions for setting up and running a 3-node Fukuii test network with the gorgoroth configuration.

## Prerequisites

Before starting the Gorgoroth trials, you must have:

- **Docker 20.10+** - Container runtime
- **Docker Compose 2.0+** - Container orchestration
- **4GB RAM minimum** - System memory
- **5GB free disk space** - Storage for blockchain data
- **fukuii-cli** - Management tool (from `ops/tools/fukuii-cli.sh`)

### Verify Prerequisites

Run the automated prerequisite check to ensure your system is ready:

```bash
./ops/tools/check-docker.sh
```

This script will verify that Docker and Docker Compose are installed and meet the minimum version requirements.

**Expected output:**
```
✓ Docker 24.0.6 installed (>= 20.10 required)
✓ Docker daemon is running
✓ Docker Compose 2.21.0 installed (>= 2.0 required)
✓ Available RAM: 16.0GB
✓ Available disk space: 100GB

All Docker prerequisites are met!
```

### Alternative: Build-Only Validation (No Docker Required)

If you're in an environment without Docker (e.g., GitHub Codespaces, CI/CD), you can validate the build process only:

```bash
./ops/tools/validate-build.sh
```

This will compile the code and create the distribution package but skip network tests.

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

### 3. Wait for DAG Generation and Peer Discovery

The nodes need about 60-90 seconds to:
1. Generate the DAG (Directed Acyclic Graph) for mining
2. Load static peer configurations
3. Establish connections with peer nodes

```bash
sleep 90
```

⚠️ **First Run Behavior**: On the very first startup, each node will generate a unique node key and persist it in a Docker volume. The static-nodes.json files in the repository contain the persistent enode IDs that these keys will generate, so nodes should connect automatically.

⚠️ **Volume Shadowing Fix (v0.1.147+)**: Previous versions had a Docker volume configuration issue where bind-mounted static-nodes.json files were shadowed by named volumes. This has been fixed in v0.1.147 by removing the conflicting bind mounts. The static-nodes.json files are now initialized when volumes are first created.

**If Nodes Don't Connect (Troubleshooting)**:

If peer count remains at 0 after 2 minutes, you may need to populate the volumes with the static-nodes.json files:

```bash
# Stop the network first
fukuii-cli stop 3nodes

# Pre-populate static-nodes.json in volumes
docker run --rm -v gorgoroth_fukuii-node1-data:/data -v $(pwd)/conf/node1:/host busybox cp /host/static-nodes.json /data/static-nodes.json
docker run --rm -v gorgoroth_fukuii-node2-data:/data -v $(pwd)/conf/node2:/host busybox cp /host/static-nodes.json /data/static-nodes.json
docker run --rm -v gorgoroth_fukuii-node3-data:/data -v $(pwd)/conf/node3:/host busybox cp /host/static-nodes.json /data/static-nodes.json

# Restart the network
fukuii-cli start 3nodes
sleep 90
```

**Note**: Once the static-nodes.json files are in the volumes, they persist across restarts. Node keys also persist in volumes, so enode IDs remain constant.

### 4. Verify Nodes are Running

Check the status of all containers:

```bash
fukuii-cli status 3nodes
```

You should see all 3 containers running and healthy.

### 5. Test Peer Connections

Check that nodes are connected to each other:

```bash
# Check peer count on node 1 (using HTTP JSON-RPC port 8546)
curl -X POST -H "Content-Type: application/json" \
  --data '{"jsonrpc":"2.0","method":"net_peerCount","params":[],"id":1}' \
  http://localhost:8546

# Expected result: {"jsonrpc":"2.0","result":"0x2","id":1}
# This means node1 is connected to 2 peers (node2 and node3)
```

**Note**: Fukuii uses port 8546 for HTTP JSON-RPC and port 8545 for WebSocket connections.

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

**First-time setup issue**: If this is the first time running the network, the placeholder enode IDs in static-nodes.json won't match the actual generated keys. See Step 3 in [Quick Start](#quick-start-5-minutes) for the solution.

1. Check that all containers are running:
   ```bash
   fukuii-cli status 3nodes
   ```

2. Check logs for connection errors:
   ```bash
   fukuii-cli logs 3nodes | grep -i "peer\|connection"
   ```

3. Verify static nodes configuration matches actual enode IDs:
   ```bash
   # Check what's in the static-nodes.json inside the container
   docker exec gorgoroth-fukuii-node1 cat /app/data/static-nodes.json
   
   # Check the actual enode ID for this node
   docker logs gorgoroth-fukuii-node1 2>&1 | grep "Node address" | tail -1
   ```
   
4. If the static-nodes.json file is empty or missing inside the container, populate the volumes manually (see Step 3 troubleshooting section above).

5. If using volumes from a previous version (&lt;0.1.147), you may have empty static-nodes.json files. Clean up and start fresh:
   ```bash
   fukuii-cli clean 3nodes
   fukuii-cli start 3nodes
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
4. ✅ **Docker Volume Shadowing (v0.1.147)**: Fixed conflicting bind mount that prevented static-nodes.json from being loaded
5. ✅ **Static Peer Loading**: Implemented static peer loading feature with clear logging

## Next Steps

- Read the full [Gorgoroth README](README.md) for advanced configuration options
- Check the [TROUBLESHOOTING_REPORT.md](TROUBLESHOOTING_REPORT.md) for detailed issue analysis
- Explore multi-client setups with Core-Geth and Besu
- Set up monitoring with Prometheus and Grafana

## Known Limitations

### Version 0.1.147+

**Static-Nodes Pre-population**:
- Static-nodes.json files must be pre-populated in Docker volumes on first run
- If volumes were created before v0.1.147, they may contain empty files
- **Solution**: Run the volume pre-population commands (see Step 3 troubleshooting) or clean volumes and restart
- **Note**: Once populated, static-nodes.json files persist across restarts

**RPC Port Configuration**:
- Fukuii uses non-standard port assignment: HTTP RPC on 8546, WebSocket on 8545
- Standard Ethereum clients use: HTTP on 8545, WebSocket on 8546
- **Note**: Be sure to use port 8546 when testing HTTP JSON-RPC endpoints

### Prerequisites

**Docker Version Requirements**:
- Docker 20.10+ required
- Docker Compose 2.0+ required
- Run `./ops/tools/check-docker.sh` to verify your environment

**System Resources**:
- Minimum 4GB RAM
- Minimum 5GB free disk space
- For 6-node or mixed networks, 8GB+ RAM recommended

## Support

For issues or questions:

- Check the [Troubleshooting](#troubleshooting) section
- Review the [TROUBLESHOOTING_REPORT.md](TROUBLESHOOTING_REPORT.md) (if available)
- Review the [Gorgoroth Trials Field Reports](../../docs/testing/gorgoroth-trials/)
- Open an issue on [GitHub](https://github.com/chippr-robotics/fukuii/issues)
