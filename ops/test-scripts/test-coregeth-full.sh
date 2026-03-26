#!/bin/bash
# Core-geth on Mordor — serves as P2P peer for Besu FULL sync
# Already synced near head. Start first, let it catch up.
# Admin API enabled for enode discovery.
set -euo pipefail

DATADIR="${DATADIR:-$HOME/.core-geth/mordor}"
BINARY="${BINARY:-../core-geth/build/bin/geth}"

if [ ! -f "$BINARY" ]; then
  echo "ERROR: core-geth binary not found at $BINARY"
  echo "Run 'make geth' in the core-geth repo first, or set BINARY."
  exit 1
fi

exec "$BINARY" \
  --mordor \
  --datadir="$DATADIR" \
  --http \
  --http.addr=0.0.0.0 \
  --http.port=8545 \
  --http.corsdomain="*" \
  --http.api=admin,eth,net,web3 \
  --port=30303 \
  --cache=1024 \
  --verbosity=3
