#!/usr/bin/env bash
# check-upstream-adapter.sh — fail when fukuii's hive adapter (hive/fukuii/) differs from the copy
# hive itself builds (clients/fukuii/ in ethereum/hive).
#
# hive/fukuii/ is the single source of the upstream client definition (B-569). fukuii's own hive
# workflows copy hive/fukuii/ over hive's clients/fukuii/ (_hive-sim.yml "Sync fukuii hive
# adapter"), so they never exercise the upstream copy: a change made here and not upstreamed, or
# one made upstream and not brought back, would go unnoticed until hive's own runs broke.
#
# Usage: scripts/hive/check-upstream-adapter.sh [--repo OWNER/NAME] [--ref REF]
#   --repo  hive repository to compare with (default ethereum/hive)
#   --ref   branch, tag or commit (default master)
# Exit: 0 identical; 1 different (the diff is printed); 2 the repository has no clients/fukuii at
# that ref, or it could not be fetched.
set -euo pipefail

REPO=ethereum/hive
REF=master
while [ $# -gt 0 ]; do
  case "$1" in
    --repo) REPO=$2; shift 2 ;;
    --ref) REF=$2; shift 2 ;;
    -h | --help) sed -n '2,14p' "$0"; exit 0 ;;
    *) echo "check-upstream-adapter: unknown argument $1" >&2; exit 2 ;;
  esac
done

HERE=$(cd "$(dirname "$0")/../.." && pwd)
LOCAL="$HERE/hive/fukuii"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

git -c advice.detachedHead=false clone -q --depth=1 --filter=blob:none --no-checkout \
  "https://github.com/$REPO.git" "$WORK/hive" 2>/dev/null || {
  echo "check-upstream-adapter: cannot clone https://github.com/$REPO.git" >&2
  exit 2
}
if ! git -C "$WORK/hive" fetch -q --depth=1 origin "$REF" 2>/dev/null; then
  echo "check-upstream-adapter: $REPO has no ref $REF" >&2
  exit 2
fi
git -C "$WORK/hive" -c advice.detachedHead=false checkout -q FETCH_HEAD -- clients/fukuii 2>/dev/null || {
  echo "check-upstream-adapter: $REPO@$REF has no clients/fukuii (not merged there yet?)" >&2
  exit 2
}
COMMIT=$(git -C "$WORK/hive" rev-parse --short FETCH_HEAD)

rc=0
diff -ru "$WORK/hive/clients/fukuii" "$LOCAL" >"$WORK/diff.txt" 2>&1 || rc=$?
if [ "$rc" -eq 0 ]; then
  echo "check-upstream-adapter: hive/fukuii/ is identical to $REPO@$REF ($COMMIT) clients/fukuii/"
  exit 0
elif [ "$rc" -ne 1 ]; then
  echo "check-upstream-adapter: diff could not compare the two directories (exit $rc):" >&2
  cat "$WORK/diff.txt" >&2
  exit 2
fi
echo "check-upstream-adapter: hive/fukuii/ differs from $REPO@$REF ($COMMIT) clients/fukuii/:" >&2
sed -e "s#$WORK/hive/clients/fukuii#upstream:clients/fukuii#g" -e "s#$LOCAL#fukuii:hive/fukuii#g" "$WORK/diff.txt" >&2
echo "Bring the two back in step: upstream the change to $REPO, or take the upstream change here." >&2
exit 1
