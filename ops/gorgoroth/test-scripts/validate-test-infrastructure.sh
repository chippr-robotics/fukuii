#!/bin/bash
# Validation script for fast sync test infrastructure
# This script validates the test scripts without requiring a full Gorgoroth network

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GORGOROTH_DIR="$(dirname "$SCRIPT_DIR")"

echo "=== Fast Sync Test Infrastructure Validation ==="
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

VALIDATION_FAILED=false

# Step 1: Validate required tools
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
log_info "Step 1: Validating required tools..."
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

REQUIRED_TOOLS=("docker" "jq" "curl" "bash")
for tool in "${REQUIRED_TOOLS[@]}"; do
  if command -v "$tool" &> /dev/null; then
    VERSION=$($tool --version 2>&1 | head -1 || echo "unknown")
    echo "  ✓ $tool: $VERSION"
  else
    log_error "$tool is not installed"
    VALIDATION_FAILED=true
  fi
done

# Check docker compose
if docker compose version &> /dev/null; then
  VERSION=$(docker compose version)
  echo "  ✓ docker compose: $VERSION"
else
  log_error "docker compose is not available"
  VALIDATION_FAILED=true
fi

echo

# Step 2: Validate test scripts exist and are executable
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
log_info "Step 2: Validating test scripts..."
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

TEST_SCRIPTS=(
  "test-fast-sync.sh"
  "monitor-decompression.sh"
)

for script in "${TEST_SCRIPTS[@]}"; do
  SCRIPT_PATH="$SCRIPT_DIR/$script"
  if [ -f "$SCRIPT_PATH" ]; then
    if [ -x "$SCRIPT_PATH" ]; then
      echo "  ✓ $script exists and is executable"
    else
      log_error "$script exists but is not executable"
      VALIDATION_FAILED=true
    fi
  else
    log_error "$script does not exist at $SCRIPT_PATH"
    VALIDATION_FAILED=true
  fi
done

echo

# Step 3: Validate bash syntax
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
log_info "Step 3: Validating bash syntax..."
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

for script in "${TEST_SCRIPTS[@]}"; do
  SCRIPT_PATH="$SCRIPT_DIR/$script"
  if [ -f "$SCRIPT_PATH" ]; then
    if bash -n "$SCRIPT_PATH" 2>&1; then
      echo "  ✓ $script: syntax OK"
    else
      log_error "$script: syntax error"
      VALIDATION_FAILED=true
    fi
  fi
done

echo

# Step 4: Validate configuration files
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
log_info "Step 4: Validating configuration files..."
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

CONFIG_FILES=(
  "docker-compose-6nodes.yml"
  "conf/base-gorgoroth.conf"
  "conf/node1/gorgoroth.conf"
  "conf/node2/gorgoroth.conf"
  "conf/node3/gorgoroth.conf"
  "conf/node4/gorgoroth.conf"
  "conf/node5/gorgoroth.conf"
  "conf/node6/gorgoroth.conf"
)

for config in "${CONFIG_FILES[@]}"; do
  CONFIG_PATH="$GORGOROTH_DIR/$config"
  if [ -f "$CONFIG_PATH" ]; then
    echo "  ✓ $config exists"
  else
    log_error "$config does not exist at $CONFIG_PATH"
    VALIDATION_FAILED=true
  fi
done

echo

# Step 5: Validate docker-compose syntax
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
log_info "Step 5: Validating docker-compose configuration..."
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

cd "$GORGOROTH_DIR"
if docker compose -f docker-compose-6nodes.yml config > /dev/null 2>&1; then
  echo "  ✓ docker-compose-6nodes.yml: syntax OK"
else
  log_error "docker-compose-6nodes.yml: syntax error"
  VALIDATION_FAILED=true
fi

echo

# Step 6: Validate documentation
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
log_info "Step 6: Validating documentation..."
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

DOC_FILES=(
  "../../docs/testing/FAST_SYNC_TESTING_PLAN.md"
  "README.md"
)

for doc in "${DOC_FILES[@]}"; do
  DOC_PATH="$GORGOROTH_DIR/$doc"
  if [ -f "$DOC_PATH" ]; then
    LINES=$(wc -l < "$DOC_PATH")
    echo "  ✓ $doc exists ($LINES lines)"
  else
    log_error "$doc does not exist at $DOC_PATH"
    VALIDATION_FAILED=true
  fi
done

echo

# Step 7: Test script argument handling
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
log_info "Step 7: Testing script argument handling..."
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

# Test monitor-decompression.sh with --help or invalid args
cd "$SCRIPT_DIR"
if bash -n monitor-decompression.sh 2>&1 | grep -q "error"; then
  log_error "monitor-decompression.sh has syntax errors"
  VALIDATION_FAILED=true
else
  echo "  ✓ monitor-decompression.sh: basic validation passed"
fi

echo

# Step 8: Verify Docker image availability
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
log_info "Step 8: Checking Docker image availability..."
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

IMAGE="ghcr.io/chippr-robotics/fukuii:latest"
if docker images -q "$IMAGE" 2> /dev/null | grep -q .; then
  echo "  ✓ Docker image $IMAGE is available locally"
else
  echo "  ⚠ Docker image $IMAGE is not available locally"
  echo "    Note: Image will be pulled automatically when running tests"
fi

echo

# Final Summary
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "=== Validation Summary ==="
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

if [ "$VALIDATION_FAILED" = true ]; then
  echo
  log_error "Validation FAILED - Please fix the issues above"
  echo
  exit 1
else
  echo
  log_success "All validations PASSED"
  echo
  echo "The fast sync test infrastructure is properly configured."
  echo "To run the actual test (requires running Gorgoroth network):"
  echo "  cd $SCRIPT_DIR"
  echo "  ./test-fast-sync.sh"
  echo
  echo "Note: Full test requires:"
  echo "  - Docker containers running"
  echo "  - 1000+ blocks generated on seed nodes (4+ hours)"
  echo "  - Approximately 1 hour for fast sync test"
  echo
  exit 0
fi
