# Fukuii Production-Readiness Backlog

Comprehensive inventory of remaining work, verified against the codebase and compared to reference clients (go-ethereum, nethermind, core-geth, Besu, Erigon).

**Branch:** `march-onward` (~51 commits ahead of upstream main, at `6220ce58b`)
**Test baseline:** 2,690 tests pass, 0 failed, 2 ignored
**RPC methods:** 118 implemented, all wired to `JsonRpcController`, zero orphaned
**Last audited:** 2026-03-26

---

## Reference Client Comparison

| Client          | Lang  | RPC Methods | Key Differentiators                                                                                            |
| --------------- | ----- | ----------- | -------------------------------------------------------------------------------------------------------------- |
| **go-ethereum** | Go    | ~70         | Reference implementation, bintrie/flat state, Engine API (PoS), EIP-7702 witness support                       |
| **core-geth**   | Go    | 68+         | pprof, 30+ debug\_\* methods, state overrides, eth_createAccessList, JWT auth, ECBP-1100 MESS                  |
| **Nethermind**  | C#    | ~140        | Parity RPC compat (trace\_\*), Flashbots/MEV, plugin system, timestamp-based fork activation, 35 debug methods |
| **Besu**        | Java  | 179         | GraphQL, plugin system, permissioning, 14 debug\_\* methods                                                    |
| **Erigon**      | Go    | ~160        | Staged sync, MDBX, advanced tracing                                                                            |
| **Fukuii**      | Scala | **118**     | MCP server (unique), IELE VM, IPC, TUI, SNAP server+client, WS subscriptions                                   |

### Network Upgrade Safety Comparison

| Feature                   | go-ethereum           | core-geth          | Nethermind                | Besu      | Fukuii                                           |
| ------------------------- | --------------------- | ------------------ | ------------------------- | --------- | ------------------------------------------------ |
| Fork ID (EIP-2124)        | Yes (4-rule)          | Yes (4-rule)       | Yes (3-result)            | Yes       | Yes (`ForkIdValidator`)                          |
| Bad block tracking        | DB-backed (10 max)    | BadHashes map      | In-memory + RPC           | Yes       | `BadBlockTracker` (Scaffeine, 128 entries, 1h TTL) |
| Peer ban on invalid block | Downloader reputation | Blacklist          | Disconnect + reputation   | Yes       | Blacklist (30 reasons)                           |
| Chain split detection     | Handshake-only        | Handshake + MESS   | Handshake-only            | Handshake | Handshake-only                                   |
| Operator fork warnings    | **None**              | AF activation logs | **None**                  | **None**  | **None** (see M-017)                             |
| Deep reorg protection     | None (PoW)            | ECBP-1100 MESS     | InvalidChainTracker (PoS) | None      | MESS implemented (deactivated at Spiral)         |
| Fork-boundary tests       | Implicit              | Implicit           | Implicit                  | Implicit  | `OlympiaForkBoundarySpec` (12 tests) + `OlympiaForkIdSpec` (13 tests) |

### Fukuii Strengths (unique or ahead of reference clients)

- **MCP server** (15 tools, 9 resources, 4 prompts) — only blockchain client with LLM integration via open MCP protocol. LLM-agnostic: works with Claude, ChatGPT, Gemini, Grok, Llama, Mistral, and any JSON-RPC 2.0 client. See M-019 for multi-LLM docs.
- **IELE VM support** — experimental VM
- **TUI** (JLine 3, 306-line renderer, 8+ panels) — first ETC client with terminal UI
- **IPC support** — Unix domain socket RPC (`JsonRpcIpcServer.scala`)
- **SNAP server + client** — full bidirectional SNAP protocol with Merkle boundary proofs
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

### C-004: Unknown branch resolution infinite loop guard ✅ DONE

- **File:** `src/main/scala/.../sync/regular/BlockImporter.scala`, `src/main/scala/.../domain/BlockHeader.scala`
- **Priority:** Critical | **Risk:** High (sync-critical)
- **Resolution:** Three-part fix for SNAP→regular sync stall:
  1. **Fork-agnostic RLP decoder** — position-based decoding accepting 15+ fields (detects baseFee by list length, not fork config). Matches go-ethereum, Besu, Erigon, Nethermind pattern.
  2. **SNAP gap detection in BranchResolution** — `UnknownBranch` handler checks if best block has a stored header but walk-back target doesn't (SNAP gap). Resets to best block instead of infinite walk-back.
  3. **Fork-incompatible peer logging** — detects `HeaderExtraFieldsError`/`HeaderBaseFeeError` in block import failures and logs fork-incompatibility warning.
  4. **Improved start() logging** — logs hash alongside block number on regular sync start, warns when no stored header found (SNAP incomplete).
- **Discovered:** 2026-03-25 — triggered by HeaderExtraFieldsError after SNAP→regular sync transition on Mordor.

---

## Tier 1: HIGH — Production Blockers

### 1.1 — RPC API Gaps

#### H-001: Expand debug\_\* API ✅ DONE (14 methods)

