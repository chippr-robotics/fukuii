#!/usr/bin/env bash
# Wrapper script for fukuii-cli sync-static-nodes command
# This script maintains backward compatibility with existing workflows

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FUKUII_CLI="$SCRIPT_DIR/../tools/fukuii-cli.sh"

# Check if fukuii-cli exists
if [ ! -f "$FUKUII_CLI" ]; then
    echo "Error: fukuii-cli not found at $FUKUII_CLI"
    exit 1
fi

# Call fukuii-cli sync-static-nodes
exec "$FUKUII_CLI" sync-static-nodes
