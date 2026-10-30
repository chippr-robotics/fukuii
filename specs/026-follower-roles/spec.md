# Feature Specification: Follower Roles — `replica` and `archive`

**Feature Branch**: `026-follower-roles`

**Created**: 2026-10-30

**Status**: **Draft, ready for `/speckit-clarify` on OQ-1..OQ-4.** Companion of spec 023. Depends on spec 013 (seed)
and spec 025 (the record stream); on spec 018 only for the `archive` basis (GT-2) and for the later move to the
unified layout. **Revised 2026-10-30 (alignment review,
[023/alignment-review.md](../023-fleet-topology/alignment-review.md)
§2)**: followers apply the **semantic diff** of 025 through a `StateReplay` capability (018 amendment request 7) and
update their own commitment trie from the changed keys, so a `replica` MVP exists on today's storage layout before 018
Track F. Its two gates (GT-1, GT-2) can start as soon as a file-sink record stream exists (025 T-003).

**Input**: Spec 023 Goals "replica bootstrap and following so initial sync happens once per chain, not once per
replica" and user decision D-5 (an archive-capable role is sufficient for historical state).

---

## Problem

Serving balances and simulation to many users means many readers per chain. Today every reader is a full node that
SNAP-syncs on its own (days on Sepolia per spec 013 §1), holds only recent state on the Path scheme, and shares its
process with import. There is no role that:

- starts from a seed another node produced and reaches head without a sync,
- changes state **only** by applying canonical change records and verifies every resulting root,
- serves reads **pinned to an explicit block** and refuses, with a defined error, anything outside what it holds,
- retains **every** block's state so historical balances and historical `eth_call` are served at any height.

---

## Goals

1. **Bootstrap once per chain**: a follower starts from a snapshot (013) or genesis and follows the record stream.
2. **Followers verify**: every applied block's post-state root equals the header's; mismatch halts serving.
3. `replica` serves point-state reads within an explicit **retention window**; `archive` serves them, and `eth_call`
   and traces, at **any** canonical block.
4. Deep reorgs (ETC) are handled by rollback over changesets, never by serving a wrong state.

## Non-goals

- Routing requests to followers, choosing how many to run, or re-seeding them (Orbita).
- Historical **indices** (logs by address over long ranges, balance history tables): the indexer's, from 025 records.
- Changing the snapshot format beyond what FR-001 needs for ETC seeding (OQ-3).
- Serving writes (`eth_sendRawTransaction` forwards or is refused per role; 023 DC-9).
- Any consensus output.

---

## Key entities

- **Follower**: a `replica` or `archive` process (023 roles).
- **Seed**: the state a follower starts from: a 013 snapshot at block N (ETH family), a checkpoint archive or an ETC
  snapshot (OQ-3), or genesis (archive only, if GT-2 requires it).
- **Retention window**: for `replica`, the range `[head − W, head]` for which it holds state and changesets; outside
  it the defined error is returned.
- **Verified root**: a state root the follower computed itself and matched to the header; the only kind it serves.
- **Follow method**: apply change records (025) or re-execute canonical blocks; GT-1 picks the default. The prior is
  apply.
- **`StateReplay`**: the capability (018 amendment request 7; `vault`) that applies a semantic diff forward or
  backward to flat state, updates the commitment trie from the changed keys, and returns the root. On today's layout
  the trie update is `MerklePatriciaTrie.put`/`remove` over the changed accounts and slots (what import does after
  execution); under 018 F6/F11 it is the flat read path and the parallel root. The follower never sees the difference.

---

## Gates *(mandatory)*

- **GT-1: Bootstrap and follow measurement.** On Mordor and Platåberget, time and cost of (a) seed then re-execute
  blocks to head; (b) seed then apply change records to head; (c) records alone from genesis (Mordor only). Output:
  the FR-002 default per family, and the bound SC-001 uses.
- **GT-2: Archive basis.** On Mordor, disk cost and read latency of an archive built on 018's layout by retaining
  every changeset (018 FR-010 archive mode; 023 amendment request 5) versus any alternative 018 makes available
  (aged changesets to the cold tier, history indices). Decides FR-005's storage basis (OQ-1).

---

## Design constraints *(mandatory)*

**DC-1: Followers verify.** A follower that applies a change record recomputes the post-state root and compares it to
the header's `stateRoot` for every block, and checks each receipt root. On mismatch it **halts**, stops serving,
reports through 023 FR-004 (`ready = false`, a named fault), and keeps the last verified state for diagnosis. It never
serves state it has not verified. A follower that re-executes blocks (if GT-1 picks that) verifies the same way,
which it does for free.

**DC-2: Followers do not write canonical state.** A follower's state changes only by applying canonical records or
re-executing canonical blocks (023 DC-3). It opens no P2P listener, imports no block from a peer, and runs no miner
or Engine API server (023 DC-9).

