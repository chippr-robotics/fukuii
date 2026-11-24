# Feature Specification: Change-Record Export Contract (Kafka)

**Feature Branch**: `025-change-record-export`

**Created**: 2026-10-30

**Status**: Planned (2026-11-03). Clarified 2026-10-31; plan with the GT-2 hook-point table, record schema (with block body), topic and golden-vector contracts, 91 tasks. Phase A: schema freeze with `vault` (018 F2) and `forge`/`beacon` hook sign-off; code in phase C. **Amended
2026-11-24**: transaction-pool records on a second topic, a safe-head watermark, and a chain-start record for chains
that reset (US-4, US-5; DC-10, DC-11; FR-013 to FR-019). v1 is not frozen yet (GT-1 open), so the new types join v1.

**Input**: Spec 023 Goal "define the change-record contract on Kafka that replicas, the archive role, the indexer and
history tables all consume, with reorg and finality semantics". User decisions D-3 (Kafka) and D-6 (ETC finality is
genesis only).

---

## Clarifications

### Session 2026-10-31

- Q: Kafka layout per chain? → A: **One topic per `chainKey`, a single partition, time-based retention** sized to the
  longest follower outage the operator tolerates; **never compacted** (retractions must stay visible); followers
  always bootstrap from a snapshot, never from Kafka alone.
- Q: Wire encoding? → A: **RLP payload with a leading schema-version byte.** The node and followers decode it
  natively and 018's changeset shares the bytes; the indexer re-encodes to Avro/Protobuf on its side if it wants a
  schema registry.
- Q: Export durability when Kafka is unavailable? → A: **Buffer in the node's own store**, keyed by block number and
  written in the same batch as the block's commit, bounded by blocks and bytes; at the bound **stop exporting and
  raise the health signal, never pause import**. The export position is a cursor into that buffer.
- Q: Witnesses (spec 017) on the same topic? → A: **Yes, as an optional record type, off by default**; the schema
  reserves the type now, 017's plan decides when it is produced.
- Defaults accepted without a question: receipts and logs ride **inside the block record** (OQ-4); the stream
  **starts at the export position** on first start, with no backfill of pre-exporter history (OQ-5).
- Alignment analysis (2026-10-31, F1/F2): the block record also carries the **block body** (transactions, uncles,
  withdrawals), and the decoded diff type is `domain/statediff/StateDiff` (shared with spec 026 and 018 F2), whose
  entries carry the `address` and `slotKey` preimages beside the hashed keys.

### Session 2026-11-24 (amendment)

- Q: Does the export carry the transaction pool? → A: **Yes, as two record types, `poolAdd` and `poolRemove`, on a
  second topic per chain**, `fukuii.<chainKey>.txpool.v1`. The pool lives in memory and is not part of any canonical
  commit, so pool records are **best-effort**: they skip the durable buffer, never wait on the broker, never slow
  admission or import, and carry a per-node sequence so a consumer sees exactly where it missed some (DC-10).
- Q: What does a pool record carry? → A: The transaction **exactly as a block body carries it** (so every field:
  type, chain ID, nonce, gas, fee fields, recipient, value, input, access list, blob versioned hashes, authorization
  list, signature), its hash, the recovered sender, how it arrived (peer, local submission, back from a reorg), and
  for a removal, why it left (included, replaced and by what, evicted, expired, dropped).
- Q: Chains that reset (Ephemery, spec 028)? → A: Keep **one record topic per `chainKey`**. Every chain instance
  opens with a **`chainStart` record** naming its chain ID and genesis; block numbers may restart only after one
  (DC-11). Spec 028's clean shutdown at a reset drains the exporter first, like a fence.
- Q: Safe head? → A: The ETH family emits a **`safe` watermark record** on every change of the applied
  `safeBlockHash`. It is advisory, unlike finality: a consumer may use it as a stronger depth signal, never as a
  promise. The ETC family never emits one.
- Q: New schema version? → A: No. GT-1 has not frozen `0x01` (T018 open), so the four types join v1 and the golden
  vectors cover them before the freeze.

## Problem

Four consumers need the same per-block facts and none can get them today:

