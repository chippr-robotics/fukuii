# SNAP Sync Performance Tuning Guide

**Audience**: Advanced node operators and performance engineers  
**Estimated Time**: 30-45 minutes  
**Prerequisites**: Understanding of SNAP sync basics, system performance tuning, and monitoring

## Overview

This guide provides advanced performance tuning strategies for optimizing SNAP sync on Fukuii nodes. While the default configuration works well for most deployments, specific environments may benefit from fine-tuning based on network conditions, hardware capabilities, and operational requirements.

## Performance Objectives

SNAP sync performance can be optimized for different objectives:

- **Minimize sync time**: Fastest possible synchronization
- **Minimize resource usage**: Lower CPU, memory, disk I/O, and network bandwidth
- **Maximize stability**: Reliable sync with minimal failures
- **Balance**: Good performance across all metrics (default)

## Hardware Optimization

### CPU

**Impact**: Affects Merkle proof verification and RLP encoding/decoding

**Recommendations by CPU Type:**

| CPU Type | account-concurrency | storage-concurrency | Notes |
|----------|---------------------|---------------------|-------|
| 2 cores | 8 | 4 | Minimum for SNAP sync |
| 4 cores | 16 (default) | 8 (default) | Balanced performance |
| 8+ cores | 32 | 16 | Maximum throughput |
| High-end (16+ cores) | 32 | 16 | No benefit beyond 32/16 |

**Configuration:**
```hocon
snap-sync {
  # High-end CPU (8+ cores)
  account-concurrency = 32
  storage-concurrency = 16
  
  # Low-end CPU (2 cores)
  account-concurrency = 8
  storage-concurrency = 4
}
```

**CPU Affinity** (Linux):
```bash
# Pin Fukuii to specific CPU cores for better cache locality
taskset -c 0-7 ./bin/fukuii -Dconfig.file=conf/fukuii.conf
```

### Memory

**Impact**: Affects LRU cache effectiveness and garbage collection

**Recommendations by RAM:**

| RAM | JVM Heap | Cache Size | Notes |
|-----|----------|------------|-------|
| 4 GB | 2 GB | 5,000 | Minimum (may cause GC pauses) |
| 8 GB | 4 GB (default) | 10,000 (default) | Balanced |
| 16 GB | 8 GB | 20,000 | Improved caching |
| 32+ GB | 16 GB | 40,000 | Maximum benefit |

**JVM Configuration:**
```bash
# 8 GB heap for 16 GB RAM system
export JAVA_OPTS="-Xms8g -Xmx8g -XX:+UseG1GC -XX:MaxGCPauseMillis=200"
./bin/fukuii -Dconfig.file=conf/fukuii.conf
```

**Garbage Collection Tuning:**
```bash
# G1GC with aggressive tuning for low latency
export JAVA_OPTS="-Xms8g -Xmx8g \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=200 \
  -XX:G1HeapRegionSize=16m \
  -XX:G1ReservePercent=15 \
  -XX:InitiatingHeapOccupancyPercent=45 \
  -XX:+ParallelRefProcEnabled"
```

**Cache Size Tuning** (requires code modification):

Current LRU cache is fixed at 10,000 entries. For systems with more memory:

```scala
// In StorageRangeDownloader.scala
// Change from:
private val storageTrieCache = new StorageTrieCache(10000)
// To:
private val storageTrieCache = new StorageTrieCache(20000)
```

### Disk

**Impact**: Critical for state storage performance

**Recommendations by Disk Type:**

| Disk Type | Expected SNAP Sync Time | Optimization |
|-----------|------------------------|--------------|
| HDD (7200 RPM) | 12-24 hours | Not recommended |
| SATA SSD | 3-6 hours | Good |
| NVMe SSD | 2-4 hours | Excellent (recommended) |
| NVMe RAID 0 | 1.5-3 hours | Maximum performance |

**Disk I/O Optimization:**

