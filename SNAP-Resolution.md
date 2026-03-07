# SNAP Sync Resolution — Fukuii Alpha Branch

**Branch:** `alpha`
**Authors:** Christopher Mercer + Claude Opus 4.6
**Date:** 2026-03-07
**Commits:** `eeb814779` (optimization suite) + `6b029ed09` (stale peer fix) + pending (stale-root guard)

---

## Background: SNAP Sync Before This Work

Fukuii inherited its SNAP sync implementation from Mantis (IOHK). The original design had the basic protocol structure in place — account range requests, Merkle proof verification, storage downloads, bytecode fetching, and trie healing — but had never been tested against a live ETC mainnet peer serving the snap/1 protocol.

### Original Architecture

```
SNAPSyncController
  ├── AccountRangeCoordinator (N workers)
  │     └── AccountRangeWorker → GetAccountRange requests
  ├── StorageRangeCoordinator (M workers)
  │     └── StorageRangeWorker → GetStorageRanges requests
  ├── ByteCodeCoordinator (K workers)
  │     └── ByteCodeWorker → GetByteCodes requests
  └── TrieNodeHealingCoordinator (L workers)
        └── HealingWorker → GetTrieNodes requests
```

The sync process divides the 256-bit address keyspace into ranges (originally 4, now 16), assigns each to a worker, and downloads accounts in batches. Each response includes Merkle proofs for verification. When accounts are done, it proceeds to bytecode, then storage, then trie healing.

### What Worked

- Protocol message encoding/decoding (GetAccountRange, AccountRange, etc.)
- Merkle proof verification
- Account storage into RocksDB MPT
- Basic worker lifecycle (create, dispatch, handle response)
- SNAP-to-fast-sync fallback (eventually)
- Integration with the broader sync pipeline

### What Was Broken

When we began testing on ETC mainnet with a local core-geth peer serving snap/1, we found 8 distinct problems that collectively made SNAP sync non-functional for production use:

1. **SNAP→Fast Sync fallback too slow** — when SNAP failed, it took ~75 minutes (5 pivot refresh cycles × 15 min each) to fall back to fast sync
2. **No snap capability detection** — sync started before verifying any peer supported snap/1
3. **False stagnation detection** — the watchdog killed sync prematurely
4. **No progress preservation across pivot changes** — all downloaded accounts lost on pivot refresh
5. **Fixed concurrency regardless of peer count** — flooded single peers with parallel requests
6. **Stop/restart on pivot refresh** — destroyed coordinator state unnecessarily
7. **Stale peer accumulation** — inflated peer count from reconnections
8. **False stateless marking after pivot refresh** — in-flight stale-root requests falsely marked peers as stateless for new root

---

## Fix 0: SNAP→Fast Sync Fallback Acceleration (Bugs 2-3)

### Problem

When SNAP sync couldn't make progress (e.g., no snap-capable peers, or all peers became stateless repeatedly), the fallback to fast sync took ~75 minutes. The original code required 5 full pivot refresh cycles before triggering the fallback, and each cycle lasted ~15 minutes (the snap serve window expiry + restart overhead).

### Root Cause

Two issues combined:

1. **No consecutive pivot tracking** — The controller restarted SNAP sync from scratch on each pivot change via `restartSnapSync()`. This reset all internal state including any failure counters. There was no memory of how many times SNAP had failed across restarts.

2. **Counter reset in `restartSnapSync()`** — Even after we added a consecutive empty pivot counter, `restartSnapSync()` reset it to zero, defeating the purpose.

### Fix

Added `consecutivePivotRefreshes` counter that persists across `restartSnapSync()` calls. After 3 consecutive unproductive pivot refreshes (configurable), the controller falls back to fast sync immediately instead of retrying SNAP. This reduced worst-case fallback time from ~75 minutes to ~6 minutes.

```scala
private var consecutivePivotRefreshes = 0  // NOT reset in restartSnapSync()

// In pivot refresh handler:
consecutivePivotRefreshes += 1
if (consecutivePivotRefreshes >= maxConsecutivePivotRefreshes) {
  fallbackToFastSync(s"$consecutivePivotRefreshes consecutive stateless pivots")
}
```

### Where

- `SNAPSyncController.scala` — `consecutivePivotRefreshes`, `PivotStateUnservable` handler, `restartSnapSync()`

