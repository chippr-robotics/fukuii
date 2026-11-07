package com.chipprbots.ethereum.domain

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.rlp.RLPCodec
import com.chipprbots.ethereum.rlp.RLPCodec.xmap
import com.chipprbots.ethereum.rlp.RLPImplicits.byteStringEncDec

opaque type BlockHash = ByteString
object BlockHash:
  val Zero: BlockHash = ByteString(new Array[Byte](32))
  def apply(bs: ByteString): BlockHash = bs
  extension (bh: BlockHash)
    def value: ByteString = bh
    def toArray: Array[Byte] = bh.toArray[Byte]
    def toHexString: String = bh.map("%02x".format(_)).mkString("0x", "", "")
  given rlpCodec: RLPCodec[BlockHash] =
    byteStringEncDec.xmap(BlockHash.apply, _.value)
