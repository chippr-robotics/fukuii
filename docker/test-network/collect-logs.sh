#!/bin/bash
# Script to collect logs from the test network

set -e

TIMESTAMP=$(date +%Y%m%d_%H%M%S)
LOG_DIR="./captured-logs"
mkdir -p "$LOG_DIR"

echo "=================================="
echo "Test Network Log Collection Script"
echo "=================================="
echo "Timestamp: $TIMESTAMP"
echo ""

# Function to capture logs from a container
capture_container_logs() {
    local container_name=$1
    local log_file="$LOG_DIR/${container_name}_${TIMESTAMP}.log"
    
    echo "Capturing logs from $container_name..."
    docker logs "$container_name" > "$log_file" 2>&1
    echo "  ✓ Saved to: $log_file"
}

# Function to check if container is running
check_container() {
    local container_name=$1
    if docker ps --format '{{.Names}}' | grep -q "^${container_name}$"; then
        echo "  ✓ $container_name is running"
        return 0
    else
        echo "  ✗ $container_name is not running"
        return 1
    fi
}

# Check container status
echo "Checking container status..."
check_container "test-core-geth"
check_container "test-fukuii"
echo ""

# Capture logs from both containers
echo "Collecting logs..."
capture_container_logs "test-core-geth"
capture_container_logs "test-fukuii"
echo ""

# Get network information
echo "Network Information:"
echo "-------------------"
docker network inspect test-network_test-network --format '{{range .Containers}}{{.Name}}: {{.IPv4Address}}{{"\n"}}{{end}}' 2>/dev/null || echo "Network not found"
echo ""

# Get peer information from core-geth
echo "Core-geth Peer Information:"
echo "---------------------------"
docker exec test-core-geth geth attach --exec "admin.peers" http://localhost:8545 2>/dev/null || echo "Unable to get peer info from core-geth"
echo ""

# Get connection status from fukuii (if RPC is available)
echo "Fukuii Connection Status:"
echo "-------------------------"
docker exec test-fukuii curl -s -X POST \
  -H "Content-Type: application/json" \
  --data '{"jsonrpc":"2.0","method":"net_peerCount","params":[],"id":1}' \
  http://localhost:8546 2>/dev/null || echo "Unable to get peer count from fukuii"
echo ""

# Display recent handshake-related logs from fukuii
echo "Recent Fukuii Handshake Logs:"
echo "-----------------------------"
docker logs test-fukuii 2>&1 | grep -i "handshake\|rlpx\|connection" | tail -20 || echo "No handshake logs found"
echo ""

# Display any errors from fukuii
echo "Recent Fukuii Errors:"
echo "---------------------"
docker logs test-fukuii 2>&1 | grep -i "error\|exception" | tail -10 || echo "No errors found"
echo ""

echo "=================================="
echo "Log collection complete!"
echo "Logs saved in: $LOG_DIR"
echo "=================================="
echo ""
echo "To view full logs:"
echo "  - Core-geth: docker logs test-core-geth"
echo "  - Fukuii: docker logs test-fukuii"
echo ""
echo "To follow logs in real-time:"
echo "  docker-compose logs -f"