---

## Fix 1: SNAP Capability Check (Bug 9)

### Problem

When Fukuii connected to the ETC network, it found peers via eth/67 handshake but many peers don't support snap/1 (the ETC Coop bootnodes initially didn't have `--snapshot` enabled). The controller launched account range workers immediately, which then timed out trying to send GetAccountRange to peers that couldn't handle them.

### Root Cause

`launchAccountRangeWorkers()` counted all `peersToDownloadFrom` without filtering for snap capability. The controller assumed all handshaked peers could serve snap requests.

### Fix

Added snap peer count check at sync start:

```scala
val snapPeerCount = peersToDownloadFrom.count { case (_, p) =>
  p.peerInfo.remoteStatus.supportsSnap
}
```

If `snapPeerCount == 0`, schedule a grace period check (`snapCapabilityGracePeriod`, default 30s) before falling back to fast sync. This gives time for snap-capable peers to connect after discovery.

### Where

`SNAPSyncController.scala` — `launchAccountRangeWorkers()` and new `CheckSnapCapability` message handler.

---

## Fix 2: Stagnation Watchdog (Bug 10)

### Problem

The stagnation watchdog timer (default 180s) checked whether the `accountsDownloaded` count had changed since the last check. But the liveness signal was only updated when a **full task completed** — meaning when an entire 1/16th of the keyspace was traversed. On ETC mainnet, each range takes ~200 seconds to complete, which exceeded the 180-second threshold. The watchdog triggered a false stagnation detection even though accounts were actively being downloaded.

### Root Cause

`lastCompletedTaskCount` was the sole liveness metric, and it incremented only on task completion (not on intermediate progress).

### Fix

Changed the liveness signal to track `accountsDownloaded` (the total account count) instead of task completions. Since each response downloads ~32K accounts, this counter advances every few seconds — well within the 180s window.

```scala
// Before: only task completions counted as liveness
if (completedTasks.size == lastCompletedTaskCount) → stagnation

// After: account downloads count as liveness
if (accountsDownloaded == lastAccountsDownloaded) → stagnation
```

### Observed Behavior

On the previous run, the watchdog had falsely triggered at ~5M accounts because no single 1/16th range had completed yet. After the fix, the watchdog correctly detected actual stagnation (when peers stop responding) while ignoring the slow-but-steady progress within ranges.

### Where

`AccountRangeCoordinator.scala` — `CheckAccountStagnation` handler.

---

## Fix 3: Partial Range Resume (Bug 11)

### Problem

The most critical issue. ETC mainnet's snap serve window is approximately 128 blocks (~10-16 minutes). After this window, peers stop serving the old state root and return empty responses. The coordinator detected this, marked peers stateless, and requested a pivot refresh.

The original code preserved progress using `preservedCompletedRanges: Set[ByteString]` — a set of range endpoints (`task.last`) for fully-completed ranges. On restart, these ranges were skipped.

**The problem:** With 16 ranges each covering 1/16th of the 256-bit keyspace, and only ~5% of the keyspace downloadable per window, no range ever fully completed before the window expired. Therefore `preservedCompletedRanges` was always empty, and every restart re-downloaded everything from scratch. Progress was impossible.

### Core-geth's Approach

We studied core-geth's source code to understand how it handles this:

1. 16 account tasks, each with `Next` and `Last` fields
2. `Next` advances after each successful response: `task.Next = incHash(lastHash)`
3. Progress persisted to DB via `saveSyncStatus()` — serializes all 16 tasks with current `Next` positions
4. On pivot change: start new sync with new root but **load saved task progress** — resume from saved `Next` positions
5. Content-addressed MPT means accounts stored under an old root are valid under a new root (within ~256 blocks)

### Fix

Replaced `preservedCompletedRanges: Set[ByteString]` with `preservedRangeProgress: Map[ByteString, ByteString]` — a map of `task.last → task.next` for ALL ranges (pending, active, and completed).

**On coordinator stop** (`postStop()`):
```scala
override def postStop(): Unit = {
  sendProgressSnapshot()
  super.postStop()
}

private def sendProgressSnapshot(): Unit = {
  val progress: Map[ByteString, ByteString] =
    (pendingTasks.iterator ++ activeTasks.values.map(_._1) ++ completedTasks)
      .map(t => t.last -> t.next)
      .toMap
  snapSyncController ! AccountRangeProgress(progress)
}
```

