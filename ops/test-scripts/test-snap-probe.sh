#!/bin/bash
# Test SNAP probe mechanism with local Besu + core-geth on Mordor.
# Verifies that fresh Fukuii discovers and verifies snap-serving peers.
#
# Prerequisites:
#   - Besu running on port 30304 (HTTP RPC 8548) with BONSAI + --snapsync-server-enabled
#   - core-geth running on port 30303 (HTTP RPC 8545)
#   - Both synced to Mordor chain head
#   - JAR built: sbt assembly
#
# What to watch for in output:
#   SUCCESS: SNAP_PROBE_SENT, SNAP_PROBE_RESULT: isServingSnap=true, account range sync starts
#   FAILURE: "Falling back to fast sync", 0 verified snap peers
set -euo pipefail

FUKUII_DIR="/media/dev/2tb/dev/fukuii"
DATADIR="/media/dev/2tb/data/blockchain/fukuii/mordor"
JAR="$FUKUII_DIR/target/scala-3.3.4/fukuii-assembly-0.1.240.jar"

# --- Pre-checks ---
[ -f "$JAR" ] || { echo "ERROR: JAR not found at $JAR. Run 'sbt assembly'."; exit 1; }
command -v jq >/dev/null 2>&1 || { echo "ERROR: jq required"; exit 1; }

# --- Discover enodes dynamically ---
echo "=== Discovering peer enodes ==="

BESU_ENODE=$(curl -sf -X POST -H 'Content-Type: application/json' \
  --data '{"jsonrpc":"2.0","method":"admin_nodeInfo","params":[],"id":1}' \
  http://localhost:8548 2>/dev/null | jq -r '.result.enode' | sed 's/@[^:]*:/@127.0.0.1:/' || true)

GETH_ENODE=$(curl -sf -X POST -H 'Content-Type: application/json' \
  --data '{"jsonrpc":"2.0","method":"admin_nodeInfo","params":[],"id":1}' \
  http://localhost:8545 2>/dev/null | jq -r '.result.enode' | sed 's/@[^:]*:/@127.0.0.1:/' || true)

ENODES=""
if [ -n "$BESU_ENODE" ] && [ "$BESU_ENODE" != "null" ]; then
  echo "  Besu:      $BESU_ENODE"
  ENODES="\"$BESU_ENODE\""
else
  echo "  WARNING: Besu not reachable on port 8548"
fi

if [ -n "$GETH_ENODE" ] && [ "$GETH_ENODE" != "null" ]; then
  echo "  core-geth: $GETH_ENODE"
  [ -n "$ENODES" ] && ENODES="$ENODES,"
  ENODES="${ENODES}\"$GETH_ENODE\""
else
  echo "  WARNING: core-geth not reachable on port 8545"
fi

if [ -z "$ENODES" ]; then
  echo "ERROR: No peers found. Start Besu and/or core-geth first."
  exit 1
fi

# --- Verify Besu is serving snap ---
if [ -n "$BESU_ENODE" ] && [ "$BESU_ENODE" != "null" ]; then
  echo ""
  echo "=== Verifying Besu SNAP serving ==="
  BESU_BLOCK=$(curl -sf -X POST -H 'Content-Type: application/json' \
    --data '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}' \
    http://localhost:8548 | jq -r '.result')
  echo "  Besu block: $BESU_BLOCK"
fi

# --- Clean datadir ---
echo ""
echo "=== Preparing fresh datadir ==="
rm -rf "$DATADIR"
mkdir -p "$DATADIR/logs"

# --- Write static-nodes.json ---
cat > "$DATADIR/static-nodes.json" <<EOF
[$ENODES]
EOF
echo "  static-nodes.json: $(cat "$DATADIR/static-nodes.json")"

# --- Launch Fukuii ---
echo ""
echo "=== Starting Fukuii fresh SNAP sync ==="
echo "Watch for: SNAP_PROBE_SENT, SNAP_PROBE_RESULT, account range sync"
echo "Press Ctrl+C to stop"
echo ""

exec java -Xmx4g \
  -Dfukuii.datadir="$DATADIR" \
  -Dfukuii.network=mordor \
  -Dfukuii.network.rpc.http.interface=0.0.0.0 \
  -Dfukuii.network.rpc.http.port=8553 \
  -Dfukuii.network.server-address.port=30305 \
  -Dfukuii.network.discovery.port=30305 \
  -Dfukuii.sync.do-fast-sync=true \
  -Dfukuii.sync.do-snap-sync=true \
  -jar "$JAR" mordor
