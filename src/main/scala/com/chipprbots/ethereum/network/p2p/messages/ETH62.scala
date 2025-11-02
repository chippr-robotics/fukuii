package com.chipprbots.ethereum.network.p2p.messages

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex

import com.chipprbots.ethereum.domain.BlockBody
import com.chipprbots.ethereum.domain.BlockBody._
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockHeaderImplicits._
import com.chipprbots.ethereum.network.p2p.Message
import com.chipprbots.ethereum.network.p2p.MessageSerializableImplicit
import com.chipprbots.ethereum.rlp.RLPImplicitConversions._
import com.chipprbots.ethereum.rlp.RLPImplicits._
import com.chipprbots.ethereum.rlp.RLPImplicits.given
import com.chipprbots.ethereum.rlp.RLPList
import com.chipprbots.ethereum.rlp._

object ETH62 {
  object BlockHash {

    implicit class BlockHashEnc(blockHash: BlockHash) extends RLPSerializable {
      override def toRLPEncodable: RLPEncodeable = RLPList(RLPValue(blockHash.hash.toArray[Byte]), blockHash.number)
    }

    implicit class BlockHashDec(val bytes: Array[Byte]) extends AnyVal {
      def toBlockHash: BlockHash = BlockHashRLPEncodableDec(bytes).toBlockHash
    }

    implicit class BlockHashRLPEncodableDec(val rlpEncodeable: RLPEncodeable) extends AnyVal {
      def toBlockHash: BlockHash = rlpEncodeable match {
        case RLPList(RLPValue(hashBytes), RLPValue(numberBytes)) =>
          BlockHash(ByteString(hashBytes), BigInt(1, numberBytes))
        case _ => throw new RuntimeException("Cannot decode BlockHash")
      }
    }
  }

  case class BlockHash(hash: ByteString, number: BigInt) {
    override def toString: String =
      s"BlockHash { " +
        s"hash: ${Hex.toHexString(hash.toArray[Byte])} " +
        s"number: $number " +
        s"}"
  }

  object NewBlockHashes {
    implicit class NewBlockHashesEnc(val underlyingMsg: NewBlockHashes)
        extends MessageSerializableImplicit[NewBlockHashes](underlyingMsg)
        with RLPSerializable {

      import BlockHash._

      override def code: Int = Codes.NewBlockHashesCode

      override def toRLPEncodable: RLPEncodeable = RLPList(msg.hashes.map(_.toRLPEncodable): _*)
    }

    implicit class NewBlockHashesDec(val bytes: Array[Byte]) extends AnyVal {
      import BlockHash._
      def toNewBlockHashes: NewBlockHashes = rawDecode(bytes) match {
        case rlpList: RLPList => NewBlockHashes(rlpList.items.map(_.toBlockHash))
        case _                => throw new RuntimeException("Cannot decode NewBlockHashes")
      }
    }
  }

  case class NewBlockHashes(hashes: Seq[BlockHash]) extends Message {
    override def code: Int = Codes.NewBlockHashesCode
    override def toString: String =
      s"NewBlockHashes { " +
        s"hashes: ${hashes} " +
        s"}"
    override def toShortString: String = toString
  }

  object GetBlockHeaders {
    implicit class GetBlockHeadersEnc(val underlyingMsg: GetBlockHeaders)
        extends MessageSerializableImplicit[GetBlockHeaders](underlyingMsg)
        with RLPSerializable {

      override def code: Int = Codes.GetBlockHeadersCode

      override def toRLPEncodable: RLPEncodeable = {
        import msg._
        block match {
          case Left(blockNumber) => RLPList(blockNumber, maxHeaders, skip, if (reverse) 1 else 0)
          case Right(blockHash)  => RLPList(RLPValue(blockHash.toArray[Byte]), maxHeaders, skip, if (reverse) 1 else 0)
        }
      }
    }

