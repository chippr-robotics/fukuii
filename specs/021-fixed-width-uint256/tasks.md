---
description: "Task list for spec 021: the fixed-width UInt256, the in-place word stack and the mutable EVM Memory"
---

# Tasks: Fixed-Width UInt256 (allocation-light EVM arithmetic, byte-identical)

**Input**: [spec.md](spec.md), [plan.md](plan.md), [research.md](research.md), [data-model.md](data-model.md),
[contracts/](contracts/), [quickstart.md](quickstart.md), and GT-1 part A
([gt1-impact-analysis-part-a.md](gt1-impact-analysis-part-a.md)).

**Prerequisite**: spec 020 has landed in code. Code slices (S1 onward) start after 0.11.0, beside spec 018 Track C
(plan, Sequencing). Phase 1 and S0 may start earlier.

## How this list is organised

**Phases.** The phases follow plan.md's slices in order. Each slice is one PR to `staging`.

| Phase | Content |
|---|---|
| 1 | Prerequisites (no code) |
| 2 | S0, golden pins |
| 3 | S1, the type and GT-2 |
| 4 | S2, the T-005 conversions |
| 5 | S3, the word stack |
| 6 | S4, mutable `Memory` |
| 7 | Final runs and closure |

**Each slice phase has:**
- a goal;
- its review point, marked **BEFORE CODE** (RP-1 to RP-5; reviewer tags **[beacon]**, **[forge]**, **[mithril]**);
- tests first;
- implementation;
- an exit gate.

**Two kinds of test come first:**
- **Pin tests**: byte-identity pins. They are green on the pre-slice tree and must stay green, so by design they cannot
  fail first: the slice must not change any output. A pin that turns red after a code commit means the code is wrong.
- **Failing-first tests**: tests of what the slice adds, such as new members, allocation budgets, and the DC-3 and
  bytecode checks. They are **red on the pre-slice tree** and turn green with the slice's code. Confirm the red run
  before writing the code.

**Story labels** (spec, User Scenarios). Prerequisite, review and gate tasks without a natural story carry none.

| Label | Story | Priority |
|---|---|---|
| US1 | Every state root is unchanged | P1 |
| US2 | Trace re-execution allocates far less | P1 |
| US3 | The guest compiles the type | P2 |

**Path prefixes:**

| Prefix | Path |
|---|---|
| `M/` | `src/main/scala/com/chipprbots/ethereum/` |
| `T/` | `src/test/scala/com/chipprbots/ethereum/` |
| `IT/` | `src/it/scala/com/chipprbots/ethereum/` |
| `D/` | `specs/021-fixed-width-uint256/` |

**Commands per commit** (quickstart §1): `sbt compile-all`, `sbt scalafmtAll`, the targeted `testOnly`, and from S1 on
`scripts/spec021/check_uint256_bigint.sh`. `testEssential` runs once per slice, at its exit gate.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (a different file, and no dependency on an incomplete task).
- **[Story]**: the user story the task serves (US1, US2, US3).

---

## Phase 1: Prerequisites (no code; runs beside S0)

**Purpose**: the data sources and decisions the gates need. T006 and T008 **block S1**.

- [ ] T001 [P] Freeze P-1, a post-Amsterdam ETH range: Platåberget if glamsterdam-devnet-8 still produces blocks, else post-Amsterdam Sepolia; N = 2,000 with ≥ 200 blocks of ≥ 30 Mgas, per spec 020 R-4. Record the bounds, the parent hash, the state root, the frozen datadir and the SHA-256 in D/research.md §B, and add the range's case to scripts/spec021/differential.sh (a copy of scripts/spec020/differential.sh with the Ephemery, Mordor and P-1 cases)
- [ ] T002 [P] Produce P-2 on the stopped Mordor node: `java -jar <jar> mordor export mordor-0-10000000.rlp.gz 0 10000000`. Record its SHA-256, and whether it includes genesis, in D/research.md §B
- [ ] T003 [P] Check P-3 on fukuii-fc: `sha256sum ~/fukuii-artifacts/etc-0-5100000.rlp.gz` = `8c52ee9dcb20e733065fad51a2cf289cc300546f61f13371748cc442ce1f89e8`. Record whether the file includes genesis (quickstart §4.2 VERIFY) in D/research.md §B
- [ ] T004 [forge] P-6 step 1: at a **planned stop** of the fukuii-fc ETC node, copy its datadir: state at head H, root verified, never a live copy. Record H, the root and the copy's path in D/research.md §B
- [ ] T005 P-6 step 2: at a later planned stop, run `java -jar <jar> etc export etc-head-<H+1>-<H+5000>.rlp.gz <H+1> <H+5000>`, and record the range and its SHA-256 in D/research.md §B (depends on T004)
- [ ] T006 **S1 blocker.** P-6 step 3 (forge R4-a): run gate row 10b **once with jar A** (the pre-S1 tree) into a fresh copy of T004's datadir. It must report `5000 imported, 0 already present`. Record its `[IMPORT-TIMING]` p50/p99 as S8's A figure in D/research.md §B row 7b (depends on T005)
- [ ] T007 [P] Check P-4: the last 64 blocks of each fixed range have state in the differentials' final datadirs. If they do not, record the N that do, in D/research.md §B
- [ ] T008 **S1 blocker (before GT-2).** Add GT-1 part B (forge) to the spec folder as D/gt1-impact-analysis-part-b.md, redacted per the user's private-until-released decision. forge prepares the redacted copy; the user approves it before it is committed
- [ ] T009 [P] Resolve the VERIFY items RP-2 needs and record them in D/research.md:
  - the Scala 3.9 standard library's `BigInt.hashCode` (R-6);
  - holiman/uint256's licence and test-data tables at a pinned commit (R-9);
  - go-ethereum's `newstack`, `GetPtr` and `dataCopy` (R-10, R-11);
  - the current ETC block gas limit (R-11.3 (c)).

