#!/usr/bin/env bash
# Checks the running private net against EG-6 (spec 018 C6, T244; gate T258) over a window of recent blocks:
#   - every node on the same chain id and genesis; with Olympia at 0, the genesis carries a base fee (HefPostOlympia);
#   - every node, core-geth included, names the same block at every height of the window: each mined block was
#     imported by every other node with the root its miner sealed (the hash covers stateRoot, receiptsRoot and the
#     ommers), so "mined root equals import root" holds for every block in the window, ommers included;
#   - from Olympia: a base fee at or above the floor in every header, none before; the treasury's balance grows by
#     exactly baseFee * gasUsed at every block (ECIP-1111), read on a fukuii node and on core-geth;
#   - ECIP-1017: miner and ommer balances equal on a fukuii node and on core-geth (etc-differential/compare-balances.sh);
#   - the fukuii logs: no REORG-EXEC-FAIL, no rejected block ("batch import failed" at ERROR); the reorg depths seen
#     (REORG-SETTLE: oldHead - fork) and make-fork.sh's forks.tsv are summarised.
# Exit 0 only when every check passes. A value that cannot be read (a node down, a state not retained) is a failure.
#
# Usage: scripts/etc-private-net/check.sh --net-dir DIR [--window N] [--confirmations N]
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
# shellcheck source=scripts/etc-private-net/lib.sh
. "$HERE/lib.sh"

usage() {
  cat <<'EOF'
check.sh: EG-6 checks on the running private net, over the last --window blocks.

Required:
  --net-dir DIR        The directory init.sh wrote.
Options:
  --window N           Blocks checked, ending --confirmations below the lowest head (default 32). The fukuii nodes
                       must still hold the state of every block in it (pruning history 64 by default).
  --confirmations N    Blocks below the lowest head left out, still settling (default 6).
  -h, --help           This text.
EOF
}

NET_DIR_ARG="" WINDOW=32 CONF=6
while [ $# -gt 0 ]; do
  case "$1" in
    --net-dir) NET_DIR_ARG=$2; shift 2 ;;
    --window) WINDOW=$2; shift 2 ;;
    --confirmations) CONF=$2; shift 2 ;;
    -h | --help) usage; exit 0 ;;
    *) usage >&2; die "unknown argument: $1" ;;
  esac
done
[ -n "$NET_DIR_ARG" ] || { usage >&2; die "--net-dir is required"; }
{ is_uint "$WINDOW" && is_uint "$CONF"; } || die "--window and --confirmations take integers"
need_cmd jq curl python3
load_net "$(realpath -m "$NET_DIR_ARG")"

FAILS=0
fail() { printf 'FAIL  %s\n' "$*"; FAILS=$((FAILS + 1)); }
pass() { printf 'ok    %s\n' "$*"; }
note() { printf 'note  %s\n' "$*"; }

mapfile -t NODES < <(node_names)
FUKUII_REF="" GETH_REF=""
for n in "${NODES[@]}"; do
  case "$(node_kind "$n")" in
    fukuii) [ -n "$FUKUII_REF" ] || FUKUII_REF=$n ;;
    geth) GETH_REF=$n ;;
  esac
done

# --- Chain id, genesis, heads ---------------------------------------------------------------------------------------
genesis="" low=""
for n in "${NODES[@]}"; do
  url=$(node_rpc "$n")
  if ! rpc "$url" web3_clientVersion >/dev/null 2>&1; then fail "$n does not answer at $url"; continue; fi
  cid=$(hex2dec "$(rpc_str "$url" eth_chainId)")
  [ "$cid" = "$CHAIN_ID" ] || fail "$n: chain id $cid, expected $CHAIN_ID"
  g=$(block_field "$url" 0 hash)
  if [ -z "$genesis" ]; then genesis=$g; elif [ "$g" != "$genesis" ]; then fail "$n: genesis $g differs from $genesis"; fi
  if [ "$OLYMPIA_BLOCK" = 0 ]; then
    bf=$(block_field "$url" 0 baseFeePerGas || true)
    if [ -n "$bf" ]; then pass "$n: genesis carries baseFeePerGas $bf (HefPostOlympia)"
    else fail "$n: Olympia at 0 but the genesis has no baseFeePerGas"; fi
  fi
  h=$(head_number "$url")
  note "$n: head $h"
  if [ -z "$low" ] || [ "$h" -lt "$low" ]; then low=$h; fi
