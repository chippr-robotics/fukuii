# Fukuii Production-Readiness Backlog

Comprehensive inventory of remaining work, verified against the codebase and compared to reference clients (core-geth, Besu, Erigon).

**Branch:** `march-onward` (~47 commits ahead of upstream main, at `6220ce58b`)
**Test baseline:** 2,459 tests pass, 0 failed, 0 ignored
**RPC methods:** 94 implemented, all wired to `JsonRpcController`, zero orphaned
**Last audited:** 2026-03-25

---

## Reference Client Comparison

| Client | Lang | RPC Methods | Key Differentiators |
|--------|------|-------------|---------------------|
| **core-geth** | Go | 68+ | pprof, 30+ debug_* methods, state overrides, eth_createAccessList, JWT auth |
| **Besu** | Java | 179 | GraphQL, plugin system, permissioning, 14 debug_* methods |
| **Erigon** | Go | ~160 | Staged sync, MDBX, advanced tracing |
| **Fukuii** | Scala | **94** | MCP integration (unique), IELE VM, IPC, TUI, SNAP server+client |

### Fukuii Strengths (unique or ahead of reference clients)

- **MCP server** (7 methods) — only ETC client with Claude AI integration
- **IELE VM support** — experimental VM
- **TUI** (JLine 3, 306-line renderer, 8+ panels) — first ETC client with terminal UI
- **IPC support** — Unix domain socket RPC (`JsonRpcIpcServer.scala`)
- **SNAP server + client** — full bidirectional SNAP protocol
- **BLS12-381 precompiles** — all 7 implemented (gnark JNI)
- **DNS discovery** — EIP-1459, ENR tree, ETC + Mordor domains
- **Health checks** — `/health`, `/readiness`, `/healthcheck`, `/buildinfo` with 6 checks
- **Log rotation** — `ResilientRollingFileAppender`, 10MB max, 50 archives, zip
- **Rate limiting** — `RateLimit.scala`, Guava LRU, HTTP 429, configurable
- **Metrics dashboards** — 8 Grafana dashboards + Prometheus + Docker Compose
- **RPC method allowlist** — `enabledApis` per-namespace filtering from HOCON config

---

## Tier 0: CRITICAL — Merge & Stabilize

### C-001: Merge open SNAP PRs (#1007, #1008)
- **Priority:** Critical | **Risk:** High (sync-critical)
- **#1007:** Fix SNAP finalization deadlocks (3 fixes)
- **#1008:** Comprehensive SNAP test coverage (~144 tests, 2,770 lines)
- **Action:** Review and merge both PRs to unblock downstream work.

### C-002: Fix SNAP healing actor name collision (#1005)
- **Priority:** Critical | **Risk:** High (sync-critical)
- **Description:** Actor name collision during SNAP healing phase. Needs `healingStarted` guard to prevent duplicate actor creation.
- **Depends on:** C-001

### C-003: Production log level
- **File:** `src/main/resources/conf/base/pekko.conf:4`
- **Priority:** Critical | **Risk:** Low
- **Description:** `TODO(production)` — Pekko actor system log level is currently DEBUG. Must be changed to INFO before mainnet release. Trivial change but blocks production deployment.

---

## Tier 1: HIGH — Production Blockers

### 1.1 — RPC API Gaps

#### H-001: Expand debug_* API
- **File:** `src/main/scala/.../jsonrpc/DebugService.scala` (52 lines)
- **Priority:** High | **Risk:** Low
- **Description:** DebugService has ONLY `debug_listPeersInfo`. `debug_accountRange` and `debug_storageRangeAt` exist but ONLY in `TestService` (not production-wired). Core-geth has 30+ debug methods; Besu has 14.
- **Need (11+ methods):**
  - Tracing: `debug_traceBlock`, `debug_traceTransaction`, `debug_traceCall`, `debug_traceBlockByHash`, `debug_traceBlockByNumber`
  - Raw data: `debug_getRawHeader`, `debug_getRawBlock`, `debug_getRawReceipts`, `debug_getRawTransaction`
  - State: Move `debug_accountRange` + `debug_storageRangeAt` from TestService to DebugService
  - Other: `debug_getBadBlocks`, `debug_setHead`

