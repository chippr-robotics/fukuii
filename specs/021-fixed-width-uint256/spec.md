# Feature Specification: Fixed-Width UInt256 (allocation-light EVM arithmetic, byte-identical)

**Feature Branch**: `021-fixed-width-uint256` (stub written on `claude/fukuii-spec-021-uint256-h3bqt9`)

**Created**: 2026-10-17

**Status**: Planned (2026-11-07). `plan.md` and `tasks.md` exist: 104 tasks in seven phases (prerequisites, then the slices S0 golden pins, S1 the limb type with GT-2, S2 the T-005 conversions, S3 the word stack and S4 mutable Memory, then closure), no code landed; next `/speckit-implement`, S0 first. Split out of spec 018 (its former Track R item 17, FR-030, T-011, OQ-13) at the user's
request on 2026-10-17, because it is pure consensus code under the consensus-change protocol and should not be
scheduled behind a storage gate. GT-1 reported 2026-11-06, and the Clarifications of 2026-11-07 scoped one end-state
interpreter design (fixed-width type plus an in-place word stack). The Gates and Design constraints sections are
binding on the implementation.

**Sequence**: after spec 020 (Scala 3.9 LTS toolchain), in parallel with spec 017 (they touch different packages:
this spec `domain/UInt256` and `vm/`, 017 `ledger/` and `db/`), before spec 018's Track R re-measures trace
re-execution (018 SC-016) and before spec 019's SP-1b consumes the type (019 FR-003b). Chain of record: 016 → 020 →
{017 ∥ 021} → 018 → 019. *(Updated 2026-11-07.)*
- Spec 017 is parked until execution-specs releases `ExecutionWitness`. Spec 018's Track C (the unified state layout)
  runs first after 0.11.0.
- This spec runs beside Track C. Its packages are `domain/UInt256` and `vm/`; Track C's are `mpt/`, `db/`, the commit
  path in `ledger/` and `consensus/`. The only shared file is `ledger/BlockPreparator.scala`, where this spec's
  masking sites and Track C's proposer view meet, so the two are sequenced per PR.
- Spec 020's code has landed (PRs 3 to 7, the last #1722); its header still has to be closed.

**In flight**: `fukuii-fc` (local session), branch `wt/021-plan`: planned and tasked; next `/speckit-implement`

**Depends on**: spec 020 landed (so the implementation is written once on the 3.9 toolchain and can use its `inline`
and opaque-type facilities without a later rewrite). Nothing in storage.

**Input**: User decision, 2026-10-17: "split UInt256 into its own spec 021". Origin: the external assessment checked
in spec 018 research §16, which found that fukuii's EVM allocates a `BigInt` per arithmetic operation while Geth's
`uint256` is allocation-free, inverting the claimed advantage; and spec 019's requirement (FR-003b) for a fixed-width
type the bounded guest subset can compile.

## Clarifications

### Session 2026-11-07

**Standing rule (user, 2026-11-06):** "avoid separation between etc and eth. we build for eth and etc is along for the
ride. the end state is a configurable, highly efficient evm el. avoid doing multiple stages as the fastest path to a
stable client is to build correctly." One code path for both chains, and the end state designed once.

- Q: Phase 1 (the fixed-width type) and a separately gated phase 2 (an in-place word stack), or one design? → A: **One
  end-state design.** The fixed-width type and the in-place mutable word stack are specified together as the target
  interpreter and implemented in slices: the type first, then the stack and the opcodes. There is no "measure, then
  decide" gate. Every slice passes GT-2 and GT-3. GT-4 measures and reports; it does not decide whether the stack work
  happens.
- Q (GT-1 decision D1): `/` by zero throws today. Keep the throw, or return zero? → A: **Retire `/`.** Its one main
  caller divides by 2 and moves to `div`, so no arithmetic member throws (G-4).
