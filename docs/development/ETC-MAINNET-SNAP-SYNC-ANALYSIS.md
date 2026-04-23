# ETC Mainnet SNAP Sync — Running Analysis Document

**Status:** ACTIVE — updated after each attempt
**Purpose:** Internal working document tracking every identified bug, root cause, fix status, and design insight. Do NOT commit — add to .gitignore. Rebuild from this document to preserve context window across sessions.

**Last updated:** 2026-04-12 — Attempt 21 JAR built (`06d1a42c3`). BUG-W7 + BUG-W6 FIXED. 2713/2713 tests pass.

---

## Attempt History Summary

| Attempt | Period | JAR / Key Fix | Outcome | Blocking Bug |
|---------|--------|---------------|---------|--------------|
| 1–5 | pre-Apr 7 | Various | Failed — network issues, restart loops | BUG-W5 (download schedulers) |
| Pre-20 | Apr 7–10 | Old JAR | Multiple SNAP restarts every ~13 min | BUG-W5 |
| **20** | Apr 10–12 2026 | `236457737` (BUG-W5 fix) | Incomplete — stuck in StateHealing | BUG-W6 + BUG-W7 |
| **21** | TBD | `06d1a42c3` (BUG-W7 + BUG-W6 fix) | **READY TO RUN** | None known |

---

## Attempt 20 Forensic Summary

**Period:** Apr 10 18:25 → Apr 12 12:54 (42.5 hours)
**JAR commit:** `236457737`

### Key Metrics

| Metric | Value |
|--------|-------|
| Walk duration (round 1/5) | ~21 hours |
| Total trie nodes walked | ~145M (109.2M at ETA-16min checkpoint; ~143–145M total) |
| Healing duration | ~31 hours |
| Nodes healed | 227,940 |
| Nodes abandoned | 1,316,854 |
| Pivot refreshes during StateHealing | 50+ |
| Spurious walk-killing restarts (BUG-W5) | 0 ✅ — fix confirmed |

### What Worked

- **BUG-W5 fix confirmed solid**: Zero destructive restarts across 42.5 hours. Walk ran 21 hours uninterrupted — first complete trie walk in ETC mainnet history.
- Phase guards: `checkAllDownloadsComplete()` guard and `startStateHealing()` duplicate coordinator guard both worked. StorageRangeCoordinator 0/0 re-fires after each pivot refresh caused no corruption.
- In-place pivot refresh: 50+ refreshes during StateHealing with no destructive restarts.
- Healing: 227,940 interior trie nodes retrieved from peers and flushed to RocksDB.
- Persisted state resume: account/storage state loaded correctly in 84s (1044/1044 contracts).

### What Failed

- **BUG-W6**: Pivot root drift forced repeated trie walks against fresh pivot roots absent from RocksDB. Each walk added ~697K nodes to queue; heal rate (~2-5K/2min) could not outpace discovery rate. Queue grew from 697K → 1.3M+ over overnight hours. Healing stagnated and abandoned everything.
- **BUG-W7**: `EthNodeStatus64ExchangeState.scala:93-95` — `Option.getOrElse` throws `IllegalStateException: Chain weight not found` continuously during live pivot refresh cycles. 100s of exceptions/hour. Causes PeerActor crash loops and peer cycling.
- **Healing stagnation**: Peers stopped serving GetTrieNodes after several hours. Root cause: peers may not store old interior trie nodes (Besu bonsai flat DB) or the specific node types needed aren't served.

---

## Bug Inventory

### BUG-W5 — Download-Phase Schedulers Kill Walk During StateHealing
**Status: FIXED** (commit `236457737`)
**Origin: Introduced by us** — We added `StateHealing` as a new phase concept but forgot to cancel the download-phase schedulers (`accountRangeRequestTask`, `bytecodeRequestTask`, `storageRangeRequestTask`, `accountStagnationCheckTask`, `storageStagnationCheckTask`) when transitioning into it. These schedulers were always running during download phases by design. The new phase was added without updating the lifecycle.
**Effect:** Each scheduler fires every 1s during StateHealing → calls `maybeRestartIfPivotTooStale()` → hard-restarts SNAP sync every ~13-14 minutes. Walk could never complete.
**Fix:** Cancel all 5 schedulers at entry of `startStateHealing()` and `startStateHealingWithSavedNodes()`. Add `if (currentPhase == StateHealing) return false` guard in `maybeRestartIfPivotTooStale()`. Reset `trieWalkInProgress` in `restartSnapSync()`.

---

