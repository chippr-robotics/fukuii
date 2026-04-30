# SNAP Sync Bug Log — may-fields

**Branch:** `may-fields`
**Format:** Each bug has a status, root cause, april-confluence fix reference, and reference client notes.

---

## Active Bugs

### BUG-006: Incomplete SNAP healing causes missing storage nodes in RegularSync
**Status:** OPEN — root cause identified, fix = wipe + re-SNAP
**Discovered:** 2026-04-27 (first RegularSync run after SNAP completion)
**Severity:** Functional — RegularSync permanently blocked at block 24447637+

**Symptom:**
```
ERROR BlockImporter - Block import error MissingStorageNodeException:
  Storage node not found 05975df20cb02517ec23b1ae243b85afd9e1408dbf31adcda1b85cdbcb6b24a6
  for account fc27cd13b432805f47c90a16646d402566bd3143
```
After exhausting GetNodeData retries: 5-minute backoff → retry → infinite loop.
Also: `stored snapStateRoot=d5fcfca279cbc9cc ≠ pivotBlockStateRoot=54c4c564e19578d5`

**Root cause:**
SNAP healing (TrieNodeHealingCoordinator) ran to completion with state root `d5fcfca...`
but a pivot refresh during healing resulted in a stateRoot mismatch in AppStateStorage.
Intermediate storage trie nodes for account `fc27cd13` were never fully healed.
No peer can serve the node via GetNodeData because:
1. eth/68 peers dropped GetNodeData entirely
2. Local Besu (127.0.0.1:30304) uses **BONSAI** data storage — BONSAI stores state diffs
   per block, not hash-keyed trie nodes. It physically cannot serve GetNodeData.
3. No eth/67 peers with this node are reachable

**Fix:** Wipe RocksDB and re-run SNAP from scratch with a current pivot. Ensure
healing runs to full completion (no pivot refresh mid-heal) before transitioning
to RegularSync.

**Files:** `/media/dev/2tb/data/blockchain/fukuii/etc/rocksdb/` — wipe and restart

---

### BUG-007: GetTrieNodes emptyPath returns wrong node for intermediate storage nodes
**Status:** FIXED (commit `6583e723d`)
**Discovered:** 2026-04-27
**Severity:** Functional — storage node recovery always failed when using GetTrieNodes

**Symptom:** StateNodeFetcher received GetTrieNodes responses with "1 node but none matched
target hash" for all SNAP peers — 10 retries always failing.

**Root cause:** `MissingStorageNodeException` handler constructed:
```scala
val emptyPath = ByteString(HexPrefix.encode(Array.empty[Byte], isLeaf = false))
val paths = Some(Seq(Seq(accountHash, emptyPath)))
```
`emptyPath` HP-encodes the empty nibble sequence = "return the storage trie root."
The missing node is an INTERMEDIATE node. Root hash ≠ intermediate node hash.
GetTrieNodes with `paths=Some(...)` routes to GetTrieNodes (never falls through to
GetNodeData), so the hash mismatch persists forever.

**Fix (commit `6583e723d`):** Pass `paths=None` for `MissingStorageNodeException`.
`paths=None` routes to GetNodeData (fetch by hash) in StateNodeFetcher.
go-ethereum/Besu both use GetNodeData for missing storage nodes in eth/67.

**File:** `BlockImporter.scala` — `MissingStorageNodeException` handler

---

---

### BUG-BC1: ByteCodeWorker stuck in working state for 30s after each response
**Status:** FIXED (commit `34e905712`)
**Discovered:** 2026-04-29 (historical branch audit — april-confluence commit `6e50792a9`)
**Severity:** Functional — bytecode download throughput limited to 2 responses/min per worker

**Symptom:** Each bytecode download took 30s per worker even with fast network responses.
Workers stalled in Pekko `working` state, stashing any new task dispatches for 30 seconds.