- Q (D2): the `<<`, `>>` and `sshift` members truncate the shift amount to 31 or 32 bits and can exhaust memory or
  throw. → A: The new members implement **EVM semantics for every shift amount**. GT-2 compares the members with the
  oracle on shifts 0–255 only, where the oracle is defined, and compares the opcode wrappers (SHL, SHR, SAR) on every
  shift. Cases E29 and E30 are recorded as unreachable from EVM paths.
- Q (D3): building a value from more than 32 bytes throws. → A: **Keep it.** It is input validation, not arithmetic.
- Q (D4, OQ-4): what happens to the stack's representation? → A: It is **in scope** as part of the word stack. Its
  design constraints come from GT-1 part A §6 (DC-8).
- Q (asked at /speckit-plan, 2026-11-07): is a mutable, in-place EVM `Memory` part of this spec, or a follow-up spec? → A:
  **Part of this spec** (the end-state rule). It is its own slice after the word stack (G-6, FR-012), under the same gates
  and DC-8-style rules: the tracers see the memory as it was before each step, and a failed operation leaves memory as it
  was.

### GT-1 result (2026-11-06)

`beacon` produced part A: `UInt256` semantics member by member, the opcodes, the `Int`/`Long` boundary, gas, and the
edge-case table. `forge` produced part B: conversion sites, domain boundaries, codecs, the storage boundary, equality,
precompiles and ETC. Both read code only; nothing was run.
- **Part A:** 139 entries: 48 change representation only, 41 keep a conversion, 50 are semantic hazards. The **62-row
  edge-case table** becomes GT-2's named cases. No existing departure from the EVM rules was found on any reachable input.
- **Part B:** 112 conversion sites in `src/main`: 30 change representation only, 77 `uint256ToBigInt` conversions stay
  (59 of them per opcode), and 5 are hazards (masking, in `ledger/`). `long2UInt256` and `byte2UInt256` have no callers.
  Tests rely on 709 `int2UInt256` sites and on cooperative `equals`.
  - The hot-path T-005 list, in order:
    1. `calcMemCost` (16 sites; size sums must stay unbounded)
    2. SLOAD and SSTORE (3 and about 12 conversions)
    3. JUMP's `isValidInt`
    4. `wordsForBytes`
    5. the `getBalance` round trip
    6. `StructLogTracer`
- **Sign-off conditions** (now FR-009 to FR-011):
  - beacon (ETH): D1 to D3 recorded (done above), the 62 rows carried into GT-2, and part A §3's truncation invariants
    added to the suites.
  - forge (ETC): S1 to S8, listed in FR-011.
- **Spec text corrected:**
  - the import-based dependency check is vacuous (DC-3, FR-004);
  - warm-slot sets are keyed by `StorageKey` (a `BigInt`), not `UInt256` (Edge Cases);
  - the counts are refreshed (Problem).
- **Where the reports are:** part A will be published in this directory as `gt1-impact-analysis-part-a.md`. Part B is
  kept locally and added before GT-2.

## Problem

`domain/UInt256.scala` is `class UInt256 private (private val n: BigInt)`: every EVM word is a `UInt256` wrapping a
`scala.math.BigInt` wrapping a `java.math.BigInteger` wrapping an `int[]`, four heap objects per word. Every
arithmetic result allocates all four again, and the modular reduction (`n & Mask`, `modPow` for `**`) allocates
intermediates on top. `vm/` references `BigInt` on about 484 lines across 21 files, about 150 of them in `OpCode.scala`
(GT-1, 2026-11-06; the stub said 485 across 18 and 158). The EVM
`Stack` is an immutable `List[UInt256]`, so every push allocates a cons cell as well. Trace re-execution
(`debug_trace*`), the import hot path and spec 019's guest all pay this on every opcode.

Reference clients do not: Geth's `holiman/uint256` is four `uint64` limbs operated in place; Besu's `UInt256` is a
fixed-width type; Reth and Erigon use `ruint`/`alloy` fixed-width words. Fukuii is the outlier, and the cost is on the
exact path (trace re-execution under read load, 018 Track R; gigablock execution, 018 DC-13; cycle count in the guest,
019 SC-006) that the surrounding specs are trying to make fast.

