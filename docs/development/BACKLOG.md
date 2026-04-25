# Development Backlog

Post-Olympia engineering work items. Each item has a source location, priority, and risk assessment.

**Schedule:** Address after Mordor activation (block 15,800,850) and ETC mainnet fork are stable.

---

## Performance Optimization

### P-001: Deduplicate gas calculations in EVM opcodes
- **Files:** `src/main/scala/.../vm/OpCode.scala:977`, `OpCode.scala:1187`
- **Priority:** Low | **Risk:** High (consensus-critical)
- **Description:** CREATE and CALL opcodes calculate gas costs twice — once for gas metering, once for execution. Account existence checks are also duplicated. Optimization would reduce CPU per transaction but must not alter consensus behavior.
- **Approach:** Adjust `state.gas` prior to execution in `OpCode#execute` so downstream code doesn't recalculate.

### P-002: Cache parsed configuration files
- **File:** `src/main/scala/.../utils/Config.scala:436`
- **Priority:** Low | **Risk:** Low
- **Description:** Chain config parsing runs on every startup. Cache parsed configs with file modification time checks to skip re-parsing unchanged files. Minor startup optimization.

### P-003: Optimize JSON-RPC request parsing
- **Files:** `src/main/scala/.../jsonrpc/server/http/JsonRpcHttpServer.scala:90-91`
- **Priority:** Low | **Risk:** Low
- **Description:** Separate routing paths for single vs. batched JSON-RPC requests. Cache parsed request body to prevent repeated JSON deserialization. Would improve throughput under RPC load.

### Fukuii Strengths (unique or ahead of reference clients)

- **MCP server** (15 tools, 9 resources, 4 prompts) — only blockchain client with LLM integration via open MCP protocol. LLM-agnostic: works with Claude, ChatGPT, Gemini, Grok, Llama, Mistral, and any JSON-RPC 2.0 client. See M-019 for multi-LLM docs.
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

### C-004: Unknown branch resolution infinite loop guard
- **File:** `src/main/scala/.../sync/regular/BlockImporter.scala:390-407`
- **Priority:** Critical | **Risk:** High (sync-critical)
- **Description:** When `BranchResolution.resolveBranch()` returns `UnknownBranch`, the importer walks back by `branchResolutionRequestSize` (64 blocks) and retries. There is no max iterations counter — if every block in the range fails (e.g., after SNAP sync where only pivot header is stored), the importer loops indefinitely through `StrictPickBlocks`, never recovering. BlockFetcher has 1024 ready blocks queued but ignores them because they fall outside the strict range.
- **Approach:** Add a max retry counter (e.g., 100 iterations). After exceeding it, either fall back to requesting from genesis or restart sync with a new pivot.
- **Discovered:** 2026-03-25 full feature audit — triggered by HeaderExtraFieldsError after SNAP→regular sync transition on Mordor.

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

#### H-014: Olympia fork boundary validation tests
- **Priority:** High | **Risk:** High (consensus-critical)
- **Description:** Comprehensive tests for block N-1 → N → N+1 transition where N = olympiaBlockNumber. Must verify:
  - Block N-1: pre-Olympia rules (15 RLP header fields, no baseFee, legacy gas pricing)
  - Block N: post-Olympia rules activate (16 RLP fields, baseFee present, EIP-1559 gas, treasury redirect begins)
  - Block N+1: continued post-Olympia operation
  - State root computed with correct rules at each block (wrong rules → different state root → invalid)
  - EIP-2935 block hash history contract deployment at fork block
  - Gas limit convergence: 8M→60M via ±1/1024 per block (~2,055 blocks)
- **Reference:** core-geth `headerchain.go` ValidateHeaderChain, Besu `ProtocolScheduleBuilder`, go-ethereum `consensus/misc/eip1559`
- **Existing base:** `MordorOlympiaSpec.scala` (live RPC tests) — needs unit-level counterpart
- **Approach:** Create `OlympiaForkBoundarySpec.scala` with parameterized tests at N-1, N, N+1. Source validation patterns from go-ethereum, Erigon, Nethermind, Besu, core-geth.
- **Depends on:** H-010, H-011

