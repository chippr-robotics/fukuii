package com.chipprbots.ethereum.network.p2p.messages

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex

import com.chipprbots.ethereum.crypto.ECDSASignature
import com.chipprbots.ethereum.domain.BlockBody.BlockBodyRLPEncodableDec
import com.chipprbots.ethereum.domain.BlockHeaderImplicits._
import com.chipprbots.ethereum.domain._
import com.chipprbots.ethereum.forkid.ForkId
import com.chipprbots.ethereum.forkid.ForkId._
import com.chipprbots.ethereum.network.p2p.Message
import com.chipprbots.ethereum.network.p2p.MessageSerializableImplicit
import com.chipprbots.ethereum.rlp.RLPCodec.Ops
import com.chipprbots.ethereum.rlp.RLPImplicitConversions._
import com.chipprbots.ethereum.rlp.RLPImplicits.given
import com.chipprbots.ethereum.rlp._
import com.chipprbots.ethereum.utils.ByteStringUtils.ByteStringOps
import com.chipprbots.ethereum.utils.ByteUtils

/** ETH/68+ wire protocol packet definitions — the single canonical source.
  *
  * Analogous to: go-ethereum eth/protocols/eth/protocol.go
  *               Erigon p2p/protocols/eth/protocol.go
  *               Reth crates/net/eth-wire-types/src/message.rs
  *
  * NAMING RULE (universal consensus across all 5 reference clients):
  *   No version suffix: wire format IDENTICAL across ETH68-ETH71.
  *   Version suffix: wire format DIFFERS between supported versions.
  *
  * MESSAGE CODE TABLE (Fukuii wire codes = std + 0x10, capability framing offset):
  *   Wire   Std    Message                    Object                    Versions
  *   0x10  (0x00)  Status (ETH68)             Status68                  ETH68 only (TD)
  *   0x10  (0x00)  Status (ETH69)             Status69                  ETH69+ (no TD)
  *   0x11  (0x01)  NewBlockHashes             NewBlockHashes            ETH68-71 (unchanged)
  *   0x12  (0x02)  Transactions               SignedTransactions         ETH68-71 (unchanged)
  *   0x13  (0x03)  GetBlockHeaders            GetBlockHeaders           ETH68-71 (unchanged)
  *   0x14  (0x04)  BlockHeaders               BlockHeaders              ETH68-71 (unchanged)
  *   0x15  (0x05)  GetBlockBodies             GetBlockBodies            ETH68-71 (unchanged)
  *   0x16  (0x06)  BlockBodies                BlockBodies               ETH68-71 (unchanged)
  *   0x17  (0x07)  NewBlock                   NewBlock                  ETH68-71 (unchanged)
  *   0x18  (0x08)  NewPooledTxHashes          NewPooledTransactionHashes ETH68-71 (unchanged)
  *   0x19  (0x09)  GetPooledTransactions      GetPooledTransactions     ETH68-71 (unchanged)
  *   0x1a  (0x0a)  PooledTransactions         PooledTransactions        ETH68-71 (unchanged)
  *   0x1d  (0x0d)  GetNodeData                REJECTED (EIP-4938)       ETH68+
  *   0x1e  (0x0e)  NodeData                   REJECTED (EIP-4938)       ETH68+
  *   0x1f  (0x0f)  GetReceipts                GetReceipts               ETH68-69 (GET unchanged)
  *   0x20  (0x10)  Receipts (ETH68)           Receipts68                ETH68 (bloom present)
  *   0x20  (0x10)  Receipts (ETH69)           Receipts69                ETH69 (bloom absent, EIP-7642)
  *   0x21  (0x11)  BlockRangeUpdate           BlockRangeUpdate          ETH69+ (new)
  *
  * No imports from ETH62-67 or BaseETH6XMessages. All definitions are standalone.
  * This replaces the scattered definitions across ETH62.scala through ETH67.scala
  * and BaseETH6XMessages.scala once those files are retired.
  */
object ETHPackets {

  // Replaces ETH66.HasRequestId once ETH66 is deleted.
  trait HasRequestId { def requestId: BigInt }

  // ── RLP CODECS (copied from BaseETH6XMessages — needed for AccessListItem, SetCodeAuthorization) ──

  implicit val addressCodec: RLPCodec[Address] =
    implicitly[RLPCodec[Array[Byte]]].xmap(Address(_), _.toArray)

  implicit val accessListItemCodec: RLPCodec[AccessListItem] =
    RLPCodec.instance[AccessListItem](
      { case AccessListItem(address, storageKeys) =>
        RLPList(address, toRlpList(storageKeys.map(UInt256(_).bytes.toArray)))
      },
      {
        case r: RLPList if r.items.isEmpty => AccessListItem(null, List.empty)
        case RLPList(rlpAddress, rlpStorageKeys: RLPList) =>
          AccessListItem(rlpAddress.decodeAs[Address]("address"),
            fromRlpList[BigInt](rlpStorageKeys).toList)
      }
    )

  implicit val setCodeAuthorizationCodec: RLPCodec[SetCodeAuthorization] =
    RLPCodec.instance[SetCodeAuthorization](
      { case SetCodeAuthorization(chainId, address, nonce, v, r, s) =>
        RLPList(RLPValue(ByteUtils.bigIntToUnsignedByteArray(chainId)), address,
          RLPValue(ByteUtils.bigIntToUnsignedByteArray(nonce)),
          RLPValue(ByteUtils.bigIntToUnsignedByteArray(v)),
          RLPValue(ByteUtils.bigIntToUnsignedByteArray(r)),
          RLPValue(ByteUtils.bigIntToUnsignedByteArray(s)))
      },
      { case RLPList(rlpChainId, rlpAddress, rlpNonce, rlpV, rlpR, rlpS) =>
        SetCodeAuthorization(rlpChainId.decodeAs[BigInt]("chainId"),
          rlpAddress.decodeAs[Address]("address"), rlpNonce.decodeAs[BigInt]("nonce"),
          rlpV.decodeAs[BigInt]("v"), rlpR.decodeAs[BigInt]("r"), rlpS.decodeAs[BigInt]("s"))
      }
    )

  // ── TYPED TRANSACTION HELPERS ────────────────────────────────────────────────
  // Copied from BaseETH6XMessages.TypedTransaction — needed by SignedTransactions and PooledTransactions.

  object TypedTransaction {
    implicit class TypedTransactionsRLPAggregator(val encodables: Seq[RLPEncodeable]) extends AnyVal {
      def toTypedRLPEncodables: Seq[RLPEncodeable] = {
        import Transaction.ByteArrayTransactionTypeValidator
        import Transaction.TransactionTypeValidator
        val result = new scala.collection.mutable.ArrayBuffer[RLPEncodeable](encodables.size)
        var i = 0
        val items = encodables match {
          case indexed: IndexedSeq[RLPEncodeable] => indexed
          case other                              => other.toIndexedSeq
        }
        val len = items.size
        while (i < len)
          items(i) match {
            case RLPValue(v) if v.isValidTransactionType && i + 1 < len =>
              items(i + 1) match {
                case rlpList: RLPList =>
                  result += PrefixedRLPEncodable(v.head, rlpList)
                  i += 2
                case _ =>
                  result += items(i)
                  i += 1
              }
            case RLPValue(v) if v.length > 1 && v.head.isValidTransactionType =>
              try
                rawDecode(v.tail) match {
                  case rlpList: RLPList => result += PrefixedRLPEncodable(v.head, rlpList)
                  case _               => result += RLPValue(v)
                }
              catch { case _: Throwable => result += RLPValue(v) }
              i += 1
            case other =>
              result += other
              i += 1
          }
        result.toSeq
      }
    }
  }

