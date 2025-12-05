#!/usr/bin/env bash
# Gorgoroth Internal Test Network Log Collection Script
# Collects logs from all running containers in the network

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Color output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Available configurations
CONFIGS=(
    "3nodes:docker-compose-3nodes.yml"
    "6nodes:docker-compose-6nodes.yml"
    "fukuii-geth:docker-compose-fukuii-geth.yml"
    "fukuii-besu:docker-compose-fukuii-besu.yml"
    "mixed:docker-compose-mixed.yml"
)

print_usage() {
    echo "Usage: $0 [config] [output-dir]"
    echo ""
    echo "Arguments:"
    echo "  config      - Configuration name (default: 3nodes)"
    echo "  output-dir  - Directory to save logs (default: ./logs-TIMESTAMP)"
    echo ""
    echo "Available configurations: 3nodes, 6nodes, fukuii-geth, fukuii-besu, mixed"
    echo ""
    echo "Examples:"
    echo "  $0 3nodes"
    echo "  $0 fukuii-geth ./my-logs"
    echo "  $0 mixed"
}

get_compose_file() {
    local config_name="${1:-3nodes}"
    for config in "${CONFIGS[@]}"; do
        IFS=: read -r name file <<< "$config"
        if [[ "$name" == "$config_name" ]]; then
            echo "$file"
            return 0
        fi
    done
    echo "Error: Unknown configuration '$config_name'" >&2
    return 1
}

collect_logs() {
    local config="${1:-3nodes}"
    local timestamp=$(date +%Y%m%d-%H%M%S)
    local output_dir="${2:-./logs-$timestamp}"
    
    local compose_file
    compose_file=$(get_compose_file "$config") || exit 1
    
    echo -e "${GREEN}Collecting logs from Gorgoroth network: $config${NC}"
    echo "Output directory: $output_dir"
    
    # Create output directory
    mkdir -p "$output_dir"
    
    # Get list of running containers
    local containers
    containers=$(docker compose -f "$compose_file" ps -q)
    
    if [[ -z "$containers" ]]; then
        echo -e "${YELLOW}Warning: No running containers found for configuration: $config${NC}"
        exit 1
    fi
    
    # Collect logs from each container
    for container_id in $containers; do
        local container_name
        container_name=$(docker inspect --format='{{.Name}}' "$container_id" | sed 's/^\///')
        
        echo "Collecting logs from: $container_name"
        
        # Save container logs
        docker logs "$container_id" > "$output_dir/${container_name}.log" 2>&1
        
        # Save container inspect info
        docker inspect "$container_id" > "$output_dir/${container_name}-inspect.json"
    done
    
    # Save network information
    echo "Collecting network information..."
    docker compose -f "$compose_file" ps > "$output_dir/containers-status.txt"
    
    # Save docker compose config
    docker compose -f "$compose_file" config > "$output_dir/docker-compose-config.yml"
    
    # Create summary file
    cat > "$output_dir/README.txt" << EOF
Gorgoroth Test Network Log Collection
======================================

Configuration: $config
Timestamp: $timestamp
Compose File: $compose_file

Files in this directory:
- *.log: Container logs
- *-inspect.json: Container inspection data
- containers-status.txt: Container status at collection time
- docker-compose-config.yml: Resolved docker-compose configuration

Collected from containers:
EOF
    
    for container_id in $containers; do
        local container_name
        container_name=$(docker inspect --format='{{.Name}}' "$container_id" | sed 's/^\///')
        echo "  - $container_name" >> "$output_dir/README.txt"
    done
    
    echo -e "${GREEN}Log collection complete!${NC}"
    echo "Logs saved to: $output_dir"
    echo ""
    echo "Files collected:"
    ls -lh "$output_dir"
}

# Main script logic
CONFIG=${1:-3nodes}
OUTPUT_DIR=${2:-}

if [[ "$CONFIG" == "-h" ]] || [[ "$CONFIG" == "--help" ]]; then
    print_usage
    exit 0
fi

collect_logs "$CONFIG" "$OUTPUT_DIR"