**Root cause:** `ByteCodesResponseMsg` is routed by `SNAPSyncController` directly to
`ByteCodeCoordinator`, bypassing `ByteCodeWorker`. Worker stays in `working` state waiting for
a message that never arrives. After 30s, `ByteCodeRequestTimeout` fires, worker returns to idle.
`requestTracker.completeRequest(requestId)` was never called, so the SNAPRequestTracker held
stale entries until timeout.

**Fix (commit `34e905712`):** Added `ByteCodeWorkerRelease(requestId)` message to Messages.scala.
Coordinator sends `worker ! ByteCodeWorkerRelease(response.requestId)` before `markWorkerIdle(worker)`
in all three response paths (validation failure, store failure, success). Worker handles it in
`working` state: calls `requestTracker.completeRequest(requestId)`, transitions to idle, calls
`unstashAll()`. Pekko FIFO mailbox guarantees Release arrives before any subsequent FetchTask.

**Reference clients:** go-ethereum and Besu route responses directly to the worker (single-actor
per peer). Fukuii's two-level coordinator+worker design required this explicit release protocol.

**Files:** `Messages.scala`, `ByteCodeCoordinator.scala`, `ByteCodeWorker.scala`

---

### BUG-008: PivotHeaderBootstrap stalls permanently on ask timeout
**Status:** FIXED (commit `8bfff9936`)
**Discovered:** 2026-04-30 (historical branch audit — april-confluence commit `3eabc4bc7`)
**Severity:** Functional — SNAP bootstrap never progresses past pivot header fetch on slow peers

**Symptom:** During SNAP bootstrap, `PivotHeaderBootstrap` sends `GetBlockHeaders` with a
15s Pekko ask timeout. If the peer responds between 15s and 45s (within `peer-response-timeout`
but after the ask window expires), `AskTimeoutException` propagates through the future chain.
`.foreach` on a failed `Future` is a no-op — `self ! Retry(...)` is never sent. Actor stalls
permanently, never retrying. SNAP initialization never begins.

**Root cause:** The pipeline `(peersClient ? req).flatMap(...).map(...).foreach(...)` had no
`.recover` before `.foreach`. Pekko ask fails the future with `AskTimeoutException` at 15s;
`.foreach` silently swallows it.

**Fix (commit `8bfff9936`):** Added `.recover { case ex => log.warning(...); None }` before
`.foreach`. Any exception converts to `None`, which the existing `case None => self ! Retry`
path handles. Exponential backoff and `maxAttempts=10` were already present; this fix makes
the retry path reachable on ask timeout.

**Note:** The "tight reconnect loop" described in april-confluence `3eabc4bc7` is a distinct
symptom from the stall. The stall is the primary failure mode in may-fields's architecture.

**Reference clients:** go-ethereum and Besu use synchronous calls for pivot selection — no
equivalent two-timeout gap. This is Fukuii-specific due to Pekko ask semantics vs. a direct
response-timeout config.

**Files:** `blockchain/sync/PivotHeaderBootstrap.scala`

---

### BUG-009: BlockFetcher blacklists valid peers during fork reorgs
**Status:** FIXED (commit `fe1df878a`)
**Discovered:** 2026-04-30 (historical branch audit — april-confluence commit `63858df8b`)
**Severity:** Functional (regular sync) — depletes peer pool during chain reorgs

**Symptom:** During regular sync (after SNAP), `BlockFetcher` called `BlacklistPeer`
when it dismissed received headers in three cases:
- `HeadersNotFormingSeq` → `BlacklistReason.HeadersNotFormingChain`
- `HeadersNotMatchingReadyBlocks` → `BlacklistReason.HeadersDontExtendReadyBlocks`
- `HeadersNotMatchingWaitingHeaders` → `BlacklistReason.UnrequestedHeaders`

During a fork reorg, peers serving valid alternative-chain headers triggered all three conditions
and were blacklisted as if they sent garbage. Over multiple reorgs, the peer pool depleted.