- **Followers** (026) need, per canonical block, every state change with enough information to apply it and verify
  the resulting root.
- **Downstream consumers** (separate services, such as an indexer) need headers, receipts, logs and
  state diffs with explicit reorg and finality semantics, so derived tables (historical balances) are exact.
- **The `archive` role** (026) needs the same stream to retain every block's state.
- **Rollback** inside the node (018 FR-010) needs the previous value of every key a block changed.

A fifth needs what happens **before** a block (amendment 2026-11-24):

- **Mempool observers** (downstream consumers that follow a transaction from submission to inclusion, wallets and
  relayers that track their own submissions, fee estimators, mempool analytics) need every transaction the pool
  admits, with all its fields, and every removal with its cause. Today the only push source is
  `eth_subscribe newPendingTransactions` over WebSocket: one node at a time, no delivery guarantee, a full-transaction
  form without type, chain ID, fee caps or access list, and nothing at all when a transaction leaves the pool. The pool
  already knows each of those moments (`PendingTransactionsManager`: `admit`, `addOrOverride`, `HeadReset`, and one
  cache removal listener that every exit passes through).

Two gaps in the canonical stream itself: a chain that **resets** (Ephemery, spec 028) restarts block numbers at 0
under a new chain ID while its `chainKey` stays the same, which DC-3's total order by `blockNumber` forbids as first
written; and the ETH family's **safe head**, which the node already receives in every forkchoice update, is dropped.

What exists: a Pekko pub-sub topic `NewBlockImported` that fires on *import* (not canonical commit), carries the
block only, and is fire-and-forget (`NodeBuilder.scala:135-139`, `BlockImporter.scala:172`); the per-block write set in
execution's in-memory world (`InMemoryWorldStateProxy.scala:184-196`: pending account writes, per-address storage,
codes, touched accounts), which is discarded after persist; and 018's planned changeset store, which records
**previous** values of trie nodes and flat keys for rollback, without `chainKey` or a schema version. Spec 018 Track H
also plans a `ColdStore` whose first adapter writes ClickHouse directly from the node, which would make the node a
writer to an external analytics store.

The write set is the record. Because it exists on today's storage layout, the exporter does not wait for spec 018's
engine or flat-layout work (alignment review §2).

---

## Goals

1. One **record schema** that serves rollback, follower replay, archive retention, export and derived tables
   (GT-1 with 018 F2).
2. **Explicit reorgs and explicit finality**: consumers never infer either from gaps, arrival time or depth.
3. **Export never blocks import** (DC-6).
4. **Kafka** as the transport (D-3) behind a seam with a file implementation for tests.
5. The node writes **no analytics store** (DC-7; 018 amendment request 3).
6. **The transaction pool is observable** from the export: every admission with full fields, every removal with its
   cause, without a WebSocket bridge (DC-10; amendment 2026-11-24).
7. **Chain instances are explicit**: a reset is a record, never inferred from a chain ID change or a block number
   going backwards (DC-11).

## Non-goals

- Downstream consumers' schemas, tables and processing.
- Historical balance tables (derived by downstream consumers from these records).
- Transport-level multi-tenancy, ACLs or encryption policy on the broker (deployment; Orbita or the platform).
- Changing any consensus output. The exporter is an observer of committed state.
- Exporting pre-sync history on first start (backfill of blocks the node imported before the exporter existed is
  OQ-5; the default is "from the export position forward").
- Durable pool records. The pool is lost on restart, so a pool record lost to a broker outage is reported as a gap,
  never buffered (DC-10). A consumer that needs the pool's current contents after a gap reads `txpool_content`.
- Blob sidecars in pool records. A blob transaction is exported in its block-body form (versioned hashes, no blobs).
- Pool records from a node that is not synced: the pool drops peer transactions then (`SignedTransactionsFilterActor`),
  so there is nothing to export.

---

## Key entities

