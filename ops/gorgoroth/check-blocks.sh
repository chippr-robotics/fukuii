#!/usr/bin/env bash
set -euo pipefail
PORTS=(8546 8548 8550)
for port in "${PORTS[@]}"; do
  block_hex=$(curl -s --ipv4 -X POST -H "Content-Type: application/json" \
    --data '{"jsonrpc":"2.0","method":"eth_getBlockByNumber","params":["latest",false],"id":1}' \
    "http://localhost:${port}" | jq -r '.result.number // "0x0"')
  hash_hex=$(curl -s --ipv4 -X POST -H "Content-Type: application/json" \
    --data '{"jsonrpc":"2.0","method":"eth_getBlockByNumber","params":["latest",false],"id":1}' \
    "http://localhost:${port}" | jq -r '.result.hash // "0x0"')
  block_dec=$((16#${block_hex:2}))
  printf "Port %s → Block %d (%s) Hash %s\n" "$port" "$block_dec" "$block_hex" "$hash_hex"
done
