package com.chipprbots.ethereum.domain

import com.chipprbots.ethereum.rlp.RLPCodec
import com.chipprbots.ethereum.rlp.RLPCodec.xmap
import com.chipprbots.ethereum.rlp.RLPImplicits.bigIntEncDec

opaque type BlockNumber = BigInt

object BlockNumber:
  val Zero: BlockNumber = BigInt(0)
  val Genesis: BlockNumber = BigInt(0)

  def apply(v: BigInt): BlockNumber = v
  def apply(v: Long): BlockNumber = BigInt(v)
  def apply(v: Int): BlockNumber = BigInt(v)

  extension (n: BlockNumber)
    def value: BigInt = n
    def toLong: Long = n.toLong
    def toInt: Int = n.toInt
    def +(other: BlockNumber): BlockNumber = n + other
    def +(delta: Long): BlockNumber = n + delta
    def -(other: BlockNumber): BlockNumber = n - other
    def -(delta: Long): BlockNumber = n - delta
    def *(k: Long): BlockNumber = n * k
    def /(k: Long): BlockNumber = n / k
    def compare(other: BlockNumber): Int = n.compare(other)
    def >(other: BlockNumber): Boolean = n > other
    def >=(other: BlockNumber): Boolean = n >= other
    def <(other: BlockNumber): Boolean = n < other
    def <=(other: BlockNumber): Boolean = n <= other
    def min(other: BlockNumber): BlockNumber = if n < other then n else other
    def max(other: BlockNumber): BlockNumber = if n > other then n else other
    def abs: BlockNumber = n.abs

  given rlpCodec: RLPCodec[BlockNumber] = bigIntEncDec.xmap((v: BigInt) => BlockNumber(v), _.value)
  // Pin the BigInt ordering explicitly. Inside this object BlockNumber =:= BigInt,
  // so the implicit Ordering parameter of bare Ordering.by(_.value) would resolve to
  // the given being defined → self-referential lazy-val init deadlock. Passing
  // scala.math.Ordering.BigInt directly removes the implicit search (same numeric order).
  given Ordering[BlockNumber] = Ordering.by[BlockNumber, BigInt](_.value)(using scala.math.Ordering.BigInt)
