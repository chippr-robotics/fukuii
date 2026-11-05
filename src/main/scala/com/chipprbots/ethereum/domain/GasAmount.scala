package com.chipprbots.ethereum.domain

import com.chipprbots.ethereum.rlp.RLPCodec
import com.chipprbots.ethereum.rlp.RLPCodec.xmap
import com.chipprbots.ethereum.rlp.RLPImplicits.bigIntEncDec

opaque type GasAmount = BigInt

object GasAmount:
  val Zero: GasAmount = BigInt(0)
  def apply(v: BigInt): GasAmount = v
  def apply(v: Long): GasAmount = BigInt(v)
  def apply(v: Int): GasAmount = BigInt(v)

  extension (g: GasAmount)
    def value: BigInt = g
    def toLong: Long = g.toLong
    def +(other: GasAmount): GasAmount = g + other
    def -(other: GasAmount): GasAmount = g - other
    def *(n: Long): GasAmount = g * n
    def *(n: BigInt): GasAmount = g * n
    def /(n: Long): GasAmount = g / n
    def /(n: Int): GasAmount = g / n
    def /(other: GasAmount): BigInt = g / other
    def compare(other: GasAmount): Int = g.compare(other)
    def >(other: GasAmount): Boolean = g > other
    def >=(other: GasAmount): Boolean = g >= other
    def <(other: GasAmount): Boolean = g < other
    def <=(other: GasAmount): Boolean = g <= other
    def abs: GasAmount = g.abs
    def min(other: GasAmount): GasAmount = if g < other then g else other
    def max(other: GasAmount): GasAmount = if g > other then g else other

  given rlpCodec: RLPCodec[GasAmount] = bigIntEncDec.xmap((v: BigInt) => GasAmount(v), _.value)
  // Pin the BigInt ordering explicitly. Inside this object GasAmount =:= BigInt,
  // so the implicit Ordering parameter of bare Ordering.by(_.value) would resolve to
  // the given being defined → self-referential lazy-val init deadlock. Passing
  // scala.math.Ordering.BigInt directly removes the implicit search (same numeric order).
  given Ordering[GasAmount] = Ordering.by[GasAmount, BigInt](_.value)(using scala.math.Ordering.BigInt)
