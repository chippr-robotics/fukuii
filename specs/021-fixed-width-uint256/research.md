# Research: Fixed-Width UInt256 (spec 021, Phase 0)

*Written 2026-11-07 by `beacon` (ETH and `vm/` owner) as the `/speckit-plan` Phase 0 output, against origin/staging
`d7c142ba5` (v0.10.49: Scala 3.9.0, `-source:3.9`, JDK 25). Read-only; nothing was run. `forge` reviews the ETC side;
those items are marked **forge**. **VERIFY** marks a fact this host could not check; each one names the slice that
checks it.*

**Inputs**: [spec.md](./spec.md) and its Clarifications of 2026-11-07, which are binding: one end-state design, three
code slices, no measure-then-decide gate, D1–D4, DC-1 to DC-8, FR-001 to FR-011. Also GT-1 part A
([gt1-impact-analysis-part-a.md](./gt1-impact-analysis-part-a.md): the 62-row table, §3, §6) and GT-1 part B (forge),
added before GT-2. forge reviewed this plan on 2026-11-07, and its changes C1–C17 are applied here.

**Resolved here**:
- OQ-1 (R-1), OQ-2 (R-2), OQ-3 (R-8) and OQ-5 (R-9);
- the word-stack choices against DC-8 (R-10);
- the mutable `Memory` design (R-11, for G-6 and FR-012, added 2026-11-07).

OQ-4 was resolved in the spec. Technical Context has no NEEDS CLARIFICATION left.

---

## R-1 OQ-1: four `Long` fields, and one algorithm source for both forms

**Decision.**
- `UInt256` is `final class UInt256 private[domain] (w0: Long, w1: Long, w2: Long, w3: Long)`, little-endian:
  value = Σ unsigned(wₖ)·2^(64k). The limbs are `private[ethereum] val`s, so the kernels and the word stack can read
  them. There is no array inside the value.
- The `Array[Long]` form exists where it pays: the word stack (R-10), at 4 limbs per slot.
- **One algorithm source for both forms.** Each cheap operation is written once:
  - The operations are add, sub, truncated mul, and/or/xor/not, negate, the shifts, SIGNEXTEND, BYTE, the compares,
    CLZ and `fillingAdd`.
  - Each is an `inline def` in `domain/UInt256Kernels.scala`. Its inputs are limbs, and it has an `inline` continuation
    parameter that receives the four result limbs.
  - `UInt256` instantiates the continuation as `new UInt256(_, _, _, _)`. The word stack instantiates it as four array
    stores into the destination slot.
  - Scala 3 beta-reduces an inline lambda argument at the call site, so no closure exists at run time.
- The heavy operations are DIV, SDIV, MOD, SMOD, ADDMOD, MULMOD and EXP. Each is an ordinary method over
  `(Array[Long], offset)` operands with a fixed-size caller-provided scratch array.
  - The word stack passes its own operands and scratch.
  - The value type copies its limbs into one fresh scratch array per call. That is one extra allocation, and only on
    these seven members. After S3 they are off the interpreter path.

**Rationale.**
- Size and count per result, assuming a 64-bit HotSpot with compressed class pointers:
  - fields: one object of 48 bytes (12-byte header, 4 padding, 32 data);
  - `Array[Long](4)`: two objects, 16 + 48 = 64 bytes;
  - today's representation: four objects of about 130 to 150 bytes in total (`UInt256`, `BigInt`, `BigInteger`,
    `int[]`), or two for values that fit a `Long`, because Scala's `BigInt` keeps a long fast path;
  - JDK 25's opt-in compact object headers (JEP 519) take the field form to 40 bytes. GT-4 may record this; nothing
    depends on it.
- Fields need no bounds checks, and C2 can scalar-replace a non-escaping temporary in a chained expression such as
  `a + b - c`.
- An opaque type over the limb class is excluded by DC-4.
- The inline continuation gives one reviewed algorithm per operation for both representations without boxing.
  DC-5 ("no closures capturing mutable state") holds because no closure survives compilation.

**Alternatives considered.**
- `Array[Long](4)` inside the value: one signature for every kernel, but it costs one more object and header per
  result. Rejected.
- Two implementations, one per form: they could diverge. Rejected; the user's one-code-path rule applies to algorithms
  too.
- Array kernels for every operation, with the value type always copying into a scratch: this is the **fallback**, and
  the next paragraph says when it is taken.

**Verification (S1, before the swap commit merges).**
- Check: `javap -c -p` on `UInt256.class`, `UInt256$.class` and `UInt256Kernels*.class`, and after S3 on `Stack.class`
  and the arithmetic opcode classes, shows no `invokedynamic` bootstrapped by `LambdaMetafactory`.
- **If the check fails**, all kernels become array kernels: the fallback above. The choice is recorded in §B, and
  mithril reviews it.
- Scala 3.4+ `@publicInBinary` may stand in for the accessors the inliner would otherwise generate for private
  members.

## R-2 OQ-2: EXP is square-and-multiply in Z/2²⁵⁶, with no 512-bit intermediate

**Decision.**
- `**` computes `base^e mod 2^256` by right-to-left square-and-multiply over the bits of `e`, from bit 0 up to
  `e`'s bit length.
- Every multiply is truncated: 256 × 256 → the low 256 bits.
- `e = 0` returns `One` (E19: 0⁰ = 1).

**Rationale.**
- Reduction mod 2²⁵⁶ is a ring homomorphism ℤ → ℤ/2²⁵⁶ℤ. Every intermediate product reduced mod 2²⁵⁶ therefore
  gives exactly `BigInteger.modPow(e, 2^256)` for every `e ≥ 0`.
- `modPow` returns `1 mod m` = 1 for exponent 0, and 0 for base 0 with `e > 0`.
- The modulus is a power of two, so a 512-bit intermediate adds nothing: its high half is discarded either way.
- A truncated multiply needs 10 limb products (4 of them `unsignedMultiplyHigh`) where a full one needs 16.
- The worst case is 256 squarings and 256 multiplies. EXP's gas (`G_expbyte · byteSize`, E22) already prices the
  exponent length.

**Alternatives.**
- Full 512-bit products then masking: the same result with about 60% more multiplies.
- Left-to-right: equivalent.
- Keeping `BigInteger.modPow`: excluded by DC-3.

**Rows**: E19, E20, E21, and random `(base, exponent)` pairs with exponents stratified by bit length (R-12).

## R-3 Division: Knuth Algorithm D on 64-bit limbs

**Decision.**
- `div`, `mod`, `sdiv` and `smod`, and the reductions inside `addmod` and `mulmod`, use Algorithm D (Knuth, TAOCP
  vol. 2, §4.3.1):
  - normalise by `s = numberOfLeadingZeros(v_top)`;
  - estimate each quotient limb from the top two dividend limbs by a 2-by-1 division;
  - correct the estimate at most twice (D3);
  - multiply and subtract (D4);
  - add back on borrow (D6).
- The 2-by-1 step is Hacker's Delight `divlu`: two 64/32 half-steps using `java.lang.Long.divideUnsigned`, an
  intrinsic on x86_64 and aarch64 since JDK 19. It is written from the published algorithms; no code is copied.
- Fast paths, each covered by GT-2:
  - divisor zero → 0 (EVM rule, E04, E11);
  - divisor > dividend → quotient 0, remainder the dividend;
  - both values below 2⁶⁴ → `Long.divideUnsigned` and `Long.remainderUnsigned`;
  - a one-limb divisor → a chain of 2-by-1 divisions.
- **The shift trap.** The JVM masks shift distances to 6 bits, so `x >>> 64 == x`. Normalising by `s = 0` (divisor
  top bit already set) must not OR in `u >>> (64 - s)`. Rows L01 and L09 pin it.
