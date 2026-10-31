---

description: "Task list for spec 024 Chain Registry (ChainSpec)"
---

# Tasks: Chain Registry (ChainSpec) — A Chain Is Data

**Input**: Design documents from `/specs/024-chain-registry/`

**Prerequisites**: plan.md, spec.md (Clarified 2026-10-31), research.md, data-model.md, contracts/, quickstart.md

**Tests**: Required by the spec (GT-1, SC-002, SC-003). Tests are written FIRST in every phase and must FAIL before
the implementation task that makes them pass (except the golden capture, which passes against today's code by design).
ScalaTest; no `Thread.sleep`; tags from `com.chipprbots.ethereum.testing.Tags`.

**Organization**: Setup → Foundational (golden capture is T001) → US2 (P1, MVP: golden + loader behind a flag) →
US1 (P1: add a chain without a release) → US3 (P2: misconfigured profile cannot start) → Polish. Slice numbers refer
to plan.md "Delivery slices".

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on an unfinished task)
- **[Story]**: US1 / US2 / US3; omitted for setup, foundational and polish tasks
- Every description names its file(s). Paths are repository-relative.

## Path Conventions

Single sbt root module: `src/main/scala/com/chipprbots/ethereum/...`, `src/test/scala/com/chipprbots/ethereum/...`,
resources `src/main/resources/conf/...`, test resources `src/test/resources/chainspec/...`.

---

## Phase 1: Setup (Shared Infrastructure) — slice 1

**Purpose**: package skeleton, fixture directories, schema committed, tables that both the schema test and the loader
read. No behaviour change.

- [ ] T001 Create package dir `src/main/scala/com/chipprbots/ethereum/chainspec/` and test dirs
      `src/test/scala/com/chipprbots/ethereum/chainspec/`, `src/test/resources/chainspec/golden/`,
      `src/test/resources/chainspec/fixtures/`
- [ ] T002 [P] Write `src/main/scala/com/chipprbots/ethereum/chainspec/ConsensusType.scala`: `enum ConsensusType(key)`
      with `ethash-pow`, `engine-api`; `family`, `finalityRule`, `forkDispatch`, `storageScheme`, `fromKey`; enums
      `Family`, `FinalityRule`, `ForkDispatch` per data-model.md §1
- [ ] T003 [P] Write `src/main/scala/com/chipprbots/ethereum/chainspec/ChainSpecSchema.scala`: `knownKeys`,
      `requiredKeys(consensus)`, `deploymentKeyPrefixes`, `messKeys`, `resourceKeys(role)` per data-model.md §5 (pure
      tables, no I/O)
- [ ] T004 [P] Write `src/main/scala/com/chipprbots/ethereum/chainspec/BlockchainConfigRender.scala`: canonical
      renderer of `BlockchainConfig` (sorted sets, `0x` hex `ByteString`/`Address`, decimal `BigInt`, explicit
      `Option`, one `path = value` line per field; research R-7)
- [ ] T005 [P] `contracts/chainspec.schema.json` is committed (done in this plan); add
      `src/test/resources/chainspec/schema-path.txt` pointing at it so the sync test resolves the file from the repo
      root
- [ ] T006 `sbt compile-all` → 0 errors; `sbt scalafmtAll`

---

## Phase 2: Foundational (Blocking Prerequisites) — slice 1, the GT-1 gate

**Purpose**: freeze today's `BlockchainConfig` for every built-in BEFORE any loader code exists (spec GT-1; alignment
review phase A). Blocks every user story.

**⚠️ CRITICAL**: T007–T010 are committed on today's conf files, untouched.

- [ ] T007 Write `src/test/scala/com/chipprbots/ethereum/chainspec/GoldenBlockchainConfigSpec.scala` (tags
      `UnitTest, ConsensusTest`): assemble shipped resources exactly as `ChainConfigMatrixSpec.scala:32-36`
      (`parseResources("conf/base/blockchains.conf").atPath("fukuii").resolve()`); for each of `etc`, `mordor`,
      `gorgoroth`, `test`, `eth`, `sepolia`, `plataberget`, `hive` assert
      `BlockchainConfigRender.canonical(BlockchainConfig.fromRawConfig(...))` equals
      `src/test/resources/chainspec/golden/<key>.golden.txt`; `-Dfukuii.chainspec.golden.regen=true` rewrites the
      fixtures; a missing fixture fails the test
- [ ] T008 Run `./scripts/fukuii-test only "*GoldenBlockchainConfigSpec*"` with regen once; commit the eight fixtures
      under `src/test/resources/chainspec/golden/`; run again without regen → PASS
