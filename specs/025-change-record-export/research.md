# Research: Change-Record Export Contract (Kafka)

**Spec**: [spec.md](spec.md) (Clarified 2026-10-31) | **Date**: 2026-10-31 | **Tree**: branch `ccr-6ee15a46-q037bc-3`

Every claim below was read from the tree on this date; line numbers are cited so the next thread can re-check them
after a rebase. Decisions already taken by the spec's Clarifications session are binding and are not re-opened here;
this file records *how* they are realised on today's code and what the alternatives were.

---

## R-1. Where the write set is captured, and how previous values are obtained

**What the code does today.** Execution builds one `InMemoryWorldStateProxy` per block from the parent's state root
(`BlockExecution.buildInitialWorld`, `ledger/BlockExecution.scala:358-385`, root at line 385). Pending writes live in
three places (`ledger/InMemoryWorldStateProxy.scala:184-196`): `accountsStateTrie` (an `InMemorySimpleMapProxy` whose
`cache: Map[Address, Option[Account]]` holds upserts as `Some` and deletions as `None`, `InMemorySimpleMapProxy.scala:
24-46`), `contractStorages` (one such proxy per touched address, keyed `BigInt → BigInt`), and `accountCodes`
(`Map[Address, Code]`). `deleteAccount` writes `None` for the address and drops its storage proxy and code
(`InMemoryWorldStateProxy.scala:219-224`).

**The trap: `persistState` runs after every transaction, not once per block.** `BlockPreparator.scala:689-693` applies
`deleteAccounts → deleteEmptyTouchedAccounts → persistState` per transaction (timed as `ImportProfile.txPersist`,
`vm/ImportProfile.scala:213-216`); the block's closing persist is `BlockExecution.scala:311` (`finalPersist`). Each
`persist()` replaces the proxy with an empty cache over a trie at the new root (`InMemorySimpleMapProxy.scala:53-60`).
So at the end of the block the caches hold only the last transaction's writes, and `inner.get(key)` returns the state
*after the previous transaction*, not the block's pre-state. The alignment review's sentence "previous values are
readable from the same proxies before the commit" (§2) is true per transaction and false per block; the review itself
flagged it as "a design statement to be confirmed by GT-2" (§6). It is confirmed false as stated and corrected here.

**Decision D-1: a `BlockWriteSetRecorder` threaded through execution, keys collected at every persist, values resolved
once at block end against the parent root and the final world.**

1. `BlockExecution.executeBlockUntimed` (`BlockExecution.scala:265-331`) creates `Option[BlockWriteSetRecorder]` when
   export is enabled (the same `Option` threading the EIP-7928 `BlockAccessRecorder` already uses at lines 275 and
   284; the recorder is `None` on every path with export off, so the cost there is one `Option` test).
2. Immediately before each `persistState` call (`BlockPreparator.scala:693`, `BlockExecution.scala:311`, and the
   system-call persists at `BlockExecution.scala:882` and `1082`), the recorder takes the **keys** of the dirty caches:
   `accountsStateTrie.cache.keys`, `contractStorages.map((a, p) => a -> p.cache.keys)`, `accountCodes.keys`. Reading
   `cache` is a field access on an immutable `Map`; nothing is executed or persisted differently.
3. After `worldPersisted` exists (`BlockExecution.scala:311`), the recorder resolves values: for every touched address,
   `prev = parentTrie.get(address)` where `parentTrie = MerklePatriciaTrie[Address, Account](parentHeader.stateRoot,
   staged.storage)(Address.hashedAddressEncoder, Account.accountSerializer)` (the same constructor
   `createProxiedAccountsStateTrie` uses, `InMemoryWorldStateProxy.scala:134-138`; parent nodes are committed, the
   staged storage reads through to them, `db/storage/StateStorage.scala:38-44`) and `new = worldPersisted.getAccount(
   address)`. For every touched slot, `prev` from `EthereumUInt256Mpt.storageMpt(prevAccount.storageRoot, storage).get(
   slot)` (`domain/package.scala:28-32`; `ArbitraryIntegerMpt` when `ethCompatibleStorage` is false) and
   `new = worldPersisted.getStorage(address).load(slot)`. Code: `kec256(code)` → bytes, from the union of
   `accountCodes` snapshots (`persistCode` computes the same hash, `InMemoryWorldStateProxy.scala:87`).
