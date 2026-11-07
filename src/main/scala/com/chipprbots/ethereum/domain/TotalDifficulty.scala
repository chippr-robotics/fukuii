package com.chipprbots.ethereum.domain

import com.chipprbots.ethereum.rlp.RLPCodec
import com.chipprbots.ethereum.rlp.RLPCodec.xmap
import com.chipprbots.ethereum.rlp.RLPImplicits.bigIntEncDec

opaque type TotalDifficulty = BigInt

object TotalDifficulty:
  val Zero: TotalDifficulty = BigInt(0)

  def apply(v: BigInt): TotalDifficulty = v

  extension (td: TotalDifficulty)
    def value: BigInt = td
    def +(d: Difficulty): TotalDifficulty = td + d.value
    def compare(other: TotalDifficulty): Int = td.compare(other)
    def >(other: TotalDifficulty): Boolean = td > other
    def >=(other: TotalDifficulty): Boolean = td >= other
    def <(other: TotalDifficulty): Boolean = td < other

  given rlpCodec: RLPCodec[TotalDifficulty] = bigIntEncDec.xmap(TotalDifficulty.apply, _.value)
  // Same opaque-type-in-scope footgun as Difficulty: `TotalDifficulty =:= BigInt` here, so
  // bare `Ordering.by(_.value)` resolves its implicit `Ordering` to the given being defined →
  // self-referential init deadlock the moment it is summoned. Pin `scala.math.Ordering.BigInt`.
  given Ordering[TotalDifficulty] = Ordering.by[TotalDifficulty, BigInt](_.value)(using scala.math.Ordering.BigInt)
