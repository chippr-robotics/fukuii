#!/usr/bin/env bash
# Fukuii CLI - Unified command-line tool for Fukuii node management
# Provides commands for deployment, network management, and node configuration

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GORGOROTH_DIR="$SCRIPT_DIR/../gorgoroth"

# Color output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Available configurations for Gorgoroth test network
CONFIGS=(
    "3nodes:docker-compose-3nodes.yml:3 Fukuii nodes"
    "6nodes:docker-compose-6nodes.yml:6 Fukuii nodes"
    "fukuii-geth:docker-compose-fukuii-geth.yml:3 Fukuii + 3 Core-Geth nodes"
    "fukuii-besu:docker-compose-fukuii-besu.yml:3 Fukuii + 3 Besu nodes"
    "mixed:docker-compose-mixed.yml:3 Fukuii + 3 Besu + 3 Core-Geth nodes"
)

print_usage() {
    cat << EOF
Fukuii CLI - Unified command-line tool for Fukuii node management

Usage: fukuii-cli <command> [options]

Commands:
  Network Deployment:
    start [config]        - Start the Gorgoroth test network (default: 3nodes)
    stop [config]         - Stop the network
    restart [config]      - Restart the network
    status [config]       - Show status of all containers
    logs [config]         - Follow logs from all containers
    clean [config]        - Stop and remove all containers and volumes
  
  Node Configuration:
    sync-static-nodes     - Synchronize static-nodes.json across running containers
    collect-logs [config] - Collect logs from all containers

  Help:
    help                  - Show this help message
    version               - Show version information

Available configurations:
EOF
    for config in "${CONFIGS[@]}"; do
        IFS=: read -r name file desc <<< "$config"
        printf "  %-15s - %s\n" "$name" "$desc"
    done
    cat << EOF

Examples:
  fukuii-cli start 3nodes
  fukuii-cli sync-static-nodes
  fukuii-cli logs fukuii-geth
  fukuii-cli status
  fukuii-cli stop mixed

For more information, see: docs/runbooks/node-configuration.md
EOF
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
    echo -e "${YELLOW}Run 'fukuii-cli help' to see available configurations${NC}" >&2
    return 1
}

# ============================================================================
# Network Deployment Commands
# ============================================================================

start_network() {
    local config="${1:-3nodes}"
    local compose_file
    compose_file=$(get_compose_file "$config") || exit 1
    
    echo -e "${GREEN}Starting Gorgoroth test network with configuration: $config${NC}"
    echo "Using compose file: $compose_file"
    
    cd "$GORGOROTH_DIR"
    docker compose -f "$compose_file" up -d
    
    echo -e "${GREEN}Network started successfully!${NC}"
    echo ""
    echo "Next steps:"
    echo "  View logs:   fukuii-cli logs $config"
    echo "  Check status: fukuii-cli status $config"
    echo "  Sync peers:   fukuii-cli sync-static-nodes"
}

stop_network() {
    local config="${1:-3nodes}"
    local compose_file
    compose_file=$(get_compose_file "$config") || exit 1
    
    echo -e "${YELLOW}Stopping Gorgoroth test network: $config${NC}"
    cd "$GORGOROTH_DIR"
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
    cd "$GORGOROTH_DIR"
    docker compose -f "$compose_file" ps
}

show_logs() {
    local config="${1:-3nodes}"
    local compose_file
    compose_file=$(get_compose_file "$config") || exit 1
    
    echo -e "${GREEN}Following logs for: $config${NC}"
    echo "Press Ctrl+C to stop following logs"
    cd "$GORGOROTH_DIR"
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
        cd "$GORGOROTH_DIR"
        docker compose -f "$compose_file" down -v
        echo -e "${GREEN}Network cleaned${NC}"
    else
        echo "Cancelled"
    fi
}

# ============================================================================
# Node Configuration Commands
# ============================================================================

