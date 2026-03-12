#!/bin/bash
# Test script for Olympia EVM opcode validation
# Verifies new opcodes (BASEFEE, MCOPY, TLOAD/TSTORE) work via eth_call

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib/test-helpers.sh"
require_tools curl jq

echo "=== Olympia EVM Opcode Test ==="
echo "Verifying new opcodes are functional post-Olympia..."
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

# Helper: run eth_call with bytecode and check for success
run_opcode_test() {
  local name=$1
  local bytecode=$2
  local description=$3
  local port=$4

  RESULT=$(curl -s -X POST -H "Content-Type: application/json" \
    --data "{\"jsonrpc\":\"2.0\",\"method\":\"eth_call\",\"params\":[{\"data\":\"$bytecode\"},\"latest\"],\"id\":1}" \
    http://localhost:$port 2>/dev/null)

  ERROR=$(echo "$RESULT" | jq -r '.error.message' 2>/dev/null || echo "")

  if [ -z "$ERROR" ] || [ "$ERROR" = "null" ]; then
    RETURN_DATA=$(echo "$RESULT" | jq -r '.result' 2>/dev/null || echo "0x")
    echo "  ✅ $name: success (returned ${#RETURN_DATA} chars)"
    return 0
  else
    # Check if it's an "invalid opcode" error (pre-Olympia) vs other error
    if echo "$ERROR" | grep -qi "invalid opcode\|bad instruction\|undefined\|not activated"; then
      echo "  ❌ $name: opcode not supported — $ERROR"
      return 1
    else
      # Other errors (out of gas, revert) mean the opcode IS recognized
      echo "  ✅ $name: opcode recognized (execution error: $ERROR)"
      return 0
    fi
  fi
}

# Test 1: BASEFEE opcode (0x48) — EIP-3198
# Bytecode: BASEFEE PUSH1 0x00 MSTORE PUSH1 0x20 PUSH1 0x00 RETURN
# 48 60 00 52 60 20 60 00 f3
echo "--- Test 1: BASEFEE Opcode (0x48 — EIP-3198) ---"
echo "  Returns the current block's base fee"

BASEFEE_CODE="0x4860005260206000f3"

for port in "${ALL_PORTS[@]}"; do
  if run_opcode_test "BASEFEE (port $port)" "$BASEFEE_CODE" "EIP-3198" $port; then
    PASS_COUNT=$((PASS_COUNT + 1))
  else
    FAIL_COUNT=$((FAIL_COUNT + 1))
  fi
done

echo ""

# Test 2: MCOPY opcode (0x5E) — EIP-5656
# Minimal test: execute MCOPY with zero-length args to verify opcode is accepted.
# Bytecode: PUSH1 0x00 PUSH1 0x00 PUSH1 0x00 MCOPY STOP
#   60 00  PUSH1 0x00  (len=0)
#   60 00  PUSH1 0x00  (src=0)
#   60 00  PUSH1 0x00  (dst=0)
#   5e     MCOPY
#   00     STOP
echo "--- Test 2: MCOPY Opcode (0x5E — EIP-5656) ---"
echo "  Memory copy within EVM execution"

MCOPY_CODE="0x6000600060005e00"

for port in "${ALL_PORTS[@]}"; do
  if run_opcode_test "MCOPY (port $port)" "$MCOPY_CODE" "EIP-5656" $port; then
    PASS_COUNT=$((PASS_COUNT + 1))
  else
    FAIL_COUNT=$((FAIL_COUNT + 1))
  fi
done

echo ""

# Test 3: TLOAD opcode (0x5C) — EIP-1153
# Bytecode: PUSH1 0x00 TLOAD PUSH1 0x00 MSTORE PUSH1 0x20 PUSH1 0x00 RETURN
# 60 00 5c 60 00 52 60 20 60 00 f3
echo "--- Test 3: TLOAD Opcode (0x5C — EIP-1153) ---"
echo "  Transient storage load"

TLOAD_CODE="0x60005c60005260206000f3"

for port in "${ALL_PORTS[@]}"; do
  if run_opcode_test "TLOAD (port $port)" "$TLOAD_CODE" "EIP-1153" $port; then
    PASS_COUNT=$((PASS_COUNT + 1))
  else
    FAIL_COUNT=$((FAIL_COUNT + 1))
  fi
done

echo ""

# Test 4: TSTORE opcode (0x5D) — EIP-1153
# Bytecode: PUSH1 0x42 PUSH1 0x00 TSTORE STOP
# 60 42 60 00 5d 00
echo "--- Test 4: TSTORE Opcode (0x5D — EIP-1153) ---"
echo "  Transient storage store"

# TSTORE needs a non-static context (eth_call is static by default in some clients)
# Use: PUSH1 0x42 PUSH1 0x00 TSTORE PUSH1 0x00 TLOAD PUSH1 0x00 MSTORE PUSH1 0x20 PUSH1 0x00 RETURN
# This stores 0x42 at slot 0, loads it back, and returns it
TSTORE_CODE="0x604260005d60005c60005260206000f3"

for port in "${ALL_PORTS[@]}"; do
  if run_opcode_test "TSTORE+TLOAD (port $port)" "$TSTORE_CODE" "EIP-1153" $port; then
    PASS_COUNT=$((PASS_COUNT + 1))
  else
    FAIL_COUNT=$((FAIL_COUNT + 1))
  fi
done

echo ""

# Test 5: Verify client versions
echo "--- Test 5: Client Version Information ---"

for port in "${ALL_PORTS[@]}"; do
  CLIENT_VERSION=$(curl -s -X POST -H "Content-Type: application/json" \
    --data '{"jsonrpc":"2.0","method":"web3_clientVersion","params":[],"id":1}' \
    http://localhost:$port 2>/dev/null | jq -r '.result' 2>/dev/null || echo "unknown")
  echo "  Port $port: $CLIENT_VERSION"
done

echo ""

# Summary
echo "=== Olympia Opcode Test Summary ==="
echo "Total checks: $((PASS_COUNT + FAIL_COUNT))"
echo "Passed: $PASS_COUNT"
echo "Failed: $FAIL_COUNT"
echo ""

if [ $FAIL_COUNT -eq 0 ]; then
  echo "✅ Olympia opcode test passed"
  exit 0
else
  echo "❌ Olympia opcode test failed"
  exit 1
fi
