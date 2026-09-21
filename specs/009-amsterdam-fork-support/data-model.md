# Phase 1 Data Model: Amsterdam Hard Fork Support

**Feature**: [spec.md](./spec.md) · **Plan**: [plan.md](./plan.md) · **Research**: [research.md](./research.md)
**Date**: 2026-09-21

This describes the shape of the data the feature adds or changes, and — where a type already exists —
what is wrong with its current shape. It is not an implementation; `beacon` owns that, with `forge`
sign-off on every shared signature listed in `research.md`.

## 1. Fork activation

### `ForkTimestamps` — additive, zero ETC risk

```scala
case class ForkTimestamps(
    shanghaiTimestamp: Option[Long] = None,
    cancunTimestamp: Option[Long] = None,
    pragueTimestamp: Option[Long] = None,
    osakaTimestamp: Option[Long] = None,
    amsterdamTimestamp: Option[Long] = None,   // NEW
    bpo1Timestamp: Option[Long] = None,
    bpo2Timestamp: Option[Long] = None
)
```

`Option` with a `None` default is the whole ETC-safety mechanism, and it is the same one Shanghai
through Osaka already rely on. No ETC config file declares a timestamp fork, so the field reads `None`
and every `isAmsterdamTimestamp` call returns `false`. Safety by omission, per `plan.md`'s Constitution
Check.

That mechanism is only as good as the configs, which is why FR-004 is a **test**, not a convention: a
config-matrix spec must load every shipped chain config and assert `amsterdamTimestamp.isEmpty` for
every ETC-family one. This mirrors the hardening applied to `olympiaGasLimitElasticity` — the ETC
assumption gets stated at an assertion site rather than trusted to reviewers noticing.

Accessor follows the existing pattern exactly:

```scala
def isAmsterdamTimestamp(timestamp: Timestamp): Boolean =
  forkTimestamps.amsterdamTimestamp.exists(ts => timestamp.toLong >= ts)
```

HOCON key `amsterdam-timestamp`, parsed alongside `osaka-timestamp` in `BlockchainConfig`.

### `EvmConfig` cascade — one new branch

`EvmConfig.forBlock(blockNumber, timestamp, blockchainConfig)` applies timestamp upgrades in ascending
fork order, each `copy`ing the previous. Amsterdam appends one branch after Osaka:

```scala
if blockchainConfig.isAmsterdamTimestamp(timestamp) then
  config = config.copy(
    feeSchedule = new FeeSchedule.AmsterdamFeeSchedule,
    amsterdamEnabled = true      // gates state-gas metering, transfer logs, code-size limits
  )
```

**Do not touch** the two-argument `forBlock(blockNumber, blockchainConfig)` overload. That is the
block-number cascade ETC dispatches through, and it is where `OlympiaConfigBuilder` and the
`etcForksDisabled` priority logic live.

## 2. Header

### `HefPostAmsterdam` — a new variant, and a decoder that must stop guessing

Current state, and the defect: `HeaderExtraFields` is a sealed trait with `HefEmpty` (15 RLP items),
`HefPostOlympia` (16), `HefPostShanghai` (17), `HefPostCancun` (20) and `HefPostPrague` (21). The
decoder matches those exactly — **except the last**, which is `case n if n >= 21`. A 23-item Amsterdam
header therefore decodes as `HefPostPrague`, silently dropping items 21 and 22, and re-encodes as 21
items. The hash that comes out is not the hash that went in: measured `94844dfd…` against canonical
`6372c88f…`.

```scala
case class HefPostAmsterdam(
    baseFee: BigInt,
    withdrawalsRoot: ByteString,
    blobGasUsed: BigInt,
    excessBlobGas: BigInt,
    parentBeaconBlockRoot: ByteString,
    requestsHash: ByteString,
    blockAccessListHash: ByteString,   // item 21, EIP-7928
    slotNumber: BigInt                 // item 22, EIP-7843
) extends HeaderExtraFields
```

