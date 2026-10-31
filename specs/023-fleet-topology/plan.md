# Implementation Plan: Fleet Topology — Process Model, Roles and the Contract to Orbita

**Branch**: `023-fleet-topology` | **Date**: 2026-10-31 | **Spec**: [spec.md](./spec.md) (Clarified 2026-10-31)

**Input**: `specs/023-fleet-topology/spec.md`, the Clarifications session of 2026-10-31 (OQ-1..OQ-5), the cross-spec
[alignment review](./alignment-review.md) (§4 sequencing is binding) and [research.md](./research.md) §2 (the T-001
retirement inventory, followed exactly by slice S1).

**Status**: Planned (2026-10-31). Nothing here is implemented. Phase B slices wait on spec 022's release gates and the
spec 020 PR 5 window (§Sequencing).

## Summary

A fukuii process serves exactly one chain in exactly one role (`ingest | replica | archive | sim`). This plan (1)
retires the in-process multi-instance runtime (`runtime/`, the `fukuii runtime` command, `conf/multi-instance.conf`,
the `instanceId` plumbing and the per-instance metrics registry) exactly as research §2 inventories it; (2) adds
`--chain <chainKey>` and `--role <role>` to the launcher with `fukuii <network>` meaning `--chain <network> --role
ingest`, carried to every component through `InstanceConfig.chainKey` / `InstanceConfig.role` (a Scala 3 `enum`),
the ActorSystem named `fukuii_<chainKey>` and the default datadir `~/.fukuii/<chainKey>/<role>`; (3) gates subsystem
start on the role in `BaseNode.start()` so followers and `sim` never bind P2P, discovery, the Engine API or the miner,
and answers methods a role does not serve with a defined JSON-RPC error; (4) adds an **admin HTTP surface** on its own
localhost-bound port (`GET /health`, `POST /lifecycle/{drain,fence,unfence,stop}`) built from the `/healthcheck`
server code, with per-role readiness and a **durable fence** in AppState; (5) forwards raw transactions from
followers and `sim` to the chain's `ingest` admission endpoint with a per-process rate cap; (6) ships a
containerised **fleet test harness** under `ops/fleet-harness/` (two devnets, every role, a Kafka broker, a load
generator) in which the isolation success criteria are measured against a recorded noise baseline (GT-3); and (7)
delivers the `sim` role on spec 018 Track R slice R1's bounded executors, consuming that mechanism rather than adding
a second one. No slice changes a block, receipt, state root or fork-choice output (DC-7).

## Technical Context

**Language/Version**: Scala 3.9.0 on JDK 25 (OpenJDK); sbt 1.13.0 (constitution §Technology; spec 020 PR 3 landed).

**Primary Dependencies**: Apache Pekko Typed actors and Pekko HTTP (the admin surface reuses the Pekko HTTP route
style of `jsonrpc/server/http/JsonRpcHttpServer.scala:78-145` and the bind call of
`InsecureJsonRpcHttpServer.run()` at `InsecureJsonRpcHttpServer.scala:25-35`); cats-effect `IO`; json4s native for the
health JSON (same serializer as `JsonRpcHttpServer.scala:52-54`); Micrometer/Prometheus for the metric twins
(`metrics/Metrics.scala:17-72`); Typesafe Config (HOCON) for the new keys. No new library. The harness is Docker
Compose plus Python 3 standard-library scripts (same tooling as `ops/gorgoroth/` and `scripts/ephemery/load-gen.py`).

**Storage**: RocksDB through the existing `AppStateStorage` (`db/storage/AppStateStorage.scala`, namespace
`Namespaces.AppStateNamespace`, string keys in `AppStateStorage.Keys` at lines 621-655). Three new keys
(`fleet.fenced`, `fleet.chainKey`, `fleet.role`; see [data-model.md](./data-model.md)), each written in one synced
batch as spec 022's markers are (022 `data-model.md` §"Invariant marker"). No new column family, no schema change.

