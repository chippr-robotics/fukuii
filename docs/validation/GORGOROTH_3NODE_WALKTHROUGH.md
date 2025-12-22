# Gorgoroth 3-Node E2E Validation Walkthrough

**Purpose**: Complete step-by-step guide for validating Fukuii self-consistency in a 3-node test network. This test validates that Fukuii is functional and self-consistent by testing Fukuii nodes against themselves, covering mining, syncing, and block propagation.

**Time Required**: 1-2 hours  
**Difficulty**: Beginner  
**Prerequisites**: Docker, basic command line knowledge, fukuii-cli.sh installed or aliased

---

## Table of Contents

1. [Overview](#overview)
2. [Prerequisites](#prerequisites)
3. [Setup](#setup)
4. [Phase 1: Network Formation](#phase-1-network-formation)
5. [Phase 2: Mining Validation](#phase-2-mining-validation)
6. [Phase 3: Block Propagation](#phase-3-block-propagation)
7. [Phase 4: Synchronization](#phase-4-synchronization)
8. [Phase 5: Results Collection](#phase-5-results-collection)
9. [Cleanup](#cleanup)
10. [Troubleshooting](#troubleshooting)

---

## Overview

**Goal**: Validate Fukuii self-consistency and core functionality by testing Fukuii nodes against themselves.

This walkthrough validates the following:
- ✅ Network formation with 3 Fukuii nodes
- ✅ Peer discovery and connectivity
- ✅ Mining functionality
- ✅ Block propagation across nodes
- ✅ Node synchronization
- ✅ Fukuii is functional and self-consistent

### What You'll Test

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│ Fukuii Node1│────▶│ Fukuii Node2│────▶│ Fukuii Node3│
│  (Miner)    │◀────│ (Follower)  │◀────│ (Follower)  │
└─────────────┘     └─────────────┘     └─────────────┘
       │                   │                   │
       └───────────────────┴───────────────────┘
   Node1 mines; followers sync and validate
      (deterministic single-miner setup)
```

---

## Prerequisites

### Required Software

```bash
# Verify Docker installation
docker --version
# Required: Docker 20.10+

docker compose version
# Required: Docker Compose 2.0+

# Verify supporting tools
curl --version
jq --version
```

### System Requirements
- **RAM**: 4GB minimum
- **Disk**: 10GB free space
- **OS**: Linux, macOS, or Windows with WSL2

### Clone Repository and Set Up CLI

```bash
# Clone the repository (adjust URL if using a fork)
git clone https://github.com/chippr-robotics/fukuii.git
# Or your fork: git clone https://github.com/YOUR_USERNAME/fukuii.git

cd fukuii
git submodule update --init --recursive

# Set up fukuii-cli for easier management (choose one):

# Option 1: Add to PATH
export PATH="$PATH:$(pwd)/ops/tools"

# Option 2: Create an alias (add to ~/.bashrc or ~/.zshrc for persistence)
alias fukuii-cli="$(pwd)/ops/tools/fukuii-cli.sh"

# Option 3: Install locally
sudo cp ops/tools/fukuii-cli.sh /usr/local/bin/fukuii-cli
sudo chmod +x /usr/local/bin/fukuii-cli

# Verify installation
fukuii-cli help
```

---

## Setup

### Step 1: Verify fukuii-cli Installation

```bash
# Check that fukuii-cli is available
fukuii-cli version

# View available commands
fukuii-cli help
```

**Expected output**: Version information and help menu showing available commands.

### Step 2: Clean Any Previous State

```bash
# Stop and clean any running network
fukuii-cli clean 3nodes
```

> ⚠️ The `clean` command prompts for confirmation. Type `yes` when you really mean to delete the existing containers and volumes.

### Step 3: Ensure Config Mounts Exist (First-Time Setup)

The Docker Compose file mounts `ops/gorgoroth/conf/node*/gorgoroth.conf` and `static-nodes.json` into each container. On fresh clones these files (especially for `node1`) may not exist yet, which causes `docker compose` to fail with `not a directory` errors. Run the following helper once before your first start:

```bash
mkdir -p ops/gorgoroth/conf/node1
cp src/main/resources/conf/gorgoroth.conf ops/gorgoroth/conf/node1/gorgoroth.conf

for node in node1 node2 node3; do
  if [ ! -f "ops/gorgoroth/conf/$node/static-nodes.json" ]; then
    echo "[]" > "ops/gorgoroth/conf/$node/static-nodes.json"
  fi
done
```

Make sure each of the files above is a **regular file** (not a directory or symlink) so Docker can mount it.

> ℹ️ **Network model (updated):** Gorgoroth runs as a *private, no-discovery* cluster. There are no public bootnodes, and peers are established by static peering via `fukuii-cli sync-static-nodes`.

---

## Phase 1: Network Formation

### Step 1.1: Start the Network

```bash
# Start 3-node Fukuii network for self-consistency testing
fukuii-cli start 3nodes
```

**Expected output**:
```
Starting Gorgoroth test network with configuration: 3nodes
Using compose file: docker-compose-3nodes.yml
[+] Running 3/3
 ✔ Container gorgoroth-node1  Started
 ✔ Container gorgoroth-node2  Started
 ✔ Container gorgoroth-node3  Started
Network started successfully!
```

### Step 1.2: Wait for Initialization (30 seconds)

```bash
echo "Waiting for nodes to initialize..."
sleep 30
```

### Step 1.3: Check Container Status

```bash
fukuii-cli status 3nodes
```

**Expected output**: All containers should show status "Up"

### Step 1.4: Verify Logs

```bash
# Follow logs to see startup
fukuii-cli logs 3nodes

# Press Ctrl+C to stop following logs

# Look for successful startup messages:
# - "Starting Fukuii node"
# - "Ethereum node ready"
# - "JSON RPC HTTP server listening on ..."
# - RLPx / enode debug logs (if enabled)
```

### Step 1.5: Sync Static Nodes (Establish Peer Connections)

```bash
# Synchronize peer information across all nodes
fukuii-cli sync-static-nodes
```

**Expected output**:
```
=== Fukuii Static Nodes Synchronization ===
Found running containers:
  - gorgoroth-fukuii-node1
  - gorgoroth-fukuii-node2
  - gorgoroth-fukuii-node3
Collecting enode URLs from containers...
  gorgoroth-fukuii-node1: ✓
  gorgoroth-fukuii-node2: ✓
  gorgoroth-fukuii-node3: ✓
Collected 3 enode(s)
...
=== Static nodes synchronization complete ===
```

> ⏱️ `sync-static-nodes` restarts every container after rewriting `static-nodes.json`. Give Docker ~30 seconds to bring the nodes back before checking peer counts.

### Step 1.6: Wait for Peer Connections

```bash
# Wait for peers to connect after restart
echo "Waiting for peer connections..."
sleep 30
```

### Step 1.7: Verify Peer Connectivity

> ℹ️ RPC quick reference (host ports):
> - Node1: HTTP `http://localhost:8546` (WS on `ws://localhost:8545`)
> - Node2: HTTP `http://localhost:8547` (WS on `ws://localhost:8548`)
> - Node3: HTTP `http://localhost:8549` (WS on `ws://localhost:8550`)
>
> This mapping matches `ops/gorgoroth/docker-compose-3nodes.yml` and the per-node configs under `ops/gorgoroth/conf/node*/gorgoroth.conf`.

```bash
# Query node1 peer count
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "method": "net_peerCount",
    "params": [],
    "id": 1
  }' | jq

# Expected: "result": "0x2" (2 peers)
```

**Expected results**:
- ✅ Each node reports 2 peers connected
- ✅ All handshakes successful
- ✅ Protocol versions match (eth/68, snap/1)

### ✅ Phase 1 Complete
- All 3 nodes are running
- Peer connections established
- Network formed successfully

> ℹ️ **Mining model (updated):** the default 3-node topology uses a single miner.
> - Node1 mines (`mining-enabled=true`)
> - Node2/Node3 are followers (`mining-enabled=false`)
>
> This keeps the chain deterministic and makes “follower accepts miner headers” issues easier to detect.

---

## Phase 2: Mining Validation

### Step 2.1: Check Initial Block Number

```bash
# Query block number on all nodes (HTTP ports 8546/8548/8550)
for port in 8546 8548 8550; do
  echo "Node at port $port:"
  curl -s -X POST http://localhost:$port \
    -H "Content-Type: application/json" \
    -d '{
      "jsonrpc": "2.0",
      "method": "eth_blockNumber",
      "params": [],
      "id": 1
    }' | jq -r '.result'
done
```

**Expected**: All nodes should show "0x0" or "0x1" initially

### Step 2.2: Wait for Mining (60 seconds)

```bash
echo "Waiting for blocks to be mined..."
sleep 60
```

### Step 2.3: Verify Block Production

```bash
# Check block numbers again
for port in 8546 8548 8550; do
  echo "Node at port $port:"
  curl -s -X POST http://localhost:$port \
    -H "Content-Type: application/json" \
    -d '{
      "jsonrpc": "2.0",
      "method": "eth_blockNumber",
      "params": [],
      "id": 1
    }' | jq -r '.result'
done
```

**Expected**: Block numbers should be greater than 0 (e.g., 0x5, 0x10)

### Step 2.4: Run Automated Mining Test

```bash
cd ops/gorgoroth/test-scripts
./test-mining.sh
cd -
```

**Expected results**:
- ✅ All nodes mining
- ✅ Block numbers increasing
- ✅ Mining difficulty adjusting
- ✅ Valid blocks produced

### Step 2.5: Inspect a Mined Block

```bash
# Get latest block from node1 (HTTP port 8546)
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "method": "eth_getBlockByNumber",
    "params": ["latest", false],
    "id": 1
  }' | jq '.result'
```

**Verify**:
- Block has valid hash
- Block has valid difficulty
- Block has valid timestamp
- Miner address is set

### ✅ Phase 2 Complete
- All nodes are mining
- Blocks are being produced
- Mining consensus working

---

## Phase 3: Block Propagation

### Step 3.1: Record Current Block Numbers

```bash
# Save current state
echo "Recording block numbers..."
for port in 8546 8548 8550; do
  echo -n "Node $port: "
  curl -s -X POST http://localhost:$port \
    -H "Content-Type: application/json" \
    -d '{
      "jsonrpc": "2.0",
      "method": "eth_blockNumber",
      "params": [],
      "id": 1
    }' | jq -r '.result'
done > /tmp/blocks_before.txt

cat /tmp/blocks_before.txt
```

### Step 3.2: Wait for More Blocks (120 seconds)

```bash
echo "Waiting for block propagation..."
sleep 120
```

### Step 3.3: Check Block Synchronization

```bash
# Compare block numbers
echo "Block numbers after waiting:"
for port in 8546 8548 8550; do
  echo -n "Node $port: "
  curl -s -X POST http://localhost:$port \
    -H "Content-Type: application/json" \
    -d '{
      "jsonrpc": "2.0",
      "method": "eth_blockNumber",
      "params": [],
      "id": 1
    }' | jq -r '.result'
done > /tmp/blocks_after.txt

cat /tmp/blocks_after.txt
```

**Expected**: All nodes should have similar block numbers (difference < 2 blocks)

### Step 3.4: Run Automated Propagation Test

```bash
cd test-scripts
./test-block-propagation.sh
cd ..
```

**Expected results**:
- ✅ Blocks propagate to all nodes
- ✅ Block numbers converge
- ✅ Block hashes match across nodes
- ✅ No fork detected

### Step 3.5: Verify Block Hash Consistency

```bash
# Get latest block hash from each node
echo "Block hashes:"
for port in 8546 8548 8550; do
  echo -n "Node $port: "
  curl -s -X POST http://localhost:$port \
    -H "Content-Type: application/json" \
    -d '{
      "jsonrpc": "2.0",
      "method": "eth_getBlockByNumber",
      "params": ["latest", false],
      "id": 1
    }' | jq -r '.result.hash'
done
```

**Expected**: All nodes should report the same (or very recent) block hash

### ✅ Phase 3 Complete
- Blocks propagate successfully
- All nodes stay synchronized
- No consensus issues

---

## Phase 4: Synchronization

### Step 4.1: Add a New Node to Test Sync

```bash
# Stop entire network
fukuii-cli stop 3nodes

# Navigate to Gorgoroth directory
cd /path/to/fukuii/ops/gorgoroth

# Remove only node3 data
docker volume rm gorgoroth_fukuii-node3-data || true

# Restart network
cd /path/to/fukuii
fukuii-cli start 3nodes

# Re-sync peers
sleep 30
fukuii-cli sync-static-nodes
```

### Step 4.2: Wait for Sync to Start

```bash
echo "Waiting for sync to initialize..."
sleep 30
```

### Step 4.3: Monitor Sync Progress

```bash
# Check all logs to see node3 syncing
fukuii-cli logs 3nodes

# Press Ctrl+C after observing sync messages
# Look for sync messages from node3:
# - "Starting blockchain sync"
# - "Downloading blocks"
# - "Imported new chain segment"
```

### Step 4.4: Verify Sync Completion

```bash
# Wait for sync (may take 2-5 minutes)
echo "Waiting for sync to complete..."
sleep 180

# Check block number (node3 HTTP port 8550)
curl -X POST http://localhost:8550 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "method": "eth_blockNumber",
    "params": [],
    "id": 1
  }' | jq -r '.result'

# Compare with other nodes (node1 HTTP port 8546)
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "method": "eth_blockNumber",
    "params": [],
    "id": 1
  }' | jq -r '.result'
```

**Expected**: Node3 should catch up to the same block number as other nodes

### Step 4.5: Verify State Consistency

```bash
# Query genesis block from all nodes
for port in 8546 8548 8550; do
  echo "Node $port genesis:"
  curl -s -X POST http://localhost:$port \
    -H "Content-Type: application/json" \
    -d '{
      "jsonrpc": "2.0",
      "method": "eth_getBlockByNumber",
      "params": ["0x0", false],
      "id": 1
    }' | jq -r '.result.hash'
done
```

**Expected**: All nodes should have the same genesis block hash

### ✅ Phase 4 Complete
- New node synced successfully
- Block data consistent across nodes
- State verified

---

## Phase 5: Results Collection

### Step 5.1: Collect Logs Using CLI

```bash
# Use fukuii-cli to collect logs from all nodes
fukuii-cli collect-logs 3nodes /tmp/gorgoroth-3node-results
```

**This will collect**:
- All container logs
- Individual node logs
- Organized in timestamped directory

### Step 5.4: Collect Metrics

```bash
# Final state
cat > /tmp/gorgoroth-3node-results/final-state.txt <<EOF
=== Gorgoroth 3-Node Validation Results ===
Date: $(date)
Duration: 1-2 hours

Node 1 Block: $(curl -s -X POST http://localhost:8546 -H "Content-Type: application/json" -d '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}' | jq -r '.result')
Node 2 Block: $(curl -s -X POST http://localhost:8548 -H "Content-Type: application/json" -d '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}' | jq -r '.result')
Node 3 Block: $(curl -s -X POST http://localhost:8550 -H "Content-Type: application/json" -d '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}' | jq -r '.result')

Node 1 Peers: $(curl -s -X POST http://localhost:8546 -H "Content-Type: application/json" -d '{"jsonrpc":"2.0","method":"net_peerCount","params":[],"id":1}' | jq -r '.result')
Node 2 Peers: $(curl -s -X POST http://localhost:8548 -H "Content-Type: application/json" -d '{"jsonrpc":"2.0","method":"net_peerCount","params":[],"id":1}' | jq -r '.result')
Node 3 Peers: $(curl -s -X POST http://localhost:8550 -H "Content-Type: application/json" -d '{"jsonrpc":"2.0","method":"net_peerCount","params":[],"id":1}' | jq -r '.result')

All tests completed successfully!
EOF

cat /tmp/gorgoroth-3node-results/final-state.txt
```

### ✅ Phase 5 Complete
- Test results collected
- Logs saved for review
- Metrics documented

---

## Cleanup

### Stop the Network

```bash
# Stop all containers
fukuii-cli stop 3nodes

# Remove volumes (optional, to start fresh next time)
fukuii-cli clean 3nodes
```

### Remove Results (Optional)

```bash
# Keep or remove results directory
# rm -rf /tmp/gorgoroth-3node-results
```

---

## Troubleshooting

### Nodes Won't Connect

**Symptoms**: Peer count is 0

**Solution**:
```bash
# Re-sync static nodes to establish peer connections
fukuii-cli sync-static-nodes

# Check status after sync
fukuii-cli status 3nodes

# Restart network if needed
fukuii-cli restart 3nodes
```

### No Blocks Being Mined

**Symptoms**: Block number stays at 0

**Solution**:
```bash
# Check logs for mining activity
fukuii-cli logs 3nodes | grep -i mining

# Verify difficulty
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"eth_getBlockByNumber","params":["latest",false],"id":1}' | jq '.result.difficulty'

# Restart if needed
fukuii-cli restart 3nodes
```

### Nodes Out of Sync

**Symptoms**: Block numbers differ by > 5 blocks

**Solution**:
```bash
# Check logs for errors
fukuii-cli logs 3nodes | grep -i error

# Restart entire network
fukuii-cli restart 3nodes

# Re-sync peers
sleep 30
fukuii-cli sync-static-nodes

# Allow time to catch up
sleep 120
```

### Docker Issues

**Symptoms**: Containers crash or fail to start

**Solution**:
```bash
# Check Docker resources
docker info

# Clean up
docker system prune -f

# Restart Docker daemon
# (varies by OS)

# Try again
docker compose -f docker-compose-3nodes.yml up -d
```

---

## Next Steps

After completing this walkthrough:

1. **Report Results**: Create a GitHub issue with your validation results
2. **Try 6-Node**: Move to [6-Node Walkthrough](GORGOROTH_6NODE_WALKTHROUGH.md)
3. **Real-World Testing**: Try [Cirith Ungol](CIRITH_UNGOL_WALKTHROUGH.md) with mainnet

---

## Related Documentation

- [Gorgoroth Status Tracker](GORGOROTH_STATUS.md)
- [Gorgoroth Validation Status](GORGOROTH_VALIDATION_STATUS.md)
- [Compatibility Testing Guide](../testing/GORGOROTH_COMPATIBILITY_TESTING.md)
- [Cirith Ungol Testing](../testing/CIRITH_UNGOL_TESTING_GUIDE.md)

---

**Questions?** Create an issue on GitHub or consult the troubleshooting section above.
