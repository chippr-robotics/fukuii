# Feature Specification: Storage Engine Consolidation (libmdbx + Flat State under the Capability Gateways)

**Feature Branch**: `018-storage-engine-consolidation` (spec written on `claude/fukuii-database-consolidation-h3bqt9`)

**Created**: 2026-10-17

**Status**: **Draft, ready for `/speckit-clarify`.** The Phase 0 evaluation is done ([research.md](./research.md)); the
plan ([plan.md](./plan.md)) is drafted to the point where `/speckit-tasks` can run once the gates below report. The
Design constraints and Gates sections are binding on that later work.

**Sequence**: spec 016 (SNAP split, `StateIngest`) → spec 017 (`StateAccess`, witnesses) → **this spec** → spec 019
(zkVM guest). **Implementation starts after spec 017 completes** (user decision, 2026-10-17). Phase 0 (measurement and
the binding spike, off the node host) may start as soon as spec 016 completes, because it merges nothing.

**Depends on**: spec 016's `StateIngest` (landed, T055; the healing-store members landed at C-H1) and spec 017's
`StateAccess` (G-1), `RecordingMptStorage` (G-2) and the differential witness test (FR-006). This spec swaps the engine
**beneath** those two gateways and must not change their signatures.

**Input**: User request, 2026-10-17: evaluate fukuii's database use and specify its move off RocksDB before the zkVM
guest work. "Fukuii must transition away from its legacy RocksDB storage engine to eliminate critical runtime limitations
and prepare the codebase for zkVM proof generation. Under heavy block validation, RocksDB introduces significant write
amplification and background compaction stalls, causing unpredictable execution latency spikes." Recommended target:
libmdbx (zero-copy memory mapping) with a flat-storage engine in the style of reth and Besu Bonsai, so that execution
reads are direct lookups instead of trie walks, there is no WAL and no compaction, and the zkVM guest stays free of any
database runtime.

## Problem

The evaluation ([research.md](./research.md)) found the pain the user describes already recorded in the repo, and it
found that the problem has two layers that today's code couples only loosely:

**Engine (RocksDB).** One instance, 20 column families, LSM with LZ4/ZSTD and background compaction. Every write batch
runs under a process-wide write lock and every read under the matching read lock (`RocksDbDataSource.dbLock`), so a
memtable flush or compaction stall inside RocksDB is held under that lock and **freezes block import, SNAP serving, the
Engine API and RPC together** (spec 015 R2; one flush measured at 30 s on the Sepolia SSD). A range delete of about 140M
keys ground for 30 minutes and pushed the container to its memory cgroup edge. The 16 GiB target host has seen I/O
stalls and OOM kills on both Sepolia and Platåberget (spec 013 §1, spec 015).

**Data model (how fukuii lays state out).** Execution reads every account and slot by walking the hexary trie, one point
read per level (research F3); the flat account and slot stores exist but are written by SNAP only and **never read by
execution**, and on ETH they are frozen at the pivot once SNAP completes (F2, F3). Basic pruning stores every trie node
with a reference count and rewrites the whole node on any count change, plus a per-node snapshot and a per-block death
row that is rewritten on every update call (F2). On the Path scheme a state node is written three times during SNAP:
path-keyed, flat, and copied into the hash-keyed store by `PathToHashExporter`. None of this is RocksDB's doing; an
engine swap alone leaves it in place.

**For the zkVM work.** Spec 019 compiles spec 017's `StatelessExecutor` for a bare-metal target. That closure must stay
free of any storage engine (017 DC-15). Today it is: the host/guest boundary is the witness. This spec must keep it so
while changing everything beneath the host's two state gateways.

### What the capability gateways make possible

Spec 016 made SNAP's storage a type, `StateIngest`. Spec 017 makes execution's storage a type, `StateAccess`, and
installs the witness recorder beneath it. After 017 every state read and write in the node goes through one of those two
traits, each with its own durability policy (016 FR-045). That is the precondition for swapping the engine without
touching sync or execution code: **the swap happens below the gateways, the gateways do not move.** Doing it earlier
would mean chasing storage handles through 14,000 lines of SNAP code that spec 016 is still splitting; doing it inside
016 or 017 would bundle a data-plane rewrite into refactors whose whole value is "zero behaviour change".

## Strategic motivation

