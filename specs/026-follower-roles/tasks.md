---
description: "Task list for spec 026: follower roles replica and archive"
---

# Tasks: Follower Roles — `replica` and `archive`

**Input**: [spec.md](spec.md), [plan.md](plan.md), [research.md](research.md), [data-model.md](data-model.md),
[contracts/state-replay.md](contracts/state-replay.md), [contracts/follower-rpc.md](contracts/follower-rpc.md),
[quickstart.md](quickstart.md)

**Prerequisites**: plan.md "Delivery slices" names what each slice is blocked on. Tasks tagged **[BLOCKED: …]** do not
start before the named cross-spec item lands.

## How this list is organised

Phases follow plan.md's slices: Setup, Foundational (S1 `StateReplay` v1, S2 queue and source, S3 `SyncMode.Follow`
and gating), then one phase per user story, then Polish. Each slice is one PR to staging. Tests are listed before the
code they pin and MUST fail before the code is written (constitution III). No `Thread.sleep`; time is `TestControl`.

| Story | Meaning |
|-------|---------|
| US1 | A `replica` bootstraps once and follows (P1) — SC-001, SC-002 |
| US2 | Historical state is served by the `archive` role (P1) — SC-003; the `replica` window error |
| US3 | A deep ETC reorg is survived or reported (P1) — SC-004 |
| US4 | A simulation pool reads a follower (P2) — 023 SC-002 |

**Reviewer tags**: **[forge]** ETC/Hash consensus adjacency, **[beacon]** ETH/Path, **[vault]** storage write order and
atomicity, **[flow]** streams and queues, **[conduit]** JSON-RPC, **[herald]** what a follower does not bind,
**[eye]** validation runs. A slice PR merges only after its tagged reviewers sign off; `forge` and `beacon` give their
impact analysis **before** the S1 PR opens (plan.md "Specialist routing").

**Builds**: `sbt compile-all` after every file; `./scripts/fukuii-test only "*<Spec>*"` after each logic task;
`testEssential` once per slice before the PR; `sbt scalafmtAll` before every commit; `sbt pp` before the PR. Code PRs
touching `StdNode`, `NodeBuilder` or `SyncController` are scheduled around 020 PR 5 (plan.md Technical Context).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependencies)
- **[Story]**: US1–US4, or none for shared work
- Every description names its exact file path

---

## Phase 1: Setup (shared infrastructure)

**Purpose**: packages, config, ADR skeleton, test generators. No behaviour.

- [ ] T001 Create packages `src/main/scala/com/chipprbots/ethereum/domain/statediff/`, `src/main/scala/com/chipprbots/ethereum/db/replay/`, `src/main/scala/com/chipprbots/ethereum/blockchain/follow/` with `package.scala` files carrying the package doc (owner, spec link)
- [ ] T002 [P] Add `src/main/resources/conf/base/follow.conf` with every `fukuii.follow.*` key and default from data-model.md §6, included from `src/main/resources/conf/base/fukuii.conf`
- [ ] T003 [P] Add `FollowRecordNamespace` (`'y'`) to `src/main/scala/com/chipprbots/ethereum/db/storage/Namespaces.scala` (end of `nsSeq`, comment as `BlockAccessListNamespace`) and extend `src/test/scala/com/chipprbots/ethereum/db/storage/NodeStorageSuite.scala`'s namespace-uniqueness check if present, else add `NamespacesSpec.scala`
- [ ] T004 [P] Write ADR skeleton `docs/adr/infrastructure/INF-010-state-replay-capability.md` (context: 018 row 7, StateIngest INF-006; decision: R-1/R-2; consequences) and link it from `docs/adr/infrastructure/README.md`
- [ ] T005 [P] Add `src/test/scala/com/chipprbots/ethereum/domain/statediff/StateDiffGenerators.scala`: ScalaCheck generators for `AccountKey`, `SlotKey`, `Change[Account]`, `Change[BigInt]`, valid `StateDiff` over a generated pre-state (uses `ObjectGenerators.addressGen`, `bigIntGen`)

**Checkpoint**: `sbt compile-all` green; no behaviour changed.

---

## Phase 2: Foundational (blocking prerequisites)

**Purpose**: `StateReplay` v1 (S1), the ordered queue and file source (S2), `SyncMode.Follow` and role gating (S3).
No user story work starts before this phase is complete.

### S1 — `StateDiff` + `StateReplay` v1 [vault] [forge] [beacon]

