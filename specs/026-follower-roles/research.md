# Research: Follower Roles — `replica` and `archive`

**Spec**: [spec.md](spec.md) (Clarified 2026-10-31) · **Plan**: [plan.md](plan.md) · **Date**: 2026-10-31

Every decision below was checked against the tree on branch `ccr-6ee15a46-q037bc-3` (0.10.x line, spec 022 slices 1–3
merged). File:line citations are to that tree. Decisions marked **binding** were taken before this plan (spec
Clarifications, alignment review §2–§4, 018 pending rows) and are recorded, not re-opened.

---

## R-1 `StateReplay` v1 on today's layout: what is written where

**Decision.** `StateReplay` v1 writes the **hash-keyed node store (CF `n`)** through `StateStorage` on *both* schemes,
updates the flat stores (`FlatAccountStorage`, `FlatSlotStorage`) and `EvmCodeStorage` in the same batch, and never
touches the Path column families (`t`, `u`). It verifies the root on a **staged** trie before anything reaches disk.

**Rationale.**
- After sync, execution and the RPC read path read the hash-keyed store on both schemes: `BlockchainReader.getAccount`
  builds `MerklePatriciaTrie[Address, Account]` over `stateStorage.getBackingStorage(blockNumber)`
  (`domain/BlockchainReader.scala:190-197, 312-319`); `Blockchain.getAccountStorageAt` and `getStorageProofAt` do the
  same with `getBackingStorage(0)` (`domain/Blockchain.scala:87-118`). `StateStorage(pruningMode, nodeStorage, …)`
  (`db/storage/StateStorage.scala:331-340`) is always over `NodeStorage` = CF `n`. The Path CFs are SNAP's working set,
  frozen at the pivot and copied into `n` by `PathToHashExporter` at handoff (`StateIngest.scala` doc, "Exporter
  durability property"; alignment review §3/013 correction). A follower that wrote Path CFs would write a store nothing
  reads.
- Flat stores exist on every datadir (`db/components/Storages.scala:51-53` constructs both unconditionally) but are
  populated only by SNAP (`FlatSlotStorage`) and never by execution (`FlatAccountStorage` has no writer; alignment
  review §3). A follower seeded from a 013 v2 snapshot *does* have populated flat stores (the v2 importer writes
  `accounts`/`storage` sections, 013 §5.1/§5.5), so keeping them current from records is cheap (one put per changed key
  in the same batch) and is what later lets a follower export by scan (alignment review §3, 013 row 6). "Update flat
  stores where they exist" therefore means: always write them; they are authoritative for nothing on v1 (DC-7 is
  unaffected, the root still comes from the trie).
- Staging: `StateStorage.stageBlock(bn)` returns a `StagedBlockState` whose trie writes go to a `BufferedNodeStorage`
  and are committed with `commitWith(bn, hash, extra)` in **one** `DataSourceBatchUpdate` together with the block's
  other writes (`StateStorage.scala:28-85`, `ReferenceCountedStateStorage.stageBlock` at `:230-275`). This is the
  import invariant "a block's state exists iff the block does", and it gives the follower verification-before-commit for
  free: compute the root on `staged.storage`, compare, then `commitWith` or `discard()`. Under `ArchivePruning`
  staging is write-through (`pending = None`, `StateStorage.scala:97-112`); a mismatch leaves unreferenced
  content-addressed nodes but no header, mapping or head pointer (acceptable; see R-7).

**What one `applyForward(record)` writes (one atomic batch).**