4. **Deleted accounts with storage.** `deleteAccount` drops the storage proxy without enumerating slots, so the key
   collection in step 2 does not see them. When `prev` has a non-empty `storageRoot` and `new` is absent, the recorder
   walks the previous storage trie and records every slot as `prev → absent`. This is what go-ethereum's
   `handleDestruction` does for its destruct set. It is reachable on every ETC fork (ETC has no EIP-6780;
   `vm/EvmConfig.scala:275` defaults `eip6780Enabled = false`, and only ETH forks set it at lines 49 and 242) and on
   ETH only for same-transaction creation-and-destruction, whose storage is tiny. Cost is one storage-trie walk per
   destroyed contract; it is bounded by SC-002 and measured in slice 1.
5. Entries whose `prev` equals `new` (written and restored within the block; empty account created then removed under
   EIP-161) are dropped, exactly as `BlockAccessListBuilder.addAccountChanges` nets them (`ledger/
   BlockAccessListBuilder.scala:62-80`).

A recorder failure must never fail the block: the resolver runs after validation, catches everything, and reports
through the exporter's `exportLastError` → export halts (a missing record is a gap, DC-3), import continues (DC-6).

**Alternatives rejected.**
- *Observer on `persistState` only (alignment review §2 as written).* Gives per-transaction diffs whose previous values
  are the previous transaction's state. Folding them into a block diff needs the first `prev` per key, which is the
  same key bookkeeping as D-1 plus N× the value reads. Rejected: more work and the same seam.
- *Diff of the parent root against the post root by trie walk.* Layout-independent and needs no execution hook, but
  it is a full-trie diff per block (787 M nodes on Sepolia, spec 022). Rejected on cost.
- *`BlockExecution` returning the write set in `BlockResult`.* Clean, but `BlockResult` is consumed by the proposer and
  `eth_call` paths too and the per-transaction persists still need the key collection. The recorder is what the
  return value would have to contain; the `Option` threading is the smaller change.
- *Reading previous values from the `StagedBlockState.pending` batch.* Those are trie nodes and reference counts
  (`StateStorage.scala:26-44`), not semantic values. Rejected: DC-2 forbids trie nodes.

**Why this is consensus-neutral (for GT-2).** Steps 2 and 3 are reads of immutable maps and of committed tries; step 1
is an `Option`; nothing is written to the world and no persisted root changes. The only new write is the record into
the export CF, in the same batch as writes that already exist (R-4). `forge` and `beacon` confirm this on the hook
table in `plan.md`.

## R-2. RLP layout choices

**Decision D-2: one leading schema-version byte, then one RLP list; integers as RLP big-endian minimal; hashes as
32-byte strings; absence as the empty string; grouped slot entries; preimages carried beside hashed keys.**

- The version byte sits **outside** the RLP so a consumer can refuse an unknown version before decoding anything
  (FR-011). `0x01` is this schema.
- The envelope is `[type, chainKey, chainId, blockNumber, blockHash, parentHash, body]` for every type, so a consumer
  reads identity with one decoder (DC-1). `type` is a byte: 0 block, 1 retraction, 2 finality, 3 witness (reserved).
- Reused encoders: `BlockHeader` via `BlockHeaderEnc.toRLPEncodable` (`domain/BlockHeader.scala:250`); receipts via
  `ReceiptCodecs` (`blockchain/sync/codec/ReceiptCodecs.scala:25-71`, the eth/68 bloom-inclusive form; typed receipts
  carry their type byte as the wire form does); `Account` via `AccountEnc.toRLPEncodable` (`domain/Account.scala:24-34`,
  four fields including `storageRoot` and `codeHash`); `UInt256` slot values via `rlpBigIntSerializer`
  (`domain/package.scala:23-26`). No new encoder is written for a type that already has one; the codec tests pin the
  golden bytes so a later change to any of those encoders is caught (SC-005).
