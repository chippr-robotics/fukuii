---
description: "Task list for Amsterdam hard fork support (ETH-family)"
---

# Tasks: Amsterdam Hard Fork Support (ETH-family)

**Input**: Design documents from `/specs/009-amsterdam-fork-support/`

**Prerequisites**: [plan.md](./plan.md), [spec.md](./spec.md), [research.md](./research.md),
[data-model.md](./data-model.md), [contracts/](./contracts/), [quickstart.md](./quickstart.md) — all complete.

**Tests**: **Required, not optional.** FR-017 makes reproducing the fixture's gas figures a completion
gate, and [contracts/gas-accounting.md](./contracts/gas-accounting.md) defines five conformance vectors
(V1–V5) that tasks below cite by name. A task that satisfies a vector says so.

**Organization**: by the **A/B/C/D slices in `plan.md`**, not by the spec's three P1 stories. Phase 0
research merged two of those stories for arithmetic reasons — block 41's figure requires EIP-8037's
`max()`, EIP-8038's `CREATE_ACCESS` and EIP-2780's decomposed intrinsic simultaneously, and no
two-of-three reproduces it. The slice mapping is:

| Slice | Spec stories | Ships |
|---|---|---|
| **A** | US2 | alone, first |
| **B** | US1, and US3 as a constraint | atomic — cannot be subdivided into separately-mergeable units |
| **C** | US4 (access lists) | after B |
| **D** | US4 (builder requests), P2 | after B |

US3 (ETC unchanged) is not a phase. It is a guard that runs in **every** phase, as T007 and the
per-slice repeats of it.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: parallelizable — different files, no dependency on an incomplete task
- **[Story]**: US1 / US2 / US3 / US4 from spec.md
- File paths are exact

## Routing — read before starting any task

Per the Consensus-Critical Change Protocol, **every** task below touching `src/main/scala` is
consensus-critical:

- **`beacon` implements** all ETH behaviour.
- **`forge` signs off** *before* any edit to the shared-signature surface enumerated in
  `research.md` → "Shared-with-ETC surface": `BlockHeader.scala`, `EvmConfig.scala`, `OpCode.scala`,
  `ProgramState.scala`, `VM.scala`, `ProgramContext.scala`, `BlockPreparator.scala`,
  `BlockResult.scala`, `StdSignedTransactionValidator.scala`, `Receipt`/`BloomFilter.scala`.
  Sign-off is on the *signature change*, not the diff that follows it.
- **`wraith`** on compile failures; **`eye`** after each slice.

The danger is not the fork gates — those are `Option`-typed and absent in every ETC config. It is a
shared signature refactor perturbing ETC arithmetic by accident.

---

## Phase 1: Setup

**Purpose**: make the fixture and its figures reproducible by someone who did not do Phase 0.

- [x] T001 [P] Commit the fixture-decoding harness (RLP decode, keccak, single-entry MPT root, receipt and bloom reconstruction) used in Phase 0 to `scripts/amsterdam-fixture/`, with its pre-Amsterdam validation cases (receipt roots at blocks 8, 24, 40; block 600 header re-encode) as runnable self-checks
- [x] T002 [P] Record in `scripts/amsterdam-fixture/README.md` how to obtain `chain.rlp`, `txinfo.json`, `headstate.json`, `headblock.json`, `genesis.json` and `forkenv.json` from hive's devp2p simulator testdata, so every figure in `contracts/gas-accounting.md` can be re-derived rather than trusted

**DONE.** `scripts/amsterdam-fixture/verify.py` — all checks pass. It caught a real error on first
run: R-1's stated maximum gas limit was 1,000,000, and the true figure is **1,628,065**. R-1's
conclusion is unaffected (both are far below 2^24) but the number was wrong in three documents and
is now corrected. That is the value of a runnable check over a recorded assertion.

**Checkpoint**: another engineer can reproduce V1, V3 and R-1 from scratch. V2, V4 and V5 are
recorded in `contracts/gas-accounting.md` but are not yet self-checking here — adding them is
worthwhile and not yet done.

