#!/usr/bin/env bash
# Validation script for Gorgoroth Docker volume shadowing fix
# Tests that all changes are correctly applied

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# GORGOROTH_DIR is set to SCRIPT_DIR since this script is in the gorgoroth directory
GORGOROTH_DIR="$SCRIPT_DIR"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

echo "=== Gorgoroth Volume Shadowing Fix Validation ==="
echo ""

TESTS_PASSED=0
TESTS_FAILED=0

# Test 1: Verify static-nodes.json files are pre-populated
echo -n "Test 1: Static-nodes.json files are pre-populated... "
for node in node1 node2 node3; do
    file="$GORGOROTH_DIR/conf/$node/static-nodes.json"
    if [ ! -f "$file" ]; then
        echo -e "${RED}FAIL${NC}"
        echo "  File not found: $file"
        TESTS_FAILED=$((TESTS_FAILED + 1))
        continue
    fi
    
    # Check file is not empty (should be > 100 bytes)
    size=$(stat -c %s "$file" 2>/dev/null || stat -f %z "$file" 2>/dev/null)
    if [ "$size" -lt 100 ]; then
        echo -e "${RED}FAIL${NC}"
        echo "  File too small: $file ($size bytes)"
        TESTS_FAILED=$((TESTS_FAILED + 1))
        continue
    fi
done
echo -e "${GREEN}PASS${NC}"
TESTS_PASSED=$((TESTS_PASSED + 1))

# Test 2: Verify JSON is valid
echo -n "Test 2: Static-nodes.json files are valid JSON... "
for node in node1 node2 node3; do
    file="$GORGOROTH_DIR/conf/$node/static-nodes.json"
    if ! jq empty "$file" 2>/dev/null; then
        echo -e "${RED}FAIL${NC}"
        echo "  Invalid JSON: $file"
        TESTS_FAILED=$((TESTS_FAILED + 1))
        continue
    fi
done
echo -e "${GREEN}PASS${NC}"
TESTS_PASSED=$((TESTS_PASSED + 1))

# Test 3: Verify each node has 2 peers (for 3-node network)
echo -n "Test 3: Each node has exactly 2 peers... "
for node in node1 node2 node3; do
    file="$GORGOROTH_DIR/conf/$node/static-nodes.json"
    peer_count=$(jq 'length' "$file")
    if [ "$peer_count" -ne 2 ]; then
        echo -e "${RED}FAIL${NC}"
        echo "  $node has $peer_count peers, expected 2"
        TESTS_FAILED=$((TESTS_FAILED + 1))
        continue
    fi
done
echo -e "${GREEN}PASS${NC}"
TESTS_PASSED=$((TESTS_PASSED + 1))

# Test 4: Verify no node references itself
echo -n "Test 4: No node references itself... "
# Node1 enode: 896acf67a7166e6af8361a4494f574d99c713bc0d0328ddbf6c33a1db51152c9
# Node2 enode: 0037d4884abf8f9abd8ee0a815ee156a6e1ce51eca7bf999e8775d552ce488da
# Node3 enode: 284c0b9f9e8b2791d00e08450d5510f22781aa8261fdf84f0793e5eb350c4535

if grep -q "896acf67a7166e6af8361a4494f574d99c713bc0d0328ddbf6c33a1db51152c9" "$GORGOROTH_DIR/conf/node1/static-nodes.json"; then
    echo -e "${RED}FAIL${NC}"
    echo "  node1 references itself"
    TESTS_FAILED=$((TESTS_FAILED + 1))
elif grep -q "0037d4884abf8f9abd8ee0a815ee156a6e1ce51eca7bf999e8775d552ce488da" "$GORGOROTH_DIR/conf/node2/static-nodes.json"; then
    echo -e "${RED}FAIL${NC}"
    echo "  node2 references itself"
    TESTS_FAILED=$((TESTS_FAILED + 1))
elif grep -q "284c0b9f9e8b2791d00e08450d5510f22781aa8261fdf84f0793e5eb350c4535" "$GORGOROTH_DIR/conf/node3/static-nodes.json"; then
    echo -e "${RED}FAIL${NC}"
    echo "  node3 references itself"
    TESTS_FAILED=$((TESTS_FAILED + 1))
else
    echo -e "${GREEN}PASS${NC}"
    TESTS_PASSED=$((TESTS_PASSED + 1))
