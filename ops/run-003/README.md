# Run 003 Configuration

This configuration sets up a Fukuii node on the **ETC mainnet** (Ethereum Classic) with enhanced debugging capabilities for snap sync protocol testing.

## Key Changes from Run 002

Based on feedback from run-002:

1. **Switched to ETC Mainnet** - The Mordor testnet node couldn't find enough peers for effective testing
2. **Using ghcr.io images** - Switched back to GitHub Container Registry images (`ghcr.io/chippr-robotics/fukuii:latest`)
3. **FastSync logging reduced to INFO** - The DEBUG level was generating too many messages that weren't directly useful
4. **Kept SNAP sync and network logging at DEBUG** - These remain the primary focus for diagnostics
5. **Maintained peer discovery diagnostics** - To continue investigating peer connection issues

## Configuration Details

### Network Configuration
- **Network**: ETC (Ethereum Classic mainnet)
- **Purpose**: End-to-end testing of snap sync protocol with better peer availability
- **Image**: `ghcr.io/chippr-robotics/fukuii:latest` (GitHub Container Registry)

### Logging Configuration

The following components have specific logging levels configured:

#### SNAP Sync Components (Primary Focus - DEBUG)
- `com.chipprbots.ethereum.blockchain.sync.snap` - SNAP sync controller
- `com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController`
- `com.chipprbots.ethereum.blockchain.sync.snap.AccountRangeDownloader`
- `com.chipprbots.ethereum.blockchain.sync.snap.StorageRangeDownloader`
- `com.chipprbots.ethereum.blockchain.sync.snap.TrieNodeHealer`
- `com.chipprbots.ethereum.blockchain.sync.snap.MerkleProofVerifier`

#### Fast Sync Components (INFO - Reduced from DEBUG)
- `com.chipprbots.ethereum.blockchain.sync.fast.*` - All fast sync components
- Rationale: Too verbose at DEBUG level, not directly useful for current diagnostics

#### Network Components (DEBUG - Peer Communication Diagnostics)
- `com.chipprbots.scalanet.*` - Low-level networking
- `com.chipprbots.ethereum.network.*` - Peer connections and management
- `com.chipprbots.ethereum.network.rlpx.*` - RLPx encrypted transport
- `com.chipprbots.ethereum.network.discovery.*` - Peer discovery

#### Sync Components (DEBUG - Supporting Diagnostics)
- `com.chipprbots.ethereum.blockchain.sync.*` - Core sync controller
- Regular sync components - DEBUG
- Blacklist - DEBUG (to investigate aggressive blacklisting)

All other components remain at INFO level for optimal performance.

## Directory Structure

```
ops/run-003/
├── conf/
│   ├── etc.conf          # Network configuration (ETC mainnet)
│   └── logback.xml       # Enhanced logging configuration
├── docker-compose.yml    # Docker Compose configuration
├── start.sh             # Quick start/stop script
└── README.md            # This file
```

## Usage

### Quick Start (Recommended)

Use the provided convenience script:

```bash
cd ops/run-003

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
   cd ops/run-003
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
   cp conf/etc.conf /path/to/fukuii/conf/
   cp conf/logback.xml /path/to/fukuii/conf/
   ```