- **Block record**: header, transactions' receipts and logs, and the **state diff** of one canonical block.
- **State diff**: the block's **semantic write set**: for every touched account (`address` → account fields), every
  touched slot (`(address, slot)` → value) and every new code blob (`codeHash` → bytes), the previous value (or
  absent) and the new value (or deleted). Keys are hashed the way 018's flat stores hash them (`keccak(address)`,
  `keccak(address) ++ keccak(slot)`) so a follower on the 018 layout applies the record directly, and a follower on
  today's layout derives the trie update from the same keys. **Trie nodes never appear in a record**: they are a
  property of one storage layout, not of the chain.
- **Retraction record**: states that a previously exported `(blockNumber, blockHash)` is no longer canonical.
- **Finality watermark**: `(chainKey, finalizedNumber, finalizedHash)`; advances independently of block records.
- **Export position**: the last `(blockNumber, blockHash)` the exporter has durably handed to the transport, stored in
  the node's own store.
- **Pool record** (amendment 2026-11-24): `poolAdd` (a transaction entered this node's pool) or `poolRemove` (it
  left, with a reason). Per node, not per chain: two nodes' pools differ.
- **Pool stream position**: `(nodeId, session, seq)`. `session` is the node process's start time; `seq` counts pool
  records from 0 within a session. A consumer that sees a jump in `seq`, or a new `session` for a `nodeId`, knows
  what it missed: the records between, or the whole earlier pool.
- **Safe watermark**: `(chainKey, safeNumber, safeHash)` on the ETH family; advisory.
- **Chain-start record**: opens one chain instance on the record topic: its chain ID and genesis hash, and for a chain
  that iterates, its iteration number.
- **Canonical commit**: the moment a block becomes part of the chain the node reports as best. ETC: the block is
  imported as the new best block (or a reorg installs it). ETH family: the block is referenced by `headBlockHash` in
  an `engine_forkchoiceUpdated` the node has applied; `engine_newPayload` alone is **not** a canonical commit.

---

## Gates *(mandatory)*

- **GT-1: Record format agreed with spec 018.** The DC-2 record and the **exportable part** of 018's changeset record
  are **one format**, agreed with `vault` before 018 F2 freezes `data-model.md`: the flat diff (prev + new) with a
  version byte and `chainKey`. 018's trie-node undo records, which exist for local rollback on the hot tier, are
  engine-specific and are **not** part of this format. Output: the schema (OQ-3 decides the encoding) plus a
  golden-bytes test vector per record type. 018 amendment request 2 (refined 2026-10-30) is the ask; this gate records
  the answer.
- **GT-2: Canonical-commit hook located.** Before any exporter code, the implementing thread names the exact call
  sites where a block becomes canonical on each family (ETC: `BlockImporter` / `ReorgStateHandler` paths; ETH:
  `EngineApiController` forkchoice application) and confirms with `forge` and `beacon` that observing them changes no
  consensus output. Output: a table of hook points in `plan.md`.

---

## Design constraints *(mandatory)*

**DC-1: Record identity.** Every record carries `schemaVersion`, `chainKey`, `chainId`, `blockNumber`, `blockHash`,
`parentHash`, and a record type (`block`, `retraction`, `finality`, the reserved optional `witness`, and since the
2026-11-24 amendment `poolAdd`, `poolRemove`, `safe` and `chainStart`). Records are RLP-encoded with the schema
version as the leading byte. `chainKey` is the partitioning identity; `chainId` is informational (024 DC-3), except
that it tells one chain instance from the next after a `chainStart` (DC-11). In a pool record the block fields name
the node's canonical head when the pool event happened.

**DC-2: The block record is complete, self-contained and layout-independent.** It contains the header, the **block
body** (transactions in order; uncle headers on the ETC family; withdrawals on the ETH family where the fork defines
them), every receipt with its logs, and the semantic state diff with **both previous and new values** for every
touched account, slot and code blob. *(Body added 2026-10-31, alignment analysis F1: without transactions a follower
cannot serve receipt or transaction lookups; the body is verifiable against `header.transactionsRoot`, `ommersHash`
and `withdrawalsRoot`.)* A consumer can (a) apply it forward to reach the post-state, (b) apply it backward to reach the pre-state,
and (c) recompute the post-state root from its own commitment trie and compare with `header.stateRoot` (026 DC-1),
with no other input than the pre-state. The record MUST NOT contain trie nodes or any key that names a storage
namespace; a record produced on the Hash scheme, the Path scheme or the 018 unified layout is byte-identical.

