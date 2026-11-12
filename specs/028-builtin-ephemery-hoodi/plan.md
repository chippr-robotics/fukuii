# Implementation Plan: Ephemery and Hoodi as Built-in Networks

**Branch**: `spec/028-builtin-ephemery-hoodi` (plan); one branch per slice | **Date**: 2026-11-12 | **Spec**:
[spec.md](./spec.md). Its clarifications of 2026-11-12 are binding, including FR-016: warn and exit for a supervisor
restart.

**Input**:
- **Spec.** `specs/028-builtin-ephemery-hoodi/spec.md`.
- **Design record.** [research.md](./research.md), R1 to R17. It is built from three read-only research passes and the
  measurements of 2026-11-12.
- **Backlog.** B-227 (supersedes B-225).

## Summary

`fukuii hoodi` and `fukuii ephemery` start with no preparation, like `fukuii sepolia`.

**Hoodi** is a static built-in: a chain config and a re-encoded genesis under `conf/base/chains`, and `conf/hoodi.conf`
copied from Sepolia's. Every value comes from eth-clients/hoodi and is cross-checked against go-ethereum. The genesis
hash and the fork ids at each fork are pinned by tests.

**Ephemery** is too short-lived to be static. Its parts:
- **One rule engine.** The release rules of `fetch-genesis.sh` move into one Scala library, used by the node and by
  `fukuii cli ephemery-fetch`; the script becomes a wrapper (R1, R2).
- **A bundled iteration.** The current iteration ships in the jar, under `ephemery/bundled/`, not among the built-ins
  (R4).
- **A startup resolver.** It runs before the node's configuration loads (R5, R6). It:
  - picks an operator-prepared directory if one is configured;
  - otherwise the newest current cached or bundled iteration;
  - otherwise fetches the iteration whose window contains now.

  It checks the computed genesis hash against the published one, and points the node at a per-iteration datadir.
- **Exit at the reset.** A running node exits with code 75 at its reset, and the supervisor's restart refreshes (R11,
  R12).
- **The fetch.** HTTPS only, with a hop allow-list, a 60 s budget and proxy support (R7, R8). It unpacks the release
  through a strict, hand-written tar reader (R9) and writes the consensus-layer directory next to the cache (R10).

**Spec 024.** Every new chain entry carries spec 024's `consensus` and `genesis-hash` keys (R14).

The work ships as **five code PRs (S1, S2, S3, S4a, S4b) and a validation-and-closure slice (S5)**, each mergeable alone, with the review cycle spec 027 used.

## Technical Context

**Language/Version**: Scala 3.9 LTS on JDK 25 (with `-XX:-ReduceAllocationMerges`); sbt 1.13.

**Primary Dependencies**: no new dependency.
- `java.net.http.HttpClient` for the fetch (R8). `java.util.zip.GZIPInputStream` plus an in-house strict tar reader
  (R9).
- json4s, already used by `GenesisDataLoader`, for the release JSON. Typesafe Config for the chain entries.
- Rejected: commons-compress (R9) and pekko-http client (R8).

**Storage**:
- Release entries are files under `<datadir>/releases/ephemery-N/`, made atomic by a stage-then-rename (R6).
- The chain database is RocksDB as today, one datadir per iteration.
- No schema change and no new column family.

**Testing**: ScalaTest.
- An in-memory fake `ReleaseTransport` stands in for the release server.
- A local JDK `HttpsServer` with a self-signed certificate tests the real transport.
- A fixed `java.time.Clock` and a manually advanced scheduler control time.
- Golden fixtures: the raw ephemery-164 and ephemery-165 inputs.
- **Property hand-over.** An in-process test applies the resolver's property map to a fresh `ConfigFactory.load()`,
  never the `Config` object.
- **End to end.** A forked IntegrationTest, `EphemeryForkedHarness`, calls `App.run(args, StartupDeps)` with an offset
  clock and a fake transport (research R16). It covers exit codes and the datadir actually used.
- **No system clock** in the `ephemery` unit tests, enforced by a grep gate (R16), because the bundle ends on a fixed
  date.