  // ── STATUS — version-suffixed: ETH68 has TD, ETH69 does not ──────────────────
  //
  // Reference: Reth status.rs: Status / StatusEth69
  //            Erigon protocol.go: StatusPacket / StatusPacket69
  //            Nethermind V68/Messages/StatusMessage.cs / V69/Messages/StatusMessage69.cs
  //
  // Source: ETH64.scala (Status68), ETH69.scala (Status69)

  object Status68 {
    case class Status68(
        protocolVersion: Int,
        networkId: Long,
        totalDifficulty: BigInt,
        bestHash: ByteString,
        genesisHash: ByteString,
        forkId: ForkId
    ) extends Message {
      override def toString: String =
        s"Status68 { v=$protocolVersion, net=$networkId, td=$totalDifficulty, " +
          s"best=${Hex.toHexString(bestHash.toArray[Byte])}, genesis=${Hex.toHexString(genesisHash.toArray[Byte])}, " +
          s"forkId=$forkId }"
      override def toShortString: String = toString
      override def code: Int             = Codes.StatusCode
    }

    object Status68 {
      implicit class Status68Enc(val underlyingMsg: Status68)
          extends MessageSerializableImplicit[Status68](underlyingMsg)
          with RLPSerializable {
        override def code: Int = Codes.StatusCode
        override def toRLPEncodable: RLPEncodeable = {
          import msg._
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

      implicit class Status68Dec(val bytes: Array[Byte]) extends AnyVal {
        def toStatus68: Status68 = rawDecode(bytes) match {
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
        }
      }
    }
  }

  object Status69 {
    case class Status69(
        protocolVersion: Int,
        networkId: Long,
        genesisHash: ByteString,
        forkId: ForkId,
        earliestBlock: BigInt,
        latestBlock: BigInt,
        latestBlockHash: ByteString
    ) extends Message {
      override val code: Int             = Codes.StatusCode
      override def toShortString: String = toString
      override def toString: String =
        s"Status69(v=$protocolVersion, net=$networkId, genesis=${genesisHash.take(4).toHex}..., " +
          s"forkId=$forkId, earliest=$earliestBlock, latest=$latestBlock, " +
          s"latestHash=${latestBlockHash.take(4).toHex}...)"
    }

    object Status69 {
      implicit class Status69Enc(val underlyingMsg: Status69)
          extends MessageSerializableImplicit[Status69](underlyingMsg)
          with RLPSerializable {
        override def code: Int = Codes.StatusCode
        override def toRLPEncodable: RLPEncodeable = {
          import msg._
          RLPList(
            RLPValue(ByteUtils.bigIntToUnsignedByteArray(protocolVersion)),
            RLPValue(ByteUtils.bigIntToUnsignedByteArray(networkId)),
            RLPValue(genesisHash.toArray[Byte]),
            forkId.toRLPEncodable,
            RLPValue(ByteUtils.bigIntToUnsignedByteArray(earliestBlock)),
            RLPValue(ByteUtils.bigIntToUnsignedByteArray(latestBlock)),
            RLPValue(latestBlockHash.toArray[Byte])
          )
        }
      }

      implicit class Status69Dec(val bytes: Array[Byte]) extends AnyVal {
        def toStatus69: Status69 = rawDecode(bytes) match {
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
          case other => throw new RuntimeException(s"Cannot decode Status69 from: $other")
        }
      }
    }
  }

  // ── NO-SUFFIX MESSAGES (wire format unchanged ETH68-71) ──────────────────────

  object NewBlockHashes {
    case class BlockHash(hash: ByteString, number: BigInt) {
      override def toString: String =
        s"BlockHash { hash: ${Hex.toHexString(hash.toArray[Byte])} number: $number }"
    }

    object BlockHash {
      implicit class BlockHashEnc(blockHash: BlockHash) extends RLPSerializable {
        override def toRLPEncodable: RLPEncodeable =
          RLPList(RLPValue(blockHash.hash.toArray[Byte]), blockHash.number)
      }
      implicit class BlockHashRLPEncodableDec(val rlpEncodeable: RLPEncodeable) extends AnyVal {
        def toBlockHash: BlockHash = rlpEncodeable match {
          case RLPList(RLPValue(hashBytes), RLPValue(numberBytes)) =>
            BlockHash(ByteString(hashBytes), ByteUtils.bytesToBigInt(numberBytes))
          case _ => throw new RuntimeException("Cannot decode BlockHash")
        }
      }
    }

    case class NewBlockHashes(hashes: Seq[BlockHash]) extends Message {
      override def code: Int             = Codes.NewBlockHashesCode
      override def toShortString: String = toString
      override def toString: String      = s"NewBlockHashes { hashes: $hashes }"
    }

    object NewBlockHashes {
      import BlockHash._
      implicit class NewBlockHashesEnc(val underlyingMsg: NewBlockHashes)
          extends MessageSerializableImplicit[NewBlockHashes](underlyingMsg)
          with RLPSerializable {
        override def code: Int = Codes.NewBlockHashesCode
        override def toRLPEncodable: RLPEncodeable =
          RLPList(msg.hashes.map(_.toRLPEncodable): _*)
      }

      implicit class NewBlockHashesDec(val bytes: Array[Byte]) extends AnyVal {
        def toNewBlockHashes: NewBlockHashes = rawDecode(bytes) match {
          case rlpList: RLPList => NewBlockHashes(rlpList.items.map(_.toBlockHash))
          case _                => throw new RuntimeException("Cannot decode NewBlockHashes")
        }
      }
    }
  }

  // ── SIGNED TRANSACTIONS ───────────────────────────────────────────────────────
  // Source: BaseETH6XMessages.SignedTransactions (full copy, standalone)

  object SignedTransactions {

