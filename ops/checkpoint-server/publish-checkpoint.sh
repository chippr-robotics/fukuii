#!/usr/bin/env bash
#
# publish-checkpoint.sh — produce a .checkpoint archive and publish it to the
# chipprbots checkpoint distribution bucket (see
# docs/adr/consensus/CON-008-checkpoint-distribution-server.md).
#
# TEMPLATE: review and wire into your identity/secret management before running
# in production. It assumes `fukuii`, `gsutil`, `sha256sum`, and `jq` are on PATH,
# and (for signing) `cosign` with a key at $COSIGN_KEY.
#
# Workflow:
#   1. export state at a reorg-safe block  -> <block>-<shorthash>.checkpoint.gz
#   2. sha256 + read block hash
#   3. upload immutable versioned object (long cache)
#   4. regenerate + re-sign manifest.json
#   5. flip latest.checkpoint.gz LAST (so a half-published state is never advertised)
#   6. prune old versions, keeping the most recent --keep
#
set -euo pipefail

CHAIN=""
BLOCK=""                       # empty -> exporter uses current best block
BUCKET="gs://chipprbots-checkpoints"
DATADIR=""
KEEP=4
FUKUII_BIN="${FUKUII_BIN:-fukuii}"
WORKDIR="$(mktemp -d)"
trap 'rm -rf "$WORKDIR"' EXIT

usage() {
  cat <<EOF
Usage: $0 --chain <etc|mordor|sepolia|eth> [--block N] --bucket gs://... [--datadir DIR] [--keep N]

  --chain    Chain selector passed to the fukuii launcher (required)
  --block    Block number to export; reorg-safe (finalized / tip-margin). Omit = best block.
  --bucket   Destination GCS bucket root (default: $BUCKET)
  --datadir  fukuii datadir for this chain (sets -Dfukuii.datadir)
  --keep     How many versioned archives to retain per chain (default: $KEEP)

Env: FUKUII_BIN (default: fukuii), COSIGN_KEY (path to signing key; signing skipped if unset)
EOF
  exit "${1:-0}"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --chain)   CHAIN="$2"; shift 2 ;;
    --block)   BLOCK="$2"; shift 2 ;;
    --bucket)  BUCKET="$2"; shift 2 ;;
    --datadir) DATADIR="$2"; shift 2 ;;
    --keep)    KEEP="$2"; shift 2 ;;
    -h|--help) usage 0 ;;
    *) echo "unknown arg: $1" >&2; usage 1 ;;
  esac
done
[[ -n "$CHAIN" ]] || { echo "--chain is required" >&2; usage 1; }

JVM_OPTS=()
[[ -n "$DATADIR" ]] && JVM_OPTS+=("-Dfukuii.datadir=$DATADIR")

# --- 1. export ------------------------------------------------------------
RAW_OUT="$WORKDIR/${CHAIN}.checkpoint"
echo ">> exporting ${CHAIN} state${BLOCK:+ at block $BLOCK} ..."
EXPORT_ARGS=("$CHAIN" checkpoint export --output "$RAW_OUT" --gzip)
[[ -n "$BLOCK" ]] && EXPORT_ARGS+=(--block "$BLOCK")
JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} ${JVM_OPTS[*]:-}" "$FUKUII_BIN" "${EXPORT_ARGS[@]}"

GZ_OUT="${RAW_OUT}.gz"   # exporter auto-appends .gz when --gzip is set
[[ -f "$GZ_OUT" ]] || { echo "expected $GZ_OUT not found" >&2; exit 1; }

# --- 2. metadata ----------------------------------------------------------
# Block number + hash come from the manifest the exporter logs; in this template
# we re-read them via the JSON-RPC of the same node. Adjust to your environment.
: "${RPC_URL:=http://127.0.0.1:8546}"
hexblock=$(printf '0x%x' "${BLOCK:-0}")
if [[ -n "$BLOCK" ]]; then
  BLOCK_HASH=$(curl -fsS -X POST --data \
    "{\"jsonrpc\":\"2.0\",\"method\":\"eth_getBlockByNumber\",\"params\":[\"$hexblock\",false],\"id\":1}" \
    "$RPC_URL" | jq -r '.result.hash')