**Testing**: ScalaTest (`AnyFlatSpec` + `Matchers`, tagged `UnitTest`, as `AppSpec.scala` and
`LauncherIntegrationSpec.scala`), Pekko TestKit for actor paths, Pekko HTTP `ScalatestRouteTest` for the admin routes,
`EphemDataSource` for AppState tests. No `Thread.sleep` (constitution III); readiness and lifecycle waits in tests use
`eventually` or TestKit probes. Tiers: `./scripts/fukuii-test only "*Spec*"` per slice, `testEssential` once per slice
end (baseline 3,621 / 0 at 0.10.17, re-measured by T002), harness scenarios on the build host outside sbt.

**Target Platform**: Linux server JVM; one process per `(chainKey, role)` in a container or systemd unit. The admin
port binds `localhost` by default (constitution VI: RPC-like surfaces default private).

**Project Type**: single sbt project, root module `main` (+ `bytes`, `crypto`, `rlp`, `Evm`, `Benchmark`, `RpcTest`,
`IntegrationTest`); CLI launcher (`App.scala`) plus long-running node (`Fukuii.scala` → `StdNode`).

**Performance Goals**: none claimed (spec Non-goals; 018 FR-028 rule). SC-002/SC-003 are *relative* assertions
against the GT-3 noise band recorded by the same harness on the same host. SC-006 records `ingest` admission p99 and
import p99 under forwarded load as a decision input for OQ-5, not as a target.

**Constraints**: consensus-neutral (DC-7; nothing under `consensus/`, `vm/`, `mpt/`, `ledger/` changes; the one
`blockchain/sync/` change, `SyncProtocol.Pause/Resume`, is a control message routed through `eye`); the admin port
never serves chain RPC (FR-009); `sim` reuses 018 FR-027 executors (FR-003); the node holds no fleet knowledge (DC-4);
every Phase B slice lands in the spec 020 PR 5 window rules (§Sequencing); the retirement PR touches only research §2
rows.

**Scale/Scope**: ~10 slices; code touched: `App.scala`, `Fukuii.scala`, `utils/{InstanceConfig,Config}.scala`, a new
`utils/Role.scala`, `nodebuilder/{NodeBuilder,StdNode}.scala`, a new `nodebuilder/RoleProfile.scala`, a new package
`admin/` (lifecycle controller, health reporter, admin HTTP server, fleet metrics), `jsonrpc/JsonRpcError.scala`, a new
`jsonrpc/RoleMethodPolicy.scala`, `jsonrpc/server/controllers/JsonRpcBaseController.scala`,
`jsonrpc/EthTxService.scala`,
`transactions/TxAdmission.scala`, a new `transactions/TxForwarder.scala`,
`blockchain/sync/{SyncProtocol,SyncController}.scala`,
`blockchain/sync/regular/RegularSyncMetrics.scala`, `db/storage/AppStateStorage.scala`,
`metrics/{Metrics,MetricsContainer}.scala`,
`ConfigValidator.scala`, `conf/base/{fukuii,metrics}.conf`, `ops/barad-dur/*`, new `ops/fleet-harness/`, two runbooks.
Deleted: `runtime/` (122 lines), `conf/multi-instance.conf`, `MetricsMultiInstanceSpec.scala`, two ops compose files.

## Constitution Check

*GATE: passed for Phase 0; re-checked after Phase 1 design (this version). Principles from
`.specify/memory/constitution.md` v1.1.1.*