sync_static_nodes() {
    echo -e "${BLUE}=== Fukuii Static Nodes Synchronization ===${NC}"
    echo ""
    
    # Temporary file for consolidated static nodes
    TEMP_STATIC_NODES=$(mktemp)
    trap "rm -f $TEMP_STATIC_NODES" EXIT
    
    # Find all running Fukuii containers
    CONTAINERS=$(docker ps --filter "name=gorgoroth-fukuii-" --format "{{.Names}}" | sort)
    
    if [ -z "$CONTAINERS" ]; then
        echo -e "${RED}Error: No running Gorgoroth Fukuii containers found${NC}"
        echo "Start the network first with: fukuii-cli start [config]"
        exit 1
    fi
    
    echo -e "${GREEN}Found running containers:${NC}"
    echo "$CONTAINERS" | sed 's/^/  - /'
    echo ""
    
    # Collect enodes from all containers
    echo -e "${BLUE}Collecting enode URLs from containers...${NC}"
    ENODES=()
    for container in $CONTAINERS; do
        echo -n "  $container: "
        enode=$(get_enode_from_container "$container")
        if [ $? -eq 0 ] && [ -n "$enode" ]; then
            ENODES+=("$enode")
            echo -e "${GREEN}✓${NC}"
        else
            echo -e "${RED}✗ (skipped)${NC}"
        fi
    done
    
    if [ ${#ENODES[@]} -eq 0 ]; then
        echo -e "${RED}Error: No enodes could be collected${NC}"
        exit 1
    fi
    
    echo ""
    echo -e "${GREEN}Collected ${#ENODES[@]} enode(s)${NC}"
    
    # Create static-nodes.json with proper formatting
    {
        echo "["
        for i in "${!ENODES[@]}"; do
            if [ $i -eq $((${#ENODES[@]} - 1)) ]; then
                printf '  "%s"\n' "${ENODES[$i]}"
            else
                printf '  "%s",\n' "${ENODES[$i]}"
            fi
        done
        echo "]"
    } > "$TEMP_STATIC_NODES"
    
    echo ""
    echo -e "${BLUE}Generated static-nodes.json:${NC}"
    cat "$TEMP_STATIC_NODES" | sed 's/^/  /'
    echo ""
    
    # Copy static-nodes.json to each container
    echo -e "${BLUE}Copying static-nodes.json to containers...${NC}"
    for container in $CONTAINERS; do
        echo -n "  $container: "
        if docker cp "$TEMP_STATIC_NODES" "$container:/app/data/static-nodes.json" 2>/dev/null; then
            echo -e "${GREEN}✓${NC}"
        else
            echo -e "${RED}✗ (failed to copy)${NC}"
        fi
    done
    
    echo ""
    echo -e "${YELLOW}Restarting containers to apply static peers configuration...${NC}"
    for container in $CONTAINERS; do
        echo -n "  $container: "
        if docker restart "$container" >/dev/null 2>&1; then
            echo -e "${GREEN}✓${NC}"
        else
            echo -e "${RED}✗ (failed to restart)${NC}"
        fi
    done
    
    echo ""
    echo -e "${GREEN}=== Static nodes synchronization complete ===${NC}"
    echo ""
    echo -e "Next steps:"
    echo -e "  1. Wait for containers to start: ${BLUE}docker ps${NC}"
    echo -e "  2. Check logs: ${BLUE}fukuii-cli logs${NC}"
    echo -e "  3. Verify peer connections:"
    echo -e "     ${BLUE}curl -X POST --data '{\"jsonrpc\":\"2.0\",\"method\":\"net_peerCount\",\"params\":[],\"id\":1}' http://localhost:8546${NC}"
}

get_enode_from_container() {
    local container_name=$1
    local max_retries=5
    local retry=0
    
    while [ $retry -lt $max_retries ]; do
        # Try to get enode via RPC
        # Note: Using grep/cut instead of jq for portability (jq may not be in all containers)
        local enode=$(docker exec "$container_name" sh -c \
            'curl -s -X POST --data "{\"jsonrpc\":\"2.0\",\"method\":\"admin_nodeInfo\",\"params\":[],\"id\":1}" http://localhost:8546 | grep -o "\"enode\":\"[^\"]*\"" | cut -d"\"" -f4' \
            2>/dev/null || echo "")
        
        if [ -n "$enode" ]; then
            echo "$enode"
            return 0
        fi
        
        retry=$((retry + 1))
        if [ $retry -lt $max_retries ]; then
            echo -e "${YELLOW}Retry $retry/$max_retries for $container_name...${NC}" >&2
            sleep 2
        fi
    done
    
    echo -e "${RED}Failed to get enode from $container_name after $max_retries retries${NC}" >&2
    return 1
}

collect_logs_cmd() {
    local config="${1:-3nodes}"
    local timestamp=$(date +%Y%m%d-%H%M%S)
    local output_dir="${2:-./logs-$timestamp}"
    
    echo -e "${GREEN}Collecting logs from Gorgoroth network: $config${NC}"
    echo "Output directory: $output_dir"
    
    cd "$GORGOROTH_DIR"
    ./collect-logs.sh "$config" "$output_dir"
}

show_version() {
    echo "Fukuii CLI v1.0.0"
    echo "Unified command-line tool for Fukuii node management"
}

# ============================================================================
# Main Command Dispatcher
# ============================================================================

if [[ $# -eq 0 ]]; then
    print_usage
    exit 0
fi

COMMAND=$1
shift

case $COMMAND in
    start)
        start_network "$@"
        ;;
    stop)
        stop_network "$@"
        ;;
    restart)
        restart_network "$@"
        ;;
    status)
        show_status "$@"
        ;;
    logs)
        show_logs "$@"
        ;;
    clean)
        clean_network "$@"
        ;;
    sync-static-nodes)
        sync_static_nodes
        ;;
    collect-logs)
        collect_logs_cmd "$@"
        ;;
    help|--help|-h)
        print_usage
        ;;
    version|--version|-v)
        show_version
        ;;
    *)
        echo -e "${RED}Error: Unknown command '$COMMAND'${NC}"
        echo ""
        print_usage
        exit 1
        ;;
esac
