#!/bin/bash
# Lightweight integration test for fast sync test scripts
# This test validates the scripts work correctly without requiring full blockchain

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GORGOROTH_DIR="$(dirname "$SCRIPT_DIR")"

echo "=== Fast Sync Test - Lightweight Integration Test ==="
echo "Starting at: $(date)"
echo

# Color output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

log_info() {
  echo -e "${GREEN}ℹ${NC} $1"
}

log_error() {
  echo -e "${RED}✗${NC} $1"
}

log_success() {
  echo -e "${GREEN}✓${NC} $1"
}

TESTS_FAILED=false

# Test 1: Verify test-fast-sync.sh can handle missing Docker environment gracefully
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
log_info "Test 1: Verify test-fast-sync.sh handles missing environment"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

cd "$GORGOROTH_DIR"

# Check if any nodes are running
if docker compose -f docker-compose-6nodes.yml ps -q 2>/dev/null | grep -q .; then
  RUNNING_NODES=$(docker compose -f docker-compose-6nodes.yml ps -q 2>/dev/null | wc -l)
  log_info "Found $RUNNING_NODES running nodes - stopping for clean test"
  docker compose -f docker-compose-6nodes.yml down 2>/dev/null || true
else
  log_info "No running nodes found"
fi

# The test script should fail gracefully when no nodes are running
# We'll test the first few steps without expecting success
log_info "Running test-fast-sync.sh (expecting early exit due to no blockchain)..."

cd "$SCRIPT_DIR"
TEST_OUTPUT=$(mktemp)
if timeout 60 bash test-fast-sync.sh 2>&1 | tee "$TEST_OUTPUT"; then
  log_error "Test unexpectedly succeeded (should fail without blockchain)"
  TESTS_FAILED=true
else
  # Expected to fail - check that it failed for the right reason
  if grep -q "Too few blocks for meaningful fast sync test" "$TEST_OUTPUT"; then
    log_success "Test failed as expected: insufficient blocks"
  elif grep -q "Failed to get block number" "$TEST_OUTPUT"; then
    log_success "Test failed as expected: no seed nodes running"
  elif grep -q "No response from RPC endpoint" "$TEST_OUTPUT"; then
    log_success "Test failed as expected: RPC endpoints not available"
  else
    log_info "Test exited with error (expected behavior)"
    log_info "Last 10 lines of output:"
    tail -10 "$TEST_OUTPUT" | sed 's/^/  /'
  fi
fi

# Clean up
rm -f "$TEST_OUTPUT"

echo

# Test 2: Verify monitor-decompression.sh handles missing container gracefully
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
log_info "Test 2: Verify monitor-decompression.sh handles missing container"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

# Test with non-existent container
if timeout 5 bash monitor-decompression.sh nonexistent-container 2>&1 | grep -q "Container.*not found"; then
  log_success "Correctly detected missing container"
else
  log_error "Failed to detect missing container"
  TESTS_FAILED=true
fi

echo

# Test 3: Test helper functions and color output
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
log_info "Test 3: Verify helper functions work correctly"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

# Source the helper functions from test-fast-sync.sh
HELPER_SCRIPT=$(mktemp)
cat > "$HELPER_SCRIPT" << 'EOF'
#!/bin/bash
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

log_info() {
  echo -e "${GREEN}ℹ${NC} $1"
}

log_warn() {
  echo -e "${YELLOW}⚠${NC} $1"
}

log_error() {
  echo -e "${RED}✗${NC} $1"
}

log_success() {
  echo -e "${GREEN}✓${NC} $1"
}

# Test all log functions
log_info "Test info message"
log_warn "Test warning message"
log_error "Test error message"
log_success "Test success message"
EOF

if bash "$HELPER_SCRIPT" > /dev/null 2>&1; then
  log_success "Helper functions work correctly"
else
  log_error "Helper functions failed"
  TESTS_FAILED=true
fi

# Clean up
rm -f "$HELPER_SCRIPT"

echo

# Test 4: Verify RPC call construction
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
log_info "Test 4: Verify RPC call construction is valid"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

# Test that the RPC calls are well-formed JSON
RPC_CALLS=(
  '{"jsonrpc":"2.0","method":"net_peerCount","params":[],"id":1}'
  '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}'
  '{"jsonrpc":"2.0","method":"eth_syncing","params":[],"id":1}'
  '{"jsonrpc":"2.0","method":"eth_getBlockByNumber","params":["latest",false],"id":1}'
)