- [ ] T009 [P] Write `src/test/scala/com/chipprbots/ethereum/chainspec/ChainSpecSchemaSyncSpec.scala` (tag
      `UnitTest`): parse `contracts/chainspec.schema.json` with circe; assert flattened `properties` paths ==
      `ChainSpecSchema.knownKeys`, top-level `required` == `requiredKeys` common part, `consensus.enum` ==
      `ConsensusType.values.map(_.key)`, `network-type.enum` == `Family` keys, `additionalProperties == false` on every
      object, the two `allOf` `if/then` blocks name exactly `requiredKeys(ethash-pow)` / `requiredKeys(engine-api)`
      extras (research R-2)
- [ ] T010 [P] Write `src/test/scala/com/chipprbots/ethereum/chainspec/BlockchainConfigRenderSpec.scala`: renderer is
      deterministic (same input twice), sorts `bootstrapNodes`, renders every `BlockchainConfig` field (reflective
      field count == rendered line count prefix set)
- [ ] T011 Ask `forge` (ETC fixtures) and `beacon` (ETH fixtures) to read the eight golden files for a value that is
      wrong today; log any finding in `.claude/agent-protocols/working-docs/CHASE-QUEUE.md` — fixtures are not
      edited in this spec
- [ ] T012 `sbt compile-all`; `./scripts/fukuii-test only "*chainspec*"` → PASS; `sbt scalafmtAll`; open slice 1 PR
      (test-only; alignment review phase A)

**Checkpoint**: GT-1 fixtures committed; schema ≡ tables test green; no `src/main` behaviour changed.

---

## Phase 3: User Story 2 — Existing chains are unchanged (Priority: P1) 🎯 MVP — slice 2

**Goal**: the registry loader exists behind `fukuii.blockchains.registry.enabled` (default `false`) and reproduces
every golden fixture exactly on both code paths (SC-002).

**Independent Test**: `GoldenBlockchainConfigSpec` passes with the flag on and off; `testEssential` at baseline.

### Tests for User Story 2 (write first, must fail)

- [ ] T013 [P] [US2] Extend `src/test/scala/com/chipprbots/ethereum/chainspec/GoldenBlockchainConfigSpec.scala`:
      for each built-in, `ChainRegistry.load(shipped.getConfig("fukuii.blockchains")).spec(key).blockchainConfig ==
      BlockchainConfig.fromRawConfig(...)` (case-class equality) and renders equal to the fixture; and
      `BlockchainsConfig(shipped.getConfig("fukuii.blockchains"))` equality with the flag `true` and `false`
- [ ] T014 [P] [US2] Write `src/test/scala/com/chipprbots/ethereum/chainspec/ChainSpecValidatorSpec.scala`: one test
      per error in data-model.md §6 rows `UnknownField`, `DeploymentKeyInChainSpec`, `MissingRequiredField`,
      `UnknownConsensusType`, `FamilyConsensusMismatch`, `TimestampForkOnBlockDispatchChain`, `MessBlockMissing`,
      `MessBlockOnEngineApiChain`, `GenesisFileUnreadable`, `GenesisMismatch`; fixtures under
      `src/test/resources/chainspec/fixtures/invalid/*-chain.conf`; assert errors are collected (an entry with three
      faults reports three) and the rendering contains `[<Name>]` and the field
- [ ] T015 [P] [US2] Write `src/test/scala/com/chipprbots/ethereum/chainspec/ChainRegistrySpec.scala`: built-in set
      equals the eight keys; operator dir adds a key; `RegistryDirectoryMissing`; `EntryParseError` (today swallowed);
      `ChainKeyCollision` by default and shadowing with `allow-shadow = true` plus the WARN text;
      `UnknownChainKey` lists sorted keys; `ChainKeyFileNameMismatch`
- [ ] T016 [P] [US2] Write `src/test/scala/com/chipprbots/ethereum/chainspec/GenesisHashSpec.scala`: hash of
      `conf/base/chains/sepolia-genesis.json` equals Sepolia's published genesis hash (go-ethereum
      `params.SepoliaGenesisHash`, `0x25a5cc106eea7138acab33231d7160d69cb777ee0c2c553fcddf5138993e6dd9`);
      `mordor-genesis.json` equals core-geth `params.MordorGenesisHash` (copy the constant from the reference client
      at implementation time; the test pins what the reference client publishes, never a value fukuii computed);
      `plataberget-genesis.json` equals the block 0 hash quoted in `plataberget-chain.conf:13`; inline genesis object
      form; a relative path resolves against the entry directory
- [ ] T017 [US2] Run `./scripts/fukuii-test only "*chainspec*"` → the new specs FAIL (types missing)

### Implementation for User Story 2

- [ ] T018 [P] [US2] Write `src/main/scala/com/chipprbots/ethereum/chainspec/ChainSpecError.scala`: sealed ADT with the
      19 cases of data-model.md §6 and `render: String` per contracts/errors.md templates
