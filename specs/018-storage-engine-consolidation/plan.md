# Implementation Plan: Storage Engine Consolidation

**Branch**: `018-storage-engine-consolidation` | **Date**: 2026-10-17 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/018-storage-engine-consolidation/spec.md`; evaluation in
[research.md](./research.md).

**Status**: Drafted ahead of implementation so `/speckit-tasks` can run once the gates report. Phase 0 can start after
spec 016 completes; Track E after spec 017 completes; Track F after Track E's SC-001 and GT-3.

## Summary

Swap RocksDB for libmdbx beneath the two state gateways (`StateIngest`, `StateAccess`) without changing them, then
change the state layout to flat accounts and slots plus one path-keyed trie with per-block changesets, so execution
reads are direct lookups and pruning no longer rewrites nodes. Every slice is behaviour-neutral for consensus and is
measured against a Phase 0 baseline. The witness pipeline (spec 017) is kept byte-identical through a key-set recorder
plus proof walk. The zkVM guest (spec 019) never sees any of it.

## Technical Context

**Language/Version**: Scala 3.3.8 (LTS) on JDK 25; sbt 1.10.7+.

**Primary Dependencies**: libmdbx (amalgamated C source, vendored under `db/mdbx/native/`, pinned version recorded in
ADR INF-007), bound through `java.lang.foreign` with `jextract`-generated Scala-callable Java bindings checked in (no
build-time jextract). Fallback (GT-1): `org.lmdbjava:lmdbjava`. Removed at the end: `org.rocksdb:rocksdbjni`.

**Storage**: one libmdbx environment per datadir at `${fukuii.datadir}/mdbx/`, one named sub-database per `Namespace`
(20), geometry with automatic growth, `MDBX_WRITEMAP` off by default (measure on), `MDBX_NOTLS` on (read txns are
not thread-bound; required for Pekko dispatchers), page size 4 KiB (measure 16 KiB for the node CFs).

**Testing**: ScalaTest; the shared `DataSourceTestBehavior` on all three engines; golden-bytes for kept formats
(016 S0c vectors); the two-engine import differential as a `SyncTest`-tagged CI job on fixed Mordor and Platåberget
ranges; ethereum/tests on the new engine; 24-hour soaks on Platåberget and Mordor. No `Thread.sleep`.

**Target Platform**: Linux x86_64 and aarch64 (release), macOS for developers; 16 GiB / 4-core host class.

**Project Type**: single project (`src/main`), `db/` module boundary.

**Performance Goals**: spec SC-002..SC-006 and SC-009 (write amplification ≤ ⅓ baseline, p99 import ≤ baseline,
max ≤ 2×p99, read wait ≤ 10 ms under write load, disk ≤ 1.5× RocksDB, fits 16 GiB, SNAP rate ≥ baseline).

**Constraints**: consensus outputs byte-identical (DC-2); gateways unchanged (DC-1); bounded readers (DC-5); stateless
closure untouched (DC-8); one-way door at GT-3 needs the user's decision.

**Scale/Scope**: `db/` is 5,616 lines over 56 files; 48 main files touch `DataSource`; 84 test files use
`EphemDataSource`; execution read path is `InMemoryWorldStateProxy` (about 350 lines) over `MerklePatriciaTrie`;
pruning is `ReferenceCountNodeStorage` (431 lines) and `StateStorage` (365 lines).

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Note |
| --- | --- | --- |
| I. Consensus determinism | PASS with protocol | No consensus output changes (DC-2). `mpt/` resolution and `ledger/` read-path changes go through `forge` (ETC) and `beacon` (ETH) before implementation; ethereum/tests and SC-001 gate every slice. |
| II. Spec-driven | PASS | This spec; `/speckit-clarify` on the OQs, then `/speckit-tasks`. ADRs INF-007 (engine) and INF-008 (layout). |
| III. Test discipline | PASS | Shared contract suite, golden-bytes, differential import in CI, soaks; coverage ≥ 70% kept (the new engine replaces, not adds, untested surface). |
| IV. Scala 3 style | PASS | FFM bindings are generated Java under `db/mdbx/` and excluded from scalafmt/scalafix like other generated code; all Scala passes both. |
| V. Quality gates | PASS | `sbt pp` per slice; the differential job is a required check for `db/` changes. |
| VI. Security | REVIEW | A native library is loaded into the process; the vendored source is pinned and hashed, built reproducibly, and listed in the SBOM. mmap'd database files inherit datadir permissions (document). |
| VII. Versioning & ADRs | PASS | INF-007 engine decision, INF-008 layout decision; breaking change (resync) flagged in the changelog and release notes. |
| Technology constraints | **VIOLATION, justified** | "RocksDB for storage" is named in the constitution; a new native dependency and a C build step are added. GT-2 amends the constitution before merge. See Complexity Tracking. |

## Project Structure

### Documentation (this feature)

```text
specs/018-storage-engine-consolidation/
├── spec.md              # the specification
├── plan.md              # this file
├── research.md          # Phase 0 evaluation (done) + baseline figures (T-002, to add as §12)
├── data-model.md        # Phase 1 output: sub-databases, key encodings, changeset format, metrics
├── quickstart.md        # Phase 1 output: resync procedure per chain, sizing formula, operator checklist
├── contracts/           # Phase 1 output: DataSource contract (post-cleanup), StorageMetrics, StorageScheme deprecation
└── tasks.md             # /speckit-tasks output
```

### Source Code (repository root)

```text
src/main/scala/com/chipprbots/ethereum/db/
├── dataSource/
│   ├── DataSource.scala              # trait cleanup (FR-003): IterationError, seekFrom, approximateKeyCount, iterateSyncRange
│   ├── EphemDataSource.scala         # gains the promoted members
│   ├── RocksDbDataSource.scala       # unchanged until removal (DC-11)
│   ├── MdbxDataSource.scala          # NEW (Track E)
│   ├── MdbxConfig.scala              # NEW: path, geometry, sync mode, reader threshold
│   └── StorageMetrics.scala          # NEW: engine-neutral gauges (replaces RocksDbCacheMetrics.register)
├── mdbx/
│   ├── native/                       # NEW: vendored libmdbx amalgamation (mdbx.c, mdbx.h), LICENSE, VERSION
│   └── ffm/                          # NEW: jextract-generated bindings (Java), excluded from scalafmt
├── components/
│   ├── MdbxDataSourceComponent.scala # NEW
│   └── Storages.scala                # wiring: changeset store, flat stores in the execution path (Track F)
└── storage/
    ├── FlatAccountStorage.scala      # downcast removed (Track E); execution read path (Track F)
    ├── FlatSlotStorage.scala         # same
    ├── BfsQueueStorage.scala         # downcast removed
    ├── ChangesetStorage.scala        # NEW (Track F): per-block undo records
    ├── PathNodeStorage.scala         # becomes the one node store (Track F)
    ├── ReferenceCountNodeStorage.scala  # retired (Track F)
    └── StateStorage.scala            # rollback/prune over changesets (Track F)

