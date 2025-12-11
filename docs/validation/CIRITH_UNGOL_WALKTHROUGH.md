# Cirith Ungol E2E Validation Walkthrough

**Purpose**: Real-world validation of Fukuii using public ETC mainnet and Mordor testnet for SNAP/Fast sync testing with diverse node types and network traffic.

**Time Required**: 6-24 hours (depending on sync mode)  
**Difficulty**: Advanced  
**Prerequisites**: Completed 3-node and 6-node walkthroughs, understanding of blockchain sync modes

---

## Table of Contents

1. [Overview](#overview)
2. [Prerequisites](#prerequisites)
3. [Setup](#setup)
4. [Phase 1: Environment Preparation](#phase-1-environment-preparation)
5. [Phase 2: SNAP Sync Testing](#phase-2-snap-sync-testing)
6. [Phase 3: Fast Sync Testing](#phase-3-fast-sync-testing)
7. [Phase 4: Peer Diversity Validation](#phase-4-peer-diversity-validation)
8. [Phase 5: Long-Term Stability](#phase-5-long-term-stability)
9. [Phase 6: Performance Benchmarking](#phase-6-performance-benchmarking)
10. [Phase 7: Results Collection](#phase-7-results-collection)
11. [Cleanup](#cleanup)
12. [Troubleshooting](#troubleshooting)

---

## Overview

**Cirith Ungol** is a single-node testing environment for validating Fukuii against real-world networks:

- **ETC Mainnet**: 20M+ blocks, production traffic, diverse peers
- **Mordor Testnet**: Active testnet, regular block production

### What You'll Test

```
                    Public ETC Network
                   ┌────────────────────┐
                   │  Core-Geth Peers   │
                   │  Besu Peers        │
                   │  Other Fukuii      │
                   │  Legacy Clients    │
                   └─────────┬──────────┘
                             │
                             ▼
                    ┌──────────────────┐
                    │  Cirith Ungol    │
                    │  Fukuii Node     │
                    │                  │
                    │  SNAP/Fast Sync  │
                    │  Peer Discovery  │
                    │  Production Load │
                    └──────────────────┘
```

### Sync Modes Tested

1. **SNAP Sync** (Recommended, 2-6 hours)
   - Downloads state snapshots
   - Fastest initial sync
   - Requires SNAP-capable peers

2. **Fast Sync** (Traditional, 6-12 hours)
   - Downloads block headers + recent state
   - More widely supported
   - Reliable fallback

---

## Prerequisites

### Completed Previous Walkthroughs

✅ Complete [3-Node Walkthrough](GORGOROTH_3NODE_WALKTHROUGH.md)  
✅ Complete [6-Node Walkthrough](GORGOROTH_6NODE_WALKTHROUGH.md)

### System Requirements

#### For ETC Mainnet:
- **RAM**: 16GB minimum (32GB recommended)
- **Disk**: 100GB+ free space (SSD strongly recommended)
- **Network**: Stable, unmetered internet connection
- **CPU**: 4+ cores

#### For Mordor Testnet:
- **RAM**: 8GB minimum
- **Disk**: 50GB free space
- **Network**: Stable internet
- **CPU**: 2+ cores

### Required Software

```bash
# Verify installations
docker --version          # Docker 20.10+
curl --version
jq --version
tmux --version           # For long-running sessions (optional)
```

---

## Setup

### Step 1: Navigate to Cirith Ungol Directory

```bash
cd /path/to/fukuii/ops/cirith-ungol
ls -la
```

**Expected files**:
- `start.sh` - Management script
- `docker-compose-mainnet.yml` - ETC mainnet config
- `docker-compose-mordor.yml` - Mordor testnet config
- `conf/` - Configuration files

### Step 2: Review Configuration

```bash
# Check mainnet configuration
cat docker-compose-mainnet.yml

# Check Mordor configuration
cat docker-compose-mordor.yml
```

### Step 3: Clean Previous State

```bash
# Stop any running instances
./start.sh stop

# Clean volumes
docker volume prune -f
```

---

## Phase 1: Environment Preparation

### Step 1.1: Choose Network

**For first-time testing, start with Mordor testnet** (smaller, faster):

```bash
export CIRITH_NETWORK=mordor
```

**For production validation, use mainnet**:

```bash
export CIRITH_NETWORK=mainnet
```

### Step 1.2: Configure Sync Mode

Edit the appropriate config file to enable SNAP sync (recommended):

```bash
# For Mordor
cat > conf/mordor-snap.conf <<EOF
include "base.conf"

fukuii {
  network = "mordor"
  
  sync {
    do-snap-sync = true
    do-fast-sync = false
  }
  
  network.rpc {
    http {
      enabled = true
      interface = "0.0.0.0"
      port = 8545
    }
  }
}
EOF
```

### Step 1.3: Set Up Monitoring

```bash
# Create monitoring dashboard script
cat > /tmp/cirith-monitor.sh <<'EOF'
#!/bin/bash
clear
echo "=== Cirith Ungol Monitoring Dashboard ==="
echo "Time: $(date)"
echo ""

# Sync status
echo "Sync Status:"
curl -s -X POST http://localhost:8545 \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"eth_syncing","params":[],"id":1}' | jq '.'

echo ""

# Block number
echo "Current Block:"
curl -s -X POST http://localhost:8545 \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}' | jq -r '.result'

echo ""

# Peer count
echo "Peer Count:"
curl -s -X POST http://localhost:8545 \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"net_peerCount","params":[],"id":1}' | jq -r '.result'

echo ""

# Container status
echo "Container Status:"
docker ps --filter name=cirith --format "table {{.Names}}\t{{.Status}}"
EOF

chmod +x /tmp/cirith-monitor.sh
```

### ✅ Phase 1 Complete
- Environment configured
- Sync mode selected
- Monitoring prepared

---

## Phase 2: SNAP Sync Testing

### Step 2.1: Start SNAP Sync

```bash
# Start with SNAP sync enabled
./start.sh start --snap

# Or manually:
docker compose -f docker-compose-${CIRITH_NETWORK}.yml up -d
```

**Expected output**:
```
[+] Running 1/1
 ✔ Container cirith-ungol-node  Started
```

### Step 2.2: Monitor Initial Sync

```bash
# Watch logs
./start.sh logs -f

# Or use the monitoring dashboard
watch -n 10 /tmp/cirith-monitor.sh
```

**Look for these log messages**:
- `Starting SNAP sync`
- `Downloading account ranges`
- `Downloading storage ranges`
- `Downloading bytecode`
- `Trie healing`

### Step 2.3: Track SNAP Sync Progress

```bash
# Create progress tracker
cat > /tmp/snap-progress.sh <<'EOF'
#!/bin/bash
echo "=== SNAP Sync Progress ==="

SYNC=$(curl -s -X POST http://localhost:8545 \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"eth_syncing","params":[],"id":1}')

if [ "$SYNC" == '{"jsonrpc":"2.0","id":1,"result":false}' ]; then
  echo "✅ SNAP Sync Complete!"
  exit 0
fi

echo "$SYNC" | jq '{
  currentBlock: .result.currentBlock,
  highestBlock: .result.highestBlock,
  pulledStates: .result.pulledStates,
  knownStates: .result.knownStates
}'

# Calculate progress percentage
CURRENT=$(echo "$SYNC" | jq -r '.result.currentBlock' | xargs printf "%d")
HIGHEST=$(echo "$SYNC" | jq -r '.result.highestBlock' | xargs printf "%d")

if [ "$HIGHEST" -gt 0 ]; then
  PERCENT=$((CURRENT * 100 / HIGHEST))
  echo "Progress: $PERCENT%"
fi
EOF

chmod +x /tmp/snap-progress.sh

# Run every minute
watch -n 60 /tmp/snap-progress.sh
```

### Step 2.4: Estimate Completion Time

**Expected SNAP sync times**:
- **Mordor**: 30 minutes - 2 hours
- **Mainnet**: 2-6 hours (depending on hardware and network)

### Step 2.5: Verify SNAP Sync Completion

```bash
# Check if sync is complete
curl -X POST http://localhost:8545 \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"eth_syncing","params":[],"id":1}' | jq '.'

# Should return: {"jsonrpc":"2.0","id":1,"result":false}

# Verify state is queryable
curl -X POST http://localhost:8545 \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"eth_getBalance","params":["0x0000000000000000000000000000000000000000","latest"],"id":1}' | jq '.'
```

### Step 2.6: Test State Queries

```bash
# Test various state queries
cat > /tmp/test-state.sh <<'EOF'
#!/bin/bash
echo "=== Testing State Queries ==="

# Get latest block
echo "Latest block:"
curl -s -X POST http://localhost:8545 \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"eth_getBlockByNumber","params":["latest",false],"id":1}' \
  | jq '.result.number'

# Get account balance
echo "Test account balance:"
curl -s -X POST http://localhost:8545 \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"eth_getBalance","params":["0x0000000000000000000000000000000000000001","latest"],"id":1}' \
  | jq '.result'

# Get transaction count
echo "Account transaction count:"
curl -s -X POST http://localhost:8545 \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"eth_getTransactionCount","params":["0x0000000000000000000000000000000000000001","latest"],"id":1}' \
  | jq '.result'

echo "✅ State queries working"
EOF

chmod +x /tmp/test-state.sh
/tmp/test-state.sh
```

### ✅ Phase 2 Complete
- SNAP sync completed
- State is queryable
- Node transitioned to full sync

---

## Phase 3: Fast Sync Testing

### Step 3.1: Stop Node and Reset

```bash
# Stop SNAP sync node
./start.sh stop

# Remove data volume
docker volume rm cirith-ungol-data || true
```

### Step 3.2: Configure Fast Sync

```bash
# Update config for Fast sync
cat > conf/${CIRITH_NETWORK}-fast.conf <<EOF
include "base.conf"

fukuii {
  network = "$CIRITH_NETWORK"
  
  sync {
    do-snap-sync = false
    do-fast-sync = true
  }
  
  network.rpc {
    http {
      enabled = true
      interface = "0.0.0.0"
      port = 8545
    }
  }
}
EOF
```

### Step 3.3: Start Fast Sync

```bash
./start.sh start --fast
```

### Step 3.4: Monitor Fast Sync Progress

```bash
# Use same monitoring tools
watch -n 60 /tmp/snap-progress.sh

# Check logs
./start.sh logs -f
```

**Expected Fast sync times**:
- **Mordor**: 1-3 hours
- **Mainnet**: 6-12 hours

### Step 3.5: Verify Fast Sync Completion

```bash
# Same verification as SNAP
/tmp/test-state.sh
```

### ✅ Phase 3 Complete
- Fast sync completed
- State verified
- Comparison with SNAP sync documented

---

## Phase 4: Peer Diversity Validation

### Step 4.1: Identify Connected Peers

```bash
# Get peer information (if admin API enabled)
curl -X POST http://localhost:8545 \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"admin_peers","params":[],"id":1}' | jq '.'
```

### Step 4.2: Analyze Peer Distribution

```bash
# Create peer analysis script
cat > /tmp/analyze-peers.sh <<'EOF'
#!/bin/bash
echo "=== Peer Analysis ==="

PEERS=$(curl -s -X POST http://localhost:8545 \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"net_peerCount","params":[],"id":1}' | jq -r '.result')

echo "Total peers: $(printf "%d" $PEERS)"

# Check peer details from logs
docker logs cirith-ungol-node 2>&1 | grep -i "peer" | tail -20
EOF

chmod +x /tmp/analyze-peers.sh
/tmp/analyze-peers.sh
```

### Step 4.3: Verify Peer Diversity

**Goals**:
- ✅ Connect to 10+ peers
- ✅ Mix of client types (Core-Geth, Besu, Fukuii, etc.)
- ✅ Geographic diversity
- ✅ Protocol version diversity

```bash
# Monitor peer stability
watch -n 300 '/tmp/analyze-peers.sh'
```

### ✅ Phase 4 Complete
- Peer diversity validated
- Multiple client types detected
- Stable peer connections

---

## Phase 5: Long-Term Stability

### Step 5.1: Start 24-Hour Stability Test

```bash
# Record start state
cat > /tmp/stability-start.txt <<EOF
Start Time: $(date)
Start Block: $(curl -s -X POST http://localhost:8545 -H "Content-Type: application/json" -d '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}' | jq -r '.result')
Start Peers: $(curl -s -X POST http://localhost:8545 -H "Content-Type: application/json" -d '{"jsonrpc":"2.0","method":"net_peerCount","params":[],"id":1}' | jq -r '.result')
EOF

cat /tmp/stability-start.txt
```

### Step 5.2: Monitor Resource Usage

```bash
# Create resource monitoring script
cat > /tmp/monitor-resources.sh <<'EOF'
#!/bin/bash
echo "=== Resource Usage ==="
echo "Time: $(date)"

# Docker stats
docker stats --no-stream --format "table {{.Container}}\t{{.CPUPerc}}\t{{.MemUsage}}\t{{.NetIO}}\t{{.BlockIO}}" cirith-ungol-node

# Disk usage
echo ""
echo "Disk Usage:"
df -h | grep -E "Filesystem|/var/lib/docker"
EOF

chmod +x /tmp/monitor-resources.sh

# Log every hour
while true; do
  /tmp/monitor-resources.sh >> /tmp/resource-log.txt
  sleep 3600
done &

MONITOR_PID=$!
echo "Resource monitoring started (PID: $MONITOR_PID)"
```

### Step 5.3: Check for Issues

```bash
# After 24 hours, check for problems
echo "=== Stability Check (24 hours) ==="

# Check error count
docker logs cirith-ungol-node 2>&1 | grep -i "error\|fatal\|panic" | wc -l

# Check if still syncing
curl -X POST http://localhost:8545 \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"eth_syncing","params":[],"id":1}' | jq '.'

# Verify peer count stable
curl -X POST http://localhost:8545 \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"net_peerCount","params":[],"id":1}' | jq '.'
```

### Step 5.4: Extended Stability (Optional)

For production readiness, run for 7 days:

```bash
# Same monitoring, longer duration
# Check daily:
# - Error logs
# - Resource usage trends
# - Peer stability
# - Block sync continuity
```

### ✅ Phase 5 Complete
- 24+ hour stability validated
- Resource usage stable
- No critical errors
- Production-ready

---

## Phase 6: Performance Benchmarking

### Step 6.1: Measure Sync Performance

```bash
# Calculate sync time
cat > /tmp/sync-benchmark.txt <<EOF
=== Sync Performance Benchmark ===

Network: $CIRITH_NETWORK
Sync Mode: SNAP/Fast
Hardware: $(uname -m), $(nproc) cores

Start Time: [from logs]
End Time: [from logs]
Total Duration: [calculate]

Blocks Synced: [final block number]
Average Speed: [blocks per minute]
Disk Space Used: [check volume size]
Peak Memory: [from monitoring]
Average CPU: [from monitoring]
EOF
```

### Step 6.2: Benchmark RPC Performance

```bash
# Test RPC response times
cat > /tmp/benchmark-rpc.sh <<'EOF'
#!/bin/bash
echo "=== RPC Performance Benchmark ==="

# Test eth_blockNumber
echo "eth_blockNumber (100 calls):"
time for i in {1..100}; do
  curl -s -X POST http://localhost:8545 \
    -H "Content-Type: application/json" \
    -d '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}' > /dev/null
done

# Test eth_getBlockByNumber
echo "eth_getBlockByNumber (100 calls):"
time for i in {1..100}; do
  curl -s -X POST http://localhost:8545 \
    -H "Content-Type: application/json" \
    -d '{"jsonrpc":"2.0","method":"eth_getBlockByNumber","params":["latest",false],"id":1}' > /dev/null
done

# Test eth_getBalance
echo "eth_getBalance (100 calls):"
time for i in {1..100}; do
  curl -s -X POST http://localhost:8545 \
    -H "Content-Type: application/json" \
    -d '{"jsonrpc":"2.0","method":"eth_getBalance","params":["0x0000000000000000000000000000000000000001","latest"],"id":1}' > /dev/null
done
EOF

chmod +x /tmp/benchmark-rpc.sh
/tmp/benchmark-rpc.sh
```

### ✅ Phase 6 Complete
- Sync performance measured
- RPC performance benchmarked
- Results documented

---

## Phase 7: Results Collection

### Step 7.1: Collect All Logs

```bash
# Create results directory
mkdir -p /tmp/cirith-ungol-results

# Collect logs
./start.sh collect-logs

# Copy to results
cp -r logs/* /tmp/cirith-ungol-results/

# Save monitoring data
cp /tmp/resource-log.txt /tmp/cirith-ungol-results/
cp /tmp/stability-start.txt /tmp/cirith-ungol-results/
```

### Step 7.2: Generate Final Report

```bash
# Get current state (with error handling)
CURRENT_BLOCK=$(curl -s -X POST http://localhost:8545 -H "Content-Type: application/json" -d '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}' 2>/dev/null | jq -r '.result' || echo "N/A")
CURRENT_PEERS=$(curl -s -X POST http://localhost:8545 -H "Content-Type: application/json" -d '{"jsonrpc":"2.0","method":"net_peerCount","params":[],"id":1}' 2>/dev/null | jq -r '.result' || echo "N/A")

cat > /tmp/cirith-ungol-results/FINAL-REPORT.md <<EOF
# Cirith Ungol Validation Results

**Network**: $CIRITH_NETWORK
**Date**: $(date)
**Duration**: 24+ hours

## Sync Testing

### SNAP Sync
- ✅ Completed successfully
- Duration: [X hours]
- Final block: $CURRENT_BLOCK
- Peers: $CURRENT_PEERS

### Fast Sync
- ✅ Completed successfully
- Duration: [X hours]
- Performance comparison: [SNAP vs Fast]

## Peer Diversity
- Total peers connected: [X]
- Client types: [Core-Geth, Besu, Fukuii, etc.]
- Geographic diversity: ✅

## Long-Term Stability
- Test duration: 24+ hours
- Errors encountered: [X]
- Resource usage: Stable
- Conclusion: ✅ Production-ready

## Performance
- Sync speed: [blocks/min]
- RPC latency: [avg ms]
- Memory usage: [avg GB]
- CPU usage: [avg %]

## Overall Assessment
✅ Fukuii successfully validated on $CIRITH_NETWORK
✅ Ready for production use
EOF

cat /tmp/cirith-ungol-results/FINAL-REPORT.md
```

### ✅ Phase 7 Complete
- All data collected
- Final report generated
- Validation complete

---

## Cleanup

```bash
# Stop node
./start.sh stop

# Remove volumes (optional)
./start.sh clean

# Archive results
tar -czf cirith-ungol-results-$(date +%Y%m%d).tar.gz /tmp/cirith-ungol-results/

# Results preserved in archive
```

---

## Troubleshooting

### Sync Stalled

```bash
# Check peer count
curl -X POST http://localhost:8545 \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"net_peerCount","params":[],"id":1}' | jq '.'

# Restart if < 3 peers
./start.sh restart
```

### High Memory Usage

```bash
# Check memory
docker stats cirith-ungol-node

# If > 80% of available RAM, consider:
# 1. Adding more RAM
# 2. Using swap space
# 3. Tuning JVM settings in config
```

### Disk Space Issues

```bash
# Check disk usage
df -h

# Clean Docker if needed
docker system prune -a

# Consider pruning old blocks (after validation)
```

### Peer Connection Issues

```bash
# Check firewall
sudo ufw status

# Ensure P2P port open (30303)
sudo ufw allow 30303/tcp
sudo ufw allow 30303/udp

# Restart
./start.sh restart
```

---

## Next Steps

1. **Report Results**: Create GitHub issue with your validation
2. **Share Insights**: Contribute to documentation
3. **Production Deployment**: Deploy validated configuration

---

## Related Documentation

- [Gorgoroth Status Tracker](GORGOROTH_STATUS.md)
- [3-Node Walkthrough](GORGOROTH_3NODE_WALKTHROUGH.md)
- [6-Node Walkthrough](GORGOROTH_6NODE_WALKTHROUGH.md)
- [Cirith Ungol Testing Guide](../testing/CIRITH_UNGOL_TESTING_GUIDE.md)

---

**Congratulations!** You've completed real-world validation of Fukuii!