- Account entries are `[addressHash, address, prevAccount, newAccount, [slotEntries]]` and slot entries are
  `[slotHash, slotKey, prevValue, newValue]`. The hashed keys (`keccak(address)`, `keccak(pad32(slot))`,
  `Address.hashedAddressEncoder` at `domain/Address.scala:16-17` and `EthereumUInt256Mpt.byteArrayBigIntSerializer`
  at `domain/package.scala:19-21`) are the apply keys (FR-008); the flat-store key `keccak(address) ++ keccak(slot)` is
  the concatenation of the two hashes a consumer already holds, so it is not repeated per slot. The preimages ride
  along because the indexer's balance tables are by address and `eth_getBalance` (SC-004) takes an address; without
  them the record cannot be checked against an archive node. The preimage is 20 + 32 bytes per entry; it is the
  cheapest field in the record and the one every external consumer needs first.
- Code entries are `[codeHash, code]`, new blobs only. Code is content-addressed and immutable, so "previous value" is
  always absent for a hash that appears; a hash that already existed is not listed. Backward application leaves code
  in place (a follower may garbage-collect by reference count; the record does not require it). This is the one place
  the decision text's "each with previous and new value" is read as "prev = absent, implicitly", flagged for GT-1.
- `prevAccount`/`newAccount` is the full `Account` RLP (nonce, balance, storageRoot, codeHash) or the empty string for
  absent. `storageRoot` is consensus data, not a layout detail: a follower on today's layout needs it to verify, a
  follower on 018's flat layout recomputes it and ignores the field.
- Receipts are the node's own full receipts (`BlockData.receipts` as stored by `BlockchainWriter.saveBatch`,
  `domain/BlockchainWriter.scala:37-55`), never the eth/70 partial form (spec edge case).

**Alternatives rejected.** Avro/Protobuf (OQ-3 closed; the indexer re-encodes); a version field inside the list (a
consumer would have to RLP-decode to find it); hashed keys without preimages (unusable by the indexer and unverifiable
against `eth_getBalance`); per-slot flat keys of 64 bytes (redundant with the grouped form, 32 bytes more per slot);
code with explicit `prevCode` (always empty; wasted bytes and a false suggestion that code is mutable).

## R-3. Kafka client choice

**Decision D-3: `org.apache.kafka:kafka-clients` (the Java client), latest 3.9.x at implementation time (3.9.1 as of
this writing), wrapped in `cats.effect.IO` inside the Kafka sink only.**

- Scala 3 compatibility is trivial: a Java artifact has no Scala suffix (constitution, Technology constraints).
- `build.sbt` and `project/Dependencies.scala` have no Kafka dependency today (grep `kafka`, 0 hits); fs2 3.12.2 and
  cats-effect 3.6.3 are present (`project/Dependencies.scala:79-95`).
- What the sink needs is small: one producer, one topic, one partition, strict order, acknowledged sends.
  `KafkaProducer` with `acks=all`, `enable.idempotence=true`, `max.in.flight.requests.per.connection=1` gives that;
  the send callback is lifted with `IO.async_`. The seam (`RecordSink`) hides it, so swapping to fs2-kafka later is a
  one-file change.
- Tests: `org.apache.kafka.clients.producer.MockProducer` ships in the same jar, so the Kafka sink's ordering, retry
  and acknowledgement logic is unit-tested without a broker; a real broker is the 023 harness's job (SC-002, SC-003).
- Compression `zstd` (RLP diffs compress 3–5×); `max.request.size` 16 MiB with a hard refusal and `exportLastError` for
  a record above it (`contracts/topic.md`).

**Alternatives rejected.**
- *fs2-kafka.* Idiomatic `Stream`-based API, but it pins a cats-effect/fs2 version pair, adds a Scala-suffixed
  dependency to track through spec 020's compiler moves, and offers stream combinators the single-partition drain loop
  does not use. Reconsider if a consumer side ever lands in the node (it does not: followers read through 026).
- *Pekko Connectors Kafka (Alpakka).* Pulls Pekko Streams into a cats-effect component; the exporter is an `IO`
  fiber, not a stream graph (`flow` review would be about a graph that does not exist).
- *Confluent client / schema registry.* Avro-only value; OQ-3 chose RLP and the indexer owns registry decisions.

## R-4. Buffer column family and WriteBatch atomicity with the block commit

