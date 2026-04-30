# Besu SNAP Sync Reference

**Source:** `/media/dev/2tb/dev/besu`
**Language:** Java
**Role:** ETC-compatible client, best current SNAP-serving peer for Fukuii
**Chain:** ETH mainnet primary (PoS), but maintains ETC/Mordor support (PoW)

> **PoW Note:** Besu is primarily a PoS client (Ethereum mainnet post-merge), but its
> ETC fork branch preserves PoW (Ethash) consensus support. ETC and Mordor are defined
> as named networks with `canSnapSync = true`. SNAP sync code itself is chain-agnostic.
> Besu is currently the **best SNAP-serving peer** for Fukuii on ETC and Mordor.

---

## Key Files

```
ethereum/eth/src/main/java/org/hyperledger/besu/ethereum/eth/sync/snapsync/
├── SnapSyncDownloader.java              # Entry point orchestrator
├── SnapWorldStateDownloadProcess.java  # 7-stage pipeline builder
├── DynamicPivotBlockSelector.java      # Pivot selection & rotation
├── RequestDataStep.java                # Network request builders (5 types)
├── PersistDataStep.java                # Storage persistence & error handling
├── SnapSyncConfiguration.java          # Configuration parameters
├── SnapWorldDownloadState.java         # Request queue state machine
├── SnapSyncProcessState.java           # Pivot & expiry state
└── request/
    ├── AccountRangeDataRequest.java
    ├── StorageRangeDataRequest.java
    ├── BytecodeRequest.java
    └── heal/
        ├── TrieNodeHealingRequest.java
        ├── AccountTrieNodeHealingRequest.java
        └── StorageTrieNodeHealingRequest.java

ethereum/eth/src/main/java/org/hyperledger/besu/ethereum/eth/manager/snap/
├── RetryingGetAccountRangeFromPeerTask.java   # 4 retries, switches peers
├── RetryingGetStorageRangeFromPeerTask.java
├── RetryingGetBytecodeFromPeerTask.java
└── RetryingGetTrieNodeFromPeerTask.java
```

---

## ETC/Mordor Network Definition

`besu/config/NetworkDefinition.java` — `CLASSIC` and `MORDOR` enums:
```java
CLASSIC("/classic.json", chainId=61, networkId=1,  canSnapSync=true, ...)
MORDOR ("/mordor.json",  chainId=63, networkId=7,  canSnapSync=true, ...)
```
SNAP sync is explicitly enabled for both ETC chains.

---

## Entry Point & Trigger

`DefaultSynchronizer` detects `SyncMode.SNAP` → `SnapDownloaderFactory` builds pipeline.
`SnapSyncDownloader.start()` calls `findPivotBlock()` → `downloadChainAndWorldState()`.

Safety check: if chain is not genesis and sync state is missing, falls back to full sync.

---

## Pipeline Architecture (7 Stages, Concurrent)

All stages run in parallel via `EthScheduler.startPipeline()`:

| Stage | Request Type | Batch Size | Phase |
|-------|-------------|-----------|-------|
| Account data | AccountRangeDataRequest | 1 per range | 1 |
| Storage data | StorageRangeDataRequest | 384 accounts | 1 |
| Large storage | StorageRangeDataRequest (chunked) | 1 range | 1 |
| Bytecode | BytecodeRequest | 84 hashes | 1 |
| Trie healing | TrieNodeHealingRequest | 384 nodes | 2 |
| Flat account healing | AccountFlatHealingRequest | 128 accounts | 2 |
| Flat storage healing | StorageFlatHealingRequest | 1024 slots | 2 |

Phase 2 (healing) starts after Phase 1 completes via `thenCombine()`.

**Concurrency:** `maxOutstandingRequests = worldStateRequestParallelism` (default 4–8).

---

## Pivot Selection

`DynamicPivotBlockSelector` — checks for new pivot every 60s and on each batch.