- **Deterministic import latency on the host.** The L1-zkEVM pipeline (017 §Strategic motivation) makes the stateful EL
  the producer of witnesses for provers; a host that pauses for tens of seconds under compaction is a host that misses
  proving deadlines. A copy-on-write B+tree with one writer, MVCC readers and no background work has no stall source
  outside its own commit.
- **Read amplification is the execution loop's floor.** Caches lower the cost of trie walks; a flat read path removes
  them. With Amsterdam's block-level access lists (EIP-7928) the write set is known up front, so the trie can be updated
  as a commitment structure from the write set rather than walked for every read.
- **One layout for both chains.** ETC runs the Hash scheme, ETH the Path scheme, and the two share a hash-keyed node
  store only because execution needs one. A flat state plus one path-keyed trie serves both chains from one layout.
- **The guest stays clean.** The host owns the data tier; the guest owns re-execution over a witness. This spec is the
  host side of that split.

## Goals

- G-1 A libmdbx-backed `DataSource` (`MdbxDataSource`) behind the existing contract, selectable by `db.data-source`,
  with the shared contract test suite green on all implementations.
- G-2 No stall coupling: a write in progress never blocks a read, and no engine background work exists to stall on.
- G-3 Per-capability durability on the new engine: `StateAccess` always durable, `StateIngest` relaxable, with the
  016/017 DC-1 barrier before SNAP completion.
- G-4 A flat execution read path: accounts by hashed address, slots by hashed account and slot, read directly by the
  world state proxy under `StateAccess`.
- G-5 One trie as a commitment structure, path-keyed, updated from each block's write set, with per-block changesets
  replacing reference counts for rollback and pruning.
- G-6 Witness capture that survives the flat read path: a key-set recorder plus proof generation from the trie,
  byte-identical to spec 017's node-level recorder on the differential set.
- G-7 One layout for ETC and ETH, with `storage-scheme` retired.
- G-8 RocksDB removed from the build one minor release after the new engine becomes the default, with operator
  migration guidance for both chains.

## Non-goals

- **NG-1 Changing any consensus output.** State roots, receipts, gas, hashes and the witness wire format are
  byte-for-byte unchanged on both chains. This spec changes where bytes live, never what they are.
- **NG-2 Changing the `StateIngest` or `StateAccess` signatures.** New members are allowed only if a member cap
  justification is written (016 FR-043 style). No SNAP or ledger code moves for this spec.
- **NG-3 The zkVM guest.** Spec 019. This spec only guarantees the stateless closure gains no engine dependency.
- **NG-4 An in-place converter from the RocksDB layout to the new layout for state.** Operators resync (ETC: checkpoint
  archive, hours; ETH: spec 013 snapshot or SNAP). A chain-data converter (headers, bodies, receipts) is OQ-4.
- **NG-5 Within-block parallel execution.** Flat reads make it cheaper later; it is not done here (017 NG-1).
- **NG-6 Historical state at arbitrary roots in archive mode at today's speed.** Archive mode keeps every changeset, so
  any historical state is reconstructible, but point reads far in the past are slower than the hash-keyed layout gave.
  History indices for fast point reads are a later spec (OQ-3).
- **NG-7 Binary state tree (EIP-7864).** The trie stays the hexary Keccak MPT; the layout is chosen so a different
  commitment structure is a backend swap, as 017 DC-12 requires.
- **NG-8 Tuning RocksDB further.** Spec 015 did that. This spec's Phase 0 measures today's engine only to set the
  baseline the new one must beat.

## Scope

Two tracks, strictly ordered. Each lands in behaviour-neutral slices with the import differential test (SC-001) green.

**Track E — the engine, behind the contract (G-1, G-2, G-3, G-8).**

1. Contract cleanup: move `IterationError` onto the trait, promote `seekFrom`, `approximateKeyCount` and
   `iterateSyncRange` onto the trait so the three downcasts in `FlatAccountStorage`, `FlatSlotStorage` and
   `BfsQueueStorage` go away, make `compactRange` a documented no-op on engines without compaction, and add an
   engine-neutral `StorageMetrics` seam replacing `RocksDbCacheMetrics.register`.
2. `MdbxDataSource` over libmdbx's amalgamated C source through JDK 25's Foreign Function & Memory API (`java.lang.foreign`,
   `jextract`-generated bindings, vendored under `db/mdbx/`), one named sub-database per `Namespace`, one write
   transaction per `update`, snapshot read transactions for reads, bounded cursor windows for scans.
