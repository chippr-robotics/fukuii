package com.chipprbots.ethereum.jsonrpc.server.http

import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Scheduler
import org.apache.pekko.actor.typed.scaladsl.AskPattern.*
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.AttributeKeys
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.model.ws.Message
import org.apache.pekko.http.scaladsl.model.ws.TextMessage
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.stream.KillSwitches
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.OverflowStrategy
import org.apache.pekko.stream.SharedKillSwitch
import org.apache.pekko.stream.scaladsl.Flow
import org.apache.pekko.stream.scaladsl.Sink
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.Timeout

import cats.effect.unsafe.IORuntime

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

import com.typesafe.config.ConfigValueType
import org.json4s.*
import org.json4s.native.JsonMethods.*
import org.json4s.native.Serialization

import com.chipprbots.ethereum.jsonrpc.JsonRpcError
import com.chipprbots.ethereum.jsonrpc.JsonRpcRequest
import com.chipprbots.ethereum.jsonrpc.RpcCallStats
import com.chipprbots.ethereum.jsonrpc.RpcNamespaceDenials
import com.chipprbots.ethereum.jsonrpc.SubscriptionManager
import com.chipprbots.ethereum.jsonrpc.SubscriptionManager.*
import com.chipprbots.ethereum.jsonrpc.serialization.JsonSerializers
import com.chipprbots.ethereum.jsonrpc.server.controllers.JsonRpcBaseController
import com.chipprbots.ethereum.tls.ListenerId
import com.chipprbots.ethereum.tls.TlsConfigError
import com.chipprbots.ethereum.tls.TlsServerContext
import com.chipprbots.ethereum.tls.TlsServerEngine
import com.chipprbots.ethereum.utils.Logger

/** WebSocket JSON-RPC server.
  *
  * Besu reference: ethereum/api/.../websocket/WebSocketService.java — WS connection lifecycle
  * ethereum/api/.../websocket/WebSocketMessageHandler.java — message routing
  * ethereum/api/.../websocket/methods/EthSubscribe.java — eth_subscribe handler
  * ethereum/api/.../websocket/methods/EthUnsubscribe.java — eth_unsubscribe handler
  *
  * Besu uses Vert.x HTTP server + WebSocketHandler. We use Pekko HTTP's handleWebSocketMessages directive which maps
  * naturally to the same flow: incoming messages → route → outgoing messages.
  *
  * eth_subscribe / eth_unsubscribe are routed directly to SubscriptionManager. All other JSON-RPC methods are forwarded
  * to jsonRpcController.handleRequest().
  *
  * Browser connections are accepted only from allowed origins: the `Origin` header is checked against
  * `fukuii.network.rpc.ws.allowed-origins` before the upgrade (see [[WsOriginPolicy]]). The key is optional; when it is
  * absent or empty, pages served from this machine (`localhost`, `127.0.0.1`, `[::1]`, any port) are allowed. Set it to
  * a list of origins, or a comma-separated string, to allow other pages; `*` allows every origin. Clients that send no
  * `Origin` header (CLIs, libraries) are not affected.
  */
