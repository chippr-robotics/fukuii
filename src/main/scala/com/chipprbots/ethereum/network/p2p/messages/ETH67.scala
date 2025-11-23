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
        // Core-geth encodes Types as a single byte string (RLPValue), not a list
        // to match Go's RLP encoding of []byte
        RLPList(RLPValue(types.toArray), toRlpList(sizes), toRlpList(hashes))
      }
    }

    implicit class NewPooledTransactionHashesDec(val bytes: Array[Byte]) extends AnyVal {
      def toNewPooledTransactionHashes: NewPooledTransactionHashes = {
        try {
          val decoded = rawDecode(bytes)
          
          // Capture RLP structure for error reporting
          val structureInfo = decoded match {
            case RLPList(items*) =>
              val itemTypes = items.map {
                case RLPValue(b) => s"RLPValue(${b.length}b)"
                case RLPList(subitems*) => s"RLPList(${subitems.size})"
                case other => s"${other.getClass.getSimpleName}"
              }.mkString(", ")
              s"RLPList[${items.size}]: [$itemTypes]"
            case RLPValue(b) => s"RLPValue(${b.length} bytes)"
            case other => s"${other.getClass.getSimpleName}"
          }
          
          decoded match {
            // ETH67/ETH68 enhanced format from core-geth: [types_as_byte_string, [sizes...], [hashes...]]
            // Note: core-geth encodes Types []byte as RLPValue (byte string), not RLPList
            // This matches Go's RLP encoding where []byte is encoded as a single byte string
            case RLPList(RLPValue(typesBytes), sizesList: RLPList, hashesList: RLPList) =>
              try {
                val types = typesBytes.toSeq
                val sizes = fromRlpList[BigInt](sizesList)
                val hashes = fromRlpList[ByteString](hashesList)
                
                NewPooledTransactionHashes(
                  types = types,
                  sizes = sizes,
                  hashes = hashes
                )
              } catch {
                case e: ArrayIndexOutOfBoundsException =>
                  throw new RuntimeException(
                    s"ETH67_DECODE_ERROR: ArrayIndexOutOfBoundsException in ETH67/68 format. " +
                    s"Structure: $structureInfo. " +
                    s"Types=${typesBytes.length}, Sizes=${sizesList.items.size}, Hashes=${hashesList.items.size}. " +
                    s"Hex: ${Hex.toHexString(bytes.take(100))}...",
                    e
                  )
                case e: Throwable =>
                  throw new RuntimeException(
                    s"ETH67_DECODE_ERROR: ${e.getClass.getSimpleName} in ETH67/68 format: ${e.getMessage}. " +
                    s"Structure: $structureInfo. Hex: ${Hex.toHexString(bytes.take(100))}...",
                    e
                  )
              }
            
            // ETH65 legacy format for backward compatibility: [hash1, hash2, ...]
            // Some older nodes may still send this format
            case rlpList: RLPList =>
              try {
                val hashes = fromRlpList[ByteString](rlpList)
                // For legacy format, assume all transactions are type 0 (legacy) with size 0 (unknown)
                NewPooledTransactionHashes(
                  types = Seq.fill(hashes.size)(0.toByte),
                  sizes = Seq.fill(hashes.size)(BigInt(0)),
                  hashes = hashes
                )
              } catch {
                case e: Throwable =>
                  throw new RuntimeException(
                    s"ETH67_DECODE_ERROR: ${e.getClass.getSimpleName} in ETH65 legacy format: ${e.getMessage}. " +
                    s"Structure: $structureInfo. Hex: ${Hex.toHexString(bytes.take(100))}...",
                    e
                  )
              }
            
            case other =>
              throw new RuntimeException(
                s"ETH67_DECODE_ERROR: Unexpected RLP structure. Expected [RLPValue, RLPList, RLPList] (ETH67/68) " +
                s"or RLPList (ETH65 legacy), got: $structureInfo. Hex: ${Hex.toHexString(bytes.take(100))}..."
              )
          }
        } catch {
          case e: RuntimeException if e.getMessage.contains("ETH67_DECODE_ERROR") =>
            // Re-throw our own detailed errors
            throw e
          case e: Throwable =>
            throw new RuntimeException(
              s"ETH67_DECODE_ERROR: ${e.getClass.getSimpleName} during RLP decode: ${e.getMessage}. " +
              s"Length: ${bytes.length}, Hex: ${Hex.toHexString(bytes.take(100))}...",
              e
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