```bash
# Check current disk scheduler
cat /sys/block/nvme0n1/queue/scheduler

# Set to 'none' for NVMe (best performance)
echo none | sudo tee /sys/block/nvme0n1/queue/scheduler

# Set to 'deadline' for SSD
echo deadline | sudo tee /sys/block/sda/queue/scheduler

# Disable access time updates
sudo mount -o remount,noatime,nodiratime /data
```

**File System Tuning:**

```bash
# XFS (recommended for blockchain data)
sudo mkfs.xfs -f -d agcount=4 /dev/nvme0n1
sudo mount -o noatime,nodiratime,largeio,inode64,swalloc /dev/nvme0n1 /data

# ext4 (alternative)
sudo mkfs.ext4 /dev/nvme0n1
sudo mount -o noatime,nodiratime,data=writeback,barrier=0 /dev/nvme0n1 /data
```

### Network

**Impact**: Affects download throughput and peer quality

**Bandwidth Requirements:**

| Network Speed | SNAP Sync Performance | Peer Count | Notes |
|---------------|----------------------|------------|-------|
| < 10 Mbps | Poor (8-12 hours) | 5-10 | Minimum |
| 10-50 Mbps | Good (4-6 hours) | 10-20 | Typical home/office |
| 50-100 Mbps | Excellent (2-3 hours) | 20-30 | Data center |
| 100+ Mbps | Maximum (1.5-2 hours) | 30+ | High-end |

**Network Tuning:**

```bash
# Increase TCP buffer sizes
sudo sysctl -w net.core.rmem_max=134217728
sudo sysctl -w net.core.wmem_max=134217728
sudo sysctl -w net.ipv4.tcp_rmem="4096 87380 134217728"
sudo sysctl -w net.ipv4.tcp_wmem="4096 65536 134217728"

# Enable TCP BBR congestion control (Linux 4.9+)
sudo sysctl -w net.ipv4.tcp_congestion_control=bbr
sudo sysctl -w net.core.default_qdisc=fq

# Increase connection tracking
sudo sysctl -w net.netfilter.nf_conntrack_max=1000000
```

## Configuration Tuning

### Concurrency Optimization

**Goal**: Maximize parallelism without overwhelming peers

**Testing Methodology:**

1. Start with default (16/8)
2. Monitor throughput (accounts/sec, slots/sec)
3. Increase by 50% (24/12)
4. Re-measure and compare
5. Continue until no improvement or errors increase

**Example Tuning Session:**

```hocon
# Baseline (default)
snap-sync {
  account-concurrency = 16
  storage-concurrency = 8
}
# Result: 5,000 accounts/sec, 100,000 slots/sec

# Test 1: Increase by 50%
snap-sync {
  account-concurrency = 24
  storage-concurrency = 12
}
# Result: 7,000 accounts/sec, 140,000 slots/sec (40% improvement)

# Test 2: Double original
snap-sync {
  account-concurrency = 32
  storage-concurrency = 16
}
# Result: 8,500 accounts/sec, 170,000 slots/sec (21% improvement)

# Test 3: Triple original
snap-sync {
  account-concurrency = 48
  storage-concurrency = 24
}
# Result: 8,600 accounts/sec, 175,000 slots/sec (1% improvement)
# Conclusion: Diminishing returns, stay with 32/16
```

**Optimal Settings by Environment:**

```hocon
# Data center with excellent network and hardware
snap-sync {
  account-concurrency = 32
  storage-concurrency = 16
  storage-batch-size = 16
  healing-batch-size = 32
  timeout = 20 seconds
}

# Standard cloud VM (8 vCPU, 16 GB RAM)
snap-sync {
  account-concurrency = 16
  storage-concurrency = 8
  storage-batch-size = 8
  healing-batch-size = 16
  timeout = 30 seconds
}

# Resource-constrained environment (4 vCPU, 8 GB RAM)
snap-sync {
  account-concurrency = 8
  storage-concurrency = 4
  storage-batch-size = 4
  healing-batch-size = 8
  timeout = 45 seconds
}
```

### Timeout Optimization

**Goal**: Balance quick failure detection with network latency

