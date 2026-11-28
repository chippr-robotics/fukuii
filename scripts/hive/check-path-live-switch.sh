#!/usr/bin/env bash
# Checks the hive adapter's switch for the unified state layout guard (B-314):
#   1. hive/fukuii/fukuii.sh passes -Dfukuii.state.path-live=true only when FUKUII_PATH_LIVE is
#      1/true, and its java command line is otherwise byte-for-byte the one it builds with the
#      variable unset (empty, 0 and false included: the Dockerfile's default ENV is empty);
#   2. an unknown value stops the script before java runs;
#   3. hive/fukuii/Dockerfile declares the build arg and exports it;
#   4. scripts/hive/path-live-clients.sh writes the client file hive needs.
#
# fukuii.sh is run on a copy whose absolute container paths (/app, /genesis.json, ...) are moved
# under a temporary root, with a stub `java` on PATH that prints its arguments, one per line.
# Needs bash and sed only (no jq: no genesis is given). Not wired into CI.
#
# Usage: scripts/hive/check-path-live-switch.sh
set -euo pipefail

REPO=$(cd "$(dirname "$0")/../.." && pwd)
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
FAILS=0
fail() { echo "FAIL: $*" >&2; FAILS=$((FAILS + 1)); }
pass() { echo "ok:   $*"; }

ROOT="$WORK/root"
mkdir -p "$ROOT" "$WORK/bin"
sed -e "s#\"/app/#\"$ROOT/app/#g" \
    -e "s#=/app/#=$ROOT/app/#g" \
    -e "s# /app/# $ROOT/app/#g" \
    -e "s#\"/genesis.json\"#\"$ROOT/genesis.json\"#g" \
    -e "s#\"/jwtsecret\"#\"$ROOT/jwtsecret\"#g" \
    -e "s#/chain.rlp#$ROOT/chain.rlp#g" \
    -e "s#/blocks/#$ROOT/blocks/#g" \
    -e "s#/dao-drain-list.txt#$ROOT/dao-drain-list.txt#g" \
    -e "s#/mapper.jq#$ROOT/mapper.jq#g" \
    "$REPO/hive/fukuii/fukuii.sh" > "$WORK/fukuii.sh"
# The copy must not touch the real filesystem root: every absolute path left is one it only reads
# or tests for, never one it writes.
if grep -nE '(>|mkdir -p|cat .*>) *"?/(app|genesis|jwtsecret|chain)' "$WORK/fukuii.sh" | grep -v "$ROOT" >/dev/null; then
  fail "fukuii.sh writes an absolute path this check does not relocate; extend the sed list above"
fi
cat > "$WORK/bin/java" <<'STUB'
#!/usr/bin/env bash
printf '%s\n' "$@"
STUB
chmod +x "$WORK/bin/java"

# Run fukuii.sh with a fixed, post-merge-like hive environment plus the given assignments.
run() {
  env -i PATH="$WORK/bin:/usr/bin:/bin" HOME="$WORK" \
    HIVE_TERMINAL_TOTAL_DIFFICULTY=0 HIVE_CHAIN_ID=1 HIVE_SHANGHAI_TIMESTAMP=0 \
    "$@" bash "$WORK/fukuii.sh" 2>"$WORK/stderr"
}

FLAG=-Dfukuii.state.path-live=true
baseline=$(run)
[ -n "$baseline" ] || fail "fukuii.sh printed no java command line (stub not reached)"
if printf '%s\n' "$baseline" | grep -qx -- "$FLAG"; then
  fail "guard flag present with FUKUII_PATH_LIVE unset"
else
  pass "unset: no guard flag"
fi

for v in "" 0 false FALSE False; do
  out=$(run FUKUII_PATH_LIVE="$v")
  if [ "$out" = "$baseline" ]; then pass "FUKUII_PATH_LIVE='$v': command line identical to unset"
  else fail "FUKUII_PATH_LIVE='$v' changes the command line"; diff <(echo "$baseline") <(echo "$out") >&2 || true
  fi
