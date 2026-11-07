#!/usr/bin/env bash
#
# check-fixture.sh — tie the EphemeryGenesisSpec fixtures to fetch-genesis.sh.
#
# Regenerates each fixture's release with the CURRENT fetch-genesis.sh and diffs the generated chain conf and genesis
# against src/test/resources/ephemery/<release>/. Run after any change to fetch-genesis.sh: a difference means either the
# change altered the mapping (then the spec no longer tests what the script produces) or the fixture is stale. Two
# fixtures, since only ephemery-165 has Amsterdam at genesis.
#
# Usage:
#   scripts/ephemery/check-fixture.sh                            # both, downloading each testnet-all.tar.gz
#   scripts/ephemery/check-fixture.sh ephemery-165 TESTNET_DIR   # one, from an already-extracted testnet-all
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT

status=0
check() { # release [testnet dir]
  local release=$1 from=${2:-} fixture="$repo/src/test/resources/ephemery/$1" f
  if [[ -n "$from" ]]; then
    "$here/fetch-genesis.sh" --from-dir "$from" --out "$out" >/dev/null
  else
    "$here/fetch-genesis.sh" --release "$release" --out "$out" >/dev/null
  fi
  for f in ephemery-chain.conf ephemery-genesis.json; do
    if diff -u "$fixture/$f" "$out/$release/chains/$f"; then
      echo "check-fixture: $release/$f identical"
    else
      echo "check-fixture: $release/$f DIFFERS from the committed fixture" >&2
      status=1
    fi
  done
}

if [[ $# -ge 1 ]]; then
  check "$1" "${2:-}"
else
  check ephemery-164
  check ephemery-165
fi
exit "$status"