**DC-3: The retention window is explicit; outside it is an error, never a guess.** `replica` reports its window in
023 FR-004 and returns a defined "outside retention window" error for any block below it. `archive` has no lower bound.

**DC-4: Rollback is by records.** A reorg (025 retractions) is applied by replaying the retracted blocks' records
**backward** (previous values) newest-first to the fork point, then applying the new records forward; the follower
keeps the records for its window so it needs no second undo log. `archive` can always roll back. A `replica` whose
window is shorter than the reorg depth **halts and reports that it must be re-seeded**; it does not serve across the
gap.

**DC-5: Seed once per chain.** A follower never SNAP-syncs. It starts from a seed artifact produced by `ingest` (or an
`archive`), verified per 013 §5.4 (root recomputed, anchor checked), then follows. SNAP code paths are not reachable
in follower roles.

**DC-6: Block-pinned reads.** Every read RPC accepts and requires an explicit block parameter (number, hash, or a tag
resolved against the follower's own head and reported back), so a router can pin a client to a height. `latest` is
resolved once per request and echoed; `pending` is refused with the role error.

**DC-7: Consensus-neutral.** Applying a diff and recomputing a root uses the same `mpt/` code as import; no new hashing
or encoding path. Anything in `mpt/` or `ledger/` routes through `forge` and `beacon`.

**DC-8: Readiness means "at head within a bound".** A follower reports `ready = true` only when its head is within the
configured lag bound of the source head it observes in the record stream and its last applied root is verified.

---

## User Scenarios & Testing *(mandatory)*

### User Story 1 — A replica bootstraps once and follows (Priority: P1)

**Independent Test**: SC-001, SC-002.

**Acceptance Scenarios**:
1. **Given** a seed at block N, **When** the replica starts, **Then** it applies records N+1..head and reports ready
   only after reaching head with every root verified.
2. **Given** a corrupted change record, **When** the replica applies it, **Then** the root check fails, the replica
   halts within that block and serves no read at the bad root.

### User Story 2 — Historical state is served by the archive role (Priority: P1)

**Independent Test**: SC-003.

**Acceptance Scenarios**:
1. **Given** an `archive` following from its seed, **When** `eth_getBalance` or `eth_call` is asked at an old block,
   **Then** it answers at that block's verified root, byte-identical to a reference archive client.
2. **Given** a `replica` asked for a block outside its window, **When** the request arrives, **Then** it returns the
   defined error, never a wrong answer.

### User Story 3 — A deep ETC reorg is survived or reported (Priority: P1)

**Independent Test**: SC-004.

**Acceptance Scenarios**:
1. **Given** an ETC reorg deeper than a replica's window, **When** the retractions arrive, **Then** the `archive` rolls
   back and continues, and the `replica` halts and reports "re-seed required".

### User Story 4 — A simulation pool reads a follower (Priority: P2)

**Independent Test**: 023 SC-002 uses this story.

**Acceptance Scenarios**:
1. **Given** `sim` processes against a `replica`, **When** they ask for `eth_call` state at a pinned block inside the
   window, **Then** every answer is at that block; **When** the block falls out of the window mid-session, **Then** the
   defined error is returned and the router (Orbita) re-pins.

### Edge cases

- A record arrives whose parent the follower has not applied: wait, with a bounded queue; never apply out of order
  (FR-008). A persistent gap is a fault reported through health.
- Duplicate records after an `ingest` restart (025 DC-3): no-op.
- The seed's block is already below the source's finality watermark by a long way (stale snapshot): follow from the
  seed; the time to head is GT-1's figure; readiness waits.
- A seed whose root does not match its header: refuse the seed (013 §5.4 failure path), do not start following.
- `replica` with `W` larger than available disk: fail at start with a sizing error, not at runtime.
- `archive` on an ETH-family chain receiving a retraction below the finality watermark: refuse and halt (025 FR-007
  should have prevented it; defence in depth).

---

## Requirements *(mandatory)*

### Functional requirements

- **FR-001** Followers MUST bootstrap from a seed artifact: a 013 snapshot for ETH-family chains; for ETC, the
  artifact OQ-3 selects (checkpoint archive v1 or a 013 snapshot v2 extended to ETC). `archive` MAY start from genesis
  if GT-2 requires it. The seed MUST be verified per 013 §5.4 before following starts.
- **FR-002** Followers MUST follow by the method GT-1 selects per family (apply records through `StateReplay`, or
  re-execute canonical blocks fetched by hash from the record stream), verifying per DC-1 on every block. The apply path
  MUST work on today's storage layout (Hash and Path) and on the 018 unified layout behind the same capability; no
  follower code names a layout. Followers MUST run in a `SyncMode.Follow` (017 FR-007 amendment) in which SNAP and
  execution-driven import are unreachable.
- **FR-003** On any verification failure a follower MUST halt applying, set `ready = false` with a named fault, keep the
  last verified state, and refuse reads at any unverified root (DC-1).
- **FR-004** `replica` MUST serve `eth_getBalance`, `eth_getCode`, `eth_getStorageAt`, `eth_getTransactionCount`,
  `eth_getProof`, `eth_getBlockByNumber/Hash`, `eth_getTransactionReceipt` and `eth_getLogs` (single block) at explicit
  blocks within its window, and return the DC-3 error outside it. Its window `W` is configuration with a documented
  default per chain (024 FR-007 profile).
- **FR-005** `archive` MUST serve the FR-004 methods, `eth_call`, `eth_simulateV1` and the `debug_trace*` methods at
  **any** canonical block, on the storage basis GT-2 selects, using 018 FR-027's bounded executors for the heavy ones.
- **FR-006** Followers MUST apply reorgs by rolling back changesets (DC-4); a `replica` whose window is exceeded MUST
  halt with a "re-seed required" fault.
- **FR-007** Followers MUST report head, finalized head (from 025 watermarks), source lag, window bounds and readiness
  in the 023 FR-004 shape (DC-8).
- **FR-008** Followers MUST apply records strictly in `blockNumber` order per chain, holding out-of-order arrivals in a
  bounded queue and reporting a persistent gap as a fault.
- **FR-009** Follower roles MUST NOT start P2P, discovery, SNAP, the miner or the Engine API server (DC-2; 023 FR-008).
- **FR-010** Every read MUST be block-pinned (DC-6): tags are resolved against the follower's head and the resolved
  block is returned in the response's block fields; `pending` is refused.

### Success criteria

- **SC-001** A replica seeded from a snapshot reaches head within the bound GT-1 sets per chain, and its first served
  read is at a verified root.
- **SC-002** An injected state-diff corruption is detected at the next block; the follower halts within that block and
  serves no read at the bad root.
- **SC-003** `archive` answers FR-005 methods at blocks sampled across the whole chain byte-identical to an
  independently synced reference (Mordor against a core-geth archive; Platåberget or Sepolia against a Geth or Reth
  archive).
- **SC-004** On the 025 FR-009 deep ETC reorg fixture, `archive` ends equal to a clean derivation and `replica` with
  `W` below the depth halts with the named fault, serving nothing across the gap.
- **SC-005** No follower process binds a P2P, discovery or Engine API port (checked in the 023 harness).

---

## Open questions

- **OQ-1: Archive basis.** Retaining every 018 changeset over flat state vs a separate historical index. GT-2 measures;
  this question records the decision. Expected: changesets only for the first release (018 OQ-3 default), since
  historical *indices* are the indexer's job (DC-2 of 023).
- **OQ-2: Replica window default.** Blocks or hours; a value per chain in the ChainSpec profile (024 FR-007).
  Recommended default: 24 h of blocks, which covers every reorg seen on Sepolia and the common case on ETC.
- **OQ-3: ETC seeding.** Spec 013 is ETH-family only; the ETC checkpoint archive v1 is hash-keyed and host-trusted
  (013 §2). Options: extend 013 v2 to ETC (flat state, root-verified; aligns with 018 DC-9 "ETC on the unified
  layout"), or accept v1 for the first release. Recommended: extend 013 v2; it is the format followers verify.
- **OQ-4: Follow method.** Decided by GT-1; the prior is "apply records" (no execution, so a follower is cheap), with
  re-execution kept as the fallback if record application cannot meet the bound. On today's layout the per-block cost
  of apply is the trie update over the changed keys, the same work import does minus execution; GT-1 records it.

---

## Related specs

- **013 State snapshots**: the seed (FR-001); OQ-3 may extend it to ETC.
- **018 Storage engine consolidation**: `StateReplay` capability (amendment request 7), archive mode (request 5),
  close-fence (request 4), multi-process read-only access (request 1), Track R executors (FR-027), the later move of
  followers onto the unified layout (Track F).
- **023 fleet-topology**: roles, health shape, DC-9 subsystem gating, DC-6 block pinning.
- **025 change-record-export**: the stream followed; DC-2 there makes DC-1 here possible.

## Known tasks (seed for `/speckit-tasks`)

- **T-001** GT-1 measurement on Mordor and Platåberget using the 025 file sink; publish the bound.
- **T-002** Follower apply loop: ordered queue (FR-008), forward/backward apply through `StateReplay` on today's
  layout (018 request 7 v1), root and receipt-root verification (DC-1), halt semantics (FR-003); `vault` and
  `forge`/`beacon` review the apply path.
- **T-003** `replica` RPC: window, DC-3 error, block pinning (FR-004, FR-010); `conduit` owns.
- **T-004** GT-2 archive measurement; OQ-1 decision recorded.
- **T-005** `archive` serving (FR-005) on 018 FR-027 executors.
- **T-006** Seeding: 013 import path reused for followers; OQ-3 for ETC; seed verification (FR-001).
- **T-007** SC-001..SC-005 in the 023 harness; the deep-reorg case from 025 FR-009.