- There is no live network in unit tests and no `Thread.sleep`.

**Target Platform**: Linux servers and containers under a supervisor (systemd, Docker, Kubernetes), as for Sepolia.

**Project Type**: a single sbt project (root module). No change to `bytes`, `crypto`, `rlp`, `scalanet` or
`scalanetDiscovery`.

**Performance Goals**:
- At most 60 s per startup on the Ephemery refresh, retries included (SC-002).
- Zero network requests on a current iteration (SC-003).
- Re-validating the cached and bundled entries, about 50 KB each, costs well under 1 s.

**Constraints**:
- **Fetch safety.** HTTPS only; every redirect hop https and, for the default source, on the allow-list.
- **No execution.** Nothing from the download is executed or sourced.
- **Bounded unpacking.** Bytes are counted while streaming.
- **ETC unchanged.** ETC, Mordor and Gorgoroth are untouched byte for byte (FR-021).
- **Before `Config`.** The resolver must not initialise `utils.Config` (R3, R5).

**Scale/Scope**:
- About 12 new main-source files: the `ephemery` package, the resolver, the watchdog, the transport, the tar reader
  and the CLI.
- 3 new resource files for Hoodi, and 3 for the bundle.
- Edits to App, Fukuii, Config, Eip1459, GenesisDataLoader (a pure extraction), and conf/ephemery.conf.
- About 10 new test specs.
- Docs: the Ephemery runbook, a new Hoodi runbook, the CHANGELOG, and the network lists in `README.md`,
  `docs/index.md` and 14 skills under `.claude/skills/`.

## Constitution Check

*GATE: passes before Phase 0. Re-checked after Phase 1 design (below).*

| Principle | How the plan meets it | Status |
|-----------|-----------------------|--------|
| **I. Consensus determinism** | See the four points below this table | PASS |
| **II. Spec-driven** | Spec clarified, then plan, then tasks, then implement. FR-017 is amended in the spec in the same commit as this plan, because a CL directory for the bundled iteration is fetched on request only (R10) | PASS |
| **III. Test discipline** | See the three points below this table | PASS |
| **IV. Scala 3, formatted** | Explicit result types; no `return`; `formatAll` per slice, `formatCheck` before each PR. New code lives under `com.chipprbots.ethereum.ephemery` | PASS |
| **V. Quality gates** | Each slice PR runs `sbt pp` and `formatCheck` locally, and must have green CI and a review before merge. The merge is the user's unless granted | PASS |
| **VI. Security** | A new network surface at startup, considered explicitly in R8, R9 and R12. A different specialist reviews it in S4a. Details below this table | PASS, with S4a security review |
| **VII. Versioning and decisions** | Conventional commits, and a CHANGELOG entry per slice. One behaviour change is flagged: the shipped default `custom-chains-dir` is removed (R5). ADR: not needed. This follows existing patterns (built-in chain entries, custom-chains-dir, sysexits codes), and the in-place switch it rejects is recorded in the spec | PASS |
| **VIII. Backlog** | B-227 is claimed by dev-node. Each slice gets a backlog row, added by `/speckit-tasks`, with a `Backlog:` line in its PR body. Follow-ups are added before a slice closes | PASS |

**Principle I in detail.**
- The Hoodi chain config, the Ephemery renderer and genesis encoder, and the `GenesisDataLoader` extraction are
  consensus-relevant. beacon reviews each one before implementation and again on the diff.
- The extraction touches a path ETC shares. forge confirms the ETC genesis and fork-id suites are unchanged. No ETC
  chain config changes.
- Hoodi's genesis hash and fork ids are pinned against go-ethereum.
- ETH paths use `forTimestamp()`. No ETC code path is edited.

**Principle III in detail.**
- No `Thread.sleep`: a fixed `Clock`, a manual scheduler and fake transports instead.
- New tests are tagged `UnitTest`, plus `NetworkTest` where relevant. The forked-JVM launcher tests are tagged as the
  existing `LauncherIntegrationSpec` is.
- Coverage holds at 70% or above: every new branch, refusals included, has a test.

