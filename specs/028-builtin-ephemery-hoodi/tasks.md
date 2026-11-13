---

description: "Task list for spec 028: Ephemery and Hoodi as built-in networks"
---

# Tasks: Ephemery and Hoodi as Built-in Networks

**Input**: Design documents from `/specs/028-builtin-ephemery-hoodi/`.

**Prerequisites**:
- spec.md (Planned 2026-11-12; FR-017 amended);
- plan.md (slices S1, S2, S3, S4a, S4b, S5);
- research.md (R1 to R17);
- data-model.md;
- contracts/ (cli, config, exit-codes, release-json);
- quickstart.md.

**Tests**: The spec requires them (FR-002, FR-019, SC-004). In every slice the tests are written first, and must fail
before the task that makes them pass (a compile error counts). The rules:
- ScalaTest `AnyFlatSpec` with `Matchers`, tagged `UnitTest`, plus `NetworkTest` where relevant. Forked tests sit
  under `src/it` and are tagged `IntegrationTest`.
- No `Thread.sleep`: a fixed or offset `Clock`, a manual scheduler, and latches the test releases.
- The `ephemery` unit tests never read the system clock (research R16). The bundle ends on 2026-11-19.

**Organization**: Setup, then one phase per slice (one PR each), then validation and closure. Each slice has its own
backlog row and branch, and is built in a full clone (never a worktree):

| Slice | Backlog | Branch | Clone |
|-------|---------|--------|-------|
| S1 | B-231 | `feat/028-s1-hoodi` | `~/work/e028-s1` |
| S2 | B-232 | `feat/028-s2-release-lib` | `~/work/e028-s2` |
| S3 | B-233 | `feat/028-s3-bundle` | `~/work/e028-s3` |
| S4a | B-234 | `feat/028-s4a-transport` | `~/work/e028-s4a` |
| S4b | B-235 | `feat/028-s4b-refresh` | `~/work/e028-s4b` |
| S5 | B-236 | – | – |

Every slice ends with the same gate shape and an exit task, using the review cycle of plan.md.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel: different files, and no dependency on an unfinished task. "Parallel" means writing,
  not sbt. Only one sbt runs at a time on this host: `flock /home/dontpanic/work/.sbt.lock sbt -Dsbt.color=false
  -Dsbt.supershell=false -Dsbt.server.forcestart=false <cmd>`.
- **[Story]**:
  - US1: Ephemery with no setup.
  - US2: refresh after a reset.
  - US3: Hoodi.
  - US4: operator control.
  - US5: exit at the reset.

  It is omitted for setup, foundational, gate and closure tasks.
- **Paths** are relative to the repository:
  - `ephemery/` means `src/main/scala/com/chipprbots/ethereum/ephemery/`;
  - `ephemery tests` means `src/test/scala/com/chipprbots/ethereum/ephemery/`;
  - `root tests` means `src/test/scala/com/chipprbots/ethereum/`;
  - `it/` means `src/it/scala/com/chipprbots/ethereum/`.

---

## Phase 1: Setup

**Purpose**: The baseline and the bookkeeping. No behaviour change.

- [x] T001 Add one backlog row per slice, referencing B-227 and this spec: B-231 S1, B-232 S2, B-233 S3, B-234 S4a,
      B-235 S4b, B-236 S5. Done 2026-11-12.
- [ ] T002 Baseline, one JVM, on `origin/staging`: `sbt "testOnly *Ephemery* *ForkId* *GenesisDataLoader*
      *ChainConfigMatrixSpec *LauncherIntegrationSpec *BlobScheduleSpec *EestBlockchainReplay*"`. Record the suite and
      test counts in each slice's PR body, as the before figure.
- [x] T003 On the first code commit of whichever slice lands first, update spec.md:
      - set `**Status**` to `Active (<date>)`;
      - set `**In flight**` to that slice's branch;
      - run `python3 -I scripts/specs/index.py` and commit `specs/README.md`.

**Checkpoint**: the baseline is recorded and the backlog rows exist.

---

## Phase 2: Slice S1, Hoodi as a built-in network (US3, P2). B-231. Independent: starts at once.

**Goal**: `fukuii hoodi` starts with chain id 560048, the published genesis hash, Hoodi's forks, blob schedule and
bootnodes, and peers on Hoodi (FR-018 to FR-020, SC-005).

**Independent test**: `HoodiGenesisSpec` and `ForkIdHoodiSpec` pass. A clean `fukuii hoodi` connects to Hoodi peers,
which accept its fork id.

- [x] T004 Make a full clone at `~/work/e028-s1` from `origin/staging`, on branch `feat/028-s1-hoodi`. Claim B-231
      (`backlog.py claim B-231`).
- [x] T005 Pre-flight with `beacon`, model `sonnet`, before any code. Give it research.md R15 and the raw [H] and [G]
      files. Have it:
      - confirm every value in R15's table and the 5 fork-id rows;
      - confirm the genesis re-encoding: `timestamp` to `"0x67d80ec0"`, `mixhash` to `mixHash`;
      - confirm that `shanghai-timestamp = 0` and `cancun-timestamp = 0` are the right encoding for forks active at
        the genesis timestamp.

      Record its note in the PR body.

### Tests for S1 (write first, see them fail)

