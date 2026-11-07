# Spec 021 GT-1 impact analysis, part A (beacon, ETH): UInt256 semantics, opcodes, the Int/Long boundary, gas, and the edge-case table

*Produced 2026-11-06 by `beacon`, read-only, against staging `49dd46d1b` (v0.10.47). Part B (`forge`: conversions, domain boundaries, codecs, ETC) is added before GT-2. Decisions D1–D5 are resolved in [spec.md](./spec.md) Clarifications (2026-11-07).*


**Tree read**: `<W>` = scratchpad `stg` worktree, origin/staging `49dd46d1b` (v0.10.47, Scala 3.9). Read-only; nothing run.

**Path shorthand**: `U:` = `src/main/scala/com/chipprbots/ethereum/domain/UInt256.scala`; `vm/X` =
`src/main/scala/com/chipprbots/ethereum/vm/X`; `dom/X` = `src/main/scala/com/chipprbots/ethereum/domain/X`;
`led/X` = `src/main/scala/com/chipprbots/ethereum/ledger/X`; `test/X` = `src/test/scala/com/chipprbots/ethereum/X`.

**Spec references**: `.claude/repo-references/` does not exist on this host, neither in `C:\Users\codyw\fukuii` nor
in `<W>`. So the EVM rules below are cited from the published Yellow Paper (Appendix H.2), execution-specs
(`src/ethereum/forks/<fork>/vm/instructions/*.py`) and the EIP texts by number. The GT-2 author should re-check each
cited rule against the local clones in WSL before freezing the table.

**Classes used**: **R** = representation change only (same semantics, new limb code); **C** = conversion that stays
(a `BigInt`/`Int` boundary that remains in phase 1); **S** = semantic hazard (an edge rule that GT-2 must pin with a
named case).

---

## 1. The current `UInt256`, member by member (GT-2's oracle)

Representation: `class UInt256 private (private val n: BigInt) extends Ordered[UInt256]` (U:71). There are two lazy
fields, `bytes` (U:81) and `signedN` (U:132). The invariant `require(n >= 0 && n < 2^256)` runs on **every**
construction (U:74). The private constants are `Modulus = 2^256` (U:15), `Mask = 2^256-1` (U:20) and
`MaxSignedValue = 2^255-1` (U:68). The reducer is `boundBigInt(n)` (U:65-66): it returns `n` unchanged if `signum >= 0`
and `bitLength <= 256`, and `n & Mask` otherwise. `BigInteger.and` works on the infinite two's-complement form, so this
is exactly `n mod 2^256` for every integer, negative ones included. This is pinned by
test/domain/UInt256Spec.scala:353-372.

### 1a. Companion

| # | Member | Line | Computes today | Edge semantics | Class |
|---|---|---|---|---|---|
| 1 | `Size` | U:13 | `32` | — | R |
| 2 | `MaxValue` | U:22 | `new UInt256(2^256-1)` | — | R |
| 3 | `Zero` / `One` / `Two` | U:24/26/28 | `new UInt256(0/1/2)` | `Two` has no caller in `src/main` (grep) | R |
| 4 | `apply(ByteString)` | U:30-32 | `require(len <= 32)`, then `ByteUtils.toBigInt` (a big-endian unsigned fold, `bytes/src/main/scala/com/chipprbots/ethereum/utils/ByteUtils.scala:39-40`), then `apply(BigInt)` | empty → 0; fewer than 32 bytes → right-aligned (zero-extended); **more than 32 bytes → `IllegalArgumentException`, even when the extra leading bytes are 0x00** (the check is on length only) | **S** |
| 5 | `apply(Array[Byte])` | U:34-35 | `apply(ByteString(array))` | same as #4 | **S** |
| 6 | `apply(BigInt)` | U:37-38 | `new UInt256(boundBigInt(n))` | total; negative → `n mod 2^256` (−1 → MaxValue); ≥ 2^256 → low 256 bits | C |
| 7 | `apply(Boolean)` | U:40-41 | `One` / `Zero` (shared instances) | — | R |
| 8 | `apply(Long)` | U:43-44 | `apply(BigInt(n))` | **sign-extends**: −1L → 2^256−1; `Long.MinValue` → 2^256−2^63. Callers rely on this (`UInt256(-1)` in SAR, vm/OpCode.scala:577). dom/Timestamp.scala:115-121 documents the rule and avoids it for uint64 timestamps | **S** |
| 9 | `extension toUInt256` | U:46 | `UInt256(bigInt)` | as #6 | C |
| 10 | `implicit uint256ToBigInt` | U:48 | `uint.toBigInt` (free today: it returns the field) | — | C |
| 11 | `implicit byte2UInt256` | U:50 | `UInt256(b)`, which resolves to `apply(Long)` by widening | **sign-extends** (`0x80.toByte` → 2^256−128) | C |
| 12 | `implicit int2UInt256` | U:52 | `apply(Long)` | sign-extends (−1 → MaxValue) | C |
| 13 | `implicit long2UInt256` | U:54 | `apply(Long)` | sign-extends | C |
| 14 | `implicit bool2UInt256` | U:56 | `apply(Boolean)` | used by LT/GT/EQ/ISZERO/SLT/SGT lambdas | R |

### 1b. Instance