| Store | Key → value | Source in the record | Note |
|---|---|---|---|
| CF `n` via `stageBlock(bn).storage` | node hash → RLP (ref-counted under basic pruning) | derived: trie put/remove over changed accounts and slots | the only consensus-relevant write; `mpt/` untouched |
| `FlatAccountStorage` (`a`) | `keccak(address)` → slim RLP account | account diff `new` (`Delete` on removal) | `FlatAccountStorage.putAccountsBatch` / `update(toRemove, …)` |
| `FlatSlotStorage` (`d`) | `keccak(address) ++ keccak(slot)` → RLP(value) | slot diff `new` (`Delete` when zero) | `FlatSlotStorage.putSlotsBatch` |
| `EvmCodeStorage` (`c`) | codeHash → bytes | code diff | content-addressed; never deleted |
| `BlockHeadersStorage` (`h`) + `BlockNumberMappingStorage` (`i`) | hash → header; number → hash | record header | `BlockchainWriter.storeBlockHeader` (`domain/BlockchainWriter.scala:96-98`) |
| `ReceiptStorage` (`r`) | block hash → receipts | record receipts | `BlockchainWriter.storeReceipts` |
| `BlockBodiesStorage` (`b`) + `TransactionMappingStorage` (`l`) | hash → body; txHash → location | **not in the 025 record today** | see Cross-spec note 1 in plan.md |
| new CF `FollowRecordNamespace` | `blockNumber(8 BE) ++ blockHash` → record bytes | the record as received | kept for the window (R-4) |
| `AppStateStorage` (`s`) | `follow.*` keys (data-model.md) | derived | head, verified root, source position |

**Alternatives rejected.**
- *Write the Path CFs on the Path scheme to stay "native".* Nothing reads them after handoff; it would double the write
  volume and bind the follower to a layout (FR-002 forbids naming one).
- *Re-execute blocks (GT-1 option (a)).* Kept as the GT-1 fallback; it needs bodies, the EVM and `StateAccess`, i.e.
  `ingest`'s whole execution stack in every follower, which is what the role exists to avoid.
- *Write through `StateIngest`.* It is `private[sync]`, scoped to SNAP, and its durability policy is SNAP's
  (`StateIngest.scala`
  header). 018 row 7 says a third capability; see R-2.

---

## R-2 The diff carries hashed keys; the trie is updated by hashed key

**Decision.** `StateReplay` builds the account trie as `MerklePatriciaTrie[ByteString, Account]` with an **identity**
key encoder over the 32-byte `keccak(address)`, and each storage trie as `MerklePatriciaTrie[ByteString, BigInt]` with
an identity encoder over the 32-byte `keccak(pad32(slot))` and `EthereumUInt256Mpt.rlpBigIntSerializer` as the value
serializer. No change to `mpt/`.

**Confirmed on the tree.** `MerklePatriciaTrie.put` derives the path as
`HexPrefix.bytesToNibbles(kSerializer.toBytes(key))`
(`mpt/MerklePatriciaTrie.scala:270-271`) and `remove` likewise (`:293-296`); the trie structure depends only on the
encoder's output bytes. Import's encoders are exactly "hash the key": `Address.hashedAddressEncoder = kec256(addr)`
(`domain/Address.scala:16-17`) and `HashByteArraySerializable(byteArrayBigIntSerializer)` = `kec256(pad32(slot))`
(`domain/package.scala:19-31`, `mpt/HashByteArraySerializable.scala`). Feeding the already-hashed 32 bytes through an
identity `ByteArrayEncoder[ByteString]` yields byte-identical nibble paths and therefore byte-identical nodes and
roots. SNAP already relies on this: `SnapHashTrie.update(key: Array[Byte], …)` and `StackTrie` take hashed keys
(`blockchain/sync/snap/SnapHashTrie.scala:62`, `mpt/StackTrie.scala:35`). Value encodings are unchanged:
`Account.accountSerializer` (`domain/Account.scala:56`) and `rlpBigIntSerializer`. Every supported chain sets
`eth-compatible-storage = true` (`conf/base/chains/*.conf`), so the slot key is always `keccak(pad32(slot))`;
`StateReplay` refuses to start on a chain with it false (the 025 key would not match `ArbitraryIntegerMpt`).

**Consequence for DC-7.** The follower performs the same `put`/`remove` operations import performs after execution
(`InMemoryWorldStateProxy.persistState`, `ledger/InMemoryWorldStateProxy.scala:84-113`: code → storage tries → account
trie), minus execution, with a different key *encoder instance* and the same bytes. `forge` and `beacon` review the
encoder equivalence and the per-block write order before the apply path lands (plan.md "Specialist routing").

