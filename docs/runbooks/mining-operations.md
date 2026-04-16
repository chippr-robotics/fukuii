# Mining Operations Runbook

**Version**: 1.0  
**Last Updated**: 2025-12-09  
**Audience**: Node Operators, Mining Pool Operators

## Overview

This runbook covers mining operations for Fukuii nodes on Ethereum Classic (ETC). Since Ethereum mainnet no longer supports mining, Fukuii provides enhanced mining RPC endpoints specifically designed for ETC mining operations.

## Table of Contents

- [Prerequisites](#prerequisites)
- [Configuration](#configuration)
- [Starting Mining](#starting-mining)
- [Stopping Mining](#stopping-mining)
- [Monitoring Mining Status](#monitoring-mining-status)
- [External Miner Integration](#external-miner-integration)
- [Troubleshooting](#troubleshooting)
- [Best Practices](#best-practices)

## Prerequisites

### Node Requirements
- Fukuii node fully synced with the ETC network
- Sufficient disk space for blockchain data
- Adequate CPU and memory resources
- Network connectivity for peer-to-peer communication

### Mining Requirements
- **Consensus Type**: Ethash (Proof of Work) - required for all mining operations
- **Coinbase Address**: Configured in node configuration file
- **Mining Enabled**: Set `mining.mining-enabled = true` in configuration

### Configuration File Location
```
conf/fukuii.conf
```

## Configuration

### Basic Mining Configuration

Edit your `fukuii.conf` file to enable mining:

```hocon
mining {
  mining-enabled = true
  coinbase = "0xYourEthereumAddress"
  
  ethash {
    mine-rounds = 100000
    ommerPoolQueryTimeout = 60 seconds
  }
}
```

### Key Configuration Parameters

| Parameter | Description | Default |
|-----------|-------------|---------|
| `mining.mining-enabled` | Enable/disable mining on node startup | `false` |
| `mining.coinbase` | Address to receive mining rewards | Required |
| `mining.ethash.mine-rounds` | Number of mining rounds per iteration | `100000` |
| `mining.ethash.ommerPoolQueryTimeout` | Timeout for ommer pool queries | `60s` |

## Starting Mining

### Via RPC (Recommended)

Start mining without restarting the node using the `miner_start` RPC endpoint:

```bash
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "miner_start",
    "params": []
  }'
```

**Response:**
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": true
}
```

### On Node Startup

Set `mining.mining-enabled = true` in your configuration file. Mining will start automatically when the node starts.

### Verification

Verify mining has started:

```bash
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "eth_mining",
    "params": []
  }'
```

**Expected Response:**
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": true
}
```

## Stopping Mining

### Via RPC

Stop mining without restarting the node:

```bash
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "miner_stop",
    "params": []
  }'
```

**Response:**
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": true
}
```

### Via Configuration

Set `mining.mining-enabled = false` and restart the node.

## Monitoring Mining Status

### Comprehensive Status Check

Get all mining information in a single call using `miner_getStatus`:

```bash
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "miner_getStatus",
    "params": []
  }'
```

**Response:**
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "isMining": true,
    "coinbase": "0x742d35Cc6634C0532925a3b844Bc454e4438f44e",
    "hashRate": "0x64",
    "blocksMinedCount": null
  }
}
```

**Response Fields:**
- `isMining`: Boolean - whether the node is actively mining
- `coinbase`: Address - the address receiving mining rewards
- `hashRate`: Hex string - current aggregate hashrate (in hashes/second)
- `blocksMinedCount`: Always `null` in current version (reserved for future use)

### Individual Status Checks

**Check if mining:**
```bash
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc": "2.0", "id": 1, "method": "eth_mining", "params": []}'
```

**Get current hashrate:**
```bash
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc": "2.0", "id": 1, "method": "eth_hashrate", "params": []}'
```

**Get coinbase address:**
```bash
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc": "2.0", "id": 1, "method": "eth_coinbase", "params": []}'
```

## External Miner Integration

Fukuii supports external miners using the standard Ethereum mining protocol.

### Get Work for External Miner

External miners can request work using `eth_getWork`:

```bash
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "eth_getWork",
    "params": []
  }'
```

**Response:**
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": [
    "0x1234567890abcdef...",  // Current block header hash
    "0x5eed00000000000...",   // Seed hash for DAG
    "0x0000000112e0be82..."   // Target (difficulty boundary)
  ]
}
```

### Submit Work from External Miner

Submit a proof-of-work solution:

```bash
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "eth_submitWork",
    "params": [
      "0x0000000000000001",
      "0x1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef",
      "0xD1FE5700000000000000000000000000D1FE5700000000000000000000000001"
    ]
  }'
```

**Parameters:**
1. Nonce (8 bytes)
2. Header hash (32 bytes)
3. Mix digest (32 bytes)

### Submit Hashrate

External miners should report their hashrate:

```bash
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "eth_submitHashrate",
    "params": [
      "0x500000",
      "0x59daa26581d0acd1fce254fb7e85952f4c09d0915afd33d3886cd914bc7d283c"
    ]
  }'
