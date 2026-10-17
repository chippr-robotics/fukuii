# Implementation Plan: Storage Engine Consolidation

**Branch**: `018-storage-engine-consolidation` | **Date**: 2026-10-17 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/018-storage-engine-consolidation/spec.md`; evaluation in
[research.md](./research.md).

**Status**: Drafted ahead of implementation so `/speckit-tasks` can run once the gates report. Phase 0 can start after
spec 016 completes; Track E after spec 017 completes and GT-1 has chosen the engine; Track F after Track E's SC-001;
Track H after GT-3. **Revised 2026-10-17**: engine chosen by bake-off (not fixed to libmdbx); gigablock harness; cold
tier (Track H); ETC on the unified layout is decided, not gated.

## Summary

Replace RocksDB beneath the two state gateways (`StateIngest`, `StateAccess`) with an embedded engine chosen by a
measured bake-off, without changing the gateways; move immutable chain data to an append-only segment store; then change
the state layout to flat accounts and slots plus one path-keyed trie with per-block changesets and a parallel, cached
state root, so execution reads are direct lookups, pruning no longer rewrites nodes, and the storage side scales to
gigablocks. Add an optional cold tier (ClickHouse is the first assessed adapter) for logs, witnesses and aged history.
Both chains move to the one layout; ETC resyncs. Every slice is behaviour-neutral for consensus and is measured against
a Phase 0 baseline. The witness pipeline (spec 017) is kept byte-identical through a key-set recorder plus proof walk.
The zkVM guest (spec 019) never sees any of it.

## Technical Context

**Language/Version**: Scala 3.3.8 (3.3 LTS) on JDK 25; sbt 1.10.7 today. The LTS audit of 2026-10-17 (`docs/reports/LTS_TOOLCHAIN_AUDIT_2026-10.md`) recommends moving to **Scala 3.9 LTS** and sbt 1.12.x before spec 017 starts (its R-1, R-2; stubbed as spec 020, `specs/020-scala-3-9-lts-migration/`); if that lands, the bake-off harness and every Track E/F/H slice target 3.9 and the JDK 25 FFM API is unaffected. JDK 25 is the current LTS and stays.

**Primary Dependencies**: the hot-tier engine GT-1 selects. Candidates and their routes (research §12): libmdbx
(amalgamated C source vendored under `db/<engine>/native/`, bound through `java.lang.foreign` with `jextract`-generated
bindings checked in; version pinned and hashed in ADR INF-007); LMDB (`org.lmdbjava:lmdbjava`); RocksDB (`rocksdbjni`,
the control); Xodus / H2 MVStore (Maven, assessed). Segment store: FFM `MemorySegment` over files, no dependency. Cold
tier (if GT-3 says go): `com.clickhouse:clickhouse-java`. Removed at the end: `org.rocksdb:rocksdbjni`.

**Storage**: one hot-tier store per datadir at `${fukuii.datadir}/<engine>/`, one named sub-store per `Namespace`
(20); an append-only segment store at `${fukuii.datadir}/segments/` for headers, bodies and receipts; an optional
cold store reached over the network. For an mmap engine: read transactions not thread-bound (Pekko dispatchers),
geometry with automatic growth, page size measured (4 vs 16 KiB for the node stores).

**Testing**: ScalaTest; the shared `DataSourceTestBehavior` on all three engines; golden-bytes for kept formats
(016 S0c vectors); the two-engine import differential as a `SyncTest`-tagged CI job on fixed Mordor and Platåberget
ranges; ethereum/tests on the new engine; 24-hour soaks on Platåberget and Mordor. No `Thread.sleep`.

**Target Platform**: Linux x86_64 and aarch64 (release), macOS for developers; 16 GiB / 4-core host class.

**Project Type**: single project (`src/main`), `db/` module boundary.

**Performance Goals**: spec SC-002..SC-006, SC-009 and SC-012 (write amplification ≤ ⅓ baseline, p99 import ≤
baseline, max ≤ 2×p99, read wait ≤ 10 ms under write load, disk ≤ 1.5× RocksDB, fits 16 GiB, SNAP rate ≥ baseline;
at 30× block gas: linear import time, serial state-root remainder ≤ 5%, linear peak heap, ≥ 6× read throughput on 8
threads).

**Constraints**: consensus outputs byte-identical (DC-2); gateways unchanged (DC-1); bounded readers (DC-5); stateless
closure untouched (DC-8); typed storage API (DC-12); gigablock readiness (DC-13); ETC on the unified layout in the
first release (DC-9, user decision).

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
| IV. Scala 3 style | PASS | FFM bindings (if the chosen engine is FFM-bound) are generated Java under `db/<engine>/ffm/` and excluded from scalafmt/scalafix like other generated code; all Scala passes both. |
| V. Quality gates | PASS | `sbt pp` per slice; the differential job is a required check for `db/` changes. |
| VI. Security | REVIEW | A native library is loaded into the process; the vendored source is pinned and hashed, built reproducibly, and listed in the SBOM. mmap'd database files inherit datadir permissions (document). |
| VII. Versioning & ADRs | PASS | INF-007 engine decision, INF-008 layout decision; breaking change (resync) flagged in the changelog and release notes. |
| Technology constraints | **VIOLATION, justified** | "RocksDB for storage" is named in the constitution; a new engine (possibly a native dependency and a C build step) and an optional networked cold store are added. GT-2 amends the constitution before merge. See Complexity Tracking. |

## Project Structure

### Documentation (this feature)

```text
specs/018-storage-engine-consolidation/
├── spec.md              # the specification
├── plan.md              # this file
├── research.md          # Phase 0 evaluation (done); §12 candidates, §13 cold tier, §14 gigablocks, §15 baseline (T-002 fills)
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
│   ├── Txn.scala                     # NEW (DC-12): ReadTxn / WriteTxn capabilities, bounded Resources
│   ├── <Engine>DataSource.scala      # NEW (Track E): the engine GT-1 selects
│   ├── <Engine>Config.scala          # NEW: path, geometry, sync mode, reader threshold
│   ├── StorageEngine.scala           # NEW: enum for db.data-source (replaces the string match in NodeBuilder)
│   └── StorageMetrics.scala          # NEW: engine-neutral gauges (replaces RocksDbCacheMetrics.register)
├── <engine>/
│   ├── native/                       # NEW if FFM-bound: vendored source, LICENSE, VERSION, SHA-256
│   └── ffm/                          # NEW if FFM-bound: jextract-generated bindings (Java), excluded from scalafmt
├── segments/
│   ├── SegmentStore.scala            # NEW (FR-022): append-only mmap files for headers, bodies, receipts
│   └── SegmentIndex.scala            # NEW: block number / hash → (file, offset)
├── cold/
│   ├── ColdStore.scala               # NEW (FR-024): async, bounded seam; NoopColdStore default
│   └── ClickHouseColdStore.scala     # NEW if GT-3 says go (FR-025)
├── components/
│   ├── <Engine>DataSourceComponent.scala # NEW
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
├── MerklePatriciaTrie.scala          # path-aware resolution (Track F item 8; forge + beacon)
└── SparseTrie.scala                  # NEW (Track F item 11, FR-023): parallel subtree hashing, cross-block cache

