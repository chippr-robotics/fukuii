# Root Cause Investigation: E2E Sync Integration Test Timeouts

**Status**: Resolved (B-018). This page replaces the December 2025 write-up, whose diagnosis was wrong.

## What the December 2025 write-up claimed

It put the nightly "state sync timeout" failures down to a 5 s Pekko ask timeout in `CommonFakePeer` and a missing
`sync.peer-response-timeout` in `src/it/resources/application.conf`, citing `PEER_REQUEST_TIMEOUT ... GetNodeData`, and
it expected the 30 s overrides to fix them. They did not. Sixteen tests (15 in `E2EStateTestSpec`, 1 in `E2ESyncSpec`)
kept failing, and the 30 s overrides are not what failed them:

- Regular sync never sends `GetNodeData`. It fetches headers and bodies and executes each block. These tests never
  produce a state-node request, so no peer-response timeout can apply to them.
- A rerun on 2026-11-25 against `origin/staging` produced no `PEER_REQUEST_TIMEOUT` line. Each failing test ran for
  about 95 s and failed with `java.util.concurrent.TimeoutException: Task time out after all retries`. That is
  `waitForRegularSyncLoadLastBlock` giving up after its own 90 retries at 1 s, and the number is its own, not a network
  timeout's.

The 30 s settings are harmless and stay. They were never the fix.

## Real causes

### A. State injected into headers with no transactions (15 `E2EStateTestSpec` tests)

The tests built the chain with `importBlocksUntil(n)(updater)`, where the updater wrote an account straight into a
world-state proxy. The block header then named the resulting state root, but the block carried no transaction that
could produce it.

The syncing peer does not copy state. It executes each block on the state of its parent. Executing block 100 gave it
its own root, which is not the root the header names. When it came to block 101 it looked for its parent's state under
the header's root, and that root was never in its store:

```
Block import error ...MerklePatriciaTrie$MissingAccountNodeException:
  Account trie node not found <header state root> while accessing account 0000...0000
```

The import failed, the best block stalled at 99 or below, and the test waited out its retries. The harness's
validators accept any post-execution state root, so nothing reported the mismatch except the next block's failure to
find its parent's state.

The one test of the 16 that did not stall, "should detect and reject invalid state roots", never synced: it builds the
same chain on both peers and compares headers. It passed before this change and is unchanged.

### B. Reorg tests mined before regular sync started (`E2ESyncSpec` reorg, and the two reorg tests in `E2EStateTestSpec`)

`mineNewBlocks` hands each block to the peer's regular-sync actor as `SyncProtocol.MinedBlock`. That actor builds the
block importer when it receives `Start`. The tests mined first and sent `Start` afterwards, so the importer did not
exist and `RegularSync` dropped the block ("Mined block ... not imported: regular sync's pipeline is not running").
Peer1 never extended its chain, and peer2 waited for a block that did not exist.

The tests were also not reorgs. Both peers held the identical chain and peer1 added blocks on top, so peer2 only caught
up.

### C. `getMptForBlock` started from the empty root (mined blocks)

`FakePeer` in `RegularSyncItSpecUtils` shadowed `CommonFakePeer.getMptForBlock` with a version that always began at
`MerklePatriciaTrie.EmptyRootHash` and ignored the parent block's `stateRoot`. Any mined block carrying state was built
on an empty world and named a root unrelated to its parent. Harmless for `IdentityUpdate`, whose root is the empty root
anyway.

## What the tests do now

- `FakePeer.sealChild` builds a block that carries real signed transactions (transfers and contract creations that
  write storage), executes it with `BlockExecution`, and seals the header with the state root, gas used and receipts
  execution gives. The sender is the block beneficiary, so block rewards fund it and its transactions start at block 2.
  Nonces come from the parent state.
- `importExecutedBlocksUntil` extends a peer's chain with such blocks. `mineExecutedBlocks` hands them to the importer
  of a running regular sync one at a time, and waits (a condition wait, not a sleep) until each is the best block.
- The shadowing `getMptForBlock` is gone. `CommonFakePeer.getMptForBlock` reads the parent header's own `stateRoot`.
- The reorg tests give peer2 a chain of its own (a different beneficiary), start regular sync on both peers, mine on
  peer1 until its chain is the heavier one, and only then connect. They check that peer2 left its fork: the fork tip is
  no longer canonical, and an account only the fork touched is gone from peer2's best state.
- Assertions read the syncing peer's own state back (`accountAtBest`, `storageAtBest`): balances, sender nonce and
  contract storage slots. A header comparison alone could not tell a synced state from a missing one.
- The `IO.sleep(1.second)` in the "missing peers" test is gone. `connectToPeers` already returns only after the
  handshake.

## Results

| | Before | After |
|---|---|---|
| `E2EStateTestSpec` | 1 passed, 15 failed (each about 95 s) | 16 passed, 0 failed |
| `E2ESyncSpec` | 15 passed, 1 failed | 16 passed, 0 failed |
| `RegularSyncItSpec` | passed | passed |

Run with:

```bash
sbt "IntegrationTest / testOnly com.chipprbots.ethereum.sync.E2EStateTestSpec com.chipprbots.ethereum.sync.E2ESyncSpec"
```

## Lessons

- A timeout in a test names the wait that expired, not the fault. Read the first error in the log, here the
  missing-node exception, not the last line.
- A fixture that writes state into a header without the transaction that produces it cannot be synced by a peer that
  executes blocks. Build test chains by executing them.
- A message sent to an actor before `Start` can be dropped without any failure. Start the actor, then send.
