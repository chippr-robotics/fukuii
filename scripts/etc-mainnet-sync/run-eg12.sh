#!/usr/bin/env bash
# EG-12 run harness (spec 018 C6, T245; the run is C7's T264, and the EG-9 ETC-mainnet half, T261).
#
# Runs, records and judges the first ETC mainnet sync on the unified state layout:
#   start     launch fukuii (path-live on, SNAP on, public peers) on a fresh datadir; record the jar, the host, and
#             the number of snap-capable ETC peers once the node has had --peer-wait-min to find them, both from
#             debug_listPeersInfo and from the node's own [SNAP-PEERS] start line (T242), and whether the two agree;
#             apply the operator's rule for fewer than 2 (T011 item 3, a user decision: --few-snap-peers).
#   sync      wait for the SNAP hand-off and for the head to reach the reference node's; check that nothing was copied
#             ([PATH-PUBLISH] absent) and that the head's hash and stateRoot equal the reference's; run the
#             ancestor-window check over RPC while the head is within 256 blocks of the pivot.
#   follow    follow the head for --follow-hours (24); then count rejected canonical blocks, REORG-EXEC-FAIL lines,
#             fork moves by depth, and compare every block from the pivot up with the reference node.
#   export    stop the node (an export needs a still state) and export the first ETC v2 archive at its head with
#             `fukuii <network> checkpoint export` (CheckpointCli).
#   reimport  EG-9: start a second node on a fresh datadir, SNAP off, that imports the archive
#             (sync.checkpoint-sync-file with the exported block's hash); require the import's success line for that
#             block, check the block and its stateRoot, follow the head for --reimport-follow-hours (1), and apply the
#             same log and canonical checks.
# Time, disk, heap, RSS and CPU are sampled throughout into OUT/samples.tsv, for comparison with spec 007's finding
# (specs/007-hotpath-alloc-reduction). Every criterion is a row of OUT/results.tsv: PASS, FAIL, RECORDED (a figure the
# gate reads) or NOT-EXERCISED (the chain gave no case in the window; the gate decides). Exit 0 only with no FAIL.
#
# Every wait has a bound (a flag below) and ends in a FAIL row when the bound is reached. Nothing here is run in CI.
#
# Usage: scripts/etc-mainnet-sync/run-eg12.sh --network etc --jar JAR --heap SIZE --datadir DIR \
#          --reimport-datadir DIR --out DIR --reference-rpc URL --few-snap-peers stop|continue|local-peer [options]
set -euo pipefail

usage() {
  cat <<'EOF'
run-eg12.sh: the EG-12 run (ETC mainnet SNAP on the unified layout, 24 h follow, v2 archive export and re-import).

Required:
  --network etc|mordor     etc for EG-12; mordor for the end-to-end rehearsal of T248.
  --jar PATH               The fukuii assembly jar under test.
  --heap SIZE              -Xmx for both nodes (recorded with the results).
  --datadir DIR            Fresh datadir for the sync (absent or empty).
  --reimport-datadir DIR   Fresh datadir for the archive re-import (absent or empty).
  --out DIR                Results directory (created; holds logs, samples, results, the archive).
  --reference-rpc URL      JSON-RPC of an independent node of the same chain (e.g. a synced core-geth), the source of
                           canonical hashes and state roots. Never the node under test.
  --few-snap-peers RULE    What to do when fewer than 2 snap-capable ETC peers are seen at start (T011 item 3, the
                           user's decision): stop (end the run, FAIL), continue (record and go on), local-peer (add
                           --local-peer, a declared local core-geth, and go on).

Options:
  --local-peer ENODE       The declared local core-geth peer (required with --few-snap-peers local-peer).
  --from PHASE             Resume at start|sync|follow|export|reimport (default start). sync and follow need the node
                           of a previous run still running (its pid is in OUT/state.env); reimport needs the export.
  --follow-hours H         Head following after the sync (default 24, EG-12).
  --reimport-follow-hours H  Head following after the re-import (default 1, EG-9).
  --sync-timeout-hours H   Bound on the sync (default 336). A bound, not an expectation: raise it if needed.
  --import-timeout-hours H Bound on the archive import (default 24).
  --peer-wait-min M        Minutes after launch before the snap-capable peers are counted (default 10).
  --snap-line-wait-min M   Further minutes, after that count, for the node's [SNAP-PEERS] line saying how SNAP
                           starts (default 5). The node's proof-of-work wait (sync.snap-sync.pow-snap-peer-wait,
                           10 min shipped) ends about when the count is made.
  --head-lag N             Blocks behind the reference head still counted as at the head (default 3).
  --confirmations N        Blocks below the head left out of the canonical comparison (default 12).
  --compare-stride N       Compare every Nth block with the reference (default 1: every block).
  --sample-interval S      Seconds between resource samples (default 60).
  --stop-timeout S         Seconds a node has to exit after SIGTERM (default 600).
  --pivot N                The SNAP pivot, when the log does not name it.
  --pivot-wait-min M       Minutes the node may sit at the head with no pivot known (no "[HANDOFF] sampling the
                           state of pivot" line and no --pivot) before the sync phase fails (default 60).
  --export-allow-estimated-td  Pass --allow-estimated-td to the export. Off by default: an archive that carries an
                           estimated total difficulty hands it to every node that imports it, and that is the
                           user's call (CheckpointCli). Without it an export refused for that reason is a FAIL row.
  --rpc-port P             JSON-RPC port, bound on 127.0.0.1 (default 8546, fukuii's).
  --metrics-port P         Prometheus port (default 13798, fukuii's).
  --p2p-port P             P2P and discovery port (default 30303).
  --java PATH              java binary (default: java on PATH).
  --extra-java-opts "..."  Extra JVM options, split on spaces.
  --dry-run                Check the arguments and print the commands; start nothing.
  -h, --help               This text.

Exit status: 0 all PASS (RECORDED and NOT-EXERCISED rows included), 1 any FAIL or a harness error, 2 usage error,
3 stopped by --few-snap-peers stop.
EOF
}

log() {
  local line
  line=$(printf '[%s] %s' "$(date -u +%FT%TZ)" "$*")
  printf '%s\n' "$line" >&2
  if [ -d "${OUT:-}" ]; then printf '%s\n' "$line" >>"$OUT/harness.log"; fi
}
die() {
  log "ERROR: $*"
  exit 1
}
usage_error() {
  usage >&2
  printf 'ERROR: %s\n' "$*" >&2
  exit 2
}

# --- Arguments -----------------------------------------------------------------------------------------------------
NETWORK="" JAR="" HEAP="" DATADIR="" DATADIR2="" OUT="" REF_RPC="" FEW_RULE="" LOCAL_PEER="" FROM=start
FOLLOW_H=24 REFOLLOW_H=1 SYNC_TIMEOUT_H=336 IMPORT_TIMEOUT_H=24 PEER_WAIT_MIN=10 SNAP_LINE_WAIT_MIN=5 HEAD_LAG=3 CONFIRMATIONS=12
STRIDE=1 SAMPLE_S=60 STOP_TIMEOUT=600 PIVOT_ARG="" PIVOT_WAIT_MIN=60 EXPORT_ALLOW_EST="" RPC_PORT=8546 METRICS_PORT=13798 P2P_PORT=30303 JAVA_BIN=java
EXTRA_JAVA="" DRY_RUN=false
while [ $# -gt 0 ]; do
  case "$1" in
    --network) NETWORK=$2; shift 2 ;;
    --jar) JAR=$2; shift 2 ;;
    --heap) HEAP=$2; shift 2 ;;
    --datadir) DATADIR=$2; shift 2 ;;
    --reimport-datadir) DATADIR2=$2; shift 2 ;;
    --out) OUT=$2; shift 2 ;;
    --reference-rpc) REF_RPC=$2; shift 2 ;;
    --few-snap-peers) FEW_RULE=$2; shift 2 ;;
    --local-peer) LOCAL_PEER=$2; shift 2 ;;
    --from) FROM=$2; shift 2 ;;
    --follow-hours) FOLLOW_H=$2; shift 2 ;;
    --reimport-follow-hours) REFOLLOW_H=$2; shift 2 ;;
    --sync-timeout-hours) SYNC_TIMEOUT_H=$2; shift 2 ;;
    --import-timeout-hours) IMPORT_TIMEOUT_H=$2; shift 2 ;;
    --peer-wait-min) PEER_WAIT_MIN=$2; shift 2 ;;
    --snap-line-wait-min) SNAP_LINE_WAIT_MIN=$2; shift 2 ;;
    --head-lag) HEAD_LAG=$2; shift 2 ;;
    --confirmations) CONFIRMATIONS=$2; shift 2 ;;
    --compare-stride) STRIDE=$2; shift 2 ;;
    --sample-interval) SAMPLE_S=$2; shift 2 ;;
    --stop-timeout) STOP_TIMEOUT=$2; shift 2 ;;
    --pivot) PIVOT_ARG=$2; shift 2 ;;
    --pivot-wait-min) PIVOT_WAIT_MIN=$2; shift 2 ;;
    --export-allow-estimated-td) EXPORT_ALLOW_EST=true; shift ;;
    --rpc-port) RPC_PORT=$2; shift 2 ;;
    --metrics-port) METRICS_PORT=$2; shift 2 ;;
    --p2p-port) P2P_PORT=$2; shift 2 ;;
    --java) JAVA_BIN=$2; shift 2 ;;
    --extra-java-opts) EXTRA_JAVA=$2; shift 2 ;;
    --dry-run) DRY_RUN=true; shift ;;
    -h | --help) usage; exit 0 ;;
    *) usage_error "unknown argument: $1" ;;
  esac
