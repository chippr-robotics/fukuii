#!/usr/bin/env bash
# EVM throughput harness. Runs BlockchainTest fixture cases through the ETH execution path
# (BlockchainTestBench -> EthereumTestExecutor -> BlockExecution.executeAndValidateBlock) and prints
# wall time and Mgas/s per case, next to a PASS/FAIL that checks gasUsed, receipts, state root and
# post-state against the fixture.
#
#   scripts/bench/blockchain-test-bench.sh <fixture.json> [caseRegex] [repeat]
#
# Example (hive's slowest legacy consensus vectors):
#   scripts/bench/blockchain-test-bench.sh loopMul-bt.json 'loopMul_d2g0v0_Cancun'
#
# The JVM gets hive's client flags (hive/fukuii/fukuii.sh): 512 MB heap, 8 MB thread stacks, G1.
#
# Environment:
#   JFR=<file.jfr>       record a JDK Flight Recorder profile of the run ("profile" settings)
#   JAVA_OPTS="..."      extra JVM flags, appended after the hive ones
#   BENCH_CP_REFRESH=1   re-resolve the classpath (after a dependency change; not needed after a
#                        source change, the classpath points at the compiled class directories)
#   SBT="..."            how to invoke sbt for the classpath (default: sbt -batch)
#   JDK_IMAGE=<image>    run the JVM in this Docker image instead of the host's java, e.g.
#                        chipprbots/fukuii:latest for the JDK hive runs (the classpath, the
#                        fixture and any JFR/MAIN_CLASSES paths are mounted at their host paths)
#   MAIN_CLASSES=<dir>   use this copy of the root module's compiled classes instead of
#                        target/scala-3.*/classes: an A/B run against a saved build of another
#                        commit (the harness and test classes must still link against it)
#
# The classpath is the `it` configuration's, so compile first: sbt "IntegrationTest / compile".
set -euo pipefail

if [[ $# -lt 1 ]]; then
  sed -n '2,10p' "$0" >&2
  exit 2
fi

root="$(cd "$(dirname "$0")/../.." && pwd)"
cp_file="$root/target/bench-it-classpath.txt"

if [[ ! -s "$cp_file" || -n "${BENCH_CP_REFRESH:-}" ]]; then
  mkdir -p "$root/target"
  (cd "$root" && ${SBT:-sbt -batch} "export IntegrationTest / fullClasspath") \
    | grep -E '^/.*\.jar' | tail -1 > "$cp_file"
  [[ -s "$cp_file" ]] || { echo "could not resolve the it classpath" >&2; exit 1; }
fi

cp="$(cat "$cp_file")"
if [[ -n "${MAIN_CLASSES:-}" ]]; then
  cp="$(tr ':' '\n' <<<"$cp" | sed -E "s#^$root/target/scala-3[^/]*/classes\$#$MAIN_CLASSES#" | paste -sd:)"
fi

jvm=(-Xmx512m -Xms128m -Xss8M -XX:+UseG1GC)
if [[ -n "${JFR:-}" ]]; then
  jvm+=("-XX:StartFlightRecording=filename=$JFR,settings=profile,dumponexit=true")
fi
# shellcheck disable=SC2206
jvm+=(${JAVA_OPTS:-})

main=(com.chipprbots.ethereum.ethtest.BlockchainTestBench "$@")
if [[ -z "${JDK_IMAGE:-}" ]]; then
  exec java "${jvm[@]}" -cp "$cp" "${main[@]}"
fi

# Every directory the JVM reads or writes, at the same path inside the container.
mounts=()
declare -A seen=()
add_mount() { # <dir> <ro|rw>
  local dir
  dir="$(cd "$1" && pwd)"
  [[ -n "${seen[$dir]:-}" ]] && return
  seen[$dir]=1
  mounts+=(-v "$dir:$dir:$2")
}
add_mount "$root" ro
add_mount "$(dirname "$(realpath "$1")")" ro
[[ -n "${MAIN_CLASSES:-}" ]] && add_mount "$MAIN_CLASSES" ro
[[ -n "${JFR:-}" ]] && add_mount "$(dirname "$JFR")" rw
coursier="${COURSIER_CACHE:-$HOME/.cache/coursier}"
[[ -d "$coursier" ]] && add_mount "$coursier" ro
while IFS= read -r entry; do
  case "$entry" in
    "$root"/* | "$coursier"/*) ;; # already mounted
    *.jar) add_mount "$(dirname "$entry")" ro ;;
    *) if [[ -d "$entry" ]]; then add_mount "$entry" ro; fi ;;
  esac
done < <(tr ':' '\n' <<<"$cp")

exec docker run --rm --memory=2g -u "$(id -u):$(id -g)" -e HOME=/tmp -w /tmp "${mounts[@]}" \
  --entrypoint java "$JDK_IMAGE" "${jvm[@]}" -cp "$cp" "${main[@]}"