---

## Phase 2: Foundational (blocking — must complete before Slice B)

**Purpose**: activation gating and the ETC guard. Additive and ETC-inert by construction, so it can
land with Slice A.

- [ ] T003 Add `amsterdamTimestamp: Option[Long] = None` to `ForkTimestamps` in `src/main/scala/com/chipprbots/ethereum/utils/BlockchainConfig.scala`, positioned after `osakaTimestamp`
- [ ] T004 Add `isAmsterdamTimestamp(timestamp: Timestamp): Boolean` to `BlockchainConfig` in `src/main/scala/com/chipprbots/ethereum/utils/BlockchainConfig.scala`, following the exact shape of `isOsakaTimestamp`
- [ ] T005 Parse HOCON key `amsterdam-timestamp` alongside `osaka-timestamp` in `BlockchainConfig`'s reader in `src/main/scala/com/chipprbots/ethereum/utils/BlockchainConfig.scala`
- [ ] T006 [P] Declare `amsterdam-timestamp` in `src/main/resources/conf/base/chains/hive-chain.conf` only, wired from the hive harness. Do NOT add it to `eth-chain.conf` or `sepolia-chain.conf` — neither network has scheduled Amsterdam, and declaring it would activate untested consensus rules on a live chain
- [ ] T007 [US3] Write `src/test/scala/com/chipprbots/ethereum/utils/ChainConfigMatrixSpec.scala` asserting that every ETC-family shipped config (`etc-chain.conf`, `mordor-chain.conf`, `gorgoroth-chain.conf`) has `forkTimestamps.amsterdamTimestamp.isEmpty`. **This is FR-004 as a test rather than a convention** — safety-by-omission is only as good as the configs, and nothing currently catches a stray declaration

**Checkpoint**: Amsterdam is declarable, absent everywhere it should be absent, and a test says so.

---

## Phase 3: Slice A — strict header arity (US2)

**Goal**: a header fukuii cannot represent is refused, not silently truncated into a wrong hash.

**Independent test**: a 23-field header round-trips to canonical hash
`6372c88fef519c6e4bebbe02d8447c084fc8cd759fae25c5b5cc3c6e2be99bfe`; a 22-field header is rejected.

**Ships alone and first.** It does **not** make devp2p progress — block 36 still fails, just earlier
and honestly. That is the expected outcome, not a reason to bundle it with Slice B.

### Tests first (these must FAIL before T010)

- [ ] T008 [US2] Write `src/test/scala/com/chipprbots/ethereum/domain/BlockHeaderAmsterdamRlpSpec.scala`: a 23-item header decodes to `HefPostAmsterdam`, re-encodes byte-identically, and hashes to `6372c88f…`. **Confirm it fails first** — today it passes decoding and produces `94844dfd…`, so a test that goes green before the fix is testing the wrong thing
- [ ] T009 [P] [US2] Extend the same spec with the round-trip invariant `encode(decode(bytes)) == bytes` for all six shapes in `contracts/header-rlp.md` (15/16/17/20/21/23), and rejection with the count named for 18, 19, 22 and 24

### Implementation

