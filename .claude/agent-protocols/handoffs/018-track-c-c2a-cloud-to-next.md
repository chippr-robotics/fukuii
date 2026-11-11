# Spec 018 Track C handoff: after C2b and the storage half of C3b (cloud session → whoever takes C3b part 2), 2026-11-11

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

**C3b (B-058) is in flight**, in two PRs. **The storage half is written** (plan.md, "C3b storage record"): the world
carries the storage tries it has replaced and tells its storage at `persistState`; `StateLock`; `StagedChanges` and
the staged mode of `PathMptStorage` (one `Change(prior, next)` for each position a block touches);
`PathStateStorage.stage(parent)` and `commitCanonical(staged, block, extra)`, one batch under the lock as a move of
the clock; `RetentionPolicy` with the prune count in AppState; `StateMetrics`. **Nothing in block execution calls it
yet.** The second PR is the wiring: T093, T094, T084, what the guard may be used for before C4, and the differential
over real blocks.

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
| `main/ledger/BlockExecution.scala`, `main/domain/Blockchain.scala`, `main/consensus/` | NOT STARTED (C3b part 2) | the wiring |
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
- **C3b part 2, the wiring** (T093, T094, T084, T075's substitute):
  - A `StagedBlockState` over `PathStateStorage`. **It must be lazy**: `BlockExecution` stages a block before it asks
    whether the block was executed already (`BlockImportGate.alreadyExecuted`), and `stage(parent)` throws for a block
    whose parent is not the anchor, which an already-applied block's is not. `commitCanonical(None, block, extra)` is
    the door for a caller with nothing staged: it writes `extra` only when the block is the anchor, decided under the
    lock. It needs the header (parent hash, state root), so `stageBlockState` takes a header, not a number.
  - **One execution, one layer.** A retry after a missing node, a stale view or a payload sent twice starts from a
    fresh `stage`: what an abandoned attempt wrote must not be in the block's changes.
  - `executeBlock(staged = None)` throws on a path-live store (dm 7.6); `saveLastBlock` writes nothing there; the
    single-block path (`executeAndValidateBlockFull`) commits state with no block data today, which a commit that
    moves the anchor with the best pointer cannot do.
  - **The guard's scope** (plan.md, the decision in the C3b storage record): linear import only. `newPayload`, a
    `forkchoiceUpdated` to another block, a reorganisation and a side branch end in a local state fault, never INVALID,
    never a peer penalty; the node does not start network sync on the guard (forge refuses to sign otherwise).
    `ConsensusStateFault` is C4b's task (T148): what C3b needs is that nothing reaches it.
  - **The differential**, in-process, since C0's pinned jar and ranges do not exist (B-203): the same chain imported
    with the guard off and on, every state root, receipts root and gas used equal; `t` and `u` equal to a raw walk
    after each block; a reverse from each record. It must include the Amsterdam access-list vectors on the real staged
    layer, the Paris and Shanghai fixtures that SELFDESTRUCT an account with storage, a CREATE frame that reverts after
    a collision with storage (forge), synthetic ETC-config chains (pre-Atlantis roots for each transaction, Atlantis
    touch-deletion, SELFDESTRUCT then CREATE2 in one block), and a rejected block that leaves the anchor, `t`, `u` and
    `v` as they were. A path-live datadir needs its genesis rows and anchor first (dm 5.4).
  - `TestModeWorldStateProxy` has its own `deleteAccount` and no `initialiseAccount`: it needs the world's
    `storageResets` in C3c (T108, forge).
  - **From the reviews of the storage half** (plan.md has the table): only import may use `stage`, and the layer
    refuses a world that does not follow the last one persisted on it; a decorator that drops the trie's removals
    (`StxLedger`, `EthSimulateService`, `GenesisDataLoader`) must never wrap the staged storage; the block's
    `BlockRef` takes its state root from the **header**; `newPayload`'s `extra` carries no best-block pointer, since
    "already applied" writes whatever `extra` holds; a block that is the anchor already is never staged; the
    `require` that a block follows its parent throws `IllegalArgumentException`, which the wiring must not take for a
    verdict on the block; pin that the staged storage is never wrapped by the decoded-node cache.
  - `finalized` is wired as none in `Storages`; C4d supplies the consensus client's.

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
