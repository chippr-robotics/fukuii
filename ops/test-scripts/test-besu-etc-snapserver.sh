#!/bin/bash
# Besu on ETC mainnet — SNAP syncs from core-geth, then serves SNAP to Fukuii.
# Uses BONSAI storage (required for SNAP serving) with SNAP sync mode
# (skips FULL block processing, avoids Bonsai clearStorage stall on SELFDESTRUCT blocks).
# Requires core-geth running on port 8545 (run-classic.sh) first.
# Admin API enabled for enode discovery.
#
# Startup sequence:
#   1. core-geth:  cd /media/dev/2tb/dev/core-geth && ./run-classic.sh
#   2. besu:       cd /media/dev/2tb/dev/fukuii && ./ops/test-scripts/test-besu-etc-snapserver.sh
#   3. fukuii:     cd /media/dev/2tb/dev/fukuii && ./run-classic.sh
#
# Besu ports: 8548 (HTTP), 30304 (P2P)
# Data: /media/dev/2tb/data/blockchain/besu/classic
set -euo pipefail

BESU_DIR="${BESU_DIR:-/media/dev/2tb/dev/besu}"
DATADIR="${DATADIR:-/media/dev/2tb/data/blockchain/besu/classic}"
GENESIS="$BESU_DIR/config/classic.json"
BINARY="$BESU_DIR/build/install/besu/bin/besu"

if [ ! -f "$BINARY" ]; then
  echo "ERROR: Besu binary not found at $BINARY"
  echo "Run './gradlew installDist -x test' in $BESU_DIR first."
  exit 1
fi

if [ ! -f "$GENESIS" ]; then
  echo "ERROR: Genesis file not found at $GENESIS"
  exit 1
fi

# Get core-geth enode automatically
COREGETH_ENODE=$(curl -sf -X POST -H 'Content-Type: application/json' \
  --data '{"jsonrpc":"2.0","method":"admin_nodeInfo","params":[],"id":1}' \
  http://localhost:8545 | jq -r '.result.enode' | sed 's/@[^:]*:/@127.0.0.1:/')

if [ -z "$COREGETH_ENODE" ] || [ "$COREGETH_ENODE" = "null" ]; then
  echo "ERROR: Could not get core-geth enode. Is core-geth running on port 8545?"
  echo "Start: cd /media/dev/2tb/dev/core-geth && ./run-classic.sh"
  exit 1
fi

echo "Core-geth enode: $COREGETH_ENODE"
echo "Genesis: $GENESIS"
echo "Data dir: $DATADIR"
echo "SNAP server: ENABLED (Fukuii can SNAP sync from this peer)"
echo ""

# JVM flags: cap heap at 3G to coexist with Fukuii (-Xmx8g) and core-geth (~1.2G)
# on a 32GB machine. Besu defaults to 25% of RAM (~8G) which causes OOM when
# all three clients run simultaneously.
export BESU_OPTS="${BESU_OPTS:--Xmx3g -Xms1g}"
echo "JVM opts: $BESU_OPTS"

exec "$BINARY" \
  --genesis-file="$GENESIS" \
  --data-path="$DATADIR" \
  --network-id=1 \
  --rpc-http-enabled \
  --rpc-http-host=0.0.0.0 \
  --rpc-http-port=8548 \
  --rpc-http-cors-origins="*" \
  --rpc-http-api=ADMIN,ETH,NET,WEB3 \
  --p2p-port=30304 \
  --data-storage-format=BONSAI \
  --sync-mode=SNAP \
  --sync-min-peers=1 \
  --snapsync-server-enabled \
  --bootnodes="$COREGETH_ENODE" \
  --logging=INFO \
  "$@"
