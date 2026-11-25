#!/usr/bin/env bash
# Spec 021 FR-004 / DC-3, research R-15 (task T030): the usage-based check that UInt256's arithmetic does not
# use BigInt. The `domain/` half.
#
# It fails on `BigInt` (as a word), `BigInteger` or `toBigInt` in
#   src/main/scala/com/chipprbots/ethereum/domain/UInt256.scala          (required)
#   src/main/scala/com/chipprbots/ethereum/domain/UInt256Kernels.scala   (checked when present; created at T033)
# outside fenced allow-list regions:
#   // DC-3 allow: <member>
#   ...
#   // DC-3 end
# The allow-list (R-15): toBigInt, apply(BigInt), the toUInt256 extension and uint256ToBigInt (conversions that
# stay, DC-2), the string renderers, and equals' cooperative branch. Each fence names its member.
#
# Comments are not usage: whole-line comments (`//`, `/*`, `*`) and a trailing ` // ...` are ignored. A fence that is
# unnamed, nested, unterminated or closed without an opening is itself a failure.
#
# The opcode half (vm/OpCode.scala and vm/Stack.scala inside `// DC-3 arithmetic` ... `// DC-3 end`) is added at
# S2's end, T046.
#
# Usage: check_uint256_bigint.sh [repo-root]    (default: this repository)
#        check_uint256_bigint.sh --self-test    (runs test_check_uint256_bigint.sh)
# Exit: 0 clean; 1 a reference outside a fence or a malformed fence; 2 a required file is missing.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [ "${1:-}" = "--self-test" ]; then
  exec "$here/test_check_uint256_bigint.sh"
fi
root="${1:-$(cd "$here/../.." && pwd)}"
domain="$root/src/main/scala/com/chipprbots/ethereum/domain"

required=("$domain/UInt256.scala")
optional=("$domain/UInt256Kernels.scala")

files=()
for f in "${required[@]}"; do
  if [ ! -f "$f" ]; then
    echo "ERROR: $f is missing" >&2
    exit 2
  fi
  files+=("$f")
done
for f in "${optional[@]}"; do
  if [ -f "$f" ]; then files+=("$f"); else echo "note: $f not present (created at T033), skipped"; fi
done

found=0
for f in "${files[@]}"; do
  if ! awk -v file="$f" '
    function fail(msg) { printf "%s:%d: %s\n", file, NR, msg; bad = 1 }
    {
      line = $0
      # Fences first: they are comments, so they must be read before comments are dropped.
      if (line ~ /^[[:space:]]*\/\/[[:space:]]*DC-3 allow:/) {
        if (open) fail("DC-3 fence opened inside the fence opened at line " openAt)
        member = line; sub(/^[[:space:]]*\/\/[[:space:]]*DC-3 allow:[[:space:]]*/, "", member)
        if (member ~ /^[[:space:]]*$/) fail("DC-3 allow fence without a member name")
        open = 1; openAt = NR; next
      }
      if (line ~ /^[[:space:]]*\/\/[[:space:]]*DC-3 end[[:space:]]*$/) {
        if (!open) fail("DC-3 end without an opening fence")
        open = 0; next
      }
      if (line ~ /^[[:space:]]*(\/\/|\/\*|\*)/) next          # a whole-line comment
      sub(/[[:space:]]\/\/.*$/, "", line)                     # a trailing comment
      if (!open && (line ~ /(^|[^A-Za-z0-9_])BigInt([^A-Za-z0-9_]|$)/ || line ~ /BigInteger/ || line ~ /toBigInt/))
        fail("BigInt outside a DC-3 allow fence: " $0)
    }
    END {
      if (open) { printf "%s:%d: DC-3 fence opened here is never closed\n", file, openAt; bad = 1 }
      exit bad
    }
  ' "$f"; then
    found=1
  fi
done

if [ "$found" -ne 0 ]; then
  echo "ERROR: DC-3: BigInt on UInt256's arithmetic path (spec 021 FR-004). Use the limb kernels, or fence a" >&2
  echo "       conversion that stays with '// DC-3 allow: <member>' ... '// DC-3 end' (research R-15)." >&2
  exit 1
fi
echo "OK: DC-3 domain half: no BigInt outside the allow-list fences."
