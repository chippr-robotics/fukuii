#!/bin/bash
# Test script for block propagation validation
# Tests that blocks propagate correctly across all nodes

set -e

echo "=== Block Propagation Test ==="
echo "Testing block propagation across network..."
echo ""

# Detect running nodes
ALL_PORTS=()
for port in 8545 8547 8549 8551 8553 8555 8557 8559 8561; do
  if curl -s -f -m 2 http://localhost:$port > /dev/null 2>&1; then
    ALL_PORTS+=($port)
  fi
done

if [ ${#ALL_PORTS[@]} -eq 0 ]; then
  echo "❌ No running nodes detected"
  exit 1
fi

echo "Detected ${#ALL_PORTS[@]} running nodes"
echo ""

# Wait for some blocks to be mined
echo "Waiting 60 seconds for blocks to be mined..."
sleep 60

# Test: Check all nodes have same latest block
echo "--- Test: Block Synchronization ---"

BLOCK_NUMBERS=()
BLOCK_HASHES=()

for port in "${ALL_PORTS[@]}"; do
  BLOCK_NUM=$(curl -s -X POST -H "Content-Type: application/json" \
    --data '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}' \
    http://localhost:$port 2>/dev/null | jq -r '.result' 2>/dev/null || echo "error")
  
  # Validate BLOCK_NUM is a hex string (e.g., 0x1234abcd)
  if [ "$BLOCK_NUM" != "error" ] && [[ "$BLOCK_NUM" =~ ^0x[0-9a-fA-F]+$ ]]; then
    BLOCK_NUM_DEC=$((16#${BLOCK_NUM#0x}))
    BLOCK_NUMBERS+=($BLOCK_NUM_DEC)
    
    # Get block hash at this height using jq to safely encode JSON
    BLOCK_HASH=$(curl -s -X POST -H "Content-Type: application/json" \
      --data "$(jq -n --arg block "$BLOCK_NUM" '{jsonrpc:"2.0",method:"eth_getBlockByNumber",params:[$block,false],id:1}')" \
      http://localhost:$port 2>/dev/null | jq -r '.result.hash' 2>/dev/null || echo "error")
    
    BLOCK_HASHES+=("$BLOCK_HASH")
    
    echo "  Port $port: Block #$BLOCK_NUM_DEC ($BLOCK_HASH)"
  else
    echo "  Port $port: ERROR - invalid block number or could not get block number"
    BLOCK_NUMBERS+=(-1)
    BLOCK_HASHES+=("error")
  fi
done

echo ""

# Check block number range
MIN_BLOCK=$(printf '%s\n' "${BLOCK_NUMBERS[@]}" | grep -v "^-1$" | sort -n | head -1)
MAX_BLOCK=$(printf '%s\n' "${BLOCK_NUMBERS[@]}" | grep -v "^-1$" | sort -n | tail -1)
BLOCK_DIFF=$((MAX_BLOCK - MIN_BLOCK))

echo "Block range: $MIN_BLOCK to $MAX_BLOCK (difference: $BLOCK_DIFF)"

if [ $BLOCK_DIFF -le 2 ]; then
  echo "✅ All nodes are synchronized (within 2 blocks)"
else
  echo "⚠️  WARNING: Nodes have significant block difference ($BLOCK_DIFF blocks)"
fi

echo ""

# Test: Verify a specific block hash matches across all nodes
echo "--- Test: Block Hash Consistency ---"

# Use a block that should be on all nodes (MIN_BLOCK - 5 or block 1 if too new)
TEST_BLOCK=$((MIN_BLOCK - 5))
if [ $TEST_BLOCK -lt 1 ]; then
  TEST_BLOCK=1
fi

TEST_BLOCK_HEX=$(printf "0x%x" $TEST_BLOCK)
echo "Testing block #$TEST_BLOCK for hash consistency..."

TEST_HASHES=()
for port in "${ALL_PORTS[@]}"; do
  HASH=$(curl -s -X POST -H "Content-Type: application/json" \
    --data "{\"jsonrpc\":\"2.0\",\"method\":\"eth_getBlockByNumber\",\"params\":[\"$TEST_BLOCK_HEX\",false],\"id\":1}" \
    http://localhost:$port 2>/dev/null | jq -r '.result.hash' 2>/dev/null || echo "error")
  
  TEST_HASHES+=("$HASH")
  echo "  Port $port: $HASH"
done

# Check all hashes are the same
UNIQUE_HASHES=$(printf '%s\n' "${TEST_HASHES[@]}" | grep -v "error" | sort -u | wc -l)
if [ $UNIQUE_HASHES -eq 1 ]; then
  echo "✅ All nodes have same hash for block #$TEST_BLOCK"
else
  echo "❌ FAIL: Nodes have different hashes for block #$TEST_BLOCK!"
  exit 1
fi

echo ""

# Test: Block propagation time
echo "--- Test: Block Propagation Time ---"
echo "Monitoring block propagation for 3 new blocks..."

for i in 1 2 3; do
  echo ""
  echo "Monitoring round $i of 3..."
  
  # Get initial block from first node
  INITIAL_BLOCK=$(curl -s -X POST -H "Content-Type: application/json" \
    --data '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}' \
    http://localhost:${ALL_PORTS[0]} | jq -r '.result')
  
  INITIAL_BLOCK_DEC=$((16#${INITIAL_BLOCK#0x}))
  TARGET_BLOCK=$((INITIAL_BLOCK_DEC + 1))
  
  echo "  Current block: $INITIAL_BLOCK_DEC, waiting for block $TARGET_BLOCK..."
  
  # Wait for new block on first node
  START_TIME=$(date +%s)
  while true; do
    CURRENT=$(curl -s -X POST -H "Content-Type: application/json" \
      --data '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}' \
      http://localhost:${ALL_PORTS[0]} | jq -r '.result')
    CURRENT_DEC=$((16#${CURRENT#0x}))
    
    if [ $CURRENT_DEC -ge $TARGET_BLOCK ]; then
      break
    fi
    
    sleep 1
    
    # Timeout after 60 seconds
    NOW=$(date +%s)
    if [ $((NOW - START_TIME)) -gt 60 ]; then
      echo "  ⚠️  Timeout waiting for new block"
      break
    fi
  done
  
  BLOCK_RECEIVED_TIME=$(date +%s)
  
  # Check how long it takes for other nodes to get this block
  echo "  Block $TARGET_BLOCK received, checking propagation..."
  
  for port in "${ALL_PORTS[@]:1}"; do  # Skip first port
    WAIT_START=$(date +%s)
    while true; do
      BLOCK=$(curl -s -X POST -H "Content-Type: application/json" \
        --data '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}' \
        http://localhost:$port | jq -r '.result' 2>/dev/null || echo "0x0")
      
      BLOCK_DEC=$((16#${BLOCK#0x}))
      
      if [ $BLOCK_DEC -ge $TARGET_BLOCK ]; then
        WAIT_END=$(date +%s)
        PROPAGATION_TIME=$((WAIT_END - BLOCK_RECEIVED_TIME))
        echo "    Port $port: $PROPAGATION_TIME seconds"
        break
      fi
      
      sleep 0.5
      
      # Timeout after 30 seconds
      NOW=$(date +%s)
      if [ $((NOW - WAIT_START)) -gt 30 ]; then
        echo "    Port $port: TIMEOUT (>30s)"
        break
      fi
    done
  done
  
  sleep 5
done

echo ""
echo "=== Block Propagation Test Complete ==="
echo "✅ Test completed successfully"
