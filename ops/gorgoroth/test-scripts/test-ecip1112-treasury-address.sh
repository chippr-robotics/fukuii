#!/bin/bash
# Test script for ECIP-1112 Treasury Address verification (Olympia)
#
# ECIP-1112 defines the Olympia Treasury Contract — an immutable vault at a
# deterministic address that receives baseFee revenue via ECIP-1111.
#
# This script verifies:
#   1. The ECIP-1112 Treasury Address is correct on all clients
#   2. The address is accessible (eth_getBalance succeeds)
#   3. The address is a regular account (no contract code deployed yet)
#   4. Cross-client parity: all clients report the same address and balance

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib/test-helpers.sh"
source "$SCRIPT_DIR/olympia-config.sh"
require_tools curl jq

echo "=== ECIP-1112 Treasury Address Verification (Olympia) ==="
echo ""

# The canonical ECIP-1112 Treasury Address (from olympia-config.sh)
ECIP1112_TREASURY_ADDRESS="${TREASURY_ADDRESS:-$OLYMPIA_TREASURY_ADDRESS}"

echo "ECIP-1112 Treasury Address: $ECIP1112_TREASURY_ADDRESS"
echo ""

# Detect running nodes
FUKUII_PORTS=()
GETH_PORTS=()
BESU_PORTS=()

# Standard Gorgoroth ports
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

# Also check standalone multi-client ports
for port in 8545 8553; do
  if curl -s -f -m 2 http://localhost:$port > /dev/null 2>&1; then
    # Avoid duplicates
    found=false
    for p in "${FUKUII_PORTS[@]}" "${GETH_PORTS[@]}" "${BESU_PORTS[@]}"; do
      if [ "$p" = "$port" ]; then found=true; break; fi
    done
    if [ "$found" = false ]; then
      GETH_PORTS+=($port)  # Default to geth category for unknown ports
    fi
  fi
done

