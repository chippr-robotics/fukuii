package com.chipprbots.ethereum.keystore

import org.apache.pekko.util.ByteString

import org.bouncycastle.crypto.AsymmetricCipherKeyPair

import com.chipprbots.ethereum.crypto.*
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.SignedTransaction
import com.chipprbots.ethereum.domain.SignedTransactionWithSender
import com.chipprbots.ethereum.domain.Transaction

case class Wallet(address: Address, prvKey: ByteString):
  lazy val keyPair: AsymmetricCipherKeyPair = keyPairFromPrvKey(prvKey.toArray)

  /** Sign any transaction type (legacy, EIP-2930, EIP-1559, EIP-4844, EIP-7702).
    *
    * `chainId` only matters for a legacy transaction: `Some` signs it EIP-155 replay-protected, `None` unprotected.
    * Typed transactions carry their chain id in the payload and always sign over it (`SignedTransaction.sign`).
    */
  def signTx(tx: Transaction, chainId: Option[BigInt]): SignedTransactionWithSender =
    SignedTransactionWithSender(SignedTransaction.sign(tx, keyPair, chainId), Address(keyPair))