done

missing=()
for pair in "--network:$NETWORK" "--jar:$JAR" "--heap:$HEAP" "--datadir:$DATADIR" "--reimport-datadir:$DATADIR2" \
  "--out:$OUT" "--reference-rpc:$REF_RPC" "--few-snap-peers:$FEW_RULE"; do
  [ -n "${pair#*:}" ] || missing+=("${pair%%:*}")
done
[ ${#missing[@]} -eq 0 ] || usage_error "missing required option(s): ${missing[*]}"

case "$NETWORK" in
  etc) EXPECTED_CHAIN_ID=61 ;;
  mordor) EXPECTED_CHAIN_ID=63 ;;
  *) usage_error "--network must be etc or mordor (EG-12 is ETC only)" ;;
esac
case "$FEW_RULE" in
  stop | continue) ;;
  local-peer) [[ $LOCAL_PEER == enode://*@* ]] || usage_error "--few-snap-peers local-peer needs --local-peer enode://..." ;;
  *) usage_error "--few-snap-peers must be stop, continue or local-peer" ;;
esac
case "$FROM" in start | sync | follow | export | reimport) ;; *) usage_error "--from: unknown phase '$FROM'" ;; esac
for v in "$FOLLOW_H" "$REFOLLOW_H" "$SYNC_TIMEOUT_H" "$IMPORT_TIMEOUT_H" "$PEER_WAIT_MIN" "$SNAP_LINE_WAIT_MIN" "$HEAD_LAG" \
  "$CONFIRMATIONS" "$STRIDE" "$SAMPLE_S" "$STOP_TIMEOUT" "$PIVOT_WAIT_MIN" "$RPC_PORT" "$METRICS_PORT" "$P2P_PORT"; do
  [[ $v =~ ^[0-9]+$ ]] || usage_error "numeric options take non-negative integers, got '$v'"
done
[ "$STRIDE" -ge 1 ] || usage_error "--compare-stride must be at least 1"
[ -z "$PIVOT_ARG" ] || [[ $PIVOT_ARG =~ ^[0-9]+$ ]] || usage_error "--pivot takes a block number"
[[ $HEAP =~ ^[0-9]+[gGmM]$ ]] || usage_error "--heap takes a size like 12g, got '$HEAP'"

for c in jq curl python3 sha256sum du realpath; do
  command -v "$c" >/dev/null 2>&1 || usage_error "'$c' is required and is not on PATH"
done
command -v "$JAVA_BIN" >/dev/null 2>&1 || usage_error "--java: '$JAVA_BIN' not found"
[ -f "$JAR" ] || usage_error "--jar: $JAR is not a file"
JAR=$(realpath "$JAR")
DATADIR=$(realpath -m "$DATADIR")
DATADIR2=$(realpath -m "$DATADIR2")
OUT=$(realpath -m "$OUT")
[ "$DATADIR" != "$DATADIR2" ] || usage_error "--datadir and --reimport-datadir must differ"
fresh() { [ ! -e "$1" ] || [ -z "$(ls -A "$1" 2>/dev/null)" ]; }
if [ "$FROM" = start ]; then fresh "$DATADIR" || usage_error "--datadir $DATADIR is not empty: EG-12 starts fresh"; fi
if [ "$FROM" != reimport ] || [ ! -f "$OUT/state.env" ] || ! grep -q '^REIMPORT_STARTED=' "$OUT/state.env"; then
  fresh "$DATADIR2" || usage_error "--reimport-datadir $DATADIR2 is not empty: the re-import starts fresh"
fi

read -ra EXTRA_JAVA_ARR <<<"$EXTRA_JAVA"
# JDK-8385993 (C2 ReduceAllocationMerges miscompile on JDK 23+): the flags .jvmopts and hive/fukuii/fukuii.sh carry.
JVM=("$JAVA_BIN" "-Xmx$HEAP" -XX:+IgnoreUnrecognizedVMOptions -XX:+UnlockDiagnosticVMOptions
  -XX:-ReduceAllocationMerges -XX:+ExitOnOutOfMemoryError "${EXTRA_JAVA_ARR[@]}")
LOCAL_RPC="http://127.0.0.1:$RPC_PORT"

# Node A syncs by SNAP. Node B runs with SNAP off: an archive the node refuses falls through to SNAP (SyncController,
# "[CHECKPOINT IMPORT] failed ... falling through"), and a node B that SNAP-synced to the archive's block would pass the
# re-import for the wrong reason. With SNAP off a refused archive leaves it importing from genesis, and the wait fails.
node_props() { # node_props DATADIR LOGFILE SNAP(true|false)
  NODE_PROPS=("-Dlogback.configurationFile=$OUT/logback.xml" "-Dfukuii.harness.logfile=$2"
    "-Dfukuii.datadir=$1" -Dfukuii.state.path-live=true "-Dfukuii.sync.do-snap-sync=$3"
    -Dfukuii.network.rpc.http.enabled=true -Dfukuii.network.rpc.http.interface=127.0.0.1
    "-Dfukuii.network.rpc.http.port=$RPC_PORT" "-Dfukuii.network.rpc.http.apis=eth,net,web3,debug,admin"
    -Dfukuii.metrics.enabled=true "-Dfukuii.metrics.port=$METRICS_PORT"
    "-Dfukuii.network.server-address.port=$P2P_PORT" "-Dfukuii.network.discovery.port=$P2P_PORT")
}

