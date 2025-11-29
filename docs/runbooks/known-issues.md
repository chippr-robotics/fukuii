# Known Issues and Solutions

**Audience**: Operators troubleshooting common problems  
**Last Updated**: 2025-11-26  
**Status**: Living Document

## Overview

This document provides practical solutions for common operational scenarios with Fukuii. Each issue includes symptoms, causes, and step-by-step resolution guides. Most issues have straightforward solutions that can be applied quickly.

## Table of Contents

1. [RocksDB Operations](#rocksdb-issues)
2. [Temporary Directory Configuration](#temporary-directory-issues)
3. [JVM Optimization](#jvm-configuration-issues)
4. [Network Connectivity](#other-common-issues)
   - [Issue 13: Network Sync Zero-Length BigInteger](#issue-13-network-sync-error---zero-length-biginteger) ✅ Resolved
   - [Issue 14: ETH68 Peer Connections](#issue-14-eth68-peer-connection-failures) ✅ Resolved
   - [Issue 15: ForkId Compatibility](#issue-15-peer-disconnections-during-regular-sync) ✅ Resolved

## RocksDB Operations

RocksDB is a robust embedded key-value database used by Fukuii for blockchain data. This section covers common operational scenarios and their solutions.

### Issue 1: Database Recovery After Unclean Shutdown

**Severity**: High  
**Frequency**: Uncommon  
**Impact**: Node fails to start

#### Symptoms

```
ERROR [RocksDbDataSource] - Failed to open database
ERROR [RocksDbDataSource] - Corruption: block checksum mismatch
ERROR [RocksDbDataSource] - Corruption: bad magic number
```

#### Root Cause

- Power loss or system crash during write operations
- Disk errors or failing storage hardware
- Out-of-memory conditions during database writes
- Improper shutdown (SIGKILL instead of SIGTERM)

#### Workaround

**Option 1: Automatic repair** (try first)
```bash
# Simply restart - RocksDB will attempt auto-repair
./bin/fukuii etc
```

**Option 2: Manual database repair** (if auto-repair fails)

RocksDB can sometimes repair itself on restart. If not:

```bash
# Stop Fukuii
pkill -f fukuii

# Remove LOCK files (prevents "database is locked" errors)
find ~/.fukuii/etc/rocksdb/ -name "LOCK" -delete

# Remove WAL (Write-Ahead Log) if corrupted
# WARNING: Loses recent uncommitted transactions
# Only do this if node won't start
# rm -rf ~/.fukuii/etc/rocksdb/*/log/

# Restart
./bin/fukuii etc
```

**Option 3: Restore from backup**
```bash
# See backup-restore.md for detailed procedures
./restore-full.sh
```

**Option 4: Resync from genesis** (last resort)
```bash
# Backup keys first!
cp ~/.fukuii/etc/node.key ~/node.key.backup
cp -r ~/.fukuii/etc/keystore ~/keystore.backup

# Remove corrupted database
rm -rf ~/.fukuii/etc/rocksdb/

# Restore keys
cp ~/node.key.backup ~/.fukuii/etc/node.key
cp -r ~/keystore.backup ~/.fukuii/etc/keystore/

# Resync (takes days)
./bin/fukuii etc
```

#### Prevention (Recommended)

**Set up proper shutdown procedures**:

1. **Proper shutdown procedure**:
   ```bash
   # Use SIGTERM, not SIGKILL
   pkill -TERM -f fukuii
   # Or for systemd:
   systemctl stop fukuii
   # Or for Docker:
   docker stop fukuii  # Sends SIGTERM by default
   ```

2. **Enable journaling filesystem** (ext4 journal, XFS):
   ```bash
   # Verify journaling is enabled
   tune2fs -l /dev/sda1 | grep "Filesystem features" | grep -i journal
   ```

3. **Use UPS** (Uninterruptible Power Supply) for physical servers

4. **Regular backups**: See [backup-restore.md](backup-restore.md)

5. **Monitor disk health**:
   ```bash
   sudo smartctl -a /dev/sda | grep -i "health\|error"
   ```

---

### Issue 2: Optimizing RocksDB Performance

**Severity**: Medium  
**Frequency**: Common after months of operation  
**Impact**: Slow block imports, high disk I/O

#### Symptoms

```
WARN  [RocksDbDataSource] - Database operation took 5000ms (expected < 100ms)
INFO  [SyncController] - Block import rate: 5 blocks/second (down from 50+)
```

- Increasing disk usage despite stable blockchain size
- High disk I/O wait times
- Slower RPC queries

#### Root Cause

- **Compaction backlog**: LSM tree needs compaction but hasn't kept up
- **Write amplification**: Multiple rewrites of same data
- **Fragmentation**: SST files not optimally organized
- **Insufficient free space**: < 20% free prevents efficient compaction

#### Workaround

**Step 1: Verify disk space**
```bash
df -h ~/.fukuii/
# Should have > 20% free for optimal RocksDB performance
```

**Step 2: Allow compaction to complete**
```bash
# Check compaction status in logs
grep -i compact ~/.fukuii/etc/logs/fukuii.log | tail -20

# Compaction runs automatically but may take hours
# Monitor with:
watch -n 5 "du -sh ~/.fukuii/etc/rocksdb/*"
```

**Step 3: Force compaction** (if supported)

If Fukuii exposes a compaction trigger (check documentation):
```bash
# Example (may not exist):
# ./bin/fukuii cli compact-database
```

**Step 4: Offline compaction via restart**
```bash
# Stop node during low-traffic period
# RocksDB performs major compaction during startup
# May take 30-60 minutes
./bin/fukuii etc
```

#### Permanent Fix

**Prevention measures**:

1. **Maintain adequate free space** (30%+ recommended):
   ```bash
   # Monitor disk usage
   df -h ~/.fukuii/ | tail -1 | awk '{print $5}' | sed 's/%//'
   # Alert if > 70%
   ```

2. **Use SSD/NVMe storage**:
   - SST file compaction is I/O intensive
   - SSD dramatically improves compaction speed
   - HDD can create compaction backlog

3. **Allocate more resources**:
   - More CPU cores help parallel compaction
   - More RAM caches database operations

4. **Regular maintenance windows**:
   - Restart weekly/monthly during low activity
   - Allows full compaction cycle

5. **Monitor metrics**:
   ```bash
   # If metrics enabled:
   curl http://localhost:9095/metrics | grep rocksdb
   ```

#### Status

**Permanent**: Inherent to LSM tree architecture. Managed through proper resource allocation and maintenance.

---

### Issue 3: File Descriptor Configuration

**Severity**: High  
**Frequency**: Rare  
**Impact**: Node crashes or fails to start

#### Symptoms

```
ERROR [RocksDbDataSource] - Failed to open database
java.io.IOException: Too many open files
```

#### Root Cause

Linux file descriptor limit exceeded. RocksDB opens many SST files simultaneously.

#### Workaround

**Temporary fix** (current session):
```bash
# Increase limit for current session
ulimit -n 65536

# Restart Fukuii
./bin/fukuii etc
```

#### Permanent Fix

**For systemd service**:

Edit `/etc/systemd/system/fukuii.service`:
```ini
[Service]
LimitNOFILE=65536
```

Reload and restart:
```bash
sudo systemctl daemon-reload
sudo systemctl restart fukuii
```

**For user (persistent)**:

Edit `/etc/security/limits.conf`:
```
fukuii_user soft nofile 65536
fukuii_user hard nofile 65536
```

Log out and back in, verify:
```bash
ulimit -n  # Should show 65536
```

**For Docker**:
```bash
docker run -d \
  --ulimit nofile=65536:65536 \
  --name fukuii \
  ghcr.io/chippr-robotics/chordodes_fukuii:v1.0.0
```

Or in `docker-compose.yml`:
```yaml
services:
  fukuii:
    ulimits:
      nofile:
        soft: 65536
        hard: 65536
```

#### Status

**Fixed**: Set file descriptor limits to 65536 or higher.

---

## Temporary Directory Configuration

Fukuii and its JVM may use temporary directories for various operations. This section covers proper configuration for temp directories.

### Issue 4: Temp Space Configuration

**Severity**: Medium  
**Frequency**: Uncommon  
**Impact**: Node crashes or performance degradation

#### Symptoms

```
ERROR [JVM] - No space left on device: /tmp
WARN  [Fukuii] - Failed to create temporary file
java.io.IOException: No space left on device
```

- Node hangs or crashes unexpectedly
- Slow performance during heavy operations

#### Root Cause

- `/tmp` partition full
- Large temporary files not cleaned up
- Small `/tmp` partition size
- Excessive JVM temporary file usage

#### Workaround

**Immediate fix**:
```bash
# Check temp space
df -h /tmp

# Clean temp files (carefully)
sudo find /tmp -type f -atime +7 -delete  # Files older than 7 days
sudo rm -rf /tmp/hsperfdata_*  # JVM performance data
sudo rm -rf /tmp/java_*  # JVM temporary files
```

#### Permanent Fix

**Option 1: Increase /tmp size**

For tmpfs (RAM-based):
```bash
# Check current size
df -h /tmp

# Increase to 4GB (edit /etc/fstab)
tmpfs /tmp tmpfs defaults,size=4G 0 0

# Remount
sudo mount -o remount /tmp
```

**Option 2: Use dedicated temp directory**

```bash
# Create dedicated temp directory
sudo mkdir -p /var/tmp/fukuii
sudo chown fukuii_user:fukuii_group /var/tmp/fukuii
sudo chmod 700 /var/tmp/fukuii
```

Set in JVM options (`.jvmopts` or startup script):
```
-Djava.io.tmpdir=/var/tmp/fukuii
```

**Option 3: Automated cleanup**

Create systemd timer or cron job:
```bash
#!/bin/bash
# /usr/local/bin/cleanup-fukuii-temp.sh

TEMP_DIR=/var/tmp/fukuii
find "$TEMP_DIR" -type f -mtime +1 -delete  # Delete files older than 1 day
```

Cron:
```cron
0 2 * * * /usr/local/bin/cleanup-fukuii-temp.sh
```

#### Status

**Fixed**: Configure adequate temp space and automated cleanup.

---

### Issue 5: Temp Directory Permissions

**Severity**: Low  
**Frequency**: Rare  
**Impact**: Node fails to start or certain operations fail

#### Symptoms

```
ERROR [JVM] - Permission denied: /tmp/fukuii_xyz
java.io.IOException: Permission denied
```

#### Root Cause

- Temp directory not writable by Fukuii user
- SELinux or AppArmor restrictions
- `/tmp` mounted with `noexec` flag

#### Workaround

```bash
# Fix permissions
sudo chmod 1777 /tmp  # Standard /tmp permissions

# Or for dedicated temp:
sudo chown fukuii_user:fukuii_group /var/tmp/fukuii
sudo chmod 700 /var/tmp/fukuii
```

#### Permanent Fix

**Verify mount options**:
```bash
mount | grep /tmp
# Should NOT have 'noexec' if JVM needs to execute from temp
```

If `/tmp` has `noexec`, use dedicated temp directory (see Issue 4).

**Check SELinux** (if applicable):
```bash
# Check SELinux status
getenforce

# If enforcing, may need context change
# WARNING: Adjust path to match your actual temp directory
sudo semanage fcontext -a -t tmp_t "/var/tmp/fukuii(/.*)?"
sudo restorecon -R /var/tmp/fukuii
```

#### Status

**Fixed**: Ensure proper permissions and mount options.

---

## JVM Optimization

Fukuii runs on the JVM and benefits from proper tuning for optimal performance. This section covers recommended JVM configurations.

### Issue 6: Heap Size Configuration

**Severity**: High  
**Frequency**: Common with default settings  
**Impact**: Node crashes

#### Symptoms

```
ERROR [JVM] - java.lang.OutOfMemoryError: Java heap space
ERROR [JVM] - java.lang.OutOfMemoryError: Metaspace
ERROR [JVM] - java.lang.OutOfMemoryError: GC overhead limit exceeded
```

Node crashes, especially during:
- Initial sync
- Heavy RPC load
- Large block imports

#### Root Cause

- Heap size too small for workload
- Memory leak (rare)
- Metaspace exhaustion (many classes loaded)

#### Workaround

**Immediate fix**: Restart node (temporary relief)

#### Permanent Fix

**Increase heap size** (`.jvmopts` file):

Default:
```
-Xms1g
-Xmx4g
```

For 16 GB RAM system:
```
-Xms4g
-Xmx8g
-XX:ReservedCodeCacheSize=1024m
-XX:MaxMetaspaceSize=1g
-Xss4M
```

For 32 GB RAM system:
```
-Xms8g
-Xmx16g
-XX:ReservedCodeCacheSize=2048m
-XX:MaxMetaspaceSize=2g
-Xss4M
```

**Guidelines**:
- `-Xms` (initial) = `-Xmx` (max) for predictable behavior
- Heap should be 50-70% of available RAM
- Leave RAM for OS, RocksDB cache, and other processes
- Minimum 4 GB heap recommended
- 8-16 GB ideal for production

**For Docker**:
```bash
docker run -d \
  -e JAVA_OPTS="-Xms8g -Xmx16g" \
  --name fukuii \
  ghcr.io/chippr-robotics/chordodes_fukuii:v1.0.0
```

**Verify settings**:
```bash
ps aux | grep fukuii | grep -o -- '-Xm[sx][^ ]*'
```

#### Metaspace Issues

If specifically `OutOfMemoryError: Metaspace`:

```
-XX:MaxMetaspaceSize=2g  # Increase from 1g default
```

#### Status

**Fixed**: Configure adequate heap size based on available RAM.

---

### Issue 7: Garbage Collection Tuning

**Severity**: Medium  
**Frequency**: Common with large heaps  
**Impact**: Periodic unresponsiveness, slow sync

#### Symptoms

```
WARN  [GC] - GC pause: 5000ms
INFO  [GC] - Full GC (System.gc()) 8192M->6144M(8192M), 3.5 secs
```

- Periodic freezes (seconds)
- Delayed block imports
- RPC timeouts
- Peer disconnections

#### Root Cause

- Default garbage collector not optimal for large heaps
- Full GC triggered too frequently
- Heap size too small (constant GC pressure)

#### Workaround

Monitor GC activity:
```bash
# Enable GC logging (add to .jvmopts)
-Xlog:gc*:file=/var/log/fukuii-gc.log:time,level,tags
```

#### Permanent Fix

**Use G1GC** (recommended for heaps > 4GB):

Add to `.jvmopts`:
```
-XX:+UseG1GC
-XX:MaxGCPauseMillis=200
-XX:G1HeapRegionSize=32M
-XX:InitiatingHeapOccupancyPercent=45
```

**Or use ZGC** (JDK 21+, for large heaps and low latency):
```
-XX:+UseZGC
-XX:ZCollectionInterval=30
```

**Or use Shenandoah GC** (JDK 21+, alternative low-pause collector):
```
-XX:+UseShenandoahGC
```

**Tuning recommendations**:
- **Heap < 8GB**: Default or G1GC
- **Heap 8-32GB**: G1GC
- **Heap > 32GB**: ZGC or Shenandoah

**Additional tuning**:
```
# Reduce GC frequency by tuning thresholds
-XX:NewRatio=2  # New generation = 1/3 of heap
-XX:SurvivorRatio=8
```

#### Status

**Fixed**: Use appropriate garbage collector and tune parameters.

---

### Issue 8: Production JVM Configuration

**Severity**: Medium  
**Frequency**: Common without tuning  
**Impact**: Suboptimal performance

#### Symptoms

- Slower than expected block imports
- High CPU usage
- Frequent GC pauses
- Poor throughput

#### Root Cause

Default JVM settings not optimized for Fukuii's workload.

#### Permanent Fix

**Recommended production configuration** (`.jvmopts`):

```
# Heap settings (adjust based on available RAM)
-Xms8g
-Xmx8g

# Garbage Collection
-XX:+UseG1GC
-XX:MaxGCPauseMillis=200
-XX:G1HeapRegionSize=32M

# Code cache and metaspace
-XX:ReservedCodeCacheSize=1024m
-XX:MaxMetaspaceSize=1g

# Stack size
-Xss4M

# Performance optimizations
-XX:+UseStringDeduplication
-XX:+OptimizeStringConcat
-XX:+UseCompressedOops

# Monitoring (optional)
-XX:+UnlockDiagnosticVMOptions
-XX:+PrintFlagsFinal

# GC logging (for troubleshooting)
-Xlog:gc*:file=/var/log/fukuii-gc.log:time,level,tags

# JMX monitoring (optional, for debugging)
# -Dcom.sun.management.jmxremote
# -Dcom.sun.management.jmxremote.port=9999
# -Dcom.sun.management.jmxremote.authenticate=false
# -Dcom.sun.management.jmxremote.ssl=false
```

**For development** (faster compilation, more debugging):
```
-Xms2g
-Xmx4g
-XX:+UseG1GC
-XX:ReservedCodeCacheSize=512m
-XX:MaxMetaspaceSize=512m
```

#### Status

**Fixed**: Use optimized JVM configuration for production.

---

### Issue 9: JVM Version Compatibility

**Severity**: High  
**Frequency**: Rare  
**Impact**: Node fails to start

#### Symptoms

```
ERROR [Fukuii] - Unsupported Java version
ERROR [JVM] - UnsupportedClassVersionError
```

#### Root Cause

- Wrong JVM version (Fukuii requires JDK 21)
- Multiple JVM installations causing confusion

#### Workaround

```bash
# Check current Java version
java -version
# Should show: openjdk version "21.x.x" or similar

# Check which Java is being used
which java
update-alternatives --display java
```

#### Permanent Fix

**Install JDK 21**:
```bash
# Ubuntu/Debian
sudo apt-get update
sudo apt-get install openjdk-21-jdk

# Set as default
sudo update-alternatives --config java
# Select JDK 21

# Verify
java -version
```

**Explicitly set JAVA_HOME** (in startup script or environment):
```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export PATH=$JAVA_HOME/bin:$PATH
```

**For Docker**: Use official image which includes correct JDK version.

#### Status

**Fixed**: Ensure JDK 21 is installed and used.

---

## Network Connectivity

### Issue 10: Network Configuration

**Severity**: Medium  
**Frequency**: Common for new operators  
**Impact**: No peers, no sync

#### Symptoms

```
WARN  [PeerManagerActor] - Disconnected from peer: incompatible network
INFO  [PeerManagerActor] - Active peers: 0
```

All peers disconnect immediately after handshake.

#### Root Cause

Running on wrong network (e.g., trying to connect ETC node to ETH network).

#### Fix

**Verify correct network**:
```bash
# For ETC mainnet:
./bin/fukuii etc

# NOT:
# ./bin/fukuii eth  # This is Ethereum mainnet, not ETC
```

Check logs for network ID:
```bash
grep -i "network\|chain" ~/.fukuii/etc/logs/fukuii.log | head -10
```

#### Status

**User Error**: Ensure correct network specified at startup.

---

### Issue 11: Time Synchronization

**Severity**: Medium  
**Frequency**: Uncommon  
**Impact**: Peer issues, synchronization problems

#### Symptoms

```
WARN  [Discovery] - Message expired or clock skew detected
WARN  [PeerActor] - Peer timestamp out of acceptable range
```

#### Root Cause

System clock significantly different from network time.

#### Fix

**Check time synchronization**:
```bash
timedatectl status
# Should show: "System clock synchronized: yes"
```

**Enable NTP**:
```bash
# Ubuntu/Debian
sudo apt-get install ntp
sudo systemctl enable ntp
sudo systemctl start ntp

# Or use systemd-timesyncd
sudo systemctl enable systemd-timesyncd
sudo systemctl start systemd-timesyncd
```

**Force sync**:
```bash
sudo ntpdate pool.ntp.org
```

#### Status

**Fixed**: Enable and verify NTP time synchronization.

---

### Issue 12: Firewall Configuration

**Severity**: Medium  
**Frequency**: Common in security-hardened environments  
**Impact**: No incoming peers, slow peer discovery

#### Symptoms

```
INFO  [PeerManagerActor] - Active peers: 5 (all outgoing)
WARN  [ServerActor] - No incoming connections
```

#### Root Cause

Firewall blocking required ports (9076/TCP, 30303/UDP).

#### Fix

See [peering.md](peering.md#problem-only-outgoing-peers-no-incoming) and [first-start.md](first-start.md#configure-firewall).

#### Status

**Configuration**: Open required ports in firewall.

---

### Issue 13: Network Sync Zero-Length BigInteger ✅

**Status**: Fixed in v1.0.1

#### Summary

This issue was caused by incorrect handling of empty byte arrays in the RLP serialization layer. The fix ensures empty byte arrays correctly deserialize to zero, per Ethereum specification.

#### Symptoms (for reference)

```
ERROR [o.a.pekko.actor.OneForOneStrategy] - Zero length BigInteger
java.lang.NumberFormatException: Zero length BigInteger
        at java.base/java.math.BigInteger.<init>(BigInteger.java:...)
```

#### Technical Details

- **Location**: `src/main/scala/com/chipprbots/ethereum/domain/package.scala`
- **Affected component**: `ArbitraryIntegerMpt.bigIntSerializer.fromBytes`
- **Root cause**: Did not handle empty byte arrays before calling `BigInt(bytes)`

The fix:
```scala
// Before:
override def fromBytes(bytes: Array[Byte]): BigInt = BigInt(bytes)

// After:
override def fromBytes(bytes: Array[Byte]): BigInt = 
  if (bytes.isEmpty) BigInt(0) else BigInt(bytes)
```

**Test coverage added**: 21+ tests covering all serialization paths.

See commit `afc0626` for full implementation details.

---

### Issue 14: ETH68 Peer Connections ✅

**Status**: Fixed in current release

#### Summary

This issue was caused by incorrect message decoder ordering. Network protocol messages must be decoded before capability-specific messages per the devp2p specification.

#### Symptoms (for reference)

```
DEBUG [c.c.e.n.p2p.MessageDecoder$$anon$1] - Unknown eth/68 message type: 1
INFO  [c.c.e.n.rlpx.RLPxConnectionHandler] - Cannot decode message from <peer-ip>:30303, because of Cannot decode Disconnect
```

#### Technical Details

- **Location**: `src/main/scala/com/chipprbots/ethereum/network/rlpx/RLPxConnectionHandler.scala`
- **Root cause**: ETH68 decoder tried to decode network messages first

The fix:
```scala
// Before:
val md = EthereumMessageDecoder.ethMessageDecoder(negotiated).orElse(NetworkMessageDecoder)

// After:
val md = NetworkMessageDecoder.orElse(EthereumMessageDecoder.ethMessageDecoder(negotiated))
```

See commit `801b236` for full implementation details.

---

## Getting Help

If you encounter an issue not documented here:

1. **Search existing issues**: https://github.com/chippr-robotics/fukuii/issues
2. **Collect information**:
   - Fukuii version
   - Operating system and version
   - JVM version
   - Relevant log excerpts
   - Steps to reproduce
3. **Open new issue**: Provide detailed report with above information

## Contributing to This Document

This is a living document. Your contributions help everyone! If you:
- Find a solution to an issue
- Discover a new operational pattern
- Have improved configurations

Please submit a pull request or open an issue to update this documentation.

---

### Issue 15: ForkId Compatibility ✅

**Status**: Fixed in current release

#### Summary

This issue was caused by incompatible ForkId values being advertised during ETH64+ protocol handshake for nodes starting from low block numbers.

#### Symptoms (for reference)

```
INFO  [c.c.e.n.handshaker.EthNodeStatus64ExchangeState] - STATUS_EXCHANGE: Sending status - bestBlock=1234
INFO  [c.c.e.n.PeerManagerActor] - Handshaked 0/80, pending connection attempts 15
INFO  [c.c.e.b.sync.PivotBlockSelector] - Cannot pick pivot block. Need at least 3 peers, but there are only 0
```

#### Technical Details

- **Location**: `src/main/scala/com/chipprbots/ethereum/network/handshaker/EthNodeStatus64ExchangeState.scala`
- **Root cause**: Bootstrap pivot block only used when `bestBlockNumber == 0`

The fix extends bootstrap pivot usage for ForkId calculation during initial sync:

```scala
// Use bootstrap pivot for ForkId during initial sync
val forkIdBlockNumber = if (bootstrapPivotBlock > 0) {
  val threshold = math.min(bootstrapPivotBlock / 10, BigInt(100000))
  if (bestBlockNumber < (bootstrapPivotBlock - threshold)) bootstrapPivotBlock
  else bestBlockNumber
} else bestBlockNumber
```

**Benefits:**
- Bootstrap pivot used for ForkId calculation during entire initial sync
- Smooth transition from pivot to actual block number when close to synced
- Both regular sync and fast sync now maintain stable peer connections

See [CON-006: ForkId Compatibility During Initial Sync](../adr/consensus/CON-006-forkid-compatibility-during-initial-sync.md) for details.

---

**Document Version**: 1.2  
**Last Updated**: 2025-11-26  
**Maintainer**: Chippr Robotics LLC