**Checkpoint**: T006 and T008 are done, so S1's gates can run.

---

## Phase 2: S0, golden pins (tests only)

**Goal**: pin every byte this spec must not change (codecs, opcode edge rows, forge's ETC rows, the opcode tables,
trace output) before any representation changes, and take the GT-4 baseline.

**Before code**:
- [x] T010 **BEFORE CODE (RP-1)** [forge] [beacon]: sign the S0 list in D/data-model.md §9 (the codec goldens, O01–O15, F01–F07); record the sign-off in D/plan.md Gates

### Pin tests (the whole slice; green on `staging`)

- [x] T011 [P] [US1] Create the ETC (config, block) matrix fixture in T/vm/EtcForkMatrix.scala: the `etc-chain.conf` forks with one block per era (below 1.15M; 1.15M–2.5M; 2.5M–8.772M; Atlantis; Agharta; Phoenix; Magneto; Mystique; Spiral; Olympia at a test block)
- [x] T012 [P] [US1] Codec goldens and P07–P09 in T/domain/UInt256GoldenBytesSpec.scala. Bytes are literals captured from the pre-swap tree:
  - Account RLP at nonce and balance ∈ {0x7f, 0x80, 0xff, 0x100, 2²⁵⁵, 2²⁵⁶−1};
  - the CREATE address at nonce ∈ {1, 0x7f, 0x80, 0x100, 2⁶⁴−2};
  - access-list key bytes → tx hash;
  - storage-trie key hashes for slots {0, 1, 2²⁵⁵, 2²⁵⁶−1};
  - JSON quantities {0, 0xf, 2²⁵⁵, 2²⁵⁶−1};
  - `toHexString`/`toString` strings;
  - the `InvalidJump` string.
- [x] T013 [P] [US1] O01–O15 and FR-010's truncation invariants (JUMP `isValidInt`, BLOBHASH bounds, `sliceBytes` min-before-truncate, CALL's requested gas, RETURNDATACOPY saturation) in T/vm/OpCodeEdgeRowsSpec.scala
- [x] T014 [US1] F01 (`calcMemCost` vectors at exact values, including the `BigInt` sentinel 2²⁵⁵−1) and F02 (`wordsForBytes` at 0, 1, 32, 33, 2⁶³−1, 2⁶³, 2⁶⁴−1, 2⁶⁴, 2²⁵⁶−1) in T/vm/OpCodeEdgeRowsSpec.scala (after T013: same file)
- [x] T015 [US1] F03, F04 and F05 in T/vm/EtcOpCodeEdgeRowsSpec.scala (uses T011):
  - F03: SSTORE below Phoenix, at Phoenix and at Magneto; keys and values {0, 1, 2²⁵⁵, 2²⁵⁶−1}; gas, refund and storage root;
  - F04: pre-EIP-150 CALL, CALLCODE and DELEGATECALL requests of 2⁶⁴+5 and MaxValue on an ETC Homestead block: OOG in `calcGas`, stack unchanged, the full request charged;
  - F05: the Olympia opcodes MCOPY, CLZ, BASEFEE, TLOAD and TSTORE on the ETC table.
- [x] T016 [P] [US1] F06, the opcode-table golden: (byte, name, delta, alpha, constant gas) for each ETC fork (`forBlock` at each boundary block, Olympia at a test block) and each ETH fork, in T/vm/OpCodeTableGoldenSpec.scala and src/test/resources/spec021/opcode-tables.golden
- [x] T017 [US1] F07: extend "before Amsterdam every instruction's state-gas delta is zero" to T011's ETC matrix in T/vm/OpCodeContractSpec.scala (uses T011)
- [x] T018 [P] [US2] Trace goldens in T/vm/TraceGoldenSpec.scala and src/test/resources/trace-goldens/:
  - formats: structLog with memory capture off **and** on, callTracer, prestateTracer, and `trace_*` vmTrace;
  - over a frozen fixture set with these error steps: OOG, InvalidJump, underflow, RETURNDATACOPY overflow, CREATE's initcode limit and preflight OOG, the EIP-8037 shortfall on a CALL, a CALL failing without revert, and a reverting CALL.
- [x] T019 [P] [US2] Add an allocated-bytes column (`ThreadMXBean.getCurrentThreadAllocatedBytes` around each case run) to IT/ethtest/BlockchainTestBench.scala
- [ ] T020 [US2] Freeze the GT-4 bench fixtures with their opcode counts: `loopMul_d2g0v0_Cancun`, plus one arithmetic-dense, one memory-dense and one DIV/MULMOD/EXP-dense GeneralStateTest. Record the S0 baseline (jar A) of D/research.md §B rows 4–8, 7c, 11, 12 and 15 per D/quickstart.md §5 (depends on T019; row 7b comes from T006)
  - **BLOCKED (2026-11-07).**
    - The fixtures are not on the S0 host. `loopMul_d2g0v0_Cancun` comes from hive's Cancun-era fixtures, and the local ethereum/tests checkout stops at Berlin/Istanbul. No substitute has been chosen.
    - The jar-A baseline needs the measurement host with every node stopped (D/quickstart.md §0).
    - T019's `alloc_bytes` column is ready for it.

