# Spec 018 Track C handoff: after C2b and C3b (cloud session → whoever takes C3c or C4a), 2026-11-11

**From** `cloud-dc960fdc`, a cloud session whose container and `.local/` do not outlive it, which is why this is a
tracked file and not a continuation under `.local/docs/continuations/`. **To** the session that claims B-056 (C2b) or
B-058 (C3b). It replaces the handoff written after C1.

## Status

C1 (B-054) merged as #1788. **C2a (B-055) merged as two PRs**, #1791 and #1792.

- **Part 1**: the six wrapping `MptStorage` implementors are on `MptStorageDecorator` (T041), every trie of the state
  names its owner (T046: execution, genesis, the BAL prefetch), and real blocks ran through a position mirror (T026,
  the EEST half).
- **Part 2**: the read side of the position store, `db/storage/pathstate`, behind `fukuii.state.path-live` (T035 to
  T038, T042 to T044, T047, T048; T045 in part).

**C3a (B-057) merged as #1793**: the changeset record and its codec, `ChangesetStorage` on the new column family `v`,
and `StateConfig`. forge and beacon signed the record's bytes. Nothing reads or writes it yet.

**C2b (B-056) is done**, merged as four PRs: #1796 (the ratchet), #1797 (the RPC readers), #1798 (SNAP serving),
#1799 (walking). Its pre-code record, with what conduit, herald and beacon changed, is in plan.md.

- **The ratchet is done** (T054, T062): `scripts/check-state-accessors.sh` with `scripts/state-accessor-allowlist.txt`,
  142 lines, each with its reason and the slice that removes it. **A slice that converts a caller lowers that line's
  count or removes the line in the same change**, or the Test and Build job fails before it compiles. The allow-list's
  sections are the work list of the next two PRs: "Readers behind RPC" and "Serving and walking".
- **The RPC readers are done** (T051, T053, T056, T057): the seam `BlockchainReader.stateAt(header)` /
  `scratchStateAt(header)`, header-taking account reads, storage reads under the account, `StateRead` for the retry.
  plan.md, "C2b RPC readers record", has what an end-to-end run on the execution-apis chain found: **the header a call
  runs under is not its block's header**, so a state is always asked for by the block's own header
  (`StxLedger.callWorld(stateOf, executing)`). The same trap waits for anyone who hands an execution header to the seam.
- **SNAP serving is done** (T052, T058): the server reads every node at its position, the handlers ask
  `BlockchainReader.stateForRoot(root)` per request, a serve that meets a `StateFault` answers with the empty response,
  `snap/1` is not advertised under the guard, and the unreachable `GetNodeData` handler is deleted. plan.md, "C2b SNAP
  serving record". **With the guard on, set `sync.do-snap-sync = false`**: the node refuses to start otherwise, until
  C4a advertises SNAP again. The node's storages now take the guard from the instance's configuration
  (`InstanceConfig.statePathLive`), not from `StateLayoutConfig`, which is left for storages built outside a node. **A reader that holds a storage across many reads and turns a failed read into "absent" has the same
  defect the server had**: look for `catch case _: Throwable` around a `get` in every walker of the fourth PR.
  `PathStateTestKit.StaleAfter` is a storage that goes stale after a given number of reads, for exactly that test.
- **Walking is done** (T055, T059, T060, T061): the recovery scans, the state validator, the shard enumerator, the
  leaf walk and the checkpoint export read every node at its position. plan.md, "C2b walkers record". What each walk
  answers on a hash store was frozen before it was touched (`test/resources/spec018/walker-verdicts-hash-store.txt`).
  **`MptStorage.nodesSharedAcrossTries`** is what a walk asks before it skips a storage trie for having seen its root:
  false on the position store, where two accounts with one root hold two tries (spec 022 V3). The scans and the
  validator are still HANDED SNAP's own store (`getBackingStorage(pivot)`, `getOrCreateMptStorage(pivot)`): there is no
  anchor to take a view of before the hand-off, so where their store comes from is C5a's.
- The decisions follow-up merged as #1795, the ratchet as #1796.

