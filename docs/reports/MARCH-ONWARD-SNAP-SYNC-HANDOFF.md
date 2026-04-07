# Fukuii SNAP Sync — Technical Handoff

**Status:** IN PROGRESS — ETC mainnet SNAP sync attempt 14 running (JAR `668c04c30`)
**Mordor:** ✅ First successful sync March 2026 (~35 minutes, genesis to chain head)
**ETC mainnet:** ⏳ Attempt 14 in progress — BUG-S1/H1/H2 + post-series handoff/heal fixes deployed
**Last updated:** 2026-04-06

> **Note:** This document will be updated when ETC mainnet SNAP sync succeeds. Sections marked `[ESTIMATE]` contain projected values from Attempt 10 benchmarks and may change.

---

## What Is SNAP Sync?

SNAP sync is a protocol extension to ETH68 that downloads Ethereum state (accounts, storage, bytecodes) in bulk — rather than one trie node at a time like fast sync. It was designed by go-ethereum and described in EIP-2459 / SNAP/1 protocol spec.

**Why SNAP over fast sync:**
- Fast sync downloads trie nodes one-by-one via `GetNodeData` (ETH63-67). For ETC (~85M accounts), this takes 30-40+ hours.
- SNAP downloads raw account ranges (sequential slices of the trie keyspace) with boundary Merkle proofs. No tree traversal overhead. For ETC: ~14 hours total.
- `GetNodeData` was removed in ETH68. Any client that wants state from ETH68 peers **must** use SNAP.

**ETC-specific context:**
- ETC mainnet has ~85M accounts as of 2026 Q1 (Ethereum Classic has a lower account density than ETH but the same trie depth).
- Three SNAP server peers available: core-geth (ETH68, no SNAP/1 currently), Besu (ETH68+SNAP/1). In practice Fukuii uses Besu as the SNAP data source and core-geth for block headers.
- Besu requires `--bonsai-historical-block-limit=8192` (default 2048 was too small; fixed in B-001).

---

## Protocol: ETH68 + SNAP/1 Wire Format

SNAP/1 is a **satellite protocol** multiplexed on the same P2P connection as ETH68. Both protocols are negotiated at handshake time.

### Capability Negotiation

```
Node advertises: [ETH68, SNAP/1]   (if snap-server-enabled=true)
                 [ETH68]            (if snap-server-enabled=false)

Peer selection:  ETH68 required (always)
                 SNAP/1 optional (needed for state download)
```

**Files:** `Capability.scala`, `Config.scala` (`supportedCapabilities`), `EtcHelloExchangeState.scala`

### SNAP/1 Message Types

All SNAP messages use a **requestId wrapper** (same pattern as ETH66+ request-response messages).

| Code | Message | Direction | Description |
|------|---------|-----------|-------------|
| 0x21 | `GetAccountRange` | Client → Server | Request accounts in `[startHash, limitHash]` up to `responseBytes` limit |
| 0x22 | `AccountRange` | Server → Client | Accounts + proof (right boundary Merkle proof for partial ranges) |
| 0x23 | `GetStorageRanges` | Client → Server | Request storage slots for up to 128 accounts per batch |
| 0x24 | `StorageRanges` | Server → Client | Slot ranges + proof per account |
| 0x25 | `GetByteCodes` | Client → Server | Request bytecodes by code hash (up to N per batch) |
| 0x26 | `ByteCodes` | Server → Client | Bytecodes in same order as request |
| 0x27 | `GetTrieNodes` | Client → Server | Request MPT nodes by path (used during healing) |
| 0x28 | `TrieNodes` | Server → Client | MPT nodes in same order as request |

**File:** `src/main/scala/.../network/p2p/messages/SNAP.scala`

### Why SNAP/1 Is Not ETH69

ETH69 (Besu) adds some additional message types. Fukuii targets ETH68 + SNAP/1 which is the go-ethereum / core-geth baseline. ETH69 support can be added post-upstream-PR if needed.

