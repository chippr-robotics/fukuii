package com.chipprbots.ethereum.keystore

import org.apache.pekko.util.ByteString

import org.bouncycastle.crypto.AsymmetricCipherKeyPair

import com.chipprbots.ethereum.crypto._
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.LegacyTransaction
import com.chipprbots.ethereum.domain.SignedTransaction
import com.chipprbots.ethereum.domain.SignedTransactionWithSender

case class Wallet(address: Address, prvKey: ByteString) {
  lazy val keyPair: AsymmetricCipherKeyPair = keyPairFromPrvKey(prvKey.toArray)

  def signTx(tx: LegacyTransaction, chainId: Option[BigInt]): SignedTransactionWithSender =
    SignedTransactionWithSender(SignedTransaction.sign(tx, keyPair, chainId), Address(keyPair))
}