if [ "$DRY_RUN" = true ]; then
  node_props "$DATADIR" "$OUT/node-a.log" true
  echo "node A (sync):    ${JVM[*]} ${NODE_PROPS[*]} -jar $JAR $NETWORK"
  echo "export:           ${JVM[*]} -Dlogback.configurationFile=$OUT/logback.xml -Dfukuii.harness.logfile=$OUT/export.log" \
    "-Dfukuii.datadir=$DATADIR -Dfukuii.state.path-live=true -jar $JAR $NETWORK checkpoint export --block <head>" \
    "--output $OUT/archive/$NETWORK-<head>.checkpoint${EXPORT_ALLOW_EST:+ --allow-estimated-td}"
  node_props "$DATADIR2" "$OUT/node-b.log" false
  echo "node B (import):  ${JVM[*]} ${NODE_PROPS[*]} -Dfukuii.sync.checkpoint-sync-file=$OUT/archive/$NETWORK-<head>.checkpoint" \
    "-Dfukuii.sync.checkpoint-sync-expected-hash=<head hash> -jar $JAR $NETWORK"
  echo "reference:        $REF_RPC (chain id expected $EXPECTED_CHAIN_ID)"
  echo "phases from:      $FROM; follow ${FOLLOW_H}h, re-import follow ${REFOLLOW_H}h; few-snap-peers rule: $FEW_RULE"
  exit 0
fi

mkdir -p "$OUT/archive"
STATE="$OUT/state.env"
RESULTS="$OUT/results.tsv"
SAMPLES="$OUT/samples.tsv"
touch "$STATE"
[ -f "$RESULTS" ] || printf 'utc\tcriterion\tstatus\tdetail\n' >"$RESULTS"
# shellcheck source=/dev/null
. "$STATE"
setstate() { printf '%s=%q\n' "$1" "$2" >>"$STATE"; printf -v "$1" '%s' "$2"; }
result() { # result CRITERION STATUS DETAIL
  printf '%s\t%s\t%s\t%s\n' "$(date -u +%FT%TZ)" "$1" "$2" "$3" >>"$RESULTS"
  log "$2  $1: $3"
}

# --- RPC helpers ---------------------------------------------------------------------------------------------------
rpc() {
  local url=$1 method=$2 params=${3:-[]} body out
  body=$(jq -cn --arg m "$method" --argjson p "$params" '{jsonrpc:"2.0",id:1,method:$m,params:$p}')
  out=$(curl -sS --max-time 60 -H 'Content-Type: application/json' -d "$body" "$url" 2>/dev/null) || return 1
  jq -e 'has("result") and (has("error") | not)' >/dev/null 2>&1 <<<"$out" || return 1
  jq -c '.result' <<<"$out"
}
dec() { python3 -I -c 'import sys; print(int(sys.argv[1], 16))' "$1"; }
hex() { printf '0x%x' "$1"; }
head_of() { local h; h=$(rpc "$1" eth_blockNumber) || return 1; dec "$(jq -r . <<<"$h")"; }
block_of() { rpc "$1" eth_getBlockByNumber "[\"$(hex "$2")\",false]"; } # -> JSON or null
hash_at() { local b; b=$(block_of "$1" "$2") || return 1; jq -r '.hash // empty' <<<"$b"; }

alive() { [ -n "${1:-}" ] && kill -0 "$1" 2>/dev/null; }

# --- The node's [SNAP-PEERS] start lines (T242) ---------------------------------------------------------------------
# Matched exactly as the code writes them (staging, T242 #1940). PathLiveSync.SnapPeerCount.describe:
#   s"$snapCapable snap-capable peer(s) on this chain ($declaredSnapCapable of them declared by the operator, " +
#     s"static-nodes.json, sync.snap-sync.snap-server-peers or admin_addPeer), of $connected connected"
# (the list of ways to declare a peer is matched loosely, [^)]*, so a wording change there, as in B-453 #2031, does not
# hide the line)
# SyncController.checkingSnapStart, where SNAP begins (INFO, or WARN with no count):
#   val against = if c.snapCapable < min then s"${c.snapCapable} < min $min" else s"min-snap-peers = $min"
#   log.info(s"[SNAP-PEERS] SNAP starts with ${c.describe}; $against")
#   s"[SNAP-PEERS] SNAP starts with an unknown number of peers (no count came from the peer manager); " +
#     s"min-snap-peers = $min"
# the fallback to regular sync from the genesis (WARN):
#   s"[SNAP-PEERS] No snap-capable peer came in ${port.snapPeers.powWait.toMinutes} min " +
#     s"(${pending.count.fold("no count")(_.describe)}). On a proof-of-work chain this node syncs by " +
#     "regular sync from its genesis instead, ...
# and while it waits (INFO):
#   s"[SNAP-PEERS] ${pending.count.fold("No count of the peers yet")(_.describe)}: a proof-of-work " +
#     s"node at its genesis waits up to ${port.snapPeers.powWait.toMinutes} min for " +
#     s"${port.snapPeers.minSnapPeers} snap-capable peers before SNAP begins"
#   s"[SNAP-PEERS] Still waiting for ${port.snapPeers.minSnapPeers} snap-capable peers: " + c.describe
# SnapPeerPool's own "[SNAP-PEERS] snapCapable={} excluded={} ..." line, later in SNAP, matches none of these.
# snap_peers_fields: log lines on stdin -> one "KIND SNAP DECLARED CONNECTED MIN" line per start line ("-" where the
# line has no figure). KIND: begin (at or over min), begin-under-min, begin-unknown (no count), regular-sync (the
# fallback), waiting (the wait's first line and each "Still waiting" line).
SNAP_DESC='([0-9]+) snap-capable peer\(s\) on this chain \(([0-9]+) of them declared by the operator, [^)]*\), of ([0-9]+) connected'
snap_peers_fields() {
  local d=$SNAP_DESC
  sed -nE \
    -e "s/.*\[SNAP-PEERS\] SNAP starts with $d; ([0-9]+) < min ([0-9]+)\$/begin-under-min \1 \2 \3 \5/p" \
    -e "s/.*\[SNAP-PEERS\] SNAP starts with $d; min-snap-peers = ([0-9]+)\$/begin \1 \2 \3 \4/p" \
    -e "s/.*\[SNAP-PEERS\] SNAP starts with an unknown number of peers \(no count came from the peer manager\); min-snap-peers = ([0-9]+)\$/begin-unknown - - - \1/p" \
    -e "s/.*\[SNAP-PEERS\] No snap-capable peer came in [0-9]+ min \($d\)\. On a proof-of-work chain this node syncs by regular sync from its genesis instead.*/regular-sync \1 \2 \3 -/p" \
    -e "s/.*\[SNAP-PEERS\] No snap-capable peer came in [0-9]+ min \(no count\)\. On a proof-of-work chain this node syncs by regular sync from its genesis instead.*/regular-sync - - - -/p" \
    -e "s/.*\[SNAP-PEERS\] $d: a proof-of-work node at its genesis waits up to [0-9]+ min for ([0-9]+) snap-capable peers before SNAP begins\$/waiting \1 \2 \3 \4/p" \
    -e "s/.*\[SNAP-PEERS\] No count of the peers yet: a proof-of-work node at its genesis waits up to [0-9]+ min for ([0-9]+) snap-capable peers before SNAP begins\$/waiting - - - \1/p" \
    -e "s/.*\[SNAP-PEERS\] Still waiting for ([0-9]+) snap-capable peers: $d\$/waiting \2 \3 \4 \1/p"
}
# snap_start_of LOGFILE -> the last line saying how SNAP started (begin*, regular-sync), or nothing
snap_start_of() { grep -F '[SNAP-PEERS]' "$1" 2>/dev/null | snap_peers_fields | grep -E '^(begin|regular-sync)' | tail -1 || true; }

