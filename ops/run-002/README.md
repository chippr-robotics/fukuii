# Run 002 Configuration

This configuration sets up a Fukuii node on the **Mordor testnet** (Ethereum Classic testnet) with enhanced debugging capabilities for snap sync protocol testing.

## Key Improvements from Run 001

1. **Removed deprecated `version` line** from docker-compose.yml (Docker Compose v2+ compatibility)
2. **Updated Docker image** to use `chipprbots/fukuii-mordor:latest` from Docker Hub (no authentication required)
3. **Enhanced logging configuration** for comprehensive snap sync diagnostics
4. **Network and RLPx debugging enabled** to diagnose peer communication issues

## Configuration Details

### Network Configuration
- **Network**: Mordor (Ethereum Classic testnet)
- **Purpose**: End-to-end testing of snap sync protocol
- **Safety**: Uses testnet instead of mainnet to avoid risks with real funds

### Logging Configuration

The following components have DEBUG-level logging enabled for comprehensive snap sync diagnostics:

#### SNAP Sync Components (Primary Focus)
- `com.chipprbots.ethereum.blockchain.sync.snap` - SNAP sync controller
- `com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController`
- `com.chipprbots.ethereum.blockchain.sync.snap.AccountRangeDownloader`
- `com.chipprbots.ethereum.blockchain.sync.snap.StorageRangeDownloader`
- `com.chipprbots.ethereum.blockchain.sync.snap.TrieNodeHealer`
- `com.chipprbots.ethereum.blockchain.sync.snap.MerkleProofVerifier`

#### Network Components (Peer Communication Diagnostics)
- `com.chipprbots.scalanet.*` - Low-level networking
- `com.chipprbots.ethereum.network.*` - Peer connections and management
- `com.chipprbots.ethereum.network.rlpx.*` - RLPx encrypted transport
- `com.chipprbots.ethereum.network.discovery.*` - Peer discovery

#### Sync Components (Supporting Diagnostics)
- `com.chipprbots.ethereum.blockchain.sync.*` - Core sync controller
- Fast sync components
- Regular sync components

All other components remain at INFO level for optimal performance.

## Directory Structure

```
ops/run-002/
├── conf/
│   ├── mordor.conf       # Network configuration (Mordor testnet)
│   └── logback.xml       # Enhanced logging configuration
├── docker-compose.yml    # Updated Docker Compose (no version line)
├── start.sh             # Quick start/stop script
└── README.md            # This file
```

## Usage

### Quick Start (Recommended)

Use the provided convenience script:

```bash
cd ops/run-002

# Start the node
./start.sh start

# View logs
./start.sh logs

# Check status
./start.sh status

# Stop the node
./start.sh stop

# Remove all data (including blockchain)
./start.sh clean

# Show help
./start.sh help
```

### Using Docker Compose Manually

1. Navigate to this directory:
   ```bash
   cd ops/run-002
   ```

2. Start the node:
   ```bash
   docker compose up -d
   ```

3. View logs:
   ```bash
   docker compose logs -f fukuii
   ```

4. Stop the node:
   ```bash
   docker compose down
   ```

5. To remove all data (including blockchain data):
   ```bash
   docker compose down -v
   ```

### Using Configuration Files Manually

If running Fukuii outside of Docker:

1. Copy the configuration files to your Fukuii installation:
   ```bash
   cp conf/mordor.conf /path/to/fukuii/conf/
   cp conf/logback.xml /path/to/fukuii/conf/
   ```

2. Start Fukuii with the custom logback configuration:
   ```bash
   ./bin/fukuii mordor -Dlogback.configurationFile=conf/logback.xml
   ```

## Monitoring

### Health Check
```bash
curl http://localhost:8546/health
```

### Readiness Check
```bash
curl http://localhost:8546/readiness
```

### Full Health Status
```bash
curl http://localhost:8546/healthcheck
```

## Exposed Ports

- **8545**: JSON-RPC HTTP endpoint
- **8546**: JSON-RPC WebSocket endpoint  
- **30303**: P2P networking (TCP and UDP)

