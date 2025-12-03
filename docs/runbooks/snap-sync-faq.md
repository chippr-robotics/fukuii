# SNAP Sync FAQ

**Audience**: All Fukuii users  
**Last Updated**: 2025-12-03

## General Questions

### What is SNAP sync?

SNAP sync (Snapshot Synchronization) is a blockchain synchronization protocol that enables Ethereum Classic nodes to download blockchain state snapshots without intermediate Merkle trie nodes. This dramatically reduces sync time (80% faster) and bandwidth usage (99% less upload, 53% less download) compared to traditional fast sync.

SNAP sync is part of the devp2p SNAP/1 protocol specification and is supported by modern Ethereum clients including geth, erigon, and core-geth.

### How does SNAP sync differ from fast sync?

| Feature | Fast Sync | SNAP Sync |
|---------|-----------|-----------|
| Downloads | Full Merkle trie nodes | State ranges + proofs |
| Bandwidth | High (full trie) | Low (ranges only) |
| Sync time | 10-12 hours | 2-3 hours |
| Disk I/O | Very high (99.39% more reads) | Low |
| Protocol | ETH/66+ | SNAP/1 (satellite of ETH) |
| Verification | Full trie validation | Merkle proof verification |

SNAP sync is fundamentally more efficient because it:
1. Downloads account and storage **ranges** instead of individual trie nodes
2. Uses Merkle **proofs** to verify data instead of reconstructing entire trie
3. Parallelizes downloads across multiple peers
4. Heals missing nodes only as needed

### Is SNAP sync safe? How is data verified?

Yes, SNAP sync is cryptographically secure. Every piece of downloaded data is verified:

1. **Merkle Proof Verification**: Each account range comes with a Merkle proof that is verified against the known state root
2. **Hash Verification**: All bytecodes are verified using keccak256 hashes
3. **State Root Verification**: The computed state root must match the pivot block's expected state root
4. **Completeness Validation**: Before transitioning to regular sync, the entire state trie is validated to detect missing nodes

If any verification fails, the data is rejected and requested from a different peer.

### Can I use SNAP sync on Ethereum Classic?

Yes! SNAP sync is fully supported on Ethereum Classic (ETC) mainnet, Mordor testnet, and all ETC-compatible networks. The protocol is network-agnostic and works with any Ethereum-compatible blockchain.

### Does SNAP sync work with other Ethereum networks?

Yes, SNAP sync works with:
- ✅ Ethereum Classic (ETC) mainnet
- ✅ Mordor testnet (ETC)
- ✅ Ethereum (ETH) mainnet (if configured)
- ✅ Ropsten, Goerli, Sepolia (ETH testnets)
- ✅ Any Ethereum-compatible network

The same SNAP sync implementation works across all networks.

## Configuration Questions

### Is SNAP sync enabled by default?

Yes, SNAP sync is **enabled by default** in Fukuii. You don't need to change any configuration to use it.

Default settings:
```hocon
sync {
  do-snap-sync = true
}
```

### How do I enable SNAP sync?

SNAP sync is already enabled by default. If it has been disabled in your configuration, re-enable it:

```hocon
sync {
  do-snap-sync = true
  
  snap-sync {
    enabled = true
  }
}
```

Then restart your node:
```bash
./bin/fukuii -Dconfig.file=conf/fukuii.conf
```

### How do I disable SNAP sync?

To disable SNAP sync and use fast sync instead:

```hocon
sync {
  do-snap-sync = false
  do-fast-sync = true
}
```

**Note:** Only disable SNAP sync if you have a specific reason (debugging, testing, compatibility issues).

### What are the recommended configuration settings?

