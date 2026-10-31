---

description: "Task list for spec 023 fleet topology"
---

# Tasks: Fleet Topology — Process Model, Roles and the Contract to Orbita

**Input**: `specs/023-fleet-topology/` — spec.md (Clarified 2026-10-31), plan.md, research.md (§2 inventory,
§"Phase 0 (plan) — decisions"), data-model.md, contracts/admin-api.md, contracts/role-matrix.md, quickstart.md.

**Prerequisites**: plan.md §Sequencing — Phase B tasks (T005–T018, T028–T052, T062–T075) open only after spec 022's
release gates and in the spec 020 PR 5 window rules; Phase C (T019–T027, T057–T061, T076–T080) needs an image built
from Phase B; US2 (T053–T058) is **blocked on spec 018 Track R slice R1**.

**Tests**: required by constitution III for every behavioural change; listed by file path; none may use
`Thread.sleep`. Run with `./scripts/fukuii-test only "*Name*"`; `./scripts/fukuii-test essential` once per slice end.

**Organization**: Setup → Foundational (2A code, 2B harness) → one phase per user story in priority order
(US1, US2, US3, US4, US5) → Polish. **MVP = US1 + US5** (a single node unchanged, and Orbita can read it).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on an unfinished task)
- **[Story]**: US1 single node unchanged · US2 sim burst · US3 chain kill · US4 ingest replaced · US5 Orbita reads
  health
- Paths are repository-relative; `src/main` = `src/main/scala/com/chipprbots/ethereum`, `src/test` likewise.

## Path Conventions

Single sbt project: `src/main/scala/...`, `src/test/scala/...`, `src/it/scala/...` (IntegrationTest module),
`src/main/resources/conf/`, `ops/`, `docs/`. Slice ids S1–S10 refer to plan.md §Delivery slices.

---

## Phase 1: Setup (window checks and baselines)

**Purpose**: establish the facts every later task compares against. No code change.

- [ ] T001 Resolve the spec 020 PR 5 window: list the PR 5 rewrite set (`git diff --stat <pr5-base>..<pr5-head>` if the
      branch exists, else a one-off `sbt -Dscalac.source=3.7-migration compile-all` grepping implicit-`using` warnings)
      for these files under `src/main/scala/com/chipprbots/ethereum/`: `App.scala`, `utils/Config.scala`,
      `utils/InstanceConfig.scala`, `nodebuilder/NodeBuilder.scala`, `nodebuilder/StdNode.scala`,
      `metrics/Metrics.scala`, `jsonrpc/server/http/JsonRpcHttpServer.scala`; record the result in
      `specs/023-fleet-topology/plan.md` §Sequencing (replace the "not enumerated" paragraph);
      do not open any Phase B task beside PR 5 (alignment-review.md §3 "020").
- [ ] T002 Record the test baseline: `./scripts/fukuii-test essential`; write "tests / failures @ <sha>" into
      `specs/023-fleet-topology/plan.md` §Technical Context → Testing (expected 3,621 / 0 at 0.10.17).
- [ ] T003 [P] GT-1 dead-code review: for every row of `specs/023-fleet-topology/research.md` §2.1–2.3 run
      `grep -rn "<symbol>" src/ ops/ docs/ --include=*.scala --include=*.conf --include=*.yml --include=*.md` and
      confirm the Delete/Rewire/KEEP verdict under `.claude/agent-protocols/dead-code-review.md`; append a
      "GT-1 confirmed <date>" line (and any corrections) to research.md §2. `prism` co-signs.