**Factors to Consider:**
- Peer geographic distribution (local vs global)
- Network latency (ping times)
- Response size (larger = longer timeout)

**Timeout Recommendations:**

| Network Conditions | Recommended Timeout | Notes |
|-------------------|---------------------|-------|
| Low latency (<50ms) | 15-20 seconds | Data center, same region |
| Medium latency (50-150ms) | 30 seconds (default) | Typical internet |
| High latency (>150ms) | 45-60 seconds | Intercontinental peers |
| Satellite/mobile | 90-120 seconds | High latency, variable |

**Configuration:**
```hocon
snap-sync {
  # Low latency environment
  timeout = 20 seconds
  
  # High latency environment
  timeout = 60 seconds
}
```

### Retry Strategy

**Goal**: Recover from transient failures without wasting time

**Configuration:**
```hocon
snap-sync {
  max-retries = 3  # Default
  # Exponential backoff: 1s, 2s, 4s
}
```

**Tuning for Different Environments:**

```hocon
# Stable network with good peers
snap-sync {
  max-retries = 2  # Fewer retries, faster failure detection
}

# Unstable network or few peers
snap-sync {
  max-retries = 5  # More retries, better resilience
}
```

### Batch Size Optimization

**Goal**: Maximize data per request while staying within response limits

**Storage Batch Size:**

```hocon
# Default (8 accounts per storage request)
snap-sync {
  storage-batch-size = 8
}

# High bandwidth network
snap-sync {
  storage-batch-size = 16  # More data per request
}

# Low bandwidth or slow peers
snap-sync {
  storage-batch-size = 4   # Smaller requests
}
```

**Healing Batch Size:**

```hocon
# Default (16 paths per healing request)
snap-sync {
  healing-batch-size = 16
}

# Minimize healing iterations
snap-sync {
  healing-batch-size = 32  # Larger batches
}

# Reduce memory pressure during healing
snap-sync {
  healing-batch-size = 8   # Smaller batches
}
```

## Peer Management

### Peer Selection Strategy

**Goal**: Connect to high-quality SNAP-capable peers

**Configuration:**
```hocon
network {
  peer {
    # Increase max peers for more SNAP sources
    max-outgoing-peers = 30  # Default: 10-15
    max-incoming-peers = 30  # Default: 10-15
    
    # Faster peer discovery
    peer-discovery-interval = 10 seconds  # Default: 30s
  }
}
```

**Peer Quality Monitoring:**

```bash
# Monitor SNAP-capable peers
tail -f logs/fukuii.log | grep "supportsSnap=true"

# Check peer geographic distribution
# Diverse locations = better reliability
```

### Blacklist Management

**Goal**: Quickly identify and avoid problematic peers

Current automatic blacklisting:
- 10+ total failures
- 3+ invalid proof errors
- 5+ malformed response errors

**Tuning for aggressive blacklisting:**

```scala
// In SNAPErrorHandler.scala
// Reduce thresholds for faster blacklisting
def shouldBlacklistPeer(peerId: PeerId): Boolean = {
  val failures = peerFailures.getOrElse(peerId, PeerFailureInfo.empty)
  failures.totalFailures >= 5 ||  // Reduced from 10
  failures.invalidProofErrors >= 2 ||  // Reduced from 3
  failures.malformedResponseErrors >= 3  // Reduced from 5
}
```

## Monitoring and Profiling

### Key Performance Indicators (KPIs)

Monitor these metrics during SNAP sync:

1. **Throughput:**
   - Accounts/sec (target: 5,000-10,000)
   - Storage slots/sec (target: 50,000-150,000)
   - Nodes healed/sec (target: 500-2,000)

2. **Resource Usage:**
   - CPU utilization (target: 60-80%)
   - Memory usage (target: <80% of heap)
   - Disk I/O (target: <80% of disk bandwidth)
   - Network bandwidth (target: <80% of available)

3. **Reliability:**
   - Request success rate (target: >95%)
   - Peer blacklist rate (target: <10%)
   - Retry rate (target: <20%)
   - Circuit breaker trips (target: 0)