The constraint that makes this a spec and not a refactor: `UInt256` is consensus-critical. Its arithmetic decides state
roots, gas and receipts on both chains. The replacement must be **byte-identical** on every input, including the
signed-division corner cases, 512-bit intermediates in `ADDMOD`/`MULMOD`, `EXP`, `SIGNEXTEND`, `BYTE`, `SHL`/`SHR`/`SAR`,
and every conversion the 46 referencing files rely on (`toBigInt`, `toLong`, `toInt`, `ByteString`, hex). The JVM also
sets a ceiling: without Project Valhalla value objects (not in JDK 25), a 256-bit value cannot be stack-allocated, so
"zero allocation" is reachable only with in-place arithmetic on a mutable word array (phase 2), and the honest phase 1
target is "one small flat object per result, no `BigInteger` on the path".

## Goals

- G-1 A fixed-width `UInt256` (four `Long` limbs, or an equivalent flat representation) with no `BigInt`/`BigInteger`
  on any arithmetic, comparison, shift, byte or sign operation, behind the **same API** the 46 referencing files use.
- G-2 Byte-identical results to the current implementation on every input: proven by a property-based differential
  against the `BigInt` reference (GT-2), ethereum/tests on both chains, EEST Amsterdam fixtures, and spec 017's
  differential set (GT-3).
- G-3 Measured gains, not asserted: allocation profile (JFR) of trace re-execution before and after; `[IMPORT-TIMING]`
  on the fixed Mordor and Platåberget ranges; the 018 GT-1 RPC arm's trace figures (018 SC-016).
- G-4 A type the spec 019 bounded subset can compile: no `BigInt`, no reflection, no exceptions on the arithmetic path
  (division by zero already returns zero per EVM semantics, as `zeroCheck` does today).
- G-5 In-place arithmetic on a mutable word stack inside the interpreter, so the hot loop allocates nothing per opcode.
  *(Clarified 2026-11-07: part of the one end-state design, no longer gated on measurement.)*
- G-6 *(2026-11-07)* An in-place, mutable EVM `Memory` (a growable byte buffer per call frame, as in go-ethereum), so that
  MSTORE, MLOAD, MCOPY, the copy opcodes and RETURN do not copy the whole memory per operation. The semantics are unchanged:
  memory-expansion gas, the 32-byte word rounding, zero fill and every byte read and written stay byte-identical.

## Non-goals

- **NG-1 Changing any consensus output.** Not one state root, gas figure, receipt or hash on either chain.
- **NG-2 Changing the `UInt256` API surface** beyond removing the implicit `BigInt` round-trips where a caller does
  not need them. Callers in `domain/`, `ledger/`, `jsonrpc/`, `rlp`, `network`, `consensus` keep compiling unchanged
  in phase 1; a caller change is its own commit with a reason.
- **NG-3 Retyping storage keys and values.** `InMemoryWorldStateProxy` keeps `BigInt` as the storage trie key/value
  type (`MerklePatriciaTrie[BigInt, BigInt]`) in phase 1; the conversion at that boundary is measured and is OQ-3.
- **NG-4 Changing `Stack`'s semantics.** The stack stays a 1024-deep LIFO with the same overflow/underflow errors;
  only its representation changes, to the in-place word stack (G-5, DC-8).
- **NG-5 A general big-integer library.** 256 bits wide, plus the 512-bit intermediates `MULMOD`/`ADDMOD` need; nothing
  else.
- **NG-6 Precompile internals.** `PrecompiledContracts` (ecrecover, modexp, BN254, BLS12-381, KZG) keep their current
  libraries; only their `UInt256` inputs/outputs change representation.

## Gates *(mandatory; nothing past them proceeds until they pass)*

