#!/usr/bin/env bash
# Fukuii CLI - Unified command-line tool for Fukuii node management
# Provides commands for deployment, network management, and node configuration

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GORGOROTH_DIR="$SCRIPT_DIR/../gorgoroth"
CIRITH_UNGOL_DIR="$SCRIPT_DIR/../cirith-ungol"

# Color output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Available configurations for Gorgoroth test network and Cirith-Ungol mainnet
CONFIGS=(
    "3nodes:docker-compose-3nodes.yml:gorgoroth:3 Fukuii nodes"
    "6nodes:docker-compose-6nodes.yml:gorgoroth:6 Fukuii nodes"
    "fukuii-geth:docker-compose-fukuii-geth.yml:gorgoroth:3 Fukuii + 3 Core-Geth nodes"
    "fukuii-besu:docker-compose-fukuii-besu.yml:gorgoroth:3 Fukuii + 3 Besu nodes"
    "mixed:docker-compose-mixed.yml:gorgoroth:3 Fukuii + 3 Besu + 3 Core-Geth nodes"
    "cirith-ungol:docker-compose.yml:cirith-ungol:Fukuii + Core-Geth on ETC mainnet"
)

print_usage() {
    cat << EOF
Fukuii CLI - Unified command-line tool for Fukuii node management

Usage: fukuii-cli <command> [options]

Commands:
  Network Deployment:
    start [config]        - Start a network (default: 3nodes)
    stop [config]         - Stop the network
    restart [config]      - Restart the network
    status [config]       - Show status of all containers
    logs [config]         - Follow logs from all containers
    clean [config]        - Stop and remove all containers and volumes
  
  Node Configuration:
    sync-static-nodes [config] - Synchronize static-nodes.json across running containers
    collect-logs [config] - Collect logs from all containers
    pitya-lore [options]  - "Short nap" watcher that stops the net once a target block is reached

  Help:
    help                  - Show this help message
    version               - Show version information

Available configurations:
EOF
    for config in "${CONFIGS[@]}"; do
        IFS=: read -r name file network desc <<< "$config"
        printf "  %-15s - %s\n" "$name" "$desc"
    done
    cat << EOF

Examples:
  fukuii-cli start 3nodes
  fukuii-cli start cirith-ungol
  fukuii-cli sync-static-nodes 6nodes
  fukuii-cli logs fukuii-geth
  fukuii-cli status cirith-ungol
  fukuii-cli stop mixed

For more information, see: docs/runbooks/node-configuration.md
EOF
}

get_compose_file() {
    local config_name="${1:-3nodes}"
    for config in "${CONFIGS[@]}"; do
        IFS=: read -r name file network desc <<< "$config"
        if [[ "$name" == "$config_name" ]]; then
            echo "$file"
            return 0
        fi
    done
    echo -e "${RED}Error: Unknown configuration '$config_name'${NC}" >&2
    echo -e "${YELLOW}Run 'fukuii-cli help' to see available configurations${NC}" >&2
    return 1
}

get_network_dir() {
    local config_name="${1:-3nodes}"
    for config in "${CONFIGS[@]}"; do
        IFS=: read -r name file network desc <<< "$config"
        if [[ "$name" == "$config_name" ]]; then
            case "$network" in
                gorgoroth)
                    echo "$GORGOROTH_DIR"
                    ;;
                cirith-ungol)
                    echo "$CIRITH_UNGOL_DIR"
                    ;;
                *)
                    echo -e "${RED}Error: Unknown network type '$network'${NC}" >&2
                    return 1
                    ;;
            esac
            return 0
        fi
    done
    echo -e "${RED}Error: Unknown configuration '$config_name'${NC}" >&2
    return 1
}

# ============================================================================
# Network Deployment Commands
# ============================================================================

