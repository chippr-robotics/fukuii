#!/usr/bin/env bash
# Sends value transfers on the private net so that blocks carry gas (spec 018 C6, T244). ECIP-1111 credits the treasury
# baseFee * gasUsed: an empty block credits nothing, so "the treasury credit every block" (EG-6) needs transactions.
#
# The sender is a keystore account load.sh creates on miner1 (personal_newAccount, a random password kept in
# NET_DIR/load.password, mode 600) and funds by moving miner1's coinbase onto it (miner_setEtherbase). No key leaves the
# node. Each transaction is a legacy transfer of 1 wei to miner2's coinbase, gas 21000, at --gas-price.
#
# Usage: scripts/etc-private-net/load.sh --net-dir DIR --duration S [--interval S] [--gas-price WEI]
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
# shellcheck source=scripts/etc-private-net/lib.sh
. "$HERE/lib.sh"

usage() {
  cat <<'EOF'
load.sh: keep transactions flowing on the private net for a bounded time.

Required:
  --net-dir DIR       The directory init.sh wrote.
  --duration S        Seconds to send for; the script ends then.
Options:
  --interval S        Seconds between transactions (default 2).
  --gas-price WEI     Legacy gas price (default 10000000000 = 10 gwei; from Olympia it must cover the base fee,
                      at least the 1 gwei floor, plus the 1 gwei minimum tip).
  --fund-timeout S    Seconds to wait for the sender's first coinbase reward (default 1800).
  --max-errors N      Consecutive send failures that end the script with an error (default 20).
  -h, --help          This text.
EOF
}

NET_DIR_ARG="" DURATION="" INTERVAL=2 GAS_PRICE=10000000000 FUND_TIMEOUT=1800 MAX_ERRORS=20
while [ $# -gt 0 ]; do
  case "$1" in
    --net-dir) NET_DIR_ARG=$2; shift 2 ;;
    --duration) DURATION=$2; shift 2 ;;
    --interval) INTERVAL=$2; shift 2 ;;
    --gas-price) GAS_PRICE=$2; shift 2 ;;
    --fund-timeout) FUND_TIMEOUT=$2; shift 2 ;;
    --max-errors) MAX_ERRORS=$2; shift 2 ;;
    -h | --help) usage; exit 0 ;;
    *) usage >&2; die "unknown argument: $1" ;;
  esac
done
[ -n "$NET_DIR_ARG" ] || { usage >&2; die "--net-dir is required"; }
[ -n "$DURATION" ] || { usage >&2; die "--duration is required"; }
for v in "$DURATION" "$INTERVAL" "$GAS_PRICE" "$FUND_TIMEOUT" "$MAX_ERRORS"; do
  is_uint "$v" || die "numeric options must be non-negative integers, got '$v'"
done
need_cmd jq curl python3 openssl
load_net "$(realpath -m "$NET_DIR_ARG")"

M1=$(node_rpc miner1)
SINK=$(node_field miner2 8)

if [ ! -f "$NET_DIR/load.password" ]; then
  (umask 077 && openssl rand -hex 24 >"$NET_DIR/load.password")
fi
PW=$(cat "$NET_DIR/load.password")
if [ ! -f "$NET_DIR/load.addr" ]; then
  addr=$(rpc_str "$M1" personal_newAccount "[\"$PW\"]") || die "miner1: personal_newAccount failed"
  is_addr "$addr" || die "miner1: personal_newAccount returned '$addr'"
  echo "$addr" >"$NET_DIR/load.addr"
  log "created the sender $addr on miner1"
fi
FROM=$(cat "$NET_DIR/load.addr")
rpc "$M1" miner_setEtherbase "[\"$FROM\"]" >/dev/null || die "miner1: miner_setEtherbase $FROM failed"

need=$(pyexpr 'int(a[0]) * 21000 * 10' "$GAS_PRICE")
deadline=$((SECONDS + FUND_TIMEOUT))
while :; do
  bal=$(hex2dec "$(rpc_str "$M1" eth_getBalance "[\"$FROM\",\"latest\"]")")
  [ "$(pyexpr 'int(a[0]) >= int(a[1])' "$bal" "$need")" = True ] && break
  ((SECONDS < deadline)) || die "the sender $FROM holds $bal wei after ${FUND_TIMEOUT}s: miner1 has not mined onto it"
  sleep 5
done
log "sender $FROM holds $bal wei"

rpc "$M1" personal_unlockAccount "[\"$FROM\",\"$PW\",$((DURATION + 600))]" >/dev/null ||
  die "miner1: personal_unlockAccount failed"

tx=$(jq -cn --arg f "$FROM" --arg t "$SINK" --arg gp "$(printf '0x%x' "$GAS_PRICE")" \
  '{from:$f,to:$t,value:"0x1",gas:"0x5208",gasPrice:$gp}')
end=$((SECONDS + DURATION))
sent=0 errors=0
while ((SECONDS < end)); do
  if rpc "$M1" personal_sendTransaction "[$tx,\"$PW\"]" >/dev/null; then
    sent=$((sent + 1)) errors=0
  else
    errors=$((errors + 1))
    ((errors < MAX_ERRORS)) || die "$MAX_ERRORS sends in a row failed (sent $sent before)"
  fi
  sleep "$INTERVAL"
done
log "sent $sent transaction(s) over ${DURATION}s"
