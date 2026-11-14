#!/usr/bin/env bash
# Spec 018 Track C, C4b part 3 (T140): the allow-list ratchet of the writers of the best block and the index of blocks
# by number.
#
# On the unified state layout the state anchor and the best block are one block (data-model 5.1, I1): they move
# together, in a block's own commit or in a move of the head, or not at all. A writer that moves the best block or
# the index alone breaks that, and a move's own rows, built outside the state lock, are then stale (beacon, part 2b).
# Nothing in the types stops a new caller from writing either, so this check counts every write and every call of a
# head writer under src/main/scala and compares the counts with scripts/head-writer-allowlist.txt, where each line
# says why that writer cannot move the head alone on the unified layout. It fails as the state accessors' check
# does (scripts/check-state-accessors.sh):
#   - a use in a file that has no line for that entry point;
#   - more uses than the line allows, or fewer, or a line whose file is gone;
#   - a line with no reason, an unknown entry point, a count that is not a positive number, a line given twice.
# A line whose first characters are `//`, `/*` or `*` is a comment and is not counted.
#
# Usage: check-head-writers.sh [--root DIR] [--allowlist FILE] [--inventory]
#   --root DIR        the tree to check (default: this repository)
#   --allowlist FILE  the list to check it against (default: DIR/scripts/head-writer-allowlist.txt)
#   --inventory       print `entry-point count file` for every use in the tree, and check nothing
# Exit: 0 as listed; 1 the tree and the list differ; 2 the check could not run.
#
# Tested by src/test/scala/com/chipprbots/ethereum/scripts/HeadWriterRatchetSpec.scala.
set -euo pipefail
export LC_ALL=C

# The entry points, by the name the allow-list gives them. Extended regular expressions; a definition counts as a use.
#
#   best-pointer    a write of the best block's AppState rows
#   index-write     a write of the index of blocks by number
#   head-writer     a writer of the head by name: what moves the best block, the index, or both
#   block-store     a store of a block that indexes it by number, or saves it as the best block
#
# This is text, not types: a writer under another name gets past it. The runtime check behind it is the refusal of
# the head writers on the unified layout (BlockchainWriter, Blockchain.removeBlock, PivotAnchor.reanchor) and the
# check of the best block under the state lock (PathStateStorage.bestIsTheAnchor).
NAMES="best-pointer index-write head-writer block-store"

regex_of() {
  case "$1" in
    best-pointer) echo '\bputBestBlock(Info|Number)\(' ;;
    index-write)  echo '\bblockNumberMappingStorage\.(put|remove)\(' ;;
    head-writer)  echo '\b(heldBlockIndex|moveHeadInPlace|saveBestKnownBlocks|setCanonicalChainHead|rewriteCanonicalIndex|promoteToCanonicalHead|canonicalHeadUpdate|repairBestBlockNumberMapping|removeBlock|removeChainForNewGenesis|reanchor|discardBlocksAfter)\(' ;;
    block-store)  echo '\b(storeBlock|storeBlockHeader|saveBatch)\(|\bblockchainWriter\.save\(' ;;
    *) return 1 ;;
  esac
}

scope_of() { echo ''; }

COMMENT='^[[:space:]]*(//|/\*|\*)'

usage() { sed -n '/^# Usage:/,/^# Exit:/p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//' >&2; }

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
allowlist=""
inventory=0
while [ "$#" -gt 0 ]; do
  case "$1" in
    --root)      [ "$#" -ge 2 ] || { usage; exit 2; }; root="$2"; shift 2 ;;
    --allowlist) [ "$#" -ge 2 ] || { usage; exit 2; }; allowlist="$2"; shift 2 ;;
    --inventory) inventory=1; shift ;;
    *) usage; exit 2 ;;
  esac
done
[ -n "$allowlist" ] || allowlist="$root/scripts/head-writer-allowlist.txt"

sources="$root/src/main/scala"
if [ ! -d "$sources" ]; then
  echo "head-writers: no main sources at $sources: nothing would be checked" >&2
  exit 2
fi

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

# The uses of one entry point in one file, comment lines aside: `line:text`.
sites() { # regex file
  { grep -nE -e "$1" "$2" || true; } | { grep -vE "^[0-9]+:${COMMENT#^}" || true; }
}

# found: `name count file`, in the order of NAMES and then by file.
: > "$work/found"
for name in $NAMES; do
  regex="$(regex_of "$name")"
  scope="$(scope_of "$name")"
  { grep -rlE --include='*.scala' -e "$regex" "$sources" || true; } | sort | while IFS= read -r file; do
    if [ -n "$scope" ] && ! grep -qE -e "$scope" "$file"; then continue; fi
    count="$({ grep -vE "$COMMENT" "$file" || true; } | { grep -oE -e "$regex" || true; } | wc -l | tr -d ' ')"
    [ "$count" -gt 0 ] && echo "$name $count ${file#"$root"/}" >> "$work/found"
    true
  done
