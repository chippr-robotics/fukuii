# Contract: Amsterdam gas accounting

**Feature**: [../spec.md](../spec.md) · **Evidence**: [../research.md](../research.md)

Every figure here is reproduced from the reference fixture. Markers: **[M]** measured, **[D]** derived
arithmetic reproducing a measured number.

## The rule

```
tx_state_gas      = evm_state_gas_used
tx_execution_gas  = max(tx_gas_used_before_refund - tx_state_gas, calldata_floor_gas_cost)

block_execution_gas_used += tx_execution_gas
block_state_gas_used     += tx_state_gas

header.gasUsed            = max(block_execution_gas_used, block_state_gas_used)
receipt.cumulativeGasUsed = running sum of tx_gas_used   (post-refund, post-floor)
```

**The header reports a maximum. Receipts report a sum.** On Amsterdam blocks these two fields
legitimately disagree, and an implementation that derives one from the other will be wrong on one of
them. This is the single most important sentence in this document.

## Reservoir split

```
evm_gas              = tx.gas - intrinsic_gas
execution_gas_budget = TX_MAX_GAS_LIMIT - intrinsic_gas       # TX_MAX_GAS_LIMIT = 2^24 = 16,777,216
gas_left             = min(execution_gas_budget, evm_gas)
state_gas_reservoir  = evm_gas - gas_left
```

State charges draw from the reservoir first, then from `gas_left`. Below `TX_MAX_GAS_LIMIT` the
reservoir is empty and state gas competes directly with execution gas — which is the entire
explanation for the measured out-of-gas cases below.

On exceptional halt, state gas restores to its frame baseline, so `evm_state_gas_used = 0` for a
top-level transaction that halts.

## Conformance vectors from the fixture

These are the assertions that distinguish a correct implementation from a plausible one.

### V1 — header/receipt divergence (block 41, `tx-calltree`)

| Quantity | Value | Source |
|---|---|---|
| header `gasUsed` | **183,600** | **[M]** |
| receipt `cumulativeGasUsed` | **326,947** | **[M]**, confirmed identical at blocks 54, 67, 600 |
| `tx_state_gas` | 183,600 = `STATE_BYTES_PER_NEW_ACCOUNT(120) × CPSB(1530)` | **[D]** |
| `tx_execution_gas` | 143,347 = 326,947 − 183,600 | **[D]** |
| header | `max(143,347, 183,600) = 183,600` | ✓ |

A single-counter implementation fails this and passes nothing weaker. It is the decisive test for
slice B.

### V2 — measured out-of-gas (blocks 42 and 45, `tx-emit-*`)

| Quantity | Value | Source |
|---|---|---|
| receipt `status` | **0** | **[M]** byte-exact reconstruction |
| receipt bloom | **empty** | **[M]** |
| logs | **zero** | **[M]** |
| `cumulativeGasUsed` | **100,000** (the full limit) | **[M]** |
| emit contract counter | frozen at **8** = the pre-Amsterdam invocation count | **[M]** |

Trace **[D]**: intrinsic `12,000 + 3,000 + TX_VALUE_COST 6,000 + 12×16 = 21,192`; entry `gas_left`
78,808, reservoir 0; prologue 2,168; `SSTORE` execution `COLD_STORAGE_ACCESS 2,100 + STORAGE_WRITE
10,000`; then `SSTORE` state gas `64 × 1530 = 97,920` against 64,540 remaining → exceptional halt.
Full cost had the gas existed: 144,888.

All four variants collapse to the same number because they differ by only ~2,800 and the shortfall is
~42–45k in every case. **Four different prior costs becoming one identical number is the signature of
the limit being hit** — it is not a coincidence to be explained away.

### V3 — pure state-dimension blocks

| Block | header `gasUsed` | Decomposition | Source |
|---|---|---|---|
| 36 | 783,360 | 8 × 97,920 = 8 × `STATE_BYTES_PER_STORAGE_SET(64) × CPSB(1530)` | **[M]** |
| 37 | 489,600 | 5 × 97,920 | **[M]** |

Exact multiples of the state charge, so the state dimension is the maximum in both — two more
witnesses for the `max()` rule.

### V4 — intrinsic gas, all three directions

