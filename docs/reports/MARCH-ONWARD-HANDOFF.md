# Fukuii `march-onward` Branch — Handoff Document

**Branch:** `march-onward` (197 commits ahead of `upstream/main` at `6220ce58b`)
**Author:** Christopher Mercer (chris-mercer) + Claude Opus/Sonnet 4.6
**Updated:** 2026-04-06
**Test results:** 2,738 unit tests passing, 0 failures
**RPC methods:** 143 implemented (135 standard + 8 MCP), all wired to `JsonRpcController`, zero orphaned
**Build:** Scala 3.3.4 LTS, JDK 21, sbt 1.10.7
**Diff vs upstream:** 357 files changed, +32,289 / -3,561 lines

---

## Upstream PR Strategy

**Submitting now for early review while ETC mainnet SNAP sync finalizes.** The cherry-pick multi-PR approach was evaluated and abandoned — commits were authored on top of each other and cherry-picking produced structural conflicts on nearly every PR. Submitting the full branch is simpler and submits code as it was actually tested.

ETC mainnet SNAP sync attempt 14 (JAR `668c04c30`) is still in progress. All known blockers are fixed (BUG-S1 storage loop, BUG-H1 parallel trie walk, BUG-H2 healing sentinel, plus 10+ additional post-BUG-series handoff and healing fixes). Submitting PR now allows Cody to begin review while the sync completes.

---

## Summary