**What commits where today.**
- ETC extend-best (`ConsensusImpl.importToTop` → `BlockExecution.executeAndCommit`, `BlockExecution.scala:647-705`):
  ONE batch holds the block, receipts, weight, access list, state and, with `adoptEachBlock = true`, the best-block
  pointer (`saveBatch(..., saveAsBestBlock)` at lines 683-690, `BlockchainWriter.saveBatch` at `BlockchainWriter.scala:
  37-55`, committed through `staged.commitWith` at line 698). Here execution commit and canonical commit coincide.
- ETC reorganisation (`ConsensusImpl.reorganise` → `settleHead`, `ConsensusImpl.scala:423-500`): side blocks were
  executed and committed earlier with `adoptEachBlock = false`; the head moves later in `rewriteCanonicalIndex`
  (`ConsensusImpl.scala:467-472`; `BlockchainWriter.scala:199-206`, one batch, commits inside) or
  `saveBestKnownBlocks` (line 454). The execution world is gone by then.
- SYNC-FORK rewind (`BlockImporter.rewindCanonicalChain`, `BlockImporter.scala:94-107` → `setCanonicalChainHead`,
  `BlockchainWriter.scala:185-190`, one batch).
- ETH: `engine_newPayload` executes and commits with no pointer move (`BlockExecution.executeAndValidateBlockFull`,
  lines 54-86: `commitWith(..., None)`); `engine_forkchoiceUpdated` → `ForkChoiceManager.applyForkChoiceState`
  (`consensus/engine/ForkChoiceManager.scala:101-131`) → `BlockchainWriter.promoteToCanonicalHead`
  (`BlockchainWriter.scala:251-301`, one batch returning `CanonicalMove(adopted, dropped)`, line 317).

So "written in the same atomic batch as the block's commit" has to mean two things, because on two of the four paths
the record payload exists at a different moment from the canonical decision.

**Decision D-4: one new column family `x` (`ExportNamespace`) with two key spaces: staged records written at
execution commit, and an emission log written at canonical commit; both always inside the batch that already exists
at that site.**

| Key space | Key | Value | Written in |
| --- | --- | --- | --- |
| Staged record | `0x01 ++ blockNumber(8, BE) ++ blockHash(32)` | the complete `block` record bytes (version byte + RLP) | the execution-commit batch (`executeAndCommit` line 698; `executeAndValidateBlockFull` line 82) |
| Emission log | `0x02 ++ seq(8, BE)` | RLP `[kind, blockNumber, blockHash]`; kind 0 block (resolve to the staged record), 1 retraction, 2 finality | the canonical-commit batch of the family (R-6 names them) |

- The staged record is keyed by block number (the Clarifications' wording) and exists for every executed block,
  canonical or not; a side block that is later adopted (ETC readopt, ETH FCU onto an earlier payload) has its record
  ready. Staged records below `position.blockNumber − buffer.side-block-retention` are deleted by the drain loop;
  a readopted block whose record was already pruned is a gap and halts export with ERROR (it cannot be regenerated
  without re-execution).
- The emission log is what the exporter drains, in `seq` order; it is small (3 fields) so a reorg of depth *d* adds
  *d* retraction entries plus the replacement entries to one batch without touching record bytes.
- `seq` is allocated by the hook from an in-memory counter initialised at start from the log's last key (one
  `seekToLast`); only one writer exists per datadir (023 DC-4, the fence), so no coordination is needed.
- Bounds (FR-005): `exportQueueDepth` = log entries above the position; `exportBufferBytes` = sum of staged record
  sizes, kept as an AppState counter updated in the same batches (`export.bufferBytes`) and recomputed by a scan on
  start. At either bound the hook writes **nothing** new to the log, sets the halted state, and the block still
  commits; the exporter then has a gap it can name (the first un-logged canonical block) and reports it.
- The CF is added to `Namespaces.nsSeq` (`db/storage/Namespaces.scala:33-56`); `RocksDbDataSource` opens with
  `setCreateMissingColumnFamilies(true)` (`db/dataSource/RocksDbDataSource.scala:798`), so an existing datadir gains
  the empty CF on next start with no migration, as the 0.9.0 `j` CF did.