class JsonRpcWsServer(
    jsonRpcController: JsonRpcBaseController,
    subscriptionManager: ActorRef[SubscriptionManager.Command],
    config: JsonRpcWsServer.JsonRpcWsServerConfig,
    tlsContext: () => Either[TlsConfigError, Option[TlsServerContext]] = () => Right(None),
    tlsCallers: Option[RpcCallers] = None
)(implicit system: ActorSystem)
    extends Logger:

  given mat: Materializer = Materializer(system)
  given ec: ExecutionContext = system.dispatcher
  given timeout: Timeout = Timeout(30.seconds)
  given scheduler: Scheduler = system.toTyped.scheduler
  given runtime: IORuntime = IORuntime.global
  given formats: Formats = DefaultFormats + JsonSerializers.RpcErrorJsonSerializer
  given serialization: Serialization.type = org.json4s.native.Serialization

  private val originPolicy: WsOriginPolicy = WsOriginPolicy(config.allowedOrigins)

  /** Who is served by which controller (spec 030 FR-020, FR-026): resolved once, at the upgrade, and kept for every
    * message and subscription of the connection.
    */
  private val callers: RpcCallers = tlsCallers.getOrElse(RpcCallers.none(jsonRpcController))

  /** The upgrade is refused with 403 when the request carries an `Origin` header that is not on the allow-list; the
    * connection is not registered with the SubscriptionManager. A request with no `Origin` header is not from a browser
    * and goes through. See [[WsOriginPolicy]]. A request that is not a WebSocket upgrade is answered with 400 and
    * registers nothing: [[buildWsFlow]] registers the connection, so it runs only once the upgrade is in hand.
    */
  val route: Route =
    (pathEndOrSingleSlash | path("ws")) {
      extractRequest { request =>
        val origins = request.headers.filter(_.is("origin")).map(_.value)
        if originPolicy.permits(origins) then
          (extractWebSocketUpgrade & optionalAttribute(AttributeKeys.sslSession)) { (upgrade, session) =>
            val controller = callers.forSession(session.map(_.session)).controller
            complete(upgrade.handleMessages(buildWsFlow(controller)))
          }
        else
          log.warn(
            s"Rejected WebSocket connection: origin not allowed: ${origins.map(WsOriginPolicy.sanitize).mkString(", ")}"
          )
          complete(StatusCodes.Forbidden, "Origin not allowed")
      }
    }

  /** Where the dashboard's RPC view is told whether the server is bound or was stopped; the node's own, and a test's
    * own where a test does not want to touch it.
    */
  protected def callStats: RpcCallStats = RpcCallStats.Node

  /** How long `stop` gives the open connections before it closes them: WebSocket connections are never finished, so
    * every one open when `stop` is called is closed by this time.
    */
  protected def stopDeadline: FiniteDuration = ServerLifecycle.DefaultHardDeadline

  /** The binding, kept: what stops this server and starts it again (`stop`, `start`), and whether it is bound
    * (`isBound`, `boundAddress`). Spec 029 FR-052.
    */
  lazy val lifecycle: ServerLifecycle =
    new ServerLifecycle(RpcCallStats.Ws, () => bind(), callStats, stopDeadline, () => closeConnections())

  // Terminating the binding does not close an upgraded WebSocket connection (measured: it stays open, and counted, for
  // as long as the client keeps it), so each connection's flow runs through the switch of the run it was opened in, and
  // `stop` shuts that switch down. A run is one bind: `bind` makes the next run's switch, so a connection opened after a
  // start is never closed by the previous stop.
  @volatile private var connections: SharedKillSwitch = KillSwitches.shared("json-rpc-ws-connections")

  private def closeConnections(): Unit = connections.shutdown()

  private val openCount = new AtomicInteger(0)

  /** The WebSocket connections open now (upgraded, and not yet closed), for the preview of `rpc stop ws`. */
  def openConnections: Int = openCount.get()

  def run(): Unit =
    // The outcome is logged, and said to the RPC view, by the lifecycle.
    val _ = lifecycle.start()

  /** Whether the listener speaks TLS (WSS), from its `tls` block (spec 030 FR-014). */
  def isTls: Boolean = config.tlsEnabled

  private def bind(): Future[Http.ServerBinding] =
    // Read on every bind, so `rpc start ws` from the dashboard takes the material as it is now. A TLS context that
    // cannot be built is a failed bind, never a plaintext listener (FR-012).
    tlsContext() match
      case Left(error) => Future.failed(new IllegalStateException(error.message))
      case Right(tls) =>
        connections = KillSwitches.shared("json-rpc-ws-connections")
        val server = Http(system).newServerAt(config.interface, config.port)
        val bindingF = tls match
          case None         => server.bind(route)
          case Some(secure) =>
            // As on the HTTP listener: the TLS session on the upgrade request, which the connection keeps for its
            // life, and a refused handshake counted and logged with its address (FR-017, FR-026).
            val settings = TlsServerEngine.serverSettings(system)
            server
              .enableHttps(TlsServerEngine.connectionContext(secure))
              .withSettings(settings)
              .bindFlow(
                TlsServerEngine.observeHandshakes(ListenerId.RpcWs, TlsServerEngine.routeHandler(route, settings))
              )
        val scheme = if tls.isDefined then "WSS" else "WS"
        bindingF.foreach(b => log.info(s"JSON RPC $scheme server listening on ${b.localAddress}"))
        bindingF

  private def buildWsFlow(controller: JsonRpcBaseController): Flow[Message, Message, Any] =
    val connId = UUID.randomUUID().toString

    // Outbound queue: SubscriptionManager pushes JSON strings here
    val (queue, outboundSource) = Source
      .queue[String](256, OverflowStrategy.dropHead)
      .preMaterialize()

    // Counted where the connection is registered, before the manager hears of it, and uncounted once it ends: see the
    // `watchTermination` below.
    openCount.incrementAndGet()
    subscriptionManager ! RegisterConnection(connId, queue)

    // Inbound sink: parse JSON-RPC, route, push response to queue
    val inboundSink: Sink[Message, Any] = Sink.foreach[Message] {
      case TextMessage.Strict(text) =>
        handleWsMessage(text, connId, queue, controller)
      case TextMessage.Streamed(stream) =>
        stream.runFold("")(_ + _).foreach(text => handleWsMessage(text, connId, queue, controller))
      case _ => ()
    }

    val outboundMessages = outboundSource.map(TextMessage.Strict(_))

    // Shut down by `stop`: it completes the inbound side, which ends the coupled flow, which makes the server send the
    // close frame; `watchTermination` below then tells the manager.
    val killSwitch = connections
    Flow[Message]
      .via(killSwitch.flow[Message])
      .via(Flow.fromSinkAndSourceCoupled(inboundSink, outboundMessages))
      .watchTermination() { (mat, doneFuture) =>
        doneFuture.onComplete { _ =>
          // Before the message, so that whoever sees the connection closed sees it uncounted.
          openCount.decrementAndGet()
          subscriptionManager ! ConnectionClosed(connId)
        }
        mat
      }

  private def handleWsMessage(
      text: String,
      connId: String,
      queue: org.apache.pekko.stream.scaladsl.SourceQueueWithComplete[String],
      controller: JsonRpcBaseController
  ): Unit =
    def sendResponse(json: String): Unit = queue.offer(json)

    def errorResponse(id: JValue, code: Int, message: String): String =
      val resp = JObject(
        "jsonrpc" -> JString("2.0"),
        "id" -> id,
        "error" -> JObject("code" -> JInt(code), "message" -> JString(message))
      )
      compact(render(resp))

    val parsed =
      try Some(parse(text))
      catch case _: Exception => None
    parsed match
      case None =>
        sendResponse(errorResponse(JNull, -32700, "Parse error"))

      case Some(json) =>
        val id = (json \ "id").toOption.getOrElse(JNull)
        val method = (json \ "method") match
          case JString(m) => m
          case _          => ""
        val params = (json \ "params").toOption

        method match
          // eth_subscribe and eth_unsubscribe are in the `eth` namespace, as in go-ethereum: a WebSocket endpoint that
          // does not serve `eth` answers them like any other method it does not serve.
          case "eth_subscribe" | "eth_unsubscribe" if !controller.enabledApis.contains("eth") =>
            // answered here, before the controller, so the denial is counted here too (spec 030 FR-024)
            controller.identityRule.foreach(rule => RpcNamespaceDenials.record(rule, "eth", method, "ws"))
            sendResponse(errorResponse(id, JsonRpcError.MethodNotFound.code, JsonRpcError.MethodNotFound.message))

          case "eth_subscribe" =>
            val subType = params.flatMap {
              case JArray(JString(t) :: _) => Some(t)
              case _                       => None
            }
            val subParams = params.flatMap {
              case JArray(_ :: p :: _) => Some(p)
              case _                   => None
            }
            subType match
              case None =>
                sendResponse(errorResponse(id, -32602, "Invalid params: missing subscription type"))
              case Some(t) =>
                subscriptionManager
                  .ask[SubscribeResponse](replyTo => Subscribe(connId, t, subParams, replyTo))
                  .foreach {
                    case SubscribeResponse(Right(subId)) =>
                      val hex = "0x" + subId.toHexString
                      sendResponse(
                        compact(
                          render(
                            JObject(
                              "jsonrpc" -> JString("2.0"),
                              "id" -> id,
                              "result" -> JString(hex)
                            )
                          )
                        )
                      )
                    case SubscribeResponse(Left(err)) =>
                      sendResponse(errorResponse(id, -32602, err))
                  }

          case "eth_unsubscribe" =>
            val subIdOpt = params.flatMap {
              case JArray(JString(s) :: _) =>
                val hex = s.stripPrefix("0x").stripPrefix("0X")
                try Some(java.lang.Long.parseLong(hex, 16))
                catch case _: Exception => None
              case _ => None
            }
            subIdOpt match
              case None =>
                sendResponse(errorResponse(id, -32602, "Invalid params: missing subscription id"))
              case Some(subId) =>
                subscriptionManager.ask[UnsubscribeResponse](replyTo => Unsubscribe(connId, subId, replyTo)).foreach {
                  case UnsubscribeResponse(found) =>
                    sendResponse(
                      compact(
                        render(
                          JObject(
                            "jsonrpc" -> JString("2.0"),
                            "id" -> id,
                            "result" -> JBool(found)
                          )
                        )
                      )
                    )
                }

          case "" =>
            sendResponse(errorResponse(id, -32600, "Invalid request"))

          case _ =>
            // All other JSON-RPC methods: delegate to standard controller
            val requestOpt =
              try Some(json.extract[JsonRpcRequest])
              catch case _: Exception => None
            requestOpt match
              case None =>
                sendResponse(errorResponse(id, -32600, "Invalid request"))
              case Some(req) =>
                controller.handleRequest(req).unsafeToFuture().foreach { resp =>
                  sendResponse(Serialization.write(resp))
                }

