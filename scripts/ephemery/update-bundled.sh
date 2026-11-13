#!/usr/bin/env bash
#
# update-bundled.sh — replace the Ephemery iteration built into the jar (spec 028 FR-003; for maintainers).
#
# Usage:
#   scripts/ephemery/update-bundled.sh ephemery-N
#
# What it does:
#   1. builds the staged distribution (`sbt stage`, under the sbt lock when /home/dontpanic/work/.sbt.lock exists);
#   2. runs FUKUII_BIN=target/universal/stage/bin/fukuii scripts/ephemery/fetch-genesis.sh --release ephemery-N
#      --out <tmp>. The script downloads the release and the fukuii rule engine validates it and writes the files:
#      it refuses, and this script stops, when the release does not validate (a hash mismatch, a feature fukuii cannot
#      represent, ...);
#   3. replaces src/main/resources/ephemery/bundled/ephemery-* (and index.txt, which names the iteration) with ephemery-N/{ephemery-chain.conf,
#      ephemery-genesis.json, release.json};
#   4. copies the raw execution-layer inputs (genesis.json, retention.vars, deposit_contract_block_hash.txt,
#      enodes.txt, boot_enr.txt) from <tmp>/ephemery-N/testnet/ to src/test/resources/ephemery/releases/ephemery-N/,
#      so BundledEphemerySpec and the golden render test always cover the bundled iteration;
#   5. prints the files to `git add`.
#
# Environment:
#   SKIP_STAGE=1   use an existing target/universal/stage (it must be built from this checkout)
#   FUKUII_BIN     the launcher to use instead of the staged one
#
# The release's data is downloaded into a new temporary directory and only read, never executed.

set -euo pipefail

die() { echo "update-bundled: ERROR: $*" >&2; exit 1; }

[[ $# -eq 1 && "$1" =~ ^ephemery-[0-9]+$ ]] || die "usage: scripts/ephemery/update-bundled.sh ephemery-N"
RELEASE="$1"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
BUNDLED="src/main/resources/ephemery/bundled"
RAW="src/test/resources/ephemery/releases/$RELEASE"
BIN="${FUKUII_BIN:-$ROOT/target/universal/stage/bin/fukuii}"

if [[ -z "${SKIP_STAGE:-}" && -z "${FUKUII_BIN:-}" ]]; then
  LOCK=/home/dontpanic/work/.sbt.lock
  if [[ -e "$LOCK" ]]; then
    flock "$LOCK" sbt -Dsbt.color=false stage
  else
    sbt -Dsbt.color=false stage
  fi
fi
[[ -x "$BIN" ]] || die "no fukuii launcher at $BIN (run sbt stage, or set FUKUII_BIN)"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# The tool's own log goes under the temporary directory, never a node's datadir.
FUKUII_BIN="$BIN" JAVA_OPTS="${JAVA_OPTS:-} -Dlogging.logs-dir=$TMP/logs" \
  "$ROOT/scripts/ephemery/fetch-genesis.sh" --release "$RELEASE" --out "$TMP" \
  || die "fetch-genesis.sh refused $RELEASE; nothing was changed"

SRC="$TMP/$RELEASE"
for f in chains/ephemery-chain.conf chains/ephemery-genesis.json release.json; do
  [[ -s "$SRC/$f" ]] || die "$SRC/$f is missing or empty; nothing was changed"
done
for f in genesis.json retention.vars deposit_contract_block_hash.txt; do
  [[ -s "$SRC/testnet/$f" ]] || die "$SRC/testnet/$f is missing or empty; nothing was changed"
done

# Only now touch the tree.
rm -rf "${BUNDLED:?}"/ephemery-*
mkdir -p "$BUNDLED/$RELEASE" "$RAW"
cp "$SRC/chains/ephemery-chain.conf" "$SRC/chains/ephemery-genesis.json" "$SRC/release.json" "$BUNDLED/$RELEASE/"
echo "$RELEASE" > "$BUNDLED/index.txt"
for f in genesis.json retention.vars deposit_contract_block_hash.txt enodes.txt boot_enr.txt; do
  [[ -f "$SRC/testnet/$f" ]] && cp "$SRC/testnet/$f" "$RAW/$f"
done

echo
echo "Bundled $RELEASE. Add these files (and remove the old iteration's, if git shows deletions):"
echo "  git add $BUNDLED/index.txt"
echo "  git add $BUNDLED/$RELEASE/ephemery-chain.conf $BUNDLED/$RELEASE/ephemery-genesis.json $BUNDLED/$RELEASE/release.json"
for f in genesis.json retention.vars deposit_contract_block_hash.txt enodes.txt boot_enr.txt; do
  [[ -f "$RAW/$f" ]] && echo "  git add $RAW/$f"
done
echo "Then update src/test/resources/ephemery/releases/README.md (the sha256 of the archive) and run:"
echo "  sbt \"testOnly com.chipprbots.ethereum.ephemery.*\""