3. Durability mapping: `update` = commit with `MDBX_SAFE_NOSYNC`-class semantics where the capability's policy allows,
   `updateSync` = fully synced commit; `StateAccess` writes always synced-on-commit or WAL-equivalent (DC-3).
4. The shared `DataSourceTestBehavior` suite, golden-bytes of every stored format that is kept, and the two-engine
   import differential (SC-001).
5. Default flip for fresh datadirs, then RocksDB removal one minor release later (DC-11).

**Track F — the flat data model (G-4, G-5, G-6, G-7).**

6. Changesets: per block, the previous value of every flat key and trie node the block changed, keyed by block number,
   replacing `ReferenceCountNodeStorage`'s stored counts, snapshots and death rows. Rollback applies them in reverse;
   pruning deletes those older than `pruning.history`; archive keeps all (NG-6).
7. Flat execution reads in `InMemoryWorldStateProxy` under `StateAccess`: `getAccount` and `getStorage` read the flat
   stores; the trie is updated from the block's write set at commit and yields the state root.
8. Path-aware node resolution in `mpt/`: the trie resolves a child by `(path, hash)` and verifies the hash, so one
   path-keyed node store serves both chains. Hashing and encoding are untouched (DC-2; `forge` and `beacon` review).
9. Key-set witness recorder plus proof walk (DC-7), with the 017 differential test as the oracle (SC-007), amending 017
   DC-8.
10. SNAP ingest writes each node once (path-keyed) and each leaf once (flat); `PathToHashExporter` is retired; the Hash
    scheme is retired and ETC moves onto the unified layout (`forge` sign-off; DC-9).

Track F cannot start before Track E's SC-001 is green on both chains, and item 9 must be green before item 7 is enabled
by default.

## Gates *(mandatory; nothing past a gate proceeds until it reports)*

**GT-0 — Attribution spike (Phase 0, off the node host).** Reproduce a stall on today's engine under a synthetic import
load on a Mordor or Platåberget datadir, with a read probe on another thread. Report, per stall: time readers waited
under `dbLock`, and time the engine itself stalled (RocksDB `STALL_MICROS` and the flush/compaction log). Then repeat
with `dbLock` replaced by a close-fence only. The result decides how much of SC-004 an engine swap buys versus a lock
fix, and whether the lock fix ships as its own PR first (research §8, alternative 2). Days, not weeks.

**GT-1 — Binding spike (Phase 0, off the node host).** Open a libmdbx environment from Scala 3 on JDK 25 through FFM:
create 20 sub-databases, put and get, cursor range scan, a write transaction of 10,000 keys with each sync flag, a long
read transaction while a writer commits 1,000 times, and a clean close. Linux x86_64 and aarch64. Report: works,
works-with-constraints (name them), or does not work. If it does not work, GT-1 is repeated with `lmdbjava` as the
fallback before any further 018 work; if that also fails, Track E is closed with findings and Track F is re-planned on
RocksDB (research §8, alternative 1).

**GT-2 — Constitution amendment.** The constitution names "RocksDB for storage" among the core libraries and requires
new dependencies to be justified in the plan. Before `MdbxDataSource` merges, the constitution is amended through
`/speckit-constitution` with ADR **INF-007** under `docs/adr/infrastructure/` recording the engine decision, the FFM
binding decision and the vendoring of libmdbx's amalgamated source. The spikes run on throwaway branches before the
amendment, because they merge nothing.

**GT-3 — Layout decision (before Track F).** One layout for both chains is a one-way door for ETC operators (resync).
The plan presents the user the measured footprint and import figures from Track E, the resync time on Mordor and ETC
mainnet via checkpoint archive, and the alternative of keeping ETC on a hash-keyed node store. Track F item 10 starts
only on the user's explicit decision (OQ-1).

## Design constraints *(mandatory)*

**DC-1 — The gateways do not move.** Every state read and write continues to flow through `StateIngest` (016) and
`StateAccess` (017). This spec changes the implementations beneath `DataSource` and the typed stores in `db/storage/`,
and the world state proxy's read path under `StateAccess`. No file under `blockchain/sync/snap/` changes except to
delete the publish step (item 10). The 016 ratchet (`scripts/snap-split/verify.py`) and the 017 dependency checks stay
green throughout.

