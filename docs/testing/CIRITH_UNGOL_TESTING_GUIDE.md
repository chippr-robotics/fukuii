# Cirith Ungol - Advanced Sync Testing with Mainnet and Mordor

> **"Bonus Trial"** - For advanced testers ready to validate sync capabilities with real networks

## Overview

**Cirith Ungol** (named after the treacherous pass in Mordor) is a single-node Docker container configuration designed for testing Fukuii's sync capabilities with **real networks** - ETC mainnet and Mordor testnet.

While Gorgoroth (see `ops/gorgoroth/README.md` in repository) provides a controlled private test network, Cirith Ungol allows you to test:
- **Fast Sync** with real network history (ETC mainnet: 20M+ blocks)
- **Snap Sync** with production network state
- **Long-term stability** with continuous sync operations
- **Network compatibility** with diverse peer implementations

## Why Use Cirith Ungol?

| Feature | Gorgoroth (Private Network) | Cirith Ungol (Public Networks) |
|---------|----------------------------|--------------------------------|
| **Purpose** | Multi-client compatibility | Real-world sync validation |
| **Network** | Private test network | ETC mainnet / Mordor testnet |
| **Block Count** | Starts from 0 | 20M+ blocks (mainnet) |
| **Peers** | Controlled (3-9 nodes) | Public network peers |
| **Sync Testing** | Limited history | Full sync capabilities |
| **Mining** | Yes (test mining) | No (observation only) |
| **Use Case** | Quick validation | Real-world scenarios |

## Prerequisites

- Docker 20.10+ with Docker Compose 2.0+
- **80GB+ free disk space** (for ETC mainnet)
- **20GB+ free disk space** (for Mordor testnet)
- Stable internet connection
- 4GB+ RAM

## Quick Start

### Option 1: ETC Mainnet Sync Testing

```bash
# Navigate to Cirith Ungol directory
cd ops/cirith-ungol

# Start node (defaults to SNAP sync on mainnet)
./start.sh start

# Monitor sync progress
./start.sh logs

# Collect logs for analysis
./start.sh collect-logs
```

### Option 2: Mordor Testnet Sync Testing

```bash
cd ops/cirith-ungol

# Edit docker-compose.yml to use Mordor configuration
# Change: -Dconfig.file=/app/conf/etc.conf
# To:     -Dconfig.file=/app/conf/mordor.conf

# Start node
docker compose up -d

# Monitor logs
docker compose logs -f
```

## Sync Mode Testing

Cirith Ungol supports testing different sync modes. Edit `conf/etc.conf` before starting:

### Test SNAP Sync (Recommended)

```hocon
fukuii.blockchain.sync {
  do-snap-sync = true
  do-fast-sync = false
}
```

**What to verify:**
- ✅ SNAP sync initiates and makes progress
- ✅ Account range requests succeed
- ✅ Storage range requests succeed
- ✅ Bytecode requests succeed
- ✅ Trie healing completes
- ✅ Final state verification passes

### Test Fast Sync

```hocon
fukuii.blockchain.sync {
  do-snap-sync = false
  do-fast-sync = true
}
```

**What to verify:**
- ✅ Fast sync starts at appropriate pivot block
- ✅ State nodes download completes
- ✅ Receipt validation succeeds
- ✅ Switches to full sync at completion

### Test Full Sync (Slow)

```hocon
fukuii.blockchain.sync {
  do-snap-sync = false
  do-fast-sync = false
}
```

**What to verify:**
- ✅ Processes all blocks from genesis
- ✅ All transactions executed
- ✅ Full state reconstruction
- ✅ Can serve as archive node

## Monitoring Sync Progress

### Check Sync Status via RPC

```bash
# Check if syncing
curl -X POST -H "Content-Type: application/json" \
  --data '{"jsonrpc":"2.0","method":"eth_syncing","params":[],"id":1}' \
  http://localhost:8545

# Get current block
curl -X POST -H "Content-Type: application/json" \
  --data '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}' \
  http://localhost:8545

# Get peer count
curl -X POST -H "Content-Type: application/json" \
  --data '{"jsonrpc":"2.0","method":"net_peerCount","params":[],"id":1}' \
  http://localhost:8545
```

### Monitor Logs

```bash
# Live log following
./start.sh logs

# Or with Docker Compose
docker compose logs -f

# Filter for sync-specific logs
docker logs fukuii-cirith-ungol 2>&1 | grep -i "snap\|sync\|progress"

# Check peer handshakes
docker logs fukuii-cirith-ungol 2>&1 | grep "PEER_HANDSHAKE_SUCCESS"
```