**GT-1 — Impact analysis (`beacon` and `forge`, before any code).** *(Reported 2026-11-06; see Clarifications,
"GT-1 result". Sign-off is conditional on FR-009 to FR-011.)* Enumerate every `UInt256` and `BigInt` entry point in
`vm/` and `domain/`: each opcode's arithmetic, every implicit conversion site (`uint256ToBigInt`, `UInt256.toUInt256` (the `BigIntAsUInt256` implicit class until spec 020 PR 5b),
`long2UInt256`, `int2UInt256`, `byte2UInt256`, `bool2UInt256`), gas arithmetic that mixes `UInt256` and `BigInt`,
`Account` balance/nonce, `Wei`, RLP and JSON codecs, the storage-proxy boundary (NG-3). Classify each as: representation
change only; conversion that stays; or a semantic hazard (signed ops, 512-bit intermediates, `EXP`, shifts ≥ 256,
`SIGNEXTEND` byte index ≥ 31, `BYTE` index ≥ 32). The report fixes the edge-case table GT-2 must cover. Both chains'
specialists sign it.

**GT-2 — Reference differential (before wiring into the VM).** A ScalaCheck property suite runs the new type against
the current `BigInt`-backed implementation as the oracle, for every operation, on: uniform random words; words near
0, 1, 2^64, 2^128, 2^255, 2^256−1 and their negations in two's complement; shift amounts 0..300; `SIGNEXTEND` and `BYTE`
indices 0..40; divisors and moduli of 0 and 1; the full GT-1 edge-case table. At least 10⁷ generated cases per
operation plus the table, in CI as a tagged suite. Zero differences, or the spec stops. *(Clarified 2026-11-07, D1 and
D2.)* `/` is retired, so it has no case. The shift members are compared with the oracle on shifts 0–255, and the opcode
wrappers on every shift. Inputs where the oracle throws are excluded, and each exclusion is named. The table is GT-1's
62 rows plus part B's conversion rows, and every word satisfies `toBigInt.signum >= 0` and `bitLength <= 256` (S1).

**GT-3 — Consensus oracles (before merge).** ethereum/tests (GeneralStateTests and VMTests) on both chains, the EEST
Amsterdam fixtures (`eest-amsterdam.yml`), hive consensus (`hive-consensus.yml`), spec 017's differential set, and a
two-tree import differential on the fixed Mordor and Platåberget ranges (state roots and receipt roots equal). `forge`
signs ETC, `beacon` ETH. *(Clarified 2026-11-07.)*
- Spec 017's differential set counts only if 017 has reopened by then.
- The ETC side follows forge's S4: the ETC-typed legacy suite, pre-Spiral ethereum/tests, a Mordor two-tree
  differential across its fork transitions, and an ETC mainnet two-tree differential. Per-transaction receipt roots
  before Atlantis must be equal too.
