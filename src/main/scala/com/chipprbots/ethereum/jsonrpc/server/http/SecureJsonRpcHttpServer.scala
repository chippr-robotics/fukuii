package com.chipprbots.ethereum.jsonrpc.server.http

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.cors.scaladsl.model.HttpOriginMatcher
import org.apache.pekko.http.scaladsl.Http

import com.chipprbots.ethereum.jsonrpc.JsonRpcHealthChecker
import com.chipprbots.ethereum.jsonrpc.graphql.GraphQLService
import com.chipprbots.ethereum.jsonrpc.server.controllers.JsonRpcBaseController
import com.chipprbots.ethereum.jsonrpc.server.http.JsonRpcHttpServer.JsonRpcHttpServerConfig
import com.chipprbots.ethereum.tls.ListenerId
import com.chipprbots.ethereum.tls.TlsConfigError
import com.chipprbots.ethereum.tls.TlsServerContext
import com.chipprbots.ethereum.tls.TlsServerEngine
import com.chipprbots.ethereum.utils.Logger

class SecureJsonRpcHttpServer(
    val jsonRpcController: JsonRpcBaseController,
    val jsonRpcHealthChecker: JsonRpcHealthChecker,
    val config: JsonRpcHttpServerConfig,
    getTlsContext: () => Either[TlsConfigError, TlsServerContext],
    override val graphQLService: Option[GraphQLService] = None
)(implicit val actorSystem: ActorSystem)
    extends JsonRpcHttpServer
    with Logger:

  def run(): scala.concurrent.Future[Http.ServerBinding] = lifecycle.start()

  override protected def bind(): scala.concurrent.Future[Http.ServerBinding] =
    given ec: scala.concurrent.ExecutionContext = actorSystem.dispatcher

    val maybeHttpsContext = getTlsContext().map(TlsServerEngine.connectionContext)

    maybeHttpsContext match
      case Right(httpsContext) =>
        // The TLS session and the peer's address ride on the connection, so a refused handshake is counted and logged
        // with its address (spec 030 FR-017) and the identity rules can read the client certificate.
        val settings = TlsServerEngine.serverSettings(actorSystem)
        val handler =
          TlsServerEngine.observeHandshakes(ListenerId.RpcHttp, TlsServerEngine.routeHandler(route, settings))
        val bindingResultF =
          Http(actorSystem)
            .newServerAt(config.interface, config.port)
            .enableHttps(httpsContext)
            .withSettings(settings)
            .bindFlow(handler)

        // The failure is logged by the lifecycle, and whether it bound is said to the RPC view there (spec 029 FR-039).
        bindingResultF.foreach(serverBinding =>
          log.info(s"JSON RPC HTTPS server listening on ${serverBinding.localAddress}")
        )

        bindingResultF
      case Left(error) =>
        // A failed future, not a throw: `rpc start` runs this on the dashboard's thread. The lifecycle logs it and says
        // to the RPC view that it will not listen.
        scala.concurrent.Future.failed(new IllegalStateException(error.message))

  override def corsAllowedOrigins: HttpOriginMatcher = config.corsAllowedOrigins
