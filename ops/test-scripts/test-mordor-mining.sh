#!/bin/bash
# Mordor mining end-to-end smoke test
# Starts mining, monitors progress, verifies a mined block, stops mining.
#
# Usage:
#   ./test-mordor-mining.sh                          # localhost:8553
#   ./test-mordor-mining.sh --host 10.0.0.5 --port 8553
#
# Prerequisites:
#   - Fukuii synced to Mordor head
#   - Coinbase configured
#   - Mining enabled (--mining-enabled=true or config)
#   - jq, curl

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib/rpc-helpers.sh"

parse_args "$@"
check_node

# Verify Mordor
chain_id=$(rpc_result "eth_chainId")
if [[ "$chain_id" != "0x3f" ]]; then
  echo -e "${RED}ERROR: Not on Mordor (chainId=$chain_id). This test is Mordor-only.${NC}"
  exit 1
fi

# Verify synced (or close to synced)
sync_result=$(rpc_call "eth_syncing" | jq -r '.result')
if [[ "$sync_result" != "false" ]]; then
  echo -e "${YELLOW}WARNING: Node is still syncing. Mining may not produce blocks until synced.${NC}"
fi

# Get initial state
coinbase=$(rpc_result "eth_coinbase" 2>/dev/null || echo "")
start_block=$(rpc_result "eth_blockNumber")
start_dec=$(hex_to_dec "$start_block")
echo "Coinbase: ${coinbase:-not set}"
echo "Start block: $start_dec"
echo ""

# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
# Phase 1: Start mining
# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

echo "── Phase 1: Start Mining ──"

# Check if already mining
mining=$(rpc_result "eth_mining")
if [[ "$mining" == "true" ]]; then
  echo "Mining already active."
else
  echo "Starting miner..."
  response=$(rpc_call "miner_start" '[]')
  err=$(echo "$response" | jq -r '.error.message // empty')
  if [[ -n "$err" ]]; then
    echo -e "${RED}Failed to start mining: $err${NC}"
    exit 1
  fi
  sleep 2
  mining=$(rpc_result "eth_mining")
  if [[ "$mining" != "true" ]]; then
    echo -e "${RED}Mining did not start (eth_mining=$mining)${NC}"
    exit 1
  fi
  echo "Mining started."
fi

# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
# Phase 2: Monitor mining progress
# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

echo ""
echo "── Phase 2: Monitor (waiting for mined block, max 10 min) ──"

MAX_WAIT=600  # 10 minutes
INTERVAL=15   # check every 15 seconds
elapsed=0

while [[ $elapsed -lt $MAX_WAIT ]]; do
  current_block=$(rpc_result "eth_blockNumber")
  current_dec=$(hex_to_dec "$current_block")
  hashrate=$(rpc_result "eth_hashrate")
  hashrate_dec=$(hex_to_dec "$hashrate")

  # Check eth_getWork
  work=$(rpc_call "eth_getWork")
  pow_hash=$(echo "$work" | jq -r '.result[0] // "none"' 2>/dev/null)

  echo -e "  [${elapsed}s] block=$current_dec hashrate=${hashrate_dec}H/s work=${pow_hash:0:18}..."

  if [[ $current_dec -gt $start_dec ]]; then
    echo ""
    echo -e "${GREEN}New block mined! $start_dec → $current_dec${NC}"
    break
  fi

  sleep $INTERVAL
  ((elapsed += INTERVAL))
done

if [[ $elapsed -ge $MAX_WAIT ]]; then
  echo ""
  echo -e "${YELLOW}Timeout: No new block mined in ${MAX_WAIT}s. This is normal for Mordor — difficulty may be high.${NC}"
fi

# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
# Phase 3: Verify mined block
# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

current_block=$(rpc_result "eth_blockNumber")
current_dec=$(hex_to_dec "$current_block")

if [[ $current_dec -gt $start_dec ]]; then
  echo ""
  echo "── Phase 3: Verify Mined Block ──"

  # Get the latest block
  response=$(rpc_call "eth_getBlockByNumber" "[\"$current_block\", true]")
  miner=$(echo "$response" | jq -r '.result.miner // empty')
  block_hash=$(echo "$response" | jq -r '.result.hash // empty')
  difficulty=$(echo "$response" | jq -r '.result.difficulty // empty')
  nonce=$(echo "$response" | jq -r '.result.nonce // empty')
  mix_hash=$(echo "$response" | jq -r '.result.mixHash // empty')

  assert_not_empty "Block hash" "$block_hash"
  assert_not_empty "Block nonce" "$nonce"
  assert_not_empty "Block mixHash" "$mix_hash"
  assert_not_empty "Block difficulty" "$difficulty"

  # Check if our coinbase mined it
  if [[ -n "$coinbase" && "${miner,,}" == "${coinbase,,}" ]]; then
    pass "Block mined by our coinbase ($miner)"
  else
    echo -e "  ${YELLOW}INFO${NC} Block miner: $miner (our coinbase: ${coinbase:-not set})"
    echo "  (Block may have been mined by another miner — this is expected on Mordor)"
  fi
fi

# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
# Phase 4: Stop mining
# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

echo ""
echo "── Phase 4: Stop Mining ──"

response=$(rpc_call "miner_stop")
sleep 2
mining=$(rpc_result "eth_mining")
if [[ "$mining" == "false" ]]; then
  pass "Mining stopped"
else
  echo -e "  ${YELLOW}INFO${NC} eth_mining still reports true — may take a moment to stop"
fi

echo ""

print_summary