| Principle | How this plan engages it | Verdict |
| --- | --- | --- |
| **I. Consensus determinism** | No slice changes EVM, state, hashing, RLP, PoW, rewards, fork activation or Engine API payload semantics. The fence *closes* the Engine API listener (a bind/unbind of `EngineApiHttpServer`, `consensus/engine/EngineApiHttpServer.scala:176-205`) and *pauses* import via a new `SyncProtocol` control message; neither alters what a block computes to. ETC/ETH dispatch untouched. `beacon` glances at the Engine API close/reopen path; `forge` is not needed (nothing ETC-consensus changes). | **PASS** (DC-7; routing in §Specialist routing) |
| **II. Spec-driven** | Spec Clarified; this plan, research §Phase 0, data-model, contracts, quickstart, tasks produced before code. Deviations go to Complexity Tracking. | **PASS** |
| **III. Tests, deterministic, ≥ 70 %** | Every behavioural slice ships ScalaTest specs (tasks list them by path); no `Thread.sleep`; harness scenarios are build-host fixtures, not tier tests. Deleting `MetricsMultiInstanceSpec` removes 3 trivial assertions; new specs (`RoleSpec`, `LifecycleControllerSpec`, `HealthReporterSpec`, `AdminHttpServerSpec`, `RoleMethodPolicySpec`, `StartPlanSpec`, `TxForwarderSpec`, AppState additions) more than replace them. `testEssential` before and after S1 (SC-001). | **PASS** |
| **IV. Idiomatic Scala 3, scalafmt/scalafix** | `Role` is a Scala 3 `enum`; `RoleProfile` and `LifecycleState` are enums/case classes; `given`/`using` only; no `return`. `sbt scalafmtAll` per slice, `sbt pp` before each PR. | **PASS** |
| **V. Quality gates** | One PR per slice (§Delivery slices), each green on `compile-all`, `testEssential`, `formatCheck`; `prism` review on every non-consensus slice. | **PASS** |
| **VI. Security & operational safety** | New listener (admin) defaults to `localhost`; `POST /lifecycle/*` is unauthenticated **by design on localhost only** — Orbita reaches it through the container's loopback or a sidecar; the runbook forbids exposing it and the validator warns when `fukuii.admin.interface` is not loopback. `fukuii.tx-forward.endpoint` is an outbound HTTP client to a trusted `ingest`; secrets none. The datadir legacy detector fails loudly rather than starting a fresh sync beside a synced store. | **REVIEW** — admin surface exposure reviewed by `prism` (security lens) in S4; documented in `contracts/admin-api.md` §Security |
| **VII. Versioning & records** | Breaking changes flagged in CHANGELOG: `fukuii runtime` removed, default datadir moves (migration note in the operating-modes runbook), new admin port. A short ADR `docs/adr/architecture/ARCH-0xx-one-process-one-chain-one-role.md` records D-1/D-4 (task T081). | **PASS** |
| **Technology constraints** | No new dependency. New package `admin/` under `com.chipprbots.ethereum`; it depends on `jsonrpc/`, `db/`, `blockchain/sync/` (downward only). | **PASS** |

## Project Structure

### Documentation (this feature)

```text
specs/023-fleet-topology/
├── spec.md                 # Clarified 2026-10-31 (unchanged by this plan)
├── alignment-review.md     # cross-spec review; §4 sequencing binds this plan
├── plan.md                 # this file
├── research.md             # §1-§4 existing; §"Phase 0 (plan) — decisions" appended by this plan
├── data-model.md           # health JSON, Role enum, AppState keys, config keys
├── contracts/
│   ├── admin-api.md        # admin HTTP paths, request/response JSON, status and error codes
│   └── role-matrix.md      # per role: listeners, namespaces, sync mode, readiness, tx handling, mining
├── quickstart.md           # runnable validation per user story + harness
└── tasks.md                # T001–T083, by user story
```

### Source Code (repository root)

