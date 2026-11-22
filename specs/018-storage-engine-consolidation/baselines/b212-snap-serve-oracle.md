# B-212 (T052): SNAP responses from the position store against the pinned v0.12.1, on one seeded state

Recorded 2026-11-22 by `vault-b212` (B-212), cloud session. Closes the jar half of T052 that
`SnapServePositionStoreSpec` left open (its oracle is staging's own server on a hash store).

## What was run

`scripts/oracle/b212/run.sh -v <plain clone of v0.12.1> [-s <sbt>] [-w <workdir>]`:

1. `SnapServeOracleSeeder` (test source, staging) builds one state and writes it twice: `<work>/seed/hash/db` (CF `n`
   nodes by hash, CF `c` code: the datadir v0.12.1 opens) and `<work>/seed/position/db` (CF `t`/`u` rows by position,
   the anchor in AppState, CF `c`: the unified layout), and `manifest.tsv` (root, every account with its slot count,
   storage root, code hash and three slot keys, every code hash, the stored paths of the account trie and of nine
   storage tries). `SnapServeOracleSeederSpec` holds the datadirs to what the oracle needs (see "The state").
2. `SnapServeOracleRunner.scala`, one file, is copied **unchanged** into v0.12.1's test sources and into staging's and
   run (`Test/runMain`) on `seed/hash/db` in each. It calls `SnapServer.serveAccountRange`, `serveStorageRanges` (with
   `SnapServer.storageRootOf`, as `NetworkPeerManagerActor` does), `serveTrieNodes` and `serveByteCodes`, and writes each
   response as the RLP bytes the wire carries (the message's own `toBytes`), in hex, one line per request. 342
   requests, 14.4 M hex characters. Then `SnapServeOraclePositionRunner` (staging only; v0.12.1 has no position store)
   runs the same request list on `seed/position/db`, one `PathStateStorage.readOnly(anchor)` view per request.
3. The three files are compared: v0.12.1 (hash store) against staging on the position store, and, as a control,
   against staging on the hash store. Exit 0 is no difference in either.

**Entry points.** The narrowest functions that produce a response message: no actor, Pekko system or peer. The
handlers in `NetworkPeerManagerActor` add the root-window check (`stateForRoot`, `isStateRootFresh`), which is out of
scope here, and the send. **`SnapServer.scala` and `SNAP.scala` are byte-identical in v0.12.1 and staging** (`diff`),
so a difference could only come from the store, never from the server's own code; the oracle is about the store.

**The oracle is the pinned release's source, built, not the release jar** (as for B-375): a plain clone at tag
`v0.12.1` (`21cfa13dfdc968c85e3ff393ab249376f37eba73`, `pinned-jar.md`'s commit), which `run.sh` insists on. The
serving code is the jar's; the jar's bytes were not launched, because the runner needs the `SnapServer` object, which a
jar launch cannot reach.

## The state

3000 accounts (keys `keccak("oracle-account-i")`), 40 contracts (accounts 100-139; code from 50 bytes to 12 KB; two
share one code), and these storage tries (slot keys hashed, values the RLP of a trimmed word):

| Accounts | Slots | Note |
| --- | --- | --- |
| 100 and 110 | 1500 | one trie, two owners (same root) |
| 101 | 600 | |
| 102 and 103 | 300 | one trie, two owners |
| 104 and 105 | 40 | one trie, two owners |
| 106, 107, 108 | 1, 2, 17 | |
| every other | 0 | empty storage |

## The requests (342)

- **GetAccountRange**: the whole range at seven budgets (2 MiB to 0); bounded sub-ranges; origins one past a key and one
  before it; single key; reversed (origin above limit); past the last key; `max..max`; `zero..zero`; a budget above
  2 MiB; an unknown root; the empty root; a pagination chain of 8 KiB pages from `zero` to the last key (as a syncing
  peer walks it).
- **GetStorageRanges**: the 1500-slot account at seven budgets; ten accounts at once (big, mid, the pair of 300, the
  small ones, an empty-storage account, an unknown one), forwards and reversed, at six budgets; the pair of 300 and the
  twin of the 1500-slot trie, one and two at a time, tight; continuation from the three slot quartiles (at the key, one
  after, one before, at three budgets, and with several accounts); bounded and reversed ranges; past the last slot; an
  unknown root; a pagination chain of 2 KiB pages over the big trie.
- **GetByteCodes**: every code at three budgets, with absent hashes mixed in, the empty-code hash, none, duplicates, 1100
  hashes (the 1024 cap), reversed.
- **GetTrieNodes**: the root; the first 64 account-trie paths at three budgets; every stored path of the account trie
  in requests of 100, and all at once at 10 KB; missing paths (odd, long, past a leaf); an empty path set, no paths, an
  unknown root; for each of nine storage holders, every stored path of its trie in requests of 60, all at 3 KB, and
  missing paths; several accounts in one request; the pair of 300; an account with no storage; an unknown account; an
  account hash with no path.

## Result

**No difference: all 342 responses are byte-identical between v0.12.1 on the hash store and staging on the position
store, and between v0.12.1 and staging on the hash store** (`cmp` equal; the three files have SHA-256 `543b300b…dcac`).

Response sizes show the limits bite: the whole account range is 127 KB, at 8 KiB it is 11 KB, at 300 bytes 1.9 KB;
the 1500-slot trie is 80 KB whole and 10.9 KB at 8 KiB; 0 requests `threw`; the 15 answers of 8 bytes or fewer are
the ones that should be empty (unknown or empty root, no accounts, empty-code hash alone, empty path sets and the like).

Nothing here is a response that looks wrong per snap/1 (geth).

## Mutation check (the harness surfaces a store or limit change staging would make)

Both in staging's `SnapServer.scala`, `run.sh` re-run, reverted afterwards (not committed):

1. `accumulated += keyHash.size + slimSize` became `accumulated += slimSize` in `serveAccountRange`: exit 1, 99 of
   the requests differ on each of the two staging runs, in GetAccountRange (25) and GetStorageRanges (74, which
   count the chain pages); the chain's page count changes (330 lines against 342), which is why the chains come last.
2. `accumulated += enc.size` became `accumulated += enc.size / 2` in the single-path branch of `serveTrieNodes`:
   exit 1, exactly 2 requests differ (`nodes-account-first64-b4096`, `nodes-account-all-tight`), the two that are cut
   by the byte budget over account-trie paths. Requests with a plenty budget cannot see it, as they should not.

The unmutated run is the result above.

## Scope and limits

- Hash layout in v0.12.1 against the unified layout in staging; the path-live store as `PathStateStorage.readOnly`
  of the anchor (the form `stateForRoot` hands out). The root-window clamp (a root outside the window) is the other
  half of T052, held by `BlockchainReaderSeamSpec`; it is not a store question and not part of this comparison.
- The state is 3000 accounts and a few thousand storage nodes, not a live chain: ranges and proofs are exercised across
  several levels and hundreds of slots, not at millions of accounts. The time budget of the server (4 s) never fires.
- Position rows come from `PathStateTestKit.seedPositions` (the rows SNAP writes), not from a node that synced the state.
  The datadir that has run the production write path (head moves, changesets) is the live check, B-393.
- Cost: seeding ~5 s; v0.12.1's runner compile and run ~25 s; staging ~45 s; about 1.5 minutes in all with a warm build.
