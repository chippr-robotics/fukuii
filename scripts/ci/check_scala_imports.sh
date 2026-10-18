#!/usr/bin/env bash
# Spec 020 DC-6 / R-2: reject Scala 2 import syntax (`import x._`, `{a, _}`, `{a => b}`,
# mixed `x._, y.*`) in every *.scala file. Scala 3 forms (`.*`, `{given, *}`, `{a as b}`) pass.
# Keep PATTERN in sync with DisableSyntax.regex in .scalafix.conf.
# project/** and *.sbt are Scala 2.12 build definitions and are excluded.
# Usage: check_scala_imports.sh [root-dir ...]   (default: the repository root)
set -euo pipefail

PATTERN='^[[:space:]]*import[[:space:]].*(\._[[:space:]]*($|,|;|//)|[{,][[:space:]]*_[[:space:]]*}|=>)'

if [ "$#" -gt 0 ]; then
  roots=("$@")
else
  roots=("$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)")
fi

found=0
for root in "${roots[@]}"; do
  while IFS= read -r -d '' f; do
    if grep -nE "$PATTERN" "$f" | sed "s|^|$f:|"; then
      found=1
    fi
  # project/ and .local/ are pruned at the root only, so a package directory with either name is still scanned.
  done < <(find "$root" \( -path "$root/project" -o -path "$root/.local" -o -name .git -o -name target -o -name node_modules -o -name .bsp \) -prune \
             -o -type f -name '*.scala' -print0)
done

if [ "$found" -ne 0 ]; then
  echo "ERROR: Scala 2 import syntax found. Use 'import x.*', 'import x.{given, *}', 'import x.{a as b}'." >&2
  exit 1
fi
echo "OK: no Scala 2 import syntax."