**Root cause:** No distinction between "cryptographically invalid header" (malicious peer →
blacklist) and "valid header that doesn't match my current chain view" (honest peer on a
different fork → no penalty).

**Fix (commit `fe1df878a`):** Removed `BlacklistPeer` from all three cases. The
`consecutiveHeaderRejections` counter for stale-tip rewind detection is preserved.
Unreachable catch-all also removed. Besu `AbstractPeerTask.java` separates
`HeadersNotMatchingExpected` (no penalty) from `InvalidHeaders` (blacklist) — same intent.

**Files:** `blockchain/sync/regular/BlockFetcher.scala`

---

### BUG-010: Non-atomic pivot+stateRoot writes (E1-W6)
**Status:** FIXED (commit `e283cf326`)
**Discovered:** 2026-04-30 (Phase 2 audit)
**Severity:** Low — crash between the two commits leaves inconsistent DB state

**Root cause:** All 7 call sites wrote `pivotBlock` and `stateRoot` as separate sequential
`.commit()` calls. A JVM crash between them leaves the two fields inconsistent. Startup
validation catches this (freshness check, root match) but the pattern was fragile.

**Fix (commit `e283cf326`):** All 7 sites chained with `DataSourceBatchUpdate.and()` into
a single atomic commit. For Option-based sites, used `for (b <- pivotBlock; r <- stateRoot)`
to require both values present before committing.

**Files:** `blockchain/sync/snap/SNAPSyncController.scala`

---

### BUG-011: consumedKeyspace reset to 0 on restart despite checkpoint (F1)
**Status:** FIXED (commit `f7b35bd6f`)
**Discovered:** 2026-04-30 (Phase 2 audit)
**Severity:** Cosmetic — ETA estimation wrong after restart; actual sync progress unaffected

**Root cause:** `AccountRangeCoordinator` initialized `consumedKeyspace = BigInt(0)` unconditionally.
On restart with `AccountRangeProgress` checkpoints, task positions were correctly restored but the
progress counter showed 0%, making ETA estimates wrong.

**Fix (commit `f7b35bd6f`):** When `resumeProgress.nonEmpty`, re-creates initial task partitions
(pure arithmetic, no I/O) to get original start positions, then sums `next - originalStart` across
all restored tasks to derive the correct `consumedKeyspace`.

**Files:** `blockchain/sync/snap/actors/AccountRangeCoordinator.scala`

---

### BUG-012: Storage recovery allows all-zero accountHash entries (B2)
**Status:** FIXED (commit `e283cf326`)
**Discovered:** 2026-04-30 (Phase 2 audit)
**Severity:** Very low — keccak256(address) is never all-zeros in practice

**Root cause:** The storage file recovery loop filtered `storageRoot != emptyRoot` but did not
guard against an all-zeros `accountHash` (possible from a corrupted/truncated file entry where
the 32-byte accountHash field is padding).

**Fix (commit `e283cf326`):** Added `accountHash != zeroHash` guard before creating a `StorageTask`.

**Files:** `blockchain/sync/snap/SNAPSyncController.scala`

---

## Fixed Bugs (First ETC Mainnet Run — 2026-04-25)

Run duration: 2.7 min (17:55:38 → 17:58:19), manually stopped.
SNAP progress at stop: **442,031 accounts / ~0.5% keyspace**, pivot block 24,439,427.
All 5 bugs identified in this run were fixed and committed by 2026-04-25 19:50.

---

### BUG-001: BlockchainReader ERROR spam after pivot refresh
**Status:** FIXED (commit `029385886`)
**Discovered:** 2026-04-25 (T+~1:45 on pivot refresh)
**Severity:** Noise — log flood, not functional

**Symptom:**
```
ERROR BlockchainReader - Best block 14690b57... (number: 24439427) not found in storage.
```
Fires 20–50 times/second immediately after pivot refresh until the post-pivot 10s cooldown ends.

