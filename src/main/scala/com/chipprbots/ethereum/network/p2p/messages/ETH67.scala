package com.chipprbots.ethereum.network.p2p.messages

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex

import com.chipprbots.ethereum.network.p2p.Message
import com.chipprbots.ethereum.network.p2p.MessageSerializableImplicit
import com.chipprbots.ethereum.rlp.RLPImplicitConversions._
import com.chipprbots.ethereum.rlp.RLPImplicits._
import com.chipprbots.ethereum.rlp.RLPImplicits.given
import com.chipprbots.ethereum.rlp._

/** ETH67 protocol messages - enhances transaction announcement with types and sizes See
  * https://github.com/ethereum/devp2p/blob/master/caps/eth.md#eth67
  *
  * The main change in ETH67 is that NewPooledTransactionHashes now includes:
  *   - transaction types (legacy, EIP-2930, EIP-1559, etc.)
  *   - transaction sizes (to help with bandwidth management)
  *
  * Note: For backward compatibility with some core-geth nodes, the decoder supports both:
  *   1. Enhanced format: [[types...], [sizes...], [hashes...]]
  *   2. Legacy ETH65 format: [hash1, hash2, ...] (sets default type=0, size=0)
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
      def toNewPooledTransactionHashes: NewPooledTransactionHashes = {
        val decoded = rawDecode(bytes)
        
        // Enhanced debugging to understand the RLP structure from core-geth
        decoded match {
          case rlpList: RLPList =>
            val itemTypes = rlpList.items.map {
              case _: RLPValue => "RLPValue"
              case _: RLPList => "RLPList"
              case other => other.getClass.getSimpleName
            }.mkString("[", ", ", "]")
            
            println(s"DEBUG: NewPooledTransactionHashes RLP structure: " +
              s"${rlpList.items.length} items, types: $itemTypes")
            
            rlpList.items match {
              // ETH67/ETH68 enhanced format: [[types...], [sizes...], [hashes...]]
              case Seq(typesList: RLPList, sizesList: RLPList, hashesList: RLPList) =>
                println(s"DEBUG: ETH67/68 format - types: ${typesList.items.length}, " +
                  s"sizes: ${sizesList.items.length}, hashes: ${hashesList.items.length}")
                
                // Debug sizes list structure
                val sizesItemTypes = sizesList.items.take(3).map {
                  case RLPValue(bytes) => s"RLPValue(${bytes.length} bytes, hex=${Hex.toHexString(bytes)})"
                  case item: RLPList => s"RLPList(${item.items.length} items)"
                  case other => other.toString
                }.mkString("[", ", ", "]")
                println(s"DEBUG: First 3 sizes items: $sizesItemTypes")
                
                NewPooledTransactionHashes(
                  fromRlpList[Byte](typesList),
                  fromRlpList[BigInt](sizesList),
                  fromRlpList[ByteString](hashesList)
                )
              // ETH65 legacy format: [hash1, hash2, ...]
              case _ =>
                println(s"DEBUG: Using ETH65 legacy format fallback")
                val hashes = fromRlpList[ByteString](rlpList)
                NewPooledTransactionHashes(
                  types = Seq.fill(hashes.size)(0.toByte),
                  sizes = Seq.fill(hashes.size)(BigInt(0)),
                  hashes = hashes
                )
            }
          case other =>
            throw new RuntimeException(
              s"Cannot decode NewPooledTransactionHashes - expected RLPList, got ${other.getClass.getSimpleName}"
            )
        }
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
  case class NewPooledTransactionHashes(types: Seq[Byte], sizes: Seq[BigInt], hashes: Seq[ByteString]) extends Message {
    require(types.size == sizes.size && sizes.size == hashes.size, "types, sizes, and hashes must have same length")

    override def code: Int = Codes.NewPooledTransactionHashesCode

    override def toString: String = {
      val txInfo = types.lazyZip(sizes).lazyZip(hashes).map { (typ, size, hash) =>
        s"(type=$typ, size=$size, hash=${Hex.toHexString(hash.toArray[Byte])})"
      }
      s"NewPooledTransactionHashes { txs: ${txInfo.mkString(", ")} }"
    }

    override def toShortString: String =
      s"NewPooledTransactionHashes { count: ${hashes.size} }"
  }
}
