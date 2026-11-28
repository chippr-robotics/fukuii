#!/usr/bin/env bash
# Starts the private ETC-config PoW net written by init.sh (spec 018 C6, T244), checks that every node is on the same
# genesis and chain id, and connects the nodes to each other.
#
# Usage: scripts/etc-private-net/start.sh --net-dir DIR [--node NAME]... [--heap SIZE] [--rpc-timeout S] [--no-connect]
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
# shellcheck source=scripts/etc-private-net/lib.sh
. "$HERE/lib.sh"

usage() {
  cat <<'EOF'
start.sh: start the private net's nodes, check their genesis, connect them.

Required:
  --net-dir DIR       The directory init.sh wrote.

Options:
  --node NAME         Start this node only (repeatable). Default: every node in nodes.tsv. On a net spread over
                      hosts, run start.sh on each host with that host's nodes, then connect.sh from anywhere that
                      reaches every node's JSON-RPC.
  --heap SIZE         fukuii -Xmx (default 2g).
  --rpc-timeout S     Seconds a node has to answer JSON-RPC after its launch (default 300).
  --no-connect        Do not wire the peers (connect.sh does it later).
  --dry-run           Print the launch commands; start nothing.
  -h, --help          This text.

Environment:
  GETH_EXTRA_ARGS     Extra arguments for core-geth, split on spaces.
  FUKUII_EXTRA_JAVA_OPTS  Extra JVM options for fukuii, split on spaces.
EOF
}

NET_DIR_ARG="" HEAP=2g RPC_WAIT=300 CONNECT=true DRY_RUN=false
SELECTED=()
while [ $# -gt 0 ]; do
  case "$1" in
    --net-dir) NET_DIR_ARG=$2; shift 2 ;;
    --node) SELECTED+=("$2"); shift 2 ;;
    --heap) HEAP=$2; shift 2 ;;
    --rpc-timeout) RPC_WAIT=$2; shift 2 ;;
    --no-connect) CONNECT=false; shift ;;
    --dry-run) DRY_RUN=true; shift ;;
    -h | --help) usage; exit 0 ;;
    *) usage >&2; die "unknown argument: $1" ;;
  esac
done
[ -n "$NET_DIR_ARG" ] || { usage >&2; die "--net-dir is required"; }
is_uint "$RPC_WAIT" || die "--rpc-timeout must be a number of seconds"
need_cmd jq curl python3
load_net "$(realpath -m "$NET_DIR_ARG")"