- [ ] T006 [P] Test `src/test/scala/com/chipprbots/ethereum/domain/statediff/StateDiffSpec.scala`: `validate` rejects unsorted keys, duplicate keys, slot without owner, account with code hash but no code; `inverse.inverse == identity`; property over `StateDiffGenerators`
- [ ] T007 [P] Test `src/test/scala/com/chipprbots/ethereum/db/replay/HashedKeyTriesSpec.scala` (contract L3): for generated `(Address, Account)` and `(slot, value)` lists, `MerklePatriciaTrie[ByteString, _]` with the identity encoder over `kec256(key)` yields the same root and node set as `MerklePatriciaTrie[Address, Account]` / `EthereumUInt256Mpt.storageMpt` over raw keys, on `EphemDataSource`
- [ ] T008 [P] Test `src/test/scala/com/chipprbots/ethereum/db/replay/StateReplayImportEquivalenceSpec.scala` (contract L2): same writes through `InMemoryWorldStateProxy.saveAccount/saveStorage/saveCode` + `persistState` and through `StateReplay.v1.applyForward` give the same root, both schemes' `StateStorage` modes (`ArchivePruning`, `BasicPruning(64)`)
- [ ] T009 [P] Test `src/test/scala/com/chipprbots/ethereum/db/replay/StateReplayRoundTripSpec.scala` (contract L1): `applyForward` then `applyBackward` leaves CF `n` byte-identical including `RefCountInspector.refCounts`, flat stores identical, code a superset; property over `StateDiffGenerators`; under `BasicPruning` and `ArchivePruning`
- [ ] T010 [P] Test `src/test/scala/com/chipprbots/ethereum/db/replay/StateReplayAtomicitySpec.scala` (contract L5): a `DataSource` wrapper that throws on the N-th `update` leaves the store byte-identical to the pre-state for every N; `RootMismatch` and `ReceiptsRootMismatch` commit nothing (flat, code, `extra` included)
- [ ] T011 [P] Test `src/test/scala/com/chipprbots/ethereum/db/replay/FollowRecordStorageSpec.scala`: put/get by `(number, hash)`, key ordering (8-byte BE), window range delete keeps `number ≥ low`, iterator closed (`withResources`)
- [ ] T012 Implement `src/main/scala/com/chipprbots/ethereum/domain/statediff/StateDiff.scala` and `FollowRecord.scala` per data-model.md §1 (opaque `AccountKey`/`SlotKey`, `Change`, `StateDiff.validate`, `inverse`)
- [ ] T013 Implement `src/main/scala/com/chipprbots/ethereum/db/replay/HashedKeyTries.scala`: `accountTrie(root, storage)` and `storageTrie(root, storage)` constructors with an identity `ByteArrayEncoder[ByteString]`, `Account.accountSerializer`, `EthereumUInt256Mpt.rlpBigIntSerializer`; no `mpt/` change
- [ ] T014 Implement `src/main/scala/com/chipprbots/ethereum/db/replay/StateReplay.scala` (trait, `Root`, `ReplayError`, `v1` factory) per data-model.md §2 and contracts/state-replay.md
- [ ] T015 Implement `src/main/scala/com/chipprbots/ethereum/db/replay/StateReplayV1.scala`: `stateStorage.stageBlock(bn)`; code puts; per-account storage trie put/remove and `next.storageRoot` check (`InvalidDiff`); account trie put/remove; `getRootHash` compare; receipts root via `MptListValidator.isValid[Receipt]`; flat-store batch; `commitWith(bn, hash, extra)` or `discard()`; `applyBackward` over `diff.inverse` tagged with the retracted block number; `dryRunRoot`; refuses construction when `eth-compatible-storage = false`
- [ ] T016 Implement `src/main/scala/com/chipprbots/ethereum/db/replay/FollowRecordStorage.scala` over CF `y` (`TransactionalKeyValueStorage`), `deleteBelow(low)` as a range delete per `storage-rocksdb.md`
- [ ] T017 Add `follow.*` accessors to `src/main/scala/com/chipprbots/ethereum/db/storage/AppStateStorage.scala` per data-model.md §3 (`putFollowHead`, `getFollowHead`, `putFollowHalt` (synced), `getFollowHalt`, `putFollowWindowLow`, `putFollowSourcePosition`, `putFollowSeed`, `getFollowRole`) and extend `src/test/scala/com/chipprbots/ethereum/db/storage/AppStateStorageSpec.scala`
- [ ] T018 Add `StateReplay` metrics (`fukuii_state_replay_*`) and DEBUG logging per contracts/state-replay.md §6 in `StateReplayV1.scala`, following `.claude/agent-protocols/logging-standards.md`
- [ ] T019 Finish `docs/adr/infrastructure/INF-010-state-replay-capability.md` with the measured L1–L5 outcomes and the `forge`/`beacon` impact-analysis summary
- [ ] T020 [eye] Run `./scripts/fukuii-test only "*StateReplay* *HashedKeyTries* *StateDiff* *FollowRecordStorage*"` then `testEssential`; record `VERIFY:` lines in the S1 PR

### S2 — `RecordSource` seam, file source, ordered queue [flow]