The default settings are optimized for most use cases:

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
    state-validation-enabled = true
  }
}
```

Only change these if you have specific performance requirements. See the [Performance Tuning Guide](snap-sync-performance-tuning.md) for advanced optimization.

### Can I use SNAP sync with an archive node?

No, SNAP sync is **not suitable for archive nodes**. SNAP sync only downloads the current state at a recent pivot block, not the full historical state required for archive nodes.

For archive nodes, use full sync from genesis:
```hocon
sync {
  do-snap-sync = false
  do-fast-sync = false
}
```

### What's the minimum hardware required for SNAP sync?

**Minimum:**
- CPU: 2 cores
- RAM: 4 GB
- Disk: 500 GB SSD
- Network: 10 Mbps

**Recommended:**
- CPU: 4+ cores
- RAM: 8+ GB
- Disk: 500 GB NVMe SSD
- Network: 50+ Mbps

**Optimal:**
- CPU: 8+ cores
- RAM: 16+ GB
- Disk: 1 TB NVMe SSD
- Network: 100+ Mbps

SNAP sync will work on minimum hardware but will be significantly slower.

## Operational Questions

### How long does SNAP sync take?

Sync time varies based on network conditions and hardware:

**Typical sync times (Ethereum Classic mainnet):**
- **Good setup** (50+ Mbps, 20+ peers, NVMe SSD): 2-3 hours
- **Average setup** (10-50 Mbps, 10-20 peers, SATA SSD): 4-6 hours
- **Poor setup** (<10 Mbps, <10 peers, HDD): 8-12 hours

For comparison, fast sync typically takes 10-12 hours under similar conditions.

### Can I stop and resume SNAP sync?

Yes! SNAP sync automatically saves its progress to disk. If you stop the node (gracefully or due to a crash), it will resume from where it left off when you restart.

```bash
# Stop node
pkill -TERM fukuii

# Restart node (resumes SNAP sync automatically)
./bin/fukuii -Dconfig.file=conf/fukuii.conf
```

The node will log: `Resuming SNAP sync from pivot block XXXXXX`

### How do I know if SNAP sync is working?

Check the logs for SNAP sync activity:

```bash
tail -f logs/fukuii.log | grep "SNAP"
```

You should see:
```
[INFO] SNAP Sync Controller initialized
[INFO] Starting SNAP sync...
[INFO] 📊 SNAP Sync phase transition: Idle → AccountRangeSync
[INFO] 📈 SNAP Sync Progress: phase=AccountRange (25%), accounts=250000@5000/s, ETA: 1h 30m
```

If you don't see these messages, SNAP sync may not be enabled or may have already completed.

### How do I monitor SNAP sync progress?

**Via logs:**
```bash
tail -f logs/fukuii.log | grep "SNAP Sync Progress"
```

**Via JSON-RPC:**
```bash
curl -X POST http://localhost:8545 -H "Content-Type: application/json" \
  --data '{"jsonrpc":"2.0","method":"eth_syncing","params":[],"id":1}'
```

**Grafana dashboard** (if configured):
- See [Monitoring SNAP Sync](../operations/monitoring-snap-sync.md)

### What are the SNAP sync phases?

SNAP sync progresses through 6 phases:

1. **AccountRangeSync** - Download account ranges (typically 60-70% of total time)
2. **ByteCodeSync** - Download smart contract bytecodes (5-10%)
3. **StorageRangeSync** - Download contract storage slots (20-30%)
4. **StateHealing** - Fill missing trie nodes (0-10%, may iterate)
5. **StateValidation** - Verify state completeness (<1%)
6. **Completed** - Transition to regular sync

Progress is shown in logs: `phase=AccountRange (45%)`

### Why is SNAP sync stuck at a certain percentage?

**Common causes:**

1. **Waiting for peer responses** - Normal, retries automatically
2. **State healing iteration** - May take 5-15 minutes
3. **Network issues** - Check internet connection
4. **Few SNAP-capable peers** - Wait for more peers to connect

**How to verify it's not stuck:**

```bash
# Check recent activity (should see new lines every 30 seconds)
tail -f logs/fukuii.log | grep "SNAP Sync Progress"