stop_node() { # stop_node PID NAME
  local pid=$1 name=$2 deadline
  alive "$pid" || return 0
  log "stopping $name (pid $pid)"
  kill -TERM "$pid"
  deadline=$((SECONDS + STOP_TIMEOUT))
  while alive "$pid" && ((SECONDS < deadline)); do sleep 2; done
  if alive "$pid"; then
    result "$name stops on SIGTERM" FAIL "still running ${STOP_TIMEOUT}s after SIGTERM; left running for inspection"
    return 1
  fi
}

# --- Resource sampler (background) ---------------------------------------------------------------------------------
CLK_TCK=$(getconf CLK_TCK)
sample_once() { # sample_once PID DATADIR PHASE
  local pid=$1 dd=$2 phase=$3 headn syncing peers rss cpu heap disk snapg stat metrics
  headn=$(head_of "$LOCAL_RPC" || echo NA)
  syncing=$(rpc "$LOCAL_RPC" eth_syncing | jq -r 'if . == false then "false" else "true" end' 2>/dev/null || echo NA)
  peers=$(rpc "$LOCAL_RPC" net_peerCount | jq -r . 2>/dev/null || echo NA)
  [[ $peers == 0x* ]] && peers=$(dec "$peers")
  rss=$(awk '/^VmRSS:/ { print $2 }' "/proc/$pid/status" 2>/dev/null || echo NA)
  # utime + stime (fields 14, 15 of /proc/PID/stat, counted after the parenthesised command name)
  stat=$(cat "/proc/$pid/stat" 2>/dev/null || true)
  if [ -n "$stat" ]; then
    cpu=$(awk -v t="$CLK_TCK" '{ sub(/^.*\) /, ""); printf "%.1f", ($12 + $13) / t }' <<<"$stat")
  else
    cpu=NA
  fi
  metrics=$(curl -sS --max-time 10 "http://127.0.0.1:$METRICS_PORT/metrics" 2>/dev/null || true)
  heap=$(awk '/^(app_)?jvm_memory_used_bytes\{.*area="heap"/ { s += $NF; f = 1 } END { if (f) printf "%.0f", s; else print "NA" }' <<<"$metrics")
  snapg=$(awk '/^app_snapsync_peers_capable_gauge/ { v = $NF } END { if (v != "") printf "%.0f", v; else print "NA" }' <<<"$metrics")
  disk=$(du -sb "$dd" 2>/dev/null | cut -f1 || echo NA)
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$(date -u +%FT%TZ)" "$(date +%s)" "$phase" "$headn" \
    "$syncing" "$peers" "$rss" "$cpu" "$heap" "$disk" "$snapg" >>"$SAMPLES"
}
SAMPLER_PID=""
start_sampler() { # start_sampler PID DATADIR PHASE
  [ -f "$SAMPLES" ] ||
    printf 'utc\tepoch\tphase\thead\tsyncing\tpeers\trss_kb\tcpu_s\theap_used_bytes\tdatadir_bytes\tsnap_capable_gauge\n' >"$SAMPLES"
  stop_sampler
  (
    while alive "$1"; do
      sample_once "$1" "$2" "$3" || true
      sleep "$SAMPLE_S"
    done
  ) &
  SAMPLER_PID=$!
}
stop_sampler() { if alive "$SAMPLER_PID"; then kill "$SAMPLER_PID" 2>/dev/null || true; wait "$SAMPLER_PID" 2>/dev/null || true; fi; SAMPLER_PID=""; }
trap stop_sampler EXIT

write_logback() {
  # INFO for fukuii, so that REORG-SETTLE, REORG-EXEC-FAIL, [HANDOFF] and [PATH-PUBLISH] are written; the shipped
  # logback.xml keeps consensus and regular-sync loggers at ERROR. One plain file per process, never rolled, so that
  # every line of the run is counted.
  cat >"$OUT/logback.xml" <<'EOF'
<configuration>
  <appender name="FILE" class="ch.qos.logback.core.FileAppender">
    <file>${fukuii.harness.logfile}</file>
    <append>true</append>
    <encoder><pattern>%d{yyyy-MM-dd'T'HH:mm:ss.SSSX,UTC} %-5level [%logger{36}] - %msg%n</pattern></encoder>
  </appender>
  <logger name="io.netty" level="WARN"/>
  <logger name="org.apache.pekko" level="WARN"/>
  <logger name="org.jupnp" level="ERROR"/>
  <logger name="org.rocksdb" level="WARN"/>
  <root level="INFO"><appender-ref ref="FILE"/></root>
</configuration>
EOF
}

# launch_node DATADIR LOGFILE SNAP RPC_WAIT_S EXTRA_PROPS... -> sets LAUNCHED_PID. RPC_WAIT_S bounds the wait for the
# node's JSON-RPC; a node that is still alive at the bound is stopped before the harness fails.
launch_node() {
  local dd=$1 lf=$2 snap=$3 wait_s=$4 deadline
  shift 4
  node_props "$dd" "$lf" "$snap"
  mkdir -p "$dd"
  nohup "${JVM[@]}" "${NODE_PROPS[@]}" "$@" -jar "$JAR" "$NETWORK" >>"${lf%.log}.stdout" 2>&1 &
  LAUNCHED_PID=$!
  deadline=$((SECONDS + wait_s))
  until rpc "$LOCAL_RPC" web3_clientVersion >/dev/null; do
    alive "$LAUNCHED_PID" || die "the node exited during start-up: see ${lf%.log}.stdout and $lf"
    if ((SECONDS >= deadline)); then
      stop_node "$LAUNCHED_PID" "the node on $dd" || true
      die "the node did not answer JSON-RPC at $LOCAL_RPC within ${wait_s}s"
    fi
    if [ -f "$lf" ] && grep -qE '\[CHECKPOINT IMPORT\] (failed|skipped)' "$lf"; then
      result "archive import" FAIL "$(grep -E '\[CHECKPOINT IMPORT\] (failed|skipped)' "$lf" | tail -1 | cut -c1-400)"
      stop_node "$LAUNCHED_PID" "the node on $dd" || true
      exit 1
    fi
    sleep 5
  done
}

