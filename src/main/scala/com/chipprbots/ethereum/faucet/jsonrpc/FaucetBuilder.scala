package com.chipprbots.ethereum.faucet.jsonrpc

import org.apache.pekko.actor.ActorSystem

import cats.effect.unsafe.IORuntime

import scala.concurrent.Await
import scala.concurrent.ExecutionContextExecutor

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
      () => sslContext("faucet.rpc-client")
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
  self: ActorSystemBuilder & JsonRpcConfigBuilder & SecureRandomBuilder & FaucetJsonRpcHealthCheckBuilder &
    FaucetJsonRpcControllerBuilder & SSLContextBuilder =>

  val faucetJsonRpcHttpServer: Either[String, JsonRpcHttpServer] = JsonRpcHttpServer(
    faucetJsonRpcController,
    faucetJsonRpcHealthCheck,
    jsonRpcConfig.httpServerConfig,
    () => sslContext("fukuii.network.rpc.http")
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
      case Right(jsonRpcServer) => jsonRpcServer.run()
      case Left(error)          => throw new RuntimeException(s"$error")
