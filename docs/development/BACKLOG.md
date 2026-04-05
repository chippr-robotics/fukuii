# Fukuii Production-Readiness Backlog

Comprehensive inventory of remaining work, verified against the codebase and compared to reference clients (go-ethereum, nethermind, core-geth, Besu, Erigon).

**Branch:** `march-onward` (172 commits ahead of upstream main, at `668c04c30`)
**Test baseline:** 2,738 unit tests pass, 0 failed, 0 ignored; 12/12 EVM consensus; 8/8 integration (24 suites, 16 empty — need ethereum/tests fixtures)
**RPC methods:** 135 standard + 8 MCP protocol = 143 total, all wired to `JsonRpcController`, zero orphaned
**Last audited:** 2026-04-04

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

### 2.1b — Customer Segment Gaps (2026-03-27 audit)

These items address specific requirements identified by analyzing the three main client user segments: block explorers (Blockscout), centralized exchanges, and mining pools.

#### M-029: Native callTracer + prestateTracer for debug_trace* (Blockscout geth-mode) ✅ DONE

- **Priority:** Medium | **Risk:** Medium
- **Status:** DONE (`1e4c807f9`) — Implemented native `callTracer` and `prestateTracer` matching go-ethereum's `eth/tracers/native/call.go` and `prestate.go` output format exactly. 7 new/modified files, 15 new unit tests (2,713 total pass).
- **What was implemented:**
  1. `ExecutionTracer` trait — pluggable tracer interface with `onStep`, `onTxStart`/`onTxEnd`, `onCallEnter`/`onCallExit`, `getResult`
  2. `CallTracer` — nested call tree with gas as decimal JInt (geth-compatible), revert reason parsing, `onlyTopCall` config
  3. `PrestateTracer` — pre-tx state capture, default mode + diffMode `{pre, post}` output, storage key tracking via `onStep`
  4. `StructLogTracer` adapted to `ExecutionTracer` trait
  5. `DebugTracingService` tracer factory — dispatches based on `TraceConfig.tracer` field
  6. `DebugTracingJsonMethodsImplicits` — `tracer`/`tracerConfig` JSON parsing, polymorphic response encoding via `nativeResult: Option[JValue]`
  7. Hooks in `CallOp.exec()` and `CreateOp.exec()` (OpCode.scala) for `onCallEnter`/`onCallExit`
- **Coverage:** All `debug_trace*` methods (traceTransaction, traceCall, traceBlock, traceBlockByHash, traceBlockByNumber) use the same tracer factory
- **Depends on:** H-001

#### M-030: Blockscout compatibility testing

- **Priority:** Medium | **Risk:** Low
- **Description:** Verify Fukuii works as a Blockscout backend. Blockscout configures client type via `ETHEREUM_JSONRPC_VARIANT` env var and normalizes all trace output to Parity-compatible format. Fukuii implements `trace_replayBlockTransactions`, `trace_block`, and `trace_transaction` — this may already work with Blockscout's `erigon` variant (which expects Parity-style traces).
- **Test plan:**
  1. Run Blockscout against synced Fukuii node with `ETHEREUM_JSONRPC_VARIANT=erigon`
  2. Verify block indexing completes (RPC calls: `eth_getBlockByNumber`, `eth_getBlockReceipts`, `trace_replayBlockTransactions`)
  3. Verify `eth_subscribe("newHeads")` WebSocket delivers new block notifications
  4. Verify transaction detail pages render traces correctly
  5. Verify token transfer indexing works (depends on `eth_getLogs` + receipt parsing)
  6. Document any missing methods or format mismatches
- **Success criteria:** Blockscout indexes Mordor chain head to head with zero RPC errors
- **Depends on:** Synced Fukuii node (Mordor or ETC)

#### M-031: Archive mode verification

- **Priority:** Medium | **Risk:** Medium
- **Description:** Blockscout and some exchange integrations require archive node access — the ability to query historical state at any block height (`eth_getBalance`, `eth_getCode`, `eth_getStorageAt`, `eth_call` with historical block parameter). Fukuii stores state in RocksDB via Merkle Patricia Trie, but the pruning behavior and historical state retention need verification. After SNAP sync, only state at/after the pivot block is available — this is expected and matches all reference clients. For full archive, a node must sync from genesis via regular sync.
- **Test plan:**
  1. Sync Fukuii via regular sync (genesis) on Mordor
  2. Query `eth_getBalance` at blocks 1, 1000, 100000, and head — verify all return correct values
  3. Query `eth_call` with historical `blockNumber` parameter — verify execution against historical state
  4. Verify `trace_replayBlockTransactions` works on historical blocks (not just recent)
  5. Document any state pruning defaults and configuration options
- **Success criteria:** All historical state queries return correct results on a genesis-synced node

#### M-032: Production benchmarking (exchange/pool readiness)

- **Priority:** Medium | **Risk:** Low
- **Description:** Centralized exchanges require <100ms RPC latency and 99.9% uptime. Mining pools require <100ms `newHeads` WebSocket notification latency. Neither segment will adopt a client without published benchmark data showing it meets their SLA requirements.
- **Test plan:**
  1. Benchmark core RPC methods under load: `eth_getBlockByNumber`, `eth_getBalance`, `eth_call`, `eth_getLogs`, `eth_getTransactionReceipt` — measure p50/p95/p99 latency
  2. Benchmark WebSocket `eth_subscribe("newHeads")` notification latency (time from block import to subscriber delivery)
  3. Benchmark under concurrent load (50, 100, 500 concurrent RPC connections)
  4. Measure memory and CPU profile during sustained RPC serving
  5. Compare against core-geth and Besu on same hardware
  6. Publish results in `docs/reports/PRODUCTION-BENCHMARKS.md`
- **Success criteria:** p99 latency <100ms for read-only RPCs on synced node; WebSocket notification latency <200ms
- **Tools:** `wrk`, `vegeta`, or custom JMH benchmark harness

### 2.1c — Institutional Protocol Prerequisites (2026-03-27 audit)

These items address code-level gaps identified by analyzing cross-chain bridge protocols (CCIP, CCTP, LayerZero, Axelar, Wormhole), oracle providers (Chainlink, RedStone, Tellor, Chronicle, Pyth, API3), and DApp infrastructure requirements. Small changes that unblock multiple protocol integrations.

#### M-034: Support `finalized` and `safe` block tags (EIP-1898 extension) ✅ DONE

- **Priority:** Medium | **Risk:** Low
- **Resolution:** Already implemented. `BlockParam.Safe` and `BlockParam.Finalized` defined in `ResolveBlock.scala`. `extractBlockParam` in `JsonMethodsImplicits.scala:138-139` handles `"safe"` and `"finalized"` strings. `resolveNumber` maps `Safe` → `latest - safe-depth` (default 15) and `Finalized` → `latest - finalized-depth` (default 120). Configurable via `fukuii.network.rpc.safe-depth` and `fukuii.network.rpc.finalized-depth`. `getBlockAtDepth` in `ResolveBlock` resolves to concrete blocks. Fukuii is the first ETC client with bridge-compatible finality tags.

#### M-035: Batch RPC compatibility with rate limiting ✅ DONE (was not broken)

- **Priority:** Medium | **Risk:** Low
- **Resolution:** Verified code at `JsonRpcHttpServer.scala:93-100` — batch requests (`entity(as[Seq[JsonRpcRequest]])`) pass through the same `rateLimit` directive as single requests. No blanket rejection. The rate limiter applies per-IP rate limiting to the batch as a whole (one HTTP request = one rate limit check). Batch processing via `parTraverse` works correctly with rate limiting enabled. The original backlog description was based on an older code version.

#### M-036: `eth_getLogs` block range limit ✅ DONE

- **Priority:** Medium | **Risk:** Low
- **Resolution:** Already implemented. `FilterManager.scala:43-46` defines `maxLogRange` with configurable `network.rpc.max-log-range` (default 10,000 blocks). Line 78 checks `to - from > maxLogRange` and returns `JsonRpcError.InvalidParams("Log query range N exceeds maximum (10000 blocks)")`. Matches Infura/Alchemy behavior. Configurable via `fukuii.network.rpc.max-log-range`.

### Wallet Readiness Assessment (2026-03-27)

Research confirmed all major wallets are RPC-agnostic — no Fukuii client changes needed:

| Wallet | ETC Support | Integration Model |
|--------|-------------|-------------------|
| MetaMask | Custom RPC (EIP-3085) | User adds Fukuii endpoint manually |
| Rabby | Native (141+ chains) | Uses trace_* for tx simulation — Fukuii has this |
| Trust Wallet | Native | Multi-chain, standard RPC |
| Coinbase Wallet | Custom RPC | User configures endpoint |
| Ledger Live | Native | Built-in ETC app on device |
| Frame | Direct node | Perfect solo-operator use case |
| Safe (Gnosis) | Not deployed | Needs tx service infrastructure (F-010) |
| WalletConnect v2 | Chain-agnostic | Supports `eip155:61` (ETC mainnet) |

EIP-3085 (`wallet_addEthereumChain`) and EIP-3326 (`wallet_switchEthereumChain`) both support ETC natively. DApps can programmatically add ETC with `chainId: '0x3d'`.

### 2.2 — Performance

#### M-007: JSON-RPC batch parsing optimization ✅ DONE

- **File:** `src/main/scala/.../jsonrpc/server/http/JsonRpcHttpServer.scala:97`
- **Priority:** Medium | **Risk:** Low
- **Resolution:** Changed batch request processing from sequential `traverse` to concurrent `parTraverse` (cats-effect parallel fibers). Each request in a batch now runs concurrently instead of waiting for the previous to complete. JSON parsing already handled by json4s entity unmarshaller (single parse).

#### M-008: EVM Stack array-backed optimization ✅ DONE

- **File:** `src/main/scala/.../vm/Stack.scala`
- **Priority:** Medium | **Risk:** High (consensus-critical)
- **Resolution:** Full rewrite from immutable `Vector[UInt256]` to mutable `Array[UInt256]` + `top: Int` pointer. Matches all reference clients (geth, Besu, Nethermind, core-geth, Erigon). New API: `pop(): UInt256`, `push(): Unit`, `peek(i): UInt256`, `set(i, value): Unit` (in-place mutation). All `varGas()` methods converted to `peek()`/`peekN()` (never pop). All `exec()` methods use in-place mutation patterns. `CallOp.getParams()` split into `peekParams()` (varGas) + `popParams()` (exec). 2,713/2,715 tests pass (2 pre-existing unrelated failures). All 540 VM/Stack/OpCode tests pass.

### 2.3 — Testing

#### M-028: DnsDiscoverySpec fails in unit test suite ✅ DONE

- **File:** `build.sbt` (line 75-78)
- **Priority:** Low | **Risk:** None (test-only)
- **Resolution:** Added `IntegrationTest` to `Test/testOptions` exclusion alongside `SlowTest`. Network-dependent DNS tests (live queries to `all.classic.blockd.info`, `all.mordor.blockd.info`) now excluded from `sbt test`. Still run via `sbt testStandard` and `sbt IntegrationTest/test`. Result: 2,706/2,706 unit tests pass.

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

#### M-012: Run full integration test suite ✅ DONE

- **Priority:** Medium | **Risk:** Low
- **Resolution:** `sbt IntegrationTest/test` — 24 suites loaded, 8 tests pass, 0 failures. 16 suites are empty (depend on external `ethereum/tests` fixtures not present locally — these run in CI with the full fixture repo). Key suites verified: MerklePatriciaTree, RocksDbDataSource, AppLauncher (3 configs). All passing.

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

#### M-033: MESS reactivation testing for Olympia hardfork defense

- **Priority:** Medium | **Risk:** High (network-critical)
- **Description:** ECBP-1100 MESS (Modified Exponential Subjective Scoring) is currently deactivated at Spiral (block 19,250,000 on ETC). MESS should be **re-enabled by default in Olympia** to protect the network from 51% attacks and to address centralized exchange confidence concerns around the fork.
- **Historical context (2020 attacks):** ETC suffered **3-4 separate 51% attacks in 2020**, resulting in millions of dollars in double-spend losses. These attacks directly led to two defensive ECIPs:
  - **ECIP-1099 (Thanos/ETChash):** Reduced DAG size to keep GPU miners viable, activated Nov 2020 at block 11,700,000
  - **ECIP-1100 (MESS):** Antigravity polynomial making deep reorgs exponentially more expensive, activated Oct 2020 at block 11,380,000
  - MESS was subsequently **deactivated at Spiral** (ECBP-1110) — this was a mistake that should be reversed