**C3b (B-058) is written**, in two PRs (plan.md, "C3b storage record" and "C3b wiring record").

- **Storage** (#1800): the world carries the storage tries it has replaced and tells its storage at `persistState`;
  `StateLock`; `StagedChanges` and the staged mode of `PathMptStorage` (one `Change(prior, next)` for each position a
  block touches, one line of worlds to a layer); `PathStateStorage.stage(parent)` and
  `commitCanonical(staged, block, extra)`, one batch under the lock as a move of the clock; `RetentionPolicy` with the
  prune count in AppState; `StateMetrics`.
- **Wiring**: `PathStagedBlock` (the `StagedBlockState` of the unified layout, its layer made at first use),
  `stageBlockState(header)`, and what the guard is for until the overlay: **a block is committed in place or not at
  all**. A block that is not adopted as it is executed, a payload's state committed apart from the block, an
  execution with nothing staged and a `forkchoiceUpdated` to another head are each refused before anything runs; the
  node does not start network sync on the guard, and the guard is refused at start with SNAP sync, mining,
  `import-chain-file` and test mode.
- **The differential** that stands in for T075: `ImportOnPositionsSpec` (the execution-apis chain),
  `MordorImportOnPositionsSpec` (256 real Mordor blocks under the proof-of-work validators) and
  `EestImportOnPositionsSpec` (fixtures; `EEST_IMPORT_POSITIONS=<dir>` runs a whole corpus:
  `scripts/eest/fetch_fixtures.py` fetches one). Each imports through `executeAndValidateBlocks` and holds the disk to
  each block's state.

**The user decided four things on 2026-11-11** (plan.md, "Decisions of 2026-11-11"): C3a merges; 512 blocks of
changesets where there is no finalized block; one downgrade barrier; no `snap1` advertisement under the guard until the
overlay.

## Files modified

| File | Status | Last action |
|------|--------|-------------|
| `main/db/storage/DecodedNodeCache.scala`, `PrefetchNodeReader.scala` | DONE | on the decorator; the prefetch reader shares one budget, one count and one stop across owner views |
| `main/ledger/InMemoryWorldStateProxy.scala`, `BalPrefetcher.scala`, `StxLedger.scala`, `main/blockchain/data/GenesisDataLoader.scala`, `main/jsonrpc/EthSimulateService.scala` | DONE | owner threading; `deleteAccount` calls `resetOwner` |
| `main/db/storage/pathstate/` (`StateExceptions`, `PositionKey`, `StateView`, `PathMptStorage`, `PathStateStorage`, `PathPrefetchReader`) | DONE (new) | the view of the anchor; a by-hash throwaway layer; no write reaches a row |
| `main/db/storage/PathNodeStorage.scala`, `AppStateStorage.scala`, `main/db/components/Storages.scala` | DONE | `read(PositionKey)`; `state.anchor`; `pathStateStorage` behind the guard |
| `main/ledger/InMemoryWorldStateProxy.scala` | DONE (C3b) | `storageResets`, `initialiseAccount`, the reset at `persistState` |
| `main/db/storage/pathstate/` (`StateLock`, `StagedChanges`, `PathStagedState`, `RetentionPolicy`, `StateMetrics`; `PathStateStorage`, `PathMptStorage`, `BlockDiffCodec`) | DONE (C3b part 1) | the staged layer and the canonical commit; `storageRows`, `rowUpdates` on `PathNodeStorage`; `state.changeset.pruned-to` |
| `main/db/storage/pathstate/PathStagedBlock.scala`, `main/domain/Blockchain.scala`, `main/ledger/BlockExecution.scala` | DONE (C3b part 2) | staging by header; the refusals of what cannot be committed in place |
| `main/consensus/engine/EngineApiService.scala`, `main/nodebuilder/StdNode.scala`, `main/ConfigValidator.scala`, `main/blockchain/data/ChainImporter.scala` | DONE (C3b part 2) | the guard's scope: SYNCING for another head or a payload request, no network sync, the start-up refusals, `holdsStateOf` |
| `test/mpt/PositionTestKit.scala`, `test/db/storage/pathstate/PathStateTestKit.scala` | DONE | `PositionMirror`, `OwnerPositionStore`, `RawTrieWalk.statePositions`; a state on both stores, on Ephem and RocksDB |

## Open compile errors

`sbt compile-all`: 0 errors. `sbt formatCheck`: passes.

## Next action (first thing to do)

Read plan.md, "C2a part 1 record" and "C2a part 2 record", then:

- **C2b is complete once its four PRs have merged** (then `backlog.py done B-056`). Three models for a differential,
  all in the tree: `test/jsonrpc/conformance/RpcReadersOnPositionsSpec.scala` (one real chain, its state copied into
  the position store of the same datadir with `PathStateTestKit.seedPositions`, two nodes, same questions);
  `test/network/snapserver/SnapServePositionStoreSpec.scala` (one state held two ways, and a second with chosen keys:
  **hashed keys give no long extension, and a path is miscounted through an extension**); and
  `test/blockchain/sync/RecoveryScannersPositionSpec.scala` (**freeze the hash-store answers before converting**, then
  hold both stores to them). `PathStateTestKit` has the pieces: `ChosenState`, `TwoWays` (the same nodes lost from both
  stores), `Lacking`, `StaleAfter`.
- **C3c part 1, the genesis commit, is written** (plan.md, "C3c genesis record"): `GenesisDataLoader.over(reader,
  writer, storages)` commits the genesis of an empty datadir to the unified layout, and refuses a datadir of the
  other layout either way (`layoutRefusal`: move it to `StateLayoutGuard` in C5a). A spec that needs a path-live
  datadir starts from it, not from `seedPositions`, unless its genesis header is not the loader's (the fixture replay).
- **C3c part 2, the proposer view, is written** (plan.md, "C3c proposer view record"): a block is built on
  `BlockchainReader.proposerStateOn(parent)`, a throwaway layer over a view; `forkchoiceUpdated` builds a payload;
  `testing_commitBlockV1` commits by the adopting import; pending is the head. **No node on the guard proposes and
  follows yet**: a mined block has no route to import without network sync, and `newPayload` adopts nothing before
  the overlay. The `mining.mining-enabled` refusal stays until C4b, and `eth_getWork` / `eth_submitWork` refuse
  with it. A spec that builds on positions starts from `ProposerOnPositionsSpec`'s `Rig` or
  `MinedBlockOnPositionsSpec`'s `Node`.
- **C3c part 3 is the linear subset** (user, 2026-11-12; plan.md, "C3c part 3"): side blocks, rewinds and the
  fork-choice harness wait for C4a and C4b. **3a, the lenient chain import, is written**: on the unified layout
  `importChainFile` takes a block built on the head through `executeAndAdoptBlockIf` and refuses any other;
  `import-chain-file` is no longer refused at start.
- **3b, the fixture replays, is written** (plan.md, "C3c part 3b"): both replays start from the node's genesis
  commit under the fixture's own header (`EestBlockchainReplay.loadFixtureGenesis`, by the loader's protected
  `prepareHeader`), the builder replay has a run on the unified layout that imports what it built, and
  `HarnessCanarySpec` holds that a replay on positions is on positions. A new harness on the unified layout ends on
  `EestBlockchainReplay.notOnPositions(env)`.
