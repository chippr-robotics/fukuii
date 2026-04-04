package com.chipprbots.ethereum.utils

import org.apache.pekko.util.ByteString

import boopickle.DefaultBasic._
import boopickle.Pickler

import com.chipprbots.ethereum.crypto.ECDSASignature
import com.chipprbots.ethereum.domain.AccessListItem
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.BlockBody
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields._
import com.chipprbots.ethereum.domain.LegacyTransaction
import com.chipprbots.ethereum.domain.SignedTransaction
import com.chipprbots.ethereum.domain.Transaction
import com.chipprbots.ethereum.domain.TransactionWithAccessList
import com.chipprbots.ethereum.domain.TransactionWithDynamicFee
import com.chipprbots.ethereum.domain.BlobTransaction
import com.chipprbots.ethereum.domain.SetCodeAuthorization
import com.chipprbots.ethereum.domain.SetCodeTransaction
import com.chipprbots.ethereum.domain.Withdrawal

object Picklers {
  implicit val byteStringPickler: Pickler[ByteString] =
    transformPickler[ByteString, Array[Byte]](ByteString(_))(_.toArray[Byte])
  implicit val ecdsaSignaturePickler: Pickler[ECDSASignature] = generatePickler[ECDSASignature]
  implicit val hefEmptyPickler: Pickler[HefEmpty.type] = generatePickler[HefEmpty.type]
  implicit val hefPostOlympiaPickler: Pickler[HefPostOlympia] = generatePickler[HefPostOlympia]
  implicit val hefPostShanghaiPickler: Pickler[HefPostShanghai] = generatePickler[HefPostShanghai]
  implicit val hefPostCancunPickler: Pickler[HefPostCancun] = generatePickler[HefPostCancun]

  implicit val extraFieldsPickler: Pickler[HeaderExtraFields] = compositePickler[HeaderExtraFields]
    .addConcreteType[HefEmpty.type]
    .addConcreteType[HefPostOlympia]
    .addConcreteType[HefPostShanghai]
    .addConcreteType[HefPostCancun]

  implicit val addressPickler: Pickler[Address] =
    transformPickler[Address, ByteString](bytes => Address(bytes))(address => address.bytes)
  implicit val accessListItemPickler: Pickler[AccessListItem] = generatePickler[AccessListItem]

  implicit val legacyTransactionPickler: Pickler[LegacyTransaction] = generatePickler[LegacyTransaction]
  implicit val transactionWithAccessListPickler: Pickler[TransactionWithAccessList] =
    generatePickler[TransactionWithAccessList]
  implicit val transactionWithDynamicFeePickler: Pickler[TransactionWithDynamicFee] =
    generatePickler[TransactionWithDynamicFee]
  implicit val setCodeAuthorizationPickler: Pickler[SetCodeAuthorization] =
    generatePickler[SetCodeAuthorization]
  implicit val blobTransactionPickler: Pickler[BlobTransaction] =
    generatePickler[BlobTransaction]
  implicit val setCodeTransactionPickler: Pickler[SetCodeTransaction] =
    generatePickler[SetCodeTransaction]

  implicit val transactionPickler: Pickler[Transaction] = compositePickler[Transaction]
    .addConcreteType[LegacyTransaction]
    .addConcreteType[TransactionWithAccessList]
    .addConcreteType[TransactionWithDynamicFee]
    .addConcreteType[BlobTransaction]
    .addConcreteType[SetCodeTransaction]

  implicit val signedTransactionPickler: Pickler[SignedTransaction] =
    transformPickler[SignedTransaction, (Transaction, ECDSASignature)] { case (tx, signature) =>
      new SignedTransaction(tx, signature)
    }(stx => (stx.tx, stx.signature))

  implicit val blockHeaderPickler: Pickler[BlockHeader] = generatePickler[BlockHeader]
  implicit val withdrawalPickler: Pickler[Withdrawal] = generatePickler[Withdrawal]
  implicit val blockBodyPickler: Pickler[BlockBody] =
    transformPickler[BlockBody, (Seq[SignedTransaction], Seq[BlockHeader], Option[Seq[Withdrawal]])] {
      case (stx, nodes, ws) => BlockBody(stx, nodes, ws)
    }(blockBody => (blockBody.transactionList, blockBody.uncleNodesList, blockBody.withdrawals))
}
