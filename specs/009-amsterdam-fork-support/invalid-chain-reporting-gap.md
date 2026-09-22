# The p2p import path cannot report invalidity to the Engine API

Analysis only; no code change. Recorded so the next thread starts from the measurement rather than re-deriving it.

## What the measurement showed

b4cdc30 changed the FAILURE MODE of 48 engine tests (24 engine-api, 24 engine-cancun)
with ZERO change in pass/fail count:

  24:  "Unexpected status response on EngineNewPayloadV1/V3: INVALID, expected=VALID,ACCEPTED"
         -> "Timeout waiting for main client to detect invalid chain"
   3:  "Client returned VALID on an invalid chain"
         -> "Timeout waiting for main client to detect invalid chain"
  21:  already "Timeout waiting..."  -> unchanged

So the node stopped wrongly rejecting valid payloads AND stopped accepting invalid chains.
It now simply fails to REPORT invalidity in time. That is a directional correctness win the
gate count hides completely, and it renames the problem: not "validation is wrong" but
"validation result never reaches the caller".

## The structural cause, verified

`EngineApiService.scala:110`   `private val invalidBlocks = ConcurrentHashMap[...]`
`EngineApiService.scala:116`   `private val acceptedChildrenByParent = ...`
`EngineApiService.scala:123`   `private def markInvalidRecursive(hash, lvh)`

    grep -rn "markInvalidRecursive\|invalidBlocks" --include=*.scala src/main \
      | grep -v EngineApiService.scala
    (no results)

**Nothing outside EngineApiService can reach the registry.** Yet that registry is what
answers INVALID later:
  * `:222`  newPayload — if `invalidBlocks.containsKey(payload.parentHash)` then propagate
            INVALID to the child with the stored LVH
  * `:475`  forkchoiceUpdated — if `invalidBlocks.containsKey(headBlockHash)` then INVALID

And newPayload does NOT share the flipped flag: `:355` still calls
`executeAndValidateBlockFull(block, alreadyValidated = true)`. The engine path and the p2p
path validate independently and do not talk.

## Why this explains both big clusters

The engine tests that sync over p2p (the "Syncing" variants, and the whole Invalid Missing
Ancestor family) feed the invalid ancestor to fukuii via a PEER, not via newPayload. So:

  before b4cdc30 — p2p imported the invalid ancestor unchecked, it became canonical, and
                   newPayload(tip) answered VALID  =>  "returned VALID on an invalid chain"
  after  b4cdc30 — p2p correctly REFUSES the ancestor, but the refusal stays inside
                   BlockImporter. invalidBlocks never learns. newPayload/forkchoiceUpdated
                   on the descendant answer SYNCING/ACCEPTED forever  =>  hive times out

Same gap, two symptoms. Fixing the validation was necessary and moved the failure one layer
outward; the remaining layer is REPORTING.

## Why the reverted attempt was the right idea done wrong

It called `reportInvalidBranch` for every `BlockExecutionError` except
`MPTError(MissingNodeException)` — including `ValidationAfterExecError("Block has invalid gas
used")`, which is how a merely-missing contract code surfaces and which
`BlockImporter.scala:518-545` treats as RECOVERABLE. So it marked honest blocks permanently
invalid.

b4cdc30 supplies the missing discrimination. `ValidationBeforeExecError` carries a TYPED
payload (`BlockHeaderError | BlockError | OmmersError`) and that case was UNREACHABLE on the
bulk path before the flip. There is no "retry later" reading of `HeaderTimestampError`.

## Proposed shape (not yet implemented, needs beacon)

A narrow, typed channel from the import path into the registry:

  REPORT invalid, with LVH = last validated ancestor:
    * `ValidationBeforeExecError(_)`   — typed, unambiguous, newly reachable
    * `TxsExecutionError(...)`         — distinct ADT case, unambiguous
    * `ValidationAfterExecError` for stateRoot/receiptsRoot — unambiguous BECAUSE
      StdValidators.scala:88-92 checks gasUsed FIRST and returns early, so reaching the
      stateRoot branch proves execution completed

  NEVER report:
    * `MPTError` / `MissingParentError`              — missing state, transient
    * `ValidationAfterExecError("...invalid gas used")` UNLESS
      `findMissingContractCode` returns None (BlockImporter.scala:545-549 already does this)

The registry must move out of EngineApiService, or gain a narrow interface the import path
can call. Prefer the latter: a small trait with `reportInvalid(hash, lvh)` owned by the
engine module, injected into BlockImporter. Do NOT widen it into a general-purpose event bus.

## NOT established

* Whether the 48 time out because reporting never happens, or because it happens too slowly.
  The mode change is consistent with "never", but I have not read a per-test timeline to
  prove the node had the answer and failed to deliver it vs. never reached a verdict.
* Whether LVH computed on the p2p path matches what hive expects
  (`altChainPayloads[InvalidIndex-1]`, a validated NON-canonical side-chain block).
* Whether the ReOrgFromCanonical=true topology (peer head 14 < fukuii head 15) even lets
  fukuii fetch the ancestor to judge it. If it cannot, reporting is moot for that half and
  the sync-strategy problem still dominates.
