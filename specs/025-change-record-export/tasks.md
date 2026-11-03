---

description: "Task list for spec 025 — Change-Record Export Contract (Kafka)"
---

# Tasks: Change-Record Export Contract (Kafka)

**Input**: Design documents from `specs/025-change-record-export/` — [plan.md](plan.md) (slices, GT-2 table),
[research.md](research.md) (R-1..R-9), [data-model.md](data-model.md), [contracts/](contracts/),
[quickstart.md](quickstart.md).

**Prerequisites**: plan.md, spec.md (user stories), research.md, data-model.md, contracts/.

**Tests**: included (the spec requires golden vectors, property tests, the FR-009 fixture set and the FR-010 reference
consumer). Tests are written first in each slice and must fail before the implementation task that makes them pass.
No `Thread.sleep` anywhere; async code is tested with cats-effect `TestControl` or an explicit wake.

**Organization**: Phase 1 setup, Phase 2 foundational (schema, codecs, vectors, GT-2 table — alignment review phase
A), then one phase per user story (US1 → US2 → US3), then polish. MVP = Phase 2 + Phase 3 (US1 at unit level with
the file sink and the reference consumer).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependencies)
- **[Story]**: US1 (reorg propagates), US2 (broker down), US3 (balances exact); foundational tasks carry no story
- Every description names the exact file path

## Path conventions

Single JVM project. Main code `src/main/scala/com/chipprbots/ethereum/`, tests `src/test/scala/com/chipprbots/
ethereum/`, integration `src/it/scala/com/chipprbots/ethereum/`, resources `src/main/resources/conf/base/` and
`src/test/resources/`. Paths below are relative to the repository root. `E/` abbreviates
`src/main/scala/com/chipprbots/ethereum/`, `T/` abbreviates `src/test/scala/com/chipprbots/ethereum/`.

---

## Phase 1: Setup

**Purpose**: the package skeleton, the config file, the ADR stub. No behaviour.

- [ ] T001 Create package directories `E/export/record/`, `E/export/capture/`, `E/export/buffer/`,
      `E/export/hooks/`, `E/export/sink/` with a `package.scala` doc comment in `E/export/package.scala` naming the
      layering rule (record → capture/buffer/hooks/sink → Exporter; `record/` depends on `domain`, `rlp`, `crypto` only)
- [ ] T002 [P] Add `src/main/resources/conf/base/export.conf` with every key and default of `data-model.md` §8 and
      `include "export.conf"` in `src/main/resources/conf/base/fukuii.conf`; `fukuii.export.enabled = false`
- [ ] T003 [P] Add `E/export/ExportConfig.scala` (case class + `fromConfig(com.typesafe.config.Config)`) and
      `T/export/ExportConfigSpec.scala` asserting the defaults and that `sink` accepts only `kafka` | `file`
- [ ] T004 [P] Draft `docs/adr/infrastructure/INF-010-change-record-export.md` (status Proposed): semantic write set,
      two-key-space buffer, kafka-clients choice; link from `specs/025-change-record-export/plan.md` Constitution VII

---

## Phase 2: Foundational — schema, codecs, golden vectors, GT-2 table (slice 0, phase A)

**Purpose**: the one record format (GT-1) and the signed hook table (GT-2). Blocks every user story.

**⚠️ CRITICAL**: no exporter code (Phases 3+) starts until T022 and T023 are signed.

### Tests first

- [ ] T005 [P] Write `T/export/record/RecordCodecSpec.scala`: reads every `.hex` under
      `src/test/resources/export/golden/v1/`, decodes, re-encodes, compares bytes; checks `manifest.json` fields match
      the decoded envelope; supports `-Dexport.golden.write=true` to regenerate (contracts/golden-vectors.md §4)
- [ ] T006 [P] Write `T/export/record/RecordCodecPropertySpec.scala` (ScalaCheck): arbitrary `Record` of all four
      types, `chainId` up to 2^64, receipts of every type (`LegacyReceipt`, `Type01..Type04Receipt`), round-trip
      `decode(encode(r)) == r`, `encode(decode(b)) == b`, entries sorted by hash, version byte is byte 0, netting
      rejects `prev == new`
- [ ] T007 [P] Write `T/export/record/RecordSchemaRulesSpec.scala`: unknown version byte ⇒ `UnknownSchemaVersion`;
      unknown `type` ⇒ `UnknownRecordType`; a destroyed account (`newAccount` absent, non-empty prev `storageRoot`)
      with an empty `slots` list is rejected by the builder; preimage that does not hash to the key is rejected
- [ ] T008 [P] Write `T/export/record/RecordBodyCodecPropertySpec.scala` (ScalaCheck): arbitrary `transactions`
      (legacy, and typed `0x01`/`0x02`/`0x03`/`0x04` with access lists, blob hashes and authorisation lists), `uncles` (0–2
      headers) and `withdrawals` (0–16 entries, `index`/`validatorIndex`/`amount` up to 2^64): round-trip `decode(encode(x))
      == x` and `encode(decode(b)) == b`; a typed transaction is a single byte string `txType ++ RLP(payload)`, never a bare
      concatenation; `None` and `Some(Nil)` withdrawals both encode as `[]`