### Exit gate

- [ ] T021 S0 exit:
  - `sbt "testOnly *UInt256GoldenBytesSpec *OpCodeEdgeRowsSpec *EtcOpCodeEdgeRowsSpec *OpCodeTableGoldenSpec *OpCodeContractSpec *TraceGoldenSpec"` green on `staging`;
  - `testEssential` once;
  - the PR merged with forge and beacon approval;
  - recorded in D/research.md §B, column S0.
  - **Status (2026-11-07, branch `feat/021-s0`):** done on the branch:
    - `compile-all`;
    - the testOnly above plus `*UInt256Spec* *OpCode* *Stack*`: 18 suites, 466 tests, all green;
    - `scalafmtAll`.

    Open: `testEssential`, the PR, and the §B record (T020 is blocked).

**Checkpoint**: every output the later slices must preserve is pinned.

---

## Phase 3: S1, the limb type behind the same API, with GT-2

**Goal**: `UInt256` on four `Long` limbs, with no `BigInt` on any arithmetic path (FR-001, FR-009, DC-3 to DC-5).
GT-2 has zero differences against the `BigInt` oracle (FR-002), and the DC-3 check covers `domain/` (FR-004).

**Blocked by**: T006, T008, T009 and T021.

**Before code**:
- [ ] T022 **BEFORE CODE (RP-2)** [beacon] [forge] [mithril]: sign D/data-model.md §1–§3 (limb rules, kernels, the 512-bit operations), D/research.md R-2 to R-7, and GT-2's named exclusions; mithril signs the DC-4 shape. Record in D/plan.md Gates

### Pin tests (green on the `BigInt` type and unchanged after the swap)

- [ ] T023 [P] [US1] Copy today's M/domain/UInt256.scala verbatim, renamed, to T/domain/oracle/BigIntUInt256.scala
- [ ] T024 [P] [US1] Add the `DifferentialTest` tag in T/testing/Tags.scala, and `-l DifferentialTest` to the `testEssential` and `testStandard` aliases in build.sbt
- [ ] T025 [US1] GT-2 generators and named rows in T/domain/UInt256DifferentialRows.scala (depends on T023):
  - the generator strata from research R-12: uniform, bit-length, limb patterns, anchors ± δ, small values, `apply` inputs;
  - the rows E01–E35, C01–C12, P01–P06, P10–P16 and L01–L09, with L07 and L08 constructed per R-12.
- [ ] T026 [US1] Two suites, each asserting `signum >= 0` and `bitLength <= 256` on every word (S1) (depends on T024, T025):
  - the fast suite, T/domain/UInt256DifferentialSpec.scala: ≥ 10⁴ cases per operation, `VMTest`;
  - the full suite, T/domain/UInt256DifferentialFullSpec.scala: ≥ 10⁷ cases per operation, `DifferentialTest`, fixed seeds, shrinking off.
- [ ] T027 [P] [US1] Fill `UInt256Spec`'s GT-1 gaps in T/domain/UInt256Spec.scala:
  - `<<`, `>>` and `sshift` on shifts 0–255;
  - `toLong`, `toHexString`, `toString`, `hashCode`;
  - the non-`UInt256` `equals`, `max`, `apply(Boolean)`, negative `apply(Long)`, `byte2UInt256`;
  - an `smod` property, and SDIV(−2²⁵⁵, −1);
  - replace the `"/"` test, which exercises `*`.
- [ ] T028 [US1] Add two CI steps to .github/workflows/ci.yml (depends on T026, T030):
  - the full GT-2 suite, nightly and on PRs touching M/domain/UInt256* or M/vm/;
  - `scripts/spec021/check_uint256_bigint.sh`, on every PR.

### Failing-first tests (red on the `BigInt` type)

- [ ] T029 [P] [US2] Allocation budget in T/domain/UInt256AllocationSpec.scala, over 10⁵ operations measured with `ThreadMXBean`:
  - `+`, `-`, `*`, `&` and `<<` allocate ≤ 64 bytes per result;
  - `div`, `mulmod` and `**` allocate ≤ 64 bytes plus one fixed scratch array.
- [ ] T030 [P] [US3] The DC-3 usage check, `domain/` half: scripts/spec021/check_uint256_bigint.sh, with its self-test scripts/spec021/test_check_uint256_bigint.sh, in which planted references must fail. It is red on today's M/domain/UInt256.scala
- [ ] T031 [P] [US3] The bytecode check scripts/spec021/check_no_closures.sh: no `LambdaMetafactory` invokedynamic and no lazy-val initialisers in the `UInt256` classes (R-1, R-16). It is red today, because of the by-name `zeroCheck` and the lazy `bytes` and `signedN`

### Implementation

- [ ] T032 [US1] D1: retire `/`. Replace `UInt256.MaxValue / 2` with a constant built with `div` (P15: exactly 2²⁵⁵−1) in M/vm/EvmConfig.scala, and remove `/` from M/domain/UInt256.scala
- [ ] T033 [US1] Inline kernels with inline continuations, per D/data-model.md §1.4 and §2.1, in M/domain/UInt256Kernels.scala: add, sub, truncated mul, neg, not, and, or, xor, shl, shr, sar, signExtend, byte, lt, gt, slt, sgt, eq, isZero, numberOfLeadingZeros, fillingAdd
- [ ] T034 [US1] Array kernels per research R-2 to R-4 and D/data-model.md §2.2–§3, in M/domain/UInt256Kernels.scala (after T033: same file):
  - Algorithm D `udivrem`: the `divlu` 2-by-1 step, D3 correction, D6 add-back, normalisation by 0;
  - div, mod, sdiv, smod;
  - addmod (reduce first), mulmod (8-limb product), exp (square-and-multiply).
