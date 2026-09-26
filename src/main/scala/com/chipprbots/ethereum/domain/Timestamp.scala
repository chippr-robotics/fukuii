package com.chipprbots.ethereum.domain

import com.chipprbots.ethereum.rlp.RLPCodec
import com.chipprbots.ethereum.rlp.RLPException
import com.chipprbots.ethereum.rlp.RLPCodec.Ops
import com.chipprbots.ethereum.rlp.RLPImplicits.bigIntEncDec

/** A block timestamp.
  *
  * The underlying representation is a JVM `Long`, but the VALUE is a **uint64** — yellow paper `Hs`, execution-apis
  * `Quantity`, go-ethereum `Header.Time uint64`, besu `BlockHeader.timestamp` (long, compared unsigned). Every
  * timestamp at or above 2^63 therefore has its sign bit set and reads as a negative `Long`.
  *
  * All ORDER RELATIONS below are consequently unsigned (`java.lang.Long.compareUnsigned`). Below 2^63 this is
  * bit-identical to the signed comparison it replaces — for two words with bit 63 clear the signed and unsigned
  * readings are literally the same integer, so no case analysis is needed. The relations diverge if and only if exactly
  * one operand has bit 63 set.
  *
  * `-` IS DELIBERATELY LEFT SIGNED. See the comment on that method.
  */
opaque type Timestamp = Long

