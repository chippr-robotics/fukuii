# Feature Specification: State Capabilities and Execution Witnesses

**Feature Branch**: `017-state-capabilities-witnesses` (stub written on `docs/spec-016-amend-017-stub`)

**Created**: 2026-10-11

**Status**: **Draft (stub).** Approved as a stub by the user on 2026-10-11. **Revised 2026-10-11** after the user's
review of staging 0.9.59: the recorder is one `MptStorage` decorator at `BlockExecution.buildInitialWorld` (DC-3),
the BAL prefetcher stays enabled (DC-2), and DC-6 to DC-10 name the gaps the decorator alone does not cover.
**Revised again 2026-10-11 (Ethereum roadmap alignment)**: ETH mainnet is the design driver. The witness is now
specified as the execution-specs `ExecutionWitness` served over `debug_executionWitness` (DC-11, DC-16), the model is
free of MPT-specific types ahead of EIP-7864 (DC-12), every validity-relevant read is recorded (DC-13), BALs give a
self-check (DC-14), and the stateless core keeps a dependency boundary that spec 018 (zkVM guest) builds on (DC-15).
To be fleshed out with `/speckit-specify`
and `/speckit-clarify`, then `/speckit-plan` and `/speckit-tasks`. The Design constraints section below is binding
on that later work.

**Depends on**: spec 016's amendment of 2026-10-11, which delivers `StateIngest` (T055) and the env freeze ratchet
(T054). **Spec 017 starts after spec 016 completes** (user decision: 017 comes after 016).

**Input**: User decision, 2026-10-11: "Adopt the hybrid sequencing. Land the env freeze now, as a ratchet. Approve the
Spec 016 amendment and the Spec 017 stub, with the exporter durability property and the prefetcher-through-recorder rule
[superseded by DC-2] written into 017's design constraints. The other adjustments (StateIngest includes verification
reads, SyncMode lives in SyncController, R4 scoped to Address/TrieKey/Wei, recording at MPT node resolution) are all
accepted as written." The source recommendation split the data plane into two typed capabilities (R1), kept SNAP on the
ingest side (R2), added a `SyncMode` ADT (R3), opaque domain types (R4), execution witnesses (R5) and a backfill mode
for historical witnesses (R6).

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
`MptStorage.get(hash)`, and execution reads the hash-keyed store on both schemes. Two other kinds of site build
worlds and are not covered by that seam: `BlockPreparator` builds a separate world for block preparation (the
proposer / Engine API path) over `getReadOnlyMptStorage()`, and whether capture covers it is OQ-4; the RPC-only sites
(`StxLedger`, GraphQL, `EthUserService`, `EthSimulateService`) are out of scope. The decorator pattern is already
used three times (`CachingMptStorage`, `PrefetchNodeReader`, `StxLedger.ReplayMptStorage`).

So witness capture is **one `MptStorage` decorator (`RecordingMptStorage`) plus two world-level hooks (code and block
hashes), at a single construction site for block import (`BlockExecution.buildInitialWorld`).** The `StatelessExecutor`
reuses the existing world over an in-memory `MptStorage`. There is no second EVM and no second world implementation.

### Prior art: ExtVM

Mantis's external-VM adapter is gone and is not restored. `VmSetup` accepts only `vm.mode = internal`, and
`src/main/protobuf/extvm/` keeps only a README and LICENSE. ExtVM was a per-transaction callback protocol: the VM
asked the node for each account, slot and code blob as it ran. A prover or stateless verifier needs the opposite: the
whole block's pre-state up front, plus the full block transition. Reviving it would add a protocol without giving the
witness. The dead `vm.external.*` config that `VmConfig` still parses is removed by T-000 (below).

## Strategic motivation

**ETH mainnet is the design driver.** *(Every roadmap statement in this section, in DC-11 to DC-16 and in spec 018 is
unverified as of this stub and is to be re-verified at `/speckit-plan` time: the EF L1-zkEVM roadmap's asks, FOCIL
(EIP-7805) and frame transactions (EIP-8141) as Hegota must-ships, Ere, and the phase numbering. Treat them as the
working hypothesis, not as settled.)* The decisive input is the Ethereum Foundation's L1-zkEVM roadmap (2026): L1 is
moving toward accepting zkEVM proofs as an alternative to re-execution for attesters, with mandatory execution proofs
on the longer-range fork plan. The pipeline is: stateful EL → `ExecutionWitness` → stateless guest program → zkVM →
prover → CL verifier. The roadmap assigns EL clients two projects:

1. **Witness production.** Generate execution witnesses for the last N blocks (N set by the client's reorg
   tolerance) and serve them over `debug_executionWitness`. The witness format is specified in execution-specs, with
   tests, and the RPC in execution-apis. Reth is named as the reference implementation, and Kona already consumes the
   endpoint in production.
2. **A guest program.** A stateless validation program built reproducibly for a standardized bare-metal RISC-V target.
   That is spec 018, which builds on this spec's `StatelessExecutor`.

This spec is fukuii's delivery of project 1. It also positions fukuii for the other roadmap items that change the
witness: block-level access lists (EIP-7928, shipped in Amsterdam), FOCIL (EIP-7805) and frame
transactions (EIP-8141), reported as Hegota must-ships (re-verify at planning), and the binary state tree (EIP-7864).

The original motivations still hold:

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
- G-6 `debug_executionWitness` served for the last N blocks, in the execution-apis shape, conformant with the
  execution-specs `ExecutionWitness` format (DC-11, DC-16).
- G-7 A witness model and recorder that are not tied to the hexary MPT, so EIP-7864 changes a backend, not the
  design (DC-12).
- G-8 A `StatelessExecutor` with a dependency boundary narrow enough for spec 018 to compile it for a zkVM target
  (DC-15).

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
- **NG-7 The zkVM guest program.** Spec 018 owns it. This spec only keeps the stateless core buildable for it
  (DC-15).
- **NG-8 A proof-verifying validation mode.** Accepting a zk proof in place of re-execution is the roadmap's end
  state, but it is out of scope here. `StateAccess` and `SyncMode` must not preclude it: a later spec adds block
  validation as re-execute *or* verify-proof.
- **NG-9 The binary state tree itself.** EIP-7864 is not implemented here. DC-12 only keeps the witness model from
  hard-coding the MPT.

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
   - a **differential test against reth's and geth's `debug_executionWitness`** on Sepolia blocks: same block,
     byte-comparable witness content, same post-state root. Reth is the roadmap's reference implementation; geth is
     the second independent check. Once execution-specs ships witness fixtures, they replace hand-picked blocks as the
     primary oracle (DC-11);
   - the **`debug_executionWitness` RPC** for the last N blocks (DC-16).
3. **Backfill mode** (G-3, R6): produces witnesses for historical blocks. It is scheduled only after SNAP reaches
   head and never runs concurrently with state sync.
4. **`SyncMode` ADT** (G-4, R3): `Snap` / `Full` / `Backfill`. It **lives in `SyncController`**, not in the SNAP
   controller. Witness capture is constructible only in `Full` or `Backfill`. A reorg or rollback across captured
   blocks invalidates the affected witnesses and emits revert events.
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

**DC-4 — SNAP isolation boundary.** SNAP modules never hold `StateAccess`. The "no `StateAccess` under `snap/`"
dependency check is delivered by this spec. It extends spec 016's T054 ratchet tooling (`scripts/snap-split/verify.py`),
and it is vacuous until `StateAccess` exists (spec 016 FR-042): no file under `blockchain/sync/snap/` may name
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

**DC-11 — The witness format is execution-specs', not ours.** The serialized witness follows the `ExecutionWitness`
defined in execution-specs, and the RPC follows execution-apis. Until those are final, fukuii matches reth's
`debug_executionWitness` output, and the plan tracks the upstream issues the EF milestone pages cite: execution-specs
#1590 (the stateless validation API) and #1865 (state-access tracking for earlier forks). Verify both before planning.
Fukuii never ships a private witness format. Internal storage may differ (DC-12, content-addressed nodes), but the
format on the wire is the upstream one, and the serializer is tested against upstream fixtures as soon as they exist.
Divergences found against reth or geth are filed upstream, not papered over.

**DC-12 — No MPT-specific types in the witness model or wire format.** EIP-7864 replaces the hexary Keccak MPT with
a binary tree and a different hash, which changes what a witness contains (stems and binary proofs instead of hexary
nodes). So the witness model and `StatelessExecutor`'s input do not bake in MPT-specific types, and the wire format is
the upstream `ExecutionWitness` (DC-11), which is itself commitment-neutral. That is the whole requirement. This
spec does **not** introduce an abstract recorder or commitment interface with a single implementation (YAGNI):
`RecordingMptStorage` stays a concrete `MptStorage` decorator (DC-3). The commitment abstraction is extracted when
EIP-7864 code exists and there are two implementations to abstract over. Where the keys are already distinct, the
MPT key space (`TrieKey`) stays an opaque type (G-5), so stem/suffix keys can be added later without mixing them. No
binary-tree code lands here (NG-9).

