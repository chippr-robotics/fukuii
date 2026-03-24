#!/bin/bash
# Fukuii Fast syncs from local Besu on Mordor (no SNAP).
# Uses separate datadir to avoid conflicts with SNAP test.
set -euo pipefail

FUKUII_DIR="/media/dev/2tb/dev/fukuii"
DATADIR="/media/dev/2tb/data/blockchain/fukuii/mordor-fast"
JAR="$FUKUII_DIR/target/scala-3.3.4/fukuii-assembly-0.1.240.jar"

if [ ! -f "$JAR" ]; then
  echo "ERROR: JAR not found. Run 'sbt assembly' first."
  exit 1
fi

BESU_ENODE=$(curl -sf -X POST -H 'Content-Type: application/json' \
  --data '{"jsonrpc":"2.0","method":"admin_nodeInfo","params":[],"id":1}' \
  http://localhost:8548 | jq -r '.result.enode' | sed 's/@[^:]*:/@127.0.0.1:/')

if [ -z "$BESU_ENODE" ] || [ "$BESU_ENODE" = "null" ]; then
  echo "ERROR: Could not get Besu enode. Start test-besu-full-snapserver.sh first."
  exit 1
fi

mkdir -p "$DATADIR"
echo "[\"$BESU_ENODE\"]" > "$DATADIR/static-nodes.json"
echo "Using Besu enode: $BESU_ENODE"

exec java -Xmx4g \
  -Dfukuii.datadir="$DATADIR" \
  -Dfukuii.network=mordor \
  -Dfukuii.network.rpc.http.interface=0.0.0.0 \
  -Dfukuii.network.rpc.http.port=8553 \
  -Dfukuii.network.server-address.port=30305 \
  -Dfukuii.network.discovery.port=30305 \
  -Dfukuii.sync.do-fast-sync=true \
  -Dfukuii.sync.do-snap-sync=false \
  -jar "$JAR" mordor