start_network() {
    local config="${1:-3nodes}"
    local compose_file
    local network_dir
    compose_file=$(get_compose_file "$config") || exit 1
    network_dir=$(get_network_dir "$config") || exit 1
    
    echo -e "${GREEN}Starting network with configuration: $config${NC}"
    echo "Using compose file: $compose_file"
    echo "Network directory: $network_dir"
    
    cd "$network_dir"
    docker compose -f "$compose_file" up -d
    
    echo -e "${GREEN}Network started successfully!${NC}"
    echo ""
    echo "Next steps:"
    echo "  View logs:   fukuii-cli logs $config"
    echo "  Check status: fukuii-cli status $config"
    if [[ "$config" != "cirith-ungol" ]]; then
        echo "  Sync peers:   fukuii-cli sync-static-nodes $config"
    fi
}

stop_network() {
    local config="${1:-3nodes}"
    local compose_file
    local network_dir
    compose_file=$(get_compose_file "$config") || exit 1
    network_dir=$(get_network_dir "$config") || exit 1
    
    echo -e "${YELLOW}Stopping network: $config${NC}"
    cd "$network_dir"
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
    local network_dir
    compose_file=$(get_compose_file "$config") || exit 1
    network_dir=$(get_network_dir "$config") || exit 1
    
    echo -e "${GREEN}Status of network: $config${NC}"
    cd "$network_dir"
    docker compose -f "$compose_file" ps
}

show_logs() {
    local config="${1:-3nodes}"
    local compose_file
    local network_dir
    compose_file=$(get_compose_file "$config") || exit 1
    network_dir=$(get_network_dir "$config") || exit 1
    
    echo -e "${GREEN}Following logs for: $config${NC}"
    echo "Press Ctrl+C to stop following logs"
    cd "$network_dir"
    docker compose -f "$compose_file" logs -f
}

clean_network() {
    local config="${1:-3nodes}"
    local compose_file
    local network_dir
    compose_file=$(get_compose_file "$config") || exit 1
    network_dir=$(get_network_dir "$config") || exit 1
    
    echo -e "${RED}WARNING: This will remove all containers and volumes for: $config${NC}"
    read -p "Are you sure? (yes/no): " -r
    if [[ $REPLY =~ ^[Yy]es$ ]]; then
        echo -e "${YELLOW}Cleaning network...${NC}"
        cd "$network_dir"
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
    local config="${1:-3nodes}"
    
    echo -e "${BLUE}=== Fukuii Static Nodes Synchronization ===${NC}"
    echo -e "${BLUE}Configuration: $config${NC}"
    echo ""
    
    # Determine container name pattern based on configuration
    local container_pattern
    if [[ "$config" == "cirith-ungol" ]]; then
        container_pattern="cirith-ungol"
        echo -e "${YELLOW}Note: Cirith-Ungol uses static peer configuration in conf/static-nodes.json${NC}"
        echo -e "${YELLOW}This command will sync the fukuii node's enode to the static-nodes.json file${NC}"
        echo ""
    else
        container_pattern="gorgoroth-fukuii-"
    fi
    
    # Find all running Fukuii containers
    CONTAINERS=$(docker ps --filter "name=${container_pattern}" --format "{{.Names}}" | sort)
    
    if [ -z "$CONTAINERS" ]; then
        echo -e "${RED}Error: No running containers found for pattern: $container_pattern${NC}"
        echo "Start the network first with: fukuii-cli start $config"
        exit 1
    fi
    
    echo -e "${GREEN}Found running containers:${NC}"
    echo "$CONTAINERS" | sed 's/^/  - /'
    echo ""
    
    # Handle cirith-ungol differently
    if [[ "$config" == "cirith-ungol" ]]; then
        sync_cirith_ungol_nodes "$CONTAINERS"
        return $?
    fi
    
    # Original gorgoroth sync logic
    sync_gorgoroth_nodes "$CONTAINERS"
}

