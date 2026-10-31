# Implementation Plan: Follower Roles — `replica` and `archive`

**Branch**: `026-follower-roles` | **Date**: 2026-10-31 | **Spec**: [spec.md](spec.md) (Clarified 2026-10-31)

**Input**: Feature specification from `specs/026-follower-roles/spec.md`; the alignment review
([023/alignment-review.md](../023-fleet-topology/alignment-review.md) §2–§4); the pending amendment rows of specs 013
(§11 rows 1–9), 017 (row 3) and 018 (rows 1, 4, 5, 7, 9); the 025 record contract (DC-1..DC-5, FR-008).

**Companions**: [research.md](research.md) (decisions R-1..R-12) · [data-model.md](data-model.md) ·
[contracts/state-replay.md](contracts/state-replay.md) · [contracts/follower-rpc.md](contracts/follower-rpc.md) ·
[quickstart.md](quickstart.md) · [tasks.md](tasks.md).

**Release**: phase C (first fleet, 0.13.0) for GT-1, the apply loop and `replica` RPC; phase D (0.14.0) for `archive`
and the move to the unified layout (alignment review §4).

## Summary

A follower is a fukuii process in role `replica` or `archive` that never syncs: it starts from a 013 v2 snapshot, then
applies the 025 **semantic diff** of every canonical block through a new `StateReplay` capability in `db/`, recomputes
the state root with the same `MerklePatriciaTrie.put`/`remove` import uses, compares it to `header.stateRoot` and the
receipts root, and commits the block atomically only on a match. Reorgs are retractions applied **backward** over the
retracted blocks' own records. Every read is pinned to an explicit block and echoed back; a `replica` serves
`[head − W, head]` with `W` = 24 h of blocks from the ChainSpec profile and returns a defined error outside it; an
`archive` serves every block plus `eth_call`, `eth_simulateV1` and `debug_trace*` on bounded executors. On any
verification failure, a reorg deeper than `W`, or an unexpected fork, the follower halts, keeps its last verified
state, reports `ready = false` with a named fault, and waits for Orbita. The MVP (`replica` on today's layout, file
sink, Mordor-class devnet) needs no 018 code; `archive` and `StateReplay` v2 land in phase D under the same contract.

## Technical Context

**Language/Version**: Scala 3.9.0 (LTS line, spec 020 PR 3 on staging), JDK 25, sbt 1.13.0. Follower code PRs that
touch launcher/config/node wiring (`StdNode`, `NodeBuilder`, `SyncController`) are scheduled around 020 PR 5 per the
alignment review (§3, 020 row): before it is regenerated or after it lands, never beside it.

**Primary Dependencies**: Pekko Typed (lifecycle shell only), cats-effect `IO` + fs2 (the follow loop, the ordered
queue, the record source), RocksDB via `db/` (`DataSource`, `DataSourceBatchUpdate`, `StateStorage.stageBlock`),
`mpt/MerklePatriciaTrie` (unchanged), 025's record codec (RLP, leading version byte) and `RecordSink` file format,
013's v2 snapshot importer, 023's `InstanceConfig.role`, admin surface and role gating, 024's ChainSpec profile.
Kafka client: the same library 025 T-004 selects (one dependency, justified there), used read-only here.

**Storage**: today's RocksDB layout on both schemes. Writes per block: hash-keyed node CF `n` (staged, through
`StateStorage`), flat stores `a`/`d`, code `c`, headers `h` + number→hash `i`, receipts `r`, AppState `s`, and one new
CF `y` (`FollowRecordNamespace`) holding each block's record for the window. Path CFs `t`/`u` are never written
(research R-1). `replica`: `pruning.mode = basic`, `history = W + lag-bound`; `archive`: `pruning.mode = archive` until
GT-2 (research R-7, R-9).

