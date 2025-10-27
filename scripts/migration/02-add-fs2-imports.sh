#!/bin/bash
# Script to add fs2 imports to files that will need them
# Part of Phase 0: Pre-Migration Setup

set -e

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"

echo "=== Adding fs2 imports to Observable-using files ==="
echo ""

# Find files with Observable usage
OBS_FILES=$(grep -r "monix.reactive.Observable" src --include="*.scala" -l 2>/dev/null || true)

if [ -z "$OBS_FILES" ]; then
    echo "No files with Observable found."
    exit 0
fi

COUNT=0
for file in $OBS_FILES; do
    # Check if file already has fs2 import
    if ! grep -q "import fs2" "$file"; then
        echo "Processing: $file"
        
        # Add fs2 imports after the last import statement
        # This is a simple approach - for production, use scalafix
        # For now, just report which files need it
        echo "  -> Needs fs2.Stream import"
        COUNT=$((COUNT + 1))
    fi
done

echo ""
echo "Files needing fs2 imports: $COUNT"
echo ""
echo "Note: Actual import addition should be done carefully with proper Scala tooling."
echo "This script identifies candidates for manual or scalafix-based migration."