src/main/scala/com/chipprbots/ethereum/ledger/
├── InMemoryWorldStateProxy.scala     # flat reads under StateAccess (Track F item 7)
└── BlockExecution.scala              # key-set recorder installation (Track F item 9; amends 017 DC-8)

src/main/scala/com/chipprbots/ethereum/blockchain/sync/snap/
└── PathToHashExporter.scala          # retired (Track F item 10); the only SNAP file this spec touches

src/main/resources/conf/base/db.conf  # db.data-source = "<engine>"; <engine> { ... }; segments { ... }; cold { ... }; rocksdb { ... } until removal
src/main/scala/com/chipprbots/ethereum/jsonrpc/EthFilterService.scala  # eth_getLogs routing to ColdStore beyond the hot window (Track H; conduit)
scripts/snap-split/verify.py          # unchanged; must stay green
scripts/stateless-closure-check.*     # 017's DC-15 check, extended (DC-8)
Benchmark/                            # GT-0 read-probe harness; GT-1 bake-off harness (FR-021); DC-14 figure collector
docs/adr/infrastructure/INF-007-*.md  # engine + FFM decision
docs/adr/infrastructure/INF-008-*.md  # unified layout decision (GT-3)
docs/runbooks/, .claude/skills/fukuii-* # operator updates (FR-018)
```

**Structure Decision**: single project; all engine, segment and cold-tier code stays inside `db/`, which is the existing
module boundary the storage protocol (`.claude/agent-protocols/storage-rocksdb.md`, to be renamed `storage.md`) already
enforces ("never call the engine directly from outside `db/`"). Track F touches four files outside `db/`: two in `mpt/`,
two in `ledger/`, plus the deletion in `snap/`. Track H touches one file in `jsonrpc/`.

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
| --- | --- | --- |
| A new embedded engine (possibly native) replacing the constitution's named RocksDB | The stall class (F1) and the engine-level write amplification are properties of the LSM; the bake-off measures whether any candidate removes them | "Tune RocksDB more" was spec 015; it bounded memory but cannot remove compaction. "Flat on RocksDB" is kept **in** the bake-off as the control arm, so if nothing beats it, it wins |
| FFM bindings over vendored source (if an FFM-bound engine wins) | No stable Maven artifact for libmdbx exists (research §8); a snapshot from a dormant repo is not a dependency a consensus client can carry | `lmdbjava` is published and is in the bake-off; it loses only if its measured properties lose |
| An append-only segment store we own | Immutable chain data must not compete with state for engine pages at gigablock volumes (DC-13); reth and Erigon both converged on this | keeping chain data in the state engine is the status quo being measured against |
| An optional networked cold store | Logs over long ranges, witnesses and aged history are scans, not point reads (research §13); the hot tier should not carry them | files only (era + witness files) is the GT-3 alternative and may win |
| Two engines compiled in for one minor release | Operators need a build that opens both layouts to plan a resync; CI needs both for SC-001 | a hard cut would leave no differential oracle and no safe rollback for a live Sepolia node |

## Phase 0 — Research and measurement (after spec 016; off the node host)

Outputs go into `research.md` §12 and the two ADR drafts. None of these merge code.

| ID | Item | Decides |
| --- | --- | --- |
| R-1 (GT-0) | Attribution spike: read wait under `dbLock` vs engine stall time, then with a close-fence only | how much the engine swap buys vs. the lock fix (OQ-7) |
| R-2 (GT-1) | Bake-off harness (FR-021) against the DC-12 typed API; one adapter per candidate (research §12) on throwaway branches: libmdbx via FFM, LMDB via `lmdbjava`, RocksDB + close-fence, Xodus and MVStore if they bind in a day; workload = fixed Platåberget and Mordor ranges through the flat model at 1×/10×/30× block gas with a read probe and bounded long readers; x86_64 + aarch64 | the engine (user decides on the report); INF-007 |
| R-3 | DC-14 baseline on today's engine (Mordor + Platåberget datadirs): p50/p99 `[IMPORT-TIMING]`, longest read wait, bytes written per block, DB size, RSS, cgroup `memory.current`, `[STORAGE-PERF]` | the numbers every slice reports against |
| R-4 | Footprint estimate without compression: sample the `n`, `a`, `d`, `t`, `u` CFs' raw vs compressed sizes; model the unified layout's size (no stored ref counts, nodes stored once) | SC-005 feasibility; OQ-2 |
| R-5 | Prior-art read: Besu Bonsai trie-log + flat DB, reth changesets + static files + sparse trie (Reth 2.0's cross-block cache, partial proofs), Erigon domains/ETL/.seg and reader discipline, geth PBSS diff layers and Pebble; record in INF-008 draft | the changeset, flat-key and segment formats (data-model.md), the sparse-trie design (FR-023), DC-5 operational rules |
| R-6 | 017 DC-8 amendment draft (DC-7): how reth builds `debug_executionWitness` from the hashed post-state; what the key-set recorder must capture (accounts, slots, code, ancestors, system-call reads per 017 DC-13) | T-006; SC-007 test design |
| R-7 | Memory accounting under cgroups v2 for mapped files; `-Xmx` formula; CL-client co-tenancy (OQ-8) | DC-10 formula in quickstart.md |
| R-8 | Resync timings: Mordor and ETC mainnet from a checkpoint archive; Sepolia from spec 013 snapshot or SNAP; chain-data download alone (OQ-4) | the ETC runbook (DC-9); OQ-4 |
| R-9 (GT-3) | Cold-tier assessment: which RPC and operator needs the hot tier cannot serve within the pruning window; cost of a ClickHouse process on the 16 GiB class or a second host; files-only alternative (era + witness files); `clickhouse-java` fitness | go / no-go on the ClickHouse adapter; the `ColdStore` seam ships either way |
| R-10 | Gigablock workload design (OQ-12): concatenated real deltas vs uniform-random keys; the host class for the 30× profile (DC-10) | the harness workload; SC-012 targets |

Phase 0 exit: GT-0, GT-1 and GT-3 reported to the user; the engine chosen; R-3 baseline and SC-012 targets published in
`research.md` §15; INF-007 drafted; `/speckit-clarify` run on the OQs.

## Phase 1 — Design artifacts (before Track E code)

- `data-model.md`: sub-store list and key encodings (unchanged for every kept namespace, so golden-bytes hold);
  changeset record format (block number → sequence of (namespace, key, previous value | absent)); segment file format
  and index; access-set record; sparse trie cache structure; `StorageMetrics` gauge list; engine config keys with
  defaults; `ColdStore` table schemas if GT-3 says go.
- `contracts/`: the post-cleanup `DataSource` trait and the DC-12 typed transaction API as the contract (FR-003,
  FR-026), `StorageMetrics`, `ColdStore`, the `StorageScheme` deprecation behaviour (DC-9), the datadir layout
  detection message.
- `quickstart.md`: per-chain resync procedure, sizing formula (DC-10), operator checklist, rollback to RocksDB during
  the transition window.
- GT-2: constitution amendment + INF-007 merged.
- Re-run the Constitution Check.

## Phase 2 — Track E: the engine (after spec 017 completes)

Each slice is one PR, behaviour-neutral, with the DC-12 figures in the PR body and SC-001 green.

| Slice | Content | Owner / review |
| --- | --- | --- |
| E1 | Contract cleanup (FR-003, FR-026): `IterationError` to the trait; `seekFrom`, `approximateKeyCount`, `iterateSyncRange` on the trait with `EphemDataSource` impls; the three downcasts removed; `compactRange` documented no-op where no compaction; `StorageEngine` enum; the DC-12 typed transaction API with RocksDB and Ephem behind it; `StorageMetrics` seam with the RocksDB impl behind it | vault; prism; mithril (opaque types, inline codecs) |
| E1b | Segment store (FR-022) for headers, bodies, receipts, behaviour-neutral, golden-bytes kept; `BlockHeadersStorage`/`BlockBodiesStorage`/`ReceiptStorage` move behind it | vault; prism |
| E2 | The chosen engine's `DataSource` (get, multiGet, scanRange, update, updateSync, deleteRange, iterate as bounded windows) + component + config; vendored source and FFM bindings if that is its route; `DataSourceTestBehavior` green on three engines | vault; prism; security note in PR |
| E3 | Durability mapping (DC-3): `StateAccess` synced commits; `StateIngest` relaxed mode behind `StateIngest.Durability` (adds the second enum value 016 T056 reserved); the DC-1 barrier before `completeSnapSync` on both chains; crash test | vault; forge sign-off (ETC path) |
| E4 | Reader discipline (DC-5): bounded read txns everywhere `iterate`/`scanRange` are used; oldest-reader gauge + WARN; injected-unbounded-reader test | vault; flow (fs2 `Stream` boundaries) |
| E5 | Close-fence replaces `dbLock` (DC-4) on the new engine; SC-004 saturating-write test; the 8-thread read-throughput figure (DC-13); if OQ-7 says yes, the same fence on RocksDB as its own earlier PR | vault; eye |
| E6 | Two-engine import differential in CI (FR-009) on fixed Mordor and Platåberget ranges; ethereum/tests on the new engine | eye |
| E7 | Operator docs and skills (FR-018) including the ETC checkpoint resync runbook (DC-9); default flip for fresh datadirs; old-layout detection message | vault; conduit for RPC docs; forge for the ETC runbook |
| E8 | Soaks (24 h Platåberget, 24 h Mordor); SC-003..SC-006, SC-009 reported | eye |
| E9 | RocksDB removal (DC-11, FR-017), one minor release after E7 | vault |

Track E exit: SC-001, SC-003, SC-004, SC-006, SC-009 green on both chains; the 1× column of research §15 filled for the
chosen engine.

## Phase 3 — Track F: the flat data model (after Track E's SC-001)

| Slice | Content | Owner / review |
| --- | --- | --- |
| F1 | INF-008 layout ADR accepted (records the user's 2026-10-17 decision that ETC moves onto the unified layout); `data-model.md` changeset and sparse-trie formats final | user; vault |
| F2 | `ChangesetStorage` + `StateStorage` rollback/prune over changesets; `ReferenceCountNodeStorage` retired behind the same `StateStorage` API; golden-bytes updated only for the retired formats; abandon/readopt semantics preserved (016 edge cases) | vault; forge (ETC rollback semantics) |
| F3 | Path-aware node resolution in `mpt/` (FR-012): `(path, hash)` lookups with hash verification; `MptStorage` keeps `get(hash)` for witness replay (017 FR-005's `WitnessMptStorage` is hash-keyed) | **forge + beacon before implementation** (consensus-change protocol); wraith; eye with ethereum/tests |
| F4 | SNAP ingest on the unified layout (FR-015): nodes written once path-keyed, leaves once flat; `PathToHashExporter` retired; the Hash scheme retired and ETC on the unified layout (DC-9, FR-016); 016 FR-044 property re-expressed as E3's barrier; 016 ratchet unchanged; Mordor resync rehearsed (SC-013) | vault; herald (serving unchanged); **forge** (ETC SNAP, mining, rewards) |
| F5 | Key-set recorder + proof walk (FR-013, DC-7) behind a flag; SC-007 byte-equality against 017's recorder on the differential set; BAL self-check; 017 docs amendment (T-006) approved | beacon; conduit (RPC unchanged); vault |
| F6 | Flat execution reads (FR-011) under `StateAccess`: `getAccount`/`getStorage` from flat stores; trie updated from the write set at commit; SC-001 under flat reads; `[IMPORT-TIMING]` compared to Track E | **beacon + forge**; eye |
| F6b | Parallel state root with sparse trie cache (FR-023, DC-13): modified-subtree hashing across independent account and storage subtrees keyed by the BAL (ETH) or the recorded write set (ETC), cache persisting across blocks, invalidated to the common ancestor on reorg; behind a flag; byte-identical roots (SC-001); SC-012 serial-remainder figure | **beacon + forge before implementation**; eye with ethereum/tests and the harness at 30× |
| F7 | Default on for flat reads and the parallel root once F5, F6 and F6b are green; `storage-scheme` deprecated (DC-9); `TrieReadMemo`, `PrefetchNodeReader` and `DecodedNodeCache` re-evaluated against the sparse trie cache (OQ-11) | vault; prism |
| F8 | Soaks repeated; the harness at 10× and 30×; SC-002, SC-005, SC-007, SC-008, SC-012, SC-013 reported; pruning modes decided (OQ-3, OQ-5) | eye |

Track F exit: SC-001..SC-013 green; `.claude/agent-protocols/storage-rocksdb.md` replaced by `storage.md`; CLAUDE.md's
"Modules" and "vault" rows updated.

## Phase 4 — Track H: the cold tier (after GT-3; beside Track F; never gating E or F)

| Slice | Content | Owner / review |
| --- | --- | --- |
| H1 | `ColdStore` seam (FR-024): async, bounded queue with depth and drop metrics; `NoopColdStore` default; writers for receipts/logs, witnesses (017 FR-004), aged changesets | vault; flow (queue and backpressure) |
| H2 | Era/era1 export of history as the file-based cold format (spec 013 §5.6, #1483) | vault; herald (eth/69 `earliestBlock` advertisement) |
| H3 | ClickHouse adapter (FR-025) if GT-3 says go: schemas in `data-model.md`, `clickhouse-java` client, operator config, Docker compose example | vault; prism; security note (network egress from the node) |
| H4 | `eth_getLogs` routing beyond the hot window (FR-025); rpc-compat and graphql unchanged without a cold store (SC-014) | **conduit**; eye |

Track H exit: SC-014; the node with no cold store is byte-identical on every RPC fixture to the node before Track H.

## Risks and rollback

| Risk | Signal | Response |
| --- | --- | --- |
| The leading candidate cannot be bound or loses the bake-off | GT-1 | the harness ranks the rest; RocksDB + flat model + close-fence is the floor and is always available |
| The parallel state root diverges from the serial walk | SC-001 under FR-023 | flag off; the serial walk stays the producer; `beacon`/`forge` review the subtree partition |
| The 30× profile does not fit any host we have | R-10 | state the host class; report 10× as the gate and 30× as measured headroom |
| Cold-tier process competes with the node on the 16 GiB host | GT-3 | files-only alternative; or a second host in the runbook |
| Long readers grow the file | oldest-reader gauge, file size gauge | DC-5 rules; E4's test; the WARN names the origin |
| Disk footprint exceeds SC-005 | R-4 model, E8 soak | shorter keys in flat stores; drop the Path CF duplication earlier (F4 before E8 if needed) |
| Page cache vs JVM heap contention on 16 GiB | DC-10 gauges, E8 | lower `-Xmx` guidance; `MDBX_NORDAHEAD`; measure `WRITEMAP` |
| Witness divergence under flat reads | SC-007 red | flat reads stay off; 017's node recorder stays the producer until fixed |
| ETC operator resync cost | R-8 | decided by the user (DC-9); the runbook, release notes and the checkpoint archive make it hours; SC-013 rehearses it on Mordor |
| Transition window with two engines | CI time | the differential job runs on `db/` changes only |

Rollback at any Track E slice: set `db.data-source = "rocksdb"` on an old-layout datadir (both engines compile in
until E9). Rollback at any Track F slice: flat reads, the key-set recorder and the parallel state root are flagged off
by default until F7. Rollback at any Track H slice: `NoopColdStore`.

## Specialist routing

- `vault`: everything under `db/` (engine, segments, cold seam); owns INF-007 and the storage protocol rewrite.
- `forge` (ETC) and `beacon` (ETH): F3, F6, F6b before implementation; E3, F2, F4 sign-off; both review F5.
- `herald`: SNAP serving from flat state is unchanged (F4); eth/69 history advertisement (H2).
- `flow`: fs2 `Stream` boundaries for bounded readers (E4); cold-tier queue and backpressure (H1).
- `conduit`: `debug_executionWitness` unchanged (F5); RPC docs (E7); `eth_getLogs` routing (H4).
- `mithril`: the DC-12 typed API (opaque types, `inline` codecs, enum config) in E1.
- `eye`: every slice's validation; the differential job; the harness runs; soaks.
- `prism`: non-consensus review of E1, E1b, E2, F7, H3.
- `wraith`: compile fallout from the trait cleanup and the `mpt/` signature changes.
