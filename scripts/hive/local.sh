#!/usr/bin/env bash
# Run a targeted hive simulation against the fukuii built from THIS checkout, the same way
# .github/workflows/_hive-sim.yml does on CI: sbt assembly -> thin chipprbots/fukuii:latest
# overlay -> copy hive/fukuii/* (+ the jar) into the hive checkout's clients/fukuii -> hive.
#
# The GitHub-hosted hive suites are a release gate (PRs to main, workflow_dispatch). Use this
# for day-to-day, targeted verification of a fix.
#
# Usage:
#   scripts/hive/local.sh <sim> [--limit REGEX] [--skip 'NameA|NameB'] [--parallelism N]
#                                   [--timelimit 40m] [--checktimelimit 120s]
#                                   [--clients fukuii,go-ethereum] [--skip-build] [-- extra hive args]
# --skip names tests NOT to run, exactly as _hive-sim.yml's sim_skip does; --limit must then name
# suites only.
# Examples:
#   scripts/hive/local.sh ethereum/consensus --limit 'legacy-cancun/.*CALLBlake2f_MaxRounds'
#   scripts/hive/local.sh ethereum/eels/consume-engine --limit '.*static_Call50000.*' --skip-build
#   scripts/hive/local.sh ethereum/graphql --clients fukuii,go-ethereum --limit '/07_eth_gasPrice'
#   scripts/hive/local.sh devp2p --limit eth --skip 'GetCells|BlobTxWithInvalidCells' --parallelism 1
#
# Env: HIVE_DIR (default ~/hive) — an ethereum/hive checkout with a built ./hive binary
#      (build it with `go build .` in that directory).
set -euo pipefail

usage() { sed -n '2,22p' "$0"; exit 2; }
[ $# -ge 1 ] || usage

SIM=$1; shift
LIMIT=""; SKIP=""; PAR=4; TIMELIMIT=40m; CHECK=120s; BUILD=1; CLIENTS=fukuii; EXTRA=()
while [ $# -gt 0 ]; do
  case "$1" in
    --limit)          LIMIT=$2; shift 2 ;;
    --skip)           SKIP=$2; shift 2 ;;
    --parallelism)    PAR=$2; shift 2 ;;
    --timelimit)      TIMELIMIT=$2; shift 2 ;;
    --checktimelimit) CHECK=$2; shift 2 ;;
    --clients)        CLIENTS=$2; shift 2 ;;
    --skip-build)     BUILD=0; shift ;;
    --)               shift; EXTRA=("$@"); break ;;
    -h|--help)        usage ;;
    *) echo "unknown option: $1" >&2; usage ;;
  esac
done

REPO=$(cd "$(dirname "$0")/../.." && pwd)
if [ -n "$SKIP" ]; then
  case "$LIMIT" in */*) echo "--skip needs a suite-only --limit, got '$LIMIT'" >&2; exit 2 ;; esac
  LIMIT="$LIMIT/$(python3 "$REPO/scripts/ci/hive_skip_regex.py" "$SKIP")"
fi
HIVE_DIR=${HIVE_DIR:-$HOME/hive}
[ -x "$HIVE_DIR/hive" ] || { echo "no hive binary at $HIVE_DIR/hive (run: cd $HIVE_DIR && go build .)" >&2; exit 1; }

cd "$REPO"
if [ "$BUILD" = 1 ]; then
  sbt -batch assembly
fi
JAR=$(ls -t target/scala-3.*/fukuii-assembly-*.jar 2>/dev/null | head -1)
[ -n "$JAR" ] || { echo "no assembly jar under target/ (drop --skip-build)" >&2; exit 1; }

# One run per MACHINE at a time. The image tags built below (chipprbots/fukuii:latest, and
# hive's hive/clients/fukuii:latest) are global to the Docker daemon, so two concurrent runs —
# even from different clones and different hive checkouts — would test each other's jar.
# The sbt assembly above stays outside the lock; each clone builds its own.
LOCK=${HIVE_LOCAL_LOCK:-/tmp/fukuii-hive-local.lock}
exec 9>"$LOCK"
if ! flock -n 9; then
  echo "waiting for another local hive run on $HIVE_DIR to finish ($LOCK)..." >&2
  flock 9
fi

# Same thin overlay CI builds (_hive-sim.yml "Tag base Docker image").
CTX=$(mktemp -d)
trap 'rm -rf "$CTX"' EXIT
cp "$JAR" "$CTX/"
cat > "$CTX/Dockerfile" <<'DOCKER'
FROM eclipse-temurin:25-jre-noble
RUN apt-get update && apt-get install -y --no-install-recommends jq curl && rm -rf /var/lib/apt/lists/*
WORKDIR /app
RUN mkdir -p /app/fukuii/lib /app/data /app/hive-conf
COPY fukuii-assembly-*.jar /app/fukuii/lib/fukuii-assembly.jar
ENTRYPOINT ["java", "-jar", "/app/fukuii/lib/fukuii-assembly.jar"]
DOCKER
docker build -q -t chipprbots/fukuii:latest "$CTX"

# Copy the WHOLE adapter directory (it is hive's docker build context), replacing any stale copy.
rm -rf "$HIVE_DIR/clients/fukuii"
mkdir -p "$HIVE_DIR/clients/fukuii"
cp "$REPO"/hive/fukuii/* "$HIVE_DIR/clients/fukuii/"
cp "$JAR" "$HIVE_DIR/clients/fukuii/"

cd "$HIVE_DIR"
STAMP=$(mktemp); touch "$STAMP"   # result files newer than this belong to this run
args=(--sim "$SIM" --client "$CLIENTS" --sim.parallelism "$PAR" --sim.timelimit "$TIMELIMIT"
      --client.checktimelimit="$CHECK" --loglevel 3)
[ -n "$LIMIT" ] && args+=(--sim.limit "$LIMIT")
echo "+ ./hive ${args[*]} ${EXTRA[*]}"
./hive "${args[@]}" "${EXTRA[@]}" || true

# Summarise every result file this run produced (one per suite): counts and failing names.
mapfile -t RESULTS < <(find workspace/logs -maxdepth 1 -name '*.json' ! -name hive.json -newer "$STAMP" | sort)
rm -f "$STAMP"
[ ${#RESULTS[@]} -gt 0 ] || { echo "no result JSON in $HIVE_DIR/workspace/logs from this run"; exit 1; }
python3 - "${RESULTS[@]}" <<'PY'
import json, sys
total_pass = total_fail = 0
for path in sys.argv[1:]:
    d = json.load(open(path))
    cases = list(d.get("testCases", {}).values())
    fails = sorted(c["name"] for c in cases if not c["summaryResult"]["pass"])
    total_pass += len(cases) - len(fails)
    total_fail += len(fails)
    print(f"{d.get('name')}: {len(cases) - len(fails)} passed, {len(fails)} failed  ({path})")
    for n in fails:
        print("  FAIL", n)
print(f"TOTAL: {total_pass} passed, {total_fail} failed")
sys.exit(1 if total_fail else 0)
PY
