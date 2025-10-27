#!/bin/bash
# Script to analyze Monix usage in the codebase
# Part of Phase 0: Pre-Migration Setup

set -e

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"

echo "=== Monix Usage Analysis ==="
echo ""

echo "1. Files with Monix imports:"
echo "----------------------------"
MONIX_FILES=$(grep -r "import monix" src --include="*.scala" 2>/dev/null | cut -d: -f1 | sort -u)
MONIX_COUNT=$(echo "$MONIX_FILES" | wc -l)
echo "Total files: $MONIX_COUNT"
echo ""

echo "2. Task usage breakdown:"
echo "------------------------"
TASK_COUNT=$(grep -r "monix.eval.Task" src --include="*.scala" 2>/dev/null | wc -l)
echo "Task occurrences: $TASK_COUNT"
echo ""

echo "3. Observable usage breakdown:"
echo "------------------------------"
OBS_COUNT=$(grep -r "monix.reactive.Observable" src --include="*.scala" 2>/dev/null | wc -l)
echo "Observable occurrences: $OBS_COUNT"
echo ""

echo "4. Scheduler usage breakdown:"
echo "-----------------------------"
SCHED_COUNT=$(grep -r "monix.execution.Scheduler" src --include="*.scala" 2>/dev/null | wc -l)
echo "Scheduler occurrences: $SCHED_COUNT"
echo ""

echo "5. Files by module:"
echo "-------------------"
for module in bytes rlp crypto src; do
    if [ -d "$module" ]; then
        MODULE_COUNT=$(grep -r "import monix" "$module" --include="*.scala" 2>/dev/null | cut -d: -f1 | sort -u | wc -l)
        echo "$module: $MODULE_COUNT files"
    fi
done
echo ""

echo "6. Top 10 files with most Monix usage:"
echo "---------------------------------------"
grep -r "import monix" src --include="*.scala" 2>/dev/null | cut -d: -f1 | sort | uniq -c | sort -rn | head -10
echo ""

echo "Analysis complete. See output above for migration planning."
