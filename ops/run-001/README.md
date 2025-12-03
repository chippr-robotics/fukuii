# Run 001 Configuration

This configuration sets up a Fukuii Ethereum Classic node with specific requirements:

## Configuration Details

### Network Configuration
- **Network**: Ethereum Classic (ETC)
- **Purpose**: Development/debugging environment for sync troubleshooting

### Logging Configuration
The following components have DEBUG-level logging enabled:

- **Sync Components** (all levels set to DEBUG):
  - `com.chipprbots.ethereum.blockchain.sync` - Core sync controller
  - `com.chipprbots.ethereum.blockchain.sync.SyncController`
  - `com.chipprbots.ethereum.blockchain.sync.AdaptiveSyncStrategy`
  - `com.chipprbots.ethereum.blockchain.sync.PeerRequestHandler`
  - `com.chipprbots.ethereum.blockchain.sync.PeersClient`
  - Fast sync components
  - Regular sync components

- **SNAP Sync Components** (all levels set to DEBUG):
  - `com.chipprbots.ethereum.blockchain.sync.snap` - SNAP sync controller
  - `com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController`
  - `com.chipprbots.ethereum.blockchain.sync.snap.AccountRangeDownloader`
  - `com.chipprbots.ethereum.blockchain.sync.snap.StorageRangeDownloader`
  - `com.chipprbots.ethereum.blockchain.sync.snap.TrieNodeHealer`
  - `com.chipprbots.ethereum.blockchain.sync.snap.MerkleProofVerifier`

All other components remain at INFO level for optimal performance.

## Directory Structure

```
ops/run-001/
├── conf/
│   ├── etc.conf          # Network configuration (ETC)
│   └── logback.xml       # Logging configuration (DEBUG for sync/snap)
├── docker-compose.yml    # Docker Compose deployment file
├── start.sh             # Quick start/stop script
└── README.md            # This file
```

## Usage

### Quick Start (Recommended)

Use the provided convenience script:

```bash
cd ops/run-001

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
   cd ops/run-001
   ```

2. Start the node:
   ```bash
   docker-compose up -d
   ```

3. View logs:
   ```bash
   docker-compose logs -f fukuii
   ```

4. Stop the node:
   ```bash
   docker-compose down
   ```

5. To remove all data (including blockchain data):
   ```bash
   docker-compose down -v
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

When using Docker Compose, logs are stored in the `fukuii-run001-logs` volume. To access them:

```bash
docker-compose exec fukuii ls -la /app/logs/
```

Or view directly:
```bash
docker-compose exec fukuii tail -f /app/logs/fukuii.log
```

## Performance Considerations

⚠️ **Warning**: DEBUG logging for sync and snap components generates significantly more log output than INFO level. This configuration is intended for:

- Development and debugging
- Troubleshooting sync issues
- Understanding sync and snap protocol behavior

**Not recommended for production use** due to:
- Increased disk I/O from verbose logging
- Larger log files requiring more storage
- Potential performance impact on sync speed

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
    network = "mordor"  # Change to mordor, eth, etc.
  }
}
```

## Troubleshooting

### Container won't start
Check logs:
```bash
docker-compose logs fukuii
```

### No peers connecting
1. Ensure ports 30303 (TCP/UDP) are open in your firewall
2. Check peer discovery logs (already at INFO level)

### Sync not progressing
With DEBUG logging enabled, examine the sync-related log entries to diagnose:
```bash
docker-compose logs fukuii | grep -i "sync\|snap"
```

## Related Documentation

- [Fukuii Documentation](https://chippr-robotics.github.io/fukuii/)
- [Operations Runbooks](../../docs/runbooks/README.md)
- [Log Triage Guide](../../docs/runbooks/log-triage.md)
- [Metrics & Monitoring](../../docs/operations/metrics-and-monitoring.md)

## Support

For issues or questions about this configuration, please refer to:
- [Known Issues](../../docs/runbooks/known-issues.md)
- [GitHub Issues](https://github.com/chippr-robotics/fukuii/issues)
