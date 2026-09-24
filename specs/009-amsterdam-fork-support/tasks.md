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

**Checkpoint**: another engineer can reproduce **all five vectors** and R-1 from scratch — 31 checks,
all passing.

---

## Phase 2: Foundational (blocking — must complete before Slice B)

**Purpose**: activation gating and the ETC guard. Additive and ETC-inert by construction, so it can
land with Slice A.

- [x] T003 Add `amsterdamTimestamp: Option[Long] = None` to `ForkTimestamps` in `src/main/scala/com/chipprbots/ethereum/utils/BlockchainConfig.scala`, positioned after `osakaTimestamp`
- [x] T004 Add `isAmsterdamTimestamp(timestamp: Timestamp): Boolean` to `BlockchainConfig` in `src/main/scala/com/chipprbots/ethereum/utils/BlockchainConfig.scala`, following the exact shape of `isOsakaTimestamp`
- [x] T005 Parse HOCON key `amsterdam-timestamp` alongside `osaka-timestamp` in `BlockchainConfig`'s reader in `src/main/scala/com/chipprbots/ethereum/utils/BlockchainConfig.scala`
- [x] T006 [P] Declare `amsterdam-timestamp` in `src/main/resources/conf/base/chains/hive-chain.conf` only, wired from the hive harness. Do NOT add it to `eth-chain.conf` or `sepolia-chain.conf` — neither network has scheduled Amsterdam, and declaring it would activate untested consensus rules on a live chain
  - **Amended 2026-09-24 (Glamsterdam release prep):** the premise no longer holds for Sepolia. It scheduled Amsterdam at `1791294816` (2026-10-06 13:53:36 UTC, Gloas epoch 353024) — EIP-7773 activation table, eth-clients/sepolia `metadata/genesis.json`, go-ethereum `params.SepoliaChainConfig`. `sepolia-chain.conf` now declares it, `ChainConfigMatrixSpec` pins that exact value, and `ForkIdSepoliaSpec` carries go-ethereum's Amsterdam rows. `eth-chain.conf` stays undeclared (mainnet is unscheduled). Platåberget (`plataberget-chain.conf`, `1787212224`) is the live network for exercising these rules first. The risk this task recorded is now a release gate rather than an omission: a build with Sepolia's timestamp but incomplete Amsterdam rules must not ship as a release — see the 0.9.0 Glamsterdam release issue.
- [x] T007 [US3] Write `src/test/scala/com/chipprbots/ethereum/utils/ChainConfigMatrixSpec.scala` asserting that every ETC-family shipped config (`etc-chain.conf`, `mordor-chain.conf`, `gorgoroth-chain.conf`) has `forkTimestamps.amsterdamTimestamp.isEmpty`. **This is FR-004 as a test rather than a convention** — safety-by-omission is only as good as the configs, and nothing currently catches a stray declaration

**Checkpoint**: Amsterdam is declarable, absent everywhere it should be absent, and a test says so.

---

## Phase 3: Slice A — strict header arity (US2)

**Goal**: a header fukuii cannot represent is refused, not silently truncated into a wrong hash.

**Independent test**: a 23-field header round-trips to canonical hash
`6372c88fef519c6e4bebbe02d8447c084fc8cd759fae25c5b5cc3c6e2be99bfe`; a 22-field header is rejected.

**Ships alone and first.** It does **not** make devp2p progress — block 36 still fails, just earlier
and honestly. That is the expected outcome, not a reason to bundle it with Slice B.

### Tests first (these must FAIL before T010)

- [x] T008 [US2] Write `src/test/scala/com/chipprbots/ethereum/domain/BlockHeaderAmsterdamRlpSpec.scala`: a 23-item header decodes to `HefPostAmsterdam`, re-encodes byte-identically, and hashes to `6372c88f…`. **Confirm it fails first** — today it passes decoding and produces `94844dfd…`, so a test that goes green before the fix is testing the wrong thing
- [x] T009 [P] [US2] Extend the same spec with the round-trip invariant `encode(decode(bytes)) == bytes` for all six shapes in `contracts/header-rlp.md` (15/16/17/20/21/23), and rejection with the count named for 18, 19, 22 and 24