- [ ] T009 [P] Write `T/export/record/RecordBodyCompletenessSpec.scala` for `contracts/record-schema.md` §3 rule 5:
      for blocks built by the golden inputs, `keccak(trie(transactions))` equals `header.transactionsRoot`,
      `keccak(RLP(uncles))` equals `header.ommersHash`, the withdrawals root equals `header.withdrawalsRoot` when present
      and `withdrawals` is `[]` when absent; a body with one transaction removed, an uncle swapped or a withdrawal altered
      fails with `BodyRootMismatch`

### Implementation

- [ ] T010 Create `E/export/record/Record.scala`: `enum RecordType(val code: Byte)` (`Block`, `Retraction`,
      `Finality`, `Witness`), `final case class RecordId(chainKey, chainId: BigInt, blockNumber: BigInt, blockHash,
      parentHash)`, `Record(id, body)`, `BlockBody(header, transactions, uncles, withdrawals, receipts, accounts, code)`,
      `AccountEntry`, `SlotEntry`, `CodeEntry`, `WitnessBody(format: Byte, bytes)` per `contracts/record-schema.md`
- [ ] T011 Create `E/domain/statediff/StateDiff.scala`: the semantic diff model `StateDiff(accounts: Map[Address,
      AccountChange], code: Map[CodeHash, ByteString])` with `AccountChange(prev: Option[Account], next:
      Option[Account], slots: Map[UInt256, SlotChange])`, `netted` (drops equal prev/new), `toEntries` (hashes keys,
      sorts) — the one type shared with spec 026 and spec 018 F2: the account entry carries `addressHash`, `address`
      (preimage), `prev`, `next`, `slots`; the slot entry `slotHash`, `slotKey` (preimage), `prev`, `next`; `code`
      entries are new-only. 026 has a task of the same purpose: this one MUST be idempotent (whichever lands first
      creates `domain/statediff/StateDiff.scala`; the other verifies the shape above and adds nothing)
- [ ] T012 Create `E/export/record/RecordCodec.scala`: `encode(record): ByteString` = `0x01 ++ RLP(envelope)` reusing
      `BlockHeaderEnc.toRLPEncodable` (`E/domain/BlockHeader.scala:250`), `ReceiptCodecs`
      (`E/blockchain/sync/codec/ReceiptCodecs.scala`), `AccountEnc` (`E/domain/Account.scala:24`),
      `rlpBigIntSerializer` (`E/domain/package.scala:23`); `decode(bytes): Either[CodecError, Record]`; canonical
      ordering enforced on encode; header/receipts/accounts/code and the envelope here, the body items in T013–T015;
      makes T005–T007 pass
- [ ] T013 Add the `transactions` codec to `E/export/record/RecordCodec.scala` (encode/decode, each item the exact
      block-body form: legacy RLP list, typed `txType ++ RLP(payload)` as one byte string) reusing the existing codec
      (`E/network/p2p/messages/ETHPackets.scala:459-467` `txListItem`, `E/domain/BlockBody.scala:68-89`); makes
      T008 pass for transactions
- [ ] T014 Add the `uncles` codec to `E/export/record/RecordCodec.scala` (`[ RLP(BlockHeader)* ]` via
      `BlockHeaderEnc.toRLPEncodable`, `E/domain/BlockHeader.scala:250`; source `BlockBody.uncleNodesList`,
      `E/domain/BlockBody.scala:15`); makes T008 pass for uncles
- [ ] T015 Add the `withdrawals` codec to `E/export/record/RecordCodec.scala` (`[ index, validatorIndex, address,
      amount ]` via `WithdrawalEnc`, `E/domain/Withdrawal.scala:27`; `None` encodes as `[]`) and the `BodyRootMismatch`
      check of `contracts/record-schema.md` §3 rule 5 used by `RecordBuilder` (T029); makes T008 and T009 pass
- [ ] T016 Generate the vector set into `src/test/resources/export/golden/v1/` (13 `.hex` files + `manifest.json` per
      `contracts/golden-vectors.md` §2, including `block-typed-body-withdrawals.hex` and `block-etc-uncle.hex`) with
      `-Dexport.golden.write=true` from hand-constructed records in `T/export/record/GoldenVectorInputs.scala`; commit
      the files; T005 must pass in compare mode
- [ ] T017 [P] Add the vectors' `manifest.json` schema check to `T/export/record/RecordCodecSpec.scala` (every file in
      the directory has a manifest entry and vice versa)
- [ ] T018 Freeze GT-1: review `contracts/record-schema.md`, `contracts/golden-vectors.md`, `data-model.md` §1–§5 with
      `vault` against spec 018 F2's draft `data-model.md`; record the two readings to agree (code new-only; preimages
      beside keys) and the outcome in `specs/025-change-record-export/plan.md` → Cross-spec notes (018 row 2)
- [ ] T019 SC-005: add `T/export/record/SharedVectorsSpec.scala`: 018 F2's changeset tests and this spec's codec tests
      read the same files under `src/test/resources/export/golden/v1/` (symlink or shared test-resources module, not a
      copy); the spec asserts that 018's configured vector path resolves to those files and compares a SHA-256 of every
      `.hex` against `manifest.json`, so a drifted copy fails; agree the path with `vault` in the T018 review
