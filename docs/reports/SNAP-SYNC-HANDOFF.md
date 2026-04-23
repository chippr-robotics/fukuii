# Fukuii SNAP Healing Pipeline — Handoff: Continue ETC Mainnet Attempt 24

## For the Fresh Thread

This document is a complete handoff. The prior session ran out of context. **The JAR was stopped by the user at 13:17:57** — the node is NOT running. This document tells you everything you need to continue without re-discovering what was already fixed.

**Do NOT re-read previous plans.** This document supersedes all prior plans.
**Do NOT re-examine bugs marked FIXED/CONFIRMED CLEAN below.**
**Do start by reading the live log and this entire document** before proposing anything.

---

## Current State (as of 2026-04-14, JAR STOPPED at 13:17:57)

- **JAR:** `fukuii-assembly-0.1.240.jar`, hash `8f8e0217` — still the correct JAR to use
- **Log:** `/media/dev/2tb/data/blockchain/fukuii/etc/logs/fukuii.log`
- **Datadir:** `/media/dev/2tb/data/blockchain/fukuii/etc/`
- **Phase at stop:** Validation walk running, 15.5M/~85M nodes scanned, **0 missing nodes found**, ETA ~3.5h remaining
- **Key finding: the trie at root `3ee9746f` may already be complete** — 15.5M nodes scanned with zero missing

### Full Sequence This Attempt (CRITICAL READ)

```
12:39:55  JAR started (v0.1.240-c2a9f42)
12:40:37  All downloads complete → healing phase
12:41:15  [HEAL-RESUME] Restoring 1370636 missing nodes — staleness=190 blocks (< 1000, pass-through)
12:41:15  Proactive pivot refresh → root 00f21acc → 3ee9746f
12:41:52  1,370,117 nodes queued (487 already in storage, 32 duplicates)
12:41:54  HealingPivotRefreshed applied → 1,361,926 pending
12:42:04  HEAL-PULSE: healed=0, +0, stagnation 1/3  ← expected (post-cooldown)
12:42:23  First healed batch: 148 nodes
12:43:00  Cumulative 1,319 healed → ~880 nodes/min

[timeout storm builds]
12:47-48  10+ consecutive request timeouts (reqIds 22,45,44,43,42...)
12:48:44  *** HEAL-IDLE-TIMEOUT (309s, no healed progress): abandons 1,360,417 tasks ***
          [HEAL-ABANDON] root=3ee9746f, healed=1978, abandoned=1360417
12:48:44  Simultaneous in-flight responses arrive: +369 healed (2,347 total)
12:49:15  HEAL-PULSE: healed=2347, +369 | pending=0 active=12 idleFor=30s
~12:51:37 Validation walk starts (coordinator idle, pending=0)
13:09-17  Walk: 15.5M nodes scanned, ~10K nodes/s, ETA ~3.5h, walkCompletedPrefixes=0/256
          HEAL-PULSE: pending=0, active=0, idleFor=1200-1560s (coordinator truly idle)
13:17:57  JAR STOPPED by user
```

### Why pending=0 During the Walk Is GOOD News

The HEAL-PULSE shows `pending=0, active=0` with the walk running. Two explanations:

1. **The trie is complete:** 15.5M scanned nodes are ALL present in local RocksDB. The 1.36M "abandoned" nodes may be false positives — queued as "missing" against old roots from prior healing rounds, but actually PRESENT in the current trie.

2. **Walk hasn't completed a subtree yet:** `walkCompletedPrefixes=0/256` — the interleave only dispatches `QueueMissingNodes` when a subtree completes. Until the first subtree finishes, we can't know if there are missing nodes at deeper levels.

If explanation 1 is correct: **walk completes, 0 missing nodes → healing done → sync completes.**
If explanation 2: the first subtree completion will reveal how many are truly missing.

---

## What Was Fixed This Session (DO NOT RE-EXAMINE)

### Fix 1: BUG-STAGNATION-POST-REFRESH ✅ APPLIED

**File:** `src/main/scala/com/chipprbots/ethereum/blockchain/sync/snap/actors/TrieNodeHealingCoordinator.scala`

**Problem:** `consecutiveStagnations` was not reset in `HealingPivotRefreshed` handler. If counter was at 2/3, the very next stagnation check fired `HealingStagnated` as a false positive — healing got a fresh pivot and immediately got flagged as permanently stuck.

