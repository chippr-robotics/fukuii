#!/bin/bash
# Shared helpers for Gorgoroth test scripts
# Source this file at the top of test scripts:
#   SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
#   source "$SCRIPT_DIR/lib/test-helpers.sh"

# Prereq validation
require_tools() {
  for cmd in "$@"; do
    if ! command -v "$cmd" &>/dev/null; then
      echo "ERROR: $cmd is required but not installed" >&2
      exit 1
    fi
  done
}

# Docker compose detection — sets DOCKER_COMPOSE variable
detect_docker_compose() {
  if docker compose version &>/dev/null; then
    DOCKER_COMPOSE="docker compose"
  elif command -v docker-compose &>/dev/null; then
    DOCKER_COMPOSE="docker-compose"
  else
    echo "ERROR: Neither 'docker compose' (plugin) nor 'docker-compose' (standalone) found" >&2
    exit 1
  fi
}

# JSON-RPC helper
rpc_call() {
  local url="$1" method="$2" params="${3:-[]}"
  curl -s -m 10 -X POST -H "Content-Type: application/json" \
    --data "{\"jsonrpc\":\"2.0\",\"method\":\"$method\",\"params\":$params,\"id\":1}" \
    "$url" 2>/dev/null
}

# Hex to decimal (returns 0 on invalid input)
hex_to_dec() {
  local hex="${1#0x}"
  printf "%d" "0x$hex" 2>/dev/null || echo "0"
}

# Color output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m'

# Log helpers
log_info() {
  echo -e "${GREEN}i${NC} $1"
}

log_warn() {
  echo -e "${YELLOW}!${NC} $1"
}

log_error() {
  echo -e "${RED}x${NC} $1"
}

log_success() {
  echo -e "${GREEN}+${NC} $1"
}