```text
src/main/scala/com/chipprbots/ethereum/
├── App.scala                                  # S1: delete `case Some("runtime")` (264-271) → named error; S2: --chain/--role
├── Fukuii.scala                               # S2: legacy-datadir detector before node start; root-config validation
├── ConfigValidator.scala                      # S1: fatal on fukuii-runtime.instances; S2/S4: role, admin port, mining×role
├── utils/
│   ├── Role.scala                             # NEW (S2): enum Role { Ingest, Replica, Archive, Sim }
│   ├── InstanceConfig.scala                   # S1: drop instanceId (29, 67); S2: chainKey, role, Admin, TxForward
│   ├── Config.scala                           # S1: single-arg super call (27), doc comment (25-27)
│   └── DatadirLayout.scala                    # NEW (S2): default-path derivation + legacy detector
├── nodebuilder/
│   ├── NodeBuilder.scala                      # S1: ActorSystem name (112); S3: Engine API role gate (956-963);
│   │                                          #     S4: AdminServerBuilder mixed into Node (1299-1381)
│   ├── RoleProfile.scala                      # NEW (S3): what a role binds/serves; start plan
│   └── StdNode.scala                          # S1: Metrics.configure (121), thread name (284); S3: gated start/shutdown;
│                                              #     S5: shutdown = drain then stop (319)
├── admin/                                     # NEW package (S4, S5)
│   ├── LifecycleController.scala              # Running | Draining | Fenced | Stopping; drain/fence/unfence/stop; hooks
│   ├── HealthReporter.scala                   # builds the FR-004 shape from HealthSources; per-role readiness
│   ├── AdminHttpServer.scala                  # GET /health, GET /ready, POST /lifecycle/*; localhost:8560 default
│   └── FleetMetrics.scala                     # metric twins (MetricsContainer)
├── jsonrpc/
│   ├── JsonRpcError.scala                     # S3: NotServedByRole (-32020), IngestFenced (-32021), ForwardRateCap (-32022)
│   ├── RoleMethodPolicy.scala                 # NEW (S3): method → served-by-role table
│   ├── server/controllers/JsonRpcBaseController.scala  # S3: policy check before dispatch (50-61)
│   ├── server/http/JsonRpcHttpServer.scala    # S5: 503 while Draining; legacy /health,/readiness,/healthcheck unchanged
│   └── EthTxService.scala                     # S1: reword 89-94; S5: fenced refusal; S6: forward when role ≠ ingest
├── transactions/
│   ├── TxAdmission.scala                      # S5: fenced check; S6: admission timer metric
│   └── TxForwarder.scala                      # NEW (S6): JSON-RPC client to ingest, token-bucket cap
├── blockchain/sync/
│   ├── SyncProtocol.scala                     # S5: Pause / Resume control messages
│   ├── SyncController.scala                   # S5: handle Pause/Resume
│   └── regular/RegularSyncMetrics.scala       # S7: block import duration timer (harness input)
├── db/storage/AppStateStorage.scala           # S2: fleet.chainKey/fleet.role; S5: fleet.fenced
├── metrics/Metrics.scala                      # S1: delete registry (79-142), keep configure(config)/get()/close
└── metrics/MetricsContainer.scala             # S1: delete Bug 29 comment (6-11)
src/main/resources/conf/
├── base/fukuii.conf                           # S2: chain, role, datadir default, admin{}, tx-forward{}
├── base/metrics.conf                          # S1: reword line 26
└── multi-instance.conf                        # S1: DELETE
src/main/scala/com/chipprbots/ethereum/runtime/ # S1: DELETE (FukuiiRuntime.scala, ChainInstance.scala)
src/test/scala/com/chipprbots/ethereum/
├── AppSpec.scala, LauncherIntegrationSpec.scala           # S2: --chain/--role cases
├── ConfigValidatorSpec.scala                              # S1/S2/S3 cases
├── metrics/MetricsMultiInstanceSpec.scala                 # S1: DELETE
├── utils/{RoleSpec,DatadirLayoutSpec,ProfileLoadSpec}.scala       # NEW
├── nodebuilder/StartPlanSpec.scala                        # NEW (S3)
├── admin/{LifecycleControllerSpec,HealthReporterSpec,AdminHttpServerSpec}.scala  # NEW (S4, S5)
├── jsonrpc/{RoleMethodPolicySpec,JsonRpcHttpServerHealthRoutesSpec}.scala       # NEW (S3, S4)
├── transactions/TxForwarderSpec.scala                     # NEW (S6)
├── blockchain/sync/SyncControllerPauseSpec.scala          # NEW (S5)
└── db/storage/AppStateStorageFleetSpec.scala              # NEW (S2, S5)
ops/
├── barad-dur/multi-etc-mordor/                # S1: two single-role services; multi-network.conf removed
├── barad-dur/sepolia/                         # S1: docker-compose-multi.yml + multi-network.conf removed; comment (19)
└── fleet-harness/                             # NEW (S7): compose, conf/, harness.py, load/, metrics.py, schema/, results/
docs/
├── runbooks/operating-modes.md                # S2: roles + datadir migration note
├── runbooks/fleet-fencing.md                  # NEW (S5)
├── operations/metrics-reference.md            # S4: fleet_* metrics
└── adr/architecture/ARCH-0xx-one-process-one-chain-one-role.md  # NEW (polish)
```