- [ ] T004 [P] Create `ops/fleet-harness/README.md` with the FR-007 sentence ("test fixture, not a production
      deployment; production orchestration is Orbita's"), the two-chain/four-role topology diagram (text), and links to
      `specs/023-fleet-topology/contracts/role-matrix.md` and `quickstart.md`.

---

## Phase 2: Foundational (blocking prerequisites)

### 2A — code foundations (phase B; needed by every story)

- [ ] T005 Create `src/main/scala/com/chipprbots/ethereum/utils/Role.scala`: `enum Role(val key: String, val isFollower:
      Boolean)`
      with `Ingest/Replica/Archive/Sim` and `object Role { def fromString(s: String): Either[String, Role] }` exactly
      as data-model.md §1.
- [ ] T006 [P] Create `src/test/scala/com/chipprbots/ethereum/utils/RoleSpec.scala`: `fromString` accepts the four
      keys case-insensitively with surrounding whitespace, rejects `"relay"` and `""` with the message listing the
      keys, `values.map(_.key)` are distinct, `isFollower` true only for Replica/Archive.
- [ ] T007 Add config keys to `src/main/resources/conf/base/fukuii.conf` per data-model.md §5: `chain =
      ${fukuii.blockchains.network}`, `role = "ingest"`, `admin { enabled = true, interface = "localhost", port = 8560,
      drain-timeout = 30.seconds, ready-lag-blocks = 64 }`, `tx-forward { endpoint = null, max-per-second = 50,
      timeout = 5.seconds }`, with comments. Leave `datadir` for T041.
- [ ] T008 Extend `src/main/scala/com/chipprbots/ethereum/utils/InstanceConfig.scala`: `val chainKey: String =
      config.getString("chain")`; `val role: Role = Role.fromString(config.getString("role")).fold(m => throw new
      IllegalArgumentException(s"fukuii.role: $m"), identity)`; `object Admin { enabled, interface, port, drainTimeout:
      FiniteDuration, readyLagBlocks }`; `object TxForward { endpoint: Option[String], maxPerSecond, timeout }`.
      Update the `[InstanceConfig] Protocol capabilities` log line (line 80-83) to print `chain=<chainKey> role=<role>`.
- [ ] T009 Extend `src/main/scala/com/chipprbots/ethereum/ConfigValidator.scala`: in `checkPortClash` (line 83) add the
      admin port when `admin.enabled`; add a fatal error when `fukuii.role` is not a `Role` key; add a WARN (not fatal)
      when `admin.interface` is not `localhost`/`127.0.0.1`/`::1`; add a fatal error when `mining.mining-enabled = true`
      and `role != ingest`.
- [ ] T010 [P] Extend `src/test/scala/com/chipprbots/ethereum/ConfigValidatorSpec.scala` with one case per T009 rule
      (admin/RPC port clash, bad role, non-loopback admin WARN present in `removedKeyWarnings`-style output, mining on a
      replica is fatal).
- [ ] T011 Create `src/main/scala/com/chipprbots/ethereum/admin/LifecycleController.scala`: `enum LifecycleState {
      Running, Draining, Fenced, Stopping }`; `final class LifecycleController(role: Role, clock: () => Long)` holding
      an `AtomicReference[LifecycleState]`; `drain(timeout): IO[LifecycleState]`, `fence(): IO[Either[LifecycleError,
      Unit]]`, `unfence()`, `stop()`; `registerFenceable(hook: Fenceable)` and `registerDrainable(hook: Drainable)`
      (traits with `onFence/onUnfence: IO[Unit]`, `onDrain: IO[Unit]`); `LifecycleError = NotSupportedByRole |
      InvalidTransition(from, signal) | HookFailed(name, cause) | Timeout`. Transition table = data-model.md §2.
      Persistence and subsystem hooks are injected (T063), not referenced here.
- [ ] T012 [P] Create `src/test/scala/com/chipprbots/ethereum/admin/LifecycleControllerSpec.scala`: every allowed and
      refused transition of data-model.md §2; `fence` on `Role.Replica` → `NotSupportedByRole`; hook order is
      registration order; a failing hook restores the previous state and runs no later hook; uses a fake clock and
      `IO` run with `unsafeRunSync()` (no sleeps; timeouts via `TestControl.executeEmbed`).
- [ ] T013 Create `src/main/scala/com/chipprbots/ethereum/admin/HealthReporter.scala`: `case class HealthSnapshot(...)`
      mirroring data-model.md §3 (all fields, `Option` for nullable ones); `trait HealthSources` with `role`,
      `chainKey`,
      `chainId`, `head: () => Option[(BigInt, ByteString)]`, `finalized: () => Option[(BigInt, ByteString)]`,
      `syncStatus: IO[SyncProtocol.Status]`, `peerCount: IO[Int]`, `lifecycle: () => LifecycleState`, `exporter:
      Option[ExporterHealthSource]`, `retention: Option[RetentionWindowSource]`, `followerHealth: Option[IO[Boolean]]`
      (sim); `HealthReporter.snapshot(sources, config): IO[HealthSnapshot]` with the per-role readiness rules of
      contracts/role-matrix.md §Readiness and the ETC rule "finalized = block 0". json4s serialisation to the documented
      field names; exporter fields emitted only for `Ingest`, `retentionWindow` only for followers.
- [ ] T014 [P] Create `src/test/scala/com/chipprbots/ethereum/admin/HealthReporterSpec.scala`: fake `HealthSources`
      per role; asserts field presence/absence per role, `ready` truth table (follower ready with `peerCount = 0`;
      ingest not ready in `Draining`; sim ready iff follower ready ∧ lag ≤ bound), ETC finalized is genesis, JSON keys
      match data-model.md §3 exactly (golden string compare on a fixed snapshot).
- [ ] T015 Create `src/main/scala/com/chipprbots/ethereum/admin/AdminHttpServer.scala`: Pekko HTTP `Route` with `GET
      /health` (200 always), `GET /ready` (200/503), `POST /lifecycle/{drain,fence,unfence,stop}` with the status codes
      and error envelope of contracts/admin-api.md; everything else 404; `def run(): Future[Http.ServerBinding]` using
      `Http(actorSystem).newServerAt(config.interface, config.port).bind(route)` (pattern:
      `src/main/scala/com/chipprbots/ethereum/jsonrpc/server/http/InsecureJsonRpcHttpServer.scala:25-35`); `def stop():
      Future[Unit]` unbinds. Serialise with `org.json4s.native.Serialization` as `JsonRpcHttpServer.scala:52-54`.
- [ ] T016 [P] Create `src/test/scala/com/chipprbots/ethereum/admin/AdminHttpServerSpec.scala` with Pekko HTTP
      `ScalatestRouteTest` (add `pekko-http-testkit` to the test dependencies in `build.sbt` if absent): GET /health 200
      body = reporter output; GET /ready 503 when not ready; POST drain 202; POST fence on replica 400
      `not_supported_by_role`; POST unfence from Running 409 `invalid_transition`; `POST /` with a JSON-RPC body 404;
      unknown path 404.
- [ ] T017 Add `trait AdminServerBuilder` to `src/main/scala/com/chipprbots/ethereum/nodebuilder/NodeBuilder.scala`
      (self-type `ActorSystemBuilder & InstanceConfigProvider & BlockchainBuilder & SyncControllerBuilder &
      NetServiceBuilder & EngineApiBuilder & Logger`): `lazy val lifecycleController`, `lazy val healthSources:
      HealthSources` (head from `blockchainReader.getBestBlock`, finalized from `forkChoiceManager` on ETH family,
      `syncController` ask, `netService.peerCount`), `lazy val adminHttpServer: Option[AdminHttpServer]`; mix it into
      `trait Node` (line 1299-1381); in `nodebuilder/StdNode.scala` `BaseNode.start()` call `startAdminServer()`
      (awaited bind, 10 s, pattern of `startJsonRpcHttpServer` lines 232-249) right after Phase 1 and before any RPC
      server; in `shutdown` stop it **last**, after `storagesInstance.dataSource.close()`.
- [ ] T018 [P] Create `src/main/scala/com/chipprbots/ethereum/admin/FleetMetrics.scala` (`extends MetricsContainer`):
      gauges `fleet.head.number`, `fleet.finalized.number`, `fleet.source.lag`, `fleet.ready`, `fleet.fenced`,
      `fleet.lifecycle.state` with tags `role`, `chain`, registered from `AdminServerBuilder` against the latest
      `HealthSnapshot` (an `AtomicReference` refreshed by each `GET /health` and by a 5 s scheduler tick on
      `classicSystem.scheduler`). Document names in data-model.md §3 (already listed).

**Checkpoint 2A**: `sbt compile-all` 0 errors; green run of
`./scripts/fukuii-test only "*RoleSpec* *LifecycleControllerSpec* *HealthReporterSpec* *AdminHttpServerSpec*"`
and `./scripts/fukuii-test only "*ConfigValidatorSpec*"`.

### 2B — fleet harness (phase C; blocks US2, US3, US4 scenarios and SC-006; not US1/US5 unit work)

- [ ] T019 Create `ops/fleet-harness/docker-compose.yml`: services `etc-ingest`, `etc-replica`, `etc-sim`, `eth-ingest`,
      `eth-ingest-standby` (profile `sc004`, not started by default), `eth-replica`, `eth-archive`, `eth-sim`, `kafka`
      (single KRaft broker, `apache/kafka:<pinned>`), `loadgen` (python:3-slim with `scripts/` mounted); every fukuii
      service: `image: ${FUKUII_IMAGE:-chipprbots/fukuii:local}`, `command: ["--chain","<etc-devnet|eth-devnet>",
      "--role","<role>"]`, `mem_limit`, `-Dfukuii.metrics.enabled=true`, admin port published on `127.0.0.1:1<port>`
      only, `tx-forward.endpoint` pointing at the chain's ingest, `fukuii.sim.follower` at the chain's replica; one
      bridge network per chain.
- [ ] T020 [P] Create `ops/fleet-harness/conf/etc-devnet.conf` (Mordor-class: copy
      `ops/gorgoroth/conf/base-gorgoroth.conf`
      genesis/fork schedule, chain id `31337`, `mining.protocol = pow`, `mining-enabled = true` **only** via the
      ingest service's `-Dfukuii.mining.mining-enabled=true`, `discovery-enabled = false`, static peer list empty) and
      `ops/fleet-harness/conf/eth-devnet.conf` (Platåberget-class: `conf/hive.conf`-style genesis with all timestamp
      forks at 0, chain id `1337`, `network-type = "eth"`, `engine-api.enabled = false`, `rpc.apis` + `testing` on the
      ingest only). Both set `rpc.http.interface = "0.0.0.0"` (container-internal).
- [ ] T021 Create `ops/fleet-harness/harness.py` (Python 3 stdlib only; run with `python3 -I`): subcommands `up
      [--wait-ready S]`, `down [--volumes]`, `baseline --runs N --blocks B`, `sc002`, `sc003 --kill|--oom <chain>`,
      `sc004`, `sc005`, `sc006 ...`; readiness wait polls each admin `/health`; writes
      `results/<scenario>-<yyyymmdd>-<shortsha>.json` in the data-model.md §7 shape; exits non-zero on FAIL.
- [ ] T022 [P] Create `ops/fleet-harness/load/read-burst.py`: N concurrent clients issuing `eth_call` /
      `eth_simulateV1` / `debug_traceCall` at a pinned block against a target RPC URL for D seconds; records
      client-side latency histogram (p50/p99) and a count per JSON-RPC error code; stdlib only.
- [ ] T023 [P] Document in `ops/fleet-harness/README.md` how the harness drives `scripts/ephemery/load-gen.py`
      against a follower (forwarding) and the ingest, with `--force-chain` not needed for ids 1337/31337; add a
      `loadgen` wrapper target to `harness.py` (`sc006` uses it).
- [ ] T024 Add a block-import duration timer to
      `src/main/scala/com/chipprbots/ethereum/blockchain/sync/regular/RegularSyncMetrics.scala`
      (`final val BlockImportTimer: Timer = metrics.timer("regularsync.block.import.duration")`) and record it in
      `src/main/scala/com/chipprbots/ethereum/blockchain/sync/regular/BlockImporter.scala` around one block's import
      (start before validation/execution, stop after the canonical write); metrics only, no control-flow change;
      `eye` confirms `testOnly *BlockImporter*` unchanged.
- [ ] T025 [P] Create `ops/fleet-harness/metrics.py`: scrape `http://<ingest>:13798/metrics` every 5 s, parse the
      `app_regularsync_block_import_duration_seconds` histogram, compute p99 over the window; expose
      `import_p99_ms(window)` to `harness.py`.
- [ ] T026 Run GT-3: `python3 -I harness.py baseline --runs 3 --blocks 300`; commit
      `ops/fleet-harness/results/gt3-baseline-<date>-<sha>.json`; append the band table (per chain: import p99 × 3,
      read p99 × 3, band) to `specs/023-fleet-topology/research.md` §"Phase 0 (plan) — decisions" D-8.
- [ ] T027 [P] Create `ops/fleet-harness/results/README.md` (what each file is, how verdicts are computed, that only
      baselines and release-evidence runs are committed) and add `ops/fleet-harness/results/*.json` except
      `gt3-baseline-*` and `sc00*-*` to `.gitignore` (scratch runs stay local).

**Checkpoint 2B**: `harness.py up --wait-ready 600` shows eight `ready: true` health bodies; baseline committed.

---

## Phase 3: User Story 1 — An operator runs today's single node unchanged (Priority: P1) 🎯 MVP

**Goal**: retire the in-process runtime (FR-006, SC-001), add `--chain`/`--role` with `fukuii <network>` ≡ `--chain
<network> --role ingest` (FR-001), gate subsystem start on the role (FR-008, DC-9).

**Independent Test**: quickstart.md §1 and §2; SC-001 grep returns only KEEP rows; `testEssential` at baseline.

### Slice S1 — runtime retirement (research §2, exactly)

- [ ] T028 [US1] `git rm src/main/scala/com/chipprbots/ethereum/runtime/FukuiiRuntime.scala
      src/main/scala/com/chipprbots/ethereum/runtime/ChainInstance.scala` (research §2.1 rows 1-2; `isMultiInstance`
      has no other caller — re-grep first).
- [ ] T029 [US1] In `src/main/scala/com/chipprbots/ethereum/App.scala` replace lines 264-271 (`case Some("runtime")`)
      with `case Some("runtime") => log.error("fukuii runtime was removed: run one process per chain and role
      (fukuii <network> is --chain <network> --role ingest); see docs/runbooks/operating-modes.md"); sys.exit(2)`.
- [ ] T030 [US1] Add `ConfigValidator.validateRoot(root: TypesafeConfig): List[String]` in
      `src/main/scala/com/chipprbots/ethereum/ConfigValidator.scala` returning the fatal message of data-model.md §5
      when `root.hasPath("fukuii-runtime.instances")`; call it from
      `src/main/scala/com/chipprbots/ethereum/Fukuii.scala`
      next to `ConfigValidator.validate(Config.config)` (line ~55) with `ConfigFactory.load()`; exit 1 on errors.
      Add the case to `src/test/scala/com/chipprbots/ethereum/ConfigValidatorSpec.scala`.
- [ ] T031 [US1] `src/main/scala/com/chipprbots/ethereum/utils/InstanceConfig.scala`: remove the `instanceId`
      constructor parameter (line 29) and its doc lines (26-27); line 67 becomes
      `config.getString("blockchains.network")`
      (no fallback). `src/main/scala/com/chipprbots/ethereum/utils/Config.scala:27` → `extends
      InstanceConfig(ConfigFactory.load().getConfig("fukuii"))`; delete the sentence "For multi-instance mode, create
      new `InstanceConfig` instances instead." (lines 25-26). Fix every test constructing `new InstanceConfig(_, _)`
      (`grep -rn "new InstanceConfig(" src/test src/it`).
- [ ] T032 [US1] Rewire: `src/main/scala/com/chipprbots/ethereum/nodebuilder/NodeBuilder.scala:112` →
      `ActorSystem(s"fukuii_${instanceConfig.chainKey}", ConfigFactory.load())`;
      `src/main/scala/com/chipprbots/ethereum/nodebuilder/StdNode.scala:121` → `Metrics.configure(metricsConfig)`;
      `StdNode.scala:284` → `"PeriodicDBConsistencyCheck"`.
- [ ] T033 [US1] `src/main/scala/com/chipprbots/ethereum/metrics/Metrics.scala`: delete lines 79-142's registry
      (`instances`, `forInstance`, the Bug 29 WARN, `closeInstance`); keep `get()`, a `configure(config: MetricsConfig):
      Try[Unit]` that builds and starts one `Metrics` and sets `defaultRef` (second call: close the new one, no-op), and
      `close`. Delete the comment block `src/main/scala/com/chipprbots/ethereum/metrics/MetricsContainer.scala:6-11`.
      Reword `src/main/resources/conf/base/metrics.conf:26` (drop "collides with multi-instance setups"; keep the
      "Address already in use" reason as "when another exporter already holds 9095"). Reword
      `src/main/scala/com/chipprbots/ethereum/jsonrpc/EthTxService.scala:89-94` to "taken as an implicit constructor
      parameter so callers supply the chain's config explicitly rather than reading the global singleton".
- [ ] T034 [US1] `git rm src/test/scala/com/chipprbots/ethereum/metrics/MetricsMultiInstanceSpec.scala`.
- [ ] T035 [US1] `git rm src/main/resources/conf/multi-instance.conf
      ops/barad-dur/sepolia/fukuii-conf/multi-network.conf
      ops/barad-dur/sepolia/docker-compose-multi.yml ops/barad-dur/multi-etc-mordor/conf/multi-network.conf`; rewrite
      `ops/barad-dur/multi-etc-mordor/docker-compose.yml` as two services `fukuii-etc` (`etc`, ports 8545/30303/9095,
      `-Xmx4g`) and `fukuii-mordor` (`mordor`, ports 8547/30304/9096, `-Xmx3g`) each with its own data mount and
      `command: ["<network>"]`; reword `ops/barad-dur/sepolia/docker-compose.yml:19` ("the runtime image" → "the
      fukuii image").
- [ ] T036 [US1] Verify the banner at the top of `docs/architecture/pluggable-consensus-vision.md` (lines 7-14) is
      present (it is on this tree) and add a CHANGELOG entry under Unreleased: "Removed: `fukuii runtime`,
      `conf/multi-instance.conf`, the in-process multi-instance runtime (spec 023 FR-006, D-1)".
- [ ] T037 [P] [US1] Create `src/test/scala/com/chipprbots/ethereum/utils/ProfileLoadSpec.scala`: for every
      `src/main/resources/conf/*.conf` except `faucet.conf`, `testmode.conf`, `enterprise-template.conf`:
      `ConfigFactory.parseResources("conf/<name>.conf").withFallback(ConfigFactory.load()).resolve()`
      `.getConfig("fukuii")` constructs an `InstanceConfig` without throwing and `role == Role.Ingest`, `chainKey == blockchains.network`.
- [ ] T038 [US1] SC-001: run the grep of quickstart.md §1 (only KEEP rows remain); `sbt compile-all`; `sbt
      scalafmtAll`; `./scripts/fukuii-test essential` and compare with T002 (`eye`); open the S1 PR (`prism` review).

### Slice S2 — `--chain` / `--role`, datadir, first-start identity

- [ ] T039 [US1] `src/main/scala/com/chipprbots/ethereum/App.scala`: add `private def extractFleetFlags(args):
      (Option[String] chain, Option[String] role, remaining)` accepting `--chain X`, `--chain=X`, `--role Y`,
      `--role=Y`;
      in `main` (line 233) call it first; `Role.fromString(role)` failure → `log.error(...)`, `sys.exit(2)` before
      `setNetworkConfig`; `System.setProperty("fukuii.role", key)`; `chain.foreach { c =>
      System.setProperty("fukuii.chain",
      c); setNetworkConfig(c) }`; a positional network also sets `fukuii.chain`; update `showHelp()` (lines 139-231)
      with the two flags, the role list, and `fukuii <network>` ≡ `--chain <network> --role ingest`.
- [ ] T040 [P] [US1] Extend `src/test/scala/com/chipprbots/ethereum/AppSpec.scala` (reflection pattern of lines 46-71)
      and `src/test/scala/com/chipprbots/ethereum/LauncherIntegrationSpec.scala`: `fukuii sepolia` sets
      `fukuii.chain=sepolia`, `fukuii.role=ingest`; `--chain=mordor --role replica` sets both; `--role bogus` is
      rejected (assert on the parsed `Either`, not on `sys.exit`); modifiers still work with the flags.
- [ ] T041 [US1] `src/main/resources/conf/base/fukuii.conf:6` → `datadir =
      ${user.home}"/.fukuii/"${fukuii.chain}"/"${fukuii.role}`;
      confirm every derived path still hangs off `${fukuii.datadir}` (`fukuii.conf:11,14`, `keystore.conf:3`,
      `logging.conf:6`, `db.conf:4`, `network.conf:311`); grep `ops/` and `docker/` for hard-coded `/app/data/<chain>`
      mounts and point them at `/app/data/<chain>/ingest` (or set `fukuii.datadir` explicitly in the compose file).
- [ ] T042 [US1] Create `src/main/scala/com/chipprbots/ethereum/utils/DatadirLayout.scala`: `def
      legacyLayoutProblem(home: Path, chainKey: String, role: Role, configuredDatadir: Path): Option[String]` returning
      the `LegacyDatadirLayout` message (quickstart.md §1) when `configuredDatadir == home/.fukuii/chainKey/role.key`,
      `!(configuredDatadir/"rocksdb").exists` and `(home/.fukuii/chainKey/"rocksdb").exists`; call it in
      `src/main/scala/com/chipprbots/ethereum/Fukuii.scala` before `new StdNode` (after `ConfigValidator`),
      `sys.exit(2)`
      on `Some`. Create `src/test/scala/com/chipprbots/ethereum/utils/DatadirLayoutSpec.scala` with
      `Files.createTempDirectory`
      fixtures covering: legacy present → Some; new present → None; explicit non-default datadir → None; role replica
      with legacy ingest store → None (a replica never adopts an ingest store).
- [ ] T043 [US1] `src/main/scala/com/chipprbots/ethereum/db/storage/AppStateStorage.scala`: add `Keys.FleetChainKey =
      "fleet.chainKey"`, `Keys.FleetRole = "fleet.role"`, `fleetIdentity()`, `recordFleetIdentity(chainKey, roleKey)`
      (one `update` batch); in `nodebuilder/StdNode.scala` `BaseNode.start()` Phase 1, after `fixDatabase()`: if
      `fleetIdentity()` is `None` → `recordFleetIdentity(...).commitSync()`; if `Some((c, r))` differs → `log.error`
      with
      `DatadirOwnedByOtherChain`/`DatadirOwnedByOtherRole` and `shutdown()`+`sys.exit(2)` before any listener binds.
      Create `src/test/scala/com/chipprbots/ethereum/db/storage/AppStateStorageFleetSpec.scala` over `EphemDataSource`:
      absent → None; record → Some; survives a second `AppStateStorage` over the same source. `vault` reviews.
- [ ] T044 [P] [US1] `docs/runbooks/operating-modes.md`: new section "Roles and the fleet profile" (the role table of
      `specs/023-fleet-topology/contracts/role-matrix.md` copied verbatim, the single-node profile sentence, the
      `--chain`/`--role` flags) and "Datadir layout change in 0.12" with the exact `mv` sequence of quickstart.md §1
      and the `fukuii.datadir` alternative; `docs/runbooks/node-configuration.md`: `fukuii.chain`, `fukuii.role`,
      `fukuii.admin.*`, `fukuii.tx-forward.*` rows.

### Slice S3 — role-gated start and the "not served by this role" error

- [ ] T045 [US1] Create `src/main/scala/com/chipprbots/ethereum/nodebuilder/RoleProfile.scala`: `final case class
      RoleProfile(role: Role, bindsP2P: Boolean, bindsDiscovery: Boolean, bindsEngineApi: Boolean, startsSync: Boolean,
      mayMine: Boolean, opensStore: Boolean)` with `RoleProfile.of(role)` per contracts/role-matrix.md; `enum StartStep`
      (one case per `BaseNode.start()` step, `StdNode.scala:55-117`) and `object StartPlan { def steps(profile:
      RoleProfile, engineApiEnabled: Boolean, miningEnabled: Boolean, engineApiBindsLast: Boolean, fenced: Boolean):
      List[StartStep] }` preserving today's order and the hive bind-order rule (`StdNode.engineApiBindsLast`).
- [ ] T046 [US1] Refactor `src/main/scala/com/chipprbots/ethereum/nodebuilder/StdNode.scala` `BaseNode.start()` to
      `StartPlan.steps(...).foreach(execute)` with a `private def execute(step: StartStep): Unit` mapping each step to
      the existing private method (no behaviour change for `Ingest` with `fenced = false`); gate `shutdown` (lines
      319-362) with the same profile: skip `peerDiscoveryManagerTyped ! Stop`, `mining.stopProtocol()`,
      `maybeEngineApiServer`, `shutdownDiscoveryIoRuntime`, `stopPortForwarding()` when the profile never started them.
- [ ] T047 [US1] `src/main/scala/com/chipprbots/ethereum/nodebuilder/NodeBuilder.scala:956-963` (`engineApiConfig`):
      `enabled = <configured> && instanceConfig.role == Role.Ingest`; log at INFO when a configured Engine API is
      disabled by role. No other change: `bindInvalidChainReporter` (997), `bindDesignatedHead` (1013),
      `maybeEngineApiServer` (1024) already check this flag.
- [ ] T048 [US1] `nodebuilder/StdNode.scala` `startMining()` (lines 209-213) runs only when `RoleProfile.mayMine`
      (T009 already makes the misconfiguration fatal; this is the belt).
- [ ] T049 [P] [US1] `src/main/scala/com/chipprbots/ethereum/jsonrpc/JsonRpcError.scala`: add
      `def NotServedByRole(role: Role, method: String): JsonRpcError` (-32020, data `{role, method}`),
      `val IngestFenced` (-32021), `def ForwardRateCapExceeded(maxPerSecond: Int)` (-32022) per data-model.md §6; add
      the json4s encoders where `RateLimitInformation` has its (line 33 pattern).
- [ ] T050 [US1] Create `src/main/scala/com/chipprbots/ethereum/jsonrpc/RoleMethodPolicy.scala`: `def
      servedBy(role: Role, method: String): Boolean` implemented as a table of namespace prefixes and method names from
      contracts/role-matrix.md §"RPC namespaces" (`Ingest` serves everything; followers/sim per the table;
      `eth_sendRawTransaction`/`eth_sendTransaction` are "served" on every role because the forwarder (T079) handles
      them); hook into `src/main/scala/com/chipprbots/ethereum/jsonrpc/server/controllers/JsonRpcBaseController.scala`
      `handleRequest` (lines 50-61): `if !RoleMethodPolicy.servedBy(role, request.method) then
      IO.pure(errorResponse(request, JsonRpcError.NotServedByRole(role, request.method)))` before the `enabledApis`
      fold; `role` comes from a new abstract `def role: Role` on the controller, supplied by `JSONRpcControllerBuilder`
      (NodeBuilder.scala:894) from `instanceConfig.role` and by test fixtures (`JsonRpcControllerFixture.scala`) as
      `Role.Ingest`.
- [ ] T051 [P] [US1] Create `src/test/scala/com/chipprbots/ethereum/jsonrpc/RoleMethodPolicySpec.scala` asserting
      every row of contracts/role-matrix.md §"RPC namespaces" (table-driven: method → expected served set), and add to
      `src/test/scala/com/chipprbots/ethereum/jsonrpc/JsonRpcControllerSpec.scala` two cases: controller with
      `role = Role.Replica` answers `admin_addPeer` with code -32020 and data
      `{"role":"replica","method":"admin_addPeer"}`;
      `role = Role.Ingest` behaviour unchanged for the same request.
- [ ] T052 [P] [US1] Create `src/test/scala/com/chipprbots/ethereum/nodebuilder/StartPlanSpec.scala`: for
      `Role.Ingest` with `engineApiEnabled = true/false` and both `engineApiBindsLast` values the step list equals
      today's order (golden list, including the RPC/Engine bind-order rule of `RpcBindOrderSpec`); for
      `Replica`/`Archive`/`Sim` the list contains none of `StartServer`, `LoadStaticNodes`, `StartPortForwarding`,
      `StartPeerManager`, `StartDiscoveryManager`, `StartEngineApiServer`, `StartMining`; `Sim` contains no store
      repair steps; `fenced = true` on `Ingest` removes `StartSyncController` and `StartEngineApiServer`.

**Checkpoint US1**: quickstart.md §1 and §2 pass; `testEssential` at baseline; S1, S2, S3 PRs merged (phase B).

---

## Phase 4: User Story 2 — A simulation burst does not move import latency (Priority: P1)

**Goal**: the `sim` role executes `eth_call`, `eth_simulateV1`, `debug_trace*` over a follower's state at a pinned
block on spec 018 Track R slice R1's bounded executors (FR-003), rejects excess work with R1's explicit error, and
SC-002 holds in the harness.

**Independent Test**: quickstart.md §7 (`harness.py sc002`).

**⚠️ BLOCKED on spec 018 Track R slice R1** (`specs/018-storage-engine-consolidation/plan.md:251`): T054–T055 consume
R1's executor API and rejection error; do not implement a second executor mechanism. T053 and T056 can start once S3
has merged.

- [ ] T053 [US2] Create `src/main/scala/com/chipprbots/ethereum/jsonrpc/client/BlockPinnedStateClient.scala`: a
      JSON-RPC HTTP client (Pekko HTTP) to `fukuii.sim.follower` (new key, default `null`, added to
      `src/main/resources/conf/base/fukuii.conf` and `InstanceConfig.Sim`) that pins a block hash from the follower's
      `GET /health` head (or the request's block parameter) and resolves `eth_getBalance`, `eth_getTransactionCount`,
      `eth_getCode`, `eth_getStorageAt` at that hash with a bounded per-request LRU; expose it as a read-only state
      source for the existing call/simulate services (adapter implementing the state-reading trait the services already
      take — identify it in `jsonrpc/EthSimulateService*.scala` / `jsonrpc/EthInfoService.scala`; if the adapter needs
      anything under `ledger/`, stop and route through `beacon`/`forge`, plan.md R-3).
- [ ] T054 [US2] [blocked: 018 R1] Wire `EthSimulateService`, `eth_call`/`eth_estimateGas` and `debug_trace*` on a
      `Sim` node onto R1's bounded executors with the `sim` profile's per-method caps, gas caps and timeouts
      (`fukuii.network.rpc.executors.*` keys as R1 names them); cancel on client disconnect through R1's mechanism;
      no new pool, no new queue.
- [ ] T055 [US2] [blocked: 018 R1] Surface R1's rejection error unchanged from `sim`; add its code to
      `specs/023-fleet-topology/data-model.md` §6 (replace `<018-r1-code>`) and to
      `ops/fleet-harness/load/read-burst.py`'s
      counted codes.
- [ ] T056 [US2] `admin/HealthReporter`: `Sim` readiness and `sourceLag` from `BlockPinnedStateClient` (follower
      `/health` `ready` and head − pinned head); add the cases to
      `src/test/scala/com/chipprbots/ethereum/admin/HealthReporterSpec.scala`.
- [ ] T057 [US2] Implement `sc002` in `ops/fleet-harness/harness.py`: start `read-burst.py` with `--sim-clients`
      against `eth-sim` (and `etc-sim`) for `--duration`; sample `eth-ingest`/`etc-ingest` import p99 via `metrics.py`;
      assert within baseline ± band; assert rejections carry the R1 code (never a timeout from an unbounded queue);
      sample 100 `eth_call`s and assert byte-identical results between `eth-sim` and `eth-replica` at the same pinned
      block; write `results/sc002-*.json`.
- [ ] T058 [P] [US2] Create `src/test/scala/com/chipprbots/ethereum/jsonrpc/client/BlockPinnedStateClientSpec.scala`:
      a fake follower (Pekko HTTP `ScalatestRouteTest` server or an in-process `Http().newServerAt("localhost", 0)`)
      answering fixed values; asserts pinning by hash, LRU hit, follower error passthrough (026 "outside retention
      window" error body unchanged), and `sourceLag` computation. No sleeps.

**Checkpoint US2**: `sc002` PASS committed; `SimRole`-related specs green; S9 PR merged (phase D).

---

## Phase 5: User Story 3 — One chain fails, the others do not notice (Priority: P1)

**Goal**: SC-003 — killing or OOM-ing every process of one chain leaves the other chain's import p99 and read p99
inside the GT-3 band. The guarantee is the process boundary (DC-1) plus per-container memory limits; the work here is
the scenario and its evidence.

**Independent Test**: quickstart.md §6.

- [ ] T059 [US3] Implement `sc003` in `ops/fleet-harness/harness.py`: with `load-gen.py` at a fixed TPS on both
      ingests and `read-burst.py` at a fixed rate on both replicas, record 5 min of eth figures (import p99 via
      `metrics.py`, read p99 from `read-burst.py`); `--kill etc`: `docker kill` every `etc-*` fukuii container; `--oom
      etc`: raise `read-burst.py` concurrency against `etc-replica` until Docker reports an OOM kill (`mem_limit`),
      then continue 5 min; compare eth before/after with baseline ± band; write `results/sc003-*.json`.
- [ ] T060 [P] [US3] In `ops/fleet-harness/docker-compose.yml` give every fukuii service `mem_limit` and
      `JAVA_TOOL_OPTIONS` with `-Xmx` below it and `-XX:+ExitOnOutOfMemoryError` (pattern:
      `ops/barad-dur/sepolia/docker-compose.yml` `x-fukuii-jvm-defaults`), so the OOM variant kills one process, not the
      host.
- [ ] T061 [US3] Run `sc003 --kill etc` and `sc003 --oom etc` once each on the build host after T026's baseline;
      commit the two result files; `eye` records the verdicts in `specs/023-fleet-topology/plan.md` §Open risks (close
      or keep R-4).

**Checkpoint US3**: both `sc003` results PASS.

---

## Phase 6: User Story 4 — The ingest node is replaced (Priority: P2)

**Goal**: lifecycle signals `drain`, `fence`, `unfence`, `stop` on the admin surface (FR-005, FR-009), the durable
fence in AppState, `SIGTERM` = drain then stop, fenced start-up, fenced transaction refusal; SC-004 in the harness.

**Independent Test**: quickstart.md §4; `LifecycleControllerSpec`, `SyncControllerPauseSpec`,
`AppStateStorageFleetSpec`.

- [ ] T062 [US4] `src/main/scala/com/chipprbots/ethereum/db/storage/AppStateStorage.scala`: `Keys.FleetFenced =
      "fleet.fenced"`, `isFenced()`, `fencedAt(): Option[Long]`, `setFenced(nowMillis)`, `clearFenced()` as single
      batches; extend `src/test/scala/com/chipprbots/ethereum/db/storage/AppStateStorageFleetSpec.scala`: set →
      `isFenced` true and `fencedAt` equals the value; `clearFenced` → false; the marker survives constructing a second
      `AppStateStorage` over the same `EphemDataSource`. `vault` reviews (one synced batch per write).
- [ ] T063 [US4] Wire persistence and subsystems into `LifecycleController` from `AdminServerBuilder`
      (`nodebuilder/NodeBuilder.scala`): `fence` on `Ingest` runs, in order, (1) state → `Fenced`, (2) `syncController !
      SyncController.WrappedSyncProtocol(SyncProtocol.Pause)` and await the `Paused` reply (ask,
      `asyncConfig.askTimeout`),
      (3) fenceable hooks, (4) `engineApiServerRef.getAndSet(None).foreach(_.stopSync(shutdownTimeout))` (ETH family),
      (5) `appStateStorage.setFenced(now).commitSync()`, (6) refresh the health snapshot; `unfence` reverses:
      `clearFenced().commitSync()`, `engineApiServerRef.set(buildEngineApiServer())` + `start()` awaited, `Resume`
      with reply, hooks' `onUnfence`. Replace `lazy val maybeEngineApiServer` (NodeBuilder.scala:1024-1030) with
      `def buildEngineApiServer(): Option[EngineApiHttpServer]` plus `val engineApiServerRef:
      AtomicReference[Option[EngineApiHttpServer]]` that `StdNode.start/shutdown` use (`StdNode.scala:256-267,331-337`).
- [ ] T064 [US4] `src/main/scala/com/chipprbots/ethereum/blockchain/sync/SyncProtocol.scala`: add `final case class
      Pause(replyTo: ActorRef[Paused.type])`, `case object Paused`, `final case class Resume(replyTo:
      ActorRef[Resumed.type])`, `case object Resumed` as `SyncProtocolMsg`s; in
      `src/main/scala/com/chipprbots/ethereum/blockchain/sync/SyncController.scala` (and `regular/RegularSync.scala` if
      it owns the fetcher) handle `Pause`: let the in-flight import finish, stop the block fetcher
      (`BlockFetcherStopped` path), ignore `NewCanonicalHead`/`MinedBlock`/new-block imports while paused, reply
      `Paused` once idle; `Resume` restarts fetching and replies `Resumed`; `GetStatus` keeps answering. Create
      `src/test/scala/com/chipprbots/ethereum/blockchain/sync/SyncControllerPauseSpec.scala` with Pekko TestKit probes:
      Pause replies once the in-flight import completes; no import after Pause; Resume replies and imports again;
      `GetStatus` answers while paused. `eye` validates; no consensus file touched.
- [ ] T065 [US4] Fenced start-up: in `nodebuilder/StdNode.scala` `BaseNode.start()` compute `fenced =
      instanceConfig.role == Role.Ingest && appStateStorage.isFenced()` and pass it to `StartPlan.steps` (T045 already
      removes `StartSyncController` and `StartEngineApiServer`); set `lifecycleController` initial state `Fenced`; log
      WARN `"starting FENCED: no import, no export, Engine API not bound; POST /lifecycle/unfence to resume"`.
- [ ] T066 [US4] Fenced transaction refusal: in `src/main/scala/com/chipprbots/ethereum/jsonrpc/EthTxService.scala`
      `sendRawTransaction` (line 229) and the `eth_sendTransaction` path, and in
      `src/main/scala/com/chipprbots/ethereum/transactions/TxAdmission.scala` (object at line 95) check an injected
      `isFenced: () => Boolean` before admission and answer `JsonRpcError.IngestFenced`; add cases to
      `src/test/scala/com/chipprbots/ethereum/jsonrpc/JsonRpcControllerEthSpec.scala` (fenced → -32021; not fenced →
      unchanged) and to the `TxAdmission` spec.
- [ ] T067 [US4] Drain: `LifecycleController.drain` sets `Draining`, sends `Pause` on `Ingest`, runs drainable hooks,
      and completes when in-flight RPC counts reach zero or the timeout elapses; in
      `src/main/scala/com/chipprbots/ethereum/jsonrpc/server/http/JsonRpcHttpServer.scala` wrap the `route` (line 78)
      in a directive that answers HTTP 503 with `{"jsonrpc":"2.0","error":{"code":-32000,"message":"node is
      draining"},"id":null}` when `lifecycle.state != Running`/`Fenced` (reads stay allowed while `Fenced`), and
      counts in-flight requests via an `AtomicInteger` the controller reads; same directive on WS and IPC request
      handling. Extend `src/test/scala/com/chipprbots/ethereum/admin/AdminHttpServerSpec.scala` and add
      `src/test/scala/com/chipprbots/ethereum/jsonrpc/JsonRpcHttpServerHealthRoutesSpec.scala` case: RPC `POST /`
      returns 503 while draining, `GET /healthcheck` still 200.
- [ ] T068 [US4] SIGTERM mapping: `nodebuilder/StdNode.scala` `shutdown` (line 319) begins with
      `lifecycleController.drainAndAwait(instanceConfig.Admin.drainTimeout)` (no-op if already `Draining`/`Stopping`),
      then the existing sequence; `POST /lifecycle/stop` calls `shutdown()` on a separate thread and then
      `sys.exit(0)`. Document the relation to `shutdown-timeout` (15 s) in `docs/runbooks/fleet-fencing.md` (T071).
- [ ] T069 [P] [US4] Extend `src/test/scala/com/chipprbots/ethereum/admin/LifecycleControllerSpec.scala` with the
      wired order: a recording fake for pause/hook/engine-close/marker proves order (1)→(5); a hook failure leaves the
      marker unwritten and the state `Running`; `unfence` clears the marker before rebinding; `drain` timeout returns
      `Timeout` without throwing.
- [ ] T070 [US4] Implement `sc004` in `ops/fleet-harness/harness.py` exactly as quickstart.md §4 steps 1–6 (compose
      profile `sc004` for `eth-ingest-standby`; volume copy while both stopped; fence; standby takes `testing_*`
      production; restart primary fenced; unfence); record `primary head frozen`, `fenced after restart`, `engine port
      closed/reopened` booleans and `"notes": "record-stream checker pending spec 025"` until 025 SC-003's checker
      exists; write `results/sc004-*.json`.
- [ ] T071 [P] [US4] Create `docs/runbooks/fleet-fencing.md`: when Orbita fences, the six fence steps, what a fenced
      process does and does not do, restart-while-fenced, unfence, SIGTERM/drain timings vs `shutdown-timeout`, and
      the two-ingest edge case fukuii cannot detect (spec §Edge cases; DC-4). Link from `docs/runbooks/README.md`.

**Checkpoint US4**: quickstart.md §4 manual steps pass on one node; `sc004` PASS (with the 025 note); S5 PR merged.

---

## Phase 7: User Story 5 — Orbita reads one process like any other (Priority: P1) 🎯 MVP

**Goal**: every role answers `GET /health` in the data-model.md §3 shape with per-role readiness that never needs
peers for roles without them (FR-004, SC-005), plus metric twins.

**Independent Test**: quickstart.md §3; `HealthReporterSpec`; `sc005`.

- [ ] T072 [US5] Finalise the per-role readiness rules in
      `src/main/scala/com/chipprbots/ethereum/admin/HealthReporter.scala`
      per contracts/role-matrix.md §Readiness: `Ingest` = `Running` ∧ best block present ∧ head advanced within
      `rpc.health.no-update-duration-threshold` (reuse the predicate of `NodeJsonRpcHealthChecker.scala:118-124`,
      extracted to a shared `HeadAdvanceTracker` in `jsonrpc/`) ∧ (ETC: `peerCount > 0`; ETH: Engine API bound ∧ a
      forkchoiceUpdated seen, or `peerCount > 0`) ∧ `sourceLag <= readyLagBlocks`; followers = `Running` ∧ store open
      ∧ `sourceLag <= readyLagBlocks`; readiness false in every non-`Running` state. Add the ETH/ETC branch cases to
      `HealthReporterSpec`.
- [ ] T073 [US5] Define `trait ExporterHealthSource { def exportLag: Option[Long]; def exportQueueDepth: Option[Long];
      def exportBufferBytes: Option[Long]; def exportLastError: Option[String] }` in `admin/HealthReporter.scala` and a
      `registerExporter(src)` on `AdminServerBuilder`; with none registered an `Ingest` emits the four fields as `null`
      (spec 025 T-007 registers the real one). Golden JSON case in `HealthReporterSpec`.
- [ ] T074 [US5] Define `trait RetentionWindowSource { def window: Option[(BigInt, BigInt)] }` and `registerRetention`
      likewise; followers emit `retentionWindow: null` until 026 registers; `archive` reports `fromBlock = 0` when
      registered. Golden JSON case in `HealthReporterSpec`.
- [ ] T075 [P] [US5] Create `src/test/scala/com/chipprbots/ethereum/jsonrpc/JsonRpcHttpServerHealthRoutesSpec.scala`
      (`ScalatestRouteTest` over `JsonRpcHttpServer.route` with a stub controller and health checker): `GET /health`,
      `/readiness`, `/healthcheck`, `/buildinfo` keep today's status codes and bodies (hive and
      `ops/*/docker-compose.yml`
      probe them), so the admin surface is provably additive.
- [ ] T076 [US5] Create `ops/fleet-harness/schema/health.schema.json` (JSON Schema draft 2020-12 of data-model.md §3
      with `role`-conditional `required` for the exporter and retention fields) and implement `sc005` in
      `ops/fleet-harness/harness.py`: for every fukuii container GET `/health` and validate (stdlib validator for the
      subset used: types, required, enum, conditional); assert followers report `ready: true` with `net_peerCount`
      `0x0`; assert `GET /ready` status matches `ready`; write `results/sc005-*.json`.
- [ ] T077 [P] [US5] In `sc005` also scrape `/metrics` of one container per role and assert the six `app_fleet_*`
      series exist with `role`/`chain` tags equal to the health body.
- [ ] T078 [P] [US5] Docs: add the `app_fleet_*` rows to `docs/operations/metrics-reference.md`; create
      `docs/api/admin-api.md` as the operator copy of `specs/023-fleet-topology/contracts/admin-api.md` (same tables),
      linked from `docs/api/README.md` or the API index that exists there.

**Checkpoint US5 (MVP with US1)**: quickstart.md §3 passes; `sc005` PASS; S4 PR merged.

---

## Phase 8: Polish & Cross-Cutting Concerns

- [ ] T079 Transaction ingress (FR-010, S6): create
      `src/main/scala/com/chipprbots/ethereum/transactions/TxForwarder.scala`
      — Pekko HTTP `Http().singleRequest` JSON-RPC client posting `eth_sendRawTransaction` to
      `instanceConfig.TxForward.endpoint` with `timeout`; a token bucket (`maxPerSecond` capacity and refill, monotonic
      clock injected) answering `JsonRpcError.ForwardRateCapExceeded` when empty; the ingest's `result` or `error`
      object returned unchanged; `None` endpoint → `JsonRpcError.NotServedByRole`. Wire in
      `src/main/scala/com/chipprbots/ethereum/jsonrpc/EthTxService.scala` `sendRawTransaction`/`sendTransaction`: when
      `role != Role.Ingest` delegate to the forwarder, else today's path. Create
      `src/test/scala/com/chipprbots/ethereum/transactions/TxForwarderSpec.scala` with an in-process fake ingest
      (`Http().newServerAt("localhost", 0)`): passthrough of result, passthrough of the ingest's -32021, bucket
      exhaustion → -32022 with a fake clock, no endpoint → -32020, timeout → `-32603`-class error with the cause. No
      sleeps. `conduit` reviews.
- [ ] T080 SC-006: add a Micrometer timer `txpool.admission.duration` in
      `src/main/scala/com/chipprbots/ethereum/transactions/TxAdmission.scala` around admission (metrics only);
      implement `sc006` in `ops/fleet-harness/harness.py` (load-gen from two followers and one sim at `--tps`, record
      ingest admission p99 and import p99, count -32022); run once; summarise the figures in
      `specs/023-fleet-topology/research.md` §"Phase 0 (plan) — decisions" (new D-11 "SC-006 figures") as the OQ-5 /
      spec 027 decision input.
- [ ] T081 [P] Create `docs/adr/architecture/ARCH-0xx-one-process-one-chain-one-role.md` (next free number) recording
      D-1 (retire the runtime), D-4 (Orbita is the control plane), DC-1/DC-4/DC-9 and the admin-surface decision
      (research D-1); link it from `specs/023-fleet-topology/plan.md` §Constitution Check row VII and from
      `docs/adr/README.md`.
- [ ] T082 [P] CHANGELOG under Unreleased: `--chain`/`--role`, admin port 8560, default datadir
      `~/.fukuii/<chainKey>/<role>` (**breaking**, migration note link), new error codes -32020/-32021/-32022, fence
      runbook; flag breaking changes per constitution VII.
- [ ] T083 Close-out: `sbt scalafmtAll`, `sbt formatCheck`, `sbt pp` on the clean tree, `./scripts/fukuii-test
      essential` compared with T002; run every quickstart.md section and record `VERIFY:` lines in the PR description;
      set `specs/023-fleet-topology/spec.md` `**Status**` to the lifecycle word `docs/development/spec-lifecycle.md`
      prescribes for implemented-awaiting-gates and regenerate `specs/README.md` with
      `python3 -I scripts/specs/index.py`.

---

## Dependencies & Execution Order

### Phase dependencies

- **Setup (T001–T004)**: T001 and T002 gate every Phase B task; T003 gates S1 (T028–T038); T004 is free.
- **Foundational 2A (T005–T018)**: after T001/T002; T005 → T008 → T009/T011/T013/T015/T017; T011 → T015; T013 →
  T015/T018; T017 last (needs T011, T013, T015).
- **Foundational 2B (T019–T027)**: needs an image with S1–S5 (T028–T052, T062–T075) for the role flags to exist; T024
  can land any time after T001 (metrics only); T026 after T019–T025.
- **US1 (T028–T052)**: S1 (T028–T038) first and alone in its PR; S2 (T039–T044) after S1; S3 (T045–T052) after S2 and
  T005–T010.
- **US5 (T072–T078)**: after 2A and S3 (role needed by readiness); T076/T077 after 2B.
- **US4 (T062–T071)**: after 2A, S3 and US5's T072 (readiness false when fenced); T070 after 2B and T063–T068.
- **US3 (T059–T061)**: after 2B (harness + baseline) only.
- **US2 (T053–T058)**: T053/T056/T058 after S3; **T054/T055 blocked on 018 R1**; T057 after 2B and T054.
- **Polish (T079–T083)**: T079 after S3 and T049; T080 after T079 and 2B; T081/T082 any time after S1; T083 last.

### Slice → tasks

| Slice | Tasks | Phase |
| --- | --- | --- |
| S1 | T028–T038 (+T003 gate) | B |
| S2 | T039–T044 | B |
| S3 | T045–T052 (+T005–T010) | B |
| S4 | T011, T013–T018, T072–T075, T078 | B |
| S5 | T062–T069, T071 | B |
| S6 | T079 | C |
| S7 | T004, T019–T027 | C |
| S8 | T059–T061, T070, T076–T077, T080 | C |
| S9 | T053–T058 | D (018 R1) |
| S10 | T081–T083, T036, T044 | B–C |

### Within each story

- Tests are written with (or before) the production change in the same task and must fail before it where that is
  meaningful (T012, T014, T016, T051, T052, T058, T064, T069, T079).
- Every file edit → `sbt compile-all`; after each slice's logic tasks → `./scripts/fukuii-test only "*<Spec>*"`;
  `./scripts/fukuii-test essential` once at the end of each slice (CLAUDE.md test cadence).
- `sbt scalafmtAll` before every commit; `sbt pp` before every PR.

---

## Parallel Examples

```bash
# Foundational 2A — independent files after T005/T007/T008 land:
Task: "T006 utils/RoleSpec.scala"            Task: "T010 ConfigValidatorSpec.scala cases"
Task: "T012 admin/LifecycleControllerSpec"   Task: "T014 admin/HealthReporterSpec"
Task: "T016 admin/AdminHttpServerSpec"       Task: "T018 admin/FleetMetrics.scala"

# S1 retirement — after T003's GT-1 sign-off, the deletions are independent files:
Task: "T028 git rm runtime/*"                Task: "T034 git rm MetricsMultiInstanceSpec"
Task: "T035 ops compose replacements"        Task: "T037 utils/ProfileLoadSpec.scala"

# S3 role gating — policy and plan are separate files:
Task: "T049 JsonRpcError codes"              Task: "T051 RoleMethodPolicySpec + controller cases"
Task: "T052 nodebuilder/StartPlanSpec"

# Harness 2B — after the compose file exists:
Task: "T020 conf/etc-devnet.conf + eth-devnet.conf"   Task: "T022 load/read-burst.py"
Task: "T025 metrics.py"                               Task: "T027 results/README.md + .gitignore"
```

---

## Implementation Strategy

### MVP first (US1 + US5)

1. T001–T003 (window, baseline, GT-1).
2. S1 (T028–T038) alone: the retirement PR; `testEssential` before and after.
3. 2A (T005–T018), then S2 (T039–T044), S3 (T045–T052), S4 (T072–T078): a single node starts unchanged with
   `fukuii <network>`, any role starts without binding P2P/Engine API/miner, and `GET /health` answers in one shape.
4. **STOP and VALIDATE**: quickstart.md §1, §2, §3 (manual, no harness yet).

### Incremental delivery

5. S5 (T062–T071): lifecycle, durable fence, SIGTERM → quickstart.md §4 manual steps.
6. Build the image; S7 harness (T019–T027) and the GT-3 baseline; S8 scenarios `sc005`, `sc003`, `sc004`; S6 forwarder
   and `sc006`.
7. When 018 R1 merges: S9 `sim` (T053–T058) and `sc002`.
8. S10 polish and close-out (T081–T083).

### Parallel team strategy

- Developer A: S1 → S2 → S3 (launcher/config/node wiring; the Phase B critical path).
- Developer B: 2A `admin/` package (T011–T018) → S4 → S5 (needs A's `Role` from T005 only).
- Developer C: harness 2B (T019–T027) against nightly images; S8 scenarios as slices land.
- `conduit` owns S4 and reviews S3/S5/S6; `vault` reviews T043, T062; `eye` runs every baseline and verdict.

---

## Notes

- 83 tasks: Setup 4 · Foundational 23 (2A 14, 2B 9) · US1 25 · US2 6 · US3 3 · US4 10 · US5 7 · Polish 5.
- No task edits another spec's files; cross-spec needs are in `plan.md` §"Cross-spec notes".
- No task changes `consensus/`, `vm/`, `mpt/`, `ledger/`; the two `blockchain/sync/` touches (T024 metrics, T064
  control messages) route through `eye`, and T053 stops and routes if it would need `ledger/`.
- `Thread.sleep` is forbidden in every spec listed here; use `eventually`, TestKit probes or `TestControl`.