- **File:** `src/main/scala/.../jsonrpc/DebugService.scala`, `DebugTracingService.scala`, `StructLogTracer.scala`
- **Priority:** High | **Risk:** Low
- **Resolution:** 14 debug methods across two services:
  - Raw data (4): `debug_getRawHeader`, `debug_getRawBlock`, `debug_getRawReceipts`, `debug_getRawTransaction` — RLP-encoded DB lookups via BlockchainReader
  - Operations (2): `debug_getBadBlocks` (returns tracked bad blocks), `debug_setHead` (rewind best block)
  - Profiling (3): `debug_memStats` (JVM heap/non-heap via ManagementFactory), `debug_gcStats` (GC collector stats), `debug_stacks` (all thread dumps)
  - Tracing (5): `debug_traceTransaction`, `debug_traceBlock`, `debug_traceCall`, `debug_traceBlockByHash`, `debug_traceBlockByNumber` — geth-style structLog format via `StructLogTracer` hooked into VM exec loop. Supports TraceConfig (disableStack, disableStorage, enableMemory, enableReturnData, limit). Prior-tx replay for correct state. Nested CALL/CREATE captured automatically via VM-level tracer.
  - `debug_accountRange` + `debug_storageRangeAt` remain in TestService (available when test mode enabled)

#### H-002: eth_feeHistory ✅ DONE

- **Priority:** High | **Risk:** Low
- **Description:** Gas price history with percentiles. Required by MetaMask for EIP-1559 gas estimation.
- **Resolution:** Full implementation in `EthTxService.feeHistory()` — baseFeePerGas array (N+1 entries with next-block prediction via `BaseFeeCalculator`), gasUsedRatio, weighted percentile reward calculation matching go-ethereum's algorithm. 1024 block count limit. Handles both 2-param (no percentiles) and 3-param forms. JSON codec in `EthTxJsonMethodsImplicits`. Wired to `JsonRpcController`.

#### H-003: eth_maxPriorityFeePerGas ✅ DONE

- **Priority:** High | **Risk:** Low
- **Description:** Returns suggested priority fee. Required by wallets for EIP-1559 transactions.
- **Resolution:** Implemented in `EthTxService.getMaxPriorityFeePerGas()` — samples 20 recent blocks, calculates median effective priority fee (`effectiveGasPrice - baseFee`). Falls back to 1 gwei when no recent blocks or no transactions. JSON codec + controller wiring added.

#### H-004: eth_getBlockReceipts ✅ DONE

- **Priority:** High | **Risk:** Low
- **Description:** Returns all receipts for a block in one call. Used by block explorers and indexers.
- **Resolution:** Implemented in `EthTxService.getBlockReceipts()` — resolves block via `BlockParam`, fetches receipts, builds `TransactionReceiptResponse` for each. Returns `null` for unknown blocks (matches go-ethereum). Manual JSON encoder `encodeReceipt` avoids Scala 3 reflection issues. Controller wiring added.

#### H-005: eth_createAccessList (EIP-2930) ✅ DONE

- **Priority:** High | **Risk:** Medium
- **Description:** Generates optimal access list for a transaction by simulating execution.
- **Resolution:** Implemented in `EthInfoService.createAccessList()` — simulates transaction via `stxLedger.simulateTransaction()`, collects `accessedAddresses` and `accessedStorageKeys` from `ProgramResult` (already tracked by all EVM opcodes: SLOAD, SSTORE, BALANCE, EXTCODEHASH, CALL, etc.). Groups storage keys by address, excludes sender/to/precompiles. Returns access list, gasUsed, and optional error. `TxResult` extended with access fields. JSON codec supports both 1-param (latest block) and 2-param forms.

#### H-006: State overrides for eth_call (EIP-3030) — DONE

- **Priority:** High | **Risk:** Medium
- **Resolution:** Implemented `AccountStateOverride` type with balance/nonce/code/state/stateDiff fields. Modified `doCall()` to build world state and apply overrides before simulation. Updated eth_call and eth_estimateGas JSON decoders to accept optional third parameter (state override map). Matches go-ethereum `internal/ethapi/override.go` Apply() behavior. Full state replacement (`state`) and differential patching (`stateDiff`) both supported.

#### H-007: SyncStateSchedulerActor parent recovery — DONE