### Collect and Analyze Logs

```bash
# Collect all logs with analysis
./start.sh collect-logs

# This creates captured-logs/ directory with:
# - Full container logs
# - SNAP sync progress summary
# - Peer capability information
# - Error summaries
```

## Using with fukuii-cli

While fukuii-cli is designed for Gorgoroth multi-node management, you can use the Cirith Ungol scripts directly:

```bash
# From repository root
cd ops/cirith-ungol

# Start Cirith Ungol
./start.sh start

# Check status
./start.sh status

# View logs
./start.sh logs

# Stop when done
./start.sh stop

# Clean all data (to restart sync)
./start.sh clean
```

**Note**: Cirith Ungol uses its own management script (`start.sh`) rather than fukuii-cli because:
- Single node configuration (not a multi-node network)
- Different logging and monitoring needs
- Specialized for sync testing rather than network testing

## Validation Checklist

When testing with Cirith Ungol, validate:

### Network Communication
- [ ] Node discovers and connects to public peers
- [ ] Peer count increases to 10+ peers
- [ ] Handshakes complete successfully
- [ ] Protocol versions are compatible (eth/66, eth/67, snap/1)

### SNAP Sync (if enabled)
- [ ] SNAP sync phase starts
- [ ] Pivot block is selected
- [ ] Account ranges are downloaded
- [ ] Storage ranges are downloaded  
- [ ] Bytecodes are retrieved
- [ ] Trie healing completes
- [ ] Transitions to full sync

### Fast Sync (if enabled)
- [ ] Pivot block selected appropriately
- [ ] State nodes download progresses
- [ ] Headers are downloaded
- [ ] Blocks are downloaded
- [ ] Receipts are validated
- [ ] Switches to full sync

### Long-term Operation
- [ ] Node stays connected for 24+ hours
- [ ] Sync continues making progress
- [ ] No memory leaks observed
- [ ] No peer blacklisting issues
- [ ] Health check remains healthy

## Comparing Results

### Expected Sync Times

**ETC Mainnet (20M+ blocks):**
- SNAP Sync: 2-6 hours (depends on peers and network)
- Fast Sync: 6-12 hours
- Full Sync: Days to weeks (not recommended)

**Mordor Testnet (10M+ blocks):**
- SNAP Sync: 1-3 hours
- Fast Sync: 3-6 hours
- Full Sync: Days

### Success Criteria

For SNAP sync to be considered successful:
1. ✅ Initial SNAP sync phase completes
2. ✅ Transitions to full sync automatically
3. ✅ Can query recent block state
4. ✅ Account balances are queryable
5. ✅ Contract storage is accessible

## Troubleshooting

### Sync Stalls or Makes No Progress

**Symptoms**: Block number doesn't increase, no peer activity

**Solutions**:
1. Check peer count: Should have 10+ peers
   ```bash
   curl -s http://localhost:8545 -X POST -H "Content-Type: application/json" \
     --data '{"jsonrpc":"2.0","method":"net_peerCount","params":[],"id":1}'
   ```

2. Check for errors in logs:
   ```bash
   docker logs fukuii-cirith-ungol 2>&1 | grep -i "error\|timeout\|fail"
   ```

3. Restart with clean state:
   ```bash
   ./start.sh stop
   ./start.sh clean
   ./start.sh start
   ```

### Disk Space Issues

**Symptoms**: Container stops, "no space left" errors

**Solutions**:
1. Check available space:
   ```bash
   df -h
   docker system df
   ```

2. Prune Docker resources:
   ```bash
   docker system prune -a
   ```

3. Monitor space usage:
   ```bash
   docker exec fukuii-cirith-ungol du -sh /app/data
   ```

### Peer Connection Issues

**Symptoms**: Low peer count, frequent disconnections

**Solutions**:
1. Check firewall: Port 30303 should be open
   ```bash
   # Test if port is accessible
   nc -zv localhost 30303
   ```

2. Verify network connectivity:
   ```bash
   docker logs fukuii-cirith-ungol 2>&1 | grep "Listening on"
   ```

3. Check for blacklisted peers:
   ```bash
   docker logs fukuii-cirith-ungol 2>&1 | grep -i "blacklist"
   ```

### SNAP Sync Specific Issues

**Symptoms**: SNAP sync fails or times out

**Solutions**:
1. Verify SNAP is enabled in config
2. Check peer capabilities:
   ```bash
   docker logs fukuii-cirith-ungol 2>&1 | grep "Capability: SNAP"
   ```

3. Increase timeouts in `conf/etc.conf`:
   ```hocon
   fukuii.blockchain.sync {
     peer-response-timeout = 120.seconds
     snap-sync.request-timeout = 90.seconds
   }
   ```

