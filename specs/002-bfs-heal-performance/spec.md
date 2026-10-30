# Feature Specification: Post-SNAP BFS State-Healing Walk — Performance, Redundancy-Avoidance, and Observability

**Feature Branch**: `002-bfs-heal-performance`

**Created**: 2026-06-13

**Status**: Superseded by 022 (2026-10-30). Shipped (51 of 59 tasks; 14 main-source sites cite it), then its BFS dispatch was replaced by spec 022's deepest-first dispatch (D1) and held-node commit (D3).
**Input**: User description: "Post-SNAP BFS state-healing walk: performance, redundancy-avoidance, and observability on resource-constrained ETC mainnet hosts."

**Closed** (2026-10-30):
- Reason: mechanism replaced after shipping. Spec 022 FR-019 marks the BFS heal design superseded.
- Lives on in: the heal actors under `blockchain/sync/snap/`, as reshaped by 022 slices 1–3 (#1682, #1684, #1685).

## Context & Background

After SNAP sync completes, the node runs a breadth-first walk over the full state trie
(`rebuildFrontierBFS`) to rebuild the healing frontier and detect referenced-but-missing trie
nodes. On the barad-dûr ETC-mainnet node this walk runs at only ~1,000–1,170 nodes/s, projecting
~17 hours to traverse Level 7 (~73M nodes), versus ~30–45 minutes on the prior in-memory
depth-first walk (v0.6.15). A node stuck in this walk cannot transition to regular sync and cannot
serve as a checkpoint source.

A focused investigation (2026-06-13) established the root causes. **These are settled facts, not
open questions:**

- **NOT a cache-config regression.** `db.conf` and the `RocksDbDataSource` cache/buffer/bloom
  settings are byte-identical between the fast v0.6.15 and the slow v0.7.7; the over-subscription
  memory fix (PR #1183) predates v0.6.15.
- **NOT unbatched reads on this node.** The production node runs **archive** pruning, so
  `ArchiveNodeStorage.multiGet` issues one `db.multiGetAsList` per 50K-hash chunk. (A *basic*-pruning
  node would silently degrade to per-key gets — a separate latent foot-gun, in scope below.)
- **Dominant cause: host CPU/GC saturation at low effective parallelism.** The walk runs at
  `effectiveParallelism = min(configured, availableProcessors − 2) = 2` on the 4-core/10GiB host,
  with each sub-range processing its chunks serially; the JVM sits at ~88–90% of all cores, load
  average ~11, heap ~85%.
- **Secondary: the DFS→BFS refactor is an intentional memory-for-safety trade**, not a defect. The
  in-memory DFS OOM'd at Level 7; BFS bounds heap by writing each level to a RocksDB-backed queue
  (~73M extra writes + reads on top of the same trie reads). The "30–45 min" figure was almost
  certainly a smaller trie (Mordor ~19M nodes). Reverting is rejected — it OOMs on ETC mainnet.
- **Co-limiter: disk/cache per-read cost.** A 512MB shared block cache covers <1% of the 54.66GB
  state column family on a DRAM-less SSD behind full-disk encryption; `max-open-files=512` is below
  the 1,591 SST file count, forcing table-handle reopen churn. The SSD is **not** saturated
  (%util 50–71%, queue depth ~1) — there is headroom.
- **Highest-leverage observation: the walk is likely redundant.** Health logs show
  `healed=0 / pending=0` and zero frontier found across all of Level 6 — a fully-healed trie. A
  "complete persisted snapshot" fast-path already exists to skip the walk, but the completeness
  marker is unset, most likely cleared by a stale pivot refresh.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Do not re-run a heal that is already complete (Priority: P1)

An operator restarts a node whose SNAP state was already fully healed in a prior run. The node must
recognize the trie is complete and transition to regular sync within minutes, instead of launching
a multi-hour full-state verification walk that finds nothing to heal.

**Why this priority**: This is the highest-leverage outcome. When the trie is genuinely complete,
the *fastest possible walk is no walk*. The completeness fast-path already exists; the gap is that
the marker is being cleared (or never set) when it should persist. Fixing this removes the ~17-hour
walk entirely for the common restart case.

**Independent Test**: Seed a node with a complete state and a set completeness marker, restart it,
and confirm it skips the full-state walk and reaches regular sync. Then exercise the pivot-refresh
path and confirm the marker is only cleared when a refresh genuinely invalidates completeness.

**Acceptance Scenarios**:

1. **Given** a node whose state trie is complete and whose completeness marker is set, **When** the
   node restarts, **Then** it skips the full-state rebuild/verification walk and proceeds to regular
   sync without re-traversing the trie.
2. **Given** a healing run that finishes with zero missing nodes, **When** the run completes, **Then**
   the completeness marker is persisted so the next restart skips the walk.
3. **Given** a pivot refresh during sync, **When** the refresh does not invalidate already-healed
   subtries, **Then** the completeness marker is **not** spuriously cleared.
4. **Given** a node whose state is genuinely incomplete (missing nodes exist), **When** it restarts,
   **Then** it still performs the walk and heals the gaps — the skip never hides a real gap.

---

### User Story 2 - See why the walk is slow, with real numbers (Priority: P1)

An operator (or developer) investigating slow healing can read concrete, live metrics that
distinguish the candidate bottlenecks — cache hit rate, per-phase time, GC pressure, and walk
inflation — instead of inferring them indirectly.

**Why this priority**: The investigation that produced this spec had to infer the bottleneck from
`iostat`, `docker stats`, and log timing because the relevant counters were not exposed (RocksDB
statistics were disabled; per-phase walk timing did not exist; visited-set inflation was assumed,
not measured). Without observability, every future tuning decision is a guess, and the project
constitution requires evidence ("ran <command> — result"), not anecdote.

**Independent Test**: On a test node, trigger a walk and confirm each new signal is emitted and
reflects reality: cache hit/miss counters change, per-phase timings sum to wall time, GC pressure is
reported, and the enqueued-vs-distinct ratio matches a known synthetic trie's true node count.

**Acceptance Scenarios**:

1. **Given** a running node, **When** an operator inspects metrics, **Then** RocksDB block-cache
   hit/miss rates for the state store are observable.
2. **Given** an active walk, **When** an operator inspects metrics/logs, **Then** the time spent in
   queue read vs. trie read vs. queue write per processing batch is reported separately.
3. **Given** an active walk, **When** an operator inspects metrics, **Then** garbage-collection
   pressure (pause time / fraction of wall time) is observable.
4. **Given** an active walk, **When** an operator inspects metrics, **Then** the ratio of total
   enqueued nodes to distinct nodes per level is reported, making any visited-set re-walk inflation
   measurable rather than assumed.

---

### User Story 3 - Use the host's available CPU and I/O headroom (Priority: P2)

On a host with spare cores and unsaturated disk, the walk should scale to use them instead of being
pinned to two serial reader threads.

**Why this priority**: The dominant bottleneck is that the walk uses only 2 effective threads and
processes each sub-range serially, while the SSD sits at ~50–70% utilization with queue depth ~1.
Raising concurrency and overlapping read/decode work converts idle headroom into throughput — but
only matters when cores/disk are actually free, so it ranks below removing the walk entirely (US1)
and seeing the numbers (US2).

**Independent Test**: On a multi-core host with a large trie, measure walk throughput before and
after, holding the trie and hardware fixed; confirm throughput rises when spare cores/disk exist and
does not regress when they do not.

**Acceptance Scenarios**:

1. **Given** a host with more than the minimum cores free, **When** the walk runs, **Then** it uses
   more than two concurrent readers (subject to a configurable ceiling).
2. **Given** a walk processing a level, **When** batches are read and decoded, **Then** queue read,
   trie read, and decode overlap rather than running strictly one-after-another.
3. **Given** an unsaturated disk, **When** trie nodes are fetched, **Then** read requests are issued
   at a queue depth greater than one.
4. **Given** a CPU-saturated host, **When** the walk runs, **Then** raising the parallelism setting
   does not make throughput worse than the serial baseline.

---

### User Story 4 - Tune resource limits without changing code (Priority: P2)

An operator running on constrained or unusual hardware can adjust the file-handle limit, block-cache
size, and heap allocation via configuration, guided by the observability from US2.

**Why this priority**: `max-open-files=512` is below the SST file count (1,591), forcing
index/filter re-reads; the block cache may be undersized for very large state CFs. These are
config-only, non-consensus levers that materially affect per-read cost — but their right values
depend on the US2 metrics, so they follow observability.

**Independent Test**: Change each setting in configuration, restart a test node, and confirm the
running database reflects the new value (open file handles, cache capacity, heap) and that defaults
remain safe on the reference host.

**Acceptance Scenarios**:

1. **Given** a database with more SST files than the open-file limit, **When** the operator raises
   the limit, **Then** table handles stay open and index/filter re-read churn drops.
2. **Given** a low observed cache hit rate, **When** the operator raises the block-cache size within
   the container memory budget, **Then** the hit rate improves without exceeding the memory limit.
3. **Given** the documented defaults, **When** a node runs unmodified on the reference host, **Then**
   it stays within its memory limit (no OOM).

---

### User Story 5 - Faster queue reads via forward scan (Priority: P3)

The dense, sequentially-keyed BFS level queue is read with a forward range scan rather than batched
random point lookups.

**Why this priority**: A clean, safe, modest win. The queue read is the cheaper of the two reads per
batch (the random trie read dominates), so this banks a real efficiency gain without changing walk
semantics — but it will not by itself move overall walk time much.

**Independent Test**: Round-trip the queue through the new read path and confirm identical results to
the existing path on both the real database and the in-memory test double; measure the per-batch
queue-read time before and after on a large queue.

**Acceptance Scenarios**:

1. **Given** a populated level queue, **When** a range is read via the forward scan, **Then** the
   entries returned are identical (order and content) to the previous read path.
2. **Given** the in-memory/test storage backend, **When** the forward-scan read is used, **Then** it
   falls back to a correct equivalent and tests pass unchanged.
3. **Given** the walk aborts mid-level, **When** the scan is interrupted, **Then** no database
   iterator or native resource is leaked.

---

### User Story 6 - Honest visited-set accounting (Priority: P3)

The bounded visited set is named and documented accurately, and its capacity is tuned from measured
inflation rather than a guessed multiplier.

**Why this priority**: The set is labelled an "LRU" but is actually insertion-order (FIFO) eviction;
its documented per-entry cost (~80B) is roughly half the real cost (~120–150B with the wrapper type).
Misleading documentation led to a proposal to raise the cap to a value that would exhaust the heap.
Correcting the record and tuning from the US2 inflation metric prevents repeat mistakes.

**Independent Test**: Review naming/docs for accuracy; with the US2 inflation metric, confirm a cap
change moves the measured enqueued-vs-distinct ratio in the expected direction without exceeding the
memory budget.

**Acceptance Scenarios**:

1. **Given** the visited-set code and docs, **When** read by a maintainer, **Then** the eviction
   policy (insertion-order/FIFO) and realistic per-entry memory cost are stated correctly.
2. **Given** the inflation metric from US2, **When** the cap is adjusted, **Then** the change is
   justified by measured inflation, and the resulting memory stays within the heap budget (the cap is
   not raised to a value that risks OOM).

---

### User Story 7 - Basic-pruning nodes do not silently degrade (Priority: P3)

A node running basic (non-archive) pruning reads trie nodes in batches, not one-by-one, during the
healing walk.

**Why this priority**: Defensive. The current production node uses archive pruning and is unaffected,
but on a basic-pruning node the batched read silently falls back to per-key point lookups (~50K
serial gets per batch), which would reproduce exactly the slowdown this feature targets. Closing the
gap prevents a future regression for a supported configuration.

**Independent Test**: On a basic-pruning storage backend, issue a multi-node read and confirm it
resolves to a single batched database call, matching the archive path's behavior.

**Acceptance Scenarios**:

1. **Given** a basic-pruning storage backend, **When** the walk reads a batch of node hashes, **Then**
   the read resolves to one batched database call rather than N sequential point lookups.
2. **Given** either pruning mode, **When** a batch of hashes is read, **Then** the set of nodes
   returned is identical — the optimization changes performance only, never results.

---

### Edge Cases

- **Stale completeness marker after pivot refresh**: a refresh that does not invalidate healed
  subtries must not clear the marker; a refresh that does invalidate state must clear it (US1).
- **Genuinely incomplete state on restart**: the skip must never fire when missing nodes exist — an
  incomplete heal that is wrongly declared complete persists a bad state root and fails at block
  import. Completeness must be conservative.
- **CPU-saturated host**: raising parallelism must not degrade throughput below the serial baseline
  (US3).
- **Memory budget**: raising block-cache size or visited-set cap must never push the node past its
  container memory limit (US4, US6).
- **Walk interrupted mid-level**: forward-scan iterators must close cleanly on abort (US5).
- **Archive vs basic pruning**: both must produce identical heal results; only performance differs
  (US7).

## Requirements *(mandatory)*

### Functional Requirements

**Redundancy avoidance (US1)**

- **FR-001**: The system MUST skip the full-state rebuild/verification walk on startup when the state
  trie is recorded as complete, transitioning to regular sync without re-traversing the trie.
- **FR-002**: The system MUST persist the completeness marker when a healing run finishes with zero
  outstanding missing nodes.
- **FR-003**: The system MUST clear the completeness marker only when an event genuinely invalidates
  already-healed state; a pivot refresh that does not invalidate healed subtries MUST NOT clear it.
- **FR-004**: The completeness skip MUST be conservative: it MUST NOT fire when any referenced node is
  missing from the local store.

**Observability (US2)**

- **FR-005**: The system MUST expose state-store cache hit and miss rates.
- **FR-006**: The system MUST report, per processing batch or aggregated per level, the time spent in
  queue read, trie-node read, and queue write separately.
- **FR-007**: The system MUST expose garbage-collection pressure (pause time and/or fraction of wall
  time) during the walk.
- **FR-008**: The system MUST report the ratio of total enqueued nodes to distinct nodes per level so
  visited-set re-walk inflation is measurable.

**Throughput on capable hosts (US3)**

- **FR-009**: The walk MUST be able to use more than two concurrent readers on hosts with spare cores,
  bounded by a configurable ceiling.
- **FR-010**: Within a level, queue read, trie read, and decode work MUST be able to overlap rather
  than execute strictly sequentially.
- **FR-011**: Trie-node reads MUST be issued at a queue depth greater than one when the disk has
  headroom.
- **FR-012**: Increasing the parallelism setting MUST NOT reduce throughput below the serial baseline
  on a CPU-saturated host.

**Tunable resource limits (US4)**

- **FR-013**: The open-file limit MUST be configurable and settable above the database's SST file
  count.
- **FR-014**: The state-store block-cache size MUST be configurable within the container memory
  budget.
- **FR-015**: Default resource settings MUST keep the node within its memory limit on the reference
  host.

**Forward-scan queue read (US5)**

- **FR-016**: The level queue MUST support a forward range-scan read that returns entries identical
  (order and content) to the existing batched read.
- **FR-017**: The forward-scan read MUST have a correct fallback for non-native (in-memory/test)
  storage backends.
- **FR-018**: The forward-scan read MUST release all iterator/native resources even when the walk is
  interrupted.

**Visited-set accounting (US6)**

- **FR-019**: Visited-set documentation and naming MUST accurately state the eviction policy
  (insertion-order/FIFO) and a realistic per-entry memory cost.
- **FR-020**: Any change to the visited-set capacity MUST be justified by the measured inflation
  metric (FR-008) and MUST keep memory within the heap budget.

**Basic-pruning batching (US7)**

- **FR-021**: On a basic-pruning storage backend, a multi-node read during the walk MUST resolve to a
  single batched database call, not N sequential point lookups.
- **FR-022**: Batched reads MUST return results identical to the per-key path in every pruning mode.

**Cross-cutting correctness & process**

- **FR-023**: No change in this feature may alter which nodes the walk enqueues, visits, or declares
  missing in a way that could let a genuinely-missing node go undetected — heal completeness MUST be
  preserved exactly. Any change touching enqueue/skip/detection logic MUST follow the `forge`
  consensus-critical change protocol.
- **FR-024**: The Bloom-filter visited set is explicitly excluded (see Out of Scope) and MUST NOT be
  introduced as part of this feature.
- **FR-025**: Tests MUST be deterministic (no wall-clock sleeps) and MUST include a regression test
  asserting that a missing node behind a *shared* branch/extension ancestor is still discovered even
  after the visited set reports that ancestor as seen.
- **FR-026**: Changes MUST NOT require restarting the live production node to take effect without
  operator sign-off; config changes apply on the operator's next controlled restart.

### Key Entities *(include if feature involves data)*

- **State trie node**: a content-addressed (hash-keyed) entry in the state column family; the unit the
  walk reads, classifies (branch / extension / leaf), and checks for presence.
- **Level queue**: a persisted, densely sequentially-keyed queue holding the nodes discovered for the
  next BFS level; written as each parent is expanded and read back when that level is processed.
- **Visited set**: a bounded, insertion-order in-memory set that gates whether a child node is
  enqueued, preventing redundant re-walks of shared subtries; eviction causes a bounded re-walk, never
  a skip.
- **Completeness marker**: a persisted flag indicating the state trie has been fully healed; when set,
  it authorizes skipping the full-state walk on restart.
- **Healing frontier**: the persisted set of known-missing nodes awaiting fetch; its emptiness, with
  the completeness marker, defines a complete heal.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A node with a complete, marked state reaches regular sync after restart in under 5
  minutes, with no full-state walk performed (down from ~17 hours).
- **SC-002**: When a genuine full-state walk is required, throughput on a host with at least 4 cores
  free and unsaturated disk is at least 2× the current serial baseline on the same trie and hardware.
- **SC-003**: An operator can determine, from exposed metrics alone, whether a slow walk is bound by
  cache misses, GC/CPU, or disk — without running external tracing tools.
- **SC-004**: Visited-set re-walk inflation (enqueued ÷ distinct per level) is reported as a number
  and is confirmed to be below 1.5× on the reference ETC-mainnet trie (or, if higher, the cap is tuned
  on that evidence).
- **SC-005**: No configuration in this feature, at its documented defaults or operator-tuned values
  within the stated memory budget, causes the node to exceed its container memory limit.
- **SC-006**: Heal completeness is unchanged: on a state with deliberately injected missing nodes
  (including nodes behind shared ancestors), the walk detects 100% of them, identical to the pre-change
  behavior.
- **SC-007**: The forward-scan queue read reduces measured per-level queue-read time relative to the
  batched-point-lookup path on a large queue, with identical returned data.

## Assumptions

- **Target hardware**: the reference deployment is the barad-dûr ETC-mainnet node (4 cores, 10GiB
  container limit, 6g heap, DRAM-less SATA SSD behind full-disk encryption). Improvements are tuned to
  not regress on this host while scaling up on larger hosts.
- **Production pruning mode is archive**; basic-pruning support (US7) is defensive for other
  deployments, validated functionally rather than on the production node.
- **The "30–45 min" historical baseline was a smaller trie** (Mordor-scale, ~19M nodes) on the
  in-memory DFS; it is not a like-for-like target for ETC mainnet's ~73M-node Level 7. The primary
  time win comes from skipping redundant walks (US1), not from matching that number on a genuine walk.
- **The completeness fast-path already exists**; US1 is primarily a diagnosis-and-fix of why the
  marker is unset/cleared, not a new mechanism.
- **The live node is mid-walk** and must not be restarted to apply changes without operator sign-off;
  the persisted frontier is expected to resume, but in-progress walk state is lost on restart.
- **Acceptance threshold for "uses spare cores" (SC-002)** is set at 2× as a defensible default in the
  absence of a stated target; it may be revised after US2 metrics quantify the per-phase breakdown.
- **Scala 3.3.7 LTS**, deterministic tests, `scalafmt`/`scalafix` clean, and `sbt pp` green before any
  PR, per the project constitution.

## Out of Scope / Rejected

- **Bloom-filter visited set** — rejected. A false positive reports a never-visited node as visited,
  skipping its enqueue and its presence check and dropping its entire subtree, which can hide a
  missing node and produce a state-root mismatch at block import that persists across restarts. This
  re-introduces the exact silent-truncation bug the current bounded set was written to fix, and was
  already rejected in the prior research record.
- **Reverting to the in-memory DFS** — rejected; it OOMs at Level 7 on ETC mainnet (the documented
  reason BFS exists).
- **Treating RocksDB cache config as the regression** — refuted; cache/buffer/bloom settings are
  byte-identical between v0.6.15 and v0.7.7.
- **Tuning the production node live** without operator sign-off — out of scope; config changes land for
  the next controlled restart.