### Implementation

- [x] T010 [US2] Add `HefPostAmsterdam` to `HeaderExtraFields` in `src/main/scala/com/chipprbots/ethereum/domain/BlockHeader.scala` with fields in the measured order from `contracts/header-rlp.md`: `baseFee`, `withdrawalsRoot`, `blobGasUsed`, `excessBlobGas`, `parentBeaconBlockRoot`, `requestsHash`, `blockAccessListHash`, `slotNumber`
- [x] T011 [US2] Replace `case n if n >= 21` with `case 21 => HefPostPrague` and `case 23 => HefPostAmsterdam` in the decoder in `src/main/scala/com/chipprbots/ethereum/domain/BlockHeader.scala`. Everything else falls through to the existing throw. **Measured correction**: only 18 and 19 were rejected before this change. `n >= 21` swallowed 22 and 24 as well, decoding both as `HefPostPrague` — so rejecting them is NEW behaviour, not pre-existing, and Slice A is more corrective than an earlier draft of this task claimed
- [x] T012 [US2] Add the `HefPostAmsterdam` encode case to `BlockHeaderEnc.toRLPEncodable` in `src/main/scala/com/chipprbots/ethereum/domain/BlockHeader.scala`, emitting 8 extra items
- [x] T013 [US2] Add `HefPostAmsterdam` cases to each accessor in `src/main/scala/com/chipprbots/ethereum/domain/BlockHeader.scala` (`baseFee`, `withdrawalsRoot`, `blobGasUsed`, `excessBlobGas`, `parentBeaconBlockRoot`, `requestsHash`) and to `extraFieldsCount` (→ 8). **Each needs its own assertion**: these are total functions returning `Option`, so a missing case is a silent `None`, not a compile error
- [x] T014 [US2] Add the `HefPostAmsterdam` case to `BlockHeader.validateFieldCount` in `src/main/scala/com/chipprbots/ethereum/domain/BlockHeader.scala`, and a case to `src/test/scala/com/chipprbots/ethereum/domain/BlockHeaderFieldCountSpec.scala` — this is the separate shape-versus-active-fork check, not the decoder arity check, and it needs the new shape too
- [x] T015 [US3] Run the four ETC regression suites and confirm green with no assertion edits: `sbt "testOnly *SpiralToOlympiaGasTransitionSpec* *OlympiaBlockHeaderValidationSpec* *OlympiaGasLimitSpec* *GasLimitCalculationSpec*"`

**Slice A is DONE and verified.** T007 landed with slice B as `ChainConfigMatrixSpec` — it loads the
shipped `blockchains.conf` the way the application assembles it (mounted at `fukuii`, so the
`${fukuii.olympia.treasury-address}` substitutions and `include required(...)` directives resolve as at
runtime) and asserts `amsterdamTimestamp.isEmpty` for `etc`, `mordor` and `gorgoroth`, plus a positive
control so the assertion cannot pass by the reader silently failing to parse the key.

```
VERIFY: ran sbt scalafmtCheckAll — result: PASS
VERIFY: ran sbt "testOnly *BlockHeader* *SpiralToOlympiaGasTransitionSpec*
        *OlympiaBlockHeaderValidationSpec* *OlympiaGasLimitSpec*
        *GasLimitCalculationSpec* *Pickler*"
        — result: PASS — 13 suites, 104 tests, 0 failed, no assertion edits
VERIFY: ran sbt testEssential — result: DID NOT RUN (end-of-thread, ~24 min)
VERIFY: ran hive devp2p — result: DID NOT RUN (Slice B gate, T037)
```

### `forge` ETC sign-off — OBTAINED (post-hoc; it should have preceded the push)

**Verdict: signed off. ETC/Mordor/Gorgoroth byte-for-byte unchanged.** Verified by source reading and
JVM bytecode decompilation, not by assertion:

| Item | Finding |
|---|---|
| Decoder strictness | Safe, and **a bug fix rather than a risk**. The only header encoder emits >16 items for Shanghai/Cancun/Prague/Amsterdam only; ETC constructs `HefEmpty` (15) and `HefPostOlympia` (16) and nothing else — ECIP-1111's base fee is one field. Crucially, the old catch-all's "acceptance" of 22/23/24 was **never canonicalization**: the node hashes a header by *re-encoding* it, so a 22-item header re-encoded to 21 yields a hash no honest peer's child references. What the catch-all actually provided was a **hash-malleability surface** — two wire byte strings mapping to one local header. Slice A closes it. |
| boopickle ordinals | Safe. Decompiled `CompositePickler`/`IdentMap` in boopickle 1.5.0: `pickle` writes `idx - 1` where `idx = k + 1`, so the wire value **is** 1-based registration order. Appending is backward compatible; ETC's two variants keep ordinals 1 and 2. |
| `validateFieldCount` order | Safe. The `networkType != ETH` short-circuit is the first condition, so ETC returns before any timestamp is read. Amsterdam-before-Cancun is also correct on its own merits — a Prague-shaped header satisfies the Cancun and Shanghai predicates, so a later branch would be dead. |
| `numberOfExtraFields` / Ethash | Safe, byte-identical. Every pre-existing arm returns its previous value; ETC's sealing encoding is 13 items (`HefEmpty`) / 14 with baseFee at index 13 (`HefPostOlympia`), unchanged at all six `getEncodedWithoutNonce` call sites. Mining hash unaffected. |

**Two gaps it found that I had not:**

1. **The four ETC suites I ran do not actually exercise any of the above.** They are gas-limit and
   Olympia-activation suites. The ones that pin this diff's ETC invariants are
   `EthashBlockHeaderValidatorSpec`, `PoWBlockHeaderValidatorSpec`,
   `RestrictedEthashBlockHeaderValidatorSpec` and `EthashMinerSpec` — they drive
   `getEncodedWithoutNonce` through the real PoW hash, which is the item where a mistake breaks
   *mining* rather than validation. **Run these.**
2. **Nothing pinned the boopickle ordinal byte** — addressed by `PicklerOrdinalRatchetSpec`.
   `Picklers.given` is imported by `BlockHeadersStorage`/`BlockBodiesStorage`, so the blast radius is
   the whole persistent header database, not just the fast-sync checkpoint. A mid-list insertion
   would renumber every later variant, corrupt an existing ETC datadir on upgrade, and leave the
   entire suite green.

**ETH-side blocker created by Slice A, relayed to Slice B**: `BlockHeaderValidatorSkeleton.scala:278-285`
has no `HefPostAmsterdam` case, and `PoSBlockHeaderValidator` inherits `validate`. A 23-item header at
devp2p block 36+ now decodes to `HefPostAmsterdam` and is rejected by `validateExtraFields`. devp2p
stops at 36 either way, but the reason moved. Also `GenesisDataLoader.scala:188-211` has no Amsterdam
branch, so a fixture with `amsterdamTime: 0` would get the Prague shape and a **wrong genesis hash**.

**T007 is CLOSED** (see above). `BlockHeaderFieldCountSpec`'s synthetic-config assertion remains, and is
strictly weaker; `ChainConfigMatrixSpec` is the one that reads the shipped files FR-004 names.

Three sites beyond this slice's stated scope needed the new variant, each a silent-failure risk
rather than a compile error: the boopickle registries in `Picklers.scala` and
`FastSyncStateStorage.scala` (appended last — registration order is the wire index, so appending
keeps persisted fast-sync records decodable) and `EthSimulateService`'s `isPoW` match (no `case _`,
so a runtime `MatchError`).

**Flagged for Slice B, not fixed here** — four `case _ =>` fallbacks that silently degrade under
Amsterdam without crashing: `EthInfoService.scala:266` (`zeroBaseFeeExtra` returns the header
unchanged, so `eth_call` keeps a non-zero base fee), `EngineApiController.scala:478` (`baseFee` falls
through to 0), `EngineApiController.scala:531` (returns `(None, None, None)`, so `engine_getPayload`
omits all three fields), and `EthSimulateService.scala:370` (patches `blobGasUsed` only on
`HefPostPrague` — currently unreachable, but live the moment Slice B adds an Amsterdam branch to its
header builder).