**Alternatives rejected.** Adding a `putHashed` to `MerklePatriciaTrie` (touches `mpt/`, forbidden by DC-7 for no
gain); carrying raw addresses in the record (025 FR-008 fixed hashed keys so the 018 layout applies them directly).

---

## R-3 Root verification cost per block (GT-1 plan)

**Decision.** GT-1 measures, on Mordor and Platåberget via the 025 file sink: (b) seed + apply records, (a) seed +
re-execute, (c) records from genesis (Mordor only). The follower's per-block cost under (b) is `persistState` minus
execution: for every changed storage trie, `put`/`remove` over its changed slots and `getRootHash`; then `put`/`remove`
over changed accounts and `getRootHash`; one batch commit. The prior is (b), and SC-001's bound is whatever (b) measures
plus the lag bound.

**Rationale.** Nothing in the apply path is new work; it is the trie-update half of import. Node reads during `put`
come from CF `n` (block cache, `DataSource.getOptimized`) and, under basic pruning, through `DecodedNodeCache`
(`StateStorage.scala:134-137`). What GT-1 must quantify is (i) the ratio of apply time to import time at the same block
range (expected well under 1), (ii) whether the follower keeps up with the chain's block time on the reference host
with headroom for the lag bound, and (iii) disk growth per hour on a `replica` with `W` = 24 h (pruning death rows at
`bn − history`, `StateStorage.scala:150-167`). The one figure in hand is a *walk*, not an apply (Mordor, 4,780,490 nodes
in 36.6 s, 022 session); it bounds seed-export time, not follow time.

**Instrument.** `fukuii follow-bench --source file:<dir> --seed <snapshot> --from N --to M` (new CLI in
`blockchain/follow/FollowBenchCli.scala`, beside `blockchain/checkpoint/CheckpointCli.scala`) prints per-block apply
time (p50/p99), nodes written, batch bytes, root-check time, and total wall time; the same binary with `--reexecute`
runs option (a) through the existing `BlockExecution` path for the comparison. Output goes into `plan.md` "GT-1
result" when it exists.

**Alternatives rejected.** Measuring on the 023 harness only (the harness is phase C and needs Kafka; the file sink is
025 T-003 and exists first); measuring on Sepolia (needs the fresh datadir from 022's gate; Platåberget is the
ETH-family chain the alignment review names for phase C).

---

## R-4 Rollback by backward replay, not an undo log

**Decision** (binding, DC-4). A retraction is applied by `applyBackward(record)` over the retracted block's own record:
every account, slot and code entry is set to its `previous` value (`put` of the previous value, `remove` where previous
is absent), the flat stores likewise, and the computed root must equal the **parent header's** `stateRoot`, which the
follower holds (it applied the parent). Retractions are processed newest-first as 025 FR-007 emits them. The follower
keeps each block's record bytes for its window in a new CF (R-1 table) so the record is at hand; `archive` keeps all.

**On the Hash scheme with basic pruning.** Forward and backward writes both go through
`stateStorage.getBackingStorage(bn)` = `SerializingMptStorage(ReferenceCountNodeStorage(nodeStorage, bn))`
(`StateStorage.scala:169-170`), tagged with the *retracted* block's number for the backward pass. Content-addressed
nodes that still exist (the parent state's nodes were replaced, not deleted; they sit on block `bn`'s death row with
count 0 until `prune(bn)` runs at `bn − history`) regain a reference when re-inserted, and the nodes the forward pass
created lose theirs. The follower never calls `onBlockRollback(bn)` (`ReferenceCountNodeStorage.rollbackReporting`,
`:213-250`) nor `onBlocksAbandoned` for retracted blocks: both are node-level undo mechanisms and using either beside
record replay double-undoes. The gate for this design is a property test over `EphemDataSource`: for a generated block
diff, `applyForward` then `applyBackward` leaves the node store **byte-identical** (keys, values, reference counts via
`RefCountInspector.refCounts`, `src/test/scala/.../db/storage/RefCountInspector.scala:9`) to the pre-state, in the style
of `ReferenceCountRollbackSpec`. `vault` reviews; if the count bookkeeping cannot be made identical, the fallback is to
run the backward pass under a `ReferenceCountNodeStorage` tagged `bn` and then call `pruneAbandonRecords`-equivalent
cleanup, decided in the S1 PR, not here.