- [ ] T019 [P] [US2] Write `src/main/scala/com/chipprbots/ethereum/chainspec/ChainSpec.scala`: `ChainSpec`,
      `ChainSpecSource`, `GenesisRef`, `ResourceProfile`, `SyncModeDefault`, `Role` (or import 023's once it exists)
- [ ] T020 [P] [US2] Write `src/main/scala/com/chipprbots/ethereum/chainspec/GenesisHash.scala`: parse genesis JSON
      from a `java.io.Reader` (json4s `parse(reader)`), build the header through `GenesisDataLoader.prepareHeader`
      logic over an in-memory `MptStorage` (`EphemDataSource`), return `ByteString`; expose
      `GenesisDataLoader`'s private `prepareHeader`/`getGenesisStateRoot` as `private[ethereum]` or move them to a
      shared object **without changing their bodies** (GT-1 of the genesis hash is `GenesisHashSpec`)
- [ ] T021 [US2] Write `src/main/scala/com/chipprbots/ethereum/chainspec/ChainSpecValidator.scala`:
      `validate(chainKey, source, entry: TypesafeConfig): Validated[NonEmptyList[ChainSpecError], ChainSpec]` running
      every check in data-model.md §2 invariants 1–5 and building
      `blockchainConfig = BlockchainConfig.fromRawConfig(entry)` (unchanged function) only when the entry is valid
- [ ] T022 [US2] Write `src/main/scala/com/chipprbots/ethereum/chainspec/ChainRegistry.scala`: `load(blockchains:
      TypesafeConfig)`, built-in set from the `blockchains.*` object keys minus `network`, `custom-chains-dir`,
      `registry`; operator dir scan (`*-chain.conf`, file-name order); collision/shadow rule; `keys`, `spec`,
      `toBlockchainsConfig(network)`; sources from `ConfigValue.origin()` (built-in) and absolute path (operator)
- [ ] T023 [US2] Edit `src/main/scala/com/chipprbots/ethereum/utils/Config.scala` `BlockchainsConfig.apply`: when
      `registry.enabled` (default `false`) delegate to `ChainRegistry.load(...).fold(errs => throw
      ChainSpecLoadException(errs), _.toBlockchainsConfig(network))`; otherwise today's code verbatim. Add
      `ChainSpecLoadException` in `ChainSpecError.scala`
- [ ] T024 [US2] Edit `src/main/resources/conf/base/blockchains.conf`: add `registry { enabled = false,
      allow-shadow = false }` with comments; exclude `registry` from the built-in key scan in both code paths
- [ ] T025 [US2] `sbt compile-all`; `./scripts/fukuii-test only "*chainspec* *BlockchainsConfigSpec* *ChainConfig*Spec*
      *MESSConfigParsingSpec*"` → PASS with `-Dfukuii.blockchains.registry.enabled=true` and without; `sbt scalafmtAll`
- [ ] T026 [US2] `prism` review of `chainspec/` (non-consensus); `eye` runs `./scripts/fukuii-test essential` once →
      baseline; open slice 2 PR

**Checkpoint**: MVP — the loader reproduces all eight built-ins byte-for-byte on both paths; invalid entries produce
named error values; nothing in the node's startup has changed yet.

---

## Phase 4: User Story 1 — An operator adds a chain without a release (Priority: P1) — slice 3 (+ slice 6 fixture)

**Goal**: `fukuii <key>` / `--chain <key>` accept any registry key; errors exit before the datadir exists;
`App.knownNetworks` is gone; `fukuii chains list|validate` ship; the flag defaults on.

**Independent Test**: SC-001 (quickstart US1) in the 023 harness; `LauncherIntegrationSpec`; `FailFastSpec`.

### Tests for User Story 1 (write first, must fail)

- [ ] T027 [P] [US1] Write `src/test/scala/com/chipprbots/ethereum/chainspec/FailFastSpec.scala` (tags `UnitTest,
      DatabaseTest`): for every "entry validation" / "selection" error class, build a `fukuii` config whose
      `db.rocksdb.path` is a fresh temp path and whose `custom-chains-dir` holds the invalid fixture; call
      `ConfigValidator.validate(config)`; assert the error line is present and `Files.exists(path)` is false (SC-003)
- [ ] T028 [P] [US1] Rewrite `src/test/scala/com/chipprbots/ethereum/LauncherIntegrationSpec.scala`: remove the
      reflective `isNetwork` (`:31-34, :54-55, :115, :125`); assert the candidate rule (`isChainKeyCandidate`:
      not a command, modifier or `-`-prefixed word), that `setNetworkConfig("plataberget2")` with no profile sets
      `fukuii.blockchains.network` on the default config, and that `--chain-registry-allow-shadow` sets
      `fukuii.blockchains.registry.allow-shadow`
- [ ] T029 [P] [US1] Edit `src/test/scala/com/chipprbots/ethereum/utils/BlockchainsConfigSpec.scala`: "give priority
      to custom configurations over built-in ones" (`:148`) becomes "refuses a custom entry that shadows a built-in"
      and "allows it with registry.allow-shadow = true"; add "fails on a missing custom-chains-dir" and "fails on an
      unparsable entry"
- [ ] T030 [P] [US1] Write `src/test/scala/com/chipprbots/ethereum/chainspec/ChainsCliSpec.scala`: `list` columns and
      sort order on the shipped set plus one operator entry; `--json` keys per contracts/cli.md; `validate <dir>`
      output and exit codes 0/1/2 (capture stdout; decline `Command.parse`)
- [ ] T031 [US1] `./scripts/fukuii-test only "*FailFastSpec* *LauncherIntegrationSpec* *BlockchainsConfigSpec*
      *ChainsCliSpec*"` → FAIL

### Implementation for User Story 1

- [ ] T032 [US1] Edit `src/main/scala/com/chipprbots/ethereum/ConfigValidator.scala`: `validate(config)` runs
      `ChainRegistry.load(config.getConfig("blockchains"))`, then
      `registry.spec(config.getString("blockchains.network"))` (`UnknownChainKey`), appends every `render`ed error
      to the fatal list; shadow warnings logged at WARN
- [ ] T033 [US1] Edit `src/main/scala/com/chipprbots/ethereum/Fukuii.scala`: move `ConfigValidator.validate` to the top
      of `main` on `ConfigFactory.load().getConfig("fukuii")` (before `Config` is touched at `:27`); read `tmpdir`
      from the raw config; keep the exit at `System.exit(1)`; same ordering in `nodebuilder/ChainTool.run` entry and
      `blockchain/checkpoint/CheckpointCli.main`
- [ ] T034 [US1] Edit `src/main/scala/com/chipprbots/ethereum/App.scala`: delete `knownNetworks` and `isNetwork`
      (`:17-28, :45-46`); add `isChainKeyCandidate`; `determineNetworkArg` and the dispatch (`:94-100, :245-296`) use
      it; `setNetworkConfig` sets `fukuii.blockchains.network` when no profile file exists (research R-4); add
      `--chain-registry-allow-shadow` → `System.setProperty("fukuii.blockchains.registry.allow-shadow","true")` and
      strip it; add `case Some("chains") => ChainsCli.main(tail)`; `showHelp` renders the `Networks:` block from
      `ChainRegistry` (read-only load; on failure print the error and point to `fukuii chains validate`)
- [ ] T035 [US1] Write `src/main/scala/com/chipprbots/ethereum/chainspec/ChainsCli.scala` (decline): `list [--json]
      [--verbose]`, `validate [<dir>] [--json]` per contracts/cli.md; exit codes 0/1/2 (3 reserved for T054)
- [ ] T036 [US1] Edit `src/main/resources/conf/base/blockchains.conf`: `registry.enabled = true`; update the
      `custom-chains-dir` comment (`:10-15`): entries no longer override built-ins; use `--chain-registry-allow-shadow`
- [ ] T037 [US1] Edit `scripts/ephemery/fetch-genesis.sh` (`:283-380`): emit `consensus = "engine-api"` and
      `genesis-hash = "${EXPECTED_HASH}"` into the generated entry; keep `sync { do-snap-sync = false }` and drop
      the `mess` block (DC-2)
- [ ] T038 [US1] Write `docs/adr/infrastructure/INF-010-chain-registry.md` (decision, GT-1, DC-5 derivation, DC-6
      record, D-1 outcome) and link it from `docs/adr/infrastructure/README.md`
- [ ] T039 [US1] `sbt compile-all`; `./scripts/fukuii-test only "*chainspec* *LauncherIntegrationSpec*
      *BlockchainsConfigSpec* *ConfigSpec*"` → PASS; manual smoke per quickstart US1 AS-2/AS-3 (`fukuii plataberget2`
      with an operator dir; `fukuii foo` → `[UnknownChainKey]`, exit 1, no datadir); `sbt scalafmtAll`
- [ ] T040 [US1] `prism` review; `eye` runs `./scripts/fukuii-test essential` → baseline; bump minor version
      (`version.sbt`) and write the breaking-change note (custom-chains-dir semantics); open slice 3 PR

**Checkpoint**: an operator entry starts with `fukuii <key>`; every DC-1 error exits before the datadir exists;
`fukuii chains list|validate` work; `knownNetworks` is gone.

### SC-001 fixture (slice 6, after US3)

- [ ] T041 [US1] Add the Platåberget-as-operator fixture to the 023 harness (`specs/023-fleet-topology` harness
      location, FR-007): `plataberget2-chain.conf` copied from `conf/base/chains/plataberget-chain.conf` with the
      Engine API devnet's genesis when the harness runs a devnet instead; a harness step runs `fukuii chains validate`
      then starts `ingest` and asserts the first imported block (SC-001)
- [ ] T042 [P] [US1] Update `.claude/skills/fukuii-custom-networks/SKILL.md` step 3 (`:31-33`) to "write a ChainSpec"
      per contracts/chainspec.md "Writing an entry", add a validate step, and point the deep reference at
      `specs/024-chain-registry/contracts/chainspec.md`
- [ ] T043 [P] [US1] Update `docs/runbooks/custom-networks.md` (`:166-167, :386-387, :591-592, :763-764`): entries
      need `consensus`, `genesis-hash`, `sync.do-snap-sync`, `mess` (ETC); `fukuii chains validate`; shadowing rule

---

## Phase 5: User Story 3 — A misconfigured profile cannot start (Priority: P2) — slices 4 and 5

**Goal**: DC-5 derivation makes `sepolia.conf`'s warning case a load-time error; DC-2 is enforced on the shipped
entries; DC-6 refuses a moved fork on a synced datadir.

**Independent Test**: SC-003 (every FR-004/FR-005 class exits before the datadir is created or opened);
quickstart US3.

### Slice 4 — DC-5 derivation and DC-2 (requires user decision D-1 before T050)

#### Tests (write first, must fail)

- [ ] T044 [P] [US3] Write `src/test/scala/com/chipprbots/ethereum/chainspec/DeploymentDerivationSpec.scala` (tags
      `UnitTest, ConsensusTest`): for each built-in, `DeploymentDerivation.apply(fukuiiConfig, spec)` yields
      `mining.protocol`, `sync.snap-sync.storage-scheme`, `sync.do-snap-sync` per research R-5 table;
      `ValidatorsExecutor.apply(MiningConfig(derived).protocol).blockHeaderValidator` is `PoWBlockHeaderValidator`
      for `etc`, `mordor`, `gorgoroth`, `test` and `TransitionBlockHeaderValidator` for `eth`, `sepolia`,
      `plataberget`, `hive` (the validator-selection pin forge/beacon review); a profile with `mining.protocol =
      "pow"` on `sepolia` → `ProfileConsensusMismatch` naming `pow`, `engine-api`, the origin line; `restricted-pow`
      and `mocked` accepted on `ethash-pow`; `storage-scheme = "hash"` on `eth` → error; explicit equal values accepted;
      `EngineApiNotConfigured` on `sepolia` with `engine-api.enabled = false` for role `ingest`, not for offline mode
- [ ] T045 [P] [US3] Extend `ChainSpecValidatorSpec`: shipped `etc`/`mordor`/`gorgoroth`/`test` entries pass DC-2;
      `eth`/`sepolia`/`plataberget`/`hive` pass (no `mess`); the quickstart `mordor2` (mess removed) →
      `MessBlockMissing` with "default is false" in the message; an ETH entry with `mess` → `MessBlockOnEngineApiChain`
- [ ] T046 [US3] Extend `GoldenBlockchainConfigSpec` with the D-1 delta allowlist: a per-key set of permitted
      differing paths (`messConfig.reactivationBlock` for `eth`, `sepolia`, `plataberget`, `hive`, `test`; empty for
      the rest) compared against the slice-1 fixtures kept as `src/test/resources/chainspec/golden/v0/<key>.golden.txt`;
      assert `messConfig.enabled == false` on all five before and after
- [ ] T047 [US3] `./scripts/fukuii-test only "*DeploymentDerivationSpec* *ChainSpecValidatorSpec* *Golden*"` → FAIL

#### Implementation

- [ ] T048 [US3] Write `src/main/scala/com/chipprbots/ethereum/chainspec/DeploymentDerivation.scala`:
      `apply(fukuii: TypesafeConfig, spec: ChainSpec, role: Role, offline: Boolean):
      Either[NonEmptyList[ChainSpecError], TypesafeConfig]` — inject `mining.protocol`,
      `sync.snap-sync.storage-scheme`, `sync.do-snap-sync` when absent
      (`withValue`), check when present (research R-5 table), `EngineApiNotConfigured` for `engine-api` + `ingest` +
      not offline
- [ ] T049 [US3] Edit `src/main/scala/com/chipprbots/ethereum/utils/InstanceConfig.scala`: primary constructor takes
      `rawConfig`; `val config: TypesafeConfig = DeploymentDerivation.apply(rawConfig, registry.spec(network), role,
      offline).fold(throw ChainSpecLoadException(_), identity)`; `blockchains` from the registry; `ConfigValidator`
      runs the same derivation first so the throw is unreachable at runtime. Edit `ConfigValidator.validate` to call
      `DeploymentDerivation` after the registry step (role from 023's flag when present, else `ingest`; offline for
      `ChainTool`/`CheckpointCli`/`ChainsCli`)
- [ ] T050 [US3] **After D-1**: edit the eight entries in `src/main/resources/conf/base/chains/`: add
      `consensus = "ethash-pow"` (etc, mordor, gorgoroth, test) / `"engine-api"` (eth, sepolia, plataberget, hive);
      add `sync { do-snap-sync = true }` (etc, mordor, test) / `false` (gorgoroth, eth, sepolia, hive; plataberget
      already has it `:58-60`); add `genesis-hash` for every entry with a genesis file (values from the reference
      clients, verified by `GenesisHashSpec`); remove dead keys `mordor-chain.conf:9 capabilities`,
      `gorgoroth-chain.conf:221-230` MESS tuning keys; DC-2: remove `mess {}` from `eth-chain.conf:257-259`,
      `sepolia-chain.conf:134-136`, `plataberget-chain.conf:156-158`, `hive-chain.conf:102-104`; add
      `mess { enabled = false }` to `test-chain.conf`; regenerate the golden fixtures (T046 allowlist must be the
      only diff)
- [ ] T051 [US3] Edit profiles: `conf/sepolia.conf` (`:8-20` sync lines, `:32-49` mining block → one line
      "mining.protocol and storage-scheme are derived from the ChainSpec's consensus (spec 024 DC-5); a wrong value
      here is a load-time error"), `conf/eth.conf:8-19, :31-48`, `conf/plataberget.conf:13-25, :37-42`,
      `conf/ephemery.conf:24-35, :44-48`,
      `conf/gorgoroth.conf:27-33 protocol = "ethash"` and `:35-38`, `conf/hive.conf:8-10`; keep `testmode.conf:7`;
      `conf/base/mining.conf:15-21` remove `protocol = pow` (comment explains derivation); `conf/base/sync.conf:6`
      remove `do-snap-sync = true` (comment explains derivation); SC-004
- [ ] T052 [US3] `sbt compile-all`; `./scripts/fukuii-test only "*chainspec* *ConfigSpec* *MiningConfig* *SyncConfig*
      *ChainConfig*Spec*"` → PASS; manual smoke: `fukuii mordor`, `fukuii sepolia` start and log the derived protocol
      (`MiningBuilder.scala:73` line); quickstart US3 AS-1/AS-2; `sbt scalafmtAll`
- [ ] T053 [US3] `forge` review (ETC derivation, `test` delta, gorgoroth), `beacon` review (ETH derivation, four
      deltas, sepolia.conf comment); `eye` runs `./scripts/fukuii-test essential` and both ethereum/tests jobs →
      baseline; open slice 4 PR (after 020 PR 5 is on staging, or rebased onto it)

### Slice 5 — Datadir compatibility record (DC-6, FR-005)

#### Tests (write first, must fail)

- [ ] T054 [P] [US3] Write `src/test/scala/com/chipprbots/ethereum/chainspec/DatadirChainRecordSpec.scala` (tags
      `UnitTest`, ScalaCheck): `render`/`parse` round-trip over arbitrary `ForkBlockNumbers`/`ForkTimestamps`/TTD;
      sorted keys; sentinels and `None` render as `-`; `v1;` prefix; unknown version → `Left`
- [ ] T055 [P] [US3] Write `src/test/scala/com/chipprbots/ethereum/chainspec/DatadirCompatibilitySpec.scala` (tags
      `UnitTest, ConsensusTest`): table of (recorded, requested, head) → outcome per geth `CheckCompatible`:
      unchanged → `Right(None)`; future fork added/moved → `Right(Some(rewritten))`; fork moved later while
      `recorded ≤ head` → `ForkScheduleIncompatible`; fork moved earlier to ≤ head → error; timestamp fork vs head
      timestamp; `chainKey` mismatch; genesis mismatch; no record + matching genesis → `Right(Some(backfill))`
- [ ] T056 [P] [US3] Write `src/test/scala/com/chipprbots/ethereum/chainspec/DatadirCompatibilityNodeSpec.scala`
      (tags `IntegrationTest, DatabaseTest`): `Storages` over `EphemDataSource` seeded with a genesis and best block
      past Spiral on the shipped `mordor` entry; a ChainSpec with `spiral-block-number` moved later → the runner
      returns the error and a probe records that `loadGenesisData` and the actor system were never reached; the
      record is unchanged; first start on an empty store writes the three keys in one batch; a pre-record store with
      a matching genesis gets backfilled
- [ ] T057 [US3] `./scripts/fukuii-test only "*Datadir*"` → FAIL

#### Implementation

- [ ] T058 [US3] Edit `src/main/scala/com/chipprbots/ethereum/db/storage/AppStateStorage.scala`: `Keys.ChainSpecChainKey
      = "chain.spec.chainKey"`, `ChainSpecGenesisHash = "chain.spec.genesisHash"`, `ChainSpecForkSchedule =
      "chain.spec.forkSchedule"`; `getDatadirChainRecord(): Option[...]` (all three or none) and
      `putDatadirChainRecord(r): DataSourceBatchUpdate` (one batch) — `vault` reviews names and batch
- [ ] T059 [P] [US3] Write `src/main/scala/com/chipprbots/ethereum/chainspec/DatadirChainRecord.scala` (pure
      render/parse) and `DatadirCompatibility.scala` (pure `check` + `run(storages, spec, chainKey, offline)` that
      reads best block / best header timestamp as `StdNode.scala:428-434` does and writes the record with
      `updateSync` unless offline)
- [ ] T060 [US3] Edit `src/main/scala/com/chipprbots/ethereum/blockchain/data/GenesisDataLoader.scala` (after 020 PR
      5): `onFirstStored` also writes the record from the active `ChainSpec` in the same batch as the genesis-related
      marker (`:128`); switch the genesis read to a `Reader` (no `mkString`, `:70, :79`) — behaviour of parsing
      unchanged
- [ ] T061 [US3] Edit `src/main/scala/com/chipprbots/ethereum/nodebuilder/StdNode.scala` (after 020 PR 5): first
      statement of `start()` (before `loadGenesisData()` at `:60`) runs `DatadirCompatibility.run`; on `Left` log the
      error, close the data source, `sys.exit(3)`; expose `ChainSpec` to `StdNode` via `InstanceConfig.chainSpec`
- [ ] T062 [US3] Extend `src/main/scala/com/chipprbots/ethereum/chainspec/ChainsCli.scala`: `validate --datadir <path>`
      opens read-only (RocksDB read-only open through the existing `RocksDbDataSource` options) and runs the check;
      exit 3 on refusal; update `ChainsCliSpec`
- [ ] T063 [US3] `sbt compile-all`; `./scripts/fukuii-test only "*Datadir* *ChainsCli* *StdNode* *GenesisDataLoader*"`
      → PASS; manual: quickstart US3 AS-3 on a Mordor datadir (or the harness's); `sbt scalafmtAll`
- [ ] T064 [US3] `vault` review (keys, batch, open order, backfill, mid-SNAP head); `forge`/`beacon` confirm the
      fork-name list in `DatadirChainRecord` covers every `ForkBlockNumbers` and `ForkTimestamps` field plus TTD;
      `eye` runs `./scripts/fukuii-test essential` → baseline; open slice 5 PR

**Checkpoint**: every FR-004/FR-005 rejection class has a test that proves the process exits before the datadir is
created (DC-1/DC-5) or before anything binds (DC-6); `conf/sepolia.conf` carries a one-line reference instead of the
warning.

---

## Phase 6: Polish & Cross-Cutting Concerns — slice 6

- [ ] T065 Remove the `fukuii.blockchains.registry.enabled` flag and the legacy code path in
      `src/main/scala/com/chipprbots/ethereum/utils/Config.scala` `BlockchainsConfig.apply`; drop the dual-path
      assertions from `GoldenBlockchainConfigSpec` (keep the fixture equality); delete
      `src/test/resources/chainspec/golden/v0/` once D-1's delta is recorded in INF-010
- [ ] T066 [P] `fukuii chains list --verbose` shows resource profiles and the derived storage scheme; `fukuii --help`
      text reviewed in `App.scala`
- [ ] T067 [P] Log lines and metrics introduced by this spec use `chainKey` (DC-3): grep `src/main/scala/com/chipprbots/
      ethereum/chainspec/` for `chainId` in log templates without a `chainKey` beside it → 0
- [ ] T068 [P] Add `specs/024-chain-registry/contracts/chainspec.schema.json` to the release artefact list
      (`specs/020-scala-3-9-lts-migration/contracts/published-artifacts.md` pattern) so Orbita fetches a versioned copy
- [ ] T069 Run quickstart.md end to end (US1, US2, US3) on a built distribution; record `VERIFY:` lines in the PR
- [ ] T070 `sbt pp` on the clean branch; `eye` runs `./scripts/fukuii-test essential` and both ethereum/tests jobs →
      baseline; open slice 6 PR

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: no dependencies.
- **Foundational (Phase 2)**: needs T002–T004; **blocks every user story** (GT-1 fixtures must exist before any loader
  code).
- **US2 (Phase 3)**: after Phase 2. MVP.
- **US1 (Phase 4)**: after US2 (the registry and errors exist). T041–T043 (SC-001 fixture, skill, runbook) wait for
  slice 4 (`consensus` key in the shipped entries) and the 023 harness.
- **US3 (Phase 5)**: slice 4 after US1 slice 3 (fail-fast path) **and user decision D-1**; slice 5 after slice 4
  (`InstanceConfig.chainSpec`). Both after 020 PR 5 for `StdNode.scala` / `GenesisDataLoader.scala` edits.
- **Polish (Phase 6)**: after everything.

### User Story Dependencies

- **US2 (P1)**: only Foundational. Independently testable: golden equality on both paths.
- **US1 (P1)**: US2's registry. Independently testable: an operator entry starts; `fukuii foo` fails named.
- **US3 (P2)**: US1's fail-fast wiring; D-1. Independently testable: quickstart US3 AS-1..AS-3.

### Within Each User Story

- Tests first and failing (T013–T017, T027–T031, T044–T047, T054–T057) before implementation.
- Model (`ChainSpecError`, `ChainSpec`, `ConsensusType`) before validator before registry before wiring.
- A slice's PR opens only after `testEssential` is at baseline (`eye`).

### Parallel Opportunities

- Phase 1: T002, T003, T004, T005 in parallel.
- Phase 2: T009, T010 in parallel with T007/T008.
- Phase 3 tests T013–T016 in parallel; implementation T018, T019, T020 in parallel, then T021 → T022 → T023.
- Phase 4 tests T027–T030 in parallel; T037, T038 in parallel with T032–T036; T042, T043 in parallel.
- Phase 5: T044, T045 in parallel; T054, T055, T056 in parallel; T059 in parallel with T058.
- Phase 6: T066, T067, T068 in parallel.

---

## Parallel Example: User Story 2

```bash
# Tests first (different files):
Task: "Extend GoldenBlockchainConfigSpec with registry equality (T013)"
Task: "Write ChainSpecValidatorSpec with one test per error (T014)"
Task: "Write ChainRegistrySpec: collisions, shadowing, missing dir (T015)"
Task: "Write GenesisHashSpec against published genesis hashes (T016)"

# Then the model files together:
Task: "ChainSpecError.scala (T018)"  Task: "ChainSpec.scala (T019)"  Task: "GenesisHash.scala (T020)"
# Then sequentially: ChainSpecValidator (T021) → ChainRegistry (T022) → BlockchainsConfig adapter (T023)
```

## Parallel Example: User Story 1

```bash
Task: "FailFastSpec (T027)"  Task: "LauncherIntegrationSpec rewrite (T028)"
Task: "BlockchainsConfigSpec shadow tests (T029)"  Task: "ChainsCliSpec (T030)"
# Implementation: ConfigValidator (T032) → Fukuii.main reorder (T033) → App.scala (T034) → ChainsCli (T035)
# In parallel with those: fetch-genesis.sh (T037), ADR INF-010 (T038)
```

---

## Implementation Strategy

### MVP First (US2: golden capture + loader behind a flag)

1. Phase 1 + Phase 2 → the eight golden fixtures are committed on today's files (slice 1 PR, test-only).
2. Phase 3 → the loader reproduces them on both paths with the flag off by default (slice 2 PR).
3. **STOP and VALIDATE**: `GoldenBlockchainConfigSpec` green with `-Dfukuii.blockchains.registry.enabled=true` and
   `false`; `testEssential` at baseline.

### Incremental Delivery

1. Slice 3 (US1): fail-fast wiring, `knownNetworks` removal, CLI, flag on → an operator chain starts without a release.
2. Slice 4 (US3, after D-1): derivation + DC-2 + conf edits → `sepolia.conf`'s warning case is impossible.
3. Slice 5 (US3): datadir record → a moved fork on a synced datadir is refused.
4. Slice 6: SC-001 fixture in the 023 harness, docs, skill, flag removal.

### Task counts

| Story | Tasks |
| --- | --- |
| Setup + Foundational | T001–T012 (12) |
| US2 | T013–T026 (14) |
| US1 | T027–T043 (17) |
| US3 | T044–T064 (21) |
| Polish | T065–T070 (6) |
| **Total** | **70** |

## Notes

- `[P]` = different files, no unfinished dependency.
- Never edit `utils/BlockchainConfig.scala` `fromRawConfig`, `consensus/`, `vm/`, `ledger/` in this spec.
- The golden fixtures change exactly once (slice 4, D-1); any other diff under `src/test/resources/chainspec/golden/`
  is a bug.
- `sbt compile-all` after every file edit; `./scripts/fukuii-test only "*X*"` per phase; `./scripts/fukuii-test
  essential` once per slice at the end; `sbt scalafmtAll` before every commit.
- `VERIFY: ran <command> — result: PASS | FAIL | DID NOT RUN` in every PR description.
