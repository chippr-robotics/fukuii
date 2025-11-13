#!/bin/bash
# Quick start script for the test network

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

echo "========================================="
echo "  Fukuii + Core-Geth Test Network"
echo "========================================="
echo ""

# Function to check if docker-compose is available
check_docker_compose() {
    if command -v docker-compose &> /dev/null; then
        COMPOSE_CMD="docker-compose"
    elif docker compose version &> /dev/null; then
        COMPOSE_CMD="docker compose"
    else
        echo "ERROR: docker-compose or 'docker compose' plugin not found"
        exit 1
    fi
}

# Function to wait for a service to be healthy
wait_for_healthy() {
    local service=$1
    local max_wait=120
    local waited=0
    
    echo "Waiting for $service to be healthy..."
    while [ $waited -lt $max_wait ]; do
        if $COMPOSE_CMD ps $service | grep -q "healthy"; then
            echo "  ✓ $service is healthy"
            return 0
        fi
        sleep 5
        waited=$((waited + 5))
        echo -n "."
    done
    
    echo ""
    echo "  ✗ $service did not become healthy within ${max_wait}s"
    return 1
}

# Main execution
main() {
    check_docker_compose
    
    echo "Step 1: Checking if test network is already running..."
    if $COMPOSE_CMD ps | grep -q "test-core-geth\|test-fukuii"; then
        echo "  Test network containers are already running."
        echo "  Do you want to restart them? (y/n)"
        read -r response
        if [[ "$response" =~ ^[Yy]$ ]]; then
            echo "  Stopping existing containers..."
            $COMPOSE_CMD down
        else
            echo "  Exiting. Use 'docker-compose logs -f' to view logs."
            exit 0
        fi
    fi
    
    echo ""
    echo "Step 2: Starting Core-Geth node..."
    $COMPOSE_CMD up -d core-geth
    
    echo ""
    echo "Step 3: Waiting for Core-Geth to initialize..."
    if ! wait_for_healthy core-geth; then
        echo "ERROR: Core-Geth failed to start properly"
        echo "Check logs with: docker-compose logs core-geth"
        exit 1
    fi
    
    echo ""
    echo "Step 4: Getting Core-Geth enode information..."
    ENODE=$(docker exec test-core-geth geth attach --exec "admin.nodeInfo.enode" http://localhost:8545 2>/dev/null | tr -d '"')
    if [ -n "$ENODE" ]; then
        echo "  Core-Geth enode: $ENODE"
    else
        echo "  Warning: Could not retrieve enode, but continuing..."
    fi
    
    echo ""
    echo "Step 5: Starting Fukuii node..."
    $COMPOSE_CMD up -d fukuii
    
    echo ""
    echo "Step 6: Starting log collector..."
    $COMPOSE_CMD up -d log-collector
    
    echo ""
    echo "Step 7: Waiting for Fukuii to initialize..."
    sleep 10  # Give fukuii some time to start connecting
    
    echo ""
    echo "========================================="
    echo "  Test Network Started Successfully!"
    echo "========================================="
    echo ""
    echo "Container Status:"
    $COMPOSE_CMD ps
    echo ""
    
    echo "Network Information:"
    docker network inspect test-network_test-network --format '{{range .Containers}}  {{.Name}}: {{.IPv4Address}}{{"\n"}}{{end}}' 2>/dev/null || echo "  Network information not available"
    echo ""
    
    echo "Next Steps:"
    echo "  1. View logs in real-time:"
    echo "     docker-compose logs -f"
    echo ""
    echo "  2. View only Fukuii logs:"
    echo "     docker-compose logs -f fukuii"
    echo ""
    echo "  3. Collect logs for analysis:"
    echo "     ./collect-logs.sh"
    echo ""
    echo "  4. Check peer connections:"
    echo "     docker exec test-fukuii curl -s -X POST \\"
    echo "       -H 'Content-Type: application/json' \\"
    echo "       --data '{\"jsonrpc\":\"2.0\",\"method\":\"net_peerCount\",\"params\":[],\"id\":1}' \\"
    echo "       http://localhost:8546"
    echo ""
    echo "  5. Stop the test network:"
    echo "     docker-compose down"
    echo ""
    echo "  6. Stop and remove all data:"
    echo "     docker-compose down -v"
    echo ""
    
    # Show initial logs
    echo "Initial Fukuii logs (looking for handshake activity):"
    echo "-----------------------------------------------------"
    sleep 5
    docker logs test-fukuii 2>&1 | grep -i "handshake\|connection\|peer" | tail -15 || echo "No handshake logs yet. Check again in a few seconds."
    echo ""
    
    echo "Test network is ready for testing!"
}

# Run main function
main "$@"
