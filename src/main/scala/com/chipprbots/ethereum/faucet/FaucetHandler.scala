package com.chipprbots.ethereum.faucet

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.Props
import akka.util.ByteString

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.faucet.FaucetHandler.WalletException
import com.chipprbots.ethereum.faucet.FaucetStatus.WalletAvailable
import com.chipprbots.ethereum.faucet.jsonrpc.WalletService
import com.chipprbots.ethereum.keystore.KeyStore.KeyStoreError
import com.chipprbots.ethereum.keystore.Wallet

class FaucetHandler(walletService: WalletService, config: FaucetConfig)(implicit runtime: IORuntime)
    extends Actor
    with ActorLogging {

  import FaucetHandler.FaucetHandlerMsg._
  import FaucetHandler.FaucetHandlerResponse._

  override def preStart(): Unit =
    self ! Initialization

  override def receive: Receive = unavailable()

  private def unavailable(): Receive = {
    case Status =>
      sender() ! StatusResponse(FaucetStatus.FaucetUnavailable)

    case Initialization =>
      log.info("Initialization called (faucet unavailable)")
      walletService.getWallet.unsafeRunSync() match {
        case Left(error) =>
          log.error(s"Couldn't initialize wallet - error: $error")
          throw new WalletException(error)
        case Right(wallet) =>
          log.info("Faucet initialization succeeded")
          context.become(available(wallet))
      }
    case SendFunds(addressTo: Address) =>
      log.info(
        s"SendFunds called, to: $addressTo, value: ${config.txValue}, gas price: ${config.txGasPrice}," +
          s" gas limit: ${config.txGasLimit} (faucet unavailable)"
      )
      sender() ! FaucetIsUnavailable
  }

  private def available(wallet: Wallet): Receive = {
    case Status =>
      val respondTo = sender()
      respondTo ! StatusResponse(WalletAvailable)

    case Initialization =>
      log.debug("Initialization called (faucet available)")
      sender() ! FaucetIsAlreadyAvailable

    case SendFunds(addressTo: Address) =>
      log.info(
        s"SendFunds called, to: $addressTo, value: ${config.txValue}, gas price: ${config.txGasPrice}, gas limit: ${config.txGasLimit} (faucet available)"
      )
      val respondTo = sender()
      // We Only consider the request fail if we found out
      // wallet is not properly initialized
      walletService
        .sendFunds(wallet, addressTo)
        .map {
          case Right(txHash) =>
            respondTo ! TransactionSent(txHash)
          case Left(error) =>
            respondTo ! WalletRpcClientError(error.msg)
        }
        .unsafeRunAndForget()
  }
}

object FaucetHandler {

  sealed abstract class FaucetHandlerMsg
  object FaucetHandlerMsg {
    case object Status extends FaucetHandlerMsg
    case object Initialization extends FaucetHandlerMsg
    case class SendFunds(address: Address) extends FaucetHandlerMsg
  }
  sealed trait FaucetHandlerResponse
  object FaucetHandlerResponse {
    case class StatusResponse(status: FaucetStatus) extends FaucetHandlerResponse
    case object FaucetIsUnavailable extends FaucetHandlerResponse
    case object FaucetIsAlreadyAvailable extends FaucetHandlerResponse
    case class WalletRpcClientError(error: String) extends FaucetHandlerResponse
    case class TransactionSent(txHash: ByteString) extends FaucetHandlerResponse
  }

  class WalletException(keyStoreError: KeyStoreError) extends RuntimeException(keyStoreError.toString)

  def props(walletRpcClient: WalletService, config: FaucetConfig)(implicit runtime: IORuntime): Props = Props(
    new FaucetHandler(walletRpcClient, config)
  )

  val name: String = "FaucetHandler"
}
