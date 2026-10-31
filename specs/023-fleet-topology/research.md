# Research: fukuii as a fleet member (Phase 0 of spec 023)

**Date**: 2026-10-30. **Tree**: `staging` at 0f1a904 (version 0.10.17). Every figure below was produced by the grep
named beside it on this tree; re-run the grep before trusting a figure on a later tree.

This file holds (1) the re-measurement of the Evidence table in the conversation notes that produced specs 023–026,
(2) the **T-001 retirement inventory** for the in-process multi-instance runtime (spec 023 FR-006), and (3) the
baseline of surfaces that already exist and that specs 023–026 build on instead of re-inventing.

---

## 1. Evidence re-measured

| Measure (as stated in the notes) | Stated | Measured 2026-10-30 | Method | Verdict |
| --- | --- | --- | --- | --- |
| References to the global `utils.Config` object outside `runtime/` | ~82 | **35 main files** import the `Config` singleton (`import com.chipprbots.ethereum.utils.Config`); **113** including test sources. Qualified member uses `Config.<member>` in main: **35 sites in 12 files** (`Fukuii.scala` 11, `HelloExchangeState.scala` 10, `StdNode.scala` 4, `App.scala` 2, one each in `Web3Service`, `TransactionResponse`, `TransactionRequest`, `NetService`, `EthTxService`, `EngineApiController`, `SyncController`, `Config.scala`). | `grep -rln "import com.chipprbots.ethereum.utils.Config$" src/main/scala` and a `Config\.` member grep excluding the `*Config` case classes | ~82 is not reproducible as stated; the magnitude (tens of files) holds. **Irrelevant after OQ-1**: with one chain per process, a process-global config is correct. |
| `IORuntime.global` uses | 31 | **31** in 24 main files (`NodeBuilder.scala` 7; one each elsewhere: `BlockImporter`, `BlockFetcher`, `HeadersFetcher`, `BodiesFetcher`, `BodiesSliceFetcher`, `StateNodeFetcher`, `PeerManagerActor`, `DiscoveryRuntime`, `JsonRpcHttpServer`, `JsonRpcWsServer`, `JsonRpcIpcServer`, `GraphQLSchema`, `FilterManager`, `EngineApiHttpServer`, `PoWMiningCoordinator`, `MockedMiner`, `SnapSyncProgressStorage`, `HealingFrontierStorage`, `ChainImporter`, `SignedTransactionsFilterActor`, `SignedTransaction` (2), `TestNode`, `FaucetBuilder`) | `grep -rn "IORuntime.global" src/main/scala` | **Confirmed.** Still relevant inside one process: `sim` and read-serving work must not share the importer's runtime (023 DC-8; 018 FR-027). |
| Scala `object`s holding mutable state | ~120 files | **136 files** contain an `object` and a mutable marker (`var`, `Atomic*`, `mutable.*`, `ConcurrentHashMap`, `TrieMap`) anywhere in the file (loose); **65 top-level, non-companion objects** hold mutable state inside the object body (tight) | two greps, loose and tight, over `src/main/scala` | Stated figure sits between the two methods. **Irrelevant after OQ-1**: no cross-chain unwind is needed. |
| Mailboxes unbounded except one | — | **Confirmed.** `conf/base/pekko.conf` defines one `bounded-mailbox` (`NonBlockingBoundedMailbox`, capacity 50,000) for `SignedTransactionsFilterActor`; every other actor uses the unbounded default. | `grep -n mailbox src/main/resources/conf/base/pekko.conf` | Relevant inside one process (023 DC-8): a burst in one role can exhaust that process's heap. The process boundary (DC-1) turns a fleet-wide failure into a per-chain one. |
| `runtime/` size | 122 lines | **122** (`ChainInstance.scala` 32, `FukuiiRuntime.scala` 90) | `wc -l` | Confirmed. |

**Correction carried into the specs**: the global-state figures are reported as "tens of files" with the greps above,
not as the stated counts. None of them gates a decision any more (OQ-1 closed GT-0).

---

## 2. T-001 — Retirement inventory for the in-process multi-instance runtime

Scope: everything that exists **only** because several chains could run in one JVM. Verdicts follow
`.claude/agent-protocols/dead-code-review.md` (Wire it / Delete it / Defer). Items marked **KEEP** are not
multi-instance code even though a grep for "instance" finds them; they are listed so the next thread does not delete
them by mistake.