**Testing**: ScalaTest (`AnyFlatSpec`/`AnyWordSpec`), ScalaCheck property tests through `ScalaCheckPropertyChecks` with
`ObjectGenerators` (`src/test/scala/com/chipprbots/ethereum/ObjectGenerators.scala`), `EphemDataSource` for every
storage test, `RefCountInspector` for reference-count identity, cats-effect `TestControl` for the gap timeout, Pekko
TestKit only for the lifecycle shell. No `Thread.sleep`. Tiers: unit specs in `testEssential`; the 023 harness
scenarios (SC-001..SC-005) in `testStandard`/`IntegrationTest`; the Mordor/Platåberget GT runs are off-CI
measurements recorded in this plan.

**Target Platform**: Linux containers in the 023 fleet harness and Orbita deployments; the single-node operator profile
is untouched (followers are new roles).

**Project Type**: node (single sbt root module), new sub-packages `blockchain/follow/`, `db/replay/`,
`domain/statediff/`, additions in `jsonrpc/`.

**Performance Goals**: GT-1 decides the number. Prior: per-block apply ≤ the chain's block time with headroom for the
lag bound (8 blocks) on the reference 4-core/16 GB host; apply cost ≈ import's trie-update half minus execution
(research R-3). `replica` disk growth bounded by `W` blocks of records plus pruned state.

**Constraints**: DC-7 consensus neutrality (no change to `mpt/`, `ledger/`, hashing or encoding; `forge` and `beacon`
review the apply path before it lands); DC-2/023 DC-9 (followers bind no P2P, discovery, Engine API or miner port);
FR-003 (never self-restart, re-seed or exit on a fault); FR-008 strict `blockNumber` order; 025 DC-3 duplicates are
no-ops; records never contain trie nodes.

**Scale/Scope**: one chain per process (023 DC-1); `W` ≈ 6,650 blocks (ETC/Mordor) or 7,200 (Sepolia/Platåberget);
`archive` retains the whole chain (Mordor first; mainnet after GT-2).

## Constitution Check

*GATE: passed before Phase 0 research; re-checked after Phase 1 design (2026-10-31).*

| Principle | Status | How this plan satisfies it |
|---|---|---|
| I. Consensus determinism (NON-NEGOTIABLE) | **Pass, with routing** | The follower computes roots with `MerklePatriciaTrie.put`/`remove` over hashed keys through an identity encoder; byte-identical paths to import's hashing encoder (research R-2, contract L2/L3). No file under `mpt/`, `ledger/`, `vm/` or `consensus/` changes. `StateReplay` v1 and the queue's retraction rules route through `forge` (ETC/Hash) and `beacon` (ETH/Path) **before** implementation (Specialist routing). ETC and ETH paths are not mixed: the follower is family-agnostic; the only family-specific inputs are the ChainSpec `W` and the 025 finality rule. |
| II. Spec-driven development | Pass | spec → this plan → tasks.md; Clarifications session is binding; GT-1/GT-2 results are appended here, not improvised. |
| III. Test discipline & tiered coverage | Pass | Property tests for forward∘backward = identity (contract L1), import equivalence (L2), hash-key equivalence (L3), atomicity (L5); deterministic clocks (`TestControl`); harness scenarios tagged `IntegrationTest`; coverage ≥ 70% kept by unit specs per new class (tasks.md). |
| IV. Idiomatic, formatted Scala 3 | Pass | New code under `com.chipprbots.ethereum`; opaque types for keys/roots (S11 propagation per `scala3-style.md`); `enum` for faults and decisions; explicit result types; `sbt scalafmtAll` per slice; `sbt pp` before each PR. |
| V. Quality gates | Pass | One PR per delivery slice; each green on `compile-all`, `testEssential`, `testStandard`; reviewer tags enforced per slice. |
| VI. Security & operational safety | Pass | Followers bind fewer ports than today's node (no P2P/discovery/Engine API; SC-005); JSON-RPC stays localhost-default; the Kafka consumer is read-only; seeds are verified by root recomputation before any pointer is written (013 §5.4; research R-8); no key material involved. |
| VII. Versioning & decision records | Pass | ADR **INF-010 StateReplay capability** (beside INF-006 `StateIngest`) in `docs/adr/infrastructure/`, written in slice S1; the 018 row 7 acceptance is recorded in 018's spec when the user accepts it. |
| Technology constraints | Pass | No new engine; RocksDB via `DataSource`; Kafka client shared with 025; module boundaries respected (`db/replay` depends on `db/storage` and `mpt`, never on `blockchain/`; `blockchain/follow` depends on `db/replay`, never the reverse). |

