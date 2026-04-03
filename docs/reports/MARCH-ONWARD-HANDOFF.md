# Fukuii `march-onward` Branch — Handoff Document

**Branch:** `march-onward` (150 commits ahead of `upstream/main` at `6220ce58b`)
**Author:** Christopher Mercer (chris-mercer) + Claude Opus/Sonnet 4.6
**Updated:** 2026-04-03
**Test results:** 2,718 unit tests passing, 0 failures
**RPC methods:** 143 implemented (135 standard + 8 MCP), all wired to `JsonRpcController`, zero orphaned
**Build:** Scala 3.3.4 LTS, JDK 21, sbt 1.10.7
**Diff vs upstream:** 321 files changed, +27,947 / -1,947 lines

---

## Upstream PR Strategy

**A single `march-onward` PR will be submitted to `chippr-robotics/fukuii` after ETC mainnet SNAP sync completes successfully.** The cherry-pick multi-PR approach was evaluated and abandoned — commits were authored on top of each other and cherry-picking produced structural conflicts on nearly every PR. Submitting the full branch is simpler and submits code as it was actually tested.

SNAP sync gating: ETC mainnet SNAP sync attempt 12 is in the healing phase — account download complete (85.9M accounts in 10h 53m, 4 peers sustained). Healing rounds are active. Once healing completes and the chain tip is reached, the branch is ready for upstream PR submission.

---

## Summary

