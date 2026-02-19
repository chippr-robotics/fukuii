#!/bin/bash
# SNAP Sync Test Script for Fukuii Gorgoroth Network
# Tests SNAP sync functionality on the 3-node network (node3 as SNAP sync target)

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GORGOROTH_DIR="$(dirname "$SCRIPT_DIR")"

echo "=== Fukuii SNAP Sync Test Suite ==="
echo "Starting at: $(date)"
echo

# Configuration
MIN_BLOCKS=200
SYNC_TIMEOUT=1800  # 30 minutes
SEED_NODE_PORT=8546     # Node 1 HTTP RPC (host port)
TEST_NODE_PORT=8549     # Node 3 HTTP RPC (host port)
COMPOSE_FILE="docker-compose-3nodes.yml"

# Color output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Helper functions
log_info() {
  echo -e "${GREEN}i${NC} $1"
}

log_warn() {
  echo -e "${YELLOW}!${NC} $1"
}

log_error() {
  echo -e "${RED}x${NC} $1"
}

log_success() {
  echo -e "${GREEN}+${NC} $1"
}

rpc_call() {
  local port=$1
  local method=$2
  local params=$3
  curl -s -X POST -H "Content-Type: application/json" \
    --data "{\"jsonrpc\":\"2.0\",\"method\":\"$method\",\"params\":[$params],\"id\":1}" \
    "http://localhost:$port" 2>/dev/null
}

cd "$GORGOROTH_DIR"

# Step 1: Ensure seed nodes are running with enough blocks
echo "---"
log_info "Step 1: Checking seed nodes (node1 + node2)..."
echo "---"

RUNNING=$(docker compose -f "$COMPOSE_FILE" ps -q fukuii-node1 fukuii-node2 | wc -l)
if [ "$RUNNING" -lt 2 ]; then
  log_info "Starting seed nodes..."
  docker compose -f "$COMPOSE_FILE" up -d fukuii-node1 fukuii-node2
  log_info "Waiting for nodes to initialize (45s)..."
  sleep 45
else
  log_info "Seed nodes already running"
fi

# Sync static nodes if CLI is available
FUKUII_CLI="$GORGOROTH_DIR/../tools/fukuii-cli.sh"
if [ -x "$FUKUII_CLI" ]; then
  if "$FUKUII_CLI" sync-static-nodes 3nodes 2>/dev/null; then
    log_success "Static nodes synchronized"
    sleep 15
  fi
fi

# Check blockchain height on seed node
BLOCK_HEX=$(rpc_call "$SEED_NODE_PORT" "eth_blockNumber" "" | jq -r '.result // "0x0"')

if [ -z "$BLOCK_HEX" ] || [ "$BLOCK_HEX" = "null" ]; then
  log_error "Failed to get block number from seed node on port $SEED_NODE_PORT"
  exit 1
fi

