#!/bin/bash

# Gorgoroth Network Verification Script
# Tests that node key persistence fix is working correctly

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NETWORK_TYPE="${1:-3nodes}"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo "============================================================"
echo "Gorgoroth Network Verification - Node Key Persistence Test"
echo "============================================================"
echo ""
echo "Network: $NETWORK_TYPE"
echo ""

# Determine number of nodes
case "$NETWORK_TYPE" in
  "3nodes")
    NUM_NODES=3
    ;;
  "6nodes")
    NUM_NODES=6
    ;;
  *)
    echo -e "${RED}✗ Unknown network type: $NETWORK_TYPE${NC}"
    echo "Usage: $0 [3nodes|6nodes]"
    exit 1
    ;;
esac

# Function to check if container is running
check_container() {
  local container_name=$1
  if docker ps --format '{{.Names}}' | grep -q "^${container_name}$"; then
    return 0
  else
    return 1
  fi
}

# Function to get peer count
get_peer_count() {
  local port=$1
  local response=$(curl -s --max-time 5 -X POST -H "Content-Type: application/json" \
    --data '{"jsonrpc":"2.0","method":"net_peerCount","params":[],"id":1}' \
    http://localhost:$port 2>/dev/null || echo '{"result":"0x0"}')
  
  # Extract hex value and convert to decimal (case-insensitive)
  local hex_count=$(echo "$response" | grep -o '"result":"0x[0-9A-Fa-f]*"' | cut -d'"' -f4)
  if [ -n "$hex_count" ]; then
    printf "%d" "$hex_count"
  else
    echo "0"
  fi
}

# Function to get enode from logs
get_enode_from_logs() {
  local container_name=$1
  docker logs "$container_name" 2>&1 | grep "Node address: enode://" | tail -1 | sed 's/.*Node address: //' || echo "NOT_FOUND"
}

# Function to get enode from node.key file
get_enode_from_key() {
  local node_num=$1
  local node_key_file="$SCRIPT_DIR/conf/node$node_num/node.key"
  
  if [ ! -f "$node_key_file" ]; then
    echo "FILE_NOT_FOUND"
    return
  fi
  
  # Read public key (line 2)
  local public_key=$(sed -n '2p' "$node_key_file")
  echo "enode://${public_key}@fukuii-node${node_num}:30303"
}

echo "Step 1: Checking if containers are running..."
echo "--------------------------------------------------------------"

ALL_RUNNING=true
for i in $(seq 1 $NUM_NODES); do
  container_name="gorgoroth-fukuii-node$i"
  if check_container "$container_name"; then
    echo -e "${GREEN}✓${NC} $container_name is running"
  else
    echo -e "${RED}✗${NC} $container_name is NOT running"
    ALL_RUNNING=false
  fi
done

if [ "$ALL_RUNNING" = false ]; then
  echo ""
  echo -e "${RED}ERROR: Not all containers are running${NC}"
  echo "Please start the network first:"
  echo "  cd ../tools && ./fukuii-cli.sh start $NETWORK_TYPE"
  exit 1
fi

echo ""
echo "Step 2: Waiting for nodes to initialize (30 seconds)..."
echo "--------------------------------------------------------------"
sleep 30

echo ""
echo "Step 3: Verifying node key persistence..."
echo "--------------------------------------------------------------"

ALL_KEYS_MATCH=true
for i in $(seq 1 $NUM_NODES); do
  container_name="gorgoroth-fukuii-node$i"
  
  # Get enode from node.key file
  expected_enode=$(get_enode_from_key "$i")
  
  # Get enode from container logs
  actual_enode=$(get_enode_from_logs "$container_name")
  
  # Extract just the public key part for comparison (without IP/port)
  expected_pubkey=$(echo "$expected_enode" | grep -o 'enode://[^@]*' | sed 's/enode:\/\///')
  actual_pubkey=$(echo "$actual_enode" | grep -o 'enode://[^@]*' | sed 's/enode:\/\///')
  
  if [ "$expected_pubkey" = "$actual_pubkey" ]; then
    echo -e "${GREEN}✓${NC} node$i: Using persistent key from node.key file"
  else
    echo -e "${RED}✗${NC} node$i: Key mismatch!"
    echo "    Expected: enode://$expected_pubkey"
    echo "    Actual:   enode://$actual_pubkey"
    ALL_KEYS_MATCH=false
  fi
done

if [ "$ALL_KEYS_MATCH" = false ]; then
  echo ""
  echo -e "${RED}ERROR: Node keys do not match!${NC}"
  echo "The node.key files may not be mounted correctly."
  exit 1
fi

echo ""
echo "Step 4: Checking peer connections..."
echo "--------------------------------------------------------------"

# Expected peer count (each node should connect to all others)
EXPECTED_PEERS=$((NUM_NODES - 1))

ALL_CONNECTED=true
for i in $(seq 1 $NUM_NODES); do
  # Calculate RPC port (8545 for node1, 8547 for node2, etc.)
  port=$((8545 + (i - 1) * 2))
  
  peer_count=$(get_peer_count $port)
  
  if [ "$peer_count" -eq "$EXPECTED_PEERS" ]; then
    echo -e "${GREEN}✓${NC} node$i (port $port): $peer_count peer(s) connected"
  else
    echo -e "${YELLOW}⚠${NC} node$i (port $port): $peer_count peer(s) connected (expected $EXPECTED_PEERS)"
    ALL_CONNECTED=false
  fi
done

echo ""
echo "============================================================"
if [ "$ALL_KEYS_MATCH" = true ] && [ "$ALL_CONNECTED" = true ]; then
  echo -e "${GREEN}SUCCESS: Node key persistence is working correctly!${NC}"
  echo "  ✓ All nodes are using persistent keys from node.key files"
  echo "  ✓ All nodes have established peer connections"
  echo "  ✓ Network is ready for testing"
elif [ "$ALL_KEYS_MATCH" = true ]; then
  echo -e "${YELLOW}PARTIAL SUCCESS: Keys are persistent but not all peers connected${NC}"
  echo "  ✓ All nodes are using persistent keys from node.key files"
  echo "  ⚠ Some nodes have not established all peer connections yet"
  echo "  ℹ This may be normal - try waiting longer or check logs"
else
  echo -e "${RED}FAILURE: Node key persistence is not working${NC}"
  echo "  ✗ Node keys are not being loaded from node.key files"
  echo "  ℹ Check docker-compose volume mounts"
fi
echo "============================================================"
echo ""

# Exit with appropriate code
if [ "$ALL_KEYS_MATCH" = true ] && [ "$ALL_CONNECTED" = true ]; then
  exit 0
elif [ "$ALL_KEYS_MATCH" = true ]; then
  exit 2  # Partial success
else
  exit 1  # Failure
fi