**DC-13 — Every validity-relevant read is recorded, not just EVM reads.** A stateless verifier must be able to
reproduce *every* check that decides a block's validity, so every state read that check makes goes through the
recorded world: system calls (beacon root, history storage), withdrawals, EIP-7685 requests, and **post-execution
checks**. The known forward case is FOCIL (EIP-7805, reported as a Hegota must-ship; re-verify at planning). As currently specified, the EL checks after
execution that each inclusion-list transaction missing from the block could not have been appended, which reads
sender nonce and balance from the post-state. When FOCIL lands, those reads must be recorded too. Re-verify against
the EIP text at planning time. Frame transactions (EIP-8141, reported as a Hegota must-ship; re-verify at planning) execute through the same world and
need no special handling, but a frame-transaction block must be in the differential test set once Hegota devnets
exist.

**DC-14 — BALs give a free self-check.** On Amsterdam-and-later ETH blocks, the block-level access list (EIP-7928)
is consensus-validated and names every account and slot the block touches. In capture mode, every BAL account and
slot must have a resolvable proof path in the witness; a missing one is a recorder bug and fails loudly. This catches
silent witness gaps on mainnet blocks without a reference client. ETC and pre-Amsterdam blocks have no BAL and rely
on DC-2's warm-equals-cold test and the differential test.

**DC-15 — The stateless core has a narrow dependency boundary.** `StatelessExecutor` and its transitive closure
(the EVM in `vm/`, `mpt/`, `rlp`, the chain spec, the witness model) must not gain any new dependency on RocksDB,
Pekko actors or streams, Cats Effect, Monix, or other JVM-runtime-only libraries. Its public API is pure: block bytes,
witness bytes and chain spec in, `Either[Error, postRoot]` out, with no I/O and no threads. Existing JVM-only
dependencies in that closure (Pekko `ByteString` across `vm`, `mpt`, `domain`, `rlp`, `bytes` and `crypto`;
BouncyCastle hex and crypto) are **inventoried, not removed**, as input to spec 018. A dependency check in the
style of DC-4 enforces "no new ones". Rationale: spec 018 compiles this closure for a bare-metal RISC-V zkVM target.
Keeping it clean costs nothing now and is expensive to recover later.

**DC-16 — RPC retention and serving.** `debug_executionWitness` is bounded by what can be produced, and that depends
on whether witnesses are stored.
- **Without stored witnesses (the default, per DC-5).** A witness is regenerated on demand by re-executing the block,
  which needs the block's **parent state**. With basic pruning fukuii keeps only about 64 blocks of state history, so
  N is bounded by that state-history window. Past it the RPC returns an explicit "not available" error, never a
  partial witness.
- **With stored witnesses.** Serving a larger N requires capture on **plus** the slow-tier witness store (DC-5's
  `Full` or `Backfill` mode). That is an explicit operator opt-in. It squares with DC-5's default-off: the default
  node serves only the state-history window, and a node that wants the roadmap's N (at least the maximum reorg depth
  on that chain) enables capture. The default N on ETH mainnet is OQ-8.
- **Never blocks import, by mechanism.** On-demand regeneration runs on a **bounded, dedicated executor** (fixed
  thread count, bounded queue, per-request timeout) that is separate from block import's execution context and from
  the RPC dispatcher. A full queue rejects the request with an explicit error. Regeneration reads state read-only and
  takes no lock that import takes (FR-020 and the test in SC-009).

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

### User Story 2 — Witnesses match the reference clients' (Priority: P1)

A maintainer runs the differential test against reth's (the roadmap reference) and geth's `debug_executionWitness`
on the same Sepolia blocks.

**Why this priority**: an independent reference is the only check that the recorder misses nothing.

**Independent Test**: the differential test, on a fixed set of Sepolia blocks that includes deletions, self-destructs
(where the fork allows them) and BAL-carrying Amsterdam blocks.

**Acceptance Scenarios**:

1. **Given** the same block, **When** fukuii and a reference client produce a witness, **Then** fukuii's witness
   contains every node and code blob in the reference's, and both execute to the same post-state root.
