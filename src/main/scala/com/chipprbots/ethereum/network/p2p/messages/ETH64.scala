package com.chipprbots.ethereum.network.p2p.messages

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex

import com.chipprbots.ethereum.forkid.ForkId
import com.chipprbots.ethereum.forkid.ForkId._
import com.chipprbots.ethereum.network.p2p.Message
import com.chipprbots.ethereum.network.p2p.MessageSerializableImplicit
import com.chipprbots.ethereum.rlp.RLPImplicits._
import com.chipprbots.ethereum.rlp._
import com.chipprbots.ethereum.utils.ByteUtils

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
        // Use bigIntToUnsignedByteArray for proper RLP integer encoding
        // BigInt.toByteArray uses two's complement which adds leading zeros for
        // values with high bit set (e.g., 128 -> [0x00, 0x80] instead of [0x80])
        // RLP specification requires integers to have no leading zeros
        RLPList(
          RLPValue(ByteUtils.bigIntToUnsignedByteArray(BigInt(protocolVersion))),
          RLPValue(ByteUtils.bigIntToUnsignedByteArray(BigInt(networkId))),
          RLPValue(ByteUtils.bigIntToUnsignedByteArray(totalDifficulty)),
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
            ByteUtils.bytesToBigInt(protocolVersionBytes).toInt,
            ByteUtils.bytesToBigInt(networkIdBytes).toInt,
            ByteUtils.bytesToBigInt(totalDifficultyBytes),
            ByteString(bestHashBytes),
            ByteString(genesisHashBytes),
            decode[ForkId](forkId)
          )

        case _ => throw new RuntimeException("Cannot decode Status")
      }
    }
  }
}