- [ ] T010 [US2] Add `HefPostAmsterdam` to `HeaderExtraFields` in `src/main/scala/com/chipprbots/ethereum/domain/BlockHeader.scala` with fields in the measured order from `contracts/header-rlp.md`: `baseFee`, `withdrawalsRoot`, `blobGasUsed`, `excessBlobGas`, `parentBeaconBlockRoot`, `requestsHash`, `blockAccessListHash`, `slotNumber`
- [ ] T011 [US2] Replace `case n if n >= 21` with `case 21 => HefPostPrague` and `case 23 => HefPostAmsterdam` in the decoder in `src/main/scala/com/chipprbots/ethereum/domain/BlockHeader.scala`. Everything else falls through to the existing throw. Note 18, 19 and 22 are *already* rejected — the catch-all is the sole tolerant branch and the sole source of wrong hashes
- [ ] T012 [US2] Add the `HefPostAmsterdam` encode case to `BlockHeaderEnc.toRLPEncodable` in `src/main/scala/com/chipprbots/ethereum/domain/BlockHeader.scala`, emitting 8 extra items
- [ ] T013 [US2] Add `HefPostAmsterdam` cases to each accessor in `src/main/scala/com/chipprbots/ethereum/domain/BlockHeader.scala` (`baseFee`, `withdrawalsRoot`, `blobGasUsed`, `excessBlobGas`, `parentBeaconBlockRoot`, `requestsHash`) and to `extraFieldsCount` (→ 8). **Each needs its own assertion**: these are total functions returning `Option`, so a missing case is a silent `None`, not a compile error
- [ ] T014 [US2] Add the `HefPostAmsterdam` case to `BlockHeader.validateFieldCount` in `src/main/scala/com/chipprbots/ethereum/domain/BlockHeader.scala`, and a case to `src/test/scala/com/chipprbots/ethereum/domain/BlockHeaderFieldCountSpec.scala` — this is the separate shape-versus-active-fork check, not the decoder arity check, and it needs the new shape too
- [ ] T015 [US3] Run the four ETC regression suites and confirm green with no assertion edits: `sbt "testOnly *SpiralToOlympiaGasTransitionSpec* *OlympiaBlockHeaderValidationSpec* *OlympiaGasLimitSpec* *GasLimitCalculationSpec*"`

**Checkpoint**: Slice A is independently shippable. Commit and land it before starting Slice B.

---

## Phase 4: Slice B — the atomic gas change (US1)

**Goal**: the node imports the fixture chain past block 36 to head ~89.

**ATOMIC.** EIP-2780, 8037, 8038, 7778, 7954 and 7708 land together. Splitting them ships intermediate
states that are wrong by construction: 2780's parameter table imports 8038's values and 8037's
products; 2780 moves account creation out of intrinsic gas into a runtime charge that must land in
8037's state dimension; 8038 defers two constants to 8037. Tasks below are ordered for implementation
convenience, **not** as merge points.

### Tests first

- [ ] T016 [US1] Write `src/test/scala/com/chipprbots/ethereum/vm/AmsterdamGasAccountingSpec.scala` covering **V1**: block 41 produces header `gasUsed` **183,600** AND receipt `cumulativeGasUsed` **326,947**. This is the decisive test for the slice — a single-counter implementation fails it and passes every weaker test
- [ ] T017 [P] [US1] Add **V2** to `src/test/scala/com/chipprbots/ethereum/vm/AmsterdamGasAccountingSpec.scala`: each of the four `tx-emit-*` transactions halts with `status = 0`, empty bloom, zero logs and exactly 100,000 consumed
- [ ] T018 [P] [US1] Add **V3** to `src/test/scala/com/chipprbots/ethereum/vm/AmsterdamGasAccountingSpec.scala`: block 36 `gasUsed` = 783,360 = 8 × 97,920 and block 37 = 489,600 = 5 × 97,920, both exact multiples of `STATE_BYTES_PER_STORAGE_SET(64) × CPSB(1530)` — the state dimension is the maximum in each
- [ ] T019 [P] [US1] Add **V4** to `src/test/scala/com/chipprbots/ethereum/vm/AmsterdamIntrinsicGasSpec.scala`: transfer to existing EOA stays **21,000**, self-transfer drops to **12,000**, `tx-callrevert` drops 23,201 → **17,201**. All three directions — a blanket change either way is wrong
- [ ] T020 [P] [US1] Add **V5** to `src/test/scala/com/chipprbots/ethereum/ledger/AmsterdamTransferLogSpec.scala`: post-activation transfer blocks reconstruct blooms with popcount 12 where pre-activation blocks 18/30/31 give 0, and block 41's popcount rises 18 → 30

### Core restructure (forge sign-off required on every signature below)