- **Signed division.**
  - Take |a| and |b| as unsigned 256-bit values. |−2²⁵⁵| = 2²⁵⁵ fits.
  - Divide unsigned.
  - Negate the quotient when the signs differ: truncation toward zero (E09).
  - Negate the remainder when the dividend is negative: the sign follows the dividend (E12).
  - −2²⁵⁵ / −1 gives 2²⁵⁵ with equal signs, so the word is 0x80…00 (E07) and needs no special case.

**Rationale.** DC-3 allows "Knuth D or a documented equivalent". Algorithm D is the reference everyone cites, its
correction and add-back steps are where the bugs live, and both can be forced by constructed inputs (R-12, L07, L08).

**Alternatives.**
- The Möller–Granlund reciprocal 2-by-1 step, which holiman/uint256 uses: faster per step, but more code to review,
  and it buys no byte-identity. Not chosen. If the S1 profile shows DIV, MOD or MULMOD to matter, it is a reviewed
  change to one kernel, not a new design.
- Binary long division: 256 iterations. Too slow for MULMOD's 512-bit dividend.

## R-4 MULMOD and ADDMOD: the 512-bit intermediates

**MULMOD decision.**
1. Form the full 512-bit product as 8 little-endian limbs in scratch. This is a schoolbook 4 × 4: `lo = a*b` and
   `hi = Math.unsignedMultiplyHigh(a, b)` per limb pair, with carries.
2. Reduce it by Algorithm D, 8 limbs by n limbs, keeping the remainder.

Fast paths:
- N = 0 → 0 (E18);
- product high half zero → a 4-by-n reduction.

E17 (MaxValue · MaxValue mod 12 = 9) discriminates against wrap-first, which gives 1.

**ADDMOD decision.**
1. Reduce each operand first: a′ = a mod N and b′ = b mod N, each < N.
2. Add with carry out: s = a′ + b′.
3. If there is a carry out, or s ≥ N, return (s − N) mod 2²⁵⁶. Otherwise return s.

**Why this is exact.**
- a′ + b′ < 2N, so one subtraction suffices.
- With a carry out, the true sum is s + 2²⁵⁶, and s + 2²⁵⁶ − N < N, so the wrapped `s − N` is exact.
- E14 (MaxValue + 1 mod MaxValue = 1), E15 (MaxValue + 2 mod 3 = 2) and E16 pin it; wrap-first gives 0 and 1.

**Alternatives.**
- A 5-limb sum then Algorithm D: also exact, but it needs a 5-limb dividend path.
- `BigInteger`: excluded by DC-3.

## R-5 The other EVM members, as limb rules