else
  echo "!! --block not given; set BLOCK explicitly so the published name/manifest is deterministic" >&2
  exit 1
fi
SHORT_HASH="${BLOCK_HASH:0:8}"
SHA256=$(sha256sum "$GZ_OUT" | cut -d' ' -f1)
SIZE=$(stat -c%s "$GZ_OUT")
OBJ_NAME="${BLOCK}-${SHORT_HASH}.checkpoint.gz"

echo ">> block=$BLOCK hash=$BLOCK_HASH size=$SIZE sha256=$SHA256 -> $OBJ_NAME"

# --- 3. upload immutable object ------------------------------------------
DEST="$BUCKET/$CHAIN/$OBJ_NAME"
gsutil -h "Cache-Control:public, max-age=31536000, immutable" \
       -h "Content-Type:application/octet-stream" \
       cp "$GZ_OUT" "$DEST"

# --- 4. regenerate + sign manifest ---------------------------------------
MANIFEST="$WORKDIR/manifest.json"
PUBLIC_BASE="https://checkpoints.chipprbots.com/$CHAIN"
# Merge the new entry into the existing manifest if present.
gsutil cp "$BUCKET/$CHAIN/manifest.json" "$MANIFEST" 2>/dev/null || echo '{"network":"'"$CHAIN"'","checkpoints":[]}' > "$MANIFEST"
jq --argjson n "$BLOCK" --arg h "$BLOCK_HASH" --arg f "$OBJ_NAME" \
   --arg u "$PUBLIC_BASE/$OBJ_NAME" --argjson s "$SIZE" --arg c "$SHA256" \
   --arg t "$(date -u +%Y-%m-%dT%H:%M:%SZ)" '
   .checkpoints = ([{blockNumber:$n, blockHash:$h, file:$f, url:$u, sizeBytes:$s, sha256:$c, gzip:true, createdAt:$t}]
                   + (.checkpoints // []))
                  | sort_by(-.blockNumber) | unique_by(.blockNumber)' \
   "$MANIFEST" > "$MANIFEST.new" && mv "$MANIFEST.new" "$MANIFEST"

gsutil -h "Cache-Control:public, max-age=300" -h "Content-Type:application/json" \
       cp "$MANIFEST" "$BUCKET/$CHAIN/manifest.json"

if [[ -n "${COSIGN_KEY:-}" ]]; then
  cosign sign-blob --yes --key "$COSIGN_KEY" --output-signature "$MANIFEST.sig" "$MANIFEST"
  gsutil -h "Cache-Control:public, max-age=300" cp "$MANIFEST.sig" "$BUCKET/$CHAIN/manifest.json.sig"
else
  echo "!! COSIGN_KEY unset — manifest published UNSIGNED (not for production)" >&2
fi

# --- 5. flip latest LAST --------------------------------------------------
# Implemented as a redirect object; the LB/CDN can also do this via URL map.
echo ">> pointing latest -> $OBJ_NAME"
gsutil -h "Cache-Control:public, max-age=300" \
       -h "x-goog-meta-redirect:$PUBLIC_BASE/$OBJ_NAME" \
       cp "$GZ_OUT" "$BUCKET/$CHAIN/latest.checkpoint.gz"
# NOTE: a metadata header alone does not redirect. In production prefer a CDN/LB
# URL rewrite for `latest`, or store latest as a tiny object served with a 302
# by the edge. Kept simple here; adapt to your LB config.

# --- 6. prune -------------------------------------------------------------
echo ">> pruning to newest $KEEP versions"
mapfile -t OLD < <(gsutil ls "$BUCKET/$CHAIN/" | grep -E '[0-9]+-0x[0-9a-f]+\.checkpoint\.gz$' | sort -t/ -k5 -V | head -n -"$KEEP")
for o in "${OLD[@]:-}"; do
  [[ -n "$o" ]] && { echo "   rm $o"; gsutil rm "$o"; }
done

echo ">> done: $DEST"