- *(Corrected 2026-11-07, forge's plan review.)* ETC mainnet is synced on `fukuii-fc`: a SNAP sync with pivot
  25,688,690 and a verified root. The ETC mainnet differential has two parts:
  - an offline import from genesis of blocks 0 to 5,100,000, the same block source as spec 018's offline ETC import;
  - a head window imported into a copy of that synced state (plan, GT-3 row 10b).

  Blocks between 5.1M and the head have no state source, which is a declared gap that forge signed (plan).

**GT-4 — Measurement (before claiming anything).** JFR allocation profile of `debug_traceBlockByNumber` over the fixture
blocks before and after; `[IMPORT-TIMING]` p50/p99 on the fixed ranges; the figures published in `research.md`. *(Clarified 2026-11-07: GT-4
measures and reports at each slice. It no longer decides whether the word stack (G-5) is built.)*

## Design constraints *(mandatory)*

**DC-1 — Byte-identical, on both chains, before fast.** Constitution principle I. Every optimisation is subordinate to
GT-2 and GT-3. Where EVM semantics and "natural" fixed-width semantics differ (signed division by −1 of the minimum
value, `SMOD` sign of result, shifts by ≥ 256, `EXP` with zero exponent and base), the EVM rule is written as a named
test case in GT-2's table before it is implemented.

**DC-2 — Same API, different representation.** `UInt256` keeps its public members (`+ - * / ** & | ^ ~ unary_-`,
`div sdiv mod smod addmod mulmod`, `slt sgt`, `<< >> sshift`, `signExtend getByte`, `compare min max`, `toBigInt
toLong toInt toByte isZero bytes toHexString`, the `apply` overloads and `MaxValue`/`Zero`/`One`). The implicit
conversions to and from `BigInt` stay in phase 1 so callers compile; each use that survives GT-1 as "conversion that
stays" is tagged, and the hot-path ones are removed in their own commits (NG-2).

**DC-3 — No `BigInteger` on the arithmetic path.** Four `Long` limbs (little-endian) in a final class, with carry-aware
add/sub, schoolbook or Karatsuba-free 256×256→512 multiply for `MULMOD`, long division by Knuth D or a documented
equivalent for `/`, `%`, `MULMOD`, and exponentiation by squaring for `**`. A `BigInt` may appear only in `toBigInt` /
`apply(BigInt)` and in the GT-2 oracle. The dependency check from 017 DC-15 (extended by 018 DC-8) is extended to
assert `vm/` and `domain/UInt256` import no `scala.math.BigInt` on the paths this spec names. *(Corrected 2026-11-07,
GT-1: an import check is vacuous, because `BigInt` is the `scala` package alias and needs no import.)* The check is
**usage-based**: no reference to `BigInt` or `BigInteger` in `domain/UInt256.scala` outside `toBigInt`, `apply(BigInt)`
and the oracle, and none in the arithmetic, shift, byte and sign opcode bodies. Gas (`FeeSchedule`), storage (NG-3) and
the tracers keep `BigInt` legitimately and are out of the check's scope. Spec 017 is parked, so the check lives in this
spec's own script until 017's DC-15 tooling exists.

**DC-4 — Scala 3 at the type.** Written on the spec 020 toolchain: an opaque type over the limb class is **not** used
(it would still allocate and would hide the class from JFR); instead a `final class` with `inline` accessors, `given`
`Ordering`, `CanEqual` derivation, and `Numeric`-free arithmetic. No reflection, no exceptions on the arithmetic path
(G-4). `hashCode`/`equals` are value-based over the four limbs and consistent with the current ones wherever
`UInt256` is a `Map` key.

**DC-5 — Guest-compilable.** The type and its operations are inside spec 019's bounded subset by construction: no
`BigInt`, no exceptions, fixed-size arrays only, no closures capturing mutable state. Spec 019's subset checker (its
DC-8) runs over `domain/UInt256` once it exists.

**DC-6 — Measured on the paths that matter.** GT-4's figures are taken on trace re-execution (the read path 018 Track R
targets), on import (`[IMPORT-TIMING]`), and, when the 018 GT-1 harness exists, at 10× and 30× block gas. No
"zero-allocation" claim is made; the honest phase 1 statement is "no `BigInteger`, one flat object per result", and the
phase 2 statement, if reached, is "no per-opcode allocation in the interpreter loop".