**DC-2 — Consensus outputs are byte-identical on both chains.** State roots, receipt roots, gas, block hashes, and the
witness wire format do not change. Any change in `mpt/` is to node **resolution** (addressing a node by path and
verifying its hash), never to hashing, RLP encoding or the trie algorithms. `forge` signs off ETC, `beacon` ETH, before
any `mpt/` or `ledger/` change merges; ethereum/tests and the Mordor/Sepolia import differential (SC-001) are the
oracles.

**DC-3 — Durability is per capability and at least today's.** `StateAccess` writes commit durably (synced commit, or an
equivalently crash-safe commit mode the engine documents as durable). `StateIngest` may run relaxed (016 FR-045), and the
DC-1 barrier of 016/017 (a synced commit before `completeSnapSync`, on both chains) is **implemented** by this spec if
016 T056 has not already done so. The `inmemory` pruning mode's durability gap (INF-006 §4) is closed or the mode is
retired (OQ-5).

**DC-4 — Writes never block reads.** Reads run in snapshot read transactions and take no lock a writer takes. The
process-wide `dbLock` shrinks to a close-fence (an open/closed state plus in-flight count) or disappears. SC-004 tests
it under a saturating write load.

**DC-5 — Every read transaction is bounded.** A long-lived reader pins old pages and grows the database file until it
ends. Every `iterate()` stream, `scanRange` window, heal-walk chunk and RPC read is a bounded read transaction (bounded
by key window or by count, never by "until the consumer stops"). An **oldest-reader lag** gauge (blocks or transactions
behind head) is exported, and a reader older than a configured threshold is logged at WARN with its origin. A read
transaction may never be held across an actor message boundary or an `await`.

**DC-6 — Bulk ingest is sorted and batched.** Random inserts into a copy-on-write B+tree cost page splits along the
path. `StateIngest` writes arrive sorted by key from SNAP ranges and are committed in batches of the size the 016 storage
protocol already prescribes (5K–10K keys); the healing path sorts its batch before commit. No per-key transactions on
any ingest path.