**DC-3: Idempotent and ordered per chain.** Records for one `chainKey` are totally ordered by `blockNumber` and
consumers never infer order from arrival time. `blockNumber` restarts only immediately after a `chainStart` record
(DC-11). Pool records are outside this order (DC-10). A duplicate record (same `blockNumber`, `blockHash`, type) is a no-op
for every consumer. The exporter MAY re-send after a restart; it MUST NOT skip.

**DC-4: Reorgs are explicit.** A reorg is expressed as **retraction records** for every abandoned block, newest
first, followed by the new canonical block records from the fork point. A gap in `blockNumber` is a fault, never a
reorg. Retractions may reach any depth (ETC) or any depth above the finalized watermark (ETH family).

**DC-5: Finality is a separate watermark, not a flag.** Finality advances after a block is exported, so it is its own
record type carrying `(chainKey, finalizedNumber, finalizedHash)`.
- **ETH family**: the watermark follows the consensus client's `finalizedBlockHash` from `engine_forkchoiceUpdated`,
  emitted on every change.
- **ETC family**: **only block 0 is final** (D-6). fukuii emits one watermark at genesis on first start and never
  advances it. Consumers that want a confirmation-depth policy apply it themselves and own the consequences; fukuii
  declares none, and MESS (024 DC-2) does not change this.
- **Safe head (amendment 2026-11-24)**: the ETH family also emits a `safe` record on every change of the applied
  `safeBlockHash`. It is the consensus client's justified head, passed on as received: a consumer MAY treat blocks at
  or below it as unlikely to be retracted, MUST NOT treat them as irrevocable, and the retraction rule of FR-007 stays
  tied to finality alone. The ETC family emits none; there is no safe head on proof of work.

**DC-6: Export never blocks import.** The exporter observes canonical commits through a bounded queue; a slow consumer
or an unavailable broker produces **lag** (a metric) and durable buffering (FR-005), never backpressure into
`BlockImporter` or the Engine API path. If the buffer bound is reached, the exporter stops *exporting* and raises a
health signal (023 FR-004 `sourceLag` on the consumer side; an `exportLag` gauge here); import continues.

**DC-7: The node writes no analytics store.** The only sink the node writes is the record topic (and the file sink in
tests). Any analytics database is written by downstream consumers from the records (018 amendment request
3). 018's `ColdStore` *read* adapter is unaffected.

**DC-8: Consensus-neutral.** The exporter reads committed state and committed changesets. It adds no step to block
validation and changes no output. GT-2 confirms the hook points with `forge` and `beacon`.

**DC-10: Pool records are best-effort and never wait (amendment 2026-11-24).** Pool records go to their own topic
(`contracts/topic.md` §8), not to the record topic, and never through the FR-005 buffer: the pool is in memory, so a
record kept across a broker outage would describe a pool that a restart has already lost. The pool hands each record
to a bounded in-memory queue with a non-blocking offer; a full queue, a broker error or a fenced or halted exporter
drops the record and counts it. Every pool record carries `(nodeId, session, seq)` so the drop is visible to the
consumer as a gap. Admission, removal, gossip and import never wait on the export. Pool records keep a per-sender order
(key = sender), and no order against the record topic: a consumer may see a block record before the `poolRemove`
for a transaction it includes, or the reverse.

**DC-11: Chain instances are explicit (amendment 2026-11-24).** The first record the exporter publishes for a datadir
is a `chainStart` record (chain ID, genesis hash, iteration). A chain that resets (Ephemery: spec 028 exits with code
75 and restarts on the next iteration in a new datadir) therefore opens every iteration with one on the **same** topic,
and block numbers restart after it. A consumer that sees a `chainStart` whose chain ID or genesis hash differs from
the last one closes the earlier instance: its blocks, its finality and every pending transaction of it are gone.
Before the exit at a reset the node drains the exporter as on a fence (023 FR-005 edge case below); if the broker is
down then, the old instance's tail is lost and the `chainStart` still tells the consumer it ended.