- Adding to the batch: `executeAndCommit` already composes `base.and(storeBlockAccessList(...))`; the staged record is
  one more `.and(exportBufferStorage.putStaged(...))`. `promoteToCanonicalHead`, `rewriteCanonicalIndex` and
  `setCanonicalChainHead` commit internally, so each gains an `extra: DataSourceBatchUpdate` parameter (default empty)
  that is `.and`-ed before `commit()`; `canonicalIndexUpdate` (`BlockchainWriter.scala:208`) is already the
  uncommitted form. `vault` reviews these three signatures and the CF.

**Alternatives rejected.**
- *A single map keyed by block number.* Cannot express "retract 5, retract 4, then block 4', block 5'" (two records at
  one height; retractions carry no payload). Rejected by DC-4.
- *A single append-only log carrying full record bytes.* Loses the record of a side block that becomes canonical
  later, because the payload exists only at execution time. Rejected.
- *Write the log entry in a second batch right after the canonical commit.* A crash between the two loses the
  emission; a gap is a fault (DC-4). Rejected.
- *Reuse the AppState CF `s` for the log.* `s` is a string-keyed `TransactionalKeyValueStorage`
  (`db/storage/AppStateStorage.scala:17-27`); range scans in `seq` order would be string-ordered. Rejected.

## R-5. Position persistence

**Decision D-5: `AppState` key `export.position` holding RLP `[seq, blockNumber, blockHash]`, written with
`update` (async WAL) after the sink acknowledges a drained batch; `updateSync` on `fence` and on orderly shutdown.**

- `AppStateStorage` is the established home of per-datadir cursors (`SnapSyncPivotBlock`, `snap.heal.invariant`,
  `checkpoint.import.inProgress`, `AppStateStorage.scala:620-653`); the key follows the dotted style of the 022 marks.
- Written after ack, never before (FR-004): a crash between ack and write re-sends from the previous position, which
  DC-3 allows. Written with `update`, not `updateSync`, because a lost async write is exactly that re-send and
  `updateSync` in the drain loop would cost 10–100× per batch (`.claude/agent-protocols/storage-rocksdb.md`, Sync vs
  async). `fence` and shutdown use `updateSync` so the position survives a host kill right after.
- Drained staged records and log entries at or below the position are deleted by the drain loop in a separate
  housekeeping batch (`deleteRange` on the log, point deletes on staged records), never in the ack path's critical
  section.
- Resume (restart, `unfence`): read the position, `seekFrom` the log at `seq + 1`, continue. First start with no
  position: write the ETC genesis finality entry as `seq 0` (FR-005/DC-5 for ETC) or wait for the first FCU (ETH),
  then start from the current head (OQ-5: no backfill).

**Alternative rejected:** position as a Kafka committed offset (consumer-group style). The producer has no consumer
group, and the position must be meaningful with the file sink and with the broker down.

## R-6. How canonical commit differs per family, and what each hook emits

**ETC (`finalityRule = genesis-only`, 024 FR-008).** A block is canonical when it becomes best:
- extend-best: the execution batch itself (R-4 first row) — emit `block` for the committed block in that batch.
- reorganisation adopted: `settleHead`'s `selectedByWeight` arm (`ConsensusImpl.scala:462-477`) — retractions for
  `displaced` (the old canonical entries above the fork, newest first), then `block` for `fork.parentSideChain`
  (readopted, ascending; staged records from their earlier execution) and for `executedBlocks` (ascending), all in
  the `rewriteCanonicalIndex` batch.
- reorganisation refused (REORG-PARTIAL, lines 478-499): the head did not move; the side blocks were committed but
  never canonical; nothing is emitted. Blocks re-presented and dropped as duplicates are handled by the same arm's
  `reExecuted` partition (lines 439-441): never retracted.
- the `!selectedByWeight` arm (lines 453-460) is the designated-head path of a chain that follows a consensus layer
  through regular sync; on ETC `followsConsensusLayer` is always false (`ConsensusImpl.scala:223`, and the comment at
  line 188), so this arm never runs on ETC. It is **not** a canonical commit on ETH either (next paragraph).
- SYNC-FORK rewind (`BlockImporter.rewindCanonicalChain`): retractions for `abandoned` newest-first in the
  `setCanonicalChainHead` batch; the replacement blocks arrive through extend-best.
