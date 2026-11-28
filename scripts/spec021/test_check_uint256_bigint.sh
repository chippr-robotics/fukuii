#!/usr/bin/env bash
# Self-test for check_uint256_bigint.sh (spec 021 T030, research R-15): every planted reference must fail (exit 1),
# every clean or fenced form must pass (exit 0), and a missing UInt256.scala must exit 2. It builds its fixtures in a
# temporary repository root, so it is independent of the real UInt256.scala and stays valid before and after the swap.
set -uo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
check="$here/check_uint256_bigint.sh"
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
fail=0

# case <name> <expected exit> <UInt256.scala body> [UInt256Kernels.scala body]
case_() {
  local name="$1" expected="$2" body="$3" kernels="${4:-}"
  local root="$tmp/$name" dir
  dir="$root/src/main/scala/com/chipprbots/ethereum/domain"
  mkdir -p "$dir"
  printf '%s\n' "$body" > "$dir/UInt256.scala"
  if [ -n "$kernels" ]; then printf '%s\n' "$kernels" > "$dir/UInt256Kernels.scala"; fi
  "$check" "$root" >/dev/null 2>&1; local rc=$?
  if [ "$rc" -ne "$expected" ]; then echo "FAIL: $name exit $rc, expected $expected"; fail=1
  else echo "ok:   $name exit $rc"; fi
}

clean='package com.chipprbots.ethereum.domain
final class UInt256(val w0: Long, val w1: Long, val w2: Long, val w3: Long):
  def +(that: UInt256): UInt256 = new UInt256(w0 + that.w0, w1, w2, w3)'

fenced="$clean
  // DC-3 allow: toBigInt
  def toBigInt: BigInt = BigInt(new java.math.BigInteger(1, Array.emptyByteArray))
  // DC-3 end"

case_ clean 0 "$clean"
case_ fenced-toBigInt 0 "$fenced"
case_ comment-only 0 "$clean
  // the value equals BigInt.hashCode; toBigInt allocates; BigInteger is not used
  /** BigInt in a scaladoc line */
  def isZero: Boolean = (w0 | w1 | w2 | w3) == 0L // not via BigInt"
case_ identifier-containing-BigInt 0 "$clean
  def fromBigIntish(x: Long): Long = x
  val notBigIntAtAll: Int = 0"

# Planted references: each must fail.
case_ planted-BigInt 1 "$clean
  def *(that: UInt256): UInt256 = UInt256(BigInt(w0) * BigInt(that.w0))"
case_ planted-qualified-BigInt 1 "$clean
  def x: scala.math.BigInt = 0"
case_ planted-BigInteger 1 "$clean
  def y: Int = java.math.BigInteger.ONE.intValue"
case_ planted-toBigInt 1 "$clean
  def z(that: UInt256): Int = that.toBigInt.bitLength"
case_ planted-after-fence 1 "$fenced
  def mod(that: UInt256): UInt256 = UInt256(toBigInt.mod(that.toBigInt))"
case_ planted-in-kernels 1 "$clean" 'package com.chipprbots.ethereum.domain
object UInt256Kernels:
  def mulmod(a: Long): Long = BigInt(a).toLong'
case_ fenced-in-kernels 0 "$clean" 'package com.chipprbots.ethereum.domain
object UInt256Kernels:
  // DC-3 allow: apply(BigInt)
  def fromBig(n: BigInt): Long = n.toLong
  // DC-3 end'

# Malformed fences: each must fail.
case_ fence-without-member 1 "$clean
  // DC-3 allow:
  def toBigInt: BigInt = 0
  // DC-3 end"
case_ fence-never-closed 1 "$clean
  // DC-3 allow: toBigInt
  def toBigInt: BigInt = 0"
case_ fence-nested 1 "$clean
  // DC-3 allow: toBigInt
  // DC-3 allow: equals
  def toBigInt: BigInt = 0
  // DC-3 end"
case_ end-without-open 1 "$clean
  // DC-3 end"

# A missing UInt256.scala is an error of its own.
mkdir -p "$tmp/empty/src/main/scala/com/chipprbots/ethereum/domain"
"$check" "$tmp/empty" >/dev/null 2>&1; rc=$?
if [ "$rc" -ne 2 ]; then echo "FAIL: missing-UInt256 exit $rc, expected 2"; fail=1; else echo "ok:   missing-UInt256 exit 2"; fi

exit $fail
