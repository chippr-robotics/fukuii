# Invalid-ancestor: the error types DO separate the two claims

Written while the fc713a9 hive matrix was running; that matrix has now completed and its
engine result is folded in below.

**Confirmed by measurement.** On `fc713a9`, with b4cdc30 landed, the invalid-ancestor tests
still fail — including `Timestamp ... Invalid P8` and all five `Transaction*` variants, which
are exactly the cases this document classifies as now unambiguously identifiable from the
error type. So the conclusion below is not merely argued, it is the observed outcome:
**classification was never the blocker.**

## The 48, reconciled independently from hive source

`simulators/ethereum/engine/suites/engine/tests.go:261-301` loops over 11 InvalidField
values (`helper.go:89-108`), × 2 ReOrgFromCanonical modes, + InvalidStateRoot's
EmptyTransactions variant × 2 = **24**, re-registered for Paris and Cancun = **48**.
Matches the inventory's count, derived from a different direction.

`InvalidNumber`, `InvalidPrevRandao` and `InvalidOmmers` are commented out upstream —
do not budget for them.

## Why the reverted attempt failed, restated precisely

It called `reportInvalidBranch` for every `BlockExecutionError` except
`MPTError(MissingNodeException)`. Missing contract code does not surface as that:
`InMemoryWorldStateProxy.getCode` returns `ByteString.empty` instead of throwing, so an
incomplete-state node produces `ValidationAfterExecError("Block has invalid gas used")` —
indistinguishable, by string, from a block that genuinely lies about gasUsed.

## What b4cdc30 changed

`ValidationBeforeExecError` carries a TYPED payload —
`ValidationError = BlockHeaderError | BlockError | OmmersError` (BlockExecution.scala:581).
There is no "retry later" reading of `HeaderTimestampError`. Before b4cdc30 that case was
unreachable on the bulk import path; now it fires.

## Classification of all 11, post-b4cdc30

| InvalidField | fukuii error | unambiguous? |
|---|---|---|
| InvalidGasLimit | `ValidationBeforeExecError(HeaderGasLimitError)` | YES — typed, NEW |
| InvalidTimestamp | `ValidationBeforeExecError(HeaderTimestampError)` | YES — typed, NEW |
| InvalidTransactionSignature | `TxsExecutionError` | YES — distinct ADT case |
| InvalidTransactionNonce | `TxsExecutionError` | YES |
| InvalidTransactionGas | `TxsExecutionError` | YES |
| InvalidTransactionGasPrice | `TxsExecutionError` | YES |
| InvalidTransactionValue | `TxsExecutionError` | YES |
| RemoveTransaction | txRoot or stateRoot mismatch | YES |
| InvalidReceiptsRoot | `ValidationAfterExecError(receipts)` | YES |
| InvalidStateRoot | `ValidationAfterExecError(state root)` | YES — see ordering below |
| **InvalidGasUsed** | `ValidationAfterExecError(gas used)` | **NO — the one ambiguous case** |

**The ordering argument for InvalidStateRoot.** `StdValidators.scala:88-92` checks gasUsed
FIRST and returns early. Reaching the stateRoot branch therefore proves gas matched, which
proves execution was complete — missing code would have zeroed gas and tripped the earlier
branch. So a stateRoot mismatch cannot be a missing-code artifact. This is load-bearing and
would break if anyone reorders those two checks.

**The tx-tampering cases are NOT txRoot mismatches.** The secondary geth node receives the
tampered payload over engine API, derives txRoot from the tampered txs, and stores a
self-consistent block. fukuii fetches THAT over p2p, so `validateTransactionRoot` passes.
The invalidity is the transaction itself — bad signature / nonce / insufficient value —
caught by `signedTransactionValidator` during execution as `TxsExecutionError`.

## The one ambiguous case already has disambiguation

`BlockImporter.scala:518-545` string-matches "Block has invalid gas used", then calls
`findMissingContractCode(failedBlock)`:
  * `Some(codeHash)` -> transient. Fetch via SNAP GetByteCodes, re-import. (existing)
  * `None`           -> already routes to `InvalidateBlocksFrom`. (existing, line 545-549)

So the separation the first attempt needed was already in the tree; the attempt fired
BEFORE it rather than using it.

## Consequence for the inventory

The "pre-existing coverage ceiling" note said header-relative invalidities are undetectable
and "this bounds how much of the 48 any discovery mechanism can reach". That bound is gone:
10 of 11 are classifiable from the error TYPE alone, and the 11th has working
disambiguation. Update that section when this lands.

## Still NOT established

* Whether fukuii can REACH the invalid ancestor at all in ReOrgFromCanonical=true, where
  the peer's head (14) is LOWER than fukuii's (15). The inventory's finding stands: any
  sync gated on greater height or total difficulty never fires, and the reverse-by-hash
  walk from the tip's parentHash does not exist. **Classification is necessary but not
  sufficient — this is the real remaining blocker, and it is a sync-strategy problem, not
  an error-taxonomy one.**
* Whether `latestValidHash = altChainPayloads[InvalidIndex-1]` can be produced once the
  ancestor is reached.
* The 60s budget (20-30s of it setup) may not fit a reverse walk.
