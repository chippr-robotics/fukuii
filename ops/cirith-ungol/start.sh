#!/bin/bash
# Quick start script for Testbed configuration
# Fukuii node on ETC mainnet - SNAP sync testing

set -euo pipefail

echo "==================================================================="
echo "  Fukuii Testbed - ETC Mainnet SNAP Sync Testing"
echo "==================================================================="
echo ""

# Check if docker-compose or docker compose is available
if ! command -v docker-compose &> /dev/null && ! docker compose version &> /dev/null 2>&1; then
    echo "Error: Neither docker-compose nor docker compose is available."
    echo "Please install Docker Compose to use this configuration."
    exit 1
fi

# Determine which command to use
if command -v docker-compose &> /dev/null; then
    COMPOSE_CMD="docker-compose"
elif docker compose version &> /dev/null 2>&1; then
    COMPOSE_CMD="docker compose"
else
    echo "Error: Docker Compose is not available."
    exit 1
fi

echo "Using: $COMPOSE_CMD"
echo ""

# Change to the script directory
cd "$(dirname "$0")"

# Function to capture logs from a container
capture_container_logs() {
    local container_name=$1
    local log_file=$2
    
    echo "  Capturing logs from $container_name..."
    if docker ps --format '{{.Names}}' | grep -q "^${container_name}$"; then
        if docker logs "$container_name" > "$log_file" 2>&1; then
            echo "    ✓ Saved to: $log_file"
        else
            echo "    ✗ Failed to capture logs from $container_name"
        fi
    else
        echo "    ✗ $container_name is not running"
    fi
}

# Function to collect logs from both nodes
collect_logs() {
    local TIMESTAMP=$(date +%Y%m%d_%H%M%S)
    local LOG_DIR="./captured-logs"
    mkdir -p "$LOG_DIR"
    
    echo "=================================="
    echo "Testbed Log Collection"
    echo "=================================="
    echo "Timestamp: $TIMESTAMP"
    echo ""
    
    # Check container status
    echo "Checking container status..."
    echo ""
    
    # Capture logs from both containers
    echo "Collecting logs..."
    capture_container_logs "fukuii-cirith-ungol" "$LOG_DIR/fukuii_${TIMESTAMP}.log"
    echo ""
    
    # Get SNAP-specific information from fukuii
    echo "Fukuii SNAP Sync Status:"
    echo "------------------------"
    docker logs fukuii-cirith-ungol 2>&1 | grep "SNAP Sync Progress" | tail -5 || echo "  No SNAP progress logs found"
    echo ""
    
    # Get peer capability information from fukuii
    echo "Fukuii Peer Capabilities:"
    echo "-------------------------"
    docker logs fukuii-cirith-ungol 2>&1 | grep "PEER_HANDSHAKE_SUCCESS" | tail -10 || echo "  No handshake logs found"
    echo ""
    
    # Get peer count from fukuii node
    echo "Peer Count:"
    echo "-----------"
    echo -n "  Fukuii: "
    docker exec fukuii-cirith-ungol curl -s -X POST \
      -H "Content-Type: application/json" \
      --data '{"jsonrpc":"2.0","method":"net_peerCount","params":[],"id":1}' \
      http://localhost:8546 2>/dev/null | grep -o '"result":"[^"]*"' || echo "Unable to get peer count"
    echo ""
    
    # Display recent SNAP-related errors from fukuii
    echo "Recent Fukuii SNAP Errors:"
    echo "--------------------------"
    docker logs fukuii-cirith-ungol 2>&1 | grep -i "snap.*error\|snap.*timeout\|snap.*fail" | tail -10 || echo "  No SNAP errors found"
    echo ""
    
    echo "=================================="
    echo "Log collection complete!"
    echo "Logs saved in: $LOG_DIR"
    echo "=================================="
    echo ""
    echo "Log files:"
    ls -lh "$LOG_DIR" | tail -n +2
    echo ""
    echo "To analyze logs:"
    echo "  - View fukuii SNAP logs:     grep -i snap $LOG_DIR/fukuii_${TIMESTAMP}.log"
    echo "  - View peer capabilities:    grep PEER_HANDSHAKE_SUCCESS $LOG_DIR/fukuii_${TIMESTAMP}.log"
    echo "  - View GetAccountRange:      grep GetAccountRange $LOG_DIR/fukuii_${TIMESTAMP}.log"
    echo ""
}

# Parse command line arguments
ACTION="${1:-up}"

case "$ACTION" in
    up|start)
        echo ""
        $COMPOSE_CMD up -d
        echo ""
        echo "✓ Fukuii Testbed started successfully!"
        echo ""
        echo "Monitoring commands:"
        echo "  - View fukuii logs:   $COMPOSE_CMD logs -f fukuii"
        echo "  - View all logs:      $COMPOSE_CMD logs -f"
        echo "  - Collect logs:       $0 collect-logs"
        echo "  - Check health:       curl http://localhost:8546/health"
        echo "  - Stop nodes:         $0 stop"
        echo ""
        ;;
    
    down|stop)
        echo "Stopping Fukuii Testbed (both nodes)..."
        $COMPOSE_CMD down
        echo "✓ Fukuii Testbed stopped."
        ;;
    
    restart)
        echo "Restarting Fukuii Testbed (both nodes)..."
        $COMPOSE_CMD restart
        echo "✓ Fukuii Testbed restarted."
        ;;
    
    logs)
        SERVICE="${2:-}"
        if [ -n "$SERVICE" ]; then
            echo "Showing $SERVICE logs (Ctrl+C to exit)..."
            echo ""
            $COMPOSE_CMD logs -f "$SERVICE"
        else
            echo "Showing all logs (Ctrl+C to exit)..."
            echo ""
            $COMPOSE_CMD logs -f
        fi
        ;;
    
    collect-logs|capture)
        collect_logs
        ;;
    
    status)
        echo "Checking Fukuii Testbed status..."
        echo ""
        $COMPOSE_CMD ps
        echo ""
        
        if command -v curl &> /dev/null; then
            echo "Fukuii health check:"
            if command -v jq &> /dev/null; then
                curl -s http://localhost:8546/health | jq 2>/dev/null || curl -s http://localhost:8546/health
            else
                curl -s http://localhost:8546/health
            fi
            echo ""
        else
            echo "Install curl to check health endpoints"
        fi
        ;;
    
    clean)
        echo "WARNING: This will remove all data including the blockchain!"
        echo "This includes:"
        echo "  - fukuii-cirith-ungol-data (blockchain data)"
        echo "  - fukuii-cirith-ungol-logs (log files)"
        read -p "Are you sure? (yes/no): " -r
        echo ""
        if [[ $REPLY =~ ^[Yy]([Ee][Ss])?$ ]]; then
            echo "Stopping and removing all data..."
            $COMPOSE_CMD down -v
            echo "✓ All data removed."
        else
            echo "Cancelled."
        fi
        ;;
    
    help|*)
        echo "Usage: $0 [command] [options]"
        echo ""
        echo "Commands:"
        echo "  down, stop       Stop both nodes"
        echo "  restart          Restart both nodes"
        echo "  collect-logs     Capture logs from both nodes to files"
        echo "  status           Show node status and health"
        echo "  clean            Stop and remove all data (including blockchain)"
        echo "  help             Show this help message"
        echo ""
        echo "Examples:"
        echo "  $0 start                    # Start both nodes"
        echo "  $0 logs fukuii              # Follow fukuii logs only"
        echo "  $0 collect-logs             # Capture logs to files"
        echo "  $0 status                   # Check status of both nodes"
        echo ""
        ;;
esac
