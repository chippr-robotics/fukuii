# Fukuii - Ethereum Classic Client

**Status:** ALPHA — active bug-hunting and stabilization | OLYMPIA hard fork implementation (ECIP-1111/1112/1121)
**Language:** Scala 3.3.7 LTS on JDK 21 LTS
**Build:** sbt 1.10.7
**License:** Apache 2.0
**Origin:** Fork of Mantis (IOHK), maintained by Chippr Robotics LLC
**Package:** `com.chipprbots` (renamed from `io.iohk`)

---

## Quick Commands

```bash
sbt compile          # Compile (~27s)
sbt test             # Unit tests (~9 min)
sbt it:test          # Integration tests
sbt assembly         # Build fat JAR
sbt scalafmtAll      # Format all code
sbt pp               # Pre-PR: format + style + tests
```

### Running the Node

**CRITICAL:** Use the assembly JAR, not `sbt run` (sbt kills the process after 1-3s).

```bash
# Build JAR first
sbt assembly

# Mordor testnet (fast sync)
java -Xmx4g \
  -Dfukuii.datadir=<datadir>/mordor \
  -Dfukuii.network=mordor \
  -Dfukuii.network.rpc.http.interface=0.0.0.0 \
  -Dfukuii.network.rpc.http.port=8553 \
  -Dfukuii.network.server-address.port=30305 \
  -Dfukuii.network.discovery.port=30305 \
  -jar target/scala-3.3.7/fukuii-assembly-*.jar mordor

# ETC mainnet (fast sync only)
java -Xmx8g \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=200 \
  -XX:G1HeapRegionSize=16m \
  -XX:InitiatingHeapOccupancyPercent=40 \
  -Dfukuii.datadir=<datadir>/etc \
  -Dfukuii.network=etc \
  -Dfukuii.network.rpc.http.interface=0.0.0.0 \
  -Dfukuii.network.rpc.http.port=8553 \
  -Dfukuii.network.server-address.port=30305 \
  -Dfukuii.network.discovery.port=30305 \
  -Dfukuii.sync.do-fast-sync=true \
  -Dfukuii.sync.do-snap-sync=false \
  -jar target/scala-3.3.7/fukuii-assembly-*.jar etc

# ETC mainnet (SNAP sync) — uses a config file for all overrides
# NOTE: Do NOT pass a network name (etc/mordor) when using -Dconfig.file.
# App.scala's setNetworkConfig() clears config.file when a network arg is present,
# discarding all overrides. The network is set inside the config file instead.
# Config file MUST use: include classpath("application.conf")
# NOT: include "app.conf" or include classpath("conf/app.conf") — both silently fail
# when the file is loaded from the filesystem (TypesafeConfig classpath resolution quirk).
java -Xmx8g \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=200 \
  -XX:G1HeapRegionSize=16m \
  -XX:InitiatingHeapOccupancyPercent=40 \
  -Dconfig.file=/path/to/run.conf \
  -jar target/scala-3.3.7/fukuii-assembly-*.jar
```

Config path: `-Dfukuii.<key>=<value>` (HOCON, namespaced under `fukuii`)

### Chain Import

Import pre-built chain data (RLP-encoded blocks) at startup:
```bash
java -Xmx4g \
  -Dfukuii.import-chain-file=/path/to/chain.rlp \
  -jar target/scala-3.3.7/fukuii-assembly-*.jar mordor
```

Blocks are executed through the standard block validation + execution pipeline and persisted with receipts and chain weight. Used by hive test framework and for bootstrapping from exported chain data.

---

## Startup Sequence

1. **Metrics + DB fix + Genesis + Chain import** — essential initialization
2. **JSON-RPC + Engine API** — API servers bind synchronously (user-facing)
3. **P2P + Discovery** — networking layer
4. **Sync + Mining** — background work
5. **DB consistency check + TUI** — non-critical maintenance

Engine API binds synchronously with 10s timeout — fails loudly if port is unavailable.

---

## Network Ports (multi-client setup)

| Client | HTTP RPC | WS RPC | P2P | Discovery |
|--------|----------|--------|-----|-----------|
| core-geth | 8545 | 8546 | 30303 | 30303 |
| besu | 8548 | 8549 | 30304 | 30304 |
| fukuii | 8553 | 8552 | 30305 | 30305 |

