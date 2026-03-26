#!/bin/bash
# Fukuii SNAP syncs from local Besu on Mordor.
# Requires Besu running on port 30304 with --snapsync-server-enabled.
# Auto-configures static-nodes.json with Besu's enode.
set -euo pipefail

FUKUII_DIR="${FUKUII_DIR:-$(cd "$(dirname "$0")/../.." && pwd)}"
DATADIR="${DATADIR:-$HOME/.fukuii/mordor}"
JAR="$FUKUII_DIR/target/scala-3.3.4/fukuii-assembly-0.1.240.jar"

if [ ! -f "$JAR" ]; then
  echo "ERROR: JAR not found at $JAR"
  echo "Run 'sbt assembly' in $FUKUII_DIR first."
  exit 1
fi

# Get Besu enode automatically
BESU_ENODE=$(curl -sf -X POST -H 'Content-Type: application/json' \
  --data '{"jsonrpc":"2.0","method":"admin_nodeInfo","params":[],"id":1}' \
  http://localhost:8548 | jq -r '.result.enode' | sed 's/@[^:]*:/@127.0.0.1:/')

if [ -z "$BESU_ENODE" ] || [ "$BESU_ENODE" = "null" ]; then
  echo "ERROR: Could not get Besu enode. Is Besu running on port 8548?"
  echo "Start test-besu-full-snapserver.sh first."
  exit 1
fi

echo "Using Besu enode: $BESU_ENODE"

# Configure static-nodes.json for Fukuii to find Besu
mkdir -p "$DATADIR"
echo "[\"$BESU_ENODE\"]" > "$DATADIR/static-nodes.json"
echo "Wrote static-nodes.json: $(cat "$DATADIR/static-nodes.json")"

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
