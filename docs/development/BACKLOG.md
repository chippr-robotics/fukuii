# Fukuii Production-Readiness Backlog

Comprehensive inventory of remaining work, verified against the codebase and compared to reference clients (go-ethereum, nethermind, core-geth, Besu, Erigon).

**Branch:** `march-onward` (~90 commits ahead of upstream main, at `e7ab739c6`)
**Test baseline:** 2,705 tests pass, 0 failed, 0 ignored
**RPC methods:** 135 standard + 8 MCP protocol = 143 total, all wired to `JsonRpcController`, zero orphaned
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
| **Fukuii**      | Scala | **143**     | MCP server (unique), IELE VM, IPC, TUI, SNAP server+client, WS subscriptions                                   |

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

### C-001: Merge open SNAP PRs (#1007, #1008) ✅ DONE

- **Priority:** Critical | **Risk:** High (sync-critical)
- **Resolution:** All fixes already implemented on `march-onward`:
  - #1007 (3 deadlock fixes): validateState catch-all sends Done, 2h chain download timeout, stale TODO removed
  - #1008 (144 SNAP tests): All 10 test files already exist in `src/test/.../sync/snap/`

### C-002: Fix SNAP healing actor name collision (#1005) ✅ DONE

- **Priority:** Critical | **Risk:** High (sync-critical)
- **Resolution:** Duplicate guard already at `SNAPSyncController.scala:2044` — "healing coordinator already exists — ignoring duplicate". `trieNodeHealingCoordinator` Option guard prevents duplicate creation.

### C-003: Production log level ✅ DONE

- **File:** `src/main/resources/conf/base/pekko.conf:4`
- **Priority:** Critical | **Risk:** Low
- **Resolution:** Already set to `loglevel = "INFO"` with env var override `${?PEKKO_LOGLEVEL}` for debugging. TODO comment removed.

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

#### M-003: SNAP work-stealing for idle workers ✅ DONE

- **File:** `src/main/scala/.../sync/snap/actors/AccountRangeCoordinator.scala`
- **Priority:** Medium | **Risk:** Medium (sync-critical)
- **Resolution:** Added `maybeStealWork()` method. When a range completes and no pending tasks remain, finds the active task with the largest remaining keyspace (>2^248 threshold) and splits it at the midpoint. New task is enqueued for idle workers. Prevents 2/4 workers sitting idle during uneven account density on ETC mainnet. ~50 lines in `handleStoreAccountChunk` + new method.

#### M-004: Version-aware message decoding — DEFERRED

- **File:** `src/main/scala/.../network/rlpx/MessageCodec.scala:127`
- **Priority:** Medium | **Risk:** Low
- **Description:** `TODO [BACKLOG N-003]` — `remotePeer2PeerVersion` is available but not threaded to `fromBytes()`. Compression IS version-aware (line 58), but message format decoding is not. Only relevant if Fukuii adopts P2P v5+ (not currently planned). Would require changing `MessageDecoder` trait + all implementations for no immediate benefit.

#### M-005: Pass capability to handshake state machine — DEFERRED

- **File:** `src/main/scala/.../network/PeerActor.scala:136`
- **Priority:** Medium | **Risk:** Low
- **Description:** `TODO [BACKLOG N-004]` — Capability information from Hello message should be forwarded to `EtcHelloExchangeState`. Only relevant for P2P v5+. `EtcHelloExchangeState` already extracts capabilities from Hello — the issue is threading them to subsequent negotiation states.
- **Depends on:** M-004

#### M-026: Static node direct-dial with auto-reconnect ✅ DONE

