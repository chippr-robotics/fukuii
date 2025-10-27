#!/bin/bash
# Script to assist with Observable → fs2.Stream migration
# Part of Phase 2: Database Layer

set -e

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"

echo "=== Observable → fs2.Stream Migration Helper ==="
echo ""

FILE="${1:-}"

if [ -z "$FILE" ]; then
    echo "Usage: $0 <file>"
    echo ""
    echo "This script analyzes a file and suggests fs2.Stream replacements."
    echo ""
    echo "Files with Observable usage:"
    grep -r "monix.reactive.Observable" src --include="*.scala" -l 2>/dev/null | head -20
    exit 1
fi

if [ ! -f "$FILE" ]; then
    echo "Error: File '$FILE' not found"
    exit 1
fi

echo "Analyzing: $FILE"
echo ""

if ! grep -q "Observable" "$FILE"; then
    echo "No Observable usage found in this file."
    exit 0
fi

echo "Observable patterns found:"
echo "--------------------------"

# Detect various Observable patterns and suggest fs2 equivalents
echo ""
grep -n "Observable\.from" "$FILE" 2>/dev/null | while IFS=: read -r line_num content; do
    echo "Line $line_num: Observable.from"
    echo "  Suggestion: Stream.emits"
    echo ""
done

grep -n "Observable\.pure" "$FILE" 2>/dev/null | while IFS=: read -r line_num content; do
    echo "Line $line_num: Observable.pure"
    echo "  Suggestion: Stream.emit"
    echo ""
done

grep -n "\.mapParallelOrdered" "$FILE" 2>/dev/null | while IFS=: read -r line_num content; do
    echo "Line $line_num: .mapParallelOrdered"
    echo "  Suggestion: .parEvalMap"
    echo ""
done

grep -n "\.toListL" "$FILE" 2>/dev/null | while IFS=: read -r line_num content; do
    echo "Line $line_num: .toListL"
    echo "  Suggestion: .compile.toList"
    echo ""
done

grep -n "\.consumeWith" "$FILE" 2>/dev/null | while IFS=: read -r line_num content; do
    echo "Line $line_num: .consumeWith"
    echo "  Suggestion: .compile.drain or .through(pipe)"
    echo ""
done

echo ""
echo "Manual Review Required:"
echo "----------------------"
echo "Observable → Stream migration requires careful attention to:"
echo "  - Pull-based vs push-based semantics"
echo "  - Backpressure handling"
echo "  - Concurrency control"
echo "  - Resource management"
echo ""
echo "See docs/MONIX_TO_IO_MIGRATION_PLAN.md for detailed patterns."
