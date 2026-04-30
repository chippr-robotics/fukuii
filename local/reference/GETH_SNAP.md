# go-ethereum SNAP Sync Reference

**Source:** `/media/dev/2tb/dev/go-ethereum`
**Language:** Go
**Role:** Canonical SNAP protocol implementation — the spec all other clients follow
**Chain:** ETH mainnet (PoS post-merge). **Not PoW.** Use for protocol mechanics only;
          do NOT borrow any Engine API, beacon chain, or post-merge assumptions.

> **PoW Warning:** go-ethereum is now a PoS client. Many sync assumptions are shaped
> by PoS finality (safe/finalized blocks, beacon API pivot selection, etc.). Extract
> the SNAP *protocol mechanics* here; use core-geth for ETC-specific network behavior.

---

## Key Files

```
eth/protocols/snap/
├── sync.go         # Main Syncer — state machine, phases, request dispatch
├── handler.go      # SNAP request handler (serving side)
├── protocol.go     # Wire protocol definition (packet structs, message IDs)
├── peer.go         # Peer abstraction, request methods
├── gentrie.go      # Path-mode trie construction during sync
├── metrics.go      # Prometheus counters/gauges
└── tracker.go      # Per-peer request time tracking
```

---

## Entry Point

`Syncer.Sync(root common.Hash, cancel chan struct{})` — `sync.go:607`

- Called with the desired state root to sync
- Loads previous progress via `loadSyncStatus()` (line 624)
- Divides account key space into 16 chunks (`accountConcurrency = 16`, line 104)
- Runs main event loop selecting on response/failure/peer channels until complete

---

## State Machine Phases

```
1. Account Download     (assignAccountTasks)       — parallel, 16 chunks
2. Storage Download     (assignStorageTasks)        — triggered per account
   Bytecode Download    (assignBytecodeTasks)       — parallel with storage
3. Trie Healing         (assignTrienodeHealTasks)   — gaps at chunk boundaries
4. Bytecode Healing     (assignBytecodeHealTasks)   — missing code during healing
```

Transition to healing when `len(s.tasks) == 0` (all account tasks complete), `sync.go:705-719`.

---

## Pivot Selection

Pivot (state root) is injected from the **downloader layer** — not selected inside the snap
syncer itself. `Syncer.Sync()` receives `root common.Hash` as a parameter (line 607).

- In ETC/PoW context, pivot comes from peer consensus (best block at N-128)
- Root is embedded in every request: `RequestAccountRange(reqid, root, ...)`
- Root validated via Merkle proof on every response

---

## Account Range

**Request:** `GetAccountRangePacket{ID, Root, Origin, Limit, Bytes}` — `protocol.go:73`
- `Origin` = start of range (inclusive), `Limit` = end of range
- `Bytes` = soft size limit (prefer responses under this)

**Concurrency:** 16 parallel account tasks covering disjoint ranges

**Response handling:** `OnAccounts()` → `processAccountResponse()`
- Verifies `VerifyRangeProof(root, ...)` — rejects on proof failure
- Monotonic ordering enforced (`accounts[i-1] >= accounts[i]` = reject)
- Marks each account's needs: `needCode`, `needState`, `needHeal`

**Empty response:** marks peer as stateless, excludes from future account requests

---

## Storage Range

**Two-phase design:**

Phase A — Small storage (fits in one request):
- `GetStorageRangesPacket` with single account, range `[origin, limit]`
- `cont=true` in response signals more slots remain → triggers Phase B

Phase B — Large contract (chunked):
- Continuation splits remaining space into `storageConcurrency = 16` sub-tasks
- Sub-tasks in `task.SubTasks[accountHash]`
- Multi-account batching: single request can cover multiple accounts

**Verification:** `VerifyRangeProof(storageRoot, origin, keys, slots, proofNodes)` — `sync.go:2856`

---

## Bytecode

**Request:** `GetByteCodesPacket{ID, Hashes, Bytes}` — `protocol.go:170`
- Max per request: 85 hashes (`maxCodeRequestCount = maxRequestSize / (24KB * 4)`)
- Built during account response processing; batched across tasks
- Reverted to queue on timeout/failure

---

## Trie Healing (GetTrieNodes)

**Purpose:** Fill boundary gaps from chunked account/storage download

**Request:** `GetTrieNodesPacket{ID, Root, Paths, Bytes}` — `protocol.go:188`
- `Paths` = `TrieNodePathSet = [][]byte`:
  - Element 0: account path (nibble sequence)
  - Elements 1+: storage paths under that account
- Max per request: 1024 nodes (`maxTrieRequestCount`, line 72)

**Throttling:** Dynamic throttle factor adjusts request rate based on healing backlog
- Starts at `maxTrienodeHealThrottle`, decreases as backlog clears
- Prevents flooding peers during healing phase

**Commit:** Healer batches accumulate until `MemSize() >= IdealBatchSize`, then flush

---

## Concurrency Model

- **Single event loop** (`Sync()` goroutine) — no concurrent state mutations
- **Per-request goroutines** — one per pending request, send on completion channels
- **Channels** for all cross-goroutine communication (no shared state)
- **Locks** (`sync.RWMutex s.lock`): protects peers map, request maps, stateless peers
- **Adaptive peer selection:** sort idle peers by capacity via `s.rates.Capacity()`

---

## Error Handling

| Scenario | Behavior |
|----------|----------|
| Timeout | Peer rate stats → 0, request reverted to queue, peer returned to idle |
| Empty response | Peer marked stateless, excluded from that request type |
| Proof failure | Request reverted, may trigger peer disconnect |
| Bad ordering | Packet rejected, peer connection error returned |
| Peer disconnect | All in-flight requests reverted via `revertRequests()` |
| Wrong state root | Proof fails → revert, different peer tried |

---

## Key Configuration Constants

```go
minRequestSize         = 64 * 1024   // Min bytes per request
maxRequestSize         = 512 * 1024  // Max bytes per request
maxCodeRequestCount    = 85          // Max bytecodes per request
maxTrieRequestCount    = 1024        // Max trie nodes per request
accountConcurrency     = 16          // Parallel account tasks
storageConcurrency     = 16          // Chunks per large contract
```

**Handler limits (serving side):**
```go
softResponseLimit      = 2 * 1024 * 1024  // Target max response size
maxCodeLookups         = 1024             // Max bytecodes to serve
maxTrieNodeLookups     = 1024             // Max trie nodes to serve
maxTrieNodeTimeSpent   = 5 * time.Second  // Max time per trie response
maxMessageSize         = 10 * 1024 * 1024 // Max protocol message
```

---

## Progress Persistence

Saved to DB via `rawdb.WriteSnapshotSyncStatus()` (line 941). Includes incomplete account
tasks, storage completion flags, counters. Loaded on restart via `loadSyncStatus()`.

---

## What to Ignore (PoS-specific)

- **Engine API / CL pivot:** geth's live pivot selection uses beacon chain finalized blocks — irrelevant for ETC PoW
- **Safe/finalized block concepts:** PoS constructs, not applicable
- **`eth_syncing` beacon status fields:** not relevant
- **Merge-related fork handling:** use core-geth for ETC fork handling

---

## Summary for Fukuii

| Aspect | Use From geth |
|--------|--------------|
| Protocol wire format | Yes — exact message structures |
| State machine phases | Yes — account → storage → bytecode → healing |
| Proof verification logic | Yes — `VerifyRangeProof` approach |
| Concurrency limits (16/16) | Yes — validated values |
| Serving-side limits | Yes — handler constants |
| Pivot selection | No — use PoW peer consensus, not beacon |
| Engine API integration | No — ETC is PoW |
