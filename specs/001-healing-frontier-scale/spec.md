# Feature Specification: Post-SNAP Healing Frontier-Rebuild Scalability

**Feature Branch**: `fix/healing-frontier-scale`

**Created**: 2026-06-06

**Status**: Draft

**Input**: User description: "Layer 1 (in the draft, validated by compile): replace the unbounded visited set with a 4M-entry LRU (insertion-order eviction, ~320 MB). This converts the OOM-restart loop into a walk that completes without crashing — and it's provably complete (pendingHashSet de-dups any re-discovered missing node; evicted present nodes only re-walk on shared refs, dominated by the childless empty-storage-root on ETC). Layers 2–3 (documented in docs/design/healing-frontier-scale.md, follow-on): Persist the frontier + resume — write pendingTasks to a dedicated RocksDB CF so restart resumes instead of re-walking the full state (O(frontier), not O(full state)). Needs a new CF + constructor wiring from SNAPSyncController. First-walk throughput — parallelise the DFS reads / RocksDB tuning."

## Overview

After SNAP sync, a Fukuii node "heals" the residual missing trie nodes before it can
transition to regular block-by-block sync. When the node restarts mid-healing and the state
root is already in local storage, it rebuilds the list of still-missing nodes (the *frontier*)
by walking the entire state trie — every account plus every storage trie. On a large chain
such as Ethereum Classic mainnet (35M+ state nodes, ~77 GB on disk) this walk's in-memory
bookkeeping grows without bound, exhausts the JVM heap, and — because the node is configured to
exit on out-of-memory — produces an unrecoverable OOM → restart → re-walk loop. The node never
reaches regular sync.

This feature makes post-SNAP healing scale to ETC-mainnet-sized state (and beyond) so that an
operator's node always recovers to regular sync. It is delivered in three layered slices of
increasing scope and decreasing urgency.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Node recovers from post-SNAP healing without OOM-looping (Priority: P1)

An operator runs a Fukuii node that has finished SNAP sync on a large chain (ETC mainnet) and
then restarts during the healing phase. On restart the node rebuilds the missing-node frontier
by walking the full state trie. The operator expects the node to finish that walk and continue
healing — not to repeatedly crash with out-of-memory and restart from scratch.

**Why this priority**: This is the OOM-stopper. Without it, an ETC-mainnet node is permanently
wedged after SNAP sync and can never reach regular sync — a total loss of node function. It is
the minimum viable slice: small, localized, and immediately makes the node recoverable. (This
slice is implemented in the current draft and validated by compile.)

**Independent Test**: Start a node whose local state contains tens of millions of trie nodes
and trigger a post-SNAP healing restart. Confirm the frontier-rebuild walk completes, the
process does not exit on out-of-memory, and bookkeeping memory stays bounded regardless of how
many nodes the walk visits.

**Acceptance Scenarios**:

1. **Given** a node with 35M+ state nodes in local storage that restarts during post-SNAP
   healing, **When** it rebuilds the frontier by walking the full state trie, **Then** the
   walk completes without the process exiting on out-of-memory.
2. **Given** the same walk in progress, **When** the number of nodes visited exceeds the
   bounded-memory cap, **Then** memory used by the walk's visited-node bookkeeping stays at or
   below the cap (does not grow with total state size).
3. **Given** a present node was evicted from the visited bookkeeping and is later reached again
   via a shared reference, **When** the walk re-encounters it, **Then** the walk re-reads it,
   continues, and still terminates (no infinite loop, no duplicated frontier entry).
4. **Given** a missing node is re-discovered after the cache evicted its prior visit record,
   **When** it is added to the frontier, **Then** it is de-duplicated so it is healed exactly
   once.

---

### User Story 2 - Restart resumes healing instead of re-walking the full state (Priority: P2)

An operator's node restarts during healing. Instead of re-discovering the entire frontier by
re-walking the full state trie (an I/O-bound pass that can take days on ETC mainnet), the node
loads the previously discovered, still-outstanding frontier from durable storage and resumes
healing immediately.

**Why this priority**: Layer 1 makes the node recoverable but the first/only frontier rebuild
is still a full-state walk that can take days; every unlucky restart pays that cost again.
Persisting the frontier makes restart proportional to the *outstanding* work (O(frontier))
rather than the *total* state (O(full state)), so the expensive walk happens at most once.

**Independent Test**: Run healing far enough to populate a non-trivial frontier, persist it,
restart the node, and confirm it resumes from the persisted frontier and skips the full-state
walk; then corrupt or remove the persisted frontier and confirm it safely falls back to the
full walk.