#### H-002: eth_feeHistory
- **Priority:** High | **Risk:** Low
- **Description:** Zero references in codebase. Gas price history with percentiles. Required by MetaMask for EIP-1559 gas estimation. Both core-geth and Besu implement this.

#### H-003: eth_maxPriorityFeePerGas
- **Priority:** High | **Risk:** Low
- **Description:** Zero references in codebase. Returns suggested priority fee. Required by wallets for EIP-1559 transactions.
- **Depends on:** H-002

#### H-004: eth_getBlockReceipts
- **Priority:** High | **Risk:** Low
- **Description:** Zero references in codebase. Returns all receipts for a block in one call. Used by block explorers and indexers for efficient receipt retrieval.

#### H-005: eth_createAccessList (EIP-2930)
- **Priority:** High | **Risk:** Medium
- **Description:** Zero references in codebase. Generates optimal access list for a transaction by simulating execution. Both core-geth and Besu support this. Used by wallets and tooling to reduce gas costs.

#### H-006: State overrides for eth_call (EIP-3030)
- **Priority:** High | **Risk:** Medium
- **Description:** No `StateOverride` type in codebase. `CallTx` has only: from, to, gas, gasPrice, value, data. State overrides allow simulating calls with modified balances, nonces, code, and storage. Used by: Tenderly, Foundry, Hardhat.

#### H-007: SyncStateSchedulerActor parent recovery
- **File:** `src/main/scala/.../sync/fast/SyncStateSchedulerActor.scala:478`
- **Priority:** High | **Risk:** Medium (sync-critical)
- **Description:** On critical trie error, the actor calls `context.stop(self)`. Parent `FastSync` spawns this actor (line 182) but its `Terminated` handler (line 249) only covers `assignedHandlers` — NOT the scheduler child. **No recovery path exists.** If the trie is malformed, the scheduler dies silently and fast sync stalls indefinitely.
- **Reference clients:** geth restarts with fresh pivot (skeleton reset). Besu falls back to full sync.
- **Approach:** Either (a) send `StateSyncFailed` to parent before stopping, or (b) `FastSync` should `context.watch(syncStateScheduler)` and handle `Terminated`.
- **Depends on:** C-001

### 1.2 — Operational

#### H-008: pprof-equivalent profiling endpoint
- **Priority:** High | **Risk:** Low
- **Description:** Zero references for pprof, profiling, JFR, or async-profiler in codebase. Core-geth exposes `--pprof` → `localhost:6060/debug/pprof/`. JVM equivalent: JFR + async-profiler HTTP endpoint, or JMX MBeans exposed via RPC.

#### H-009: JWT authentication for RPC
- **Priority:** High | **Risk:** Low
- **Description:** Zero references for JWT, bearer tokens, or Authorization headers. Core-geth implements `node/jwt_handler.go`. Required to protect RPC when exposed to untrusted networks.

---

## Tier 2: MEDIUM — Feature Completeness & Testing

### 2.1 — RPC & Operational

#### M-001: debug runtime profiling RPCs
- **Priority:** Medium | **Risk:** Low
- **Description:** Zero references for cpuProfile, memStats, gcStats, stacks, ManagementFactory. Methods: `debug_cpuProfile`, `debug_memStats`, `debug_gcStats`, `debug_stacks`. JVM equivalents available via `java.lang.management.ManagementFactory`.
- **Depends on:** H-001

#### M-002: Per-module log verbosity control
- **Priority:** Medium | **Risk:** Low
- **Description:** Zero references for setLogLevel, verbosity, or vmodule. Static `logback.xml` only — no runtime log level changes. Core-geth supports `--log.vmodule eth/*=5,p2p=4`, `debug_verbosity`, `debug_vmodule`. Logback supports `JMXConfigurator` for runtime changes.

