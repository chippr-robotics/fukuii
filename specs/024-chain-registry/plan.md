# Implementation Plan: Chain Registry (ChainSpec) — A Chain Is Data

**Branch**: `024-chain-registry` | **Date**: 2026-10-31 | **Spec**: [spec.md](./spec.md) (Clarified 2026-10-31;
its Clarifications session is binding)

**Input**: Feature specification from `/specs/024-chain-registry/spec.md`; cross-spec inputs
`specs/023-fleet-topology/alignment-review.md` §3 (024 verdict: Aligned, one transitional note on `storage-scheme`)
and §4 (phases), `specs/023-fleet-topology/research.md` §3 (existing surfaces).

## Summary

A chain becomes one validated registry entry (`<chainKey>-chain.conf`, today's shape plus a required `consensus`
key and an optional `genesis-hash`) loaded from the classpath and an operator directory, with every FR-004/FR-005
rejection a named error reported through `ConfigValidator` before `object Config` is initialised, so nothing can open
the datadir or bind a port. `BlockchainConfig.fromRawConfig` is not modified; the registry wraps it, which is how the
eight built-in entries stay byte-identical (GT-1, golden fixtures committed first). `mining.protocol`,
`storage-scheme` and the default sync mode are derived from `consensus` and injected into the deployment config; a
profile that contradicts them is refused (DC-5), which retires the 15-line comment in `conf/sepolia.conf`.
`App.knownNetworks` goes; `fukuii <key>` accepts any registry key and fails with the key list otherwise.
`fukuii chains list|validate` expose the loader. A versioned AppState record (chainKey, genesis hash, fork schedule)
written at first genesis store implements geth's `CheckCompatible` rule on every open (DC-6). Research:
[research.md](./research.md); entities: [data-model.md](./data-model.md); contracts: [contracts/](./contracts/).

## Technical Context

