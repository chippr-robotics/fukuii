#!/bin/bash
# Test harness for 3-node log review
# Tests the 3-node environment with node1 mining-enabled=true, node2/3 mining-enabled=false
# Analyzes logs for RLPx errors and block header propagation

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GORGOROTH_DIR="$SCRIPT_DIR/.."

# Color output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Test configuration
CONFIG="3nodes"
COMPOSE_FILE="docker-compose-3nodes.yml"
TIMESTAMP=$(date +%Y%m%d-%H%M%S)
LOGS_DIR="${GORGOROTH_DIR}/logs-3node-review-${TIMESTAMP}"

echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}=== 3-Node Log Review Test Harness ===${NC}"
echo -e "${BLUE}========================================${NC}"
echo ""
echo "Configuration:"
echo "  - Node1: mining-enabled=true"
echo "  - Node2: mining-enabled=false"
echo "  - Node3: mining-enabled=false"
echo ""
echo "Test will:"
echo "  1. Start 3-node network"
echo "  2. Generate enodes"
echo "  3. Sync static-nodes.json"
echo "  4. Allow node1 to mine blocks"
echo "  5. Capture logs"
echo "  6. Analyze for RLPx errors"
echo "  7. Analyze block header propagation"
echo ""
echo "Logs will be saved to: ${LOGS_DIR}"
echo ""

# Function to wait for user confirmation
wait_for_confirmation() {
    local message=$1
    echo -e "${YELLOW}${message}${NC}"
    read -p "Press Enter to continue..."
}

# Function to check if containers are running
check_containers_running() {
    local running_count=$(docker ps --filter "name=gorgoroth-fukuii-" --format "{{.Names}}" | wc -l)
    if [ $running_count -eq 3 ]; then
        return 0
    else
        return 1
    fi
}

