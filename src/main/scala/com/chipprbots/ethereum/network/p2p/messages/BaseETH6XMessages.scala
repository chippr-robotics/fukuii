package com.chipprbots.ethereum.network.p2p.messages

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex

import com.chipprbots.ethereum.crypto.ECDSASignature
import com.chipprbots.ethereum.domain.BlockHeaderImplicits._
import com.chipprbots.ethereum.domain._
import com.chipprbots.ethereum.network.p2p.Message
import com.chipprbots.ethereum.network.p2p.MessageSerializableImplicit
import com.chipprbots.ethereum.rlp.RLPCodec.Ops
import com.chipprbots.ethereum.rlp.RLPImplicitConversions._
import com.chipprbots.ethereum.rlp.RLPImplicits._
import com.chipprbots.ethereum.rlp.RLPImplicits.given
import com.chipprbots.ethereum.rlp._
import com.chipprbots.ethereum.utils.ByteStringUtils.ByteStringOps
import com.chipprbots.ethereum.utils.ByteUtils

object BaseETH6XMessages {
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
              RLPValue(bestHashBytes),
              RLPValue(genesisHashBytes)
            ) =>
          Status(
            ByteUtils.bytesToBigInt(protocolVersionBytes).toInt,
            ByteUtils.bytesToBigInt(networkIdBytes).toLong,
            ByteUtils.bytesToBigInt(totalDifficultyBytes),
            ByteString(bestHashBytes),
            ByteString(genesisHashBytes)
          )

        case _ => throw new RuntimeException("Cannot decode Status")
      }
    }

  }

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
          val address = rlpAddress.decodeAs[Address]("address")
          val storageKeys = fromRlpList[BigInt](rlpStorageKeys).toList
          AccessListItem(address, storageKeys)
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

  /** used by eth61, eth62, eth63
    */
  case class Status(
      protocolVersion: Int,
      networkId: Long,
      totalDifficulty: BigInt,
      bestHash: ByteString,
      genesisHash: ByteString
  ) extends Message {

    override def toString: String =
      s"Status { " +
        s"code: $code, " +
        s"protocolVersion: $protocolVersion, " +
        s"networkId: $networkId, " +
        s"totalDifficulty: $totalDifficulty, " +
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
      import SignedTransactions._

      override def code: Int = Codes.NewBlockCode

      override def toRLPEncodable: RLPEncodeable = {
        import msg._
        // Use bigIntToUnsignedByteArray for proper RLP integer encoding
        // BigInt.toByteArray uses two's complement which adds leading zeros for
        // values with high bit set (e.g., 128 -> [0x00, 0x80] instead of [0x80])
        // RLP specification requires integers to have no leading zeros
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
      import SignedTransactions._
      import TypedTransaction._

      def toNewBlock: NewBlock = rawDecode(bytes) match {
        case RLPList(
              RLPList(blockHeader, transactionList: RLPList, uncleNodesList: RLPList),
              RLPValue(totalDifficultyBytes)
            ) =>
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

  /** used by eth61, eth62, eth63
    */
  case class NewBlock(block: Block, totalDifficulty: BigInt) extends Message {

    override def toString: String =
      s"NewBlock { " +
        s"code: $code, " +
        s"block: $block, " +
        s"totalDifficulty: $totalDifficulty" +
        s"}"

    override def toShortString: String =
      s"NewBlock { " +
        s"code: $code, " +
        s"block.header: ${block.header}, " +
        s"totalDifficulty: $totalDifficulty" +
        s"}"

    override def code: Int = Codes.NewBlockCode
  }

  object TypedTransaction {
    implicit class TypedTransactionsRLPAggregator(val encodables: Seq[RLPEncodeable]) extends AnyVal {

      import Transaction.ByteArrayTransactionTypeValidator
      import Transaction.TransactionTypeValidator

      /** Convert a Seq of RLPEncodable containing TypedTransaction informations into a Seq of Prefixed RLPEncodable.
        *
        * PrefixedRLPEncodable(prefix, prefixedRLPEncodable) generates binary data as prefix ||
        * RLPEncodable(prefixedRLPEncodable).
        *
        * As prefix is a byte value lower than 0x7f, it is read back as RLPValue(prefix), thus PrefixedRLPEncodable is
        * binary equivalent to RLPValue(prefix), RLPEncodable
        *
        * The method aggregates back the typed transaction prefix with the following heuristic:
        *   - a RLPValue(byte) with byte < 07f + the following RLPEncodable are associated as a PrefixedRLPEncodable
        *   - all other RLPEncodable are kept unchanged
        *
        * This is the responsibility of the RLPDecoder to insert this meaning into its RLPList, when appropriate.
        *
        * @return
        *   a Seq of TypedTransaction enriched RLPEncodable
        */
      def toTypedRLPEncodables: Seq[RLPEncodeable] = {
        // Iterative implementation — the recursive version was O(n²) due to Seq pattern matching
        // creating intermediate views for each element. For 2000 txs this caused ~2M operations.
        val result = new scala.collection.mutable.ArrayBuffer[RLPEncodeable](encodables.size)
        var i = 0
        val items = encodables match {
          case indexed: IndexedSeq[RLPEncodeable] => indexed
          case other => other.toIndexedSeq
        }
        val len = items.size
        while (i < len) {
          items(i) match {
            case RLPValue(v) if v.isValidTransactionType && i + 1 < len =>
              items(i + 1) match {
                case rlpList: RLPList =>
                  // Type byte followed by RLPList: combine into PrefixedRLPEncodable
                  result += PrefixedRLPEncodable(v.head, rlpList)
                  i += 2
                case _ =>
                  result += items(i)
                  i += 1
              }
            case RLPValue(v) if v.length > 1 && v.head.isValidTransactionType =>
              // Typed envelopes (EIP-2718): typeByte || rlp(payload) encoded as single RLPValue.
              // Normalize to PrefixedRLPEncodable for all callers (BlockBody, SignedTransactions, etc.)
              try
                rawDecode(v.tail) match {
                  case rlpList: RLPList =>
                    result += PrefixedRLPEncodable(v.head, rlpList)
                  case _ =>
                    result += RLPValue(v)
                }
              catch {
                case _: Throwable => result += RLPValue(v)
              }
              i += 1
            case other =>
              result += other
              i += 1
          }
        }
        result.toSeq
      }
    }
  }

  object SignedTransactions {

    implicit class SignedTransactionEnc(val signedTx: SignedTransaction) extends RLPSerializable {

      override def toRLPEncodable: RLPEncodeable = {
        val receivingAddressBytes = signedTx.tx.receivingAddress
          .map(_.toArray)
          .getOrElse(Array.empty[Byte])
        signedTx.tx match {
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
                RLPValue(ByteUtils.bigIntToUnsignedByteArray(gasLimit)),
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
                RLPValue(ByteUtils.bigIntToUnsignedByteArray(gasPrice)),
                RLPValue(ByteUtils.bigIntToUnsignedByteArray(gasLimit)),
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
                RLPValue(ByteUtils.bigIntToUnsignedByteArray(gasLimit)),
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

    implicit class SignedTransactionsEnc(val underlyingMsg: SignedTransactions)
        extends MessageSerializableImplicit[SignedTransactions](underlyingMsg)
        with RLPSerializable {

      override def code: Int = Codes.SignedTransactionsCode
      override def toRLPEncodable: RLPEncodeable = RLPList(msg.txs.map(_.toRLPEncodable): _*)
    }

    implicit class SignedTransactionsDec(val bytes: Array[Byte]) extends AnyVal {

      import TypedTransaction._

      def toSignedTransactions: SignedTransactions = rawDecode(bytes) match {
        case rlpList: RLPList => SignedTransactions(rlpList.items.toTypedRLPEncodables.map(_.toSignedTransaction))
        case _                => throw new RuntimeException("Cannot decode SignedTransactions")
      }
    }

    implicit class SignedTransactionRlpEncodableDec(val rlpEncodeable: RLPEncodeable) extends AnyVal {

      // scalastyle:off method.length
      /** A signed transaction is either a RLPList representing a Legacy SignedTransaction or a
        * PrefixedRLPEncodable(transactionType, RLPList of typed transaction envelope)
        *
        * @see
        *   TypedTransaction.TypedTransactionsRLPAggregator
        *
        * @return
        *   a SignedTransaction
        */
      def toSignedTransaction: SignedTransaction = rlpEncodeable match {
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
          val receivingAddressOpt = if (receivingAddress.bytes.isEmpty) None else Some(Address(receivingAddress.bytes))
          SignedTransaction(
            SetCodeTransaction(
              ByteUtils.bytesToBigInt(chainIdBytes),
              ByteUtils.bytesToBigInt(nonceBytes),
              ByteUtils.bytesToBigInt(maxPriorityFeePerGasBytes),
              ByteUtils.bytesToBigInt(maxFeePerGasBytes),
              ByteUtils.bytesToBigInt(gasLimitBytes),
              receivingAddressOpt,
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
          val receivingAddressOpt = if (receivingAddress.bytes.isEmpty) None else Some(Address(receivingAddress.bytes))
          SignedTransaction(
            BlobTransaction(
              ByteUtils.bytesToBigInt(chainIdBytes),
              ByteUtils.bytesToBigInt(nonceBytes),
              ByteUtils.bytesToBigInt(maxPriorityFeePerGasBytes),
              ByteUtils.bytesToBigInt(maxFeePerGasBytes),
              ByteUtils.bytesToBigInt(gasLimitBytes),
              receivingAddressOpt,
              ByteUtils.bytesToBigInt(valueBytes),
              ByteString(payloadBytes),
              fromRlpList[AccessListItem](accessList).toList,
              ByteUtils.bytesToBigInt(maxFeePerBlobGasBytes),
              blobVersionedHashes.items.map(item => ByteString(item.asInstanceOf[RLPValue].bytes)).toList
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
          val receivingAddressOpt = if (receivingAddress.bytes.isEmpty) None else Some(Address(receivingAddress.bytes))
          SignedTransaction(
            TransactionWithDynamicFee(
              ByteUtils.bytesToBigInt(chainIdBytes),
              ByteUtils.bytesToBigInt(nonceBytes),
              ByteUtils.bytesToBigInt(maxPriorityFeePerGasBytes),
              ByteUtils.bytesToBigInt(maxFeePerGasBytes),
              ByteUtils.bytesToBigInt(gasLimitBytes),
              receivingAddressOpt,
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
          val receivingAddressOpt = if (receivingAddress.bytes.isEmpty) None else Some(Address(receivingAddress.bytes))
          SignedTransaction(
            TransactionWithAccessList(
              ByteUtils.bytesToBigInt(chainIdBytes),
              ByteUtils.bytesToBigInt(nonceBytes),
              ByteUtils.bytesToBigInt(gasPriceBytes),
              ByteUtils.bytesToBigInt(gasLimitBytes),
              receivingAddressOpt,
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
          val receivingAddressOpt = if (receivingAddress.bytes.isEmpty) None else Some(Address(receivingAddress.bytes))
          SignedTransaction(
            LegacyTransaction(
              ByteUtils.bytesToBigInt(nonceBytes),
              ByteUtils.bytesToBigInt(gasPriceBytes),
              ByteUtils.bytesToBigInt(gasLimitBytes),
              receivingAddressOpt,
              ByteUtils.bytesToBigInt(valueBytes),
              ByteString(payloadBytes)
            ),
            ECDSASignature(
              ByteUtils.bytesToBigInt(signatureRandomBytes),
              ByteUtils.bytesToBigInt(signatureBytes),
              ByteUtils.bytesToBigInt(pointSignBytes)
            )
          )
        case _ =>
          throw new RuntimeException("Cannot decode SignedTransaction")
      }
    }
    // scalastyle:on method.length

    implicit class SignedTransactionDec(val bytes: Array[Byte]) extends AnyVal {
      def toSignedTransaction: SignedTransaction = {
        val first = bytes(0)
        (first match {
          case Transaction.Type04 => PrefixedRLPEncodable(Transaction.Type04, rawDecode(bytes.tail))
          case Transaction.Type03 =>
            // EIP-4844: handle network-wrapped blob tx ([tx_payload, blobs, commitments, proofs])
            val decoded = rawDecode(bytes.tail)
            decoded match {
              case outer: RLPList if outer.items.size == 4 && outer.items.head.isInstanceOf[RLPList] =>
                // Network wrapped form: extract just the tx payload (first element)
                PrefixedRLPEncodable(Transaction.Type03, outer.items.head)
              case _ =>
                PrefixedRLPEncodable(Transaction.Type03, decoded)
            }
          case Transaction.Type02 => PrefixedRLPEncodable(Transaction.Type02, rawDecode(bytes.tail))
          case Transaction.Type01 => PrefixedRLPEncodable(Transaction.Type01, rawDecode(bytes.tail))
          case _                  => rawDecode(bytes)
        }).toSignedTransaction
      }
    }
  }

  case class SignedTransactions(txs: Seq[SignedTransaction]) extends Message {
    override def code: Int = Codes.SignedTransactionsCode
    override def toShortString: String =
      s"SignedTransactions { txs: ${txs.map(_.hash.toHex)} }"
  }
}