# --- Log checks ----------------------------------------------------------------------------------------------------
# rejected_canonical LOGFILE: blocks the node refused ("batch import failed" at ERROR; an orphan batch logs the same text
# at WARN) whose hash is the reference's canonical block at that height. Prints "refused rejected_canonical".
rejected_canonical() {
  local lf=$1 n h refh refused=0 canon=0
  while read -r n h; do
    refused=$((refused + 1))
    refh=$(hash_at "$REF_RPC" "$n" || true)
    if [ "${refh#0x}" = "${h#0x}" ]; then
      canon=$((canon + 1))
      log "rejected canonical block $n ($h)"
    fi
  done < <(sed -nE 's/.* ERROR .*Block ([0-9]+) batch import failed, hash ([0-9a-fx]+) .*/\1 \2/p' "$lf" | sort -u)
  echo "$refused $canon"
}
# fork_depths LOGFILE: "depth:count ..." from REORG-SETTLE (oldHead - fork); empty when none.
fork_depths() {
  sed -nE 's/.*REORG-SETTLE: fork=([0-9]+) oldHead=([0-9]+) .*/\2 \1/p' "$1" |
    awk '{ c[$1 - $2]++ } END { for (d in c) printf "%d:%d ", d, c[d] }'
}
# canonical_compare FROM TO: compares every STRIDE-th block hash with the reference. Prints "compared mismatched".
canonical_compare() {
  local from=$1 to=$2 n ours theirs compared=0 mism=0
  for ((n = from; n <= to; n += STRIDE)); do
    ours=$(hash_at "$LOCAL_RPC" "$n" || echo unreadable)
    theirs=$(hash_at "$REF_RPC" "$n" || echo unreadable)
    compared=$((compared + 1))
    if [ "$ours" != "$theirs" ] || [ "$ours" = unreadable ]; then
      mism=$((mism + 1))
      [ "$mism" -le 20 ] && log "block $n: ours $ours, reference $theirs"
    fi
  done
  echo "$compared $mism"
}
log_checks() { # log_checks LOGFILE NAME
  local lf=$1 name=$2 ef rc depths short=0 long=0 d c
  ef=$(grep -c 'REORG-EXEC-FAIL' "$lf" || true)
  if [ "$ef" = 0 ]; then result "$name: REORG-EXEC-FAIL" PASS "0 lines"; else result "$name: REORG-EXEC-FAIL" FAIL "$ef line(s) in $lf"; fi
  read -r refused rc < <(rejected_canonical "$lf")
  if [ "$rc" = 0 ]; then
    result "$name: rejected canonical blocks" PASS "0 (blocks refused in all: $refused; none canonical)"
  else
    result "$name: rejected canonical blocks" FAIL "$rc canonical block(s) refused (of $refused refused)"
  fi
  depths=$(fork_depths "$lf")
  for dc in $depths; do
    d=${dc%%:*} c=${dc#*:}
    if [ "$d" -ge 1 ] && [ "$d" -le 2 ]; then short=$((short + c)); else long=$((long + c)); fi
  done
  result "$name: fork moves followed" RECORDED "depth 1-2: $short; other depths: $long; by depth (depth:count): ${depths:-none}"
  result "$name: local state faults" RECORDED "$(grep -c '\[LOCAL-STATE-FAULT\]' "$lf" || true) [LOCAL-STATE-FAULT] line(s)"
}

# --- Ancestor-window check -----------------------------------------------------------------------------------------
# BLOCKHASH probe. eth_call to an address whose code is replaced for the call (the state-override argument, as geth's
# eth_call takes it): PUSH4 n, BLOCKHASH, PUSH1 0, MSTORE, PUSH1 32, PUSH1 0, RETURN. A message call, not a creation,
# so the 32 bytes come back as they are (a creation would be refused by EIP-3541 when the hash starts with 0xef, and
# would pay the code deposit). The call runs in block AT's context, where BLOCKHASH serves AT-256 .. AT-1.
PROBE_ADDR=0x00000000000000000000000000000000000eb012
blockhash_probe() { # blockhash_probe AT_BLOCK N -> the 32-byte hash BLOCKHASH(N) gives in block AT_BLOCK's context
  local code
  code=$(printf '0x63%08x4060005260206000f3' "$2")
  rpc "$LOCAL_RPC" eth_call "[{\"to\":\"$PROBE_ADDR\",\"data\":\"0x\",\"gas\":\"0x30d40\"},\"$(hex "$1")\",{\"$PROBE_ADDR\":{\"code\":\"$code\"}}]" |
    jq -r .
}
# Probed while the head is in the window (pivot+1 .. pivot+255), in the context of the head itself, whose state the
# node holds: BLOCKHASH of the pivot, of the block below it, and of the deepest ancestor the head can reach (head-255)
# when that is at or below the pivot. Those are blocks whose state this node never held, only their headers. Each head
# is probed once. A call the node does not serve is "missed", not wrong; a zero or different hash is wrong.
BH_OK=0 BH_BAD=0 BH_MISSED=0
declare -A PROBED=()
probe_at() { # probe_at AT
  local at=$1 n want got
  for n in "$PIVOT" $((PIVOT - 1)) $((at - 255)); do
    if [ "$n" -lt 0 ] || [ "$n" -ge "$at" ] || [ $((at - n)) -gt 255 ] || [ "$n" -gt "$PIVOT" ]; then continue; fi
    want=$(hash_at "$REF_RPC" "$n" || true)
    if ! got=$(blockhash_probe "$at" "$n"); then
      BH_MISSED=$((BH_MISSED + 1))
      log "BLOCKHASH($n) at block $at: the node did not serve the call"
    elif [ -n "$want" ] && [ "$got" = "$want" ]; then
      BH_OK=$((BH_OK + 1))
    else
      BH_BAD=$((BH_BAD + 1))
      log "BLOCKHASH($n) at block $at: got $got, reference ${want:-unreadable}"
    fi
  done
}
probe_window() { # probe_window HEAD
  local at=$1
  if [ "$at" -gt "$PIVOT" ] && [ $((at - PIVOT)) -le 255 ] && [ -z "${PROBED[$at]:-}" ]; then
    PROBED[$at]=1
    probe_at "$at"
  fi
}
ommer_window() { # ommers in pivot+1..pivot+6 whose parent is below the pivot (ommer number <= pivot)
  local b blk nunc i unc un found=0 bad=0
  for ((b = PIVOT + 1; b <= PIVOT + 6; b++)); do
    blk=$(block_of "$LOCAL_RPC" "$b") || { bad=$((bad + 1)); continue; }
    nunc=$(jq '.uncles | length' <<<"$blk")
    for ((i = 0; i < nunc; i++)); do
      unc=$(rpc "$LOCAL_RPC" eth_getUncleByBlockNumberAndIndex "[\"$(hex "$b")\",\"$(hex "$i")\"]") || { bad=$((bad + 1)); continue; }
      un=$(dec "$(jq -r .number <<<"$unc")")
      if [ "$un" -le "$PIVOT" ]; then
        found=$((found + 1))
        [ "$(jq -r .hash <<<"$blk")" = "$(hash_at "$REF_RPC" "$b" || true)" ] || bad=$((bad + 1))
        log "block $b carries ommer $i (number $un, parent below the pivot $PIVOT)"
      fi
    done
  done
  if [ "$bad" -gt 0 ]; then
    result "ancestor window: ommer with parent below the pivot" FAIL "$bad block(s) in pivot+1..+6 unreadable or not canonical"
  elif [ "$found" -gt 0 ]; then
    result "ancestor window: ommer with parent below the pivot" PASS "$found such ommer(s) in pivot+1..+6, imported canonically"
  else
    result "ancestor window: ommer with parent below the pivot" NOT-EXERCISED "no block in pivot+1..+6 carries an ommer at or below the pivot $PIVOT"
  fi
}

host_record() {
  {
    echo "jar: $JAR"
    echo "jar sha256: $(sha256sum "$JAR" | cut -d' ' -f1)"
    echo "java: $("$JAVA_BIN" -version 2>&1 | head -1)"
    echo "jvm: ${JVM[*]}"
    echo "host: $(uname -srm); cpus $(nproc); $(awk '/^MemTotal:/ { print "memory " $2 " kB" }' /proc/meminfo)"
    echo "datadir filesystem: $(df -hP "$(dirname "$DATADIR")" | tail -1)"
    echo "reference: $REF_RPC ($(rpc "$REF_RPC" web3_clientVersion | jq -r . 2>/dev/null || echo unknown))"
  } >"$OUT/run-info.txt"
}

# ================================================================================================= start
write_logback
if [ "$FROM" = start ]; then
  ref_cid=$(rpc "$REF_RPC" eth_chainId) || die "the reference node at $REF_RPC does not answer eth_chainId"
  ref_cid=$(dec "$(jq -r . <<<"$ref_cid")")
  [ "$ref_cid" = "$EXPECTED_CHAIN_ID" ] || die "the reference node is on chain $ref_cid, $NETWORK is $EXPECTED_CHAIN_ID"
  host_record
  log "launching node A on $DATADIR ($NETWORK, path-live, SNAP)"
  launch_node "$DATADIR" "$OUT/node-a.log" true 600
  setstate PID_A "$LAUNCHED_PID"
  setstate START_EPOCH "$(date +%s)"
  cid=$(dec "$(rpc "$LOCAL_RPC" eth_chainId | jq -r .)")
  [ "$cid" = "$EXPECTED_CHAIN_ID" ] || die "node A reports chain id $cid"
  start_sampler "$PID_A" "$DATADIR" start

  log "counting snap-capable peers in ${PEER_WAIT_MIN} min"
  deadline=$((SECONDS + PEER_WAIT_MIN * 60))
  while ((SECONDS < deadline)); do alive "$PID_A" || die "node A exited (see $OUT/node-a.log)"; sleep 15; done
  peers_json=$(rpc "$LOCAL_RPC" debug_listPeersInfo) || die "debug_listPeersInfo failed on node A"
  total=$(jq 'length' <<<"$peers_json")
  snap=$(jq '[.[] | select(test("supportsSnap: true")) | select(test("forkAccepted: true"))] | length' <<<"$peers_json")
  setstate SNAP_PEERS_AT_START "$snap"
  result "snap-capable ETC peers at start" RECORDED "$snap of $total handshaked peer(s), ${PEER_WAIT_MIN} min after launch"

  # The node's own count at SNAP's start (T242), beside the RPC count. It is made at another moment (the end of the
  # node's wait, or at once when it reaches min-snap-peers), so a disagreement is recorded, not failed.
  log "waiting up to ${SNAP_LINE_WAIT_MIN} min for the node's [SNAP-PEERS] start line"
  deadline=$((SECONDS + SNAP_LINE_WAIT_MIN * 60))
  sp=$(snap_start_of "$OUT/node-a.log")
  while [ -z "$sp" ] && ((SECONDS < deadline)); do
    alive "$PID_A" || die "node A exited (see $OUT/node-a.log)"
    sleep 15
    sp=$(snap_start_of "$OUT/node-a.log")
  done
  waits=$(grep -F '[SNAP-PEERS]' "$OUT/node-a.log" 2>/dev/null | snap_peers_fields | grep -c '^waiting ' || true)
  if [ -z "$sp" ]; then
    result "snap-capable ETC peers at start (node log)" FAIL \
      "no [SNAP-PEERS] start line in $OUT/node-a.log $((PEER_WAIT_MIN + SNAP_LINE_WAIT_MIN)) min after launch ($waits waiting line(s)); raise --snap-line-wait-min, or the jar predates T242"
  else
    read -r sp_kind sp_snap sp_decl sp_conn sp_min <<<"$sp"
    setstate SNAP_LOG_KIND "$sp_kind"
    setstate SNAP_LOG_PEERS "$sp_snap"
    setstate SNAP_LOG_DECLARED "$sp_decl"
    setstate SNAP_LOG_CONNECTED "$sp_conn"
    setstate SNAP_LOG_MIN "$sp_min"
    case "$sp_kind" in
      begin) how="SNAP began at or over min-snap-peers = $sp_min" ;;
      begin-under-min) how="SNAP began under the minimum ($sp_snap < min $sp_min)" ;;
      begin-unknown) how="SNAP began with no count from the peer manager (min-snap-peers = $sp_min)" ;;
      regular-sync) how="no snap-capable peer: the node fell back to regular sync from its genesis" ;;
    esac
    counted="$sp_snap snap-capable ($sp_decl declared by the operator) of $sp_conn connected"
    [ "$sp_snap" != - ] || counted="no count"
    result "snap-capable ETC peers at start (node log)" RECORDED "$counted; $how; $waits waiting line(s)"
    if [ "$sp_kind" = regular-sync ]; then
      result "SNAP begins" FAIL "the node fell back to regular sync from its genesis; EG-12 needs a SNAP sync"
    elif [ "$sp_snap" = - ]; then
      result "snap-peer count: node log vs RPC" NOT-EXERCISED "the node logged no count; RPC: $snap of $total"
    elif [ "$sp_snap" = "$snap" ]; then
      result "snap-peer count: node log vs RPC" PASS "both $snap snap-capable (connected: log $sp_conn, RPC $total)"
    else
      result "snap-peer count: node log vs RPC" RECORDED \
        "DISAGREE: node log $sp_snap snap-capable of $sp_conn, RPC $snap of $total (counted at different moments)"
    fi
  fi
  if [ "$snap" -lt 2 ]; then
    case "$FEW_RULE" in
      stop)
        result "snap-peer precondition" FAIL "fewer than 2 snap-capable peers; rule 'stop' (T011)"
        stop_node "$PID_A" "node A" || true
        exit 3
        ;;
      continue) result "snap-peer precondition" RECORDED "fewer than 2; rule 'continue' (T011): the run goes on with public peers only" ;;
      local-peer)
        ok=$(rpc "$LOCAL_RPC" admin_addPeer "[\"$LOCAL_PEER\"]" || echo error)
        result "snap-peer precondition" RECORDED "fewer than 2; rule 'local-peer' (T011): declared local core-geth $LOCAL_PEER added ($ok)"
        ;;
    esac
  else
    result "snap-peer precondition" PASS "$snap snap-capable peers"
  fi
  FROM=sync
