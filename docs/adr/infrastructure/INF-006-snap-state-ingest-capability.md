# ADR-INF-006: SNAP State-Ingest Capability and the Exporter Durability Boundary

**Status**: Proposed (spec 016 T055; becomes Accepted when the T055 PR merges with forge and vault sign-off)

**Date**: 2026-10-11

**Context**: Spec 016 amendment 2026-10-11 (FR-040..045, SC-008..010), task T055. Spec 017 (`StateAccess`, execution
witnesses) depends on it.

## Background

fukuii's data plane has two kinds of state writer, and today they share the same storage handles:

- **Ingest** writes trie ranges and trie nodes in bulk, with no execution. Today that is the SNAP download and healing;
  later it includes snapshot import (spec 013) and era imports. It cannot be recorded as an execution witness, and it
  may one day run with relaxed durability (T056).
- **Access** is EVM execution (`ledger/BlockExecution`, `BlockPreparator`, through `Blockchain`'s backing MPT store).
  It is always fully durable, and it is the only path that can be wrapped to record an execution witness (spec 017).

Both share the node store. On the Path scheme (ETH/Sepolia), SNAP writes `PathNodeStorage`, and `PathToHashExporter`
publishes the finished trie into the hash-keyed store that execution reads. On the Hash scheme (ETC/Mordor), SNAP
writes the store that execution reads directly. Until now the boundary between the two was a convention. Before T055,
`SnapControllerEnv` (the controller modules' environment) carried 8 raw storage handles among its 18 members.

## Decision

### 1. `StateIngest` is SNAP's storage capability

`snap/controller/StateIngest.scala` defines a capability trait. It has one member for each storage handle the env
held, plus a durability policy:

| Member | Was (env) | Used for |
|---|---|---|
| `appState: AppStateStorage` | `appStateStorage` | progress, resume checkpoints, completion markers, pivot persistence |
| `stateTries: StateStorage` | `stateStorage` | the writable per-pivot trie store (`getBackingStorage`) |
| `flatSlots: FlatSlotStorage` | `flatSlotStorage` | flat slots; its data source backs the derived SNAP stores |
| `pathNodes: Option[PathNodeStorage]` | `pathNodeStorageOpt` | the Path node store (None on Hash/ETC); the store of the batched storage-trie write path |
| `bytecode: EvmCodeStorage` | `evmCodeStorage` | bytecode writes, healed-code presence checks |
| `chainReader: BlockchainReader` | `blockchainReader` | headers, genesis, chain weights |
| `chainWriter: BlockchainWriter` | `blockchainWriter` | pivot header and block, chain weight |
| `validatorFactory: MptStorage => StateValidator` | `validatorFactory` | state validation |
| `durability: StateIngest.Durability` | (new) | the per-capability durability policy |

- The env drops the 8 handles and gains `stateIngest`, going from 18 members to 11 (FR-041, SC-008). The env-freeze
  ratchet (`scripts/snap-split/storage-baseline.json`) records 11 and an empty storage-member list.
- The controller modules reach storage only through `stateIngest`. These are the resume planner, finalization
  (including the Path-to-hash publish), pivot selection, the pivot refresher, healing, validation, the download
  supervisor, the lifecycle and the peer pool. The change at each call site is a substitution, for example
  `appStateStorage.x` becomes `stateIngest.appState.x`.
- Verification reads are ingest, not access. SNAP reads what it needs to decide what to write: proof checks, healing
  presence checks, state validation, and the publish verification. These go through `StateIngest`.
- `StateIngest` exposes no EVM execution read or write. SNAP code never holds `StateAccess` (FR-042, spec 017 DC-4).
- This is the first shape of the capability, so the members stay at the handle level. A member gets narrower when a
  later slice moves a caller that only needs part of a store (the coordinator narrowings, C-*). Each member added or
  changed needs a justification line against the cap in the routing doc (`.claude/agent-protocols/snap-sync.md`).

**Note: derived-store members (spec 016 T060d, D-T060-1, user decision 2026-10-12).** The coordinators take stores that
the core derives from the handles. A capability-mode extension adds three of them as members, which takes the cap from 9
to 12 (`justifications["capabilities.StateIngest.members"]`):

| Member | Hands out | Built |
|---|---|---|
| `trieStore(pivot: BigInt): MptStorage` | `stateTries.getBackingStorage(pivot)` (a pure forwarder) | per call; on the Hash scheme (ETC) each call is a new `MptStorage` |
| `snapProgress: SnapSyncProgressStorage` | the core's `snapProgressStorage` (namespace `p`) | by the core, before the capability (a `val` parameter) |
| `storageDone: SnapStorageDoneStorage` | the core's `storageDoneStorage` | by the core, after the capability (a by-name parameter, read on each call) |

- **Timing decision.** Every member hands out the instance the core already builds, at the point where the core
  builds it. Nothing is built earlier. `storageDone` is by-name, not strict, because a strict argument would capture
  the core's field before it is initialized (`null`).
- **Caching decision.** `trieStore` does not cache. The core's per-pivot cache (`getOrCreateMptStorage`, reset on
  dormancy and restart) is its only caller, so one `MptStorage` per pivot is still shared by every coordinator. That
  keeps one reference-count bookkeeping per pivot on ETC.
- **Recovery path.** `SyncController`'s mirrored capability (D-T060-2) derives the two wrappers as the core does. Each
  recovery start builds them; both are stateless (a data-source reference and a namespace constant) and do no I/O.
- **Deferred to C-H1.** `HealingFrontierStorage` and `BfsQueueStorage` are not members yet. They are
  `HealingOrchestrator`'s `private lazy val`s, built on first use. The gate's mirror rule cannot carry a lazy handle:
  it would build a `RocksDbBfsQueueStorage`, which holds a write counter, on every recovery start. Naming them from
  the core also needs a visibility change, which capability mode rejects. A small verify change at C-H1 admits
  by-name/lazy members (#1584).

### 2. Chain-data handles stay on `StateIngest` (no header/chain sub-interface now)

`chainReader` and `chainWriter` are chain-data handles (headers, pivot, best block), not trie state. We considered
splitting them into a separate header/chain capability and decided not to, for now:

- The modules use them in the same sequences as `appState`. The pivot anchor at finalization stores the block, then the
  chain weight, then the best-block info, then the completion flags. Pivot selection stores the header together with
  the pivot and root markers. One capability covers one sequence.
- A split would add an env member (12) and a second capability, with no consumer that needs chain data but not ingest.
  The ChainDownloader, the only other user, takes the reader, the writer and app state together through
  `ChildFactories`.
- The spec 017 boundary is execution versus ingest. The chain-data handles are neither EVM reads nor trie writes, so
  keeping them here does not blur that boundary.

We will revisit this when one of these happens: a consumer needs chain data without ingest (backfill/ChainDownloader
narrowing, spec 013 import, spec 017 `SyncMode`); the `StateIngest` cap comes under pressure; or `StateAccess` needs
the same headers. The two members are adjacent and named for chain data, so extracting a `ChainData` trait that
`StateIngest` extends is mechanical.

### 3. Durability policy: per capability, default is today's behaviour

`StateIngest.Durability` has one value, `Default`. That is today's behaviour: the WAL is on for every write, ingest
writes are plain commits, and the completion flags are written in one synced commit. Nothing reads the policy yet. A
relaxed policy (a lighter WAL) is T056's, may be set on `StateIngest` only, and never reaches `StateAccess` or the
execution store on either scheme (FR-045).

### 4. The exporter is the durability boundary (FR-044): the property, and what the code does today

**The property.** `PathToHashExporter`'s publish is `StateIngest`'s commit to the access store. Its output must be
durable (flushed and synced) before SNAP finalization marks SNAP complete (`snapSyncDone`), and before execution reads
it, whatever policy the ingest writes used. On the Hash scheme there is no export step, so the property needs a
durable flush/sync barrier before `completeSnapSync`. The scaladoc of `StateIngest` states it.

The property is about **execution** reads. SNAP itself reads the hash-keyed store before the publish and before the
flags, read-only, to decide what to do: the #1555 pivot-root guard (`FinalizationPolicy.pathPivotRootAbsent`, from
`SnapFinalization.finalizeSnapSync` and `HealingOrchestrator`), the state-validation walks (`StateValidationModule`),
the healing coordinator's walks, and the post-publish root check in `onPathPublishDone`. These are ingest verification
reads (decision 1), not execution reads, and the property does not cover them (beacon review note N1).

**What the code does today (T055 finding, on `staging@2be66fe3c`):**

- The node runs one RocksDB instance (`RocksDbDataSource`) with all column families in it. The WAL is on for every
  write: nothing sets `disableWAL`, and there is no manual WAL flush.
- Under archive and basic pruning (basic is the default in `conf/base/pruning.conf`; hive runs archive), ingest
  writes and the publish's writes (`MptStorage.storeRawNodes`, then `persist()`) reach RocksDB as plain `commit()`s:
  `WriteOptions.setSync(false)`. The WAL record is appended but not fsynced. What `persist()` does depends on the
  pruning mode, so it is not a general no-op.
- Under `pruning.mode = "inmemory"` (`CachedReferenceCountedStateStorage`, `StateStorage.scala` ~314), the trie store
  (`getBackingStorage`, a `CachedReferenceCountedStorage`) writes only to its LRU cache and change log, and its
  `persist()` is `()`. Those nodes are **not in the WAL** when the flags sync, on either scheme: the Path publish's
  target is `stateTries.getBackingStorage` too. The codebase already says so (`SNAPSyncController`'s
  `recordStorageDone` comment, ~243-250; `AccountRangeCoordinator` ~701, where correctness rests on the healing walk
  every resume forces). Shipped configs (basic, archive) are not affected.
- On both schemes, `SnapFinalization.finalizeSnapSync` writes the pivot block, its chain weight and the best-block
  info with plain commits. It then writes the three completion flags (`snapSyncDone`, `storageRecoveryDone`, and
  `bytecodeRecoveryDone` when bytecode is complete) in **one batch with `commitSync()`**, which uses
  `WriteOptions.setSync(true)`. That is the only synced write on the finalization path.
- So there is **no separate flush/sync barrier before `completeSnapSync`**, on either scheme. The publish output is
  not synced when the publish ends. The memtables are not flushed. The only flush in the codebase,
  `RocksDbDataSource.flushAll`, is `private[dataSource]` test support.
- What does hold, **under archive and basic pruning**: a synced write syncs the WAL up to and including itself. A
  crash therefore leaves a prefix of the writes, and if `snapSyncDone` survived, every earlier ingest and publish write
  survived too. That holds while every ingest write goes into the WAL. It does **not** hold under `inmemory` pruning
  (above), and it would not hold under a relaxed policy that skipped the WAL.

**Rolled WAL files (vault's answer).** A `sync = true` write fsyncs every live WAL not yet synced (those still in
`logs_`), not only the current one. A memtable flush syncs the closed logs before it writes the SST. All column
families share one WAL. With point-in-time recovery's prefix rule, that gives "recovered flag ⇒ every earlier write
recovered", including writes in WAL files rolled before the flags. **Caveat:** this comes from RocksDB's documented
semantics, not from a read of the rocksdbjni 10.10.1.1 source. T056 confirms it, against the pinned source or with a
kill -9 test across a forced WAL roll.

**Decision (user, 2026-10-11): document now, add the barrier in T056.** T055 stays behaviour-neutral. It states the
property on the trait and pins today's implicit guarantee with a new Tier 1 test, `StateIngestDurabilitySpec`. On both
schemes, the test checks three things: the completion flags are the only synced write; they come after every ingest or
publish write; and every replayed prefix that holds `snapSyncDone` also holds the pivot root node. T055 does **not**
add an explicit barrier, because that changes the ETC path (an extra WAL sync, or a memtable flush, before completion).

The explicit durable barrier before `completeSnapSync` is added by **T056** and is a **hard precondition** of it: no
relaxed WAL policy may be enabled, or even selectable, without the barrier, because the flags' WAL sync no longer
covers writes that skip the WAL. T056 needs forge and vault sign-off. vault's requirements for the barrier:

- **What:** a memtable flush (`FlushOptions` with wait) across the node, path-node, flat-slot and code namespaces, plus
  a WAL sync. Not a synced empty batch.
- **Inmemory pruning:** flush the node cache to RocksDB first, so the barrier also closes the `inmemory` gap.
- **Where:** on the Path scheme, after the publish and before the flags; on the Hash scheme, after the last ingest
  write and before the flags.
- **Owner:** the barrier lives on `StateIngest`.
- **Tests:** extend `StateIngestDurabilitySpec` to expect the barrier before the flags on both schemes, and add a
  real-RocksDB kill -9 recovery test (which also settles the rolled-WAL caveat).

### 5. The batched storage-trie node-write path (SC-010)

The hottest SNAP write is the Path-scheme storage-trie node write (#1548). `StorageRangeCoordinator` builds each
update with `PathNodeStorage.storageNodeUpsert` / `storageNodeRemoval`, buffers it in `trieNodeWrites`, and commits one
batch per applied response in `flushTrieNodeWrites`. The capability's member for this path is `pathNodes`, the store
itself. The coordinator reads it once per batch, and the per-node calls stay direct calls on `PathNodeStorage`, so
there is no wrapper call and no allocation per node. The production implementation is a `final class` of plain
`val`s, built once, so every call through the trait has a single receiver class. T055 moves no coordinator call site;
the coordinator's narrowing (C-S2, T067) does. **User decision 2026-10-11:** the SC-010 same-host `[STORAGE-PERF]`
parity gate (quiescent soak host, the Sepolia Path-scheme storage phase against ~1.7M entries/h, plus a same-host
pre-wrap vs post-wrap A/B) moves from T055 to T067, where the per-node write path actually moves.

### 6. `ChildFactories` keeps its parameter lists in T055 (user decision 2026-10-11: deferred to the C-* slices)

T055 was to make the `ChildFactories` spawn arguments take a `StateIngest`. `ChildFactoriesSpec` compares each
factory method's default arguments with the child `apply`'s, position by position (`<method>$default$<n>`), and the
recording factories in the module stubs override every method with its exact parameter list. Adding or replacing
parameters therefore means changing assertions in an existing spec, which a behaviour-neutral PR may not do. The
spawn sites pass `stateIngest`'s members as the existing arguments (`flatSlotStorage = stateIngest.flatSlots`). Each
factory method changes together with its child's `apply` in that coordinator's narrowing (C-*), which keeps the
default-equality spec true by construction. The user confirmed this deferral on 2026-10-11; it is a done criterion of
every C-* slice (tasks.md, "Coordinators").

## Consequences

- `SnapControllerEnv` has 11 members and no storage handle. The storage-reference total over the measured set falls
  from **692 to 678** under the T054b capability-mode counting. That counting is deliberate: a call such as
  `stateIngest.appState.putSnapSyncPivotBlock(...)` reaches a raw store through a parameterless storage-typed member,
  so `stateIngest.<handle>` still counts once, as `appStateStorage` did. The module rows therefore stay close to their
  old values (`StateValidationModule` rises 3 → 5, because `stateIngest.validatorFactory` is storage-typed; the gate is
  the total). The drop of 14 is the env's 23 handle references gone, offset by the core's 7 new ones (building
  `stateIngest`) and `StateValidationModule`'s 2: 692 − 23 + 7 + 2 = 678. The capability file itself is not a `storage_refs` row; it has its own member-cap entry (9)
  under `capabilities`. An earlier measurement of 556 used the pre-T054b counter, which did not count
  `stateIngest.<handle>`. That figure understated the remaining coupling. The real reduction comes in the C-* slices,
  as each coordinator narrows to calls on the capability (user decision 2026-10-11: handle-level members, as in
  decision 1).
- The coordinators still take raw stores until their C-* narrowings. Each narrowing takes `StateIngest`, never
  `StateAccess`, and lowers the total.
- The module stubs in the controller test suites gain a `stateIngest` that forwards, by name, to their existing stores
  (`ForwardingStateIngest`). No assertion changes.
- No behaviour change on either scheme: every call reaches the same store object as before.

## Alternatives considered

- **A method-level facade** (one `StateIngest` method per `AppStateStorage` method the modules call, about 45). This
  was rejected for T055. It hides nothing that substitution does not already hide, inflates the cap, and makes every
  call site change more than its handle. Narrower members come with the callers that need them.
- **Splitting chain data into its own capability now.** See decision 2.
- **Adding the Hash-scheme barrier in T055.** See decision 4. It is a behaviour change on the ETC path; the user
  placed it in T056 as a hard precondition of any relaxed WAL policy.

## References

- `specs/016-snap-controller-split/spec.md` and `plan.md`, "Amendment 2026-10-11"; `tasks.md` T054, T054b, T055, T056
- `specs/017-state-capabilities-witnesses/spec.md` (DC-1, DC-4)
- `.claude/agent-protocols/snap-sync.md` (capability caps), `.claude/agent-protocols/storage-rocksdb.md`
- `src/main/scala/com/chipprbots/ethereum/blockchain/sync/snap/controller/StateIngest.scala`
- `src/test/scala/com/chipprbots/ethereum/blockchain/sync/snap/controller/StateIngestDurabilitySpec.scala`
