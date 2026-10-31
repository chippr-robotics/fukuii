# Feature Specification: Change-Record Export Contract (Kafka)

**Feature Branch**: `025-change-record-export`

**Created**: 2026-10-30

**Status**: Clarified (2026-10-31). OQ-1..OQ-6 resolved (OQ-7 is a plan-time figure); ready for `/speckit-plan`. Companion of spec 023. **Time-critical part**: the
record schema (GT-1) must be agreed with spec 018's `data-model.md` owner before 018 slice F2 freezes the changeset
format. **Revised 2026-10-30 (alignment review, [023/alignment-review.md](../023-fleet-topology/alignment-review.md)
§2)**: the state diff is the **semantic write set** (accounts, slots, code by address), never trie nodes, so the
exporter is built on today's storage layout and does **not** wait for 018 Track F. 018 F2 shares the exportable part of
the format and keeps its trie-node undo records local.

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

## Problem

Four consumers need the same per-block facts and none can get them today:

- **Followers** (026) need, per canonical block, every state change with enough information to apply it and verify
  the resulting root.
- **The Chain Intelligence indexer** (separate service, Scala 3 on Kafka/Flink) needs headers, receipts, logs and
  state diffs with explicit reorg and finality semantics, so derived tables (historical balances) are exact.
- **The `archive` role** (026) needs the same stream to retain every block's state.
- **Rollback** inside the node (018 FR-010) needs the previous value of every key a block changed.

What exists: a Pekko pub-sub topic `NewBlockImported` that fires on *import* (not canonical commit), carries the
block only, and is fire-and-forget (`NodeBuilder.scala:135-139`, `BlockImporter.scala:172`); the per-block write set in
execution's in-memory world (`InMemoryWorldStateProxy.scala:184-196`: pending account writes, per-address storage,
codes, touched accounts), which is discarded after persist; and 018's planned changeset store, which records
**previous** values of trie nodes and flat keys for rollback, without `chainKey` or a schema version. Spec 018 Track H
also plans a `ColdStore` whose first adapter writes ClickHouse directly from the node, which would make the node a
second writer to the analytics schema.

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

## Non-goals

- The indexer's schemas, tables and correlation logic.
- Historical balance tables (derived by the indexer from these records).
- Transport-level multi-tenancy, ACLs or encryption policy on the broker (deployment; Orbita or the platform).
- Changing any consensus output. The exporter is an observer of committed state.
- Exporting pre-sync history on first start (backfill of blocks the node imported before the exporter existed is
  OQ-5; the default is "from the export position forward").

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
`parentHash`, and a record type (`block`, `retraction`, `finality`, and the reserved optional `witness`). Records are
RLP-encoded with the schema version as the leading byte. `chainKey` is the partitioning identity;
`chainId` is informational (024 DC-3).

**DC-2: The block record is complete, self-contained and layout-independent.** It contains the header, every receipt
with its logs, and the semantic state diff with **both previous and new values** for every touched account, slot and
code blob. A consumer can (a) apply it forward to reach the post-state, (b) apply it backward to reach the pre-state,
and (c) recompute the post-state root from its own commitment trie and compare with `header.stateRoot` (026 DC-1),
with no other input than the pre-state. The record MUST NOT contain trie nodes or any key that names a storage
namespace; a record produced on the Hash scheme, the Path scheme or the 018 unified layout is byte-identical.

**DC-3: Idempotent and ordered per chain.** Records for one `chainKey` are totally ordered by `blockNumber` and
consumers never infer order from arrival time. A duplicate record (same `blockNumber`, `blockHash`, type) is a no-op
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

**DC-6: Export never blocks import.** The exporter observes canonical commits through a bounded queue; a slow consumer
or an unavailable broker produces **lag** (a metric) and durable buffering (FR-005), never backpressure into
`BlockImporter` or the Engine API path. If the buffer bound is reached, the exporter stops *exporting* and raises a
health signal (023 FR-004 `sourceLag` on the consumer side; an `exportLag` gauge here); import continues.

**DC-7: The node writes no analytics store.** The only sink the node writes is the record topic (and the file sink in
tests). ClickHouse or any other analytics database is written by the indexer from the records (018 amendment request
3). 018's `ColdStore` *read* adapter is unaffected.

**DC-8: Consensus-neutral.** The exporter reads committed state and committed changesets. It adds no step to block
validation and changes no output. GT-2 confirms the hook points with `forge` and `beacon`.

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

---

## Related specs

- **018 Storage engine consolidation**: FR-010 changesets (the record's previous values), F2 (format freeze; GT-1 here),
  Track H `ColdStore` (amendment request 3), DC-3 (cold writes never on the import path, which DC-6 mirrors).
- **023 fleet-topology**: fence (FR-005 there) stops export; health shape carries FR-006 fields.
- **024 chain-registry**: `finalityRule`, `chainKey`, `chainId`.
- **026 follower-roles**: the primary in-house consumer; DC-2's root check is 026 DC-1.
- **Chain Intelligence** (separate service): the primary external consumer.

## Known tasks (seed for `/speckit-tasks`)

- **T-001** Record schema (DC-1, DC-2, FR-011), golden-bytes vectors, agreed with 018 `data-model.md` (GT-1); OQ-3.
- **T-002** GT-2 hook-point table with `forge` and `beacon`.
- **T-003** Exporter seam + file implementation + reference consumer (FR-002, FR-010); unit-level fixture replay.
- **T-004** Kafka implementation (FR-002, FR-003; OQ-1); `flow` reviews the bounded queue.
- **T-005** Export position, durable buffer and bound (FR-004, FR-005; OQ-2); `vault` reviews the store writes.
- **T-006** Reorg fixture set (FR-009) and SC-001; `forge` for the deep ETC reorg semantics.
- **T-007** Metrics and health fields (FR-006); SC-002/SC-003 in the 023 harness.