- [ ] T021 [P] Test `src/test/scala/com/chipprbots/ethereum/blockchain/follow/OrderedRecordQueueSpec.scala`: in-order apply; out-of-order hold then release; duplicate (same number and hash) dropped and counted; same number different hash without retraction → `UnexpectedFork`; retraction of head applied, lower retraction held until higher ones; retraction below `windowLow` → `ReseedRequired`; retraction at/below finalized → `FinalizedRetraction`; `isFull` at record and byte bounds; `gapSince` and `PersistentGap` after `gapTimeout` under a manual clock; property: any permutation of a valid sequence yields the same applied order
- [ ] T022 [P] Test `src/test/scala/com/chipprbots/ethereum/blockchain/follow/FileRecordSourceSpec.scala`: reads a 025 file-sink directory in order, resumes from a `Position`, yields raw bytes plus position, stops cleanly at EOF and continues when a new file appears (polling with `TestControl`), fails loudly on a truncated file
- [ ] T023 Implement `src/main/scala/com/chipprbots/ethereum/blockchain/follow/RecordSource.scala` (trait: `records(from: Position): Stream[IO, (Position, ByteString)]`, `Position` codec for `follow.sourcePosition`) and `FileRecordSource` over the 025 T-003 file format
- [ ] T024 Implement `src/main/scala/com/chipprbots/ethereum/blockchain/follow/OrderedRecordQueue.scala` and `FollowFault.scala` per data-model.md §4–§5 (pure; no IO)
- [ ] T025 [eye] Run `./scripts/fukuii-test only "*OrderedRecordQueue* *FileRecordSource*"`; `flow` review of the queue stage before the PR

### S3 — `SyncMode.Follow`, role gating, wiring [herald] [eye] [BLOCKED: 023 T-003/T-004; 017 row 3 or marker]

- [ ] T026 [P] Test `src/test/scala/com/chipprbots/ethereum/nodebuilder/FollowerRoleGatingSpec.scala`: for `role = replica` and `archive`, `StdNode.start()` does not construct or start `ServerActor`, `PeerDiscoveryManager`, `PeerManagerActor.StartConnectingCmd`, `SyncController`, mining, or the Engine API server; constructs `FollowController`; for `ingest` behaviour is unchanged (existing `AppLauncherIntegrationSpec` fixtures); `StateReplay` is constructed only for follower roles
- [ ] T027 [P] Test `src/test/scala/com/chipprbots/ethereum/blockchain/follow/FollowConfigSpec.scala`: `fukuii.follow.*` parsing and defaults; window default from the ChainSpec `replica.retention-window-blocks` (6,650 / 7,200) and override; `pruning.history = window + lag-bound` derived on `replica`, `archive` mode on `archive`; a follower profile with `sync.do-snap-sync = true` or a P2P port is rejected
- [ ] T028 Add `Follow` to `SyncController.SyncMode` and `selectSyncMode(role, config)` in `src/main/scala/com/chipprbots/ethereum/blockchain/sync/SyncController.scala` (`:2577-2585`); marker comment `// 017 FR-007 amendment row 3` if 017's plan has not adopted it; `SyncControllerSpec` case for `Follow`
- [ ] T029 Implement `src/main/scala/com/chipprbots/ethereum/blockchain/follow/FollowConfig.scala` per data-model.md §6
- [ ] T030 Branch `StdNode.start()` in `src/main/scala/com/chipprbots/ethereum/nodebuilder/StdNode.scala` on `InstanceConfig.role`: follower roles start metrics, JSON-RPC servers (HTTP/WS/IPC), the 023 admin surface and `FollowController`; nothing else
- [ ] T031 Wire `StateReplay.v1`, `FollowRecordStorage`, `RecordSource` (by `fukuii.follow.source.type`) and `FollowController` in `src/main/scala/com/chipprbots/ethereum/nodebuilder/NodeBuilder.scala` for follower roles only
- [ ] T032 [eye] Run `FollowerRoleGatingSpec`, `FollowConfigSpec`, `SyncControllerSpec`; `herald` confirms no listener is built; `testEssential` before the PR

**Checkpoint**: a follower-role process starts, binds only RPC and admin ports, holds a `StateReplay`, and does nothing
else. User story work can begin.

---

## Phase 3: User Story 1 — a `replica` bootstraps once and follows (Priority: P1) 🎯 MVP

**Goal**: seed → apply records N+1..head through `StateReplay` → verify every root → ready; a corrupted record halts
within the block (SC-001, SC-002).

**Independent Test**: quickstart.md §1 and §1b on the Mordor-class devnet with the file sink and the genesis seed.

### Tests for User Story 1