- [ ] T020 [P] `sbt scalafmtAll` + `sbt "scalafixAll --check"` on `E/export/record/` and `T/export/record/`
- [ ] T021 [P] Run `./scripts/fukuii-test only "*RecordCodec* *RecordSchemaRules*"` — VERIFY line in the PR
- [ ] T022 GT-2 sign-off with `forge`: rows X0, X1, E1–E5 of the hook table in `plan.md`; record date, agent, any
      condition in the table's Status line (no code; the reviewer reads the cited lines on the current tree)
- [ ] T023 GT-2 sign-off with `beacon`: rows X0, X1, H1–H3, F1; same recording
- [ ] T024 Open slice-0 PR: contracts, codec, vectors, ADR draft, signed table; reviewers vault, forge, beacon, prism

**Checkpoint**: one format exists in code and bytes; the hook sites are named and confirmed consensus-neutral.

---

## Phase 3: User Story 1 — A reorg propagates correctly (Priority: P1) 🎯 MVP

**Goal**: records with previous values, staged at execution commit, logged at canonical commit, drained to a file
sink, folded by a reference consumer that equals a clean derivation on every FR-009 fixture (SC-001 at unit level).
Slices 1, 2 and 3 of `plan.md`.

**Independent test**: `./scripts/fukuii-test only "*ReorgFixturesSpec*"` (quickstart US1).

### Slice 1 — write-set capture and staged records

#### Tests first

- [ ] T025 [P] [US1] Write `T/export/capture/BlockWriteSetRecorderSpec.scala`: given a world built on an
      `EphemDataSource` parent state, two transactions touching the same slot (write 5 then 7), one slot cleared, one
      account created then deleted (EIP-161), one contract destroyed with 3 slots, one code deployment — assert the
      block-level `StateDiff`: prev from the parent root, new from the final world, netted, destroyed storage listed,
      code hash = `kec256(code)`
- [ ] T026 [P] [US1] Write `T/export/buffer/ExportBufferStorageSpec.scala` (staged half): `putStaged` key layout
      `0x01 ++ number(8 BE) ++ hash`, `getStaged`, `bufferBytes` counter in the same batch, recount on open
- [ ] T027 [P] [US1] Extend `T/ledger/BlockExecutionSpec.scala` with one case: export enabled ⇒ the commit batch of a
      block contains exactly one CF `x` put whose bytes decode to a block record with `header == block.header`; export
      disabled ⇒ no CF `x` put and no recorder constructed

#### Implementation

- [ ] T028 [US1] Create `E/export/capture/BlockWriteSetRecorder.scala`: `observe(world: InMemoryWorldStateProxy):
      Unit` collects `accountsStateTrie.cache.keys`, `contractStorages.map(_.cache.keys)`, `accountCodes`;
      `resolve(parentRoot, storage: MptStorage, finalWorld, ethCompatibleStorage): Either[Throwable, StateDiff]`
      opens `MerklePatriciaTrie[Address, Account](parentRoot, storage)(Address.hashedAddressEncoder,
      Account.accountSerializer)` and per-account storage tries; walks destroyed storage (research R-1 step 4)
- [ ] T029 [US1] Create `E/export/capture/RecordBuilder.scala`: `(Block, Seq[Receipt], StateDiff, chainKey, chainId)
      → Record` of type Block
- [ ] T030 [US1] Modify `E/ledger/BlockExecution.scala`: add `recorderFactory: () => Option[BlockWriteSetRecorder]`
      constructor param (default `() => None`); in `executeBlockUntimed` (`:265-331`) create the recorder, pass it to
      `executeBlockTransactions`, call `observe` before the closing persist (`:311`) and the system-call persists
      (`:882`, `:1082`), call `resolve` after `worldPersisted`; return the `Either[Throwable, StateDiff]` in
      `BlockResult` as `writeSet: Option[Either[Throwable, StateDiff]]`
- [ ] T031 [US1] Modify `E/ledger/BlockPreparator.scala`: thread `Option[BlockWriteSetRecorder]` into
      `executeTransactions` (`:894`) and `executeTransaction` (`:462`); call `recorder.foreach(_.observe(w))` in the
      composition at `:689-693` immediately before `persistStateFn`
- [ ] T032 [US1] Add `ExportNamespace = IndexedSeq[Byte]('x'.toByte)` to `E/db/storage/Namespaces.scala` and append
      it to `nsSeq` with the 0.9.0-style comment (created on next open, nothing migrated)
- [ ] T033 [US1] Create `E/export/buffer/ExportBufferStorage.scala` (over `DataSource`, CF `x`): `putStaged(number,
      hash, bytes): DataSourceBatchUpdate`, `getStaged`, `deleteStaged`, `scanStagedBelow(number)`; add
      `Keys.ExportBufferBytes` to `E/db/storage/AppStateStorage.scala` with `getExportBufferBytes` and
      `putExportBufferBytes`
- [ ] T034 [US1] Modify `E/ledger/BlockExecution.scala` `executeAndCommit` (`:647-705`) and
      `executeAndValidateBlockFull` (`:54-86`): when `BlockResult.writeSet` is `Some(Right(ws))`, `.and` the staged
      record put and the `bufferBytes` update onto the batch passed to `commitWith`; when `Some(Left(e))`, call
      `exportHalt(e)` (an injected `Throwable => Unit`, default no-op) and commit the block unchanged