---

## Architecture: Four-Coordinator Actor Model

The SNAP sync client follows go-ethereum's concurrent design: **all four download phases run in parallel from the first account response**, rather than sequentially.

```
SNAPSyncController (orchestrator)
│
├── AccountRangeCoordinator    ─── N workers ──→ Peers (SNAP/1 GetAccountRange)
│                                   ↓ discovers contracts
├── ByteCodeCoordinator        ─── M workers ──→ Peers (SNAP/1 GetByteCodes)
│
├── StorageRangeCoordinator    ─── P workers ──→ Peers (SNAP/1 GetStorageRanges)
│
└── TrieNodeHealingCoordinator ─── Q workers ──→ Peers (SNAP/1 GetTrieNodes)
```

**Key directories:**
- `src/main/scala/.../blockchain/sync/snap/SNAPSyncController.scala` — 4,000+ line orchestrator
- `src/main/scala/.../blockchain/sync/snap/actors/` — four coordinator + four worker actors

---

## Sync Phases

### Phase 0: Idle / Pivot Selection

`SNAPSyncController` waits for at least 3 connected peers to agree on a pivot block (same height ± tolerance). Pivot block must be at least 64 blocks behind the network head (safety margin). If no consensus in grace period → fallback to fast sync after 3 retries.

**Pivot refresh:** If the pivot goes stale (chain advances >256 blocks past pivot), `SNAPSyncController` requests a new pivot and sends `PivotRefreshed` to all coordinators. Coordinators resume from their last persisted progress.

### Phase 1: Account Range Download

`AccountRangeCoordinator` divides the 256-bit keyspace into ranges and dispatches to workers.

- **Range size:** Adaptive per-peer byte budget (102KB–524KB per request). Workers request `[next, 0xffff…]` with a byte limit; server truncates and returns a boundary proof.
- **Merkle proof verification:** Every partial response includes a right-boundary proof. `MerkleProofVerifier` validates the proof at the worker level before storing.
- **Work-stealing:** When a worker goes idle and other workers have large ranges (>2^248 keyspace), the idle worker steals the second half of the largest range.
- **Contract detection:** Accounts with `nonce > 0` or `balance > 0` are contracts needing bytecode+storage. These are written to file-backed buffers (~64 bytes/entry, avoids 45M+ in-memory array OOM on ETC).
- **Persistence:** Progress (current range boundaries) saved via `AppStateStorage.putSnapSyncProgress()`. Survives restart.

**ETC benchmark:** ~13h 43m for 85.9M accounts (Attempt 10), 4 Besu peers.

### Phase 2: Bytecode Download (concurrent with Phase 1)

`ByteCodeCoordinator` downloads EVM bytecodes by code hash. Starts immediately when first contract accounts are discovered.

