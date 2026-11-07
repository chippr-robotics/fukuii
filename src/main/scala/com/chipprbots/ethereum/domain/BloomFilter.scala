package com.chipprbots.ethereum.domain

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.rlp.RLPCodec
import com.chipprbots.ethereum.rlp.RLPCodec.xmap
import com.chipprbots.ethereum.rlp.RLPImplicits.byteStringEncDec

opaque type BloomFilter = ByteString
object BloomFilter:
  val Empty: BloomFilter = ByteString(new Array[Byte](256))
  def apply(bs: ByteString): BloomFilter = bs
  def fromArray(arr: Array[Byte]): BloomFilter = ByteString(arr)
  extension (bf: BloomFilter)
    def value: ByteString = bf
    def toArray: Array[Byte] = bf.toArray[Byte]
    def toHexString: String = bf.map("%02x".format(_)).mkString("0x", "", "")
  given rlpCodec: RLPCodec[BloomFilter] =
    byteStringEncDec.xmap(BloomFilter.apply, _.value)