    implicit class SignedTransactionEnc(val signedTx: SignedTransaction) extends RLPSerializable {
      override def toRLPEncodable: RLPEncodeable = {
        val receivingAddressBytes = signedTx.tx.receivingAddress.map(_.toArray).getOrElse(Array.empty[Byte])
        signedTx.tx match {
          case TransactionWithDynamicFee(chainId, nonce, maxPriorityFeePerGas, maxFeePerGas, gasLimit, _, value, payload, accessList) =>
            PrefixedRLPEncodable(Transaction.Type02, RLPList(
              RLPValue(ByteUtils.bigIntToUnsignedByteArray(chainId)),
              RLPValue(ByteUtils.bigIntToUnsignedByteArray(nonce)),
              RLPValue(ByteUtils.bigIntToUnsignedByteArray(maxPriorityFeePerGas)),
              RLPValue(ByteUtils.bigIntToUnsignedByteArray(maxFeePerGas)),
              RLPValue(ByteUtils.bigIntToUnsignedByteArray(gasLimit)),
              receivingAddressBytes,
              RLPValue(ByteUtils.bigIntToUnsignedByteArray(value)),
              RLPValue(payload.toArray),
              toRlpList(accessList),
              RLPValue(ByteUtils.bigIntToUnsignedByteArray(signedTx.signature.v)),
              RLPValue(ByteUtils.bigIntToUnsignedByteArray(signedTx.signature.r)),
              RLPValue(ByteUtils.bigIntToUnsignedByteArray(signedTx.signature.s))
            ))
          case TransactionWithAccessList(chainId, nonce, gasPrice, gasLimit, _, value, payload, accessList) =>
            PrefixedRLPEncodable(Transaction.Type01, RLPList(
              RLPValue(ByteUtils.bigIntToUnsignedByteArray(chainId)),
              RLPValue(ByteUtils.bigIntToUnsignedByteArray(nonce)),
              RLPValue(ByteUtils.bigIntToUnsignedByteArray(gasPrice)),
              RLPValue(ByteUtils.bigIntToUnsignedByteArray(gasLimit)),
              receivingAddressBytes,
              RLPValue(ByteUtils.bigIntToUnsignedByteArray(value)),
              RLPValue(payload.toArray),
              toRlpList(accessList),
              RLPValue(ByteUtils.bigIntToUnsignedByteArray(signedTx.signature.v)),
              RLPValue(ByteUtils.bigIntToUnsignedByteArray(signedTx.signature.r)),
              RLPValue(ByteUtils.bigIntToUnsignedByteArray(signedTx.signature.s))
            ))
          case BlobTransaction(chainId, nonce, maxPriorityFeePerGas, maxFeePerGas, gasLimit, _, value, payload, accessList, maxFeePerBlobGas, blobVersionedHashes) =>
            PrefixedRLPEncodable(Transaction.Type03, RLPList(
              RLPValue(ByteUtils.bigIntToUnsignedByteArray(chainId)),
              RLPValue(ByteUtils.bigIntToUnsignedByteArray(nonce)),
              RLPValue(ByteUtils.bigIntToUnsignedByteArray(maxPriorityFeePerGas)),
              RLPValue(ByteUtils.bigIntToUnsignedByteArray(maxFeePerGas)),
              RLPValue(ByteUtils.bigIntToUnsignedByteArray(gasLimit)),
              receivingAddressBytes,
              RLPValue(ByteUtils.bigIntToUnsignedByteArray(value)),
              RLPValue(payload.toArray),
              toRlpList(accessList),
              RLPValue(ByteUtils.bigIntToUnsignedByteArray(maxFeePerBlobGas)),
              RLPList(blobVersionedHashes.map(h => RLPValue(h.toArray)): _*),
              RLPValue(ByteUtils.bigIntToUnsignedByteArray(signedTx.signature.v)),
              RLPValue(ByteUtils.bigIntToUnsignedByteArray(signedTx.signature.r)),
              RLPValue(ByteUtils.bigIntToUnsignedByteArray(signedTx.signature.s))
            ))
          case SetCodeTransaction(chainId, nonce, maxPriorityFeePerGas, maxFeePerGas, gasLimit, _, value, payload, accessList, authorizationList) =>
            PrefixedRLPEncodable(Transaction.Type04, RLPList(
              RLPValue(ByteUtils.bigIntToUnsignedByteArray(chainId)),
              RLPValue(ByteUtils.bigIntToUnsignedByteArray(nonce)),
              RLPValue(ByteUtils.bigIntToUnsignedByteArray(maxPriorityFeePerGas)),
              RLPValue(ByteUtils.bigIntToUnsignedByteArray(maxFeePerGas)),
              RLPValue(ByteUtils.bigIntToUnsignedByteArray(gasLimit)),
              receivingAddressBytes,
              RLPValue(ByteUtils.bigIntToUnsignedByteArray(value)),
              RLPValue(payload.toArray),
              toRlpList(accessList),
              toRlpList(authorizationList),
              RLPValue(ByteUtils.bigIntToUnsignedByteArray(signedTx.signature.v)),
              RLPValue(ByteUtils.bigIntToUnsignedByteArray(signedTx.signature.r)),
              RLPValue(ByteUtils.bigIntToUnsignedByteArray(signedTx.signature.s))
            ))
          case LegacyTransaction(nonce, gasPrice, gasLimit, _, value, payload) =>
            RLPList(
              RLPValue(ByteUtils.bigIntToUnsignedByteArray(nonce)),
              RLPValue(ByteUtils.bigIntToUnsignedByteArray(gasPrice)),
              RLPValue(ByteUtils.bigIntToUnsignedByteArray(gasLimit)),
              receivingAddressBytes,
              RLPValue(ByteUtils.bigIntToUnsignedByteArray(value)),
              RLPValue(payload.toArray),
              RLPValue(ByteUtils.bigIntToUnsignedByteArray(signedTx.signature.v)),
              RLPValue(ByteUtils.bigIntToUnsignedByteArray(signedTx.signature.r)),
              RLPValue(ByteUtils.bigIntToUnsignedByteArray(signedTx.signature.s))
            )
        }
      }
    }