- [ ] T021 [US1] Add `executionGasUsed` and `stateGasUsed` to `BlockResult` in `src/main/scala/com/chipprbots/ethereum/ledger/BlockResult.scala`, keeping `gasUsed` as the **derived** `max(executionGasUsed, stateGasUsed)` so header construction call sites are unchanged. Pre-Amsterdam the new counters stay 0 and `gasUsed` keeps its present meaning
- [ ] T022 [US1] Add the five frame-local state-gas fields to `ProgramState` in `src/main/scala/com/chipprbots/ethereum/vm/ProgramState.scala` per `data-model.md` §3: `stateGasReservoir`, `evmStateGasUsed`, `stateGasFromGasLeft`, `stateGasBaseline`, `stateGasCommitted`
- [ ] T023 [US1] Implement save/restore of `stateGasBaseline` on frame entry and exit in `src/main/scala/com/chipprbots/ethereum/vm/VM.scala`, restoring to baseline on revert and on exceptional halt. **This is what makes V2 come out at `max(100,000, 0)`** rather than `max(100,000, something)` — the restore is load-bearing, not bookkeeping
- [ ] T024 [US1] Convert `ProgramContext.startGas` to the reservoir split in `src/main/scala/com/chipprbots/ethereum/vm/ProgramContext.scala`: `gasLeft = min(TX_MAX_GAS_LIMIT - intrinsic, tx.gas - intrinsic)`, `stateGasReservoir = (tx.gas - intrinsic) - gasLeft`, with `TX_MAX_GAS_LIMIT = 2^24`
- [ ] T025 [US1] Extend `calcTransactionIntrinsicGas` in `src/main/scala/com/chipprbots/ethereum/vm/EvmConfig.scala` with `to: Option[Address]`, `value: UInt256` and `sender: Address` — EIP-2780 cannot decompose the flat 21,000 without them. **forge sign-off before this edit**
- [ ] T026 [US1] Update all five call sites: `src/main/scala/com/chipprbots/ethereum/consensus/validators/std/StdSignedTransactionValidator.scala:290`, `src/main/scala/com/chipprbots/ethereum/ledger/BlockPreparator.scala:409` and `:471`, `src/main/scala/com/chipprbots/ethereum/vm/ProgramContext.scala:21`, `src/main/scala/com/chipprbots/ethereum/domain/SignedTransaction.scala:620`. Every one is on a consensus path
- [ ] T027 [US1] Add `AmsterdamFeeSchedule` to `src/main/scala/com/chipprbots/ethereum/vm/EvmConfig.scala` with the EIP-8038 figures, extending `OsakaFeeSchedule`
- [ ] T028 [US1] Add the Amsterdam branch to the timestamp cascade in `EvmConfig.forBlock(blockNumber, timestamp, blockchainConfig)` in `src/main/scala/com/chipprbots/ethereum/vm/EvmConfig.scala`, after the Osaka branch. **Do not touch** the two-argument `forBlock(blockNumber, blockchainConfig)` overload — that is the block-number cascade ETC dispatches through
- [ ] T029 [US1] Add the state-gas return channel to `SSTORE`, `CreateOp`, `CALL` and `SELFDESTRUCT` in `src/main/scala/com/chipprbots/ethereum/vm/OpCode.scala`, drawing from the reservoir first and then from `gas_left`. **forge sign-off before this edit**
- [ ] T030 [US1] Implement EIP-7778 per-transaction accounting in `src/main/scala/com/chipprbots/ethereum/ledger/BlockPreparator.scala`: `tx_execution_gas = max(tx_gas_used_before_refund - tx_state_gas, calldata_floor)`, accumulating the two block counters separately while receipts keep summing `tx_gas_used`. **This is the V1 divergence** — header maximum, receipt sum
- [ ] T031 [US1] Implement the EIP-7928 creation pre-check ordering in `src/main/scala/com/chipprbots/ethereum/vm/OpCode.scala` — defer reading the computed destination until after sender-balance, nonce-overflow and call-depth checks. **Lands in B although the commitment lands in C** (`research.md` R-2): without the ordering, 8037's creation charge gives wrong answers for creations that fail their pre-checks
- [ ] T032 [US1] Implement EIP-7708 value-transfer logs in `src/main/scala/com/chipprbots/ethereum/ledger/BlockPreparator.scala` and the CALL path, emitted from `0xffffffffffffffffffffffffffffffffffffffFE` as ordinary `TxLogEntry` values so they flow into receipts and blooms unchanged. **In B, not C**: the cost is folded into EIP-2780's `TX_VALUE_COST` of 6,000, so it is not separable from the intrinsic work
- [ ] T033 [P] [US1] Raise code-size and initcode-size limits to 65,536 / 131,072 under the Amsterdam gate (EIP-7954) in `src/main/scala/com/chipprbots/ethereum/vm/EvmConfig.scala`

