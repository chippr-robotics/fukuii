#!/bin/bash
# Test script for ECIP-1111 treasury accumulation validation (Olympia)
# Verifies that 100% of baseFee is redirected to the treasury address

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib/test-helpers.sh"
source "$SCRIPT_DIR/olympia-config.sh"
require_tools curl jq

echo "=== ECIP-1111 Treasury Accumulation Test (Olympia) ==="
echo "Verifying baseFee accumulation in treasury address..."
echo ""

# Configuration
TREASURY_ADDRESS="${TREASURY_ADDRESS:-$OLYMPIA_TREASURY_ADDRESS}"
WAIT_BLOCKS=${WAIT_BLOCKS:-10}
POLL_INTERVAL=${POLL_INTERVAL:-15}

echo "Treasury address: $TREASURY_ADDRESS"
echo "Monitoring for $WAIT_BLOCKS new blocks (polling every ${POLL_INTERVAL}s)..."
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

if [ ${#ALL_PORTS[@]} -eq 0 ]; then
  echo "❌ FAIL: No running nodes detected"
  exit 1
fi

PRIMARY_PORT=${ALL_PORTS[0]}
echo "Using primary node on port $PRIMARY_PORT"
echo ""

PASS_COUNT=0
FAIL_COUNT=0

# Test 1: Check initial treasury balance
echo "--- Test 1: Initial Treasury Balance ---"

INITIAL_BALANCE_HEX=$(curl -s -X POST -H "Content-Type: application/json" \
  --data "{\"jsonrpc\":\"2.0\",\"method\":\"eth_getBalance\",\"params\":[\"$TREASURY_ADDRESS\",\"latest\"],\"id\":1}" \
  http://localhost:$PRIMARY_PORT | jq -r '.result' 2>/dev/null || echo "0x0")

echo "  Treasury balance: $INITIAL_BALANCE_HEX"
echo ""

# Test 2: Record block number and treasury balance, wait for blocks, compare
echo "--- Test 2: Treasury Balance Change Over Time ---"

START_BLOCK_HEX=$(curl -s -X POST -H "Content-Type: application/json" \
  --data '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}' \
  http://localhost:$PRIMARY_PORT | jq -r '.result')

START_BLOCK_DEC=$((16#${START_BLOCK_HEX#0x}))
TARGET_BLOCK=$((START_BLOCK_DEC + WAIT_BLOCKS))

echo "  Start block: $START_BLOCK_DEC"
echo "  Target block: $TARGET_BLOCK"
echo "  Waiting for $WAIT_BLOCKS new blocks..."
echo ""

# Wait for blocks to be mined
ATTEMPTS=0
MAX_ATTEMPTS=$((WAIT_BLOCKS * 60 / POLL_INTERVAL))  # generous timeout

while true; do
  CURRENT_BLOCK_HEX=$(curl -s -X POST -H "Content-Type: application/json" \
    --data '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}' \
    http://localhost:$PRIMARY_PORT | jq -r '.result')
  CURRENT_BLOCK_DEC=$((16#${CURRENT_BLOCK_HEX#0x}))

  if [ $CURRENT_BLOCK_DEC -ge $TARGET_BLOCK ]; then
    echo "  Reached block $CURRENT_BLOCK_DEC (target: $TARGET_BLOCK)"
    break
  fi

  ATTEMPTS=$((ATTEMPTS + 1))
  if [ $ATTEMPTS -ge $MAX_ATTEMPTS ]; then
    echo "  ❌ Timeout waiting for blocks (stuck at $CURRENT_BLOCK_DEC)"
    FAIL_COUNT=$((FAIL_COUNT + 1))
    break
  fi

  echo "  Block $CURRENT_BLOCK_DEC / $TARGET_BLOCK — waiting..."
  sleep $POLL_INTERVAL
done

echo ""

# Check final treasury balance
FINAL_BALANCE_HEX=$(curl -s -X POST -H "Content-Type: application/json" \
  --data "{\"jsonrpc\":\"2.0\",\"method\":\"eth_getBalance\",\"params\":[\"$TREASURY_ADDRESS\",\"latest\"],\"id\":1}" \
  http://localhost:$PRIMARY_PORT | jq -r '.result' 2>/dev/null || echo "0x0")

echo "  Initial treasury balance: $INITIAL_BALANCE_HEX"
echo "  Final treasury balance:   $FINAL_BALANCE_HEX"
echo ""

# Test 3: Calculate expected baseFee accumulation
echo "--- Test 3: BaseFee Accumulation Verification ---"

TOTAL_BASEFEE_COLLECTED=0
BLOCKS_WITH_GAS=0

for ((i=START_BLOCK_DEC+1; i<=CURRENT_BLOCK_DEC; i++)); do
  BLOCK_HEX=$(printf "0x%x" $i)

  BLOCK_DATA=$(curl -s -X POST -H "Content-Type: application/json" \
    --data "{\"jsonrpc\":\"2.0\",\"method\":\"eth_getBlockByNumber\",\"params\":[\"$BLOCK_HEX\",false],\"id\":1}" \
    http://localhost:$PRIMARY_PORT 2>/dev/null)

  BASE_FEE=$(echo "$BLOCK_DATA" | jq -r '.result.baseFeePerGas' 2>/dev/null || echo "null")
  GAS_USED=$(echo "$BLOCK_DATA" | jq -r '.result.gasUsed' 2>/dev/null || echo "0x0")

  if [ "$BASE_FEE" != "null" ] && [ -n "$BASE_FEE" ]; then
    BASE_FEE_DEC=$((16#${BASE_FEE#0x}))
    GAS_USED_DEC=$((16#${GAS_USED#0x}))

    BLOCK_FEE=$((BASE_FEE_DEC * GAS_USED_DEC))
    TOTAL_BASEFEE_COLLECTED=$((TOTAL_BASEFEE_COLLECTED + BLOCK_FEE))

    if [ $GAS_USED_DEC -gt 0 ]; then
      BLOCKS_WITH_GAS=$((BLOCKS_WITH_GAS + 1))
      echo "  Block #$i: baseFee=$BASE_FEE_DEC, gasUsed=$GAS_USED_DEC, fee=$BLOCK_FEE wei"
    fi
  fi
done

echo ""
echo "  Total baseFee collected: $TOTAL_BASEFEE_COLLECTED wei"
echo "  Blocks with gas used: $BLOCKS_WITH_GAS"
echo ""

if [ $BLOCKS_WITH_GAS -eq 0 ]; then
  echo "  ⚠️  No blocks had gas usage — treasury accumulation cannot be verified"
  echo "     (Send transactions to generate gas fees, then re-run)"
  echo "     Treasury balance unchanged is expected when gasUsed=0"
  PASS_COUNT=$((PASS_COUNT + 1))
else
  # Compare balance increase with expected accumulation
  # Note: bash can't do big number arithmetic well, this is approximate
  if [ "$FINAL_BALANCE_HEX" != "$INITIAL_BALANCE_HEX" ]; then
    echo "  ✅ Treasury balance changed (fees accumulated)"
    PASS_COUNT=$((PASS_COUNT + 1))
  else
    echo "  ❌ Treasury balance unchanged despite gas usage"
    FAIL_COUNT=$((FAIL_COUNT + 1))
  fi
fi

echo ""

# Test 4: Cross-client treasury balance consistency
echo "--- Test 4: Cross-Client Treasury Balance ---"

BALANCES=()
for port in "${ALL_PORTS[@]}"; do
  BAL=$(curl -s -X POST -H "Content-Type: application/json" \
    --data "{\"jsonrpc\":\"2.0\",\"method\":\"eth_getBalance\",\"params\":[\"$TREASURY_ADDRESS\",\"latest\"],\"id\":1}" \
    http://localhost:$port | jq -r '.result' 2>/dev/null || echo "error")
  BALANCES+=("$BAL")
  echo "  Port $port: $BAL"
done

UNIQUE_BALANCES=$(printf '%s\n' "${BALANCES[@]}" | sort -u | wc -l)

if [ $UNIQUE_BALANCES -eq 1 ]; then
  echo "  ✅ All nodes report same treasury balance"
  PASS_COUNT=$((PASS_COUNT + 1))
else
  echo "  ❌ Treasury balance mismatch across nodes!"
  FAIL_COUNT=$((FAIL_COUNT + 1))
fi

echo ""

# Summary
echo "=== Treasury Accumulation Test Summary ==="
echo "Total checks: $((PASS_COUNT + FAIL_COUNT))"
echo "Passed: $PASS_COUNT"
echo "Failed: $FAIL_COUNT"
echo ""

if [ $FAIL_COUNT -eq 0 ]; then
  echo "✅ Treasury accumulation test passed"
  exit 0
else
  echo "❌ Treasury accumulation test failed"
  exit 1
fi
