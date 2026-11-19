#!/usr/bin/env bash
# Rewards and era-boundary differential helper (spec 018 C6, T246; feeds T238 / B-381 and EG-1, EG-6).
#
# For every block in the ranges given, takes the block's miner and the miner of each of its ommers, and reads each
# address's balance at the block's parent and at the block. Those are the accounts ECIP-1017 pays (the block reward,
# the 1/32 nephew bonus, the ommer reward); a reward computed differently anywhere in the range shows as a difference.
#
#   record   reads the values from one node (the pinned jar's: specs/018-storage-engine-consolidation/baselines/
#            pinned-jar.md, run with its default Hash-scheme config) into a TSV file.
#   compare  reads the same rows from a node under test and fails on any difference with a recorded file.
#   diff     reads both from two live nodes (e.g. fukuii and core-geth on the private net) and fails on a difference.
#
# The node read needs the state at every block of the range and at the block before it: an archive node, or a range
# inside the node's retained history. A balance the node cannot serve is an error, never a pass.
#
# Usage:
#   compare-balances.sh record  --rpc URL --out FILE --label TEXT (--boundary N | --range A-B)... [--window W]
#   compare-balances.sh compare --rpc URL --recorded FILE
#   compare-balances.sh diff    --rpc URL --rpc-b URL (--boundary N | --range A-B)... [--window W]
set -euo pipefail

usage() {
  cat <<'EOF'
compare-balances.sh: miner and ommer balances across ECIP-1017 era boundaries, recorded and compared.

Modes:
  record   --rpc URL --out FILE --label TEXT RANGES   Record from the reference node (the pinned jar's).
  compare  --rpc URL --recorded FILE                  Compare a node with a recorded file (its own ranges).
  diff     --rpc URL --rpc-b URL RANGES               Compare two live nodes.

Ranges (at least one, repeatable):
  --boundary N    The blocks N-W .. N+W around an era boundary. ETC mainnet's first is 5000000 (era-duration
                  5,000,000 in etc-chain.conf). Mordor's era-duration is 2,000,000 (mordor-chain.conf): name its
                  boundaries explicitly.
  --range A-B     The blocks A .. B inclusive.
  --window W      Half-width for --boundary (default 64).

Other:
  --label TEXT    record: what the reference node runs (the jar's version and SHA-256, its config); written to the
                  file and shown by compare. Required: a recording that does not say what made it is not evidence.
  --dry-run       Print the blocks that would be read; read nothing.
  -h, --help      This text.

Exit status: 0 when every row matches (or was recorded), 1 on any difference or read failure, 2 on a usage error.
EOF
}

log() { printf '[%s] %s\n' "$(date -u +%H:%M:%S)" "$*" >&2; }
die() {
  printf '[%s] ERROR: %s\n' "$(date -u +%H:%M:%S)" "$*" >&2
  exit 1
}
usage_error() {
  usage >&2
  printf 'ERROR: %s\n' "$*" >&2
  exit 2
}

rpc() {
  local url=$1 method=$2 params=${3:-[]} body out
  body=$(jq -cn --arg m "$method" --argjson p "$params" '{jsonrpc:"2.0",id:1,method:$m,params:$p}')
  out=$(curl -sS --max-time "${RPC_TIMEOUT_S:-60}" -H 'Content-Type: application/json' -d "$body" "$url") ||
    { echo "rpc $method at $url failed" >&2; return 1; }
  jq -e 'has("result") and (has("error") | not)' >/dev/null 2>&1 <<<"$out" ||
    { echo "rpc $method at $url: ${out:0:300}" >&2; return 1; }
  jq -c '.result' <<<"$out"
}

[ $# -ge 1 ] || usage_error "a mode is required"
MODE=$1
shift
case "$MODE" in
  record | compare | diff) ;;
  -h | --help) usage; exit 0 ;;
  *) usage_error "unknown mode '$MODE'" ;;
esac

