# Contract: the `UInt256` public API (spec 021)

*Spec 021, Phase 1, 2026-11-07. Scope: `com.chipprbots.ethereum.domain.UInt256` (class and companion) after slices S1
and S2. The semantics column is the GT-2 oracle's rule, row-pinned. Limb rules are in
[data-model.md §1](../data-model.md#1-uint256-domainuint256scala), and the reasons in [research.md](../research.md).*

## 1. Stability promise

- **Kept members** keep their name, signature and result on every input. The oracle is the `BigInt` implementation
  (GT-2, zero differences). Exception: the shift members above 255 (D2).
- **Wire and storage bytes do not change**: RLP, JSON, `bytes`, hex strings, `toString`, and every storage key and
  value derived from a `UInt256`. S0's goldens pin them before the swap, and they stay unchanged after it (forge S2).
- **Callers compile unchanged** in `domain/`, `ledger/`, `jsonrpc/`, `network/`, `consensus/` and `blockchain/`
  (NG-2, SC-006). The only caller edits are:
  - the one `/` caller (S1);
  - SAR's `toSign` (S2);
  - the T-005 hot-path commits (S2), each its own commit with a measurement.
- **No flag.** There is no configuration switch between representations. The `BigInt` class survives only in the test
  tree, as the oracle.

## 2. Members

Legend:
- **K** = kept.
- **A** = added: allocation-free, removes a `BigInt` round trip (NG-2).
- **R** = removed.
- **Throws** = may throw on some input.

| Member | Status | Slice | Semantics (= the oracle unless stated) | Throws | Rows |
|---|---|---|---|---|---|
| `object UInt256.Size: Int` | K | — | `32` | no | — |
| `MaxValue`, `Zero`, `One`, `Two` | K | — | shared instances | no | — |
| `apply(bytes: ByteString)`, `apply(array: Array[Byte])` | K | S1 | big-endian, right-aligned; empty → `Zero` | **yes**: `IllegalArgumentException` when the length is > 32, even with leading zeros (D3) | C04, P04 |
| `apply(n: BigInt)` | K | S1 | n mod 2²⁵⁶ for every integer | no | C05, P03 |
| `apply(b: Boolean)` | K | — | `One` / `Zero` | no | — |
| `apply(n: Long)` | K | S1 | **sign-extending**: −1L → `MaxValue` | no | C06, P01 |
| `extension (BigInt) toUInt256` | K | — | `apply(BigInt)` | no | — |
| `implicit uint256ToBigInt`, `byte2UInt256`, `int2UInt256`, `long2UInt256`, `bool2UInt256` | K | — | as today; the `Byte`/`Int`/`Long` ones sign-extend | no | P02 |
| `bytes: ByteString` | K (no longer memoised) | S1 | exactly 32 bytes, big-endian | no | C07, P06 |
| `byteSize: Int` | K | S1 | 0 for zero, else `(bitLength + 7) / 8` | no | E22, C10, L02 |
| `getByte(i)` | K | S1 | byte i, MSB first; `Zero` for i ≥ 32, any size | no | E25 |
| `&`, `\|`, `^`, `unary_~`, `unary_-`, `+`, `-`, `*` | K | S1 | mod 2²⁵⁶ | no | E01–E03, E34, L03, L04 |
| `/` | **R** | S1 | retired (D1); its caller moves to `div` | — | E06 (removed) |
| `**` | K | S1 | `modPow(e, 2²⁵⁶)`; 0⁰ = 1 | no | E19–E21 |
| `compare`, `min`, `max`, `<`, `>`, `<=`, `>=`, `compareTo` | K | S1 | **unsigned**; on a tie `min`/`max` return `that` | no | E32, P12, C12, L05 |
| `isZero` | K | S1 | — | no | — |
| `<<(s)`, `>>(s)` | K | S1 | **EVM semantics for every s** (D2): s ≥ 256 → `Zero`. Equal to the oracle on 0–255 | no | E26, E27, L01 |
| `div`, `sdiv`, `mod`, `smod` | K | S1 | EVM DIV, SDIV, MOD, SMOD; divisor 0 → `Zero`; SDIV truncates toward zero; the SMOD sign follows the dividend | no | E04–E13, L07–L09 |
| `addmod(b, N)`, `mulmod(b, N)` | K | S1 | no wrap before the modulus; N = 0 → `Zero` | no | E14–E18 |
| `slt`, `sgt` | K | S1 | two's-complement compare | no | E33, L06 |
| `sshift(s)` | K | S1 | **EVM SAR for every s** (D2): s ≥ 256 → sign fill. Equal to the oracle on 0–255 | no | E28, L01 |
| `signExtend(b)` | K | S1 | EVM SIGNEXTEND; b ≥ 31 → identity | no | E23, E24 |
| `fillingAdd` | K | S1 | saturating at `MaxValue` | no | C11 |
| `equals` | K | S1 | limbwise for a `UInt256`; **cooperative branch kept** (`other == toBigInt`), asymmetric as today (S6) | no | P11 |
| `hashCode` | K | S1 | **equal to `BigInt.hashCode`** of the value (S6) | no | P11 |
| `toString` | K | — | `toSignedDecString` | no | C09, P09 |
| `toDecString`, `toSignedDecString` | K | — | unsigned / signed decimal | no | C09, P09 |
| `toHexString` | K | — | `"0x"` + lowercase hex padded to an even length | no | C08, P08 |
| `toSign: BigInt` | **R** | S2 | retired: its only caller (SAR) uses `isNegative` | — | E28 |
| `toBigInt` | K (now allocates) | S1 | the non-negative `BigInt` | no | P05 |
| `toInt` | K | — | **the low 31 bits** (not saturating) | no | C01, P10 |
| `toLong` | K | — | **the low 63 bits** | no | C02, P10 |
| `toByte: Byte` | **A** | S1 | equals `BigInt.toByte`: the low 8 bits, signed (FR-009, D5) | no | C03 |
| `isNegative: Boolean` | **A** | S2 | bit 255 set | no | E28 |
| `isValidInt: Boolean` | **A** | S2 | value ≤ `Int.MaxValue` (JUMP, BLOBHASH bounds) | no | O04, O05 |
| `numberOfLeadingZeros: Int` | **A** | S2 | 256 − bit length, 0..256 (CLZ, `byteSize`) | no | E31, L02 |
| `toHexQuantity: String` | **A** | S2 | `"0x"` + minimal lowercase hex (`"0x0"` for zero) = `"0x" + toBigInt.toString(16)` | no | S0 trace goldens |
| `given Ordering[UInt256]` | A, conditional | S1 | unsigned; added only if it resolves without ambiguity under `-source:3.9` (research R-7) | no | P12 |
| `derives CanEqual` | A | S1 | inert without `strictEquality` (research R-7) | — | — |

**Throw surface.** After S1 the only throwing members are `apply(ByteString)` and `apply(Array[Byte])` on more than
32 bytes. No arithmetic, comparison, shift, byte, sign or conversion member throws (G-4, FR-009).

## 3. Package-private surface (for `vm/` only)

| Item | Visibility | Use |
|---|---|---|
| `w0`, `w1`, `w2`, `w3` | `private[ethereum]` | the word stack and the kernels |
| `UInt256Kernels` (inline kernels and array kernels) | `private[ethereum]` | `UInt256` members; the word stack's in-place ops (S3) |
| the limb constructor | `private[domain]`, plus a `private[ethereum]` factory `fromLimbs(w0, w1, w2, w3)` | the word stack's `peek`/`pop` |

## 4. The DC-3 / FR-004 check

`scripts/spec021/check_uint256_bigint.sh` (research R-15) fails on `BigInt`, `BigInteger` or `toBigInt` in
`domain/UInt256.scala` or `domain/UInt256Kernels.scala` **outside** the fenced allow-list regions:
- `toBigInt`;
- `apply(BigInt)`;
- `toUInt256`;
- `uint256ToBigInt`;
- the string renderers;
- `equals`' cooperative branch.

It also fails on any of them **inside** the fenced arithmetic regions of `vm/OpCode.scala` and `vm/Stack.scala`, from
S2's end onward. Gas, storage and the tracers are out of its scope (DC-3).
