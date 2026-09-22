package com.chipprbots.ethereum.network.p2p.messages

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex

import com.chipprbots.ethereum.crypto.ECDSASignature
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.domain.BlockBody.BlockBodyRLPEncodableDec
import com.chipprbots.ethereum.domain.BlockHeaderImplicits.*
import com.chipprbots.ethereum.forkid.ForkId
import com.chipprbots.ethereum.forkid.ForkId.*
import com.chipprbots.ethereum.network.p2p.Message
import com.chipprbots.ethereum.network.p2p.MessageSerializableImplicit
import com.chipprbots.ethereum.rlp.*
import com.chipprbots.ethereum.rlp.RLPCodec.Ops
import com.chipprbots.ethereum.rlp.RLPImplicitConversions.*
import com.chipprbots.ethereum.rlp.RLPImplicits.given
import com.chipprbots.ethereum.utils.ByteStringUtils.ByteStringOps
import com.chipprbots.ethereum.utils.ByteUtils

/** ETH/68+ wire protocol packet definitions — the single canonical source.
  *
  * Analogous to: go-ethereum eth/protocols/eth/protocol.go Erigon p2p/protocols/eth/protocol.go Reth
  * crates/net/eth-wire-types/src/message.rs
  *
  * NAMING RULE (universal consensus across all 5 reference clients): No version suffix: wire format IDENTICAL across
  * ETH68-ETH71. Version suffix: wire format DIFFERS between supported versions.
  *
  * MESSAGE CODE TABLE (Fukuii wire codes = std + 0x10, capability framing offset): Wire Std Message Object Versions
  * 0x10 (0x00) Status (ETH68) Status68 ETH68 only (TD) 0x10 (0x00) Status (ETH69) Status69 ETH69+ (no TD) 0x11 (0x01)
  * NewBlockHashes NewBlockHashes ETH68-71 (unchanged) 0x12 (0x02) Transactions SignedTransactions ETH68-71 (unchanged)
  * 0x13 (0x03) GetBlockHeaders GetBlockHeaders ETH68-71 (unchanged) 0x14 (0x04) BlockHeaders BlockHeaders ETH68-71
  * (unchanged) 0x15 (0x05) GetBlockBodies GetBlockBodies ETH68-71 (unchanged) 0x16 (0x06) BlockBodies BlockBodies
  * ETH68-71 (unchanged) 0x17 (0x07) NewBlock NewBlock ETH68-71 (unchanged) 0x18 (0x08) NewPooledTxHashes
  * NewPooledTransactionHashes ETH68-71 (unchanged) 0x19 (0x09) GetPooledTransactions GetPooledTransactions ETH68-71
  * (unchanged) 0x1a (0x0a) PooledTransactions PooledTransactions ETH68-71 (unchanged) 0x1d (0x0d) GetNodeData REJECTED
  * (EIP-4938) ETH68+ 0x1e (0x0e) NodeData REJECTED (EIP-4938) ETH68+ 0x1f (0x0f) GetReceipts GetReceipts ETH68-69 (GET
  * unchanged) 0x20 (0x10) Receipts (ETH68) Receipts68 ETH68 (bloom present) 0x20 (0x10) Receipts (ETH69) Receipts69
  * ETH69 (bloom absent, EIP-7642) 0x21 (0x11) BlockRangeUpdate BlockRangeUpdate ETH69+ (new)
  *
  * No imports from ETH62-67 or BaseETH6XMessages. All definitions are standalone. This replaces the scattered
  * definitions across ETH62.scala through ETH67.scala and BaseETH6XMessages.scala once those files are retired.
  */