- The finality watermark is one `finality` entry at genesis on the first start with export enabled; it never advances.

**ETH family (`finalityRule = consensus-layer`).** A block is canonical only when an applied `engine_forkchoiceUpdated`
names it `headBlockHash` or an ancestor (spec, Canonical commit). `engine_newPayload` commits state and writes the
staged record, nothing else. Regular-sync imports on ETH (`importToTop` with `adoptEachBlock = true`, the designated-
head arm of `settleHead`) move the best pointer but are **not** canonical commits for the exporter; the CL's next FCU
is. Therefore the ETH hook is exactly one site, `ForkChoiceManager.applyForkChoiceState` after
`promoteToCanonicalHead` (`ForkChoiceManager.scala:124-129`), and it emits:
1. retractions for `move.dropped` newest-first (only entries at or below the last emitted block can have been
   exported; each is checked against the watermark first, FR-007);
2. `block` entries for every canonical index entry from `min(forkPoint, lastEmitted) + 1` to the head, ascending,
   read from `BlockchainReader.getCanonicalHashByNumber` (`domain/BlockchainReader.scala:161`) — not only
   `move.adopted`, because `promoteToCanonicalHead` stops rewriting at the first height at or below the old best block
   whose entry already names the walked block (`BlockchainWriter.scala:251-262`), so blocks that reached the index
   through regular sync between two FCUs are absent from `adopted` and would otherwise never be emitted;
3. a `finality` entry when `finalizedBlockHash` is non-zero and differs from the last emitted watermark (DC-5),
   resolved to a number through `getBlockHeaderByHash` (the unknown-finalized case is already refused at
   `EngineApiService.scala:881-891` before the manager is called).
All three go into the `promoteToCanonicalHead` batch through its new `extra` parameter.

**Below-finality retraction (US-1 scenario 3).** The hook compares each retraction's `blockNumber` with the watermark
read from `export.finality` (AppState). At or below it: log at ERROR, write **no** log entries for this move, set the
halted state with `exportLastError = "retraction below finality: #N hash"`. The index move itself is the Engine API's
business and proceeds; the exporter refuses to describe it. `engine_newPayload`'s `abandonBlockStates` calls
(`EngineApiService.scala:617`, `654`) are side payloads that were never canonical: no retraction.

**ETC side payloads in `importToTop` (`ConsensusImpl.scala:236-245`)** are post-merge only (`followsConsensusLayer`),
were never in the exporter's log, and are not retracted.

## R-7. File sink and reference consumer for tests

**Decision D-7: the file sink writes length-prefixed records to append-only segment files; the reference consumer
is test code that folds records into a flat `Map` state and recomputes roots with the existing `MerklePatriciaTrie`.**

- File format: `u32 BE length ++ record bytes`, repeated; one file per `chainKey` segment
  (`<dir>/<chainKey>/records-<firstSeq>.bin`), rolled at `file.segment-bytes`. A sidecar `position` file is not
  needed: the node's position lives in AppState for both sinks (R-5). Readers detect a truncated tail (crash mid-write)
  by length mismatch and stop there.
- `RecordSink` seam: `publish(records: Chunk[Record]): IO[Unit]` returns after durable acknowledgement (Kafka: the
  callback of the last send; file: `FileChannel.force(true)`), plus `close`. Both implementations live under
  `export/sink/`; nothing outside the package references either (FR-002).
- Reference consumer (`src/test/.../export/ReferenceConsumer.scala`): reads a record source (file segments or an
  in-memory `Seq`), enforces order (gap ⇒ fail, duplicate ⇒ no-op, retraction ⇒ pop and apply backward), keeps
  `accounts: Map[AddressHash, Account]`, `slots: Map[(AddressHash, SlotHash), UInt256]`, `code: Map[CodeHash, Bytes]`,
  and after each block rebuilds the state root over an `EphemDataSource`-backed `MerklePatriciaTrie` from the changed
  keys only (the same `update` the import path uses, 026 note) and compares with `header.stateRoot`; it also recomputes
  the receipts root from the record's receipts against `header.receiptsRoot`. Balances are read by address through the
  preimage field (R-2). It is the oracle for SC-001 and SC-004 (FR-010).