Field ordering is **measured, not assumed**: `research.md` re-encoded block 600's 23-field header and
reproduced `headblock.json.hash` byte-identically, which fixes `requestsHash`[20],
`blockAccessListHash`[21], `slotNumber`[22].

The decoder becomes `case 21 => HefPostPrague`, `case 23 => HefPostAmsterdam`, and everything else —
including 22, and including anything above 23 — falls to the existing throw. Note that 18, 19 and 22
are *already* rejected; the `>= 21` catch-all is the only inconsistency, and closing it makes the
decoder uniformly exact.

Accessor methods (`baseFee`, `withdrawalsRoot`, `blobGasUsed`, `excessBlobGas`,
`parentBeaconBlockRoot`, `requestsHash`) each gain an `HefPostAmsterdam` case. Missing one is a silent
`None`, not a compile error, because the accessors are total functions returning `Option` — so each
accessor needs a round-trip assertion rather than review.

`extraFieldsCount` gains `case HefPostAmsterdam(...) => 8`.

## 3. Gas — the structural part

### The two counters are not interchangeable

`research.md` Decision 1, restated because every downstream shape follows from it:

```
tx_state_gas      = evm_state_gas_used
tx_execution_gas  = max(tx_gas_used_before_refund - tx_state_gas, calldata_floor_gas_cost)

block_execution_gas_used += tx_execution_gas
block_state_gas_used     += tx_state_gas

header.gasUsed            = max(block_execution_gas_used, block_state_gas_used)   # a MAXIMUM
receipt.cumulativeGasUsed = running sum of tx_gas_used                            # a SUM
```

Block 41 measured: header **183,600**, receipt **326,947**. They disagree, legitimately, and any type
carrying one scalar for both will fail one of them.

### `BlockResult` — three counters where there is now one

```scala
case class BlockResult(
    worldState: InMemoryWorldStateProxy,
    gasUsed: BigInt = 0,                 // becomes max(executionGasUsed, stateGasUsed)
    executionGasUsed: BigInt = 0,        // NEW — accumulates tx_execution_gas
    stateGasUsed: BigInt = 0,            // NEW — accumulates tx_state_gas
    receipts: Seq[Receipt] = Nil,
    executionRequests: Seq[ByteString] = Nil
)
```

Keeping `gasUsed` as the derived maximum means header construction and validation are unchanged at
their call sites; the receipt counter already lives inside `Receipt.cumulativeGasUsed` and stays there.
Pre-Amsterdam the two new counters are unused and `gasUsed` keeps its present meaning.

### `ProgramState` — frame-local state-gas accounting

Five new fields, all `BigInt`, all with save/restore on frame entry and exit:

| Field | Meaning |
|---|---|
| `stateGasReservoir` | remaining separate state-gas allowance |
| `evmStateGasUsed` | state gas charged so far in this transaction |
| `stateGasFromGasLeft` | state gas that had to be drawn from ordinary gas once the reservoir emptied |
| `stateGasBaseline` | frame-entry snapshot, restored on revert or exceptional halt |
| `stateGasCommitted` | committed portion, for the EIP-7702 interaction |

**On exceptional halt, state gas restores to baseline** — this is what makes the measured `tx-emit-*`
case come out at `max(100,000, 0) = 100,000` rather than `max(100,000, something)`.

`ProgramContext.startGas` becomes a split rather than a scalar:

```scala
evmGas             = tx.gasLimit - intrinsicGas
executionGasBudget = TX_MAX_GAS_LIMIT - intrinsicGas      // TX_MAX_GAS_LIMIT = 2^24 = 16,777,216
gasLeft            = min(executionGasBudget, evmGas)
stateGasReservoir  = evmGas - gasLeft
```

**Carry R-1 forward into the tests.** Every transaction in the reference fixture has
`tx.gas ≤ 1,000,000`, far below `2^24`, so `stateGasReservoir` is **0 in every fixture transaction**.
The fixture cannot distinguish a correct reservoir implementation from one that never seeds it at all.
Reservoir seeding, cross-frame passing (full, not 63/64), LIFO refill ordering and the successful-child
merge need written vectors.

