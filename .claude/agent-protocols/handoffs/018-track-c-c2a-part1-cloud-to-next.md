# Spec 018 Track C handoff: after C2a part 1 (cloud session → whoever takes C2a part 2), 2026-11-11

**From** `cloud-dc960fdc`, a cloud session whose container and `.local/` do not outlive it, which is why this is a
tracked file and not a continuation under `.local/docs/continuations/`. **To** the session that holds B-055 next. It
replaces the handoff written after C1.

## Status

C1 (B-054) merged as #1788. **C2a (B-055) lands as two PRs.** Part 1 is done: the six wrapping `MptStorage` implementors
are on `MptStorageDecorator` (T041), every trie of the state names its owner (T046: execution, genesis, the BAL
prefetch), and real blocks were run through a position mirror (T026, the EEST half). Part 2 is next: the `pathstate`
package behind `fukuii.state.path-live` (T035 to T038, T042 to T045, T047 to T050).

C0 (B-053) is still open on fukuii-fc. What waits on its pinned jar: T025, T033, T034 (B-203), T049 (M4), T050's DC-14
figures, and the baseline half of T026 (B-207).

## Files modified

| File | Status | Last action |
|------|--------|-------------|
| `main/db/storage/DecodedNodeCache.scala`, `PrefetchNodeReader.scala` | DONE | on the decorator; `read` hook; the prefetch reader shares one budget across owner views |
| `main/ledger/InMemoryWorldStateProxy.scala`, `BalPrefetcher.scala`, `StxLedger.scala` | DONE | owner threading; `storageOwner(address)`; `deleteAccount` calls `resetOwner` |
| `main/blockchain/data/GenesisDataLoader.scala`, `main/jsonrpc/EthSimulateService.scala` | DONE | owner threading in genesis; the two wrappers that withhold removals |
| `test/mpt/PositionTestKit.scala` | DONE | `OwnerPositionStore`, `PositionMirror`, `RawTrieWalk.leaves` |
| `test/ledger/EestBlockchainReplay.scala`, `EestPositionMirror.scala`, `EestPositionReplaySpec.scala`, `EestFixtureCorpusSpec.scala` | DONE | a `StorageObserver` hook; `EEST_POSITIONS=1` |
| `main/db/storage/pathstate/*` | NOT STARTED | part 2 |

## Open compile errors

`sbt compile-all`: 0 errors. `sbt formatCheck`: passes.

## Next action (first thing to do)

Read plan.md, "C2a part 1 record (2026-11-11)", then start part 2 at T042 (`StateExceptions.scala`, the `StateFault`
hierarchy of data-model 6.3.2, outside `MPTException`) and T036, its test. Then T043: the anchor-only `StateView` and
`PathMptStorage` over `PathNodeStorage` (CF `t` and `u`, which SNAP already writes).

Three things the task text does not say:

1. **A world already asks its storage for `forOwner`.** `PathMptStorage.forOwner(owner)` is the only entry: a read or an
   update on the unscoped storage is a bug, and `OwnerPositionStore` in the test kit refuses it the same way.
2. **The reset does nothing yet, and must not start to before T111.** `deleteAccount` calls `resetOwner` on the world's
   storage at once. The PoW miner, `eth_simulateV1` and the lenient builder run over a read-only store nobody commits
   (forge). Part 2's read side must leave `resetOwner` a no-op; C3b gives it a layer.
3. **Use the mirror.** `EEST_POSITIONS=1 EEST_FIXTURES=<dir> sbt "testOnly *EestFixtureCorpusSpec"` runs any
   execution-specs fixture set through `PositionMirror`. The Amsterdam corpus cannot see a missing reset (EIP-6780,
   EIP-7610); `for_paris` and `for_shanghai` can
   (`scripts/eest/fetch_fixtures.py DIR --prefix fixtures/blockchain_tests/for_paris/ --prefix fixtures/blockchain_tests/for_shanghai/`).

## Test baseline

On the part 1 branch, on staging `f75ffcf10`: see the PR body for the figures of the run that preceded it
(`testEssential`, the position specs, the fixture corpora). Known in this container only: `KeyStoreImplSpec` "return an
error when the keystore dir cannot be initialized" fails because the container runs as root (B-205). Not run here: the
ethereum/tests job (no `ets/tests` checkout) and `IntegrationTest / test`.

The build container reaches Maven Central only through a mirror: `repo1.maven.org` answers 429. sbt was run with
`-Dsbt.override.build.repos=true` and a repositories file naming
`https://maven-central.storage-download.googleapis.com/maven2/` first, and `scalafixResolvers` set to the same.
