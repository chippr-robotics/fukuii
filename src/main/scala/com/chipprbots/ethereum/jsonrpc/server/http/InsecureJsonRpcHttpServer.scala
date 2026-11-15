package com.chipprbots.ethereum.jsonrpc.server.http

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.cors.scaladsl.model.HttpOriginMatcher
import org.apache.pekko.http.scaladsl.Http

import com.chipprbots.ethereum.jsonrpc.*
import com.chipprbots.ethereum.jsonrpc.graphql.GraphQLService
import com.chipprbots.ethereum.jsonrpc.server.controllers.JsonRpcBaseController
import com.chipprbots.ethereum.jsonrpc.server.http.JsonRpcHttpServer.JsonRpcHttpServerConfig
import com.chipprbots.ethereum.utils.Logger

class InsecureJsonRpcHttpServer(
    val jsonRpcController: JsonRpcBaseController,
    val jsonRpcHealthChecker: JsonRpcHealthChecker,
    val config: JsonRpcHttpServerConfig,
    override val graphQLService: Option[GraphQLService] = None
)(implicit val actorSystem: ActorSystem)
    extends JsonRpcHttpServer
    with Logger:

  def run(): scala.concurrent.Future[Http.ServerBinding] = lifecycle.start()

  override protected def bind(): scala.concurrent.Future[Http.ServerBinding] =
    given ec: scala.concurrent.ExecutionContext = actorSystem.dispatcher

    val bindingResultF = Http(actorSystem).newServerAt(config.interface, config.port).bind(route)

    // The failure is logged by the lifecycle, and whether it bound is said to the RPC view there (spec 029 FR-039).
    bindingResultF.foreach(serverBinding =>
      log.info(s"JSON RPC HTTP server listening on ${serverBinding.localAddress}")
    )

    bindingResultF

  override def corsAllowedOrigins: HttpOriginMatcher = config.corsAllowedOrigins
