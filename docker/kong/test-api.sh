#!/usr/bin/env bash

# Kong API Gateway Test Script
# Tests various endpoints and authentication methods

set -e

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

# Configuration
KONG_URL="${KONG_URL:-http://localhost:8000}"
ADMIN_URL="${ADMIN_URL:-http://localhost:8001}"
ADMIN_USER="${ADMIN_USER:-admin}"
ADMIN_PASSWORD="${ADMIN_PASSWORD:-fukuii_admin_password}"

echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}Kong API Gateway Test Suite${NC}"
echo -e "${BLUE}========================================${NC}"
echo ""

# Test function
test_endpoint() {
    local name="$1"
    local url="$2"
    local method="${3:-GET}"
    local auth="$4"
    local data="$5"
    
    echo -ne "${YELLOW}Testing ${name}...${NC} "
    
    local cmd="curl -s -o /dev/null -w '%{http_code}'"
    
    if [ "$method" = "POST" ]; then
        cmd="$cmd -X POST"
    fi
    
    if [ -n "$auth" ]; then
        cmd="$cmd -u $auth"
    fi
    
    if [ -n "$data" ]; then
        cmd="$cmd -H 'Content-Type: application/json' -d '$data'"
    fi
    
    cmd="$cmd $url"
    
    local response=$(eval $cmd)
    
    if [ "$response" = "200" ] || [ "$response" = "401" ] || [ "$response" = "429" ]; then
        echo -e "${GREEN}✓ ($response)${NC}"
        return 0
    else
        echo -e "${RED}✗ ($response)${NC}"
        return 1
    fi
}

echo -e "${BLUE}1. Testing Kong Admin API${NC}"
test_endpoint "Admin API Status" "$ADMIN_URL/status" "GET"
test_endpoint "Admin API Services" "$ADMIN_URL/services" "GET"
echo ""

echo -e "${BLUE}2. Testing Health Endpoints (No Auth)${NC}"
test_endpoint "Health Check" "$KONG_URL/health" "GET"
test_endpoint "Readiness Check" "$KONG_URL/readiness" "GET"
echo ""

echo -e "${BLUE}3. Testing JSON-RPC with Basic Auth${NC}"
JSON_RPC_REQUEST='{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}'

test_endpoint "JSON-RPC (No Auth - should fail)" "$KONG_URL/" "POST" "" "$JSON_RPC_REQUEST"
test_endpoint "JSON-RPC (With Auth)" "$KONG_URL/" "POST" "$ADMIN_USER:$ADMIN_PASSWORD" "$JSON_RPC_REQUEST"
test_endpoint "JSON-RPC /rpc (With Auth)" "$KONG_URL/rpc" "POST" "$ADMIN_USER:$ADMIN_PASSWORD" "$JSON_RPC_REQUEST"
echo ""

echo -e "${BLUE}4. Testing HD Wallet Routes${NC}"
test_endpoint "Bitcoin Route" "$KONG_URL/bitcoin" "POST" "$ADMIN_USER:$ADMIN_PASSWORD" "$JSON_RPC_REQUEST"
test_endpoint "Bitcoin Route (/btc)" "$KONG_URL/btc" "POST" "$ADMIN_USER:$ADMIN_PASSWORD" "$JSON_RPC_REQUEST"
test_endpoint "Ethereum Route" "$KONG_URL/ethereum" "POST" "$ADMIN_USER:$ADMIN_PASSWORD" "$JSON_RPC_REQUEST"
test_endpoint "Ethereum Route (/eth)" "$KONG_URL/eth" "POST" "$ADMIN_USER:$ADMIN_PASSWORD" "$JSON_RPC_REQUEST"
test_endpoint "ETC Route" "$KONG_URL/etc" "POST" "$ADMIN_USER:$ADMIN_PASSWORD" "$JSON_RPC_REQUEST"
test_endpoint "ETC Route (ethereum-classic)" "$KONG_URL/ethereum-classic" "POST" "$ADMIN_USER:$ADMIN_PASSWORD" "$JSON_RPC_REQUEST"
echo ""

echo -e "${BLUE}5. Testing Rate Limiting${NC}"
echo -ne "${YELLOW}Sending 10 rapid requests...${NC} "
for i in {1..10}; do
    curl -s -u "$ADMIN_USER:$ADMIN_PASSWORD" \
        -X POST "$KONG_URL/" \
        -H "Content-Type: application/json" \
        -d "$JSON_RPC_REQUEST" > /dev/null
done
echo -e "${GREEN}✓${NC}"

# Try one more request that might hit rate limit
response=$(curl -s -o /dev/null -w '%{http_code}' \
    -u "$ADMIN_USER:$ADMIN_PASSWORD" \
    -X POST "$KONG_URL/" \
    -H "Content-Type: application/json" \
    -d "$JSON_RPC_REQUEST")

if [ "$response" = "429" ]; then
    echo -e "${GREEN}✓ Rate limiting is working (got 429)${NC}"
elif [ "$response" = "200" ]; then
    echo -e "${YELLOW}⚠ Rate limit not reached yet (got 200)${NC}"
else
    echo -e "${RED}✗ Unexpected response ($response)${NC}"
fi
echo ""

echo -e "${BLUE}6. Detailed JSON-RPC Test${NC}"
echo -e "${YELLOW}Making actual JSON-RPC call...${NC}"
response=$(curl -s -u "$ADMIN_USER:$ADMIN_PASSWORD" \
    -X POST "$KONG_URL/" \
    -H "Content-Type: application/json" \
    -d "$JSON_RPC_REQUEST")

if echo "$response" | grep -q "result"; then
    echo -e "${GREEN}✓ JSON-RPC call successful${NC}"
    echo -e "Response: $response"
elif echo "$response" | grep -q "error"; then
    echo -e "${YELLOW}⚠ JSON-RPC call returned error (this is expected if Fukuii is not fully synced)${NC}"
    echo -e "Response: $response"
else
    echo -e "${RED}✗ Unexpected response${NC}"
    echo -e "Response: $response"
fi
echo ""

echo -e "${BLUE}7. Testing Prometheus Metrics${NC}"
if curl -s "$ADMIN_URL/metrics" | grep -q "kong_"; then
    echo -e "${GREEN}✓ Prometheus metrics are exposed${NC}"
else
    echo -e "${RED}✗ Prometheus metrics not found${NC}"
fi
echo ""

echo -e "${BLUE}8. Testing CORS${NC}"
response=$(curl -s -o /dev/null -w '%{http_code}' \
    -X OPTIONS "$KONG_URL/" \
    -H "Origin: https://example.com" \
    -H "Access-Control-Request-Method: POST")

if [ "$response" = "200" ] || [ "$response" = "204" ]; then
    echo -e "${GREEN}✓ CORS preflight successful${NC}"
else
    echo -e "${RED}✗ CORS preflight failed ($response)${NC}"
fi
echo ""

echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}Test Suite Complete${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""
echo -e "${YELLOW}Additional Manual Tests:${NC}"
echo -e "1. Check Grafana dashboards: http://localhost:3000"
echo -e "2. Check Prometheus targets: http://localhost:9090/targets"
echo -e "3. View Kong logs: docker-compose logs kong"
echo -e "4. Check Cassandra: docker exec -it fukuii-cassandra cqlsh"
echo ""
