package com.chipprbots.ethereum.domain

import com.chipprbots.ethereum.rlp.RLPCodec
import com.chipprbots.ethereum.rlp.RLPCodec.xmap
import com.chipprbots.ethereum.rlp.RLPImplicits.bigIntEncDec

opaque type StorageKey = BigInt
object StorageKey:
  def apply(v: BigInt): StorageKey = v
  extension (sk: StorageKey) def value: BigInt = sk
  given rlpCodec: RLPCodec[StorageKey] = bigIntEncDec.xmap(StorageKey.apply, _.value)
