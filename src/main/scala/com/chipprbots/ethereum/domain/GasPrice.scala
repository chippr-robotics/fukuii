package com.chipprbots.ethereum.domain

import com.chipprbots.ethereum.rlp.RLPCodec
import com.chipprbots.ethereum.rlp.RLPCodec.xmap
import com.chipprbots.ethereum.rlp.RLPImplicits.bigIntEncDec

opaque type GasPrice = BigInt

object GasPrice:
  val Zero: GasPrice = BigInt(0)
  def apply(v: BigInt): GasPrice = v
  def apply(v: Long): GasPrice = BigInt(v)
  def apply(v: Int): GasPrice = BigInt(v)

  extension (p: GasPrice)
    def value: BigInt = p
    def toLong: Long = p.toLong
    def +(other: GasPrice): GasPrice = p + other
    def *(n: Long): GasPrice = p * n
    def *(n: BigInt): GasPrice = p * n
    def /(n: Long): GasPrice = p / n
    def compare(other: GasPrice): Int = p.compare(other)
    def >(other: GasPrice): Boolean = p > other
    def >=(other: GasPrice): Boolean = p >= other
    def <(other: GasPrice): Boolean = p < other
    def <=(other: GasPrice): Boolean = p <= other
    def min(other: GasPrice): GasPrice = if p < other then p else other
    def max(other: GasPrice): GasPrice = if p > other then p else other

  given rlpCodec: RLPCodec[GasPrice] = bigIntEncDec.xmap((v: BigInt) => GasPrice(v), _.value)
  given Ordering[GasPrice] = Ordering.by[GasPrice, BigInt](_.value)(using scala.math.Ordering.BigInt)