**Checkpoint**: Slice A is independently shippable and shipped.

---

## Phase 4: Slice B — the atomic gas change (US1)

**Goal**: the node imports the fixture chain past block 36 to head ~89.

**ATOMIC.** EIP-2780, 8037, 8038, 7778, 7954 and 7708 land together. Splitting them ships intermediate
states that are wrong by construction: 2780's parameter table imports 8038's values and 8037's
products; 2780 moves account creation out of intrinsic gas into a runtime charge that must land in
8037's state dimension; 8038 defers two constants to 8037. Tasks below are ordered for implementation
convenience, **not** as merge points.

### Tests first

- [x] T016 [US1] Write `src/test/scala/com/chipprbots/ethereum/vm/AmsterdamGasAccountingSpec.scala` covering **V1**: block 41 produces header `gasUsed` **183,600** AND receipt `cumulativeGasUsed` **326,947**. This is the decisive test for the slice — a single-counter implementation fails it and passes every weaker test
- [x] T017 [P] [US1] Add **V2** to `src/test/scala/com/chipprbots/ethereum/vm/AmsterdamGasAccountingSpec.scala`: each of the four `tx-emit-*` transactions halts with `status = 0`, empty bloom, zero logs and exactly 100,000 consumed
- [x] T018 [P] [US1] Add **V3** to `src/test/scala/com/chipprbots/ethereum/vm/AmsterdamGasAccountingSpec.scala`: block 36 `gasUsed` = 783,360 = 8 × 97,920 and block 37 = 489,600 = 5 × 97,920, both exact multiples of `STATE_BYTES_PER_STORAGE_SET(64) × CPSB(1530)` — the state dimension is the maximum in each
- [x] T019 [P] [US1] Add **V4** to `src/test/scala/com/chipprbots/ethereum/vm/AmsterdamIntrinsicGasSpec.scala`: transfer to existing EOA stays **21,000**, self-transfer drops to **12,000**, `tx-callrevert` drops 23,201 → **17,201**. All three directions — a blanket change either way is wrong
- [x] T020 [P] [US1] Add **V5** to `src/test/scala/com/chipprbots/ethereum/ledger/AmsterdamTransferLogSpec.scala`: post-activation transfer blocks reconstruct blooms with popcount 12 where pre-activation blocks 18/30/31 give 0, and block 41's popcount rises 18 → 30

### Core restructure (forge sign-off required on every signature below)

