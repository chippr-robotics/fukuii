package com.chipprbots.ethereum.network.p2p.messages

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex

import com.chipprbots.ethereum.network.p2p.Message
import com.chipprbots.ethereum.network.p2p.MessageSerializableImplicit
import com.chipprbots.ethereum.rlp.RLPImplicitConversions._
import com.chipprbots.ethereum.rlp.RLPImplicits._
import com.chipprbots.ethereum.rlp._

/** ETH67 protocol messages - enhances transaction announcement with types and sizes
  * See https://github.com/ethereum/devp2p/blob/master/caps/eth.md#eth67
  *
  * The main change in ETH67 is that NewPooledTransactionHashes now includes:
  * - transaction types (legacy, EIP-2930, EIP-1559, etc.)
  * - transaction sizes (to help with bandwidth management)
  */
object ETH67 {

  object NewPooledTransactionHashes {
    implicit class NewPooledTransactionHashesEnc(val underlyingMsg: NewPooledTransactionHashes)
        extends MessageSerializableImplicit[NewPooledTransactionHashes](underlyingMsg)
        with RLPSerializable {
      override def code: Int = Codes.NewPooledTransactionHashesCode

      override def toRLPEncodable: RLPEncodeable = {
        import msg._
        RLPList(toRlpList(types), toRlpList(sizes), toRlpList(hashes))
      }
    }

    implicit class NewPooledTransactionHashesDec(val bytes: Array[Byte]) extends AnyVal {
      def toNewPooledTransactionHashes: NewPooledTransactionHashes = rawDecode(bytes) match {
        case RLPList(typesList: RLPList, sizesList: RLPList, hashesList: RLPList) =>
          NewPooledTransactionHashes(
            fromRlpList[Byte](typesList),
            fromRlpList[BigInt](sizesList),
            fromRlpList[ByteString](hashesList)
          )
        case _ => throw new RuntimeException("Cannot decode NewPooledTransactionHashes")
      }
    }
  }

  /** New pooled transaction hashes announcement with types and sizes
    *
    * @param types
    *   Transaction types (0=legacy, 1=EIP-2930, 2=EIP-1559, etc.)
    * @param sizes
    *   Transaction sizes in bytes
    * @param hashes
    *   Transaction hashes
    */
  case class NewPooledTransactionHashes(types: Seq[Byte], sizes: Seq[BigInt], hashes: Seq[ByteString])
      extends Message {
    require(types.size == sizes.size && sizes.size == hashes.size, "types, sizes, and hashes must have same length")

    override def code: Int = Codes.NewPooledTransactionHashesCode

    override def toString: String = {
      val txInfo = (types, sizes, hashes).zipped.map { (typ, size, hash) =>
        s"(type=$typ, size=$size, hash=${Hex.toHexString(hash.toArray[Byte])})"
      }
      s"NewPooledTransactionHashes { txs: ${txInfo.mkString(", ")} }"
    }

    override def toShortString: String =
      s"NewPooledTransactionHashes { count: ${hashes.size} }"
  }
}