- [ ] T035 [US1] Rewrite M/domain/UInt256.scala on four limbs, per D/data-model.md §1 and D/contracts/uint256-api.md (depends on T032–T034):
  - constants;
  - the `apply` overloads: sign-extending `Long`, masking `BigInt`, the `ByteString` fold with its > 32-byte throw;
  - an unsigned `toBigInt`, `bytes` built per call, `toInt`/`toLong`/`toByte`;
  - `compare`/`slt`/`sgt`, and `equals` with the cooperative branch;
  - `hashCode` = `BigInt.hashCode`, from the limbs;
  - the string renderers;
  - the shift members with EVM semantics (D2);
  - no `lazy val`.
- [ ] T036 [US1] Ordering and `CanEqual` per research R-7 in M/domain/UInt256.scala: add the `given Ordering[UInt256]` only if `summon` resolves without ambiguity and E205 stays silent; record the outcome in D/research.md R-7
- [ ] T037 [US3] Add the DC-3 allow-list fences to M/domain/UInt256.scala and M/domain/UInt256Kernels.scala, so that T030 and T031 turn green. If T031 cannot pass, record R-1's fallback (array kernels) in D/research.md §B row 3, and have mithril review it
- [ ] T038 [P] Write the ADR docs/adr/vm/VM-NNN-fixed-width-uint256.md (next free number): the representation, the kernel sharing, and the word stack and memory to come. Add its rows to docs/adr/vm/README.md and docs/adr/README.md

### Exit gate

- [ ] T039 [US1] GT-2 full: 0 differences at ≥ 10⁷ per operation; scoverage 100% of M/domain/UInt256*.scala statements and branches from GT-2, with L07 reaching D6. Record D/research.md §B rows 1–3
- [ ] T040 [US1] GT-3 rows 1–10b at S1's tip, per D/quickstart.md §4. [forge] signs ETC and [beacon] ETH; record §B row 13:
  - ETH: the `ethereum-tests-nightly`, `eest-amsterdam` and `hive-consensus` dispatches; the Ephemery and P-1 differentials;
  - ETC: the ETC-typed legacy suite, Mordor fixed and from genesis, ETC 0–5.1M, and row 10b.
- [ ] T041 [US2] GT-4 at S1's tip, D/research.md §B rows 4–8, 7b and 7c. S8 is recorded, not judged. **The release rule** applies until S2 merges: no release, image or deployment from a `staging` commit that has S1 without S2
- [ ] T042 S1 exit: S0's pins unchanged, `testEssential` once, `sbt formatCheck` green; the PR merged with forge, beacon and mithril approval

**Checkpoint**: the type is byte-identical and allocation-light, and the DC-3 domain check is green (US1, US3).

---

## Phase 4: S2, the T-005 hot-path conversions

**Goal**: remove the hot `BigInt` round trips that S1 made allocate, in the GT-1 ranking, with forge's S7 rules; then
judge S8 (FR-006, FR-010).

**Before code**:
- [ ] T043 **BEFORE CODE (RP-3)** Record both sign-offs in D/plan.md Gates:
  - [forge] signs `calcMemCost` in today's order with the exact sentinel (C9), `wordsForBytes`' `Long` path iff n < 2⁶³ (C11), and SLOAD/SSTORE converting once per method (C12);
  - [beacon] signs JUMP, BLOBHASH, CLZ, SAR and the tracer rendering.

### Pin tests

S0's O and F rows and its trace goldens (T013–T018) already pin every call site S2 touches. There are no new pins.

### Failing-first tests (red on S1's tip)

- [ ] T044 [P] [US1] GT-2 rows and properties for the new members `isValidInt`, `isNegative`, `numberOfLeadingZeros` and `toHexQuantity`, at ≥ 10⁷ each in the full suite, in T/domain/UInt256DifferentialRows.scala
- [ ] T045 [P] [US2] Hot-path allocation in T/vm/HotPathAllocationSpec.scala (`ThreadMXBean` and a class histogram): JUMP to a valid destination, `calcMemCost` and `wordsForBytes` on small operands, and one structLog stack render allocate no `BigInt` or `BigInteger`
- [ ] T046 [P] [US3] The DC-3 check, opcode half: add the arithmetic-region rule to scripts/spec021/check_uint256_bigint.sh and its self-test. It is red while CLZ uses `toBigInt` and SAR uses `toSign`

### Implementation (one commit each, with its bench measurement in the commit message)

- [ ] T047 [US1] Add `isValidInt`, `isNegative`, `numberOfLeadingZeros` and `toHexQuantity` (D/data-model.md §1.4–§1.5) to M/domain/UInt256.scala, so that T044 turns green
- [ ] T048 [US1] `calcMemCost` typed on `UInt256`, in today's exact order, in M/vm/EvmConfig.scala:
  1. size zero, tested on the full word;
  2. an operand ≥ 2³¹ → the sentinel;
  3. the `Long` sum, and > 2³¹−1 → the sentinel;
  4. ≤ memSize → 0;
  5. otherwise `c(m) − c(memSize)`.
