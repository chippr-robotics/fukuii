#!/bin/bash
# Main test suite runner for Gorgoroth network compatibility testing

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CONFIG="${1:-fukuii-geth}"
RESULTS_DIR="./test-results-$(date +%Y%m%d-%H%M%S)"

mkdir -p "$RESULTS_DIR"

echo "========================================="
echo "Gorgoroth Compatibility Test Suite"
echo "========================================="
echo ""
echo "Configuration: $CONFIG"
echo "Results directory: $RESULTS_DIR"
echo ""

# Check prerequisites
echo "Checking prerequisites..."
for cmd in curl jq docker; do
  if ! command -v $cmd &> /dev/null; then
    echo "❌ Error: $cmd is not installed"
    exit 1
  fi
done
echo "✅ Prerequisites OK"
echo ""

# Detect docker compose command (supports both 'docker compose' and 'docker-compose')
if docker compose version &> /dev/null; then
  DOCKER_COMPOSE="docker compose"
elif docker-compose --version &> /dev/null; then
  DOCKER_COMPOSE="docker-compose"
else
  echo "❌ Error: Neither 'docker compose' nor 'docker-compose' is available"
  exit 1
fi

# Start the network if not already running
echo "Checking network status..."
if ! $DOCKER_COMPOSE -f "docker-compose-${CONFIG}.yml" ps | grep -q "Up"; then
  echo "Starting network..."
  $DOCKER_COMPOSE -f "docker-compose-${CONFIG}.yml" up -d
  echo "Waiting 60 seconds for nodes to initialize..."
  sleep 60
else
  echo "Network already running"
fi
echo ""

# Run tests
FAILED_TESTS=()

run_test() {
  local test_name=$1
  local test_script=$2
  local log_file=$3
  
  echo "========================================="
  echo "Running: $test_name"
  echo "========================================="
  echo ""
  
  if bash "$test_script" 2>&1 | tee "$log_file"; then
    echo ""
    echo "✅ $test_name PASSED"
  else
    echo ""
    echo "❌ $test_name FAILED"
    FAILED_TESTS+=("$test_name")
  fi
  
  echo ""
  echo ""
}

# Test 1: Network Connectivity
run_test \
  "Network Connectivity" \
  "$SCRIPT_DIR/test-connectivity.sh" \
  "$RESULTS_DIR/01-connectivity.log"

# Test 2: Block Propagation
run_test \
  "Block Propagation" \
  "$SCRIPT_DIR/test-block-propagation.sh" \
  "$RESULTS_DIR/02-block-propagation.log"

# Test 3: Mining Compatibility
run_test \
  "Mining Compatibility" \
  "$SCRIPT_DIR/test-mining.sh" \
  "$RESULTS_DIR/03-mining.log"

# Test 4: Consensus Maintenance (run for 5 minutes by default)
run_test \
  "Consensus Maintenance (5 min)" \
  "$SCRIPT_DIR/test-consensus.sh 5" \
  "$RESULTS_DIR/04-consensus.log"

# Test 5: SNAP Sync (node3 syncs via SNAP protocol)
run_test \
  "SNAP Sync" \
  "$SCRIPT_DIR/test-snap-sync.sh" \
  "$RESULTS_DIR/05-snap-sync.log"

# Olympia-specific tests (post-fork)

# Test 6: EIP-1559 BaseFee (Olympia)
run_test \
  "EIP-1559 BaseFee (Olympia)" \
  "$SCRIPT_DIR/test-eip1559-basefee.sh" \
  "$RESULTS_DIR/06-eip1559-basefee.log"

# Test 7: ECIP-1111 Treasury Accumulation (Olympia)
run_test \
  "Treasury Accumulation (Olympia)" \
  "$SCRIPT_DIR/test-treasury-accumulation.sh" \
  "$RESULTS_DIR/07-treasury-accumulation.log"

# Test 8: EIP-7935 Gas Limit Convergence (Olympia)
run_test \
  "Gas Limit Convergence (Olympia)" \
  "$SCRIPT_DIR/test-gas-limit-convergence.sh" \
  "$RESULTS_DIR/08-gas-limit-convergence.log"

# Test 9: Olympia EVM Opcodes
run_test \
  "Olympia EVM Opcodes" \
  "$SCRIPT_DIR/test-olympia-opcodes.sh" \
  "$RESULTS_DIR/09-olympia-opcodes.log"

# Test 10: ECIP-1111 BaseFee Redirect (Olympia)
run_test \
  "ECIP-1111 BaseFee Redirect (Olympia)" \
  "$SCRIPT_DIR/test-ecip1111-basefee-redirect.sh" \
  "$RESULTS_DIR/10-ecip1111-basefee-redirect.log"

# Test 11: ECIP-1112 Treasury Address Verification (Olympia)
run_test \
  "ECIP-1112 Treasury Address (Olympia)" \
  "$SCRIPT_DIR/test-ecip1112-treasury-address.sh" \
  "$RESULTS_DIR/11-ecip1112-treasury-address.log"

# Generate summary report
echo "========================================="
echo "Generating Summary Report"
echo "========================================="
echo ""

bash "$SCRIPT_DIR/generate-report.sh" "$RESULTS_DIR"

# Final summary
echo ""
echo "========================================="
echo "Test Suite Complete"
echo "========================================="
echo ""
echo "Total tests: 11"
echo "Failed tests: ${#FAILED_TESTS[@]}"
echo ""

if [ ${#FAILED_TESTS[@]} -eq 0 ]; then
  echo "✅ All tests passed!"
  echo ""
  echo "Results saved to: $RESULTS_DIR"
  exit 0
else
  echo "❌ The following tests failed:"
  for test in "${FAILED_TESTS[@]}"; do
    echo "  - $test"
  done
  echo ""
  echo "Results saved to: $RESULTS_DIR"
  echo "Please review the log files for details."
  exit 1
fi