fi

# ================================================================================================= sync
if [ "$FROM" = sync ]; then
  alive "${PID_A:-}" || die "node A is not running (pid ${PID_A:-none}): sync cannot be resumed"
  start_sampler "$PID_A" "$DATADIR" sync
  deadline=$((SECONDS + SYNC_TIMEOUT_H * 3600))
  PIVOT=${PIVOT_ARG:-${PIVOT:-}}
  synced=false why="" at_head_since=""
  while :; do
    alive "$PID_A" || { result "sync reaches the head" FAIL "node A exited during the sync"; exit 1; }
    if [ -z "$PIVOT_ARG" ]; then
      # The last hand-off the log names: a hand-off held back (excluded accounts) re-pegs to a newer pivot.
      p=$(sed -nE 's/.*\[HANDOFF\] sampling the state of pivot ([0-9]+) .*/\1/p' "$OUT/node-a.log" | tail -1)
      if [ -n "$p" ] && [ "$p" != "${PIVOT:-}" ]; then
        PIVOT=$p
        setstate PIVOT "$PIVOT"
        log "hand-off at pivot $PIVOT"
      fi
    fi
    head=$(head_of "$LOCAL_RPC" || echo 0)
    ref_head=$(head_of "$REF_RPC" || echo 0)
    syncing=$(rpc "$LOCAL_RPC" eth_syncing || echo true)
    at_head=false
    if [ "$syncing" = false ] && [ "$ref_head" -gt 0 ] && [ $((ref_head - head)) -le "$HEAD_LAG" ]; then at_head=true; fi
    if [ -n "$PIVOT" ] && [ "$head" -gt "$PIVOT" ]; then
      probe_window "$head"
      # Synced once past the ancestor window, so that the window was walked while the node followed.
      if [ "$at_head" = true ] && [ $((head - PIVOT)) -gt 256 ]; then
        synced=true
        break
      fi
    elif [ -z "$PIVOT" ] && [ "$at_head" = true ]; then
      # At the head with no pivot known: the hand-off line was not logged (or not in this form). Bounded.
      at_head_since=${at_head_since:-$SECONDS}
      if ((SECONDS - at_head_since >= PIVOT_WAIT_MIN * 60)); then
        why="at the head for ${PIVOT_WAIT_MIN} min with no [HANDOFF] pivot line in $OUT/node-a.log; pass --pivot"
        break
      fi
    else
      at_head_since=""
    fi
    if ((SECONDS >= deadline)); then
      why="not at the head after ${SYNC_TIMEOUT_H} h"
      break
    fi
    sleep 10
  done
  if [ "$synced" != true ]; then
    result "sync reaches the head" FAIL "$why (pivot ${PIVOT:-not seen}, head $head, reference $ref_head)"
    exit 1
  fi
  setstate SYNC_DONE_EPOCH "$(date +%s)"
  result "sync reaches the head" PASS "pivot $PIVOT, head $head, $(((SYNC_DONE_EPOCH - START_EPOCH) / 60)) min after launch"
  copies=$(grep -c '\[PATH-PUBLISH\]' "$OUT/node-a.log" || true)
  if [ "$copies" = 0 ]; then result "no copy at the hand-off" PASS "no [PATH-PUBLISH] line"
  else result "no copy at the hand-off" FAIL "$copies [PATH-PUBLISH] line(s): the Hash publish ran"; fi
  # The root at the head: the head block's hash covers its stateRoot, and the node answers a state read there.
  n=$((head - CONFIRMATIONS))
  ours=$(block_of "$LOCAL_RPC" "$n")
  theirs=$(block_of "$REF_RPC" "$n")
  if [ "$(jq -r .hash <<<"$ours")" = "$(jq -r .hash <<<"$theirs")" ] &&
    [ "$(jq -r .stateRoot <<<"$ours")" = "$(jq -r .stateRoot <<<"$theirs")" ] &&
    rpc "$LOCAL_RPC" eth_getBalance "[$(jq '.miner' <<<"$ours"),\"$(hex "$head")\"]" >/dev/null; then
    result "root equals the head's stateRoot" PASS "block $n: hash and stateRoot equal the reference's; state read at $head"
  else
    result "root equals the head's stateRoot" FAIL "block $n: ours $(jq -c '{hash,stateRoot}' <<<"$ours"), reference $(jq -c '{hash,stateRoot}' <<<"$theirs")"
  fi
  # The live probes above only run while the head is inside the window, and a node catching up after the hand-off
  # crosses pivot+1..pivot+255 in seconds (T248 on Mordor: 302 blocks in 5 s, no live probe in the window). So the
  # window is probed again now, in the context of past blocks inside it: pivot+1, its middle and pivot+255, as far as
  # the head reaches. Those calls need the node to serve state at those blocks; a call it does not serve is "missed".
  for at in $((PIVOT + 1)) $((PIVOT + 128)) $((PIVOT + 255)); do
    if [ "$at" -le "$head" ]; then probe_window "$at"; fi
  done
  log "ancestor window: $BH_OK BLOCKHASH probe(s) right, $BH_BAD wrong, $BH_MISSED not served (live and historical)"
  if [ "$BH_BAD" -gt 0 ]; then
    result "ancestor window: BLOCKHASH below the pivot" FAIL "$BH_BAD probe(s) wrong, $BH_OK right, $BH_MISSED not served"
  elif [ "$BH_OK" -gt 0 ]; then
    result "ancestor window: BLOCKHASH below the pivot" PASS "$BH_OK probe(s) in pivot+1..+255 gave the reference's hash ($BH_MISSED not served)"
  else
    result "ancestor window: BLOCKHASH below the pivot" FAIL "no probe was served ($BH_MISSED missed): the check did not run, so it cannot pass"
  fi
  read -r compared mism < <(canonical_compare $((PIVOT + 1)) $((PIVOT + 256)))
  if [ "$mism" = 0 ]; then result "ancestor window: blocks pivot+1..+256 canonical" PASS "$compared compared"
  else result "ancestor window: blocks pivot+1..+256 canonical" FAIL "$mism of $compared differ from the reference"; fi
  ommer_window
  FROM=follow