**Principle VI in detail.**
- **What is downloaded, and how.** Only data. HTTPS only, with an allow-listed redirect chain, size and ratio caps,
  and a strict tar reader with containment and no exec bits.
- **What is checked.** The genesis hash, computed against the published value.
- **Logs.** The signed redirect queries and proxy credentials never appear in them.
- **The stated limit.** The release is not signed. The hash proves internal consistency, not authenticity (spec
  Assumptions). The real defence is the fork-id check with peers.
- **Reviewers.** S4a gets prism (security lens) and a second reviewer from a different specialist (conduit, for the
  HTTP client), as the integrate-after-second-review rule requires.
- **Stays private.** No security details go on the public tracker.

**Post-design re-check, 2026-11-12.** The design artifacts (data-model.md, contracts/, quickstart.md) add:
- no dependency;
- no consensus path beyond those listed under Principle I;
- no globally configurable switch that weakens the fetch rules. The test seams are constructor-injected (R8).

The gates still PASS. The Complexity Tracking table below is empty.

## Project Structure

### Documentation (this feature)

```text
specs/028-builtin-ephemery-hoodi/
├── spec.md              # clarified 2026-11-12; FR-017 amended with this plan
├── plan.md              # this file
├── research.md          # R1-R17: decisions, measurements, rejected alternatives
├── data-model.md        # entities, validation rules, the startup decision table
├── quickstart.md        # validation scenarios per slice, and the live checks
├── contracts/
│   ├── cli.md           # fukuii ephemery | hoodi, fukuii cli ephemery-fetch, the two scripts
│   ├── config.md        # fukuii.ephemery.* keys, resolver-set properties, chain-entry keys
│   ├── exit-codes.md    # 75 / 69 / 78, the operator-facing log lines and refusal texts
│   └── release-json.md  # the release.json schema (cache and bundle metadata)
├── checklists/requirements.md
└── tasks.md             # /speckit-tasks (not created by this command)
```

### Source Code (repository root)

```text
src/main/scala/com/chipprbots/ethereum/
├── ephemery/                         # NEW package: pure, no actors, no utils.Config
│   ├── ReleaseInputs.scala           # the EL members of a release, read from a dir or an archive stream
│   ├── EphemeryRelease.scala         # validate(inputs): Either[ReleaseRefusal, ValidRelease]; R1-R11, N1-N4
│   ├── ReleaseRefusal.scala          # refusal ADT, with exit class and message (contracts/exit-codes.md)
│   ├── GenesisJsonEncoder.scala      # R9/R10 re-encoding
│   ├── ChainConfRenderer.scala       # the chain config HOCON, golden-pinned
│   ├── ReleaseMetadata.scala         # release.json codec
│   ├── ReleaseStore.scala            # <root>/releases: stage, rename, load-and-revalidate, sweep
│   ├── ReleaseTransport.scala        # trait + JdkHttpsTransport (R8)
│   ├── StrictTarReader.scala         # R9
│   ├── ReleaseFetcher.scala          # target iteration K, asset URL, API fallback, budget (R7)
│   ├── EphemeryResolver.scala        # startup selection, then system properties (R5)
│   ├── EphemeryResetWatchdog.scala   # FR-016 (R11)
│   └── EphemeryFetchCli.scala        # `fukuii cli ephemery-fetch`
├── blockchain/data/GenesisDataLoader.scala   # EDIT: pure genesisHeader(...) extracted (R3)
├── network/discovery/Eip1459.scala           # EDIT: all.hoodi.ethdisco.net trust anchor
├── utils/Config.scala                        # EDIT: B-225 message names the searched dir (FR-023)
├── cli/CliLauncher.scala (or its command table)  # EDIT: register ephemery-fetch
├── App.scala                                 # EDIT: knownNetworks + hoodi, help text; main → run(args, StartupDeps) + resolver;
│                                             #       `cli ephemery-fetch` gets the ephemery network config first
└── Fukuii.scala                              # EDIT: start the watchdog after the node starts

src/main/resources/
├── conf/base/blockchains.conf                # EDIT: hoodi { include required("chains/hoodi-chain.conf") }
├── conf/base/chains/hoodi-chain.conf         # NEW
├── conf/base/chains/hoodi-genesis.json       # NEW (re-encoded)
├── conf/hoodi.conf                           # NEW (copied from sepolia.conf)
├── conf/ephemery.conf                        # EDIT: drop the default custom-chains-dir; fukuii.ephemery defaults
└── ephemery/bundled/ephemery-165/            # NEW: ephemery-chain.conf, ephemery-genesis.json, release.json

src/test/scala/com/chipprbots/ethereum/
├── ephemery/                                 # NEW: EphemeryReleaseSpec, golden render spec, ReleaseStoreSpec,
│                                             #      JdkHttpsTransportSpec, StrictTarReaderSpec, ReleaseFetcherSpec,
│                                             #      EphemeryResolverSpec, EphemeryResetWatchdogSpec, BundledEphemerySpec,
│                                             #      EphemeryPropertyHandoverSpec
├── ephemery/SyntheticRelease.scala           # NEW test helper: the next iteration from a raw release (R16)
├── blockchain/data/HoodiGenesisSpec.scala    # NEW
├── network/ForkIdHoodiSpec.scala             # NEW
└── (edits) EphemeryGenesisSpec, LauncherIntegrationSpec, ChainConfigMatrixSpec, BlobScheduleSpec

src/test/resources/ephemery/releases/ephemery-{164,165}/   # NEW raw inputs (R1)
src/it/scala/com/chipprbots/ethereum/ephemery/EphemeryForkedHarness.scala + EphemeryLaunchItSpec.scala   # NEW (S3, S4b)

scripts/ephemery/fetch-genesis.sh      # S2: delegates the rules to the CLI; S4b: exec wrapper
scripts/ephemery/update-bundled.sh     # NEW (FR-003)
scripts/ephemery/check-fixture.sh      # S2: removed, replaced by the golden tests
docs/runbooks/ephemery.md, docs/runbooks/hoodi.md (NEW), CHANGELOG.md, README.md, docs/index.md,
.claude/skills/*/SKILL.md (network lists)
```

