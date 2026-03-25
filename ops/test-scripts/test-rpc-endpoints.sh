#!/bin/bash
# Live RPC integration tests for Fukuii — Tier 1 (Core) + Tier 3 (Peer/Admin)
# Tests all non-mining JSON-RPC endpoints against a running Fukuii node.
#
# Usage:
#   ./test-rpc-endpoints.sh                          # localhost:8553
#   ./test-rpc-endpoints.sh --host 10.0.0.5 --port 8553
#
# Prerequisites: jq, curl, running Fukuii node with HTTP RPC enabled

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib/rpc-helpers.sh"

parse_args "$@"
check_node

# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
# Tier 1: Core RPC (must work for any user)
# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

echo "── Tier 1: Core RPC ──"

# web3_clientVersion
result=$(rpc_result "web3_clientVersion")
assert_contains "web3_clientVersion contains 'fukuii'" "$result" "fukuii"

# web3_sha3 — keccak256("") = 0xc5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470
result=$(rpc_result "web3_sha3" '["0x"]')
assert_eq "web3_sha3 keccak256 of empty" \
  "0xc5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470" "$result"

# net_version
result=$(rpc_result "net_version")
if [[ "$result" == "7" || "$result" == "1" ]]; then
  pass "net_version ($result)"
else
  fail "net_version (expected 7 or 1, got '$result')"
fi

# net_listening
result=$(rpc_result "net_listening")
assert_eq "net_listening" "true" "$result"

# net_peerCount
result=$(rpc_result "net_peerCount")
assert_hex_gte_zero "net_peerCount" "$result"

# eth_chainId
result=$(rpc_result "eth_chainId")
if [[ "$result" == "0x3f" || "$result" == "0x3d" ]]; then
  pass "eth_chainId ($result)"
else
  fail "eth_chainId (expected 0x3f Mordor or 0x3d ETC, got '$result')"
fi
CHAIN_ID="$result"

# eth_protocolVersion
result=$(rpc_result "eth_protocolVersion")
assert_not_empty "eth_protocolVersion" "$result"

# eth_blockNumber
result=$(rpc_result "eth_blockNumber")
assert_hex_gt_zero "eth_blockNumber" "$result"
BLOCK_NUMBER="$result"

# eth_gasPrice
result=$(rpc_result "eth_gasPrice")
assert_hex_gt_zero "eth_gasPrice" "$result"

# eth_syncing — returns false when synced, object when syncing
response=$(rpc_call "eth_syncing")
sync_result=$(echo "$response" | jq -r '.result')
if [[ "$sync_result" == "false" ]]; then
  pass "eth_syncing (synced)"
elif echo "$response" | jq -e '.result.currentBlock' &>/dev/null; then
  current=$(echo "$response" | jq -r '.result.currentBlock')
  highest=$(echo "$response" | jq -r '.result.highestBlock')
  pass "eth_syncing (syncing: $(hex_to_dec "$current")/$(hex_to_dec "$highest"))"
else
  fail "eth_syncing (unexpected: $sync_result)"
fi

# eth_getBlockByNumber("latest", false)
response=$(rpc_call "eth_getBlockByNumber" '["latest", false]')
block_hash=$(echo "$response" | jq -r '.result.hash // empty')
block_num=$(echo "$response" | jq -r '.result.number // empty')
assert_not_empty "eth_getBlockByNumber(latest) hash" "$block_hash"
assert_not_empty "eth_getBlockByNumber(latest) number" "$block_num"

# eth_getBlockByHash — use hash from above
if [[ -n "$block_hash" && "$block_hash" != "null" ]]; then
  response2=$(rpc_call "eth_getBlockByHash" "[\"$block_hash\", false]")
  hash2=$(echo "$response2" | jq -r '.result.hash // empty')
  assert_eq "eth_getBlockByHash consistent" "$block_hash" "$hash2"
else
  skip "eth_getBlockByHash (no block hash from previous test)"
fi

# eth_getBalance — genesis / zero address (should return something)
result=$(rpc_result "eth_getBalance" '["0x0000000000000000000000000000000000000000", "latest"]')
assert_hex_gte_zero "eth_getBalance(zero addr)" "$result"

# eth_getTransactionCount — zero address
result=$(rpc_result "eth_getTransactionCount" '["0x0000000000000000000000000000000000000000", "latest"]')
assert_hex_gte_zero "eth_getTransactionCount(zero addr)" "$result"

