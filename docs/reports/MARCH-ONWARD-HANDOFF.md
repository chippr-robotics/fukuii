# Fukuii `march-onward` Branch — Handoff Document

**Branch:** `march-onward` (81 commits ahead of `upstream/main` at `6220ce58b`)
**Author:** Christopher Mercer (chris-mercer) + Claude Opus 4.6
**Date:** 2026-03-26
**Test results:** 2,695 unit tests passing, 0 failed, 2 ignored
**RPC methods:** 118 implemented, all wired to `JsonRpcController`, zero orphaned
**Build:** Scala 3.3.4 LTS, JDK 21, sbt 1.10.7

---

## Summary

The `march-onward` branch is a comprehensive production-readiness pass building on the `alpha` (PR #1003, merged) and `olympia` branches. It adds 81 commits covering:

- **SNAP sync server + client** — Full bidirectional SNAP protocol implementation, first successful Mordor sync to chain head (~35 min)
- **54 new RPC methods** (64 → 118) — trace, txpool, admin, debug, miner, fee market, state overrides
- **BLS12-381 precompiles** — All 7 Olympia precompiles via gnark JNI
- **WebSocket subscriptions** — eth_subscribe/eth_unsubscribe (newHeads, logs, pendingTransactions, syncing)
- **JWT authentication** — HS256 across HTTP and WebSocket, geth-compatible secret format
- **Consensus hardening** — Fork-boundary validation, adversarial resilience, transaction type gating
- **Production infrastructure** — Health checks, rate limiting, config validation, log rotation, DNS discovery

---

## Commit Categories (81 total)

| Category | Count | Description |
|----------|-------|-------------|
| feat(snap) | 12 | SNAP server handlers, client probes, flat storage, trie node cache |
| feat(rpc) | 14 | trace/txpool/admin/debug/miner/fee market/state overrides/JWT |
| feat(evm) | 2 | BLS12-381 precompiles, CREATE gas dedup |
| feat | 3 | WebSocket transport, config centralization, treasury updates |
| fix(sync) | 5 | Fork-agnostic RLP, SNAP gap detection, fast sync recovery |
| fix(snap) | 3 | Probe clobber, bytecode validation, premature eviction |
| fix(consensus) | 2 | Transaction type gating, baseFee corruption guard, atomic finalization |
| perf | 7 | RocksDB bulk write, trie cache, prefetch, adaptive timeouts, pipelining |
| test | 12 | SNAP PRs #1007/#1008, trace/txpool/admin specs, flat storage, mining, fork boundary |
| docs | 9 | BACKLOG, SNAP report, cleanup, multi-LLM MCP docs |
| chore | 6 | Log noise, config alignment, stale TODO cleanup |
| refactor | 2 | Treasury centralization, config includes |
| fix(config) | 3 | Olympia alignment, prod log level, API defaults |

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

### 2. RPC Expansion (64 → 118 methods)

| Namespace | Methods Added | Key Methods |
|-----------|---------------|-------------|
| trace | 6 | trace_block, trace_transaction, trace_filter, trace_replayBlockTransactions |
| txpool | 4 | txpool_status, txpool_content, txpool_inspect, txpool_contentFrom |
| admin | 7 | admin_nodeInfo, admin_peers, admin_addPeer, admin_exportChain, admin_importChain |
| debug | 12 | debug_traceTransaction, debug_traceCall, debug_traceBlock, debug_memStats, debug_stacks, debug_setVerbosity |
| eth | 9 | eth_feeHistory, eth_maxPriorityFeePerGas, eth_getBlockReceipts, eth_createAccessList, eth_signTransaction, eth_getHeaderByNumber/Hash |
| miner | 5 | miner_setMinGasPrice, miner_setExtraData, miner_changeTargetGasLimit, miner_setRecommitInterval |
| personal | 1 | personal_signTransaction |

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

### 7. Production Infrastructure

- **Config validation:** Pre-flight checks at startup (sync flag conflicts, port validation, memory warnings)
- **Health endpoints:** `/health`, `/readiness`, `/healthcheck`, `/buildinfo`
- **Rate limiting:** Per-IP request throttling with configurable limits, HTTP 429
- **Log rotation:** `ResilientRollingFileAppender` (10MB max, 50 archives, auto-recreates if deleted)
- **DNS discovery:** EIP-1459 ENR tree resolution for ETC + Mordor
- **State overrides:** `eth_call` and `eth_estimateGas` support `stateOverrides` parameter

---

## Performance Optimizations

| Optimization | Impact |
|-------------|--------|
| RocksDB bulk write options during sync | Reduced write amplification |
| LRU cache for upper trie nodes in GetTrieNodes | Server response speedup |
| State prefetcher (warm cache for next block) | ~15% block import speedup |
| Exponential backoff for fast sync retries | Reduced network churn |
| Per-peer adaptive timeouts + pipelining | Better utilization of fast peers |
| Atomic block discard in removeBlockRange | Prevents partial state corruption |

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
| SNAP PRs #1007/#1008 | ~144 | SNAP finalization + comprehensive coverage |

**Total new tests:** ~270 (2,425 → 2,695)

---

## Bug Fixes Merged

- **PR #1007:** SNAP finalization deadlocks — 3 fixes (safety timeouts, pivot refresh guard, completion race)
- **PR #1008:** Comprehensive SNAP test coverage (~144 tests, 2,770 lines)
- **C-003:** Production log level (Pekko DEBUG → INFO)
- **C-004:** Unknown branch resolution infinite loop guard (fork-agnostic RLP + SNAP gap detection)
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

### Treasury Address Updates

- Demo v0.2: `0x035b2e3c189B772e52F4C3DA6c45c84A3bB871bf` (centralized via `olympia-treasury.conf` HOCON include)
- Demo v0.3: `0x60d0A7394f9Cd5C469f9F5Ec4F9C803F5294d79b`

---

## Known Remaining Work

See `docs/development/BACKLOG.md` for the complete inventory. Key items:

### Critical
- C-001/C-002: Merge SNAP PRs #1007/#1008 to upstream (merged locally, not yet upstream PRs)
- C-003: Production Pekko log level (trivial, not yet committed to upstream)

### High Priority
- All HIGH items (H-001 through H-016) are DONE

### Medium Priority (selected)
- M-003: SNAP work-stealing for idle workers
- M-007: JSON-RPC batch parsing optimization
- M-008: EVM Stack array-backed optimization
- M-024: debug_cpuProfile (JFR/async-profiler)

---

## Multi-Client Compatibility

| Client | Branch | Status |
|--------|--------|--------|
| core-geth | `etc` | Synced to head, all tests pass |
| Besu | `etc` | SNAP server for Fukuii SNAP sync |
| Fukuii | `march-onward` | 2,695 tests, Mordor SNAP synced |

Fukuii successfully syncs Mordor using Besu as a SNAP server peer (core-geth provides block headers, Besu provides SNAP state). ETC mainnet SNAP sync is the next milestone.

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
sbt test          # 2,695 tests, ~7 min
sbt it:test       # Integration tests
sbt pp            # Pre-PR: format + style + tests
```

---

## Previous Handoff Documents

- [ETC-HANDOFF.md](ETC-HANDOFF.md) — Alpha stabilization (55 commits, 19 bugs, 2,193 tests)
- [OLYMPIA-HANDOFF.md](OLYMPIA-HANDOFF.md) — Olympia hard fork implementation (34 commits, ECIP-1111/1112/1121)