**Acceptance Scenarios**:

1. **Given** healing has discovered and persisted a non-empty frontier, **When** the node
   restarts, **Then** it loads the persisted frontier and resumes healing without performing
   the full-state walk.
2. **Given** a node is healing, **When** a node is queued into the frontier and later healed,
   **Then** the persisted frontier gains the queued entry and loses the healed entry, so it
   reflects only outstanding work.
3. **Given** no persisted frontier exists (first post-SNAP run) or the persisted frontier is
   missing/unreadable/corrupt, **When** the node starts healing, **Then** it falls back to the
   full-state walk rather than wedging or skipping healing.

---

### User Story 3 - First full walk completes in an operationally acceptable time (Priority: P3)

When a full-state walk is unavoidable (first post-SNAP run, or no persisted frontier), the
operator expects it to finish in hours, not days. The walk's throughput is improved so the
initial frontier rebuild is not itself an operational blocker.

**Why this priority**: This is a pure optimization on top of Layers 1–2. With Layer 1 the walk
completes (recoverable) and with Layer 2 it happens at most once; speeding it up is valuable
but not required for correctness or recoverability. It is deferred until the first walk is
shown to still be too slow in practice.

**Independent Test**: Measure the throughput (nodes traversed per second) of the full-state
walk on a large chain before and after the optimization and confirm a material improvement and
an acceptable total wall-clock time.

**Acceptance Scenarios**:

1. **Given** a full-state walk on a 35M+ node chain, **When** the optimization is enabled,
   **Then** the walk's throughput is materially higher than the serial baseline (~142
   nodes/s).
2. **Given** the optimization changes how the node reads from storage, **When** the walk runs,
   **Then** total memory usage stays within the host's documented budget (no regression into
   the memory-over-subscription failure mode).

---

### Edge Cases

- **Cap set very low**: A smaller bounded-memory cap causes more evictions and therefore more
  re-walks of shared subtries. The walk MUST still complete and heal every missing node; it is
  only slower, never incorrect.
- **Pathological sharing**: If many distinct references point at the same present subtrie, that
  subtrie may be re-walked after eviction. On ETC the dominant shared node is the childless
  empty-storage-root (a cheap re-read), but the design MUST not assume sharing is rare — the
  walk MUST terminate regardless.
- **Crash mid-walk (Layer 1 only)**: A restart before any frontier is persisted re-runs the
  full walk from zero; this is acceptable for Layer 1 because the walk now completes.
- **Persisted frontier disagrees with on-disk state (Layer 2)**: If a persisted frontier entry
  has actually already been healed, re-processing it MUST be a no-op (idempotent), not an
  error.
- **Empty frontier**: If the walk (or the persisted load) finds the trie already fully healed,
  the node MUST proceed to regular sync rather than stalling.
- **Already-wedged node**: For a node that is already stuck in the OOM loop today, importing a
  trusted checkpoint remains the fast operational unblock; this feature does not replace that
  escape hatch.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The post-SNAP frontier-rebuild walk MUST complete on chains with tens of millions
  of state nodes (≥ 35M) without the node process exiting on out-of-memory.
- **FR-002**: The memory consumed by the walk's visited-node bookkeeping MUST be bounded by a
  fixed cap that is independent of total state size, with a default that fits comfortably
  within the documented heap budget for ETC mainnet (default ≈ 320 MB).
- **FR-003**: The walk MUST discover every missing trie node in the frontier; bounding memory
  MUST NOT cause any missing node to be skipped (completeness is preserved).
- **FR-004**: Re-discovering a node that is already queued in the frontier MUST be
  de-duplicated so each missing node is healed exactly once.
- **FR-005**: The walk MUST terminate for any input state, even when bounded memory forces
  evicted nodes to be re-walked (re-walks add bounded work, never cycles).
- **FR-006**: The healing outcome MUST be complete and correct — after healing, the local state
  MUST be consistent with the chain state root and the node MUST be able to transition to
  regular sync and import subsequent blocks.
- **FR-007** *(Layer 2)*: On restart, when a non-empty persisted frontier exists, the node MUST
  resume healing from it and MUST skip the full-state walk.
- **FR-008** *(Layer 2)*: The persisted frontier MUST be updated as the node makes progress —
  entries added when nodes are queued and removed when nodes are healed — so it reflects only
  outstanding work at any time.