- **Current threat indicators (2026-03-27, sources: [2miners ETC hashrate](https://2miners.com/etc-network-hashrate), [BitInfoCharts ETC hashrate vs price](https://bitinfocharts.com/comparison/hashrate-price-etc.html#alltime)):**
  - **Week view:** Network at 175 TH/s, oscillating between ~150-220 TH/s — a **~70 TH/s daily sawtooth cycle (40% of total hashrate)** appearing and disappearing in regular patterns. Not organic mining behavior; indicates programmatic hashrate rental cycling on/off to game the difficulty adjustment.
  - **Year view:** Reveals a longer pattern. Apr-Sep 2025: ~250-300 TH/s with the same sawtooth cycling. **Oct 2025: ~100 TH/s dropped off almost overnight** (300→200 TH/s), suggesting a single large entity switched off. The 70 TH/s sawtooth cycling continues at the lower baseline. This means ~100 TH/s of rentable ETChash hashrate exists in the market that was previously on the ETC network and could return at any time — for honest mining or for an attack.
  - **All-time view — post-Merge hashrate profile (BitInfoCharts ETH vs ETC overlay):** ETC was ~20-50 TH/s from 2016 to Sep 2022. ETH peaked at ~1.1 PH/s (1,100 TH/s) before the Merge. At the Merge (Sep 2022), ETH hashrate drops to zero and ETC spikes from ~50 TH/s to ~300 TH/s — absorbing ~250 TH/s of displaced ex-ETH GPU miners (~25% of ETH's peak hashrate). **ETC's current hashrate is 75%+ mercenary post-Merge miners** with no ideological commitment to the chain. They mine whatever is most profitable and their hashrate is available for rent on NiceHash/MiningRigRentals at any time. The remaining ~750 TH/s of ex-ETH hashrate went to other coins or shut down but could return to ETC at any time if profitable. The original MESS parameters were calibrated for 3 TH/s network vs 66 TH/s attacker (2020). Post-Merge, the raw numbers are higher but the dynamic is arguably *worse* — nearly all the hashrate is rentable, not committed, and there is a massive pool of idle ethash-compatible hashrate in the broader market. MESS parameter recalibration for the post-Merge hashrate landscape should be evaluated as part of this work item.
  - **Implication for MESS:** At 175 TH/s network hashrate, an attacker could rent the 100 TH/s that left in October. Without MESS, a deep reorg costs only hashrate rental × blocks (linear). With MESS at 31x peak multiplier, deep reorgs become economically prohibitive. Re-enabling MESS is not theoretical — the hashrate to attack already exists and was recently on the network.
- **Olympia fork defense:** Re-enabling MESS addresses centralized exchange concerns about a minority chain attacking the main Olympia branch:
  - The minority chain (anti-development/ossify faction) has no significant hashrate and no economic backing
  - Olympia is the primary branch — it includes ETC Cooperative, Grayscale's ETC trust products, and alignment with all material stakeholders who need a modern, maintained client
  - ETC needs active development, not ossification. Olympia absorbs all meaningful hashrate because the entire institutional ecosystem (exchanges, funds, infrastructure) follows the maintained fork
  - MESS makes it economically impossible for a minority chain to reorg the Olympia branch, giving exchanges confidence to credit deposits without extended confirmation delays
- **Current state:** MESS is fully implemented in Fukuii (`MESSScorer`, `ChainDifficultyHelper`). M-016 verified MESS is inactive at Olympia blocks. 29 MESS tests pass. The question is no longer *whether* to reactivate — it's ensuring all 3 clients handle reactivation correctly.
- **Adversarial scenario:** A minority chain split (no significant hashrate, <18 participants with no mining hardware) attempts to:
  1. Mine a competing chain at the Olympia fork block using pre-fork rules
  2. Accumulate enough TD to trigger reorgs on upgraded nodes that accepted the minority chain's blocks pre-fork
  3. Confuse network topology by running nodes that reject Olympia blocks and relay pre-fork blocks
- **Test plan:**
  1. **Reactivation mechanics:** Verify MESS can be re-enabled via new `MESSConfig` window (activation block, deactivation block) in chain config. Test that adding a new MESS window after Spiral works alongside the existing Mordor/ETC windows.
  2. **Antigravity at fork boundary:** Simulate a minority chain that forks at Olympia block N with pre-fork rules. Measure the antigravity penalty applied to competing chains of length 1, 10, 100, 1000 blocks. Verify upgraded nodes reject the minority chain even if it temporarily has higher raw TD.
  3. **Fork choice interaction with EIP-1559:** Post-Olympia blocks have baseFee. Pre-fork minority blocks do not. Verify MESS scoring handles the mixed-era comparison correctly (TD-only fork choice, no baseFee influence on MESS penalty).
  4. **Peer handling:** Verify that peers serving minority chain blocks (pre-fork headers at post-fork heights) are identified and handled — ForkId mismatch should trigger disconnect before MESS even applies. Test the interaction between ForkId validation (H-015) and MESS scoring.
  5. **Configuration proposal:** Document recommended MESS reactivation window for Olympia (e.g., activate at N-1000, deactivate at N+10000) and the config entries needed for all 3 clients.
- **Cross-client:** Core-geth has MESS natively. Besu does NOT implement MESS — Besu nodes would rely on ForkId filtering only. Document this asymmetry and its implications for network defense.
- **Depends on:** M-016 (MESS verification), H-015 (chain split detection), H-014 (fork boundary tests)

#### M-037: Dynamic confirmation recommendations RPC (MESS + attack cost model)

- **Priority:** Medium | **Risk:** Medium
- **Description:** No EVM client provides transaction-aware confirmation recommendations. Every centralized exchange hardcodes a static confirmation count (e.g., "24 confirmations for ETC") regardless of transaction value — a $1 transfer and a $1M deposit get the same wait time. With MESS reactivated, Fukuii can compute the actual cost to attack (reorg) at any given depth using the antigravity polynomial, current network difficulty, and ETChash hashrate market price. This creates a new RPC method that returns dynamic confirmation recommendations based on transaction value: "how many confirmations until the cost to reorg exceeds the transaction value?"
- **Concept:** For a given transaction value V, compute the number of confirmations N where:
  `cost_to_reorg(N) = hashrate_rental_cost(N_blocks) × MESS_antigravity_multiplier(N) > V`
  - `hashrate_rental_cost` = blocks × block_reward × (1 + rental_premium), derived from current difficulty + ETChash hashrate market rates
  - `MESS_antigravity_multiplier` = the cubic polynomial from `ArtificialFinality.scala` (1x at 0s, rising to 31x at ~9000s)
  - With MESS active, deep reorgs cost exponentially more than without — a 100-block reorg costs ~8x more than 100× the single-block cost
  - Without MESS, cost scales linearly with depth (just hashrate rental)
- **Existing infrastructure:**
  - `ArtificialFinality.scala` — antigravity polynomial (cubic, 1x→31x multiplier over ~9000s)
  - `ChainDifficultyHelper` — difficulty calculation
  - `EthMiningService` — current hashrate via `eth_hashrate`
  - `MiningMetrics` — Prometheus hashrate gauge
- **Proposed RPC method:** `fukuii_getConfirmationRecommendation`
  - **Input:** `{ value: "0x..." }` (transaction value in wei) + optional `{ hashratePremium: 1.5 }` (rental cost multiplier, default 1.5x)
  - **Output:** `{ confirmations: 6, estimatedSeconds: 90, costToAttack: "0x...", messMultiplier: 3.2, withoutMess: { confirmations: 24, estimatedSeconds: 360 } }`
  - Returns both MESS-protected and unprotected recommendations for comparison
  - Dynamically adjusts as difficulty changes (recalculated per-call against current chain state)
- **MESS cost-to-attack table:** Recreate the original ECBP-1100 analysis table showing cost to attack at various reorg depths with and without MESS. Sources identified:
  - **ECIP-1100 spec** (local: `/ECIPs/_specs/ecip-1100.md`): Polynomial implementation, parameter reasoning, Go code from core-geth. Constants: `xcap=25132` (floor(8000π)), `ampl=15`, `denominator=128`, `height=3840`. Peak multiplier 31x at ~7 hours.
  - **meowsbits/51-percent-docs** (Isaac Ardis, original MESS author): Cost-to-attack tables, Messnet simulation results. Historical: Aug 2020 attack cost ~$192K for 3,594-block reorg without MESS; with MESS 31x multiplier: ~$5.9M required (net loss for attacker). Messnet finality times: $100K tx = 2.53hr, $1M tx = 8.15hr.
  - **Polynomial:** `y = 128 + (3x² - 2x³/xcap) × height / xcap²` where height=128×15×2=3840. Integer-only arithmetic. Multiplier ramps 1x→31x over ~9000s. 31x ceiling based on 22x worst-case hashrate ratio (66TH attacker vs 3TH ETC) with 50% safety margin.
  - **Safety mechanisms** (from core-geth impl in ECIP-1100): MESS disabled during sync, requires MinimumSyncPeers (5), disabled if head stale (>30×13s). Fukuii must replicate these guards.
- **Exchange value proposition:** Exchanges could query this endpoint per-deposit to dynamically set confirmation requirements. A $1 ETC deposit could confirm in 1-2 blocks (~15-30s). A $100K deposit might need 30+ blocks (~7.5 min). This replaces the current "one size fits all" 24-block static policy that every exchange uses.
- **Differentiator:** No EVM client (geth, Besu, Nethermind, Erigon, core-geth) offers this. Unique to Fukuii. Directly monetizable for exchange partnerships — reduces deposit latency for small transactions while maintaining security for large ones.
- **Research items:**
  1. ~~Locate original ECBP-1100 cost-to-attack table~~ — **DONE.** ECIP-1100 spec + meowsbits/51-percent-docs (see sources above)
  2. Model ETChash hashrate rental market (NiceHash ETC rates, historical attack costs from 2020 51% attacks — baseline: $192K for 3,594-block reorg at Aug 2020 hashrate)
  3. Define the cost function: `hashrate_cost_per_second × reorg_depth_seconds × MESS_multiplier(depth)` — polynomial constants now known (see sources above)
  4. Validate against historical ETC attacks (2020: 3-4 separate 51% attacks including 3,594-block and 7,000+ block reorgs) — would MESS + dynamic confirms have caught them? These attacks directly led to ECIP-1099 and ECIP-1100.
  5. Determine if the `hashratePremium` parameter should be configurable per-exchange or use a network-wide default
  6. Port the Fukuii `ArtificialFinality.scala` antigravity polynomial into the cost model (already implemented in `consensus/mess/`)
  7. Evaluate MESS parameter recalibration for post-Merge hashrate landscape: original xcap=25132 and 31x ceiling were set for 3 TH/s network (2020). Current network is 175 TH/s but ~75% mercenary/rentable. The multiplier ceiling may need adjustment based on the ratio of rentable-to-committed hashrate rather than absolute values.
  8. Factor in post-Merge hashrate/price decorrelation: BitInfoCharts ETC hashrate vs price shows complete decorrelation after Sep 2022. Pre-Merge, hashrate tracked price (miners follow profitability). Post-Merge, hashrate jumped 6x (50→300 TH/s) while price was flat ($25), and remains at 198 TH/s with price at $8.10. Honest mining revenue per TH/s is near break-even — the opportunity cost of redirecting hashrate from honest mining to attacking is minimal. The `hashratePremium` parameter in the cost model should account for this: when mining margins are thin, attack hashrate is cheaper to acquire because miners lose little by switching. Consider using `ETC_price / mining_cost_per_TH` as a profitability-aware input to the rental cost estimate.
- **Depends on:** M-033 (MESS reactivation testing), M-032 (production benchmarking)

### 2.1d — MEV & Transaction Pool Modernization (2026-03-27 audit)

Research into MEV (Flashbots, CoW Protocol), decentralized RPC protocols (DRPC, Pocket, Lava, Ankr), and on-chain data indexing (The Graph, Parsiq, Envio) revealed three client-level code gaps in Fukuii's transaction pool and block building that affect correctness and mining competitiveness. Strategic product-level findings (decentralized mining pools, indexer infrastructure, DRPC provider networks) are documented separately in `FUTURE-PRODUCTS.md` (gitignored).

#### M-038: Transaction pool fee-market ordering (EIP-1559 effective tip sorting)

- **Priority:** Medium | **Risk:** Medium (mining-revenue-critical)
- **Description:** Post-Olympia, Fukuii builds suboptimal blocks because transaction ordering does not account for EIP-1559 fee dynamics. Two problems:
  1. **Pool eviction:** `PendingTransactionsManager.scala` uses a Guava `Cache<ByteString, PendingTransaction>` with FIFO/LRU eviction (`expireAfterWrite` + `maximumSize`). When the pool is full (1,000 txs), high-tip transactions that arrived earlier are evicted in favor of low-tip transactions that arrived later. Every other EVM client (geth, Besu, Nethermind, Erigon) uses a price-ordered heap so the pool always retains the highest-paying transactions.
  2. **Block building:** `BlockGeneratorSkeleton.prepareTransactions()` at line 141 sorts by `-gasPrice` — correct for legacy (Type 0) and access-list (Type 1) transactions but **wrong for EIP-1559 (Type 2)**. Type 2 txs have `maxFeePerGas` and `maxPriorityFeePerGas` — the miner's revenue is the *effective tip* (`min(maxFeePerGas - baseFee, maxPriorityFeePerGas)`), not `gasPrice`. The `Transaction.effectiveGasPrice(tx, baseFee)` method at `Transaction.scala:53` computes the correct value but `prepareTransactions()` does not use it.
- **Impact:** Miners running Fukuii earn less per block than miners running geth/core-geth because high-tip transactions may be evicted from the pool or sorted behind low-tip EIP-1559 transactions.
- **Files:** `BlockGeneratorSkeleton.scala:126-166` (`prepareTransactions()`), `PendingTransactionsManager.scala:90-101` (Guava cache), `Transaction.scala:53-62` (`effectiveGasPrice()` — exists but unused in block building)
- **Reference clients:** geth `txsByPriceAndNonce` (effective tip heap), Besu `BaseFeePendingTransactionsSorter`, Nethermind `CompareTxByGasPrice`
- **Depends on:** None (standalone, EIP-1559 infrastructure already complete via H-002/H-003)

#### M-039: Transaction pool nonce queuing

- **Priority:** Medium | **Risk:** Medium (correctness)
- **Description:** `txpool_status` always returns `queued: 0` (hardcoded at `TxPoolService.scala:97`). Fukuii has no concept of queued (future-nonce) transactions. When a user submits nonce N+1 before nonce N arrives, the transaction is either rejected or placed in the pending pool where it cannot execute. Every reference client maintains a separate "queued" pool for future-nonce transactions and automatically promotes them to "pending" when the gap fills.
- **Why it matters:** Wallet software (MetaMask, Rabby) and DApp frontends routinely send multiple transactions in rapid succession with sequential nonces. If nonce N+1 arrives before nonce N (common with network latency), Fukuii drops it. The user must manually resubmit.
- **Files:** `PendingTransactionsManager.scala` (add queued storage), `TxPoolService.scala:97` (`queued = 0` hardcoded)
- **Reference clients:** geth `TxPool.queue` + `promoteExecutables()`, Besu `PendingTransactions` pending+ready buckets, Nethermind `_pendingTxs` + `_futureTxs`
- **Depends on:** None

#### M-040: Private transaction submission RPC (`fukuii_sendPrivateTransaction`)

- **Priority:** Medium | **Risk:** Low
- **Description:** Add `fukuii_sendPrivateTransaction` RPC method that accepts a signed transaction and adds it to the local pending pool WITHOUT broadcasting to P2P peers. The transaction enters the miner's block template directly and is only revealed when included in a mined block. This is the minimal viable MEV protection feature.
- **MEV context:** On ETC, sandwich bots monitor the public mempool via `eth_subscribe("newPendingTransactions")` or `txpool_content`. A private transaction bypasses both — never visible to peers, only to the local miner. Eliminates frontrunning and sandwich attacks for users who submit to a trusted Fukuii node. This is the local-only equivalent of Flashbots Protect (PoS relay service) and MEV Blocker (CoW DAO) — no relay infrastructure needed.
- **How it differs from `eth_sendRawTransaction`:** Current `sendRawTransaction` at `EthTxService.scala:206` calls `AddOrOverrideTransaction` which triggers `NotifyPeers` at line 162-166 — immediate P2P broadcast. The private variant skips `NotifyPeers`.
- **Namespace:** `fukuii_*` (like Nethermind's `nethermind_*`, Besu's `priv_*`, geth's `mev_*`)
- **Security:** Must be JWT-protected (H-009 already implemented). Without JWT, any peer could use the node as a free private mempool relay.
- **Files:** `EthTxService.scala:200-214` (`sendRawTransaction()` as template), `PendingTransactionsManager.scala:168-185` (`NotifyPeers` — the P2P broadcast path to skip), `JsonRpcController.scala` (method registration)
- **Depends on:** None

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

#### H-018: Fast sync pivot selection deadlock — bounded retries + single-peer pivot ✅ DONE

- **Files:** `sync.conf`, `Config.scala`, `FastSync.scala`, `SyncController.scala`
- **Priority:** High | **Risk:** High (sync-critical)
- **Status:** DONE — Fixed infinite retry loop in fast sync pivot selection:
  - Lowered `min-peers-to-choose-pivot-block` from 3 to 1 (matches geth/Besu behavior)
  - Lowered `peers-to-choose-pivot-block-margin` from 3 to 1
  - Added `max-fast-sync-outer-pivot-retries = 10` config with bounded outer retry loop
  - `PivotRetriesExhausted` message signals SyncController to try SNAP sync or escape to regular sync
  - Existing `checkSnapFastEscapeHatch()` (3 transitions) still applies as final safety net
- **Root cause:** After SNAP→Fast fallback, PivotBlockSelector required 3 peers for consensus but only 1-2 were available. Inner selector failed after 20 attempts → FastSync created new selector → repeat forever at 5-min intervals with no upper bound.
- **Reference clients:** geth and Besu both use 1 peer for pivot selection. Besu has explicit 50-retry budget.

---

## Tier 3: LOW — Polish

#### L-001: Deprecate pre-EIP-8 handshake support — WONTFIX

- **File:** `src/main/scala/.../network/rlpx/RLPxConnectionHandler.scala:298`
- **Priority:** Low | **Risk:** Medium (network compatibility)
- **Resolution:** WONTFIX — all 5 reference clients (go-ethereum, core-geth, Erigon, Nethermind, Besu) retain pre-EIP-8 handshake fallback. Fukuii already matches this pattern (dual-path: tries pre-EIP-8 first, falls back to EIP-8). Follows Postel's Law ("be liberal in what you accept"). No change needed.

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

#### L-007: Multi-threaded CPU mining with nonce range partitioning (PoW review R-001) ✅ DONE

- **File:** `src/main/scala/.../consensus/pow/miners/EthashMiner.scala`
- **Priority:** Low | **Risk:** Low
- **Resolution:** Added parallel mining with `mineEthashParallel()`. Dispatcher method checks `availableProcessors - 2` (reserves cores for sync/RPC); if >1 thread available, splits nonce space across N `Future` workers with `AtomicBoolean` early cancellation. Falls back to single-threaded `mineEthashSingleThread()` on low-core machines. Uses IORuntime compute pool, `Await.result` with 5-minute timeout.
- **Source:** PoW review R-001, `docs/reports/POW-CODEBASE-REVIEW.md`

#### L-008: Bandwidth-weighted peer selection (PoW review R-002) ✅ DONE

- **File:** `src/main/scala/.../blockchain/sync/PeersClient.scala`, `SyncController.scala`
- **Priority:** Low | **Risk:** Low
- **Resolution:** Connected the existing `PeerScoringManager` (was built but never wired) to `PeersClient`. Shared scoring manager instance created in `SyncController`, passed to all `PeersClient` instances. `bestPeer()` now uses quality scores as tiebreaker among peers with equal chain weight. Response success/failure events from `PeerRequestHandler` feed into scoring (latency tracking on success, timeout recording on failure).
- **Source:** PoW review R-002, `docs/reports/POW-CODEBASE-REVIEW.md`

#### L-009: Emergency PoW halt mechanism (PoW review R-003) ✅ DONE

- **File:** `src/main/scala/.../consensus/ConsensusImpl.scala`, `BlockchainConfig.scala`
- **Priority:** Low | **Risk:** Low
- **Resolution:** Added `emergencyTdCeiling: Option[BigInt]` to `BlockchainConfig` with HOCON key `emergency-td-ceiling`. `ConsensusImpl` rejects branches (both extend and reorg paths) that would push chain TD above ceiling. Warns when within 10% of ceiling. 2 new tests in `ConsensusImplSpec`.
- **Usage:** Set `-Dfukuii.blockchains.etc.emergency-td-ceiling=<value>` or add `emergency-td-ceiling = "..."` in chain config. Omit or leave unset for normal operation.
- **Source:** PoW review R-003, `docs/reports/POW-CODEBASE-REVIEW.md`

#### L-010: Memory-mapped DAG files (PoW review R-004) ✅ DONE

- **File:** `src/main/scala/.../consensus/pow/miners/EthashDAGManager.scala`, `EthashMiner.scala`
- **Priority:** Low | **Risk:** Low
- **Resolution:** Replaced in-heap `Array[Array[Int]]` DAG (~40M GC-tracked objects for a 2.4GB DAG) with `MappedByteBuffer` via `FileChannel.map(READ_ONLY, 8, dataSize)`. Aligns with go-ethereum/core-geth/Erigon mmap approach; improvement over Besu/Nethermind heap pattern. Thread-safe concurrent reads via `ThreadLocal<byte[]>` + absolute-position `buffer.slice(offset, 64).get(bytes)`. DAG files keyed by seed hash — fully ECIP-1099 (Etchash) compatible, epoch/seed/naming logic untouched. Benefits: GC pressure elimination (~2.4GB off heap), instant startup (demand-paged by kernel), OS page cache integration for hot mining pages. `EthashMiner` mining methods updated from `dag: Array[Array[Int]]` to `dagLookup: Int => Array[Int]` (already the callback pattern used by `EthashUtils.hashimoto()`). 47 mining tests pass.
- **Source:** PoW review R-004, `docs/reports/POW-CODEBASE-REVIEW.md`

#### L-011: Mining hashrate Prometheus metrics (PoW review R-005) ✅ DONE

- **File:** `src/main/scala/.../consensus/mining/MiningMetrics.scala`, `src/main/scala/.../consensus/pow/miners/Miner.scala`
- **Priority:** Low | **Risk:** Low
- **Status:** DONE — Added 4 Prometheus metrics to `MiningMetrics`: `mining.hashrate.hps.gauge` (current H/s), `mining.hashes.tried.total` (cumulative nonce attempts), `mining.blocks.mined.success.counter`, `mining.blocks.mined.failure.counter`. Instrumented via `Miner.submitHashRate()` which is called after every mining round. 47 mining tests pass.
- **Source:** PoW review R-005, `docs/reports/POW-CODEBASE-REVIEW.md`

#### L-012: Static peer outbound RLPx connection silently fails on localhost

- **File:** `src/main/scala/.../network/ConnectedPeers.scala`, `src/main/scala/.../network/PeerManagerActor.scala`
- **Priority:** Low | **Risk:** Medium (affects multi-client local setups)
- **Discovered:** 2026-03-27, during core-geth snap/1 serving verification
- **Status:** DONE — Race condition in `CheckStaticPeers`: inbound peer from core-geth uses ephemeral source port (`127.0.0.1:RANDOM`), which doesn't match static node address (`127.0.0.1:30303`). Neither `isConnectionHandled(addr)` nor `hasHandshakedWith(nodeId)` catches a pending inbound, so duplicate outbound connections are initiated every 15-30s, immediately disconnected with `AlreadyConnected`. Fix: (1) Added `hasNodeIdPending(nodeId)` to `ConnectedPeers` — checks pending peers by node ID across both incoming and outgoing. (2) `createPeer` accepts `targetNodeId` from enode URI for outgoing peers. (3) `CheckStaticPeers` guard now includes `!connectedPeers.hasNodeIdPending(nodeId)` + debug-level lifecycle logging. 2,706 tests pass.

#### L-013: Review concurrent bytecode download during account phase ✅ DONE

- **File:** `src/main/scala/.../blockchain/sync/snap/SNAPSyncController.scala`
- **Priority:** Low | **Risk:** Low
- **Discovered:** 2026-03-27, during ETC mainnet SNAP sync attempt 3
- **Status:** DONE (2026-03-28) — Enabled concurrent bytecode download during account phase with `budget=2` (was 0). All 3 coordinators (accounts=5, storage=3, bytecode=2) now run concurrently from the first account batch, matching geth's behavior. Bytecodes are content-addressed (hash-keyed) so pivot changes don't invalidate them — the existing `ByteCodePivotRefreshed` handler already clears stale peer tracking. On ETC mainnet (~2M contracts, ~50K+ unique codeHashes), this eliminates the post-account bytecode download wait. Removed the redundant budget boost in `AccountRangeSyncComplete` handler (budget was already at 2).

#### L-014: RocksDB memory budget — replace ClockCache, add WriteBufferManager ✅ DONE

- **File:** `src/main/scala/.../db/dataSource/RocksDbDataSource.scala`
- **Priority:** Low | **Risk:** Medium (affects sync performance if budget too tight)
- **Discovered:** 2026-03-27, during ETC mainnet SNAP sync attempt 3 (RSS 10.6GB on 4GB heap, swap nearly exhausted)
- **Status:** DONE (2026-03-28) — Three changes to cap RocksDB native memory:
  1. Replaced deprecated `ClockCache` with `LRUCache` (both `apply()` and `destroyDB()` paths) — ClockCache was deprecated in RocksDB 8.x due to memory fragmentation and over-allocation.
  2. Added `WriteBufferManager(blockCacheSize, cache)` on `DBOptions` — enforces a shared memory ceiling across block cache + all memtable write buffers across 14 column families. This is what Besu does to keep RSS stable.
  3. Set `setMaxWriteBufferNumber(2)` on `ColumnFamilyOptions` — caps queued memtables per CF.
- **Also fixed:** SNAP sync temp files (`fukuii-contract-*.bin`) now write to `<datadir>/tmp/` instead of `/tmp` on root SSD. 739 stale temp files (14 GB) accumulated on root across sync restarts — caused root drive to fill to 100% and crash Besu (ETC mainnet attempt 3 failure at ~03:44). `AccountRangeCoordinator` now accepts `tempDir` parameter, defaulting to datadir.
  4. Added configurable `tmpdir` variable to `fukuii.conf` — defaults to `${fukuii.datadir}/tmp`, independently overridable via `-Dfukuii.tmpdir=...`. `Fukuii.scala` sets `java.io.tmpdir` at JVM startup so ALL temp file paths (RocksDB JNI `.so` extraction, `Files.createTempFile()`, JFR profiles) land on the same volume as the database. `SNAPSyncController` reads from config for `AccountRangeCoordinator`. `DebugService` CPU profiler output also redirected to datadir.
- 2,706 tests pass.

#### L-019: Skip storage tasks after repeated proof verification failures ✅ DONE

- **File:** `src/main/scala/.../blockchain/sync/snap/actors/StorageRangeCoordinator.scala`
- **Priority:** Low | **Risk:** Low
- **Status:** DONE (`469421119`) — Skip storage tasks that repeatedly fail proof verification, preventing infinite retry loops with peers that return inconsistent proofs.

#### L-020: Improve SNAP coordinator progress metrics accuracy and detail ✅ DONE

- **File:** `src/main/scala/.../blockchain/sync/snap/actors/AccountRangeCoordinator.scala`
- **Priority:** Low | **Risk:** Low
- **Status:** DONE (`24fa35293`) — More accurate progress reporting: keyspace percentage, ranges done count, pending/active task counts, worker/peer counts, accounts/sec throughput.

#### L-021: Node-wide periodic status reporting and log quality improvements ✅ DONE

- **File:** Multiple sync actors
- **Priority:** Low | **Risk:** Low
- **Status:** DONE (`d2c2664ee`) — Periodic status summary for all SNAP coordinators. Reduced redundant log spam (e.g., `UpdateMaxInFlightPerPeer`).

#### L-022: Eliminate SNAP sync redundancies — non-overlapping work-steal + storage pivot dedup ✅ DONE

- **Files:** `AccountRangeCoordinator.scala`, `StorageRangeCoordinator.scala`
- **Priority:** Low | **Risk:** Medium (sync-critical)
- **Status:** DONE (`734533c87`) — Two fixes:
  - **R1: Non-overlapping work-steal ranges.** Old code: victim keeps `[next, last]`, stealer gets `[midpoint, last]` — both download `[midpoint, last]` (up to 50% overlap per steal). New code: victim's `last` shrunk to `midpoint`, stealer gets `[midpoint, originalLast]` — zero overlap. `AccountTask.last` changed from `val` to `var`. This was the PRIMARY cause of ETC mainnet attempt 3 stall (278% keyspace = 2.8x redundancy, effective unique rate ~530 acc/sec instead of ~1,487).
  - **R2: Storage pivot dedup.** `StorageRangeCoordinator` skips re-downloading storage for accounts already completed before a pivot refresh. Uses `completedAccountHashes` set.

#### L-022b: Persist completed storage accounts + healing pre-check (R5/R6) ✅ DONE

- **Files:** `StorageRangeCoordinator.scala`, `TrieNodeHealingCoordinator.scala`, `AppStateStorage.scala`
- **Priority:** Low | **Risk:** Low
- **Status:** DONE (`01f8a8250`) — Two fixes:
  - **R5: Healing mptStorage pre-check.** Before requesting trie nodes from peers, check if the node already exists in local mptStorage. Skips redundant network requests for nodes already downloaded.
  - **R6: Crash recovery persistence.** Completed storage accounts written to file-backed storage (32-byte entries per account hash). Path persisted via `AppStateStorage.putSnapSyncCompletedStoragePath()`. On restart, completed accounts loaded and skipped. 256-block safety valve for stale data.

#### L-022c: R3 task-peer cooldown + R4 cross-batch bytecode dedup ✅ DONE

- **Files:** `StorageRangeCoordinator.scala`, `ByteCodeCoordinator.scala`
- **Priority:** Low | **Risk:** Low
- **Status:** DONE (`cf16873eb`) — Two fixes:
  - **R3: Task-peer failure tracking.** `StorageRangeCoordinator` tracks recent task-peer failures in `Map[(accountHash, peerId), timestamp]` with 30s TTL (3x base peer cooldown). Prevents re-dispatching a timed-out storage task to the same peer. Lazy eviction during dispatch. Cleared on pivot refresh.
  - **R4: Cross-batch bytecode dedup.** `ByteCodeCoordinator` maintains coordinator-level `HashSet[ByteString]` of downloaded codeHashes across all batches. Eliminates ~7,350 redundant bytecode requests caused by Bloom filter false positives in per-batch dedup. `filterAndDedupeCodeHashes()` checks `downloadedCodeHashes` before `seen` set. Response handler tracks successful downloads.

#### S-001: StateNodeFetcher escape valve — max retries + GetNodeData fallback ✅ DONE

- **Files:** `StateNodeFetcher.scala`, `BlockFetcher.scala`, `BlockImporter.scala`, `Config.scala`, `sync.conf`
- **Priority:** P0 | **Risk:** Medium (sync-critical)
- **Status:** ✅ Done (2026-03-31) — escape valve prevents infinite retry.
- **Problem:** `StateNodeFetcher` retried GetTrieNodes forever with 5s backoff when responses were empty. 16,896 empty responses = ~23.5 hours of futile retrying in attempt 4. No max retry, no fallback, no stale root detection.
- **Research (2026-03-31):** Geth has no max retry but healing runs in snap syncer with multi-node batch requests, not one-at-a-time in RegularSync. Besu uses MAX_RETRIES=4 per request (`RetryingGetAccountRangeFromPeerTask.java:30`), then switches peer.
- **Fix:** Added retry counters (`snapRetryCount`, `totalRetryCount`, `emptyResponseCount`). After `maxStateNodeSnapRetries` (10) empty SNAP responses, falls back to GetNodeData (hash-based, works regardless of state root). After `maxStateNodeTotalRetries` (30) total failures, signals `StateNodeFetchFailed` to supervisor. BlockImporter skips the failing block and continues. Rate-limited logging (first + every 100th empty response).
- **Config:** `sync.max-state-node-snap-retries = 10`, `sync.max-state-node-total-retries = 30`
- **Observed in:** ETC mainnet attempt 4 (2026-03-30). 16,896 empty GetTrieNodes responses, 23.5h stall at 0 blk/s.

#### S-002: Disable deferred merkleization by default ✅ DONE

- **Files:** `sync.conf`
- **Priority:** P0 | **Risk:** Low
- **Status:** ✅ Done (2026-03-31) — `deferred-merkleization = false`.
- **Problem:** `deferred-merkleization = true` (default) caused `checkAllDownloadsComplete()` to skip healing entirely. RegularSync then hit missing trie nodes on every block and relied on StateNodeFetcher's one-at-a-time GetTrieNodes requests — which stalled because the state root was stale.
- **Research (2026-03-31):** Both geth and Besu run mandatory healing after snap download. Geth: eager healing phase with `healTask` scheduler (`sync.go:705-718`). Besu: two dedicated healing pipelines (account + storage flat DB). Neither skips healing.
- **Fix:** Changed `deferred-merkleization = false` in `sync.conf`. Re-enables existing `startStateHealing()` which was already implemented but bypassed.
- **Observed in:** ETC mainnet attempt 4 (2026-03-30). Root cause of the 0 blk/s stall.

#### S-003: Per-peer request cap — ✅ ALREADY IMPLEMENTED

- **Files:** All three coordinators
- **Priority:** P1 | **Risk:** Medium
- **Status:** ✅ Already implemented — `maxInFlightPerPeer = 5` (matches Besu's MAX_OUTSTANDING_REQUESTS=5).
- **Research (2026-03-31):** Besu caps at `MAX_OUTSTANDING_REQUESTS = 5` per peer (`EthPeer.java`). Geth doesn't have explicit cap but uses capacity-sorted peer selection with dynamic throttle that implicitly limits requests to high-throughput peers. Fukuii already has `maxInFlightPerPeer` tracked via `UpdateMaxInFlightPerPeer` messages across all coordinators.

#### S-004: Log noise reduction ✅ DONE

- **Files:** `StateNodeFetcher.scala`
- **Priority:** P2 | **Risk:** Low
- **Status:** ✅ Done (2026-03-31) — rate-limited empty response logging.
- **Problem:** Attempt 4 logs were flooded with "SNAP TrieNodes response was empty, retrying" — 16,896 times.
- **Fix:** Rate-limited in S-001 implementation: logs first occurrence, then every 100th occurrence with cumulative count. Coordinator stateless marking messages are already one-per-event (not repeated).
- **Observed in:** ETC mainnet attempt 4 (2026-03-30). Log file dominated by repeated warnings.

#### L-023: Peer rehabilitation after pivot refresh — ✅ ALREADY ALIGNED

- **Files:** `AccountRangeCoordinator.scala`, `StorageRangeCoordinator.scala`, `ByteCodeCoordinator.scala`
- **Priority:** Low | **Risk:** Medium (sync-critical, peer management)
- **Status:** ✅ No change needed — already aligned with reference clients.
- **Research (2026-03-31):** Geth clears `statelessPeers` map on new sync cycle (equivalent to pivot refresh) at `sync.go:617`. Fukuii's `PivotRefreshed` handler already clears `statelessPeers` and `peerConsecutiveTimeouts`. Besu has no explicit rehabilitation — uses per-request retry with peer switching instead.
- **Observed in:** ETC mainnet attempt 4 (2026-03-30), 6.5h into sync. 13/14 storage peers marked stateless.

#### L-024: Auto-scale worker count based on connected peer count — ✅ ALREADY IMPLEMENTED

- **Files:** `AccountRangeCoordinator.scala`, `StorageRangeCoordinator.scala`, `ByteCodeCoordinator.scala`
- **Priority:** Low | **Risk:** Medium (performance-critical)
- **Status:** ✅ Already implemented — `maxWorkers = activePeerCount * maxInFlightPerPeer` (default 5).
- **Research (2026-03-31):** Geth uses goroutine-per-request with capacity-sorted peer selection (`capacitySort` at `sync.go:1382-1393`) and dynamic throttle (1.33x increase / 1.25x decrease at `sync.go:2370-2385`) — implicit scaling via peer throughput. Besu uses fixed `maxOutstandingRequests` per pipeline — no auto-scaling. Fukuii's approach (explicit scaling tied to peer count) goes beyond both reference clients, which is appropriate for ETC's low snap peer count.
- **Observed in:** ETC mainnet attempt 4 (2026-03-30). 14 workers on 2 peers.

#### L-025: Less aggressive stateless marking under peer pressure — ✅ DONE

- **Files:** `AccountRangeCoordinator.scala`, `StorageRangeCoordinator.scala`
- **Priority:** Low | **Risk:** Medium (sync-critical)
- **Status:** ✅ Done (2026-03-31) — dynamic threshold scales with workers/peers ratio.
- **Research (2026-03-31):** Geth marks stateless on first empty response — even more aggressive than Fukuii's 3-timeout threshold (`sync.go:2574, 2684, 2812, 2931`). But geth doesn't suffer because Ethereum mainnet has many peers. Besu uses MAX_RETRIES=4 per request with `AbstractRetryingSwitchingPeerTask` peer switching (lines 62-161). Neither approach works well for ETC's low peer count.
- **Fix:** Changed `consecutiveTimeoutThreshold` from static `val = 3` to dynamic `def` that scales with `workers / peers`: `max(3, 3 * (workers / peers))`. With 14 workers / 2 peers → threshold 21. Applied to AccountRangeCoordinator and StorageRangeCoordinator. ByteCodeCoordinator doesn't have stateless peer tracking.
- **Related:** L-024 (worker auto-scaling) also mitigates by reducing per-peer load.
- **Observed in:** ETC mainnet attempt 4 (2026-03-30). 1,643 account + 3,521 storage + 2,958 bytecode timeouts.

#### L-027: Clean up internal bug-reference log tags in TrieNodeHealingCoordinator ✅ DONE

- **File:** `src/main/scala/.../blockchain/sync/snap/actors/TrieNodeHealingCoordinator.scala`
- **Status:** ✅ DONE — `[HW1-BOOT]` → `[HEAL]` and `[HW1-FEED]` → `[HEAL]` with operator-friendly messages.
- **Introduced in:** commit `8dbad07cb` (BUG-HW1 fix)

#### L-028: Remove post-healing trie walk once proof seeding + self-feeding validated

- **Files:** `SNAPSyncController.scala` (`StateValidator`, `startTrieWalk`, `findMissingNodesWithPaths`)
- **Priority:** Low | **Risk:** Low (audit/research)
- **Status:** Deferred — requires proof seeding with path tracking first (see L-028b below)
- **Description:** Long-term, consider removing the full trie walk entirely once proof-based seeding +
  `discoverMissingChildren` self-feeding is validated as comprehensive over multiple ETC mainnet syncs
  (aligning fully with core-geth/Besu). The walk would remain as an opt-in audit mode.
  Prerequisite: `MerkleProofVerifier.traversePath()` must collect (compact-path, hash) pairs rather than
  hashes only, so proof-discovered nodes can be queued via `QueueMissingNodes` with proper pathsets.
  The current implementation collects hashes only — the flush is deferred and logged as `[PROOF-SEED]`.

#### L-026: Aggressive peer discovery when snap peer count is low — RESEARCH DONE, DEFERRED

- **Files:** `src/main/scala/.../network/PeerManagerActor.scala`, `src/main/scala/.../network/discovery/`
- **Priority:** Low | **Risk:** Low
- **Status:** Deferred to post-attempt-5. Research complete, implementation deferred.
- **Research (2026-03-31):** Geth reserves half of peer slots for snap/1-capable peers during snap sync (PR #22171). Besu has no adaptive discovery. Geth's approach is relevant but requires deeper changes to `PeerManagerActor` and `ConnectedPeers` to distinguish snap-capable peers during connection selection.
- **Proposed fix:** When connected snap peers < 5, double discovery frequency and prioritize snap/1-capable peers in `PeerManagerActor.maybeConnectToDiscoveredNodes()`. Reserve a portion of outgoing peer slots for snap-capable peers during SNAP sync phase.
- **Observed in:** ETC mainnet attempt 4 (2026-03-30). 2 connected peers, 32 pending, 0 inbound.

---

## Tier 4: FUTURE — Post-Production Roadmap

#### F-007: Geth-style lazy healing for RegularSync (Option B)

- **Files:** New subsystem in `src/main/scala/.../sync/regular/`
- **Priority:** Future | **Risk:** High (significant new subsystem, ~300-500 lines)
- **Prerequisite:** Successful ETC mainnet SNAP sync with healing enabled (attempt 5+)
- **Description:** Make `deferred-merkleization = true` viable by adding a proper healing mechanism to RegularSync instead of relying on one-at-a-time GetTrieNodes. Currently, deferred merkleization skips healing entirely, causing RegularSync to stall on missing trie nodes.
- **Research (2026-03-31):** Geth runs an eager healing phase with `assignTrienodeHealTasks()` integrated into the snap syncer — discovers missing nodes via trie walk and batch-fetches via GetTrieNodes (multiple nodes per request). Besu runs two dedicated healing pipelines (account + storage flat DB). Both require healing; neither defers it entirely to block execution.
- **Proposed implementation:**
  1. When RegularSync starts after deferred-merkleization SNAP sync, launch a background healing task
  2. The healing task walks the state trie from the pivot root, discovers missing nodes, and batch-fetches them via GetTrieNodes (multiple nodes per request, unlike current one-at-a-time)
  3. Block import waits for healing to reach "good enough" coverage before proceeding
  4. This eliminates the healing phase bottleneck while still ensuring all nodes are present before RegularSync
- **Why deferred:** The current fix (S-002: disable deferred merkleization) re-enables the existing healing phase, which is slower but proven. This Option B optimization should wait until attempt 5 succeeds and we have telemetry on healing phase duration.
- **Geth reference:** `go-ethereum/eth/protocols/snap/sync.go:705-718` (healing begins when `len(s.tasks) == 0`)

#### F-001: GraphQL endpoint

- **Priority:** Future | **Risk:** Medium
- **Description:** Core-geth, Besu, and Erigon all expose GraphQL (EIP-1767 schema). Thin wrapper over same backend as JSON-RPC — Besu uses graphql-java on separate port 8547, core-geth uses graphql-go on shared port at `/graphql` path. Covers blocks, transactions, accounts, logs, pending state.
- **Customer segment analysis (2026-03-27):** NOT needed by current customer segments. Blockscout uses JSON-RPC (configurable `ETHEREUM_JSONRPC_VARIANT`, supports geth/erigon/nethermind/besu — does NOT use GraphQL to talk to clients). Centralized exchanges use standard JSON-RPC + WebSocket. Mining pools use `eth_getWork`/`eth_submitWork` + WebSocket. GraphQL becomes relevant for DApp developers post-Olympia — deprioritize behind customer-facing gaps (M-029, M-030, M-031, M-032). Also not required by cross-chain bridges (CCIP, LayerZero, Axelar, Wormhole), oracle providers (Chainlink, RedStone, Tellor, Pyth), or wallets (MetaMask, Rabby, Trust, Ledger, Safe). Post-Olympia DApp developer convenience only.

#### F-002: Built-in Stratum mining server

- **Priority:** Future | **Risk:** Medium
- **Description:** No ETC client (core-geth, Besu, Fukuii) implements the Stratum protocol. Adding a built-in Stratum TCP server would make Fukuii the first ETC client where ASIC/GPU miners can connect directly without external pool software — reducing the mining stack from 3 components (node + pool software + miner) to 2 (Fukuii + miner). Large pool operators (F2pool, 2miners, Antpool) continue using their proprietary infrastructure via `eth_getWork`/`eth_submitWork` RPC — this feature serves solo miners, small cooperatives, and operators who want a single-binary mining setup.
- **ETChash note:** ETC mining is primarily ASIC (iPollo, Jasminer, Bitmain E9) and GPU. The existing `EthashMiner` CPU miner is useful for Mordor testnet only. Stratum serves the real mining hardware on mainnet.
- **Monero precedent:** Monero (monero-project/monero-gui) is the only major PoW project where the reference client includes built-in mining, eliminating external dependencies. No other PoW client (Zcash, Kaspa, Ergo, Ravencoin) has built-in Stratum.

**Protocol:** Stratum V1 (widely supported by all ASIC/GPU mining software). Stratum V2 (stratum-mining/stratum) as future upgrade path.

**Architecture:** Pekko TCP actor accepting persistent miner connections, integrated with existing `EthMiningService`, `WorkNotifier`, and `PoWMiningCoordinator`.

**Scope:**
- Stratum TCP server (configurable port, default 3333, `--stratum-enabled --stratum-port=3333`)
- Job distribution (new work pushed on block change + `recommit-interval` timer)
- Share validation (configurable difficulty target per miner)
- Miner authentication (optional, for operator tracking and per-worker stats)
- Hashrate reporting (feeds into existing `eth_submitHashrate` aggregation and Prometheus `mining.hashrate.hps.gauge`)
- TUI mining panel (connected miners, aggregate hashrate, shares accepted/rejected, blocks found)
- Configurable payout address (uses existing `mining.coinbase` config)

**Existing integration points:**
- `EthMiningService` — already handles `eth_getWork`/`eth_submitWork`, `BlockGenerator.getPrepared()` cache
- `WorkNotifier` — HTTP POST push to external URLs, same pattern adapts to Stratum job push
- `PoWMiningCoordinator` — `RecommitWork` message already regenerates work on timer
- `MiningMetrics` — Prometheus counters already in place for hashrate/blocks
- `Tui.scala` / `TuiRenderer.scala` — JLine 3 framework ready for mining panel addition

**What it replaces:** External pool software (open-etc-pool, etc-stratum) for solo/small-pool operators.
**What it doesn't replace:** Large pool proprietary infrastructure — they keep using `eth_getWork`/`eth_submitWork` RPC.

**Research references (for implementation phase):**

| Repo | Language | Relevance |
|------|----------|-----------|
| `monero-project/monero` | C++ | Built-in solo mining, CLI mine command |
| `monero-project/monero-gui` | C++/QML | GUI mining UI with thread slider — gold standard UX |
| `sammy007/monero-stratum` | Go | Simple Stratum server with web UI — closest architectural match |
| `stratum-mining/stratum` | Rust | Stratum V2 reference implementation — future protocol path |
| `stratum-mining/sv2-spec` | Spec | V2 protocol specification |
| `etclabscore/open-etc-pool` | Go | Current ETC pool software — shows what Fukuii would replace |
| `ethereum-mining/ethminer` | C++ | GPU miner Stratum client — what connects to the server |

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

### 4.1 — Post-Olympia Ecosystem Enablement (2026-03-27 audit)

These items prepare Fukuii for institutional protocol integrations post-Olympia. They are documentation and conformance testing — not code changes.

#### F-007: Cross-chain bridge conformance testing

- **Priority:** Future | **Risk:** Low
- **Description:** Test and document Fukuii's RPC conformance against cross-chain bridge requirements. Creates the technical collateral needed for bridge governance proposals (CCIP, LayerZero, Axelar, Wormhole, CCTP).
- **Test plan:**
  1. Run CCIP contract simulations against Fukuii (batch eth_getLogs, `finalized` tag queries, WebSocket newHeads)
  2. Run LayerZero Ultra Light Node client simulation (event monitoring via getLogs + subscribe)
  3. Test Wormhole Guardian observer pattern (full node event monitoring, block confirmation depth)
  4. Document PoW ETC block confirmation semantics (how `finalized` depth maps to security assumptions — 120 blocks ≈ 30 min for high-value cross-chain transfers)
  5. Test Axelar Gateway contract interaction patterns
- **Output:** `docs/integrations/BRIDGE-CONFORMANCE.md`
- **Depends on:** M-034 ✅ (finalized/safe tags), M-035 ✅ (batch + rate limit) — prerequisites met
- **Note:** Actual bridge deployment requires protocol governance votes (CCIP: Chainlink node operator review, Axelar: AXL holder vote, LayerZero: DVN deployment). This item provides the technical evidence for those proposals.

#### F-008: Ecosystem infrastructure deployment guide

- **Priority:** Future | **Risk:** Low
- **Description:** Many institutional integrations require deployed infrastructure that is NOT client code. Document what needs to be deployed on ETC post-Olympia and who deploys it:
  - **Multicall3** — Deterministic CREATE2 address (`0xcA11bde05977b3631167028862bE2a173976CA11`), deployed on 250+ chains. Requires pre-signed deployment transaction. ETC gas limit going to 60M post-Olympia (well above 872,776 gas requirement).
  - **Safe singleton + proxy factory** — Deterministic CREATE2 addresses. Required for multisig governance (Olympia DAO uses Safe).
  - **The Graph subgraph node** — Standard EVM indexer. Document Fukuii as `graph-node` backend configuration (eth_getLogs + eth_getBlockByNumber).
  - **ERC-4337 bundler** — Application-layer account abstraction. No client changes needed, but needs bundler infrastructure (alt-mempool, EntryPoint contract deployment).
- **Output:** `docs/integrations/ECOSYSTEM-INFRASTRUCTURE-GUIDE.md`

#### F-009: Oracle provider conformance testing

- **Priority:** Future | **Risk:** Low
- **Description:** Verify and document Fukuii compatibility with oracle node software. No oracle provider (Chainlink, RedStone, Tellor, Chronicle, Pyth, API3) is currently deployed on ETC — these are prerequisites for DeFi.
- **Test plan:**
  1. Run Chainlink OCR node simulation against Fukuii (eth_getLogs for FluxAggregator events, eth_estimateGas for report submission, eth_feeHistory for gas oracle)
  2. Verify Fukuii behind nginx/caddy TLS termination (Chainlink requires `https://` and `wss://`)
  3. Document multi-provider setup (Chainlink requires 3 independent, stable RPC endpoints with no rate limiting and valid SSL)
  4. Verify RedStone pull-oracle pattern (standard eth_call + eth_sendTransaction — minimal client overhead)
  5. Verify Tellor dispute contract interaction (standard RPC, 1-hour optimistic delay between report and attestation)
  6. Document Pyth/Wormhole price feed integration path (Wormhole attestation service → ETC contract)
- **Output:** `docs/integrations/ORACLE-CONFORMANCE.md`
- **Note:** Oracle deployment requires business decisions from providers (Chainlink Labs, Chronicle validators, Pyth network). This item provides technical readiness proof.

#### F-010: Safe transaction service compatibility

- **Priority:** Future | **Risk:** Low
- **Description:** Safe (Gnosis Safe) is the standard multisig wallet for DAO governance. Requires `trace_*` methods for transaction indexing and a dedicated transaction service. Fukuii already has `trace_replayBlockTransactions`, `trace_block`, `trace_transaction` (Parity-style). This item verifies the trace output format matches Safe's transaction service expectations.
- **Test plan:**
  1. Run Safe transaction service against Fukuii's `trace_*` output (Parity-format)
  2. Verify ERC-20 transfer indexing via event logs
  3. Document Safe singleton + proxy factory deployment addresses for ETC
  4. Test Safe contract deployment via deterministic CREATE2 factory
- **Output:** `docs/integrations/SAFE-COMPATIBILITY.md`
- **Depends on:** F-008 (Multicall3 deployment)

#### F-011: Production deployment reference architecture

- **Priority:** Future | **Risk:** Low
- **Description:** Document the canonical production deployment topology for institutional use. Chainlink requires `https://` + `wss://`. Exchanges require <100ms latency. Multiple RPC providers need this reference to list ETC.
- **Scope:**
  - nginx/caddy TLS termination config with WebSocket passthrough (`proxy_pass`, `Upgrade`/`Connection` headers)
  - Let's Encrypt / Cloudflare certificate automation
  - Health check integration (`/health`, `/readiness`, `/buildinfo`)
  - Rate limiting at both proxy (nginx `limit_req`) and Fukuii (`rateLimit.enabled`) levels
  - JWT secret management and rotation
  - Docker Compose reference for the full stack (Fukuii + nginx + certbot + Prometheus + Grafana)
  - Multi-instance load balancing (active-active Fukuii nodes behind nginx upstream)
- **Output:** `docs/deployment/PRODUCTION-REFERENCE-ARCHITECTURE.md`

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
H-001 (debug expand) ── M-029 (JS tracer — needs StructLogTracer base)
M-029 (JS tracer) ── M-030 (Blockscout compat — needs trace methods)
M-016 (MESS verify) ─┬── M-033 (MESS reactivation — needs baseline)
H-015 (chain split) ──┘
H-014 (fork boundary) ─┘
M-033 (MESS reactivation) ─┬── M-037 (dynamic confirms — needs MESS + cost model)
M-032 (benchmarks) ────────┘
M-034 ✅ (finalized/safe tags) ─┬── F-007 (bridge conformance)
M-035 ✅ (batch + rate limit) ──┘
M-036 ✅ (getLogs range limit) ── M-032 (production benchmarks)
F-008 (ecosystem infra guide) ── F-010 (Safe compatibility)
M-038 (fee-market ordering) ── (future: bundle tx support)
M-040 (private tx) ── (future: MEV documentation, bundle tx support)
L-024 (worker auto-scale) ── L-025 (stateless marking — L-024 partially mitigates)
L-023 (peer rehab) ── L-025 (stateless marking — rehab reduces need for lenient marking)
L-026 (aggressive discovery) ── (independent, low risk)
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
| 11a — Protocol Prereqs | M-034 ✅, M-035 ✅, M-036 ✅                   | finalized/safe tags, batch+ratelimit, getLogs range limit — ALL DONE |
| 11b — Customer Gaps  | M-029, M-030, M-031, M-032                     | JS tracer, Blockscout compat, archive verify, benchmarks       |
| 11c — Tx Pool Modernization | M-038, M-039                              | Fee-market ordering, nonce queuing                             |
| 12 — MESS Defense    | M-033, M-037                                    | MESS reactivation + dynamic confirmation recommendations       |
| 12b — MEV Foundation | M-040                                           | Private transaction submission                                 |
| 13 — Polish + Future | M-007, M-008, M-019, M-022..M-025, L-001..L-006, F-001..F-006 | Perf, MCP docs, missing RPC, README, networking, GraphQL       |
| 14 — Ecosystem       | F-007, F-008, F-009, F-010, F-011               | Bridge/oracle/Safe conformance, infra guide, prod architecture |

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
| Tier 3 (LOW)        | 6      | networking polish, API docs, operator guide. S-001–S-004 DONE, L-023/L-024/L-025 DONE/ALIGNED, L-026 DEFERRED                                                                                                                               |
| Tier 4 (FUTURE)     | 5      | GraphQL, Stratum, plugin system, GUI/releases, lazy healing Option B (F-004 DONE via Olympia, F-005 removed, F-007 added)                                                                                                                   |
| **Total remaining** | **39** | Was 38, +4 SNAP attempt 5 items (S-001–S-004 all DONE), +1 future item (F-007 lazy healing). L-026 deferred.                                                                                                                                |

---

## Completed on march-onward (35+ items, verified 2026-03-27)

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
| EVM Stack mutable array (M-008)  | `Stack.scala`, `OpCode.scala`, 6 test files    | Array[UInt256]+top replaces Vector. peek/set in-place. 540 VM tests + 12 EVM consensus pass |
| IntegrationTest exclusion (M-028) | `build.sbt:75-78`                              | ScalaTest `-l IntegrationTest` alongside SlowTest. 2,706/2,706 pass |
| Integration test baseline (M-012) | Diagnostic only                                 | 24 suites, 8 tests pass, 16 empty (need ethereum/tests fixtures) |
| Memory-mapped DAG files (L-010)  | `EthashDAGManager.scala`, `EthashMiner.scala`   | MappedByteBuffer replaces heap Array. Aligns with geth mmap. ECIP-1099 compatible |
| ETH63-67 protocol removal (ETH68-only) | `bc7c62666` | `ETH65.scala` deleted, 7 old decoders removed (-376 lines from `MessageDecoders.scala`), `Capability.scala` simplified to ETH68+SNAP1 only, all test specs rewritten |
| ETH65TxHandlerActor (ETH67/68 tx handling) | `7005512d6` | New actor: capability-aware ETH67/68 `NewPooledTransactionHashes` announcement/retrieval; PendingTransactionsManager outbound split by capability |
| BUG-TX1: RLP iterative decode fix | `bc7c62666` | `toTypedRLPEncodables()` converted from recursive→iterative — fixes `StackOverflowError` on large `PooledTransactions` batches (hundreds of typed txs) |
| BUG-S1: Storage infinite loop fix | `668c04c30` | `StorageRangeCoordinator`: removed `emptyResponsesByTask.clear()` on pivot refresh — escape-hatch counter now accumulates across pivots; stuck accounts complete in ~3 min vs 1 h |
| BUG-H1: Parallel trie walk guard  | `668c04c30` | `SNAPSyncController`: added `if (!trieWalkInProgress)` guard in `ScheduledTrieWalk` handler — prevents 4 concurrent redundant walks (was ~3.5× slower) |
| BUG-H2: Healing sentinel fix      | `668c04c30` | Changed `lastTrieWalkMissingCount: Int = Int.MaxValue` → `Option[Int] = None` — eliminates spurious "2147483646 nodes healed" log + incorrect stagnation comparison on first round |

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

## Pre-Upstream-PR Cleanup

Final gate before submitting PR to upstream.

| Item | Status | Details |
|------|--------|---------|
| Gitignore local run scripts | ✅ DONE | Removed `!run-classic.sh`/`!run-mordor.sh` negative patterns, untracked from git |
| Remove hardcoded local paths | ✅ DONE | 13 files had `/media/dev/2tb/` — replaced with env var defaults and generic paths |
| Fix broken doc links | ✅ DONE | Removed references to non-existent `ETC-HANDOFF.md` and `OLYMPIA-HANDOFF.md` |
| Update test counts | ✅ DONE | README and BACKLOG updated to 2,738 (was 2,314/2,642 → 2,678 → 2,706 → 2,738) |
| Fix CHANGELOG typo | ✅ DONE | "Rebranded from Fukuii to Fukuii" → "Rebranded from Mantis to Fukuii" |
| Move internal HANDOFF.md | ✅ DONE | Moved to `.claude/` (not public-facing) |
| Update MARCH-ONWARD-HANDOFF.md | ✅ DONE | Added M-008 section (why/what/where/who/how), reference client table, updated commit/test/RPC counts |

---

## Comprehensive QA & Deep Review

**Status:** DEFERRED — not blocking ETC mainnet SNAP sync, but must not be forgotten. This section documents every area of the client that requires systematic review, testing, optimization, and code hygiene work once the client is operational. This is a living checklist, not a complete audit — each area will surface additional items during the review itself.

**Context:** Fukuii is now functional (SNAP sync operational, 2,738 tests passing, 172 commits on `march-onward`). The next phase after ETC mainnet SNAP success is a disciplined client-wide QA pass: identify correctness gaps, test blind spots, dead code, redundant logic, placeholder implementations, and optimization opportunities. The goal is production-grade code quality equivalent to reference clients.

---

### QA-01: Sync — Regular Sync Review

**Files:** `blockchain/sync/regular/` — `RegularSync.scala`, `BlockImporter.scala`, `BlockFetcher.scala`, `BlockFetcherState.scala`, `BodiesFetcher.scala`, `HeadersFetcher.scala`, `BlockBroadcasterActor.scala`, `StateNodeFetcher.scala`

**Tests exist:** `RegularSyncSpec`, `BlockFetcherSpec`, `BlockFetcherStateSpec`, `FetchRequestSpec` — but coverage is shallow relative to file complexity.

**Review areas:**
- Reorg handling: correct chain reorganization under all fork depths
- Ommer validation during block import (`StdOmmersValidator`, `OmmersValidator`)
- `StateNodeFetcher` — state node recovery path; confirm it handles all MPT node types
- `BlockBroadcasterActor` — peer selection strategy for new block announcements (ETH67/68 `NewPooledTransactionHashes` with types+sizes vs full block)
- Block import failure modes: bad blocks, invalid state root, orphaned branches — all paths reach `BadBlockTracker`
- Memory/resource cleanup after import failures — no leaked actor refs or pending messages
- Interaction with SNAP→regular transition: first regular sync block after SNAP completes
- `BlockImporter` 886-line file: dead code paths, redundant state, cleanup opportunities
- Test: adversarial block sequence (repeated forks at various depths, invalid blocks injected between valid ones)

---

### QA-02: Sync — Fast Sync Review

**Files:** `blockchain/sync/fast/` — `FastSync.scala` (1,314 lines), `SyncStateSchedulerActor.scala` (585 lines), `SyncStateScheduler.scala` (507 lines), `HeaderSkeletonSpec.scala`

**Tests exist:** `FastSyncBranchResolverActorSpec`, `FastSyncBranchResolverSpec`, `HeaderSkeletonSpec` — skeleton/branch resolution tested, state download has thin coverage.

**Review areas:**
- State download completeness: all MPT node types fetched, no silent skips
- Pivot selection: edge cases (1 peer, all peers at same height, pivot far behind network head)
- Checkpoint validation: genesis→pivot chain verified before state download begins
- Recovery after partial state download (crash mid-sync, partial RocksDB state)
- `SyncStateSchedulerActor` 585-line actor: message handling exhaustiveness, actor lifecycle (postStop cleanup)
- FastSync→RegularSync transition: timing, pivot validation, first regular block
- `H-018` (bounded pivot retry) under stress: what happens at retry limit?
- Block body download stall detection and recovery
- Test: corrupt state node in response stream — does scheduler detect and retry?
- Test: pivot block replaced mid-download (pivot refresh) — no double-download, no orphaned state
- Performance: batch sizing vs. peer count, parallel vs. sequential node fetching

---

### QA-03: Sync — SNAP Serving (SNAP Server)

**Files:** `network/NetworkPeerManagerActor.scala`, `network/p2p/messages/SNAP.scala` (693 lines), SNAP server handlers in `NetworkPeerManagerActor`

**Tests exist:** `SNAPServerHandlerSpec` (11 tests) — basic handler coverage only.

**Review areas:**
- `GetAccountRange`: boundary proof correctness under all trie shapes (empty trie, single account, accounts at exact boundary)
- `GetStorageRanges`: 128-account batching logic; account with no storage; account with exactly 1 slot; account with 10K+ slots
- `GetByteCodes`: hash validation; missing code (deleted contract); duplicate hash in request
- `GetTrieNodes`: LRU upper-trie cache correctness; cache invalidation on reorg; node not found response
- Proof generation for empty ranges (`emptyAccountProof` edge case)
- Response size capping: does oversized response truncate correctly per SNAP spec?
- Concurrency: multiple simultaneous SNAP requests from different peers — no shared mutable state corruption
- Rate limiting: does the per-IP rate limiter apply to SNAP requests?
- Behavior at chain tip: serving requests for a block only 1–2 blocks old
- Test: SNAP client (Fukuii) syncing against Fukuii as SNAP server — full round-trip
- Test: boundary proof verification (Merkle proof for range start/end) by independent verifier

---

### QA-04: Sync — SNAP Client Deep Dive

**Files:** `blockchain/sync/snap/` — `SNAPSyncController.scala` (4,026 lines), all coordinators, `MerkleProofVerifier.scala`, `SNAPRequestTracker.scala`, `SNAPErrorHandler.scala`, `PeerRateTracker.scala`

**Tests exist:** Comprehensive (16 spec files, ~144 tests) — but many edge cases remain.

**Review areas:**
- `SNAPSyncController.scala` 4,026-line file: dead code paths from earlier iterations, redundant state, actor message handling completeness — this file has grown organically and needs a careful pass
- Pivot refresh race conditions: in-flight requests completing after pivot change — all responses validated against current root hash
- All-peers-stateless path: `HealingAllPeersStateless` → pivot refresh → healing restart — full round-trip test
- Healing convergence guarantee: does healing always terminate? What if a node is perpetually unavailable?
- `MerkleProofVerifier`: edge cases in proof verification (empty proof, single-node trie, proof for non-existent key)
- `SNAPErrorHandler` 434-line file: all error classifications correct? Any missing cases that fall through to wrong handler?
- `PeerRateTracker`: adaptive timeout calibration — does it converge correctly for slow peers? Fast peers?
- Static peer exemption (SP-001): confirm that static peers never enter `statelessPeers` across all code paths, including edge cases (reconnect, capability re-probe, pivot refresh clearing stateless set)
- Storage range coordinator: `storageSyncCompleteReported` flag correctness — no double-completion signal
- Bytecode deduplication: confirm `knownCodeHashes` Bloom filter has no false-positive issues at scale
- Test: healing with only one available peer (Besu only, no core-geth) — completes without hanging
- Test: healing across two pivot refreshes in sequence — no task queue corruption
- Performance: coordinator lock contention under 16-worker load

---

### QA-05: EVM Review

**Files:** `vm/` — `VM.scala`, `OpCode.scala` (1,519 lines), `PrecompiledContracts.scala` (672 lines), `Stack.scala`, `Memory.scala`, `ProgramState.scala`, `EvmConfig.scala` (444 lines)

**Tests exist:** 40+ spec files in `src/test/.../vm/` — good coverage but several gaps.

**Review areas:**
- `OpCode.scala` 1,519 lines: every opcode implementation reviewed against EVM yellow paper and ETC fork rules — confirm gas, stack effects, error conditions
- Fork-gated opcode availability: each opcode only enabled at the correct fork boundary (e.g., PUSH0 at Shanghai, MCOPY at Cancun, transient storage at Cancun)
- `PrecompiledContracts.scala` 672 lines: all 7 BLS12-381 precompiles, MODEXP (EIP-7883 gas formula), BLAKE2b, P256VERIFY — input validation, gas calculation, failure modes
- `Stack.scala` mutable array implementation: bounds checking, overflow (>1024 elements), underflow, `copy()` for tracer snapshots
- `Memory.scala`: expansion gas cost calculation, out-of-bounds reads (should zero-fill), large memory allocation (memory bomb protection)
- `EvmConfig.scala` 444 lines: fork config correctness for all ETC fork names — Olympia opcodes enabled at correct block
- Gas calculation: verify every opcode's `varGas()` method against reference client behavior (peek-only, no stack mutation)
- `SSTORE` gas rules: EIP-2200 (net metering), EIP-3529 (reduced refunds) — both sides of the dirty/clean/original state matrix
- `CALL` family: value transfer to non-existent account (new account creation gas), stipend forwarding, depth limit (1024)
- `CREATE`/`CREATE2`: collision detection, init code size limit (EIP-3860), gas dedup fix (CREATE gas dedup commit `6c705432b`) correctness
- Olympia-specific: `BASEFEE`, transient storage (`TLOAD`/`TSTORE`), `MCOPY`, `SELFDESTRUCT` post-Cancun semantics
- Test: run full ethereum/tests EVM test vectors (currently 12/12 EVM consensus pass — expand coverage)
- Test: gas exhaustion in every opcode — confirm correct `OutOfGas` error, no state mutation after OOG
- Test: EIP-7702 (if applicable to ETC) — currently tracked in reference client comparison

---

### QA-06: Consensus & Block Validation

**Files:** `consensus/` — `ConsensusImpl.scala`, `ConsensusAdapter.scala`, validators, difficulty calculators, block generators

**Tests exist:** `ConsensusAdapterSpec`, `ConsensusImplSpec`, `PreOlympiaForkComplianceSpec`, `OlympiaForkBoundarySpec`, `OlympiaForkIdSpec`, `AdversarialForkBoundarySpec`

**Review areas:**
- `StdBlockValidator`: all validation rules exhaustively tested — header hash, tx root, receipts root, state root, gas limit bounds, timestamp ordering, extraData length
- `StdOmmersValidator`: ommer depth (≤6 blocks), ommer not already in chain, ommer header valid
- `SignedTransactionValidator`: each tx type (Legacy/Type1/Type2) at each fork — correct acceptance gates
- `EthashBlockHeaderValidator`: PoW hash validation, difficulty check, mixHash format
- Difficulty calculation: `EthashDifficultyCalculator` vs. `TargetTimeDifficultyCalculator` — which is active at which fork, correct bomb delay for ETC
- EIP-1559 `BaseFeeCalculator`: all three cases (congested, sparse, at target) at fork boundary block N-1/N/N+1
- Block generator (`PoWBlockGenerator`, `BlockGeneratorSkeleton`): produced blocks pass all validators — no silent misconfiguration
- `BadBlockTracker`: eviction policy (128 entries, 1h TTL), persistence across restarts (currently in-memory only — risk?)
- Test: produce a block with each possible validation failure and confirm correct rejection with correct error type
- Test: timestamp edge cases — block exactly at parent time (should fail), block 1s after (should pass)

---

### QA-07: PoW Mining Review

**Files:** `consensus/pow/miners/` — `EthashMiner.scala`, `EthashDAGManager.scala`, `PoWMiningCoordinator.scala`, `PoWBlockCreator.scala`, `WorkNotifier.scala`

**Tests exist:** `EthashMinerSpec`, `EthashMinerUnitSpec`, `MockedMinerSpec`, `WorkNotifierSpec`

**Review areas:**
- `EthashDAGManager`: DAG file memory-mapping (M-010 MappedByteBuffer) — correct epoch boundary handling, file path construction, file integrity check
- `EthashMiner` multi-threaded nonce partitioning (L-007): nonce ranges don't overlap between threads, full uint64 space covered, no gap at epoch boundaries
- `PoWMiningCoordinator`: work notification to external miners (stratum/HTTP), new-block interrupt (stop current search immediately), mining coordinator restart on uncle/reorg
- `WorkNotifier` HTTP work notifications: correct getwork format, reconnect on failure, rate limiting
- Hashrate metrics (L-011 Prometheus): correct calculation from completed nonce ranges, reset on new work
- Emergency TD ceiling (`L-009`): correctly halts mining at threshold, logs warning
- `PeerScoringManager` integration (L-008): mined blocks propagated with correct peer selection
- Test: mine a block on a private test network (Gorgoroth/anvil) — confirms full round-trip (mine → broadcast → accepted by peer)
- Test: epoch boundary (DAG change at block 30000) — no mining gap or crash during epoch transition
- Test: mining resume after `miner_setRecommitInterval` change mid-mining

---

### QA-08: MESS (Modified Exponential Subjective Scoring / ECBP-1100) Review

**Files:** `consensus/mess/` — `ArtificialFinality.scala`, `MESSConfig.scala`

**Tests exist:** `MESScorerSpec` (shallow), `OlympiaForkBoundarySpec` (includes MESS reactivation test)

**MESS lifecycle on ETC mainnet:**
- **Activated at Thanos** — MESS enabled; ECBP-1100 scoring applied to fork choice
- **Deactivated at Spiral** (block 19,250,000) — MESS disabled; standard TD fork choice resumes
- **Reactivated at Olympia** — MESS re-enabled with updated parameters for PoW tail security post-merge era

Each transition must be exact — off-by-one errors at fork boundaries leave the chain briefly unprotected or applying MESS to blocks where it shouldn't apply.

**Review areas:**
- MESS score calculation: exponential decay formula matches ECBP-1100 specification exactly for all three active periods
- Fork selection: MESS-enhanced fork choice correctly prefers high-scoring chains over higher-TD chains in reorg scenarios
- **Thanos activation** (block N): MESS scoring begins at exactly block N — block N-1 uses standard TD, block N uses MESS
- **Spiral deactivation** (block 19,250,000): MESS disabled at exactly that block — no scoring applied post-Spiral, no residual state from pre-Spiral scores
- **Olympia reactivation**: MESS resumes at Olympia activation block — confirm parameters (antigravityConstant, window) are the Olympia values, not the original Thanos values if they differ
- `MESSConfig.scala`: correct activation/deactivation/reactivation block numbers for ETC mainnet, Mordor, Gorgoroth test network — no hardcoded numbers that differ between network configs
- `ArtificialFinality.scala`: state machine correctly tracks which period (pre-Thanos / Thanos→Spiral / Spiral→Olympia / post-Olympia) and applies correct logic
- Adversarial reorg during active MESS: attacker chain with >50% hashrate attempts deep reorg — MESS resistance quantified for both active periods
- Adversarial reorg during inactive MESS (Spiral→Olympia): standard TD fork choice, no MESS protection — documented as known reduced security window
- Interaction with SNAP sync: MESS scoring during sync (pivot block in the past, history spans all three transitions) — no false finalization from scoring stale blocks
- Test: deep reorg (100 blocks) during active MESS — correct rejection
- Test: identical attack during inactive window (Spiral→Olympia) — succeeds (expected behavior, not a bug, but must be documented)
- Test: each of the three boundary blocks (Thanos activation, Spiral deactivation, Olympia reactivation) — behavior changes exactly at block N, not N±1
- Test: Mordor testnet activation blocks match Mordor config, not mainnet blocks

---

### QA-09: Network Layer — Peer Management & Scoring

**Files:** `network/` — `PeerManagerActor.scala` (921 lines), `NetworkPeerManagerActor.scala` (1,213 lines), `PeerScoringManager.scala`, `PeerScore.scala`, `ConnectedPeers.scala`, `PeerActor.scala` (432 lines), `AutoBlocker.scala`, `BlockedIPRegistry.scala`

**Tests exist:** `PeerManagerSpec`, `EtcPeerManagerSpec`, `PeerActorHandshakingSpec`, `PeerScoreSpec`, `PeerStatisticsSpec`, `AutoBlockerSpec`

**Review areas:**
- Static peer exemption (SP-001) completeness: `isStatic=true` propagates through all peer lifecycle events (reconnect, capability re-probe, PeerDisconnected, PeerAvailable) — no path where a static peer is lost silently
- Static peer reconnect (L-035 / `StaticNodesLoader`): 15s reconnect timer fires correctly, no double-dial race, persists to `static-nodes.json` via `admin_addPeer`
- `PeerScoringManager` (L-008): score decay, reputation thresholds, peer eviction ordering — tests cover the actual scoring formulas
- `AutoBlocker` (L-031): detection threshold calibration, block duration, unblock behavior, interaction with `BlockedIPRegistry`
- `BlockedIPRegistry`: persistence across restarts (currently in-memory — is this intentional?), `admin_addBlockedIP`/`admin_removeBlockedIP` correctness
- `ConnectedPeers`: `promotePeerToHandshaked` / `removePeer` concurrent access safety, stale entry detection
- `NetworkPeerManagerActor` 1,213 lines: dead message handlers, redundant state, actor lifecycle cleanup
- Port-0 filter (L-027): applied at exactly the right point in peer discovery pipeline
- IP blocklist (L-028): IPv4/IPv6 both filtered, CIDR range support (if any), blocklist max size
- Max outbound peer limit enforcement: static peers don't count toward the limit
- Test: peer connects, gets scored low, gets evicted — correct sequence of events
- Test: static peer repeatedly disconnects and reconnects — remains in pool, never blacklisted
- Test: IP blocklist with 1000 entries — no performance regression in hot path

---

### QA-10: Network Layer — P2P Protocol & Handshaker

**Files:** `network/handshaker/`, `network/rlpx/`, `network/p2p/messages/`

**Tests exist:** `AuthHandshakerSpec`, `AuthInitiateMessageSpec`, `EIP8AuthMessagesSpec`, `PeerActorHandshakingSpec`

**Review areas:**
- ETH61–ETH68 message codec round-trips: every message type encodes→decodes correctly at every protocol version
- `MessageDecoders.scala` (553 lines): version negotiation correctness — highest common version selected, fallback for older peers
- RLPx frame codec: framing, de-framing, chunked message reassembly, encrypted frame integrity
- `AuthHandshaker`: EIP-8 forward compatibility, pre-EIP-8 fallback (L-001 WONTFIX — document why)
- Fork ID (EIP-2124) handshake: correct fork hash computation for all ETC fork history, correct rejection of non-ETC peers
- `EtcForkBlockExchangeState`: fork block exchange with ETH64 `Status` message (ForkId validation) — ETH63-67 capability negotiation removed (see ETH68-only refactor below)
- ETH68 `NewPooledTransactionHashes` (typed tx announcements with types+sizes): announcement/retrieval round-trip via `ETH65TxHandlerActor`
- Capability negotiation: peer advertising snap/1 but not capable (tested by `SnapServerChecker`) — no false admission
- Test: complete handshake sequence against real peers (core-geth, Besu) — no unexpected disconnects
- Test: malformed handshake message — clean disconnect, no panic, no resource leak

---

### QA-11: JSON-RPC API — Correctness & Completeness

**Files:** `jsonrpc/` — 15+ service files, `JsonRpcController.scala` (493 lines), `JsonMethodsImplicits.scala` (440 lines)

**Tests exist:** 30+ spec files — but many services tested in isolation without integration.

**Review areas:**
- `eth_getLogs` range limit (M-036): correct rejection at limit, correct acceptance just below, no off-by-one
- `finalized`/`safe` block tags (M-034): consistent behavior across all 143 methods — any method accepting a block parameter must handle these tags
- Batch RPC rate limiting (M-035): batch requests count toward per-IP limit correctly, not per-method
- `trace_replayBlockTransactions` all four trace types (`trace`, `stateDiff`, `vmTrace`, `revertReason`): each produces correct output for a known transaction, format matches geth/Erigon exactly
- `debug_traceTransaction` callTracer output: nested calls, DELEGATECALL, CREATE, REVERT — output format matches geth callTracer JSON schema
- `debug_traceTransaction` prestateTracer: pre-state snapshot includes all touched accounts/storage slots
- `eth_feeHistory` percentile calculation: matches go-ethereum algorithm for varied block populations
- `eth_createAccessList`: output matches go-ethereum for ERC-20 transfer, complex DeFi call
- `FilterManager` (`eth_newFilter`, `eth_getLogs`, `eth_newBlockFilter`): filter expiry (5-minute idle cleanup), concurrent filter creation, log matching with complex topic filters
- WebSocket subscription (`eth_subscribe`): newHeads fires for every new block, logs deliver correct events, unsubscribe cleans up resources with no leak
- `personal_*` methods: keystore operations tested with real encrypted keystores
- `TestService.scala` (489 lines) and `QAService.scala`: are these dead code or intentionally test-mode only? Document and clean up if unused
- JSON serialization edge cases: null fields, empty arrays, uint256 overflow, address checksum encoding
- IPC transport (`JsonRpcIpcServer.scala`): correct behavior under concurrent connections, large request/response

---

### QA-12: MCP Server Review

**Files:** `jsonrpc/mcp/` — `McpTools.scala` (610 lines), `McpResources.scala` (421 lines), `McpService.scala`, `McpJsonMethodsImplicits.scala`

**Tests exist:** `McpServiceSpec` — basic coverage.

**Review areas:**
- All 15 MCP tools: each tool tested with valid/invalid parameters, correct error responses
- All 9 MCP resources: resource enumeration, resource content correctness, update notifications
- All 4 MCP prompts: prompt rendering with variable substitution
- LLM-agnostic compatibility: verify JSON-RPC 2.0 framing works with Claude, GPT-4, and any spec-compliant client
- Authentication: MCP over JWT-protected HTTP — confirm auth is enforced
- Large response handling: MCP tool returning large state diffs — no truncation
- `McpTools.scala` 610 lines: dead tool implementations, placeholder responses

---

### QA-13: Storage & Database Layer

**Files:** `db/` — `RocksDbDataSource.scala` (488 lines), `db/storage/`, `db/cache/`, `db/components/`

**Tests exist:** `db/dataSource/` and `db/storage/` — moderate coverage.

**Review areas:**
- RocksDB write options: `sync`, `disableWAL`, column family usage — correct for each data type (headers, state, receipts, SNAP flat storage)
- `FlatAccountStorage` / `FlatSlotStorage`: write ordering, key collision handling, seek-based range iteration correctness
- `DeferredWriteMptStorage` (deferred-write MPT): flush timing, memory bound, correct root computation after flush
- State trie pruning (`db/storage/pruning/`): is pruning code active? Correct at fork boundaries?
- Cache eviction (`db/cache/`): cache hit/miss ratio under sync load, no stale reads after cache invalidation on reorg
- Write-ahead log recovery: if the node crashes mid-batch-write, does the DB recover to a consistent state?
- `RocksDbDataSource` 488 lines: error handling on disk full, read-only mode behavior, correct column family routing
- Test: RocksDB state after 10,000-block reorg — no orphaned state, no dangling references
- Test: crash recovery (SIGKILL during bulk write) — node restarts cleanly, state root verifiable

---

### QA-14: Merkle Patricia Trie (MPT)

**Files:** `mpt/` — `MerklePatriciaTrie.scala` (528 lines), `MptVisitors/`

**Tests exist:** `mpt/` test directory — coverage unknown.

**Review areas:**
- All four node types: BranchNode, ExtensionNode, LeafNode, HashNode — encode/decode round-trips
- Empty trie root: keccak256(RLP("")) = correct EIP-style empty root constant
- Insert/update/delete operations: correct node splitting, merging, hash propagation to root
- Proof generation and verification: `eth_getProof` Merkle proofs validated against EIP-1186 test vectors
- Large trie performance: 85M+ account trie — no O(n) operations in hot path
- `decodeNode()` visited set: HashNode collision fix (commit on march-onward) — verify fix is correct and no regression
- Test: delete all keys from a trie → root equals empty trie root
- Test: insert 1M accounts, verify root matches reference client for same data

---

### QA-15: Transaction Pool & Mempool

**Files:** `transactions/`, relevant portions of `EthTxService.scala`, `TxPoolService.scala`

**Tests exist:** `TxPoolServiceSpec` (9 tests), various tx-related specs.

**Review areas:**
- Transaction ordering: price-sorted pending queue, nonce-gapped queued set
- Nonce gap handling: gapped transactions moved to queued, promoted to pending when gap filled
- Transaction replacement: higher-fee replacement (>10% bump rule), eviction of replaced tx
- Memory bound: max pool size enforcement, eviction of lowest-priority transactions
- Transaction types: Legacy, EIP-2930 (Type 1), EIP-1559 (Type 2) in pool simultaneously — correct sorting (effective priority fee)
- `eth_sendRawTransaction` validation: all rejection cases (nonce too low, insufficient balance, gas too low, invalid sig)
- Pool persistence: across restarts? What survives a node restart?
- `txpool_content` / `txpool_inspect` response format matches geth for wallets relying on it
- Test: fill pool to capacity, confirm oldest/lowest-fee transactions evicted correctly

---

### QA-16: Network Upgrade Gating (Fork Boundary Correctness)

**Files:** `vm/EvmConfig.scala`, `consensus/validators/`, `domain/`, fork ID implementation

**Tests exist:** `OlympiaForkBoundarySpec` (12), `OlympiaForkIdSpec` (13), `AdversarialForkBoundarySpec` (11), `PreOlympiaForkComplianceSpec`

**Review areas:**
- Pre-upgrade block N-1: all Olympia features (BLS12-381, BASEFEE opcode, transient storage, MCOPY) absent — test with actual opcode bytes
- Upgrade block N: all features enabled — test with minimal valid post-Olympia transaction
- Post-upgrade adversarial: attacker sends Type 2 tx pre-Magneto — rejected; attacker sends `PUSH0` pre-Shanghai — rejected
- Fork ID mismatch: Fukuii correctly disconnects from non-ETC peers and from ETC peers on wrong fork (e.g., pre-Olympia peer after activation)
- Timestamp vs. block-number activation: ETC uses block-number activation; verify no timestamp-based path is accidentally active
- Mordor testnet vs. ETC mainnet activation blocks: both correct, no config cross-contamination
- Test network configs (Gorgoroth, test-mode): Olympia activated at block 0 — all post-Olympia features available immediately
- `M-017` operator fork warning (not yet implemented): add log warning when fork activation is approaching (within N blocks)

---

### QA-17: Peer Penalization & Reputation Systems

**Files:** `network/PeerScoringManager.scala`, `network/PeerScore.scala`, `network/AutoBlocker.scala`, `network/BlockedIPRegistry.scala`, `network/TimeSlotStats.scala`, `blockchain/sync/Blacklist.scala`

**Tests exist:** `PeerScoreSpec`, `AutoBlockerSpec`, `TimeSlotStatsSpec` — fragmented coverage.

**Review areas:**
- `PeerScoringManager` score update logic: all 30 scored events in `Blacklist.scala` produce correct score delta — no event that should penalize goes unscored
- Score decay over time: peers recover reputation after good behavior — decay rate calibrated correctly
- Eviction threshold: peer evicted at correct score, not before or after
- `AutoBlocker` detection criteria: which behaviors trigger auto-block vs. just penalization? Are thresholds appropriate for ETC network conditions?
- Interaction between `PeerScoringManager` (soft: score down), `Blacklist` (medium: connection ban), `BlockedIPRegistry` (hard: IP ban) — correct escalation path
- Static peer exemption: static peers (`isStatic=true`) must never be score-penalized into disconnection — verify `PeerScoringManager` respects this
- Reconnect after ban expiry: peer banned for N minutes, correctly reconnected after expiry, score reset
- SNAP coordinator penalization vs. network-layer penalization: `statelessPeers` in SNAP coordinators and `Blacklist` in network layer are separate — no double-penalization
- Test: peer sends 10 consecutive bad blocks → evicted with correct reason logged
- Test: static peer gets a bad block → scored down at network layer (if any) but NOT evicted

---

### QA-18: Code Hygiene — Dead Code, Redundant Logic, Placeholders

**Scope:** All 76,631 lines of source across every subsystem.

**Already cleaned (2026-04-04):**
- `ETH65.scala` — **deleted** as part of ETH63-67 deprecation. ETH65 defined pre-ETH68 transaction pool message types (pre-EIP-2681 format, no requestId). No ETC peer negotiates ETH63-67 anymore.
- `MessageDecoders.scala` — ETH63/64/65/66/67 decoders removed (-376 lines); only `ETH68MessageDecoder` active
- `Capability.scala` — simplified from version-range negotiation to ETH68-only (or ETH68+SNAP1)

**Review areas:**
- `TestService.scala` (489 lines): large test-mode service — document exactly which methods are test-only, which are used in production; add `@testModeOnly` annotation or equivalent
- `QAService.scala`, `QAJsonMethodsImplicits.scala`: QA/test namespace — is any of this in the production RPC surface? Should it be gated?
- `IeleJsonMethodsImplicits.scala`, `IeleService.scala` (if it exists): IELE VM experimental — is it dead code? Remove or document
- `faucet/` package: faucet RPC — production or test-only? If test-only, not exposed in default API list?
- `extvm/` package: external VM support — active code path or placeholder?
- `console/` package: TUI console — working? Tested? Dead code?
- `testmode/` package: test-mode utilities — should not be compiled into production JAR
- `NodeBuilder.scala` (1,115 lines): largest bootstrap file — review for dead initialization paths, commented-out features, unused wiring
- Actor lifecycle: every actor has a `postStop()` that cleans up timers, subscriptions, child actors — verify no resource leaks
- Message handling exhaustiveness: every `receive` / `case` should either handle or explicitly log unhandled messages — no silently dropped messages in production code
- Error handling: every `Future` has a `.recover` or `.onFailure`; no unhandled `Left[Error]` in chain of `Either` handling
- Commented-out code blocks: remove or document with `// TODO:` if intentional
- Redundant logging: duplicate log lines in hot paths (sync progress logged by both coordinator and controller?)

---

### QA-19: Testing Suite Quality & Coverage

**Scope:** All 2,738 tests, test infrastructure, coverage gaps.

**Review areas:**
- Test isolation: every test cleans up actors, RocksDB instances, temp files — no test-order dependencies
- `SlowTest` tag audit: tagged tests run in CI? Are slow tests actually slow, or just tagged out of caution?
- Flaky test registry: `SyncControllerSpec` pivot freshness test (tagged flaky) — root cause identified and fixed or documented
- Integration test completeness: `src/it/` — which integration tests are actually wired and pass? Which are empty stubs?
- Missing spec files: compare `src/main/scala` packages against `src/test/scala` — packages with no corresponding test file are coverage gaps
- Property-based testing: `ScalaCheck` used anywhere? Consider adding property tests for MPT operations, RLP codec, crypto primitives
- Test data: test fixtures use real ETC transaction data or constructed? Real data catches more edge cases
- Performance regression tests: no test currently measures sync speed, block import time, RPC latency — add benchmarks for critical paths
- EVM consensus tests (`ethereum/tests` fixtures): currently 12/12 EVM consensus pass — expand to full GeneralStateTests, BlockchainTests suites
- Cross-client compatibility test: Fukuii blocks accepted by core-geth and Besu; core-geth blocks accepted by Fukuii — round-trip test

---

### QA-20: Performance Optimization Opportunities

**Scope:** Identified from code review and sync benchmarks. Not blocking, but significant.

**Review areas:**
- `SNAPSyncController.scala` (4,026 lines): actor message processing under high load — are there N+1 dispatcher round-trips that could be collapsed?
- RocksDB read amplification: `GetTrieNodes` SNAP server handler reads many individual nodes — batch read with `multiGet()`?
- `MerklePatriciaTrie.scala` hash caching: does the trie cache computed hashes, or recompute on every `encode()`?
- JSON-RPC serialization: `JsonMethodsImplicits.scala` (440 lines) — circe codecs generating intermediate objects? Consider direct streaming encoder for large responses
- `BlockPreparator.scala` (618 lines): transaction execution loop — parallel transaction execution within a block (EIP-2929 access list enables independence detection)
- Peer message routing: `NetworkPeerManagerActor` (1,213 lines) as central routing bottleneck — consider sharding by peer ID range
- `ExpiringMap.scala`: currently backed by `ConcurrentHashMap` + background cleaner — could use Guava `CacheBuilder` for built-in expiry
- `FilterManager.scala`: log filter matching — linear scan per new block? Could index by topic
- DAG memory mapping: `EthashDAGManager` with `MappedByteBuffer` — is the page fault pattern optimal? Consider pre-faulting on startup
- Actor mailbox sizing: default Pekko mailboxes — should high-throughput sync actors use bounded mailboxes with backpressure?

---

### QA Summary Checklist

| Area | Files | Tests Exist | Status |
|------|-------|-------------|--------|
| Regular Sync | `blockchain/sync/regular/` | Partial | NEEDS REVIEW |
| Fast Sync | `blockchain/sync/fast/` | Partial | NEEDS REVIEW |
| SNAP Server | `NetworkPeerManagerActor` + handlers | Thin (11 tests) | NEEDS REVIEW |
| SNAP Client | `sync/snap/` | Good (144 tests) | ONGOING |
| EVM | `vm/` | Good (40+ specs) | EXPAND COVERAGE |
| Consensus/Validation | `consensus/` | Good | EXPAND ADVERSARIAL |
| PoW Mining | `consensus/pow/miners/` | Moderate | NEEDS REVIEW |
| MESS | `consensus/mess/` | Thin (1 spec) | NEEDS REVIEW |
| Peer Management | `network/PeerManagerActor` | Moderate | NEEDS REVIEW |
| P2P Protocol | `network/handshaker/`, `rlpx/` | Good | NEEDS REVIEW |
| JSON-RPC API | `jsonrpc/` | Good (30+ specs) | EXPAND INTEGRATION |
| MCP Server | `jsonrpc/mcp/` | Thin | NEEDS REVIEW |
| Storage/DB | `db/` | Moderate | NEEDS REVIEW |
| MPT | `mpt/` | Unknown | AUDIT |
| Transaction Pool | `transactions/` | Thin | NEEDS REVIEW |
| Fork Boundary | `vm/EvmConfig`, validators | Good (36 tests) | EXPAND |
| Peer Penalization | `network/PeerScoringManager` | Partial | NEEDS REVIEW |
| Code Hygiene | All | N/A | DEFERRED |
| Test Suite Quality | All tests | N/A | DEFERRED |
| Performance | All | N/A | DEFERRED |

**Total QA items:** 20 review areas, each containing 8–15 specific check points. Estimated effort: 4–6 engineering weeks for a single thorough pass. Each area will surface additional sub-items during review.

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