- **File:** `src/main/scala/.../sync/fast/SyncStateSchedulerActor.scala`
- **Priority:** High | **Risk:** Medium (sync-critical)
- **Resolution:** Added `StateSyncFailed(reason)` response message. On critical trie error, scheduler now sends `StateSyncFailed` to parent before stopping. FastSync handles it by triggering a pivot refresh (matching geth's skeleton-reset approach). Eliminates silent fast sync stall on malformed trie data.

### 1.2 — Operational

#### H-008: pprof-equivalent profiling endpoint — DONE

- **Priority:** High | **Risk:** Low
- **Resolution:** Two parallel implementations exist:
  - **HTTP:** `ProfilingRoutes.scala` exposes `/debug/pprof/{heap,threads,gc,vm,pools}` — plain text, matches go-ethereum's `--pprof` format
  - **JSON-RPC:** `DebugService.scala` exposes `debug_memStats`, `debug_gcStats`, `debug_stacks` — structured JSON responses
  - Both use JDK ManagementFactory MBeans (no external dependencies)
  - ProfilingRoutes wired in `JsonRpcHttpServer.scala:82`, protected by same JWT gate as RPC
  - CPU profiling (JFR/async-profiler, flame graphs) deferred to M-024

#### H-009: JWT authentication for RPC — DONE

- **Priority:** High | **Risk:** Low
- **Resolution:** Full JWT auth across HTTP and WebSocket:
  - **HTTP:** `JwtAuth.scala` HS256 directive wired into `JsonRpcHttpServer.scala`. Health endpoints exempt.
  - **WebSocket:** `JsonRpcWsServer.scala` now applies same `JwtAuth` directive on HTTP upgrade. Shares `jwt-auth` config with HTTP server.
  - **Config:** `network.rpc.http.jwt-auth { enabled, secret-file }` in `network.conf`. Disabled by default.
  - **Tests:** 12 tests in `JwtAuthSpec.scala` — valid/invalid/expired tokens, clock skew, secret auto-generation, missing auth header.
  - **Secret format:** 32-byte hex file (compatible with geth `--authrpc.jwtsecret`), auto-generates if missing.

### 1.3 — Consensus Validation Gaps (2026-03-25 audit)

#### H-010: Fork-gate transaction type acceptance ✅ DONE

- **Files:** `src/main/scala/.../consensus/validators/StdSignedTransactionValidator.scala:106-131`, `BlockPreparator.scala`
- **Priority:** High | **Risk:** High (consensus-critical)
- **Description:** `TransactionWithDynamicFee` (EIP-1559) and `SetCodeTransaction` (EIP-7702) are accepted by the signature validator without checking fork activation. A malicious peer could send pre-fork blocks containing these transaction types and they would be accepted. Similarly, `BlockPreparator` generates `Type02Receipt` for dynamic-fee transactions without verifying the fork is active. Both core-geth and Besu gate transaction type acceptance on fork block.
- **Resolution:** Added `validateTransactionType()` as first check in `StdSignedTransactionValidator.validate()`. Rejects Type 1 pre-Magneto, Type 2/4 pre-Olympia. Added `TransactionTypeNotSupported` error type.

#### H-011: Pre-Olympia baseFee field rejection ✅ DONE (already correct)

- **File:** `src/main/scala/.../consensus/validators/BlockHeaderValidatorSkeleton.scala:207-218`
- **Priority:** High | **Risk:** High (consensus-critical)
- **Description:** `validateExtraFields()` correctly accepts `HefEmpty` pre-Olympia and `HefPostOlympia` post-Olympia, but does NOT explicitly reject `HefPostOlympia` pre-Olympia. A block decoded with 16 RLP fields (baseFee present) before Olympia activation falls to the default case — but downstream code like `BaseFeeCalculator` may encounter unexpected `baseFee` values on pre-Olympia blocks that slipped through other paths.
- **Resolution:** Already correctly implemented — the exhaustive pattern match in `validateExtraFields()` rejects `HefPostOlympia` pre-Olympia via the `case _` branch which returns `Left(HeaderExtraFieldsError)`.

#### H-012: BaseFee calculation corruption guard ✅ DONE

- **File:** `src/main/scala/.../consensus/BaseFeeCalculator.scala:25-47`
- **Priority:** High | **Risk:** Medium
- **Description:** `calcBaseFee()` uses `parent.baseFee.getOrElse(InitialBaseFee)` for post-Olympia parents. If a post-Olympia parent block somehow has `baseFee=None` (corruption, malformed import), the calculation silently falls back to 1 gwei instead of failing. This produces incorrect baseFee for all subsequent blocks, causing cascading validation failures.
- **Resolution:** Replaced `getOrElse(InitialBaseFee)` with `getOrElse(throw IllegalStateException)` for post-Olympia parents. Matches go-ethereum (panic), Besu (throw), Nethermind (throw) behavior.

#### H-013: SNAP→Regular atomic finalization ✅ DONE

- **File:** `src/main/scala/.../sync/snap/SNAPSyncController.scala`
- **Priority:** High | **Risk:** Medium (sync-critical)
- **Description:** `finalizeSnapSync()` stores the pivot block and then marks `SnapSyncDone` in two separate commits. If the node crashes between these operations, the next restart finds an inconsistent state: `getBestBlockNumber()` returns the pivot but the SnapSyncDone flag may not be set, or vice versa. This can cause regular sync to fail with "Unknown branch" loops.
- **Resolution:** Chained `snapSyncDone()`, `storeBlock()`, `storeChainWeight()`, and `putBestBlockInfo()` into a single atomic `DataSourceBatchUpdate.and().commit()`. Matches go-ethereum's atomic pivot+state write pattern.

### 1.4 — Network Upgrade Safety

#### H-014: Olympia fork boundary validation tests ✅ DONE

- **Priority:** High | **Risk:** High (consensus-critical)
- **Resolution:** Created `OlympiaForkBoundarySpec.scala` with 12 tests covering block N-1/N/N+1 transition: pre-Olympia baseFee acceptance/rejection, fork block initial baseFee (1 gwei) validation, post-Olympia dynamic baseFee calculation, baseFee increase/decrease based on gas usage, RLP encode/decode symmetry (15 vs 16 fields). Uses custom `BlockHeaderValidatorSkeleton` subclass that skips PoW but validates all other rules. All 12 tests pass.
- **Depends on:** H-010, H-011

#### H-015: Chain split detection and handling ✅ DONE

- **Priority:** High | **Risk:** High (network-critical)
- **Resolution:** Created `OlympiaForkIdSpec.scala` with 13 tests verifying EIP-2124 ForkId behavior at the Olympia fork boundary: ForkId hash transition, next announcement, noFork placeholder filtering, peer validation for matching/syncing/stale/incompatible states, cross-configuration detection (upgraded vs non-upgraded nodes), and pre-fork tolerance per EIP-2124 design. Existing infrastructure already handles disconnect and logging: `ForkIdValidator` validates peers at handshake in `EthNodeStatus64ExchangeState`, disconnecting with `UselessPeer` and logging both local/remote ForkIds. No additional code changes needed — the infrastructure is correct and now thoroughly tested.
- **Existing infrastructure verified:** `ForkIdValidator.validatePeer()`, `EthNodeStatus64ExchangeState` (disconnect + logging), `gatherForks()` (auto-includes Olympia when activated)

#### H-016: Adversarial node resilience at fork boundary ✅ DONE

- **Priority:** High | **Risk:** High (consensus-critical)
- **Resolution:** Created `BadBlockTracker` (Scaffeine cache, 128 entries, 1h TTL) for known-bad block hash tracking with peer association — modeled after core-geth's BadHashes map. Created `AdversarialForkBoundarySpec.scala` with 11 tests covering adversarial scenarios: error type classification (HeaderExtraFieldsError vs HeaderBaseFeeError), manipulated/zero/wrong initial baseFee attacks, gasUsed overflow, BadBlockTracker CRUD/eviction/listing, and error string compatibility with BlockImporter's fork detection logic. Fork-boundary validation already correct in `BlockHeaderValidatorSkeleton` (validated by H-014). BlockImporter already distinguishes fork-incompatible errors from general validation failures (no blacklist for fork peers, blacklist for general errors).
- **Depends on:** H-014

---

## Tier 2: MEDIUM — Feature Completeness & Testing

### 2.1 — RPC & Operational

#### M-001: debug runtime profiling RPCs ✅ DONE

- **Priority:** Medium | **Risk:** Low
- **Resolution:** Implemented `debug_memStats` (heap/non-heap usage via `ManagementFactory.getMemoryMXBean`), `debug_gcStats` (GC collector names, counts, times via `getGarbageCollectorMXBeans`), `debug_stacks` (full thread dumps via `Thread.getAllStackTraces`). `debug_cpuProfile` deferred (requires async-profiler JNI integration).

#### M-002: Per-module log verbosity control ✅ DONE

- **Priority:** Medium | **Risk:** Low
- **Resolution:** Implemented `debug_setVerbosity` (set root log level), `debug_setVmodule` (set per-module level, supports short names like "sync" → `com.chipprbots.ethereum.sync` and full package paths), `debug_getVerbosity` (return root level + all module overrides). Uses Logback `LoggerContext` API for runtime level changes without restart. JSON codecs + controller wiring. RPC count: 110 → 113.

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

#### M-006: Additional miner methods ✅ DONE

- **Priority:** Medium | **Risk:** Low
- **Description:** `miner_setMinGasPrice`, `miner_setExtraData`, `miner_changeTargetGasLimit` — zero references. Lower priority since gas price/extra data are less configurable in ETC PoW consensus.
- **Resolution:** All 3 methods implemented with JSON codecs, controller routing, and AtomicReference-backed dynamic config in BlockGeneratorSkeleton. RPC count: 94 → 97.

#### M-022: eth_signTransaction — DONE

- **Priority:** Medium | **Risk:** Low
- **Resolution:** Signs transaction without broadcasting. Accepts `{from, to, value, gas, gasPrice, nonce, data}` + passphrase, returns `{raw: "0x..."}` signed bytes. Uses existing `Wallet.signTx()` infrastructure with EIP-155 chain-specific signing. Wired in `JsonRpcController`, codec in `JsonMethodsImplicits`.

#### M-023: eth_getHeaderByNumber / eth_getHeaderByHash — DONE

- **Priority:** Medium | **Risk:** Low
- **Resolution:** Lightweight header-only retrieval. `eth_getHeaderByNumber(blockParam)` and `eth_getHeaderByHash(hash)` return block header JSON without `transactions` or `uncles` fields. Uses existing `resolveBlock()` and `getBlockHeaderByHash()`. Codecs strip transactions/uncles via `removeField`. Wired in `JsonRpcController`.

#### M-024: debug_cpuProfile

- **Priority:** Medium | **Risk:** Low
- **Description:** CPU profiling via RPC (deferred from M-001). Requires async-profiler JNI integration or JFR activation via `ManagementFactory`. go-ethereum exposes `debug.CpuProfile()` via pprof. JVM equivalent: start/stop JFR recording and return the `.jfr` file as hex, or integrate async-profiler for flamegraph output.
- **Reference:** go-ethereum `internal/debug/api.go` `CpuProfile()`, `StartCpuProfile()`, `StopCpuProfile()`

#### M-025: README accuracy audit

- **Priority:** Medium | **Risk:** Low
- **Description:** README contains outdated metrics and missing feature mentions:
  - Test count says "2,314" — actual is 2,642+
  - Missing mention of WebSocket subscriptions (`eth_subscribe`/`eth_unsubscribe`)
  - Missing mention of trace_* (6 methods), txpool_* (4 methods), admin_* (7 methods)
  - "Full eth/web3/net API support" claim needs qualification or method count
  - RPC method count not mentioned (now 113)
  - Missing `/buildinfo` endpoint in health documentation
  - Missing rate limiting mention in Key Features

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

#### M-015: SNAP state freshness after reorg past pivot

- **File:** `src/main/scala/.../sync/snap/SNAPSyncController.scala`
- **Priority:** Medium | **Risk:** Medium (sync-critical)
- **Description:** After SNAP sync completes at a pivot block, if a chain reorg occurs that invalidates the pivot (e.g., reorg at blocks before the pivot), regular sync inherits stale state. SNAP only stores the pivot header area — there are no headers to walk back to the pre-reorg state. The node could sync to the minority fork and later orphan all blocks after the reorg point.
- **Discovered:** 2026-03-25 full feature audit.

#### M-021: SNAP storage slot monotonicity validation ✅ DONE

- **File:** `src/main/scala/.../sync/snap/SNAPRequestTracker.scala:205-229`
- **Priority:** Medium | **Risk:** Low
- **Resolution:** Already implemented in `validateStorageRanges()` — iterates each account's slots and verifies `compareUnsignedLexicographically(prev, curr) < 0`. Rejects responses with non-monotonic slot ordering.

### 2.4 — Network Upgrade Testing

#### M-016: MESS behavior verification at Olympia boundary

- **Priority:** Medium | **Risk:** Medium
- **Description:** MESS (ECBP-1100) was deactivated at Spiral on both networks (Mordor: 10,400,000, ETC: 19,250,000). Olympia activates well after deactivation. Verify:
  - MESS is correctly inactive at Olympia activation block (both Mordor and ETC)
  - `MESSConfig.isActiveAtBlock(olympiaBlockNumber)` returns false
  - `BranchResolution.shouldMessReject()` does NOT apply MESS scoring at Olympia blocks
  - If MESS is ever re-activated post-Olympia (governance decision), the polynomial curve works correctly with EIP-1559 block timestamps
  - Deep reorg attempt at Olympia boundary without MESS protection — verify TD comparison still works correctly as the sole fork choice mechanism
- **Reference:** core-geth `blockchain_af.go` ECBP-1100 activation window, `--ecbp1100.nodisable` flag
- **Existing base:** `MESScorerSpec.scala` (43 tests), `MESSIntegrationSpec.scala` — needs Olympia-era test cases
- **Approach:** Add test cases to `MESScorerSpec` and `MESSIntegrationSpec` verifying MESS inactivity at Olympia blocks. Add test for hypothetical re-activation.

#### M-017: Operator upgrade signaling and warnings

- **Priority:** Medium | **Risk:** Low
- **Description:** Operators need clear signals about upcoming forks. Implement:
  - **Countdown logging:** When Olympia is configured and current head approaches activation, log periodic warnings: "Olympia activates in N blocks (~X hours)"
  - **Fork readiness RPC:** Expose fork schedule via `fukuii_getForkSchedule` or `admin_nodeInfo` extension — return all configured forks with activation status
  - **Post-activation confirmation:** Log "Olympia activated at block N. baseFee: X, treasury balance: Y" on first post-fork block
  - **Stale client warning:** If node is significantly behind chain head AND Olympia has passed, warn that the node may be on a minority fork
- **Reference:** go-ethereum `log.Warn("Upcoming fork", ...)`, Besu `ProtocolSchedule` logging, core-geth `blockchain_af.go:41-46` AF warnings

#### M-018: Hive integration test coverage for Olympia

- **Priority:** Medium | **Risk:** Low
- **Description:** The hive multi-client testing framework needs Olympia-specific test suites:
  - Fork ID matching: pre-fork peer ↔ post-fork peer → disconnect
  - Block validation at fork boundary (N-1, N, N+1)
  - Cross-client consensus: Fukuii, core-geth, and Besu must produce identical state roots at block N
  - Reorg across fork boundary with correct rule application per era
- **Reference:** `hive/simulators/ethereum/` existing test patterns (https://github.com/ethereum/hive)
- **Existing base:** core-geth + besu-etc PASSING in hive, fukuii build WIP
- **Depends on:** Fukuii hive client build completion, H-014

#### M-019: MCP server multi-LLM documentation and examples

- **Priority:** Medium | **Risk:** Low
- **Description:** The MCP server is already fully LLM-agnostic (pure JSON-RPC 2.0 over HTTP, zero provider-specific code), but documentation and config examples only reference Claude Desktop. Expand to explicitly support all major LLMs:
  - **Proprietary:** Claude (Anthropic), ChatGPT (OpenAI), Gemini (Google), Grok (xAI), Copilot (Microsoft/GitHub)
  - **Open-source:** Llama (Meta), Mistral, DeepSeek, Qwen (Alibaba), Phi (Microsoft), Gemma (Google), Command R (Cohere)
  - Add config examples for each (HTTP transport, stdio proxy bridge where needed)
  - Update `docs/MCP.md` and `docs/api/MCP_INTEGRATION_GUIDE.md` to remove Claude-only framing
  - Update BACKLOG strengths description and README
- **Existing base:** `McpService.scala` (236 lines), `.github/copilot/mcp.json` (Claude Desktop config)
- **No code changes needed** — only documentation and config examples

#### M-020: go-ethereum pre-merge PoW codebase review

- **Priority:** Medium | **Risk:** Low
- **Description:** go-ethereum removed PoW consensus code after The Merge (September 2022, block 15,537,394). ETC still uses PoW. Review the pre-merge go-ethereum codebase (v1.10.x, before `consensus/ethash` was removed) to identify:
  - PoW-specific fork activation patterns that Fukuii should adopt
  - Ethash/difficulty calculation improvements made between go-ethereum's ETC fork and The Merge
  - Block validation logic for PoW chains (uncle validation, difficulty bombs, reward calculation)
  - PoW-specific peer scoring and sync optimizations (e.g., TD-based peer selection)
  - Any PoW safety mechanisms that were removed post-merge but remain relevant for ETC
- **Reference:** go-ethereum v1.10.26 (last PoW release), `consensus/ethash/`, `core/block_validator.go`, `eth/downloader/`
- **Approach:** Checkout go-ethereum at tag `v1.10.26` or last pre-merge commit. Systematic diff against current Fukuii consensus code. Source any missing PoW hardening.

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

H-010 (fork-gate txs) ─┬── H-014 (fork boundary tests)
H-011 (baseFee reject) ─┘
H-014 ── H-016 (adversarial — needs boundary validation first)
H-015 (chain split) ── M-018 (hive — cross-client chain split)
```

---

## Sprint Execution Order

| Phase                | Items                                           | Focus                                                          |
| -------------------- | ----------------------------------------------- | -------------------------------------------------------------- |
| 1 — Stabilize        | C-001, C-002, C-003, C-004                      | Merge PRs, fix healing, prod log, branch loop guard            |
| 2 — Consensus        | H-010, H-011, H-012, H-013                      | Fork-gate txs, baseFee guards, SNAP atomic finalization        |
| 3 — PoW Mining       | M-020, M-006                                    | Pre-merge go-ethereum review, miner methods                    |
| 4 — Fee Market       | H-002, H-003, H-004, H-005                      | Wallet/explorer compatibility (MetaMask, EIP-1559)             |
| 5 — Debug Expand     | H-001, M-001                                    | Geth-style tracing + runtime profiling RPCs                    |
| 6 — Simulation       | H-006                                           | State overrides for eth_call (Tenderly/Foundry/Hardhat)        |
| 7 — Operations       | H-008, H-009, M-002, M-017                      | Profiling, JWT auth, log control, upgrade signaling            |
| 8 — Fork Safety      | H-014, H-015, H-016, M-016                      | Fork boundary tests, chain split, adversarial, MESS            |
| 9 — Sync + Network   | H-007, M-003..M-005, M-015, M-021               | Recovery, work-stealing, SNAP reorg freshness, slot validation |
| 10 — Testing         | M-009..M-014, M-018                             | Full test suite pass, hive Olympia coverage                    |
| 11 — Polish + Future | M-007, M-008, M-019, M-022..M-025, L-001..L-006, F-001..F-006 | Perf, MCP docs, missing RPC, README, networking, GraphQL       |

**Methodology:** Each item requires reviewing all 5 reference clients (go-ethereum primary, core-geth, Besu, Erigon, Nethermind) before implementation. For PoW consensus: pre-Sept 2022 go-ethereum v1.10.26, Besu, current core-geth.

---

## GitHub Issues

| #    | Title                             | Priority | Backlog Ref |
| ---- | --------------------------------- | -------- | ----------- |
| 1005 | SNAP healing actor name collision | Critical | C-002       |
| 975  | ECIP-1121 research                | Medium   | F-004       |
| 972  | ECIP-1120 research                | Medium   | F-005       |
| 969  | PoC - pmkt-1                      | Low      | —           |
| 966  | Release builder                   | Medium   | F-006       |
| 959  | Gorgoroth Trial                   | Medium   | —           |
| 936  | 37 nightly failures               | Medium   | M-011       |

## Open PRs

| #    | Title                                  | Status              | Backlog Ref |
| ---- | -------------------------------------- | ------------------- | ----------- |
| 1008 | SNAP test coverage (~144 tests)        | OPEN, review needed | C-001       |
| 1007 | SNAP finalization deadlocks (3 fixes)  | OPEN, review needed | C-001       |
| 1006 | Automated bootnode update (2026-03-22) | OPEN, routine       | —           |
| 1004 | Automated bootnode update (2026-03-15) | OPEN, routine       | —           |

---

## Item Count

| Tier                | Count  | Description                                                                                                                                                                                                                                  |
| ------------------- | ------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Tier 0 (CRITICAL)   | 3      | SNAP PRs, healing fix, prod log (C-004 DONE)                                                                                                                                                                                                 |
| Tier 1 (HIGH)       | 13     | debug expansion, fee market, access lists, state overrides, sync recovery, profiling, JWT, tx fork-gating, baseFee guards, SNAP finalization (H-014, H-015, H-016 DONE)                                                                    |
| Tier 2 (MEDIUM)     | 20     | debug profiling, log verbosity, SNAP work-stealing, miner methods, testing push, perf, SNAP reorg freshness, MESS verification, operator signaling, hive Olympia, MCP multi-LLM docs, go-ethereum pre-merge PoW review (M-021 DONE)         |
| Tier 3 (LOW)        | 6      | networking polish, API docs, operator guide                                                                                                                                                                                                  |
| Tier 4 (FUTURE)     | 6      | GraphQL, Stratum, plugin system, GUI, releases                                                                                                                                                                                               |
| **Total remaining** | **48** | Was 53, minus C-004, M-021, H-014, H-015, H-016                                                                                                                                                                                              |

---

## Completed on march-onward (32+ items, verified 2026-03-26)

Items below were implemented on the `march-onward` branch and verified against the actual codebase with commit hashes and file locations.

| Item                               | Commit/File                                    | Verification                            |
| ---------------------------------- | ---------------------------------------------- | --------------------------------------- |
| BLS12-381 precompiles (all 7)      | `15be56ddf`                                    | `PrecompiledContracts.scala:566-672`    |
| trace\_\* API (6 methods, wired)   | `51af7c092`, `df18db4c5`                       | `JsonRpcController.scala:403-409`       |
| txpool\_\* API (4 methods, wired)  | `b09d40b46`                                    | `JsonRpcController.scala:392-398`       |
| admin\_\* API (7 methods, wired)   | `cf53a0231`                                    | `JsonRpcController.scala:374-387`       |
| Mining block counter               | `2752e5e04`                                    | `EthMiningService.scala:99,177,239`     |
| Mining recommit interval           | `2752e5e04`                                    | `EthMiningService.scala:250-260`        |
| Mining HTTP work notifications     | `d736e99d3`                                    | `WorkNotifier`                          |
| ExpiringMap thread safety          | `f740c9b52`                                    | `ConcurrentHashMap` replacement         |
| NetService blacklist metadata      | `f740c9b52`                                    | Real reasons, duration tracking         |
| SNAP server (4 handlers + probe)   | `af54fc877`..`0bc35acca`                       | `NetworkPeerManagerActor.scala:195-205` |
| SNAP bytecode hash validation      | `0d4f8873b`                                    | `SNAPRequestTracker`                    |
| Atomic block discard               | `668b25344`                                    | `removeBlockRange`                      |
| State root skip on restart         | `7653df42b`                                    | Skip when root exists                   |
| MPT node recovery                  | `84aafc187`                                    | Recover instead of crash                |
| eth_subscribe full tx objects      | WebSocketHandler                               | `includeTransactions` param             |
| EC-243 gas dedup                   | `6220ce58b`                                    | `opcodeGasCost` in `ProgramState`       |
| RocksDB write optimization         | `a3923989f`                                    | Bulk sync options                       |
| State prefetching                  | `1017f72f3`                                    | Next block prefetch                     |
| Fast sync exponential backoff      | `3343f1af5`                                    | Retry intervals                         |
| Adaptive timeouts + pipelining     | `5452a3268`                                    | `PeerRateTracker`                       |
| LRU trie node cache                | `dcceb2150`                                    | Upper trie nodes                        |
| Flat storage (RocksDB)             | `cbed8f822`, `0bd91afe5`                       | `FlatAccountStorage`                    |
| DNS discovery (EIP-1459)           | `DnsDiscovery.scala`                           | ENR tree, ETC + Mordor domains          |
| Health checks (liveness/readiness) | `NodeJsonRpcHealthChecker.scala`               | `/health`, `/readiness`, 6 checks       |
| Log rotation                       | `logback.xml` + `ResilientRollingFileAppender` | 10MB, 50 archives, zip                  |
| Rate limiting                      | `RateLimit.scala`                              | Guava LRU, HTTP 429                     |
| TUI (8+ panels)                    | `TuiRenderer.scala` (306 lines)                | SNAP viz, colorized, configurable       |
| Metrics dashboards (8)             | `ops/barad-dur/grafana/`                       | Prometheus + Docker Compose             |
| RPC method allowlist               | `JsonRpcBaseController.enabledApis`            | Per-namespace HOCON config              |
| 78 new tests (8 phases)            | Multiple commits                               | All passing                             |
| SNAP sync stall fix (C-004)        | `8f80f2ff8`                                    | Fork-agnostic decoder + gap detection   |
| SNAP server Merkle proofs          | `a0f255a54`                                    | Boundary proofs for partial ranges      |
| SNAP client proof validation       | `AccountRangeWorker`, `StorageRangeCoordinator` | MerkleProofVerifier at worker level    |
| SNAP slot monotonicity (M-021)     | `SNAPRequestTracker:205-229`                   | Already implemented                     |
| Fork boundary tests (H-014)       | `eaf830404`                                    | 12 tests: baseFee, RLP, gas dynamics    |
| Chain split detection (H-015)     | `d2d87b32a`                                    | 13 tests: ForkId + peer validation      |
| Adversarial resilience (H-016)   | `79d80c4e2`                                    | BadBlockTracker + 11 adversarial tests  |

### Resolved in FIXME/TODO Audit (2026-03-25)

| Item                                                                           | Resolution                                                                           |
| ------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------ |
| EC-242 SELFDESTRUCT storage (`BlockPreparator.scala:194`)                      | Already handled by `InMemoryWorldStateProxy.deleteAccount`                           |
| Config caching (`Config.scala:469`)                                            | Configs parsed once at startup — caching unnecessary                                 |
| Fork management (`EvmConfig.scala:33`)                                         | Priority-sorted list is functionally correct — equivalent to geth's `IsEnabled()`    |
| Stack List vs Vector (`Stack.scala:15`)                                        | List would be worse (O(n) indexed). Vector correct. See M-008 for real optimization. |
| PoW two-type extraction (`PoWMining.scala:105`)                                | Mutex + Option pattern works correctly                                               |
| JsonRpcBaseController config (`JsonRpcBaseController.scala:40`)                | Config required for enabledApis filtering                                            |
| MessageSerializableImplicit redundancy (`MessageSerializableImplicit.scala:5`) | `msg: T` provides typed access used by all 41 subclasses — NOT redundant             |
| Chain config format (`*-chain.conf:50`)                                        | Inline comments already document EIP-170 gating                                      |
| Mallet wiki reference (`mallet.conf:10`)                                       | IOHK wiki no longer exists — HTTPS docs kept inline                                  |
| RPC test script (`rpcTest/README.md:45`)                                       | Superseded by `ops/test-scripts/test-rpc-endpoints.sh`                               |
| EC-243 gas dedup (`OpCode.scala`)                                              | Fixed — `opcodeGasCost` in `ProgramState`                                            |

---

## Remaining TODOs in Source (6)

| File                            | Line | Comment                                       | Status                       |
| ------------------------------- | ---- | --------------------------------------------- | ---------------------------- |
| `SyncStateSchedulerActor.scala` | 473  | `TODO [BACKLOG A-004]` parent supervision gap | Valid — see H-007            |
| `MessageCodec.scala`            | 127  | `TODO [BACKLOG N-003]` version-aware decoding | Valid — see M-004            |
| `PeerActor.scala`               | 136  | `TODO [BACKLOG N-004]` capability passing     | Valid — see M-005            |
| `ExpiringMap.scala`             | 23   | `TODO: Make class thread safe`                | Resolved — ConcurrentHashMap |
| `pekko.conf`                    | 4    | `TODO(production)` change loglevel to INFO    | Valid — see C-003            |
| `EthereumTestsSpec.scala`       | 59   | TODO: execute using BlockExecution            | Valid — see M-009            |

---

## Pre-Upstream-PR Cleanup (DONE)

Final gate before submitting PR to upstream. All items completed in a single pass.

| Item | Status | Details |
|------|--------|---------|
| Gitignore local run scripts | ✅ DONE | Removed `!run-classic.sh`/`!run-mordor.sh` negative patterns, untracked from git |
| Remove hardcoded local paths | ✅ DONE | 13 files had `/media/dev/2tb/` — replaced with env var defaults and generic paths |
| Fix broken doc links | ✅ DONE | Removed references to non-existent `ETC-HANDOFF.md` and `OLYMPIA-HANDOFF.md` |
| Update test counts | ✅ DONE | README and BACKLOG updated from 2,314/2,642 → 2,678 |
| Fix CHANGELOG typo | ✅ DONE | "Rebranded from Fukuii to Fukuii" → "Rebranded from Mantis to Fukuii" |
| Move internal HANDOFF.md | ✅ DONE | Moved to `.claude/` (not public-facing) |

---

## Legend

| Priority     | Meaning                                          |
| ------------ | ------------------------------------------------ |
| **Critical** | Blocks merge or production deployment            |
| **High**     | Blocks production use or causes data loss        |
| **Medium**   | Improves reliability or correctness meaningfully |
| **Low**      | Nice-to-have optimization or cleanup             |
| **Future**   | Post-production roadmap items                    |

| Risk       | Meaning                                          |
| ---------- | ------------------------------------------------ |
| **High**   | Consensus-critical or sync-critical code path    |
| **Medium** | Could affect peer connectivity or data integrity |
| **Low**    | Isolated change with limited blast radius        |
