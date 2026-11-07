package com.chipprbots.ethereum.domain

import com.chipprbots.ethereum.rlp.RLPCodec
import com.chipprbots.ethereum.rlp.RLPCodec.xmap
import com.chipprbots.ethereum.rlp.RLPImplicits.bigIntEncDec

opaque type Difficulty = BigInt

object Difficulty:
  val Zero: Difficulty = BigInt(0)
  val MinimumDifficulty: Difficulty = BigInt(131072) // EIP-2 / Ethash minimum

  def apply(v: BigInt): Difficulty = v

  extension (d: Difficulty)
    def value: BigInt = d
    def +(other: Difficulty): Difficulty = d + other
    def -(other: Difficulty): Difficulty = d - other
    def *(n: Long): Difficulty = d * n
    def /(n: Long): Difficulty = d / n
    def max(other: Difficulty): Difficulty = if d >= other then d else other
    def compare(other: Difficulty): Int = d.compare(other)
    def <(other: Difficulty): Boolean = d < other
    def <=(other: Difficulty): Boolean = d <= other
    def >(other: Difficulty): Boolean = d > other
    def toLong: Long = d.toLong
    def toBigInt: BigInt = d

  // Note: BlockHeader RLP uses bigIntToUnsignedByteArray directly (0x80 for zero), not this codec.
  // This codec follows the same BigInt encoding convention and is correct for non-header use sites.
  given rlpCodec: RLPCodec[Difficulty] = bigIntEncDec.xmap(Difficulty.apply, _.value)
  // Pin the BigInt ordering explicitly. Inside this object `Difficulty =:= BigInt`,
  // so the implicit `Ordering` parameter of the bare `Ordering.by(_.value)` resolved to
  // the given being defined → self-referential lazy-val init deadlock. Passing
  // `scala.math.Ordering.BigInt` directly removes the implicit search while keeping the
  // identical numeric total order.
  given Ordering[Difficulty] = Ordering.by[Difficulty, BigInt](_.value)(using scala.math.Ordering.BigInt)