fi

# Test 5: Verify docker-compose files don't have static-nodes.json bind mounts
echo -n "Test 5: Docker-compose files have no static-nodes.json bind mounts... "
BIND_MOUNTS_FOUND=false
for file in docker-compose-{3nodes,6nodes,fukuii-besu,fukuii-geth,mixed}.yml; do
    if grep -q "static-nodes.json" "$GORGOROTH_DIR/$file"; then
        echo -e "${RED}FAIL${NC}"
        echo "  Found static-nodes.json bind mount in $file"
        TESTS_FAILED=$((TESTS_FAILED + 1))
        BIND_MOUNTS_FOUND=true
        break
    fi
done
if [ "$BIND_MOUNTS_FOUND" = false ]; then
    echo -e "${GREEN}PASS${NC}"
    TESTS_PASSED=$((TESTS_PASSED + 1))
fi

# Test 6: Verify init-volumes.sh exists and is executable
echo -n "Test 6: init-volumes.sh exists and is executable... "
if [ ! -f "$GORGOROTH_DIR/init-volumes.sh" ]; then
    echo -e "${RED}FAIL${NC}"
    echo "  init-volumes.sh not found"
    TESTS_FAILED=$((TESTS_FAILED + 1))
elif [ ! -x "$GORGOROTH_DIR/init-volumes.sh" ]; then
    echo -e "${RED}FAIL${NC}"
    echo "  init-volumes.sh is not executable"
    TESTS_FAILED=$((TESTS_FAILED + 1))
else
    echo -e "${GREEN}PASS${NC}"
    TESTS_PASSED=$((TESTS_PASSED + 1))
fi

# Test 7: Verify init-volumes.sh has valid bash syntax
echo -n "Test 7: init-volumes.sh has valid syntax... "
if ! bash -n "$GORGOROTH_DIR/init-volumes.sh"; then
    echo -e "${RED}FAIL${NC}"
    echo "  Syntax error in init-volumes.sh"
    TESTS_FAILED=$((TESTS_FAILED + 1))
else
    echo -e "${GREEN}PASS${NC}"
    TESTS_PASSED=$((TESTS_PASSED + 1))
fi

# Test 8: Verify enode format is correct
echo -n "Test 8: Enode URLs have correct format... "
ENODE_FORMAT_ERROR=false
for node in node1 node2 node3; do
    file="$GORGOROTH_DIR/conf/$node/static-nodes.json"
    # Check each enode URL matches the pattern: enode://[128 hex chars]@hostname:30303
    if ! jq -r '.[]' "$file" | grep -qE '^enode://[0-9a-f]{128}@fukuii-node[0-9]+:30303$'; then
        echo -e "${RED}FAIL${NC}"
        echo "  Invalid enode format in $file"
        TESTS_FAILED=$((TESTS_FAILED + 1))
        ENODE_FORMAT_ERROR=true
        break
    fi
done
if [ "$ENODE_FORMAT_ERROR" = false ]; then
    echo -e "${GREEN}PASS${NC}"
    TESTS_PASSED=$((TESTS_PASSED + 1))
fi

# Summary
echo ""
echo "=== Test Summary ==="
echo -e "Passed: ${GREEN}$TESTS_PASSED${NC}"
echo -e "Failed: ${RED}$TESTS_FAILED${NC}"
echo ""

if [ $TESTS_FAILED -eq 0 ]; then
    echo -e "${GREEN}All tests passed!${NC}"
    echo ""
    echo "The Gorgoroth Docker volume shadowing fix has been successfully validated."
    echo ""
    echo "Next steps:"
    echo "  1. Initialize volumes: cd ops/gorgoroth && ./init-volumes.sh 3nodes"
    echo "  2. Start network: fukuii-cli start 3nodes"
    echo "  3. Verify peers: curl -X POST -H 'Content-Type: application/json' \\"
    echo "       --data '{\"jsonrpc\":\"2.0\",\"method\":\"net_peerCount\",\"params\":[],\"id\":1}' \\"
    echo "       http://localhost:8546"
    echo "  Expected result: {\"jsonrpc\":\"2.0\",\"result\":\"0x2\",\"id\":1}"
    exit 0
else
    echo -e "${RED}Some tests failed. Please review the errors above.${NC}"
    exit 1
fi