- [ ] T049 [US1] SLOAD and SSTORE in M/vm/OpCode.scala: one `toBigInt` per method, passed to `load`, `store`, `StorageKey`, `addressAndKey` and `BlockAccessRecorder.slot`. The reverse conversions are unchanged
- [ ] T050 [US1] JUMP and JUMPI: `isJumpDestination` via `isValidInt`, in M/vm/OpCode.scala (after T049: same file)
- [ ] T051 [US1] `wordsForBytes(UInt256)` in M/vm/package.scala, with its callers in M/vm/OpCode.scala: the `Long` path iff w1 = w2 = w3 = 0 and w0 ≥ 0, the `BigInt` path otherwise
- [ ] T052 [P] [US1] In M/vm/WorldStateProxy.scala, `getBalance`'s `UInt256(a.balance)` becomes `a.balance` (P16)
- [ ] T053 [US2] Render structLog stacks and VmTracer's `exPush` with `toHexQuantity`, in M/vm/StructLogTracer.scala and M/vm/VmTracer.scala, and correct the stale "toSeq = underlying.reverse" comment
- [ ] T054 [US1] In M/vm/OpCode.scala and M/domain/UInt256.scala:
  - CLZ via `numberOfLeadingZeros`;
  - SAR via `isNegative`, and retire `toSign`;
  - BLOBHASH bounds via `isValidInt` (O05).
- [ ] T055 [US3] Add the arithmetic-region fence to M/vm/OpCode.scala, so that T046 turns green

### Exit gate

- [ ] T056 [US1] Both GT-2 suites green and S0's pins unchanged; GT-3 rows 1–10b at S2's tip; [forge] and [beacon] sign. Record D/research.md §B row 13
- [ ] T057 [US2] **S8 judged**: A = pre-S1, B = S2's tip, on the Mordor fixed range and the ETC head window, p50 and p99 within 10% (§B rows 7 and 7b).
  - Record the storage-boundary counts (§B rows 9–10).
  - A breach is answered with more T-005 conversions, measured.
  - If S8 is not met before the next release cut, revert S1 (the release rule).
- [ ] T058 S2 exit: `testEssential` once and `sbt formatCheck` green; the PR merged

**Checkpoint**: S1 and S2 can be released together. This is the first release unit (Implementation Strategy).

---

## Phase 5: S3, the word stack and the opcode stack data flow

**Goal**: the in-place word stack: an `Array[Long]` with 4 limbs per slot, peek before pop, validate before mutate,
tracer snapshots, and arithmetic opcodes with no per-opcode word allocation (FR-007, DC-7, DC-8). `Memory` is untouched.

**Before code**:
- [ ] T059 **BEFORE CODE (RP-4)** In D/rp4-stack-tables.md:
  - [beacon] presents three tables: the 64 pop sites (peek or consume), the 22 `withError` sites with the stack each returns today, and the tracer contract;
  - [forge] signs the ETC-only paths: pre-EIP-150 CALL gas read unbounded (C13), pre-Phoenix SSTORE, the Olympia opcodes, and W04–W06 over the ETC matrix (C14).

### Pin tests (green on S2's tip, unchanged after)

- [ ] T060 [P] [US1] W04 in T/vm/OpCodeContractSpec.scala: `calcGas`, `stateGasDelta`, `recordReadsBeforeOutOfGas` and `availableInContext` leave the stack bit-identical. This holds for every opcode of every table, on full-range words, over the ETH builders and T011's ETC matrix
- [ ] T061 [P] [US1] W05 in T/vm/ErrorPathStackSpec.scala: each of the 22 `withError` sites returns today's stack, on both sets of configs
- [ ] T062 [P] [US2] W06 in T/vm/TracerPreStepStackSpec.scala: the tracers' `prevState.stack` is the pre-step stack for SLOAD, SSTORE, BALANCE, EXTCODE*, SELFDESTRUCT, MSTORE, MSTORE8, TLOAD, TSTORE and MCOPY, on both sets of configs
- [ ] T063 [P] [US2] W07 in T/vm/StateGasShortfallStackSpec.scala: after an EIP-8037 shortfall following `exec`, `nextState.stack` equals the pre-instruction stack
- [ ] T064 [P] [US1] W01 in T/vm/PushImmediateSpec.scala: PUSH1..PUSH32 truncated by the end of the code read the missing low-order bytes as zero

### Failing-first tests (red on S2's tip)

- [ ] T065 [P] [US1] W02, W03 and W08–W10 in T/vm/StackSpec.scala, against the `Vector` model, through the new API: `Stack.of`, `peek`, mutators returning `Unit`, `snapshot`, DUP copy independence, growth from 16 to 1024, lenient pop and push
- [ ] T066 [P] [US2] The stack-vs-value kernel differential: each in-place kernel against the `UInt256` member, on GT-2's generators. Fast (`VMTest`) in T/vm/WordStackKernelDifferentialSpec.scala; full (`DifferentialTest`, ≥ 10⁷ per op) in T/vm/WordStackKernelDifferentialFullSpec.scala
- [ ] T067 [P] [US2] Allocation in T/vm/WordStackAllocationSpec.scala: ADD, MUL, DIV, MULMOD, EXP, SHL, DUP, SWAP and PUSH32 on the word stack allocate 0 bytes inside `Stack`, excluding `ProgramState`'s copy
- [ ] T068 [P] [US3] Extend scripts/spec021/check_no_closures.sh to M/vm/Stack*.class and the arithmetic opcode classes (`ADD$` … `CLZ$`)

### Implementation

