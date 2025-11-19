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
import com.chipprbots.ethereum.domain.SignedTransactionWithSender
import com.chipprbots.ethereum.jsonrpc.client.RpcClient.RpcError
import com.chipprbots.ethereum.jsonrpc.client.RpcClient.RpcError
import com.chipprbots.ethereum.keystore.KeyStore.KeyStoreError
import com.chipprbots.ethereum.keystore.KeyStore.KeyStoreError
import com.chipprbots.ethereum.testing.Tags._

// SCALA 3 MIGRATION: Fixed by creating manual stub implementation for WalletRpcClient
class WalletServiceSpec extends AnyFlatSpec with Matchers with MockFactory {

  implicit val runtime: IORuntime = IORuntime.global

  "Wallet Service" should "send a transaction successfully when getNonce and sendTransaction successfully" taggedAs (
    UnitTest,
    RPCTest
  ) in new TestSetup {

    val receivingAddress: Address = Address("0x99")
    val currentNonce = 2

    val tx: SignedTransactionWithSender = wallet.signTx(
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

    val expectedTx: Array[Byte] = rlp.encode(tx.tx.toRLPEncodable)

    val retTxId: ByteString = ByteString(Hex.decode("112233"))

    (walletRpcClient.getNonce _).expects(config.walletAddress).returning(IO.pure(Right(currentNonce)))
    (walletRpcClient.sendTransaction _).expects(ByteString(expectedTx)).returning(IO.pure(Right(retTxId)))

    val res: Either[RpcError, ByteString] = walletService.sendFunds(wallet, Address("0x99")).unsafeRunSync()

    res shouldEqual Right(retTxId)

  }

  it should "failure the transaction when get timeout of getNonce" taggedAs (UnitTest, RPCTest) in new TestSetup {

    val timeout: ConnectionError = ConnectionError("timeout")
    (walletRpcClient.getNonce _).expects(config.walletAddress).returning(IO.pure(Left(timeout)))

    val res: Either[RpcError, ByteString] = walletService.sendFunds(wallet, Address("0x99")).unsafeRunSync()

    res shouldEqual Left(timeout)

  }

  it should "get wallet successful" taggedAs (UnitTest, RPCTest) in new TestSetup {
    (mockKeyStore.unlockAccount _).expects(config.walletAddress, config.walletPassword).returning(Right(wallet))

    val res: Either[KeyStoreError, Wallet] = walletService.getWallet.unsafeRunSync()

    res shouldEqual Right(wallet)
  }

  it should "wallet decryption failed" taggedAs (UnitTest, RPCTest) in new TestSetup {
    (mockKeyStore.unlockAccount _)
      .expects(config.walletAddress, config.walletPassword)
      .returning(Left(DecryptionFailed))

    val res: Either[KeyStoreError, Wallet] = walletService.getWallet.unsafeRunSync()

    res shouldEqual Left(DecryptionFailed)
  }

  trait TestSetup {
    val walletKeyPair: AsymmetricCipherKeyPair = generateKeyPair(new SecureRandom)
    val (prvKey, pubKey) = keyPairToByteStrings(walletKeyPair)
    val wallet: Wallet = Wallet(Address(crypto.kec256(pubKey)), prvKey)

    val walletRpcClient: WalletRpcClientApi = mock[WalletRpcClientApi]
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
