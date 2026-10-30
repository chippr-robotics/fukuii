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
