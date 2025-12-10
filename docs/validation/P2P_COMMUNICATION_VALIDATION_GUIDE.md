# P2P Communication Validation Guide for ETC64 Removal

## Purpose

This guide provides step-by-step instructions for validating that the ETC64 removal changes are working correctly in the Gorgoroth test network environment, specifically testing peer-to-peer communication and message routing.

## Background

The issue identified was: **"messages being routed to etc63 v. eth64 in the code base"**

After the ETC64 removal, we need to verify:
1. Messages are correctly routed to ETH protocol handlers (not ETC)
2. Capability negotiation works properly (ETH63, ETH64+)
3. P2P communication succeeds between Fukuii nodes
4. Mixed network communication works (Fukuii + Geth/Besu)

## Prerequisites

- Docker and Docker Compose installed
- Fukuii built and available in Docker image
- Access to the Gorgoroth test network configuration

## Validation Test Plan

### Test 1: Three Fukuii Nodes (ETH64+ Communication)

**Objective:** Verify Fukuii-to-Fukuii communication with ETH64+ protocol

```bash
cd ops/gorgoroth

# Start 3 Fukuii nodes
fukuii-cli start 3nodes

# Wait for nodes to start (30 seconds)
sleep 30

# Check peer connections on node1
curl -X POST http://localhost:8545 -H "Content-Type: application/json" \
  --data '{"jsonrpc":"2.0","method":"admin_peers","params":[],"id":1}' \
  | jq '.result[].caps' 

# Expected: Should show "eth/64", "eth/65", or higher - NOT "etc/64"

# Check that nodes are syncing blocks
curl -X POST http://localhost:8545 -H "Content-Type: application/json" \
  --data '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}'

# Collect logs for analysis
fukuii-cli logs collect

# Check logs for protocol negotiation
grep -i "negotiated protocol" logs/fukuii-node1/*.log
grep -i "STATUS_EXCHANGE" logs/fukuii-node1/*.log
grep -i "PEER_CAPABILITIES" logs/fukuii-node1/*.log

# Stop nodes
fukuii-cli stop 3nodes
```

**Success Criteria:**
- ✅ All nodes connect to each other
- ✅ Peer capabilities show "eth/64" or higher (NOT "etc/64")
- ✅ Blocks are being created and synced
- ✅ No "etc63" or "etc64" references in logs
- ✅ STATUS_EXCHANGE logs show ForkId for ETH64+ connections

### Test 2: Mixed Network (Fukuii + Core-Geth)

**Objective:** Verify cross-client communication with ETH protocol

```bash
cd ops/gorgoroth

# Start mixed Fukuii + Geth network
fukuii-cli start fukuii-geth

# Wait for nodes to start
sleep 45

# Check Fukuii node's peers (should include Geth nodes)
curl -X POST http://localhost:8545 -H "Content-Type: application/json" \
  --data '{"jsonrpc":"2.0","method":"admin_peers","params":[],"id":1}' \
  | jq '.result[] | {name: .name, caps: .caps}'

# Check Geth node's peers (should include Fukuii nodes)
curl -X POST http://localhost:8551 -H "Content-Type: application/json" \
  --data '{"jsonrpc":"2.0","method":"admin_peers","params":[],"id":1}' \
  | jq '.result[] | {name: .name, caps: .caps}'

# Check block propagation
for port in 8545 8547 8549 8551 8553 8555; do
  echo "Node on port $port:"
  curl -s -X POST http://localhost:$port -H "Content-Type: application/json" \
    --data '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}' \
    | jq '.result'
done

# Collect and analyze logs
fukuii-cli logs collect
grep -i "negotiated protocol\|capability\|STATUS_EXCHANGE" logs/fukuii-node*/*.log

# Stop network
fukuii-cli stop fukuii-geth
```

**Success Criteria:**
- ✅ Fukuii nodes connect to Geth nodes
- ✅ Protocol negotiation succeeds (likely ETH64 or ETH63 depending on Geth version)
- ✅ No "malformed signature" or "incompatible protocol" errors
- ✅ Block numbers are synchronized across clients
- ✅ ForkId validation succeeds (for ETH64+ connections)

### Test 3: Protocol Downgrade (ETH63 Fallback)

**Objective:** Verify graceful fallback to ETH63 when needed

This test validates that if a peer only supports ETH63, Fukuii correctly:
1. Negotiates down to ETH63
2. Uses BaseETH6XMessages.Status (without ForkId)
3. Still successfully establishes connection