object JsonRpcWsServer:

  trait JsonRpcWsServerConfig:
    val enabled: Boolean
    val interface: String
    val port: Int

    /** Origins that may open a WebSocket connection from a browser (`fukuii.network.rpc.ws.allowed-origins`). Empty
      * means the default of [[WsOriginPolicy.DefaultAllowedOrigins]]; `*` allows every origin.
      */
    val allowedOrigins: Seq[String] = Seq.empty

    /** `network.rpc.ws.tls.enabled` (spec 030); false where a configuration does not carry the block. */
    val tlsEnabled: Boolean = false

  object JsonRpcWsServerConfig:
    def apply(fukuiiConfig: com.typesafe.config.Config): JsonRpcWsServerConfig =
      val wsConfig = fukuiiConfig.getConfig("network.rpc.ws")
      new JsonRpcWsServerConfig:
        override val enabled: Boolean = wsConfig.getBoolean("enabled")
        override val interface: String = wsConfig.getString("interface")
        override val port: Int = wsConfig.getInt("port")
        override val allowedOrigins: Seq[String] = parseAllowedOrigins(wsConfig)
        override val tlsEnabled: Boolean = wsConfig.hasPath("tls.enabled") && wsConfig.getBoolean("tls.enabled")

    /** Reads the optional `allowed-origins` key of the `ws` block: a list of strings, or one comma-separated string.
      * Absent (or null) gives an empty list, which selects the default.
      */
    private[http] def parseAllowedOrigins(wsConfig: com.typesafe.config.Config): Seq[String] =
      val key = "allowed-origins"
      if !wsConfig.hasPath(key) then Seq.empty
      else
        wsConfig.getValue(key).valueType match
          case ConfigValueType.LIST => wsConfig.getStringList(key).asScala.toSeq
          case _                    => wsConfig.getString(key).split(',').toSeq
