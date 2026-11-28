#!/usr/bin/env bash
# B-212 (spec 018 T052): SNAP responses, pinned v0.12.1 (hash store) against this checkout (position store).
#
#   scripts/oracle/b212/run.sh -v <plain clone of tag v0.12.1> [-s "<sbt command>"] [-w <work dir>]
#
# 1. seeds one state twice (SnapServeOracleSeeder, test source of this checkout): a hash-layout RocksDB datadir for
#    v0.12.1 and a position-layout (unified) datadir for this checkout;
# 2. compiles SnapServeOracleRunner.scala, unchanged, into v0.12.1's test sources and into this checkout's, and runs it
#    on the hash datadir in each; then runs SnapServeOraclePositionRunner (this checkout only) on the position datadir;
#    one sbt at a time. Each runner writes name<TAB>hex(response RLP) for every request of a fixed list;
# 3. diffs v0.12.1 (hash store) against staging on the position store, and, as a control, against staging on the hash
#    store. Exit 0: no difference in either. Exit 1: a difference (printed, truncated). Anything else: the run failed.
#
# The v0.12.1 tree must be a PLAIN clone (git clone --no-checkout, then git checkout v0.12.1), not a git worktree:
# sbt-git cannot build a worktree. The copied runners are removed from both trees on exit.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "$here/../../.." && pwd)"
sbt_cmd="sbt"
work=""
v0121=""
while getopts "v:s:w:" opt; do
  case "$opt" in
    v) v0121="$OPTARG" ;;
    s) sbt_cmd="$OPTARG" ;;
    w) work="$OPTARG" ;;
    *) echo "usage: $0 -v <v0.12.1 clone> [-s sbt] [-w workdir]" >&2; exit 2 ;;
  esac
done
[ -n "$v0121" ] || { echo "-v <plain clone of v0.12.1> is required" >&2; exit 2; }
[ -n "$work" ] || work="$(mktemp -d)"
mkdir -p "$work"

want=21cfa13dfdc968c85e3ff393ab249376f37eba73
have="$(git -C "$v0121" rev-parse HEAD)"
[ "$have" = "$want" ] || { echo "$v0121 is at $have, not v0.12.1 ($want)" >&2; exit 2; }
[ ! -f "$v0121/.git" ] || { echo "$v0121 is a worktree; use a plain clone" >&2; exit 2; }

pkg=src/test/scala/com/chipprbots/ethereum/network/snapserver
runner=SnapServeOracleRunner.scala
position=SnapServeOraclePositionRunner.scala
ns=com.chipprbots.ethereum.network.snapserver
cleanup() { rm -f "$repo/$pkg/$runner" "$repo/$pkg/$position" "$v0121/$pkg/$runner"; }
trap cleanup EXIT

seed="$work/seed"
rm -rf "$seed" "$work/v0121.tsv" "$work/staging-hash.tsv" "$work/staging-position.tsv"

echo "== seed ($repo)"
(cd "$repo" && $sbt_cmd "Test/runMain $ns.SnapServeOracleSeeder $seed")

echo "== serve on v0.12.1, hash store ($v0121)"
mkdir -p "$v0121/$pkg"
cp "$here/$runner" "$v0121/$pkg/$runner"
(cd "$v0121" && $sbt_cmd "Test/runMain $ns.SnapServeOracleRunner $seed $work/v0121.tsv")
rm -f "$v0121/$pkg/$runner"

echo "== serve on staging, hash store and position store ($repo)"
cp "$here/$runner" "$repo/$pkg/$runner"
cp "$here/$position" "$repo/$pkg/$position"
(cd "$repo" && $sbt_cmd \
  "Test/runMain $ns.SnapServeOracleRunner $seed $work/staging-hash.tsv" \
  "Test/runMain $ns.SnapServeOraclePositionRunner $seed $work/staging-position.tsv")
rm -f "$repo/$pkg/$runner" "$repo/$pkg/$position"

echo "== $(wc -l < "$work/v0121.tsv") requests each; response bytes: $(cut -f2 "$work/v0121.tsv" | tr -d '\n' | wc -c) hex chars"
status=0
for side in staging-position staging-hash; do
  if cmp -s "$work/v0121.tsv" "$work/$side.tsv"; then
    echo "NO DIFFERENCE: v0.12.1 (hash store) vs $side"
  else
    echo "DIFFERENCES FOUND: v0.12.1 (hash store) vs $side; differing requests:"
    diff <(cut -f1,2 "$work/v0121.tsv") <(cut -f1,2 "$work/$side.tsv") | grep '^<' | cut -f1 | head -40 || true
    status=1
  fi
done
exit $status
