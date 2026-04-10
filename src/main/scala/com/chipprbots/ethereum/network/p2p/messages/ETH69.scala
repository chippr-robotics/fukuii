package com.chipprbots.ethereum.network.p2p.messages

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.forkid.ForkId
import com.chipprbots.ethereum.utils.ByteStringUtils.ByteStringOps
import com.chipprbots.ethereum.network.p2p.Message
import com.chipprbots.ethereum.network.p2p.MessageSerializableImplicit
import com.chipprbots.ethereum.rlp._
import com.chipprbots.ethereum.rlp.RLPImplicitConversions._
import com.chipprbots.ethereum.rlp.RLPImplicits._
import com.chipprbots.ethereum.rlp.RLPImplicits.given
import com.chipprbots.ethereum.utils.ByteUtils

/** ETH/69 protocol (EIP-7642) — restructured Status, simplified receipts, BlockRangeUpdate.
  *
  * Key changes from ETH/68:
  * - Status message: removes totalDifficulty, reorders fields, adds earliestBlock/latestBlock
  * - Receipt encoding: removes bloom filter, uses flat RLP list with explicit tx-type field
  * - New BlockRangeUpdate (0x11) notification message
  * - All other messages (GetBlockHeaders, BlockHeaders, etc.) unchanged from ETH/68
  */
object ETH69 {

  /** ETH/69 Status message.
    * RLP: [version, networkId, genesis, forkId, earliestBlock, latestBlock, latestBlockHash]
    */
  case class Status(
      protocolVersion: Int,
      networkId: Long,
      genesisHash: ByteString,
      forkId: ForkId,
      earliestBlock: BigInt,
      latestBlock: BigInt,
      latestBlockHash: ByteString
  ) extends Message {
    override val code: Int = Codes.StatusCode
    override def toShortString: String = toString

    override def toString: String =
      s"ETH69.Status(v=$protocolVersion, net=$networkId, genesis=${genesisHash.take(4).toHex}..., " +
        s"forkId=$forkId, earliest=$earliestBlock, latest=$latestBlock, " +
        s"latestHash=${latestBlockHash.take(4).toHex}...)"
  }

  object Status {
    implicit class StatusEnc(val underlyingMsg: Status)
        extends MessageSerializableImplicit[Status](underlyingMsg)
        with RLPSerializable {
      override def code: Int = Codes.StatusCode
      import msg._
      override def toRLPEncodable: RLPEncodeable = RLPList(
        RLPValue(ByteUtils.bigIntToUnsignedByteArray(protocolVersion)),
        RLPValue(ByteUtils.bigIntToUnsignedByteArray(networkId)),
        RLPValue(genesisHash.toArray[Byte]),
        forkId.toRLPEncodable,
        RLPValue(ByteUtils.bigIntToUnsignedByteArray(earliestBlock)),
        RLPValue(ByteUtils.bigIntToUnsignedByteArray(latestBlock)),
        RLPValue(latestBlockHash.toArray[Byte])
      )
    }

    implicit class StatusDec(val bytes: Array[Byte]) extends AnyVal {
      import com.chipprbots.ethereum.forkid.ForkId._

      def toETH69Status: Status = rawDecode(bytes) match {
        case RLPList(
              RLPValue(protocolVersionBytes),
              RLPValue(networkIdBytes),
              RLPValue(genesisHashBytes),
              forkIdRlp: RLPList,
              RLPValue(earliestBlockBytes),
              RLPValue(latestBlockBytes),
              RLPValue(latestBlockHashBytes)
            ) =>
          Status(
            protocolVersion = ByteUtils.bytesToBigInt(protocolVersionBytes).toInt,
            networkId = ByteUtils.bytesToBigInt(networkIdBytes).toLong,
            genesisHash = ByteString(genesisHashBytes),
            forkId = decode[ForkId](forkIdRlp),
            earliestBlock = ByteUtils.bytesToBigInt(earliestBlockBytes),
            latestBlock = ByteUtils.bytesToBigInt(latestBlockBytes),
            latestBlockHash = ByteString(latestBlockHashBytes)
          )
        case other => throw new RuntimeException(s"Cannot decode ETH69.Status from: $other")
      }
    }
  }

  /** BlockRangeUpdate notification (0x11).
    * Sent when peer's available block range changes. No request-id.
    * RLP: [earliestBlock, latestBlock, latestBlockHash]
    */
  case class BlockRangeUpdate(
      earliestBlock: BigInt,
      latestBlock: BigInt,
      latestBlockHash: ByteString
  ) extends Message {
    override val code: Int = Codes.BlockRangeUpdateCode
    override def toShortString: String = s"BlockRangeUpdate(earliest=$earliestBlock, latest=$latestBlock)"
  }

  object BlockRangeUpdate {
    implicit class BlockRangeUpdateEnc(val msg: BlockRangeUpdate) extends RLPSerializable {
      override def toRLPEncodable: RLPEncodeable = RLPList(
        RLPValue(ByteUtils.bigIntToUnsignedByteArray(msg.earliestBlock)),
        RLPValue(ByteUtils.bigIntToUnsignedByteArray(msg.latestBlock)),
        RLPValue(msg.latestBlockHash.toArray[Byte])
      )
    }

    implicit class BlockRangeUpdateDec(val bytes: Array[Byte]) extends AnyVal {
      def toBlockRangeUpdate: BlockRangeUpdate = rawDecode(bytes) match {
        case RLPList(
              RLPValue(earliestBlockBytes),
              RLPValue(latestBlockBytes),
              RLPValue(latestBlockHashBytes)
            ) =>
          BlockRangeUpdate(
            earliestBlock = ByteUtils.bytesToBigInt(earliestBlockBytes),
            latestBlock = ByteUtils.bytesToBigInt(latestBlockBytes),
            latestBlockHash = ByteString(latestBlockHashBytes)
          )
        case other => throw new RuntimeException(s"Cannot decode BlockRangeUpdate from: $other")
      }
    }
  }
}
