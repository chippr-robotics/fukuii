#!/bin/bash
# Script to perform automated Task → IO replacements
# Part of Phase 1: Foundation Modules

set -e

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"

echo "=== Task → IO Migration (Automated Replacements) ==="
echo ""

# This script performs simple, safe replacements
# Complex cases require manual review

MODULE="${1:-}"

if [ -z "$MODULE" ]; then
    echo "Usage: $0 <module>"
    echo "Example: $0 rlp"
    echo ""
    echo "Available modules: bytes, rlp, crypto, src"
    exit 1
fi

if [ ! -d "$MODULE" ]; then
    echo "Error: Module directory '$MODULE' not found"
    exit 1
fi

echo "Processing module: $MODULE"
echo ""

# Count files before
BEFORE_COUNT=$(grep -r "import monix.eval.Task" "$MODULE" --include="*.scala" -l 2>/dev/null | wc -l)
echo "Files with Task imports: $BEFORE_COUNT"

if [ "$BEFORE_COUNT" -eq 0 ]; then
    echo "No Task imports found in $MODULE"
    exit 0
fi

echo ""
echo "Performing automated replacements..."
echo ""

# Create backup
BACKUP_DIR="/tmp/monix-migration-backup-$(date +%s)"
mkdir -p "$BACKUP_DIR"
cp -r "$MODULE" "$BACKUP_DIR/"
echo "Backup created at: $BACKUP_DIR"
echo ""

# Perform replacements (safe patterns only)
# Note: This is a basic implementation. Production use should employ scalafix.

find "$MODULE" -name "*.scala" -type f | while read -r file; do
    if grep -q "monix.eval.Task" "$file"; then
        echo "Processing: $file"
        
        # Replace import statements
        sed -i 's/import monix\.eval\.Task/import cats.effect.IO/g' "$file"
        
        # Simple method signature replacements
        sed -i 's/: Task\[/: IO[/g' "$file"
        sed -i 's/Task\[/IO[/g' "$file"
        sed -i 's/Task\./IO./g' "$file"
        
        # Replace common patterns
        sed -i 's/Task\.pure/IO.pure/g' "$file"
        sed -i 's/Task\.eval/IO.apply/g' "$file"
        sed -i 's/Task\.defer/IO.defer/g' "$file"
        sed -i 's/Task\.raiseError/IO.raiseError/g' "$file"
        
        echo "  -> Updated"
    fi
done

echo ""
echo "Automated replacements complete."
echo "Backup location: $BACKUP_DIR"
echo ""
echo "IMPORTANT: Review changes carefully and run tests!"
echo "  git diff $MODULE"
echo "  sbt ${MODULE}/test"