**DC-9: One schema, three uses.** The record 018 F2 writes for rollback, the record followers replay, and the record
the indexer consumes are the same bytes with the same version byte (GT-1). A second schema is a defect.

---

## User Scenarios & Testing *(mandatory)*

### User Story 1 — A reorg propagates correctly (Priority: P1)

A reorg on the source chain reaches followers and the indexer as retractions plus new records.

**Independent Test**: SC-001.

**Acceptance Scenarios**:
1. **Given** a two-block reorg fixture, **When** it is replayed through `ingest`, **Then** a follower and a reference
   consumer end in the same state as a clean derivation from the new canonical chain.
2. **Given** an ETC reorg deeper than any consumer's chosen confirmation depth, **When** replayed, **Then** fukuii
   emits retractions for every abandoned block and makes no finality claim beyond genesis.
3. **Given** an ETH-family reorg below the finalized watermark (impossible on a correct CL), **When** it is injected,
   **Then** the exporter refuses to retract a finalized block, logs at ERROR and halts export (never silently rewrites
   finalized history).

### User Story 2 — The broker is down and nothing is lost (Priority: P1)

**Independent Test**: SC-003.

**Acceptance Scenarios**:
1. **Given** Kafka unavailable for the configured bound, **When** blocks keep importing, **Then** import p99 is
   unchanged (SC-002), the export position does not advance, records are buffered durably, and on recovery every block
   since the position is exported in order with no gap.
2. **Given** the buffer bound is exceeded, **When** the next block commits, **Then** export stops, `exportLag` and the
   health endpoint report it, and import continues.

### User Story 3 — The indexer derives balances exactly (Priority: P1)

**Independent Test**: SC-001's reference consumer, SC-004.

**Acceptance Scenarios**:
1. **Given** the record stream for a block range, **When** a consumer folds state diffs forward from a known
   pre-state, **Then** every account balance at every block equals `eth_getBalance` at that block on an archive node.

### User Story 4 — Pending transactions are observable without a bridge (Priority: P2; amendment 2026-11-24)

A downstream consumer follows a transaction from the moment a node's pool accepts it to inclusion, replacement or
loss, with every field of the transaction, from the export alone.

**Independent Test**: SC-006.

**Acceptance Scenarios**:
1. **Given** a typed (`0x02`) transaction submitted over RPC, **When** the pool admits it, **Then** one `poolAdd`
   with `source = local` appears on the pool topic, and decoding its `tx` item yields the same type, chain ID, nonce,
   fee caps and access list as `eth_getTransactionByHash`.
2. **Given** a pooled transaction and a replacement at the same sender and nonce that meets the price bump, **When**
   the replacement is admitted, **Then** a `poolRemove` with reason `replaced` names the new hash, and a `poolAdd` for
   the new one follows on the same key.
3. **Given** a pooled transaction, **When** a block that includes it becomes canonical, **Then** a `poolRemove` with
   reason `included` appears; **when** that block is retracted and the transaction goes back into the pool, **Then**
   a `poolAdd` with `source = reorg` appears.
4. **Given** the pool at its size limit, or a transaction past its timeout, **When** it leaves, **Then** the
   `poolRemove` reason is `evicted` or `expired`.
5. **Given** the broker down for a minute, **When** it returns, **Then** pool records resume with a `seq` jump equal
   to the number dropped, `fukuii_export_pool_dropped_total` says the same, and import p99 and admission were
   unaffected.

### User Story 5 — A chain reset is a record (Priority: P2; amendment 2026-11-24)

An Ephemery node runs across a reset under a supervisor; consumers of `fukuii.ephemery.records.v1` see the old
iteration end and the new one start, with no fault.

**Independent Test**: SC-007.

**Acceptance Scenarios**:
1. **Given** an exporting Ephemery node on iteration N, **When** the reset time passes, **Then** the exporter drains
   to the broker before the exit with code 75, and after the supervisor restart the first record on the same topic is
   a `chainStart` for iteration N+1 (chain ID `39438000 + N + 1`, the published genesis hash), followed by block
   records from the new genesis.