**Alternative rejected:** an in-memory-only sink for tests. The restart-mid-export fixture (FR-009) needs a sink whose
durable state outlives the node instance; the file sink is that and doubles as the offline tool.

## R-8. Fixture construction for deep ETC reorgs

**What exists.** `ReorgBlockhashParitySpec` (`src/test/scala/.../consensus/ReorgBlockhashParitySpec.scala`) drives
the real import path (`ConsensusAdapter → ConsensusImpl → BlockExecution` with the real VM and after-execution checks)
with an `Oracle` that seals blocks at chosen difficulties and a `Node` under test, both `ChainHost extends
EphemBlockchainTestSetup` (lines 205-279). It already produces two-block and multi-block reorgs (`extendCanonical`,
`extendSide`, `invalidChild`) and the `ChainReorganised` result with `oldBranch`/`newBranch`.

**Decision D-8: build the FR-009 fixture set as a reusable `ReorgFixtures` test harness on `ChainHost`, generating
chains deterministically (fixed seed), and replaying them through the real import path with the file sink attached.**

- Two-block ETC reorg: canonical `h1..h10`, side `s9..s10` heavier; expect `[retract h10, retract h9, block s9, block
  s10]`.
- Deep ETC reorg (≥ 100 blocks): canonical `h1..h120` at difficulty 1000, side `s21..s120` at 1100 (heavier by
  weight, selected by `BranchResolution`). The import path's own limits matter: `syncConfig.branchResolutionRequestSize`
  and the `MaxForkAncestryDepth` floor (`BlockImporter.scala:1186-1204`) apply to the fetcher, not to
  `ConsensusAdapter.evaluateBranch`, which the fixture calls directly as the parity spec does, so a 100-block branch is
  one `evaluateBranch` call; `ReorgStateHandler` under `ArchivePruning` ignores abandons, so the fixture also runs once
  under basic pruning to exercise the reference-count path the hooks sit beside. Expect 100 retractions newest-first,
  then 100 blocks, then no finality record beyond genesis.
- ETH above finality: `PostMergeFixture` (TTD 0) with the `ForkChoiceManager` driven directly: `newPayload` two
  branches, FCU to A, FCU to B with `finalized` below the fork; expect retractions and replacements.
- Injected below-finality attempt: same, then an FCU whose head's ancestry excludes the finalized block *while the
  service-level ancestry check is bypassed* (the check at `EngineApiService.scala:888-891` would refuse a correct CL's
  call; the fixture calls `ForkChoiceManager.applyForkChoiceState` directly, which does not check). Expect: no log
  entries, ERROR logged, `exportLastError` set, import of the next block still succeeds.
- Restart mid-export: file sink; export 1..N, stop the exporter after acking N−2 but before the position write (a test
  hook on the drain loop), start a fresh exporter over the same `EphemDataSource`; expect N−1 and N re-sent, the
  consumer's duplicate rule absorbing them.
- Devnet replay (harness tier): the same shapes exported from a two-node Gorgoroth devnet (`conf/gorgoroth.conf`) are
  not needed for SC-001; the unit harness is the independent test. The 023 harness runs the same fixtures against a
  broker for SC-003.

Transactions in the fixtures touch storage (a `SSTORE` contract from the parity spec's `blockhashCall` pattern) and
include one `SELFDESTRUCT` of a contract with ≥ 3 slots on the ETC chain, so the deleted-storage walk (R-1 step 4) is
exercised and its entries verified by the consumer.

## R-9. Buffer bounds and retention (OQ-7, plan-time figures)

Defaults until the 023 harness measures: `buffer.max-blocks = 4096`, `buffer.max-bytes = 4 GiB`,
`buffer.side-block-retention = 1024` blocks; Kafka `retention.ms` 7 days. Rationale: a Sepolia block record is
0.2–2 MB (300–3 000 slot writes × ~110 bytes, receipts included), so 4 GiB covers roughly 2 000–20 000 blocks, well
past 4096 blocks ≈ 13.6 h at 12 s; Mordor records are an order of magnitude smaller. The harness's outage runs set the
per-chain figures and `quickstart.md` records how to read them off the metrics.