**DC-7 — One end-state design, delivered in slices** *(rewritten 2026-11-07; it said "Phase 2 is a separate
decision")*. The fixed-width type and the in-place word stack (an `Array[Long]` of 4×1024 limbs, Geth's model) are one
design. It changes `Stack`, `Memory` and every opcode's data flow, so it lands in slices:
1. the type behind the same API;
2. the hot-path conversions (T-005);
3. the word stack and the opcode data flow.

Each slice passes GT-2 and GT-3 on its own. GT-4 measures every slice but gates none.

**DC-8 — What the word stack must preserve** *(added 2026-11-07, from GT-1 part A §6)*.
- **Peek before pop.** `OpCode.scala` has 64 `stack.pop(...)` sites, and many of them read the *pre-instruction* stack
  without consuming it:
  - the `varGas` of the memory and copy ops;
  - the `address()` reads of BALANCE, EXTCODE*, SLOAD's `addressAndKey` and `stateGasDelta`;
  - `recordReadsBeforeOutOfGas`;
  - CALL's gas and target helpers;
  - CREATE2's re-pop of the salt.

  `OpCode.execute` runs `calcGas`, then `stateGasDelta`, then `execAndSpendGas` on the same pre-instruction state. The
  word stack must give these sites a read-only peek that leaves the state intact.
- **Tracers.** `StructLogTracer`, `VmTracer`, `PrestateTracer` and `AccessListTracer` read `prevState.stack` after the
  step. A ring shared by the old and new state would show them the post-op stack, so tracing must snapshot or copy
  first. `debug_trace*` output stays byte-identical.
- **Observable behaviour.** `equals`, `hashCode` and `toString` of `Stack` keep their observable behaviour wherever tests
  or tracers rely on it.

## User Scenarios & Testing *(mandatory)*

### User Story 1 — Every state root is unchanged (Priority: P1)

A maintainer swaps the representation and re-runs every consensus oracle on both chains. Nothing moves.

**Independent Test**: GT-2 zero differences; GT-3 all green; two-tree import differential equal.

### User Story 2 — Trace re-execution allocates far less (Priority: P1)

An operator serving `debug_trace*` under load sees GC pressure and trace latency fall.

**Independent Test**: GT-4 JFR profile shows no `BigInteger` allocation on the arithmetic path; 018 SC-016; trace p99
on the 018 GT-1 RPC arm.

### User Story 3 — The guest compiles the type (Priority: P2)

Spec 019's SP-1b translates `UInt256` without a `BigInt` shim.

**Independent Test**: 019's subset checker passes over `domain/UInt256`; SP-1b's sample uses the real type.

### Edge Cases

- `SDIV` of −2²⁵⁵ by −1 (must wrap to −2²⁵⁵); `SMOD` sign follows the dividend; division or modulus by zero returns zero.
- `MULMOD`/`ADDMOD` with operands near 2²⁵⁶−1 (512-bit intermediate; no wrap before the modulus).
- `EXP` with exponent 0 (result 1), base 0 (result 0 unless exponent 0), and large exponents (exponentiation by
  squaring must match `modPow` bit for bit).
- Shifts by ≥ 256 (result 0, or all-ones / all-zeros for `SAR` by sign), shift amount not fitting an `Int`.
- `SIGNEXTEND` with byte index ≥ 31 (identity); `BYTE` with index ≥ 32 (zero).
- Conversions: `toLong`/`toInt` truncation semantics identical to today; `apply(ByteString)` with fewer or more than 32
  bytes; negative `BigInt` input to `apply` (today: `n & Mask`).
- `hashCode`/`equals` where `UInt256` is a `Map` key: `BlockAccessRecorder`, `BlockAccessListBuilder` and
  `PrestateTracer`'s insertion-ordered set. *(Corrected 2026-11-07: warm-slot sets and access lists are keyed by
  `StorageKey`, a `BigInt`, not by `UInt256`.)* The EIP-7928 block access list is re-sorted with `Ordering[UInt256]`,
  which must stay unsigned. `equals` is cross-type and asymmetric today (`UInt256 == 0` holds), and tests rely on it.
- JSON hex encoding and RLP encoding of the same value before and after (golden bytes).

## Requirements *(mandatory)*

### Functional Requirements (stub level; refined by /speckit-specify)

- **FR-001** `UInt256` MUST be reimplemented over fixed-width limbs with no `BigInt`/`BigInteger` on any arithmetic,
  comparison, shift, byte or sign operation (DC-3), keeping its public API (DC-2).
- **FR-002** A property-based differential suite against the `BigInt` reference MUST exist and pass with zero
  differences over the GT-2 generators and edge-case table, and MUST run in CI as a tagged suite.