    // scalastyle:off method.length
    implicit class SignedTransactionRlpEncodableDec(val rlpEncodeable: RLPEncodeable) extends AnyVal {
      def toSignedTransaction: SignedTransaction = rlpEncodeable match {
        case PrefixedRLPEncodable(Transaction.Type04, RLPList(
              RLPValue(chainIdBytes), RLPValue(nonceBytes), RLPValue(maxPriorityFeePerGasBytes),
              RLPValue(maxFeePerGasBytes), RLPValue(gasLimitBytes), (receivingAddress: RLPValue),
              RLPValue(valueBytes), RLPValue(payloadBytes), (accessList: RLPList),
              (authorizationList: RLPList), RLPValue(pointSignBytes),
              RLPValue(signatureRandomBytes), RLPValue(signatureBytes)
            )) =>
          SignedTransaction(
            SetCodeTransaction(ByteUtils.bytesToBigInt(chainIdBytes), ByteUtils.bytesToBigInt(nonceBytes),
              ByteUtils.bytesToBigInt(maxPriorityFeePerGasBytes), ByteUtils.bytesToBigInt(maxFeePerGasBytes),
              ByteUtils.bytesToBigInt(gasLimitBytes),
              if (receivingAddress.bytes.isEmpty) None else Some(Address(receivingAddress.bytes)),
              ByteUtils.bytesToBigInt(valueBytes), ByteString(payloadBytes),
              fromRlpList[AccessListItem](accessList).toList,
              fromRlpList[SetCodeAuthorization](authorizationList).toList),
            ECDSASignature(ByteUtils.bytesToBigInt(signatureRandomBytes),
              ByteUtils.bytesToBigInt(signatureBytes), ByteUtils.bytesToBigInt(pointSignBytes)))
        case PrefixedRLPEncodable(Transaction.Type03, RLPList(
              RLPValue(chainIdBytes), RLPValue(nonceBytes), RLPValue(maxPriorityFeePerGasBytes),
              RLPValue(maxFeePerGasBytes), RLPValue(gasLimitBytes), (receivingAddress: RLPValue),
              RLPValue(valueBytes), RLPValue(payloadBytes), (accessList: RLPList),
              RLPValue(maxFeePerBlobGasBytes), (blobVersionedHashes: RLPList),
              RLPValue(pointSignBytes), RLPValue(signatureRandomBytes), RLPValue(signatureBytes)
            )) =>
          SignedTransaction(
            BlobTransaction(ByteUtils.bytesToBigInt(chainIdBytes), ByteUtils.bytesToBigInt(nonceBytes),
              ByteUtils.bytesToBigInt(maxPriorityFeePerGasBytes), ByteUtils.bytesToBigInt(maxFeePerGasBytes),
              ByteUtils.bytesToBigInt(gasLimitBytes),
              if (receivingAddress.bytes.isEmpty) None else Some(Address(receivingAddress.bytes)),
              ByteUtils.bytesToBigInt(valueBytes), ByteString(payloadBytes),
              fromRlpList[AccessListItem](accessList).toList,
              ByteUtils.bytesToBigInt(maxFeePerBlobGasBytes),
              blobVersionedHashes.items.map(item => ByteString(item.asInstanceOf[RLPValue].bytes)).toList),
            ECDSASignature(ByteUtils.bytesToBigInt(signatureRandomBytes),
              ByteUtils.bytesToBigInt(signatureBytes), ByteUtils.bytesToBigInt(pointSignBytes)))
        case PrefixedRLPEncodable(Transaction.Type02, RLPList(
              RLPValue(chainIdBytes), RLPValue(nonceBytes), RLPValue(maxPriorityFeePerGasBytes),
              RLPValue(maxFeePerGasBytes), RLPValue(gasLimitBytes), (receivingAddress: RLPValue),
              RLPValue(valueBytes), RLPValue(payloadBytes), (accessList: RLPList),
              RLPValue(pointSignBytes), RLPValue(signatureRandomBytes), RLPValue(signatureBytes)
            )) =>
          SignedTransaction(
            TransactionWithDynamicFee(ByteUtils.bytesToBigInt(chainIdBytes), ByteUtils.bytesToBigInt(nonceBytes),
              ByteUtils.bytesToBigInt(maxPriorityFeePerGasBytes), ByteUtils.bytesToBigInt(maxFeePerGasBytes),
              ByteUtils.bytesToBigInt(gasLimitBytes),
              if (receivingAddress.bytes.isEmpty) None else Some(Address(receivingAddress.bytes)),
              ByteUtils.bytesToBigInt(valueBytes), ByteString(payloadBytes),
              fromRlpList[AccessListItem](accessList).toList),
            ECDSASignature(ByteUtils.bytesToBigInt(signatureRandomBytes),
              ByteUtils.bytesToBigInt(signatureBytes), ByteUtils.bytesToBigInt(pointSignBytes)))
        case PrefixedRLPEncodable(Transaction.Type01, RLPList(
              RLPValue(chainIdBytes), RLPValue(nonceBytes), RLPValue(gasPriceBytes),
              RLPValue(gasLimitBytes), (receivingAddress: RLPValue),
              RLPValue(valueBytes), RLPValue(payloadBytes), (accessList: RLPList),
              RLPValue(pointSignBytes), RLPValue(signatureRandomBytes), RLPValue(signatureBytes)
            )) =>
          SignedTransaction(
            TransactionWithAccessList(ByteUtils.bytesToBigInt(chainIdBytes), ByteUtils.bytesToBigInt(nonceBytes),
              ByteUtils.bytesToBigInt(gasPriceBytes), ByteUtils.bytesToBigInt(gasLimitBytes),
              if (receivingAddress.bytes.isEmpty) None else Some(Address(receivingAddress.bytes)),
              ByteUtils.bytesToBigInt(valueBytes), ByteString(payloadBytes),
              fromRlpList[AccessListItem](accessList).toList),
            ECDSASignature(ByteUtils.bytesToBigInt(signatureRandomBytes),
              ByteUtils.bytesToBigInt(signatureBytes), ByteUtils.bytesToBigInt(pointSignBytes)))
        case RLPList(
              RLPValue(nonceBytes), RLPValue(gasPriceBytes), RLPValue(gasLimitBytes),
              (receivingAddress: RLPValue), RLPValue(valueBytes), RLPValue(payloadBytes),
              RLPValue(pointSignBytes), RLPValue(signatureRandomBytes), RLPValue(signatureBytes)
            ) =>
          SignedTransaction(
            LegacyTransaction(ByteUtils.bytesToBigInt(nonceBytes), ByteUtils.bytesToBigInt(gasPriceBytes),
              ByteUtils.bytesToBigInt(gasLimitBytes),
              if (receivingAddress.bytes.isEmpty) None else Some(Address(receivingAddress.bytes)),
              ByteUtils.bytesToBigInt(valueBytes), ByteString(payloadBytes)),
            ECDSASignature(ByteUtils.bytesToBigInt(signatureRandomBytes),
              ByteUtils.bytesToBigInt(signatureBytes), ByteUtils.bytesToBigInt(pointSignBytes)))
        case _ => throw new RuntimeException("Cannot decode SignedTransaction")
      }
    }
    // scalastyle:on method.length

    implicit class SignedTransactionDec(val bytes: Array[Byte]) extends AnyVal {
      def toSignedTransaction: SignedTransaction = {
        val first = bytes(0)
        (first match {
          case Transaction.Type04 => PrefixedRLPEncodable(Transaction.Type04, rawDecode(bytes.tail))
          case Transaction.Type03 =>
            rawDecode(bytes.tail) match {
              case outer: RLPList if outer.items.size == 4 && outer.items.head.isInstanceOf[RLPList] =>
                PrefixedRLPEncodable(Transaction.Type03, outer.items.head)
              case other => PrefixedRLPEncodable(Transaction.Type03, other)
            }
          case Transaction.Type02 => PrefixedRLPEncodable(Transaction.Type02, rawDecode(bytes.tail))
          case Transaction.Type01 => PrefixedRLPEncodable(Transaction.Type01, rawDecode(bytes.tail))
          case _                  => rawDecode(bytes)
        }).toSignedTransaction
      }
    }

    implicit class SignedTransactionsEnc(val underlyingMsg: SignedTransactions)
        extends MessageSerializableImplicit[SignedTransactions](underlyingMsg)
        with RLPSerializable {
      override def code: Int = Codes.SignedTransactionsCode
      override def toRLPEncodable: RLPEncodeable =
        RLPList(msg.txs.map(_.toRLPEncodable): _*)
    }

