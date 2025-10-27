#!/bin/bash
# Script to replace Scheduler with IORuntime
# Part of Phase 1-5: Various modules

set -e

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"

echo "=== Scheduler → IORuntime Migration ==="
echo ""

MODULE="${1:-}"

if [ -z "$MODULE" ]; then
    echo "Usage: $0 <module|file>"
    echo ""
    echo "Modules with Scheduler usage:"
    for mod in bytes rlp crypto src; do
        if [ -d "$mod" ]; then
            count=$(grep -r "Scheduler" "$mod" --include="*.scala" 2>/dev/null | wc -l)
            if [ "$count" -gt 0 ]; then
                echo "  $mod: $count occurrences"
            fi
        fi
    done
    exit 1
fi

if [ ! -e "$MODULE" ]; then
    echo "Error: '$MODULE' not found"
    exit 1
fi

echo "Processing: $MODULE"
echo ""

# Create backup
BACKUP_DIR="/tmp/scheduler-migration-backup-$(date +%s)"
mkdir -p "$BACKUP_DIR"
if [ -d "$MODULE" ]; then
    cp -r "$MODULE" "$BACKUP_DIR/"
else
    cp "$MODULE" "$BACKUP_DIR/"
fi
echo "Backup created at: $BACKUP_DIR"
echo ""

# Perform replacements
if [ -d "$MODULE" ]; then
    FILES=$(find "$MODULE" -name "*.scala" -type f)
else
    FILES="$MODULE"
fi

for file in $FILES; do
    if grep -q "monix.execution.Scheduler" "$file"; then
        echo "Processing: $file"
        
        # Replace imports
        sed -i 's/import monix\.execution\.Scheduler/import cats.effect.unsafe.IORuntime/g' "$file"
        
        # Replace implicit parameters
        sed -i 's/implicit val scheduler: Scheduler/implicit val runtime: IORuntime/g' "$file"
        sed -i 's/implicit scheduler: Scheduler/implicit runtime: IORuntime/g' "$file"
        sed -i 's/(implicit s: Scheduler)/(implicit runtime: IORuntime)/g' "$file"
        
        # Replace usage
        sed -i 's/Scheduler\.global/IORuntime.global/g' "$file"
        sed -i 's/scheduler/runtime/g' "$file"
        
        echo "  -> Updated"
    fi
done

echo ""
echo "Scheduler replacements complete."
echo "Backup location: $BACKUP_DIR"
echo ""
echo "IMPORTANT: Review changes and update method implementations!"
echo "  - .runToFuture → .unsafeToFuture()"
echo "  - .runAsync → .unsafeRunAsync"
echo "  - Execution context handling may need adjustment"
