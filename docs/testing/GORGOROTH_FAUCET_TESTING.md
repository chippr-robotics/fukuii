# Gorgoroth Network - Faucet Testing Guide

## Overview

The Fukuii faucet is a testnet service that automatically distributes tokens to test addresses. This guide covers how to set up, run, and validate the faucet service on the Gorgoroth test network.

## Faucet Architecture

The faucet consists of:

1. **Faucet Service** - HTTP JSON-RPC server that receives fund requests
2. **Wallet Service** - Manages the faucet wallet and sends transactions
3. **RPC Client** - Connects to a Fukuii node to submit transactions

```
[User Request] → [Faucet HTTP API] → [Faucet Handler] → [Wallet Service] → [Fukuii Node] → [Blockchain]
```

## Configuration

### Faucet Configuration File

The faucet uses `src/main/resources/conf/faucet.conf` for configuration.

**Key Settings:**

```hocon
faucet {
  # Base directory for faucet data
  datadir = ${user.home}"/.fukuii-faucet"
  
  # Wallet address to send funds from
  wallet-address = "0x1000000000000000000000000000000000000001"
  
  # Wallet password
  wallet-password = "test-password"
  
  # Keystore directory
  keystore-dir = ${faucet.datadir}"/keystore"
  
  # Transaction parameters
  tx-gas-price = 20000000000
  tx-gas-limit = 90000
  tx-value = 1000000000000000000  # 1 ETC
  
  rpc-client {
    # Fukuii node RPC endpoint
    rpc-address = "http://127.0.0.1:8545/"
    timeout = 3.seconds
  }
}

fukuii.network.rpc.http {
  # Faucet HTTP API settings
  enabled = true
  interface = "localhost"
  port = 8099
  
  # Enable faucet API
  apis = "faucet"
}
```

### Gorgoroth-Specific Configuration

For the Gorgoroth test network, create `ops/gorgoroth/conf/faucet-gorgoroth.conf`:

```hocon
include "faucet.conf"

faucet {
  # Use one of the pre-funded genesis accounts
  wallet-address = "0x1000000000000000000000000000000000000001"
  wallet-password = ""
  
  # Point to Gorgoroth node
  rpc-client {
    rpc-address = "http://localhost:8545/"
  }
  
  # Smaller amounts for testing
  tx-value = 500000000000000000  # 0.5 ETC
}

fukuii.network.rpc.http {
  port = 8099
  cors-allowed-origins = ["*"]
}
```

## Setting Up the Faucet

### Step 1: Create Faucet Wallet

The faucet needs a wallet with funds. For Gorgoroth, use one of the pre-funded genesis accounts:

```bash
# The genesis accounts are already funded in the Gorgoroth genesis block
# Address: 0x1000000000000000000000000000000000000001
# Balance: 1,000,000,000,000 ETC
```

### Step 2: Generate Keystore File

If you need to create a new keystore file for the faucet wallet:

```bash
# Use fukuii CLI to generate key pairs
./bin/fukuii cli generate-key-pairs

# Or use existing genesis account private key
# Place keystore file in: ~/.fukuii-faucet/keystore/
```

For Gorgoroth testing, you can use the genesis account directly if the private key is available.

### Step 3: Start Gorgoroth Network

```bash
cd ops/gorgoroth
fukuii-cli start 3nodes

# Wait for nodes to initialize
sleep 45
```

### Step 4: Start Faucet Service

```bash
# Start faucet with custom config
./bin/fukuii faucet -Dconfig.file=ops/gorgoroth/conf/faucet-gorgoroth.conf

# Or with environment variables
FAUCET_WALLET_ADDRESS=0x1000000000000000000000000000000000000001 \
FAUCET_WALLET_PASSWORD="" \
./bin/fukuii faucet
```

## Testing the Faucet

### Automated Test Script

Run the automated faucet validation test:

```bash
cd ops/gorgoroth/test-scripts
./test-faucet.sh
```

This script tests:
- ✅ Faucet service availability
- ✅ Faucet status endpoint
- ✅ RPC method availability
- ✅ Fund distribution functionality
- ✅ Transaction confirmation
- ✅ Balance verification
- ✅ Rate limiting (optional)