| Case | Pre-Amsterdam | Amsterdam | Source |
|---|---|---|---|
| transfer to existing EOA | 21,000 | 21,000 (block 46) | **[M]** |
| self-transfer | 21,000 | 12,000 (block 165) | **[M]** |
| `tx-callrevert` | 23,201 (block 23) | 17,201 (block 40) | **[M]**, reproduced **[D]** |

A blanket change in either direction is wrong. The self-transfer case is the sharpest, and it is
measured rather than read from the EIP.

### V5 — value-transfer logs (EIP-7708)

| Blocks | Bloom popcount | Source |
|---|---|---|
| 18, 30, 31 (pre-activation transfers) | 0 | **[M]** |
| 46, 47, 48 (post-activation transfers) | 12 | **[M]** |
| 41 (`tx-calltree`) | 18 → 30 | **[M]** |

Blocks 46 and 48 reconstruct byte-exactly as one `Transfer(address,address,uint256)` log from
`0xffffffffffffffffffffffffffffffffffffffFE`. Without these logs every receipt root and bloom after
activation is wrong.

### V6 — contract-creation transactions (added in slice B, measured)

Not in the original five, and it should have been: **131 of the 522 post-activation transactions are
contract creations**, a path none of V1–V5 touched. The chain deploys three identical initcodes on both
sides of the fork, so each is a measured pre/post pair.

| Initcode | Pre-Amsterdam | Amsterdam | What it exercises |
|---|---|---|---|
| 256-byte deployer (blocks 4 / 50) | 105,782 | **84,692** = the whole limit | code deposit, 200/byte → CPSB/byte |
| LOG loop (blocks 5 / 38) | 64,613 | **44,560** = the whole limit | gas-metered loop termination |
| SSTORE loop (blocks 6 / 39) | 119,662 | **104,258** = the whole limit | fresh-slot state charges in a loop |

All three post-activation deployments consume their entire gas limit, and the mechanism is EIP-2780's
**pre-execution phase**: the 183,600 new-account charge is a runtime charge applied after the
transaction is already valid but before the first frame, and none of the three is funded for it. Per
EIP-2780 the transaction stays valid and included — it is charged for everything consumed and reverted.
An implementation that instead rejected these transactions, or that charged the 183,600 as intrinsic
gas, produces a different block.

## What the fixture does NOT cover

Recorded so it is not mistaken for coverage. Maximum `tx.gas` across the chain's 612 transactions is
**1,628,065** (block 14, legacy), far
below `TX_MAX_GAS_LIMIT`, so `state_gas_reservoir = 0` in **every** transaction.

Status after slice B. **A written vector is weaker evidence than a measured one**; the distinction is
kept explicit rather than collapsed into a tick.

| Behaviour | Status |
|---|---|
| reservoir seeding above the threshold | **written vector** — `AmsterdamStateGasReservoirSpec`, incl. the exact-threshold boundary |
| cross-frame reservoir passing (full, not 63/64) | **written vector**, and confirmed discriminating: forwarding at 63/64 makes it fail |
| LIFO refill ordering | **written vector** (unit-level; the ordering is unobservable end to end unless gas runs out) |
| the successful-child merge | **written vector** (unit-level, both the simple and the child-draw case) |
| the reservoir being *necessary* — `gas_left` exhausted while the reservoir funds a charge | **still uncovered**. Reaching it needs an intrinsic cost near 2^24, i.e. ~1 MB of calldata |
| `state_gas_committed` | **still uncovered**. Implemented (EIP-7702 commit ordering) but no vector reaches it |
| EIP-7702 authorization state charges under EIP-2780 | **still uncovered**. The chain's single Type-4 transaction is pre-activation |
| `STORAGE_CLEAR_REFUND` 11,616 | **still uncovered** — no transaction in the chain clears a slot |
| EIP-7778's no-refund block accounting | **still uncovered** — implemented, but no fixture transaction earns a refund |
| the EIP-7928 creation pre-check ordering, for a creation that fails its pre-checks | **still uncovered** (slice C, T041) |

**Fixture-green is necessary, not sufficient.** The rows still marked uncovered need
`ethereum/execution-spec-tests` `tests/amsterdam/` vectors, which compounds the gate gap declared in
`plan.md`.