2. **Given** a consumer of that topic, **When** it reads across the reset, **Then** block numbers going back to 0
   after the `chainStart` is not a gap or a reorg (DC-4), and a block number going back anywhere else still is.

### Edge cases

- A record for a block whose parent the consumer has not seen: the consumer waits (026 FR-008); the exporter never
  emits out of order on one partition.
- The exporter restarts after exporting block N but before persisting position N: it re-exports N (DC-3 duplicates).
- `fence` (023 FR-005) arrives: the exporter drains its queue to the transport if the broker is up, persists the
  position, and stops. A fenced process never exports again until unfenced.
- The chain's first start: one genesis finality watermark (ETC) or the CL's first finalized hash (ETH), then block
  records from the export position (OQ-5 for backfill).
- A block whose state diff is empty (no transactions, no reward change on ETH): still a block record; the diff is
  empty and the root check still passes.
- Receipts with `eth/70` partial receipts in flight: the record carries full receipts from the node's own execution,
  never the partial form.
- A transaction moved off the chain by a reorg that the pool does not take back (its nonce was spent on the new
  chain, or a pooled transaction holds its nonce): no `poolAdd`, because it never re-enters the pool. The replacement
  chain's block records say what happened to that nonce.
- The same transaction re-sent over RPC while pooled replaces itself in place: no record (nothing entered or left).
- A pool removal the cache reports without a cause the pool set (a cleanup it did not ask for) is exported with reason
  `dropped`; `included`, `replaced`, `evicted` and `expired` are only used where the pool knows them.
- Several nodes publish pool records for one chain: each carries its own `nodeId` and sequence, and the same
  transaction appears once per node that admitted it. First-seen is per node.
- A node restarts: its pool is empty and its `session` changes. It emits no `poolRemove` for what it held; the new
  `session` is the signal.

---

## Requirements *(mandatory)*

### Functional requirements

- **FR-001** `ingest` MUST produce one block record per canonical commit (DC-2), retraction records on reorg (DC-4)
  and finality watermarks (DC-5), at the hook points GT-2 names.
- **FR-002** Records MUST be published through an **exporter seam** with two implementations: Kafka (production) and
  append-only files (tests, offline tools). Neither implementation is referenced outside the seam. Records are
  RLP-encoded with a leading schema-version byte (DC-1); no other encoding is produced by the node.
- **FR-003** One topic per `chainKey` with a **single partition** and **time-based, non-compacted retention**; per-chain
  total order MUST hold. The topic name, the retention guidance (at least the longest tolerated follower outage) and
  the ordering guarantee MUST be documented in `contracts/`. Followers MUST NOT depend on Kafka retention for
  bootstrap (026 FR-001 seeds from a snapshot).
- **FR-004** The export position MUST be persisted in the node's own store after the transport acknowledges, and MUST
  be the resume point after restart and after `unfence`. It is a cursor into the FR-005 buffer.
- **FR-005** Every record MUST be written to a **durable export buffer in the node's own store**, keyed by block
  number, in the same atomic batch as the block's commit; the exporter drains it to the transport in order. The buffer
  is bounded by blocks and by bytes (both configured); at the bound the exporter MUST stop exporting and set the
  FR-006 health signal, and import MUST continue (DC-6). Drained records are deleted from the buffer once the position
  passes them.
- **FR-006** The exporter MUST export `exportLag` (blocks between head and the export position), `exportQueueDepth`,
  `exportBufferBytes` and `exportLastError` as metrics and in the 023 FR-004 health shape.
- **FR-007** Retractions MUST be emitted newest-first before the replacement block records, and MUST never target a
  block at or below the current finality watermark (US-1 scenario 3).
- **FR-008** The state diff MUST be the semantic write set of DC-2, keyed by hashed address and hashed slot as 018's
  flat stores key them, so a follower on the 018 layout applies it without translation and a follower on today's
  layout derives its trie update from the same keys (GT-1). It MUST NOT contain trie nodes. Code MUST be a dependency
  of the account leaf that references it (the diff includes new code bytes keyed by code hash). The producer reads the
  write set from execution's in-memory world (`InMemoryWorldStateProxy`: pending account writes, per-address storage
  writes, codes, touched accounts) before the block is persisted; GT-2 names the exact site.