done

if [ "$inventory" -eq 1 ]; then
  cat "$work/found"
  exit 0
fi

if [ ! -f "$allowlist" ]; then
  echo "head-writers: no allow-list at $allowlist" >&2
  exit 2
fi

# listed: the allow-list as `name count file`; what cannot be read goes to `problems`.
awk -v names="$NAMES" -v problems="$work/problems" '
  BEGIN { n = split(names, a, " "); for (i = 1; i <= n; i++) known[a[i]] = 1 }
  /^[[:space:]]*(#|$)/ { next }
  {
    where = "line " FNR ": " $0
    if (!($1 in known)) { print "UNREADABLE: `" $1 "` is not an entry point (" where ")" > problems; next }
    if ($2 !~ /^[1-9][0-9]*$/) { print "UNREADABLE: `" $2 "` is not a count above zero (" where ")" > problems; next }
    if (NF < 3) { print "UNREADABLE: no file (" where ")" > problems; next }
    if (NF < 4) { print "NO REASON: every line says why the use is there (" where ")" > problems; next }
    if ($4 !~ /^(MOVE|REFUSED|DEF|HASH|PRE|START|C4D|C4E)$/) { print "NO VERDICT: a reason starts with MOVE, REFUSED, DEF, HASH, PRE, START, C4D or C4E (" where ")" > problems; next }
    if (($1, $3) in seen) { print "LISTED TWICE: " $1 " in " $3 " (" where ")" > problems; next }
    seen[$1, $3] = 1
    print $1, $2, $3
  }
' "$allowlist" > "$work/listed"
touch "$work/problems"

# verdicts: `kind name file found allowed` for every difference between the tree and the list.
# (By file name and not by `FNR == NR`, which is also true of the second file when the list is empty.)
awk '
  FILENAME == ARGV[1] { allowed[$1, $3] = $2; next }
  {
    met[$1, $3] = 1
    if (!(($1, $3) in allowed)) print "unlisted", $1, $3, $2, 0
    else if ($2 > allowed[$1, $3] + 0) print "over", $1, $3, $2, allowed[$1, $3]
    else if ($2 < allowed[$1, $3] + 0) print "under", $1, $3, $2, allowed[$1, $3]
  }
  END {
    for (key in allowed) if (!(key in met)) { split(key, k, SUBSEP); print "gone", k[1], k[2], 0, allowed[key] }
  }
' "$work/listed" "$work/found" | sort > "$work/verdicts"

failed=0
while IFS= read -r problem; do
  echo "head-writers: $problem"
  failed=1
done < "$work/problems"

while read -r kind name file found allowed; do
  failed=1
  case "$kind" in
    unlisted)
      echo "head-writers: NOT LISTED: $name in $file ($found)"
      sites "$(regex_of "$name")" "$root/$file" | sed "s|^|  $file:|"
      echo "  if it has to stay, the line to add:  $name $found $file <how it cannot move the head alone on the unified layout>"
      ;;
    over)
      echo "head-writers: MORE THAN LISTED: $name in $file: $found found, $allowed allowed"
      sites "$(regex_of "$name")" "$root/$file" | sed "s|^|  $file:|"
      echo "  if the new use has to stay, the line's count becomes:  $name $found $file ..."
      ;;
    under)
      echo "head-writers: FEWER THAN LISTED: $name in $file: $found found, $allowed allowed"
      echo "  lower the line's count:  $name $found $file ..."
      ;;
    gone)
      echo "head-writers: NO LONGER USED: $name in $file ($allowed allowed)"
      echo "  remove the line:  $name $allowed $file ..."
      ;;
  esac
done < "$work/verdicts"

if [ "$failed" -ne 0 ]; then
  cat >&2 <<EOF

The best block or the index of blocks by number is written somewhere the allow-list does not say, or the list says
more than is there. A NEW writer: on the unified state layout the best block moves only with the state (spec 018
Track C, I1). Move the head with BlockchainWriter.moveHeadInPlace (PathStateStorage.moveHead with canonicalHeadUpdate), or refuse on that
layout; then add a line to ${allowlist#"$root"/} that says which. A use that is GONE: lower its count or remove its
line, in the same change.
EOF
  exit 1
fi

echo "head writers: $(wc -l < "$work/listed" | tr -d ' ') lines and $(awk '{ s += $2 } END { print s + 0 }' "$work/listed") uses, all as listed"