done
[ "$FAILS" = 0 ] || { echo "$FAILS failure(s) before the window checks"; exit 1; }

TOP=$((low - CONF))
BOTTOM=$((TOP - WINDOW + 1))
[ "$BOTTOM" -ge 1 ] || die "the net is too short for the window: lowest head $low, window $WINDOW, confirmations $CONF"
note "window: blocks $BOTTOM..$TOP"

# --- Same block at every height on every node ----------------------------------------------------------------------
declare -A BY_MINER=()
ommer_blocks=0 disagree=0
REF_URL=$(node_rpc "${NODES[0]}")
for ((b = BOTTOM; b <= TOP; b++)); do
  ref=$(rpc "$REF_URL" eth_getBlockByNumber "[\"$(printf '0x%x' "$b")\",false]")
  ref_hash=$(jq -r .hash <<<"$ref")
  for n in "${NODES[@]:1}"; do
    h=$(block_field "$(node_rpc "$n")" "$b" hash || echo missing)
    if [ "$h" != "$ref_hash" ]; then
      fail "block $b: ${NODES[0]} has $ref_hash, $n has $h"
      disagree=$((disagree + 1))
    fi
  done
  m=$(jq -r '.miner | ascii_downcase' <<<"$ref")
  BY_MINER[$m]=$((${BY_MINER[$m]:-0} + 1))
  [ "$(jq '.uncles | length' <<<"$ref")" -gt 0 ] && ommer_blocks=$((ommer_blocks + 1))
done
[ "$disagree" = 0 ] && pass "every node names the same block at each of the $WINDOW heights (mined root = import root)"
for n in "${NODES[@]}"; do
  [ "$(node_field "$n" 7)" = true ] || continue
  addrs=("$(node_field "$n" 8 | tr '[:upper:]' '[:lower:]')")
  if [ "$n" = miner1 ] && [ -f "$NET_DIR/load.addr" ]; then
    addrs+=("$(tr '[:upper:]' '[:lower:]' <"$NET_DIR/load.addr")")
  fi
  c=0
  for addr in "${addrs[@]}"; do c=$((c + ${BY_MINER[$addr]:-0})); done
  if [ "$c" -gt 0 ]; then note "$n: $c canonical block(s) in the window"; else note "$n: no canonical block in the window"; fi
done
if [ "$ommer_blocks" -gt 0 ]; then
  pass "$ommer_blocks block(s) in the window carry ommers, imported alike on every node"
else
  note "no block in the window carries an ommer: the ommer path is not exercised by this window (widen it or run make-fork.sh)"
fi