**Structure decision.**
- **Root module only.** The new code lives in one package, `ephemery`, so its "no global `Config`" rule can be checked
  with one grep.
- **Static versus generated chains.** Hoodi follows the Sepolia and Platåberget precedent under `conf/base`. Ephemery's
  data lives apart from the built-ins (R4).

## Slices

Each slice is one PR with its own gate, and is mergeable alone.

**Review cycle** (as in spec 027):
1. Implementer subagent.
2. beacon or forge pre-flight, where the slice is consensus-relevant.
3. prism review.
4. Fix round.
5. A second specialist re-check (Principle VI and the integrate-after-second-review rule).
6. eye validation.
7. PR. Merge once CI is green, pinned to the head commit.

**Shared rules.** Implementers run one sbt at a time under `flock`, from full clones (never worktrees), and use Sonnet
unless a slice needs more (memory: subagent model choice).

### S1: Hoodi built-in. Independent.

- **Scope.**
  - `hoodi-chain.conf` and `hoodi-genesis.json` (re-encoded per R15), including `consensus` and `genesis-hash`;
  - `conf/hoodi.conf`;
  - the `blockchains.conf` include and the DNS trust anchor;
  - `App` knownNetworks and help;
  - McpResources 560048 → "hoodi";
  - the bootnode pin test, then the list chosen by its result (R15).
- **Tests.**
  - `HoodiGenesisSpec`: the hash, plus a state root checked against a public RPC's block 0 at implementation time.
  - `ForkIdHoodiSpec`: 5 rows.
  - `ChainConfigMatrixSpec`, with the deposit contract.
  - `LauncherIntegrationSpec`.
  - `BlobScheduleSpec`: a Hoodi row.
- **Docs.** `docs/runbooks/hoodi.md`, the CHANGELOG, and the network lists (Hoodi lines only).
- **Gate.**
  - `testOnly` on the specs above;
  - the ETC fork-id and genesis suites, unchanged;
  - `compile-all`, `formatCheck`.
