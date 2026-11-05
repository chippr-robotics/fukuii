package com.chipprbots.ethereum.domain

import org.apache.pekko.util.ByteString

import scala.util.Try

import org.bouncycastle.util.encoders.Hex

import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.mpt.ByteArraySerializable
import com.chipprbots.ethereum.rlp
import com.chipprbots.ethereum.rlp.*
import com.chipprbots.ethereum.rlp.RLPImplicits.given
import com.chipprbots.ethereum.utils.ByteUtils

object Account:
  val EmptyStorageRootHash: TrieRoot = TrieRoot(ByteString(kec256(rlp.encode(Array.empty[Byte]))))
  val EmptyCodeHash: CodeHash = CodeHash.Empty

  def empty(startNonce: UInt256 = UInt256.Zero): Account =
    Account(nonce = startNonce, storageRoot = EmptyStorageRootHash, codeHash = EmptyCodeHash)

  // RLP codec — inlined from ETH63.AccountImplicits (go-ethereum / Erigon inline pattern)
  implicit class AccountEnc(val account: Account) extends RLPSerializable:
    override def toRLPEncodable: RLPEncodeable =
      import account.*
      import UInt256RLPImplicits.*
      import RLPImplicits.byteStringEncDec
      RLPList(
        nonce.toRLPEncodable,
        balance.toRLPEncodable,
        byteStringEncDec.encode(storageRoot.value),
        byteStringEncDec.encode(codeHash.value)
      )

  extension (bytes: Array[Byte])
    def toAccount: Account = rawDecode(bytes) match
      case RLPList(
            RLPValue(nonceBytes),
            RLPValue(balanceBytes),
            RLPValue(storageRootBytes),
            RLPValue(codeHashBytes)
          ) =>
        val normalizedStorageRoot =
          if storageRootBytes.isEmpty then Account.EmptyStorageRootHash else TrieRoot(ByteString(storageRootBytes))
        val normalizedCodeHash =
          if codeHashBytes.isEmpty then Account.EmptyCodeHash else CodeHash(ByteString(codeHashBytes))
        Account(
          UInt256(ByteUtils.bytesToBigInt(nonceBytes)),
          UInt256(ByteUtils.bytesToBigInt(balanceBytes)),
          normalizedStorageRoot,
          normalizedCodeHash
        )
      case _ => throw new RuntimeException("Cannot decode Account")

  implicit val accountSerializer: ByteArraySerializable[Account] = new ByteArraySerializable[Account]:
    override def fromBytes(bytes: Array[Byte]): Account = bytes.toAccount
    override def toBytes(input: Account): Array[Byte] = input.toBytes

  def apply(bytes: ByteString): Try[Account] = Try(accountSerializer.fromBytes(bytes.toArray))

case class Account(
    nonce: UInt256 = 0,
    balance: UInt256 = 0,
    storageRoot: TrieRoot = Account.EmptyStorageRootHash,
    codeHash: CodeHash = Account.EmptyCodeHash
):

  def increaseBalance(value: UInt256): Account =
    copy(balance = balance + value)

  def increaseNonce(value: UInt256 = 1): Account =
    copy(nonce = nonce + value)

  def withCode(codeHash: CodeHash): Account =
    copy(codeHash = codeHash)

  def withStorage(storageRoot: TrieRoot): Account =
    copy(storageRoot = storageRoot)

  /** According to EIP161: An account is considered empty when it has no code and zero nonce and zero balance. An
    * account's storage is not relevant when determining emptiness.
    */
  def isEmpty(startNonce: UInt256 = UInt256.Zero): Boolean =
    nonce == startNonce && balance == UInt256.Zero && codeHash == Account.EmptyCodeHash

  /** Under EIP-684 if this evaluates to true then we have a conflict when creating a new account
    */
  def nonEmptyCodeOrNonce(startNonce: UInt256 = UInt256.Zero): Boolean =
    nonce != startNonce || codeHash != Account.EmptyCodeHash

  override def toString: String =
    s"Account(nonce: $nonce, balance: $balance, " +
      s"storageRoot: ${Hex.toHexString(storageRoot.toArray)}, codeHash: ${Hex.toHexString(codeHash.value.toArray[Byte])})"
