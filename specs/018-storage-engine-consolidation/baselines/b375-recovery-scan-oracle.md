# B-375 (T214): the recovery scans against the pinned v0.12.1, on one seeded store

Recorded 2026-11-22 by `vault-b375` (B-375), cloud session. Closes the jar half of T214 that
`RecoveryScansVerdictSpec` left open (its oracle is staging's own hash-layout scan).

## What was run

`scripts/oracle/b375/run.sh -v <plain clone of v0.12.1> [-s <sbt>] [-w <workdir>]`:

1. `RecoveryScanOracleSeeder` (test source, staging) writes one hash-layout RocksDB datadir for each of 11 cases under
   `<work>/seed/<case>/db` (CF `n` nodes by hash, CF `c` code by hash, nothing else: the scans read nothing else) and a
   `manifest.tsv` (case, state root, the node hashes left out, the code hashes left out, the nodes replaced by bytes
   that are not that node, a description). The fixture is `RecoveryScansVerdictSpec`'s state (alice and bob share a
   storage trie, carol has another, dave has none; alice and carol share a code, dave has another).
   `RecoveryScanOracleSeederSpec` holds the seeded datadirs to the in-memory fixture: each node and code is present
   unless injected, and `CombinedRecoveryScan` finds the same on the datadir as on the `Lacking` store the spec uses.
2. `RecoveryScanOracleRunner.scala`, one file, is copied **unchanged** into v0.12.1's test sources and into staging's,
   and run (`Test/runMain`) on the same seeded directory in each. It opens each datadir with `RocksDbDataSource` and
   runs six scans: `CombinedRecoveryScan.scanFrom` (whole), the same by shard (`ShardEnumerator.enumShards` depth 1,
   `scanShard`), `CombinedRecoveryScanner.run()` sequential and with concurrency 2, `StorageRecoveryActor.scanForMissingStorage`
   and `BytecodeRecoveryActor.scanForMissingBytecodes`. 66 verdicts each side (11 cases x 6 scans).
   (2026-11-24, B-217: the two legacy scans were retired, and the runner no longer prints them. A run of it now gives
   44 verdicts each side, 11 cases x 4 scans; the record below is the run of 2026-11 as it was.)
3. The two verdict files are diffed. Exit 0 is no difference.

**Entry points.** Everything is public or `private[sync]`, so the runner sits in package `blockchain.sync` and calls the
scan functions directly; no actor, Pekko system or peer is involved. The actors wrap exactly these functions, so the
verdicts are the actors' scan phase, without the download phase that follows it. The store each scan reads is
`SerializingMptStorage` over `ArchiveNodeStorage` over `NodeStorage`, the Hash scheme's store; `path-live` is not
involved (v0.12.1 ships it off).

**The oracle is the pinned release's source, built, not the release jar.** v0.12.1 was a plain `git clone` of this repo
checked out at tag `v0.12.1` (`21cfa13dfdc968c85e3ff393ab249376f37eba73`, the commit `pinned-jar.md` records) and built
with sbt. `run.sh` refuses any other HEAD and any worktree. The scans' code is the code in the jar; the jar's bytes
(SHA-256 `2100c4ce…08fec9`) were not used, because the runner has to be compiled against the scans' `private[sync]`
members, which a jar launch cannot reach.

**Normalisation.** A scan that throws is `threw <nearest named class>`. Otherwise
`code=[sorted] storage=[sorted owner>root] holes=N`. The parallel scanner's shards race to name the owner of a storage
trie that two accounts share (the same race `RecoveryScansVerdictSpec` accommodates), so its storage is printed as the
sorted roots only.

## Result

**No difference: all 66 verdicts are byte-identical between v0.12.1 and staging** (`cmp` equal). The cases:

| Case | Injected | scanFrom / scanShards / scanner (both) | legacy-storage | legacy-bytecode |
| --- | --- | --- | --- | --- |
| `whole` | nothing | nothing, 0 holes | none | none |
| `code-shared-by-two-accounts` | code alice and carol share | that code, once | none | that code |
| `both-codes` | both codes | both | none | both |
| `shared-storage-root` | root alice and bob share | one trie, one owner (hash store) | one trie | none |
| `carol-storage-root` | carol's storage root | carol's trie | carol's trie | none |
| `storage-root-and-code` | carol's root and dave's code | both | carol's trie | dave's code |
| `account-child` | an account-trie node under the root | whole walk: `threw MissingRootNodeException`; by shard, sequential and parallel: 1 hole | none (logged, partial) | none |
| `account-root` | the account-trie root | every scan `threw MissingRootNodeException` | same | same |
| `storage-trie-interior` | a node below a storage root | nothing: the scans check a storage root's presence, not its subtree | none | none |
| `corrupt-storage-root` | bytes that are no node under carol's root | missing storage trie (carol's), as absent | carol's trie | none |
| `corrupt-account-child` | another node's bytes under an account-trie node | `threw CorruptNodeException` (every scan that walks) | none (logged, partial) | none |

Nothing here is a verdict that looks wrong. Two behaviours to know, both present in v0.12.1 and unchanged:
`storage-trie-interior` is a gap neither version finds (by design: the scan is a presence check of the root, the
subtree is the audit's); and the legacy scans return what they had when a walk fails, as `RecoveryScansVerdictSpec`
records.

## Mutation check (the harness surfaces a gap staging would miss)

In staging's `CombinedRecoveryScan.onLeaf` the missing-code check was skipped for a code hash starting `c6` (dave's),
and `run.sh` re-run (not committed, reverted):

```
-both-codes  scanFrom   code=[ae8e63…,c63664…] …        +both-codes  scanFrom   code=[ae8e63…] storage=[] holes=0
(and scanShards, scanner-sequential, scanner-parallel)  (and the same four for storage-root-and-code)
```

8 of 66 lines differ; exit 1, "DIFFERENCES FOUND". The four legacy-bytecode lines did not change, as they should (the
mutation is in the combined scan only). Reverted; the unmutated run is the result above.

## Scope and limits

- Hash layout only. The scans on the unified layout are held to the hash layout's by `RecoveryScansVerdictSpec`; the
  by-position scans did not exist in v0.12.1 (C5b is after it), so the jar has no by-position verdict to compare.
- The gaps are those of `RecoveryScansVerdictSpec` plus three (`account-root`, `storage-trie-interior` and the two
  `corrupt-*`, which are the hash layout's H3 cases).
- The datadirs are small (about 100 nodes). Shard counts at depth 1 are therefore few; the parallel scanner's merge is
  exercised, not stressed. A live-host state is the follow-up if wanted.
- Cost: the v0.12.1 tree's first build took about 3 minutes here; a run after that took about 1 minute.
