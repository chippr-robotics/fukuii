# Gorgoroth Network - Multi-Client Compatibility Testing Guide

> **Purpose**: This guide provides step-by-step instructions for validating Fukuii compatibility with Core-Geth and Hyperledger Besu on the Gorgoroth test network.

## Table of Contents

1. [Overview](#overview)
2. [Prerequisites](#prerequisites)
3. [Test Scenarios](#test-scenarios)
4. [Network Communication Testing](#network-communication-testing)
5. [Mining Compatibility Testing](#mining-compatibility-testing)
6. [Fast Sync Testing](#fast-sync-testing)
7. [Snap Sync Testing](#snap-sync-testing)
8. [Automated Testing Scripts](#automated-testing-scripts)
9. [Results and Compatibility Matrix](#results-and-compatibility-matrix)
10. [Troubleshooting](#troubleshooting)

## Overview

The Gorgoroth test network validates Fukuii's compatibility with other Ethereum Classic clients in a controlled environment. This guide covers testing the following areas:

- **Network Communication**: Peer discovery, handshakes, and block propagation
- **Mining**: Block production and consensus across different clients
- **Fast Sync**: Initial blockchain synchronization using fast sync protocol
- **Snap Sync**: Snapshot-based synchronization (if supported)
- **Faucet Service**: Testnet token distribution functionality

### Test Configurations Available

| Configuration | Fukuii Nodes | Core-Geth Nodes | Besu Nodes | Total |
|--------------|--------------|-----------------|------------|-------|
| `3nodes` | 3 | 0 | 0 | 3 |
| `6nodes` | 6 | 0 | 0 | 6 |
| `fukuii-geth` | 3 | 3 | 0 | 6 |
| `fukuii-besu` | 3 | 0 | 3 | 6 |
| `mixed` | 3 | 3 | 3 | 9 |

## Prerequisites

### Required Software

- Docker 20.10+ with Docker Compose 2.0+
- `curl` for API testing
- `jq` for JSON processing
- Minimum 8GB RAM (for larger configurations)
- 20GB free disk space

### Optional Tools

- Insomnia or Postman for API testing
- `netcat` for network debugging
- `watch` for continuous monitoring

### Installation

```bash
# Install fukuii-cli if not already installed
sudo cp ops/tools/fukuii-cli.sh /usr/local/bin/fukuii-cli
sudo chmod +x /usr/local/bin/fukuii-cli

# Verify installation
fukuii-cli --help
```

## Test Scenarios

### Scenario 1: Fukuii-only Network (Baseline)

**Purpose**: Establish baseline performance and verify Fukuii works correctly in isolation.

**Configuration**: `3nodes` or `6nodes`

**Expected Outcomes**:
- All nodes connect to each other
- Blocks are mined and propagated
- Consensus is maintained across all nodes

### Scenario 2: Fukuii + Core-Geth Mixed Network

**Purpose**: Validate Fukuii can communicate and maintain consensus with Core-Geth nodes.

**Configuration**: `fukuii-geth`

**Expected Outcomes**:
- Fukuii nodes can connect to Core-Geth nodes
- Blocks mined by either client are accepted by both
- Network stays in sync

### Scenario 3: Fukuii + Besu Mixed Network

**Purpose**: Validate Fukuii can communicate and maintain consensus with Hyperledger Besu nodes.

**Configuration**: `fukuii-besu`

**Expected Outcomes**:
- Fukuii nodes can connect to Besu nodes
- Blocks mined by either client are accepted by both
- Network stays in sync

### Scenario 4: Full Multi-Client Network

**Purpose**: Validate Fukuii in a diverse network with multiple client implementations.

**Configuration**: `mixed`

**Expected Outcomes**:
- All clients can communicate with each other
- Blocks from any client are accepted by all others
- Network consensus is maintained

## Network Communication Testing

### Test 1.1: Basic Connectivity

**Objective**: Verify all nodes can establish peer connections.

```bash
# Start the network
cd ops/gorgoroth
fukuii-cli start fukuii-geth

# Wait for initialization
sleep 60

# Check peer count on each Fukuii node
for port in 8545 8547 8549; do
  echo "=== Fukuii Node on port $port ==="
  curl -s -X POST -H "Content-Type: application/json" \
    --data '{"jsonrpc":"2.0","method":"net_peerCount","params":[],"id":1}' \
    http://localhost:$port | jq -r '.result' | xargs printf "Peers: %d\n"
done

# Check peer count on each Core-Geth node
for port in 8551 8553 8555; do
  echo "=== Core-Geth Node on port $port ==="
  curl -s -X POST -H "Content-Type: application/json" \
    --data '{"jsonrpc":"2.0","method":"net_peerCount","params":[],"id":1}' \
    http://localhost:$port | jq -r '.result' | xargs printf "Peers: %d\n"
done
```

**Success Criteria**:
- Each node has at least 2 connected peers
- Fukuii nodes show Core-Geth nodes as peers
- Core-Geth nodes show Fukuii nodes as peers

### Test 1.2: Protocol Compatibility

**Objective**: Verify protocol version compatibility between clients.

```bash
# Get protocol version from Fukuii
curl -s -X POST -H "Content-Type: application/json" \
  --data '{"jsonrpc":"2.0","method":"eth_protocolVersion","params":[],"id":1}' \
  http://localhost:8545 | jq

# Get protocol version from Core-Geth
curl -s -X POST -H "Content-Type: application/json" \
  --data '{"jsonrpc":"2.0","method":"eth_protocolVersion","params":[],"id":1}' \
  http://localhost:8551 | jq

# Get network version
for port in 8545 8551; do
  echo "=== Port $port ==="
  curl -s -X POST -H "Content-Type: application/json" \
    --data '{"jsonrpc":"2.0","method":"net_version","params":[],"id":1}' \
    http://localhost:$port | jq -r '.result'
done
```

**Success Criteria**:
- All nodes report compatible protocol versions
- All nodes report the same network ID (1337)
- No protocol mismatch errors in logs

### Test 1.3: Block Propagation

**Objective**: Verify blocks mined by one client are received by others.

```bash
# Monitor block numbers across all nodes
watch -n 5 'for port in 8545 8547 8549 8551 8553 8555; do
  echo -n "Port $port: "
  curl -s -X POST -H "Content-Type: application/json" \
    --data "{\"jsonrpc\":\"2.0\",\"method\":\"eth_blockNumber\",\"params\":[],\"id\":1}" \
    http://localhost:$port | jq -r ".result"
done'
```

**Success Criteria**:
- All nodes reach the same block height within 2-3 block times (~30-45 seconds)
- No nodes get stuck or fall behind permanently
- Block hashes match across all nodes at the same height

### Test 1.4: Transaction Propagation

**Objective**: Verify transactions submitted to one client appear on all clients.

```bash
# Submit a transaction to a Fukuii node
TX_HASH=$(curl -s -X POST -H "Content-Type: application/json" \
  --data '{
    "jsonrpc":"2.0",
    "method":"eth_sendTransaction",
    "params":[{
      "from":"0x1000000000000000000000000000000000000001",
      "to":"0x2000000000000000000000000000000000000002",
      "value":"0x1000"
    }],
    "id":1
  }' http://localhost:8545 | jq -r '.result')

# Wait for transaction to propagate
sleep 10

# Check transaction on Core-Geth node
curl -s -X POST -H "Content-Type: application/json" \
  --data "{
    \"jsonrpc\":\"2.0\",
    \"method\":\"eth_getTransactionByHash\",
    \"params\":[\"$TX_HASH\"],
    \"id\":1
  }" http://localhost:8551 | jq
```

**Success Criteria**:
- Transaction appears in mempool of all nodes
- Transaction gets mined within reasonable time
- Transaction receipt is identical across all clients

## Mining Compatibility Testing

### Test 2.1: Multi-Client Mining

**Objective**: Verify blocks mined by different clients are accepted by all.

```bash
# Identify who mined recent blocks
for i in {1..10}; do
  echo "=== Block $i ==="
  
  # Get block from Fukuii node
  BLOCK=$(curl -s -X POST -H "Content-Type: application/json" \
    --data "{\"jsonrpc\":\"2.0\",\"method\":\"eth_getBlockByNumber\",\"params\":[\"0x$i\",false],\"id\":1}" \
    http://localhost:8545 | jq -r '.result')
  
  MINER=$(echo $BLOCK | jq -r '.miner')
  HASH=$(echo $BLOCK | jq -r '.hash')
  
  echo "Miner: $MINER"
  echo "Hash: $HASH"
  
  # Verify same block on Core-Geth
  GETH_HASH=$(curl -s -X POST -H "Content-Type: application/json" \
    --data "{\"jsonrpc\":\"2.0\",\"method\":\"eth_getBlockByNumber\",\"params\":[\"0x$i\",false],\"id\":1}" \
    http://localhost:8551 | jq -r '.result.hash')
  
  if [ "$HASH" = "$GETH_HASH" ]; then
    echo "✓ Block hash matches"
  else
    echo "✗ Block hash mismatch!"
  fi
  echo ""
done
```

**Success Criteria**:
- Blocks are mined by both Fukuii and Core-Geth nodes
- Block hashes match across all clients
- No orphaned blocks or reorgs

### Test 2.2: Mining Difficulty Adjustment

**Objective**: Verify difficulty adjusts correctly across clients.

```bash
# Monitor difficulty over time
for i in {1..20}; do
  BLOCK_NUM=$(printf "0x%x" $i)
  echo "=== Block $i ==="
  
  # Get difficulty from both clients
  FUKUII_DIFF=$(curl -s -X POST -H "Content-Type: application/json" \
    --data "{\"jsonrpc\":\"2.0\",\"method\":\"eth_getBlockByNumber\",\"params\":[\"$BLOCK_NUM\",false],\"id\":1}" \
    http://localhost:8545 | jq -r '.result.difficulty')
  
  GETH_DIFF=$(curl -s -X POST -H "Content-Type: application/json" \
    --data "{\"jsonrpc\":\"2.0\",\"method\":\"eth_getBlockByNumber\",\"params\":[\"$BLOCK_NUM\",false],\"id\":1}" \
    http://localhost:8551 | jq -r '.result.difficulty')
  
  echo "Fukuii difficulty: $FUKUII_DIFF"
  echo "Geth difficulty: $GETH_DIFF"
  echo ""
  
  sleep 15
done
```

**Success Criteria**:
- Difficulty values match between clients
- Difficulty adjusts according to ETC rules
- No difficulty bomb or unexpected difficulty changes

### Test 2.3: Consensus Maintenance

**Objective**: Verify network maintains consensus during extended mining.

```bash
# Run for 30 minutes and check for chain splits
START_TIME=$(date +%s)
END_TIME=$((START_TIME + 1800))

while [ $(date +%s) -lt $END_TIME ]; do
  # Get latest block from each node
  echo "=== $(date) ==="
  
  for port in 8545 8547 8549 8551 8553 8555; do
    BLOCK_INFO=$(curl -s -X POST -H "Content-Type: application/json" \
      --data '{"jsonrpc":"2.0","method":"eth_getBlockByNumber","params":["latest",false],"id":1}' \
      http://localhost:$port | jq -r '.result | "\(.number) \(.hash)"')
    echo "Port $port: $BLOCK_INFO"
  done
  
  echo ""
  sleep 60
done
```

**Success Criteria**:
- All nodes remain on the same chain
- No persistent chain splits occur
- Block times remain consistent

## Fast Sync Testing

### Test 3.1: Fast Sync from Fukuii Node

**Objective**: Verify a new node can fast sync from Fukuii nodes.

**Setup**: Create a configuration with fast sync enabled.

```bash
# Create a test configuration for fast sync node
mkdir -p /tmp/gorgoroth-fastsync
cat > /tmp/gorgoroth-fastsync/fastsync.conf << 'EOF'
include "base-gorgoroth.conf"

fukuii {
  sync {
    do-fast-sync = true
    fast-sync-throttle = 100.milliseconds
    peers-scan-interval = 3.seconds
  }
  
  blockchain {
    custom-genesis-file = "gorgoroth-genesis.json"
  }
}
EOF

# Add to docker-compose temporarily for testing
# (Implementation details in separate test setup script)
```

**Test Procedure**:

1. Start a network with Fukuii and Core-Geth nodes mining
2. Wait for 500+ blocks to be mined
3. Start a new Fukuii node with fast sync enabled
4. Monitor sync progress

```bash
# Check sync status on fast sync node
watch -n 5 'curl -s -X POST -H "Content-Type: application/json" \
  --data "{\"jsonrpc\":\"2.0\",\"method\":\"eth_syncing\",\"params\":[],\"id\":1}" \
  http://localhost:8557 | jq'
```

**Success Criteria**:
- Fast sync completes successfully
- Node reaches current block height
- State root matches other nodes
- Sync time is significantly less than full sync

### Test 3.2: Fast Sync from Core-Geth Node

**Objective**: Verify a new Fukuii node can fast sync from Core-Geth nodes.

**Procedure**: Same as Test 3.1 but ensure Core-Geth nodes are the primary block producers.

**Success Criteria**:
- Fast sync completes successfully from Core-Geth peers
- No protocol incompatibilities
- Synced state is valid and complete

### Test 3.3: Fast Sync from Besu Node

**Objective**: Verify a new Fukuii node can fast sync from Besu nodes.

**Procedure**: Same as Test 3.1 but using Besu nodes as sync sources.

**Success Criteria**:
- Fast sync completes successfully from Besu peers
- No protocol incompatibilities
- Synced state is valid and complete

## Snap Sync Testing

### Test 4.1: Snap Sync Capability Check

**Objective**: Determine which clients support snap sync on Gorgoroth network.

```bash
# Check if snap sync is supported
for port in 8545 8551; do
  echo "=== Testing port $port ==="
  
  # Check available eth protocols
  curl -s -X POST -H "Content-Type: application/json" \
    --data '{"jsonrpc":"2.0","method":"admin_nodeInfo","params":[],"id":1}' \
    http://localhost:$port | jq '.result.protocols'
done
```

**Expected Results**:
- Fukuii: Check for snap/1 protocol support
- Core-Geth: Check for snap/1 protocol support  
- Besu: May or may not support snap sync

### Test 4.2: Snap Sync from Fukuii Node

**Objective**: If supported, verify snap sync works from Fukuii nodes.

**Configuration**: Create a node with snap sync enabled.

```bash
# Configuration snippet for snap sync
cat > /tmp/gorgoroth-snapsync/snapsync.conf << 'EOF'
include "base-gorgoroth.conf"

fukuii {
  sync {
    do-snap-sync = true
    snap-sync-pivot-block-offset = 64
  }
}
EOF
```

**Test Procedure**:

1. Ensure network has 1000+ blocks
2. Start new node with snap sync enabled
3. Monitor sync progress
4. Verify state is complete

```bash
# Monitor snap sync progress
watch -n 10 'curl -s -X POST -H "Content-Type: application/json" \
  --data "{\"jsonrpc\":\"2.0\",\"method\":\"eth_syncing\",\"params\":[],\"id\":1}" \
  http://localhost:8557 | jq'
```

**Success Criteria**:
- Snap sync completes successfully
- State root matches network
- Account state is queryable
- Contract storage is accessible

### Test 4.3: Snap Sync Mixed Client Support

**Objective**: Verify snap sync works in mixed client environment.

**Success Criteria**:
- Snap sync node can fetch data from any available client
- Sync completes regardless of peer client type
- Final state is valid

## Faucet Service Testing

### Test 5.1: Faucet Service Availability

**Objective**: Verify the faucet service can be started and accessed.

**Setup**: Configure and start the faucet service.

```bash
# Configure faucet for Gorgoroth
cat > /tmp/faucet-gorgoroth.conf << 'EOF'
include "faucet.conf"

faucet {
  wallet-address = "0x1000000000000000000000000000000000000001"
  wallet-password = ""
  rpc-client.rpc-address = "http://localhost:8545/"
  tx-value = 500000000000000000  # 0.5 ETC
}

fukuii.network.rpc.http {
  port = 8099
}
EOF

# Start faucet
./bin/fukuii faucet -Dconfig.file=/tmp/faucet-gorgoroth.conf
```

**Test Procedure**:

```bash
# Check if faucet is accessible
curl -X POST -H "Content-Type: application/json" \
  --data '{"jsonrpc":"2.0","method":"faucet_status","params":[],"id":1}' \
  http://localhost:8099
```

**Success Criteria**:
- Faucet service starts without errors
- HTTP endpoint is accessible on port 8099
- Status endpoint returns valid response

### Test 5.2: Faucet Wallet Initialization

**Objective**: Verify faucet wallet is properly initialized and has funds.

```bash
# Check faucet status
curl -X POST -H "Content-Type: application/json" \
  --data '{"jsonrpc":"2.0","method":"faucet_status","params":[],"id":1}' \
  http://localhost:8099 | jq
```

**Success Criteria**:
- Status returns `"WalletAvailable"`
- No errors in faucet logs
- Wallet address has sufficient balance

### Test 5.3: Fund Distribution

**Objective**: Verify faucet can send funds to test addresses.

```bash
# Request funds
curl -X POST -H "Content-Type: application/json" \
  --data '{
    "jsonrpc":"2.0",
    "method":"faucet_sendFunds",
    "params":[{
      "address":"0x2000000000000000000000000000000000000002"
    }],
    "id":1
  }' \
  http://localhost:8099 | jq

# Wait for transaction to be mined
sleep 30

# Verify recipient balance increased
curl -X POST -H "Content-Type: application/json" \
  --data '{
    "jsonrpc":"2.0",
    "method":"eth_getBalance",
    "params":["0x2000000000000000000000000000000000000002","latest"],
    "id":1
  }' \
  http://localhost:8545 | jq
```

**Success Criteria**:
- Faucet returns transaction hash
- Transaction is mined successfully
- Recipient balance increases by expected amount
- Faucet wallet balance decreases appropriately

### Test 5.4: Automated Faucet Testing

**Objective**: Run comprehensive faucet validation.

```bash
# Run automated faucet test
cd ops/gorgoroth/test-scripts
./test-faucet.sh
```

The automated test covers:
- Service availability
- Status endpoint
- Fund distribution
- Transaction confirmation
- Balance verification
- Rate limiting (optional)

**Success Criteria**:
- All automated tests pass
- No errors in logs
- Faucet operates as expected

See [GORGOROTH_FAUCET_TESTING.md](GORGOROTH_FAUCET_TESTING.md) for detailed faucet testing documentation.

## Automated Testing Scripts

### Automated Compatibility Test Suite

Create an automated test script for continuous validation:

```bash
#!/bin/bash
# File: ops/gorgoroth/test-compatibility.sh

set -e

CONFIG="${1:-fukuii-geth}"
RESULTS_DIR="./test-results-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$RESULTS_DIR"

echo "=== Starting Compatibility Test Suite for $CONFIG ==="
echo "Results will be saved to: $RESULTS_DIR"

# Start the network
fukuii-cli start "$CONFIG"
sleep 60

# Test 1: Network Connectivity
echo "=== Test 1: Network Connectivity ===" | tee "$RESULTS_DIR/01-connectivity.log"
./test-scripts/test-connectivity.sh >> "$RESULTS_DIR/01-connectivity.log" 2>&1

# Test 2: Block Propagation
echo "=== Test 2: Block Propagation ===" | tee "$RESULTS_DIR/02-block-propagation.log"
./test-scripts/test-block-propagation.sh >> "$RESULTS_DIR/02-block-propagation.log" 2>&1

# Test 3: Transaction Propagation
echo "=== Test 3: Transaction Propagation ===" | tee "$RESULTS_DIR/03-tx-propagation.log"
./test-scripts/test-tx-propagation.sh >> "$RESULTS_DIR/03-tx-propagation.log" 2>&1

# Test 4: Mining Compatibility
echo "=== Test 4: Mining Compatibility ===" | tee "$RESULTS_DIR/04-mining.log"
./test-scripts/test-mining.sh >> "$RESULTS_DIR/04-mining.log" 2>&1

# Test 5: Consensus Maintenance
echo "=== Test 5: Consensus Maintenance ===" | tee "$RESULTS_DIR/05-consensus.log"
./test-scripts/test-consensus.sh >> "$RESULTS_DIR/05-consensus.log" 2>&1

# Test 6: Faucet Service (if available)
if [ -f "./test-scripts/test-faucet.sh" ]; then
  echo "=== Test 6: Faucet Service ===" | tee "$RESULTS_DIR/06-faucet.log"
  ./test-scripts/test-faucet.sh >> "$RESULTS_DIR/06-faucet.log" 2>&1
fi

# Generate summary report
echo "=== Generating Summary Report ==="
./test-scripts/generate-report.sh "$RESULTS_DIR" > "$RESULTS_DIR/SUMMARY.md"

echo "=== Test Suite Complete ==="
cat "$RESULTS_DIR/SUMMARY.md"
```

### Individual Test Scripts

These should be created in `ops/gorgoroth/test-scripts/`:

- `test-connectivity.sh` - Network connectivity checks
- `test-block-propagation.sh` - Block propagation validation
- `test-tx-propagation.sh` - Transaction propagation tests
- `test-mining.sh` - Mining compatibility checks
- `test-consensus.sh` - Long-running consensus validation
- `generate-report.sh` - Create markdown summary report

## Results and Compatibility Matrix

### Expected Compatibility Matrix

| Feature | Fukuii ↔ Fukuii | Fukuii ↔ Core-Geth | Fukuii ↔ Besu |
|---------|-----------------|-------------------|---------------|
| **Network Communication** | ✅ Expected | ✅ Expected | ✅ Expected |
| **Peer Discovery** | ✅ Expected | ✅ Expected | ✅ Expected |
| **Block Propagation** | ✅ Expected | ✅ Expected | ✅ Expected |
| **Transaction Propagation** | ✅ Expected | ✅ Expected | ✅ Expected |
| **Mining Consensus** | ✅ Expected | ✅ Expected | ✅ Expected |
| **Fast Sync (as client)** | ✅ Expected | ✅ Expected | ✅ Expected |
| **Fast Sync (as server)** | ✅ Expected | ✅ Expected | ⚠️ To Verify |
| **Snap Sync (as client)** | ⚠️ To Verify | ⚠️ To Verify | ⚠️ To Verify |
| **Snap Sync (as server)** | ⚠️ To Verify | ⚠️ To Verify | ⚠️ To Verify |

### Test Results Template

After running tests, document results in this format:

```markdown
## Test Results - [Date]

**Configuration**: fukuii-geth (3 Fukuii + 3 Core-Geth)
**Test Duration**: 4 hours
**Tester**: [Name]

### Network Communication
- ✅ All nodes connected successfully
- ✅ Protocol compatibility verified (eth/64)
- ✅ Block propagation: < 2 seconds
- ✅ Transaction propagation: < 1 second

### Mining
- ✅ Both clients mining blocks
- ✅ No orphaned blocks
- ✅ Consensus maintained for 4 hours
- ✅ Block distribution: Fukuii 48%, Core-Geth 52%

### Fast Sync
- ✅ Fukuii synced from Core-Geth in 45 seconds
- ✅ Core-Geth synced from Fukuii in 42 seconds
- ✅ State verification passed

### Snap Sync
- ⚠️ Not tested (requires >1000 blocks)

### Issues Found
- None

### Notes
- Network stable throughout testing
- No unexpected errors in logs
```

## Troubleshooting

### Common Issues

#### Issue: Nodes not connecting to each other

**Symptoms**:
- Peer count is 0 or very low
- Logs show connection refused errors

**Solutions**:
1. Check Docker networking: `docker network inspect gorgoroth_gorgoroth`
2. Verify static-nodes.json has correct enode URLs
3. Check firewall settings
4. Ensure all nodes are using the same network ID (1337)

#### Issue: Chain splits or forks

**Symptoms**:
- Different nodes report different latest blocks
- Block hashes don't match at same height

**Solutions**:
1. Check all nodes have the same genesis file
2. Verify network ID is consistent
3. Check for time synchronization issues
4. Review mining difficulty settings

#### Issue: Fast sync fails or stalls

**Symptoms**:
- Sync progress stops
- "Sync failed" errors in logs
- State root mismatch

**Solutions**:
1. Ensure source nodes have snap protocol enabled
2. Check network connectivity
3. Increase timeout values in configuration
4. Verify pivot block is valid

#### Issue: Different clients mining incompatible blocks

**Symptoms**:
- Blocks from one client rejected by others
- High orphan rate
- Consensus not maintained

**Solutions**:
1. Verify all clients use same genesis configuration
2. Check EIP/ECIP activation blocks are aligned
3. Ensure all nodes have same chain configuration
4. Review logs for validation errors

### Debug Commands

```bash
# Check detailed peer information
curl -s -X POST -H "Content-Type: application/json" \
  --data '{"jsonrpc":"2.0","method":"admin_peers","params":[],"id":1}' \
  http://localhost:8545 | jq

# Get node info including enode
curl -s -X POST -H "Content-Type: application/json" \
  --data '{"jsonrpc":"2.0","method":"admin_nodeInfo","params":[],"id":1}' \
  http://localhost:8545 | jq

# Check sync status
curl -s -X POST -H "Content-Type: application/json" \
  --data '{"jsonrpc":"2.0","method":"eth_syncing","params":[],"id":1}' \
  http://localhost:8545 | jq

# Get detailed block information
curl -s -X POST -H "Content-Type: application/json" \
  --data '{"jsonrpc":"2.0","method":"eth_getBlockByNumber","params":["latest",true],"id":1}' \
  http://localhost:8545 | jq
```

### Collecting Diagnostic Information

When reporting issues, collect:

```bash
# Collect all logs
fukuii-cli collect-logs fukuii-geth ./diagnostic-logs

# Get network configuration
docker network inspect gorgoroth_gorgoroth > ./diagnostic-logs/network-config.json

# Get container configurations
for container in $(docker ps --filter "network=gorgoroth_gorgoroth" --format "{{.Names}}"); do
  docker inspect $container > ./diagnostic-logs/$container-config.json
done

# Create issue report
tar -czf gorgoroth-diagnostic-$(date +%Y%m%d-%H%M%S).tar.gz ./diagnostic-logs/
```

## Bonus Trial: Cirith Ungol Real-World Sync Testing

### Overview

For advanced testers ready to validate **real-world sync capabilities**, we provide **Cirith Ungol** - a single-node testing environment for syncing with **ETC mainnet** and **Mordor testnet**.

**Why test with Cirith Ungol?**

| Aspect | Gorgoroth (This Guide) | Cirith Ungol (Bonus Trial) |
|--------|----------------------|----------------------------|
| **Network** | Private test network | Public ETC mainnet/Mordor |
| **Blocks** | Starts from 0 | 20M+ blocks (mainnet) |
| **Peers** | Controlled (3-9 nodes) | Public network peers |
| **Purpose** | Multi-client compatibility | Real-world sync validation |
| **Sync Testing** | Limited history | Full SNAP/Fast sync |
| **Duration** | Minutes to hours | Hours to days |

### What You'll Test

With Cirith Ungol, you can validate:

1. **SNAP Sync** - Against real network with 20M+ blocks
2. **Fast Sync** - Full state sync from production network
3. **Long-term Stability** - 24+ hour continuous operation
4. **Peer Diversity** - Connection to various client implementations
5. **Production Performance** - Real-world resource usage

### Quick Start

```bash
# Navigate to Cirith Ungol
cd ops/cirith-ungol

# Start sync with ETC mainnet
./start.sh start

# Monitor sync progress
./start.sh logs

# Collect logs and metrics
./start.sh collect-logs
```

### Expected Results

**ETC Mainnet SNAP Sync:**
- Duration: 2-6 hours (depending on peers and network)
- Final block: 20M+ blocks
- Disk usage: ~50-80GB
- Peer count: 10-30 peers

**Mordor Testnet:**
- Duration: 1-3 hours
- Final block: 10M+ blocks
- Disk usage: ~20-40GB
- Ideal for faster testing

### Validation Checklist

When testing with Cirith Ungol:

- [ ] Node discovers public peers (10+ peers)
- [ ] SNAP sync initiates successfully
- [ ] Account ranges download completes
- [ ] Storage ranges download completes
- [ ] Trie healing completes
- [ ] Transitions to full sync
- [ ] State is queryable after sync
- [ ] Node remains stable for 24+ hours

### Complete Documentation

For full Cirith Ungol testing instructions, see:

**[Cirith Ungol Testing Guide](CIRITH_UNGOL_TESTING_GUIDE.md)**

This comprehensive guide includes:
- Detailed setup instructions
- Sync mode configuration (SNAP/Fast/Full)
- Monitoring and troubleshooting
- Performance benchmarks
- Integration with fukuii-cli
- Results reporting templates

### When to Use Cirith Ungol

**Use Gorgoroth (this guide) for:**
- Quick validation of multi-client compatibility
- Network communication testing
- Mining functionality
- Protocol compatibility

**Use Cirith Ungol (bonus trial) for:**
- Real-world sync performance
- Long-term stability testing
- Production network compatibility
- Before deploying to mainnet

### Community Testing

More adventurous community members are encouraged to:

1. Complete Gorgoroth compatibility tests first
2. Move to Cirith Ungol for real-world validation
3. Report findings for both test environments
4. Share performance metrics and sync times

## Community Testing

### Getting Started for Community Testers

1. **Clone the repository**:
   ```bash
   git clone https://github.com/chippr-robotics/fukuii.git
   cd fukuii/ops/gorgoroth
   ```

2. **Read the Quick Start guide**:
   ```bash
   cat ops/gorgoroth/QUICKSTART.md
   ```

3. **Start with the simplest test**:
   ```bash
   fukuii-cli start 3nodes
   ```

4. **Report your results**: Open an issue on GitHub with your test results

### What to Test

Community testers should focus on:

1. **Basic Functionality**: Can you get the network running?
2. **Stability**: Does it stay running for extended periods?
3. **Multi-Client**: Try fukuii-geth or fukuii-besu configurations
4. **Performance**: How fast do blocks propagate?
5. **Edge Cases**: Try stopping/starting nodes, network issues, etc.

### Reporting Results

When reporting results, please include:

- OS and Docker version
- Configuration tested (3nodes, fukuii-geth, etc.)
- Duration of test
- Any errors encountered
- Screenshots or log snippets if relevant
- Results of compatibility tests

## Next Steps

After completing compatibility testing:

1. Document all results in the compatibility matrix
2. File issues for any incompatibilities found
3. Update configuration documentation with any required changes
4. Share results with the community
5. Consider running tests on public testnets

## References

- [Gorgoroth README](README.md)
- Gorgoroth Quick Start - see `ops/gorgoroth/QUICKSTART.md` (internal)
- Verification Complete Report - see `ops/gorgoroth/VERIFICATION_COMPLETE.md` (internal)
- [Fukuii Documentation](../README.md)
- [Core-Geth Documentation](https://core-geth.org/)
- [Hyperledger Besu Documentation](https://besu.hyperledger.org/)

## Support

For questions or issues:

- GitHub Issues: https://github.com/chippr-robotics/fukuii/issues
- Check existing documentation in `docs/` and `ops/gorgoroth/`
- Review troubleshooting guide above