# eth_getCode — zero address (should be empty = 0x)
result=$(rpc_result "eth_getCode" '["0x0000000000000000000000000000000000000000", "latest"]')
assert_not_empty "eth_getCode(zero addr)" "$result"

# eth_estimateGas — simple value transfer
response=$(rpc_call "eth_estimateGas" '[{"from":"0x0000000000000000000000000000000000000000","to":"0x0000000000000000000000000000000000000001","value":"0x1"}]')
result=$(echo "$response" | jq -r '.result // empty')
if [[ -n "$result" ]]; then
  assert_hex_gt_zero "eth_estimateGas (value transfer)" "$result"
else
  # May fail during sync — not a hard error
  err=$(echo "$response" | jq -r '.error.message // empty')
  skip "eth_estimateGas ($err)"
fi

# eth_call — read-only call to zero address (should return empty)
response=$(rpc_call "eth_call" '[{"to":"0x0000000000000000000000000000000000000001","data":"0x"}, "latest"]')
assert_no_error "eth_call (zero addr)" "$response"

# eth_getLogs — empty filter for latest block
response=$(rpc_call "eth_getLogs" "[{\"fromBlock\":\"$BLOCK_NUMBER\",\"toBlock\":\"$BLOCK_NUMBER\"}]")
assert_no_error "eth_getLogs (single block filter)" "$response"

# eth_getBlockTransactionCountByNumber
result=$(rpc_result "eth_getBlockTransactionCountByNumber" '["latest"]')
assert_hex_gte_zero "eth_getBlockTransactionCountByNumber" "$result"

# eth_getUncleCountByBlockNumber
result=$(rpc_result "eth_getUncleCountByBlockNumber" '["latest"]')
assert_hex_gte_zero "eth_getUncleCountByBlockNumber" "$result"

# eth_accounts
response=$(rpc_call "eth_accounts")
assert_no_error "eth_accounts" "$response"

echo ""

# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
# Tier 3: Peer/Admin RPC
# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

echo "── Tier 3: Peer/Admin RPC ──"

# admin_nodeInfo
response=$(rpc_call "admin_nodeInfo")
enode=$(echo "$response" | jq -r '.result.enode // empty')
if [[ -n "$enode" ]]; then
  assert_contains "admin_nodeInfo enode" "$enode" "enode://"
else
  err=$(echo "$response" | jq -r '.error.message // empty')
  if [[ -n "$err" ]]; then
    skip "admin_nodeInfo (method not available: $err)"
  else
    fail "admin_nodeInfo (empty response)"
  fi
fi

# admin_peers
response=$(rpc_call "admin_peers")
if echo "$response" | jq -e '.result' &>/dev/null; then
  peer_count=$(echo "$response" | jq '.result | length')
  pass "admin_peers (${peer_count} peers)"
else
  err=$(echo "$response" | jq -r '.error.message // empty')
  skip "admin_peers ($err)"
fi

# net_nodeInfo
response=$(rpc_call "net_nodeInfo")
if echo "$response" | jq -e '.result' &>/dev/null; then
  assert_no_error "net_nodeInfo" "$response"
else
  skip "net_nodeInfo (not available)"
fi

# net_listPeers
response=$(rpc_call "net_listPeers")
if echo "$response" | jq -e '.result' &>/dev/null; then
  peer_count=$(echo "$response" | jq '.result | length')
  pass "net_listPeers (${peer_count} peers)"
else
  skip "net_listPeers (not available)"
fi

# txpool_status
response=$(rpc_call "txpool_status")
if echo "$response" | jq -e '.result' &>/dev/null; then
  pending=$(echo "$response" | jq -r '.result.pending // empty')
  assert_not_empty "txpool_status" "$pending"
else
  skip "txpool_status (not available)"
fi

# txpool_content
response=$(rpc_call "txpool_content")
if echo "$response" | jq -e '.result' &>/dev/null; then
  assert_no_error "txpool_content" "$response"
else
  skip "txpool_content (not available)"
fi

# debug_accountRange (may require debug namespace)
response=$(rpc_call "debug_accountRange" '["latest", "0x0000000000000000000000000000000000000000000000000000000000000000", 10]')
if echo "$response" | jq -e '.result' &>/dev/null; then
  assert_no_error "debug_accountRange" "$response"
else
  skip "debug_accountRange (not available)"
fi

echo ""

print_summary
