#!/usr/bin/env bash
# Spec 018 Track C, T062: the allow-list ratchet of the state's entry points.
#
# The position store (CF `t` and `u`) answers a read that names where the node is. A caller that has only a hash
# cannot be served by it: a root handed to an accessor, a batch read by hash, a trie built from a root alone, a
# visitor that is given a hash node and no position. Until the track's last slice the hash store is still there,
# and nothing in the types stops a new caller from taking the old door. This check counts every use of those
# doors under src/main/scala and compares the counts with scripts/state-accessor-allowlist.txt, where each line
# carries its reason. It fails on
#   - a use in a file that has no line for that entry point;
#   - more uses than the line allows;
#   - FEWER uses than the line allows, or a line whose file is gone: the list only shrinks, so a use that is
#     removed takes its allowance with it;
#   - a line with no reason, an unknown entry point, a count that is not a positive number, a line given twice.
# A line whose first characters are `//`, `/*` or `*` is a comment and is not counted.
#
# Usage: check-state-accessors.sh [--root DIR] [--allowlist FILE] [--inventory]
#   --root DIR        the tree to check (default: this repository)
#   --allowlist FILE  the list to check it against (default: DIR/scripts/state-accessor-allowlist.txt)
#   --inventory       print `entry-point count file` for every use in the tree, and check nothing
# Exit: 0 as listed; 1 the tree and the list differ; 2 the check could not run.
#
# Tested by src/test/scala/com/chipprbots/ethereum/scripts/StateAccessorRatchetSpec.scala.
set -euo pipefail
export LC_ALL=C

# The entry points, by the name the allow-list gives them. Extended regular expressions; a definition counts as
# a use, so the files that define them are on the list too, and the check is known to look where they are.
#
#   backing-mpt, readonly-mpt   Blockchain's two: the store of a block number, and a buffer over the store
#   backing, readonly           StateStorage's two, the same
#   bulk-load                   the store without its per-node undo rows
#   stage-block                 the staged write of a block
#   prefetch-reader             a reader for the prefetch of a block
#   node-by-hash                one node, by its hash
#   get-by-hash                 a store's `get`, on the names a store usually has
#   multi-get, raw-nodes        the batch reads, which a wrapper in front of execution does not see
#   raw-store                   a write, a repair or a delete of a node by its hash
#   snap-store                  SNAP's own two names for the store of a pivot
#   store-handed                a method that hands out a store: where the next door would appear, under a new name
#   storage-mpt                 a storage trie from its root alone
#   visit-hash                  a visitor that is handed a hash node, and no position
#   trie-of                     a trie built over a store
#   store-built                 a hash store built by hand
#
# This is text, not types. `get-by-hash` knows a store by its name, so a store called something else gets past
# it; and since a map is often called `storage` too, it looks only in files that name a store or a trie
# (scope_of). The proof that nothing reads by hash alone is the track's last slice, which deletes the read;
# until then this is a tripwire on the shapes the code uses today.
NAMES="backing-mpt readonly-mpt backing readonly bulk-load stage-block prefetch-reader node-by-hash get-by-hash multi-get raw-nodes raw-store snap-store store-handed storage-mpt visit-hash trie-of store-built"

# What a type argument is made of, brackets aside. `trie-of` takes type arguments nested two deep, and a `(` straight
# after them: `MerklePatriciaTrie[Address, Account](root, store)` is a trie being built, and
# `wrap[Address, Account, MerklePatriciaTrie[Address, Account]](...)` is not.
TYPE='[^][()]'