#### M-003: SNAP work-stealing for idle workers
- **File:** `src/main/scala/.../sync/snap/actors/AccountRangeCoordinator.scala`
- **Priority:** Medium | **Risk:** Medium (sync-critical)
- **Description:** No work-stealing queue. 1:1 worker-to-peer mapping. On ETC mainnet with 4 peers/ranges, 2/4 ranges finished early (uneven account density), leaving 2 workers idle while others continued.
- **Approach:** When a range completes, split the largest active task at its `next` midpoint. ~30-40 lines in `handleStoreAccountChunk`.
- **Depends on:** C-001, C-002

#### M-004: Version-aware message decoding
- **File:** `src/main/scala/.../network/rlpx/MessageCodec.scala:127`
- **Priority:** Medium | **Risk:** Low
- **Description:** `TODO [BACKLOG N-003]` — `remotePeer2PeerVersion` is available but not threaded to `fromBytes()`. Compression IS version-aware (line 58), but message format decoding is not. Relevant if Fukuii adopts P2P v5+.

#### M-005: Pass capability to handshake state machine
- **File:** `src/main/scala/.../network/PeerActor.scala:136`
- **Priority:** Medium | **Risk:** Low
- **Description:** `TODO [BACKLOG N-004]` — Capability information from Hello message should be forwarded to `EtcHelloExchangeState`. Capabilities available at `rlpxConnectionFactory` (line 369) but not passed on `InitialHelloReceived`.
- **Depends on:** M-004

#### M-006: Additional miner methods
- **Priority:** Medium | **Risk:** Low
- **Description:** `miner_setMinGasPrice`, `miner_setExtraData`, `miner_changeTargetGasLimit` — zero references. Lower priority since gas price/extra data are less configurable in ETC PoW consensus.

### 2.2 — Performance

#### M-007: JSON-RPC batch parsing optimization
- **File:** `src/main/scala/.../jsonrpc/server/http/JsonRpcHttpServer.scala:90-91`
- **Priority:** Medium | **Risk:** Low
- **Description:** Batch requests work via json4s but with no special optimization (prevalidation, dedup, concurrency). Separate routing paths for single vs. batched requests and cache parsed request body to prevent repeated JSON deserialization.

#### M-008: EVM Stack array-backed optimization
- **File:** `src/main/scala/.../vm/Stack.scala`
- **Priority:** Medium | **Risk:** High (consensus-critical)
- **Description:** Current `Vector[UInt256]` provides O(log32 n) indexed access. Reference clients use array-backed stacks with O(1) access and pooled allocation. Potential throughput improvement for DUP/SWAP-heavy code.

### 2.3 — Testing

#### M-009: Complete EthereumTestsSpec block execution
- **File:** `src/it/scala/.../ethtest/EthereumTestsSpec.scala:59`
- **Priority:** Medium | **Risk:** Low
- **Description:** The spec currently parses test fixtures and sets up initial state but does not execute blocks through `BlockExecution`. Completing this gives full consensus test coverage against the ethereum/tests suite.

#### M-010: Audit 141 SlowTest-tagged tests
- **Priority:** Medium | **Risk:** Low
- **Description:** 141 tests excluded from `sbt test` via SlowTest tag. Need triage to determine which can be moved to standard suite and which require dedicated infrastructure.

#### M-011: Investigate nightly test failures (#936)
- **Priority:** Medium | **Risk:** Low
- **Description:** 37 failures reported in nightly runs. Need triage to separate flaky tests from real regressions.

#### M-012: Run full integration test suite
- **Priority:** Medium | **Risk:** Low
- **Description:** `sbt it:test` — 37 spec files, 1 ignored. Full run needed to establish baseline.
- **Depends on:** C-001

#### M-013: Run full EVM test suite
- **Priority:** Medium | **Risk:** Low
- **Description:** `sbt evmTest:test` — 11 spec files. Full run needed for consensus confidence.