- **Review.** beacon pre-flight and diff review, then forge's no-ETC-impact note, then prism, then eye.
- **Live.** `fukuii hoodi` from a clean datadir reaches Hoodi peers, with fork-id accepted, and starts syncing
  (SC-005).

### S2: the Ephemery release library and `ephemery-fetch --from-dir`. Independent of S1.

- **Commit 1.**
  - The `ephemery` package, without the transport, the tar reader or the resolver;
  - the `GenesisDataLoader.genesisHeader` extraction (R3);
  - `fukuii cli ephemery-fetch --from-dir DIR --out DIR`, writing today's script layout plus `release.json`;
  - raw inputs for 164 and 165;
  - golden tests, byte-identical against the current fixtures;
  - refusal tests for R1 to R11 and N1 to N4.
- **Commit 2.** The spec 024 keys in the renderer, and the fixtures regenerated.
- **Commit 3.** `fetch-genesis.sh` keeps its download and calls the CLI for everything else (R1). `check-fixture.sh`
  is removed, and so is its one reference, in `docs/runbooks/ephemery.md:213`.
- **Gate.**
  - `testOnly` on the `*Ephemery*` and `GenesisDataLoader*` specs, plus the ETC genesis specs, unchanged;
  - the transitive no-`Config` grep (R3);
  - the no-system-clock grep over the `ephemery` tests (R16);
  - a manual `fetch-genesis.sh --from-dir` run that diffs clean against the fixtures.
- **Review.** beacon (the encoder, renderer and genesis extraction) and forge (the shared genesis path), then prism,
  then eye.

### S3: the bundled iteration, startup selection, and the exit at the reset. Needs S2.

- **Scope.**
  - The bundle `ephemery/bundled/ephemery-165/` and `scripts/ephemery/update-bundled.sh`;
  - `ReleaseStore`, with `<root>/releases`, stage and rename, and the sweep;
  - `EphemeryResolver` with no fetch: the operator directory, then cache, then bundle. If all have ended, it refuses
    with exit 78, naming the script path as the remedy until S4b lands;
  - system-property hand-over, including the pinned `logs-dir`;
  - `EphemeryResetWatchdog`, exit 75;
  - `conf/ephemery.conf` without the default `custom-chains-dir`;
  - the B-225 message (FR-023);
  - the startup report line (US1-3).
- **Tests.**
  - `BundledEphemerySpec`: the bundled hash equals release.json, the computed hash, and the raw-input render.
  - `EphemeryResolverSpec`: every no-fetch case.
  - `EphemeryResetWatchdogSpec`.
  - The in-process property hand-over test (R16).
  - `EphemeryLaunchItSpec` through `EphemeryForkedHarness` (forked IntegrationTest):
    - on an empty root it starts on the bundle in `<root>/ephemery-165`;
    - with the clock offset to 5 s before the reset, it exits 75, and RocksDB then reopens cleanly.
  - The updated `EphemeryGenesisSpec`.
- **Gate.** The specs above; `LauncherIntegrationSpec`; `IntegrationTest / testOnly *EphemeryLaunchItSpec`;
  `compile-all` and `formatCheck`.