```bash
# Manual test using Docker exec to check logs
cd ops/gorgoroth
fukuii-cli start 3nodes

# Enable verbose logging by modifying conf/fukuii-node1.conf
# Add: logging-level = "TRACE"
# Restart node1

# Watch for capability negotiation in real-time
docker logs -f fukuii-node1 2>&1 | grep -i "capability\|negotiat\|status"

# Look for logs like:
# "Negotiated protocol version with client ... is eth/64"
# or
# "Negotiated protocol version with client ... is eth/63"

fukuii-cli stop 3nodes
```

**Success Criteria:**
- ✅ No crashes during protocol negotiation
- ✅ Appropriate Status message used (ETH64.Status or BaseETH6XMessages.Status)
- ✅ Logs clearly show which protocol version was negotiated
- ✅ Connection succeeds regardless of protocol version (63-68)

### Test 4: Message Type Validation

**Objective:** Verify correct message types are used for each protocol version

```bash
cd ops/gorgoroth

# Start nodes with DEBUG logging enabled
# Edit conf/fukuii-node1.conf to set: logging-level = "DEBUG"
fukuii-cli start 3nodes

# Wait for initial handshakes
sleep 30

# Collect logs
fukuii-cli logs collect

# Analyze message types in logs
echo "=== Checking for ETH64 Status messages ==="
grep "STATUS_EXCHANGE.*protocolVersion=64" logs/fukuii-node*/*.log

echo "=== Checking for ForkId in status exchange ==="
grep "forkId=" logs/fukuii-node*/*.log

echo "=== Verifying no ETC64 references ==="
grep -i "etc64\|etc/64" logs/fukuii-node*/*.log
# Should return no results

echo "=== Checking capability advertisements ==="
grep "PEER_CAPABILITIES" logs/fukuii-node*/*.log

fukuii-cli stop 3nodes
```

**Success Criteria:**
- ✅ STATUS_EXCHANGE logs show ForkId field for ETH64+ connections
- ✅ No references to "etc64" or "etc/64" in logs
- ✅ PEER_CAPABILITIES shows only ETH family capabilities
- ✅ Message routing goes to appropriate decoder (ETH64MessageDecoder, etc.)

### Test 5: RLP Encoding Validation

