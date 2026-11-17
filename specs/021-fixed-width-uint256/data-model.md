# Data model: Fixed-Width UInt256 and the word stack (spec 021, Phase 1)

*Written 2026-11-07 by `beacon`. Decisions are argued in [research.md](./research.md) (R-n); the public surfaces are in
[contracts/uint256-api.md](./contracts/uint256-api.md) and [contracts/word-stack.md](./contracts/word-stack.md).
Row IDs are listed in §9: E, C and O from GT-1 part A; P from GT-1 part B (forge), added before GT-2; L, W and M new
here; F from forge's plan review (2026-11-07). The memory contract is
[contracts/memory.md](./contracts/memory.md).*

Notation:
- `wₖ` is limb k, a signed JVM `Long` read as an unsigned 64-bit digit.
- `u(x)` is that unsigned reading.
- `cmpU` is `java.lang.Long.compareUnsigned`.
- `mulHi` is `Math.unsignedMultiplyHigh`.

---

## 1. `UInt256` (`domain/UInt256.scala`)

### 1.1 Layout

```text
final class UInt256 private[domain] (
  private[ethereum] val w0: Long,   // bits   0..63   (least significant)
  private[ethereum] val w1: Long,   // bits  64..127
  private[ethereum] val w2: Long,   // bits 128..191
  private[ethereum] val w3: Long    // bits 192..255  (bit 255 = the EVM sign bit)
) extends Ordered[UInt256] derives CanEqual
```

- value = u(w0) + u(w1)·2⁶⁴ + u(w2)·2¹²⁸ + u(w3)·2¹⁹².
- **Every 4-tuple is a valid value.** There is no constructor check: today's `require(n >= 0 && n < 2^256)` goes,
  because the representation cannot hold anything else.
- The object is immutable. It has no `lazy val` (R-16), no cached `bytes` and no cached `BigInt` (R-6, R-14).
- Size: 48 bytes, one object (R-1).

### 1.2 Constants (shared instances)

| Name | Limbs (w0, w1, w2, w3) | Note |
|---|---|---|
| `Zero` | (0, 0, 0, 0) | `apply(false)` returns it |
| `One` | (1, 0, 0, 0) | `apply(true)` returns it |
| `Two` | (2, 0, 0, 0) | no `src/main` caller (part A row 3); kept |
| `MaxValue` | (−1, −1, −1, −1) | 2²⁵⁶ − 1 |
| `Size` | — | `32` |

### 1.3 Comparison, sign and equality

- **Unsigned compare** (`compare`, `<`, `>`, `<=`, `>=`, `min`, `max`, `Ordering`):
  ```text
  c = cmpU(a.w3, b.w3); if c != 0 return c
  c = cmpU(a.w2, b.w2); if c != 0 return c
  c = cmpU(a.w1, b.w1); if c != 0 return c
  return cmpU(a.w0, b.w0)
  ```
  A signed compare on any limb breaks it: E32, E35 (a 2²⁵⁵ endowment against a 2²⁵⁵−1 balance), P12, L05.
- **Sign**: `isNegative` ⇔ `w3 < 0` (bit 255).
- **Signed compare** (`slt`, `sgt`): `java.lang.Long.compare(a.w3, b.w3)` first, then `cmpU` on w2, w1 and w0. In
  two's complement the order is the signed order of the top limb, then the unsigned order of the rest (E33, L06).
- **`isZero`** ⇔ `(w0 | w1 | w2 | w3) == 0`.
- **`equals`**: for a `UInt256`, all four limbs equal. For anything else, `other == toBigInt`, the cooperative branch
  kept per S6 (R-6). It is asymmetric, exactly as today.
- **`hashCode`**: equal to `toBigInt.hashCode`, computed from the limbs without allocating (formula in R-6, VERIFY at
  S1, pinned by P11).
- **`min` and `max` on a tie** return `that`, as today (C12).

### 1.4 Limb rules, member by member

"Rows" are the named GT-2 cases that pin each rule (§9). Shift and index operands are full words; "≥ 256" means any
of w1..w3 non-zero, or `cmpU(w0, 256) >= 0`.

