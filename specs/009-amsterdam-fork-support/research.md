# Phase 0 Research: Amsterdam Hard Fork Support

**Feature**: [spec.md](./spec.md) · **Plan**: [plan.md](./plan.md) · **Issue**: #1409
**Date**: 2026-09-21
**Status**: Complete. FR-017 satisfied — both figures closed.

## Method

Every figure below was reproduced against the reference fixture, not derived from EIP text alone. A
pure-Python RLP decoder, Keccak-256, single-entry MPT root and receipt/bloom constructor were built and
**validated against known pre-Amsterdam blocks first**, then applied to Amsterdam blocks.

Markers: **[M]** measured from `chain.rlp` / `headstate.json` / `headblock.json`; **[D]** derived
arithmetic reproducing a measured number; **[I]** inference not directly measured.

Validation of the machinery before use:

| Check | Result |
|---|---|
| Re-encode block 600 header (23 fields) → keccak | **PASS** — byte-identical to `headblock.json.hash`. Field order confirmed: `requestsHash`[20], `blockAccessListHash`[21], `slotNumber`[22] |
| Receipt-root reconstruction, blocks 8 & 24 (pre-Amsterdam `tx-calltree`) | **PASS** — both roots match |
| Receipt-root reconstruction, block 40 (`tx-callrevert`) | **PASS** |

## Decision 1 — the header's `gasUsed` is a per-dimension maximum, not a total

**This is the whole answer to the 183,600 puzzle.** EIP-8037 block accounting:

```python
tx_state_gas     = tx_output.evm_state_gas_used
tx_execution_gas = max(tx_gas_used_before_refund - tx_state_gas, calldata_floor_gas_cost)
block_execution_gas_used += tx_execution_gas
block_state_gas_used     += tx_state_gas

gas_used = max(block_execution_gas_used, block_state_gas_used)   # the header field
```

Receipts follow a **different counter**: `cumulative_gas_used` sums `tx_gas_used` (post-refund,
post-floor) — execution *plus* state.

go-ethereum master implements exactly this (`core/gaspool.go`, `ChargeGasAmsterdam`, with an explicit
comment "*After 8037 Block gas used is max(cumulativeExecution, cumulativeState)*").

**Rationale**: the execution gas was never missing. It is simply not what the header reports.

**Consequence for implementation**: header `gasUsed` and receipt `cumulativeGasUsed` **legitimately
disagree** on Amsterdam blocks. Any implementation carrying one scalar will fail one of them. Block 41
is the sharpest single test case: header **183,600**, receipt **326,947**.

**Alternatives considered**: that execution gas was not being added (refuted — it is, to a different
counter); that the state charge replaced rather than supplemented execution gas (refuted by the
receipt figure).

## Decision 2 — `tx-calltree` = 183,600. CLOSED.

**[M]** Block 41 header `gasUsed` = 183,600. Block 41 receipt `cumulativeGasUsed` = **326,947**
(recovered by brute-force search against `receiptsRoot`; then *predicted and confirmed* identical at
blocks 54, 67 and 600 — the last cross-checked against `headblock.json.receiptsRoot`).

```
tx_state_gas     = 183,600  = STATE_BYTES_PER_NEW_ACCOUNT(120) × CPSB(1530)
tx_execution_gas = 326,947 − 183,600 = 143,347
header gasUsed   = max(143,347, 183,600) = 183,600        ✓ [M]
```

State gas is **one term**: calltree's only state-creating act is its `CREATE` of a child whose initcode
is `LOG1; CALLER; SELFDESTRUCT`. The child returns empty, so code deposit is 0. The frame halts
normally, so no rollback refill. And EIP-8037 is explicit that `SELFDESTRUCT` of a same-transaction
account **produces no state-gas refill** — the 183,600 stands.

Everything else contributes zero state gas, verified against `headstate.json` **[M]**: calltree slot 0
= 46 and exactly one hash-keyed slot = 45, because the delegatecall always passes four zero bytes, so
`keccak(0x00000000)` is the *same* slot every time. It was created pre-Amsterdam at block 24, so every
later write is an existing-slot update. The counter reaching 46 also proves all 46 invocations
succeeded — the delegatecall did not OOG.

Execution gas 143,347 reproduced from the pre-Amsterdam baseline **[D]**:

| Term | Δ |
|---|---|
| block 24 total (all execution, pre-Amsterdam) **[M]** | 168,247 |
| intrinsic 21,000 → `TX_BASE_COST 12,000 + COLD_ACCOUNT_ACCESS 3,000` | −6,000 |
| 4 × cold account access 2,600 → 3,000 | +1,600 |
| `CALL_VALUE` 9,000 → `ACCOUNT_WRITE 9,000 + CALL_STIPEND 2,300` | +2,300 |
| delegatecall'd SSTORE pair repriced | −2,800 |
| `CREATE`: `GAS_CREATE 32,000` → `CREATE_ACCESS 12,000`; 120×1530 moves to state gas | −20,000 |
| **= 143,347** | **exactly 326,947 − 183,600** ✓ |