    implicit class SignedTransactionsDec(val bytes: Array[Byte]) extends AnyVal {
      def toSignedTransactions: SignedTransactions = rawDecode(bytes) match {
        case rlpList: RLPList =>
          import TypedTransaction._
          SignedTransactions(rlpList.items.toTypedRLPEncodables.map(_.toSignedTransaction))
        case _ => throw new RuntimeException("Cannot decode SignedTransactions")
      }
    }
  }

  case class SignedTransactions(txs: Seq[SignedTransaction]) extends Message {
    override def code: Int             = Codes.SignedTransactionsCode
    override def toShortString: String = s"SignedTransactions { txs: ${txs.map(_.hash.toHex)} }"
  }

  // ── NEW BLOCK ─────────────────────────────────────────────────────────────────
  // Source: BaseETH6XMessages.NewBlock

  object NewBlock {
    implicit class NewBlockEnc(val underlyingMsg: NewBlock)
        extends MessageSerializableImplicit[NewBlock](underlyingMsg)
        with RLPSerializable {
      import SignedTransactions._
      override def code: Int = Codes.NewBlockCode
      override def toRLPEncodable: RLPEncodeable = {
        import msg._
        RLPList(
          RLPList(
            block.header.toRLPEncodable,
            RLPList(block.body.transactionList.map(_.toRLPEncodable): _*),
            RLPList(block.body.uncleNodesList.map(_.toRLPEncodable): _*)
          ),
          RLPValue(ByteUtils.bigIntToUnsignedByteArray(totalDifficulty))
        )
      }
    }

    implicit class NewBlockDec(val bytes: Array[Byte]) extends AnyVal {
      def toNewBlock: NewBlock = rawDecode(bytes) match {
        case RLPList(
              RLPList(blockHeader, transactionList: RLPList, uncleNodesList: RLPList),
              RLPValue(totalDifficultyBytes)
            ) =>
          import SignedTransactions._
          import TypedTransaction._
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
      }
    }
  }

  case class NewBlock(block: Block, totalDifficulty: BigInt) extends Message {
    override def code: Int = Codes.NewBlockCode
    override def toShortString: String =
      s"NewBlock { code: $code, block.header: ${block.header}, totalDifficulty: $totalDifficulty }"
  }

  // ── GET BLOCK HEADERS ─────────────────────────────────────────────────────────
  // Source: ETH66.GetBlockHeaders

  object GetBlockHeaders {
    implicit class GetBlockHeadersEnc(val underlyingMsg: GetBlockHeaders)
        extends MessageSerializableImplicit[GetBlockHeaders](underlyingMsg)
        with RLPSerializable {
      override def code: Int = Codes.GetBlockHeadersCode
      override def toRLPEncodable: RLPEncodeable = {
        import msg._
        def num(b: BigInt): RLPValue    = RLPValue(ByteUtils.bigIntToUnsignedByteArray(b))
        val reverseFlag: RLPValue =
          if (reverse) RLPValue(Array[Byte](1.toByte)) else RLPValue(Array.emptyByteArray)
        val blockQuery = block match {
          case Left(blockNumber) => RLPList(num(blockNumber), num(maxHeaders), num(skip), reverseFlag)
          case Right(blockHash)  => RLPList(RLPValue(blockHash.toArray[Byte]), num(maxHeaders), num(skip), reverseFlag)
        }
        RLPList(num(requestId), blockQuery)
      }
    }

    implicit class GetBlockHeadersDec(val bytes: Array[Byte]) extends AnyVal {
      def toGetBlockHeaders: GetBlockHeaders = rawDecode(bytes) match {
        case RLPList(RLPValue(requestIdBytes), RLPList(block: RLPValue, RLPValue(maxHeadersBytes), RLPValue(skipBytes), RLPValue(reverseBytes)))
            if block.bytes.length < 32 =>
          GetBlockHeaders(ByteUtils.bytesToBigInt(requestIdBytes), Left(ByteUtils.bytesToBigInt(block.bytes)),
            ByteUtils.bytesToBigInt(maxHeadersBytes), ByteUtils.bytesToBigInt(skipBytes),
            ByteUtils.bytesToBigInt(reverseBytes).toInt == 1)
        case RLPList(RLPValue(requestIdBytes), RLPList(block: RLPValue, RLPValue(maxHeadersBytes), RLPValue(skipBytes), RLPValue(reverseBytes))) =>
          GetBlockHeaders(ByteUtils.bytesToBigInt(requestIdBytes), Right(ByteString(block.bytes)),
            ByteUtils.bytesToBigInt(maxHeadersBytes), ByteUtils.bytesToBigInt(skipBytes),
            ByteUtils.bytesToBigInt(reverseBytes).toInt == 1)
        case RLPList(RLPValue(blockBytes), RLPValue(maxHeadersBytes), RLPValue(skipBytes), RLPValue(reverseBytes))
            if blockBytes.length < 32 =>
          GetBlockHeaders(0, Left(ByteUtils.bytesToBigInt(blockBytes)),
            ByteUtils.bytesToBigInt(maxHeadersBytes), ByteUtils.bytesToBigInt(skipBytes),
            ByteUtils.bytesToBigInt(reverseBytes) == 1)
        case RLPList(RLPValue(blockBytes), RLPValue(maxHeadersBytes), RLPValue(skipBytes), RLPValue(reverseBytes)) =>
          GetBlockHeaders(0, Right(ByteString(blockBytes)),
            ByteUtils.bytesToBigInt(maxHeadersBytes), ByteUtils.bytesToBigInt(skipBytes),
            ByteUtils.bytesToBigInt(reverseBytes) == 1)
        case _ => throw new RuntimeException("Cannot decode GetBlockHeaders")
      }
    }
  }

  case class GetBlockHeaders(requestId: BigInt, block: Either[BigInt, ByteString], maxHeaders: BigInt, skip: BigInt, reverse: Boolean)
      extends Message with HasRequestId {
    override def code: Int = Codes.GetBlockHeadersCode
    override def toShortString: String =
      s"GetBlockHeaders { requestId: $requestId, block: ${block.fold(identity, h => Hex.toHexString(h.toArray))}, maxHeaders: $maxHeaders }"
  }

  // ── BLOCK HEADERS ─────────────────────────────────────────────────────────────
  // Source: ETH66.BlockHeaders

  object BlockHeaders {
    implicit class BlockHeadersEnc(val underlyingMsg: BlockHeaders)
        extends MessageSerializableImplicit[BlockHeaders](underlyingMsg)
        with RLPSerializable {
      override def code: Int = Codes.BlockHeadersCode
      override def toRLPEncodable: RLPEncodeable =
        RLPList(RLPValue(ByteUtils.bigIntToUnsignedByteArray(msg.requestId)),
          RLPList(msg.headers.map(_.toRLPEncodable): _*))
    }