- **3c, test mode, is written** (plan.md, "C3c part 3c"): `test_setChainParams` removes the blocks of the chain
  before from the head down, destroys its state (`PathStateStorage.destroyStateForNewGenesis`: one synced batch,
  allowed only on a store built for a node in test mode) and commits the genesis through the node's loader; a
  heavier branch and a rewind between the genesis and the head are answered with an error and write nothing; a
  rewind to the genesis sets the chain again. A spec of test mode starts from `TestServiceOnPositionsSpec`'s `Rig`
  (a `TestModeNode` in memory, on either layout). **Two faults of test mode were fixed on both layouts**: the world
  stayed a test-mode world for the first transaction of a block alone (so `debug_storageRangeAt` missed later
  slots), and `test_mineBlocks(n)` mined one block and n - 1 siblings.
- **C3c is complete in its linear subset.** What it left is B-219 (mining: no route to import without network sync)
  and B-220 (side blocks in the lenient import and in test mode, `test_rewindToBlock` below the head, the three
  harnesses under `it/ethtest`, `newPayload` adopting nothing).
- **C4a is four PRs** (plan.md, "C4a part 1"): forward layers, the reverse index, the bounds, the node's use of
  them, in that order. **Part 1, forward layers, is written**: `ForwardLayer`, `StateOverlay`, a `StateView` that
  reads through layers, `PathStateStorage.commitOverlay` and `promote`, `LayerInconsistent`. **A layer holds what
  its block left, the hash of what it found, and the owners of the tries it replaced; it is settled against the
  disk when it is promoted** (`StagedChanges.settle`), so it does not grow with a trie an ETC SELFDESTRUCT removes
  (forge). Keep it so: nothing may put the rows of a replaced trie, or a prior's bytes, into a layer. No view lists
  a trie's rows (`StateView.ownerRows` was built and removed). Nothing in the node makes a layer, and nothing may
  before part 3's bounds. A spec of layers starts from `ForwardLayerSpec`'s `Chain` and its `sameAsInPlace`. The
  degrade of a staged commit to an overlay commit is NOT built: it lands with its callers in part 4.