src/main/scala/com/chipprbots/ethereum/mpt/
└── MerklePatriciaTrie.scala          # path-aware resolution (Track F item 8; forge + beacon)

src/main/scala/com/chipprbots/ethereum/ledger/
├── InMemoryWorldStateProxy.scala     # flat reads under StateAccess (Track F item 7)
└── BlockExecution.scala              # key-set recorder installation (Track F item 9; amends 017 DC-8)

src/main/scala/com/chipprbots/ethereum/blockchain/sync/snap/
└── PathToHashExporter.scala          # retired (Track F item 10); the only SNAP file this spec touches

src/main/resources/conf/base/db.conf  # db.data-source = "mdbx"; mdbx { ... }; rocksdb { ... } until removal
scripts/snap-split/verify.py          # unchanged; must stay green
scripts/stateless-closure-check.*     # 017's DC-15 check, extended (DC-8)
Benchmark/                            # GT-0 read-probe harness; DC-12 figure collector
docs/adr/infrastructure/INF-007-*.md  # engine + FFM decision
docs/adr/infrastructure/INF-008-*.md  # unified layout decision (GT-3)
docs/runbooks/, .claude/skills/fukuii-* # operator updates (FR-018)
```

**Structure Decision**: single project; all engine code stays inside `db/`, which is the existing module boundary the
storage protocol (`.claude/agent-protocols/storage-rocksdb.md`, to be renamed `storage.md`) already enforces ("never call
the engine directly from outside `db/`"). Track F touches exactly three files outside `db/`: one in `mpt/`, two in
`ledger/`, plus the deletion in `snap/`.

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
| --- | --- | --- |
| New native dependency (libmdbx) and a C build step | The engine's properties (no WAL, no compaction, MVCC readers) are the point of the spec; there is no pure-JVM store with them | "Tune RocksDB more" was spec 015; it bounded memory but cannot remove compaction or the stall class. "Flat on RocksDB" (Besu) fixes the data model only and is kept as the Phase 0 control arm |
| FFM bindings instead of a published JNI artifact | No stable Maven artifact for libmdbx exists (research §8); a snapshot from a dormant repo is not a dependency a consensus client can carry | `lmdbjava` is published but is LMDB, not libmdbx (no automatic geometry, weaker large-txn handling); kept as the GT-1 fallback |
| Two engines compiled in for one minor release | Operators need a build that opens both layouts to plan a resync; CI needs both for SC-001 | A hard cut would leave no differential oracle and no safe rollback for a live Sepolia node |

## Phase 0 — Research and measurement (after spec 016; off the node host)

Outputs go into `research.md` §12 and the two ADR drafts. None of these merge code.

| ID | Item | Decides |
| --- | --- | --- |
| R-1 (GT-0) | Attribution spike: read wait under `dbLock` vs engine stall time, then with a close-fence only | how much the engine swap buys vs. the lock fix (OQ-7) |
| R-2 (GT-1) | FFM binding spike: env open, 20 DBIs, put/get, cursor, 10K-key txn per sync flag, long reader during 1,000 commits, close; x86_64 + aarch64 | go / go-with-constraints / fallback to lmdbjava |
| R-3 | DC-12 baseline on today's engine (Mordor + Platåberget datadirs): p50/p99 `[IMPORT-TIMING]`, longest read wait, bytes written per block, DB size, RSS, cgroup `memory.current`, `[STORAGE-PERF]` | the numbers every slice reports against |
| R-4 | Footprint estimate without compression: sample the `n`, `a`, `d`, `t`, `u` CFs' raw vs compressed sizes; model the unified layout's size (no stored ref counts, nodes stored once) | SC-005 feasibility; OQ-2 |
| R-5 | Prior-art read: Besu Bonsai trie-log + flat DB, reth changesets + history indices + `reth-libmdbx` settings, Erigon domains/ETL and reader discipline, geth PBSS diff layers; record in INF-008 draft | the changeset and flat-key formats (data-model.md), DC-5 operational rules |
| R-6 | 017 DC-8 amendment draft (DC-7): how reth builds `debug_executionWitness` from the hashed post-state; what the key-set recorder must capture (accounts, slots, code, ancestors, system-call reads per 017 DC-13) | T-006; SC-007 test design |
| R-7 | Memory accounting under cgroups v2 for mapped files; `-Xmx` formula; CL-client co-tenancy (OQ-8) | DC-10 formula in quickstart.md |
| R-8 | Resync timings: Mordor and ETC mainnet from a checkpoint archive; Sepolia from spec 013 snapshot or SNAP; chain-data download alone (OQ-4) | GT-3 material |

Phase 0 exit: GT-0 and GT-1 reported to the user; R-3 baseline published; INF-007 drafted; `/speckit-clarify` run on the
OQs.

## Phase 1 — Design artifacts (before Track E code)

- `data-model.md`: sub-database list and key encodings (unchanged for every kept namespace, so golden-bytes hold);
  changeset record format (block number → sequence of (namespace, key, previous value | absent)); access-set record;
  `StorageMetrics` gauge list; `MdbxConfig` keys with defaults.
- `contracts/`: the post-cleanup `DataSource` trait as the contract (FR-003), `StorageMetrics`, the `StorageScheme`
  deprecation behaviour (DC-9), the datadir layout detection message.
- `quickstart.md`: per-chain resync procedure, sizing formula (DC-10), operator checklist, rollback to RocksDB during
  the transition window.
- GT-2: constitution amendment + INF-007 merged.
- Re-run the Constitution Check.

## Phase 2 — Track E: the engine (after spec 017 completes)

Each slice is one PR, behaviour-neutral, with the DC-12 figures in the PR body and SC-001 green.

| Slice | Content | Owner / review |
| --- | --- | --- |
| E1 | Contract cleanup (FR-003): `IterationError` to the trait; `seekFrom`, `approximateKeyCount`, `iterateSyncRange` on the trait with `EphemDataSource` impls; the three downcasts removed; `compactRange` documented no-op where no compaction; `StorageMetrics` seam with the RocksDB impl behind it | vault; prism |
| E2 | Vendored libmdbx + FFM bindings + `MdbxDataSource` (get, multiGet, scanRange, update, updateSync, deleteRange, iterate as bounded windows) + `MdbxDataSourceComponent` + config; `DataSourceTestBehavior` green on three engines | vault; prism; security note in PR |
| E3 | Durability mapping (DC-3): `StateAccess` synced commits; `StateIngest` relaxed mode behind `StateIngest.Durability` (adds the second enum value 016 T056 reserved); the DC-1 barrier before `completeSnapSync` on both chains; crash test | vault; forge sign-off (ETC path) |
| E4 | Reader discipline (DC-5): bounded read txns everywhere `iterate`/`scanRange` are used; oldest-reader gauge + WARN; injected-unbounded-reader test | vault; flow (fs2 `Stream` boundaries) |
| E5 | Close-fence replaces `dbLock` (DC-4) on the new engine; SC-004 saturating-write test; if OQ-7 says yes, the same fence on RocksDB as its own earlier PR | vault; eye |
| E6 | Two-engine import differential in CI (FR-009) on fixed Mordor and Platåberget ranges; ethereum/tests on the new engine | eye |
| E7 | Operator docs and skills (FR-018); default flip for fresh datadirs; old-layout detection message (DC-9 part 1) | vault; conduit for RPC docs |
| E8 | Soaks (24 h Platåberget, 24 h Mordor); SC-003..SC-006, SC-009 reported | eye |
| E9 | RocksDB removal (DC-11, FR-017), one minor release after E7 | vault |

Track E exit: SC-001, SC-003, SC-004, SC-006, SC-009 green on both chains; GT-3 material delivered to the user.

## Phase 3 — Track F: the flat data model (after GT-3)

| Slice | Content | Owner / review |
| --- | --- | --- |
| F1 | INF-008 layout ADR accepted (GT-3 decision recorded); `data-model.md` changeset format final | user; vault |
| F2 | `ChangesetStorage` + `StateStorage` rollback/prune over changesets; `ReferenceCountNodeStorage` retired behind the same `StateStorage` API; golden-bytes updated only for the retired formats; abandon/readopt semantics preserved (016 edge cases) | vault; forge (ETC rollback semantics) |
| F3 | Path-aware node resolution in `mpt/` (FR-012): `(path, hash)` lookups with hash verification; `MptStorage` keeps `get(hash)` for witness replay (017 FR-005's `WitnessMptStorage` is hash-keyed) | **forge + beacon before implementation** (consensus-change protocol); wraith; eye with ethereum/tests |
| F4 | SNAP ingest on the unified layout (FR-015): nodes written once path-keyed, leaves once flat; `PathToHashExporter` retired; 016 FR-044 property re-expressed as E3's barrier; 016 ratchet unchanged | vault; herald (serving unchanged); forge (ETC SNAP) |
| F5 | Key-set recorder + proof walk (FR-013, DC-7) behind a flag; SC-007 byte-equality against 017's recorder on the differential set; BAL self-check; 017 docs amendment (T-006) approved | beacon; conduit (RPC unchanged); vault |
| F6 | Flat execution reads (FR-011) under `StateAccess`: `getAccount`/`getStorage` from flat stores; trie updated from the write set at commit; SC-001 under flat reads; `[IMPORT-TIMING]` compared to Track E | **beacon + forge**; eye |
| F7 | Default on for flat reads once F5 and F6 are green; `storage-scheme` deprecated (DC-9); `TrieReadMemo`, `PrefetchNodeReader` and `DecodedNodeCache` re-evaluated (some become redundant) | vault; prism |
| F8 | Soaks repeated; SC-002, SC-005, SC-007, SC-008 reported; pruning modes decided (OQ-3, OQ-5) | eye |

Track F exit: all SCs green; `.claude/agent-protocols/storage-rocksdb.md` replaced by `storage.md`; CLAUDE.md's
"Modules" and "vault" rows updated.

## Risks and rollback

| Risk | Signal | Response |
| --- | --- | --- |
| FFM binding does not work on a release platform | GT-1 | `lmdbjava` fallback; if both fail, Track E closes and Track F runs on RocksDB |
| Long readers grow the file | oldest-reader gauge, file size gauge | DC-5 rules; E4's test; the WARN names the origin |
| Disk footprint exceeds SC-005 | R-4 model, E8 soak | shorter keys in flat stores; drop the Path CF duplication earlier (F4 before E8 if needed) |
| Page cache vs JVM heap contention on 16 GiB | DC-10 gauges, E8 | lower `-Xmx` guidance; `MDBX_NORDAHEAD`; measure `WRITEMAP` |
| Witness divergence under flat reads | SC-007 red | flat reads stay off; 017's node recorder stays the producer until fixed |
| ETC operator resync cost | R-8 | GT-3 is the user's call; the alternative (hash-keyed node store option) stays documented |
| Transition window with two engines | CI time | the differential job runs on `db/` changes only |

Rollback at any Track E slice: set `db.data-source = "rocksdb"` on an old-layout datadir (both engines compile in
until E9). Rollback at any Track F slice: flat reads and the key-set recorder are flagged off by default until F7.

## Specialist routing

- `vault`: everything under `db/`; owns INF-007 and the storage protocol rewrite.
- `forge` (ETC) and `beacon` (ETH): F3, F6 before implementation; E3 and F2 sign-off; both review F5.
- `herald`: SNAP serving from flat state is unchanged (F4 review).
- `flow`: fs2 `Stream` boundaries for bounded readers (E4).
- `conduit`: `debug_executionWitness` unchanged (F5); RPC docs (E7).
- `eye`: every slice's validation; the differential job; soaks.
- `prism`: non-consensus review of E1, E2, F7.
- `wraith`: compile fallout from the trait cleanup and the `mpt/` signature change.
