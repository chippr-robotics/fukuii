#!/usr/bin/env bash
# Initialize Docker volumes with static-nodes.json files
# This script pre-populates the named volumes with peer configuration
# so that nodes can connect on first run without manual intervention.

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Color output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

echo -e "${BLUE}=== Gorgoroth Volume Initialization ===${NC}"
echo ""
echo "This script pre-populates Docker volumes with static-nodes.json files"
echo "so that nodes can connect to peers on first startup."
echo ""

# Get configuration (3nodes or 6nodes)
CONFIG="${1:-3nodes}"
COMPOSE_FILE=""

case "$CONFIG" in
    3nodes)
        COMPOSE_FILE="docker-compose-3nodes.yml"
        NODES=(node1 node2 node3)
        ;;
    6nodes)
        COMPOSE_FILE="docker-compose-6nodes.yml"
        NODES=(node1 node2 node3 node4 node5 node6)
        ;;
    *)
        echo -e "${RED}Error: Unknown configuration '$CONFIG'${NC}"
        echo "Usage: $0 [3nodes|6nodes]"
        exit 1
        ;;
esac

echo -e "${GREEN}Configuration: $CONFIG${NC}"
echo -e "${GREEN}Compose file: $COMPOSE_FILE${NC}"
echo ""

# Check if volumes already exist
echo -e "${BLUE}Checking for existing volumes...${NC}"
VOLUMES_EXIST=false
for node in "${NODES[@]}"; do
    volume_name="gorgoroth_fukuii-${node}-data"
    if docker volume inspect "$volume_name" >/dev/null 2>&1; then
        echo -e "  ${YELLOW}⚠${NC} Volume $volume_name already exists"
        VOLUMES_EXIST=true
    fi
done

if [ "$VOLUMES_EXIST" = true ]; then
    echo ""
    echo -e "${YELLOW}Warning: Some volumes already exist.${NC}"
    echo "This script only initializes volumes on first run."
    echo ""
    echo "Options:"
    echo "  1. If you want to reinitialize, run: fukuii-cli clean $CONFIG"
    echo "  2. To update existing running nodes, use: fukuii-cli sync-static-nodes"
    echo ""
    read -p "Do you want to continue and update existing volumes? [y/N] " -n 1 -r
    echo ""
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        echo "Cancelled."
        exit 0
    fi
fi

echo ""
echo -e "${BLUE}Initializing volumes with static-nodes.json files...${NC}"

# Create volumes and copy static-nodes.json to each
for node in "${NODES[@]}"; do
    volume_name="gorgoroth_fukuii-${node}-data"
    config_file="$SCRIPT_DIR/conf/${node}/static-nodes.json"
    
    echo -n "  ${node}: "
    
    # Verify config file exists
    if [ ! -f "$config_file" ]; then
        echo -e "${RED}✗ Config file not found: $config_file${NC}"
        continue
    fi
    
    # Create volume if it doesn't exist
    if ! docker volume inspect "$volume_name" >/dev/null 2>&1; then
        docker volume create "$volume_name" >/dev/null
    fi
    
    # Copy static-nodes.json to volume using a temporary container
    docker run --rm \
        -v "$volume_name:/data" \
        -v "$config_file:/host/static-nodes.json:ro" \
        busybox \
        cp /host/static-nodes.json /data/static-nodes.json
    
    # Verify the file was copied
    file_size=$(docker run --rm -v "$volume_name:/data" busybox stat -c %s /data/static-nodes.json)
    
    if [ "$file_size" -gt 10 ]; then
        echo -e "${GREEN}✓ Initialized ($file_size bytes)${NC}"
    else
        echo -e "${YELLOW}⚠ Warning: File size is only $file_size bytes${NC}"
    fi
done

echo ""
echo -e "${GREEN}=== Volume initialization complete ===${NC}"
echo ""
echo "Next steps:"
echo "  1. Start the network: fukuii-cli start $CONFIG"
echo "  2. Wait for nodes to initialize (~45 seconds)"
echo "  3. Verify peer connectivity: curl -X POST -H 'Content-Type: application/json' \\"
echo "       --data '{\"jsonrpc\":\"2.0\",\"method\":\"net_peerCount\",\"params\":[],\"id\":1}' \\"
echo "       http://localhost:8546"
echo ""
echo "Expected result: Nodes should connect automatically with 0x2 peers (3-node) or 0x5 peers (6-node)"