**A `replica` whose window is shorter than the reorg depth** (DC-4): the retraction names a block below `head − W`, so
its record is gone (pruned with its death row) and the state below it may be pruned. The follower halts with fault
`ReseedRequired` before touching anything (R-7). `archive` keeps every record and never prunes, so it always rolls back.

**Alternatives rejected.**
- *Reuse `ReferenceCountNodeStorage`'s snapshots (`onBlockRollback`) as the undo.* Hash-scheme-only; the 018 unified
  layout has no such snapshots; DC-4 forbids a second undo log in the follower; and `rollbackReporting` is documented as
  "NOT a general undo … only correct for the tip" (`ReferenceCountNodeStorage.scala:226-235`), which a records-first
  design must not depend on.
- *Keep a separate per-block undo log of flat keys.* The record already carries previous values (025 DC-2); a second
  copy is 025 DC-9's "second schema is a defect".

---

## R-5 The ordered queue and gap detection

**Decision.** A pure, bounded `OrderedRecordQueue` (no actor, no clock inside) between the `RecordSource` and the
applier. Invariants:
- `next` is the record with `blockNumber == applied + 1` and `parentHash == appliedHash`; anything else waits.
- A record with `blockNumber ≤ applied` and the same `blockHash` as the applied one at that height is a **duplicate** →
  dropped, counted (025 DC-3).
- A record with `blockNumber ≤ applied` and a *different* hash, with no retraction for the applied one seen → fault
  `UnexpectedFork` (025 DC-4: a reorg is explicit; a silent replacement is a producer defect).
- A retraction record is applied immediately if it names `applied`; a retraction for a lower block is queued until the
  blocks above it are retracted (newest-first, 025 FR-007); a retraction naming a block below `head − W` on a `replica`
  → fault `ReseedRequired`; on an ETH-family `archive` naming a block at or below the finality watermark → fault
  `FinalizedRetraction` (spec edge case; 025 FR-007 defence in depth).
- Finality records update `follow.finalized` and never block the queue.
- Bounds: `fukuii.follow.queue-bound` records (default 1024) and `queue-bytes` (default 256 MiB); at the bound the
  source is **not** read further (backpressure onto Kafka/file, never a drop). A gap (`next` absent) that persists
  longer
  than `fukuii.follow.gap-timeout` (default 5 min) → fault `PersistentGap`, reported through health; the queue keeps
  waiting (the exporter MUST NOT skip, 025 DC-3; a late record clears the fault).
- The driver is an fs2 `Stream[IO, Record]` from the `RecordSource`, through the queue (`evalScan`), into the applier;
  `flow` reviews the stage for silent drops and the backpressure path (`.claude/agents/flow.md`). Tests use a manual
  `TestControl` clock for the gap timeout, no `Thread.sleep`.

**Alternatives rejected.** A Pekko Typed actor mailbox as the queue (ordering and bounds are then implicit in mailbox
configuration; the rules above want a unit-testable pure structure); dropping out-of-order records and relying on Kafka
re-delivery (the single partition is already ordered, FR-003 of 025; any out-of-order arrival is a fault, not noise).

---

## R-6 Block pinning in the RPC layer

**Decision.** Followers get a role-aware block resolver, `PinnedBlockResolver`, used in place of `ResolveBlock`'s
`resolveBlock` (`jsonrpc/ResolveBlock.scala:27-56`) and `EthProofService`'s private copy
(`EthProofService.scala:212-237`):
- `latest` → the follower's applied head (`AppState follow.appliedHead`; equal to the best-block pointer the follower
  writes, so `blockchainReader.getBestBlock` stays correct); `safe`/`finalized` → the 025 finality watermark
  (`follow.finalized`), on ETC genesis only (025 DC-5); `earliest` → block 0 only if inside the window;
  `pending` → the 023 DC-9 role error; numbers and hashes → looked up, then window-checked.
