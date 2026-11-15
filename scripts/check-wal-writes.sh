#!/usr/bin/env bash
# Spec 018 Track C, C5a part 5: no write of the node's database goes round the write-ahead log.
#
# On the unified state layout a sync's hand-off copies nothing: the rows the sync wrote are the state, and what makes
# them durable is the one synced write that ends the sync (the anchor, the sync done, the best block). A synced write
# syncs the log up to itself, so every write before it is on the disk with it: if it was written to the log. A write
# with the log turned off, or a file of rows ingested beside it, is not, and a kill after the hand-off would leave an
# anchor over rows that are gone (data-model 7.4; vault). Nothing in the types stops such a write, so this check
# fails on any use of the RocksDB calls that make one, under the main sources.
#
# Usage: check-wal-writes.sh [--root DIR]
#   --root DIR   the tree to check (default: this repository)
# Exit: 0 none; 1 a use was found; 2 the check could not run.
#
# Tested by src/test/scala/com/chipprbots/ethereum/scripts/WalWriteRatchetSpec.scala.
set -euo pipefail
export LC_ALL=C

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
while [ $# -gt 0 ]; do
  case "$1" in
    --root) ROOT="$2"; shift 2 ;;
    *) echo "usage: $0 [--root DIR]" >&2; exit 2 ;;
  esac
done

# The calls that write beside the log: the option that turns it off, and the ingest of a file of rows.
PATTERN='\b(setDisableWAL|disableWAL|ingestExternalFile|SstFileWriter|IngestExternalFileOptions)\b'

DIRS=()
for dir in "$ROOT"/src/main/scala "$ROOT"/*/src/main/scala; do
  [ -d "$dir" ] && DIRS+=("$dir")
done
if [ ${#DIRS[@]} -eq 0 ]; then
  echo "no main sources under $ROOT" >&2
  exit 2
fi

# a line whose first characters are `//`, `/*` or `*` is a comment and is not counted
FOUND="$(grep -rnE --include='*.scala' "$PATTERN" "${DIRS[@]}" | grep -vE '^[^:]+:[0-9]+:[[:space:]]*(//|/\*|\*)' || true)"
if [ -n "$FOUND" ]; then
  echo "A write that goes round the write-ahead log:"
  echo "$FOUND" | sed "s|^$ROOT/|  |"
  echo
  echo "On the unified state layout the rows of a sync are made durable by the synced write that ends it, which syncs"
  echo "the log and nothing else (spec 018 data-model 7.4). Write through the log, or sync what was written beside it"
  echo "before the hand-off and say so here."
  exit 1
fi
echo "writes beside the write-ahead log: none"
