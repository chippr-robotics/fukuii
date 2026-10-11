# Feature Specification: State Capabilities and Execution Witnesses

**Feature Branch**: `017-state-capabilities-witnesses` (stub written on `docs/spec-016-amend-017-stub`)

**Created**: 2026-10-11

**Status**: **Draft (stub).** Approved as a stub by the user on 2026-10-11. **Revised 2026-10-11** after the user's
review of staging 0.9.59: the recorder is one `MptStorage` decorator at `BlockExecution.buildInitialWorld` (DC-3),
the BAL prefetcher stays enabled (DC-2), and DC-6 to DC-10 name the gaps the decorator alone does not cover. To be fleshed out with `/speckit-specify`
and `/speckit-clarify`, then `/speckit-plan` and `/speckit-tasks`. The Design constraints section below is binding
on that later work.

**Depends on**: spec 016's amendment of 2026-10-11, which delivers `StateIngest` (T055) and the env freeze ratchet
(T054). Spec 017 starts after spec 016's coordinator stage, or at least after T055.

**Input**: User decision, 2026-10-11: "Adopt the hybrid sequencing. Land the env freeze now, as a ratchet. Approve
the Spec 016 amendment and the Spec 017 stub, with the exporter durability property and the prefetcher-through-recorder
rule written into 017's design constraints. The other adjustments (StateIngest includes verification reads, SyncMode
lives in SyncController, R4 scoped to Address/TrieKey/Wei, recording at MPT node resolution) are all accepted as
written." The source recommendation split the data plane into two typed capabilities (R1), kept SNAP on the ingest
side (R2), added a `SyncMode` ADT (R3), opaque domain types (R4), execution witnesses (R5) and a backfill mode for
historical witnesses (R6).

## Problem

Fukuii has two kinds of state writer that share one node store, and nothing in the types tells them apart:

- **Ingest**: SNAP downloads and healing (and later snapshot import from spec 013 and era imports) write trie ranges
  and trie nodes in bulk, with no execution.
- **Access**: post-pivot block import (`ledger/BlockExecution`, `BlockPreparator`, through `Blockchain`'s backing MPT
  storage) reads and writes state while executing the EVM.

On Path scheme, SNAP writes `PathNodeStorage` and `PathToHashExporter` publishes into the hash-keyed store that
execution reads. On Hash scheme (ETC/Mordor), SNAP writes the store that execution reads directly. Spec 016's
amendment makes the ingest side a type (`StateIngest`). The execution side has no type yet, so:

- nothing stops SNAP code from reaching execution state, or execution from inheriting an ingest durability setting;
- there is no single seam at which execution reads can be observed, so fukuii cannot produce an **execution witness**
  (the set of trie nodes and code a block's execution touches, in the geth/reth `debug_executionWitness` shape);
- several read layers can answer a state read, and a recorder in the wrong place would miss what they serve: the
  BAL-driven prefetcher (task #79, `bal-prefetch-threads`), the decoded-node cache (`decoded-node-cache-bytes`), the
  code and code-size caches (`code-cache-bytes`), the world-read memo (`world-read-memo-entries`), and the ancestor
  block hashes BLOCKHASH reads.

### The seam already exists (staging 0.9.59)

This is a smaller change than the stub first assumed. All block execution builds its world in **one place**,
`BlockExecution.buildInitialWorld` (`ledger/BlockExecution.scala`), from the parent root over
`SerializingMptStorage.cachedForExecution` (`db/storage/MptStorage.scala`): one storage choice per block and a fresh
world each time. Every trie walk, including the sibling fetches of deletion collapse, goes through
`MptStorage.get(hash)`, and execution reads the hash-keyed store on both schemes. The decorator pattern is already
used three times (`CachingMptStorage`, `PrefetchNodeReader`, `StxLedger.ReplayMptStorage`).

So witness capture is **one `MptStorage` decorator (`RecordingMptStorage`) plus two world-level hooks (code and
block hashes), at a single construction site.** The `StatelessExecutor` reuses the existing world over an in-memory
`MptStorage`. There is no second EVM and no second world implementation.

### Prior art: ExtVM

Mantis's external-VM adapter is gone and is not restored. `VmSetup` accepts only `vm.mode = internal`, and
`src/main/protobuf/extvm/` keeps only a README and LICENSE. ExtVM was a per-transaction callback protocol: the VM
asked the node for each account, slot and code blob as it ran. A prover or stateless verifier needs the opposite: the
whole block's pre-state up front, plus the full block transition. Reviving it would add a protocol without giving the
witness. The dead `vm.external.*` config that `VmConfig` still parses is removed by T-000 (below).

## Strategic motivation

- **Independent-client verification**: re-execute history from witnesses and compare post-state roots with another
  client, block by block.
- **A verifying-executor mode**: execute a block from its witness alone, without the full state.
- **An analysis data plane**: per-block read/write sets for tooling and research.
- **Prover input**: witnesses are the input format zk provers and stateless clients consume.

## Goals

- G-1 A `StateAccess` capability for EVM-execution reads and writes: a single writer, concurrent readers via
  snapshots, always fully durable.
- G-2 Execution witnesses: `RecordingMptStorage` (the `MptStorage` decorator under `StateAccess`'s execution world)
  plus the code and block-hash hooks, a content-addressed witness node store on the slow tier, a
  `StatelessExecutor`, and a differential test against geth.
- G-3 A backfill mode that produces historical witnesses after SNAP reaches head.
- G-4 A `SyncMode` ADT (`Snap` / `Full` / `Backfill`) in `SyncController` that decides when witness capture is
  possible.
- G-5 Opaque `Address`, `TrieKey` (hashed trie key) and `Wei` in the new signatures.

## Non-goals

- **NG-1 Within-block parallel execution (Block-STM).** Out of scope. Revisit after witnesses and BAL-driven prefetch
  have settled.
- **NG-2 Delivering `StateIngest`.** Spec 016's amendment (T055) does that; 017 depends on it.
- **NG-3 An opaque-type retrofit.** `Address`, `TrieKey` and `Wei` are used in the new signatures only. No existing
  signature is retyped, and no retyping rides along with a code move.
- **NG-4 Other opaque types.** The ones already on staging (`BlockNumber`, `ChainId`, `CodeHash`, `StorageKey`,
  `TrieRoot`, `TxHash`, `BlockHash`, `GasAmount`, `GasPrice`, `Timestamp`, `BlobVersionedHash`, `BloomFilter`,
  `Difficulty`, `TotalDifficulty`) are reused, not reworked.
- **NG-5 Consensus changes.** Witness capture observes execution; it never changes a state root, gas figure or hash.
- **NG-6 Witness capture during SNAP.** See DC-5.

## Scope

1. **`StateAccess`** (G-1). EVM-execution reads and writes, used by block import. One writer; concurrent readers
   through snapshots. Always fully durable: no relaxed durability policy may be set on it.
2. **Execution witnesses** (G-2, recommendation R5):
   - **`RecordingMptStorage`**, an `MptStorage` decorator installed at `buildInitialWorld` (DC-3, DC-10), plus the
     world-level code hook (DC-6) and the ancestor-hash hook (DC-7);
   - a **content-addressed node store** for witnesses on the slow storage tier (#1483, tiered storage);
   - a **`StatelessExecutor`**: `(block, witness, chainSpec) => Either[Error, postRoot]`. It is the existing
     `InMemoryWorldStateProxy` over a `WitnessMptStorage` (an in-memory hash → node map), with witness-backed code and
     a witness-backed ancestor-hash function. `MptStorage.get` already throws `MissingRootNodeException` for an absent
     node; that is the "witness incomplete" error;
   - a **differential test against geth's `debug_executionWitness`** on Sepolia blocks: same block, byte-comparable
     witness content, same post-state root.
3. **Backfill mode** (G-3, R6): produces witnesses for historical blocks. It is scheduled only after SNAP reaches
   head and never runs concurrently with state sync.
4. **`SyncMode` ADT** (G-4, R3): `Snap` / `Full` / `Backfill`. It **lives in `SyncController`**, not in the SNAP
   controller. Witness capture is constructible only in `Full` or `Backfill`, and a `Reverted` transition (a reorg
   or a rollback across captured blocks) invalidates the witnesses of the blocks it reverts.
5. **Opaque types** (G-5, R4): `Address`, `TrieKey` and `Wei` only, in the new signatures only.

`StateIngest` itself is out of scope here: spec 016's amendment delivers it (T055), and this spec depends on it.

## Design constraints *(mandatory)*

These are binding on the plan and every task of this spec. A plan that cannot meet one stops and asks the user.

**DC-1 — Exporter durability property.** All `StateIngest` writes are durable before SNAP finalization marks SNAP
complete, on **both** storage schemes. On Path scheme, the `PathToHashExporter` publish is `StateIngest`'s commit to
the access store: it runs under full durability, never under a relaxed-WAL ingest policy, and its output is flushed
and synced before SNAP is marked complete and before execution reads from it, even when the ingest writes ran with
relaxed WAL. On Hash scheme (ETC/Mordor), SNAP writes directly into the store that execution reads and there is no
export step, so an explicit durable flush/sync barrier runs before `completeSnapSync`. A relaxed-WAL policy never
leaks into the execution store on either scheme. Rationale: otherwise a crash during finalization leaves execution
reading a store that was never durably written. (Spec 016 FR-044 and T055 state the same property for the
`StateIngest` side.)

**DC-2 — The prefetcher stays enabled; the recorder sits above every node cache.** *(Revised 2026-10-11; supersedes
the earlier "route the prefetcher through the recorder" rule.)* The BAL-driven prefetcher (#79) stays **enabled** in
capture mode. It warms `DecodedNodeCache`, which sits **below** `cachedForExecution` (`new CachingMptStorage(this,
cache)`). A recorder on the outermost layer (DC-3) therefore sees every `get` the execution walk makes, whether the
cache hits or not, so neither disabling the prefetcher nor a pre-Amsterdam/ETC fallback is needed. The same holds for
any node cache below the recorder. The read layers that sit above `MptStorage` (code and code-size caches, the
ancestor-hash function, the read memos) are covered by DC-6, DC-7 and DC-9. A read that bypasses the recorder is a
silent witness gap and is forbidden. Test requirement (verification): a capture run with every cache warm (prefetcher
on, decoded-node cache, code caches, memos) produces a witness byte-identical to one with every cache cold.

**DC-3 — Recording location: an `MptStorage` decorator on the outermost layer at `buildInitialWorld`.** The recorder
is `RecordingMptStorage`, an `MptStorage` decorator in the pattern of `CachingMptStorage`, `PrefetchNodeReader` and
`StxLedger.ReplayMptStorage`. It is installed in `BlockExecution.buildInitialWorld` on the **outermost** layer: it
wraps the result of `cachedForExecution`, and it is installed **after** the `staged.fold(...)(_.storage)` match, so
the backing-storage and the staged-storage paths are both covered. This is MPT node resolution, where the Path and
Hash schemes converge: execution reads the hash-keyed store on both, and every trie walk, including the sibling
fetches of deletion collapse, goes through `MptStorage.get(hash)`. Recording at the storage-key level is rejected:
Path scheme keys nodes by path, but a witness needs hash-addressed nodes.

**DC-4 — SNAP isolation boundary.** SNAP modules never hold `StateAccess`. A dependency check enforces it, extending
spec 016's env freeze ratchet (T054) and its FR-042 check: no file under `blockchain/sync/snap/` may name
`StateAccess` or reach it transitively through a capability it holds.

**DC-5 — Defaults.** Witness capture defaults to off. No witness is written to the state SSD during SNAP: witnesses
go only to the slow-tier node store, and only in `Full` or `Backfill` mode.

**DC-6 — Code is recorded at the world level.** `EvmCodeStorage` is a concrete class with process-wide static
caches, `shared` (code) and `sizes` (code lengths) (`db/storage/EvmCodeStorage.scala`). EXTCODESIZE can be answered
from the size cache without the bytecode ever being read, yet a stateless verifier needs the code. So code hashes are
recorded at the **world level**, in `getCodeAndHash` and `getCodeSize` of the world, and the bytes are resolved from
`EvmCodeStorage` when the witness is finalized. The storage class is **not** wrapped.

**DC-7 — BLOCKHASH ancestors are recorded.** `AncestorBlockHashes.forBlock` is a plain function passed into the world.
In capture mode it is wrapped to record the ancestor headers it returns, so the witness carries the headers BLOCKHASH
needs. The `StatelessExecutor` gets a witness-backed function of the same shape.

**DC-8 — Capture implies no flat slot storage, by construction.** Block execution does not pass `flatSlotStorage` to
the world today; only SNAP and recovery do. If execution ever used it, SLOADs would skip the trie and the witness
would silently lose storage proofs. "Capture implies no flat storage" is enforced at constructor level: the types make
a capturing world with flat slot storage **unconstructible**, not merely checked at run time.

**DC-9 — Read memos never cross block boundaries.** `TrieReadMemo` is per world and keyed by trie root. Worlds are
built fresh per block, so the first read in each block always walks the trie and is recorded. That assumption is what
keeps the memos safe for capture, so a test asserts that a memo never carries entries from one block's world into the
next.

**DC-10 — Staged storage is covered.** Blocks executed over a `StagedBlockState` (the `staged` argument of
`buildInitialWorld`) are recorded exactly like blocks over the backing storage, because the recorder is installed
after the `staged.fold(...)(_.storage)` match (DC-3). A test captures one block on each path.

## User Scenarios & Testing *(mandatory)*

### User Story 1 — A verifier re-executes a block from its witness (Priority: P1)

An operator enables witness capture on a node at head. For each imported block the node stores a witness, and a
`StatelessExecutor` re-executes the block from the witness alone and arrives at the same post-state root.

**Why this priority**: it is the core property every other use (verification, provers, analysis) relies on.

**Independent Test**: capture witnesses for a range of Sepolia blocks, run the `StatelessExecutor` on each, and
compare the post-state roots with the imported headers.

**Acceptance Scenarios**:

1. **Given** capture mode is on in `Full` mode, **When** a block is imported, **Then** its witness is stored and the
   `StatelessExecutor` returns `Right(postRoot)` equal to the header's state root.
2. **Given** a witness with one node removed, **When** the `StatelessExecutor` runs, **Then** it returns a `Left`
   naming the missing node, never a wrong root.

### User Story 2 — Witnesses match geth's (Priority: P1)

A maintainer runs the differential test against geth's `debug_executionWitness` on the same Sepolia blocks.

**Why this priority**: an independent reference is the only check that the recorder misses nothing.

**Independent Test**: the differential test, on a fixed set of Sepolia blocks that includes deletions, self-destructs
(where the fork allows them) and BAL-carrying Amsterdam blocks.

**Acceptance Scenarios**:

1. **Given** the same block, **When** both clients produce a witness, **Then** fukuii's witness contains every node
   and code blob in geth's, and both execute to the same post-state root.

### User Story 3 — SNAP is unaffected (Priority: P1)

An operator syncs a fresh node with SNAP. Witness capture is unavailable until SNAP reaches head, and no witness data
is written during SNAP.

**Why this priority**: SNAP is the default sync path; spec 016's isolation must not regress.

**Independent Test**: a Mordor and a Platåberget SNAP run with capture configured on; no witness writes until `Full`
mode, and the DC-4 dependency check is green.

**Acceptance Scenarios**:

1. **Given** `SyncMode` is `Snap`, **When** capture is configured on, **Then** no capture wrapper can be built, and the
   node logs that capture starts at `Full`.

### User Story 4 — Historical witnesses by backfill (Priority: P2)

After SNAP reaches head, an operator schedules backfill to produce witnesses for a historical range.

**Why this priority**: useful for verification of history, but it needs US-1 first.

**Independent Test**: backfill a range on Mordor and check every witness with the `StatelessExecutor`.

**Acceptance Scenarios**:

1. **Given** SNAP has reached head, **When** backfill is scheduled, **Then** it runs in `Backfill` mode and never
   concurrently with state sync.
2. **Given** a reorg reverts captured blocks, **When** the `Reverted` transition runs, **Then** their witnesses are
   invalidated.

### Edge Cases

- A cache is warm when capture is switched on (DC-2).
- EXTCODESIZE answered from the code-size cache (DC-6).
- BLOCKHASH of an ancestor (DC-7).
- A block executed over staged storage (DC-10).
- A crash during SNAP finalization, on each scheme (DC-1).
- A reorg across captured blocks (`Reverted`).
- Capture switched on during `Snap` mode (US-3).

## Requirements *(mandatory)*

### Functional Requirements (stub level; refined by /speckit-specify)

- **FR-001** The system MUST provide a `StateAccess` capability for EVM-execution reads and writes, with one writer,
  concurrent readers through snapshots, and full durability always.
- **FR-002** The system MUST provide `RecordingMptStorage`, installed on the outermost `MptStorage` layer at
  `buildInitialWorld`, after the staged-storage match (DC-3, DC-10).
- **FR-003** In capture mode the BAL prefetcher MUST stay enabled, and no read may bypass the recorder: node caches sit
  below it (DC-2), code and code sizes are recorded at the world level (DC-6), BLOCKHASH ancestors are recorded
  (DC-7), and read memos never cross a block (DC-9).
- **FR-003a** A capturing world MUST be unconstructible with flat slot storage (DC-8).
- **FR-004** The system MUST store witnesses in a content-addressed node store on the slow tier (#1483), never on the
  state SSD during SNAP (DC-5).
- **FR-005** The system MUST provide a `StatelessExecutor` `(block, witness, chainSpec) => Either[Error, postRoot]`
  built on the existing `InMemoryWorldStateProxy` over a `WitnessMptStorage`, with witness-backed code and ancestor
  hashes. An incomplete witness surfaces as `MissingRootNodeException`, mapped to the error side.
- **FR-006** A differential test MUST compare fukuii's witnesses with geth's `debug_executionWitness` on Sepolia
  blocks.
- **FR-007** `SyncController` MUST hold a `SyncMode` ADT (`Snap` / `Full` / `Backfill`). Witness capture MUST be
  constructible only in `Full` or `Backfill`, and a `Reverted` transition MUST invalidate the affected witnesses.
- **FR-008** Backfill MUST run only after SNAP reaches head and never concurrently with state sync.
- **FR-009** New signatures MUST use opaque `Address`, `TrieKey` and `Wei`; existing signatures MUST NOT be retyped
  by this spec.
- **FR-010** SNAP code MUST NOT hold `StateAccess`, enforced by a dependency check (DC-4).
- **FR-011** All `StateIngest` writes MUST be durable before SNAP is marked complete, on both schemes (DC-1).
- **FR-012** Witness capture MUST default to off (DC-5).
- **FR-013** Witness capture MUST NOT change any state root, gas figure, receipt or hash (constitution I). ETC/Mordor
  execution MUST be byte-for-byte unchanged with capture off. [NEEDS CLARIFICATION: whether ETC needs witness capture
  at all in the first release, or only ETH/Sepolia.]

### Key Entities

- **`StateIngest`** (spec 016, T055): the SNAP data-plane capability. Bulk writes and verification reads, no
  execution, per-capability durability policy.
- **`StateAccess`**: the execution capability. Single writer, snapshot readers, fully durable; the only path that can
  be recorded.
- **`RecordingMptStorage`**: the `MptStorage` decorator that records every node `get` during execution.
- **`WitnessMptStorage`**: an in-memory hash → node `MptStorage` the `StatelessExecutor` runs over.
- **Execution witness**: the hash-addressed trie nodes, code and headers a block's execution needs, in the
  `debug_executionWitness` shape.
- **Witness node store**: content-addressed, slow tier.
- **`SyncMode`**: `Snap` / `Full` / `Backfill`, held by `SyncController`.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001** For every block in the differential test set, fukuii's witness re-executes to the header's state root,
  and contains every node and code blob in geth's witness for that block.
- **SC-002** A warm-cache and a cold-cache capture run of the same blocks produce byte-identical witnesses (DC-2).
- **SC-003** With capture off, block import throughput and every consensus output are unchanged against the
  `[IMPORT-TIMING]` baseline (within the constitution's ~10% budget for throughput, exact for consensus outputs).
- **SC-004** The DC-4 dependency check is green, and a SNAP run with capture configured on writes no witness data.
- **SC-005** A crash injected during SNAP finalization, on each scheme, resumes without execution ever reading
  undurable state (DC-1).

## Assumptions

- Spec 016's T055 (`StateIngest`) has merged before any 017 implementation task starts.
- BALs (EIP-7928) are implemented for Amsterdam on staging, and the BAL prefetcher (#79) exists and warms
  `DecodedNodeCache` below `cachedForExecution`.
- `buildInitialWorld` stays the single construction site of execution worlds. A second site would need its own
  recorder installation and is a plan-time stop.
- The slow-tier storage of #1483 exists or lands first; if not, the witness node store's placement is re-planned.
- `[STORAGE-PERF]` and `[IMPORT-TIMING]` are the existing instrumentation and are reused, not replaced.
- `beacon` owns the ETH execution side, `forge` signs off that ETC is unchanged, and `vault` reviews the stores and
  durability.

## Open Questions

- **OQ-1 Per-capability timing.** How to measure `StateIngest` and `StateAccess` cost separately. The proposal is to
  reuse `[STORAGE-PERF]` (ingest) and `[IMPORT-TIMING]` (execution) rather than add a new timer family.
- **OQ-2 Witness storage sizing.** Bytes per block for Sepolia and ETC mainnet, the retention policy, and the slow-tier
  capacity it needs.
- **OQ-3** Whether ETC needs witness capture in the first release (FR-013).

## Known tasks (seed for /speckit-tasks)

- **T-000 (chore, prerequisite or standalone).** Remove the dead external-VM config: `VmConfig` still parses
  `vm.external.*` (`VmMode.External`, `VmConfig.ExternalConfig`, `parseExternalConfig` in `utils/Config.scala`), while
  `VmSetup` accepts only `internal`. Small, separate PR; not part of this docs PR. Keep `vm.mode = internal` parsing,
  and fail loudly on `external`, as `VmSetup` does today.
