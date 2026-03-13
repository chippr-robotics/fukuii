#!/bin/bash
# Test script for EIP-7935 gas limit convergence validation (Olympia)
# Verifies gas limit converges toward 60M target at ±1/1024 per block

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib/test-helpers.sh"
require_tools curl jq

echo "=== EIP-7935 Gas Limit Convergence Test (Olympia) ==="
echo "Verifying gas limit convergence toward 60M target..."
echo ""

# Configuration
GAS_LIMIT_TARGET=${GAS_LIMIT_TARGET:-60000000}  # 60M default (EIP-7935)
SAMPLE_BLOCKS=${SAMPLE_BLOCKS:-20}

echo "Target gas limit: $GAS_LIMIT_TARGET"
echo "Sampling last $SAMPLE_BLOCKS blocks..."
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

PASS_COUNT=0
FAIL_COUNT=0

# Test 1: Gas limit values over recent blocks
echo "--- Test 1: Gas Limit History ---"

CURRENT_BLOCK=$(curl -s -X POST -H "Content-Type: application/json" \
  --data '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}' \
  http://localhost:$PRIMARY_PORT | jq -r '.result')

CURRENT_BLOCK_DEC=$((16#${CURRENT_BLOCK#0x}))

START_BLOCK=$((CURRENT_BLOCK_DEC - SAMPLE_BLOCKS + 1))
if [ $START_BLOCK -lt 1 ]; then
  START_BLOCK=1
fi

GAS_LIMITS=()
INCREASING=0
DECREASING=0
STABLE=0
PREV_LIMIT=0

for ((i=START_BLOCK; i<=CURRENT_BLOCK_DEC; i++)); do
  BLOCK_HEX=$(printf "0x%x" $i)

  GAS_LIMIT_HEX=$(curl -s -X POST -H "Content-Type: application/json" \
    --data "{\"jsonrpc\":\"2.0\",\"method\":\"eth_getBlockByNumber\",\"params\":[\"$BLOCK_HEX\",false],\"id\":1}" \
    http://localhost:$PRIMARY_PORT 2>/dev/null | jq -r '.result.gasLimit' 2>/dev/null || echo "0x0")

  GAS_LIMIT_DEC=$((16#${GAS_LIMIT_HEX#0x}))
  GAS_LIMITS+=($GAS_LIMIT_DEC)

  # Track direction
  if [ $PREV_LIMIT -gt 0 ]; then
    if [ $GAS_LIMIT_DEC -gt $PREV_LIMIT ]; then
      INCREASING=$((INCREASING + 1))
    elif [ $GAS_LIMIT_DEC -lt $PREV_LIMIT ]; then
      DECREASING=$((DECREASING + 1))
    else
      STABLE=$((STABLE + 1))
    fi
  fi

  # Calculate distance to target
  if [ $GAS_LIMIT_DEC -ge $GAS_LIMIT_TARGET ]; then
    DISTANCE=$((GAS_LIMIT_DEC - GAS_LIMIT_TARGET))
  else
    DISTANCE=$((GAS_LIMIT_TARGET - GAS_LIMIT_DEC))
  fi

  DISTANCE_PCT=$(( (DISTANCE * 100) / GAS_LIMIT_TARGET ))
  echo "  Block #$i: gasLimit=$GAS_LIMIT_DEC (${DISTANCE_PCT}% from target)"

  PREV_LIMIT=$GAS_LIMIT_DEC
done

echo ""
echo "  Direction: $INCREASING increasing, $DECREASING decreasing, $STABLE stable"

echo ""

# Test 2: Verify convergence direction
echo "--- Test 2: Convergence Direction ---"

FIRST_LIMIT=${GAS_LIMITS[0]}
LAST_LIMIT=${GAS_LIMITS[-1]}

if [ $FIRST_LIMIT -lt $GAS_LIMIT_TARGET ]; then
  # Below target — should be increasing
  if [ $LAST_LIMIT -ge $FIRST_LIMIT ]; then
    echo "  ✅ Gas limit trending upward toward target ($FIRST_LIMIT → $LAST_LIMIT)"
    PASS_COUNT=$((PASS_COUNT + 1))
  else
    echo "  ❌ Gas limit trending downward despite being below target"
    FAIL_COUNT=$((FAIL_COUNT + 1))
  fi
elif [ $FIRST_LIMIT -gt $GAS_LIMIT_TARGET ]; then
  # Above target — should be decreasing
  if [ $LAST_LIMIT -le $FIRST_LIMIT ]; then
    echo "  ✅ Gas limit trending downward toward target ($FIRST_LIMIT → $LAST_LIMIT)"
    PASS_COUNT=$((PASS_COUNT + 1))
  else
    echo "  ❌ Gas limit trending upward despite being above target"
    FAIL_COUNT=$((FAIL_COUNT + 1))
  fi
else
  # At target
  echo "  ✅ Gas limit is at target ($GAS_LIMIT_TARGET)"
  PASS_COUNT=$((PASS_COUNT + 1))
fi

echo ""

# Test 3: Verify step size is within ±1/1024
echo "--- Test 3: Step Size Validation (±1/1024) ---"

STEP_VIOLATIONS=0

for ((i=1; i<${#GAS_LIMITS[@]}; i++)); do
  PREV=${GAS_LIMITS[$((i-1))]}
  CURR=${GAS_LIMITS[$i]}

  if [ $CURR -ge $PREV ]; then
    STEP=$((CURR - PREV))
  else
    STEP=$((PREV - CURR))
  fi

  # Maximum allowed step: parent_gas_limit / 1024
  MAX_STEP=$((PREV / 1024))
  if [ $MAX_STEP -eq 0 ]; then
    MAX_STEP=1
  fi

  BLOCK_NUM=$((START_BLOCK + i))

  if [ $STEP -le $MAX_STEP ]; then
    echo "  Block #$BLOCK_NUM: step=$STEP (max=$MAX_STEP) ✅"
  else
    echo "  Block #$BLOCK_NUM: step=$STEP (max=$MAX_STEP) ❌ VIOLATION"
    STEP_VIOLATIONS=$((STEP_VIOLATIONS + 1))
  fi
done

echo ""

if [ $STEP_VIOLATIONS -eq 0 ]; then
  echo "  ✅ All gas limit steps within ±1/1024 bounds"
  PASS_COUNT=$((PASS_COUNT + 1))
else
  echo "  ❌ $STEP_VIOLATIONS step size violations found"
  FAIL_COUNT=$((FAIL_COUNT + 1))
fi

echo ""

# Test 4: Cross-client gas limit consistency
echo "--- Test 4: Cross-Client Gas Limit ---"

GAS_LIMITS_CROSS=()
for port in "${ALL_PORTS[@]}"; do
  GL=$(curl -s -X POST -H "Content-Type: application/json" \
    --data '{"jsonrpc":"2.0","method":"eth_getBlockByNumber","params":["latest",false],"id":1}' \
    http://localhost:$port 2>/dev/null | jq -r '.result.gasLimit' 2>/dev/null || echo "error")
  GAS_LIMITS_CROSS+=("$GL")
  GL_DEC=$((16#${GL#0x}))
  echo "  Port $port: gasLimit=$GL_DEC"
done

UNIQUE_GL=$(printf '%s\n' "${GAS_LIMITS_CROSS[@]}" | sort -u | wc -l)

if [ $UNIQUE_GL -eq 1 ]; then
  echo "  ✅ All nodes report same gas limit"
  PASS_COUNT=$((PASS_COUNT + 1))
else
  echo "  ❌ Gas limit mismatch across nodes!"
  FAIL_COUNT=$((FAIL_COUNT + 1))
fi

echo ""

# Estimate blocks to target
if [ $LAST_LIMIT -ne $GAS_LIMIT_TARGET ]; then
  REMAINING=$((GAS_LIMIT_TARGET - LAST_LIMIT))
  if [ $REMAINING -lt 0 ]; then
    REMAINING=$((-REMAINING))
  fi
  STEP_PER_BLOCK=$((LAST_LIMIT / 1024))
  if [ $STEP_PER_BLOCK -gt 0 ]; then
    BLOCKS_REMAINING=$((REMAINING / STEP_PER_BLOCK))
    echo "  Estimated blocks to reach target: ~$BLOCKS_REMAINING"
  fi
fi

echo ""

# Summary
echo "=== Gas Limit Convergence Test Summary ==="
echo "Total checks: $((PASS_COUNT + FAIL_COUNT))"
echo "Passed: $PASS_COUNT"
echo "Failed: $FAIL_COUNT"
echo ""

if [ $FAIL_COUNT -eq 0 ]; then
  echo "✅ Gas limit convergence test passed"
  exit 0
else
  echo "❌ Gas limit convergence test failed"
  exit 1
fi