if [ ${#SELECTED[@]} -eq 0 ]; then mapfile -t SELECTED < <(node_names); fi
for n in "${SELECTED[@]}"; do node_kind "$n" >/dev/null; done

read -ra GETH_EXTRA <<<"${GETH_EXTRA_ARGS:-}"
read -ra JAVA_EXTRA <<<"${FUKUII_EXTRA_JAVA_OPTS:-}"

running() {
  local pidf
  pidf="$(node_dir "$1")/node.pid"
  [ -f "$pidf" ] && kill -0 "$(cat "$pidf")" 2>/dev/null
}

fukuii_cmd() {
  local n=$1
  CMD=("$JAVA_BIN" "-Xmx$HEAP" "${FUKUII_JVM_SAFETY_FLAGS[@]}" "${JAVA_EXTRA[@]}"
    "-Dlogback.configurationFile=$NET_DIR/logback.xml" "-Dfukuii.privnet.logfile=$(node_dir "$n")/node.log"
    "-Dconfig.file=$(node_dir "$n")/node.conf" -jar "$FUKUII_JAR")
}

geth_cmd() {
  local n=$1 host p2p rpcp mine coinbase d
  host=$(node_host "$n")
  p2p=$(node_field "$n" 4)
  rpcp=$(node_field "$n" 5)
  mine=$(node_field "$n" 7)
  coinbase=$(node_field "$n" 8)
  d=$(node_dir "$n")
  CMD=("$GETH_BIN" --datadir "$d/data" --networkid "$NETWORK_ID" --port "$p2p" --nodiscover --nat "extip:$host"
    --syncmode full --gcmode archive
    --http --http.addr "$host" --http.port "$rpcp" --http.api "eth,net,web3,admin,debug,miner,personal,txpool"
    --http.vhosts "*"
    --ethash.dagdir "$DAG_DIR/$n")
  if [ "$mine" = true ]; then CMD+=(--mine --miner.threads 1 --miner.etherbase "$coinbase"); fi
  CMD+=("${GETH_EXTRA[@]}")
}

STARTED=()
for n in "${SELECTED[@]}"; do
  d=$(node_dir "$n")
  if running "$n"; then
    log "$n is already running (pid $(cat "$d/node.pid")): left as it is"
    continue
  fi
  case "$(node_kind "$n")" in
    fukuii) fukuii_cmd "$n" ;;
    geth)
      geth_cmd "$n"
      if [ ! -d "$d/data/geth/chaindata" ]; then
        init_cmd=("$GETH_BIN" --datadir "$d/data" init "$NET_DIR/chains/geth-genesis.json")
        if [ "$DRY_RUN" = true ]; then
          echo "$n (init): ${init_cmd[*]}"
        else
          mkdir -p "$DAG_DIR/$n"
          "${init_cmd[@]}" >"$d/geth-init.log" 2>&1 || die "core-geth init failed: see $d/geth-init.log"
        fi
      fi
      ;;
    *) die "unknown node kind for $n" ;;
  esac
  if [ "$DRY_RUN" = true ]; then
    echo "$n: ${CMD[*]}"
    continue
  fi
  log "starting $n"
  # fukuii writes its log to node.log through NET_DIR/logback.xml; its stdout and stderr (JVM messages) go beside it.
  if [ "$(node_kind "$n")" = fukuii ]; then out="$d/node.stdout"; else out="$d/node.log"; fi
  nohup "${CMD[@]}" >>"$out" 2>&1 &
  echo $! >"$d/node.pid"
  STARTED+=("$n")
done
[ "$DRY_RUN" = true ] && exit 0

for n in "${STARTED[@]}"; do wait_rpc "$n" "$RPC_WAIT"; done

stop_started() {
  local n pid
  for n in "${STARTED[@]}"; do
    pid=$(cat "$(node_dir "$n")/node.pid")
    kill "$pid" 2>/dev/null || true
  done
}

# Same chain id and genesis on every node that answers: a mismatch is a config error, and mining on it wastes the run.
genesis="" first=""
for n in $(node_names); do
  url=$(node_rpc "$n")
  rpc "$url" web3_clientVersion >/dev/null 2>&1 || { warn "$n does not answer at $url: not checked"; continue; }
  cid=$(hex2dec "$(rpc_str "$url" eth_chainId)")
  if [ "$cid" != "$CHAIN_ID" ]; then
    stop_started
    die "$n reports chain id $cid, the net's is $CHAIN_ID: stopped the nodes this run started"
  fi
  g=$(block_field "$url" 0 hash) || { stop_started; die "$n has no genesis block over RPC"; }
  if [ -z "$genesis" ]; then
    genesis=$g first=$n
  elif [ "$g" != "$genesis" ]; then
    stop_started
    die "genesis differs: $first has $genesis, $n has $g. The core-geth genesis and fukuii's derived genesis do not" \
      "produce the same header (see init.sh's header). Stopped the nodes this run started"
  fi
done
log "genesis $genesis on every node that answers"
echo "$genesis" >"$NET_DIR/genesis.hash"

# load.sh moved miner1's coinbase to its funded account; a restart reads the config again, so it is put back.
if [ -f "$NET_DIR/load.addr" ] && printf '%s\n' "${STARTED[@]}" | grep -qx miner1; then
  rpc "$(node_rpc miner1)" miner_setEtherbase "[\"$(cat "$NET_DIR/load.addr")\"]" >/dev/null ||
    warn "could not put miner1's coinbase back on $(cat "$NET_DIR/load.addr")"
fi

if [ "$CONNECT" = true ]; then
  "$HERE/connect.sh" --net-dir "$NET_DIR"
fi
log "started: ${STARTED[*]:-none}"