The full table, with each member's limb rule and the rows that pin it, is in
[data-model.md §1.4](./data-model.md#14-limb-rules-member-by-member). Two decisions are taken here.

**D2 (Clarifications).** `<<`, `>>` and `sshift` take EVM semantics for every shift amount:
- a shift ≥ 256 (any upper limb non-zero, or `w0` unsigned ≥ 256) gives `Zero`;
- for `sshift`, it gives the sign fill.

These members no longer agree with the oracle above 255. GT-2 compares the members on shifts 0–255 only, and the
opcode wrappers (SHL, SHR, SAR) on every shift. E29 and E30 are recorded as unreachable from EVM paths.

**Limb-boundary rows.** The `BigInt` representation had no limb boundaries, so GT-1's table has no rows for them. A
fixed-width one does. This plan adds rows L01–L09 (data-model §9) for:
- shifts by exactly 64, 128 and 192;
- carry and borrow chains across limbs;
- compares where only a lower limb's top bit differs;
- the D3 and D6 steps of Algorithm D;
- normalisation by 0.

## R-6 Conversions, codecs and identity (FR-009; forge S1, S3, S6)

| Member | Decision | Pins |
|---|---|---|
| `apply(Long)` | `(n, s, s, s)` with `s = n >> 63`: sign extension, exactly as today | C06, P01, P02 |
| implicits `int2UInt256`, `long2UInt256`, `byte2UInt256`, `bool2UInt256`, `uint256ToBigInt`, `toUInt256` | unchanged; they compile every caller (DC-2) | test tree's 709 `int2UInt256` sites |
| `apply(BigInt)` | limb k = `n.bigInteger.shiftRight(64·k).longValue()`. `longValue` is the low 64 bits of the two's-complement form and `shiftRight` is arithmetic, so the result is n mod 2²⁵⁶ for every integer, negative or ≥ 2²⁵⁶ (**S3: masking preserved exactly**) | C05, P03 |
| `apply(ByteString)`, `apply(Array[Byte])` | `require(length <= 32)` stays: D3, input validation and the only throw. Then a big-endian fold into limbs, with no `BigInt` (GT-1: today it is a per-byte `BigInt` fold on every MLOAD and CALLDATALOAD) | C04, P04 |
| `toBigInt` | the 32-byte big-endian magnitude, then `new BigInteger(1, mag)`. **Non-negative by construction (S1).** Never `BigInt(w3) << 192 \| …` from signed longs: a set top bit would make it negative and silently change storage-trie keys. Not memoised (S8, R-14) | P05; every GT-2 word asserts `signum >= 0`, `bitLength <= 256` |
| `bytes` | 32 bytes big-endian from the limbs, built per call. The `lazy val` goes: it is outside DC-5's subset and costs a volatile field | C07, P06 |
| `toInt`, `toLong` | the low 31 bits and the low 63 bits of `w0`, exactly as today's `intValue & Int.MaxValue` and `longValue & Long.MaxValue` | C01, C02, P10 |
| `toByte` (new member, FR-009 / D5) | `w0.toByte`, equal to `BigInt.toByte`. It now wins over the implicit at MSTORE8 | C03 |
| `toHexString`, `toString`, `toDecString`, `toSignedDecString` | rendered through `toBigInt`, on cold paths only, inside the DC-3 allow-list (R-15) | C08, C09, P08, P09 |
| `toHexQuantity` (new member, S2) | `"0x"` + minimal lowercase hex, rendered from the limbs, with `"0x0"` for zero. Equal to `"0x" + toBigInt.toString(16)`. structLog uses it | S0 trace goldens |
| `equals` | limbwise for a `UInt256`. **The cooperative branch is kept** as `other == this.toBigInt`: S6's first option, so `UInt256(0) == 0` stays true and the 709-site test tree needs no migration. Main has no cross-type compare (GT-1), so it allocates only in tests | P11, StackSpec:29 |
| `hashCode` | **`BigInt.hashCode`, reproduced from the limbs without allocation** (S6's first option). For value v ≥ 0: v < 2³¹ → `v.toInt`; v < 2⁶³ → `(v ^ (v >>> 32)).toInt`; otherwise `BigInteger.hashCode`'s fold `h = 31·h + (m & 0xffffffffL)` over the big-endian 32-bit magnitude words without leading zeros. **VERIFY** the formula against the Scala 3.9 standard library source at S1; P11's ≥ 10⁷-case property (`hashCode == oracle.hashCode`) catches any slip | P11 |
| `toSign` | **retired in S2.** Its only caller is SAR's `value.toSign >= 0` (part A row 47), which becomes `isNegative`, a test of the top bit of `w3`. It is a `BigInt` round trip the caller does not need (NG-2) | E28 |
| `/` | **retired in S1 (D1).** Its one caller, `vm/EvmConfig.scala:322` (`UInt256.MaxValue / 2`, the memory-cost sentinel 2²⁵⁵−1), becomes a constant computed with `div` | P15 |
| `MaxValue`, `Zero`, `One`, `Two`, `Size` | shared instances; `apply(Boolean)` returns `One`/`Zero` | — |

**Codecs are unchanged in code.** `UInt256RLPImplicits` uses `bytes.dropWhile(_ == 0)`, JSON uses `toBigInt`, and
`Address(uint)` uses the last 20 of `bytes`. Only the representation under them changes. S0's goldens prove the bytes
are the same (S2).

## R-7 Ordering and `CanEqual` under `-source:3.9`

**Decision.**
- Keep `extends Ordered[UInt256]`. Its operators are public members (DC-2), and every `<` and `>=` in `vm/` and
  `ledger/` uses them.
- `compare` is the unsigned limb compare from data-model §1.3.
- DC-4's `given Ordering[UInt256]` is added to the companion **only if** two compile checks hold:
  - `summon[Ordering[UInt256]]` resolves to it without ambiguity against `Ordering.ordered`;
  - `-Wconf:id=E205:error` stays silent.
- If either fails, no given is added. `Ordering.ordered` then supplies the instance through `compare`, still unsigned,
  and the deviation from DC-4's wording is recorded here and reviewed by mithril.
- `derives CanEqual` is added. It has no effect without `-language:strictEquality`, which the build does not set.
  This is recorded so that nobody infers stricter equality from it.

**Pins**: P12 (2²⁵⁵ > 2²⁵⁵−1), and the EIP-7928 block access list sort (`ledger/BlockAccessListBuilder.scala:96,99`),
which must stay unsigned.

## R-8 OQ-3: the storage boundary stays `BigInt`, and is measured

**Decision.**
- Out of scope, as NG-3 and FR-006 require.
- `InMemoryWorldStateProxy` keeps `MerklePatriciaTrie[BigInt, BigInt]`, and `vm/Storage.scala` keeps
  `load(BigInt)`/`store(BigInt, BigInt)`.
- S2's T-005 item 2 converts each word to `BigInt` once **per method** instead of once per use (forge C12).
  `baseGas`/`varGas` and `exec` are separate calls:
  - SLOAD: 3 forward conversions → 2;
  - SSTORE: about 9 to 12 → 4 (GT-1's counts).

  That one `BigInt` goes to `load`, `store`, `StorageKey`, `addressAndKey` and `BlockAccessRecorder.slot`. Row F03
  pins the ETC SSTORE paths.
- The reverse conversions (`UInt256(value)` and the `UInt256(currentValue).isZero` tests) stay as written.
- GT-4 measures the remaining conversion cost after S2 and after S3: allocation by frame under `Storage`/`StorageKey`,
  and per-SLOAD/SSTORE counts (§B rows 9 and 10).

**Rationale.**
- The storage key and value types belong to spec 018's Track C, which is redesigning the state layout in `mpt/`,
  `db/` and the commit path. Retyping them here would collide with that work.
- The measurement goes to Track C as an input. If the boundary is more than 10% of interpreter allocation on the
  fixture blocks after S3, this spec records a recommendation to retype it in Track C. It does not do the retype.

**Alternative**: retype in another slice here. Rejected: these are Track C's packages, and the sign-sensitive key
encoding (GT-1) deserves Track C's own review.

## R-9 OQ-5: external test vectors and their licences

Fukuii is Apache-2.0 (`LICENSE`). **GT-2's gate does not depend on any external vector set.** The oracle, the 62 rows,
the P rows and the limb rows suffice, so the licence question only decides an optional supplement.

| Source | Licence | Compatible with Apache-2.0? | What it would add | Decision |
|---|---|---|---|---|
| holiman/uint256 (geth's `uint256`) | BSD-3-Clause, **VERIFY** at a pinned commit | yes, keeping the copyright notice and licence text with the fixture | hex case tables for unary, binary and ternary ops in its tests, **VERIFY** that they exist as data at that commit | **Import as a supplementary GT-2 fixture** (`src/test/resources/uint256/`, with a header naming the source, commit and licence) only after both VERIFYs pass at S1; otherwise skip |
| go-ethereum `core/vm/testdata/testcases_*.json` | go-ethereum's library code is LGPL-3.0, **VERIFY** | not without legal review | per-opcode results over a fixed set of interesting operands | **Do not import.** The operand set is covered by R-12's anchors; our oracle regenerates any such table |
| Besu `UInt256` tests | Apache-2.0, **VERIFY** | yes, with NOTICE attribution | mostly property tests against `BigInteger`, the same shape as ours | not imported (no marginal value) |
| Apache Tuweni `units.bigints` | Apache-2.0, project in the Apache Attic, **VERIFY** | yes | as Besu | not imported |
| ethereum/tests, EEST | already vendored or fetched (GT-3) | — | opcode semantics end to end | unchanged (GT-3) |

## R-10 The word stack against DC-8

**Layout (data-model §5).**
- One `Array[Long]`, slot-major: slot `s` holds limbs `[4s, 4s+3]`, limb 0 first.
- Slot 0 is the bottom. Depth `i` (0 = top, as today's API) is slot `size − 1 − i`.
- Capacity starts at 16 words and doubles up to `maxSize` (1024), so a full stack is 4 × 1024 longs = 32 KiB.
- **Interpretation of DC-7.** "An `Array[Long]` of 4×1024 limbs" is read as the layout and the capacity bound, not
  as a preallocation. Preallocating 32 KiB per CALL/CREATE frame costs 32 MiB per transaction at depth 1024.
  go-ethereum's stack also grows from a small initial capacity: **VERIFY** `newstack` in `core/vm/stack.go`.
  Flagged for review in plan.md.

**(a) Peek before pop.**
- Read-only accessors (contract [word-stack.md](./contracts/word-stack.md) §1) serve every pre-instruction read:
  - `varGas` and `baseGas`;
  - `address()` and `addressAndKey`;
  - `stateGasDelta` and `recordReadsBeforeOutOfGas`;
  - `availableInContext`;
  - CALL's `getParams`, `calcGas`, `callTarget`, `ownExecutionCost` and `recordCallTargets`;
  - CREATE2's salt.
- All 64 `stack.pop(...)` sites in `OpCode.scala` are classified at review point RP-4 as **peek** (a pre-instruction
  read) or **consume** (in `exec`).
- A contract test extends `OpCodeContractSpec`'s pattern. For every opcode of every table it runs `calcGas`,
  `stateGasDelta`, `recordReadsBeforeOutOfGas` and `availableInContext` on generated states and asserts that the
  stack is bit-identical afterwards (row W04).
- **The ETC matrix (forge C14).** W04, W05 and W06 also run with the ETC chain config at one block in each ETC era:
  below 1.15M; 1.15M–2.5M; 2.5M–8.772M; Atlantis; Agharta; Phoenix; Magneto; Mystique; Spiral; and Olympia at a test
  block. SSTORE's path and the EIP-2929 costs dispatch on the header's block number, not on the `EvmConfig` builder.
  W04 draws full-range words; its gas functions return the sentinel before any expansion, so huge offsets cost
  nothing.
- **CALL's gas word (C13, F04)** is read with `peek(i).toBigInt` or an unbounded compare, never `int31At` or a
  `toLong`. Before EIP-150, `gasCap` returns the raw 256-bit request.

**(b) Validate before mutate.**
- `exec` reads its operands by peek and runs every check that returns an error state carrying the pre-instruction
  stack before its first drop or push. Those checks are JUMP and JUMPI `InvalidJump`, the EIP-8024 immediate checks,
  and the CALL and CREATE preflights.
- Error paths that today return a post-pop stack keep doing so, by the same order of operations:
  - RETURNDATACOPY's overflow returns the popped stack (`OpCode.scala:748`);
  - CREATE's initcode limit returns the popped stack plus `Zero` (`:1292`).
- RP-4's table lists all 22 `withError` sites with the stack each returns today. Row W05 asserts them.

**(c) The one failure after mutation.**
- `OpCode.execute`'s EIP-8037 path (`:337-341`) runs `exec`, then may find the state charge unaffordable and returns
  `state.copy(gas = 0).withError(OutOfGas)`, the pre-instruction stack.
- In place, `exec` has already mutated the stack. So when `stateGas > 0`, `execute` checkpoints the `delta` operand
  words before `exec` and restores them on that path (row W07).
- The checkpoint is never taken before Amsterdam or on ETC, where `stateGas` is 0. Row F07 proves this over the ETC
  (config, block) matrix (forge C16); it is not just asserted.
- Only `trace_*` vmTrace's `exPush` could observe the difference. It is restored anyway, because DC-8 requires
  user-visible trace output to stay identical.

**(d) Tracers.**
- When a VM-level or `env` tracer is set, `VM.exec` passes `state.withStack(state.stack.snapshot())` as `prevState`.
- `nextState.stack` is the live stack. It is read synchronously inside `onStep` and never retained. This was checked
  for StructLogTracer (`:73-99`), VmTracer (`:83-101`), PrestateTracer (`:68-83`) and AccessListTracer (`:58`): all
  compute values inside `onStep`.
- With no tracer, no snapshot is taken and nothing is spent on it.
- `log.trace(... stack)` in `VM.exec` formats the live stack synchronously, and only when TRACE is on.

**(e) Observable behaviour.**
- `equals`: value equality over the live words, ignoring `maxSize`, as today.
- `hashCode`: the bottom-first `Seq` hash of the elements, as today (`toSeq.reverse.hashCode`). It is computed through
  `toSeq` and is a tests-only path (GT-1).
- `toString`: `"Stack(" + top-first signed decimals + ")"`.
- Row W08 checks all three against StackSpec's `Vector` model.

**(f) Other rules.**
- DUP copies four limbs, so no word is shared (part A §6 item 6, row W02).
- Mutators return `Unit`. Every old persistent-style use, such as `val (a, s1) = stack.pop()` or a `push` used as a
  `Stack`, then fails to compile instead of silently aliasing. Tests use `Stack.of(...)` and `snapshot()`.
- Overflow and underflow stay `OpCode.execute`'s errors. The Stack's lenient behaviour (pop of an empty stack yields
  zero; push onto a full one is ignored) is kept as today (NG-4, row W10).

**Alternatives.**
- A persistent array ring: it copies on every operation (part A §6).
- Keeping `List`: one cons and one `UInt256` per push, which fails G-5.
- A shared mutable ring without snapshots: it corrupts `debug_trace*` (part A §6 item 2).
- A mutable `ProgramState`: out of scope (R-17).

## R-11 Mutable `Memory` (slice S4; G-6, FR-012)

*Scope: the user folded mutable memory into this spec on 2026-11-07 (spec Clarifications, G-6, FR-012). It is slice
S4, after the word stack. `Memory`'s whole change lands in S4, its interface included, so each memory call site is
reworked once. S3 leaves `Memory` and its `UInt256`-typed API as they are.*

### R-11.1 Today's `Memory` and why it is shaped that way (Chesterton's Fence)

`vm/Memory.scala` is `class Memory private (underlying: ByteString, size: Int)`, immutable.

**`size` and the materialised prefix.**
- `size` is the EVM memory size in bytes: the highest end touched, **not rounded to 32**. MSIZE (`OpCode.scala:1075`)
  and `calcMemCost` round it at the point of use with `wordsForBytes`.
- Only `underlying`, the prefix up to the highest end **written**, is materialised. Every byte in
  `[underlying.length, size)` is zero and is never allocated.
- `load` returns slices of `underlying`, followed by views of a process-wide 1 MiB zero page. The zero page is never
  written, and its backing array is one byte longer than the page, so `toArrayUnsafe` never returns it.

**The two ethereum/tests that require this design** (comments at `Memory.scala:24-30, 48-52`):
- `randomStatetest94`: SHA3 over about 1.5 GB of never-written memory, paid for and expected to succeed;
- `static_Call1MB1024Calldepth`: 1,000,000 bytes of unwritten memory passed as CALL input down a STATICCALL recursion
  about 550 deep, under hive's `-Xmx512m`.

Materialising either one died with `OutOfMemoryError`. **The lazy zero tail must survive S4.**

**Costs and identity.**
- `store` allocates a new array of the full materialised length and copies the old prefix in (`:82-87`). That makes
  every MSTORE O(memory). G-6 exists to remove this.
- `equals` and `hashCode` are on content: `size` plus the stored bytes without trailing zeros, independent of how much
  is materialised (`:135-141`).

**Who reads it.**
- `ProgramState.memory` (`ProgramState.scala:93`) is replaced by `withMemory` at about 20 sites in `OpCode.scala`.
- StructLogTracer reads `prevState.memory` when `enableMemory` is set (`StructLogTracer.scala:56-67`: every 32-byte
  word, rendered as hex).
- VmTracer reads `nextState.memory.load(offset, 32 or 1)` after MSTORE and MSTORE8 (`VmTracer.scala:87-94`).
- No other main code reads it (grep).

### R-11.2 GT-1 part A facts that bind S4

- **T01–T05, the gas-before-exec invariant.** `calcMemCost` prices offset + size in unbounded arithmetic and returns
  the sentinel 2²⁵⁵−1 past `MaxMemory = 2^31−1`, so `exec` only sees regions ending at or below 2³¹−1, or size 0. S4
  keeps every memory access after the gas check (contract Rule M3).
- **O02**: a copy of size 0 does not expand memory, whatever the offsets.
- **O06 and G08**: MCOPY's gas uses the unbounded max(src, dst) + size. That is the past EEST
  `MCOPY_memory_expansion_cost[huge_*]` bug.
- **O07**: expansion past 2³¹−1 is out of gas.
- **§5a note 1**: fukuii's 2³¹−1 cap differs from go-ethereum's `0x1FFFFFFFE0` only for frames with about 8.8·10¹²
  gas. Kept as is.
- **T05**: CALL's out-region `expand` is a no-op for size 0.
- **T06**: the copy opcodes' source slicing (min-before-truncate) is unchanged.

### R-11.3 Decisions

**(a) The buffer.** `Memory` becomes a per-frame mutable object with three fields:
- `buf: Array[Byte]`, the materialised bytes plus spare capacity;
- `written: Int`, the materialised length, which is today's `underlying.length`;
- `size: Int`, today's `size`, unrounded.

**Invariant**: every byte of `buf` at or past `written` is zero. Writes touch only `[0, written)` after growing it,
and memory never shrinks within a frame, so a fresh capacity region is already zero.

**(b) Growth policy.**
- A write ending at `e > buf.length` reallocates to `max(e, buf.length + buf.length / 2, 1024)`, clamped to the JVM
  array limit (`Int.MaxValue − 8`), and copies `[0, written)`.
- Growth is amortised O(1) per byte, with at most 50% slack.
- The first array is allocated on the first write, so a frame that never writes memory allocates no buffer.
- **Only writes materialise.** Loads, `expand` and SHA3 move `size` only. That is today's rule and the
  `randomStatetest94` fix.
- A write whose end exceeds the array limit fails as today's `new Array[Byte](newLen)` does. Gas makes it unreachable
  below 2⁶³ gas (part A §5a).
- go-ethereum resizes its slice to the exact rounded size on every expansion. That is not copied, because fukuii's
  `size` is unrounded and the zero tail is lazy.

**(c) Ownership, and what crosses a frame boundary.**
- A buffer belongs to exactly one frame. It is created with the frame's `ProgramState` (`Memory.empty`), it is shared
  only by that frame's `ProgramState` copies (the same rule as the stack, R-10), and it dies with the frame.
- There is **no pool**. Reuse across frames would make every escaped view a hazard.

**Two ways bytes leave the buffer** (forge re-check R1-a, R1-c):
- **An owned copy**: `copyOut(offset, size): ByteString` copies the materialised overlap into a fresh array and
  appends zero-page views for the rest, exactly today's `doLoad` shape.
- **A read-only view**: `view(offset, size): ByteString` shares `buf` for the overlap with `[0, written)`, plus
  zero-page views for the rest. No copy.

In both, the unwritten tail is never materialised, so the `static_Call1MB1024Calldepth` bound holds.

**Rule: copy only what the escape pays for.** An owned copy costs O(bytes). It is allowed only where the operation's
gas already pays per byte of that escape. Anything else gets a view, and an owned copy is taken only at the point
where the view would outlive its validity.

**Why CALL input must be a view, not a copy.**
- A CALL costs a constant amount of gas once its input region is paid for: 100 to a warm address under EIP-2929, and
  40 before EIP-150.
- A per-call `copyOut` would turn that into O(input) bytes copied per call. Writing 256 KiB costs about 0.16 Mgas,
  after which an ETC block of about 8 Mgas (**VERIFY** the current limit) fits about 60,000 warm CALLs: about 15 GB
  copied by one block. It is worse before EIP-150, and worse again on ETH's larger blocks.
- That is a liveness DoS that no gate would see: S8 measures ordinary traffic, and GT-2 compares memory states.
- Today the immutable `load` shares a slice, at O(1) per CALL. go-ethereum passes a slice of the caller's memory
  (`GetPtr`; **VERIFY** at a pinned commit).

**Why a view is safe for CALL input.**
- The view is valid for the duration of `vm.call`. The caller's frame is suspended, and Rule M6 lets no other frame
  write its buffer. Re-entry into the same contract is a new frame with a new buffer.
- The callee only reads its input. CALLDATACOPY writes the bytes into the callee's own buffer, and CALLDATALOAD
  reads them into its stack.
- Where the input outlives the call, an owned copy is taken at that point:
  - `InternalTransaction.data`, which is retained only when `traceInternalTransactions` is set
    (`ProgramState.scala:295-296`; the default is false, `EvmConfig.scala:142,153`);
  - the tracers' `onCallEnter`, only when a tracer is active.
- **Precompile output is always owned.** The VM's precompile-call wrapper copies `returnData` before it leaves
  `vm.call`.
  - The identity precompile returns its input. Without the copy, RETURNDATA would alias the caller's buffer, and a
    later MSTORE would change RETURNDATACOPY's result: a consensus bug (row M15 (a)).
  - The copy is bounded by gas: identity charges per word. go-ethereum's `dataCopy` copies for the same reason
    (**VERIFY**).
  - NG-6 holds, because the copy sits in the VM wrapper, not in the precompiles.
- **The `toArrayUnsafe` audit (RP-5).** Pekko's `toArrayUnsafe` returns the backing array itself when a view covers
  the whole array. Every `toArrayUnsafe` in `vm/`, in the precompiles and in their crypto calls must be shown to be
  read-only. Today's immutable memory already relies on this rule; a mutable buffer makes a breach worse.

| Site (`OpCode.scala`) | Today | Escapes to | S4: view or owned copy | What pays for the bytes |
|---|---|---|---|---|
| CALL-family input (`:1547`) | `load` (shared slice) | the child's `ExecEnv.inputData` and precompile input, for the duration of `vm.call` | **view** | nothing per byte, so no copy (R1-a) |
| — retained input: `InternalTransaction.data` | (shared slice) | `internalTxs`, only when `traceInternalTransactions` | **owned copy**, at retention | an opt-in tracing mode, off on import |
| — retained input: tracer `onCallEnter` | (shared slice) | tracer output | **owned copy**, only when a tracer is active | the trace request |
| Precompile output (VM wrapper) | shared (identity returns its input) | the caller's RETURNDATA; CALL output write-back | **owned copy** in the wrapper | the precompile's per-word gas (identity 3 per word) |
| CREATE/CREATE2 initcode (`:1297`) | `load` | the child's program code (and any code or jump-table cache keyed on it); the deployed code comes from its RETURN | **owned copy** | the 32,000 base plus EIP-3860's initcode word gas, and the memory expansion |
| RETURN (`:1819`), REVERT (`:1829`) | `load` | `returnData`: the caller's RETURNDATA buffer, deployed contract code (state), transaction output | **owned copy** | the returning frame's own memory expansion (only written bytes are copied; the tail is zero-page views) |
| LOG0..LOG4 data (`:1245`) | `load` | `TxLogEntry.data`: receipts and the receipts root | **owned copy** | `G_logdata`, 8 gas per byte |
| SHA3 (`:592`) | `loadZeroPadded`, then `prefix.toArray` | the digest only (within the step) | the overlap copied into the digest input, as today's `toArray` does; the tail streamed. A zero-copy digest overload would touch `crypto/` and is not in this spec | 6 gas per word |
| MLOAD (`:797`) | `load` → `UInt256(ByteString)` | the stack (within the step) | in-step read into the new top slot's limbs (word transfer) | — |
| MCOPY source (`:2083`) | `load` then `store` | none | in place: (e) | — |
| CALL output write-back (`:1615-1633`, `:1670`) | `store(outOffset, output).expand(...)` | none (writes) | written in place from the child's `returnData` (always owned: RETURN's copy, or the precompile wrapper's) | the out region's expansion |
| Tracers | `load` | tracer output | **owned copy**, or a snapshot (h) | the trace request |

A missed copy is a receipt-root or code-hash divergence that GT-2 cannot see, because GT-2 compares memory, not its
consumers. It is caught by:
- the escape rows M06 and M15 (data-model §9): write the region again after the escape, and the escaped bytes are
  unchanged;
- the S0 goldens and GT-3 (receipts roots, state roots).

A copy made where nothing pays per byte is a DoS. M15 (c) catches it: allocated bytes per CALL must not depend on the
input size.

**(d) Region reads never expand; expansion commits with the operation.**
- Today a `load` returns a new, expanded `Memory`, and a path that fails attaches the *old* memory instead. Two
  examples:
  - CREATE's EIP-3860 initcode limit and its preflight out-of-gas return `state0` (`:1290-1292, :1334`);
  - RETURNDATACOPY's overflow stores nothing (`:748`).
- In place, `copyOut`, `view` and `hashRegion` **do not change `size`**.
- MLOAD's `loadWordInto` is the exception (forge re-check R1-d). It sets `size = max(size, offset + 32)`, as today,
  because MLOAD has no failure path after its gas check: it commits within its own step. M13 pins MSIZE after a read.
- Every other opcode calls `expand(end)` or writes **at the point where today's code attaches the expanded memory to
  its result**:
  - CREATE expands on every outcome after the initcode check. The comment at `:1388-1394` records why
    (go-ethereum resizes before `opCreate`; Platåberget block 319,453 tx 3);
  - CALL expands in and out on every outcome and writes its output only on success or revert (`:1621-1633`);
  - the copy opcodes write only after their checks.
- RP-5's table lists every memory-touching path, including the error paths: what `size` and which bytes each leaves
  today. Row M10 asserts them. This is FR-012's "memory left unchanged when an operation fails a check before it
  writes".
- **The ETC eras (forge R1-b).** M10 and M13 also run on the ETC (config, block) matrix, at least on the pre-Atlantis
  eras: below 1,150,000 (Frontier), 1,150,000–2,500,000 (Homestead) and 2,500,000–8,772,000. The cases:
  - CALL `InvalidCall` from depth 1024, and from insufficient balance: in and out expanded, no output;
  - a child's exceptional halt, which before Byzantium has no REVERT: in and out expanded, no output;
  - a Frontier CREATE whose code deposit cannot be paid: success with empty code, and memory expanded to the initcode
    region. From Homestead the same case is a failure, again with memory expanded;
  - F04's pre-EIP-150 CALL requests (2⁶⁴+5 and MaxValue), out of gas in `calcGas`: memory `size` and bytes unchanged;
  - LOG data (M06) on a pre-Atlantis config, so that receipts with intermediate state roots carry the escaped bytes.
    GT-3 row 10 is the consumer-side oracle.

**(e) MCOPY (EIP-5656).**
- The source is read "as if an intermediate buffer was used", which is memmove semantics. `System.arraycopy` is
  specified to copy as if through a temporary array, so overlapping regions are exact in either direction.
- Procedure:
  1. grow the buffer so that `written ≥ dst + size`;
  2. copy the source part below the old `written` with `arraycopy`;
  3. zero-fill the destination bytes whose source lay at or past the old `written`. They may hold earlier data, so
     they are written, not skipped.
- Size 0 does nothing (O02).
- Rows M05: forward and backward overlap, dst = src, a source past `written`, a source straddling `written`, and a
  source past capacity.

**(f) The copy opcodes' zero padding.**
- CALLDATACOPY, CODECOPY, EXTCODECOPY and RETURNDATACOPY write the source overlap, then **write zeros** over the rest
  of `[memOffset, memOffset + size)`, as today's `sliceBytes(...).padToByteString` stores do. The padding overwrites
  earlier bytes and materialises up to the end (row M14).
- The source slicing keeps T06's min-before-truncate.

**(g) `Int` offsets: contract Rules M1–M3.**
- A word becomes an `Int` only after the gas check, with `toInt`'s low-31-bit semantics.
- Size-zero tests read the full word.
- No memory access moves ahead of the gas check.
- MLOAD, MSTORE and MSTORE8 transfer between the buffer and the stack's limbs with no `UInt256` and no 32-byte
  `ByteString`.

**(h) The tracers' pre-step view, and what it costs.**
- `ExecutionTracer` gains `readsPreStepMemory: Boolean = false`. StructLogTracer returns its `enableMemory`. It is
  the only tracer that reads `prevState.memory`.
- When a tracer that returns `true` is active, `VM.exec` installs `state.memory.snapshot()` in the `prevState` it
  passes. That is a new `Memory` holding a copy of `[0, written)` and the same `size`; the zero tail stays lazy.
- **Cost**: one O(written) copy per step, only under `debug_trace*` with memory capture on. StructLogTracer already
  renders all of `size` as hex on every such step (`:56-67`), so the snapshot is cheaper than the rendering it
  serves. No tracer, or a tracer without memory capture (the default), means no snapshot.
- VmTracer reads `nextState.memory`, the live buffer, synchronously in `onStep`, through `copyOut`. It complies as is.
- Rows M11 and the S0 trace goldens (with memory capture on) pin this.

**(i) `ProgramState`.**
- The `memory` field keeps its type. Its meaning becomes the frame's live buffer, shared by every `copy` of the state,
  as for the stack.
- `withMemory` stays, for installing a snapshot and for tests. Its about 20 uses in `OpCode.scala` go, because
  writes happen in place.
- `equals`, `hashCode` and `toString` keep today's content semantics, materialisation-independent (row M12).

### R-11.4 Alternatives considered

- **Copy-on-write snapshots** (O(1) snapshot, with a copy on the first write after it). A cheaper trace path, but it
  adds a branch to every write on the import path. Not chosen; eager snapshots cost only when memory capture is on.
- **A buffer pool across frames**, as go-ethereum does: it saves allocation per frame, but it makes every
  `ByteString` view of memory a hazard. Not chosen, since every escape copies anyway.
- **Off-heap (`MemorySegment`) or `ByteBuffer`**: no gain over a heap `Array[Byte]` for this access pattern, and
  more API surface.
- **Rounding `size` to 32 on every expansion** (go-ethereum): it changes `Memory.size` and `equals` for no gain.
  MSIZE and gas already round at use.
- **Copying CALL input on every CALL** (this plan's first S4 draft): a constant-gas operation then copies O(input)
  bytes, a DoS (forge R1-a). Replaced by the view rule in (c).
- **Keeping memory immutable** (the plan before 2026-11-07): MSTORE stays O(memory). Superseded by the user's
  decision.

## R-12 GT-2: generators, exclusions, named rows, cost

**Oracle.** The current `domain/UInt256.scala`, copied verbatim into the test tree as
`com.chipprbots.ethereum.domain.oracle.BigIntUInt256` (renamed, otherwise unchanged; spec Assumptions). Comparison:
- word results by `toBigInt` and by `bytes`;
- `Int`, `Long`, `Boolean`, `Byte` and `String` results by value;
- `hashCode` by value;
- throws by exception type, for the named exclusions only.

**Generators.** Each operand is drawn from a mixture:
1. uniform 256-bit;
2. bit-length strata: L uniform in 0..256, then uniform below 2^L. Without this, division and EXP almost always see
   256-bit operands;
3. limb patterns: each limb independently from {0, 1, −1L, `Long.MinValue`, `Long.MaxValue`, uniform}. This is the
   carry, borrow and signed-limb generator;
4. anchors k ± δ, δ ∈ 0..64, k ∈ {0, 1, 2⁶⁴, 2¹²⁸, 2¹⁹², 2²⁵⁵, 2²⁵⁶−1}, each also negated in two's complement;
5. small values: shift amounts 0..300, SIGNEXTEND and BYTE indices 0..40, and divisors and moduli of 0 and 1 at 1/8
   weight;
6. `apply(BigInt)` inputs uniform in [−2⁶⁰⁰, 2⁶⁰⁰], and `apply(ByteString)` lengths 0..33 with leading zeros.

**Exclusions (named).**
- `<<`, `>>` and `sshift` are compared on shifts 0–255 only (E29, E30; D2).
- `apply` of 33+ bytes compares "both throw `IllegalArgumentException`" (D3).
- `/` has no case (D1).
- Nothing else is excluded: every other member is total on both sides.

**Volume.**
- Fast suite: ≥ 10⁴ cases per operation plus every named row, in Tier 1.
- Full suite: ≥ 10⁷ cases per operation plus every named row (SC-001).
- Fixed seeds per operation, derived from the operation name. Shrinking off. `workers` = available cores.
- Estimate: about 45 operations × 10⁷ = 4.5 × 10⁸ comparisons. At 1–2 µs each, including generation and the
  `BigInt` oracle, that is 8 to 15 CPU-minutes, or 2 to 5 minutes on four workers. Measured at S1 (§B row 1).

**Named rows.**
- Type level (S1): part A E01–E35 and C01–C12, the P rows P01–P16, and the limb rows L01–L09
  (data-model §9).
- Opcode level (S0):
  - part A O01–O15 and FR-010's truncation invariants: JUMP `isValidInt`, BLOBHASH bounds, `sliceBytes`
    min-before-truncate, CALL's requested gas, RETURNDATACOPY saturation;
  - forge's F01–F07: every S7 vector, ETC SSTORE across Phoenix and Magneto, pre-EIP-150 CALL gas, the Olympia
    opcodes on the ETC table, the opcode-table golden, and state-gas zero on ETC.
- Word stack (S3): W01–W10.
- Every generated and named word also asserts `toBigInt.signum >= 0` and `bitLength <= 256` (S1).

**Forcing Algorithm D's rare steps.**
- D3's double correction and D6's add-back happen with probability around 2⁻⁶⁴ on random input. They are forced by
  constructed rows L07 and L08: Hacker's Delight's add-back example from `divmnu`, scaled from 32-bit to 64-bit digits.
  - dividend limbs (little-endian) `[0, 0, 2^63, 2^63 − 1]`;
  - divisor `[1, 0, 2^63]`;
  - plus its neighbours.
- **VERIFY at S1**: that the scaled case reaches D6. Evidence: the scoverage report for `UInt256Kernels` shows the
  add-back statements covered by the named rows.

**Coverage target.** 100% of the statements and branches in `UInt256.scala` and `UInt256Kernels.scala`, from GT-2
alone (§B row 2).

**Tags.**
- The fast suite carries `VMTest`, so it runs in Tier 1.
- The full suite carries a new `DifferentialTest` tag. That tag is excluded from `testEssential` and `testStandard`
  and run by a CI step: nightly, and on PRs that touch `domain/UInt256*` or `vm/`.
- Both are deterministic (constitution III).

**S4's memory differential (FR-012).**
- **Oracle**: today's `vm/Memory.scala`, copied verbatim into the test tree as
  `com.chipprbots.ethereum.vm.oracle.ImmutableMemory`.
- **Input**: generated **operation sequences** of 1 to 64 operations, applied to a fresh oracle and a fresh mutable
  `Memory`. The operations:
  - `store` of 0–100 bytes and of word or byte values;
  - `copyOut`, `view` and the word loads, with size 0 at offsets up to 2³¹−1;
  - `loadZeroPadded`;
  - `expand(end)`;
  - MCOPY with overlapping and disjoint regions;
  - `snapshot`.
- **Offset generator**: near `written`, at the capacity growth points (1 KiB × 1.5ᵏ), at 32-byte boundaries, at the
  1 MiB zero-page boundary, and up to 4 MiB materialised. Expansions to about 1.5 GB of logical size never write.
- **Compared after every operation**:
  - `size`;
  - every byte of every read result;
  - `loadZeroPadded`'s (prefix, zero count);
  - `equals`, `hashCode` and `toString` against the oracle at the sequence end;
  - **isolation**: a snapshot or an escaped `copyOut` result is unchanged by every later operation. A `view` equals `copyOut` of the same region when it is taken; it is not expected to survive a later write (its validity ends with `vm.call`, Rule M4);
  - **heap**: the mutable buffer's capacity never exceeds 1.5 × `written` + 1 KiB, so a 1.5 GB logical size allocates
    nothing.
- **Volume**:
  - fast suite (`VMTest`): ≥ 10³ sequences plus the M rows;
  - full suite (`DifferentialTest`): ≥ 10⁶ sequences, about 3 × 10⁷ operations, plus the M rows.

  The oracle copies its whole prefix per store, so the generated sizes stay mostly small. The runtime is measured at S4
  (§B row 14).
- **Named rows**: M01–M15 (data-model §9).

## R-13 GT-3: the oracle matrix on both chains

**Rule (spec 020 R-4).** The canonical header is the oracle. A strict offline import (`fukuii <net> import <file>`,
#1651) executes each block and validates `stateRoot`, `receiptsRoot` and `gasUsed` against its header.
- Pass = every block imported, 0 failed.
- Jar A (the pre-slice tree) is re-run only for timing (R-14), not for correctness.

| # | Oracle | Chain | How | Owner |
|---|---|---|---|---|
| 1 | ethereum/tests GeneralStateTests, VMTests, BlockchainTests (ETH type; Frontier through the current fork names) | ETH, and ETC's shared EVM | `ethereum-tests-nightly.yml` dispatch on the branch, or `IntegrationTest / testOnly com.chipprbots.ethereum.ethtest.*` | beacon |
| 2 | EEST Amsterdam fixtures | ETH | `eest-amsterdam.yml` dispatch | beacon |
| 3 | hive consensus | ETH | `hive-consensus.yml` dispatch (40–80 min per suite) | beacon |
| 4 | Ephemery-164 range 135,638–147,609 (spec 020 §B row 8; Prague/Osaka, blobs) | ETH | `scripts/spec020/differential.sh ephemery` | beacon |
| 5 | A post-Amsterdam range: Platåberget if glamsterdam-devnet-8 still produces blocks, else post-Amsterdam Sepolia (spec 020 F-13 rule). EIP-8024, EIP-8037 and EIP-7928 paths, which the word stack touches | ETH | **prerequisite P-1**: freeze per spec 020 R-4, then the same script with a new case | beacon |
| 6 | (a) `LegacyPreByzantiumBlockchainTestsSpec` with `FUKUII_LEGACY_TESTS_NETWORK_TYPE=ETC` | ETC | `IntegrationTest / testOnly` | **forge** |
| 7 | (b) pre-Spiral ethereum/tests | ETC | row 1's suites for the fork names up to Shanghai, run through the one shared EVM | **forge** |
| 8 | (c) Mordor fixed range 17,207,612–17,212,611 (spec 020) | ETC | `differential.sh mordor` | **forge** |
| 9 | (c) Mordor from genesis to ≥ 10,000,000: Agharta 301,243; Phoenix 999,983; Magneto 3,985,893; Mystique 5,520,000; Spiral 9,957,000 | ETC | **prerequisite P-2**: `fukuii mordor export` from the stopped Mordor node, then a strict import into a fresh datadir | **forge** |
| 10 | (d) ETC mainnet 0–5,100,000 from genesis: Homestead 1,150,000; EIP-150 2,500,000; EIP-155/160 3,000,000; the ECIP-1017 era boundary 5,000,000. Pre-Atlantis receipts commit per-transaction intermediate state roots, so receipt-root validation covers them | ETC | **prerequisite P-3** (present): `~/fukuii-artifacts/etc-0-5100000.rlp.gz` on `fukuii-fc` (SHA-256 `8c52ee9dcb20e733065fad51a2cf289cc300546f61f13371748cc442ce1f89e8`; on the NFS share later), read from that copy only, never from a node's tree; strict import (`.gz` is read directly, `ChainImporter`). Its wall time is recorded at the S0 baseline (A) and at each slice tip (B); not a gate (C8) | **forge** |
| 10b | **ETC mainnet head window** (forge C3): a strict import of blocks H+1..H+5,000 into a copy of P-6's datadir (state at H, root verified), jar B. The header is the oracle; 0 failed. A gate **from S1 on** | ETC | **prerequisite P-6**: on a planned stop of the `fukuii-fc` node, copy its datadir; at a later planned stop, export H+1..H+5,000. Never copy a live datadir | **forge** |
| 11 | spec 017's differential set | both | only if 017 has reopened (spec GT-3) | — |

**ETC state, as of 2026-11-07.** ETC mainnet **is** synced on `fukuii-fc`: a SNAP sync with pivot 25,688,690 and a
verified root. That gives reference state at the head (row 10b), but not between 5.1M and the head, because a SNAP sync
holds state at its pivot only.

**Declared gap (forge signed it, C1).** It covers ETC mainnet blocks between 5,100,000 and the head window, including
the Atlantis, Agharta, Phoenix, Magneto, Mystique and Spiral transitions on mainnet. Their rules are covered elsewhere:
- 5.1M to Atlantis runs row 10's rules (EIP-150, EIP-155 and EIP-160 active);
- Agharta to Spiral is row 9 (Mordor from genesis);
- the pre- to post-Atlantis transition is covered by ethereum/tests' Byzantium transition cases, on the ETH type and
  through the same EVM.

What is missing is mainnet's transaction mix, not an untested rule. Either of two things closes the gap, and **no
slice waits for either**:
- spec 018's EG-12;
- a state-free strict replay of a `fukuii-fc` export from 0 to at least 19,260,000 (Spiral + 10,000), run once at S3's
  tip.

## R-14 GT-4: what is measured, where, and S8

GT-4 measures at every slice and gates none (spec Clarifications). The one exception is forge's S8 budget, which is a
sign-off condition. It is judged as follows.

**Measured.**
1. **Allocation per opcode.** `scripts/bench/blockchain-test-bench.sh` (`JFR=` recording) on fixed EVM-heavy
   fixtures: hive's `loopMul`, an arithmetic-dense GeneralStateTest, a memory-dense one, and a DIV/MULMOD/EXP-dense
   one. The opcode count is fixed per fixture: the structLog count from one traced run.
   - Report total allocated bytes ÷ opcodes (`jdk.ThreadAllocationStatistics`).
   - Report the top classes (`jfr view allocation-by-class`).
   - Report `java.math.BigInteger` samples whose stacks pass through `domain.UInt256` or the arithmetic opcode classes
     (`jfr print --events jdk.ObjectAllocationSample --stack-depth 12`). SC-003 wants zero.
2. **Trace re-execution.** On a copy of each differential's final datadir, with RPC bound to localhost
   (constitution VI), `debug_traceBlockByNumber` (default structLog tracer) over the last 64 blocks of each fixed range,
   under a JFR recording. Report allocation per traced block, the top classes, and p50/p99 latency per block.
   **VERIFY** that the datadir retains state for those 64 blocks under the default pruning, or use the range's last
   N blocks that it does retain.
3. **`[IMPORT-TIMING]`** p50 and p99 per range, by spec 020's procedure: A, B, A, B alternating, the first 200 blocks
   dropped, the median of two.

**S8 (forge).**
- S1 alone may move ETC import time either way: arithmetic allocates less, but `toBigInt` at the 59 hot implicit sites
  now allocates (GT-1).
- S1's figure is **recorded**.
- **S8 is judged at S2's tip** (forge C5): jar A = the pre-S1 tree, jar B = the S2 tip, on **both** the Mordor fixed
  range and the ETC head window (row 10b). The condition is `p50_B ≤ 1.10·p50_A` and `p99_B ≤ 1.10·p99_A` on each.
  Mordor's traffic is not ETC mainnet's.
- **Re-judged at S3's tip** (C7): the same A, with B = the S3 tip, on the same two ranges. A breach blocks S3's merge.
  S3 adds one array per frame, and pre-EIP-150 ETC makes 1024-deep recursion cheap, so per-frame allocation is an
  ETC-specific cost.
- **Re-judged at S4's tip**, by the same reasoning: one `Memory` object per frame. This is added by this plan, for
  forge to confirm.
- **The release rule** (C6):
  - no release tag, image or operator deployment is cut from a `staging` commit that has S1 without S2;
  - if S2 cannot meet S8 before the next release cut, S1 is **reverted**. It does not wait on `staging`.
- If S2's tip still breaches 10%, the response is more T-005 conversions, measured. `toBigInt` memoisation is the last
  resort, because a lazy field is outside DC-5's subset.
- **Recorded, not gated** (C8):
  - row 10's wall time at the S0 baseline and at each slice tip;
  - optionally, `[IMPORT-TIMING]` p50/p99 over the pre-EIP-150 window 2,200,000–2,500,000, the 2016 DoS-era blocks
    (**VERIFY** the window from the timings).

**SC-003 honesty.** After S3 and S4, the per-step allocations that remain are:
- the `ProgramState` copy, 26 fields;
- `BigInt` gas arithmetic;
- the storage-boundary conversions;
- the owned copies of memory that escapes: CALL input, RETURN, LOG data. These are O(region), not O(memory);
- tracer rendering, when tracing.

None of these is in scope (R-17). S4 removes memory's O(memory) copy per store, and the new `Memory` object each
memory operation allocates today.

A back-of-envelope estimate for an arithmetic opcode:
- today about 420 bytes per step: the result's ~136 bytes, the cons, the pop tuple and `Seq`, the `ProgramState`
  copy and the gas `BigInt`;
- after S3 about 160 bytes;
- so about 2.6×.

SC-003's "at least 3×" may therefore need the revision the spec already allows ("confirmed or revised from the first
profile"). The first profile at S0's tip decides. Memory opcodes gain far more after S4, because today each MSTORE
copies the whole materialised memory. §B row 4's memory-dense fixture shows that.

## R-15 FR-004 / DC-3: the usage-based check

**Decision.** Add `scripts/spec021/check_uint256_bigint.sh`, with a self-test in the style of 020's
`scripts/ci/check_scala_imports.sh`, and run it as a step inside `ci.yml`'s existing job (no new required context).

**What it checks.** It fails on `\bBigInt\b`, `BigInteger` or `toBigInt` in these places:
- `domain/UInt256.scala` and `domain/UInt256Kernels.scala`, **outside** fenced allow-list regions
  (`// DC-3 allow: <member>` … `// DC-3 end`);
- `vm/OpCode.scala` and `vm/Stack.scala`, **inside** fenced arithmetic regions (`// DC-3 arithmetic` … `// DC-3 end`)
  around UnaryOp, BinaryOp, TernaryOp, ShiftingOp, the case objects ADD to CLZ, and the in-place kernels.

**The allow-list.**
- `toBigInt`, `apply(BigInt)`, the `toUInt256` extension and `uint256ToBigInt`. These are conversions that stay
  (DC-2), and their signatures name `BigInt`.
- The string renderers.
- `equals`' cooperative branch.

Gas (`FeeSchedule`), storage (NG-3) and the tracers are out of scope, as DC-3 says.

**Staging.**
- The `domain/` half lands in S1.
- The opcode half lands at S2's end, after CLZ (`toBigInt.bitLength`) and SAR (`toSign`) move off `BigInt`.
- The self-test proves that each half fails on a planted reference.

## R-16 DC-5: inside spec 019's subset by construction

- No `lazy val`: `bytes` and `signedN` go.
- No exception on the arithmetic path. The only `require` is in `apply(ByteString)` and `apply(Array[Byte])`, which is
  input validation (D3).
- No closure: the inline continuations disappear at compile time (R-1).
- Fixed-size arrays only. Scratch sizes are constants:
  - Algorithm D needs 9 + 4 + 5 limbs;
  - MULMOD needs 8 product limbs;
  - the checkpoint needs 7 × 4.
- No reflection.

Spec 019's subset checker does not exist yet. FR-008 is marked when it runs over `domain/UInt256*` (SC-005).

## R-17 Residual per-step costs: out of scope, measured

This spec does not change these:
- `ProgramState`'s copy per step;
- `gas: BigInt`;
- the storage boundary (R-8);
- tracer rendering.

(`Memory`'s copy-on-store is removed by S4.) GT-4 reports each one's share of allocation after S3 and after S4 (§B
rows 9 to 12), so that the next spec starts from numbers.

---

## §B Measurements (filled at each slice; the empty cells are this table's purpose)

| # | What | S0 tip (baseline) | S1 | S2 | S3 | S4 |
|---|---|---|---|---|---|---|
| 1 | GT-2 full suite: cases per op / differences / wall time | — | | | | |
| 2 | scoverage of `UInt256*.scala` from GT-2 (statements / branches) | — | | | | |
| 3 | `javap` LambdaMetafactory count in UInt256*, Stack and the arithmetic opcodes (R-1) | — | | | | |
| 4 | Bench allocation per opcode, bytes (loopMul / arith / memory / divmod) | | | | | |
| 5 | `BigInteger` allocation samples on the arithmetic path (SC-003) | | | | | |
| 6 | Trace re-execution: allocation per block, p50 / p99 ms (per range; with and without memory capture from S4) | | | | | |
| 7 | `[IMPORT-TIMING]` p50 / p99, Mordor fixed range (S8: judged at S2, re-judged at S3 and S4) | | | | | |
| 7b | `[IMPORT-TIMING]` p50 / p99, ETC head window, row 10b (S8, the same tips) | | | | | |
| 7c | Row 10 (ETC 0–5.1M) wall time; optionally p50 / p99 over 2.2M–2.5M (C8, not gated) | | | | | |
| 8 | `[IMPORT-TIMING]` p50 / p99, Ephemery and the post-Amsterdam range | | | | | |
| 9 | Storage boundary: conversions per SLOAD / SSTORE (R-8) | | | | | |
| 10 | Storage boundary: share of interpreter allocation | | | | | |
| 11 | `ProgramState` copy and gas `BigInt`: share of interpreter allocation (R-17) | | | | | |
| 12 | `Memory`: share of interpreter allocation (copy-on-store until S4; escaped copies after) (R-11) | | | | | |
| 13 | GT-3 rows 1–10b: pass / fail, with the run reference (10b from S1 on) | — | | | | |
| 14 | S4 memory differential: sequences / operations / differences / wall time | — | — | — | — | |
| 15 | Heap: `randomStatetest94` and `static_Call1MB1024Calldepth` under the bench's 512 MB heap (pass, peak heap) | | | | | |
| 16 | M15 (c): allocated bytes per warm value-free CALL with 1 KiB and 1 MiB written inputs; must not depend on the input size | | | | | |