- **Bloom filter dedup:** Before requesting, the coordinator checks a per-sync Bloom filter (seeded at startup, reset on pivot). ~73.5M raw hashes → ~2M unique bytecodes on ETC.
- **Permanent peer exclusion:** After 5 consecutive empty `GetByteCodes` responses, a peer is permanently excluded from bytecode requests. Exclusion survives pivot refreshes (Besu doesn't serve bytecodes from before its bonsai window — this is expected and correct).
- **Cross-batch dedup (R4):** Per-batch dedup using the shared `knownCodeHashes` Bloom filter prevents requesting the same hash across batches when account chunks arrive out-of-order.

**ETC benchmark:** ~36,913 bytecodes; completes concurrently with account download.

### Phase 3: Storage Range Download (concurrent with Phase 1)

`StorageRangeCoordinator` downloads storage slots for contract accounts. Also starts when first contract accounts are discovered.

- **Multi-account batching:** Up to 128 accounts per `GetStorageRanges` request.
- **Pivot dedup (R2):** Completed accounts tracked in file-backed storage. On pivot refresh, already-completed accounts are skipped.
- **Stale root fast-fail:** If 2+ distinct peers return Merkle proof mismatch for the same storage root (indicating the root is outside their pruning window), the coordinator immediately skips all queued tasks for that root. Peers stay healthy. Healing phase recovers skipped slots.
- **BUG-S1 (2026-04-04):** Removed `emptyResponsesByTask.clear()` on pivot refresh. Previously, the 5-empty-response escape hatch was reset on every pivot, causing 3 contracts to loop for 1+ hour. Now the counter accumulates across pivots — stuck accounts escape in ~3 min.

### Phase 4: Trie Node Healing

After all accounts, bytecodes, and storage are downloaded, healing fetches the intermediate MPT nodes (branch/extension nodes) that SNAP doesn't download (SNAP only delivers leaves + proofs, not inner nodes).

- **Trigger:** `SNAPSyncController.startTrieWalk()` traverses the full state trie from the state root, finding all `HashNode` references that aren't in local storage. Missing hashes are queued to `TrieNodeHealingCoordinator`.
- **Self-feeding discovery:** After storing each healed node, the coordinator decodes it and queues its missing children immediately:
  - `BranchNode` → check 16 HashNode children
  - `ExtensionNode` → check HashNode at ext.next
  - `LeafNode`/`NullNode` → no children
  - This eliminates periodic full DFS trie walks (100+ min for 11M state nodes) — subsequent rounds complete in seconds.
- **BUG-H1 (2026-04-04):** Added `if (!trieWalkInProgress)` guard in `ScheduledTrieWalk` handler. Without this, 4 concurrent trie walks ran simultaneously (~3.5× slower, 242K nodes/min vs 467K nodes/min for a single walk).
- **BUG-H2 (2026-04-04):** Changed `lastTrieWalkMissingCount: Int = Int.MaxValue` sentinel to `Option[Int] = None`. The MaxValue sentinel caused the first round to always appear "improved" (any count < MaxValue), generating spurious "2147483646 nodes healed" log lines and preventing correct stagnation detection.
- **Stagnation watchdog:** If no nodes healed for 5 minutes → trigger state validation (check if healing is actually complete).
- **20-retry limit per node:** After 20 failures across all peers, the node is skipped. If skipped nodes prevent state validation → pivot refresh.

**ETC estimate [ESTIMATE]:** 1–2 hours depending on pivot staleness. Fresher pivot = fewer missing nodes.

### Phase 5: State Validation

`StateValidator` traverses the full state trie from the pivot state root and verifies all nodes are present locally. If complete → transitions to `ChainDownloadCompletion`.

### Phase 6: Chain Download Completion

`ChainDownloader` downloads missing block headers and bodies between the pivot block and the current chain head. Transitions to regular sync.

---

## SNAP Server Implementation

When `snap-server-enabled=true`, Fukuii serves SNAP requests from other peers. This makes Fukuii a full bidirectional SNAP node.

**Guard:** Server handlers only activate when `SnapSyncDone=true` (local state is complete). Serving partial state would give incorrect/invalid responses.

**File:** `NetworkPeerManagerActor.scala` (lines ~547–800+)

| Handler | Storage Used | Notes |
|---------|-------------|-------|
| `handleGetAccountRange` | `FlatAccountStorage` (RocksDB key-value) | Returns up to byte limit with right-boundary Merkle proof |
| `handleGetStorageRanges` | `FlatSlotStorage` (RocksDB key-value) | Multi-account batching, byte-limited |
| `handleGetByteCodes` | `EvmCodeStorage` (hash-keyed RocksDB) | Batch up to byte limit |
| `handleGetTrieNodes` | `MptStorage` + LRU upper-trie cache | Up to 512 nodes/batch (go-ethereum: 1024) |

**Flat storage:** Fukuii uses Geth-aligned flat key-value storage (`FlatAccountStorage`, `FlatSlotStorage`) for O(1) SLOAD lookups during SNAP serving, rather than traversing the MPT.

---

## Reference Client Alignment

### What We Took from go-ethereum / core-geth

| Pattern | Source | File |
|---------|--------|------|
| Concurrent bytecode/storage phases from first account response | go-ethereum `sync.go` | `SNAPSyncController.scala` |
| 20 retries per healing node | go-ethereum `sync.go` | `TrieNodeHealingWorker.scala` |
| Permanent peer exclusion after 5 consecutive bytecode failures | go-ethereum `sync.go` | `ByteCodeCoordinator.scala` |
| Byte-budget request sizing (client-tuned) | go-ethereum peer capacity model | `AccountRangeCoordinator.scala`, workers |
| Merkle boundary proof validation at worker level | SNAP/1 spec + go-ethereum pattern | `MerkleProofVerifier.scala`, workers |
| Stagnation watchdog to detect healing completion | go-ethereum healing scheduler | `TrieNodeHealingCoordinator.scala` |
| Work-stealing for idle workers | go-ethereum goroutine pool | `AccountRangeCoordinator.maybeStealWork()` |
| 256-block safety valve on pivot refresh | go-ethereum | `AccountRangeCoordinator.scala` |
| Flat storage (FlatAccountStorage, FlatSlotStorage) | go-ethereum flat state DB | `FlatAccountStorage.scala`, `FlatSlotStorage.scala` |

### What We Observed from Besu

| Observation | Effect on Fukuii |
|-------------|-----------------|
| Besu's bonsai historical limit (2048 blocks) caused it to stop serving trie nodes during healing | B-001 fix: `--bonsai-historical-block-limit=8192` in run script |
| Besu doesn't serve `GetByteCodes` at all | L-036: permanently exclude peers after 5 consecutive empty responses; exclusion survives pivot refreshes |
| Besu is ETH68+ETH69; serves SNAP/1 | Primary SNAP server for ETC mainnet tests |

### Unique to Fukuii (Leading Implementations)

These patterns are not present in go-ethereum or Besu and were developed specifically for Fukuii's Scala actor model and ETC network conditions:

| Innovation | Description | Why It's Different |
|-----------|-------------|-------------------|
| **Self-feeding healing** | After storing a healed node, immediately decode it and queue missing children | go-ethereum uses periodic full DFS walks (100+ min for 11M nodes); Fukuii's child discovery makes subsequent rounds take seconds |
| **Stale storage root fast-fail** | Track `staleRootMismatchPeers` per storageRoot across distinct peer IDs; skip all queued tasks after 2+ distinct peers return mismatch | go-ethereum has no equivalent; without this, 4 unservable ETC accounts each stall for 60+ min |
| **File-backed contract tracking** | Contract accounts written to temp file (~64 bytes/entry) rather than in-memory ArrayBuffer | go-ethereum keeps all in memory (feasible for ETH); ETC has 45M+ contracts — would OOM with in-memory approach |
| **Bloom filter bytecode dedup** | Per-sync Bloom filter on `knownCodeHashes` before requesting; ~73.5M raw hashes → ~2M unique on ETC | go-ethereum deduplicates within a single batch; Fukuii deduplicates across all batches eliminating ~7,350 redundant requests (R4 fix) |
| **Static peer exemption** | `isStatic=true` flag from `PeerManagerActor`; all three SNAP coordinators skip stateless marking and cooldowns for static peers | go-ethereum doesn't distinguish static peers in SNAP; ETC has very few SNAP peers — marking Besu as stateless broke entire sync |
| **Cross-pivot storage escape-hatch accumulation** | `emptyResponsesByTask` counter accumulates across pivot refreshes (BUG-S1) | go-ethereum doesn't have pivot-crossing task counters; without this, 3 ETC contracts looped for 1 h each per sync attempt |
| **Parallel trie walk guard** | `if (!trieWalkInProgress)` in `ScheduledTrieWalk` handler (BUG-H1) | Unique to Fukuii's actor-based scheduler — Pekko scheduled messages can queue, causing 4 concurrent walks without the guard |
| **Adaptive per-peer byte budgets** | Workers adjust request size per-peer based on response times and sizes | go-ethereum uses a single global capacity model; Fukuii's per-peer budgets handle heterogeneous peers (fast local Besu + slow remote peers) |
| **Dynamic stateless threshold** | Scales `consecutiveTimeoutThreshold` with `workers / peers` ratio | go-ethereum has a fixed threshold; ETC's low peer count (2–4 peers, 14 workers) made fixed threshold too aggressive |

---

## Key Design Decisions

### ADR References

| Decision | ADR |
|---------|-----|
| Actor-based coordinator model (vs goroutine pool) | Follows existing Fukuii actor architecture (Pekko) |
| Flat storage for SNAP server | Geth-aligned; avoids MPT traversal for O(1) SLOAD |
| File-backed contract tracking | Memory budget constraint at ETC scale (45M contracts) |
| Permanent bytecode peer exclusion | Besu bonsai limitation; would otherwise stall indefinitely |
| Single upstream PR (full branch) | Cherry-pick approach produced structural conflicts |

### Why Scala Actor Model for SNAP

Fukuii uses Apache Pekko actors throughout. SNAP coordinators are actors that:
- Receive `PivotRefreshed` messages and update their state root in-place (no stop/restart)
- Subscribe to the `PeerEventBus` for `PeerAvailable`/`PeerDisconnected` events
- Send work to worker actors via tell (fire-and-forget)
- Manage peer state (stateless set, cooldown map, budget map) as actor-local mutable state — no locks needed

This means some patterns from go-ethereum (goroutine-per-request with shared maps) translate differently. The `if (!trieWalkInProgress)` guard (BUG-H1) is a Pekko-specific issue: scheduled messages queue in the actor's mailbox, so without the guard, multiple `ScheduledTrieWalk` messages can arrive and each launches a full walk.

---

## Known Issues & BUG Fix Log

All bugs below have been fixed as of attempt 14 (JAR `668c04c30`).

| ID | Severity | Description | Fix | JAR |
|----|---------|-------------|-----|-----|
| BUG-WS3 | CRITICAL | WALK-SKIP false positive on HEAL-RESUME: saved checkpoint nodes all in DB → 0 healed → WALK-SKIP fires before walk ran → trie declared complete while millions of undiscovered nodes remain missing → regular sync fails → restart loop | Add `resumedFromPartialHealCheckpoint` flag; set in `startStateHealingWithSavedNodes`, clear in `TrieWalkResult` handler, add `&& !resumedFromPartialHealCheckpoint` to WALK-SKIP guard | Attempt 16 JAR |
| BUG-H3 | CRITICAL | Healing pivot infinite loop: pivot drifts to Besu bonsai limit (8,192 blocks) → all peers stateless → pivot refresh discards entire pending queue → hours-long re-walk → repeat | (a) Add `StateHealing` to `CheckPivotFreshness` condition — proactive 120-block refresh every ~28 min; (b) Remove `pendingTasks = Seq.empty` + `pendingHashSet.clear()` from `HealingPivotRefreshed` — queue preserved across refreshes; (c) `--bonsai-historical-block-limit=131072` in Besu script | Attempt 15 JAR |
| BUG-S1 | HIGH | Storage infinite loop — 3 contracts × 1 hour each | Remove `emptyResponsesByTask.clear()` on pivot refresh | `668c04c30` |
| BUG-H1 | MEDIUM | 4 concurrent trie walks (3.5× slower) | Add `if (!trieWalkInProgress)` guard in `ScheduledTrieWalk` handler | `668c04c30` |
| BUG-H2 | LOW | Spurious "2147483646 nodes healed" log | Change `Int.MaxValue` sentinel to `Option[Int] = None` | `668c04c30` |
| BUG-TX1 | MEDIUM | `StackOverflowError` on large `PooledTransactions` batches | Convert `toTypedRLPEncodables()` from recursive to iterative | `7005512d6` |
| H-001 | HIGH | Healing stagnation false-fire after long trie walk | Reset `lastHealedAtMs` on `QueueMissingNodes` | `74918ee6c` |
| SP-001 | HIGH | Static peers (Besu) marked stateless and dropped | Set `isStatic=true` at handshake; exempt from stateless/cooldown | `e8d4f243c` |
| L-036 | HIGH | Bytecode stall (Besu doesn't serve `GetByteCodes`) | Permanently exclude peers after 5 consecutive empty responses | `b7cfb5cb8` |
| L-035 | HIGH | `admin_addPeer` one-shot (lost on restart) | Persist static nodes to `static-nodes.json` | `77435bd46` |
| L-022 | MEDIUM | Work-steal overlap (278% keyspace redundancy on ETC) | Non-overlapping steal: victim shrinks to midpoint, stealer gets second half | `734533c87` |
| R4 | LOW | Bytecode redundant requests across batches | Cross-batch Bloom filter dedup eliminates ~7,350 requests | `cf16873eb` |

**Post-BUG-series fixes (April 2026):**

| ID | Severity | Description | Fix | Commits |
|----|---------|-------------|-----|---------|
| BUG-SD1 | HIGH | SNAP→Regular handoff: false-positive `snapSyncDone`, chain weight orphan after pivot substitution, wrong stateRoot check | Reset + re-heal path; accumulate chain weight; check `snapSyncStateRoot` not substituted header | `95545f9d8`, `3d6566fa2`, `d14e4080c`, `5e020472b`, `4a57c1d93` |
| BUG-WS1 | HIGH | WALK-SKIP infinite loop when pivot root absent from RocksDB | Skip-walk path returns to heal; don't re-enter skip on empty DB | `2ee32fd55` |
| BUG-WS2 | MEDIUM | WALK-SKIP false positive when healing is non-trivial | Check actual abandoned node count before deciding to skip | `15a782094` |
| BUG-HS1 | MEDIUM | Healing stall: `RetryPivotRefresh` message sent during `StateHealing` phase, interrupting heal | Exclude `StateHealing` from `RetryPivotRefresh` handler | `77c72db11` |
| OPT-H1 | — | Mid-healing pending queue lost on crash; entire queue re-derived from scratch on restart | Persist pending queue to `AppStateStorage` on each flush | `2123348ba` |
| OPT-SD1 | — | Oversized storage accounts (too large for SNAP serve window) caused repeated timeouts | Defer to trie healing after `max(3, peerCount)` timeouts | `cd1bbeea2` |
| OPT-AD1 | — | Peer dispatch could flood slow peers or hold stale peers in rotation | EMA latency throttle + stale-hold gate per peer | `349673b9a` |

**Trie walk speedups (April 2026):**
- `c3c51a713` — fillCache=false + multiGet for storage walk chain (Speedup 0+1)
- `10d00ca28` — parallel depth-2 fan-out + multiGet BranchNode + fillCache=false in StateValidator (Speedup 2)
- `0e0cd3fda` — collect proof-discovered interior node hashes during account download (reduces subsequent healing rounds)

**Full history:** See L-015 through L-036 + S-001 through S-004 in `MARCH-ONWARD-HANDOFF.md` for the complete SNAP reliability series.

---

## ETC Mainnet Benchmarks

### Attempt 10 (JAR `a890a25`) — Most Complete Data

This was the first attempt to reach the healing phase. Data is used for timing estimates.

| Phase | Duration | Notes |
|-------|----------|-------|
| Pivot selection | ~2 min | 3 peers, 256-block safety |
| Account download | 13h 43m | 85,948,289 accounts, 16 workers, 4 Besu peers |
| Bytecode download | concurrent | 36,913 unique bytecodes |
| Storage download | concurrent | Completed with accounts |
| Healing Round 1 trie walk | 5h 19m | Full DFS walk (self-feeding not yet written) |
| **Healed before stagnation false-fire** | ~1h 10m | 25,642 missing nodes found before false abort |

Attempt 10 failed due to H-001 (stagnation false-fire after the long trie walk). The healing timeout fired because `lastHealedAtMs` wasn't reset when new nodes were queued, only when nodes were stored.

### Attempt 12 (JAR `e8d4f243`) — Previous Best

- All L-series + H-001 + SP-001 fixes bundled
- Account download: 85.9M accounts in 10h 53m (3h faster than attempt 10, better peer utilization)
- Healing phase active when superseded by BUG-S1/H1/H2 discovery

### Mordor Testnet (Attempt 1 — First Success)

| Metric | Value |
|--------|-------|
| Total sync time | ~35 minutes |
| Accounts downloaded | 2,628,940 |
| Peak rate | 6,786 accounts/sec |
| Pivot refreshes | 6 in-place |
| Date | March 2026 |

---

## Current Status

**Attempt 15 (JAR `1670becf3`) — IN PROGRESS as of 2026-04-06**

All known blockers fixed in this JAR:
1. **BUG-H3:** Healing pivot infinite loop (proactive refresh during StateHealing + preserve pending queue)
2. **BUG-S1:** Storage infinite loop for 3 unservable contracts (each was stalling 1h per attempt)
3. **BUG-H1:** 4 concurrent trie walks replaced by single walk with guard (3.5× healing speedup)
4. **BUG-H2:** Healing sentinel corrected (removes spurious log + incorrect stagnation comparison)
5. **BUG-SD1:** SNAP→Regular handoff race (false-positive done, chain weight orphan, stateRoot mismatch)
6. **BUG-WS1/WS2:** WALK-SKIP infinite loop + false positive
7. **BUG-HS1:** Healing stall from RetryPivotRefresh interrupting StateHealing
7. **OPT-H1/SD1/AD1:** Mid-healing crash recovery, oversized storage deferral, adaptive dispatch

**Status as of 2026-04-06 ~13:38:** Healing complete (301,631 nodes, 0 abandoned). State validation trie walk in progress — 108.8M nodes scanned @ ~3,250 nodes/s, ETA ~3h.

**Upstream PR:** Submitted to `chippr-robotics/fukuii` while sync finalizes. See `MARCH-ONWARD-HANDOFF.md` for full details (197 commits).

---

## Test Coverage

SNAP sync is covered by two categories of tests:

### Unit Tests

| Spec | Tests | Coverage |
|------|-------|----------|
| `SNAPServerHandlerSpec` | 11 | All 4 server handlers (account, storage, bytecode, trie node) |
| `FlatAccountStorageSpec` | 5 | Flat key-value account storage |
| `FlatSlotStorageSpec` | 5 | Flat key-value slot storage |
| `MerkleProofVerifierSpec` | (part of SNAP PRs) | Boundary proof validation |
| SNAP PRs #1007/#1008 | ~144 | Full SNAP coordinator behavior — 10 test files |

### Integration Tests

- 8 integration tests pass (ETC mainnet syncing is the real integration test)
- `sbt it:test` — runs integration suite

### Running Tests

```bash
sbt test          # 2,738 unit tests, ~7 min
sbt "evm:test"    # EVM consensus tests
sbt it:test       # Integration tests
sbt pp            # Pre-PR: format + style + tests
```

---

## Related Documents

- [`MARCH-ONWARD-HANDOFF.md`](MARCH-ONWARD-HANDOFF.md) — Full branch handoff (197 commits)
- [`SNAP-SYNC-MORDOR-FIRST-SUCCESS.md`](SNAP-SYNC-MORDOR-FIRST-SUCCESS.md) — Detailed Mordor sync report
- [`docs/architecture/`](../architecture/) — Sync architecture ADRs
- [`docs/runbooks/SNAP-SYNC-FAQ.md`](../runbooks/SNAP-SYNC-FAQ.md) — Operational FAQ
- [`docs/development/BACKLOG.md`](../development/BACKLOG.md) — QA-07 section for SNAP coordinator review items