Corroborating exact reproductions **[D]** vs **[M]**: `tx-callrevert` 23,201 → 17,201;
`tx-emit-legacy` pre-fork 49,768; `tx-emit-eip1559` 49,068 (block 9) and 51,868 (block 25); and
self-transfers costing exactly **12,000** = `TX_BASE_COST` alone, confirming EIP-2780's self-transfer
rule verbatim while ordinary transfers stay at 21,000.

Two further `max()` witnesses **[M]**: block 36 `gasUsed` = 783,360 = 8 × 97,920 and block 37 =
489,600 = 5 × 97,920, where 97,920 = `STATE_BYTES_PER_STORAGE_SET(64) × CPSB(1530)`. Both are exact
multiples, so the state dimension is the max in each.

## Decision 3 — `tx-emit-*` = 100,000. CLOSED. It is out-of-gas, measured.

**[M] Direct evidence, not inference**: blocks 42 and 45 receipts reconstruct byte-exactly as
`status = 0`, `cumulativeGasUsed = 100,000`, **empty bloom, zero logs**. `emit` has no REVERT path and
these are top-level calls, so the only reachable failure is OOG. Independently **[M]**: the emit
contract's counter is frozen at 8 — exactly the number of *pre*-Amsterdam `tx-emit-*` transactions.
All 43 post-Amsterdam invocations wrote nothing.

**The missing ~48k is `GAS_STORAGE_SET` = 97,920, and it competes with execution gas inside the same
budget.** EIP-8037:

```python
evm_gas              = tx.gas - intrinsic_gas
execution_gas_budget = TX_MAX_GAS_LIMIT - intrinsic_gas      # TX_MAX_GAS_LIMIT = 2^24 = 16,777,216
gas_left             = min(execution_gas_budget, evm_gas)
state_gas_reservoir  = evm_gas - gas_left
```

With `tx.gas = 100,000 ≪ 2^24`, `gas_left = evm_gas` and **`state_gas_reservoir = 0`**. State charges
draw from the reservoir first, then from `gas_left` — and there is no reservoir.

Hand-trace, `tx-emit-legacy` block 45 **[D]**:

```
intrinsic = 12,000 + 3,000 + TX_VALUE_COST 6,000 + 12×16 = 21,192
gas_left on entry = 78,808 ; reservoir = 0
prologue (incl. cold SLOAD 2,100)                                =  2,168  → 76,640
SSTORE execution: COLD_STORAGE_ACCESS 2,100 + STORAGE_WRITE 10,000 = 12,100  → 64,540
SSTORE state gas: 64 × 1530                                       = 97,920  > 64,540
                                                        → EXCEPTIONAL HALT (OOG)
```

Full cost had the gas existed: **144,888**, i.e. 44,888 over the limit. On exceptional halt state gas
restores to baseline, so `evm_state_gas_used = 0`, `tx_gas_used = max(100,000, floor 21,480) = 100,000`
and header `gasUsed = max(100,000, 0) = 100,000` ✓ **[M]**.

**Why all four variants collapse to one number**: they differ only by access list (net +2,800) and blob
fields. The shortfall is ≈42–45k in every case, so each halts at the same opcode and consumes its
entire limit. The pre-fork spread of ~2,100–2,800 is exactly what OOG erases. Four different numbers
becoming one is the signature of the limit being hit.

## Decision 4 — EIP-2780 / 8037 / 8038 / 7778 are ONE atomic change

They cannot be sequenced independently:

- EIP-2780's parameter table **imports** 8038's values and 8037's products. 2780 alone is not
  numerically defined.
- EIP-2780 **moves** new-account creation out of intrinsic gas into a runtime charge that must land in
  8037's state dimension. 2780 without 8037 leaves account creation uncharged.
- EIP-8038 explicitly **defers** `GAS_STORAGE_SET`/`GAS_NEW_ACCOUNT` to 8037. 8038 alone produces a
  pricing state that exists in no client.
- 8037's block accounting is written in terms of 7778's no-refund rule.
- **Empirically**: block 41's 183,600 requires 8037's `max()` *and* 8038's `CREATE_ACCESS` *and*
  2780's 15,000 intrinsic simultaneously. No two-of-three reproduces it.

**Revised slicing** (supersedes the provisional split in `plan.md`):

| Slice | Content | Depends on |
|---|---|---|
| **A** | Header exact-arity rejection + `HefPostAmsterdam` + `amsterdamTimestamp` plumbing + ForkId | nothing — ship first |
| **B** (atomic) | 2780 + 8037 + 8038 + 7778 + 7954 + **7708** | A |
| **C** | EIP-7928 block-level access list computation/validation | A, B |
| **D** | EIP-8282 builder deposit/exit contracts + request commitment | A, B |

Plan slices 2 and 3 therefore **merge into B**. Slice A remains genuinely independent, confirming the
spec's judgement that User Story 2 is separately shippable.