RPC_A="" RPC_B="" OUT="" RECORDED="" LABEL="" WINDOW=64 DRY_RUN=false
RANGES=()
while [ $# -gt 0 ]; do
  case "$1" in
    --rpc) RPC_A=$2; shift 2 ;;
    --rpc-b) RPC_B=$2; shift 2 ;;
    --out) OUT=$2; shift 2 ;;
    --recorded) RECORDED=$2; shift 2 ;;
    --label) LABEL=$2; shift 2 ;;
    --boundary) RANGES+=("b:$2"); shift 2 ;;
    --range) RANGES+=("r:$2"); shift 2 ;;
    --window) WINDOW=$2; shift 2 ;;
    --dry-run) DRY_RUN=true; shift ;;
    -h | --help) usage; exit 0 ;;
    *) usage_error "unknown argument: $1" ;;
  esac
done

for c in jq curl python3; do command -v "$c" >/dev/null 2>&1 || die "'$c' is required"; done
[[ $WINDOW =~ ^[0-9]+$ ]] || usage_error "--window must be a non-negative integer"
[ -n "$RPC_A" ] || usage_error "--rpc is required"
case "$MODE" in
  record)
    [ -n "$OUT" ] || usage_error "record needs --out"
    [ -n "$LABEL" ] || usage_error "record needs --label (what the reference node runs)"
    [ ${#RANGES[@]} -gt 0 ] || usage_error "record needs at least one --boundary or --range"
    [ ! -e "$OUT" ] || die "--out $OUT exists: a recording is never overwritten"
    ;;
  compare)
    [ -n "$RECORDED" ] || usage_error "compare needs --recorded"
    [ -f "$RECORDED" ] || die "--recorded $RECORDED is not a file"
    [ ${#RANGES[@]} -eq 0 ] || usage_error "compare reads its blocks from the recorded file; drop --boundary/--range"
    ;;
  diff)
    [ -n "$RPC_B" ] || usage_error "diff needs --rpc-b"
    [ ${#RANGES[@]} -gt 0 ] || usage_error "diff needs at least one --boundary or --range"
    ;;
esac

# The block numbers, sorted and unique.
blocks() {
  local spec a b
  for spec in "${RANGES[@]}"; do
    case "$spec" in
      b:*)
        b=${spec#b:}
        [[ $b =~ ^[0-9]+$ ]] || usage_error "--boundary takes a block number, got '$b'"
        a=$((b > WINDOW ? b - WINDOW : 1))
        seq "$a" $((b + WINDOW))
        ;;
      r:*)
        [[ ${spec#r:} =~ ^([0-9]+)-([0-9]+)$ ]] || usage_error "--range takes A-B, got '${spec#r:}'"
        a=${BASH_REMATCH[1]} b=${BASH_REMATCH[2]}
        [ "$a" -le "$b" ] || usage_error "--range $a-$b is empty"
        [ "$a" -ge 1 ] || a=1
        seq "$a" "$b"
        ;;
    esac
  done | sort -n -u
}

hex() { printf '0x%x' "$1"; }
dec() { python3 -I -c 'import sys; print(int(sys.argv[1], 16))' "$1"; }

# rows URL: one TSV row per (block, paid account): block hash role address balance_parent balance_block
rows() {
  local url=$1 n blk hash miner nunc i unc
  while read -r n; do
    blk=$(rpc "$url" eth_getBlockByNumber "[\"$(hex "$n")\",false]") || return 1
    [ "$blk" != null ] || { echo "block $n is not on the node at $url" >&2; return 1; }
    hash=$(jq -r .hash <<<"$blk")
    miner=$(jq -r '.miner | ascii_downcase' <<<"$blk")
    printf '%s\t%s\tminer\t%s\n' "$n" "$hash" "$miner"
    nunc=$(jq '.uncles | length' <<<"$blk")
    for ((i = 0; i < nunc; i++)); do
      unc=$(rpc "$url" eth_getUncleByBlockNumberAndIndex "[\"$(hex "$n")\",\"$(hex "$i")\"]") || return 1
      [ "$unc" != null ] || { echo "ommer $i of block $n is not served by $url" >&2; return 1; }
      printf '%s\t%s\tommer%d\t%s\n' "$n" "$hash" "$i" "$(jq -r '.miner | ascii_downcase' <<<"$unc")"
    done
  done
}

# balances URL: reads rows from stdin (block hash role address ...), appends the two balances.
balances() {
  local url=$1 n hash role addr rest b0 b1
  while IFS=$'\t' read -r n hash role addr rest; do
    b0=$(rpc "$url" eth_getBalance "[\"$addr\",\"$(hex $((n - 1)))\"]") || return 1
    b1=$(rpc "$url" eth_getBalance "[\"$addr\",\"$(hex "$n")\"]") || return 1
    printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$n" "$hash" "$role" "$addr" "$(dec "$(jq -r . <<<"$b0")")" \
      "$(dec "$(jq -r . <<<"$b1")")"
  done
}

node_header() {
  local url=$1
  printf '# node: %s\n' "$(rpc "$url" web3_clientVersion | jq -r .)"
  printf '# chain id: %s\n' "$(dec "$(rpc "$url" eth_chainId | jq -r .)")"
  printf '# genesis: %s\n' "$(rpc "$url" eth_getBlockByNumber '["0x0",false]' | jq -r .hash)"
}

if [ "$DRY_RUN" = true ]; then
  case "$MODE" in
    compare) grep -v '^#' "$RECORDED" | tail -n +2 | cut -f1 | sort -n -u | tr '\n' ' ' ;;
    *) blocks | tr '\n' ' ' ;;
  esac
  echo
  exit 0
fi

# A node that does not answer is named once, here, instead of failing row by row.
for u in "$RPC_A" ${RPC_B:+"$RPC_B"}; do
  rpc "$u" web3_clientVersion >/dev/null 2>&1 || die "no JSON-RPC answer at $u (web3_clientVersion)"
done

COLS=$'block\thash\trole\taddress\tbalance_parent_wei\tbalance_block_wei'
case "$MODE" in
  record)
    tmp="$OUT.partial"
    {
      printf '# compare-balances.sh record, %s\n' "$(date -u +%FT%TZ)"
      printf '# label: %s\n' "$LABEL"
      node_header "$RPC_A"
      printf '# ranges: %s (window %s)\n' "${RANGES[*]}" "$WINDOW"
      printf '%s\n' "$COLS"
      blocks | rows "$RPC_A" | balances "$RPC_A"
    } >"$tmp" || { rm -f "$tmp"; die "recording failed (see above); nothing written"; }
    mv "$tmp" "$OUT"
    log "recorded $(($(grep -vc '^#' "$OUT") - 1)) row(s) to $OUT"
    exit 0
    ;;
esac

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
if [ "$MODE" = compare ]; then
  ref_genesis=$(awk '/^# genesis: / { print $3 }' "$RECORDED")
  got_genesis=$(rpc "$RPC_A" eth_getBlockByNumber '["0x0",false]' | jq -r .hash)
  [ "$ref_genesis" = "$got_genesis" ] || die "the node's genesis $got_genesis is not the recording's $ref_genesis"
  grep -v '^#' "$RECORDED" | tail -n +2 >"$WORK/a.tsv"
  # The node under test is read for the recorded (block, role, address) rows, so a different miner shows as a
  # different address in the row, not as a missing row.
  cut -f1 "$WORK/a.tsv" | sort -n -u | rows "$RPC_A" | balances "$RPC_A" >"$WORK/b.tsv" ||
    die "reading the node under test failed"
  A_NAME="recorded ($(awk -F': ' '/^# label: / { print $2 }' "$RECORDED"))"
  B_NAME=$RPC_A
else
  blocks | rows "$RPC_A" | balances "$RPC_A" >"$WORK/a.tsv" || die "reading $RPC_A failed"
  blocks | rows "$RPC_B" | balances "$RPC_B" >"$WORK/b.tsv" || die "reading $RPC_B failed"
  A_NAME=$RPC_A
  B_NAME=$RPC_B
fi

total=$(wc -l <"$WORK/a.tsv")
if diff -u --label "$A_NAME" --label "$B_NAME" "$WORK/a.tsv" "$WORK/b.tsv" >"$WORK/diff"; then
  log "PASS: $total row(s) equal (miner and ommer balances at each block's parent and at the block)"
  exit 0
fi
cat "$WORK/diff"
die "FAIL: the balances differ (rows above; columns: $COLS)"