**Structure Decision**: single project; one new package `admin/` (lifecycle, health, admin HTTP, metric twins) because
these concerns are neither chain RPC (`jsonrpc/`) nor node assembly (`nodebuilder/`), and the admin listener must be
reviewable as one surface. Everything else is edits to the files the retirement inventory and the FRs already name.
The harness lives in `ops/fleet-harness/` beside the other multi-container fixtures (`ops/gorgoroth/`,
`ops/cirith-ungol/`); rationale in research §Phase 0, D-7.

## Sequencing (binding; from alignment-review.md §4)

| Phase | What lands here | Gate |
| --- | --- | --- |
| **B** (0.12.0) | S1 runtime retirement, S2 CLI/InstanceConfig/datadir, S3 role gating, S4 admin health, S5 lifecycle + fence | After spec 022 slices 4–6 and both release gates; **in the same window as 022 slices 4–6 (before spec 020 PR 5 is regenerated) or after 020 PR 7 — never beside PR 5** |
| **C** (0.13.0) | S6 transaction forwarding, S7 harness + GT-3, S8 isolation scenarios SC-003/SC-004/SC-005/SC-006 | S7 needs S2–S5 in an image; SC-004 needs 025's gap checker for the record-stream half |
| **D** (0.14.0) | S9 `sim` role (T-007) | **Blocked on 018 Track R slice R1** (bounded executors, FR-027) |
| polish | S10 docs/ADR/index | after S5 at the latest |

**Spec 020 PR 5 check (performed 2026-10-31).** `specs/020-scala-3-9-lts-migration/dc3-sites.md` lists only the 381
E205 given-prioritisation sites (RLP codecs and 7 MCP decoders in `JsonRpcController.scala`); none of
`App.scala`, `utils/Config.scala`, `utils/InstanceConfig.scala`, `nodebuilder/NodeBuilder.scala`,
`nodebuilder/StdNode.scala`, `metrics/Metrics.scala` appears there. The PR 5 rewrite set (187 files, 514 implicit →
`using` insertions; 020 `plan.md:105,151`) is **not enumerated by file in the repository**, so membership cannot be
confirmed from the tree today. Expect `NodeBuilder.scala` to be in it (it passes implicits explicitly at ~969
`(blockchainConfig, typedScheduler)` and declares `implicit lazy val ioRuntime` at 207, 1193, 1381) and
`JsonRpcHttpServer.scala` likely (`implicit def myRejectionHandler` at 65). Task T001 regenerates the list
(`git diff --stat` of the PR 5 branch, or a one-off `-source:3.7-migration` compile grepping the implicit-`using`
warnings for these paths) and records the result here before any Phase B slice opens. If PR 5 has already landed when
Phase B starts, the question is moot and S1–S5 rebase on it.

## Delivery slices

One PR per slice. Every slice is behaviour-neutral for consensus (no change to any block, receipt, state root or
fork-choice output); the "Consensus" column records what a reviewer checks to confirm that.