    implicit class BlockHeadersDec(val bytes: Array[Byte]) extends AnyVal {
      def toBlockHeaders: BlockHeaders = rawDecode(bytes) match {
        case rlpList: RLPList if rlpList.items.size == 2 =>
          rlpList.items match {
            case Seq(RLPValue(requestIdBytes), headersList: RLPList) =>
              BlockHeaders(ByteUtils.bytesToBigInt(requestIdBytes), headersList.items.map(_.toBlockHeader))
            case _ => BlockHeaders(0, rlpList.items.map(_.toBlockHeader))
          }
        case rlpList: RLPList => BlockHeaders(0, rlpList.items.map(_.toBlockHeader))
        case _                => throw new RuntimeException("Cannot decode BlockHeaders")
      }
    }
  }

  case class BlockHeaders(requestId: BigInt, headers: Seq[BlockHeader]) extends Message with HasRequestId {
    val code: Int = Codes.BlockHeadersCode
    override def toShortString: String =
      s"BlockHeaders { requestId: $requestId, count: ${headers.size} }"
  }

  // ── GET BLOCK BODIES ──────────────────────────────────────────────────────────
  // Source: ETH66.GetBlockBodies

  object GetBlockBodies {
    implicit class GetBlockBodiesEnc(val underlyingMsg: GetBlockBodies)
        extends MessageSerializableImplicit[GetBlockBodies](underlyingMsg)
        with RLPSerializable {
      override def code: Int = Codes.GetBlockBodiesCode
      override def toRLPEncodable: RLPEncodeable =
        RLPList(RLPValue(ByteUtils.bigIntToUnsignedByteArray(msg.requestId)), toRlpList(msg.hashes))
    }

    implicit class GetBlockBodiesDec(val bytes: Array[Byte]) extends AnyVal {
      def toGetBlockBodies: GetBlockBodies = rawDecode(bytes) match {
        case rlpList: RLPList if rlpList.items.size == 2 =>
          rlpList.items match {
            case Seq(RLPValue(requestIdBytes), hashesList: RLPList) =>
              GetBlockBodies(ByteUtils.bytesToBigInt(requestIdBytes), fromRlpList[ByteString](hashesList))
            case _ => GetBlockBodies(0, fromRlpList[ByteString](rlpList))
          }
        case rlpList: RLPList => GetBlockBodies(0, fromRlpList[ByteString](rlpList))
        case _                => throw new RuntimeException("Cannot decode GetBlockBodies")
      }
    }
  }

  case class GetBlockBodies(requestId: BigInt, hashes: Seq[ByteString]) extends Message with HasRequestId {
    override def code: Int             = Codes.GetBlockBodiesCode
    override def toShortString: String = s"GetBlockBodies { requestId: $requestId, count: ${hashes.size} }"
  }

  // ── BLOCK BODIES ──────────────────────────────────────────────────────────────
  // Source: ETH66.BlockBodies

  object BlockBodies {
    implicit class BlockBodiesEnc(val underlyingMsg: BlockBodies)
        extends MessageSerializableImplicit[BlockBodies](underlyingMsg)
        with RLPSerializable {
      override def code: Int = Codes.BlockBodiesCode
      override def toRLPEncodable: RLPEncodeable =
        RLPList(RLPValue(ByteUtils.bigIntToUnsignedByteArray(msg.requestId)),
          RLPList(msg.bodies.map(_.toRLPEncodable): _*))
    }

    implicit class BlockBodiesDec(val bytes: Array[Byte]) extends AnyVal {
      def toBlockBodies: BlockBodies = rawDecode(bytes) match {
        case rlpList: RLPList if rlpList.items.size == 2 =>
          rlpList.items match {
            case Seq(RLPValue(requestIdBytes), bodiesList: RLPList) =>
              BlockBodies(ByteUtils.bytesToBigInt(requestIdBytes), bodiesList.items.map(_.toBlockBody))
            case _ => BlockBodies(0, rlpList.items.map(_.toBlockBody))
          }
        case rlpList: RLPList => BlockBodies(0, rlpList.items.map(_.toBlockBody))
        case _                => throw new RuntimeException("Cannot decode BlockBodies")
      }
    }
  }

  case class BlockBodies(requestId: BigInt, bodies: Seq[BlockBody]) extends Message with HasRequestId {
    val code: Int = Codes.BlockBodiesCode
    override def toShortString: String =
      s"BlockBodies { requestId: $requestId, count: ${bodies.size} }"
  }

  // ── NEW POOLED TRANSACTION HASHES ─────────────────────────────────────────────
  // Source: ETH67.NewPooledTransactionHashes (includes ETH65 backward-compat decode)

  object NewPooledTransactionHashes {
    implicit class NewPooledTransactionHashesEnc(val underlyingMsg: NewPooledTransactionHashes)
        extends MessageSerializableImplicit[NewPooledTransactionHashes](underlyingMsg)
        with RLPSerializable {
      override def code: Int = Codes.NewPooledTransactionHashesCode
      override def toRLPEncodable: RLPEncodeable = {
        import msg._
        RLPList(RLPValue(types.toArray), toRlpList(sizes), toRlpList(hashes))
      }
    }

    implicit class NewPooledTransactionHashesDec(val bytes: Array[Byte]) extends AnyVal {
      def toNewPooledTransactionHashes: NewPooledTransactionHashes = {
        rawDecode(bytes) match {
          case RLPList(RLPValue(typesBytes), sizesList: RLPList, hashesList: RLPList) =>
            NewPooledTransactionHashes(typesBytes.toSeq, fromRlpList[BigInt](sizesList), fromRlpList[ByteString](hashesList))
          case rlpList: RLPList =>
            val hashes = fromRlpList[ByteString](rlpList)
            NewPooledTransactionHashes(Seq.fill(hashes.size)(0.toByte), Seq.fill(hashes.size)(BigInt(0)), hashes)
          case _ => throw new RuntimeException("Cannot decode NewPooledTransactionHashes")
        }
      }
    }
  }

  case class NewPooledTransactionHashes(types: Seq[Byte], sizes: Seq[BigInt], hashes: Seq[ByteString]) extends Message {
    require(types.size == sizes.size && sizes.size == hashes.size, "types, sizes, and hashes must have same length")
    override def code: Int             = Codes.NewPooledTransactionHashesCode
    override def toShortString: String = s"NewPooledTransactionHashes { count: ${hashes.size} }"
  }

  // ── GET POOLED TRANSACTIONS ───────────────────────────────────────────────────
  // Source: ETH66.GetPooledTransactions

  object GetPooledTransactions {
    implicit class GetPooledTransactionsEnc(val underlyingMsg: GetPooledTransactions)
        extends MessageSerializableImplicit[GetPooledTransactions](underlyingMsg)
        with RLPSerializable {
      override def code: Int = Codes.GetPooledTransactionsCode
      override def toRLPEncodable: RLPEncodeable =
        RLPList(RLPValue(ByteUtils.bigIntToUnsignedByteArray(msg.requestId)), toRlpList(msg.txHashes))
    }

    implicit class GetPooledTransactionsDec(val bytes: Array[Byte]) extends AnyVal {
      def toGetPooledTransactions: GetPooledTransactions = rawDecode(bytes) match {
        case RLPList(RLPValue(requestIdBytes), rlpList: RLPList) =>
          GetPooledTransactions(ByteUtils.bytesToBigInt(requestIdBytes), fromRlpList[ByteString](rlpList))
        case _ => throw new RuntimeException("Cannot decode GetPooledTransactions")
      }
    }
  }

