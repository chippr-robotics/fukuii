# SNAP Sync User Guide

**Audience**: Node operators and system administrators  
**Estimated Time**: 15-20 minutes  
**Prerequisites**: Basic understanding of Fukuii configuration and operation

## Overview

SNAP sync (Snapshot Synchronization) is a high-performance blockchain synchronization protocol that dramatically reduces the time and bandwidth required to sync a Fukuii node with the Ethereum Classic network. This guide explains how to enable, configure, and monitor SNAP sync on your node.

### What is SNAP Sync?

SNAP sync is part of the SNAP/1 protocol (devp2p specification) that enables nodes to download blockchain state snapshots without intermediate Merkle trie nodes. This results in:

- **80% faster sync time** compared to traditional fast sync
- **99% less upload bandwidth** required
- **53% less download bandwidth** required
- **99% fewer disk reads** during synchronization

### When to Use SNAP Sync

**Use SNAP sync when:**
- ✅ Setting up a new node that needs to sync from scratch
- ✅ You want the fastest possible initial sync
- ✅ You have limited bandwidth or want to minimize network usage
- ✅ You're syncing to a recent state (within ~1024 blocks of chain head)

**Don't use SNAP sync when:**
- ❌ You need to validate the entire blockchain from genesis (use full sync)
- ❌ You're running an archive node (SNAP sync only syncs recent state)
- ❌ You have very few SNAP-capable peers (SNAP requires compatible peers)

## Enabling SNAP Sync

### Default Configuration

SNAP sync is **enabled by default** in Fukuii. No additional configuration is required for basic usage.

To verify SNAP sync is enabled, check your configuration:

```bash
# Check if SNAP sync is enabled
grep -A 2 "do-snap-sync" conf/fukuii.conf
```

You should see:
```hocon
sync {
  do-snap-sync = true
}
```

### Enabling SNAP Sync (if disabled)

If SNAP sync is disabled in your configuration, enable it by adding to your config file:

**File:** `conf/fukuii.conf`
```hocon
sync {
  # Enable SNAP sync (recommended for new nodes)
  do-snap-sync = true
  
  # Disable fast sync to prioritize SNAP sync
  # SNAP sync is preferred over fast sync when both are enabled
  do-fast-sync = false
  
  snap-sync {
    enabled = true
  }
}
```

### Disabling SNAP Sync

To disable SNAP sync and fall back to fast sync:

**File:** `conf/fukuii.conf`
```hocon
sync {
  do-snap-sync = false
  do-fast-sync = true
}
```

## Configuration

### Basic Configuration

The default SNAP sync configuration is optimized for most use cases. These are the key parameters:

```hocon
sync {
  do-snap-sync = true
  
  snap-sync {
    enabled = true
    pivot-block-offset = 1024
    account-concurrency = 16
    storage-concurrency = 8
    timeout = 30 seconds
    max-retries = 3
  }
}
```

### Configuration Parameters

#### `pivot-block-offset` (Default: 1024)

Number of blocks before the chain head to use as the sync pivot point.

- **Higher values** (e.g., 2048): More stability against chain reorganizations, but longer catch-up time after sync
- **Lower values** (e.g., 512): Faster catch-up after sync, but may need to re-sync if chain reorganizes

**Recommendation:** Use default 1024 blocks (matches core-geth)

#### `account-concurrency` (Default: 16)

Number of parallel account range downloads.

- **Higher values** (e.g., 32): Faster account sync, but may overwhelm peers
- **Lower values** (e.g., 8): Gentler on peers, but slower sync

**Recommendation:** 16 for good balance of speed and peer friendliness

#### `storage-concurrency` (Default: 8)

Number of parallel storage range downloads.

- **Higher values** (e.g., 16): Faster storage sync for contracts
- **Lower values** (e.g., 4): Lower memory usage

**Recommendation:** 8 for balanced performance

#### `timeout` (Default: 30 seconds)

Request timeout for SNAP protocol messages.

- **Higher values** (e.g., 60s): Better for slow networks or distant peers
- **Lower values** (e.g., 15s): Faster failure detection

**Recommendation:** 30 seconds for most networks

#### `max-retries` (Default: 3)

Maximum retry attempts for failed requests.