- [ ] T035 [US1] Wire in `E/nodebuilder/NodeBuilder.scala`: new `trait ExportBuilder` providing `exportConfig`,
      `exportBufferStorage`, `recorderFactory` (`Some(new BlockWriteSetRecorder)` iff `enabled`); pass into
      `BlockExecution` where it is constructed; `StdNode` mixes it in (`E/nodebuilder/StdNode.scala:438-441`)
- [ ] T036 [US1] `sbt compile-all`; `./scripts/fukuii-test only "*BlockWriteSetRecorder* *ExportBufferStorage*
      *BlockExecution* *BlockPreparator*"`; VERIFY lines
- [ ] T037 [US1] SC-002 pre-measurement: on a Mordor datadir, 500 blocks with `export.enabled = false` vs `true`
      (no sink yet), record p99 import time from `[IMPORT-TIMING]`/`[IMPORT-COMMIT]` lines; write the two figures into
      `research.md` R-1 (a gap beyond the 023 GT-3 band blocks slice 2)
- [ ] T038 [US1] Open slice-1 PR; reviewers forge + beacon (X0/X1), vault (CF, batch), eye

### Slice 2 — emission log, canonical-commit hooks, finality

#### Tests first

- [ ] T039 [P] [US1] Write `T/export/hooks/CanonicalCommitObserverSpec.scala`: given a fake index and log, (a) ETC
      extend-best appends one `block`; (b) ETC reorg with `displaced = [(10,h10),(9,h9)]`, `parentSideChain =
      [(9,s9)]`, executed `[s10]` appends `retract 10, retract 9, block 9(s9), block 10(s10)`; (c) ETH FCU with
      `dropped` and a head 3 above `lastEmitted` appends retractions then blocks 1..3 from the index, then `finality`
      when the hash changed; (d) a dropped block at or below the watermark appends nothing and yields
      `Halted(BelowFinality)`; (e) at `max-blocks` appends nothing and yields `Halted(BoundReached)`
- [ ] T040 [P] [US1] Extend `T/export/buffer/ExportBufferStorageSpec.scala` (log half): `appendLog` key layout
      `0x02 ++ seq(8 BE)`, `scanLogFrom(seq)`, `deleteLogThrough(seq)`, `lastSeq` on open, `queueDepth(position)`
- [ ] T041 [P] [US1] Write `T/export/buffer/ExportPositionSpec.scala`: `export.position` RLP round-trip; absent ⇒
      `None`; `export.finality` and `export.halted` read/write
- [ ] T042 [P] [US1] Extend `T/consensus/ConsensusImplSpec.scala` and `T/consensus/engine/ForkChoiceManagerSpec.scala`
      with one case each asserting that the `rewriteCanonicalIndex` / `promoteToCanonicalHead` batch passed to the
      writer carries the observer's puts (spy the `DataSourceBatchUpdate`), and that `ReorgStateHandler` calls are
      unchanged in order and arguments

#### Implementation

- [ ] T043 [US1] Create `E/export/buffer/EmissionLogEntry.scala` (`enum Kind`, RLP `[kind, number, hash]`) and extend
      `E/export/buffer/ExportBufferStorage.scala` with the log key space (`appendLog`, `scanLogFrom`,
      `deleteLogThrough`, `lastSeq`)
- [ ] T044 [US1] Create `E/export/buffer/ExportPosition.scala` over `AppStateStorage`; add `Keys.ExportPosition`,
      `Keys.ExportFinality`, `Keys.ExportHalted` to `E/db/storage/AppStateStorage.scala`
- [ ] T045 [US1] Modify `E/domain/BlockchainWriter.scala`: add `extra: DataSourceBatchUpdate = appStateStorage.
      emptyBatchUpdate` to `promoteToCanonicalHead` (`:251`), `rewriteCanonicalIndex` (`:199`) and
      `setCanonicalChainHead` (`:185`), `.and(extra)` before each `commit()`; all existing callers unchanged
- [ ] T046 [US1] Create `E/export/hooks/FinalityWatermark.scala`: `current: (BigInt, ByteString)`,
      `refuses(number): Boolean`, `genesisEntry(genesisHash)` (ETC), `entryFor(finalizedHash, reader)` (ETH; `None` when
      zero or unchanged)
- [ ] T047 [US1] Create `E/export/hooks/CanonicalCommitObserver.scala`: `onExtendBest(number, hash):
      DataSourceBatchUpdate`; `onReorg(displaced, readopted, executed)`; `onForkchoice(move: CanonicalMove, head,
      finalizedHash, reader)` computing heights `min(forkPoint, lastEmitted) + 1 .. head` via
      `BlockchainReader.getCanonicalHashByNumber`; `onRewind(abandoned)`; each checks the watermark then the bounds,
      returns the puts or records `Halted(reason)` and returns an empty batch; `tryOffer`s the wake queue after the
      caller's commit (callback)
- [ ] T048 [US1] Hook E1: in `E/ledger/BlockExecution.scala` `executeAndCommit` (`:683-700`), when `adoptEachBlock`
      and the family is ETC (observer injected by `NodeBuilder` as the ETC observer; `None` on ETH), `.and`
      `observer.onExtendBest(number, hash)` onto the same batch as the staged record