  case class GetPooledTransactions(requestId: BigInt, txHashes: Seq[ByteString]) extends Message with HasRequestId {
    override def code: Int             = Codes.GetPooledTransactionsCode
    override def toShortString: String = s"GetPooledTransactions { requestId: $requestId, count: ${txHashes.size} }"
  }

  // ── POOLED TRANSACTIONS ───────────────────────────────────────────────────────
  // Source: ETH66.PooledTransactions

  object PooledTransactions {
    implicit class PooledTransactionsEnc(val underlyingMsg: PooledTransactions)
        extends MessageSerializableImplicit[PooledTransactions](underlyingMsg)
        with RLPSerializable {
      import SignedTransactions._
      override def code: Int = Codes.PooledTransactionsCode
      override def toRLPEncodable: RLPEncodeable = {
        val txItems: Seq[RLPEncodeable] = msg.txs.map { stx =>
          msg.blobTxRawBytes.get(stx.hash) match {
            case Some(rawBytes) => PrefixedRLPEncodable(rawBytes(0), rawDecode(rawBytes.toArray.drop(1)))
            case None           => stx.toRLPEncodable
          }
        }
        RLPList(RLPValue(ByteUtils.bigIntToUnsignedByteArray(msg.requestId)), RLPList(txItems: _*))
      }
    }

    implicit class PooledTransactionsDec(val bytes: Array[Byte]) extends AnyVal {
      def toPooledTransactions: PooledTransactions = rawDecode(bytes) match {
        case RLPList(RLPValue(requestIdBytes), rlpList: RLPList) =>
          import SignedTransactions._
          import TypedTransaction._
          val typedItems = rlpList.items.toTypedRLPEncodables
          typedItems.foreach {
            case PrefixedRLPEncodable(Transaction.Type03, inner: RLPList) =>
              val isNetworkWrapped = inner.items.size == 4 && inner.items.head.isInstanceOf[RLPList]
              if (!isNetworkWrapped)
                throw new RuntimeException("Blob tx in PooledTransactions missing sidecar (network wrapping required)")
            case _ =>
          }
          val blobTxRawBytesBuilder = Map.newBuilder[ByteString, ByteString]
          val unwrappedItems = typedItems.map {
            case prefixed @ PrefixedRLPEncodable(Transaction.Type03, inner: RLPList)
                if inner.items.size == 4 && inner.items.head.isInstanceOf[RLPList] =>
              val rawBytes = com.chipprbots.ethereum.rlp.encode(prefixed)
              val unwrapped = PrefixedRLPEncodable(Transaction.Type03, inner.items.head)
              val stx = unwrapped.toSignedTransaction
              blobTxRawBytesBuilder += (stx.hash -> ByteString(rawBytes))
              unwrapped
            case other => other
          }
          val originalSizes = rlpList.items.map {
            case RLPValue(v) => v.length
            case rl: RLPList => com.chipprbots.ethereum.rlp.encode(rl).length
            case _           => 0
          }
          PooledTransactions(ByteUtils.bytesToBigInt(requestIdBytes),
            unwrappedItems.map(_.toSignedTransaction), originalSizes, blobTxRawBytesBuilder.result())
        case _ => throw new RuntimeException("Cannot decode PooledTransactions")
      }
    }
  }

  case class PooledTransactions(requestId: BigInt, txs: Seq[SignedTransaction],
      originalSizes: Seq[Int] = Seq.empty, blobTxRawBytes: Map[ByteString, ByteString] = Map.empty)
      extends Message with HasRequestId {
    override def code: Int             = Codes.PooledTransactionsCode
    override def toShortString: String = s"PooledTransactions { requestId: $requestId, count: ${txs.size} }"
  }

  // ── GET RECEIPTS ──────────────────────────────────────────────────────────────
  // Source: ETH66.GetReceipts

  object GetReceipts {
    implicit class GetReceiptsEnc(val underlyingMsg: GetReceipts)
        extends MessageSerializableImplicit[GetReceipts](underlyingMsg)
        with RLPSerializable {
      override def code: Int = Codes.GetReceiptsCode
      override def toRLPEncodable: RLPEncodeable =
        RLPList(RLPValue(ByteUtils.bigIntToUnsignedByteArray(msg.requestId)), toRlpList(msg.blockHashes))
    }

    implicit class GetReceiptsDec(val bytes: Array[Byte]) extends AnyVal {
      def toGetReceipts: GetReceipts = rawDecode(bytes) match {
        case rlpList: RLPList if rlpList.items.size == 2 =>
          rlpList.items match {
            case Seq(RLPValue(requestIdBytes), hashesList: RLPList) =>
              GetReceipts(ByteUtils.bytesToBigInt(requestIdBytes), fromRlpList[ByteString](hashesList))
            case _ => GetReceipts(0, fromRlpList[ByteString](rlpList))
          }
        case rlpList: RLPList => GetReceipts(0, fromRlpList[ByteString](rlpList))
        case _                => throw new RuntimeException("Cannot decode GetReceipts")
      }
    }
  }

  case class GetReceipts(requestId: BigInt, blockHashes: Seq[ByteString]) extends Message with HasRequestId {
    override def code: Int             = Codes.GetReceiptsCode
    override def toShortString: String = s"GetReceipts { requestId: $requestId, count: ${blockHashes.size} }"
  }

  /** ETH69 GetReceipts — same wire format as GetReceipts, distinct type so BlockchainHostActor
    * can serve bloom-absent Receipts69 in response (EIP-7642).
    * ETH69MessageDecoder decodes GetReceiptsCode to this type.
    */
  object GetReceipts69 {
    implicit class GetReceipts69Enc(val underlyingMsg: GetReceipts69)
        extends MessageSerializableImplicit[GetReceipts69](underlyingMsg)
        with RLPSerializable {
      override def code: Int = Codes.GetReceiptsCode
      override def toRLPEncodable: RLPEncodeable =
        RLPList(RLPValue(ByteUtils.bigIntToUnsignedByteArray(msg.requestId)), toRlpList(msg.blockHashes))
    }

    implicit class GetReceipts69Dec(val bytes: Array[Byte]) extends AnyVal {
      def toGetReceipts69: GetReceipts69 = rawDecode(bytes) match {
        case rlpList: RLPList if rlpList.items.size == 2 =>
          rlpList.items match {
            case Seq(RLPValue(requestIdBytes), hashesList: RLPList) =>
              GetReceipts69(ByteUtils.bytesToBigInt(requestIdBytes), fromRlpList[ByteString](hashesList))
            case _ => GetReceipts69(0, fromRlpList[ByteString](rlpList))
          }
        case rlpList: RLPList => GetReceipts69(0, fromRlpList[ByteString](rlpList))
        case _                => throw new RuntimeException("Cannot decode GetReceipts69")
      }
    }
  }

