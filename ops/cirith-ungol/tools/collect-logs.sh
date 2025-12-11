#!/usr/bin/env bash
# Cirith Ungol ETC Mainnet Testbed - Log Collection Helper
# Captures logs, health, and sync telemetry for validation runs

set -euo pipefail

for cmd in docker curl jq; do
  if ! command -v "$cmd" >/dev/null 2>&1; then
    echo "Error: $cmd is required for log collection." >&2
    exit 1
  fi
done

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORKDIR="$SCRIPT_DIR/.."
cd "$WORKDIR"

OUTPUT_DIR=${1:-"captured-logs"}
TIMESTAMP=$(date +%Y%m%d-%H%M%S)
RUN_DIR="$OUTPUT_DIR/$TIMESTAMP"
mkdir -p "$RUN_DIR"

CONTAINER="fukuii-cirith-ungol"
COMPOSE="docker compose -f docker-compose.yml"

log() {
  echo -e "[$(date +%H:%M:%S)] $*"
}

log "Collecting runtime status..."
$COMPOSE ps > "$RUN_DIR/containers-status.txt"
$COMPOSE logs --tail 200 > "$RUN_DIR/docker-compose-tail.log"

docker inspect "$CONTAINER" > "$RUN_DIR/${CONTAINER}-inspect.json" 2>/dev/null || true

log "Capturing full log stream from $CONTAINER"
docker logs "$CONTAINER" > "$RUN_DIR/${CONTAINER}.log" 2>&1 || true

log "Capturing sync telemetry via JSON-RPC"
cat > "$RUN_DIR/rpc-checks.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
rpc() {
  method=$1
  payload=$(jq -n --arg method "$method" '{jsonrpc:"2.0",id:1,method:$method,params:[]}' )
  curl -s -H "Content-Type: application/json" --data "$payload" http://localhost:8545 || true
}

rpc eth_syncing | tee sync-status.json
rpc net_peerCount | tee peer-count.json
rpc eth_blockNumber | tee head-block.json
EOF
chmod +x "$RUN_DIR/rpc-checks.sh"
( cd "$RUN_DIR" && ./rpc-checks.sh )

log "Extracting SNAP/Fast sync indicators"
grep -E "SNAP|FastSync|pivot|GetAccountRange" "$RUN_DIR/${CONTAINER}.log" > "$RUN_DIR/sync-highlights.log" || true

echo "Log bundle ready at $RUN_DIR"
