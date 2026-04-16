#!/bin/bash
# Test script for faucet functionality validation
# Tests that the faucet service can distribute testnet tokens

set -e

echo "=== Faucet Validation Test ==="
echo "Testing faucet service for testnet token distribution..."
echo ""

# Configuration
FAUCET_PORT=${FAUCET_PORT:-8099}
FAUCET_URL="http://localhost:${FAUCET_PORT}"
NODE_PORT=${NODE_PORT:-8546}  # Fukuii HTTP RPC port
NODE_URL="http://localhost:${NODE_PORT}"

# Check if faucet is running
echo "--- Test 1: Faucet Availability ---"
if curl -s -f -m 2 "$FAUCET_URL" > /dev/null 2>&1; then
  echo "✅ Faucet service is accessible at $FAUCET_URL"
else
  echo "❌ FAIL: Faucet service is not accessible at $FAUCET_URL"
  echo ""
  echo "To start the faucet service:"
  echo "  1. Ensure a Fukuii node is running"
  echo "  2. Configure faucet.conf with wallet details"
  echo "  3. Run: ./bin/fukuii faucet"
  exit 1
fi

echo ""

# Test 2: Check faucet status
echo "--- Test 2: Faucet Status ---"
STATUS_RESPONSE=$(curl -s -X POST -H "Content-Type: application/json" \
  --data '{"jsonrpc":"2.0","method":"faucet_status","params":[],"id":1}' \
  "$FAUCET_URL" || echo "error")

if [ "$STATUS_RESPONSE" != "error" ]; then
  echo "Response: $STATUS_RESPONSE"
  
  # Check if response contains success indicator
  if echo "$STATUS_RESPONSE" | grep -q "result"; then
    echo "✅ Faucet status endpoint responding"
    
    # Parse status
    STATUS=$(echo "$STATUS_RESPONSE" | jq -r '.result.status' 2>/dev/null || echo "unknown")
    echo "Faucet status: $STATUS"
    
    if [ "$STATUS" = "WalletAvailable" ]; then
      echo "✅ Faucet wallet is available"
    else
      echo "⚠️  WARNING: Faucet wallet may not be initialized (status: $STATUS)"
    fi
  else
    echo "⚠️  WARNING: Unexpected response format"
  fi
else
  echo "❌ FAIL: Could not get faucet status"
  exit 1
fi

echo ""

# Test 3: Validate RPC methods are available
echo "--- Test 3: RPC Method Availability ---"

# Check if faucet methods are listed in supported methods (if endpoint exists)
METHODS_RESPONSE=$(curl -s -X POST -H "Content-Type: application/json" \
  --data '{"jsonrpc":"2.0","method":"rpc_modules","params":[],"id":1}' \
  "$FAUCET_URL" 2>/dev/null || echo "")

if [ -n "$METHODS_RESPONSE" ] && echo "$METHODS_RESPONSE" | grep -q "faucet"; then
  echo "✅ Faucet RPC methods are registered"
else
  echo "⚠️  Note: Could not verify RPC modules (endpoint may not be available)"
fi

echo ""

# Test 4: Send funds test (requires recipient address)
echo "--- Test 4: Send Funds Functionality ---"

# Use a test recipient address (one of the genesis addresses)
TEST_RECIPIENT="0x2000000000000000000000000000000000000002"

echo "Testing fund distribution to address: $TEST_RECIPIENT"

# Get initial balance of recipient (from connected node)
INITIAL_BALANCE=$(curl -s -X POST -H "Content-Type: application/json" \
  --data "{\"jsonrpc\":\"2.0\",\"method\":\"eth_getBalance\",\"params\":[\"$TEST_RECIPIENT\",\"latest\"],\"id\":1}" \
  "$NODE_URL" 2>/dev/null | jq -r '.result' 2>/dev/null || echo "0x0")

echo "Initial balance: $INITIAL_BALANCE"

# Request funds from faucet
SEND_RESPONSE=$(curl -s -X POST -H "Content-Type: application/json" \
  --data "{\"jsonrpc\":\"2.0\",\"method\":\"faucet_sendFunds\",\"params\":[{\"address\":\"$TEST_RECIPIENT\"}],\"id\":1}" \
  "$FAUCET_URL" 2>/dev/null || echo "error")

