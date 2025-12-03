#!/bin/bash
# Quick start script for Run 001 configuration

set -e

echo "==================================================================="
echo "  Fukuii Run 001 - ETC with Debug Sync/Snap Logging"
echo "==================================================================="
echo ""

# Check if docker-compose is available
if ! command -v docker-compose &> /dev/null && ! command -v docker &> /dev/null; then
    echo "Error: Neither docker-compose nor docker is installed."
    echo "Please install Docker to use this configuration."
    exit 1
fi

# Determine which command to use
if command -v docker-compose &> /dev/null; then
    COMPOSE_CMD="docker-compose"
elif docker compose version &> /dev/null; then
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
        echo "Starting Fukuii Run 001..."
        echo ""
        $COMPOSE_CMD up -d
        echo ""
        echo "✓ Fukuii Run 001 started successfully!"
        echo ""
        echo "Monitoring commands:"
        echo "  - View logs:          $COMPOSE_CMD logs -f fukuii"
        echo "  - Check health:       curl http://localhost:8546/health"
        echo "  - Check readiness:    curl http://localhost:8546/readiness"
        echo "  - Stop node:          $0 stop"
        echo ""
        ;;
    
    down|stop)
        echo "Stopping Fukuii Run 001..."
        $COMPOSE_CMD down
        echo "✓ Fukuii Run 001 stopped."
        ;;
    
    restart)
        echo "Restarting Fukuii Run 001..."
        $COMPOSE_CMD restart
        echo "✓ Fukuii Run 001 restarted."
        ;;
    
    logs)
        echo "Showing Fukuii logs (Ctrl+C to exit)..."
        echo ""
        $COMPOSE_CMD logs -f fukuii
        ;;
    
    status)
        echo "Checking Fukuii Run 001 status..."
        echo ""
        $COMPOSE_CMD ps
        echo ""
        if command -v curl &> /dev/null; then
            echo "Health check:"
            curl -s http://localhost:8546/health | json_pp 2>/dev/null || curl -s http://localhost:8546/health
        else
            echo "Install curl to check health endpoints"
        fi
        ;;
    
    clean)
        echo "WARNING: This will remove all data including the blockchain!"
        read -p "Are you sure? (yes/no): " -r
        echo ""
        if [[ $REPLY =~ ^[Yy][Ee][Ss]$ ]]; then
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
