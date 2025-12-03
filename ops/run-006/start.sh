#!/bin/bash
# Quick start script for Run 006 configuration
# Fukuii node on ETC mainnet - SNAP sync testing

set -euo pipefail

echo "==================================================================="
echo "  Fukuii Run 006 - ETC Mainnet SNAP Sync Testing"
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

# Parse command line arguments
ACTION="${1:-up}"

case "$ACTION" in
    up|start)
        echo "Starting Fukuii Run 006..."
        echo ""
        $COMPOSE_CMD up -d
        echo ""
        echo "✓ Fukuii Run 006 started successfully!"
        echo ""
        echo "Monitoring commands:"
        echo "  - View logs:          $COMPOSE_CMD logs -f fukuii"
        echo "  - Check health:       curl http://localhost:8546/health"
        echo "  - Check readiness:    curl http://localhost:8546/readiness"
        echo "  - Stop node:          $0 stop"
        echo ""
        ;;
    
    down|stop)
        echo "Stopping Fukuii Run 006..."
        $COMPOSE_CMD down
        echo "✓ Fukuii Run 006 stopped."
        ;;
    
    restart)
        echo "Restarting Fukuii Run 006..."
        $COMPOSE_CMD restart
        echo "✓ Fukuii Run 006 restarted."
        ;;
    
    logs)
        echo "Showing Fukuii logs (Ctrl+C to exit)..."
        echo ""
        $COMPOSE_CMD logs -f fukuii
        ;;
    
    status)
        echo "Checking Fukuii Run 006 status..."
        echo ""
        $COMPOSE_CMD ps
        echo ""
        if command -v curl &> /dev/null; then
            echo "Health check:"
            if command -v jq &> /dev/null; then
                curl -s http://localhost:8546/health | jq
            elif command -v json_pp &> /dev/null; then
                curl -s http://localhost:8546/health | json_pp
            else
                curl -s http://localhost:8546/health
            fi
        else
            echo "Install curl to check health endpoints"
        fi
        ;;
    
    clean)
        echo "WARNING: This will remove all data including the blockchain!"
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
        echo "Usage: $0 [command]"
        echo ""
        echo "Commands:"
        echo "  up, start     Start the Fukuii node (default)"
        echo "  down, stop    Stop the Fukuii node"
        echo "  restart       Restart the Fukuii node"
        echo "  logs          Show and follow logs"
        echo "  status        Show node status and health"
        echo "  clean         Stop and remove all data (including blockchain)"
        echo "  help          Show this help message"
        echo ""
        ;;
esac
