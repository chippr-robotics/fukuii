#!/bin/bash
# Check sync status of all running clients
set -uo pipefail

echo "=== Core-geth (port 8545) ==="
curl -sf -X POST -H 'Content-Type: application/json' \
  --data '{"jsonrpc":"2.0","method":"eth_syncing","params":[],"id":1}' \
  http://localhost:8545 2>/dev/null | jq '.result' || echo "NOT RUNNING"

echo ""
echo "=== Besu (port 8548) ==="
curl -sf -X POST -H 'Content-Type: application/json' \
  --data '{"jsonrpc":"2.0","method":"eth_syncing","params":[],"id":1}' \
  http://localhost:8548 2>/dev/null | jq '.result' || echo "NOT RUNNING"

echo ""
echo "=== Fukuii (port 8553) ==="
curl -sf -X POST -H 'Content-Type: application/json' \
  --data '{"jsonrpc":"2.0","method":"eth_syncing","params":[],"id":1}' \
  http://localhost:8553 2>/dev/null | jq '.result' || echo "NOT RUNNING"
