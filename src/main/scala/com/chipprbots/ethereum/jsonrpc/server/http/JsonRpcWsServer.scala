package com.chipprbots.ethereum.jsonrpc.server.http

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route

import scala.util.Failure
import scala.util.Success

import com.chipprbots.ethereum.jsonrpc.server.controllers.JsonRpcBaseController
import com.chipprbots.ethereum.jsonrpc.server.http.JsonRpcHttpServer.JwtAuthConfig
import com.chipprbots.ethereum.utils.Logger

/** Separate WebSocket JSON-RPC server on its own port.
  *
  * Follows geth/besu/core-geth pattern: WS is on a different port from HTTP RPC.
  * Default port 8552 (see network.conf ws.port).
  *
  * JWT authentication is shared with the HTTP server — both read from the same
  * `jwt-auth` config section (`network.rpc.http.jwt-auth`). The Bearer token is
  * validated on the HTTP upgrade request before the WebSocket handshake completes.
  */
class JsonRpcWsServer(
    jsonRpcController: JsonRpcBaseController,
    subscriptionManager: ActorRef,
    wsConfig: JsonRpcWsServer.WsConfig,
    jwtAuthConfig: JwtAuthConfig
)(implicit actorSystem: ActorSystem)
    extends Logger {

  private val wsHandler = new WebSocketHandler(
    jsonRpcController,
    subscriptionManager,
    wsConfig.notificationBufferSize
  )

  private val jwtAuth = new JwtAuth(jwtAuthConfig)

  val route: Route =
    pathEndOrSingleSlash {
      jwtAuth {
        handleWebSocketMessages(wsHandler.createFlow())
      }
    }

  def run(): Unit = {
    implicit val ec = actorSystem.dispatcher
    Http(actorSystem)
      .newServerAt(wsConfig.interface, wsConfig.port)
      .bind(route)
      .onComplete {
        case Success(binding) =>
          val authStatus = if (jwtAuthConfig.enabled) "JWT enabled" else "no auth"
          log.info("JSON-RPC WebSocket server listening on {} ({})", binding.localAddress, authStatus)
        case Failure(ex) =>
          log.error("Cannot start JSON-RPC WebSocket server", ex)
      }
  }
}

object JsonRpcWsServer {

  trait WsConfig extends com.chipprbots.ethereum.jsonrpc.subscription.SubscriptionManager.Config {
    val enabled: Boolean
    val interface: String
    val port: Int
    val maxActiveConnections: Int
    val maxSubscriptionsPerConnection: Int
    val notificationBufferSize: Int
  }

  object WsConfig {
    def apply(fukuiiConfig: com.typesafe.config.Config): WsConfig = {
      val wsConf = fukuiiConfig.getConfig("network.rpc.ws")
      new WsConfig {
        override val enabled: Boolean = wsConf.getBoolean("enabled")
        override val interface: String = wsConf.getString("interface")
        override val port: Int = wsConf.getInt("port")
        override val maxActiveConnections: Int = wsConf.getInt("max-active-connections")
        override val maxSubscriptionsPerConnection: Int = wsConf.getInt("max-subscriptions-per-connection")
        override val notificationBufferSize: Int = wsConf.getInt("notification-buffer-size")
      }
    }
  }
}