2. Start Fukuii with the custom logback configuration:
   ```bash
   ./bin/fukuii etc -Dlogback.configurationFile=conf/logback.xml
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

When using Docker Compose, logs are stored in the `fukuii-run003-logs` volume. To access them:

```bash
docker compose exec fukuii ls -la /app/logs/
```

Or view directly:
```bash
docker compose exec fukuii tail -f /app/logs/fukuii.log
```

## Diagnostic Focus Areas

Based on the logging configuration and run-002 feedback, this setup enables detailed diagnostics for:

### 1. Snap Sync Protocol (PRIMARY)
- Account range downloads
- Storage range downloads
- Trie node healing
- Merkle proof verification

### 2. Peer Communication
- RLPx handshake process
- Message encoding/decoding
- Connection establishment
- Protocol negotiation
- Peer blacklisting behavior

### 3. Network Discovery
- Peer discovery process
- Node discovery service
- UDP peer groups

### 4. Sync Strategy
- Adaptive sync strategy selection
- Pivot block selection
- State synchronization
- Block fetching and importing

## Investigation Areas for Run 003

Based on run-002 notes, we're investigating:

1. **Automatic Sync Switching**: Whether the node automatically switches from fast sync to snap sync when enough blocks are retrieved
2. **Peer Blacklisting**: Whether we're being overly aggressive with blacklisting, leading to low peer counts
3. **Snap Sync Connection Issues**: Why connections appear to be terminated prior to receiving message traffic after snap sync requests
4. **Mainnet Peer Availability**: Testing whether ETC mainnet provides better peer availability than Mordor

## Configuration Adjustments from Base

This configuration overrides base.conf settings to be **less aggressive with peer blacklisting** and **focuses exclusively on SNAP sync testing** based on run-002 feedback:

### Sync Configuration
- `sync.do-fast-sync = false` (disabled - focusing on SNAP sync only)
- `sync.do-snap-sync = true` (enabled - primary focus of run-003)
- `sync.blacklist-duration = 60.seconds` (reduced from base 120s, originally 200s)
- `sync.critical-blacklist-duration = 30.minutes` (reduced from base 60 minutes, originally 240 minutes)
- `sync.peer-response-timeout = 60.seconds` (increased from base 45s, originally 30s)

**Note**: Fast sync is disabled because SNAP sync and fast sync are independent features with no interdependence. This allows pure SNAP sync testing without fallback to fast sync.

### Network Configuration
- `network.peer.update-nodes-interval = 60.seconds` (from base, increased from original 30s)
- `network.peer.short-blacklist-duration = 2.minutes` (reduced from base 3 minutes, originally 6 minutes)
- `network.peer.long-blacklist-duration = 30.minutes` (reduced from base 60 minutes, originally 600 minutes)

**Rationale**: Run-002 feedback indicated "we are being overly aggressive with blacklisting as we seemingly have low peer counts at all times". These adjustments allow faster peer retry and should maintain higher peer counts.

See `src/main/resources/conf/base.conf` in the repository for full base configuration details.

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
Verify the node is connected to ETC mainnet:
```bash
docker compose logs fukuii | grep -i "network\|etc"
```

### Investigating blacklisting behavior
Check blacklist activity (DEBUG enabled):
```bash
docker compose logs fukuii | grep -i "blacklist"
```

## Customization

### Changing Log Levels

Edit `conf/logback.xml` and change the level attribute for specific loggers:

```xml
<!-- Change from DEBUG to INFO to reduce verbosity -->
<logger name="com.chipprbots.ethereum.blockchain.sync" level="INFO" />
```

### Switching Networks

Edit `conf/etc.conf` to change the network:

```hocon
fukuii {
  blockchains {
    network = "mordor"  # Change back to Mordor testnet if needed
  }
}
```

⚠️ **Note**: This configuration uses ETC mainnet based on run-002 feedback. Only switch networks if you understand the implications.

## Comparison Table

| Aspect | Run 001 | Run 002 | Run 003 |
|--------|---------|---------|---------|
| Network | ETC Mainnet | Mordor Testnet | ETC Mainnet |
| Docker Image | `ghcr.io/...` | `chipprbots/fukuii-mordor:latest` | `ghcr.io/chippr-robotics/fukuii:latest` |
| FastSync Logging | INFO | DEBUG | INFO |
| SNAP Sync Logging | INFO | DEBUG | DEBUG |
| Network Logging | INFO | DEBUG | DEBUG |
| RLPx Logging | INFO | DEBUG | DEBUG |
| Discovery Logging | INFO | DEBUG | DEBUG |
| Container Name | fukuii-run-001 | fukuii-run-002 | fukuii-run-003 |
| Volume Names | fukuii-run001-* | fukuii-run002-* | fukuii-run003-* |
| Node ID | run-001 | run-002 | run-003 |

## Known Issues from Run 002

1. **Mordor Peer Discovery**: Mordor testnet had insufficient peers - ADDRESSED by switching to ETC mainnet
2. **FastSync Log Verbosity**: Too many DEBUG messages - ADDRESSED by reducing to INFO
3. **Automatic Sync Switching**: Need to investigate if node automatically switches from fast to snap - MONITORING
4. **Snap Sync Connection Termination**: Connections terminated before receiving responses - MONITORING
5. **Aggressive Blacklisting**: Possibly too aggressive, leading to low peer counts - MONITORING with DEBUG on Blacklist

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