- [ ] T049 [US1] Hook E2: in `E/consensus/ConsensusImpl.scala` `settleHead` (`:462-477`) pass
      `extra = observer.onReorg(displaced, fork.parentSideChain, executedBlocks)` into `rewriteCanonicalIndex`; leave
      `:478-499` (REORG-PARTIAL) and `:453-460` untouched (rows E3, H2)
- [ ] T050 [US1] Hook E4: in `E/blockchain/sync/regular/BlockImporter.scala` `rewindCanonicalChain` (`:94-107`)
      pass `extra = observer.onRewind(abandoned)` into `writer.setCanonicalChainHead`; `BlockImporter.apply` gains
      the observer param (default no-op), `NodeBuilder` supplies it
- [ ] T051 [US1] Hook H1 + F1: in `E/consensus/engine/ForkChoiceManager.scala` `applyForkChoiceState` (`:124-129`)
      compute `extra = observer.onForkchoice(...)` before `promoteToCanonicalHead` — the dropped set is derived by the
      observer from the index walk the writer performs, so either (a) split `promoteToCanonicalHead` into a plan
      (`CanonicalMove` without commit) + commit, or (b) have the observer compute `dropped` the same way; choose (a)
      with `vault`, keep the single commit
- [ ] T052 [US1] Hook E5: in `E/export/Exporter.scala` (created in slice 3; stub here) on first start with no
      position and ETC family, append `finality(0, genesisHash)` as `seq 0` and write `export.finality`
- [ ] T053 [US1] Wire `E/nodebuilder/NodeBuilder.scala` `ExportBuilder`: construct `FinalityWatermark` by family
      (`BlockchainConfig` network type, `E/utils/BlockchainConfig.scala:330`), the observer, inject into
      `BlockExecution`, `ConsensusImpl`, `BlockImporter`, `ForkChoiceManager` (`:965`)
- [ ] T054 [US1] `sbt compile-all`; `./scripts/fukuii-test only "*CanonicalCommitObserver* *ExportBufferStorage*
      *ExportPosition* *ConsensusImpl* *ForkChoiceManager* *BlockImporter*"`; VERIFY lines
- [ ] T055 [US1] Open slice-2 PR; reviewers forge (E1–E4), beacon (H1–H3, F1), vault (signatures, keys), eye

### Slice 3 — exporter, file sink, reference consumer, fixtures (MVP)

#### Tests first

- [ ] T056 [P] [US1] Write `T/export/sink/FileRecordSinkSpec.scala`: append N records, read back in order; roll at
      `segment-bytes`; a truncated tail (cut mid-record) is detected and the reader stops before it; `publish` returns
      only after `force(true)`
- [ ] T057 [P] [US1] Write `T/export/ExporterSpec.scala` (cats-effect `TestControl`, no sleeps): drains log entries
      in `seq` order, resolves `block` entries to staged records, calls `publish` in batches of `drain.batch`, writes
      `export.position` only after `publish` returns, deletes drained entries in a separate housekeeping batch;
      restart from a position re-sends `seq + 1 ..`; `Halted` stops the loop and sets `export.halted`; `fence()`
      drains if the sink is up, `updateSync`s the position, sets `Fenced`; `unfence()` resumes
- [ ] T058 [P] [US1] Write `T/export/ReferenceConsumer.scala` (test code, FR-010) and
      `T/export/ReferenceConsumerSpec.scala`: ordering rules of `contracts/record-schema.md` §7 (gap ⇒ fail,
      duplicate ⇒ no-op, retraction ⇒ backward apply), flat maps for accounts/slots/code, `stateRoot` recompute over an
      `EphemDataSource` MPT from changed keys, receipts root recompute, `balance(address, number)`
- [ ] T059 [P] [US1] Write `T/export/fixtures/ReorgFixtures.scala` on `ReorgBlockhashParitySpec`'s `ChainHost`
      pattern (`T/consensus/ReorgBlockhashParitySpec.scala:205-279`; extract `ChainHost`, `Oracle`, `Node` into
      `T/consensus/ReorgChainHost.scala` first so both specs share it): shapes `two-block-etc`, `deep-etc` (120
      canonical, 100-block heavier side), `eth-above-finality`, `eth-below-finality-injected`, `restart-mid-export`;
      transactions include SSTORE writes and one SELFDESTRUCT of a contract with ≥ 3 slots; deterministic seed
- [ ] T060 [P] [US1] Write `T/export/fixtures/ReorgFixturesSpec.scala` (SC-001 unit level): for each shape, under
      `ArchivePruning` and under basic pruning, import through the real path with the file sink, fold with
      `ReferenceConsumer`, assert equality with `CleanDerivation` (a second node importing only the final canonical
      chain) on accounts, slots, code and every `stateRoot`; assert the exact record sequence of quickstart US1;
      assert `exportLastError`/halt for the injected shape and that the next block still imports

#### Implementation

- [ ] T061 [US1] Create `E/export/sink/RecordSink.scala` (seam: `publish(records: fs2.Chunk[(EmissionLogEntry,
      ByteString)]): IO[Unit]`, `close: IO[Unit]`) and `E/export/sink/FileRecordSink.scala` (length-prefixed segments
      under `file.directory/<chainKey>/`, `FileChannel.force(true)`)
