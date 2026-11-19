#!/usr/bin/env bash
# Produces a fork of a chosen depth on the private net (spec 018 C6, T244): one miner is cut off from every other node
# (admin_removePeer both ways, which also stops the re-dial), both sides mine at least DEPTH blocks past the split, and
# the links are restored. The lighter side then reorganises onto the heavier one. Concurrent mining gives depth-1 forks
# on its own; this makes depths 2 and 3 routine.
#
# The depth that results is measured, not assumed: before the links come back each side's canonical hashes are read
# from the split down, the fork point is the highest height where both sides agree, and the losing side's depth is its
# tip minus the fork point. One line is appended to NET_DIR/forks.tsv per run.
#
# Usage: scripts/etc-private-net/make-fork.sh --net-dir DIR --depth 1|2|3 [--isolate NAME] [--timeout S]
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
# shellcheck source=scripts/etc-private-net/lib.sh
. "$HERE/lib.sh"

usage() {
  cat <<'EOF'
make-fork.sh: cut one miner off, let both sides mine past the split, reconnect, and record the reorg depth.

Required:
  --net-dir DIR     The directory init.sh wrote.
  --depth D         Blocks each side mines past the split before the links come back (1 to 3).
Options:
  --isolate NAME    The miner cut off (default miner1). It must mine, and the other side must hold a miner too.
  --timeout S       Seconds allowed for each wait: the cut, the mining, the convergence (default 900).
  -h, --help        This text.
EOF
}

NET_DIR_ARG="" DEPTH="" ISOLATE=miner1 TIMEOUT=900
while [ $# -gt 0 ]; do
  case "$1" in
    --net-dir) NET_DIR_ARG=$2; shift 2 ;;
    --depth) DEPTH=$2; shift 2 ;;
    --isolate) ISOLATE=$2; shift 2 ;;
    --timeout) TIMEOUT=$2; shift 2 ;;
    -h | --help) usage; exit 0 ;;
    *) usage >&2; die "unknown argument: $1" ;;
  esac
done
[ -n "$NET_DIR_ARG" ] || { usage >&2; die "--net-dir is required"; }
[[ $DEPTH =~ ^[123]$ ]] || { usage >&2; die "--depth must be 1, 2 or 3"; }
is_uint "$TIMEOUT" || die "--timeout must be a number of seconds"
need_cmd jq curl python3
load_net "$(realpath -m "$NET_DIR_ARG")"

[ "$(node_field "$ISOLATE" 7)" = true ] || die "$ISOLATE does not mine: it cannot make its own branch"
mapfile -t OTHERS < <(node_names | grep -vx "$ISOLATE")
other_miner=""
for n in "${OTHERS[@]}"; do [ "$(node_field "$n" 7)" = true ] && other_miner=$n && break; done
[ -n "$other_miner" ] || die "no other node mines: the other side would not make a branch"

[ -f "$NET_DIR/enodes.tsv" ] || die "$NET_DIR/enodes.tsv missing: run connect.sh first"
enode() { awk -F'\t' -v n="$1" '$1==n{print $2}' "$NET_DIR/enodes.tsv"; }

ISO_URL=$(node_rpc "$ISOLATE")
OTHER_URL=$(node_rpc "$other_miner")

# Reconnects on any exit, so that a failed run does not leave the net partitioned.
restore() { "$HERE/connect.sh" --net-dir "$NET_DIR" --timeout "$TIMEOUT" >/dev/null 2>&1 || warn "reconnect failed: run connect.sh"; }
trap restore EXIT

log "cutting $ISOLATE off from ${OTHERS[*]}"
for n in "${OTHERS[@]}"; do
  rpc "$ISO_URL" admin_removePeer "[\"$(enode "$n")\"]" >/dev/null || die "$ISOLATE: admin_removePeer $n failed"
  rpc "$(node_rpc "$n")" admin_removePeer "[\"$(enode "$ISOLATE")\"]" >/dev/null ||
    die "$n: admin_removePeer $ISOLATE failed"
