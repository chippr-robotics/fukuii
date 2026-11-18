package com.chipprbots.ethereum.faucet.jsonrpc

import org.apache.pekko.actor.ActorSystem

import cats.effect.unsafe.IORuntime

import scala.concurrent.Await
import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration.*

import com.typesafe.config.ConfigFactory

import com.chipprbots.ethereum.faucet.FaucetConfigBuilder
import com.chipprbots.ethereum.faucet.FaucetSupervisor
import com.chipprbots.ethereum.jsonrpc.server.controllers.ApisBase
import com.chipprbots.ethereum.jsonrpc.server.controllers.JsonRpcBaseController.JsonRpcConfig
import com.chipprbots.ethereum.jsonrpc.server.http.JsonRpcHttpServer
import com.chipprbots.ethereum.keystore.KeyStoreImpl
import com.chipprbots.ethereum.security.SSLContextBuilder
import com.chipprbots.ethereum.security.SecureRandomBuilder
import com.chipprbots.ethereum.utils.KeyStoreConfig
import com.chipprbots.ethereum.utils.Logger

trait ActorSystemBuilder:
  def systemName: String
  given system: ActorSystem = ActorSystem(systemName, ConfigFactory.load())

trait FaucetControllerBuilder:
  self: FaucetConfigBuilder & ActorSystemBuilder =>

  given ec: ExecutionContextExecutor = system.dispatcher
  given runtime: IORuntime = IORuntime.global

trait FaucetRpcServiceBuilder:
  self: FaucetConfigBuilder & FaucetControllerBuilder & ActorSystemBuilder & SecureRandomBuilder & ShutdownHookBuilder &
    SSLContextBuilder =>

  val keyStore =
    new KeyStoreImpl(
      KeyStoreConfig.customKeyStoreConfig(faucetConfig.keyStoreDir),
      secureRandom
    )

  val walletRpcClient: WalletRpcClient =
    new WalletRpcClient(
      faucetConfig.rpcClient.address,
      faucetConfig.rpcClient.timeout,
      () => sslContext(rawConfig, "faucet.rpc-client")
    )
  val walletService = new WalletService(walletRpcClient, keyStore, faucetConfig)
  val faucetSupervisor: FaucetSupervisor = new FaucetSupervisor(walletService, faucetConfig, shutdown)
  val faucetRpcService = new FaucetRpcService(faucetConfig, faucetSupervisor.handler)

trait FaucetJsonRpcHealthCheckBuilder:
  self: FaucetRpcServiceBuilder =>

  val faucetJsonRpcHealthCheck = new FaucetJsonRpcHealthCheck(faucetRpcService)

trait ApisBuilder extends ApisBase:
  object Apis:
    val Faucet = "faucet"

  override def available: List[String] = List(Apis.Faucet)

trait JsonRpcConfigBuilder:
  self: FaucetConfigBuilder & ApisBuilder =>

  lazy val availableApis: List[String] = available
  lazy val jsonRpcConfig: JsonRpcConfig = JsonRpcConfig(rawFukuiiConfig, availableApis)
  lazy val api = Apis

trait FaucetJsonRpcControllerBuilder:
  self: JsonRpcConfigBuilder & FaucetRpcServiceBuilder & ActorSystemBuilder =>

  val faucetJsonRpcController = new FaucetJsonRpcController(faucetRpcService, jsonRpcConfig, system)

trait FaucetJsonRpcHttpServerBuilder:
  self: ActorSystemBuilder & FaucetConfigBuilder & JsonRpcConfigBuilder & SecureRandomBuilder &
    FaucetJsonRpcHealthCheckBuilder & FaucetJsonRpcControllerBuilder & SSLContextBuilder =>

  val faucetJsonRpcHttpServer: Either[String, JsonRpcHttpServer] = JsonRpcHttpServer(
    faucetJsonRpcController,
    faucetJsonRpcHealthCheck,
    jsonRpcConfig.httpServerConfig,
    () => sslContext(rawFukuiiConfig, "network.rpc.http", "fukuii")
  )

trait ShutdownHookBuilder:
  self: ActorSystemBuilder & FaucetConfigBuilder & Logger =>

  def shutdown: () => Unit = () =>
    Await.ready(
      system
        .terminate()
        .map(
          _ ->
            log.info("actor system finished")
        )(using system.dispatcher),
      faucetConfig.shutdownTimeout
    )

class FaucetServer
    extends ActorSystemBuilder
    with FaucetConfigBuilder
    with ApisBuilder
    with JsonRpcConfigBuilder
    with SecureRandomBuilder
    with SSLContextBuilder
    with FaucetControllerBuilder
    with FaucetRpcServiceBuilder
    with FaucetJsonRpcHealthCheckBuilder
    with FaucetJsonRpcControllerBuilder
    with FaucetJsonRpcHttpServerBuilder
    with ShutdownHookBuilder
    with Logger:

  override def systemName: String = "Faucet-system"

  def start(): Unit =
    log.info("About to start Faucet JSON-RPC server")
    startJsonRpcHttpServer()

  private def startJsonRpcHttpServer() =
    faucetJsonRpcHttpServer match
      case Right(jsonRpcServer) =>
        // Awaited: a bind that fails (a TLS context that cannot be built, an address in use) stops the faucet, it is
        // not left to a future nobody reads (B-148).
        FaucetServer.awaitBind(jsonRpcServer.run(), faucetConfig.shutdownTimeout.min(10.seconds)) match
          case Right(binding) => log.info(s"Faucet JSON-RPC server bound to ${binding.localAddress}")
          case Left(message)  => throw new IllegalStateException(message)
      case Left(error) => throw new RuntimeException(s"$error")

object FaucetServer:

  /** Waits for the faucet's HTTP bind; a failure is the message to stop with, naming the key to fix. */
  def awaitBind[A](bind: => scala.concurrent.Future[A], timeout: FiniteDuration): Either[String, A] =
    try Right(Await.result(bind, timeout))
    catch
      case scala.util.control.NonFatal(e) =>
        Left(
          s"Refusing to start: the faucet JSON-RPC server cannot start (${e.getClass.getSimpleName}: ${e.getMessage}). " +
            "Check fukuii.network.rpc.http (mode, interface, port, certificate)."
        )