| Slice | Spec task | Content (files) | Phase | Owner | Reviewer(s) | Validation (`eye`) | Consensus check |
| --- | --- | --- | --- | --- | --- | --- | --- |
| **S1** | T-002 | Runtime retirement per research §2: delete `runtime/`, `App.scala:264-271` → named error, `InstanceConfig.instanceId`, `Config.scala:25-27`, `Metrics.scala:79-142` registry, `MetricsContainer.scala:6-11`, `MetricsMultiInstanceSpec`, `conf/multi-instance.conf`; rewire `NodeBuilder.scala:112`, `StdNode.scala:121,284`; reword `EthTxService.scala:89-94`, `metrics.conf:26`, `sepolia/docker-compose.yml:19`; replace `ops/barad-dur/multi-etc-mordor` with two services; delete `sepolia/docker-compose-multi.yml` + `multi-network.conf`; `ConfigValidator` fatal on `fukuii-runtime.instances`; `ProfileLoadSpec` | B | implementing thread | `prism` (GT-1 dead-code review of every row) | `testEssential` before and after (SC-001 baseline), SC-001 grep, every `conf/*.conf` loads | pure deletion/rewiring; no consensus file touched |
| **S2** | T-003 | `utils/Role.scala`; `InstanceConfig.chainKey/role/Admin/TxForward`; `App.scala` `--chain`/`--role`; `fukuii.conf` keys + datadir default; `DatadirLayout` legacy detector in `Fukuii.main`; AppState `fleet.chainKey`/`fleet.role` first-start record + mismatch refusal; ActorSystem `fukuii_<chainKey>`; runbook migration note | B | implementing thread | `prism`; **`vault`** for the AppState write (one synced batch) | `RoleSpec`, `AppSpec`, `LauncherIntegrationSpec`, `DatadirLayoutSpec`, `AppStateStorageFleetSpec`; manual: US1 AS1 on a 0.10.17 datadir after moving it | config/launcher only |
| **S3** | T-004 | `nodebuilder/RoleProfile.scala`; `BaseNode.start()` refactored into a pure start plan + executor, gated per role; shutdown mirrors the gate; `EngineApiBuilder.engineApiConfig.enabled && role == Ingest`; mining × role validator; `JsonRpcError` codes -32020/-32021/-32022; `RoleMethodPolicy` + hook in `JsonRpcBaseController.handleRequest` | B | implementing thread | `prism`; **`conduit`** (controller hook, error codes); `herald` glance (P2P/discovery not bound for followers) | `StartPlanSpec`, `RoleMethodPolicySpec`, `JsonRpcControllerSpec` cases, `ConfigValidatorSpec`; manual: `--role replica` binds no 30303/8551 | the Engine API gate is `enabled && role == Ingest`; on `ingest` the three consumers (`bindInvalidChainReporter`, `bindDesignatedHead`, `maybeEngineApiServer`) behave exactly as today |
| **S4** | T-005 | `admin/HealthReporter`, `admin/AdminHttpServer` (GET /health, GET /ready), `admin/FleetMetrics`, `AdminServerBuilder` in `Node`, start in `BaseNode.start()` before RPC servers; per-role readiness; `JsonRpcHttpServerHealthRoutesSpec` guards the legacy routes | B | **`conduit`** (owns, per spec T-005) | `prism` (security lens on the new listener) | `HealthReporterSpec`, `AdminHttpServerSpec`, route regression spec; manual: `curl localhost:8560/health` on each role | read-only reporting |
| **S5** | T-006 | `admin/LifecycleController` (drain/fence/unfence/stop, hooks); AppState `fleet.fenced`; `SyncProtocol.Pause/Resume` + `SyncController` handling; Engine API server factory + close/reopen on fence/unfence; fenced tx refusal in `EthTxService`/`TxAdmission`; 503 while draining; `BaseNode.shutdown` = drain then stop (SIGTERM); fenced start-up; `POST /lifecycle/*`; `docs/runbooks/fleet-fencing.md` | B | implementing thread | `conduit` (admin POST, RPC refusal); **`vault`** (durable marker: batch, sync, reopen); `beacon` glance (Engine API listener close/reopen on ETH family); `eye` (sync pause path) | `LifecycleControllerSpec`, `SyncControllerPauseSpec`, `AppStateStorageFleetSpec` (marker survives reopen), `AdminHttpServerSpec` POST cases; manual US4 AS2 | `Pause` only stops *scheduling* imports; an in-flight import completes unchanged. Reviewer confirms no change under `ledger/`, `consensus/`, `vm/`, `mpt/` |
| **S6** | FR-010 | `transactions/TxForwarder.scala` (JSON-RPC client, token bucket `max-per-second`, result passed unchanged); wired in `EthTxService.sendRawTransaction` when `role != Ingest`; no endpoint → -32020; admission timer metric in `TxAdmission` | C | implementing thread | `conduit`; `prism` | `TxForwarderSpec` (fake endpoint, no sleep) | txpool admission logic unchanged; forwarding is a client in front of it |
| **S7** | T-008 | `ops/fleet-harness/` (compose, two devnet confs, `harness.py`, `load/read-burst.py`, `metrics.py`, `schema/health.schema.json`, README); `RegularSyncMetrics` block-import duration timer; GT-3 baseline recorded in research §Phase 0 D-8 | C | implementing thread | `prism`; `eye` runs the baseline | 3 baseline runs; `sc005` schema check on every role | metrics only |
| **S8** | T-009 | Scenarios `sc003` (chain kill, OOM variant), `sc004` (fence/standby), `sc005` (health shape), `sc006` (forwarded tx load); results committed under `ops/fleet-harness/results/` | C | implementing thread | `eye` judges against the GT-3 band; `prism` | the scenarios themselves | harness only |
| **S9** | T-007 | `sim` role: remote block-pinned state client over follower RPC; `eth_call`/`eth_simulateV1`/`debug_trace*` on **018 R1 executors** (consumed, not re-implemented); sim readiness; `sc002` | D | implementing thread | `conduit` (R1 owner); `prism`; `beacon`/`forge` **not** needed unless the remote state client touches `ledger/` (then route) | `SimRoleSpec`, `sc002` | execution semantics come from the existing EVM over a different state source; byte-identical `eth_call` results against the follower's own answer are asserted in `sc002` |
| **S10** | T-010 | Operating-modes runbook (role table), metrics reference, admin API doc page, ADR, CHANGELOG, spec status + `specs/README.md` regen | B–C | implementing thread | `prism` | `python3 -I scripts/specs/index.py` | docs |