sync_cirith_ungol_nodes() {
    local containers="$1"
    local network_dir
    network_dir=$(get_network_dir "cirith-ungol") || exit 1
    
    # Collect enodes from containers
    echo -e "${BLUE}Collecting enode URLs from containers...${NC}"
    declare -A ENODES_MAP
    for container in $containers; do
        echo -n "  $container: "
        if enode=$(get_enode_from_container "$container"); then
            if [ -z "$enode" ]; then
                echo -e "${YELLOW}⚠ extracted empty enode${NC}"
                continue
            fi
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
    
    # Build static-nodes.json with all collected enodes
    local static_nodes_file="$network_dir/conf/static-nodes.json"
    echo -e "${BLUE}Updating static-nodes.json at: $static_nodes_file${NC}"
    
    # Create JSON array of enodes
    local json_content="["
    local first=true
    for container in $(echo "${!ENODES_MAP[@]}" | tr ' ' '\n' | sort); do
        if [ "$first" = true ]; then
            first=false
        else
            json_content+=","
        fi
        json_content+=$'\n  '
        json_content+="\"${ENODES_MAP[$container]}\""
    done
    json_content+=$'\n]\n'
    
    echo "$json_content" > "$static_nodes_file"
    echo -e "${GREEN}✓ Static nodes file updated${NC}"
    
    echo ""
    echo -e "${YELLOW}Restarting containers to apply static peers configuration...${NC}"
    for container in $containers; do
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
    echo -e "  2. Check logs: ${BLUE}fukuii-cli logs cirith-ungol${NC}"
    echo -e "  3. Verify peer connections via RPC"
}

sync_gorgoroth_nodes() {
    local containers="$1"
    local network_dir
    network_dir=$(get_network_dir "6nodes") || network_dir="$GORGOROTH_DIR"
    
    # Collect enodes from all containers
    echo -e "${BLUE}Collecting enode URLs from containers...${NC}"
    declare -A ENODES_MAP  # Associate container name with its enode
    for container in $containers; do
        echo -n "  $container: "
        if enode=$(get_enode_from_container "$container"); then
            if [ -z "$enode" ]; then
                echo -e "${YELLOW}⚠ extracted empty enode${NC}"
                continue
            fi
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
    
    local validator_container=${FUKUII_VALIDATOR_CONTAINER:-gorgoroth-fukuii-node1}
    if [[ -n "$FUKUII_VALIDATOR_NODE" ]]; then
        validator_container="gorgoroth-fukuii-${FUKUII_VALIDATOR_NODE}"
    fi

    if [[ -n "$FUKUII_VALIDATOR_CONTAINER" && -n "$FUKUII_VALIDATOR_NODE" ]]; then
        echo -e "${YELLOW}Warning: both FUKUII_VALIDATOR_CONTAINER and FUKUII_VALIDATOR_NODE set; using container override${NC}"
    fi

    if [[ -z "${ENODES_MAP[$validator_container]}" ]]; then
        echo -e "${YELLOW}Warning: validator container '$validator_container' not detected or missing enode.${NC}"
        # Fall back to the first container alphabetically if validator missing
        validator_container=$(printf "%s\n" "${!ENODES_MAP[@]}" | sort | head -n1)
        echo -e "${YELLOW}Falling back to '$validator_container' as validator reference.${NC}"
    fi

    local validator_enode="${ENODES_MAP[$validator_container]}"
    if [[ -z "$validator_enode" ]]; then
        echo -e "${RED}Error: unable to determine validator enode${NC}"
        exit 1
    fi

    echo -e "${BLUE}Validator container:${NC} $validator_container"
    echo -e "${BLUE}Validator enode:${NC} $validator_enode"

    # Update static-nodes.json for each container
    # Followers should include only the validator; validator should have none
    echo ""
    echo -e "${BLUE}Updating static-nodes.json in config directories...${NC}"
    for container in $containers; do
        node_num=$(echo "$container" | grep -o "node[0-9]*" | grep -o "[0-9]*")
        config_file="$network_dir/conf/node${node_num}/static-nodes.json"
        
        echo -n "  node${node_num}: "
        if [ -f "$config_file" ]; then
            if [ "$container" == "$validator_container" ]; then
                echo "[]" > "$config_file"
                echo -e "${GREEN}✓ validator: no static peers${NC}"
            else
                printf '[\n  "%s"\n]\n' "$validator_enode" > "$config_file"
                echo -e "${GREEN}✓ follower -> validator${NC}"
            fi
        else
            echo -e "${YELLOW}⚠ config file not found: $config_file${NC}"
        fi
    done
    
    echo ""
    echo -e "${YELLOW}Restarting containers to apply static peers configuration...${NC}"
    for container in $containers; do
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
    local log_tail="${FUKUII_LOG_TAIL:-500}"
    local enode=""

    enode=$(docker logs --tail "$log_tail" "$container_name" 2>&1 | \
        grep -o "Node address: enode://[^@]*@\[0:0:0:0:0:0:0:0\]:[0-9]*" | \
        tail -1 | \
        sed 's/Node address: //' || true)

    if [ -z "$enode" ]; then
        enode=$(docker logs "$container_name" 2>&1 | \
            grep -o "Node address: enode://[^@]*@\[0:0:0:0:0:0:0:0\]:[0-9]*" | \
            tail -1 | \
            sed 's/Node address: //' || true)
    fi

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
    local network_dir
    network_dir=$(get_network_dir "$config") || exit 1
    
    echo -e "${GREEN}Collecting logs from network: $config${NC}"
    echo "Output directory: $output_dir"
    
    cd "$network_dir"
    if [ -f "./collect-logs.sh" ]; then
        ./collect-logs.sh "$config" "$output_dir"
    else
        # Fallback to basic log collection if collect-logs.sh doesn't exist
        mkdir -p "$output_dir"
        local compose_file
        compose_file=$(get_compose_file "$config") || exit 1
        docker compose -f "$compose_file" logs > "$output_dir/all-logs.txt"
        echo -e "${GREEN}Logs saved to: $output_dir/all-logs.txt${NC}"
    fi
}