## Log Files

When using Docker Compose, logs are stored in the `fukuii-run002-logs` volume. To access them:

```bash
docker compose exec fukuii ls -la /app/logs/
```

Or view directly:
```bash
docker compose exec fukuii tail -f /app/logs/fukuii.log
```

## Diagnostic Focus Areas

Based on the logging configuration documentation, this setup enables detailed diagnostics for:

### 1. Snap Sync Protocol
- Account range downloads
- Storage range downloads
- Trie node healing
- Merkle proof verification

### 2. Peer Communication
- RLPx handshake process
- Message encoding/decoding
- Connection establishment
- Protocol negotiation

### 3. Network Discovery
- Peer discovery process
- Node discovery service
- UDP peer groups

### 4. Sync Strategy
- Adaptive sync strategy selection
- Pivot block selection
- State synchronization
- Block fetching and importing

## Performance Considerations

⚠️ **Warning**: DEBUG logging for snap sync, network, and RLPx components generates **significantly more log output** than INFO level. This configuration is intended for:

- Development and debugging
- End-to-end testing of snap sync protocol
- Troubleshooting sync and peer communication issues
- Understanding snap sync protocol behavior

**Not recommended for production use** due to:
- Increased disk I/O from verbose logging
- Larger log files requiring more storage (10MB per file, 50 files max)
- Potential performance impact on sync speed

## Troubleshooting Guide

### Container won't start
Check logs:
```bash
docker compose logs fukuii
```

### No peers connecting
1. Ensure ports 30303 (TCP/UDP) are open in your firewall
2. Check peer discovery logs (DEBUG enabled):
   ```bash
   docker compose logs fukuii | grep -i "discovery\|peer"
   ```

### Sync not progressing
With DEBUG logging enabled, examine the sync-related log entries:
```bash
docker compose logs fukuii | grep -i "sync\|snap"
```

### RLPx handshake failures
Check RLPx logs (DEBUG enabled):
```bash
docker compose logs fukuii | grep -i "rlpx\|handshake"
```

### Network configuration issues
Verify the node is connected to Mordor testnet:
```bash
docker compose logs fukuii | grep -i "network\|mordor"
```

## Customization

### Changing Log Levels

Edit `conf/logback.xml` and change the level attribute for specific loggers:

```xml
<!-- Change from DEBUG to INFO to reduce verbosity -->
<logger name="com.chipprbots.ethereum.blockchain.sync" level="INFO" />
```

### Switching Networks

Edit `conf/mordor.conf` to change the network:

```hocon
fukuii {
  blockchains {
    network = "etc"  # Change to etc (mainnet), mordor (testnet), etc.
  }
}
```

⚠️ **Note**: This configuration uses Mordor testnet by default for safety. Only switch to mainnet if you understand the implications.

## Differences from Run 001

| Aspect | Run 001 | Run 002 |
|--------|---------|---------|
| Docker Compose Version | `version: '3.8'` (deprecated) | No version line (modern) |
| Docker Image | `ghcr.io/chippr-robotics/fukuii:latest` | `chipprbots/fukuii-mordor:latest` |
| Network Logging | INFO | DEBUG |
| RLPx Logging | INFO | DEBUG |
| Discovery Logging | INFO | DEBUG |
| Container Name | fukuii-run-001 | fukuii-run-002 |
| Volume Names | fukuii-run001-* | fukuii-run002-* |
| Node ID | run-001 | run-002 |

## Related Documentation

- [Fukuii Logging Documentation](../../docs/operations/LOGGING.md)
- [Log Level Categorization ADR](../../docs/adr/operations/OPS-002-logging-level-categorization.md)
- [Operations Runbooks](../../docs/runbooks/README.md)
- [Log Triage Guide](../../docs/runbooks/log-triage.md)
- [Metrics & Monitoring](../../docs/operations/metrics-and-monitoring.md)

## Support

For issues or questions about this configuration, please refer to:
- [Known Issues](../../docs/runbooks/known-issues.md)
- [GitHub Issues](https://github.com/chippr-robotics/fukuii/issues)
