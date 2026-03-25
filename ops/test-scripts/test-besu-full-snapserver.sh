#!/bin/bash
# Besu on Mordor — FULL syncs from core-geth, then serves SNAP to Fukuii.
# Requires core-geth running on port 30303 first.
# Admin API enabled for enode discovery.
set -euo pipefail

BESU_DIR="/media/dev/2tb/dev/besu"
DATADIR="/media/dev/2tb/data/blockchain/besu/mordor"
GENESIS="$BESU_DIR/config/mordor.json"
BINARY="$BESU_DIR/build/install/besu/bin/besu"

if [ ! -f "$BINARY" ]; then
  echo "ERROR: Besu binary not found at $BINARY"
  echo "Run './gradlew installDist -x test' in $BESU_DIR first."
  exit 1
fi

# Get core-geth enode automatically
COREGETH_ENODE=$(curl -sf -X POST -H 'Content-Type: application/json' \
  --data '{"jsonrpc":"2.0","method":"admin_nodeInfo","params":[],"id":1}' \
  http://localhost:8545 | jq -r '.result.enode' | sed 's/@[^:]*:/@127.0.0.1:/')

if [ -z "$COREGETH_ENODE" ] || [ "$COREGETH_ENODE" = "null" ]; then
  echo "ERROR: Could not get core-geth enode. Is core-geth running on port 8545?"
  echo "Start test-coregeth-full.sh first."
  exit 1
fi

echo "Using core-geth enode: $COREGETH_ENODE"

exec "$BINARY" \
  --genesis-file="$GENESIS" \
  --data-path="$DATADIR" \
  --network-id=7 \
  --rpc-http-enabled \
  --rpc-http-host=0.0.0.0 \
  --rpc-http-port=8548 \
  --rpc-http-cors-origins="*" \
  --rpc-http-api=ADMIN,ETH,NET,WEB3 \
  --p2p-port=30304 \
  --data-storage-format=FOREST \
  --sync-mode=FULL \
  --sync-min-peers=1 \
  --snapsync-server-enabled \
  --bootnodes="$COREGETH_ENODE" \
  --logging=INFO