**On restart** (`launchAccountRangeWorkers()`):
- Pass `preservedRangeProgress` to the new coordinator
- Coordinator creates tasks with `next = resumeProgress.getOrElse(task.last, task.originalStart)`
- Already-traversed keyspace is skipped — accounts are already in the MPT

**Safety valve:** If pivot drifts more than 256 blocks (`MaxPreservedPivotDistance`), clear preserved progress — the MPT data may be stale.

### Where

- `SNAPSyncController.scala` — `preservedRangeProgress`, `AccountRangeProgress` handler
- `AccountRangeCoordinator.scala` — `resumeProgress` constructor param, `postStop()`, `sendProgressSnapshot()`
- `Messages.scala` — `AccountRangeProgress(progress: Map[ByteString, ByteString])` message
- `AccountTask.scala` — `remainingKeyspace` method for priority queue ordering

---

## Fix 4: Dynamic Concurrency (Bug 12)

### Problem

The original code launched a fixed number of workers based on `accountConcurrency` config (default 16). With only 1-4 actual snap-capable peers, this meant multiple workers sent requests to the same peer simultaneously. Peers like core-geth handle snap requests sequentially — concurrent requests to the same peer queue up and slow down, creating the appearance of stagnation.

### Fix

Cap workers to the actual number of snap-capable peers:

```scala
val snapPeerCount = peersToDownloadFrom.count { case (_, p) =>
  p.peerInfo.remoteStatus.supportsSnap
}
val effectiveConcurrency = math.min(snapSyncConfig.accountConcurrency, snapPeerCount).max(1)
```

This creates a 1:1 worker-to-peer mapping. With 1 snap peer, 1 worker; with 4 snap peers, 4 workers; with 50 snap peers, capped at 16 (the configured max).

### Priority Queue Dispatching

As part of this change, we also switched from FIFO task dispatching to a priority queue ordered by remaining keyspace (smallest-remaining-first):

```scala
private val pendingTasks = mutable.PriorityQueue[AccountTask](remainingTasks: _*)(
  Ordering.by[AccountTask, BigInt](_.remainingKeyspace).reverse
)
```

This ensures nearly-complete ranges finish first, freeing workers for other work. Each completed range represents a guaranteed piece of progress that doesn't need re-downloading.

### Where

- `SNAPSyncController.scala` — `effectiveConcurrency` calculation
- `AccountRangeCoordinator.scala` — `PriorityQueue`, `activePeerCount`, worker cap logic

---

## Fix 5: In-Place Pivot Refresh (Bug 13)

### Problem

When all peers became stateless (serve window expired), the original code path was:

1. Coordinator detects all peers stateless → sends progress snapshot → requests refresh
2. Controller calls `restartSnapSync()` → stops coordinator actor → creates new coordinator

This stop/restart cycle destroyed all in-memory state (worker pool, adaptive byte budgets, cooling-down peer tracking). The new coordinator started cold. More importantly, there was a race condition: `postStop()` sent the progress snapshot, but the controller might not process it before creating the new coordinator.

### Fix

Added a `PivotRefreshed(newStateRoot: ByteString)` message that updates the coordinator's state root **in place** without stopping the actor:

```scala
case PivotRefreshed(newStateRoot) =>
  stateRoot = newStateRoot
  // Clear stateless tracking — peers should be fresh for new root
  statelessPeers.clear()
  pivotRefreshRequested = false
  // Update pending tasks with new state root (active tasks will use new root on retry)
  // ... re-enqueue tasks that were active
  tryRedispatchPendingTasks()
```

The controller sends this message instead of restarting the coordinator when the pivot change is within the preserve distance:

```scala
case AccountRangeProgress(progress) if accountRangeCoordinator.isDefined =>
  // In-place refresh: forward new root to existing coordinator
  coordinator ! PivotRefreshed(newStateRoot)
```

### Observed Behavior

On live ETC mainnet testing, we observed 7 seamless pivot refreshes over ~110 minutes with zero progress lost. The coordinator simply cleared its stateless peer list, updated the root, and continued downloading from where it left off. The `postStop()` path now serves as a safety net for the `restartSnapSync()` path (which is still used for full restarts).

### Where