- **FR-009** The repository MUST provide a **reorg fixture set**: a two-block and a deep (≥ 100 block) ETC reorg, an
  ETH-family reorg above finality, an injected below-finality attempt, and a restart-mid-export case; replayable in the
  023 harness and in a unit-level file-sink test.
- **FR-010** A **reference consumer** (test code) MUST fold records into a flat state and compare roots and balances
  against the node (SC-001, SC-004); it is the oracle for every follower and indexer claim.
- **FR-011** Every record MUST begin with the schema version; a consumer seeing an unknown version MUST stop, not
  guess. The schema and its golden-bytes vectors live in `contracts/` and are shared with 018 `data-model.md`.
- **FR-012** The schema MUST reserve a `witness` record type carrying spec 017's `ExecutionWitness` for a block,
  published only when the operator enables it (default off); 017's plan decides which role produces it.
- **FR-013** *(amendment 2026-11-24)* When `fukuii.export.pool.enabled` is on, the node MUST publish a `poolAdd` for
  every transaction its pool admits (peer delivery, local submission, re-admission after a reorg) and a `poolRemove`
  for every transaction that leaves it, on `fukuii.<chainKey>.txpool.v1` (`contracts/topic.md` §8). Off by default.
- **FR-014** A `poolAdd` MUST carry the transaction item exactly as a block body carries it
  (`contracts/record-schema.md` §3 `transactions`), its hash, the recovered sender and its `source`
  (`peer` | `local` | `reorg`). Every field of the transaction is recoverable from that item; no field is dropped or
  re-encoded.
