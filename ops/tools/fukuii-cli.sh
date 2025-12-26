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

# ============================================================================
# Utility helpers
# ============================================================================

sanitize_enode_url() {
    local raw="$1"

    if [[ -z "$raw" ]]; then
        return 1
    fi

    raw=${raw//[$'\r\n']/}
    raw=${raw#"${raw%%[![:space:]]*}"}
    raw=${raw%"${raw##*[![:space:]]}"}
    raw=${raw%%\?*}

    local pattern="^enode://[0-9a-fA-F]{${ENODE_ID_LENGTH}}@.+:[0-9]+$"
    if [[ "$raw" =~ $pattern ]]; then
        echo "$raw"
        return 0
    fi

    return 1
}

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
    smoke-test [config] - Quick smoketest (sync status, block, peers)
    collect-logs [config] - Collect logs from all containers
        reset-fast-sync [config] - Soft-reset fast sync state and restart fukuii
        inject-peers [config] [script-args...] - Inject peers from a core-geth/geth node into fukuii via RPC
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
  fukuii-cli smoke-test fukuii-geth
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

# Constants for enode validation
readonly ENODE_ID_LENGTH=128  # Enode IDs are 128 hex characters (512 bits)

# Helper function to generate static-nodes.json content from array of enodes
generate_static_nodes_json() {
    local -n enodes_ref=$1  # Use nameref to pass array by reference
    local json_content='['
    
    for i in "${!enodes_ref[@]}"; do
        json_content+="\"${enodes_ref[$i]}\""
        if [ $i -lt $((${#enodes_ref[@]} - 1)) ]; then
            json_content+=","
        fi
    done
    json_content+=']'
    
    echo "$json_content"
}

ensure_python3() {
    if ! command -v python3 >/dev/null 2>&1; then
        echo -e "${RED}Error: python3 is required for this operation${NC}" >&2
        return 1
    fi
}

update_toml_array() {
    local file_path="$1"
    local key="$2"
    shift 2
    local values=("$@")

    ensure_python3 || return 1

    python3 - "$file_path" "$key" "${values[@]}" <<'PY'
import sys
from pathlib import Path
import re

file_path = Path(sys.argv[1])
key = sys.argv[2]
values = sys.argv[3:]

content = file_path.read_text()

def build_array(vals):
    if not vals:
        return f"{key} = []"
    body = ",\n".join(f'  "{v}"' for v in vals)
    return f"{key} = [\n{body}\n]"

pattern = re.compile(rf'({re.escape(key)}\s*=\s*\[)(.*?)(\])', re.DOTALL)
replacement = build_array(values)

if pattern.search(content):
    content = pattern.sub(replacement, content, count=1)
else:
    section_pattern = re.compile(r'(\[Node\.P2P\][^\[]*)', re.DOTALL)
    match = section_pattern.search(content)
    if not match:
        sys.stderr.write(f"Could not locate [Node.P2P] section to insert {key} in {file_path}\n")
        sys.exit(1)
    start, end = match.span()
    updated = match.group(1).rstrip() + "\n" + replacement + "\n"
    content = content[:start] + updated + content[end:]

file_path.write_text(content)
PY
}

update_geth_config_peers() {
    local config_file="$1"
    local -n peers_ref=$2

    if [ ! -f "$config_file" ]; then
        echo -e "${YELLOW}⚠ geth config not found: $config_file${NC}" >&2
        return 1
    fi

    update_toml_array "$config_file" "StaticNodes" "${peers_ref[@]}" || return 1
    update_toml_array "$config_file" "TrustedNodes" "${peers_ref[@]}" || return 1
    return 0
}

sync_static_nodes() {
    local config="${1:-3nodes}"
    
    echo -e "${BLUE}=== Multi-Client Static Nodes Synchronization ===${NC}"
    echo -e "${BLUE}Configuration: $config${NC}"
    echo ""
    
    # Determine container name patterns based on configuration
    local container_patterns=()
    if [[ "$config" == "cirith-ungol" ]]; then
        container_patterns+=("fukuii-cirith-ungol" "coregeth-cirith-ungol")
        echo -e "${YELLOW}Note: Cirith-Ungol uses static peer configuration${NC}"
        echo ""
    elif [[ "$config" == "fukuii-geth" ]]; then
        container_patterns+=("gorgoroth-fukuii-" "gorgoroth-geth-")
    elif [[ "$config" == "fukuii-besu" ]]; then
        container_patterns+=("gorgoroth-fukuii-" "gorgoroth-besu-")
    elif [[ "$config" == "mixed" ]]; then
        container_patterns+=("gorgoroth-fukuii-" "gorgoroth-geth-" "gorgoroth-besu-")
    else
        # Default: only Fukuii nodes (3nodes, 6nodes)
        container_patterns+=("gorgoroth-fukuii-")
    fi
    
    # Find all running containers matching the patterns
    local CONTAINERS=""
    for pattern in "${container_patterns[@]}"; do
        local found_containers=$(docker ps --filter "name=${pattern}" --format "{{.Names}}" | sort)
        if [ -n "$found_containers" ]; then
            CONTAINERS="${CONTAINERS}${found_containers}"$'\n'
        fi
    done
    CONTAINERS=$(echo "$CONTAINERS" | grep -v '^$' | sort)
    
    if [ -z "$CONTAINERS" ]; then
        echo -e "${RED}Error: No running containers found for patterns: ${container_patterns[*]}${NC}"
        echo "Start the network first with: fukuii-cli start $config"
        exit 1
    fi
    
    echo -e "${GREEN}Found running containers:${NC}"
    echo "$CONTAINERS" | sed 's/^/  - /'
    echo ""
    
    # Handle cirith-ungol differently
    if [[ "$config" == "cirith-ungol" ]]; then
        sync_cirith_ungol_nodes "$CONTAINERS" "$config"
        return $?
    fi
    
    # Multi-client gorgoroth sync logic
    sync_gorgoroth_nodes "$CONTAINERS" "$config"
}

sync_cirith_ungol_nodes() {
    local containers="$1"
    local config="$2"
    local network_dir
    network_dir=$(get_network_dir "$config") || exit 1
    
    # Collect enodes from containers
    echo -e "${BLUE}Collecting enode URLs from all containers...${NC}"
    declare -A ENODES_MAP
    declare -A CONTAINER_TYPES
    
    for container in $containers; do
        local container_type=$(get_container_type "$container")
        CONTAINER_TYPES["$container"]="$container_type"
        
        echo -n "  $container ($container_type): "
        if [ "$container_type" == "geth" ]; then
            local enode_raw=""

            # Prefer the host-mapped RPC endpoint for cirith-ungol coregeth.
            # This avoids depending on curl inside the container and avoids flaky attach parsing.
            if [[ "$container" == "coregeth-cirith-ungol" ]]; then
                enode_raw=$(curl -s -H 'Content-Type: application/json' -X POST \
                    --data '{"jsonrpc":"2.0","method":"admin_nodeInfo","params":[],"id":1}' \
                    http://localhost:18545 2>/dev/null | \
                    grep -o '"enode":"[^"]*"' | head -n1 | cut -d'"' -f4 || true)
            fi

            # Fallback to container attach (IPC then HTTP)
            if [ -z "$enode_raw" ]; then
                for _ in $(seq 1 15); do
                    enode_raw=$(docker exec "$container" sh -c \
                        "geth attach --exec 'admin.nodeInfo.enode' /root/.ethereum/geth.ipc 2>/dev/null || geth attach --exec 'admin.nodeInfo.enode' http://localhost:8545 2>/dev/null" \
                        2>/dev/null | tr -d '\\r' | tr -d '\\n' | sed 's/^[[:space:]]*\"//; s/\"[[:space:]]*$//' || true)
                    if [ -n "$enode_raw" ]; then
                        break
                    fi
                    sleep 2
                done
            fi

            if [ -n "$enode_raw" ]; then
                if enode=$(sanitize_enode_url "$enode_raw"); then
                    local hostname
                    hostname=$(get_container_service_hostname "$container")

                    # Force hostname to the docker-compose service name so peers dial inside the bridge network.
                    local pattern="^enode://([0-9a-fA-F]{${ENODE_ID_LENGTH}})@(.+):([0-9]+)$"
                    if [[ "$enode" =~ $pattern ]]; then
                        enode="enode://${BASH_REMATCH[1]}@$hostname:${BASH_REMATCH[3]}"
                    fi

                    ENODES_MAP["$container"]="$enode"
                    echo -e "${GREEN}✓${NC}"
                    continue
                fi
            fi

            echo -e "${RED}✗ (skipped)${NC}"
        else
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
        fi
    done
    
    if [ ${#ENODES_MAP[@]} -eq 0 ]; then
        echo -e "${RED}Error: No enodes could be collected${NC}"
        exit 1
    fi
    
    echo ""
    echo -e "${GREEN}Collected ${#ENODES_MAP[@]} enode(s)${NC}"

    # For cirith-ungol we expect at least one enode for each client type.
    # If we can't collect both, don't clobber existing peer configuration.
    local have_geth=false
    local have_fukuii=false
    for container in $(echo "${!ENODES_MAP[@]}" | tr ' ' '\n' | sort); do
        local t="${CONTAINER_TYPES[$container]}"
        if [ "$t" == "geth" ]; then
            have_geth=true
        elif [ "$t" == "fukuii" ]; then
            have_fukuii=true
        fi
    done
    if [ "$have_geth" != "true" ] || [ "$have_fukuii" != "true" ]; then
        echo -e "${YELLOW}⚠ Not all enodes collected (geth=$have_geth, fukuii=$have_fukuii).${NC}" >&2
        echo -e "${YELLOW}⚠ Refusing to overwrite static peer config files; try again once containers are fully started.${NC}" >&2
        return 1
    fi
    
    # Build static-nodes.json with client-specific peer sets
    # - Host file is mounted into Fukuii, so it should list Core-Geth peers only.
    # - Core-Geth's internal static-nodes.json should list Fukuii peers only.
    local static_nodes_file="$network_dir/conf/static-nodes.json"
    echo -e "${BLUE}Updating static-nodes.json at: $static_nodes_file${NC}"

    local fukuii_peers=()
    local geth_peers=()
    for container in $(echo "${!ENODES_MAP[@]}" | tr ' ' '\n' | sort); do
        local container_type="${CONTAINER_TYPES[$container]}"
        if [ "$container_type" == "geth" ]; then
            fukuii_peers+=("${ENODES_MAP[$container]}")
        else
            geth_peers+=("${ENODES_MAP[$container]}")
        fi
    done

    # Pretty JSON writer
    build_pretty_json() {
        local -n _arr=$1
        local _json="["
        for i in "${!_arr[@]}"; do
            if [ "$i" -gt 0 ]; then
                _json+=","
            fi
            _json+=$'\n  '
            _json+="\"${_arr[$i]}\""
        done
        _json+=$'\n]\n'
        echo "$_json"
    }

    local fukuii_json_content
    fukuii_json_content=$(build_pretty_json fukuii_peers)
    echo "$fukuii_json_content" > "$static_nodes_file"
    echo -e "${GREEN}✓ Static nodes file updated${NC}"

    # Keep CoreGeth's startup config consistent with static-nodes.json.
    # CoreGeth in cirith-ungol is launched with --config=/root/.ethereum/config.toml,
    # so if that file contains a stale StaticNodes entry it can keep dialing with the
    # wrong pubkey and cause RLPx auth-init decode failures on the remote.
    local coregeth_config_file="$network_dir/conf/coregeth-config.toml"
    local peer_enodes=()
    for container in $(echo "${!ENODES_MAP[@]}" | tr ' ' '\n' | sort); do
        local container_type="${CONTAINER_TYPES[$container]}"
        if [ "$container_type" != "geth" ]; then
            peer_enodes+=("${ENODES_MAP[$container]}")
        fi
    done

    echo -e "${BLUE}Updating CoreGeth config peers at: $coregeth_config_file${NC}"
    {
        echo "[Node.P2P]"
        echo "StaticNodes = ["
        for i in "${!peer_enodes[@]}"; do
            printf '  "%s"' "${peer_enodes[$i]}"
            if [ "$i" -lt $((${#peer_enodes[@]} - 1)) ]; then
                printf ',\n'
            else
                printf '\n'
            fi
        done
        echo "]"
        echo "TrustedNodes = ["
        for i in "${!peer_enodes[@]}"; do
            printf '  "%s"' "${peer_enodes[$i]}"
            if [ "$i" -lt $((${#peer_enodes[@]} - 1)) ]; then
                printf ',\n'
            else
                printf '\n'
            fi
        done
        echo "]"
    } > "$coregeth_config_file"
    echo -e "${GREEN}✓ CoreGeth config updated${NC}"
    
    # Also update CoreGeth container's internal static-nodes.json
    echo ""
    echo -e "${BLUE}Updating static-nodes.json in CoreGeth container volume...${NC}"
    for container in $containers; do
        local container_type="${CONTAINER_TYPES[$container]}"
        if [ "$container_type" == "geth" ]; then
            echo -n "  $container: "
            local geth_json_content
            geth_json_content=$(build_pretty_json geth_peers)
            if docker exec "$container" sh -c "echo '$geth_json_content' > /root/.ethereum/static-nodes.json" 2>/dev/null; then
                echo -e "${GREEN}✓ updated container volume${NC}"
            else
                echo -e "${YELLOW}⚠ failed to update (container may not support it)${NC}"
            fi
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
    echo -e "  2. Check logs: ${BLUE}fukuii-cli logs cirith-ungol${NC}"
    echo -e "  3. Verify peer connections via RPC"
}

sync_gorgoroth_nodes() {
    local containers="$1"
    local config="$2"
    local network_dir
    network_dir=$(get_network_dir "$config") || exit 1
    
    # Collect enodes from all containers
    echo -e "${BLUE}Collecting enode URLs from all containers...${NC}"
    declare -A ENODES_MAP  # Associate container name with its enode
    declare -A CONTAINER_TYPES  # Track container type for each container
    
    for container in $containers; do
        local container_type=$(get_container_type "$container")
        CONTAINER_TYPES["$container"]="$container_type"
        
        echo -n "  $container ($container_type): "
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
    
    # Build list of all enodes for full mesh connectivity
    local all_enodes=()
    for container in $(echo "${!ENODES_MAP[@]}" | tr ' ' '\n' | sort); do
        all_enodes+=("${ENODES_MAP[$container]}")
    done
    
    # Update static-nodes.json for each container based on client type
    echo ""
    echo -e "${BLUE}Updating static-nodes.json for all containers...${NC}"
    for container in $containers; do
        local container_type="${CONTAINER_TYPES[$container]}"
        local container_enode="${ENODES_MAP[$container]}"
        
        # Skip containers without enodes
        if [ -z "$container_enode" ]; then
            continue
        fi
        
        echo -n "  $container ($container_type): "
        
        # Build peer list (all other containers), forcing the enode host to the
        # target container's network IP for maximum cross-client robustness.
        local peer_enodes=()
        local other
        for other in $containers; do
            if [ "$other" = "$container" ]; then
                continue
            fi

            local other_enode="${ENODES_MAP[$other]}"
            if [ -z "$other_enode" ]; then
                continue
            fi

            local other_ip
            other_ip=$(get_container_network_ip "$other")
            if [ -z "$other_ip" ]; then
                peer_enodes+=("$other_enode")
                continue
            fi

            peer_enodes+=("$(force_enode_host "$other_enode" "$other_ip")")
        done
        
        # Update static-nodes.json based on container type
        case "$container_type" in
            fukuii)
                # Update Fukuii node config file
                local node_num=$(echo "$container" | grep -o "node[0-9]*" | grep -o "[0-9]*")
                local config_file="$network_dir/conf/node${node_num}/static-nodes.json"
                
                if [ -f "$config_file" ]; then
                    # Write all peer enodes
                    printf '[\n' > "$config_file"
                    for i in "${!peer_enodes[@]}"; do
                        printf '  "%s"' "${peer_enodes[$i]}" >> "$config_file"
                        if [ $i -lt $((${#peer_enodes[@]} - 1)) ]; then
                            printf ',\n' >> "$config_file"
                        else
                            printf '\n' >> "$config_file"
                        fi
                    done
                    printf ']\n' >> "$config_file"
                    echo -e "${GREEN}✓ updated host config${NC}"
                else
                    echo -e "${YELLOW}⚠ config file not found: $config_file${NC}"
                fi
                ;;
            geth)
                # Update Geth node's static-nodes.json in container volume
                local static_nodes_content=$(generate_static_nodes_json peer_enodes)
                local node_num=$(echo "$container" | grep -o "node[0-9]*" | grep -o "[0-9]*")
                local toml_file="$network_dir/conf/geth/node${node_num}.toml"
                local msg_parts=()

                if update_geth_config_peers "$toml_file" peer_enodes; then
                    msg_parts+=("${GREEN}host-config${NC}")
                else
                    msg_parts+=("${YELLOW}host-config⚠${NC}")
                fi

                if docker exec "$container" sh -c "echo '$static_nodes_content' > /root/.ethereum/static-nodes.json" 2>/dev/null; then
                    msg_parts+=("${GREEN}container-volume${NC}")
                else
                    msg_parts+=("${RED}container-volume✗${NC}")
                fi

                local msg
                msg=$(IFS=$', '; echo "${msg_parts[*]}")
                echo -e "$msg"
                ;;
            besu)
                # Update Besu node's static-nodes.json in container volume.
                # Besu requires IP addresses (not hostnames) in enode URLs.
                local static_nodes_content
                static_nodes_content=$(generate_static_nodes_json peer_enodes)

                # Write to /opt/besu/data/static-nodes.json inside the container
                if docker exec "$container" sh -c "echo '$static_nodes_content' > /opt/besu/data/static-nodes.json" 2>/dev/null; then
                    echo -e "${GREEN}✓ updated container volume${NC}"
                else
                    echo -e "${RED}✗ failed to update${NC}"
                fi
                ;;
            *)
                echo -e "${YELLOW}⚠ unknown type${NC}"
                ;;
        esac
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
    echo -e "  2. Check logs: ${BLUE}fukuii-cli logs $config${NC}"
    echo -e "  3. Verify peer connections:"
    echo -e "     ${BLUE}curl -X POST --data '{\"jsonrpc\":\"2.0\",\"method\":\"net_peerCount\",\"params\":[],\"id\":1}' http://localhost:8546${NC}"
}

get_container_service_hostname() {
    local container_name=$1
    local service_name

    service_name=$(docker inspect -f '{{index .Config.Labels "com.docker.compose.service"}}' "$container_name" 2>/dev/null || true)
    if [ -n "$service_name" ]; then
        echo "$service_name"
        return 0
    fi

    local alias
    alias=$({ docker inspect -f '{{range $netName, $net := .NetworkSettings.Networks}}{{range $alias := $net.Aliases}}{{printf "%s\n" $alias}}{{end}}{{end}}' "$container_name" 2>/dev/null || true; } | grep -v '_' | head -n1)
    if [ -n "$alias" ]; then
        echo "$alias"
        return 0
    fi

    echo "$container_name"
}

get_container_network_ip() {
    local container_name=$1

    # Return the first non-empty IPv4 address from any attached docker network.
    # Compose attaches these stacks to a single bridge network, so this is stable.
    docker inspect -f '{{range $netName, $net := .NetworkSettings.Networks}}{{if $net.IPAddress}}{{println $net.IPAddress}}{{end}}{{end}}' \
        "$container_name" 2>/dev/null | grep -E '^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$' | head -n1
}

force_enode_host() {
    local enode=$1
    local host=$2

    if [[ -z "$host" ]]; then
        echo "$enode"
        return 0
    fi

    local pattern="^enode://([0-9a-fA-F]{${ENODE_ID_LENGTH}})@(.+):([0-9]+)$"
    if [[ "$enode" =~ $pattern ]]; then
        local node_id="${BASH_REMATCH[1]}"
        local port="${BASH_REMATCH[3]}"
        echo "enode://$node_id@$host:$port"
        return 0
    fi

    echo "$enode"
    return 0
}

format_enode_hostname() {
    local enode=$1
    local hostname=$2

    if [[ -z "$hostname" ]]; then
        echo "$enode"
        return 0
    fi

    local pattern="^enode://([0-9a-fA-F]{${ENODE_ID_LENGTH}})@(.+):([0-9]+)$"
    if [[ "$enode" =~ $pattern ]]; then
        local node_id="${BASH_REMATCH[1]}"
        local host_part="${BASH_REMATCH[2]}"
        local port="${BASH_REMATCH[3]}"

        local normalized_host="$host_part"
        normalized_host="${normalized_host#[}"
        normalized_host="${normalized_host%]}"

        if [[ "$normalized_host" =~ ^(0\.0\.0\.0|127\.0\.0\.1|localhost|::|0:0:0:0:0:0:0:0)$ ]] ||
           [[ "$normalized_host" =~ ^0(:0)*$ ]]; then
            echo "enode://$node_id@$hostname:$port"
            return 0
        fi
    fi

    echo "$enode"
    return 0
}

get_container_host_port() {
    local container_name=$1
    local container_port=$2

    docker inspect -f "{{with (index .NetworkSettings.Ports \"${container_port}/tcp\")}}{{(index . 0).HostPort}}{{end}}" \
        "$container_name" 2>/dev/null || true
}

resolve_rpc_url() {
    local container_name=$1
    local container_type=$2
    local probe_method=${3:-}
    local ports=()
    local fallback_url=""

    case "$container_type" in
        fukuii)
            ports=(8546 8545)
            ;;
        geth|besu)
            ports=(8545 8546)
            ;;
        *)
            ports=(8545 8546)
            ;;
    esac

    local port
    for port in "${ports[@]}"; do
        local host_port
        host_port=$(get_container_host_port "$container_name" "$port")
        if [ -z "$host_port" ]; then
            continue
        fi

        local candidate="http://127.0.0.1:${host_port}"
        if [ -z "$fallback_url" ]; then
            fallback_url="$candidate"
        fi
        if [ -z "$probe_method" ]; then
            echo "$candidate"
            return 0
        fi

        # Nodes may take a few seconds to bring up JSON-RPC after container start.
        local attempt=0
        while [ $attempt -lt 5 ]; do
            local probe_response
            probe_response=$(rpc_call "$candidate" "$probe_method")
            if rpc_response_ok "$probe_response"; then
                echo "$candidate"
                return 0
            fi
            attempt=$((attempt + 1))
            sleep 1
        done
    done

    if [ -n "$fallback_url" ]; then
        echo "$fallback_url"
        return 0
    fi

    return 1
}

rpc_call() {
    local rpc_url=$1
    local method=$2

    curl -s --max-time 5 -X POST -H 'Content-Type: application/json' \
        --data "{\"jsonrpc\":\"2.0\",\"method\":\"${method}\",\"params\":[],\"id\":1}" \
        "$rpc_url" 2>/dev/null || true
}

rpc_call_container() {
    local container_name=$1
    local container_type=$2
    local port=$3
    local method=$4

    if [ -n "$port" ] && docker exec "$container_name" sh -c "command -v curl >/dev/null 2>&1" 2>/dev/null; then
        docker exec "$container_name" sh -c \
            "curl -s --max-time 5 -X POST -H 'Content-Type: application/json' \
            --data '{\"jsonrpc\":\"2.0\",\"method\":\"${method}\",\"params\":[],\"id\":1}' \
            http://localhost:${port}" 2>/dev/null || true
        return 0
    fi

    if [[ "$container_type" == "geth" ]]; then
        local expr=""
        case "$method" in
            eth_syncing)
                expr="JSON.stringify(eth.syncing)"
                ;;
            eth_blockNumber)
                expr="eth.blockNumber"
                ;;
            net_peerCount)
                expr="net.peerCount"
                ;;
            *)
                return 1
                ;;
        esac
                # Update Besu node's static-nodes.json in container volume.
                # Besu requires IP addresses (not hostnames) in enode URLs.
                local besu_peer_enodes=()
                local other
                for other in $containers; do
                    if [ "$other" = "$container" ]; then
                        continue
                    fi
                    local other_enode="${ENODES_MAP[$other]}"
                    if [ -z "$other_enode" ]; then
                        continue
                    fi

                    local other_ip
                    other_ip=$(get_container_network_ip "$other")
                    if [ -z "$other_ip" ]; then
                        echo -e "${YELLOW}⚠ missing container IP for $other; skipping for Besu peers${NC}"
                        continue
                    fi

                    besu_peer_enodes+=("$(force_enode_host "$other_enode" "$other_ip")")
                done

                local static_nodes_content
                static_nodes_content=$(generate_static_nodes_json besu_peer_enodes)

                # Write to /opt/besu/data/static-nodes.json inside the container
                if docker exec "$container" sh -c "echo '$static_nodes_content' > /opt/besu/data/static-nodes.json" 2>/dev/null; then
                    echo -e "${GREEN}✓ updated container volume${NC}"
                else
                    echo -e "${RED}✗ failed to update${NC}"
                fi

        printf '%s' "$attach_out" | python3 - <<'PY'
import json
import sys

raw = sys.stdin.read().strip()
if not raw:
    sys.exit(1)

try:
    value = json.loads(raw)
except Exception:
    value = raw

if isinstance(value, str):
    try:
        value = json.loads(value)
    except Exception:
        pass

print(json.dumps({"jsonrpc": "2.0", "id": 1, "result": value}))
PY
        return 0
    fi

    return 1
}

resolve_container_rpc_port() {
    local container_name=$1
    local container_type=$2
    local probe_method=${3:-}
    local ports=()

    case "$container_type" in
        fukuii)
            ports=(8546 8545)
            ;;
        geth|besu)
            ports=(8545 8546)
            ;;
        *)
            ports=(8545 8546)
            ;;
    esac

    if docker exec "$container_name" sh -c "command -v curl >/dev/null 2>&1" 2>/dev/null; then
        local port
        for port in "${ports[@]}"; do
            local probe_response
            probe_response=$(rpc_call_container "$container_name" "$container_type" "$port" "$probe_method")
            if rpc_response_ok "$probe_response"; then
                echo "$port"
                return 0
            fi
        done
    fi

    if [[ "$container_type" == "geth" ]]; then
        local probe_response
        probe_response=$(rpc_call_container "$container_name" "$container_type" "" "$probe_method")
        if rpc_response_ok "$probe_response"; then
            echo "ipc"
            return 0
        fi
    fi

    return 1
}

json_result_value() {
    python3 -c 'import json,sys
data = sys.stdin.read()
try:
    obj = json.loads(data)
except Exception:
    print("")
    sys.exit(0)
res = obj.get("result")
if isinstance(res, (dict, list)):
    print(json.dumps(res))
elif res is True:
    print("true")
elif res is False:
    print("false")
elif res is None:
    print("")
else:
    print(res)'
}

json_error_message() {
    python3 -c 'import json,sys
data = sys.stdin.read()
try:
    obj = json.loads(data)
except Exception:
    print("")
    sys.exit(0)
err = obj.get("error")
if isinstance(err, dict):
    msg = err.get("message")
    if msg:
        print(msg)
    else:
        print(json.dumps(err))
elif err:
    print(str(err))
else:
    print("")'
}

rpc_response_ok() {
    local response=$1

    if [ -z "$response" ]; then
        return 1
    fi

    local result
    result=$(json_result_value <<< "$response")
    local err
    err=$(json_error_message <<< "$response")

    if [ -n "$result" ] || [ -n "$err" ]; then
        return 0
    fi

    return 1
}

parse_sync_status() {
    python3 -c 'import json,sys
data = sys.stdin.read()
try:
    obj = json.loads(data)
except Exception:
    print("")
    sys.exit(0)
res = obj.get("result")
if res is False:
    print("false")
    sys.exit(0)
if res is True:
    print("true")
    sys.exit(0)
if isinstance(res, dict):
    def to_int(value):
        if isinstance(value, bool) or value is None:
            return ""
        if isinstance(value, int):
            return str(value)
        if isinstance(value, str):
            if value.startswith("0x"):
                try:
                    return str(int(value, 16))
                except Exception:
                    return value
            if value.isdigit():
                return value
            if any(c in "abcdefABCDEF" for c in value):
                try:
                    return str(int(value, 16))
                except Exception:
                    return value
        return ""
    parts = []
    current = to_int(res.get("currentBlock"))
    highest = to_int(res.get("highestBlock"))
    starting = to_int(res.get("startingBlock"))
    if current:
        parts.append(f"current={current}")
    if highest:
        parts.append(f"highest={highest}")
    if starting:
        parts.append(f"starting={starting}")
    if parts:
        print("true (" + " ".join(parts) + ")")
    else:
        print("true")
    sys.exit(0)
print("")'
}

hex_to_dec() {
    local hex_value=$1

    if [ -z "$hex_value" ]; then
        echo ""
        return 0
    fi

    if [[ ! "$hex_value" =~ ^0x[0-9a-fA-F]+$ && ! "$hex_value" =~ ^[0-9a-fA-F]+$ ]]; then
        echo ""
        return 0
    fi

    if [[ "$hex_value" == 0x* ]]; then
        hex_value=${hex_value#0x}
    else
        if [[ ! "$hex_value" =~ [a-fA-F] ]]; then
            if [[ "$hex_value" =~ ^[0-9]+$ ]]; then
                echo "$hex_value"
                return 0
            fi
        fi
    fi

    if [ -z "$hex_value" ]; then
        echo "0"
        return 0
    fi

    printf "%d" "$((16#$hex_value))"
}

get_container_type() {
    local container_name=$1
    
    if [[ "$container_name" == *"fukuii"* ]]; then
        echo "fukuii"
    elif [[ "$container_name" == *"geth"* ]] || [[ "$container_name" == *"coregeth"* ]]; then
        echo "geth"
    elif [[ "$container_name" == *"besu"* ]]; then
        echo "besu"
    else
        echo "unknown"
    fi
}

get_enode_from_logs() {
    local container_name=$1
    local container_type=$(get_container_type "$container_name")
    local log_tail="${FUKUII_LOG_TAIL:-1000}"
    local enode_raw=""

    case "$container_type" in
        fukuii)
            enode_raw=$(docker logs --tail "$log_tail" "$container_name" 2>&1 | \
                sed -n "s/.*Node address: \(enode:\/\/[^[:space:]]*\).*/\1/p" | \
                tail -1 || true)

            if [ -z "$enode_raw" ]; then
                enode_raw=$(docker logs "$container_name" 2>&1 | \
                    sed -n "s/.*Node address: \(enode:\/\/[^[:space:]]*\).*/\1/p" | \
                    tail -1 || true)
            fi
            ;;
        geth)
            enode_raw=$(docker logs --tail "$log_tail" "$container_name" 2>&1 | \
                sed -n "s/.*self=\(enode:\/\/[^[:space:]]*\).*/\1/p" | \
                tail -1 || true)

            if [ -z "$enode_raw" ]; then
                enode_raw=$(docker logs --tail "$log_tail" "$container_name" 2>&1 | \
                    sed -n "s/.*\(enode:\/\/[0-9a-fA-F]\{$ENODE_ID_LENGTH\}[^[:space:]]*\).*/\1/p" | \
                    tail -1 || true)
            fi
            ;;
        besu)
            enode_raw=$(docker logs --tail "$log_tail" "$container_name" 2>&1 | \
                sed -n "s/.*Node address[[:space:]]*\(enode:\/\/[^[:space:]]*\).*/\1/p" | \
                tail -1 || true)

            if [ -z "$enode_raw" ]; then
                enode_raw=$(docker logs --tail "$log_tail" "$container_name" 2>&1 | \
                    sed -n "s/.*Enode URL[[:space:]]*\(enode:\/\/[^[:space:]]*\).*/\1/p" | \
                    tail -1 || true)
            fi

            if [ -z "$enode_raw" ]; then
                enode_raw=$(docker logs --tail "$log_tail" "$container_name" 2>&1 | \
                    sed -n "s/.*\(enode:\/\/[0-9a-fA-F]\{$ENODE_ID_LENGTH\}[^[:space:]]*\).*/\1/p" | \
                    tail -1 || true)
            fi
            ;;
        *)
            return 1
            ;;
    esac

    if [ -z "$enode_raw" ]; then
        return 1
    fi

    local sanitized
    if ! sanitized=$(sanitize_enode_url "$enode_raw"); then
        echo -e "${YELLOW}Warning: Unable to parse enode from logs for $container_name${NC}" >&2
        return 1
    fi
    local hostname
    hostname=$(get_container_service_hostname "$container_name")
    sanitized=$(format_enode_hostname "$sanitized" "$hostname")
    echo "$sanitized"
    return 0
}

get_enode_from_container() {
    local container_name=$1
    local container_type=$(get_container_type "$container_name")
    local max_retries=5
    if [[ "$container_type" == "geth" ]]; then
        max_retries=15
    fi
    local retry=0

    # Deterministic path for geth/coregeth: prefer `geth attach` (doesn't require curl).
    if [[ "$container_type" == "geth" ]]; then
        local attach_enode
        attach_enode=$(docker exec "$container_name" sh -c \
            "geth attach --exec 'admin.nodeInfo.enode' /root/.ethereum/geth.ipc 2>/dev/null || geth attach --exec 'admin.nodeInfo.enode' http://localhost:8545 2>/dev/null" \
            2>/dev/null | tr -d '\\r' | tr -d '\\n' | sed 's/^[[:space:]]*\"//; s/\"[[:space:]]*$//' || true)

        if [ -n "$attach_enode" ]; then
            attach_enode=$(sanitize_enode_url "$attach_enode") || attach_enode=""
        fi

        if [ -n "$attach_enode" ]; then
            local hostname
            hostname=$(get_container_service_hostname "$container_name")
            attach_enode=$(format_enode_hostname "$attach_enode" "$hostname")
            echo "$attach_enode"
            return 0
        fi
    fi
    
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
        # Different clients may use different RPC ports (8545 or 8546)
        local rpc_port="8545"
        if [[ "$container_type" == "fukuii" ]]; then
            rpc_port="8546"
        fi
        
        local rpc_method="admin_nodeInfo"
        if [[ "$container_type" == "fukuii" ]]; then
            rpc_method="net_nodeInfo"
        fi

        if docker exec "$container_name" sh -c "command -v curl >/dev/null 2>&1" 2>/dev/null; then
            enode=$(docker exec "$container_name" sh -c \
                "curl -s -X POST --data '{\"jsonrpc\":\"2.0\",\"method\":\"${rpc_method}\",\"params\":[],\"id\":1}' http://localhost:${rpc_port} | grep -o '\"enode\":\"[^\"]*\"' | cut -d'\"' -f4" \
                2>/dev/null || echo "")
        else
            enode=""
        fi

        # CoreGeth images often don't ship with curl; fall back to geth's own attach.
        # Note: `geth attach` expects the endpoint after flags (not before).
        if [ -z "$enode" ] && [[ "$container_type" == "geth" ]]; then
            enode=$(docker exec "$container_name" sh -c \
                "geth attach --exec 'admin.nodeInfo.enode' /root/.ethereum/geth.ipc 2>/dev/null || geth attach --exec 'admin.nodeInfo.enode' http://localhost:${rpc_port} 2>/dev/null" \
                2>/dev/null | tr -d '\\r' | tr -d '\\n' | sed 's/^[[:space:]]*\"//; s/\"[[:space:]]*$//' || echo "")
        fi

        if [ -n "$enode" ]; then
            enode=$(sanitize_enode_url "$enode") || enode=""
        fi

        if [ -n "$enode" ]; then
            local hostname
            hostname=$(get_container_service_hostname "$container_name")
            enode=$(format_enode_hostname "$enode" "$hostname")
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

smoke_test() {
    local config="${1:-3nodes}"

    if ! command -v curl >/dev/null 2>&1; then
        echo -e "${RED}Error: curl is required for smoke-test${NC}" >&2
        return 1
    fi

    ensure_python3 || return 1

    echo -e "${BLUE}=== Smoke Test ===${NC}"
    echo -e "${BLUE}Configuration: $config${NC}"
    echo ""

    # Determine container name patterns based on configuration
    local container_patterns=()
    if [[ "$config" == "cirith-ungol" ]]; then
        container_patterns+=("fukuii-cirith-ungol" "coregeth-cirith-ungol")
    elif [[ "$config" == "fukuii-geth" ]]; then
        container_patterns+=("gorgoroth-fukuii-" "gorgoroth-geth-")
    elif [[ "$config" == "fukuii-besu" ]]; then
        container_patterns+=("gorgoroth-fukuii-" "gorgoroth-besu-")
    elif [[ "$config" == "mixed" ]]; then
        container_patterns+=("gorgoroth-fukuii-" "gorgoroth-geth-" "gorgoroth-besu-")
    else
        # Default: only Fukuii nodes (3nodes, 6nodes)
        container_patterns+=("gorgoroth-fukuii-")
    fi

    local CONTAINERS=""
    for pattern in "${container_patterns[@]}"; do
        local found_containers
        found_containers=$(docker ps --filter "name=${pattern}" --format "{{.Names}}" | sort || true)
        if [ -n "$found_containers" ]; then
            CONTAINERS="${CONTAINERS}${found_containers}"$'\n'
        fi
    done
    CONTAINERS=$(echo "$CONTAINERS" | grep -v '^$' | sort || true)

    if [ -z "$CONTAINERS" ]; then
        echo -e "${RED}Error: No running containers found for patterns: ${container_patterns[*]}${NC}"
        echo "Start the network first with: fukuii-cli start $config"
        return 1
    fi

    echo -e "${GREEN}Found running containers:${NC}"
    echo "$CONTAINERS" | sed 's/^/  - /'
    echo ""

    for container in $CONTAINERS; do
        local container_type
        container_type=$(get_container_type "$container")

        local rpc_url
        local rpc_source="host"
        local rpc_port=""
        local rpc_label=""
        local block_response=""
        local probe_method="eth_blockNumber"
        rpc_url=$(resolve_rpc_url "$container" "$container_type" "$probe_method") || rpc_url=""

        echo -e "${BLUE}${container}${NC} (${container_type})"
        if [ -n "$rpc_url" ]; then
            block_response=$(rpc_call "$rpc_url" "$probe_method")
            if ! rpc_response_ok "$block_response"; then
                rpc_url=""
            else
                rpc_label="$rpc_url"
            fi
        fi

        if [ -z "$rpc_url" ]; then
            rpc_port=$(resolve_container_rpc_port "$container" "$container_type" "$probe_method") || rpc_port=""
            if [ -n "$rpc_port" ]; then
                rpc_source="container"
                if [ "$rpc_port" = "ipc" ]; then
                    rpc_label="container://$container (geth attach)"
                    rpc_port=""
                else
                    rpc_label="container://$container:$rpc_port"
                fi
                block_response=$(rpc_call_container "$container" "$container_type" "$rpc_port" "$probe_method")
            fi
        fi

        if [ -z "$rpc_label" ]; then
            echo -e "  ${YELLOW}RPC unavailable (no host response or container access)${NC}"
            echo ""
            continue
        fi

        local sync_response
        local peer_response
        if [ "$rpc_source" = "container" ]; then
            sync_response=$(rpc_call_container "$container" "$container_type" "$rpc_port" "eth_syncing")
            peer_response=$(rpc_call_container "$container" "$container_type" "$rpc_port" "net_peerCount")
        else
            sync_response=$(rpc_call "$rpc_url" "eth_syncing")
            peer_response=$(rpc_call "$rpc_url" "net_peerCount")
        fi

        if [ -z "$block_response" ]; then
            if [ "$rpc_source" = "container" ]; then
                block_response=$(rpc_call_container "$container" "$container_type" "$rpc_port" "eth_blockNumber")
            else
                block_response=$(rpc_call "$rpc_url" "eth_blockNumber")
            fi
        fi

        local sync_status
        sync_status=$(parse_sync_status <<< "$sync_response")
        if [ -z "$sync_status" ]; then
            local err_msg
            err_msg=$(json_error_message <<< "$sync_response")
            if [ -n "$err_msg" ]; then
                sync_status="error: $err_msg"
            else
                sync_status="error"
            fi
        fi

        local block_hex
        block_hex=$(json_result_value <<< "$block_response")
        local block_dec
        block_dec=$(hex_to_dec "$block_hex")
        if [ -z "$block_hex" ]; then
            block_hex="unknown"
        fi
        if [ -z "$block_dec" ]; then
            block_dec="unknown"
        fi

        local peer_hex
        peer_hex=$(json_result_value <<< "$peer_response")
        local peer_dec
        peer_dec=$(hex_to_dec "$peer_hex")
        if [ -z "$peer_hex" ]; then
            peer_hex="unknown"
        fi
        if [ -z "$peer_dec" ]; then
            peer_dec="unknown"
        fi

        echo "  rpc:   $rpc_label"
        echo "  sync:  $sync_status"
        echo "  block: $block_dec ($block_hex)"
        echo "  peers: $peer_dec ($peer_hex)"
        echo ""
    done

    echo -e "${GREEN}Smoke test complete${NC}"
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

reset_fast_sync_cmd() {
    local config="${1:-3nodes}"
    local compose_file
    local network_dir

    compose_file=$(get_compose_file "$config") || exit 1
    network_dir=$(get_network_dir "$config") || exit 1

    cd "$network_dir"

    local container_id
    container_id=$(docker compose -f "$compose_file" ps -q fukuii 2>/dev/null || true)
    if [ -z "$container_id" ]; then
        echo -e "${RED}Error: fukuii container not found for config '$config'${NC}" >&2
        return 1
    fi

    local rpc_url
    rpc_url=$(resolve_rpc_url "$container_id" "fukuii" "net_peerCount" 2>/dev/null || true)
    if [ -z "$rpc_url" ]; then
        echo -e "${RED}Error: unable to resolve fukuii RPC URL for config '$config'${NC}" >&2
        return 1
    fi

    echo -e "${YELLOW}Requesting in-process fast sync restart via RPC (${rpc_url})...${NC}"
    local resp
    resp=$(rpc_call "$rpc_url" "fukuii_restartFastSync")
    if ! rpc_response_ok "$resp"; then
        echo -e "${RED}Error: fukuii_restartFastSync RPC failed${NC}" >&2
        echo "$resp" >&2
        return 1
    fi

    echo -e "${GREEN}✓ restart requested${NC}"
    echo "$resp"
}

inject_peers_cmd() {
    local config="${1:-cirith-ungol}"
    shift || true

    ensure_python3 || return 1

    local geth_pattern=""
    local fukuii_pattern=""
    if [[ "$config" == "cirith-ungol" ]]; then
        geth_pattern="coregeth-cirith-ungol"
        fukuii_pattern="fukuii-cirith-ungol"
    elif [[ "$config" == "fukuii-geth" || "$config" == "mixed" ]]; then
        geth_pattern="gorgoroth-geth-"
        fukuii_pattern="gorgoroth-fukuii-"
    else
        echo -e "${RED}Error: inject-peers is supported for configs with a geth/core-geth node (cirith-ungol, fukuii-geth, mixed)${NC}" >&2
        return 1
    fi

    local geth_container
    geth_container=$(docker ps --filter "name=${geth_pattern}" --format "{{.Names}}" | sort | head -n 1 || true)
    if [ -z "$geth_container" ]; then
        echo -e "${RED}Error: no running geth/core-geth container found (pattern: ${geth_pattern})${NC}" >&2
        return 1
    fi

    local fukuii_containers
    fukuii_containers=$(docker ps --filter "name=${fukuii_pattern}" --format "{{.Names}}" | sort || true)
    if [ -z "$fukuii_containers" ]; then
        echo -e "${RED}Error: no running fukuii container(s) found (pattern: ${fukuii_pattern})${NC}" >&2
        return 1
    fi

    local coregeth_url
    coregeth_url=$(resolve_rpc_url "$geth_container" "geth" "net_peerCount" 2>/dev/null || true)
    if [ -z "$coregeth_url" ]; then
        echo -e "${RED}Error: unable to resolve geth/core-geth RPC URL for ${geth_container}${NC}" >&2
        return 1
    fi

    local project_root
    project_root=$(cd "$SCRIPT_DIR/../.." && pwd)
    local inject_script="$project_root/scripts/inject_peers_from_coregeth.py"
    if [ ! -f "$inject_script" ]; then
        echo -e "${RED}Error: inject script not found at $inject_script${NC}" >&2
        return 1
    fi

    local fukuii_container
    for fukuii_container in $fukuii_containers; do
        local fukuii_url
        fukuii_url=$(resolve_rpc_url "$fukuii_container" "fukuii" "net_peerCount" 2>/dev/null || true)
        if [ -z "$fukuii_url" ]; then
            echo -e "${YELLOW}Warning: unable to resolve fukuii RPC URL for ${fukuii_container}; skipping${NC}" >&2
            continue
        fi

        echo -e "${GREEN}Injecting peers: ${geth_container} -> ${fukuii_container}${NC}"
        python3 "$inject_script" --coregeth-url "$coregeth_url" --fukuii-url "$fukuii_url" "$@"
        echo ""
    done
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
    smoke-test)
        smoke_test "$@"
        ;;
    reset-fast-sync)
        reset_fast_sync_cmd "$@"
        ;;
    inject-peers)
        inject_peers_cmd "$@"
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
