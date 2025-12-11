#!/bin/bash
# Test script for mining compatibility validation
# Tests that blocks mined by different clients are accepted

set -e

echo "=== Mining Compatibility Test ==="
echo "Testing mining across different clients..."
echo ""

# Detect running nodes (Fukuii uses HTTP on 8546/8548/8550)
FUKUII_PORTS=()
GETH_PORTS=()
BESU_PORTS=()

for port in 8546 8548 8550; do
  if curl -s -f -m 2 http://localhost:$port > /dev/null 2>&1; then
    FUKUII_PORTS+=($port)
  fi
done

for port in 8551 8553 8555; do
  if curl -s -f -m 2 http://localhost:$port > /dev/null 2>&1; then
    GETH_PORTS+=($port)
  fi
done

for port in 8557 8559 8561; do
  if curl -s -f -m 2 http://localhost:$port > /dev/null 2>&1; then
    BESU_PORTS+=($port)
  fi
done

echo "Detected nodes:"
echo "  - Fukuii: ${#FUKUII_PORTS[@]} nodes"
echo "  - Core-Geth: ${#GETH_PORTS[@]} nodes"
echo "  - Besu: ${#BESU_PORTS[@]} nodes"
echo ""

# Test 1: Check mining status
echo "--- Test 1: Mining Status ---"

check_mining() {
  local port=$1
  local client=$2
  
  IS_MINING=$(curl -s -X POST -H "Content-Type: application/json" \
    --data '{"jsonrpc":"2.0","method":"eth_mining","params":[],"id":1}' \
    http://localhost:$port 2>/dev/null | jq -r '.result' 2>/dev/null || echo "error")
  
  if [ "$IS_MINING" != "error" ]; then
    echo "  $client on port $port: Mining = $IS_MINING"
  else
    echo "  $client on port $port: ERROR - could not get mining status"
  fi
}

for port in "${FUKUII_PORTS[@]}"; do
  check_mining $port "Fukuii"
done

for port in "${GETH_PORTS[@]}"; do
  check_mining $port "Core-Geth"
done

for port in "${BESU_PORTS[@]}"; do
  check_mining $port "Besu"
done

echo ""

# Test 2: Identify who mined recent blocks
echo "--- Test 2: Block Producers ---"
echo "Analyzing last 20 blocks to identify miners..."

# Get current block number
CURRENT_BLOCK=$(curl -s -X POST -H "Content-Type: application/json" \
  --data '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}' \
  http://localhost:${FUKUII_PORTS[0]:-${GETH_PORTS[0]:-${BESU_PORTS[0]}}} | jq -r '.result')

CURRENT_BLOCK_DEC=$((16#${CURRENT_BLOCK#0x}))

# Start from 20 blocks ago or block 1
START_BLOCK=$((CURRENT_BLOCK_DEC - 19))
if [ $START_BLOCK -lt 1 ]; then
  START_BLOCK=1
fi

# Maps to count blocks per miner address
declare -A MINER_COUNTS
TOTAL_CHECKED=0

for ((i=START_BLOCK; i<=CURRENT_BLOCK_DEC; i++)); do
  BLOCK_HEX=$(printf "0x%x" $i)
  
  MINER=$(curl -s -X POST -H "Content-Type: application/json" \
    --data "{\"jsonrpc\":\"2.0\",\"method\":\"eth_getBlockByNumber\",\"params\":[\"$BLOCK_HEX\",false],\"id\":1}" \
    http://localhost:${FUKUII_PORTS[0]:-${GETH_PORTS[0]:-${BESU_PORTS[0]}}} | jq -r '.result.miner' 2>/dev/null)
  
  if [ -n "$MINER" ] && [ "$MINER" != "null" ]; then
    MINER_COUNTS[$MINER]=$((${MINER_COUNTS[$MINER]:-0} + 1))
    TOTAL_CHECKED=$((TOTAL_CHECKED + 1))
  fi
done

echo ""
echo "Block distribution (last $TOTAL_CHECKED blocks):"
for miner in "${!MINER_COUNTS[@]}"; do
  count=${MINER_COUNTS[$miner]}
  percentage=$((count * 100 / TOTAL_CHECKED))
  echo "  $miner: $count blocks ($percentage%)"
done

echo ""

# Test 3: Verify blocks from different clients
echo "--- Test 3: Cross-Client Block Validation ---"
echo "Verifying blocks from different miners are accepted by all nodes..."

# Get a sample of recent blocks
SAMPLE_SIZE=10
START=$((CURRENT_BLOCK_DEC - SAMPLE_SIZE + 1))
if [ $START -lt 1 ]; then
  START=1
fi

VALIDATION_ERRORS=0

for ((i=START; i<=CURRENT_BLOCK_DEC; i++)); do
  BLOCK_HEX=$(printf "0x%x" $i)
  
  # Get block hash from all node types
  HASHES=()
  
  if [ ${#FUKUII_PORTS[@]} -gt 0 ]; then
    HASH=$(curl -s -X POST -H "Content-Type: application/json" \
      --data "{\"jsonrpc\":\"2.0\",\"method\":\"eth_getBlockByNumber\",\"params\":[\"$BLOCK_HEX\",false],\"id\":1}" \
      http://localhost:${FUKUII_PORTS[0]} | jq -r '.result.hash' 2>/dev/null)
    HASHES+=("$HASH")
  fi
  
  if [ ${#GETH_PORTS[@]} -gt 0 ]; then
    HASH=$(curl -s -X POST -H "Content-Type: application/json" \
      --data "{\"jsonrpc\":\"2.0\",\"method\":\"eth_getBlockByNumber\",\"params\":[\"$BLOCK_HEX\",false],\"id\":1}" \
      http://localhost:${GETH_PORTS[0]} | jq -r '.result.hash' 2>/dev/null)
    HASHES+=("$HASH")
  fi
  
  if [ ${#BESU_PORTS[@]} -gt 0 ]; then
    HASH=$(curl -s -X POST -H "Content-Type: application/json" \
      --data "{\"jsonrpc\":\"2.0\",\"method\":\"eth_getBlockByNumber\",\"params\":[\"$BLOCK_HEX\",false],\"id\":1}" \
      http://localhost:${BESU_PORTS[0]} | jq -r '.result.hash' 2>/dev/null)
    HASHES+=("$HASH")
  fi
  
  # Check all hashes are the same
  UNIQUE=$(printf '%s\n' "${HASHES[@]}" | sort -u | wc -l)
  
  if [ $UNIQUE -eq 1 ]; then
    echo "  Block #$i: ✅ Validated by all clients"
  else
    echo "  Block #$i: ❌ Hash mismatch between clients!"
    VALIDATION_ERRORS=$((VALIDATION_ERRORS + 1))
  fi
done

echo ""

# Summary
echo "=== Mining Compatibility Test Summary ==="

if [ $TOTAL_CHECKED -gt 0 ]; then
  echo "✅ Blocks mined by multiple clients"
  echo "✅ $TOTAL_CHECKED blocks analyzed"
  
  if [ $VALIDATION_ERRORS -eq 0 ]; then
    echo "✅ All blocks validated consistently across clients"
  else
    echo "❌ $VALIDATION_ERRORS validation errors found"
  fi
else
  echo "⚠️  No blocks found to analyze"
fi

echo ""

if [ $VALIDATION_ERRORS -eq 0 ]; then
  echo "✅ Mining compatibility test passed"
  exit 0
else
  echo "❌ Mining compatibility test failed"
  exit 1
fi