**Language/Version**: Scala 3.9.0 (LTS line, spec 020) on JDK 25; sbt 1.13.0 (constitution "Technology &
Architecture Constraints"; `CLAUDE.md`).

**Primary Dependencies**: Typesafe Config (HOCON; `ConfigValue.origin()` for profile provenance), circe
(`project/Dependencies.scala:35-44`, already present) for the JSON Schema sync test, decline (`cli/CliCommands.scala`)
for `fukuii chains`, json4s for genesis parsing (`GenesisDataLoader.scala:89-96`). **No new dependency** (research
R-2).

**Storage**: RocksDB through `AppStateStorage` (string→string, `db/storage/AppStateStorage.scala:17-27`) for the
datadir compatibility record; three new keys beside spec 022's markers (`:652-655`). `EphemDataSource` in tests.

**Testing**: ScalaTest (`AnyFlatSpec` + `Matchers`, tags from `testing/Tags.scala`: `UnitTest`, `ConsensusTest`,
`IntegrationTest`, `DatabaseTest`), ScalaCheck for the record renderer round-trip, no `Thread.sleep`. Commands per
`CLAUDE.md`: `sbt compile-all` per edit, `./scripts/fukuii-test only "*Foo*"` per phase, `./scripts/fukuii-test
essential` once at the end.

**Target Platform**: Linux server JVM; the node launcher (`App.scala`) and the offline tools (`ChainTool`,
`CheckpointCli`, new `ChainsCli`).

**Project Type**: single sbt root module (`src/main`, `src/test`), new package
`com.chipprbots.ethereum.chainspec`; resources under `src/main/resources/conf/`.

**Performance Goals**: registry load + validation of eight built-ins plus an operator directory in well under one
second at startup (HOCON parse + 40 key reads per entry; the genesis-hash check builds the genesis state trie in
memory, which `GenesisDataLoader` already does on first start — Platåberget's 44 KB allocation is the largest shipped
genesis). The datadir check performs three AppState reads and one header lookup. No hot-path code is touched.

**Constraints**: consensus-neutral by construction (DC-4, GT-1): no edit to `utils/BlockchainConfig.scala`
`fromRawConfig`, `consensus/`, `vm/`, `ledger/`; every error is a value, never a thrown exception inside the loader
(fail loudly at one exit point); process exits before `db.rocksdb.path` exists for DC-1/DC-5 errors and before any
listener for DC-6. 120-column scalafmt; scalafix clean.

**Scale/Scope**: 8 built-in entries + Ephemery generated; ~12 new source files (~1,500 LOC), ~10 new test specs,
edits to `App.scala`, `utils/Config.scala` (`BlockchainsConfig`), `utils/InstanceConfig.scala`,
`ConfigValidator.scala`, `Fukuii.scala`, `nodebuilder/StdNode.scala`, `blockchain/data/GenesisDataLoader.scala`,
`db/storage/AppStateStorage.scala`, 8 chain `.conf` files, 6 profile `.conf` files, `base/mining.conf`,
`base/sync.conf`, `base/blockchains.conf`, `scripts/ephemery/fetch-genesis.sh`, the `fukuii-custom-networks` skill
and `docs/runbooks/custom-networks.md`. Six delivery slices, one PR each.

## Constitution Check

*GATE: passed before Phase 0 research; re-checked after Phase 1 design (this table).*

| Principle | Engaged? | Status / justification |
| --- | --- | --- |
| **I. Consensus determinism** | Yes, by proximity: the registry produces the `BlockchainConfig` every consensus path reads, and the DC-5 derivation chooses which header validator `ValidatorsExecutor.apply` (`:55-60`) builds. | **PASS by construction + gate.** `fromRawConfig`, `ValidatorsExecutor`, `MiningBuilder` are not edited. GT-1 golden fixtures captured first (slice 1) and reproduced by the loader (slice 2). The validator-selection derivation is reviewed by `forge` (ETC entries → `PoWBlockHeaderValidator`) and `beacon` (ETH entries → `TransitionBlockHeaderValidator`) in slice 4. One documented, inert field delta (`messConfig.reactivationBlock`, `enabled = false`) is tracked in Complexity Tracking and needs the user's decision (D-1). ETC/ETH code paths untouched. |
| **II. Spec-driven** | Yes | PASS. Spec Clarified; this plan + research + data-model + contracts + quickstart + tasks. ADR `docs/adr/infrastructure/INF-010-chain-registry.md` added in slice 3 (constitution VII). |
| **III. Tests & coverage** | Yes | PASS. Every new type has a unit spec; every error class has a test (SC-003); no `Thread.sleep`; `testEssential` at baseline at the end of each slice; new code is pure and table-driven, so coverage rises. Golden capture is the first task. |
| **IV. Idiomatic Scala 3** | Yes | PASS. `enum`/sealed ADTs for `ConsensusType`, `ChainSpecError`; `Either`/`Validated` returns; explicit result types; `scalafmtAll` before each commit; no `return`. Files this spec edits that fall in 020 PR 5's rewrite set are edited after PR 5 (research R-9). |
| **V. Quality gates** | Yes | PASS. One PR per slice; `sbt pp` before each; CI green. |
| **VI. Security & operational safety** | Yes (operator-supplied files; Engine API JWT check) | PASS. Entries are parsed with Typesafe Config (no code execution); paths resolve relative to the entry directory, never the CWD; `EngineApiNotConfigured` refuses an ingest node without a JWT secret on an engine-api chain; RPC binding defaults unchanged. The shadow flag is development-only and logs at WARN. |
| **VII. Versioning & decisions** | Yes | PASS. Minor bump at slice 3 (CLI surface change: `knownNetworks` removal); breaking-change callout for `custom-chains-dir` semantics (missing dir and shadowing now fail); ADR INF-010. |
| **Module boundaries** | Yes | PASS. `chainspec/` depends on `utils/` (BlockchainConfig) and `db/storage/` (AppState record); nothing in `consensus/`, `vm/`, `ledger/` depends on `chainspec/`. |

## Project Structure

### Documentation (this feature)

```text
specs/024-chain-registry/
├── spec.md                       # Clarified 2026-10-31 (not edited by this plan)
├── plan.md                       # this file
├── research.md                   # R-0 evidence, R-1..R-9 decisions
├── data-model.md                 # ChainSpec, enums, errors, datadir record, registry
├── quickstart.md                 # US1/US2/US3 runnable scenarios
├── contracts/
│   ├── chainspec.schema.json     # JSON Schema draft 2020-12 of the entry (meta-schema checked)
│   ├── chainspec.md              # field reference, ETC and ETH examples, authoring steps
│   ├── cli.md                    # fukuii chains list|validate output and exit codes
│   └── errors.md                 # named errors, templates, exit statuses, SC-003 obligations
└── tasks.md                      # /speckit-tasks output
```

### Source Code (repository root)

```text
src/main/scala/com/chipprbots/ethereum/
├── chainspec/                                  # NEW package
│   ├── ConsensusType.scala                     # enum ethash-pow | engine-api; family, finalityRule, forkDispatch, storageScheme
│   ├── ChainSpec.scala                         # entry model (+ GenesisRef, ResourceProfile, ChainSpecSource)
│   ├── ChainSpecError.scala                    # sealed ADT + render (contracts/errors.md)
│   ├── ChainSpecSchema.scala                   # knownKeys / requiredKeys / deploymentKeys tables
│   ├── ChainSpecValidator.scala                # FR-004 checks over one HOCON entry → Validated[ChainSpec]
│   ├── GenesisHash.scala                       # genesis JSON → header hash via GenesisDataLoader.prepareHeader on an in-memory MptStorage
│   ├── ChainRegistry.scala                     # built-in + operator dir, collisions, shadowing, UnknownChainKey
│   ├── DeploymentDerivation.scala              # DC-5: mining.protocol / storage-scheme / sync.do-snap-sync injection + checks
│   ├── DatadirChainRecord.scala                # v1 record render/parse (pure)
│   ├── DatadirCompatibility.scala              # geth CheckCompatible rule (pure) + node-side runner
│   ├── BlockchainConfigRender.scala            # canonical renderer used by the golden test (main, so chains list --verbose can reuse)
│   └── ChainsCli.scala                         # fukuii chains list|validate (decline)
├── App.scala                                   # EDIT: knownNetworks removed, chain-key candidate rule, `chains`, --chain-registry-allow-shadow, --help
├── Fukuii.scala                                # EDIT: ConfigValidator.validate(raw) before Config is touched
├── ConfigValidator.scala                       # EDIT: validate() also runs ChainRegistry.load + DeploymentDerivation
├── utils/Config.scala                          # EDIT: BlockchainsConfig.apply → adapter over ChainRegistry (flagged in slice 2)
├── utils/InstanceConfig.scala                  # EDIT: config = DeploymentDerivation.apply(raw, spec); blockchains from registry
├── nodebuilder/StdNode.scala                   # EDIT: DatadirCompatibility.run first in start()  (after 020 PR 5)
├── blockchain/data/GenesisDataLoader.scala     # EDIT: Reader-based parse; onFirstStored writes the record (after 020 PR 5)
└── db/storage/AppStateStorage.scala            # EDIT: Keys.ChainSpecChainKey / ChainSpecGenesisHash / ChainSpecForkSchedule + accessors

src/main/resources/conf/
├── base/blockchains.conf                       # EDIT: registry { enabled, allow-shadow } keys
├── base/chains/*-chain.conf (8)                # EDIT: + consensus, + genesis-hash, + sync.do-snap-sync; dead keys removed; mess per DC-2
├── base/mining.conf                            # EDIT: protocol default removed (derived)
├── base/sync.conf                              # EDIT: do-snap-sync default removed (derived)
└── {sepolia,eth,plataberget,ephemery,gorgoroth,hive}.conf   # EDIT: mining/storage-scheme/do-snap-sync lines removed (SC-004)

src/test/scala/com/chipprbots/ethereum/
├── chainspec/
│   ├── GoldenBlockchainConfigSpec.scala        # GT-1 (first task)
│   ├── ChainSpecSchemaSyncSpec.scala           # schema ≡ tables
│   ├── ChainSpecValidatorSpec.scala, ChainRegistrySpec.scala, DeploymentDerivationSpec.scala,
│   ├── GenesisHashSpec.scala, DatadirChainRecordSpec.scala, DatadirCompatibilitySpec.scala,
│   ├── DatadirCompatibilityNodeSpec.scala (IntegrationTest), ChainsCliSpec.scala, FailFastSpec.scala (SC-003)
├── LauncherIntegrationSpec.scala               # EDIT: isNetwork reflection → candidate rule
└── utils/BlockchainsConfigSpec.scala           # EDIT: "custom overrides built-in" → refuse / allow-shadow
src/test/resources/chainspec/golden/<key>.golden.txt (8)   # committed fixtures
src/test/resources/chainspec/fixtures/*                    # invalid entries, operator dir, genesis files

scripts/ephemery/fetch-genesis.sh               # EDIT: emits consensus and genesis-hash
.claude/skills/fukuii-custom-networks/SKILL.md  # EDIT: step 3 → "write a ChainSpec"; validate step
docs/runbooks/custom-networks.md, docs/adr/infrastructure/INF-010-chain-registry.md
```

**Structure Decision**: single root module; one new package `chainspec/` so the loader has no dependency edges into
`consensus/`, `vm/`, `ledger/`, and edits to existing files are confined to wiring (`App`, `Fukuii`,
`ConfigValidator`, `Config`, `InstanceConfig`, `StdNode`, `GenesisDataLoader`, `AppStateStorage`). Test specs mirror
the package.

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
| --- | --- | --- |
| GT-1 letter ("no field-level change to any built-in") vs DC-2 (ETH entries must not carry `mess`; `test` must) — five entries' `messConfig.reactivationBlock` flips (`Some(10^18)`↔`None`) with `enabled = false` (research R-3) | Both are binding; the field is inert (`MESSConfig.isActiveAtBlock` is `enabled && ...`, `MESSConfig.scala:33-34`) and the only consumer is `BranchResolution.messConfig` via `NodeBuilder.messConfigOpt` | Synthesising the forbidden block inside the loader for ETH entries hides the very thing DC-2 forbids and still leaves the `test` delta; leaving the files alone violates DC-2. **Resolution**: enumerated delta allowlist in the golden test, signed by beacon (4 ETH) and forge (`test`); user decision D-1 below. |
| `storageScheme` as a derived ChainSpec field (not a HOCON key) although it is transitional | 018 DC-9 retires the Hash scheme later; until then ETC needs `hash` and ETH `path` and nothing checks it pre-open (`SyncController.scala:466-472` is post-open) | Keeping it a profile key keeps the sepolia.conf class of error alive; making it a ChainSpec *key* would need a later schema break. Derived + a `RemovedKeys` row when 018 DC-9 lands is the smallest footprint. |
| Startup order change: `ConfigValidator.validate` runs on the raw config before `object Config` | `InstanceConfig` builds `blockchains` eagerly (`InstanceConfig.scala:86`) and `Fukuii.main` touches `Config` at `:27`; errors must be named, not `ExceptionInInitializerError` | Making `BlockchainsConfig` lazy touches 20+ readers and still leaves the exception path. |

**Decisions for the user** (surfaced, not assumed):
- **D-1 (GT-1 vs DC-2)**: (a) *recommended* — accept the enumerated, inert `messConfig.reactivationBlock` delta on
  `eth`, `sepolia`, `plataberget`, `hive`, `test`, visible as five one-line fixture diffs in the slice 4 PR; or (b) the
  loader synthesises `mess { enabled = false }` for ETH entries (zero delta on the four ETH built-ins, `test` delta
  remains); or (c) amend spec DC-2 to tolerate `mess { enabled = false }` on ETH entries (not recommended; the spec
  explicitly rejects it). Slice 4 does not start until D-1 is answered; slices 1–3 are unaffected.
- **D-2 (`gorgoroth.conf:32 protocol = "ethash"`)**: by reading `Protocol.KnownProtocolNames` (`Protocol.scala:49-56`)
  this value is rejected by `MiningConfig.readProtocol` today (DID NOT RUN). Slice 4 removes the line; the user may
  want a `fix:` ahead of it.

## Delivery slices (one PR each; every slice leaves `testEssential` at baseline)

Alignment-review placement: slice 1 is phase A (test-only; the review names "024 T-001 golden capture" there).
Slices 2–6 are phase B, "sequenced around 020 PR 5": slices 2 and 3 touch no file in 020's inventories and may land
before PR 5; slices 4–6 edit `StdNode.scala` and `GenesisDataLoader.scala` (candidates for PR 5's `using` rewrite;
`GenesisDataLoader` is in `dc3-sites.md:203`) and land **after PR 5 is on staging** or are rebased onto it before
review (research R-9). 023 T-002 (runtime retirement) deletes `FukuiiRuntime`, which this spec does not edit.

| # | Slice | Scope | Consensus-neutral because | Owner / reviewers |
| --- | --- | --- | --- | --- |
| 1 | **GT-1 golden capture** (spec T-001) | `BlockchainConfigRender`, `GoldenBlockchainConfigSpec`, 8 fixtures; `ChainSpecSchemaSyncSpec` skeleton against a tables-only `ChainSpecSchema`; `contracts/chainspec.schema.json` committed | Test + renderer only; zero `src/main` behaviour | main session; **forge** + **beacon** glance at the fixtures (ETC / ETH rows) ; **eye** runs `testOnly` |
| 2 | **Loader behind a flag** (T-002 part 1) | `chainspec/` model, errors, schema tables, validator, `GenesisHash`, `ChainRegistry`; `BlockchainsConfig.apply` adapter under `fukuii.blockchains.registry.enabled = false`; unit specs for every error; golden test runs both paths | `fromRawConfig` untouched; flag default off; golden equality on both paths | main session; **prism** (8-lens, non-consensus); **eye** |
| 3 | **Fail-fast wiring, `knownNetworks` removal, CLI** (T-002 part 2, T-003) | `ConfigValidator.validate` runs the registry on the raw config; `Fukuii.main` reorder; `App.scala` candidate rule + `chains` + `--chain-registry-allow-shadow`; `ChainsCli`; flag default → `true`; `LauncherIntegrationSpec`, `BlockchainsConfigSpec` updated; ADR INF-010; `--help` from registry | No consensus file; selection and reporting only | main session; **prism**; **eye** (incl. `LauncherIntegrationSpec`, a manual `fukuii mordor` smoke per quickstart) |
| 4 | **DC-5 derivation + DC-2 + conf edits** (T-005, T-002 MESS) | `DeploymentDerivation`; `InstanceConfig` applies it; profile keys removed from 6 `conf/*.conf`; `mining.conf`/`sync.conf` defaults removed; 8 chain files gain `consensus`, `genesis-hash`, `sync.do-snap-sync`, lose dead keys; DC-2 edits + golden allowlist (after D-1) | Validator set per chain asserted unchanged by `DeploymentDerivationSpec` (`ValidatorsExecutor.apply(derived)` class per chain) | main session; **forge** (ETC entries → `PoWBlockHeaderValidator`; `test` delta; gorgoroth `ethash` removal); **beacon** (ETH entries → `TransitionBlockHeaderValidator`; sepolia.conf comment → one line; 4 deltas); **eye** |
| 5 | **Datadir compatibility record** (T-004) | `AppStateStorage` keys + accessors; `DatadirChainRecord`, `DatadirCompatibility`; `GenesisDataLoader.onFirstStored` writes; `StdNode.start` checks first; backfill; `chains validate --datadir`; exit 3 | Reads three storages, writes one batch at first genesis store; no block processing path | main session; **vault** (AppState key names, batch, open order, backfill on pre-record datadirs); **forge/beacon** confirm the fork-name list covers every `ForkBlockNumbers`/`ForkTimestamps` field; **eye** |
| 6 | **SC-001 in the 023 harness, docs, skill, flag removal** (T-006) | Platåberget-as-operator / Engine API devnet fixture in the 023 harness; `fetch-genesis.sh` emits `consensus` + `genesis-hash`; skill + runbook; remove `registry.enabled` flag and the legacy path | Docs, fixtures, dead-flag removal | main session; **herald** glance (bootnodes in operator entries); **prism**; **eye** (`testEssential`, both ethereum/tests jobs) |

Rollback per slice: 1 — delete tests; 2 — flag is off; 3 — revert `App`/`Fukuii`/`ConfigValidator` (one commit);
4 — conf files revert independently of code (derivation tolerates explicit equal values); 5 — the record is
additive; an older binary ignores the three keys; 6 — docs.

## Specialist routing

| When | Agent | Why |
| --- | --- | --- |
| Before slice 1 fixtures are committed | `forge`, `beacon` | Eyeball the ETC / ETH golden renderings for a field that reads wrong today (a pre-existing config bug would otherwise be frozen as "golden"). |
| Slice 4 design review (before code) | `forge`, `beacon` | DC-5 derivation table (research R-5): confirm `ethash-pow → pow` keeps `PoWBlockHeaderValidator` and `engine-api → engine-api` keeps `TransitionBlockHeaderValidator` for each chain; D-1 sign-off. |
| Slice 5 design review (before code) | `vault` | AppState key names, one-batch write with the genesis, read-only open for `chains validate --datadir`, backfill rule on old datadirs, mid-SNAP head semantics. |
| Any compile failure | `wraith` | Scala 3.9 fallout, especially after rebasing onto 020 PR 5. |
| Slices 2, 3, 6 | `prism` | Non-consensus code quality (8 lenses). |
| End of every slice | `eye` | `compile-all`, targeted `testOnly`, `testEssential` once; chain compatibility (`fukuii mordor`, `fukuii sepolia` smoke per quickstart). |
| Slice 6 | `herald` | Operator-entry bootnode/DNS fields reach discovery unchanged. |

## Cross-spec notes

- **023 fleet-topology**: `--chain <chainKey>` (023 T-003) must call `ChainRegistry.resolve(key)` and report
  `UnknownChainKey` through `ConfigValidator`; `InstanceConfig.chainKey` is the registry key, and 023's default
  datadir `~/.fukuii/<chainKey>/<role>` must be what the DC-6 record's `chain.spec.chainKey` is compared against.
  023's harness (FR-007) gains the SC-001 fixture from slice 6. 023 T-002 deletes `runtime/FukuiiRuntime.scala`; its
  call to `ConfigValidator.removedKeyWarnings` (`:79-80`) needs no change from this spec. `EngineApiNotConfigured`
  applies to role `ingest` only; 023's role gating must pass the role to `DeploymentDerivation`.
- **025 change-record-export**: read `ChainSpec.finalityRule`, `chainKey`, `chainId` from the registry
  (`ChainRegistry.spec(key)`), never from `BlockchainConfig` alone (which has no key or finality field).
- **026 follower-roles**: `ChainSpec.resourceProfile(Role.Replica).retentionWindow` is an `Option[FiniteDuration]`;
  026 owns the 24 h default when it is `None`.
- **018 storage-engine-consolidation**: when DC-9 unifies the layout, remove the `storage-scheme` row from
  `DeploymentDerivation` and add `sync.snap-sync.storage-scheme` to `ConfigValidator.RemovedKeys`; the
  `SyncController.scala:466-472` `require` goes with it.
- **020 Scala 3.9**: slices 4–6 edit `StdNode.scala` and `GenesisDataLoader.scala`; land after PR 5 or rebase onto it
  (research R-9). `GenesisDataLoader.scala:92` `Manifest` deprecation (gt1-inventory row 69) is 020's, not ours.
- **009 Amsterdam / Platåberget**: the SC-001 fixture copies `plataberget-chain.conf` under a second key; if 009
  changes that file, the fixture is regenerated from it (slice 6 task).
- **Ephemery** (`scripts/ephemery/fetch-genesis.sh`): must emit `consensus = "engine-api"` and `genesis-hash` or the
  generated entry fails `MissingRequiredField` once slice 3 lands; the script edit is a slice 3 task so Ephemery keeps
  working in the same release.
- **ops overlays** (`ops/barad-dur/*/fukuii-conf/*.conf`, `ops/cirith-ungol/conf/etc.conf:145`) set
  `mining.protocol` by hand; they keep working while the value equals the derivation and should drop the key
  (deployment repo task, not this spec).