- **FR-003** ethereum/tests on both chains, EEST Amsterdam, hive consensus, spec 017's differential set and the
  two-tree import differential MUST pass unchanged (GT-3), with `forge` and `beacon` sign-off.
- **FR-004** The 017/018 dependency check MUST be extended to forbid `scala.math.BigInt` on the named `vm/` and
  `domain/UInt256` paths.
- **FR-005** JFR allocation profiles and `[IMPORT-TIMING]` before and after MUST be published in `research.md` (GT-4);
  no performance claim MAY precede them.
- **FR-006** The storage-proxy boundary (`MerklePatriciaTrie[BigInt, BigInt]`) MUST be left as is in phase 1 (NG-3);
  its conversion cost MUST be measured and recorded for OQ-3.
- **FR-007** *(Rewritten 2026-11-07.)* The in-place word stack (G-5) MUST be delivered in this spec, as its own slice
  after the type and the T-005 conversions, with its own GT-2 and GT-3 pass, and MUST satisfy DC-8.
- **FR-008** The type MUST satisfy spec 019's bounded subset (DC-5) and spec 019 FR-003b MUST be marked satisfied by
  this spec when it lands.
- **FR-009** *(2026-11-07, D1–D3, D5.)* No arithmetic member MAY throw:
  - `/` is removed, and its caller moves to `div`.
  - The shift members follow EVM semantics for every shift amount.
  - `apply` of more than 32 bytes keeps throwing; that is input validation.
  - A `toByte` member, if one exists, equals `BigInt.toByte`.