- **Higher values** (e.g., 5): More resilient to transient failures
- **Lower values** (e.g., 1): Faster failure detection

**Recommendation:** 3 retries with exponential backoff

#### `state-validation-enabled` (Default: true)

Whether to validate state completeness before transitioning to regular sync.

- **true**: Ensures all trie nodes are present (recommended for production)
- **false**: Skip validation for faster sync (testing only)

**Recommendation:** Always true for production

### Advanced Configuration

For advanced users who need fine-tuned control:

```hocon
sync {
  do-snap-sync = true
  
  snap-sync {
    enabled = true
    pivot-block-offset = 1024
    account-concurrency = 16
    storage-concurrency = 8
    storage-batch-size = 8
    healing-batch-size = 16
    state-validation-enabled = true
    max-retries = 3
    timeout = 30 seconds
    max-snap-sync-failures = 5
  }
}
```

**Additional Parameters:**

- `storage-batch-size`: Accounts per storage request (default: 8)
- `healing-batch-size`: Trie nodes per healing request (default: 16)
- `max-snap-sync-failures`: Critical failures before fallback to fast sync (default: 5)

## Starting a Node with SNAP Sync

### First-Time Sync

1. **Start the node:**
   ```bash
   ./bin/fukuii -Dconfig.file=conf/fukuii.conf
   ```

2. **Monitor sync progress:**
   ```bash
   # Check logs for SNAP sync status
   tail -f logs/fukuii.log | grep "SNAP"
   ```

3. **Expected log output:**
   ```
   [INFO] SNAP Sync Controller initialized
   [INFO] Starting SNAP sync...
   [INFO] Selected pivot block: 12345678
   [INFO] 📊 SNAP Sync phase transition: Idle → AccountRangeSync
   [INFO] 📈 SNAP Sync Progress: phase=AccountRange (25%), accounts=250000@5000/s, ETA: 1h 30m
   ```

### Resuming After Restart

SNAP sync automatically resumes from where it left off if the node is restarted:

```bash
# SNAP sync state is persisted to disk
# On restart, sync will continue from last checkpoint
./bin/fukuii -Dconfig.file=conf/fukuii.conf
```

**Log output on resume:**
```
[INFO] Resuming SNAP sync from pivot block 12345678
[INFO] Loaded existing state trie with root 0x159e332c...
[INFO] Resuming from phase: StorageRangeSync
```

## Monitoring SNAP Sync

### Progress Monitoring

SNAP sync provides detailed progress information during synchronization.

#### Log Output

Monitor sync progress in the logs:

```bash
tail -f logs/fukuii.log | grep "SNAP Sync Progress"
```

**Example output:**
```
[INFO] 📈 SNAP Sync Progress: phase=AccountRange (45%), accounts=450000@7500/s, ETA: 1h 30m
[INFO] 📈 SNAP Sync Progress: phase=ByteCode (30%), codes=15000@250/s, ETA: 2h 15m
[INFO] 📈 SNAP Sync Progress: phase=Storage (60%), slots=6000000@100000/s, ETA: 45m
[INFO] 📈 SNAP Sync Progress: phase=Healing, nodes=5000@833/s, ETA: 30m
[INFO] ✅ SNAP sync completed successfully
```

#### Sync Phases

SNAP sync progresses through several phases:

1. **AccountRangeSync** - Downloading account ranges with Merkle proofs
   - Progress shows: `accounts synced`, `accounts/sec`, percentage complete
   
2. **ByteCodeSync** - Downloading smart contract bytecodes
   - Progress shows: `bytecodes downloaded`, `codes/sec`, percentage complete
   
3. **StorageRangeSync** - Downloading contract storage slots
   - Progress shows: `storage slots synced`, `slots/sec`, percentage complete
   
4. **StateHealing** - Filling missing trie nodes
   - Progress shows: `nodes healed`, `nodes/sec`
   
5. **StateValidation** - Verifying state completeness
   - Validates all trie nodes are present
   
6. **Completed** - SNAP sync finished, transitioning to regular sync

### JSON-RPC Status Query

Query sync status via JSON-RPC:

```bash
# Get sync status
curl -X POST http://localhost:8545 -H "Content-Type: application/json" \
  --data '{"jsonrpc":"2.0","method":"eth_syncing","params":[],"id":1}'
```

