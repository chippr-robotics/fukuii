# Log Triage Runbook

**Audience**: Operators diagnosing issues and troubleshooting via logs  
**Estimated Time**: 15-45 minutes per issue  
**Prerequisites**: Access to Fukuii logs

## Overview

This runbook covers log configuration, analysis techniques, and troubleshooting common issues through log examination. Logs are your primary diagnostic tool for understanding node behavior and identifying problems.

## Table of Contents

1. [Log Configuration](#log-configuration)
2. [Log Locations and Structure](#log-locations-and-structure)
3. [Understanding Log Levels](#understanding-log-levels)
4. [Common Log Patterns](#common-log-patterns)
5. [Troubleshooting by Category](#troubleshooting-by-category)
6. [Log Analysis Tools](#log-analysis-tools)
7. [Best Practices](#best-practices)

## Log Configuration

### Default Configuration

Fukuii uses Logback for logging, configured in `src/main/resources/logback.xml`.

**Default settings**:
- **Format**: Text with timestamp, level, logger name, and message
- **Console**: INFO level and above
- **File**: All levels (configurable)
- **Rotation**: 10 MB per file, max 50 files
- **Location**: `~/.fukuii/<network>/logs/`

### Configuring Log Levels

Log levels can be set via application configuration:

**Via application.conf**:
```hocon
logging {
  logs-dir = ${user.home}"/.fukuii/"${fukuii.blockchains.network}"/logs"
  logs-file = "fukuii"
  logs-level = "INFO"  # Options: TRACE, DEBUG, INFO, WARN, ERROR
  json-output = false
}
```

**Via environment variable** (if supported):
```bash
export FUKUII_LOG_LEVEL=DEBUG
./bin/fukuii etc
```

**Via JVM system property**:
```bash
./bin/fukuii -Dlogging.logs-level=DEBUG etc
```

### Adjusting Specific Logger Levels

Edit your configuration or create a custom `logback.xml`:

```xml
<configuration>
    <!-- ... other config ... -->
    
    <!-- Set specific package to DEBUG -->
    <logger name="com.chipprbots.ethereum.blockchain.sync" level="DEBUG" />
    
    <!-- Reduce verbose logger -->
    <logger name="io.netty" level="WARN"/>
    
    <!-- Silence very verbose logger -->
    <logger name="com.chipprbots.ethereum.vm.VM" level="OFF" />
</configuration>
```

### Enabling JSON Logging

For structured logging (useful for log aggregation tools like ELK, Splunk):

```hocon
logging {
  json-output = true
}
```

Restart Fukuii to apply changes.

### Log Rotation

Rotation is automatic with default settings:

- **Size-based**: Rolls over at 10 MB
- **Retention**: Keeps 50 archived logs
- **Compression**: Archives are compressed (.zip)
- **Naming**: `fukuii.1.log.zip`, `fukuii.2.log.zip`, etc.

To adjust, modify `logback.xml`:

```xml
<rollingPolicy class="ch.qos.logback.core.rolling.FixedWindowRollingPolicy">
    <fileNamePattern>${LOGSDIR}/${LOGSFILENAME}.%i.log.zip</fileNamePattern>
    <minIndex>1</minIndex>
    <maxIndex>100</maxIndex>  <!-- Keep 100 files instead of 50 -->
</rollingPolicy>
<triggeringPolicy class="ch.qos.logback.core.rolling.SizeBasedTriggeringPolicy">
    <maxFileSize>50MB</maxFileSize>  <!-- 50 MB instead of 10 MB -->
</triggeringPolicy>
```

## Log Locations and Structure

### Log File Locations

**Binary installation**:
```
~/.fukuii/etc/logs/
├── fukuii.log              # Current log
├── fukuii.1.log.zip        # Most recent archive
├── fukuii.2.log.zip
└── ...
```

**Docker installation**:
```bash
# View logs
docker logs fukuii

# Follow logs
docker logs -f fukuii

# Export logs to file
docker logs fukuii > fukuii.log 2>&1
```

**Systemd service**:
```bash
# View logs
journalctl -u fukuii

# Follow logs
journalctl -u fukuii -f

# Export logs
journalctl -u fukuii --no-pager > fukuii.log
```

### Log Entry Format

**Standard format**:
```
2025-11-02 10:30:45 INFO  [com.chipprbots.ethereum.Fukuii] - Starting Fukuii client version: 1.0.0
│                   │     │                                   │
│                   │     │                                   └─ Message
│                   │     └─ Logger name (class/package)
│                   └─ Log level
└─ Timestamp
```

**JSON format** (when enabled):
```json
{
  "timestamp": "2025-11-02T10:30:45.123Z",
  "level": "INFO",
  "logger": "com.chipprbots.ethereum.Fukuii",
  "message": "Starting Fukuii client version: 1.0.0",
  "hostname": "node01"
}
```

## Understanding Log Levels

### Log Level Hierarchy

```
TRACE < DEBUG < INFO < WARN < ERROR
```

When you set a level, you see that level and all higher levels.

### Level Descriptions

| Level | Description | When to Use | Volume |
|-------|-------------|-------------|--------|
| **ERROR** | Critical failures | Production - always monitor | Low |
| **WARN** | Potential issues | Production - should investigate | Low-Medium |
| **INFO** | Important events | Production - normal operations | Medium |
| **DEBUG** | Detailed diagnostic info | Development/troubleshooting | High |
| **TRACE** | Very detailed execution flow | Deep debugging only | Very High |

### Typical Production Setup

```
Root level: INFO
Specific troubleshooting: DEBUG for relevant packages
Performance-critical paths: WARN or OFF (e.g., VM execution)
```

## Common Log Patterns

### Healthy Node Startup

```
INFO  [Fukuii] - Starting Fukuii client version: 1.0.0
INFO  [NodeBuilder] - Fixing database...
INFO  [GenesisDataLoader] - Loading genesis data...
INFO  [GenesisDataLoader] - Genesis data loaded successfully
INFO  [NodeBuilder] - Starting peer manager...
INFO  [ServerActor] - Server bound to /0.0.0.0:9076
INFO  [NodeBuilder] - Starting server...
INFO  [DiscoveryService] - Discovery service started on port 30303
INFO  [NodeBuilder] - Starting sync controller...
INFO  [SyncController] - Starting blockchain synchronization
INFO  [NodeBuilder] - Starting JSON-RPC HTTP server on 0.0.0.0:8546...
INFO  [JsonRpcHttpServer] - JSON-RPC HTTP server listening on 0.0.0.0:8546
INFO  [Fukuii] - Fukuii started successfully
```

### Normal Operation Logs

```
INFO  [PeerManagerActor] - Connected to peer: Peer(...)
INFO  [SyncController] - Imported 100 blocks in 5.2 seconds
INFO  [BlockBroadcaster] - Broadcasted block #12345678 to 25 peers
INFO  [PendingTransactionsManager] - Added transaction 0xabc...
```

### Warning Signs (Need Attention)

```
WARN  [PeerManagerActor] - Disconnected from peer: handshake timeout
WARN  [SyncController] - No suitable peers for synchronization
WARN  [RocksDbDataSource] - Compaction took longer than expected: 120s
WARN  [PeerActor] - Received unknown message type from peer
```

### Error Indicators (Immediate Action Needed)

```
ERROR [ServerActor] - Failed to bind to port 9076: Address already in use
ERROR [RocksDbDataSource] - Database corruption detected
ERROR [BlockImporter] - Failed to execute block: insufficient gas
ERROR [Fukuii] - Fatal error during startup
```

## Troubleshooting by Category

### Startup Issues

#### Problem: Port Already in Use

**Log pattern**:
```
ERROR [ServerActor] - Failed to bind to port 9076
java.net.BindException: Address already in use
```

**Diagnosis**:
```bash
# Check what's using the port
sudo lsof -i :9076
sudo netstat -tulpn | grep 9076
```

**Solution**:
```bash
# Kill conflicting process or change Fukuii port
# Change port in config:
# fukuii.network.server-address.port = 9077
```

See: [first-start.md](first-start.md#troubleshooting)

#### Problem: Database Corruption

**Log pattern**:
```
ERROR [RocksDbDataSource] - Failed to open database
ERROR [RocksDbDataSource] - Corruption: ...
```

**Solution**: See [known-issues.md](known-issues.md#rocksdb-corruption)

#### Problem: Genesis Data Load Failure

**Log pattern**:
```
ERROR [GenesisDataLoader] - Failed to load genesis data
ERROR [GenesisDataLoader] - Invalid genesis configuration
```

**Diagnosis**:
```bash
# Check genesis file exists and is valid
ls -l ~/.fukuii/etc/blockchain.conf
```

**Solution**:
- Ensure correct network specified (etc, eth, mordor)
- Verify genesis configuration files are present
- Check for file corruption

### Synchronization Issues

#### Problem: Slow or Stalled Sync

**Log pattern**:
```
INFO  [SyncController] - Current block: 1000000, Target: 15000000
# No progress for extended period
```

**Diagnosis**:
```bash
# Check recent import activity
grep "Imported.*blocks" ~/.fukuii/etc/logs/fukuii.log | tail -20

# Check peer count
grep "peer count" ~/.fukuii/etc/logs/fukuii.log | tail -5
```

**Common causes**:
1. **No peers**: See [peering.md](peering.md)
2. **Disk I/O bottleneck**: See [disk-management.md](disk-management.md)
3. **Network issues**: Check bandwidth, latency

**Solution**:
```bash
# Enable DEBUG logging for sync
# In config: logging.logs-level = "DEBUG"
# Or specific: <logger name="com.chipprbots.ethereum.blockchain.sync" level="DEBUG" />

# Monitor for detailed sync info
tail -f ~/.fukuii/etc/logs/fukuii.log | grep -i sync
```

#### Problem: Block Import Failures

**Log pattern**:
```
ERROR [BlockImporter] - Failed to execute block 12345678
ERROR [BlockImporter] - Invalid block: state root mismatch
```

**Diagnosis**: This may indicate:
- Database corruption
- Bug in EVM implementation
- Fork incompatibility

**Solution**:
1. Check Fukuii version is up-to-date
2. Review recent hard forks - may need upgrade
3. Verify database integrity (see [disk-management.md](disk-management.md))
4. Report issue with block number to maintainers

### Network and Peering Issues

#### Problem: No Peers

**Log pattern**:
```
WARN  [PeerManagerActor] - No peers available
INFO  [PeerManagerActor] - Active peers: 0
```

**Diagnosis**:
```bash
# Check discovery is enabled
grep "discovery" ~/.fukuii/etc/logs/fukuii.log | tail -10

# Check for connection errors
grep -i "connection\|peer" ~/.fukuii/etc/logs/fukuii.log | grep -i error | tail -20
```

**Solution**: See [peering.md](peering.md#troubleshooting-connectivity)

#### Problem: Peers Disconnecting

**Log pattern**:
```
WARN  [PeerManagerActor] - Disconnected from peer: incompatible network
WARN  [PeerActor] - Peer handshake timeout
INFO  [PeerManagerActor] - Blacklisted peer: ...
```

**Analysis**:
```bash
# Count disconnect reasons
grep "Disconnected from peer" ~/.fukuii/etc/logs/fukuii.log | \
  cut -d: -f3 | sort | uniq -c | sort -rn
```

**Common reasons**:
- `incompatible network` - Wrong network/fork
- `handshake timeout` - Network latency or peer overload
- `protocol error` - Peer misbehavior or version incompatibility

**Solution**: Usually normal - node filters incompatible peers. If excessive (> 50% disconnect rate), see [peering.md](peering.md#problem-high-peer-churn)

#### Problem: Network ID Mismatch (All Peers Disconnecting)

**Log pattern**:
```
INFO  [PeerManagerActor] - Total number of discovered nodes 29. Handshaked 0/80
DEBUG [PeerActor] - Message received: Status { networkId: 1, ... }
DEBUG [RLPxConnectionHandler] - Sent message: Status { networkId: 61, ... }
INFO  [CacheBasedBlacklist] - Blacklisting peer [...] for 36000000 milliseconds
DEBUG [PeersClient] - Total handshaked peers: 0, Available peers (not blacklisted): 0
```

**Diagnosis**:
```bash
# Check network ID in status messages
grep "networkId" ~/.fukuii/etc/logs/fukuii.log | head -20

# Check if all peers are being blacklisted
grep "Blacklisting peer" ~/.fukuii/etc/logs/fukuii.log | wc -l

# Verify configured network
grep "Using network" ~/.fukuii/etc/logs/fukuii.log
```

**Root Cause**: Node configured for one network (e.g., Ethereum Classic with networkId: 61) but discovering peers from another network (e.g., Ethereum mainnet with networkId: 1). This typically indicates wrong bootstrap nodes in configuration.

**Solution**: 
1. Update bootstrap nodes to match your network (ETC, not ETH)
2. Clear peer database: `rm -rf ~/.fukuii/etc/discovery/ ~/.fukuii/etc/nodeDatabase/`
3. Restart node

**Detailed Analysis**: See [Network Mismatch Log Analysis](../analysis/network-mismatch-log-analysis.md) for comprehensive troubleshooting steps

### RPC and API Issues

#### Problem: RPC Not Responding

**Log pattern**:
```
# No JSON-RPC startup message, or:
ERROR [JsonRpcHttpServer] - Failed to start HTTP server
```

**Diagnosis**:
```bash
# Check if RPC server started
grep "JSON-RPC" ~/.fukuii/etc/logs/fukuii.log

# Test RPC endpoint
curl -X POST --data '{"jsonrpc":"2.0","method":"web3_clientVersion","params":[],"id":1}' \
  http://localhost:8546
```

**Solution**:
- Verify RPC is enabled in configuration
- Check port is not in use
- Review firewall rules

#### Problem: RPC Errors

**Log pattern**:
```
ERROR [EthService] - Error executing RPC call
ERROR [EthService] - Method not found: xyz
```

**Analysis**: Check which RPC methods are failing:
```bash
grep "RPC\|JSON-RPC" ~/.fukuii/etc/logs/fukuii.log | grep ERROR
```

### Performance Issues

#### Problem: High Memory Usage

**Log pattern**:
```
WARN  [JvmMemory] - Heap memory usage: 95%
ERROR [JVM] - OutOfMemoryError: Java heap space
```

**Diagnosis**:
```bash
# Check current memory usage
ps aux | grep fukuii
jps -lvm | grep fukuii

# Check JVM settings
cat .jvmopts
```

**Solution**: See [known-issues.md](known-issues.md#jvm-memory-tuning)

#### Problem: Slow Performance

**Log pattern**:
```
WARN  [RocksDbDataSource] - Database operation took 5000ms (expected < 100ms)
WARN  [SyncController] - Block import rate: 2 blocks/second (expected 50+)
```

**Diagnosis**:
```bash
# Check for disk I/O warnings
grep -i "slow\|took.*ms\|performance" ~/.fukuii/etc/logs/fukuii.log

# System diagnostics
iostat -x 1 10
top
```

**Solution**: See [disk-management.md](disk-management.md#optimization-strategies)

### Database Issues

#### Problem: RocksDB Errors

**Log pattern**:
```
ERROR [RocksDbDataSource] - RocksDB error: ...
ERROR [RocksDbDataSource] - Failed to write batch
WARN  [RocksDbDataSource] - Compaction pending
```

**Solution**: See [known-issues.md](known-issues.md#rocksdb-issues)

## Log Analysis Tools

### Basic Command-Line Tools

**Search for errors**:
```bash
grep ERROR ~/.fukuii/etc/logs/fukuii.log | tail -50
```

**Count log levels**:
```bash
awk '{print $3}' ~/.fukuii/etc/logs/fukuii.log | sort | uniq -c
```

**Find recent activity**:
```bash
tail -f ~/.fukuii/etc/logs/fukuii.log
```

**Search archived logs**:
```bash
zgrep "pattern" ~/.fukuii/etc/logs/fukuii.*.log.zip
```

**Time-range analysis**:
```bash
# Logs from last hour
awk -v d=$(date -d '1 hour ago' '+%Y-%m-%d %H') '$0 ~ d' ~/.fukuii/etc/logs/fukuii.log
```

**Extract stack traces**:
```bash
# Find exceptions with context
grep -A 20 "Exception" ~/.fukuii/etc/logs/fukuii.log
```

### Advanced Analysis Scripts

**Summarize issues**:
```bash
#!/bin/bash
# log-summary.sh

LOG_FILE=~/.fukuii/etc/logs/fukuii.log

echo "=== Log Summary ==="
echo "Total lines: $(wc -l < $LOG_FILE)"
echo ""
echo "=== Log Levels ==="
awk '{print $3}' "$LOG_FILE" | sort | uniq -c | sort -rn
echo ""
echo "=== Top Errors ==="
grep ERROR "$LOG_FILE" | awk -F'\\[|\\]' '{print $2}' | sort | uniq -c | sort -rn | head -10
echo ""
echo "=== Recent Errors ==="
grep ERROR "$LOG_FILE" | tail -10
```

**Monitor specific patterns**:
```bash
#!/bin/bash
# monitor-logs.sh

tail -f ~/.fukuii/etc/logs/fukuii.log | while read line; do
    if echo "$line" | grep -q "ERROR"; then
        echo "🔴 $line"
    elif echo "$line" | grep -q "WARN"; then
        echo "🟡 $line"
    elif echo "$line" | grep -q "Imported.*blocks"; then
        echo "✅ $line"
    fi
done
```

**Performance metrics extraction**:
```bash
# Extract block import rates
grep "Imported.*blocks" ~/.fukuii/etc/logs/fukuii.log | \
  awk '{print $1, $2, $6, $7, $8, $9}' | tail -20
```

### Log Aggregation Tools

For production environments:

**1. ELK Stack (Elasticsearch, Logstash, Kibana)**
```bash
# Enable JSON logging in Fukuii
# Configure Logstash to read fukuii.log
# Visualize in Kibana
```

**2. Grafana Loki**
```bash
# Configure Promtail to scrape logs
# Query with LogQL in Grafana
```

**3. Splunk**
```bash
# Configure Splunk forwarder
# Index Fukuii logs
# Create dashboards
```

**4. CloudWatch / Stackdriver**
```bash
# Use CloudWatch agent (AWS) or Logging agent (GCP)
# Stream logs to cloud logging service
```

## Best Practices

### Logging Strategy

1. **Production**: INFO level by default
2. **Troubleshooting**: DEBUG for specific packages
3. **Development**: DEBUG or TRACE
4. **Performance testing**: WARN or ERROR only

### Log Retention

1. **Keep logs for troubleshooting window**: 7-30 days typical
2. **Archive old logs**: Compress and move to long-term storage
3. **Automate cleanup**: Prevent disk exhaustion

```bash
# Clean logs older than 30 days
find ~/.fukuii/etc/logs/ -name "fukuii.*.log.zip" -mtime +30 -delete
```

### Monitoring and Alerting

Set up alerts for:

```bash
# Critical errors
grep -c "ERROR" fukuii.log > threshold

# Startup failures
grep "Fatal error" fukuii.log

# Peer connectivity
grep "No peers available" fukuii.log

# Database issues
grep "RocksDB.*error\|corruption" fukuii.log
```

### Log Rotation Best Practices

1. **Size-based rotation**: 10-50 MB per file
2. **Retention count**: 50-100 files
3. **Compression**: Always enable
4. **Monitoring**: Alert if logs stop rotating (may indicate hang)

### Security Considerations

1. **Restrict access**: `chmod 640 ~/.fukuii/etc/logs/*`
2. **No sensitive data**: Avoid logging private keys, passwords
3. **Audit logging**: Enable for production nodes
4. **Secure storage**: Protect log archives

### Debugging Workflow

1. **Identify symptoms**: What's not working?
2. **Check recent logs**: Look for errors around symptom time
3. **Increase verbosity**: Enable DEBUG for relevant packages
4. **Reproduce issue**: Observe logs during reproduction
5. **Analyze patterns**: Look for correlations
6. **Test hypothesis**: Make changes, observe results
7. **Document findings**: Update runbooks

### Log Analysis Checklist

When investigating an issue:

- [ ] Check latest log entries for errors
- [ ] Review startup sequence for anomalies
- [ ] Verify all services started successfully
- [ ] Check for resource warnings (memory, disk)
- [ ] Review peer connectivity messages
- [ ] Look for patterns (timing, frequency)
- [ ] Check archived logs if issue is historical
- [ ] Compare with known good logs
- [ ] Search for similar issues in documentation
- [ ] Correlate with system metrics (CPU, disk, network)

## Related Runbooks

- [First Start](first-start.md) - Initial setup and startup logs
- [Peering](peering.md) - Network and peer-related logs
- [Disk Management](disk-management.md) - Database and storage logs
- [Known Issues](known-issues.md) - Common log patterns and solutions
- [Log Analysis Reports](../analysis/) - Detailed analysis of production incidents and operational issues

### Example Analysis Reports

- **[Network Mismatch Log Analysis](../analysis/network-mismatch-log-analysis.md)** - Analysis of a sync failure caused by network ID mismatch (ETC node discovering ETH mainnet peers), including root cause analysis and bootstrap node configuration fixes
- **[Sync Process Log Analysis](../analysis/sync-process-log-analysis.md)** - Analysis of a complete sync failure due to peer connection issues, including ForkId validation failures and remediation steps

---

**Document Version**: 1.1  
**Last Updated**: 2025-11-10  
**Maintainer**: Chippr Robotics LLC