- **Review.** prism (resolver, store and watchdog), then beacon (the bundle's chain config and hash pin), then eye.
- **Live.** On dev-node, `fukuii ephemery` with no `FUKUII_CONF_DIR` starts on 165 and follows the head with
  `eb-lighthouse` (the start of SC-007).

### S4a: the transport and the tar reader, pure and security-reviewed. Independent of S3.

- **Scope.** `ReleaseTransport` and `JdkHttpsTransport` (R8), and `StrictTarReader` (R9). Neither is wired into
  startup yet.
- **Tests.** `JdkHttpsTransportSpec` and `StrictTarReaderSpec` (R16).
- **Gate.** Those specs, then `compile-all` and `formatCheck`.
- **Review.** prism with the security lens first, then conduit for the HTTP client, then eye.

### S4b: the refresh, wired in. Needs S3 and S4a.

- **Scope.**
  - `ReleaseFetcher`: target K, the asset URL, the API fallback, and the 60 s budget (R7);
  - the resolver's fetch path, with exits 69 and 78;
  - `fukuii.ephemery.{refresh,source}`;
  - the CL `testnet/` directory, written into the release entry (R10);
  - `ephemery-fetch --release` and the no-`--out` mode (contracts/cli.md);
  - `fetch-genesis.sh` reduced to `exec`;
  - docs: the Ephemery runbook rewrite, plus the CHANGELOG and the Ephemery lines of the network lists. The runbook
    covers:
    - a quick start;
    - supervisor units with the exit codes;
    - pinning an iteration with the script;
    - **the CL across a reset.** It must restart on the new `<root>/current/testnet`, with a fresh CL datadir for each
      iteration, for example a systemd unit that is `PartOf=` the fukuii unit with a datadir keyed by
      `readlink <root>/current`. fukuii's restart alone does not move the CL;
    - the datadir-derived paths that move at each reset, and how to pin them (contracts/config.md).
- **Tests.**
  - `ReleaseFetcherSpec` and the `EphemeryResolverSpec` fetch cases, all against the fake transport.
  - `EphemeryLaunchItSpec` refresh case: clock past the reset, plus a fake transport serving
    `SyntheticRelease.next(165)`. It must refresh and start in the new datadir, leaving the old one untouched (US2).
- **Gate.** The specs above; `pp`; `formatCheck`.
- **Review.** prism, then conduit for the fetch wiring, then beacon (the release validation call site), then eye.

### S5: live validation and closure. Needs S1 to S4b merged and a build installed on dev-node.

- **Checks.**
  - **SC-007.** 24 h on the live iteration with no `FUKUII_CONF_DIR`.
  - **SC-008.** The 165→166 reset, 2026-11-19 19:00 UTC, if S4b is installed by 2026-11-18. Otherwise a
    controllable-clock run, with the live check moved to 167, 2026-12-17.
    - fukuii runs under a systemd unit with the runbook's settings.
    - `eb-lighthouse` runs under a companion unit (`PartOf=`) that restarts it on `<root>/current/testnet` with a fresh
      CL datadir, so neither side needs an operator.
  - **SC-005.** The Hoodi peer check from S1, repeated on the release candidate.
- **Close.** Set the spec status to Shipped or Validated per `docs/development/spec-lifecycle.md`, regenerate the index,
  and release B-227.

**Order.** S1 ∥ S2 ∥ S4a, then S3 (after S2), then S4b (after S3 and S4a), then S5.

The critical path to the 166 reset is S2 → S3 → S4b, in about 6 days. S3 alone already gives a node that exits at
the reset, plus a documented manual refresh with the script. That is the fallback if S4b misses 2026-11-18.

## Risks

| Risk | Mitigation |
|------|------------|
| A transitive `Config` read on the resolver's path freezes the wrong datadir | The S2 grep task; the forked-JVM launcher test checks the datadir actually used (S3) |
| The `GenesisDataLoader` extraction changes the genesis hash on some chain | A pure extraction; every existing genesis spec, ETC included, passes unchanged; beacon and forge review |
| The Ephemery release format changes (pax headers, new keys, bpo3) | A refusal with exit 78 and the reason, in place of a wrong chain; the local-dir source is the fallback (R9, R13) |
| A Hoodi bootnode with TCP port 0 is dialled at port 0 | The S1 pin test decides the list (R15); DNS discovery is the backstop |
| Paths derived from the datadir move at each reset (static nodes, IPC socket, keystore, node key) | The table in contracts/config.md; the resolver seeds `static-nodes.json` from the root; the runbook says how to pin the others |
| A test on the bundle reads the real clock and goes red on the reset date | The no-system-clock grep gate (R16) |
| The 166 reset arrives before S4b | S3's exit 75 plus a manual refresh with the script (R17) |
| The resolver overrides an operator's `custom-chains-dir` that holds other chains | It overrides only when that dir has no `ephemery-chain.conf`, and logs the override; for `network = ephemery` the other entries are unused |

## Complexity Tracking

No Constitution Check violation needs justifying.