- **Files:** `PeerManagerActor.scala`, `StaticNodesLoader.scala`, `NodeBuilder.scala`, `logback.xml`
- **Priority:** Medium | **Risk:** Medium (network-critical)
- **Description:** Fukuii treated `static-nodes.json` entries as Kademlia discovery seeds (wrong). All reference clients (geth, core-geth, Besu, Nethermind, Erigon) treat them as direct TCP dial targets with 15-second auto-reconnect, exempt from max outgoing peer limit. Local peers (Besu SNAP server, core-geth) never formed persistent connections.
- **Resolution:** Added `staticNodes: Set[URI]` to `PeerManagerActor` with direct-dial on `StartConnecting`, `CheckStaticPeers` every 15s (matches geth's `staticPeerCheckInterval`), exempt from `MaxOutgoingConnections`. Added `StaticNodesLoader.loadUrisFromDatadir()` returning parsed URIs. Wired through `NodeBuilder`. Promoted `StaticNodesLoader` and `PeerManagerActor` logback to INFO. Verified: ETC mainnet SNAP sync immediately connects to local Besu and downloads accounts at ~6K/sec.

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

#### M-024: debug_cpuProfile ✅ DONE

- **Priority:** Medium | **Risk:** Low
- **Resolution:** Implemented `debug_startCpuProfile` and `debug_stopCpuProfile` using JDK 21's built-in Java Flight Recorder (JFR) API. `startCpuProfile` accepts optional file path (defaults to `$TMPDIR/fukuii-cpu-profile.jfr`), uses `jdk.jfr.Configuration.getConfiguration("profile")` for standard CPU sampling. `stopCpuProfile` dumps and closes the recording, returns file path and size. Guards against double-start and stop-without-start. No external dependencies (JFR is built into JDK 21). 4 new tests in `DebugServiceSpec`. RPC count: 113 → 115.

#### M-025: README accuracy audit ✅ DONE

- **Priority:** Medium | **Risk:** Low
- **Resolution:** All 7 items fixed:
  - Test count updated to 2,690 (2 occurrences)
  - Added WebSocket subscriptions mention (`eth_subscribe`/`eth_unsubscribe`)
  - Added "Comprehensive RPC Coverage" section with full namespace/method breakdown table
  - Replaced "Full eth/web3/net API support" with "118 RPC methods across 12 namespaces"
  - Added `/buildinfo` endpoint to health documentation
  - Added rate limiting to Key Features

### 2.2 — Performance

#### M-007: JSON-RPC batch parsing optimization ✅ DONE

- **File:** `src/main/scala/.../jsonrpc/server/http/JsonRpcHttpServer.scala:97`
- **Priority:** Medium | **Risk:** Low
- **Resolution:** Changed batch request processing from sequential `traverse` to concurrent `parTraverse` (cats-effect parallel fibers). Each request in a batch now runs concurrently instead of waiting for the previous to complete. JSON parsing already handled by json4s entity unmarshaller (single parse).

#### M-008: EVM Stack array-backed optimization

- **File:** `src/main/scala/.../vm/Stack.scala`
- **Priority:** Medium | **Risk:** High (consensus-critical)
- **Description:** Current `Vector[UInt256]` provides O(log32 n) indexed access. Reference clients use array-backed stacks with O(1) access and pooled allocation. Potential throughput improvement for DUP/SWAP-heavy code.

### 2.3 — Testing

#### M-009: Complete EthereumTestsSpec block execution ✅ DONE

- **File:** `src/it/scala/.../ethtest/EthereumTestsSpec.scala`
- **Priority:** Medium | **Risk:** Low
- **Resolution:** Updated `runSingleTest` in base class to call `executeTest` (full block execution + post-state validation) instead of just setting up state. The execution infrastructure (`EthereumTestHelper.setupAndExecuteTest`, `BlockExecution`) was already built — only the base class entry point was bypassing it. Also fixed 2 pre-existing integration test compilation errors: `BlockImporterItSpec` (FetchStateNode pattern match arity) and `SNAPSyncIntegrationSpec` (AccountRangeProgress type change). All integration tests now compile.

#### M-010: Audit SlowTest-tagged tests ✅ DONE

- **Priority:** Medium | **Risk:** Low
- **Resolution:** Only 7 tests are SlowTest-tagged (not 141 as originally reported), across 3 files:
  - `EthashMinerSpec` (2 tests) — real PoW mining, correctly excluded (~30s each)
  - `StateSyncSpec` (1 test) — sync state, correctly excluded
  - `BlockBodiesStorageSpec` (2 tests) — large block body storage, correctly excluded
  - All are legitimately slow (real cryptographic work). No tests need to be moved to the standard suite.
  - Build.sbt line 76 correctly excludes `SlowTest` from default runs.

#### M-011: Investigate nightly test failures (#936) ✅ DONE

- **Priority:** Medium | **Risk:** Low
- **Resolution:** Triaged in two passes:
  1. **Bug fix:** `validateStorageRanges()` in `SNAPRequestTracker` used `collectFirst`+`flatten` which silently accepted non-monotonic storage slots when the violation was in any account except the first. Changed to `flatMap`+`headOption`. Security-relevant: malicious peer could bypass monotonicity validation.
  2. **Re-enabled tests:** Both previously-ignored unit tests now pass — `SNAPRequestTrackerAdaptiveSpec` (non-monotonic slots in second account) and `SnapSyncPipelineSpec` (bytecode end-to-end, needed `ProgressBytecodesDownloaded` expectation).
  3. **Remaining:** 1 ignored integration test (`ContractTest.scala`) — corrupted fixture data from legacy Mantis codebase (incorrect `codeHash` in account state). Not a code bug. Historical CI analysis (Dec 2025) showed 2 `RegularSyncSpec` timeout-sensitive flaky tests (3s→5s timeout fix recommended).
  - **Result:** 2,701 unit tests, 0 failed, 0 ignored.

#### M-012: Run full integration test suite

- **Priority:** Medium | **Risk:** Low
- **Description:** `sbt it:test` — 37 spec files, 1 ignored. Full run needed to establish baseline.
- **Depends on:** ~~C-001~~ (resolved)

#### M-013: Run full EVM test suite ✅ DONE

- **Priority:** Medium | **Risk:** Low
- **Resolution:** Pre-compiled `.bin`/`.abi` fixtures with solc 0.5.17 (matching `pragma ^0.5.1`), stored in `src/evmTest/resources/contracts/`. Removed runtime `solc` dependency, following reference client pattern. Fixed circe-generic-extras import (deprecated in Scala 3) with manual decoders. Fixed sbt `Evm` config source directory resolution. All 12 EVM tests pass.

#### M-014: Config validation on startup ✅ DONE

- **Priority:** Medium | **Risk:** Low
- **Resolution:** Created `ConfigValidator.scala` — pre-flight validation at startup before subsystem initialization. Checks:
  - SNAP sync without fast sync fallback (warn)
  - Zero or single pivot peer threshold (error/warn)
  - RPC port range, HTTP-WS port collision, 0.0.0.0 security warning
  - JVM heap size vs sync mode (SNAP needs 3+ GB)
  - Integrated into `StdNode.start()` as first call. Fatal issues throw `IllegalStateException`. 5 unit tests in `ConfigValidatorSpec`.

#### M-015: SNAP state freshness after reorg past pivot ✅ DONE

- **File:** `src/main/scala/.../sync/regular/BlockImporter.scala`, `BlockchainReader.scala`
- **Priority:** Medium | **Risk:** Medium (sync-critical)
- **Resolution:** Added pivot canonical validation at regular sync startup. When starting after SNAP sync, `BlockImporter.start()` compares the stored best block hash against the header at that block number. If they differ (reorg orphaned the pivot), logs a critical error with guidance to re-sync. Added `getBestBlockInfo()` and `isSnapSyncDone()` to `BlockchainReader` for access. Detection-only (not auto-recovery) — matches go-ethereum's approach of alerting rather than silently recovering from deep reorgs past the pivot.

#### M-021: SNAP storage slot monotonicity validation ✅ DONE

- **File:** `src/main/scala/.../sync/snap/SNAPRequestTracker.scala:205-229`
- **Priority:** Medium | **Risk:** Low
- **Resolution:** Already implemented in `validateStorageRanges()` — iterates each account's slots and verifies `compareUnsignedLexicographically(prev, curr) < 0`. Rejects responses with non-monotonic slot ordering.

### 2.4 — Network Upgrade Testing

#### M-016: MESS behavior verification at Olympia boundary ✅ DONE

- **Priority:** Medium | **Risk:** Medium
- **Resolution:** Added 4 tests to `MESScorerSpec` verifying MESS is inactive at Olympia blocks on both networks:
  - Mordor: MESS deactivates at 10,400,000, Olympia target ~16,001,337 → inactive
  - ETC: MESS deactivates at Spiral (19,250,000), any Olympia block → inactive
  - TD-only fork choice at Olympia boundary confirmed (no antigravity interference)
  - Hypothetical re-activation test: new MESSConfig with new window would be needed
  - Total MESS tests: 29 (was 25). All passing.

#### M-017: Operator upgrade signaling and warnings ✅ DONE

- **Priority:** Medium | **Risk:** Low
- **Resolution:** Already implemented in two components:
  - **Countdown logging:** `BlockImporter.logForkSignaling()` (lines 651-669) — WARNING at 100 blocks, INFO every 100/1000 blocks, activation confirmation at block 0. Follows go-ethereum pattern.
  - **Fork readiness RPC:** `fukuii_getForkSchedule` in `FukuiiService.scala` (lines 84-110) — returns all 16 configured forks with name, block number, and active status. Wired in `JsonRpcController`.

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

#### M-019: MCP server multi-LLM documentation and examples ✅ DONE

- **Priority:** Medium | **Risk:** Low
- **Resolution:** Updated `docs/MCP.md` "Integration with AI Assistants" section to be LLM-agnostic:
  - Added config examples for Claude Code (HTTP), Claude Desktop (stdio proxy), GitHub Copilot (`.github/copilot/mcp.json`), ChatGPT/Custom GPTs (custom action), and open-source LLMs (Ollama, LM Studio, vLLM)
  - Documented standard MCP methods accessible via any JSON-RPC 2.0 client
  - Removed Claude-only framing — now explicitly states "LLM-agnostic" and "no provider-specific code"
  - No code changes needed — documentation only

#### M-020: go-ethereum pre-merge PoW codebase review ✅ DONE

- **Priority:** Medium | **Risk:** Low
- **Resolution:** Comprehensive comparison of Fukuii's PoW consensus against go-ethereum v1.10.26 (last pre-merge release). Report at `docs/reports/POW-CODEBASE-REVIEW.md`. Key findings:
  - **0 critical gaps** — Ethash, difficulty, rewards, uncle validation, header validation all match go-ethereum patterns
  - **Fukuii ahead in:** BadBlockTracker (128 vs 10 entries), ECBP-1100 MESS, typed actor mining, fork boundary test coverage (36 tests), transaction type fork-gating (H-010)
  - **7 recommendations** (5 low, 1 medium, 1 added to High tier): nonce range partitioning, bandwidth-weighted peer selection, emergency PoW halt mechanism, memory-mapped DAGs, mining hashrate metrics, future block timestamp check (M-027), unclean shutdown recovery (H-017)
  - Recent H-010 through H-016 hardening pass closed all consensus safety gaps identified in the comparison

#### M-027: Future block timestamp validation (PoW review R-006) ✅ DONE

- **File:** `src/main/scala/.../consensus/validators/BlockHeaderValidatorSkeleton.scala`
- **Priority:** Medium | **Risk:** Low
- **Status:** DONE — Added `HeaderFutureTimestampError` and wall-clock check (`timestamp > now + 15s`) to `validateTimestamp()`. Matches go-ethereum's `allowedFutureBlockTimeSeconds`. Added distinct error type for diagnostics. Updated `EthashBlockHeaderValidatorSpec` to cover the new check. 52 validation tests pass.
- **Source:** PoW review R-006, `docs/reports/POW-CODEBASE-REVIEW.md`

#### H-017: Unclean shutdown recovery (PoW review R-007) ✅ DONE

- **File:** `AppStateStorage.scala`, `StdNode.scala`, `AppStateStorageSpec.scala`
- **Priority:** High | **Risk:** Medium (operational)
- **Status:** DONE — Implemented go-ethereum-style crash recovery:
  - `CleanShutdown` flag in AppStateStorage (set `true` on graceful shutdown, cleared on startup)
  - `LastSafeBlock` marker written every 64 blocks via `putBestBlockNumber()` (same batch, no extra I/O)
  - On startup: if unclean shutdown detected and `lastSafeBlock < bestBlock`, rewinds chain head
  - Graceful shutdown writes both `CleanShutdown=true` and `LastSafeBlock=bestBlock`
  - 4 new tests (16 total in AppStateStorageSpec): clean/unclean flag, 64-block marker interval, empty state
- **Source:** PoW review R-007, `docs/reports/POW-CODEBASE-REVIEW.md`

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

#### L-005: API documentation ✅ DONE

- **Priority:** Low | **Risk:** Low
- **Resolution:** Comprehensive API documentation already exists:
  - `docs/api/JSON_RPC_API_REFERENCE.md` (53KB) — full reference for all RPC methods with request/response examples
  - `docs/api/openapi.json` (201KB) — OpenAPI 3.0 specification for automated tooling
  - `docs/api/JSON_RPC_COVERAGE_ANALYSIS.md` — gap analysis against reference clients
  - `docs/api/INSOMNIA_RPC_ANALYSIS.md` + `INSOMNIA_WORKSPACE_GUIDE.md` — interactive testing
  - `docs/api/MCP_INTEGRATION_GUIDE.md` + `MCP_ANALYSIS_SUMMARY.md` — MCP server documentation
  - `docs/api/MAINTAINING_API_REFERENCE.md` — maintenance guide for keeping docs current

#### L-006: Operator guide ✅ DONE

- **Priority:** Low | **Risk:** Low
- **Resolution:** Comprehensive operator documentation already exists across 20+ guides:
  - `docs/runbooks/first-start.md` — initial setup and configuration
  - `docs/runbooks/node-configuration.md` — all config options with examples
  - `docs/runbooks/operating-modes.md` — SNAP/fast/regular sync modes
  - `docs/runbooks/snap-sync-user-guide.md` + `snap-sync-faq.md` + `snap-sync-performance-tuning.md`
  - `docs/runbooks/peering.md` + `network-management.md` — peer management and network ops
  - `docs/runbooks/security.md` — JWT auth, TLS, rate limiting, firewall
  - `docs/runbooks/backup-restore.md` + `disk-management.md` — data management
  - `docs/runbooks/enterprise-deployment.md` — production deployment patterns
  - `docs/runbooks/known-issues.md` — known issues and workarounds
  - `docs/runbooks/log-triage.md` — log analysis and troubleshooting
  - `docs/for-operators/index.md` + `static-nodes-configuration.md` — operator-focused index

#### L-007: Multi-threaded CPU mining with nonce range partitioning (PoW review R-001)

- **File:** `src/main/scala/.../consensus/pow/miners/EthashMiner.scala`
- **Priority:** Low | **Risk:** Low
- **Description:** go-ethereum partitions the nonce space across goroutines for parallel CPU mining. Fukuii's `EthashMiner.mineEthash()` uses single-threaded sequential nonce iteration. Partitioning across N threads would increase mining throughput linearly. Not relevant for nodes that don't mine, but improves competitiveness for solo/small-pool miners.
- **Fix:** Split nonce space into `Runtime.getRuntime.availableProcessors` ranges, spawn parallel `Future[MiningResult]` per range, race for first valid nonce.
- **Source:** PoW review R-001, `docs/reports/POW-CODEBASE-REVIEW.md`

#### L-008: Bandwidth-weighted peer selection (PoW review R-002)

- **File:** `src/main/scala/.../blockchain/sync/PeersClient.scala`
- **Priority:** Low | **Risk:** Low
- **Description:** go-ethereum weighs peer selection by both total difficulty AND download bandwidth. Fukuii selects peers primarily by TD via `ChainWeight`. Adding bandwidth tracking (bytes/second per peer over a rolling window) and weighting peer selection by `TD * bandwidth_score` would improve sync speed when multiple peers have similar TD but different throughput.
- **Fix:** Track `bytesReceived / elapsed` per peer in `PeerScoringManager`, expose as `bandwidthScore`. Weight `bestPeer()` selection by `chainWeight * bandwidthScore`.
- **Source:** PoW review R-002, `docs/reports/POW-CODEBASE-REVIEW.md`

#### L-009: Emergency PoW halt mechanism (PoW review R-003) ✅ DONE

- **File:** `src/main/scala/.../consensus/ConsensusImpl.scala`, `BlockchainConfig.scala`
- **Priority:** Low | **Risk:** Low
- **Resolution:** Added `emergencyTdCeiling: Option[BigInt]` to `BlockchainConfig` with HOCON key `emergency-td-ceiling`. `ConsensusImpl` rejects branches (both extend and reorg paths) that would push chain TD above ceiling. Warns when within 10% of ceiling. 2 new tests in `ConsensusImplSpec`.
- **Usage:** Set `-Dfukuii.blockchains.etc.emergency-td-ceiling=<value>` or add `emergency-td-ceiling = "..."` in chain config. Omit or leave unset for normal operation.
- **Source:** PoW review R-003, `docs/reports/POW-CODEBASE-REVIEW.md`

#### L-010: Memory-mapped DAG files (PoW review R-004)

- **File:** `src/main/scala/.../consensus/pow/miners/EthashDAGManager.scala`
- **Priority:** Low | **Risk:** Low
- **Description:** go-ethereum uses memory-mapped files for DAG storage, letting the OS manage paging. Fukuii loads the entire DAG into JVM arrays. Current ETC DAG is ~4GB and growing. Memory-mapped files would reduce JVM heap pressure and allow the OS to page DAG data intelligently.
- **Fix:** Replace `Array[Array[Int]]` with `MappedByteBuffer` from `java.nio.channels.FileChannel.map()`. DAG file format already compatible (sequential 64-byte hashes).
- **Source:** PoW review R-004, `docs/reports/POW-CODEBASE-REVIEW.md`

#### L-011: Mining hashrate Prometheus metrics (PoW review R-005) ✅ DONE

- **File:** `src/main/scala/.../consensus/mining/MiningMetrics.scala`, `src/main/scala/.../consensus/pow/miners/Miner.scala`
- **Priority:** Low | **Risk:** Low
- **Status:** DONE — Added 4 Prometheus metrics to `MiningMetrics`: `mining.hashrate.hps.gauge` (current H/s), `mining.hashes.tried.total` (cumulative nonce attempts), `mining.blocks.mined.success.counter`, `mining.blocks.mined.failure.counter`. Instrumented via `Miner.submitHashRate()` which is called after every mining round. 47 mining tests pass.
- **Source:** PoW review R-005, `docs/reports/POW-CODEBASE-REVIEW.md`

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

#### F-004: ECIP-1121 research (#975) ✅ DONE

- **Priority:** Future | **Risk:** Low
- **Status:** DONE — ECIP-1121 is fully implemented as part of the Olympia fork across all 3 clients (core-geth, Besu, Fukuii).

#### ~~F-005: ECIP-1120 research (#972)~~ REMOVED

- **Status:** REMOVED — Not pursuing this ECIP.

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
| Tier 0 (CRITICAL)   | 3      | SNAP PRs, healing fix (C-003 DONE, C-004 DONE)                                                                                                                                                                                               |
| Tier 1 (HIGH)       | 13     | debug expansion, fee market, access lists, state overrides, sync recovery, profiling, JWT, tx fork-gating, baseFee guards, SNAP finalization (H-014, H-015, H-016 DONE)                                                                    |
| Tier 2 (MEDIUM)     | 20     | debug profiling, log verbosity, SNAP work-stealing, testing push, perf, SNAP reorg freshness, hive Olympia, MCP multi-LLM docs, go-ethereum pre-merge PoW review (M-007, M-016, M-017, M-021 DONE)                                          |
| Tier 3 (LOW)        | 6      | networking polish, API docs, operator guide                                                                                                                                                                                                  |
| Tier 4 (FUTURE)     | 4      | GraphQL, Stratum, plugin system, GUI/releases (F-004 DONE via Olympia, F-005 removed)                                                                                                                                                        |
| **Total remaining** | **39** | Was 53, minus C-003, C-004, M-007, M-010, M-011, M-016, M-017, M-019, M-021, M-024, H-014, H-015, H-016. M-013 BLOCKED (needs solc).                                                                                                            |

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
| Prod log level (C-003)            | `pekko.conf:5`                                 | Already INFO + env var override         |
| Operator signaling (M-017)        | `BlockImporter:651-669`, `FukuiiService:84-110` | Countdown logging + getForkSchedule RPC |
| MESS at Olympia (M-016)           | `MESScorerSpec` +4 tests                       | Inactive at Olympia on both networks    |
| Batch RPC concurrency (M-007)     | `JsonRpcHttpServer:97`                         | `traverse` → `parTraverse`              |
| SlowTest audit (M-010)            | 3 files, 7 tests                               | All correctly excluded (real PoW/sync)  |
| MCP multi-LLM docs (M-019)        | `docs/MCP.md`                                  | LLM-agnostic config examples            |
| Fork boundary tests (H-014)       | `eaf830404`                                    | 12 tests: baseFee, RLP, gas dynamics    |
| Chain split detection (H-015)     | `d2d87b32a`                                    | 13 tests: ForkId + peer validation      |
| Adversarial resilience (H-016)   | `79d80c4e2`                                    | BadBlockTracker + 11 adversarial tests  |
| Nightly failures triage (M-011)  | `a4997acfa`                                    | validateStorageRanges bug fix + 2 tests re-enabled |
| JFR CPU profiling (M-024)       | `2e55b16e4`                                    | `debug_startCpuProfile`/`stopCpuProfile` + 4 tests |
| SNAP pivot reorg detection (M-015) | `BlockImporter.scala`, `BlockchainReader.scala` | Canonical validation at regular sync startup |
| EthereumTestsSpec block execution (M-009) | `EthereumTestsSpec.scala` + 2 IT compile fixes | `runSingleTest` now executes blocks via `BlockExecution` |
| SNAP finalization fixes (C-001/#1007)    | Already on `march-onward`                      | Deadlock fix, 2h timeout, TODO cleanup — all pre-existing |
| SNAP test coverage (C-001/#1008)         | Already on `march-onward`                      | 10 test files, 144 tests — all pre-existing |
| SNAP healing name collision (C-002/#1005) | `SNAPSyncController.scala:2044`                | Duplicate guard already implemented |
| SNAP work-stealing (M-003)               | `AccountRangeCoordinator.scala:maybeStealWork` | Split largest active range at midpoint when idle |
| CPU profiling via JFR (M-024)    | `DebugService.scala`                           | debug_startCpuProfile + debug_stopCpuProfile, 4 tests |

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
| `ExpiringMap.scala`             | 23   | `TODO: Make class thread safe`                | Resolved — ConcurrentHashMap. TODO removed. |
| `pekko.conf`                    | 4    | `TODO(production)` change loglevel to INFO    | Resolved — already INFO. TODO removed.      |
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