object Timestamp:
  val Zero: Timestamp = 0L

  /** 2^64-1, the largest uint64 — NOT `Long.MaxValue` (2^63-1), which is only half way up the range. Stored as the bit
    * pattern `0xFFFFFFFFFFFFFFFF`.
    *
    * CAUTION before reaching for this as a "never expires" ceiling. The relational operators below are unsigned, so
    * `MaxValue > anything` is true as you would expect — but `-` is deliberately SIGNED (see its own comment), so
    * `MaxValue - anything` is NEGATIVE. The two do not agree, and code that compares and then subtracts will get a sign
    * flip. It also prints as `-1` in any log line, because `toLong` returns the raw bit pattern.
    *
    * There is no production consumer today. If one is ever added, read the `-` comment first.
    */
  val MaxValue: Timestamp = -1L

  /** 2^64, for the unsigned widening below. */
  private val TwoPow64: BigInt = BigInt(1) << 64

  def apply(v: Long): Timestamp = v
  def apply(v: Int): Timestamp = v.toLong

  extension (t: Timestamp)
    def toLong: Long = t
    def underlying: Long = t

    /** DELIBERATELY SIGNED — do not "finish the job" and make this unsigned.
      *
      * Two's-complement subtraction already yields the exact difference for any two uint64s whose true difference fits
      * in a signed `Long`, which is every parent/child pair on any real chain, anywhere in the uint64 range. Four
      * callers depend on the result being signed and possibly NEGATIVE:
      *   - `consensus/pow/difficulty/EthashDifficultyCalculator.scala:21` — `math.max(parentUncleFactor -
      *     timestampDiff/9, FrontierTimestampDiffLimit)`, where the limit is -99 and the sign selects the branch. ETC
      *     consensus.
      *   - `consensus/pow/difficulty/TargetTimeDifficultyCalculator.scala:25` — same shape.
      *   - `ledger/BranchResolution.scala:107` and `:125` — ECIP-1100 MESS artificial finality; the `math.max(0L, ...)`
      *     clamp is meaningless unless the difference can go negative (`commonAncestorTimestamp` falls back to `Zero`).
      *     ETC consensus.
      *   - `ledger/BlockMetrics.scala:46` — Prometheus gauge, non-consensus.
      *
      * Making this unsigned would turn a clamped 0 into ~1.8e19 and CHANGE ETC DIFFICULTY AND REORG POLICY. The price
      * is that the algebra is not internally coherent: `a > b` is unsigned while `a - b` is signed, so `a > b && (a -
      * b) < 0` is satisfiable when exactly one operand is above 2^63. That is a deliberate trade, not an oversight.
      */
    def -(other: Timestamp): Long = t - other

    // Two's-complement addition is the same bit operation for signed and unsigned operands,
    // and wrap-around at 2^64 is the correct uint64 behaviour. No change needed.
    def +(delta: Long): Timestamp = t + delta
    def +(delta: Int): Timestamp = t + delta.toLong

    def >(other: Timestamp): Boolean = java.lang.Long.compareUnsigned(t, other) > 0
    def >=(other: Timestamp): Boolean = java.lang.Long.compareUnsigned(t, other) >= 0
    def <(other: Timestamp): Boolean = java.lang.Long.compareUnsigned(t, other) < 0
    def <=(other: Timestamp): Boolean = java.lang.Long.compareUnsigned(t, other) <= 0
    def ==(other: Timestamp): Boolean = t == other
    def !=(other: Timestamp): Boolean = t != other
    def min(other: Timestamp): Timestamp = if java.lang.Long.compareUnsigned(t, other) <= 0 then t else other
    def max(other: Timestamp): Timestamp = if java.lang.Long.compareUnsigned(t, other) >= 0 then t else other

    /** Timestamp-fork activation test: is this block at or after `activation`?
      *
      * The single shape used by every `is*Timestamp` gate in `BlockchainConfig` and `BlockchainConfigForEvm`. Those
      * gates must keep wrapping this in `Option.exists` — ETC/Mordor/Gorgoroth safety rests on the predicate never
      * being EVALUATED, because every ETC-family config leaves all seven fork timestamps `None`. Replacing `.exists`
      * with `getOrElse(0L)` would activate every ETH fork on ETC.
      *
      * `activation` is a raw `Long` read from HOCON `getLong`; a negative configured activation is not expressible as a
      * sensible uint64 and under unsigned comparison would mean "never activate" rather than "activate immediately". No
      * shipped config does this.
      */
    def isAtOrAfter(activation: Long): Boolean = java.lang.Long.compareUnsigned(t, activation) >= 0

    // Must call the static java.lang.Long.toHexString, NOT `t.toHexString`:
    // `t: Timestamp` is opaque (no `.toHexString` member on the underlying Long),
    // so `t.toHexString` re-binds to THIS extension -> infinite recursion / runtime
    // hang on every caller (eth_subscribe newHeads, Engine API block JSON).
    // It is already unsigned: 2^64-1 renders as "ffffffffffffffff".
    def toHexString: String = java.lang.Long.toHexString(t)

    /** The uint64 VALUE as a `BigInt`.
      *
      * `BigInt(aLong)` sign-extends, so a 2^64-1 timestamp widens to -1 rather than 2^64-1. Use this instead at every
      * widening that must preserve the uint64 value: the header RLP encoder, the EIP-4788 ring buffer, the `TIMESTAMP`
      * opcode. Below 2^63 it is exactly `BigInt(t)`.
      *
      * Arithmetic rather than `BigInt(java.lang.Long.toUnsignedString(t))` because `TIMESTAMP` (0x42) is a hot EVM
      * opcode and the string form allocates on every execution.
      */
    def toUnsignedBigInt: BigInt =
      val v: Long = t
      if v < 0L then BigInt(v) + TwoPow64 else BigInt(v)

    /** The uint64 value as a [[UInt256]].
      *
      * NOT `UInt256(t)`: `UInt256.apply(n: Long)` goes through `BigInt(n)` and then `boundBigInt`, which maps a
      * sign-extended -1 to 2^256-1. That is the correct behaviour for `UInt256`'s other callers (they rely on the
      * modular wrap), so the fix belongs here at the timestamp call site, not underneath `UInt256`.
      */
    def toUInt256: UInt256 = UInt256(t.toUnsignedBigInt)

  /** Built on `bigIntEncDec` + [[toUnsignedBigInt]], NOT on `longEncDec`.
    *
    * `longEncDec.encode` is `bigIntCodec.encode(BigInt(obj))` and is documented in `RLPImplicits` as being for
    * "positive (or 0) longs". Fed a sign-extended -1 it emits `BigInt(-1).toByteArray` = the single byte `0xFF`, which
    * decodes back as 255 — the codec is not even an involution above 2^63. `BlockHeader.hash` is `kec256` of the header
    * encoding, so that truncation produces a WRONG BLOCK HASH.
    */
  given rlpCodec: RLPCodec[Timestamp] = bigIntEncDec.xmap(
    (v: BigInt) =>
      // Reinstates the bound `longEncDec` gave us for free. `longCodec.decode` rejects
      // `bytes.length > 8`; `bigIntEncDec` accepts any length, and a bare `v.toLong` would
      // silently truncate a 9-byte field to its low 64 bits. This given has no production
      // consumer today, but BlockHeaderDec's hand-written `longFromEncodeable(items(11))` is
      // one natural cleanup away from becoming one — at which point an over-long timestamp
      // would decode to a value whose re-encoding differs from its wire form, i.e. a header
      // that hashes differently than it arrived. Forge flagged this as a chain-split-class
      // serialization footgun during the ETC sign-off; the guard closes it permanently.
      if v.bitLength > 64 then throw RLPException(s"timestamp exceeds uint64: $v")
      else Timestamp(v.toLong),
    _.toUnsignedBigInt
  )

  /** Must stay in lockstep with the relational operators above, or `maxBy`/`sorted` and `>` disagree — the textbook
    * non-transitive-comparator bug.
    *
    * `Ordering.by(_.toLong)` cannot express this: inside the opaque scope `Timestamp =:= Long`, so the `Ordering[Long]`
    * it needs resolves to THIS given and the result is infinite self-recursion. The Scala 3 compiler flags it
    * ("Infinite loop in function body") and any consumer — `BlockGeneratorSkeleton.getPendingBlock` is the only one in
    * main sources — hangs at runtime.
    */
  given Ordering[Timestamp] =
    Ordering.fromLessThan((a: Timestamp, b: Timestamp) => java.lang.Long.compareUnsigned(a, b) < 0)
