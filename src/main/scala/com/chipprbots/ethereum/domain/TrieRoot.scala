package com.chipprbots.ethereum.domain

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.rlp.RLPCodec
import com.chipprbots.ethereum.rlp.RLPCodec.xmap
import com.chipprbots.ethereum.rlp.RLPImplicits.byteStringEncDec

opaque type TrieRoot = ByteString
object TrieRoot:
  /** 32 zero bytes. Note: this is NOT the empty-trie root (kec256(RLP(""))); for that use [[BlockHeader.EmptyMpt]].
    * This mirrors [[BlockHash.Zero]] as a neutral default.
    */
  val Empty: TrieRoot = ByteString(new Array[Byte](32))
  def apply(bs: ByteString): TrieRoot = bs
  extension (tr: TrieRoot)
    def value: ByteString = tr
    def toArray: Array[Byte] = tr.toArray[Byte]
    def toHexString: String = tr.map("%02x".format(_)).mkString("0x", "", "")
  given rlpCodec: RLPCodec[TrieRoot] =
    byteStringEncDec.xmap(TrieRoot.apply, _.value)
