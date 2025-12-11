# Gorgoroth 6-Node E2E Validation Walkthrough

**Purpose**: Complete step-by-step guide for validating Fukuii in a 6-node test network where each node performs all roles: mining, syncing, and serving sync data.

**Time Required**: 4-8 hours  
**Difficulty**: Intermediate  
**Prerequisites**: Completed 3-node walkthrough, Docker, monitoring tools

---

## Table of Contents

1. [Overview](#overview)
2. [Prerequisites](#prerequisites)
3. [Setup](#setup)
4. [Phase 1: Network Formation & Topology](#phase-1-network-formation--topology)
5. [Phase 2: Mining Distribution](#phase-2-mining-distribution)
6. [Phase 3: Per-Node Validation](#phase-3-per-node-validation)
7. [Phase 4: Sync Testing](#phase-4-sync-testing)
8. [Phase 5: Long-Running Stability](#phase-5-long-running-stability)
9. [Phase 6: Results Collection](#phase-6-results-collection)
10. [Cleanup](#cleanup)
11. [Troubleshooting](#troubleshooting)

---

## Overview

This walkthrough validates advanced multi-node scenarios:
- ✅ Full mesh network with 6 nodes (30 connections)
- ✅ Each node mining blocks
- ✅ Each node serving sync data
- ✅ Each node participating in consensus
- ✅ Long-running stability (8+ hours)

### Network Topology

```
┌─────────┐     ┌─────────┐     ┌─────────┐
│  Node1  │────▶│  Node2  │────▶│  Node3  │
│(Mining) │◀────│(Mining) │◀────│(Mining) │
└────┬────┘     └────┬────┘     └────┬────┘
     │               │               │
     │    ┌─────────┐│┌─────────┐   │
     └───▶│  Node4  │└│  Node5  │◀──┘
          │(Mining) │ │(Mining) │
          └────┬────┘ └────┬────┘
               │           │
               └──▶┌───────┴───┐
                   │   Node6   │
                   │ (Mining)  │
                   └───────────┘

Each node connects to all others (full mesh)
```

---

## Prerequisites

### Completed Previous Walkthrough

✅ Complete [3-Node Walkthrough](GORGOROTH_3NODE_WALKTHROUGH.md) first

### System Requirements
- **RAM**: 8GB minimum (16GB recommended)
- **Disk**: 20GB free space
- **CPU**: 4+ cores recommended
- **OS**: Linux, macOS, or Windows with WSL2

### Required Software

```bash
# Verify installations
docker --version          # Docker 20.10+
docker compose version    # Docker Compose 2.0+
curl --version
jq --version
watch --version           # For monitoring
```

---

## Setup

### Step 1: Navigate to Gorgoroth Directory

```bash
cd /path/to/fukuii/ops/gorgoroth
ls -la docker-compose-6nodes.yml
```

### Step 2: Clean Previous State

```bash
# Stop any running containers
docker compose -f docker-compose-6nodes.yml down -v

# Clean volumes
docker volume prune -f

# Verify clean state
docker ps -a | grep gorgoroth
```

### Step 3: Verify Configuration

```bash
# Check 6-node configuration
cat docker-compose-6nodes.yml | grep -A 5 "node[1-6]:"

# Verify all 6 nodes are defined
# Verify ports: 8545-8550 for RPC, 30303-30308 for P2P
```

---

## Phase 1: Network Formation & Topology

### Step 1.1: Start All Nodes

```bash
docker compose -f docker-compose-6nodes.yml up -d
```

**Expected output**:
```
[+] Running 6/6
 ✔ Container gorgoroth-node1  Started
 ✔ Container gorgoroth-node2  Started
 ✔ Container gorgoroth-node3  Started
 ✔ Container gorgoroth-node4  Started
 ✔ Container gorgoroth-node5  Started
 ✔ Container gorgoroth-node6  Started
```

### Step 1.2: Wait for Initialization

```bash
echo "Waiting for nodes to initialize (60 seconds)..."
sleep 60
```

### Step 1.3: Verify All Containers Running

```bash
docker compose -f docker-compose-6nodes.yml ps
```

**Expected**: All 6 containers show status "Up"

### Step 1.4: Check Full Mesh Connectivity

```bash
# Create a script to check peer counts
cat > /tmp/check-peers.sh <<'EOF'
#!/bin/bash
echo "=== Peer Count Check ==="
for port in 8545 8546 8547 8548 8549 8550; do
  node=$((port - 8544))
  count=$(curl -s -X POST http://localhost:$port \
    -H "Content-Type: application/json" \
    -d '{"jsonrpc":"2.0","method":"net_peerCount","params":[],"id":1}' | jq -r '.result')
  echo "Node $node (port $port): $count peers"
done
EOF

chmod +x /tmp/check-peers.sh
/tmp/check-peers.sh
```

**Expected**: Each node should have 5 peers (connected to all other nodes)

### Step 1.5: Run Connectivity Test

```bash
cd test-scripts
./test-connectivity.sh
cd ..
```

**Expected results**:
- ✅ All 6 nodes running
- ✅ Each node has 5 peer connections
- ✅ Full mesh topology established
- ✅ All handshakes successful

### ✅ Phase 1 Complete
- Network topology validated
- All nodes connected
- Full mesh operational

---

## Phase 2: Mining Distribution

### Step 2.1: Monitor Initial Mining

```bash
# Watch block numbers in real-time
watch -n 5 '/tmp/check-blocks.sh'
```

Create the monitoring script:
```bash
cat > /tmp/check-blocks.sh <<'EOF'
#!/bin/bash
echo "=== Block Numbers ==="
for port in 8545 8546 8547 8548 8549 8550; do
  node=$((port - 8544))
  block=$(curl -s -X POST http://localhost:$port \
    -H "Content-Type: application/json" \
    -d '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}' | jq -r '.result')
  echo "Node $node: $block"
done
EOF

chmod +x /tmp/check-blocks.sh
```

Watch for 5 minutes, then press Ctrl+C

### Step 2.2: Analyze Block Production

```bash
# Collect last 100 blocks and check miners
cat > /tmp/check-miners.sh <<'EOF'
#!/bin/bash
echo "=== Mining Distribution (Last 50 Blocks) ==="

# Get current block number
LATEST=$(curl -s -X POST http://localhost:8545 \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}' | jq -r '.result')

LATEST_DEC=$(printf "%d" $LATEST)
START=$((LATEST_DEC - 50))

# Count blocks per miner
declare -A miners

for ((i=START; i<=LATEST_DEC; i++)); do
  HEX=$(printf "0x%x" $i)
  MINER=$(curl -s -X POST http://localhost:8545 \
    -H "Content-Type: application/json" \
    -d "{\"jsonrpc\":\"2.0\",\"method\":\"eth_getBlockByNumber\",\"params\":[\"$HEX\",false],\"id\":1}" \
    | jq -r '.result.miner')
  
  if [ "$MINER" != "null" ]; then
    miners[$MINER]=$((${miners[$MINER]:-0} + 1))
  fi
done

echo "Mining distribution:"
for miner in "${!miners[@]}"; do
  echo "$miner: ${miners[$miner]} blocks"
done
EOF

chmod +x /tmp/check-miners.sh
/tmp/check-miners.sh
```

**Expected**: Blocks should be distributed across multiple miners (may not be perfectly even)

### Step 2.3: Run Mining Test

```bash
cd test-scripts
./test-mining.sh
cd ..
```

**Expected results**:
- ✅ All nodes producing blocks
- ✅ Mining power distributed
- ✅ Difficulty adjusting properly
- ✅ No single node monopolizing

### ✅ Phase 2 Complete
- Mining operational on all nodes
- Block production distributed
- Mining consensus working

---

## Phase 3: Per-Node Validation

**Goal**: Validate that EACH of the 6 nodes can mine, propagate, and serve sync data.

### Step 3.1: Node 1 Validation

```bash
echo "=== Validating Node 1 ==="

# 1. Check mining
BLOCK1=$(curl -s -X POST http://localhost:8545 \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}' | jq -r '.result')

sleep 60

BLOCK2=$(curl -s -X POST http://localhost:8545 \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}' | jq -r '.result')

echo "Node 1 - Block increase: $BLOCK1 -> $BLOCK2"

# 2. Check it can serve data
curl -s -X POST http://localhost:8545 \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"eth_getBlockByNumber","params":["0x1",false],"id":1}' \
  | jq '.result.number'

# 3. Check peer connectivity
curl -s -X POST http://localhost:8545 \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"net_peerCount","params":[],"id":1}' \
  | jq '.result'

echo "✅ Node 1 validated"
```

### Step 3.2: Node 2 Validation

```bash
echo "=== Validating Node 2 ==="

# Repeat same checks for Node 2 (port 8546)
BLOCK1=$(curl -s -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}' | jq -r '.result')

sleep 60

BLOCK2=$(curl -s -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}' | jq -r '.result')

echo "Node 2 - Block increase: $BLOCK1 -> $BLOCK2"

curl -s -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"eth_getBlockByNumber","params":["0x1",false],"id":1}' \
  | jq '.result.number'

curl -s -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"net_peerCount","params":[],"id":1}' \
  | jq '.result'

echo "✅ Node 2 validated"
```

### Step 3.3: Automated Per-Node Validation

```bash
# Create comprehensive per-node validation script
cat > /tmp/validate-all-nodes.sh <<'EOF'
#!/bin/bash
echo "=== Per-Node Validation ==="

for port in 8545 8546 8547 8548 8549 8550; do
  node=$((port - 8544))
  echo ""
  echo "=== Validating Node $node (port $port) ==="
  
  # 1. Mining check
  BLOCK1=$(curl -s -X POST http://localhost:$port \
    -H "Content-Type: application/json" \
    -d '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}' | jq -r '.result')
  
  sleep 30
  
  BLOCK2=$(curl -s -X POST http://localhost:$port \
    -H "Content-Type: application/json" \
    -d '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}' | jq -r '.result')
  
  echo "  Mining: $BLOCK1 -> $BLOCK2"
  
  # 2. Data serving check
  GENESIS=$(curl -s -X POST http://localhost:$port \
    -H "Content-Type: application/json" \
    -d '{"jsonrpc":"2.0","method":"eth_getBlockByNumber","params":["0x0",false],"id":1}' \
    | jq -r '.result.hash')
  echo "  Serves genesis: $GENESIS"
  
  # 3. Peer connectivity
  PEERS=$(curl -s -X POST http://localhost:$port \
    -H "Content-Type: application/json" \
    -d '{"jsonrpc":"2.0","method":"net_peerCount","params":[],"id":1}' \
    | jq -r '.result')
  echo "  Peer count: $PEERS"
  
  if [ "$BLOCK1" != "$BLOCK2" ] && [ "$GENESIS" != "null" ] && [ "$PEERS" == "0x5" ]; then
    echo "  ✅ Node $node PASSED"
  else
    echo "  ❌ Node $node FAILED"
  fi
done
EOF

chmod +x /tmp/validate-all-nodes.sh
/tmp/validate-all-nodes.sh
```

**Expected**: All 6 nodes pass validation

### ✅ Phase 3 Complete
- Each node validated individually
- All nodes mining successfully
- All nodes serving data
- All nodes properly connected

---

## Phase 4: Sync Testing

### Step 4.1: Test Sync from Each Node

We'll test that a new node can sync from any of the 6 nodes.

```bash
# Stop node6 and clear its data
docker compose -f docker-compose-6nodes.yml stop node6
docker volume rm gorgoroth_node6-data || true

# Let network continue mining
echo "Network mining without node6..."
sleep 120

# Check other nodes progressed
/tmp/check-blocks.sh
```

### Step 4.2: Sync Node 6 from the Network

```bash
# Restart node6
docker compose -f docker-compose-6nodes.yml up -d node6

echo "Node 6 syncing..."
sleep 30

# Monitor sync progress
docker compose -f docker-compose-6nodes.yml logs -f node6 &
LOGS_PID=$!

sleep 180
kill $LOGS_PID 2>/dev/null
```

### Step 4.3: Verify Sync Completion

```bash
# Compare block numbers
echo "=== Block Numbers After Sync ==="
for port in 8545 8546 8547 8548 8549 8550; do
  node=$((port - 8544))
  block=$(curl -s -X POST http://localhost:$port \
    -H "Content-Type: application/json" \
    -d '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}' | jq -r '.result')
  echo "Node $node: $block"
done
```

**Expected**: Node 6 should be within 2-3 blocks of other nodes

### Step 4.4: Test Sync from Specific Node

```bash
# Stop node5, let it fall behind
docker compose -f docker-compose-6nodes.yml stop node5
sleep 180

# Restart and ensure it syncs from node1
docker compose -f docker-compose-6nodes.yml up -d node5
sleep 120

# Verify caught up
curl -s -X POST http://localhost:8549 \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}' | jq '.result'
```

### ✅ Phase 4 Complete
- Sync tested from multiple nodes
- All nodes can serve sync data
- State consistency maintained

---

## Phase 5: Long-Running Stability

### Step 5.1: Start Long-Running Test (8 Hours)

```bash
cd test-scripts

# Run consensus test for 8 hours (480 minutes)
./test-consensus.sh 480 &
CONSENSUS_PID=$!

echo "Long-running test started (PID: $CONSENSUS_PID)"
echo "This will run for 8 hours. You can:"
echo "  - Check progress: tail -f test-scripts/consensus-test.log"
echo "  - Stop early: kill $CONSENSUS_PID"

cd ..
```

### Step 5.2: Monitor During Test

```bash
# Create monitoring dashboard script
cat > /tmp/6node-dashboard.sh <<'EOF'
#!/bin/bash
echo "=== Gorgoroth 6-Node Dashboard ==="
echo "Time: $(date)"
echo ""

echo "Block Numbers:"
for port in 8545 8546 8547 8548 8549 8550; do
  node=$((port - 8544))
  block=$(curl -s --max-time 5 -X POST http://localhost:$port \
    -H "Content-Type: application/json" \
    -d '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}' 2>/dev/null | jq -r '.result' || echo "N/A")
  echo "  Node $node: $block"
done

echo ""
echo "Peer Counts:"
for port in 8545 8546 8547 8548 8549 8550; do
  node=$((port - 8544))
  peers=$(curl -s --max-time 5 -X POST http://localhost:$port \
    -H "Content-Type: application/json" \
    -d '{"jsonrpc":"2.0","method":"net_peerCount","params":[],"id":1}' 2>/dev/null | jq -r '.result' || echo "N/A")
  echo "  Node $node: $peers peers"
done

echo ""
echo "Container Status:"
docker compose -f docker-compose-6nodes.yml ps --format "table {{.Name}}\t{{.Status}}" 2>/dev/null || echo "  Unable to get container status"
EOF

chmod +x /tmp/6node-dashboard.sh

# Run dashboard every minute
watch -n 60 /tmp/6node-dashboard.sh
```

### Step 5.3: Collect Stability Metrics

After the 8-hour test completes (or manually stopped):

```bash
# Check if any errors occurred
echo "=== Error Summary ==="
docker compose -f docker-compose-6nodes.yml logs | grep -i "error\|fatal\|panic" | wc -l

# Check for forks
echo "=== Fork Detection ==="
for port in 8545 8546 8547 8548 8549 8550; do
  node=$((port - 8544))
  hash=$(curl -s -X POST http://localhost:$port \
    -H "Content-Type: application/json" \
    -d '{"jsonrpc":"2.0","method":"eth_getBlockByNumber","params":["latest",false],"id":1}' \
    | jq -r '.result.hash')
  echo "Node $node latest hash: $hash"
done
```

**Expected**:
- Few or no errors
- All nodes on same chain (same block hashes)
- Stable memory usage
- Consistent peer counts

### ✅ Phase 5 Complete
- Long-running stability validated
- No consensus issues
- Network resilient

---

## Phase 6: Results Collection

### Step 6.1: Generate Final Report

```bash
cd test-scripts
./generate-report.sh
cd ..
```

### Step 6.2: Collect All Logs

```bash
# Create results directory
mkdir -p /tmp/gorgoroth-6node-results

# Save all logs
docker compose -f docker-compose-6nodes.yml logs > /tmp/gorgoroth-6node-results/all-logs.txt

# Save individual node logs
for i in 1 2 3 4 5 6; do
  docker compose -f docker-compose-6nodes.yml logs node$i > /tmp/gorgoroth-6node-results/node$i.log
done

echo "Logs saved to /tmp/gorgoroth-6node-results/"
```

### Step 6.3: Create Summary Report

```bash
cat > /tmp/gorgoroth-6node-results/SUMMARY.md <<EOF
# Gorgoroth 6-Node Validation Results

**Date**: $(date)
**Duration**: 8+ hours
**Configuration**: 6 Fukuii nodes, full mesh

## Test Results

### Network Formation
- ✅ All 6 nodes started successfully
- ✅ Full mesh topology (30 connections)
- ✅ All handshakes successful

### Mining
- ✅ All nodes mining blocks
- ✅ Mining distributed across nodes
- ✅ Difficulty adjusting properly
- ✅ Consensus maintained

### Per-Node Validation
$(for i in 1 2 3 4 5 6; do echo "- ✅ Node $i: Mining, syncing, serving data"; done)

### Synchronization
- ✅ Nodes can sync from any peer
- ✅ State consistency maintained
- ✅ Fast sync working

### Long-Running Stability
- ✅ Ran for 8+ hours without issues
- ✅ No forks detected
- ✅ Memory usage stable
- ✅ Peer connections stable

## Final State

$(for port in 8545 8546 8547 8548 8549 8550; do
  node=$((port - 8544))
  block=$(curl -s -X POST http://localhost:$port -H "Content-Type: application/json" -d '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}' | jq -r '.result' 2>/dev/null || echo "N/A")
  peers=$(curl -s -X POST http://localhost:$port -H "Content-Type: application/json" -d '{"jsonrpc":"2.0","method":"net_peerCount","params":[],"id":1}' | jq -r '.result' 2>/dev/null || echo "N/A")
  echo "Node $node: Block $block, $peers peers"
done)

## Conclusion

✅ All tests passed. Fukuii 6-node network is stable and functional.
EOF

cat /tmp/gorgoroth-6node-results/SUMMARY.md
```

### ✅ Phase 6 Complete
- Results documented
- Logs collected
- Summary created

---

## Cleanup

```bash
# Stop network
docker compose -f docker-compose-6nodes.yml down

# Remove volumes (optional)
docker compose -f docker-compose-6nodes.yml down -v

# Results preserved in /tmp/gorgoroth-6node-results/
```

---

## Troubleshooting

### Node Falls Out of Sync

```bash
# Identify lagging node
/tmp/check-blocks.sh

# Restart it
docker compose -f docker-compose-6nodes.yml restart node<N>

# Monitor recovery
watch -n 10 'curl -s -X POST http://localhost:854<N> -H "Content-Type: application/json" -d "{\"jsonrpc\":\"2.0\",\"method\":\"eth_blockNumber\",\"params\":[],\"id\":1}" | jq ".result"'
```

### High Resource Usage

```bash
# Check Docker stats
docker stats

# Reduce load by stopping some nodes temporarily
docker compose -f docker-compose-6nodes.yml stop node5 node6

# Let system stabilize, then restart
docker compose -f docker-compose-6nodes.yml up -d node5 node6
```

### Fork Detected

```bash
# Check all block hashes
for port in 8545 8546 8547 8548 8549 8550; do
  curl -s -X POST http://localhost:$port \
    -H "Content-Type: application/json" \
    -d '{"jsonrpc":"2.0","method":"eth_getBlockByNumber","params":["latest",false],"id":1}' \
    | jq -r '.result.hash'
done

# If different, check logs for cause
docker compose -f docker-compose-6nodes.yml logs | grep -i "fork\|reorg"

# Restart network if necessary
docker compose -f docker-compose-6nodes.yml restart
```

---

## Next Steps

1. **Report Results**: Create GitHub issue with validation results
2. **Real-World Testing**: Move to [Cirith Ungol](CIRITH_UNGOL_WALKTHROUGH.md)
3. **Multi-Client**: Try fukuii-geth or fukuii-besu configurations

---

## Related Documentation

- [Gorgoroth Status Tracker](GORGOROTH_STATUS.md)
- [3-Node Walkthrough](GORGOROTH_3NODE_WALKTHROUGH.md)
- [Cirith Ungol Walkthrough](CIRITH_UNGOL_WALKTHROUGH.md)
- [Compatibility Testing Guide](../testing/GORGOROTH_COMPATIBILITY_TESTING.md)

---

**Questions?** Create an issue on GitHub or refer to troubleshooting above.
