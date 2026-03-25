#!/bin/bash
# Shared helpers for RPC integration test scripts
# Source this file at the top of RPC test scripts:
#   SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
#   source "$SCRIPT_DIR/lib/rpc-helpers.sh"

set -euo pipefail

# Defaults
RPC_HOST="${RPC_HOST:-localhost}"
RPC_PORT="${RPC_PORT:-8553}"
RPC_URL="http://${RPC_HOST}:${RPC_PORT}"

# Color output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

# Counters
PASS_COUNT=0
FAIL_COUNT=0
SKIP_COUNT=0

# Parse --host and --port flags
parse_args() {
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --host) RPC_HOST="$2"; shift 2 ;;
      --port) RPC_PORT="$2"; shift 2 ;;
      *) echo "Unknown option: $1"; exit 1 ;;
    esac
  done
  RPC_URL="http://${RPC_HOST}:${RPC_PORT}"
}

# JSON-RPC call — returns raw JSON response
rpc_call() {
  local method="$1" params="${2:-[]}"
  curl -s -m 10 -X POST -H "Content-Type: application/json" \
    --data "{\"jsonrpc\":\"2.0\",\"method\":\"$method\",\"params\":$params,\"id\":1}" \
    "$RPC_URL" 2>/dev/null
}

# HTTP GET — returns raw response
http_get() {
  local path="$1"
  curl -s -m 10 "${RPC_URL}${path}" 2>/dev/null
}

# Extract .result from JSON-RPC response (requires jq)
rpc_result() {
  local method="$1" params="${2:-[]}"
  rpc_call "$method" "$params" | jq -r '.result // empty'
}

# Hex string to decimal
hex_to_dec() {
  local hex="${1#0x}"
  printf "%d" "0x$hex" 2>/dev/null || echo "0"
}

# Test assertion helpers
pass() {
  echo -e "  ${GREEN}PASS${NC} $1"
  ((PASS_COUNT++)) || true
}

fail() {
  echo -e "  ${RED}FAIL${NC} $1"
  ((FAIL_COUNT++)) || true
}

skip() {
  echo -e "  ${YELLOW}SKIP${NC} $1"
  ((SKIP_COUNT++)) || true
}

# Assert that a value is non-empty
assert_not_empty() {
  local label="$1" value="$2"
  if [[ -n "$value" && "$value" != "null" ]]; then
    pass "$label"
  else
    fail "$label (got empty/null)"
  fi
}

# Assert value equals expected
assert_eq() {
  local label="$1" expected="$2" actual="$3"
  if [[ "$actual" == "$expected" ]]; then
    pass "$label"
  else
    fail "$label (expected '$expected', got '$actual')"
  fi
}

# Assert value is a hex number > 0
assert_hex_gt_zero() {
  local label="$1" value="$2"
  local dec
  dec=$(hex_to_dec "$value")
  if [[ "$dec" -gt 0 ]]; then
    pass "$label (=$dec)"
  else
    fail "$label (expected >0, got $value=$dec)"
  fi
}

# Assert value is a hex number >= 0
assert_hex_gte_zero() {
  local label="$1" value="$2"
  if [[ -n "$value" && "$value" != "null" ]]; then
    local dec
    dec=$(hex_to_dec "$value")
    pass "$label (=$dec)"
  else
    fail "$label (got empty/null)"
  fi
}

# Assert JSON response has no error field
assert_no_error() {
  local label="$1" response="$2"
  local err
  err=$(echo "$response" | jq -r '.error // empty')
  if [[ -z "$err" ]]; then
    pass "$label"
  else
    fail "$label (error: $err)"
  fi
}

# Assert HTTP response is non-empty and contains expected substring
assert_contains() {
  local label="$1" haystack="$2" needle="$3"
  if [[ "$haystack" == *"$needle"* ]]; then
    pass "$label"
  else
    fail "$label (expected to contain '$needle')"
  fi
}

# Check if node is reachable
check_node() {
  echo -e "${CYAN}Connecting to Fukuii at ${RPC_URL}...${NC}"
  local version
  version=$(rpc_result "web3_clientVersion")
  if [[ -z "$version" ]]; then
    echo -e "${RED}ERROR: Cannot reach Fukuii node at ${RPC_URL}${NC}"
    echo "Make sure the node is running with HTTP RPC enabled."
    exit 1
  fi
  echo -e "${GREEN}Connected: ${version}${NC}"
  echo ""
}

# Print summary and exit with appropriate code
print_summary() {
  echo ""
  echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
  echo -e "  ${GREEN}PASS: ${PASS_COUNT}${NC}  ${RED}FAIL: ${FAIL_COUNT}${NC}  ${YELLOW}SKIP: ${SKIP_COUNT}${NC}"
  echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
  if [[ "$FAIL_COUNT" -gt 0 ]]; then
    exit 1
  fi
}
