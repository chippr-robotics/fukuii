# Gorgoroth 3-Node E2E Validation Walkthrough

**Purpose**: Complete step-by-step guide for validating Fukuii in a 3-node test network, covering mining, syncing, and block propagation.

**Time Required**: 1-2 hours  
**Difficulty**: Beginner  
**Prerequisites**: Docker, basic command line knowledge

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

This walkthrough validates the following:
- ✅ Network formation with 3 Fukuii nodes
- ✅ Peer discovery and connectivity
- ✅ Mining functionality
- ✅ Block propagation across nodes
- ✅ Node synchronization

### What You'll Test

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│   Node 1    │────▶│   Node 2    │────▶│   Node 3    │
│  (Miner)    │◀────│  (Miner)    │◀────│  (Miner)    │
└─────────────┘     └─────────────┘     └─────────────┘
       │                   │                   │
       └───────────────────┴───────────────────┘
              All nodes mine and sync
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

### Clone Repository

```bash
# Clone the repository (adjust URL if using a fork)
git clone https://github.com/chippr-robotics/fukuii.git
# Or your fork: git clone https://github.com/YOUR_USERNAME/fukuii.git

cd fukuii
git submodule update --init --recursive
```

---

## Setup

### Step 1: Navigate to Gorgoroth Directory

```bash
cd ops/gorgoroth
ls -la
```

**Expected output**: You should see Docker Compose files and test scripts.

### Step 2: Verify Configuration Files

```bash
# Check genesis file
cat genesis/genesis.json | jq '.config'

# Check 3-node configuration
cat docker-compose-3nodes.yml | head -20
```

### Step 3: Clean Any Previous State

```bash
# Stop any running containers
docker compose -f docker-compose-3nodes.yml down -v

# Remove old data volumes
docker volume prune -f
```

---

## Phase 1: Network Formation

### Step 1.1: Start the Network

```bash
docker compose -f docker-compose-3nodes.yml up -d
```

**Expected output**:
```
[+] Running 3/3
 ✔ Container gorgoroth-node1  Started
 ✔ Container gorgoroth-node2  Started
 ✔ Container gorgoroth-node3  Started
```

### Step 1.2: Wait for Initialization (30 seconds)

```bash
echo "Waiting for nodes to initialize..."
sleep 30
```

### Step 1.3: Check Container Status

```bash
docker compose -f docker-compose-3nodes.yml ps
```

**Expected output**: All containers should show status "Up"

### Step 1.4: Verify Logs

```bash
# Check node1 logs
docker compose -f docker-compose-3nodes.yml logs node1 | tail -20

# Look for successful startup messages:
# - "Starting Fukuii node"
# - "Ethereum node ready"
# - "Mining enabled"
```

### Step 1.5: Check Peer Connectivity

```bash
# Use the automated connectivity test
cd test-scripts
./test-connectivity.sh
cd ..
```

**Expected results**:
- ✅ Each node reports 2 peers connected
- ✅ All handshakes successful
- ✅ Protocol versions match (eth/68, snap/1)

**Manual verification** (optional):
```bash
# Query node1 peer count
curl -X POST http://localhost:8545 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "method": "net_peerCount",
    "params": [],
    "id": 1
  }' | jq

# Expected: "result": "0x2" (2 peers)
```

### ✅ Phase 1 Complete
- All 3 nodes are running
- Peer connections established
- Network formed successfully

---

## Phase 2: Mining Validation

### Step 2.1: Check Initial Block Number

```bash
# Query block number on all nodes
for port in 8545 8546 8547; do
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
for port in 8545 8546 8547; do
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
cd test-scripts
./test-mining.sh
cd ..
```

**Expected results**:
- ✅ All nodes mining
- ✅ Block numbers increasing
- ✅ Mining difficulty adjusting
- ✅ Valid blocks produced

### Step 2.5: Inspect a Mined Block

```bash
# Get latest block from node1
curl -X POST http://localhost:8545 \
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
for port in 8545 8546 8547; do
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
for port in 8545 8546 8547; do
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
for port in 8545 8546 8547; do
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
# Stop node3 to simulate a new node joining
docker compose -f docker-compose-3nodes.yml stop node3

# Remove its data
docker volume rm gorgoroth_node3-data || true

# Restart node3
docker compose -f docker-compose-3nodes.yml up -d node3
```

### Step 4.2: Wait for Sync to Start