**Objective:** Verify RLP encoding is correct (no two's complement issues)

```bash
cd ops/gorgoroth

# Enable RLPx frame logging in conf/fukuii-node1.conf:
# Add: verbose-rlpx-logging = true
fukuii-cli start 3nodes

# Wait for handshakes
sleep 30

# Check logs for RLP encoding issues
fukuii-cli logs collect
grep -i "malformed\|cannot decode\|rlp" logs/fukuii-node*/*.log

# Should NOT see errors like:
# - "malformed signature"
# - "Cannot decode Status"
# - "RLP encoding error"

fukuii-cli stop 3nodes
```

**Success Criteria:**
- ✅ No RLP decoding errors
- ✅ No "malformed signature" errors
- ✅ Integer fields properly encoded without leading zeros
- ✅ ForkId properly serialized/deserialized

## Common Issues and Troubleshooting

### Issue: Nodes not connecting

**Symptoms:**
- `admin_peers` returns empty array
- No peer connection logs

**Resolution:**
```bash
# Check if nodes are running
docker ps

# Check network connectivity
docker network inspect gorgoroth_fukuii-network

# Verify enode URLs are correct
cat ops/gorgoroth/enodes.txt

# Check firewall/port mappings
netstat -tulpn | grep -E "8545|30303"
```

### Issue: "Incompatible protocol" errors

**Symptoms:**
- Logs show "DisconnectedState(IncompatibleP2pProtocolVersion)"

**Resolution:**
- This should NOT happen with current code
- Check that all nodes are running latest Fukuii build
- Verify capability negotiation logic in EtcHelloExchangeState

### Issue: Messages routed to wrong decoder

**Symptoms:**
- Decode errors for valid messages
- Wrong message type in logs

**Resolution:**
- Check EthereumMessageDecoder.ethMessageDecoder routing
- Verify Capability.negotiate returns correct value
- Ensure MessageCodec uses correct decoder for negotiated capability

## Automation Script

Create `validate-p2p-routing.sh`:

```bash
#!/bin/bash
set -e

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
cd "$SCRIPT_DIR"

echo "======================================"
echo "ETC64 Removal Validation Test Suite"
echo "======================================"

# Test 1: Basic connectivity
echo -e "\n[TEST 1] Basic Fukuii-to-Fukuii connectivity..."
fukuii-cli start 3nodes
sleep 30

peers=$(curl -s -X POST http://localhost:8545 -H "Content-Type: application/json" \
  --data '{"jsonrpc":"2.0","method":"admin_peers","params":[],"id":1}' \
  | jq -r '.result | length')

if [ "$peers" -ge 2 ]; then
  echo "✅ PASS: Node has $peers peers"
else
  echo "❌ FAIL: Node has only $peers peers (expected >= 2)"
  exit 1
fi

# Check for ETH protocol (not ETC)
eth_caps=$(curl -s -X POST http://localhost:8545 -H "Content-Type: application/json" \
  --data '{"jsonrpc":"2.0","method":"admin_peers","params":[],"id":1}' \
  | jq -r '.result[].caps[]' | grep -c "eth/" || true)

if [ "$eth_caps" -gt 0 ]; then
  echo "✅ PASS: Peers are using ETH protocol"
else
  echo "❌ FAIL: No ETH protocol capabilities found"
  exit 1
fi

# Test 2: Check logs for routing
echo -e "\n[TEST 2] Checking logs for message routing..."
fukuii-cli logs collect

if grep -q "etc64\|etc/64\|ETC64" logs/fukuii-node*/*.log 2>/dev/null; then
  echo "❌ FAIL: Found ETC64 references in logs"
  grep -n "etc64\|etc/64\|ETC64" logs/fukuii-node*/*.log
  exit 1
else
  echo "✅ PASS: No ETC64 references in logs"
fi

if grep -q "STATUS_EXCHANGE.*forkId=" logs/fukuii-node*/*.log 2>/dev/null; then
  echo "✅ PASS: ForkId found in status exchanges (ETH64+ working)"
else
  echo "⚠️  WARN: No ForkId in status exchanges (possibly ETH63)"
fi

# Cleanup
fukuii-cli stop 3nodes

echo -e "\n======================================"
echo "✅ All validation tests passed!"
echo "======================================"
```

## Expected Log Patterns

### Successful ETH64+ Connection:
```
[INFO] PEER_CAPABILITIES: clientId=fukuii, p2pVersion=5, capabilities=[eth/64, eth/65, eth/66, snap/1]
[INFO] Negotiated protocol version with client fukuii is eth/64
[INFO] STATUS_EXCHANGE: Sending status - protocolVersion=64, networkId=1337, forkId=ForkId(...)
[INFO] STATUS_EXCHANGE: Received status from peer - protocolVersion=64, forkId=ForkId(...)
[INFO] STATUS_EXCHANGE: ForkId validation passed
```

### Successful ETH63 Fallback:
```
[INFO] PEER_CAPABILITIES: clientId=geth, p2pVersion=5, capabilities=[eth/63, eth/64]
[INFO] Negotiated protocol version with client geth is eth/63
[DEBUG] sending status Status { protocolVersion: 63, networkId: 1337, ... }
```

### ❌ FAILURE - Should NOT see:
```
[ERROR] malformed signature
[ERROR] Cannot decode Status
[ERROR] Negotiated protocol version with client ... is etc/64
[ERROR] Unknown etc/63 message type
```

## Validation Checklist

After running all tests, verify:

- [ ] No "etc64" or "etc/64" strings in active logs
- [ ] Capability negotiation uses ETH protocol family
- [ ] ForkId present in ETH64+ status exchanges
- [ ] ForkId absent in ETH63 status exchanges
- [ ] Cross-client communication works (Fukuii + Geth/Besu)
- [ ] No RLP encoding errors
- [ ] No malformed signature errors
- [ ] Block propagation working across all nodes
- [ ] Message routing uses correct decoder (ETH64MessageDecoder, etc.)
- [ ] Graceful protocol downgrade to ETH63 when needed

## Summary

This validation guide ensures the ETC64 removal changes work correctly in real-world P2P scenarios. The tests cover:

1. **Protocol Negotiation** - ETH family only, no ETC
2. **Message Routing** - Correct decoder selection
3. **Cross-Client Compatibility** - Works with Geth, Besu
4. **RLP Encoding** - No encoding issues
5. **Fallback Behavior** - Graceful ETH63 fallback

Run these tests before deploying to production networks to ensure robustness and compatibility.
