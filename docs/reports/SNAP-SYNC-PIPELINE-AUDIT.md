# Fukuii SNAP Sync Pipeline Audit
**Date:** 2026-04-03  
**Branch:** `march-onward` (153 commits ahead of upstream)  
**Scope:** Full pipeline from pivot selection through regular sync startup  
**Trigger:** 12 ETC mainnet SNAP sync attempts; stalling in healing phase  
**Reference:** go-ethereum `eth/protocols/snap/sync.go`

---

## Executive Summary

12 ETC mainnet SNAP sync attempts have stalled during or after the trie healing phase. A comprehensive code audit of every pipeline stage has identified **8 bugs requiring fixes before attempt 13** and **4 additional improvements** for post-sync correctness. The root failure chain in attempts 11-12 is:

1. `SnapServerChecker` probe validates AccountRange only — 9 of 11 ETC mainnet peers pass the probe but silently return empty on `GetTrieNodes`
2. The adaptive timeout floor (6s) is too short for static peers (Besu/core-geth) under post-download I/O load — they time out rather than serve
3. `statelessPeers` keyed by ephemeral session ID — the 9 dead peers reconnect with new IDs and bypass stateless filtering in round 2

This means every healing round dispatches to 9 known-broken peers, wastes 6+ hours on a second trie walk, and repeats. The fixes in this document eliminate all three root causes and harden the remaining pipeline.

---

## Pipeline Overview

```
1. Pivot Selection          PivotBlockSelector
2. Account Download         AccountRangeCoordinator (workers + DeferredWriteMptStorage)
3. Bytecode Download        ByteCodeCoordinator
4. Storage Download         StorageRangeCoordinator
5. Trie Healing             TrieNodeHealingCoordinator (via SNAPSyncController)
6. State Validation         SNAPSyncController.validateState()
7. Finalization             SNAPSyncController.finalizeSnapSync()
8. Regular Sync Startup     SyncController → RegularSync → BlockImporter
```

---

## Phase 1: Pivot Selection

**Files:** `src/.../sync/fast/PivotBlockSelector.scala`, `src/.../sync/snap/SNAPSyncController.scala`

### Status: SOUND