### Manual Testing

#### 1. Check Faucet Status

```bash
curl -X POST -H "Content-Type: application/json" \
  --data '{"jsonrpc":"2.0","method":"faucet_status","params":[],"id":1}' \
  http://localhost:8099
```

**Expected Response:**
```json
{
  "jsonrpc":"2.0",
  "result":{
    "status":"WalletAvailable"
  },
  "id":1
}
```

#### 2. Request Funds

```bash
# Request funds for a test address
curl -X POST -H "Content-Type: application/json" \
  --data '{
    "jsonrpc":"2.0",
    "method":"faucet_sendFunds",
    "params":[{
      "address":"0x2000000000000000000000000000000000000002"
    }],
    "id":1
  }' \
  http://localhost:8099
```

**Expected Response:**
```json
{
  "jsonrpc":"2.0",
  "result":{
    "txId":"0x1234567890abcdef..."
  },
  "id":1
}
```

#### 3. Verify Transaction

```bash
# Check transaction receipt
curl -X POST -H "Content-Type: application/json" \
  --data '{
    "jsonrpc":"2.0",
    "method":"eth_getTransactionReceipt",
    "params":["0x1234567890abcdef..."],
    "id":1
  }' \
  http://localhost:8545
```

#### 4. Verify Balance Increase

```bash
# Get balance before and after
curl -X POST -H "Content-Type: application/json" \
  --data '{
    "jsonrpc":"2.0",
    "method":"eth_getBalance",
    "params":["0x2000000000000000000000000000000000000002","latest"],
    "id":1
  }' \
  http://localhost:8545
```

## Docker Integration

### Docker Compose Configuration

Add faucet service to Gorgoroth Docker Compose:

```yaml
# Add to docker-compose-3nodes.yml or create docker-compose-with-faucet.yml

services:
  # ... existing fukuii nodes ...
  
  faucet:
    image: ghcr.io/chippr-robotics/fukuii:latest
    container_name: gorgoroth-faucet
    hostname: faucet
    restart: unless-stopped
    networks:
      gorgoroth:
        ipv4_address: 172.25.0.20
    ports:
      - "8099:8099"
    volumes:
      - ./conf/faucet-gorgoroth.conf:/app/fukuii/conf/faucet.conf:ro
      - faucet-data:/app/faucet-data
    environment:
      - FAUCET_WALLET_ADDRESS=0x1000000000000000000000000000000000000001
      - FAUCET_WALLET_PASSWORD=
    command: ["fukuii", "faucet"]
    depends_on:
      - fukuii-node1

volumes:
  faucet-data:
```

### Start with Faucet

```bash
cd ops/gorgoroth
docker compose -f docker-compose-with-faucet.yml up -d

# Wait for services to initialize
sleep 60

# Test faucet
curl http://localhost:8099
```

## API Reference

### faucet_status

Returns the current status of the faucet service.

**Request:**
```json
{
  "jsonrpc":"2.0",
  "method":"faucet_status",
  "params":[],
  "id":1
}
```

**Response:**
```json
{
  "jsonrpc":"2.0",
  "result":{
    "status":"WalletAvailable"
  },
  "id":1
}
```

**Possible Status Values:**
- `WalletAvailable` - Faucet is ready to send funds
- `FaucetUnavailable` - Wallet is not initialized

### faucet_sendFunds

Sends testnet tokens to a specified address.

**Request:**
```json
{
  "jsonrpc":"2.0",
  "method":"faucet_sendFunds",
  "params":[{
    "address":"0x2000000000000000000000000000000000000002"
  }],
  "id":1
}
```

**Response (Success):**
```json
{
  "jsonrpc":"2.0",
  "result":{
    "txId":"0x1234567890abcdef..."
  },
  "id":1
}
```

**Response (Error):**
```json
{
  "jsonrpc":"2.0",
  "error":{
    "code":-32000,
    "message":"Faucet is unavailable: Please try again in a few more seconds"
  },
  "id":1
}
```

