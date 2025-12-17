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
        pitya-lore [options]  - "Short nap" watcher that stops the net once a target block is reached

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

sanitize_enode() {
    local enode="$1"
    enode="${enode%%\?*}"
    enode=$(printf "%s" "$enode" | tr -d '\r\n')
    echo "$enode"
}

normalize_enode_host() {
    local container_name="$1"
    local enode="$2"

    if [[ "$enode" != *"@"* ]]; then
        echo "$enode"
        return
    fi

    local suffix=${enode#*@}
    if [[ "$suffix" == "$enode" || "$suffix" != *":"* ]]; then
        echo "$enode"
        return
    fi

    local port=${suffix##*:}
    local host=${suffix%:$port}
    local replacement="$container_name"

    if [[ -z "$replacement" ]]; then
        echo "$enode"
        return
    fi

    if [[ "$host" == "127.0.0.1" || "$host" == "localhost" || "$host" == "0.0.0.0" ]]; then
        echo "${enode%%@*}@$replacement:$port"
        return
    fi

    echo "$enode"
}

build_static_nodes_json() {
    local self="$1"
    local entries=()

    for peer_container in $CONTAINERS; do
        if [ "$peer_container" == "$self" ]; then
            continue
        fi

        local peer_enode="${ENODES_MAP[$peer_container]}"
        if [ -n "$peer_enode" ]; then
            entries+=("$peer_enode")
        fi
    done

    if [ ${#entries[@]} -eq 0 ]; then
        echo "[]"
        return
    fi

    local json="[\n"
    local idx=0
    local total=${#entries[@]}
    for entry in "${entries[@]}"; do
        idx=$((idx + 1))
        local suffix=",";
        if [ $idx -eq $total ]; then
            suffix=""
        fi
        json+="  \"$entry\"$suffix\n"
    done
    json+="]\n"
    printf "%b" "$json"
}

write_geth_static_nodes() {
    local container="$1"
    local payload="$2"
    local target="/root/.ethereum/static-nodes.json"
    local trusted="/root/.ethereum/trusted-nodes.json"

    if ! docker exec "$container" /bin/sh -c "cat <<'EOF' > $target
$payload
EOF" >/dev/null 2>&1; then
        echo -e "${RED}✗ failed to update $target${NC}"
        return 1
    fi

    docker exec "$container" /bin/sh -c "cat <<'EOF' > $trusted
$payload
EOF" >/dev/null 2>&1 || true

    echo -e "${GREEN}✓ updated${NC}"
    return 0
}

write_besu_static_nodes() {
    local container="$1"
    local payload="$2"
    local target="/opt/besu/data/static-nodes.json"

    if ! docker exec "$container" /bin/sh -c "cat <<'EOF' > $target
$payload
EOF" >/dev/null 2>&1; then
        echo -e "${RED}✗ failed to update $target${NC}"
        return 1
    fi

    echo -e "${GREEN}✓ updated${NC}"
    return 0
}

write_fukuii_static_nodes() {
    local container="$1"
    local payload="$2"
    local target="/app/data/static-nodes.json"

    docker exec "$container" /bin/sh -c "cat <<'EOF' > $target
$payload
EOF" >/dev/null 2>&1
}

sync_static_nodes() {
    echo -e "${BLUE}=== Multi-client Static Nodes Synchronization ===${NC}"
    echo ""

    local fukuii_containers
    local geth_containers
    local besu_containers

    fukuii_containers=$(docker ps --filter "name=gorgoroth-fukuii-" --format "{{.Names}}" | sort)
    geth_containers=$(docker ps --filter "name=gorgoroth-geth-" --format "{{.Names}}" | sort)
    besu_containers=$(docker ps --filter "name=gorgoroth-besu-" --format "{{.Names}}" | sort)

    CONTAINERS=$(printf "%s\n%s\n%s\n" "$fukuii_containers" "$geth_containers" "$besu_containers" | sed '/^$/d')

    if [ -z "$CONTAINERS" ]; then
        echo -e "${RED}Error: No running Fukuii, Core-Geth, or Besu containers detected${NC}"
        echo "Start the network first with: fukuii-cli start [config]"
        exit 1
    fi

    if [ -n "$fukuii_containers" ]; then
        echo -e "${GREEN}Fukuii containers:${NC}"
        echo "$fukuii_containers" | sed 's/^/  - /'
        echo ""
    fi

    if [ -n "$geth_containers" ]; then
        echo -e "${GREEN}Core-Geth containers:${NC}"
        echo "$geth_containers" | sed 's/^/  - /'
        echo ""
    fi

    if [ -n "$besu_containers" ]; then
        echo -e "${GREEN}Besu containers:${NC}"
        echo "$besu_containers" | sed 's/^/  - /'
        echo ""
    fi

    echo -e "${BLUE}Collecting enode URLs from containers...${NC}"
    declare -A ENODES_MAP
    local missing_enodes=0

    for container in $CONTAINERS; do
        echo -n "  $container: "
        if enode=$(get_enode_from_container "$container"); then
            if [ -z "$enode" ]; then
                echo -e "${YELLOW}⚠ extracted empty enode${NC}"
                missing_enodes=1
                continue
            fi
            ENODES_MAP["$container"]="$enode"
            echo -e "${GREEN}✓${NC}"
        else
            echo -e "${RED}✗ (skipped)${NC}"
            missing_enodes=1
        fi
    done

    if [ ${#ENODES_MAP[@]} -eq 0 ]; then
        echo -e "${RED}Error: No enodes could be collected${NC}"
        exit 1
    fi

    if [ $missing_enodes -ne 0 ]; then
        echo -e "${YELLOW}Warning: Some enodes could not be collected; static peer lists may be incomplete${NC}"
    fi

    echo ""
    echo -e "${GREEN}Collected ${#ENODES_MAP[@]} enode(s)${NC}"

    local validator_container=""
    if [ -n "$fukuii_containers" ]; then
        validator_container=${FUKUII_VALIDATOR_CONTAINER:-gorgoroth-fukuii-node1}
        if [[ -n "$FUKUII_VALIDATOR_NODE" ]]; then
            validator_container="gorgoroth-fukuii-${FUKUII_VALIDATOR_NODE}"
        fi

        if [[ -n "$FUKUII_VALIDATOR_CONTAINER" && -n "$FUKUII_VALIDATOR_NODE" ]]; then
            echo -e "${YELLOW}Warning: both FUKUII_VALIDATOR_CONTAINER and FUKUII_VALIDATOR_NODE set; using container override${NC}"
        fi

        if [[ -z "${ENODES_MAP[$validator_container]}" ]]; then
            echo -e "${YELLOW}Warning: validator container '$validator_container' not detected or missing enode.${NC}"
            validator_container=$(printf "%s\n" "${!ENODES_MAP[@]}" | sort | head -n1)
            echo -e "${YELLOW}Falling back to '$validator_container' as validator reference.${NC}"
        fi

        echo -e "${BLUE}Validator container:${NC} $validator_container"
        echo -e "${BLUE}Validator enode:${NC} ${ENODES_MAP[$validator_container]}"
    fi

    if [ -n "$fukuii_containers" ]; then
        echo ""
        echo -e "${BLUE}Updating Fukuii static-nodes.json files...${NC}"
        for container in $fukuii_containers; do
            node_num=$(echo "$container" | grep -o "node[0-9]*" | grep -o "[0-9]*")
            config_file="$GORGOROTH_DIR/conf/node${node_num}/static-nodes.json"
            echo -n "  node${node_num}: "

            if [ ! -f "$config_file" ]; then
                echo -e "${YELLOW}⚠ config file not found: $config_file${NC}"
                continue
            fi

            if [ -n "$validator_container" ] && [ "$container" == "$validator_container" ]; then
                peers_json="[]"
                echo "$peers_json" > "$config_file"
                if write_fukuii_static_nodes "$container" "$peers_json"; then
                    echo -e "${GREEN}✓ validator remains inbound-only${NC}"
                else
                    echo -e "${RED}✗ failed to update container static nodes${NC}"
                fi
                continue
            fi

            peers_json=$(build_static_nodes_json "$container")
            echo "$peers_json" > "$config_file"
            if write_fukuii_static_nodes "$container" "$peers_json"; then
                echo -e "${GREEN}✓ updated${NC}"
            else
                echo -e "${RED}✗ failed to update container static nodes${NC}"
            fi
        done
    fi

    if [ -n "$geth_containers" ]; then
        echo ""
        echo -e "${BLUE}Updating Core-Geth static peers...${NC}"
        for container in $geth_containers; do
            echo -n "  $container: "
            peers_json=$(build_static_nodes_json "$container")
            write_geth_static_nodes "$container" "$peers_json"
        done
    fi

    if [ -n "$besu_containers" ]; then
        echo ""
        echo -e "${BLUE}Updating Besu static peers...${NC}"
        for container in $besu_containers; do
            echo -n "  $container: "
            peers_json=$(build_static_nodes_json "$container")
            write_besu_static_nodes "$container" "$peers_json"
        done
    fi

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
    echo -e "  3. Verify peer connections with the smoke-test command:"
    echo -e "     ${BLUE}fukuii-cli smoke-test fukuii-geth${NC}"
}

get_enode_from_logs() {
    local container_name=$1
    local log_tail="${FUKUII_LOG_TAIL:-500}"
    local enode=""

    enode=$(docker logs --tail "$log_tail" "$container_name" 2>&1 | \
        grep -o 'enode://[^[:space:]]*' | \
        tail -1 || true)

    if [ -z "$enode" ]; then
        enode=$(docker logs "$container_name" 2>&1 | \
            grep -o 'enode://[^[:space:]]*' | \
            tail -1 || true)
    fi

    if [ -z "$enode" ]; then
        return 1
    fi

    if [[ "$enode" =~ \[0:0:0:0:0:0:0:0\] ]]; then
        local node_num=$(echo "$container_name" | grep -o "node[0-9]*" | grep -o "[0-9]*")
        if [ -z "$node_num" ]; then
            echo -e "${YELLOW}Warning: Could not extract node number from container name: $container_name${NC}" >&2
            return 1
        fi
        local hostname="gorgoroth-fukuii-node${node_num}"
        enode=$(echo "$enode" | sed "s/\[0:0:0:0:0:0:0:0\]/$hostname/")
    fi

    enode=$(sanitize_enode "$enode")
    enode=$(normalize_enode_host "$container_name" "$enode")

    if [[ "$enode" != enode://*@* ]]; then
        echo -e "${YELLOW}Warning: Extracted enode has unexpected format: $enode${NC}" >&2
        return 1
    fi

    echo "$enode"
    return 0
}

perform_rpc_request() {
    local container_name=$1
    local method=$2
    local params=${3:-"[]"}
    local payload
    payload=$(printf '{"jsonrpc":"2.0","method":"%s","params":%s,"id":1}' "$method" "$params")
    local ports=(8546 8545)

    # Prefer host-accessible ports first
    for candidate in 8545/tcp 8546/tcp; do
        local binding
        binding=$(docker port "$container_name" "$candidate" 2>/dev/null | head -n1)
        if [ -n "$binding" ]; then
            local host_port=${binding##*:}
            local response
            response=$(curl -s -X POST -H "Content-Type: application/json" --data "$payload" "http://127.0.0.1:$host_port" 2>/dev/null || true)
            if echo "$response" | grep -q '"result"'; then
                echo "$response"
                return 0
            fi
        fi
    done

    # Fallback to in-container RPC (for unpublished ports)
    for port in "${ports[@]}"; do
        local response
        response=$(docker exec "$container_name" curl -s -X POST -H "Content-Type: application/json" --data "$payload" http://localhost:$port 2>/dev/null || true)
        if echo "$response" | grep -q '"result"'; then
            echo "$response"
            return 0
        fi
    done

    return 1
}

get_enode_from_container() {
    local container_name=$1
    local max_retries=5
    local retry=0

    local enode=$(get_enode_from_logs "$container_name")
    if [ -n "$enode" ]; then
        echo "$enode"
        return 0
    fi

    while [ $retry -lt $max_retries ]; do
        local response
        if response=$(perform_rpc_request "$container_name" "admin_nodeInfo"); then
            enode=$(echo "$response" | grep -o '"enode":"[^"]*"' | head -n1 | cut -d'"' -f4)
            if [ -n "$enode" ]; then
                enode=$(sanitize_enode "$enode")
                enode=$(normalize_enode_host "$container_name" "$enode")
                echo "$enode"
                return 0
            fi
        fi

        retry=$((retry + 1))
        if [ $retry -lt $max_retries ]; then
            sleep 2
        fi
    done

    return 1
}

extract_string_result() {
    local response=$1
    echo "$response" | grep -o '"result":"[^"]*"' | head -n1 | cut -d'"' -f4
}

hex_to_dec() {
    local value=$1
    value=${value#0x}
    if [ -z "$value" ]; then
        echo 0
        return
    fi
    printf "%d" "0x$value"
}

smoke_test() {
    local config="${1:-fukuii-geth}"
    local compose_file
    compose_file=$(get_compose_file "$config") || exit 1

    local fukuii_containers
    local geth_containers
    local besu_containers
    fukuii_containers=$(docker ps --filter "name=gorgoroth-fukuii-" --format "{{.Names}}" | sort)
    geth_containers=$(docker ps --filter "name=gorgoroth-geth-" --format "{{.Names}}" | sort)
    besu_containers=$(docker ps --filter "name=gorgoroth-besu-" --format "{{.Names}}" | sort)

    local container_list
    container_list=$(printf "%s\n%s\n%s\n" "$fukuii_containers" "$geth_containers" "$besu_containers" | sed '/^$/d')

    if [ -z "$container_list" ]; then
        echo -e "${RED}Error: No running containers found for configuration '$config'${NC}"
        exit 1
    fi

    echo -e "${BLUE}=== Running Fukuii/Core-Geth smoke test for $config ===${NC}"
    echo -e "${BLUE}Compose file:${NC} $compose_file"

    declare -A BLOCKS
    declare -A PEERS
    declare -A STATUSES
    local failures=0

    for container in $container_list; do
        echo -e "\n${BLUE}Probing $container...${NC}"
        local block_resp
        local block_hex
        local peer_resp
        local peer_hex

        if block_resp=$(perform_rpc_request "$container" "eth_blockNumber"); then
            block_hex=$(extract_string_result "$block_resp")
            if [ -n "$block_hex" ]; then
                BLOCKS["$container"]=$(hex_to_dec "$block_hex")
                echo "  eth_blockNumber: $block_hex"
            else
                echo -e "  ${RED}eth_blockNumber: malformed response${NC}"
                STATUSES["$container"]="rpc"
                failures=1
                continue
            fi
        else
            echo -e "  ${RED}eth_blockNumber: request failed${NC}"
            STATUSES["$container"]="rpc"
            failures=1
            continue
        fi

        if peer_resp=$(perform_rpc_request "$container" "net_peerCount"); then
            peer_hex=$(extract_string_result "$peer_resp")
            if [ -n "$peer_hex" ]; then
                PEERS["$container"]=$(hex_to_dec "$peer_hex")
                echo "  net_peerCount: $peer_hex"
            else
                echo -e "  ${RED}net_peerCount: malformed response${NC}"
                STATUSES["$container"]="peers"
                failures=1
                continue
            fi
        else
            echo -e "  ${RED}net_peerCount: request failed${NC}"
            STATUSES["$container"]="peers"
            failures=1
            continue
        fi

        STATUSES["$container"]="ok"
    done

    local header="\n%-28s %-10s %-12s %-7s %-10s\n"
    printf "$header" "CONTAINER" "CLIENT" "BLOCK" "PEERS" "STATUS"
    printf '%0.s-' {1..70}
    echo ""

    local min_block=""
    local max_block=""

    for container in $container_list; do
        local client="Fukuii"
        if [[ "$container" == gorgoroth-geth-* ]]; then
            client="Core-Geth"
        elif [[ "$container" == gorgoroth-besu-* ]]; then
            client="Besu"
        fi
        local block_val="${BLOCKS[$container]}"
        local peer_val="${PEERS[$container]}"
        local status="${STATUSES[$container]:-missed}"

        if [ -n "$block_val" ]; then
            if [ -z "$min_block" ] || [ "$block_val" -lt "$min_block" ]; then
                min_block=$block_val
            fi
            if [ -z "$max_block" ] || [ "$block_val" -gt "$max_block" ]; then
                max_block=$block_val
            fi
        fi

        printf "%-28s %-10s %-12s %-7s %-10s\n" "$container" "$client" "${block_val:-N/A}" "${peer_val:-N/A}" "$status"
    done

    local block_gap=0
    if [ -n "$min_block" ] && [ -n "$max_block" ]; then
        block_gap=$((max_block - min_block))
    fi

    if [ $block_gap -gt 2 ]; then
        echo -e "\n${RED}Block height gap detected (max-min = $block_gap).${NC}"
        failures=1
    fi

    local fukuii_peer_seen=0
    if [ -n "$fukuii_containers" ]; then
        for container in $fukuii_containers; do
            if [ "${PEERS[$container]:-0}" -gt 0 ]; then
                fukuii_peer_seen=1
                break
            fi
        done
        if [ $fukuii_peer_seen -eq 0 ]; then
            echo -e "${RED}No Fukuii node reports connected peers.${NC}"
            failures=1
        fi
    fi

    local geth_peer_seen=0
    if [ -n "$geth_containers" ]; then
        for container in $geth_containers; do
            if [ "${PEERS[$container]:-0}" -gt 0 ]; then
                geth_peer_seen=1
                break
            fi
        done
        if [ $geth_peer_seen -eq 0 ]; then
            echo -e "${RED}No Core-Geth node reports connected peers.${NC}"
            failures=1
        fi
    fi

    local besu_peer_seen=0
    if [ -n "$besu_containers" ]; then
        for container in $besu_containers; do
            if [ "${PEERS[$container]:-0}" -gt 0 ]; then
                besu_peer_seen=1
                break
            fi
        done
        if [ $besu_peer_seen -eq 0 ]; then
            echo -e "${RED}No Besu node reports connected peers.${NC}"
            failures=1
        fi
    fi

    if [ $failures -eq 0 ]; then
        echo -e "\n${GREEN}Smoke test passed: cross-client peers are exchanging blocks.${NC}"
    else
        echo -e "\n${RED}Smoke test failed. Resolve the issues above before running long-range scenarios.${NC}"
        exit 1
    fi
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

    local container_name="gorgoroth-fukuii-$miner_node"
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

    cd "$GORGOROTH_DIR"
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
        sync_static_nodes
        ;;
    smoke-test)
        smoke_test "$@"
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
