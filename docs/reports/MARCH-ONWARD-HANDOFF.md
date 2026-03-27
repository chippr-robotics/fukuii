# Fukuii `march-onward` Branch — Handoff Document

**Branch:** `march-onward` (104 commits ahead of `upstream/main` at `6220ce58b`)
**Author:** Christopher Mercer (chris-mercer) + Claude Opus 4.6
**Date:** 2026-03-27
**Test results:** 2,713 unit tests passing, 2 pre-existing unrelated failures (DnsDiscoverySpec), 0 ignored
**RPC methods:** 143 implemented (135 standard + 8 MCP), all wired to `JsonRpcController`, zero orphaned
**Build:** Scala 3.3.4 LTS, JDK 21, sbt 1.10.7

---

## Summary

The `march-onward` branch is a comprehensive production-readiness pass building on the `alpha` (PR #1003, merged) and `olympia` branches. It adds 104 commits covering:

- **SNAP sync server + client** — Full bidirectional SNAP protocol implementation, first successful Mordor sync to chain head (~35 min)
- **79 new RPC methods** (64 → 143) — trace, txpool, admin, debug, miner, fee market, state overrides, MCP
- **BLS12-381 precompiles** — All 7 Olympia precompiles via gnark JNI
- **WebSocket subscriptions** — eth_subscribe/eth_unsubscribe (newHeads, logs, pendingTransactions, syncing)
- **JWT authentication** — HS256 across HTTP and WebSocket, geth-compatible secret format
- **Consensus hardening** — Fork-boundary validation, adversarial resilience, transaction type gating
- **Production infrastructure** — Health checks, rate limiting, config validation, log rotation, DNS discovery
- **EVM Stack optimization** — Mutable array-backed stack matching all 5 reference clients

---

## Commit Categories (104 total)

| Category | Count | Description |
|----------|-------|-------------|
| feat(snap) | 12 | SNAP server handlers, client probes, flat storage, trie node cache |
| feat(rpc) | 14 | trace/txpool/admin/debug/miner/fee market/state overrides/JWT |
| feat(evm) | 3 | BLS12-381 precompiles, CREATE gas dedup, Stack array optimization |
| feat | 3 | WebSocket transport, config centralization, treasury updates |
| fix(sync) | 5 | Fork-agnostic RLP, SNAP gap detection, fast sync recovery |
| fix(snap) | 3 | Probe clobber, bytecode validation, premature eviction |
| fix(consensus) | 2 | Transaction type gating, baseFee corruption guard, atomic finalization |
| perf | 8 | RocksDB bulk write, trie cache, prefetch, adaptive timeouts, pipelining, Stack array |
| test | 12 | SNAP PRs #1007/#1008, trace/txpool/admin specs, flat storage, mining, fork boundary |
| docs | 10 | BACKLOG, SNAP report, cleanup, multi-LLM MCP docs, handoff updates |
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
- `GetTrieNodes` — MPT node walk with LRU upper-trie cache (Phase 9)

**Client achievements:**
- First successful Mordor SNAP sync: genesis → chain head in ~35 minutes
- 2,628,940 accounts, peak 6,786 accts/sec, 6 in-place pivot refreshes
- File-backed contract account buffers (OOM fix for ~45M ETC mainnet entries)
- Deferred-write MPT storage (~200x speedup for batch trie insertion)
- Binary stateless detection + adaptive batching per peer

**Key files:**
- `src/main/scala/.../sync/snap/` — Full SNAP sync implementation
- `src/main/scala/.../sync/snap/server/` — SNAP server handlers
- `src/main/scala/.../db/FlatAccountStorage.scala` — O(1) SLOAD via flat key-value
- `docs/reports/SNAP-SYNC-MORDOR-FIRST-SUCCESS.md` — Detailed sync report

### 2. RPC Expansion (64 → 143 methods)

| Namespace | Methods Added | Key Methods |
|-----------|---------------|-------------|
| trace | 6 | trace_block, trace_transaction, trace_filter, trace_replayBlockTransactions |
| txpool | 4 | txpool_status, txpool_content, txpool_inspect, txpool_contentFrom |
| admin | 7 | admin_nodeInfo, admin_peers, admin_addPeer, admin_exportChain, admin_importChain |
| debug | 14 | debug_traceTransaction, debug_traceCall, debug_traceBlock, debug_memStats, debug_stacks, debug_setVerbosity, debug_startCpuProfile, debug_stopCpuProfile |
| eth | 9 | eth_feeHistory, eth_maxPriorityFeePerGas, eth_getBlockReceipts, eth_createAccessList, eth_signTransaction, eth_getHeaderByNumber/Hash |
| miner | 5 | miner_setMinGasPrice, miner_setExtraData, miner_changeTargetGasLimit, miner_setRecommitInterval |
| personal | 1 | personal_signTransaction |
| mcp | 8 | MCP protocol (15 tools, 9 resources, 4 prompts) |

### 3. BLS12-381 Precompiles

All 7 Olympia BLS12-381 precompiled contracts implemented via gnark JNI:
- BLS12_G1ADD, BLS12_G1MUL, BLS12_G1MULTIEXP
- BLS12_G2ADD, BLS12_G2MUL, BLS12_G2MULTIEXP
- BLS12_PAIRING_CHECK
- BLS12_MAP_FP_TO_G1, BLS12_MAP_FP2_TO_G2

### 4. WebSocket Subscriptions

Full `eth_subscribe`/`eth_unsubscribe` over WebSocket:
- `newHeads` — New block headers as they arrive
- `logs` — Filtered event logs with topic/address filters
- `newPendingTransactions` — Pending transaction hashes
- `syncing` — Sync status changes

### 5. JWT Authentication

HS256 JWT auth for both HTTP and WebSocket RPC:
- Compatible with geth `--authrpc.jwtsecret` format (32-byte hex file)
- Auto-generates secret file if missing
- ±60s clock skew tolerance
- Health endpoints (`/health`, `/readiness`, `/healthcheck`, `/buildinfo`) exempt
- 12 unit tests in `JwtAuthSpec.scala`

### 6. Consensus Hardening

- **H-010:** Fork-gate transaction type acceptance (Type 1 pre-Magneto, Type 2/4 pre-Olympia rejected)
- **H-012:** BaseFee calculation corruption guard (throw instead of silent fallback)
- **H-013:** SNAP→Regular atomic finalization (single DataSourceBatchUpdate)
- **H-014:** 12 fork boundary validation tests (pre/post Olympia block N-1/N/N+1)
- **H-015:** 13 fork ID (EIP-2124) tests for chain split detection
- **H-016:** Adversarial fork boundary resilience (BadBlockTracker, error classification)
- **H-017:** Unclean shutdown recovery (go-ethereum-style CleanShutdown flag + LastSafeBlock marker)
- **H-018:** Fast sync pivot selection deadlock fix (bounded retries + single-peer pivot)

### 7. Production Infrastructure

- **Config validation:** Pre-flight checks at startup (sync flag conflicts, port validation, memory warnings)
- **Health endpoints:** `/health`, `/readiness`, `/healthcheck`, `/buildinfo`
- **Rate limiting:** Per-IP request throttling with configurable limits, HTTP 429
- **Log rotation:** `ResilientRollingFileAppender` (10MB max, 50 archives, auto-recreates if deleted)
- **DNS discovery:** EIP-1459 ENR tree resolution for ETC + Mordor
- **State overrides:** `eth_call` and `eth_estimateGas` support `stateOverrides` parameter
- **Static nodes:** Direct-dial with 15s auto-reconnect (geth pattern), exempt from max outgoing limit

### 8. EVM Stack Optimization (M-008)

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

**How — API changes:**

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

**How — OpCode.scala refactor (~80 sites):**
- All `varGas()` methods → `peek()`/`peekN()` (never pop)
- All `exec()` methods → in-place mutation
- `CallOp.getParams()` split into `peekParams()` (varGas, non-mutating) + `popParams()` (exec, mutating)
- Unary ops: `peek(0)` + `set(0, result)` — zero pop/push overhead
- Binary ops: `pop()` one + `peek(0)` + `set(0, result)` — eliminates one pop+push

**Impact:**
- Eliminates ~400M short-lived Vector objects per block validation
- O(1) array access replaces O(log32 n) Vector access for all stack operations
- Matches every reference client's implementation pattern
- 14 files changed, 661 insertions, 451 deletions
- **540/540 VM/Stack/OpCode tests pass** (StackSpec, OpCodeFunSpec, OpCodeGasSpec, OpCodeGasSpecPostEip161, OpCodeGasSpecPostEip2929, Push0Spec, ShiftingOpCodeSpec, CallOpcodesSpec, CreateOpcodeSpec)
- **12/12 EVM consensus tests pass** (Solidity contract execution: Fibonacci, MutualRecursion, CallSelfDestruct, etc.)

**Who should review:** Anyone touching EVM execution paths, opcode implementations, or gas calculation. The key invariant is: **varGas must never mutate the stack** — only peek/peekN.

**Where:**
- `src/main/scala/.../vm/Stack.scala` — Full rewrite (186 lines)
- `src/main/scala/.../vm/OpCode.scala` — ~80 call sites updated (351 line diff)
- `src/test/.../vm/StackSpec.scala` — Rewritten for mutable API
- `src/test/.../vm/Generators.scala` — push() returns Unit
- `src/test/.../vm/OpCodeFunSpec.scala` — executeOp clone pattern, withStackVerification non-mutating
- `src/test/.../vm/OpCodeGasSpec.scala` — All post-execute reads → pre-execute peek
- `src/test/.../vm/OpCodeGasSpecPostEip161.scala` — pop → peek
- `src/test/.../vm/OpCodeGasSpecPostEip2929.scala` — pop → peek
- `src/test/.../vm/Push0Spec.scala` — Capture sizes before execute (shared mutable stack)
- `src/test/.../vm/ShiftingOpCodeSpec.scala` — pop → peek
- `src/test/.../vm/CallOpFixture.scala` — New mutable API
- `src/test/.../vm/CallOpcodesSpec.scala` — New mutable API
- `src/test/.../vm/CreateOpcodeSpec.scala` — New mutable API

---

## Performance Optimizations

| Optimization | Impact | Backlog |
|-------------|--------|---------|
| EVM Stack: mutable Array[UInt256] + in-place set() | Eliminates ~400M short-lived objects/block, O(1) access | M-008 |
| RocksDB bulk write options during sync | Reduced write amplification | — |
| LRU cache for upper trie nodes in GetTrieNodes | Server response speedup | — |
| State prefetcher (warm cache for next block) | ~15% block import speedup | — |
| Exponential backoff for fast sync retries | Reduced network churn | — |
| Per-peer adaptive timeouts + pipelining | Better utilization of fast peers | — |
| Atomic block discard in removeBlockRange | Prevents partial state corruption | — |
| JSON-RPC batch parsing: traverse → parTraverse | Concurrent batch request processing | M-007 |

---

## Tests Added

| Spec | Tests | Coverage |
|------|-------|----------|
| `TraceServiceSpec` | 19 | All 6 trace methods |
| `AdminServiceSpec` | 13 | All 7 admin methods |
| `TxPoolServiceSpec` | 9 | All 4 txpool methods |
| `JwtAuthSpec` | 12 | JWT auth (valid/invalid/expired/missing) |
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

**Total new tests:** ~290 (2,425 → 2,715)

---

## Bug Fixes Merged

- **PR #1007:** SNAP finalization deadlocks — 3 fixes (safety timeouts, pivot refresh guard, completion race)
- **PR #1008:** Comprehensive SNAP test coverage (~144 tests, 2,770 lines)
- **C-003:** Production log level (Pekko DEBUG → INFO)
- **C-004:** Unknown branch resolution infinite loop guard (fork-agnostic RLP + SNAP gap detection)
- **H-018:** Fast sync pivot selection deadlock (bounded retries + single-peer pivot)
- **SNAP probe fix:** Context.become clobber, premature peer eviction, Merkle proofs check

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

### Treasury Address Updates

- Demo v0.2: `0x035b2e3c189B772e52F4C3DA6c45c84A3bB871bf` (centralized via `olympia-treasury.conf` HOCON include)
- Demo v0.3: `0x60d0A7394f9Cd5C469f9F5Ec4F9C803F5294d79b`

---

## Known Remaining Work

See `docs/development/BACKLOG.md` for the complete inventory (38 remaining items). Key items:

### In-scope for this branch (before PR)

- **M-012:** Run full integration test suite (`sbt it:test`)
- **M-028:** Fix DnsDiscoverySpec exclusion from unit tests (tagged IntegrationTest but not excluded)
- **Handoff document update** (this document — keep current before PR)

### Deferred to later branch/PR

- **M-004/M-005:** Version-aware message decoding + capability passing (P2P v5+, not currently needed)
- **M-018:** Hive integration test coverage for Olympia (separate testing effort)
- **L-001:** Deprecate pre-EIP-8 handshake support
- **L-002/L-003/L-004:** Discovery v5 improvements (scalanet submodule)
- **L-010:** Memory-mapped DAG files
- **F-001..F-006:** Future roadmap (GraphQL, Stratum, plugin system)

### All items DONE

All Critical (C-001..C-004), High (H-001..H-018), and most Medium items are DONE. See the Completed Items table in BACKLOG.md (50+ items).

---

## Multi-Client Compatibility

| Client | Branch | Status |
|--------|--------|--------|
| core-geth | `etc` | Synced to head, all tests pass |
| Besu | `etc` | SNAP server for Fukuii SNAP sync |
| Fukuii | `march-onward` | 2,715 tests, Mordor SNAP synced |

Fukuii successfully syncs Mordor using Besu as a SNAP server peer (core-geth provides block headers, Besu provides SNAP state). ETC mainnet SNAP sync is in progress.

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
sbt test          # 2,715 tests, ~7 min
sbt "evm:test"    # 12 EVM consensus tests
sbt it:test       # Integration tests
sbt pp            # Pre-PR: format + style + tests
```

---

## Previous Handoff Documents

- [ETC-HANDOFF.md](ETC-HANDOFF.md) — Alpha stabilization (55 commits, 19 bugs, 2,193 tests)
- [OLYMPIA-HANDOFF.md](OLYMPIA-HANDOFF.md) — Olympia hard fork implementation (34 commits, ECIP-1111/1112/1121)
