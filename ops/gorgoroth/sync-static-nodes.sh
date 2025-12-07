#!/usr/bin/env bash
# Script to synchronize static-nodes.json across all running Fukuii containers
# This script:
# 1. Collects enode URLs from all running containers
# 2. Consolidates them into a single static-nodes.json file
# 3. Copies the file back to each container
# 4. Restarts containers to apply the new static peers configuration

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Color output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Temporary file for consolidated static nodes
TEMP_STATIC_NODES=$(mktemp)
trap "rm -f $TEMP_STATIC_NODES" EXIT

echo -e "${BLUE}=== Fukuii Static Nodes Synchronization ===${NC}"
echo ""

# Function to get enode from a container
get_enode_from_container() {
    local container_name=$1
    local max_retries=5
    local retry=0
    
    while [ $retry -lt $max_retries ]; do
        # Try to get enode via RPC
        local enode=$(docker exec "$container_name" curl -s -X POST \
            --data '{"jsonrpc":"2.0","method":"admin_nodeInfo","params":[],"id":1}' \
            http://localhost:8546 2>/dev/null | \
            grep -o '"enode":"[^"]*"' | \
            cut -d'"' -f4 || echo "")
        
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

# Find all running Fukuii containers
CONTAINERS=$(docker ps --filter "name=gorgoroth-fukuii-" --format "{{.Names}}" | sort)

if [ -z "$CONTAINERS" ]; then
    echo -e "${RED}Error: No running Gorgoroth Fukuii containers found${NC}"
    echo "Start the network first with: ./deploy.sh start [config]"
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

# Create static-nodes.json
echo "[" > "$TEMP_STATIC_NODES"
for i in "${!ENODES[@]}"; do
    if [ $i -eq $((${#ENODES[@]} - 1)) ]; then
        echo "  \"${ENODES[$i]}\"" >> "$TEMP_STATIC_NODES"
    else
        echo "  \"${ENODES[$i]}\"," >> "$TEMP_STATIC_NODES"
    fi
done
echo "]" >> "$TEMP_STATIC_NODES"

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
echo -e "  2. Check logs: ${BLUE}./deploy.sh logs [config]${NC}"
echo -e "  3. Verify peer connections via RPC:"
echo -e "     ${BLUE}curl -X POST --data '{\"jsonrpc\":\"2.0\",\"method\":\"net_peerCount\",\"params\":[],\"id\":1}' http://localhost:8546${NC}"