#### M-014: Config validation on startup
- **Priority:** Medium | **Risk:** Low
- **Description:** Implicit validation only — config fails at construction time with opaque errors. No dedicated `ConfigValidator` for pre-flight checks on incompatible flag combinations (e.g., SNAP sync without fast sync enabled).

---

## Tier 3: LOW — Polish

#### L-001: Deprecate pre-EIP-8 handshake support
- **File:** `src/main/scala/.../network/rlpx/RLPxConnectionHandler.scala:298`
- **Priority:** Low | **Risk:** Medium (network compatibility)
- **Description:** EIP-8 (May 2016) added variable-length RLPx handshake encoding. Fukuii supports both pre-EIP-8 and EIP-8. Dropping pre-EIP-8 would simplify the handshake code but could break connectivity with very old peers.

#### L-002: Discovery v5 topic registration
- **Files:** `scalanet` submodule, lines 177, 199
- **Priority:** Low | **Risk:** Low

#### L-003: Discovery v5 ENR parsing
- **File:** `scalanet` submodule, line 588
- **Priority:** Low | **Risk:** Low

#### L-004: KRouter configuration cleanup
- **Files:** `scalanet` submodule (5 TODOs)
- **Priority:** Low | **Risk:** Low

#### L-005: API documentation
- **Priority:** Low | **Risk:** Low
- **Description:** Document all 94+ RPC methods with request/response examples. Would improve developer onboarding and third-party integration.

#### L-006: Operator guide
- **Priority:** Low | **Risk:** Low
- **Description:** Hardware requirements, config tuning, monitoring setup, backup procedures, upgrade path. Currently documentation is developer-focused, not operator-focused.

---

## Tier 4: FUTURE — Post-Production Roadmap

#### F-001: GraphQL endpoint
- **Priority:** Future | **Risk:** Medium
- **Description:** Core-geth, Besu, and Erigon all expose GraphQL. Would allow flexible queries without multiple RPC calls.

#### F-002: Stratum server
- **Priority:** Future | **Risk:** Medium
- **Description:** Neither Fukuii nor core-geth implement Stratum protocol. Would be a differentiator for GPU mining pool connectivity.

#### F-003: Plugin system
- **Priority:** Future | **Risk:** High
- **Description:** Besu model: lifecycle hooks, RPC registration, storage plugins. Major architectural addition.

#### F-004: ECIP-1121 research (#975)
- **Priority:** Future | **Risk:** Low

#### F-005: ECIP-1120 research (#972)
- **Priority:** Future | **Risk:** Low

#### F-006: Release builder (#966) + GUI/web dashboard
- **Priority:** Future | **Risk:** Low

---

## Dependency Graph

```
C-001 (SNAP PRs) ──┬── C-002 (#1005 healing)
                    ├── H-007 (sync recovery)
                    ├── M-003 (work-stealing)
                    └── M-012 (integration tests)

H-002 (feeHistory) ── H-003 (maxPriorityFee)
H-001 (debug expand) ── M-001 (debug profiling)
M-004 (msg decoding) ── M-005 (capability)
```

---

## Sprint Execution Order

| Phase | Items | Focus |
|-------|-------|-------|
| 1 — Stabilize | C-001, C-002, C-003 | Merge PRs, fix healing, prod log |
| 2 — Fee Market | H-002, H-003, H-004, H-005 | Wallet/explorer compatibility |
| 3 — Debug Expand | H-001, M-001 | Geth-style tracing + profiling |
| 4 — Simulation | H-006 | State overrides for eth_call |
| 5 — Operations | H-008, H-009, M-002 | Profiling, auth, log control |
| 6 — Testing | M-009..M-014 | Full test suite pass |
| 7 — Sync + Network | H-007, M-003..M-005 | Recovery, work-stealing |
| 8 — Polish | M-006..M-008, L-001..L-006 | Mining, perf, docs |
| 9 — Future | F-001..F-006 | GraphQL, Stratum, plugin, GUI |