object ETHPackets:

  // Replaces ETH66.HasRequestId once ETH66 is deleted.
  trait HasRequestId:
    def requestId: BigInt

  // Replaces ETHPackets.nextRequestId once ETH66 is deleted.
  private val requestIdCounter = new java.util.concurrent.atomic.AtomicLong(1L)
  def nextRequestId: BigInt = BigInt(requestIdCounter.getAndIncrement())

  // ── RLP CODECS (copied from BaseETH6XMessages — needed for AccessListItem, SetCodeAuthorization) ──

  implicit val addressCodec: RLPCodec[Address] =
    implicitly[RLPCodec[Array[Byte]]].xmap(Address(_), _.toArray)

  implicit val accessListItemCodec: RLPCodec[AccessListItem] =
    RLPCodec.instance[AccessListItem](
      { case AccessListItem(address, storageKeys) =>
        RLPList(address, toRlpList(storageKeys.map(sk => UInt256(sk.value).bytes.toArray)))
      },
      {
        case r: RLPList if r.items.isEmpty => AccessListItem(null, List.empty)
        case RLPList(rlpAddress, rlpStorageKeys: RLPList) =>
          AccessListItem(
            rlpAddress.decodeAs[Address]("address"),
            fromRlpList[BigInt](rlpStorageKeys).toList.map(StorageKey(_))
          )
      }
    )

  implicit val setCodeAuthorizationCodec: RLPCodec[SetCodeAuthorization] =
    RLPCodec.instance[SetCodeAuthorization](
      { case SetCodeAuthorization(chainId, address, nonce, v, r, s) =>
        RLPList(
          RLPValue(ByteUtils.bigIntToUnsignedByteArray(chainId)),
          address,
          RLPValue(ByteUtils.bigIntToUnsignedByteArray(nonce)),
          RLPValue(ByteUtils.bigIntToUnsignedByteArray(v)),
          RLPValue(ByteUtils.bigIntToUnsignedByteArray(r)),
          RLPValue(ByteUtils.bigIntToUnsignedByteArray(s))
        )
      },
      { case RLPList(rlpChainId, rlpAddress, rlpNonce, rlpV, rlpR, rlpS) =>
        SetCodeAuthorization(
          rlpChainId.decodeAs[BigInt]("chainId"),
          rlpAddress.decodeAs[Address]("address"),
          rlpNonce.decodeAs[BigInt]("nonce"),
          rlpV.decodeAs[BigInt]("v"),
          rlpR.decodeAs[BigInt]("r"),
          rlpS.decodeAs[BigInt]("s")
        )
      }
    )

  // ── TYPED TRANSACTION HELPERS ────────────────────────────────────────────────
  // Copied from ETHPackets.TypedTransaction — needed by SignedTransactions and PooledTransactions.

  object TypedTransaction:
    extension (encodables: Seq[RLPEncodeable])
      def toTypedRLPEncodables: Seq[RLPEncodeable] =
        import Transaction.ByteArrayTransactionTypeValidator
        import Transaction.TransactionTypeValidator
        val result = new scala.collection.mutable.ArrayBuffer[RLPEncodeable](encodables.size)
        var i = 0
        val items = encodables.toIndexedSeq
        val len = items.size
        while i < len do
          items(i) match
            case RLPValue(v) if v.isValidTransactionType && i + 1 < len =>
              items(i + 1) match
                case rlpList: RLPList =>
                  result += PrefixedRLPEncodable(v.head, rlpList)
                  i += 2
                case _ =>
                  result += items(i)
                  i += 1
            case RLPValue(v) if v.length > 1 && v.head.isValidTransactionType =>
              try
                rawDecode(v.tail) match
                  case rlpList: RLPList => result += PrefixedRLPEncodable(v.head, rlpList)
                  case _                => result += RLPValue(v)
              catch case _: Throwable => result += RLPValue(v)
              i += 1
            case other =>
              result += other
              i += 1
        result.toSeq

  // ── STATUS — version-suffixed: ETH68 has TD, ETH69 does not ──────────────────
  //
  // Reference: Reth status.rs: Status / StatusEth69
  //            Erigon protocol.go: StatusPacket / StatusPacket69
  //            Nethermind V68/Messages/StatusMessage.cs / V69/Messages/StatusMessage69.cs
  //
  // Source: ETH64.scala (Status68), ETH69.scala (Status69)

  object Status68:
    case class Status68(
        protocolVersion: Int,
        networkId: Long,
        totalDifficulty: BigInt,
        bestHash: ByteString,
        genesisHash: ByteString,
        forkId: ForkId
    ) extends Message:
      override def toString: String =
        s"Status68 { v=$protocolVersion, net=$networkId, td=$totalDifficulty, " +
          s"best=${Hex.toHexString(bestHash.toArray[Byte])}, genesis=${Hex.toHexString(genesisHash.toArray[Byte])}, " +
          s"forkId=$forkId }"
      override def toShortString: String = toString
      override def code: Int = Codes.StatusCode

    object Status68:
      implicit class Status68Enc(val underlyingMsg: Status68)
          extends MessageSerializableImplicit[Status68](underlyingMsg)
          with RLPSerializable:
        override def code: Int = Codes.StatusCode
        override def toRLPEncodable: RLPEncodeable =
          import msg.*
          RLPList(
            RLPValue(ByteUtils.bigIntToUnsignedByteArray(BigInt(protocolVersion))),
            RLPValue(ByteUtils.bigIntToUnsignedByteArray(BigInt(networkId))),
            RLPValue(ByteUtils.bigIntToUnsignedByteArray(totalDifficulty)),
            RLPValue(bestHash.toArray[Byte]),
            RLPValue(genesisHash.toArray[Byte]),
            forkId.toRLPEncodable
          )

      extension (bytes: Array[Byte])
        def toStatus68: Status68 = rawDecode(bytes) match
          case RLPList(
                RLPValue(protocolVersionBytes),
                RLPValue(networkIdBytes),
                RLPValue(totalDifficultyBytes),
                RLPValue(bestHashBytes),
                RLPValue(genesisHashBytes),
                forkId
              ) =>
            Status68(
              ByteUtils.bytesToBigInt(protocolVersionBytes).toInt,
              ByteUtils.bytesToBigInt(networkIdBytes).toLong,
              ByteUtils.bytesToBigInt(totalDifficultyBytes),
              ByteString(bestHashBytes),
              ByteString(genesisHashBytes),
              decode[ForkId](forkId)
            )
          case _ => throw new RuntimeException("Cannot decode Status68")

  object Status69:
    case class Status69(
        protocolVersion: Int,
        networkId: Long,
        genesisHash: ByteString,
        forkId: ForkId,
        earliestBlock: BigInt,
        latestBlock: BigInt,
        latestBlockHash: ByteString
    ) extends Message:
      override val code: Int = Codes.StatusCode
      override def toShortString: String = toString
      override def toString: String =
        s"Status69(v=$protocolVersion, net=$networkId, genesis=${genesisHash.take(4).toHex}..., " +
          s"forkId=$forkId, earliest=$earliestBlock, latest=$latestBlock, " +
          s"latestHash=${latestBlockHash.take(4).toHex}...)"

    object Status69:
      implicit class Status69Enc(val underlyingMsg: Status69)
          extends MessageSerializableImplicit[Status69](underlyingMsg)
          with RLPSerializable:
        override def code: Int = Codes.StatusCode
        override def toRLPEncodable: RLPEncodeable =
          import msg.*
          RLPList(
            RLPValue(ByteUtils.bigIntToUnsignedByteArray(protocolVersion)),
            RLPValue(ByteUtils.bigIntToUnsignedByteArray(networkId)),
            RLPValue(genesisHash.toArray[Byte]),
            forkId.toRLPEncodable,
            RLPValue(ByteUtils.bigIntToUnsignedByteArray(earliestBlock)),
            RLPValue(ByteUtils.bigIntToUnsignedByteArray(latestBlock)),
            RLPValue(latestBlockHash.toArray[Byte])
          )

      extension (bytes: Array[Byte])
        // Two-arm decode: canonical match + catch-all stub.
        // The catch-all covers all off-spec STATUS shapes (ETH/68-shaped, legacy 6-field,
        // ≥8-field extensions, no-forkId variants) without requiring a new arm per variant.
        // Stub uses empty genesisHash so the upstream networkId/genesis check always rejects
        // via UselessPeer rather than crashing the codec with DECODE_ERROR.
        def toStatus69: Status69 = rawDecode(bytes) match
          // (1) Canonical 7-field EIP-7642 — geth/besu/reth/nethermind on a real eth/69 chain.
          case RLPList(
                RLPValue(protocolVersionBytes),
                RLPValue(networkIdBytes),
                RLPValue(genesisHashBytes),
                forkIdRlp: RLPList,
                RLPValue(earliestBlockBytes),
                RLPValue(latestBlockBytes),
                RLPValue(latestBlockHashBytes)
              ) =>
            Status69(
              ByteUtils.bytesToBigInt(protocolVersionBytes).toInt,
              ByteUtils.bytesToBigInt(networkIdBytes).toLong,
              ByteString(genesisHashBytes),
              decode[ForkId](forkIdRlp),
              ByteUtils.bytesToBigInt(earliestBlockBytes),
              ByteUtils.bytesToBigInt(latestBlockBytes),
              ByteString(latestBlockHashBytes)
            )
          // (2) Anything else: extract version+networkId for tracing, stub the rest.
          // Observed variants: ETH/68-shape (TD+bestHash), legacy 6-field (no earliestBlock),
          // ≥8-field extensions, all-RLPValue (no forkId list). All are off-network or
          // off-spec and will be rejected by the genesis/networkId check as UselessPeer.
          case RLPList(RLPValue(protocolVersionBytes), RLPValue(networkIdBytes), _*) =>
            Status69(
              ByteUtils.bytesToBigInt(protocolVersionBytes).toInt,
              ByteUtils.bytesToBigInt(networkIdBytes).toLong,
              genesisHash = ByteString.empty,
              forkId = ForkId(0, None),
              earliestBlock = BigInt(0),
              latestBlock = BigInt(0),
              latestBlockHash = ByteString.empty
            )
          case other =>
            val fieldCount = other match
              case RLPList(items*) => items.length; case _ => -1
            throw new RuntimeException(s"Cannot decode Status69 (got $fieldCount fields): $other")

  // ── Status70 — ETH70 Status message ─────────────────────────────────────────
  //
  // ETH70 does not change the Status wire format vs ETH69 (still 7 fields, no TD).
  // A separate type keeps ETH70's decoder self-contained: if ETH69 is deprecated and
  // Status69 is removed, ETH70 continues to compile without modification.
  //
  // Wire: [protocolVersion, networkId, genesisHash, forkId, earliestBlock, latestBlock, latestBlockHash]
  // Reference: EIP-7706 — Status format unchanged from EIP-7642.

  object Status70:
    case class Status70(
        protocolVersion: Int,
        networkId: Long,
        genesisHash: ByteString,
        forkId: ForkId,
        earliestBlock: BigInt,
        latestBlock: BigInt,
        latestBlockHash: ByteString
    ) extends Message:
      override val code: Int = Codes.StatusCode
      override def toShortString: String = toString
      override def toString: String =
        s"Status70(v=$protocolVersion, net=$networkId, genesis=${genesisHash.take(4).toHex}..., " +
          s"forkId=$forkId, earliest=$earliestBlock, latest=$latestBlock, " +
          s"latestHash=${latestBlockHash.take(4).toHex}...)"

    object Status70:
      implicit class Status70Enc(val underlyingMsg: Status70)
          extends MessageSerializableImplicit[Status70](underlyingMsg)
          with RLPSerializable:
        override def code: Int = Codes.StatusCode
        override def toRLPEncodable: RLPEncodeable =
          import msg.*
          RLPList(
            RLPValue(ByteUtils.bigIntToUnsignedByteArray(protocolVersion)),
            RLPValue(ByteUtils.bigIntToUnsignedByteArray(networkId)),
            RLPValue(genesisHash.toArray[Byte]),
            forkId.toRLPEncodable,
            RLPValue(ByteUtils.bigIntToUnsignedByteArray(earliestBlock)),
            RLPValue(ByteUtils.bigIntToUnsignedByteArray(latestBlock)),
            RLPValue(latestBlockHash.toArray[Byte])
          )

      extension (bytes: Array[Byte])
        // Two-arm decode: canonical match + catch-all stub. Mirrors Status69Dec.
        def toStatus70: Status70 = rawDecode(bytes) match
          // (1) Canonical 7-field EIP-7706 shape.
          case RLPList(
                RLPValue(protocolVersionBytes),
                RLPValue(networkIdBytes),
                RLPValue(genesisHashBytes),
                forkIdRlp: RLPList,
                RLPValue(earliestBlockBytes),
                RLPValue(latestBlockBytes),
                RLPValue(latestBlockHashBytes)
              ) =>
            Status70(
              ByteUtils.bytesToBigInt(protocolVersionBytes).toInt,
              ByteUtils.bytesToBigInt(networkIdBytes).toLong,
              ByteString(genesisHashBytes),
              decode[ForkId](forkIdRlp),
              ByteUtils.bytesToBigInt(earliestBlockBytes),
              ByteUtils.bytesToBigInt(latestBlockBytes),
              ByteString(latestBlockHashBytes)
            )
          // (2) Anything else: stub for clean UselessPeer rejection. See Status69Dec for observed variants.
          case RLPList(RLPValue(protocolVersionBytes), RLPValue(networkIdBytes), _*) =>
            Status70(
              ByteUtils.bytesToBigInt(protocolVersionBytes).toInt,
              ByteUtils.bytesToBigInt(networkIdBytes).toLong,
              genesisHash = ByteString.empty,
              forkId = ForkId(0, None),
              earliestBlock = BigInt(0),
              latestBlock = BigInt(0),
              latestBlockHash = ByteString.empty
            )
          case other =>
            val fieldCount = other match
              case RLPList(items*) => items.length; case _ => -1
            throw new RuntimeException(s"Cannot decode Status70 (got $fieldCount fields): $other")

  // ── NO-SUFFIX MESSAGES (wire format unchanged ETH68-71) ──────────────────────

  object NewBlockHashes:
    case class BlockHash(hash: ByteString, number: BigInt):
      override def toString: String =
        s"BlockHash { hash: ${Hex.toHexString(hash.toArray[Byte])} number: $number }"

    object BlockHash:
      extension (blockHash: BlockHash)
        def toRLPEncodable: RLPEncodeable =
          RLPList(RLPValue(blockHash.hash.toArray[Byte]), blockHash.number)
      extension (rlpEncodeable: RLPEncodeable)
        def toBlockHash: BlockHash = rlpEncodeable match
          case RLPList(RLPValue(hashBytes), RLPValue(numberBytes)) =>
            BlockHash(ByteString(hashBytes), ByteUtils.bytesToBigInt(numberBytes))
          case _ => throw new RuntimeException("Cannot decode BlockHash")

    case class NewBlockHashes(hashes: Seq[BlockHash]) extends Message:
      override def code: Int = Codes.NewBlockHashesCode
      override def toShortString: String = toString
      override def toString: String = s"NewBlockHashes { hashes: $hashes }"

    object NewBlockHashes:
      import BlockHash.*
      implicit class NewBlockHashesEnc(val underlyingMsg: NewBlockHashes)
          extends MessageSerializableImplicit[NewBlockHashes](underlyingMsg)
          with RLPSerializable:
        override def code: Int = Codes.NewBlockHashesCode
        override def toRLPEncodable: RLPEncodeable =
          RLPList(msg.hashes.map(_.toRLPEncodable)*)

      extension (bytes: Array[Byte])
        def toNewBlockHashes: NewBlockHashes = rawDecode(bytes) match
          case rlpList: RLPList => NewBlockHashes(rlpList.items.map(_.toBlockHash))
          case _                => throw new RuntimeException("Cannot decode NewBlockHashes")

  // ── SIGNED TRANSACTIONS ───────────────────────────────────────────────────────
  // Source: ETHPackets.SignedTransactions (full copy, standalone)

  object SignedTransactions:

    /** Legacy EIP-4844 blob-tx network wrapper: `[tx_payload, blobs, commitments, proofs]` — one KZG proof per blob. */
    private[messages] val BlobTxWrapperSizeEip4844: Int = 4

    /** EIP-7594 (PeerDAS, Osaka) blob-tx network wrapper: `[tx_payload, wrapper_version, blobs, commitments,
      * cell_proofs]` — an explicit version byte plus CELLS_PER_EXT_BLOB (128) cell proofs per blob instead of one proof
      * per blob.
      */
    private[messages] val BlobTxWrapperSizeEip7594: Int = 5

    /** The only wrapper version EIP-7594 defines. */
    private[messages] val BlobTxWrapperVersionEip7594: BigInt = BigInt(1)

    private[messages] def isBlobTxNetworkWrapperSize(size: Int): Boolean =
      size == BlobTxWrapperSizeEip4844 || size == BlobTxWrapperSizeEip7594

    /** Validate the EIP-7594 wrapper version byte. Fails loudly on anything other than `0x01`: an unknown wrapper
      * version means the blob/commitment/proof layout that follows is not the one we are about to parse, and accepting
      * it would let us re-broadcast a sidecar we never actually validated.
      */
    private[messages] def validateBlobTxWrapperVersion(versionField: RLPEncodeable): Unit =
      val version = versionField match
        case RLPValue(bs) => ByteUtils.bytesToBigInt(bs)
        case other =>
          throw new RuntimeException(
            s"Blob tx network wrapper version must be a scalar, got ${other.getClass.getSimpleName}"
          )
      if version != BlobTxWrapperVersionEip7594 then
        throw new RuntimeException(
          s"Unsupported blob tx network wrapper version $version (only $BlobTxWrapperVersionEip7594 is defined by EIP-7594)"
        )

    implicit class SignedTransactionEnc(val signedTx: SignedTransaction) extends RLPSerializable:
      override def toRLPEncodable: RLPEncodeable =
        val receivingAddressBytes = signedTx.tx.receivingAddress.map(_.toArray).getOrElse(Array.empty[Byte])
        signedTx.tx match
          case TransactionWithDynamicFee(
                chainId,
                nonce,
                maxPriorityFeePerGas,
                maxFeePerGas,
                gasLimit,
                _,
                value,
                payload,
                accessList
              ) =>
            PrefixedRLPEncodable(
              Transaction.Type02,
              RLPList(
                RLPValue(ByteUtils.bigIntToUnsignedByteArray(chainId)),
                RLPValue(ByteUtils.bigIntToUnsignedByteArray(nonce)),
                RLPValue(ByteUtils.bigIntToUnsignedByteArray(maxPriorityFeePerGas)),
                RLPValue(ByteUtils.bigIntToUnsignedByteArray(maxFeePerGas)),
                RLPValue(ByteUtils.bigIntToUnsignedByteArray(gasLimit.value)),
                receivingAddressBytes,
                RLPValue(ByteUtils.bigIntToUnsignedByteArray(value)),
                RLPValue(payload.toArray),
                toRlpList(accessList),
                RLPValue(ByteUtils.bigIntToUnsignedByteArray(signedTx.signature.v)),
                RLPValue(ByteUtils.bigIntToUnsignedByteArray(signedTx.signature.r)),
                RLPValue(ByteUtils.bigIntToUnsignedByteArray(signedTx.signature.s))
              )
            )
          case TransactionWithAccessList(chainId, nonce, gasPrice, gasLimit, _, value, payload, accessList) =>
            PrefixedRLPEncodable(
              Transaction.Type01,
              RLPList(
                RLPValue(ByteUtils.bigIntToUnsignedByteArray(chainId)),
                RLPValue(ByteUtils.bigIntToUnsignedByteArray(nonce)),
                RLPValue(ByteUtils.bigIntToUnsignedByteArray(gasPrice.value)),
                RLPValue(ByteUtils.bigIntToUnsignedByteArray(gasLimit.value)),
                receivingAddressBytes,
                RLPValue(ByteUtils.bigIntToUnsignedByteArray(value)),
                RLPValue(payload.toArray),
                toRlpList(accessList),
                RLPValue(ByteUtils.bigIntToUnsignedByteArray(signedTx.signature.v)),
                RLPValue(ByteUtils.bigIntToUnsignedByteArray(signedTx.signature.r)),
                RLPValue(ByteUtils.bigIntToUnsignedByteArray(signedTx.signature.s))
              )
            )
          case BlobTransaction(
                chainId,
                nonce,
                maxPriorityFeePerGas,
                maxFeePerGas,
                gasLimit,
                _,
                value,
                payload,
                accessList,
                maxFeePerBlobGas,
                blobVersionedHashes
              ) =>
            PrefixedRLPEncodable(
              Transaction.Type03,
              RLPList(
                RLPValue(ByteUtils.bigIntToUnsignedByteArray(chainId)),
                RLPValue(ByteUtils.bigIntToUnsignedByteArray(nonce)),
                RLPValue(ByteUtils.bigIntToUnsignedByteArray(maxPriorityFeePerGas)),
                RLPValue(ByteUtils.bigIntToUnsignedByteArray(maxFeePerGas)),
                RLPValue(ByteUtils.bigIntToUnsignedByteArray(gasLimit.value)),
                receivingAddressBytes,
                RLPValue(ByteUtils.bigIntToUnsignedByteArray(value)),
                RLPValue(payload.toArray),
                toRlpList(accessList),
                RLPValue(ByteUtils.bigIntToUnsignedByteArray(maxFeePerBlobGas)),
                RLPList(blobVersionedHashes.map(h => RLPValue(h.value.toArray))*),
                RLPValue(ByteUtils.bigIntToUnsignedByteArray(signedTx.signature.v)),
                RLPValue(ByteUtils.bigIntToUnsignedByteArray(signedTx.signature.r)),
                RLPValue(ByteUtils.bigIntToUnsignedByteArray(signedTx.signature.s))
              )
            )
          case SetCodeTransaction(
                chainId,
                nonce,
                maxPriorityFeePerGas,
                maxFeePerGas,
                gasLimit,
                _,
                value,
                payload,
                accessList,
                authorizationList
              ) =>
            PrefixedRLPEncodable(
              Transaction.Type04,
              RLPList(
                RLPValue(ByteUtils.bigIntToUnsignedByteArray(chainId)),
                RLPValue(ByteUtils.bigIntToUnsignedByteArray(nonce)),
                RLPValue(ByteUtils.bigIntToUnsignedByteArray(maxPriorityFeePerGas)),
                RLPValue(ByteUtils.bigIntToUnsignedByteArray(maxFeePerGas)),
                RLPValue(ByteUtils.bigIntToUnsignedByteArray(gasLimit.value)),
                receivingAddressBytes,
                RLPValue(ByteUtils.bigIntToUnsignedByteArray(value)),
                RLPValue(payload.toArray),
                toRlpList(accessList),
                toRlpList(authorizationList),
                RLPValue(ByteUtils.bigIntToUnsignedByteArray(signedTx.signature.v)),
                RLPValue(ByteUtils.bigIntToUnsignedByteArray(signedTx.signature.r)),
                RLPValue(ByteUtils.bigIntToUnsignedByteArray(signedTx.signature.s))
              )
            )
          case LegacyTransaction(nonce, gasPrice, gasLimit, _, value, payload) =>
            RLPList(
              RLPValue(ByteUtils.bigIntToUnsignedByteArray(nonce)),
              RLPValue(ByteUtils.bigIntToUnsignedByteArray(gasPrice.value)),
              RLPValue(ByteUtils.bigIntToUnsignedByteArray(gasLimit.value)),
              receivingAddressBytes,
              RLPValue(ByteUtils.bigIntToUnsignedByteArray(value)),
              RLPValue(payload.toArray),
              RLPValue(ByteUtils.bigIntToUnsignedByteArray(signedTx.signature.v)),
              RLPValue(ByteUtils.bigIntToUnsignedByteArray(signedTx.signature.r)),
              RLPValue(ByteUtils.bigIntToUnsignedByteArray(signedTx.signature.s))
            )

    // scalastyle:off method.length
    extension (rlpEncodeable: RLPEncodeable)
      def toSignedTransaction: SignedTransaction = rlpEncodeable match
        case PrefixedRLPEncodable(
              Transaction.Type04,
              RLPList(
                RLPValue(chainIdBytes),
                RLPValue(nonceBytes),
                RLPValue(maxPriorityFeePerGasBytes),
                RLPValue(maxFeePerGasBytes),
                RLPValue(gasLimitBytes),
                (receivingAddress: RLPValue),
                RLPValue(valueBytes),
                RLPValue(payloadBytes),
                (accessList: RLPList),
                (authorizationList: RLPList),
                RLPValue(pointSignBytes),
                RLPValue(signatureRandomBytes),
                RLPValue(signatureBytes)
              )
            ) =>
          SignedTransaction(
            SetCodeTransaction(
              ByteUtils.bytesToBigInt(chainIdBytes),
              ByteUtils.bytesToBigInt(nonceBytes),
              ByteUtils.bytesToBigInt(maxPriorityFeePerGasBytes),
              ByteUtils.bytesToBigInt(maxFeePerGasBytes),
              GasAmount(ByteUtils.bytesToBigInt(gasLimitBytes)),
              if receivingAddress.bytes.isEmpty then None else Some(Address(receivingAddress.bytes)),
              ByteUtils.bytesToBigInt(valueBytes),
              ByteString(payloadBytes),
              fromRlpList[AccessListItem](accessList).toList,
              fromRlpList[SetCodeAuthorization](authorizationList).toList
            ),
            ECDSASignature(
              ByteUtils.bytesToBigInt(signatureRandomBytes),
              ByteUtils.bytesToBigInt(signatureBytes),
              ByteUtils.bytesToBigInt(pointSignBytes)
            )
          )
        case PrefixedRLPEncodable(
              Transaction.Type03,
              RLPList(
                RLPValue(chainIdBytes),
                RLPValue(nonceBytes),
                RLPValue(maxPriorityFeePerGasBytes),
                RLPValue(maxFeePerGasBytes),
                RLPValue(gasLimitBytes),
                (receivingAddress: RLPValue),
                RLPValue(valueBytes),
                RLPValue(payloadBytes),
                (accessList: RLPList),
                RLPValue(maxFeePerBlobGasBytes),
                (blobVersionedHashes: RLPList),
                RLPValue(pointSignBytes),
                RLPValue(signatureRandomBytes),
                RLPValue(signatureBytes)
              )
            ) =>
          SignedTransaction(
            BlobTransaction(
              ByteUtils.bytesToBigInt(chainIdBytes),
              ByteUtils.bytesToBigInt(nonceBytes),
              ByteUtils.bytesToBigInt(maxPriorityFeePerGasBytes),
              ByteUtils.bytesToBigInt(maxFeePerGasBytes),
              GasAmount(ByteUtils.bytesToBigInt(gasLimitBytes)),
              if receivingAddress.bytes.isEmpty then None else Some(Address(receivingAddress.bytes)),
              ByteUtils.bytesToBigInt(valueBytes),
              ByteString(payloadBytes),
              fromRlpList[AccessListItem](accessList).toList,
              ByteUtils.bytesToBigInt(maxFeePerBlobGasBytes),
              blobVersionedHashes.items.map {
                case v: RLPValue => BlobVersionedHash(ByteString(v.bytes))
                case other => throw new RuntimeException(s"Expected RLPValue for blob versioned hash, got: $other")
              }.toList
            ),
            ECDSASignature(
              ByteUtils.bytesToBigInt(signatureRandomBytes),
              ByteUtils.bytesToBigInt(signatureBytes),
              ByteUtils.bytesToBigInt(pointSignBytes)
            )
          )
        case PrefixedRLPEncodable(
              Transaction.Type02,
              RLPList(
                RLPValue(chainIdBytes),
                RLPValue(nonceBytes),
                RLPValue(maxPriorityFeePerGasBytes),
                RLPValue(maxFeePerGasBytes),
                RLPValue(gasLimitBytes),
                (receivingAddress: RLPValue),
                RLPValue(valueBytes),
                RLPValue(payloadBytes),
                (accessList: RLPList),
                RLPValue(pointSignBytes),
                RLPValue(signatureRandomBytes),
                RLPValue(signatureBytes)
              )
            ) =>
          SignedTransaction(
            TransactionWithDynamicFee(
              ByteUtils.bytesToBigInt(chainIdBytes),
              ByteUtils.bytesToBigInt(nonceBytes),
              ByteUtils.bytesToBigInt(maxPriorityFeePerGasBytes),
              ByteUtils.bytesToBigInt(maxFeePerGasBytes),
              GasAmount(ByteUtils.bytesToBigInt(gasLimitBytes)),
              if receivingAddress.bytes.isEmpty then None else Some(Address(receivingAddress.bytes)),
              ByteUtils.bytesToBigInt(valueBytes),
              ByteString(payloadBytes),
              fromRlpList[AccessListItem](accessList).toList
            ),
            ECDSASignature(
              ByteUtils.bytesToBigInt(signatureRandomBytes),
              ByteUtils.bytesToBigInt(signatureBytes),
              ByteUtils.bytesToBigInt(pointSignBytes)
            )
          )
        case PrefixedRLPEncodable(
              Transaction.Type01,
              RLPList(
                RLPValue(chainIdBytes),
                RLPValue(nonceBytes),
                RLPValue(gasPriceBytes),
                RLPValue(gasLimitBytes),
                (receivingAddress: RLPValue),
                RLPValue(valueBytes),
                RLPValue(payloadBytes),
                (accessList: RLPList),
                RLPValue(pointSignBytes),
                RLPValue(signatureRandomBytes),
                RLPValue(signatureBytes)
              )
            ) =>
          SignedTransaction(
            TransactionWithAccessList(
              ByteUtils.bytesToBigInt(chainIdBytes),
              ByteUtils.bytesToBigInt(nonceBytes),
              GasPrice(ByteUtils.bytesToBigInt(gasPriceBytes)),
              GasAmount(ByteUtils.bytesToBigInt(gasLimitBytes)),
              if receivingAddress.bytes.isEmpty then None else Some(Address(receivingAddress.bytes)),
              ByteUtils.bytesToBigInt(valueBytes),
              ByteString(payloadBytes),
              fromRlpList[AccessListItem](accessList).toList
            ),
            ECDSASignature(
              ByteUtils.bytesToBigInt(signatureRandomBytes),
              ByteUtils.bytesToBigInt(signatureBytes),
              ByteUtils.bytesToBigInt(pointSignBytes)
            )
          )
        case RLPList(
              RLPValue(nonceBytes),
              RLPValue(gasPriceBytes),
              RLPValue(gasLimitBytes),
              (receivingAddress: RLPValue),
              RLPValue(valueBytes),
              RLPValue(payloadBytes),
              RLPValue(pointSignBytes),
              RLPValue(signatureRandomBytes),
              RLPValue(signatureBytes)
            ) =>
          SignedTransaction(
            LegacyTransaction(
              ByteUtils.bytesToBigInt(nonceBytes),
              GasPrice(ByteUtils.bytesToBigInt(gasPriceBytes)),
              GasAmount(ByteUtils.bytesToBigInt(gasLimitBytes)),
              if receivingAddress.bytes.isEmpty then None else Some(Address(receivingAddress.bytes)),
              ByteUtils.bytesToBigInt(valueBytes),
              ByteString(payloadBytes)
            ),
            ECDSASignature(
              ByteUtils.bytesToBigInt(signatureRandomBytes),
              ByteUtils.bytesToBigInt(signatureBytes),
              ByteUtils.bytesToBigInt(pointSignBytes)
            )
          )
        case _ => throw new RuntimeException("Cannot decode SignedTransaction")
    // scalastyle:on method.length

    extension (bytes: Array[Byte])
      def toSignedTransaction: SignedTransaction =
        val first = bytes(0)
        (first match
          case Transaction.Type04 => PrefixedRLPEncodable(Transaction.Type04, rawDecode(bytes.tail))
          case Transaction.Type03 =>
            rawDecode(bytes.tail) match
              case outer: RLPList if outer.items.size == 4 =>
                outer.items.head match
                  case inner: RLPList => PrefixedRLPEncodable(Transaction.Type03, inner)
                  case _              => PrefixedRLPEncodable(Transaction.Type03, outer)
              case other => PrefixedRLPEncodable(Transaction.Type03, other)
          case Transaction.Type02 => PrefixedRLPEncodable(Transaction.Type02, rawDecode(bytes.tail))
          case Transaction.Type01 => PrefixedRLPEncodable(Transaction.Type01, rawDecode(bytes.tail))
          case _                  => rawDecode(bytes)
        ).toSignedTransaction

      /** Decode a signed transaction, preserving raw bytes for network-wrapped EIP-4844 blob txs. Returns
        * (SignedTransaction, Some(rawBytes)) for Type-3 in network-wrapped form, else (stx, None).
        */
      def toSignedTransactionWithSidecar: (SignedTransaction, Option[Array[Byte]]) =
        val first = bytes(0)
        first match
          case Transaction.Type03 =>
            val decoded = rawDecode(bytes.tail)
            decoded match
              case outer: RLPList if isBlobTxNetworkWrapperSize(outer.items.size) =>
                outer.items.head match
                  case inner: RLPList =>
                    // A 5-element wrapper is EIP-7594 and carries an explicit version byte at
                    // index 1. Validate it: silently accepting an unknown wrapper version would
                    // admit a sidecar we cannot interpret (wrong proof count, wrong proof
                    // semantics) and propagate it to peers as if it were well-formed.
                    if outer.items.size == BlobTxWrapperSizeEip7594 then validateBlobTxWrapperVersion(outer.items(1))
                    val stx = PrefixedRLPEncodable(Transaction.Type03, inner).toSignedTransaction
                    (stx, Some(bytes))
                  case _ =>
                    (PrefixedRLPEncodable(Transaction.Type03, decoded).toSignedTransaction, None)
              case _ =>
                (PrefixedRLPEncodable(Transaction.Type03, decoded).toSignedTransaction, None)
          case _ => (bytes.toSignedTransaction, None)

    implicit class SignedTransactionsEnc(val underlyingMsg: SignedTransactions)
        extends MessageSerializableImplicit[SignedTransactions](underlyingMsg)
        with RLPSerializable:
      override def code: Int = Codes.SignedTransactionsCode
      override def toRLPEncodable: RLPEncodeable =
        RLPList(msg.txs.map(_.toRLPEncodable)*)

    extension (bytes: Array[Byte])
      def toSignedTransactions: SignedTransactions = rawDecode(bytes) match
        case rlpList: RLPList =>
          import TypedTransaction.*
          SignedTransactions(rlpList.items.toTypedRLPEncodables.map(_.toSignedTransaction))
        case _ => throw new RuntimeException("Cannot decode SignedTransactions")

  case class SignedTransactions(txs: Seq[SignedTransaction]) extends Message:
    override def code: Int = Codes.SignedTransactionsCode
    override def toShortString: String = s"SignedTransactions { txs: ${txs.map(_.hash.toHex)} }"

  // ── NEW BLOCK ─────────────────────────────────────────────────────────────────
  // Source: ETHPackets.NewBlock

  object NewBlock:
    implicit class NewBlockEnc(val underlyingMsg: NewBlock)
        extends MessageSerializableImplicit[NewBlock](underlyingMsg)
        with RLPSerializable:
      import SignedTransactions.*
      override def code: Int = Codes.NewBlockCode
      override def toRLPEncodable: RLPEncodeable =
        import msg.*
        RLPList(
          RLPList(
            block.header.toRLPEncodable,
            RLPList(block.body.transactionList.map(_.toRLPEncodable)*),
            RLPList(block.body.uncleNodesList.map(_.toRLPEncodable)*)
          ),
          RLPValue(ByteUtils.bigIntToUnsignedByteArray(totalDifficulty))
        )

    extension (bytes: Array[Byte])
      def toNewBlock: NewBlock = rawDecode(bytes) match
        case RLPList(
              RLPList(blockHeader, transactionList: RLPList, uncleNodesList: RLPList),
              RLPValue(totalDifficultyBytes)
            ) =>
          import SignedTransactions.*
          import TypedTransaction.*
          NewBlock(
            Block(
              blockHeader.toBlockHeader,
              BlockBody(
                transactionList.items.toTypedRLPEncodables.map(_.toSignedTransaction),
                uncleNodesList.items.map(_.toBlockHeader)
              )
            ),
            ByteUtils.bytesToBigInt(totalDifficultyBytes)
          )
        case _ => throw new RuntimeException("Cannot decode NewBlock")

  case class NewBlock(block: Block, totalDifficulty: BigInt) extends Message:
    override def code: Int = Codes.NewBlockCode
    override def toShortString: String =
      s"NewBlock { code: $code, block.header: ${block.header}, totalDifficulty: $totalDifficulty }"

  // ── GET BLOCK HEADERS ─────────────────────────────────────────────────────────
  // Source: ETH66.GetBlockHeaders

  object GetBlockHeaders:
    implicit class GetBlockHeadersEnc(val underlyingMsg: GetBlockHeaders)
        extends MessageSerializableImplicit[GetBlockHeaders](underlyingMsg)
        with RLPSerializable:
      override def code: Int = Codes.GetBlockHeadersCode
      override def toRLPEncodable: RLPEncodeable =
        import msg.*
        def num(b: BigInt): RLPValue = RLPValue(ByteUtils.bigIntToUnsignedByteArray(b))
        val reverseFlag: RLPValue =
          if reverse then RLPValue(Array[Byte](1.toByte)) else RLPValue(Array.emptyByteArray)
        val blockQuery = block match
          case Left(blockNumber) => RLPList(num(blockNumber), num(maxHeaders), num(skip), reverseFlag)
          case Right(blockHash)  => RLPList(RLPValue(blockHash.toArray[Byte]), num(maxHeaders), num(skip), reverseFlag)
        RLPList(num(requestId), blockQuery)

    extension (bytes: Array[Byte])
      def toGetBlockHeaders: GetBlockHeaders = rawDecode(bytes) match
        case RLPList(
              RLPValue(requestIdBytes),
              RLPList(block: RLPValue, RLPValue(maxHeadersBytes), RLPValue(skipBytes), RLPValue(reverseBytes))
            ) if block.bytes.length < 32 =>
          GetBlockHeaders(
            ByteUtils.bytesToBigInt(requestIdBytes),
            Left(ByteUtils.bytesToBigInt(block.bytes)),
            ByteUtils.bytesToBigInt(maxHeadersBytes),
            ByteUtils.bytesToBigInt(skipBytes),
            ByteUtils.bytesToBigInt(reverseBytes).toInt == 1
          )
        case RLPList(
              RLPValue(requestIdBytes),
              RLPList(block: RLPValue, RLPValue(maxHeadersBytes), RLPValue(skipBytes), RLPValue(reverseBytes))
            ) =>
          GetBlockHeaders(
            ByteUtils.bytesToBigInt(requestIdBytes),
            Right(ByteString(block.bytes)),
            ByteUtils.bytesToBigInt(maxHeadersBytes),
            ByteUtils.bytesToBigInt(skipBytes),
            ByteUtils.bytesToBigInt(reverseBytes).toInt == 1
          )
        case RLPList(RLPValue(blockBytes), RLPValue(maxHeadersBytes), RLPValue(skipBytes), RLPValue(reverseBytes))
            if blockBytes.length < 32 =>
          GetBlockHeaders(
            0,
            Left(ByteUtils.bytesToBigInt(blockBytes)),
            ByteUtils.bytesToBigInt(maxHeadersBytes),
            ByteUtils.bytesToBigInt(skipBytes),
            ByteUtils.bytesToBigInt(reverseBytes) == 1
          )
        case RLPList(RLPValue(blockBytes), RLPValue(maxHeadersBytes), RLPValue(skipBytes), RLPValue(reverseBytes)) =>
          GetBlockHeaders(
            0,
            Right(ByteString(blockBytes)),
            ByteUtils.bytesToBigInt(maxHeadersBytes),
            ByteUtils.bytesToBigInt(skipBytes),
            ByteUtils.bytesToBigInt(reverseBytes) == 1
          )
        case _ => throw new RuntimeException("Cannot decode GetBlockHeaders")

  case class GetBlockHeaders(
      requestId: BigInt,
      block: Either[BigInt, ByteString],
      maxHeaders: BigInt,
      skip: BigInt,
      reverse: Boolean
  ) extends Message
      with HasRequestId:
    override def code: Int = Codes.GetBlockHeadersCode
    override def toShortString: String =
      s"GetBlockHeaders { requestId: $requestId, block: ${block.fold(identity, h => Hex.toHexString(h.toArray))}, maxHeaders: $maxHeaders }"

  // ── BLOCK HEADERS ─────────────────────────────────────────────────────────────
  // Source: ETH66.BlockHeaders

  object BlockHeaders:
    implicit class BlockHeadersEnc(val underlyingMsg: BlockHeaders)
        extends MessageSerializableImplicit[BlockHeaders](underlyingMsg)
        with RLPSerializable:
      override def code: Int = Codes.BlockHeadersCode
      override def toRLPEncodable: RLPEncodeable =
        RLPList(
          RLPValue(ByteUtils.bigIntToUnsignedByteArray(msg.requestId)),
          RLPList(msg.headers.map(_.toRLPEncodable)*)
        )

    extension (bytes: Array[Byte])
      def toBlockHeaders: BlockHeaders = rawDecode(bytes) match
        case rlpList: RLPList if rlpList.items.size == 2 =>
          rlpList.items match
            case Seq(RLPValue(requestIdBytes), headersList: RLPList) =>
              BlockHeaders(ByteUtils.bytesToBigInt(requestIdBytes), headersList.items.map(_.toBlockHeader))
            case _ => BlockHeaders(0, rlpList.items.map(_.toBlockHeader))
        case rlpList: RLPList => BlockHeaders(0, rlpList.items.map(_.toBlockHeader))
        case _                => throw new RuntimeException("Cannot decode BlockHeaders")

  case class BlockHeaders(requestId: BigInt, headers: Seq[BlockHeader]) extends Message with HasRequestId:
    val code: Int = Codes.BlockHeadersCode
    override def toShortString: String =
      s"BlockHeaders { requestId: $requestId, count: ${headers.size} }"

  // ── GET BLOCK BODIES ──────────────────────────────────────────────────────────
  // Source: ETH66.GetBlockBodies

  object GetBlockBodies:
    implicit class GetBlockBodiesEnc(val underlyingMsg: GetBlockBodies)
        extends MessageSerializableImplicit[GetBlockBodies](underlyingMsg)
        with RLPSerializable:
      override def code: Int = Codes.GetBlockBodiesCode
      override def toRLPEncodable: RLPEncodeable =
        RLPList(RLPValue(ByteUtils.bigIntToUnsignedByteArray(msg.requestId)), toRlpList(msg.hashes))

    extension (bytes: Array[Byte])
      def toGetBlockBodies: GetBlockBodies = rawDecode(bytes) match
        case rlpList: RLPList if rlpList.items.size == 2 =>
          rlpList.items match
            case Seq(RLPValue(requestIdBytes), hashesList: RLPList) =>
              GetBlockBodies(ByteUtils.bytesToBigInt(requestIdBytes), fromRlpList[ByteString](hashesList))
            case _ => GetBlockBodies(0, fromRlpList[ByteString](rlpList))
        case rlpList: RLPList => GetBlockBodies(0, fromRlpList[ByteString](rlpList))
        case _                => throw new RuntimeException("Cannot decode GetBlockBodies")

  case class GetBlockBodies(requestId: BigInt, hashes: Seq[ByteString]) extends Message with HasRequestId:
    override def code: Int = Codes.GetBlockBodiesCode
    override def toShortString: String = s"GetBlockBodies { requestId: $requestId, count: ${hashes.size} }"

  // ── BLOCK BODIES ──────────────────────────────────────────────────────────────
  // Source: ETH66.BlockBodies

  object BlockBodies:
    implicit class BlockBodiesEnc(val underlyingMsg: BlockBodies)
        extends MessageSerializableImplicit[BlockBodies](underlyingMsg)
        with RLPSerializable:
      override def code: Int = Codes.BlockBodiesCode
      override def toRLPEncodable: RLPEncodeable =
        RLPList(
          RLPValue(ByteUtils.bigIntToUnsignedByteArray(msg.requestId)),
          RLPList(msg.bodies.map(_.toRLPEncodable)*)
        )

    extension (bytes: Array[Byte])
      def toBlockBodies: BlockBodies = rawDecode(bytes) match
        case rlpList: RLPList if rlpList.items.size == 2 =>
          rlpList.items match
            case Seq(RLPValue(requestIdBytes), bodiesList: RLPList) =>
              BlockBodies(ByteUtils.bytesToBigInt(requestIdBytes), bodiesList.items.map(_.toBlockBody))
            case _ => BlockBodies(0, rlpList.items.map(_.toBlockBody))
        case rlpList: RLPList => BlockBodies(0, rlpList.items.map(_.toBlockBody))
        case _                => throw new RuntimeException("Cannot decode BlockBodies")

  case class BlockBodies(requestId: BigInt, bodies: Seq[BlockBody]) extends Message with HasRequestId:
    val code: Int = Codes.BlockBodiesCode
    override def toShortString: String =
      s"BlockBodies { requestId: $requestId, count: ${bodies.size} }"

  // ── NEW POOLED TRANSACTION HASHES ─────────────────────────────────────────────
  // Source: ETH67.NewPooledTransactionHashes (includes ETH65 backward-compat decode)

  object NewPooledTransactionHashes:
    implicit class NewPooledTransactionHashesEnc(val underlyingMsg: NewPooledTransactionHashes)
        extends MessageSerializableImplicit[NewPooledTransactionHashes](underlyingMsg)
        with RLPSerializable:
      override def code: Int = Codes.NewPooledTransactionHashesCode
      override def toRLPEncodable: RLPEncodeable =
        import msg.*
        RLPList(RLPValue(types.toArray), toRlpList(sizes), toRlpList(hashes))

    extension (bytes: Array[Byte])
      def toNewPooledTransactionHashes: NewPooledTransactionHashes =
        rawDecode(bytes) match
          case RLPList(RLPValue(typesBytes), sizesList: RLPList, hashesList: RLPList) =>
            NewPooledTransactionHashes(
              typesBytes.toSeq,
              fromRlpList[BigInt](sizesList),
              fromRlpList[ByteString](hashesList)
            )
          case rlpList: RLPList =>
            val hashes = fromRlpList[ByteString](rlpList)
            NewPooledTransactionHashes(Seq.fill(hashes.size)(0.toByte), Seq.fill(hashes.size)(BigInt(0)), hashes)
          case _ => throw new RuntimeException("Cannot decode NewPooledTransactionHashes")

  case class NewPooledTransactionHashes(types: Seq[Byte], sizes: Seq[BigInt], hashes: Seq[ByteString]) extends Message:
    require(types.size == sizes.size && sizes.size == hashes.size, "types, sizes, and hashes must have same length")
    override def code: Int = Codes.NewPooledTransactionHashesCode
    override def toShortString: String = s"NewPooledTransactionHashes { count: ${hashes.size} }"

  // ── GET POOLED TRANSACTIONS ───────────────────────────────────────────────────
  // Source: ETH66.GetPooledTransactions

  object GetPooledTransactions:
    implicit class GetPooledTransactionsEnc(val underlyingMsg: GetPooledTransactions)
        extends MessageSerializableImplicit[GetPooledTransactions](underlyingMsg)
        with RLPSerializable:
      override def code: Int = Codes.GetPooledTransactionsCode
      override def toRLPEncodable: RLPEncodeable =
        RLPList(RLPValue(ByteUtils.bigIntToUnsignedByteArray(msg.requestId)), toRlpList(msg.txHashes))

    extension (bytes: Array[Byte])
      def toGetPooledTransactions: GetPooledTransactions = rawDecode(bytes) match
        case RLPList(RLPValue(requestIdBytes), rlpList: RLPList) =>
          GetPooledTransactions(ByteUtils.bytesToBigInt(requestIdBytes), fromRlpList[ByteString](rlpList))
        case _ => throw new RuntimeException("Cannot decode GetPooledTransactions")

  case class GetPooledTransactions(requestId: BigInt, txHashes: Seq[ByteString]) extends Message with HasRequestId:
    override def code: Int = Codes.GetPooledTransactionsCode
    override def toShortString: String = s"GetPooledTransactions { requestId: $requestId, count: ${txHashes.size} }"

  // ── POOLED TRANSACTIONS ───────────────────────────────────────────────────────
  // Source: ETH66.PooledTransactions

  object PooledTransactions:
    implicit class PooledTransactionsEnc(val underlyingMsg: PooledTransactions)
        extends MessageSerializableImplicit[PooledTransactions](underlyingMsg)
        with RLPSerializable:
      import SignedTransactions.*
      override def code: Int = Codes.PooledTransactionsCode
      override def toRLPEncodable: RLPEncodeable =
        val txItems: Seq[RLPEncodeable] = msg.txs.map { stx =>
          msg.blobTxRawBytes.get(stx.hash.value) match
            case Some(rawBytes) => PrefixedRLPEncodable(rawBytes(0), rawDecode(rawBytes.toArray.drop(1)))
            case None           => stx.toRLPEncodable
        }
        RLPList(RLPValue(ByteUtils.bigIntToUnsignedByteArray(msg.requestId)), RLPList(txItems*))

    extension (bytes: Array[Byte])
      def toPooledTransactions: PooledTransactions = rawDecode(bytes) match
        case RLPList(RLPValue(requestIdBytes), rlpList: RLPList) =>
          import SignedTransactions.*
          import TypedTransaction.*
          val typedItems = rlpList.items.toTypedRLPEncodables
          typedItems.foreach {
            case PrefixedRLPEncodable(Transaction.Type03, inner: RLPList) =>
              val isNetworkWrapped = inner.items.size == 4 && (inner.items.head match
                case _: RLPList => true
                case _          => false
              )
              if !isNetworkWrapped then
                throw new RuntimeException("Blob tx in PooledTransactions missing sidecar (network wrapping required)")
            case _ =>
          }
          val blobTxRawBytesBuilder = Map.newBuilder[ByteString, ByteString]
          val unwrappedItems = typedItems.map {
            case prefixed @ PrefixedRLPEncodable(Transaction.Type03, inner: RLPList)
                if inner.items.size == 4 && (inner.items.head match
                  case _: RLPList => true
                  case _          => false
                ) =>
              val rawBytes = com.chipprbots.ethereum.rlp.encode(prefixed)
              val unwrapped = PrefixedRLPEncodable(Transaction.Type03, inner.items.head)
              val stx = unwrapped.toSignedTransaction
              blobTxRawBytesBuilder += (stx.hash.value -> ByteString(rawBytes))
              unwrapped
            case other => other
          }
          val originalSizes = rlpList.items.map {
            case RLPValue(v) => v.length
            case rl: RLPList => com.chipprbots.ethereum.rlp.encode(rl).length
            case _           => 0
          }
          PooledTransactions(
            ByteUtils.bytesToBigInt(requestIdBytes),
            unwrappedItems.map(_.toSignedTransaction),
            originalSizes,
            blobTxRawBytesBuilder.result()
          )
        case _ => throw new RuntimeException("Cannot decode PooledTransactions")

  case class PooledTransactions(
      requestId: BigInt,
      txs: Seq[SignedTransaction],
      originalSizes: Seq[Int] = Seq.empty,
      blobTxRawBytes: Map[ByteString, ByteString] = Map.empty
  ) extends Message
      with HasRequestId:
    override def code: Int = Codes.PooledTransactionsCode
    override def toShortString: String = s"PooledTransactions { requestId: $requestId, count: ${txs.size} }"

  // ── GET RECEIPTS ──────────────────────────────────────────────────────────────
  // Source: ETH66.GetReceipts

  object GetReceipts:
    implicit class GetReceiptsEnc(val underlyingMsg: GetReceipts)
        extends MessageSerializableImplicit[GetReceipts](underlyingMsg)
        with RLPSerializable:
      override def code: Int = Codes.GetReceiptsCode
      override def toRLPEncodable: RLPEncodeable =
        RLPList(RLPValue(ByteUtils.bigIntToUnsignedByteArray(msg.requestId)), toRlpList(msg.blockHashes))

    extension (bytes: Array[Byte])
      def toGetReceipts: GetReceipts = rawDecode(bytes) match
        case rlpList: RLPList if rlpList.items.size == 2 =>
          rlpList.items match
            case Seq(RLPValue(requestIdBytes), hashesList: RLPList) =>
              GetReceipts(ByteUtils.bytesToBigInt(requestIdBytes), fromRlpList[ByteString](hashesList))
            case _ => GetReceipts(0, fromRlpList[ByteString](rlpList))
        case rlpList: RLPList => GetReceipts(0, fromRlpList[ByteString](rlpList))
        case _                => throw new RuntimeException("Cannot decode GetReceipts")

  case class GetReceipts(requestId: BigInt, blockHashes: Seq[ByteString]) extends Message with HasRequestId:
    override def code: Int = Codes.GetReceiptsCode
    override def toShortString: String = s"GetReceipts { requestId: $requestId, count: ${blockHashes.size} }"

  /** ETH69 GetReceipts — same wire format as GetReceipts, distinct type so BlockchainHostActor can serve bloom-absent
    * Receipts69 in response (EIP-7642). ETH69MessageDecoder decodes GetReceiptsCode to this type.
    */
  object GetReceipts69:
    implicit class GetReceipts69Enc(val underlyingMsg: GetReceipts69)
        extends MessageSerializableImplicit[GetReceipts69](underlyingMsg)
        with RLPSerializable:
      override def code: Int = Codes.GetReceiptsCode
      override def toRLPEncodable: RLPEncodeable =
        RLPList(RLPValue(ByteUtils.bigIntToUnsignedByteArray(msg.requestId)), toRlpList(msg.blockHashes))

    extension (bytes: Array[Byte])
      def toGetReceipts69: GetReceipts69 = rawDecode(bytes) match
        case rlpList: RLPList if rlpList.items.size == 2 =>
          rlpList.items match
            case Seq(RLPValue(requestIdBytes), hashesList: RLPList) =>
              GetReceipts69(ByteUtils.bytesToBigInt(requestIdBytes), fromRlpList[ByteString](hashesList))
            case _ => GetReceipts69(0, fromRlpList[ByteString](rlpList))
        case rlpList: RLPList => GetReceipts69(0, fromRlpList[ByteString](rlpList))
        case _                => throw new RuntimeException("Cannot decode GetReceipts69")

  case class GetReceipts69(requestId: BigInt, blockHashes: Seq[ByteString]) extends Message with HasRequestId:
    override def code: Int = Codes.GetReceiptsCode
    override def toShortString: String = s"GetReceipts69 { requestId: $requestId, count: ${blockHashes.size} }"

  // ── RECEIPT ENCODING IMPLICITS ────────────────────────────────────────────────
  // Inline log + receipt encoders so ETHPackets is standalone (no ETH63 import needed).
  // Used by BlockchainHostActor when serving receipts to ETH68 and ETH69 peers.

  /** RLP encoding for a single TxLogEntry. Same as ETH63.TxLogEntryImplicits.TxLogEntryEnc. */
  implicit class TxLogEntryRLPEnc(logEntry: TxLogEntry) extends RLPSerializable:
    override def toRLPEncodable: RLPEncodeable =
      RLPList(
        RLPValue(logEntry.loggerAddress.bytes.toArray[Byte]),
        RLPList(logEntry.logTopics.map(t => RLPValue(t.toArray[Byte]))*),
        RLPValue(logEntry.data.toArray[Byte])
      )

  private def receiptStateHash(r: Receipt): RLPEncodeable = r.postTransactionStateHash match
    case HashOutcome(hash) => RLPValue(hash.toArray[Byte])
    case SuccessOutcome    => 1.toByte
    case _                 => 0.toByte

  private def wrapTypedReceipt(r: Receipt, legacyRLP: RLPList): RLPEncodeable = r match
    case _: LegacyReceipt      => legacyRLP
    case _: Type01Receipt      => PrefixedRLPEncodable(Transaction.Type01, legacyRLP)
    case _: Type02Receipt      => PrefixedRLPEncodable(Transaction.Type02, legacyRLP)
    case _: Type03Receipt      => PrefixedRLPEncodable(Transaction.Type03, legacyRLP)
    case _: Type04Receipt      => PrefixedRLPEncodable(Transaction.Type04, legacyRLP)
    case _: TypedLegacyReceipt => legacyRLP

  /** Encode a Receipt with bloom (ETH68 serving). Same as ETH63.ReceiptImplicits.ReceiptEnc. */
  implicit class ReceiptBloomEnc(r: Receipt) extends RLPSerializable:
    override def toRLPEncodable: RLPEncodeable =
      wrapTypedReceipt(
        r,
        RLPList(
          receiptStateHash(r),
          RLPValue(ByteUtils.bigIntToUnsignedByteArray(r.cumulativeGasUsed)),
          RLPValue(r.logsBloomFilter.toArray),
          RLPList(r.logs.map(_.toRLPEncodable)*)
        )
      )

  /** Encode a Receipt WITHOUT bloom (ETH69 serving, EIP-7642). Wire: [stateHash, gasUsed, [logs]] — no logsBloomFilter
    * field.
    */
  implicit class ReceiptBloomFreeEnc(r: Receipt) extends RLPSerializable:
    override def toRLPEncodable: RLPEncodeable =
      wrapTypedReceipt(
        r,
        RLPList(
          receiptStateHash(r),
          RLPValue(ByteUtils.bigIntToUnsignedByteArray(r.cumulativeGasUsed)),
          RLPList(r.logs.map(_.toRLPEncodable)*)
        )
      )

  // ── RECEIPTS — version-suffixed: EIP-7642 removes bloom in ETH69 ─────────────
  //
  // Reference: Reth receipts.rs: Receipts / Receipts69
  //            Nethermind: ReceiptsMessage.cs (V63+) / ReceiptsMessage69.cs (V69/)
  //            Erigon handlers.go: if isEth69 { EncodeRLP69(buf) }
  //
  // Wire format difference:
  //   ETH68: [requestId, [[stateHash, gasUsed, logsBloom, [logs]], ...]]
  //   ETH69: [requestId, [[stateHash, gasUsed, [logs]], ...]]  ← no bloom (EIP-7642)

  /** ETH68 receipts: bloom-inclusive. Source: ETH66.Receipts + ETH63.ReceiptEnc. */
  object Receipts68:
    implicit class Receipts68Enc(val underlyingMsg: Receipts68)
        extends MessageSerializableImplicit[Receipts68](underlyingMsg)
        with RLPSerializable:
      override def code: Int = Codes.ReceiptsCode
      override def toRLPEncodable: RLPEncodeable =
        RLPList(RLPValue(ByteUtils.bigIntToUnsignedByteArray(msg.requestId)), msg.receiptsForBlocks)

    extension (bytes: Array[Byte])
      def toReceipts68: Receipts68 = rawDecode(bytes) match
        case rlpList: RLPList if rlpList.items.size == 2 =>
          rlpList.items match
            case Seq(RLPValue(requestIdBytes), receiptsList: RLPList) =>
              Receipts68(ByteUtils.bytesToBigInt(requestIdBytes), receiptsList)
            case _ => Receipts68(0, rlpList)
        case rlpList: RLPList => Receipts68(0, rlpList)
        case _                => throw new RuntimeException("Cannot decode Receipts68")

  case class Receipts68(requestId: BigInt, receiptsForBlocks: RLPList) extends Message with HasRequestId:
    override def code: Int = Codes.ReceiptsCode
    override def toShortString: String =
      s"Receipts68 { requestId: $requestId, blocks: ${receiptsForBlocks.items.size} }"

  /** ETH69 receipts: bloom-ABSENT (EIP-7642). NEW — no equivalent in current Fukuii.
    *
    * ETH69MessageDecoder previously used ETH66.Receipts (bloom-inclusive) — protocol violation. Using Receipts69 here
    * is the fix.
    */
  object Receipts69:
    implicit class Receipts69Enc(val underlyingMsg: Receipts69)
        extends MessageSerializableImplicit[Receipts69](underlyingMsg)
        with RLPSerializable:
      override def code: Int = Codes.ReceiptsCode
      override def toRLPEncodable: RLPEncodeable =
        RLPList(RLPValue(ByteUtils.bigIntToUnsignedByteArray(msg.requestId)), msg.receiptsForBlocks)

    extension (bytes: Array[Byte])
      def toReceipts69: Receipts69 = rawDecode(bytes) match
        case rlpList: RLPList if rlpList.items.size == 2 =>
          rlpList.items match
            case Seq(RLPValue(requestIdBytes), receiptsList: RLPList) =>
              Receipts69(ByteUtils.bytesToBigInt(requestIdBytes), receiptsList)
            case _ => Receipts69(0, rlpList)
        case rlpList: RLPList => Receipts69(0, rlpList)
        case _                => throw new RuntimeException("Cannot decode Receipts69")

  case class Receipts69(requestId: BigInt, receiptsForBlocks: RLPList) extends Message with HasRequestId:
    override def code: Int = Codes.ReceiptsCode
    override def toShortString: String =
      s"Receipts69 { requestId: $requestId, blocks: ${receiptsForBlocks.items.size} }"

  // ── ETH70 RECEIPTS — partial delivery (EIP-7706) ─────────────────────────────
  //
  // ETH70 adds two fields enabling chunked receipt delivery for large blocks:
  //   Server: can truncate at the 2 MiB soft limit and set LastBlockIncomplete=true
  //   Client: can retry with FirstBlockReceiptIndex to skip already-received receipts
  //
  // Wire format:
  //   GetReceipts70: [requestId, firstBlockReceiptIndex, [blockHashes]]
  //   Receipts70:    [requestId, lastBlockIncomplete, [[receipts...], ...]]
  //
  // Reference: go-ethereum eth/protocols/eth/protocol.go GetReceiptsPacket70 / ReceiptsPacket70

  /** ETH70 GetReceipts — adds firstBlockReceiptIndex for partial receipt resume. */
  object GetReceipts70:
    implicit class GetReceipts70Enc(val underlyingMsg: GetReceipts70)
        extends MessageSerializableImplicit[GetReceipts70](underlyingMsg)
        with RLPSerializable:
      override def code: Int = Codes.GetReceiptsCode
      override def toRLPEncodable: RLPEncodeable =
        RLPList(
          RLPValue(ByteUtils.bigIntToUnsignedByteArray(msg.requestId)),
          RLPValue(ByteUtils.bigIntToUnsignedByteArray(msg.firstBlockReceiptIndex)),
          toRlpList(msg.blockHashes)
        )

    extension (bytes: Array[Byte])
      def toGetReceipts70: GetReceipts70 = rawDecode(bytes) match
        case RLPList(RLPValue(requestIdBytes), RLPValue(firstBlockBytes), hashesList: RLPList) =>
          GetReceipts70(
            ByteUtils.bytesToBigInt(requestIdBytes),
            ByteUtils.bytesToBigInt(firstBlockBytes).toLong,
            fromRlpList[ByteString](hashesList)
          )
        case _ => throw new RuntimeException("Cannot decode GetReceipts70")

  case class GetReceipts70(requestId: BigInt, firstBlockReceiptIndex: Long, blockHashes: Seq[ByteString])
      extends Message
      with HasRequestId:
    override def code: Int = Codes.GetReceiptsCode
    override def toShortString: String =
      s"GetReceipts70 { requestId: $requestId, firstBlockReceiptIndex: $firstBlockReceiptIndex, count: ${blockHashes.size} }"

  /** ETH70 Receipts — adds lastBlockIncomplete flag for partial receipt responses. */
  object Receipts70:
    implicit class Receipts70Enc(val underlyingMsg: Receipts70)
        extends MessageSerializableImplicit[Receipts70](underlyingMsg)
        with RLPSerializable:
      override def code: Int = Codes.ReceiptsCode
      override def toRLPEncodable: RLPEncodeable =
        RLPList(
          RLPValue(ByteUtils.bigIntToUnsignedByteArray(msg.requestId)),
          if msg.lastBlockIncomplete then RLPValue(Array[Byte](1)) else RLPValue(Array.emptyByteArray),
          msg.receiptsForBlocks
        )

    extension (bytes: Array[Byte])
      def toReceipts70: Receipts70 = rawDecode(bytes) match
        case RLPList(RLPValue(requestIdBytes), RLPValue(lastBlockBytes), receiptsList: RLPList) =>
          Receipts70(
            ByteUtils.bytesToBigInt(requestIdBytes),
            lastBlockBytes.nonEmpty && lastBlockBytes(0) != 0,
            receiptsList
          )
        case _ => throw new RuntimeException("Cannot decode Receipts70")

  case class Receipts70(requestId: BigInt, lastBlockIncomplete: Boolean, receiptsForBlocks: RLPList)
      extends Message
      with HasRequestId:
    override def code: Int = Codes.ReceiptsCode
    override def toShortString: String =
      s"Receipts70 { requestId: $requestId, lastBlockIncomplete: $lastBlockIncomplete, blocks: ${receiptsForBlocks.items.size} }"

  // ── ETH69+ NEW MESSAGES ───────────────────────────────────────────────────────
  // Source: ETH69.BlockRangeUpdate

  object BlockRangeUpdate:
    implicit class BlockRangeUpdateEnc(val underlyingMsg: BlockRangeUpdate)
        extends MessageSerializableImplicit[BlockRangeUpdate](underlyingMsg)
        with RLPSerializable:
      override def code: Int = Codes.BlockRangeUpdateCode
      override def toRLPEncodable: RLPEncodeable = RLPList(
        RLPValue(ByteUtils.bigIntToUnsignedByteArray(msg.earliestBlock)),
        RLPValue(ByteUtils.bigIntToUnsignedByteArray(msg.latestBlock)),
        RLPValue(msg.latestBlockHash.toArray[Byte])
      )

    extension (bytes: Array[Byte])
      def toBlockRangeUpdate: BlockRangeUpdate = rawDecode(bytes) match
        case RLPList(
              RLPValue(earliestBlockBytes),
              RLPValue(latestBlockBytes),
              RLPValue(latestBlockHashBytes)
            ) =>
          BlockRangeUpdate(
            ByteUtils.bytesToBigInt(earliestBlockBytes),
            ByteUtils.bytesToBigInt(latestBlockBytes),
            ByteString(latestBlockHashBytes)
          )
        case other => throw new RuntimeException(s"Cannot decode BlockRangeUpdate from: $other")

  case class BlockRangeUpdate(earliestBlock: BigInt, latestBlock: BigInt, latestBlockHash: ByteString) extends Message:
    override val code: Int = Codes.BlockRangeUpdateCode
    override def toShortString: String = s"BlockRangeUpdate(earliest=$earliestBlock, latest=$latestBlock)"

  // ── LEGACY TYPES: GetNodeData / NodeData (EIP-4938: removed in ETH68) ──────────────────
  // Retained so BlockchainHostActor can respond to legacy peers and StateNodeFetcher can
  // attempt fallback requests. ETH68MessageDecoder REJECTS these on inbound — they are
  // outbound-only on Fukuii's modern peer set (all ETH68/69 peers will reject them).

  object GetNodeData:
    implicit class GetNodeDataEnc(val underlyingMsg: GetNodeData)
        extends MessageSerializableImplicit[GetNodeData](underlyingMsg)
        with RLPSerializable:
      override def code: Int = Codes.GetNodeDataCode
      override def toRLPEncodable: RLPEncodeable = toRlpList(msg.mptElementsHashes)

  case class GetNodeData(mptElementsHashes: Seq[ByteString]) extends Message:
    override def code: Int = Codes.GetNodeDataCode
    override def toShortString: String = s"GetNodeData{ hashes: <${mptElementsHashes.size} state tree hashes> }"

  object NodeData:
    implicit class NodeDataEnc(val underlyingMsg: NodeData)
        extends MessageSerializableImplicit[NodeData](underlyingMsg)
        with RLPSerializable:
      override def code: Int = Codes.NodeDataCode
      override def toRLPEncodable: RLPEncodeable = RLPList(msg.values.map(v => RLPValue(v.toArray[Byte]))*)

    extension (bytes: Array[Byte])
      def toNodeData: NodeData = rawDecode(bytes) match
        case rlpList: RLPList =>
          NodeData(rlpList.items.map {
            case RLPValue(bytes) => ByteString(bytes)
            case _               => throw new RuntimeException("Cannot decode NodeData item")
          })
        case _ => throw new RuntimeException("Cannot decode NodeData")

  case class NodeData(values: Seq[ByteString]) extends Message:
    override def code: Int = Codes.NodeDataCode
    override def toShortString: String = s"NodeData{ values: <${values.size} nodes> }"