### 2.1 Code

| Path | Lines | What it is | Verdict | Note |
| --- | --- | --- | --- | --- |
| `src/main/scala/com/chipprbots/ethereum/runtime/FukuiiRuntime.scala` | 90 | Parses `fukuii-runtime.instances`, starts and stops N `ChainInstance`s | **Delete** | Whole file. `isMultiInstance` has no other caller. |
| `src/main/scala/com/chipprbots/ethereum/runtime/ChainInstance.scala` | 32 | `BaseNode with StdMiningBuilder` per instance | **Delete** | Whole file; the package `runtime/` goes with it. |
| `src/main/scala/com/chipprbots/ethereum/App.scala:264-271` | 8 | `case Some("runtime")` CLI dispatch | **Delete** | Help text (lines 144–219) does not mention `runtime`; nothing else to update there. 023 FR-001 adds `--chain` / `--role` in the same dispatcher. |
| `src/main/scala/com/chipprbots/ethereum/utils/InstanceConfig.scala:29` (`instanceId` parameter) and `:67` (fallback to `instanceId` for the network name) | 2 | The per-instance identifier | **Delete the parameter**; keep the class | `InstanceConfig` itself is the non-global configuration path and the natural home for `chainKey` and `role` (023 FR-001). Only `instanceId` is runtime plumbing. |
| `src/main/scala/com/chipprbots/ethereum/utils/InstanceConfig.scala` (`InstanceConfigProvider`) | — | Cake self-type used by every `*Builder` in `nodebuilder/` | **KEEP** | Dependency injection for the node, not multi-instance code. |
| `src/main/scala/com/chipprbots/ethereum/utils/Config.scala:25-27` | 3 | Doc comment "For multi-instance mode, create new `InstanceConfig` instances" | **Delete the sentence** | `object Config extends InstanceConfig(..., "default")` loses the second argument. |
| `src/main/scala/com/chipprbots/ethereum/nodebuilder/NodeBuilder.scala:112` | 1 | `ActorSystem(s"fukuii_${instanceConfig.instanceId}", ...)` | **Rewire** | Name becomes `fukuii` or `fukuii_<chainKey>` (023 FR-001 decides; the name appears in actor paths and logs). |
| `src/main/scala/com/chipprbots/ethereum/nodebuilder/StdNode.scala:121` | 1 | `Metrics.configure(metricsConfig, instanceConfig.instanceId)` | **Rewire** | Drop the second argument once `Metrics.configure` loses it. |
| `src/main/scala/com/chipprbots/ethereum/nodebuilder/StdNode.scala:284` | 1 | `s"PeriodicDBConsistencyCheck_${instanceConfig.instanceId}"` thread name | **Rewire** | Fixed name. |
| `src/main/scala/com/chipprbots/ethereum/metrics/Metrics.scala:79-142` | ~60 | `instances: ConcurrentHashMap[String, Metrics]`, `forInstance`, `configure(config, instanceId)` with the Bug 29 WARN, `closeInstance` | **Delete the registry**; keep `configure(config)`, `get()`, `close` | Bug 29 (every `MetricsContainer` writes to the static default registry) is **resolved by DC-1, not by a refactor**: with one chain per process the static registry is per chain. |
| `src/main/scala/com/chipprbots/ethereum/metrics/MetricsContainer.scala:6-11` | 6 | "Multi-instance caveat (Bug 29)" doc comment | **Delete the comment** | — |
| `src/main/scala/com/chipprbots/ethereum/jsonrpc/EthTxService.scala:89-90` | 2 | Comment: implicit `blockchainConfig` "so multi-instance runtime callers supply the per-instance config" | **Reword** | The implicit stays (it is the right DI); the justification changes. |
| `src/test/scala/com/chipprbots/ethereum/metrics/MetricsMultiInstanceSpec.scala` | ~45 | Locks the Bug 29 fallback semantics (`forInstance(unknown) == get()`) | **Delete** | Its purpose is to make a refactor of the registry visible; the registry is being removed instead. |
| `src/test/scala/com/chipprbots/ethereum/TestInstanceConfigProvider.scala` and the ~25 test files that use `instanceConfig` | — | Test DI of `InstanceConfig` | **KEEP** | Only the `instanceId` constructor argument, where passed, goes. |
| `src/main/scala/com/chipprbots/ethereum/ConfigValidator.scala` | — | `removedKeyWarnings` is called from `FukuiiRuntime.fromConfig` | **KEEP** | The single-instance launcher is the other caller; nothing to delete here. |

