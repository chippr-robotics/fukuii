#!/usr/bin/env bash
# Run a targeted hive simulation against the fukuii built from THIS checkout, the same way
# .github/workflows/_hive-sim.yml does on CI: sbt assembly -> thin chipprbots/fukuii:latest
# overlay -> copy hive/fukuii/* (+ the jar) into the hive checkout's clients/fukuii -> hive.
#
# The GitHub-hosted hive suites are a release gate (PRs to main, workflow_dispatch). Use this
# for day-to-day, targeted verification of a fix.
#
# Usage:
#   scripts/hive/local.sh <sim> [--limit REGEX] [--parallelism N] [--timelimit 40m]
#                                   [--checktimelimit 120s] [--skip-build] [-- extra hive args]
# Examples:
#   scripts/hive/local.sh ethereum/consensus --limit 'legacy-cancun/.*CALLBlake2f_MaxRounds'
#   scripts/hive/local.sh ethereum/eels/consume-engine --limit '.*static_Call50000.*' --skip-build
#
# Env: HIVE_DIR (default ~/hive) — an ethereum/hive checkout with a built ./hive binary
#      (build it with `go build .` in that directory).
set -euo pipefail

usage() { sed -n '2,19p' "$0"; exit 2; }
[ $# -ge 1 ] || usage

SIM=$1; shift
LIMIT=""; PAR=4; TIMELIMIT=40m; CHECK=120s; BUILD=1; EXTRA=()
while [ $# -gt 0 ]; do
  case "$1" in
    --limit)          LIMIT=$2; shift 2 ;;
    --parallelism)    PAR=$2; shift 2 ;;
    --timelimit)      TIMELIMIT=$2; shift 2 ;;
    --checktimelimit) CHECK=$2; shift 2 ;;
    --skip-build)     BUILD=0; shift ;;
    --)               shift; EXTRA=("$@"); break ;;
    -h|--help)        usage ;;
    *) echo "unknown option: $1" >&2; usage ;;
  esac
done

REPO=$(cd "$(dirname "$0")/../.." && pwd)
HIVE_DIR=${HIVE_DIR:-$HOME/hive}
[ -x "$HIVE_DIR/hive" ] || { echo "no hive binary at $HIVE_DIR/hive (run: cd $HIVE_DIR && go build .)" >&2; exit 1; }

cd "$REPO"
if [ "$BUILD" = 1 ]; then
  sbt -batch assembly
fi
JAR=$(ls -t target/scala-3.*/fukuii-assembly-*.jar 2>/dev/null | head -1)
[ -n "$JAR" ] || { echo "no assembly jar under target/ (drop --skip-build)" >&2; exit 1; }

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
args=(--sim "$SIM" --client fukuii --sim.parallelism "$PAR" --sim.timelimit "$TIMELIMIT"
      --client.checktimelimit="$CHECK" --loglevel 3)
[ -n "$LIMIT" ] && args+=(--sim.limit "$LIMIT")
echo "+ ./hive ${args[*]} ${EXTRA[*]}"
./hive "${args[@]}" "${EXTRA[@]}" || true

# Summarise the newest result file: pass/fail counts and failing test names.
LATEST=$(ls -t workspace/logs/*.json 2>/dev/null | grep -v hive.json | head -1 || true)
[ -n "$LATEST" ] || { echo "no result JSON in $HIVE_DIR/workspace/logs"; exit 1; }
python3 - "$LATEST" <<'PY'
import json, sys
d = json.load(open(sys.argv[1]))
cases = d.get("testCases", {}).values()
fails = [c["name"] for c in cases if not c["summaryResult"]["pass"]]
print(f"{sys.argv[1]}: {len(cases) - len(fails)} passed, {len(fails)} failed")
for n in sorted(fails):
    print("  FAIL", n)
sys.exit(1 if fails else 0)
PY
