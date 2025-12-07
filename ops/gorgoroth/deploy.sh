#!/usr/bin/env bash
# Gorgoroth Internal Test Network Deployment Script
# This script is a wrapper around the unified fukuii-cli tool
# It maintains backward compatibility with existing workflows

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FUKUII_CLI="$SCRIPT_DIR/../tools/fukuii-cli.sh"

# Check if fukuii-cli exists
if [ ! -f "$FUKUII_CLI" ]; then
    echo "Error: fukuii-cli not found at $FUKUII_CLI"
    exit 1
fi

# Forward all commands to fukuii-cli
exec "$FUKUII_CLI" "$@"