- [ ] T033 [P] [US1] Test `src/test/scala/com/chipprbots/ethereum/blockchain/follow/BlockApplierSpec.scala`: a `BlockRecord` becomes one batch (header, number→hash, receipts, record copy, `BestBlockInfo`, `follow.appliedHead`, `follow.lastVerifiedRoot`, `follow.windowLow`, `follow.sourcePosition`) committed through `StateReplay.applyForward`; a `RootMismatch` commits nothing and yields `FollowFault.RootMismatch`; `ReceiptsRootMismatch` likewise; `ChainKeyMismatch` on a foreign `chainKey`; `UnknownSchemaVersion` on a bad version byte; a `Finality` record updates `follow.finalized` only
- [ ] T034 [P] [US1] Test `src/test/scala/com/chipprbots/ethereum/blockchain/follow/FollowControllerSpec.scala` (fs2 + `TestControl`): applies a generated chain from a seed in order; out-of-order delivery is reordered; duplicates are no-ops; `ready` flips true only when lag ≤ bound and the last root verified; a corrupted record halts: `follow.halt` written synced, queue stops, status `ready = false`, `fault = root-mismatch`, the bad record is kept under `follow.halt.record`; restart with `follow.halt` present starts halted; `drain`/`stop` complete; `commitSync` every `sync-every` blocks (count `updateSync` calls on a counting `DataSource`)
- [ ] T035 [P] [US1] Test `src/test/scala/com/chipprbots/ethereum/blockchain/follow/SeedImporterSpec.scala`: genesis seed stamps `follow.role`, `follow.seedBlock = 0`, `follow.appliedHead = 0`, `follow.lastVerifiedRoot = genesis.stateRoot`, SNAP done flags, in one synced batch; a datadir stamped `replica` refuses to start as `archive` or `ingest`; a seed whose root mismatches its header is refused and writes no pointer (013 §5.4 path; mocked 013 importer until S6)
- [ ] T036 [P] [US1] Test `src/test/scala/com/chipprbots/ethereum/jsonrpc/PinnedBlockResolverSpec.scala` per contracts/follower-rpc.md §8: tags, numbers, hashes, `pending` → `-39011`, above head → `-39013`, below window → `-39010` with `data.window`, halt-first → `-39012`, `windowLow` served and `windowLow − 1` refused
- [ ] T037 [P] [US1] Test `src/test/scala/com/chipprbots/ethereum/jsonrpc/PinnedBlockEchoSpec.scala`: scalar results carry `pinnedBlock`; HTTP responses carry `Fukuii-Pinned-Block`; WS carries the member only; batch elements each carry their own; `ingest` responses byte-identical to existing `JsonRpcControllerSpec` fixtures

### Implementation for User Story 1

- [ ] T038 [US1] Implement `src/main/scala/com/chipprbots/ethereum/blockchain/follow/BlockApplier.scala`: decode (025 codec), `StateDiff.validate`, build `extra` (`BlockchainWriter.storeBlockHeader`, `storeReceipts`, `FollowRecordStorage.put`, `AppStateStorage.putBestBlockInfo` + `follow.*`), call `applyForward`/`applyBackward`, window delete on `replica`
- [ ] T039 [US1] Implement `src/main/scala/com/chipprbots/ethereum/blockchain/follow/FollowController.scala`: fs2 loop `RecordSource → OrderedRecordQueue → BlockApplier`; `Ref[IO, FollowStatus]`; halt path (synced `follow.halt`, stop consuming, keep process up); `sync-every` cadence; `drain`/`stop` hooks for 023 FR-005; start-halted when `follow.halt` present
- [ ] T040 [US1] Implement `src/main/scala/com/chipprbots/ethereum/blockchain/follow/FollowStatus.scala` and the 023 FR-004 health projection (`window`, `fault`, `seedBlock`, `sourceLag`, `ready`) plus metrics `fukuii_follow_*` per research R-11 **[BLOCKED: 023 T-005 health shape]**
- [ ] T041 [US1] Implement `src/main/scala/com/chipprbots/ethereum/blockchain/follow/SeedImporter.scala` with the genesis-seed path now and the 013 v2 path behind the 013 importer interface (research R-8); role stamp; refusal path; start-time disk sizing check (`min-free-bytes-per-block × W`) for `replica` **[013 v2 path BLOCKED: 013 §11 rows 2/6/8, 013 Phases 1–2]**
- [ ] T042 [US1] Implement `src/main/scala/com/chipprbots/ethereum/jsonrpc/PinnedBlockResolver.scala` per contracts/follower-rpc.md §2 and route `EthUserService`, `EthProofService` (replace the private resolver at `EthProofService.scala:212-237`), `EthBlocksService` through it on follower roles [conduit]
- [ ] T043 [US1] Add `pinnedBlock: Option[PinnedBlock]` to `src/main/scala/com/chipprbots/ethereum/jsonrpc/JsonRpcResponse.scala`, encode it only on follower roles, and emit `Fukuii-Pinned-Block` in `src/main/scala/com/chipprbots/ethereum/jsonrpc/server/http/JsonRpcHttpServer.scala` when `fukuii.follow.rpc.pin-header` is on [conduit]
- [ ] T044 [US1] Add `-39010 OutsideRetentionWindow`, `-39012 FollowerHalted`, `-39013 BlockNotApplied` to `src/main/scala/com/chipprbots/ethereum/jsonrpc/JsonRpcError.scala` with `data` payloads per data-model.md §7; adopt 023's role error for `-39011` [conduit] **[BLOCKED: 023 T-004 final code]**
- [ ] T045 [US1] Implement `src/main/scala/com/chipprbots/ethereum/blockchain/follow/FollowInspectCli.scala` (`fukuii follow inspect-halt --datadir`) printing `follow.halt`, both roots and the kept record summary; register in `src/main/scala/com/chipprbots/ethereum/cli/CliCommands.scala`
- [ ] T046 [US1] Implement `src/main/scala/com/chipprbots/ethereum/blockchain/follow/FollowBenchCli.scala` (`fukuii follow-bench`, research R-3) with `--dry-run`, apply and `--reexecute` arms and the JSON report; register in `CliCommands.scala`
- [ ] T047 [US1] [eye] Run GT-1 on Mordor and Platåberget with the 025 file sink (quickstart.md §5); paste the report into plan.md "GT-1 result"; record FR-002's default per family and SC-001's bound **[BLOCKED: 025 T-003 file sink]**
- [ ] T048 [US1] Add `scripts/follow/corrupt-record.sh` (flip one slot value / one account balance in a file-sink record, re-sign nothing, keep the version byte) for SC-002 and quickstart.md §1b
- [ ] T049 [US1] [eye] Integration scenario `src/it/scala/com/chipprbots/ethereum/follow/ReplicaFollowsSpec.scala` (tag `IntegrationTest`): quickstart §1 and §1b against the 023 harness fixtures; asserts SC-001 (first read at the verified root) and SC-002 (halt within the bad block, no read at the bad root) **[BLOCKED: 023 T-007 harness]**