2. **Given** execution-specs witness fixtures exist, **When** the fixture suite runs, **Then** fukuii's serialized
   witness matches the fixture (DC-11).

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
2. **Given** captured blocks, **When** a reorg or rollback crosses them, **Then** the affected witnesses are
   invalidated and revert events are emitted.

### Edge Cases

- A cache is warm when capture is switched on (DC-2).
- EXTCODESIZE answered from the code-size cache (DC-6).
- BLOCKHASH of an ancestor (DC-7).
- A block executed over staged storage (DC-10).
- A crash during SNAP finalization, on each scheme (DC-1).
- A reorg or rollback across captured blocks.
- Capture switched on during `Snap` mode (US-3).
- An Amsterdam block whose BAL names an account the witness lacks (DC-14).
- A block whose validity depends on a post-execution check, such as FOCIL inclusion-list satisfaction once Hegota is
  specified (DC-13).
- A frame-transaction block (EIP-8141), once Hegota devnets exist (DC-13).
- A `debug_executionWitness` request for a block older than the retention window (DC-16).

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
- **FR-006** A differential test MUST compare fukuii's witnesses with reth's and geth's `debug_executionWitness` on
  Sepolia blocks, and MUST adopt execution-specs witness fixtures as the primary oracle once they exist.
- **FR-007** `SyncController` MUST hold a `SyncMode` ADT (`Snap` / `Full` / `Backfill`). Witness capture MUST be
  constructible only in `Full` or `Backfill`. A reorg or rollback across captured blocks MUST invalidate the affected
  witnesses and emit revert events.
- **FR-008** Backfill MUST run only after SNAP reaches head and never concurrently with state sync.
- **FR-009** New signatures MUST use opaque `Address`, `TrieKey` and `Wei`; existing signatures MUST NOT be retyped
  by this spec.
- **FR-010** SNAP code MUST NOT hold `StateAccess`, enforced by a dependency check (DC-4).
- **FR-011** All `StateIngest` writes MUST be durable before SNAP is marked complete, on both schemes (DC-1).
- **FR-012** Witness capture MUST default to off (DC-5).
- **FR-013** Witness capture MUST NOT change any state root, gas figure, receipt or hash (constitution I). ETC/Mordor
  execution MUST be byte-for-byte unchanged with capture off. [NEEDS CLARIFICATION: whether ETC needs witness capture
  at all in the first release. Recommended default after the roadmap review: ETH/Sepolia first, because ETH is the
  design driver and the L1-zkEVM effort is ETH-only. ETC follows, since the same code serves both chains and ETC
  witnesses feed proof-backed sync and bridge work later.]
- **FR-014** The system MUST serve `debug_executionWitness` for the window DC-16 defines (the state-history window
  by default; at least N with capture and the slow-tier store opted in), in the execution-apis shape, returning an
  explicit error (never a partial witness) outside it.
- **FR-015** The serialized witness MUST follow the execution-specs `ExecutionWitness` format (DC-11). No
  fukuii-private wire format.
- **FR-016** The witness model and wire format MUST NOT contain MPT-specific types (DC-12). The recorder stays the
  concrete `RecordingMptStorage` (DC-3); no abstract recorder or commitment interface is introduced until EIP-7864
  code exists.
- **FR-017** Every state read that a block-validity check depends on, including system calls, withdrawals, requests
  and post-execution checks, MUST go through the recorded world (DC-13).
- **FR-018** In capture mode on BAL-carrying blocks, every BAL account and slot MUST have a resolvable proof path in
  the witness; otherwise capture fails loudly (DC-14).
- **FR-019** `StatelessExecutor`'s transitive closure MUST NOT gain new JVM-runtime-only dependencies, enforced by a
  dependency check; its public API MUST be pure (DC-15).
