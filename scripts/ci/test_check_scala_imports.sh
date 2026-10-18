#!/usr/bin/env bash
# Self-test for check_scala_imports.sh: each bad fixture must exit 1, each good fixture exit 0.
set -uo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
guard="$here/check_scala_imports.sh"
fx="$here/tests/import_guard"
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
fail=0
run() { # kind expected file
  rm -rf "$tmp/t"; mkdir -p "$tmp/t"; cp "$3" "$tmp/t/Fixture.scala"
  "$guard" "$tmp/t" >/dev/null 2>&1; rc=$?
  if [ "$rc" -ne "$2" ]; then echo "FAIL: $1 $(basename "$3") exit $rc, expected $2"; fail=1
  else echo "ok:   $1 $(basename "$3") exit $rc"; fi
}
for f in "$fx"/bad/*.scala.txt;  do run bad 1 "$f"; done
for f in "$fx"/good/*.scala.txt; do run good 0 "$f"; done
# excluded paths must not be scanned
mkdir -p "$tmp/x/project"; echo 'import scala.collection.mutable._' > "$tmp/x/project/Build.scala"
"$guard" "$tmp/x" >/dev/null 2>&1 && echo "ok:   project/ excluded" || { echo "FAIL: project/ not excluded"; fail=1; }
exit $fail