**Fix (2 lines added after `pivotRefreshRequestedAtMs = 0L`, ~line 314):**
```scala
consecutiveStagnations = 0
lastPulseHealedCount = totalNodesHealed  // start fresh velocity window post-refresh
```

**Verified in attempt 24:** stagnation 1/3 fired at 12:42:04 with counter starting from 0 (fresh session) — correct behavior.

---

### Fix 2: BUG-HEAL-RESUME-STALE ✅ APPLIED

**File:** `src/main/scala/com/chipprbots/ethereum/blockchain/sync/snap/SNAPSyncController.scala`

**Problem:** The HEAL-RESUME recovery path in `checkAllDownloadsComplete()` called `startStateHealingWithSavedNodes()` unconditionally. This skips the 5-hour trie walk — and also skips every staleness check the walk path has. On restart, inherited 1.37M stale nodes for a root ~3,469 blocks old. ETC SNAP serve window is ~128 blocks. Healed at ~48 nodes/min with no escape (local Besu trickling nodes reset the stagnation counter on every 2-minute pulse).

**Fix:** Staleness gate wrapping the saved-nodes load:
```scala
if (savedNodes.nonEmpty) {
  val savedRound = appStateStorage.getSnapSyncHealingRound()
  val pivotBlockNum = pivotBlock.getOrElse(BigInt(0))
  val networkBest   = currentNetworkBestFromSnapPeers().getOrElse(BigInt(0))
  val staleness     = if (networkBest > pivotBlockNum) networkBest - pivotBlockNum else BigInt(0)
  val isTooStale    = networkBest > 0 && staleness > snapSyncConfig.maxHealingPivotAgeBlocks
  if (isTooStale) {
    log.warning(s"[HEAL-RESUME-STALE] Saved ${savedNodes.size} nodes are $staleness blocks stale ...")
    startStateHealing()  // fresh pivot + fresh walk
  } else {
    // ... existing HEAL-RESUME path
    startStateHealingWithSavedNodes(savedNodes)
  }
}
```

**Verified in attempt 24:** pivot was 190 blocks stale (< 1000 threshold) → correctly loaded saved nodes. No `[HEAL-RESUME-STALE]` warning in log, which is the correct outcome.

---

## New Bug Found This Attempt: BUG-HEAL-IDLE-TIMEOUT

**Status:** IDENTIFIED. Not yet fixed. Needs investigation before next restart.

**File:** `src/main/scala/com/chipprbots/ethereum/blockchain/sync/snap/actors/TrieNodeHealingCoordinator.scala`

**Problem:** There is a hard-coded 309-second idle stagnation timer (`HealingCheckIdle` or similar) that is SEPARATE from `FIX-STAGNATION-LIMIT`. When it fires:
1. It abandons ALL pending tasks (1.36M in this case)
2. It triggers the validation walk (nodes "will be re-discovered during state validation trie walk")
3. It fires **before** `FIX-STAGNATION-LIMIT` can complete (which needs 3 × 2-min pulses = 360s minimum)

**Why it fires:** The 309s timer fires when there's been no healed progress for 309 seconds. During the timeout storm at 12:47-48, all requests were timing out but the timeout handlers were re-queuing tasks — so `totalNodesHealed` wasn't incrementing, triggering the idle timer.

**Log evidence:**
```
12:48:44  Healing stagnation timeout (309s, no progress). Likely cause: peers lack GetTrieNodes
          support or all peers offline. Abandoning 1360417 remaining tasks.
          Nodes will be re-discovered during state validation trie walk.
12:48:44  [HEAL-ABANDON] root=3ee9746f, healed=1978, abandoned=1360417
```

**What to investigate in the next session:**
1. Where is the 309s timer defined? Look for `HealingCheckIdle`, `scheduleOnce`, or similar in `TrieNodeHealingCoordinator.scala`
2. Is this timer necessary, or is it redundant with `FIX-STAGNATION-LIMIT`?
3. What should happen when it fires? If the walk IS the right fallback, the timer is correct behavior. The real question is whether abandoning 1.36M tasks and starting a walk is right vs. waiting for `HealingStagnated` to trigger a pivot refresh.

**Key question:** Should we extend/remove the 309s timer, or does the walk correctly resolve the situation? If the walk completes with 0 missing nodes, the 309s timer was actually the RIGHT thing to do (start a confirmatory walk instead of endless retries against a possibly-complete trie).

---

## The Structural Bug Pattern (CRITICAL — Read This Before Writing Any Fix)