if [ "$SEND_RESPONSE" != "error" ]; then
  echo "Response: $SEND_RESPONSE"
  
  # Check for transaction hash in response
  TX_HASH=$(echo "$SEND_RESPONSE" | jq -r '.result.txId' 2>/dev/null || echo "null")
  
  # Validate TX_HASH is in the expected format (0x followed by hex characters)
  if [ "$TX_HASH" != "null" ] && [ -n "$TX_HASH" ] && [[ "$TX_HASH" =~ ^0x[0-9a-fA-F]{64}$ ]]; then
    echo "✅ Faucet sent funds"
    echo "Transaction hash: $TX_HASH"
    
    # Wait for transaction to be mined
    echo "Waiting 30 seconds for transaction to be mined..."
    sleep 30
    
    # Check transaction receipt using jq to safely construct JSON
    TX_RECEIPT=$(curl -s -X POST -H "Content-Type: application/json" \
      --data "$(jq -n --arg tx "$TX_HASH" '{jsonrpc:"2.0",method:"eth_getTransactionReceipt",params:[$tx],id:1}')" \
      "$NODE_URL" | jq -r '.result' 2>/dev/null || echo "null")
    
    if [ "$TX_RECEIPT" != "null" ]; then
      echo "✅ Transaction was mined"
      
      # Verify balance increased
      NEW_BALANCE=$(curl -s -X POST -H "Content-Type: application/json" \
        --data "{\"jsonrpc\":\"2.0\",\"method\":\"eth_getBalance\",\"params\":[\"$TEST_RECIPIENT\",\"latest\"],\"id\":1}" \
        "$NODE_URL" | jq -r '.result' 2>/dev/null || echo "0x0")
      
      echo "New balance: $NEW_BALANCE"
      
      # Convert hex to decimal for comparison
      INITIAL_DEC=$((16#${INITIAL_BALANCE#0x}))
      NEW_DEC=$((16#${NEW_BALANCE#0x}))
      
      if [ $NEW_DEC -gt $INITIAL_DEC ]; then
        echo "✅ Balance increased after faucet transaction"
      else
        echo "⚠️  WARNING: Balance did not increase as expected"
      fi
    else
      echo "⚠️  WARNING: Transaction not yet mined (may need more time)"
    fi
  else
    # Check for error message
    ERROR=$(echo "$SEND_RESPONSE" | jq -r '.error.message' 2>/dev/null || echo "")
    if [ -n "$ERROR" ]; then
      echo "⚠️  Faucet returned error: $ERROR"
      
      if echo "$ERROR" | grep -q "unavailable"; then
        echo "    This likely means the faucet wallet is not initialized"
        echo "    Check faucet configuration and ensure wallet is unlocked"
      fi
    else
      echo "⚠️  WARNING: Unexpected response format"
    fi
  fi
else
  echo "❌ FAIL: Could not send funds request to faucet"
  exit 1
fi

echo ""

# Test 5: Rate limiting test (optional)
echo "--- Test 5: Rate Limiting (Optional) ---"

# Try sending funds twice in quick succession
echo "Testing rate limiting by making consecutive requests..."

RESPONSE1=$(curl -s -X POST -H "Content-Type: application/json" \
  --data "{\"jsonrpc\":\"2.0\",\"method\":\"faucet_sendFunds\",\"params\":[{\"address\":\"$TEST_RECIPIENT\"}],\"id\":1}" \
  "$FAUCET_URL" 2>/dev/null || echo "error")

sleep 2

RESPONSE2=$(curl -s -X POST -H "Content-Type: application/json" \
  --data "{\"jsonrpc\":\"2.0\",\"method\":\"faucet_sendFunds\",\"params\":[{\"address\":\"$TEST_RECIPIENT\"}],\"id\":2}" \
  "$FAUCET_URL" 2>/dev/null || echo "error")

# Check if second request was handled appropriately
if [ "$RESPONSE2" != "error" ]; then
  ERROR2=$(echo "$RESPONSE2" | jq -r '.error.message' 2>/dev/null || echo "")
  
  if [ -n "$ERROR2" ]; then
    if echo "$ERROR2" | grep -qi "rate\|limit\|too.*fast\|wait"; then
      echo "✅ Rate limiting is working"
    else
      echo "⚠️  Note: Second request returned error: $ERROR2"
    fi
  else
    TX_HASH2=$(echo "$RESPONSE2" | jq -r '.result.txId' 2>/dev/null || echo "null")
    if [ "$TX_HASH2" != "null" ]; then
      echo "⚠️  Note: Second request succeeded (no rate limiting detected)"
    fi
  fi
else
  echo "⚠️  Note: Could not complete rate limiting test"
fi

echo ""

# Summary
echo "=== Faucet Test Summary ==="
echo ""
echo "Faucet service is running and responding to requests."
echo ""
echo "Configuration:"
echo "  - Faucet URL: $FAUCET_URL"
echo "  - Node URL: $NODE_URL"
echo ""
echo "✅ Faucet validation tests completed"
echo ""
echo "Note: Full validation requires:"
echo "  1. Faucet service running with initialized wallet"
echo "  2. Connected Fukuii node with mining enabled"
echo "  3. Genesis account with sufficient balance in faucet wallet"
