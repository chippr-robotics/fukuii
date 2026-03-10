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

### Sync

1. **Config loading** — `ConfigFactory.invalidateCaches()` + fixed HOCON include paths for non-default networks.
2. **Fast sync best block hash + JSON-RPC error format** — Track correct best block during sync. Proper error responses for malformed requests; null id coercion via `getOrElse(JNull)`.
3. **Actor name collision on sync restart** — Generation counter for unique actor names prevents crashes on sync restart.
4. **Block body download stall** — Peers timeout on `GetBlockBodies`, retry loop re-queues to same blacklisted peer. Fix: `ExcludingPeers` selector, exponential backoff, `maxBodyFetchRetries` limit.

### RPC

5. **RPC unavailability during sync** — Three related fixes: (a) Sync actors monopolized default dispatcher, starving HTTP/TCP I/O. Isolated to dedicated `sync-dispatcher`. (b) `eth_call`, `eth_estimateGas`, `eth_getCode` threw unhandled `MissingNodeException` during sync. Added `.recover` handlers. (c) Same pattern missing in `personal_sendTransaction`. (d) `net_listPeers` with 30+ peers exceeded 20s timeout. In-process `peerStatusCache` updated reactively (<1ms).

### SNAP Sync

6. **SNAP peer and pivot resilience** — SNAP sync couldn't handle peer instability or pivot staleness. Seven related fixes: (a) No escape hatch when peers lacked snapshots — added exponential backoff (2s→60s), 5-minute timeout, graceful fast sync fallback. (b) Pivot refresh counter reset on restart. (c) Snap/1 capability check with grace period. (d) Stagnation watchdog tracked task completions instead of `accountsDownloaded` — false stagnation on slow ranges. (e) Partial range resume — preserve `task.next` positions across pivot refreshes via `AccountRangeProgress` message, 256-block safety valve. (f) Dynamic concurrency — cap workers to `min(config, snapPeerCount)`, 1:1 mapping prevents peer flooding. (g) In-place pivot refresh via `PivotRefreshed` message, zero-downtime root update. (h) Stale peer dedup by `remoteAddress` across all 3 coordinators. (i) Stale-root guard — `task.rootHash == stateRoot` prevents marking good peers as stateless after pivot refresh.
7. **SNAP OOM on large state** — Two unbounded memory sources: (a) `DeferredWriteMptStorage` held all trie nodes in memory (~420 bytes/account, OOM at 9.5M). Fix: periodic flush after each response batch. (b) `contractAccounts`/`contractStorageAccounts` ArrayBuffers grew unbounded (~45M entries on ETC, ~85% contracts due to pre-Mystique GasToken bloat). Fix: file-backed storage with 64-byte entries, temp files cleaned on stop.
8. **Log file resilience** — `ResilientRollingFileAppender` recreates log file if deleted while running. Standard `RollingFileAppender` writes to dangling inode — logs silently lost.
9. **SNAP post-download pipeline failures** — Four related failures after account download completes: (a) Phase handoff timeout — 5s ask to read 73.5M contract accounts (4.7GB file) timed out, silently skipping bytecode+storage sync. Fix: Bloom filter dedup (73.5M→2M codeHashes), storage streaming in 10K batches, concurrent bytecode+storage phases, recovery actors. (b) `StorageConsistencyChecker` crash — assumed all headers 0..bestBlock exist; after SNAP only pivot header stored. Skip when `SnapSyncDone=true`. (c) Recovery actors dropped SNAP responses via `case _ => // Ignore`. Added message forwarding + `PollRecoveryPeers` scheduler. (d) State validation short-circuit — `visited` set collision in `MptStorage.decodeNode()` where HashNode and resolved node share same hash, causing traversal to stop at depth 1. Fixed in all 4 trie walk methods; treat unreachable nodes as missing instead of silently swallowing.

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