regex_of() {
  case "$1" in
    backing-mpt)     echo '\bgetBackingMptStorage\b' ;;
    readonly-mpt)    echo '\bgetReadOnlyMptStorage\b' ;;
    backing)         echo '\bgetBackingStorage\b' ;;
    readonly)        echo '\bgetReadOnlyStorage\b' ;;
    bulk-load)       echo '\bgetBulkLoadStorage\b' ;;
    stage-block)     echo '\bstageBlock(State)?(Apart)?\(' ;;
    prefetch-reader) echo '\bprefetchReader\(' ;;
    node-by-hash)    echo '\bgetMptNodeByHash\b|\bgetNode\(' ;;
    get-by-hash)     echo '\b(mptStorage|storage|source|hashStore|mpt|getReadOnlyStorage)\.get\(' ;;
    multi-get)       echo '\bmultiGetNodes\b' ;;
    raw-nodes)       echo '\brawNodes(At)?\(' ;;
    raw-store)       echo '\b(storeRawNodes|saveNode|repairNode|deleteBadNode)\(' ;;
    snap-store)      echo '\b(trieStore|getOrCreateMptStorage)\(' ;;
    store-handed)    echo '\bdef [A-Za-z0-9_]+(\[[^]]*\])?(\([^()]*\))*: MptStorage([[:space:]]*=([^>]|$)|[[:space:]]*$)' ;;
    storage-mpt)     echo '\bstorageMpt\(' ;;
    visit-hash)      echo '\bdef visitHash\(' ;;
    trie-of)         echo "\\bMerklePatriciaTrie(\\[($TYPE|\\[($TYPE|\\[$TYPE*\\])*\\])*\\])?\\(" ;;
    store-built)     echo '\b(SerializingMptStorage|ReadOnlyNodeStorage)\(' ;;
    *) return 1 ;;
  esac
}

# What a file has to mention for an entry point to be looked for in it; nothing, for all but one.
scope_of() {
  case "$1" in
    get-by-hash) echo '[Mm]ptStorage|[Ss]tateStorage|MerklePatriciaTrie|[Tt]rieStore' ;;
    *) echo '' ;;
  esac
}

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
[ -n "$allowlist" ] || allowlist="$root/scripts/state-accessor-allowlist.txt"

sources="$root/src/main/scala"
if [ ! -d "$sources" ]; then
  echo "state-accessors: no main sources at $sources: nothing would be checked" >&2
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
  echo "state-accessors: no allow-list at $allowlist" >&2
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
  echo "state-accessors: $problem"
  failed=1
done < "$work/problems"

while read -r kind name file found allowed; do
  failed=1
  case "$kind" in
    unlisted)
      echo "state-accessors: NOT LISTED: $name in $file ($found)"
      sites "$(regex_of "$name")" "$root/$file" | sed "s|^|  $file:|"
      echo "  if it has to stay, the line to add:  $name $found $file <why it is there, and the slice that removes it>"
      ;;
    over)
      echo "state-accessors: MORE THAN LISTED: $name in $file: $found found, $allowed allowed"
      sites "$(regex_of "$name")" "$root/$file" | sed "s|^|  $file:|"
      echo "  if the new use has to stay, the line's count becomes:  $name $found $file ..."
      ;;
    under)
      echo "state-accessors: FEWER THAN LISTED: $name in $file: $found found, $allowed allowed"
      echo "  lower the line's count:  $name $found $file ..."
      ;;
    gone)
      echo "state-accessors: NO LONGER USED: $name in $file ($allowed allowed)"
      echo "  remove the line:  $name $allowed $file ..."
      ;;
  esac
done < "$work/verdicts"

if [ "$failed" -ne 0 ]; then
  cat >&2 <<EOF

The state's entry points are used somewhere the allow-list does not say, or the list says more than is there.
A NEW use: the position store cannot serve a reader that has only a hash (spec 018 Track C). Read through a view
of a block and name the position; if the use has to stay for now, add a line with its reason to
${allowlist#"$root"/}. A use that is GONE: lower its count or remove its line, in the same change.
EOF
  exit 1
fi

echo "state accessors: $(wc -l < "$work/listed" | tr -d ' ') lines and $(awk '{ s += $2 } END { print s + 0 }' "$work/listed") uses, all as listed"