#### H-015: Chain split detection and handling
- **Priority:** High | **Risk:** High (network-critical)
- **Description:** If some nodes don't upgrade to Olympia, the network splits at the fork block. Fukuii must:
  - **Detect:** Fork ID mismatch via `ForkIdValidator` — peers on pre-fork chain have different CRC32 checksum after block N. Verify `ForkIdValidator.validatePeer()` correctly returns `ErrLocalIncompatibleOrStale` for non-upgraded peers.
  - **Disconnect:** Peers on the minority fork should be disconnected and blacklisted. Verify blacklist reason propagation.
  - **Report:** Log operator-visible messages: "Peer X disconnected: incompatible fork (pre-Olympia)", peer count by fork state, % of peers on correct fork.
  - **Recover:** If the node itself is on the minority fork (operator didn't upgrade), detect stalling (no new blocks from compatible peers) and warn loudly.
- **Reference:** core-geth `forkid.go` 4-tier validation, go-ethereum `eth/handler.go` peer drop on fork ID mismatch, Besu `EthProtocolManager` fork ID enforcement
- **Existing base:** `ForkIdValidator.scala` (validation logic), `ForkIdSpec.scala` (6 tests) — needs chain split scenario tests
- **Approach:** Create `ChainSplitSpec.scala` testing fork ID transitions at Olympia boundary. Add logging to `PeerActor` for fork-incompatible disconnections. Source disconnect patterns from go-ethereum, Erigon, Nethermind, Besu, core-geth.

#### H-016: Adversarial node resilience at fork boundary
- **Priority:** High | **Risk:** High (consensus-critical)
- **Description:** Malicious or buggy peers may send invalid blocks around the fork boundary. Fukuii must handle:
  - **Pre-fork blocks with post-fork features:** Block at N-1 with baseFee field, EIP-1559 txs, or new opcodes → reject and ban peer (relates to H-010, H-011)
  - **Post-fork blocks with pre-fork rules:** Block at N without baseFee, wrong gas calculation → reject and ban peer
  - **Deep reorg across fork boundary:** Attacker sends long chain crossing N, applying wrong rules on one side → BranchResolution must validate each block with correct era rules
  - **Bad block hash tracking:** Maintain a set of known-bad block hashes (like core-geth `BadHashes` map). If a peer sends headers containing banned hashes, reject immediately and disconnect.
  - **Peer scoring at boundary:** Peers sending invalid fork-boundary blocks receive heavier penalties than normal invalid blocks (deliberate attack vs. transient error)
- **Reference:** core-geth `headerchain.go:318` BadHashes map, go-ethereum `core/block_validator.go`, Erigon `eth/stagedsync/stage_headers.go` bad block tracking, Nethermind `BlockValidator.cs`
- **Existing base:** `Blacklist.scala` (30 reasons), `BlockHeaderValidatorSkeleton.scala`, `BranchResolution.scala`
- **Approach:** Add `BadBlockTracker` utility (hash set + peer association). Add fork-boundary-specific validation in `BlockHeaderValidatorSkeleton`. Create `AdversarialForkBoundarySpec.scala`. Source patterns from go-ethereum, Erigon, Nethermind, Besu, core-geth.
- **Depends on:** H-014

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
- **Description:** When a worker's range completes, it idles while other ranges continue. On ETC mainnet with 4 peers/ranges, Range 1 took 21h 32m but Range 2 finished 26 min later — uneven account density across keyspace means some ranges finish much earlier. Idle workers should steal work from in-progress ranges.
- **Observed:** 2/4 ranges done at 92.7% keyspace — 2 workers idle while 1-2 ranges still active.
- **Approach:** When a range completes and `pendingTasks` is empty, split the largest remaining active task at its current `next` midpoint. Create a new `AccountTask` for the upper half, update original task's `last` to midpoint, enqueue the new task. ~30-40 lines in `handleStoreAccountChunk` after the `isTaskRangeComplete` branch.
- **Constraint:** Must handle the case where the active task has an in-flight request — split at the `next` position (not the in-flight boundary) and let the original task's response naturally stop at the new `last`.

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

#### M-013: Run full EVM test suite — BLOCKED

- **Priority:** Medium | **Risk:** Low
- **Description:** `sbt Evm/test` — 8 spec files. Requires `solc` (Solidity compiler) for `solidityCompile` task (compiles `.sol` contracts to ABI+bin). Not available on current machine. Install `solc` 0.8.x to unblock.

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

#### H-017: Unclean shutdown recovery (PoW review R-007)

- **File:** New file + `src/main/scala/.../StdNode.scala` or equivalent startup path
- **Priority:** High | **Risk:** Medium (operational)
- **Description:** go-ethereum writes periodic safe-block markers to LevelDB and rewinds chain head to the last marker on startup after unclean shutdown (OOM kill, power loss). Fukuii does not have this mechanism. After a crash, the chain head might point to a state root not fully committed to RocksDB, causing state inconsistencies. RocksDB WAL provides atomic single-key writes, but multi-key updates (block storage spans multiple column families) could be partially committed.
- **Fix:** Write a `lastSafeBlock` marker to RocksDB every N blocks (e.g., 64) or M seconds (e.g., 30s). On startup, check if the last shutdown was clean (marker present + cleared on clean shutdown). If unclean, rewind `bestBlockNumber` to the marker and rebuild state from there.
- **Source:** PoW review R-007, `docs/reports/POW-CODEBASE-REVIEW.md`

---

## Architecture

### A-001: Make blockchain reorg operations atomic
- **Files:** `src/main/scala/.../sync/fast/FastSyncBranchResolver.scala:18,22`, `FastSync.scala:491`
- **Priority:** Medium | **Risk:** Medium (sync-critical)
- **Description:** `discardBlocksAfter` and `discardBlocks` are called from sync actors during chain reorganizations. These should be moved into the `Blockchain` interface as atomic operations to prevent race conditions between concurrent sync and RPC state reads.

### A-002: Refactor fork management in EVM config
- **Files:** `src/main/scala/.../vm/EvmConfig.scala:33`, `BlockchainConfigForEvm.scala:23`
- **Priority:** Low | **Risk:** Medium (consensus-adjacent)
- **Description:** Fork configuration uses a flat list of block numbers (16+ forks). A more structured approach (fork registry, ordered activation) would reduce maintenance burden when adding future forks. Current approach works but is verbose.

### A-003: Handle existing state root in sync restart
- **File:** `src/main/scala/.../sync/fast/SyncStateSchedulerActor.scala:185`
- **Priority:** Low | **Risk:** Low
- **Description:** When restarting sync, if we already have the target state root, skip directly to block sync instead of re-downloading known state. Optimization for crash recovery scenarios.

### A-004: Improve trie corruption recovery
- **File:** `src/main/scala/.../sync/fast/SyncStateSchedulerActor.scala:376`
- **Priority:** Low | **Risk:** Medium
- **Description:** When a malformed trie is detected during sync, restart from a new target block rather than continuing with corrupt data. Current behavior logs and continues, which is safe but suboptimal.

---

## Protocol & Networking

### N-001: SNAP protocol server-side implementation
- **Files:** `src/main/scala/.../network/NetworkPeerManagerActor.scala:405,440,475,508`
- **Priority:** Low | **Risk:** Medium
- **Description:** Fukuii currently acts as a SNAP sync client only. Implementing server-side handlers (GetAccountRange, GetStorageRanges, GetTrieNodes, GetByteCodes) would allow Fukuii to serve state data to other SNAP-capable peers. Requires serving from local trie storage.
- **Deferred:** Not needed until Fukuii is deployed as a full archive/serving node.

### N-002: Validate bytecode hashes in SNAP responses
- **File:** `src/main/scala/.../sync/snap/SNAPRequestTracker.scala:225`
- **Priority:** Medium | **Risk:** Low
- **Description:** Bytecode responses are validated structurally but not matched against requested code hashes. Adding hash verification would catch peer misbehavior earlier, improving sync reliability.

### N-003: Use negotiated protocol version in message decoder
- **File:** `src/main/scala/.../network/rlpx/MessageCodec.scala:127`
- **Priority:** Low | **Risk:** Low
- **Description:** Message decoding doesn't switch on the negotiated P2P protocol version. Relevant if Fukuii adopts P2P v5 or later protocol versions. Current approach works for ETH/63-68.

### N-004: Pass capability to handshake state machine
- **File:** `src/main/scala/.../network/PeerActor.scala:136`
- **Priority:** Low | **Risk:** Low
- **Description:** During peer connection, capability information from the Hello message should be forwarded to `EtcHelloExchangeState` for protocol-aware negotiation. Currently works without it but limits future protocol flexibility.

### N-005: Deprecate pre-EIP-8 handshake support
- **File:** `src/main/scala/.../network/rlpx/RLPxConnectionHandler.scala:298`
- **Priority:** Low | **Risk:** Medium (network compatibility)
- **Description:** EIP-8 (May 2016) added variable-length RLPx handshake encoding. Fukuii supports both pre-EIP-8 and EIP-8. Dropping pre-EIP-8 would simplify the handshake code but could break connectivity with very old peers. Requires network coordination before removal.

---

## JSON-RPC

### R-001: Consistent rate limiting across RPC endpoints
- **File:** `src/main/scala/.../jsonrpc/server/http/JsonRpcHttpServer.scala:82`
- **Priority:** Medium | **Risk:** Low
- **Description:** Some RPC endpoints have rate limiting applied; others don't. Review and apply a consistent rate-limiting policy across all endpoint categories (state queries, transaction submission, debug methods).

---

## Testing

### T-001: Extend Ethereum test suite to execute blocks
- **File:** `src/it/scala/.../ethtest/EthereumTestsSpec.scala:59`
- **Priority:** Medium | **Risk:** Low
- **Description:** The `EthereumTestsSpec` currently parses test fixtures and sets up initial state, but does not execute blocks through the `BlockExecution` infrastructure. Completing this would give us full consensus test coverage against the ethereum/tests suite.

### T-002: Verify SyncStateScheduler pivot selection test coverage
- **File:** `src/main/scala/.../sync/fast/SyncStateSchedulerActor.scala:166`
- **Priority:** Low | **Risk:** Low
- **Description:** Verify whether the pivot block selection path in `SyncStateSchedulerActor` has test coverage. If not, add a test case for the scenario where a new pivot is selected during active sync.

---

## Legend

| Priority | Meaning |
|----------|---------|
| **High** | Blocks production use or causes data loss |
| **Medium** | Improves reliability or correctness meaningfully |
| **Low** | Nice-to-have optimization or cleanup |

| Risk | Meaning |
|------|---------|
| **High** | Consensus-critical or sync-critical code path |
| **Medium** | Could affect peer connectivity or data integrity |
| **Low** | Isolated change with limited blast radius |