Every session has discovered bugs of the same type: **path coverage failures**.

A fix is correctly implemented on the **forward (happy) code path**, but an equivalent check is NOT added to one or more **recovery/alternate paths** that bypass the fixed code.

| Bug | Forward Path Fixed | Recovery Path Missed |
|-----|-------------------|---------------------|
| BUG-HEAL-RESUME-STALE | `TrieWalkResult` handler (~line 967) has staleness check | `checkAllDownloadsComplete` HEAL-RESUME path skips the walk, skipping all walk-path checks |
| BUG-STAGNATION-POST-REFRESH | Counter resets correctly when `recentHealed > 0` | `HealingPivotRefreshed` is a state transition that should also reset the counter |
| (earlier) FIX-STALE-HEAL-GUARD | Added at walk result | Not added at HEAL-RESUME (fixed this session) |

**When writing any new fix, explicitly ask:** "What other code paths reach a similar outcome that might bypass this check?" Look especially at:
1. Recovery/resume paths that load persisted state
2. Event handlers that represent state transitions (refresh, pivot change, restart)
3. Anything that calls `startX()` or `resumeX()` directly without going through the main initialization sequence

---

## The Core Unresolved Problem: Healing Throughput Gap

**This is the work remaining for this thread.**

### What We Know

- **Actual:** ~880 nodes/min with 1.36M pending → ~25h ETA
- **Expected:** Geth achieves ~50,000+ nodes/min in comparable scenarios → 27 min ETA
- **Gap:** 57× below expected — this is not a minor inefficiency, this is a structural problem

### What We Observed

In attempt 24 log at 12:42:04 (first HEAL-PULSE): `healed=0, +0` with `stagnation 1/3`.

In earlier attempts, **timeout storms** were observed:
```
12:19:34 - 12:19:54  10 consecutive TrieNode request timeouts across multiple peers
```

The timeout rate at ~91% explains much of the throughput gap. But **why** are requests timing out?

### Hypotheses to Investigate (ordered by likelihood)

1. **Batch dispatch is not pipelined:** Workers may be sending one request, waiting for response, then sending the next. Geth pipelines multiple in-flight requests per peer. Check `TrieNodeHealingCoordinator` dispatch logic — is it scheduling new requests as soon as a response arrives, or waiting for the full batch?

2. **Request batch size too small or too large:** If batches contain nodes the peer doesn't have (wrong root), every node in that batch times out. With 1.36M nodes for a root that may be partially stale, this could drive the timeout rate high.

3. **Peer budget depletion:** With the global 5-req/peer budget shared across coordinators, healing may be starved. Check `UpdateMaxInFlightPerPeer` messages during healing phase — is the budget actually reaching the healing coordinator?

4. **Root freshness of individual pending nodes:** The 1.36M nodes were discovered against root `00f21acc` but healing is running against `3ee9746f` (post-proactive-pivot-refresh). Nodes that exist in one trie but not the other cause timeouts. How many of the 1.36M nodes are actually present in the current healing root?

5. **`QueueMissingNodes` saturation:** If `TrieNodeHealingCoordinator` has 1.36M entries in `pendingTasks`, priority queue operations may be slow. Measure: does time-per-HEAL-PULSE correlate with queue depth?

### Key Files for Investigation

```
src/main/scala/com/chipprbots/ethereum/blockchain/sync/snap/
  SNAPSyncController.scala                          # Controller orchestration
  actors/TrieNodeHealingCoordinator.scala           # Healing coordinator (priority queue, dispatch)
  actors/TrieNodeHealingWorker.scala                # Per-peer request/response
  actors/Messages.scala                             # Message types
```

**In `TrieNodeHealingCoordinator.scala`, the key dispatch loop:**
- Look for how `scheduleHealingRequests()` (or equivalent) fires new requests
- Does it dispatch as many as `maxInFlightPerPeer` per peer simultaneously?
- Is there a `RequestTrieNodeHealing` message that fires on a schedule vs. reactive firing after each response?

**In `TrieNodeHealingWorker.scala`, the timeout handling:**
- How long is the timeout? Is it adaptive (like `AccountRangeWorker`)?
- When a timeout fires, does it requeue the same nodes immediately? That would cause retry storms.

**In `SNAPSyncController.scala`:**
- `UpdateMaxInFlightPerPeer` — when is this sent to the healing coordinator during healing?
- Is the budget set to a reasonable value (e.g., 5 per peer) once in healing phase?

