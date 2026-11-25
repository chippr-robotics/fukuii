#!/usr/bin/env bash
# Self-test for check_no_closures.sh (spec 021 T031, research R-1 and R-16): compiles small Java fixtures named like the
# UInt256 classes with javac, so it needs a JDK but no Scala build, and is independent of the real classes. Each planted
# closure or lazy-val marker must fail (exit 1); a clean class, including string concatenation (an invokedynamic that is
# not a lambda), must pass (exit 0); a directory with no matching class must exit 2.
set -uo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
check="$here/check_no_closures.sh"
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
fail=0
if ! command -v javac >/dev/null 2>&1; then echo "FAIL: javac not found"; exit 1; fi

pkg='package com.chipprbots.ethereum.domain;'

# case <name> <expected exit> <file:java-source>...
case_() {
  local name="$1" expected="$2"; shift 2
  local src="$tmp/$name/src" out="$tmp/$name/classes"
  mkdir -p "$src" "$out"
  local spec
  for spec in "$@"; do
    printf '%s\n%s\n' "$pkg" "${spec#*:}" > "$src/${spec%%:*}.java"
  done
  if ! javac -d "$out" "$src"/*.java >/dev/null 2>&1; then echo "FAIL: $name does not compile"; fail=1; return; fi
  "$check" "$out" >/dev/null 2>&1; local rc=$?
  if [ "$rc" -ne "$expected" ]; then echo "FAIL: $name exit $rc, expected $expected"; fail=1
  else echo "ok:   $name exit $rc"; fi
}

clean='UInt256:public final class UInt256 {
  final long w0;
  UInt256(long w0) { this.w0 = w0; }
  UInt256 plus(UInt256 that) { return new UInt256(w0 + that.w0); }
  public String toString() { return "UInt256(" + w0 + ")"; }
}'
cleanObject='UInt256$:public final class UInt256$ { public static final UInt256$ MODULE$ = new UInt256$(); }'

case_ clean 0 "$clean" "$cleanObject"
case_ clean-kernels 0 "$clean" 'UInt256Kernels:final class UInt256Kernels { static long add(long a, long b) { return a + b; } }'

# Planted closures: each must fail.
case_ planted-lambda 1 "$clean" 'UInt256$:public final class UInt256$ {
  Runnable r() { return () -> {}; }
}'
case_ planted-method-reference 1 "$clean" 'UInt256Kernels:final class UInt256Kernels {
  java.util.function.LongBinaryOperator add() { return Long::sum; }
}'
case_ planted-capturing-lambda 1 'UInt256:public final class UInt256 {
  final long w0;
  UInt256(long w0) { this.w0 = w0; }
  java.util.function.Supplier<String> message() { return () -> "bad " + w0; }
}'
case_ planted-lambda-in-nested-kernels 1 "$clean" 'UInt256Kernels$Inner:final class UInt256Kernels$Inner {
  Runnable r() { return () -> {}; }
}'

# Planted lazy-val markers (the names Scala 3 and Scala 2 generate): each must fail.
case_ planted-lzy-field 1 'UInt256:public final class UInt256 { private volatile Object bytes$lzy1; }'
case_ planted-lzyINIT-method 1 'UInt256:public final class UInt256 { private Object signedN$lzyINIT1() { return null; } }'
case_ planted-lzycompute 1 'UInt256:public final class UInt256 { private Object bytes$lzycompute() { return null; } }'

# No matching class is an error of its own.
mkdir -p "$tmp/none"
"$check" "$tmp/none" >/dev/null 2>&1; rc=$?
if [ "$rc" -ne 2 ]; then echo "FAIL: no-classes exit $rc, expected 2"; fail=1; else echo "ok:   no-classes exit 2"; fi

exit $fail
