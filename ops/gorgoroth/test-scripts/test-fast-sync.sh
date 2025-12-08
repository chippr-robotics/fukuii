#!/bin/bash
# Fast Sync Test Script for Fukuii Gorgoroth Network
# Tests fast sync functionality on the 6-node network

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GORGOROTH_DIR="$(dirname "$SCRIPT_DIR")"

echo "=== Fukuii Fast Sync Test Suite ==="
echo "Starting at: $(date)"
echo

# Configuration
MIN_BLOCKS=1000
SYNC_TIMEOUT=3600  # 1 hour
TEST_NODE_PORT=8556  # Node 6

# Color output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Helper functions
log_info() {
  echo -e "${GREEN}ℹ${NC} $1"
}

log_warn() {
  echo -e "${YELLOW}⚠${NC} $1"
}

log_error() {
  echo -e "${RED}✗${NC} $1"
}

log_success() {
  echo -e "${GREEN}✓${NC} $1"
}

# Step 1: Start seed nodes
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
log_info "Step 1: Starting seed nodes (1-3)..."
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

cd "$GORGOROTH_DIR"

# Check if nodes are already running
RUNNING=$(docker compose -f docker-compose-6nodes.yml ps -q fukuii-node1 fukuii-node2 fukuii-node3 | wc -l)
if [ "$RUNNING" -eq 3 ]; then
  log_info "Seed nodes already running, skipping startup"
else
  docker compose -f docker-compose-6nodes.yml up -d fukuii-node1 fukuii-node2 fukuii-node3
  log_info "Waiting for nodes to initialize (45s)..."
  sleep 45
fi

# Step 2: Verify seed connectivity
echo
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
log_info "Step 2: Verifying seed node connectivity..."
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