No violation to justify; Complexity Tracking is empty.

## Project Structure

### Documentation (this feature)

```text
specs/026-follower-roles/
├── spec.md              # Clarified 2026-10-31 (not changed by this plan)
├── plan.md              # this file
├── research.md          # R-1..R-12
├── data-model.md        # types, AppState keys, queue, config, errors, health
├── quickstart.md        # US1–US4 scenarios, GT-1 run, SC-005 check
├── contracts/
│   ├── state-replay.md  # StateReplay capability contract (vault)
│   └── follower-rpc.md  # replica/archive RPC behaviour (conduit)
└── tasks.md             # Phase 2 output
```

### Source Code (repository root)

```text
src/main/scala/com/chipprbots/ethereum/
├── domain/statediff/
│   ├── StateDiff.scala                 # AccountKey, SlotKey, Change, StateDiff, validate, inverse (shared 025/018)
│   └── FollowRecord.scala              # BlockRecord / Retraction / Finality (decoded form of the 025 record)
├── db/replay/
│   ├── StateReplay.scala               # trait, Root, ReplayError, StateReplay.v1(...)
│   ├── StateReplayV1.scala             # today's layout: staged trie puts over hashed keys + flat + code
│   ├── HashedKeyTries.scala            # identity-encoder MerklePatriciaTrie constructors (account, storage)
│   └── FollowRecordStorage.scala       # CF `y`: record bytes per (blockNumber, blockHash); window delete
├── db/storage/
│   ├── Namespaces.scala                # + FollowRecordNamespace ('y')
│   └── AppStateStorage.scala           # + follow.* keys (data-model §3)
├── blockchain/follow/
│   ├── FollowConfig.scala              # fukuii.follow.* (data-model §6), ChainSpec window default
│   ├── RecordSource.scala              # seam: Stream[IO, (Position, bytes)]; FileRecordSource, KafkaRecordSource
│   ├── OrderedRecordQueue.scala        # pure bounded queue (data-model §4)
│   ├── FollowFault.scala               # enum (data-model §5)
│   ├── BlockApplier.scala              # record → StateReplay + block batch (header, receipts, mapping, AppState)
│   ├── FollowController.scala          # fs2 loop: source → queue → applier; halt; status Ref; drain/stop
│   ├── FollowStatus.scala              # health/metrics projection (data-model §5, §8)
│   ├── SeedImporter.scala              # wraps the 013 importer; follow.* seed keys; role stamp
│   ├── FollowBenchCli.scala            # GT-1 instrument (`fukuii follow-bench`)
│   └── FollowInspectCli.scala          # `fukuii follow inspect-halt`
├── blockchain/sync/SyncController.scala # SyncMode + Follow (017 FR-007 row 3); selectSyncMode(role, config)
├── nodebuilder/
│   ├── NodeBuilder.scala               # StateReplay/FollowController wiring for follower roles only
│   └── StdNode.scala                   # start(): follower branch (no server/discovery/peers/sync/miner/engine)
└── jsonrpc/
    ├── PinnedBlockResolver.scala       # role-aware resolver (contracts/follower-rpc.md §2)
    ├── JsonRpcResponse.scala           # + pinnedBlock: Option[PinnedBlock]
    ├── JsonRpcError.scala              # + -39010..-39014 (data-model §7)
    ├── EthUserService.scala, EthProofService.scala, EthBlocksService.scala, EthFilterService.scala  # resolver
    ├── FollowerMethodGate.scala        # per-role method matrix (contracts/follower-rpc.md §1) over 023's role error
    └── server/http/JsonRpcHttpServer.scala  # Fukuii-Pinned-Block header

src/main/resources/conf/base/follow.conf   # fukuii.follow defaults
src/test/scala/com/chipprbots/ethereum/
├── domain/statediff/StateDiffSpec.scala, StateDiffGenerators.scala
├── db/replay/StateReplayV1Spec.scala, StateReplayRoundTripSpec.scala, StateReplayImportEquivalenceSpec.scala,
│              HashedKeyTriesSpec.scala, StateReplayAtomicitySpec.scala, FollowRecordStorageSpec.scala
├── blockchain/follow/OrderedRecordQueueSpec.scala, FollowControllerSpec.scala, BlockApplierSpec.scala,
│                     FileRecordSourceSpec.scala, SeedImporterSpec.scala, FollowConfigSpec.scala
├── jsonrpc/PinnedBlockResolverSpec.scala, FollowerRpcMatrixSpec.scala, PinnedBlockEchoSpec.scala,
│          FollowerGetLogsSpec.scala
└── nodebuilder/FollowerRoleGatingSpec.scala
src/it/scala/com/chipprbots/ethereum/follow/   # SC-001..SC-005 against the 023 harness fixtures
scripts/follow/corrupt-record.sh, compare-archive.sh
docs/adr/infrastructure/INF-010-state-replay-capability.md
```