**Checkpoint**: US1 is the MVP — a `replica` on the devnet follows the file sink from genesis, serves pinned reads
inside
its window, and halts on corruption. Deliver and measure before US2–US4.

---

## Phase 4: User Story 2 — historical state is served by the `archive` role (Priority: P1)

**Goal**: `archive` answers FR-004 methods, `eth_call`, `eth_simulateV1`, `debug_trace*` at any block on bounded
executors, byte-identical to a reference archive (SC-003); a `replica` outside its window returns `-39010` (US2
scenario 2, delivered with US1's resolver and re-asserted here).

**Independent Test**: quickstart.md §2 and `scripts/follow/compare-archive.sh`.

### Tests for User Story 2

- [ ] T050 [P] [US2] Test `src/test/scala/com/chipprbots/ethereum/jsonrpc/FollowerRpcMatrixSpec.scala`: the contracts/follower-rpc.md §1 matrix, one request per method per role, asserting served or the exact error code; `replica` `eth_call` → `-39011`; `archive` `eth_call` served; `eth_getTransactionReceipt` → `-39011` while the 025 record carries no body (pinned until Cross-spec note 1 lands)
- [ ] T051 [P] [US2] Test `src/test/scala/com/chipprbots/ethereum/jsonrpc/FollowerGetLogsSpec.scala` per contracts/follower-rpc.md §4: `blockHash` form, range inside window, range straddling `windowLow` → `-39010`, `toBlock` above head → `-39013`, default range `latest..latest`, `blockHash` of a retracted block → `-39013`
- [ ] T052 [P] [US2] Test `src/test/scala/com/chipprbots/ethereum/jsonrpc/ArchiveExecutorSpec.scala`: `eth_call` at a historical block runs on the FR-027 executor; a full queue returns `-39014 executor saturated`; the executor shares no pool with the follow loop **[BLOCKED: 018 Track R slice R1]**

### Implementation for User Story 2

- [ ] T053 [US2] Implement `src/main/scala/com/chipprbots/ethereum/jsonrpc/FollowerMethodGate.scala` (per-role method matrix on top of 023's role error) and install it in `src/main/scala/com/chipprbots/ethereum/jsonrpc/JsonRpcController.scala` for follower roles [conduit]
- [ ] T054 [US2] Route `EthFilterService.getLogs` (`src/main/scala/com/chipprbots/ethereum/jsonrpc/EthFilterService.scala:104-146`) through `PinnedBlockResolver` on follower roles: both edges resolved and window-checked, explicit numbers to `FilterManager`, no clamping [conduit]
- [ ] T055 [US2] [eye] GT-2 on Mordor (research R-9): basis A (018 changesets-only) vs B (`ArchivePruning` on today's layout); disk per 1M blocks, `eth_getBalance`/`eth_call` p50/p99 at 1/10k/100k/1M behind head; record OQ-1 in spec.md and plan.md "GT-2 result" **[BLOCKED: 018 F2 for basis A; basis B runs now]**
- [ ] T056 [US2] Wire `archive` serving of `eth_call`, `eth_estimateGas`, `eth_simulateV1`, `debug_trace*` at historical roots on 018 FR-027 executors in `src/main/scala/com/chipprbots/ethereum/jsonrpc/{EthInfoService,EthSimulateService,DebugTracingService}.scala` (historical world built with `InMemoryWorldStateProxy` over `getBackingMptStorage(blockNumber)` as `EthUserService.getCode` does); add `-39014` [conduit] [forge] [beacon] **[BLOCKED: 018 Track R slice R1, 018 row 5, GT-2]**
- [ ] T057 [US2] Make `FollowConfig` select `pruning.mode = archive` (or the GT-2 basis) for `archive` and skip window deletes in `BlockApplier` for `archive`
- [ ] T058 [US2] Add `scripts/follow/compare-archive.sh` (samples N blocks across the chain; diffs `eth_getBalance`, `eth_getCode`, `eth_getStorageAt`, `eth_getProof`, `eth_call` against a reference archive; exit 0 iff identical)
- [ ] T059 [US2] [eye] Integration scenario `src/it/scala/com/chipprbots/ethereum/follow/ArchiveHistoricalSpec.scala`: SC-003 on Mordor against core-geth archive and on Platåberget/Sepolia against Geth or Reth; and US2 scenario 2 (`replica` outside window → `-39010`) **[BLOCKED: 023 T-007 harness; reference archives in the harness]**

**Checkpoint**: US1 and US2 work independently; the `archive` role is complete on the GT-2 basis.

---

## Phase 5: User Story 3 — a deep ETC reorg is survived or reported (Priority: P1)

**Goal**: retractions are applied backward newest-first to the fork point, then the new records forward; an `archive`
ends equal to a clean derivation; a `replica` with `W` below the depth halts with `reseed-required` (SC-004).

**Independent Test**: quickstart.md §3 with the 025 FR-009 deep-reorg fixture.

### Tests for User Story 3

- [ ] T060 [P] [US3] Test `src/test/scala/com/chipprbots/ethereum/blockchain/follow/FollowControllerReorgSpec.scala`: two-block reorg (retract 2, apply 2') → head and roots equal a clean derivation; retractions delivered newest-first are applied in that order; a retraction for a lower block delivered before a higher one is held; `archive` with a 100-block reorg ends equal to the reference consumer fold (025 FR-010 oracle, unit-level file-sink replay); `replica` with `W = 50` and depth 100 → `ReseedRequired`, nothing written past the halt, every read `-39012`
- [ ] T061 [P] [US3] Test `src/test/scala/com/chipprbots/ethereum/blockchain/follow/FinalizedRetractionSpec.scala`: ETH-family `archive` receiving a retraction at or below `follow.finalized` → `FinalizedRetraction` halt; ETC family (finalized = genesis) accepts any depth above 0
- [ ] T062 [P] [US3] Extend `StateReplayRoundTripSpec.scala` with a multi-block property: forward N blocks then backward N blocks leaves the store identical to the seed state (reference counts included), under `BasicPruning(history ≥ N)` [vault] [forge]

### Implementation for User Story 3

- [ ] T063 [US3] Implement retraction handling in `BlockApplier.scala`: load the retracted block's record from `FollowRecordStorage`, `applyBackward` tagged with its number, expected root = parent header's `stateRoot`, remove the number→hash mapping (header kept by hash), rewind `follow.appliedHead`, `BestBlockInfo`, `follow.windowLow`; on a missing record (below window) → `ReseedRequired` [forge] [beacon]
- [ ] T064 [US3] Implement the ETH-family finality guard in `OrderedRecordQueue.offer` (retraction ≤ finalized → `FinalizedRetraction`) and the ETC genesis-only rule from the 024 `finalityRule`
- [ ] T065 [US3] [eye] Integration scenario `src/it/scala/com/chipprbots/ethereum/follow/DeepReorgSpec.scala`: SC-004 on the 025 FR-009 deep ETC reorg fixture; `archive` equals the clean derivation; `replica` halts with the named fault and serves nothing across the gap **[BLOCKED: 025 T-006 fixture; 023 T-007 harness]**

**Checkpoint**: US1–US3 pass independently; DC-4 and FR-006 are demonstrated on both families.

---

## Phase 6: User Story 4 — a simulation pool reads a follower (Priority: P2)

**Goal**: `sim` sessions pinned to a block always get that block; when it leaves the window they get `-39010` with the
window and re-pin (023 SC-002's follower side).

**Independent Test**: quickstart.md §4.

### Tests for User Story 4

- [ ] T066 [P] [US4] Test `src/test/scala/com/chipprbots/ethereum/jsonrpc/PinnedReadStabilitySpec.scala`: with the head advancing concurrently (controlled `Ref`), 1,000 `eth_call`-shaped reads at a fixed explicit block all return the same `pinnedBlock`; a read at a block that has just left the window returns `-39010` with `data.window.high == head`

### Implementation for User Story 4

- [ ] T067 [US4] Ensure `PinnedBlockResolver` reads `follow.appliedHead` and `follow.windowLow` from one consistent snapshot of `FollowStatus` per request (no torn read between head and window) in `src/main/scala/com/chipprbots/ethereum/jsonrpc/PinnedBlockResolver.scala`
- [ ] T068 [US4] Wire 023 FR-010 transaction forwarding into the follower's `JsonRpcController` (`eth_sendRawTransaction` → `ingest` endpoint or `-39011`) [conduit] **[BLOCKED: 023 T-004 forwarder]**
- [ ] T069 [US4] [eye] Harness scenario `src/it/scala/com/chipprbots/ethereum/follow/SimReadsReplicaSpec.scala`: `sim` pool against a `replica` with `W = 300`, head advancing, assert zero reads answered at a block other than the pinned one and a re-pin on `-39010` **[BLOCKED: 023 T-006 `sim`, T-007 harness]**

**Checkpoint**: all four stories pass; 023 SC-002 can use US4.

---

## Phase 7: Kafka source, seed completion, unified layout (cross-story)

- [ ] T070 [P] Test `src/test/scala/com/chipprbots/ethereum/blockchain/follow/KafkaRecordSourceSpec.scala` (embedded or stubbed consumer): single-partition order preserved, resume from `follow.sourcePosition − 1`, re-delivered records surface as duplicates to the queue, broker outage → stream retries with backoff and `sourceLag` grows, no record lost [flow]
- [ ] T071 Implement `KafkaRecordSource` in `src/main/scala/com/chipprbots/ethereum/blockchain/follow/RecordSource.scala` over 025 T-004's client; position = `<partition>:<offset>` [flow] [herald] **[BLOCKED: 025 T-004]**
- [ ] T072 Complete `SeedImporter.scala`'s 013 v2 path: wrap the 013 importer, add `follow.*` keys to its final atomic batch (Cross-spec note 5), ETC anchor via `--trusted-block-hash` or stream linkage, ETH anchor per 013 §5.4; `SeedImporterSpec` cases for both [vault] [forge] [beacon] **[BLOCKED: 013 §11 rows 2/6/8; 013 Phases 1–2]**
- [ ] T073 Implement `StateReplay` v2 over the 018 unified layout in `src/main/scala/com/chipprbots/ethereum/db/replay/StateReplayV2.scala`; `StateReplayDifferentialSpec.scala` (contract L4) comparing v1 and v2 roots over the same diffs; `FollowController` unchanged [vault] [forge] [beacon] **[BLOCKED: 018 Track E then F]**
- [ ] T074 Move followers onto the unified layout (`NodeBuilder` selects v2 when the datadir is on the 018 layout); 018 FR-016 refusal message names the follower re-seed procedure **[BLOCKED: 018 Track F]**

---

## Phase 8: Polish & cross-cutting concerns

- [ ] T075 [P] Role table for 023 FR-002 in `specs/023-fleet-topology/quickstart.md` follower rows: servers, namespaces, `SyncMode.Follow`, readiness, transaction admission (write the rows here as a proposal; 023 owns the file)
- [ ] T076 [P] Operator docs: `docs/runbooks/follower-roles.md` (seed, start, window sizing, halt handling, inspect-halt) and update `docs/runbooks/checkpoint-service.md` to say checkpoint v1 is not a follower seed
- [ ] T077 [P] Update `.claude/agent-protocols/snap-sync.md`'s routing table or add `.claude/agent-protocols/follow.md` (follower files → owners → log tags → metrics), as `snap-sync.md` does for SNAP
- [ ] T078 [P] Add `fukuii_follow_*` and `fukuii_state_replay_*` panels to `ops/grafana/` dashboards
- [ ] T079 Coverage check: `sbt testStandard` with coverage; every new class in `db/replay`, `blockchain/follow`, `jsonrpc/PinnedBlockResolver`, `FollowerMethodGate` at or above 70% statements
- [ ] T080 Run quickstart.md §1–§4 and §6 end to end on the harness; record `VERIFY:` lines in the final PR [eye]
- [ ] T081 Append GT-1 and GT-2 results and the OQ-1/OQ-4 decisions to `specs/026-follower-roles/spec.md` "Open questions" and set the spec status line to `Active` with the release (owner of the spec thread; not this plan)

---

## Dependencies & execution order

### Phase dependencies

- **Setup (Phase 1)**: no dependencies.
- **Foundational (Phase 2)**: S1 depends on Setup and on 025 T-001's schema (the decoded type must match) and 018 row 7
  acceptance in review; S2 depends on Setup and 025 T-003's file format; S3 depends on 023 T-003/T-004 and 017 row 3
  (or the marker). **Blocks all user stories.**
- **US1 (Phase 3)**: after Phase 2. T047 (GT-1) also needs 025 T-003; T049 needs 023 T-007.
- **US2 (Phase 4)**: after Phase 2 and US1's resolver (T042–T044); T052/T056 need 018 Track R slice R1 and GT-2 (T055).
- **US3 (Phase 5)**: after Phase 2 and US1's `BlockApplier` (T038); T065 needs 025 T-006.
- **US4 (Phase 6)**: after US1's resolver; T068/T069 need 023 T-004/T-006/T-007.
- **Phase 7**: T071 after 025 T-004; T072 after 013; T073/T074 after 018 Track E/F.
- **Polish (Phase 8)**: after the stories wanted for the release.

### Within each story

- Tests first and failing; `StateDiff`/queue (models) before `BlockApplier`/`FollowController` (services) before RPC
  (endpoints); core before harness scenarios; a story complete before the next priority starts.

### Parallel opportunities

- Phase 1: T002–T005 together.
- S1: T006–T011 together, then T012 → T013 → T014 → T015 → T016–T018.
- S2 can run beside S1 (different packages); S3 after both.
- Phase 3: T033–T037 together; then T038 → T039 → T040; T041, T042–T044, T045, T046 in parallel after T039.
- Phase 4: T050–T052 together; T053/T054 beside T055; T056 after T055.
- Phase 5: T060–T062 together; T063 then T064.
- Phases 4, 5 and 6 can proceed in parallel after Phase 3's checkpoint if staffed.

---

## Parallel example: User Story 1

```bash
# Tests for US1 together (all fail until T038–T044 land):
Task: "BlockApplierSpec in src/test/scala/com/chipprbots/ethereum/blockchain/follow/BlockApplierSpec.scala"
Task: "FollowControllerSpec in src/test/scala/com/chipprbots/ethereum/blockchain/follow/FollowControllerSpec.scala"
Task: "SeedImporterSpec in src/test/scala/com/chipprbots/ethereum/blockchain/follow/SeedImporterSpec.scala"
Task: "PinnedBlockResolverSpec in src/test/scala/com/chipprbots/ethereum/jsonrpc/PinnedBlockResolverSpec.scala"
Task: "PinnedBlockEchoSpec in src/test/scala/com/chipprbots/ethereum/jsonrpc/PinnedBlockEchoSpec.scala"

# After FollowController (T039), in parallel:
Task: "SeedImporter in src/main/scala/com/chipprbots/ethereum/blockchain/follow/SeedImporter.scala"
Task: "PinnedBlockResolver in src/main/scala/com/chipprbots/ethereum/jsonrpc/PinnedBlockResolver.scala"
Task: "FollowInspectCli in src/main/scala/com/chipprbots/ethereum/blockchain/follow/FollowInspectCli.scala"
Task: "FollowBenchCli in src/main/scala/com/chipprbots/ethereum/blockchain/follow/FollowBenchCli.scala"
```

---

## Implementation strategy

### MVP first (Foundational + US1 on the Mordor-class devnet via the file sink)

1. Phase 1 Setup.
2. Phase 2: S1 (`StateReplay` v1 with L1–L5 green and the `forge`/`beacon` analysis on file), S2, S3.
3. Phase 3 US1 with the **genesis seed** and `FileRecordSource`; T047 runs GT-1 as soon as the 025 file sink exists.
4. **STOP and VALIDATE**: quickstart.md §1, §1b, §6; `testEssential`; `eye` reports.
5. Demo: a `replica` following the devnet, pinned reads, window error, halt on corruption.

### Incremental delivery

1. MVP (above) → 2. US3 (reorg correctness is cheap once `applyBackward` exists and is the P1 risk) → 3. US4 (needs the
   023 `sim` role) → 4. Phase 7 Kafka and production seeds → 5. US2 `archive` in phase D once 018 R1 and GT-2 report.

### Parallel team strategy

- Developer A: S1 (`db/replay`) with `vault`; Developer B: S2 + S3 (`blockchain/follow`, wiring) with `flow`/`herald`;
  Developer C: S5 RPC (`jsonrpc`) with `conduit`, starting from the contract while S1 is in review. US3 and US4 split
  the same way after the MVP.

---

## Task count

| Phase | Tasks |
|---|---|
| Setup | 5 (T001–T005) |
| Foundational | 27 (T006–T032): S1 15, S2 5, S3 7 |
| US1 | 17 (T033–T049) |
| US2 | 10 (T050–T059) |
| US3 | 6 (T060–T065) |
| US4 | 4 (T066–T069) |
| Cross-story (Kafka, seed, v2) | 5 (T070–T074) |
| Polish | 7 (T075–T081) |
| **Total** | **81** |

## Notes

- [P] tasks touch different files and have no dependency on each other.
- Every slice PR carries its reviewer sign-offs and `VERIFY:` lines (`compile-all`, the targeted specs,
  `testEssential`).
- No task edits `mpt/`, `ledger/`, `vm/` or `consensus/` (DC-7); the one `blockchain/sync/SyncController.scala` edit is
  the enum case and the mode selector (T028).
- Commit after each task or logical group with conventional prefixes; add files individually.
