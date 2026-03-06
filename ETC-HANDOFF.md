# Fukuii Alpha Stabilization — Handoff Document

**Branch:** `alpha` (derived from `main` at v0.1.240)
**Author:** Christopher Mercer (chris-mercer) + Claude Opus 4.6
**Date:** 2026-03-06
**Commits:** 53 (12 bug fixes + 7 chores + 3 features + 2 test suites + 1 cleanup + 1 config fix + 1 dep bump + 10 docs + 1 consensus fix + 2 sync fixes + 1 MCP + 12 alpha stabilization)

---

## Summary

The `alpha` branch is a systematic stabilization pass over Fukuii v0.1.240. Over 16 phases of testing, every major subsystem was exercised on both Mordor testnet and ETC mainnet using the assembly JAR. **11 bugs were found and fixed**, then the branch was extended with ECBP-1100 (MESS) wiring (later rewritten to match the ECIP-1100 polynomial spec), comprehensive consensus test suites, gas limit convergence logic, and dependency updates.

**Test results:** 2,195 unit tests passing, clean compile, assembly JAR verified on Mordor.

---

## ECIP Alignment

The `alpha` branch implements a canonical [ECIP-1066](https://ecips.ethereumclassic.org/ECIPs/ecip-1066) client — the Ethereum Classic Network Description that records all feature upgrades applied to ETC mainnet (Frontier through Spiral).

### Multi-Client References

| Client | Branch | Repository |
|--------|--------|------------|
| core-geth | `etc` | https://github.com/chris-mercer/core-geth/tree/etc |
| Besu | `etc` | https://github.com/chris-mercer/besu/tree/etc |
| Fukuii | `alpha` | https://github.com/chris-mercer/fukuii/tree/alpha |

### Gorgoroth Trials (Local Network Testing)

The alpha stabilization was validated through 16 phases of systematic testing ("Gorgoroth trials"), exercising every major subsystem on both Mordor testnet and ETC mainnet using the assembly JAR. Local devnet testing uses the multi-client stack from the maintained forks above (core-geth `etc`, Besu `etc`, Fukuii `alpha`) — not the deprecated upstream core-geth (last updated 2024) or upstream Besu. 11 bugs were found and fixed, with 2,194 unit tests passing.

---

## Bugs Fixed

### Bug 1: Config Cache Poisoning (Critical)
**Commit:** `9f89a0aa3` — fix: resolve config loading bug for non-default networks

**Problem:** Running `fukuii mordor` loaded ETC mainnet config instead of Mordor. All non-default networks were broken.

**Root cause:** Two interacting issues:
1. Logback's `ConfigPropertyDefiner` eagerly calls `ConfigFactory.load()` during logger initialization, caching the default `application.conf` (network="etc"). When `setNetworkConfig()` later sets `config.resource`, `ConfigFactory.load()` returns the stale cached config.
2. Network configs used bare `include "app.conf"` which resolves from the classpath root, not relative to the config file's directory. The include silently fails, leaving configs without base settings.

**Fix:**
- Call `ConfigFactory.invalidateCaches()` after setting system properties in `App.scala`
- Change all network config includes from `include "app.conf"` to `include classpath("application.conf")`

**Files changed:**
- `src/main/scala/com/chipprbots/ethereum/App.scala` (+6 lines)
- `src/main/resources/conf/mordor.conf` (1 line)
- `src/main/resources/conf/etc.conf` (1 line)
- `src/main/resources/conf/eth.conf` (1 line)
- `src/main/resources/conf/gorgoroth.conf` (1 line)
- `src/main/resources/conf/bootnode.conf` (1 line)

**Reproduce before:** `java -jar fukuii.jar mordor` → logs show ETC mainnet checkpoints
**Verify after:** `java -jar fukuii.jar mordor` → logs show Mordor checkpoint at block 9,957,000

---

### Bug 2: SNAP→Fast Sync Fallback Too Slow (High)
**Commit:** `b28a3a754` — fix: accelerate SNAP→fast sync fallback when peers lack snapshots

**Problem:** When no peer serves SNAP protocol snapshots (common on Mordor), fallback to fast sync takes up to 75 minutes. Each cycle: start SNAP → all peers stateless → refresh pivot → stagnation timer resets → repeat × 5.

**Root cause:** The 15-minute stagnation timer resets on every pivot refresh, even when zero accounts are downloaded.

**Fix:** Track consecutive `PivotStateUnservable` events that produce no account downloads. After 3 consecutive stateless pivots, record a critical failure immediately instead of waiting for the stagnation timer. Reduces worst-case fallback from ~75 minutes to ~5 minutes.

**Files changed:**
- `src/main/scala/.../blockchain/sync/snap/SNAPSyncController.scala` (+30/-1)

**Reproduce before:** Start on Mordor with SNAP sync → wait 75 minutes for fallback
**Verify after:** Start on Mordor with SNAP sync → fallback to fast sync within 5 minutes

---

### Bug 3: Pivot Refresh Counter Reset Loop (High)
**Commit:** `35515e752` — fix: prevent pivot refresh counter from resetting on restart

**Problem:** The SNAP sync consecutive pivot refresh counter from Bug 2 never reached the fallback threshold.

**Root cause:** `restartSnapSync()` reset the counter to 0, but `refreshPivotInPlace` calls `restartSnapSync` when no new pivot is available. This created a loop: counter → 1 → refresh fails → restart resets to 0 → repeat.

**Fix:** Don't reset `consecutiveEmptyPivotRefreshes` in `restartSnapSync()`. Counter resets only on successful account download or full restart from `startSync()`.

**Files changed:**
- `src/main/scala/.../blockchain/sync/snap/SNAPSyncController.scala` (+5/-2)

**Reproduce before:** Run Bug 2 fix on ETC mainnet where core-geth has partial snapshots → counter never accumulates
**Verify after:** Counter correctly accumulates when no accounts arrive; resets when accounts arrive (2,000 from ETC mainnet partial window)

---

### Bug 4: FastSync Best Block Hash Stale (Medium)
**Commit:** `9a529b09b` — fix: correct best block hash tracking in fast sync and JSON-RPC error handling

**Problem:** During fast sync, `eth_blockNumber` returned sync progress but `eth_getBlockByNumber("latest")` returned genesis block.

**Root cause:** `FastSync.updateBestBlockIfNeeded()` called `putBestBlockNumber()` which only updates the number, leaving the hash stale at genesis. `eth_getBlockByNumber("latest")` looks up by hash, not number.

**Fix:** Changed to `putBestBlockInfo()` which updates both hash and number atomically, matching the pattern already used in `SNAPSyncController`.

**Files changed:**
- `src/main/scala/.../blockchain/sync/fast/FastSync.scala` (+6/-2)

---

### Bug 5: JSON-RPC Malformed Request Error Format (Low)
**Commit:** `9a529b09b` (same commit as Bug 4)

**Problem:** Malformed JSON-RPC requests returned plain text errors instead of proper JSON-RPC error format.

**Root cause:** Pekko HTTP's CORS rejection handler was not wrapped with an explicit `handleRejections` directive, causing the default Pekko rejection response (plain text) to bypass JSON-RPC error formatting.

**Fix:** Added explicit `handleRejections` wrapper in `JsonRpcHttpServer` to ensure all error responses use JSON-RPC format.

**Files changed:**
- `src/main/scala/.../jsonrpc/server/http/JsonRpcHttpServer.scala` (+5/-2)

---

### Bug 6: Null Request ID Coerced to 0 (Low)
**Commit:** `9a529b09b` (same commit as Bugs 4-5)

**Problem:** JSON-RPC requests with `"id": null` returned responses with `"id": 0` instead of `"id": null`.

**Root cause:** `getOrElse(0)` in response ID handling.

**Fix:** Changed to `getOrElse(JNull)` per JSON-RPC spec.

**Files changed:**
- `src/main/scala/.../jsonrpc/server/controllers/JsonRpcBaseController.scala` (+5/-2)

---

### Bug 7: Actor Name Collision on Sync Restart (High)
**Commit:** `23b068dc8` — fix: prevent actor name collisions on sync restart RPCs

**Problem:** `fukuii_restartFastSync` RPC crashed with `InvalidActorNameException: actor name [fast-sync] is not unique`.

**Root cause:** `PoisonPill` is async — the old sync actor hadn't stopped before the new one was created with the same name. This is a race condition between actor stop and start.

**Fix:** Added a `syncGeneration` counter to all sync actor names (`fast-sync-N`, `snap-sync-N`, `regular-sync-N`, `peers-client-N`), matching the pattern already used for bootstrap actors. Updated `stopSyncChildren()` to match by prefix instead of exact name.

**Files changed:**
- `src/main/scala/.../blockchain/sync/SyncController.scala` (+27/-8)
- `src/test/scala/.../blockchain/sync/SyncControllerSpec.scala` (+12/-5)

---

### Bug 8: RPC Starvation Under SNAP Sync (Critical)
**Commit:** `836a1f5d6` — fix: isolate sync actors on dedicated dispatcher to prevent RPC starvation

**Problem:** During SNAP sync with 16+ concurrent workers, ALL JSON-RPC calls time out indefinitely — even trivial ones like `eth_chainId`.

**Root cause:** SNAP sync `AccountRangeWorker` actors perform CPU-intensive MPT validation on the default Pekko dispatcher. With 16+ workers, the default dispatcher's thread pool is fully saturated. The HTTP server accepts TCP connections at the kernel level but Pekko's IO layer never processes them — total RPC blackout.

**Fix:** Added a dedicated `sync-dispatcher` (ForkJoinPool, 4-16 threads) in `pekko.conf` and applied it to all sync actors: `SNAPSyncController`, `FastSync`, `RegularSync`, `PeersClient`, and all SNAP coordinator/worker actors. This isolates sync work from the default dispatcher, leaving it free for HTTP, TCP I/O, and general system actors.

**Files changed:**
- `src/main/resources/conf/base/pekko.conf` (+13 lines — new dispatcher config)
- `src/main/scala/.../blockchain/sync/SyncController.scala` (+9/-4)
- `src/main/scala/.../blockchain/sync/snap/SNAPSyncController.scala` (+8/-4)
- `src/main/scala/.../blockchain/sync/snap/actors/AccountRangeCoordinator.scala` (+2/-1)
- `src/main/scala/.../blockchain/sync/snap/actors/ByteCodeCoordinator.scala` (+2/-1)
- `src/main/scala/.../jsonrpc/server/http/InsecureJsonRpcHttpServer.scala` (+3/-1)
- `src/main/scala/.../jsonrpc/server/http/SecureJsonRpcHttpServer.scala` (+5/-2)

**Reproduce before:** Start SNAP sync → `curl eth_chainId` hangs indefinitely
**Verify after:** Start SNAP sync → `curl eth_chainId` responds within 1s

---

### Bug 9: Unhandled MissingNodeException in State RPCs (Medium)
**Commit:** `a107ae1a9` — fix: return proper error for eth_call/estimateGas/getCode when state unavailable

**Problem:** During fast sync, `eth_call`, `eth_estimateGas`, and `eth_getCode` throw unhandled `MissingNodeException`, resulting in generic "Internal JSON-RPC error" (-32603).

**Root cause:** These methods access state trie data which doesn't exist yet during fast sync. `eth_getBalance` and `eth_getTransactionCount` already had proper `MissingNodeException` handling, but these three were missed.

**Fix:** Added `.recover { case _: MissingNodeException => Left(JsonRpcError.NodeNotFound) }` to `call()`, `estimateGas()` in `EthInfoService.scala` and `getCode()` in `EthUserService.scala`, matching the existing pattern.

**Files changed:**
- `src/main/scala/.../jsonrpc/EthInfoService.scala` (+5 lines)
- `src/main/scala/.../jsonrpc/EthUserService.scala` (+2 lines)

---

### Bug 10: Block Body Download Stall (High)
**Commit:** `8dfa845e8` — fix(sync): add peer switching and backoff for block body downloads

**Problem:** Fast sync stalls when peers timeout on `GetBlockBodies`. The retry loop re-queues the same hashes to the same (now blacklisted) peer without delay, causing a tight loop that never recovers.

**Root cause:** `BodiesFetcher.requestBodies()` uses `BestPeer` selector on every retry. When a peer times out, it gets blacklisted, but the next retry still selects the same "best" peer. With all peers blacklisted, `NoSuitablePeer` is returned with only a 0.5s fixed delay, creating a tight retry loop.

**Fix (aligned with go-ethereum/Besu patterns):**
- `PeersClient`: Added `ExcludingPeers(exclude: Set[PeerId])` peer selector to skip failed peers on retry (Besu's `AbstractRetryingSwitchingPeerTask` pattern)
- `FetchRequest`: Track `triedPeers` and `retryCount`, apply exponential backoff `min(syncRetryInterval × 2^retryCount, maxRetryDelay)` on failure
- `BodiesFetcher`: Carry retry state (`triedPeers`, `retryCount`) through the fetch cycle
- `BlockFetcher`: Enforce `maxBodyFetchRetries` limit (default 10), then reset tried peers (allows re-trying after blacklist expiry)
- `Config`: Added `maxRetryDelay` (30s) and `maxBodyFetchRetries` (10) to `SyncConfig`

**Files changed:**
- `src/main/scala/.../blockchain/sync/PeersClient.scala` (+11 lines)
- `src/main/scala/.../blockchain/sync/regular/FetchRequest.scala` (+44/-9)
- `src/main/scala/.../blockchain/sync/regular/BodiesFetcher.scala` (+40/-12)
- `src/main/scala/.../blockchain/sync/regular/BlockFetcher.scala` (+45/-8)
- `src/main/scala/.../utils/Config.scala` (+14/-1)
- `src/main/resources/conf/base/sync.conf` (+9 lines)
- `src/test/.../sync/TestSyncConfig.scala` (+4/-1)
- `src/test/.../sync/regular/FetchRequestSpec.scala` (NEW — 10 tests)

**Tests:** 10 new tests covering exponential backoff math, `maxRetryDelay` cap, config defaults, `ExcludingPeers` selector, and retry state data carriers.

---

### Bug 11: `net_listPeers` Timeout Under Load (Medium)
**Commit:** `e7b4e9281` — fix(rpc): cache peer status to prevent net_listPeers timeout

**Problem:** With 30+ peers, `net_listPeers` times out after 20s.

**Root cause:** `PeerManagerActor.GetPeers` used `parTraverse` to query each peer actor individually (2s timeout per peer). 30 peers × 2s / dispatcher parallelism exceeded the 20s RPC timeout.

**Fix (aligned with go-ethereum/Besu/Nethermind — all store status in-process):**
- Added `peerStatusCache: Map[PeerId, PeerActor.Status]` maintained in-process
- Cache updated reactively: set to `Connecting` on peer creation, `Handshaked` on successful handshake, removed on disconnect/termination
- Background refresh via `parTraverse` every 10s (reuses existing `getPeerStatus` pattern)
- `GetPeers` returns cached data immediately (<1ms) regardless of peer count

**Files changed:**
- `src/main/scala/.../network/PeerManagerActor.scala` (+45/-5)
- `src/test/.../network/PeerManagerSpec.scala` (+2/-3)

---

### Bug 12: `personal_sendTransaction` MissingNodeException During Sync (Medium)
**Commit:** (this session) — fix(rpc): handle MissingNodeException in personal_sendTransaction during sync

**Problem:** `personal_sendTransaction` throws generic `-32603 Internal Error` when state is unavailable during sync.

**Root cause:** `getCurrentAccount()` calls `blockchainReader.getAccount()` which throws `MissingNodeException` when the state trie hasn't been downloaded yet. Unlike `eth_getBalance` and other state-dependent RPCs (fixed in Bug 9), `personal_sendTransaction` was missing the recovery handler.

**Fix:** Added `.recover { case _: MissingNodeException => Left(JsonRpcError.NodeNotFound) }` to both public `sendTransaction` overloads in `PersonalService.scala`, matching the pattern established in Bug 9.

**Files changed:**
- `src/main/scala/.../jsonrpc/PersonalService.scala` (+3 lines)
- `src/test/scala/.../jsonrpc/PersonalServiceSpec.scala` (+2 new tests)

**Tests:** 2 new tests covering both `SendTransactionWithPassphraseRequest` and `SendTransactionRequest` paths when state is unavailable.

---

## Feature: Live MCP Tools & Resources

**Commits:** (this session) — feat(mcp): expand MCP infrastructure + replace stubs with live tools/resources

Replaced all stub MCP tools and resources with live blockchain query implementations, making Fukuii the first ETC client with a functional MCP server.

**Protocol version:** Updated from `2024-11-05` to `2025-11-25` (latest stable MCP spec).

### Tools (15 live, replacing 7 stubs)

| Tool | Type | Description |
|------|------|-------------|
| `mcp_node_info` | Status | Live node version, network, build info |
| `mcp_node_status` | Status | Sync state, peer count, block numbers (actor queries) |
| `mcp_blockchain_info` | Status | Best block, total difficulty, genesis hash |
| `mcp_sync_status` | Status | Detailed sync progress with state node tracking |
| `mcp_peer_list` | Status | Connected peers with addresses, direction, status |
| `mcp_etherbase_info` | Info | Etherbase configuration documentation |
| `mcp_mining_rpc_summary` | Info | Mining RPC endpoint documentation |
| `get_block` | Query | Block by number/hash/"latest" with full header |
| `get_transaction` | Query | Transaction location by hash |
| `get_account` | Query | Account state (nonce, balance) at best block |
| `detect_reorg` | ETC | Parent hash consistency check for reorgs |
| `convert_units` | ETC | Wei/Gwei/ETC conversion |
| `get_etc_emission` | ETC | Emission schedule and current era (ECIP-1017) |
| `get_etc_forks` | ETC | ECIP fork history with activation status |
| `get_chain_config` | ETC | Full blockchain config as structured output |

All tools annotated with `readOnlyHint`, parameterized tools with JSON Schema `inputSchema` and `idempotentHint`.

### Resources (9 live, replacing 6 stubs)

| Resource URI | Type | Description |
|-------------|------|-------------|
| `fukuii://node/status` | Static | Live node status JSON |
| `fukuii://node/config` | Static | Blockchain config values |
| `fukuii://sync/status` | Static | Live sync progress |
| `fukuii://peers/connected` | Static | Connected peer list |
| `fukuii://mining/rpc` | Static | Mining RPC method documentation |
| `fukuii://blockchain/latest` | Static | Latest block header |
| `fukuii://block/{number}` | Templated | Block by number |
| `fukuii://tx/{hash}` | Templated | Transaction by hash |
| `fukuii://account/{address}` | Templated | Account state by address |

### Infrastructure Changes

- `McpService` expanded with `blockchainReader`, `blockchainConfig`, `nodeStatusHolder`, `transactionMappingStorage` dependencies
- `McpDependencies` bundle case class for passing deps to tool/resource registries
- `McpToolAnnotations` case class for MCP 2025-11-25 tool annotations
- `NodeBuilder.McpServiceBuilder` expanded with `BlockchainBuilder`, `BlockchainConfigBuilder`, `NodeStatusBuilder`, `StorageBuilder` mixins

**Files changed:**
- `src/main/scala/.../jsonrpc/McpService.scala` — Expanded constructor, protocol version, annotations
- `src/main/scala/.../jsonrpc/mcp/McpTools.scala` — 15 live tools (complete rewrite)
- `src/main/scala/.../jsonrpc/mcp/McpResources.scala` — 9 live resources (complete rewrite)
- `src/main/scala/.../nodebuilder/NodeBuilder.scala` — McpServiceBuilder expanded
- Test files updated for new McpService constructor

---

## Feature: ECBP-1100 (MESS) — ECIP-1100 Polynomial Anti-Reorg Protection

**Initial commit:** `609a09e77` — feat: wire ECBP-1100 (MESS) into block processing pipeline
**Rewrite commit:** `e22db0013` — consensus: rewrite MESS to ECIP-1100 cubic polynomial (align with core-geth/Besu)

Modified Exponential Subjective Scoring (MESS) is ETC's anti-reorg protection mechanism defined in ECIP-1100. Despite "exponential" in the name, the specification uses a **capped cubic polynomial** antigravity curve applied at the fork-choice level.

The initial implementation used exponential decay (wrong algorithm). The rewrite aligns Fukuii with the ECIP-1100 spec and the other two ETC clients (core-geth, Besu).

**Architecture:** `BranchResolution` → `ArtificialFinality.shouldRejectReorg()` (stateless, at fork choice)

**Polynomial formula:**
```
polynomialV(x) = DENOMINATOR + (3x² − 2x³/xcap) × HEIGHT / xcap²
where DENOMINATOR=128, xcap=25132, HEIGHT=3840
```

**Reorg rejection condition:**
```
reject if: proposed_subchain_td × 128 < polynomialV(timeDelta) × local_subchain_td
```

**Key changes (rewrite):**
- `ArtificialFinality.scala` — NEW: stateless polynomial + reorg rejection logic
- `MESSConfig.scala` — Simplified: only `enabled`, `activationBlock`, `deactivationBlock`
- `BranchResolution.scala` — REWRITTEN: single subchain TD check at reorg decision point
- `ChainWeight.scala` — Simplified: removed `messScore` field (pure TD comparison)
- `BlockQueue.scala` — Removed per-block MESS scoring and first-seen tracking
- `MESSScorer.scala` — DELETED (replaced by stateless `ArtificialFinality`)
- `NodeBuilder.scala`, `SyncController.scala` — `MESSScorer` → `MESSConfig`
- `ChainWeightStorage.scala` — Updated legacy deserialization
- Chain configs (`etc-chain.conf`, `mordor-chain.conf`) — Activation windows:
  - ETC mainnet: block 11,380,000 → 19,250,000 (deactivated at Spiral)
  - Mordor: block 2,380,000 → 10,400,000

**Tests:** 25 unit tests (`MESSConfigSpec`: 10 activation window tests, `ArtificialFinalitySpec`: 15 polynomial + reorg rejection tests) covering spec reference values, cross-client vectors, and boundary conditions.

---

## Feature: Gas Limit Convergence (Critical — Pre-Olympia Requirement)

**Commit:** `2cc224c62` — fix: implement gas limit convergence toward configurable target

**Problem:** `BlockGeneratorSkeleton.calculateGasLimit()` was a no-op — it returned `parentGas` unchanged. This meant Fukuii miners would never adjust the gas limit toward any target. Post-Olympia (when the target increases from 8M to 60M), Fukuii-mined blocks would stay at 8M forever, causing the client to fall behind core-geth and Besu miners who actively converge.

**Root cause:** The method was a placeholder stub: `protected def calculateGasLimit(parentGas: BigInt): BigInt = parentGas`

**Fix:** Implemented ±1/1024 per-block convergence matching core-geth's `CalcGasLimit()` (`core/block_validator.go:156-177`) and Besu's `TargetingGasLimitCalculator`. Added `gasLimitTarget` config field to `MiningConfig` (default: 8M for ETC mainnet).

**Algorithm:**
```
delta = parentGas / GasLimitBoundDivisor - 1  (GasLimitBoundDivisor = 1024)
if parentGas < target: next = min(parentGas + delta, target)
if parentGas > target: next = max(parentGas - delta, target)
if parentGas == target: next = parentGas  (stable)
```

**Cross-client verification:** All three clients converge from 8M to 60M in exactly **2,055 blocks** (~7.4 hours at 13s/block).

**Files changed:**
- `src/main/scala/.../consensus/mining/MiningConfig.scala` — Added `gasLimitTarget: BigInt` field + config parsing
- `src/main/scala/.../consensus/blocks/BlockGeneratorSkeleton.scala` — Replaced no-op with convergence logic
- `src/main/resources/conf/base/mining.conf` — Added `gas-limit-target = 8000000` default
- `src/test/scala/.../consensus/mining/MiningConfigs.scala` — Updated test config constructor
- `src/test/scala/.../consensus/blocks/GasLimitCalculationSpec.scala` — **7 new tests**

**Tests (GasLimitCalculationSpec):**
| Test | Assertion |
|------|-----------|
| Converge upward from 1M to 8M | Completes in 2,122 blocks |
| Stable at target (8M) | `calcGasLimit(8M) == 8M` |
| Converge downward from 10M to 8M | Completes in 219 blocks |
| Respect ±1/1024 bound | First delta from 1M = 975 |
| MinGasLimit (5000) floor | Never drops below 5000 |
| Cross-client parity (8M→60M) | Exactly 2,055 blocks (matches core-geth + Besu) |
| Snap to target when close | Overshoots clamped to exact target |

**Reproduce before:** Set `gas-limit-target = 60000000` → mined blocks never change gas limit from parent
**Verify after:** `sbt 'testOnly *GasLimitCalculationSpec'` → 7 tests pass, convergence times match core-geth

---

## Test Suite: PoW/ETChash Consensus

**Commit:** `0a9434088` — test: add comprehensive PoW/ETChash test suite

25 unit tests + 16 live RPC validation tests covering Ethash mining and verification:

| Test Class | Tests | Coverage |
|------------|-------|----------|
| `EthashEpochBoundarySpec` | 9 | ECIP-1099 epoch transitions (30K→60K), seed consistency, cache/DAG size, Mordor boundary |
| `EthashDifficultyCalculatorSpec` | 9 | Difficulty adjustment (fast/slow blocks), min difficulty, bomb pause/continue/removal, uncle-aware post-Atlantis, calculator dispatch |
| `BlockRewardCalculatorSpec` | +4 | Era 4 (2.048 ETC) and Era 5 (1.6384 ETC) block rewards, ommer rewards, era boundary transitions |
| `MordorPoWMiningSpec` | 8 | Live RPC validation of real Mordor block headers against epoch/difficulty/PoW/reward calculations |
| `MainnetPoWMiningSpec` | 8 | Live RPC validation of real ETC mainnet block headers |

The live RPC tests validate against real chain data, confirming that Fukuii's consensus calculations match blocks already accepted by the network.

---

## Test Suite: Pre-Olympia Consensus Parity

**Commit:** `a73449db6` — test: add pre-Olympia consensus parity tests

38 tests across 3 files, covering gaps identified by cross-referencing Besu (`ClassicDifficultyCalculatorsTest`, `GenesisConfigClassicTest`, `ClassicProtocolSpecsTest`) and core-geth (`difficulty_test.go`, `backward_compat_test.go`) test suites:

| Test Class | Tests | Coverage |
|------------|-------|----------|
| `ChainConfigValidationSpec` | 13 | ETC mainnet and Mordor fork block numbers, ECBP-1100 activation windows, ECIP-1099 epoch block, monetary policy era duration, fork ordering — all loaded from HOCON config |
| `GasLimitValidationSpec` | 11 | Gas limit bounds (±parent/1024), exact boundary acceptance/rejection, MinGasLimit (5000) enforcement, ETC 8M target validation, EIP-106 MaxGasLimit overflow |
| `PreOlympiaForkComplianceSpec` | 14 | Each pre-Olympia fork (Frontier→Spiral) selects correct EVM fee schedule and opcodes, Atlantis preferred over Byzantium, PUSH0 gated at Spiral, EtcForks enum ordering, EIP feature flag helpers |

---

## Cleanup: ECIP-1098/1049/1097 Removal

**Commit:** `8d5f82610` — chore: remove WITHDRAWN IOHK/Mantis ECIPs and fix flaky tests

Removed three WITHDRAWN Mantis-era ECIPs that do not belong in a canonical ECIP-1066 implementation:

| ECIP | Name | What Was Removed |
|------|------|-----------------|
| ECIP-1098 | Proto-Treasury (80/20 split) | Treasury config, block reward splitting, treasury address handling, 26 treasury-specific tests |
| ECIP-1049 | Keccak256 PoW | Keccak mining config, SHA-3 difficulty calculator, Keccak-specific tests |
| ECIP-1097 | Checkpointing (Ouroboros BFT) | CheckpointingProtocol, `ChainWeight.lastCheckpointNumber` field, `BlockHeader.extraFields`/`HefEmpty`, checkpointing RPC API, 7 integration tests |

**Scope:** 129 files changed, ~5,286 lines deleted. The `BlockHeader` case class was simplified from 16 to 15 fields (removed `extraFields`), and `ChainWeight` from 3 to 2 fields (removed `lastCheckpointNumber`).

**Also fixed 2 pre-existing flaky tests:**
- `PoWMiningCoordinatorSpec`: Made `Miner` injectable via `minerOpt: Option[Miner]` parameter on `PoWMiningCoordinator`. Tests now use `InstantMiner` (bypasses ~1GB Ethash DAG generation). Previously tagged `FlakyTest`, now reliable (54ms/49ms).
- `SyncControllerSpec`: Fixed stale boopickle-generated pickler classes that referenced deleted `HefEmpty` singleton. Resolved by `sbt clean`.

---

## Config Fix: Stale Bootstrap Nodes

**Commit:** `a8ecb370d` — fix: replace stale Mantis-era bootstrap nodes with current core-geth entries

**Problem:** Fukuii inherited ~57 stale bootstrap nodes from the Mantis era (27 for Mordor, 30 for ETC mainnet). None overlapped with core-geth's current bootnode lists. Result: peer discovery failed completely — the node could not find any peers on Mordor.

**Fix:** Replaced both bootnode lists with core-geth's current entries:
- **Mordor:** 1 ETC Cooperative bootnode (was 27 stale)
- **ETC mainnet:** 3 ETC Cooperative bootnodes (was 30 stale)

**Files changed:**
- `src/main/resources/conf/base/chains/mordor-chain.conf` — bootstrap-nodes updated
- `src/main/resources/conf/base/chains/etc-chain.conf` — bootstrap-nodes updated

**Verify:** Start on Mordor → peers connect within 30 seconds (was: never)

---

## What Was NOT Changed

- No architecture changes (except adding sync-dispatcher isolation)
- No modifications to consensus-critical validation code (EVM execution, Ethash mining, state trie operations, block validation logic remain identical). Gas limit convergence is miner policy, not consensus validation.
- No changes to submodules (bytes, crypto, rlp, scalanet)
- No changes to CI/CD functionality (apt-key modernization and GHCR path fix are non-functional)

**What WAS updated:**
- Bouncy Castle 1.82 → 1.83 (security library, Nov 2025 release)
- Netty 4.1.115.Final → 4.1.131.Final (networking, Feb 2026 release)

**What WAS removed:**
- 3 WITHDRAWN IOHK/Mantis ECIPs (ECIP-1098 proto-treasury, ECIP-1049 Keccak256 PoW, ECIP-1097 checkpointing) — 129 files, ~5,286 lines deleted. These are not part of any canonical ECIP-1066 implementation.
- 57 stale Mantis-era bootstrap nodes replaced with current core-geth entries (4 total)
- `MESSScorer.scala` — replaced by stateless `ArtificialFinality` (ECIP-1100 polynomial rewrite)

**What WAS added beyond bug fixes:**
- ECBP-1100 (MESS) wiring into block processing pipeline, then rewritten to match ECIP-1100 cubic polynomial spec (aligns with core-geth/Besu)
- Gas limit convergence toward configurable target (feature, commit 13) — prerequisite for Olympia
- 73 new consensus tests across 4 test suites (commits 10-11, 13, 23)
- Injectable Miner pattern for reliable PoW test infrastructure
- Live MCP tools (15 tools, 9 resources) replacing stub implementations — Fukuii is the first ETC client with a functional MCP server
- Bug 12: `personal_sendTransaction` MissingNodeException handling during sync
- README accuracy pass: fixed CI badge URL, MCP tool count (16→15), network names, broken doc links, ECIP-1066 compliance reference, markdown heading structure
- Production readiness sweep: renamed 62 stale `chordodes_fukuii` → `fukuii` references, modernized CI apt-key to signed-by keyring (6 workflows), renamed misleading logback config, deduplicated 3 CONTRIBUTING.md copies, fixed confused rebrand text ("Fukuii to Fukuii" → "Mantis to Fukuii"), hardened ~20 shell scripts (shared helper library, docker compose detection, JAR validation, subshell scoping fix)
- Test count: 2,195 (down from 2,229 baseline due to removal of ~130 ECIP-specific tests, offset by 85 new tests: 73 consensus + 10 sync + 2 personal_sendTransaction)

---

## How to Verify

### 1. Build and Test
```bash
git checkout alpha
sbt compile          # Should complete cleanly
sbt test             # 2,195 tests, 0 failures
sbt assembly          # Produces target/scala-3.3.4/fukuii-assembly-0.1.240.jar
```

### 2. Mordor Testnet Smoke Test
```bash
java -Xmx4g \
  -Dfukuii.datadir=/tmp/fukuii-mordor-test \
  -Dfukuii.network.rpc.http.interface=0.0.0.0 \
  -Dfukuii.network.rpc.http.port=8553 \
  -Dfukuii.network.server-address.port=30305 \
  -Dfukuii.network.discovery.port=30305 \
  -jar target/scala-3.3.4/fukuii-assembly-0.1.240.jar mordor
```

**Verify:**
- Logs show "Using network mordor" (not "etc") — Bug 1 fixed
- `curl -X POST http://localhost:8553 -H "Content-Type: application/json" -d '{"jsonrpc":"2.0","method":"eth_chainId","params":[],"id":1}'` → `"result":"0x3f"` (63 = Mordor)
- RPC responds during sync — Bug 8 fixed
- `eth_blockNumber` matches sync progress — Bug 4 fixed

### 3. Key Bug Verifications
```bash
# Bug 5: Malformed request → proper JSON-RPC error (not plain text)
curl -X POST http://localhost:8553 -H "Content-Type: application/json" -d 'not json'

# Bug 6: Null id preserved
curl -X POST http://localhost:8553 -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"eth_chainId","params":[],"id":null}'
# Should return "id": null, not "id": 0

# Bug 9: State methods during sync return proper error
curl -X POST http://localhost:8553 -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"eth_call","params":[{"to":"0x0000000000000000000000000000000000000000"},"latest"],"id":1}'
# Should return -32016 "State node doesn't exist" (not -32603 internal error)
```

---

## Next Steps

The `alpha` branch establishes a stable pre-Olympia baseline with live MCP tooling.

### `olympia` branch (PR #1001) — Olympia Hard Fork
Implements ECIP-1111/1112/1121: EIP-1559 fee market with treasury redirect, EVM modernization (TLOAD/TSTORE, MCOPY, BASEFEE), new precompiles (P256VERIFY), and Type-4 transactions (EIP-7702). Targets Mordor block 15,800,850 (~March 28, 2026). **Rebased on alpha HEAD** — inherits gas limit convergence and live MCP tools; the olympia branch overrides the default target to 60M.

### Configuration Note: Gas Limit Target
The `gas-limit-target` config defaults to 8M (ETC mainnet pre-Olympia). On the `olympia` branch, this should be updated to 60M (60,000,000) to match core-geth's `DefaultConfig.GasCeil` and Besu's `NetworkDefinition.CLASSIC.targetGasLimit`. Operators upgrading to Olympia should set:
```hocon
mining {
  gas-limit-target = 60000000
}
```

---

## Production Release Checklist

Items to address before the first tagged production release:

### Configuration
- [ ] Change `pekko.loglevel` from `"DEBUG"` to `"INFO"` in `src/main/resources/conf/base/pekko.conf` (DEBUG generates excessive actor lifecycle noise in production; `PEKKO_LOGLEVEL` env var still allows override)
- [ ] Review `TODO(production)` comments in config files (`grep -rn 'TODO(production)' src/main/resources/`)

### Documentation
- [ ] Update version number across all docs if bumped from 0.1.240
- [ ] Verify CHANGELOG.md covers all changes since last release

### CI/CD
- [ ] Verify GHCR image builds succeed with new `chippr-robotics/fukuii` path (renamed from `chordodes_fukuii`)
- [ ] Test release workflow end-to-end with a pre-release tag

### Security
- [ ] Run `sbt dependencyCheck` for CVE scan
- [ ] Verify no secrets in git history (`git log --all -p | grep -i 'password\|secret\|private.key'`)
