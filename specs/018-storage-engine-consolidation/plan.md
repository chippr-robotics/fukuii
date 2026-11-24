# Implementation Plan: Storage Engine Consolidation

**Branch**: `018-storage-engine-consolidation` | **Date**: 2026-10-17 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/018-storage-engine-consolidation/spec.md`; evaluation in
[research.md](./research.md).

**Order of the tracks.** **Track C** (Phase 1b), the unified path-live layout for both chains, runs first, after 0.11.0,
on RocksDB, ahead of Track E and of spec 017 (spec 017 is parked until execution-specs releases `ExecutionWitness`;
only items 7 and 9 depend on it). Track F's items 6 and 8 and the copy and Hash-scheme retirement of item 10 are in
Track C. Phase 0 is research off the node host; Track E follows Track C and GT-1 (the engine is chosen by a measured
bake-off); Track F follows Track E's SC-001; Track H follows GT-3; Track R follows Phase 0. Under the user's standing
rule (one infrastructure for both chains, no staged or release-level transitions, no external users of old versions)
this plan has no transition windows. How each of these was decided is in the "Decision log" at the end.

**Companions**: [data-model.md](./data-model.md) (Track C's formats and design record) and
[implementation-log.md](./implementation-log.md) (the dated build record of Track C: the S0 reviews and every slice's
and part's record, moved out of this file on 2026-11-21).

## Status (2026-11-21, staging `ad7c768354`, 0.12.76)

**Track C: code for C1 to C6 is merged; C7 (the soak and exit gate) is in progress; C8a and C8b have not started.**
Tracks E, F, H and R and Phase 0 (GT-0 to GT-3, T-000 to T-013 of spec.md) have no merged code; `dbLock` is still a
read/write lock, so T-010 (the close-fence, OQ-7) has not shipped.

| Slice | Backlog | State | Merged PRs (merge date) |
| --- | --- | --- | --- |
| C0 | B-053 (open) | in progress: docs and measurements | #1729, #1732 (11-07); #1794 (11-15, live Mordor check of C1, `baselines/m7-c1.md`); #1888 (11-17, oracle jar pinned at v0.12.1, `baselines/pinned-jar.md`) |
| C1 | B-054 (done) | done; jar gates open (B-203) | #1788, #1790 (11-10) |
| C2a | B-055 (done) | done; jar and ethereum/tests halves open (B-203, B-207) | #1791, #1792 (11-11) |
| C2b | B-056 (done) | done; T052's jar half open (B-212) | #1796, #1797, #1798, #1799 (11-11) |
| C3a | B-057 (done) | done | #1793, #1795 (11-11) |
| C3b | B-058 (done) | done; recorded values and figures open (B-203) | #1800 (11-11) |
| C3c | B-059 (done) | done, linear subset (decision of 2026-11-12); B-219 done (#1901), B-220 open | #1801–#1804 (11-11); #1805 (11-12) |
| C4a | B-060 (done) | done | #1806–#1809, #1811, #1813, #1815 (11-12) |
| C4b | B-061 (done) | done | #1816, #1818 (11-12); #1824–#1829 (11-13) |
| C4c | — | withdrawn (`inmemory` retired, 2026-11-06) | none |
| C4d | B-062 (done) | done; T156's crash test open (B-255) | #1830–#1832 (11-13); #1833, #1838, #1840, #1841, #1843 (11-14) |
| C4e | B-063 (done) | done, with its carried gates B-293, B-295, B-296, B-297, B-300 | #1846 (11-14); #1849–#1853 (11-15); gates #1872, #1874, #1876 (11-16), #1882, #1908, #1909 (11-17) |
| C5a | B-064 (blocked) | code done; T209 and EG-4 (fresh Sepolia sync plus 24 h) open | parts 1 to 7b #1855–#1864 (11-15); #1865–#1876, #1878, and #1880 (part 10, refusals lifted) (11-16); follow-ups #1887, #1896–#1898, #1900, #1910 (11-17), #1922 (11-18). v0.12.0 was cut after part 10 (B-312) |
| C5b | B-065 (done) | done; B-373, B-375 open | #1921, #1929, #1930 (11-18); follow-ups #1945, #1947, #1950 (11-18), #1948 (11-19), #1962, #1963 (11-20) |
| C5c | B-066 (done) | done; B-377, B-379, T233 (M9), T234 (user) open | #1931, #1932, #1935 (11-18); follow-ups #1939, #1944 (11-18), #1976 (11-20) |
| C6 | B-067 (done) | code done; live gates in B-393 | #1936, #1937, #1938, #1940, #1942 (11-18); follow-ups #1943, #1946, #1949 (11-18), #1965, #1966, #1971 (11-20), #1977 (11-21) |
| C7 | B-068 (open); B-393 (in progress) | in progress: live gates | #1969, #1972, #1974, #1975 (11-20: T248's results and `baselines/c7-gate-report.md`). No soak build cut (T250) |
| C8a | B-069 (open) | not started; waits for C7 and EG-12 | none |
| C8b | B-070 (open) | not started; waits for C8a | none |

**What remains**, by what it waits for:
- **C0** (B-053): the jar's keeper and its NFS path (user), and T003 to T007, T009, T011 to T013, T016, T017: the fixed
  ranges' recorded roots, the hive and EEST baselines on the pinned jar, M1 and M14, beacon's sign-off of the EIP-8246
  read (T008), and the size estimate set again (T016).
- **Jar comparisons of done slices**: B-203 (T025, T033, T034 and the other DC-14 figures), B-207 (T026), B-212 (T052),
  B-375 (T214), B-381 (T238). Each needs the pinned jar; B-203 and B-381 also need a live host.
- **C7 live gates** (B-068, B-393): T248 passed on dev-node on 2026-11-20 (the Mordor rehearsal passed every node-side
  criterion); the Mordor half of EG-6 has run on dev-node since 2026-11-21; EG-12 (T264) waits for the NVMe drive
  (user, 2026-11-20); EG-6's private Olympia net was skipped (user, 2026-11-20); EG-4 (B-064), EG-9, EG-14 and the other
  EG rows have not run. Also live: B-073 (Mordor gate runs and the archive to the NAS), B-255 (T156), B-314 (hive engine
  re-org suites with the guard on), B-379 (pinned checkpoint hashes, from EG-9 and EG-12 archives).
- **C8a** (B-069), after C7 and EG-12; **C8b** (B-070), after C8a. B-292 (fault injection for reverse-then-commit at the
  cap) is to land before the guard goes.
- Code follow-ups that need no host are listed in the backlog (spec 018, area storage/sync).

**The guard.** `fukuii.state.path-live` is **`false` by default** (`src/main/resources/conf/base/state.conf`) in every
release so far, including v0.12.0 and the pinned oracle v0.12.1: the unified layout runs only on nodes and harnesses that
set it (the C7 gate runs, the hive switch of #1910). It never becomes an operator default: C8b deletes the key with the
Hash-scheme code, after which the unified layout is the only layout (see "Order" under Phase 1b).

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

**Language/Version**: Scala 3.9.0 (3.9 LTS) on JDK 25; sbt 1.13.0 (`build.sbt`, `project/build.properties`; the move
from 3.3.8 is spec 020, `specs/020-scala-3-9-lts-migration/`, as the LTS audit of 2026-10-17,
`docs/reports/LTS_TOOLCHAIN_AUDIT_2026-10.md` R-1 and R-2, recommended). The bake-off harness and every Track E/F/H
slice target 3.9; the JDK 25 FFM API is unaffected. JDK 25 is the current LTS and stays.

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
| Two engines compiled in, within Track E only | CI needs both for SC-001's differential while Track E is in development | the "one minor release" operator window is removed under the user's standing rule: no external operator relies on old datadirs. RocksDB is removed in the same track once SC-001 and the soaks are green |
| A pinned pre-track jar as the oracle (Track C) | Track C deletes the Hash scheme, so no in-tree build can serve as the roots-equal oracle after C8 | keeping the Hash scheme in tree as an oracle would keep two layouts, which the standing rule rules out |

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
| R-11 (FR-028) | RPC read-load arm: provision Geth, Besu, Erigon and Reth on the same hardware and synced range; fixture ranges (OQ-14); the trace/log/call workload at concurrency 1/8/64 with and without import; interference metrics | SC-015 baseline; the only basis for any external performance statement |
| R-12 | *(moved to spec 021 GT-1)* The `UInt256` impact analysis is spec 021's first gate; this plan only schedules the SC-016 re-measurement after 021 lands | SC-016 timing |

Phase 0 exit: GT-0, GT-1 (both arms) and GT-3 reported to the user; the engine chosen; R-3 baseline, SC-012 and SC-015
figures published in `research.md` §15; INF-007 drafted; T-010 (close-fence) merged on RocksDB; `/speckit-clarify` run
on the OQs.

## Phase 1 — Design artifacts (before Track E code)

- `data-model.md`: sub-store list and key encodings (unchanged for every kept namespace, so golden-bytes hold);
  changeset record format (block number → sequence of (namespace, key, previous value | absent)); segment file format
  and index; access-set record; sparse trie cache structure; `StorageMetrics` gauge list; engine config keys with
  defaults; `ColdStore` table schemas if GT-3 says go.
- `contracts/`: the post-cleanup `DataSource` trait and the DC-12 typed transaction API as the contract (FR-003,
  FR-026), `StorageMetrics`, `ColdStore`, the `StorageScheme` deprecation behaviour (DC-9), the datadir layout
  detection message.
- `quickstart.md`: per-chain resync procedure, sizing formula (DC-10), operator checklist, rollback to RocksDB during
  Track E (both engines compile in only within that track).
- GT-2: constitution amendment + INF-007 merged.
- Re-run the Constitution Check.

## Phase 1b — Track C: the unified path-live layout (after 0.11.0; before Track E and spec 017)

**Status**: C1 to C6 are built and merged; C7 is running its live gates; C8a and C8b have not started (see "Status" at
the top). The design passed two S0 review rounds before code (`beacon` for ETH, `forge` for ETC): round 1 (2026-11-06)
resolved 3 REJECTs, round 2 (2026-11-07) had none, and every SIGN WITH CHANGES of both was applied; each reviewer verified
the applied changes at the owning slice's PR (the records are in [implementation-log.md](./implementation-log.md)).
Written by `vault`. Companion: [data-model.md](./data-model.md) (formats, overlay, design record A to I). The design was
first drawn against `wt/plan-018-021` at staging `49dd46d1b` (v0.10.47); file:line references in the slice tables are
to that tree unless a later date is given. Where a slice's build differed from the first design, the slice sections
below and data-model.md state the design as built; how each difference was reached is in the implementation log.

Track C runs first, after 0.11.0, on RocksDB with today's `DataSource`, ahead of Track E and of spec 017. It depends on
neither, nor on GT-1 or the constitution amendment.

### The rules this plan is built to (user, 2026-11-06)

- **One infrastructure, both chains.** ETH is the build target and ETC comes along. Item 10 is one gate: ETH, Sepolia,
  Platåberget **and ETC (Mordor, mainnet)** move onto the same path-keyed layout (nodes in place, changesets, overlay,
  write-through) in this track, and the **Hash scheme is retired in it**: CF `n` as live state, `ReferenceCountNodeStorage`,
  `ReferenceCountedStateStorage`, `CachedReferenceCountedStateStorage`, `storage-scheme`, `PathToHashExporter`. There is no
  10a/10b split. Consensus rules still differ per chain by config (`forBlock` versus `forTimestamp`); only the storage
  infrastructure is shared.
- **No legacy-datadir machinery.** One layout marker. A datadir without it, on either chain, is refused at start with a resync
  message unless it is empty. No adoption, no migration, no legacy reader, no finished-copy versus mid-copy detection.
- **No release-level transition.** No flag ships default-off for a release and flips later; no "one minor release" windows.
  The only flag is an in-development guard that keeps staging green between PRs and is deleted, together with the Hash-scheme
  code, in the last slice.
- Kept: Option Y, write-through (no journal), the changeset record, checkpoint import funded and required, T064's intent on a
  fresh datadir, the overlay budget left to M1 and M2. **Two pruning modes on both chains, `basic` and `archive`: `inmemory` is
  retired** and rejected at start on both chains with a clear message, with no deprecation window (#1739, 2026-11-07).

### Summary

| Item | Slices | What it delivers |
| --- | --- | --- |
| 8, path-aware node resolution | C1, C2a, C2b | The trie reports positions; a position store resolves by `(owner, path)` with hash verification; every reader can be handed it; the typed `StateFault`s exist |
| 6, changesets | C3a, C3b, C3c, C4a, C4b, C4d, C4e (C4c is withdrawn; its id is not reused) | The `BlockDiff` record and CF `v`; the in-place canonical commit under the state lock; the proposer view, the bypass writers and the ported test harnesses; the overlay; head moves planned from the anchor; the PoS arms (Engine API, local recovery, finality-aware retention) and the PoW arms (pinned branches, `SYNC-FORK`, result mapping) |
| 10, one gate | C5a, C5b, C5c, C6, C7, C8a, C8b | Hand-off without a copy, with the ancestor window; the layout guard; the audit, on-demand fetch and recovery scans by position; the checkpoint v2 format and importer; ETC enablement and mainnet readiness; the soak and exit gate; then deletion of the exporter and the Hash scheme and removal of the guard flag |

**Order.** Item 6 lands before item 10, as decided. Because C3 and C4 cannot be soaked on a live chain without a hand-off that
does not publish, the hand-off (C5) is built behind the same in-development guard before the soak (C7). The guard
`fukuii.state.path-live` is `false` by default while slices land, selects the unified layout when `true`, and is removed in
C8b; it is never an operator option and never flips to `true` as a default: releases cut during the track (v0.12.0 onward)
carry it at `false`, the soak and the gate runs set it on, and after C8b there is no flag and no second layout. **The harness port (C3c) is
before every slice that claims a roots-equal check**, because a gate that ran on the Hash-scheme harnesses would prove nothing
about the position store (S0 item 14).

**The oracle.** The Hash scheme is deleted in this track, so no in-tree build can serve as the differential oracle after C8.
A release is **pinned as a jar outside the tree** (SHA-256 recorded): **v0.12.1** (the user's choice of 2026-11-17; it holds
C1 to C5a with the guard off, so it runs the Hash scheme), with v0.11.0, the last pre-track release, as the fallback
(`baselines/pinned-jar.md`). With it go recorded roots for the fixed Mordor, Sepolia and Platåberget ranges, **and the ETH
hive and EEST baselines (pass lists per suite)** (C0, still to record), and the jar is kept until
EG-12 passes and the first ETC v2 archive has been re-imported on a second datadir, and at least one release past C8b. Every
"roots equal" check below is against it. For ETC mainnet there is no pre-state to record: its oracle is the chain itself,
through an import from genesis validated block by block against the canonical headers (EG-1).

### Slices

Sizes are rough, in lines, main code / tests. A slice that would exceed about 1,500 lines of main code is split. After S0
round 2 the additions total about **21,200 lines (about 11,050 main, 10,140 tests)**; the retirement of `inmemory` and the Hash scheme removes about 6,800 (about
3,300 main, 3,500 tests) and migrates about 700 more test lines. Round 2 added about 390 main and 570 test lines (round 1 plus the `inmemory`
retirement had brought the figure to about 20,200): the no-anchor refusal and the reset-owner prior (C3b, +60 / +110), the
MockedMiner wait (C3c, +20 / +40), `saveLastBlock` and the reverse-depth refusal (C4b, +60 / +100), the escalation trigger,
hysteresis, cool-down, loop guard and ordered clear (C4d, +150 / +180), the generation pause (C4e, +30 / +40), the symmetric
guard (C5a, +10 / +20) and the ancestor-window chaining plus the genesis-anchor clear (C5c, +60 / +80). The first estimate was about 15,000; the increase is what the
reviews priced and the first draft had not: the ported harnesses (C3c), the by-hash in-block layer, the state lock and the anchor
compare-and-set, per-block reset tracking, local recovery, the PoS and PoW arms split out of one slice, the
ancestor window, and the larger checkpoint format. Research 17's "about 1,100 for Option X plus 1.5-2.5k for item 6" priced
none of it. (`inmemory` was costed at about 450 main and 400 test lines in C4c and is withdrawn.) These are estimates, not
measurements: M1 to M15 (data-model 13) replace the guesses, and the sizes have not yet been set again against what merged
(T016, C0, open).

| Slice | Content | Owner / review |
| --- | --- | --- |
| **C0** | **S0**: rounds 1 and 2 are done (records in [implementation-log.md](./implementation-log.md#s0-c0-the-review-records)). Golden vectors 1 to 3 (verified independently by both reviewers) and the oracle tests frozen; the pre-track jar pinned with the hive and EEST baselines and the fixed ranges' roots recorded; the **EIP-8246 text read from an EIPs clone, and the pass count of the 658 `eip8246_selfdestruct_no_burn` EEST fixtures recorded** (on the pinned jar's harness now, on the ported harness at C3c); the source of the ETC 0-5.1M blocks named (it gates C7, not code; named 2026-11-07: this project's own ETC sync and export, T010); M14 measured; M1 reports the largest observed diff against `max-record-bytes`. Docs and measurements only | vault authors; the reviewers have signed (no round 3); user accepts the remaining open items |
| **C1** | Item 8a. `NibblePath`; position threading through `MerklePatriciaTrie.put`, `remove` and `fix`; reporting of replaced and absorbed nodes only; the first-event prior rule; `MptTraversals.collapseTrieAt`; the additive `MptStorage` members (`getAt`, `forOwner`, `resetOwner`, `updateNodesAt`, `rawNodesAt`); `MptStorageDecorator`. Consensus byte-identical on both chains. **M7 on Mordor within the spec 007 allocation budget is a merge gate** | vault; **forge and beacon before code**, both review the PR; wraith; eye |
| **C2a** | Item 8b. The position store's read side: `PathMptStorage` (verifying, owner-scoped), the stale / corrupt / absent classification, the `StateFault` hierarchy, the anchor-only `StateView`, `PathStateStorage` read members, owner threading in `InMemoryWorldStateProxy` **and in the genesis loader's storage tries**, `PathPrefetchReader` over `view(parent)`, the seven implementors on the decorator base. Wired behind the in-development guard | vault; beacon (execution read path, BAL prefetch); forge (shared `InMemoryWorldStateProxy`, genesis); prism |
| **C2b** | Item 8c. The readers: `Blockchain` and `BlockchainReader` state reads, `eth_getStorageAt` / `eth_getProof` / GraphQL signatures, the four readers the first table omitted, `StxLedger` and simulate, SNAP serving by root view and the served-root window clamp, checkpoint export (v1 here), the four recovery walkers, the hash-only mpt walkers, the **allow-list grep ratchet** on the four state accessors, `GetNodeData`, RPC stale-view retry | vault; **conduit** (RPC signatures); **herald** (SNAP serving); beacon; eye |
| **C3a** | Item 6a. `BlockDiff`, `BlockDiffCodec` (structure-only rejections, the incomplete tombstone), `ChangesetStorage` (CF `v`, height index), `StateChangesetNamespace`, config keys. The format is a one-way door: golden vectors 1 to 3 signed before merge | vault; prism; **forge and beacon sign the golden vectors** |
| **C3b** | Item 6b. `PathStagedState` with the **by-hash in-block layer** and the first-event prior; per-block reset tracking and the prefix-scan enumeration of a replaced storage trie with its bound and tombstone; **the state lock, the anchor compare-and-set, the epoch and the `StateMove` publication for the canonical commit**; `buildExtra(adopted)`; the three already-applied cases; **an absent anchor refuses every in-place writer**; `isBlockApplied` and `stateAvailable`; `stageBlock(parent, bn)`; the `basic` and `archive` modes; `executeBlock(staged = None)` throws; **a reset owner's priors come from the prefix scan** | vault; **beacon and forge before code**; eye |
| **C3c** | Item 6b'. The **proposer view** for every proposer path (PoW generators, `eth_getWork`, `NoOmmersBlockGenerator`, `test_mineBlocks`, the Engine API builder, MockedMiner chaining **and its wait for the import**, the cached-world rule); the **bypass writers** (`ChainImporter` lenient and strict, test mode); the **genesis commit** (owner-scoped, empty alloc, refusal of non-empty storage when `ethCompatibleStorage = false`); the **port of the harnesses** (`EthereumTestHelper`, `EthereumTestExecutor`, `ForkChoiceBlockchainTestsSpec`, `EestBlockchainReplay`, `RpcCompatNode`) | vault; **beacon and forge before code** (proposer, genesis); conduit (RPC harness); eye |
| **C4a** | Item 6c. `StateOverlay`: forward layers, the reverse index, the full `StateView`, move-log reads, eviction, **pinned layers**, metrics | vault; beacon (isolation); forge reviews; prism |
| **C4b** | Item 6d. The **move core**: `HeadMove` planned from the anchor's records, `HeadMovePrepared` carried into `promoteToCanonicalHead` and `rewriteCanonicalIndex`, chunked deep moves, startup reconciliation I1 and the index check I6, the sampled post-move check, the `ConsensusStateFault` results and `Consensus.isLocalStateFault`; **`saveLastBlock` a no-op on a path-live store and `executeAndValidateBlocks` stopping at the first commit that no longer extends the anchor; the refusal on reverse depth with `reorg.max-depth` defaulting to the retention floor** | vault; **beacon and forge before code**; eye |
| **C4c** | **Withdrawn: the `inmemory` mode is retired** (user, 2026-11-06). Its work (the undo ring, the persisted tail, the clean-shutdown flush, the restart-reach rules, the mode's durability tests) is not done. What the remaining modes needed from it is already elsewhere: the state-move lock and the crash matrix (C3b, C4b), the finalization durability test (C5a). The id is not reused, so the references to C4d and C4e stay valid. The mode's rejection at start (a one-line config check with a test) is part of C5a | n/a |
| **C4d** | Item 6f, **PoS arms**. `forkchoiceUpdated` ordering; `StateRecovery` (local re-execution); the Engine API answer table and the INVALID layer drop; finality-aware retention. **Built in C5a instead** (the user, 2026-11-14): the designated-head arm went to C4e, and **the escalation route: a direct `StateEscalationRequested` message to `SyncController` with PoS hysteresis (64 consecutive refusals), the cool-down and loop guard, the ordered clear (one synced batch with `clearSnapSyncDone`, then CF `v`, `t`, `u`, with a `state.reset-pending` key)** went to C5a. Also: builder stale-view retry | vault; **beacon before code**; forge reviews the shared `ConsensusImpl`; eye |
| **C4e** | Item 6g, **PoW arms**. `reorganise` with pinned branch layers and the up-front refusal **on reverse depth, not branch length**; `SYNC-FORK` moves no state (the user, 2026-11-15: the fetcher restarts from the common block and the reorganisation judges the branch by weight, so there is no rewind to clamp); the PoW result mapping; `fork.parentSideChain` re-execution; `REORG-PARTIAL` layer reuse; the designated-head arm (from C4d); `archive` moves unbounded and chunked, **with block generation paused for the move** | vault; **forge before code**; beacon reviews; eye |
| **C5a** | Item 10 (hand-off). Hand-off without a publish (both chains); `PathHandoffSample`; `StateLayoutGuard` (one marker, one rule, **refusing a marked datadir too while the in-development flag is off**); the synced finalization commit carrying the anchor; the genesis clear and `HealInvariant.isEmpty`; **`AncestorWindow.ensure` (256 headers, up to 5 bodies after B-302) as a hand-off precondition**; **`pruning.mode = "inmemory"` rejected at start on both chains** (done ahead of the track by #1739, 2026-11-07); **the escalation on both chains** (from C4d and C4e: trigger, hysteresis, cool-down, permanent PoW loop guard, the ordered clear); the PoS catch-up after a hand-off as an in-place adoption; **the SNAP and sync refusals on the guard lifted** (part 10) | vault; beacon; forge (ETC SNAP wiring, ancestor window); eye; the 016 ratchet stays green |
| **C5b** | Item 10 (audit and recovery). The audit, `repairNodeAt` / `deleteBadNodeAt` (position-scoped, with a real `stillReached`), both on-demand writes of the importer, the recovery scans | vault; beacon; forge (PoW importer path); eye |
| **C5c** | Item 10 (checkpoint), **required**. Checkpoint format version 2, **byte layout pinned 2026-11-18 (B-066; data-model E.1 table)**. The header block binds the chain id (u64), the genesis hash and a TD-exact flag. The ancestor window comes right after it: headers **chained by hash to the checkpoint header**, then on ETC only B-302's bodies (`C..C-4` with ommers) **matched to their headers**. Then nodes (no hash), codes sorted by hash, the end tag and CRC32C. Also: the trust checks (expected hash pinned per public chain; an estimated TD accepted with a WARN; PoS finalized-only, TD >= TTD) and the **five** importer assertions with code completeness at threshold 0. **Vault's mark-first clear** comes before begin, and the final batch goes through `commitHandoff`. A failed or killed import is cleared and **not retried**. The golden streams; version 1 refused. **Three PRs, one release:** PR1 codec, exporter and golden streams (the format is signed); PR2 importer; PR3 atomicity, no-retry, config refusal lifted, docs | vault (PR3); **forge and beacon sign the format at PR1** (one-way door); eye |
| **C6** | **ETC enablement and ETC mainnet readiness.** The ETC SNAP guard (`SyncController.scala:476-480`) and Hash wiring bypassed under the guard; the peer precondition and its logging; the PoW-specific hand-off checks; the private ETC-config PoW net and its scripts; the ETC reorg matrix; the rewards and era-boundary differential harness; the EG-12 run harness | vault; **forge signs the ETC side before code**; eye |
| **C7** | **Soak and exit gate** (below), run with the guard on. **C7 runs EG-12.** No code | eye; user |
| **C8a** | Retirement 1. Delete `PathToHashExporter` and the publish plumbing (destructive block below), `StorageScheme` and every Hash branch in `snap/`, the `PathPublish*` messages and their specs. **Merges only after EG-12 passes** | vault; beacon; forge (ETC SNAP); prism; the 016 ratchet |
| **C8b** | Retirement 2, **the last slice.** Delete the Hash-scheme storages in `db/`, `getBackingStorage` and `getReadOnlyStorage` as state accessors, the pruning caches and config blocks; migrate or delete the remaining tests that use them; **remove the guard flag**; rewrite the operator docs, skills and `storage-rocksdb.md`; re-run the EG-1 subset on the final tree | vault; beacon; forge; prism; eye |

#### Files, size and tests per slice

| Slice | Files (main; `db/` unless noted) | Lines main / tests | Tests |
| --- | --- | --- | --- |
| C1 | `mpt/NibblePath.scala` (new), `mpt/MerklePatriciaTrie.scala`, `mpt/MptTraversals.scala`, `mpt/MptVisitors/RlpHashingVisitor.scala`, `storage/MptStorage.scala`, `storage/MptStorageDecorator.scala` (new) | 450 / 750 | position oracles: incremental vs full diff, `StackTrie` rebuild, single and **multi-block** reverse exactness, **several persist rounds per block**, the empty trie, genesis emits nothing vanished; EEST Amsterdam fixtures through the staged layer; **pre-Atlantis intermediate-root receipts** (ethereum/tests Frontier, Homestead, EIP-150); roots and node sets equal the pinned jar's on random sequences and recorded Mordor and Sepolia ranges; decorator-completeness (reflection over `MptStorage`); ethereum/tests; **M7 gate** |
| C2a | `storage/pathstate/PathMptStorage.scala`, `StateView.scala`, `StateExceptions.scala`, `PathStateStorage.scala`, `PathPrefetchReader.scala` (new); `components/Storages.scala`; `ledger/InMemoryWorldStateProxy.scala`; `blockchain/data/GenesisDataLoader.scala` (owner threading); the other six implementors | 1,010 / 680 | a SNAP-built position store read through the trie equals the pinned jar's reads for the same root; mismatch classification; the fault hierarchy is outside `MPTException`; prefetch on a **sibling parent** reads through `view(parent)`, stops on a fault, and `putIfCurrent` holds; decorator forwarding; `EphemDataSource` and RocksDB |
| C2b | `domain/Blockchain.scala`, `BlockchainReader.scala`; `jsonrpc/EthUserService`, `EthProofService`, `TestService`, `graphql/GraphQLSchema`; `ledger/StxLedger`; `jsonrpc/EthSimulateService`; `network/snapserver/SnapServer`, `NetworkPeerManagerActor`; `blockchain/checkpoint/CheckpointExporter`; the four recovery actors; `mpt/LeafWalkVisitor`, `PathTrackingLeafWalkVisitor`, `ShardEnumerator`, `MptConstructionVisitor`; `StateValidator`; `scripts` (ratchet) | 840 / 650 | rpc-compat (40) and graphql (2) unchanged; `eth_getProof` golden bytes; SNAP serve differential (account ranges, storage ranges, trie nodes and proofs byte-identical to the pinned jar's for the same root); window clamp; the grep ratchet fails on an unlisted caller; RPC under a stale view retries |
| C3a | `storage/pathstate/BlockDiff.scala`, `BlockDiffCodec.scala`, `ChangesetStorage.scala`; `storage/Namespaces.scala`; `conf/base/state.conf` | 510 / 440 | golden vectors 1 to 3 (data-model 4.3) re-derived with `java.util.zip.CRC32C`; round trip; every structural rejection; the tombstone; height index; CF created on an existing database (RocksDB test); prune idempotence |
| C3b | `storage/pathstate/PathStagedState.scala`, `StateLock.scala` (new); `PathStateStorage` commit members; `ledger/BlockExecution.scala` (commit mode, `buildExtra`, `stageBlock(parent, bn)`, the `staged = None` guard); `domain/Blockchain.scala`; `storage/StateStorage.scala` | 1,550 / 1,060 | import fixed Platåberget, Sepolia and Mordor ranges with the guard on: **every root equals the pinned jar's**; the Amsterdam BAL `before`/`after` reads work (by-hash layer); apply then reverse leaves every row as it was; injected failure mid-batch leaves nothing; first-event prior; replaced-trie deletion: tx1 writes, tx2 destroys, tx3 recreates; EIP-161 removal with storage; the bound writes a tombstone and **never fails the block**; **a reset owner's priors are the scanned parent rows (tx1 writes, tx2 destroys, tx3 recreates over a dead position, then reverse: every row of `u` as the parent's)**; **with the anchor deleted, FCU, the importer and a repair are refused and `t`/`u` are byte-identical**; the lock and the compare-and-set under a **deterministic interleaving test** (importer commit, `moveHead`, `newPayload` on `view(head)`, a builder view); `[IMPORT-COMMIT]` |
| C3c | `ledger/BlockExecution.scala` (`executeForProposer`), `BlockPreparator.scala` (`prepareBlock`); `consensus/pow/blocks/*`, `MockedMiner`; `jsonrpc/EthMiningService`; `consensus/engine/EngineApiService.scala` (builder); `blockchain/data/ChainImporter.scala`, `GenesisDataLoader.scala`; `testmode/*`, `jsonrpc/TestService`; test harnesses under `src/test` | 670 / 1,140 | proposer never writes a row on an Amsterdam build with a BAL, withdrawals, all five system calls and the empty-payload fallback; a mined block with ommers has the root its import computes; MockedMiner rebuilds after import **and waits for a slow import (two blocks one second apart)**; hive startup import on the unified store, **post-merge side blocks included**; the ported harnesses run on the position store (EG-2 is then meaningful) |
| C4a | `storage/pathstate/StateOverlay.scala`, `ForwardLayer.scala`, `ReverseIndex.scala`, `StateView.scala` (full), metrics | 910 / 750 | view of the block k back equals the state replayed from scratch; reader-versus-move interleavings; budgets, eviction and **pinning**; move-log age and `StateMove.pre` values (a coalesced reverse-plus-forward batch); a concurrency stress run |
| C4b | `storage/pathstate/HeadMove.scala`; `domain/BlockchainWriter.scala`; `consensus/ReorgStateHandler.scala`, `Consensus.scala`, `ConsensusImpl.scala` (result types); `blockchain/sync/regular/BlockImporter.scala` (`isLocalStateFault`) | 710 / 700 | plan from the anchor even when the index is ahead or behind; the batch contains the index, the pointer and the state; chunked moves; I1 rules 1 to 4 and I6; the writer table as a test; the sampled post-move check catches an injected interior fault; **a competing head move between blocks 2 and 3 of a 5-block batch leaves best equal to the anchor, commits 1 and 2 and reports a fault for 3 to 5; a fork at `best - 2` with a 300-block branch is accepted and one at `best - retained - 1` is refused `Deep`; a reorg at the finalized lag (about 90) is not refused** |
| C4c | *(withdrawn: `inmemory` retired)* | 0 / 0 | |
| C4d | `consensus/engine/EngineApiService.scala`, `ForkChoiceManager.scala`; `storage/pathstate/StateRecovery.scala` (new); `consensus/ConsensusImpl.scala` (designated-head arm); `blockchain/sync/SyncController.scala` (escalation); `AppStateStorage` | 850 / 880 | the answer table; **kill -9 between `newPayload` and FCU, restart with no peers, the consensus client reaches VALID with no resync**; FCU refused leaves `currentState`, safe and finalized, payload id unchanged; INVALID_REQUESTS drops the layer and the gate memo; a designated-head branch longer than `max-forward-layers`; a side-payload chain longer than the budget, then FCU; reorg at the finalized lag (about 90); fault injection into `newPayload`, FCU and the builder; **escalation: each trigger induced; 63 consecutive refusals do not escalate and the 64th does; a second trigger inside the cool-down does not (and survives a restart); a re-offered branch is dropped at ERROR by the loop guard; kill -9 at every step of the ordered clear recovers to a clean SNAP; the anchor is never deleted before `SnapSyncDone` is cleared; healing over a tombstone-skipped dead trie (one test)**; hive Engine API suites |
| C4e | `consensus/ConsensusImpl.scala`, `ConsensusAdapter.scala`; `blockchain/sync/regular/BlockImporter.scala`, `BlockQueue`; `storage/pathstate/StateOverlay.scala` (pin); the miner and builder pause hook | 530 / 540 | a heavier branch of 33, 50 and 64 blocks adopted, **and one of 300 blocks forking at `best - 2`**; a branch above the pinned bytes raises the retryable `BranchTooLarge`, a fork deeper than the retained depth is refused `Deep` (reverse depth, not length), never invalid; **block generation waits during a chunked archive move**; `REORG-PARTIAL` layers re-used; `fork.parentSideChain` with a lost layer re-executed; fork detection (`SYNC-FORK`) moves no state and the fetcher restarts from the common block; result mapping never reaches `reportIfProvenInvalid`; archive moves of thousands of blocks in chunks, interrupted and recovered |
| C5a | `blockchain/sync/snap/controller/SnapFinalization.scala`; `storage/PathHandoffSample.scala`, `StateLayoutGuard.scala`, `AncestorWindow.scala` (new); `nodebuilder/NodeBuilder.scala`; `snap/heal/HealInvariant.scala` | 810 / 670 | guard table (empty, marked, other version, any non-empty unmarked datadir, each chain; **a marked datadir with the flag off, an unmarked one with it on**); finalization crash matrix; the sample on a seeded store (port of `PathToHashExportCheckSpec`); genesis clear idempotence; **the ancestor window: BLOCKHASH in the first 256 blocks and an ommer whose parent is below the pivot**; `StateIngestDurabilitySpec` order under `basic` and `archive`, and kill -9 right after the synced finalization commit leaves the rows, the anchor and the pointer; `inmemory` rejected at start on both chains; `snap-split/verify.py` green |
| C5b | `blockchain/sync/BackgroundAudit.scala`, `snap/heal/AuditWalk.scala`; `storage/pathstate/` repair members; `regular/BlockImporter.scala`; `StorageRecoveryActor`, `BytecodeRecoveryActor`, `CombinedRecoveryScanner`/`Actor` | 910 / 650 | audit over a store with injected absent, bad-keccak and moved positions; a stale read never deletes; **a decodable node that differs from the expected one is never deleted**; on-demand fetch by position fills a hole mid-import (ETH and PoW importer); both `saveNode` call sites are `repairNodeAt`; recovery scans match the pinned jar's verdicts |
| C5c | `blockchain/checkpoint/CheckpointArchive.scala` (v2), `CheckpointExporter.scala`, `CheckpointImporter.scala`; `db/storage/pathstate/SyncHandoff.scala` (checkpoint variant), `AppStateStorage.scala` (refused-archive record), `blockchain/sync/SyncController.scala` (no retry), `ConfigValidator.scala` (refusal lifted) | 860 / 680 | export then import: every root equal, on seeded tries, Mordor, Sepolia; slot-0 leaf shared across tries and identical storage roots across owners, one row per (owner, path), with the owner the leaf's full key and not hashed again; corrupt entry, keccak mismatch, CRC32C, truncation, section order, **the five assertions**; wrong chain id, genesis hash and expected block hash refused; **an ancestor window that does not chain (a wrong `parentHash`, a gap, a non-consecutive number) or whose ETC bodies are not exactly B-302's set or do not match their headers refused `BadFormat`, `min(256, N)` headers accepted when N < 256, the last at genesis**; PoS: no bodies, finalized-only export, TD >= TTD; an estimated TD accepted with a WARN; byte-reproducible export; the three golden streams (data-model E.1); code completeness at threshold 0; vault's write order and a kill at each step; a failed or killed import cleared and not retried; version 1 refused; M9 (outside a container) |
| C6 | `blockchain/sync/SyncController.scala` (guard); the peer-count logging; `consensus/pow/*` (if the PoW paths need changes beyond C3c); test harnesses and `scripts` | 440 / 550 | ETC PoW reorg matrix on Mordor and the **private ETC-config PoW net** (two or three fukuii miners plus a core-geth, Olympia at a low block); mined root equals import root; rewards and era-boundary differential; ETC ethereum/tests; the EG-12 harness; M10, M13 |
| C8a | delete `snap/PathToHashExporter.scala` and 3 specs; `StorageScheme`, `SnapHashTrie` and every `StorageScheme.Hash` arm (64 references, 18 files); `PathPublish*` in `SNAPSyncController` and two dispatch specs | about -2,000 / -1,000 | `sbt pp`; 016 ratchet; the EG-1 subset |
| C8b | delete `ReferenceCountNodeStorage` (520), `CachedReferenceCountedStorage` (292), `FastSyncNodeStorage`, the Hash classes of `StateStorage.scala`, pruning caches and config; remove the guard; migrate the remaining tests | about -1,300 / -2,500, plus 700 migrated | `sbt pp`; `grep` for every removed symbol is empty; EG-1 subset on the final tree |

#### S0 prerequisites per slice (round 2)

"Satisfied" means the text is in data-model.md or this plan as of this revision; the named reviewer verifies it at that slice's
PR, and a deviation reopens the item.

- **C0:** read the EIP-8246 text (EIPs clone) and record the 658-fixture pass count (read 2026-11-07,
  `baselines/eip-8246-check.md`; beacon's sign-off pending, T008); name the ETC block source (named 2026-11-07, T010); M14;
  M1 with the largest diff against `max-record-bytes`; pin the jar (v0.12.1, 2026-11-17), the baselines and the golden
  vectors. Prerequisites: none.
- **C1, C2a, C2b, C3a:** none. Round 2 confirms nothing blocks them (item 1 signed, golden vectors verified).
- **C3b:** beacon item 3 (iii), the no-anchor rule (data-model 7.0), and forge item 5, the reset-owner prior (3.6). Satisfied.
- **C3c:** forge item 12, MockedMiner waits for the import (data-model E). Satisfied.
- **C4a:** none.
- **C4b:** forge item 3 (a) `saveLastBlock` and stop at the first non-extending commit (7.1) and (b), with beacon item 3 (i), the
  reverse-depth refusal and the `reorg.max-depth` default (6.4, 10). Satisfied.
- **C4d:** beacon item 3 (i) and the escalation (a) to (c): the direct message, hysteresis, the ordered clear, `state.reset-pending`
  (Part 2 D); forge R2.3 loop guard and cool-down. Satisfied.
- **C4e:** forge item 3 (b), (c) and (d): reverse-depth refusal, loop guard and cool-down, generation pause (6.4, 7.3, D). Satisfied.
- **C5a:** forge item 7, the symmetric flag-off refusal (data-model 5.3). Satisfied.
- **C5b:** none.
- **C5c:** forge item 8 (a) and (b), the one-way door: the window chained by hash and the genesis-anchor clear (E.1). Satisfied.
  The byte layout is pinned in data-model E.1 (2026-11-18, B-066), before any code, with the user's four decisions of that
  day (window after the header; TD-exact flag, estimate accepted with a WARN; PoS rules; failed import cleared and not
  retried) and the forge and beacon corrections. The import's write order is vault's mark-first order (data-model E.1,
  "Atomicity and failure order"), not 8 (b)'s "clear, then delete the anchor". Both reviewers signed the format at C5c's
  PR1 (#1932), before it merged, as for any one-way door.
- **C6:** none beyond C5a.
- **C7:** beacon item 5 (i) and the escalation (d) in the gate (EG-4, EG-15, EG-14; satisfied); the ETC 0 to 5.1M block source named, for
  EG-1's ETC half (satisfied 2026-11-07: this project's own ETC mainnet sync and `fukuii etc export`, T010).
- **C8a:** EG-12 passes. **C8b:** none new (the symmetric guard goes with the flag).

#### Test matrices (C3b, C3c, C4a to C4e, C5a, C6)

**Reorg matrix, PoS and PoW** (C4b, C4d, C4e; each case checks the state root, the position map against a from-scratch replay,
the best pointer and the canonical index): extend by one; `newPayload` then `forkchoiceUpdated`; sibling of the head, promoted;
reorg of depth 1, 2, 8, `history - 1`, `history`, `history + 1` (the last refused with `ReorgTooDeep`, pointer unmoved); a
reorg at the finalized lag (about 90) on a consensus-driven chain; a side branch executed in the overlay and never promoted; a
block applied, reversed, then re-adopted without re-execution; a duplicate `newPayload` while the first is executing; the
designated-head arm with a branch **longer than `max-forward-layers`**; a **side-payload chain longer than the budget, then
`forkchoiceUpdated`**; `forkchoiceUpdated` **refused**, with `currentState`, safe and finalized state and the payload id
unchanged; an `INVALID_REQUESTS` payload whose layer is dropped and whose gate memo is forgotten; `forkchoiceUpdated` **with
attributes racing a move**; a **heavier PoW branch of 33, 50 and 64 blocks** adopted, a **300-block branch forking at `best - 2`**
adopted (a shallow fork is never refused on length), one above the pinned bytes refused retryably (`BranchTooLarge`), and a fork
deeper than the retained depth refused `Deep`; a `saveLastBlock` after a degraded commit writes nothing; fork detection (`SYNC-FORK`) moving no state (the blind rewind of 128 is gone, 2026-11-15); a fork point at the SNAP pivot (the lowest legal one).

**ETC PoW matrix** (C6, on Mordor, on the private ETC-config PoW net, and on recorded ETC blocks; forge reviews each case): a
heavier fork of depth 1 and 2 (the routine case); a heavier fork of depth `history`; `REORG-PARTIAL`, where the executed prefix
does not outweigh the head and the old head stays, **and its side blocks are re-enqueued**; a fork whose blocks carry ommers
that reference the abandoned branch; fork detection (`SYNC-FORK`), which moves no state; an import batch of many blocks each adopted in turn; a mined block
imported via `MinedBlockImport`, **with ommers**; a proposer run on a stale parent; MockedMiner across several head moves; a
range spanning an ECIP-1017 era boundary with every miner and ommer balance compared; **SELFDESTRUCT on every ETC fork,
EIP-161 touch-deletion from Atlantis (block 8,772,000) including an "empty" account with non-empty storage, and the CREATE2
recreate at the same address** (tx1 writes A's storage, tx2 destroys A, tx3 recreates A with S'; the owner's rows equal S''s trie
and a reverse restores the parent); on the private net with Olympia at a low block: the treasury credit every block, the
base-fee floor and genesis `HefPostOlympia` (`GenesisDataLoader.scala:237-238`). The `ethCompatibleStorage = false` position
test is scoped to **private nets** (no shipped chain, ETC included, sets it false).

**Crash matrix** (kill -9 at each injected point, restart, assert I1 to I6 and the answers the Engine API gives): after overlay
commit and before `forkchoiceUpdated`, **restarting with no peers**; before a promotion batch; after it; during retention
pruning (`basic`); during a two-block forward promotion; **during a chunk of a deep move**; **during side-branch execution,
after the index names the side blocks and before `settleHead` (I6)**; at each of the startup reconciliation rules (data-model
5.1); during SNAP finalization before and after the synced commit; during genesis and its clear; during a checkpoint import
before and after its final synced batch; **at each step of an escalation (the synced batch of `clearSnapSyncDone` and the anchor delete,
the `deleteRange` of CF `v`, of `t` and of `u`, the `state.reset-pending` removal)**, and **during a checkpoint import that started
on a genesis anchor**. For the first: `newPayload(B+1)` answers ACCEPTED (not VALID, not INVALID), local
re-execution runs, and the consensus client reaches VALID with no resync.

**Isolation and concurrency matrix** (C3b, C4a): a reader on the anchor view during an importer canonical commit (never
`CorruptNodeException`); a reader that starts before a move and finishes after it, for a position the move touched, one it did
not, and a view older than `max-view-age-moves`; an execution that is retried; a prefetch that stops; an audit read that meets
a moved position (counted as moved, never deleted); **the deterministic interleaving test**: the importer's canonical commit,
FCU's `moveHead`, `newPayload` on `view(head)` and a builder view, in every order a controllable scheduler can produce, with the
final position map equal to a from-scratch replay; rpc-compat and `debug_traceBlock` **during a regular-sync burst**.

**Fault-injection matrix** (C2a, C4d): a `StaleStateViewException`, `StateUnavailable`, `ReorgTooDeep`, `BranchTooLarge` and a
`CorruptNodeException` injected into `newPayload`, `forkchoiceUpdated`, the importer, the PoW consensus path and the builder.
Assert: never INVALID, never `markInvalidRecursive`, never `reportInvalid`, never a delete, never a provider blacklisted.

### S0 review checklist: decisions `forge` and `beacon` must sign before any code

"Sign" means: agree, or say what changes. **Rounds 1 (2026-11-06) and 2 (2026-11-07) are done; the rows below state the design as revised by both, and
the two S0 records ([implementation-log.md](./implementation-log.md#s0-c0-the-review-records)) hold each reviewer's
verdict per item.** The table is the signed record: where a later decision changed a row (for example `SYNC-FORK`,
the escalation's home, the ETC window bodies after B-302), the slice tables above, data-model.md and the "Decision log"
give the current design. Items 1 to 5 can change a block's outcome or the
chain a node follows; 6 to 15 are consequences to accept. `forge` signs the ETC side of every item before code and `beacon`
the ETH side; where both are named, both sign.

| # | Decision (as revised by round 1) | Specified in | Why it matters | Signs |
| --- | --- | --- | --- | --- |
| 1 | **Position emission and the vanished-position rule**: the 32-byte stored rule and the always-stored root; the empty trie has no rows; the prior is fixed at the **first event** at a position (a position created in the block has prior None); only **replaced or absorbed** nodes are reported (not the branch `fix` resolves and leaves); a node under execution is answered **by hash** from the staged layer, then by position from the parent view; the oracles include multi-block reverse, several persist rounds per block, EEST Amsterdam fixtures and the pre-Atlantis intermediate-root networks | data-model 2, 3, 6.2, 8 | A missed or extra position leaves a stale or missing node, or lets a reverse restore the wrong state; a position-keyed in-block layer fails every Amsterdam block. It is inside `MerklePatriciaTrie`, which both chains execute | forge and beacon |
| 2 | **Commit timing and state availability**: `newPayload` executes into the overlay; state reaches disk at `forkchoiceUpdated`; `parentValidated` becomes "state available"; the Engine API claims table (VALID, ACCEPTED, SYNCING, INVALID) is pinned; an unavailable parent is **re-executed locally**, with no peer; a post-execution INVALID drops the layer; the new faults never extend `MPTException` | data-model 6.3.2, 7.2, Part 2 D | Changes what the Engine API can claim about a parent after a crash, and which errors can ever become INVALID | beacon; forge reviews the shared code |
| 3 | **Head moves**: planned **from the anchor's records**, never the index; **one state lock with an anchor compare-and-set** over every in-place batch; the state batch is **contributed into** `promoteToCanonicalHead` and `rewriteCanonicalIndex` before their commit; `currentState` is set only after a successful move; a block that is a forward layer is **promoted**, not dropped; the designated-head arm is reverse-then-commit; PoW branches are **pinned**; chunked deep moves; runtime refusals escalate and never apply I1 rule 4; the corrected writer audit (importer lenient path, test mode); the `SYNC-FORK` clamp; PoW result mapping. **Round 2:** the refusal is on **reverse depth** (`best - fork`), not branch length, and `reorg.max-depth` defaults to the retention floor; **an absent anchor refuses every in-place writer**; `saveLastBlock` writes nothing on a path-live store and the batch stops at the first commit that no longer extends the anchor; block generation pauses during a chunked move | data-model 5.1, 6.4, 7.0, 7.1, 7.3, Part 2 A, C, D | A reorg that moves the pointer without the state, or the state without the pointer, or a diff applied onto the wrong base, forks the node from its own history | forge (PoW) and beacon (PoS) |
| 4 | **Isolation**: every in-place batch is a state move that publishes pre-batch values and bumps the epoch; "anchor view" is decided at read time; the importer's delete is position-scoped with a real `stillReached`; the fault hierarchy and the caller mapping are pinned by fault injection; RPC retries a stale view; the lock is the fallback | data-model 6.3 | The missing-node machinery deletes and refetches; a stale read mistaken for corruption would delete a node the head needs, and a storage fault must never become INVALID | beacon; forge reviews |
| 5 | **Replaced storage tries**: account removal, recreation, EIP-161 touch-deletion, ETC's SELFDESTRUCT (full SELFDESTRUCT deletion before Olympia, and EIP-6780 from Olympia (ECIP-1121)), Amsterdam's clear: **per-block reset tracking; enumeration by prefix scan of CF `u`**; ordering within a block; above the bound the deletion is **skipped and the record is an incomplete tombstone**, so a valid block is never failed; reverse across a tombstone is refused. **Round 2:** for a reset owner every prior is the scanned parent row (overriding the first-event prior), the tombstone refusal is required, and EG-4 and EG-15 assert zero tombstones | data-model 3 rule 6, 4.2, 10, 13 (M15) | A local resource bound must not fail a canonical block (a mining node would mine a minority fork) | beacon (EIP-6780, EIP-8246) and forge (ETC semantics) |
| 6 | **Genesis on the unified layout and `HealInvariant.isEmpty`**: owner-scoped, insert-only, **no BlockDiff for genesis**; test mode uses the same commit; non-empty genesis storage is refused when `ethCompatibleStorage = false`; SNAP start clears `t`/`u` and the anchor | data-model 5.4 | Otherwise every fresh datadir is pre-invariant and pays the walk spec 022 removes | beacon; forge for ETC |
| 7 | **The layout guard**: one marker, refuse any non-empty unmarked datadir on either chain, no adoption, no probe of CF `n`. **Round 2:** while the in-development flag exists, a build with it off also refuses a datadir that has the marker | data-model 5.2, 5.3 | A wrong refusal strands a node; a wrong stamp runs on a half-state | beacon; forge; user |
| 8 | **Checkpoint format v2 and the importer**: genesis hash in the header, the **256-header and 6-body ancestor window** as tags 0x03 and 0x04, CRC32C, the operator-supplied expected block hash and the stated trust in total difficulty, the importer's four assertions, code completeness, a golden stream; v1 refused. **Round 2:** the ancestor window is chained by `parentHash` to the checkpoint header and each body matches its header (`min(256, N)` when N < 256); a genesis anchor is cleared at import start | data-model E.1 | A one-way door, and ETC's resync path | forge and beacon |
| 9 | **`get(hash)` on a position store** answers the account root and overlay nodes only; the recorder sits **above** the cache and records parent-state reads only; every hash-only walker is converted or allow-listed under a grep ratchet; a hit by hash can mask a positional hole | data-model 6.5, 9 | The witness recorder must see every parent-state read | beacon; herald confirms |
| 10 | **Owner threading and RPC signatures**: `forOwner`, `getAt`; the four omitted readers; an allow-list ratchet on the four state accessors; RPC retries a stale view | data-model 8, Part 2 E | The one RPC-visible code change | conduit, beacon; forge |
| 11 | **ETC consensus unchanged on the unified layout**: the evidence is **an offline import of ETC blocks 0 to 5.1M from genesis (the block source was then an open item; named 2026-11-07, T010)**, validated per header, with pinned-jar node-set equality every 100k blocks**, a Mordor import from genesis across 2,000,000 and 2,520,000, the ETC ethereum/tests job on the ported harness, `ForkChoiceBlockchainTestsSpec` under `basic` and `archive`, a private ETC-config net for Olympia, and M7. **Round 2:** the block source is logistics and gates EG-1's ETC half (C7), not code; forge signed without it | plan EG-1, EG-2, EG-6 | The track moves ETC's storage with no Hash fallback in tree, and no ETC mainnet pre-state exists | forge |
| 12 | **Block generation and mining**: a proposer view, content-addressed and fresh per attempt, for **every** proposer path; MockedMiner rebuilds from the mined block **and waits for that block's import (round 2)**; cached worlds are never committed or read stale; a stale view retries the build; the mined root equals the import root, with ommers and on the Olympia net | data-model E table | A proposer write in place would corrupt the head | forge (PoW) and beacon (builder) |
| 13 | **Moot: the `inmemory` mode is retired** (user, 2026-11-06). The mode is rejected at start on both chains, and the ring, the persisted tail and the restart-reach rules are not designed. Nothing remains to sign | data-model 4.6 | n/a | none |
| 14 | **Retirement scope**: what is deleted and what stays (the hash-keyed store over `EphemDataSource`); the harnesses are **ported in C3c, before any gate**; both `saveNode` call sites become `repairNodeAt` before `FastSyncNodeStorage` goes; the pinned jar keeps its hive and EEST baselines and its retention | plan C3c, C5b, C8a, C8b | Nothing in tree can be the oracle after C8, and a gate on the Hash-scheme harnesses proves nothing | forge, beacon |
| 15 | **ETC SNAP on the unified layout and the EG-12 route**: guard removal; **the ancestor window** as a hand-off precondition (C5a); the peer precondition; Mordor proven in the dev loop (`tasks.md:391,480`), ETC mainnet never | data-model G | The ETC resync path before a checkpoint archive exists | forge |

### Build records (moved to implementation-log.md)

The S0 review records and every per-slice and per-part build record (C1 to C5a, about 4,000 lines) are in
[implementation-log.md](./implementation-log.md), verbatim. They are the history of how the design in this plan was reached; where a record and
this plan differ, this plan and data-model.md are the current word. A citation of the form `plan.md "<name>"` in code
or in tasks.md names one of these sections:

- **S0 (C0)**: [S0 record (round 1, 2026-11-06)](./implementation-log.md#s0-record-round-1-2026-11-06); [S0 record (round 2, 2026-11-07)](./implementation-log.md#s0-record-round-2-2026-11-07).
- **C1**: [C1 pre-code record (2026-11-11)](./implementation-log.md#c1-pre-code-record-2026-11-11).
- **C2a**: [C2a part 1 record (2026-11-11)](./implementation-log.md#c2a-part-1-record-2026-11-11); [C2a part 2 record (2026-11-11)](./implementation-log.md#c2a-part-2-record-2026-11-11).
- **C2b**: [C2b pre-code record (2026-11-11)](./implementation-log.md#c2b-pre-code-record-2026-11-11); [C2b ratchet record (2026-11-11)](./implementation-log.md#c2b-ratchet-record-2026-11-11); [C2b RPC readers record (2026-11-11)](./implementation-log.md#c2b-rpc-readers-record-2026-11-11); [C2b SNAP serving record (2026-11-11)](./implementation-log.md#c2b-snap-serving-record-2026-11-11); [C2b walkers record (2026-11-11)](./implementation-log.md#c2b-walkers-record-2026-11-11).
- **C3a**: [C3a record (2026-11-11)](./implementation-log.md#c3a-record-2026-11-11); [Decisions of 2026-11-11 (user)](./implementation-log.md#decisions-of-2026-11-11-user).
- **C3b**: [C3b storage record (2026-11-11)](./implementation-log.md#c3b-storage-record-2026-11-11); [C3b wiring record (2026-11-11)](./implementation-log.md#c3b-wiring-record-2026-11-11).
- **C3c**: [C3c genesis record (2026-11-11)](./implementation-log.md#c3c-genesis-record-2026-11-11); [C3c proposer view record (2026-11-12)](./implementation-log.md#c3c-proposer-view-record-2026-11-12); [C3c part 3: the linear subset now (user, 2026-11-12), and the lenient import record](./implementation-log.md#c3c-part-3-the-linear-subset-now-user-2026-11-12-and-the-lenient-import-record); [C3c part 3b: the fixture replays from a genesis commit, and the harness canary](./implementation-log.md#c3c-part-3b-the-fixture-replays-from-a-genesis-commit-and-the-harness-canary); [C3c part 3c: test mode on the unified layout (2026-11-12)](./implementation-log.md#c3c-part-3c-test-mode-on-the-unified-layout-2026-11-12).
- **C4a**: [C4a part 1: forward layers (2026-11-12)](./implementation-log.md#c4a-part-1-forward-layers-2026-11-12); [C4a part 2: the reverse index (2026-11-12)](./implementation-log.md#c4a-part-2-the-reverse-index-2026-11-12); [C4a part 3: the bounds (2026-11-12)](./implementation-log.md#c4a-part-3-the-bounds-2026-11-12); [C4a part 3c: the ordering, the stress and M3 (2026-11-12)](./implementation-log.md#c4a-part-3c-the-ordering-the-stress-and-m3-2026-11-12); [C4a part 4: the node reads behind its head, first for SNAP (2026-11-12)](./implementation-log.md#c4a-part-4-the-node-reads-behind-its-head-first-for-snap-2026-11-12); [C4a part 4b-1: what a reader behind the head may cost the node (2026-11-12)](./implementation-log.md#c4a-part-4b-1-what-a-reader-behind-the-head-may-cost-the-node-2026-11-12); [C4a part 4b-2: the JSON-RPC reads a block behind the head (2026-11-12)](./implementation-log.md#c4a-part-4b-2-the-json-rpc-reads-a-block-behind-the-head-2026-11-12).
- **C4b**: [C4b part 0: a fault of this node's state ends a batch as a result of its own (2026-11-13)](./implementation-log.md#c4b-part-0-a-fault-of-this-nodes-state-ends-a-batch-as-a-result-of-its-own-2026-11-13); [C4b part 1: the plan of a move, and why one is refused (2026-11-13)](./implementation-log.md#c4b-part-1-the-plan-of-a-move-and-why-one-is-refused-2026-11-13); [C4b part 2a: the move of the head in the store (2026-11-13)](./implementation-log.md#c4b-part-2a-the-move-of-the-head-in-the-store-2026-11-13); [C4b part 2b: the writer's rows in the move's batch (2026-11-13)](./implementation-log.md#c4b-part-2b-the-writers-rows-in-the-moves-batch-2026-11-13); [C4b part 2c: a move's batches bounded, the barrier, the hold for an attempt (2026-11-13)](./implementation-log.md#c4b-part-2c-a-moves-batches-bounded-the-barrier-the-hold-for-an-attempt-2026-11-13); [C4b part 3a: the head's writers on the unified layout (2026-11-13)](./implementation-log.md#c4b-part-3a-the-heads-writers-on-the-unified-layout-2026-11-13); [C4b part 3b: the fork choice and test mode's rewind move the head through a move (2026-11-13)](./implementation-log.md#c4b-part-3b-the-fork-choice-and-test-modes-rewind-move-the-head-through-a-move-2026-11-13); [C4b part 4: the reconciliation at start (2026-11-13)](./implementation-log.md#c4b-part-4-the-reconciliation-at-start-2026-11-13).
- **C4d**: [C4d part 0: a confirmation is no move, and `Corrupt` fails closed (2026-11-14)](./implementation-log.md#c4d-part-0-a-confirmation-is-no-move-and-corrupt-fails-closed-2026-11-14); [C4d part 1: the finalized block (2026-11-14)](./implementation-log.md#c4d-part-1-the-finalized-block-2026-11-14); [C4d part 2a: a payload is executed as a forward layer (2026-11-14)](./implementation-log.md#c4d-part-2a-a-payload-is-executed-as-a-forward-layer-2026-11-14); [C4d part 2b: the fork choice moves the head (2026-11-14)](./implementation-log.md#c4d-part-2b-the-fork-choice-moves-the-head-2026-11-14); [C4d part 3: a state that is no longer held is had again (2026-11-14)](./implementation-log.md#c4d-part-3-a-state-that-is-no-longer-held-is-had-again-2026-11-14); [C4d part 4a: a branch longer than the forward layers is committed in place (2026-11-14)](./implementation-log.md#c4d-part-4a-a-branch-longer-than-the-forward-layers-is-committed-in-place-2026-11-14); [C4d part 4b: what readers and the consensus client see of an adoption (2026-11-14)](./implementation-log.md#c4d-part-4b-what-readers-and-the-consensus-client-see-of-an-adoption-2026-11-14).
- **C4e**: [C4e part 1: a heavier branch is executed on pinned layers and adopted by one move (2026-11-15)](./implementation-log.md#c4e-part-1-a-heavier-branch-is-executed-on-pinned-layers-and-adopted-by-one-move-2026-11-15); [C4e part 2: the side blocks a branch stands on are executed again when their state is gone (2026-11-15)](./implementation-log.md#c4e-part-2-the-side-blocks-a-branch-stands-on-are-executed-again-when-their-state-is-gone-2026-11-15); [C4e part 3: at the pinned cap, the head is moved first and the branch committed in place (2026-11-15)](./implementation-log.md#c4e-part-3-at-the-pinned-cap-the-head-is-moved-first-and-the-branch-committed-in-place-2026-11-15); [C4e part 4: the head's generation, who builds on the head, and a fork below the views (2026-11-15)](./implementation-log.md#c4e-part-4-the-heads-generation-who-builds-on-the-head-and-a-fork-below-the-views-2026-11-15); [C4e part 5: the consensus layer's branch is executed on layers and adopted by one move (2026-11-15)](./implementation-log.md#c4e-part-5-the-consensus-layers-branch-is-executed-on-layers-and-adopted-by-one-move-2026-11-15); [C4e part 6: fork recovery moves no state (2026-11-15)](./implementation-log.md#c4e-part-6-fork-recovery-moves-no-state-2026-11-15); [Mining on the unified layout: the miners read the head under its generation (B-293) (2026-11-17)](./implementation-log.md#mining-on-the-unified-layout-the-miners-read-the-head-under-its-generation-b-293-2026-11-17).
- **C5a**: [C5a part 1: the layout guard, where the storages are built (2026-11-15)](./implementation-log.md#c5a-part-1-the-layout-guard-where-the-storages-are-built-2026-11-15); [C5a part 2: the window below a block, and ommers a node cannot check (2026-11-15)](./implementation-log.md#c5a-part-2-the-window-below-a-block-and-ommers-a-node-cannot-check-2026-11-15); [C5a part 3: the state is cleared for a sync from scratch (2026-11-15)](./implementation-log.md#c5a-part-3-the-state-is-cleared-for-a-sync-from-scratch-2026-11-15); [C5a part 4: the hand-off's sample, on the rows where they lie (2026-11-15)](./implementation-log.md#c5a-part-4-the-hand-offs-sample-on-the-rows-where-they-lie-2026-11-15); [C5a part 5a: a sync hands off in one synced write, with nothing copied (2026-11-15)](./implementation-log.md#c5a-part-5a-a-sync-hands-off-in-one-synced-write-with-nothing-copied-2026-11-15); [C5a part 5c: the bodies below a pivot are fetched (2026-11-15)](./implementation-log.md#c5a-part-5c-the-bodies-below-a-pivot-are-fetched-2026-11-15); [C5a part 5b: a sync ends by a sample of its rows and one write (2026-11-15)](./implementation-log.md#c5a-part-5b-a-sync-ends-by-a-sample-of-its-rows-and-one-write-2026-11-15); [C5a part 6a: a node says when it cannot follow the chain, on proof of stake (2026-11-16)](./implementation-log.md#c5a-part-6a-a-node-says-when-it-cannot-follow-the-chain-on-proof-of-stake-2026-11-16); [C5a part 6b: a node says when it cannot follow the chain, on proof of work (2026-11-16)](./implementation-log.md#c5a-part-6b-a-node-says-when-it-cannot-follow-the-chain-on-proof-of-work-2026-11-16); [C5a part 7a: whether an escalation is acted on (2026-11-16)](./implementation-log.md#c5a-part-7a-whether-an-escalation-is-acted-on-2026-11-16); [C5a part 7b: the state given up (2026-11-16)](./implementation-log.md#c5a-part-7b-the-state-given-up-2026-11-16); [C5a, a gate on part 10: what the clear of an escalation also does (B-307, in part) (2026-11-16)](./implementation-log.md#c5a-a-gate-on-part-10-what-the-clear-of-an-escalation-also-does-b-307-in-part-2026-11-16); [C5a, a gate on part 10: the index by number after a clear for an escalation (B-303) (2026-11-16)](./implementation-log.md#c5a-a-gate-on-part-10-the-index-by-number-after-a-clear-for-an-escalation-b-303-2026-11-16); [C5a, a gate on part 10: what the proof-of-work feed does not count (B-306) (2026-11-16)](./implementation-log.md#c5a-a-gate-on-part-10-what-the-proof-of-work-feed-does-not-count-b-306-2026-11-16); [C5a part 8: the catch-up after a hand-off, on proof of stake (2026-11-16)](./implementation-log.md#c5a-part-8-the-catch-up-after-a-hand-off-on-proof-of-stake-2026-11-16); [C5a, a gate on part 10: the pivot of the sync after an escalation is on the refused branch (B-307 c1) (2026-11-16)](./implementation-log.md#c5a-a-gate-on-part-10-the-pivot-of-the-sync-after-an-escalation-is-on-the-refused-branch-b-307-c1-2026-11-16); [C5a, a gate on part 10: the sync is steered onto the refused branch (B-307 c2) (2026-11-16)](./implementation-log.md#c5a-a-gate-on-part-10-the-sync-is-steered-onto-the-refused-branch-b-307-c2-2026-11-16); [C5a part 9a: the ethereum/tests fork vectors on the unified layout (B-289) (2026-11-16)](./implementation-log.md#c5a-part-9a-the-ethereumtests-fork-vectors-on-the-unified-layout-b-289-2026-11-16); [C5a part 9b: an announced block does not end the fetching of a branch (B-300, in part) (2026-11-16)](./implementation-log.md#c5a-part-9b-an-announced-block-does-not-end-the-fetching-of-a-branch-b-300-in-part-2026-11-16); [C5a, a gate on part 10: the consensus layer's branch beyond the forward layers, and a refused one (B-297) (2026-11-16)](./implementation-log.md#c5a-a-gate-on-part-10-the-consensus-layers-branch-beyond-the-forward-layers-and-a-refused-one-b-297-2026-11-16); [C5a part 9c: a heavier branch that is the shorter, and a lighter one fetched again and again (B-300, in part) (2026-11-16)](./implementation-log.md#c5a-part-9c-a-heavier-branch-that-is-the-shorter-and-a-lighter-one-fetched-again-and-again-b-300-in-part-2026-11-16); [C5a part 9d: the bodies below a block with ommers are fetched when they are not stored (B-301) (2026-11-16)](./implementation-log.md#c5a-part-9d-the-bodies-below-a-block-with-ommers-are-fetched-when-they-are-not-stored-b-301-2026-11-16); [C5a, a gate on part 10: a hand-off's sample that keeps failing does not restart the sync for ever (B-304) (2026-11-16)](./implementation-log.md#c5a-a-gate-on-part-10-a-hand-offs-sample-that-keeps-failing-does-not-restart-the-sync-for-ever-b-304-2026-11-16); [C5a, a gate on part 10: the escalation's tests left from B-307 (B-311), and task #68 (B-309) (2026-11-16)](./implementation-log.md#c5a-a-gate-on-part-10-the-escalations-tests-left-from-b-307-b-311-and-task-68-b-309-2026-11-16); [C5a, a gate on part 10: a refresh after an escalation stays on the branch (B-310) (2026-11-16)](./implementation-log.md#c5a-a-gate-on-part-10-a-refresh-after-an-escalation-stays-on-the-branch-b-310-2026-11-16); [C5a part 10: the refusals lifted, and a hand-off run live (B-325, B-308) (built, #1880, 2026-11-17)](./implementation-log.md#c5a-part-10-the-refusals-lifted-and-a-hand-off-run-live-b-325-b-308-built-1880-2026-11-17).

### C4b: the move core, as built (2026-11-13)

Two consults before code (forge for ETC, beacon for ETH; both AGREE WITH CHANGES) changed the shape of the slice.
What follows is the design C4b was built to (B-061; #1816 to #1829); where it differs from data-model 7.0 and 7.3
and from tasks.md, this is the later word.

- **The move takes the caller's rows; it hands out nothing** (beacon). `moveHead(target, extraFor)` asks the
  caller for its index and best-pointer rows outside the state lock, once for each chunk and again after every
  re-plan, and writes them in its own batch under the lock, as `commitCanonical(staged, block, extra)` does. No
  `HeadMovePrepared` is handed out with the lock held: the lock is one thread's and not re-entrant, the Engine
  API's callers run in `IO`, and a caller that never committed would leave every view of the node waiting on an
  odd epoch. `BlockchainWriter.promoteToCanonicalHead` splits into a builder of its rows and a commit; with the
  guard off it builds and commits as today, byte for byte, and a test holds it to that.
- **A move of several blocks is settled and verified against a virtual disk**: the disk and the batch so far
  (beacon, forge). A block that is forwarded from a layer has its priors, and the rows of the storage tries it
  replaces, read from its parent's state, which inside one batch is not on disk. Settled against the disk its
  record lists the wrong rows, and a later move back by that record writes a wrong state; ETC has no EIP-6780 before Olympia
  (ECIP-1112), so a replaced storage trie is routine there. Every reversed entry's `next` and every forwarded record's `prior`
  is compared with the virtual value before anything is written, and what a reader's view needs of the old state
  is the disk's row, read under the lock. That is also the interior check, made before the write and not after.
- **Refusals.** `Deep` is decided by the records that are there (the walk meets one that is gone below the
  pruned-to mark), with `reorg.max-depth` as policy on top: the retention formula and the disk differ after a
  jump of the finalized block, after a restart, and after a move to a shorter branch. `Pivot` needs the block the
  state began at, kept durably (`state.origin`, written with the batch that creates the anchor). `Incomplete` is
  a tombstone in the reverse set. **`Finalized` is new**: a reverse set that crosses the finalized block is
  refused and does not escalate, since escalating would follow a consensus client that broke finality. A record
  that is gone above the pruned-to mark is corruption, said as that and never as `Deep`.
- **One move at a time, and a latch for the builders.** A mutex outside the state lock is held across all the
  chunks of a move, and a move-in-progress latch spans the whole move or adoption attempt. "The state lock's
  holder flag" of data-model 7.3 cannot be the pause: the lock is free between chunks.
- **The chunk invariant** is `anchor == best` on the path old head, fork, target. Data-model 7.3 and T138 say
  "on an ancestor of the target", which the reverse chunks are not. A move that fails mid-way leaves the head on
  a block of that path.
- **Compare-and-set on the anchor and the epoch, and on the layers**: a layer of the plan may be dropped as
  invalid or evicted between the plan and the lock, and is checked for identity under it.
- **Records and pruning of one batch are computed together**; the pre-values of a chunk are bounded in bytes,
  with a barrier move above the bound that makes older views stale, never wrong; a deep move in the archive mode
  streams its records by chunk.
- **Reverse-then-commit** (the user's decision of 2026-11-12) is, at this slice's surface: a move to the fork
  point with nothing to forward; the branch committed in place block by block; and, to go back, a move to the
  old head, forwarded from its records. **Retention must not prune a record above the fork point while such an
  attempt is unsettled** (forge): every in-place commit prunes, so a branch longer than the retained depth would
  delete what the way back needs. The slice offers a hold for that; its caller is C4e.
- **A block of the branch that is invalid** (forge W4; the user's decision of 2026-11-14): the blocks of the branch
  already committed in place stay the head when they already outweigh the old head, as `ConsensusImpl.settleHead`
  keeps the executed prefix today; only otherwise is the head moved back to the old head along the records. C4e
  builds it.
- **After the write** a sample that does not match means the store wrote wrong bytes: the node fails closed (no
  in-place writer goes on, the Engine API answers SYNCING) and takes the escalation route. It never rolls back
  onto such a store.

**The parts.** Part 0: a fault of the state as a way for a batch to end. Part 1: the plan, pure, with the
refusals. Part 2: the move, with the split of `BlockchainWriter` and the one-batch test (T137 and T147 move here
from the callers). Part 3: the callers that exist on the guard (`test_rewindToBlock`, the index writers), and the
table of pointer writers as a test, with the rows the audit missed (forge: the SYNC-FORK resolver's
`discardBlocksAfter`, which steps the best block down one batch at a time under no lock;
`PivotAnchor.reanchor`; `repairBestBlockNumberMapping`). Part 4: the reconciliation at start, in place of
`fixDatabase` and `repairBestBlockMapping` on the unified layout (they trust the best block or the index, and would
undo it); as built it applies rule 4 only (the user, 2026-11-14: "go with what is on disk"; rules 2 and 3 are not
built), in `domain/StateReconciliation.scala`.

**Recorded and not decided here.** A legal reorganisation deeper than 512 blocks during a time without finality
is refused and escalated (retention's bound; an accepted risk, to be stated in the operator's notes).
`recovery.max-blocks` is the history, 64, against 512 retained on ETC: a lost side chain longer than that is not
executed again but escalated (C4e).

*The records of C4b parts 0 to 4 are in [implementation-log.md](./implementation-log.md#c4b-part-records), verbatim (moved 2026-11-21).*

### C4d: the PoS arms, as built (2026-11-14)

Beacon's decomposition, before code (C4d is beacon-first; built as B-062, #1830 to #1843). What the guard did before
C4d, read in the code:
`newPayload` never executes (a block not on the anchor is refused before execution, `refuseUnadopted`), and is stored
by hash only and answered ACCEPTED; `forkchoiceUpdated` follows only the held head. **A latent defect part 2a closed
first:** once a payload executes, its store indexes it by number (`storeBlock` -> `storeBlockHeader`), which is
not refused on the guard and would put an index entry above the anchor: on the guard every executed payload is stored by
hash only, and a spec pins it.

The parts, in order, each mergeable alone:
- **Part 0, plumbing, no change to the answer table**: a confirmation of the head is no move (B-249); `Corrupt` fails
  the state closed at the fork choice, answered `SYNCING`.
- **Part 1, finality** (T160, T171): the finalized block from the fork choice, set only after a move that succeeded
  (the zero hash is none), persisted in AppState in the move's own batch; the retention floor follows it.
- **Part 2a, `newPayload` executes as a forward layer** (never in place: only a fork choice moves the head): a parent
  that is the anchor, a layer or a block behind the anchor executes (VALID or INVALID); a parent stored and executed
  whose state is not held, or unknown, is ACCEPTED; stored by hash only; `INVALID_REQUESTS` drops the layer and the
  import gate's memo (`BlockImportGate.forget`), marking the block invalid first. `stateAvailable` over layers and
  records (T094) lands here, with its first caller. The fork choice still follows only the held head.
- **Part 2b, `forkchoiceUpdated` moves** (`moveHeadInPlace` with no refusal): the checks that move nothing first
  (invalid blocks, safe and finalized ancestry `-38002`, a head with no receipts), the move under `IO.blocking`, then
  on success alone `currentState`, the finalized block, the gossip gate, the pool, then the attributes and the build
  (on the named parent's view, never on `movesStarted`). Refusals: `Finalized` `-38002` (decided, below), `Deep`,
  `Incomplete` and `Pivot` `SYNCING` with a strike, `Corrupt` fails closed, `NotHeld` `SYNCING`.
- **Part 3, `StateRecovery`** (T161, T169, T156): needed now, since the guard starts no sync. Re-executes, oldest
  first, the stored blocks from one whose state is available up to the head the consensus client names, as overlay
  commits on a bounded dispatcher, never inline; bounded by `recovery.max-blocks`.
- **Part 4, a branch longer than the forward-layer budget** (T159): reverse-then-commit under `withRecordsHeld`.
- **Part 5, the escalation trigger, log-only** (T163, T164, T174): strikes, hysteresis, cool-down, loop guard,
  metrics; `StateEscalationRequested` logged and counted as suppressed until there is a SNAP to escalate to.
- **Part 6, the ordered clear** (T165, T166, T172, T173): with or after C5a, never before: with SNAP refused on the
  guard, a clear would leave a datadir that can never hold a state again.

Parts 5 and 6 were built in C5a, not here (the user's decision of 2026-11-14, below; #1843 moved T163 to T165 and
T174).

**Decided by the user on 2026-11-14**, on beacon's proposal: `Finalized` is answered `-38002` (go-ethereum: the
finalized block is not in the canonical chain); on PoS a block of a long branch found invalid moves the head back to
the fork choice's last head (the consensus layer's head stays the validated one), where W4 (PoW, C4e) keeps the
executed prefix; the designated-head arm (T170) moves to C4e, which edits the same `reorganise`; the armed ordered
clear lands with C5a, not before.

*The records of C4d parts 0 to 4b are in [implementation-log.md](./implementation-log.md#c4d-part-records), verbatim (moved 2026-11-21).*

### C4e: the PoW arms, as built (2026-11-15)

Forge's decomposition, before code (C4e is forge-first; built as B-063, #1846 to #1853). What the guard did before
C4e, read in the code: the only way to a
reorganisation in a running node is test mode (`test_importRawBlock`, `test_mineBlocks`); the first block of a heavier
branch was refused before it ran (a block not adopted as it is executed), so the ethereum/tests fork vectors failed
there. A chain import refuses side blocks without asking the fork choice, mining is refused at start, and regular
sync's importer is not started on the guard (C5, C6).

**The design is the user's decision 1 of 2026-11-12** (implementation-log.md, "C4a part 3: the bounds", under "Two
decisions part 4 could not be designed without"): **execute first, on pinned layers; reverse-then-commit as the rule
when the cap is hit.** `BranchTooLarge` is never the last word for a heavier branch (forge); the S0 reconciliation note
("`BranchTooLarge` is the default over the cap ... forge confirms at C4e") and the original wording of T177 and T189 no
longer apply. Not
reverse-then-commit always: a fork of one or two blocks is routine on ETC, and committing it in place would write the
disk down and up, show readers a lighter head and open a generation pause each time; execute-first leaves the old head
untouched when a branch turns out lighter or invalid, as core-geth keeps its head until a side chain's total difficulty
exceeds it.

The parts, in order, each mergeable alone:
- **Part 1, the reorganisation on pinned layers**: a block not adopted as it is executed is staged apart and stored by
  its hash only (I6, B-250); the settle is one move of the head; a refusal up front on the reverse depth; every fault of
  the state is no verdict.
- **Part 2, a side parent whose state is not held** (T180, T187): `fork.parentSideChain` blocks with neither layer nor
  record are executed again before the branch (`StateRecovery`). **Not to be left out before the guard is on for any
  Mordor node** (forge): layers are in memory, so after a restart a branch on a stored side block would be refused for
  good.
- **Part 3, the cap**: one in-place adoption core shared with `StateRecovery.adopt` (its failure policy: keep the
  committed prefix when it is heavier, W4, for proof of work; back to the followed head for proof of stake), taken
  when a branch's pinned layers would be more than they may (`BranchTooLarge`). The same warning as part 2.
- **Part 4, deep moves in `archive`, the attempt-wide latch and the miners' readers** (T183, T184, T190): a miner reads
  before and after it builds and discards a build a move overtook; the Engine API's builder builds on a named
  parent's view and needs no pause.
- **Part 5, the designated-head arm** (T170, T159's first half; beacon).
- **Part 6, `SYNC-FORK`** (T181, T188). **The user's decision of 2026-11-15: no state is moved on fork detection.**
  The importer's blind rewind lowers the best block and then adopts the peer's branch block by block, which skips the
  weight rule, and core-geth never sets its head back on detecting a fork. The fetcher is reset from the common
  ancestor and the reorganisation judges the branch by weight. The rewind's history (it unsticks a fetcher on an
  unknown parent) is traced first, and that purpose kept.
- **The PoW escalation** (T182's second half) **is folded into C5a with the PoS trigger** (the user's decision of
  2026-11-15): there is nothing to escalate to before it. C4e says `[STATE-REORG-TOO-DEEP]` at ERROR.

Recorded, not changed: on equal total difficulty core-geth prefers the lower block number, then chooses at random;
fukuii keeps its head, on both layouts (B-284). ECIP-1017 rewards do not depend on the layout.

*The records of C4e parts 1 to 6, and of the mining gate (B-293) are in [implementation-log.md](./implementation-log.md#c4e-part-records-and-the-mining-gate-b-293), verbatim (moved 2026-11-21).*

### C5a: the hand-off, the layout guard and the escalation, as built (2026-11-15)

Read before code by forge (ETC and the shared structure), beacon (proof of stake) and vault (the write order). Built as
B-064, parts 1 to 10 (#1855 to #1880, 2026-11-15 and 11-16); the live gates (T209, EG-4) are open.

**What the guard refused before C5a, which decided what C5a could run** (part 10 lifted the SNAP and sync refusals, and
B-293 lifted the mining refusal): with `fukuii.state.path-live` on, the sync
controller is never started, `do-snap-sync` and mining are refused at the configuration, SNAP is not advertised (and
the capability is mutual), and ETC's SNAP is held to the hash scheme. The escalation trigger that C4d part 5 was to
log had not been built; the keys `state.recovery.escalation-refusals` and `escalation-cooldown` had no reader until C5a.

**The user's decisions of 2026-11-15**:
1. **C5a lifts the refusals of SNAP and of sync on the guard**, so that a hand-off runs live in this slice (forge and
   beacon had recommended a seeded store, with the lifting as a later gated part). The gates that name the start of
   the importer on the guard come into C5a with it and close before that part: B-297, B-300, and the fork vectors of
   B-289. Mining was not asked and stays refused (B-293).
2. **After a hand-off on proof of stake, the gap to the consensus client's head is closed by an adoption in place of
   a straight extension of the anchor**, in chunks, under a bound of its own (`recovery.max-catch-up`, 8192); the 64
   stays the bound of a side branch. The gap is at least 64 blocks by construction (the pivot is re-pegged to the
   head less 64), so without this every hand-off would end `TooLong` and escalate (beacon).
3. **The loop guard of a proof-of-work escalation is permanent for a branch**: keyed by the branch's first block above
   the fork, persisted, ended by an operator only, said at ERROR with a metric. A different branch is a new
   escalation. It is a knowing departure from the heaviest chain for a branch this node cannot follow.
4. **What waits for 64**: the faults of this node (corrupt records, a block that fails when it is executed again).
   **A chain that cannot be reached escalates at once.** The unit: on proof of stake, 64 `forkchoiceUpdated` in a row
   that are refused, and no less than 64 slots of time; on proof of work, 64 blocks of growth of the branch that is
   refused. One count, set back by any move of the head that succeeds.

**Taken as the three read it, with no objection**:
- the layout guard only reads; the stamp stays the genesis commit's (vault);
- the ordered clear also sets back what SNAP keeps of its run (the pivot, the progress, the flags of a phase done,
  the best block), in its one synced batch: left, a flag that says the accounts are done over rows that are gone lets
  the next run skip them (vault, forge);
- the clear deletes `state.origin`, `state.finalized` and `state.changeset.pruned-to`, and keeps `state.layout`
  (beacon); the memory of the finalized block starts again with the first fork choice after the hand-off;
- **before an escalation for a chain that cannot be reached, the branch's headers are validated** from the fork to
  its tip and its weight worked out again (forge): the reorganisation refuses on the claimed difficulty before a
  block is validated, so a forged branch that forks below the records would otherwise cost every such node a sync
  from scratch every cool-down, for no work;
- a body of an ancestor that is missing when ommers are validated is a fault of this node, not an invalid block; the
  window below a pivot on ETC is, since B-302, the bodies of the pivot and the four below it whose headers name ommers
  (`AncestorWindow.check`; data-model E.1 and G), and the 256 hashes are checked by hash, not by the index (forge);
- the floor of the pivot of an escalated sync on proof of work is the common block, never the old head, since a
  heavier chain may be shorter (forge);
- SNAP never starts over an anchor above the genesis, and `clearDoneOnStart` is refused on the guard (beacon).

**The parts**, each mergeable alone:
1. the layout guard and the order of a start (T194, T203);
2. the ancestor window, and the ommers' ancestors that never end on a missing body (T198, T204, B-283);
3. the clear: its keys, the batch, the ranges, the repeat at a start, the genesis clear (T165, T172, T197, T205);
4. the hand-off sample (T202, T196);
5. the hand-off commit, with the scans and the audit of the hash store held off (T201, T195, T199, B-243);
6. the escalation's policy and its triggers on both chains, saying only (T163, T174, T182);
7. the escalation armed: the handler, the cool-down, the loop guard, the validation of the headers (T164, T166, T173);
8. the catch-up after a hand-off on proof of stake;
9. the gates of the importer on the guard (B-297, B-300), and the fork vectors (B-289);
10. the refusals lifted, and a hand-off run live;
11. the measurements (T207 to T209; T207, M12, and T209 are open).

*The records of C5a parts 1 to 10 and of the gates on part 10 are in [implementation-log.md](./implementation-log.md#c5a-part-records-and-the-gates-on-part-10), verbatim (moved 2026-11-21).*

### Destructive change (C8a and C8b): the vault protocol blocks

```
DELETION REQUIRED: PathToHashExporter (321 lines), startPathPublish and onPathPublishDone (about 75 lines in
SnapFinalization), the PathPublish* messages, getBulkLoadStorage, and three specs (about 560 lines)
Rationale: once execution, commit and serving read the position store (C2 to C5) the copy has no reader, and keeping it would
  keep a second writer of CF n, which the one-layout rule forbids.
Chesterton's Fence: it bridged SNAP's path-keyed output to execution's hash-keyed reads (its own doc, lines 16-34); it carried
  the FR-044 durability property (StateIngest.scala:71-92) and the hand-off sample (spec 022 D9). The sample is ported to
  PathHandoffSample (C5a); the durability property is met by the synced anchor commit (C5a); the copy's read-bound cost is what
  T063 measured (35.7k down to 9.2k nodes/s, 10.8 h, stopped at 40-50%).
Alternative considered: Option X (frozen base read through, about 1,100 LOC), a per-chunk multiGet, keeping the exporter behind
  a flag. The user chose Option Y on 2026-11-06 and ruled out flags that outlive development.
Recommend: DELETE in C8a, after the C7 gate and EG-12.

DELETION REQUIRED: the Hash scheme as live state: ReferenceCountNodeStorage (520), CachedReferenceCountedStorage (292),
FastSyncNodeStorage (30), the Archive, ReferenceCounted and CachedReferenceCounted classes of StateStorage.scala (about 350),
StorageScheme and its 64 references in 18 main files, spec 022's Hash-only mechanisms (excluded-account seed, per-edge reference
counts), the `inmemory` pruning mode (`CachedReferenceCountedStorage`, its LRU and the inmemory-pruning-node-caching block), and about 3,500 lines of their tests
Rationale: ETC moves onto the unified layout in this track; with no Hash-layout datadir allowed to start, none of it has a caller.
Chesterton's Fence: it is today's production storage for ETC and the whole of ETC's SNAP and pruning; it keeps a node alive by
  reference count where an in-place store needs none. Its properties are replaced as follows: reorg undo by BlockDiff (7.3),
  pruning by retention (4.5), the duplicate-commit guard by isBlockApplied, the `inmemory` mode is retired, not replaced (4.6).
  RETAINED: SerializingMptStorage over ArchiveNodeStorage over EphemDataSource, for list tries and tests.
Alternative considered: keep the Hash scheme for ETC until a later gate (the earlier 10b), and run two layouts in one binary.
  The standing rule rejects it: one infrastructure, no staged transition.
Recommend: DELETE in C8b, the last slice, after the C7 gate; KEEP-AND-GUARD (the in-development flag) until then.
```

### Exit gate for Track C (C7 runs it; nothing in C8a or C8b merges before it reports, and C8a also waits for EG-12)

All runs use the unified layout with the in-development guard on, and compare with the pinned pre-track jar. **EG-2 and every
roots-equal item run on the ported harnesses of C3c**; a run on the Hash-scheme harnesses would measure the wrong store.

| # | Check | Pass condition |
| --- | --- | --- |
| EG-1 | **Roots-equal import differential.** ETH: fixed ranges of Platåberget and Sepolia, **including Prague and Amsterdam ranges with request and block-access-list activity**, and an ETH mainnet range if a mainnet state exists. **ETC: an offline import from genesis** of ETC blocks 0 to 5,100,000 (`ChainImporter.importChainFileStrict` over a chain file; **the source is this project's own ETC mainnet sync, exported with `fukuii etc export <file> 0 5100000` (the user, 2026-11-07, T010; exported 2026-11-07, `baselines/README.md`)**; a core-geth export or a fukuii full sync with SNAP off are the fallbacks), and a Mordor import from genesis to 2,600,000 (era 0 to 1, Agharta 301,243, Phoenix 999,983, ECIP-1099 2,520,000) | ETH: every state root, receipt root and gas-used equal, and `requestsHash` and `blockAccessListHash` equal explicitly. ETC: **every block validates against its canonical header** (state root, receipts root including the pre-Atlantis intermediate roots, gas used), node-set equality with the pinned jar **every 100,000 blocks**, and every miner and ommer balance equal across the 5,000,000 era boundary and Mordor's two. At Platåberget and Mordor scale the position store walked from the anchor equals a `StackTrie` rebuild. `app.db.state.record.tombstones` is recorded over the ETC import and the Mordor runs (a non-zero count is explained before the bound is frozen, M15). Mordor also under `archive` (EG-10) |
| EG-2 | ethereum/tests and EEST on the ported harnesses | the ETH job and the ETC job green; **`ForkChoiceBlockchainTestsSpec`** (Istanbul and Berlin, i.e. Phoenix and Magneto, through `reorganise`) green under `basic` and `archive`; the pre-Byzantium and EIP-150 networks green (intermediate-root receipts) |
| EG-3 | the matrices of C3b to C6: reorg (PoS and PoW), ETC PoW, crash, isolation and concurrency, fault injection | all green; I1 to I6 asserted; **the deterministic interleaving test** over the importer commit, `moveHead`, `newPayload` and a builder view gives a position map equal to a from-scratch replay; the fault-injection runs never produce INVALID, `markInvalidRecursive`, `reportInvalid` or a delete; the no-anchor refusal and the escalation tests (hysteresis, cool-down, loop guard, kill at every step) pass |
| EG-4 | **Fresh Sepolia sync with 24 h of head following (the T063 rerun)**, under a real consensus client | no `[PATH-PUBLISH]`; hand-off time, disk (expect about 150 GB below T063's 300-340 GB), import p50 and p99 and `[IMPORT-COMMIT]` against the pinned jar (DC-14), heap and RSS, overlay gauges, `app.db.state.view.stale` and `.retries`, the audit's first pass clean; **`app.db.state.record.tombstones == 0`** (a tombstone at the head would turn a routine depth-1 reorg into a multi-hour re-SNAP) |
| EG-5 | **T064's intent on a FRESH datadir** | on a fresh Mordor datadir and then on EG-4's datadir once its measurements are taken: rows deleted, bytes corrupted and positions moved are found and filled, the hand-off completes with no copy, and the audit's first pass records its time and findings |
| EG-6 | **ETC end to end** | a fresh Mordor SNAP sync and 24 h of head following with a local miner (running on dev-node since 2026-11-21), **and a private ETC-config PoW net** (two or three fukuii miners plus a core-geth) that produces routine depth 1 to 3 forks, **with Olympia at a low block** (treasury credit every block, the base-fee floor, genesis `HefPostOlympia`): mined blocks valid, each mined block's root equal to its import root (with ommers), ECIP-1017 rewards unchanged, M7 and M10. **The private-net half is skipped** (the user, 2026-11-20: no Olympia core-geth on the host); Olympia is covered in process (#1901, #1937) |
| EG-7 | repository gates | `sbt pp`; the 016 ratchet; the 017 and stateless-closure dependency checks (DC-8): `NibblePath` is in `mpt/`, no new `db/` import reaches the closure; the grep ratchets (state accessors, hash-only walkers) |
| EG-8 | DC-14 figures in every slice's PR | p50 and p99 `[IMPORT-TIMING]`, longest read wait, bytes written per block, database size, RSS, `[STORAGE-PERF]` |
| EG-9 | **Checkpoint v2 round trip, both chains** | export from the Mordor and Sepolia nodes, import into fresh datadirs: every root equal; the ETC resync time recorded (SC-013). The **ETC mainnet half is EG-12's export**: import it into a fresh datadir, check root equality, follow the head for 1 h |
| EG-10 | **Both pruning modes, and the retired one** | `archive` passes EG-1's Mordor and Platåberget runs; a test shows `pruning.mode = "inmemory"` is **rejected at start on both chains** with the message naming the retired mode and the two that remain; the finalization durability test (kill -9 right after the synced commit) passes under `basic` and `archive` |
| EG-11 | after C8b, on the final tree | `grep` for every removed symbol is empty; `sbt pp`; an EG-1 subset and a short head-follow on each chain, with the flag gone |
| EG-12 | **First successful ETC mainnet sync** *(user decision, 2026-11-06; fukuii has never completed one; forge's route)* | a fresh ETC mainnet **SNAP sync on the unified layout with public ETC peers** reaches the head with no copy and the root equal to the head's `stateRoot`, then follows the head for 24 h. **Passes only with:** zero rejected canonical blocks; zero `REORG-EXEC-FAIL`; the number of routine fork moves (depth 1 to 2) followed, recorded; **the ancestor-window check** (BLOCKHASH in the first 256 blocks and an ommer whose parent is below the pivot) passing; the number of snap-capable ETC peers recorded at start, from the `[SNAP-PEERS]` line (**a declared local core-geth peer counts toward the precondition**, the user, 2026-11-18, and the line says how many were declared); time, disk, heap, RSS and CPU recorded against spec 007's finding. The node then exports the first ETC v2 archive, which is re-imported (EG-9) and followed for 1 h. **Ownership:** C5a the ancestor window, C6 ETC enablement and mainnet readiness (guard removal, peer precondition and logging, the PoW-specific hand-off checks, the run harness), C5c the export, C7 the run. **C8a does not merge before EG-12 passes.** A checkpoint is impossible as a route (no archive exists) and a sync from genesis takes one to three months. **The run waits for the NVMe drive** (the user, 2026-11-20); C8a waits with it |
| EG-13 | **Hive and EEST** | each suite at least equals the pinned jar's recorded baseline: `ethereum/engine` (paris, shanghai, cancun, prague, osaka and amsterdam variants; the reorg, sidechain, invalid-ancestor, "Re-org to Previously Validated Sidechain" and "Transaction Re-Org" families), `ethereum/rpc-compat`, `ethereum/graphql`, `ethereum/consensus`, and EEST `consume-engine` and `consume-rlp` for Cancun through Amsterdam (eip7928, eip6780, eip7610, eip8246 and the 7002, 7251 and 8282 system contracts) |
| EG-14 | **A consensus-client devnet** (kurtosis `ethereum-package`: Lighthouse or Prysm, fukuii and a second execution client) | induced reorgs (a late block, a partition) are followed; the crash matrix under a real consensus client: **kill -9 between `newPayload` and `forkchoiceUpdated`, restart with no peers, the consensus client reaches VALID with no resync**; **an induced escalation**: lower `state.retention.max-blocks` on the devnet and force a deeper reorg, then assert SYNCING (forkchoiceUpdated) and ACCEPTED (newPayload) during the re-SNAP, then VALID, with no manual step, one escalation only (cool-down), and `t`, `u` and CF `v` cleared at its start |
| EG-15 | **Platåberget live head-follow, at least 6 h**, beside EG-4 | it has the largest blocks, where `max-layer-bytes`, the tombstone guard and the `forkchoiceUpdated` latency (M6, against an 8 s consensus-client timeout) bind; **`app.db.state.record.tombstones == 0`** over the whole run, and the largest diff reported against `max-record-bytes` (M1) |
| EG-16 | **RPC under catch-up** | rpc-compat and `debug_traceBlock` during a regular-sync burst: the stale-view rate is measured (M5 extended), no request returns a stale-view error, and import latency stays within the DC-14 bound |

### Risks and rollback, per slice

There is no operator-facing rollback after C8b and none is promised: the client is not in production, and a datadir written
by the unified layout is re-synced to go anywhere else. Until C8b the in-development guard is the rollback; after it, revert
the PR on staging.

| Slice | Risk | Signal | Rollback |
| --- | --- | --- | --- |
| C1 | the shared trie change alters either chain's roots or allocation; a missed or extra position | roots versus the pinned jar; the oracles; M7 (a merge gate) | revert (additive and inert until C2a wires it) |
| C2a, C2b | a reader is handed a hash-only call on a position store and throws; the window clamp changes SNAP serving; the prefetch feeds execution an unverified node | decorator-completeness test; the grep ratchets; SNAP serve differential; rpc-compat | guard off |
| C3a | the record format is wrong and shipped | golden vectors signed in C0; read by nothing until C3b | revert before C3b; after C3b a change is a layout-marker bump (data-model 4.4) |
| C3b | the staged commit diverges from the pinned jar's roots; an Amsterdam block fails to execute; two writers interleave; a writer runs with no anchor while SNAP owns `t`/`u`; a reverse after destroy-then-recreate leaves holes | EG-1 per PR; the by-hash tests; the deterministic interleaving test | guard off |
| C3c | a proposer or an unstaged path writes a row; the harnesses still run on the Hash store | the proposer-never-writes test; `executeBlock(staged = None)` throws; a harness that does not touch the position store fails a canary | guard off |
| C4a | a stale read is misclassified; pinned layers exhaust memory | isolation matrix; `view.stale` and `verify.mismatch`; `overlay.pinned.bytes` | switch to the reader-writer lock (data-model 6.3.3), a one-class change |
| C4b | a pointer writer is missed; a move is planned from a stale index | the writer table as a test; `anchor.lag.blocks`; startup rule 4 rewinds loudly | guard off |
| C4d | the Engine API claims VALID or INVALID where it must not; local re-execution runs inline or never; an escalation fires on a consensus-client flip-flop, loops, or bricks the datadir on a crash | the answer table; fault injection; the crash test under a real consensus client; the escalation counters and the suppressed counter; the kill-at-every-step test | guard off |
| C4e | a heavier PoW branch cannot be adopted; a shallow fork is refused on length; a refusal strands the node | the 33-, 50- and 64-block cases; escalation counters; M13 | switch the PoW arm to reverse-then-commit with rollback (data-model 6.4) |
| C5a | the guard refuses a good datadir or stamps a bad one; hand-off waits forever on the ancestor window | the guard table; M12 | guard off |
| C5b | the audit or the importer deletes a valid node | the stale-never-deletes and the different-valid-node tests | guard off |
| C5c | the v2 stream and importer disagree; a version 1 archive or a wrong-chain archive is imported (ETH and ETC share a genesis hash); a truncated state imports as complete; a failed import is retried on every start and wipes a SNAP run; PR1 ships without PR2 and Hash-layout import is broken | round-trip tests; the three golden streams; the v1, chain-id, genesis-hash and expected-hash refusal tests; the fifth assertion; the no-retry test; PR1 and PR2 in one release | revert; the format is a one-way door, pinned 2026-11-18 (data-model E.1) and signed by forge and beacon at PR1 |
| C6 | ETC SNAP or mining breaks on the unified layout | EG-6; EG-12; the ETC PoW matrix | guard off |
| C7 | the gate fails, or EG-12 does not pass | any EG item | C8 does not merge; fix forward |
| C8a, C8b | a deleted path was still used; the oracle jar is lost | `grep`, `sbt pp`, EG-11 | revert the PR on staging |

### Specialist routing (additions for Track C)

- `vault`: owns every slice, the data-model section, the golden vectors, the overlay, the lock and the isolation code, the retirement.
- `forge`: **signs the ETC side before code** (S0 items 1, 3, 5, 6, 7, 8, 10, 11, 12, 14, 15; item 13 is moot; both reviews are
  recorded in the S0 records, and the applied round-2 changes are verified at the owning slice's PR); **before code** on C1, C3b, C3c, C4b, C4e, C5c and C6; reviews every other slice; owns the ETC gate (EG-1 ETC, EG-2's
  `ForkChoiceBlockchainTestsSpec`, EG-6, EG-9 ETC, EG-12).
- `beacon`: signs the ETH side of every item; **before code** on C1, C2a, C3b, C3c, C4b, C4d and C5c; reviews every other slice;
  owns EG-1 for ETH, EG-4, EG-13, EG-14, EG-15.
- `conduit`: C2b RPC signatures, the stale-view retry and rpc-compat; the RPC harness port in C3c; confirms
  `debug_executionWitness` and the trace paths are unchanged; EG-16.
- `herald`: C2b SNAP serving by root view and the window clamp; the `GetNodeData` answer; C5a and C6 peer behaviour (the
  snap-peer precondition); peer interop on the serve differential.
- `eye`: validates every slice; runs the differential against the pinned jar, the matrices, the harness canary, the devnets and
  the soak.
- `prism`: non-consensus review of C2a, C3a, C4a, C5a, C5b, C8a, C8b.
- `wraith`: compile fallout from the `MptStorage` additions, the `commitWith` mode and the deletions.
- `flow`, `loom`, `mithril`: not needed.


### Project-structure additions and deletions (for plan.md "Source Code")

As built through C6; the deletions are C8a's and C8b's.

```text
src/main/scala/com/chipprbots/ethereum/db/storage/
├── MptStorage.scala                  # getAt, forOwner, updateNodesAt, rawNodesAt (additive; hash-keyed default kept for list tries)
├── MptStorageDecorator.scala         # NEW: forwards every member; the wrapping implementors derive from it
├── PathHandoffSample.scala           # NEW: the spec 022 D9 sample, ported from PathToHashExporter
├── StateLayoutGuard.scala            # NEW: the one marker, the one rule
├── AncestorWindow.scala              # NEW: 256 headers below the pivot, and up to 5 bodies (B-302), a hand-off precondition
└── pathstate/                        # NEW package
    ├── PathStateStorage.scala, PathMptStorage.scala, PathStagedState.scala, PathPrefetchReader.scala, StateLock.scala
    ├── BlockDiff.scala, BlockDiffCodec.scala, ChangesetStorage.scala
    ├── StateOverlay.scala, ForwardLayer.scala, ReverseIndex.scala, StateView.scala
    ├── MoveBatch.scala                # the batch of a head move; moveHead itself is a member of PathStateStorage
    └── StateExceptions.scala         # the StateFault hierarchy: StaleStateViewException, ReorgTooDeep, StateUnavailable, BranchTooLarge
src/main/scala/com/chipprbots/ethereum/mpt/NibblePath.scala   # NEW (in mpt/, so the stateless closure gains no db/ import)
src/main/scala/com/chipprbots/ethereum/domain/StateReconciliation.scala   # NEW: startup reconciliation, I1 rule 4 and I6
src/main/scala/com/chipprbots/ethereum/ledger/StateRecovery.scala        # NEW: local re-execution of a state no longer held
src/main/scala/com/chipprbots/ethereum/blockchain/checkpoint/ # CheckpointArchive v2, exporter, importer (C5c)
src/main/resources/conf/base/state.conf                        # NEW: fukuii.state.*; the guard key is deleted in C8b

DELETED in C8a/C8b: snap/PathToHashExporter.scala, StorageScheme, SnapHashTrie and the Hash arms in snap/,
  db/storage/ReferenceCountNodeStorage.scala, CachedReferenceCountedStorage.scala, FastSyncNodeStorage.scala, the Hash classes of
  StateStorage.scala, inmemory-pruning-node-caching.conf, the storage-scheme config key (no deprecation window)
```

### What the rest of spec 018 must change when this plan is accepted

Tracked by T017 (C0, open). As of 2026-11-21 spec.md has Scope item 10 and FR-033 (the `inmemory` retirement) applied;
the rest of the list is not yet applied (verify against spec.md when T017 is closed).

- `spec.md` Scope items 6, 8 and 10 and `plan.md` Phase 3: item 10 is **one gate** (no 10a/10b); F2 (changesets), F3
  (path-aware resolution) and the hand-off part of F4 move to Track C; F4 keeps only what Track E needs.
- `spec.md` DC-9 and FR-016: the layout moves for **both chains in this track**; **no deprecation window** for
  `storage-scheme` (deleted outright); the refusal is the one marker rule of data-model 5.3, for any non-empty unmarked datadir.
  G-7 is met by Track C. DC-11's two-engine "one minor release" window is also removed under the standing rule (spec
  clarification of DC-11); both engines coexist only within Track E as its differential oracle.
- `spec.md` FR-010 and Key Entities: the record key is `blockHash ++ kind` with a height index; amendment #2's row records
  "local part ships in C3a" (it shipped in #1793).
- `spec.md` DC-3, OQ-5 and Edge Cases: the `inmemory` mode is **retired** (OQ-5's recommendation), which closes its durability gap by
  removal; `pruning.mode = "inmemory"` is rejected at start, with no deprecation window (#1739). The "`pruning.mode = inmemory`"
  edge case becomes "rejected at start".
- `spec.md` Edge Cases: add "a datadir without the layout marker", "a block executed but not promoted at a crash", "a reorg
  deeper than `pruning.history`: `ReorgTooDeep`"; the T063 mid-copy case is now simply "refused".
- `research.md` 17: replace the Option Y cost row with this plan's sizes once C0 resets them (T016).
- `.claude/agent-protocols/storage-rocksdb.md`: the unified-layout rules (anchor, diff, overlay, "never write `t`/`u` outside a
  batch that carries the anchor"), in C8b.

### Remaining open items

The numbers are kept because tasks.md, scripts and code comments cite them ("plan open item 3"). Items 1 and 3 are
decided; 2, 4 and 5 are partly decided; 6 is open. Each decision is also in the "Decision log".

1. **The source of ETC blocks 0 to 5,100,000 for EG-1's ETC half. Decided 2026-11-07 (T010).** This project's own ETC
   mainnet sync on `fukuii-fc` (v0.10.48, SNAP with chain download), stopped, then `fukuii etc export <file> 0 5100000`
   (`ChainTool`), which writes one chain file from the header-validated canonical chain. Exported 2026-11-07 as
   `etc-0-5100000.rlp.gz` (size and SHA-256 in `baselines/README.md`); both the pinned jar and the Track C build replay it
   offline from genesis. It has the one-deterministic-file property of a core-geth export without a core-geth node; a
   core-geth `export` and a fukuii full sync with SNAP off remain the fallbacks. What depends on it: EG-1's ETC half (blocks
   0 to 5.1M validated per header, node-set equality with the pinned jar every 100,000 blocks, miner and ommer balances
   across the 5,000,000 era boundary), and **the only coverage of the pre-Atlantis ETC rules** (ECIP-1010, ECIP-1017 era 0
   to 1, the pre-Spurious-Dragon rules without EIP-161) and of the pre-Atlantis intermediate-root receipts on real ETC data,
   since Mordor starts at Atlantis and ethereum/tests has no ETC 3M-8.77M network. EG-12, the Mordor imports from genesis,
   the ETC ethereum/tests job, EG-6 and every ETH item do not depend on it.
2. **The pinned oracle jar, its recorded roots and the hive and EEST baselines.** Partly decided: the jar is **v0.12.1**,
   with v0.11.0 as the fallback (the user, 2026-11-17; `baselines/pinned-jar.md`, T002). **Open: who keeps it and where
   (the NFS path)** (B-053). It is kept until EG-12 passes, the first ETC archive is imported a second time, and one release
   past C8b. The recorded roots and the hive and EEST baselines are still to be produced (T003 to T007).
3. **The snap-peer precondition for EG-12. Decided 2026-11-18: a declared local core-geth peer counts.** An
   operator-declared static peer (`static-nodes.json`, `sync.snap-sync.snap-server-peers`) counts toward the precondition
   like any other snap-capable peer. At SNAP's start on the guard the node logs one `[SNAP-PEERS]` line with the count of
   snap-capable peers on the chain and how many of them were declared, and EG-12's run records that line, so a run that
   relied on a declared peer says so. With the same decision, a proof-of-work guard node may regular-sync from its genesis
   on the unified layout: with `do-snap-sync = false`, or when no snap-capable peer comes within
   `sync.snap-sync.pow-snap-peer-wait` (10 min) of SNAP's start (one WARN with the count). Chains with a terminal total
   difficulty never fall back from a SNAP start (C6 PR-C, B-067); since 2026-11-24 they take regular sync from the
   genesis with `do-snap-sync = false`, as PoW does (B-314).
4. **Hosts for the live gates, and the Ethash DAG on the miners.** Partly decided: EG-6's Mordor half runs on dev-node
   (since 2026-11-21); EG-6's private ETC-config PoW net is skipped (the user, 2026-11-20: no Olympia core-geth on the host);
   EG-12 waits for the NVMe drive (the user, 2026-11-20). **Open: the hosts for EG-14 (the consensus-client devnet) and
   EG-15 (Platåberget, 6 h)**, and the host for EG-12 once the drive is in.
5. **The EIP-8246 text.** Read on 2026-11-07 against the EIP (`baselines/eip-8246-check.md`): it confirms the design, which
   never depended on it (rule 6 triggers on the parent's storage root, and the one shape it needs, storage cleared and the
   account kept, is what the code and `AmsterdamSelfDestructSpec` produce). **Open: beacon's sign-off of that read (T008)**,
   and the pass count of the 658 `eip8246_selfdestruct_no_burn` EEST fixtures on the pinned jar (T009; baseline 1 passing
   before #1422, `specs/009-amsterdam-fork-support/baselines/eest-v21-amsterdam-2026-09-26.txt:24`).
6. **Archive view reach** (`state.archive.view-reach = 1024`) and its disk cost, after M1 and M9. **Open** (T234, the user).

## Phase 2 — Track E: the engine (after Track C and GT-1; it does not wait for spec 017)

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
| E9 | RocksDB removal (DC-11, FR-017), in Track E once SC-001 and the soaks are green; no operator release window | vault |

Track E exit: SC-001, SC-003, SC-004, SC-006, SC-009 green on both chains; the 1× column of research §15 filled for the
chosen engine.

## Phase 3 — Track F: the flat data model (after Track E's SC-001)

Slices F2 (changesets), F3 (path-aware resolution) and F4 (single-write SNAP ingest, the exporter and Hash-scheme
retirement, ETC on the unified layout) are delivered by **Track C** (Phase 1b, slices C1–C8b); their rows below say what
each was to cover and are not separate work. Track F keeps F1, F5, F6, F6b, F7 and F8, re-based on Track C's layout.

| Slice | Content | Owner / review |
| --- | --- | --- |
| F1 | INF-008 layout ADR accepted (records the user's 2026-10-17 decision that ETC moves onto the unified layout); `data-model.md` changeset and sparse-trie formats final | user; vault |
| F2 | `ChangesetStorage` + `StateStorage` rollback/prune over changesets; `ReferenceCountNodeStorage` retired behind the same `StateStorage` API; golden-bytes updated only for the retired formats; abandon/readopt semantics preserved (016 edge cases) | vault; forge (ETC rollback semantics) |
| F3 | Path-aware node resolution in `mpt/` (FR-012): `(path, hash)` lookups with hash verification; `MptStorage` keeps `get(hash)` for witness replay (017 FR-005's `WitnessMptStorage` is hash-keyed) | **forge + beacon before implementation** (consensus-change protocol); wraith; eye with ethereum/tests |
| F4 | SNAP ingest on the unified layout (FR-015): nodes written once path-keyed, leaves once flat; `PathToHashExporter` retired; the Hash scheme retired and ETC on the unified layout (DC-9, FR-016); 016 FR-044 property re-expressed as E3's barrier; 016 ratchet unchanged; Mordor resync rehearsed (SC-013) | vault; herald (serving unchanged); **forge** (ETC SNAP, mining, rewards) |
| F5 | Key-set recorder + proof walk (FR-013, DC-7) behind a flag; SC-007 byte-equality against 017's recorder on the differential set; BAL self-check; 017 docs amendment (T-006) approved | beacon; conduit (RPC unchanged); vault |
| F6 | Flat execution reads (FR-011) under `StateAccess`: `getAccount`/`getStorage` from flat stores; trie updated from the write set at commit; SC-001 under flat reads; `[IMPORT-TIMING]` compared to Track E | **beacon + forge**; eye |
| F6b | Parallel state root with sparse trie cache (FR-023, DC-13): modified-subtree hashing across independent account and storage subtrees keyed by the BAL (ETH) or the recorded write set (ETC), cache persisting across blocks, invalidated to the common ancestor on reorg; behind a flag; byte-identical roots (SC-001); SC-012 serial-remainder figure | **beacon + forge before implementation**; eye with ethereum/tests and the harness at 30× |
| F7 | Default on for flat reads and the parallel root once F5, F6 and F6b are green; `storage-scheme` is already gone (deleted in C8b, with no deprecation window); `TrieReadMemo`, `PrefetchNodeReader` and `DecodedNodeCache` re-evaluated against the sparse trie cache (OQ-11) | vault; prism |
| F8 | Soaks repeated; the harness at 10× and 30×; SC-002, SC-005, SC-007, SC-008, SC-012, SC-013 reported; pruning modes decided (OQ-3; OQ-5 is closed: `inmemory` is retired) | eye |

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

## Phase 5 — Track R: the read-serving path (after Phase 0; beside E, F and H)

| Slice | Content | Owner / review |
| --- | --- | --- |
| R1 | Bounded read executors (FR-027): dedicated pools for `debug_trace*`, range `eth_getLogs`, `eth_call`/`eth_simulateV1`, witness regeneration (017 DC-16 shares this mechanism); per-method caps in `jsonrpc` config; explicit rejection error; rpc-compat and graphql unchanged | **conduit**; flow; eye |
| R2 | Streaming compile-time codecs for trace and log output (FR-029): circe derivation, fs2 chunked response, json4s removed from those paths; byte-identical JSON on the rpc-compat vectors | conduit; prism |
| R3 | json4s retired from the rest of `jsonrpc/` (FR-029); the dependency removed | conduit; prism |
| R4 | *(spec 021)* Fixed-width `UInt256` is delivered by spec 021 (after spec 020, in parallel with 017); this track only consumes it | spec 021 owners |
| R5 | GT-1 RPC arm re-run after R1–R4 (SC-015, SC-016); figures published beside the comparators | eye |

Track R exit: SC-015 and SC-016; DC-15 holds under the harness; operator docs describe the DC-16 topology
(`quickstart.md`: validating node, snapshot-seeded replicas, cold tier).

## Risks and rollback

| Risk | Signal | Response |
| --- | --- | --- |
| The leading candidate cannot be bound or loses the bake-off | GT-1 | the harness ranks the rest; RocksDB + flat model + close-fence is the floor and is always available |
| The parallel state root diverges from the serial walk | SC-001 under FR-023 | flag off; the serial walk stays the producer; `beacon`/`forge` review the subtree partition |
| The 30× profile does not fit any host we have | R-10 | state the host class; report 10× as the gate and 30× as measured headroom |
| Cold-tier process competes with the node on the 16 GiB host | GT-3 | files-only alternative; or a second host in the runbook |
| Read load degrades import despite executors (shared storage lock or page cache) | SC-015 interference figure | T-010 first; then the engine; per-method caps lowered until the bound holds |
| External material claims figures GT-1 has not produced | FR-028 rule | nothing leaves until the RPC arm has run on the same hardware as the comparators |
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
- `forge` (ETC) and `beacon` (ETH): F3, F6, F6b before implementation; E3, F2, F4 sign-off; both review F5. (`UInt256` review is spec 021's.)
- `herald`: SNAP serving from flat state is unchanged (F4); eth/69 history advertisement (H2).
- `flow`: fs2 `Stream` boundaries for bounded readers (E4); cold-tier queue and backpressure (H1).
- `conduit`: `debug_executionWitness` unchanged (F5); RPC docs (E7); `eth_getLogs` routing (H4); Track R executors and codecs (R1–R3).
- `mithril`: the DC-12 typed API (opaque types, `inline` codecs, enum config) in E1.
- `eye`: every slice's validation; the differential job; the harness runs; soaks.
- `prism`: non-consensus review of E1, E1b, E2, F7, H3.
- `wraith`: compile fallout from the trait cleanup and the `mpt/` signature changes.

## Decision log

The user's decisions for this spec, one line each, oldest first, with where the reasoning is. "IL" is
[implementation-log.md](./implementation-log.md). Reviewer corrections that the user did not have to decide are in the
slice sections and data-model.md, not here.

**2026-10-17**
- The engine is chosen by a measured bake-off (GT-1), not fixed to libmdbx; gigablock volume is the sizing target (the harness at 1×/10×/30×); an optional cold tier is added as Track H (research §13, §14).
- ETC moves onto the unified layout (DC-9): decided, not gated (this closes the old GT-3 question for ETC).
- Read serving is in scope: Track R, the GT-1 RPC read-load arm against Geth, Besu, Erigon and Reth, and the deployment topology DC-16 (research §16).
- OQ-7: the `dbLock` close-fence ships as its own fix PR, once GT-0 has reported (T-010; not shipped as of 2026-11-21).
- OQ-13: fixed-width `UInt256` goes to spec 021 (R-12, R4).
- Implementation starts after spec 017 (amended 2026-11-06, below).

**2026-11-06**
- Track C, the unified path-live layout for both chains, runs first after 0.11.0, on RocksDB, ahead of Track E and of spec 017; spec 017 is parked until execution-specs releases `ExecutionWitness`.
- Track F's items 6 and 8 and the copy and Hash-scheme retirement of item 10 move into Track C.
- Standing rule: one infrastructure for both chains (ETH is the build target, ETC comes along); ETC moves and the Hash scheme is retired in this track, with no 10a/10b split; no release-level transition and no "one minor release" windows (DC-11's included), only a short-lived in-development guard removed in the last slice.
- No legacy-datadir machinery: one layout marker, and a non-empty unmarked datadir is refused at start with no migration (this reverses the earlier adopt-in-place choice for mid-copy datadirs).
- Option Y (path nodes in place, per-block changesets, an overlay) is reconfirmed with the size estimate seen (about 15,000 lines then; about 21,200 after the S0 reviews, T016 to reset it).
- Write-through: the disk layer is the canonical head; state, BlockDiff, anchor and best pointer go in one batch; no journal.
- Only the local part of the changeset (amendment #2) ships.
- Checkpoint import onto the unified layout is funded and required (C5c): it is ETC's resync path.
- T064's intent runs on a fresh datadir (EG-5); the held T063 datadir is not used.
- Two pruning modes on both chains, `basic` and `archive`; `inmemory` is retired and rejected at start (reversing "keep all three" earlier the same day); C4c is withdrawn; this closes the INF-006 section 4 gap by removal.
- The overlay budget stays a guess until M1 and M2.
- The first successful ETC mainnet sync gates Track C (EG-12); the old ETC datadir on `fukuii-fc` was deleted on the user's instruction.
- A pinned release jar outside the tree is the oracle, since no in-tree build can be after C8 (Complexity Tracking).

**2026-11-07**
- The ETC 0 to 5.1M block source is (c): this project's own ETC mainnet sync on `fukuii-fc` and `fukuii etc export` (T010; open item 1; exported the same day).

**2026-11-11** (IL, "Decisions of 2026-11-11 (user)"; #1795)
- C3a merges, with CF `v`.
- 512 blocks of changesets are kept where there is no finality (`state.retention.without-finality`, B-208).
- One downgrade barrier: the release that first ships CF `v` ships every other such change of the track, with one release note.
- `snap1` is not advertised on the guard until the node follows the head (ended 2026-11-17, decision 2 below).

**2026-11-12**
- C3c part 3 is the linear subset only; side blocks, rewinds and the rest go to B-219 (done, #1901) and B-220 (open) (IL, "C3c part 3: the linear subset now").
- Decision 1: execute a heavier branch first, on pinned layers; reverse-then-commit at the pinned cap (IL, "C4a part 3: the bounds").
- Decision 2: more than 32 payloads in a row ahead of a `forkchoiceUpdated` cost a re-execution from stored bodies at the fork choice; the bounds stay (IL, "C4a part 3: the bounds").

**2026-11-14**
- A `Finalized` refusal is answered `-38002` (C4d).
- On PoS, a block of a long branch found invalid moves the head back to the fork choice's last head; on PoW (W4) the executed prefix is kept when it outweighs the old head.
- The designated-head arm (T170) moves from C4d to C4e.
- The armed ordered clear lands with C5a, not before; the escalation trigger moves to C5a with it (T163 to T165, T174; #1843).
- The fork choice's own finalized block is not used to plan against: the `Finalized` refusal stays held against the finalized block named before (IL, "C4d part 2b").
- A branch longer than the forward layers is committed in place (C4d part 4a), with three side fixes: `eth_syncing` says syncing during an adoption; a never-executed head's arm answers `-38002` where it would reverse the finalized block; `safe` never names a reversed block (IL, "C4d part 4a", "C4d part 4b").
- Startup reconciliation goes with what is on disk: rule 4 only, rules 2 and 3 not built (IL, "C4b part 4").

**2026-11-15** (C5a intro above; IL, C4e part 6 and C5a)
- Fork detection (`SYNC-FORK`) moves no state; the fetcher restarts from the common block.
- The PoW escalation is folded into C5a with the PoS trigger.
- C5a lifts the refusals of SNAP and of sync on the guard, so a hand-off runs live in this slice.
- On PoS the gap after a hand-off is closed by an in-place adoption in chunks (`state.recovery.max-catch-up`, 8192).
- The loop guard of a PoW escalation is permanent per branch: persisted, ended only by an operator (`forget-given-up-branches`).
- A fault of this node waits for 64 (refused fork choices and 64 slots on PoS, 64 blocks of branch growth on PoW); a chain that cannot be reached escalates at once.

**2026-11-16**
- The pivot of the sync after an escalation is held to the refused branch; after a bounded number of attempts the requirement is dropped with an ERROR (IL, "C5a, a gate on part 10: the pivot ...", B-307 c1).
- A fork deeper than the fetcher holds is refused (`ReorgRefusal.BeyondTheFetch`) and left to the escalation (IL, "C5a part 9c").
- v0.12.0 is cut after C5a part 10 (B-312).

**2026-11-17**
- C5a part 10, decision 1: a hand-off that left accounts out heals first and is not let through on the guard.
- Decision 2: SNAP is advertised on the guard (ends the 2026-11-11 decision).
- Decision 3: regular sync from the genesis is refused on the guard (narrowed for PoW on 2026-11-18, ended on 2026-11-24, below) (IL, "C5a part 10").
- A store that failed closed is persisted synced as `state.fault` and opens closed until a clear (B-252).
- The oracle jar is v0.12.1, with v0.11.0 as the fallback (`baselines/pinned-jar.md`).

**2026-11-18**
- Checkpoint v2, decision 1: the ancestor window comes directly after the header block (data-model E.1).
- Decision 2: an estimated TD is accepted with a WARN (the TD-exact flag).
- Decision 3: on PoS the window is headers only and only a finalized block is exported.
- Decision 4: a failed or killed import is cleared and not retried (`checkpoint.import.refused`, `checkpoint-sync-retry-refused`).
- Only a content fault records an archive as refused (B-384; data-model E.1, "Names").
- B-370 (in #1921): the overwrite is done under the lock, after the `AnchorReach` check.
- Open item 3: a declared local core-geth peer counts toward EG-12's snap-peer precondition, with a `[SNAP-PEERS]` line.
- A PoW guard node is exempt from the refusal of regular sync from the genesis (`do-snap-sync = false`, or no snap peer within `pow-snap-peer-wait`, 10 min); chains with a TTD keep the refusal (ended on 2026-11-24, below).
- B-385 is solved as a local fault (#1943).

**2026-11-19**
- B-369: the audit is enabled, one pass per pivot (#1947).
- B-074: the hand-off holds when its re-read finds excluded accounts (#1948).
- B-392: the seal-only first header applies to `forkWeightNotStored` (#1949).
- B-376: recovery refuses on positions (#1950).
- B-391: the seal-only first header (#1938).

**2026-11-20**
- The Olympia treasury is `d6165F3aF4281037bce810621F62B43077Fb0e37` (B-388, #1966).
- EG-12 / T264 wait for the NVMe drive; C8a waits with them.
- The private Olympia ETC net (T244's run, T258's half, EG-6's second half) is skipped: there is no Olympia core-geth on the host.
- EG-6's Mordor half runs on dev-node (open item 4).

**2026-11-21**
- B-411: state RPCs resolve the block by header, for geth parity (#1976).
- B-413: the Ethash DAG is memory-mapped off-heap (#1977).

**2026-11-24**
- B-314: regular sync from the genesis is taken on the guard on every chain, a chain with a TTD included ("allow by default"; ends decision 3 of 2026-11-17). hive's engine simulators, devnets and Ephemery start from the genesis with the consensus client building the chain, and every path-live hive engine test had died at its start on the refusal. Still refused at start: `do-snap-sync = false` on a node that holds no state at all (`PathLiveSync.regularSyncWithNoStateRefusal`).