```

**Parameters:**
1. Hashrate (hex-encoded)
2. Miner ID (32 bytes, unique identifier for the miner)

## Troubleshooting

### Mining Not Starting

**Symptom:** `miner_start` returns `true` but `eth_mining` returns `false`

**Possible Causes:**
1. Node not fully synced
2. No peers connected
3. Consensus type is not Ethash

**Solutions:**
```bash
# Check sync status
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc": "2.0", "id": 1, "method": "eth_syncing", "params": []}'

# Check peer count
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc": "2.0", "id": 1, "method": "net_peerCount", "params": []}'
```

### Error: "Mining is not Ethash"

**Symptom:** RPC calls return error: `"Mining is not Ethash"`

**Cause:** The node is not configured for Proof of Work mining.

**Solution:** Verify your configuration file has:
```hocon
consensus {
  protocol = "pow"  # or "ethash"
}
```

### Low Hashrate

**Symptom:** `eth_hashrate` returns a very low value

**Possible Causes:**
1. Limited CPU resources
2. External miners not reporting hashrate
3. Hashrate submissions timing out

**Solutions:**
- Ensure external miners call `eth_submitHashrate` regularly
- Check system resource usage
- Verify hashrate submissions are within the timeout window (default: 2 minutes)

### No Work Available

**Symptom:** `eth_getWork` returns an error

**Possible Causes:**
1. Node not synced
2. Mining not started
3. No pending transactions

**Solutions:**
```bash
# Start mining first
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc": "2.0", "id": 1, "method": "miner_start", "params": []}'

# Verify mining is active
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc": "2.0", "id": 1, "method": "miner_getStatus", "params": []}'
```

## Best Practices

### 1. Monitor Mining Status Regularly

Use `miner_getStatus` to get a comprehensive view of mining operations:

```bash
# Check status every 30 seconds
watch -n 30 'curl -s -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d "{\"jsonrpc\": \"2.0\", \"id\": 1, \"method\": \"miner_getStatus\", \"params\": []}" | jq'
```

### 2. Proper Coinbase Configuration

- Use a secure wallet address you control
- Never use exchange addresses for mining rewards
- Backup your wallet's private keys securely

### 3. Resource Management

- Monitor CPU and memory usage
- Ensure adequate cooling for mining hardware
- Set appropriate `mine-rounds` based on your hardware capabilities

### 4. Network Considerations

- Maintain stable peer connections (recommended: 15-25 peers)
- Use low-latency network connection
- Consider running multiple nodes for redundancy

### 5. External Miner Integration

- Configure miners to submit hashrate every 60 seconds
- Use unique miner IDs to track individual miner performance
- Implement retry logic for work submission failures

### 6. Security

- Restrict RPC access to trusted networks only
- Use firewall rules to limit RPC endpoint access
- Never expose mining RPC endpoints to the public internet
- Consider using TLS for RPC connections (see [TLS Operations](tls-operations.md))

### 7. Logging and Monitoring

Monitor these log events:
- `Mining started via RPC` - Mining successfully initiated
- `Mining stopped via RPC` - Mining successfully stopped
- Mining errors or exceptions

Example log monitoring:
```bash
tail -f /path/to/fukuii/logs/fukuii.log | grep -i mining
```

### 8. Graceful Shutdown

Always stop mining gracefully before shutting down the node:

```bash
# Stop mining
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc": "2.0", "id": 1, "method": "miner_stop", "params": []}'

# Wait a few seconds
sleep 5

# Shutdown node
systemctl stop fukuii
```

## API Reference

For complete API documentation of all mining endpoints, see:
- [JSON-RPC API Reference - Mining Section](../api/JSON_RPC_API_REFERENCE.md#mining)

## Related Documentation

- [Node Configuration](node-configuration.md) - General node configuration
- [Operating Modes](operating-modes.md) - Different node operating modes
- [Security](security.md) - Security best practices
- [TLS Operations](tls-operations.md) - Securing RPC connections

## Support

For mining-related issues:
1. Check logs: `tail -f /path/to/fukuii/logs/fukuii.log`
2. Review [Known Issues](known-issues.md)
3. Open an issue on GitHub: https://github.com/chippr-robotics/fukuii/issues

---

**Note**: Mining is only supported on Ethereum Classic (ETC) networks. Ethereum mainnet has transitioned to Proof of Stake and no longer supports mining.
