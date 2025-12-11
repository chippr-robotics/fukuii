#!/bin/bash
# Test script for network connectivity validation
# Tests peer connections and protocol compatibility

set -e

echo "=== Network Connectivity Test ==="
echo "Testing peer connections and protocol compatibility..."
echo ""

# Determine which ports to test based on running containers
FUKUII_PORTS=()
GETH_PORTS=()
BESU_PORTS=()

# Detect running Fukuii nodes (HTTP RPC on 8546, 8548, 8550)
for port in 8546 8548 8550; do
  if curl -s -f -m 2 http://localhost:$port > /dev/null 2>&1; then
    FUKUII_PORTS+=($port)
  fi
done

# Detect running Core-Geth nodes
for port in 8551 8553 8555; do
  if curl -s -f -m 2 http://localhost:$port > /dev/null 2>&1; then
    GETH_PORTS+=($port)
  fi
done

# Detect running Besu nodes
for port in 8557 8559 8561; do
  if curl -s -f -m 2 http://localhost:$port > /dev/null 2>&1; then
    BESU_PORTS+=($port)
  fi
done

TOTAL_NODES=$((${#FUKUII_PORTS[@]} + ${#GETH_PORTS[@]} + ${#BESU_PORTS[@]}))
echo "Detected $TOTAL_NODES running nodes:"
echo "  - Fukuii: ${#FUKUII_PORTS[@]} nodes"
echo "  - Core-Geth: ${#GETH_PORTS[@]} nodes"
echo "  - Besu: ${#BESU_PORTS[@]} nodes"
echo ""

# Test 1: Check peer count on all nodes
echo "--- Test 1: Peer Count ---"
PASS_COUNT=0
FAIL_COUNT=0

check_peer_count() {
  local port=$1
  local client=$2
  
  PEER_COUNT=$(curl -s -X POST -H "Content-Type: application/json" \
    --data '{"jsonrpc":"2.0","method":"net_peerCount","params":[],"id":1}' \
    http://localhost:$port 2>/dev/null | jq -r '.result' 2>/dev/null || echo "error")
  
  if [ "$PEER_COUNT" != "error" ]; then
    PEER_COUNT_DEC=$((16#${PEER_COUNT#0x}))
    echo "  $client on port $port: $PEER_COUNT_DEC peers"
    
    if [ $PEER_COUNT_DEC -gt 0 ]; then
      PASS_COUNT=$((PASS_COUNT + 1))
    else
      FAIL_COUNT=$((FAIL_COUNT + 1))
      echo "    ⚠️  WARNING: No peers connected"
    fi
  else
    echo "  $client on port $port: ERROR - could not get peer count"
    FAIL_COUNT=$((FAIL_COUNT + 1))
  fi
}

for port in "${FUKUII_PORTS[@]}"; do
  check_peer_count $port "Fukuii"
done

for port in "${GETH_PORTS[@]}"; do
  check_peer_count $port "Core-Geth"
done

for port in "${BESU_PORTS[@]}"; do
  check_peer_count $port "Besu"
done

echo ""

# Test 2: Check network version consistency
echo "--- Test 2: Network Version ---"
NETWORK_VERSIONS=()

for port in "${FUKUII_PORTS[@]}" "${GETH_PORTS[@]}" "${BESU_PORTS[@]}"; do
  if [ -n "$port" ]; then
    VERSION=$(curl -s -X POST -H "Content-Type: application/json" \
      --data '{"jsonrpc":"2.0","method":"net_version","params":[],"id":1}' \
      http://localhost:$port 2>/dev/null | jq -r '.result' 2>/dev/null || echo "error")
    
    if [ "$VERSION" != "error" ]; then
      NETWORK_VERSIONS+=("$VERSION")
      echo "  Port $port: Network ID $VERSION"
    fi
  fi
done

# Check all network versions are the same
UNIQUE_VERSIONS=$(printf '%s\n' "${NETWORK_VERSIONS[@]}" | sort -u | wc -l)
if [ $UNIQUE_VERSIONS -eq 1 ]; then
  echo "  ✅ All nodes on same network (ID: ${NETWORK_VERSIONS[0]})"
  PASS_COUNT=$((PASS_COUNT + 1))
else
  echo "  ❌ FAIL: Nodes on different networks!"
  FAIL_COUNT=$((FAIL_COUNT + 1))
fi

echo ""

# Test 3: Check protocol version
echo "--- Test 3: Protocol Version ---"

check_protocol() {
  local port=$1
  local client=$2
  
  PROTOCOL=$(curl -s -X POST -H "Content-Type: application/json" \
    --data '{"jsonrpc":"2.0","method":"eth_protocolVersion","params":[],"id":1}' \
    http://localhost:$port 2>/dev/null | jq -r '.result' 2>/dev/null || echo "error")
  
  if [ "$PROTOCOL" != "error" ]; then
    echo "  $client on port $port: Protocol $PROTOCOL"
  else
    echo "  $client on port $port: ERROR - could not get protocol version"
  fi
}

for port in "${FUKUII_PORTS[@]}"; do
  check_protocol $port "Fukuii"
done

for port in "${GETH_PORTS[@]}"; do
  check_protocol $port "Core-Geth"
done

for port in "${BESU_PORTS[@]}"; do
  check_protocol $port "Besu"
done

echo ""

# Summary
echo "=== Connectivity Test Summary ==="
echo "Total checks: $((PASS_COUNT + FAIL_COUNT))"
echo "Passed: $PASS_COUNT"
echo "Failed: $FAIL_COUNT"
echo ""

if [ $FAIL_COUNT -eq 0 ]; then
  echo "✅ All connectivity tests passed"
  exit 0
else
  echo "❌ Some connectivity tests failed"
  exit 1
fi
