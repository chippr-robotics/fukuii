package com.chipprbots.ethereum.faucet.jsonrpc

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.TestKit
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.util.ByteString

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import scala.concurrent.duration._

import org.bouncycastle.util.encoders.Hex
import org.scalactic.TypeCheckedTripleEquals
import org.scalamock.scalatest.MockFactory
import org.scalatest.OptionValues
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.NormalPatience
import com.chipprbots.ethereum.WithActorSystemShutDown
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.faucet.FaucetConfig
import com.chipprbots.ethereum.faucet.FaucetHandler.FaucetHandlerMsg
import com.chipprbots.ethereum.faucet.FaucetHandler.FaucetHandlerResponse.FaucetIsUnavailable
import com.chipprbots.ethereum.faucet.FaucetHandler.FaucetHandlerResponse.StatusResponse
import com.chipprbots.ethereum.faucet.FaucetHandler.FaucetHandlerResponse.TransactionSent
import com.chipprbots.ethereum.faucet.FaucetHandler.FaucetHandlerResponse.WalletRpcClientError
import com.chipprbots.ethereum.faucet.FaucetStatus.WalletAvailable
import com.chipprbots.ethereum.faucet.RpcClientConfig
import com.chipprbots.ethereum.faucet.SupervisorConfig
import com.chipprbots.ethereum.faucet.jsonrpc.FaucetDomain.SendFundsRequest
import com.chipprbots.ethereum.faucet.jsonrpc.FaucetDomain.StatusRequest
import com.chipprbots.ethereum.jsonrpc.JsonRpcError
import com.chipprbots.ethereum.testing.ActorsTesting.simpleAutoPilot
import com.chipprbots.ethereum.testing.Tags._

class FaucetRpcServiceSpec
    extends TestKit(ActorSystem("ActorSystem_DebugFaucetRpcServiceSpec"))
    with AnyFlatSpecLike
    with WithActorSystemShutDown
    with Matchers
    with ScalaFutures
    with OptionValues
    with MockFactory
    with NormalPatience
    with TypeCheckedTripleEquals {

  implicit val runtime: IORuntime = IORuntime.global

  "FaucetRpcService" should "answer txHash correctly when the wallet is available and the requested send funds be successfully" in new TestSetup {
    val address: Address = Address("0x00")
    val request: SendFundsRequest = SendFundsRequest(address)
    val txHash: ByteString = ByteString(Hex.decode("112233"))

    faucetHandler.setAutoPilot(simpleAutoPilot { case FaucetHandlerMsg.SendFunds(`address`) =>
      TransactionSent(txHash)
    })
    faucetRpcService.sendFunds(request).unsafeRunSync() match {
      case Left(error)     => fail(s"failure with error: $error")
      case Right(response) => response.txId shouldBe txHash
    }
  }

  it should "answer WalletRpcClientError when the wallet is available and the requested send funds be failure" in new TestSetup {
    val address: Address = Address("0x00")
    val request: SendFundsRequest = SendFundsRequest(address)
    val clientError: String = "Parser error"

    faucetHandler.setAutoPilot(simpleAutoPilot { case FaucetHandlerMsg.SendFunds(`address`) =>
      WalletRpcClientError(clientError)
    })
    faucetRpcService.sendFunds(request).unsafeRunSync() match {
      case Right(_)    => fail()
      case Left(error) => error shouldBe JsonRpcError.LogicError(s"Faucet error: $clientError")
    }
  }

  it should "answer FaucetIsUnavailable when tried to send funds and the wallet is unavailable" in new TestSetup {
    val address: Address = Address("0x00")
    val request: SendFundsRequest = SendFundsRequest(address)

    faucetHandler.setAutoPilot(simpleAutoPilot { case FaucetHandlerMsg.SendFunds(`address`) =>
      FaucetIsUnavailable
    })
    faucetRpcService.sendFunds(request).unsafeRunSync() match {
      case Right(_) => fail()
      case Left(error) =>
        error shouldBe JsonRpcError.LogicError("Faucet is unavailable: Please try again in a few more seconds")
    }
  }

  it should "answer FaucetIsUnavailable when tried to get status and the wallet is unavailable" in new TestSetup {
    faucetHandler.setAutoPilot(simpleAutoPilot { case FaucetHandlerMsg.Status =>
      FaucetIsUnavailable
    })
    faucetRpcService.status(StatusRequest()).unsafeRunSync() match {
      case Right(_) => fail()
      case Left(error) =>
        error shouldBe JsonRpcError.LogicError("Faucet is unavailable: Please try again in a few more seconds")
    }
  }

  it should "answer WalletAvailable when tried to get status and the wallet is available" in new TestSetup {
    faucetHandler.setAutoPilot(simpleAutoPilot { case FaucetHandlerMsg.Status =>
      StatusResponse(WalletAvailable)
    })
    faucetRpcService.status(StatusRequest()).unsafeRunSync() match {
      case Left(error)     => fail(s"failure with error: $error")
      case Right(response) => response shouldBe FaucetDomain.StatusResponse(WalletAvailable)
    }
  }

  it should "answer internal error when tried to send funds but the Faucet Handler is disable" in new TestSetup {
    val address: Address = Address("0x00")
    val request: SendFundsRequest = SendFundsRequest(address)

    faucetRpcServiceWithoutFaucetHandler.sendFunds(request).unsafeRunSync() match {
      case Right(_) => fail()
      case Left(error) =>
        error shouldBe JsonRpcError.InternalError
    }
  }

  it should "answer internal error when tried to get status but the Faucet Handler is disable" in new TestSetup {
    val address: Address = Address("0x00")
    SendFundsRequest(address)

    faucetRpcServiceWithoutFaucetHandler.status(StatusRequest()).unsafeRunSync() match {
      case Right(_) => fail()
      case Left(error) =>
        error shouldBe JsonRpcError.InternalError
    }
  }

  class TestSetup(implicit system: ActorSystem) {

    val config: FaucetConfig = FaucetConfig(
      walletAddress = Address("0x99"),
      walletPassword = "",
      txGasPrice = 10,
      txGasLimit = 20,
      txValue = 1,
      rpcClient = RpcClientConfig(address = "", timeout = 10.seconds),
      keyStoreDir = "",
      handlerTimeout = 10.seconds,
      actorCommunicationMargin = 10.seconds,
      supervisor = mock[SupervisorConfig],
      shutdownTimeout = 15.seconds
    )

    val faucetHandler: TestProbe = TestProbe()

    val faucetRpcService: FaucetRpcService = new FaucetRpcService(config) {
      override def selectFaucetHandler()(implicit system: ActorSystem): IO[ActorRef] =
        IO(faucetHandler.ref)
    }

    val faucetRpcServiceWithoutFaucetHandler: FaucetRpcService = new FaucetRpcService(config) {
      override def selectFaucetHandler()(implicit system: ActorSystem): IO[ActorRef] =
        IO.raiseError(new RuntimeException("time out"))
    }
  }

}