fi

# ================================================================================================= follow
if [ "$FROM" = follow ]; then
  alive "${PID_A:-}" || die "node A is not running (pid ${PID_A:-none}): follow cannot be resumed"
  [ -n "${PIVOT:-}" ] || PIVOT=$PIVOT_ARG
  [ -n "$PIVOT" ] || die "the pivot is not known: pass --pivot"
  start_sampler "$PID_A" "$DATADIR" follow
  setstate FOLLOW_START_EPOCH "$(date +%s)"
  end=$((SECONDS + FOLLOW_H * 3600))
  while ((SECONDS < end)); do
    alive "$PID_A" || { result "follows the head for ${FOLLOW_H} h" FAIL "node A exited during the follow"; exit 1; }
    sleep 60
  done
  head=$(head_of "$LOCAL_RPC") || die "node A does not answer eth_blockNumber"
  ref_head=$(head_of "$REF_RPC") || die "the reference does not answer eth_blockNumber"
  if [ $((ref_head - head)) -le "$HEAD_LAG" ]; then
    result "follows the head for ${FOLLOW_H} h" PASS "head $head, reference $ref_head"
  else
    result "follows the head for ${FOLLOW_H} h" FAIL "head $head is $((ref_head - head)) behind the reference $ref_head"
  fi
  read -r compared mism < <(canonical_compare $((PIVOT + 1)) $((head - CONFIRMATIONS)))
  if [ "$mism" = 0 ]; then result "node A: canonical chain equals the reference" PASS "$compared block(s) from the pivot up (stride $STRIDE)"
  else result "node A: canonical chain equals the reference" FAIL "$mism of $compared block(s) differ"; fi
  log_checks "$OUT/node-a.log" "node A"
  FROM="export"
fi

# ================================================================================================= export
if [ "$FROM" = export ]; then
  alive "${PID_A:-}" || die "node A is not running: the export reads the head from it before stopping it"
  head=$(head_of "$LOCAL_RPC") || die "node A does not answer eth_blockNumber"
  blk=$(block_of "$LOCAL_RPC" "$head")
  setstate EXPORT_N "$head"
  setstate EXPORT_HASH "$(jq -r .hash <<<"$blk")"
  setstate EXPORT_ROOT "$(jq -r .stateRoot <<<"$blk")"
  stop_sampler
  stop_node "$PID_A" "node A" || exit 1
  archive="$OUT/archive/$NETWORK-$EXPORT_N.checkpoint"
  log "exporting block $EXPORT_N ($EXPORT_HASH) to $archive"
  t0=$(date +%s)
  export_args=(--block "$EXPORT_N" --output "$archive")
  [ -z "$EXPORT_ALLOW_EST" ] || export_args+=(--allow-estimated-td)
  if "${JVM[@]}" "-Dlogback.configurationFile=$OUT/logback.xml" "-Dfukuii.harness.logfile=$OUT/export.log" \
    "-Dfukuii.datadir=$DATADIR" -Dfukuii.state.path-live=true -jar "$JAR" "$NETWORK" checkpoint export \
    "${export_args[@]}" >>"$OUT/export.stdout" 2>&1 && [ -s "$archive" ]; then
    setstate ARCHIVE "$archive"
    result "v2 archive export" PASS "block $EXPORT_N in $(($(date +%s) - t0)) s${EXPORT_ALLOW_EST:+ (--allow-estimated-td)}; $(stat -c %s "$archive") bytes; sha256 $(sha256sum "$archive" | cut -d' ' -f1)"
  else
    result "v2 archive export" FAIL "checkpoint export exited non-zero or wrote nothing: $OUT/export.log, $OUT/export.stdout"
    exit 1
  fi
  FROM=reimport
fi

