# Fukuii and Core-Geth Test Network

This directory contains a Docker Compose setup for testing Fukuii's connectivity with Core-Geth, the Ethereum Classic client based on go-ethereum.

## Purpose

This test network allows you to:
- Test peer-to-peer connectivity between Fukuii and Core-Geth
- Capture detailed logs of the handshake process
- Verify network synchronization behavior
- Debug connection issues in a controlled environment

## Architecture

The test network consists of three Docker containers:

1. **core-geth**: Ethereum Classic Core-Geth node
   - IP: 172.25.0.10
   - P2P Port: 30303
   - RPC Port: 8545
   - WebSocket: 8546

2. **fukuii**: Fukuii Ethereum Classic node
   - IP: 172.25.0.20
   - P2P Port: 30303 (mapped to host 30304)
   - RPC Port: 8546 (mapped to host 8547)
   - WebSocket: 8547 (mapped to host 8548)

3. **log-collector**: Ubuntu container for log collection
   - Provides utilities for capturing and analyzing logs

## Prerequisites

- Docker Engine 20.10+
- Docker Compose 1.29+
- At least 4GB of available RAM
- 10GB of available disk space

## Quick Start

### 1. Start the Test Network

```bash
cd docker/test-network
docker-compose up -d
```

This will:
- Start Core-Geth node and wait for it to be healthy
- Start Fukuii node configured to connect to Core-Geth
- Start the log collector container

### 2. Monitor the Logs

Watch logs in real-time:
```bash
# All containers
docker-compose logs -f

# Only Fukuii
docker-compose logs -f fukuii

# Only Core-Geth
docker-compose logs -f core-geth
```

### 3. Collect Logs for Analysis

Run the log collection script:
```bash
./collect-logs.sh
```

This script will:
- Capture logs from both containers
- Display network information
- Show peer connection status
- Extract handshake-related log entries
- Identify any errors
- Save all logs to `./captured-logs/` directory

### 4. Stop the Test Network

```bash
docker-compose down

# To also remove volumes (blockchain data)
docker-compose down -v
```

## Configuration

### Core-Geth Configuration

Core-Geth is configured with:
- Ethereum Classic network (networkid=61)
- P2P enabled with discovery
- HTTP and WebSocket RPC enabled
- Verbose logging (level 4)
- Maximum 50 peers

### Fukuii Configuration

Fukuii is configured via `fukuii.conf` with:
- Ethereum Classic network (networkid=61)
- Bootstrap node pointing to Core-Geth (172.25.0.10:30303)
- Enhanced logging for network and sync operations
- JSON-RPC APIs enabled
- Metrics endpoint enabled

## Troubleshooting

### Containers won't start

Check container status:
```bash
docker-compose ps
```

View startup errors:
```bash
docker-compose logs
```

### Fukuii can't connect to Core-Geth

1. Verify Core-Geth is healthy:
```bash
docker-compose ps core-geth
```

2. Check if Core-Geth enode is accessible:
```bash
docker exec test-core-geth geth attach --exec "admin.nodeInfo.enode" http://localhost:8545
```

3. Verify network connectivity:
```bash
docker exec test-fukuii ping -c 3 172.25.0.10
```

4. Check Core-Geth accepts connections:
```bash
docker exec test-core-geth geth attach --exec "admin.peers" http://localhost:8545
```

### No handshake logs appearing

1. Check if Fukuii is attempting connections:
```bash
docker logs test-fukuii 2>&1 | grep -i "connection\|peer"
```

2. Verify discovery is working:
```bash
docker logs test-fukuii 2>&1 | grep -i "discovery"
```

3. Increase verbosity (edit `fukuii.conf` and restart):
```conf
fukuii.logging.json-rpc-http-mode-enabled = true
```

## Log Analysis

### Important Log Patterns

**Successful Handshake:**
```
[RLPx] TCP connection established for peer 172.25.0.10:30303
[RLPx] Auth handshake SUCCESS for peer 172.25.0.10:30303
[RLPx] Connection FULLY ESTABLISHED with peer 172.25.0.10:30303
```

**Connection Errors:**
```
ERROR [c.c.e.n.rlpx.RLPxConnectionHandler] - [Stopping Connection] TCP connection to ...
```

**Handshake Timeouts:**
```
AuthHandshakeTimeout
```

**NullPointerException (if present):**
```
java.lang.NullPointerException: Cannot invoke "String.contains(java.lang.CharSequence)"
```

### Log Collection Script Output

The `collect-logs.sh` script generates:

1. **Timestamped log files** in `./captured-logs/`:
   - `test-core-geth_YYYYMMDD_HHMMSS.log`
   - `test-fukuii_YYYYMMDD_HHMMSS.log`

2. **Network information**: IP addresses and container connectivity

3. **Peer information**: Connected peers from both clients

4. **Filtered logs**: Recent handshake and error logs for quick analysis

## Advanced Usage

### Custom Bootstrap Node

To use a different Core-Geth enode:

1. Get the enode from Core-Geth:
```bash
docker exec test-core-geth geth attach --exec "admin.nodeInfo.enode" http://localhost:8545
```

2. Update `fukuii.conf`:
```conf
bootstrap-nodes = [
  "enode://YOUR_ENODE_HERE@172.25.0.10:30303"
]
```

3. Restart Fukuii:
```bash
docker-compose restart fukuii
```

### Enable Debug Logging

For more detailed logs, modify `fukuii.conf`:

```conf
fukuii {
  logging {
    # Add more detailed logging configurations
    # Note: This may require a custom logback.xml configuration
  }
}
```

### Persistent Data

By default, blockchain data is stored in Docker volumes:
- `test-network_core-geth-data`
- `test-network_fukuii-data`

To use host directories instead, modify `docker-compose.yml`:

```yaml
volumes:
  - ./data/core-geth:/root/.ethereum
  - ./data/fukuii:/app/data
```

### Testing Specific Scenarios

**Test initial sync:**
```bash
# Remove volumes and restart
docker-compose down -v
docker-compose up -d
```

**Test with multiple peers:**
Modify `docker-compose.yml` to add more Core-Geth instances with different ports.

**Test network interruption:**
```bash
# Pause Core-Geth
docker pause test-core-geth

# Wait 30 seconds, then resume
sleep 30
docker unpause test-core-geth
```

## Integration with CI/CD

This test network can be used in automated testing:

```bash
#!/bin/bash
# CI test script example

# Start network
docker-compose up -d

# Wait for services to be healthy
timeout 120 bash -c 'until docker-compose ps | grep -q "healthy"; do sleep 5; done'

# Collect logs
./collect-logs.sh

# Check for errors in fukuii logs
if docker logs test-fukuii 2>&1 | grep -q "NullPointerException"; then
    echo "ERROR: NullPointerException found in logs"
    exit 1
fi

# Check peer connectivity
PEER_COUNT=$(docker exec test-fukuii curl -s -X POST \
  -H "Content-Type: application/json" \
  --data '{"jsonrpc":"2.0","method":"net_peerCount","params":[],"id":1}' \
  http://localhost:8546 | jq -r '.result')

if [ "$PEER_COUNT" == "0x0" ]; then
    echo "ERROR: No peers connected"
    exit 1
fi

echo "SUCCESS: Test network is functioning correctly"

# Cleanup
docker-compose down -v
```

## Support

For issues specific to this test network setup:
- Check the logs in `./captured-logs/`
- Review the [Documentation Home](../index.md)
- Open an issue on GitHub with captured logs

## Related Documentation

- [Fukuii Documentation](../index.md)
- [Docker Deployment Guide](docker.md)
- [Core-Geth Documentation](https://core-geth.org/)