```bash
echo "Waiting for sync to initialize..."
sleep 30
```

### Step 4.3: Monitor Sync Progress

```bash
# Check node3 sync status
docker compose -f docker-compose-3nodes.yml logs node3 | tail -50

# Look for sync messages:
# - "Starting blockchain sync"
# - "Downloading blocks"
# - "Imported new chain segment"
```

### Step 4.4: Verify Sync Completion

```bash
# Wait for sync (may take 2-5 minutes)
echo "Waiting for sync to complete..."
sleep 180

# Check block number
curl -X POST http://localhost:8547 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "method": "eth_blockNumber",
    "params": [],
    "id": 1
  }' | jq -r '.result'

# Compare with other nodes
curl -X POST http://localhost:8545 \
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
for port in 8545 8546 8547; do
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

### Step 5.1: Run Complete Test Suite

```bash
cd test-scripts
./run-test-suite.sh 3nodes
cd ..
```

**This will run**:
1. Connectivity tests
2. Block propagation tests
3. Mining tests
4. Consensus validation

### Step 5.2: Generate Report

```bash
cd test-scripts
./generate-report.sh
cd ..
```

### Step 5.3: Collect Logs

```bash
# Create results directory
mkdir -p /tmp/gorgoroth-3node-results

# Save logs
docker compose -f docker-compose-3nodes.yml logs > /tmp/gorgoroth-3node-results/all-logs.txt

# Save individual node logs
docker compose -f docker-compose-3nodes.yml logs node1 > /tmp/gorgoroth-3node-results/node1.log
docker compose -f docker-compose-3nodes.yml logs node2 > /tmp/gorgoroth-3node-results/node2.log
docker compose -f docker-compose-3nodes.yml logs node3 > /tmp/gorgoroth-3node-results/node3.log

echo "Logs saved to /tmp/gorgoroth-3node-results/"
```

### Step 5.4: Collect Metrics

```bash
# Final state
cat > /tmp/gorgoroth-3node-results/final-state.txt <<EOF
=== Gorgoroth 3-Node Validation Results ===
Date: $(date)
Duration: 1-2 hours

Node 1 Block: $(curl -s -X POST http://localhost:8545 -H "Content-Type: application/json" -d '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}' | jq -r '.result')
Node 2 Block: $(curl -s -X POST http://localhost:8546 -H "Content-Type: application/json" -d '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}' | jq -r '.result')
Node 3 Block: $(curl -s -X POST http://localhost:8547 -H "Content-Type: application/json" -d '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}' | jq -r '.result')

Node 1 Peers: $(curl -s -X POST http://localhost:8545 -H "Content-Type: application/json" -d '{"jsonrpc":"2.0","method":"net_peerCount","params":[],"id":1}' | jq -r '.result')
Node 2 Peers: $(curl -s -X POST http://localhost:8546 -H "Content-Type: application/json" -d '{"jsonrpc":"2.0","method":"net_peerCount","params":[],"id":1}' | jq -r '.result')
Node 3 Peers: $(curl -s -X POST http://localhost:8547 -H "Content-Type: application/json" -d '{"jsonrpc":"2.0","method":"net_peerCount","params":[],"id":1}' | jq -r '.result')

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
docker compose -f docker-compose-3nodes.yml down

# Remove volumes (optional, to start fresh next time)
docker compose -f docker-compose-3nodes.yml down -v
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
# Check network
docker network ls | grep gorgoroth

# Verify static-nodes.json
docker compose -f docker-compose-3nodes.yml exec node1 cat /app/conf/static-nodes.json

# Restart network
docker compose -f docker-compose-3nodes.yml restart
```

### No Blocks Being Mined

**Symptoms**: Block number stays at 0

**Solution**:
```bash
# Check mining is enabled
docker compose -f docker-compose-3nodes.yml logs node1 | grep -i mining

# Verify difficulty
curl -X POST http://localhost:8545 \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"eth_getBlockByNumber","params":["latest",false],"id":1}' | jq '.result.difficulty'

# Restart if needed
docker compose -f docker-compose-3nodes.yml restart
```

### Nodes Out of Sync

**Symptoms**: Block numbers differ by > 5 blocks

**Solution**:
```bash
# Check logs for errors
docker compose -f docker-compose-3nodes.yml logs | grep -i error

# Restart lagging node
docker compose -f docker-compose-3nodes.yml restart node3

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