### `calcTransactionIntrinsicGas` — signature change, five call sites

EIP-2780 decomposes the flat 21,000 into resource charges, which means the function needs facts it is
not currently given: the destination (does it exist? is it a contract?), the value (is this a
transfer? a self-transfer?), and the sender.

```scala
def calcTransactionIntrinsicGas(
    txData: ByteString,
    isContractCreation: Boolean,
    accessList: Seq[AccessListItem],
    authorizationListSize: Int = 0,
    to: Option[Address],          // NEW
    value: UInt256,               // NEW
    sender: Address               // NEW
): BigInt
```

Call sites, all of which must be updated and each of which is on a consensus path:
`StdSignedTransactionValidator.scala:290`, `BlockPreparator.scala:409` and `:471`,
`ProgramContext.scala:21`, `SignedTransaction.scala:620`.

Measured intrinsic behaviour to preserve exactly (`research.md` Decision 2 corroborations):

| Case | Pre-Amsterdam | Amsterdam |
|---|---|---|
| transfer to existing EOA | 21,000 | **21,000** (unchanged) |
| self-transfer | 21,000 | **12,000** (`TX_BASE_COST` alone) |
| zero-value call to contract | 21,000 | 15,000 (`12,000 + COLD_ACCOUNT_ACCESS 3,000`) |

The self-transfer figure is measured in the fixture, not read from the EIP.

## 4. Value-transfer logs (EIP-7708, FR-019)

Every balance-changing value transfer emits, from `0xffffffffffffffffffffffffffffffffffffffFE`, a log
that reconstructs as `Transfer(address,address,uint256)`. These are ordinary logs for every downstream
purpose: they enter `TxLogEntry`, the receipt's log list, the receipt bloom and the block bloom.

Measured: post-activation transfer blocks carry bloom popcount 12 where pre-activation blocks 18, 30
and 31 carry 0; blocks 46 and 48 reconstruct byte-exactly. Calltree's bloom popcount rises 18 → 30,
the four extra bloom items being its 1-wei CALL's transfer log.

The cost is folded into EIP-2780's `TX_VALUE_COST` of 6,000, which is why this cannot be separated
from the intrinsic-gas work and lands in slice B.

## 5. Builder execution requests (EIP-8282, slice D)

Two fixed system contract addresses, two new request types appended to the EIP-7685 typed-request list
in canonical order, contributing to `requestsHash`. Their system calls run with a dedicated
30,000,000 gas limit that must **not** count against the block gas limit — the same non-metering rule
fukuii already implements correctly for EIP-7002/7251, which was verified during diagnosis and should
be extended rather than re-derived.

`BlockResult.executionRequests` already carries typed requests as `type_byte || data` and needs no
shape change; only the producer list grows.

**Open** (`research.md` R-6): EIP-8282 has not been read. Blocks 36 and 37 carry gas totals that are
exact multiples of the storage-set state charge (783,360 = 8 × 97,920; 489,600 = 5 × 97,920) —
measured — but attributing those to specific builder queue slots is inference. Close before slice D.

## 6. What this does not change

`OlympiaOpCodes`, `ForkBlockNumbers`, the block-number `forBlock` cascade, `EthashConsensus`, ECIP-1017
emission, and every ETC chain config. The risk is not in the fork gates — those are `Option`-typed and
absent everywhere on ETC. It is in the shared *signatures* enumerated in `research.md`, where a
refactor can perturb ETC arithmetic by accident. Every one of them goes to `forge` before it is edited.

One coupling to assert rather than reason about: `BlockPreparator` credits ECIP-1111's treasury as
`baseFee * blockHeader.gasUsed`. Under Amsterdam that field is a maximum, not a total. ETC never
activates Amsterdam, so this is inert — but inert-by-argument is how the EIP-1559 elasticity gap got
missed, so it gets a test.