### Validation

- [ ] T034 [US1] Run V1–V5 and confirm all pass: `sbt "testOnly *Amsterdam*"`
- [ ] T035 [US3] Re-run `sbt "testOnly *SpiralToOlympiaGasTransitionSpec* *OlympiaBlockHeaderValidationSpec* *OlympiaGasLimitSpec* *GasLimitCalculationSpec* *ChainConfigMatrixSpec*"` — green, no assertion edits
- [ ] T036 [US3] Add a test asserting ECIP-1111 treasury crediting in `src/main/scala/com/chipprbots/ethereum/ledger/BlockPreparator.scala` is unaffected. It computes `baseFee * blockHeader.gasUsed`, and under Amsterdam that field is a **maximum**, not a total. ETC never activates Amsterdam so this is inert — but inert-by-argument is exactly how the EIP-1559 elasticity gap was missed, so it gets an assertion
- [ ] T037 [US1] Run the fixture end to end: `cd hive && ./hive --sim devp2p --client fukuii`. Passes when import reaches head ~89 with no `ValidationAfterExecError`. **Make no numeric prediction** for the resulting failure count — a prediction made earlier in this effort was wrong for a knowable reason
- [ ] T038 Run both inertness oracles from `hive/` and confirm they have not moved **in either direction**: rpc-compat (baseline 40/247) and graphql (baseline 2/52). An improvement is the same signal as a regression — it means Amsterdam code ran on a chain that never activated it. Derive counts from `testCases[].summaryResult.pass` verdicts, **not** from fukuii's own logs

**Checkpoint**: US1 delivered. Slice B is one merge.

---

## Phase 5: Slice C — block-level access lists (US4)

**Goal**: the header's access-list commitment is validated against what execution actually accessed.

**Depends on B**, and not only for ordering — T031 already landed B's half of the dependency.

- [ ] T039 [US4] Implement block-level access list accumulation during block execution in `src/main/scala/com/chipprbots/ethereum/ledger/BlockExecution.scala`
- [ ] T040 [US4] Compute the commitment and validate it against `HefPostAmsterdam.blockAccessListHash` in `src/main/scala/com/chipprbots/ethereum/consensus/validators/`
- [ ] T041 [US4] Write a vector for the creation-pre-check ordering path (`research.md` R-2). **The fixture does not cover this** — the vector must be written by hand or taken from `execution-spec-tests`, not extracted from `chain.rlp`
- [ ] T042 [US3] Run `sbt "testOnly *SpiralToOlympiaGasTransitionSpec* *OlympiaBlockHeaderValidationSpec* *OlympiaGasLimitSpec* *GasLimitCalculationSpec* *ChainConfigMatrixSpec*"` — green, no assertion edits

---

## Phase 6: Slice D — builder execution requests (US4, P2)

**Goal**: builder deposit and exit requests contribute to `requestsHash`.