- The resolution happens **once per request**; the resolved `(number, hash)` is attached to the response. For results
  that carry block fields (`eth_getBlockBy*`, `eth_getTransactionReceipt`, `eth_getLogs`) the fields already say it;
  for scalar results (`eth_getBalance`, `eth_getCode`, `eth_getStorageAt`, `eth_getTransactionCount`, `eth_getProof`)
  the follower adds the JSON-RPC response extension member `"pinnedBlock": {"number": "0x…", "hash": "0x…"}` to the
  response object and, on the HTTP transport, the header `Fukuii-Pinned-Block: <number>:<hash>` so a router can read it
  without parsing the body. `JsonRpcResponse` (`jsonrpc/JsonRpcResponse.scala:8`) gains an optional field, encoded only
  on follower roles; `ingest` responses are byte-identical to today.
- `eth_getLogs`: both range edges are resolved and window-checked (FR-004); `EthFilterService.getLogs` today clamps
  `toBlock` to the best block and resolves tags implicitly (`EthFilterService.scala:112-114`); on a follower it goes
  through the resolver, and `filterManager` is asked with explicit numbers only.

**Rationale.** 023 DC-6 wants a router to pin a session to a height and detect drift from the response alone; the
extension member is the one place Orbita reads, and the header is cheap. Strict JSON-RPC 2.0 clients that reject
unknown members are a known risk, logged in plan.md; followers are behind Orbita's gateway (023), not public.

**Alternatives rejected.** Changing result shapes (breaks every client); echoing only in a header (WS and IPC have
none);
a separate `fukuii_pin` method (a second round trip defeats the pin).

---

## R-7 Window enforcement, halt semantics, and what "keep last verified state" means on disk

**Window.** `W` is a block count. The ChainSpec per-role profile declares `replica.retention-window-blocks` (024
FR-007):
Mordor and ETC mainnet ≈ 6,650 (13 s blocks), Sepolia and Platåberget 7,200 (12 s slots); `fukuii.follow.window`
overrides it. The window is `[appliedHead − W, appliedHead]`; `follow.windowLow` is recomputed and written in every
block's batch. Basic pruning's `history` is set to `W + margin` (margin = `fukuii.follow.lag-bound`, so a block that
is still inside the served window is never on a pruned death row); an `archive` runs `pruning.mode = "archive"`
(`ArchiveStateStorage`, `StateStorage.scala:95`) until GT-2 moves it to 018's changesets-only basis. At start, a
`replica` checks `W × measured bytes/block` (from the seed manifest or a default per chain) against free space and
fails with a sizing error (spec edge case).

**Halt.** On a root or receipts-root mismatch, a `ReseedRequired`, `UnexpectedFork`, `FinalizedRetraction` or a
`StateReplay` exception: `staged.discard()`; nothing of the bad block is committed (R-1); `follow.halt` is written with
`updateSync` (`{fault, blockNumber, blockHash, expectedRoot, computedRoot, at}`); the queue stops consuming; health
reports `ready = false`, `fault = <name>`; every chain-RPC read returns the `follower halted` error (contracts/
follower-rpc.md). The process stays up and keeps answering the admin surface; it never re-seeds, restarts or exits
(FR-003). `drain`/`stop` from Orbita (023 FR-005) still work. On the next start with `follow.halt` present, the follower
starts **halted** (the fault is durable) until Orbita replaces or re-seeds the datadir; there is no in-process clear.

