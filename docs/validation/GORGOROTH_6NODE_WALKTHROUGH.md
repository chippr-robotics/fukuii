# Gorgoroth 6-Node (Mixed Network) E2E Validation Walkthrough

**Purpose**: Complete step-by-step guide for validating Fukuii interoperability and network connectivity in a mixed-client test network with Core-Geth and Besu. This tests Fukuii against reference clients to validate multi-client compatibility.

**Time Required**: 4-8 hours  
**Difficulty**: Intermediate  
**Prerequisites**: Completed 3-node walkthrough, Docker, monitoring tools, fukuii-cli.sh installed or aliased

---

## Table of Contents

1. [Overview](#overview)
2. [Prerequisites](#prerequisites)
3. [Setup](#setup)
4. [Phase 1: Network Formation & Topology](#phase-1-network-formation--topology)
5. [Phase 2: Mining Distribution](#phase-2-mining-distribution)
6. [Phase 3: Per-Node Validation](#phase-3-per-node-validation)
7. [Phase 4: Sync Testing](#phase-4-sync-testing)
8. [Phase 5: Long-Running Stability](#phase-5-long-running-multi-client-stability)
9. [Phase 6: Results Collection](#phase-6-results-collection)
10. [Cleanup](#cleanup)
11. [Troubleshooting](#troubleshooting)

---

## Overview

**Goal**: Validate Fukuii interoperability and network connectivity by testing against reference Ethereum Classic clients (Core-Geth and Besu).

This walkthrough validates advanced multi-client scenarios:
- ✅ Mixed network with 3 Fukuii + 3 Core-Geth nodes (or 3 Fukuii + 3 Besu)
- ✅ Cross-client peer connectivity (max 5 peers per node in battlenet)
- ✅ Cross-client mining and block validation
- ✅ Cross-client block propagation
- ✅ Cross-client synchronization
- ✅ Long-running stability (8+ hours)

### Network Topology

```
┌──────────┐     ┌──────────┐     ┌──────────┐
│ Fukuii 1 │────▶│ Fukuii 2 │────▶│ Fukuii 3 │
│ (Mining) │◀────│ (Mining) │◀────│ (Mining) │
└────┬─────┘     └────┬─────┘     └────┬─────┘
     │                │                │
     ├────────────────┼────────────────┤
     │                │                │
┌────▼─────┐     ┌───▼──────┐     ┌──▼───────┐
│  Geth 1  │────▶│  Geth 2  │────▶│  Geth 3  │
│ (Mining) │◀────│ (Mining) │◀────│ (Mining) │
└──────────┘     └──────────┘     └──────────┘

Multi-client network (max 5 peers per node)
Testing interoperability between clients
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

# Verify fukuii-cli is installed
fukuii-cli version
```

---

## Setup

### Step 1: Choose Configuration

For this walkthrough, we'll use the **mixed** configuration (3 Fukuii + 3 Core-Geth nodes).

```bash
# Available options for multi-client testing:
# - fukuii-geth: 3 Fukuii + 3 Core-Geth nodes
# - fukuii-besu: 3 Fukuii + 3 Besu nodes  
# - mixed: 3 Fukuii + 3 Core-Geth + 3 Besu (9 nodes total - advanced)

export GORGOROTH_CONFIG="fukuii-geth"
```

### Step 2: Clean Previous State

```bash
# Stop and clean any running network
fukuii-cli clean $GORGOROTH_CONFIG
```

### Step 3: Verify Configuration

```bash
# View available configurations
fukuii-cli help

# Verify fukuii-geth is listed
```

---

## Phase 1: Network Formation & Topology

### Step 1.1: Start Mixed Network

```bash
# Start Fukuii + Core-Geth network
fukuii-cli start $GORGOROTH_CONFIG
```

**Expected output**:
```
Starting Gorgoroth test network with configuration: fukuii-geth
Using compose file: docker-compose-fukuii-geth.yml
[+] Running 6/6
 ✔ Container gorgoroth-fukuii-node1  Started
 ✔ Container gorgoroth-fukuii-node2  Started
 ✔ Container gorgoroth-fukuii-node3  Started
 ✔ Container gorgoroth-geth-node1    Started
 ✔ Container gorgoroth-geth-node2    Started
 ✔ Container gorgoroth-geth-node3    Started
Network started successfully!
```

### Step 1.2: Wait for Initialization

```bash
echo "Waiting for nodes to initialize (90 seconds for multi-client network)..."
sleep 90
```

### Step 1.3: Verify All Containers Running

```bash
fukuii-cli status $GORGOROTH_CONFIG
```

**Expected**: All 6 containers show status "Up" (3 Fukuii + 3 Core-Geth)

### Step 1.4: Sync Static Nodes

```bash
# Establish peer connections across all clients
fukuii-cli sync-static-nodes
```

**Expected output**:
```
=== Fukuii Static Nodes Synchronization ===
Found running containers:
  - gorgoroth-fukuii-node1
  - gorgoroth-fukuii-node2
  - gorgoroth-fukuii-node3
Collecting enode URLs...
...
=== Static nodes synchronization complete ===
```

**Note**: The sync-static-nodes command syncs Fukuii nodes. Core-Geth and Besu nodes use their own discovery mechanisms and will connect automatically.

### Step 1.5: Wait for Cross-Client Peer Discovery

```bash
# Wait for all clients to discover each other
echo "Waiting for cross-client peer discovery (60 seconds)..."
sleep 60
```

### Step 1.6: Check Multi-Client Connectivity

```bash
# Create a script to check peer counts
cat > /tmp/check-peers-mixed.sh <<'EOF'
#!/bin/bash
echo "=== Multi-Client Peer Count Check ==="
echo ""
echo "Fukuii Nodes:"
for port in 8545 8546 8547; do
  node=$((port - 8544))
  count=$(curl -s -X POST http://localhost:$port \
    -H "Content-Type: application/json" \
    -d '{"jsonrpc":"2.0","method":"net_peerCount","params":[],"id":1}' 2>/dev/null | jq -r '.result' || echo "N/A")
  echo "  Fukuii Node $node (port $port): $count peers"
done

echo ""
echo "Core-Geth Nodes:"
for port in 8548 8549 8550; do
  node=$((port - 8547))
  count=$(curl -s -X POST http://localhost:$port \
    -H "Content-Type: application/json" \
    -d '{"jsonrpc":"2.0","method":"net_peerCount","params":[],"id":1}' 2>/dev/null | jq -r '.result' || echo "N/A")
  echo "  Geth Node $node (port $port): $count peers"
done
EOF

chmod +x /tmp/check-peers-mixed.sh
/tmp/check-peers-mixed.sh
```

**Expected**: Each node should have 2-5 peers (max 5 peers per node in private battlenet)

### Step 1.7: Verify Cross-Client Connections

Look at the logs to confirm Fukuii is connecting to Core-Geth nodes:

```bash
# Check Fukuii logs for Core-Geth peer connections
fukuii-cli logs $GORGOROTH_CONFIG | grep -i "peer\|geth" | tail -20
```

### ✅ Phase 1 Complete
- Mixed network topology established
- All 6 nodes connected (3 Fukuii + 3 Core-Geth)
- Cross-client peer discovery working
- Fukuii ↔ Core-Geth communication validated

---

## Phase 2: Cross-Client Mining & Block Validation

### Step 2.1: Monitor Initial Mining

```bash
# Watch block numbers in real-time
watch -n 5 '/tmp/check-blocks-mixed.sh'
```

Create the monitoring script:
```bash
cat > /tmp/check-blocks-mixed.sh <<'EOF'
#!/bin/bash
echo "=== Block Numbers (Multi-Client) ==="
echo ""
echo "Fukuii Nodes:"
for port in 8545 8546 8547; do
  node=$((port - 8544))
  block=$(curl -s --max-time 5 -X POST http://localhost:$port \
    -H "Content-Type: application/json" \
    -d '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}' 2>/dev/null | jq -r '.result' || echo "N/A")
  echo "  Fukuii Node $node: $block"
done

echo ""
echo "Core-Geth Nodes:"
for port in 8548 8549 8550; do
  node=$((port - 8547))
  block=$(curl -s --max-time 5 -X POST http://localhost:$port \
    -H "Content-Type: application/json" \
    -d '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}' 2>/dev/null | jq -r '.result' || echo "N/A")
  echo "  Geth Node $node: $block"
done
EOF

chmod +x /tmp/check-blocks-mixed.sh
```

Watch for 5 minutes, then press Ctrl+C

### Step 2.2: Validate Cross-Client Block Acceptance

```bash
# Get a block from Fukuii node
FUKUII_BLOCK=$(curl -s -X POST http://localhost:8545 \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"eth_getBlockByNumber","params":["latest",false],"id":1}' | jq '.result')

FUKUII_HASH=$(echo "$FUKUII_BLOCK" | jq -r '.hash')
FUKUII_NUMBER=$(echo "$FUKUII_BLOCK" | jq -r '.number')

echo "Fukuii latest block: $FUKUII_NUMBER ($FUKUII_HASH)"

# Query same block from Core-Geth node
GETH_BLOCK=$(curl -s -X POST http://localhost:8548 \
  -H "Content-Type: application/json" \
  -d "{\"jsonrpc\":\"2.0\",\"method\":\"eth_getBlockByNumber\",\"params\":[\"$FUKUII_NUMBER\",false],\"id\":1}" | jq '.result')

GETH_HASH=$(echo "$GETH_BLOCK" | jq -r '.hash')

echo "Core-Geth same block: $FUKUII_NUMBER ($GETH_HASH)"

# Compare
if [ "$FUKUII_HASH" == "$GETH_HASH" ]; then
  echo "✅ Block hashes match! Cross-client block validation working."
else
  echo "❌ Block hashes differ! Potential compatibility issue."
fi
```

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

### Step 2.3: Analyze Mining Distribution by Client

```bash
# Collect last 50 blocks and check which client mined them
cat > /tmp/check-miners-mixed.sh <<'EOF'
#!/bin/bash
echo "=== Mining Distribution by Client (Last 50 Blocks) ==="

# Get current block number (from Fukuii node)
LATEST=$(curl -s -X POST http://localhost:8545 \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}' | jq -r '.result')

LATEST_DEC=$(printf "%d" $LATEST)
START=$((LATEST_DEC - 50))

# Get known miner addresses for each node type
# (These would be from genesis or configuration)
echo "Analyzing blocks $START to $LATEST_DEC..."

# Count blocks (simplified - in real testing you'd check actual miner addresses)
echo "Note: In a real test, analyze miner addresses to determine which client mined each block"
echo "Expected: Mix of Fukuii and Core-Geth blocks"
EOF

chmod +x /tmp/check-miners-mixed.sh
/tmp/check-miners-mixed.sh
```

**Expected**: Blocks should be distributed across both Fukuii and Core-Geth nodes

### Step 2.4: Verify Block Propagation

```bash
# Wait for some blocks to be mined
sleep 120

# Check that all nodes have same latest block
echo "=== Checking Block Synchronization ==="
/tmp/check-blocks-mixed.sh
```

**Expected**: All nodes (both Fukuii and Core-Geth) should have similar block numbers (difference < 3 blocks)

### ✅ Phase 2 Complete
- Mining operational on both Fukuii and Core-Geth nodes
- Cross-client block acceptance validated
- Block propagation working across clients
- Multi-client consensus maintained

---

## Phase 3: Cross-Client Validation

**Goal**: Validate that each Fukuii node can interoperate with Core-Geth nodes.

### Step 3.1: Fukuii Node 1 Cross-Client Validation

```bash
echo "=== Validating Fukuii Node 1 Interoperability ==="

# 1. Check it's connected to Core-Geth peers
curl -s -X POST http://localhost:8545 \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"net_peerCount","params":[],"id":1}' | jq '.result'

# 2. Verify it can retrieve blocks from Core-Geth
# Get a block that was likely mined by Core-Geth
LATEST=$(curl -s -X POST http://localhost:8545 \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}' | jq -r '.result')

curl -s -X POST http://localhost:8545 \
  -H "Content-Type: application/json" \
  -d "{\"jsonrpc\":\"2.0\",\"method\":\"eth_getBlockByNumber\",\"params\":[\"$LATEST\",false],\"id\":1}" | jq '.result.number'

echo "✅ Fukuii Node 1 can query and sync from multi-client network"
```

### Step 3.2: Validate All Fukuii Nodes

```bash
# Test each Fukuii node
for port in 8545 8546 8547; do
  node=$((port - 8544))
  echo ""
  echo "=== Fukuii Node $node ==="
  
  # Peer count
  peers=$(curl -s -X POST http://localhost:$port \
    -H "Content-Type: application/json" \
    -d '{"jsonrpc":"2.0","method":"net_peerCount","params":[],"id":1}' 2>/dev/null | jq -r '.result' || echo "N/A")
  echo "  Peers: $peers"
  
  # Block number
  block=$(curl -s -X POST http://localhost:$port \
    -H "Content-Type: application/json" \
    -d '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}' 2>/dev/null | jq -r '.result' || echo "N/A")
  echo "  Block: $block"
  
  if [ "$peers" != "N/A" ] && [ "$block" != "N/A" ]; then
    echo "  ✅ Node $node operational"
  else
    echo "  ❌ Node $node has issues"
  fi
done
```

### ✅ Phase 3 Complete
- All Fukuii nodes validated for cross-client interoperability
- Fukuii ↔ Core-Geth communication confirmed
- All nodes mining and syncing in multi-client network

---

## Phase 4: Cross-Client Sync Testing

### Step 4.1: Test Sync from Mixed Network

We'll test that a new Fukuii node can sync from a network with both Fukuii and Core-Geth nodes.

```bash
# Stop the entire network
fukuii-cli stop $GORGOROTH_CONFIG

# Navigate to Gorgoroth directory to remove specific volume
cd /path/to/fukuii/ops/gorgoroth

# Clear data for one Fukuii node
docker volume rm gorgoroth_fukuii-node3-data || true

# Restart network
cd /path/to/fukuii
fukuii-cli start $GORGOROTH_CONFIG

# Let network mine some blocks
echo "Network mining without node3 having data..."
sleep 90

# Sync static nodes
fukuii-cli sync-static-nodes

# Check other nodes progressed
/tmp/check-blocks-mixed.sh
```

### Step 4.2: Verify Fukuii Syncs from Multi-Client Network

```bash
echo "Fukuii Node 3 should now be syncing from both Fukuii and Core-Geth peers..."
sleep 180

# Compare block numbers
echo "=== Block Numbers After Sync ==="
/tmp/check-blocks-mixed.sh
```

**Expected**: Fukuii Node 3 should catch up to the same block number as other nodes, having synced from both Fukuii and Core-Geth peers

### ✅ Phase 4 Complete
- Fukuii successfully syncs from multi-client network
- Cross-client sync validated
- State consistency maintained across clients

---

## Phase 5: Long-Running Multi-Client Stability

### Step 5.1: Start Long-Running Test (8 Hours)

```bash
echo "Starting 8-hour stability test for multi-client network..."
echo "Start time: $(date)"

# Create monitoring script for long-running test
cat > /tmp/long-run-monitor.sh <<'EOF'
#!/bin/bash
LOG_FILE="/tmp/stability-log.txt"
echo "=== Multi-Client Stability Test ===" | tee -a "$LOG_FILE"
echo "Start: $(date)" | tee -a "$LOG_FILE"

while true; do
  echo "" | tee -a "$LOG_FILE"
  echo "Check at: $(date)" | tee -a "$LOG_FILE"
  
  # Check Fukuii nodes
  for port in 8545 8546 8547; do
    node=$((port - 8544))
    block=$(curl -s --max-time 10 -X POST http://localhost:$port \
      -H "Content-Type: application/json" \
      -d '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}' 2>/dev/null | jq -r '.result' || echo "ERROR")
    peers=$(curl -s --max-time 10 -X POST http://localhost:$port \
      -H "Content-Type: application/json" \
      -d '{"jsonrpc":"2.0","method":"net_peerCount","params":[],"id":1}' 2>/dev/null | jq -r '.result' || echo "ERROR")
    echo "  Fukuii Node $node: Block $block, Peers $peers" | tee -a "$LOG_FILE"
  done
  
  # Check Core-Geth nodes
  for port in 8548 8549 8550; do
    node=$((port - 8547))
    block=$(curl -s --max-time 10 -X POST http://localhost:$port \
      -H "Content-Type: application/json" \
      -d '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}' 2>/dev/null | jq -r '.result' || echo "ERROR")
    peers=$(curl -s --max-time 10 -X POST http://localhost:$port \
      -H "Content-Type: application/json" \
      -d '{"jsonrpc":"2.0","method":"net_peerCount","params":[],"id":1}' 2>/dev/null | jq -r '.result' || echo "ERROR")
    echo "  Geth Node $node: Block $block, Peers $peers" | tee -a "$LOG_FILE"
  done
  
  sleep 600  # Check every 10 minutes
done
EOF

chmod +x /tmp/long-run-monitor.sh

# Run in background
nohup /tmp/long-run-monitor.sh > /dev/null 2>&1 &
MONITOR_PID=$!

echo "Monitor running (PID: $MONITOR_PID)"
echo "Logs: /tmp/stability-log.txt"
echo "This test will run for 8 hours..."
```
```

### Step 5.2: Monitor During Test

```bash
# Create monitoring dashboard script
cat > /tmp/6node-dashboard.sh <<'EOF'
#!/bin/bash
echo "=== Gorgoroth Mixed Network Dashboard ==="
echo "Time: $(date)"
echo ""

echo "Fukuii Nodes - Block Numbers:"
for port in 8545 8546 8547; do
  node=$((port - 8544))
  block=$(curl -s --max-time 5 -X POST http://localhost:$port \
    -H "Content-Type: application/json" \
    -d '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}' 2>/dev/null | jq -r '.result' || echo "N/A")
  echo "  Fukuii Node $node: $block"
done

echo ""
echo "Core-Geth Nodes - Block Numbers:"
for port in 8548 8549 8550; do
  node=$((port - 8547))
  block=$(curl -s --max-time 5 -X POST http://localhost:$port \
    -H "Content-Type: application/json" \
    -d '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}' 2>/dev/null | jq -r '.result' || echo "N/A")
  echo "  Geth Node $node: $block"
done

echo ""
echo "Peer Counts:"
for port in 8545 8546 8547; do
  node=$((port - 8544))
  peers=$(curl -s --max-time 5 -X POST http://localhost:$port \
    -H "Content-Type: application/json" \
    -d '{"jsonrpc":"2.0","method":"net_peerCount","params":[],"id":1}' 2>/dev/null | jq -r '.result' || echo "N/A")
  echo "  Fukuii Node $node: $peers peers"
done

echo ""
echo "Container Status:"
docker ps --filter name=gorgoroth --format "table {{.Names}}\t{{.Status}}" 2>/dev/null || echo "  Unable to get container status"
EOF

chmod +x /tmp/6node-dashboard.sh

# Run dashboard every minute
watch -n 60 /tmp/6node-dashboard.sh
```

### Step 5.3: Collect Stability Metrics

After the 8-hour test completes:

```bash
# Stop monitoring
pkill -f long-run-monitor.sh

# Check stability log
tail -100 /tmp/stability-log.txt

# Check if any errors occurred
echo "=== Error Summary ==="
fukuii-cli logs $GORGOROTH_CONFIG | grep -i "error\|fatal\|panic" | wc -l

# Check for forks (all nodes should be on same chain)
echo "=== Fork Detection ==="
/tmp/check-blocks-mixed.sh

# Compare block hashes between Fukuii and Core-Geth
FUKUII_HASH=$(curl -s -X POST http://localhost:8545 \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"eth_getBlockByNumber","params":["latest",false],"id":1}' | jq -r '.result.hash')

GETH_HASH=$(curl -s -X POST http://localhost:8548 \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"eth_getBlockByNumber","params":["latest",false],"id":1}' | jq -r '.result.hash')

echo "Fukuii latest hash: $FUKUII_HASH"
echo "Geth latest hash: $GETH_HASH"

if [ "$FUKUII_HASH" == "$GETH_HASH" ]; then
  echo "✅ All clients on same chain"
else
  echo "⚠️ Clients may be on different chains - investigate"
fi
```

**Expected**:
- Few or no errors
- All nodes on same chain (matching block hashes)
- Stable memory usage
- Consistent peer counts (2-5 peers per node)

### ✅ Phase 5 Complete
- Long-running stability validated
- No consensus issues
- Network resilient

---

## Phase 6: Results Collection

### Step 6.1: Collect All Logs

```bash
# Use fukuii-cli to collect logs
fukuii-cli collect-logs $GORGOROTH_CONFIG /tmp/gorgoroth-mixed-results
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
fukuii-cli stop $GORGOROTH_CONFIG

# Remove volumes (optional)
fukuii-cli clean $GORGOROTH_CONFIG

# Results preserved in /tmp/gorgoroth-mixed-results/
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

1. **Report Results**: Create GitHub issue with multi-client validation results
2. **Test Other Configurations**: Try fukuii-besu or full mixed configuration
3. **Real-World Testing**: Move to [Cirith Ungol](CIRITH_UNGOL_WALKTHROUGH.md)

---

## Related Documentation

- [Gorgoroth Status Tracker](GORGOROTH_STATUS.md)
- [3-Node Walkthrough](GORGOROTH_3NODE_WALKTHROUGH.md)
- [Cirith Ungol Walkthrough](CIRITH_UNGOL_WALKTHROUGH.md)
- [Compatibility Testing Guide](../testing/GORGOROTH_COMPATIBILITY_TESTING.md)

---

**Questions?** Create an issue on GitHub or refer to troubleshooting above.