| Member | Limb rule | Rows |
|---|---|---|
| `+` | add with carry, low to high; the carry out of w3 is dropped | E01, L03 |
| `-` | subtract with borrow; the borrow out is dropped | E02, L04 |
| `*` | truncated 4 × 4 schoolbook: for i + j ≤ 3, accumulate `lo = a_i·b_j` into r_{i+j} and `mulHi(a_i, b_j)` into r_{i+j+1}, with carries; terms at i + j ≥ 4 are never formed | E03, L03 |
| `unary_-` | `~x + 1` | E34, O14 |
| `unary_~` | `~` per limb | E34 |
| `&`, `\|`, `^` | per limb | — |
| `div`, `mod` | divisor zero → `Zero`; else Algorithm D (§2.2), keeping q or r | E04, E05, E11, L07–L09 |
| `sdiv` | divisor zero → `Zero`; unsigned divide of the absolute values; negate q iff the signs differ (truncation toward zero) | E07–E10 |
| `smod` | divisor zero → `Zero`; unsigned remainder of the absolute values; negate r iff the dividend is negative | E12, E13 |
| `addmod(b, N)` | §3.2 | E14–E16 |
| `mulmod(b, N)` | §3.1 | E17, E18 |
| `**` | R-2: right-to-left square-and-multiply with truncated `*`, over the bits of e up to its bit length; e = 0 → `One` | E19–E21 |
| `<<` (s) | s ≥ 256 → `Zero`; else q = s/64, r = s%64, `out_k = (in_{k−q} << r) \| (if r == 0 then 0 else in_{k−q−1} >>> (64−r))` with in_{<0} = 0. **The `r == 0` branch is required: the JVM masks `>>> 64` to `>>> 0`** | E26, L01 |
| `>>` (s) | mirror of `<<` | E27, L01 |
| `sshift` (s) | s ≥ 256 → `MaxValue` if negative else `Zero`; else negative x → `~((~x) >> s)`, non-negative x → `x >> s` | E28, L01 |
| `signExtend(b)` | b ≥ 31 → `this` (index 31 is the identity by construction); else p = 8b+7, k = p/64; if bit p is set, set every bit above p, else clear them | E23, E24 |
| `getByte(i)` | i ≥ 32 → `Zero`; else `(w_{3 − i/8} >>> (56 − 8·(i%8))) & 0xff` (index 0 = the MSB) | E25 |
| `numberOfLeadingZeros` (new, S2) | first non-zero limb from w3 down: 64·(3−k) + `Long.numberOfLeadingZeros(w_k)`; zero → 256 | E31, L02 |
| `byteSize` | zero → 0; else `(256 − numberOfLeadingZeros + 7) / 8` | E22, C10, L02 |
| `fillingAdd` | add with carry; a carry out → `MaxValue` | C11 |
| `slt`, `sgt`, `isZero`, `compare` | §1.3 | E32, E33, P12 |
| `isValidInt` (new, S2) | `w1 == 0 && w2 == 0 && w3 == 0 && (w0 >>> 31) == 0` | O04, O05 |
| `toInt` | `w0.toInt & Int.MaxValue` (low 31 bits) | C01, P10 |
| `toLong` | `w0 & Long.MaxValue` (low 63 bits) | C02, P10 |
| `toByte` (new, S1) | `w0.toByte` | C03 |

### 1.5 Conversions in and out

| Member | Rule | Rows |
|---|---|---|
| `apply(Long)` and the `Int`/`Byte`/`Long` implicits | `(n, s, s, s)` with `s = n >> 63`: sign extension | C06, P01, P02 |
| `apply(Boolean)`, `bool2UInt256` | `One` or `Zero` | — |
| `apply(BigInt)`, `toUInt256` | limb k = `n.bigInteger.shiftRight(64k).longValue()`, i.e. n mod 2²⁵⁶ | C05, P03 |
| `apply(ByteString)`, `apply(Array[Byte])` | length > 32 → `IllegalArgumentException` (D3); else a big-endian fold, right-aligned; empty → `Zero` | C04, P04 |
| `toBigInt`, `uint256ToBigInt` | 32-byte big-endian magnitude → `new BigInteger(1, mag)` → `BigInt`; never negative | P05 |
| `bytes` | 32 bytes, big-endian, from w3 down to w0 | C07, P06 |
| `toHexString` | `"0x"` + lowercase hex padded to an even length (0 → `"0x00"`) | C08, P08 |
| `toHexQuantity` (new, S2) | `"0x"` + minimal lowercase hex (0 → `"0x0"`) | S0 trace goldens |
| `toString` = `toSignedDecString`, `toDecString` | signed or unsigned decimal through `toBigInt` | C09, P09 |

---

## 2. Kernels (`domain/UInt256Kernels.scala`, `private[ethereum] object UInt256Kernels`)

### 2.1 Inline kernels (the cheap operations)

Shape: `inline def add[R](a0: Long, a1: Long, a2: Long, a3: Long, b0: Long, b1: Long, b2: Long, b3: Long)(inline k: (Long, Long, Long, Long) => R): R`.
Unary and ternary shapes follow the same pattern, and predicates return `Boolean` or `Int` directly.

| Instantiation | Continuation |
|---|---|
| `UInt256` members | `(r0, r1, r2, r3) => new UInt256(r0, r1, r2, r3)` |
| The word stack (S3) | `(r0, r1, r2, r3) => { words(d) = r0; words(d+1) = r1; words(d+2) = r2; words(d+3) = r3 }` |

