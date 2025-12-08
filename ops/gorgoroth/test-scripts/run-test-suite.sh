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

# Start the network if not already running
echo "Checking network status..."
if ! docker compose -f "docker-compose-${CONFIG}.yml" ps | grep -q "Up"; then
  echo "Starting network..."
  docker compose -f "docker-compose-${CONFIG}.yml" up -d
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
echo "Total tests: 4"
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
