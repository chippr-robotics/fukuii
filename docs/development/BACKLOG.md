# Development Backlog

Post-Olympia engineering work items. Each item has a source location, priority, and risk assessment.

**Schedule:** Address after Mordor activation (block 15,800,850) and ETC mainnet fork are stable.
**Last updated:** 2026-03-25 (FIXME/TODO audit — see git log for details)

---

## Performance Optimization

### P-001: ~~Deduplicate gas calculations in EVM opcodes~~ — RESOLVED
- **Status:** Fixed in `march-onward` branch (EC-243 gas dedup commit)
- **What was done:** Passed pre-computed gas from `execute()` into `exec()` so CREATE/CREATE2 no longer recompute `baseGas + varGas`.

### P-003: Optimize JSON-RPC request parsing
- **Files:** `src/main/scala/.../jsonrpc/server/http/JsonRpcHttpServer.scala:90-91`
- **Priority:** Low | **Risk:** Low
- **Description:** Separate routing paths for single vs. batched JSON-RPC requests. Cache parsed request body to prevent repeated JSON deserialization. Would improve throughput under RPC load.

### P-004: SNAP work-stealing for idle workers
- **File:** `src/main/scala/.../sync/snap/actors/AccountRangeCoordinator.scala`
- **Priority:** Medium | **Risk:** Medium (sync-critical)
- **Description:** When a worker's range completes, it idles while other ranges continue. On ETC mainnet with 4 peers/ranges, Range 1 took 21h 32m but Range 2 finished 26 min later — uneven account density across keyspace means some ranges finish much earlier. Idle workers should steal work from in-progress ranges.
- **Observed:** 2/4 ranges done at 92.7% keyspace — 2 workers idle while 1-2 ranges still active.
- **Approach:** When a range completes and `pendingTasks` is empty, split the largest remaining active task at its current `next` midpoint. Create a new `AccountTask` for the upper half, update original task's `last` to midpoint, enqueue the new task. ~30-40 lines in `handleStoreAccountChunk` after the `isTaskRangeComplete` branch.
- **Constraint:** Must handle the case where the active task has an in-flight request — split at the `next` position (not the in-flight boundary) and let the original task's response naturally stop at the new `last`.

---

## Architecture

### A-001: Make blockchain reorg operations atomic
- **Files:** `src/main/scala/.../sync/fast/FastSyncBranchResolver.scala:18,22`, `FastSync.scala:491`
- **Priority:** Medium | **Risk:** Medium (sync-critical)
- **Description:** `discardBlocksAfter` and `discardBlocks` are called from sync actors during chain reorganizations. These should be moved into the `Blockchain` interface as atomic operations to prevent race conditions between concurrent sync and RPC state reads.

### A-003: Handle existing state root in sync restart
- **File:** `src/main/scala/.../sync/fast/SyncStateSchedulerActor.scala:185`
- **Priority:** Low | **Risk:** Low
- **Description:** When restarting sync, if we already have the target state root, skip directly to block sync instead of re-downloading known state. Optimization for crash recovery scenarios.

### A-004: Critical error recovery in SyncStateSchedulerActor
- **File:** `src/main/scala/.../sync/fast/SyncStateSchedulerActor.scala:473`
- **Priority:** Medium | **Risk:** Medium (sync-critical)
- **Description:** On critical trie error, the actor calls `context.stop(self)`. The parent `FastSync` spawns this actor (line 182) but its `Terminated` handler (line 249) only covers `assignedHandlers` — NOT the scheduler child. **No recovery path exists.** If the trie is malformed, the scheduler dies silently and fast sync stalls indefinitely.
- **Reference clients:** geth restarts with fresh pivot (skeleton reset). Besu falls back to full sync.
- **Approach:** Either (a) send a `StateSyncFailed` message to parent before stopping, so `FastSync` can restart with a new pivot, or (b) `FastSync` should `context.watch(syncStateScheduler)` and handle `Terminated` to trigger pivot refresh.

### A-005: EVM Stack data structure optimization
- **Priority:** Low | **Risk:** High (consensus-critical)
- **Description:** Current `Vector[UInt256]` stack provides O(log32 n) indexed access. Reference clients (geth, Besu) use array-backed stacks with O(1) indexed access and pooled allocation. Potential throughput improvement for DUP/SWAP-heavy code. The old TODO suggested `List` — that would be worse (O(n) indexed). An `Array`-backed `Stack` is the correct optimization target.
- **Note:** Previous TODO at `Stack.scala:15` suggested List — removed as incorrect.

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
- **Description:** Message decoding doesn't switch on the negotiated P2P protocol version. Compression IS version-aware (line 58), but message format decoding isn't. Relevant if Fukuii adopts P2P v5 or later. Current approach works for ETH/63-68.

### N-004: Pass capability to handshake state machine
- **File:** `src/main/scala/.../network/PeerActor.scala:136`
- **Priority:** Low | **Risk:** Low
- **Description:** During peer connection, capability information from the Hello message should be forwarded to `EtcHelloExchangeState` for protocol-aware negotiation. Capabilities are available at `rlpxConnectionFactory` (line 369) but NOT passed through on `InitialHelloReceived`. Currently works without it but limits future protocol flexibility.

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

## Pre-Release

### X-001: Change production log level to INFO
- **File:** `src/main/resources/conf/base/pekko.conf:4`
- **Priority:** High | **Risk:** Low
- **Description:** `TODO(production)` — Pekko actor system log level is currently DEBUG. Must be changed to INFO before mainnet release to avoid excessive logging in production.

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

## Resolved in FIXME/TODO Audit (2026-03-25)

Items below were investigated against reference clients (geth, Besu, Erigon) and resolved:

| Item | Resolution |
|------|-----------|
| EC-242 SELFDESTRUCT storage (`BlockPreparator.scala:194`) | Already handled by `InMemoryWorldStateProxy.deleteAccount` |
| Config caching (`Config.scala:469`) | Configs parsed once at startup — caching unnecessary |
| Fork management (`EvmConfig.scala:33`) | Priority-sorted list is functionally correct — equivalent to geth's `IsEnabled()` |
| Stack List vs Vector (`Stack.scala:15`) | List would be worse (O(n) indexed). Vector is correct. See A-005 for real optimization. |
| PoW two-type extraction (`PoWMining.scala:105`) | Mutex + Option pattern works correctly. Besu splits types but no behavioral benefit. |
| JsonRpcBaseController config (`JsonRpcBaseController.scala:40`) | Config required for enabledApis filtering. Only 2 implementors, both pass correctly. |
| MessageSerializableImplicit redundancy (`MessageSerializableImplicit.scala:5`) | `msg: T` provides typed access used by all 41 subclasses. NOT redundant — type-safe convenience. |
| Chain config format (`*-chain.conf:50`) | Inline comments already document EIP-170 gating. Config parser changes not worth it. |
| Mallet wiki reference (`mallet.conf:10`) | IOHK wiki no longer exists. HTTPS docs kept inline. |
| RPC test script (`rpcTest/README.md:45`) | Superseded by `ops/test-scripts/test-rpc-endpoints.sh` |

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