**Root cause:** During SNAP sync the pivot block header is in memory only — the block body is not stored. Multiple actors concurrently call `getBestBlock()`. Each call that can't find the block body logs at ERROR level. Expected behavior logged at wrong severity.

**Fix on april-confluence:** Commit `e9f9d0647` — changed `log.error()` → `log.warn()`.

**Fix on may-fields (commit `029385886`):** Changed `log.error()` → `log.debug()`.
Went to DEBUG (not WARN) to match reference client silence — go-ethereum and Besu don't
log anything when the pivot body is absent during SNAP. DEBUG preserves the message for
diagnostic builds without polluting INFO/WARN output during normal operation.

**File:** `src/main/scala/com/chipprbots/ethereum/domain/BlockchainReader.scala`

---

### BUG-002: GetBlockHeaders tight loop on non-serving peer
**Status:** FIXED (visibility — commit `7b6620ac7`; behavioral backoff is Phase 3)
**Discovered:** 2026-04-25 (T+0s, dominated log throughout)
**Severity:** Noise — performance and log flood

**Symptom:** One discovery peer (`37.27.231.14:31504`) hammered with `GetBlockHeaders(block=1, maxHeaders=1024)` at ~6–7 req/sec. Every response: `count=0`. No backoff applied.

**Root cause:** Fast sync header download retries immediately on empty `BlockHeaders` response from the same peer with no backoff or per-peer skip logic.

**Fix on may-fields (commit `7b6620ac7`):** Visibility only — replace per-response RECV_BLOCKHEADERS
debug logs with an actor-local `emptyHeadersReceived` counter rolled into the 60s network
summary. Non-empty responses still log at debug with peer/count/blockNumbers.

Behavioral per-peer backoff (go-ethereum `downloader/downloader.go` short-circuits on repeated
empty ranges) is Phase 3 work. The loop still runs but no longer floods the log individually.

**Files:** `NetworkPeerManagerActor.scala`

---

### BUG-003: StorageRangeCoordinator stall from peer resource contention
**Status:** FIXED (commits `2cf5608ca` + `0c967ed88`)
**Discovered:** 2026-04-25 (T+~1:45, at 17:57:43)
**Severity:** Functional — triggers unnecessary pivot refresh, discards progress

**Symptom:**
```
WARN StorageRangeCoordinator - Storage dispatch stalled: 2851 pending, 0 active, no activity for 120s.
WARN StorageRangeCoordinator - All 5 known peers are stateless for root c014bd4b.
  Requesting pivot refresh (attempt 1).
```
Fired at T+~1:45 even though all 5 peers were alive — AccountRangeCoordinator was using 4 of the 5 SNAP peers.

**Root cause:** Two separate issues:
1. `StorageRangeCoordinator` stall detector fires when `maxInFlightPerPeer=0` (the initial value set to defer storage dispatch while AccountRangeCoordinator completes) — no activity is expected in this state.
2. After budget opens, "no activity for 120s" is too slow when all peers are cooling down (not stateless but occupied by account workers).

**Fix on may-fields:**
- `0c967ed88` — Guard stall condition with `maxInFlightPerPeer > 0`. No activity is expected when budget is 0 (storage deferred). Prevents false pivot refresh at startup.
- `2cf5608ca` — `pendingTaskKeys` dedup set; **idle escape valve** after 5 consecutive `tryRedispatch` cycles with tasks pending AND zero eligible peers AND zero active requests → request pivot refresh immediately (faster than the 2-minute timer).

go-ethereum and Besu use a shared peer pool — no per-coordinator starvation possible.
The idle escape valve is the Phase 1 pragmatic fix; shared pool is Phase 3.

**Files:** `StorageRangeCoordinator.scala`

---