**DC-7 — Witnesses are produced from the access set, and must equal 017's.** Under the flat read path the world records
the **access set** (hashed account keys, hashed slot keys per account, code hashes, ancestor headers) instead of trie
nodes. At witness finalization the proof nodes are generated by walking the trie for every recorded key (the way reth's
`debug_executionWitness` and geth's stateless package do). The wire format is unchanged (017 DC-11). This **amends 017
DC-8**: a capturing world with flat reads is constructible, and the DC-8 property ("no read may bypass what the witness
needs") is kept by the key-set recorder plus the proof walk rather than by forbidding flat reads. The 017 differential
test and 017 DC-14's BAL self-check are the oracles (SC-007). Until SC-007 is green the flat read path stays off by
default and 017's node recorder stays in place.

**DC-8 — The stateless closure gains nothing.** `StatelessExecutor` and its transitive closure (017 DC-15) gain no
dependency on `db/`, the FFM bindings, the flat stores, the changeset store or the key-set recorder's host plumbing. The
017 DC-15 dependency check is extended to name `db.mdbx` and `java.lang.foreign` and stays green. The witness is the
only thing that crosses from host to guest.

**DC-9 — One layout, decided at GT-3.** The target is one path-keyed trie plus flat accounts and slots plus
changesets, for both chains. `storage-scheme` is deprecated (accepted and ignored with a WARN for one minor release,
then rejected). Existing datadirs on the old layout are detected at start and refused with a message naming the resync
procedure; no silent mixing of layouts in one datadir.

**DC-10 — Memory is accounted, not assumed.** The engine's memory is the OS page cache over the mapped file, which the
container cgroup counts as file memory. The plan publishes a sizing formula (`-Xmx` + direct + metaspace + page-cache
working set ≤ host) replacing spec 015's RocksDB budget, and the node exports mapped-file size, resident mapped pages
and dirty pages as gauges. The 16 GiB / 4-core host remains the target (spec 013, 015).

**DC-11 — The transition window is bounded.** Both engines compile in for at most one minor release after the default
flips to the new engine; then RocksDB, `rocksdbjni`, spec 015's budget config and `RocksDbCacheMetrics` are removed in
one PR. `db.data-source = "rocksdb"` is rejected at start after removal with a message naming the migration runbook.

**DC-12 — Every claim is measured against the baseline.** Phase 0 records, on the same datadir and host class, for
today's engine: p50/p99 `[IMPORT-TIMING]` per block, longest read wait, bytes written per block (engine write bytes ÷
block state bytes), database size, RSS and cgroup `memory.current`, and SNAP `[STORAGE-PERF]` rate. Every Track E and F
slice reports the same figures. No slice merges on a claim it did not measure (constitution III and the working
discipline's evidence standard).

## User Scenarios & Testing *(mandatory)*

### User Story 1 — An operator imports blocks with no stall (Priority: P1)

An operator runs a synced node on the 16 GiB host under heavy block validation (Sepolia at head, or Mordor with a
backlog). Block import time is stable and no RPC, Engine API or SNAP-serving request waits on a write.

**Why this priority**: this is the runtime limitation the spec exists to remove, and the property the zkVM host needs.

**Independent Test**: SC-004's saturating-write test, and a 24-hour soak on Platåberget and Mordor with the DC-12
figures compared to the Phase 0 baseline.

**Acceptance Scenarios**:

1. **Given** a write transaction in progress, **When** an RPC `eth_getBalance` arrives, **Then** it is answered from a
   snapshot without waiting for the write.
2. **Given** a 24-hour soak, **When** the DC-12 figures are compared to the baseline, **Then** the longest read wait is
   below the SC-004 bound and p99 import time does not exceed the baseline.

### User Story 2 — State roots are identical under either engine (Priority: P1)

A maintainer imports the same block range on two datadirs, one on RocksDB and one on the new engine, on Mordor and on
Platåberget, and compares every post-state root, receipt root and `[IMPORT-TIMING]` line.

**Why this priority**: constitution I. Nothing else in this spec matters if this fails.

**Independent Test**: the two-engine import differential (SC-001), run per Track E slice, plus ethereum/tests.

**Acceptance Scenarios**:

1. **Given** the same block range, **When** imported under both engines, **Then** every state root and receipt root is
   equal and ethereum/tests passes on the new engine.

### User Story 3 — SNAP syncs a fresh node on the new engine (Priority: P1)

An operator starts a fresh Sepolia node on the new engine. SNAP download, healing, finalization and the transition to
`Full` mode complete, resume works across a restart mid-phase, and a crash during finalization never leaves execution
reading undurable state.

**Why this priority**: SNAP is the default sync path and `StateIngest` is the first gateway the engine sits under.

**Independent Test**: a Mordor and a Platåberget SNAP run on the new engine, with the 016 US-1 restart test and the 017
DC-1 crash test.

**Acceptance Scenarios**:

1. **Given** a node mid-healing on the new engine, **When** it restarts, **Then** it resumes at the saved frontier
   (016 US-1 holds).
2. **Given** a crash injected after the publish and before `snapSyncDone`, **When** the node restarts, **Then** it
   either completes finalization from durable state or redoes the publish; execution never reads a missing node.

### User Story 4 — Execution reads flat state and the witness is unchanged (Priority: P2)

With the flat read path enabled, a maintainer captures witnesses for the 017 differential set and compares them with
witnesses captured by 017's node-level recorder on the same blocks.

**Why this priority**: it is the point where the data model changes and where the witness pipeline could silently
break. It is P2 only because it depends on Track E and on 017's recorder existing.

**Independent Test**: SC-007, and the BAL self-check over 10,000 Amsterdam blocks (017 SC-007).

**Acceptance Scenarios**:

1. **Given** flat reads on, **When** a block in the differential set is captured, **Then** the witness is byte-identical
   to 017's recorder's witness and re-executes to the header's state root.
2. **Given** flat reads on, **When** the same block is imported, **Then** the `[IMPORT-TIMING]` figure is at or below
   the Track E figure for that block.

### User Story 5 — An ETC operator moves to the unified layout (Priority: P2)

An ETC mainnet operator on the Hash scheme upgrades. The node refuses to open the old datadir with a message naming the
checkpoint resync, the operator resyncs from a checkpoint archive in hours, and mining, RPC and `forge`-reviewed
consensus behave exactly as before.

**Why this priority**: it is the one-way door of DC-9 and it needs the user's decision at GT-3.

**Independent Test**: a Mordor resync from a checkpoint archive onto the unified layout, then a mining run and the ETC
ethereum/tests job.

### Edge Cases

- A read transaction held across an actor message or an `await` (DC-5): must be impossible by construction or caught
  by the oldest-reader gauge test.
- A write transaction larger than the engine's dirty-page limit (a huge SNAP batch, a giant block): the engine spills
  or the batch is split; either way the commit stays atomic.
- Disk full during commit: the commit fails loudly, the previous state is intact (no partial page writes visible).
- Power loss between a relaxed `StateIngest` commit and the DC-3 barrier: SNAP resumes from its last durable
  checkpoint; nothing execution reads is missing.
- An old-layout datadir opened by a new build (DC-9); a new-layout datadir opened by an old build.
- `pruning.mode = "inmemory"` (OQ-5).
- A reorg deeper than `pruning.history` under changesets: refused loudly, as today's reference counting refuses it.
- The file-memory cgroup pressure case: page cache evicted under JVM heap pressure, import slows but never fails
  (DC-10 gauges show it).
- A Windows or macOS developer build: the FFM binding must load or fail with a clear message.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001** The system MUST provide `MdbxDataSource`, a `DataSource` implementation over libmdbx, selectable with
  `db.data-source = "mdbx"`, with one named sub-database per `Namespace`.
- **FR-002** `MdbxDataSource` MUST bind libmdbx through the JDK Foreign Function & Memory API over the vendored
  amalgamated source; no JNI and no third-party binding artifact (GT-1 decides; `lmdbjava` is the named fallback).
- **FR-003** The `DataSource` trait MUST become engine-neutral: `IterationError` moves onto the trait; `seekFrom`,
  `approximateKeyCount` and `iterateSyncRange` become trait members with `EphemDataSource` implementations; no code
  outside `db/dataSource/` names a concrete engine class.
- **FR-004** The shared `DataSourceTestBehavior` suite MUST pass on `EphemDataSource`, `RocksDbDataSource` and
  `MdbxDataSource`, extended with the DC-5 reader-bound behaviour and the DC-3 durability modes.
- **FR-005** `update` on `MdbxDataSource` MUST be one atomic write transaction; `updateSync` MUST be a fully synced
  commit; the `StateIngest` durability policy MUST map to a relaxed commit mode on ingest writes only (DC-3).
- **FR-006** A read MUST never wait on a write in progress (DC-4); the process-wide read/write lock MUST be reduced to
  a close-fence.
- **FR-007** Every read transaction MUST be bounded (DC-5); the system MUST export an oldest-reader lag gauge and log a
  reader past the configured threshold with its origin.
- **FR-008** The system MUST export engine-neutral storage gauges: database file size, mapped resident bytes, dirty
  bytes, read and write transaction durations (p50/p99), and oldest-reader lag, through a `StorageMetrics` seam that
  replaces `RocksDbCacheMetrics.register`.
- **FR-009** The two-engine import differential (SC-001) MUST run in CI on a fixed Mordor range and a fixed Platåberget
  range, comparing every state root and receipt root.
- **FR-010** The system MUST provide a changeset store: per block, the previous value of every flat key and trie node
  the block changed; rollback MUST apply changesets in reverse; basic pruning MUST delete changesets older than
  `pruning.history`; archive MUST keep all.
- **FR-011** Under `StateAccess` with the flat read path on, `getAccount` and `getStorage` MUST read the flat stores,
  and the state root MUST be computed by updating the path-keyed trie from the block's write set; the root MUST equal
  the trie-walk root for every block (SC-001 under flat reads).
- **FR-012** `mpt/` node resolution MUST accept a path and verify the resolved node's hash; hashing, encoding and trie
  algorithms MUST NOT change (DC-2).
- **FR-013** In capture mode with flat reads, the world MUST record the access set and the witness MUST be generated by
  a trie proof walk over it; the result MUST be byte-identical to 017's node recorder on the differential set (DC-7,
  SC-007). This FR amends 017 DC-8 and FR-003a.
- **FR-014** The stateless closure MUST gain no dependency on `db/`, `java.lang.foreign` or the new stores (DC-8),
  enforced by extending 017's DC-15 check.
- **FR-015** SNAP ingest on the unified layout MUST write each trie node once (path-keyed) and each leaf once (flat);
  `PathToHashExporter` MUST be retired with its 016 FR-044 durability property re-expressed as the DC-3 barrier.
- **FR-016** `storage-scheme` MUST be deprecated per DC-9; a datadir on the old layout MUST be refused at start with a
  message naming the resync procedure.
- **FR-017** RocksDB MUST be removable in one PR one minor release after the default flips (DC-11); the removal PR MUST
  delete `rocksdbjni`, spec 015's budget config and `RocksDbCacheMetrics`.
- **FR-018** The operator runbooks and skills that name `rocksdb/` (`fukuii-backup-restore`, `fukuii-disk-management`,
  `multi-instance.conf`, `first-start`, `known-issues`) MUST be updated for the new datadir layout and the resync
  procedure, before the default flips.
- **FR-019** Every Track E and Track F slice MUST report the DC-12 figures against the Phase 0 baseline in its PR.
- **FR-020** The constitution MUST be amended (GT-2) with ADR INF-007 before `MdbxDataSource` merges.

### Key Entities

- **`MdbxDataSource`**: the libmdbx `DataSource`; sub-database per namespace; write txn per `update`; snapshot read
  txns.
- **`StorageMetrics`**: the engine-neutral gauge seam.
- **Changeset**: per block, the previous values the block overwrote (flat keys and trie nodes), keyed by block number;
  the unit of rollback and pruning.
- **Flat state**: accounts by `keccak(address)` (namespace `a`), slots by `keccak(address) ++ keccak(slot)` (namespace
  `d`), read by execution under `StateAccess`.
- **Path-keyed trie**: the one commitment structure, nodes addressed by nibble path and verified by hash (namespaces
  `t`, `u`).
- **Access set**: the hashed keys, code hashes and ancestor headers a block's execution touched; input to the proof walk
  that produces the witness.
- **Oldest-reader lag**: how far behind head the oldest open read transaction is; the DC-5 health signal.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001** On the fixed Mordor and Platåberget ranges, every state root and receipt root is equal under RocksDB and
  under the new engine, and ethereum/tests passes on the new engine. Re-checked under flat reads (FR-011).
- **SC-002** Engine bytes written per block state byte (write amplification) on the new engine with changesets is at
  most one third of the Phase 0 baseline on basic pruning, on both chains.
- **SC-003** p99 `[IMPORT-TIMING]` over the 24-hour soaks is at or below the Phase 0 baseline, and the maximum is at
  most twice p99 (no multi-second outliers).
- **SC-004** Under a saturating write load, no read waits more than 10 ms on the engine (measured at the `DataSource`
  boundary), where the Phase 0 baseline shows waits of seconds.
- **SC-005** Database size on disk for the same synced state is at most 1.5× the RocksDB size on Sepolia and on Mordor
  (no compression, offset by no stored reference counts and no triple-written nodes); the figure is published.
- **SC-006** RSS plus page-cache working set under import fits the 16 GiB host with the published formula, with no OOM
  kill over the soaks.
- **SC-007** On the 017 differential set, witnesses from the key-set recorder are byte-identical to witnesses from 017's
  node recorder, and the BAL self-check reports zero gaps over 10,000 Amsterdam blocks.
- **SC-008** The oldest-reader lag gauge never exceeds the configured threshold over the soaks, and the DC-5 test
  catches an injected unbounded reader.
- **SC-009** SNAP `[STORAGE-PERF]` rate on the new engine is at or above the baseline on both chains, and the 016 US-1
  restart test and the 017 DC-1 crash test pass.
- **SC-010** The 016 ratchet and the 017 dependency checks (DC-4, DC-15 extended per DC-8) stay green on every slice.
- **SC-011** After the removal PR, no file in the repo names `org.rocksdb`, and the `db/` line count is at or below
  today's 5,616 (one engine, fewer caches).

## Assumptions

- Spec 016 is complete and spec 017 has landed `StateAccess`, `RecordingMptStorage` and the differential test before
  Track E implementation starts. Phase 0 needs only spec 016.
- JDK 25 is the runtime (constitution), so `java.lang.foreign` is available without preview flags.
- libmdbx's amalgamated source builds with the system C compiler on the CI runners and the release image; the build
  step is added to the Docker and nix builds.
- The 16 GiB / 4-core host class stays the target; the soaks run on Platåberget (ETH, PoS) and Mordor (ETC, PoW), never
  on the live Sepolia node (016 US-4).
- The `[IMPORT-TIMING]` and `[STORAGE-PERF]` instrumentation is reused for DC-12, not replaced.
- `vault` owns the engine and the stores, `forge` and `beacon` own the `mpt/` and `ledger/` changes for their chains,
  `eye` validates, `prism` reviews non-consensus code, `conduit` confirms `debug_executionWitness` is unchanged.
- Besu Bonsai, reth and Erigon layouts were read as prior art for the flat + changeset design; specifics are re-verified
  in Phase 0 (R-5) before the layout ADR is written.

## Open Questions

- **OQ-1 (GT-3)** Does ETC move onto the unified layout in the first release, or keep a hash-keyed node store as an
  option? Recommended: unify. The cost is a resync for ETC operators (hours from a checkpoint archive); the benefit is
  one layout, one SNAP writer, one witness path, and the removal of `PathToHashExporter`.
- **OQ-2** Sub-database granularity: one per namespace (20) as today, or merge the small metadata namespaces? Default:
  one per namespace, so golden-bytes and the typed stores do not change.
- **OQ-3** Archive mode: keep all changesets only (NG-6), or add history indices for fast historical point reads now?
  Default: changesets only; indices are a later spec if ETC archive operators need them.
- **OQ-4** A converter for chain data (headers, bodies, receipts, tx mapping) so a resync re-downloads only state?
  Default: no converter in the first release; measure how long chain-data download takes on each chain first.
- **OQ-5** `pruning.mode = "inmemory"`: close its durability gap on the new engine, or retire the mode? Recommended:
  retire; changesets make the in-memory reference-count cache redundant.
- **OQ-6** Relaxed commit mode for `StateIngest`: `MDBX_SAFE_NOSYNC` (metadata consistency kept, last transactions
  may be lost) is the recommended default; `UTTERLY_NOSYNC` only if GT-0/GT-1 show it matters and SNAP resume covers it.
- **OQ-7** Does the lock fix (research §8, alternative 2) ship as a standalone PR on RocksDB before Track E, if GT-0
  attributes most of the read wait to `dbLock`? Recommended: yes, it is small and it is a real fix for today's nodes.
- **OQ-8** Page-cache sizing guidance for operators who run a CL client on the same 16 GiB host.
- **OQ-9** Whether the flat stores should carry a per-key "last modified block" to make 017's state-history window
  (DC-16, about 64 blocks) a flat lookup rather than a changeset replay.

## Related specs

- **Spec 015** (RocksDB memory budget): superseded by DC-10 once RocksDB is removed.
- **Spec 016** (SNAP split, `StateIngest`): prerequisite; this spec sits under its gateway; the 016 ratchet must stay
  green.
- **Spec 017** (`StateAccess`, witnesses): prerequisite; this spec sits under its gateway, amends its DC-8 (DC-7
  here), and extends its DC-15 check (DC-8 here).
- **Spec 013** (state snapshots): the resync accelerator for ETH operators under DC-9; its flat-state export format is
  aligned with this spec's flat stores (OQ for 013).
- **Spec 019** (zkVM guest): downstream; unaffected except through DC-8's guarantee.

## Known tasks (seed for /speckit-tasks)

- **T-000 (Phase 0, GT-0)** The attribution spike, with the read-probe harness checked in under `Benchmark/`.
- **T-001 (Phase 0, GT-1)** The FFM binding spike on a throwaway branch; the report names every constraint found.
- **T-002 (Phase 0)** The DC-12 baseline on today's engine, on a Mordor and a Platåberget datadir, published in
  `research.md` §12 (to be added).
- **T-003 (Phase 0, R-5)** Read Besu Bonsai's trie-log, reth's changesets and history indices, Erigon's domains and
  geth's PBSS diff layers, and write the layout ADR draft (INF-008) that GT-3 decides on.
- **T-004 (GT-2)** The constitution amendment and ADR INF-007.
- **T-005 (Track E, contract cleanup)** FR-003, in its own behaviour-neutral PR with the 84 `EphemDataSource` tests
  green and the three downcasts gone.
- **T-006 (Track F, 017 amendment)** The DC-7 amendment to 017's DC-8 and FR-003a, as a docs PR on 017, approved by
  the user before Track F item 7 is enabled.