- **C4a part 2, the reverse index, is written** (plan.md, "C4a part 2"): `ReverseIndex.between(anchor, fork)` from
  the anchor's own records, `AncestorWindow`, a `StateView` that reads layers, then the index, then the rows, and
  `PathStateStorage.viewOf` / `stageOf` for a block behind the anchor or on a fork (package-private until part 4).
  A fork's layers live while its fork point is led back to. **The invariant**: the index's view of a block is the
  disk after the blocks above it are reversed by their records, at every position, leftovers included; C4b's move
  back writes priors exactly, and a layer promoted after it is settled against that disk. Before part 4: the window's first build off the state lock, and the RocksDB cases. A spec of these views
  starts from `ReverseIndexSpec` and the shared `LayerChains`.
- **C4a part 3, the bounds, is written** (plan.md, "C4a part 3"): the layers no branch pins are bounded in number
  and bytes and evicted least recently used, tips only; a branch under execution is pinned
  (`PathStateStorage.withPinnedBranch`, `commitOverlay(staged, block, Some(pin))`) and bounded by
  `pinned-max-bytes`, above which it is refused with `BranchTooLarge`. `commitOverlay` answers `Kept`, `NotKept`,
  `AlreadyApplied` or `BehindTheAnchor`: **`NotKept` is not a fault and not a verdict on the block**, and `Kept` is as
  of now. A caller that adopts a pinned branch promotes first and releases after. A spec of the bounds starts from
  `OverlayBudgetSpec`, where every block changes one account so that a bound in bytes is a count of layers.
- **C4a part 3c is written** (plan.md, "C4a part 3c"): `EpochOrderingSpec` (the writer's order, readers at every
  step of a move), `OverlayStressSpec` (10,000 seeded steps; change the store's isolation and run it first: it reads
  rows as well as nodes, and holds two invariants), and M3 (`baselines/m3-layer-heap.md`). **The two decisions part
  4 waited on are taken (user, 2026-11-12; plan.md, "C4a part 3")**: a heavier branch above `pinned-max-bytes` is
  adopted by reverse-then-commit, with the disk moved back by the records if it fails (it lands with C4b's head
  move; until then such a branch is deferred, never judged); and the overlay's bounds stay, with the blocks whose
  state is not held executed again at the `forkchoiceUpdated`.
- **C4a part 4 is the readers, not the consensus callers** (plan.md, "C4a part 4"). The served-root window is
  built: `BlockchainReader.stateForRoot` hands out the state of any canonical block within 128 of the best block,
  through `PathStateStorage.readOnlyBehind`, never an executed block kept beside the chain. **SNAP is still not
  advertised under the guard**: it is advertised again when the node follows the head (C4d, C4e), and the refusal
  of `do-snap-sync` goes with the hand-off (C5a). A spec that needs a chain as a node holds one (headers, index,
  records, best block, the reader) starts from `CanonicalChainOnPositions`. Next in part 4: JSON-RPC reads at a
  block behind the head. The overlay commit of a block that is executed and not adopted (`PathStagedBlock`,
  `BlockExecution.refuseUnadopted`) lands with its first caller, in C4d or C4e.