show_version() {
    echo "Fukuii CLI v1.0.0"
    echo "Unified command-line tool for Fukuii node management"
}

pitya_lore_short_nap() {
    local config="6nodes"
    local rpc_url="http://localhost:8545"
    local target_block=1000000
    local poll_interval=30
    local snapshot_dir="$GORGOROTH_DIR/debug-logs"
    local miner_node="node1"
    local stop_mode="stop"

    while [[ $# -gt 0 ]]; do
        case "$1" in
            --config)
                config="$2"
                shift 2
                ;;
            --rpc-url)
                rpc_url="$2"
                shift 2
                ;;
            --target-block)
                target_block="$2"
                shift 2
                ;;
            --interval)
                poll_interval="$2"
                shift 2
                ;;
            --snapshot-dir)
                snapshot_dir="$2"
                shift 2
                ;;
            --miner-node)
                miner_node="$2"
                shift 2
                ;;
            --stop-mode)
                stop_mode="$2"
                shift 2
                ;;
            --help|-h)
                cat <<'EOF'
Usage: fukuii-cli pitya-lore [options]

Options:
  --config <name>        Compose config to control (default: 6nodes)
  --rpc-url <url>        RPC endpoint to poll (default: http://localhost:8545)
  --target-block <num>   Block height to stop at (default: 1000000)
  --interval <seconds>   Poll interval in seconds (default: 30)
  --snapshot-dir <path>  Directory for miner snapshots (default: ops/gorgoroth/debug-logs)
  --miner-node <node>    Container suffix for miner logs (default: node1)
  --stop-mode <stop|down> Action to stop the compose stack (default: stop)
EOF
                return 0
                ;;
            *)
                echo -e "${RED}Error: Unknown option '$1'${NC}" >&2
                return 1
                ;;
        esac
    done

    if [[ ! "$target_block" =~ ^[0-9]+$ ]]; then
        echo -e "${RED}Error: target block must be a positive integer${NC}" >&2
        return 1
    fi

    if [[ ! "$poll_interval" =~ ^[0-9]+$ || "$poll_interval" -le 0 ]]; then
        echo -e "${RED}Error: interval must be a positive integer${NC}" >&2
        return 1
    fi

    if [[ "$stop_mode" != "stop" && "$stop_mode" != "down" ]]; then
        echo -e "${RED}Error: stop-mode must be either 'stop' or 'down'${NC}" >&2
        return 1
    fi

    local compose_file
    compose_file=$(get_compose_file "$config") || return 1
    
    local network_dir
    network_dir=$(get_network_dir "$config") || return 1

    local container_name="gorgoroth-fukuii-$miner_node"
    # Adjust container name for cirith-ungol
    if [[ "$config" == "cirith-ungol" ]]; then
        container_name="fukuii-cirith-ungol"
    fi
    
    mkdir -p "$snapshot_dir"

    echo -e "${GREEN}Pitya-lórë watcher engaged — monitoring $rpc_url until block >= $target_block${NC}"
    echo "Snapshot directory: $snapshot_dir"
    echo "Stopping action: docker compose -f $compose_file $stop_mode"

    local current_block=0
    while true; do
        local response
        response=$(curl -s -X POST -H 'Content-Type: application/json' \
            --data '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}' \
            "$rpc_url" || true)

        local block_hex
        block_hex=$(echo "$response" | grep -o '"result":"[^"]*"' | head -n1 | cut -d'"' -f4)

        if [[ -z "$block_hex" ]]; then
            echo -e "${YELLOW}[$(date '+%H:%M:%S')] Unable to parse block height from RPC response${NC}"
            sleep "$poll_interval"
            continue
        fi

        block_hex=${block_hex#0x}
        if [[ -z "$block_hex" ]]; then
            current_block=0
        else
            current_block=$((16#$block_hex))
        fi

        echo "[$(date '+%H:%M:%S')] Block height: $current_block / $target_block"

        if (( current_block >= target_block )); then
            break
        fi

        sleep "$poll_interval"
    done

    local timestamp=$(date +%Y%m%d-%H%M%S)
    local snapshot_file="$snapshot_dir/pitya-lore-${timestamp}.log"
    {
        echo "Pitya-lórë short nap snapshot"
        echo "Timestamp: $(date)"
        echo "RPC URL: $rpc_url"
        echo "Target block: $target_block"
        echo "Reached block: $current_block"
        echo "Config: $config"
        echo "Miner container: $container_name"
        echo "Stop mode: $stop_mode"
        echo ""
        echo "eth_mining"
        curl -s -X POST -H 'Content-Type: application/json' \
            --data '{"jsonrpc":"2.0","method":"eth_mining","params":[],"id":1}' "$rpc_url"
        echo ""
        echo "eth_hashrate"
        curl -s -X POST -H 'Content-Type: application/json' \
            --data '{"jsonrpc":"2.0","method":"eth_hashrate","params":[],"id":1}' "$rpc_url"
        echo ""
        echo "Recent miner logs"
        echo "-----------------"
    } > "$snapshot_file"

    if ! docker logs "$container_name" --tail 500 >> "$snapshot_file" 2>&1; then
        echo -e "${YELLOW}Warning: Unable to capture logs from $container_name${NC}" | tee -a "$snapshot_file"
    fi

    echo -e "${BLUE}Snapshot saved to $snapshot_file${NC}"

    cd "$network_dir"
    echo -e "${YELLOW}Target met — issuing docker compose $stop_mode to let the network take its short nap${NC}"
    if ! docker compose -f "$compose_file" "$stop_mode"; then
        echo -e "${RED}Warning: docker compose $stop_mode failed${NC}"
        return 1
    fi

    echo -e "${GREEN}Pitya-lórë complete. Network is resting.${NC}"
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
        sync_static_nodes "$@"
        ;;
    collect-logs)
        collect_logs_cmd "$@"
        ;;
    pitya-lore)
        pitya_lore_short_nap "$@"
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