- [ ] T062 [US1] Create `E/export/Exporter.scala`: `start: IO[Fiber]`, drain loop over `ExportBufferStorage.
      scanLogFrom(position.seq + 1)`, wake on `Queue[IO, Unit]` (capacity 1) or `drain.idle-poll`, position write
      after ack (`update`), housekeeping (`deleteLogThrough`, `deleteStaged` below `position.number −
      side-block-retention`), states `Running | Halted | Fenced`, `fence`/`unfence`, E5 genesis emission
- [ ] T063 [US1] Create `E/export/ExportMetrics.scala` (`MetricsContainer`; gauges/counters of `data-model.md` §7) and
      update them from `Exporter` and `CanonicalCommitObserver`
- [ ] T064 [US1] Create `E/export/tools/DumpRecords.scala` (`runMain`: segment directory → manifest-style JSON lines)
      used by quickstart US1 step 3
- [ ] T065 [US1] Wire `E/nodebuilder/NodeBuilder.scala` `ExportBuilder`: construct the sink by `sink` config (file now;
      Kafka in slice 4), start `Exporter` after storages open, stop on shutdown (`updateSync` position)
- [ ] T066 [US1] `sbt compile-all`; `./scripts/fukuii-test only "*FileRecordSink* *Exporter* *ReferenceConsumer*
      *ReorgFixtures*"`; then `./scripts/fukuii-test essential` once (baseline 3,621 / 0 must hold); VERIFY lines
- [ ] T067 [US1] Open slice-3 PR (MVP); reviewers flow (drain loop, wake), forge (deep-reorg fixture), prism, eye

**Checkpoint**: US1 acceptance 1–3 hold at unit level on every FR-009 shape; the file sink and reference consumer are
the oracle for 026 GT-1 and for 018 F2.

---

## Phase 4: User Story 2 — The broker is down and nothing is lost (Priority: P1)

**Goal**: the Kafka sink, bound handling, health fields, and SC-002/SC-003 in the 023 harness. Slice 4.

**Independent test**: quickstart US2; unit-level `./scripts/fukuii-test only "*KafkaRecordSink* *Exporter*"`.

### Tests first

- [ ] T068 [P] [US2] Write `T/export/sink/KafkaRecordSinkSpec.scala` with `org.apache.kafka.clients.producer.
      MockProducer`: records are sent in order with key = `blockNumber` (8 BE), headers `schemaVersion`,
      `recordType`, `chainKey`; `publish` completes only when every send in the chunk is acked; a delivery timeout
      fails `publish` and the exporter keeps the position (no advance); a record above `max-request-size` yields
      `Halted(Oversize)`; a topic with 2 partitions at start yields `Halted(TopicShape)`
- [ ] T069 [P] [US2] Extend `T/export/ExporterSpec.scala`: bound reached ⇒ `Halted(BoundReached)`, `exportLag` keeps
      growing, no log entry written, next block commit succeeds (spy the batch); after the sink recovers the loop does
      **not** resume on its own
- [ ] T070 [P] [US2] Write `T/jsonrpc/HealthcheckExportFieldsSpec.scala`: `/healthcheck` carries `exportLag`,
      `exportQueueDepth`, `exportBufferBytes`, `exportLastError`, `exportState` entries with the values of a stubbed
      `Exporter`; absent entirely when `export.enabled = false`

### Implementation

- [ ] T071 [US2] Add `"org.apache.kafka" % "kafka-clients" % "3.9.1"` (latest 3.9.x at merge time) to
      `project/Dependencies.scala` as `val kafka` and to the `node` project in `build.sbt` (`:257-290`); run `sbt
      dependencyCheck`/SBOM step the repo uses; note the licence (Apache-2.0)
- [ ] T072 [US2] Create `E/export/sink/KafkaRecordSink.scala`: `KafkaProducer[Array[Byte], Array[Byte]]` with the
      fixed properties of `contracts/topic.md` §4 plus `fukuii.export.kafka.properties` passthrough (redact `sasl.*`
      and `*password*` in logs); `IO.async_` over the send callback; partition-count check at start via
      `producer.partitionsFor(topic)`
- [ ] T073 [US2] Extend `E/export/Exporter.scala`: `Halted(BoundReached | Oversize | TopicShape | SinkTimeout)`
      reasons; bound checks read `queueDepth` and `bufferBytes` in the observer (`CanonicalCommitObserver` B1 row);
      `exportLastError` string
- [ ] T074 [US2] Modify `E/jsonrpc/NodeJsonRpcHealthChecker.scala` (`:92`): append the five `HealthcheckResult`
      entries when the exporter exists (`ok` while running, `error` with `exportLastError` when halted), names exactly
      as `data-model.md` §7 so 023 T-005 adopts them
- [ ] T075 [US2] Wire the Kafka sink in `E/nodebuilder/NodeBuilder.scala` `ExportBuilder` (`sink = "kafka"`); expose
      `Exporter.fence`/`unfence` through a small `ExportControl` trait for 023 T-006