The `march-onward` branch is a comprehensive production-readiness pass building on the `alpha` (PR #1003, merged) and `olympia` branches. It adds 150 commits covering:

- **SNAP sync server + client** — Full bidirectional SNAP/1 protocol, first successful Mordor sync to chain head (~35 min), ETC mainnet SNAP sync attempt 12 in healing phase
- **SNAP reliability series (L-015 → L-036 + H-001 + SP-001)** — 25 commits hardening SNAP client for ETC mainnet conditions
- **79 new RPC methods** (64 → 143) — trace, txpool, admin, debug, miner, fee market, state overrides, MCP
- **BLS12-381 precompiles** — All 7 Olympia precompiles via gnark JNI
- **WebSocket subscriptions** — eth_subscribe/eth_unsubscribe (newHeads, logs, pendingTransactions, syncing)
- **JWT authentication** — HS256 across HTTP and WebSocket, geth-compatible secret format
- **Consensus hardening** — Fork-boundary validation, adversarial resilience, transaction type gating
- **Production infrastructure** — Health checks, rate limiting, config validation, log rotation, DNS discovery
- **EVM Stack optimization** — Mutable array-backed stack matching all 5 reference clients
- **Memory-mapped DAG files** — MappedByteBuffer replaces heap Array, aligns with geth/Erigon mmap pattern
- **Debug tracers** — callTracer + prestateTracer for debug_trace* RPC methods
- **Network hardening** — Port-0 filter, IP blocklist, AutoBlocker, static peer reconnect

---

## Commit Categories (150 total)

| Category | Count | Description |
|----------|-------|-------------|
| feat(snap) | 12 | SNAP server handlers, client probes, flat storage, trie node cache |
| L-series (snap) | 22 | SNAP client reliability: L-015 through L-036 |
| feat(rpc) | 15 | trace/txpool/admin/debug/miner/fee market/state overrides/JWT; trace_replayBlockTransactions complete |
| feat(evm) | 3 | BLS12-381 precompiles, CREATE gas dedup, Stack array optimization |
| feat | 3 | WebSocket transport, config centralization, treasury updates |
| fix(sync) | 5 | Fork-agnostic RLP, SNAP gap detection, fast sync recovery |
| fix(snap) | 7 | Probe clobber, bytecode validation, premature eviction, storage spam, trie walk heartbeat, H-001, SP-001 |
| fix(consensus) | 2 | Transaction type gating, baseFee corruption guard, atomic finalization |
| perf | 8 | RocksDB bulk write, trie cache, prefetch, adaptive timeouts, pipelining, Stack array |
| test | 12 | SNAP PRs #1007/#1008, trace/txpool/admin specs, flat storage, mining, fork boundary |
| docs | 11 | BACKLOG, SNAP report, cleanup, multi-LLM MCP docs, handoff updates |
| chore | 6 | Log noise, config alignment, stale TODO cleanup |
| refactor | 2 | Treasury centralization, config includes |
| fix(config) | 3 | Olympia alignment, prod log level, API defaults |
| fix(mining) | 3 | Nonce partitioning, peer scoring, PoW halt, hashrate metrics |
| fix(test) | 6 | StackSpec, OpCodeFunSpec, Push0Spec, gas spec mutable stack fixes |

---

## Major Features

### 1. SNAP Sync (Server + Client)

Full bidirectional SNAP/1 protocol — Fukuii can both serve and consume SNAP state.

**Server handlers:**
- `GetAccountRange` — Account trie range queries with Merkle boundary proofs
- `GetStorageRanges` — Multi-account storage slot batching (128 accounts/request)
- `GetByteCodes` — Contract bytecode retrieval with hash validation
- `GetTrieNodes` — MPT node walk with LRU upper-trie cache

**Client achievements:**
- First successful Mordor SNAP sync: genesis → chain head in ~35 minutes
- 2,628,940 accounts, peak 6,786 accts/sec, 6 in-place pivot refreshes
- ETC mainnet attempt 12: 85.9M accounts in 10h 53m (3h faster than attempt 10); healing active
- File-backed contract account buffers (OOM fix for ~45M ETC mainnet entries)
- Deferred-write MPT storage (~200x speedup for batch trie insertion)
- Binary stateless detection + adaptive batching per peer

**Key files:**
- `src/main/scala/.../sync/snap/` — Full SNAP sync implementation
- `src/main/scala/.../sync/snap/server/` — SNAP server handlers
- `src/main/scala/.../db/FlatAccountStorage.scala` — O(1) SLOAD via flat key-value
- `docs/reports/SNAP-SYNC-MORDOR-FIRST-SUCCESS.md` — Detailed sync report

### 2. SNAP Reliability Series (L-015 → L-036 + H-001 + SP-001)

25 commits added after initial Mordor success, targeting ETC mainnet conditions (~85M accounts, persistent SNAP peers, hostile network):

| Commit | Description |
|--------|-------------|
| L-015 | Adaptive bytecode timeouts, batch sizing, exponential peer backoff |
| L-016 | Gate SNAP coordinator routing on verified `isServingSnap` |
| L-017 | Adaptive per-peer budgets for bytecode, storage, and healing coordinators |
| L-018 | Downgrade noisy peer errors to appropriate log levels |
| L-019 | Skip storage tasks after repeated proof verification failures |
| L-020 | Improve SNAP coordinator progress metrics accuracy and detail |
| L-021 | Node-wide periodic status reporting and log quality improvements |
| L-022 | Eliminate SNAP sync redundancies — non-overlapping work-steal + storage pivot dedup |
| L-022b | Persist completed storage accounts + healing pre-check (R5/R6) |
| L-022c | R3 task-peer cooldown + R4 cross-batch bytecode dedup |
| L-027/028/029 | Port-0 filter, IP blocklist with admin RPC, conditional SNAP capability |
| L-030 | Sync failure handling — each mode retries itself, no cross-mode fallback |
| L-031 | Auto-detect and block disruptive peers (AutoBlocker) |
| L-032 | ByteCodeCoordinator `knownAvailablePeers` — fix single-peer redispatch stall |
| L-033 | Re-probe SNAP peer after disconnect/reconnect (clear `probedPeers` on PeerDisconnected) |
| L-033b | Clear `probedPeers` on SnapProbeTimeout to allow re-probe after failure |
| L-035 | `admin_addPeer`/`removePeer` persist to `static-nodes.json` — survives restart |
| L-036 | ByteCodeCoordinator: permanently exclude peers after 5 consecutive empty `GetByteCodes` responses; exclusion survives pivot refreshes (Besu does not serve bytecodes) |
| S-003+S-004 | Storage coordinator: suppress 30s heartbeat spam after storage sync completes; SNAPSyncController: add 15s trie walk heartbeat for long walks |
| H-001 | Healing stagnation timer: reset `lastHealedAtMs` on `QueueMissingNodes` — prevents false stagnation abort after long trie walks (root cause of attempts 10–11 healing failure) |
| SP-001 | Static peer exemption: `PeerManagerActor` sets `isStatic=true` at handshake; all three SNAP coordinators skip `statelessPeers`/cooldown for static peers — trusted local infrastructure (Besu, core-geth) stays active for entire sync |

Also: `8808bcd35` — SNAP sync reliability fixes for ETC mainnet (Fixes 1-6), and `e077fcaa4` — SNAP attempt 5: escape valve, healing, dynamic stateless threshold.

**External fix (not a Fukuii commit):** Besu `--bonsai-historical-block-limit` 2048→8192 in `run-besu-classic.sh`. Original limit caused Besu to stop serving trie nodes after chain advanced >2,048 blocks past pivot during healing (5,480 blocks in attempt 10). 8,192 covers the full ~16h ETC mainnet sync with 2× margin.

### 3. RPC Expansion (64 → 143 methods)

| Namespace | Methods Added | Key Methods |
|-----------|---------------|-------------|
| trace | 6 | trace_block, trace_transaction, trace_filter, trace_replayBlockTransactions |
| txpool | 4 | txpool_status, txpool_content, txpool_inspect, txpool_contentFrom |
| admin | 7 | admin_nodeInfo, admin_peers, admin_addPeer, admin_exportChain, admin_importChain |
| debug | 14 | debug_traceTransaction, debug_traceCall, debug_traceBlock, debug_memStats, debug_stacks, debug_setVerbosity, debug_startCpuProfile, debug_stopCpuProfile |
| eth | 9 | eth_feeHistory, eth_maxPriorityFeePerGas, eth_getBlockReceipts, eth_createAccessList, eth_signTransaction, eth_getHeaderByNumber/Hash, eth_getLogs range limit |
| miner | 5 | miner_setMinGasPrice, miner_setExtraData, miner_changeTargetGasLimit, miner_setRecommitInterval |
| personal | 1 | personal_signTransaction |
| mcp | 8 | MCP protocol (15 tools, 9 resources, 4 prompts) |

**M-034/M-035/M-036 additions** (batch added 2026-03-27):
- `finalized` and `safe` block tags supported across all block-param methods
- Batch RPC requests are now rate-limited (same per-IP throttle as single requests)
- `eth_getLogs` enforces a configurable block range limit to prevent OOM

**trace_replayBlockTransactions complete** (commit `a890a2545`): All four trace types now implemented — `trace`, `stateDiff`, `vmTrace`, `revertReason`; with `traceTypes` gating so callers can request any subset.

### 4. Debug Tracers (M-029)

Native callTracer + prestateTracer for `debug_trace*` RPC methods:
- `CallTracer` — structured call tree (matches geth `callTracer` output format)
- `PrestateTracer` — pre-execution state snapshot (matches geth `prestateTracer` output)
- `ExecutionTracer` — base tracer infrastructure with `onCallEnter`/`onCallExit`/`onOpcode` hooks
- Wired into `debug_traceTransaction`, `debug_traceCall`, `debug_traceBlock`

**Key files:** `CallTracer.scala`, `PrestateTracer.scala`, `ExecutionTracer.scala`

### 5. BLS12-381 Precompiles

All 7 Olympia BLS12-381 precompiled contracts implemented via gnark JNI:
- BLS12_G1ADD, BLS12_G1MUL, BLS12_G1MULTIEXP
- BLS12_G2ADD, BLS12_G2MUL, BLS12_G2MULTIEXP
- BLS12_PAIRING_CHECK
- BLS12_MAP_FP_TO_G1, BLS12_MAP_FP2_TO_G2

### 6. WebSocket Subscriptions

Full `eth_subscribe`/`eth_unsubscribe` over WebSocket:
- `newHeads` — New block headers as they arrive
- `logs` — Filtered event logs with topic/address filters
- `newPendingTransactions` — Pending transaction hashes
- `syncing` — Sync status changes

### 7. JWT Authentication

HS256 JWT auth for both HTTP and WebSocket RPC:
- Compatible with geth `--authrpc.jwtsecret` format (32-byte hex file)
- Auto-generates secret file if missing
- ±60s clock skew tolerance
- Health endpoints (`/health`, `/readiness`, `/healthcheck`, `/buildinfo`) exempt
- 12 unit tests in `JwtAuthSpec.scala`

### 8. Consensus Hardening

- **H-010:** Fork-gate transaction type acceptance (Type 1 pre-Magneto, Type 2/4 pre-Olympia rejected)
- **H-012:** BaseFee calculation corruption guard (throw instead of silent fallback)
- **H-013:** SNAP→Regular atomic finalization (single DataSourceBatchUpdate)
- **H-014:** 12 fork boundary validation tests (pre/post Olympia block N-1/N/N+1)
- **H-015:** 13 fork ID (EIP-2124) tests for chain split detection
- **H-016:** Adversarial fork boundary resilience (BadBlockTracker, error classification)
- **H-017:** Unclean shutdown recovery (go-ethereum-style CleanShutdown flag + LastSafeBlock marker)
- **H-018:** Fast sync pivot selection deadlock fix (bounded retries + single-peer pivot)

### 9. Network Hardening

- **Port-0 filter** — Reject peers advertising port 0 (invalid) during handshake
- **IP blocklist** — Persistent blocklist with `admin_addBlockedIP`/`admin_removeBlockedIP` RPC
- **AutoBlocker** — Auto-detect and block disruptive peers after configurable threshold
- **Static nodes** — Direct-dial with 15s auto-reconnect (geth pattern), exempt from max outgoing limit
- **L-012/012v2** — Static peer duplicate connection race fix + cooldown + exemptions
- **Conditional SNAP capability** — `snap-server-enabled` config flag disables SNAP/1 advertisement

### 10. Production Infrastructure

- **Config validation:** Pre-flight checks at startup (sync flag conflicts, port validation, memory warnings)
- **Health endpoints:** `/health`, `/readiness`, `/healthcheck`, `/buildinfo`
- **Rate limiting:** Per-IP request throttling with configurable limits, HTTP 429; now also applied to batch requests
- **Log rotation:** `ResilientRollingFileAppender` (10MB max, 50 archives, auto-recreates if deleted)
- **DNS discovery:** EIP-1459 ENR tree resolution for ETC + Mordor
- **State overrides:** `eth_call` and `eth_estimateGas` support `stateOverrides` parameter

### 11. EVM Stack Optimization (M-008)

**Why:** Fukuii's EVM stack used an immutable `Vector[UInt256]` where every push/pop/dup/swap allocated a new Vector via structural sharing. With ~200M opcodes per ETC block, that's 400M+ short-lived JVM objects per block validation — heavy GC pressure on a mining client.

**What:** Full rewrite from immutable `Vector[UInt256]` to mutable `Array[UInt256]` + `top: Int` pointer. This is a consensus-critical change affecting every opcode execution path.

**Reference clients reviewed and emulated:**

| Client | Language | Stack Backing | Gas Calc Pattern | In-place Mutation |
|--------|----------|--------------|-----------------|-------------------|
| go-ethereum | Go | `[]uint256.Int` slice | `Back(n)` peek only | Yes — pointer return, modify in place |
| core-geth | Go | `[]uint256.Int` slice | `Back(n)` peek only | Yes — same as geth |
| Erigon | Go | `[]uint256.Int` slice | `Back(n)` peek only | Yes — same as geth |
| Besu | Java | `T[]` array (FlexStack) | `getStackItem(n)` peek only | Yes — `set(offset, value)` |
| Nethermind | C# | `Span<byte>` pinned | `peek` (SIMD AVX2) | Yes — zero-copy |

**The universal pattern discovered:** Gas calculators NEVER pop. They peek. Only execution pops. Fukuii's old architecture violated this — `varGas()` methods called `state.stack.pop()` which only worked because Stack was immutable (each pop returned a NEW Stack).

**API changes:**

| Method | Old (immutable) | New (mutable) | Why |
|--------|----------------|---------------|-----|
| `pop()` | `(UInt256, Stack)` tuple | `UInt256` | Mutation is in-place, no new Stack |
| `pop(n)` | `(Seq[UInt256], Stack)` tuple | `Seq[UInt256]` | Same |
| `push(word)` | Returns new `Stack` | `Unit` | In-place |
| `dup(i)` | Returns new `Stack` | `Unit` | In-place |
| `swap(i)` | Returns new `Stack` | `Unit` | In-place |
| `peek(i)` | Did not exist | `UInt256` | Non-mutating read for varGas |
| `peekN(n)` | Did not exist | `Seq[UInt256]` | Non-mutating batch read |
| `set(i, value)` | Did not exist | `Unit` | geth's peek-and-modify pattern |
| `copy()` | Did not exist | `Stack` | Deep clone for test infrastructure |

**Impact:** Eliminates ~400M short-lived Vector objects per block validation. O(1) array access replaces O(log32 n) Vector access. **540/540 VM/Stack/OpCode tests pass.**

---

## Performance Optimizations

| Optimization | Impact |
|-------------|--------|
| EVM Stack: mutable Array[UInt256] + in-place set() | Eliminates ~400M short-lived objects/block, O(1) access |
| Memory-mapped DAG: MappedByteBuffer replaces heap Array | ~2.4GB off JVM heap, instant startup, OS page cache |
| RocksDB bulk write options during sync | Reduced write amplification |
| LRU cache for upper trie nodes in GetTrieNodes | Server response speedup |
| State prefetcher (warm cache for next block) | ~15% block import speedup |
| Exponential backoff for fast sync retries | Reduced network churn |
| Per-peer adaptive timeouts + pipelining (PeerRateTracker) | Better utilization of fast peers |
| Atomic block discard in removeBlockRange | Prevents partial state corruption |
| JSON-RPC batch: parTraverse (parallel) | Concurrent batch request processing |

---

## Tests Added

| Spec | Tests | Coverage |
|------|-------|----------|
| `TraceServiceSpec` | 19 | All 6 trace methods |
| `AdminServiceSpec` | 13 | All 7 admin methods |
| `TxPoolServiceSpec` | 9 | All 4 txpool methods |
| `JwtAuthSpec` | 12 | JWT auth (valid/invalid/expired/missing) |
| `CallTracerSpec` | ~15 | callTracer output format |
| `PrestateTracerSpec` | ~15 | prestateTracer output format |
| `OlympiaForkBoundarySpec` | 12 | Fork block N-1/N/N+1 validation |
| `OlympiaForkIdSpec` | 13 | EIP-2124 chain split detection |
| `AdversarialForkBoundarySpec` | 11 | BadBlockTracker, manipulated baseFee |
| `SNAPServerHandlerSpec` | 11 | All 4 SNAP server handlers |
| `FlatAccountStorageSpec` | 5 | Flat key-value storage |
| `FlatSlotStorageSpec` | 5 | Flat slot storage |
| `StatePrefetcherSpec` | 4 | Cache warming |
| `EthashMinerUnitSpec` | 5 | Mining unit tests |
| `WorkNotifierSpec` | 2 | HTTP work notifications |
| `ConfigValidatorSpec` | 5 | Startup config validation |
| `StackSpec` | 12 | Mutable stack API (rewritten for M-008) |
| SNAP PRs #1007/#1008 | ~144 | SNAP finalization + comprehensive coverage |

**Total: ~2,718 tests passing**

---

## Bug Fixes Merged

- **PR #1007:** SNAP finalization deadlocks — 3 fixes (safety timeouts, pivot refresh guard, completion race)
- **PR #1008:** Comprehensive SNAP test coverage (~144 tests, 2,770 lines)
- **C-003:** Production log level (Pekko DEBUG → INFO)
- **C-004:** Unknown branch resolution infinite loop guard (fork-agnostic RLP + SNAP gap detection)
- **H-018:** Fast sync pivot selection deadlock (bounded retries + single-peer pivot)
- **SNAP probe fix:** Context.become clobber, premature peer eviction, Merkle proofs check
- **SNAP reliability fixes 1-6** (`8808bcd35`): Escape valve, healing, dynamic stateless threshold for ETC mainnet
- **ExpiringMap thread safety:** Concurrent modification race in blacklist/metadata

---

## Configuration Changes

### New Config Keys

| Key | Default | Description |
|-----|---------|-------------|
| `network.rpc.http.jwt-auth.enabled` | `false` | Enable JWT authentication |
| `network.rpc.http.jwt-auth.secret-file` | — | Path to 32-byte hex secret |
| `sync.snap-server-enabled` | `true` | Enable SNAP server capability |
| `sync.snap-sync.max-snap-fast-cycle-transitions` | `3` | SNAP→fast sync fallback threshold |
| `emergency-td-ceiling` | unset | Emergency PoW halt threshold |
| `max-fast-sync-outer-pivot-retries` | `10` | Bounded fast sync pivot retry |
| `network.rpc.http.rate-limit.enabled` | `true` | Apply rate limit to batch requests too |
| `sync.eth-get-logs-max-block-range` | `2000` | Block range cap for eth_getLogs |

### Treasury Address Updates

- Demo v0.2: `0x035b2e3c189B772e52F4C3DA6c45c84A3bB871bf` (centralized via `olympia-treasury.conf` HOCON include)
- Demo v0.3: `0x60d0A7394f9Cd5C469f9F5Ec4F9C803F5294d79b`

---

## Known Remaining Work

See `docs/development/BACKLOG.md` for the complete inventory. Key items:

### Gating condition for upstream PR

- **ETC mainnet SNAP sync** — Attempt 12 in healing phase. Account download complete (85.9M accounts, 10h 53m, 4 peers sustained throughout). Healing rounds active. Once healing completes and chain tip is reached, open the upstream PR.

### ETC Mainnet SNAP Sync — Attempt History

| Attempt | JAR | Outcome | Root Cause |
|---------|-----|---------|------------|
| 1–5 | various | Failed | UDP spam, fast-sync bounce, deferred-merkleization stall |
| 6–9 | `d9a22c2` | Stalled on bytecodes | L-036 not yet written; remote Besu doesn't serve `GetByteCodes` |
| 10 | `a890a25` | Healing cycling (stagnation false-fire) | H-001 not yet written; stagnation timer false-fired after 5h 19m trie walk |
| 11 | `74918ee` | Not deployed | Superseded by SP-001 before it ran |
| **12** | **`e8d4f243`** | **In progress — healing active** | All fixes bundled; 85.9M accounts in 10h 53m |

Key fixes that unblocked ETC mainnet healing: **L-035, L-036, H-001, SP-001** (Fukuii commits) + **B-001** (Besu bonsai window 2048→8192).

### Deferred to later branch/PR

- **M-004/M-005:** Version-aware message decoding + capability passing (P2P v5+, not currently needed)
- **M-018:** Hive integration test coverage for Olympia (separate testing effort)
- **L-001:** Pre-EIP-8 handshake — WONTFIX (all 5 reference clients retain fallback, Postel's Law)
- **L-002/L-003/L-004:** Discovery v5 improvements (scalanet submodule)
- **F-001..F-006:** Future roadmap (GraphQL, Stratum, plugin system)

### All items DONE

All Critical (C-001..C-004), High (H-001..H-018), and most Medium items are DONE. See the Completed Items table in BACKLOG.md.

---

## Multi-Client Compatibility

| Client | Branch | Status |
|--------|--------|--------|
| core-geth | `etc` | Synced to head, all tests pass |
| Besu | `etc` | SNAP server for Fukuii SNAP sync |
| Fukuii | `march-onward` | 2,718 tests, Mordor SNAP synced, ETC mainnet attempt 12 healing active |

Fukuii successfully syncs Mordor using Besu as a SNAP server peer (core-geth provides block headers, Besu provides SNAP state). ETC mainnet attempt 12 completed account download in 10h 53m and is now in the healing phase.

---

## How to Test

```bash
# Build
sbt assembly

# Run on Mordor (SNAP sync enabled by default)
./run-mordor.sh

# Run on ETC mainnet (SNAP sync enabled, needs Besu SNAP peer)
./run-classic.sh

# Run tests
sbt test          # 2,718 tests, ~7 min
sbt "evm:test"    # EVM consensus tests
sbt it:test       # Integration tests
sbt pp            # Pre-PR: format + style + tests
```

---

## Previous Handoff Documents

- [ETC-HANDOFF.md](ETC-HANDOFF.md) — Alpha stabilization (55 commits, 19 bugs, 2,193 tests)
- [OLYMPIA-HANDOFF.md](OLYMPIA-HANDOFF.md) — Olympia hard fork implementation (34 commits, ECIP-1111/1112/1121)