- [x] T006 [P] [US3] Write `src/test/scala/com/chipprbots/ethereum/blockchain/data/HoodiGenesisSpec.scala`. It loads
      the built-in `hoodi` chain config and builds the genesis through `GenesisDataLoader` over `EphemDataSource`. It
      checks:
      - the hash equals `0xbbe312868b376a3001692a646dd2d7d1e4406380dfd86b98aa8a34d1557c971b`;
      - the state root equals block 0's `stateRoot` from a public Hoodi RPC (fetch it once at implementation; put the
        value and its source in a comment);
      - the load is not refused, even with 3 storage accounts and `countsExact = false` (research R15's correction).
- [x] T007 [P] [US3] Write `src/test/scala/com/chipprbots/ethereum/network/ForkIdHoodiSpec.scala`, modelled on
      `ForkIdSepoliaSpec.scala`. It uses `ForkId.create(genesisHash, 1742212800L, blockchains("hoodi"))` and checks the
      5 rows of research R15, from `0xbef71d30`/1742999832 to `0x23aa1351`/None. Tag it `UnitTest`, `NetworkTest`.
- [x] T008 [P] [US3] Write `src/test/scala/com/chipprbots/ethereum/network/discovery/HoodiBootnodeSpec.scala`, the
      pin test of R15.
      - It checks that `Node.fromUri` on `enode://70ba…d1a3@212.99.218.66:0?discport=20153` gives TCP 0 and UDP 20153.
      - It traces what `DiscoveryConfig` and the peer dialler do with a TCP-0 bootstrap node: a discovery ping to the
        UDP port, and whether any RLPx dial to port 0 happens. It asserts the behaviour it finds.
      - The result decides T012's bootnode list.
- [x] T009 [P] [US3] Edit three existing tests:
      - `src/test/scala/com/chipprbots/ethereum/utils/ChainConfigMatrixSpec.scala`: add `hoodi` to `allOf` (`:127`)
        and `"hoodi" -> 0x00000000219ab540356cBB839Cbe05303d7705Fa` to `ExpectedDepositContract` (`:153`);
      - `root tests/LauncherIntegrationSpec.scala`: add `hoodi` to the known networks (`:129`) and the conf pairing
        (`:355`);
      - `src/test/scala/com/chipprbots/ethereum/utils/BlobScheduleSpec.scala`: add a Hoodi row equal to Sepolia's.

### Implementation for S1

- [x] T010 [US3] Create `src/main/resources/conf/base/chains/hoodi-genesis.json` from [H] `metadata/genesis.json`:
      - `timestamp` becomes `"0x67d80ec0"`;
      - `mixhash` is renamed to `mixHash`;
      - `extraData` becomes `"0x"`;
      - the `config` block is dropped;
      - `alloc` is copied verbatim.

      If S2's `GenesisJsonEncoder` is merged, use it. Otherwise use a one-off `jq`, with the command in the commit
      message. T006 is the check.
- [x] T011 [US3] Create `src/main/resources/conf/base/chains/hoodi-chain.conf` by copying `sepolia-chain.conf` and
      setting the values of data-model.md "HoodiNetworkEntry". That includes:
      - `consensus = "engine-api"` and `genesis-hash`;
      - `dns-discovery-domains = ["all.hoodi.ethdisco.net"]`;
      - no `amsterdam-timestamp`.
- [x] T012 [US3] Set `bootstrap-nodes` in `hoodi-chain.conf` from T008's result: either [H]'s 6 enodes, or the 4
      `:30303` enodes (the 3 legacy from [G] plus Lodestar). Record the choice in the PR body.
- [x] T013 [US3] Add Hoodi to the launch and discovery code:
      - `src/main/resources/conf/base/blockchains.conf`: `hoodi {include required("chains/hoodi-chain.conf")}` after
        sepolia;
      - `src/main/resources/conf/hoodi.conf`: a copy of `sepolia.conf` with `network = "hoodi"` (contracts/config.md);
      - `src/main/scala/com/chipprbots/ethereum/network/discovery/Eip1459.scala` (`DnsTrustAnchors.shipped`, `:230`):
        `all.hoodi.ethdisco.net` mapped to the EF key Sepolia's entry uses.
- [x] T014 [US3] Add Hoodi to the names the node prints:
      - `src/main/scala/com/chipprbots/ethereum/App.scala`: `"hoodi"` in `knownNetworks` and the help-text line
        (contracts/cli.md);
      - `src/main/scala/com/chipprbots/ethereum/jsonrpc/mcp/McpResources.scala:78`: map `560048` to `"hoodi"`.
- [x] T015 [P] [US3] Write the docs:
      - a new `docs/runbooks/hoodi.md`: launch, pairing a CL (Lighthouse `--network hoodi`), the JWT as an absolute
        path, sync defaults as Sepolia's;
      - a `CHANGELOG.md` entry;
      - Hoodi lines in the network lists of `README.md`, `docs/index.md` and the `.claude/skills/*/SKILL.md` files that
        list Sepolia. `grep -rln -i sepolia .claude/skills` finds them.

### Gate and exit for S1

- [x] T016 Run `sbt compile-all`, then `sbt "testOnly *HoodiGenesisSpec *ForkIdHoodiSpec *HoodiBootnodeSpec
      *ChainConfigMatrixSpec *LauncherIntegrationSpec *BlobScheduleSpec"`. Expect PASS.
- [x] T017 Run `sbt "testOnly *ForkId* *GenesisDataLoader* *Mordor* *Gorgoroth*"`. Expect PASS with no assertion
      changes (FR-021). Then `sbt formatAll`, read `git diff`, and run `sbt formatCheck`. Expect clean.
- [ ] T018 [US3] Manual, with no sbt running. Run `fukuii hoodi -Dfukuii.datadir=/chipprbots/blockchain/hoodi-check`,
      a new path. It needs a JWT file and Lighthouse `--network hoodi` with checkpoint sync. Check:
      - chain id 560048 and the genesis hash in the log;
      - peers with fork id `0x23aa1351`;
      - the CL starting sync.

      Stop once that holds. This is not a soak (research R17).
- [ ] T019 Exit S1:
      1. `forge` (sonnet) writes a no-ETC-impact note.
      2. `beacon` reviews the diff.
      3. `prism`, then `eye`.
      4. Run `sbt pp` alone.
      5. Open the PR `feat(chains): Hoodi as a built-in network (spec 028 S1)` with `Backlog: B-231`.
      6. `backlog.py status B-231 review --pr <n>`; tick the boxes.

**Checkpoint**: `fukuii hoodi` works out of the box.

---

## Phase 3: Slice S2, one Ephemery rule engine (foundational for US1, US2, US4). B-232. Starts at once.

**Goal**: the rules of `fetch-genesis.sh` live in one Scala library. `fukuii cli ephemery-fetch --from-dir` applies
them, and the script delegates to it (R1, R2, R3; FR-006, FR-007, FR-013, FR-015).

**Independent test**: the golden renders equal the committed fixtures, every refusal has a test, and the 165 hash is
pinned.

- [ ] T020 Make a full clone at `~/work/e028-s2` on branch `feat/028-s2-release-lib`, and claim B-232. Pre-flight:
      - `beacon` (sonnet) on R1 to R3: the encoder and renderer, and the genesis extraction;
      - `forge` (sonnet) on R3's change to the shared `GenesisDataLoader` path.

      Record both notes.
- [ ] T021 [P] Download the raw inputs. Put `testnet-all.tar.gz` for `ephemery-164` and `ephemery-165` into a new,
      empty scratch directory. Copy only `genesis.json`, `retention.vars`, `deposit_contract_block_hash.txt`,
      `enodes.txt` and `boot_enr.txt` into `src/test/resources/ephemery/releases/ephemery-{164,165}/`. Add a `README.md`
      there with the source URL, each tarball's sha256 and the date. Never commit `parsedBeaconState.json` or
      `genesis.ssz`.

### Tests for S2 (write first, see them fail)

- [ ] T022 [P] Write `ephemery tests/EphemeryGoldenRenderSpec.scala`. For 164 and 165, the raw inputs must render
      byte for byte to `src/test/resources/ephemery/ephemery-16x/ephemery-chain.conf` and `ephemery-genesis.json`.
      This replaces `scripts/ephemery/check-fixture.sh`.
- [ ] T023 [P] Write `ephemery tests/EphemeryReleaseSpec.scala`: one test for each of R1 to R11 and N1 to N3
      (research R2).
      - Each test mutates one field of an in-memory copy of the raw 165 inputs.
      - It checks the `ReleaseRefusal` case, its exit class (contracts/exit-codes.md), and that the message keeps
        `fetch-genesis.sh`'s wording.
      - Also: a valid 164 and 165, N4's reset time, and that large JSON numbers stay exact.
- [ ] T024 [P] Write `src/test/scala/com/chipprbots/ethereum/blockchain/data/GenesisHeaderExtractionSpec.scala`.
      - The companion's pure `genesisHeader` must equal what `loadGenesisData` stores for the etc, mordor, sepolia and
        plataberget built-ins and the ephemery-164 and -165 fixtures.
      - A subclass that overrides the instance `prepareHeader`, as `src/test/scala/com/chipprbots/ethereum/ledger/
        EestBlockchainReplay.scala:279` does, still takes effect in `loadGenesisData`.
- [ ] T025 [P] Write `ephemery tests/EphemeryFetchCliSpec.scala`.
      - `ephemery-fetch --from-dir <raw 165> --out <tmp>` writes the script's layout:
        `ephemery-165/{testnet,chains,conf,release.env,release.json}`, `current` and `ephemery-local.conf`.
        `release.env` has the script's keys.
      - A usage error exits 2.
      - A refusal exits with its class.

### Implementation for S2

- [ ] T026 In `src/main/scala/com/chipprbots/ethereum/blockchain/data/GenesisDataLoader.scala`, extract the pure
      `GenesisDataLoader.genesisHeader(genesisData)(using BlockchainConfig): BlockHeader` into the companion: the state
      root over an in-memory MPT on `EphemDataSource`, then today's header logic.
      - The instance `protected def prepareHeader` (`:250`) delegates to the companion, so overrides still apply.
      - `loadGenesisData` (`:110-149`) keeps calling the instance method. No behaviour change.
- [ ] T027 Run the transitive no-`Config` check (R3). From the extraction and every file under `ephemery/`, list the
      reachable classes: MPT storage, `EphemDataSource`, `BlockchainConfig.fromRawConfig`, `BlobSchedule`, json4s
      formats. Grep each for `utils.Config` imports and `Config.` reads. Fix any hit by passing the value explicitly.
      Put the list and the result in the PR body.
- [ ] T028 [P] Write `ephemery/ReleaseInputs.scala`: the EL members read from a directory. `retention.vars` is parsed
      with the key=value regex of R2 and never sourced. `chainspec.json` is optional.
- [ ] T029 [P] Write `ephemery/ReleaseRefusal.scala`: the sealed ADT of data-model.md, with the exit class (69 or 78)
      and the message of each case (contracts/exit-codes.md).
- [ ] T030 Write `ephemery/GenesisJsonEncoder.scala`: R9 and R10, the top-level allow-list and the hex re-encoding.
- [ ] T031 Write `ephemery/ChainConfRenderer.scala`: HOCON identical to `fetch-genesis.sh:280-372`. In this commit it
      has no spec 024 keys.
- [ ] T032 Write `ephemery/EphemeryRelease.scala`: `validate(inputs): Either[ReleaseRefusal, ValidRelease]`, applying
      R1 to R11, N1, N2 and N4. It then checks N3 by parsing the rendered config with `BlockchainConfig.fromRawConfig`
      and computing the hash with T026's function.
- [ ] T033 [P] Write `ephemery/ReleaseMetadata.scala`: the `release.json` codec and rules of
      contracts/release-json.md.
- [ ] T034 Write `ephemery/EphemeryFetchCli.scala` and add `ephemery-fetch` to
      `src/main/scala/com/chipprbots/ethereum/cli/CliCommands.scala`. In S2, `--from-dir` and `--out` are required.
      `App.scala` makes `cli ephemery-fetch` call `setNetworkConfig("ephemery")` before anything reads configuration
      (contracts/cli.md, "How it finds its configuration").
- [ ] T035 Commit 1: the byte-identical port. T022 to T025 pass.
- [ ] T036 Commit 2: `ChainConfRenderer` emits `consensus = "engine-api"` and `genesis-hash = "0x…"` inside the chain
      object (R14). Regenerate `src/test/resources/ephemery/ephemery-16x/ephemery-chain.conf` with the CLI, and review
      the fixture diff: two lines per file.
- [ ] T037 Commit 3, the script and its fixture check:
      - `scripts/ephemery/fetch-genesis.sh` keeps its flags, download and extraction. It replaces the rules and
        rendering (`:105-415`) with `"${FUKUII_BIN:-fukuii}" cli ephemery-fetch --from-dir "$SRC" --out "$OUT"`. A
        missing binary exits 2, naming `FUKUII_BIN`.
      - Delete `scripts/ephemery/check-fixture.sh`.
      - Replace its reference at `docs/runbooks/ephemery.md:213` with `EphemeryGoldenRenderSpec`.

### Gate and exit for S2

- [ ] T038 Run `sbt compile-all`, then `sbt "testOnly com.chipprbots.ethereum.ephemery.* *GenesisHeaderExtractionSpec
      *GenesisDataLoader* *EphemeryGenesisSpec *EphemeryBlobScheduleVectorsSpec *EestBlockchainReplay* *ForkId*"`.
      Expect PASS.
- [ ] T039 Run the grep gates; each must find nothing:
      - `grep -rnE "Clock\.systemUTC|Instant\.now|currentTimeMillis|LocalDateTime\.now" ephemery tests`;
      - `grep -rn "utils.Config\b" src/main/scala/com/chipprbots/ethereum/ephemery/`.
- [ ] T040 Manual check of the script. Run `sbt stage`, then
      `FUKUII_BIN=target/universal/stage/bin/fukuii scripts/ephemery/fetch-genesis.sh --from-dir
      src/test/resources/ephemery/releases/ephemery-165 --out /tmp/eph-s2`. `diff -u` against the fixtures must be
      clean.
- [ ] T041 Run `sbt formatAll`, read `git diff`, then run `sbt formatCheck`. Expect clean.
- [ ] T042 Exit S2:
      1. `beacon` reviews the encoder, renderer and extraction.
      2. `forge` reviews the shared genesis path.
      3. `prism`, then `eye`.
      4. Run `sbt pp` alone.
      5. Open the PR `feat(ephemery): one release-rule engine in Scala; fetch-genesis.sh delegates (spec 028 S2)` with
         `Backlog: B-232`.

**Checkpoint**: one rule engine exists, and the script and the node can no longer drift.

---

## Phase 4: Slice S3, bundled iteration, selection, and exit at the reset (US1 P1, US5 P3, US4). B-233. Needs S2.

**Goal**: `fukuii ephemery` starts on the bundled or cached iteration with no setup, in a per-iteration datadir. A
running node exits 75 at its reset. An operator-prepared directory keeps working (FR-001 to FR-003, FR-008, FR-009,
FR-015, FR-016, FR-023).

**Independent test**: the resolver and watchdog specs and `EphemeryLaunchItSpec` pass. A clean `fukuii ephemery` on
dev-node follows 165.

- [ ] T043 After S2 merges, make a full clone at `~/work/e028-s3` on branch `feat/028-s3-bundle`, and claim B-233.

### Tests for S3 (write first, see them fail)

- [ ] T044 [P] [US1] Write `ephemery tests/BundledEphemerySpec.scala`. The bundle's `release.json` `genesisHash`
      equals the hash the library computes, and equals the raw-165 render (FR-002). Its fields obey
      contracts/release-json.md.
- [ ] T045 [P] [US1] Write `ephemery tests/ReleaseStoreSpec.scala`. It checks:
      - a stage is placed with an atomic rename;
      - the same N with the same hash: the stage is discarded;
      - a different hash: `Republished`;
      - `testnet/` added to an existing entry;
      - a bad or missing `release.json`: the entry is invisible and logged;
      - `.stage-*` dirs older than 1 h are swept;
      - nothing under `releases/` or any datadir is ever deleted;
      - `static-nodes.json` is copied from the root into a new iteration datadir, and never overwritten.
- [ ] T046 [P] [US1] [US4] Write `ephemery tests/EphemeryResolverSpec.scala`, the no-fetch cases, with a fixed `Clock`
      only:
      - network ≠ ephemery: nothing happens;
      - an operator `custom-chains-dir` holding `ephemery-chain.conf`: the resolver stands down, with a WARN when it
        has ended;
      - a `custom-chains-dir` without one: overridden and logged;
      - the bundle current: `Managed(165)`, placed;
      - a newer current cached entry wins;
      - everything ended: `Ended`, exit 78, the message naming `fetch-genesis.sh`;
      - an old pre-028 `<root>/rocksdb`: logged, untouched;
      - the startup report line (contracts/exit-codes.md).
- [ ] T047 [P] [US1] Write `ephemery tests/EphemeryPropertyHandoverSpec.scala`.
      - Apply the resolver's property map and call `ConfigFactory.invalidateCaches()`. Using a fresh
        `ConfigFactory.load()`, never the `Config` object, check that:
        - `fukuii.datadir` is `<root>/ephemery-165`;
        - `db.rocksdb.path`, `node-key-file` and `keystore.keystore-dir` follow it;
        - `logging.logs-dir` stays `<root>/logs`;
        - `custom-chains-dir` points at the entry.
      - Clear the properties in `afterEach`.
- [ ] T048 [P] [US5] Write `ephemery tests/EphemeryResetWatchdogSpec.scala`, with a fixed or offset clock, a manual
      scheduler and a captured exit:
      - it fires at `resetTime` with the WARN text of contracts/exit-codes.md and calls `exit(75)`;
      - `exit-at-reset = false` only warns;
      - it is cancelled on shutdown;
      - on the operator path it is `WarnOnly`.
- [ ] T049 [US1] [US5] Write `it/ephemery/EphemeryForkedHarness.scala`, a test-scope main. It builds `StartupDeps`
      with an offset clock (the anchor comes in as an argument), a fake transport and the real exit, and calls
      `App.run`.
- [ ] T050 [US1] [US5] Write `it/ephemery/EphemeryLaunchItSpec.scala`, tagged `IntegrationTest`. It forks the harness
      as `it/sync/HealCrashSafetyItSpec.scala:228-244` does (`java.home`, `java.class.path`, `ProcessBuilder`), with
      an empty temporary root. It checks:
      - (a) the node starts on the bundle in `<root>/ephemery-165` (the log line);
      - (b) with the clock offset to `resetTime − 5 s`, the process exits 75, and RocksDB in `<root>/ephemery-165`
        then reopens cleanly.
- [ ] T051 [P] [US1] Edit `src/test/scala/com/chipprbots/ethereum/blockchain/data/EphemeryGenesisSpec.scala`.
      - `:83-91`: there is no default `custom-chains-dir`, and the `fukuii.ephemery` defaults are present.
      - `:101-106`: the not-configured error is unreachable for ephemery. Another unknown network gets the FR-023
        message (contracts/exit-codes.md).

### Implementation for S3

- [ ] T052 [US1] Create the bundle `src/main/resources/ephemery/bundled/ephemery-165/{ephemery-chain.conf,
      ephemery-genesis.json,release.json}` with the S2 CLI from the raw 165 inputs.
- [ ] T053 [P] [US1] Write `scripts/ephemery/update-bundled.sh` as contracts/cli.md describes: `sbt stage`, then
      `FUKUII_BIN=… fetch-genesis.sh --release`, replace the bundle, copy the raw inputs, print the files to add.
- [ ] T054 [US1] Write `ephemery/ReleaseStore.scala` (data-model.md "Ephemery root and its entries"): place, load and
      re-validate, sweep, list old datadirs with their sizes, seed `static-nodes.json`.
- [ ] T055 [US1] In `src/main/scala/com/chipprbots/ethereum/App.scala`, `main(args)` becomes
      `run(args, StartupDeps.production)`. `StartupDeps` carries a `Clock` (UTC), a `ReleaseTransport` (a stub
      that refuses, until S4b) and `exit: Int => Unit`. Put `StartupDeps` in `ephemery/StartupDeps.scala`.
- [ ] T056 [US1] [US4] Write `ephemery/EphemeryResolver.scala` with rows 1, 2, 3 and 5 of data-model.md's decision
      table; there is no fetch in S3.
      - `App.run` calls it after `setNetworkConfig("ephemery")` and before anything touches `utils.Config`.
      - It sets the properties of contracts/config.md, calls `invalidateCaches()`, updates `<root>/current`, and logs
        the startup report and the old-datadirs line.
- [ ] T057 [US5] Write `ephemery/EphemeryResetWatchdog.scala`. `src/main/scala/com/chipprbots/ethereum/Fukuii.scala`
      starts it after the node starts, when `fukuii.ephemery.active.managed` is set, and it exits through
      `StartupDeps.exit`.
- [ ] T058 [US1] Edit `src/main/resources/conf/ephemery.conf` as contracts/config.md describes: remove the default
      `custom-chains-dir`, add the `fukuii.ephemery` defaults, rewrite the header comment. Add the bounds check for
      `refresh-timeout` to `ConfigValidator`.
- [ ] T059 [US1] Change the not-configured message at `src/main/scala/com/chipprbots/ethereum/utils/Config.scala:349-358`
      to name the resolved directory and whether it exists (FR-023; this closes B-225). Update the ephemery help line
      in `App.scala` (contracts/cli.md).
- [ ] T060 [P] [US1] Write the interim docs:
      - in `docs/runbooks/ephemery.md`, a "Quick start: built in" section, exit 75 with a supervisor, and a pointer to
        the table of paths that move;
      - in `CHANGELOG.md`, the behaviour change (the default `custom-chains-dir` is removed; R5).

### Gate and exit for S3

- [ ] T061 Run `sbt compile-all`, then `sbt "testOnly com.chipprbots.ethereum.ephemery.* *EphemeryGenesisSpec
      *LauncherIntegrationSpec"`, then `sbt "IntegrationTest / testOnly *EphemeryLaunchItSpec"`. Expect PASS. Then the
      T039 grep gates.
- [ ] T062 Run `sbt formatAll`, read `git diff`, then run `sbt formatCheck`. Expect clean.
- [ ] T063 [US1] Manual on dev-node, with no sbt running and no `FUKUII_CONF_DIR`. Run `fukuii ephemery
      -Dfukuii.datadir=/chipprbots/blockchain/ephemery/builtin`, a new path; never `frozen-H-data`. Use a JWT at an
      absolute path, and `eb-lighthouse` on the 165 testnet dir. Check:
      - the startup report says `from bundled`;
      - the node follows the head for 1 h.
- [ ] T064 Exit S3:
      1. `prism` reviews the resolver, store and watchdog.
      2. `beacon` reviews the bundle's chain config and hash pin.
      3. `eye`.
      4. Run `sbt pp` alone.
      5. Open the PR `feat(ephemery): bundled iteration, startup selection and exit at the reset (spec 028 S3)` with
         `Backlog: B-233`.
      6. When it merges, close B-225 as superseded.

**Checkpoint**: Ephemery works with no setup until 2026-11-19. After that the node exits 75 and points at the manual
refresh. This is the fallback if S4b misses the 166 reset.

---

## Phase 5: Slice S4a, strict transport and tar reader (foundational for US2). B-234. Starts at once.

**Goal**: an HTTPS client and a tar reader that enforce every rule of R8 and R9. They are pure, tested in isolation,
security-reviewed, and not yet wired into startup (FR-011, FR-012).

**Independent test**: `JdkHttpsTransportSpec` and `StrictTarReaderSpec` pass.

- [ ] T065 Make a full clone at `~/work/e028-s4a` on branch `feat/028-s4a-transport`, and claim B-234.

### Tests for S4a (write first, see them fail)

- [ ] T066 [P] Write `ephemery tests/StrictTarReaderSpec.scala`. Archives are built in memory. Each of these is
      refused with its own message:
      - an absolute name, `..`, `a/../../x`, a backslash or NUL in a name, a name over 255 bytes, more than 8 levels;
      - a duplicate name;
      - a symlink, hardlink, char or block device, fifo, sparse entry, pax `x` or `g`, or GNU `K`;
      - more than 256 entries, an entry over 64 MiB, more than 128 MiB in total, a compressed size over 32 MiB, a
        ratio over 50;
      - a truncated archive, or a bad header checksum.

      It accepts:
      - GNU `L` long names up to 1 KiB;
      - both magics, POSIX and GNU;
      - a synthetic archive with the 29 names and types of the real 165 listing (research R9).

      Mode 0755 on a file is written as 0644. Every write is `CREATE_NEW` inside the stage.
- [ ] T067 [P] Write `ephemery tests/JdkHttpsTransportSpec.scala`. It runs a local `com.sun.net.httpserver.HttpsServer`
      with a self-signed certificate generated in the test (BouncyCastle is on the classpath), and passes in its
      `SSLContext` and allow-list. It checks that the transport:
      - refuses an `http:` start URL, a hop to http, a hop to a host off the allow-list under the default-source
        policy (and accepts it under the mirror policy), and a 6th hop;
      - stops at the body cap;
      - honours the body deadline, with a server stalled on a latch;
      - maps `HTTPS_PROXY`/`NO_PROXY` into a `ProxySelector` (a unit test of the parser; no real proxy);
      - refuses a proxy URL with credentials;
      - never logs a query string (captured log appender);
      - sends `User-Agent: fukuii/<version>`.

### Implementation for S4a

- [ ] T068 [P] Write `ephemery/StrictTarReader.scala` (R9): a streaming gzip and tar reader. It reads the EL members
      into memory and the CL members into a given stage directory, skipping `parsedBeaconState.json` and
      `bootnode-keys/`.
- [ ] T069 [P] Write `ephemery/ReleaseTransport.scala`: the trait, `JdkHttpsTransport`, `SourcePolicy`
      (`DefaultGithub` with the allow-list, or `Mirror`), and the environment proxy selector (R8).

### Gate and exit for S4a

- [ ] T070 Run `sbt compile-all`, then `sbt "testOnly *StrictTarReaderSpec *JdkHttpsTransportSpec"`. Expect PASS.
      Then the T039 grep gates.
- [ ] T071 Run `sbt formatAll`, read `git diff`, then run `sbt formatCheck`. Expect clean.
- [ ] T072 Exit S4a:
      1. `prism` reviews with the security lens.
      2. `conduit` gives the second, independent review of the HTTP client.
      3. `eye`.
      4. Run `sbt pp` alone.
      5. Open the PR `feat(ephemery): strict HTTPS release transport and tar reader (spec 028 S4a)` with
         `Backlog: B-234`.

      The PR text states the rules only. No security findings go on public trackers.

**Checkpoint**: the network surface exists and is reviewed, and nothing calls it yet.

---

## Phase 6: Slice S4b, the refresh (US2 P1, US4 P2). B-235. Needs S3 and S4a.

**Goal**: after a reset, a restarted node fetches, validates and starts the new iteration, and writes its CL directory.
Operators control the source and can turn fetching off. The script becomes an exec wrapper (FR-004, FR-005, FR-010 to
FR-014, FR-017).

**Independent test**: the refresh cases of `ReleaseFetcherSpec`, `EphemeryResolverSpec` and `EphemeryLaunchItSpec`
pass against the fake transport.

- [ ] T073 After S3 and S4a merge, make a full clone at `~/work/e028-s4b` on branch `feat/028-s4b-refresh`, and claim
      B-235.

### Tests for S4b (write first, see them fail)

- [ ] T074 [P] [US2] Write `ephemery tests/SyntheticRelease.scala`, a test helper. `next(raw)` shifts the timestamp and
      reset by one interval, adds 1 to the iteration and chain id, computes the published hash with the library, and
      can pack a GNU-tar `testnet-all.tar.gz` (R16).
- [ ] T075 [P] [US2] Write `ephemery tests/ReleaseFetcherSpec.scala`, with the fake transport, a manual clock and a
      manual scheduler. It covers:
      - target K, including several iterations behind;
      - the asset URL form;
      - a 404 retried within the budget, then `NotYetPublished` (69);
      - connect errors and 5xx, with the backoff, inside the budget;
      - a window mismatch: the API's `latest` once, then accept or 69;
      - the size cap;
      - `Republished` (78);
      - the EL members in memory, and `testnet/` without the excluded members.
- [ ] T076 [P] [US2] [US4] Add the fetch cases to `ephemery tests/EphemeryResolverSpec.scala`:
      - ended, then fetched: `Managed(166)` with `testnet/`;
      - `refresh = false`: `Ended` (78);
      - `source = <dir>`: no network, then `Managed`, `Ended` or `BadSource`;
      - an `http:` source: `BadSource` (78);
      - an https mirror;
      - not published, or unreachable: 69;
      - **zero transport calls** while the iteration is current (SC-003).
- [ ] T077 [US2] Add the refresh case to `it/ephemery/EphemeryLaunchItSpec.scala`: the clock past the 165 reset, and
      the fake transport serving `SyntheticRelease.next(165)`. The node starts in `<root>/ephemery-166`, and
      `<root>/ephemery-165` is untouched.
- [ ] T078 [P] [US4] Extend `ephemery tests/EphemeryFetchCliSpec.scala`:
      - `--release` through the fake transport (the CLI takes `StartupDeps`);
      - without `--out`, it writes into the node's root, including `testnet/`;
      - `refresh = false` does not block an explicit fetch.

### Implementation for S4b

- [ ] T079 [US2] Write `ephemery/ReleaseFetcher.scala` (R7): target K, the asset URL, the single API fallback, the
      60 s budget from `fukuii.ephemery.refresh-timeout`, and a call to `StrictTarReader` and then
      `EphemeryRelease.validate`.
- [ ] T080 [US2] [US4] Add the fetch path to `ephemery/EphemeryResolver.scala`: rows 4 and 6 of data-model.md, exits
      69 and 78, `fukuii.ephemery.refresh` and `source`, `testnet/` placed into the entry, and the CL path on the
      startup line. `StartupDeps.production` now carries the real `JdkHttpsTransport`.
- [ ] T081 [US4] Add the S4b modes to `ephemery/EphemeryFetchCli.scala`: `--release` downloads, and with no `--out`
      it writes into the node's root, adding `testnet/` to an existing entry with an atomic rename.
- [ ] T082 [US4] Reduce `scripts/ephemery/fetch-genesis.sh` to argument defaults (`--out`) plus
      `exec "${FUKUII_BIN:-fukuii}" cli ephemery-fetch "$@"`.
- [ ] T083 [P] [US2] Rewrite `docs/runbooks/ephemery.md` to cover:
      - the quick start;
      - systemd (`Restart=on-failure`, `RestartSec=120`, `RestartPreventExitStatus=78`);
      - Docker: no retry cap, and watch for 78;
      - the CL across a reset: a companion unit with `PartOf=`, `--testnet-dir <root>/current/testnet`, and a CL
        datadir per iteration keyed by `readlink <root>/current`;
      - the table of paths that move (contracts/config.md);
      - pinning with the script;
      - offline use and opting out;
      - proxies.

      Also update `CHANGELOG.md`, and the Ephemery lines of `README.md`, `docs/index.md` and the skills that list
      networks.

### Gate and exit for S4b

- [ ] T084 Run `sbt compile-all`, then `sbt "testOnly com.chipprbots.ethereum.ephemery.* *LauncherIntegrationSpec"`,
      then `sbt "IntegrationTest / testOnly *EphemeryLaunchItSpec"`. Expect PASS. Then the T039 grep gates.
- [ ] T085 Run `sbt formatAll`, read `git diff`, then run `sbt formatCheck`. Expect clean.
- [ ] T086 [US4] Manual, with network, from an empty scratch `HOME`: `fukuii cli ephemery-fetch --release
      ephemery-165`. Check that `releases/ephemery-165/testnet/config.yaml` is present and that there is no
      `parsedBeaconState.json`. Also check that `scripts/ephemery/fetch-genesis.sh --out /tmp/eph-s4b` writes the old
      layout.
- [ ] T087 Exit S4b:
      1. `prism`.
      2. `conduit` reviews the fetch wiring.
      3. `beacon` reviews the validation call site.
      4. `eye`.
      5. Run `sbt pp` alone.
      6. Open the PR `feat(ephemery): refresh after a reset, CL testnet dir, fetch-genesis.sh wrapper (spec 028 S4b)`
         with `Backlog: B-235`.

**Checkpoint**: `fukuii ephemery` survives resets under a supervisor.

---

## Phase 7: Slice S5, live validation and closure. B-236. Needs S1 to S4b merged.

**Purpose**: SC-005, SC-006, SC-007 and SC-008 on real networks, then close the spec.

- [ ] T088 Prepare dev-node. **Ask the user before installing or starting units.** Check host load first: no sbt while
      the run lasts, and no Mordor node alongside.
      - A `fukuii-ephemery.service` unit: built in, root `/chipprbots/blockchain/ephemery/builtin`, a JWT at an
        absolute path, and the runbook's restart settings.
      - An `eb-lighthouse` companion unit, `PartOf=` the first, with `--testnet-dir <root>/current/testnet` and a CL
        datadir per iteration.
- [ ] T089 [US1] SC-007: follow the live iteration for 24 h. Record the head lag and the peer count.
- [ ] T090 [US2] [US5] SC-008, across 165→166 (2026-11-19 19:00 UTC). Record:
      - the WARN line, and exit 75 at 19:00:00;
      - any restarts with exit 69;
      - the time from 166's `published_at` to the node on `ephemery-166`, which must be under 10 min;
      - the CL following the new chain.

      **Fallback** if S4b is not installed by 2026-11-18: the S3 build plus a manual refresh with the script, the
      controllable-clock IT, and the live check moved to 167 (2026-12-17 19:00 UTC).
- [ ] T091 [US3] SC-005 on the release candidate: Hoodi peers and the start of sync, as in T018.
- [ ] T092 SC-006: run the full `./scripts/fukuii-test essential` once. ETC, Mordor and Gorgoroth pass unchanged.
- [ ] T093 After 166 is published, bump the bundle to it with `scripts/ephemery/update-bundled.sh ephemery-166` and
      open a PR. This is optional under FR-003; do it if a release is due.
- [ ] T094 Closure:
      - set spec.md `**Status**` per `docs/development/spec-lifecycle.md` (Shipped or Validated), and remove
        `**In flight**`;
      - regenerate the index;
      - mark B-231 to B-236 and B-227 done;
      - add follow-ups to the backlog: spec 024's load-time `genesis-hash` check, a bundle bump at each release cut,
        and anything found in S5.

---

## Dependencies and execution order

```text
Setup (T001-T003)
  ├── S1 Hoodi (T004-T019) ─────────────────────────────────────────────┐
  ├── S2 release library (T020-T042) ──▶ S3 bundle + selection (T043-T064) ──┐
  └── S4a transport + tar (T065-T072) ──────────────────────────────────────┴──▶ S4b refresh (T073-T087) ──▶ S5 (T088-T094)
```

| Story | Delivered by | Needs |
|-------|--------------|-------|
| US3 (Hoodi) | S1 | nothing |
| US1 (Ephemery, no setup) | S3 | S2 |
| US5 (exit at the reset) | S3 | S2 |
| US4 (operator control) | partly in S3 (operator dir, `refresh` key); fully in S4b (`source`, CLI modes) | S2, S3, S4a |
| US2 (refresh) | S4b | S3, S4a |

Within a slice, the order is: tests, then pure types, then I/O, then wiring, then docs, then the gate, then the exit.

## Parallel opportunities

- **Three slices at once.** S1, S2 and S4a share no files, and can be written by three implementers at the same time.
  Their sbt runs still queue on the lock.
- **Within S1.** T006, T007, T008 and T009 are different test files. T015 (docs) runs alongside T010 to T014.
- **Within S2.** T022 to T025 (tests), and T028, T029 and T033 (independent types).
- **Within S3.** T044 to T048 (unit tests); T053 (script) and T060 (docs).
- **Within S4a.** T066 and T067; T068 and T069.
- **Within S4b.** T074, T075, T076 and T078; T083 (docs).

Example, S2 tests together:

```text
Task: "EphemeryGoldenRenderSpec in src/test/scala/com/chipprbots/ethereum/ephemery/"
Task: "EphemeryReleaseSpec in src/test/scala/com/chipprbots/ethereum/ephemery/"
Task: "GenesisHeaderExtractionSpec in src/test/scala/com/chipprbots/ethereum/blockchain/data/"
Task: "EphemeryFetchCliSpec in src/test/scala/com/chipprbots/ethereum/ephemery/"
```

## Implementation strategy

- **MVP: US1**, which is S2 and then S3. After S3, `fukuii ephemery` works on a clean machine until 2026-11-19, exits
  75 at the reset (US5), and respects operator directories. It is the fallback if the refresh misses the 166 reset.
- **Quick win in parallel: US3**, which is S1. It is small, static, and can merge first.
- **Then US2 and the rest of US4**, which is S4a and then S4b. S4a is reviewed on its own, so the security review is
  not mixed with the wiring.
- **Each slice merges alone**, with a green CI and a merge pinned to the head commit. Merging is the user's unless
  they grant it.
- **Live checks and closure, S5.** The date-bound item is the 166 reset, 2026-11-19 19:00 UTC.
