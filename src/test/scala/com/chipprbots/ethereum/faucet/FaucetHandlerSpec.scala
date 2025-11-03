package com.chipprbots.ethereum.faucet

import java.security.SecureRandom

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.Props
import org.apache.pekko.pattern.gracefulStop
import org.apache.pekko.testkit.ImplicitSender
import org.apache.pekko.testkit.TestKit
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.util.ByteString

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import scala.concurrent.ExecutionContext

import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.util.encoders.Hex
import org.scalamock.scalatest.MockFactory
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.freespec.AnyFreeSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.NormalPatience
import com.chipprbots.ethereum.WithActorSystemShutDown
import com.chipprbots.ethereum.crypto
import com.chipprbots.ethereum.crypto.generateKeyPair
import com.chipprbots.ethereum.crypto.keyPairToByteStrings
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.faucet.FaucetHandler.FaucetHandlerMsg
import com.chipprbots.ethereum.faucet.FaucetHandler.FaucetHandlerResponse
import com.chipprbots.ethereum.faucet.jsonrpc.WalletService
import com.chipprbots.ethereum.jsonrpc.client.RpcClient.ParserError
import com.chipprbots.ethereum.jsonrpc.client.RpcClient.RpcClientError
import com.chipprbots.ethereum.keystore.KeyStore.DecryptionFailed
import com.chipprbots.ethereum.keystore.Wallet

class FaucetHandlerSpec
    extends TestKit(ActorSystem("ActorSystem_DebugFaucetHandlerSpec"))
    with AnyFreeSpecLike
    with ImplicitSender
    with WithActorSystemShutDown
    with Matchers
    with MockFactory
    with ScalaFutures
    with NormalPatience {

  "Faucet Handler" - {
    "without wallet unlocked" - {

      "should not respond in case wallet unlock fails" in new TestSetup {
        withUnavailableFaucet {
          faucetHandler ! FaucetHandlerMsg.Initialization
          sender.expectNoMessage()
        }
      }

      "shouldn't send funds if the Faucet isn't initialized" in new TestSetup {
        sender.send(faucetHandler, FaucetHandlerMsg.Status)
        sender.expectMsg(FaucetHandlerResponse.StatusResponse(FaucetStatus.FaucetUnavailable))

        sender.send(faucetHandler, FaucetHandlerMsg.SendFunds(paymentAddress))
        sender.expectMsg(FaucetHandlerResponse.FaucetIsUnavailable)

        stopController()
      }
    }

    "with wallet unlocked" - {

      "should respond that it is available if it was initialized successfully" in new TestSetup {
        withInitializedFaucet {
          sender.send(faucetHandler, FaucetHandlerMsg.Initialization)
          sender.expectMsg(FaucetHandlerResponse.FaucetIsAlreadyAvailable)
        }
      }

      "should respond that it is available when ask the status if it was initialized successfully" in new TestSetup {
        withInitializedFaucet {
          sender.send(faucetHandler, FaucetHandlerMsg.Status)
          sender.expectMsg(FaucetHandlerResponse.StatusResponse(FaucetStatus.WalletAvailable))
        }
      }

      "should be able to paid if it was initialized successfully" in new TestSetup {
        withInitializedFaucet {
          val retTxId = ByteString(Hex.decode("112233"))
          (walletService.sendFunds _).expects(wallet, paymentAddress).returning(IO.pure(Right(retTxId)))

          sender.send(faucetHandler, FaucetHandlerMsg.SendFunds(paymentAddress))
          sender.expectMsg(FaucetHandlerResponse.TransactionSent(retTxId))
        }
      }

      "should failed the payment if don't can parse the payload" in new TestSetup {
        withInitializedFaucet {
          val errorMessage = RpcClientError("parser error")
          (walletService.sendFunds _)
            .expects(wallet, paymentAddress)
            .returning(IO.pure(Left(errorMessage)))

          sender.send(faucetHandler, FaucetHandlerMsg.SendFunds(paymentAddress))
          sender.expectMsg(FaucetHandlerResponse.WalletRpcClientError(errorMessage.msg))
        }
      }

      "should failed the payment if throw rpc client error" in new TestSetup {
        withInitializedFaucet {
          val errorMessage = ParserError("error parser")
          (walletService.sendFunds _)
            .expects(wallet, paymentAddress)
            .returning(IO.pure(Left(errorMessage)))

          sender.send(faucetHandler, FaucetHandlerMsg.SendFunds(paymentAddress))
          sender.expectMsg(FaucetHandlerResponse.WalletRpcClientError(errorMessage.msg))
        }
      }
    }
  }

  implicit val ec: ExecutionContext = ExecutionContext.global
  implicit val runtime: IORuntime = IORuntime.global

  trait TestSetup extends FaucetConfigBuilder {
    val walletService: WalletService = mock[WalletService]
    val paymentAddress: Address = Address("0x99")

    val faucetHandler: ActorRef = system.actorOf(FaucetHandlerFake.props(walletService, faucetConfig))

    val walletKeyPair: AsymmetricCipherKeyPair = generateKeyPair(new SecureRandom)
    val (prvKey, pubKey) = keyPairToByteStrings(walletKeyPair)
    val wallet: Wallet = Wallet(Address(crypto.kec256(pubKey)), prvKey)

    val sender: TestProbe = TestProbe()

    def withUnavailableFaucet(behaviour: => Unit): Unit = {
      (() => walletService.getWallet).expects().returning(IO.pure(Left(DecryptionFailed)))

      sender.send(faucetHandler, FaucetHandlerMsg.Status)
      sender.expectMsg(FaucetHandlerResponse.StatusResponse(FaucetStatus.FaucetUnavailable))

      behaviour
      stopController()
    }

    def withInitializedFaucet(behaviour: => Unit): Unit = {
      (() => walletService.getWallet).expects().returning(IO.pure(Right(wallet)))

      faucetHandler ! FaucetHandlerMsg.Initialization

      sender.send(faucetHandler, FaucetHandlerMsg.Status)
      sender.expectMsg(FaucetHandlerResponse.StatusResponse(FaucetStatus.WalletAvailable))
      behaviour
      stopController()
    }

    def stopController(): Unit =
      awaitCond(gracefulStop(faucetHandler, actorAskTimeout.duration).futureValue)
  }
}

class FaucetHandlerFake(walletService: WalletService, config: FaucetConfig)(implicit runtime: IORuntime)
    extends FaucetHandler(walletService, config) {
  override def preStart(): Unit = {}
}

object FaucetHandlerFake {
  def props(walletRpcClient: WalletService, config: FaucetConfig)(implicit runtime: IORuntime): Props = Props(
    new FaucetHandlerFake(walletRpcClient, config)
  )
}