### Profiling Tools

**JVM Profiling:**

```bash
# Enable JMX for secure local monitoring
export JAVA_OPTS="-Dcom.sun.management.jmxremote \
  -Dcom.sun.management.jmxremote.host=127.0.0.1 \
  -Dcom.sun.management.jmxremote.port=9999 \
  -Dcom.sun.management.jmxremote.rmi.port=9999 \
  -Dcom.sun.management.jmxremote.authenticate=true \
  -Dcom.sun.management.jmxremote.password.file=/path/jmxremote.password \
  -Dcom.sun.management.jmxremote.access.file=/path/jmxremote.access \
  -Dcom.sun.management.jmxremote.ssl=true"

# Use VisualVM or JConsole to connect
visualvm --openjmx 127.0.0.1:9999
```

> **Note:**  
> Replace `/path/jmxremote.password` and `/path/jmxremote.access` with the actual paths to your JMX password and access files.  
> For remote access, use an SSH tunnel and restrict port 9999 to trusted hosts via firewall.

**CPU Profiling:**

```bash
# Async-profiler for low-overhead profiling
./profiler.sh -d 60 -f /tmp/flamegraph.svg $(pgrep -f fukuii)

# Analyze flamegraph to identify bottlenecks
# Common hotspots: RLP encoding, proof verification, trie operations
```

**Memory Profiling:**

```bash
# Heap dump on OutOfMemoryError
export JAVA_OPTS="-XX:+HeapDumpOnOutOfMemoryError \
  -XX:HeapDumpPath=/tmp/heapdump.hprof"

# Analyze with Eclipse MAT or JProfiler
```

**Network Profiling:**

```bash
# tcpdump for packet capture
sudo tcpdump -i eth0 -w /tmp/snap-sync.pcap port 30303

# Analyze with Wireshark
wireshark /tmp/snap-sync.pcap
```

## Performance Scenarios

### Scenario 1: Minimize Sync Time (Data Center)

**Hardware:**
- CPU: 16 cores
- RAM: 32 GB
- Disk: NVMe SSD
- Network: 1 Gbps

**Configuration:**
```hocon
sync {
  do-snap-sync = true
  
  snap-sync {
    enabled = true
    pivot-block-offset = 512  # Lower for faster catch-up
    account-concurrency = 32
    storage-concurrency = 16
    storage-batch-size = 16
    healing-batch-size = 32
    state-validation-enabled = true
    max-retries = 2  # Fail fast
    timeout = 20 seconds
  }
}

network.peer {
  max-outgoing-peers = 50
  max-incoming-peers = 50
}
```

**JVM:**
```bash
export JAVA_OPTS="-Xms16g -Xmx16g \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=100 \
  -XX:+ParallelRefProcEnabled"
```

**Expected Result:** 1.5-2 hours sync time

### Scenario 2: Minimize Resource Usage (Budget VPS)

**Hardware:**
- CPU: 2 cores
- RAM: 4 GB
- Disk: SATA SSD
- Network: 100 Mbps

**Configuration:**
```hocon
sync {
  do-snap-sync = true
  
  snap-sync {
    enabled = true
    pivot-block-offset = 1024
    account-concurrency = 6  # Lower for 2 cores
    storage-concurrency = 3
    storage-batch-size = 4
    healing-batch-size = 8
    state-validation-enabled = true
    max-retries = 4  # More tolerant
    timeout = 45 seconds
  }
}

network.peer {
  max-outgoing-peers = 15
  max-incoming-peers = 10
}
```

**JVM:**
```bash
export JAVA_OPTS="-Xms2g -Xmx2g \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=500"
```

**Expected Result:** 8-12 hours sync time, low resource usage

### Scenario 3: Maximum Reliability (Production)

**Hardware:**
- CPU: 8 cores
- RAM: 16 GB
- Disk: NVMe SSD RAID 1 (mirrored)
- Network: 500 Mbps