- `AccountRangeCoordinator.scala` — `PivotRefreshed` handler
- `SNAPSyncController.scala` — in-place refresh vs restart decision
- `Messages.scala` — `PivotRefreshed(newStateRoot: ByteString)` message

---

## Fix 6: Stale Peer Accumulation (Bug 14)

### Problem

Progress logs showed "1 workers/4 peers" when only 1 physical snap peer existed. Investigation revealed that `knownAvailablePeers: mutable.Set[Peer]` in all 3 coordinators (Account, Storage, Healing) only grew — peers were added on `PeerAvailable` but never removed on disconnect.

### Root Cause

`Peer` is a case class with `PeerId` derived from `ActorRef.path.name`. Each time a physical node reconnects, it creates a new `Peer` instance with a different `PeerId` but the same `remoteAddress`. The set accumulated stale entries.

The controller's `PeerListSupportNg` trait correctly handles peer disconnects via `PeerDisconnected(peerId) => removePeerById(peerId)`, keeping `peersToDownloadFrom` accurate. But it doesn't forward disconnect signals to coordinators, and `removePeerById` is `private` in the trait.

### Impact

`activePeerCount` (which caps worker count) counted all entries in `knownAvailablePeers` that weren't marked stateless or cooling-down. Stale entries from disconnected peers passed this filter, so the worker cap was inflated — creating multiple workers all targeting the same physical peer.

### Fix

Deduplicate by `remoteAddress` when a new `PeerAvailable` arrives — evict stale entries for the same physical node before adding the new one:

```scala
case PeerAvailable(peer) =>
  // Evict stale entry for same physical node (reconnection creates new PeerId)
  val evicted = knownAvailablePeers.filter(_.remoteAddress == peer.remoteAddress)
  knownAvailablePeers --= evicted
  evicted.foreach(p => statelessPeers -= p.id)
  knownAvailablePeers += peer
```

This is a 3-line addition per coordinator. No new messages, no trait modifications, no controller changes.

### Where

- `AccountRangeCoordinator.scala` — `PeerAvailable` handler
- `StorageRangeCoordinator.scala` — `StoragePeerAvailable` handler
- `TrieNodeHealingCoordinator.scala` — `HealingPeerAvailable` handler

---

## Fix 7: False Stateless Marking After Pivot Refresh (Bug 15)

### Problem

After an in-place pivot refresh, all peers were immediately re-marked as stateless for the **new** root — within milliseconds. This caused ~2 minutes of wasted thrashing per pivot refresh cycle, with peers repeatedly marked stateless, backed off, and triggering unnecessary additional pivot refreshes.

Live log evidence (ETC mainnet, 2026-03-07):

```
07:56:15,450 — All 4 stateless for root 13542126 → Pivot refreshes to f35e2bcb
07:56:15,487 — Peer marked stateless for NEW root f35e2bcb (37ms later!)
07:56:16-20  — All 4 re-marked stateless every second → backoff (1s/60s, 2s/60s, ...)
07:58:15     — Backoff expires → ANOTHER pivot refresh (unnecessary)
```

### Root Cause

`handleTaskFailed()` unconditionally called `markPeerStateless(peer, reason)` without checking whether the failed request used the current state root or a stale one.