# ================================================================================================= reimport (EG-9)
if [ "$FROM" = reimport ]; then
  [ -n "${ARCHIVE:-}" ] && [ -f "$ARCHIVE" ] || die "no exported archive recorded in $STATE"
  if alive "${PID_B:-}"; then
    log "node B is running (pid $PID_B): resuming its checks"
  else
    log "launching node B on $DATADIR2, importing $ARCHIVE"
    # The import may run before JSON-RPC answers: the wait for it is the import's bound.
    setstate REIMPORT_STARTED "$(date +%s)"
    launch_node "$DATADIR2" "$OUT/node-b.log" false $((IMPORT_TIMEOUT_H * 3600)) \
      "-Dfukuii.sync.checkpoint-sync-file=$ARCHIVE" \
      "-Dfukuii.sync.checkpoint-sync-expected-hash=$EXPORT_HASH"
    setstate PID_B "$LAUNCHED_PID"
  fi
  start_sampler "$PID_B" "$DATADIR2" reimport
  deadline=$((SECONDS + IMPORT_TIMEOUT_H * 3600))
  # Done when the node logs the import's success for the exported block; a failed or skipped import is a FAIL (SNAP is
  # off on node B, so nothing else can bring it to that block).
  ok_line=""
  while :; do
    ok_line=$(grep -E "\[CHECKPOINT IMPORT\] success: block=$EXPORT_N " "$OUT/node-b.log" 2>/dev/null | tail -1 || true)
    [ -z "$ok_line" ] || break
    if grep -qE '\[CHECKPOINT IMPORT\] (failed|skipped)' "$OUT/node-b.log" 2>/dev/null; then
      result "archive import" FAIL "$(grep -E '\[CHECKPOINT IMPORT\] (failed|skipped)' "$OUT/node-b.log" | tail -1 | cut -c1-400)"
      stop_node "$PID_B" "node B" || true
      exit 1
    fi
    alive "$PID_B" || { result "archive import" FAIL "node B exited during the import: $OUT/node-b.log"; exit 1; }
    ((SECONDS < deadline)) || { result "archive import" FAIL "no import success line ${IMPORT_TIMEOUT_H} h after launch"; exit 1; }
    sleep 30
  done
  imported=$(date +%s)
  # The block's hash covers its stateRoot. That the imported state is the right one is shown by what follows: node B
  # executes every later block on it, and its canonical chain is compared with the reference below.
  # The archive carries the window's headers but not the block's own body, and the block RPCs answer only a block whose
  # body is stored, so the imported block itself may not be served over RPC (T248 on Mordor, dev-node 2026-11-20). The
  # root is judged at the header level instead: the import's own line names the hash and the root it wrote, and the
  # first block node B executes on that state (N+1) must name the exported block as its parent and equal the
  # reference's block N+1, which a wrong root could not produce. Whether block N itself is served is recorded apart.
  hdr_line=$(grep -E "\[CHECKPOINT IMPORT\] header and window checked: block=$EXPORT_N " "$OUT/node-b.log" | tail -1 || true)
  line_hash=$(sed -nE 's/.* hash=(0x)?([0-9a-fA-F]+).*/\2/p' <<<"$hdr_line" | tr 'A-F' 'a-f')
  line_root=$(sed -nE 's/.*state of root (0x)?([0-9a-fA-F]+).*/\2/p' <<<"$hdr_line" | tr 'A-F' 'a-f')
  want_hash=$(tr 'A-F' 'a-f' <<<"${EXPORT_HASH#0x}") want_root=$(tr 'A-F' 'a-f' <<<"${EXPORT_ROOT#0x}")
  next=""
  next_deadline=$((SECONDS + 600))
  while ((SECONDS < next_deadline)); do
    next=$(block_of "$LOCAL_RPC" $((EXPORT_N + 1)) || echo null)
    [ "$(jq -r '.hash // empty' <<<"$next")" = "" ] || break
    sleep 10
  done
  ref_next=$(hash_at "$REF_RPC" $((EXPORT_N + 1)) || true)
  if [ -n "$line_hash" ] && [ "$line_hash" = "$want_hash" ] && [ "$line_root" = "$want_root" ] &&
    [ "$(jq -r '.parentHash // empty' <<<"$next")" = "$EXPORT_HASH" ] &&
    [ -n "$ref_next" ] && [ "$(jq -r '.hash // empty' <<<"$next")" = "$ref_next" ]; then
    result "archive import: root equal" PASS "block $EXPORT_N, stateRoot $EXPORT_ROOT (import line); block $((EXPORT_N + 1)) executed on it equals the reference's and names it as parent; ${ok_line##*\] }; ETC resync time (SC-013): $((imported - REIMPORT_STARTED)) s from launch"
  else
    result "archive import: root equal" FAIL "import line hash=${line_hash:-none} root=${line_root:-none}; block $((EXPORT_N + 1)) on node B $(jq -c '{hash,parentHash}' <<<"$next" 2>/dev/null), reference ${ref_next:-unreadable}; exported $EXPORT_HASH / $EXPORT_ROOT"
  fi
  got=$(block_of "$LOCAL_RPC" "$EXPORT_N" || echo null)
  if [ "$(jq -r '.hash // empty' <<<"$got")" = "$EXPORT_HASH" ]; then
    result "archive block served over RPC" RECORDED "eth_getBlockByNumber($EXPORT_N) answers the exported block"
  else
    result "archive block served over RPC" RECORDED "eth_getBlockByNumber($EXPORT_N) answers $(jq -c . <<<"$got" 2>/dev/null | cut -c1-80): the block's body is not stored (the archive carries none)"
  fi
  end=$((SECONDS + REFOLLOW_H * 3600))
  while ((SECONDS < end)); do
    alive "$PID_B" || { result "re-imported node follows ${REFOLLOW_H} h" FAIL "node B exited"; exit 1; }
    sleep 60
  done
  head=$(head_of "$LOCAL_RPC") || die "node B does not answer eth_blockNumber"
  ref_head=$(head_of "$REF_RPC") || die "the reference does not answer eth_blockNumber"
  if [ "$head" -gt "$EXPORT_N" ] && [ $((ref_head - head)) -le "$HEAD_LAG" ]; then
    result "re-imported node follows ${REFOLLOW_H} h" PASS "head $head (archive at $EXPORT_N), reference $ref_head"
  else
    result "re-imported node follows ${REFOLLOW_H} h" FAIL "head $head (archive at $EXPORT_N), reference $ref_head"
  fi
  read -r compared mism < <(canonical_compare $((EXPORT_N + 1)) $((head - CONFIRMATIONS)))
  if [ "$mism" = 0 ]; then result "node B: canonical chain equals the reference" PASS "$compared block(s) above the archive"
  else result "node B: canonical chain equals the reference" FAIL "$mism of $compared block(s) differ"; fi
  log_checks "$OUT/node-b.log" "node B"
  stop_sampler
  stop_node "$PID_B" "node B" || true
fi

# ================================================================================================= summary
{
  echo "EG-12 run: $NETWORK, out $OUT"
  cat "$OUT/run-info.txt" 2>/dev/null || true
  echo
  column -t -s $'\t' "$RESULTS" 2>/dev/null || cat "$RESULTS"
  echo
  if [ -s "$SAMPLES" ]; then
    awk -F'\t' 'NR > 1 {
        if ($7 != "NA" && $7 > rss) rss = $7; if ($9 != "NA" && $9 > heap) heap = $9;
        if ($10 != "NA") disk = $10; if ($8 != "NA") cpu[$3] = $8; if (!t0) t0 = $2; t1 = $2 }
      END { printf "resources: max RSS %.1f GiB; max heap used %.1f GiB; last datadir %.1f GiB; wall %.1f h\n",
              rss / 1048576, heap / 1073741824, disk / 1073741824, (t1 - t0) / 3600
            for (p in cpu) printf "  cpu seconds at the end of %s: %s\n", p, cpu[p] }' "$SAMPLES"
  fi
} | tee "$OUT/summary.txt"
if cut -f3 "$RESULTS" | grep -qx FAIL; then exit 1; fi