| # | Member | Line | Computes today | Edge semantics | Class |
|---|---|---|---|---|---|
| 15 | `bytes` (lazy) | U:81-85 | `ByteString(n.toByteArray).takeRight(32)`, left-padded with zeros | always exactly 32 bytes, big-endian; 0 → 32×0x00; for n ≥ 2^255, `toByteArray` gives 33 bytes (a 0x00 sign byte) and `takeRight` drops it. Memoised per instance (a thread-safe lazy val) | R |
| 16 | `byteSize` | U:93 | `0` if zero, else `(bitLength-1)/8+1` | 0→0, 1..255→1, 256→2, MaxValue→32. EXP gas reads it | R |
| 17 | `getByte(i)` | U:95-96 | `if i.n > 31 then Zero else UInt256(bytes(i.n.toInt) & 0xff)` | `i` is compared as an unbounded BigInt, so huge indices give 0; index 0 is the MSB | **S** |
| 18 | `&` `\|` `^` | U:99/101/103 | `new UInt256(n op m)` | in range by construction | R |
| 19 | `unary_-` | U:105 | `UInt256(-n)`, i.e. `(-n) & Mask` | 0→0; x→2^256−x. dom/Account and vm/WorldStateProxy.scala:93 rely on it (`increaseBalance(-value)`) | R |
| 20 | `unary_~` | U:107 | `UInt256(~n)`, i.e. 2^256−1−n | — | R |
| 21 | `+` `-` `*` | U:109/111/113 | BigInt op, then `boundBigInt` | wrap mod 2^256; `*` builds the full 512-bit product, then masks | R |
| 22 | `/` | U:115 | `new UInt256(n / m)` | **throws `ArithmeticException("BigInteger divide by zero")` when m = 0** (no zeroCheck). Only `src/main` caller: vm/EvmConfig.scala:322 (`MaxValue / 2`). Test gap: UInt256Spec's `"/"` test (test/domain/UInt256Spec.scala:110-117) asserts `*`, not `/` | **S** |
| 23 | `**` | U:117 | `UInt256(n.modPow(m, 2^256))` | `BigInteger.modPow` returns 1 for exponent 0 (so 0^0 = 1) and 0 for base 0 with exponent > 0. The modulus is even, so it uses the power-of-two path (`modPow2`); the result is exact | **S** |
| 24 | `compare` | U:119 | `n.compare(m)` | unsigned (both non-negative) | R |
| 25 | `min` / `max` | U:121/123 | `compare` | on a tie both return `that` (reference identity only; values equal) | R |
| 26 | `<` `>` `<=` `>=` `compareTo` (from `Ordered`) | U:71 | via `compare` | unsigned. `Comparable` also enables `Ordering.ordered`, used by led/BlockAccessListBuilder.scala:96,99 | R |
| 27 | `isZero` | U:125 | `n == 0` (BigInt cooperative equality) | — | R |
| 28 | `<<` | U:127 | `UInt256(n << that.toInt)` | `that.toInt` = **low 31 bits** (#45). Shift ≥ 2^31 wraps: `x << 2^31` = x, `x << (2^31+1)` = 2x mod 2^256. Low-31 values from 256 to ~2^26 return 0 but allocate a huge BigInteger; near 2^31 → `ArithmeticException` (BigInteger overflow) or OOM. Only the guarded SHL path calls it (vm/OpCode.scala:515) | **S** |
| 29 | `>>` | U:129 | `UInt256(n >> that.toInt)` | low-31-bit truncation as #28: `x >> (2^31+1)` = x>>1. No OOM (right shift) | **S** |
| 30 | `div` | U:137 | `zeroCheck(that)(new UInt256(n / m))` | m = 0 → `Zero` (the by-name body is not evaluated). The body's UInt256 is implicitly turned back into BigInt and re-`apply`'d, so it allocates twice | **S** |
| 31 | `sdiv` | U:139 | `zeroCheck(UInt256(signedN / that.signedN))` | `BigInteger.divide` **truncates toward zero**. −2^255 / −1 = +2^255, which `boundBigInt` keeps as 2^255 (= −2^255 two's complement); no exception. m = 0 → 0 | **S** |
| 32 | `mod` | U:141 | `zeroCheck(UInt256(n.mod(m)))` | m = 0 → 0 | **S** |
| 33 | `smod` | U:143 | `zeroCheck(UInt256(signedN % that.signedN.abs))` | `%` is `BigInteger.remainder`, so the **sign follows the dividend**; `abs(−2^255)` = 2^255 (BigInt, no overflow); m = 0 → 0 | **S** |
| 34 | `addmod` | U:145-147 | `zeroCheck(modulus)(new UInt256((n + m) % N))` | unbounded sum (up to 2^257−2), **no wrap before the modulus**; N = 0 → 0; N = 1 → 0 | **S** |
| 35 | `mulmod` | U:149-151 | `zeroCheck(modulus)(new UInt256((n * m).mod(N)))` | full 512-bit product; N = 0 → 0 | **S** |
| 36 | `slt` / `sgt` | U:153/155 | `signedN < / > that.signedN` | `signedN` = n − 2^256 if n > 2^255−1 (U:132) | **S** |
| 37 | `sshift` | U:157 | `UInt256(signedN >> that.signedN.toInt)` | **`BigInt.toInt` here, not `UInt256.toInt`**: the low 32 bits, **signed**. A shift whose bit 31 is set gives a negative Int, and BigInt `>>` by a negative Int shifts **left** (e.g. shift 2^32−1 → value×2 mod 2^256); large ones → OOM or `ArithmeticException`. Only the guarded SAR path calls it (shift < 256, vm/OpCode.scala:577-578) | **S** |
| 38 | `signExtend(b)` | U:159-166 | if `b.n < 0 \|\| b.n > 31` then `this`; else, with idx = b, sign = bit 8·idx+7, mask = 2^(8(idx+1))−1: `n \| (MaxValue ^ mask)` when negative, else `n & mask` | index ≥ 32 (any size, BigInt compare) → identity, the same instance. Index 31 → mask = 2^256−1 → identity by computation. `MaxValue ^ mask` compiles through the implicit view of `MaxValue` to BigInt (`mask` is a BigInt) | **S** |
| 39 | `fillingAdd` | U:168-171 | `r = n + m` (unbounded); `MaxValue` if `r > MaxValue`, else `r` | saturating. RETURNDATACOPY's bounds check depends on it (vm/OpCode.scala:748) | **S** |
| 40 | `equals` | U:174-177 | `UInt256` → `n.equals(that.n)`; anything else → `other == n` | **cross-type**: `UInt256(5).equals(BigInt(5))` and `.equals(5)` are true; asymmetric (`5 == UInt256(5)` is false). test/vm/StackSpec.scala:29 (`v shouldEqual 0`) relies on it. **Part B** | C |
| 41 | `hashCode` | U:179 | `n.hashCode` (Scala BigInt hash: equals `Int.##`/`Long.##` for small values) | consistent with #40 cross-type. **Part B** | C |
| 42 | `toString` | U:181 | `toSignedDecString` | **signed** decimal: MaxValue → "-1". Used by `Stack.toString` (vm/Stack.scala:94-95) and `Account.toString` (dom/Account.scala:93) | C |
| 43 | `toDecString` / `toSignedDecString` | U:183-187 | `n.toString` / `signedN.toString` | no caller in `src/main` (grep) | C |
| 44 | `toHexString` | U:189-193 | `f"$n%x"`, padded to an even length | 0 → "0x00", 1 → "0x01", 0x100 → "0x0100", MaxValue → "0x" + 64×f; lowercase. Used by `InvalidJump.toString` (vm/ProgramError.scala:16-18), which reaches trace output | C |
| 45 | `toInt` | U:203 | `n.intValue & Int.MaxValue` | **the low 31 bits** (not saturating): 2^31 → 0; 2^32+5 → 5; MaxValue → Int.MaxValue | **S** |
| 46 | `toLong` | U:208 | `n.longValue & Long.MaxValue` | **the low 63 bits**: 2^63 → 0; 2^64+7 → 7. No `vm/` caller (grep) | **S** |
| 47 | `toSign` | U:195 | `signedN` (BigInt) | used only by SAR (vm/OpCode.scala:577) | C |
| 48 | `toBigInt` | U:198 | `n` (the same object, **no allocation today**) | becomes an allocation in phase 1 | C |
| 49 | `toByte` (named in DC-2) | **not found as a member** | `uint.toByte` resolves through `uint256ToBigInt` to `BigInt.toByte` (low 8 bits, signed) | caller: MSTORE8, vm/OpCode.scala:833. If phase 1 adds a `toByte` member, it must equal `BigInt.toByte` | C |

Public members that DC-2 does not name: `Two`, `Size`, `byteSize`, `fillingAdd`, `toSign`, `toDecString`,
`toSignedDecString`, `toString`, and the `Ordered` operators.

**Section 1 tally** (49 rows, 58 members when grouped rows are expanded): R 22, C 15, S 21.

---

## 2. Opcodes doing arithmetic, comparison, bitwise, shift, byte, sign, EXP or CLZ

Dispatch helpers:
- `UnaryOp` (vm/OpCode.scala:471-479), `BinaryOp` (481-488) and `TernaryOp` (490-498) pop through `Stack.pop`/`pop(n)`
  and apply a `UInt256` lambda. `a` is the top of the stack.
- `ShiftingOp` (509-516) returns `Zero` when `shift >= UInt256(256)` (an unsigned compare, total for any shift size),
  and only otherwise calls `f(value, shift)`.

Every one of these is a `StackOnlyOp` (441-465): the next stack is computed from the pre-instruction state.

| Opcode | Line | UInt256 ops | BigInt used directly | Bypasses UInt256? | Class |
|---|---|---|---|---|---|
| ADD 0x01 | 518 | `+` | — | no | R |
| MUL 0x02 | 520 | `*` | — | no | R |
| SUB 0x03 | 522 | `-` (a − b, a = top) | — | no | R |
| DIV 0x04 | 524 | `div` | — | no | S (÷0) |
| SDIV 0x05 | 526 | `sdiv` (`signedN`) | inside UInt256 | no | S |
| MOD 0x06 | 528 | `mod` | — | no | S (÷0) |
| SMOD 0x07 | 530 | `smod` | inside UInt256 (`abs`, `%`) | no | S |
| ADDMOD 0x08 | 532 | `addmod(b, N)` | inside UInt256 (unbounded sum) | no | S |
| MULMOD 0x09 | 534 | `mulmod(b, N)` | inside UInt256 (512-bit) | no | S |
| EXP 0x0a | 536-539 | `**` (base = top); gas: `m.byteSize` on the exponent (`Seq(_, m)`) | `G_expbyte * byteSize` (BigInt × Int) | no | S |
| SIGNEXTEND 0x0b | 541 | `b.signExtend(a)` (a = byte index = top) | inside UInt256 (`testBit`, mask) | no | S |
| LT / GT 0x10/0x11 | 543/545 | `<` / `>`, then `bool2UInt256` | — | no | R |
| SLT / SGT 0x12/0x13 | 547/549 | `slt` / `sgt` | inside UInt256 (`signedN`) | no | S |
| EQ 0x14 | 551 | `==` (UInt256.equals) | `n.equals` | no | R |
| ISZERO 0x15 | 553 | `isZero` | — | no | R |
| AND / OR / XOR 0x16-0x18 | 555-559 | `&` `\|` `^` | — | no | R |
| NOT 0x19 | 561 | `unary_~` | — | no | R |
| BYTE 0x1a | 563 | `b.getByte(a)` (a = index = top) | inside UInt256 | no | S |
| SHL 0x1b | 566 (+515) | guard `>= UInt256(256)`, then `value << shift` | inside UInt256 | no | S |
| SHR 0x1c | 569 (+515) | guard, then `>>` | inside UInt256 | no | S |
| SAR 0x1d | 572-580 | guard `>= UInt256(256)`; `value.sshift(shift)`; `UInt256(-1)` (apply(Long), sign-extended) | **`value.toSign >= 0` is a BigInt compare (577)** | **partly** (the ≥256 branch decides the sign through BigInt) | S |
| CLZ 0x1e (EIP-7939) | 585 | `UInt256(256 - …)`, i.e. apply(Long) | **`v.toBigInt.bitLength` (585)** | **yes**, it computes on BigInt | S |

Opcode tables: CLZ is in `OsakaOpCodes` (vm/OpCode.scala:202) and `AmsterdamOpCodes` (209), selected by timestamp in
vm/EvmConfig.scala:56-60 and 69-74. On ETC it is in `EtcOlympiaOpCodes` (vm/OpCode.scala:177-178). CLZ gas is `G_low` = 5,
matching go-ethereum's `GasFastStep`.

Other opcodes that call `BigInt` directly instead of going through UInt256 are listed in §3 and §4: JUMP/JUMPI
(`isValidInt`), BLOBHASH (bounds), BLOCKHASH, MSTORE8 (`toByte`), MCOPY gas, GAS, MSIZE, SLOAD/SSTORE/TLOAD/TSTORE
(BigInt storage), and the `Program.immediate` PUSH path.

**Section 2 tally** (26 opcodes): R 11, S 15.

---

## 3. Where `UInt256` meets `Int`/`Long` (Stack, Memory, ProgramState, copy/offset paths, JUMP)

The invariant that makes today's `toInt` truncations safe: every memory-touching opcode prices memory in **unbounded
BigInt** before `exec` runs (`calcMemCost`, vm/EvmConfig.scala:314-324). That function returns `MaxValue/2` ≈ 2^255
whenever `offset + size > MaxMemory = 2^31−1` (vm/EvmConfig.scala:23-25), and `OpCode.execute` turns that into OutOfGas
before `exec` (vm/OpCode.scala:320-325). So `exec` only ever sees `offset + size ≤ 2^31−1`, or `size = 0`. **Any phase 1
or phase 2 change that moves a memory access ahead of the gas check, or computes `offset+size` in wrapping UInt256 or a
truncated Long, breaks this invariant.**

| ID | Site | Line | Conversion | Safe because | Class |
|---|---|---|---|---|---|
| T01 | `Memory.store` | vm/Memory.scala:71-87 | `offset.toInt` (low 31 bits) | gas bound above; empty data returns early (72) | S |
| T02 | `Memory.load(offset)` → `doLoad(offset, 32)` | vm/Memory.scala:89-91, 114-124 | `offset.toInt` | gas bound; result goes through `UInt256(ByteString)`, a 32-step BigInt fold today | S |
| T03 | `Memory.load(offset, size)` | vm/Memory.scala:93 | `size.toInt`; `doLoad` returns empty and does not expand when `size <= 0` (115) | size 0 means no expansion, whatever the offset (the EVM rule) | S |
| T04 | `Memory.loadZeroPadded` (SHA3) | vm/Memory.scala:99-107 | `size.toInt`, `offset.toInt` | gas bound. A size of exactly 2^31 would truncate to 0, but `calcMemCost` makes it OutOfGas first | S |
| T05 | `Memory.expand` (CALL out region) | vm/Memory.scala:130-133 | `(offset + size).toInt`: wrapping add, then low 31 bits | out-region gas is priced at vm/OpCode.scala:1716-1726; `size.isZero` → no-op | S |
| T06 | `OpCode.sliceBytes` | vm/OpCode.scala:228-231 | `offset.min(len).toInt`; `(offset+size).min(len).toInt`; `size.toInt` | **min before truncation**: a huge offset clamps to `len`, so the slice is empty and zero-padded. The `offset+size` wrap is harmless because `start = len` whenever `offset ≥ len` | S |
| T07 | CALLDATALOAD | vm/OpCode.scala:662-667 | T06 with size 32 | any offset is fine (constant gas) | S |
| T08 | CALLDATACOPY / CODECOPY / EXTCODECOPY | vm/OpCode.scala:671-676, 686-691, 724-731 | T06 for the source; T01 for the destination | source offset unbounded (T06); destination gas-bound | S |
| T09 | RETURNDATACOPY bounds | vm/OpCode.scala:748 | `dataOffset.fillingAdd(size) > returnData.size` (Int → UInt256 through `int2UInt256`) | saturation, so no wrap (EIP-211) | S |
| T10 | MCOPY exec | vm/OpCode.scala:2077-2085 | through T03 then T01 | gas computed in BigInt (2098-2101) | R |
| T11 | JUMP / JUMPI destination | vm/OpCode.scala:225-226, 1055, 1070 | `pos.toBigInt.isValidInt && validJumpDestinations.contains(pos.toInt)` | `isValidInt` rejects pos ≥ 2^31, so 2^32+d cannot alias d | S |
| T12 | BLOBHASH index | vm/OpCode.scala:2016 | `index.toBigInt < hashes.size && >= 0`, then `hashes(index.toInt)` | BigInt guard first | S |
| T13 | CALL return copy | vm/OpCode.scala:1615 | `outSize.min(returnData.size).toInt` | min first | R |
| T14 | MSTORE8 | vm/OpCode.scala:833 | `value.mod(256).toByte` (`mod` is UInt256; `toByte` is through the implicit BigInt) | low 8 bits | C |
| T15 | PC / CALLDATASIZE / CODESIZE / RETURNDATASIZE / EXTCODESIZE | vm/OpCode.scala:1073, 669, 684, 743, 712 | non-negative Int → UInt256 (`int2UInt256` or `apply(Long)`) | values non-negative | R |
| T16 | PUSH1..PUSH32 | vm/Program.scala:35-49; vm/OpCode.scala:1093-1096 | ≤ 7 bytes: Long accumulation, then `UInt256(Long)` (non-negative); ≥ 8 bytes: `UInt256(BigInt(1, bytes))`; past the end of code reads 0 (vm/Program.scala:22-23) | — | R |
| T17 | SHL/SHR shift count | U:127/129 behind vm/OpCode.scala:515 | `that.toInt` | guard (< 256) | R |
| T18 | BYTE/SIGNEXTEND index | U:96, U:162 | `.toInt` / `.toByte` | guard (≤ 31) | R |
| T19 | CREATE initcode limit | vm/OpCode.scala:1290 | `inSize.toBigInt > max` | unbounded | C |
| T20 | BLOCKHASH window | vm/OpCode.scala:764-766 | `header.number.value - blockNumber > 256 \|\| blockNumber >= number` (UInt256 → BigInt through the implicit view) | unbounded | C |

`ProgramState.gas` (vm/ProgramState.scala:89), `pc: Int` (94) and `Stack.size: Int` (vm/Stack.scala:22) never convert to
or from UInt256 except PC/GAS pushes. `toLong` has **no caller in `vm/`** (grep).

**Section 3 tally** (20 sites): R 6, C 3, S 11.

---

## 4. Gas arithmetic mixing `UInt256` and `BigInt`, and ETH-only opcodes

All gas is `BigInt` (`FeeSchedule`, vm/EvmConfig.scala:608-649; `ProgramState.gas`). **Phase 1 must keep gas
unbounded.** The MCOPY comment (vm/OpCode.scala:2093-2097) records a real past bug of exactly that kind: `src + size` in
wrapping UInt256 priced a 2^256−1 offset as a 1-byte expansion (EEST `MCOPY_memory_expansion_cost[huge_*]`).

| ID | Site | Line | Mix | Class |
|---|---|---|---|---|
| G01 | `calcMemCost(memSize: BigInt, offset: BigInt, dataSize: BigInt)` | vm/EvmConfig.scala:314-324 | every caller passes UInt256 through `uint256ToBigInt`; `offset + dataSize` is unbounded; `dataSize == 0` → 0 | C (S if retyped) |
| G02 | `MaxMemory: UInt256 = 2^31−1`, compared as BigInt | vm/EvmConfig.scala:23-25, 322 | implicit view on every memory gas call | R |
| G03 | OOG sentinel `UInt256.MaxValue / 2` returned as BigInt | vm/EvmConfig.scala:322 | the `/` member (nonzero divisor) | C |
| G04 | `wordsForBytes(n: BigInt)` | vm/package.scala:9-10 | UInt256 sizes passed through the implicit view | C |
| G05 | EXP byte gas | vm/OpCode.scala:537-539 | `G_expbyte * m.byteSize` (YP H.1; EIP-160: 50/byte) | S (byteSize at limb boundaries) |
| G06 | SHA3 word gas | vm/OpCode.scala:598-602 | `G_sha3word * wordsForBytes(size)` | C |
| G07 | Copy gas: CALLDATACOPY / CODECOPY / EXTCODECOPY / RETURNDATACOPY | vm/OpCode.scala:678-682, 693-697, 733-737, 754-758 | `G_copy * wordsForBytes(size)` + G01 | C |
| G08 | MCOPY (EIP-5656, Cancun) | vm/OpCode.scala:2087-2102 | `src.toBigInt + size.toBigInt`, max, then G01 with `offset = 0` | C (must stay BigInt) |
| G09 | LOG gas | vm/OpCode.scala:1250-1255 | `G_logdata * size` (UInt256 → BigInt) | C |
| G10 | CREATE / CREATE2 initcode-word and hash gas (EIP-3860) | vm/OpCode.scala:1437-1455 | `wordsForBytes(inSize)` | C |
| G11 | CALL memory | vm/OpCode.scala:1716-1726 | `max(calcMemCost(in), calcMemCost(out))` | C |
| G12 | **CALL 63/64 (EIP-150)** | vm/OpCode.scala:1693-1703, 1736-1749, 1772-1779; vm/EvmConfig.scala:426-427 | the requested `gas` (UInt256) is passed as `g: BigInt` and becomes `g.min(gasCap(state.gas − consumed))`. Pre-EIP-150 (`subGasCapDivisor = None`) the raw request is charged and is ≥ available, so OOG. A `toLong`/`toInt` here would forward 5 gas for a request of 2^64+5 | C (S if truncated) |
| G13 | CALL `gasExtra`: value transfer / new account | vm/OpCode.scala:1781-1803 | `endowment > 0`, `isZero` (unsigned) | R |
| G14 | **EIP-7702** delegation access (Prague on ETH, by timestamp) | vm/OpCode.scala:1756-1770; also 1494-1507, 1569-1575 | no UInt256 arithmetic; `Address(to)` = low 20 bytes of `bytes` (dom/Address.scala:24) | R |
| G15a | EIP-2929 address access | vm/OpCode.scala:233-238, 407-416 | `Address(UInt256)` | R |
| G15b | EIP-2929 storage access key | vm/OpCode.scala:240-249, 418-430, 822, 828 | `StorageKey(offset.toBigInt)`. The warm set is `Set[(Address, StorageKey)]` with `StorageKey` an opaque BigInt (dom/StorageKey.scala:7; vm/ProgramState.scala:104), **not keyed by UInt256** | C |
| G16 | SLOAD | vm/OpCode.scala:815-828 | `storage.load(offset)` (implicit to BigInt), then `UInt256(value)` | C (NG-3) |
| G17 | SSTORE gas, EIP-2200 / EIP-1283 / EIP-2929 | vm/OpCode.scala:888-900, 987-1016 | BigInt storage values against `newValue.toBigInt`; `UInt256(currentValue).isZero` (1009) | C (NG-3) |
| G18 | SSTORE refunds (pre-Amsterdam) | vm/OpCode.scala:855-879 | `currentValue != newValue.toBigInt`; `UInt256(originalValue).isZero` (873); `UInt256(currentValue).isZero` (878) | C |
| G19 | SSTORE Amsterdam (EIP-8037/8038, ETH only) | vm/OpCode.scala:914-985, 1026-1036 | the same BigInt-against-`toBigInt` comparisons | C |
| G20 | SELFDESTRUCT gas / state gas | vm/OpCode.scala:1927-1974 | `ownBalance > 0` (unsigned) | R |
| G21 | CREATE EIP-8037 preflight | vm/OpCode.scala:1312-1329 | `endowment <= ownBalance` (unsigned); `nonce.toBigInt <= MaxNonce` | R |
| G22 | CALL EIP-8037 new-account state gas | vm/OpCode.scala:1466-1473 | `endowment > UInt256.Zero` | R |
| G23 | **BASEFEE** (EIP-3198) | vm/OpCode.scala:2002-2006 | `UInt256(baseFee: BigInt)`. Opcode only; the burn is elsewhere | C |
| G24 | **BLOBBASEFEE** (EIP-7516) | vm/OpCode.scala:2025-2040 | `UInt256(fee: BigInt)`, which **masks silently** if fee ≥ 2^256 (go-ethereum: `uint256.MustFromBig`, which panics). Unreachable: the fee would need excess blob gas ≈ 256·ln2·fraction. `blobBaseFeeOverride` (eth_simulateV1 only) can supply any BigInt | C |
| G25 | **BLOBHASH** (EIP-4844) | vm/OpCode.scala:2011-2018 | BigInt bounds (T12) | S |
| G26 | GAS | vm/OpCode.scala:1077 | `(state.gas − G_base).toUInt256` | C |
| G27 | MSIZE | vm/OpCode.scala:1075 | `(32 * wordsForBytes(mem.size)).toUInt256` (Int × BigInt) | C |
| G28 | TLOAD / TSTORE (EIP-1153) | vm/OpCode.scala:2053-2069 | `Map[(Address, StorageKey), BigInt]` (vm/ProgramState.scala:105) | C |
| G29 | EIP-7708 transfer log (Amsterdam, ETH only; receipt data) | vm/AmsterdamGas.scala:194-199 | `leftPadded32(value.toBigInt.toByteArray.dropWhile(0))`, byte-identical to `value.bytes` (T-005 candidate) | C |
| G30 | Intrinsic gas / EIP-2780 base | vm/EvmConfig.scala:333-395 | `value.isZero` only | R |
| G31 | Header-to-stack pushes: TIMESTAMP (776; unsigned widening, dom/Timestamp.scala:115-121), NUMBER (778), DIFFICULTY/PREVRANDAO (780-785; `mixHash` is a 32-byte ByteString), GASLIMIT (787), COINBASE (771; 20 bytes), CHAINID (1992; BigInt, Platåberget 7091047534 > 2^32), SLOTNUM (2050) | vm/OpCode.scala | BigInt or ByteString → UInt256 | C |
| G32 | CALLVALUE / GASPRICE | vm/OpCode.scala:660, 699; vm/ExecEnv.scala:76-78 | already UInt256 (built through `UInt256(tx.value)` / `UInt256(effectiveGasPrice)`, vm/ProgramContext.scala:51, 159, 180-181) | R |
| G33 | `WorldStateProxy.transfer` | vm/WorldStateProxy.scala:86-95 | `increaseBalance(-value)`: **relies on mod-2^256 `unary_-` and `+`** for subtraction | S |
| G34 | `getBalance` | vm/WorldStateProxy.scala:83-84 | `UInt256(a.balance)`, where `a.balance` is already UInt256: an implicit `toBigInt` round trip (removable) | C |

**Section 4 tally** (35 sites): R 9, C 23, S 3.

**Grand total, §1-§4** (counting §1's 58 members individually): **R 48, C 41, S 50**, 139 entries
(§1 58 + §2 26 + §3 20 + §4 35).

---

## 5. Edge-case table (the core deliverable; GT-2 must turn every row into a named case)

"Current" is read from the code cited, not executed. "Rule" sources:
- YP = Yellow Paper Appendix H.2 (instruction set) or H.1 (gas);
- ES = execution-specs `forks/<fork>/vm/instructions/<file>.py`;
- EIP-n as numbered.

Hazard: S/R/C as above, plus **OOC** = out of contract (a member-level behaviour no EVM path reaches; GT-2 must pin a
decision, see D1-D3).

| ID | Operation | Inputs | Current result (code) | EVM rule (source) | Hazard |
|---|---|---|---|---|---|
| E01 | ADD | MaxValue, 1 | 0 (U:109, mask) | mod 2^256 (YP ADD) | R |
| E02 | SUB | 0, 1 | MaxValue (U:111) | mod 2^256 (YP SUB) | R |
| E03 | MUL | MaxValue, MaxValue / 2^128, 2^128 / 2^255, 2 | 1 / 0 / 0 (U:113: 512-bit product, then mask) | mod 2^256 (YP MUL) | R |
| E04 | DIV | x, 0 (x = 0, 1, MaxValue) | 0 (U:137 zeroCheck) | 0 if divisor is 0 (YP DIV; ES arithmetic.py `div`) | S |
| E05 | DIV | MaxValue/1; 0/x; x/x; x/(x+1); MaxValue/2 | MaxValue; 0; 1; 0; 2^255−1 | floor (YP DIV) | R |
| E06 | `/` member | x, 0 | **throws ArithmeticException** (U:115) | n/a (not an EVM op) | OOC (D1) |
| E07 | SDIV | −2^255, −1 | 0x80…00 (= −2^255) (U:139; `boundBigInt(2^255)` keeps it) | −2^255 (YP SDIV's explicit clause; ES `sdiv`) | S |
| E08 | SDIV | x, 0; −1, −1; −1, MaxValue | 0; 1; 1 | YP SDIV | S |
| E09 | SDIV truncation | −7, 2 / 7, −2 / −7, −2 | −3 (0xFF…FD) / −3 / 3: truncation toward zero (`BigInteger.divide`) | sgn(a÷b)·⌊\|a÷b\|⌋, toward zero (YP SDIV). A floor-division implementation gives −4: discriminator | S |
| E10 | SDIV | −2^255, 1 / −2^255, −2^255 / 1, −2^255 / 2^255−1, −2^255 | −2^255 / 1 / 0 / −1 | YP SDIV | S |
| E11 | MOD | x, 0 / MaxValue, 1 / x, MaxValue | 0 / 0 / x (x < MaxValue) | YP MOD | S |
| E12 | SMOD sign | −7, 2 / 7, −2 / −7, −2 | −1 / 1 / −1 (`remainder`; the sign follows the dividend) | sgn(a)·(\|a\| mod \|b\|) (YP SMOD; ES `smod`). Python-style floor mod gives 1 for (−7, 2): discriminator | S |
| E13 | SMOD | −2^255, −1 / −2^255, −2^255 / x, 0 / −2^255, 2^255−1 / 2^255−1, −2^255 | 0 / 0 / 0 / −1 / 2^255−1 | YP SMOD | S |
| E14 | ADDMOD no wrap | MaxValue, 1, MaxValue | **1** (2^256 mod (2^256−1)) | intermediates not reduced mod 2^256 (YP ADDMOD; ES `addmod` uses Uint) | S |
| E15 | ADDMOD no wrap | MaxValue, 2, 3 | **2** (2^256+1 ≡ 2 mod 3). Wrap-first gives 1 | YP ADDMOD | S |
| E16 | ADDMOD | a, b, 0 / a, b, 1 / MaxValue, MaxValue, MaxValue / MaxValue, MaxValue, 2 | 0 / 0 / 0 / 0 | YP ADDMOD | S |
| E17 | MULMOD 512-bit | MaxValue, MaxValue, 12 | **9** (MaxValue ≡ 3 mod 12). Wrap-first gives 1 | YP MULMOD; ES `mulmod` | S |
| E18 | MULMOD | a, b, 0 / a, b, 1 / MaxValue, MaxValue, MaxValue / 2^255, 2, MaxValue | 0 / 0 / 0 / 1 (2^256 mod (2^256−1)) | YP MULMOD | S |
| E19 | EXP | 0, 0 | 1 (`modPow`: exponent 0 → ONE) | 0^0 = 1 (ES `exp`: `pow(base, exp, U256_CEIL_VALUE)`) | S |
| E20 | EXP | 0, k>0 / x, 0 (x = MaxValue) / 1, MaxValue | 0 / 1 / 1 | YP EXP | S |
| E21 | EXP wrap | 2, 255 / 2, 256 / MaxValue, 2 / MaxValue, MaxValue / 3, MaxValue / 0x…random, random | 2^255 / 0 / 1 / MaxValue ((−1)^odd) / oracle / oracle | a^b mod 2^256 (YP EXP); squaring must equal `modPow` bit for bit (OQ-2) | S |
| E22 | EXP gas | exponent 0 / 1 / 255 / 256 / 2^64−1 / 2^64 / MaxValue | byteSize 0 / 1 / 1 / 2 / 8 / 9 / 32 (U:93; vm/OpCode.scala:539) | G_exp + G_expbyte·(1+⌊log256 e⌋) if e > 0 (YP H.1; EIP-160) | S |
| E23 | SIGNEXTEND | b=0, x=0x80 / b=0, x=0x7f / b=0, x=0x1ff / b=30, x=2^247 | 0xFF…FF80 / 0x7f / MaxValue / 0xFF80…0 (bits ≥ 248 set) | sign bit 8b+7 (YP SIGNEXTEND; ES `signextend`) | S |
| E24 | SIGNEXTEND | b = 31, 32, 2^64, 2^255, MaxValue (any x) | x unchanged (U:160 BigInt `> 31`; index 31 → full mask) | identity for b ≥ 31 (ES: `if byte_num > 31: result = value`, and 31 is identity by construction) | S |
| E25 | BYTE | i=0, x=0x80…00 / i=31, x=…ff / i=32 / i=2^64 / i=MaxValue | 0x80 / 0xff / 0 / 0 / 0 (U:96) | 0 if i ≥ 32, MSB-first (YP BYTE; ES `get_byte`) | S |
| E26 | SHL | shift 0 / 1 / 255 (value 1) / 256 / 257 / 2^31 / 2^32 / 2^63 / 2^64 / MaxValue | v / 2v / 2^255 / 0 / 0 / 0 / 0 / 0 / 0 / 0 (vm/OpCode.scala:515 guard) | 0 for shift ≥ 256 (EIP-145) | S |
| E27 | SHR | the same shifts | v / v>>1 / v>>255 / 0 … 0 | EIP-145 | S |
| E28 | SAR | −1 by any shift / −2^255 by 255 / −2^255 by 254 / 2^255−1 by 254 / negative x by ≥ 256 (incl. 2^32−1, MaxValue) / non-negative x by ≥ 256 | MaxValue / MaxValue / −2 / 1 / MaxValue / 0 (vm/OpCode.scala:576-578) | arithmetic shift, sign-fill (EIP-145; ES `bitwise_sar`) | S |
| E29 | `<<` / `>>` members, shift ≥ 256 | x << 2^31 / x << (2^31+1) / x >> (2^31+1) / x << (2^31−1) | x / 2x / x>>1 / ArithmeticException or OOM (U:127-129, low-31-bit `toInt`) | n/a (no EVM path) | OOC (D2) |
| E30 | `sshift` member, shift ≥ 256 | x.sshift(2^32−1) / x.sshift(2^31) | x·2 mod 2^256 (BigInt.toInt = −1, so a left shift) / OOM or exception (U:157) | n/a | OOC (D2) |
| E31 | CLZ (EIP-7939) | 0 / 1 / 2^63 / 2^64−1 / 2^64 / 2^128 / 2^192 / 2^255 / MaxValue | 256 / 255 / 192 / 192 / 191 / 127 / 63 / 0 / 0 (vm/OpCode.scala:585) | 256 − bit_length(x) (EIP-7939; ES osaka `count_leading_zeros`). Limb boundaries | S |
| E32 | LT/GT unsigned | 2^255 vs 1 / 2^63 vs 2^64 / 2^64 vs 2^63 | GT true / LT true / GT true | unsigned (YP LT/GT) | R |
| E33 | SLT/SGT | 2^255 vs 1 / MaxValue vs 0 / 0 vs MaxValue / x vs x / 2^255−1 vs 2^255 | SLT true / SLT true / SGT true / false / SGT true | two's complement (YP SLT/SGT) | S |
| E34 | NOT / negation | NOT 0 / −0 / −1 / −2^255 | MaxValue / 0 / MaxValue / 2^255 | YP NOT | R |
| E35 | CALL value comparisons | endowment 2^255 against balance 2^255−1; endowment 2^255 > 0 | `<=` false, so InvalidCall (vm/VM.scala:336-338); `> 0` true (vm/OpCode.scala:1787) | unsigned (YP Θ; balance check). A signed or limb-signed compare would let 2^255 pass the balance check | S |
| C01 | `toInt` | 2^31−1 / 2^31 / 2^32+5 / MaxValue / 0 | 2^31−1 / **0** / **5** / Int.MaxValue / 0 (U:203) | n/a (internal); must stay identical | S |
| C02 | `toLong` | 2^63 / 2^64+7 / MaxValue | 0 / 7 / Long.MaxValue (U:208) | n/a | S |
| C03 | `toByte` (implicit BigInt) / MSTORE8 | 255 / 256 / 0x1ff | −1 / 0 / −1; MSTORE8 writes 0xff, 0x00, 0xff (vm/OpCode.scala:833) | MSTORE8 stores μs[1] mod 256 (YP MSTORE8) | C |
| C04 | `apply(ByteString)` | empty / [0xff] / 31×0xff / 32 bytes / 33 bytes (leading 0x00) / 33 bytes | 0 / 255 / 2^248−1 / BE value / **IllegalArgumentException** / **IllegalArgumentException** (U:31) | n/a | S (D3) |
| C05 | `apply(BigInt)` | −1 / −2^256 / −(2^256+1) / 2^256 / 2^512+3 | MaxValue / 0 / MaxValue / 0 / 3 (U:65-66) | n/a | C |
| C06 | `apply(Long)` and Int/Byte implicits | −1L / Long.MinValue / (−1: Int) / 0x80.toByte | MaxValue / 2^256−2^63 / MaxValue / 2^256−128 (sign-extended) | n/a; SAR depends on `UInt256(-1)` = MaxValue | S |
| C07 | `bytes` | 0 / 2^255 / MaxValue / 1 | 32×0x00 / 0x80‖31×0x00 / 32×0xff / 31×0x00‖0x01 (U:81-85) | n/a. Address, MSTORE, LOG topics and CREATE2 salt use it | R |
| C08 | `toHexString` | 0 / 1 / 0x100 / MaxValue | "0x00" / "0x01" / "0x0100" / "0x"+64×'f' (U:189-193) | n/a (trace/JSON; part B) | C |
| C09 | `toString` | MaxValue / 2^255 / 5 | "-1" / "−2^255" in decimal / "5" (U:181) | n/a | C |
| C10 | `byteSize` | see E22 | — | — | R |
| C11 | `fillingAdd` | MaxValue−10, 20 / 0, MaxValue−1 / MaxValue, 1 | MaxValue / MaxValue−1 / MaxValue (U:168-171) | saturation | S |
| C12 | `min`/`max` on a tie | x, x | returns `that` | n/a | R |
| O01 | CALLDATALOAD offset | len−1 / len / 2^64 / 2^255 / MaxValue (calldata non-empty) | last byte then zero padding / 0 / 0 / 0 / 0 (T06) | zero-padded read (ES memory.py `calldataload` → `buffer_read`). A toLong-first rewrite reads offset 0 for 2^64: discriminator | S |
| O02 | *COPY, size 0 | CALLDATACOPY / CODECOPY / EXTCODECOPY / MCOPY with offsets = MaxValue, size 0 | no expansion, no error, gas = base (+ access) (G01 `dataSize == 0`; vm/Memory.scala:72; vm/OpCode.scala:2080) | size 0 → no expansion (ES `calculate_gas_extend_memory` skips size 0) | S |
| O03 | RETURNDATACOPY | dataOffset = MaxValue, size 1 / dataOffset = len+1, size 0 / dataOffset = len, size 0 | halt (saturating) / halt / ok (vm/OpCode.scala:748) | halt if offset+size > len, unbounded (EIP-211; ES `returndatacopy`). Wrap-first gives 0 ≤ len, so it would wrongly succeed | S |
| O04 | JUMP / JUMPI | d valid / 2^32+d / 2^31 / MaxValue / JUMPI(cond 0, MaxValue) | jump / InvalidJump / InvalidJump / InvalidJump / no error (T11) | dest ∉ valid set → halt (ES `jump`). A toInt-only rewrite aliases 2^32+d to d | S |
| O05 | BLOBHASH (ETH Cancun+) | index 0 (1 hash) / 1 / 2^31 / 2^32 / MaxValue | hash / 0 / 0 / 0 / 0 (T12) | 0 if index ≥ len (EIP-4844). A toInt rewrite returns hashes(0) for 2^32 | S |
| O06 | MCOPY gas (ETH Cancun+) | dst=0, src=MaxValue, size=1 / src=0, dst=MaxValue, size=1 / size 0, src=dst=MaxValue | OOG / OOG / base gas only (G08) | expansion to max(src,dst)+size, unbounded (EIP-5656) | S |
| O07 | Memory expansion | offset+size = 2^31−1 / 2^31 / offset=MaxValue, size=1 / offset=MaxValue, size=0 | priced / sentinel 2^255−1, so OOG / OOG / 0 (G01) | YP H.1 C_mem; ES `calculate_memory_gas_cost` (unbounded Uint) | S |
| O08 | CALL requested gas | 2^63 / 2^64+5 / MaxValue with EIP-150 | forwards min(request, all-but-1/64) (G12) | EIP-150 (L(n) = n − ⌊n/64⌋). Truncation would forward 5 | S |
| O09 | BLOCKHASH | n = cur / cur−1 / cur−256 / cur−257 / MaxValue | 0 / hash / hash / 0 / 0 (T20) | last 256 blocks (YP BLOCKHASH) | C |
| O10 | TIMESTAMP (ETH) | header timestamp ≥ 2^63 (Long negative) | the unsigned uint64 value (dom/Timestamp.scala:121) | uint64 timestamp (YP H.2 TIMESTAMP). `UInt256(t: Long)` would push 2^256 − … | C |
| O11 | CHAINID (ETH Platåberget) | 7091047534 | exact (BigInt path, vm/OpCode.scala:1992) | EIP-1344 | C |
| O12 | CREATE2 salt | 0 / 2^255 / MaxValue | `salt.bytes` = 32 BE bytes (vm/WorldStateProxy.scala:151) | EIP-1014 (salt as bytes32) | R |
| O13 | Address from word | 2^160+a / MaxValue | low 20 bytes (dom/Address.scala:24) | low 160 bits (YP) | R |
| O14 | Balance transfer arithmetic | balance b, value v ≤ b | b + (2^256 − v) mod 2^256 = b − v (vm/WorldStateProxy.scala:93) | balance − value | S |
| O15 | SHL/SHR/SAR operand order | SHL(shift=1, value=x) | x << 1 (shift is top, vm/OpCode.scala:514) | EIP-145 | R |

**Row count: 62** (E01-E35, C01-C12, O01-O15).

### 5a. Suspected divergences from the EVM rule (reported, not "fixed" in the table)

**None in UInt256 arithmetic or any opcode semantics on reachable inputs.** Every arithmetic/sign/shift/byte/EXP/CLZ
rule above matches YP / execution-specs / EIP text on my reading. Two pre-existing notes, neither caused by the
representation and neither reachable on ETH mainnet, Sepolia or Platåberget:

1. **Memory cap** (vm/EvmConfig.scala:23-25, 322): fukuii prices any expansion past 2^31−1 bytes at 2^255−1, so it is
   always OOG. go-ethereum allows up to 0x1FFFFFFFE0 bytes, and execution-specs has no cap. They differ only for a frame
   with ≥ ~8.8·10^12 gas (C_mem at 2 GiB). That is impossible under EIP-7825 (2^24 per tx, Osaka) and current block gas
   limits, and only conceivable in synthetic 2^63-gas tests. GT-2 must keep today's behaviour (O07).
2. **BLOBBASEFEE overflow** (vm/OpCode.scala:2040): silent mod-2^256 masking vs go-ethereum's `MustFromBig` panic.
   Economically unreachable (G24).

Flag-only (non-consensus, reported because the protocol makes consensus files flag-only):
- vm/StructLogTracer.scala:96-98 says "toSeq = underlying.reverse", but `Stack.toSeq` is top-first `underlying`
  (vm/Stack.scala:84). The code is right; the comment is stale.
- test/domain/UInt256Spec.scala:110-117: the `"/"` test exercises `*`, so the `/` member is untested.

---

## 6. `Stack` (`List[UInt256]`, vm/Stack.scala) and OQ-4

**Today:** `class Stack private (underlying: List[UInt256], size: Int, maxSize: Int)` (vm/Stack.scala:22), top at the
head.
- `pop`/`push`/`dup` are O(1) cons; `swap`/`exchange` rebuild only down to the deepest index and share the tail (55-78).
- `toSeq` returns the List itself, top-first, without copying (84).
- `equals` is List structural equality (86-89).
- `hashCode` is `underlying.reverse.hashCode`, bottom-first, kept equal to the former Vector's hash (92).
- `toString` is `"Stack(" + top-first UInt256.toString + ")"` (94-95), so signed decimal.

**What depends on List or persistence semantics:**
1. **Non-destructive "pop" as peek.** OpCode.scala has **64** `stack.pop(...)` sites (grep). A large share of them run
   on the *same* pre-instruction state, in sequence, and must leave it intact:
   - `varGas` of every memory or copy op;
   - `address()` (BALANCE / EXTCODEHASH / EXTCODESIZE / EXTCODECOPY, 616-618, 652-654, 715-717, 739-741);
   - `addressAndKey` (SLOAD 824-828);
   - `stateGasDelta` (SSTORE 980, SELFDESTRUCT 1971);
   - `recordReadsBeforeOutOfGas` (1029, 1986);
   - CALL's `availableInContext` (1809), `recordCallTargets`, `callTarget`, `ownExecutionCost` and two `calcGas`
     (1494-1541 via `getParams`, 1728-1734);
   - CREATE2 re-pops the salt (1322, 1375).

   `OpCode.execute` calls `calcGas(state)`, then `stateGasDelta(state)`, then `execAndSpendGas(state, …)`
   (vm/OpCode.scala:320-342). `StackOnlyOp` documents that `nextStack` sees the pre-instruction state (441-447).
2. **Tracers read `prevState.stack` after the step.** vm/VM.scala:305-306 calls `onStep(opCode, state, newState)`.
   - StructLogTracer reads it at 73-84 and 99;
   - VmTracer reads `prevState` and `nextState` at 83-101;
   - PrestateTracer at 68-83;
   - AccessListTracer at 58-65.

   A mutable ring shared by `state` and `newState` would show the post-op stack, which corrupts `debug_trace*`,
   `trace_*`, prestate tracing and `eth_createAccessList`. None of this is consensus, but all of it is user-visible.
3. **Error paths keep the un-popped stack** (e.g. JUMP `state.withError(InvalidJump(pos))`, vm/OpCode.scala:1056; the
   static-context and underflow early-outs, 316-318). That is not consensus (`ProgramResult` carries no stack), but the
   structLog of the failing step depends on it.
4. **Value equality and hash.** `ProgramState` is a case class (vm/ProgramState.scala:86), so state equality includes
   `Stack.equals`. test/vm/StackSpec.scala:117-193 checks a Vector oracle for `toSeq`, `size`, `maxSize`, `hashCode`
   (bottom-first) and `toString` over random operation sequences. Its line 29 (`v shouldEqual 0`) also leans on
   UInt256's cross-type equals. 15 test files under `src/test/scala/.../vm/` build or compare Stacks (grep):
   StackSpec, Generators, OpCodeFunSpec, OpCodeGasSpec, OpCodeGasSpecPostEip2929, ShiftingOpCodeSpec, Push0Spec,
   CreateOpcodeSpec, CallOpFixture, OpCodeContractSpec, LargeMemoryExpansionSpec, BlockAccessRecordingSpec,
   AmsterdamOpCodesSpec, AmsterdamCodeReadSurchargeSpec and StructLogTracerSpec.
5. **`pop(n)` returns a `Seq`** consumed by `Seq(a, b, …)` extractors and by `+:` extractors in LOG
   (vm/OpCode.scala:1244, 1252). Any Seq works, but an array-backed one allocates a view or copy per pop.
6. **Sharing of values.** DUP pushes the same `UInt256` reference (vm/Stack.scala:51). That is harmless while UInt256
   is immutable (phase 1), and **unsafe once words are mutable** (phase 2 must copy on DUP).
7. **Per-frame allocation.** `ProgramState` defaults to `Stack.empty()` (vm/ProgramState.scala:92). An Array(1024) ring
   adds about 4 KB per CALL/CREATE frame.

**OQ-4 verdict (beacon):** OQ-4 says an Array ring is "cheap, no semantic change". I disagree.
- A **mutable** ring changes semantics for the peek sites (1), the tracers (2) and the error paths (3).
- A **persistent** array ring has to copy on every push or pop, which costs more than a cons cell.

Recommendation:
- Keep `List` in phase 1.
- If the cons and peek allocations matter after GT-4, do it in two separate, gated steps:
  - first add a non-destructive `peek(i)`/`apply(i)` API and migrate the peek sites. This needs no behaviour change,
    and StackSpec plus the opcode specs prove it;
  - then swap the backing store as part of phase 2 (DC-7), with tracers snapshotting the pre-step stack.

---

## Phase-1 decisions to surface (user call; byte-identity vs G-4)

- **D1 `/` by zero.** Today it throws `ArithmeticException` (U:115); the only main caller divides by 2.
  - (a) Keep the throw: exact oracle parity.
  - (b) Return 0: same as `div`. GT-2 then excludes divisor 0 for `/` and records the deliberate difference.

  beacon recommends (a), or deprecating `/` in favour of `div`.
- **D2 `<<` / `>>` / `sshift` with shift ≥ 256.** Today they truncate to 31 bits (or to 32 signed bits for `sshift`)
  and can OOM or throw. beacon recommends:
  - new members implement EVM semantics for every shift;
  - GT-2 differentials the members on [0, 255] only, and the opcode wrappers (`ShiftingOp`/SAR) on every shift;
  - document E29/E30 as unreachable from EVM paths.
- **D3 `apply` of more than 32 bytes** throws `IllegalArgumentException`. Keep it: it is validation, not arithmetic.
  Part B should confirm the RLP and JSON decoders depend on it.
- **D4 OQ-4**: defer (see §6).
- **D5** If a `toByte` member is added, it must equal `BigInt.toByte` (C03).

Spec text issues:
- **FR-004 / DC-3's import-based check is vacuous.** `BigInt` is the `scala.BigInt` alias and is visible without
  `import scala.math.BigInt`, so the check must be usage-based.
- **The check must be scoped**, to `domain/UInt256.scala` and the opcode lambdas. Gas (`FeeSchedule`), storage (NG-3)
  and tracers legitimately keep `BigInt` throughout `vm/` in phase 1.
- **The spec's counts are drifting.** `vm/` has 484 BigInt-bearing lines across 21 files (grep -c); the spec says 485
  across 18, and OpCode.scala has 150 vs the spec's 158.

## For part B (seen, not analysed here)

- **`UInt256.equals` is cross-type and asymmetric** (U:174-177) and `hashCode` is `BigInt.hashCode` (U:179).
  test/vm/StackSpec.scala:29 relies on `UInt256 == 0`.
- **UInt256 is a hash key** only in:
  - vm/BlockAccessRecorder.scala:33, 38-39;
  - led/BlockAccessListBuilder.scala:111-112;
  - vm/PrestateTracer.scala:40 (LinkedHashSet, insertion-ordered).

  The EIP-7928 outputs are re-sorted with `Ordering[UInt256]` (led/BlockAccessListBuilder.scala:96, 99), so iteration
  order is not consensus. The warm-slot sets and access lists are keyed by `StorageKey` = BigInt (dom/StorageKey.scala:7;
  dom/Transaction.scala:197), **not** UInt256. The spec's edge-case bullet ("warm-slot sets") should be corrected.
- **If a `given Ordering[UInt256]` is added (DC-4)**, check it is not ambiguous with `Ordering.ordered` (UInt256 is
  `Comparable` through `Ordered`) and that it stays unsigned.
- **EIP-7928 BAL codec** (ETH only; the BAL hash is committed in the header): `uintRLP(x.toBigInt)`
  (dom/BlockAccessList.scala:143-148), decode `UInt256(_)` (254), and strict-ascending checks with UInt256 `<` (205, 207).
- **Account wrap.** `Account.increaseBalance(-value)` wraps (dom/Account.scala:69-70 with vm/WorldStateProxy.scala:93).
  `Account.toString` prints the signed decimal (dom/Account.scala:93).
- **Round trips and sign-extending conversions:**
  - `getBalance` re-wraps (vm/WorldStateProxy.scala:84);
  - `Address(Long)` sign-extends (dom/Address.scala:30);
  - `Timestamp.toUInt256` (dom/Timestamp.scala:121);
  - `InMemoryWorldStateProxy.getBlockHash(UInt256)` goes through implicit BigInt (led/InMemoryWorldStateProxy.scala:346).
- **Nonce − 1 RLP** (vm/WorldStateProxy.scala:133; vm/VM.scala:203).
- **Storage-proxy boundary** (`Storage[BigInt]`, vm/Storage.scala:6-7): SLOAD/SSTORE pay 2-6 `toBigInt`/`apply`
  conversions each. They are free today and allocate in phase 1, so measure them for OQ-3.
- **Precompiles.** vm/PrecompiledContracts.scala has **no** UInt256 references (grep); it is BigInt only (NG-6).
- **Test-side implicit use:** test/domain/UInt256Spec.scala:346 (`x.doubleValue` through the implicit conversion).

## Top 3 phase-1 risks (beacon)

1. **Implicit-conversion fallout.** Today `toBigInt` is free (U:198). In phase 1, every implicit `uint256ToBigInt`
   allocates: `calcMemCost`, `wordsForBytes`, `gasCap`, SLOAD, SSTORE, TLOAD, TSTORE, BLOCKHASH, `getBalance` and
   `isJumpDestination`. Unconverted, memory and storage opcodes can regress.

   Converted carelessly, they truncate. The pinned cases are O01, O03-O05, O08 and C01: JUMP `isValidInt`, BLOBHASH
   bounds, `sliceBytes` min-before-truncate, CALL requested gas, RETURNDATACOPY saturation.
2. **The oracle is not total.** `/` by 0 throws; `<<`, `>>` and `sshift` beyond 255 truncate, throw or OOM;
   `apply` of more than 32 bytes throws. GT-2 generators must avoid or catch these inputs, and D1-D3 must be decided
   before code. Otherwise "zero differences" is undefined.
3. **Limb-level signed/unsigned and boundary bugs.**
   - unsigned compare across limbs (E35: a 2^255 endowment passing a balance check);
   - SDIV/SMOD truncation vs floor (E09, E12);
   - 512-bit MULMOD and ADDMOD carry (E14-E18);
   - CLZ and `byteSize` at 2^64/2^128/2^192 (E22, E31);
   - SAR sign-fill (E28);
   - `toInt`/`toLong` low-31/63-bit semantics (C01, C02).

**beacon sign-off (ETH/Sepolia, part A):** analysis complete. I sign the ETH side of GT-1 once D1-D3 are recorded, the
62-row table above is carried into GT-2 as named cases, and §3's truncation invariants are added to the GT-2 or opcode
suites. This analysis does not authorise any code; GT-2 and GT-3 gates apply.
