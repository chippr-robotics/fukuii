#!/usr/bin/env bash
# Gorgoroth Internal Test Network Deployment Script
# Manages deployment of various node configurations

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Color output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Available configurations
CONFIGS=(
    "3nodes:docker-compose-3nodes.yml:3 Fukuii nodes"
    "6nodes:docker-compose-6nodes.yml:6 Fukuii nodes"
    "fukuii-geth:docker-compose-fukuii-geth.yml:3 Fukuii + 3 Core-Geth nodes"
    "fukuii-besu:docker-compose-fukuii-besu.yml:3 Fukuii + 3 Besu nodes"
    "mixed:docker-compose-mixed.yml:3 Fukuii + 3 Besu + 3 Core-Geth nodes"
)

print_usage() {
    echo "Usage: $0 {start|stop|restart|status|logs|clean|sync-static-nodes} [config]"
    echo ""
    echo "Commands:"
    echo "  start [config]        - Start the network with specified config (default: 3nodes)"
    echo "  stop [config]         - Stop the network"
    echo "  restart [config]      - Restart the network"
    echo "  status [config]       - Show status of all containers"
    echo "  logs [config]         - Follow logs from all containers"
    echo "  clean [config]        - Stop and remove all containers and volumes"
    echo "  sync-static-nodes     - Collect enodes from running containers, create static-nodes.json,"
    echo "                          distribute to all containers, and restart them"
    echo ""
    echo "Available configurations:"
    for config in "${CONFIGS[@]}"; do
        IFS=: read -r name file desc <<< "$config"
        printf "  %-15s - %s\n" "$name" "$desc"
    done
    echo ""
    echo "Examples:"
    echo "  $0 start 3nodes"
    echo "  $0 logs fukuii-geth"
    echo "  $0 sync-static-nodes"
    echo "  $0 stop mixed"
}

get_compose_file() {
    local config_name="${1:-3nodes}"
    for config in "${CONFIGS[@]}"; do
        IFS=: read -r name file desc <<< "$config"
        if [[ "$name" == "$config_name" ]]; then
            echo "$file"
            return 0
        fi
    done
    echo -e "${RED}Error: Unknown configuration '$config_name'${NC}" >&2
    echo -e "${YELLOW}Run '$0' without arguments to see available configurations${NC}" >&2
    return 1
}

start_network() {
    local config="${1:-3nodes}"
    local compose_file
    compose_file=$(get_compose_file "$config") || exit 1
    
    echo -e "${GREEN}Starting Gorgoroth test network with configuration: $config${NC}"
    echo "Using compose file: $compose_file"
    
    docker compose -f "$compose_file" up -d
    
    echo -e "${GREEN}Network started successfully!${NC}"
    echo ""
    echo "To view logs: $0 logs $config"
    echo "To check status: $0 status $config"
}

stop_network() {
    local config="${1:-3nodes}"
    local compose_file
    compose_file=$(get_compose_file "$config") || exit 1
    
    echo -e "${YELLOW}Stopping Gorgoroth test network: $config${NC}"
    docker compose -f "$compose_file" down
    echo -e "${GREEN}Network stopped${NC}"
}

restart_network() {
    local config="${1:-3nodes}"
    stop_network "$config"
    sleep 2
    start_network "$config"
}

show_status() {
    local config="${1:-3nodes}"
    local compose_file
    compose_file=$(get_compose_file "$config") || exit 1
    
    echo -e "${GREEN}Status of Gorgoroth network: $config${NC}"
    docker compose -f "$compose_file" ps
}

show_logs() {
    local config="${1:-3nodes}"
    local compose_file
    compose_file=$(get_compose_file "$config") || exit 1
    
    echo -e "${GREEN}Following logs for: $config${NC}"
    echo "Press Ctrl+C to stop following logs"
    docker compose -f "$compose_file" logs -f
}

clean_network() {
    local config="${1:-3nodes}"
    local compose_file
    compose_file=$(get_compose_file "$config") || exit 1
    
    echo -e "${RED}WARNING: This will remove all containers and volumes for: $config${NC}"
    read -p "Are you sure? (yes/no): " -r
    if [[ $REPLY =~ ^[Yy]es$ ]]; then
        echo -e "${YELLOW}Cleaning network...${NC}"
        docker compose -f "$compose_file" down -v
        echo -e "${GREEN}Network cleaned${NC}"
    else
        echo "Cancelled"
    fi
}

# Main script logic
if [[ $# -eq 0 ]]; then
    print_usage
    exit 0
fi

COMMAND=$1
CONFIG=${2:-3nodes}

case $COMMAND in
    start)
        start_network "$CONFIG"
        ;;
    stop)
        stop_network "$CONFIG"
        ;;
    restart)
        restart_network "$CONFIG"
        ;;
    status)
        show_status "$CONFIG"
        ;;
    logs)
        show_logs "$CONFIG"
        ;;
    clean)
        clean_network "$CONFIG"
        ;;
    sync-static-nodes)
        "$SCRIPT_DIR/../tools/fukuii-cli.sh"
        ;;
    *)
        echo -e "${RED}Error: Unknown command '$COMMAND'${NC}"
        echo ""
        print_usage
        exit 1
        ;;
esac
