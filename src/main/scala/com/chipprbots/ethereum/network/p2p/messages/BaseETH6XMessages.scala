package com.chipprbots.ethereum.network.p2p.messages

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex

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
        RLPList(
          RLPValue(BigInt(protocolVersion).toByteArray),
          RLPValue(BigInt(networkId).toByteArray),
          RLPValue(totalDifficulty.toByteArray),
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
            BigInt(1, protocolVersionBytes).toInt,
            BigInt(1, networkIdBytes).toInt,
            BigInt(1, totalDifficultyBytes),
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

  /** used by eth61, eth62, eth63
    */
  case class Status(
      protocolVersion: Int,
      networkId: Int,
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
        RLPList(
          RLPList(
            block.header.toRLPEncodable,
            RLPList(block.body.transactionList.map(_.toRLPEncodable): _*),
            RLPList(block.body.uncleNodesList.map(_.toRLPEncodable): _*)
          ),
          RLPValue(totalDifficulty.toByteArray)
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
            BigInt(1, totalDifficultyBytes)
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
      def toTypedRLPEncodables: Seq[RLPEncodeable] =
        encodables match {
          case Seq() => Seq()
          case Seq(RLPValue(v), rlpList: RLPList, tail @ _*) if v.isValidTransactionType =>
            PrefixedRLPEncodable(v.head, rlpList) +: tail.toTypedRLPEncodables
          case Seq(head, tail @ _*) => head +: tail.toTypedRLPEncodables
        }
    }
  }

  object SignedTransactions {

    implicit class SignedTransactionEnc(val signedTx: SignedTransaction) extends RLPSerializable {

      override def toRLPEncodable: RLPEncodeable = {
        val receivingAddressBytes = signedTx.tx.receivingAddress
          .map(_.toArray)
          .getOrElse(Array.emptyByteArray)
        signedTx.tx match {
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
                RLPValue(ByteUtils.bigIntToUnsignedByteArray(BigInt(signedTx.signature.v))),
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
              RLPValue(ByteUtils.bigIntToUnsignedByteArray(BigInt(signedTx.signature.v))),
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
              BigInt(1, chainIdBytes),
              BigInt(1, nonceBytes),
              BigInt(1, gasPriceBytes),
              BigInt(1, gasLimitBytes),
              receivingAddressOpt,
              BigInt(1, valueBytes),
              ByteString(payloadBytes),
              fromRlpList[AccessListItem](accessList).toList
            ),
            BigInt(1, pointSignBytes).toInt.toByte,
            ByteString(signatureRandomBytes),
            ByteString(signatureBytes)
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
              BigInt(1, nonceBytes),
              BigInt(1, gasPriceBytes),
              BigInt(1, gasLimitBytes),
              receivingAddressOpt,
              BigInt(1, valueBytes),
              ByteString(payloadBytes)
            ),
            BigInt(1, pointSignBytes).toInt.toByte,
            ByteString(signatureRandomBytes),
            ByteString(signatureBytes)
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
          case Transaction.Type01 => PrefixedRLPEncodable(Transaction.Type01, rawDecode(bytes.tail))
          // TODO enforce legacy boundaries
          case _ => rawDecode(bytes)
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