done

for v in 1 true TRUE True; do
  out=$(run FUKUII_PATH_LIVE="$v")
  added=$(diff <(echo "$baseline") <(echo "$out") | grep '^>' || true)
  removed=$(diff <(echo "$baseline") <(echo "$out") | grep '^<' || true)
  if [ "$added" = "> $FLAG" ] && [ -z "$removed" ]; then
    pass "FUKUII_PATH_LIVE='$v': exactly $FLAG added"
  else
    fail "FUKUII_PATH_LIVE='$v': expected exactly '$FLAG' added, got added='$added' removed='$removed'"
  fi
  # The flag must reach java before -jar: system properties after it are program arguments.
  before_jar=$(printf '%s\n' "$out" | sed '/^-jar$/q')
  printf '%s\n' "$before_jar" | grep -qx -- "$FLAG" || fail "FUKUII_PATH_LIVE='$v': $FLAG is not before -jar"
done

for v in yes on 2 " 1"; do
  if out=$(run FUKUII_PATH_LIVE="$v"); then
    fail "FUKUII_PATH_LIVE='$v' was accepted (exit 0)"
  elif [ -n "$out" ]; then
    fail "FUKUII_PATH_LIVE='$v': java ran before the refusal"
  else
    pass "FUKUII_PATH_LIVE='$v': refused"
  fi
done

DF="$REPO/hive/fukuii/Dockerfile"
grep -qx 'ARG FUKUII_PATH_LIVE=' "$DF" && pass "Dockerfile: ARG FUKUII_PATH_LIVE (empty default)" \
  || fail "Dockerfile: no 'ARG FUKUII_PATH_LIVE=' line"
grep -qx 'ENV FUKUII_PATH_LIVE=${FUKUII_PATH_LIVE}' "$DF" && pass "Dockerfile: ENV FUKUII_PATH_LIVE exported" \
  || fail "Dockerfile: FUKUII_PATH_LIVE is not exported with ENV"
arg_line=$(grep -n '^ARG FUKUII_PATH_LIVE' "$DF" | cut -d: -f1)
aot_line=$(grep -n 'aot-train.sh &&' "$DF" | cut -d: -f1)
if [ -n "$arg_line" ] && [ -n "$aot_line" ] && [ "$arg_line" -gt "$aot_line" ]; then
  pass "Dockerfile: the build arg comes after the AOT training step"
else
  fail "Dockerfile: ARG FUKUII_PATH_LIVE must follow the AOT training step (default image unchanged)"
fi

GEN="$REPO/scripts/hive/path-live-clients.sh"
expected='- client: fukuii
  nametag: pathlive
  build_args:
    FUKUII_PATH_LIVE: "1"
- client: go-ethereum'
got=$("$GEN" "fukuii, go-ethereum")
[ "$got" = "$expected" ] && pass "path-live-clients.sh: fukuii,go-ethereum" \
  || { fail "path-live-clients.sh output differs"; diff <(echo "$expected") <(echo "$got") >&2 || true; }
[ "$("$GEN")" = "$(printf '%s\n' "$expected" | head -4)" ] && pass "path-live-clients.sh: default is fukuii alone" \
  || fail "path-live-clients.sh: default output differs"
if "$GEN" go-ethereum >/dev/null 2>&1; then fail "path-live-clients.sh accepted a list without fukuii"
else pass "path-live-clients.sh: refuses a list without fukuii"; fi
if "$GEN" 'fukuii,a b;c' >/dev/null 2>&1; then fail "path-live-clients.sh accepted a malformed name"
else pass "path-live-clients.sh: refuses a malformed name"; fi

if [ "$FAILS" -ne 0 ]; then
  echo "$FAILS check(s) failed" >&2
  exit 1
fi
echo "all path-live switch checks passed"
