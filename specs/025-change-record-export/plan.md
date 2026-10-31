# Implementation Plan: Change-Record Export Contract (Kafka)

**Branch**: `025-change-record-export` | **Date**: 2026-10-31 | **Spec**: [spec.md](spec.md) (Clarified 2026-10-31)

**Input**: Feature specification from `specs/025-change-record-export/spec.md`; the alignment review
[023/alignment-review.md](../023-fleet-topology/alignment-review.md) §2 (semantic write set, layout independence), §3
(025, 018 amendments) and §4 (phase placement); spec 018's pending amendment table rows 2, 7, 8.

**Phase placement** (alignment review §4): the schema, the golden vectors and the GT-2 hook table are **phase A**
(docs and test-only, beside 022's release gates); every line of exporter code is **phase C** (0.13.0, after 020 PR 5–7
and 023 T-002/T-003). Nothing here touches a file 022 slices 4–6 own.

## Summary

`ingest` will publish, per canonical block, one self-contained RLP record: header, receipts with logs, and the block's
**semantic write set** (accounts by `keccak(address)`, slots by `keccak(address) ++ keccak(slot)`, code by `codeHash`,
each with previous and new value, preimages beside the hashed keys). Reorgs are explicit retraction records, newest
first; finality is a separate watermark record (ETH: the CL's `finalizedBlockHash`; ETC: genesis, once). Records go
through an exporter seam with a Kafka implementation (one topic per `chainKey`, one partition, time-based non-compacted
retention) and an append-only-file implementation for tests.

**How, on today's code** ([research.md](research.md)):
- A `BlockWriteSetRecorder` is threaded through execution the way the EIP-7928 `BlockAccessRecorder` already is. It
  collects the dirty **keys** of `InMemoryWorldStateProxy`'s caches before every per-transaction `persistState` and
  resolves previous values once, at block end, from a trie at the parent root (R-1). The naïve "read the proxies at
  persist" idea does not work because `persistState` runs after every transaction (`BlockPreparator.scala:689-693`).
- The record is written as a **staged record** in the execution-commit batch (keyed by block number and hash), and an
  **emission-log** entry is written in the batch that makes the block canonical: the same batch for ETC extend-best,
  `rewriteCanonicalIndex` for ETC reorgs, `promoteToCanonicalHead` for ETH forkchoice (R-4, R-6). Both live in one new
  column family `x`.
- The exporter is a cats-effect fiber that drains the log in order to the sink, persists the position after the ack,
  and never blocks the import thread: the hook does a durable write it was already doing plus a non-blocking wake (R-5).
- Previous values, retractions and finality give followers (026) and the indexer everything DC-2 promises; a test-only
  reference consumer proves it on a reorg fixture set (R-7, R-8).

## Technical Context

- **Language/Version**: Scala 3.9.0 LTS (`build.sbt:50`) on JDK 25; sbt 1.13.0. No behaviour change to execution.
- **Primary dependencies**: cats-effect 3.6.3 and fs2 3.12.2 (present, `project/Dependencies.scala:79-95`); Pekko
  Typed for the existing actors the hooks sit in; RLP module `rlp/`; **new**: `org.apache.kafka:kafka-clients` 3.9.x
  (Java artifact, Scala-version independent; research R-3; Complexity Tracking).
- **Storage**: RocksDB through `DataSource` (`db/dataSource/DataSource.scala`); one new column family `x`
  (`Namespaces.ExportNamespace`) with two key spaces (staged records, emission log); four new AppState keys in CF `s`
  (`export.position`, `export.finality`, `export.bufferBytes`, `export.halted`). Opened automatically on an existing
  datadir (`RocksDbDataSource.scala:798`, `setCreateMissingColumnFamilies(true)`); no migration.
- **Testing**: ScalaTest + ScalaCheck (`scalacheck-1-18`) for codec round-trips and golden vectors; the real import
  path on `EphemDataSource` (`ReorgBlockhashParitySpec`'s `ChainHost` pattern) for the reorg fixtures; `MockProducer`
  from kafka-clients for the Kafka sink's ordering logic; the 023 harness (docker broker) for SC-002/SC-003; an archive
  reference node for SC-004. No `Thread.sleep`; the drain loop is tested with `TestControl` / explicit `wake`.
- **Target platform**: the reference host (4 cores, 16 GB, SATA SSD); `ingest` role only (023 FR-002).
- **Project type**: single JVM application; one new package `export/` plus a test-only reference consumer.
- **Performance goals**: SC-002 — `ingest` p99 import time with export on/off/broker-down within the 023 GT-3 noise
  band; the recorder's block-end resolution is O(touched keys) trie reads against warm caches.
- **Constraints**: DC-6 (export never blocks import), DC-8 (consensus-neutral; GT-2), byte-identical records across
  Hash/Path/018 layouts (DC-2), bounded buffer with halt-not-pause semantics (FR-005), no analytics store (DC-7).
- **Scale/Scope**: Sepolia records 0.2–2 MB at one per 12 s; Mordor tens of KB at one per 13 s; reorg depth
  unbounded on ETC (fixtures to ≥ 100), bounded by finality on ETH.

No `NEEDS CLARIFICATION` remains: OQ-1..OQ-6 are closed in the spec; OQ-7's figures are plan-time defaults in
research R-9 and `data-model.md` §8, to be replaced by the 023 harness measurements.

## Constitution Check

*GATE: passed before research; re-checked after design (this table).*

| Principle | Status | Notes |
|-----------|--------|-------|
| I. Consensus determinism | **Gate — consensus-adjacent, no consensus change.** | The recorder reads immutable maps and committed tries after validation; it writes nothing into the world and alters no root, gas or hash. The only new writes are puts added to batches that already exist at each site. Hook sites are in `ledger/` and `consensus/`, so `forge` (ETC paths) and `beacon` (ETH paths) sign the GT-2 table **before** slice 1 and review slices 1–2. ETC and ETH hook sites are distinct methods; no shared `if family` branch is introduced inside execution. A recorder failure is caught and halts export, never the block. |
| II. Spec-driven | Pass | This plan, research, data model, contracts, quickstart and tasks under `specs/025-change-record-export/`; GT-1 and GT-2 are the two gates the spec names. |
| III. Test discipline | Pass | Codec: golden vectors + ScalaCheck round-trip. Hooks: the five FR-009 fixtures through the real import path on `EphemDataSource`. Sink: `MockProducer`; broker only in the harness. Tiers: unit specs untagged/`UnitTest`; harness runs `IntegrationTest`. Coverage stays ≥ 70% (new code is codec and loops, both unit-testable). No `Thread.sleep`. |
| IV. Idiomatic Scala 3 | Pass | New package `com.chipprbots.ethereum.export`; `enum RecordType`; opaque-free (records are plain case classes over `ByteString`); explicit result types; `IO` for the drain loop; scalafmt/scalafix clean before each PR. |
| V. Quality gates | Pass | One PR per slice; `sbt pp` before each; `eye` validates. |
| VI. Security | Pass with a note | Kafka credentials arrive through `fukuii.export.kafka.properties` and are never logged (the sink redacts `sasl.*`/`ssl.*password`); the file sink writes under the datadir. No new network listener. Records contain public chain data only. |
| VII. Versioning / ADRs | Pass | ADR **INF-010 "Change-record export: semantic write set, buffer and Kafka"** records the schema freeze, the Kafka client choice and the two-key-space buffer (slice 0). `0.13.0` minor per alignment §4. |

## Project Structure

### Documentation (this feature)

```text
specs/025-change-record-export/
├── spec.md                 # Clarified 2026-10-31 (unchanged by this plan)
├── plan.md                 # this file
├── research.md             # R-1..R-9 decisions with alternatives
├── data-model.md           # records, CF x, AppState keys, metrics, config
├── quickstart.md           # US1/US2/US3 runnable scenarios
├── tasks.md                # dependency-ordered task list
└── contracts/
    ├── record-schema.md    # byte-level RLP layout (shared with 018 F2)
    ├── topic.md            # naming, partition, retention, ordering guarantees
    └── golden-vectors.md   # vector set and its location
```

### Source Code (repository root)

```text
src/main/scala/com/chipprbots/ethereum/
├── export/                                   # NEW package (justification below)
│   ├── record/                               # pure: depends on domain, rlp, crypto only (018 F2 can reuse it)
│   │   ├── Record.scala                      # enum RecordType; Record, BlockBody, AccountEntry, SlotEntry, CodeEntry
│   │   ├── RecordCodec.scala                 # version byte + RLP encode/decode, canonical ordering, netting
│   │   └── WriteSet.scala                    # the semantic diff model (prev/new) the recorder produces
│   ├── capture/
│   │   ├── BlockWriteSetRecorder.scala       # key collection per persist; block-end resolution (research R-1)
│   │   └── RecordBuilder.scala               # Block + receipts + WriteSet → Record
│   ├── buffer/
│   │   ├── ExportBufferStorage.scala         # CF x: staged records + emission log (typed over DataSource)
│   │   ├── ExportPosition.scala              # AppState keys export.{position,finality,bufferBytes,halted}
│   │   └── EmissionLogEntry.scala            # [kind, blockNumber, blockHash]
│   ├── hooks/
│   │   ├── CanonicalCommitObserver.scala     # the single API the hook sites call; builds the log puts for a batch
│   │   └── FinalityWatermark.scala           # below-finality refusal; ETC genesis emission
│   ├── sink/
│   │   ├── RecordSink.scala                  # the seam: publish(Chunk[Record]): IO[Unit]; close
│   │   ├── KafkaRecordSink.scala             # kafka-clients producer (research R-3)
│   │   └── FileRecordSink.scala              # length-prefixed append-only segments (research R-7)
│   ├── Exporter.scala                        # drain loop: log → sink → position; halt/fence states
│   ├── ExportMetrics.scala                   # MetricsContainer: fukuii_export_*
│   └── ExportConfig.scala                    # fukuii.export.* (data-model.md §8)
├── ledger/
│   ├── BlockExecution.scala                  # MODIFIED: Option[BlockWriteSetRecorder] threading; staged-record put at
│   │                                         #   executeAndCommit and executeAndValidateBlockFull
│   └── BlockPreparator.scala                 # MODIFIED: recorder.observe(world) before the per-tx persistState (:693)
├── consensus/
│   ├── ConsensusImpl.scala                   # MODIFIED: settleHead emits into the rewriteCanonicalIndex batch
│   └── engine/ForkChoiceManager.scala        # MODIFIED: emits into the promoteToCanonicalHead batch; finality entry
├── blockchain/sync/regular/BlockImporter.scala  # MODIFIED: rewind emits retractions into setCanonicalChainHead
├── domain/BlockchainWriter.scala             # MODIFIED: `extra` batch param on promoteToCanonicalHead,
│                                             #   rewriteCanonicalIndex, setCanonicalChainHead
├── db/storage/
│   ├── Namespaces.scala                      # MODIFIED: ExportNamespace 'x' in nsSeq
│   └── AppStateStorage.scala                 # MODIFIED: Keys.Export{Position,Finality,BufferBytes,Halted}
├── nodebuilder/NodeBuilder.scala             # MODIFIED: ExportBuilder trait wires recorder, observer, exporter, sink
└── jsonrpc/NodeJsonRpcHealthChecker.scala    # MODIFIED: exportLag, exportQueueDepth, exportBufferBytes,
                                              #   exportLastError, exportState entries

src/main/resources/conf/base/
├── fukuii.conf                               # MODIFIED: include "export.conf"
└── export.conf                               # NEW: fukuii.export defaults

src/test/scala/com/chipprbots/ethereum/export/
├── record/RecordCodecSpec.scala              # golden vectors (read/compare; -Dexport.golden.write)
├── record/RecordCodecPropertySpec.scala      # ScalaCheck round-trip, ordering, netting
├── capture/BlockWriteSetRecorderSpec.scala   # per-persist key collection, prev/new resolution, destroyed storage
├── buffer/ExportBufferStorageSpec.scala      # key spaces, bounds, housekeeping, restart recount
├── hooks/CanonicalCommitObserverSpec.scala   # ETC/ETH emission sets, below-finality refusal
├── sink/FileRecordSinkSpec.scala             # append, roll, truncated tail
├── sink/KafkaRecordSinkSpec.scala            # MockProducer: order, ack, timeout → halt
├── ExporterSpec.scala                        # drain, position-after-ack, restart re-send, fence
├── ReferenceConsumer.scala                   # FR-010 oracle (test code)
└── fixtures/ReorgFixtures.scala              # FR-009 set on ChainHost (research R-8)
src/test/resources/export/golden/v1/          # vectors + manifest.json (contracts/golden-vectors.md)
src/it/scala/com/chipprbots/ethereum/export/  # harness-facing: SC-002 timing, SC-003 outage, SC-004 archive compare
```

**Structure decision.** A new top-level package `export/` rather than `fleet/export/` or `jsonrpc/`:
- `fleet/` does not exist on the tree (`grep -rl "ethereum.fleet" src/main` is empty) and is 023's to create in phase
  B; this package must compile in phase C independently of how 023 lays out role wiring.
- The constitution's module list names layered node packages (`blockchain/sync`, `consensus`, `db/storage`, `domain`,
  `jsonrpc`, `ledger`, …); `export/` is a sibling at that layer, depending downward on `domain`, `rlp`, `crypto`, `db`
  and being called from `ledger`/`consensus` through one interface (`CanonicalCommitObserver`, `BlockWriteSetRecorder`)
  injected by `NodeBuilder`, so no cycle: `ledger` depends on the `export.capture` *interface*, not on sinks or Kafka.
- `export/record/` has no dependency above `domain`/`rlp`/`crypto`, so 018 F2's code under `db/` can depend on the
  codec (GT-1: one format) without importing the exporter.
- It is not `jsonrpc/`: no RPC surface; `SubscriptionEvents.scala` (`NewBlockImported`, import-time, fire-and-forget)
  stays as it is for WebSocket subscriptions and is not the canonical-commit signal (spec, Problem).

## Design decisions (summary; details in research.md)

| # | Decision | Where |
|---|----------|-------|
| D-1 | `BlockWriteSetRecorder` collects dirty keys before each `persistState`, resolves prev/new at block end from the parent-root trie and the final world; destroyed accounts' storage is walked; equal prev/new netted; failures halt export, never the block | R-1 |
| D-2 | Framing `0x01 ++ RLP(envelope)`; reuse `BlockHeaderEnc`, `ReceiptCodecs`, `AccountEnc`, `rlpBigIntSerializer`; grouped slot entries; preimages beside hashed keys; code new-only | R-2, `contracts/record-schema.md` |
| D-3 | `kafka-clients` Java 3.9.x, `acks=all`, idempotent, one in flight, zstd; `MockProducer` for unit tests | R-3, `contracts/topic.md` |
| D-4 | CF `x`: staged records at execution commit + emission log at canonical commit, both inside existing batches; bounds halt the log, never import | R-4, `data-model.md` §6 |
| D-5 | `export.position` written after ack with `update`; `updateSync` on fence/shutdown; resume from `seq + 1` | R-5 |
| D-6 | Per-family hook sites and emission sets; ETH emits from `lastEmitted + 1` over the canonical index, not only `move.adopted`; below-finality attempt halts | R-6, GT-2 table |
| D-7 | File sink (length-prefixed segments) and `ReferenceConsumer` (flat maps + MPT root recompute) as test oracle | R-7 |
| D-8 | `ReorgFixtures` on `ChainHost`, deterministic, five FR-009 shapes incl. ≥ 100-block ETC and a SELFDESTRUCT with storage | R-8 |
| D-9 | Defaults 4096 blocks / 4 GiB / 1024 side-block retention / 7-day topic retention until the harness measures | R-9 |

## GT-2 hook-point table

Status: **drafted by this plan; requires `forge` (rows E1–E4) and `beacon` (rows H1–H3) sign-off before slice 1
starts.**
Each row names the call site that makes a block canonical (or stops it being), what the exporter adds to the batch at
that site, and why observing it changes no consensus output. "Batch" = the `DataSourceBatchUpdate` already committed
there; the exporter only `.and`s puts onto it.

| Row | Family | Event | Exact site (file:line, today) | Exporter action in that batch | Consensus-adjacent? | Sign-off |
|-----|--------|-------|-------------------------------|-------------------------------|---------------------|----------|
| X0 | both | **Execution commit** (block executed and validated; may or may not be canonical) | `ledger/BlockExecution.scala:683-700` (`executeAndCommit`: `saveBatch` … `staged.commitWith(…, Some(withAccessList))`) and `:62-86` (`executeAndValidateBlockFull`, Engine `newPayload`: `commitWith(…, None)`) | put **staged record** `0x01 ++ number ++ hash → record bytes`; update `export.bufferBytes` | Yes: inside the atomic block commit. Adds puts to CF `x`/`s` only; state, receipts, weight, index and pointer puts unchanged. Recorder reads happen before, after validation (`:311` onward). | forge + beacon |
| X1 | both | **Recorder observation** before each `persistState` | `ledger/BlockPreparator.scala:689-693` (per tx), `ledger/BlockExecution.scala:311` (closing), `:882` and `:1082` (system calls) | read `accountsStateTrie.cache.keys`, `contractStorages(_).cache.keys`, `accountCodes.keys`; no write | Yes: inside execution. Pure reads of immutable maps; `None` when export is off. | forge + beacon |
| E1 | ETC | **Extend-best** (block becomes best in its own commit) | `ledger/BlockExecution.scala:683-690` (`saveAsBestBlock = adoptEachBlock = true`, called from `ConsensusImpl.importToTop`, `consensus/ConsensusImpl.scala:242`) | append emission-log entry `block(number, hash)` in the same batch as X0 | Yes (same batch as X0). | forge |
| E2 | ETC | **Reorg adopted** | `consensus/ConsensusImpl.scala:462-477` (`settleHead`, `selectedByWeight && executedWeight > oldBestWeight` → `rewriteCanonicalIndex(put = fork.parentSideChain, remove, newBest)`) | retractions for `displaced` (`:441`) newest-first; then `block` for `fork.parentSideChain` ascending and `executedBlocks` ascending — all `.and`-ed into the `rewriteCanonicalIndex` batch (new `extra` param, `domain/BlockchainWriter.scala`) | Yes: the batch that moves the head. Adds CF `x` puts only; `abandonBlockStates`/`readoptBlockStates` calls (`:475-476`) untouched. | forge + vault (signature) |
| E3 | ETC | **Reorg refused** (REORG-PARTIAL) and **re-presented canonical blocks** | `consensus/ConsensusImpl.scala:478-499`; `reExecuted` partition at `:439-441` | **nothing** (head did not move; re-presented blocks are already exported) | No change; row documents the non-emission. | forge |
| E4 | ETC | **SYNC-FORK rewind** | `blockchain/sync/regular/BlockImporter.scala:94-107` (`rewindCanonicalChain` → `writer.setCanonicalChainHead`, `domain/BlockchainWriter.scala:185-190`) | retractions for `abandoned` newest-first in the `setCanonicalChainHead` batch (new `extra` param) | Yes: moves the pointer down. CF `x` puts only. | forge + vault (signature) |
| E5 | ETC | **Genesis finality** | `Exporter.start` on first start with no `export.position` | append `finality(0, genesisHash)` as `seq 0`; write `export.finality` | No: exporter-internal write. | — |
| H1 | ETH | **Canonical commit** = applied `engine_forkchoiceUpdated` | `consensus/engine/ForkChoiceManager.scala:124-129` (`applyForkChoiceState` → `blockchainWriter.promoteToCanonicalHead(header, reader)`, `domain/BlockchainWriter.scala:251-301`) | retractions for `move.dropped` newest-first (each checked against `export.finality`); `block` entries for canonical heights `min(forkPoint, lastEmitted) + 1 … head.number` via `getCanonicalHashByNumber`; `finality` entry when `finalizedBlockHash` is non-zero and changed — all in the `promoteToCanonicalHead` batch (new `extra` param) | Yes: the batch that sets the head. CF `x`/`s` puts only; `abandon`/`readopt` (`:127-128`) untouched. | beacon + vault (signature) |
| H2 | ETH | **Not a commit**: `engine_newPayload` and regular-sync extend-best | `consensus/engine/EngineApiService.scala:329` (`newPayload` → `executeAndValidateBlockFull`), `ConsensusImpl.importToTop` on a CL-following chain, `settleHead` `!selectedByWeight` arm (`ConsensusImpl.scala:453-460`) | staged record only (X0); **no log entry**; emission happens at H1 | Documents the non-emission; the staged record is the X0 put. | beacon |
| H3 | ETH | **Side payloads abandoned** | `consensus/engine/EngineApiService.scala:617`, `:654` (`abandonBlockStates` on never-canonical payloads); `ConsensusImpl.scala:236-245` (`displaced` in `importToTop`, post-merge only) | **nothing** (never exported, so never retracted) | No change; row documents the non-emission. | beacon |
| F1 | ETH | **Below-finality retraction attempt** | inside H1, before any put | compare each dropped `number` with `export.finality`; at or below → write **no** log entries, log ERROR, set `export.halted` | No change to the index move (Engine API semantics unchanged); export refuses to describe it. | beacon |
| B1 | both | **Bound reached** | inside E1/E2/E4/H1, before the log puts | write no log entries; set `export.halted`; block commit proceeds | No change to the commit. | — |

Signatures changed (all in `domain/BlockchainWriter.scala`, default `extra = emptyBatchUpdate` so every existing caller
compiles unchanged): `promoteToCanonicalHead(head, reader, extra)`, `rewriteCanonicalIndex(put, remove, newBest,
reader, extra)`, `setCanonicalChainHead(target, targetHash, currentBest, extra)`. `vault` reviews these three.

What `forge`/`beacon` are asked to confirm: (1) rows X0/X1 read nothing that execution has not already computed and
write nothing that execution reads; (2) rows E1/E2/E4/H1 add puts to a batch whose existing puts are unchanged and
whose commit point is unchanged; (3) rows E3/H2/H3 are correctly classified as non-emissions; (4) no `family` branch is
introduced inside `BlockExecution`/`BlockPreparator` — the recorder is family-agnostic and the family split lives in
which observer the hook site calls.

## Delivery slices

One PR each; each leaves `main` releasable; slices 0 is phase A, 1–5 are phase C.

| Slice | Content | Owner / reviewers | Gate |
|-------|---------|-------------------|------|
| **0** (phase A) | `contracts/*`, `data-model.md` §1–§5, `export/record/` codec, golden vectors + manifest, `RecordCodecSpec`, `RecordCodecPropertySpec`, ADR INF-010 draft, this GT-2 table signed | main session; **vault** (GT-1 with 018 F2), **forge + beacon** (GT-2 table), **prism** (codec readability) | GT-1, GT-2 |
| **1** | `BlockWriteSetRecorder`, `RecordBuilder`, `Option` threading in `BlockExecution`/`BlockPreparator`, staged-record put at X0, CF `x` + `ExportBufferStorage` (staged half), `export.bufferBytes`; config `enabled` flag; `BlockWriteSetRecorderSpec`; SC-002 pre-measurement with export off vs on-but-no-sink | main; **forge + beacon** (X0/X1), **vault** (CF, batch composition), **eye** | Compile-all; `testOnly *BlockExecution* *BlockPreparator* *WriteSet*`; import p99 within band |
| **2** | Emission log (CF `x` second key space), `CanonicalCommitObserver`, `FinalityWatermark`, the three `extra` params in `BlockchainWriter`, hooks E1/E2/E4/H1/F1/B1, AppState keys, `ExportPosition`; `CanonicalCommitObserverSpec`, `ExportBufferStorageSpec` | main; **forge** (E1–E4), **beacon** (H1–H3, F1), **vault** (signatures, key layout, housekeeping) | `testOnly *ConsensusImpl* *ForkChoiceManager* *BlockImporter* *CanonicalCommit*` |
| **3 (MVP)** | `RecordSink` seam, `FileRecordSink`, `Exporter` drain loop with position-after-ack and halt states, `ReferenceConsumer`, `ReorgFixtures` (five shapes), US1 at unit level (SC-001 on fixtures, both pruning modes), `ExporterSpec`, `FileRecordSinkSpec`, `ExportMetrics` | main; **flow** (drain loop, wake queue, no backpressure into hooks), **forge** (deep ETC reorg semantics in fixtures), **eye** | SC-001 unit-level green; `testEssential` baseline unchanged |
| **4** | `KafkaRecordSink`, `kafka-clients` dependency, topic checks (partition count), health fields on `/healthcheck`, fence/unfence API for 023, `KafkaRecordSinkSpec` (MockProducer); harness scenarios for SC-002/SC-003 (US2) | main; **flow** (ack/timeout handling), **conduit** (health fields), **eye** (harness) | SC-002, SC-003 in the 023 harness |
| **5** | SC-004 archive comparison on a fixed Mordor range and a fixed Platåberget range (US3); OQ-7 figures from the harness into `data-model.md` §8 and `contracts/topic.md`; ADR INF-010 final; docs | main; **eye**; **beacon** (Platåberget range choice) | SC-004 |

Dependencies: 0 → 1 → 2 → 3 → 4 → 5. Slice 3 is the MVP (schema + file sink + reference consumer + US1 at unit
level). Slice 4 needs 023 T-005 (health shape) and T-006 (fence) for the *wiring*; the API is provided here and is
callable without them. Slice 0 lands beside 022 and touches none of its files (`snap/`, `StateStorage`, CF `g`).

## Specialist routing

| Agent | Called for | When |
|-------|-----------|------|
| `forge` | ETC consensus-adjacency of X0/X1/E1–E4; deep-reorg fixture semantics; reading `storageRoot`/`codeHash` into records | GT-2 (slice 0), review of slices 1–3 |
| `beacon` | ETH consensus-adjacency of X0/X1/H1–H3/F1; FCU emission set; Platåberget SC-004 range | GT-2 (slice 0), review of slices 1–2, 5 |
| `vault` | GT-1 (one format with 018 F2); CF `x` key layout, `nsSeq` addition, the three `extra` signatures, batch composition, housekeeping deletes, position `update` vs `updateSync` | slices 0, 1, 2 |
| `flow` | drain loop, `Queue[IO, Unit]` wake, Kafka ack/timeout path, proof that hooks never wait | slices 3, 4 |
| `conduit` | `/healthcheck` entries and their adoption by 023's shape | slice 4 |
| `eye` | per-slice validation: compile-all, `testOnly`, `testEssential` at slice end; harness SC-002/003; SC-004 | every slice |
| `wraith` | compile errors from the `Option` threading or the signature changes | as needed |
| `prism` | codec and exporter readability (non-consensus) | slices 0, 3, 4 |

## Complexity Tracking

| Violation / addition | Why needed | Simpler alternative rejected because |
|----------------------|------------|--------------------------------------|
| New dependency `org.apache.kafka:kafka-clients` (Java, 3.9.x) | D-3: Kafka is the transport (spec Goal 4). | Reimplementing the Kafka protocol is out of the question; fs2-kafka adds a Scala-suffixed dependency to track across 020's compiler moves for combinators a single-partition drain does not use (research R-3). |
| New column family `x` | FR-005: durable buffer in the node's own store, atomic with the block commit. | AppState CF `s` is string-keyed and cannot range-scan a sequence; a map keyed by block number alone cannot express retractions (R-4). |
| Three signature changes in `BlockchainWriter` (`extra` param) | Atomicity of the emission-log entry with the canonical commit (R-4). | A second batch after the commit loses the entry on a crash between them: a gap is a fault (DC-4). |
| `Option[BlockWriteSetRecorder]` threaded through `BlockExecution`/`BlockPreparator` | D-1: per-transaction persists mean the keys must be collected during execution. | Observing `persistState` alone yields per-transaction diffs with the wrong previous values; a trie diff per block is a full-trie walk (R-1). |
| Two key spaces in one CF rather than two CFs | One `nsSeq` entry; one housekeeping sweep; both are owned by one component. | Two CFs double the namespace additions for no isolation benefit (the exporter is the only reader and writer). |

## Cross-spec notes

- **018 (amendment row 2, GT-1).** The exportable changeset part **is** `contracts/record-schema.md` §3 (`accounts`
  + `code`, with the envelope's version byte and `chainKey`). Two readings here need `vault`'s agreement before F2
  freezes: (a) code entries are new-only (code is content-addressed; research R-2); (b) preimages (`address`,
  `slotKey`) ride beside the hashed keys. 018's trie-node undo records stay outside this format. The codec lives in
  `export/record/` with no dependency above `domain`/`rlp`/`crypto`, so 018's `db/` code can import it.
- **018 (row 7, `StateReplay`).** The diff keys 026 applies are exactly `addressHash`, `addressHash ++ slotHash`,
  `codeHash` (`record-schema.md` §3 rule 2); `prevAccount`/`prevValue` give the backward direction. `StateReplay.apply(
  diff, Forward | Backward): IO[Root]` can be typed over `export.record.WriteSet` directly.
- **018 (row 8, Track H demoted).** Confirmed by DC-7: the node's only sinks are the Kafka topic and the test file
  sink; no `ColdStore` write adapter is referenced anywhere in this plan.
- **023.** Health fields are added as `HealthcheckResult` entries named `exportLag`, `exportQueueDepth`,
  `exportBufferBytes`, `exportLastError`, `exportState` so 023 T-005 adopts them unchanged; `Exporter.fence()` /
  `unfence()` are provided for 023 T-006 (fence: drain if the broker is up, `updateSync` the position, stop; a fenced
  process never exports until unfenced). The harness (023 T-007/T-008) runs SC-002/SC-003 with the Kafka sink and
  SC-001 with the file sink. 023 FR-005's durable fence marker is read at start when it exists.
- **024.** `chainKey`, `chainId` and `finalityRule` are read from the ChainSpec when 024 lands; until then
  `fukuii.export.chain-key` defaults to `fukuii.blockchains.network` and the family is `BlockchainConfig`'s network
  type (`utils/BlockchainConfig.scala:330`). The hooks are already split by family (E vs H rows), so `finalityRule`
  only selects which `FinalityWatermark` policy is constructed.
- **026.** Followers need: `record-schema.md` §7 ordering rules (hold out-of-order, FR-008 there), the duplicate rule
  (DC-3), `ReferenceConsumer` as the oracle for GT-1 measurement (026 T-001 uses the file sink from slice 3).
- **017.** The `witness` type is reserved (`type = 3`, `witnessFormat = 0`); production is 017's plan; the codec and
  `fukuii.export.witness.enabled = false` ship here.
- **020.** Slices 1–2 modify `BlockExecution.scala`, `BlockPreparator.scala`, `ConsensusImpl.scala`,
  `ForkChoiceManager.scala`, `BlockImporter.scala`, `BlockchainWriter.scala`; check each against
  `specs/020-scala-3-9-lts-migration/dc3-sites.md` and the PR 5 file list before scheduling (alignment §3, 020).
- **022.** No shared files. The emission log is unrelated to CF `g` (retired by 022 slice 4) and to the invariant
  marker; the staged record is written only on the execution path, which SNAP never takes.