---

## All Previously Fixed Bugs (CONFIRMED CLEAN — DO NOT RE-EXAMINE)

These were fixed across attempts 1-24. They are all working. Do not re-investigate.

### BUG-W Series (Walk/Healing pipeline)
- **BUG-W5:** Walk ran 21h due to unbounded visited set — FIXED (attempt 20)
- **BUG-W6:** `snapSyncSnapshotRoot` not frozen at healing entry — FIXED (attempt 21)
- **BUG-W7:** Chain weight atomic commit + safe fallback — FIXED (attempt 21)
- **BUG-W8, W10, W11, W-STAGNATION-OUTCOME, W-PRIORITY:** All fixed prior to attempt 22

### PERF-WALK-CHECKPOINT (attempt 23)
- Async batched checkpoints every 32 subtrees via `blocking-io-dispatcher`
- Eliminated 8h synchronous blockage that was making root go stale

### ARCH-WALK-HEAL-INTERLEAVE (attempt 23)
- Healing coordinator created BEFORE walk starts
- `TrieWalkSubtreeResult` feeds nodes to coordinator immediately — healing runs concurrent with discovery

### BUG-HEAL-SCHED (attempt 23)
- `startHealingRequestScheduler()` cancels before creating — no double-fire

### FIX-STALE-HEAL-GUARD (attempt 23)
- At `TrieWalkResult`: if pivot >1000 blocks stale, triggers `refreshPivotInPlace` before healing

### FIX-STAGNATION-LIMIT (attempt 23)
- `consecutiveStagnations` counter fires `HealingStagnated` at 3/3
- PLUS: reset on `HealingPivotRefreshed` (fixed this session — attempt 24)

### Bug 20 (OOM fixes, earlier sessions)
- File-backed `contractAccounts`/`contractStorageAccounts`
- `DeferredWriteMptStorage` periodic flush
- Bloom-filtered unique codeHashes
- `visited` set fix in `MptStorage.decodeNode()`

### Confirmed Clean (audited this session, no action needed)
- **Checkpoint overlap:** Two successive async checkpoints at 32/64 are a superset. No data loss.
- **walkCurrentNodes snapshot:** `.toSeq.toVector` before `Future` — immutable, safe.
- **Dead coordinator after `HealingStagnated`:** `.foreach` guard → silent drop. Pekko dead-letter, no crash.
- **QueueMissingNodes during interleave:** Actor ordering preserved, `pendingHashSet` deduplicates.
- **1.37M missing nodes are genuine:** DeferredWriteMptStorage investigation confirmed flush timing is NOT the cause. Missing nodes are genuine SNAP protocol gaps, not write timing bugs.

---

## Why Mordor Worked But ETC Mainnet Struggles

This was the user's key insight: "we didn't used to have this problem... mordor snap sync worked — these bugs are our own."

| Metric | Mordor | ETC Mainnet | Ratio |
|--------|--------|-------------|-------|
| Accounts | ~2.6M | ~85.9M | 33× |
| Account download | ~35 min | ~13h 44m | 23.5× |
| Healing load (attempt 24) | negligible | 1.36M nodes | — |
| SNAP serve window | ~128 blocks | ~128 blocks | same |

**All the path-coverage bugs above are invisible on Mordor** because:
- Healing is fast enough that staleness windows don't matter
- Counter edge cases don't manifest in short runs
- OOM issues don't appear at 2.6M accounts

Bugs only manifest at ETC mainnet scale. This means **every test must be mentally validated at ETC scale**, not just at Mordor scale.

---

## Phase Timing Benchmarks (ETC Mainnet, Attempt 10 — Reference)

| Phase | Duration | Notes |
|-------|----------|-------|
| Account download | ~13h 44m | 85,948,289 accounts, 16 workers |
| Bytecode sync | concurrent | 36,913 bytecodes |
| Storage sync | concurrent | Completed with accounts |
| Trie walk R1 | ~0s | 1 + 16 nodes initial |
| Trie walk R2 | ~25m | Found 25,642 missing nodes |
| **Total to healing start** | **~14h** | |

Attempt 24 shows similar profile. The healing phase (~25h at 880 nodes/min) is now the critical path.

---

## Project Setup / Quick Commands

```bash
# Build
sbt compile
sbt test          # 2,713 tests (must all pass before building JAR)
sbt assembly      # Builds fat JAR: target/scala-3.3.4/fukuii-assembly-0.1.240.jar

# CRITICAL: Never start the JAR — user always runs it manually.
# Launching JAR from Claude wipes logs (startup truncation).
# Always ask user to restart after building new JAR.
```

