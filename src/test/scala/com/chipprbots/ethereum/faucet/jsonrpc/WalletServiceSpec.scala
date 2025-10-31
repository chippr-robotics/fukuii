package com.chipprbots.ethereum.faucet.jsonrpc

import java.security.SecureRandom

import org.apache.pekko.util.ByteString

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import scala.concurrent.duration._

import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.util.encoders.Hex
import org.scalamock.scalatest.MockFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.crypto
import com.chipprbots.ethereum.crypto._
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.LegacyTransaction
import com.chipprbots.ethereum.faucet.FaucetConfig
import com.chipprbots.ethereum.faucet.RpcClientConfig
import com.chipprbots.ethereum.faucet.SupervisorConfig
import com.chipprbots.ethereum.jsonrpc.client.RpcClient.ConnectionError
import com.chipprbots.ethereum.keystore.KeyStore
import com.chipprbots.ethereum.keystore.KeyStore.DecryptionFailed
import com.chipprbots.ethereum.keystore.Wallet
import com.chipprbots.ethereum.network.p2p.messages.BaseETH6XMessages.SignedTransactions.SignedTransactionEnc
import com.chipprbots.ethereum.rlp

class WalletServiceSpec extends AnyFlatSpec with Matchers with MockFactory {

  implicit val runtime: IORuntime = IORuntime.global

  "Wallet Service" should "send a transaction successfully when getNonce and sendTransaction successfully" in new TestSetup {

    val receivingAddress = Address("0x99")
    val currentNonce = 2

    val tx = wallet.signTx(
      LegacyTransaction(
        currentNonce,
        config.txGasPrice,
        config.txGasLimit,
        receivingAddress,
        config.txValue,
        ByteString()
      ),
      None
    )

    val expectedTx = rlp.encode(tx.tx.toRLPEncodable)

    val retTxId = ByteString(Hex.decode("112233"))

    (walletRpcClient.getNonce _).expects(config.walletAddress).returning(IO.pure(Right(currentNonce)))
    (walletRpcClient.sendTransaction _).expects(ByteString(expectedTx)).returning(IO.pure(Right(retTxId)))

    val res = walletService.sendFunds(wallet, Address("0x99")).unsafeRunSync()

    res shouldEqual Right(retTxId)

  }

  it should "failure the transaction when get timeout of getNonce" in new TestSetup {

    val timeout = ConnectionError("timeout")
    (walletRpcClient.getNonce _).expects(config.walletAddress).returning(IO.pure(Left(timeout)))

    val res = walletService.sendFunds(wallet, Address("0x99")).unsafeRunSync()

    res shouldEqual Left(timeout)

  }

  it should "get wallet successful" in new TestSetup {
    (mockKeyStore.unlockAccount _).expects(config.walletAddress, config.walletPassword).returning(Right(wallet))

    val res = walletService.getWallet.unsafeRunSync()

    res shouldEqual Right(wallet)
  }

  it should "wallet decryption failed" in new TestSetup {
    (mockKeyStore.unlockAccount _)
      .expects(config.walletAddress, config.walletPassword)
      .returning(Left(DecryptionFailed))

    val res = walletService.getWallet.unsafeRunSync()

    res shouldEqual Left(DecryptionFailed)
  }

  trait TestSetup {
    val walletKeyPair: AsymmetricCipherKeyPair = generateKeyPair(new SecureRandom)
    val (prvKey, pubKey) = keyPairToByteStrings(walletKeyPair)
    val wallet: Wallet = Wallet(Address(crypto.kec256(pubKey)), prvKey)

    // MIGRATION: Scala 3 requires explicit type ascription for mock with complex parameterized types
    val walletRpcClient: WalletRpcClient = mock[WalletRpcClient].asInstanceOf[WalletRpcClient]
    val mockKeyStore: KeyStore = mock[KeyStore]
    val config: FaucetConfig =
      FaucetConfig(
        walletAddress = wallet.address,
        walletPassword = "",
        txGasPrice = 10,
        txGasLimit = 20,
        txValue = 1,
        rpcClient = RpcClientConfig("", timeout = 10.seconds),
        keyStoreDir = "",
        handlerTimeout = 10.seconds,
        actorCommunicationMargin = 10.seconds,
        supervisor = mock[SupervisorConfig],
        shutdownTimeout = 15.seconds
      )

    val walletService = new WalletService(walletRpcClient, mockKeyStore, config)
  }

}
