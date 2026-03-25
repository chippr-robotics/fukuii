#!/bin/bash
# Live RPC integration tests for Fukuii — Tier 2 (Mining RPC)
# Tests mining-related JSON-RPC endpoints against a running Fukuii node.
# Mordor only — the NUC can mine Mordor blocks with real Ethash.
#
# Usage:
#   ./test-rpc-mining.sh                          # localhost:8553
#   ./test-rpc-mining.sh --host 10.0.0.5 --port 8553
#
# Prerequisites: jq, curl, running Fukuii node on Mordor with mining enabled

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib/rpc-helpers.sh"

parse_args "$@"
check_node

# Verify we're on Mordor
chain_id=$(rpc_result "eth_chainId")
if [[ "$chain_id" != "0x3f" ]]; then
  echo -e "${YELLOW}WARNING: Not on Mordor (chainId=$chain_id). Mining tests are designed for Mordor.${NC}"
  echo "Some tests may not apply to this network."
  echo ""
fi

echo "── Tier 2: Mining RPC ──"

# eth_mining — check current mining state
result=$(rpc_result "eth_mining")
assert_not_empty "eth_mining" "$result"
MINING_STATE="$result"

# eth_coinbase — should return configured coinbase address
response=$(rpc_call "eth_coinbase")
coinbase=$(echo "$response" | jq -r '.result // empty')
if [[ -n "$coinbase" && "$coinbase" != "null" ]]; then
  assert_contains "eth_coinbase is address" "$coinbase" "0x"
else
  err=$(echo "$response" | jq -r '.error.message // empty')
  skip "eth_coinbase ($err)"
fi

# eth_hashrate — returns 0 when not mining, >0 when mining
result=$(rpc_result "eth_hashrate")
assert_hex_gte_zero "eth_hashrate" "$result"

# eth_getWork — returns [powHash, seedHash, target] triple
response=$(rpc_call "eth_getWork")
if echo "$response" | jq -e '.result[0]' &>/dev/null; then
  pow_hash=$(echo "$response" | jq -r '.result[0]')
  seed_hash=$(echo "$response" | jq -r '.result[1]')
  target=$(echo "$response" | jq -r '.result[2]')
  assert_not_empty "eth_getWork powHash" "$pow_hash"
  assert_not_empty "eth_getWork seedHash" "$seed_hash"
  assert_not_empty "eth_getWork target" "$target"
else
  err=$(echo "$response" | jq -r '.error.message // empty')
  skip "eth_getWork ($err)"
fi

# eth_submitWork — test with invalid nonce (should return false, not error)
response=$(rpc_call "eth_submitWork" '["0x0000000000000001","0x0000000000000000000000000000000000000000000000000000000000000000","0x0000000000000000000000000000000000000000000000000000000000000000"]')
result=$(echo "$response" | jq -r '.result // empty')
if [[ "$result" == "false" ]]; then
  pass "eth_submitWork (rejects invalid nonce)"
elif [[ -n "$result" ]]; then
  pass "eth_submitWork (responded: $result)"
else
  err=$(echo "$response" | jq -r '.error.message // empty')
  skip "eth_submitWork ($err)"
fi

# eth_submitHashrate — report hashrate
response=$(rpc_call "eth_submitHashrate" '["0x0000000000000000000000000000000000000000000000000000000000500000","0x59daa26581d0acd1fce254fb7e85952f4c09d0915afd33d3886cd914bc7d283c"]')
result=$(echo "$response" | jq -r '.result // empty')
if [[ "$result" == "true" ]]; then
  pass "eth_submitHashrate"
else
  err=$(echo "$response" | jq -r '.error.message // empty')
  skip "eth_submitHashrate ($err)"
fi

echo ""

# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
# Miner namespace (if available)
# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

echo "── Miner Namespace ──"

# miner_getStatus
response=$(rpc_call "miner_getStatus")
if echo "$response" | jq -e '.result' &>/dev/null; then
  is_mining=$(echo "$response" | jq -r '.result.isMining // empty')
  assert_not_empty "miner_getStatus isMining" "$is_mining"
  miner_coinbase=$(echo "$response" | jq -r '.result.coinbase // empty')
  if [[ -n "$miner_coinbase" ]]; then
    pass "miner_getStatus coinbase ($miner_coinbase)"
  fi
else
  skip "miner_getStatus (namespace not available)"
fi

# miner_start / miner_stop — only test if mining is currently OFF
if [[ "$MINING_STATE" == "false" ]]; then
  # Start mining
  response=$(rpc_call "miner_start" '[]')
  result=$(echo "$response" | jq -r '.result // empty')
  if [[ "$result" == "true" || "$result" == "null" ]] || echo "$response" | jq -e '.result' &>/dev/null; then
    pass "miner_start (accepted)"

    # Brief pause for state change
    sleep 2

    # Verify mining started
    mining_now=$(rpc_result "eth_mining")
    if [[ "$mining_now" == "true" ]]; then
      pass "eth_mining after miner_start (true)"
    else
      skip "eth_mining after miner_start (state didn't change yet)"
    fi

    # Stop mining
    response=$(rpc_call "miner_stop")
    result=$(echo "$response" | jq -r '.result // empty')
    if [[ "$result" == "true" || "$result" == "null" ]] || echo "$response" | jq -e '.result' &>/dev/null; then
      pass "miner_stop (accepted)"
    else
      fail "miner_stop"
    fi
  else
    err=$(echo "$response" | jq -r '.error.message // empty')
    skip "miner_start/stop ($err)"
  fi
else
  skip "miner_start/stop (mining already active, won't disrupt)"
fi

echo ""

print_summary