- [ ] T069 [US1] Add the read-only accessors (`peek`, `isZeroAt`, `int31At`, `isValidIntAt`, `byteSizeAt`, `addressAt`, `toSeq`, `snapshot`) to the current list-backed M/vm/Stack.scala, and move every pop-as-peek site of T059's table to them in M/vm/OpCode.scala. CALL's gas word is read with `peek(i).toBigInt` (C13)
- [ ] T070 [US1] The swap:
  - M/vm/Stack.scala on the word array (D/data-model.md §5): mutators returning `Unit`, checkpoint and rollback, `equals`/`hashCode`/`toString` as today;
  - the sharing documentation in M/vm/ProgramState.scala;
  - `stepWithStack` and `jumpWithStack` without the stack parameter.
- [ ] T071 [US2] The pre-step stack snapshot in M/vm/VM.scala, taken when any tracer is set, and the `onStep` contract text in M/vm/ExecutionTracer.scala
- [ ] T072 [US1] In M/vm/OpCode.scala: every `exec` follows Rule V, and `OpCode.execute` takes the EIP-8037 checkpoint and rolls it back
- [ ] T073 [US1] Migrate the vm test files to `Stack.of` and snapshot-before-execute: T/vm/Generators.scala, OpCodeFunSpec, OpCodeGasSpec, OpCodeGasSpecPostEip2929, ShiftingOpCodeSpec, Push0Spec, CreateOpcodeSpec, CallOpFixture, OpCodeContractSpec, LargeMemoryExpansionSpec, BlockAccessRecordingSpec, AmsterdamOpCodesSpec, AmsterdamCodeReadSurchargeSpec, StructLogTracerSpec
- [ ] T074 [US2] In-place data flow, in M/vm/OpCode.scala, M/vm/Stack.scala and M/vm/Program.scala:
  - arithmetic through `UInt256Kernels`: UnaryOp, BinaryOp, TernaryOp, ShiftingOp, SAR, CLZ, BYTE, SIGNEXTEND, EXP, and DIV/MOD/SDIV/SMOD/ADDMOD/MULMOD;
  - PUSHn bytes written straight into the slot;
  - DUP, SWAP and EXCHANGE as limb copies.
- [ ] T075 [US3] Extend the DC-3 fence over the in-place region in M/vm/OpCode.scala and M/vm/Stack.scala, so that T068 turns green
- [ ] T076 [P] Amend docs/adr/vm/VM-NNN-fixed-width-uint256.md with the word stack

### Exit gate

- [ ] T077 [US1] GT-2 plus the stack-vs-value full suite at 0 differences; S0's pins unchanged (the trace goldens, F06); GT-3 rows 1–10b; [forge] and [beacon] sign. Record D/research.md §B row 13
- [ ] T078 [US2] **S8 re-judged** at S3's tip on Mordor and the ETC head window; a breach blocks the merge. GT-4: SC-003 and the residual shares (§B rows 4–12)
- [ ] T079 S3 exit: `testEssential` once and `sbt formatCheck` green; the PR merged
- [ ] T080 [US1] Optional, not gating [forge]: close the ETC declared gap with a state-free strict replay of a fukuii-fc export of blocks 0–19,260,000 at S3's tip, or mark it closed by spec 018 EG-12. Record in D/research.md R-13

**Checkpoint**: the interpreter's arithmetic allocates no words (US2), and the user-visible trace output is unchanged.

---

## Phase 6: S4, mutable `Memory`

**Goal**: a per-frame mutable buffer (G-6, FR-012) with:
- owned copies only at escapes whose bytes are paid for;
- CALL input as a read-only view;
- precompile output owned;
- region reads that never expand;
- the tracers' pre-step view;
- byte-identical gas, bytes, zero fill and failure paths.