**Response during SNAP sync:**
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "startingBlock": "0x0",
    "currentBlock": "0xbc614e",
    "highestBlock": "0xbc614e",
    "pulledStates": "0x6ddd0",
    "knownStates": "0xf4240"
  }
}
```

**Response when sync is complete:**
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": false
}
```

### Performance Metrics

Monitor system resource usage during SNAP sync:

```bash
# CPU and memory usage
top -p $(pgrep -f fukuii)

# Disk I/O
iostat -x 5

# Network bandwidth
iftop -i eth0
```

**Expected resource usage:**
- **CPU**: 50-100% during proof verification
- **Memory**: 2-4 GB (with LRU cache)
- **Disk I/O**: 50-200 MB/s writes
- **Network**: 5-50 Mbps download (varies by peer quality)

## Troubleshooting

### SNAP Sync Not Starting

**Symptom:** Node starts but SNAP sync doesn't begin

**Possible causes:**
1. SNAP sync is disabled in configuration
2. SNAP sync already completed previously
3. No SNAP-capable peers available

**Solutions:**

```bash
# 1. Check configuration
grep -A 5 "do-snap-sync" conf/fukuii.conf
# Should show: do-snap-sync = true

# 2. Check if SNAP sync already completed
grep "SNAP sync completed" logs/fukuii.log
# If found, SNAP sync is done - node is in regular sync mode

# 3. Check peer connections
grep "SNAP1" logs/fukuii.log | grep "capabilities"
# Should show peers advertising "snap/1" capability
```

### Slow Sync Progress

**Symptom:** SNAP sync is progressing very slowly (<100 accounts/sec)

**Possible causes:**
1. Few SNAP-capable peers
2. Slow network connection
3. Low concurrency settings
4. Disk I/O bottleneck

**Solutions:**

```bash
# 1. Check peer count
grep "peers:" logs/fukuii.log | tail -1
# Recommendation: 10+ peers for good performance

# 2. Increase concurrency (edit conf/fukuii.conf)
snap-sync {
  account-concurrency = 32  # Increase from 16
  storage-concurrency = 16  # Increase from 8
}

# 3. Check disk performance
dd if=/dev/zero of=/tmp/testfile bs=1M count=1024 oflag=direct
# Should show >100 MB/s for SSD

# 4. Restart node with new settings
./bin/fukuii -Dconfig.file=conf/fukuii.conf
```

### Sync Stalled or Stuck

**Symptom:** Sync progress hasn't advanced in 5+ minutes

**Possible causes:**
1. Waiting for peer responses (timeouts)
2. State healing iteration
3. Peer disconnections

**Solutions:**

```bash
# Check recent log output for errors
tail -100 logs/fukuii.log | grep -E "ERROR|WARN|timeout"

# Common patterns and fixes:
# "Request timeout" - Normal, retrying with different peer
# "Circuit breaker is OPEN" - Too many failures, will auto-recover
# "Blacklisting peer" - Removing bad peer, will find new ones

# If truly stuck (no progress for 30+ minutes), restart:
./bin/fukuii -Dconfig.file=conf/fukuii.conf
# SNAP sync will resume from last checkpoint
```

### State Validation Failures

**Symptom:** Sync reaches validation phase but fails

```
[ERROR] ❌ CRITICAL: State root verification FAILED!
[WARN] State validation found 1234 missing nodes
```

**Cause:** Some trie nodes are missing from the downloaded state

**Solution:** SNAP sync will automatically trigger healing

```bash
# Healing will download missing nodes
# Monitor healing progress:
tail -f logs/fukuii.log | grep "Healing"

# Expected output:
[INFO] Queued 1234 missing nodes for healing
[INFO] 📈 SNAP Sync Progress: phase=Healing, nodes=500@100/s
[INFO] Healing iteration 1 complete, validating...
[INFO] ✅ State validation passed - no missing nodes
```

**If healing fails repeatedly:**
```bash
# Increase max retries in config
snap-sync {
  max-retries = 5  # Increase from 3
}

# Restart to apply new config
./bin/fukuii -Dconfig.file=conf/fukuii.conf
```

### Fallback to Fast Sync

