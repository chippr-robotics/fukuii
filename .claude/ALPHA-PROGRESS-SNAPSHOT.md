# Alpha Branch Progress Snapshot

**Date:** 2026-03-10
**Branch:** `alpha` (76 commits ahead of `main`)
**PR:** [#998](https://github.com/chippr-robotics/fukuii/pull/998) — "Alpha Stabilization — ECIP-1066 Canonical Client"
**Tests:** 2,193 (2,190 pass, 3 pre-existing flaky actor tests)
**Status:** Fresh SNAP re-sync running — 15% keyspace, ~1,134 accounts/sec, 4 snap peers, RSS stable at 1.3GB

---

## What Changed: main → alpha

The `main` branch could not SNAP sync ETC mainnet. It would OOM at ~9.5M accounts, lose all progress on pivot refresh, flood peers, mark good peers as stateless, crash on startup after SNAP, and silently skip bytecode+storage download. The `alpha` branch has 9 bug fixes (covering 23 individual issues) across sync, RPC, SNAP, consensus, and infrastructure that make the client production-viable.

---

## Bug Fixes

### Sync

1. **Config loading** — `ConfigFactory.invalidateCaches()` + fixed HOCON include paths for non-default networks.

2. **Fast sync best block hash + JSON-RPC error format** — Track correct best block during sync. Proper error responses for malformed requests; null id coercion.

3. **Actor name collision on sync restart** — Generation counter for unique actor names prevents crashes on sync restart.

4. **Block body download stall** — Peers timeout on `GetBlockBodies`, retry loop re-queues to same blacklisted peer. Fix: `ExcludingPeers` selector, exponential backoff, `maxBodyFetchRetries` limit.

### RPC

5. **RPC unavailability during sync** — Four related fixes:
   - Sync actors monopolized default dispatcher, starving HTTP/TCP I/O. Isolated to dedicated `sync-dispatcher`.
   - `eth_call`, `eth_estimateGas`, `eth_getCode` threw unhandled `MissingNodeException` during sync.
   - Same pattern missing in `personal_sendTransaction`.
   - `net_listPeers` with 30+ peers exceeded 20s timeout. In-process `peerStatusCache` (<1ms).

### SNAP Sync

6. **SNAP peer and pivot resilience** — SNAP sync couldn't handle peer instability or pivot staleness. Nine related fixes:
   - No escape hatch when peers lacked snapshots — added exponential backoff (2s→60s), 5-minute timeout, graceful fast sync fallback.
   - Pivot refresh counter reset on restart.
   - Snap/1 capability check with grace period.
   - Stagnation watchdog tracked task completions instead of `accountsDownloaded`.
   - Partial range resume — preserve `task.next` positions across pivot refreshes via `AccountRangeProgress`, 256-block safety valve.
   - Dynamic concurrency — cap workers to `min(config, snapPeerCount)`, 1:1 mapping.
   - In-place pivot refresh via `PivotRefreshed` message, zero-downtime root update.
   - Stale peer dedup by `remoteAddress` across all 3 coordinators.
   - Stale-root guard — `task.rootHash == stateRoot` prevents marking good peers as stateless after pivot refresh.

7. **SNAP OOM on large state** — Two unbounded memory sources:
   - `DeferredWriteMptStorage` held all trie nodes in memory (~420 bytes/account, OOM at 9.5M). Fix: periodic flush after each response batch.
   - `contractAccounts`/`contractStorageAccounts` ArrayBuffers grew unbounded (~45M entries on ETC, ~85% contracts due to pre-Mystique GasToken bloat). Fix: file-backed storage with 64-byte entries.

8. **Log file resilience** — `ResilientRollingFileAppender` recreates log file if deleted while running.

9. **SNAP post-download pipeline failures** — Four related failures after account download completes:
   - Phase handoff timeout — 5s ask to read 73.5M contract accounts timed out, silently skipping bytecode+storage sync. Fix: Bloom filter dedup (73.5M→2M codeHashes), storage streaming in 10K batches, concurrent bytecode+storage phases, recovery actors.
   - `StorageConsistencyChecker` crash — assumed all headers 0..bestBlock exist; after SNAP only pivot header stored. Skip when `SnapSyncDone=true`.
   - Recovery actors dropped SNAP responses via `case _ => // Ignore`. Added message forwarding + `PollRecoveryPeers` scheduler.
   - State validation short-circuit — `visited` set collision in `MptStorage.decodeNode()` where HashNode and resolved node share same hash, causing traversal to stop at depth 1. Fixed in all 4 trie walk methods.

---

## Consensus Correctness

- **ECBP-1100 (MESS) rewrite** — Cubic polynomial matching core-geth/Besu implementation.
- **Gas limit convergence** — Correct ±1/1024 per block toward configurable target (needed for Olympia 8M→60M).
- **Removed WITHDRAWN ECIPs** — ECIP-1098, 1049, 1097 deleted.
- **PoW/ETChash test suite** — Comprehensive consensus parity tests across all 14 ECIP-1066 forks.

---

## Infrastructure & Hardening

- **Config:** Fixed loading for non-default networks.
- **Bootstrap nodes:** Replaced stale Mantis-era bootnodes with current core-geth entries.
- **Docker:** Ubuntu 24.04 base images, GID 1000 conflict fix, `.env`→`.example` templates, security hardening.
- **Dependencies:** Bouncy Castle 1.82→1.83, Netty 4.1.115→4.1.131.
- **Rebrand:** `chordodes_fukuii` → `fukuii`, company name typo fix in NOTICE.
- **CI:** scalafmt ordering fix, PR coverage enabled, `apt-key` modernized to `signed-by` keyring, shell script hardening.
- **Dead code removal:** 39 stale TODO/FIXME comments, obsolete ETH/Ropsten/ECIP configs, 10 dead tests resolved.
- **Documentation:** Cross-client ETC-TEST-MATRIX.md mapping every EIP/ECIP test across core-geth, Besu, and Fukuii.

---

## What Was Reverted (not in alpha)

- **Heal-only SNAP sync mode** (commits `928056383` + `0a56b628c`) — Attempted to recover 305 missing intermediate trie nodes via GetNodeData without full re-sync. Failed — GetNodeData is dead in ETH68 (EIP-4938). No production client has heal-only mode. Cleanly reverted.

---

## Current SNAP Sync Status

```
Started:     2026-03-10 12:38
Accounts:    ~12.9M downloaded (15.1% keyspace)
Throughput:  ~1,134 accounts/sec (sustained)
Peers:       4 snap-capable, 3 workers active
Memory:      1.3GB RSS (stable — Bug 7 fix holding)
DB size:     5.4GB RocksDB
ETA:         Account download ~09:00 March 11
             Then: bytecode + storage + healing + block import
```

**Key verification:** Block import must pass block 24,133,018 (where previous sync failed on `gasUsed` mismatch). This confirms the end-to-end pipeline works.

---

## What's Next After SNAP Completes

1. Verify block import past block 24,133,018
2. Rebase olympia branch onto clean alpha
3. Extended Mordor sync for Besu (synced to 175K, needs extended run)
4. Hive integration testing (core-geth + besu passing, fukuii build WIP)
5. Gorgoroth trials — private Foundry network testing (scripts ready at `ops/gorgoroth/`)
6. Mordor Olympia activation — block 15,800,850 (~March 28)