---

## GitHub Issues

| # | Title | Priority | Backlog Ref |
|---|-------|----------|-------------|
| 1005 | SNAP healing actor name collision | Critical | C-002 |
| 975 | ECIP-1121 research | Medium | F-004 |
| 972 | ECIP-1120 research | Medium | F-005 |
| 969 | PoC - pmkt-1 | Low | — |
| 966 | Release builder | Medium | F-006 |
| 959 | Gorgoroth Trial | Medium | — |
| 936 | 37 nightly failures | Medium | M-011 |

## Open PRs

| # | Title | Status | Backlog Ref |
|---|-------|--------|-------------|
| 1008 | SNAP test coverage (~144 tests) | OPEN, review needed | C-001 |
| 1007 | SNAP finalization deadlocks (3 fixes) | OPEN, review needed | C-001 |
| 1006 | Automated bootnode update (2026-03-22) | OPEN, routine | — |
| 1004 | Automated bootnode update (2026-03-15) | OPEN, routine | — |

---

## Item Count

| Tier | Count | Description |
|------|-------|-------------|
| Tier 0 (CRITICAL) | 3 | SNAP PRs, healing fix, prod log |
| Tier 1 (HIGH) | 9 | debug expansion, fee market, access lists, state overrides, sync recovery, profiling, JWT |
| Tier 2 (MEDIUM) | 14 | debug profiling, log verbosity, SNAP work-stealing, miner methods, testing push, perf |
| Tier 3 (LOW) | 6 | networking polish, API docs, operator guide |
| Tier 4 (FUTURE) | 6 | GraphQL, Stratum, plugin system, GUI, releases |
| **Total remaining** | **38** | All verified NOT DONE against codebase |

---

## Completed on march-onward (29+ items, verified 2026-03-25)

Items below were implemented on the `march-onward` branch and verified against the actual codebase with commit hashes and file locations.

| Item | Commit/File | Verification |
|------|-------------|-------------|
| BLS12-381 precompiles (all 7) | `15be56ddf` | `PrecompiledContracts.scala:566-672` |
| trace_* API (6 methods, wired) | `51af7c092`, `df18db4c5` | `JsonRpcController.scala:403-409` |
| txpool_* API (4 methods, wired) | `b09d40b46` | `JsonRpcController.scala:392-398` |
| admin_* API (7 methods, wired) | `cf53a0231` | `JsonRpcController.scala:374-387` |
| Mining block counter | `2752e5e04` | `EthMiningService.scala:99,177,239` |
| Mining recommit interval | `2752e5e04` | `EthMiningService.scala:250-260` |
| Mining HTTP work notifications | `d736e99d3` | `WorkNotifier` |
| ExpiringMap thread safety | `f740c9b52` | `ConcurrentHashMap` replacement |
| NetService blacklist metadata | `f740c9b52` | Real reasons, duration tracking |
| SNAP server (4 handlers + probe) | `af54fc877`..`0bc35acca` | `NetworkPeerManagerActor.scala:195-205` |
| SNAP bytecode hash validation | `0d4f8873b` | `SNAPRequestTracker` |
| Atomic block discard | `668b25344` | `removeBlockRange` |
| State root skip on restart | `7653df42b` | Skip when root exists |
| MPT node recovery | `84aafc187` | Recover instead of crash |
| eth_subscribe full tx objects | WebSocketHandler | `includeTransactions` param |
| EC-243 gas dedup | `6220ce58b` | `opcodeGasCost` in `ProgramState` |
| RocksDB write optimization | `a3923989f` | Bulk sync options |
| State prefetching | `1017f72f3` | Next block prefetch |
| Fast sync exponential backoff | `3343f1af5` | Retry intervals |
| Adaptive timeouts + pipelining | `5452a3268` | `PeerRateTracker` |
| LRU trie node cache | `dcceb2150` | Upper trie nodes |
| Flat storage (RocksDB) | `cbed8f822`, `0bd91afe5` | `FlatAccountStorage` |
| DNS discovery (EIP-1459) | `DnsDiscovery.scala` | ENR tree, ETC + Mordor domains |
| Health checks (liveness/readiness) | `NodeJsonRpcHealthChecker.scala` | `/health`, `/readiness`, 6 checks |
| Log rotation | `logback.xml` + `ResilientRollingFileAppender` | 10MB, 50 archives, zip |
| Rate limiting | `RateLimit.scala` | Guava LRU, HTTP 429 |
| TUI (8+ panels) | `TuiRenderer.scala` (306 lines) | SNAP viz, colorized, configurable |
| Metrics dashboards (8) | `ops/barad-dur/grafana/` | Prometheus + Docker Compose |
| RPC method allowlist | `JsonRpcBaseController.enabledApis` | Per-namespace HOCON config |
| 78 new tests (8 phases) | Multiple commits | All passing |