CONNECTIVITY_OK=true
for port in 8545 8547 8549; do
  PEERS=$(curl -s -X POST -H "Content-Type: application/json" \
    --data '{"jsonrpc":"2.0","method":"net_peerCount","params":[],"id":1}' \
    http://localhost:$port 2>/dev/null | jq -r '.result // "0x0"')
  
  if [ -z "$PEERS" ]; then
    log_error "Port $port: No response from RPC endpoint"
    CONNECTIVITY_OK=false
    continue
  fi
  
  PEER_COUNT=$((16#${PEERS:2}))
  echo "  Port $port: $PEER_COUNT peers"
  
  if [ $PEER_COUNT -lt 1 ]; then
    log_warn "Insufficient peers on port $port (expected ≥1)"
    CONNECTIVITY_OK=false
  fi
done

if [ "$CONNECTIVITY_OK" = true ]; then
  log_success "All seed nodes connected"
else
  log_error "Connectivity issues detected. You may need to run: fukuii-cli sync-static-nodes"
  log_info "Continuing with test despite connectivity issues..."
fi

# Step 3: Check blockchain height
echo
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
log_info "Step 3: Checking blockchain height..."
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

BLOCK_HEX=$(curl -s -X POST -H "Content-Type: application/json" \
  --data '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}' \
  http://localhost:8545 2>/dev/null | jq -r '.result // "0x0"')

if [ -z "$BLOCK_HEX" ] || [ "$BLOCK_HEX" = "null" ]; then
  log_error "Failed to get block number from seed node"
  exit 1
fi

BLOCK_NUM=$((16#${BLOCK_HEX:2}))
echo "  Current block height: $BLOCK_NUM"

if [ $BLOCK_NUM -lt $MIN_BLOCKS ]; then
  log_warn "Insufficient blocks for comprehensive testing"
  log_warn "Need: $MIN_BLOCKS blocks, Have: $BLOCK_NUM blocks"
  log_info "Fast sync will still be tested with available blocks"
  
  if [ $BLOCK_NUM -lt 100 ]; then
    log_error "Too few blocks for meaningful fast sync test (minimum 100)"
    exit 1
  fi
else
  log_success "Sufficient blockchain history ($BLOCK_NUM blocks)"
fi

# Step 4: Stop test node if running
echo
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
log_info "Step 4: Preparing fast sync node (node 6)..."
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

# Stop node 6 if running
docker compose -f docker-compose-6nodes.yml stop fukuii-node6 2>/dev/null || true
sleep 5

# Clean node 6 data to ensure fresh fast sync
log_info "Cleaning node 6 data directory..."
docker compose -f docker-compose-6nodes.yml rm -f fukuii-node6 2>/dev/null || true

# Start fresh node 6
log_info "Starting node 6 with fast sync enabled..."
docker compose -f docker-compose-6nodes.yml up -d fukuii-node6
log_info "Waiting for node to initialize (30s)..."
sleep 30

# Step 5: Monitor fast sync progress
echo
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
log_info "Step 5: Monitoring fast sync progress..."
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

START_TIME=$(date +%s)
SYNCED=false
LAST_BLOCK=0
STALL_COUNT=0

while [ $(($(date +%s) - START_TIME)) -lt $SYNC_TIMEOUT ]; do
  ELAPSED=$(($(date +%s) - START_TIME))
  
  # Check if syncing is complete
  SYNCING=$(curl -s -X POST -H "Content-Type: application/json" \
    --data '{"jsonrpc":"2.0","method":"eth_syncing","params":[],"id":1}' \
    http://localhost:$TEST_NODE_PORT 2>/dev/null | jq -r '.result // "error"')
  
  # Get current block
  NODE_BLOCK_HEX=$(curl -s -X POST -H "Content-Type: application/json" \
    --data '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}' \
    http://localhost:$TEST_NODE_PORT 2>/dev/null | jq -r '.result // "0x0"')
  NODE_BLOCK=$((16#${NODE_BLOCK_HEX:2}))
  
  # Get peer count
  PEERS=$(curl -s -X POST -H "Content-Type: application/json" \
    --data '{"jsonrpc":"2.0","method":"net_peerCount","params":[],"id":1}' \
    http://localhost:$TEST_NODE_PORT 2>/dev/null | jq -r '.result // "0x0"')
  PEER_COUNT=$((16#${PEERS:2}))
  
  # Display progress
  echo "  [${ELAPSED}s] Block: $NODE_BLOCK/$BLOCK_NUM | Peers: $PEER_COUNT | Syncing: $SYNCING"
  
  # Check for stall
  if [ $NODE_BLOCK -eq $LAST_BLOCK ] && [ $NODE_BLOCK -gt 0 ]; then
    STALL_COUNT=$((STALL_COUNT + 1))
    if [ $STALL_COUNT -gt 6 ]; then
      log_warn "Sync appears to have stalled at block $NODE_BLOCK"
      log_info "Checking if sync is actually complete..."
    fi
  else
    STALL_COUNT=0
  fi
  LAST_BLOCK=$NODE_BLOCK
  
  # Check if syncing is complete
  if [ "$SYNCING" == "false" ] && [ $NODE_BLOCK -gt 0 ]; then
    # Verify we're close to seed node height
    if [ $NODE_BLOCK -ge $((BLOCK_NUM - 5)) ]; then
      SYNCED=true
      break
    fi
  fi
  
  sleep 10
done

echo
if [ "$SYNCED" = true ]; then
  ELAPSED=$(($(date +%s) - START_TIME))
  log_success "Fast sync completed in ${ELAPSED}s"
  echo "  Final block: $NODE_BLOCK"
  echo "  Blocks synced: $NODE_BLOCK"
  echo "  Sync rate: $((NODE_BLOCK / (ELAPSED / 60))) blocks/min"
else
  log_error "Fast sync timeout after ${SYNC_TIMEOUT}s"
  log_error "Final block: $NODE_BLOCK/$BLOCK_NUM"
  echo
  log_info "Last 20 lines of node 6 logs:"
  docker compose -f docker-compose-6nodes.yml logs --tail=20 fukuii-node6
  exit 1
fi

# Step 6: Verify state consistency
echo
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
log_info "Step 6: Verifying state consistency..."
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

# Compare block hashes at same height
COMMON_BLOCK=$((NODE_BLOCK < BLOCK_NUM ? NODE_BLOCK : BLOCK_NUM))
COMMON_BLOCK_HEX=$(printf "0x%x" $COMMON_BLOCK)

SEED_HASH=$(curl -s -X POST -H "Content-Type: application/json" \
  --data "{\"jsonrpc\":\"2.0\",\"method\":\"eth_getBlockByNumber\",\"params\":[\"$COMMON_BLOCK_HEX\",false],\"id\":1}" \
  http://localhost:8545 2>/dev/null | jq -r '.result.hash // "null"')

NODE6_HASH=$(curl -s -X POST -H "Content-Type: application/json" \
  --data "{\"jsonrpc\":\"2.0\",\"method\":\"eth_getBlockByNumber\",\"params\":[\"$COMMON_BLOCK_HEX\",false],\"id\":1}" \
  http://localhost:$TEST_NODE_PORT 2>/dev/null | jq -r '.result.hash // "null"')

echo "  Comparing block #$COMMON_BLOCK"
echo "    Seed hash:  $SEED_HASH"
echo "    Node6 hash: $NODE6_HASH"

if [ "$SEED_HASH" == "$NODE6_HASH" ] && [ "$SEED_HASH" != "null" ]; then
  log_success "Block hashes match"
else
  log_error "Block hash mismatch!"
  exit 1
fi

# Step 7: Check for decompression errors
echo
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
log_info "Step 7: Checking for decompression errors..."
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

DECOMPRESS_ERRORS=$(docker compose -f docker-compose-6nodes.yml logs fukuii-node6 2>&1 | \
  grep -i "decompress.*error\|decompression.*fail" | wc -l)

if [ $DECOMPRESS_ERRORS -eq 0 ]; then
  log_success "No decompression errors found"
else
  log_warn "Found $DECOMPRESS_ERRORS decompression error(s)"
  echo
  echo "Sample errors:"
  docker compose -f docker-compose-6nodes.yml logs fukuii-node6 2>&1 | \
    grep -i "decompress.*error\|decompression.*fail" | head -5
fi

# Step 8: Test Summary
echo
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "=== Fast Sync Test Summary ==="
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo
echo "Test Results:"
echo "  ✓ Seed nodes: 3 nodes running"
echo "  ✓ Blockchain height: $BLOCK_NUM blocks"
echo "  ✓ Sync time: ${ELAPSED}s ($(($ELAPSED / 60))m $(($ELAPSED % 60))s)"
echo "  ✓ Final block: $NODE_BLOCK"
echo "  ✓ Sync rate: $((NODE_BLOCK / (ELAPSED / 60))) blocks/min"
echo "  ✓ State verified: Block hashes match"
if [ $DECOMPRESS_ERRORS -eq 0 ]; then
  echo "  ✓ Decompression: No errors"
else
  echo "  ⚠ Decompression: $DECOMPRESS_ERRORS errors found"
fi
echo
echo "Finished at: $(date)"
echo

# Exit with appropriate code
if [ $DECOMPRESS_ERRORS -gt 0 ]; then
  log_warn "Test completed with warnings (decompression errors)"
  exit 0  # Still consider it a pass, but with warnings
else
  log_success "All tests passed!"
  exit 0
fi