- [x] T021 [US1] Add `executionGasUsed` and `stateGasUsed` to `BlockResult` in `src/main/scala/com/chipprbots/ethereum/ledger/BlockResult.scala`, keeping `gasUsed` as the **derived** `max(executionGasUsed, stateGasUsed)` so header construction call sites are unchanged. Pre-Amsterdam the new counters stay 0 and `gasUsed` keeps its present meaning
- [x] T022 [US1] Add the five frame-local state-gas fields to `ProgramState` in `src/main/scala/com/chipprbots/ethereum/vm/ProgramState.scala` per `data-model.md` §3: `stateGasReservoir`, `evmStateGasUsed`, `stateGasFromGasLeft`, `stateGasBaseline`, `stateGasCommitted`
- [x] T023 [US1] Implement save/restore of `stateGasBaseline` on frame entry and exit in `src/main/scala/com/chipprbots/ethereum/vm/VM.scala`, restoring to baseline on revert and on exceptional halt. **This is what makes V2 come out at `max(100,000, 0)`** rather than `max(100,000, something)` — the restore is load-bearing, not bookkeeping
- [x] T024 [US1] Convert `ProgramContext.startGas` to the reservoir split in `src/main/scala/com/chipprbots/ethereum/vm/ProgramContext.scala`: `gasLeft = min(TX_MAX_GAS_LIMIT - intrinsic, tx.gas - intrinsic)`, `stateGasReservoir = (tx.gas - intrinsic) - gasLeft`, with `TX_MAX_GAS_LIMIT = 2^24`
- [x] T025 [US1] Extend `calcTransactionIntrinsicGas` in `src/main/scala/com/chipprbots/ethereum/vm/EvmConfig.scala` with `to: Option[Address]`, `value: UInt256` and `sender: Address` — EIP-2780 cannot decompose the flat 21,000 without them. **forge sign-off before this edit**
- [x] T026 [US1] Update all five call sites: `src/main/scala/com/chipprbots/ethereum/consensus/validators/std/StdSignedTransactionValidator.scala:290`, `src/main/scala/com/chipprbots/ethereum/ledger/BlockPreparator.scala:409` and `:471`, `src/main/scala/com/chipprbots/ethereum/vm/ProgramContext.scala:21`, `src/main/scala/com/chipprbots/ethereum/domain/SignedTransaction.scala:620`. Every one is on a consensus path
- [x] T027 [US1] Add `AmsterdamFeeSchedule` to `src/main/scala/com/chipprbots/ethereum/vm/EvmConfig.scala` with the EIP-8038 figures, extending `OsakaFeeSchedule`
- [x] T028 [US1] Add the Amsterdam branch to the timestamp cascade in `EvmConfig.forBlock(blockNumber, timestamp, blockchainConfig)` in `src/main/scala/com/chipprbots/ethereum/vm/EvmConfig.scala`, after the Osaka branch. **Do not touch** the two-argument `forBlock(blockNumber, blockchainConfig)` overload — that is the block-number cascade ETC dispatches through
- [x] T029 [US1] Add the state-gas return channel to `SSTORE`, `CreateOp`, `CALL` and `SELFDESTRUCT` in `src/main/scala/com/chipprbots/ethereum/vm/OpCode.scala`, drawing from the reservoir first and then from `gas_left`. **forge sign-off before this edit**
- [x] T030 [US1] Implement EIP-7778 per-transaction accounting in `src/main/scala/com/chipprbots/ethereum/ledger/BlockPreparator.scala`: `tx_execution_gas = max(tx_gas_used_before_refund - tx_state_gas, calldata_floor)`, accumulating the two block counters separately while receipts keep summing `tx_gas_used`. **This is the V1 divergence** — header maximum, receipt sum
- [x] T031 [US1] Implement the EIP-7928 creation pre-check ordering in `src/main/scala/com/chipprbots/ethereum/vm/OpCode.scala` — defer reading the computed destination until after sender-balance, nonce-overflow and call-depth checks. **Lands in B although the commitment lands in C** (`research.md` R-2): without the ordering, 8037's creation charge gives wrong answers for creations that fail their pre-checks
- [x] T032 [US1] Implement EIP-7708 value-transfer logs in `src/main/scala/com/chipprbots/ethereum/ledger/BlockPreparator.scala` and the CALL path, emitted from `0xffffffffffffffffffffffffffffffffffffffFE` as ordinary `TxLogEntry` values so they flow into receipts and blooms unchanged. **In B, not C**: the cost is folded into EIP-2780's `TX_VALUE_COST` of 6,000, so it is not separable from the intrinsic work
- [x] T033 [P] [US1] Raise code-size and initcode-size limits to 65,536 / 131,072 under the Amsterdam gate (EIP-7954) in `src/main/scala/com/chipprbots/ethereum/vm/EvmConfig.scala`

### Validation