Covered: add, sub, mul (truncated), neg, not, and, or, xor, shl/shr/sar (s < 256, after the opcode's ≥ 256 guard),
signExtend (b < 31), byte (i < 32), lt, gt, slt, sgt, eq, isZero, numberOfLeadingZeros, fillingAdd.

### 2.2 Array kernels (the heavy operations)

Shape: `def udivrem(src: Array[Long], uOff: Int, uLen: Int, vOff: Int, q: Array[Long], qOff: Int, r: Array[Long], rOff: Int, scratch: Array[Long]): Unit`
and the members built on it: `div`, `mod`, `sdiv`, `smod`, `addmod`, `mulmod`, `exp`.

Algorithm D state (R-3), all in `scratch`, which has a fixed size:

| Region | Limbs | Content |
|---|---|---|
| `un` | 9 | dividend shifted left by s, plus one top limb (8 for MULMOD's product, + 1) |
| `vn` | 4 | divisor shifted left by s |
| `qt` | 5 | quotient limbs |
| `prod` | 8 | MULMOD's 512-bit product (§3.1) |
| `ckpt` | — | not here: the stack's checkpoint is separate (§5.4) |

The steps are D1 normalise, D2–D7 per quotient limb (estimate by 2-by-1 `divlu`, correct at most twice, multiply and
subtract, add back on borrow), and D8 unnormalise the remainder. The fast paths are listed in R-3.

### 2.3 Where each form is used

| Caller | Cheap ops | Heavy ops |
|---|---|---|
| `UInt256` members (domain, ledger, jsonrpc, tests; the interpreter before S3) | inline kernels → one new `UInt256` | copy the limbs into a fresh scratch, run the array kernel, build one `UInt256` |
| The word stack (S3) | inline kernels writing into the destination slot | the array kernel on the stack's own array and its own scratch: no allocation |

## 3. The 512-bit intermediates

### 3.1 MULMOD (a·b mod N)

```text
if N.isZero                 → Zero                                   (E18)
prod[0..7] = a × b          (schoolbook 4×4: r_{i+j} += lo(a_i·b_j); r_{i+j+1} += mulHi(a_i, b_j); carries)
if prod[4..7] == 0          → prod[0..3] mod N                        (Algorithm D, 4 by n)
else                        → prod[0..7] mod N                        (Algorithm D, 8 by n)
```

No limb of the product is dropped before the reduction. That is the property E17 discriminates
(MaxValue·MaxValue mod 12 = 9; wrap-first gives 1).

### 3.2 ADDMOD ((a + b) mod N)

```text
if N.isZero                 → Zero                                   (E16)
a' = if a >= N then a mod N else a
b' = if b >= N then b mod N else b
(s, carry) = a' + b'        (256-bit add, carry out kept)
if carry or s >= N          → s − N  (mod 2^256)                      exact: a' + b' < 2N
else                        → s
```

E14 gives 1 and E15 gives 2, so a wrap-first implementation, which gives 0 and 1, fails both.

## 4. The oracle (test tree only)

`src/test/scala/com/chipprbots/ethereum/domain/oracle/BigIntUInt256.scala` is today's class verbatim, renamed. It is
never on a main classpath. Its partial members are named exclusions in GT-2 (R-12):
- `/` by zero throws;
- `<<`, `>>` and `sshift` above 255;
- `apply` of more than 32 bytes.

S4 adds a second oracle, `src/test/scala/com/chipprbots/ethereum/vm/oracle/ImmutableMemory.scala`: today's
`vm/Memory.scala` verbatim, renamed. It is total on the operations the generator uses, so it has no exclusions.

## 5. The word stack (`vm/Stack.scala`, slice S3)

### 5.1 Layout

```text
final class Stack private (
  private var words: Array[Long],   // 4 × capacity; slot s = words(4s .. 4s+3), limb 0 first
  private var top: Int,             // live words = size; slots [0, top) are live, slot 0 = bottom
  val maxSize: Int                  // 1024 (YP 9.1), or smaller in tests
)

depth i (0 = top)  ↔  slot (top − 1 − i)  ↔  longs [4(top−1−i), 4(top−1−i)+3]

capacity: 16 words at construction → doubles on a push that needs it → never above maxSize
full stack: 4 × 1024 longs = 32 KiB  (DC-7's bound; R-10 explains growth over preallocation)
```

### 5.2 Invariants

- 0 ≤ `top` ≤ `maxSize` ≤ 1024, and capacity ≥ `top`.
- Longs at or beyond `4·top` are garbage and **never observable**: `equals`, `hashCode`, `toSeq`, `snapshot`,
  `toString` and every reader touch only live slots.
- No two slots share storage. DUP copies four limbs, and a `UInt256` pushed or popped is copied in or out.

### 5.3 State transitions (in `exec` only)

| Operation | Effect | Writes |
|---|---|---|
| `push(word)`, `pushInt`, `pushBytes`, `pushZero` | grow if `top == capacity`; slot `top` ← value; `top += 1` (ignored when `top == maxSize`, as today) | 4 longs |
| `drop(n)` | `top −= n` (n ≤ top) | none |
| unary in place | reads slot `top−1`, writes slot `top−1` | 4 longs |
| binary in place | reads slots `top−1` (a) and `top−2` (b), writes slot `top−2`, `top −= 1` | 4 longs |
| ternary in place | reads `top−1`, `top−2`, `top−3`, writes `top−3`, `top −= 2` | 4 longs |
| `dup(i)` | copies slot `top−1−i` to slot `top`, `top += 1` | 4 longs |
| `swap(i)`, `exchange(i, j)` | exchanges two slots' limbs | 8 longs |

### 5.4 The checkpoint (EIP-8037 shortfall only)

`checkpoint(n)` copies the top `n` words (n = the opcode's `delta`, at most 7) and `top` into a lazily allocated
buffer of 7 × 4 longs. `rollback()` restores them. `OpCode.execute` takes the checkpoint only when `stateGas > 0`, and
rolls back only on the path that returns `state.copy(gas = 0).withError(OutOfGas)` after `exec` (R-10 (c), row W07).
It is never taken before Amsterdam or on ETC, as row F07 proves.

### 5.5 Per-frame cost

One `Stack` object and a 64-long array (512 bytes) per frame. The heavy-kernel scratch (26 longs) and the checkpoint
buffer (28 longs) are allocated lazily, on the first use in that frame.

## 6. Views: what opcodes, gas functions and tracers get

| Consumer | Gets | Lifetime | May mutate? | Checked by |
|---|---|---|---|---|
| `calcGas`, `baseGas`, `varGas`, `stateGasDelta`, `recordReadsBeforeOutOfGas`, `availableInContext`, `address()`, `addressAndKey`, CALL's helpers, CREATE2's salt read | `state.stack`'s read-only accessors (`peek`, `isZeroAt`, `int31At`, `isValidIntAt`, `byteSizeAt`, `addressAt`) | the call | **no** | W04 (contract test over every opcode of every table) |
| `exec` / `execAndSpendGas` | the live stack: readers, then mutators, after every check that returns the pre-instruction stack | the step | yes | W05, the S0 trace goldens |
| `OpCode.execute` (EIP-8037) | `checkpoint` / `rollback` | the step | yes | W07 |
| Tracers' `prevState.stack` | a **snapshot**, a separate `Stack` with the pre-step words, taken by `VM.exec` only when a tracer is set | as long as the tracer wants | no (it is a copy) | W06, the S0 trace goldens |
| Tracers' `nextState.stack` | the live stack | **only during `onStep`**; never retained | no | contract doc; the four tracers checked (R-10 (d)) |
| `VM.exec`'s `log.trace` | the live stack, formatted synchronously | the call | no | — |
| Tracers' `prevState.memory` (S4) | a **snapshot** (`Memory.snapshot()`), taken by `VM.exec` only when an active tracer has `readsPreStepMemory` (StructLogTracer with `enableMemory`); otherwise the live buffer | as long as the tracer wants | no | M11, the S0 trace goldens with memory capture on |
| Tracers' `nextState.memory` (S4) | the live buffer, read through `copyOut` during `onStep` (VmTracer, after MSTORE and MSTORE8) | only during `onStep` | no | the S0 vmTrace goldens |
| Memory escapes (S4): initcode, RETURN, REVERT, LOG data, precompile output | owned copies (`copyOut`; the VM wrapper for precompile output) | unbounded (receipts, code, RETURNDATA) | — | M06, M15, GT-3 |
| CALL-family input (S4) | a read-only `view` of the caller's buffer; an owned copy only where retained (`InternalTransaction.data` under `traceInternalTransactions`, `onCallEnter` when tracing) | the duration of `vm.call` | no | M15, GT-3 |
| `ProgramResult` | no stack | — | — | — |
| Tests | `Stack.of(words*)` (bottom first, like today's `push(Seq)`), `snapshot()`, `toSeq` | — | — | the 15 migrated vm test files |

## 7. `ProgramState` (`vm/ProgramState.scala`)

- The field `stack: Stack = Stack.empty()` keeps its type. **Its meaning changes in S3**: it is the frame's live
  stack, and every `copy` of the state shares it. The doc comment on the field says so.
- `withStack(s)` stays, for installing a snapshot (the VM's tracer path, word-stack contract §5) and for tests.
- `stepWithStack` and `jumpWithStack` lose their stack parameter, because a mutated stack needs no re-attaching. The
  old forms are removed with their last caller; there is no back-compat overload.
- `ProgramState.equals` (case-class equality) compares the stack's content at call time. Only tests use it.
- **S4** applies the same rule to `memory: Memory = Memory.empty`: it is the frame's live buffer, shared by every
  `copy`. `withMemory` stays, for snapshots and tests; its about 20 uses in `OpCode.scala` go.

## 8. `Memory`: the per-frame mutable buffer (`vm/Memory.scala`, slice S4; research R-11)

S3 leaves `Memory` as it is today. S4 changes its representation and its interface together.

### 8.1 Layout

```text
final class Memory private (
  private var buf: Array[Byte],   // materialised bytes + spare capacity; null until the first write
  private var written: Int,       // materialised length (today's underlying.length)
  private var _size: Int          // EVM memory size in bytes, the highest end touched, NOT rounded to 32 (as today)
)

[0, written)        stored bytes (may contain zeros)
[written, buf.len)  spare capacity: always zero (invariant)
[written, size)     logical zero tail: never allocated; reads produce zero-page views
```

| Field | Range | Note |
|---|---|---|
| `size` | 0 ≤ size ≤ 2³¹ − 1 | `calcMemCost` makes anything larger out of gas before `exec` (T01–T05). MSIZE = 32 · ⌈size/32⌉, as today |
| `written` | 0 ≤ written ≤ size | grows only on a write, never on a read, `expand` or SHA3 |
| `buf.length` | written ≤ buf.length ≤ `Int.MaxValue − 8` | growth: `max(end, len + len/2, 1024)` on a write past the end (R-11.3 (b)) |

### 8.2 Invariants

- Every byte of `buf` at or past `written` is zero.
- `size`, `written` and the buffer belong to one frame. They are shared only by that frame's `ProgramState` copies,
  and never pooled or reused.
- Exactly one kind of `ByteString` shares `buf`: CALL-family input, as a read-only `view` valid for the duration of
  `vm.call`, while this frame is suspended and nothing writes its buffer.
  - Every other `ByteString` that leaves an operation is a `copyOut` copy.
  - So is any CALL input that is retained past the call.
  - The process-wide zero page is only ever read.
- `equals` and `hashCode` are on content (size, and the stored bytes without trailing zeros), independent of
  `written` and of capacity, as today.

### 8.3 Operations

| Operation | Today (`UInt256` offsets, returns a new `Memory`) | After S4 (`Int` offsets after the gas check; mutates in place) |
|---|---|---|
| MLOAD | `load(offset): (UInt256, Memory)` | `loadWordInto(offset, stack)`: 32 bytes into a new top slot's limbs; `size` = max(size, offset + 32) |
| MSTORE | `store(offset, uint)` | `storeWordFrom(offset, stack, depth)`: limbs to bytes |
| MSTORE8 | `store(offset, b: Byte)` | `storeByte(offset, b)` |
| a store of bytes | `store(offset, data: ByteString)` | `write(offset, src: ByteString)`; empty `src` → no-op (T01) |
| a copy with zero padding | `store(offset, sliceBytes(...))` | `writePadded(offset, size, src, srcFrom, srcLen)`: the overlap, then **zeros written** over the rest (M14) |
| MCOPY | `load(src, n)` then `store(dst, data)` | `move(dst, src, n)`: memmove through `System.arraycopy`, with zero fill for any source at or past `written` (M05) |
| CALL-family input | `load(offset, size)` (a shared slice) | `view(offset, size): ByteString`: a **read-only view** of `buf` plus zero-page views, with no copy, valid for the duration of `vm.call`. An owned copy is taken only where it is retained (internal-tx tracing, `onCallEnter`). It does not change `size` (R-11.3 (c)) |
| a read that escapes and is paid per byte (initcode, RETURN, REVERT, LOG) | `load(offset, size): (ByteString, Memory)` | `copyOut(offset, size): ByteString`, an owned copy plus zero-page views. **It does not change `size`**; the opcode calls `expand` when it commits (R-11.3 (d)) |
| precompile output | the precompile's `ByteString` (identity: the input itself) | copied in the VM's precompile-call wrapper before it leaves `vm.call` (R-11.3 (c)) |
| SHA3 | `loadZeroPadded` | `hashRegion(offset, size)`: the overlap copied into the digest, the tail streamed as zeros; then `expand` |
| CALL out region | `expand(offset, size)` | `expand(end: Int)`: `size = max(size, end)`; a caller with a size-zero region does not call it (T05) |
| tracer pre-step view | `prevState.memory` (immutable) | `snapshot(): Memory`, a copy of `[0, written)` with the same `size` (R-11.3 (h)) |
| `size`, `equals`, `hashCode`, `toString` | — | unchanged semantics |

### 8.4 Per-frame cost

- One `Memory` object per frame, and no buffer until the first write.
- After that, amortised O(1) per written byte, with at most 50% slack.
- Escaped regions cost O(region) each, not O(memory).

## 9. Named cases (the GT-2 and opcode-suite tables)

| IDs | Source | Level | Lands in |
|---|---|---|---|
| E01–E35, C01–C12 | GT-1 part A §5 | type (GT-2) | S1 |
| O01–O15 | GT-1 part A §5, with FR-010's invariants | opcode | S0 |
| F01–F07 | forge's plan review (C10, C12, C13, C15, C16, C17) | opcode, opcode table, ETC configs | S0 |
| P01–P16 | GT-1 part B (forge), added before GT-2; numbered here | type, codec, domain | S0 (codec rows), S1 (type rows) |
| L01–L09 | this plan (limb boundaries) | type (GT-2) | S1 |
| W01–W10 | this plan (word stack) | stack, opcode, tracer | S3 |
| M01–M15 | this plan (mutable memory, FR-012); M15 and the ETC eras of M10/M13 from forge's re-check | memory, opcode, tracer, ETC configs | S4 (tests first) |

**P rows** (from GT-1 part B, numbered here):

| ID | Case | Expected |
|---|---|---|
| P01 | `apply(Long)`: −1L, `Long.MinValue` | MaxValue; 2²⁵⁶ − 2⁶³ |
| P02 | `int2UInt256(−1)`; `byte2UInt256((-1).toByte)`; `byte2UInt256(0x80.toByte)` | MaxValue; MaxValue; 2²⁵⁶ − 128 |
| P03 | `apply(BigInt)` for {−2²⁵⁶−1, −2²⁵⁶, −2²⁵⁵, −1, 2²⁵⁶, 2²⁵⁶+1, 2⁵¹²−1, k·2²⁵⁶ ± δ} | the low 256 bits (two's complement) |
| P04 | `apply(ByteString)` of length 0..32 with leading zeros; length 33 | the value; `IllegalArgumentException` |
| P05 | `toBigInt` at 2⁶³, 2⁶⁴−1, 2¹²⁷, 2¹²⁸−1, 2¹⁹¹, 2²⁵⁵, 2²⁵⁶−1 | non-negative and exact |
| P06 | `bytes` | exactly 32 bytes, big-endian, including all-zero inner limbs and a top byte of 0x80 |
| P07 | RLP of {0, 0x7f, 0x80, 0x0100, 2²⁵⁶−1} | `0x80`; `0x7f`; `0x81 80`; `0x82 01 00`; `0xa0` ff×32 |
| P08 | `toHexString` | 0 → `"0x00"`; 0xf → `"0x0f"`; MaxValue → `"0x"` + 64 `f` |
| P09 | `toString` / `toSignedDecString` of 2²⁵⁵ and MaxValue; `toDecString` | −2²⁵⁵ and `"-1"`; unsigned |
| P10 | `toInt` of 2³¹, 2³²+5, MaxValue; `toLong` of 2⁶³, MaxValue | 0, 5, `Int.MaxValue`; 0, `Long.MaxValue` |
| P11 | `equals` with `0`, `0L`, `BigInt(0)`; `hashCode` over GT-2 words | cooperative true; `hashCode == BigInt.hashCode` |
| P12 | unsigned `compare` | 2²⁵⁵ > 2²⁵⁵ − 1 |
| P13 | `Timestamp(−1L).toUInt256` | 2⁶⁴ − 1 |
| P14 | `Address(UInt256)` | the last 20 bytes |
| P15 | the memory-cost out-of-gas sentinel (formerly `MaxValue / 2`) | exactly the `BigInt` 2²⁵⁵ − 1: a value, printed as structLog `gasCost` (C9) |
| P16 | `WorldStateProxy.getBalance` round trip | identity |

**S0 codec goldens** (forge S2; the bytes are written as literals in the test, captured from the pre-swap tree and
cross-checked against an independent source where one exists):
- Account RLP at nonce and balance ∈ {0x7f, 0x80, 0xff, 0x100, 2²⁵⁵, 2²⁵⁶−1};
- the CREATE address at nonce ∈ {1, 0x7f, 0x80, 0x100, 2⁶⁴−2};
- `toHexString` and `toString` strings;
- the `InvalidJump` error string;
- access-list storage-key bytes → tx hash;
- storage-trie key hash for slots {0, 1, 2²⁵⁵, 2²⁵⁶−1};
- JSON quantities for {0, 0xf, 2²⁵⁵, 2²⁵⁶−1};
- structLog stack hex; the Prestate nonce as a decimal.

**F rows** (forge's plan review, 2026-11-07; all S0, all green on today's tree, and unchanged through S1 to S4):

| ID | Case | Expected / asserts |
|---|---|---|
| F01 | `calcMemCost` with memSize 0 (unless stated) and (offset, size) ∈ (2²⁵⁶−1, 1), (2²⁵⁶−1, 0), (2⁶⁴, 2⁶⁴), (2³¹, 2²⁵⁶ − 2³¹) (the `UInt256` sum wraps to exactly 0), (0, 2³¹−1), (1, 2³¹−1), and one case with `memNeeded <= memSize` | the exact `BigInt` result: the sentinel 2²⁵⁵−1 where `memNeeded > 2³¹−1`, 0 for size 0 and for `memNeeded <= memSize`, else `c(memNeeded) − c(memSize)` (C9, C10) |
| F02 | `wordsForBytes` at 0, 1, 32, 33, 2⁶³−1, 2⁶³, 2⁶⁴−1, 2⁶⁴ and 2²⁵⁶−1 | exact and unbounded; 2⁶³−1 / 2⁶³ pin S2's `Long` threshold n < 2⁶³ (C10, C11) |
| F03 | SSTORE on the **ETC** chain config, by block number: below Phoenix (the Frontier rule), at Phoenix (EIP-2200), at Magneto (EIP-2929 cold and warm); keys and values in {0, 1, 2²⁵⁵, 2²⁵⁶−1} | gas, refund and the storage root as today (C12) |
| F04 | pre-EIP-150 CALL gas on an ETC Homestead config (a block between 1,150,000 and 2,500,000), for CALL, CALLCODE and DELEGATECALL: requests of 2⁶⁴+5 and MaxValue with about 10⁵ gas available | OutOfGas in `calcGas` with the stack unchanged; the charged cost is the full request plus memory plus extra (`gasCap` returns the raw request when `subGasCapDivisor` is `None`) (C13) |
| F05 | the Olympia opcodes on the ETC table, at the Olympia (test) block: MCOPY (O06's vectors), CLZ at 0, 1, 2⁶⁴ and MaxValue, BASEFEE, TLOAD and TSTORE with keys 2²⁵⁵ and MaxValue | results and gas as today (C15) |
| F06 | **opcode-table golden**: for each ETC fork (`forBlock` at each boundary block, Olympia at a test block) and each ETH fork, the list of (byte, name, delta, alpha, constant gas) | byte-identical from S0 through S4: forge S5 has a test (C17) |
| F07 | "before Amsterdam every instruction's state-gas delta is zero" (`OpCodeContractSpec`), extended to the ETC (config, block) matrix of C14 | zero everywhere on ETC, so the EIP-8037 checkpoint is provably never taken on ETC (C16) |

**L rows** (limb boundaries, new):

| ID | Case | Pins |
|---|---|---|
| L01 | SHL, SHR, SAR members and opcodes by 63, 64, 65, 127, 128, 129, 191, 192, 193 on limb-pattern words | the `>>> 64` trap |
| L02 | `numberOfLeadingZeros` and `byteSize` at 2^(64k) − 1, 2^(64k), 2^(64k) + 1 for k = 1, 2, 3 | limb hand-off |
| L03 | carry chains: (2⁶⁴−1) + 1, (2¹²⁸−1) + 1, (2¹⁹²−1) + 1, MaxValue + 1; `*` with carries into every limb | carry propagation |
| L04 | borrow chains: 2⁶⁴ − 1, 2¹²⁸ − 1, 2¹⁹² − 1, 0 − 1 | borrow propagation |
| L05 | `compare`: (`Long.MinValue`, 0, 0, 0) vs (`Long.MaxValue`, 0, 0, 0), and the same at w1, w2 and w3 | a signed-limb compare |
| L06 | `slt`/`sgt` with equal w3 and lower limbs whose top bit differs | a signed compare below w3 |
| L07 | Algorithm D add-back (D6): dividend [0, 0, 2⁶³, 2⁶³−1], divisor [1, 0, 2⁶³] (little-endian), and neighbours | D6 reached (scoverage) |
| L08 | Algorithm D double correction (D3 test true twice) | D3 |
| L09 | divisors with a top bit already set (normalisation shift 0), with one to four limbs | the `>>> 64` trap in D1 |

**W rows** (word stack, S3):

| ID | Case |
|---|---|
| W01 | PUSH1..PUSH32 truncated by the end of the code: the missing low-order bytes read as zero, as today (`vm/Program.scala:22-23`) |
| W02 | DUP then mutate the copy: the source is unchanged |
| W03 | DUP16, SWAP16, DUPN, SWAPN and EXCHANGE at the depths EIP-8024 allows |
| W04 | `calcGas`, `stateGasDelta`, `recordReadsBeforeOutOfGas` and `availableInContext` leave the stack bit-identical, for every opcode of every table, on full-range words (GT-2's anchors), over the ETH builders **and the ETC (config, block) matrix** (C14) |
| W05 | each of the 22 `withError` sites returns the stack it returns today (RP-4 table), over the same matrix |
| W06 | tracers' `prevState.stack` is the pre-step stack for SLOAD, SSTORE, BALANCE, EXTCODE*, SELFDESTRUCT, MSTORE, MSTORE8, TLOAD, TSTORE and MCOPY (C15), over the same matrix |
| W07 | EIP-8037 shortfall after `exec`: `nextState.stack` is the pre-instruction stack |
| W08 | `equals`, `hashCode` and `toString` against StackSpec's `Vector` model over random operation sequences |
| W09 | growth across 16, 32, …, 1024 words; overflow at `maxSize` |
| W10 | lenient behaviour as today: pop of an empty stack yields `Zero`; push onto a full stack is ignored |

**M rows** (mutable memory, S4; each passes on today's immutable `Memory` before the swap):

| ID | Case | Pins |
|---|---|---|
| M01 | a store of empty data at any offset, including the 2³¹−1 limit: `size` and the bytes unchanged | T01 |
| M02 | a read or `copyOut` of size 0 at any offset: no expansion | T03, O02 |
| M03 | a read past `written`: zero-filled; `size` grows to the end when the opcode commits; `written` and capacity unchanged | lazy zero tail |
| M04 | `expand(end)` with end ≤ size, and CALL's size-zero out region: no-op | T05 |
| M05 | MCOPY forward overlap (dst > src), backward overlap (dst < src), dst = src, a source past `written`, a source straddling `written`, a source past capacity, size 0 with offsets 2²⁵⁶−1 | EIP-5656, O02, O06 |
| M06 | escape isolation: LOG data, RETURN and REVERT data and CREATE initcode are unchanged after the same region is written again. This includes LOG data on a pre-Atlantis ETC config, whose receipts carry intermediate state roots | receipts, code |
| M07 | a 1,000,000-byte never-written CALL input through a deep recursion: no materialisation per level (`static_Call1MB1024Calldepth` under the bench's 512 MB heap) | `Memory.scala:24-30` |
| M08 | SHA3 over about 1.5 GB of never-written memory: the hash is correct and nothing is materialised (`randomStatetest94`) | `Memory.scala:48-52` |
| M09 | growth across 1 KiB × 1.5ᵏ; writes that straddle `written`; writes at the capacity end | growth policy |
| M10 | every memory-touching failure and early-out path leaves the `size` and bytes it leaves today. The cases are CREATE's initcode limit and preflight OOG; RETURNDATACOPY's overflow; a CALL that fails with no revert (in and out expanded, output not written), a CALL that reverts (output written), and a CALL that succeeds; and an opcode out of gas before `exec` (RP-5's table). **Also over the pre-Atlantis ETC eras** (below 1,150,000; 1,150,000–2,500,000; 2,500,000–8,772,000; forge R1-b), with these cases:<ul><li>CALL `InvalidCall` from depth 1024 and from insufficient balance;</li><li>a child's exceptional halt with no REVERT;</li><li>a Frontier CREATE whose code deposit cannot be paid (success, empty code, memory expanded), and the same case from Homestead (failure, memory expanded);</li><li>F04's pre-EIP-150 CALL requests out of gas in `calcGas` (memory unchanged).</li></ul> | FR-012 |
| M11 | the tracers' pre-step view: structLog with memory capture shows the pre-step memory for MSTORE, MSTORE8, MCOPY, CALLDATACOPY, RETURNDATACOPY and CALL | DC-8, FR-012 |
| M12 | `equals`, `hashCode` and `toString` equal the oracle's for the same content, whatever `written` and capacity are | `Memory.scala:135-147` |
| M13 | MSIZE after an expansion by MLOAD (it expands within its own step), by `expand`, by a store, and after a failed CALL; also over M10's pre-Atlantis ETC eras | G27 |
| M14 | CALLDATACOPY, CODECOPY, EXTCODECOPY and RETURNDATACOPY whose source runs past its end, over previously written memory: the padding writes zeros | T06, T08 |
| M15 | CALL input as a view (forge R1-a):<ul><li>(a) CALL the identity precompile with input region R, MSTORE over R, then RETURNDATACOPY returns the **original** bytes (the precompile output is owned);</li><li>(b) with `traceInternalTransactions` on, and with a tracer active, the recorded input is unchanged after the caller rewrites R;</li><li>(c) a bench case: a loop of warm value-free CALLs to an EOA, with written inputs of 1 KiB and of 1 MiB. Allocated bytes per CALL must not depend on the input size, within noise (§B row 16). A dependence fails S4.</li></ul> | the CALL-input DoS; RETURNDATA aliasing |

## 10. Measurements

`research.md` §B holds one row per measurement and one column per slice tip. The method of each row is in quickstart
§5.
