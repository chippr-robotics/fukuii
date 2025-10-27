package com.chipprbots.ethereum.faucet.jsonrpc

import akka.util.ByteString

import cats.data.EitherT
import cats.effect.IO

import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.LegacyTransaction
import com.chipprbots.ethereum.faucet.FaucetConfig
import com.chipprbots.ethereum.jsonrpc.client.RpcClient.RpcError
import com.chipprbots.ethereum.keystore.KeyStore
import com.chipprbots.ethereum.keystore.KeyStore.KeyStoreError
import com.chipprbots.ethereum.keystore.Wallet
import com.chipprbots.ethereum.network.p2p.messages.BaseETH6XMessages.SignedTransactions.SignedTransactionEnc
import com.chipprbots.ethereum.rlp
import com.chipprbots.ethereum.utils.ByteStringUtils
import com.chipprbots.ethereum.utils.Logger

class WalletService(walletRpcClient: WalletRpcClient, keyStore: KeyStore, config: FaucetConfig) extends Logger {

  def sendFunds(wallet: Wallet, addressTo: Address): IO[Either[RpcError, ByteString]] =
    (for {
      nonce <- EitherT(walletRpcClient.getNonce(wallet.address))
      txId <- EitherT(walletRpcClient.sendTransaction(prepareTx(wallet, addressTo, nonce)))
    } yield txId).value.map {
      case Right(txId) =>
        val txIdHex = s"0x${ByteStringUtils.hash2string(txId)}"
        log.info(s"Sending ${config.txValue} ETC to $addressTo in tx: $txIdHex.")
        Right(txId)
      case Left(error) =>
        log.error(s"An error occurred while using faucet", error)
        Left(error)
    }

  private def prepareTx(wallet: Wallet, targetAddress: Address, nonce: BigInt): ByteString = {
    val transaction =
      LegacyTransaction(nonce, config.txGasPrice, config.txGasLimit, Some(targetAddress), config.txValue, ByteString())

    val stx = wallet.signTx(transaction, None)
    ByteString(rlp.encode(stx.tx.toRLPEncodable))
  }

  def getWallet: IO[Either[KeyStoreError, Wallet]] = IO {
    keyStore.unlockAccount(config.walletAddress, config.walletPassword) match {
      case Right(w) =>
        log.info(s"unlock wallet for use in faucet (${config.walletAddress})")
        Right(w)
      case Left(err) =>
        log.error(s"Cannot unlock wallet for use in faucet (${config.walletAddress}), because of $err")
        Left(err)
    }
  }

}