### BUG-004: AccountRangeWorker mass timeouts on peer batch disconnect
**Status:** FIXED (commit `e944b2718`)
**Discovered:** 2026-04-25 (T+~2:20, at 17:57:57)
**Severity:** Functional — cascading 30s stalls per worker, halts account download

**Symptom:** After a batch of 7+ TCP failures simultaneously:
```
WARN AccountRangeWorker - Request 26 timed out
WARN AccountRangeCoordinator - Task failed: Request timeout
WARN AccountRangeWorker - Request 27 timed out   [+30s later]
```
Each worker waits the full 30s timeout before reporting failure.

**Root cause:** `PeerDisconnected` events are NOT forwarded from `SNAPSyncController` to `AccountRangeCoordinator`. Workers with in-flight requests to disconnected peers have no way to know the peer is gone.

**Fix on may-fields (commit `e944b2718`):** Three-layer cancellation chain:
1. `SNAPSyncController` → `PeerUnavailable(peerId)` → `AccountRangeCoordinator` on every `PeerDisconnected`
2. `AccountRangeCoordinator` removes peer from known pool, sends `WorkerPeerDisconnected` to active workers; disconnect failures don't increment `peerConsecutiveTimeouts`
3. `AccountRangeWorker` immediately cancels tracker, reports `TaskFailed("Peer disconnected")`, returns to idle (milliseconds vs 30s)

go-ethereum: `peerDropped()` calls `cancel()` synchronously. Besu: `CompletableFuture.cancel(true)` from peer removal handler.

**Files:** `SNAPSyncController.scala`, `AccountRangeCoordinator.scala`, `AccountRangeWorker.scala`, `Messages.scala`

---

### BUG-005: Per-connection log noise — no 60s aggregation
**Status:** FIXED (commit `1da249c37`)
**Discovered:** 2026-04-25 (throughout 2.7-min run)
**Severity:** Noise — makes real signal unobservable

**Symptom:** High-frequency events logged individually at INFO or ERROR level:
- `[HIVE-DEBUG] Accepting incoming connection` at ERROR (from hive integration work)
- `[HIVE-DEBUG] Auth handshake FAILED: Invalid MAC` at ERROR
- `[Stopping Connection] TCP connection failed / auth timeout` at ERROR
- `SEND_MSG: type=GetBlockHeaders` / `PEER_REQUEST_SUCCESS` — hundreds/minute

**Fix on may-fields (commit `1da249c37`):**
- `RLPxConnectionHandler`: AUTH_SUCCESS, FULLY_ESTABLISHED, per-frame SEND_MSG → debug; TCP_FAILED, AUTH_TIMEOUT, AUTH_FAILED → warning; add `tcpFailedCount`/`authFailedCount`/`authTimeoutCount` AtomicIntegers
- `PeerManagerActor`: `[HIVE-DEBUG]` incoming/rejected → debug
- `NetworkPeerManagerActor`: per-peer events → debug; 60s `LogNetworkSummary` emits one aggregate line with active peer count + RLPx error counters (read+reset each cycle)
- `PeerRequestHandler`: PEER_REQUEST/PEER_REQUEST_SUCCESS → debug; timeouts/disconnects → warning

go-ethereum: peer lifecycle at trace/debug. Besu: high-frequency events via Prometheus, not log lines.

**Files:** `RLPxConnectionHandler.scala`, `NetworkPeerManagerActor.scala`, `PeerManagerActor.scala`, `PeerRequestHandler.scala`

---

## Bug Template

```markdown
### BUG-NNN: Short title
**Status:** OPEN | IN PROGRESS | FIXED (commit hash)
**Discovered:** YYYY-MM-DD
**Severity:** Critical | Functional | Noise

**Symptom:** Exact log lines / observed behavior

**Root cause:** Why it happens

**Fix on april-confluence:** Commit hash + description, files changed

**Reference clients:** What go-ethereum / Besu do

**Files on may-fields:** Specific files to change
```