- **FR-015** A `poolRemove` MUST carry the hash, sender, nonce and a `reason`: `included` (a canonical block took it),
  `replaced` (with the replacing hash), `evicted` (the pool's size limit or the blob-sidecar byte budget), `expired`
  (the pool's transaction timeout), or `dropped` (any other removal: an explicit remove, a pool clear). The reason is
  set where the pool decides the removal, not guessed in the removal listener.
- **FR-016** Pool records MUST NOT block or slow admission, gossip or import (DC-10): a bounded queue with a
  non-blocking offer, and a drop counted in `fukuii_export_pool_dropped_total`. Every pool record carries
  `(nodeId, session, seq)` with `seq` contiguous within a session, so drops are visible to consumers.
- **FR-017** The exporter MUST publish a `chainStart` record as the first record for a datadir (DC-11), before the ETC
  genesis finality record, and MUST drain to the transport before the spec 028 exit at a reset as it does on a fence.
- **FR-018** On the ETH family the exporter MUST publish a `safe` record on every change of the applied
  `safeBlockHash` (DC-5), through the same emission log as finality; never on the ETC family.
- **FR-019** The quickstart and the operator docs MUST show how to create the pool topic and how a consumer detects
  and recovers from a pool gap (`txpool_content`).

### Success criteria

- **SC-001** On the FR-009 fixture set, the reference consumer's state equals a clean derivation from the canonical
  chain, for ETC (genesis-only finality, including the deep reorg) and for an ETH-family chain (CL finality).
- **SC-002** With the exporter on, off, and on with the broker down, `ingest` p99 import time differs by no more than
  the 023 GT-3 noise band.
- **SC-003** After a broker outage shorter than the FR-005 bound, the stream has no gap and no out-of-order record;
  after an outage longer than the bound, export is stopped, the health signal is raised, and import continued.
- **SC-004** Balances folded by the reference consumer over a fixed Mordor range and a fixed Platåberget range equal
  `eth_getBalance` on an archive reference at every sampled block.
- **SC-005** One golden-bytes vector set is consumed by 018's changeset tests and by this spec's codec tests; the
  vectors are identical files.
- **SC-006** *(amendment)* On a pool fixture (admit, local submit, replace, include, retract and re-admit, evict,
  expire), the pool topic carries exactly the expected records in per-sender order with contiguous `seq`; with the
  broker down, the `seq` jump equals the drop counter; and import p99 and admission latency with pool export on are
  within the 023 GT-3 noise band of export off.
- **SC-007** *(amendment)* Across a simulated Ephemery reset in the 023 harness, the record topic carries the old
  iteration's records up to its last exported block, then one `chainStart` for the new iteration, then its blocks;
  the reference consumer (FR-010) treats it as a new chain and reports no fault.

---

## Open questions

- **OQ-1** *(resolved 2026-10-31)*: single partition per chain, time-based non-compacted retention, snapshot bootstrap
  (FR-003).
- **OQ-2** *(resolved 2026-10-31)*: durable buffer in the node's store, same batch as the commit, bounded by blocks and
  bytes; stop export at the bound, never import (FR-005).
- **OQ-3** *(resolved 2026-10-31)*: RLP with a leading version byte (DC-1, FR-002).
- **OQ-4** *(default accepted 2026-10-31)*: receipts and logs inside the block record.
- **OQ-5** *(default accepted 2026-10-31)*: the stream starts at the export position; no backfill in the first release.
- **OQ-6** *(resolved 2026-10-31)*: reserved optional `witness` record type, off by default (FR-012).
- **OQ-7 (new)**: the concrete buffer bounds per chain (blocks and bytes) and the retention figure per chain, to be set
  from the 023 harness's outage tests in `plan.md`.
- **OQ-8 (amendment 2026-11-24)**: pool topic sizing: partition count (default 1; any count keeps per-sender order
  because the key is the sender), retention (default guidance 24 hours) and the queue bound, from a mainnet-scale
  gossip measurement. Default accepted for now: 1 partition, 24 hours, 65,536 records.
- **OQ-9 (amendment 2026-11-24)**: the default `nodeId`: the node's enode ID (stable across restarts) or an operator
  label. Default accepted for now: the first 8 bytes of the node ID, overridable by `fukuii.export.node-id`.

---

## Related specs

- **018 Storage engine consolidation**: FR-010 changesets (the record's previous values), F2 (format freeze; GT-1 here),
  Track H `ColdStore` (amendment request 3), DC-3 (cold writes never on the import path, which DC-6 mirrors).
- **023 fleet-topology**: fence (FR-005 there) stops export; health shape carries FR-006 fields.
- **024 chain-registry**: `finalityRule`, `chainKey`, `chainId`.
- **026 follower-roles**: the primary in-house consumer; DC-2's root check is 026 DC-1.
- **028 built-in Ephemery**: the reset exit (FR-016 there) is where FR-017's drain runs; each iteration's datadir has
  its own export state, so each opens with a `chainStart` (DC-11).
- **029 TUI command center**: FR-034 there counts pool evictions and expiries; FR-015's `evicted` and `expired` are
  the same events.
- **Downstream consumers** (separate services): the external consumers of the records.

## Known tasks (seed for `/speckit-tasks`)

- **T-001** Record schema (DC-1, DC-2, FR-011), golden-bytes vectors, agreed with 018 `data-model.md` (GT-1); OQ-3.
- **T-002** GT-2 hook-point table with `forge` and `beacon`.
- **T-003** Exporter seam + file implementation + reference consumer (FR-002, FR-010); unit-level fixture replay.
- **T-004** Kafka implementation (FR-002, FR-003; OQ-1); `flow` reviews the bounded queue.
- **T-005** Export position, durable buffer and bound (FR-004, FR-005; OQ-2); `vault` reviews the store writes.
- **T-006** Reorg fixture set (FR-009) and SC-001; `forge` for the deep ETC reorg semantics.
- **T-007** Metrics and health fields (FR-006); SC-002/SC-003 in the 023 harness.
- **T-008** *(amendment)* Codec and vectors for `poolAdd`, `poolRemove`, `safe`, `chainStart` (before GT-1 freezes).
- **T-009** *(amendment)* Pool hooks in `PendingTransactionsManager`, the pool queue and the pool topic (FR-013 to
  FR-016, SC-006).
- **T-010** *(amendment)* `chainStart`, the reset drain and the `safe` watermark (FR-017, FR-018, SC-007).