done
deadline=$((SECONDS + TIMEOUT))
until [ "$(rpc "$ISO_URL" admin_peers | jq 'length')" = 0 ]; do
  ((SECONDS < deadline)) || die "$ISOLATE still has peers ${TIMEOUT}s after the cut"
  sleep 1
done
SPLIT=$(head_number "$ISO_URL")
log "$ISOLATE is alone at block $SPLIT; waiting for both sides to mine $DEPTH block(s) past it"

deadline=$((SECONDS + TIMEOUT))
while :; do
  a=$(head_number "$ISO_URL")
  b=$(head_number "$OTHER_URL")
  [ "$a" -ge $((SPLIT + DEPTH)) ] && [ "$b" -ge $((SPLIT + DEPTH)) ] && break
  ((SECONDS < deadline)) || die "the sides did not both reach $((SPLIT + DEPTH)) within ${TIMEOUT}s ($ISOLATE $a, $other_miner $b)"
  sleep 1
done

# Each side's canonical hashes from its tip down to the fork point.
TIP_A=$(head_number "$ISO_URL")
TIP_B=$(head_number "$OTHER_URL")
low=$((SPLIT > 8 ? SPLIT - 8 : 0))
fork_point=-1
top=$((TIP_A < TIP_B ? TIP_A : TIP_B))
for ((h = top; h >= low; h--)); do
  if [ "$(block_field "$ISO_URL" "$h" hash)" = "$(block_field "$OTHER_URL" "$h" hash)" ]; then
    fork_point=$h
    break
  fi
done
[ "$fork_point" -ge 0 ] || die "the sides share no block between $low and $top: the cut did not happen where expected"
HASH_A=$(block_field "$ISO_URL" "$TIP_A" hash)
HASH_B=$(block_field "$OTHER_URL" "$TIP_B" hash)

log "reconnecting ($ISOLATE tip $TIP_A, $other_miner tip $TIP_B, fork point $fork_point)"
trap - EXIT
"$HERE/connect.sh" --net-dir "$NET_DIR" --timeout "$TIMEOUT"

# Converged when the isolated miner and the other side name the same block at the height of one side's old tip.
deadline=$((SECONDS + TIMEOUT))
winner=""
while [ -z "$winner" ]; do
  ha=$(block_field "$ISO_URL" "$TIP_A" hash || true)
  hb=$(block_field "$OTHER_URL" "$TIP_B" hash || true)
  ha_b=$(block_field "$OTHER_URL" "$TIP_A" hash || true)
  hb_a=$(block_field "$ISO_URL" "$TIP_B" hash || true)
  if [ "$hb_a" = "$HASH_B" ] && [ "$hb" = "$HASH_B" ]; then winner=$other_miner
  elif [ "$ha_b" = "$HASH_A" ] && [ "$ha" = "$HASH_A" ]; then winner=$ISOLATE
  fi
  [ -n "$winner" ] && break
  ((SECONDS < deadline)) || die "the sides did not converge on either tip within ${TIMEOUT}s"
  sleep 2
done
if [ "$winner" = "$ISOLATE" ]; then loser=$other_miner depth=$((TIP_B - fork_point)); else loser=$ISOLATE depth=$((TIP_A - fork_point)); fi

[ -f "$NET_DIR/forks.tsv" ] || printf '# utc\trequested_depth\tfork_point\tisolated\tisolated_tip\tother\tother_tip\twinner\tloser_reorg_depth\n' >"$NET_DIR/forks.tsv"
printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$(date -u +%FT%TZ)" "$DEPTH" "$fork_point" "$ISOLATE" "$TIP_A" \
  "$other_miner" "$TIP_B" "$winner" "$depth" >>"$NET_DIR/forks.tsv"
log "converged on $winner's branch: $loser reorganised $depth block(s) (recorded in $NET_DIR/forks.tsv)"