  case class GetReceipts69(requestId: BigInt, blockHashes: Seq[ByteString]) extends Message with HasRequestId {
    override def code: Int             = Codes.GetReceiptsCode
    override def toShortString: String = s"GetReceipts69 { requestId: $requestId, count: ${blockHashes.size} }"
  }

  // ── RECEIPT ENCODING IMPLICITS ────────────────────────────────────────────────
  // Inline log + receipt encoders so ETHPackets is standalone (no ETH63 import needed).
  // Used by BlockchainHostActor when serving receipts to ETH68 and ETH69 peers.

  /** RLP encoding for a single TxLogEntry. Same as ETH63.TxLogEntryImplicits.TxLogEntryEnc. */
  implicit class TxLogEntryRLPEnc(logEntry: TxLogEntry) extends RLPSerializable {
    override def toRLPEncodable: RLPEncodeable =
      RLPList(
        RLPValue(logEntry.loggerAddress.bytes.toArray[Byte]),
        RLPList(logEntry.logTopics.map(t => RLPValue(t.toArray[Byte])): _*),
        RLPValue(logEntry.data.toArray[Byte])
      )
  }

  private def receiptStateHash(r: Receipt): RLPEncodeable = r.postTransactionStateHash match {
    case HashOutcome(hash) => RLPValue(hash.toArray[Byte])
    case SuccessOutcome    => 1.toByte
    case _                 => 0.toByte
  }

  private def wrapTypedReceipt(r: Receipt, legacyRLP: RLPList): RLPEncodeable = r match {
    case _: LegacyReceipt      => legacyRLP
    case _: Type01Receipt      => PrefixedRLPEncodable(Transaction.Type01, legacyRLP)
    case _: Type02Receipt      => PrefixedRLPEncodable(Transaction.Type02, legacyRLP)
    case _: Type03Receipt      => PrefixedRLPEncodable(Transaction.Type03, legacyRLP)
    case _: Type04Receipt      => PrefixedRLPEncodable(Transaction.Type04, legacyRLP)
    case _: TypedLegacyReceipt => legacyRLP
  }

  /** Encode a Receipt with bloom (ETH68 serving). Same as ETH63.ReceiptImplicits.ReceiptEnc. */
  implicit class ReceiptBloomEnc(r: Receipt) extends RLPSerializable {
    override def toRLPEncodable: RLPEncodeable =
      wrapTypedReceipt(r, RLPList(
        receiptStateHash(r),
        RLPValue(ByteUtils.bigIntToUnsignedByteArray(r.cumulativeGasUsed)),
        RLPValue(r.logsBloomFilter.toArray[Byte]),
        RLPList(r.logs.map(_.toRLPEncodable): _*)
      ))
  }

  /** Encode a Receipt WITHOUT bloom (ETH69 serving, EIP-7642).
    * Wire: [stateHash, gasUsed, [logs]] — no logsBloomFilter field.
    */
  implicit class ReceiptBloomFreeEnc(r: Receipt) extends RLPSerializable {
    override def toRLPEncodable: RLPEncodeable =
      wrapTypedReceipt(r, RLPList(
        receiptStateHash(r),
        RLPValue(ByteUtils.bigIntToUnsignedByteArray(r.cumulativeGasUsed)),
        RLPList(r.logs.map(_.toRLPEncodable): _*)
      ))
  }

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
  object Receipts68 {
    implicit class Receipts68Enc(val underlyingMsg: Receipts68)
        extends MessageSerializableImplicit[Receipts68](underlyingMsg)
        with RLPSerializable {
      override def code: Int = Codes.ReceiptsCode
      override def toRLPEncodable: RLPEncodeable =
        RLPList(RLPValue(ByteUtils.bigIntToUnsignedByteArray(msg.requestId)), msg.receiptsForBlocks)
    }

    implicit class Receipts68Dec(val bytes: Array[Byte]) extends AnyVal {
      def toReceipts68: Receipts68 = rawDecode(bytes) match {
        case rlpList: RLPList if rlpList.items.size == 2 =>
          rlpList.items match {
            case Seq(RLPValue(requestIdBytes), receiptsList: RLPList) =>
              Receipts68(ByteUtils.bytesToBigInt(requestIdBytes), receiptsList)
            case _ => Receipts68(0, rlpList)
          }
        case rlpList: RLPList => Receipts68(0, rlpList)
        case _                => throw new RuntimeException("Cannot decode Receipts68")
      }
    }
  }

  case class Receipts68(requestId: BigInt, receiptsForBlocks: RLPList) extends Message with HasRequestId {
    override def code: Int             = Codes.ReceiptsCode
    override def toShortString: String = s"Receipts68 { requestId: $requestId, blocks: ${receiptsForBlocks.items.size} }"
  }

  /** ETH69 receipts: bloom-ABSENT (EIP-7642). NEW — no equivalent in current Fukuii.
    *
    * ETH69MessageDecoder previously used ETH66.Receipts (bloom-inclusive) — protocol violation.
    * Using Receipts69 here is the fix.
    */
  object Receipts69 {
    implicit class Receipts69Enc(val underlyingMsg: Receipts69)
        extends MessageSerializableImplicit[Receipts69](underlyingMsg)
        with RLPSerializable {
      override def code: Int = Codes.ReceiptsCode
      override def toRLPEncodable: RLPEncodeable =
        RLPList(RLPValue(ByteUtils.bigIntToUnsignedByteArray(msg.requestId)), msg.receiptsForBlocks)
    }

    implicit class Receipts69Dec(val bytes: Array[Byte]) extends AnyVal {
      def toReceipts69: Receipts69 = rawDecode(bytes) match {
        case rlpList: RLPList if rlpList.items.size == 2 =>
          rlpList.items match {
            case Seq(RLPValue(requestIdBytes), receiptsList: RLPList) =>
              Receipts69(ByteUtils.bytesToBigInt(requestIdBytes), receiptsList)
            case _ => Receipts69(0, rlpList)
          }
        case rlpList: RLPList => Receipts69(0, rlpList)
        case _                => throw new RuntimeException("Cannot decode Receipts69")
      }
    }
  }

  case class Receipts69(requestId: BigInt, receiptsForBlocks: RLPList) extends Message with HasRequestId {
    override def code: Int             = Codes.ReceiptsCode
    override def toShortString: String = s"Receipts69 { requestId: $requestId, blocks: ${receiptsForBlocks.items.size} }"
  }

  // ── ETH69+ NEW MESSAGES ───────────────────────────────────────────────────────
  // Source: ETH69.BlockRangeUpdate

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
            ByteUtils.bytesToBigInt(earliestBlockBytes),
            ByteUtils.bytesToBigInt(latestBlockBytes),
            ByteString(latestBlockHashBytes)
          )
        case other => throw new RuntimeException(s"Cannot decode BlockRangeUpdate from: $other")
      }
    }
  }

  case class BlockRangeUpdate(earliestBlock: BigInt, latestBlock: BigInt, latestBlockHash: ByteString) extends Message {
    override val code: Int             = Codes.BlockRangeUpdateCode
    override def toShortString: String = s"BlockRangeUpdate(earliest=$earliestBlock, latest=$latestBlock)"
  }
}