**Before code**:
- [ ] T081 **BEFORE CODE (RP-5)** In D/rp5-memory-tables.md:
  - [beacon] presents the escape table (per site: view or owned copy, and what pays for each owned copy's bytes);
  - the path table: the `size` and bytes each memory-touching path leaves today, on the ETH forks and the pre-Atlantis ETC eras;
  - the `readsPreStepMemory` capability;
  - the `toArrayUnsafe` audit of M/vm/, M/vm/PrecompiledContracts.scala and their crypto calls;
  - [forge] signs the ETC paths.

### Pin tests (green on S3's tip, unchanged after)

- [ ] T082 [P] [US1] Copy today's M/vm/Memory.scala verbatim, renamed, to T/vm/oracle/ImmutableMemory.scala
- [ ] T083 [US1] Memory differential generators and rows M01–M05, M09, M12 and M14 (operation sequences per research R-12, with isolation for `copyOut` results and snapshots), in two suites (depends on T082):
  - fast: T/vm/MemoryDifferentialSpec.scala, ≥ 10³ sequences, `VMTest`;
  - full: T/vm/MemoryDifferentialFullSpec.scala, ≥ 10⁶ sequences, `DifferentialTest`.
- [ ] T084 [P] [US1] M06, M10 and M13 in T/vm/MemoryPathRowsSpec.scala:
  - M06: isolation of LOG data (including on a pre-Atlantis ETC config), RETURN, REVERT and CREATE initcode;
  - M10 and M13: over the ETH forks and the pre-Atlantis ETC eras, with forge R1-b's cases.
- [ ] T085 [P] [US1] M15 in T/vm/CallInputViewSpec.scala: (a) the identity precompile's output is owned; (b) retained input is owned under `traceInternalTransactions` and under a tracer
- [ ] T086 [P] [US2] M11 in T/vm/TracerPreStepMemorySpec.scala: structLog with memory capture sees the pre-step memory for MSTORE, MSTORE8, MCOPY, CALLDATACOPY, RETURNDATACOPY and CALL
- [ ] T087 [P] [US2] Bench runs (record S3's-tip figures in §B rows 15–16):
  - M07 and M08 (`randomStatetest94`, `static_Call1MB1024Calldepth`, under 512 MB);
  - the M15 (c) fixture, a warm value-free CALL loop with 1 KiB and 1 MiB written inputs, in src/it/resources/spec021/m15-call-input-bt.json.

### Failing-first tests (red on S3's tip)

- [ ] T088 [P] [US2] Allocation in T/vm/MemoryAllocationSpec.scala: with 1 MiB of written memory, MSTORE and MLOAD allocate < 1 KiB per operation, i.e. no copy-on-store
- [ ] T089 [US2] Add the differential's heap check (capacity ≤ 1.5 × written + 1 KiB) and the new API rows (`view` equals `copyOut` when taken; `writePadded`, `move`, `hashRegion`) to T/vm/MemoryDifferentialSpec.scala (after T083)

### Implementation

- [ ] T090 [US1] Commit-order refactor on the immutable `Memory`, in M/vm/Memory.scala and M/vm/OpCode.scala:
  - an `Int`-typed API;
  - every memory site restructured to check → read (no expansion; MLOAD expands in its own step) → commit (Rule M5; Rules M1–M3).
- [ ] T091 [US1] The swap:
  - M/vm/Memory.scala as the per-frame buffer (D/data-model.md §8): 1.5× growth, the lazy zero tail, zero-page views, `copyOut`, `view`, `snapshot`;
  - the sharing documentation in M/vm/ProgramState.scala;
  - the `withMemory` uses removed from M/vm/OpCode.scala.
- [ ] T092 [US1] Escapes per Rules M4 and M7, in M/vm/OpCode.scala and M/vm/VM.scala:
  - `copyOut` for initcode, RETURN, REVERT and LOG;
  - CALL-family input as a `view`;
  - owned copies at `InternalTransaction` retention and at `onCallEnter`;
  - precompile output copied in the VM's precompile-call wrapper.
- [ ] T093 [US2] The tracer's pre-step memory view:
  - `readsPreStepMemory` in M/vm/ExecutionTracer.scala (default false) and in M/vm/StructLogTracer.scala (= `enableMemory`);
  - the memory snapshot in M/vm/VM.scala;
  - VmTracer reading through `copyOut` in M/vm/VmTracer.scala.
- [ ] T094 [US2] In-place data flow in M/vm/OpCode.scala and M/vm/Memory.scala, then remove the `UInt256`-typed `Memory` methods:
  - `loadWordInto` and `storeWordFrom` (MLOAD, MSTORE, MSTORE8);
  - `move` (MCOPY);
  - `writePadded` (CALLDATACOPY, CODECOPY, EXTCODECOPY, RETURNDATACOPY);
  - `hashRegion` (SHA3);
  - the CALL output write-back.
- [ ] T095 [US1] Migrate memory-state comparisons to `snapshot()`: T/vm/MemorySpec.scala, OpCodeFunSpec, OpCodeGasSpec, LargeMemoryExpansionSpec, CallOpFixture, CreateOpcodeSpec, OlympiaMcopySpec, StructLogTracerSpec
- [ ] T096 [P] Amend docs/adr/vm/VM-NNN-fixed-width-uint256.md with mutable memory

### Exit gate

- [ ] T097 [US1] The memory differential, full: 0 differences over ≥ 10⁶ sequences plus M01–M15. The UInt256 suites are green, and S0's pins unchanged (the trace goldens with memory capture). GT-3 rows 1–10b; [forge] and [beacon] sign. Record D/research.md §B rows 13–14
- [ ] T098 [US2] M07 and M08 pass under 512 MB, and M15 (c)'s allocated bytes per CALL do not depend on the input size; a dependence fails S4. **S8 re-judged** at S4's tip. GT-4: §B rows 4, 6, 12, 15 and 16
- [ ] T099 S4 exit: `testEssential` once and `sbt formatCheck` green; the PR merged

**Checkpoint**: MSTORE, MLOAD, MCOPY, the copy opcodes and RETURN copy nothing O(memory) (G-6), and consensus outputs are
unchanged.

---

## Phase 7: Final runs and closure

**Purpose**: the final GT-3 and GT-4 record, the hand-offs, and closure.

- [ ] T100 [US2] Final GT-4 at S4's tip: trace re-execution with and without memory capture on each range, and SC-003 measured. If it is below 3×, ask the user to confirm or revise SC-003 as the spec allows. Record in D/research.md §B and R-14
- [ ] T101 [US1] Hand OQ-3's storage-boundary figures (§B rows 9–10) to spec 018 Track C. If the boundary is above 10% of interpreter allocation, recommend a retype there. Record the note in D/research.md R-8 and in specs/018-storage-engine-consolidation/research.md (Track C inputs)
- [ ] T102 [US2] Notify spec 018 SC-016 that trace re-execution can be re-measured, with a note in specs/018-storage-engine-consolidation/plan.md (Track R)
- [ ] T103 [US3] When spec 019's subset checker exists, run it over M/domain/UInt256*.scala, and mark spec 019 FR-003b satisfied in specs/019-zkvm-guest-program/spec.md (FR-008, SC-005)
- [ ] T104 Close spec 021 per docs/development/spec-lifecycle.md:
  - Status `Done`, with the merged PRs and the release;
  - a Closed block;
  - the closure line at the top of D/tasks.md;
  - remove `**In flight**`;
  - regenerate specs/README.md.

---

## Dependencies & Execution Order

### Phase dependencies

- **Phase 1** has no dependencies and runs beside Phase 2. T006 (P-6 with its jar-A run) and T008 (GT-1 part B
  added) block Phase 3.
- **Phase 2 (S0)** depends on T010 (RP-1) only. It is tests only and may merge before 0.11.0.
- **Phase 3 (S1)** depends on Phase 2, T006, T008, T009 and T022 (RP-2).
- **Phase 4 (S2)** depends on Phase 3 and T043 (RP-3). S1 and S2 are released together (the release rule).
- **Phase 5 (S3)** depends on Phase 4, because it uses `isValidInt`, `isNegative` and `numberOfLeadingZeros`, and on
  T059 (RP-4).
- **Phase 6 (S4)** depends on Phase 5, because word transfer uses the word stack and the snapshot path in `VM.exec`,
  and on T081 (RP-5).
- **Phase 7** depends on Phase 6. T103 also waits on spec 019's subset checker.

### Within each slice phase

1. The review point, BEFORE CODE.
2. Pin tests, then failing-first tests. Confirm the red run.
3. Implementation, in the listed order: each commit compiles (`compile-all`), and consensus commits are bucket C,
   never batched with cleanup.
4. The exit gate.

### Story dependencies

- **US1** (roots unchanged) is served by every slice. Its evidence accumulates: S0's pins, then GT-2 and GT-3 at
  each slice tip.
- **US2** (trace allocation) is partly met at S2 (the tracer rendering and the hot conversions), mostly at S3 (the
  word stack) and fully at S4 (memory).
- **US3** (guest-compilable) is met at S1 (the DC-3 check, no closures, no lazy val, no throws) and kept through S3.
  Its last task, T103, waits on spec 019.

---

## Parallel Example

```bash
# Phase 1 and S0 together (different hosts and files):
Task: "T001 Freeze P-1 (post-Amsterdam ETH range)"           # measurement host
Task: "T002 Mordor export 0–10M"                              # Mordor host, node stopped
Task: "T004 P-6 step 1 on fukuii-fc"                          # planned stop only
Task: "T012 Codec goldens in T/domain/UInt256GoldenBytesSpec.scala"
Task: "T013 O rows in T/vm/OpCodeEdgeRowsSpec.scala"
Task: "T016 Opcode-table golden in T/vm/OpCodeTableGoldenSpec.scala"
Task: "T018 Trace goldens in T/vm/TraceGoldenSpec.scala"

# S1 tests first:
Task: "T023 Oracle copy in T/domain/oracle/BigIntUInt256.scala"
Task: "T027 UInt256Spec gaps in T/domain/UInt256Spec.scala"
Task: "T029 Allocation budget in T/domain/UInt256AllocationSpec.scala"
Task: "T030 DC-3 check script and self-test"
Task: "T031 Closure/lazy-val bytecode check"

# S3 pins (all different files):
Task: "T060 W04"  Task: "T061 W05"  Task: "T062 W06"  Task: "T063 W07"  Task: "T064 W01"
```

Host rules (quickstart §0) serialise the measurement work:
- no sbt beside a running node;
- the import differentials and trace profiles run with every node stopped, one at a time;
- P-6 only at planned stops of fukuii-fc.

---

## Implementation Strategy

**First release unit (the MVP): S0 + S1 + S2.**
- These deliver the byte-identical, allocation-light type (US1, US3), with the hot conversions removed.
- The release rule forbids releasing S1 without S2, so they ship together. If S2 cannot meet S8 before a release cut,
  S1 is reverted.

**Second unit: S3.** The word stack: the interpreter's arithmetic allocates no words (US2). S8 is re-judged.

**Third unit: S4.** Mutable memory: no O(memory) copy per memory operation (US2, G-6). S8 is re-judged, and M15 (c)
guards against copying CALL input.

**Rollback.** Each slice is one PR, and reverts go in reverse order: S4, S3, S2, S1. There is no flag and no on-disk,
wire or RPC format change, so no datadir action is ever needed.

**Stop points.** Every slice's exit gate is a safe stop. The next slice's review point (RP-n) must sign before its code
starts.

---

## Task count per phase

| Phase | Slice | Tasks | IDs |
|---|---|---|---|
| 1 | Prerequisites (no code) | 9 | T001–T009 |
| 2 | S0, golden pins | 12 | T010–T021 |
| 3 | S1, the type and GT-2 | 21 | T022–T042 |
| 4 | S2, the T-005 conversions | 16 | T043–T058 |
| 5 | S3, the word stack | 22 | T059–T080 |
| 6 | S4, mutable `Memory` | 19 | T081–T099 |
| 7 | Final runs and closure | 5 | T100–T104 |
| **Total** | | **104** | |

**By story** (counted by grep):
- US1: 49 tasks;
- US2: 24 tasks;
- US3: 8 tasks;
- unlabelled (prerequisites, review points, exit gates, ADR): 23 tasks.

## Notes

- [P] = a different file and no dependency on an incomplete task. Same-file tasks are sequenced in the order listed.
- Verify that failing-first tests are red before implementing. Pin tests are green before and after, by design.
- Commit per task or per logical group. Consensus code is bucket C (risk-stratified commit protocol).
- Never edit a golden file in a slice PR. A changed golden means the change is wrong (DC-8, forge S2).
