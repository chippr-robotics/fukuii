#!/usr/bin/env bash
# Spec 021 DC-5, research R-1 and R-16 (task T031): the bytecode check that the UInt256 classes hold no closure and no
# lazy val. It reads the compiled classes with `javap -v -p` and fails on
#   - an invokedynamic whose bootstrap method is java/lang/invoke/LambdaMetafactory (a lambda, a by-name argument, an
#     eta-expansion): R-1's inline continuations must leave none at run time;
#   - a lazy-val initialiser: Scala 3's `<name>$lzy<N>` fields and `$lzyINIT` methods, scala/runtime/LazyVals, or a
#     Scala 2 style `$lzycompute` (R-16: `bytes` and `signedN` go).
# String concatenation (StringConcatFactory) and other bootstraps are not closures and pass.
#
# Classes checked, relative to the classes directory (CLASS_GLOBS; T068 adds vm/Stack*.class and the arithmetic
# opcode classes ADD$ ... CLZ$ at S3):
#   com/chipprbots/ethereum/domain/UInt256.class, UInt256$.class, UInt256Kernels*.class
#
# The count it prints per class is research.md §B row 3 ("javap LambdaMetafactory count").
#
# Usage: check_no_closures.sh [classes-dir]   (default: target/scala-*/classes; compile first)
#        check_no_closures.sh --self-test     (runs test_check_no_closures.sh)
# Exit: 0 clean; 1 a closure or a lazy val; 2 no classes directory, no class matched, or javap missing.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [ "${1:-}" = "--self-test" ]; then
  exec "$here/test_check_no_closures.sh"
fi

if [ "$#" -gt 0 ]; then
  classes="$1"
else
  root="$(cd "$here/../.." && pwd)"
  shopt -s nullglob
  dirs=("$root"/target/scala-*/classes)
  shopt -u nullglob
  if [ "${#dirs[@]}" -ne 1 ]; then
    echo "ERROR: expected one target/scala-*/classes directory under $root, found ${#dirs[@]}; compile first or pass one" >&2
    exit 2
  fi
  classes="${dirs[0]}"
fi
if [ ! -d "$classes" ]; then
  echo "ERROR: $classes is not a directory" >&2
  exit 2
fi
if ! command -v javap >/dev/null 2>&1; then
  echo "ERROR: javap not found (a JDK is required)" >&2
  exit 2
fi

CLASS_GLOBS=(
  'com/chipprbots/ethereum/domain/UInt256.class'
  'com/chipprbots/ethereum/domain/UInt256$.class'
  'com/chipprbots/ethereum/domain/UInt256Kernels*.class'
)

shopt -s nullglob
targets=()
for g in "${CLASS_GLOBS[@]}"; do
  # shellcheck disable=SC2206 # the glob is meant to expand here
  matches=("$classes"/$g)
  # A glob with no wildcard (UInt256.class) is kept by nullglob even when absent, hence the -f test.
  for m in ${matches[@]+"${matches[@]}"}; do # an empty array is "unbound" under set -u in bash < 4.4
    if [ -f "$m" ]; then targets+=("$m"); fi
  done
done
shopt -u nullglob
if [ "${#targets[@]}" -eq 0 ]; then
  echo "ERROR: no class under $classes matches ${CLASS_GLOBS[*]}" >&2
  exit 2
fi

found=0
for c in "${targets[@]}"; do
  out="$(javap -v -p "$c" 2>/dev/null)"
  # Bootstrap entries ("  0: #117 REF_invokeStatic java/lang/invoke/LambdaMetafactory.altMetafactory:...") give the
  # lambda indices; each "invokedynamic ... // InvokeDynamic #<index>:" using one of them is one closure site.
  lambdas="$(printf '%s\n' "$out" | awk '
    /^BootstrapMethods:/ { inBsm = 1; next }
    inBsm && /^[[:space:]]+[0-9]+: #[0-9]+ REF_invokeStatic java\/lang\/invoke\/LambdaMetafactory\./ {
      idx = $1; sub(/:$/, "", idx); lmf[idx] = 1
    }
    { lines[NR] = $0 }
    END {
      n = 0
      for (i = 1; i <= NR; i++) {
        if (lines[i] ~ /invokedynamic/ && match(lines[i], /InvokeDynamic #[0-9]+:/)) {
          ref = substr(lines[i], RSTART + 15, RLENGTH - 16)
          if (ref in lmf) n++
        }
      }
      print n
    }')"
  lazies="$(printf '%s\n' "$out" | grep -oE '[A-Za-z0-9_]+\$lzy[0-9]+|[A-Za-z0-9_]+\$lzyINIT[0-9]+|[A-Za-z0-9_]+\$lzycompute|scala/runtime/LazyVals' | sort -u | tr '\n' ' ' || true)"
  name="${c#"$classes"/}"
  if [ "$lambdas" -ne 0 ] || [ -n "$lazies" ]; then
    echo "$name: $lambdas LambdaMetafactory invokedynamic site(s); lazy-val markers: ${lazies:-none}"
    found=1
  else
    echo "$name: 0 LambdaMetafactory invokedynamic sites; no lazy val"
  fi
done

if [ "$found" -ne 0 ]; then
  echo "ERROR: DC-5: a closure or a lazy val in the UInt256 classes (spec 021 R-1, R-16). Use inline kernels with" >&2
  echo "       inline continuations, no by-name arguments (require's message is one), and no lazy val." >&2
  exit 1
fi
echo "OK: DC-5: no closure and no lazy val in ${#targets[@]} class(es)."