# --- Olympia: base fee and the treasury ----------------------------------------------------------------------------
t_mismatch=0 empty=0 credited=0
for ((b = BOTTOM; b <= TOP; b++)); do
  for n in "$FUKUII_REF" ${GETH_REF:+"$GETH_REF"}; do
    url=$(node_rpc "$n")
    bf=$(block_field "$url" "$b" baseFeePerGas || true)
    if [ "$b" -lt "$OLYMPIA_BLOCK" ]; then
      [ -z "$bf" ] || { fail "$n block $b: a base fee ($bf) before Olympia ($OLYMPIA_BLOCK)"; continue; }
      continue
    fi
    [ -n "$bf" ] || { fail "$n block $b: no base fee from Olympia on"; continue; }
    bfd=$(hex2dec "$bf")
    [ "$(pyexpr 'int(a[0]) >= int(a[1])' "$bfd" "$BASE_FEE_FLOOR")" = True ] ||
      fail "$n block $b: base fee $bfd below the floor $BASE_FEE_FLOOR"
    gu=$(hex2dec "$(block_field "$url" "$b" gasUsed)")
    t0=$(hex2dec "$(rpc_str "$url" eth_getBalance "[\"$TREASURY\",\"$(printf '0x%x' $((b - 1)))\"]")") ||
      { fail "$n: treasury balance at $((b - 1)) not readable (state not retained?)"; continue; }
    t1=$(hex2dec "$(rpc_str "$url" eth_getBalance "[\"$TREASURY\",\"$(printf '0x%x' "$b")\"]")") ||
      { fail "$n: treasury balance at $b not readable"; continue; }
    if [ "$(pyexpr 'int(a[1]) - int(a[0]) == int(a[2]) * int(a[3])' "$t0" "$t1" "$bfd" "$gu")" != True ]; then
      fail "$n block $b: treasury grew by $(pyexpr 'int(a[1]) - int(a[0])' "$t0" "$t1"), expected baseFee $bfd * gasUsed $gu"
      t_mismatch=$((t_mismatch + 1))
    elif [ "$n" = "$FUKUII_REF" ]; then
      if [ "$gu" = 0 ]; then empty=$((empty + 1)); else credited=$((credited + 1)); fi
    fi
  done
done
if [ "$TOP" -ge "$OLYMPIA_BLOCK" ] && [ "$t_mismatch" = 0 ]; then
  pass "treasury credit = baseFee * gasUsed at every Olympia block in the window ($credited credited, $empty empty)"
  [ "$empty" = 0 ] || note "$empty empty block(s) credited nothing: run load.sh so that every block carries gas"
elif [ "$TOP" -lt "$OLYMPIA_BLOCK" ]; then
  note "the window ends before Olympia ($OLYMPIA_BLOCK): no Olympia check ran"
fi

# --- ECIP-1017: miner and ommer balances, fukuii against core-geth -------------------------------------------------
if [ -n "$GETH_REF" ]; then
  if "$HERE/../etc-differential/compare-balances.sh" diff --rpc "$(node_rpc "$FUKUII_REF")" \
    --rpc-b "$(node_rpc "$GETH_REF")" --range "$BOTTOM-$TOP" >"$NET_DIR/check-balances.diff" 2>&1; then
    pass "miner and ommer balances equal on $FUKUII_REF and $GETH_REF over the window"
  else
    fail "miner and ommer balances differ between $FUKUII_REF and $GETH_REF: $NET_DIR/check-balances.diff"
  fi
fi

# --- fukuii logs ---------------------------------------------------------------------------------------------------
for n in "${NODES[@]}"; do
  [ "$(node_kind "$n")" = fukuii ] || continue
  lf="$(node_dir "$n")/node.log"
  [ -f "$lf" ] || { fail "$n: no log at $lf"; continue; }
  ef=$(grep -c 'REORG-EXEC-FAIL' "$lf" || true)
  # An orphan batch logs the same text at WARN (a gap of this node's, B-007); a rejected block logs it at ERROR.
  rj=$(grep -cE ' ERROR .*batch import failed' "$lf" || true)
  if [ "$ef" = 0 ]; then pass "$n: no REORG-EXEC-FAIL"; else fail "$n: $ef REORG-EXEC-FAIL line(s)"; fi
  if [ "$rj" = 0 ]; then pass "$n: no rejected block"; else fail "$n: $rj rejected block(s) ('batch import failed' at ERROR)"; fi
  depths=$(sed -nE 's/.*REORG-SETTLE: fork=([0-9]+) oldHead=([0-9]+) .*/\2 \1/p' "$lf" |
    awk '{ d = $1 - $2; c[d]++ } END { for (k in c) printf "depth %d: %d  ", k, c[k] }')
  note "$n: reorgs seen (oldHead - fork): ${depths:-none}"
done
if [ -f "$NET_DIR/forks.tsv" ]; then
  note "make-fork.sh runs (loser's reorg depth: count): $(awk -F'\t' '!/^#/ { c[$9]++ } END { for (k in c) printf "%s: %d  ", k, c[k] }' "$NET_DIR/forks.tsv")"
fi

echo
if [ "$FAILS" = 0 ]; then
  echo "PASS: every check over blocks $BOTTOM..$TOP"
else
  echo "FAIL: $FAILS failure(s)"
  exit 1
fi
