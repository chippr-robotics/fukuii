#!/bin/bash
# Local test for the Fukuii Hive adapter
# Tests the adapter without needing the full hive framework
#
# Usage: ./test-local.sh
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ADAPTER_DIR="$SCRIPT_DIR/fukuii"

echo "========================================="
echo "  Fukuii Hive Adapter Local Test"
echo "========================================="
echo ""

# 1. Build the adapter image
echo "--- Building adapter image ---"
docker build -t fukuii-hive:test -f "$ADAPTER_DIR/Dockerfile" "$ADAPTER_DIR"
echo "Build successful"
echo ""

# 2. Create a test genesis (simple geth-format)
TMPDIR=$(mktemp -d)
cat > "$TMPDIR/genesis.json" << 'GENESIS'
{
  "config": {
    "chainId": 7,
    "homesteadBlock": 0,
    "eip150Block": 0,
    "eip155Block": 0,
    "eip158Block": 0,
    "byzantiumBlock": 0,
    "constantinopleBlock": 0,
    "petersburgBlock": 0,
    "istanbulBlock": 0,
    "berlinBlock": 0,
    "londonBlock": 0,
    "terminalTotalDifficulty": 0
  },
  "difficulty": "0x20000",
  "gasLimit": "0x1c9c380",
  "nonce": "0x0000000000000000",
  "timestamp": "0x0",
  "extraData": "0x",
  "alloc": {
    "0000000000000000000000000000000000000001": {
      "balance": "0x1"
    }
  }
}
GENESIS
echo "--- Test genesis created ---"
echo ""

# 3. Run the adapter with Engine API enabled
echo "--- Starting Fukuii in hive mode ---"
CONTAINER_ID=$(docker run -d --rm \
  -e HIVE_LOGLEVEL=3 \
  -e HIVE_NETWORK_ID=7 \
  -e HIVE_CHAIN_ID=7 \
  -e HIVE_TERMINAL_TOTAL_DIFFICULTY=0 \
  -e HIVE_FORK_HOMESTEAD=0 \
  -e HIVE_FORK_TANGERINE=0 \
  -e HIVE_FORK_SPURIOUS=0 \
  -e HIVE_FORK_BYZANTIUM=0 \
  -e HIVE_FORK_CONSTANTINOPLE=0 \
  -e HIVE_FORK_PETERSBURG=0 \
  -e HIVE_FORK_ISTANBUL=0 \
  -e HIVE_FORK_BERLIN=0 \
  -e HIVE_FORK_LONDON=0 \
  -v "$TMPDIR/genesis.json:/genesis.json:ro" \
  -p 18545:8545 \
  -p 18551:8551 \
  fukuii-hive:test)

echo "Container: $CONTAINER_ID"
echo ""

# 4. Wait for readiness (TCP 8545)
echo "--- Waiting for readiness (TCP 8545) ---"
for i in $(seq 1 60); do
  if docker exec "$CONTAINER_ID" curl -s -f http://localhost:8545/health > /dev/null 2>&1; then
    echo "Ready after ${i}s"
    break
  fi
  if [ "$i" -eq 60 ]; then
    echo "TIMEOUT after 60s"
    echo "--- Container logs ---"
    docker logs "$CONTAINER_ID" 2>&1 | tail -30
    docker stop "$CONTAINER_ID" 2>/dev/null
    rm -rf "$TMPDIR"
    exit 1
  fi
  sleep 1
done
echo ""

# 5. Test JSON-RPC
echo "--- Testing JSON-RPC ---"
echo -n "eth_blockNumber: "
curl -s -X POST http://localhost:18545 -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}'
echo ""

echo -n "eth_chainId: "
curl -s -X POST http://localhost:18545 -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"eth_chainId","params":[],"id":2}'
echo ""

echo -n "web3_clientVersion: "
curl -s -X POST http://localhost:18545 -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"web3_clientVersion","params":[],"id":3}'
echo ""

# 6. Test Engine API (if TTD was set)
echo ""
echo "--- Testing Engine API ---"
JWT="7365637265747365637265747365637265747365637265747365637265747365"
TOKEN=$(python3 -c "
import hmac,hashlib,base64,json,time
secret=bytes.fromhex('$JWT')
h=base64.urlsafe_b64encode(json.dumps({'alg':'HS256','typ':'JWT'}).encode()).rstrip(b'=').decode()
p=base64.urlsafe_b64encode(json.dumps({'iat':int(time.time())}).encode()).rstrip(b'=').decode()
m=f'{h}.{p}'
s=base64.urlsafe_b64encode(hmac.new(secret,m.encode(),hashlib.sha256).digest()).rstrip(b'=').decode()
print(f'{m}.{s}')
" 2>/dev/null)

echo -n "engine_exchangeCapabilities: "
curl -s -X POST http://localhost:18551 -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"jsonrpc":"2.0","method":"engine_exchangeCapabilities","params":[[]],"id":1}'
echo ""

# 7. Test enode.sh
echo ""
echo "--- Testing enode.sh ---"
docker exec "$CONTAINER_ID" /hive-bin/enode.sh 2>/dev/null || echo "(enode.sh failed — expected if admin_nodeInfo not available)"
echo ""

# 8. Cleanup
echo "--- Cleanup ---"
docker stop "$CONTAINER_ID" 2>/dev/null
rm -rf "$TMPDIR"
echo "Done"