### 2.2 Configuration and deployment

| Path | What it is | Verdict | Note |
| --- | --- | --- | --- |
| `src/main/resources/conf/multi-instance.conf` | The `fukuii-runtime.instances { etc, mordor }` sample | **Delete** | Named in 018 FR-018's runbook list; 018 amendment request 6 removes it there. |
| `src/main/resources/conf/base/metrics.conf:26` | Comment: default 9095 "collides with multi-instance setups" | **Reword** | — |
| `ops/barad-dur/multi-etc-mordor/conf/multi-network.conf` | ETC + Mordor in one JVM | **Replace** | Becomes two services in `docker-compose.yml`, one per chain (the single-node operator profile of 023 DC-1), or is removed if the deployment is not used. |
| `ops/barad-dur/multi-etc-mordor/docker-compose.yml:39-50` | Mounts `multi-network.conf`, passes `-Dconfig.file=...` and the `runtime` command | **Replace** | Same. |
| `ops/barad-dur/sepolia/fukuii-conf/multi-network.conf` | Sepolia multi-instance variant | **Delete** | — |
| `ops/barad-dur/sepolia/docker-compose-multi.yml:47` | Passes `runtime` | **Delete** | "4 GB heap shared across both instances" is the anti-pattern DC-1 removes. |
| `ops/barad-dur/sepolia/docker-compose.yml:19` | Comment referring to "the runtime" | **Reword** | — |

### 2.3 Documentation

| Path | What it says | Verdict | Note |
| --- | --- | --- | --- |
| `docs/architecture/pluggable-consensus-vision.md` §"Multi-Network Runtime", §"In-Process L2 Architecture", §"Cross-Instance Communication", §"Orbita Legacy" (line 117: "An 'orbita' is a chain instance within the fukuii runtime"), §"Phase 2: Multi-Instance Runtime" | The in-process vision and the old use of the Orbita name | **Mark superseded**; keep as history | A status banner at the top points to spec 023 (done in this PR). The pluggable-consensus idea itself is not retired; its process model is. |
| `CHANGELOG.md:18` | Historical entry mentioning the runtime | **KEEP** | History. |
| `docs/deployment/kong-security.md:377` | "For multi-instance deployments, use Redis" | **KEEP (false positive)** | Kong rate-limit state across several *nodes*; nothing to do with the JVM runtime. |
| `ops/barad-dur/README.md:12,161,214` | "Multiple Fukuii instances" for HA | **KEEP (false positive)** | Several containers behind Kong; this is the topology 023 wants. |
| `specs/018-storage-engine-consolidation/spec.md` FR-018 | Lists `multi-instance.conf` among runbooks to update | **Amend** | 018 amendment request 6 (see spec 023 §"Amendment requests to spec 018"). |
| `specs/018-storage-engine-consolidation/research.md` | Mentions the runtime in passing | **KEEP** | Research record. |

### 2.4 What the inventory does not contain