## Specialist routing

- **`prism`** — every slice (non-consensus code quality, 8 lenses); GT-1 dead-code review of S1 row by row under
  `.claude/agent-protocols/dead-code-review.md`; security lens on the admin listener (S4) and the forwarder (S6).
- **`conduit`** — owns S4 (health shape, admin HTTP) per spec T-005; reviews the `JsonRpcBaseController` hook and the
  three new error codes (S3), the fenced refusal and 503-while-draining (S5), the forwarder (S6); owner of 018 R1 that
  S9 consumes.
- **`vault`** — reviews every AppState write: `fleet.chainKey`/`fleet.role` (S2), `fleet.fenced` (S5): one synced
  batch per write, read-after-reopen test, key naming consistent with 022's `snap.heal.*` markers.
- **`eye`** — `testEssential` before/after S1 (SC-001), `testOnly` per slice, the GT-3 baseline and every harness
  scenario verdict (S7/S8/S9), the `SyncProtocol.Pause/Resume` path (S5).
- **`beacon`** — glance at S5's Engine API listener close/reopen on ETH-family chains (wiring, not payload semantics);
  consulted if S9's remote state client needs anything under `ledger/`.
- **`forge`** — not engaged by default (no ETC consensus path changes); engaged only if S9 touches `ledger/` or
  `vm/` on the ETC side.
- **`herald`** — glance at S3 (followers bind no P2P/discovery; `PeerManagerActor` not started) and at the harness
  devnet peering (S7).
- **`wraith`** — on any compile failure; `mithril` — not needed (new code is written idiomatic from the start).
- **`flow`** — not engaged (no Pekko Streams graph changes).

## Complexity Tracking

| Violation / tension | Why needed | Simpler alternative rejected because |
| --- | --- | --- |
| New package `admin/` (one more top-level package) | Lifecycle + health + admin listener are one reviewable surface, neither chain RPC nor node assembly | Putting the admin routes on the JSON-RPC server violates FR-009 (admin port never serves chain RPC; separate port); scattering lifecycle into `nodebuilder/` hides the fence's write order from `vault` |
| Unauthenticated `POST /lifecycle/*` | Orbita needs signals without a key-distribution scheme in the first release; the surface binds loopback | JWT like the Engine API adds a secret per process that Orbita must manage before it exists; mTLS same. Reconsidered when the admin interface is ever non-loopback (validator warns) |
| Default datadir moves to `~/.fukuii/<chainKey>/<role>` and US1 AS1 says "no config change" | OQ-3 decided the layout and the migration note; a synced 0.10.17 datadir at `~/.fukuii/<chainKey>` must never be silently abandoned for a fresh sync | Auto-adopting the legacy path for `ingest` keeps US1 literal but makes the "default datadir" depend on disk state and defers the move forever; the plan fails loudly with both remedies (`DatadirLayout`). **Surfaced as open risk R-1 for the user** |
| `SyncProtocol.Pause/Resume` touches `blockchain/sync/` | FR-005 "stops importing" needs a control message; none exists (`SyncProtocol.scala:37` has `Start`, no stop/pause) | Stopping the `SyncController` actor loses `GetStatus` for health and cannot resume on `unfence` without a restart |
| A block-import duration timer added to `RegularSyncMetrics` | SC-002/SC-003 need per-block import time; today only `regularsync.blocks.imported.total` and propagation timers exist (`RegularSyncMetrics.scala:14-31`) | Deriving import time from logs is brittle and host-dependent |