- **FR-020** On-demand witness regeneration MUST run on a bounded, dedicated executor separate from block import and
  the RPC dispatcher, with a bounded queue and a per-request timeout (DC-16).

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
- **SC-006** `debug_executionWitness` responses for the differential test set are accepted by a reth-compatible
  consumer (stateless re-execution of the response reaches the header's state root), and a hive test covers the
  endpoint.
- **SC-007** Over a Sepolia capture run of at least 10,000 Amsterdam blocks, the BAL self-check (DC-14) reports zero
  gaps.
- **SC-008** The DC-15 dependency check is green, and the inventory of existing JVM-only dependencies in the stateless
  closure is published as an input to spec 018.
- **SC-009** Under a saturating load of `debug_executionWitness` regeneration requests, block import time
  (`[IMPORT-TIMING]`) does not regress beyond the agreed tolerance, excess requests are rejected with the explicit
  error, and the import path is shown to share no executor or lock with regeneration.

## Assumptions

- Spec 016 has completed (including T055, `StateIngest`) before any 017 implementation task starts.
- BALs (EIP-7928) are implemented for Amsterdam on staging, and the BAL prefetcher (#79) exists and warms
  `DecodedNodeCache` below `cachedForExecution`.
- `buildInitialWorld` stays the single construction site of block-import worlds. A second block-import site would
  need its own recorder installation and is a plan-time stop. `BlockPreparator`'s world is OQ-4; the RPC-only sites
  are out of scope.
- The slow-tier storage of #1483 exists or lands first; if not, the witness node store's placement is re-planned.
- `[STORAGE-PERF]` and `[IMPORT-TIMING]` are the existing instrumentation and are reused, not replaced.
- `beacon` owns the ETH execution side, `forge` signs off that ETC is unchanged, `vault` reviews the stores and
  durability, and `conduit` owns the `debug_executionWitness` endpoint.
- Roadmap facts were read on 2026-10-11 from the EF L1-zkEVM roadmap (Ethereum Magicians), the EF EL milestone pages
  (HackMD) and the Protocol Cluster's Hegota priorities (FOCIL and EIP-8141 must-ship, reported 2026-09-08). They move
  quickly, so re-verify at `/speckit-plan` time.

## Open Questions

- **OQ-1 Per-capability timing.** How to measure `StateIngest` and `StateAccess` cost separately. The proposal is to
  reuse `[STORAGE-PERF]` (ingest) and `[IMPORT-TIMING]` (execution) rather than add a new timer family.
- **OQ-2 Witness storage sizing.** Bytes per block for Sepolia and ETC mainnet, the retention policy, and the slow-tier
  capacity it needs.
- **OQ-3** Whether ETC needs witness capture in the first release (FR-013). Recommended: ETH first.
- **OQ-4** Whether witness capture covers block preparation: `BlockPreparator` (around line 978) builds its own world
  over `getReadOnlyMptStorage()` for the proposer / Engine API path, outside `buildInitialWorld`.
- **OQ-5** The current state of the execution-specs `ExecutionWitness` definition and fixtures, and whether the
  roadmap's proposed zk-optimized successor endpoint has replaced `debug_executionWitness`. If it has, FR-014 targets
  the successor and keeps `debug_executionWitness` for compatibility.
- **OQ-6** The value of N (DC-16) per chain, and whether witnesses older than the state-history window are retained
  on the slow tier (opt-in) or simply unavailable. This ties into OQ-2.
- **OQ-7** Whether fukuii joins the EL zkEVM working group now (recommended), so witness-format decisions are made
  with it, not discovered after.
- **OQ-8** The default N on ETH mainnet: the state-history window only (about 64 blocks under basic pruning), or a
  larger default that would require capture and the slow-tier store on by default and so reopen DC-5.

## Related specs

- **Spec 016** (SNAP controller split and its 2026-10-11 amendment): delivers `StateIngest`; prerequisite.
- **Spec 018** (zkVM guest program): compiles this spec's `StatelessExecutor` for a bare-metal RISC-V zkVM target.
  Depends on DC-15 and on G-2's executor existing and passing the differential test on the JVM first.

## Known tasks (seed for /speckit-tasks)

- **T-001 (017 planning task, DC-13 bypass audit).** Confirm that every system call (EIP-4788 beacon root, EIP-2935
  history storage, EIP-7002, EIP-7251, EIP-8282), every withdrawal and every request computation runs through the world
  built in `BlockExecution.buildInitialWorld`, and not through a direct `blockchainReader` or state-root read, which
  would bypass the recorder. Method: trace each call site from `BlockExecution`, and grep the block-execution closure
  for `blockchainReader` / `getStateRoot`-style reads outside the world. Any bypass found is a **must-fix before witness
  capture ships**, and is listed in the plan with a test that captures a block exercising it (SC-004's differential
  test must include one such block per system call).

- **T-000 (chore, prerequisite or standalone).** Remove the dead external-VM config: `VmConfig` still parses
  `vm.external.*` (`VmMode.External`, `VmConfig.ExternalConfig`, `parseExternalConfig` in `utils/Config.scala`), while
  `VmSetup` accepts only `internal`. Small, separate PR; not part of this docs PR. Keep `vm.mode = internal` parsing,
  and fail loudly on `external`, as `VmSetup` does today.
