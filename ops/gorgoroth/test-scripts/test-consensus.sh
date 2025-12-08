#!/bin/bash
# Test script for consensus validation
# Long-running test to verify consensus is maintained

set -e

echo "=== Consensus Maintenance Test ==="
echo "Monitoring network consensus over time..."
echo ""

# Configuration
DURATION_MINUTES=${1:-30}
CHECK_INTERVAL=60  # Check every 60 seconds

echo "Test duration: $DURATION_MINUTES minutes"
echo "Check interval: $CHECK_INTERVAL seconds"
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

echo "Monitoring ${#ALL_PORTS[@]} nodes"
echo ""

# Track metrics
TOTAL_CHECKS=0
CONSENSUS_BREAKS=0
MAX_BLOCK_DIFF=0

# Calculate end time
START_TIME=$(date +%s)
END_TIME=$((START_TIME + DURATION_MINUTES * 60))

echo "Started at: $(date)"
echo "Will end at: $(date -d @$END_TIME)"
echo ""

# Main monitoring loop
while [ $(date +%s) -lt $END_TIME ]; do
  TOTAL_CHECKS=$((TOTAL_CHECKS + 1))
  CURRENT_TIME=$(date +%s)
  ELAPSED=$((CURRENT_TIME - START_TIME))
  REMAINING=$((END_TIME - CURRENT_TIME))
  
  echo "--- Check $TOTAL_CHECKS (Elapsed: ${ELAPSED}s, Remaining: ${REMAINING}s) ---"
  echo "Time: $(date)"
  
  # Get block numbers from all nodes
  BLOCK_NUMBERS=()
  BLOCK_HASHES=()
  
  for port in "${ALL_PORTS[@]}"; do
    BLOCK_NUM=$(curl -s -X POST -H "Content-Type: application/json" \
      --data '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}' \
      http://localhost:$port 2>/dev/null | jq -r '.result' 2>/dev/null || echo "0x0")
    
    BLOCK_NUM_DEC=$((16#${BLOCK_NUM#0x}))
    BLOCK_NUMBERS+=($BLOCK_NUM_DEC)
    
    # Get hash of latest block
    HASH=$(curl -s -X POST -H "Content-Type: application/json" \
      --data '{"jsonrpc":"2.0","method":"eth_getBlockByNumber","params":["latest",false],"id":1}' \
      http://localhost:$port 2>/dev/null | jq -r '.result.hash' 2>/dev/null || echo "null")
    
    BLOCK_HASHES+=("$HASH")
    
    echo "  Port $port: Block #$BLOCK_NUM_DEC ($HASH)"
  done
  
  # Calculate block range
  MIN_BLOCK=$(printf '%s\n' "${BLOCK_NUMBERS[@]}" | sort -n | head -1)
  MAX_BLOCK=$(printf '%s\n' "${BLOCK_NUMBERS[@]}" | sort -n | tail -1)
  BLOCK_DIFF=$((MAX_BLOCK - MIN_BLOCK))
  
  # Track maximum divergence
  if [ $BLOCK_DIFF -gt $MAX_BLOCK_DIFF ]; then
    MAX_BLOCK_DIFF=$BLOCK_DIFF
  fi
  
  echo ""
  echo "  Block range: $MIN_BLOCK - $MAX_BLOCK (diff: $BLOCK_DIFF)"
  
  # Check for consensus break
  if [ $BLOCK_DIFF -gt 5 ]; then
    echo "  ⚠️  WARNING: Large block difference detected ($BLOCK_DIFF blocks)"
    CONSENSUS_BREAKS=$((CONSENSUS_BREAKS + 1))
  else
    echo "  ✅ Consensus maintained (diff <= 5)"
  fi
  
  # Check if nodes at same height have same hash
  # Group nodes by block number and check hashes match
  declare -A BLOCKS_AT_HEIGHT
  for i in "${!BLOCK_NUMBERS[@]}"; do
    height=${BLOCK_NUMBERS[$i]}
    hash=${BLOCK_HASHES[$i]}
    
    if [ -n "${BLOCKS_AT_HEIGHT[$height]}" ]; then
      # There's already a hash for this height, check if it matches
      if [ "${BLOCKS_AT_HEIGHT[$height]}" != "$hash" ]; then
        echo "  ❌ CHAIN SPLIT DETECTED! Two nodes at height $height have different hashes!"
        echo "     Hash 1: ${BLOCKS_AT_HEIGHT[$height]}"
        echo "     Hash 2: $hash"
        CONSENSUS_BREAKS=$((CONSENSUS_BREAKS + 1))
      fi
    else
      BLOCKS_AT_HEIGHT[$height]=$hash
    fi
  done
  
  echo ""
  
  # Sleep until next check
  sleep $CHECK_INTERVAL
done

# Final summary
echo ""
echo "=== Consensus Test Summary ==="
echo "Duration: $DURATION_MINUTES minutes"
echo "Total checks: $TOTAL_CHECKS"
echo "Consensus breaks: $CONSENSUS_BREAKS"
echo "Maximum block difference observed: $MAX_BLOCK_DIFF blocks"
echo ""

if [ $CONSENSUS_BREAKS -eq 0 ]; then
  echo "✅ Consensus maintained throughout test"
  echo "✅ No chain splits detected"
  exit 0
else
  echo "⚠️  $CONSENSUS_BREAKS consensus issues detected"
  exit 1
fi
