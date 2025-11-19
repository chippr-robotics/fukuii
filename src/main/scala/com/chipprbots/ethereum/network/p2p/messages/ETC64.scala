package com.chipprbots.ethereum.network.p2p.messages

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex

import com.chipprbots.ethereum.domain.BlockHeaderImplicits._
import com.chipprbots.ethereum.domain._
import com.chipprbots.ethereum.network.p2p.Message
import com.chipprbots.ethereum.network.p2p.MessageSerializableImplicit
import com.chipprbots.ethereum.rlp.RLPImplicitConversions._
import com.chipprbots.ethereum.rlp.RLPImplicits._
import com.chipprbots.ethereum.rlp.RLPImplicits.given
import com.chipprbots.ethereum.rlp._
import com.chipprbots.ethereum.utils.ByteUtils

/** This is temporary ETC64 version, the real one will be implemented by ETCM-355 This one will be probably ETC67 in the
  * future
  */
object ETC64 {
  object Status {
    implicit class StatusEnc(val underlyingMsg: Status)
        extends MessageSerializableImplicit[Status](underlyingMsg)
        with RLPSerializable {
      override def code: Int = Codes.StatusCode

      override def toRLPEncodable: RLPEncodeable = {
        import msg._
        RLPList(
          protocolVersion,
          networkId,
          chainWeight.totalDifficulty,
          chainWeight.lastCheckpointNumber,
          RLPValue(bestHash.toArray[Byte]),
          RLPValue(genesisHash.toArray[Byte])
        )
      }
    }

    implicit class StatusDec(val bytes: Array[Byte]) extends AnyVal {
      def toStatus: Status = rawDecode(bytes) match {
        case RLPList(
              RLPValue(protocolVersionBytes),
              RLPValue(networkIdBytes),
              RLPValue(totalDifficultyBytes),
              RLPValue(lastCheckpointNumberBytes),
              RLPValue(bestHashBytes),
              RLPValue(genesisHashBytes)
            ) =>
          Status(
            ByteUtils.bytesToBigInt(protocolVersionBytes).toInt,
            ByteUtils.bytesToBigInt(networkIdBytes).toInt,
            ChainWeight(
              ByteUtils.bytesToBigInt(lastCheckpointNumberBytes),
              ByteUtils.bytesToBigInt(totalDifficultyBytes)
            ),
            ByteString(bestHashBytes),
            ByteString(genesisHashBytes)
          )

        case _ => throw new RuntimeException("Cannot decode Status ETC64 version")
      }
    }

  }

  case class Status(
      protocolVersion: Int,
      networkId: Int,
      chainWeight: ChainWeight,
      bestHash: ByteString,
      genesisHash: ByteString
  ) extends Message {

    override def toString: String =
      s"Status { " +
        s"protocolVersion: $protocolVersion, " +
        s"networkId: $networkId, " +
        s"chainWeight: $chainWeight, " +
        s"bestHash: ${Hex.toHexString(bestHash.toArray[Byte])}, " +
        s"genesisHash: ${Hex.toHexString(genesisHash.toArray[Byte])}," +
        s"}"

    override def toShortString: String = toString

    override def code: Int = Codes.StatusCode
  }

  object NewBlock {
    implicit class NewBlockEnc(val underlyingMsg: NewBlock)
        extends MessageSerializableImplicit[NewBlock](underlyingMsg)
        with RLPSerializable {
      import com.chipprbots.ethereum.network.p2p.messages.BaseETH6XMessages.SignedTransactions._

      override def code: Int = Codes.NewBlockCode

      override def toRLPEncodable: RLPEncodeable = {
        import msg._
        RLPList(
          RLPList(
            block.header.toRLPEncodable,
            RLPList(block.body.transactionList.map(_.toRLPEncodable): _*),
            RLPList(block.body.uncleNodesList.map(_.toRLPEncodable): _*)
          ),
          chainWeight.totalDifficulty,
          chainWeight.lastCheckpointNumber
        )
      }
    }

    implicit class NewBlockDec(val bytes: Array[Byte]) extends AnyVal {
      import com.chipprbots.ethereum.network.p2p.messages.BaseETH6XMessages.SignedTransactions._
      import com.chipprbots.ethereum.network.p2p.messages.BaseETH6XMessages.TypedTransaction._

      def toNewBlock: NewBlock = rawDecode(bytes) match {
        case RLPList(
              RLPList(blockHeader, transactionList: RLPList, (uncleNodesList: RLPList)),
              RLPValue(totalDifficultyBytes),
              RLPValue(lastCheckpointNumberBytes)
            ) =>
          NewBlock(
            Block(
              blockHeader.toBlockHeader,
              BlockBody(
                transactionList.items.toTypedRLPEncodables.map(_.toSignedTransaction),
                uncleNodesList.items.map(_.toBlockHeader)
              )
            ),
            ChainWeight(
              ByteUtils.bytesToBigInt(lastCheckpointNumberBytes),
              ByteUtils.bytesToBigInt(totalDifficultyBytes)
            )
          )
        case _ => throw new RuntimeException("Cannot decode NewBlock ETC64 version")
      }
    }
  }

  case class NewBlock(block: Block, chainWeight: ChainWeight) extends Message {
    override def toString: String =
      s"NewBlock { " +
        s"block: $block, " +
        s"chainWeight: $chainWeight" +
        s"}"

    override def toShortString: String =
      s"NewBlock { " +
        s"block.header: ${block.header}, " +
        s"chainWeight: $chainWeight" +
        s"}"

    override def code: Int = Codes.NewBlockCode
  }
}