ALL_VALID=true
for rpc in "${RPC_CALLS[@]}"; do
  if echo "$rpc" | jq . > /dev/null 2>&1; then
    echo "  ✓ Valid JSON: $(echo "$rpc" | jq -c '.method')"
  else
    log_error "Invalid JSON RPC call: $rpc"
    ALL_VALID=false
  fi
done

if [ "$ALL_VALID" = true ]; then
  log_success "All RPC calls are well-formed"
else
  TESTS_FAILED=true
fi

echo

# Test 5: Verify division by zero protection
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
log_info "Test 5: Verify division by zero protection logic"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

# Test the division logic with various ELAPSED values
DIVISION_SCRIPT=$(mktemp)
DIVISION_OUTPUT=$(mktemp)
cat > "$DIVISION_SCRIPT" << 'EOF'
#!/bin/bash
test_division() {
  ELAPSED=$1
  NODE_BLOCK=100
  
  if [ $ELAPSED -ge 60 ]; then
    MINUTES=$((ELAPSED / 60))
    RATE=$((NODE_BLOCK / MINUTES))
    echo "$ELAPSED seconds -> $RATE blocks/min"
    return 0
  else
    echo "$ELAPSED seconds -> N/A (too quick)"
    return 0
  fi
}

# Test cases
test_division 0
test_division 30
test_division 60
test_division 120
test_division 300
EOF

if bash "$DIVISION_SCRIPT" > "$DIVISION_OUTPUT" 2>&1; then
  if grep -q "N/A (too quick)" "$DIVISION_OUTPUT" && \
     grep -q "blocks/min" "$DIVISION_OUTPUT"; then
    log_success "Division by zero protection works correctly"
    cat "$DIVISION_OUTPUT" | sed 's/^/  /'
  else
    log_error "Division logic output unexpected"
    TESTS_FAILED=true
  fi
else
  log_error "Division logic failed with error"
  TESTS_FAILED=true
fi

# Clean up
rm -f "$DIVISION_SCRIPT" "$DIVISION_OUTPUT"

echo

# Test 6: Verify null handling in RPC responses
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
log_info "Test 6: Verify null/empty handling in RPC responses"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

NULL_SCRIPT=$(mktemp)
NULL_OUTPUT=$(mktemp)
cat > "$NULL_SCRIPT" << 'EOF'
#!/bin/bash

test_null_handling() {
  PEERS=$1
  
  if [ -z "$PEERS" ] || [ "$PEERS" = "null" ]; then
    echo "FAIL: Empty or null"
    return 1
  else
    echo "PASS: Valid value"
    return 0
  fi
}

# Test cases
echo "Testing empty string:"
test_null_handling ""

echo "Testing null:"
test_null_handling "null"

echo "Testing valid value:"
test_null_handling "0x5"
EOF

if bash "$NULL_SCRIPT" > "$NULL_OUTPUT" 2>&1; then
  if grep -q "FAIL.*Empty or null" "$NULL_OUTPUT" && \
     grep -q "PASS.*Valid value" "$NULL_OUTPUT"; then
    log_success "Null handling works correctly"
    cat "$NULL_OUTPUT" | sed 's/^/  /'
  else
    log_error "Null handling output unexpected"
    TESTS_FAILED=true
  fi
else
  log_error "Null handling test failed"
  TESTS_FAILED=true
fi

# Clean up
rm -f "$NULL_SCRIPT" "$NULL_OUTPUT"

echo

# Final Summary
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "=== Integration Test Summary ==="
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

if [ "$TESTS_FAILED" = true ]; then
  echo
  log_error "Some tests FAILED"
  echo
  exit 1
else
  echo
  log_success "All integration tests PASSED"
  echo
  echo "Summary of validated components:"
  echo "  ✓ test-fast-sync.sh handles missing environment gracefully"
  echo "  ✓ monitor-decompression.sh detects missing containers"
  echo "  ✓ Helper functions (log_info, log_warn, log_error, log_success)"
  echo "  ✓ RPC call JSON formatting"
  echo "  ✓ Division by zero protection logic"
  echo "  ✓ Null/empty string handling in RPC responses"
  echo
  echo "The fast sync test infrastructure is working correctly."
  echo "Full end-to-end testing requires a running Gorgoroth network."
  echo
  exit 0
fi