## Decision 5 — EIP-7708 must be added to scope

**[M]** Post-Amsterdam transfer blocks 46–48 have bloom popcount 12 where pre-Amsterdam blocks
18/30/31 have 0. Blocks 46 and 48 reconstruct byte-exactly as a single
`Transfer(address,address,uint256)` log emitted by `0xffffffffffffffffffffffffffffffffffffffFE`.
Calltree's bloom popcount rises 18 → 30 — exactly the four extra bloom items from its 1-wei CALL's
transfer log.

**Without EIP-7708, every receipt root and bloom after block 36 is wrong.** It is also inseparable from
`TX_VALUE_COST`, since 2780 folds the transfer-log cost into the 6,000.

The spec's Assumptions state that scope is defined by what the fixture exercises. The fixture exercises
7708. **The spec is amended accordingly** — this is a correction to the spec, not a scope expansion.

## Risks and open items

**R-1 — the fixture never exercises the state-gas reservoir.** Maximum `tx.gas` in the entire chain is
1,000,000, far below `TX_MAX_GAS_LIMIT = 16,777,216`, so `state_gas_reservoir = 0` in **every**
transaction. Passing this fixture proves nothing about reservoir seeding, cross-frame passing (full,
not 63/64), LIFO refill ordering, `state_gas_committed`, or the successful-child merge step. **Treat
"fixture green" as necessary, not sufficient** — those paths need `ethereum/execution-spec-tests`
`tests/amsterdam/` vectors. This compounds the gate gap already recorded in `plan.md`.

**R-2 — EIP-7928 is a sequencing dependency on 8037, not just a header field.** 8037's CREATE charge is
conditional on an ordering 7928 defines (defer reading the computed destination until after sender
balance, nonce overflow and call-stack depth checks). Implementing 8037's CREATE charge without that
ordering gives wrong answers for creations that fail pre-checks. The fixture does not cover that path.

**R-3 — `STORAGE_CLEAR_REFUND` 11,616 and 7778's no-refund block accounting are untested here.** No
transaction in the chain clears a storage slot. Both are pure spec-reading.

**R-4 — EIP-8246 unread.** EIP-7708 references it ("after EIP-8246, ETH can no longer be burned via
`SELFDESTRUCT` to self"). Whether 8246 is in Amsterdam is **an open scope question**. The fixture does
not discriminate — calltree's child selfdestructs to `CALLER` with zero balance.

**R-5 — the exact OOG halt point is derived, not measured.** Status 0 / 100,000 / no logs are measured;
placing the halt at the first `SSTORE`'s state metering is a trace. EIP-7928 defines a two-phase
pre/post-state SSTORE validation that was not read in full; if the pre-state phase pre-charges
differently the halt point moves. **The observable is unaffected** (an exceptional halt consumes
everything either way), but a per-opcode conformance test could disagree.

**R-6 — block 36/37 slot attribution is inferred.** The totals are exact multiples of
`GAS_STORAGE_SET` **[M]**; attributing them to specific EIP-8282 queue slots is **[I]** from
`headstate.json`. EIP-8282 was not read. Close before slice D.

**R-7 — go-ethereum master is mid-flight.** `core/gaspool.go` carries a `TODO(rjl, marius)` on
`GasPool.Gas()` semantics under Amsterdam. Treat geth as authoritative for **observable output on this
fixture** (it produced it), not for internal API shape.

## Implementation verdict

**Not a constant swap — a restructure.** `ProgramState` gains four frame-local counters
(`stateGasReservoir`, `evmStateGasUsed`, `stateGasFromGasLeft`, `stateGasBaseline`, plus
`stateGasCommitted` for EIP-7702) with save/restore on frame entry/exit;
`ProgramContext.startGas` becomes a reservoir split; `BlockResult` and `executeTransactions` must carry
two block counters **plus a separate receipt counter**.

The constant tables are the easy part and should land last.

### Shared-with-ETC surface — route to `forge` before any edit

The dangerous surface is not the fork gates (which are `Option`-typed and absent in every ETC config).
It is the shared **signatures** whose refactor can perturb ETC arithmetic by accident:

`BlockHeader.scala` · `EvmConfig.scala` (`FeeSchedule`, `calcTransactionIntrinsicGas` — signature must
gain `to`, `value`, sender identity; all 5 call sites change) · `OpCode.scala` (SSTORE, `CreateOp`,
CALL, SELFDESTRUCT each need a state-gas return channel) · `ProgramState.scala` · `VM.scala` ·
`ProgramContext.scala` · `BlockPreparator.scala` · `BlockResult.scala` ·
`StdSignedTransactionValidator.scala` · `Receipt` / `BloomFilter.scala`.

One coupling to assert rather than assume: `BlockPreparator` credits ECIP-1111's treasury as
`baseFee * blockHeader.gasUsed`. Under Amsterdam `gasUsed` is a `max()`, not a total. ETC never
activates Amsterdam so this is inert — but it must be **tested**, not reasoned about.
