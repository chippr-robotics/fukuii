# T026: real blocks through a position mirror (C2a part 1, 2026-11-11)

**What ran.** Blocks of execution-specs fixtures, executed by the production import path (`EestBlockchainReplay`) on
today's hash-keyed store, with a `PositionMirror` (`src/test/scala/com/chipprbots/ethereum/mpt/PositionTestKit.scala`) in
front of the storage every world is built over, and in front of the genesis load.

The mirror holds what a position-keyed store would hold. It applies each change a trie reports, under the owner and at
the position the trie reports it. It checks each read: a read at `(owner, path)` for a hash is right only when the block
under execution wrote that node at that owner and position, or the row there when the block began hashes to it. After the
genesis load and after each accepted block it compares its rows with a walk of the raw RLP from the block's state root: the
account trie, the storage trie of every account that has one, and no rows for any other owner. A rejected block's changes
are dropped.

A fixture fails on: a read or an update with no owner; a read by hash alone; a node reported as replaced or absorbed that
is not the row at that position; a write over a row that was not reported; a read no rule answers; rows that differ from
the walk; and, before Amsterdam, a read of a storage trie after its account was removed (only the access-list builder
makes those).

**Where.** A cloud container, 4 cores, JDK 21, branch of staging `f75ffcf10` with C2a part 1.

| Run | Fixtures | Units kept | Blocks rejected | Trie updates applied | Reads checked | Storage tries reset | Of them holding rows | Findings | Wall time |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| tests@v21.0.0 `for_amsterdam` + `for_bpo2toamsterdamattime15k` (the CI corpus) | 26,503 | 53,855 | 5,715 | 2,882,422 | 13,997,435 | 737 | 0 | 0 | 92 s |
| tests@v21.0.0 `for_paris` + `for_shanghai` | 8,835 | 20,097 | 596 | 130,167 | 406,238 | 717 | 22 | 0 | 24 s |
| the vendored vectors (`EestPositionReplaySpec`) | 41 | 88 | 5 | 4,490 | 24,620 | n/a | 7 | 0 | 4 s |

A unit is the genesis load or one accepted block. Every fixture also ended on its expected head, as without the mirror.

**The mirror was shown to fail.** A check that passes first time proves little, so each rule that C2a part 1 exists to
satisfy was broken on purpose:

| Mutation | Run | Result |
| --- | --- | --- |
| The owner threading and the wrappers reverted (main sources at staging) | the vendored vectors | 0 of 7 pass: every fixture reports reads and updates with no owner |
| `resetOwner` removed from `deleteAccount` | the Amsterdam corpus | **26,503 of 26,503 still pass.** From Cancun on a removed account never has stored storage: EIP-6780 removes only a contract created in the same transaction, and EIP-7610 refuses a creation over storage. So this corpus cannot see a missing reset |
| `resetOwner` removed from `deleteAccount` | `for_paris` + `for_shanghai` | 22 of 8,835 fail, each with `rows are kept for account <owner>, which has no trie`: exactly the 22 fixtures that reset a trie holding rows |

Seven of those 22 are vendored as `src/test/resources/eest-regression/selfdestruct-stored-storage.json` (Paris and
Shanghai; SELFDESTRUCT of a contract with storage, a creation at the same address in the same block and in a later one, a
reverted SELFDESTRUCT beside a real one), so the unit-test job fails on a missing reset. The spec asserts that each of them
does reset a trie that holds rows, or the vector would stop showing what it is there for.

**What this does not cover.**

- The proposer and lenient-builder path, the BAL prefetch, `eth_call` and `eth_simulateV1`: the mirror is on import only.
  T111 names a mirrored builder run as its test.
- A side branch: the mirror takes each block to build on the last accepted one. A fixture that reorganises would be
  reported, not passed.
- Forks before Paris, and with them a state root per transaction (Frontier, Homestead, EIP-150). The replay harness does
  not model them; their runner is the ethereum/tests one (`it/ethtest/`), which needs the `ets/tests` checkout. Open as
  B-207 with the comparison against `ethtests-<date>.txt` (T007).
- Cost on a real chain. The states here are tens of accounts.

**Cost in CI.** `eest-amsterdam.yml` runs the corpus a second time with `EEST_POSITIONS=1`. The first run is unchanged
and stays the unobserved import path (beacon). The second took 92 s here with the mirror on, 103 s with the mutation.