**Structure Decision.**
- The follower lives in **`blockchain/follow/`**, a sibling of `blockchain/sync/` and `blockchain/checkpoint/`: it is
  the
  follower's substitute for sync (it owns `SyncMode.Follow`'s behaviour) and reuses the checkpoint importer's patterns.
  It is **not** under `blockchain/sync/`: the snap-split verifier (`scripts/snap-split/verify.sh`) counts storage
  references in `snap/controller`, and 022 forbids new components in module directories; a follower there would move
  the baseline for no reason. A `fleet/` package was rejected: 023 DC-4 says the node does not know about the fleet, and
  no such package exists.
- `StateReplay` lives in **`db/replay/`** (018 row 7 says "a capability in `db/`"): a capability composes several
  `db/storage` stores and is not itself a store, so it does not belong beside the one-class-per-CF files in
  `db/storage/`. The same sub-package pattern is open to 017's `StateAccess` later. `db/replay` depends on `db/storage`
  and `mpt` only.
- The decoded diff type lives in **`domain/statediff/`** because three owners share it (025 encodes it, 018 F2 stores
  it, 026 applies it) and `db/` must not depend on a wire package; see Cross-spec note 2.

## Design decisions (summary; full text in research.md)

| # | Decision | Research |
|---|---|---|
| D1 | v1 writes CF `n` through `StateStorage.stageBlock(bn)` on both schemes, plus flat stores and code, in one batch; Path CFs untouched; verification before commit via `commitWith`/`discard` | R-1 |
| D2 | Account and storage tries are updated by hashed key with an identity `ByteArrayEncoder`; no `mpt/` change; equivalence pinned by property tests | R-2 |
| D3 | GT-1 runs through `fukuii follow-bench` on the 025 file sink; the prior is apply; re-execution is the fallback | R-3 |
| D4 | Rollback = backward replay of the retracted block's own record (prev values) tagged with its number; no `onBlockRollback`/`onBlocksAbandoned`; round-trip identity incl. reference counts is the gate | R-4 |
| D5 | A pure bounded `OrderedRecordQueue`; duplicates no-op; replacement without retraction = `UnexpectedFork`; `PersistentGap` after the timeout, non-halting; retraction below the window = `ReseedRequired` | R-5 |
| D6 | `PinnedBlockResolver` resolves once per request; `pending` refused; scalar results carry `pinnedBlock` + `Fukuii-Pinned-Block` header; `ingest` responses unchanged | R-6 |
| D7 | `W` in blocks from the ChainSpec profile (6,650 ETC-family, 7,200 ETH-family), overridable; `pruning.history = W + lag-bound`; halt is durable, refuses all chain reads, never self-clears | R-7 |
| D8 | Seed = 013 v2 for both families via `SeedImporter` over the 013 importer, final atomic batch as `CheckpointImporter`; devnet MVP seed = genesis + records from 1 | R-8 |
| D9 | GT-2 measures 018 changesets-only (default) vs Hash `ArchivePruning` (available today) vs history indices (paper) | R-9 |
| D10 | `SyncMode.Follow` added to `SyncController.SyncMode`; follower roles never spawn `SyncController`; `StdNode.start()` branches on role | R-10 |
| D11 | Readiness = not halted ∧ lag ≤ bound ∧ last root verified; health gains `window`, `fault`, `seedBlock` | R-11 |
| D12 | One `commit()` per block; `commitSync` every 64 blocks and on halt/drain/stop; restart resumes from `follow.sourcePosition − 1` with duplicates as no-ops | R-12 |