### BUG-W6 — Walk Forced Against Fresh Pivot Roots Absent From RocksDB
**Status: FIXED** (commit `06d1a42c3`)
**Origin: Introduced by us** — Commit `2ee32fd55` ("Fix D") added `WALK-SKIP override`: `if (!rootInDb) → force walk`. Correct intent for the original snapshot root, but fired for ALL absent roots — including live pivot roots advancing every ~13 min that were never downloaded.

**Effect:** Every pivot refresh → new absent root → override fires → walk starts → finds root missing → queues ~697K nodes → healing stagnates → 1.3M abandoned. Healing throughput (~2-5K nodes/2min) could never outpace discovery.

**The Core Design Flaw:** Conflating (1) snapshot root (what we downloaded — frozen validation target) with (2) live pivot root (current chain head — only needed for SNAP serve window).

**Fix applied:** Added `snapSyncSnapshotRoot: Option[ByteString]` frozen at `startStateHealing()` + `startStateHealingWithSavedNodes()` entry. `startTrieWalk()` rootInDb check uses `snapSyncSnapshotRoot.orElse(stateRoot)` instead of live `stateRoot`. Reset in `restartSnapSync()`.

**Reference validation:** Directly mirrors go-ethereum v1.10.26 `Syncer.Sync(root) → s.root frozen at entry` and Besu v22.7.3 `SnapSyncState.isExpired()` pattern (all healing requests bound to frozen pivot header's state root). Both PoW-era reference clients freeze the healing root at entry; routine chain advancement never affects the healing target.

---

### BUG-W7 — Chain Weight Not Found During Peer Handshake
**Status: FIXED** (commit `06d1a42c3`)
**Origin: Pre-existing, partially fixed — race condition unique to our SNAP pivot bootstrap**

**File:** `src/main/scala/com/chipprbots/ethereum/network/handshaker/EthNodeStatus64ExchangeState.scala` lines 92–96

**Prior fixes:** `3d6566fa2` (startup header orphan), `d14e4080c` (idempotent repair at restart). Both addressed startup-time cases; the runtime race was not covered.

**Root cause:** `updateBestBlockForPivot()` did 3 separate `.commit()` calls (header, chain weight, best block info). A peer connecting between commits 1 and 2 would get the new header from `getBestBlockHeader()` but find no chain weight yet → throw `IllegalStateException` → PeerActor crash → `OneForOneStrategy` restart → same race again → 100s/hour continuously.

**Why neither go-ethereum nor Besu had this:** In full-sync, every block's TD is stored atomically as part of normal chain extension. Our SNAP sync pattern of storing a pivot block separately (for fork ID status) without full chain extension is unique to our implementation. go-ethereum v1.10.26 read TD fresh via `chain.GetTd()` but it was always present. Besu v22.7.3 cached TD in `volatile Difficulty totalDifficulty` updated before DB commit.

**Fix applied:**
1. Safe `getOrElse` fallback: `ChainWeight.totalDifficultyOnly(bestBlockHeader.difficulty)` — header difficulty always available, same estimate as `updateBestBlockForPivot()` already uses. Logs a WARNING (not a crash).
2. Atomic commit: `blockchainWriter.storeBlockHeader(header).and(storeChainWeight(...)).commit()` — eliminates race window between header and chain weight writes. Mirrors Besu's `appendBlockHelper()` single-commit pattern.

---

### FAILURE-1 — Same-Root Pivot Refresh Triggered Full Restart
**Status: ALREADY FIXED** (present in current codebase, fixed before Attempt 20)
**Origin: Pre-existing** — log evidence Apr 7: `"Pivot refresh: new root same as old. Falling back to full restart."`
**Current code:** `completePivotRefreshWithStateRoot()` handles same-root correctly — early return, no restart, just updates pivot block number:
```scala
if (stateRoot.contains(newStateRoot)) {
  // Root unchanged — do NOT restart. Advance block number, keep healing.
  ...
  return
}
```
**No action needed.**

---

### OBS-1 — Healing Stagnation: Peers Not Serving GetTrieNodes
**Status: DESIGN ISSUE — requires architectural rethink**
**Observed:** During Attempt 20, healing nodes healed/2min dropped to 0 repeatedly. 12 SNAP peers connected but serving empty responses. Stagnation timeout (300s) abandoned 1.3M+ tasks.

**Root cause candidates:**
1. **Besu bonsai flat DB**: Besu's bonsai storage format doesn't store Merkle Patricia Trie interior nodes as individual addressable entries. When Fukuii sends `GetTrieNodes(root, path)`, Besu may not be able to serve interior nodes for old/pruned roots.
2. **ETC network peer quality**: Most ETC SNAP peers are core-geth or Besu instances. Core-geth SNAP implementation is known to have issues (SNAP serving bugs were fixed as part of our work). If core-geth isn't serving GetTrieNodes correctly, the peer pool is largely ineffective for healing.
3. **Root staleness**: SNAP protocol has a serve window. Peers serving SNAP state for block 24352189 may only do so for ~128 blocks. After the pivot drifts, the old root is outside the serve window.

**Design alternative: Local Trie Reconstruction (no peers needed)**
The healing approach (GetTrieNodes) asks peers for pre-computed interior nodes. But those nodes can be computed locally:
- We have all 85M+ account leaves already in RocksDB from the SNAP account range download
- Every interior node (branch, extension) is a deterministic SHA3 hash of its children
- We can reconstruct the full Merkle Patricia Trie from leaves upward — no network needed

**Local reconstruction advantages:**
- CPU-only: no peer dependency, no network latency, no stagnation risk
- Speed: millions of nodes/min (CPU + RocksDB) vs. 1-2.5K/min via GetTrieNodes
- Deterministic: will always succeed if leaf data is correct
- Scale: all 12 CPU threads usable

**Estimated local reconstruction time:** At ~1M RocksDB operations/sec, building a hexary trie with 85M leaves (depth ~8, ~170M interior nodes) ≈ 2-4 hours. Compare to GetTrieNodes at 1.5K nodes/min → 1.3M nodes ≈ **14+ days**. Local reconstruction is ~80-100× faster.

**Implementation approach:** A `TrieReconstructionPass` that:
1. Iterates all account leaf nodes in RocksDB (already present)
2. Builds hexary MPT bottom-up using SHA3 hashing
3. Writes all computed interior nodes to RocksDB under the same key format as SNAP downloads
4. Verifies result root matches the target snapshot root
5. If root matches → StateHealingComplete(abandoned=0, healed=N)

This is architecturally cleaner than GetTrieNodes healing for large tries and removes all peer dependency during the healing phase.

---

### OBS-2 — Mass SNAP Peer Disconnect (Apr 12 09:18)
**Status: LOW PRIORITY — unresolved root cause**
**Observed:** 8 SNAP peers disconnected within 1 second (8→1) at 09:18:19. Not a gradual loss — simultaneous mass disconnect.
**Suspected:** May be related to BUG-W7 exception storm causing PeerActor restart cascade, or may be a network-level event (CDN/ISP coordination, ETC network maintenance window).
**Impact:** Left system with 1 SNAP peer for several hours.

---

## Design Insights for Attempt 21

### Critical Architecture Change: Snapshot Root vs Live Pivot Root

During StateHealing, the system must distinguish:

```
snapSyncSnapshotRoot  = the root at download time (frozen)  → validation target
currentPivotRoot      = live chain head (advancing)          → SNAP serve window only
```

The validation walk MUST target `snapSyncSnapshotRoot` — not the live pivot. The live pivot is only needed to stay within the SNAP serve window for incoming GetAccountRange/GetStorageRanges requests from other peers.

### Healing Strategy: Local First, Network Second

Priority order:
1. **Local trie reconstruction** (if leaf data is confirmed complete): Build interior nodes from owned leaves. No peers. Hours instead of days.
2. **GetTrieNodes healing** (supplementary): For any nodes the local reconstruction can't compute (missing leaves = gaps in downloaded ranges). Only use peers to fill specific gaps, not to bulk-heal 1.3M nodes.
3. **SNAP re-download** (last resort): If local reconstruction reveals the leaf data has gaps, re-run SNAP account range download for the missing ranges.

### Pivot History Logging

**User-suggested improvement:** Rather than treating each pivot root as ephemeral (overwriting state), log ALL pivot state roots encountered during SNAP sync from start to completion. This serves:
1. **Debug continuity**: Understand the full chain of pivots in any sync attempt
2. **Post-sync validation**: After regular sync starts, confirm the snapshot root was in the canonical chain
3. **BUG-W6 fix support**: The first entry in this log is `snapSyncSnapshotRoot` — the frozen validation target

**Implementation:**
```scala
// AppStateStorage additions:
def putSnapPivotHistoryEntry(blockNum: BigInt, root: ByteString, timestamp: Long): Unit
def getSnapPivotHistory(): Seq[(BigInt, ByteString, Long)]
// OR: simple append-only text file at datadir/snap-pivot-history.log
```

Each pivot refresh appends: `timestamp | blockNumber | stateRoot | reason (proactive/stagnation/healing)`

---

## Fix Status

| Priority | Bug | Commit | Status |
|----------|-----|--------|--------|
| 1 | **BUG-W7** (chain weight crash) | `06d1a42c3` | ✅ FIXED |
| 2 | **BUG-W6** (walk against absent roots) | `06d1a42c3` | ✅ FIXED |
| 3 | **Local trie reconstruction** (healing throughput) | — | OPEN — design only |
| 4 | **Pivot history log** | — | OPEN — not yet implemented |

## Next: Attempt 21

JAR `06d1a42c3` is built and ready. Expected behavior with both fixes:
- **Healing phase**: coordinator heals against frozen snapshot root, queue approaches zero
- **Pivot refreshes every ~13 min**: `stateRoot` advances, healing root stays frozen — no new discovery events
- **Peer stability**: chain weight fallback prevents PeerActor crash storms; peer count stable during pivot refreshes
- **Walk**: fires once after healing completes with 0 abandoned → ~21h → `StateHealingComplete` → first successful ETC mainnet SNAP sync

Remaining risk: healing throughput (GetTrieNodes). If peers still can't serve the snapshot root (stale root outside SNAP serve window), the 1.3M abandoned situation could recur — but with BUG-W6 fixed, the queue won't grow uncontrollably from new pivot refreshes.

---

## Code Locations Reference

| Component | File | Key Lines |
|-----------|------|-----------|
| `startStateHealing()` | SNAPSyncController.scala | 2689–2769 |
| `startStateHealingWithSavedNodes()` | SNAPSyncController.scala | ~2774 |
| `maybeRestartIfPivotTooStale()` | SNAPSyncController.scala | ~3027+ |
| `startTrieWalk()` / WALK-SKIP logic | SNAPSyncController.scala | 2596–2614 |
| `completePivotRefreshWithStateRoot()` | SNAPSyncController.scala | 3165–3230 |
| `updateBestBlockForPivot()` | SNAPSyncController.scala | 3143–3162 |
| `restartSnapSync()` | SNAPSyncController.scala | ~3255–3354 |
| `ScheduledTrieWalk` handler | SNAPSyncController.scala | 945 |
| `StateHealingComplete` handler | SNAPSyncController.scala | 753 |
| `TrieWalkResult` handler | SNAPSyncController.scala | 850 |
| `HEAL-PULSE` / healing coordinator | TrieNodeHealingCoordinator.scala | various |
| Chain weight throw (BUG-W7) | EthNodeStatus64ExchangeState.scala | 93–95 |
| Pivot history logging (proposed) | AppStateStorage + SNAPSyncController | new |

---

## Log Files Reference (Attempt 20)

| File | Period | Key Events |
|------|--------|------------|
| `fukuii.7.log.zip` | Apr 7 06:41 → Apr 8 01:02 | Account download 30-45%, FAILURE-1 same-root restart |
| `fukuii.6.log.zip` | Apr 8 01:02 → Apr 8 21:31 | Account download at ~33% |
| `fukuii.5.log.zip` | Apr 8 21:32 → Apr 9 21:50 | Account download, BUG-W5 rapid walk cycles |
| `fukuii.4.log.zip` | Apr 9 21:50 → Apr 10 09:07 | Rapid walk/heal cycles, multiple restarts |
| `fukuii.3.log.zip` | Apr 10 09:07 → Apr 10 20:25 | BUG-W5 JAR starts 18:25, walk begins 19:24 |
| `fukuii.2.log.zip` | Apr 10 20:25 → Apr 11 16:06 | Long walk in progress, 63.8M→109.2M nodes |
| `fukuii.1.log.zip` | Apr 11 16:06 → Apr 12 07:51 | Walk completes ~16:22, BUG-W6 pattern 17:59, healing 227K, BUG-W7 02:18 |
| `fukuii.log` | Apr 12 07:51 → 12:54 | Stuck state, only pivot refreshes, mass disconnect 09:18, manual stop 12:54 |

All logs at: `/media/dev/2tb/data/blockchain/fukuii/etc/logs/`

---

## Reference Client Analysis (PoW Era)

ETC uses ETH64/ETH68 (PoW). Researched **pre-Merge PoW-era code** of both reference clients for Attempt 21 fix design. Current HEAD targets ETH69 (PoS) — not directly applicable to ETC.

**Versions analyzed:** go-ethereum v1.10.26 (last PoW release), Besu v22.7.3 (Sep 23 2022, just post-Merge).

### BUG-W7 pattern in reference clients

| Client | TD source for ETH status | Race protection |
|--------|--------------------------|-----------------|
| go-ethereum v1.10.26 | `chain.GetTd(hash, number)` — direct DB read | None — TD assumed always present (full sync from genesis) |
| Besu v22.7.3 | `volatile Difficulty totalDifficulty` in-memory cache | Single `updater.commit()` bundles header + TD atomically in `appendBlockHelper()` |
| **Fukuii (ours)** | `getChainWeightByHash()` — DB lookup | **None — 3 separate commits with race windows** |

Our bug is unique to SNAP sync pivot bootstrap: we store a pivot block without full chain extension, so the header/chain-weight atomic invariant that full-sync maintains by design is not guaranteed.

**Long-term fix (post-Attempt 21):** Cache chain weight in a `@volatile` field updated alongside DB writes (Besu pattern) so `createStatusMsg()` never needs a DB lookup.

### BUG-W6 pattern in reference clients

Both reference clients freeze the healing root at entry and never change it for routine chain advancement:
- **go-ethereum v1.10.26:** `Syncer.Sync(root) → s.root = root` frozen; healer's `StateSync` scheduler constructed from that root. Pivot change = cancel + restart entire sync.
- **Besu v22.7.3:** `SnapSyncState.isExpired(request)` checks `request.getRootHash() != pivotBlockHeader.getStateRoot()` — requests for wrong root discarded. Pivot switch is explicit, not triggered by routine 13-min chain advancement.

**Key difference from Fukuii before fix:** We updated `stateRoot` on every pivot refresh, then used the same var for the walk target. Reference clients use two separate concepts (frozen healing root vs live chain head) — which is exactly what `snapSyncSnapshotRoot` now provides.

### ETH69 is NOT applicable to ETC

Post-Merge changes to avoid:
- `PivotSelectorFromFinalizedBlock` (Besu Oct 2022+) — requires consensus layer, PoS only
- ETH69 `BlockRangeUpdate` messages — ETC uses ETH68
- TD removal from status message — ETC must still send TD

---

## Root Cause Classification

| Bug | Introduced By | Classification |
|-----|--------------|----------------|
| BUG-W5 (schedulers kill walk) | Our StateHealing implementation — forgot to cancel download schedulers on phase transition | **Our bug — incomplete phase transition** |
| BUG-W6 (walk against absent roots) | Our "Fix D" commit `2ee32fd55` — WALK-SKIP override too broad, fires for ALL absent roots not just snapshot root | **Our bug — fix was too general** |
| BUG-W7 (chain weight throw) | Pre-existing + partially fixed by `3d6566fa2` + `d14e4080c` — race condition in pivot refresh commit sequence | **Pre-existing + incomplete fix** |
| FAILURE-1 (same-root restart) | Pre-existing — already fixed in current codebase before Attempt 20 | **Pre-existing, already resolved** |
| OBS-1 (healing stagnation) | Architectural — GetTrieNodes healing not suited for large tries with low-quality peer pools | **Design limitation, not a code bug** |
| OBS-2 (mass peer disconnect) | Unknown | **Unclassified** |

### Pattern Recognition: Our Recurring Failure Mode

Both BUG-W5 and BUG-W6 follow the same pattern:
1. We added a new feature/fix to handle a specific case
2. The new code was correct for the specific case it targeted
3. But it didn't account for interactions with the existing scheduler/trigger ecosystem
4. A pre-existing mechanism (scheduler, timer, message) continued firing in the new context and caused unexpected behavior

**Development checklist item to add:** When adding a new phase or state transition in SNAPSyncController, explicitly enumerate EVERY periodic scheduler and message handler that was active in the previous phase and verify whether it should continue, be cancelled, or be guarded in the new phase.

---

## Open Questions for Investigation

1. **Can peers serve GetTrieNodes for the ORIGINAL snapshot root after 50+ pivot refreshes?** — If the snapshot root is now 650+ blocks old (50 pivots × 13 blocks), is it outside the SNAP serve window?

2. **What fraction of the 1.3M abandoned nodes were interior trie nodes vs. leaf nodes?** — If they're mostly interior nodes, local reconstruction is the right fix. If mostly leaves, we have a gap in the account range download.

3. **Does the walk's "1 missing node" represent the root hash itself or a first-level branch?** — If it's the root, then 1 GetTrieNodes success = root in DB, and next walk reveals thousands more missing. If it's the full tree root, all 1.3M missing nodes cascade from that 1 entry.

4. **Why did the healing coordinator fail to get 1 specific node from 12 SNAP peers?** — Stagnation on 1 node with 12 connected peers suggests all 12 are marking this specific root as stateless. Is this a Besu bonsai limitation (can't serve this root age)?

5. **What is the actual throughput floor for local trie reconstruction on the NUC?** — Measure SHA3 throughput × RocksDB write throughput for 1M nodes as a baseline.
