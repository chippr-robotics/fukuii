# Fukuii Alpha Stabilization — Handoff Document

**Branch:** `alpha` (derived from `main` at v0.1.240)
**Author:** Christopher Mercer (chris-mercer) + Claude Opus 4.6
**Date:** 2026-03-03
**Commits:** 12 (6 bug fixes + 1 chore + 1 multi-fix + 1 feature + 2 test suites + 1 docs)

---

## Summary

The `alpha` branch is a systematic stabilization pass over Fukuii v0.1.240. Over 16 phases of testing, every major subsystem was exercised on both Mordor testnet and ETC mainnet using the assembly JAR. **9 bugs were found and fixed**, then the branch was extended with ECBP-1100 (MESS) wiring and comprehensive consensus test suites.

**Test results:** 2,267+ unit tests passing, clean compile, assembly JAR verified on Mordor.

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

## Feature: ECBP-1100 (MESS) Wiring

**Commit:** `609a09e77` — feat: wire ECBP-1100 (MESS) into block processing pipeline

Modified Exponential Subjective Scoring (MESS) is ETC's anti-reorg protection mechanism defined in ECBP-1100. This commit wires the existing MESS scoring implementation into the block processing pipeline so it actively participates in branch resolution decisions.

**Architecture:** `BlockQueue` → `MESSScorer` → `BranchResolution`

**Key changes:**
- `MESSConfig.scala` — Configuration parsing for activation/deactivation block windows
- `MESSScorer.scala` — Scoring implementation (exponential decay, first-seen recording)
- `BlockQueue.scala` — Integration point: MESS scores applied during block import
- `BranchResolution.scala` — Scoring influences chain selection
- `SyncController.scala` — Passes MESS config to sync subsystem
- `NodeBuilder.scala` — Wires MESS into the node dependency graph
- Chain configs (`etc-chain.conf`, `mordor-chain.conf`) — Activation windows:
  - ETC mainnet: block 11,380,000 → 19,250,000
  - Mordor: block 2,380,000 → 10,400,000

**Tests:** 22 unit tests (`MESSConfigSpec` + `MESScorerSpec`) covering config validation, activation windows, scoring, decay, and first-seen recording.

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

## Known Issues (Not Fixed)

These were discovered during testing but are tuning/architecture issues, not code bugs:

### Block Body Download Stall
- **Symptom:** Fast sync stalls at a specific block when peers timeout on `GetBlockBodies`
- **Cause:** Retry loop re-queues same hashes to same (blacklisted) peers without exponential backoff
- **Workaround:** Restart the node; it resumes from last checkpoint
- **Proper fix:** Add exponential backoff for repeated timeouts, differentiate timeout vs protocol blacklisting, add peer rotation

### `net_listPeers` Timeout Under Load
- **Symptom:** With 30+ peers, `net_listPeers` times out after 20s
- **Cause:** `PeerManagerActor.GetPeers` uses `parTraverse` to query each peer actor status (2s timeout per peer). With 30 peers, worst case is 30×2s = 60s > 20s ask timeout.
- **Workaround:** Use `net_peerCount` instead (counts handshaked peers without individual status queries)
- **Proper fix:** Cache peer status locally instead of querying each actor on every request

### MCP Tools Return Stubs
- All 7 MCP tools return hardcoded placeholder text, not live data
- The MCP framework and routing work correctly — stubs are the original implementation
- **Resolved:** PR #999 (`enterprise` branch) replaces all stubs with 20 live MCP tools + 9 resources

### `personal_sendTransaction` During Sync
- Returns generic error when state isn't available
- Consistent with other state-dependent RPCs — they now all return proper errors after Bug 9 fix

---

## What Was NOT Changed

- No dependency version updates
- No architecture changes (except adding sync-dispatcher isolation)
- No modifications to consensus-critical calculation code (EVM execution, Ethash mining, state trie operations, block validation logic remain identical)
- No changes to submodules (bytes, crypto, rlp, scalanet)
- No changes to CI/CD workflows or Docker configs

**What WAS added beyond bug fixes:**
- ECBP-1100 (MESS) wiring into block processing pipeline (feature, commit 9)
- 63 new consensus tests across 3 test suites (commits 10-11)
- Test count: 2,267+ (up from 2,229 baseline)

---

## How to Verify

### 1. Build and Test
```bash
git checkout alpha
sbt compile          # Should complete cleanly
sbt test             # 2,267+ tests, 0 failures
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

The `alpha` branch establishes a stable pre-Olympia baseline. Two independent branches build on it:

### `enterprise` branch (PR #999) — MCP Live Tools
Replaces all stub MCP tools with 20 live blockchain query tools and 9 resources. Makes Fukuii the first ETC client with a live MCP server. Further enterprise sprints planned: WebSocket subscriptions, TLS/JWT auth, structured logging, trace/debug APIs.

### `olympia` branch (PR #1001) — Olympia Hard Fork
Implements ECIP-1111/1112/1121: EIP-1559 fee market with treasury redirect, EVM modernization (TLOAD/TSTORE, MCOPY, BASEFEE), new precompiles (P256VERIFY), and Type-4 transactions (EIP-7702). Targets Mordor block 15,800,850 (~March 28, 2026).

Both branches fan out independently from `alpha` HEAD and can be merged separately.