- No metric *names* are specific to the runtime; `MetricsContainer` subclasses are per subsystem, not per instance.
- No RocksDB column family, AppState key or on-disk format is specific to the runtime. Retirement is datadir-neutral.
- No test outside `MetricsMultiInstanceSpec` exercises `FukuiiRuntime` or `ChainInstance`
  (`grep -rl "FukuiiRuntime\|ChainInstance" src/test src/it` finds only the metrics spec, by the bug's name).

**SC-001 check for the removal PR**: the following returns only the KEEP rows above.

```
grep -rn "fukuii-runtime\|FukuiiRuntime\|ChainInstance\|instanceId\|multi-instance" src/ ops/ docs/ \
  --include=*.scala --include=*.conf --include=*.yml --include=*.md
```

---

## 3. Surfaces that already exist (build on these; do not re-invent)

| Need (spec) | What exists today | Where | Gap the spec closes |
| --- | --- | --- | --- |
| Health and readiness (023 FR-004) | `GET /healthcheck` on the JSON-RPC HTTP server; `healthCheck` and `readinessCheck` (DB opened, peers > 0, tip advancing) | `jsonrpc/server/http/JsonRpcHttpServer.scala:84,182,199`; `jsonrpc/NodeJsonRpcHealthChecker.scala:92,106` | No role, `chainKey`, finalized head, source lag; readiness assumes P2P peers, which followers and `sim` do not have. |
| A post-import event (025 FR-001 hook candidate) | Pekko Typed pub-sub `Topic[NewBlockImported]` ("block-imported-topic"), published by `BlockImporter`, consumed by `SubscriptionManager` for `eth_subscribe newHeads` | `nodebuilder/NodeBuilder.scala:135-139`; `blockchain/sync/regular/BlockImporter.scala:172`; `jsonrpc/SubscriptionManager.scala:92-102` | Fires on import, not on canonical commit; carries the block, not the state diff; is fire-and-forget (no position, no retraction). Spec 025 DC-2 defines "canonical commit" per chain family. |
| Chain config as data (024) | `fukuii.blockchains.<name> { include "chains/<name>-chain.conf" }` for 9 built-in chains, plus `custom-chains-dir` which loads any `<name>-chain.conf` from a directory at startup | `conf/base/blockchains.conf:8-31`; `utils/Config.scala:350-400` (`BlockchainsConfig`) | `App.knownNetworks` (`App.scala:18-28`) is a **hardcoded set** gating the CLI network name; custom-chain parse failures are **logged and swallowed** (`result.toOption`), the opposite of DC-4 fail-fast; no consensus-type or fork-schedule validation. |
| Consensus family marker (024) | `network-type = "eth"` in the four ETH-family chain files, read as `NetworkType.ETH` by tx validation, admission and the gas oracle; `mining.protocol = "engine-api" | "pow"` in the profile (`conf/sepolia.conf`) | `conf/base/chains/{eth,sepolia,plataberget,hive}-chain.conf`; `consensus/validators/std/StdSignedTransactionValidator.scala:87,382`; `transactions/TxAdmission.scala` | Two keys in two files must agree and nothing checks that they do; `storage-scheme` is a third. |
| MESS default (024 DC-2) | `MESSConfig(enabled = false)` default; `mess.enabled = false` in `etc-chain.conf:248` and `mordor-chain.conf:217` | `consensus/mess/MESSConfig.scala:22` | Nothing validates the default or documents it as a fleet constraint. |
| Startup validation (024 DC-5) | `ConfigValidator.validate` returns fatal errors (port clashes) and logs removed-key warnings | `ConfigValidator.scala:53-100` | Extend, do not duplicate: the ChainSpec validator reports through the same path. |
| Follower seed, ETH family (026 FR-001) | Spec 013 snapshot v2 (flat state + code, root-verified on import); CL-anchored trust | `specs/013-state-snapshots/spec.md` §5 | 013 is scoped to ETH-family chains. |
| Follower seed, ETC family (026 FR-001) | Checkpoint archive v1 (`CheckpointArchive`, hash-keyed trie nodes + code, CRC32), fresh-DB gate in `SyncController` | `blockchain/checkpoint/*`; 013 §2 | Hash-keyed; host-trusted (no root anchor beyond the header). 026 OQ-3 decides whether ETC followers seed from v1 or from a 013 v2 extended to ETC. |
| Rollback data (025 DC-9, 026 DC-4) | 018 FR-010 changeset store: per block, the **previous** value of every flat key and trie node changed; archive keeps all | `specs/018-storage-engine-consolidation/spec.md` FR-010, plan F2 | Previous value only; no post-value, no `chainKey`, no schema version. 018 amendment request 2. |
| Isolation inside a process (023 DC-8) | 018 FR-027 / DC-15: bounded, dedicated executors for heavy reads, explicit rejection when full (Track R, slice R1) | 018 spec FR-027, plan Phase 5 | Not yet implemented; 023 consumes it for the single-node profile and for `sim`. |
| Node-as-source topology (023 DC-2) | 018 DC-16 | 018 spec DC-16 | 023 makes it a fleet contract. |
| Per-chain datadir and ports | One `fukuii <network>` CLI, one datadir, one set of ports per process | `App.scala`, `conf/*.conf` | Already the single-node profile; 023 only adds `--role`. |

---

## 4. Reading list the specs assume (for the implementing thread)

`ARCHITECTURE.md`; `.claude/CLAUDE.md`; `specs/018-storage-engine-consolidation/{spec,research,plan}.md` (DC-16,
research §16,
plan Tracks F, H, R); `specs/013-state-snapshots/spec.md`; `src/main/scala/com/chipprbots/ethereum/App.scala`;
`utils/Config.scala`, `utils/InstanceConfig.scala`; `nodebuilder/NodeBuilder.scala`, `StdNode.scala`;
`consensus/ConsensusAdapter.scala`, `consensus/ReorgStateHandler.scala`, `consensus/mess/`;
`conf/base/blockchains.conf`, `conf/base/chains/`; `jsonrpc/NodeJsonRpcHealthChecker.scala`;
`blockchain/sync/regular/BlockImporter.scala`; `.claude/agent-protocols/dead-code-review.md`,
`consensus-change-protocol.md`, `storage-rocksdb.md`.

---

## Phase 0 (plan) — decisions

**Date**: 2026-10-31. **Tree**: `ccr-6ee15a46-q037bc-3` (0.10.18 line). Each decision below is the one the plan builds
on; alternatives are recorded so the premise can be challenged. File:line citations were read on this tree.

### D-1 Admin surface implementation

- **Decision**: a new class `admin.AdminHttpServer` (package `com.chipprbots.ethereum.admin`) that builds a Pekko HTTP
  `Route` and binds it with `Http(classicSystem).newServerAt(interface, port).bind(route)`, exactly the call
  `InsecureJsonRpcHttpServer.run()` makes (`jsonrpc/server/http/InsecureJsonRpcHttpServer.scala:25-35`), serialising
  with the same json4s `native.Serialization` the RPC server uses for `/healthcheck`
  (`JsonRpcHttpServer.scala:52-54,198-213`). It serves `GET /health`, `GET /ready` and `POST /lifecycle/{drain,fence,
  unfence,stop}` and nothing else. It binds on the node's main Classic ActorSystem, started in `BaseNode.start()`
  before the RPC servers so Orbita can see a process while it is still coming up. The legacy `/health`, `/readiness`
  and `/healthcheck` on the RPC port (`JsonRpcHttpServer.scala:80-85`) stay unchanged — hive and every compose file
  probe them.
- **Rationale**: FR-009 requires a dedicated port that never serves chain RPC; the cheapest correct reuse is the
  bind/serialise pattern, not the route tree. Health reads only cheap sources (best block from `BlockchainReader`,
  the CL head from `ForkChoiceManager`, AppState markers, an `AtomicReference` lifecycle state) so a busy node still
  answers; the only actor hop is the existing `SyncController.GetStatus` ask already used by
  `NodeJsonRpcHealthChecker.scala:78-85`.
- **Alternatives**: (a) mount the routes on the JSON-RPC HTTP server — rejected, FR-009; (b) an isolated ActorSystem
  + IORuntime like `EngineApiHttpServer` (`consensus/engine/EngineApiHttpServer.scala:56` explains why the Engine API
  needs it) — rejected for the first release: the admin surface carries a handful of requests per second and no
  heavy handler; if health latency under load is ever measured as a problem, moving it to its own system is a local
  change; (c) gRPC — rejected, Orbita's contract is HTTP/JSON (spec §Interface to Orbita).

### D-2 `Role` enum placement and `InstanceConfig` fields

- **Decision**: `enum Role { case Ingest, Replica, Archive, Sim }` in `utils/Role.scala`, with `key` (lowercase wire
  form), `fromString: String => Either[String, Role]`, `isFollower`. `InstanceConfig` (`utils/InstanceConfig.scala:29`)
  gains `val chainKey: String = config.getString("chain")` and `val role: Role` (parsed from `config.getString("role")`,
  an invalid value fails construction with a named `IllegalArgumentException` that `Fukuii.main` turns into exit 2).
  `instanceId` is removed (research §2.1). `object Config extends
  InstanceConfig(ConfigFactory.load().getConfig("fukuii"))`
  (`utils/Config.scala:27`) therefore reads `fukuii.chain` and `fukuii.role` from the merged config, which `App.scala`
  sets as system properties from `--chain`/`--role` before `Config` initialises (today's `setNetworkConfig` already
  relies on that ordering, `App.scala:71-92,239-240`).
- **Rationale**: FR-001 says role and `chainKey` reach every component through `InstanceConfig`, not a new global.
  `utils/` already owns configuration; `nodebuilder/` depends on `utils/`, not the reverse, so the enum must live in
  `utils/` or below.
- **Alternatives**: `nodebuilder/Role.scala` — rejected (dependency direction); a `String` field — rejected
  (constitution IV, exhaustive matching in `RoleProfile`); `--role` as a modifier like `public`/`enterprise`
  (`App.scala:32`) — rejected (modifiers are boolean switches; role has four values and a default).

### D-3 Config keys and CLI mapping

- **Decision**: `fukuii.chain = ${fukuii.blockchains.network}` (so every existing profile keeps working: the profile
  sets `blockchains.network`, and `chainKey` equals it until 024 makes the registry the source), `fukuii.role =
  "ingest"`.
  `App.scala` parses `--chain <key>` / `--chain=<key>` and `--role <role>` / `--role=<role>`, sets
  `-Dfukuii.chain` and `-Dfukuii.role`, and treats `--chain X` like the positional network `X` for config-file
  selection (`setNetworkConfig`, `App.scala:72-92`). A positional network with no `--role` is `--role ingest`. An
  unknown role exits 2 with a named error before logging starts. 024 later replaces `knownNetworks` (`App.scala:18-28`).
- **Alternatives**: deriving role from the config file name (`sepolia-replica.conf`) — rejected (hides the role from
  the process arguments Orbita and `ps` see); a `fukuii.roles = [...]` list — rejected (DC-1, one role per process).

### D-4 Datadir layout and migration

- **Decision**: `conf/base/fukuii.conf:6` becomes
  `datadir = ${user.home}"/.fukuii/"${fukuii.chain}"/"${fukuii.role}`; every derived path (`tmpdir`, `node-key-file`,
  `keystore-dir`, `logs-dir`, `rocksdb.path`, IPC socket) already hangs off `${fukuii.datadir}` (`fukuii.conf:11,14`,
  `keystore.conf:3`, `logging.conf:6`, `db.conf:4`, `network.conf:311`) and follows. A `utils/DatadirLayout` check runs
  in `Fukuii.main` before `StdNode` is built: if `fukuii.datadir` is the default-derived path, the new path has no
  `rocksdb/`, and `~/.fukuii/<chainKey>/rocksdb` exists, the process exits 2 with error `LegacyDatadirLayout` naming
  both paths and the two remedies (`mv ~/.fukuii/<chainKey> ~/.fukuii/<chainKey>/ingest` done safely via a temp name,
  or set `fukuii.datadir` explicitly). The operating-modes runbook carries the same note (OQ-3).
- **Rationale**: two roles for one chain on one host must not collide (OQ-3); a synced store must never be silently
  abandoned for a fresh sync ("fail loudly"); the detector costs one `Files.exists`.
- **Alternatives**: auto-adopt the legacy path for `ingest` with a WARN — rejected by the plan (default would depend
  on disk state; the move is deferred forever) but **surfaced as risk R-1** because US1 AS1 says "no config change";
  a symlink created by the node — rejected (the node does not restructure the operator's filesystem).

### D-5 Durable fence marker and first-start identity (AppState)

- **Decision**: three string keys in `AppStateStorage.Keys` (`db/storage/AppStateStorage.scala:621-655`), named in
  022's dotted style: `fleet.fenced` (value: epoch-millis of the fence; presence means fenced; `unfence` removes it),
  `fleet.chainKey` and `fleet.role` (written once at first start, in the same batch as 022's
  `snap.heal.datadirCreated`-style first-write; a later start with a different `chainKey` or `role` is refused with a
  named error before any listener binds). Every write is one `updateSync` batch (022 `data-model.md` §"Invariant
  marker": "Every write of these keys is one `updateSync` batch"). The fence's write order is: in-memory state →
  `SyncProtocol.Pause` → fenceable hooks (025 exporter persists its position) → Engine API listener closed → marker
  written and synced → health reports `fenced=true`. The marker is written **last** so a crash mid-fence leaves a
  process that is unfenced on disk but was stopped, which Orbita re-fences; the reverse (marker first) could leave a
  fenced marker over a process whose exporter never persisted its position.
- **Alternatives**: a file `datadir/FENCED` — rejected (not in the same durability domain as the store the fence
  protects; 022 chose AppState for the same reason); a RocksDB column family — rejected (one boolean does not need a
  CF; AppState is the key/value store for exactly this).

### D-6 Role gating mechanism in the cake builders

- **Decision**: no changes to the cake composition (`NodeBuilder.scala:1299-1381`). The builders are `lazy val`s, so
  a subsystem that is never *started* is never constructed. `BaseNode.start()` (`nodebuilder/StdNode.scala:55-117`) is
  refactored into a pure `StartPlan.steps(role: Role, engineApiEnabled, miningEnabled, readinessPort): List[Step]`
  (unit-testable) and an executor that runs the steps; `RoleProfile(role)` says which of `startServer`,
  `loadStaticNodes`, `startPortForwarding`, `startPeerManager`, `startDiscoveryManager`, `startEngineApiServer`,
  `startSyncController`, `startMining` are in the plan (contracts/role-matrix.md). `shutdown` (`StdNode.scala:319-362`)
  is gated by the same profile so it never touches `peerDiscoveryManagerTyped`, `mining` or `maybeEngineApiServer`
  of a role that never started them. The Engine API has one extra gate at its source,
  `engineApiConfig.enabled = configured && role == Ingest` (`NodeBuilder.scala:956-963`), so `bindInvalidChainReporter`,
  `bindDesignatedHead` and `maybeEngineApiServer` (`:997,1013,1024`) are all inert for followers through the gate they
  already check. RPC: `RoleMethodPolicy.servedBy(role, method)` is consulted in `JsonRpcBaseController.handleRequest`
  (`jsonrpc/server/controllers/JsonRpcBaseController.scala:50-61`) before the `enabledApis` fold; a refused method
  answers `JsonRpcError.NotServedByRole` (-32020). Namespaces are not removed from `enabledApis`, so `rpc_modules`
  stays truthful about what is *configured* while the policy says what is *served*.
- **Rationale**: the start sequence is already the single place that decides what binds, with comments explaining
  bind order for hive (`StdNode.scala:75-108`); gating there keeps those invariants. Making the plan a pure function
  gives a deterministic unit test per role without binding ports.
- **Alternatives**: per-role `Node` subclasses (`IngestNode`, `ReplicaNode`) — rejected (four cakes to keep in step;
  the difference is which steps run, not which components exist); gating inside each builder's `lazy val` — rejected
  (scatters the policy across 1,400 lines); dropping namespaces from `enabledApis` — rejected (returns -32601
  "method not found", which is the wrong answer; DC-9 wants a *named* role error).

### D-7 Harness tooling and placement

- **Decision**: Docker Compose under **`ops/fleet-harness/`** with a Python 3 standard-library driver
  (`harness.py`), reusing `scripts/ephemery/load-gen.py` as the transaction load generator (it already accepts the
  local devnet chain ids 1337/31337 and refuses production ids) and adding a small `load/read-burst.py` for
  `eth_call`/`eth_simulateV1` saturation. Two devnets: an ETC devnet derived from the Gorgoroth private network
  (`ops/gorgoroth/conf/`, PoW with the internal miner on `ingest` only) as the Mordor-class chain, and an ETH-family
  devnet driven by the execution-apis `testing_*` block-production namespace (`NodeBuilder.scala:1392-1404`,
  `conf/hive.conf` genesis style with timestamp forks) as the Platåberget-class chain, so no consensus client
  container is needed. A single KRaft Kafka broker container. Each fukuii service is started with
  `--chain <key> --role <role>`, has a `mem_limit`, and exposes its admin port on loopback only. The README states
  it is a test fixture (FR-007), never a deployment.
- **Rationale**: every existing multi-container fixture lives in `ops/` (`ops/gorgoroth/`, `ops/cirith-ungol/`,
  `ops/barad-dur/`; `ops/README.md` calls Gorgoroth an "internal test network"), while `scripts/` holds single-file
  helpers (`scripts/README.md`). Compose matches how `scripts/hive/local.sh` and the gorgoroth fixtures already run
  fukuii images on the build host, and resource limits per container are what SC-003's OOM variant needs.
- **Alternatives**: `scripts/fleet-harness/` — rejected (placement convention above; `ops/` is where compose lives);
  Testcontainers from ScalaTest — rejected (adds a JVM test dependency, runs under sbt where a 2-chain × 4-role × Kafka
  topology exceeds the Essential tier budget and the CI runner; harness runs are build-host windows like 022's
  gates); Kubernetes manifests — rejected (that is Orbita's domain; the harness must not become a deployment).
  Switching to the 024 SC-001 fixtures when they exist is a conf swap, not a redesign.

### D-8 How SC-002 and SC-003 are measured (and GT-3)

- **Decision**: **import p99** = p99 of a new Micrometer timer `regularsync.block.import.duration` recorded in
  `BlockImporter` around one block's import, added to `RegularSyncMetrics`
  (`blockchain/sync/regular/RegularSyncMetrics.scala`
  has only `regularsync.blocks.imported.total` and propagation timers today, lines 14-31). The harness scrapes each
  `ingest`'s Prometheus endpoint (`metrics.enabled = true`, port 13798, `conf/base/metrics.conf:5-9`) every 5 s and
  computes p99 over the scenario window from the histogram buckets. **Read p99** = client-side latency recorded by
  `load/read-burst.py` against the follower under test. **GT-3 noise band** = for each of the two chains and each of
  the two figures, three unloaded runs of a fixed-length window (≥ 300 imported blocks); band = max − min of the three
  p99s, floored at 5 % of their mean. SC-002/SC-003 pass when the loaded (or post-kill) p99 lies within
  `baseline_mean ± band`. Rejections in SC-002 are counted by error code from the load generator's responses (the 018
  R1 rejection code and -32020). Results are JSON files under `ops/fleet-harness/results/` with the tree hash, host and
  image id; the baseline is committed, scenario runs are committed when they are release evidence.
- **Alternatives**: log-derived timings — rejected (brittle, host-dependent); `eth_blockNumber` polling — rejected
  (gives throughput, not per-block import time); a statistical test instead of a band — deferred; three runs do not
  support one, and the band is what the spec asks for.

### D-9 Transaction forwarding (FR-010)

- **Decision**: `transactions/TxForwarder` is a minimal JSON-RPC HTTP client (Pekko HTTP `Http().singleRequest`) that
  posts `eth_sendRawTransaction` to `fukuii.tx-forward.endpoint` and returns the ingest's JSON result or error object
  **unchanged** to the caller; a token bucket of `fukuii.tx-forward.max-per-second` tokens per process rejects with
  -32022 when empty; with no endpoint configured the method answers -32020. It is wired in `EthTxService` when
  `instanceConfig.role != Role.Ingest`; the ingest's own path (`EthTxService.scala:229` → `TxAdmission`) is unchanged
  except for the fenced check (-32021). Peers are never used for forwarding (followers have no P2P).
- **Alternatives**: a gossip-only `relay` role — that is OQ-5 / spec 027, opened only if SC-006 shows ingest
  admission is the bottleneck.

### D-10 Error codes

- **Decision**: `-32020 NotServedByRole`, `-32021 IngestFenced`, `-32022 ForwardRateCapExceeded` in
  `jsonrpc/JsonRpcError.scala`. The -320xx server-error range is where fukuii already keeps `-32000 LogicError` and
  `-32005 RateLimitError` (`JsonRpcError.scala:33,41`); -3802x is taken by `eth_simulateV1` errors (`:52-64`), and
  -32601 must stay "method does not exist" so clients that probe for support keep working.

### Baseline figures to be filled by the implementing thread

| Figure | Where it is recorded | Task |
| --- | --- | --- |
| `testEssential` count/failures before S1 | plan.md §Technical Context (Testing) | T002 |
| Spec 020 PR 5 membership of the Phase B files | plan.md §Sequencing | T001 |
| GT-3 noise band per chain per figure | this section, D-8 (append a table) | T026 |
| SC-006 ingest admission p99 / import p99 under forwarded load | `ops/fleet-harness/results/sc006-*.json`; summary here | T080 |