After `PivotRefreshed` updates `stateRoot` and clears `statelessPeers`, in-flight workers still have requests dispatched with the **old** root. These requests complete quickly (peers respond with "Missing proof for empty account range" because the proof doesn't match). The coordinator receives `TaskFailed`, calls `markPeerStateless`, and marks the peer stateless for the **new** root — even though the peer can serve the new root perfectly fine.

### Fix

Added a stale-root guard: only mark peers stateless when the failing task's `rootHash` matches the current `stateRoot`.

```scala
private def handleTaskFailed(requestId: BigInt, reason: String): Unit = {
  activeTasks.remove(requestId).foreach { case (task, worker, peer) =>
    // Only mark peer stateless if the task was using the CURRENT root.
    // After pivot refresh, in-flight requests with the OLD root will fail
    // but this doesn't mean the peer can't serve the NEW root.
    if (task.rootHash == stateRoot) {
      markPeerStateless(peer, reason)
    } else {
      log.info(s"Ignoring failure from stale-root request (...)")
    }
    task.rootHash = stateRoot  // Update to current root before re-enqueue
    pendingTasks.enqueue(task)
```

### Impact

- Eliminates ~2 minutes of wasted time per pivot refresh cycle
- Prevents unnecessary cascading pivot refreshes
- With 7+ refreshes per sync, saves ~14+ minutes of dead time
- Download rate maintained continuously instead of stop-start pattern

### Where

- `AccountRangeCoordinator.scala` — `handleTaskFailed()` method

---

## Additional Optimizations

### Cumulative Keyspace Tracking

Added `consumedKeyspace: BigInt` that increments monotonically as accounts are downloaded. This provides accurate progress percentage across pivot refreshes:

```scala
private var consumedKeyspace: BigInt = BigInt(0)

// In updateTaskProgress:
consumedKeyspace += (newNext - oldNext).abs
val pct = (consumedKeyspace * 1000 / totalKeyspace).toDouble / 10.0
```

### Adaptive Response Sizing

Initial response size set to 2MB (core-geth's handler limit) instead of 512KB. Peers return what they can handle, and the adaptive logic scales down on failures. This maximizes throughput from the first request.

### Logging Cleanup

Moved high-frequency chunk/response logs to DEBUG level (94% noise reduction). Added periodic 100K-account progress logs, range completion logs, and preserved range logs at INFO level.

---

## Live Test Results

### Test 1: Single Snap Peer (2026-03-06, pre-fix baseline)

- **Setup:** Fukuii on ETC mainnet, 1 snap-capable peer (local core-geth with `--snapshot`)
- **Result:** Downloaded ~5M accounts per window, zero progress preserved across restarts
- **Failure mode:** Stagnation watchdog false-triggered after ~5M accounts (180s without task completion)

### Test 2: Post-Fix, Single Snap Peer (2026-03-06)

- **Setup:** Same as Test 1, all fixes applied
- **Result:** 7 seamless pivot refreshes over ~110 minutes
- **Progress:** 0.2% → 11.2% keyspace (~9.6M accounts)
- **Download rate:** ~1,500 accounts/sec (1 peer)
- **Pivot refresh:** In-place, zero progress lost
- **Dynamic concurrency:** 1 worker for 1 snap peer (correct)

### Test 3: Multiple Snap Peers (2026-03-07)

- **Setup:** Fukuii on ETC mainnet, 4 snap-capable peers (local core-geth + 3 ETC Coop bootnodes)
- **Result:** `4 workers/4 peers` — correct 1:1 mapping
- **Download rate:** ~5,800 accounts/sec (4 peers, ~4x improvement)
- **Pivot refresh:** First refresh at ~16 min (4/4 peers stateless), in-place refresh, continued downloading
- **Peer deduplication:** After pivot refresh, peer count correctly stayed at 4 (no stale accumulation)

---

## Architecture After Fixes

```
SNAPSyncController
  ├── preservedRangeProgress: Map[ByteString, ByteString]  ← NEW: survives pivot refreshes
  ├── effectiveConcurrency = min(configured, snapPeerCount) ← NEW: dynamic
  │
  ├── AccountRangeCoordinator
  │     ├── knownAvailablePeers (deduplicated by remoteAddress) ← FIXED
  │     ├── PriorityQueue (smallest-remaining-first)            ← NEW
  │     ├── consumedKeyspace (monotonic progress)               ← NEW
  │     ├── PivotRefreshed handler (in-place update)            ← NEW
  │     ├── postStop() → AccountRangeProgress snapshot          ← NEW
  │     ├── activePeerCount → worker cap                        ← NEW
  │     └── stale-root guard in handleTaskFailed                ← FIXED: prevents false stateless
  │
  ├── StorageRangeCoordinator (peer deduplication applied)
  ├── ByteCodeCoordinator (unchanged)
  └── TrieNodeHealingCoordinator (peer deduplication applied)
```

---

## Remaining Work

1. **Full SNAP sync completion** — Run to 100% keyspace on ETC mainnet (estimated ~2.5 hours with 4 peers)
2. **Bytecode/storage/healing phases** — Not yet exercised on live network
3. **Persistence** — Range progress is in-memory only; core-geth persists to DB via `saveSyncStatus()`. Consider adding disk persistence for crash recovery.
4. **Multi-peer load balancing** — Currently round-robin; could benefit from latency-aware peer selection
5. **Metrics** — No Prometheus/metrics integration for monitoring SNAP sync progress in production