**IMPORTANT:** Discovery port (`network.discovery.port`) defaults to 30303. When running alongside core-geth, MUST set `-Dfukuii.network.discovery.port=30305` to avoid UDP bind conflict.

Health endpoints: `GET /health`, `GET /readiness`, `GET /healthcheck`, `GET /buildinfo`

---

## Project Structure

```
src/main/scala/com/chipprbots/ethereum/
  blockchain/data/           # Genesis loading, chain import
    ChainImporter.scala      # RLP chain file import (chain.rlp)
    GenesisDataLoader.scala  # Genesis block creation from JSON
  blockchain/sync/           # Sync controllers (fast, SNAP, regular)
    fast/                    # FastSync, PivotBlockSelector
    snap/                    # SNAPSyncController, coordinators, workers
  consensus/engine/          # Engine API (post-merge CL interface)
  jsonrpc/                   # JSON-RPC API
    server/http/             # HTTP server (Pekko HTTP)
    server/controllers/      # RPC method handlers
  network/                   # P2P networking, peer management
    discovery/               # Peer discovery (UDP)
    rlpx/                    # RLPx protocol
  utils/Config.scala         # All config classes
src/main/resources/conf/
  base/                      # Base configs (network, sync, pekko, etc.)
  etc.conf, mordor.conf      # Network-specific overrides
src/test/                    # Unit tests (ScalaTest)
src/it/                      # Integration tests
docker/                      # 6 Dockerfile variants
bytes/, crypto/, rlp/, scalanet/  # Submodules
```

---

## Key Architecture

- **Actor system:** Apache Pekko 1.1.2 (actors), Pekko HTTP 1.1.0
- **IO runtime:** cats-effect 3 (IORuntime.global for JSON-RPC handlers)
- **Database:** RocksDB for blockchain state
- **Dispatchers (pekko.conf):**
  - `default-dispatcher` — HTTP server, TCP I/O, general actors
  - `sync-dispatcher` — All sync actors (SNAP, fast, regular) isolated here to prevent RPC starvation
  - `validation-context` — Concurrent header validation threadpool

### Sync Modes
- **SNAP sync:** Tries SNAP protocol first, falls back to fast sync after 3 consecutive empty pivot refreshes
- **Fast sync:** Downloads headers → state → block bodies, requires `min-peers-to-choose-pivot-block` (default 3) for pivot consensus
- **Regular sync:** Block-by-block from genesis
- **Bootstrap checkpoints:** Stored pivot from hardcoded checkpoints (Spiral: block 19,250,000 for ETC)

---

## Resolved Issues (SNAP Sync)

The following bugs were identified and fixed during ETC mainnet SNAP sync development. This list provides context for why the code is structured the way it is.