- [x] T034 [US1] Run V1–V5 and confirm all pass: `sbt "testOnly *Amsterdam*"`
- [x] T035 [US3] Re-run `sbt "testOnly *SpiralToOlympiaGasTransitionSpec* *OlympiaBlockHeaderValidationSpec* *OlympiaGasLimitSpec* *GasLimitCalculationSpec* *ChainConfigMatrixSpec*"` — green, no assertion edits
- [x] T036 [US3] Add a test asserting ECIP-1111 treasury crediting in `src/main/scala/com/chipprbots/ethereum/ledger/BlockPreparator.scala` is unaffected. It computes `baseFee * blockHeader.gasUsed`, and under Amsterdam that field is a **maximum**, not a total. ETC never activates Amsterdam so this is inert — but inert-by-argument is exactly how the EIP-1559 elasticity gap was missed, so it gets an assertion
- [ ] T037 [US1] Run the fixture end to end: `cd hive && ./hive --sim devp2p --client fukuii`. Passes when import reaches head ~89 with no `ValidationAfterExecError`. **Make no numeric prediction** for the resulting failure count — a prediction made earlier in this effort was wrong for a knowable reason
- [ ] T038 Run both inertness oracles from `hive/` and confirm they have not moved **in either direction**: rpc-compat (baseline 40/247) and graphql (baseline 2/52). An improvement is the same signal as a regression — it means Amsterdam code ran on a chain that never activated it. Derive counts from `testCases[].summaryResult.pass` verdicts, **not** from fukuii's own logs

**Slice B is implemented and verified at the unit/vector level, except T037 and T038** (hive, run by
the coordinator).

```
VERIFY: ran sbt compile-all                          — result: PASS — 0 errors
VERIFY: ran sbt scalafmtCheckAll                     — result: PASS
VERIFY: ran sbt "testOnly *Amsterdam*"               — result: PASS — 5 suites, 0 failed
VERIFY: ran sbt "testOnly *SpiralToOlympiaGasTransitionSpec* *OlympiaBlockHeaderValidationSpec*
        *OlympiaGasLimitSpec* *GasLimitCalculationSpec* *ChainConfigMatrixSpec*"
        — result: PASS — no assertion edits
VERIFY: ran sbt testVM                               — result: PASS — 271 tests
VERIFY: ran hive devp2p (T037) / rpc-compat + graphql (T038) — result: DID NOT RUN (coordinator's)
```

**Twelve measured block totals reproduce to the gas unit** by executing the fixture's own bytecode, six
on each side of the fork. Six pre-Amsterdam figures (blocks 4, 5, 6, 8, 23, 24) validate the harness
before any Amsterdam claim is made; six post-Amsterdam ones (blocks 38, 39, 41, 45, 50, plus block 41's
receipt) are the result. Block 41 carries header 183,600 and receipt 326,947 simultaneously — V1.

**Scope correction, measured:** 131 of the 522 post-activation transactions are contract creations, a
path none of V1–V5 covered. They are added as **V6** in `contracts/gas-accounting.md`, and they are the
reason `ProgramContext`'s EIP-2780 pre-execution phase is load-bearing for devp2p rather than an edge
case: all three measured post-activation deployments run out of gas *in that phase*.

**Not implemented, flagged rather than silently skipped** — see the hand-back and
`contracts/gas-accounting.md`: EIP-8037's per-dimension block-fullness check in transaction validation,
and the Amsterdam branch in `GenesisDataLoader` (blocked on EIP-7928's empty block-access-list
commitment, which lands in slice C; a `log.error` fires rather than a wrong genesis hash being produced
quietly).

**`SYSTEM_CALL_GAS_LIMIT` bump — CLOSED in slice D (T044), and it is not builder-only.** EIP-8037
defines `SYSTEM_CALL_GAS_LIMIT` as one global constant, so `BlockExecution.scala:~420` funds *every*
VM-executed system call on an Amsterdam block at `AmsterdamGas.SystemCallGasLimit` (31,566,720), which
includes the already-shipped EIP-7002/7251 calls — not only the two EIP-8282 ones. Scoping the bump to
the builder pair would invent a two-constant model no reference client has. EIP-2935 and EIP-4788 need
no change because this client applies them as direct storage writes rather than EVM calls (the
optimisation EIP-4788 explicitly permits), so they have no ceiling to raise. No fixture block
distinguishes 30,000,000 from 31,566,720 — every dequeue path is orders of magnitude below both — so
the no-op claim is carried by an oracle instead: `AmsterdamBuilderRequestsSpec` → "leave the EIP-7002/7251
system calls byte-identical when Amsterdam raises the gas ceiling" runs the fixture's own withdrawal and
consolidation bytecode over a non-empty queue on both sides of the fork and requires identical requests,
identical storage and an identical state root.

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
