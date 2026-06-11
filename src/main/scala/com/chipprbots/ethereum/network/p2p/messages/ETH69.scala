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
  *   - Status message: removes totalDifficulty, adds block range. Per EIP-7642 the wire layout is `[version, networkId,
  *     genesis, forkId, earliestBlock, latestBlock, latestBlockHash]` — **7 fields**. Both core-geth's `StatusPacket`
  *     and besu's `StatusMessage` encode all 7 with `earliestBlock` between `forkId` and `latestBlock`. PR #1194
  *     erroneously dropped `earliestBlock` thinking BlockRangeUpdate (0x11) carried it; that broke cross-client interop
  *     because peers' RLP decoder reads `latestBlockHash` (32 bytes) into `latestBlock` (uint64) and rejects the
  *     handshake with `rlp: input string too long for uint64`. The 7-field layout is restored to match geth/besu.
  *   - Receipt encoding: removes bloom filter, uses flat RLP list with explicit tx-type field
  *   - New BlockRangeUpdate (0x11) notification message — sent periodically AFTER the initial 7-field STATUS
  *   - All other messages (GetBlockHeaders, BlockHeaders, etc.) unchanged from ETH/68
  */
object ETH69 {

  /** ETH/69 Status message.
    *
    * Wire format per EIP-7642 (matches geth + besu): `[version, networkId, genesis, forkId, earliestBlock, latestBlock,
    * latestBlockHash]` — 7 fields.
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
      // EIP-7642 wire layout: 7 fields, with earliestBlock between forkId and latestBlock.
      // Matches go-ethereum's `StatusPacket` (eth/protocols/eth/protocol.go) and besu's
      // `StatusMessage69` exactly. Closes the cross-client interop break observed in
      // hive's `ethereum/sync` simulator after PR #1194.
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

      /** Decode an ETH/69 STATUS frame.
        *
        * Tolerant of three shapes:
        *   1. **Canonical 7-field EIP-7642** (forkId at idx 3): `[version, networkId, genesis, forkId, earliest,
        *      latest, latestHash]` — what core-geth's `StatusPacket`, besu's `StatusMessage69`, reth, and post-fix
        *      fukuii send. 2. **6-field legacy** (forkId at idx 3): `[version, networkId, genesis, forkId, latest,
        *      latestHash]` — pre-fix fukuii nodes that omitted `earliestBlock` (PR #1194). Treat `earliestBlock = 0` so
        *      the peer is still accepted; older fukuii nodes are minority and cycle out. 3. **ETH/68-shape on ETH/69
        *      channel** (6 fields, forkId at idx 5): `[version, networkId, td, bestHash, genesis, forkId]` — peers
        *      (often wrong-chain like Holesky) that announce ETH/69 capability but emit the older ETH/68 STATUS layout.
        *      The decode lets the peer reach the genesis-hash check, where they get rejected cleanly with `Useless
        *      peer` instead of a noisy `DECODE_ERROR`. `totalDifficulty` is dropped on purpose; ETC TD recovery uses
        *      local ChainWeightStorage.
        *
        * The 6-field legacy path and the ETH/68 path share field count but differ structurally — legacy has the forkId
        * RLPList at index 3, ETH/68 has it at index 5. Scala pattern matching distinguishes them via the type at each
        * position (`RLPList` vs `RLPValue`).
        */
      def toETH69Status: Status = rawDecode(bytes) match {
        // (1) Canonical 7-field EIP-7642 shape — geth/besu/reth.
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
        // (3) 6-field ETH/68-shape on ETH/69 channel (forkId at idx 5) — common from wrong-chain peers
        // (e.g. Holesky-derived testnets) that announce ETH/69 but emit ETH/68 STATUS. We accept the
        // payload so the genesis check downstream can disconnect them as `Useless peer`.
        case RLPList(
              RLPValue(protocolVersionBytes),
              RLPValue(networkIdBytes),
              RLPValue(_),
              RLPValue(bestHashBytes),
              RLPValue(genesisHashBytes),
              forkIdRlp: RLPList
            ) =>
          Status(
            protocolVersion = ByteUtils.bytesToBigInt(protocolVersionBytes).toInt,
            networkId = ByteUtils.bytesToBigInt(networkIdBytes).toLong,
            genesisHash = ByteString(genesisHashBytes),
            forkId = decode[ForkId](forkIdRlp),
            earliestBlock = BigInt(0),
            latestBlock = BigInt(0),
            latestBlockHash = ByteString(bestHashBytes)
          )
        // (2) 6-field legacy fukuii shape (forkId at idx 3) — pre-fix fukuii nodes that omitted earliestBlock.
        // Accept with earliestBlock=0 so old fukuii peers still handshake during the rollout window.
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
    implicit class BlockRangeUpdateEnc(val underlyingMsg: BlockRangeUpdate)
        extends MessageSerializableImplicit[BlockRangeUpdate](underlyingMsg)
        with RLPSerializable {
      override def code: Int = Codes.BlockRangeUpdateCode
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