1. **Config cache poisoning** — `ConfigFactory.invalidateCaches()` + fixed HOCON include paths for non-default networks.
2. **SNAP fallback resilience** — Two code paths to `fallbackToFastSync()`: (a) consecutive pivot refresh counter, (b) bootstrap retry with exponential backoff (2s→60s cap) and 5-minute timeout.
3. **FastSync best block hash** — Track correct best block during sync.
4. **JSON-RPC error format** — Proper error responses for malformed requests; null id coercion via `getOrElse(JNull)`.
5. **Actor name collision** — Generation counter for unique actor names on sync restart.
6. **RPC starvation (CRITICAL)** — Sync actors on dedicated `sync-dispatcher`, freeing default dispatcher for HTTP/TCP I/O.
7. **MissingNodeException in State RPCs** — `eth_call`, `eth_estimateGas`, `eth_getCode` threw unhandled `MissingNodeException` during sync. Added `.recover` matching the `eth_getBalance` pattern.
8. **Block body download stall** — Peers timeout on `GetBlockBodies`, retry loop re-queues to same blacklisted peer. Fix: `ExcludingPeers` selector, exponential backoff, `maxBodyFetchRetries` limit.
9. **net_listPeers timeout** — With 30+ peers, `parTraverse` exceeded 20s RPC timeout. Fix: in-process `peerStatusCache` updated reactively, `GetPeers` returns cached data (<1ms).
10. **personal_sendTransaction MissingNodeException** — Same pattern as #7, missing in `PersonalService.scala`.
11. **SNAP capability check** — Verify snap/1 peer availability before starting account sync, with grace period fallback to fast sync.
12. **SNAP stagnation watchdog** — Track `accountsDownloaded` as liveness signal (not just task completions), preventing false stagnation on slow ranges.
13. **SNAP partial range resume** — Preserve `task.next` positions across pivot refreshes via `AccountRangeProgress` message + `postStop()` snapshot. 256-block safety valve.
14. **SNAP dynamic concurrency** — Cap workers to `min(accountConcurrency, snapPeerCount)` — 1:1 worker-to-snap-peer mapping prevents peer flooding.
15. **SNAP in-place pivot refresh** — `PivotRefreshed` message updates coordinator's state root without stop/restart, preserving progress seamlessly.
16. **SNAP stale peer accumulation** — Deduplicate `knownAvailablePeers` by `remoteAddress` on peer reconnection.
17. **SNAP false stateless after pivot refresh** — `handleTaskFailed` unconditionally marked peers stateless on stale-root in-flight failures. Added `task.rootHash == stateRoot` guard.
18. **SNAP OOM** — Three unbounded memory sources: (a) `DeferredWriteMptStorage` held all trie nodes — periodic flush after each response batch. (b) `contractAccounts`/`contractStorageAccounts` ArrayBuffers — file-backed storage with 64-byte entries. (c) Progress persistence for crash recovery.
19. **Log file resilience** — `ResilientRollingFileAppender` recreates log file if deleted while running.
20. **SNAP post-download pipeline failures** — Four related failures: (a) Phase handoff timeout — Bloom filter dedup, storage streaming in 10K batches, concurrent bytecode+storage phases. (b) `StorageConsistencyChecker` crash after SNAP. (c) Recovery actors dropped SNAP responses. (d) State validation short-circuit in `MptStorage.decodeNode()`.

---

## Test Tiers

| Tier | Command | Duration | When |
|------|---------|----------|------|
| Essential | `sbt test` | <5 min | Every change |
| Standard | `sbt it:test` | <30 min | Before PR |
| Comprehensive | `sbt pp` | <3 hr | Pre-merge |
| EVM | `sbt evmTest:test` | Variable | Consensus changes |

---

## Research Workflow (for sync/network fixes)

When fixing a sync or networking bug, follow this protocol before writing any code:

**Step 1 — Search git history:** Check prior commits on this repo for work on the same symptom. Use `git log --grep` with relevant keywords.

**Step 2 — Reference clients (implement):** Read actual source in at least one production client before writing any code. Reference clients are the implementation authority.

**Architectural proximity guide:**
- JVM concurrency / actor patterns → **Besu first** (Java → Scala, same JVM model)
- Protocol mechanics, timing constants → **go-ethereum** (canonical ETH)
- High-performance patterns, cancellation → **reth** (Rust async, most modern)
- ETC chain config (forks, networkId) → **core-geth only** (only production PoW ETC client)

**Convergence signal:** 2+ clients using the same pattern = strong signal. 3+ = industry consensus. Divergence = understand the tradeoff before choosing.

---

## Boundaries

### Always Do
- Run `sbt compile` and `sbt test` before committing
- Follow existing package naming (`com.chipprbots`)
- Use ScalaTest for all tests
- Add `.withDispatcher("sync-dispatcher")` when creating sync actor children
- Respect consensus-critical code boundaries (EVM, Ethash, state trie)
- For any sync/network fix: read reference client source BEFORE proposing implementation
- Cite the reference client file and behavior in commit messages for non-trivial fixes

### Ask First
- Changes to consensus logic
- Modifying submodules (bytes, crypto, rlp, scalanet)
- Docker base image changes
- CI/CD workflow modifications

### Never Do
- Break backwards compatibility with ETC network protocol
- Commit private keys or mnemonics
- Disable tests in CI
- Use `sbt run` for long-running node operation (use JAR)
- Create sync actors without `sync-dispatcher` (causes RPC starvation)
- Propose or implement a sync/network fix without verifying against at least one reference client source
