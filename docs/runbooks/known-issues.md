# Known Issues

**Audience**: Operators troubleshooting common problems  
**Last Updated**: 2025-11-04  
**Status**: Living Document

## Overview

This document catalogs known issues, their symptoms, root causes, workarounds, and permanent fixes for Fukuii operations. It focuses on three main areas: RocksDB database issues, temporary directory problems, and JVM configuration issues.

## Table of Contents

1. [RocksDB Issues](#rocksdb-issues)
2. [Temporary Directory Issues](#temporary-directory-issues)
3. [JVM Configuration Issues](#jvm-configuration-issues)
4. [Other Common Issues](#other-common-issues)
   - [Issue 13: Network Sync Error - Zero Length BigInteger](#issue-13-network-sync-error---zero-length-biginteger)

## RocksDB Issues

RocksDB is the embedded key-value database used by Fukuii to store blockchain data. While robust, it can encounter issues under certain conditions.

### Issue 1: RocksDB Corruption After Unclean Shutdown

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

#### Permanent Fix

**Prevention measures**:

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

#### Status

**Permanent**: This is inherent to write-ahead logging systems. Mitigation through proper shutdown procedures and backups.

---

### Issue 2: RocksDB Performance Degradation Over Time

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

### Issue 3: RocksDB "Too Many Open Files"

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

## Temporary Directory Issues

Fukuii and its JVM may use temporary directories for various operations. Issues can arise when temp directories are full, have incorrect permissions, or are cleaned up by system maintenance.

### Issue 4: Insufficient Temp Space

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

## JVM Configuration Issues

Fukuii runs on the JVM and requires proper tuning for optimal performance. Common issues relate to heap size, garbage collection, and other JVM flags.

### Issue 6: OutOfMemoryError

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

### Issue 7: Long Garbage Collection Pauses

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

### Issue 8: Poor Performance with Default JVM Flags

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

## Other Common Issues

### Issue 10: Network ID Mismatch

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

### Issue 11: Clock Skew

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

### Issue 12: Firewall Blocking Connections

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

### Issue 13: Network Sync Error - Zero Length BigInteger

**Severity**: High  
**Frequency**: Intermittent during network sync  
**Impact**: Node crashes or fails to sync
**Status**: Fixed in v1.0.1

#### Symptoms

```
ERROR [o.a.pekko.actor.OneForOneStrategy] - Zero length BigInteger
java.lang.NumberFormatException: Zero length BigInteger
        at java.base/java.math.BigInteger.<init>(BigInteger.java:...)
```

- Error occurs intermittently during network sync
- Most common on Mordor testnet but can occur on any network
- Node may crash or fail to process certain blocks
- State storage operations may fail

#### Root Cause

The `ArbitraryIntegerMpt.bigIntSerializer` in the domain package was calling Scala's `BigInt(bytes)` constructor, which delegates to Java's `BigInteger` constructor. According to the Ethereum RLP specification, an empty byte array represents the integer zero. However, Java's `BigInteger` constructor throws `NumberFormatException: Zero length BigInteger` when given an empty byte array.

**Technical Details**:
- **Location**: `src/main/scala/com/chipprbots/ethereum/domain/package.scala`
- **Affected component**: `ArbitraryIntegerMpt.bigIntSerializer.fromBytes`
- **Issue**: Did not handle empty byte arrays before calling `BigInt(bytes)`
- **Spec violation**: Ethereum RLP spec requires empty byte string = integer 0

#### Ethereum Specification Context

According to the [Ethereum RLP specification](https://ethereum.org/en/developers/docs/data-structures-and-encoding/rlp/):
- Integer 0 is encoded as an empty byte string (0x80 in RLP)
- Empty byte arrays must decode to zero
- This is critical for state storage where zero values are valid

The bug occurred because:
1. RLP layer correctly handled empty arrays (using `foldLeft`)
2. ArbitraryIntegerMpt (internal storage) used direct `BigInt(bytes)` constructor
3. During network sync, zero values in state storage caused the exception

#### Workaround

**Temporary mitigation** (before fix):
- No reliable workaround available
- Restarting node may temporarily help but issue recurs
- Avoid syncing from scratch on affected networks

#### Permanent Fix

**Applied in commit**: `afc0626`

Modified `ArbitraryIntegerMpt.bigIntSerializer.fromBytes` to handle empty byte arrays:

```scala
// Before (buggy):
override def fromBytes(bytes: Array[Byte]): BigInt = BigInt(bytes)

// After (fixed):
override def fromBytes(bytes: Array[Byte]): BigInt = 
  if (bytes.isEmpty) BigInt(0) else BigInt(bytes)
```

This aligns with:
- Ethereum RLP specification (empty byte string = zero)
- Ethereum Yellow Paper (Appendix B - RLP encoding)
- devp2p RLPx protocol requirements
- Existing RLP implementation in fukuii

#### Prevention & Testing

**Test coverage added**:
- 7 tests in `ArbitraryIntegerMptSpec` for zero/empty value handling
- 3 tests in `RLPSuite` for BigInt edge cases
- 21 tests in new `BigIntSerializationSpec` covering:
  - Empty byte array deserialization
  - Zero value round-trip serialization
  - Network sync edge cases
  - Ethereum spec compliance (0x80 encoding)
  - All serialization paths (RLP, ArbitraryIntegerMpt, ByteUtils)

**Documentation**:
- Detailed specification compliance documented
- Root cause analysis included
- All serialization paths verified

#### Verification

After applying fix, verify with:

```bash
# Run comprehensive test suite
sbt "testOnly com.chipprbots.ethereum.domain.BigIntSerializationSpec"
sbt "testOnly com.chipprbots.ethereum.domain.ArbitraryIntegerMptSpec"
sbt "rlp / testOnly com.chipprbots.ethereum.rlp.RLPSuite"

# Sync from scratch on Mordor testnet (regression test)
./bin/fukuii-launcher mordor
# Should complete without NumberFormatException
```

#### Related Issues

- Similar pattern in `ByteUtils.toBigInt` - already correctly used `foldLeft`
- Similar pattern in RLP layer - already correctly handled empty arrays
- UInt256 construction - uses safe `ByteUtils.toBigInt` path

#### Impact Assessment

**Before fix**:
- Network sync could fail intermittently
- State storage corruption possible with zero values
- Consensus divergence risk if nodes handled zero differently

**After fix**:
- Full Ethereum specification compliance
- Reliable network sync on all networks
- Consistent zero value handling across all serialization paths

#### References

1. [Ethereum RLP Specification](https://ethereum.org/en/developers/docs/data-structures-and-encoding/rlp/)
2. [Ethereum Yellow Paper - Appendix B](https://ethereum.github.io/yellowpaper/paper.pdf)
3. [devp2p RLPx Protocol](https://github.com/ethereum/devp2p/blob/master/rlpx.md)
4. [Ethereum Execution Specs](https://github.com/ethereum/execution-specs)
5. Java BigInteger JavaDoc: Empty arrays not supported

#### Status

**Fixed**: v1.0.1 and later include the fix. Update to latest version or apply patch manually.

---

## Reporting New Issues

If you encounter an issue not documented here:

1. **Search existing issues**: https://github.com/chippr-robotics/fukuii/issues
2. **Collect information**:
   - Fukuii version
   - Operating system and version
   - JVM version
   - Relevant log excerpts
   - Steps to reproduce
3. **Open new issue**: Provide detailed report with above information
4. **Workaround if found**: Document temporarily until fix is released

## Contributing to This Document

This is a living document. If you:
- Find a solution to an issue
- Discover a new issue
- Have improved workarounds

Please submit a pull request or open an issue to update this documentation.

---

**Document Version**: 1.1  
**Last Updated**: 2025-11-04  
**Maintainer**: Chippr Robotics LLC
