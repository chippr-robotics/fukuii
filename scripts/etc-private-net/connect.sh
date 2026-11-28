#!/usr/bin/env bash
# Connects every pair of the private net's nodes with admin_addPeer (spec 018 C6, T244), and waits until each node has
# a peer. Also restores the links make-fork.sh cut.
#
# Usage: scripts/etc-private-net/connect.sh --net-dir DIR [--node NAME]... [--timeout S]
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
# shellcheck source=scripts/etc-private-net/lib.sh
. "$HERE/lib.sh"

usage() {
  cat <<'EOF'
connect.sh: wire every pair of nodes (admin_addPeer both ways) and wait for each node to have a peer.

Required:
  --net-dir DIR    The directory init.sh wrote.
Options:
  --node NAME      Connect only the pairs that include this node (repeatable). Default: every pair.
  --timeout S      Seconds to wait for each node to show a peer (default 120).
  -h, --help       This text.
EOF
}

NET_DIR_ARG="" TIMEOUT=120
ONLY=()
while [ $# -gt 0 ]; do
  case "$1" in
    --net-dir) NET_DIR_ARG=$2; shift 2 ;;
    --node) ONLY+=("$2"); shift 2 ;;
    --timeout) TIMEOUT=$2; shift 2 ;;
    -h | --help) usage; exit 0 ;;
    *) usage >&2; die "unknown argument: $1" ;;
  esac
done
[ -n "$NET_DIR_ARG" ] || { usage >&2; die "--net-dir is required"; }
is_uint "$TIMEOUT" || die "--timeout must be a number of seconds"
need_cmd jq curl python3
load_net "$(realpath -m "$NET_DIR_ARG")"

mapfile -t NODES < <(node_names)

# enode_of NAME: the node's enode URL with the host it was configured with (a node bound to 0.0.0.0 or :: reports that
# address, which no peer can dial).
enode_of() {
  local info e host p2p
  info=$(rpc "$(node_rpc "$1")" admin_nodeInfo) || die "$1: admin_nodeInfo failed"
  e=$(jq -r '.enode // empty' <<<"$info")
  [[ $e == enode://*@* ]] || die "$1: admin_nodeInfo gave no enode URL"
  host=$(node_host "$1")
  p2p=$(node_field "$1" 4)
  echo "${e%%@*}@$host:$p2p"
}

declare -A ENODE=()
for n in "${NODES[@]}"; do ENODE[$n]=$(enode_of "$n"); done
for n in "${NODES[@]}"; do printf '%s\t%s\n' "$n" "${ENODE[$n]}"; done >"$NET_DIR/enodes.tsv"

selected() {
  [ ${#ONLY[@]} -eq 0 ] && return 0
  printf '%s\n' "${ONLY[@]}" | grep -qxe "$1" -e "$2"
}

for a in "${NODES[@]}"; do
  for b in "${NODES[@]}"; do
    [ "$a" = "$b" ] && continue
    selected "$a" "$b" || continue
    ok=$(rpc "$(node_rpc "$a")" admin_addPeer "[\"${ENODE[$b]}\"]") || die "$a: admin_addPeer $b failed"
    [ "$ok" = true ] || log "$a: admin_addPeer $b answered $ok (already maintained or connected)"
  done
done

deadline=$((SECONDS + TIMEOUT))
for n in "${NODES[@]}"; do
  while :; do
    c=$(rpc "$(node_rpc "$n")" admin_peers | jq 'length')
    [ "$c" -ge 1 ] && break
    ((SECONDS < deadline)) || die "$n has no peer ${TIMEOUT}s after connect (fork id or network id mismatch?" \
      "see $(node_dir "$n")/node.log)"
    sleep 2
  done
  log "$n: $c peer(s)"
done
