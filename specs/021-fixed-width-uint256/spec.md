# Feature Specification: Fixed-Width UInt256 (allocation-light EVM arithmetic, byte-identical)

**Feature Branch**: `021-fixed-width-uint256` (stub written on `claude/fukuii-spec-021-uint256-h3bqt9`)

**Created**: 2026-10-17

**Status**: Draft (2026-10-30). (stub). Split out of spec 018 (its former Track R item 17, FR-030, T-011, OQ-13) at the user's
request on 2026-10-17, because it is pure consensus code under the consensus-change protocol and should not be
scheduled behind a storage gate. To be fleshed out with `/speckit-specify` and `/speckit-clarify` once the impact
analysis (GT-1) has reported, then `/speckit-plan` and `/speckit-tasks`. The Gates and Design constraints sections are
binding on that later work.

**Sequence**: after spec 020 (Scala 3.9 LTS toolchain), in parallel with spec 017 (they touch different packages:
this spec `domain/UInt256` and `vm/`, 017 `ledger/` and `db/`), before spec 018's Track R re-measures trace
re-execution (018 SC-016) and before spec 019's SP-1b consumes the type (019 FR-003b). Chain of record: 016 → 020 →
{017 ∥ 021} → 018 → 019.

**Depends on**: spec 020 landed (so the implementation is written once on the 3.9 toolchain and can use its `inline`
and opaque-type facilities without a later rewrite). Nothing in storage.

**Input**: User decision, 2026-10-17: "split UInt256 into its own spec 021". Origin: the external assessment checked
in spec 018 research §16, which found that fukuii's EVM allocates a `BigInt` per arithmetic operation while Geth's
`uint256` is allocation-free, inverting the claimed advantage; and spec 019's requirement (FR-003b) for a fixed-width
type the bounded guest subset can compile.

## Problem

`domain/UInt256.scala` is `class UInt256 private (private val n: BigInt)`: every EVM word is a `UInt256` wrapping a
`scala.math.BigInt` wrapping a `java.math.BigInteger` wrapping an `int[]`, four heap objects per word. Every
arithmetic result allocates all four again, and the modular reduction (`n & Mask`, `modPow` for `**`) allocates
intermediates on top. `vm/` references `BigInt` 485 times across 18 files; `OpCode.scala` alone 158 times. The EVM
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
- G-5 (phase 2, gated) In-place arithmetic on a mutable word stack inside the interpreter, so the hot loop allocates
  nothing per opcode; only if phase 1's measurement shows the remaining allocation matters.

## Non-goals

- **NG-1 Changing any consensus output.** Not one state root, gas figure, receipt or hash on either chain.
- **NG-2 Changing the `UInt256` API surface** beyond removing the implicit `BigInt` round-trips where a caller does
  not need them. Callers in `domain/`, `ledger/`, `jsonrpc/`, `rlp`, `network`, `consensus` keep compiling unchanged
  in phase 1; a caller change is its own commit with a reason.
- **NG-3 Retyping storage keys and values.** `InMemoryWorldStateProxy` keeps `BigInt` as the storage trie key/value
  type (`MerklePatriciaTrie[BigInt, BigInt]`) in phase 1; the conversion at that boundary is measured and is OQ-3.
- **NG-4 Changing `Stack`'s semantics.** The stack stays a 1024-deep LIFO with the same overflow/underflow errors;
  only its representation may change (phase 2).
- **NG-5 A general big-integer library.** 256 bits wide, plus the 512-bit intermediates `MULMOD`/`ADDMOD` need; nothing
  else.
- **NG-6 Precompile internals.** `PrecompiledContracts` (ecrecover, modexp, BN254, BLS12-381, KZG) keep their current
  libraries; only their `UInt256` inputs/outputs change representation.

## Gates *(mandatory; nothing past them proceeds until they pass)*