**"Keep last verified state"** means: every committed batch is a verified block, so the datadir is exactly the state at
`follow.appliedHead` with `follow.lastVerifiedRoot == header(appliedHead).stateRoot`; the offending record is kept in
the record CF under a `follow.halt.record` key for diagnosis (never applied). `debug_*` and `eth_*` reads at verified
blocks are refused while halted (FR-003 "refuse reads at any unverified root" plus DC-1 "stops serving"); a diagnosis
is done offline on a copy of the datadir or through the admin surface's `GET /follow/halt`.

**Alternatives rejected.** Serving verified blocks while halted (the spec says halt serving; a half-ready follower is
what a router mis-pins to); auto-clearing the fault on restart (a restart loop hides a corrupt stream).

---

## R-8 Seed import: reuse from 013 and the checkpoint importer

**Decision** (binding, FR-001). The seed is a 013 v2 snapshot for both families. 026 contributes nothing to the format;
it (a) depends on 013 §11 rows 2 (ETC coverage), 6 (walk-based export) and 8 (022 markers) being accepted and 013
Phases 1–2 landing, and (b) wraps the 013 importer in `SeedImporter` (`blockchain/follow/SeedImporter.scala`) that, in
the importer's final atomic batch, also writes `follow.role`, `follow.seedBlock`, `follow.appliedHead = N`,
`follow.lastVerifiedRoot = header(N).stateRoot`, `follow.windowLow`, and clears `follow.halt`.

**Pattern reused.** `CheckpointImporter.streamEntries` (`blockchain/checkpoint/CheckpointImporter.scala:84-191`) is the
template: a synced "import in progress" marker before the first write (`appStateStorage.beginCheckpointImport`,
`AppStateStorage.scala:163-167`), bulk writes in batches of 10k nodes / 1k codes through `mpt.storeRawNodes` and
`evmCodeStorage.put`, `forcePersist(SnapSyncFinalization)` for the inmemory-pruning gap, then **one** `commitSync` with
header, chain weight, best-block pointer, done flags and `endCheckpointImport` (`:169-186`). 013 §5.4's root check
happens before that final batch; a failing check wipes the written namespaces and refuses to start following (spec
edge case). The 013 importer rebuilds the trie with `StackTrie` from flat data in hash order (013 §5.1, §5.5) and
writes the flat stores as it goes; that is what makes R-1's flat-store maintenance meaningful.

**ETC anchor.** The fleet's own `ingest` head (Clarifications): `SeedImporter` checks the manifest's `blockHash`
against `--trusted-block-hash` or against the first record the `RecordSource` yields whose `parentHash` chain reaches it
(the stream starts at the export position, 025 OQ-5; a seed at or below that position is required). Until 013 lands the
ETC side, the follower MVP on Mordor is exercised with a **devnet seed = genesis** plus records from block 1 (GT-1
option (c)); this is explicitly the MVP path in tasks.md and is not a production seed.

**Alternatives rejected.** Checkpoint archive v1 as a seed (hash-keyed, host-trusted, retired with the Hash scheme, 018
row 9; FR-001 forbids it); a follower SNAP-syncing once (DC-5).

---

## R-9 Archive basis options for GT-2

**Decision** (binding default, OQ-1): 018 changesets-only (FR-010 archive mode, 018 row 5), measured by GT-2 on Mordor
against the alternatives before FR-005 lands (phase D).

**Options GT-2 measures.**

| Basis | Available when | Disk | Historical read | Note |
|---|---|---|---|---|
| (A) 018 changesets retained for every block over flat state | 018 Track F (phase D) | one diff per block (small) | reconstruct by walking changesets back from head: O(blocks back) unless indexed | the spec's expected answer; needs 018 OQ-3/OQ-5 to keep the mode |
| (B) Hash-scheme `ArchivePruning` (every node kept, content-addressed) | **today** (`ArchiveStateStorage`) | full archive growth (every node version) | O(1): open the trie at the block's root | works on today's layout; the only basis an `archive` could ship on before 018 Track F; dies with the Hash scheme (018 DC-9) |
| (C) history indices (per-key version lists, Erigon-style) | 018 Track H or later | between A and B | O(log versions) | the indexer's job on the fleet (023 DC-2); not fukuii's unless GT-2 shows A cannot serve `eth_call` |