# Check for errors
tail -100 logs/fukuii.log | grep -E "ERROR|WARN"
```

If truly stuck (no progress for 30+ minutes), restart the node.

### Why did SNAP sync fall back to fast sync?

SNAP sync falls back to fast sync after 5 critical failures (configurable via `max-snap-sync-failures`).

**Common reasons:**
1. **Too few SNAP-capable peers** - Need at least 5-10 peers with SNAP/1 capability
2. **Network instability** - Frequent disconnections or timeouts
3. **State validation failures** - Missing nodes that couldn't be healed

**Check the logs:**
```bash
grep "ERROR.*SNAP" logs/fukuii.log | tail -20
```

**To retry SNAP sync:**
```bash
# Stop node
pkill fukuii

# Clear SNAP sync state
rm -rf ~/.fukuii/leveldb/snap-sync-*

# Restart
./bin/fukuii -Dconfig.file=conf/fukuii.conf
```

## Troubleshooting Questions

### SNAP sync is very slow. What can I do?

**Check your peer count:**
```bash
grep "peers:" logs/fukuii.log | tail -1
```
Need 10+ peers for good performance, 20+ for optimal.

**Check SNAP-capable peers:**
```bash
grep "supportsSnap=true" logs/fukuii.log | wc -l
```
Need at least 5 SNAP-capable peers.

**Increase concurrency** (if you have good hardware):
```hocon
snap-sync {
  account-concurrency = 32  # Increase from 16
  storage-concurrency = 16  # Increase from 8
}
```

**Check network speed:**
```bash
# Download speed test
curl -o /dev/null http://speedtest.wdc01.softlayer.com/downloads/test100.zip
```

See [Performance Tuning Guide](snap-sync-performance-tuning.md) for detailed optimization.

### I see "Request timeout" errors. Is this normal?

Yes, occasional timeouts are normal and expected. SNAP sync automatically retries with different peers.

**Normal:** 10-20% timeout rate  
**Concerning:** >50% timeout rate

If timeouts are excessive:
```hocon
snap-sync {
  timeout = 45 seconds  # Increase from 30s
  max-retries = 5        # Increase from 3
}
```

### Why are peers being blacklisted?

Peers are automatically blacklisted for bad behavior:
- 10+ total failures
- 3+ invalid Merkle proofs (malicious/broken peer)
- 5+ malformed responses (incompatible peer)

**Check blacklist rate:**
```bash
grep "Blacklisting peer" logs/fukuii.log | wc -l
```

**Normal:** <10% of peers blacklisted  
**Concerning:** >30% of peers blacklisted

If too many peers are blacklisted:
1. Check your internet connection (may be the problem, not the peers)
2. Ensure your node is running latest version
3. Try connecting to different peer discovery bootstrap nodes

### State validation failed. What does this mean?

State validation detects missing trie nodes. This is **not an error** - it's part of the normal SNAP sync process.

```
[WARN] State validation found 1234 missing nodes
[INFO] Queued 1234 missing nodes for healing
```

SNAP sync will automatically:
1. Queue missing nodes for healing
2. Download missing nodes from peers
3. Re-validate state
4. Repeat until complete

This may take 1-3 healing iterations (5-15 minutes each).

**Only concerning if:**
- Healing iterations exceed 10
- Same nodes remain missing after multiple iterations

In that case, restart the node.

### Can I interrupt SNAP sync and switch to fast sync?

Yes, but you'll lose SNAP sync progress:

```bash
# Stop node
pkill fukuii

# Disable SNAP sync
# Edit conf/fukuii.conf:
sync {
  do-snap-sync = false
  do-fast-sync = true
}

# Clear SNAP sync state
rm -rf ~/.fukuii/leveldb/snap-sync-*

