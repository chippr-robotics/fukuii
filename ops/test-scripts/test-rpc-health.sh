#!/bin/bash
# Live RPC integration tests for Fukuii — Tier 4 (Health endpoints)
# Tests HTTP health endpoints (not JSON-RPC) against a running Fukuii node.
#
# Usage:
#   ./test-rpc-health.sh                          # localhost:8553
#   ./test-rpc-health.sh --host 10.0.0.5 --port 8553
#
# Prerequisites: jq, curl, running Fukuii node with HTTP RPC enabled

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib/rpc-helpers.sh"

parse_args "$@"

echo -e "${CYAN}Testing health endpoints at ${RPC_URL}...${NC}"
echo ""

echo "── Tier 4: Health Endpoints ──"

# GET /health
response=$(http_get "/health")
if [[ -n "$response" ]]; then
  http_code=$(curl -s -o /dev/null -w "%{http_code}" -m 10 "${RPC_URL}/health" 2>/dev/null)
  if [[ "$http_code" == "200" ]]; then
    pass "GET /health (200 OK)"
    # Check for expected fields
    if echo "$response" | jq -e '.peers' &>/dev/null 2>&1; then
      peers=$(echo "$response" | jq -r '.peers // empty')
      pass "  /health has peers field ($peers)"
    fi
    if echo "$response" | jq -e '.bestBlock' &>/dev/null 2>&1; then
      best=$(echo "$response" | jq -r '.bestBlock // empty')
      pass "  /health has bestBlock field ($best)"
    fi
  else
    fail "GET /health (HTTP $http_code)"
  fi
else
  fail "GET /health (no response)"
fi

# GET /readiness
response=$(http_get "/readiness")
http_code=$(curl -s -o /dev/null -w "%{http_code}" -m 10 "${RPC_URL}/readiness" 2>/dev/null)
if [[ "$http_code" == "200" || "$http_code" == "503" ]]; then
  pass "GET /readiness (HTTP $http_code)"
else
  if [[ "$http_code" == "000" ]]; then
    fail "GET /readiness (no response)"
  else
    fail "GET /readiness (HTTP $http_code)"
  fi
fi

# GET /healthcheck
response=$(http_get "/healthcheck")
if [[ -n "$response" ]]; then
  http_code=$(curl -s -o /dev/null -w "%{http_code}" -m 10 "${RPC_URL}/healthcheck" 2>/dev/null)
  if [[ "$http_code" == "200" ]]; then
    pass "GET /healthcheck (200 OK)"
    # Should return array of check results
    if echo "$response" | jq -e '.[0]' &>/dev/null 2>&1; then
      check_count=$(echo "$response" | jq 'length')
      pass "  /healthcheck returns array ($check_count checks)"
    elif echo "$response" | jq -e '.checks' &>/dev/null 2>&1; then
      check_count=$(echo "$response" | jq '.checks | length')
      pass "  /healthcheck has checks ($check_count)"
    fi
  else
    fail "GET /healthcheck (HTTP $http_code)"
  fi
else
  fail "GET /healthcheck (no response)"
fi

# GET /buildinfo
response=$(http_get "/buildinfo")
if [[ -n "$response" ]]; then
  http_code=$(curl -s -o /dev/null -w "%{http_code}" -m 10 "${RPC_URL}/buildinfo" 2>/dev/null)
  if [[ "$http_code" == "200" ]]; then
    pass "GET /buildinfo (200 OK)"
    # Check for version/commit fields
    if echo "$response" | jq -e '.version' &>/dev/null 2>&1; then
      version=$(echo "$response" | jq -r '.version')
      pass "  /buildinfo version ($version)"
    fi
    if echo "$response" | jq -e '.commit' &>/dev/null 2>&1; then
      commit=$(echo "$response" | jq -r '.commit')
      pass "  /buildinfo commit ($commit)"
    fi
  else
    fail "GET /buildinfo (HTTP $http_code)"
  fi
else
  fail "GET /buildinfo (no response)"
fi

echo ""

print_summary
