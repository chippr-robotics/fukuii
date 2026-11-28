#!/usr/bin/env bash
# Stops the private net's nodes started by start.sh on this host (spec 018 C6, T244). SIGTERM, then a bounded wait;
# a node still alive at the end is reported and the script fails, unless --kill is given.
#
# Usage: scripts/etc-private-net/stop.sh --net-dir DIR [--node NAME]... [--timeout S] [--kill]
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
# shellcheck source=scripts/etc-private-net/lib.sh
. "$HERE/lib.sh"

usage() {
  cat <<'EOF'
stop.sh: stop the private net's nodes on this host (the pids start.sh recorded).

Required:
  --net-dir DIR   The directory init.sh wrote.
Options:
  --node NAME     Stop this node only (repeatable). Default: every node with a pid file here.
  --timeout S     Seconds to wait for each node to exit after SIGTERM (default 120).
  --kill          SIGKILL a node still alive at the timeout (its datadir may then need a recovery at the next start).
  -h, --help      This text.
EOF
}

NET_DIR_ARG="" TIMEOUT=120 KILL=false
SELECTED=()
while [ $# -gt 0 ]; do
  case "$1" in
    --net-dir) NET_DIR_ARG=$2; shift 2 ;;
    --node) SELECTED+=("$2"); shift 2 ;;
    --timeout) TIMEOUT=$2; shift 2 ;;
    --kill) KILL=true; shift ;;
    -h | --help) usage; exit 0 ;;
    *) usage >&2; die "unknown argument: $1" ;;
  esac
done
[ -n "$NET_DIR_ARG" ] || { usage >&2; die "--net-dir is required"; }
is_uint "$TIMEOUT" || die "--timeout must be a number of seconds"
load_net "$(realpath -m "$NET_DIR_ARG")"
if [ ${#SELECTED[@]} -eq 0 ]; then mapfile -t SELECTED < <(node_names); fi

failed=()
for n in "${SELECTED[@]}"; do
  pidf="$(node_dir "$n")/node.pid"
  [ -f "$pidf" ] || continue
  pid=$(cat "$pidf")
  if ! kill -0 "$pid" 2>/dev/null; then
    rm -f "$pidf"
    continue
  fi
  log "stopping $n (pid $pid)"
  kill -TERM "$pid"
  deadline=$((SECONDS + TIMEOUT))
  while kill -0 "$pid" 2>/dev/null && ((SECONDS < deadline)); do sleep 1; done
  if kill -0 "$pid" 2>/dev/null; then
    if [ "$KILL" = true ]; then
      warn "$n still alive after ${TIMEOUT}s: SIGKILL"
      kill -KILL "$pid" || true
    else
      failed+=("$n")
      continue
    fi
  fi
  rm -f "$pidf"
done
[ ${#failed[@]} -eq 0 ] || die "still running after ${TIMEOUT}s: ${failed[*]} (re-run with --kill to force)"
log "stopped"
