# Spec 018 Track C handoff: after C1 (cloud session → whoever takes C2a), 2026-11-11

**From** `cloud-dc960fdc`, a cloud session whose container and `.local/` do not outlive it, which is why this is a
tracked file and not a continuation under `.local/docs/continuations/`. **To** the session that claims B-055 (C2a).

## Status

C1 (B-054, "the trie reports positions") merged to staging as #1788 on 2026-11-11, with CI green (Test and Build on
JDK 25, EEST Amsterdam, the hive runs, the SNAP ratchet). The next slice in merge order is **C2a**
(B-055), and **C3a** (B-057) can be drafted beside it: C3a needs only `NibblePath` from C1.

C0 (B-053) is still open on fukuii-fc: the pinned jar, the fixed ranges and their recorded roots (T002 to T004) do not
exist. Three C1 tasks wait on them and are **not** done: T025 (roots against the jar), T033 (the M7 merge gate) and
T034 (DC-14). They are B-203.

## Files modified

| File | Status | Last action |
|------|--------|-------------|
| `src/main/scala/com/chipprbots/ethereum/mpt/NibblePath.scala`, `TriePosition.scala` | DONE (new) | in #1788 |
| `mpt/MerklePatriciaTrie.scala`, `MptTraversals.scala`, `MptVisitors/RlpHashingVisitor.scala` | DONE | positions threaded; `collapseTrieAt` |
| `db/storage/MptStorage.scala`, `MptStorageDecorator.scala` (new) | DONE | five defaulted members; the decorator base |
| `src/test/.../mpt/PositionTestKit.scala` and the `Position*Spec` files | DONE (new) | the C1 oracles; reusable by C2a and C3b |
| The seven wrapping `MptStorage` implementors | NOT STARTED | they move onto the decorator in C2a (T041) |

## Open compile errors

`sbt compile-all`: 0 errors. `sbt formatCheck`: passes.

## Next action (first thing to do)

Read `specs/018-storage-engine-consolidation/plan.md`, "C1 pre-code record (2026-11-11)", then start C2a at T041 with
these three things in hand, none of which the original task text says:

1. **T041, per wrapper.** `MptStorageDecorator` makes `get`/`getAt` final over a `read(hash, path)` hook and the two
   update members final over an `update(newRoot, removals)` hook. `CachingMptStorage`, `PrefetchNodeReader` and the
   `contextStorage` wrapper in `InMemoryWorldStateProxy` override `read`. `AppendOnlyMptStorage`, `ReplayMptStorage`
   and `RetainingMptStorage` override `update` with `removals.none`, or genesis load, `eth_call` replay and
   `eth_simulateV1` start deleting nodes. `AppendOnlyMptStorageSpec`, `ReplayMptStorageSpec` and
   `RetainingMptStorageSpec` pin today's behaviour and must stay green through the move.
2. **Batch reads.** `multiGetNodes`, `rawNodes` and `rawNodesAt` do not go through the `read` hook. Before the spec 017
   recorder lands, route them through a hook or record in data-model 9 that a batch read is never a parent-state read.
3. **T026 is now a C2a task**, after T046: fixtures through a recording sink need the owner threaded, or the account
   trie and every storage trie write their positions into one keyspace.

For C3a: `PositionKey` as data-model 3 writes it (a case class over `NibblePath`) compares by identity. Key by
`NibblePath.key` with the owner in front, or give it equality by content.

## Test baseline

On the C1 branch, rebased on staging `1e8ed47b2` (0.11.45): the ten new specs and `ConsensusAdapterSpec`, 70 tests,
0 failed; the trie, ledger, pruning and miner suites, 693 tests, 0 failed. `testEssential`: 739 suites, 8,748 passed,
1 failed, and that one is `KeyStoreImplSpec` "return an error when the keystore dir cannot be initialized", which fails
the same way on the parent commit there: the container runs as root, and root can create the directory the test
expects to be refused. Not run in the cloud container: the ethereum/tests job (no `ets/tests` submodule there) and
`IntegrationTest / test`.

The build container reaches Maven Central only through a mirror: `repo1.maven.org` answers 429. sbt was run with
`-Dsbt.override.build.repos=true` and a repositories file naming
`https://maven-central.storage-download.googleapis.com/maven2/` first, and `scalafixResolvers` set to the same.