- Initial: select block at ~126 behind best chain height (`pivotBlockWindowValidity = 126`)
- Dynamic re-select: if best height grows by >60 blocks (`pivotBlockDistanceBeforeCaching`)
- State root from `header.getStateRoot()` — used in all requests
- Expiry: `SnapSyncProcessState.isExpired(request)` — if rootHash ≠ current pivot root, request discarded

---

## Account Range

`AccountRangeDataRequest` + `RetryingGetAccountRangeFromPeerTask`

- `MAX_RETRIES = 4`, switches to different peer on each retry
- Timeout: 10 seconds per request
- Only selects peers where `peer.isServingSnap()`
- Validates with `WorldStateProofProvider`
- Spawns child `StorageRangeDataRequest` and `BytecodeRequest` on response

---

## Storage Range (Two-Phase)

**Phase A — Normal:** `fetchStorageDataPipeline` — 384 accounts/slots per request

**Phase B — Large contract:** `fetchLargeStorageDataPipeline`
- Triggered when response is empty range but proofs exist (range too large)
- `RangeManager.findNewBeginElementInRange()` splits into narrower sub-ranges
- Recursive until all slots fit in one request

Empty range detection (RequestDataStep:161-164):
```java
boolean isEmptyRange = (response.slots().isEmpty() || response.slots().get(0).isEmpty())
    && !response.proofs().isEmpty();
```

---

## Bytecode

Batch deduplication built-in: only batches when unique code hashes < 84.
- `inBatches(168, tasks -> 84 - distinctCodeHashes(tasks))`
- Stores by content hash (content-addressed)

---

## Trie Healing

- Pre-step: check local storage first (`LoadLocalDataStep`) — 3 parallel threads
- Only requests from network if node not found locally
- `TrieNodeHealingRequest` supports parent-child relationships
- On pivot change during healing: `reloadTrieHeal()` restarts healing with new root

---

## Error Handling

| Scenario | Behavior |
|----------|----------|
| Timeout (10s) | Retry up to 4 times, switch peer each time |
| Empty slots + proofs | Valid (range proven empty), split into sub-ranges |
| Empty slots, no proofs | Invalid, discard |
| StorageException (transient) | Clear task data, retry |
| StorageException (fatal) | Throw, abort |
| Cancelled | Expected (sync stopped), not logged as error |
| Pivot changed | Old requests expire via `isExpired()`, not persisted |
| Stalling | `requestsSinceLastProgress > maxNodeRequestsWithoutProgress` (100) |

---

## Key Configuration

`SnapSyncConfiguration.java`:

| Parameter | Default | Purpose |
|-----------|---------|---------|
| `pivotBlockWindowValidity` | 126 | Blocks behind best for pivot |
| `storageCountPerRequest` | 384 | Storage slots per request |
| `bytecodeCountPerRequest` | 84 | Code hashes per request |
| `trienodeCountPerRequest` | 384 | Trie nodes per healing request |
| `localFlatAccountCountToHealPerRequest` | 128 | Flat account heal batch |
| `localFlatStorageCountToHealPerRequest` | 1024 | Flat storage heal batch |

`SynchronizerConfiguration.java`:

| Parameter | Default | Purpose |
|-----------|---------|---------|
| `worldStateRequestParallelism` | 4 | Max concurrent requests |
| `worldStateMaxRequestsWithoutProgress` | 100 | Stalling threshold |
| `worldStateMinMillisBeforeStalling` | 120000 (2 min) | Time before stalled |

---

## What to Use from Besu for Fukuii

| Aspect | Use |
|--------|-----|
| ETC/Mordor network definitions | Reference for chain IDs 61/63 |
| Large storage two-phase chunking | Yes — pattern is clear and tested |
| Retry with peer switching (4 retries) | Yes — good default |
| Pivot expiry on root change | Yes — clean design |
| 7-stage pipeline structure | Informational — our Pekko actor model differs |
| Bonsai flat-storage healing | Reference only — we use RocksDB differently |

---

## What to Ignore (PoS-specific)

- Beacon API pivot selection (we use PoW best-block pivot)
- `Engine API` / CL synchronization callbacks
- Withdrawal indexing during sync
- Any `SafeBlock` / `FinalizedBlock` references
