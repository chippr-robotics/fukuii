# Spec 018 Track C handoff: after C2a and C3a (cloud session → whoever takes C2b and C3b), 2026-11-11

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

**C2b (B-056) is in flight** in this session, in three PRs: the ratchet, the RPC readers, serving and walking. Its
pre-code record, with what conduit, herald and beacon changed, is in plan.md.

Next after it: **C3b** (B-058, the staged commit), which needs C3a and C2a and not C2b.

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
| `test/mpt/PositionTestKit.scala`, `test/db/storage/pathstate/PathStateTestKit.scala` | DONE | `PositionMirror`, `OwnerPositionStore`, `RawTrieWalk.statePositions`; a state on both stores, on Ephem and RocksDB |

## Open compile errors

`sbt compile-all`: 0 errors. `sbt formatCheck`: passes.

## Next action (first thing to do)

Read plan.md, "C2a part 1 record" and "C2a part 2 record", then:

- **C2b**: start at T054 (the allow-list ratchet test) and T057. The readers that execute (`eth_call`, estimate, trace,
  `eth_simulateV1`) run on `PathStateStorage.throwaway(block)`; readers that only read use `readOnly(block)`. A view
  serves only the anchor until C4a, so anything else is `StateUnavailable`: decide per reader what that means with the
  guard on. The ratchet (T062) also lists the three batch reads (data-model 9).
- **C3b**: start at T087 (`StateLock`), which is what makes the `StateLockHeld` that `ChangesetStorage` asks for, then
  T088 to T091. The commit writes its batch inside `StateMoveLog.inFlight(move) { … }`, takes the anchor from
  `move.newAnchor`, and keeps a **persisted** prune cursor: `ChangesetStorage` never scans for heights (data-model 4.1).

Four things the task text does not say:

1. **The reset does nothing yet, and must not start to before T111.** `deleteAccount` calls `resetOwner` on the world's
   storage at once. The PoW miner, `eth_simulateV1` and the lenient builder run over storage nobody commits (forge).
2. **Use the mirror.** `EEST_POSITIONS=1 EEST_FIXTURES=<dir> sbt "testOnly *EestFixtureCorpusSpec"` runs any
   execution-specs fixture set through `PositionMirror`. The Amsterdam corpus cannot see a missing reset (EIP-6780,
   EIP-7610); `for_paris` and `for_shanghai` can
   (`scripts/eest/fetch_fixtures.py DIR --prefix fixtures/blockchain_tests/for_paris/ --prefix fixtures/blockchain_tests/for_shanghai/`).
3. **Nothing calls `StateMoveLog.publish` yet.** C3b's commit is its first writer, under the state lock: publish, write
   the batch, `settled()`. The in-place commit must evict the decoded-node cache after it writes (T131).
4. **A spec that passes first time gets a mutation run.** Both parts were checked that way; the tables are in
   `baselines/t026-position-mirror.md` and in plan.md.

## Test baseline

See each PR's body for its figures. Part 1, on staging `f75ffcf10`: `testEssential` 748 suites, 8,934 tests, 1 failed.
That one is `KeyStoreImplSpec` "return an error when the keystore dir cannot be initialized", which fails the same way
on the parent commit in this container: it runs as root (B-205). Not run here: the ethereum/tests job (no `ets/tests`
checkout) and `IntegrationTest / test`.

The build container reaches Maven Central only through a mirror: `repo1.maven.org` answers 429. sbt was run with
`-Dsbt.override.build.repos=true` and a repositories file naming
`https://maven-central.storage-download.googleapis.com/maven2/` first, and `scalafixResolvers` set to the same.