## Configuration Reference

### Key Configuration Files

- `docker-compose.yml` - Container definition
- `conf/etc.conf` - ETC mainnet configuration
- `conf/mordor.conf` - Mordor testnet configuration (if exists)
- `conf/logback.xml` - Logging configuration

### Important Settings

```hocon
# Sync mode selection
fukuii.blockchain.sync {
  do-snap-sync = true   # Enable SNAP sync
  do-fast-sync = false  # Disable fast sync
  
  # SNAP sync tuning
  snap-sync {
    pivot-block-offset = 128
    request-timeout = 60.seconds
    max-concurrent-requests = 50
  }
  
  # Peer management
  peer-response-timeout = 90.seconds
  blacklist-duration = 60.seconds
}

# Logging levels
# Edit conf/logback.xml to adjust verbosity
```

## Integration with Gorgoroth Testing

Cirith Ungol complements Gorgoroth testing:

### Use Gorgoroth For:
- Multi-client compatibility validation
- Quick network communication tests
- Mining functionality testing
- Protocol compatibility checks

### Use Cirith Ungol For:
- Real-world sync performance
- Long-term stability testing
- Production network compatibility
- Sync mode validation (SNAP/Fast/Full)

### Recommended Testing Flow:

1. **Start with Gorgoroth** - Validate basic functionality
   ```bash
   cd ops/gorgoroth
   fukuii-cli start 3nodes
   cd test-scripts && ./run-test-suite.sh
   ```

2. **Move to Cirith Ungol** - Test real-world scenarios
   ```bash
   cd ops/cirith-ungol
   ./start.sh start
   # Wait for sync to complete (hours)
   ./start.sh collect-logs
   ```

3. **Report Results** - Document findings
   - Gorgoroth results: Multi-client compatibility
   - Cirith Ungol results: Sync performance and stability

## Reporting Results

When reporting Cirith Ungol test results, include:

### System Information
- OS and version
- Docker version
- Available disk space
- Network connection speed

### Test Configuration
- Network tested (mainnet/Mordor)
- Sync mode (SNAP/Fast/Full)
- Configuration changes made
- Test duration

### Sync Performance
- Start time and end time
- Blocks synced per hour
- Final block number reached
- Peer count average

### Issues Encountered
- Errors observed (with log snippets)
- Sync stalls or failures
- Performance issues
- Workarounds applied

### Template

```markdown
## Cirith Ungol Test Results

**Date**: YYYY-MM-DD
**Tester**: Your Name
**Network**: ETC Mainnet
**Sync Mode**: SNAP

### System Info
- OS: Ubuntu 22.04
- Docker: 24.0.6
- Disk: 100GB available
- Network: 100Mbps

### Results
- Start: 2025-12-08 10:00 UTC
- End: 2025-12-08 14:30 UTC (4.5 hours)
- Final Block: 20,150,000
- Avg Peers: 15
- Sync Rate: ~1.1M blocks/hour

### Status
- ✅ SNAP sync completed successfully
- ✅ Transitioned to full sync
- ✅ State queryable
- ⚠️ Had 2 timeout errors (recovered)

### Notes
- Used default configuration
- No custom tuning needed
- Stable after completion
```

## Advanced Usage

### Testing with Custom Bootnodes

Edit `conf/etc.conf`:

```hocon
fukuii.network.discovery {
  bootstrap-nodes = [
    "enode://custom-bootnode@host:port",
    # Add more bootnodes
  ]
}
```

### Monitoring with Prometheus

Expose metrics:

```hocon
fukuii.metrics {
  enabled = true
  port = 9095
}
```

Then scrape from `http://localhost:9095/metrics`

### Running Multiple Networks

Test both mainnet and Mordor simultaneously:

```bash
# Terminal 1: Mainnet
cd ops/cirith-ungol
./start.sh start

# Terminal 2: Mordor  
# Create cirith-ungol-mordor directory with modified config
# Run separate instance on different ports
```

## Related Documentation

- Gorgoroth Network Testing - see `ops/gorgoroth/README.md` (internal)
- [Gorgoroth Compatibility Testing](GORGOROTH_COMPATIBILITY_TESTING.md)
- [Fukuii Sync Documentation](../architecture/sync-modes.md)

## Support

For Cirith Ungol issues:
- Check logs with `./start.sh collect-logs`
- Review `ISSUE_RESOLUTION.md` for known issues
- Check existing GitHub issues
- Report new findings with log snippets

---

**Ready to begin?** Start with the Quick Start section above and choose your network!