# Function to check mining status
check_mining_status() {
    local port=$1
    local node_name=$2
    
    # Try to use jq if available, otherwise fall back to grep/cut
    local is_mining
    if command -v jq &> /dev/null; then
        is_mining=$(curl -s -X POST -H "Content-Type: application/json" \
            --data '{"jsonrpc":"2.0","method":"eth_mining","params":[],"id":1}' \
            http://localhost:$port 2>/dev/null | jq -r '.result // "error"' || echo "error")
    else
        is_mining=$(curl -s -X POST -H "Content-Type: application/json" \
            --data '{"jsonrpc":"2.0","method":"eth_mining","params":[],"id":1}' \
            http://localhost:$port 2>/dev/null | grep -o '"result":[^,}]*' | cut -d':' -f2 || echo "error")
    fi
    
    echo "  ${node_name} (port ${port}): mining=${is_mining}"
}

# Function to get block number
get_block_number() {
    local port=$1
    
    # Try to use jq if available, otherwise fall back to grep/cut
    local block_num
    if command -v jq &> /dev/null; then
        block_num=$(curl -s -X POST -H "Content-Type: application/json" \
            --data '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}' \
            http://localhost:$port 2>/dev/null | jq -r '.result // "0x0"' || echo "0x0")
    else
        block_num=$(curl -s -X POST -H "Content-Type: application/json" \
            --data '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}' \
            http://localhost:$port 2>/dev/null | grep -o '"result":"[^"]*"' | cut -d'"' -f4 || echo "0x0")
    fi
    
    if [[ "$block_num" =~ ^0x[0-9a-fA-F]+$ ]]; then
        echo $((16#${block_num#0x}))
    else
        echo "0"
    fi
}

# Function to analyze logs for RLPx errors
analyze_rlpx_errors() {
    echo -e "${BLUE}=== Analyzing Logs for RLPx Errors ===${NC}"
    echo ""
    
    local error_count=0
    local total_errors=0
    
    for node in node1 node2 node3; do
        local log_file="${LOGS_DIR}/gorgoroth-fukuii-${node}.log"
        
        if [ ! -f "$log_file" ]; then
            echo -e "${YELLOW}Warning: Log file not found: ${log_file}${NC}"
            continue
        fi
        
        echo -e "${GREEN}Analyzing ${node}:${NC}"
        
        # Search for common RLPx errors
        echo "  Searching for RLPx-related errors..."
        
        # RLPx handshake errors
        local handshake_errors=$(grep -i "handshake.*error\|rlpx.*handshake\|handshake.*fail" "$log_file" 2>/dev/null | wc -l || echo "0")
        if [ $handshake_errors -gt 0 ]; then
            echo -e "    ${RED}✗ Found ${handshake_errors} handshake error(s)${NC}"
            total_errors=$((total_errors + handshake_errors))
            error_count=$((error_count + 1))
            
            # Show first few examples
            echo "      Examples:"
            grep -i "handshake.*error\|rlpx.*handshake\|handshake.*fail" "$log_file" 2>/dev/null | head -3 | sed 's/^/        /' || true
        else
            echo -e "    ${GREEN}✓ No handshake errors${NC}"
        fi
        
        # RLPx encoding/decoding errors
        local encoding_errors=$(grep -i "rlp.*error\|rlpx.*encoding\|rlpx.*decoding\|invalid rlp" "$log_file" 2>/dev/null | wc -l || echo "0")
        if [ $encoding_errors -gt 0 ]; then
            echo -e "    ${RED}✗ Found ${encoding_errors} RLP encoding/decoding error(s)${NC}"
            total_errors=$((total_errors + encoding_errors))
            error_count=$((error_count + 1))
            
            # Show first few examples
            echo "      Examples:"
            grep -i "rlp.*error\|rlpx.*encoding\|rlpx.*decoding\|invalid rlp" "$log_file" 2>/dev/null | head -3 | sed 's/^/        /' || true
        else
            echo -e "    ${GREEN}✓ No RLP encoding/decoding errors${NC}"
        fi
        
        # Connection errors
        local connection_errors=$(grep -i "connection.*refused\|connection.*reset\|connection.*timeout\|peer.*disconnect" "$log_file" 2>/dev/null | wc -l || echo "0")
        if [ $connection_errors -gt 0 ]; then
            echo -e "    ${YELLOW}⚠ Found ${connection_errors} connection-related message(s)${NC}"
            # Note: Connection issues may be expected during startup
        else
            echo -e "    ${GREEN}✓ No connection errors${NC}"
        fi
        
        # Snappy compression errors (if applicable)
        local snappy_errors=$(grep -i "snappy.*error\|decompression.*error\|compression.*error" "$log_file" 2>/dev/null | wc -l || echo "0")
        if [ $snappy_errors -gt 0 ]; then
            echo -e "    ${RED}✗ Found ${snappy_errors} Snappy compression error(s)${NC}"
            total_errors=$((total_errors + snappy_errors))
            error_count=$((error_count + 1))
            
            # Show first few examples
            echo "      Examples:"
            grep -i "snappy.*error\|decompression.*error\|compression.*error" "$log_file" 2>/dev/null | head -3 | sed 's/^/        /' || true
        else
            echo -e "    ${GREEN}✓ No Snappy compression errors${NC}"
        fi
        
        echo ""
    done
    
    echo -e "${BLUE}RLPx Error Analysis Summary:${NC}"
    if [ $total_errors -eq 0 ]; then
        echo -e "${GREEN}✓ No critical RLPx errors found${NC}"
    else
        echo -e "${RED}✗ Found ${total_errors} total error(s) across ${error_count} error type(s)${NC}"
    fi
    echo ""
    
    return $total_errors
}

# Function to analyze block header propagation
analyze_block_propagation() {
    echo -e "${BLUE}=== Analyzing Block Header Propagation ===${NC}"
    echo ""
    
    local propagation_issues=0
    
    for node in node1 node2 node3; do
        local log_file="${LOGS_DIR}/gorgoroth-fukuii-${node}.log"
        
        if [ ! -f "$log_file" ]; then
            echo -e "${YELLOW}Warning: Log file not found: ${log_file}${NC}"
            continue
        fi
        
        echo -e "${GREEN}Analyzing ${node}:${NC}"
        
        # Search for block import/propagation messages
        echo "  Searching for block-related messages..."
        
        # Block import success
        local imported_blocks=$(grep -i "imported.*block\|block.*imported\|new block" "$log_file" 2>/dev/null | wc -l || echo "0")
        echo "    Imported blocks: ${imported_blocks}"
        
        # Block received from peers
        local received_blocks=$(grep -i "received.*block\|block.*received" "$log_file" 2>/dev/null | wc -l || echo "0")
        echo "    Received blocks: ${received_blocks}"
        
        # Block broadcast
        local broadcast_blocks=$(grep -i "broadcast.*block\|block.*broadcast" "$log_file" 2>/dev/null | wc -l || echo "0")
        echo "    Broadcast blocks: ${broadcast_blocks}"
        
        # Block validation errors
        local validation_errors=$(grep -i "invalid.*block\|block.*invalid\|block.*validation.*fail" "$log_file" 2>/dev/null | wc -l || echo "0")
        if [ $validation_errors -gt 0 ]; then
            echo -e "    ${RED}✗ Found ${validation_errors} block validation error(s)${NC}"
            propagation_issues=$((propagation_issues + validation_errors))
            
            # Show examples
            echo "      Examples:"
            grep -i "invalid.*block\|block.*invalid\|block.*validation.*fail" "$log_file" 2>/dev/null | head -3 | sed 's/^/        /' || true
        else
            echo -e "    ${GREEN}✓ No block validation errors${NC}"
        fi
        
        # NewBlockHashes messages (ETH protocol)
        local new_block_hashes=$(grep -i "NewBlockHashes\|new block hashes" "$log_file" 2>/dev/null | wc -l || echo "0")
        echo "    NewBlockHashes messages: ${new_block_hashes}"
        
        # NewBlock messages (ETH protocol)
        local new_block_msgs=$(grep -i "NewBlock message\|received NewBlock" "$log_file" 2>/dev/null | wc -l || echo "0")
        echo "    NewBlock messages: ${new_block_msgs}"
        
        echo ""
    done
    
    echo -e "${BLUE}Block Propagation Analysis Summary:${NC}"
    if [ $propagation_issues -eq 0 ]; then
        echo -e "${GREEN}✓ No block propagation issues detected${NC}"
    else
        echo -e "${RED}✗ Found ${propagation_issues} potential propagation issue(s)${NC}"
    fi
    echo ""
    
    return $propagation_issues
}

# Function to check peer connections
check_peer_connections() {
    echo -e "${BLUE}=== Checking Peer Connections ===${NC}"
    echo ""
    
    for port in 8546 8548 8550; do
        local node_id=$((($port - 8546) / 2 + 1))
        echo -e "${GREEN}Node${node_id} (port ${port}):${NC}"
        
        # Try to use jq if available, otherwise fall back to grep/cut
        local peer_count
        if command -v jq &> /dev/null; then
            peer_count=$(curl -s -X POST -H "Content-Type: application/json" \
                --data '{"jsonrpc":"2.0","method":"net_peerCount","params":[],"id":1}' \
                http://localhost:$port 2>/dev/null | jq -r '.result // "0x0"' || echo "0x0")
        else
            peer_count=$(curl -s -X POST -H "Content-Type: application/json" \
                --data '{"jsonrpc":"2.0","method":"net_peerCount","params":[],"id":1}' \
                http://localhost:$port 2>/dev/null | grep -o '"result":"[^"]*"' | cut -d'"' -f4 || echo "0x0")
        fi
        
        if [[ "$peer_count" =~ ^0x[0-9a-fA-F]+$ ]]; then
            local peer_count_dec=$((16#${peer_count#0x}))
            echo "  Peer count: ${peer_count_dec}"
            
            if [ $peer_count_dec -eq 2 ]; then
                echo -e "  ${GREEN}✓ Connected to all expected peers${NC}"
            elif [ $peer_count_dec -eq 0 ]; then
                echo -e "  ${RED}✗ No peers connected${NC}"
            else
                echo -e "  ${YELLOW}⚠ Connected to ${peer_count_dec}/2 expected peers${NC}"
            fi
        else
            echo -e "  ${RED}✗ Could not get peer count${NC}"
        fi
        echo ""
    done
}

# ============================================================================
# Main Test Execution
# ============================================================================

echo -e "${BLUE}Step 1: Starting 3-node network${NC}"
echo ""

cd "$GORGOROTH_DIR"

# Check if network is already running
if check_containers_running; then
    echo -e "${YELLOW}Network appears to be already running.${NC}"
    read -p "Do you want to restart it? (yes/no): " -r
    if [[ $REPLY =~ ^[Yy]es$ ]]; then
        echo "Stopping existing network..."
        docker compose -f "$COMPOSE_FILE" down
        sleep 2
    else
        echo "Using existing network..."
    fi
fi

# Start network if not running
if ! check_containers_running; then
    echo "Starting network..."
    docker compose -f "$COMPOSE_FILE" up -d
    
    echo "Waiting for containers to start (30 seconds)..."
    sleep 30
fi

if ! check_containers_running; then
    echo -e "${RED}Error: Failed to start all 3 nodes${NC}"
    exit 1
fi

echo -e "${GREEN}✓ Network started successfully${NC}"
echo ""

# ============================================================================
echo -e "${BLUE}Step 2: Synchronizing static-nodes.json${NC}"
echo ""

# Use fukuii-cli to sync static nodes
"${GORGOROTH_DIR}/../tools/fukuii-cli.sh" sync-static-nodes

echo "Waiting for nodes to reconnect after restart (30 seconds)..."
sleep 30

echo -e "${GREEN}✓ Static nodes synchronized${NC}"
echo ""

# ============================================================================
echo -e "${BLUE}Step 3: Verifying mining configuration${NC}"
echo ""

echo "Checking mining status for each node:"
check_mining_status 8546 "Node1"
check_mining_status 8548 "Node2"
check_mining_status 8550 "Node3"
echo ""

# ============================================================================
echo -e "${BLUE}Step 4: Allowing node1 to generate blocks${NC}"
echo ""

echo "Monitoring block generation for 120 seconds..."
echo ""

START_BLOCK=$(get_block_number 8546)
echo "Starting block number: ${START_BLOCK}"

# Monitor for 2 minutes
for i in {1..12}; do
    sleep 10
    CURRENT_BLOCK=$(get_block_number 8546)
    BLOCKS_MINED=$((CURRENT_BLOCK - START_BLOCK))
    echo "  [${i}0s] Block: ${CURRENT_BLOCK} (+${BLOCKS_MINED} blocks mined)"
done

echo ""
FINAL_BLOCK=$(get_block_number 8546)
TOTAL_BLOCKS=$((FINAL_BLOCK - START_BLOCK))

if [ $TOTAL_BLOCKS -gt 0 ]; then
    echo -e "${GREEN}✓ Node1 successfully mined ${TOTAL_BLOCKS} block(s)${NC}"
else
    echo -e "${YELLOW}⚠ Warning: No blocks mined by node1${NC}"
fi
echo ""

# Check peer connections
check_peer_connections

# ============================================================================
echo -e "${BLUE}Step 5: Collecting logs from all nodes${NC}"
echo ""

mkdir -p "$LOGS_DIR"

echo "Collecting logs from containers..."
for node in node1 node2 node3; do
    container_name="gorgoroth-fukuii-${node}"
    echo "  ${container_name}..."
    docker logs "$container_name" > "${LOGS_DIR}/${container_name}.log" 2>&1
    docker inspect "$container_name" > "${LOGS_DIR}/${container_name}-inspect.json" 2>/dev/null || true
done

# Save network status
docker compose -f "$COMPOSE_FILE" ps > "${LOGS_DIR}/containers-status.txt"

# Save test metadata
cat > "${LOGS_DIR}/test-metadata.txt" << EOF
3-Node Log Review Test
======================

Test Timestamp: ${TIMESTAMP}
Configuration: ${CONFIG}

Node Configuration:
  - Node1: mining-enabled=true, coinbase=0x1000000000000000000000000000000000000001
  - Node2: mining-enabled=false, coinbase=0x2000000000000000000000000000000000000002
  - Node3: mining-enabled=false, coinbase=0x3000000000000000000000000000000000000003

Test Duration: 120 seconds
Starting Block: ${START_BLOCK}
Ending Block: ${FINAL_BLOCK}
Blocks Mined: ${TOTAL_BLOCKS}

Logs Directory: ${LOGS_DIR}
EOF

echo -e "${GREEN}✓ Logs collected successfully${NC}"
echo ""

# ============================================================================
echo -e "${BLUE}Step 6: Analyzing logs for RLPx errors${NC}"
echo ""

analyze_rlpx_errors
RLPX_EXIT_CODE=$?

# ============================================================================
echo -e "${BLUE}Step 7: Analyzing block header propagation${NC}"
echo ""

analyze_block_propagation
PROPAGATION_EXIT_CODE=$?

# ============================================================================
echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}=== Test Harness Execution Complete ===${NC}"
echo -e "${BLUE}========================================${NC}"
echo ""

echo "Summary:"
echo "  - Logs saved to: ${LOGS_DIR}"
echo "  - Blocks mined: ${TOTAL_BLOCKS}"
echo "  - RLPx errors: $([ $RLPX_EXIT_CODE -eq 0 ] && echo 'None' || echo 'Found')"
echo "  - Block propagation issues: $([ $PROPAGATION_EXIT_CODE -eq 0 ] && echo 'None' || echo 'Found')"
echo ""

echo "Next steps:"
echo "  1. Review detailed logs in: ${LOGS_DIR}"
echo "  2. Examine specific error messages if any were found"
echo "  3. Check peer connectivity and block synchronization"
echo ""

# Generate report
cat > "${LOGS_DIR}/analysis-report.txt" << EOF
3-Node Log Review Analysis Report
==================================

Generated: $(date)
Test Timestamp: ${TIMESTAMP}

Configuration
-------------
- Node1: mining-enabled=true
- Node2: mining-enabled=false
- Node3: mining-enabled=false

Test Results
------------
- Test Duration: 120 seconds
- Starting Block: ${START_BLOCK}
- Ending Block: ${FINAL_BLOCK}
- Total Blocks Mined: ${TOTAL_BLOCKS}

RLPx Error Analysis
-------------------
$([ $RLPX_EXIT_CODE -eq 0 ] && echo "✓ No critical RLPx errors detected" || echo "✗ RLPx errors found - see detailed analysis above")

Block Propagation Analysis
--------------------------
$([ $PROPAGATION_EXIT_CODE -eq 0 ] && echo "✓ No block propagation issues detected" || echo "✗ Block propagation issues found - see detailed analysis above")

Overall Status
--------------
$([ $RLPX_EXIT_CODE -eq 0 ] && [ $PROPAGATION_EXIT_CODE -eq 0 ] && echo "✓ PASS - No issues detected" || echo "✗ FAIL - Issues detected, review logs for details")

Files in this directory:
- gorgoroth-fukuii-node*.log: Container logs
- gorgoroth-fukuii-node*-inspect.json: Container metadata
- containers-status.txt: Container status
- test-metadata.txt: Test configuration details
- analysis-report.txt: This report

For detailed analysis, review the individual log files.
EOF

echo -e "${GREEN}Analysis report saved to: ${LOGS_DIR}/analysis-report.txt${NC}"
echo ""

# Determine exit code
if [ $RLPX_EXIT_CODE -eq 0 ] && [ $PROPAGATION_EXIT_CODE -eq 0 ]; then
    echo -e "${GREEN}✓ Test harness completed successfully - No issues detected${NC}"
    exit 0
else
    echo -e "${YELLOW}⚠ Test harness completed with warnings - Review logs for details${NC}"
    exit 1
fi