**Configuration:**
```hocon
sync {
  do-snap-sync = true
  
  snap-sync {
    enabled = true
    pivot-block-offset = 2048  # Extra stability
    account-concurrency = 16
    storage-concurrency = 8
    storage-batch-size = 8
    healing-batch-size = 16
    state-validation-enabled = true  # Always validate
    max-retries = 5  # More resilient
    timeout = 45 seconds  # Generous timeout
    max-snap-sync-failures = 10  # Avoid premature fallback
  }
}

network.peer {
  max-outgoing-peers = 30
  max-incoming-peers = 25
}
```

**JVM:**
```bash
export JAVA_OPTS="-Xms8g -Xmx8g \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=200 \
  -XX:+HeapDumpOnOutOfMemoryError"
```

**Expected Result:** 3-4 hours sync time, maximum reliability

## Benchmarking

### Baseline Measurement

Before tuning, establish baseline performance:

```bash
# Start sync with default config
./bin/fukuii -Dconfig.file=conf/fukuii.conf

# Record metrics every 5 minutes
while true; do
  echo "$(date): $(grep 'SNAP Sync Progress' logs/fukuii.log | tail -1)" >> /tmp/baseline.log
  sleep 300
done

# After sync completes, calculate average throughput
grep "accounts=" /tmp/baseline.log | awk -F'@' '{sum+=$2} END {print "Avg:", sum/NR, "accounts/sec"}'
```

### A/B Testing

Compare two configurations:

```bash
# Test A: Default config
./bin/fukuii -Dconfig.file=conf/default.conf
# Record: Total sync time, avg throughput, resource usage

# Test B: Tuned config
./bin/fukuii -Dconfig.file=conf/tuned.conf
# Record: Total sync time, avg throughput, resource usage

# Compare results
# Choose configuration with best overall performance
```

## Troubleshooting Performance Issues

### High CPU Usage

**Symptom:** CPU at 100%, sync progressing slowly

**Diagnosis:**
```bash
# Check what's consuming CPU
top -H -p $(pgrep -f fukuii)

# Profile to identify hotspots
./profiler.sh -d 60 -f /tmp/cpu-profile.svg $(pgrep -f fukuii)
```

**Solutions:**
- Reduce concurrency if CPU-bound
- Upgrade to faster CPU
- Ensure CPU governor is set to "performance"

### High Memory Usage / GC Pauses

**Symptom:** Frequent GC pauses, high memory usage

**Diagnosis:**
```bash
# Monitor GC activity
jstat -gcutil $(pgrep -f fukuii) 1000

# Check heap usage
jmap -heap $(pgrep -f fukuii)
```

**Solutions:**
- Increase heap size (-Xmx)
- Tune G1GC parameters
- Reduce cache sizes
- Enable GC logging for analysis

### Disk I/O Bottleneck

**Symptom:** High disk wait times, slow write speeds

**Diagnosis:**
```bash
# Monitor disk I/O
iostat -x 1

# Check if disk is saturated
iotop -o
```

**Solutions:**
- Upgrade to NVMe SSD
- Enable write caching
- Optimize file system (XFS, noatime)
- Use separate disk for logs

### Network Bottleneck

**Symptom:** Low download throughput, peer timeouts

**Diagnosis:**
```bash
# Monitor network bandwidth
iftop -i eth0

# Check for packet loss
ping -c 100 8.8.8.8 | grep loss
```

**Solutions:**
- Upgrade network connection
- Increase TCP buffers
- Enable BBR congestion control
- Connect to geographically closer peers

## Related Documentation

- [SNAP Sync User Guide](snap-sync-user-guide.md) - Basic usage and configuration
- [SNAP Sync FAQ](snap-sync-faq.md) - Common questions
- [Monitoring SNAP Sync](../operations/monitoring-snap-sync.md) - Grafana dashboards
- [Operating Modes](operating-modes.md) - Node operating modes
- [Node Configuration](node-configuration.md) - Complete configuration reference

---

**Last Updated:** 2025-12-03  
**Version:** 1.0  
**Maintainer:** Fukuii Development Team