The `march-onward` branch is a comprehensive production-readiness pass building on the `alpha` (PR #1003, merged) and `olympia` branches. It adds 197 commits covering:

- **SNAP sync server + client** — Full bidirectional SNAP/1 protocol, first successful Mordor sync to chain head (~35 min), ETC mainnet SNAP sync attempt 14 in progress
- **SNAP reliability series (L-015 → L-036 + H-001 + SP-001 + BUG-S1/H1/H2)** — 28 commits hardening SNAP client for ETC mainnet conditions, plus 10 post-BUG-series handoff/heal fixes
- **ETH63-67 protocol removal** — ETH68-only modernization: `ETH65.scala` deleted, 7 old decoders removed, `Capability.scala` simplified, all tests rewritten to ETH68 expectations
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

## Commit Categories (197 total)

| Category | Count | Description |
|----------|-------|-------------|
| feat(snap) | 12 | SNAP server handlers, client probes, flat storage, trie node cache |
| L-series (snap) | 32 | SNAP client reliability: L-015 through L-036 + 10 post-BUG-series SNAP→handoff + heal fixes |
| BUG-series (snap) | 3 | BUG-S1 (storage loop), BUG-H1 (parallel walk), BUG-H2 (healing sentinel) |
| feat(rpc) | 15 | trace/txpool/admin/debug/miner/fee market/state overrides/JWT; trace_replayBlockTransactions complete |
| feat(evm) | 3 | BLS12-381 precompiles, CREATE gas dedup, Stack array optimization |
| feat(protocol) | 3 | ETH63-67 removal, ETH65TxHandlerActor, BUG-TX1 RLP iterative fix |
| feat | 3 | WebSocket transport, config centralization, treasury updates |
| fix(sync) | 5 | Fork-agnostic RLP, SNAP gap detection, fast sync recovery |
| fix(snap) | 7 | Probe clobber, bytecode validation, premature eviction, storage spam, trie walk heartbeat, H-001, SP-001 |
| fix(consensus) | 2 | Transaction type gating, baseFee corruption guard, atomic finalization |
| perf | 8 | RocksDB bulk write, trie cache, prefetch, adaptive timeouts, pipelining, Stack array |
| test | 17 | SNAP PRs #1007/#1008, trace/txpool/admin specs, flat storage, mining, fork boundary, ETH68 protocol rewrite |
| docs | 13 | BACKLOG, SNAP report, cleanup, multi-LLM MCP docs, handoff updates |
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
- ETC mainnet attempt 14 (JAR `668c04c30`): BUG-S1/H1/H2 fixes bundled; in progress
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
| BUG-S1 | Storage infinite loop: removed `emptyResponsesByTask.clear()` on pivot refresh — escape-hatch counter now accumulates across pivots; 3 stuck contracts complete in ~3 min vs 1 h each |
| BUG-H1 | Parallel trie walk guard: added `if (!trieWalkInProgress)` in `ScheduledTrieWalk` handler — prevents 4 concurrent walks (was causing ~3.5× slower healing; 242K nodes/min → 467K nodes/min single walk) |
| BUG-H2 | Healing sentinel fix: `lastTrieWalkMissingCount` changed from `Int = Int.MaxValue` sentinel to `Option[Int] = None` — eliminates spurious "2147483646 nodes healed" log and incorrect stagnation comparison on first round |

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

### 12. ETH63-67 Protocol Removal — ETH68-Only Modernization

ETC now operates exclusively on ETH68 (core-geth: ETH68-only; Besu: ETH68+ETH69; go-ethereum: ETH69-only). The deprecated ETH63-67 legacy protocol code was removed entirely.

**What was removed:**
- `ETH65.scala` — deleted (defined pre-EIP-2681 transaction pool messages without requestId)
- 7 decoders from `MessageDecoders.scala` — ETH63/64/65/66/67 message decoders removed (-376 lines)
- ETH63-67 entries from capability negotiation — `Capability.scala` simplified from version-range to ETH68 check

**What was simplified:**
- `Capability.scala`: `ethVersions1.nonEmpty && ethVersions2.nonEmpty` → ETH68; clean ETH68+SNAP1 pair
- `Config.supportedCapabilities`: advertises `[ETH68]` or `[ETH68, SNAP1]` only — no legacy versions

**New: ETH65TxHandlerActor** (commit `7005512d6`)
- Handles inbound ETH67/68 `NewPooledTransactionHashes` (with types+sizes, EIP-2464)
- Requests unknown hashes via `ETH66.GetPooledTransactions` (with requestId)
- `PendingTransactionsManager` outbound split: ETH67/68 peers get typed announcements; legacy peers get full transactions

**BUG-TX1: RLP StackOverflowError fix** (same commit)
- `toTypedRLPEncodables()` in `BaseETH6XMessages.scala` converted from recursive to iterative
- Recursive version crashed with `StackOverflowError` on large `PooledTransactions` batches (hundreds of EIP-2718 typed envelopes)
- Iterative while-loop version: O(1) stack depth, identical semantics

**Test rewrite scope:**
- `EtcHandshakerSpec`: replaced ETH62 Status tests with ETH68+ForkId variants (206 → 73 lines in the changed section)
- `SyncControllerSpec`: updated for ETH68-only network (GetNodeData/NodeData now rejected)
- `PeerManagerSpec`, message layer tests: all updated to ETH66/68 expectations
- Net: -294 lines of obsolete ETH63/64/65 test code

---

### 13. Post-BUG-Series SNAP Reliability + Handoff Fixes (April 2026)

~25 commits hardening SNAP→Regular sync handoff and healing-phase robustness, added after the BUG-S1/H1/H2 fixes while ETC mainnet attempt 14 runs:

**SNAP→Regular Handoff Fixes:**

| Commit | Description |
|--------|-------------|
| `95545f9d8` | Fix three SNAP→regular sync handoff bugs (race between snapSyncDone flag and regular sync start) |
| `3d6566fa2` | Fix chain weight orphan after pivot header substitution + false-positive snapSyncDone |
| `d14e4080c` | Add pivot header/chain weight consistency repair on SNAP restart |
| `5e020472b` | Fix SNAP false-positive recovery: reset and heal instead of substituting wrong stateRoot |
| `4a57c1d93` | Fix C2: check snapSyncStateRoot not substituted block header stateRoot |
| `2ee32fd55` | Fix D: WALK-SKIP infinite loop when pivot root is absent from RocksDB |
| `15a782094` | Fix WALK-SKIP false positive when healing is non-trivial |
| `77c72db11` | Fix healing stall: RetryPivotRefresh excluded StateHealing phase |

**Healing + Dispatch Improvements:**

| Commit | Description |
|--------|-------------|
| `cd1bbeea2` | Defer oversized storage accounts to trie healing after `max(3, peerCount)` timeouts |
| `2123348ba` | OPT-H1: persist mid-healing pending queue for crash recovery |
| `349673b9a` | Adaptive SNAP peer dispatch: stale-hold gate + EMA latency throttle |
| `dedc6c51c` | Skip validation walk when healing completes with 0 abandoned nodes |
| `25c241486` | Truncate log files on startup before first log line (clean logs per run) |
| `c894558d5` | Replace [HW1-BOOT]/[HW1-FEED] with operator-friendly [HEAL] log tags |

**Trie Walk Speedups:**

| Commit | Description |
|--------|-------------|
| `c3c51a713` | Walk Speedup 0+1: fillCache=false + multiGet for storage walk chain |
| `10d00ca28` | Walk Speedup 2: parallel depth-2 fan-out + multiGet BranchNode + fillCache=false in StateValidator |
| `0e0cd3fda` | Collect proof-discovered interior node hashes during account download (reduces healing rounds) |

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
| ETH68 handshaker tests | ~12 | EtcHandshakerSpec rewritten for ETH68+ForkId (replaces ETH62 variants) |
| ETH68 p2p/sync/network tests | ~20 | SyncControllerSpec, PeerManagerSpec, message layer — all ETH68-only expectations |

**Total: ~2,738 tests passing**

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

- **ETC mainnet SNAP sync** — Attempt 14 in progress (JAR `668c04c30`). All known blockers fixed: BUG-S1/H1/H2 plus 10 post-series handoff and healing fixes. PR now submitted for early review. Update PR status once healing completes and chain tip is reached.

### ETC Mainnet SNAP Sync — Attempt History

| Attempt | JAR | Outcome | Root Cause |
|---------|-----|---------|------------|
| 1–5 | various | Failed | UDP spam, fast-sync bounce, deferred-merkleization stall |
| 6–9 | `d9a22c2` | Stalled on bytecodes | L-036 not yet written; remote Besu doesn't serve `GetByteCodes` |
| 10 | `a890a25` | Healing cycling (stagnation false-fire) | H-001 not yet written; stagnation timer false-fired after 5h 19m trie walk |
| 11 | `74918ee` | Not deployed | Superseded by SP-001 before it ran |
| 12 | `e8d4f243` | Healing active, then superseded | All L/H/SP fixes bundled; 85.9M accounts in 10h 53m; BUG-S1/H1/H2 not yet |
| 13 | `7005512d6` | Superseded by BUG-S1/H1/H2 fixes | ETH65TxHandlerActor + RLP iterative fix bundled; BUG-* fixes pending |
| **14** | **`668c04c30`** | **In progress** | BUG-S1 (storage loop) + BUG-H1 (parallel walk) + BUG-H2 (sentinel) all fixed |

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
| Fukuii | `march-onward` | 2,738 tests, Mordor SNAP synced, ETC mainnet attempt 14 in progress (post-BUG-series + handoff fixes) |

Fukuii successfully syncs Mordor using Besu as a SNAP server peer (core-geth provides block headers, Besu provides SNAP state). ETC mainnet attempt 14 (JAR `668c04c30`) targets the three healing-phase blockers fixed by BUG-S1/H1/H2.

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
sbt test          # 2,738 tests, ~7 min
sbt "evm:test"    # EVM consensus tests
sbt it:test       # Integration tests
sbt pp            # Pre-PR: format + style + tests
```

---

## Previous Handoff Documents

- [ETC-HANDOFF.md](ETC-HANDOFF.md) — Alpha stabilization (55 commits, 19 bugs, 2,193 tests)
- [OLYMPIA-HANDOFF.md](OLYMPIA-HANDOFF.md) — Olympia hard fork implementation (34 commits, ECIP-1111/1112/1121)
