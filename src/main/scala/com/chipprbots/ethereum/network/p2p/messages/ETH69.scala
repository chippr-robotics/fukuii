package com.chipprbots.ethereum.network.p2p.messages

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.forkid.ForkId
import com.chipprbots.ethereum.utils.ByteStringUtils.ByteStringOps
import com.chipprbots.ethereum.network.p2p.Message
import com.chipprbots.ethereum.network.p2p.MessageSerializableImplicit
import com.chipprbots.ethereum.rlp._
import com.chipprbots.ethereum.utils.ByteUtils

/** ETH/69 protocol (EIP-7642) — restructured Status, simplified receipts, BlockRangeUpdate.
  *
  * Key changes from ETH/68:
  *   - Status message: removes totalDifficulty, reorders fields. Per EIP-7642 the wire layout is `[version, networkId,
  *     genesis, forkId, latestBlock, latestBlockHash]` — exactly 6 fields. The `earliestBlock` info is conveyed by the
  *     separate BlockRangeUpdate message (0x11).
  *   - Receipt encoding: removes bloom filter, uses flat RLP list with explicit tx-type field
  *   - New BlockRangeUpdate (0x11) notification message
  *   - All other messages (GetBlockHeaders, BlockHeaders, etc.) unchanged from ETH/68
  */
object ETH69 {

  /** ETH/69 Status message.
    *
    * Wire format per EIP-7642: `[version, networkId, genesis, forkId, latestBlock, latestBlockHash]` — 6 fields. The
    * `earliestBlock` field on this case class is convenience-only (set by `BlockRangeUpdate` later in the session) and
    * is NOT serialized in the STATUS frame.
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
      // EIP-7642 wire layout: 6 fields. earliestBlock is intentionally omitted —
      // it travels via the BlockRangeUpdate (0x11) message instead. Sending 7 fields
      // here is a fukuii-only legacy and breaks interop with core-geth/besu (#1194).
      override def toRLPEncodable: RLPEncodeable = RLPList(
        RLPValue(ByteUtils.bigIntToUnsignedByteArray(protocolVersion)),
        RLPValue(ByteUtils.bigIntToUnsignedByteArray(networkId)),
        RLPValue(genesisHash.toArray[Byte]),
        forkId.toRLPEncodable,
        RLPValue(ByteUtils.bigIntToUnsignedByteArray(latestBlock)),
        RLPValue(latestBlockHash.toArray[Byte])
      )
    }

    implicit class StatusDec(val bytes: Array[Byte]) extends AnyVal {
      import com.chipprbots.ethereum.forkid.ForkId._

      /** Decode an ETH/69 STATUS frame.
        *
        * Tolerant of two shapes for backward compatibility during the rollout:
        *   1. EIP-7642 spec (6 fields): `[version, networkId, genesis, forkId, latest, latestHash]` 2. Legacy fukuii
        *      pre-#1194 (7 fields): `[version, networkId, genesis, forkId, earliest, latest, latestHash]` — used by
        *      older fukuii nodes. Decoded with `earliestBlock` set to its wire value; new fukuii nodes never emit this
        *      shape.
        *
        * The 6-field path sets `earliestBlock = 0` because the field is not on the wire. Once the network is
        * unanimously on the spec shape, the 7-field branch can be removed.
        */
      def toETH69Status: Status = rawDecode(bytes) match {
        // 6-field EIP-7642 spec shape — what core-geth, besu, reth, and post-#1194 fukuii send.
        case RLPList(
              RLPValue(protocolVersionBytes),
              RLPValue(networkIdBytes),
              RLPValue(genesisHashBytes),
              forkIdRlp: RLPList,
              RLPValue(latestBlockBytes),
              RLPValue(latestBlockHashBytes)
            ) =>
          Status(
            protocolVersion = ByteUtils.bytesToBigInt(protocolVersionBytes).toInt,
            networkId = ByteUtils.bytesToBigInt(networkIdBytes).toLong,
            genesisHash = ByteString(genesisHashBytes),
            forkId = decode[ForkId](forkIdRlp),
            earliestBlock = BigInt(0),
            latestBlock = ByteUtils.bytesToBigInt(latestBlockBytes),
            latestBlockHash = ByteString(latestBlockHashBytes)
          )
        // 7-field legacy fukuii shape — kept for backward compat with pre-#1194 nodes.
        case RLPList(
              RLPValue(protocolVersionBytes),
              RLPValue(networkIdBytes),
              RLPValue(genesisHashBytes),
              forkIdRlp: RLPList,
              RLPValue(earliestBlockBytes),
              RLPValue(latestBlockBytes),
              RLPValue(latestBlockHashBytes),
              _*
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

  /** BlockRangeUpdate notification (0x11). Sent when peer's available block range changes. No request-id. RLP:
    * [earliestBlock, latestBlock, latestBlockHash]
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
