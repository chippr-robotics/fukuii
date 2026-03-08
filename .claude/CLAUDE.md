# Fukuii - Ethereum Classic Client

**Status:** ALPHA — active bug-hunting and stabilization
**Language:** Scala 3.3.4 LTS on JDK 21 LTS
**Build:** sbt 1.10.7
**License:** Apache 2.0
**Origin:** Fork of Mantis (IOHK), maintained by Chippr Robotics LLC
**Package:** `com.chipprbots` (renamed from `io.iohk`)
**Branch:** `alpha` (from upstream `chippr-robotics/fukuii` main)

---

## Quick Commands

```bash
sbt compile          # Compile (~27s)
sbt test             # Unit tests (2,195 tests, ~5 min)
sbt it:test          # Integration tests
sbt assembly         # Build fat JAR (~176MB)
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
  -Dfukuii.datadir=/media/dev/2tb/data/blockchain/fukuii/mordor \
  -Dfukuii.network=mordor \
  -Dfukuii.network.rpc.http.interface=0.0.0.0 \
  -Dfukuii.network.rpc.http.port=8553 \
  -Dfukuii.network.server-address.port=30305 \
  -Dfukuii.network.discovery.port=30305 \
  -jar target/scala-3.3.4/fukuii-assembly-0.1.240.jar mordor

# ETC mainnet (fast sync only)
java -Xmx4g \
  -Dfukuii.datadir=/media/dev/2tb/data/blockchain/fukuii/etc \
  -Dfukuii.network=etc \
  -Dfukuii.network.rpc.http.interface=0.0.0.0 \
  -Dfukuii.network.rpc.http.port=8553 \
  -Dfukuii.network.server-address.port=30305 \
  -Dfukuii.network.discovery.port=30305 \
  -Dfukuii.sync.do-fast-sync=true \
  -Dfukuii.sync.do-snap-sync=false \
  -jar target/scala-3.3.4/fukuii-assembly-0.1.240.jar etc

# ETC mainnet (SNAP sync)
java -Xmx4g \
  -Dfukuii.datadir=/media/dev/2tb/data/blockchain/fukuii/etc \
  -Dfukuii.network=etc \
  -Dfukuii.network.rpc.http.interface=0.0.0.0 \
  -Dfukuii.network.rpc.http.port=8553 \
  -Dfukuii.network.server-address.port=30305 \
  -Dfukuii.network.discovery.port=30305 \
  -Dfukuii.sync.do-fast-sync=true \
  -Dfukuii.sync.do-snap-sync=true \
  -jar target/scala-3.3.4/fukuii-assembly-0.1.240.jar etc
```

Config path: `-Dfukuii.<key>=<value>` (HOCON, namespaced under `fukuii`)

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
  blockchain/sync/           # Sync controllers (fast, SNAP, regular)
    fast/                    # FastSync, PivotBlockSelector
    snap/                    # SNAPSyncController, coordinators, workers
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

## Alpha Bugs Fixed (committed on `alpha` branch)

1. **Config cache poisoning** — `ConfigFactory.invalidateCaches()` + fixed HOCON include paths
2. **SNAP fallback acceleration** — Consecutive pivot refresh counter (6min vs 75min). Don't reset counter in `restartSnapSync()` so it accumulates properly.
3. **FastSync best block hash** — Track correct best block during sync
4. **Actor name collision** — Generation counter for unique actor names on sync restart
5. **JSON-RPC error format** — Proper error responses for malformed requests; null id coercion via `getOrElse(JNull)`
6. **RPC starvation (CRITICAL)** — Sync actors on dedicated `sync-dispatcher`, freeing default dispatcher for HTTP/TCP I/O
7. **SNAP capability check** — Verify snap/1 peer availability before starting account sync, with grace period fallback to fast sync
8. **SNAP stagnation watchdog** — Track `accountsDownloaded` as liveness signal (not just task completions), preventing false stagnation on slow ranges
9. **SNAP partial range resume** — Preserve `task.next` positions across pivot refreshes via `AccountRangeProgress` message + `postStop()` snapshot. 256-block safety valve.
10. **SNAP dynamic concurrency** — Cap workers to `min(accountConcurrency, snapPeerCount)` — 1:1 worker-to-snap-peer mapping prevents peer flooding
11. **SNAP in-place pivot refresh** — `PivotRefreshed` message updates coordinator's state root without stop/restart, preserving progress seamlessly
12. **SNAP stale peer accumulation** — Deduplicate `knownAvailablePeers` by `remoteAddress` on peer reconnection. Prevents inflated `activePeerCount` from stale entries across all 3 coordinators.
13. **SNAP false stateless after pivot refresh** — `handleTaskFailed` unconditionally marked peers stateless, even when failures came from stale-root in-flight requests after pivot refresh. Added `task.rootHash == stateRoot` guard.
14. **SNAP OOM + progress persistence** — `DeferredWriteMptStorage` kept ALL trie nodes in memory, only flushing once at finalization. ~420 bytes/account caused OOM at 9.5M (4GB) and 19.3M (8GB). Fix: periodic flush after each response batch (~32K accounts), bounding peak memory to ~13MB. Also wired `AppStateStorage.putSnapSyncProgress` for disk persistence with crash recovery (256-block pivot safety valve).
15. **SNAP bootstrap retry + log file resilience** — Bootstrap retry loop (the `LocalPivot` branch in `startSnapSync()`) ran at fixed 2s interval indefinitely with no peers — separate code path from the `PivotStateUnservable` fallback logic, so existing fast sync fallback never triggered. Added exponential backoff (2s->60s cap), 5-minute timeout to fast sync fallback, and periodic diagnostic logging. Also added `ResilientRollingFileAppender` to recreate log file if deleted while running.

---

## Test Tiers

| Tier | Command | Duration | When |
|------|---------|----------|------|
| Essential | `sbt test` | <5 min | Every change |
| Standard | `sbt it:test` | <30 min | Before PR |
| Comprehensive | `sbt pp` | <3 hr | Pre-merge |
| EVM | `sbt evmTest:test` | Variable | Consensus changes |

---

## Boundaries

### Always Do
- Run `sbt compile` and `sbt test` before committing
- Follow existing package naming (`com.chipprbots`)
- Use ScalaTest for all tests
- Add `.withDispatcher("sync-dispatcher")` when creating sync actor children
- Respect consensus-critical code boundaries (EVM, Ethash, state trie)

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
