package com.chipprbots.ethereum.jsonrpc.server.http

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.cors.scaladsl.model.HttpOriginMatcher
import org.apache.pekko.http.scaladsl.Http

import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Failure
import scala.util.Success

import com.chipprbots.ethereum.jsonrpc._
import com.chipprbots.ethereum.jsonrpc.server.controllers.JsonRpcBaseController
import com.chipprbots.ethereum.jsonrpc.server.http.JsonRpcHttpServer.JsonRpcHttpServerConfig
import com.chipprbots.ethereum.utils.Logger

class InsecureJsonRpcHttpServer(
    val jsonRpcController: JsonRpcBaseController,
    val jsonRpcHealthChecker: JsonRpcHealthChecker,
    val config: JsonRpcHttpServerConfig
)(implicit val actorSystem: ActorSystem)
    extends JsonRpcHttpServer
    with Logger {

  def run(): Unit = {
    val bindingResultF = Http(actorSystem).newServerAt(config.interface, config.port).bind(route)

    bindingResultF.onComplete {
      case Success(serverBinding) => log.info(s"JSON RPC HTTP server listening on ${serverBinding.localAddress}")
      case Failure(ex)            => log.error("Cannot start JSON HTTP RPC server", ex)
    }
  }

  override def corsAllowedOrigins: HttpOriginMatcher = config.corsAllowedOrigins
}