**What GT-2 reports.** Disk per 1M blocks, `eth_getBalance` and `eth_call` p50/p99 at blocks 1, 10k, 100k and 1M behind
head, for A and B on Mordor; C is costed on paper from the A/B numbers. The decision is recorded in spec OQ-1 and
plan.md "GT-2 result". Note for the user: if phase D slips, (B) gives a Mordor archive on today's layout at the price
of disk; it is listed so the trade-off is explicit, not as a recommendation.

---

## R-10 `SyncMode.Follow` and what a follower process starts

**Decision.** `SyncController.SyncMode` (`blockchain/sync/SyncController.scala:2577-2585`, today `Snap | Regular`,
`private[sync]`) gains `Follow` per 017 FR-007's pending row 3, and `selectSyncMode` takes the 023 role: `Follow` for
`replica` and `archive`. In `Follow`, `SyncController.start()` is **not** spawned at all: `StdNode.start()`
(`nodebuilder/StdNode.scala:55-111`) skips `startServer`, `startDiscoveryManager`, `startPeerManager`,
`startSyncController`, `startMining` and `startEngineApiServer` for follower roles (023 FR-008 / T-004 owns the
gating; 026 adds `startFollowController` in their place). The checkpoint gate in `SyncController.start()`
(`:1479-1536`) is therefore unreachable, as DC-5 requires, and the `do-snap-sync` match (`:1550-1600`) never runs.

**Blocked on.** 017's amendment row 3 being accepted (the enum lives in `SyncController` by 017 FR-007; until 017 is
planned, 026 adds the case with a `// 017 FR-007 amendment` marker and 017's plan adopts it) and 023 T-003/T-004
(`--role`, `InstanceConfig.role`, gating). The follower's own tests do not need either: `FollowController` is
constructed directly in tests.

**Alternatives rejected.** Running the follower *inside* `SyncController` as a fourth behavior (drags the SNAP split's
storage baseline and `private[sync]` capabilities into the follower; the snap-split verifier counts storage references
in `snap/controller`); deriving the mode from config only (the role is the source of truth, 023 FR-001).

---

## R-11 Readiness and health fields

**Decision.** Readiness (DC-8) = `!halted && sourceLag ≤ fukuii.follow.lag-bound && lastVerifiedRoot ==
header(appliedHead).stateRoot`,
where `sourceLag` = the highest `blockNumber` seen from the source (Kafka end offset's record, or the newest record
observed) minus `appliedHead`. The 023 FR-004 shape gains, for followers, `window: {low, high}`, `fault`, and
`seedBlock`; metrics twins `fukuii_follow_applied_head`, `fukuii_follow_source_lag`, `fukuii_follow_window_low`,
`fukuii_follow_apply_seconds` (histogram), `fukuii_follow_queue_depth`, `fukuii_follow_halted` (0/1). The admin
surface is 023 FR-009's; 026 only contributes the fields. Today's `NodeJsonRpcHealthChecker` requires peers
(`jsonrpc/NodeJsonRpcHealthChecker.scala:41-42, 92-109`) and is replaced per role by 023 T-005.

---

## R-12 Durability and the restart path

**Decision.** Each block is one `DataSourceBatchUpdate.commit()` (WAL on, not fsynced) containing every row in R-1's
table; `commitSync()` every `fukuii.follow.sync-every` blocks (default 64), on `drain`/`stop`, and for every halt and
seed write. On restart the follower reads `follow.appliedHead`, resumes the `RecordSource` from the stored
`follow.sourcePosition` (Kafka offset or file index) minus one, and treats re-delivered records as duplicates (R-5).
Because the state, header, mapping and head pointer are in one batch, a crash between batches is invisible and a crash
inside one loses nothing (RocksDB WriteBatch atomicity, `storage-rocksdb.md` "Write path"). `vault` reviews the batch
composition and the `commitSync` cadence against `storage-rocksdb.md` (sync writes 10–100× slower; never in the hot
loop).
