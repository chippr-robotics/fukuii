# SNAP Sync — ETC Mainnet Attempt 3

**Date:** 2026-03-27
**Branch:** `march-onward`
**JAR:** `fukuii-assembly-0.1.240.jar`
**JVM:** `-Xmx4g`, JDK 21 LTS
**Hardware:** Intel NUC10i7FNH (i7-10710U 6C/12T, 32GB RAM, NVMe)

## Configuration

- **Network:** ETC mainnet (chain ID 61)
- **Sync mode:** SNAP sync (do-fast-sync=true, do-snap-sync=true)
- **Peer setup:** Besu v26.3 (local, port 30304) as primary SNAP server + external network peers
- **Core-geth:** Stopped mid-sync (could not serve SNAP state — known bug, GetBlockHeaders timeouts)
- **Static nodes:** Besu + core-geth (core-geth removed during sync)
- **Discovery port:** 30305
- **RPC:** port 8553, APIs: eth,web3,net,personal,fukuii,admin

## Code Changes (vs previous attempts)

1. **Storage concurrent with accounts (geth-aligned):** Removed `UpdateMaxInFlightPerPeer(0)` override on StorageRangeCoordinator. Storage now dispatches at `initialMaxInFlightPerPeer=3` from the start, running truly concurrent with account download.
2. **L-012 v2:** Static peer reconnection cooldown (60s), maxIncomingPeers exemption for static peers, lifecycle logging.
3. **Admin RPC enabled by default:** Added `admin` to default `apis` config in `network.conf`.

## Timeline

| Time | Event |
|------|-------|
| 18:09 | Fukuii started |
| 18:09:48 | SNAP sync initialized, pivot 24253898 (from Besu height 24253962) |
| 18:10:04 | Coordinators created — storage at budget=3, bytecode at budget=0 |
| 18:10:05 | First storage tasks arriving + dispatching (concurrent with accounts) |
| 18:10:06 | First GetStorageRanges sent to Besu — **storage concurrency confirmed working** |
| 18:26:10 | First proactive pivot refresh (122 blocks drift, threshold=120) |
| 18:38:44 | Second pivot refresh |
| 18:41:05 | All-stateless pivot refresh (storage, attempt 1/3) — peers filtered, Besu resumed |
| 18:53:12 | Third proactive pivot refresh |
| 19:03:12 | Fourth proactive pivot refresh |
| ~19:10 | Core-geth stopped (was only causing timeouts, freed ~1.5GB RAM) |

## Progress Snapshots

| Time | Accounts | Keyspace | Accts/sec | Workers/Peers | Storage Reqs | RocksDB | Fukuii RSS | System RAM Free | Swap Used | Load Avg | Notes |
|------|----------|----------|-----------|---------------|-------------|---------|-----------|-----------------|-----------|----------|-------|
| 18:10:14 | 101,807 | 0.1% | 9,853 | 6/4 | 13 | — | — | — | — | — | Initial burst, few peers |
| 18:32:19 | 4,042,845 | 4.7% | 4,171 | 12/4 | ~500 | — | — | — | — | — | Settling to steady state |
| 18:40:33 | 5,256,410 | 6.1% | 3,592 | 14/10 | ~700 | — | — | — | — | — | More peers joining |
| 18:53:48 | 7,075,568 | 8.3% | 3,132 | 15/12 | — | — | — | — | — | — | Stable throughput |
| 19:05:10 | 8,788,536 | 10.3% | 2,989 | 16/9 | — | — | — | — | — | — | Core-geth stopped |
| 19:14:09 | 10,000,924 | 11.7% | 2,874 | 16/8 | ~1,245 | 12 GB | 8,617 MB | 3.7 GB | 7.9/8 GB | 30.4 | **First resource snapshot** |

## Resource Concerns

- **Swap nearly full (7.9/8 GB):** Besu 880MB in swap, Claude processes ~678MB. Risk of OOM if swap exhausts.
- **Load average 30.4:** 5x the 6-core CPU. Heavy context switching / I/O wait expected.
- **Fukuii RSS 8.6GB on 4GB heap:** Native memory (RocksDB block cache, mmap files, NIO buffers) doubling the JVM footprint.
- **RocksDB 12GB at 11.7%:** Projects to ~100GB for full account keyspace. Disk space fine (2TB NVMe).

## Peer Observations

- **Besu (local):** Rock solid. Zero disconnects. Primary SNAP server for both accounts and storage.
- **Core-geth (local):** Advertises snap/1 but cannot serve state. Consecutive GetBlockHeaders timeouts → marked stateless for both accounts and storage. Stopped at ~19:10 to free resources.
- **External peers:** 8-12 snap peers from the network. Most cannot serve storage (marked stateless after 3 timeouts). Some serve accounts. One external peer (`1626...`) confirmed serving storage alongside Besu.
- **Discovery:** Finding peers via UDP on port 30305. Peer count fluctuates 8-12.

## Concurrent Phase Status

| Phase | Budget | Status | Notes |
|-------|--------|--------|-------|
| Accounts | 5/peer | Active | Primary phase, ~2,900 accts/sec |
| Storage | 3/peer | Active | Dispatching concurrently — **this is new vs previous attempts** |
| Bytecode | 0/peer | Queuing | 2,153+ unique codeHashes accumulated, dispatches after accounts complete |
| Healing | — | Not started | Starts after all 3 complete |

## Open Questions

- [ ] Should bytecode also run concurrently during account phase? (Backlog L-013)
- [ ] Will swap pressure cause OOM before sync completes?
- [ ] Projected total sync time at current throughput?
- [ ] RocksDB compaction behavior as DB grows past 50GB?