## Validation Checklist

When validating the faucet for release, ensure:

- [ ] **Configuration**
  - [ ] Faucet config file is correct
  - [ ] Wallet address is valid and funded
  - [ ] RPC endpoint is accessible
  - [ ] Port 8099 is available

- [ ] **Functionality**
  - [ ] Faucet service starts without errors
  - [ ] Status endpoint returns "WalletAvailable"
  - [ ] Can request funds successfully
  - [ ] Transactions are submitted to blockchain
  - [ ] Transactions are mined and confirmed
  - [ ] Recipient balance increases

- [ ] **Security**
  - [ ] Wallet password is secure
  - [ ] Rate limiting is configured (if needed)
  - [ ] CORS is configured appropriately
  - [ ] Faucet is only accessible from intended networks

- [ ] **Integration**
  - [ ] Works with Fukuii nodes
  - [ ] Compatible with testnet configurations
  - [ ] Can run in Docker container
  - [ ] Logs are accessible and informative

## Troubleshooting

### Faucet Service Won't Start

**Symptoms:** Service exits immediately or fails to bind to port

**Solutions:**
1. Check port 8099 is not already in use: `lsof -i :8099`
2. Verify config file exists and is valid
3. Check logs in `~/.fukuii-faucet/logs/faucet.log`

### "Faucet is unavailable" Error

**Symptoms:** Status returns `FaucetUnavailable`

**Solutions:**
1. Check wallet address is correct in config
2. Verify wallet keystore file exists
3. Check wallet password is correct
4. Ensure wallet has sufficient balance
5. Verify RPC connection to Fukuii node

### Transaction Not Mined

**Symptoms:** Transaction hash returned but receipt is null

**Solutions:**
1. Wait longer (mining may be slow)
2. Check if mining is enabled on network
3. Verify gas price is sufficient
4. Check node logs for errors

### Balance Doesn't Increase

**Symptoms:** Recipient balance unchanged after transaction

**Solutions:**
1. Verify transaction was actually mined
2. Check transaction receipt for errors
3. Ensure correct recipient address
4. Verify faucet wallet has sufficient balance

## Performance Considerations

### Rate Limiting

Configure rate limiting to prevent abuse:

```hocon
fukuii.network.rpc.http.rate-limit {
  enabled = true
  min-request-interval = 60.seconds  # One request per minute
  latest-timestamp-cache-size = 1024
}
```

### Transaction Parameters

Adjust for network conditions:

```hocon
faucet {
  tx-gas-price = 20000000000  # Adjust based on network
  tx-gas-limit = 90000         # Standard transfer
  tx-value = 1000000000000000000  # 1 ETC
}
```

## Security Best Practices

1. **Wallet Security**
   - Use a dedicated wallet for faucet
   - Limit funds in faucet wallet
   - Store wallet password securely
   - Regular wallet balance monitoring

2. **Network Security**
   - Enable rate limiting
   - Configure CORS appropriately
   - Use HTTPS in production
   - Monitor for abuse patterns

3. **Monitoring**
   - Log all fund requests
   - Monitor wallet balance
   - Alert on unusual activity
   - Regular health checks

## Integration with Gorgoroth Tests

The faucet validation is integrated into the Gorgoroth test suite:

```bash
# Run complete test suite including faucet
cd ops/gorgoroth/test-scripts
./run-test-suite.sh 3nodes --with-faucet

# Or run faucet test individually
./test-faucet.sh
```

## References

- [Faucet Implementation](../../src/main/scala/com/chipprbots/ethereum/faucet/)
- [Faucet Configuration](../../src/main/resources/conf/faucet.conf)
- [Node Configuration Guide](../../docs/runbooks/node-configuration.md)
- [Gorgoroth Network Documentation](README.md)

## Support

For faucet-related issues:
- Check faucet logs: `~/.fukuii-faucet/logs/faucet.log`
- Review configuration: `conf/faucet.conf`
- Test with automated script: `test-scripts/test-faucet.sh`
- GitHub Issues: https://github.com/chippr-robotics/fukuii/issues
