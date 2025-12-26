#!/usr/bin/env bash
# Fukuii - Main entry point for Fukuii node management
# Provides unified command-line interface for all Fukuii deployments

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CLI_SCRIPT="$SCRIPT_DIR/ops/tools/fukuii-cli.sh"

# Color output
RED='\033[0;31m'
GREEN='\033[0;32m'
NC='\033[0m' # No Color

# Check if fukuii-cli exists
if [ ! -f "$CLI_SCRIPT" ]; then
    echo -e "${RED}Error: fukuii-cli.sh not found at $CLI_SCRIPT${NC}"
    exit 1
fi

inject_peers() {
    "$CLI_SCRIPT" inject-peers "$@"
}

if [[ "${1:-}" == "inject-peers" ]]; then
    shift
    inject_peers "$@"
    exit $?
fi

# Forward all other arguments to fukuii-cli
exec "$CLI_SCRIPT" "$@"