**Symptom:** SNAP sync fails and node falls back to fast sync

```
[ERROR] SNAP sync failed after 5 critical failures, falling back to fast sync
```

**Cause:** Too many critical errors during SNAP sync

**Solutions:**

1. **Review error logs to identify root cause:**
   ```bash
   grep "ERROR.*SNAP" logs/fukuii.log | tail -20
   ```

2. **Common issues and fixes:**
   - **Few SNAP peers:** Wait for more peers to connect
   - **Network issues:** Check internet connection and firewall
   - **Disk full:** Free up disk space

3. **Retry SNAP sync:**
   ```bash
   # Stop the node
   pkill -f fukuii
   
   # Clear SNAP sync state to force fresh start
   rm -rf ~/.fukuii/leveldb/snap-sync-*
   
   # Restart with SNAP sync
   ./bin/fukuii -Dconfig.file=conf/fukuii.conf
   ```

## Best Practices

### Production Deployments

1. **Use Default Settings**: The default SNAP sync configuration is optimized for production
2. **Enable State Validation**: Always keep `state-validation-enabled = true`
3. **Monitor Logs**: Watch for errors and warnings during sync
4. **Plan for Downtime**: Initial SNAP sync takes 2-6 hours depending on network
5. **Verify Completion**: Ensure sync completes before serving production traffic

### Testing Environments

1. **Disable Validation for Speed**: Set `state-validation-enabled = false` (testing only!)
2. **Increase Concurrency**: Higher values for faster sync on test networks
3. **Lower Timeouts**: Faster failure detection in controlled environments

### Resource Planning

**Minimum Requirements:**
- **CPU**: 2 cores (4+ recommended)
- **Memory**: 4 GB RAM (8+ recommended)
- **Disk**: 500 GB SSD (NVMe recommended)
- **Network**: 10 Mbps (50+ Mbps recommended)

**Expected Sync Times (Ethereum Classic mainnet):**
- **Good network** (50+ Mbps, 20+ peers): 2-3 hours
- **Average network** (10-50 Mbps, 10-20 peers): 4-6 hours
- **Poor network** (<10 Mbps, <10 peers): 8-12 hours

## Migration Guide

### Migrating from Fast Sync to SNAP Sync

If you have an existing node using fast sync:

1. **Check current sync mode:**
   ```bash
   grep "do-fast-sync" conf/fukuii.conf
   ```

2. **Enable SNAP sync:**
   ```hocon
   sync {
     do-snap-sync = true   # Enable SNAP
     do-fast-sync = false  # Disable fast sync
   }
   ```

3. **Restart node:**
   ```bash
   ./bin/fukuii -Dconfig.file=conf/fukuii.conf
   ```

**Note:** If your node is already synced, enabling SNAP sync won't re-sync. SNAP sync only activates on fresh nodes or when sync state is cleared.

### Migrating from SNAP Sync to Fast Sync

To switch back to fast sync:

1. **Update configuration:**
   ```hocon
   sync {
     do-snap-sync = false  # Disable SNAP
     do-fast-sync = true   # Enable fast sync
   }
   ```

2. **Clear sync state (optional):**
   ```bash
   rm -rf ~/.fukuii/leveldb/snap-sync-*
   ```

3. **Restart node:**
   ```bash
   ./bin/fukuii -Dconfig.file=conf/fukuii.conf
   ```

## Related Documentation

- [Operating Modes Runbook](operating-modes.md) - Overview of all node operating modes
- [Node Configuration Runbook](node-configuration.md) - Complete configuration reference
- [SNAP Sync Performance Tuning Guide](snap-sync-performance-tuning.md) - Advanced optimization
- [SNAP Sync FAQ](snap-sync-faq.md) - Common questions and answers
- [Monitoring SNAP Sync](../operations/monitoring-snap-sync.md) - Grafana dashboards and metrics

## Support

For issues or questions:
- **GitHub Issues**: https://github.com/chippr-robotics/fukuii/issues
- **Community Chat**: Join our Discord/Telegram
- **Documentation**: https://chippr-robotics.github.io/fukuii/

---

**Last Updated:** 2025-12-03  
**Version:** 1.0  
**Maintainer:** Fukuii Development Team
