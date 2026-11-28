#!/usr/bin/env bash
# Self-test for sbt-retry.sh with a fake sbt: download failures are retried, real failures are not, the exit code is sbt's.
set -uo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
fail=0
# fake sbt: prints the line for attempt N from $tmp/script (one line per attempt: "<exit> <text>"), counts calls
cat > "$tmp/sbt" <<'EOF'
#!/usr/bin/env bash
n=$(( $(cat "$FAKE_DIR/calls" 2>/dev/null || echo 0) + 1 )); echo "$n" > "$FAKE_DIR/calls"
line="$(sed -n "${n}p" "$FAKE_DIR/script")"; code="${line%% *}"; echo "${line#* }"; exit "$code"
EOF
chmod +x "$tmp/sbt"
check() { # name expected_exit expected_calls script-lines...
  local name="$1" want_rc="$2" want_calls="$3"; shift 3
  rm -f "$tmp/calls"; printf '%s\n' "$@" > "$tmp/script"
  FAKE_DIR="$tmp" SBT="$tmp/sbt" SBT_RETRY_DELAY=0 SBT_RETRY_ATTEMPTS=3 "$here/sbt-retry.sh" compile >/dev/null 2>&1; rc=$?
  calls="$(cat "$tmp/calls")"
  if [ "$rc" -ne "$want_rc" ] || [ "$calls" -ne "$want_calls" ]; then
    echo "FAIL: $name: exit $rc calls $calls, expected exit $want_rc calls $want_calls"; fail=1
  else echo "ok:   $name"; fi
}
check "success runs once" 0 1 "0 [success] Total time: 1 s"
check "429 then success" 0 2 "1 Server returned HTTP response code: 429 for URL: https://repo1.maven.org/x.pom" "0 [success]"
check "scalafix fetch then success" 0 2 "1 [error] Failed to fetch scalafix-cli_3.9.0:0.14.9" "0 [success]"
check "download error every time" 1 3 "1 CantDownloadModule org.scala-sbt:sbt" "1 CantDownloadModule" "1 could not retrieve sbt 1.13.0"
check "compile error not retried" 1 1 "1 [error] -- [E007] Type Mismatch Error: Foo.scala:3:4"
check "test failure not retried" 1 1 "1 [info] - x *** FAILED ***"
check "fetch error beside a test failure not retried" 1 1 "1 download error ... *** FAILED ***"
check "other failure not retried" 2 1 "2 [error] something else"
exit $fail