**Test count:** 2,713 (increased from 2,704 over session history). If count changes materially, investigate.

---

## Next Steps for This Thread

### Step 1: Read the log to understand current state
```
/media/dev/2tb/data/blockchain/fukuii/etc/logs/fukuii.log
```
The log ends at 13:17:57 (JAR stopped). Read the tail to confirm: validation walk was at 15.5M nodes, 0 missing found, ETA ~3.5h.

### Step 2: Decide — restart JAR immediately or investigate BUG-HEAL-IDLE-TIMEOUT first

**Option A — Restart immediately:**
On restart, HEAL-RESUME loads the persisted abandoned nodes, the 309s idle timer fires again (same timeout storm), abandoned nodes get re-abandoned, validation walk re-starts. The walk may complete this time if given ~3.5h to run. **If the walk finishes with 0 missing nodes, sync is DONE.** This is the lowest-effort path.

**Option B — Fix BUG-HEAL-IDLE-TIMEOUT first:**
Investigate the 309s timer in `TrieNodeHealingCoordinator.scala`. Decide whether to:
- Remove it (rely on `FIX-STAGNATION-LIMIT` instead)
- Extend it to >360s (let `FIX-STAGNATION-LIMIT` run first)
- Leave it but don't abandon tasks — just start the walk while keeping the queue for retry
This requires a new build before restart.

### Step 3: Investigate the timeout storm (core problem)
Whether or not the walk completes, the timeout storm at 12:47-48 is the root cause of why only 2,347/1,360,417 nodes healed. Focus:
1. Why did 10+ consecutive requests timeout? Peer `235067a0` and `6376ee6e` both timing out simultaneously
2. Was the ETC SNAP serve window exceeded? Pivot age at first healing was 190 blocks. ETC actual serve window ~128 blocks. 190 > 128 → nodes outside serve window for most peers
3. **The 190-block staleness is the real culprit:** `maxHealingPivotAgeBlocks=1000` is too permissive. The HEAL-RESUME staleness threshold should be closer to ~64-96 blocks (half the serve window for safety margin), not 1000.

### Step 4: Apply targeted fix, test, build, hand off to user for restart

---

## Handoff Document Location

This plan file will be copied to:
```
/media/dev/2tb/dev/fukuii/docs/reports/SNAP-SYNC-HANDOFF.md
```

And `docs/reports/` will be added to `.gitignore` (internal dev docs, not for commit).

To start a new thread: **"Let's resume work on Fukuii SNAP sync, read this handoff doc: /media/dev/2tb/dev/fukuii/docs/reports/SNAP-SYNC-HANDOFF.md"**

---

## Files to Know

| File | Role |
|------|------|
| `src/main/scala/com/chipprbots/ethereum/blockchain/sync/snap/SNAPSyncController.scala` | Main orchestrator — ~1,400+ lines |
| `src/main/scala/com/chipprbots/ethereum/blockchain/sync/snap/actors/TrieNodeHealingCoordinator.scala` | Healing coordinator — priority queue, dispatch |
| `src/main/scala/com/chipprbots/ethereum/blockchain/sync/snap/actors/TrieNodeHealingWorker.scala` | Per-peer trie node fetching |
| `src/main/scala/com/chipprbots/ethereum/blockchain/sync/snap/actors/Messages.scala` | All message types for all coordinators |
| `src/main/scala/com/chipprbots/ethereum/blockchain/sync/snap/SNAPSyncConfig.scala` | Config values (maxHealingPivotAgeBlocks etc.) |
| `/media/dev/2tb/dev/fukuii/docs/reports/MARCH-ONWARD-CHECKPOINT.md` | 425-commit history, all attempt details, all bug IDs |

---

## Memory / Context Files

- **Memory:** `/home/dev/.claude/projects/-media-dev-2tb-dev/memory/fukuii-snap-sync.md`
  - NOTE: Still references attempt 23 JAR hash `c23c8d894` — needs update to attempt 24 JAR `8f8e0217`
- **MEMORY.md index:** `/home/dev/.claude/projects/-media-dev-2tb-dev/memory/MEMORY.md`
- **Checkpoint report:** `/media/dev/2tb/dev/fukuii/docs/reports/MARCH-ONWARD-CHECKPOINT.md`