## Cross-spec notes (for the orchestrator)

1. **018 Track R slice R1** (`specs/018-storage-engine-consolidation/plan.md:251`): S9 consumes R1's bounded
   executors, per-method caps and rejection error verbatim (FR-003). S9 is blocked until R1 merges; the plan does not
   define a second executor mechanism. 018 amendment request 6 (drop `multi-instance.conf` from FR-018's runbook list)
   becomes true at S1.
2. **024 chain-registry**: S2 keeps `App.knownNetworks` for the positional form and accepts `--chain` over the same
   set; 024 FR-002 removes the set and makes `--chain` accept any ChainSpec `chainKey`. `InstanceConfig.chainKey`
   (this plan) is the field 024's loader fills. The first-start record `fleet.chainKey` (S2) and 024 DC-6/FR-005's
   genesis-and-fork-schedule record should share one write and one "recorded at first start" read path; 024's plan
   should extend `AppStateStorage.recordFleetIdentity` rather than add a parallel key.
3. **025 change-record-export**: S5 exposes `LifecycleController.registerFenceable(hook)`; 025 T-005's exporter
   registers to persist its export position on fence (025 FR-004). S4 exposes `HealthSources.exporter:
   Option[ExporterHealthSource]`; 025 T-007 implements it (exportLag, exportQueueDepth, exportBufferBytes,
   exportLastError). Until 025 lands, an `ingest` reports those four fields as `null`. SC-004's "no gap" half uses
   025 SC-003's checker; `sc004` records "checker: pending 025" until then.
4. **026 follower-roles**: S4 exposes `HealthSources.retention: Option[RetentionWindowSource]` and the follower
   readiness bound `fukuii.admin.ready-lag-blocks`; 026 implements the source and sets the default from its retention
   window W. `RoleProfile` names sync mode `Follow` for followers; the `SyncMode` ADT itself is 017 FR-007 (pending
   row 3) — until 017 lands, followers simply do not start `SyncController` (the gate in S3), which is equivalent.
5. **017**: `SyncMode.Follow` (pending row 3) should be derived from `InstanceConfig.role` rather than a second key.
6. **020**: Phase B slices obey the PR 5 window rule above; T001 records the file-list check.
7. **022**: AppState key naming (`fleet.*`, dotted, one synced batch per write) follows 022 `data-model.md`; no 022
   file changes.
8. **Hive / compose health probes**: `GET /health`, `/readiness`, `/healthcheck` on the RPC port stay exactly as they
   are (hive probes 8545/8551, `ops/*/docker-compose.yml` probe `/health`); the admin surface is additive.

## Open risks

- **R-1 datadir default vs US1 AS1** (Complexity Tracking row 3): the plan fails loudly on a legacy layout; the user
  may prefer auto-adoption for `ingest`. Decide before S2 opens.
- **R-2 020 PR 5 membership** of the Phase B files is unverified in the tree (§Sequencing); T001 resolves it.
- **R-3 `sim` state source**: a remote block-pinned state client for EVM execution over follower RPC is the largest
  new piece of S9 and has no precedent in the codebase; if it needs changes under `ledger/`, S9 routes through
  `beacon`/`forge` and its scope is re-planned.
- **R-4 ETH-family devnet in the harness** uses the `testing_*` block-production namespace (`NodeBuilder.scala:
  1392-1404`) instead of a CL container; if the `sc0xx` scenarios need Engine API traffic, a Lighthouse container is
  added later (ops/barad-dur/sepolia shows the pattern).
- **R-5 Follower readiness bound** depends on 026's retention window; S4 ships a config default (`64` blocks) that
  026 adjusts.