- **FR-009** *(Layer 2)*: When no persisted frontier exists, or it is unreadable or corrupt,
  the node MUST fall back to the full-state walk (fail safe to Layer 1 behavior, never wedge or
  silently skip healing).
- **FR-010** *(Layer 2)*: Resuming from a persisted frontier MUST be idempotent — an entry that
  has already been healed MUST be a no-op when re-encountered.
- **FR-011** *(Layer 3)*: When a full-state walk is required, the system SHOULD complete it in
  an operationally acceptable time (target: hours, not days) without exceeding the host's
  documented memory budget.
- **FR-012**: The bounded-memory cap SHOULD be operator-tunable, with the default applying when
  unset.

### Key Entities *(include if feature involves data)*

- **Healing frontier**: The set of trie-node references (identified by node hash, with the trie
  path that locates them) that are known to be missing from local storage and still need to be
  fetched and stored. Healing is complete when the frontier is empty.
- **Visited-node bookkeeping**: The transient record, during a frontier-rebuild walk, of which
  present nodes have already been traversed. It exists only to avoid redundant re-walks; it is
  not part of the healing result and may be bounded/evicted without affecting completeness.
- **Persisted frontier (Layer 2)**: A durable copy of the outstanding frontier (`node hash →
  locating path`) that survives restarts, allowing healing to resume rather than re-walk. Kept
  in sync with the in-memory frontier as nodes are queued and healed.
- **State trie**: The full account trie plus every per-account storage trie; the structure the
  walk traverses to find missing nodes. Content-addressed, so subtries can be shared by
  reference.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A node that completed SNAP sync on a chain with ≥ 35M state nodes reaches regular
  sync after a healing restart with **zero** out-of-memory-induced restarts attributable to the
  frontier rebuild (down from an unbounded restart loop).
- **SC-002**: Peak memory used by the frontier-rebuild walk's bookkeeping stays at or below
  ~350 MB regardless of total state size (down from ~2.9 GB observed unbounded), and total heap
  usage during the walk stays below the configured maximum.
- **SC-003**: After healing completes, 100% of missing trie nodes are healed: the node's local
  state is consistent with the chain state root and the node imports subsequent blocks under
  regular sync.
- **SC-004** *(Layer 2)*: After a restart with a persisted frontier, healing resumes in time
  proportional to the outstanding frontier (minutes) rather than re-walking the full state
  (days) — the full-state walk runs at most once per node lifetime in the common case.
- **SC-005** *(Layer 3)*: When a full-state walk is required, it completes within an
  operationally acceptable window (target single-digit hours on ETC mainnet), a material
  improvement over the ~142 nodes/s serial baseline that would take days.

## Assumptions

- **Scope = all three layers as one feature**: This spec covers the frontier-rebuild
  scalability problem end to end. Layer 1 (bounded bookkeeping) is the MVP and is already in the
  working draft; Layer 2 (persist + resume) and Layer 3 (throughput) are prioritized follow-ons
  that ship as separate changes. The layered rollout in `docs/design/healing-frontier-scale.md`
  is authoritative for sequencing.
- **De-dup is relied upon for correctness**: The existing frontier de-duplication (a node is
  not queued if already queued) is assumed correct and is what makes bounding the visited
  bookkeeping safe.
- **Sharing profile on ETC**: The dominant shared present node is the childless
  empty-storage-root, so re-walks caused by eviction are cheap in practice; correctness does
  not depend on this, only the cost estimate does.
- **Out-of-memory = restart**: The node runs with exit-on-out-of-memory, so an unbounded walk
  manifests as a crash-restart loop rather than a hang. This is why bounding memory is the fix.
- **Documented heap budgets apply**: Operators run ETC mainnet with the documented heap sizing
  (≥ 5 GB); the default cap is chosen to fit within that budget.
- **Checkpoint import is the operational unblock**: For nodes already wedged in the loop,
  importing a trusted checkpoint remains the fast recovery path and is out of scope for this
  feature.
- **Layer 3 read changes respect the memory trade-off**: Any parallelism or storage tuning in
  Layer 3 must respect the open-files/block-cache vs. heap trade-off already documented for the
  recovery scan, so it does not reintroduce host memory over-subscription.

## Dependencies

- The persisted-frontier slice (Layer 2) depends on a durable storage namespace for the
  frontier and on wiring that namespace into the healing coordinator at construction time
  (mirroring how flat slot storage is wired today).
- All three layers operate within the post-SNAP healing phase of the sync state machine and
  assume SNAP sync has already produced a local state whose root is in storage.