- [ ] T076 [US2] Add `src/it/scala/com/chipprbots/ethereum/export/ExportOutageIT.scala` (tag `IntegrationTest`):
      the harness scenario of quickstart US2 steps 3–4 against a docker broker (`testcontainers` if present in
      `project/Dependencies.scala`, else the harness's compose); asserts SC-003 both arms
- [ ] T077 [US2] Add the SC-002 arms to the 023 harness scripts (`scripts/fleet-harness measure import-p99 --export
      {off,on} --kafka {up,down}`) or, if 023 T-007 has not landed, a standalone
      `scripts/export-p99.sh` that reads `[IMPORT-TIMING]` lines; record the three p99 figures in `research.md` R-1
- [ ] T078 [US2] `sbt compile-all`; `./scripts/fukuii-test only "*KafkaRecordSink* *Exporter* *HealthcheckExport*"`;
      `sbt "IntegrationTest / testOnly *ExportOutageIT*"` on the harness host; VERIFY lines
- [ ] T079 [US2] Open slice-4 PR; reviewers flow (ack/timeout path), conduit (health), eye (harness)

**Checkpoint**: US2 acceptance 1–2 demonstrated in the harness; import p99 within the GT-3 band on all three arms.

---

## Phase 5: User Story 3 — The indexer derives balances exactly (Priority: P1)

**Goal**: SC-004 on a fixed Mordor range and a fixed Platåberget range against an archive node; OQ-7 figures. Slice 5.

**Independent test**: quickstart US3 (`BalanceAudit` exits 0 on both ranges).

### Tests first

- [ ] T080 [P] [US3] Write `T/export/tools/BalanceAuditSpec.scala`: with a fake archive (`eth_getBalance`,
      `eth_getTransactionCount`, `eth_getCode`, `eth_getStorageAt` stubs) and a record range from `ReorgFixtures`,
      the audit seeds the pre-state for exactly the keys the records name, folds, compares every (block, address), and
      fails on one injected mismatch

### Implementation

- [ ] T081 [US3] Create `E/export/tools/BalanceAudit.scala` (`runMain`): reads file segments or a Kafka topic from a
      position, seeds from an archive JSON-RPC endpoint at `from − 1`, folds with the production codec and a
      main-code port of the consumer's fold (`E/domain/statediff/StateDiff.applyForward/applyBackward`, which 026's
      `StateReplay` v1 reuses), compares balances per block, prints the record-size distribution
- [ ] T082 [US3] Run the audit on Mordor range 9,400,000–9,401,000 (or the current head − 1,000 at run time) against
      an archive reference; record the result and the figures in `research.md` R-9
- [ ] T083 [US3] Run the audit on a Platåberget range chosen with `beacon` (post-Amsterdam blocks with BAL present);
      record the result in `research.md` R-9
- [ ] T084 [US3] Update `data-model.md` §8 defaults and `contracts/topic.md` §3 retention guidance from the measured
      record sizes and the harness outage runs (OQ-7); note the per-chain figures
- [ ] T085 [US3] Open slice-5 PR; reviewers eye, beacon (range), prism (tool code)

**Checkpoint**: US3 acceptance holds on both chains; OQ-7 is closed in the spec by a one-line status edit (by the
spec owner, not this plan).

---

## Phase 6: Polish and cross-cutting

- [ ] T086 [P] Finalise `docs/adr/infrastructure/INF-010-change-record-export.md` (status Accepted) with the measured
      figures and the GT-1/GT-2 outcomes
- [ ] T087 [P] Operator docs: `docs/operations/change-record-export.md` (topic creation, config, health, halt
      recovery, fence interplay) linked from `docs/operations/README.md`
- [ ] T088 [P] Add `export` entries to `.claude/agent-protocols/working-docs/CHASE-QUEUE.md` for anything deferred
      (e.g. the `promoteToCanonicalHead` plan/commit split follow-ups)
- [ ] T089 Coverage check: `sbt testStandard` with coverage; `E/export/` statement coverage ≥ 70% (constitution III)
- [ ] T090 Run `quickstart.md` US1 end to end on a clean checkout and fix any drift between the document and the code
- [ ] T091 `sbt pp` on the final branch before the last PR; VERIFY line

---

## Dependencies and execution order

### Phase dependencies

- **Phase 1 (Setup)**: none; T002–T004 in parallel with T001.
- **Phase 2 (Foundational)**: after Phase 1. T005–T009 (tests) in parallel; T010 → T011 → T012 → T013–T015 (body
  codecs, parallel to each other, same file so serialised in review) → T016 → T017; T018 (GT-1) needs T016; T019
  needs T018; T022/T023 (GT-2) are independent of the codec and can run as soon as the hook table is read; T024
  needs all. **Blocks every later phase.**
- **Phase 3 (US1)**: after Phase 2. Internally slice 1 (T025–T038) → slice 2 (T039–T055) → slice 3 (T056–T067).
- **Phase 4 (US2)**: after slice 3 (needs `Exporter` and the seam). T068–T070 in parallel.
- **Phase 5 (US3)**: after slice 3 for the fixture-based test (T080) and after slice 4 for the Kafka reading path
  of T081; the archive runs (T082, T083) need synced nodes.
- **Phase 6**: after Phases 3–5.

### Cross-spec dependencies

- Slice 0 is phase A (no code conflicts with 022). Slices 1–5 are phase C: after 020 PR 5–7 and 023 T-002/T-003
  (alignment review §4); check the modified files against `specs/020-scala-3-9-lts-migration/dc3-sites.md`.
