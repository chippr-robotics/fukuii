# Ancestry, not the canonical index (consensus footgun)

Used by: forge, beacon, eye. Applies to every execution and fork-choice path.

## Rule 1: block-relative history resolves by ancestry

Anything an executing block may ask about its own past (BLOCKHASH today, and any future "hash/header of height n"
lookup) MUST walk `parentHash` back from the executing block. Use `AncestorBlockHashes.forBlock(header, reader)`.

Never read `BlockchainReader.getBlockHeaderByNumber` (the canonical number→hash index) in an execution path. The index
names whatever chain the node last wrote at each height. That is not the executing block's ancestry when:
- a side-chain block executes during a reorganisation (heights above the fork point);
- the branch parent is a previously orphaned block (the queue roots the branch directly on it);
- an Engine API side-chain payload is stored by hash only;
- a DB was left mixed by an earlier bug.

Reference: core-geth / go-ethereum `GetHashFn`, `core/evm.go:93-129`. Reproduced divergence: a valid canonical block
rejected with a state-root mismatch (`ReorgBlockhashParitySpec`).

Grep ratchet. Block import, mining and test mode must stay at zero hits; the only remaining ones are the RPC
simulation sites in `StxLedger` (3 today), plus EthUserService, GraphQL and EthSimulate outside these dirs, all tracked
as CHASE-QUEUE C10. The count may only go down:
```
grep -rn "getBlockHeaderByNumber(number).map(_.hash" src/main/scala/com/chipprbots/ethereum/ledger \
  src/main/scala/com/chipprbots/ethereum/testmode src/main/scala/com/chipprbots/ethereum/consensus \
  | grep -v StxLedger.scala        # must print nothing
```

## Rule 2: every head change leaves the index equal to the head's ancestry

core-geth `writeBlockAndSetHead` + `reorg()` (`core/blockchain.go` ~1465, ~2160-2284):
- the head moves only to a strictly heavier block (`ReorgNeeded`);
- on a move, heights between the common ancestor and the new head are rewritten to the new chain, including the
  part below the branch parent when that parent was not canonical;
- every index entry above the new head is deleted;
- when the head does NOT move, side blocks never touch the index.

fukuii writes index entries as it executes, so a reorganisation that keeps the old head must put the captured entries
back. `ConsensusImpl.settleHead` does this, atomically, through `BlockchainWriter.rewriteCanonicalIndex`.

The post-merge head (ETH) moves through a different writer, with the same invariant: `engine_forkchoiceUpdated` ->
`ForkChoiceManager.applyForkChoiceState` -> `BlockchainWriter.promoteToCanonicalHead` (go-ethereum `SetCanonical`:
`reorg` + `writeHeadBlock`). A PoS head can move DOWN — to an ancestor, or to a shorter side chain — so:
- every entry above the new head is deleted (up to the old best, then on while entries remain);
- the walk back from the new head trusts an existing entry as the meeting point only AT OR BELOW the old best.
  Above it the index is not the best block's ancestry: `engine_newPayload` writes entries ahead of the head, and the
  PoS designated-head arm moves the best block without clearing above it. Stopping at such an entry leaves the other
  branch's hashes below the new head (hive `Re-org to Previously Validated Sidechain Payload` walks into exactly this).
Shapes: `EngineApiSidechainNewPayloadSpec`, "engine_forkchoiceUpdated to a lower head".

## Checklist for any change touching import, reorg or execution

1. Does anything executed read the index? Replace it with an ancestry lookup.
2. After every path out of `reorganise` (success, partial heavier, partial lighter, nothing executed), is
   index == ancestry(best)? Test all four; `ReorgBlockhashParitySpec` has the shapes.
3. Is any walk bounded by reorg depth rather than chain length? A walk "until it meets X" that can miss X runs to
   genesis.
4. PoS arm (branch selected only by the designated head) belongs to beacon. Do not change it from the ETC side.