**GT-1 — Impact analysis (`beacon` and `forge`, before any code).** Enumerate every `UInt256` and `BigInt` entry point in
`vm/` and `domain/`: each opcode's arithmetic, every implicit conversion site (`uint256ToBigInt`, `BigIntAsUInt256`,
`long2UInt256`, `int2UInt256`, `byte2UInt256`, `bool2UInt256`), gas arithmetic that mixes `UInt256` and `BigInt`,
`Account` balance/nonce, `Wei`, RLP and JSON codecs, the storage-proxy boundary (NG-3). Classify each as: representation
change only; conversion that stays; or a semantic hazard (signed ops, 512-bit intermediates, `EXP`, shifts ≥ 256,
`SIGNEXTEND` byte index ≥ 31, `BYTE` index ≥ 32). The report fixes the edge-case table GT-2 must cover. Both chains'
specialists sign it.

**GT-2 — Reference differential (before wiring into the VM).** A ScalaCheck property suite runs the new type against
the current `BigInt`-backed implementation as the oracle, for every operation, on: uniform random words; words near
0, 1, 2^64, 2^128, 2^255, 2^256−1 and their negations in two's complement; shift amounts 0..300; `SIGNEXTEND` and `BYTE`
indices 0..40; divisors and moduli of 0 and 1; the full GT-1 edge-case table. At least 10⁷ generated cases per
operation plus the table, in CI as a tagged suite. Zero differences, or the spec stops.

**GT-3 — Consensus oracles (before merge).** ethereum/tests (GeneralStateTests and VMTests) on both chains, the EEST
Amsterdam fixtures (`eest-amsterdam.yml`), hive consensus (`hive-consensus.yml`), spec 017's differential set, and a
two-tree import differential on the fixed Mordor and Platåberget ranges (state roots and receipt roots equal). `forge`
signs ETC, `beacon` ETH.

**GT-4 — Measurement (before claiming anything).** JFR allocation profile of `debug_traceBlockByNumber` over the fixture
blocks before and after; `[IMPORT-TIMING]` p50/p99 on the fixed ranges; the figures published in `research.md`. Phase 2
(G-5) is opened only if the post-phase-1 profile still shows per-opcode allocation dominating.

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
assert `vm/` and `domain/UInt256` import no `scala.math.BigInt` on the paths this spec names.

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

**DC-7 — Phase 2 is a separate decision.** Mutable in-place arithmetic on a `Array[Long]` word stack (Geth's model)
changes `Stack`, `Memory` and every opcode's data flow; it is gated on GT-4 showing it is worth it, and on its own
GT-2/GT-3 pass. It never lands in the same PR as phase 1.

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
- `hashCode`/`equals` where `UInt256` is a `Map` key (access lists, warm-slot sets).
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
- **FR-007** Phase 2 (in-place word stack) MUST be its own gated slice with its own GT-2/GT-3 pass (DC-7).
- **FR-008** The type MUST satisfy spec 019's bounded subset (DC-5) and spec 019 FR-003b MUST be marked satisfied by
  this spec when it lands.

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
  change, removes the cons allocation) or waits for phase 2's word stack.
- **OQ-5** Whether the Besu/Tuweni or Geth `uint256` test vectors can be imported as additional GT-2 fixtures (licence
  check).

## Related specs

- **Spec 018** (storage architecture): its Track R item 17, FR-030, T-011 and OQ-13 are satisfied by this spec; 018
  SC-016 re-measures trace re-execution after it lands.
- **Spec 019** (zkVM guest): FR-003b is satisfied by this spec; SP-1b consumes the fixed-width type.
- **Spec 020** (Scala 3.9 LTS): prerequisite toolchain.
- **Spec 017** (`StateAccess`, witnesses): runs in parallel; disjoint packages; its differential set is a GT-3 oracle.

## Known tasks (seed for /speckit-tasks)

- **T-000 (GT-1)** `beacon` + `forge` impact analysis and edge-case table.
- **T-001 (GT-2)** The reference differential suite, against the current implementation, before any VM wiring.
- **T-002** The fixed-width type behind the same API; `UInt256Spec` extended; golden bytes for RLP/JSON.
- **T-003 (GT-3)** Oracles on both chains; two-tree import differential.
- **T-004 (GT-4)** JFR profiles and `[IMPORT-TIMING]`; `research.md`.
- **T-005** Remove hot-path implicit `BigInt` conversions, one commit each with its measurement.
- **T-006 (FR-004)** Extend the dependency check.
- **T-007 (phase 2, gated)** In-place word stack, its own spec slice.
