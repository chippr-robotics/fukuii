package com.chipprbots.ethereum.utils

import org.apache.pekko.util.ByteString

import boopickle.DefaultBasic.*
import boopickle.Pickler

import com.chipprbots.ethereum.crypto.ECDSASignature
import com.chipprbots.ethereum.domain.AccessListItem
import com.chipprbots.ethereum.domain.StorageKey
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.BlobTransaction
import com.chipprbots.ethereum.domain.GasAmount
import com.chipprbots.ethereum.domain.GasPrice
import com.chipprbots.ethereum.domain.BlockBody
import com.chipprbots.ethereum.domain.BlockHash
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockNumber
import com.chipprbots.ethereum.domain.Difficulty
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.*
import com.chipprbots.ethereum.domain.BlobVersionedHash
import com.chipprbots.ethereum.domain.BloomFilter
import com.chipprbots.ethereum.domain.LegacyTransaction
import com.chipprbots.ethereum.domain.SetCodeAuthorization
import com.chipprbots.ethereum.domain.SetCodeTransaction
import com.chipprbots.ethereum.domain.SignedTransaction
import com.chipprbots.ethereum.domain.Transaction
import com.chipprbots.ethereum.domain.TransactionWithAccessList
import com.chipprbots.ethereum.domain.TransactionWithDynamicFee
import com.chipprbots.ethereum.domain.Timestamp
import com.chipprbots.ethereum.domain.TrieRoot
import com.chipprbots.ethereum.domain.Withdrawal

object Picklers:
  given byteStringPickler: Pickler[ByteString] =
    transformPickler[ByteString, Array[Byte]](ByteString(_))(_.toArray[Byte])
  given bloomFilterPickler: Pickler[BloomFilter] =
    transformPickler[BloomFilter, Array[Byte]](arr => BloomFilter.fromArray(arr))(_.toArray)
  given blobVersionedHashPickler: Pickler[BlobVersionedHash] =
    transformPickler[BlobVersionedHash, Array[Byte]](arr => BlobVersionedHash(ByteString(arr)))(_.toArray)
  given blockHashPickler: Pickler[BlockHash] =
    transformPickler[BlockHash, Array[Byte]](arr => BlockHash(ByteString(arr)))(_.toArray)
  given trieRootPickler: Pickler[TrieRoot] =
    transformPickler[TrieRoot, Array[Byte]](arr => TrieRoot(ByteString(arr)))(_.toArray)
  given ecdsaSignaturePickler: Pickler[ECDSASignature] = generatePickler[ECDSASignature]
  given hefEmptyPickler: Pickler[HefEmpty.type] = generatePickler[HefEmpty.type]
  given hefPostOlympiaPickler: Pickler[HefPostOlympia] = generatePickler[HefPostOlympia]
  given hefPostShanghaiPickler: Pickler[HefPostShanghai] = generatePickler[HefPostShanghai]
  given hefPostCancunPickler: Pickler[HefPostCancun] = generatePickler[HefPostCancun]
  given hefPostPraguePickler: Pickler[HefPostPrague] = generatePickler[HefPostPrague]
  given hefPostAmsterdamPickler: Pickler[HefPostAmsterdam] = generatePickler[HefPostAmsterdam]

  given extraFieldsPickler: Pickler[HeaderExtraFields] = compositePickler[HeaderExtraFields]
    .addConcreteType[HefEmpty.type]
    .addConcreteType[HefPostOlympia]
    .addConcreteType[HefPostShanghai]
    .addConcreteType[HefPostCancun]
    .addConcreteType[HefPostPrague]
    // Appended last on purpose: boopickle assigns concrete-type indices in registration
    // order, so appending leaves every already-persisted header decodable.
    .addConcreteType[HefPostAmsterdam]

  given addressPickler: Pickler[Address] =
    transformPickler[Address, ByteString](bytes => Address(bytes))(address => address.bytes)
  given difficultyPickler: Pickler[Difficulty] =
    transformPickler[Difficulty, BigInt](Difficulty(_))(_.value)
  given storageKeyPickler: Pickler[StorageKey] =
    transformPickler[StorageKey, BigInt](StorageKey(_))(_.value)
  given accessListItemPickler: Pickler[AccessListItem] = generatePickler[AccessListItem]
  given gasAmountPickler: Pickler[GasAmount] =
    transformPickler[GasAmount, BigInt](GasAmount(_))(_.value)
  given gasPricePickler: Pickler[GasPrice] =
    transformPickler[GasPrice, BigInt](GasPrice(_))(_.value)
  given blockNumberPickler: Pickler[BlockNumber] =
    transformPickler[BlockNumber, BigInt](BlockNumber(_))(_.value)
  given timestampPickler: Pickler[Timestamp] =
    transformPickler[Timestamp, Long](Timestamp(_))(_.toLong)

  given legacyTransactionPickler: Pickler[LegacyTransaction] = generatePickler[LegacyTransaction]
  given transactionWithAccessListPickler: Pickler[TransactionWithAccessList] =
    generatePickler[TransactionWithAccessList]
  given transactionWithDynamicFeePickler: Pickler[TransactionWithDynamicFee] =
    generatePickler[TransactionWithDynamicFee]
  given setCodeAuthorizationPickler: Pickler[SetCodeAuthorization] =
    generatePickler[SetCodeAuthorization]
  given blobTransactionPickler: Pickler[BlobTransaction] =
    generatePickler[BlobTransaction]
  given setCodeTransactionPickler: Pickler[SetCodeTransaction] =
    generatePickler[SetCodeTransaction]

  given transactionPickler: Pickler[Transaction] = compositePickler[Transaction]
    .addConcreteType[LegacyTransaction]
    .addConcreteType[TransactionWithAccessList]
    .addConcreteType[TransactionWithDynamicFee]
    .addConcreteType[BlobTransaction]
    .addConcreteType[SetCodeTransaction]

  given signedTransactionPickler: Pickler[SignedTransaction] =
    transformPickler[SignedTransaction, (Transaction, ECDSASignature)] { case (tx, signature) =>
      new SignedTransaction(tx, signature)
    }(stx => (stx.tx, stx.signature))

  given blockHeaderPickler: Pickler[BlockHeader] = generatePickler[BlockHeader]
  given withdrawalPickler: Pickler[Withdrawal] = generatePickler[Withdrawal]
  given blockBodyPickler: Pickler[BlockBody] =
    transformPickler[BlockBody, (Seq[SignedTransaction], Seq[BlockHeader], Option[Seq[Withdrawal]])] {
      case (stx, nodes, ws) => BlockBody(stx, nodes, ws)
    }(blockBody => (blockBody.transactionList, blockBody.uncleNodesList, blockBody.withdrawals))
