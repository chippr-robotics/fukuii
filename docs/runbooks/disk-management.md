# Disk Management Runbook

**Audience**: Operators managing storage and database growth  
**Estimated Time**: 30-60 minutes  
**Prerequisites**: Running Fukuii node, basic Linux administration

## Overview

This runbook covers managing Fukuii's disk usage, including database growth, pruning strategies, disk space monitoring, and optimization techniques. Proper disk management is critical for long-term node operation.

## Table of Contents

1. [Understanding Storage Layout](#understanding-storage-layout)
2. [Disk Space Requirements](#disk-space-requirements)
3. [Monitoring Disk Usage](#monitoring-disk-usage)
4. [Pruning and Database Management](#pruning-and-database-management)
5. [Optimization Strategies](#optimization-strategies)
6. [Troubleshooting](#troubleshooting)

## Understanding Storage Layout

### Default Directory Structure

Default data directory: `~/.fukuii/<network>/`

```
~/.fukuii/etc/
├── node.key                    # Node's private key (~100 bytes)
├── keystore/                   # Encrypted account keys (~1 KB per key)
│   └── UTC--2024...            
├── logs/                       # Application logs (~10 MB per file, max 50 files)
│   ├── fukuii.log
│   └── fukuii.*.log.zip
├── rocksdb/                    # Blockchain database (main storage consumer)
│   ├── blockchain/             # Block headers, bodies, receipts (~200-400 GB for ETC)
│   │   ├── 000001.sst
│   │   ├── MANIFEST-000001
│   │   └── ...
│   └── state/                  # World state data (~50-100 GB)
│       ├── 000001.sst
│       └── ...
├── knownNodes.json             # Discovered peers (~50 KB)
└── app-state.json              # Node state (~1 KB)
```

### Storage Breakdown

Typical space consumption for ETC mainnet (as of 2025):

| Component | Size | Growth Rate | Can Prune |
|-----------|------|-------------|-----------|
| Block headers | ~10-20 GB | ~2 GB/year | No |
| Block bodies | ~150-300 GB | ~30 GB/year | No |
| Receipts | ~20-40 GB | ~4 GB/year | Yes* |
| World state | ~50-100 GB | ~10 GB/year | Yes |
| Logs | ~500 MB | Capped | Yes |
| Other | ~1 GB | Minimal | N/A |
| **Total** | **~230-460 GB** | **~46 GB/year** | Partial |

*Note: Receipt pruning may impact certain RPC queries

### RocksDB Storage Engine

Fukuii uses RocksDB, a high-performance key-value store:

- **Log-Structured Merge (LSM) tree** architecture
- **SST files** - Immutable sorted string tables
- **Compaction** - Background process that merges and removes old data
- **Compression** - Data is compressed (typically Snappy or LZ4)
- **Write-Ahead Log (WAL)** - Ensures durability

## Disk Space Requirements

### Minimum Requirements

- **Initial sync**: 500 GB
- **Operational margin**: 20% free space (critical for RocksDB performance)
- **Recommended minimum**: 650 GB total capacity

### Recommended Requirements

- **Storage**: 1 TB SSD/NVMe
- **Free space target**: 30-40% free
- **IOPS**: 10,000+ (SSD/NVMe strongly recommended over HDD)

### Future Growth Planning

| Year | Estimated Size (ETC) | Recommended Storage |
|------|---------------------|---------------------|
| 2025 | 400 GB | 650 GB |
| 2026 | 450 GB | 750 GB |
| 2027 | 500 GB | 850 GB |
| 2028 | 550 GB | 1 TB |

**Note**: Growth rates depend on network activity and may vary.

## Monitoring Disk Usage

### Check Current Usage

```bash
# Check total disk space
df -h ~/.fukuii/

# Check data directory size
du -sh ~/.fukuii/etc/

# Check database size breakdown
du -sh ~/.fukuii/etc/rocksdb/*
```

Expected output:
```
Filesystem      Size  Used Avail Use% Mounted on
/dev/sda1       1.0T  350G  650G  35% /

350G    /home/user/.fukuii/etc/
300G    /home/user/.fukuii/etc/rocksdb/blockchain/
45G     /home/user/.fukuii/etc/rocksdb/state/
500M    /home/user/.fukuii/etc/logs/
```

### Monitor Growth Over Time

Create a monitoring script:

```bash
#!/bin/bash
# monitor-disk.sh

DATADIR=~/.fukuii/etc
LOG_FILE=/var/log/fukuii-disk-usage.log

echo "$(date) - Disk usage report" >> $LOG_FILE
df -h $DATADIR >> $LOG_FILE
du -sh $DATADIR/* >> $LOG_FILE
echo "---" >> $LOG_FILE
```

Schedule with cron:
```bash
# Run daily at 2 AM
0 2 * * * /path/to/monitor-disk.sh
```

### Set Up Alerts

Alert when disk usage exceeds threshold:

```bash
#!/bin/bash
# check-disk-space.sh

THRESHOLD=80
USAGE=$(df -h ~/.fukuii/ | grep -v Filesystem | awk '{print $5}' | sed 's/%//')

if [ $USAGE -gt $THRESHOLD ]; then
    echo "WARNING: Disk usage is at ${USAGE}%"
    # Send alert (email, Slack, PagerDuty, etc.)
    # Example: mail -s "Fukuii Disk Alert" admin@example.com <<< "Disk usage: ${USAGE}%"
fi
```

### Using Prometheus Metrics

If metrics are enabled:

```bash
# Check disk metrics
curl http://localhost:9095/metrics | grep disk
```

Example Prometheus alert:
```yaml
- alert: HighDiskUsage
  expr: node_filesystem_avail_bytes{mountpoint="/data"} / node_filesystem_size_bytes < 0.2
  for: 10m
  annotations:
    summary: "Disk space low on Fukuii node"
    description: "Less than 20% disk space remaining"
```

## Pruning and Database Management

### Understanding Pruning Modes

Fukuii supports different pruning strategies:

1. **Archive Mode** (No Pruning)
   - Keeps all historical state
   - Required for full historical queries
   - Largest disk usage (~500+ GB)
   - Use case: Block explorers, analytics

2. **Basic Pruning** (Default)
   - Keeps recent state + some history
   - Balances storage and functionality
   - Moderate disk usage (~300-400 GB)
   - Use case: General operation, mining

3. **Aggressive Pruning** (Manual)
   - Minimal historical state
   - Reduces disk usage significantly
   - Limited historical queries
   - Use case: Resource-constrained environments

### Current Pruning Status

Check your node's pruning configuration:

```bash
# Check configuration
grep -i prune ~/.fukuii/etc/logs/fukuii.log | head -5

# Or check config files
grep -r "pruning" src/main/resources/conf/
```

### Manual Database Compaction

RocksDB performs automatic compaction, but you can trigger manual compaction if needed.

**Warning**: Manual compaction is intensive and may impact performance.

```bash
# Stop the node first
# Compaction happens automatically during normal operation
# To force compaction on next start, delete LOG files (RocksDB will rebuild)

# Backup first!
# Then restart the node - RocksDB will compact during startup
```

### Cleaning Logs

Logs are automatically rotated but you can manually clean old logs:

```bash
# Keep only last 10 log files
cd ~/.fukuii/etc/logs/
ls -t fukuii.*.log.zip | tail -n +11 | xargs rm -f

# Or delete all archived logs (keep current)
rm -f fukuii.*.log.zip
```

### Removing Orphaned Data

After crashes or unclean shutdowns:

```bash
# Stop Fukuii

# Remove RocksDB lock files (if stuck)
rm ~/.fukuii/etc/rocksdb/*/LOCK

# Remove WAL logs (if corrupted - will lose recent uncommitted data)
# DANGER: Only do this if database won't start
# rm -rf ~/.fukuii/etc/rocksdb/*/log/

# Restart Fukuii
```

## Optimization Strategies

### 1. Use SSD/NVMe Storage

**Impact**: 10-100x performance improvement over HDD

```bash
# Check your disk type
lsblk -d -o name,rota
# ROTA=1 means HDD, ROTA=0 means SSD
```

**Migration to SSD**:
```bash
# Stop Fukuii
# Copy data to SSD
sudo rsync -avh --progress ~/.fukuii/ /mnt/ssd/fukuii/
# Update datadir in config or create symlink
ln -sf /mnt/ssd/fukuii ~/.fukuii
# Start Fukuii
```

### 2. Enable Compression

RocksDB compression is enabled by default, but verify:

Compression reduces disk usage by 50-70% with minimal CPU overhead.

**Check compression in logs**:
```bash
grep -i compress ~/.fukuii/etc/logs/fukuii.log
```

### 3. Adjust RocksDB Options

For advanced users, RocksDB can be tuned via JVM options.

Create/edit `.jvmopts` in your installation directory:

```bash
# NOTE: RocksDB tuning in Fukuii is typically done through internal configuration,
# not JVM properties. The examples below are HYPOTHETICAL and for illustration only.

# For actual RocksDB tuning options, consult:
# - Configuration files: ~/.fukuii/etc/*.conf or src/main/resources/conf/base.conf
# - Fukuii source: src/main/scala/com/chipprbots/ethereum/db/dataSource/RocksDbDataSource.scala
# - RocksDB documentation: https://github.com/facebook/rocksdb/wiki/RocksDB-Tuning-Guide

# Example (may not be supported):
# -Drocksdb.write_buffer_size=67108864  # 64 MB
# -Drocksdb.max_background_jobs=4

# Actual RocksDB tuning depends on Fukuii's implementation.
```

**Warning**: Improper tuning can degrade performance. Test in non-production first.

### 4. Separate Data and Logs

For better I/O performance:

```bash
# Move logs to different disk
mkdir /var/log/fukuii
ln -sf /var/log/fukuii ~/.fukuii/etc/logs
```

Configure in `custom.conf`:
```hocon
logging {
  logs-dir = "/var/log/fukuii"
}
```

### 5. Use RAID or LVM

For large deployments:

**RAID 0** (striping):
- 2x+ performance
- No redundancy
- Good for: Performance-critical nodes (with backups)

**RAID 10** (mirrored stripe):
- 2x performance
- Redundancy
- Good for: Production nodes

**LVM**:
- Easy expansion
- Snapshots for backups
- Good for: Flexible storage management

### 6. Monitor I/O Performance

```bash
# Monitor I/O in real-time
iostat -x 1

# Check for disk bottlenecks
iotop -o  # Shows processes causing I/O

# Check disk latency
sudo hdparm -Tt /dev/sda
```

**Healthy metrics**:
- Avg response time: < 10 ms for SSD
- Queue depth: < 10
- Utilization: < 80%

## Troubleshooting

### Problem: Disk Full

**Symptoms**:
- Node crashes or freezes
- Errors: `No space left on device`
- Database corruption

**Immediate Actions**:

1. **Check disk space**
   ```bash
   df -h ~/.fukuii/
   ```

2. **Free up space quickly**
   ```bash
   # Clean logs
   rm -f ~/.fukuii/etc/logs/fukuii.*.log.zip
   
   # Clean system temp
   sudo rm -rf /tmp/*
   ```

3. **Move data to larger disk** (see migration steps above)

**Prevention**:
- Set up disk usage alerts
- Plan for growth
- Implement log rotation

### Problem: Database Corruption

**Symptoms**:
- Node won't start
- Errors mentioning RocksDB corruption
- Blockchain data mismatch

**Diagnostic**:
```bash
# Check logs for corruption errors
grep -i "corrupt\|error" ~/.fukuii/etc/logs/fukuii.log | tail -20
```

**Recovery Options**:

**Option 1: Let RocksDB auto-repair**
```bash
# Often RocksDB can self-repair on restart
# Simply restart the node
./bin/fukuii etc
```

**Option 2: Manual repair** (if built-in repair exists)
```bash
# Check if Fukuii has a repair command
./bin/fukuii --help | grep repair
```

**Option 3: Restore from backup**
```bash
# Stop node
# Restore from backup (see backup-restore.md)
# Restart node
```

**Option 4: Resync from genesis**
```bash
# Last resort - delete database and resync
# Backup node key first!
cp ~/.fukuii/etc/node.key ~/node.key.backup

# Remove database
rm -rf ~/.fukuii/etc/rocksdb/

# Restart - will resync from genesis
./bin/fukuii etc
```

See [known-issues.md](known-issues.md) for RocksDB-specific issues.

### Problem: Slow Database Performance

**Symptoms**:
- Slow block imports (< 10 blocks/second)
- High disk latency
- Slow RPC queries

**Diagnostic**:

1. **Check disk type**
   ```bash
   lsblk -d -o name,rota,size,model
   ```

2. **Check I/O wait**
   ```bash
   top
   # Look at "%wa" (I/O wait) - should be < 20%
   ```

3. **Check disk health**
   ```bash
   # For SSD
   sudo smartctl -a /dev/sda | grep -i "health\|error"
   ```

**Solutions**:

1. **Upgrade to SSD** (most impactful)
2. **Reduce concurrent operations** - Adjust JVM options
3. **Check for competing I/O** - Stop other disk-heavy processes
4. **Verify no disk errors** - Replace failing drives
5. **Enable write caching** (if safe):
   ```bash
   sudo hdparm -W1 /dev/sda  # Enable write cache
   ```

### Problem: Database Growing Too Fast

**Symptoms**:
- Disk usage increasing faster than expected
- Frequent "low space" warnings

**Causes**:
- Not enough free space for compaction
- WAL files accumulating
- Log files not rotating

**Solutions**:

1. **Verify log rotation is working**
   ```bash
   ls -lh ~/.fukuii/etc/logs/
   # Should see rotated logs: fukuii.1.log.zip, etc.
   ```

2. **Check for WAL file accumulation**
   ```bash
   find ~/.fukuii/etc/rocksdb/ -name "*.log" -ls
   # A few WAL files is normal, hundreds indicates a problem
   ```

3. **Ensure sufficient free space**
   - RocksDB needs 20%+ free space to compact efficiently
   - Expand storage if consistently above 80% usage

## Best Practices

### For All Deployments

1. **Monitor disk usage weekly** - Catch issues early
2. **Maintain 20%+ free space** - Critical for RocksDB performance
3. **Use SSD/NVMe** - Essential for acceptable performance
4. **Set up alerts** - Automate monitoring
5. **Regular backups** - Protect against corruption (see [backup-restore.md](backup-restore.md))
6. **Plan for growth** - Budget for storage expansion

### For Production Nodes

1. **Use redundant storage** - RAID 10 or equivalent
2. **Monitor SMART data** - Predict disk failures
3. **Have spare capacity** - Replace disks proactively
4. **Document storage layout** - Maintain runbook
5. **Test disaster recovery** - Verify backups work
6. **Capacity planning** - Review every 6 months

### For Development/Test Nodes

1. **Smaller storage OK** - Can resync if needed
2. **Use test networks** - Mordor has smaller blockchain
3. **Prune aggressively** - Save space
4. **Snapshot for quick recovery** - VM snapshots

## Related Runbooks

- [First Start](first-start.md) - Initial storage setup and configuration
- [Backup & Restore](backup-restore.md) - Data protection and recovery
- [Known Issues](known-issues.md) - RocksDB-specific problems and solutions
- [Log Triage](log-triage.md) - Diagnosing disk-related errors

## Further Reading

- [RocksDB Tuning Guide](https://github.com/facebook/rocksdb/wiki/RocksDB-Tuning-Guide)
- [RocksDB FAQ](https://github.com/facebook/rocksdb/wiki/RocksDB-FAQ)
- [Linux I/O Monitoring](https://www.brendangregg.com/linuxperf.html)

---

**Document Version**: 1.0  
**Last Updated**: 2025-11-02  
**Maintainer**: Chippr Robotics LLC