## Delivery slices (one PR each, in order; owner → reviewers)

| Slice | Phase | Content | Owner → reviewers | Blocked on |
|---|---|---|---|---|
| **S0** GT-1 instrument and measurement | C | `FollowBenchCli` over `StateReplay.v1` in dry-run and apply modes; runs on Mordor and Platåberget with the 025 file sink; result pasted into "GT-1 result" below; FR-002 default recorded | `eye` → `forge`, `beacon` (read the figures) | 025 T-003 (file sink); S1 (`StateReplay.v1`, so S0 and S1 are developed together and S0's PR follows S1's) |
| **S1** `StateDiff` + `StateReplay` v1 + ADR INF-010 | C | `domain/statediff`, `db/replay/*`, `FollowRecordStorage`, `Namespaces` + `AppStateStorage` keys, property tests L1–L5 | `vault` → **`forge` and `beacon` impact analysis BEFORE the PR opens** (encoder equivalence, write order, refcount tagging), `prism` | 018 row 7 accepted (or the user accepting it in this PR's review); 025 T-001 schema (the decoded type must match) |
| **S2** `RecordSource` seam + `FileRecordSource` + `OrderedRecordQueue` | C | fs2 source over the 025 file sink; the pure queue; `TestControl` tests for the gap timeout | follower thread → `flow` (queue stage, backpressure, no silent drops) | 025 T-003 file format |
| **S3** `SyncMode.Follow` + role gating + wiring | C | `SyncController.SyncMode.Follow`, `selectSyncMode(role, config)`, `StdNode.start()` follower branch, `NodeBuilder` construction of `StateReplay`/`FollowController` for follower roles only, `follow.conf`, `FollowerRoleGatingSpec` (no P2P/discovery/Engine/miner start) | follower thread → `herald` (what is not started), `eye` | 023 T-003 (`--role`, `InstanceConfig.role`), 023 T-004 (gating, role error); 017 row 3 accepted (else the enum case carries the amendment marker); scheduled around 020 PR 5 |
| **S4** `FollowController` + `BlockApplier` + halt + health fields | C | the loop, forward/backward apply through `StateReplay`, receipts-root check, `FollowFault`, durable halt, `FollowStatus` into the 023 health shape and metrics, `follow inspect-halt` | follower thread → `vault` (batch composition, sync cadence), `forge`/`beacon` (retraction semantics per family), `eye` | S1–S3; 023 T-005 (health shape) |
| **S5** `replica` RPC: resolver, window, echo, method gate | C | `PinnedBlockResolver`, `JsonRpcResponse.pinnedBlock`, header, `-39010..-39013`, `FollowerMethodGate`, `EthFilterService` range handling on followers; `ingest` byte-identity test | `conduit` → `prism` | 023 T-004 (role error code) |
| **S6** `SeedImporter` (013 reuse) | C | wraps the 013 importer; `follow.*` seed keys; role stamp; ETC anchor via `--trusted-block-hash` or stream linkage; refusal path | follower thread → `vault`, `beacon` (ETH anchor), `forge` (ETC anchor) | **013 §11 rows 2, 6, 8 accepted and 013 Phases 1–2 landed**; until then the MVP uses the genesis seed |
| **S7** `KafkaRecordSource` | C | consumer over 025's topic, position in `follow.sourcePosition`, re-delivery as duplicates | follower thread → `flow`, `herald` (client config) | 025 T-004 (Kafka implementation and client choice) |
| **S8** Harness scenarios SC-001, SC-002, SC-004, SC-005 (+ US4) | C | `src/it` scenarios against 023 FR-007 fixtures; `corrupt-record.sh`; the 025 FR-009 deep-reorg fixture | `eye` | 023 T-007/T-008 (harness); 025 T-006 (fixtures) |
| **S9** GT-2 measurement | D | archive basis A vs B on Mordor; OQ-1 recorded in spec.md and "GT-2 result" below | `eye` → `vault` | 018 Track F slice F2 (changesets) for option A; option B runs today |
| **S10** `archive` serving (FR-005) | D | `eth_call`, `eth_simulateV1`, `debug_trace*` on 018 FR-027 executors; `-39014`; SC-003 against core-geth/Geth archives | `conduit` → `forge`/`beacon` (execution at historical roots), `prism` | **018 Track R slice R1** (executors), GT-2 result, 018 row 5 (archive pruning mode) |
| **S11** `StateReplay` v2 on the unified layout | D | same trait over 018 F6/F11; differential against v1; followers move with 018 Track F | `vault` → `forge`, `beacon` | 018 Track E then F |

**Explicitly blocked** (no code for these lands before the named item):
- on **013 pending rows 2, 6, 8** and 013 Phases 1–2: S6 (production seeds for both families). The MVP seed is genesis
  on the devnet; a production `replica` cannot start without S6.
- on **018 row 5** (archive pruning mode) and **row 7** (`StateReplay` acceptance): S10 and, formally, S1. S1's PR
  carries the row 7 text for the user to accept in review; it does not merge over a rejection.
- on **018 Track R slice R1** (FR-027 executors): S10. A `replica` never needs them.
- on **017 FR-007 amendment row 3** (`SyncMode.Follow`): S3. If 017 is not planned when S3 is ready, S3 adds the enum
  case with a `// 017 FR-007 amendment row 3` marker and 017's plan adopts it (Cross-spec note 4).
- on **023 T-003/T-004/T-005/T-007** and **025 T-001/T-003/T-004/T-006**: as listed per slice.

**MVP** = S1–S5 on a Mordor-class devnet with the file sink and the genesis seed (tasks.md "Implementation Strategy").

## Specialist routing

| When | Who | What they do |
|---|---|---|
| Before S1's PR opens | `forge`, `beacon` | Impact analysis of `StateReplayV1`: identity-encoder equivalence (R-2), per-block write order vs `persistState`, reference-count tagging of backward writes (R-4), ETC `ArbitraryIntegerMpt` exclusion. Byte-perfect check: L2/L3 property tests plus one real Mordor and one real Platåberget block range replayed in `follow-bench --dry-run` against the headers' roots. |
| S1, S4, S6 review | `vault` | Batch composition (one `WriteBatch` per block), `commitSync` cadence, CF `y` key layout and range delete, AppState keys, iterator lifecycle in `FollowRecordStorage`, `storage-rocksdb.md` conformance. |
| S2, S7 review | `flow` | The fs2 stage: bounded queue, backpressure onto the source, no `preMaterialize`, no silent drop on error, `TestControl` tests. |
| S3 review | `herald` | Confirms no P2P/discovery/Engine listener is constructed for follower roles (SC-005). |
| S5, S10 review | `conduit` | Resolver, error codes, echo member/header, `eth_getLogs` edges, executor saturation error; JSON-RPC 2.0 strictness risk. |
| After S4, S5, S8, S10 | `eye` | Runs `testEssential`, the follower unit specs, the harness scenarios; reports `VERIFY: ran … — PASS/FAIL`. |
| Any compile failure | `wraith` | Scala 3.9 build errors without altering semantics. |
| S1, S5 (non-consensus parts) | `prism` | 8-lens review. |

## GT-1 result

*Pending S0.* Fields to fill: chain, range, seed, option (a)/(b)/(c), blocks/s, apply p50/p99 ms, nodes/block,
batch bytes/block, disk growth per 1,000 blocks, wall time; FR-002 default per family; SC-001 bound.

## GT-2 result

*Pending S9 (phase D).* Fields: basis A/B, disk per 1M blocks, `eth_getBalance` and `eth_call` p50/p99 at 1 / 10k /
100k / 1M blocks behind head; OQ-1 decision.

## Risks

| Risk | Mitigation |
|---|---|
| Reference-count identity on backward replay (R-4) cannot be made exact under basic pruning | L1 property test fails early in S1; fallback decided in S1 by `vault` (tag + cleanup), never by weakening the test |
| 025's record lacks bodies, so `eth_getTransactionReceipt` and full-tx blocks cannot be served (contracts/follower-rpc.md §6) | Cross-spec note 1; until amended the method returns `-39011` and the matrix test pins that |
| Strict JSON-RPC 2.0 clients reject the `pinnedBlock` extension member | Followers sit behind Orbita's gateway; the header is the alternative; `fukuii.follow.rpc.pin-header` is on by default |
| 013 ETC coverage slips, leaving Mordor followers without a production seed | MVP on genesis seed is still valid for GT-1 option (c) and SC-002/SC-004; production ETC `replica` waits for S6 |
| Apply does not meet the chain's block time on the reference host | GT-1 says so before S4; the re-execution fallback (FR-002) is designed in but not built unless GT-1 requires it |
| `ArchivePruning` on an `archive` grows without bound before GT-2 | `archive` is phase D; S9 runs before S10; the Hash basis is measured, not assumed |

## Complexity Tracking

No Constitution Check violation; nothing to justify.

## Cross-spec notes (for the owners of 013, 017, 018, 023, 024, 025; no spec text changed here)

1. **025 (block record contents).** The record carries header, receipts and state diff, not the block **body**.
   Without transactions a follower cannot write `BlockBodiesStorage`/`TransactionMappingStorage`, so
   `eth_getTransactionReceipt`, `eth_getTransactionBy*` and `fullTransactions = true` blocks are unservable
   (contracts/follower-rpc.md §6). Request: add the body (or the transaction list) to the block record in 025 T-001;
   026 FR-004 lists `eth_getTransactionReceipt` as a `replica` method and cannot meet it otherwise.
2. **025 and 018 (shared decoded type).** 026 proposes `domain/statediff/StateDiff` as the one decoded diff type
   (data-model §1); 025's codec should decode into it and 018 F2 store its encoding, so 025 DC-9 holds in code, not
   only in bytes. 025 T-001 should adopt or amend the type before S1.
3. **018 rows 5 and 7.** S1 carries row 7's text for acceptance; S10 needs row 5 (a mode that retains every changeset)
   and Track R slice R1. 026 also asks 018's GT-1 harness (row 1) to include a follower-shaped reader profile only if
   `sim` ever opens a follower's store directly (023 DC-5 says RPC first), so no change is requested now.
4. **017 FR-007 (row 3).** 026 adds `SyncMode.Follow` in S3. If 017's plan has not landed, the case is added with the
   amendment marker and 017 adopts it; followers are never `Snap` or `Backfill`; witness capture on `archive` is 017's
   phase D work and uses the follower's verified roots.
5. **013 §11 rows 2, 6, 8.** S6 depends on all three. 026 adds one request: the v2 importer should expose a hook to add
   writes to its final atomic batch (so `SeedImporter` stamps `follow.*` keys in the same `commitSync`), mirroring how
   `CheckpointImporter` composes `appStateStorage.*` into its last batch.
6. **023 T-004 (role error code).** 026 proposes `-39011 not served by this role` and reserves `-39010`, `-39012`,
   `-39013`, `-39014` beside it (data-model §7); 023 owns the final value and 026 adopts it.
7. **023 FR-004 (health shape).** Followers add `window {low, high}`, `fault`, `seedBlock` (data-model §8); 023 T-005
   should leave room for role-specific members.
8. **024 FR-007 (profiles).** The `replica` profile key is `retention-window-blocks`, defaults 6,650 (ETC family) and
   7,200 (ETH family); 024's loader validates it as a positive integer and 026 reads it through `FollowConfig`.
9. **022.** The follower never reads `snap.heal.invariant`; the seed import sets the SNAP done flags as the checkpoint
   importer does so a datadir is never mistaken for fresh. No 022 change needed; recorded so the marker's readers know.
