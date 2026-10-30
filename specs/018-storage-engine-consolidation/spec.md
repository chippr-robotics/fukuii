# Feature Specification: Storage Architecture for Gigablock-Scale Execution (Engine Consolidation under the Capability Gateways)

**Feature Branch**: `018-storage-engine-consolidation` (spec written on `claude/fukuii-database-consolidation-h3bqt9`)

**Created**: 2026-10-17

**Status**: **Draft, ready for `/speckit-clarify`.** The Phase 0 evaluation is done ([research.md](./research.md)); the
plan ([plan.md](./plan.md)) is drafted to the point where `/speckit-tasks` can run once the gates below report. The
Design constraints and Gates sections are binding on that later work. **Revised 2026-10-17 (same day)** after the user's
direction: the hot-tier engine is **not fixed to libmdbx**; it is chosen by a measured bake-off (GT-1) against the
requirements in DC-13; networked engines (ClickHouse, ScyllaDB) are evaluated for a **cold tier** rather than the hot
path; the sizing target is **gigablock** execution; and **ETC is brought along** onto the unified layout (user decision,
replaces the former GT-3). **Revised again 2026-10-17 (read-serving)** after an external assessment of fukuii's
suitability for G-SIB-scale risk analysis, traces and concurrent reads was checked against the code (research §16):
a read-serving track (Track R), the enterprise analytics user story (US-8), the deployment topology (DC-16), the
`dbLock` close-fence and the fixed-width `UInt256` pulled forward as named tasks (T-010, T-011), and Erigon and Reth
added as comparators in the GT-1 harness (FR-028).

**Sequence**: spec 016 (SNAP split, `StateIngest`) → spec 017 (`StateAccess`, witnesses) → **this spec** → spec 019
(zkVM guest). **Implementation starts after spec 017 completes** (user decision, 2026-10-17). Phase 0 (measurement,
the engine bake-off, the cold-tier assessment; all off the node host) may start as soon as spec 016 completes, because
it merges nothing.

**Depends on**: spec 016's `StateIngest` (landed, T055; healing-store members at C-H1) and spec 017's `StateAccess`
(G-1), `RecordingMptStorage` (G-2) and the differential witness test (FR-006). This spec changes the storage
**beneath** those two gateways and must not change their signatures.

**Input**: User request, 2026-10-17: evaluate fukuii's database use and specify its move off RocksDB before the zkVM
guest work, as its own spec between 017 and the guest. Clarified the same day: "libmdbx isn't a hard requirement. We
should consider other database patterns as well that would make good use of Scala 3 architecture, like ScyllaDB and
ClickHouse. The goal is to make the system more efficient as we will need to deal with much larger blocks and execution
if ETH moves to gigablocks. ETC should be brought along as well. There are few ETC operators and this will be a
substantial optimization."

## Problem

The evaluation ([research.md](./research.md)) found the pain already recorded in the repo, and it found that the
problem has two layers that today's code couples only loosely:

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
row rewritten on every update call (F2). On the Path scheme a state node is written three times during SNAP:
path-keyed, flat, and copied into the hash-keyed store by `PathToHashExporter`. None of this is RocksDB's doing; an
engine swap alone leaves it in place. The state root is computed by re-walking and re-hashing the modified trie paths
after execution, serially, which at today's block sizes is already a large share of import time and at gigablock sizes
is the bottleneck every high-throughput client has had to remove (research §14).

**At gigablock scale.** If ETH raises the gas limit by an order of magnitude or more, a block carries on the order of
10⁵–10⁶ state reads and 10⁴–10⁵ state writes. Every one of today's costs multiplies: trie walks per read, node rewrites
per write, a serial state root, and a lock that lets any of them stall the whole node. No embedded engine choice fixes
that on its own; the data model and the concurrency model do, and the engine must not get in their way (DC-13).

**Under read load.** Enterprise users (risk engines, compliance, auditors) ask for `debug_trace*` over blocks and
ranges, `eth_getLogs` over long ranges and `eth_call` at volume, concurrently with import. Today the process-wide
`dbLock` means an import or a flush stall blocks every one of those reads (F1); trace re-execution allocates a `BigInt`
per EVM arithmetic op (`UInt256` over `BigInt`, 485 `BigInt` references in `vm/`); trace and log results are serialised
through json4s (reflection-based) in 40 of 60 `jsonrpc/` files; and nothing bounds trace concurrency separately from
import. An external assessment claimed the opposite on each point; research §16 checks it against the tree. The
storage work removes the lock; the read-serving track (Track R) removes the rest.

**For the zkVM work.** Spec 019 compiles spec 017's `StatelessExecutor` for a bare-metal target. That closure must stay
free of any storage engine (017 DC-15). Today it is: the host/guest boundary is the witness. This spec must keep it so
while changing everything beneath the host's two state gateways.

### What the capability gateways make possible

Spec 016 made SNAP's storage a type, `StateIngest`. Spec 017 makes execution's storage a type, `StateAccess`, and
installs the witness recorder beneath it. After 017 every state read and write in the node goes through one of those two
traits, each with its own durability policy (016 FR-045). That is the precondition for changing the storage without
touching sync or execution code: **the change happens below the gateways, the gateways do not move.** Doing it earlier
would mean chasing storage handles through 14,000 lines of SNAP code that spec 016 is still splitting; doing it inside
016 or 017 would bundle a data-plane rewrite into refactors whose whole value is "zero behaviour change".

### Which engines belong where

The user named ScyllaDB and ClickHouse as patterns to consider. The evaluation (research §13) places them:

- **Neither can be the hot state store.** Both are separate processes reached over a socket, and both are merge-based
  (ScyllaDB: memtables, SSTables and compaction; ClickHouse: MergeTree parts and background merges). A loopback round
  trip is two to three orders of magnitude slower than an in-process page read, and at 10⁵–10⁶ reads per gigablock
  that is the whole block time many times over. Every production EL keeps state in an embedded, in-process store for
  this reason (RocksDB, Pebble, MDBX). The hot tier stays embedded; **which** embedded engine is the bake-off's question.
