#!/usr/bin/env bash
# B-375 (spec 018 T214): the recovery scans' verdicts, pinned v0.12.1 against this checkout, on one seeded store.
#
#   scripts/oracle/b375/run.sh -v <plain clone of tag v0.12.1> [-s "<sbt command>"] [-w <work dir>]
#
# 1. seeds hash-layout RocksDB datadirs with the injected gaps (RecoveryScanOracleSeeder, test source of this checkout);
# 2. compiles RecoveryScanOracleRunner.scala, unchanged, into v0.12.1's test sources and into this checkout's, and runs
#    it on the seeded datadirs in each, one sbt at a time;
# 3. diffs the two verdict files. Exit 0: no difference. Exit 1: a difference (printed). Anything else: the run failed.
#
# The v0.12.1 tree must be a PLAIN clone (git clone --no-checkout, then git checkout v0.12.1), not a git worktree:
# sbt-git cannot build a worktree. The copied runner is removed from both trees on exit.
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

pkg=src/test/scala/com/chipprbots/ethereum/blockchain/sync
runner=RecoveryScanOracleRunner.scala
cleanup() { rm -f "$repo/$pkg/$runner" "$v0121/$pkg/$runner"; }
trap cleanup EXIT

seed="$work/seed"
rm -rf "$seed" "$work/v0121.tsv" "$work/staging.tsv"

echo "== seed ($repo)"
(cd "$repo" && $sbt_cmd "Test/runMain com.chipprbots.ethereum.blockchain.sync.RecoveryScanOracleSeeder $seed")

for side in v0121 staging; do
  tree="$repo"; [ "$side" = v0121 ] && tree="$v0121"
  echo "== scans on $side ($tree)"
  mkdir -p "$tree/$pkg"
  cp "$here/$runner" "$tree/$pkg/$runner"
  (cd "$tree" && $sbt_cmd "Test/runMain com.chipprbots.ethereum.blockchain.sync.RecoveryScanOracleRunner $seed $work/$side.tsv")
  rm -f "$tree/$pkg/$runner"
done

echo "== diff (v0.12.1 vs staging): $(wc -l < "$work/v0121.tsv") verdicts each"
if diff -u --label v0.12.1 --label staging "$work/v0121.tsv" "$work/staging.tsv"; then
  echo "NO DIFFERENCE"
else
  echo "DIFFERENCES FOUND"
  exit 1
fi