### Resolved in FIXME/TODO Audit (2026-03-25)

| Item | Resolution |
|------|-----------|
| EC-242 SELFDESTRUCT storage (`BlockPreparator.scala:194`) | Already handled by `InMemoryWorldStateProxy.deleteAccount` |
| Config caching (`Config.scala:469`) | Configs parsed once at startup — caching unnecessary |
| Fork management (`EvmConfig.scala:33`) | Priority-sorted list is functionally correct — equivalent to geth's `IsEnabled()` |
| Stack List vs Vector (`Stack.scala:15`) | List would be worse (O(n) indexed). Vector correct. See M-008 for real optimization. |
| PoW two-type extraction (`PoWMining.scala:105`) | Mutex + Option pattern works correctly |
| JsonRpcBaseController config (`JsonRpcBaseController.scala:40`) | Config required for enabledApis filtering |
| MessageSerializableImplicit redundancy (`MessageSerializableImplicit.scala:5`) | `msg: T` provides typed access used by all 41 subclasses — NOT redundant |
| Chain config format (`*-chain.conf:50`) | Inline comments already document EIP-170 gating |
| Mallet wiki reference (`mallet.conf:10`) | IOHK wiki no longer exists — HTTPS docs kept inline |
| RPC test script (`rpcTest/README.md:45`) | Superseded by `ops/test-scripts/test-rpc-endpoints.sh` |
| EC-243 gas dedup (`OpCode.scala`) | Fixed — `opcodeGasCost` in `ProgramState` |

---

## Remaining TODOs in Source (6)

| File | Line | Comment | Status |
|------|------|---------|--------|
| `SyncStateSchedulerActor.scala` | 473 | `TODO [BACKLOG A-004]` parent supervision gap | Valid — see H-007 |
| `MessageCodec.scala` | 127 | `TODO [BACKLOG N-003]` version-aware decoding | Valid — see M-004 |
| `PeerActor.scala` | 136 | `TODO [BACKLOG N-004]` capability passing | Valid — see M-005 |
| `ExpiringMap.scala` | 23 | `TODO: Make class thread safe` | Resolved — ConcurrentHashMap |
| `pekko.conf` | 4 | `TODO(production)` change loglevel to INFO | Valid — see C-003 |
| `EthereumTestsSpec.scala` | 59 | TODO: execute using BlockExecution | Valid — see M-009 |

---

## Legend

| Priority | Meaning |
|----------|---------|
| **Critical** | Blocks merge or production deployment |
| **High** | Blocks production use or causes data loss |
| **Medium** | Improves reliability or correctness meaningfully |
| **Low** | Nice-to-have optimization or cleanup |
| **Future** | Post-production roadmap items |

| Risk | Meaning |
|------|---------|
| **High** | Consensus-critical or sync-critical code path |
| **Medium** | Could affect peer connectivity or data integrity |
| **Low** | Isolated change with limited blast radius |
