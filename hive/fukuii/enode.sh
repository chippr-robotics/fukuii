#!/bin/bash
# Returns the enode URL for this Fukuii instance
# Called by hive to discover the node's P2P identity

# Try admin_nodeInfo first (if available)
RESULT=$(curl -s -X POST -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"admin_nodeInfo","params":[],"id":1}' \
  http://localhost:8545 2>/dev/null)

ENODE=$(echo "$RESULT" | jq -r '.result.enode // empty' 2>/dev/null)

if [ -n "$ENODE" ]; then
    echo "$ENODE"
    exit 0
fi

# Fallback: parse from server log
ENODE=$(grep -o 'enode://[^ ]*' /app/data/logs/*.log 2>/dev/null | head -1)
if [ -n "$ENODE" ]; then
    echo "$ENODE"
    exit 0
fi

# Last resort: construct from node key + IP
echo "enode://unknown@127.0.0.1:30303"