- Pivot lag: 64 blocks behind network head (geth-aligned)
- Minimum peers: `min-peers-to-choose-pivot-block` (default 1; **recommend increasing to 3 via config**)
- In-place pivot refresh implemented (more efficient than geth's full restart)
- Consecutive stall limit: 3 empty refreshes → fallback to fast sync
- **LocalPivot escape (Bug 2b):** Fixed. 5-minute timeout with exponential backoff. ✓

### Observation

With `min-peers-to-choose-pivot-block=1`, a single ETC mainnet peer can determine the pivot. With 9 connected peers reporting varying chain heights, this can produce a suboptimal pivot (too old or too new). Recommend setting to 3 in `mordor.conf` / `etc.conf`.

---

## Phase 2: Account Download

**File:** `src/.../sync/snap/actors/AccountRangeCoordinator.scala`

### Status: MOSTLY SOUND — 1 bug

**Working correctly:**
- Partial range resume via `AccountRangeProgress` messages and `postStop()` snapshot ✓
- Dynamic concurrency: `min(accountConcurrency, snapPeerCount)` cap (Bug 14 fix) ✓
- File-backed contract accounts list (64-byte records, ~4.7GB on ETC) (Bug 18b fix) ✓
- DeferredWriteMptStorage periodic flush every 50K accounts (Bug 18a fix) ✓
- Stagnation detection via consecutive timeout-based pivot refresh ✓

### Bug A-001: Silent trie flush failure

**File:** `AccountRangeCoordinator.scala` lines 1033–1037  
**Severity:** HIGH

```scala
// CURRENT — silently ignores flush failure:
private def flushTrieToStorage(): Unit =
  deferredStorage.flush().foreach { rootHash =>
    stateTrie = MerklePatriciaTrie[ByteString, Account](rootHash, deferredStorage)
    log.info(s"Flushed trie to storage...")
  }
```

When `flush()` returns `None` (storage failure), the trie is not rebuilt, the `accountsSinceLastFlush` counter is not reset, and processing continues silently. Up to 50K accounts can be in-memory but not persisted.

**Fix:** Replace `.foreach` with `match` to log the failure case:
```scala
private def flushTrieToStorage(): Unit =
  deferredStorage.flush() match {
    case Some(rootHash) =>
      stateTrie = MerklePatriciaTrie[ByteString, Account](rootHash, deferredStorage)
      log.info(s"Flushed trie to storage, root=...")
    case None =>
      log.error("Trie flush returned no root — in-memory state may be lost. Continuing with current trie.")
  }
```

---

## Phase 3: Bytecode Download

**File:** `src/.../sync/snap/actors/ByteCodeCoordinator.scala`

### Status: SOUND

- Bloom filter dedup: 73.5M → 2M unique codeHashes (Bug 20a fix) ✓
- File-backed unique codehash list (Bug 20a fix) ✓
- Phase handoff: completion waits for `noMoreTasksExpected && pendingTasks.isEmpty && activeTasks.isEmpty` ✓
- Per-hash failure tracking: after 10 consecutive failures, hash is skipped with warning ✓
- Recovery actor message forwarding (Bug 20c fix) ✓
- `PollRecoveryPeers` scheduler: fires every 5s (Bug 20c fix) ✓

---

## Phase 4: Storage Download

**File:** `src/.../sync/snap/actors/StorageRangeCoordinator.scala`

### Status: SOUND

- Streaming in 10K batches (Bug 20a fix) ✓
- Adaptive batch sizing: scales down on empty, up on success ✓
- Wall-clock stagnation watchdog: 2-minute no-activity timeout (separate from pivot-refresh path) ✓
- Empty storage account handling: after 5 empty responses, skip with warning ✓

---

## Phase 5: Trie Healing

**Files:** `src/.../sync/snap/actors/TrieNodeHealingCoordinator.scala`, `src/.../sync/snap/SNAPSyncController.scala`

### Status: THREE BUGS — primary failure cause

This is the phase where attempts 11-12 stalled. Three bugs interact to produce the failure:

### Bug H-001: `statelessPeers` keyed by ephemeral session ID

**File:** `TrieNodeHealingCoordinator.scala` line 100  
**Severity:** CRITICAL

```scala
private val statelessPeers = mutable.Set[String]()  // keyed by peer.id.value (session ID)
```

ETC mainnet peers that fail `GetTrieNodes` are marked stateless by session ID. When they disconnect and reconnect, they get a new session ID and bypass the filter. Round 2 dispatches to the same 9 broken peers with fresh IDs.

`statelessRemoteAddresses` does not exist in the current code.

**Fix:** Add a persistent set keyed by `remoteAddress`:
- Declare: `private val statelessRemoteAddresses = mutable.Set[String]()`
- Populate alongside `statelessPeers` in the empty-response handler (line 489)
- Check in `HealingPeerAvailable` before adding to `knownAvailablePeers` (line 219)
- Check in `tryRedispatchPendingTasks()` filter (line 583)
- Clear in `HealingPivotRefreshed` alongside `statelessPeers.clear()` (line 243)

Also: when a peer reconnects (new session ID, same address) and is added to `knownAvailablePeers`, remove the old peer's ID from `statelessPeers` and `peerCooldownUntilMs` to prevent stale ID accumulation.

### Bug H-002: GetTrieNodes adaptive timeout too short

**File:** `SNAPRequestTracker.scala` / `TrieNodeHealingCoordinator.scala`  
**Severity:** CRITICAL

`PeerRateTracker.targetTimeout()` floor: `2s × 3.0 TTL scaling = 6s minimum`. After 10+ hours of account/storage download I/O, Besu and core-geth cannot serve 361 trie nodes within 6s. Both static peers timed out in round 1 with all 64,152 tasks.

**Fix:** Set a 30s floor for `GetTrieNodes` requests in healing, either at the dispatch call site or in `SNAPRequestTracker`:
```scala
// Option A — at dispatch call site:
snapRequestTracker.trackRequest(peer, RequestType.GetTrieNodes, batch, 30.seconds)

// Option B — in SNAPRequestTracker:
requestType match {
  case RequestType.GetTrieNodes => rateTracker.targetTimeout().max(30.seconds)
  case _                        => rateTracker.targetTimeout()
}
```

### Bug H-003: `maxUnproductiveHealingRounds = 3` too aggressive; no progress-based reset

**File:** `SNAPSyncController.scala` line 146  
**Severity:** HIGH

`3 rounds × 2 min = 6 minutes` before giving up on healing. With only 2 effective peers (Besu + core-geth) under load, this is insufficient.

Additionally, the counter increments whenever a trie walk finds ANY missing nodes — even if 30K were healed since the last walk. A walk finding 60K missing (down from 94K) should reset the counter to 0 (progress was made), not increment it.

**Fix A:** Change `maxUnproductiveHealingRounds = 5` (10-minute window).

**Fix B:** Add `lastTrieWalkMissingCount: Int` field. In `TrieWalkResult` handler, reset the counter if `missingNodes.size < lastTrieWalkMissingCount` (progress was made).

### Additional healing observations (no bugs, but worth noting)

- **SP-001 is correctly implemented:** Static peers (Besu, core-geth) are never marked stateless regardless of empty responses (lines 488–503). This is correct and intentional.
- **Stagnation timer (5 min):** If no nodes healed for 5 minutes, coordinator clears tasks and signals `StateHealingComplete`. This is a safety valve, not a bug.
- **`pendingTasks` is unbounded:** Theoretically could grow to millions of entries across retries. Mitigated by the 5-minute stagnation timer clearing the list.
- **Peer cooldown cleanup:** `peerCooldownUntilMs` entries are never removed on peer disconnect (only on pivot refresh). Minor memory accumulation over long healing sessions. Cleaned up in Fix H-001.

---

## Phase 6: State Validation

**File:** `src/.../sync/snap/SNAPSyncController.scala` lines 2237–2317

### Status: ONE BUG — non-atomic fallback writes

State validation (missing from go-ethereum, added by Fukuii) is architecturally sound:
1. Validates account trie completeness
2. Validates all storage trie completeness
3. Re-triggers healing for missing nodes if found
4. Max 3 retries for "missing root node" before proceeding

### Bug V-001: Non-atomic SnapSyncDone in validation fallback paths

**Severity:** HIGH

Two fallback paths write only `SnapSyncDone` without pivot block/chain weight/best block info:

```scala
// Line 2291 — root node missing after max retries:
appStateStorage.snapSyncDone().commit()  // ONLY writes SnapSyncDone
context.parent ! Done

// Line 2315 — missing state root or pivot block:
appStateStorage.snapSyncDone().commit()  // ONLY writes SnapSyncDone
context.parent ! Done
```

The normal path (lines 2789–2798) is correctly atomic, batching all 4 writes. If the JVM crashes between line 2291/2315's commit and the `Done` message, the node restarts with `SnapSyncDone=true` but no pivot block header, no chain weight, and no best block info. Regular sync then starts against broken state.

**Fix:** Include `putBestBlockNumber(pivot)` in both fallback batches. If `pivotBlock` is `None`, trigger a retry instead of proceeding:
```scala
// Replace line 2291:
pivotBlock match {
  case Some(pivot) =>
    appStateStorage.snapSyncDone()
      .and(appStateStorage.putBestBlockNumber(pivot))
      .commit()
    context.parent ! Done
  case None =>
    context.parent ! RetrySnapSync("pivot block unknown during validation fallback")
}
```

### Oscillation risk (H→V→H→V loop)

If validation finds missing nodes → triggers healing → healing stagnates → triggers validation again → repeat. The `consecutiveUnproductiveHealingRounds` counter resets each time we re-enter healing from validation, so the counter never accumulates across oscillation cycles.

Add a global `healingValidationCycles` counter. After 5 oscillations, proceed with `StateValidationComplete` and let regular sync fetch missing nodes on-demand.

---

## Phase 7: Finalization

**File:** `src/.../sync/snap/SNAPSyncController.scala` lines 2772–2817

### Status: ONE BUG — wrong total difficulty formula

The normal finalization path is correctly atomic (lines 2789–2798): batches SnapSyncDone + storeBlock + storeChainWeight + putBestBlockInfo in a single RocksDB write.

### Bug V-002: Total difficulty estimated as `difficulty × blockNumber`

**File:** `SNAPSyncController.scala` line 2783  
**Severity:** HIGH

```scala
val estimatedTotalDifficulty = pivotHeader.difficulty * pivot  // pivot is the block number
```

This is not cumulative total difficulty. For ETC at block 19M with current block difficulty ~4.7T:
- `estimatedTotalDifficulty = 4.7T × 19M = 89e18`
- Actual ETC cumulative TD at block 19M is approximately `3.8e23` (sum of all difficulties from genesis)

The formula dramatically underestimates real TD. Peers comparing chain weights during ETH handshake will see Fukuii as having much less work than the actual chain. This can cause:
- Peers treating Fukuii as behind and refusing to accept blocks from it
- RegularSync's branch resolution incorrectly preferring peers' chains over the SNAP pivot

**Note:** go-ethereum also estimates TD after SNAP (embedded in block header from peers). The correct approach is to read the actual cumulative TD stored by `ChainDownloader`, which computes it correctly:
```scala
// ChainDownloader.scala lines 393-400 — correctly accumulates TD:
val parentWeight = blockchainReader.getChainWeightByHash(header.parentHash)
blockchainWriter.storeChainWeight(header.hash, parentWeight.increase(header))
```

**Fix:** Read actual stored chain weight from ChainDownloader's stored data; fall back to `pivotHeader.difficulty` (single block, safe lower bound) if not available:
```scala
val estimatedTotalDifficulty = blockchainReader
  .getChainWeightByHash(pivotHash)
  .map(_.totalDifficulty)
  .getOrElse {
    log.warning("No stored chain weight for pivot — using single block difficulty as lower bound")
    pivotHeader.difficulty
  }
```

---

## Phase 8: Regular Sync Startup

**Files:** `src/.../sync/SyncController.scala`, `src/.../sync/regular/BlockImporter.scala`

### Status: SOUND (dependent on correct finalization)

If finalization stores the pivot block correctly (Fixes V-001 and V-002), regular sync startup is correct:

- Starts from `bestKnownBlockNumber` (= pivot after clean finalization) ✓
- SNAP gap detection in `BlockImporter.resolveBranch()` handles missing headers between genesis and pivot ✓  
- `MissingNodeException` during block execution triggers on-demand node fetching from peers ✓
- Block is skipped (with warning) if missing node cannot be fetched after retries — this is correct behavior (defer to healing) ✓
- Receipt storage: ChainDownloader downloads receipts in parallel; `eth_getTransactionReceipt` returns null for pre-pivot receipts not yet downloaded ✓

### Observation: ChainDownloader is eager, not lazy (unlike geth)

go-ethereum downloads genesis→pivot headers lazily (on-demand during regular sync). Fukuii's `ChainDownloader` downloads headers + bodies + receipts eagerly in parallel with SNAP. This adds correctness (complete chain before regular sync) but delays SNAP completion. On ETC mainnet (~24M blocks), this is a significant download. This is a design choice, not a bug.

---

## Peer Infrastructure

**Files:** `src/.../network/NetworkPeerManagerActor.scala`, `src/.../sync/snap/SnapServerChecker.scala`

### SnapServerChecker Design Gap

**Severity:** MEDIUM (mitigated by statelessRemoteAddresses fix)

The AccountRange probe (`GetAccountRange` with start=end=ZeroHash) correctly identifies peers that serve SNAP state data. However, it does NOT validate `GetTrieNodes` capability — a separate SNAP sub-protocol used only in healing.

On ETC mainnet, 9 of 11 connected peers pass the AccountRange probe but return empty on `GetTrieNodes`. The `statelessRemoteAddresses` fix (Bug H-001) prevents these peers from receiving healing dispatches after being identified as non-serving.

A secondary `GetTrieNodes` probe would be the correct long-term fix but requires a valid state root at probe time (complex). Deferred.

### SNAP Server: No Rate Limiting

**Severity:** LOW

Incoming `GetTrieNodes` requests from other clients (Besu, core-geth syncing from Fukuii) are handled in the same actor mailbox as sync logic, without per-peer in-flight limits. A flooding peer could queue large numbers of requests. No evidence this is a current problem. Deferred.

### Peer Eviction: Static Peers Exempt

Confirmed: `evictNonSnapPeers()` correctly exempts `isStatic=true` peers. Besu and core-geth cannot be evicted. ✓

---

## go-ethereum Comparison: Key Differences

| Aspect | go-ethereum | Fukuii | Assessment |
|--------|-------------|--------|------------|
| Stateless tracking | By P2P node ID, cleared on disconnect | By session ID (bug) + remote addr (fix) | Fix H-001 aligns behavior |
| Healing timeout | Dynamic `TargetTimeout()` (6s–60s) | Same algorithm but needs 30s floor | Fix H-002 |
| Healing rounds | Implicit (run until queue empty) | 5 rounds (was 3) with progress detection | Fix H-003 |
| Healing peer dispatch | Capacity-weighted sort | Equal distribution | Acceptable with 2 serving peers |
| Post-healing validation | None (trust healing) | Full account+storage trie walk | Fukuii ahead |
| Crash recovery | Full task state persisted to DB | In-memory only (lost on crash) | Missing — deferred |
| TD after SNAP | From block header (embedded by peers) | `difficulty × block_number` (bug) | Fix V-002 |
| Non-atomic finalization | N/A (single codepath) | Two fallback paths are non-atomic | Fix V-001 |
| Genesis→pivot headers | Lazy (on demand) | Eager (ChainDownloader) | Design choice |

---

## Fixes Summary

### Must Fix Before Attempt 13

| # | Component | File | Lines | Description |
|---|-----------|------|-------|-------------|
| H-001 | TrieNodeHealingCoordinator | line 100, 219-223, 489, 583, 243 | Add `statelessRemoteAddresses` set |
| H-002 | SNAPRequestTracker / dispatch site | TBD | GetTrieNodes minimum 30s timeout |
| H-003a | SNAPSyncController | line 146 | `maxUnproductiveHealingRounds = 5` |
| H-003b | SNAPSyncController | lines 635-671 | Progress-based counter reset |
| V-001 | SNAPSyncController | lines 2291, 2315 | Atomic SnapSyncDone in fallback paths |
| V-002 | SNAPSyncController | line 2783 | Fix TD formula |
| A-001 | AccountRangeCoordinator | lines 1033-1037 | Log flush failure |

### Fix in Same PR

| # | Component | File | Description |
|---|-----------|------|-------------|
| H-007 | SNAPSyncController | `triggerHealingForMissingNodes` | Reset counter on re-entry from validation |
| H-008 | SNAPSyncController | add `healingValidationCycles` | Oscillation escape after 5 cycles |

### Deferred

| # | Description | Reason |
|---|-------------|--------|
| Crash recovery | Persist task state to DB like geth | Complex; doesn't affect current 10h+ sync |
| GetTrieNodes probe | Secondary probe in SnapServerChecker | Requires valid state root; H-001 mitigates |
| SNAP server rate limiting | Per-peer in-flight limits | No evidence of current problem |
| Capacity-weighted healing dispatch | Sort peers by capacity | Low impact with 2 serving peers |

---

## Attempt 13 Expected Log Signatures

**Round 1 healing (existing behavior preserved):**
```
Peer <id>@<addr> marked stateless for healing root ... (1/11 stateless)
...
Peer <id>@<addr> marked stateless for healing root ... (9/11 stateless)
[STATIC] Skipping stateless marking for static peer <besu-addr> (healing)
[STATIC] Skipping stateless marking for static peer <core-geth-addr> (healing)
```

**Round 2 healing (new behavior — 9 peers blocked by address):**
```
[HEALING] Skipping <addr> — address known stateless (prior GetTrieNodes failure)
... (×9)
Dispatching to 2 peers: <besu-addr>, <core-geth-addr>
Healed X/Y trie nodes from <besu-id>   ← X > 0 means success
```

**Finalization:**
```
SNAP sync completed successfully at block <pivot> (hash=...)
```