- **C4a part 4b-1: a reader behind the head is bounded in what it costs** (plan.md, "C4a part 4b-1"). A reader's
  view is made with no lock held and never by the lock's holder; an index is built once for those who ask together;
  `readOnlyBehind(block, Reader.Peer | Reader.Rpc)` builds two indexes at once per kind and refuses a third with
  `StateUnavailable` ("Ask again"); the kept indexes are `ReverseIndexCache`'s, four an execution's and four a
  reader's. The reach is `retention.max-blocks` (basic) or `archive.view-reach` (archive). **4b-2 must bound how
  many reads behind the head run at once at its own seam** (an index that is not kept lives as long as its views),
  and treat a refusal and a stale view alike. A spec that needs a thread stopped inside a read uses
  `ReaderIndexBuildSpec`'s `Stop`; it waits on `indexBuildsWaitedFor`, never on a thread's state.
- **C4a part 4b-2: the JSON-RPC and GraphQL read behind the head, in a scope** (plan.md, "C4a part 4b-2").
  `BlockchainReader.stateAt` and `scratchStateAt` hand out the head's state to anyone and a block behind it only
  inside `ReadScope.run` (`StateRead.at`, `scratchAt`, `attempting`, `reading`, `scoped`): four such reads in flight,
  a fifth told to ask again. **A new caller that takes a state and is in no scope reads the head only**: wrap the
  synchronous body that takes the state and reads through it, never an `IO` that hops threads, and one scope a
  block for a range. Three RPC errors: `JsonRpcError.StateNotHeld`, `StateBusy`, `StateMoved`. The chain imported
  on the unified layout and compared with the hash store behind its head is `RpcReadersBehindHeadSpec`: start
  there for any reader of a block behind the head. Forward layers by hash over RPC, `requireCanonical` and
  `BLOCKHASH` by a block's own ancestry are C4d's.
- **C4b is planned in plan.md, "C4b: the move core, as it is to be built"**, after forge's and beacon's consults:
  read that before data-model 7.0 and 7.3, which it corrects (the move takes the caller's rows and hands out
  nothing; a move of several blocks is settled against a virtual disk; `Deep` by the records that are there; a
  `Finalized` refusal; the chunk invariant). **Part 0 is built**: a `StateFault` met in a batch is
  `BlockExecutionError.StateFaulted` in the batch's result, `ConsensusStateFault` and `BlockImportStateFault`
  above it, never a failed import; `saveLastBlock` writes nothing on the unified layout. A new consumer of a
  batch's result must take the fault out before it judges a block (`BlockExecution.BatchOutcome.of`). The chain
  on the unified layout driven through the node's own consensus, with a second writer, is
  `StateFaultInABatchSpec`. **Part 1 is built**: `HeadMovePlan.of` (pure) and `PathStateStorage.planMove`, with
  the refusals `Deep`, `Incomplete`, `Pivot`, `Finalized` (number and hash) and `Corrupt`; `state.origin` in
  AppState, written by the genesis commit; `CommitSettings.finalized` names a `FinalizedBlock`. Nothing calls
  the plan yet. The specs' model of blocks that are only headers, records' headers and empty layers is
  `PlannedMoves`. **Part 2a is built**: `PathStateStorage.moveHead(target, link, extraFor)` and `MoveBatch` (a
  batch built against the disk plus the batch so far, every step held to it before anything is written);
  `promote` is that batch for one block; a commit waits for a move that is under way (`adopting`); a store that
  failed its read-back gives out no state. The spec is `HeadMoveSpec`, whose oracle is a second chain that
  commits the same blocks in place. **Part 2b is built**: `BlockchainWriter.canonicalHeadUpdate` (the head's
  index rows, built apart from their commit) is what a caller passes as `extraFor`, and `HeadMoveOnTheChainSpec`
  drives that on the execution-apis chain on the unified layout. The plan's layers are pinned for the move; the
  move's metrics are told. **Part 2c is built**: batches cut by bytes, the barrier move (and the log's byte budget),
  and `withRecordsHeld` for C4e's reverse-then-commit. **Part 3a is built**: on the unified layout the move and a
  block's own commit are the only writers of the best block and the index; every other writer refuses there (or is
  PRE/START), the best block is held to the anchor under the state lock, and `scripts/check-head-writers.sh` holds
  the table (a NEW writer of the head must say in `scripts/head-writer-allowlist.txt` which way it stays safe).
  Part 3b routes the fork choice and test mode's rewind through `moveHead`.