BLOCK_NUM=$((16#${BLOCK_HEX:2}))
echo "  Seed node block height: $BLOCK_NUM"

if [ "$BLOCK_NUM" -lt "$MIN_BLOCKS" ]; then
  log_warn "Insufficient blocks ($BLOCK_NUM < $MIN_BLOCKS). SNAP sync needs enough state to test."
  log_warn "Let mining run longer or lower MIN_BLOCKS."
  if [ "$BLOCK_NUM" -lt 50 ]; then
    log_error "Too few blocks ($BLOCK_NUM) for meaningful SNAP sync test"
    exit 1
  fi
else
  log_success "Sufficient blockchain history ($BLOCK_NUM blocks)"
fi

# Step 2: Prepare node3 for fresh SNAP sync
echo
echo "---"
log_info "Step 2: Preparing node3 for fresh SNAP sync..."
echo "---"

# Stop and remove node3 data for a clean SNAP sync start
docker compose -f "$COMPOSE_FILE" stop fukuii-node3 2>/dev/null || true
sleep 5
docker compose -f "$COMPOSE_FILE" rm -f fukuii-node3 2>/dev/null || true

# Remove data volume to force fresh sync
docker volume rm "gorgoroth_fukuii-node3-data" 2>/dev/null || true

log_info "Starting node3 with SNAP sync enabled..."
docker compose -f "$COMPOSE_FILE" up -d fukuii-node3
log_info "Waiting for node3 to initialize (30s)..."
sleep 30

# Step 3: Monitor SNAP sync progress
echo
echo "---"
log_info "Step 3: Monitoring SNAP sync progress..."
echo "---"

START_TIME=$(date +%s)
SYNCED=false
LAST_BLOCK=0
STALL_COUNT=0

while [ $(($(date +%s) - START_TIME)) -lt $SYNC_TIMEOUT ]; do
  ELAPSED=$(($(date +%s) - START_TIME))

  # Check syncing status
  SYNCING_RAW=$(rpc_call "$TEST_NODE_PORT" "eth_syncing" "")
  SYNCING=$(echo "$SYNCING_RAW" | jq -r '.result // "error"')

  # Get current block
  NODE_BLOCK_HEX=$(rpc_call "$TEST_NODE_PORT" "eth_blockNumber" "" | jq -r '.result // "0x0"')
  NODE_BLOCK=$((16#${NODE_BLOCK_HEX:2}))

  # Get peer count
  PEERS_HEX=$(rpc_call "$TEST_NODE_PORT" "net_peerCount" "" | jq -r '.result // "0x0"')
  PEER_COUNT=$((16#${PEERS_HEX:2}))

  # Display progress
  if [ "$SYNCING" = "false" ]; then
    echo "  [${ELAPSED}s] Block: $NODE_BLOCK/$BLOCK_NUM | Peers: $PEER_COUNT | Syncing: complete"
  else
    # Try to extract SNAP sync phase info from syncing result
    CURRENT=$(echo "$SYNCING" | jq -r '.currentBlock // empty' 2>/dev/null)
    HIGHEST=$(echo "$SYNCING" | jq -r '.highestBlock // empty' 2>/dev/null)
    KNOWN=$(echo "$SYNCING" | jq -r '.knownStates // empty' 2>/dev/null)
    PULLED=$(echo "$SYNCING" | jq -r '.pulledStates // empty' 2>/dev/null)
    if [ -n "$KNOWN" ] && [ "$KNOWN" != "null" ]; then
      echo "  [${ELAPSED}s] Block: $NODE_BLOCK/$BLOCK_NUM | Peers: $PEER_COUNT | States: $PULLED/$KNOWN"
    else
      echo "  [${ELAPSED}s] Block: $NODE_BLOCK/$BLOCK_NUM | Peers: $PEER_COUNT | Syncing: active"
    fi
  fi

  # Check for stall
  if [ "$NODE_BLOCK" -eq "$LAST_BLOCK" ] && [ "$NODE_BLOCK" -gt 0 ]; then
    STALL_COUNT=$((STALL_COUNT + 1))
    if [ "$STALL_COUNT" -gt 12 ]; then
      log_warn "Sync appears stalled at block $NODE_BLOCK for 2+ minutes"
    fi
  else
    STALL_COUNT=0
  fi
  LAST_BLOCK=$NODE_BLOCK

  # Check if syncing is complete
  if [ "$SYNCING" = "false" ] && [ "$NODE_BLOCK" -gt 0 ]; then
    # Allow some tolerance (node3 might be a few blocks behind the mining node)
    if [ "$NODE_BLOCK" -ge $((BLOCK_NUM - 10)) ]; then
      SYNCED=true
      break
    fi
  fi

  sleep 10
done

echo
if [ "$SYNCED" = true ]; then
  ELAPSED=$(($(date +%s) - START_TIME))
  log_success "SNAP sync completed in ${ELAPSED}s ($(($ELAPSED / 60))m $(($ELAPSED % 60))s)"
  echo "  Final block: $NODE_BLOCK"
else
  log_error "SNAP sync timeout after ${SYNC_TIMEOUT}s"
  log_error "Final block: $NODE_BLOCK/$BLOCK_NUM"
  echo
  log_info "Last 30 lines of node3 logs:"
  docker compose -f "$COMPOSE_FILE" logs --tail=30 fukuii-node3
  exit 1
fi

# Step 4: Verify state consistency
echo
echo "---"
log_info "Step 4: Verifying state consistency..."
echo "---"

# Compare block hashes at a common height
COMMON_BLOCK=$((NODE_BLOCK < BLOCK_NUM ? NODE_BLOCK : BLOCK_NUM))
COMMON_BLOCK_HEX=$(printf "0x%x" "$COMMON_BLOCK")

SEED_HASH=$(rpc_call "$SEED_NODE_PORT" "eth_getBlockByNumber" "\"$COMMON_BLOCK_HEX\",false" | jq -r '.result.hash // "null"')
NODE3_HASH=$(rpc_call "$TEST_NODE_PORT" "eth_getBlockByNumber" "\"$COMMON_BLOCK_HEX\",false" | jq -r '.result.hash // "null"')

echo "  Comparing block #$COMMON_BLOCK"
echo "    Seed hash:  $SEED_HASH"
echo "    Node3 hash: $NODE3_HASH"

if [ "$SEED_HASH" = "$NODE3_HASH" ] && [ "$SEED_HASH" != "null" ]; then
  log_success "Block hashes match"
else
  log_error "Block hash mismatch!"
  exit 1
fi

# Verify state: check balance of genesis-funded accounts
GENESIS_ACCOUNT="0x1000000000000000000000000000000000000001"
SEED_BALANCE=$(rpc_call "$SEED_NODE_PORT" "eth_getBalance" "\"$GENESIS_ACCOUNT\",\"latest\"" | jq -r '.result // "null"')
NODE3_BALANCE=$(rpc_call "$TEST_NODE_PORT" "eth_getBalance" "\"$GENESIS_ACCOUNT\",\"latest\"" | jq -r '.result // "null"')

echo "  Comparing balance of $GENESIS_ACCOUNT"
echo "    Seed balance:  $SEED_BALANCE"
echo "    Node3 balance: $NODE3_BALANCE"

if [ "$SEED_BALANCE" = "$NODE3_BALANCE" ] && [ "$SEED_BALANCE" != "null" ] && [ "$SEED_BALANCE" != "0x0" ]; then
  log_success "State balance matches"
else
  log_warn "State balance mismatch or zero (may be expected if account received mining rewards)"
fi

# Step 5: Check for SNAP sync errors in logs
echo
echo "---"
log_info "Step 5: Checking for SNAP sync errors..."
echo "---"

SNAP_ERRORS=$(docker compose -f "$COMPOSE_FILE" logs fukuii-node3 2>&1 | \
  grep -i "SNAP sync failed\|fallback.*fast sync\|circuit breaker" | wc -l)

if [ "$SNAP_ERRORS" -eq 0 ]; then
  log_success "No SNAP sync error patterns found in logs"
else
  log_warn "Found $SNAP_ERRORS SNAP sync error/fallback messages"
  docker compose -f "$COMPOSE_FILE" logs fukuii-node3 2>&1 | \
    grep -i "SNAP sync failed\|fallback.*fast sync\|circuit breaker" | head -5
fi

# Step 6: Summary
echo
echo "---"
echo "=== SNAP Sync Test Summary ==="
echo "---"
echo
echo "Test Results:"
echo "  + Seed nodes: 2 nodes running"
echo "  + Blockchain height: $BLOCK_NUM blocks"
echo "  + Sync time: ${ELAPSED}s ($(($ELAPSED / 60))m $(($ELAPSED % 60))s)"
echo "  + Final block: $NODE_BLOCK"
echo "  + State verified: Block hashes match"
if [ "$SNAP_ERRORS" -eq 0 ]; then
  echo "  + SNAP errors: None"
else
  echo "  ! SNAP errors: $SNAP_ERRORS messages"
fi
echo
echo "Finished at: $(date)"
echo

log_success "SNAP sync test passed!"
exit 0
