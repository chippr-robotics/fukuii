#!/usr/bin/env bash
# Healthcheck script for Fukuii Docker container

set -e

# Configuration
RPC_HOST="${RPC_HOST:-localhost}"
RPC_PORT="${RPC_PORT:-8545}"
RPC_URL="http://${RPC_HOST}:${RPC_PORT}"

# Check if the RPC endpoint is responding
if command -v curl >/dev/null 2>&1; then
    # Use curl if available
    response=$(curl -s -X POST \
        -H "Content-Type: application/json" \
        --data '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}' \
        --max-time 5 \
        "${RPC_URL}" || echo "")
    
    if [ -z "$response" ]; then
        echo "ERROR: No response from RPC endpoint at ${RPC_URL}"
        exit 1
    fi
    
    if echo "$response" | grep -q '"result"'; then
        echo "OK: RPC endpoint is responding"
        exit 0
    else
        echo "ERROR: Invalid response from RPC endpoint: $response"
        exit 1
    fi
else
    # Fallback: Check if the process is running and port is listening
    if pgrep -f "fukuii" >/dev/null 2>&1; then
        echo "OK: Fukuii process is running"
        exit 0
    else
        echo "ERROR: Fukuii process not found"
        exit 1
    fi
fi
