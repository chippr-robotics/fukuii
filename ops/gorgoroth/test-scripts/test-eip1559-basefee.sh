#!/bin/bash
# Test script for EIP-1559 baseFee validation (Olympia)
# Verifies that post-Olympia blocks contain baseFeePerGas field

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib/test-helpers.sh"
require_tools curl jq

echo "=== EIP-1559 BaseFee Test (Olympia) ==="
echo "Verifying baseFeePerGas is present in block headers..."
echo ""

# Detect running nodes
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

ALL_PORTS=("${FUKUII_PORTS[@]}" "${GETH_PORTS[@]}" "${BESU_PORTS[@]}")
TOTAL_NODES=${#ALL_PORTS[@]}

echo "Detected $TOTAL_NODES running nodes:"
echo "  - Fukuii: ${#FUKUII_PORTS[@]} nodes"
echo "  - Core-Geth: ${#GETH_PORTS[@]} nodes"
echo "  - Besu: ${#BESU_PORTS[@]} nodes"
echo ""

if [ $TOTAL_NODES -eq 0 ]; then
  echo "❌ FAIL: No running nodes detected"
  exit 1
fi

# Use first available port
PRIMARY_PORT=${ALL_PORTS[0]}

# Test 1: Check baseFeePerGas in latest block
echo "--- Test 1: BaseFee in Latest Block ---"

PASS_COUNT=0
FAIL_COUNT=0

for port in "${ALL_PORTS[@]}"; do
  BLOCK=$(curl -s -X POST -H "Content-Type: application/json" \
    --data '{"jsonrpc":"2.0","method":"eth_getBlockByNumber","params":["latest",false],"id":1}' \
    http://localhost:$port 2>/dev/null)

  BLOCK_NUM=$(echo "$BLOCK" | jq -r '.result.number' 2>/dev/null || echo "null")
  BASE_FEE=$(echo "$BLOCK" | jq -r '.result.baseFeePerGas' 2>/dev/null || echo "null")

  if [ "$BASE_FEE" != "null" ] && [ -n "$BASE_FEE" ]; then
    BASE_FEE_DEC=$((16#${BASE_FEE#0x}))
    echo "  Port $port: Block $BLOCK_NUM — baseFeePerGas = $BASE_FEE ($BASE_FEE_DEC wei)"
    PASS_COUNT=$((PASS_COUNT + 1))
  else
    echo "  Port $port: Block $BLOCK_NUM — ❌ baseFeePerGas NOT FOUND"
    FAIL_COUNT=$((FAIL_COUNT + 1))
  fi
done

echo ""

# Test 2: Verify baseFee consistency across nodes
echo "--- Test 2: BaseFee Consistency Across Nodes ---"

BASE_FEES=()
for port in "${ALL_PORTS[@]}"; do
  BASE_FEE=$(curl -s -X POST -H "Content-Type: application/json" \
    --data '{"jsonrpc":"2.0","method":"eth_getBlockByNumber","params":["latest",false],"id":1}' \
    http://localhost:$port 2>/dev/null | jq -r '.result.baseFeePerGas' 2>/dev/null || echo "null")
  BASE_FEES+=("$BASE_FEE")
done

UNIQUE_FEES=$(printf '%s\n' "${BASE_FEES[@]}" | sort -u | wc -l)

if [ $UNIQUE_FEES -eq 1 ] && [ "${BASE_FEES[0]}" != "null" ]; then
  echo "  ✅ All nodes report same baseFeePerGas: ${BASE_FEES[0]}"
  PASS_COUNT=$((PASS_COUNT + 1))
elif [ $UNIQUE_FEES -eq 1 ] && [ "${BASE_FEES[0]}" = "null" ]; then
  echo "  ❌ No nodes report baseFeePerGas — Olympia fork may not be active"
  FAIL_COUNT=$((FAIL_COUNT + 1))
else
  echo "  ❌ BaseFee mismatch across nodes!"
  for i in "${!ALL_PORTS[@]}"; do
    echo "    Port ${ALL_PORTS[$i]}: ${BASE_FEES[$i]}"
  done
  FAIL_COUNT=$((FAIL_COUNT + 1))
fi

echo ""

# Test 3: Verify baseFee evolves over blocks
echo "--- Test 3: BaseFee Evolution (last 10 blocks) ---"

CURRENT_BLOCK=$(curl -s -X POST -H "Content-Type: application/json" \
  --data '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}' \
  http://localhost:$PRIMARY_PORT | jq -r '.result')

CURRENT_BLOCK_DEC=$((16#${CURRENT_BLOCK#0x}))

START_BLOCK=$((CURRENT_BLOCK_DEC - 9))
if [ $START_BLOCK -lt 1 ]; then
  START_BLOCK=1
fi

BASEFEE_PRESENT=0
BASEFEE_ABSENT=0

for ((i=START_BLOCK; i<=CURRENT_BLOCK_DEC; i++)); do
  BLOCK_HEX=$(printf "0x%x" $i)

  BLOCK_DATA=$(curl -s -X POST -H "Content-Type: application/json" \
    --data "{\"jsonrpc\":\"2.0\",\"method\":\"eth_getBlockByNumber\",\"params\":[\"$BLOCK_HEX\",false],\"id\":1}" \
    http://localhost:$PRIMARY_PORT 2>/dev/null)

  BASE_FEE=$(echo "$BLOCK_DATA" | jq -r '.result.baseFeePerGas' 2>/dev/null || echo "null")
  GAS_USED=$(echo "$BLOCK_DATA" | jq -r '.result.gasUsed' 2>/dev/null || echo "0x0")

  if [ "$BASE_FEE" != "null" ] && [ -n "$BASE_FEE" ]; then
    BASE_FEE_DEC=$((16#${BASE_FEE#0x}))
    GAS_USED_DEC=$((16#${GAS_USED#0x}))
    echo "  Block #$i: baseFee=$BASE_FEE_DEC wei, gasUsed=$GAS_USED_DEC"
    BASEFEE_PRESENT=$((BASEFEE_PRESENT + 1))
  else
    echo "  Block #$i: (pre-Olympia, no baseFee)"
    BASEFEE_ABSENT=$((BASEFEE_ABSENT + 1))
  fi
done

echo ""
echo "  Blocks with baseFee: $BASEFEE_PRESENT"
echo "  Blocks without baseFee: $BASEFEE_ABSENT"

if [ $BASEFEE_PRESENT -gt 0 ]; then
  echo "  ✅ EIP-1559 baseFee is active"
  PASS_COUNT=$((PASS_COUNT + 1))
else
  echo "  ❌ No blocks with baseFee found — Olympia fork may not be active yet"
  FAIL_COUNT=$((FAIL_COUNT + 1))
fi

echo ""

# Summary
echo "=== EIP-1559 BaseFee Test Summary ==="
echo "Total checks: $((PASS_COUNT + FAIL_COUNT))"
echo "Passed: $PASS_COUNT"
echo "Failed: $FAIL_COUNT"
echo ""

if [ $FAIL_COUNT -eq 0 ]; then
  echo "✅ EIP-1559 baseFee test passed"
  exit 0
else
  echo "❌ EIP-1559 baseFee test failed"
  exit 1
fi
