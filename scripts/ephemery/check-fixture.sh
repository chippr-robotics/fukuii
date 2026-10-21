#!/usr/bin/env bash
#
# check-fixture.sh — tie the EphemeryGenesisSpec fixture to fetch-genesis.sh.
#
# Regenerates release ephemery-164 with the CURRENT fetch-genesis.sh and diffs the generated chain conf and genesis
# against src/test/resources/ephemery/ephemery-164/. Run after any change to fetch-genesis.sh: a difference means
# either the change altered the mapping (then the spec no longer tests what the script produces) or the fixture is
# stale.
#
# Usage:
#   scripts/ephemery/check-fixture.sh                    # downloads ephemery-164's testnet-all.tar.gz
#   scripts/ephemery/check-fixture.sh TESTNET_ALL_DIR    # an already-extracted ephemery-164 testnet-all
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
fixture="$repo/src/test/resources/ephemery/ephemery-164"
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT

if [[ $# -ge 1 ]]; then
  "$here/fetch-genesis.sh" --from-dir "$1" --out "$out" >/dev/null
else
  "$here/fetch-genesis.sh" --release ephemery-164 --out "$out" >/dev/null
fi

status=0
for f in ephemery-chain.conf ephemery-genesis.json; do
  if diff -u "$fixture/$f" "$out/ephemery-164/chains/$f"; then
    echo "check-fixture: $f identical"
  else
    echo "check-fixture: $f DIFFERS from the committed fixture" >&2
    status=1
  fi
done
exit "$status"