- **FR-010** *(2026-11-07, beacon's conditions.)* GT-2 MUST carry GT-1's 62 edge-case rows as named cases, and the
  opcode suites MUST assert part A §3's truncation invariants: JUMP `isValidInt`, BLOBHASH bounds, `sliceBytes`
  min-before-truncate, CALL's requested gas, RETURNDATACOPY saturation.
- **FR-011** *(2026-11-07, forge's conditions S1–S8.)*
  - S1: GT-2 covers part B's rows, and every word satisfies `signum >= 0` and `bitLength <= 256`.
  - S2: golden-bytes tests for the RLP and JSON codecs land **before** the type swap and are unchanged after it.
  - S3: `apply(BigInt)` masking and `apply(Long)` sign extension are preserved exactly.
  - S4: GT-3's ETC scope as written in GT-3.
  - S5: no change to `forBlock`, the ETC opcode tables or the fork tables.
  - S6: `equals` keeps its cooperative branch, or the test migration is its own commit. `hashCode` stays equal to
    `BigInt.hashCode`, or the change is documented.
  - S7: any T-005 rewrite of `calcMemCost` or `wordsForBytes` keeps size arithmetic unbounded, with vectors
    (2^256−1, 1) and (2^64, 2^64).
  - S8: `[IMPORT-TIMING]` on the ETC ranges stays within 10%, or `toBigInt` is memoised.
- **FR-012** *(2026-11-07, G-6.)* EVM `Memory` MUST become an in-place, mutable buffer per call frame, delivered as its own
  slice after the word stack. That slice has its own GT-2 (a differential against today's immutable `Memory` on generated
  operation sequences) and GT-3 pass, and it MUST preserve:
  - memory-expansion gas and its overflow and out-of-gas behaviour;
  - byte-identical reads, writes and zero fill;
  - the pre-step view the tracers read (copy or snapshot, as DC-8 requires for the stack);
  - memory left unchanged when an operation fails a check before it writes.

### Key Entities

- **`UInt256` (fixed-width)**: four `Long` limbs, little-endian, final class, same API.
- **Reference differential**: the ScalaCheck suite with the current `BigInt` implementation as oracle.
- **Edge-case table**: GT-1's enumerated semantic hazards, each a named test.
- **Word stack (phase 2)**: a mutable `Array[Long]` of 4×1024 with in-place operations.

## Success Criteria *(mandatory)*

- **SC-001** GT-2: zero differences over ≥ 10⁷ generated cases per operation plus the edge-case table.
- **SC-002** GT-3: ethereum/tests both chains, EEST Amsterdam, hive consensus, 017 differential set, two-tree import
  differential all unchanged.
- **SC-003** GT-4: JFR shows zero `java.math.BigInteger` allocations on the arithmetic path during trace re-execution
  of the fixture blocks; total allocation per opcode reduced by at least 3× from baseline (confirmed or revised from the
  first profile).
- **SC-004** `[IMPORT-TIMING]` p50 and p99 on the fixed ranges improve or stay within the constitution's 10% budget.
- **SC-005** The dependency check (FR-004) is green; spec 019's subset checker passes over `domain/UInt256`.
- **SC-006** Every caller in the 46 referencing files compiles with no semantic change; hot-path implicit conversions
  removed are listed with their measured effect.

## Assumptions

- Spec 020 has landed, so the type is written once on Scala 3.9.
- JDK 25 has no value objects (Valhalla); phase 1 accepts one flat allocation per result.
- The current `BigInt` implementation is the oracle; it is kept in the test tree after removal from `main`.
- `beacon` owns ETH review, `forge` ETC; `eye` runs the oracles; `wraith` handles compile fallout; `mithril` reviews
  the Scala 3 shape (DC-4).

## Open Questions

- **OQ-1** Limb representation: four `Long`s as fields (fastest, no array bounds checks) vs `Array[Long](4)` (simpler
  loops, one more object). Default: fields.
- **OQ-2** Whether `**` keeps `modPow` semantics via exponentiation by squaring in 256-bit with wrap, or routes through
  a 512-bit intermediate; both must match `BigInt.modPow(_, 2²⁵⁶)` bit for bit.
- **OQ-3** When to retype the storage trie key/value from `BigInt` to `UInt256` (NG-3): phase 1 measures the
  conversion cost; the retype is a later slice or spec if it is material.
- **OQ-4** Whether `Stack` moves from `List[UInt256]` to an `Array[UInt256]` ring in phase 1 (cheap, no semantic
  change, removes the cons allocation) or waits for phase 2's word stack. **Resolved 2026-11-07:** neither as asked. The
  stack becomes the in-place word stack within this spec (FR-007, DC-8). GT-1 showed that a ring is neither cheap nor free
  of semantic change, because of the 64 pop-as-peek sites and the tracers.
- **OQ-5** Whether the Besu/Tuweni or Geth `uint256` test vectors can be imported as additional GT-2 fixtures (licence
  check).

## Related specs

- **Spec 018** (storage architecture): its Track R item 17, FR-030, T-011 and OQ-13 are satisfied by this spec; 018
  SC-016 re-measures trace re-execution after it lands.
- **Spec 019** (zkVM guest): FR-003b is satisfied by this spec; SP-1b consumes the fixed-width type.
- **Spec 020** (Scala 3.9 LTS): prerequisite toolchain.
- **Spec 017** (`StateAccess`, witnesses): runs in parallel; disjoint packages; its differential set is a GT-3 oracle.

## Known tasks (seed for /speckit-tasks)

- **T-000 (GT-1)** `beacon` + `forge` impact analysis and edge-case table. **Done 2026-11-06** (Clarifications).
- **T-001 (GT-2)** The reference differential suite, against the current implementation, before any VM wiring.
- **T-002** The fixed-width type behind the same API; `UInt256Spec` extended; golden bytes for RLP/JSON.
- **T-003 (GT-3)** Oracles on both chains; two-tree import differential.
- **T-004 (GT-4)** JFR profiles and `[IMPORT-TIMING]`; `research.md`.
- **T-005** Remove hot-path implicit `BigInt` conversions, one commit each with its measurement.
- **T-006 (FR-004)** Extend the dependency check.
- **T-007** In-place word stack and the opcode data flow (FR-007, DC-8), its own slice. It is no longer gated
  (2026-11-07).
