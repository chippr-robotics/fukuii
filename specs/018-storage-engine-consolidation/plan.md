# Implementation Plan: Storage Engine Consolidation

**Branch**: `018-storage-engine-consolidation` | **Date**: 2026-10-17 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/018-storage-engine-consolidation/spec.md`; evaluation in
[research.md](./research.md).

**Status**: Drafted ahead of implementation so `/speckit-tasks` can run once the gates report. Phase 0 can start after
spec 016 completes; Track E after spec 017 completes and GT-1 has chosen the engine; Track F after Track E's SC-001;
Track H after GT-3. **Revised 2026-10-17**: engine chosen by bake-off (not fixed to libmdbx); gigablock harness; cold
tier (Track H); ETC on the unified layout is decided, not gated. **Revised again 2026-10-17 (read-serving)**: Track R (bounded read executors, streaming codecs,
fixed-width `UInt256`), the GT-1 RPC read-load arm against Geth, Besu, Erigon and Reth, the `dbLock` close-fence as a
standalone fix PR, and the deployment topology (DC-16); see research §16 for the assessment that prompted them.
**Revised 2026-11-06 (after T063; spec Clarifications, session 2026-11-06):**
- **Track C** (Phase 1b), the unified path-live layout for both chains, runs first after 0.11.0, on RocksDB, ahead of
  Track E and of spec 017. Spec 017 is parked until execution-specs releases `ExecutionWitness`, and only items 7 and 9
  depend on it.
- Track F's items 6 and 8 and the copy and Hash-scheme retirement of item 10 move into Track C.
- Under the user's standing rule (one infrastructure for both chains, no staged or release-level transitions, no external
  users of old versions), the transition windows in this plan are removed.

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
| Two engines compiled in, within Track E only *(revised 2026-11-06)* | CI needs both for SC-001's differential while Track E is in development | the "one minor release" operator window is removed under the user's standing rule: no external operator relies on old datadirs. RocksDB is removed in the same track once SC-001 and the soaks are green |
| A pinned pre-track jar as the oracle *(Track C, 2026-11-06)* | Track C deletes the Hash scheme, so no in-tree build can serve as the roots-equal oracle after C8 | keeping the Hash scheme in tree as an oracle would keep two layouts, which the standing rule rules out |

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
  the transition window.
- GT-2: constitution amendment + INF-007 merged.
- Re-run the Constitution Check.

## Phase 1b — Track C: the unified path-live layout (after 0.11.0; before Track E and spec 017)

**Status**: design **revised after S0 review rounds 1 and 2** (`beacon` for ETH, `forge` for ETC). Round 1 (2026-11-06): 3 REJECTs
resolved, every SIGN WITH CHANGES applied. **Round 2 (2026-11-07): no REJECT; every SIGN WITH CHANGES applied** (see "S0 record
round 1" and "round 2" below). There is no round 3: each reviewer verifies the applied changes at the owning slice's PR, and
"S0 prerequisites per slice" says which text each slice waits on. C1 to C3a wait on nothing. Written by `vault`. Companion: [data-model.md](./data-model.md) (formats, overlay, design record A
to I). Evidence base: `wt/plan-018-021` at staging `49dd46d1b` (v0.10.47). Nothing here is implemented.

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
  message unless it is empty. No adoption, no migration, no legacy reader, no finished-copy versus mid-copy detection. This
  reverses the user's earlier adopt-in-place choice.
- **No release-level transition.** No flag ships default-off for a release and flips later; no "one minor release" windows.
  The only flag is an in-development guard that keeps staging green between PRs and is deleted, together with the Hash-scheme
  code, in the last slice.
- Kept: Option Y, write-through (no journal), the changeset record, checkpoint import funded and required, T064's intent on a
  fresh datadir, the overlay budget left to M1 and M2. **Two pruning modes on both chains, `basic` and `archive`: `inmemory` is
  retired** (user, later on 2026-11-06, reversing "keep all three") and rejected at start on both chains with a clear message,
  with no deprecation window.

### Summary

| Item | Slices | What it delivers |
| --- | --- | --- |
| 8, path-aware node resolution | C1, C2a, C2b | The trie reports positions; a position store resolves by `(owner, path)` with hash verification; every reader can be handed it; the typed `StateFault`s exist |
| 6, changesets | C3a, C3b, C3c, C4a, C4b, C4d, C4e (C4c is withdrawn; its id is not reused) | The `BlockDiff` record and CF `v`; the in-place canonical commit under the state lock; the proposer view, the bypass writers and the ported test harnesses; the overlay; head moves planned from the anchor; the PoS arms (Engine API, local recovery, finality-aware retention) and the PoW arms (pinned branches, `SYNC-FORK`, result mapping) |
| 10, one gate | C5a, C5b, C5c, C6, C7, C8a, C8b | Hand-off without a copy, with the ancestor window; the layout guard; the audit, on-demand fetch and recovery scans by position; the checkpoint v2 format and importer; ETC enablement and mainnet readiness; the soak and exit gate; then deletion of the exporter and the Hash scheme and removal of the guard flag |

**Order.** Item 6 lands before item 10, as decided. Because C3 and C4 cannot be soaked on a live chain without a hand-off that
does not publish, the hand-off (C5) is built behind the same in-development guard before the soak (C7). The guard
`fukuii.state.path-live` is `false` while slices land, selects the unified layout when `true`, and is removed in C8b; no
release is cut from the track with the guard as an operator option, and the soak runs with it on. **The harness port (C3c) is
before every slice that claims a roots-equal check**, because a gate that ran on the Hash-scheme harnesses would prove nothing
about the position store (S0 record, item 14).

**The oracle.** The Hash scheme is deleted in this track, so no in-tree build can serve as the differential oracle after C8.
Before C1 the last pre-track release is **pinned as a jar outside the tree** (SHA-256 recorded), with recorded roots for the
fixed Mordor, Sepolia and Platåberget ranges, **and the ETH hive and EEST baselines (pass lists per suite)**, and kept until
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
none of it. (`inmemory` was costed at about 450 main and 400 test lines in C4c and is withdrawn.) Nothing here is measured; M1 to M14 (data-model 13) replace the guesses, and the sizes are set again at the end of C0.

| Slice | Content | Owner / review |
| --- | --- | --- |
| **C0** | **S0**: rounds 1 and 2 are done (records below). Golden vectors 1 to 3 (verified independently by both reviewers) and the oracle tests frozen; the pre-track jar pinned with the hive and EEST baselines and the fixed ranges' roots recorded; the **EIP-8246 text read from an EIPs clone, and the pass count of the 658 `eip8246_selfdestruct_no_burn` EEST fixtures recorded** (on the pinned jar's harness now, on the ported harness at C3c); the source of the ETC 0-5.1M blocks named or listed open (it gates C7, not code); M14 measured; M1 reports the largest observed diff against `max-record-bytes`. Docs and measurements only | vault authors; the reviewers have signed (no round 3); user accepts the remaining open items |
| **C1** | Item 8a. `NibblePath`; position threading through `MerklePatriciaTrie.put`, `remove` and `fix`; reporting of replaced and absorbed nodes only; the first-event prior rule; `MptTraversals.collapseTrieAt`; the additive `MptStorage` members (`getAt`, `forOwner`, `resetOwner`, `updateNodesAt`, `rawNodesAt`); `MptStorageDecorator`. Consensus byte-identical on both chains. **M7 on Mordor within the spec 007 allocation budget is a merge gate** | vault; **forge and beacon before code**, both review the PR; wraith; eye |
| **C2a** | Item 8b. The position store's read side: `PathMptStorage` (verifying, owner-scoped), the stale / corrupt / absent classification, the `StateFault` hierarchy, the anchor-only `StateView`, `PathStateStorage` read members, owner threading in `InMemoryWorldStateProxy` **and in the genesis loader's storage tries**, `PathPrefetchReader` over `view(parent)`, the seven implementors on the decorator base. Wired behind the in-development guard | vault; beacon (execution read path, BAL prefetch); forge (shared `InMemoryWorldStateProxy`, genesis); prism |
| **C2b** | Item 8c. The readers: `Blockchain` and `BlockchainReader` state reads, `eth_getStorageAt` / `eth_getProof` / GraphQL signatures, the four readers the first table omitted, `StxLedger` and simulate, SNAP serving by root view and the served-root window clamp, checkpoint export (v1 here), the four recovery walkers, the hash-only mpt walkers, the **allow-list grep ratchet** on the four state accessors, `GetNodeData`, RPC stale-view retry | vault; **conduit** (RPC signatures); **herald** (SNAP serving); beacon; eye |
| **C3a** | Item 6a. `BlockDiff`, `BlockDiffCodec` (structure-only rejections, the incomplete tombstone), `ChangesetStorage` (CF `v`, height index), `StateChangesetNamespace`, config keys. The format is a one-way door: golden vectors 1 to 3 signed before merge | vault; prism; **forge and beacon sign the golden vectors** |
| **C3b** | Item 6b. `PathStagedState` with the **by-hash in-block layer** and the first-event prior; per-block reset tracking and the prefix-scan enumeration of a replaced storage trie with its bound and tombstone; **the state lock, the anchor compare-and-set, the epoch and the `StateMove` publication for the canonical commit**; `buildExtra(adopted)`; the three already-applied cases; **an absent anchor refuses every in-place writer**; `isBlockApplied` and `stateAvailable`; `stageBlock(parent, bn)`; the `basic` and `archive` modes; `executeBlock(staged = None)` throws; **a reset owner's priors come from the prefix scan** | vault; **beacon and forge before code**; eye |
| **C3c** | Item 6b'. The **proposer view** for every proposer path (PoW generators, `eth_getWork`, `NoOmmersBlockGenerator`, `test_mineBlocks`, the Engine API builder, MockedMiner chaining **and its wait for the import**, the cached-world rule); the **bypass writers** (`ChainImporter` lenient and strict, test mode); the **genesis commit** (owner-scoped, empty alloc, refusal of non-empty storage when `ethCompatibleStorage = false`); the **port of the harnesses** (`EthereumTestHelper`, `EthereumTestExecutor`, `ForkChoiceBlockchainTestsSpec`, `EestBlockchainReplay`, `RpcCompatNode`) | vault; **beacon and forge before code** (proposer, genesis); conduit (RPC harness); eye |
| **C4a** | Item 6c. `StateOverlay`: forward layers, the reverse index, the full `StateView`, move-log reads, eviction, **pinned layers**, metrics | vault; beacon (isolation); forge reviews; prism |
| **C4b** | Item 6d. The **move core**: `HeadMove` planned from the anchor's records, `HeadMovePrepared` carried into `promoteToCanonicalHead` and `rewriteCanonicalIndex`, chunked deep moves, startup reconciliation I1 and the index check I6, the sampled post-move check, the `ConsensusStateFault` results and `Consensus.isLocalStateFault`; **`saveLastBlock` a no-op on a path-live store and `executeAndValidateBlocks` stopping at the first commit that no longer extends the anchor; the refusal on reverse depth with `reorg.max-depth` defaulting to the retention floor** | vault; **beacon and forge before code**; eye |
| **C4c** | **Withdrawn: the `inmemory` mode is retired** (user, 2026-11-06). Its work (the undo ring, the persisted tail, the clean-shutdown flush, the restart-reach rules, the mode's durability tests) is not done. What the remaining modes needed from it is already elsewhere: the state-move lock and the crash matrix (C3b, C4b), the finalization durability test (C5a). The id is not reused, so the references to C4d and C4e stay valid. The mode's rejection at start (a one-line config check with a test) is part of C5a | n/a |
| **C4d** | Item 6f, **PoS arms**. `forkchoiceUpdated` ordering; the designated-head arm as reverse-then-commit; `StateRecovery` (local re-execution); the Engine API answer table and the INVALID layer drop; finality-aware retention; **the escalation route: a direct `StateEscalationRequested` message to `SyncController` with PoS hysteresis (64 consecutive refusals), the cool-down and loop guard, the ordered clear (one synced batch with `clearSnapSyncDone`, then CF `v`, `t`, `u`, with a `state.reset-pending` key)**; builder stale-view retry | vault; **beacon before code**; forge reviews the shared `ConsensusImpl`; eye |
| **C4e** | Item 6g, **PoW arms**. `reorganise` with pinned branch layers and the up-front refusal **on reverse depth, not branch length**; the `SYNC-FORK` clamp; the PoW result mapping; `fork.parentSideChain` re-execution; `REORG-PARTIAL` layer reuse; escalation through `RegularSyncStuck` (with the cool-down and loop guard); `archive` moves unbounded and chunked, **with block generation paused for the move** | vault; **forge before code**; beacon reviews; eye |
| **C5a** | Item 10 (hand-off). Hand-off without a publish (both chains); `PathHandoffSample`; `StateLayoutGuard` (one marker, one rule, **refusing a marked datadir too while the in-development flag is off**); the synced finalization commit carrying the anchor; the genesis clear and `HealInvariant.isEmpty`; **`AncestorWindow.ensure` (256 headers, 6 bodies) as a hand-off precondition**; **`pruning.mode = "inmemory"` rejected at start on both chains** | vault; beacon; forge (ETC SNAP wiring, ancestor window); eye; the 016 ratchet stays green |
| **C5b** | Item 10 (audit and recovery). The audit, `repairNodeAt` / `deleteBadNodeAt` (position-scoped, with a real `stillReached`), both on-demand writes of the importer, the recovery scans | vault; beacon; forge (PoW importer path); eye |
| **C5c** | Item 10 (checkpoint), **required**. Checkpoint format version 2: genesis hash in the header, the ancestor-window entries **chained by hash to the checkpoint header and matched to their bodies**, CRC32C, the trust check, the importer's assertions and code completeness, **a genesis anchor cleared at import start**, the golden stream; version 1 refused | vault; **forge and beacon sign the format** (one-way door); eye |
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
| C4e | `consensus/ConsensusImpl.scala`, `ConsensusAdapter.scala`; `blockchain/sync/regular/BlockImporter.scala`, `BlockQueue`; `storage/pathstate/StateOverlay.scala` (pin); the miner and builder pause hook | 530 / 540 | a heavier branch of 33, 50 and 64 blocks adopted, **and one of 300 blocks forking at `best - 2`**; a branch above the pinned bytes raises the retryable `BranchTooLarge`, a fork deeper than the retained depth is refused `Deep` (reverse depth, not length), never invalid; **block generation waits during a chunked archive move**; `REORG-PARTIAL` layers re-used; `fork.parentSideChain` with a lost layer re-executed; the `SYNC-FORK` rewind clamped and escalating; result mapping never reaches `reportIfProvenInvalid`; archive moves of thousands of blocks in chunks, interrupted and recovered |
| C5a | `blockchain/sync/snap/controller/SnapFinalization.scala`; `storage/PathHandoffSample.scala`, `StateLayoutGuard.scala`, `AncestorWindow.scala` (new); `nodebuilder/NodeBuilder.scala`; `snap/heal/HealInvariant.scala` | 810 / 670 | guard table (empty, marked, other version, any non-empty unmarked datadir, each chain; **a marked datadir with the flag off, an unmarked one with it on**); finalization crash matrix; the sample on a seeded store (port of `PathToHashExportCheckSpec`); genesis clear idempotence; **the ancestor window: BLOCKHASH in the first 256 blocks and an ommer whose parent is below the pivot**; `StateIngestDurabilitySpec` order under `basic` and `archive`, and kill -9 right after the synced finalization commit leaves the rows, the anchor and the pointer; `inmemory` rejected at start on both chains; `snap-split/verify.py` green |
| C5b | `blockchain/sync/BackgroundAudit.scala`, `snap/heal/AuditWalk.scala`; `storage/pathstate/` repair members; `regular/BlockImporter.scala`; `StorageRecoveryActor`, `BytecodeRecoveryActor`, `CombinedRecoveryScanner`/`Actor` | 910 / 650 | audit over a store with injected absent, bad-keccak and moved positions; a stale read never deletes; **a decodable node that differs from the expected one is never deleted**; on-demand fetch by position fills a hole mid-import (ETH and PoW importer); both `saveNode` call sites are `repairNodeAt`; recovery scans match the pinned jar's verdicts |
| C5c | `blockchain/checkpoint/CheckpointArchive.scala` (v2), `CheckpointExporter.scala`, `CheckpointImporter.scala` | 860 / 680 | export then import: every root equal, on seeded tries, Mordor, Sepolia; slot-0-leaf shared across tries; identical storage roots across owners; corrupt entry, hash mismatch, CRC32C, truncation, the four assertions; wrong genesis hash and wrong expected block hash refused; **an ancestor window that does not chain (a wrong `parentHash`, a gap, a body that does not match its header) refused `BadFormat`, and `min(256, N)` headers accepted when N < 256; a crash mid-import after a genesis commit leaves no anchor**; the golden stream (data-model E.1); code completeness; version 1 refused; M9 |
| C6 | `blockchain/sync/SyncController.scala` (guard); the peer-count logging; `consensus/pow/*` (if the PoW paths need changes beyond C3c); test harnesses and `scripts` | 440 / 550 | ETC PoW reorg matrix on Mordor and the **private ETC-config PoW net** (two or three fukuii miners plus a core-geth, Olympia at a low block); mined root equals import root; rewards and era-boundary differential; ETC ethereum/tests; the EG-12 harness; M10, M13 |
| C8a | delete `snap/PathToHashExporter.scala` and 3 specs; `StorageScheme`, `SnapHashTrie` and every `StorageScheme.Hash` arm (64 references, 18 files); `PathPublish*` in `SNAPSyncController` and two dispatch specs | about -2,000 / -1,000 | `sbt pp`; 016 ratchet; the EG-1 subset |
| C8b | delete `ReferenceCountNodeStorage` (520), `CachedReferenceCountedStorage` (292), `FastSyncNodeStorage`, the Hash classes of `StateStorage.scala`, pruning caches and config; remove the guard; migrate the remaining tests | about -1,300 / -2,500, plus 700 migrated | `sbt pp`; `grep` for every removed symbol is empty; EG-1 subset on the final tree |

#### S0 prerequisites per slice (round 2)

"Satisfied" means the text is in data-model.md or this plan as of this revision; the named reviewer verifies it at that slice's
PR, and a deviation reopens the item.

- **C0:** read the EIP-8246 text (EIPs clone) and record the 658-fixture pass count; name or list open the ETC block source; M14; M1 with the
  largest diff against `max-record-bytes`; pin the jar, the baselines and the golden vectors. Prerequisites: none.
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
- **C5c:** forge item 8 (a) and (b), the one-way door: the window chained by hash and the genesis-anchor clear (E.1). Satisfied;
  **both reviewers sign the format again at the C5c PR before merge**, as for any one-way door.
- **C6:** none beyond C5a.
- **C7:** beacon item 5 (i) and the escalation (d) in the gate (EG-4, EG-15, EG-14; satisfied); **the ETC 0 to 5.1M block source named
  (open item 1; not satisfied)**, for EG-1's ETC half.
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
deeper than the retained depth refused `Deep`; a `saveLastBlock` after a degraded commit writes nothing; the `SYNC-FORK` blind rewind of 128 against a reach of 64; a fork point at the SNAP pivot (the lowest legal one).

**ETC PoW matrix** (C6, on Mordor, on the private ETC-config PoW net, and on recorded ETC blocks; forge reviews each case): a
heavier fork of depth 1 and 2 (the routine case); a heavier fork of depth `history`; `REORG-PARTIAL`, where the executed prefix
does not outweigh the head and the old head stays, **and its side blocks are re-enqueued**; a fork whose blocks carry ommers
that reference the abandoned branch; the `SYNC-FORK` rewind; an import batch of many blocks each adopted in turn; a mined block
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

"Sign" means: agree, or say what changes. **Rounds 1 (2026-11-06) and 2 (2026-11-07) are done; the rows below state the design as revised by both,
and the two S0 records after this table hold each reviewer's verdict per item.** Items 1 to 5 can change a block's outcome or the
chain a node follows; 6 to 15 are consequences to accept. `forge` signs the ETC side of every item before code and `beacon`
the ETH side; where both are named, both sign.

| # | Decision (as revised by round 1) | Specified in | Why it matters | Signs |
| --- | --- | --- | --- | --- |
| 1 | **Position emission and the vanished-position rule**: the 32-byte stored rule and the always-stored root; the empty trie has no rows; the prior is fixed at the **first event** at a position (a position created in the block has prior None); only **replaced or absorbed** nodes are reported (not the branch `fix` resolves and leaves); a node under execution is answered **by hash** from the staged layer, then by position from the parent view; the oracles include multi-block reverse, several persist rounds per block, EEST Amsterdam fixtures and the pre-Atlantis intermediate-root networks | data-model 2, 3, 6.2, 8 | A missed or extra position leaves a stale or missing node, or lets a reverse restore the wrong state; a position-keyed in-block layer fails every Amsterdam block. It is inside `MerklePatriciaTrie`, which both chains execute | forge and beacon |
| 2 | **Commit timing and state availability**: `newPayload` executes into the overlay; state reaches disk at `forkchoiceUpdated`; `parentValidated` becomes "state available"; the Engine API claims table (VALID, ACCEPTED, SYNCING, INVALID) is pinned; an unavailable parent is **re-executed locally**, with no peer; a post-execution INVALID drops the layer; the new faults never extend `MPTException` | data-model 6.3.2, 7.2, Part 2 D | Changes what the Engine API can claim about a parent after a crash, and which errors can ever become INVALID | beacon; forge reviews the shared code |
| 3 | **Head moves**: planned **from the anchor's records**, never the index; **one state lock with an anchor compare-and-set** over every in-place batch; the state batch is **contributed into** `promoteToCanonicalHead` and `rewriteCanonicalIndex` before their commit; `currentState` is set only after a successful move; a block that is a forward layer is **promoted**, not dropped; the designated-head arm is reverse-then-commit; PoW branches are **pinned**; chunked deep moves; runtime refusals escalate and never apply I1 rule 4; the corrected writer audit (importer lenient path, test mode); the `SYNC-FORK` clamp; PoW result mapping. **Round 2:** the refusal is on **reverse depth** (`best - fork`), not branch length, and `reorg.max-depth` defaults to the retention floor; **an absent anchor refuses every in-place writer**; `saveLastBlock` writes nothing on a path-live store and the batch stops at the first commit that no longer extends the anchor; block generation pauses during a chunked move | data-model 5.1, 6.4, 7.0, 7.1, 7.3, Part 2 A, C, D | A reorg that moves the pointer without the state, or the state without the pointer, or a diff applied onto the wrong base, forks the node from its own history | forge (PoW) and beacon (PoS) |
| 4 | **Isolation**: every in-place batch is a state move that publishes pre-batch values and bumps the epoch; "anchor view" is decided at read time; the importer's delete is position-scoped with a real `stillReached`; the fault hierarchy and the caller mapping are pinned by fault injection; RPC retries a stale view; the lock is the fallback | data-model 6.3 | The missing-node machinery deletes and refetches; a stale read mistaken for corruption would delete a node the head needs, and a storage fault must never become INVALID | beacon; forge reviews |
| 5 | **Replaced storage tries**: account removal, recreation, EIP-161 touch-deletion, ETC's SELFDESTRUCT (no EIP-6780 on any ETC fork), Amsterdam's clear: **per-block reset tracking; enumeration by prefix scan of CF `u`**; ordering within a block; above the bound the deletion is **skipped and the record is an incomplete tombstone**, so a valid block is never failed; reverse across a tombstone is refused. **Round 2:** for a reset owner every prior is the scanned parent row (overriding the first-event prior), the tombstone refusal is required, and EG-4 and EG-15 assert zero tombstones | data-model 3 rule 6, 4.2, 10, 13 (M15) | A local resource bound must not fail a canonical block (a mining node would mine a minority fork) | beacon (EIP-6780, EIP-8246) and forge (ETC semantics) |
| 6 | **Genesis on the unified layout and `HealInvariant.isEmpty`**: owner-scoped, insert-only, **no BlockDiff for genesis**; test mode uses the same commit; non-empty genesis storage is refused when `ethCompatibleStorage = false`; SNAP start clears `t`/`u` and the anchor | data-model 5.4 | Otherwise every fresh datadir is pre-invariant and pays the walk spec 022 removes | beacon; forge for ETC |
| 7 | **The layout guard**: one marker, refuse any non-empty unmarked datadir on either chain, no adoption, no probe of CF `n`. **Round 2:** while the in-development flag exists, a build with it off also refuses a datadir that has the marker | data-model 5.2, 5.3 | A wrong refusal strands a node; a wrong stamp runs on a half-state | beacon; forge; user |
| 8 | **Checkpoint format v2 and the importer**: genesis hash in the header, the **256-header and 6-body ancestor window** as tags 0x03 and 0x04, CRC32C, the operator-supplied expected block hash and the stated trust in total difficulty, the importer's four assertions, code completeness, a golden stream; v1 refused. **Round 2:** the ancestor window is chained by `parentHash` to the checkpoint header and each body matches its header (`min(256, N)` when N < 256); a genesis anchor is cleared at import start | data-model E.1 | A one-way door, and ETC's resync path | forge and beacon |
| 9 | **`get(hash)` on a position store** answers the account root and overlay nodes only; the recorder sits **above** the cache and records parent-state reads only; every hash-only walker is converted or allow-listed under a grep ratchet; a hit by hash can mask a positional hole | data-model 6.5, 9 | The witness recorder must see every parent-state read | beacon; herald confirms |
| 10 | **Owner threading and RPC signatures**: `forOwner`, `getAt`; the four omitted readers; an allow-list ratchet on the four state accessors; RPC retries a stale view | data-model 8, Part 2 E | The one RPC-visible code change | conduit, beacon; forge |
| 11 | **ETC consensus unchanged on the unified layout**: the evidence is **an offline import of ETC blocks 0 to 5.1M from genesis (the block source is an open item)**, validated per header, with pinned-jar node-set equality every 100k blocks**, a Mordor import from genesis across 2,000,000 and 2,520,000, the ETC ethereum/tests job on the ported harness, `ForkChoiceBlockchainTestsSpec` under `basic` and `archive`, a private ETC-config net for Olympia, and M7. **Round 2:** the block source is logistics and gates EG-1's ETC half (C7), not code; forge signed without it | plan EG-1, EG-2, EG-6 | The track moves ETC's storage with no Hash fallback in tree, and no ETC mainnet pre-state exists | forge |
| 12 | **Block generation and mining**: a proposer view, content-addressed and fresh per attempt, for **every** proposer path; MockedMiner rebuilds from the mined block **and waits for that block's import (round 2)**; cached worlds are never committed or read stale; a stale view retries the build; the mined root equals the import root, with ommers and on the Olympia net | data-model E table | A proposer write in place would corrupt the head | forge (PoW) and beacon (builder) |
| 13 | **Moot: the `inmemory` mode is retired** (user, 2026-11-06). The mode is rejected at start on both chains, and the ring, the persisted tail and the restart-reach rules are not designed. Nothing remains to sign | data-model 4.6 | n/a | none |
| 14 | **Retirement scope**: what is deleted and what stays (the hash-keyed store over `EphemDataSource`); the harnesses are **ported in C3c, before any gate**; both `saveNode` call sites become `repairNodeAt` before `FastSyncNodeStorage` goes; the pinned jar keeps its hive and EEST baselines and its retention | plan C3c, C5b, C8a, C8b | Nothing in tree can be the oracle after C8, and a gate on the Hash-scheme harnesses proves nothing | forge, beacon |
| 15 | **ETC SNAP on the unified layout and the EG-12 route**: guard removal; **the ancestor window** as a hand-off precondition (C5a); the peer precondition; Mordor proven in the dev loop (`tasks.md:391,480`), ETC mainnet never | data-model G | The ETC resync path before a checkpoint archive exists | forge |

### S0 record (round 1, 2026-11-06)

Reviews: `beacon` (ETH side: 3 SIGN, 11 SIGN WITH CHANGES, 1 REJECT, plus the golden vectors) and `forge` (ETC side: 8 SIGN
WITH CHANGES, 2 REJECT, 3 no objection), both read-only, both against the tree at `49dd46d1b`. Neither could read a
reference-client clone or the EIPs; their go-ethereum and EIP claims are from memory and the code's own citations, and the
design marks them "verify" (C0 checks the EIP-8246 text against execution-specs). "Not reviewed" means the reviewer's table did
not list the item. Every finding is applied, except where the last column says otherwise. The letters are the reviewers' own.

| # | beacon | forge | Change made | Where | Round 2 |
| --- | --- | --- | --- | --- | --- |
| 1 | SIGN WITH CHANGES: 1a prior at first touch; 1b only replaced or absorbed nodes; 1c in-block layer by hash; 1d multi-persist and multi-block reverse oracles; 1e empty trie; 1f EEST fixtures | SIGN WITH CHANGES: pre-Atlantis intermediate-root receipts; genesis storage owner threading; genesis emits no vanished positions; M7 a C1 merge gate | All applied. The prior is fixed at the first event; `fix` reports only absorbed nodes; the staged layer answers by hash then by position; the empty trie has no rows; the oracle list grew; genesis tries are owner-scoped | data-model 2, 3, 5.4, 6.1-6.2, 8; plan C1, C2a, C3b | **beacon, forge** (a consensus-adjacent rule changed) |
| 2 | SIGN WITH CHANGES: 2a claims table; 2b one answer for "parent stored, state unavailable"; 2c recover locally; 2d INVALID drops the layer; 2e not an `MPTException`; 2f overlay commit | no objection | The table is pinned (ACCEPTED for `newPayload`, SYNCING for `forkchoiceUpdated`); `StateRecovery` re-executes locally on a bounded dispatcher, never inline; `dropLayer` and `BlockImportGate.forget`; the `StateFault` hierarchy sits outside `MPTException`; `adoptEachBlock = false` is an overlay commit | data-model 6.3.2, 7.2, D; plan C4d | **beacon** |
| 3 | **REJECT**: 3a corrected writer table; 3b plan from the anchor; 3c state lock and compare-and-set; 3d batch-contributing hook; 3e FCU ordering; 3f forward-layer promotion; 3g designated-head arm; 3h ETH reach and recovery | SIGN WITH CHANGES: pin PoW branch layers; clamp the `SYNC-FORK` rewind and never apply rule 4; PoW result mapping; `parentSideChain` re-execution; index check I6; `REORG-PARTIAL` layer reuse | **Resolution of the REJECT:** the writer table is corrected (importer lenient path, test mode) and an unstaged `executeBlock` throws; every plan is made from the anchor's records; one state lock with an anchor compare-and-set guards every in-place batch, a commit that finds the anchor moved becomes an overlay commit; `HeadMovePrepared` is `.and`ed into the pointer batch before its commit; `currentState` is set after the move; a forward-layer block is promoted; the designated-head arm is reverse-then-commit; the retention floor follows the finality lag; refusals escalate. PoW: pinned layers and an up-front refusal, the clamp, new `ConsensusResult`s, local re-execution of lost side blocks, I6, layer reuse | data-model 5.1, 6.4, 7.0, 7.1, 7.3, A, C, C.1, D; plan C3b, C4b, C4d, C4e | **beacon, forge** |
| 4 | SIGN WITH CHANGES: 4a every in-place batch is a move; 4b pre-batch bytes; 4c anchor view at read time; 4d position-scoped delete; 4e fault mapping; 4f memory-model note; 4g RPC retry; 4h M5 under catch-up | no objection (a second stale failure must map to a retryable non-invalid result on PoW) | All applied; the PoW mapping is in the fault table | data-model 6.3.1-6.3.3, 7.0; plan C3b, C4a | **beacon** (forge reviews) |
| 5 | SIGN WITH CHANGES: 5a the guard never fails a valid block; 5b per-block reset tracking; 5c enumerate outside the EVM path; 5d holes do not block import; 5e tests; 5f verify EIP-8246 | **REJECT**: skip above the bound and mark incomplete; enumerate by prefix scan of CF `u`; in-block ordering; ETC vectors; fix risk 6 | **Resolution of the REJECT:** above the bound the dead trie's deletion is skipped and the record becomes an incomplete tombstone (flag bit 1); the block commits; a reverse across a tombstone is refused; the owner's rows are listed by prefix scan, so a hole cannot fail a block; reset tracking is per block and across transactions; the ordering and ETC test vectors are in the matrix; risk 6 is corrected (no EIP-161 on ETC before Atlantis) | data-model 3 rule 6, 4.2, 4.3 vector 3, 10, 14; plan C3a, C3b, C6 | **beacon, forge**; C0 checks the EIP-8246 text |
| 6 | SIGN WITH CHANGES (minor): 6a test-mode genesis; 6b empty-alloc root | SIGN WITH CHANGES: owner-scoped genesis; no genesis BlockDiff; harnesses seed through the genesis commit; `ethCompatibleStorage = false` mismatch | Applied: the genesis commit has no BlockDiff, is owner-scoped and insert-only; test mode and the harnesses use it (C3c); the mismatch is resolved by **refusing** non-empty genesis storage on such a net (a private-net case) | data-model 5.4, 2; plan C3c | **beacon, forge** |
| 7 | SIGN | not reviewed | No change | data-model 5.2, 5.3 | **forge** (not reviewed in round 1) |
| 8 | SIGN WITH CHANGES (minor): 8a golden stream; 8b code completeness | SIGN WITH CHANGES: genesis hash in the header; ancestor window (tags 0x03, 0x04); trusted total difficulty and an expected block hash; the four importer assertions; checksum decided | All applied; CRC32C chosen | data-model E.1; plan C5c | **beacon, forge** (one-way door) |
| 9 | SIGN WITH CHANGES: 9a recorder above the cache; 9b parent-state reads only; 9c hash-only walker ratchet; 9d positional holes | no objection | All applied; `GetNodeData` removal by eth/67 recorded | data-model 6.5, 9; plan C2b | **beacon** |
| 10 | SIGN WITH CHANGES: 10a four omitted readers; 10b allow-list ratchet; 10c RPC retry | not reviewed | All applied | data-model E; plan C2b | **beacon, conduit, forge** (not reviewed) |
| 11 | SIGN (no ETH impact) | SIGN WITH CHANGES: offline import of ETC 0-5.1M from genesis; Mordor import across 2,000,000 and 2,520,000; ethereum/tests on the ported harness; `ForkChoiceBlockchainTestsSpec`; a private net for Olympia | All applied to the gate; **the source of the 0-5.1M blocks is not named: an open item** | plan EG-1, EG-2, EG-6; open items | **forge** |
| 12 | SIGN WITH CHANGES: 12a content-addressed, fresh per attempt; 12b stale view retries; 12c Amsterdam test; 12d `testing_commitBlockV1` | SIGN WITH CHANGES: every proposer path named; MockedMiner chaining; the cached world rule; context; mined root with ommers and Olympia | All applied | data-model E, 6.2; plan C3c | **beacon, forge** |
| 13 | SIGN WITH CHANGES: 13a persisted floor or re-pivot; 13b matrix | **REJECT**: persist a tail of K diffs; optional shutdown flush; `ReorgTooDeep(restart)` must escalate; M11 against fork depths | **Moot: mode retired** (user, later on 2026-11-06). Round 1's resolution (a persisted tail of K = 16 and a shutdown flush) is withdrawn with the mode. What survives, because it is not specific to `inmemory`: `ReorgTooDeep` and `StateUnavailable` escalate through the stuck-sync escape valve, never strand | data-model 4.6, D | **none** (moot; dropped from both lists) |
| 14 | SIGN WITH CHANGES: 14a port the harnesses before the gate; 14b baselines in the jar | SIGN WITH CHANGES: migrate before C7; both `saveNode` sites; jar retention until EG-12 and a second import | All applied: the harness port is in C3c; `repairNodeAt` replaces both sites in C5b; the jar keeps hive and EEST pass lists and is kept until EG-12 passes and past C8b | plan oracle paragraph, C3c, C5b, C8a, C8b; data-model 7.5 | **beacon, forge** |
| 15 | SIGN (no ETH impact; ETH's Path selection must be unchanged) | SIGN WITH CHANGES: the EG-12 route; the ancestor window; C6 renamed | Applied: `AncestorWindow.ensure` is a hand-off precondition (C5a); C6 is "ETC enablement and ETC mainnet readiness"; the peer precondition | data-model G; plan C5a, C6, EG-12 | **forge** |
| GV-1 | SIGN WITH CHANGES: vector 2's 2-byte prior breaks the inline rule | n/a | Vector 2 now has a 37-byte extension-node prior (219 bytes, CRC `707894bf`); the codec is stated to check structure only (the inline rule is a writer assertion); vector 3, an incomplete tombstone, is added (144 bytes, CRC `ae4e831f`). Vector 1 is unchanged | data-model 3, 4.2, 4.3 | **beacon** re-derives, **forge** |

**The reviews' unlisted risks, each as a design change, a slice task or a gate item.**

| Risk | Resolution |
| --- | --- |
| beacon R1, forge 8: harnesses and the lenient import bypass the position store | C3c ports them; an unstaged `executeBlock` throws; the lenient import is converted (data-model 7.6, A) |
| beacon R2: concurrent writers, no compare-and-set | the state lock and compare-and-set (data-model 7.0); a deterministic interleaving test is a gate item (EG-3) |
| beacon R3: Amsterdam BAL before/after reads | the by-hash in-block layer (data-model 6.2); the Amsterdam test in C3b and C3c |
| beacon R4: storage exceptions on the INVALID path | the `StateFault` hierarchy and the caller table; the fault-injection matrix (EG-3) |
| beacon R5, 11: ETH reach below the finality lag; reorg across the pivot | finality-aware retention; `ReorgTooDeep(Pivot)`; escalation (data-model 4.5, B, D) |
| beacon R6: FCU sets `currentState` before the move | order reversed (data-model 7.3) |
| beacon R7: coalescing and `fix` | rules 1 and 5 of data-model 3 |
| beacon R8, forge 1: layers against long branches | pinned layers; the designated-head arm without a stack; the up-front refusal (data-model 6.4) |
| beacon R9: BAL prefetch unplaced | `PathPrefetchReader` in C2a |
| beacon R10: system calls | EG-1 includes Prague and Amsterdam ranges with request activity |
| forge 2: recovery paths deeper than the reach | the `SYNC-FORK` clamp; escalation instead of rule 4 |
| forge 3: PoW error mapping | the PoW `ConsensusResult`s (data-model C.1) |
| forge 4, 5: the size guard fails a valid block; replaced-trie walk meets holes | tombstone; prefix scan (data-model 3 rule 6) |
| forge 6: index ahead of state during `reorganise` | invariant I6 (data-model 5.1) |
| forge 7: archive reach 1024 against a 3,700 to 7,000-block ETC reorg | archive moves are unbounded and chunked; only views are bounded (data-model 4.6, 7.3) |
| forge 8: EG-2 does not test the unified layout | C3c; EG-2 is valid only after it |
| forge 9: MockedMiner and getWork worlds | data-model E, block generation |
| forge 10: `ethCompatibleStorage = false` is not ETC | the wording and the test are scoped to private nets |
| both: wrong file:line or fact (`walkTrie` at `:274-306`; ETC's trie is about 90M nodes; no EIP-161 on ETC before Atlantis; the first reader table omitted four readers; the SYNCING versus ACCEPTED answer) | corrected in data-model E.1, 14, 3, E and D |

**Round 2 re-sign list (as set after round 1; round 2 is done, see its record below).** `beacon`: items 1, 2, 3, 4, 5, 6, 8, 9, 10, 12, 14 and the golden vectors (GV-1, vectors 1 to 3
re-derived independently). `forge`: items 1, 3, 5, 6, 8, 11, 12, 14, 15, and items 7 and 10 (not reviewed in round 1). **Item 13
is moot and is on neither list.**
Items 7, 11 and 15 stand signed by `beacon`, and 2, 4 and 9 by `forge`; both are asked to read the changes to those items for ETH
or ETC impact. `conduit` and `herald` confirm C2b (item 10) and the `GetNodeData` answer (item 9). **Open in round 2 beyond the
re-signs:** the EIP-8246 text against execution-specs (beacon 5f, C0), and the source of the ETC 0-5.1M blocks (open item 1).

### S0 record (round 2, 2026-11-07)

Reviews, both read-only, both of the revised design: `beacon` (ETH side: **10 SIGN, 3 SIGN WITH CHANGES, 0 REJECT**; the three are
item 3, item 5 and the escalation route; items 7, 11 and 15 stand from round 1; item 13 moot) and `forge` (ETC side: **5 SIGN**
(1, 6, 10, 14, 15), **6 SIGN WITH CHANGES** (3, 5, 7, 8, 11, 12), **0 REJECT**; no objection to 2, 4 and 9 after reading their
changes). Both re-derived golden vectors 1 to 3 independently (153 bytes CRC32C `8d0afd3e`, 219 bytes `707894bf`, 144 bytes
`ae4e831f`; each pinned string equals its field-by-field construction). **Every change below is applied** (the text is in
data-model.md and in this plan); "applied" does not mean re-reviewed: each reviewer verifies the applied text at the PR of the slice
that owns it (the next section), and there is no round 3. The letters are the reviewers' own.

| # | beacon | forge | Change made | Where | Status |
| --- | --- | --- | --- | --- | --- |
| 1 | SIGN (note: rule 6's scan takes physical rows as priors, a departure from rule 3's logical prior; say so) | SIGN | The exception is stated in rule 3: harmless, because a forward move deletes the junk and a reverse writes it back unreachable | data-model 3 rule 3 | applied |
| 2 | SIGN (nit: the dedupe row said "canonical entry present"; the code needs header plus receipts and admits side payloads) | no objection | The row is aligned with `EngineApiService.scala:407-416` | data-model D | applied |
| 3 | SIGN WITH CHANGES: (i) the reorg depth is the retention floor, not `pruning.history`, and the measure is reverse depth, not branch length; (ii) the PoS escalation trigger (see ESC); (iii) an absent anchor refuses every in-place writer | SIGN WITH CHANGES: (a) `importToTop` and `saveLastBlock`; (b) refuse on fork depth, not branch length; (c) escalation loop guard and cool-down (see ESC); (d) pause block generation during a chunked archive move | (i, b) `reorg.max-depth` defaults to the retention floor; a move is refused up front only when `best - fork.number` exceeds the retained depth; branch length is bounded by pinned bytes only. (iii) "No anchor" fails the compare-and-set for 7.1, 7.3 and 7.5; only the writers that create the anchor run without one. (a) `saveLastBlock` writes nothing on a path-live store and `executeAndValidateBlocks` stops at the first commit that no longer extends the anchor (`ConsensusStateFault` for the rest). (d) Generation waits during a chunked move | data-model 6.4, 7.0, 7.1, 7.3, 10, A, C.1; plan C3b, C4b, C4e | applied |
| 4 | SIGN | no objection | none | | |
| 5 | SIGN WITH CHANGES: (i) gate on zero tombstones (EG-4, EG-15; C0/M1 shows the largest diff well below `max-record-bytes`); (ii) recommended: keep a record-size-only diff resident as a reverse diff until finalized; (iii) the EIP-8246 text is still open | SIGN WITH CHANGES: for a reset owner the prior of every scanned position is the parent row's bytes, overriding the first-event prior; **keep the reverse refusal across a tombstone** (required: no priors, and later blocks record prior None over leftovers); add a measurement of rows per deleted owner | (i) EG-4 and EG-15 assert zero tombstones; ETC and Mordor runs record the count and explain any non-zero one; M1 reports the largest diff. (ii) recorded as recommended, **not built**. (iii) a C0 task. Forge: the prior rule and the two reasons for the refusal are in rule 6; new measurement **M15** (rows per deleted owner, tombstones, on the ETC import and the Mordor imports) sets `max-entries-per-owner-delete` | data-model 3 rule 6, 13 (M15), 14; plan EG-1, EG-4, EG-15, C0, C3b | applied |
| 6 | SIGN | SIGN | none | | |
| 7 | stands | SIGN WITH CHANGES (development only): a build with the in-development flag off must also refuse a datadir that has the marker | One line in `StateLayoutGuard`, removed with the flag in C8b; a test starts a marked datadir with the flag off and an unmarked one with it on | data-model 5.3; plan C5a | applied |
| 8 | SIGN | SIGN WITH CHANGES (one-way door): (a) bind the ancestor window by hash; (b) clear a genesis anchor at import start | (a) the `0x03` headers chain by `parentHash` to the checkpoint header and each `0x04` body matches its header, else `BadFormat`; `min(256, N)` when N < 256; a second golden stream does not chain. (b) `beginCheckpointImport` clears `t`/`u` and the genesis anchor first, idempotently | data-model E.1, 5.4; plan C5c | applied |
| 9, 10 | SIGN | no objection (9); SIGN (10) | none | | |
| 11 | stands | SIGN WITH CHANGES: (a) the stale text "`basic` and `inmemory`"; (b) open item 1 said the sign-off depends on the block source (it does not); recommendation: a core-geth export primary, fukuii's own sync acceptable | (a) fixed to `basic` and `archive` (checklist row 11). (b) open item 1 reworded: the source is logistics and **gates C7 and EG-1's ETC half, not code**; both candidates and forge's preference are recorded; the user left the choice open | plan checklist 11, open item 1, EG-1 | applied |
| 12 | SIGN | SIGN WITH CHANGES (minor): MockedMiner must wait for the import of the mined block (or retry), because `MiningFailed` drops the run to `waiting()` | The E table row says so, with a test of two blocks one second apart and a slow import | data-model E; plan C3c | applied |
| 14, 15 | SIGN | SIGN | none | | |
| GV 1 to 3 | SIGN, re-derived | verified | none | | |
| ESC | SIGN WITH CHANGES (vault's escalation re-SNAP): (a) the PoS trigger does not exist (`handleBeaconHead` only records the head and hints a running SNAP, and an FCU-side refusal produces no importer strikes), so add a direct message with hysteresis; (b) the anchor delete goes in the same synced batch as `clearSnapSyncDone()` or strictly after it, under the state lock after clearing the overlay, the gate memo and pending recovery; (c) clear `t` and `u` too; (d) EG-14 gains an induced escalation | SIGN WITH CHANGES (R2.3): a loop guard; a cool-down; note: tombstone leftovers keep "present means complete" only while a dead owner's rows are untouched (add one test); note: `ReorgTooDeep(Incomplete)` can escalate on a routine reorg across a tombstoned head (M15 keeps it rare) | (a) `StateEscalationRequested` from the FCU and `newPayload` refusal path to `SyncController`, sent after `escalation-refusals` (64, about 2 epochs) consecutive refusals; (b, c) one synced batch (`clearSnapSyncDone`, anchor delete, `state.escalation.last`, `state.reset-pending`), then `deleteRange` of CF `v`, `t`, `u`; (d) EG-14 asserts SYNCING and ACCEPTED, then VALID, one escalation. Forge: `escalation-cooldown` (6 h, persisted, counted) and the loop guard (a re-offered branch is dropped at ERROR, never a second escalation); the healing test is in C4d; the tombstone note is in 3.6 and risk 6 | data-model D, 5.1, 5.2, 10, 11, 14; plan C4d, EG-3, EG-14 | applied |

**Adaptations and one reconciliation, stated so the reviewers can object.**
- *Vault's addition, `state.reset-pending`.* Beacon's order (b then c) leaves a crash window between the synced batch and the
  `deleteRange` calls in which a restart would resume SNAP over the old rows and owe the walk spec 022 removed. A start that finds
  the key repeats the three idempotent `deleteRange` calls first; a plain mid-SNAP restart has no such key and keeps its rows.
- *Reconciliation (forge item 3b against 6.4).* Forge wrote that above the pinned bytes "the recorded reverse-then-commit fallback is
  taken". The design had `BranchTooLarge` (retryable, never invalid) as the default over the cap and reverse-then-commit as the
  recorded alternative, switched on only if M13 shows real branches above it. It stays that way; forge confirms at C4e.
- *Hysteresis.* "N consecutive FCUs, or about 2 epochs" is one key, `escalation-refusals = 64`, which is 2 epochs at one
  `forkchoiceUpdated` per 12 s slot.
- *EIP-8246 pass count.* Beacon asked for it on the ported harness; that harness is C3c, after C0, so C0 records the count on the pinned
  jar's harness and C3c records it again on the ported one.
- *Zero tombstones.* Asserted on ETH (EG-4, EG-15) as beacon asked; on ETC and Mordor the count is recorded and any non-zero value is
  explained before `max-entries-per-owner-delete` is frozen, because forge asked for a measurement, not an assertion.

**Text inconsistencies forge listed (R2.5), each fixed:** the stale "`basic` and `inmemory`" in checklist row 11; item 13 named in the
forge routing line and in data-model C.1; open item 1 saying item 11 depended on the block source. **The unlisted risks of round 1**
(forge's three: PoW branches over 32 blocks, recovery deeper than the reach, ommer and BLOCKHASH ancestors after hand-off; beacon's
R1 to R3) are reported covered by both reviewers, with the changes above (3b, loop guard and cool-down, 8a) as the remaining edges.

### C1 pre-code record (2026-11-11)

`forge` and `beacon` read the implementation design of C1 before code (the Consensus-Critical Change Protocol's step 2), both
read-only. Both returned the same counterexample, and both signed with the changes below; all are applied in the C1 PR.

| Finding | By | Change |
| --- | --- | --- |
| **Critical.** Reporting the node `fix` absorbs in the same list as the replaced ones is not neutral on the hash store: the data-model 9 default passes that list to `updateNodesInStorage`, and `ReferenceCountNodeStorage` decrements every hash in it. Example: keys `[1,2]` and `[3,4]`, remove `[1,2]`: today the store is told of the branch and the leaf at `[1]`; it would also have been told of the leaf at `[3]`, which the trie only re-keys. beacon withdrew its S0 sign-off of that default as written | forge, beacon | `updateNodesAt(newRoot, replaced, absorbed)`: two lists; the default passes `replaced` alone, in today's order. `TwinTrie` in the C1 tests holds every update of a hash-keyed store to the nodes on the path of the change, root first, so the neutrality is tested and not argued (data-model 8 item 2, 9) |
| A node is reported as absorbed when "the extension held its hash", not by a flag for "the remaining child is old": the flag also named an embedded sibling, which has no row | beacon | applied; an embedded sibling is a test case |
| A put of the value a key already holds reports the nodes on its path, each replaced by an identical node: rule 2 must drop them | forge | a test: such a put leaves an empty change set |
| `ConsensusAdapterSpec` drives a trie over `mock[MptStorage]` and expected `get` | forge | the expectation is `getAt` |
| The decorator base must not let `getAt` bypass a wrapper's `get`, nor `updateNodesAt` bypass a wrapper that drops removals (`AppendOnlyMptStorage`, `ReplayMptStorage`, `RetainingMptStorage`: genesis load, `eth_call` replay and `eth_simulateV1` would start deleting nodes) | forge, beacon | `get`/`getAt` final over one `read` hook, the two update members final over one `update` hook; T041 carries the per-wrapper tests |
| The collapse test compares ordered sequences, not multisets; child positions are computed only for a node that is walked | beacon | applied (`TriePosition`, built lazily) |
| The path threaded as an array, not the cons list of data-model 8 item 1 | forge, beacon: SIGN | recorded in data-model 8 item 1, with figures in `baselines/m7-c1.md` |
| `PositionKey` over an opaque array has identity equality (data-model 3) | forge, beacon: agreed | noted in data-model 3 for C3a |

Two task-list defects found while building C1, recorded in tasks.md: **T026** (fixtures through a recording sink) cannot run
before C2a's owner threading (T046), because until then every storage trie and the account trie reach one storage with no
owner, and their positions collide; **T031** said the implementors derive from the decorator in C1, and T041 says they move
in C2a: C1 ships the base, C2a moves them.

**Open after round 2.** Nothing blocks code on C1 to C3a. Two C0 deliverables stay open and neither is a design change: the
**EIP-8246 text** (read from an EIPs clone; the storage design does not depend on it) and the **source of the ETC 0 to 5.1M blocks**
(open item 1; it gates C7).

### C2a part 1 record (2026-11-11)

C2a lands as two PRs (tasks.md, Phase 3). Part 1 moves the six wrapping `MptStorage` implementors onto
`MptStorageDecorator` (T041) and threads the owner through execution, genesis and the BAL prefetch (T046). Both chains run
that code today, so `forge` and `beacon` read the design before code and the diff after it, read-only.

| Finding | By | When | Change |
| --- | --- | --- | --- |
| `PrefetchNodeReader.forOwner` must not give each trie its own budget: a block's prefetch is one budget and one count | beacon | before code | a private constructor passes the same two counters to every owner view; a test |
| `PrefetchNodeReader.resetOwner` must throw like its other writes, not forward | beacon | before code | applied |
| The mirror's read rule accepted "the current row hashes to it", which passes a read no staged layer would answer | beacon | before code | two rules only: a node this block wrote at that owner and position, or the row there when the block began |
| A hash the block wrote at another position must not satisfy a read | forge | before code | rule 1 is keyed by owner, position and hash, which is stricter than data-model 6.2's by-hash layer |
| **Blocker.** Four specs build a world over `mock[MptStorage]`, and a world now asks its storage for `forOwner(None)`: 12 tests fail | beacon | after code | confirmed by running them; the mocks stub `forOwner` |
| The Amsterdam corpus barely exercises resets (EIP-6780, EIP-7610): run pre-Cancun suites | beacon | after code | confirmed by mutation: with the reset removed, 26,503 of 26,503 Amsterdam fixtures still pass and 22 of 8,835 Paris and Shanghai fixtures fail. Seven of the 22 are vendored |
| A read of a storage trie after its reset is legitimate only for the access-list builder; before Amsterdam it is a bug the mirror passed | forge | after code | counted apart, and a finding on a fork that builds no access list |
| Keep the unobserved replay as the consensus gate; the mirror is an addition | beacon | after code | `eest-amsterdam.yml` runs the corpus a second time with the mirror |
| The mirror's `committed` is the last accepted block, not the block's real parent | beacon | after code | documented on `PositionMirror.ended`; a side branch would be reported, not passed |

Verdicts after code: forge signs D1 to D3 and D4 with the reset-read change above; beacon finds the main diff neutral on the
hash store for import, `newPayload`, payload building, `eth_call`, `eth_simulateV1`, genesis and the prefetch.

**What later slices inherit** (each is written into the task it binds):

- **C3b (T089).** `deleteAccount` resets at once and on the world's storage, so the reset belongs to the layer of the
  execution that made it and dies with a rejected block. It never drops `byHash`. A reset of an owner with no rows is the
  normal case and must be free.
- **C3c (T111) before C3b's reset acts.** The PoW miner, `eth_simulateV1` and the lenient builder issue resets on a storage
  nobody commits. Each attempt needs its own throwaway layer.
- **C2b (T062).** The three batch reads go on the ratchet (data-model 9, "A batch read is not an execution read").
- **The reset path is covered on real blocks only before Cancun.** `selfdestruct-stored-storage.json` is the oracle; the
  Amsterdam corpus cannot see a missing reset.

T026's figures and mutation runs: `baselines/t026-position-mirror.md`. Its ethereum/tests half is open (B-207).

### C2a part 2 record (2026-11-11)

Part 2 is the read side of the position store, in a new package `db/storage/pathstate`, behind `fukuii.state.path-live`
(default false). `beacon` and `vault` read the design before code.

| Finding | By | Change |
| --- | --- | --- |
| A storage whose writes throw cannot serve the readers C2b converts (`eth_call`, estimate, trace, simulate): each persists after every transaction and reads the new root back, and the access-list builder reads the older world. data-model 6.2 names them as throwaway layers, and the tasks put C2b before C3b | beacon | the by-hash layer lands here: `PathMptStorage.throwaway(view)`. C3b adds `changes` and `resets` to it |
| Each move must be logged under the odd epoch it sets, or a reader that samples that epoch misses the move in flight | beacon | applied, and pinned by a test |
| Add the interleaving of 6.3.3 now: the batch lands between the read of the row and the read of the epoch | beacon | `StateViewSpec`, three orders, driven by hand on one thread |
| The stopped state of the prefetch reader must be shared by every trie's view, like its two counters: the prefetcher asks only the reader it holds | beacon | one flag, passed to each view |
| `location` of a missing node stays the hex-prefix of the path alone, as today | beacon | applied |
| `getOptimized` is the right read for execution (it fills the block cache; a closed database throws, and that must not be read as absent). The reader's key must be the bytes the SNAP writer wrote | vault | `PathNodeStorage.read(PositionKey)` uses the key as it is; a round-trip test; a closed-RocksDB test |
| `EphemDataSource.get` is not synchronised with its `update`: a test that races a reader against a writer on it is unsound | vault | no such test: the clock is driven deterministically |
| The conf include belongs in `conf/base/fukuii.conf`, and the accessor should not load the whole node configuration | vault | `state.conf`, `utils/StateLayoutConfig` |
| `pathStateStorage` must be declared after `appStateStorage`; one `PathNodeStorage` per use is fine (it holds no state) | vault | applied |

Two things in the code are not in the design note. `PathPrefetchReader` is not a second class: it is `PrefetchNodeReader`
with a stop on the first fault, over a read-only view, so the hash store's reader and this one share one body. And
`multiGetNodes` and `rawNodes` throw on a position store, where the trait's defaults would have answered "absent" for nearly
every node (data-model 9).

**The specs were shown to fail.** They passed on their first run but for one case, which was the test's own mistake (it
took as "a wrong node" one the walk had read, rightly, at its own position). So nine rules were broken on purpose, one at a
time, and the specs run again:

| Rule broken | Tests that fail |
| --- | --- |
| a mismatching row is always called corrupt | stale once the disk has moved or is moving; decide after the read |
| a view ignores the log of moves | 14, in `StateViewSpec`, `PathMptStorageSpec` and `PathPrefetchReaderSpec` |
| a move is logged under the even epoch after it | 15, among them the one that pins the odd epoch |
| the layer is not asked on a read | an execution that commits nothing persists its worlds and reads them back |
| the prefetch reader does not stop on a fault | the three stop cases of `PathPrefetchReaderSpec` |
| a row is not checked against the hash its parent holds | corrupt, stale, decide after the read, the throwaway execution, never cache a wrong row |
| corrupt or stale is decided before the row is read | exactly the one test written for it |
| the latest move answers, not the earliest | the earliest move wins; stale once the log no longer reaches back |
| a throwaway update writes nothing | did not compile (the unused-binding ratchet); the fourth row covers the same layer |

After code, `vault` found no blocker and asked for four things, all applied: a writer cannot forget to settle
(`StateMoveLog.inFlight(move) { batch }` settles in a `finally`, as not committed when the batch throws); a test that a
batch which failed still ends "the view of what is on disk" for earlier views, so that they report stale and never corrupt;
the test kit's RocksDB directory is deleted whatever throws; and three rules for C3b's commit, written into T091.

`beacon` signed after code with two changes, both applied. The first is the same pairing rule, taken one step further:
`publish` and `settled` are private to the package, so a writer outside it has only `inFlight`. The second: an anchor written
past the clock would leave views of a block the disk no longer holds looking current, and each row they met would be called
corrupt, the one verdict that can end in a delete. So `AppStateStorage.putStateAnchor` is `private[storage]`, and a source
ratchet (`AppStateAnchorRatchetSpec`) allows the path state storage as its only caller. beacon also named three cases with no
test, now written: a view read through a batch that failed, `get(root)` on a view the disk has moved past, and a root under
32 bytes found by hash in a throwaway layer. It confirmed the `PrefetchNodeReader` change neutral for today's hash-store
prefetch (import and `newPayload`), and noted that a caller must ask `stateAvailable`, never `get(root)` (data-model 9, T056).

**What later slices inherit.** T057 runs on `throwaway(block)`. T070 builds on the existing `PositionKey`. T091 writes
its batch inside `inFlight`, under the state lock. T093 switches the prefetch's call site. T130 puts the forward chain and the
reverse index behind the same `StateView.read`. T131's in-place commit evicts after it writes.

### C3a record (2026-11-11)

C3a is the changeset record (data-model 4), its storage in CF `v`, and the `fukuii.state` keys. Nothing reads or writes
any of it until C3b. **The record's bytes are a one-way door**, so `forge` and `beacon` each re-derived the CRC32C of the three
frozen vectors with an implementation of their own, and **both sign the vectors and the format**.

What the frozen text left open, and how it was decided:

| Decision | Signed by |
| --- | --- |
| The reader refuses four things more than data-model 4.2 lists (a length in more bytes than it needs, a presence byte other than 0 or 1, a tag other than 1 or 2, a key that is no row key), so that a record it accepts encodes back to its own bytes. No future writer needs any of them: new data is a new kind, and a new layout is a new version and a re-sync (4.4) | forge, beacon |
| The version is checked first and alone, then the kind and flags, then the CRC, then the fields | forge, beacon |
| `BlockDiff.byHash` is derived from the `next` nodes. The every-version map of an execution (`PathMptStorage.Layer`) is another structure and stays one | beacon |
| The retention arithmetic: 64 with no finalized block, 106 at a 90-block finality lag, 512 when finality has stopped, 64 while the anchor is behind the finalized block | beacon |
| No range scan in `ChangesetStorage`. An ETC mainnet block hash begins with about six zero bytes, so its record key sorts before every height key from 2^24 up; on Mordor, about four, they interleave. Pruning reads heights by their own keys | forge |
| CF `v` joins `nsSeq`: a datadir this build has started cannot be opened by an older one | forge, with a release note |

Changes asked for, and applied:

| Finding | By | Change |
| --- | --- | --- |
| The index of a height is read, extended and written back. Outside the state lock two sibling commits lose one hash, and that record is never pruned | beacon | `recordUpdates` and `pruneUpdates` ask for a `StateLockHeld`, which only the lock of C3b can make; `put` and `prune` are private to the package |
| `validate` can catch a diff built from the wrong world of a block (a persist inside a pre-Byzantium block, a world the miner threw away) | forge | the root rule: the account trie's root entry has a prior hashing to the pre-state root and a next hashing to the post-state root, and is there exactly when the two differ |
| `retention.max-blocks` below `pruning.history` should not load | forge | refused when the configuration is read |
| `recovery.escalation-refusals` is written in forkchoiceUpdated terms | forge | the conf says it does nothing on a proof-of-work node |

Five rules were broken on purpose after the specs first passed (the writer sorts keys as signed bytes; no CRC check; a
non-minimal length accepted; pruning takes the floor's height too; a second block at a height overwrites the index). Each
was caught.

**Carried to C3b, in its tasks:** a **persisted** prune cursor (T095: without one a restart or a switch from archive to
basic would point-read every height from 0, about 22 million on ETC mainnet); a malformed height index fails the commit as
storage corruption, never as a fault of the block (T091); `pruning.mode = "inmemory"` starts failing at start when C3b
builds `StateConfig`, which the release notes must say (T093).

**Decided by the user on 2026-11-11** (see "Decisions of 2026-11-11" below): 512 blocks where there is no finalized block,
and one downgrade barrier. The two questions as they were put:

1. **ETC keeps 64 blocks of changesets** by this design, because a proof-of-work chain has no finalized block and the floor
   is then `pruning.history`. A reorganisation deeper than that is refused and the node re-syncs its state. ETC was
   reorganised 3,000 to 7,000 blocks deep in 2020, and MESS returns only with Olympia; Besu keeps 512 trie logs by default.
   forge asks for a proof-of-work default of at least 512 (`retention.max-blocks`). The cost is disk: one record per block
   kept.
2. **The downgrade barrier.** After this slice a build older than it cannot open a datadir (RocksDB must open every column
   family a database has). forge asks that every column family C3b needs land in the same release, so operators meet one
   barrier and not two, and that the release note say a rollback means a re-sync.

### Decisions of 2026-11-11 (user)

Four questions were put to the user after C3a was built, and answered in one message.

| Question | Decision | Where it lands |
| --- | --- | --- |
| Merge C3a, which adds column family `v` to every datadir, after which an older build cannot open it? | **Merge.** | #1793 |
| How many blocks of changesets where there is no finalized block (ETC, Mordor)? 64 by the design, at least 512 by forge | **512.** | `state.retention.without-finality = 512`; `StateConfig.retained`; data-model 4.5 and 10 |
| One downgrade barrier or two? | **One: everything lands in the same release.** | below |
| With the guard on a node can serve SNAP for the anchor's root only, until the overlay (C4a). Accept empty answers, or stop advertising? | **Stop advertising `snap1`, so that peers are not lost.** | T058 |

**One downgrade barrier.** The release that first ships column family `v` also ships every other change of this track after
which an older build cannot open a datadir. Today the track adds no column family but `v` (data-model 2: `t` and `u` exist,
`n` stays as an empty family), so the rule binds two things: a later slice that finds it needs another family lands it before
that release is cut; and the release note says, once, that a datadir started by this release cannot be opened by an older
one. The `state.layout` marker (5.2) is a different barrier with its own message, and it stays behind the in-development
guard until the track's last slice.

**`snap1` under the guard.** With `fukuii.state.path-live` on and until the overlay can serve the window of recent roots,
the node does not advertise `snap1`. A node in that state does not need SNAP as a client either: its hand-off (C5a) comes
after the overlay (C4a) in merge order. With the guard off nothing changes. It is part of C2b's serving PR.

### C2b pre-code record (2026-11-11)

C2b (every reader can be handed the position store) touches 44 call sites of the four state accessors in 27 files. `conduit`
(RPC), `herald` (SNAP serving) and `beacon` (the seam, the walkers) read the design before code. Each signs with changes; none
blocks. **It lands as three PRs:** the ratchet; the RPC readers; serving and walking.

| Finding | By | Change to the plan |
| --- | --- | --- |
| Only RPC readers move onto the seam in this slice. With the guard on there is no writer before C3b, so the anchor stands still while import moves the head: the proposer's nonce read (`EngineApiService:1191`), the importer's missing-node lookup (`BlockImporter:835-838`) and the chain importer's presence check (`ChainImporter:362`) would break | beacon | they stay on a root-keyed hash accessor, allow-listed, until C3b and C5b |
| With the guard off the seam hands every reader `getReadOnlyStorage`: reads are the same as the backing storage in each pruning mode, and a reader that persists by accident then writes into a buffer | beacon | a differential test on basic and in-memory that a converted reader's answers are unchanged |
| Account reads by block number resolve the number through the canonical index, so a request for a side block by hash reads the canonical block's state **today** (`BlockchainReader.scala:190-194,330-337`). Ten call sites hold the header and pass the number | conduit, beacon | the readers pass the header; an existing bug fixed on the way |
| `StxLedger.advanceWorldToTx` needs the parent's state and the header in hand is the traced block's | conduit | it takes the parent header |
| `eth_simulateV1` makes one storage and threads the world through every simulated block: block N+1 reads the root N persisted, which is no header's | conduit | one scratch state per request, shared |
| Nothing in the RPC layer catches a `StateFault`: it would leave as `-32603`, or a 500 from GraphQL | conduit | a retry helper that re-runs the whole read up to 3 times, and a backstop that maps a `StateFault` to the existing `NodeNotFound` error |
| Every caller of the root-only storage reads has the address | conduit | the root-only overloads are deleted |
| Only `walkRangeVisit` carries a prefix in the SNAP server; `resolve`, `proofFor`, `collectNodeAtPath` and `resolveLeafAccount` do not, and two raw reads sit in `NetworkPeerManagerActor` (:1159, :1171) | herald | the prefix is threaded through all of them |
| Only GetAccountRange checks the served-root window today; storage ranges and trie nodes serve any root the hash store holds | herald | with the guard on they are limited to the window too; with it off nothing changes |
| `GetNodeData` is unreachable: every decoder from eth/68 to eth/72 rejects its codes. (The note said "eth/68 and eth/69 only"; eth/70 to 72 are opt-in per network) | herald | the handler is deleted or allow-listed, not converted |
| Three walkers see only a hash (`LeafWalkVisitor`, `MptConstructionVisitor`, `ShardEnumerator`), and every read of a storage root by hash fails on a position store (`StorageRecoveryActor:452,497`, `StateValidator:79,287`) | beacon | positioned visitors for the three; the storage-root reads take the account hash they already hold |
| The four accessors are not all the doors in | beacon | the ratchet also covers `getBulkLoadStorage`, `stageBlock`, `prefetchReader`, `getNode`/`getMptNodeByHash`, `storageMpt(`, trie constructions, `def visitHash`, and stores built outside `db/storage` |

### C2b ratchet record (2026-11-11)

C2b's first PR freezes who may still enter the state by a root or a hash (T054, T062). No code of the node changes.

`scripts/check-state-accessors.sh` counts every use of eighteen entry points under `src/main/scala` and compares the counts
with `scripts/state-accessor-allowlist.txt`, where each line has its reason. More uses than a line allows fail, and so do
fewer: the list only shrinks. It runs in the Test and Build job before the compile, and again from
`StateAccessorRatchetSpec`.

**What the tree holds: 142 lines, 279 uses, 69 files.** By what happens to them:

| Group | Lines | Leaves in |
| --- | --- | --- |
| The stores and the trie themselves (definitions) | 41 | C8b, with the Hash scheme; some are kept |
| Tries and decorators that name their owner | 11 | kept (C2a did these) |
| Not the state: list tries over an ephemeral source | 11 | kept |
| Readers behind RPC, and the transaction pool | 12 | C2b, second PR |
| SNAP serving, checkpoint export, recovery scans, walkers | 22 | C2b, third PR |
| Execution, block production and the writers beside them | 10 | C3b, C3c |
| SNAP hand-off, the audit, repair, checkpoint import | 11 | C5a, C5b, C5c |
| The copy to the hash store, and SNAP's Hash arm | 24 | C8a |

18 lines are marked `WRITER`.

**What the list showed that the design had not:**

- The four accessors named in T054 are 57 of the 279 uses, their definitions included. Most hash-only readers never touch
  an accessor: they are handed a store and call `get(hash)` on it. SNAP serving, `StateValidator` (9 reads), the recovery
  scans and `ShardEnumerator` appear only under `get-by-hash`, which is why it was added.
- `PendingTransactionsManager` (`:498`) reads senders' accounts at the best block from a root alone. No task named it. It
  is not on the consensus path and falls back to the senders it knows on any failure (`:530-533`), so it moves with the
  RPC readers (beacon agrees).
- Scala 3 builds a class without `new`: a pattern on `new SerializingMptStorage(` misses half of the constructions.
- `MerklePatriciaTrie.getProof` has an arm that resolves a hash node with no position (`:196`). It is **unreachable**:
  `pathTraverse` resolves every hash node before the arm sees one (beacon, who had said so in the C1 review; a first
  reading here took it for a fault in `eth_getProof` on a position store, which it is not). T051 deletes the arm.

**beacon's review of the list (SIGN WITH CHANGES; all taken):**

| Finding | Change |
| --- | --- |
| No pattern covered the writes by hash: `storeRawNodes` (13 uses in 9 files), `repairNode`, `deleteBadNode`, `saveNode`. They are the riskiest doors | entry point `raw-store` |
| An accessor reached under another name escaped: `StateIngest.trieStore` returns `getBackingStorage(pivot)`, and `SnapResumePlanner.getOrCreateMptStorage` hands that on to nine call sites | entry point `snap-store`; and `store-handed`, every method whose result is a store, so that the next such name has to be listed when it is written |
| Three reasons were wrong about the code: the `getProof` arm; `NodeBuilder:496` serves SNAP only, not GetNodeData; the two raw reads of `NetworkPeerManagerActor` belong to the storage-range answer | corrected |
| A `get` on a map called `storage` trips `get-by-hash`, and that kind of noise is what gets a check switched off | `get-by-hash` looks only in files that name a store or a trie (three rows left the list); a failure prints the exact line to add or edit |
| Exact counts in both directions | kept: it is what makes the list only shrink, and moving lines inside a file is free |

**Its limits.** It is text, not types. `get-by-hash` knows a store by its name (`mptStorage`, `storage`, `source`,
`hashStore`, `mpt`), so a store under another name gets past it. The proof that nothing reads by hash alone is C8b, which
deletes the read; until then this trips on the shapes the code uses today.

**Evidence.** The spec passed when first run, apart from one wording mismatch, and four of its assertions were then
found to pass on the script's closing paragraph and not on its verdict; they now name the verdict. The script was then
broken one rule at a time, 27 ways, and every one fails at least one case (46 cases in all):

| Broken | Cases that fail |
| --- | --- |
| more than listed passes; fewer passes; a line whose file is gone passes | 1 each |
| comment lines are counted; lines are counted and not uses | 2 each |
| a line with no reason, a line given twice, an unknown entry point, a count that is no number: each accepted | 1 each |
| `src/test` is scanned too | 2 |
| an empty list is read as the tree (the `FNR == NR` trap in awk, which the first run of the script hit) | 33 |
| a tree with no sources passes; a missing allow-list passes | 1 each |
| a difference exits 0 | 39 |
| the uses of an unlisted file are not shown; the line to add is not printed | 34; 1 |
| `get-by-hash` looks in every file | 2 |
| a pattern loosened or narrowed, ten ways: no word boundary; `visitHash(` without `def`; any receiver for `get(`; a type argument counted as a trie; `new` required for a store; `raw-store` without the delete, or without the batch write; `snap-store` without its second name; `store-handed` taking a function type, or missing a declaration | 1 to 3 each |

On the first set of mutations two of the loosened patterns failed only the run on the repository; each now has a made-up
case of its own.

### C2b RPC readers record (2026-11-11)

C2b's second PR moves the readers behind RPC, and the transaction pool, onto a seam that hands out the state of a
**block** (T051, T053, T056, T057). With the guard off they read what they read before, with one exception that is a fix.

**The seam** is on `BlockchainReader`: `stateAt(header)` for a reader that writes nothing, `scratchStateAt(header)` for an
execution that commits nothing. Guard off, both are the read-only store. Guard on, a view of the position store: the
anchor's state, and `StateUnavailable` for any other block until the overlay (C4a).

| What moved | To |
| --- | --- |
| Account reads by block number (`getAccount(branch, address, n)`): ten call sites | `accountAt(header, address)`; `accountIn` / `accountProofIn(state, header, address)`, which keep "a block above the best block has no account" |
| `eth_getStorageAt`, `eth_getProof`, `eth_getStorageValues`, GraphQL `storage`, the test RPC: a storage trie from a root alone, over block 0's store | `(state, address, storageRoot, position)`, under the account, from the state the account was read from; the root-only forms are deleted |
| `eth_call`, `eth_estimateGas`, `eth_createAccessList`, the trace and debug replays, `eth_simulateV1` | `scratchStateAt`; a replay takes the **parent's** header; a simulation takes one scratch state for the whole request |
| The pool's read of senders at the best block | `stateAt(best header)` |

**The one RPC-visible change.** A read by block number goes through the canonical index, so a request **by hash** for a
side block was answered from the canonical block at that height. It is read by the header now (EIP-1898).
`EthUserServiceSpec` has the case; it fails on the old read (123, the canonical balance) and passes on the new (456).

**A stale view is retried, an unavailable state is the existing error.** `StateRead.at` / `scratchAt` run a read again,
all of it, on a fresh state, three times at most. A request that holds a world across many reads (`eth_call`, a
simulation, a trace) is run again as a whole: 18 routes go through `handleRead`. A state that is not held is
`NodeNotFound` (code 3), the error a client already gets for a missing state node, and the controller answers a
`StateFault` that escapes a method the same way. A `MissingNodeException` keeps each method's own answer.

**What an end-to-end run found that the design had not.** `RpcReadersOnPositionsSpec` imports the execution-apis test
chain (the chain hive's rpc-compat serves its vectors from), copies its head's state into the position store of the same
datadir, and asks a node on each store the same questions through the JSON-RPC controller: balance, nonce, code, storage,
proofs, `eth_call` and `eth_estimateGas`, for all 34 genesis accounts. **Every plain `eth_call` failed on the position
store.** A message call does not run under its block's header: `CallTxBuilder` zeroes the base fee for a call that names
no gas price (`runHeader`), and block overrides replace what they name. The ledger built the call's world from the header
it was given. That was right while a state was found by its root; a view is asked for by a block's hash, and the header a
call runs under has another, which names no block. Conduit's review had named this trap for traces (the parent's header);
it was there for calls too, and no unit test of either store alone could have shown it. `StxLedger.callWorld(stateOf,
executing)` separates the two, and `simulateCall` / `estimateCallGas` take the world instead of making one.

**Reviews (both SIGN WITH CHANGES; all taken).**

| Finding | By | Change |
| --- | --- | --- |
| GraphQL caught a `StateFault` with the missing-node case and answered no account or the start nonce: a state that is not held would read as a zero balance | conduit | not caught: it surfaces as an error of the field, as it does for `code` |
| On the send paths a `StateFault` reached the backstop (code 3) where a missing node is `-32000` | conduit | admission answers both the same |
| A read given up as unavailable left no trace: a view that stays stale looks like a node that is not there | conduit | a debug log with the attempts |
| GraphQL's `call` and `estimateGas` reach the services without the controller, so without the retry | conduit, beacon | B-211; until then a stale view there is an error of the field |
| With the arm gone, `case _ => acc` would drop a hash node that ever reached a proof, and serve the proof short | beacon | that case throws |
| A retry is only worth something if each run takes its own state | beacon | `RpcRetryFreshStateSpec` counts the scratch states taken across retries of three real methods |
| `PathMptStorage.decoded` has no check for a stored value shaped like a bare hash, which the hash store has | beacon | for C3b (B-209) |
| A block imported by `newPayload` and not yet the head is above best, and answers "no account" though its state is held (old behaviour, kept) | beacon | B-210 |

**Not covered here, and said so.** GraphQL has no test with the guard on. The two worlds `StxLedger` still makes from
the header it is given (`runSimulated`, the estimate search) are reached only with a world handed in; a comment says why
they must not be reached otherwise. hive's rpc-compat and graphql runs are with the guard off: a regression fence, not a
test of the position store.

**The ratchet** went from 142 lines and 279 uses to 136 and 268.

**Evidence.** The new specs passed on their first or second run, so the production code was then broken one rule at a
time, 18 ways. Fifteen of the first runs failed a case. **Three did not, and each was a gap in the tests, which now has a
case:** the trace replay before Amsterdam asking for the traced block's state (only the Amsterdam arm had a block), the
pool's account trie naming no owner (a transaction sent over RPC is judged by the transaction service; the pool's own
read is for a peer's), and `eth_simulateV1` taking a reader's state. Three more did not compile as first written (the
unused-symbol ratchet refuses a parameter or an import left unused) and were run again in a form that does.

| Broken | Cases that fail |
| --- | --- |
| a reader's state takes writes; an execution's state refuses them (guard on) | 1; 2 |
| `accountIn`, `accountProofIn` forget the block above the best block | 2 each |
| the account trie names no owner | 5 |
| a storage trie is read under the wrong owner | 4 |
| a stale read is not run again; a stale request is not run again | 2; 9 |
| a state that is not held is retried | 1 |
| the controller's backstop answers an internal error | 1 |
| `handleRead` does not run a request again | 7 |
| a call's state is asked for by the header it runs under | 1 |
| a replay asks for the traced block's state: before Amsterdam; from Amsterdam | 1; 1 |
| admission lets a state fault escape | 1 |
| the pool's account trie names no owner | 1 |
| `eth_getStorageAt` reads the account by block number again | 1 |
| `eth_simulateV1` takes a reader's state | 1 |

### C2b SNAP serving record (2026-11-11)

C2b's third PR is SNAP serving (T052, T058). The walkers (T055, T059 to T061) are a fourth PR: serving is wire
behaviour and herald's review, walking is recovery and beacon's.

**Every read says where it is.** A SNAP server reaches each node by a descent from a root, so it always knows the trie
and the path: the account trie under no owner, a storage trie under its account's hash, the path counted through the
range walk and the three descents (`proofFor`, `resolveLeafAccount`, `collectNodeAtPath`). A store keyed by hash ignores
both. The handler of `GetStorageRanges` had its own copy of the account lookup, by hash; it is `SnapServer.storageRootOf`
now, the server's own descent.

**The state is asked for by the request's root, for each request**: `BlockchainReader.stateForRoot(root)`. Guard off it
is the read-only store whatever the root, and the serve finds the root's node there or answers with nothing, as it
always has. Guard on it is the anchor's view when the root is the anchor's and the anchor is within 128 blocks of the
best block. No other state is held until the overlay, so no other root is served; C4a widens it to every block the
overlay can give a view of, with the window `min(128, the retention floor)` (noted on T130).

**A defect the conversion would have carried in.** The server took every read that failed as "no node here". For a node
that is missing that is how it has always answered. A view of the position store can fail another way: the disk moves
under it, and the read says the view is stale. Taken as an absent node that is a hole in the middle of a range (the
accounts under that node left out) sent with proofs that cannot close over it, to a peer that finds the response invalid
and drops this node. The handler's lookup did the same one level up: a stale read became "no such account", which is
sent as an account with no storage. Each node that was read is still a node of the root asked about, since each is
checked against the hash its parent holds, but a part of a range is not a range. **A serve that meets a `StateFault`
answers with the empty response**, wherever in the serve the move falls. `SnapServePositionStoreSpec` moves the disk
after every read count of a serve, from none to all of them, and holds the answer to the empty response each time.

**`snap/1` under the guard** (user decision, this file, "Decisions of 2026-11-11"). `InstanceConfig` leaves `snap/1`
and `snap/2` out of the capabilities while `state.path-live` is on, and says so at start. The capability is mutual: a
node that does not offer it is not offered it, so such a node does not sync by SNAP either. Nothing can import a block
with the guard on before C3b, so nothing is lost by it today; C4a advertises it again.

**`GetNodeData`.** Every decoder this node has, eth/68 to eth/72, rejects codes `0x0d` and `0x0e` (EIP-4938). The
handler in `BlockchainHostActor` could not be reached, and it read a node by hash alone, which this layout cannot do. It
is deleted with its subscription and its two tests; one test pins that the host does not subscribe to the code.

**The spec passed when first run, so the code was broken one rule at a time.** 38 mutations, all caught:

| Rule broken | Mutations | Caught by |
| --- | --- | --- |
| a state fault is an absent node, or escapes one of the three serves | 4 | the stale-view cases |
| a node, or a root, is looked for at the wrong position | 2 | both states |
| the account trie under an owner; a storage trie under none, or under another account | 6 | both states (`alice` and `bob` hold the same trie) |
| an extension counted as one nibble, in each of the three descents | 3 | **the chosen-key state alone** |
| a descent, or the range walk, looks at the root, one nibble short or one long | 6 | both states |
| a handler asks for another root's state, looks storage roots up in it, takes a fault in the lookup as no account, or finds its state outside its `try` | 6 | `SnapServingActorSpec` |
| the window ignored or one block short; any root served; nothing served with the guard off | 4 | `BlockchainReaderSeamSpec` |
| snap advertised when it is not served; the guard does not withdraw it | 3 | `NetworkProtocolConfigSpec` |
| the host subscribes to `GetNodeData` again | 1 | `BlockchainHostActorSpec` |
| SNAP sync may start under the guard, for the node or for an instance; the node's storages read the process-wide guard | 3 | `ConfigValidatorSpec`, `StorageBuilderStateGuardSpec` |

The three extension mutations are why the second state exists. The state the production world builds has keys that are
hashes, so its tries are bushy: counting an extension as one nibble passes every case on it, and every case of the two
older specs of the server. The second state's keys are chosen: extensions of 4 and 62 nibbles in the account trie and in
a storage trie held by two accounts, and requests from keys that part from the trie inside, before and after each.

**Review (herald: SIGN WITH CHANGES, no blocker; all taken).** With the guard off it found no response that differs,
and no path on which a state fault is still turned into a part of a response.

| Finding | Change |
| --- | --- |
| The guard was read in two places: the storages from the process-wide configuration, the capabilities from the instance's. In a multi-instance run a node could hold the position store and offer SNAP, or the reverse | `StorageBuilder` takes the guard from its instance's configuration (`InstanceConfig.statePathLive`); `StorageBuilderStateGuardSpec` builds the production storages of an instance whose guard differs from the process's |
| With the guard on and `do-snap-sync = true` the node waits for a snap-capable peer for ever: nobody offers SNAP to a node that does not offer it, and SNAP sync has no way back to importing blocks | refused at start, for the node and for an instance (`ConfigValidator.stateGuardErrors`): it names both keys and says to set `do-snap-sync = false`. It goes with the capability rule in C4a |
| The handler's warning for a failed load of the root went with its copy of the lookup | `orAbsent` says at debug when a node is there and cannot be read; a missing node stays silent |
| `GetStorageRanges` found its state outside its `try`, where the other two find it inside | the same shape for all three; a spec has the state fail to be handed out, and the actor answers and stays up |
| The window compares block numbers, `isStateRootFresh` (`GetAccountRange` only) looks the root up among canonical headers: for a block that is not canonical the two would disagree | harmless while the one root served is the anchor's; for C4a, with the window (T130) |
| `hive.conf` sets `snap2 = true`: with the guard on the hive devp2p snap tests see no snap | by design until C4a; the guard and those runs do not go together |
| The requesting side of `GetNodeData` is dead too (`StateNodeFetcher`'s fallback, `PeersClient`'s node-data peers, the message classes) | B-214 |

**Not covered here, and said so.** The half of T052 against the pinned jar waits for C0 (B-212): this compares two
stores through one server, and would not show a change of the server itself that both stores share; `SnapServerSpec` and
`SnapServerLimitsSpec`, unchanged, are what holds that. A node that cannot be read for any other reason (missing,
corrupt) is still served as a hole where go-ethereum answers with nothing (B-213, old behaviour).

**The ratchet** went from 136 lines and 268 uses to 133 and 263.

### C2b walkers record (2026-11-11)

C2b's fourth and last PR: every walk of the state reads each node at its position (T055, T059, T060, T061). The walks
are the combined recovery scan and the two legacy scans, the state validator, the shard enumerator, the leaf walk they
share, and the checkpoint export.

**Frozen first.** The risk in this PR is the hash store: these walks decide what a node heals and when it stops
looking, on ETC and ETH, today. So before a walker was touched, what ten walks answer on a hash store was written down:
three states (hashed keys; chosen keys with extensions 4 and 62 nibbles long and nodes inside their parents; one
account, the one shape in which the heal walk reaches a storage trie), seven sets of holes, two code stores. 420 answers,
in order, in `src/test/resources/spec018/walker-verdicts-hash-store.txt`, and the checkpoint archive's bytes for the
chosen-key state. Every conversion after that was run against them.

**What a position store changes for a walk, beyond where a read is.** Two accounts with one storage root share one trie
on a hash store. On a position store each has rows of its own, and the second's can be missing while the first's are
whole (spec 022 V3). Three walks skipped a storage trie for having seen its root. On a position store that skip hides
exactly that hole, and nothing else would find it until a block read it. `MptStorage.nodesSharedAcrossTries` is what a
walk asks first: true of a hash store (by root, as before: the result and the number of storage tasks do not change),
false of the position store (by account and root). The validator's skip of a node it has seen is by hash only where a
node is its hash; a walk of a tree comes to each position once.

**A state fault is not a hole.** The defect the SNAP server had, in its other places: the validator took any failed
read as a missing node, or flagged the storage trie for healing. A view the disk has moved away from would have had a
node that is there fetched again. It ends the walk: the validator answers `Left("State fault, not a hole")`, a scan
ends, the export answers `StateNotHeld`.

**A defect found on the way (beacon), fixed.** A legacy scan that could not run at all (the state root not in the
store, the store failing) was mapped to a scan that found nothing missing, and the recovery flag was written for good on
a state nobody had read. A test pinned it, as "graceful resilience". It now ends recovery for the run, so that regular
sync starts, and writes nothing: what the combined scan, the default, already does (`recoveryCouldNotRun`).

**Where a walk's store comes from did not change.** The scans and the validator are handed SNAP's own store, during or
right after the sync, before there is an anchor to take a view of. Only the export has a block: it takes
`BlockchainReader.stateAt(header)`. The rest is the hand-off's (C5a), and the allow-list rows say so.

**Each spec passed when first run, so the code was broken one rule at a time.** 46 mutations, all caught:

| Rule broken | Mutations | Caught by |
| --- | --- | --- |
| the leaf walk reads at the root, or one nibble short | 2 | `WalkersPositionSpec`, the scanners spec |
| the shards read under an owner, at the root, or a shard under an extension or a branch is read above it | 4 | both |
| a scan walks the account trie under an owner, or probes a storage root under none (combined, legacy storage, legacy bytecode) | 5 | the scanners spec, on the position store |
| a scan tells storage tries by root on a position store (combined, legacy, the parallel merge), or by account on a hash store | 4 | the alias case, the merge case; the frozen answers |
| a scan that could not run marks recovery done (storage, bytecode) | 2 | the two actor specs |
| the validator: an owner, a path, an extension, a branch index or a leaf's key wrong in each of its walks | 10 | the scanners spec, on the position store |
| the validator: a state fault is a missing node, flags a trie for healing, or is a failed load in other words | 3 | the moving-state case |
| the validator skips a node it has seen on a position store | 1 | the case of one subtree at two positions |
| the export: an owner, a path, an extension, a branch index, a pushed path or a leaf's key wrong | 6 | the frozen bytes, from the position store |
| the export: a state fault is a missing node; a state not held is another error | 2 | `CheckpointExporterSpec` |
| the position store says its nodes are shared; a wrapper answers for itself | 2 | the alias case; `MptStorageDecoratorSpec` |
| after review: an account under a short key is a hole, or a leaf that does not decode, or is handed to the store by the export; a scan that could not run tells the controller it completed (storage, bytecode) | 5 | the short-key cases; the two actor specs |

**Reviews (beacon and forge: SIGN WITH CHANGES, no blocker).** Both read the hash-store behaviour as unchanged: the
same nodes, read in the same order.

| Finding | By | Change |
| --- | --- | --- |
| On a position store an account under a key that is not 32 bytes names no storage trie; the store throws, and the validator's catch-all would have flagged the storage root for healing: a corrupt account trie read as a hole | beacon | the validator answers `Left("Corrupt trie node, not a hole")`, the export `CorruptAccountKey`; on a hash store, where the name is not used, both walk on as before |
| The controller was told `RecoveryComplete` whether or not the scan ran, and logged "complete". It also counts failures of a bulk bytecode recovery towards a cap, which a scan that never ran must not count against | forge | `RecoveryNotRun` from both legacy actors; the controller says so at ERROR and sets `recoveryScanCouldNotRun`, as the combined scan's path does |
| The closing check of storage recovery (a log) caught trie exceptions only: a state fault there would have crashed the actor | beacon | it is logged and the check skipped |
| The path was copied twice for every node read, on a walk of 90 M accounts | beacon | once (`NibblePath.wrap`, in the two walkers inside `mpt`) |
| A v1 archive holds a storage trie that two accounts share once. An importer that places nodes by owner and path, with no lookup by hash, cannot rebuild the second account's trie from it | beacon | written into T230: v2 emits each owner's trie, or importing v1 keeps a lookup by hash |
| How many accounts share a storage root on ETC mainnet, that is how many more storage tasks a position-store scan starts, is not measured; nothing in the storage download assumes one task per root | forge | the legacy scan already logs both counts (contracts, distinct roots); to be read off a Mordor and a mainnet run before C5a |

**Not taken, with the reason.** forge: a pivot root that has been pruned makes a failed scan repeat at every start,
so settle the flag once the best block is past the pivot by the pruning history. The scan does not read the pivot's
root when it can read the best block's (`SyncController.recoveryScanRoot`): the pivot's is the fallback for a datadir
whose best block has no readable state, and there the combined scan, the default, already repeats at every start, at
the cost of one failed read. Whether either path should give up is one question for both, with B-217.

**Left as it is, and why.** A legacy scan that meets a hole stops there, returns what it had, and the flag is settled
on it (beacon: a walk that stopped must be a failure; forge: count the hole and go on, as the combined scan does, then
retire the legacy path). The user decided on 2026-10-31, for the combined scan, that a
pass that ran settles the flags, holes or not, because a rescan at every start blocks regular sync for hours. With the
fix above, "a stopped walk is a failure" would be that rescan on any datadir with one hole. The consistent change is the
combined scan's hole counter for the legacy walks, which is the user's to decide (B-217). What the SNAP controller does
when a validation ends in a state fault is unreachable until the validator is handed a view (B-216).

**Not covered here, and said so.** The controller's two new arms for `RecoveryNotRun` have no test of their own: the
legacy path is chosen by the process-wide configuration, which the controller's spec cannot set for one case. The
actors' message is pinned; the arms mirror the ones beside them. And one case of `SyncControllerSpec` fails when that
spec is run alone, at #1797's head as well as here, though it passes in the full run (B-218).

**The ratchet** went from 133 lines and 263 uses to 121 and 240. Nothing in main resolves a node of the state by hash
alone in a walk any more: what is left of the list's walking section is the store each walk is handed.

### C3b storage record (2026-11-11)

C3b is two PRs. **This is the first, the storage half**: what a block does to the state is recorded by position while
it runs, and committed in one batch (T087 to T092, T095, T096, with their tests). Nothing in block execution calls it
yet. The second PR is the wiring: `BlockExecution` and `Blockchain` (T093, T094, T084), what the guard may be used for
before C4, and the differential over real blocks.

**Three consults before code** (beacon, forge, vault), and what they changed.

| Finding | By | Change |
| --- | --- | --- |
| A second place replaces a storage trie whole: `initialiseAccount` over an account that has storage (a create collision; reachable on ETC, where the collision check is nonce and code only). It runs inside a frame that can revert, so a reset applied at once to the block's shared layer cannot be undone | forge (BLOCK) | **the world carries the owners it has reset**, as a value that reverts with its frame, and tells its storage at `persistState`, before the contract storage is persisted. Neutral on a hash store. Its own commit, with `WorldStorageResetSpec` |
| An in-place commit at `newPayload` moves the anchor to a payload the consensus client has not chosen, and a one-slot reorganisation cannot be executed before the overlay | beacon (BLOCK) | the scope decision below |
| A view asked for between the move's announcement and its end waits for the writer, with no timeout: a commit that asked for one there would wait for itself | vault | everything is read and built before the announcement; inside it there is the one write. A test notes every call on the data source and holds the write to be the last |
| The lock must be the innermost, and not reentrant | vault | `StateLock`: fair, refuses its own holder, nothing under it but the data source |
| The scan of a replaced trie's rows, made outside the lock, can be stale by the time the batch is written | vault | it is made under the lock, after the anchor check (dm 7.0's last bullet has it outside, on a view: that returns with the overlay, when there are other views to scan) |
| Records cannot be scanned for their lowest height, so a restart, or a switch from archive, would read every height from 0 | vault, forge | the height pruned up to is kept in AppState, started by the first commit at its own number, moved in the batch that deletes |

**The decision taken, for the user to see: what the guard is for until C4.** dm 3.6 already says that on ETH every block
is a forward layer first and is promoted at `forkchoiceUpdated`. That is the overlay's (C4a, C4d), and ETC's
reorganisation is C4e's. So in C3b **the in-place commit serves linear import only**: a chain file, or an importer
that extends the anchor block by block. A block that does not extend the anchor is refused with `StateUnavailable`
and nothing written, where dm 7.1 has it degrade to an overlay commit. The second PR makes the rest of it true: with
the guard on, `newPayload`, a `forkchoiceUpdated` to another block, a reorganisation and a side branch end in a local
state fault (never INVALID, never a penalty for a peer), and the node does not start network sync, so that no ETC,
Mordor or ETH node runs on the guard before C4. Not chosen: executing twice (at `newPayload` and again at
`forkchoiceUpdated`), which is machinery C4a throws away.

**What a block's changes are** (`StagedChanges`, dm 3). The trie tells its storage of each change as it makes it: the
nodes on the path of the change, the nodes a removal merged into their parent, and the nodes it has there now. From
those, one entry for each position: the prior is fixed by the first event there (an old node named first is the
parent's; a node written where none was named has no prior, and every old node named there later is this block's
own); the next is the last word. Only stored nodes count, and a root is stored at any size. A reset clears what the
block had written under that owner, and at the commit every position of the owner takes the disk's row as its prior,
whatever the first event said.

**The commit** (`PathStateStorage.commitCanonical`, dm 7.0, 7.1), under the lock:

1. the anchor is checked: none, refused; this block, only the caller's own updates are written; not the block the
   layer was built on, refused;
2. the rows of each replaced storage trie are scanned, a chunk at a time, until they are all read or are more than
   may be listed;
3. the diff is held to the header's two state roots (`BlockDiff.validate`): a layer that is not this block's final
   world stops here;
4. the record is the diff, or a tombstone;
5. one batch: the caller's updates (the block, the best pointer), the rows, the deletes of retention, the record and
   its height index, the prune count, the anchor last; written as a move of the clock, whose `pre` is the priors.

**The bounds, and what each leaves.** A replaced trie with more rows than
`max-entries-per-owner-delete`, or whose rows hold more than 64 MiB (a constant of the commit's settings, added on
review: the rows are read into memory under the lock): its old rows stay, unreachable; the rows of whatever the block
put in its place are written; nothing is recorded for the block. A record over `max-record-bytes`: every row is written and deleted as it
should be, and only the record is a tombstone. The text of dm 3.6 reads as if the second skipped the deletion too; it
has no reason to, since the rows are in hand. Neither fails a block. A later block that writes where a leftover row
lies records no prior there, so a reverse across it would not put the leftover back: it is not state, and the
tombstone below it stops any reverse first (forge's caveat for the audit, C6).

**Reviews of the code (vault, forge, beacon: each SIGN WITH CHANGES, no consensus or correctness defect found).**

| Finding | By | Change |
| --- | --- | --- |
| The scan of a replaced trie's rows was bounded by a count alone, a million rows by default: read in one pass under the data source's read lock, copied three or four times, with the state lock held. About a gigabyte for one owner, and every other reader of the data source held back behind a queued writer | vault | the scan is made **a chunk at a time** (8,192 rows, from the key after the last), and stops at the count or at **64 MiB of rows**, whichever comes first. A trie over either is not listed: its rows stay and the record is a tombstone, as before |
| `finalized` was asked for under the state lock, and the observer told there too. Neither is this class's, and the first will one day be a consensus client's answer | vault | `finalized` is asked once, before the lock; the observer is told after the lock is free, and what it throws is logged and goes no further: a block that is committed stays committed |
| Nothing held the layer to **one line of worlds**. Two worlds persisted from one start on one layer (a retry on a layer that was not dropped) would mix their rows under a root that still checks against the header; the batch would be written, and the next read would call the datadir corrupt | beacon, forge | a change to a trie must replace the root the block last left there, or it is refused where it is persisted (`StagedChanges.linear`), before anything is written |
| A reset, and the commit, each looked through every change of the block for one owner's positions. A block can replace some 1,600 storage tries on ETC for the gas of their SELFDESTRUCTs: tens of millions of map operations, the second time under the lock | forge | the positions are kept by owner as they are recorded |
| "Already applied" writes the caller's updates. The block, its receipts and its weight are on disk by then, so all those updates can add is a best-block pointer: on a chain whose head is the consensus client's to choose, a payload sent again must not move it | beacon | said in the commit's contract. The wiring must hand an `extra` with no pointer from `newPayload`, and never stage a block that is the anchor already |
| The commit called any refusal from the record's writer "the index is corrupt" | vault, beacon | the index's own error (`ChangesetStorage.CorruptIndex`) is the one it reports so |
| The race test let a reader that started late pass without having waited, and its spin had no deadline | vault | the reader is known to be parked on the clock, and the second writer in the lock's queue, before the first is let go; thirty seconds bound the wait |
| A committed batch surviving a restart was not shown | vault | a RocksDB datadir is closed and opened again: anchor, rows, record and pointer are there, and the chain goes on |
| Amsterdam clears the storage of an account it keeps; a storage root under 32 bytes has a row; a layer that is not the block's, handed with a block that is applied | beacon | each through a commit, and back |

forge traced `MerklePatriciaTrie` for the question the whole record rests on: every node of the parent's state that a
change replaces or absorbs is named, at its own position, and a first event that names a node names what the parent
held there. forge re-signed the world's resets (F1).

**Not taken.** vault's registry of path state storages by data source, to stop two of them over one data source
having two clocks: vault accepted the reason on review. The node builds one, in `Storages`; a registry is
process-wide mutable state, and a test that opens a datadir a second time wants exactly the second clock it would
forbid. The class says one per data source. And forge's optional narrowing, a reset recorded only for an account
whose storage root is not empty: with the positions kept by owner a reset of an account with no storage costs one
seek at the commit, which forge found acceptable; it changes the world, which a hash store shares, and is better
judged with M15's count of resets that find no row (T100).

**For the wiring, from the reviews.** Only import may use `stage`: a builder and a retry use `throwaway` or a fresh
layer. A decorator that drops the trie's removals (`StxLedger`, `EthSimulateService`, `GenesisDataLoader` each have
one) must not wrap the staged storage: with no old node named, every prior comes out empty (forge). The block's
`BlockRef` takes its state root from the header, not from the world, or the check of the roots checks the world
against itself (beacon). A reader that lists one owner's rows by a scan must check each node against its hash:
leftover rows are allowed there (forge). On ETH non-finality has lasted longer than 512 blocks, so a reorganisation
past the floor must end in a state fault, never INVALID (beacon, C4d).

**Tests.** A block is run twice from one parent state, on the staged storage and on a hash store, and the hash
store's nodes, walked as raw RLP, say what the rows must be: forward (parent rows and every next are the new state's
rows), reverse, each prior is the parent's row, and `validate`. At the commit the same, read off the disk: every row
of `t` and `u`, on the in-memory data source and on RocksDB.

| Spec | Cases | What |
| --- | --- | --- |
| `WorldStorageResetSpec` | 5 | the world's resets: removal, a create collision over storage, a frame that reverts, order at the persist |
| `StateLockSpec` | 5 | one at a time, in order, free after a throw, its own holder refused |
| `StagedChangesSpec` | 14 | hot rewrites, created then rewritten, vanished positions, a replaced trie, destroy and recreate, replaced twice, an older world's reads, an unlisted trie, a world that does not follow the last, nodes inside their parent, a root of any size, 40 seeded random blocks |
| `CanonicalCommitSpec` | 46 | 15 on each data source: the rows, the anchor and the record after each block; forward and back from the records; replaced tries; a dead trie with a hole; an account cleared and kept; a storage root under 32 bytes; an empty block; the three bounds, at and over; retention, the switch from archive, the record of the block being committed. And 16 more: no anchor, the three refusals, a layer that is not the block's, another data source, one write under the lock as a move, a write that fails, a corrupt index, what is asked and told outside the lock, an observer that throws, a datadir closed and opened again, a view across a commit, a view across an unlisted trie, a view gone stale, two commits on one anchor in both orders, two commits of one block |
| `OwnerRowsSpec`, `RetentionPolicySpec`, `StateMetricsSpec`, additions to `BlockDiffCodecSpec` | 18 | the scan's bounds (adjacent owners, the last owner, key order, chunks of every size, rows and bytes at and over, on both data sources), the floor's arithmetic, the metrics, the record's length without writing it |

**Broken on purpose.** 8 rules of the world's resets, 2 of the lock, 13 of the staged layer and 34 of the commit, one
at a time, and 13 more for what the reviews changed (the chunks and the two bounds of the scan, the line of worlds in
the account trie and in a storage trie, the positions kept by owner, the observer, where `finalized` is asked): each
is caught. One of those was missed at first: only the account trie's arm of the line of worlds had a test, since a
world that branches in a storage trie branches in the account trie too; a storage trie written straight onto the
layer pins the other arm. Two more are equivalent mutants and are said so in their drivers: a reset owner's position
with no parent row keeping the first event's prior (a first event with a prior names a node read from the parent, so
the row is there), and the record written before retention's deletes (the floor never reaches the block being
committed, which its own mutant holds).

**Not covered here, and said so.** No block has gone through the EVM onto this layer: that is the second PR's
differential, with the Amsterdam access-list vectors and the SELFDESTRUCT fixtures. T075's recorded values and the
measurements T098 to T100 need C0's pinned jar and ranges (B-203). A crash inside a RocksDB WriteBatch is RocksDB's
contract and is not injected. `finalized` is wired as none: the floor is `without-finality` until a consensus client
drives this layout (C4d).

**The ratchet** went from 121 lines and 240 uses to 121 and 241: the staged factory of `PathMptStorage` hands out a
store.


### C3b wiring record (2026-11-11)

C3b's second PR: block execution stages and commits a block on the unified layout (T093, T084, T094 in part), and the
differential over real blocks that stands in for T075.

**The adapter** (`PathStagedBlock`). Execution stages a block before it asks whether the block was executed already,
and a layer can only be made over the state the disk holds. For a block that is applied that state is the block's
own, so the layer is made when the execution first asks for the storage, and never for such a block: it reaches the
commit with nothing staged, where its own records are written if it is still the anchor. A block is staged by its
**header** (`stageBlockState(header)`): the parent is found by hash, and the staged changes are held to the state
root the header carries, not to the world's (beacon).

**In place or not at all, made true for the callers.** The commit of part 1 refuses a block that does not extend the
anchor. This PR refuses the callers that could not commit in place **before the block runs**, each with
`StateUnavailable`, a condition of this node's state and never a verdict on the block:

| Caller | On the unified layout |
| --- | --- |
| `executeAndCommit` with the block adopted (the import of a block onto the head, a chain file) | the canonical commit, with the block and the best pointer in its batch |
| `executeAndCommit` with the block not adopted (a side branch, the blocks of a reorganisation) | refused |
| `executeAndValidateBlockFull` (`newPayload`: the state committed apart from the block) | refused; `newPayload`'s existing arm answers ACCEPTED, with no INVALID, no `markInvalidRecursive`, no deletion (beacon traced it) |
| an execution with nothing staged (`getBackingMptStorage`) | `IllegalStateException` (dm 7.6): the chain file imported at start, test mode, a proposer's `executeBlock` |
| `forkchoiceUpdated` to a head that is not the anchor, or asking for a payload | SYNCING, with no payload id; nothing moved, published or marked |

And the node itself: it does not start network sync on the guard (ERROR at start), and the guard is refused at start
with SNAP sync, with mining, with `import-chain-file` and with test mode, each of which would fail later or never
finish. So no ETC, Mordor or ETH node follows a network on the guard before C4 (forge's condition). What it can do:
take blocks from a chain file through the strict importer, and answer from the state it holds.

**The differential (T075's substitute).** C0's pinned jar and ranges do not exist (B-203), so the same chains are
imported in process on both layouts, through `BlockExecution.executeAndValidateBlocks`, the path a synced block and a
chain file take. Every block validates against its header on each, so state root, receipts root, gas used, logs
bloom, requests hash and access-list hash agree by construction. What is held beyond that is the disk.

| Chain | Blocks | What is held |
| --- | --- | --- |
| the execution-apis chain (`ImportOnPositionsSpec`): proof of work, the merge, every fork to Amsterdam | 60 | after each block the rows of `t` and `u` are that block's state as a hash-store reference holds it, the anchor and best block are that block, receipts and access list are the reference's; the records take the disk back to genesis; a block with a wrong state root leaves everything; the hash store holds what the genesis load left and no node more; the same path with the guard off imports to the same head and writes nothing of the unified layout |
| Mordor's first blocks (`MordorImportOnPositionsSpec`, forge): the shipped Mordor configuration, the proof-of-work validators, real Ethash seals | 256 | the same, against Mordor's own state roots: the ECIP-1017 reward of era 0; a forged seal refused with nothing touched |
| execution-spec-tests, vendored (`EestImportOnPositionsSpec`): the regression vectors and `selfdestruct-stored-storage.json` | 47 kept, 5 rejected | after each block the disk is exactly the block's state, each node found among the rows by its hash; the record takes the rows back; a rejected block leaves rows, anchor and best block |
| the Paris and Shanghai corpus (`EEST_IMPORT_POSITIONS`), frontier to shanghai | 11,262 kept, 596 rejected, in 8,835 fixtures | the same; 336 storage tries removed whole, 3,262 of their rows deleted and put back by the reverse; no tombstone |
| the Amsterdam corpus (`tests@v21.0.0`), frontier to amsterdam | 27,352 kept, 5,715 rejected, in 26,503 fixtures | the same; 848 storage tries removed whole; no tombstone |

The fixture oracle has no second store for a block's new state: it walks the state from the header's root, which
validation has checked, finds each node among the disk's rows by its hash, and requires the disk to be those rows at
those positions and no other. Both reviewers read it as sound for the rows, a replaced storage trie included. It
does not look at contract code, which this track does not move.

**The access-list prefetch** (from T045) reads the parent's rows through a view of its own. The chain's Amsterdam
blocks are imported a second time with their lists supplied, as a peer serves them: each prefetch is handed a reader,
and the disk ends the same.

**Reviews of the code (beacon, forge: each SIGN WITH CHANGES, no critical finding; both confirmed nothing changes
with the guard off).**

| Finding | By | Change |
| --- | --- | --- |
| With the head at the anchor and a payload asked for, `forkchoiceUpdated` started a build over the hash store, which fails; the failure was swallowed, the answer was VALID with a payload id, and `getPayload` then had nothing | beacon | SYNCING with no payload id, said at ERROR |
| The chain file imported at start executes with nothing staged: the node aborted while starting, after a notice that said it imports chain files | forge | refused at start with the guard, naming the `import` command and `admin_importChain`; the notice says the same. Test mode too |
| The strict importer decides that a block is present by finding the head's state root in the hash store, which holds nothing on the unified layout: a file that overlapped the local chain was refused as a fork | forge | `BlockchainReader.holdsStateOf(header)`: the anchor on the unified layout, the root's node on the hash store |
| The 35 proof-of-work blocks of the test chain run under an ETH configuration with their headers trusted: no ETC rule is tested | forge | `MordorImportOnPositionsSpec` |
| Nothing showed that the hash store is left alone | beacon, forge | held in all three differentials |
| Only Paris and Shanghai fixtures had run | beacon | the Amsterdam corpus, through the same case |
| A state fault at `newPayload` was logged as an unnamed error | beacon | its own arm, at INFO |

**Not taken, with the reason.** beacon: the gate of `forkchoiceUpdated` sits before the branch for a head that is a
canonical ancestor, so such a head gets SYNCING where the specification expects VALID with nothing moved. Harmless
while the guard is in development, and that branch reads the canonical index, which the head move of C4b replaces.
beacon: check that the code of every account the walk meets is present; code is written by the world as it always
was, to the same storage on both layouts. `saveLastBlock` still writes the best pointer after a batch (dm 7.1 has it
write nothing): with no degraded commit the last imported block is the anchor, which both reviewers traced; the rule
returns with the overlay commit.

**Broken on purpose.** 16 rules of the wiring, one at a time: a commit without the block's records, a block
committed as another, a discarded layer kept, an applied block not known to be, each of the refusals, the header's
state root, the prefetch's reader, the gate of `forkchoiceUpdated` and its payload arm, the head's state looked for in
the wrong store, each start-up refusal. Each is caught.

**Not covered, and said so.** The node's own refusal to start sync on the guard is three lines of `StdNode.start`
with no test. `eth_getWork` still builds over the hash store on the guard (forge; the proposer view, T111). A
re-import of an older canonical block after a restart is refused as a state fault, with no message of its own for
the operator (beacon). T075's recorded values, and T098 to T100, wait for C0.


### C3c genesis record (2026-11-11)

C3c is three PRs: the genesis commit (this one: T107, T115), the proposer view (T102 to T106, T111 to T114), and the
lenient chain import, test mode and the ported harnesses (T108 to T110, T116, T117). Each of the last two lifts the
start-up refusals C3b put in `ConfigValidator.stateGuardErrors`, with the code that makes them safe.

**What it is.** On the unified layout the genesis loader builds the genesis tries on a layer of their own
(`GenesisLayer`, a staged layer over a view of nothing) and `PathStateStorage.commitGenesis` writes what they end
with: one batch, synced, under the state lock, as a move of the clock, with the genesis block, its weight and the
best-block pointer, the rows, the marks of a datadir this build created, the layout stamp `state.layout =
path-live-1`, and the anchor last. A genesis has no changeset record, and the count of pruned records is left for
the first block to start. The loader still runs at every start, to say what the genesis header is; on a datadir that
has its genesis the layer is dropped unused.

**One consult before code (forge: AGREE WITH CHANGES), and what it changed.**

| Finding | Change |
| --- | --- |
| The data model has the genesis built through the buffer that drops the tries' removals (dm 5.4, "insert-only"). On the unified layout that would make every second change to a trie look like a second world, which a layer refuses since C3b | **removals are honoured** on the genesis layer. The buffer exists because two storage tries can share a node by its hash on the hash store; here each account has rows of its own. The rows come out the same: every prior is none, a node made and then replaced ends on its last form, and nothing vanishes. forge checked the shapes that could differ (a slot of zero, a storage of zeros, `accountStartNonce`, two accounts with one storage) |
| One address written two ways (`0xAB…` and `ab…`, `0x1` and `0x01`) is two entries of the allocation for one account. On the hash store the second overwrites the first. Here the first's storage would be rows under an owner whose account names none, or a second trie built over the first | refused on the unified layout, before anything is built. No shipped genesis has one |
| This is the first thing that writes the layout stamp, and nothing reads it until the guard of C5a: a build with the in-development guard off would open a stamped datadir and run the hash store's code over a store that holds none of the state; and with the guard on, a datadir of the hash layout would have its genesis found, the commit skipped, and the node run with no anchor | **the rule of dm 5.3 is in the loader now**, both ways (`GenesisDataLoader.layoutRefusal`); C5a moves it to the node's own guard at start |
| The marks of a created datadir were a second synced write after the genesis, with a crash window between | they are in the genesis batch. That reference counts are exact is never said here: nothing on this layout has them |
| `commitGenesis` should also refuse a datadir with changeset records or a count of pruned ones, and a build that replaced a storage trie whole | taken |
| Genesis storage on a chain whose storage is not Ethereum-compatible is at positions execution never looks at (dm 5.4) | refused on the unified layout, when some account has a slot that is not zero. Every shipped chain is compatible |

**Not taken, with the reason.** forge: refuse a duplicated address on the hash store too. It would change what a
private chain's genesis does with the guard off, and nothing here needs it: the refusal is where the harm is. forge
(a suggestion): walk the layer from the genesis root at the commit and require the positions reached to be the change
set. The tests hold that property against an independent walk for every shipped genesis; at run time the commit holds
the account trie's root to the header's state root.

**Tests.** Each genesis is loaded twice by the production loader, onto the hash store of one datadir and onto the
unified layout of another. The first says what the genesis state is, by a walk of its raw nodes; the second must
hold exactly those nodes as rows, with nothing in its hash store.

| Case | What is held |
| --- | --- |
| Ethereum Classic (8,893 accounts), Mordor (no allocation: no row, the empty root), Platåberget and Sepolia (system contracts with code, storage and nonces) | the rows, the header's hash, the anchor in memory and on disk, a view of it at once, the block and its body, the head, the weight, the stamp, the marks; no changeset record |
| two accounts with one storage; a storage of zeros | each account's own rows; none |
| one batch | one synced write, made under the lock while the clock says a move is in flight, the stamp inside, the anchor last; a write that fails leaves no block, row, stamp or anchor, and the next start commits |
| a datadir that has something | a restart writes nothing; another genesis is refused as before; a commit over an anchor, over rows with no anchor, or as a block that is not block 0, is refused; a build that is not the state its block names |
| what a genesis may not have, and which layout opens a datadir | an account allocated twice, in capitals and unpadded; storage on a chain that would not find it; the rule of dm 5.3, case by case and through two real datadirs |
| the guard off | the load writes no stamp, no anchor and no row of the unified layout |

The import differentials of C3b (`ImportOnPositionsSpec`, `MordorImportOnPositionsSpec`) now start from a genesis
commit where they seeded rows by hand, so block 1 is imported onto a committed genesis: an allocation with contracts
on the first, none on the second. The fixture replay still seeds by hand: a fixture's genesis header carries fields
the loader does not produce, so its hash is the fixture's and not the loader's (the harness port, T117).

**Mutation run.** 25 mutants of the commit and of the loader's rules, each a rule dropped or weakened (a commit over
an anchor, over rows with none, over changeset records, over a count of pruned ones, over a stamp; a block that is
not block 0; a build that replaced a storage trie whole; a build not held to its root; the stamp or the marks left
out; the anchor not last; the batch not synced or written before the move is announced; counts said to be exact;
removals dropped; the genesis stored the hash store's way; no best block; the two refusals of the loader; the layout
rule's three arms; the two halves of address normalisation; the withdrawals list of the body): 25 caught. Two were
first written so that the unused-symbol ratchet refused them before any test ran, and were re-aimed. Four were added
after forge's read found their rules had no test. One rule has no mutant: a change with a node before the genesis,
which no build over a view of nothing can make; it is kept as a guard.

**Two reads after code (forge: APPROVE WITH CHANGES; beacon: APPROVE), and what they changed.** Both traced the guard
off to the code of `staging`: the same buffer, the same writes in the same order, one more read of AppState (the
stamp, which no datadir of the hash layout has).

| Finding | Change |
| --- | --- |
| forge: four refusals of the commit had no test and no mutant (changeset records, a count of pruned ones, a stamp with no anchor, a build that replaced a storage trie whole) | four cases, four mutants |
| forge, beacon: the header's hash was held only to the hash layout's, so a state root wrong on both would pass | each shipped genesis is held to the hash every client of its network has: Ethereum Classic `d4e56740…`, Mordor `a68ebde7…`, Platåberget `ee33ef92…`, Sepolia `25a5cc10…` |
| forge: the one-batch case had no code, so nothing showed where the code write falls | a case with code: one write that is not synced, then the batch, and the code is found by its hash |
| beacon: the genesis block was built once in each arm | built once, before the arms |
| beacon: the commit threw where the loader returns a `Try` | the commit's refusals and a failed write are the load's `Failure` |
| forge: `ChangesetStorage.isEmpty` runs an effect to its end on the calling thread | said in its doc: for a caller at start-up |

**Accepted, with the reason.** forge: on the hash layout the genesis nodes are written before the genesis block, so a
crash between them leaves nodes and no block 0; such a datadir then opened with the guard on gets a genesis of the
unified layout beside orphan nodes of the hash store. Nothing reads them, the window is one start wide and needs the
guard to be turned on after it, and C5a's guard at start is where a datadir is judged whole. Left as it is.

**For part 3, from beacon and forge (test mode).** `TestService.setChainParams` builds its loader by the constructor
and not by `GenesisDataLoader.over`, so it has no position store and reads no stamp; it removes the genesis block and
loads another, which on a datadir with an anchor the commit refuses (the anchor, the rows and the stamp are still
there), so part 3 needs a reset of the state in one synced batch that also clears the clock's anchor; its
`storeGenesisAccountCodes` and `storeGenesisAccountStorageData` write to the hash store; and it throws away the
`Try` of the load. The start-up refusal of `testmode` with the guard on stands until then. An Amsterdam-at-genesis
header on the unified layout (no shipped chain has one: Platåberget's genesis is before its Amsterdam) comes with the
fixture replay of T117, whose Amsterdam fixtures start there.

### C3c proposer view record (2026-11-12)

The second of C3c's three PRs: T102 to T106 and T111 to T114.

**What a node on the guard can and cannot do after it.** Every builder is right on the unified layout, and is held
to import by differentials. **No running node proposes and follows yet**, and this PR does not change that:

- a block that is mined reaches import through `SyncController` to `RegularSync` to `BlockImporter.MinedBlock`, and
  a node on the guard does not start network sync (C3b): it could not follow the side branches and reorganisations
  that come with it. A mined block would be dropped as a dead letter;
- `engine_newPayload` executes a payload apart from adopting it, which the unified layout refuses before its overlay
  (`refuseUnadopted`, C3b): the answer is ACCEPTED, and a `forkchoiceUpdated` to that block is SYNCING. A payload
  this node built is a block like any other there.

So the refusal of `mining.mining-enabled` at start **stays**, with its real reason in its message, until the head
can move (C4b); and `eth_getWork` and `eth_submitWork`, which are served with the internal miner off, answer an
error with the same reason, where a solution would have been answered `true` and its block dropped. The plan had
this PR lift the mining refusal; forge traced the route and it does not exist. A solo miner that started regular
sync for its own blocks alone would refuse every valid block beside its own and publish a minority chain: that is
the door C3b closed.

**What it is.**

| | Guard on | Guard off |
| --- | --- | --- |
| The state a block is built on (`BlockchainReader.proposerStateOn`) | the parent's state under a layer of the build's own (`PathStateStorage.throwaway`): a fresh view and a fresh layer at every call. What the build persists is read back by it and dropped with it. No row, no record, no anchor move | `None`: `executeForProposer` takes `getBackingMptStorage(child number)`, `prepareBlock` takes `getReadOnlyMptStorage()`, each as it did |
| The Engine API's build | `forkchoiceUpdated` with attributes builds on the block whose state is held (the SYNCING answer of C3b is gone). A view that goes stale under a lenient attempt is retried on a fresh one with the attempt's own list, three times; any other fault of the node's state is thrown past both fallbacks (the empty payload, and the block sealed over its parent's root), and the first build answers -38003 with no payload id, as go-ethereum answers a `BuildPayload` that fails. A rebuild that meets one serves the version it has | unchanged |
| The pool's nonce filter (`executableAtParent`) | reads the parent's state through `accountAt(parent, …)`; a state that is not held is the build's to answer, where a missing node of the hash store leaves the pool unfiltered | the same read-only buffer at the same root |
| `testing_commitBlockV1` | commits the block it built by importing it (`executeAndValidateBlocks(…, adoptEachBlock = true)`): the block, its receipts, its access list, the head and its state in one batch. An import that refuses is an error, and the head does not move | unchanged: the build's own writes are the state |
| `BlockParam.Pending` (both copies: `ResolveBlock`, `EthProofService`) | the head: the pending block's state is held nowhere, and readers that take its header would be refused | the generator's last block and its world |
| `MockedMiner` | builds the next block with no inherited world, once the block before is the block whose state is held; waits up to ten ticks for that import, stops at once when the state on disk is another block's at that height | chains the worlds of its builds |

What a build does write on both layouts is contract code: `persistState` puts it in the code store, by its hash.
It is the bytes import writes.

**Two consults before code (forge and beacon: AGREE WITH CHANGES), and what they changed.**

| Finding | Change |
| --- | --- |
| beacon: the design said `newPayload` commits a block that extends the anchor. It does not: it executes apart from adopting, which is refused. No node on the guard proposes and follows before the overlay | said above and in the PR; `testing_commitBlockV1` goes through the adopting import and not through `newPayload` |
| forge: a mined block has no route to import on the guard | the mining refusal stays, reworded; `eth_getWork` and `eth_submitWork` refuse, which the design had not seen: with the builders fixed, work would have been given and a solution dropped |
| forge: "pending while its parent is the anchor" is not enough. `eth_getBalance`, `eth_getCode`, `eth_getStorageAt`, `eth_getTransactionCount` and `eth_getProof` read state by the pending block's header, which is never the block whose state is held. On the hash store they work because the proposer's nodes leak into it | pending is the head on the unified layout |
| beacon: the pool's nonce filter reads the hash store, which is empty there: the pool went unfiltered and stale transactions burned the lenient retries | it reads through the seam (T111) |
| beacon: the first build's answer on a fault is neither SYNCING (the head was already set) nor VALID with no id | -38003, the head kept, nothing stored |
| beacon: catch the stale view around the attempt alone, as a value; never turn a fault into a `Left`, which falls to the empty payload and then to a block sealed over its parent's root | so built; a state fault still cannot reach either fallback |
| forge, beacon: the guard-off arms must be the calls they were, to the letter (mocks stub them, the ratchet counts them, `getBackingMptStorage` takes the child's number) | `proposerStateOn(...)` is tried first and the old call is its `getOrElse` |
| forge: the mocked miner is gated on the guard and not on the kind of fault; it retries only while the state is behind the block it mined | so built |

**Tests.**

| Spec | What is held |
| --- | --- |
| `ProposerOnPositionsSpec` | every post-merge block of the execution-apis chain, to Amsterdam, is built again on the unified layout, strict and lenient (with a transaction an earlier block included, which the lenient build drops): the fixture's hash. Around each build every key of the datadir but contract code is compared; new code is keyed by its hash; the epoch and the anchor have not moved. The block is then imported. Also: a stale view retried once; a view that stays stale ends as that after four attempts and never as an empty payload; a parent that is not held; `forkchoiceUpdated` with attributes, whose payload import takes; its answer with no id on a fault; the pool filter; `testing_commitBlockV1`, and its import refusing |
| `MinedBlockOnPositionsSpec` | proof-of-work blocks prepared on both layouts from the same parent, transactions, ommers and clock are the same block: a contract deployed with storage, a slot cleared, the contract destroyed and its address paid again in one block (no row of its storage is left), a transaction the generator leaves out, ommers, the first block of the next era. A block that is prepared writes nothing; import computes the root it carries. Pending is the head on the unified layout |
| `MockedMinerOnPositionsSpec` | on the test's own clock: the next block is built on its parent with no inherited world, after the import; the bound; a block lost to a sibling; a state that moves between the look and the build; a fault that is not of the state |
| `EthMiningServiceSpec`, `EthProofPositionStoreSpec` | no work given or taken on the unified layout, and nothing sent to sync; `eth_getProof` for pending answers for the head and does not ask the generator |

**Mutation run.** 24 mutants over the accessor, the two builders, the Engine API's retry and its answer on a fault, the pool filter, `testing_commitBlockV1`, pending in both resolvers, the two work methods and the mocked miner's wait: 24 caught. One was first written so that it did not compile, and was re-aimed. One was missed at first: the mocked miner building on the world of its last build when the import had landed before its next look, which the tests reached only after a wait (where the world is dropped anyway); a case was added, and the mutant is caught. Not mutated: one layer shared between attempts, which gives the same roots.

**Two reads after code (forge and beacon: APPROVE WITH CHANGES, no blocker).** Both traced the guard off, call by
call, to what `staging` evaluates, and found no route by which a fault of the state reaches a fallback of the
lenient build.

| Finding | Change |
| --- | --- |
| forge: the mocked miner's spec raced its own clock: the miner tells sync of a block before it sets its timer, and a failed build comes back after the reply a tick waits for | the spec waits for the miner to have dealt with what it was sent before it moves the clock, and where a failure comes back on its own it ticks until the block is there |
| beacon: the lenient builds of blocks with no transaction proved nothing, since dropping a transaction and falling to the empty payload give the same block there | drops are counted where the block has transactions, and each such build is two executions: the one that fails and the one that is the block |
| beacon: no test on the unified layout reached the empty-payload arm | a case: a failure no transaction explains gives the empty payload, which import takes, and writes nothing |
| forge: what ommers are paid was held only through the block's hash | the ommers' miners are read on both layouts |
| both: comments that named a spec that does not exist, or the proposer's nonce read as a caller of the read by a root | corrected, with the allow-list's reason |

**Not here, with the reason.**

- An Olympia block prepared on the unified layout (forge). At Olympia the base fee is credited to the Treasury
  account: one more account written through the same layer, by code import shares. The shipped test configuration
  has no fork between Frontier and Olympia that a four-block chain can cross. With the lift of the mining refusal
  (C4b), on Mordor's own configuration.
- That each lenient attempt has a view of its own is not asserted by a count: one layer shared between attempts
  gives the same roots, so no test could tell. The retry on a stale view is what needs it, and that is tested.
- A fault of the state raised by a read in the middle of an execution: every injected fault is thrown before the
  execution or as it opens its storage. Nothing between a trie read and the builder catches it (beacon read the
  path), and import's own tests of a view lost in mid-execution are C3b's.
- A view that goes stale in the pool's nonce filter is not retried, and neither is one in the strict build of the
  testing namespace: before the overlay a stale view means the parent is no longer held, and a fresh one would be
  refused. The first answers -38003, the second a JSON-RPC error; the head does not move.
- `eth_submitHashrate` still marks the node as active on the guard. Left.
- The fixture replay of the builder on positions (`EestBuilderReplay`): it starts from a fixture's genesis header,
  which the loader does not produce. With the harness ports (T117).
- A rebuild that meets a fault after the head moved (beacon): the path is the one that already serves the stored
  version when a rebuild throws; a test needs a pool. Not added.
- `test_mineBlocks`, `TestmodeMining` and `NoOmmersBlockGenerator`'s caller are test mode: part 3, with its refusal.
- For C4b, when network sync returns: `BlockImporter` drops a `MinedBlock` that arrives while an import is running
  (`case _: MinedBlock => Behaviors.same`), which a miner that waits for its last import will meet every time; and
  the mining refusal and the two work methods are lifted there.
- A node that is corrupt (`CorruptNodeException`, an `MPTException`) still reaches the lenient build's fallbacks,
  as a missing node of the hash store does. Unchanged.


### C3c part 3: the linear subset now (user, 2026-11-12), and the lenient import record

The third part of C3c was written as the lenient chain import, test mode and the ported harnesses (T108 to T110,
T116, T117). As written it takes side blocks, rewinds and reorganisations, which the unified layout cannot do before
its overlay: a block is committed in place when it is built on the block whose state is held, or not at all (C3b).
**Decision (user): the linear subset is done now**, and what needs a state beside the head's moves to C4a and C4b:
post-merge side blocks in the lenient import (the rest of T109), `test_rewindToBlock`, and the fork-choice harness
(`ForkChoiceBlockchainTestsSpec`, the rest of T117). It is three PRs: the lenient import (this record), the fixture
replays on positions with the harness canary, and test mode.

**The lenient import (`ChainImporter.importChainFile`, the `import-chain-file` key: hive and ethereum/tests).** On
the unified layout a block that is built on the head goes through the node's own import of one block onto the head
(`BlockExecution.executeAndAdoptBlockIf`, the one an operator's restore uses): one batch for the block, its
receipts, its weight, the head and the state. A block that is not built on the head is refused, on either side of
the merge, and nothing of it is kept; it is counted as failed, and the log says it is a limit of this node's layout
and no verdict on the block (the first loudly, the rest at debug). A head whose state is not the one the disk holds
is a fault of the datadir, and ends the import. The refusal of `import-chain-file` with the guard at start is
lifted. With the guard off the three arms are as they were.

**One consult before code (forge and beacon: AGREE WITH CHANGES).** Both read the two paths side by side: for a
block built on the head the standard import makes the checks the lenient arm makes, in the same order, with the
same validator (before execution, after it, the requests hash, the access-list hash), and writes what
`saveImported` wrote, in one batch where that was two writes.

| Finding | Change |
| --- | --- |
| beacon: a fault of the node's own state must not be counted as a failed block and passed over; every block after it meets it too | it is thrown, as anything thrown by the hash store's arm is, and ends the import |
| beacon: "built on the head" must also mean "the head's state is the one held"; otherwise the log blames the block for a fault of the datadir | checked, and thrown as that fault |
| forge: calling the fork choice for a side block would be worse than refusing it: a heavier branch reaches a reorganisation that the layout refuses on its first block, and the throw would end the node's start | refused before the fork choice |
| both: failed and not skipped (skipped means present, or kept as a side block; nothing is kept); do not store the side block by its hash | so |
| forge: the parent's weight as the strict path takes it | so; each block's weight is compared with the hash store's |
| beacon: one error line per refused block is noise for a long tail | the first of each kind at error, the rest at debug, and both counts in the import's last line |
| beacon: an Amsterdam block with a wrong access-list hash should be among the cases | it is, and its reason is held |

**Two reads after code (forge: APPROVE WITH CHANGES; beacon: APPROVE).** Both traced the guard off to what it was:
one more read, of a flag that is false there. Taken: the two checks and the commit are under one exclusive lock,
with the head looked at again before the commit, as the strict path has it (the import runs at start, where
nothing else writes the head, so this is parity and not a fix); a fork choice that fails the test when it is
reached, and the refusal counted by its kind, so that a side block is seen to be refused for that and not for
something an execution found; the counters are per import; the datadir compared around the fault of the held
state. The exclusive lock taken around an import that takes the shared one is the nesting the strict path has had
since C3b (a write lock's holder may take the read lock).

**Tests** (`ChainExportImportSpec`, on the unified layout): the execution-apis chain file imported whole, to the
head, rows, receipts and weights it has on the hash store, with nothing in the hash store; the file again, every
block skipped and nothing written; an invalid block counted and the valid one at its height imported; an Amsterdam
block whose header commits to another access list refused with nothing written; the blocks after a failed one
refused; a side block before the merge and after it refused with the datadir as it was, and the chain going on;
a head whose state is not held. A mutation run over the new arm (the arm not taken, a block that is not on the head not refused, or counted as kept, or counted under the other kind; the held-state check dropped; the weight taken wrong; a block that import refuses counted as imported): 7 mutants, 7 caught. The look at the head before the commit has no mutant: nothing moves the head while this import runs.

**What a node on the guard gets from it, and what it does not.** It starts from a chain file and serves the head
it imported. The ethereum/tests vectors that end on a side chain (`bcMultiChainTest`, the uncle races,
`lotsOfLeafs`) end on another head with the guard on, until C4. No hive run sets the guard. And a payload sent by a
consensus client after the import is still accepted and not adopted, before the overlay (C4a, C4d).


### C3c part 3b: the fixture replays from a genesis commit, and the harness canary

The two replays of the execution-spec-tests fixtures (`EestBlockchainReplay.replayOnPositions`, of import, and
`EestBuilderReplay`, of the builder) started on the unified layout from rows a test kit copied out of a genesis
loaded on the hash store: a fixture's genesis header carries fields the loader does not produce, so the loader's
genesis was not the fixture's. They now start from **the node's own genesis commit, under the fixture's header**:
the loader builds the state from the fixture's allocation and commits it, and the header it stores is the one the
harness hands it. The seam is the loader's `prepareHeader`, protected where it was private; nothing in the node
overrides it. So no node of a fixture's state is written to the hash store, at the genesis or after it, and the
datadir a replay ends on is one the unified layout wrote alone.

- **The import replay** (`EestImportOnPositionsSpec`) is otherwise as it was: after each block the rows are the
  block's state, the anchor and the head are the block, and its record takes the rows back.
- **The builder replay** has a run on the unified layout (`rebuild(t, onPositions = true)`,
  `EngineApiAmsterdamBuilderEestSpec`): each block is built on a view of its parent's state to the fixture's hash,
  and is then imported, where on the hash store it is saved as it was built. The eight vendored Amsterdam fixtures
  (14 blocks, the EIP-8282 dequeues among them) pass, and an Amsterdam fixture's genesis is the first
  Amsterdam-at-genesis header committed to this layout.
- **The harness canary** (T110, `HarnessCanarySpec`): a replay on positions ends by asking its datadir what wrote it
  (`EestBlockchainReplay.notOnPositions`: nodes in the hash store, no layout stamp, no anchor, an anchor that is
  not the head). The spec holds the
  question to both answers: a genesis the unified layout committed passes it, and the same fixture's genesis loaded
  with the guard off is seen for what it is. A harness that was quietly on the hash store no longer passes.

**The corpora, again, from genesis commits** (`EEST_IMPORT_POSITIONS`, the replay of import): the Paris and Shanghai sets, 8,835 fixtures, 11,262 blocks committed, 596 rejected, 336 storage tries emptied; the Amsterdam corpus, 26,503 fixtures, 27,352 blocks committed, 5,715 rejected, 848 storage tries emptied. No divergence and no tombstone in either, and the figures are those of C3b's runs from copied rows. The 35,338 genesis states the loader committed are the states the fixtures name: each one's rows were walked from its root and compared, as every block's are (run again after the read below asked for that).

**One read after code (beacon: APPROVE WITH CHANGES).** The seam is the narrowest there is: no new public method,
no constructor argument, no flag in the node; the override gives the fixture's header only when the state built is
the one it names. Two changes, both in the harness: the rows a genesis commit leaves are held to the genesis state,
each of its nodes and no other, where the copy that seeded them was exact by construction (a row missing at the
genesis could have hidden behind a block the fixture expects to be rejected); and the question the canary asks also
holds the anchor to the head, at the genesis and at the end, so that a run whose state stopped moving is seen.

**Not here.** The three harnesses under `it/ethtest` (`EthereumTestHelper`, `EthereumTestExecutor`,
`ForkChoiceBlockchainTestsSpec`): the first two import ethereum/tests chains with side blocks through the lenient
importer, and the third goes through `reorganise`; they wait for the overlay and the head move with the rest of
T109 and T117. `RpcCompatNode` needs no port: `ImportOnPositionsSpec`, `ProposerOnPositionsSpec` and
`ChainExportImportSpec` run it on the unified layout by overriding its storages.


### C3c part 3c: test mode on the unified layout (2026-11-12)

Test mode (`fukuii.testmode`, retesteth's `test_*` methods, `TestService`) runs on the unified layout, in the linear
subset. Its refusal at start is lifted. Tasks T108 and the rest of T116. With the guard off each method does what it
did, but for two faults of test mode that the work found and that are fixed on both layouts (below).

- **A chain is set on a datadir that holds the chain before** (`test_setChainParams` is the first call of every
  test). On the hash store the new genesis is written over the old. Here a genesis is committed only to a datadir
  that holds no state, so, in this order: a genesis the layout cannot commit is refused with the datadir as it is
  (`GenesisDataLoader.unifiedLayoutRefusal`); the blocks of the chain before are removed, from the head down (the
  hash store's arm removes block 0 alone and leaves the numbers above it naming old blocks); its state is destroyed;
  the node's own loader commits the genesis, and its failure is answered as an error. Nothing of the genesis goes to
  the hash store: the storage tries are rows of the commit.
- **The destruction of a state** is new, and is the position store's (`PathStateStorage.destroyStateForNewGenesis`):
  under the state lock, one synced batch of point deletes of every row and every changeset record, with the anchor,
  the layout stamp and the pruned-to count. One batch, so there is no moment with the rows gone and the anchor
  there, or the reverse; point deletes, since a range delete is no part of a batch; so it holds the keys in memory,
  and refuses above a million. It is a move of the clock of its own kind (`StateMoveLog.inFlightToNoState`): the log
  of moves is emptied before the epoch goes odd, so a view made before it is stale and never reads "no row" as its
  state. **Only a store built for a node in test mode does it** (`CommitSettings.allowsStateDestruction`, set from
  `instanceConfig.testmode` in `NodeBuilder`); any other throws.
- **Blocks.** A block on the head is committed in place by the import test mode always used (`importToTop`, the
  adopting execution). A block beside the head is queued, as on the hash store. The block that makes a branch the
  heavier one reaches a reorganisation, which the layout refuses before anything is written; the answer says the
  layout holds the state of its head alone until its overlay (`TestService.HeadStateOnly`), and is no verdict on
  the block. A mined block that was not made the head is an error there, where the hash store answers that it was
  mined.
- **A rewind** (`test_rewindToBlock`): to the head or above it, no block goes; to the genesis, the chain that was set
  is set again (the same block, the same state, on a datadir that holds nothing of the blocks above it); to a block
  between them, refused before anything is removed. The state of such a block is held nowhere until the head can
  move back (C4b).
- **The world of test mode** (`TestModeWorldStateProxy`) carries the storage tries it replaces whole, as the base
  world does since C3b, and builds its account trie over the account trie's own view of the storage: without the
  second every import in test mode threw on the unified layout (forge).
- **`TestModeNode`**: what the three classes of test mode take from their node (the chain configuration and seal
  engine a test sets, the queue, the genesis loader, the position store). `TestNode` is it; a spec gives the
  services one in memory.

**Two faults of test mode, fixed on both layouts, each in a commit of its own.** Both are visible with the guard off,
in test mode and nowhere else; neither touches a state root.

1. *The world of test mode stopped being that world at the first persist.* The base world's `copyWith` built a plain
   world, and a block's transactions are each persisted. So the hook that notes the storage keys a transaction
   writes ran for the first transaction of a block alone, and `debug_storageRangeAt` left out every slot first
   written by a later one. `copyWith` is protected and the test-mode world overrides it.
2. *`test_mineBlocks(n)` mined one block and n - 1 siblings of it.* The head was read once, outside the effect that
   is repeated; each later block was built on the same parent, queued as a side block, and reported as mined. The
   head is read when each block's turn comes.

**Not parity with the hash store, and said in `ConfigValidator.stateGuardErrors`:** a side branch is never
reorganised to; a rewind goes to the head or the genesis; `debug_storageRangeAt` and `debug_accountRange` answer at
the head alone. Each is in B-220, with the side blocks of the lenient import.

**Tests.** `TestServiceOnPositionsSpec`: two test-mode nodes in memory, one on each layout, given the same calls.
The same block at each height; rows that are exactly the head's state; nothing in the hash store; the same answers
from the storage range and the account range. The blocks: a contract created with storage, a slot first written by
the third transaction of a block, a storage trie that loses a leaf, a contract created onto an account with storage
and no code, a contract destroyed and paid again in one block, two blocks in one request. A second chain, and the
first again by a rewind, each leave the datadir a node that was only ever given that chain has. The refusals each
leave the datadir as it was: a genesis written twice, a store that would not destroy its state, the heavier branch,
the rewind between, a mined block that is already queued, a head whose state is not held, a range of the state of a
block below the head. A write that fails (the destruction, the genesis commit) is an error of the method, and the
chain is set by asking again. `TestModeWorldSpec` (the world), `StateDestructionSpec` (in memory and on RocksDB),
`StorageBuilderStateGuardSpec` (only a node in test mode).

**Three reads after code (forge, vault, conduit: each APPROVE WITH CHANGES, no blocker).** Guard off was traced
method by method to what `staging` does but for the two faults above; the destruction's batch, key shapes, bound
and clock were found right, and its fence closed (one override, `instanceConfig.testmode`). Taken:

- *What is refused is refused before anything changes* (vault). The store's refusals (not built for test mode, more
  keys than one batch deletes) were met after the blocks of the chain before were gone. The service now asks first
  (`PathStateStorage.refusalToDestroyState`, a read under the lock), with the genesis refusal, and before it takes
  the new chain's rules.
- *A fault is an answer* (forge, conduit). A write that failed in `test_setChainParams`, and a range read of a block
  below the head, were thrown before any effect existed, past the controller's mapping, to the transport (an HTTP
  500). Both are now errors of the method. The codes stay -32000: -1 is "block validation failed", a verdict on a
  block, and a limit of the layout answered as that could let a test that expects an invalid block pass.
- *A view made after a move is stale across a destruction* (the mutation run): the case had an empty log, where a
  log kept across the destruction changes nothing. It now has a move in it.

**The mutation run**: 41 mutants over the service, the world, the destruction, its clock and its fence; every one
is caught. Four were not at first, and each got a case: the log of moves kept across a destruction, a state above
the bound deleted all the same, the changeset keys with no bound of their own, and a chain that was not set still
taken for the one to rewind to.

Left as they are, and said: an invalid branch that outweighs the head is answered with the layout's refusal and not
with "block validation failed" (the refusal comes before validation); the service's fields are plain variables, as
they were (retesteth calls in sequence); the queue is kept across a rewind to the genesis, as on the hash store.

### C4a part 1: forward layers (2026-11-12)

C4a is four PRs, in this order, each a precondition of the last: **forward layers** (this), **the reverse index**
(views of blocks behind the anchor and of forks), **the bounds** (budgets, eviction, pinned branches), and **the node
uses it** (an execution that is not adopted commits to the overlay). Until the fourth, nothing in the node makes a
layer: part 1 is storage and its spec.

- **A forward layer** (`ForwardLayer`) is what an executed block, whose state is on no row, **left** of its
  parent's state: the node at each position its tries touched (or that it left none), the **hash** of the node its
  first event there named as old, and the owners of the storage tries it replaced whole. A position of a replaced
  trie that the block did not write again reads as no row. It is made from what the execution staged
  (`StagedChanges.frozen`), and **nothing of the parent's state is read to make it**.
- **A layer does not grow with a storage trie its block destroys** (forge, in the consult for part 3). ETC has no
  EIP-6780: a SELFDESTRUCT removes a trie of any size. A layer that held a prior for each of its rows, which is
  what this part first built, is as large as the trie; under the bounds of part 3 a heavier proof-of-work branch
  with such a block in it could not be kept, so could not be adopted, and the node would stay on the lighter
  branch. What a block did to the rows (the priors, the rows of a replaced trie, whether they are too many to
  list) is therefore settled **when the layer is promoted**, below.
- **A view** (`StateView`) of a block that is not the anchor reads through the layers between the disk and that block,
  newest first, and then the row as its base held it: the existing read, corrected by the log of moves. It holds its
  layers itself, so it reads the same after one of them is promoted or dropped, until the log no longer reaches back.
  **The epoch, the anchor and the layers are one observation** (`PathStateStorage.viewAfter`): a layer goes only
  after the batch that promoted it has settled, and a view whose epoch moved while its layers were looked up is made
  again.
- **The overlay commit** (`PathStateStorage.commitOverlay`, data-model 7.2) keeps a staged block as a layer and writes
  nothing: no row, no record, no anchor, no move of the clock. The lock is held for the look at the anchor and the
  registration alone, which are one step: the block that has become the anchor is not kept beside itself, and a
  block whose parent's state is no longer held (neither the anchor nor a layer) is not kept.
- **Promotion** (`PathStateStorage.promote`, data-model 7.1 case 2) commits a layer whose parent is the anchor
  through the batch an in-place commit writes, and drops the layer when the batch has returned. **The block is
  settled then, against the disk, under the lock, as a commit in place settles one**
  (`StagedChanges.settle`, which `finalise` now is): the prior of each position is the row, read then and held to
  the hash the layer names; the rows of a replaced trie are scanned then, and listed or left by the bound a commit
  in place applies. The disk is the parent's state at that moment, so the record, the rows deleted and the priors
  of the move are those of the same block committed in place. A layer that was not built on the rows the disk holds
  is dropped, with what stands on it, and nothing is written.
- **What is kept can be promoted, unless the disk is not the state it was built on.** A layer is held to the two
  state roots when it is made, by the account trie's root (the hash of the node the block first found there is the
  parent's state root, and the node it left hashes to the root in the block's header), and to every other check of
  a diff that needs no row of the parent's state: a node under 32 bytes away from its trie's root, the shape of the
  blocks' names. The whole diff is validated again at promotion; a layer that fails there, which only a defect can
  bring about, goes with what stands on it. Kept, it would be the answer to every later execution of its block,
  and a branch with that block in it could never be adopted (forge, beacon).
- **A wrong node under a layered view** is `LayerInconsistent` when the layer gave it, or when the disk is at rest on
  the view's base: a new `StateFault`, not retried as a stale read and **never a corrupt row**, since what a corrupt
  row is answered with deletes it by hash. Stale otherwise.

**Not as the data-model has it, and why.**

- Promotion is its own method, and `commitCanonical` with nothing staged does what it did. Promotion moves the
  anchor, so it is for a caller that adopts the block; once layers count as executed (part 4), a caller that only
  has the block's own records to write reaches `commitCanonical` with nothing staged for a block that is a layer
  (forge).
- **The third case, a staged block whose parent is not the anchor degrading to an overlay commit, is not built
  here.** `PathStagedBlock.commitWith` takes a return for "committed", and `saveLastBlock` would then write the head
  past the anchor (beacon, forge). It lands with its callers in part 4: `buildExtra(adopted)`,
  `NotExtendingAnchor`, `saveLastBlock` writing nothing. `isApplied` stays the anchor alone until then.
- A prior of none is not held against the row at promotion. A storage trie replaced with too many rows to list
  leaves them on disk, and a block that first writes where one lies names no prior there, committed in place or
  kept as a layer.
- **No view lists a storage trie's rows** (data-model 3.6, 6.2 and 7.0 have `StateView.ownerRows`, outside the
  lock). Every commit, in place or a promotion, is made over the rows of the block's parent, so a replaced trie is
  listed there, from the disk, under the lock (`PathNodeStorage.storageRows`, as C3b built it). The listing through
  a view that this part first built, put right by the log of moves and laid over by the layers, had no other caller
  and is removed.
- The count of layers is capped at 32 as a stopgap (`CommitSettings.maxForwardLayers`); part 3 replaces it.
- **No layer outlives the chain that leads to it** (the reads after code, all three). A layer is kept only on the
  anchor or on layers that lead from it. When the anchor moves, by a commit in place or a promotion, and when a
  layer is dropped, every layer no chain leads to goes: the siblings of the new anchor and what stands on them, and
  what stood on the dropped layer. Left alone they would fill the cap for good, and side blocks are routine on a
  proof-of-work chain. The reverse index changes what "leads to" means: a sibling of the anchor is then reached
  through the block behind it.

**Tests.** `ForwardLayerSpec`: two datadirs are given the same blocks, one committing each in place, the other
keeping each as a layer on the layers before it. Every node of each block's state is read through the layers; then
the layers are promoted, oldest first, and after each the datadir is the other's, key for key (rows, records, their
index, the anchor, the head). The blocks replace a storage trie in one block, make it again in the next and replace
it again in the third. Run again where the replaced trie has too many rows to list, and with the bound just above
and just below the disk's rows while the layers add to them or take from them. A view read across the promotion of
its own oldest layer, and stale when the log ends; a view made while a promotion lands between its two looks; the
faults; the refusals of a layer and of a promotion; **a block that destroys a storage trie grown by 400 slots holds
what the same block holds of the small one, to the byte, reads every row of it as gone, and deletes and records
each when promoted**; the layers nothing leads to; a promotion whose write fails.

**Three reads after code (beacon, forge, vault: each APPROVE WITH CHANGES, no blocker).** Found sound: the capture
of a view (a layer is dropped after its batch settles, so a chain seen without it is seen with a moved epoch), the
batch of a promotion being the staged commit's, the lock (no path asks for it while holding it), and every flow of
a node that makes no layer being what it was. Taken: the layers nothing leads to, above; a block of a hash that is
kept, on another parent or with another state root, is refused; `stateAvailable` is the anchor alone again; a view
with no layers does not look through any.

**Two reads of the layer as it now is (beacon, forge: each APPROVE WITH CHANGES, no blocker).** Found sound: a
layer grows with what its block wrote, which gas bounds, and never with a trie it destroyed, also where the trie is
made again in the same block or in the next layer; a promotion's record, rows, tombstone and move are those of the
block committed in place (the settling overwrites the prior of every position of a replaced trie from the scan, so
what the layer names there does not matter); a view reads as before, and more exactly at the positions a replaced
trie left; under the lock a promotion does what a commit in place does, with the same bounds, and one hash for
each named prior. Taken: the checks without the disk when a layer is kept, and a layer that cannot be promoted
dropped, above; a commit in place settles the staged changes without copying them; the cases beacon named (the old
anchor's view across the promotion of a layer that removed a trie, a promotion over a row that is not there, the
root check's branches, the positions a block left as it found them).

**The mutation run**: 51 mutants over the layer, the overlay, the view, the overlay commit, the promotion and the
settling. 47 are caught. Fourteen were not at first, over the two forms of the layer, and each got a case (the
anchor promoted again, the layers after the state is destroyed, a layer's removed row and its wrong node after a
move, a block of a kept hash with another state root or on another parent, what stood on a layer that failed its
promotion, the two branches of the root check that no staged block reaches, what a layer is taken to hold). Four
change nothing that can be seen: two fast paths (a view with no layers, a block that is a layer already), the check
of a layer's parent by its layer where every layer kept is reachable, and a chain ending on a block of the anchor's
hash and another state root, which nothing can register.

**For the later parts, from the reads.** (1) *A move's priors are not exact where a block first writes over a row
an unlisted trie left behind*: the move says "no row", and a row was there. A read never comes to such a position,
and nothing lists a trie's rows through a view any more, so nothing is misled by it. (2) A layered view at rest that
meets an *absent* row answers with a missing node, which is fetched, and not with an inconsistency of its layers: to
settle before part 4 routes it. (3) `commitOverlay` can answer with a layer that a promotion has just put on disk:
part 4 does not read "layered" as "not on disk". (4) `promote` is for an adopted block, and only its documentation
says so: part 4 guards its caller. (5) A promotion that answers "was not built on the state the disk holds" has
dropped the layer: the caller of part 4 (`forkchoiceUpdated`, the head move of a proof-of-work branch) executes the
block again from its stored body before it answers (beacon). (6) Part 3's bound on a layer's bytes is set above
what the largest block can write at the highest gas limit a chain allows, Olympia's schedule included: a bound
under that would bring back, for writes, what this part removed for destroyed tries (forge). And the time the lock
is held by a promotion is measured there: the scan of each replaced trie is under it, as in a commit in place, and
an 8M-gas ETC block can replace some 1,600 (forge). (7) The differential on RocksDB (the shape of scan keys, the
resume key of a chunk), and `EphemDataSource.scanRangeLimit`, which copies the whole range for each chunk.

### C4a part 2: the reverse index (2026-11-12)

The state of a block **behind the anchor**, and of a block on **a fork**, is read through the changeset records with
no row touched. Tasks T121 to T123, and the rest of T128 and T130. Still storage and its spec: the node's readers
and its execution are given the anchor's state and no other (`PathStateStorage.view`); the new views come from
`viewOf`, which only the overlay's own code can call until part 4 changes the callers with it (beacon: a reader
handed a state behind the head today would answer where it refuses, `CheckpointExporter`'s walk would go stale, and
test mode would lose its refusal).

- **`ReverseIndex.between(anchor, fork)`** is what the state of `fork` held at every position the blocks after it
  changed: the prior of the earliest such block. It is built by a walk of the anchor's own records, by the parent
  each record names; the canonical index of blocks by number is never asked (data-model 7.0), and neither is the
  index of records by height. Each record is held to its block (its number and the state root it leaves; its hash by
  the record's key), and the walk ends on the record whose parent is exactly `fork`. A record that is pruned, is a
  tombstone, cannot be read, is too far back or would make the index too large is a state that is not held: never
  an index with a block missing from it. `fork`'s own record is not read. A record is not decoded to find that it is
  by itself more than an index may hold.
- **A view** reads its layers, then the index, then the row as its base held it. Its base is the anchor the index
  was built from, so the log of moves keeps the rows under it that anchor's, and the index, a fact about two states,
  needs no correction. **What the index gives is never taken for the disk's**: a wrong node there, or none where one
  is named, is the records' inconsistency (`LayerInconsistent`), not a missing node, which is fetched and written to
  a row that is the anchor's, and not a corrupt row. A row that is missing where neither layers nor records say
  anything is a real hole in the anchor's disk, and is said as a missing node with no position.
- **A block executed on such a view is kept as a layer like any other**: a layer holds what the block left and is
  settled against the disk when it is promoted (part 1), so nothing lists a storage trie's rows through the index.
- **`AncestorWindow`**: the blocks behind the anchor that the records lead back to, newest first. It is derived (a
  walk of the records' headers at the first need, then one step with each commit) and never stored. It says which
  forward layers stand on the anchor's chain and which are of a block that is now canonical, without a record being
  read under the lock at every move (vault). An error in it costs a view that could have been made; the view itself
  is made from the records.
- **A fork's layers are kept while the block they left the chain at is led back to**: a sibling of the new anchor
  stays readable, where part 1 dropped it. A layer of a block that is the anchor or behind it is dropped, and such a
  block is not kept as a layer (`OverlayOutcome.BehindTheAnchor`).

**The invariant part 4 and C4b build on** (beacon): *the index's view of a block equals the disk after the blocks
above it are reversed by their records, at every position*, the rows a replaced storage trie left behind among
them. Where a block first wrote over such a row its prior is "none"; a reverse deletes the row, and the index says
it was not there. So a move's priors need not be made exact (part 1's open point): an inexact "none" lies only over
a leftover row, which no state names, and a head move back must write a record's priors exactly, "none" as a
delete. A layer promoted after such a move is settled against that disk, so it lists a replaced trie by the rows
the move left: **C4b's spec promotes a fork's layer after the move back and holds its record to the block committed
in place over that disk** (beacon; nothing here moves the disk back, so nothing here can show it). The spec holds
what a view behind the anchor reads at every position of a trie, the leftover rows among them, to a second datadir
whose last blocks were reversed by their records.

**Not built, and where it goes.** No cache of decoded records and no index built from a nearer one: a handful of
whole indexes are kept by (anchor, fork) and dropped when the anchor moves; part 3 owns the budgets. The first
build of the window reads whole records under the state lock: before part 4 it is walked outside the lock and
installed under it, or bounded in bytes (beacon, vault). The specs run in memory: the RocksDB cases (a reader beside
pruning commits, a reopened datadir, a record of several MiB, hashes that begin 0x00) come before part 4.

**Tests.** `ReverseIndexSpec` (6 cases): the view 1, 2, 8 and 9 blocks back reads that block's state, every node of
it as the hash store holds it, on a chain that replaces a trie every third block and makes it again; the same after
the anchor moves on, until the log of moves ends, and from records alone after a restart; a fork of three blocks
from a block behind the head, executed on layers and read through index and layers, before and after the anchor
moves, and a second fork that replaces a storage trie and makes it again, where neither the fork point's rows nor
the disk's show through; the states that are not held; the faults; the positions of a trie behind a tombstone's successors, held to
a datadir reversed by its records; the window. `ForwardLayerSpec`'s case of the layers nothing leads to is now a
fork that outlives its fork point's records.

**Two reads before code and two after (beacon, vault), and the mutation run.** Before: an answer of the index must
never reach the disk's fault branches; the new anchor's own layer would have counted as reachable; no record is
walked under the lock at each move. After (each APPROVE WITH CHANGES,
no blocker), taken: the window is bounded by the depth a view is made across (on an archive node it grew with every
block, and a layer was accepted on a parent no view could serve); a record's encoded size is held to the cap before
it is decoded; the indexes of an anchor go when it moves; a block behind the anchor has an outcome of its own; a
hole under a layered view names no row. A third read (beacon, APPROVE WITH CHANGES, no blocker) of this part on the
layer as part 1 now has it: a fork block that replaces a trie reads right through layers, index and disk; a named
prior of a fork's layer is the row after the move back, and a leftover row is never a named prior; taken: the fork
that replaces and remakes a trie, and the promotion after a move back named as C4b's to test. 34 mutants, 32
caught; nine were not at first and each got a case. Two change nothing that can be seen: a layer of a block behind
the anchor (it is dropped when that block becomes the anchor), and a window of another anchor (there is never one).

### C4a part 3: the bounds (2026-11-12)

What the overlay keeps, and what goes when it is full (data-model 6.4). Tasks T124 and T132, and the rest of T129.
Still storage and its spec: nothing in the node keeps a layer until part 4.

- **The forward layers no branch pins are bounded** in number (`state.overlay.max-forward-layers`, 32), in bytes
  together (half of `state.overlay.max-bytes`, 128 MiB) and in bytes each (`max-layer-bytes`, 64 MiB). A layer's
  bytes are what `ForwardLayer.bytes` counts: its nodes, their keys, and an overhead for each entry.
- **One more than fits evicts layers to make room.** Only a **tip** goes, a layer no kept layer stands on: an inner
  layer would take every state above it with it. Never a pinned layer, and never one the new layer stands on. The
  eviction is worked out first and done only if the layer then fits: nothing goes for a layer that is not kept in
  the end.
- **Which tip goes first**: a fork whose fork point is the oldest block the records still lead back to (the first
  the anchor's moving on would take away in any case); then the layer least recently **used**; between layers used
  alike a fork's before one on the anchor's chain, and then the lower block. A use is an execution staged through
  the layer (`stage`, `stageOf`), a layer kept on it, and a delivery again. **A reader's view is no use**
  (`readOnly`, `throwaway`): a caller of `eth_call` does not decide what a node keeps.
- **A block that is not kept is answered with an outcome, and not a fault**: `OverlayOutcome.NotKept(TooLarge |
  NoRoom)`, beside `Kept`, `AlreadyApplied` and `BehindTheAnchor`. Nothing is wrong with the block; its state is to
  be had by executing it again. `Kept` is as of now: the next layer can evict it.
- **A branch under execution is pinned** (`PathStateStorage.withPinnedBranch(name)(pin => ...)`, and
  `commitOverlay(staged, block, Some(pin))`). Its layers are kept whatever the bounds of the others are, are never
  evicted, and are bounded together in bytes across every pinned branch (`pinned-max-bytes`, 256 MiB), each layer
  once whoever holds it. A pin takes hold of the kept layers its blocks stand on, and of a layer that is there
  already. A branch that would not fit is refused with `BranchTooLarge`, a fault to try again after and never a
  verdict on a block, and nothing of that block is kept or pinned.
- **A pin is released however the execution ends**, under the lock. Nothing is evicted by a release: the bounds
  apply to what the branch held from the next layer that is kept, pinned or not, and what it held counts as used at
  the release. So a caller that adopts a branch promotes it first and releases after. Between a release and the
  next layer that is kept, the layers no branch pins can be over their bounds by what the branch held. A branch is
  not pinned by the holder of the state lock, which the release needs.
- **The reverse indexes that are kept** are bounded together in bytes (the other half of `max-bytes`); one that is
  by itself more than that is built for its view and not kept.
- **Metrics** (data-model 11): `db.state.overlay.forward.layers` and `.bytes`, `.pinned.layers` and `.bytes`,
  `.reverse.resident` and `.bytes`, `.evictions` (each eviction told once, from a count the overlay hands over),
  and `db.state.view.retries` and `.stale` for the views that were made again or could not be made.
- **A reverse index is kept with the epoch it was built at**, and given only to a view of that epoch: one built
  across a move of the disk is never met again, also when a head move (C4b) brings the anchor back to a block it
  has been at (beacon).

**Not as the data-model has it, and why.**

- *"One layer above `max-layer-bytes` is committed in place if it extends the anchor"* (6.4): a layer is kept or not
  kept, and the store does not commit a block nobody adopted. A caller that adopts the block commits it in place
  from what it staged, whatever its size; part 4 does that.
- *`resident-reverse-blocks`* is not read: whole indexes are kept, by the anchor and the block they lead back to,
  and not decoded records. The key stays in the configuration until the measurements say what the pool should be.
- *Pinned layers are exempt from the bound of one layer too*: a branch is as large as its blocks are, and
  `pinned-max-bytes` is its bound. (ETC: a block's layer does not grow with a storage trie it destroys, part 1.)
- *An evicted layer that a view still holds is not counted.* A view holds its layers; it ends with its caller or
  with `max-view-age-moves`.
- *A pin does not keep a layer nothing leads to.* When the anchor moves and no chain leads to a pinned layer any
  more, it goes like any other: a pin holds a branch against the bounds, not a state that cannot be reached.
- `db.state.move.seconds` comes with the head move (C4b). A read that goes stale after its view was made is counted
  by what catches it, which comes with the callers (part 4).
- **T126 and T131 do not apply as the code stands**: the position store is given no decoded-node cache
  (`PathStagedBlock.prefetchReader` passes none, and `PathMptStorage` has none). They stay open, with what they ask:
  when a cache is put in front of the position store, the in-place commit evicts the hashes of the nodes it
  replaces or removes, after its write.

**For part 4** (from the consults before code, and the reads after): "kept" is a snapshot, so a caller asks for a
view and executes again when there is none; the candidates for the head are the pinned chains and the last
`forkchoiceUpdated` target; a branch is rebuilt from the stored side blocks and executed again when its layers
went. What each answer of the store means to a caller (beacon, forge): `Kept`, `AlreadyApplied` and
`BehindTheAnchor` are a block that was executed and is valid; **`NotKept` is that too**, with its state to be had
again by execution, and its header, body and weight are stored all the same; a parent's state that is not held
(`StateUnavailable` when an execution is staged or kept) is answered by executing the ancestors again from their
stored bodies, or by SYNCING or ACCEPTED, and on a proof-of-work chain by leaving the block queued. **None of these
is an invalid block, a penalty for a peer, or an entry in a cache of bad blocks.** Side blocks and payloads are
handled one at a time with the execution of a branch, so that nothing evicts the layers a branch is about to take
hold of. Blocks of the head's own chain are committed in place or pinned, and never left to the layers no branch
pins. To count there: blocks not kept, by reason; branches refused; blocks executed again because their state had
gone; the lag of the anchor behind the head.

**Two decisions part 4 could not be designed without** (raised by forge and beacon; **decided by the user,
2026-11-12**).

1. *A branch that is heavier and holds more than `pinned-max-bytes`.* `BranchTooLarge` is the same answer however
   often the branch is tried: left as the last word, such a branch is never adopted, and on a proof-of-work chain a
   node that cannot adopt the heaviest chain has left it. Forge's estimate of what 256 MiB holds: some 85 full
   blocks at today's 8M gas, some 20 at 60M, and the deep reorganisations ETC has seen were of thousands of blocks.
   **Decided: reverse-then-commit, as the rule when the cap is hit** (the fallback data-model 6.4 records). The
   branch is executed under a pin as far as the cap allows; when the cap is hit, the disk is moved to the fork
   point by the records, the executed prefix is promoted block by block, and the rest of the branch is executed
   and committed in place on it. If a later block is invalid, or the branch turns out not to outweigh the head it
   left, the disk is moved back by the records of the blocks it committed and the old branch is committed again
   from its stored blocks. Not chosen: pinned layers spilled to a scratch column family; the cap as the limit.
   What this asks of the slices: the head move is C4b's, so the fallback lands with it, and until then a branch
   above the cap is deferred and never judged; the move back after a failed adoption is in the crash matrix (a
   crash between the move to the fork point and the last commit leaves a head the node must be able to explain
   from its records and stored blocks); only a branch whose headers prove more weight than the head starts it,
   so what it costs to make a node do this is the work of that branch.
2. *More than 32 payloads in a row ahead of a `forkchoiceUpdated`.* A pin lasts for one call, so the payloads a
   consensus client sends ahead of its choice are layers no branch pins: the 33rd on one chain finds no room (every
   kept layer is under it), and the 34th finds its parent's state not held. **Decided: the bounds stay as they
   are, and the cost is execution again**: at the `forkchoiceUpdated`, the blocks of the chosen chain whose state
   is not held are executed again from their stored bodies, in order, and committed. Not chosen: a higher bound
   for a chain that follows a consensus client; a pin that outlives a call. To look at again with M1's bytes per
   block on Sepolia and Platåberget: the bound of 128 MiB on the layers' bytes would bind before a higher count of
   layers did.

**Two reads after code (beacon, forge: each APPROVE WITH CHANGES; nothing blocks this part).** Found sound: nothing
that is not pinned can evict or block a pinned branch; a refusal with `BranchTooLarge` pins and keeps nothing; a
view made while tips are evicted is refused or right, never wrong (only tips go, a layer's content never changes,
and a child is registered under the lock that evicts); dropping a pinned layer nothing leads to. Taken: a branch is
not pinned under the state lock, and a failed release does not hide the execution's failure; a pinned layer that is
kept holds the others to their bounds; evictions told once each; an index kept with its epoch; a bound of one layer
above the bound of all refused when the store is made; the comments of the overlay's keys. Left, and said: a use is
marked with one write and no lock, so two at once can leave the older stamp (the order of eviction bends, nothing
else); a branch whose execution failed counts as used when it is released like one that was not adopted (forge
would not stamp it); a legitimate ETC block at a 60M gas target can be above 64 MiB and is then `NotKept` unless a
branch pins it, which is why `NotKept` is never a verdict.

**Tests.** `OverlayBudgetSpec` (16 cases; every block changes one account, so a bound in bytes is a count of
layers): the count bound, with the one layer that could go being the one the new layer stands on; nothing going for
a block that finds no room in the end, where some layers could have gone; a refused branch taking hold of nothing;
a branch refused a pin under the state lock; what an earlier branch left held to the bounds by the next; a view of an
evicted layer that goes on reading, and the block kept again when it is executed again; the order of use (a
reader's view, an execution staged, a delivery again, a layer kept on one); the bytes together and each, to the
byte; a pinned branch of six beside a bound of two, others coming and going beside it, and the bounds applied after
the release; `BranchTooLarge` at one layer more than fits, with nothing kept of it; a pin taking hold of the layers
under its block, two pins on one layer counted once; the release when the execution throws, and a branch promoted
before it is released; a fork at the floor going first though it was last used; the order between layers used
alike; what the observer is told, and the views made again and given up; the index pool. `StateMetricsSpec` and
`StateConfigSpec` hold the metrics and the settings the configuration gives.

**The mutation run**: 54 mutants over the bounds, the eviction and its order, the pins, the release, the pool of
indexes, what the observer is told and the settings. 53 are caught. Two were not at first, and each got a case: a
layer kept on a layer being a use of it (an execution is staged through the layer first, which is a use already, so
the case stages on another layer in between), and a released branch counting as used (the same layers were left
whichever went first, so the case evicts one layer and no more). One changes nothing a test of one thread can see:
an index given to a view of a later epoch, which is there for a builder that crosses a move of the disk.

### C4a part 3c: the ordering, the stress and M3 (2026-11-12)

Tests and a measurement; no change to the store. Tasks T125, T127 and T134.

- **`EpochOrderingSpec` (T125)**: the one ordering the isolation of a view rests on (data-model 6.3.3). A data source
  that stops before and after its write makes the steps: one thread, the same interleaving every run. *The writer*:
  when its batch is about to be written the epoch is odd, the move is in the log with the new anchor, and nothing
  is written; when the batch has returned the move is in flight still, and says of every row the batch changed
  what it held, and of no row a thing that was not so (the rows before and after, compared). For a commit in place
  and for a promotion. *A reader*: the anchor's view, a view through a layer, a view behind the anchor and a view
  of a fork, each made before the move, read every node of their states before the batch, after it while the clock
  is not settled, and after. *The fallback* of 6.3.3 is another clock under the same view: a view asks its clock
  three things, so a store whose writers exclude its readers by a reader-writer lock is a `StateClock` of a few
  lines, shown reading. The memory model's own gap (no happens-before across the native write) is as the
  data-model has it: documented, and not something a test of one thread can close.
- **`OverlayStressSpec` (T127)**: ten thousand steps in an order a seed decides: blocks committed in place (with
  readers reading before and after the batch, inside the write), blocks kept on the anchor, on layers, behind the
  anchor and on forks, branches of two to four blocks under a pin while other blocks arrive, promotions, drops,
  views opened and read. The overlay is small (six layers, 40 KB, 12 blocks of records), so its bounds are met all
  the time. **Every read that succeeds is right, and every failure is `StaleStateViewException`,
  `StateUnavailable` or `BranchTooLarge`**: never a wrong node, a missing one, or `LayerInconsistent`. A read is of
  nodes by their hash, and of rows: at positions of the view's state and of another state, where a view must hold
  nothing if its own state has no row there. Every block is executed on a hash store too and the two state roots
  are held to each other, so an execution through layers and records is checked as it is made. Two invariants are
  held as the run goes: every kept layer has a view while the disk is at rest, and what a branch holds stays while
  it is under execution. At the end the disk is its anchor's state, row for row. About five seconds.
- **The mutation run for the two**: 12 mutants of isolation (the batch written before the move is said, a move that
  says nothing of its rows, a view that reads the rows as they are, the wrong move or the wrong layer answering, a
  row of a replaced trie showing through, an inner or a pinned layer evicted, a promotion that scans nothing, the
  layers nothing leads to left). Ten are caught. Six were not at first: the stress read only nodes that exist and
  took every refusal for a right answer, so it was given the reads of rows and the two invariants. Two change
  nothing one thread can see: a view that is not made again when the disk moves while it is made (`viewOf`; the
  same rule in `view` has its case in `ForwardLayerSpec`), and an index kept across a move of the anchor (it is
  kept with its epoch and never given again).
- **M3** (`baselines/m3-layer-heap.md`, `LayerHeapBenchmark`): what a layer holds on the heap against what the
  overlay counts. The count is no less than the heap in every shape measured, by 9 % to 2.2 times, so a bound on the
  count is a bound on the heap and the defaults stay. Measured by a heap difference over hundreds of layers, not by
  JOL, which is not a dependency of the build. Setting the defaults from real blocks waits for M1 and M2.

### C4a part 4: the node reads behind its head, first for SNAP (2026-11-12)

**What part 4 is.** The record of part 1 said the degrade of a staged commit to an overlay commit "lands with its
callers in part 4". Its callers are not C4a's: a block that is executed and not adopted comes from a `newPayload`
(C4d) or from the blocks of a reorganisation (C4e), and what moves the head to it is C4b. So the degrade lands with
the first of those, and C4a ends where its checkpoint says: *non-head state is readable and bounded; nothing moves
the head yet.* Part 4 is the readers: the state served to a peer by its root (this), and the readers of a block
behind the head over JSON-RPC (next).

- **`BlockchainReader.stateForRoot(root)` under the guard** looks the root up among the canonical blocks from the
  block the disk holds back to 128 behind the best block, and hands out that block's state: the disk's own rows
  for the block it holds, and for a block behind it a view through the changeset records
  (`PathStateStorage.readOnlyBehind`). Where two blocks of the window have one state root (an empty block after
  the merge) the newer is read: the same state through fewer records. The roots of a window are kept for the next
  request, by the block the disk holds and the best block's number, and made again when either moves.
- **Not served**: a root of no canonical block of the window; a root whose block the records no longer lead back
  to (past the retention floor, or behind a record that lists nothing); and **an executed block kept beside the
  chain, ever**, also where the index by number names it (a side branch's headers written under the chain):
  `readOnlyBehind` gives the state of a block of the anchor's own chain and of no other.
- **One rule for the three requests.** Under the guard the state that is handed out for a root is the rule, for
  `GetAccountRange`, `GetStorageRanges` and `GetTrieNodes` alike. With the guard off nothing changes: the
  read-only store whatever the root, and the 128-block check on `GetAccountRange` alone.
- **A state that goes away while a request is served** (a view the disk moved past, a state no longer held) is
  answered with nothing, as a root that is not served is, and logged at debug: nothing is wrong with the node.
- **SNAP stays unadvertised under the guard, and T130's "advertise again" moves to the slice in which the node
  follows the head (C4d, C4e)** (herald). A node on the guard does not sync from the network yet, so the roots a
  syncing peer asks about, which are near the network's head, are none of its own: every answer would be empty,
  which is what the user's decision of 2026-11-11 was made to avoid. The window is served to whoever asks (the
  handlers do not wait for the capability). The refusal of `do-snap-sync` under the guard stays, and says why: the
  hand-off of a SNAP sync onto the unified layout is C5a.

**Tests.** `ServedRootWindowSpec` (5 cases, on `CanonicalChainOnPositions`: a chain as a node holds one, with its
headers, their index, the records and the best block): every block of a chain served and read node for node
against a hash store; the window's edge to the block, and its move with the best block; a root past the records
not served, and a root two blocks share read at the newer when the older is one record too far; three executed
blocks beside the chain not served, one of them named by the index; a state handed out earlier stale after as many
moves as a view outlives and never wrong, and the new root served when the disk catches up with the best block.
`SnapServingActorSpec`: each of the three requests for each root of a chain whose storage trie changes in every
block, equal to the answer a hash store gives, proofs included; and nothing for a root of no canonical block.

**The mutation run**: 9 mutants over the lookup, the window, the kept roots and the seam; all caught, one after a
case was added (the roots kept when the disk moves to another block while the best block's number stands).

**Two reads (herald).** Before code, AGREE WITH CHANGES: the same window for the three requests is what geth,
Nethermind and Besu do (from memory, not from their code: to check before it is relied on elsewhere), and an empty
answer with the request's id is the answer for a root that is not held, never a disconnect. After code, APPROVE
WITH CHANGES, nothing blocking: with the guard off nothing differs (no `StateFault` can be thrown there); the
window is the old rule's and the fresh-root cache's, to the block; the kept roots can miss a state and cannot hand
out a wrong one, since the view is made of the exact block and only of a block the records lead back to. Taken:
the case of a best block's number behind the disk. **Checked in code by herald**: contract code is not pruned with
retention (nothing in the position store touches the code store), so a code hash of a window state is there for
`GetByteCodes`; and fukuii's own SNAP client marks a peer that answers empty five times running as unusable for
good (`AccountPeerDispatch`), which is the behaviour not advertising avoids. `ServedRootWindowSpec` runs in the
essential tier (no actors); the actor spec's cases are `SyncTest`, as that spec's are.

### C4a part 4b-1: what a reader behind the head may cost the node (2026-11-12)

The readers that come next are anyone who can reach the node: a peer (part 4, above) and a caller of the JSON-RPC
(part 4b-2). Two consults before code (conduit, beacon; both AGREE WITH CHANGES) named what the store had to be
first, and this is that: no change to what any caller reads.

- **The look at the blocks behind the anchor is made by a reader with no lock held.** It reads the header of a
  record for each block behind the anchor (up to the reach), and under the state lock that was every commit held
  up behind one read after a start. A reader builds it at the epoch its view observed and it is kept only if the
  disk did not move meanwhile; otherwise the view is made again. A writer builds it under the lock it holds
  anyway.
- **An index is built once for those who ask for it together.** Who asks for an index that is being built waits
  for it and is given it, kept or not, and is told what the builder was told when the records give none: no second
  walk of the same records, no second copy of the same bytes.
- **The readers of one kind build two at once**, a peer and a caller of the JSON-RPC each their own
  (`PathStateStorage.Reader`): a third is told the state is not available right now and to ask again
  (`StateUnavailable`), and counted. One that waits for a build in flight needs no such leave. **An execution is
  held to no limit.** The number is a constant until it is measured (M5).
- **The indexes that are kept are an execution's or a reader's** (`ReverseIndexCache`, in place of four that were
  all dropped when a fifth came): four of each, the least recently used going first, within the pool's bytes. A
  reader's makes room only among readers'; an execution's takes a reader's place when it must, and takes over a
  reader's index it uses. An index of an epoch that is past goes when a later one is kept. So a sweep of reads
  over old blocks costs the readers their own indexes and nothing else.
- **A view is not made by the holder of the state lock** (`IllegalStateException`): it may wait for records to be
  read.
- **How far behind the anchor a view reaches is the configuration's**: `state.retention.max-blocks` in the basic
  mode (as far as records are kept), `state.archive.view-reach` in the archive mode. It was 1024 whatever the
  configuration said. **In the archive mode this bounds an execution on a fork point too**: a head move across
  more than `view-reach` blocks must not go through a view (C4b: the move walks the records itself).
- Metrics: `db.state.overlay.reverse.builds`, `.build.bytes`, `.refused`.

**What this does not bound, and 4b-2 must (beacon W1).** The pool and the builds in flight are bounded. An index
that is not kept, or no longer, lives as long as the views that read through it, up to 64 MiB each: how many reads
behind the head run at once is for the layer that serves them to bound. SNAP serving answers one request at a
time; the JSON-RPC does not, and 4b-2 bounds it at its seam. Also for 4b-2: a refusal may reach the caller as a
stale view when the disk moved meanwhile, and both mean "ask again".

**Tests.** `ReverseIndexCacheSpec` (6 cases): found by its two blocks and its epoch and nothing else; each kind's
few and their order of going; a reader never at the cost of an execution, by count and by bytes; an execution's
three ways of making room; the take-over; an index above the pool. `ReaderIndexBuildSpec` (11 cases, threads
stopped inside the data source's read by latches, no sleep): the look made with the lock free (a writer takes it
inside every record read); the view made again when the disk moves during the look, and during the index build;
one build for a reader, a second reader and an execution that ask together, with room for the index and with
none; the failure told to who waited without a second walk; two builds per kind, the third refused, the other
kind and an execution not; a build that failed gives its leave back; a sweep of readers leaves the execution's
index; the reach; no view under the lock. `StateConfigSpec`, `StateMetricsSpec`: the reach by mode, the counters.

**The mutation run**, twice: 27 mutants before beacon's read (26 caught, one did not compile), and 32 over the
code as it is after it: the lock around the look, a look kept across a move, each caller building, a waiter not
given the index or the reason, the take-over by an execution that waited, the limit lifted, shared between the
kinds, put on an execution, or one more or less, a leave not given back at all or only after a failure, a build
left behind or never ended, the cache's seven rules of who goes and what is found, the past epoch's index kept or
a late one taken, the reach by mode, the guard against the lock's holder, and a peer served as a caller. All
caught: one after it was written again so that it compiled, and one after a case was added (a peer served from the
peers' own builds, in `ServedRootWindowSpec`). The run also showed one of the new cases to depend on the order two
threads came to wait in: it now starts the second after the first is counted.

**Read after code (beacon): APPROVE WITH CHANGES, nothing that hands out a wrong state.** Verified by beacon in
code: every change of the anchor moves the epoch, so a look or an index built across a move is never used; the
window's and the walk's bounds agree with the retention floor to the block. Taken: waiters given the result
directly (they were given it through the kept indexes only, so an index that was not kept was built again by each);
a kind of reader each with its own builds; the guard against the lock's holder; the take-over made one at a time
with the keeps; indexes of a past epoch dropped; the tests told apart from an accident of interleaving (a count of
who waits, in place of a thread's state). Recorded for 4b-2: the bound on reads in flight. Not tested, because
nothing stops a thread there: a caller that waited on a reader that was refused asks again.

### C4a part 4b-2: the JSON-RPC reads a block behind the head (2026-11-12)

The readers behind the JSON-RPC and GraphQL now read the state of a canonical block behind the head, within the
reach of the changeset records, and the reads behind the head that are in flight are counted and bounded. With the
guard off nothing changes.

- **A read behind the head is made in a scope** (`ReadScope`, the calling thread's). What is made of the records
  for such a state lives as long as anything reads through it, and nothing in a storage says when its reader is
  done; so the reader says it. `BlockchainReader.stateAt` and `scratchStateAt` hand out the state of the block the
  disk holds to any caller, as ever, and the state of a block behind it only to a caller inside a scope
  (`PathStateStorage.readOnlyInScope`, `throwawayInScope`). **A caller in no scope reads the head and no other
  block**: that is every caller that was not looked at for this slice, and the node's own (the transaction pool,
  the Engine API's `executableAtParent`, the exporter, test mode, the MCP tools, `personal_`).
- **Four reads behind the head in flight, and a fifth is refused** (`maxReadsBehindHead`, a constant until M5): a
  scope is counted once for each block it reads behind the head, at the view's own look at the block the disk
  holds and before a record is read for it, and counted no more when it ends. A refusal and not a queue, as for
  the builds: the caller is told at once and bears the retry, and nothing a caller asks for is waited for by
  anyone else. With part 4b-1 this bounds what readers hold: the kept indexes within their pool, two builds per
  kind of reader, and four reads in flight of at most `maxReverseIndexBytes` each. (This closes what part 4b-1
  left open.)
- **Anchor chain only.** A scoped read is of the block the disk holds or of a block behind it on its own chain,
  never of an executed block kept beside it, whether it is named by number or by hash. Reads of such a block by
  its hash, with EIP-1898's `requireCanonical` and `BLOCKHASH` by the block's own ancestry, are C4d's.
- **Where the scope is.** `StateRead.at`, `scratchAt`, `retrying` and `attempting` run each attempt in one
  (`eth_getBalance`, `getTransactionCount`, `getCode`, `getStorageAt`, `getProof`). A method that executes on a
  state wraps the synchronous body in which it takes the state and reads through it (`StateRead.reading`):
  `eth_call`, `estimateGas`, `createAccessList`, `simulateV1`, the `debug_trace*` and `trace_*` methods. **A range
  takes one scope for each block** (`debug_traceChain`, `trace_filter`): a block's state is held for that block
  alone, so a range of any length holds one read at a time. GraphQL's `code`, `storage`, `balance` and nonce
  fields read through `StateRead.attempting`, which also gives them the retry on a state that moved.
- **Three errors, none of them an execution error** (code 3 is what a client takes for a revert): a state that is
  not held is `-32000 missing trie node: the state of that block is not held` (go-ethereum's words for a pruned
  state); one that is not handed out right now is `-32005 ... ask again`; one that moved under every view of it is
  `-32005 ... ask again`. They were all the error for a missing state node. Only under the guard: no `StateFault`
  is thrown with it off.

**Tests.** `ReadScopeSpec` (13): the head with no scope and no count; behind with no scope refused at no cost;
counted once a block while the scope lasts; given back on an exception and with the outer scope; a third reader
of two refused before a record is walked, a peer and an execution not; counted when the disk moves on under a
scope; a scratch execution behind the head writes nothing; a scope is its thread's. `RpcReadersBehindHeadSpec`
(12): **the rpc-compat chain imported on the unified layout from its genesis**, and every reader asked about the
blocks 1, 2, 8, 20 and 40 behind its head and compared, as JSON, with a node that imported the same chain on the
hash store: balance, nonce, code and storage of every genesis account; `eth_getProof` by number and by hash;
`eth_call`, `estimateGas` and `createAccessList`; the replay of a block's transactions on its parent's state, the
head's too; a range of twelve blocks one scope a block; `debug_traceBlockByNumber`, `debug_traceTransaction`, `trace_block`,
`debug_traceChain` and `trace_filter` through the services, with transactions; GraphQL's account fields, `call`
and `estimateGas`; a fifth request told to ask again over JSON-RPC and over GraphQL while four are in flight; the
fifth block of one scope refused for good; `eth_simulateV1` on a block behind. `StateReadRetrySpec`: the three errors, the scope of a read and of a method's body, and that no
later stage of the `IO` is in it.

**The mutation run**: 37 mutants, 19 before the reads after code and 18 over what they changed. A scope that
gives nothing back, that ends inside another, that is counted for every state it takes, or that stays on its
thread; a caller in no scope let through; the count not taken, or not at the view's look at the disk; no bound, a
leave not given back, the bound one more; a refusal that does not say to ask again, and one that says so to a
scope that holds every leave itself; the seam handing out the old doors, or a state that takes no writes to an
execution; a read through `StateRead` in no scope, and a method's body; each of the three errors said as another;
each wrapped method in no scope (`eth_call`, `estimateGas`, `createAccessList`, `simulateV1`, four of the tracing
methods), a range read in no scope and a range read in one; a GraphQL field in no scope, one that gives a call's
default for a state that was not read, one that does not run again; the tracing services under the node's rules
whatever chain they serve; a failure to give back hiding the read's own; a peer counted as a caller. All caught.
The two range mutants and the tracing ones were missed until the tracing services took the chain's rules as a
parameter: on the test chain they had traced no transaction.

**Reads after code: conduit and beacon, both APPROVE WITH CHANGES, nothing blocking.** Verified by them in code:
nothing that reads through a state leaves a wrapped body (no lazy collection, no later stage of an `IO`); no
nesting that piles up counts; a `StateFault` reaches the controllers' backstop from every wrapped method; SNAP
serving holds a state for one request (a typed actor, synchronous handlers); the refusal cannot be swallowed by
the view's own retry; a scratch execution behind the head writes no row, keeps no layer and repairs nothing.
Taken: GraphQL's `call` and `estimateGas` answer a state that cannot be read with an error of the field and run
again when it moved (an estimate of 0 was the default they would have given); the tracing services take the
chain's rules, which made the conformance spec trace real transactions through them; a scope that holds every
leave itself is refused for good; the doors behind the anchor that count nothing are the package's, with one for
a peer, so that no later caller goes round the count; a failure to give back goes with the read's own failure;
the invariant a count rests on is written where the scope is (**a scope keeps no more than one world of a block
within reach**: it is counted once for a block however often it takes it, and after a move of the disk each
taking is an index made anew). **The bound as built** (beacon): the kept pool, plus one index for each read in
flight and one for a peer, each at most `maxReverseIndexBytes`; twice that for the moment a replay holds the
last world and the next. Follow-ups in the backlog: the head-only callers read in a scope, for the one block the
disk may move between their lookup and their read (B-238); `executableAtParent` through the proposer's door when
layers return (B-239, C4d); `IO.blocking` for the bodies that run for seconds (B-240).

### C4b: the move core, as it is to be built (2026-11-13)

Two consults before code (forge for ETC, beacon for ETH; both AGREE WITH CHANGES) changed the shape of the slice.
What follows is the plan C4b is built to; where it differs from data-model 7.0 and 7.3 and from tasks.md, this
is the later word, and the tasks are annotated.

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
  record lists the wrong rows, and a later move back by that record writes a wrong state; ETC has no EIP-6780, so
  a replaced storage trie is routine there. Every reversed entry's `next` and every forwarded record's `prior`
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

**The parts.** Part 0: a fault of the state as a way for a batch to end (below). Part 1: the plan, pure, with the
refusals. Part 2: the move, with the split of `BlockchainWriter` and the one-batch test (T137 and T147 move here
from the callers). Part 3: the callers that exist on the guard (`test_rewindToBlock`, the index writers), and the
table of pointer writers as a test, with the rows the audit missed (forge: the SYNC-FORK resolver's
`discardBlocksAfter`, which steps the best block down one batch at a time under no lock;
`PivotAnchor.reanchor`; `repairBestBlockNumberMapping`). Part 4: the reconciliation at start, in place of
`fixDatabase` and `repairBestBlockMapping` on the unified layout (as built, below: they trust the best block or the
index, and would undo it).

**Recorded and not decided here.** A legal reorganisation deeper than 512 blocks during a time without finality
is refused and escalated (retention's bound; an accepted risk, to be stated in the operator's notes).
`recovery.max-blocks` is the history, 64, against 512 retained on ETC: a lost side chain longer than that is not
executed again but escalated (C4e).

#### C4b part 0: a fault of this node's state ends a batch as a result of its own (2026-11-13)

Forge found this live on the guard as it stands: a `StateFault` met while a batch is imported (the state a block
is to be executed on is not the one the disk holds, or is no longer; a view went stale) was thrown out of
`BlockExecution`, past the blocks of the batch that were already adopted, and failed the import as an exception.
With the head able to move under a batch it is an ordinary event, so it comes first.

- **`BlockExecutionError.StateFaulted(fault)`** ends a batch as an error does and is no error of the block: the
  blocks before it are adopted, the block it was met at wrote nothing, and neither it nor the blocks after it
  were judged. It travels in the result the batch executor's callers already take; `ConsensusImpl` takes it out
  once (`BlockExecution.BatchOutcome.of`), before any arm that judges a block by its error.
- **`ConsensusStateFault(fault, imported, blocksToEnqueue)`**, and from the adapter
  **`BlockImportStateFault(fault, imported)`**: a result of its own and no failed import, so that every place
  that tells the results apart has to say what it does with it. The blocks that were not executed are queued
  again and none is dropped from the queue; no block is reported invalid; the importer counts the adopted prefix,
  fetches the rest again from the first that was not executed, and blames no peer.
- **`saveLastBlock` writes nothing on the unified layout** (T149): there the pointer moves with the state, in
  the batch that commits each block. Written again after a batch, under no lock, it would put the pointer back
  on a block whose state the disk no longer holds.
- **Not here**: the block-by-hash-only records of a block that is executed and not adopted (they come with the
  overlay commit's first caller, C4d or C4e); what the node does when the fault keeps coming (the escalation of
  Part 2, D).

**Tests.** `StateFaultInABatchSpec`: the rpc-compat chain on the unified layout through the node's own
consensus; a batch of five whose third block is executed, and before it is committed another writer makes a
sibling of it the head. The first two are adopted with their records; the third is answered as a state fault
with the last three handed back; nothing is reported invalid; the best block is the block the disk holds, which
is the other writer's; the last two were never stored. The same through the adapter, with the blocks still
queued; the same with the head moved before the third block is executed; and a view gone stale under a block,
which ends the batch with that very fault and the blocks before it adopted. `ConsensusImplSpec`: the fault
mid-batch, at the first block, and in a reorganisation, each with a reporter that stays silent.
`RegularSyncSpec`: the block is tried three times at the importer's usual pace, then spaced out; no peer is
blacklisted.

**The mutation run**: 18 mutants; 15 caught. The fault thrown out of a batch; judged as an error of the block;
the pointer written after a batch on the unified layout; the blocks not handed back, the adopted ones not told,
the handing back starting one block late; a reorganisation answered as done; the adapter answering a failed
import, or not queueing the blocks again; the importer blaming the peer, or counting a strike; test mode
answering an invalid block; the tries never spaced out, spaced out at the usual pace, or from the second. **Three
missed, and why**: the `testing_` namespace's rethrow and test mode's answer for a mined block have no spec that
meets a state fault there; and a pick of blocks sent at once after the third fault is no change of behaviour (the
fetcher was emptied from that block on before the pick reaches it, and answers a pick it cannot serve with
nothing: the mutant is equivalent). The first case of the spaced-out tries could not tell them from ordinary ones (its
window was shorter than the importer's usual pace) and was made to.

**Read after code (forge): APPROVE WITH CHANGES, nothing critical.** Verified in code: every way a `StateFault`
can leave a batch is inside the catch (staging, the refusal of a block that is not adopted, a stale view, the
commit); no arm of `ConsensusImpl` that judges a block can be handed one; `saveLastBlock` writes the same row
the block's own batch wrote, so writing nothing is right at each of its calls; the fetcher's rewind without a
blacklist blames nobody; the queue cannot grow without bound; with the guard off nothing changes, the old matches
being moved and not altered. Taken: **the tries are spaced out** (a fault that stays, such as a branch this
layout cannot execute yet, was met again at every round trip of the fetcher for good: from the third in a row on
one block the next pick waits out the importer's backoff, said once at ERROR, and nothing is escalated); the
fault met where a block's state is taken as well as where it is committed, and another kind of fault through
the real catch, as cases; test mode's parity; the reorganisation's answer after an executed prefix, which is
not met as the code stands and is said so in the code and in B-242 (it must tell the settled prefix and the old
branch's blocks when a branch is executed on layers, C4e).

#### C4b part 1: the plan of a move, and why one is refused (2026-11-13)

**What it is.** `HeadMovePlan.of(anchor, epoch, target, sources, limits)` says how the disk's state gets from the
block it holds to another, and changes nothing: the blocks to reverse, newest first, by the headers of the
anchor's own changeset records; the blocks to apply, oldest first, by the target's headers; the fork, which is the
highest block the two chains name by one hash. It is given no index of blocks by number and no chain weight, so it
can ask neither (T136's two cases of an index ahead of the anchor and behind it are met by what the plan is made
from; the caller's `link`, built from headers by hash, is part 3's to test with a reader whose index throws). A
block to apply is taken from its record where it has a complete one that leads from its parent, since a record is
exact and needs no settling, and from its layer otherwise. `PathStateStorage.planMove(target, link)` makes it from
what the store keeps, off the lock. Nothing calls it yet: the move is part 2.

**Three answers.** `Planned`; `Refused(reason)`, which executing blocks again does not change; `NotHeld(heldTo)`,
where a block on the way up has neither a record nor a layer and `heldTo` is the highest block a move could be made
to as things are (the caller executes from there), or `None` where no fork was found at all (the target's headers
do not lead here, or it lies further above the head than a move reaches: a sync's matter, not re-execution's).

**The refusals, asked for each block to reverse in this order**, so that the answer does not depend on what happens
to have been pruned:

1. `Finalized`: the block is the finalized one, by number **and hash**. A block of another hash at that height is
   not it: the head is then on a branch the consensus client left, and the way back is open (beacon: a number alone
   would refuse that move, and `Finalized` is the one refusal that does not escalate, so the node would be stuck).
2. `Pivot`: block 0, or a block at or below `state.origin`, the block this datadir's state began at (a new
   AppState key, written by the genesis commit; SNAP finalization and checkpoint import write it with their anchor,
   C5a and E.1, B-243). A datadir that does not say where its state began answers `Deep` there.
3. `Deep` by policy: more blocks than `state.reorg.max-depth` allows. In the archive mode with `floor`, which keeps
   every record, that is what one plan holds (65,536 blocks), unless a depth is configured.
4. By the record: gone below `state.changeset.pruned-to` is `Deep` (the physical bound: what retention left, which
   is not always what its formula says); gone at or above that mark, unreadable, or not its block's, is `Corrupt`,
   a refusal of its own that is never said as `Deep`; a tombstone is `Incomplete`.

A fork exactly at the finalized block, at the origin, or at the block below the lowest record is planned: that
block is not reversed and its record is never read.

**Recorded, with the slice that owns it.**
- **A depth met above the finalized block is said as `Deep`** even when the fork would have lain below it: the walk
  never came to it. With retention capped at 512 and more than that without finality, a head that breaks finality
  would be escalated and followed. That a head descends from the finalized block is asked of its headers **before
  a move is planned** (C4d, at the `forkchoiceUpdated`: `-38002`), and not inside a plan of the state (beacon).
- **`Corrupt` fails closed** at the Engine API (C4d): SYNCING, no in-place writer goes on, ERROR, then the
  escalation route; never INVALID, never as `Deep`.
- **`NotHeld(None)` is SYNCING and a sync**, not re-execution (C4d, C4e).
- `ReorgTooDeep` and `StateUnavailable` are thrown from these answers by the move (part 2), which also carries
  `heldTo`.

**The mutation run**: 44 mutants, all caught. One was missed at first (a genesis committed to a datadir that
says where a state began) and got a case. The walk (a move to the anchor walked; a target's header of another
block; a meeting block its header does not describe; the anchor's chain reversed whatever the target's height;
the reverse set out of order; a parent given its child's state root); the bound above the head, on both sides of
it; each refusal, its boundary and its place in the order (the finalized block reversed, taken by its number
alone, not looked at, or asked after the depth; the origin and block 0; one block more than the policy; a record
gone at the mark itself, where nothing was pruned, above the mark; a record of another number or state root; a
tombstone taken for a record); the target's headers (under another hash, of another height); the sources (a
layer preferred to a record; a record on another parent or another state; a layer on another parent; what is held
said to end at the fork; a record written for a block that has one); the chunks; and the store (no origin written
at genesis, or left behind when the state is destroyed; a plan without the finalized block, the policy, the mark
or the origin; the depth taken without the finalized block; the bounds wrapping round; a plan given out though
the state moved under it, or made again one time fewer; the archive's cap). Twenty-one of them were run before
the reads' changes and not again, the code they break being unchanged.

**Reads after code (forge, beacon): both APPROVE WITH CHANGES, nothing critical.** Verified by them in code: the
fork and every parent named are right (each parent comes from a record and is held to its header at the next step
or at the meeting point; a forward parent is held by hash and height); both walks end on hostile headers; with the
guard off nothing changes (`commitGenesis` and the new key are reached only on the unified layout). Taken:
- **The bound on the target's chain no longer overrides the refusals** (forge): it counted every header walked, so
  a deep move answered "not held", which sends the caller to execute blocks again for nothing, and in the archive
  mode capped a re-adoption at about a thousand blocks. It now bounds how far **above** the head a target lies;
  below that each header walked is a block reversed.
- **The finalized block by hash** (beacon), as above.
- **A plan is made again if the state moved while it was made** (beacon): a commit prunes records and moves the
  mark in one batch, and a walk that began before it would call the records corrupt. No answer is given out that
  the epoch moved under; after a few tries, `NotHeld(None)`.
- **A hard cap where nothing else bounds a reverse** (both), as above; a deep move in the archive mode that streams
  its records is part 2's.
- **Corruption on the forward side is said at ERROR** (forge): the block can still be applied from its layer or
  executed again, and a record is then written over the bad one, so it is said where it is last seen.
- The bounds are added without wrapping round; the detail of a chain that shares no block with this node's.

#### C4b part 2a: the move of the head in the store (2026-11-13)

**What it is.** `PathStateStorage.moveHead(target, link, extraFor)` plans the move (part 1) and writes it: as one
batch, or as a chain of batches of at most `state.move.max-blocks-per-batch` blocks. Each batch holds the rows, the
records of the blocks that had none, the anchor, and **what the caller gives for it** (`extraFor`, asked for
outside the state lock, once for each batch and again after a re-plan): its index of blocks by number and its
best-block pointer. So after any batch, and after a crash between two, the anchor and the best block are one
block, on the way from the old head down to the fork and up to the target. Nothing in the node calls it yet; the
callers that exist on the guard are part 3, and `BlockchainWriter`'s rows part 2b.

**A batch is built against a virtual disk** (`MoveBatch`): the disk's rows under what the batch has done so far.
A block reversed must find at every position what its record says it left; a record applied again must find what
it was applied over (not where it names no prior, as a layer's is not: the rows a trie left behind when it had
too many to list). A block applied from its layer is settled against the virtual rows of the tries it replaces,
with the bounds of an in-place commit held on **those** rows and not on the disk's: an owner the batch has shrunk
is listed as a commit on its parent's state would list it, and one it has grown is not (forge, beacon: else the
record of a block, and whether its old rows stay, would depend on how a move was cut into batches). The account
trie's root on the virtual disk must be the state root of the block the batch ends on. All of that is before
anything is written; a batch that fails it writes nothing, and the batches before it stand. What is written is
each position whose final value differs from the disk's, and a reader of the old state is given back the disk's
row for exactly those.

**A promotion is that batch for one block.** `promote` goes through the same builder; the 411 specs of the store
that existed before passed unchanged.

**One move at a time, and no commit between two batches.** A move holds a lock of its own from its plan to its
last batch and says it is under way (`moveInProgress`, with `movesStarted` for who builds on the head and must
know a move began meanwhile). A writer that adopts a block looks at that under the state lock; if a move is under
way it lets the state lock go, waits for the move's end and asks again. It does not take the move's lock
otherwise: writers queue where they always did, in the fair state lock. A commit that got in just before a move
began makes the move's first batch find another head, and the move is planned again (three times at most); a
head moved under a later batch stops the move where it is.

**Retention.** A batch that only applies blocks prunes as the commit of a block does, and never a height it
records itself (the index of a height is read from the disk, which does not hold what the batch writes there). A
batch that reverses a block prunes nothing: the records above the fork are the way back.

**A store that wrote other bytes than it was given fails closed.** Up to 64 of the rows a batch wrote are read
back. A mismatch cannot come from anything but the store: what is kept in memory follows the disk first, and then
nothing is written in place and **no state is given out**, by a view or an overlay commit, until the node is
started again (beacon: a block executed on such a store could be answered INVALID, and a consensus client keeps
that verdict).

**Left for part 2b** (recorded by the reads): a bound on a batch by bytes and a barrier move above it; the layers
of a plan pinned for the whole move (an eviction between two batches stops the move, which fails closed);
`max-view-age-moves` against batches of one block; the hold that keeps retention off the records while a
reverse-then-commit attempt is unsettled; `BlockchainWriter` split into a builder of its rows and a commit, with
the one-batch test (T137, T147); the metrics. For C4e: the miner reads `movesStarted` before and after it
builds, with the flag (forge). `moveHead` waits and may be long: its callers run it where a thread may block
(beacon).

**The mutation run**: 50 mutants; 48 caught, 2 equivalent. The batch builder (a reversed or re-applied block not
held to the disk; a reverse that writes what the block left; a re-applied record that writes its priors; a layer
read over the disk and not over the batch so far; a replaced trie settled against the disk's rows alone, or with
the batch's deletes shown, or bounded on the disk's rows and not the virtual ones, in rows or in bytes; rows of an
unlisted trie not written; the root not held to the target's; every touched row written, changed or not; a reader
of the old state given the new rows; a tombstone or a record over its bound or an unlisted trie's block treated as
an ordinary record); the move (a commit between two batches; a move not said to be under way, or said to be after
its end; a dropped layer applied; a batch written though the epoch moved; the caller's rows of a move to the head
not written; no re-plan, or a re-plan after a later batch; a layer that does not stand kept; pruning in a reversing
batch, of heights the batch records, or counted from its last block; no record for a layer's block; the caller's
rows or the anchor left out of the batch; one batch whatever the size; no `heldTo`; the layers applied kept, or
dropped between batches, or kept after a move that failed; a move inside a move; a move not counted); and the
store that failed its read-back (written to again, moved, read, given an executed block, or its promotion or batch
saying nothing). **Equivalent**: the check that the anchor is where a batch starts, with the epoch checked too (the
anchor never moves without the epoch); and the drop of layers after a one-layer batch that is not the last (records
lie on a prefix of the blocks applied, so every later block is a layer on it). Three cases were added for mutants
the first run missed: a branch that leaves a trie as the old one left it writes none of its rows; a record that
became a tombstone under the plan is named; a layer dropped between two batches stops the move where it is.

**Reads after code (forge, beacon, vault): all APPROVE WITH CHANGES, nothing critical.** Verified by them in
code: the coalescing is exact for a trie reset earlier in the batch, a reset that is reversed, and a contract
destroyed and made again in one block; the reverse check is sound above an older tombstone, and the reverse of a
prior of none over a row left behind deletes that row, rightly; the views of the old head, behind it, through
fork layers and through layers being promoted stay right across one batch and across several; no key is written
twice or deleted after it is written in a batch; rows, records and anchor recover together (one write-ahead log,
never disabled); no commit can land between two batches; with the guard off nothing changes. Taken: the bounds
on the virtual rows (above); the layers a later batch needs are not dropped between batches; the fail-closed
store gives out no state, and its memory follows the disk before it says so; records are read past the block
cache; the move's lock is fair, and a move asked for from inside a move is refused.

#### C4b part 2b: the writer's rows in the move's batch (2026-11-13)

**The head's index rows are built apart from their commit.** `BlockchainWriter.canonicalHeadUpdate(head, reader)`
builds the batch that makes a block the canonical head (its ancestry in the index of blocks by number, nothing
above it, the best-block pointer, the transaction lookups of the blocks it drops) and writes nothing;
`promoteToCanonicalHead` is that batch committed, with its log line. With the guard off nothing changes, and a
spec holds it to that byte for byte: two stores of the same chains, one promoted and one given the builder's batch,
are equal after a move up, across, down and to the head itself.

**The move writes them in its batch** (T137). On the execution-apis chain imported on the unified layout, a move
whose `extraFor` is the builder for the block each batch ends on leaves the index the ancestry of the state's
block, the best block that block, the transaction lookups in the canonical one of two blocks that share them, and
the anchor, in one batch: down by three records, a competing block imported in place, and across by records. A
batch that fails writes none of them; after each batch of a move of one block a batch, the index is the ancestry
of the block the state is at.

**The layers a plan applies are pinned for the whole move**, and the pin released at its end, whatever came of it
(beacon: an eviction between two batches would stop the move). They are in memory already, so the bound on pinned
bytes is not asked.

**The move's metrics** (T151): `db.state.move.seconds`, `db.state.move.blocks` by side, `db.state.move.batches`,
`db.state.reorg.refused` by reason.

**Read after code (beacon): APPROVE WITH CHANGES, nothing critical.** Verified in code: guard off, promotion
reads, builds, logs and writes what it did; the builder is right for a head on the way down and on the way up, and
a lookup deleted in one batch and written again in a later one ends as one promotion would leave it. Taken: the
byte comparison now covers a move up and the sweep of entries left above the head; an across move one block a
batch checks the index, the pointer and every lookup after each batch; the way down checks the lookups it drops;
a failure to release the pin does not hide the move's own; every refusal reason has its counter. **Owed by part 3**
(recorded at T140): the index rows a caller gives a move are built outside the state lock and written blind, so
any other writer of the index or the best block in between makes them stale (`newPayload`'s `storeBlock` indexes a
payload whose parent is canonical). On the guard the move must be the only such writer, or the batch checked under
the lock.

**Left, and where.** Nothing in the node calls `moveHead` yet: the callers that exist on the guard
(`test_rewindToBlock`, the index writers, the SYNC-FORK rewind) are part 3, with the table of pointer writers as a
test; the Engine API's `forkchoiceUpdated` is C4d. A bound on a batch by bytes with a barrier move, and the hold
that keeps retention off the records while a reverse-then-commit attempt is unsettled, are part 2c.

**The mutation run**: 13 mutants; 12 caught, 1 equivalent. Promotion that writes nothing; the lookups of dropped transactions
kept, or those of re-included ones dropped; what was dropped or cleared not said right; an extension taken for a
reorganisation and the other way round (missed at first: no case moved to a branch that ends above the old head;
it has one now); a move's layers not pinned, or the pin of a move that failed never released; a move, or a refused
one, not told; a refusal reason without its counter. After the read four were run again on the new cases (the
lookups both ways, and the pin). **Equivalent**: the release of the pin after a move that succeeded, whose every
pinned layer has been applied and dropped by then.

#### C4b part 2c: a move's batches bounded, the barrier, the hold for an attempt (2026-11-13)

**Batches cut by what they read.** A plan reads every record's header already; the store keeps each record's
length from that one read, and a move's batches are cut by count and by bytes (a record's length, a layer's
bytes; `CommitSettings.maxMoveBatchBytes`, 128 MiB). A block worth more than a batch goes alone. What ends up on
disk does not depend on the cut: a move cut into a batch per block leaves the data source one batch leaves.

**The barrier.** The prior values a batch must give back to older views are kept in memory for as long as the log
keeps its move. A batch whose prior values are more than `CommitSettings.maxMovePreBytes` (64 MiB) is written as a
barrier (`StateMoveLog.inFlightBarrier`): the log is emptied before the epoch is made odd, and every view made
before it is stale, never wrong. An in-place commit takes the same rule. And the log as a whole holds no more than
`CommitSettings.maxMoveLogBytes` (128 MiB) of prior values: the oldest moves go first, the newest stays whatever it
holds (forge: eight moves of 64 MiB each would otherwise be held outside every bound).

**The hold for an attempt** (reverse-then-commit, the user's decision of 2026-11-12; C4e is its caller).
`PathStateStorage.withRecordsHeld(above)(body)`: while it runs, no commit and no move prunes a record above
`above`, and a move may go back to `above` however deep that has become (forge: the depth policy bounds what a
move may undo, and not the way back from an attempt the node made itself; on ETC with 512 retained, an attempt
longer than that would otherwise have no way back though every record is on disk). It is taken under the state
lock and refused at once (`Deep`) when the records above `above` are pruned already. It lives in memory: after a
crash mid-attempt the next commit prunes as usual, and what a restart means for an attempt is C4e's to say.

**The mutation run**: 17 mutants, all caught. A batch not cut by bytes, cut at the bound itself, or started at
nothing; the records' sizes not kept; a layer worth nothing; no barrier, a barrier that keeps the log or leaves the
anchor; an in-place commit never a barrier; a log that keeps its bytes whatever they are, or lets the newest move
go; a hold that holds nothing, holds too little, outlives its attempt, or is ignored by retention; the way back
bounded by the policy; a hold taken above records that are gone. Two of them did not compile as first written and
were written again.

**Read after code (forge, with vault's questions): APPROVE WITH CHANGES, nothing critical.** Verified in code: the
barrier leaves every view made before it stale, never wrong (the anchor's, one through a reverse index, one through
layers, one made while the log was already empty); nothing else reads the log in a way it breaks; a hold taken with
no lock could not corrupt a state (a prune before it is caught by the epoch and the re-plan), and capping the floor
at the block above the fork is exact. Taken: the way back's depth while a hold is open; the log's byte budget, and
the barrier for in-place commits; the hold under the lock and refused at once; barrier cases for a view through
layers and a barrier whose write fails. **Noted for M6**: a batch's memory at its peak is some three to five times
its records' bytes (decoded record, the virtual disk, the maps it writes from, the write batch), and the scans of
replaced tries are not counted in the cut.

#### C4b part 3a: the head's writers on the unified layout (2026-11-13)

Beacon and forge, consulted before code (both read the tree), agree: **on the unified layout the move of the head,
and a block's own commit, which is the move of one block, are the only writers of the best block and the index of
blocks by number.** A check under the lock alone does not close the window beacon found in part 2b (a writer that
takes no lock writes between the check and the batch), so every other writer is routed through a move, refuses on
that layout, or runs only where the layout is not yet in force (before an anchor, at start). Part 3a is the
structure; part 3b routes the two callers that reach the head on the guard today (the fork choice, which promotes
the head after `newPayload`, and test mode's rewind).

- **The writers refuse** on the unified layout with an `IllegalStateException`: `BlockchainWriter.saveBestKnownBlocks`,
  `setCanonicalChainHead` (the SYNC-FORK rewind), `rewriteCanonicalIndex` (the reorganisation's index), and
  `Blockchain.removeBlock` (and with it `discardBlocksAfter`). Test mode's reset of the chain for a new genesis is
  `Blockchain.removeChainForNewGenesis`, allowed only where the state may be destroyed, which it is next.
  `PivotAnchor.reanchor` refuses once there is a state anchor. None of these is reached on the guard today (sync is
  not started there, SNAP and mining are refused); a path that reaches one later fails loudly at its first test.
  `promoteToCanonicalHead` refuses with part 3b, which routes the fork choice through a move.
- **The best block is held to the anchor under the state lock** before a block's commit and before every batch of a
  move (`CommitSettings.checksBestBlock`, on in the node): a writer that moved the best block alone is a defect, and
  nothing is written over it.
- **A move's link is the header stored under a hash**: `BlockchainReader.headerLink` and `ancestorAt`, which never read
  the index of blocks by number (a spec plans and makes a move with a reader whose index throws at every read).
- **The table of the head's writers is a ratchet** (T140): `scripts/check-head-writers.sh` and
  `scripts/head-writer-allowlist.txt`, the state accessors' engine with its own entry points (the best block's rows,
  the index's, the head's writers by name, the block stores that index). Every line says which of MOVE, REFUSED, DEF,
  HASH, PRE, START or C4D/C4E holds for that writer. It runs in CI beside the state accessors' check.

**Recorded, with the slice that owns it.**
- **SYNC-FORK on the unified layout makes no head move at all** (forge): today it rewinds to the common ancestor and
  imports the peer's branch with no comparison of weight, and the old branch is deleted, so there is no way back. On
  that layout the fetcher is invalidated from above the ancestor, the blocks arrive as a side branch, and the
  reorganisation decides by weight under `withRecordsHeld` (C4E); `Deep`, `Pivot` or `Corrupt` there escalate (D).
- **`newPayload` stores by hash only on the unified layout** (beacon, C4D), as go-ethereum's
  `InsertBlockWithoutSetHead` writes no canonical hash.
- A pre-existing defect of the hash layout that forge found reading the resolver: `discardBlocksAfter` deletes the
  index entries before `rewindCanonicalChain` reads them, so the reference counts' undo never runs on that path
  (B-248, its own change).

**Read after code (beacon): APPROVE WITH CHANGES.** Verified in code: nothing on the hash layout changes; on the
unified layout no path reachable today throws in its normal order (test mode's commit is followed by the fork
choice's promotion of the same block; the Engine API promotes only the head the state is at); throwing, not failing
the store closed, is right for a best block moved alone, since nothing was written and the disk keeps the refusal
across a restart. Taken: two guard specs that staged "the head moved alone" with a writer that now refuses write the
row themselves; the caller's rows for the head as it is are held to the anchor as any batch is; in the node a batch
that moves the state must bring the rows that move the best block; the ratchet holds each line to its verdict word;
two `testing_commitBlockV1` in a row on the unified layout. **Owed by part 3b** (recorded in the allow-list): the
fork choice still promotes outside the state lock; a commit that moves the anchor in between leaves the best block
behind, which the check then refuses to write over, and nothing but test mode's reset mends that before part 4.

**The mutation run**: 18 mutants, all caught. The writer not told the layout; each refusal undone
(`saveBestKnownBlocks`, `setCanonicalChainHead`, `rewriteCanonicalIndex`, `removeBlock`, the reset outside test
mode, the pivot's re-anchor); the best block not held to the anchor, held by its hash alone, not for a move, not for
a commit, not in the node; a header under another hash taken for a link, or followed to an ancestor (a walk that
then never ends); an ancestor above the block found; the head's own rows written over a best block moved alone; a
move without the rows that move the best block; a reason without its verdict. Five were missed at first and got
their cases.

#### C4b part 3b: the fork choice and test mode's rewind move the head through a move (2026-11-13)

Part 3a left two callers that reach the head on the guard today: the fork choice, which promoted the head outside the
state lock, and test mode's rewind, which refused any block between the genesis and the head. Part 3b routes both
through a move of the head, the design beacon gave before code (part 3a's consult, §3 and §4).

- **One way to move the head with the state**: `BlockchainWriter.moveHeadInPlace(target, reader, refuse)`, which is
  `PathStateStorage.moveHead` planned by `BlockchainReader.headerLink`, with `canonicalHeadUpdate` for the block each
  batch ends on as that batch's rows. The writer now takes the path store (`pathState`, from `BlockchainWriter(storages)`),
  so every writer the node and the specs build from their storages has it, and no caller is wired by hand. `refuse` is
  asked for each batch before its rows are built; a refusal ends the move with a `StateUnavailable`.
  **`promoteToCanonicalHead` refuses on the unified layout.**
- **The fork choice confirms the head by a move of nothing** (`ForkChoiceManager.applyForkChoiceState` on the guard).
  `EngineApiService.forkchoiceUpdated` lets through only the head the state is at; a commit in place of the next block
  may land between that look and the write. The move of nothing is written under the state lock against the anchor it
  was planned from, so that commit makes it a move back, which is refused: nothing is written and `currentState` is
  not set. `forkchoiceUpdated` then finds the head it was given a VALID ancestor of the canonical head and answers it
  as its own branch for one does, VALID with no payload (execution-apis paris.md point 2; go-ethereum "Ignoring beacon
  update to old head"); T145's interleaving, driven through the Engine API with the commit landed after every look
  it takes at the head and before the fork choice's write. Before 3b the promotion moved the best block back and deleted the new head's index entry under a state that
  stayed the new head's. Any other move off the held block is refused until C4d (`SYNCING`); a `Corrupt` plan is
  thrown on. On that layout the call runs under `IO.blocking`, since it may wait behind a move; the hash store's runs
  as it did. The reference counts are not touched on that layout.
- **`test_rewindToBlock` below the head moves the head back** to the block of its own chain at that height, found by
  parent hashes from the held block (`ancestorAt`), never by the index the move rewrites. The blocks above it are not
  removed: they stay known by their hash, with their records, the way back up; imported again, a block is executed
  again, to the block it was. A refused move (`Deep`, `Corrupt`, `Incomplete`, `Pivot`, `Finalized`) is the request's
  error, `TestService.RewindRefused`, with nothing changed and the pending transactions kept. A rewind to the genesis
  still sets the chain's genesis again.
- **The writes themselves are watched** (beacon 5b): the guard chain spec's data source records every write of the best
  block's rows or of the index of blocks by number made while the state has an anchor by a thread that does not hold
  the state lock. The commits, the moves, the fork choice (confirmed and refused) write none; a write of the best
  block's rows alone is seen.
- **The ratchet** counts `moveHeadInPlace` as a writer of the head (MOVE): the fork choice's line becomes HASH for the
  hash layout's promotion and MOVE for the confirmation; test mode's gains the rewind.

**Read after code (beacon): APPROVE WITH CHANGES**, all taken but where said. The hash layout is unchanged (the
refusal is inert without a path store; the hash arms of the fork choice and the rewind are untouched); the guard's
branch is reached for a head other than the anchor only by the race. Taken: the race answers VALID, not `SYNCING`
(above), with an end-to-end case through `forkchoiceUpdated`, with and without payload attributes; the guard's call
under `IO.blocking`; a `Corrupt` refusal logged at ERROR with `[STATE-MOVE]` in test mode, as the fork choice throws
it; a `StateUnavailable` in a rewind answered as `RewindRefused`. Not taken, with the reason: a rewind of several
batches is the writer's one-block-a-batch case in `HeadMoveOnTheChainSpec`; `importRawBlock` of the very block kept
above the new head cannot be made in this rig (a block mined in test mode carries no seal, and a sealed copy is
another block), so the same block mined again stands for it (known by its hash, with a record, committed in place
again); a commit that waits for a confirmation under way is `adopting`'s, covered by `HeadMoveSpec`.

**Recorded.**
- **A kept block still answers by its hash** after a rewind on the unified layout (`eth_getBlockByHash`, its receipts
  are kept), where the hash layout and go-ethereum's `SetHead` answer null. The headers are what a move back up is
  planned from (`headerLink`), so they stay; `eth_getBlockByNumber` above the head answers null on both.
- **A confirmation counts as a move** (`movesStarted`, `moving`): no code in main reads them yet. A builder that does
  (the miner, C4e; the payload builder, C4d) must not take a confirmation for a move, or the store gets a path for a
  move of nothing that does not count.

**The mutation run**: 17 mutants, all caught. The fork choice promoting on the unified layout, moving the head off the
held block in any direction or only up, taking a head it did not confirm as applied, answering a corrupt record
`SYNCING`, throwing a refused move; the Engine API answering a head the race made an ancestor `SYNCING`; the writer's
promotion not refused, a refused batch written, every batch given the target's rows, a move's batches given none, the
writer not given the store; the rewind keeping the pending transactions, taking them for a refused move, throwing a
refusal past the request, refusing as before, or going to the block above. Two did not compile at first (an unused
symbol is an error here) and were rewritten so that they do. The Engine API's mutant survived the first form of the
end-to-end case, which committed inside the gate's look: there the Engine API's own branch for an ancestor answers
first. The case now commits inside the fork choice, where the window is.

#### C4b part 4: the reconciliation at start (2026-11-13)

Vault and forge, consulted before code, agree: **the block whose state the disk holds wins, always.** Since part 2 a
block's commit and every batch of a move write the state's rows, the anchor, the best block and the index in one
RocksDB write batch (one `dataSource.update`, the write-ahead log on), under the state lock, after part 3a's check that
the best block is the anchor. So no crash leaves "the pointer half committed first", the case data-model 5.1's rules 2
and 3 were written for. A best block that is not the anchor at start comes from a writer's defect, a datadir written
before 3a, or test mode's reset of the chain, which is two writes (vault). Moving the state to that best block would
throw away a state that execution proved (rule 2) or undo a fork choice that a move made deliberately (rule 3), and
trusting the best block while the state stays put leaves every import refused, since a block is committed in place only
on the anchor (forge). Rule 4 needs only the anchor's header, never writes the state, and loses nothing: the blocks
above the anchor stay stored by hash and are executed again when they are imported again (on the unified layout today a
chain import or test mode; the Engine API and sync follow with C4d and C5). go-ethereum's start does the same, down to
the highest block whose state it has (beacon). **So rule 4 is the rule, and rules 2
and 3 are not built** (data-model 5.1 amended; the user agreed on 2026-11-14: "go with what is on disk").

- **`StateReconciliation.run`** (`domain/`), called from `StdNode.start` before the genesis loader and before any
  actor, server or sync, in place of the hash layout's `fixDatabase` and `repairBestBlockMapping`, which trust the best
  block or the index and would undo it (forge: also before the storage check, the sync controller's starting block and
  the fork ID, all of which read the best block).
  - Not stamped `path-live-1`: nothing; the genesis loader judges the datadir.
  - No anchor: nothing, unless the datadir says SNAP sync is done, which is refused.
  - The anchor's header not stored: refused, but in test mode, where it is a reset that stopped between its two writes,
    left for the next `test_setChainParams` (which destroys the state) with nothing written.
  - The anchor's header naming another number or state root than its row, or its chain weight not stored: refused.
  - Otherwise the best block and the index are made the anchor's (`[STATE-ANCHOR] best pointer rewound` at WARN), or,
    with the best block in place, the index alone (I6). A datadir found consistent takes no write.
- **`BlockchainWriter.heldBlockIndex`**, a START builder: the anchor's ancestry by parent hashes for 1,024 blocks, with
  every height whose entry is not the ancestor rewritten at whatever depth (not `canonicalHeadUpdate`'s walk, which
  stops at the first entry that matches); every entry above the anchor deleted, up to the old best block and 1,024
  heights past the highest found, capped at a million above the anchor; the lookups of the transactions of dropped
  blocks; the best block. One synced batch, under the state lock. Nothing else: no state row, anchor, record, header,
  body, receipt or weight.
- **The ratchet** counts `heldBlockIndex` as a START writer; `StdNode`'s two START lines are the hash layout's only.

**Tests** (`HeadMoveOnTheChainSpec`, the execution-apis chain on the unified layout): a best block above the state with
its index and lookups (rewound; the rest of the datadir byte for byte as it was; the writes under the lock; a second
start writes nothing; the blocks above imported again as blocks to execute, and a fork choice on the new head); a best
block below, on another branch, with no hash, unknown; the index with the best block in place (a side entry under
entries that match, a hole, an entry above a gap); the refusals, each writing nothing, and test mode's residue left; a
batch that fails, then the next start.

**Recorded.** I6 is not reachable on the guard today: side-branch execution is refused before its `saveBatch`. C4e
should store a block it does not adopt by hash only, as C4d does for `newPayload`, so that I6 stays a defence (forge).
`StdNode.start`'s call is not covered by a spec (the node is not started in unit tests).

**Read after code (beacon): APPROVE WITH CHANGES**, all taken but where said. Rule 4 is right after the merge too,
and dropping rules 2 and 3 is right on ETH as on ETC. `newPayload`'s answer of VALID for a kept block above the anchor
(its receipts are kept) is no consensus fault: the block was executed and validated when it was committed in place,
validity is the block's and not the state's, and nothing moves on it (go-ethereum answers a known payload the same
way). The caches start empty and the finalized supplier is none on this layout. Taken:
- the Engine API's gate for a head that is not the held block now records it as the head asked for and publishes it
  (notify-only, as its other `SYNCING` branches): without it, after a reconciliation that lowered the head, nothing
  would know where the blocks above lead;
- the claim that the blocks above "are executed again when they come back" says what imports them today;
- a transaction of a dropped block that the ancestry includes higher up keeps its lookup, pointed at that block (the
  re-included set is the ancestry from the lowest rewritten height up, and its locations are written again);
- the hash layout's two repairs do not run on a datadir stamped for the unified layout and opened with the guard off,
  which the genesis loader then refuses: nothing is repaired in it first;
- cases: the Engine API after a reconciliation (the consensus layer's head above answered `SYNCING`, nothing written,
  that head recorded); the window and the cap; the shared transaction.
Not taken, with the reason: `newPayload` for a kept block and for its child is not driven here (a payload from a block
of this chain is not built by any helper of this spec); the duplicate rule (`number <= best`) is read, not driven
through `ConsensusAdapter`, whose node this fixture does not build; `StdNode.start`'s call has no spec.

**The mutation run**: 16 mutants, all caught. The reconciliation starting a synced datadir with no anchor, refusing
test mode's residue, taking an anchor row whose state root contradicts its header, taking an anchor with no weight,
writing a consistent datadir, writing off the state lock, reconciling a datadir with no stamp; the index walk stopping
at the first entry that matches, looking for no entry above a gap, keeping a window of one block, removing nothing
above the held block; the best block not moved; the lookups of dropped blocks kept; the old lookups rule (a shared
transaction dropped and not put back); the Engine API's gate recording no head. Five did not compile at first (an
unused symbol is an error here) and were rewritten so that they do. One more, the new lookups rule's first half
alone, is equivalent: the locations written again in the same batch put the lookup back.

### C4d: the PoS arms, as it is to be built (2026-11-14)

Beacon's decomposition, before code (C4d is beacon-first). What the guard does today, read in the code:
`newPayload` never executes (a block not on the anchor is refused before execution, `refuseUnadopted`), and is stored
by hash only and answered ACCEPTED; `forkchoiceUpdated` follows only the held head. **A latent defect part 2a must
close first:** once a payload executes, its store indexes it by number (`storeBlock` -> `storeBlockHeader`), which is
not refused on the guard and would put an index entry above the anchor: on the guard every executed payload is stored by
hash only, and a spec pins it.

The parts, in order, each mergeable alone:
- **Part 0, plumbing, no change to the answer table**: a confirmation of the head is no move (B-249); `Corrupt` fails
  the state closed at the fork choice, answered `SYNCING` (below).
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

**Decided by the user on 2026-11-14**, on beacon's proposal: `Finalized` is answered `-38002` (go-ethereum: the
finalized block is not in the canonical chain); on PoS a block of a long branch found invalid moves the head back to
the fork choice's last head (the consensus layer's head stays the validated one), where W4 (PoW, C4e) keeps the
executed prefix; the designated-head arm (T170) moves to C4e, which edits the same `reorganise`; the armed ordered
clear lands with C5a, not before.

#### C4d part 0: a confirmation is no move, and `Corrupt` fails closed (2026-11-14)

- **B-249**: `movesStarted` counts a move of the head under the state lock before its first batch is written. A
  confirmation of the head as it is moves nothing and is not counted, and neither is a move refused before any write
  (by its plan, or by its caller's rows). A build that overlaps any write of a move still sees the count change.
- **`Corrupt` fails closed at the fork choice** (plan.md "C4b part 1"): `PathStateStorage.failClosed(why)` (through
  `BlockchainWriter.failStateClosed`) marks the store as one that takes no further in-place write and gives out no
  state until the node is started again, logged at ERROR; the fork choice answers `SYNCING` and does not set
  `currentState`. Before, part 3b threw the refusal past the Engine API. Test mode's rewind still answers it as a
  refused rewind with nothing changed (C4b part 4), since a test datadir is mended by the test.

**The mutation run**: 6 mutants, all caught: a move not counted, counted at every batch, a confirmation counted; a
corrupt record leaving the state open, thrown past the fork choice, a closed store taking writes. The count at every
batch survived the first run; the one-block-a-batch case now counts its moves.

**Read after code (beacon): APPROVE WITH CHANGES**, all taken. The count is under the moves lock at every access and
counted once per move; `Corrupt` reaches the fork choice only from a real bad record or a codec fault (a prune, a
commit in between or a test-mode wipe moves the epoch and is refused otherwise); the reconciliation at start reads no
view; nothing changes on the hash layout. Taken: the count's wording (counted before its first batch is built, so a
first batch refused while it is built is counted with nothing written, the safe side; one per move, so a reader pairs
it with `moveInProgress`); `failClosed` takes no lock, so a write already past its check completes and none that
starts after does; a genesis commit refuses a store that failed closed, and test mode's destruction of the state
clears the fault with the state it was found in (a case); test mode's comment on `Corrupt` (it logs, and does not fail
closed: a test datadir is the test's to mend). Recorded: the fault is kept in memory only, and nothing looks at the
records again at the next start (B-252).

#### C4d part 1: the finalized block (2026-11-14)

The node's finalized block on the unified layout, which a move of the head must not reverse (C4b part 1's `Finalized`
refusal) and above which retention keeps the records (`StateConfig.retained`: the anchor less the finalized block,
plus the margin), was none: no consensus client drove the layout. Now:
- **The fork choice names it** (T160, T171): the finalized block hash of a fork choice that was followed, by its
  header, is written in the batch of the move, or the confirmation, of the head that followed it, as the AppState row
  `state.finalized` (`v1:<hash>:<number>`), and is the store's (`PathStateStorage.finalizedBlock`) under the state
  lock right after that batch (`moveHead(..., finalized)`, through `BlockchainWriter.moveHeadInPlace`), so the move or
  commit that follows sees it. A fork choice that was refused names nothing; the row is not written again when it is
  the block already named. The zero hash names none and leaves the block named before; a hash with no stored header
  does too, logged; a block below the one named is taken, logged (go-ethereum's `SetFinalized` takes it, and on the
  held state's chain it only loosens what is kept). `testing_commitBlockV1` names none: test mode has no beacon chain
  behind it, and naming each committed head would refuse every later rewind.
- **It survives a restart**: the store reads the row when it is opened. Before this a restart lost the `Finalized`
  refusal, and the retention floor jumped from the finality lag to `state.retention.without-finality` and back.
- **Test mode's destruction of the state takes it away** with the state's other marks.

**The mutation run**: 9 mutants, all caught: a refused fork choice naming the block, the row not written, the row not
read at start, the store ignoring the block it was given, test mode's reset keeping the block or its row, the block
not taken by the store after a confirmation's batch, the writer not passing it on, a test-mode commit finalizing its
head.

**Read after code (beacon): APPROVE WITH CHANGES**, all taken. The named block is always on the held state's chain:
the Engine API refuses a finalized block it does not know or that is not an ancestor of the head before the fork
choice, and the confirmation passes only for the anchor. Taken: the block is the store's under the state lock right
after the batch that ends on the target (it was set after the move returned, a window a fork choice that reverses
blocks, from part 2b, could have used, and a gap where a throw after the write left the row on disk and the store
without it); `testing_commitBlockV1` names none; the two WARN lines; the row not rewritten each slot; one zero hash.
Cases: the Engine API's fork choice names it; a lower one is taken; a store is not opened over a row it cannot read; a
test-mode commit names none. Not driven here: retention narrowing to the finality lag once a block is named (the
arithmetic is `StateConfig.retained`'s, and its spec; a chain longer than `pruning.history` is not built here).
Recorded: a path that gives a datadir its anchor again (the hand-off of C5a, a checkpoint import of C5c) must take the
row away with the old state, as test mode's destruction does.

#### C4d part 2a: a payload is executed as a forward layer (2026-11-14)

`newPayload` on the unified layout executed nothing: a block not on the anchor was refused before it ran, and every
payload was answered ACCEPTED. Now a payload is executed and its state kept beside the state the disk holds, as a
forward layer (C4a), and the head does not move: only a fork choice moves it (part 2b; the execution-apis Paris
specification).
- **A block is committed apart from the block** (`StagedBlockState.commitApart`): on the hash layout the commit of its
  state alone, as before; on the unified layout `PathStateStorage.commitOverlay`. A payload is staged apart
  (`Blockchain.stageBlockStateApart`): its parent may be the anchor, a layer, a block behind the anchor that the
  records lead to, or a block on a fork from those (`stageOf`, which C4a kept private until an execution could commit
  such a block). The importer's in-place staging, and its refusal of a block it does not adopt (C4e), are unchanged.
- **An executed payload is stored by its hash only** on the unified layout, whatever its parent (beacon: the store
  that indexes it by number was not refused there, and would have put an entry above the anchor, I6). Its state is a
  layer, not a reference count, so nothing is taken back out of the hash store's counts.
- **A payload whose requests are not those its execution derived** is marked invalid first, so a fork choice in
  between finds it so, and then its layer, with what stands on it, and the import gate's record of it are dropped
  (`BlockchainWriter.dropForwardLayer`, `BlockImportGate.forget`).

Answers on the unified layout: a parent whose state is held (the anchor, a layer, behind the anchor through the
records, a fork from those) executes, VALID or INVALID; a parent stored and executed whose state is not held any more
(its layer evicted), or a parent not known, ACCEPTED, stored by its hash; a layer not kept (too large, no room) is still
VALID, since the block was validated and its state can be executed again (part 3). The fork choice still follows the
held block only until part 2b.

**The mutation run**: 5 mutants, all caught: a payload's layer not kept, a payload staged in place, a block staged
apart staged on the anchor only, an executed payload indexed, a payload committed in place.

**Read after code (beacon): APPROVE WITH CHANGES**, all taken but where said. The answers are right (a layer's
receipts admit its child; a parent whose layer is gone is a state fault, ACCEPTED, as go-ethereum's
`delayPayloadImport`), no state fault reaches INVALID, and nothing changes on the hash layout. Taken: a payload staged
apart is "applied" for the import gate while its layer is kept, so one delivered again while its first execution runs
takes that execution instead of a second (the consensus client resends a slow block); the `INVALID_REQUESTS` cleanup
runs under the block's gate; a layer not kept is logged with its reason; `newPayload`'s log line says "layer" on the
unified layout; the Engine API requires that it is told the held block exactly when the state is held in place (a
builder replay on positions that was not told it is fixed); cases: an unknown parent answered exactly ACCEPTED, a
parent whose layer was dropped answered ACCEPTED with nothing executed. Not taken: `INVALID_REQUESTS` is not driven (it
is unreachable over RPC: the controller refuses malformed requests, and the requests hash binds the block hash), and a
layer not kept, which needs a store with a small layer bound. **Owed by part 2b**: a fork choice to a head whose
layers were not kept, or were evicted, re-executes them (go-ethereum's `recoverAncestors`; part 3's re-execution), and
the descendants of such a block stay ACCEPTED until then.

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
| EG-1 | **Roots-equal import differential.** ETH: fixed ranges of Platåberget and Sepolia, **including Prague and Amsterdam ranges with request and block-access-list activity**, and an ETH mainnet range if a mainnet state exists. **ETC: an offline import from genesis** of ETC blocks 0 to 5,100,000 (`ChainImporter.importChainFileStrict` over a `chain.rlp` export; **the source is open item 1: a core-geth export (forge's recommended primary) or a fukuii full sync with SNAP off (acceptable), decided later; it gates C7 and this half of EG-1, not code**), and a Mordor import from genesis to 2,600,000 (era 0 to 1, Agharta 301,243, Phoenix 999,983, ECIP-1099 2,520,000) | ETH: every state root, receipt root and gas-used equal, and `requestsHash` and `blockAccessListHash` equal explicitly. ETC: **every block validates against its canonical header** (state root, receipts root including the pre-Atlantis intermediate roots, gas used), node-set equality with the pinned jar **every 100,000 blocks**, and every miner and ommer balance equal across the 5,000,000 era boundary and Mordor's two. At Platåberget and Mordor scale the position store walked from the anchor equals a `StackTrie` rebuild. `app.db.state.record.tombstones` is recorded over the ETC import and the Mordor runs (a non-zero count is explained before the bound is frozen, M15). Mordor also under `archive` (EG-10) |
| EG-2 | ethereum/tests and EEST on the ported harnesses | the ETH job and the ETC job green; **`ForkChoiceBlockchainTestsSpec`** (Istanbul and Berlin, i.e. Phoenix and Magneto, through `reorganise`) green under `basic` and `archive`; the pre-Byzantium and EIP-150 networks green (intermediate-root receipts) |
| EG-3 | the matrices of C3b to C6: reorg (PoS and PoW), ETC PoW, crash, isolation and concurrency, fault injection | all green; I1 to I6 asserted; **the deterministic interleaving test** over the importer commit, `moveHead`, `newPayload` and a builder view gives a position map equal to a from-scratch replay; the fault-injection runs never produce INVALID, `markInvalidRecursive`, `reportInvalid` or a delete; the no-anchor refusal and the escalation tests (hysteresis, cool-down, loop guard, kill at every step) pass |
| EG-4 | **Fresh Sepolia sync with 24 h of head following (the T063 rerun)**, under a real consensus client | no `[PATH-PUBLISH]`; hand-off time, disk (expect about 150 GB below T063's 300-340 GB), import p50 and p99 and `[IMPORT-COMMIT]` against the pinned jar (DC-14), heap and RSS, overlay gauges, `app.db.state.view.stale` and `.retries`, the audit's first pass clean; **`app.db.state.record.tombstones == 0`** (a tombstone at the head would turn a routine depth-1 reorg into a multi-hour re-SNAP) |
| EG-5 | **T064's intent on a FRESH datadir** | on a fresh Mordor datadir and then on EG-4's datadir once its measurements are taken: rows deleted, bytes corrupted and positions moved are found and filled, the hand-off completes with no copy, and the audit's first pass records its time and findings |
| EG-6 | **ETC end to end** | a fresh Mordor SNAP sync and 24 h of head following with a local miner, **and a private ETC-config PoW net** (two or three fukuii miners plus a core-geth) that produces routine depth 1 to 3 forks, **with Olympia at a low block** (treasury credit every block, the base-fee floor, genesis `HefPostOlympia`): mined blocks valid, each mined block's root equal to its import root (with ommers), ECIP-1017 rewards unchanged, M7 and M10 |
| EG-7 | repository gates | `sbt pp`; the 016 ratchet; the 017 and stateless-closure dependency checks (DC-8): `NibblePath` is in `mpt/`, no new `db/` import reaches the closure; the grep ratchets (state accessors, hash-only walkers) |
| EG-8 | DC-14 figures in every slice's PR | p50 and p99 `[IMPORT-TIMING]`, longest read wait, bytes written per block, database size, RSS, `[STORAGE-PERF]` |
| EG-9 | **Checkpoint v2 round trip, both chains** | export from the Mordor and Sepolia nodes, import into fresh datadirs: every root equal; the ETC resync time recorded (SC-013). The **ETC mainnet half is EG-12's export**: import it into a fresh datadir, check root equality, follow the head for 1 h |
| EG-10 | **Both pruning modes, and the retired one** | `archive` passes EG-1's Mordor and Platåberget runs; a test shows `pruning.mode = "inmemory"` is **rejected at start on both chains** with the message naming the retired mode and the two that remain; the finalization durability test (kill -9 right after the synced commit) passes under `basic` and `archive` |
| EG-11 | after C8b, on the final tree | `grep` for every removed symbol is empty; `sbt pp`; an EG-1 subset and a short head-follow on each chain, with the flag gone |
| EG-12 | **First successful ETC mainnet sync** *(user decision, 2026-11-06; fukuii has never completed one; forge's route)* | a fresh ETC mainnet **SNAP sync on the unified layout with public ETC peers** reaches the head with no copy and the root equal to the head's `stateRoot`, then follows the head for 24 h. **Passes only with:** zero rejected canonical blocks; zero `REORG-EXEC-FAIL`; the number of routine fork moves (depth 1 to 2) followed, recorded; **the ancestor-window check** (BLOCKHASH in the first 256 blocks and an ommer whose parent is below the pivot) passing; the number of snap-capable ETC peers recorded at start (**fewer than 2: the user decides whether a declared local core-geth peer is allowed in the run**); time, disk, heap, RSS and CPU recorded against spec 007's finding. The node then exports the first ETC v2 archive, which is re-imported (EG-9) and followed for 1 h. **Ownership:** C5a the ancestor window, C6 ETC enablement and mainnet readiness (guard removal, peer precondition and logging, the PoW-specific hand-off checks, the run harness), C5c the export, C7 the run. **C8a does not merge before EG-12 passes.** A checkpoint is impossible as a route (no archive exists) and a sync from genesis takes one to three months |
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
| C5c | the v2 stream and importer disagree; a version 1 archive or a wrong-chain archive is imported | round-trip tests; the golden stream; the v1 and genesis-hash refusal tests | revert; the format is a one-way door, so it is signed in round 2 |
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

```text
src/main/scala/com/chipprbots/ethereum/db/storage/
├── MptStorage.scala                  # getAt, forOwner, updateNodesAt, rawNodesAt (additive; hash-keyed default kept for list tries)
├── MptStorageDecorator.scala         # NEW: forwards every member; the wrapping implementors derive from it
├── PathHandoffSample.scala           # NEW: the spec 022 D9 sample, ported from PathToHashExporter
├── StateLayoutGuard.scala            # NEW: the one marker, the one rule
├── AncestorWindow.scala              # NEW: 256 headers and 6 bodies below the pivot, a hand-off precondition
└── pathstate/                        # NEW package
    ├── PathStateStorage.scala, PathMptStorage.scala, PathStagedState.scala, PathPrefetchReader.scala, StateLock.scala
    ├── BlockDiff.scala, BlockDiffCodec.scala, ChangesetStorage.scala
    ├── StateOverlay.scala, ForwardLayer.scala, ReverseIndex.scala, StateView.scala
    ├── HeadMove.scala, StateRecovery.scala   # moveHead, startup reconciliation I1 and I6; local re-execution
    └── StateExceptions.scala         # the StateFault hierarchy: StaleStateViewException, ReorgTooDeep, StateUnavailable, BranchTooLarge
src/main/scala/com/chipprbots/ethereum/mpt/NibblePath.scala   # NEW (in mpt/, so the stateless closure gains no db/ import)
src/main/scala/com/chipprbots/ethereum/blockchain/checkpoint/ # CheckpointArchive v2, exporter, importer (C5c)
src/main/resources/conf/base/state.conf                        # NEW: fukuii.state.*; the guard key is deleted in C8b

DELETED in C8a/C8b: snap/PathToHashExporter.scala, StorageScheme, SnapHashTrie and the Hash arms in snap/,
  db/storage/ReferenceCountNodeStorage.scala, CachedReferenceCountedStorage.scala, FastSyncNodeStorage.scala, the Hash classes of
  StateStorage.scala, inmemory-pruning-node-caching.conf, the storage-scheme config key (no deprecation window)
```

### What the rest of spec 018 must change when this plan is accepted (not done here)

- `spec.md` Scope items 6, 8 and 10 and `plan.md` Phase 3: item 10 is **one gate** (no 10a/10b); F2 (changesets), F3
  (path-aware resolution) and the hand-off part of F4 move to Track C; F4 keeps only what Track E needs.
- `spec.md` DC-9 and FR-016: the layout moves for **both chains in this track**; **no deprecation window** for
  `storage-scheme` (deleted outright); the refusal is the one marker rule of data-model 5.3, for any non-empty unmarked datadir.
  G-7 is met by Track C. DC-11's two-engine "one minor release" window is also removed under the standing rule (spec
  clarification of DC-11); both engines coexist only within Track E as its differential oracle.
- `spec.md` FR-010 and Key Entities: the record key is `blockHash ++ kind` with a height index; amendment #2's row records
  "local part ships in C3a".
- `spec.md` DC-3, OQ-5 and Edge Cases: the `inmemory` mode is **retired** (OQ-5's recommendation), which closes its durability gap by
  removal; `pruning.mode = "inmemory"` is rejected at start, with no deprecation window. The "`pruning.mode = inmemory`" edge case
  becomes "rejected at start".
- `spec.md` Edge Cases: add "a datadir without the layout marker", "a block executed but not promoted at a crash", "a reorg
  deeper than `pruning.history`: `ReorgTooDeep`"; the T063 mid-copy case is now simply "refused".
- `research.md` 17: replace the Option Y cost row with this plan's sizes once C0 resets them.
- `.claude/agent-protocols/storage-rocksdb.md`: the unified-layout rules (anchor, diff, overlay, "never write `t`/`u` outside a
  batch that carries the anchor"), in C8b.

### Decisions made 2026-11-06

1. Option Y reconfirmed, with the size estimate seen. (The estimate has since risen from about 15,000 to about 20,200 added
   lines after S0 round 1 and to about 21,200 after round 2; the user has not yet seen those figures.)
2. Write-through confirmed: the disk layer is the canonical head; state, BlockDiff, anchor and best pointer in one batch; no
   journal.
3. Checkpoint import onto the unified layout is **funded and required** (C5c): it is ETC's resync path.
4. T064's intent runs on a **fresh** datadir (EG-5). The held T063 datadir is not used.
5. **Two pruning modes on both chains, `basic` and `archive`; `inmemory` is retired** (user, later the same day, reversing the
   earlier "keep all three") and rejected at start. This closes the INF-006 section 4 gap by removal; C4c is withdrawn.
6. The overlay budget stays a guess until M1 and M2.
7. **Standing rule:** one infrastructure for both chains; ETC moves and the Hash scheme is retired in this track; no
   legacy-datadir machinery (the earlier adopt-in-place choice is reversed); no release-level transition, only a short-lived
   in-development guard removed in the last slice; no 10a/10b split.
8. **The first successful ETC mainnet sync gates Track C** (EG-12); the old ETC datadir on `fukuii-fc` was deleted on the
   user's instruction, and fukuii has never synced ETC mainnet.

### Remaining open items (genuinely open)

1. **DECIDED 2026-11-07 (user: "this machine should sync etc then we can push to the nfs directory"):** source (c).
   `fukuii-fc` syncs ETC mainnet with the current release (v0.10.48, SNAP with chain download on, started 2026-11-07).
   Once it is synced and stopped, `fukuii etc export <file> 0 5100000` (`ChainTool`) writes one chain file from the
   header-validated canonical chain. That file is pushed to the user's NFS directory, and both the pinned jar and the
   Track C build replay it offline from genesis.
   - It keeps (a)'s one-deterministic-file property without a core-geth node.
   - If the sync fails, (a) and (b) below remain the fallbacks.

   The original candidates (2026-11-06):
   - (a) a `chain.rlp` from `geth export` (core-geth) on a synced ETC node: is there one, and is its history complete from
     genesis?
   - (b) fukuii's own full sync from genesis over p2p with SNAP off, stopped at a height: the whole chain is one to three months
     (`specs/022-snap-heal-convergence/research.md:351`), early blocks are small, and **M14 in C0 measures 0 to 1M** to say whether
     it is feasible; it also makes the import itself the oracle.
   **What in the gate depends on it:** EG-1's ETC half (blocks 0 to 5.1M validated per header, node-set equality with the pinned
   jar every 100,000 blocks, miner and ommer balances across the 5,000,000 era boundary); **the only coverage of the pre-Atlantis
   ETC rules** (ECIP-1010, ECIP-1017 era 0 to 1, the pre-Spurious-Dragon rules without EIP-161) and of the pre-Atlantis
   intermediate-root receipts on real ETC data, since Mordor starts at Atlantis and ethereum/tests has no ETC 3M-8.77M network;
   **Forge signed S0 item 11 in round 2 without the source: it is logistics, and it gates EG-1's ETC half (C7), not code.** Forge's recommendation: **(a) a core-geth `export` of 0 to 5,100,000 is primary** (one deterministic file replayed by the pinned jar and the new build, no p2p variance); **(b) fukuii's own sync from genesis is acceptable**, since the canonical headers are the root oracle either way and both builds can sync the same heights for the 100k node-set checkpoints, and M14 decides (b)'s cost. The user left the choice open. What does *not* depend on it: EG-12 (the ETC mainnet SNAP sync and 24 h
   follow), the Mordor imports from genesis, the ETC ethereum/tests job, EG-6, and every ETH item. If no source is named, EG-1's ETC
   half is unmeetable, item 11 stays open, and ETC's pre-Atlantis rules are covered only by ethereum/tests (Frontier, Homestead,
   EIP-150) and are not exercised on real data.
2. **Pinned oracle jar, recorded roots, hive and EEST baselines:** who keeps them, and for how long after the gate (the plan says
   until EG-12 passes, a second import of the first ETC archive, and one release past C8b).
3. **The snap-peer precondition for EG-12:** if fewer than 2 snap-capable ETC peers are visible, may a declared local core-geth
   peer be used? (The user decides, per forge.)
4. **Hosts for EG-6 (the private ETC-config PoW net), EG-14 (the consensus-client devnet) and EG-15 (Platåberget, 6 h):** which
   machines, and the Ethash DAG on the miners.
5. **The EIP-8246 text** has not been read by anyone against the EIP itself (beacon 5f and round 2): neither reviewer had an EIPs clone. C0 reads it and records the pass count of the 658 `eip8246_selfdestruct_no_burn` EEST fixtures (baseline 1 passing before #1422, `specs/009-amsterdam-fork-support/baselines/eest-v21-amsterdam-2026-09-26.txt:24`). Track C's storage design does not depend on the text: rule 6 triggers on the parent's storage root, and the one shape it needs (storage cleared, account kept) is what the code and `AmsterdamSelfDestructSpec` already produce.
6. **Archive view reach** (`state.archive.view-reach = 1024`) and its disk cost, after M1 and M9.

## Phase 2 — Track E: the engine (after Track C and GT-1; no longer waits for spec 017, clarified 2026-11-06)

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

*(Revised 2026-11-06.)* Slices F2 (changesets), F3 (path-aware resolution) and F4 (single-write SNAP ingest, the exporter
and Hash-scheme retirement, ETC on the unified layout) are delivered by **Track C** (Phase 1b, slices C1–C8b). Their rows
below are kept for history. Track F keeps F1, F5, F6, F6b, F7 and F8, re-based on Track C's layout.

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