- T074/T075 provide what 023 T-005/T-006 consume; they do not wait for them.
- T018 must complete before 018 F2 freezes its `data-model.md`.

### Within each slice

- Tests first; they fail; implementation makes them pass; `sbt compile-all` after every file; `testOnly` after each
  logic change; `testEssential` once at slice end (`.claude/agent-protocols/testing-protocol.md`).
- Core-domain sweep rule does not apply (no `BlockHeader`/`Account`/`Block`/`Transaction` edits).

## Parallel examples

```text
# Phase 2, codec tests (different files, no dependencies):
T005 RecordCodecSpec.scala | T006 RecordCodecPropertySpec.scala | T007 RecordSchemaRulesSpec.scala
T008 RecordBodyCodecPropertySpec.scala | T009 RecordBodyCompletenessSpec.scala
# Phase 2, gates in parallel with codec work:
T022 forge GT-2 rows | T023 beacon GT-2 rows | T018 vault GT-1 (after T016)

# Slice 1 tests:
T025 BlockWriteSetRecorderSpec | T026 ExportBufferStorageSpec (staged) | T027 BlockExecutionSpec case

# Slice 2 tests:
T039 CanonicalCommitObserverSpec | T040 ExportBufferStorageSpec (log) | T041 ExportPositionSpec | T042 spies

# Slice 3 tests and fixtures:
T056 FileRecordSinkSpec | T057 ExporterSpec | T058 ReferenceConsumer(+Spec) | T059 ReorgFixtures | T060 ReorgFixturesSpec

# Slice 4 tests:
T068 KafkaRecordSinkSpec | T069 ExporterSpec (bounds) | T070 HealthcheckExportFieldsSpec
```

## Implementation strategy

### MVP first (Phases 1–3)

1. Phase 1, Phase 2 (GT-1 and GT-2 signed; vectors committed).
2. Slice 1: records exist in CF `x` for every executed block; import p99 unchanged.
3. Slice 2: canonical commits and reorgs are logged atomically; below-finality refused.
4. Slice 3: file sink + exporter + reference consumer; **stop and validate** SC-001 on all five fixtures.

### Incremental delivery

5. Slice 4 (US2): Kafka, bounds, health, harness SC-002/SC-003.
6. Slice 5 (US3): archive audit on Mordor and Platåberget; OQ-7 closed.
7. Polish.

### Task counts

| Phase | Tasks | Story |
|-------|-------|-------|
| 1 Setup | T001–T004 (4) | — |
| 2 Foundational | T005–T024 (20) | — |
| 3 US1 | T025–T067 (43) | US1 |
| 4 US2 | T068–T079 (12) | US2 |
| 5 US3 | T080–T085 (6) | US3 |
| 6 Polish | T086–T091 (6) | — |
| **Total** | **91** | |

## Notes

- `[P]` = different files, no dependency on an unfinished task.
- Every consensus-adjacent task (T030, T031, T034, T048–T051) is reviewed by `forge`/`beacon` per the GT-2 table
  before merge; none changes a root, gas or hash.
- Never `git add .`; add the files each task names.
- A task that cannot be completed as written is re-planned in `plan.md`, not silently narrowed.

## Requirement coverage

Every FR and SC of `spec.md` maps to at least one implementing and one test task (alignment analysis F10).

| Requirement | Implementing tasks | Test tasks |
|-------------|--------------------|------------|
| FR-001 block record per canonical commit, retractions, finality | T028, T029, T030, T034, T043, T046, T047, T048–T052 | T025, T027, T039, T042, T060 |
| FR-002 exporter seam, Kafka and file implementations | T061, T062, T072, T075 | T056, T068 |
| FR-003 one topic per chainKey, single partition, time-based retention | T072, T084 | T068 |
| FR-004 export position persisted after ack, resume point | T044, T062 | T041, T057 |
| FR-005 durable export buffer with bound, import never blocked | T032, T033, T045, T073 | T026, T040, T063, T076 |
| FR-006 lag, queue depth, buffer bytes, last error as metrics and health | T063, T074 | T070 |
| FR-007 retractions newest-first, never below finality | T047, T049, T050, T051 | T039, T042, T059, T060 |
| FR-008 semantic state diff keyed by hashed address and slot, code as dependency | T011, T028, T029 | T025, T007, T016 |
| FR-009 reorg fixture set | T059 | T060 |
| FR-010 reference consumer | T058 | T060, T080 |
| FR-011 schema version first, unknown version stops | T012 | T007, T005 |
| FR-012 reserved `witness` record type, off by default | T003, T010, T012 | T006, T007, T016 |
| SC-001 reference consumer equals clean derivation on the fixtures | T058, T059, T067 | T060 |
| SC-002 import p99 unchanged with exporter on, off, broker down | T037, T077 | T037, T076, T077 |
| SC-003 no gap, no out-of-order record after an outage | T073, T076 | T076, T063 |
| SC-004 folded balances equal archive `eth_getBalance` on Mordor and Platåberget | T081 | T080, T082, T083 |
| SC-005 one golden-bytes vector set shared with 018 as identical files | T016, T018, T019 | T005, T017, T019 |