# Restart with fast sync
./bin/fukuii -Dconfig.file=conf/fukuii.conf
```

**Note:** Fast sync will start from scratch. Only do this if SNAP sync is failing repeatedly.

### My disk is full. Will SNAP sync work?

No, SNAP sync requires sufficient free disk space:

**Required free space:**
- **ETC mainnet:** 500 GB (400 GB chain data + 100 GB overhead)
- **Mordor testnet:** 50 GB
- **ETH mainnet:** 800 GB

**Check free space:**
```bash
df -h ~/.fukuii
```

**Free up space:**
```bash
# Remove old logs
rm ~/.fukuii/logs/*.log.gz

# Remove old snapshots (if not using)
rm -rf ~/.fukuii/snapshots/old-*
```

See [Disk Management Runbook](disk-management.md) for more options.

## Peer and Network Questions

### How many peers do I need for SNAP sync?

**Minimum:** 5 SNAP-capable peers (sync will work but be slow)  
**Recommended:** 10-20 SNAP-capable peers (good performance)  
**Optimal:** 20+ SNAP-capable peers (maximum throughput)

**Check your peer count:**
```bash
grep "peers:" logs/fukuii.log | tail -1
```

**Check SNAP-capable peers:**
```bash
grep "supportsSnap=true" logs/fukuii.log | wc -l
```

### What if I have no SNAP-capable peers?

SNAP sync requires peers that support the SNAP/1 protocol. If you have no SNAP-capable peers:

1. **Wait** - Peer discovery takes 5-10 minutes
2. **Check network** - Ensure port 30303 is open
3. **Manual peer connection:**
   ```bash
   # Add known SNAP-capable bootstrap nodes
   # (configure in fukuii.conf under network.discovery)
   ```
4. **Fallback** - Node will automatically fallback to fast sync after failures

### Do I need to open any firewall ports?

Yes, for optimal peer connectivity:

**Required:**
- TCP/UDP 30303 (P2P communication)

**Optional:**
- TCP 8545 (JSON-RPC, if serving RPC)
- TCP 8546 (WebSocket, if enabled)

**Firewall rules:**
```bash
# Ubuntu/Debian
sudo ufw allow 30303/tcp
sudo ufw allow 30303/udp

# CentOS/RHEL
sudo firewall-cmd --add-port=30303/tcp --permanent
sudo firewall-cmd --add-port=30303/udp --permanent
sudo firewall-cmd --reload
```

### Can I use SNAP sync behind a VPN?

Yes, but it may affect performance:

**Considerations:**
- Higher latency (increase `timeout` to 45-60 seconds)
- Variable bandwidth (SNAP sync will adapt)
- Peer geographic distribution (may connect to peers far away)

**Recommended VPN configuration:**
```hocon
snap-sync {
  timeout = 60 seconds  # Accommodate VPN latency
  max-retries = 5        # More tolerant of transient issues
}
```

## Performance Questions

### How can I make SNAP sync faster?

**Quick wins:**

1. **Upgrade to SSD/NVMe** - Biggest single improvement
2. **Increase concurrency:**
   ```hocon
   snap-sync {
     account-concurrency = 32
     storage-concurrency = 16
   }
   ```
3. **Increase peer count:**
   ```hocon
   network.peer {
     max-outgoing-peers = 30
   }
   ```
4. **Ensure good network** - 50+ Mbps recommended

See [Performance Tuning Guide](snap-sync-performance-tuning.md) for comprehensive optimization.

### Does SNAP sync use more CPU/memory than fast sync?

**CPU:** Similar or slightly higher (Merkle proof verification)  
**Memory:** Similar (2-4 GB with LRU cache)  
**Disk I/O:** Much lower (99% fewer reads)  
**Network:** Much lower (53% less download, 99% less upload)

Overall, SNAP sync is **more efficient** than fast sync.

### Why is my CPU at 100% during SNAP sync?

This is normal during proof verification phases. CPU usage breakdown:
- 40-60%: Merkle proof verification
- 20-30%: RLP encoding/decoding
- 10-20%: Trie operations
- 10%: Other (networking, logging)

**If CPU is consistently 100% and sync is slow:**
- Reduce concurrency
- Upgrade CPU
- Check for other processes competing for CPU

### Is SNAP sync using too much memory?

SNAP sync uses:
- **JVM heap:** 2-4 GB (configurable)
- **LRU cache:** ~100 MB (10,000 storage tries)
- **Buffers:** ~500 MB

**Total:** 3-5 GB typical

**If seeing OutOfMemoryError:**
```bash
# Increase heap size
export JAVA_OPTS="-Xms8g -Xmx8g"
./bin/fukuii -Dconfig.file=conf/fukuii.conf
```

## Security Questions

### Is SNAP sync secure?

Yes! SNAP sync uses cryptographic verification for all data:

- ✅ Merkle proof verification for account ranges
- ✅ Merkle proof verification for storage ranges
- ✅ keccak256 hash verification for bytecodes
- ✅ State root verification against known good blocks
- ✅ Complete trie validation before transition

Malicious peers cannot provide fake data - it will be detected and rejected.

### Can SNAP sync be attacked?

SNAP sync has defenses against attacks:

**DOS Attacks:**
- Request timeouts prevent hanging
- Circuit breakers prevent repeated failures
- Peer blacklisting removes malicious peers
- Resource limits (LRU cache) prevent memory exhaustion

**Data Attacks:**
- Merkle proof verification detects fake accounts
- Hash verification detects fake bytecodes
- State root verification detects incomplete state

### Should I validate state after SNAP sync?

State validation is **enabled by default** (`state-validation-enabled = true`) and is recommended for production.

This ensures:
- No missing trie nodes
- Complete state before serving RPC requests
- Cryptographic correctness

Only disable for testing/debugging.

## Compatibility Questions

### Which Ethereum clients support SNAP sync?

SNAP/1 protocol is supported by:
- ✅ Fukuii (this implementation)
- ✅ geth (go-ethereum)
- ✅ erigon
- ✅ core-geth (Ethereum Classic)
- ✅ Besu (partial support)

All these clients can interoperate via SNAP protocol.

### Can I SNAP sync from geth/core-geth peers?

Yes! SNAP sync is a standard protocol. Fukuii can download state from any SNAP-capable peer regardless of client implementation.

### What version of Fukuii introduced SNAP sync?

SNAP sync was introduced in Fukuii version X.X.X (TBD - check release notes).

To verify your version:
```bash
./bin/fukuii --version
```

### Will SNAP sync work with old peers?

SNAP sync requires peers that advertise the `snap/1` capability. Older peers that don't support SNAP/1 will be ignored for SNAP sync purposes (but can still be used for block sync via ETH protocol).

The node will automatically:
1. Detect SNAP-capable peers from Hello handshake
2. Use SNAP protocol with capable peers
3. Fall back to ETH protocol with non-SNAP peers

## Related Documentation

- [SNAP Sync User Guide](snap-sync-user-guide.md) - How to enable, configure, and monitor
- [SNAP Sync Performance Tuning](snap-sync-performance-tuning.md) - Advanced optimization
- [Monitoring SNAP Sync](../operations/monitoring-snap-sync.md) - Grafana dashboards
- [Operating Modes](operating-modes.md) - Node operating modes
- [Known Issues](known-issues.md) - Known bugs and workarounds

## Getting Help

If your question isn't answered here:

1. **Check logs:** `tail -100 logs/fukuii.log`
2. **Search issues:** https://github.com/chippr-robotics/fukuii/issues
3. **Ask community:** Discord/Telegram
4. **File issue:** https://github.com/chippr-robotics/fukuii/issues/new

**Include in your question:**
- Fukuii version (`./bin/fukuii --version`)
- Configuration (relevant sections)
- Logs (last 100 lines with errors)
- System info (OS, CPU, RAM, disk type)
- Network info (bandwidth, peer count)

---

**Last Updated:** 2025-12-03  
**Version:** 1.0  
**Maintainer:** Fukuii Development Team
