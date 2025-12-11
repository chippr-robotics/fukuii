#!/usr/bin/env bash
# Cirith Ungol ETC mainnet smoketest harness

set -euo pipefail

require_cmd() {
  for cmd in "$@"; do
    if ! command -v "$cmd" >/dev/null 2>&1; then
      echo "Error: $cmd is required for the smoketest." >&2
      exit 1
    fi
  done
}

require_cmd docker curl jq

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}" )" && pwd)"
WORKDIR="$SCRIPT_DIR/.."
MODE=$(echo "${1:-fast}" | tr '[:upper:]' '[:lower:]')
if [[ "$MODE" != "fast" && "$MODE" != "snap" ]]; then
  echo "Error: Unsupported mode '$MODE'. Use fast or snap." >&2
  exit 1
fi

COMPOSE_CMD=(docker compose -f "$WORKDIR/docker-compose.yml")
CONTAINER="fukuii-cirith-ungol"
TIMESTAMP=$(date +%Y%m%d-%H%M%S)
ARTIFACT_DIR="$WORKDIR/smoketest-artifacts/${TIMESTAMP}-${MODE}"
mkdir -p "$ARTIFACT_DIR"

log() {
  echo "[$(date +%H:%M:%S)] $*"
}

rpc_call() {
  local method=$1
  local label=${2:-}
  local payload
  payload=$(jq -n --arg method "$method" '{jsonrpc:"2.0",id:1,method:$method,params:[]}' )
  local suffix=""
  if [[ -n "$label" ]]; then
    suffix="-$label"
  fi
  local output="$ARTIFACT_DIR/${method}${suffix}.json"
  curl -s -H "Content-Type: application/json" --data "$payload" http://localhost:8545 > "$output"
  cat "$output"
}

hex_to_dec() {
  local value=$1
  value=${value#0x}
  if [[ -z "$value" ]]; then
    echo 0
  else
    printf "%d" $((16#$value))
  fi
}

wait_for_health() {
  for _ in {1..30}; do
    local status
    status=$(docker inspect -f '{{.State.Health.Status}}' "$CONTAINER" 2>/dev/null || echo "starting")
    if [[ "$status" == "healthy" ]]; then
      return 0
    fi
    sleep 10
  done
  echo "Container failed to become healthy within 5 minutes" >&2
  return 1
}

log "Starting Cirith Ungol ($MODE) containers"
CIRITH_SYNC_MODE=${CIRITH_SYNC_MODE:-$MODE} "${COMPOSE_CMD[@]}" up -d >/dev/null

log "Waiting for container health"
wait_for_health

log "Capturing baseline metrics"
rpc_call eth_syncing before >/dev/null
PEERS_BEFORE_HEX=$(rpc_call net_peerCount before | jq -r '.result // "0x0"')
PEERS_BEFORE=$(hex_to_dec "$PEERS_BEFORE_HEX")
BLOCK_BEFORE_HEX=$(rpc_call eth_blockNumber before | jq -r '.result // "0x0"')
BLOCK_BEFORE=$(hex_to_dec "$BLOCK_BEFORE_HEX")

log "Sleeping to observe block progression"
sleep 60

log "Capturing follow-up metrics"
BLOCK_AFTER_HEX=$(rpc_call eth_blockNumber after | jq -r '.result // "0x0"')
BLOCK_AFTER=$(hex_to_dec "$BLOCK_AFTER_HEX")

if (( PEERS_BEFORE < 1 )); then
  echo "Smoketest failed: expected at least one peer, got $PEERS_BEFORE" >&2
  exit 1
fi

if (( BLOCK_AFTER <= BLOCK_BEFORE )); then
  echo "Smoketest failed: block height did not advance (before=$BLOCK_BEFORE after=$BLOCK_AFTER)" >&2
  exit 1
fi

log "Saving log excerpts"
"${COMPOSE_CMD[@]}" logs --tail 400 > "$ARTIFACT_DIR/docker-compose-tail.log"
docker logs "$CONTAINER" > "$ARTIFACT_DIR/${CONTAINER}.log" 2>&1

grep -Ei "FastSync|SNAP" "$ARTIFACT_DIR/${CONTAINER}.log" > "$ARTIFACT_DIR/sync-lines.log" || true

cat <<EOF > "$ARTIFACT_DIR/summary.txt"
Mode: $MODE
Timestamp: $TIMESTAMP
Peers (baseline): $PEERS_BEFORE
Block before: $BLOCK_BEFORE_HEX ($BLOCK_BEFORE)
Block after:  $BLOCK_AFTER_HEX ($BLOCK_AFTER)
Sync response: see eth_syncing-before.json
EOF

log "Smoketest succeeded. Artifacts saved to $ARTIFACT_DIR"