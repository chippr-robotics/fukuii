#!/usr/bin/env bash
# Spec 020 Q-7: two-tree import differential and [IMPORT-TIMING] (research.md §R-4, quickstart Q-7).
#
#   differential.sh <mordor|ephemery> <jar> <label>
#
# Copies the chain's frozen base datadir into runs/<chain>-<label>/data, imports the chain's range file offline with
# `fukuii <net> import` (#1651: strict, executes and validates every block against its header, no networking), and
# writes runs/<chain>-<label>/{import.log,timing.txt,result.txt}. Pass = exit 0 and "N imported, 0 already present".
set -euo pipefail

CHAIN=$1 JAR=$(readlink -f "$2") LABEL=$3
HERE=${SPEC020_WORKDIR:-/home/dontpanic/work/spec020-diff}   # range files, jars and runs/ live here, not in the repo
RUN=$HERE/runs/$CHAIN-$LABEL

case $CHAIN in
  mordor)
    BASE=/home/dontpanic/mordor-data                       # head 17207611, frozen 2026-10-17 (unit stopped)
    RANGE=$HERE/mordor-17207612-17212611.rlp
    N=5000
    INCLUDE='include classpath("conf/mordor.conf")'
    HEAP=-Xmx4g ;;
  ephemery)
    BASE=/chipprbots/blockchain/ephemery/frozen-H-data     # ephemery-164, head 135637
    RANGE=$HERE/ephemery-164-135638-147609.rlp    # erigon-built file trimmed to start at head+1
    N=11972
    INCLUDE='include file("/chipprbots/blockchain/ephemery/ephemery-164/conf/ephemery.conf")'
    HEAP=-Xmx3g ;;
  *) echo "unknown chain $CHAIN" >&2; exit 2 ;;
esac

if [ -e "$RUN" ]; then echo "$RUN exists; pick a new label" >&2; exit 2; fi
mkdir -p "$RUN/conf" "$RUN/logs"
cp -a "$BASE" "$RUN/data"
cat > "$RUN/conf/$CHAIN.conf" <<EOF
$INCLUDE
fukuii {
  datadir = "$RUN/data"
  state-read-caches.import-timing-log = true
}
EOF

start=$(date +%s)
set +e
FUKUII_CONF_DIR=$RUN/conf MALLOC_ARENA_MAX=2 JAVA_TOOL_OPTIONS=-Dcats.effect.tracing.mode=none \
  java -XX:+UnlockDiagnosticVMOptions -XX:-ReduceAllocationMerges \
    -Dlogback.configurationFile="$(dirname "$(readlink -f "$0")")/logback-timing.xml" -Dlogging.logs-dir="$RUN/logs" -Dlogging.logs-level=INFO \
    -Dfukuii.state-read-caches.import-timing-log=true \
    -Xms1g $HEAP -Xss8M -XX:MaxDirectMemorySize=512M -XX:+UseG1GC -XX:MaxGCPauseMillis=200 \
    -jar "$JAR" "$CHAIN" import "$RANGE" > "$RUN/import.log" 2>&1
rc=$?
set -e
secs=$(( $(date +%s) - start ))

summary=$(grep -ho "Imported $RANGE: [0-9]* imported, [0-9]* already present" "$RUN/import.log" "$RUN"/logs/*.log 2>/dev/null | tail -1 || true)
grep -h "\[IMPORT-TIMING\]" "$RUN/import.log" "$RUN"/logs/*.log 2>/dev/null \
  | sed -n 's/.*\[IMPORT-TIMING\] block=\([0-9]*\) gas=\([0-9]*\) .* total=\([0-9.]*\)ms.*/\1 \2 \3/p' \
  | sort -n -u > "$RUN/timing.txt" || true
stats=$(python3 -I - "$RUN/timing.txt" <<'PY'
import sys
rows=[l.split() for l in open(sys.argv[1]) if l.strip()]
t=sorted(float(r[2]) for r in rows[200:])
q=lambda p: t[min(len(t)-1,int(p*len(t)))] if t else float('nan')
if not t: print(f'timed={len(rows)} NO TIMING'); sys.exit(0)
print(f"timed={len(rows)} after_warmup={len(t)} p50={q(0.50):.2f}ms p99={q(0.99):.2f}ms")
PY
)
{
  echo "chain=$CHAIN label=$LABEL jar=$JAR sha256=$(sha256sum "$JAR" | cut -c1-64)"
  echo "range=$RANGE sha256=$(sha256sum "$RANGE" | cut -c1-64) expected=$N"
  echo "exit=$rc seconds=$secs"
  echo "summary: ${summary:-<none>}"
  echo "timing: $stats"
} | tee "$RUN/result.txt"
[ "$rc" -eq 0 ] && echo "$summary" | grep -q ": $N imported, 0 already present"