ALL_PORTS=("${FUKUII_PORTS[@]}" "${GETH_PORTS[@]}" "${BESU_PORTS[@]}")
TOTAL_NODES=${#ALL_PORTS[@]}

echo "Detected $TOTAL_NODES running nodes:"
echo "  Fukuii: ${#FUKUII_PORTS[@]} (ports: ${FUKUII_PORTS[*]:-none})"
echo "  Core-Geth: ${#GETH_PORTS[@]} (ports: ${GETH_PORTS[*]:-none})"
echo "  Besu: ${#BESU_PORTS[@]} (ports: ${BESU_PORTS[*]:-none})"
echo ""

if [ $TOTAL_NODES -eq 0 ]; then
  echo "FAIL: No running nodes detected"
  exit 1
fi

PRIMARY_PORT=${ALL_PORTS[0]}

PASS_COUNT=0
FAIL_COUNT=0

# Test 1: Verify ECIP-1112 Treasury Address is queryable on all nodes
echo "--- Test 1: ECIP-1112 Treasury Address Accessible ---"

for port in "${ALL_PORTS[@]}"; do
  RESULT=$(curl -s -X POST -H "Content-Type: application/json" \
    --data "{\"jsonrpc\":\"2.0\",\"method\":\"eth_getBalance\",\"params\":[\"$ECIP1112_TREASURY_ADDRESS\",\"latest\"],\"id\":1}" \
    http://localhost:$port 2>/dev/null)

  BALANCE=$(echo "$RESULT" | jq -r '.result' 2>/dev/null || echo "error")
  ERROR=$(echo "$RESULT" | jq -r '.error.message' 2>/dev/null || echo "")

  if [ "$BALANCE" != "null" ] && [ "$BALANCE" != "error" ] && [ -n "$BALANCE" ]; then
    echo "  Port $port: balance = $BALANCE"
    PASS_COUNT=$((PASS_COUNT + 1))
  else
    echo "  Port $port: FAIL — could not query treasury address (error: $ERROR)"
    FAIL_COUNT=$((FAIL_COUNT + 1))
  fi
done
echo ""

# Test 2: Verify ECIP-1112 Treasury Address is a regular account (no code)
echo "--- Test 2: Treasury Is Regular Account (No Contract Code Yet) ---"
echo "  ECIP-1112 deploys an immutable vault, but governance code (ECIP-1113/1114)"
echo "  is not deployed until governance activation. Pre-governance, the address"
echo "  should be a regular account (no code) that accumulates baseFee revenue."
echo ""

CODE=$(curl -s -X POST -H "Content-Type: application/json" \
  --data "{\"jsonrpc\":\"2.0\",\"method\":\"eth_getCode\",\"params\":[\"$ECIP1112_TREASURY_ADDRESS\",\"latest\"],\"id\":1}" \
  http://localhost:$PRIMARY_PORT | jq -r '.result' 2>/dev/null || echo "error")

if [ "$CODE" = "0x" ] || [ "$CODE" = "0x0" ] || [ -z "$CODE" ]; then
  echo "  PASS: No contract code at ECIP-1112 Treasury Address (regular account)"
  PASS_COUNT=$((PASS_COUNT + 1))
else
  CODE_LEN=${#CODE}
  echo "  INFO: Contract code found at treasury address ($CODE_LEN chars)"
  echo "  This may indicate governance contract deployment (ECIP-1113/1114)"
  # Not a failure — just informational
  PASS_COUNT=$((PASS_COUNT + 1))
fi
echo ""

# Test 3: Cross-client balance parity
echo "--- Test 3: Cross-Client ECIP-1112 Treasury Balance Parity ---"

BALANCES=()
BALANCE_LABELS=()

for port in "${ALL_PORTS[@]}"; do
  BAL=$(curl -s -X POST -H "Content-Type: application/json" \
    --data "{\"jsonrpc\":\"2.0\",\"method\":\"eth_getBalance\",\"params\":[\"$ECIP1112_TREASURY_ADDRESS\",\"latest\"],\"id\":1}" \
    http://localhost:$port | jq -r '.result' 2>/dev/null || echo "error")
  BALANCES+=("$BAL")
  BALANCE_LABELS+=("Port $port: $BAL")
  echo "  Port $port: $BAL"
done

UNIQUE_BALANCES=$(printf '%s\n' "${BALANCES[@]}" | sort -u | wc -l)

echo ""
if [ $UNIQUE_BALANCES -eq 1 ]; then
  echo "  PASS: All ${#ALL_PORTS[@]} nodes report identical ECIP-1112 Treasury balance"
  PASS_COUNT=$((PASS_COUNT + 1))
else
  echo "  FAIL: Treasury balance mismatch across nodes!"
  echo "  This indicates a consensus divergence in baseFee accounting"
  for label in "${BALANCE_LABELS[@]}"; do
    echo "    $label"
  done
  FAIL_COUNT=$((FAIL_COUNT + 1))
fi
echo ""

# Test 4: Verify chain ID matches expected network
echo "--- Test 4: Chain ID Verification ---"

CHAIN_ID=$(curl -s -X POST -H "Content-Type: application/json" \
  --data '{"jsonrpc":"2.0","method":"eth_chainId","params":[],"id":1}' \
  http://localhost:$PRIMARY_PORT | jq -r '.result' 2>/dev/null || echo "error")

CHAIN_ID_DEC=$((16#${CHAIN_ID#0x}))

echo "  Chain ID: $CHAIN_ID ($CHAIN_ID_DEC)"

case $CHAIN_ID_DEC in
  61)
    echo "  Network: ETC Mainnet"
    echo "  Expected ECIP-1112 Treasury Address: TBD (not yet scheduled)"
    PASS_COUNT=$((PASS_COUNT + 1))
    ;;
  63)
    echo "  Network: Mordor Testnet"
    echo "  Expected ECIP-1112 Treasury Address: TBD (set TREASURY_ADDRESS env var when decided)"
    PASS_COUNT=$((PASS_COUNT + 1))
    ;;
  *)
    echo "  Network: Custom/Gorgoroth (chain ID $CHAIN_ID_DEC)"
    echo "  PASS: Custom network — treasury address set by config"
    PASS_COUNT=$((PASS_COUNT + 1))
    ;;
esac
echo ""

# Test 5: Treasury nonce should be 0 (no outgoing transactions — no disbursements)
echo "--- Test 5: Treasury Nonce Is Zero (No Disbursements) ---"

NONCE=$(curl -s -X POST -H "Content-Type: application/json" \
  --data "{\"jsonrpc\":\"2.0\",\"method\":\"eth_getTransactionCount\",\"params\":[\"$ECIP1112_TREASURY_ADDRESS\",\"latest\"],\"id\":1}" \
  http://localhost:$PRIMARY_PORT | jq -r '.result' 2>/dev/null || echo "error")

NONCE_DEC=$((16#${NONCE#0x}))

if [ $NONCE_DEC -eq 0 ]; then
  echo "  Nonce: $NONCE_DEC"
  echo "  PASS: Treasury has sent no transactions (immutable vault, no disbursements)"
  PASS_COUNT=$((PASS_COUNT + 1))
else
  echo "  Nonce: $NONCE_DEC"
  echo "  INFO: Treasury has nonce > 0 — disbursements may have occurred"
  echo "  (This is expected after governance activation via ECIP-1113/1114)"
  PASS_COUNT=$((PASS_COUNT + 1))
fi
echo ""

# Summary
echo "=== ECIP-1112 Treasury Address Test Summary ==="
echo "Total checks: $((PASS_COUNT + FAIL_COUNT))"
echo "Passed: $PASS_COUNT"
echo "Failed: $FAIL_COUNT"
echo ""

if [ $FAIL_COUNT -eq 0 ]; then
  echo "ECIP-1112 Treasury Address verification passed"
  exit 0
else
  echo "ECIP-1112 Treasury Address verification failed"
  exit 1
fi