- [ ] T043 [US4] **Read EIP-8282 before writing any code** and close `research.md` R-6: blocks 36 and 37 carry gas totals that are exact multiples of the storage-set state charge (measured), but attributing those to specific builder queue slots is inference. Record the outcome in `research.md`
- [ ] T044 [US4] Add the two builder system contract invocations at the block boundary in `src/main/scala/com/chipprbots/ethereum/ledger/BlockExecution.scala`, following the existing EIP-7002/7251 pattern
- [ ] T045 [US4] Append the two request types to `BlockResult.executionRequests` in canonical order, produced in `src/main/scala/com/chipprbots/ethereum/ledger/BlockExecution.scala`. The `Seq[ByteString]` shape of `type_byte || data` already fits — only the producer list grows
- [ ] T046 [US4] Assert in `src/test/scala/com/chipprbots/ethereum/ledger/AmsterdamBuilderRequestsSpec.scala` that the builder system calls do **not** count against the block gas limit, extending the existing EIP-7002/7251 non-metering rule rather than re-deriving it
- [ ] T047 [US3] Run `sbt "testOnly *SpiralToOlympiaGasTransitionSpec* *OlympiaBlockHeaderValidationSpec* *OlympiaGasLimitSpec* *GasLimitCalculationSpec* *ChainConfigMatrixSpec*"` — green, no assertion edits

---

## Phase 7: Polish & cross-cutting

- [ ] T048 [P] Record in `contracts/gas-accounting.md` → "What the fixture does NOT cover" which of the seven listed behaviours now have written vectors and which remain uncovered. **Do not let this list quietly empty itself** — R-1 stands until a vector exists, and fixture-green is not compliance
- [ ] T049 [P] Fetch the `execution-spec-tests` `tests/amsterdam/` corpus in `.github/workflows/` and add it to the `testComprehensive` tier in `build.sbt` if reachable, per the declared gate gap in `plan.md`. If it is not reachable, say so in the plan rather than leaving the gap implied
- [ ] T050 Run `sbt scalafmtAll`, then `sbt pp` on a clean tree pre-PR
- [ ] T051 Run `sbt testEssential` once at the end of the thread (~24 min, 3,621 tests) to confirm the baseline holds
- [ ] T052 Add the version note and changelog entry — this is consensus-affecting for ETH-family chains (Constitution VII)

---

## Dependencies

```
Setup (T001-T002)
   │
Foundational (T003-T007)  ──────┐
   │                            │
Slice A (T008-T015) ── ships ───┤   independent of B; land it first
                                │
Slice B (T016-T038) ── ships ───┤   ATOMIC — one merge, no partial landing
                                │
                ┌───────────────┴───────────────┐
        Slice C (T039-T042)            Slice D (T043-T047)
                └───────────────┬───────────────┘
                        Polish (T048-T052)
```

Slice A does not depend on the Foundational phase, but the Foundational phase is additive and
ETC-inert, so landing them together is fine. Slices C and D are independent of each other once B lands.

## Parallel opportunities

- **Setup**: T001 and T002 together
- **Slice A**: T008 and T009 together; T010–T013 are the same file and are sequential
- **Slice B tests**: T017, T018, T019, T020 together once T016 establishes the harness
- **Slice B implementation**: T033 is independent; T021–T032 form a dependency chain through the gas
  model and are sequential
- **Slices C and D**: fully parallel once B lands
- **Polish**: T048 and T049 together

## Implementation strategy

**MVP is Slice A.** It is small, it fixes live corruption today, and it has zero dependency on the gas
work. Land it alone.

**Slice B is the feature.** It is atomic by arithmetic, not by preference. Resist any suggestion to
land "just the constants first" — the constant tables are the easy part and should land last *within*
the slice, after the restructure they depend on.

**Slices C and D complete conformance** and are not required to unblock devp2p.

**Two standing cautions, both earned in this effort:**

1. Derive suite results from simulator verdicts, never from grepping fukuii's own logs. That
   methodology produced three wrong diagnoses here, one retracted publicly.
2. Do not predict failure counts. Count what the harness reports after the change.