- **ClickHouse is a strong candidate for a cold tier**: receipts and logs (`eth_getLogs` over long ranges is a columnar
  scan, not a point read), execution witnesses (017's slow-tier witness store), changesets older than the pruning window,
  traces, and history after expiry (#1483). It is optional, operator-run, and a node must be complete without it.
- **ScyllaDB has no fit**: it is a distributed wide-column key-value store, and the EL is a single node with no
  analytical queries that need it. It is recorded as assessed and declined.

## Strategic motivation

- **Deterministic import latency on the host.** The L1-zkEVM pipeline (017 §Strategic motivation) makes the stateful EL
  the producer of witnesses for provers; a host that pauses for tens of seconds under compaction misses proving
  deadlines. An engine with no background work has no stall source outside its own commit; a data model with no
  per-write node rewrites has fewer commits to stall on.
- **Gigablocks are a data-model problem first.** The clients that have published gigagas-per-second results removed the
  serial state root (a sparse trie updated during execution and cached across blocks), moved execution reads to flat
  state, and made the engine a snapshot-reading, batch-committing store beneath both. Block-level access lists
  (EIP-7928, live on Amsterdam) make the write set known before execution, which is what parallel state-root and
  parallel execution key on. This spec builds the storage side of that; parallel execution itself stays out of scope
  (NG-5) but is not precluded (DC-13).
- **One layout for both chains, and ETC comes along.** ETC runs the Hash scheme, ETH the Path scheme, and the two share
  a hash-keyed node store only because execution needs one. ETC operators are few and the gain for them is the same
  optimisation; the user has decided they resync onto the unified layout (DC-9).
- **Scala 3 at the storage boundary.** The engine sits behind a trait; the Scala 3 features the repo already uses
  (opaque types, `given` codecs, `inline`, enums) can make that boundary zero-copy, allocation-free on the hot path and
  type-safe about the single-writer invariant (DC-12), whichever engine wins.
- **The guest stays clean.** The host owns the data tiers; the guest owns re-execution over a witness.

## Goals

- G-1 A hot-tier engine chosen by a measured bake-off (GT-1) against DC-13, implemented as a `DataSource` behind the
  existing contract, selectable by `db.data-source`, with the shared contract test suite green on all implementations.
- G-2 No stall coupling: a write in progress never blocks a read, and no engine background work exists to stall on (or,
  if the chosen engine has background work, it is shown by measurement never to block a read or a commit beyond the
  SC-004 bound).
- G-3 Per-capability durability on the new engine: `StateAccess` always durable, `StateIngest` relaxable, with the
  016/017 DC-1 barrier before SNAP completion.
- G-4 A flat execution read path: accounts by hashed address, slots by hashed account and slot, read directly by the
  world state proxy under `StateAccess`.
- G-5 One trie as a commitment structure, path-keyed, updated from each block's write set, with per-block changesets
  replacing reference counts for rollback and pruning, and with a state-root computation that runs in parallel with
  execution and is cached across blocks.
- G-6 Witness capture that survives the flat read path: a key-set recorder plus proof generation from the trie,
  byte-identical to spec 017's node-level recorder on the differential set.
- G-7 One layout for ETC and ETH, with `storage-scheme` retired; ETC on the unified layout in the first release.
- G-8 A cold-tier seam (`ColdStore`) for receipts/logs, witnesses, old changesets and expired history, with ClickHouse
  as the first assessed adapter; the node is complete without any cold store.
- G-9 RocksDB removed from the build one minor release after the new engine becomes the default, with operator
  migration guidance for both chains.
- G-10 A read-serving path that never blocks import and never is blocked by it: bounded, dedicated executors for
  `debug_trace*` and heavy reads, compile-time-derived streaming codecs for trace and log output; the fixed-width
  `UInt256` that removes per-op allocation from trace re-execution is spec 021.
- G-11 Measured, not asserted: the GT-1 harness has an RPC read-load arm that compares fukuii with Geth, Besu, Erigon
  and Reth on the same hardware, and no external throughput claim is made before it has run.

## Non-goals

- **NG-1 Changing any consensus output.** State roots, receipts, gas, hashes and the witness wire format are
  byte-for-byte unchanged on both chains. This spec changes where bytes live and how they are reached, never what they
  are.
- **NG-2 Changing the `StateIngest` or `StateAccess` signatures.** New members are allowed only with a member cap
  justification (016 FR-043 style). No SNAP or ledger code moves for this spec.
- **NG-3 The zkVM guest.** Spec 019. This spec only guarantees the stateless closure gains no engine dependency.
- **NG-4 An in-place converter from the RocksDB layout to the new layout for state.** Operators resync (ETC: checkpoint
  archive, hours; ETH: spec 013 snapshot or SNAP). A chain-data converter is OQ-4.
- **NG-5 Within-block parallel execution (Block-STM).** The storage side must not preclude it (DC-13) and the
  BAL-driven prefetcher continues to work; the scheduler itself is a later spec.
- **NG-6 Historical state at arbitrary roots in archive mode at today's speed.** Archive keeps every changeset, so any
  historical state is reconstructible; fast historical point reads (history indices, or the cold tier) are OQ-3.
- **NG-7 Binary state tree (EIP-7864).** The trie stays the hexary Keccak MPT; the layout keeps the commitment
  structure swappable, as 017 DC-12 requires.
- **NG-8 Tuning RocksDB further.** Spec 015 did that. Phase 0 measures today's engine only to set the baseline, and
  RocksDB stays in the bake-off only as the control arm.
- **NG-9 A distributed or replicated database.** The EL is one node. ScyllaDB and any multi-node store are declined for
  every tier.
- **NG-10 Requiring a cold store to run a node.** A node with only the hot tier serves consensus, the Engine API and
  the RPC methods it serves today, within the pruning window.

## Scope

Four tracks. Track E and Track F are strictly ordered; Track H runs beside Track F behind its own seam; Track R (read
serving) runs beside all of them and gates none.

**Track E — the hot-tier engine, behind the contract (G-1, G-2, G-3, G-9).**

1. Contract cleanup: move `IterationError` onto the trait, promote `seekFrom`, `approximateKeyCount` and
   `iterateSyncRange` onto the trait so the three downcasts in `FlatAccountStorage`, `FlatSlotStorage` and
   `BfsQueueStorage` go away, make `compactRange` a documented no-op on engines without compaction, add an engine-neutral
   `StorageMetrics` seam replacing `RocksDbCacheMetrics.register`, and introduce the typed transaction API of DC-12.
2. The bake-off harness (GT-1) and the candidate adapters needed to run it. Candidates (research §12): libmdbx through
   JDK 25 FFM over the vendored amalgamation; LMDB through `lmdbjava`; RocksDB with the DC-4 close-fence (control arm);
   pure-JVM embedded stores (JetBrains Xodus, H2 MVStore) as assessed candidates; and a fukuii-owned mmap'd segment
   store (FFM `MemorySegment` over append-only files) for the **immutable** chain data (headers, bodies, receipts),
   which every candidate pairs with.
3. The chosen engine's `DataSource` and component, with durability mapping (DC-3): `update` relaxable where the
   capability's policy allows, `updateSync` fully synced; `StateAccess` always durable.
4. The shared `DataSourceTestBehavior` suite, golden-bytes of every stored format that is kept, and the two-engine import
   differential (SC-001).
5. Default flip for fresh datadirs, then RocksDB removal one minor release later (DC-11).

**Track F — the flat data model (G-4, G-5, G-6, G-7).**

6. Changesets: per block, the previous value of every flat key and trie node the block changed, keyed by block number,
   replacing `ReferenceCountNodeStorage`'s stored counts, snapshots and death rows. Rollback applies them in reverse;
   pruning deletes those older than `pruning.history`; archive keeps all (NG-6).
7. Flat execution reads in `InMemoryWorldStateProxy` under `StateAccess`: `getAccount` and `getStorage` read the flat
   stores; the trie is updated from the block's write set and yields the state root.
8. Path-aware node resolution in `mpt/`: the trie resolves a child by `(path, hash)` and verifies the hash, so one
   path-keyed node store serves both chains. Hashing and encoding are untouched (DC-2; `forge` and `beacon` review).
9. Key-set witness recorder plus proof walk (DC-7), with the 017 differential test as the oracle (SC-007), amending 017
   DC-8.
10. SNAP ingest writes each node once (path-keyed) and each leaf once (flat); `PathToHashExporter` is retired; the Hash
    scheme is retired and ETC moves onto the unified layout (`forge` sign-off; DC-9).
11. Gigablock readiness of the state root (DC-13): the modified-subtree hashing runs in parallel across independent
    account and storage subtrees, keyed by the block's write set (the BAL where present, the recorded write set
    otherwise), with a sparse in-memory trie cache that persists across blocks; the root is byte-identical to the
    serial walk (SC-001) and the end-of-block serial remainder is bounded (SC-012).

**Track H — the cold tier (G-8).**

12. A `ColdStore` seam in `db/` for append-only, query-oriented data: receipts and logs index, witnesses (017 FR-004's
    slow-tier store), changesets past the pruning window, expired history (#1483). Writes are asynchronous and never on
    the import path; the hot tier is the source of truth for everything within the pruning window.
13. A ClickHouse adapter behind that seam, operator opt-in, with `eth_getLogs` over ranges beyond the hot tier's bloom
    index served from it when configured (`conduit` owns the RPC side). Assessed first (GT-3); built only on a go.
14. Era/era1 export as the file-based cold format for history (ties to spec 013 §5.6 and #1483), independent of any
    database.

**Track R — the read-serving path (G-10, G-11).** Independent of the engine choice; may start after Phase 0.

15. **Bounded read executors.** `debug_trace*`, `eth_getLogs` over ranges, `eth_call`/`eth_simulateV1` and witness
    regeneration (017 DC-16) run on dedicated executors with fixed thread counts, bounded queues and per-request
    timeouts, separate from block import's execution context and from the RPC dispatcher; a full queue rejects with an
    explicit error. Per-method concurrency caps are configuration.
16. **Streaming, compile-time codecs for large results.** Trace and log responses are encoded by circe codecs derived
    at compile time (no reflection) and streamed over fs2 as they are produced, never materialised as one tree; json4s
    is retired from the trace and log paths first and from `jsonrpc/` as a whole by the end of the track.
17. **Fixed-width `UInt256` → spec 021.** Split into its own spec on 2026-10-17 (user decision, OQ-13):
    `specs/021-fixed-width-uint256/`. It is pure consensus code under the consensus-change protocol and is not
    scheduled behind any storage gate. This spec keeps only the dependency: 018 SC-016 re-measures trace re-execution
    after 021 lands, and Track R item 15/16 do not wait for it.
18. **History reads for analytics.** Archive and long-window reads needed by traces over history are served from
    changesets plus the cold tier (Track H, OQ-3), with the documented error beyond what is configured.

Track F cannot start before Track E's SC-001 is green on both chains; item 9 must be green before item 7 is enabled by
default; Track H never gates Track E or F; Track R items 15 and 16 never gate E or F and item 17 is gated only by its
own consensus review.

## Gates *(mandatory; nothing past a gate proceeds until it reports)*

**GT-0 — Attribution spike (Phase 0, off the node host).** Reproduce a stall on today's engine under a synthetic import
load on a Mordor or Platåberget datadir, with a read probe on another thread. Report, per stall: time readers waited
under `dbLock`, and time the engine itself stalled (RocksDB `STALL_MICROS` and the flush/compaction log). Then repeat
with `dbLock` replaced by a close-fence only. The result decides how much of SC-004 an engine swap buys versus a lock
fix, and whether the lock fix ships as its own PR first (OQ-7). Days, not weeks.

**GT-1 — Hot-tier engine bake-off (Phase 0, off the node host).** One harness (`Benchmark/`), one workload, every
candidate. The workload replays a fixed Platåberget range and a fixed Mordor range through the flat data model of Track
F (accounts and slots by hashed key, path-keyed nodes, changesets) at 1×, 10× and 30× today's block gas (synthetic
gigablocks built by concatenating consecutive blocks' state deltas), with a concurrent read probe and a bounded set of
long readers. Each candidate reports the DC-13 figures and the DC-12 figures. The report ranks candidates and names the
constraints each imposes (binding maturity, platform support, build steps, memory accounting, size on disk). The harness
has an **RPC read-load arm** (FR-028): `debug_traceBlockByNumber` with callTracer and prestateTracer,
`debug_traceTransaction`, `eth_getLogs` over 1k/100k/1M-block ranges and `eth_call` at concurrency 1/8/64, run against
fukuii and against Geth, Besu, Erigon and Reth on the same hardware and the same synced range, with and without a
concurrent import load; it reports p50/p99 latency, throughput, and import-time interference. The user decides the
engine; ADR INF-007 records it. A candidate that cannot be bound from JDK 25 in a day of effort is dropped
with the attempt recorded, not carried.

**GT-2 — Constitution amendment.** The constitution names "RocksDB for storage" among the core libraries and requires
new dependencies to be justified. Before the chosen engine's `DataSource` merges, the constitution is amended through
`/speckit-constitution` with ADR INF-007 recording the engine decision, the binding route and any vendored native
source. Spec 019's CG-1 amends the same section; the two amendments are coordinated so the section is rewritten once.
The spikes run on throwaway branches before the amendment, because they merge nothing.

**GT-3 — Cold-tier go/no-go (before Track H item 13).** The assessment (research §13, plan R-9) reports: the RPC and
operator use cases a cold store serves that the hot tier cannot within the pruning window, the operational cost of a
second process on the 16 GiB host class or a second host, and the alternative of files only (era + witnesses on disk).
The user decides whether a ClickHouse adapter is built in the first release or the seam ships empty.

*(The former GT-3, "layout decision for ETC", is closed by the user's decision of 2026-10-17: ETC moves onto the
unified layout in the first release. DC-9 records it.)*

## Design constraints *(mandatory)*

**DC-1 — The gateways do not move.** Every state read and write continues to flow through `StateIngest` (016) and
`StateAccess` (017). This spec changes the implementations beneath `DataSource` and the typed stores in `db/storage/`,
and the world state proxy's read path under `StateAccess`. No file under `blockchain/sync/snap/` changes except to
delete the publish step (item 10). The 016 ratchet (`scripts/snap-split/verify.py`) and the 017 dependency checks stay
green throughout.

**DC-2 — Consensus outputs are byte-identical on both chains.** State roots, receipt roots, gas, block hashes, and the
witness wire format do not change. Any change in `mpt/` is to node **resolution** (addressing a node by path and
verifying its hash) and to the **scheduling** of hashing (parallel subtrees, cached sparse trie), never to hashing, RLP
encoding or the trie algorithms. `forge` signs off ETC, `beacon` ETH, before any `mpt/` or `ledger/` change merges;
ethereum/tests and the Mordor/Sepolia import differential (SC-001) are the oracles.

**DC-3 — Durability is per capability and at least today's.** `StateAccess` writes commit durably (synced commit, or an
equivalently crash-safe commit mode the engine documents as durable). `StateIngest` may run relaxed (016 FR-045), and the
DC-1 barrier of 016/017 (a synced commit before `completeSnapSync`, on both chains) is **implemented** by this spec if
016 T056 has not already done so. The `inmemory` pruning mode's durability gap (INF-006 §4) is closed or the mode is
retired (OQ-5). Cold-tier writes are asynchronous and their loss is never a consensus or sync failure.

**DC-4 — Writes never block reads.** Reads run in snapshot read transactions and take no lock a writer takes. The
process-wide `dbLock` shrinks to a close-fence (an open/closed state plus in-flight count) or disappears. SC-004 tests
it under a saturating write load. If the chosen engine has background work, SC-004 must hold with it running.

**DC-5 — Every read transaction is bounded.** On MVCC engines a long-lived reader pins old pages and grows the database
file until it ends; on LSM engines it pins SST files. Every `iterate()` stream, `scanRange` window, heal-walk chunk and
RPC read is a bounded read transaction (bounded by key window or by count, never by "until the consumer stops"). An
**oldest-reader lag** gauge (blocks or transactions behind head) is exported, and a reader older than a configured
threshold is logged at WARN with its origin. A read transaction may never be held across an actor message boundary or an
`await`; DC-12's types make that a compile error where the type system can see it.

**DC-6 — Bulk ingest is sorted and batched.** Random inserts cost page splits (B+tree) or compaction (LSM).
`StateIngest` writes arrive sorted by key from SNAP ranges and are committed in batches of the size the 016 storage
protocol already prescribes (5K–10K keys); the healing path sorts its batch before commit. No per-key transactions on
any ingest path. Immutable chain data goes to the append-only segment store, never to the state engine.

**DC-7 — Witnesses are produced from the access set, and must equal 017's.** Under the flat read path the world records
the **access set** (hashed account keys, hashed slot keys per account, code hashes, ancestor headers) instead of trie
nodes. At witness finalization the proof nodes are generated by walking the trie for every recorded key (the way reth's
`debug_executionWitness` and geth's stateless package do). The wire format is unchanged (017 DC-11). This **amends 017
DC-8**: a capturing world with flat reads is constructible, and the DC-8 property ("no read may bypass what the witness
needs") is kept by the key-set recorder plus the proof walk rather than by forbidding flat reads. The 017 differential
test and 017 DC-14's BAL self-check are the oracles (SC-007). Until SC-007 is green the flat read path stays off by
default and 017's node recorder stays in place.

**DC-8 — The stateless closure gains nothing.** `StatelessExecutor` and its transitive closure (017 DC-15) gain no
dependency on `db/`, any engine binding, `java.lang.foreign`, the flat stores, the changeset store, the cold-tier seam
or the key-set recorder's host plumbing. The 017 DC-15 dependency check is extended to name them and stays green. The
witness is the only thing that crosses from host to guest.

**DC-9 — One layout for both chains; ETC comes along (user decision, 2026-10-17).** The target is one path-keyed trie
plus flat accounts and slots plus changesets, on both chains, in the first release. `storage-scheme` is deprecated
(accepted and ignored with a WARN for one minor release, then rejected). Existing datadirs on the old layout are
detected at start and refused with a message naming the resync procedure; no silent mixing of layouts in one datadir.
ETC operators resync from a checkpoint archive (hours); the release notes, the `fukuii-checkpoint-service` and
`fukuii-first-start` skills and the ETC runbooks carry the procedure before the default flips. `forge` signs off that
ETC consensus is unchanged and that mining, ECIP-1017 rewards and the ETC ethereum/tests job are green on the unified
layout.

**DC-10 — Memory is accounted, not assumed.** Whatever the engine, the node exports its memory as gauges (for an mmap
engine: mapped-file size, resident mapped pages, dirty pages; for an LSM engine: the spec 015 gauges) and the plan
publishes one sizing formula (`-Xmx` + direct + metaspace + engine working set ≤ host) that replaces spec 015's. The
16 GiB / 4-core host remains the target for today's blocks (spec 013, 015); the gigablock profile (DC-13) states its own
host class and is measured on it.

**DC-11 — The transition window is bounded.** Both engines compile in for at most one minor release after the default
flips to the new engine; then RocksDB, `rocksdbjni`, spec 015's budget config and `RocksDbCacheMetrics` are removed in
one PR. `db.data-source = "rocksdb"` is rejected at start after removal with a message naming the migration runbook.

**DC-12 — Scala 3 at the storage boundary.** The boundary is engine-agnostic and makes the invariants types:
- **Typed transactions.** `ReadTxn` and `WriteTxn` are distinct types; a `DataSource` hands out a `WriteTxn` only
  through a single-writer `Resource` and a `ReadTxn` only through a bounded `Resource` (DC-5), so holding one across a
  boundary the type system can see is a compile error, and the single-writer invariant (research F1) is enforced by
  construction rather than by `dbLock`.
- **Zero-copy reads.** Values are read into `MemorySegment`s (FFM) or `Array[Byte]` slices with no intermediate
  `IndexedSeq[Byte]`; the trait's `Key`/`Value` aliases become opaque types over the engine's native view, and codecs
  are `given` instances derived with `inline` at compile time (no reflection, no runtime schema).
- **Opaque keys.** Namespace and key types for the flat stores (`AccountKey`, `SlotKey`, `NodePath`) are opaque types
  (017 G-5's `Address`/`TrieKey` pattern), so a hashed account key cannot be passed where a slot key is expected.
- **Streams are bounded by type.** `fs2.Stream` results come from a windowed API (`scanRange(from, toExclusive)` or
  `take(n)`), never from an unbounded iterator; the `IterationError` type lives on the trait.
- **Engine selection is an enum**, not a string match, and each engine's config is its own case class.
The bake-off harness (GT-1) is written against this API, so every candidate adapter implements it.

**DC-13 — Gigablock readiness.** The storage side must scale to blocks an order of magnitude or more above today's gas
limit without redesign. Concretely, measured by the GT-1 harness at 1×, 10× and 30× today's block gas:
- **Reads scale with cores.** State reads from concurrent threads (the BAL prefetcher today; a parallel executor later,
  NG-5) see snapshot isolation and no shared lock; read throughput grows with reader threads until the storage device
  saturates.
- **Writes are one commit per block.** The block's write set (flat keys, trie nodes, changeset) commits as one
  transaction (or one ordered group where the engine's transaction size is bounded, with the commit boundary recorded so
  recovery is exact). Commit time grows at most linearly with the write set.
- **The state root is not serial.** Modified-subtree hashing runs in parallel across independent account and storage
  subtrees (item 11), keyed by the write set, with a sparse trie cache that persists across blocks. The end-of-block
  serial remainder is bounded (SC-012); the result is byte-identical to the serial walk.
- **Memory per block is bounded.** Working memory for a block's execution and commit is proportional to its write set
  and access set, never to total state; the harness reports peak heap and engine memory per block size.
- **The witness scales with the access set.** Proof generation (DC-7) walks the trie once per recorded key with shared
  prefixes de-duplicated; witness bytes and generation time are reported per block size.
- **Immutable data is off the state engine.** Headers, bodies and receipts go to append-only segments (Track E item 2),
  so block-data volume at gigablock sizes never competes with state for the engine's pages or compaction.
- **Not precluded.** Nothing in the storage API assumes a single executing thread per block; `StateAccess` snapshots
  can be read by many threads and the write set is merged once at commit.
Targets are set from the 1× measurement at GT-1 and recorded in `research.md` §15; no target is asserted before the
measurement exists.

**DC-14 — Every claim is measured against the baseline.** Phase 0 records, on the same datadir and host class, for
today's engine: p50/p99 `[IMPORT-TIMING]` per block, longest read wait, bytes written per block (engine write bytes ÷
block state bytes), database size, RSS and cgroup `memory.current`, and SNAP `[STORAGE-PERF]` rate. Every Track E, F and
H slice reports the same figures. No slice merges on a claim it did not measure (constitution III and the working
discipline's evidence standard).

**DC-15 — Read serving never blocks import and is never blocked by it.** Every heavy read (`debug_trace*`, range
`eth_getLogs`, `eth_call`/`eth_simulateV1`, witness regeneration) runs on a bounded, dedicated executor (fixed threads,
bounded queue, per-request timeout) that shares no thread pool and no lock with block import; a full queue rejects
with an explicit error rather than queueing indefinitely. Large results are streamed as produced through
compile-time-derived codecs, never materialised as one tree. The GT-1 RPC arm (FR-028) measures interference in both
directions: import p99 under read load, and read p99 under import load; both must stay within the SC-015 bounds. Until
Track R lands, the spec makes no claim about fukuii's read scalability (research §16).

**DC-16 — The node is the source, not the query engine.** fukuii's job under enterprise load is to be a correct,
deterministic, stable-latency source. The topology the specs assume, and the operator docs describe, is: one or more
validating nodes; snapshot-seeded read replicas (spec 013) that follow them; an indexer or cold tier (Track H) that
answers analytical queries. No single node is specified to serve "millions of concurrent requests", and no SC is
written against that. Track R makes one node serve its share well; Track H and spec 013 make the fleet possible.

## User Scenarios & Testing *(mandatory)*

### User Story 1 — An operator imports blocks with no stall (Priority: P1)

An operator runs a synced node on the 16 GiB host under heavy block validation (Sepolia at head, or Mordor with a
backlog). Block import time is stable and no RPC, Engine API or SNAP-serving request waits on a write.

**Why this priority**: this is the runtime limitation the spec exists to remove, and the property the zkVM host needs.

**Independent Test**: SC-004's saturating-write test, and a 24-hour soak on Platåberget and Mordor with the DC-14
figures compared to the Phase 0 baseline.

**Acceptance Scenarios**:

1. **Given** a write transaction in progress, **When** an RPC `eth_getBalance` arrives, **Then** it is answered from a
   snapshot without waiting for the write.
2. **Given** a 24-hour soak, **When** the DC-14 figures are compared to the baseline, **Then** the longest read wait is
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

### User Story 4 — A gigablock imports within budget (Priority: P1)

A maintainer runs the GT-1 harness at 30× today's block gas on the chosen engine with the flat data model and the
parallel state root. Import time, commit time, peak memory and witness size scale as DC-13 requires.

**Why this priority**: it is the user's stated goal. It is testable before mainnet raises any limit because the harness
builds synthetic gigablocks from real state deltas.

**Independent Test**: the harness at 1×, 10× and 30×, with the SC-012 figures.

**Acceptance Scenarios**:

1. **Given** a 30× synthetic block, **When** it is imported, **Then** the state root equals the serial walk's, the
   end-of-block serial remainder is within SC-012, and peak memory is proportional to the write set.
2. **Given** eight reader threads, **When** they read disjoint accounts during a commit, **Then** aggregate read
   throughput is at least the single-thread figure times the number of threads, until the device saturates.

### User Story 5 — Execution reads flat state and the witness is unchanged (Priority: P2)

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

### User Story 6 — An ETC operator moves to the unified layout (Priority: P1)

An ETC mainnet operator on the Hash scheme upgrades. The node refuses to open the old datadir with a message naming the
checkpoint resync, the operator resyncs from a checkpoint archive in hours, and mining, RPC and `forge`-reviewed
consensus behave exactly as before, faster.

**Why this priority**: the user decided ETC comes along in the first release (DC-9); it is a one-way door for those
operators and must be rehearsed, not assumed.

**Independent Test**: a Mordor resync from a checkpoint archive onto the unified layout, then a mining run, the ETC
ethereum/tests job, and the DC-14 figures against the Mordor baseline.

### User Story 7 — Logs over a long range come from the cold tier (Priority: P3)

An operator who has enabled the ClickHouse adapter asks `eth_getLogs` over a million-block range. The node answers from
the cold tier; a node without the adapter answers the same query for the range its hot tier covers and returns the
documented error beyond it.

**Why this priority**: useful and the reason the cold tier exists, but it never gates consensus or sync, and it waits on
GT-3.

**Independent Test**: the same query on a node with and without the adapter; identical results within the shared range.

### User Story 8 — An enterprise risk engine traces and audits against fukuii (Priority: P2)

A regulated institution runs fukuii as the deterministic source behind a risk and compliance pipeline: continuous
`debug_traceBlockByNumber` with callTracer and prestateTracer on every new block, periodic `eth_getLogs` over long
ranges, and bursts of `eth_call` risk checks, while the node keeps importing at head. Traces stream, heavy requests
are bounded and rejected explicitly when the budget is full, import latency does not move, and historical traces
beyond the hot window come from the cold tier when configured.

**Why this priority**: it is the workload the external assessment (research §16) described, and the one Track R and
Track H exist to serve. P2 because consensus and sync (P1) come first and because the topology (DC-16), not one node,
carries the full load.

**Independent Test**: the GT-1 RPC arm (FR-028) with concurrent import; SC-015; US-7 for the cold-tier range.

**Acceptance Scenarios**:

1. **Given** 64 concurrent `debug_traceBlockByNumber` requests during import, **When** measured, **Then** import p99
   stays within the SC-015 bound and no request waits on a storage write.
2. **Given** a trace queue at capacity, **When** another request arrives, **Then** it is rejected with the documented
   error within the timeout, never queued indefinitely.
3. **Given** a 1M-block `eth_getLogs` range with the cold tier configured, **When** requested, **Then** the result
   streams and matches an archive reference client for the fixture range.

### Edge Cases

- A read transaction held across an actor message or an `await` (DC-5): a compile error where DC-12's types see it,
  otherwise caught by the oldest-reader gauge test.
- A write transaction larger than the engine's transaction limit (a gigablock's write set, a huge SNAP batch): split into
  an ordered group with the boundary recorded; recovery is exact (DC-13).
- Disk full during commit: the commit fails loudly, the previous state is intact (no partial page writes visible).
- Power loss between a relaxed `StateIngest` commit and the DC-3 barrier: SNAP resumes from its last durable
  checkpoint; nothing execution reads is missing.
- An old-layout datadir opened by a new build (DC-9); a new-layout datadir opened by an old build.
- `pruning.mode = "inmemory"` (OQ-5).
- A reorg deeper than `pruning.history` under changesets: refused loudly, as today's reference counting refuses it.
- The parallel state root and a block whose write set touches one deep subtree only: no parallelism available; the
  serial path must still meet SC-012's bound for that shape or the bound is stated per shape.
- The sparse trie cache after a reorg: invalidated to the common ancestor; the next root must still match (SC-001).
- The file-memory cgroup pressure case: page cache evicted under JVM heap pressure, import slows but never fails
  (DC-10 gauges show it).
- The cold store unreachable: import and sync unaffected; the cold-tier queue is bounded and drops with a metric, never
  blocks (DC-3).
- A Windows or macOS developer build: the engine binding must load or fail with a clear message.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001** The system MUST provide a `DataSource` implementation over the engine GT-1 selects, selectable with
  `db.data-source`, with one named sub-store per `Namespace`.
- **FR-002** The engine binding MUST be one of: a published, maintained JVM artifact; or JDK 25 FFM over vendored native
  source with the version pinned and hashed. No unmaintained snapshot artifact, no custom JNI.
- **FR-003** The `DataSource` trait MUST become engine-neutral: `IterationError` moves onto the trait; `seekFrom`,
  `approximateKeyCount` and `iterateSyncRange` become trait members with `EphemDataSource` implementations; no code
  outside `db/dataSource/` names a concrete engine class; engine selection is an enum.
- **FR-004** The shared `DataSourceTestBehavior` suite MUST pass on `EphemDataSource`, `RocksDbDataSource` and the new
  implementation, extended with the DC-5 reader-bound behaviour, the DC-3 durability modes and the DC-12 typed
  transaction API.
- **FR-005** `update` MUST be one atomic transaction (or one recorded ordered group where the engine bounds transaction
  size); `updateSync` MUST be a fully synced commit; the `StateIngest` durability policy MUST map to a relaxed commit
  mode on ingest writes only (DC-3).
- **FR-006** A read MUST never wait on a write in progress (DC-4); the process-wide read/write lock MUST be reduced to
  a close-fence.
- **FR-007** Every read transaction MUST be bounded (DC-5); the system MUST export an oldest-reader lag gauge and log a
  reader past the configured threshold with its origin.
- **FR-008** The system MUST export engine-neutral storage gauges through a `StorageMetrics` seam that replaces
  `RocksDbCacheMetrics.register`: store size on disk, engine resident memory, dirty or unflushed bytes, read and write
  transaction durations (p50/p99), oldest-reader lag, and cold-tier queue depth and drops.
- **FR-009** The two-engine import differential (SC-001) MUST run in CI on a fixed Mordor range and a fixed Platåberget
  range, comparing every state root and receipt root.
- **FR-010** The system MUST provide a changeset store: per block, the previous value of every flat key and trie node
  the block changed; rollback MUST apply changesets in reverse; basic pruning MUST delete changesets older than
  `pruning.history`; archive MUST keep all (and MAY move them to the cold tier, Track H).
- **FR-011** Under `StateAccess` with the flat read path on, `getAccount` and `getStorage` MUST read the flat stores,
  and the state root MUST be computed by updating the path-keyed trie from the block's write set; the root MUST equal
  the trie-walk root for every block (SC-001 under flat reads).
- **FR-012** `mpt/` node resolution MUST accept a path and verify the resolved node's hash; hashing, encoding and trie
  algorithms MUST NOT change (DC-2).
- **FR-013** In capture mode with flat reads, the world MUST record the access set and the witness MUST be generated by
  a trie proof walk over it; the result MUST be byte-identical to 017's node recorder on the differential set (DC-7,
  SC-007). This FR amends 017 DC-8 and FR-003a.
- **FR-014** The stateless closure MUST gain no dependency on `db/`, any engine binding, `java.lang.foreign` or the new
  stores (DC-8), enforced by extending 017's DC-15 check.
- **FR-015** SNAP ingest on the unified layout MUST write each trie node once (path-keyed) and each leaf once (flat);
  `PathToHashExporter` MUST be retired with its 016 FR-044 durability property re-expressed as the DC-3 barrier.
- **FR-016** `storage-scheme` MUST be deprecated per DC-9; a datadir on the old layout MUST be refused at start with a
  message naming the resync procedure; ETC MUST run on the unified layout in the first release.
- **FR-017** RocksDB MUST be removable in one PR one minor release after the default flips (DC-11); the removal PR MUST
  delete `rocksdbjni`, spec 015's budget config and `RocksDbCacheMetrics`.
- **FR-018** The operator runbooks and skills that name `rocksdb/` (`fukuii-backup-restore`, `fukuii-disk-management`,
  `fukuii-checkpoint-service`, `fukuii-first-start`, `multi-instance.conf`, `known-issues`) MUST be updated for the new
  datadir layout and the per-chain resync procedure before the default flips.
- **FR-019** Every Track E, F and H slice MUST report the DC-14 figures against the Phase 0 baseline in its PR.
- **FR-020** The constitution MUST be amended (GT-2) with ADR INF-007 before the new engine's `DataSource` merges.
- **FR-021** The GT-1 harness MUST exist in `Benchmark/`, run every candidate adapter against the same workload at 1×,
  10× and 30× block gas, and emit the DC-13 and DC-14 figures as one machine-readable report per candidate.
- **FR-022** Headers, bodies and receipts MUST be stored in an append-only segment store separate from the state engine,
  readable zero-copy (DC-12, DC-13), with the same golden-bytes formats as today where the format is kept.
- **FR-023** The state root MUST be computed by parallel hashing of independent modified subtrees with a sparse trie
  cache persisting across blocks, invalidated on reorg to the common ancestor, and MUST equal the serial walk's root on
  every block (DC-13, SC-012).
- **FR-024** The system MUST provide a `ColdStore` seam whose writes are asynchronous, bounded and never on the import
  path; the node MUST be complete with no cold store configured (NG-10).
- **FR-025** If GT-3 says go, a ClickHouse adapter MUST implement `ColdStore` for receipts/logs, witnesses, aged
  changesets and expired history, operator opt-in, and `eth_getLogs` MUST be served from it for ranges the hot tier does
  not cover, returning the documented error for such ranges when no cold store is configured.
- **FR-026** The typed transaction API of DC-12 (`ReadTxn`, `WriteTxn`, bounded `Resource`s, opaque key types,
  compile-time-derived codecs) MUST be the API every engine adapter, including the harness's, implements.
- **FR-027** Heavy reads (`debug_trace*`, range `eth_getLogs`, `eth_call`/`eth_simulateV1`, witness regeneration) MUST
  run on bounded, dedicated executors with fixed threads, bounded queues, per-request timeouts and per-method
  concurrency caps, sharing no pool or lock with block import; a full queue MUST reject with an explicit error (DC-15).
- **FR-028** The GT-1 harness MUST include an RPC read-load arm comparing fukuii with Geth, Besu, Erigon and Reth on
  the same hardware and synced range, with and without concurrent import, reporting p50/p99 latency, throughput and
  import interference; no external throughput or concurrency claim MAY be published before it has run (G-11).
- **FR-029** Trace and log responses MUST be encoded by compile-time-derived codecs and streamed as produced; json4s
  MUST be removed from the trace and log paths in Track R and from `jsonrpc/` by the end of the track.
- **FR-030** *(moved to spec 021)* The fixed-width `UInt256` requirement is spec 021's FR-001..008. This spec
  MUST re-measure trace re-execution (SC-016) once spec 021 lands and MUST NOT duplicate its implementation.
- **FR-031** The `dbLock` close-fence (DC-4) MUST ship as its own PR on the RocksDB engine as soon as GT-0 has reported,
  independent of the engine decision (T-010); it is the one Track E change that does not wait for GT-1.

### Key Entities

- **Hot-tier engine**: the embedded, in-process `DataSource` GT-1 selects; one named sub-store per namespace; a write
  transaction per `update`; snapshot read transactions.
- **Segment store**: append-only, mmap-readable files for immutable chain data (headers, bodies, receipts), indexed by
  block number and hash.
- **`ColdStore`**: the asynchronous, optional seam for query-oriented and aged data; ClickHouse is the first assessed
  adapter.
- **`StorageMetrics`**: the engine-neutral gauge seam.
- **`ReadTxn` / `WriteTxn`**: the typed transaction capabilities of DC-12.
- **Changeset**: per block, the previous values the block overwrote (flat keys and trie nodes), keyed by block number;
  the unit of rollback and pruning.
- **Flat state**: accounts by `keccak(address)` (namespace `a`), slots by `keccak(address) ++ keccak(slot)` (namespace
  `d`), read by execution under `StateAccess`.
- **Path-keyed trie**: the one commitment structure, nodes addressed by nibble path and verified by hash (namespaces
  `t`, `u`), with a sparse in-memory cache across blocks.
- **Access set**: the hashed keys, code hashes and ancestor headers a block's execution touched; input to the proof walk
  that produces the witness.
- **Oldest-reader lag**: how far behind head the oldest open read transaction is; the DC-5 health signal.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001** On the fixed Mordor and Platåberget ranges, every state root and receipt root is equal under RocksDB and
  under the new engine, and ethereum/tests passes on the new engine. Re-checked under flat reads (FR-011) and under the
  parallel state root (FR-023).
- **SC-002** Engine bytes written per block state byte (write amplification) on the new engine with changesets is at
  most one third of the Phase 0 baseline on basic pruning, on both chains.
- **SC-003** p99 `[IMPORT-TIMING]` over the 24-hour soaks is at or below the Phase 0 baseline, and the maximum is at
  most twice p99 (no multi-second outliers).
- **SC-004** Under a saturating write load, no read waits more than 10 ms on the engine (measured at the `DataSource`
  boundary), where the Phase 0 baseline shows waits of seconds.
- **SC-005** Database size on disk for the same synced state is at most 1.5× the RocksDB size on Sepolia and on Mordor;
  the figure is published per candidate at GT-1 and for the chosen engine at E8.
- **SC-006** RSS plus engine working set under import fits the 16 GiB host with the published formula, with no OOM kill
  over the soaks.
- **SC-007** On the 017 differential set, witnesses from the key-set recorder are byte-identical to witnesses from 017's
  node recorder, and the BAL self-check reports zero gaps over 10,000 Amsterdam blocks.
- **SC-008** The oldest-reader lag gauge never exceeds the configured threshold over the soaks, and the DC-5 test
  catches an injected unbounded reader.
- **SC-009** SNAP `[STORAGE-PERF]` rate on the new engine is at or above the baseline on both chains, and the 016 US-1
  restart test and the 017 DC-1 crash test pass.
- **SC-010** The 016 ratchet and the 017 dependency checks (DC-4, DC-15 extended per DC-8) stay green on every slice.
- **SC-011** After the removal PR, no file in the repo names `org.rocksdb`, and the `db/` line count is at or below
  today's 5,616 plus the segment store and `ColdStore` seam (one engine, fewer caches).
- **SC-012** On the GT-1 harness at 30× today's block gas, with the chosen engine, flat reads and the parallel state
  root: import time grows at most linearly with block gas from the 1× figure; the end-of-block serial state-root
  remainder is at most 5% of block import time; peak heap per block grows at most linearly with the write set; read
  throughput with 8 reader threads during a commit is at least 6× the single-thread figure on a device that is not
  saturated; witness generation time grows at most linearly with the access set. Targets are confirmed or revised from
  the 1× measurement at GT-1 and recorded in `research.md` §15.
- **SC-013** Mordor on the unified layout: the DC-14 figures are at or better than the Mordor baseline on every metric,
  mining produces valid blocks, ECIP-1017 rewards and the ETC ethereum/tests job are unchanged, and a resync from a
  checkpoint archive completes within the time `research.md` §15 records for today's layout.
- **SC-014** With no cold store configured, every RPC method answers exactly as today within the pruning window and the
  node passes rpc-compat (40) and graphql (2) unchanged; with the ClickHouse adapter, `eth_getLogs` over a range beyond
  the hot tier returns results identical to an archive geth for the fixture range.
- **SC-015** On the GT-1 RPC arm with 64 concurrent `debug_traceBlockByNumber` requests during import: import p99 is
  within 10% of its no-load figure; no read waits on a storage write (SC-004 holds under read load); trace p99 is
  within 2× its no-import figure; a request beyond the queue bound is rejected within its timeout. Figures are
  published beside Geth, Besu, Erigon and Reth on the same hardware; no target relative to them is set until the first
  run exists.
- **SC-016** After spec 021 lands, the GT-1 RPC arm is re-run: trace re-execution of the fixture blocks shows no
  `BigInteger` allocation on the arithmetic path (spec 021 SC-003) and the trace p99 figure is republished beside the
  comparators.

## Assumptions

- Spec 016 is complete and spec 017 has landed `StateAccess`, `RecordingMptStorage` and the differential test before
  Track E implementation starts. Phase 0 needs only spec 016.
- JDK 25 is the runtime (constitution), so `java.lang.foreign` is available without preview flags for any FFM-bound
  candidate.
- The 16 GiB / 4-core host class stays the target for today's blocks; the gigablock profile names its own host class
  at GT-1 (DC-10, DC-13). Soaks run on Platåberget (ETH, PoS) and Mordor (ETC, PoW), never on the live Sepolia node
  (016 US-4).
- The `[IMPORT-TIMING]` and `[STORAGE-PERF]` instrumentation is reused for DC-14, not replaced.
- `vault` owns the engine, the segment store, the stores and the cold-tier seam; `forge` and `beacon` own the `mpt/` and
  `ledger/` changes for their chains; `eye` validates; `prism` reviews non-consensus code; `conduit` owns the
  `eth_getLogs` routing and confirms `debug_executionWitness` is unchanged; `flow` reviews the bounded-stream and
  cold-tier queue plumbing.
- Besu Bonsai, reth (changesets, static files, sparse trie), Erigon (domains, ETL, MDBX reader discipline) and geth
  (PBSS diff layers, Pebble) were read as prior art; the gigagas figures cited in research §14 are from the clients'
  public write-ups and are re-verified in Phase 0 (R-5) before the layout ADR is written.

## Open Questions

- **OQ-1** Which hot-tier engine? Answered by GT-1 and the user. The research's desk ranking (research §12) puts libmdbx
  first on the stall and reader properties and last on binding maturity; RocksDB-with-flat-model is the control that
  must be beaten, not assumed beaten.
- **OQ-2** Sub-store granularity: one per namespace (20) as today, or merge the small metadata namespaces? Default:
  one per namespace, so golden-bytes and the typed stores do not change.
- **OQ-3** Archive mode: keep all changesets only (NG-6), add history indices, or age changesets to the cold tier?
  Default: changesets only in the hot tier; aging to the cold tier if GT-3 says go.
- **OQ-4** A converter for chain data (headers, bodies, receipts, tx mapping) into the segment store so a resync
  re-downloads only state? Default: no converter in the first release; measure how long chain-data download takes on
  each chain first (R-8).
- **OQ-5** `pruning.mode = "inmemory"`: close its durability gap on the new engine, or retire the mode? Recommended:
  retire; changesets make the in-memory reference-count cache redundant.
- **OQ-6** Relaxed commit mode for `StateIngest`: the engine-specific "metadata-safe, last transactions may be lost"
  mode is the recommended default; the fully unsynced mode only if GT-0/GT-1 show it matters and SNAP resume covers it.
- **OQ-7** *(decided 2026-10-17, user)* The lock fix (research §8, alternative 2) ships as its own PR on RocksDB as soon
  as GT-0 has reported, whatever the attribution (FR-031, T-010); GT-0 still runs so the before/after is recorded.
- **OQ-13** *(decided 2026-10-17, user)* The fixed-width `UInt256` is its own spec, **021**
  (`specs/021-fixed-width-uint256/`); T-011 here is closed in favour of 021's task seed.
- **OQ-14** Which trace fixtures the GT-1 RPC arm uses: a fixed Sepolia range with known heavy contracts (recommended)
  plus a Mordor range, so both chains have figures.
- **OQ-8** Engine and page-cache sizing guidance for operators who run a CL client on the same 16 GiB host; and the
  host class the gigablock profile assumes.
- **OQ-9** Whether the flat stores carry a per-key "last modified block" to make 017's state-history window (DC-16,
  about 64 blocks) a flat lookup rather than a changeset replay.
- **OQ-10** Cold tier first release: ClickHouse adapter, files only (era + witness files), or the empty seam? Answered
  by GT-3.
- **OQ-11** The sparse trie cache's memory budget per chain, and whether it is shared with `DecodedNodeCache` or
  replaces it.
- **OQ-12** The synthetic gigablock construction for the harness: concatenated real deltas (default; realistic key
  distribution) versus generated uniform-random keys (worst case for B+trees); recommended: report both.

## Related specs

- **Spec 015** (RocksDB memory budget): superseded by DC-10 once RocksDB is removed.
- **Spec 016** (SNAP split, `StateIngest`): prerequisite; this spec sits under its gateway; the 016 ratchet must stay
  green.
- **Spec 017** (`StateAccess`, witnesses): prerequisite; this spec sits under its gateway, amends its DC-8 (DC-7
  here), extends its DC-15 check (DC-8 here), and offers the cold tier for its slow-tier witness store (FR-004 there).
- **Spec 013** (state snapshots): the resync accelerator for ETH operators under DC-9; its flat-state export format is
  aligned with this spec's flat stores; its §5.6 era files are Track H item 14.
- **Spec 009** (Amsterdam): EIP-7928 block access lists are the write-set oracle DC-13's parallel state root keys on.
- **Spec 019** (zkVM guest): downstream; unaffected except through DC-8's guarantee.

## Known tasks (seed for /speckit-tasks)

- **T-000 (Phase 0, GT-0)** The attribution spike, with the read-probe harness checked in under `Benchmark/`.
- **T-001 (Phase 0, GT-1)** The bake-off harness (FR-021) against the DC-12 API, then one adapter per candidate on
  throwaway branches; the report names every constraint found per candidate.
- **T-002 (Phase 0)** The DC-14 baseline on today's engine, on a Mordor and a Platåberget datadir, published in
  `research.md` §15.
- **T-003 (Phase 0, R-5)** Read Besu Bonsai's trie-log, reth's changesets, static files and sparse trie, Erigon's
  domains and ETL, geth's PBSS diff layers, and write the layout ADR draft (INF-008).
- **T-004 (GT-2)** The constitution amendment and ADR INF-007, coordinated with spec 019's CG-1.
- **T-005 (Track E, contract cleanup)** FR-003 and FR-026, in its own behaviour-neutral PR with the 84
  `EphemDataSource` tests green and the three downcasts gone.
- **T-006 (Track F, 017 amendment)** The DC-7 amendment to 017's DC-8 and FR-003a, as a docs PR on 017, approved by
  the user before Track F item 7 is enabled.
- **T-007 (Phase 0, GT-3)** The cold-tier assessment (R-9): use cases, cost on the host class, files-only alternative.
- **T-008 (Track E)** The segment store for immutable chain data (FR-022), behaviour-neutral, golden-bytes kept.
- **T-009 (Track F item 11)** The parallel state root with sparse trie cache (FR-023), behind a flag until SC-001 and
  SC-012 are green; `beacon` and `forge` before implementation.
- **T-010 (standalone fix PR, after GT-0 reports; FR-031)** Replace `RocksDbDataSource.dbLock` with a close-fence (an
  open/closed state plus in-flight count); SC-004-style saturating-write test; `vault` owns, `eye` validates on
  Mordor and Platåberget. Ships on the RocksDB engine, independent of GT-1.
- **T-011** *(moved)* Fixed-width `UInt256` is spec 021 (T-000..T-007 there).
- **T-012 (Track R)** Bounded read executors and per-method caps (FR-027); circe streaming codecs for traces and logs
  (FR-029); `conduit` owns, `flow` reviews the streams, `prism` reviews.
- **T-013 (Phase 0, GT-1)** The RPC read-load arm of the harness (FR-028), with the four comparator clients provisioned
  on the same hardware and the fixture ranges of OQ-14.

## Amendment requests from the fleet specs (023–026) — pending acceptance

*Added 2026-10-30. Raised by [spec 023](../023-fleet-topology/spec.md) §"Amendment requests to spec 018" because the
Phase 0 and Phase 1 decisions above lock them in. Owner: `vault`; the user accepts or rejects each. Nothing in this
section changes a DC, FR or SC of this spec until accepted; when one is accepted, edit the item it names and mark the
row here.*

| # | Target in this spec | Request | Needed by | Status |
| --- | --- | --- | --- | --- |
| 1 | GT-1 harness (FR-021, FR-028) | Add a **read-fleet profile**: N reader processes plus one writer, concurrency 8–64, larger host class; record whether the engine supports safe **multi-process read-only access** (DC-5 reader discipline across processes). | 023 OQ-4 (`sim` reading a follower's store) | Pending |
| 2 | F2 changeset format (FR-010, `data-model.md`) | Store the **post-value** beside the previous value; prefix every record with a **schema version** and `chainKey`. One record then serves rollback, follower replay, export and balance derivation. | 025 GT-1 / DC-9 (must land before F2 freezes) | Pending |
| 3 | Track H slice H3 (FR-025) | **The node does not write ClickHouse.** The `ColdStore` write side publishes to Kafka (025 FR-002); the indexer owns analytics tables; H4's `eth_getLogs` routing reads them through the `ColdStore` read adapter. | 025 DC-7 | Pending |
| 4 | T-010 close-fence (FR-031) | Schedule early (it is independent of GT-1 and merge-able on RocksDB). | 026 (followers and `sim` read concurrently) | Pending — already the intent of OQ-7 / FR-031; the request is only about ordering |
| 5 | Pruning decisions (OQ-3, OQ-5; FR-010 archive) | Leave a mode that **retains every changeset**, so the `archive` role needs no second storage layout. | 026 GT-2 / FR-005 | Pending |
| 6 | FR-018 runbook list | Drop `multi-instance.conf`: retired by 023 FR-006 (one process, one chain). | 023 T-002 | Pending |
