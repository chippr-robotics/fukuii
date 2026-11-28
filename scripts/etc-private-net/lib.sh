# shellcheck shell=bash
# Shared helpers for scripts/etc-private-net/*.sh (spec 018 C6, T244). Sourced, never run.
#
# Every wait in these scripts has a bound (a timeout flag) and fails loudly when the bound is reached: a script that
# cannot reach the state it waits for exits non-zero and says what it was waiting for.

# The JVM flags every fukuii launch carries: JDK-8385993 (C2 ReduceAllocationMerges miscompile on JDK 23+), the same
# three lines as .jvmopts and hive/fukuii/fukuii.sh. IgnoreUnrecognizedVMOptions keeps JDK 21 starting.
# shellcheck disable=SC2034 # used by the scripts that source this file
FUKUII_JVM_SAFETY_FLAGS=(-XX:+IgnoreUnrecognizedVMOptions -XX:+UnlockDiagnosticVMOptions -XX:-ReduceAllocationMerges)

# The network name the generated chain file is loaded under (<custom-chains-dir>/etcpriv-chain.conf).
# shellcheck disable=SC2034 # used by the scripts that source this file
PRIVNET_NAME=etcpriv

log() { printf '[%s] %s\n' "$(date -u +%H:%M:%S)" "$*" >&2; }
warn() { printf '[%s] WARNING: %s\n' "$(date -u +%H:%M:%S)" "$*" >&2; }
die() {
  printf '[%s] ERROR: %s\n' "$(date -u +%H:%M:%S)" "$*" >&2
  exit 1
}

need_cmd() {
  local c
  for c in "$@"; do
    command -v "$c" >/dev/null 2>&1 || die "'$c' is required and is not on PATH"
  done
}

# rpc URL METHOD [PARAMS_JSON] -> prints the JSON of .result (compact). Fails (status 1, message on stderr) on a
# transport error or a JSON-RPC error object.
rpc() {
  local url=$1 method=$2 params=${3:-[]} body out
  body=$(jq -cn --arg m "$method" --argjson p "$params" '{jsonrpc:"2.0",id:1,method:$m,params:$p}')
  if ! out=$(curl -sS --max-time "${RPC_TIMEOUT_S:-30}" -H 'Content-Type: application/json' -d "$body" "$url" 2>&1); then
    echo "rpc $method at $url: $out" >&2
    return 1
  fi
  if ! jq -e . >/dev/null 2>&1 <<<"$out"; then
    echo "rpc $method at $url: not JSON: ${out:0:200}" >&2
    return 1
  fi
  if jq -e 'has("error")' >/dev/null <<<"$out"; then
    echo "rpc $method at $url: $(jq -c .error <<<"$out")" >&2
    return 1
  fi
  jq -c '.result' <<<"$out"
}

# rpc_str: as rpc, with the JSON string unquoted.
rpc_str() {
  local r
  r=$(rpc "$@") || return 1
  jq -r . <<<"$r"
}

# Big-integer arithmetic goes through python3: balances exceed 64 bits and jq reads numbers as doubles.
hex2dec() { python3 -I -c 'import sys; print(int(sys.argv[1], 16))' "$1"; }
# pyexpr EXPR [ARGS...]: evaluates a python expression with the arguments as sys.argv[1:] (strings).
pyexpr() {
  local e=$1
  shift
  python3 -I -c "import sys; a=sys.argv[1:]; print($e)" "$@"
}

is_uint() { [[ $1 =~ ^[0-9]+$ ]]; }
is_addr() { [[ $1 =~ ^0x[0-9a-fA-F]{40}$ ]]; }

# load_net NET_DIR: reads the files init.sh wrote. Sets NET_DIR and the variables of net.env.
load_net() {
  NET_DIR=$1
  [ -n "$NET_DIR" ] || die "--net-dir is required"
  [ -f "$NET_DIR/net.env" ] || die "$NET_DIR/net.env not found: run init.sh first"
  [ -f "$NET_DIR/nodes.tsv" ] || die "$NET_DIR/nodes.tsv not found: run init.sh first"
  # shellcheck source=/dev/null
  . "$NET_DIR/net.env"
}

# nodes.tsv columns: name kind host p2p_port rpc_port metrics_port mine coinbase
# node_field NAME COLUMN
node_field() {
  awk -F'\t' -v n="$1" -v c="$2" '$1 == n { print $c; found = 1 } END { if (!found) exit 1 }' "$NET_DIR/nodes.tsv" ||
    die "node '$1' is not in $NET_DIR/nodes.tsv"
}
node_names() { awk -F'\t' '!/^#/ { print $1 }' "$NET_DIR/nodes.tsv"; }
node_kind() { node_field "$1" 2; }
node_host() { node_field "$1" 3; }
node_rpc() { echo "http://$(node_field "$1" 3):$(node_field "$1" 5)"; }
node_dir() { echo "$NET_DIR/nodes/$1"; }

# wait_rpc NAME TIMEOUT_S: until the node answers web3_clientVersion. Ends at the timeout with an error.
wait_rpc() {
  local name=$1 timeout=$2 url deadline
  url=$(node_rpc "$name")
  deadline=$((SECONDS + timeout))
  until rpc "$url" web3_clientVersion >/dev/null 2>&1; do
    if ((SECONDS >= deadline)); then
      die "$name did not answer JSON-RPC at $url within ${timeout}s (see $(node_dir "$name")/node.log)"
    fi
    sleep 2
  done
}

# head_number URL -> decimal number of the node's head.
head_number() {
  local h
  h=$(rpc_str "$1" eth_blockNumber) || return 1
  hex2dec "$h"
}

# block_field URL NUMBER FIELD -> the field of the canonical block at NUMBER (raw JSON value, strings unquoted).
block_field() {
  local b
  b=$(rpc "$1" eth_getBlockByNumber "[\"$(printf '0x%x' "$2")\",false]") || return 1
  [ "$b" != null ] || return 1
  jq -r --arg f "$3" '.[$f] // empty' <<<"$b"
}