    implicit class GetBlockHeadersDec(val bytes: Array[Byte]) extends AnyVal {
      def toGetBlockHeaders: GetBlockHeaders = rawDecode(bytes) match {
        case RLPList(RLPValue(blockBytes), RLPValue(maxHeadersBytes), RLPValue(skipBytes), RLPValue(reverseBytes))
            if blockBytes.length < 32 =>
          GetBlockHeaders(
            Left(BigInt(1, blockBytes)),
            BigInt(1, maxHeadersBytes),
            BigInt(1, skipBytes),
            BigInt(1, reverseBytes) == 1
          )

        case RLPList(RLPValue(blockBytes), RLPValue(maxHeadersBytes), RLPValue(skipBytes), RLPValue(reverseBytes)) =>
          GetBlockHeaders(
            Right(ByteString(blockBytes)),
            BigInt(1, maxHeadersBytes),
            BigInt(1, skipBytes),
            BigInt(1, reverseBytes) == 1
          )

        case _ => throw new RuntimeException("Cannot decode GetBlockHeaders")
      }
    }
  }

  case class GetBlockHeaders(block: Either[BigInt, ByteString], maxHeaders: BigInt, skip: BigInt, reverse: Boolean)
      extends Message {
    override def code: Int = Codes.GetBlockHeadersCode

    override def toString: String =
      s"GetBlockHeaders{ " +
        s"block: ${block.fold(a => a, b => Hex.toHexString(b.toArray[Byte]))} " +
        s"maxHeaders: $maxHeaders " +
        s"skip: $skip " +
        s"reverse: $reverse " +
        s"}"

    override def toShortString: String = toString
  }

  object BlockBodies {
    implicit class BlockBodiesEnc(val underlyingMsg: BlockBodies)
        extends MessageSerializableImplicit[BlockBodies](underlyingMsg)
        with RLPSerializable {
      override def code: Int = Codes.BlockBodiesCode

      override def toRLPEncodable: RLPEncodeable = RLPList(msg.bodies.map(_.toRLPEncodable): _*)
    }

    implicit class BlockBodiesDec(val bytes: Array[Byte]) extends AnyVal {
      def toBlockBodies: BlockBodies = rawDecode(bytes) match {
        case rlpList: RLPList => BlockBodies(rlpList.items.map(_.toBlockBody))
        case _                => throw new RuntimeException("Cannot decode BlockBodies")
      }
    }
  }

  case class BlockBodies(bodies: Seq[BlockBody]) extends Message {
    val code: Int = Codes.BlockBodiesCode
    override def toShortString: String =
      s"BlockBodies { bodies: ${bodies.map(_.toShortString)} }"
  }

  object BlockHeaders {
    implicit class BlockHeadersEnc(val underlyingMsg: BlockHeaders)
        extends MessageSerializableImplicit[BlockHeaders](underlyingMsg)
        with RLPSerializable {

      override def code: Int = Codes.BlockHeadersCode

      override def toRLPEncodable: RLPEncodeable = RLPList(msg.headers.map(_.toRLPEncodable): _*)
    }

    implicit class BlockHeadersDec(val bytes: Array[Byte]) extends AnyVal {

      def toBlockHeaders: BlockHeaders = rawDecode(bytes) match {
        case rlpList: RLPList => BlockHeaders(rlpList.items.map(_.toBlockHeader))

        case _ => throw new RuntimeException("Cannot decode BlockHeaders")
      }
    }

  }

  case class BlockHeaders(headers: Seq[BlockHeader]) extends Message {
    override def code: Int = Codes.BlockHeadersCode
    override def toShortString: String =
      s"BlockHeaders { headers: ${headers.map(_.hashAsHexString)} }"
  }

  object GetBlockBodies {
    implicit class GetBlockBodiesEnc(val underlyingMsg: GetBlockBodies)
        extends MessageSerializableImplicit[GetBlockBodies](underlyingMsg)
        with RLPSerializable {

      override def code: Int = Codes.GetBlockBodiesCode

      override def toRLPEncodable: RLPEncodeable = toRlpList(msg.hashes)
    }

    implicit class GetBlockBodiesDec(val bytes: Array[Byte]) extends AnyVal {
      def toGetBlockBodies: GetBlockBodies = rawDecode(bytes) match {
        case rlpList: RLPList => GetBlockBodies(fromRlpList[ByteString](rlpList))

        case _ => throw new RuntimeException("Cannot decode BlockHeaders")
      }
    }
  }

  case class GetBlockBodies(hashes: Seq[ByteString]) extends Message {
    override def code: Int = Codes.GetBlockBodiesCode

    override def toString: String =
      s"GetBlockBodies { " +
        s"hashes: ${hashes.map(h => Hex.toHexString(h.toArray[Byte]))} " +
        s"}"
    override def toShortString: String = toString
  }
}
