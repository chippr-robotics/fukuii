package com.chipprbots.ethereum.network.p2p.messages

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex

import com.chipprbots.ethereum.forkid.ForkId
import com.chipprbots.ethereum.forkid.ForkId._
import com.chipprbots.ethereum.network.p2p.Message
import com.chipprbots.ethereum.network.p2p.MessageSerializableImplicit
import com.chipprbots.ethereum.rlp.RLPImplicitConversions._
import com.chipprbots.ethereum.rlp.RLPImplicits._
import com.chipprbots.ethereum.rlp.RLPImplicits.given
import com.chipprbots.ethereum.rlp._

object ETH64 {

  case class Status(
      protocolVersion: Int,
      networkId: Int,
      totalDifficulty: BigInt,
      bestHash: ByteString,
      genesisHash: ByteString,
      forkId: ForkId
  ) extends Message {

    override def toString: String =
      s"Status { " +
        s"code: $code, " +
        s"protocolVersion: $protocolVersion, " +
        s"networkId: $networkId, " +
        s"totalDifficulty: $totalDifficulty, " +
        s"bestHash: ${Hex.toHexString(bestHash.toArray[Byte])}, " +
        s"genesisHash: ${Hex.toHexString(genesisHash.toArray[Byte])}," +
        s"forkId: $forkId," +
        s"}"

    override def toShortString: String = toString
    override def code: Int = Codes.StatusCode
  }

  object Status {
    implicit class StatusEnc(val underlyingMsg: Status)
        extends MessageSerializableImplicit[Status](underlyingMsg)
        with RLPSerializable {
      override def code: Int = Codes.StatusCode

      override def toRLPEncodable: RLPEncodeable = {
        import msg._
        RLPList(
          RLPValue(BigInt(protocolVersion).toByteArray),
          RLPValue(BigInt(networkId).toByteArray),
          RLPValue(totalDifficulty.toByteArray),
          RLPValue(bestHash.toArray[Byte]),
          RLPValue(genesisHash.toArray[Byte]),
          forkId.toRLPEncodable
        )
      }
    }

    implicit class StatusDec(val bytes: Array[Byte]) extends AnyVal {
      def toStatus: Status = rawDecode(bytes) match {
        case RLPList(
              RLPValue(protocolVersionBytes),
              RLPValue(networkIdBytes),
              RLPValue(totalDifficultyBytes),
              RLPValue(bestHashBytes),
              RLPValue(genesisHashBytes),
              forkId
            ) =>
          Status(
            BigInt(1, protocolVersionBytes).toInt,
            BigInt(1, networkIdBytes).toInt,
            BigInt(1, totalDifficultyBytes),
            ByteString(bestHashBytes),
            ByteString(genesisHashBytes),
            decode[ForkId](forkId)
          )

        case _ => throw new RuntimeException("Cannot decode Status")
      }
    }
  }
}