- **C4a** brings back what C3b refuses: the overlay commit for a block that does not extend the anchor
  (`buildExtra(adopted)`, `NotExtendingAnchor`, `saveLastBlock` writing nothing),
  `stateAvailable(hash)`, the served-root window, and SNAP advertised again. C4b: the head move, `ConsensusStateFault`
  (T148), the refusal of a reverse across a tombstone. C4d: `newPayload` on a forward layer, `forkchoiceUpdated`
  moving the head (its gate today sits before the canonical-ancestor branch: beacon), the consensus client's
  finalized block for retention (`finalized` is wired as none in `Storages`).
- **From the reviews, for whoever touches these**: only import may use `stage`, and a layer refuses a world that does
  not follow the last one persisted on it; a decorator that drops the trie's removals (`StxLedger`,
  `EthSimulateService`, `GenesisDataLoader`) must never wrap the staged storage; `newPayload`'s `extra` must carry no
  best-block pointer, since "already applied" writes whatever `extra` holds; a reader that lists one owner's rows by
  a scan must check each node against its hash, leftover rows being allowed there; on ETH non-finality has lasted
  longer than 512 blocks, so a reorganisation past the floor must end in a state fault, never INVALID.

Four things the task text does not say:

1. **A reset reaches the storage at `persistState`, not at `deleteAccount`** (C3b). It does something only on a staged
   layer; the throwaway layer and a read-only view ignore it, so the PoW miner, `eth_simulateV1` and the lenient
   builder, which run over storage nobody commits, are untouched (forge).
2. **Use the mirror.** `EEST_POSITIONS=1 EEST_FIXTURES=<dir> sbt "testOnly *EestFixtureCorpusSpec"` runs any
   execution-specs fixture set through `PositionMirror`. The Amsterdam corpus cannot see a missing reset (EIP-6780,
   EIP-7610); `for_paris` and `for_shanghai` can
   (`scripts/eest/fetch_fixtures.py DIR --prefix fixtures/blockchain_tests/for_paris/ --prefix fixtures/blockchain_tests/for_shanghai/`).
3. **`commitCanonical` is the one caller of `StateMoveLog.inFlight`**, under the state lock, with nothing inside it
   but the write: a view asked for in there waits for the writer. The staged storage is not behind the decoded-node
   cache; if it is ever put there, the in-place commit must evict after it writes (T131).
4. **A spec that passes first time gets a mutation run.** Every part was checked that way; the tables are in
   `baselines/t026-position-mirror.md` and in plan.md. **A driver that is killed leaves its mutation in the source**
   (it happened once, in `PathStateStorage`): grep for the mutant before trusting a tree after an interrupted run.

## Test baseline

See each PR's body for its figures. Part 1, on staging `f75ffcf10`: `testEssential` 748 suites, 8,934 tests, 1 failed.
That one is `KeyStoreImplSpec` "return an error when the keystore dir cannot be initialized", which fails the same way
on the parent commit in this container: it runs as root (B-205). Not run here: the ethereum/tests job (no `ets/tests`
checkout) and `IntegrationTest / test`.

The build container reaches Maven Central only through a mirror: `repo1.maven.org` answers 429. sbt was run with
`-Dsbt.override.build.repos=true` and a repositories file naming
`https://maven-central.storage-download.googleapis.com/maven2/` first, and `scalafixResolvers` set to the same.
