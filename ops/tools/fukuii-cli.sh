#!/usr/bin/env bash
# Fukuii CLI - Unified command-line tool for Fukuii node management
# Provides commands for deployment, network management, and node configuration

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GORGOROTH_DIR="$SCRIPT_DIR/../gorgoroth"
CIRITH_DIR="$SCRIPT_DIR/../cirith-ungol"
CIRITH_COMPOSE_FILE="$CIRITH_DIR/docker-compose.yml"
CIRITH_TOOLS_DIR="$CIRITH_DIR/tools"

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

    Cirith Ungol ETC Testbed:
        cirith-ungol start [mode]      - Start ETC testbed in fast or snap mode (default: fast)
        cirith-ungol stop              - Stop the testbed
        cirith-ungol logs [service]    - Tail logs (optionally specific service)
        cirith-ungol status            - Show container status
        cirith-ungol collect-logs [dir]- Archive logs and RPC snapshots
        cirith-ungol smoketest [mode]  - Run automated smoke validation
        cirith-ungol clean             - Stop and remove volumes

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
    declare -A ENODES_MAP  # Associate container name with its enode
    for container in $CONTAINERS; do
        echo -n "  $container: "
        enode=$(get_enode_from_container "$container")
        if [ $? -eq 0 ] && [ -n "$enode" ]; then
            ENODES_MAP["$container"]="$enode"
            echo -e "${GREEN}✓${NC}"
        else
            echo -e "${RED}✗ (skipped)${NC}"
        fi
    done
    
    if [ ${#ENODES_MAP[@]} -eq 0 ]; then
        echo -e "${RED}Error: No enodes could be collected${NC}"
        exit 1
    fi
    
    echo ""
    echo -e "${GREEN}Collected ${#ENODES_MAP[@]} enode(s)${NC}"
    
    # Update static-nodes.json for each container
    # Each container's static-nodes.json should contain all OTHER nodes (excluding itself)
    echo ""
    echo -e "${BLUE}Updating static-nodes.json in config directories...${NC}"
    for container in $CONTAINERS; do
        node_num=$(echo "$container" | grep -o "node[0-9]*" | grep -o "[0-9]*")
        config_file="$GORGOROTH_DIR/conf/node${node_num}/static-nodes.json"
        
        echo -n "  node${node_num}: "
        if [ -f "$config_file" ]; then
            # Count how many peer nodes this node has (excluding itself)
            peer_count=0
            for other_container in "${!ENODES_MAP[@]}"; do
                if [ "$other_container" != "$container" ]; then
                    peer_count=$((peer_count + 1))
                fi
            done
            
            # Validate that we have at least one peer
            if [ $peer_count -eq 0 ]; then
                echo -e "${YELLOW}⚠ no peers (only 1 node in network?)${NC}"
                # Create empty array for single-node case
                echo "[]" > "$config_file"
                continue
            fi
            
            # Create static-nodes.json with all enodes EXCEPT this node's own
            {
                echo "["
                first=true
                for other_container in "${!ENODES_MAP[@]}"; do
                    if [ "$other_container" != "$container" ]; then
                        if [ "$first" = true ]; then
                            printf '  "%s"' "${ENODES_MAP[$other_container]}"
                            first=false
                        else
                            printf ',\n  "%s"' "${ENODES_MAP[$other_container]}"
                        fi
                    fi
                done
                echo ""
                echo "]"
            } > "$config_file"
            echo -e "${GREEN}✓ updated ($peer_count peer(s))${NC}"
        else
            echo -e "${YELLOW}⚠ config file not found: $config_file${NC}"
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

get_enode_from_logs() {
    local container_name=$1
    # Extract enode from container logs
    # Expected log format: "Node address: enode://<64-hex-chars>@[0:0:0:0:0:0:0:0]:<port>"
    # Example: "INFO [ServerActor] - Node address: enode://abc123...@[0:0:0:0:0:0:0:0]:30303"
    local enode=$(docker logs "$container_name" 2>&1 | \
        grep -o "Node address: enode://[^@]*@\[0:0:0:0:0:0:0:0\]:[0-9]*" | \
        tail -1 | \
        sed 's/Node address: //' || echo "")
    
    if [ -n "$enode" ]; then
        # Validate enode format (should start with "enode://" and contain @)
        if [[ ! "$enode" =~ ^enode://[0-9a-f]+@\[0:0:0:0:0:0:0:0\]:[0-9]+$ ]]; then
            echo "" >&2
            echo -e "${YELLOW}Warning: Extracted enode has unexpected format: $enode${NC}" >&2
            return 1
        fi
        
        # Convert [0:0:0:0:0:0:0:0] to container hostname
        # Extract node number from container name (e.g., gorgoroth-fukuii-node1 -> 1)
        local node_num=$(echo "$container_name" | grep -o "node[0-9]*" | grep -o "[0-9]*")
        if [ -z "$node_num" ]; then
            echo -e "${YELLOW}Warning: Could not extract node number from container name: $container_name${NC}" >&2
            return 1
        fi
        
        local hostname="fukuii-node${node_num}"
        
        # Replace [0:0:0:0:0:0:0:0] with hostname
        enode=$(echo "$enode" | sed "s/\[0:0:0:0:0:0:0:0\]/$hostname/")
        echo "$enode"
        return 0
    fi
    
    return 1
}

get_enode_from_container() {
    local container_name=$1
    local max_retries=5
    local retry=0
    
    # First, try to get enode from logs (works even without admin RPC enabled)
    local enode=$(get_enode_from_logs "$container_name")
    if [ -n "$enode" ]; then
        echo "$enode"
        return 0
    fi
    
    # Fallback to RPC method (requires admin namespace to be enabled)
    while [ $retry -lt $max_retries ]; do
        # Try to get enode via RPC
        # Note: Using grep/cut instead of jq for portability (jq may not be in all containers)
        enode=$(docker exec "$container_name" sh -c \
            'curl -s -X POST --data "{\"jsonrpc\":\"2.0\",\"method\":\"admin_nodeInfo\",\"params\":[],\"id\":1}" http://localhost:8546 | grep -o "\"enode\":\"[^\"]*\"" | cut -d"\"" -f4' \
            2>/dev/null || echo "")
        
        if [ -n "$enode" ]; then
            echo "$enode"
            return 0
        fi
        
        retry=$((retry + 1))
        if [ $retry -lt $max_retries ]; then
            sleep 2
        fi
    done
    
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

# ============================================================================
# Cirith Ungol ETC Testbed Commands
# ============================================================================

ensure_cirith_dirs() {
    mkdir -p "$CIRITH_DIR/conf/generated"
}

normalize_cirith_mode() {
    local mode=$(echo "${1:-fast}" | tr '[:upper:]' '[:lower:]')
    case "$mode" in
        fast|snap)
            echo "$mode"
            ;;
        *)
            echo -e "${RED}Error: Unknown sync mode '${1:-<unspecified>}'. Use 'fast' or 'snap'.${NC}" >&2
            exit 1
            ;;
    esac
}

render_cirith_config() {
    ensure_cirith_dirs
    local mode=$(normalize_cirith_mode "$1")
    local profile
    case "$mode" in
        fast) profile="etc-fast.conf" ;;
        snap) profile="etc-snap.conf" ;;
    esac

    local source_file="$CIRITH_DIR/conf/modes/$profile"
    local target_file="$CIRITH_DIR/conf/generated/runtime.conf"

    if [[ ! -f "$source_file" ]]; then
        echo -e "${RED}Error: Missing Cirith Ungol profile $source_file${NC}" >&2
        exit 1
    fi

    cp "$source_file" "$target_file"
    echo -e "${GREEN}Rendered Cirith Ungol config: $profile -> conf/generated/runtime.conf${NC}"
    echo "$mode"
}

cirith_compose() {
    (cd "$CIRITH_DIR" && "$@")
}

cirith_start() {
    local mode
    mode=$(render_cirith_config "${1:-fast}")
    
    # Validate that rendered config exists
    local target_file="$CIRITH_DIR/conf/generated/runtime.conf"
    if [[ ! -f "$target_file" ]]; then
        echo -e "${RED}Error: Rendered config not found at $target_file${NC}" >&2
        echo -e "${YELLOW}This should have been created by render_cirith_config, but wasn't.${NC}" >&2
        exit 1
    fi
    
    echo -e "${GREEN}Starting Cirith Ungol testbed (${mode} sync)...${NC}"
    (cd "$CIRITH_DIR" && CIRITH_SYNC_MODE="$mode" docker compose up -d)
    echo -e "${GREEN}Cirith Ungol testbed is up.${NC}"
    echo "Next steps:"
    echo "  - View logs:    fukuii-cli cirith-ungol logs"
    echo "  - Status:       fukuii-cli cirith-ungol status"
    echo "  - Smoke test:   fukuii-cli cirith-ungol smoketest $mode"
}

cirith_stop() {
    echo -e "${YELLOW}Stopping Cirith Ungol testbed...${NC}"
    (cd "$CIRITH_DIR" && docker compose down)
    echo -e "${GREEN}Cirith Ungol stopped.${NC}"
}

cirith_restart() {
    local mode=${1:-fast}
    cirith_stop
    sleep 2
    cirith_start "$mode"
}

cirith_status() {
    echo -e "${GREEN}Cirith Ungol container status${NC}"
    (cd "$CIRITH_DIR" && docker compose ps)
}

cirith_logs() {
    local service="${1:-}"
    (cd "$CIRITH_DIR"
        if [[ -n "$service" ]]; then
            echo -e "${GREEN}Following logs for $service${NC}"
            docker compose logs -f "$service"
        else
            echo -e "${GREEN}Following logs for all services${NC}"
            docker compose logs -f
        fi)
}

cirith_clean() {
    echo -e "${RED}WARNING: This will remove Cirith Ungol containers and volumes!${NC}"
    read -p "Are you sure? (yes/no): " -r
    if [[ $REPLY =~ ^[Yy]([Ee][Ss])?$ ]]; then
        (cd "$CIRITH_DIR" && docker compose down -v)
        echo -e "${GREEN}Cirith Ungol environment cleaned.${NC}"
    else
        echo "Cancelled."
    fi
}

cirith_collect_logs() {
    local output_dir="${1:-}" 
    ensure_cirith_dirs
    (cd "$CIRITH_DIR" && ./tools/collect-logs.sh ${output_dir:+"$output_dir"})
}

cirith_smoketest() {
    local mode
    mode=$(render_cirith_config "${1:-fast}")
    (cd "$CIRITH_DIR" && CIRITH_SYNC_MODE="$mode" ./tools/smoketest.sh "$mode")
}

cirith_help() {
    cat << EOF
Cirith Ungol ETC Testbed Commands:
  fukuii-cli cirith-ungol start [fast|snap]     Start containers in desired sync mode
  fukuii-cli cirith-ungol stop                  Stop containers
  fukuii-cli cirith-ungol restart [mode]        Restart containers (default mode: fast)
  fukuii-cli cirith-ungol status                Show container status
  fukuii-cli cirith-ungol logs [service]        Tail logs
  fukuii-cli cirith-ungol collect-logs [dir]    Capture logs and RPC samples
  fukuii-cli cirith-ungol smoketest [mode]      Run smoketest validation
  fukuii-cli cirith-ungol clean                 Remove containers and volumes
EOF
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
    cirith-ungol)
        subcommand=${1:-help}
        if [[ $# -gt 0 ]]; then
            shift
        fi
        case $subcommand in
            start)
                cirith_start "$@"
                ;;
            stop)
                cirith_stop
                ;;
            restart)
                cirith_restart "$@"
                ;;
            status)
                cirith_status
                ;;
            logs)
                cirith_logs "$@"
                ;;
            collect-logs)
                cirith_collect_logs "$@"
                ;;
            smoketest)
                cirith_smoketest "$@"
                ;;
            clean)
                cirith_clean
                ;;
            help|--help|-h)
                cirith_help
                ;;
            *)
                echo -e "${RED}Unknown cirith-ungol subcommand: $subcommand${NC}" >&2
                cirith_help
                exit 1
                ;;
        esac
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
