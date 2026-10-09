#!/usr/bin/env bash
# Spec 016 move verification (S0f). Usage:
#   scripts/snap-split/verify.sh [--branch NAME] [--compile [--compile-cmd CMD]] [--list-move-commits] <base> [head]
# Checks every non-merge commit of base..head (default head: HEAD), e.g. `verify.sh origin/staging`.
# Needs only git + python3 (no JVM) unless --compile is given. See verify.py for the checks.
set -euo pipefail
exec python3 "$(dirname "${BASH_SOURCE[0]}")/verify.py" "$@"
